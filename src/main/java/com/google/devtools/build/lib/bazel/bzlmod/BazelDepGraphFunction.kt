// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code

/**
 * This function runs Bazel module resolution, extracts the dependency graph from it and creates a
 * value containing all Bazel modules, along with a few lookup maps that help with further usage. By
 * this stage, module extensions are not evaluated yet.
 */
class BazelDepGraphFunction : SkyFunction {
    @Throws(BazelDepGraphFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val selectionResult: BazelModuleResolutionValue? =
            env.getValue(BazelModuleResolutionValue.Companion.KEY) as BazelModuleResolutionValue?
        if (env.valuesMissing()) {
            return null
        }
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Module?> =
            selectionResult.getResolvedDepGraph()

        com.google.devtools.build.lib.profiler.Profiler.instance()
            .profile(com.google.devtools.build.lib.profiler.ProfilerTask.BZLMOD, "finalize dep graph").use { c ->
                val canonicalRepoNameLookup: com.google.common.collect.ImmutableBiMap<RepositoryName?, ModuleKey?> =
                    computeCanonicalRepoNameLookup(depGraph)
                val extensionUsagesById: com.google.common.collect.ImmutableTable<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?>?
                try {
                    extensionUsagesById = getExtensionUsagesById(depGraph, canonicalRepoNameLookup.inverse())
                } catch (e: ExternalDepsException) {
                    throw BazelDepGraphFunctionException(e, Transience.PERSISTENT)
                }

                val extensionUniqueNames: com.google.common.collect.ImmutableBiMap<String?, ModuleExtensionId?> =
                    calculateUniqueNameForUsedExtensionId(extensionUsagesById)
                return BazelDepGraphValue.Companion.create(
                    depGraph,
                    canonicalRepoNameLookup,
                    depGraph.values().stream()
                        .map<AbridgedModule?>(java.util.function.Function { module: com.google.devtools.build.lib.bazel.bzlmod.Module? ->
                            AbridgedModule.Companion.from(module)
                        }).collect(com.google.common.collect.ImmutableList.toImmutableList<AbridgedModule?>()),
                    extensionUsagesById,
                    extensionUniqueNames.inverse(),
                    resolveRepoOverrides(
                        depGraph,
                        extensionUsagesById,
                        extensionUniqueNames.inverse(),
                        canonicalRepoNameLookup
                    )
                )
            }
    }

    private fun calculateUniqueNameForUsedExtensionId(
        extensionUsagesById: com.google.common.collect.ImmutableTable<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?>
    ): com.google.common.collect.ImmutableBiMap<String?, ModuleExtensionId?> {
        // Calculate a unique name for each used extension id with the following property that is
        // required for BzlmodRepoRuleFunction to unambiguously identify the extension that generates a
        // given repo:
        // After appending a single `+` to each such name, none of the resulting strings is a prefix of
        // any other such string.
        val extensionUniqueNames: com.google.common.collect.BiMap<String?, ModuleExtensionId?> =
            com.google.common.collect.HashBiMap.create<String?, ModuleExtensionId?>()
        for (id in extensionUsagesById.rowKeySet()) {
            var attempt = 1
            while (extensionUniqueNames.putIfAbsent(makeUniqueNameCandidate(id, attempt), id) != null) {
                attempt++
            }
        }
        return com.google.common.collect.ImmutableBiMap.copyOf<String?, ModuleExtensionId?>(extensionUniqueNames)
    }

    internal class BazelDepGraphFunctionException(e: ExternalDepsException?, transience: Transience?) :
        SkyFunctionException(e, transience)

    companion object {
        @Throws(ExternalDepsException::class)
        private fun getExtensionUsagesById(
            depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Module?>,
            moduleKeyToRepositoryNames: com.google.common.collect.ImmutableMap<ModuleKey?, RepositoryName?>?
        ): com.google.common.collect.ImmutableTable<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?> {
            val extensionUsagesTableBuilder: com.google.common.collect.ImmutableTable.Builder<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?> =
                com.google.common.collect.ImmutableTable.builder<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?>()
            for (module in depGraph.values()) {
                val repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping =
                    module.getRepoMappingWithBazelDepsOnly(moduleKeyToRepositoryNames)
                val labelConverter: LabelConverter =
                    LabelConverter(
                        PackageIdentifier.create(repoMapping.contextRepo(), PathFragment.EMPTY_FRAGMENT),
                        module.getRepoMappingWithBazelDepsOnly(moduleKeyToRepositoryNames)
                    )
                for (usage in module.getExtensionUsages()) {
                    val moduleExtensionId: ModuleExtensionId?
                    try {
                        moduleExtensionId =
                            ModuleExtensionId.Companion.create(
                                labelConverter.convert(usage.getExtensionBzlFile()),
                                usage.getExtensionName(),
                                usage.getIsolationKey()
                            )
                    } catch (e: LabelSyntaxException) {
                        throw withCauseAndMessage(
                            Code.BAD_MODULE,
                            e,
                            "invalid label for module extension found at %s",
                            usage.getProxies().getFirst().getLocation()
                        )
                    }
                    if (!moduleExtensionId.bzlFileLabel.getRepository().isVisible()) {
                        throw ExternalDepsException.Companion.withMessage(
                            Code.BAD_MODULE,
                            "invalid label for module extension found at %s: no repo visible as '@%s' here",
                            usage.getProxies().getFirst().getLocation(),
                            moduleExtensionId.bzlFileLabel.getRepository().getName()
                        )
                    }
                    extensionUsagesTableBuilder.put(moduleExtensionId, module.getKey(), usage)
                }
            }
            return extensionUsagesTableBuilder.buildOrThrow()
        }

        private fun computeCanonicalRepoNameLookup(
            depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Module?>
        ): com.google.common.collect.ImmutableBiMap<RepositoryName?, ModuleKey?> {
            // Find modules with multiple versions in the dep graph. Currently, the only source of such
            // modules is multiple_version_override.
            val multipleVersionsModules: com.google.common.collect.ImmutableSet<String?> =
                depGraph.keySet().stream()
                    .collect(Collectors.groupingBy(ModuleKey::name, Collectors.counting()))
                    .entrySet()
                    .stream()
                    .filter(java.util.function.Predicate { entry: MutableMap.MutableEntry<String?, Long?>? -> entry.getValue() > 1 })
                    .map<String?>(java.util.function.Function { obj: MutableMap.MutableEntry<String?, Long?>? -> obj.getKey() })
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())

            // If there is a unique version of this module in the entire dep graph, we elide the version
            // from the canonical repository name. This has a number of benefits:
            // * It prevents the output base from being polluted with repository directories corresponding
            //   to outdated versions of modules, which can be large and would otherwise only be cleaned
            //   up by the discouraged bazel clean --expunge.
            // * It improves cache hit rates by ensuring that a module update doesn't e.g. cause the paths
            //   of all toolchains provided by its extensions to change, which would result in widespread
            //   cache misses on every update.
            return depGraph.keySet().stream()
                .collect(
                    com.google.common.collect.ImmutableBiMap.toImmutableBiMap<ModuleKey?, RepositoryName?, ModuleKey?>(
                        java.util.function.Function { key: ModuleKey? ->
                            if (multipleVersionsModules.contains(key.name))
                                key.getCanonicalRepoNameWithVersion()
                            else
                                key.getCanonicalRepoNameWithoutVersion()
                        },
                        java.util.function.Function { key: ModuleKey? -> key })
                )
        }

        private fun makeUniqueNameCandidate(id: ModuleExtensionId, attempt: Int): String {
            com.google.common.base.Preconditions.checkArgument(attempt >= 1)
            val extensionNameDisambiguator = if (attempt == 1) "" else java.lang.String.valueOf(attempt)
            // An innate extension name is of the form "@repo//path/to/defs.bzl repo_rule_name", which
            // cannot be part of a valid repo name.
            val extensionName: String? =
                if (id.isInnate())
                    id.extensionName.substring(id.extensionName.indexOf(' '.code) + 1)
                else
                    id.extensionName
            return id.isolationKey
                .map<String>(
                    java.util.function.Function { isolationKey: IsolationKey? ->
                        java.lang.String.format( // When using an isolation key, prefix the extension name with "_" to
                            // distinguish the prefix from those generated by non-isolated extension usages.
                            // Extension names are identified by their Starlark identifier, which in the
                            // case of an exported symbol cannot start with "_".
                            "%s+_%s%s+%s+%s+%s",
                            id.bzlFileLabel.getRepository().getName(),
                            extensionName,
                            extensionNameDisambiguator,
                            isolationKey.module.name,
                            isolationKey.module.version,
                            isolationKey.usageExportedName
                        )
                    })
                .orElse(
                    (id.bzlFileLabel.getRepository().getName()
                            + "+"
                            + extensionName
                            + extensionNameDisambiguator)
                )
        }

        private fun resolveRepoOverrides(
            depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Module?>?,
            extensionUsagesTable: com.google.common.collect.ImmutableTable<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?>,
            extensionUniqueNames: com.google.common.collect.ImmutableMap<ModuleExtensionId?, String?>?,
            canonicalRepoNameLookup: com.google.common.collect.ImmutableBiMap<RepositoryName?, ModuleKey?>?
        ): com.google.common.collect.ImmutableTable<ModuleExtensionId?, String?, RepositoryName?> {
            val rootModuleMappingWithoutOverrides: com.google.devtools.build.lib.cmdline.RepositoryMapping =
                BazelDepGraphValue.Companion.getRepositoryMapping(
                    ModuleKey.Companion.ROOT,
                    depGraph,
                    extensionUsagesTable,
                    extensionUniqueNames,
                    canonicalRepoNameLookup,  // ModuleFileFunction ensures that repos that override other repos are not themselves
                    // overridden, so we can safely pass an empty table here instead of resolving chains
                    // of overrides.
                    com.google.common.collect.ImmutableTable.of<ModuleExtensionId?, String?, RepositoryName?>()
                )
            val repoOverridesBuilder: com.google.common.collect.ImmutableTable.Builder<ModuleExtensionId?, String?, RepositoryName?> =
                com.google.common.collect.ImmutableTable.builder<ModuleExtensionId?, String?, RepositoryName?>()
            for (extensionId in extensionUsagesTable.rowKeySet()) {
                val rootUsage: ModuleExtensionUsage? =
                    extensionUsagesTable.row(extensionId).get(ModuleKey.Companion.ROOT)
                if (rootUsage != null) {
                    for (override in rootUsage.getRepoOverrides().entrySet()) {
                        repoOverridesBuilder.put(
                            extensionId,
                            override.getKey(),
                            rootModuleMappingWithoutOverrides.get(override.getValue().overridingRepoName)
                        )
                    }
                }
            }
            return repoOverridesBuilder.buildOrThrow()
        }
    }
}
