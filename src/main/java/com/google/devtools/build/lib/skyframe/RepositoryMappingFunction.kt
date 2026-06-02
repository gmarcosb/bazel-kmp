// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue

/** [SkyFunction] for [RepositoryMappingValue]s.  */
class RepositoryMappingFunction(ruleClassProvider: RuleClassProvider) : SkyFunction {
    private val ruleClassProvider: RuleClassProvider

    init {
        this.ruleClassProvider = ruleClassProvider
    }

    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val key: com.google.devtools.build.lib.skyframe.RepositoryMappingValue.Key =
            skyKey as com.google.devtools.build.lib.skyframe.RepositoryMappingValue.Key
        val repositoryMappingValue: RepositoryMappingValue? = computeInternal(key, env)
        if (repositoryMappingValue == null) {
            return null
        }
        if (repositoryMappingValue === RepositoryMappingValue.Companion.NOT_FOUND_VALUE
            && RepoDefinitionFunction.REPOSITORY_OVERRIDES
                .get(env)
                .containsKey(key.repoName().name)
        ) {
            throw RepositoryMappingFunctionException( // Use this rather than NoSuchThingException so that the error mentions the requested
                // target.
                NoSuchPackageException(
                    PackageIdentifier.create(key.repoName(), PathFragment.EMPTY_FRAGMENT),
                    java.lang.String.format(
                        ("the repository %s does not exist, but has been specified as overridden with"
                                + " --override_repository. Use --inject_repository instead to add a new"
                                + " repository."),
                        key.repoName()
                    )
                )
            )
        }
        return repositoryMappingValue
    }

    @Throws(java.lang.InterruptedException::class)
    private fun computeInternal(
        skyKey: com.google.devtools.build.lib.skyframe.RepositoryMappingValue.Key,
        env: SkyFunction.Environment
    ): RepositoryMappingValue? {
        val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? =
            PrecomputedValue.Companion.STARLARK_SEMANTICS.get(env)
        if (starlarkSemantics == null) {
            return null
        }
        val repositoryName: RepositoryName = skyKey.repoName()

        if (StarlarkBuiltinsValue.isBuiltinsRepo(repositoryName)) {
            // If tools repo is not set, use the default empty mapping.
            if (ruleClassProvider.toolsRepository == null) {
                return RepositoryMappingValue.Companion.DEFAULT_VALUE_FOR_BUILTINS_REPO
            }
            // Builtins .bzl files should use the repo mapping of @bazel_tools, to get access to repos
            // such as @platforms.
            val bazelToolsMapping: RepositoryMappingValue? =
                env.getValue(RepositoryMappingValue.Companion.key(ruleClassProvider.toolsRepository)) as RepositoryMappingValue?
            if (bazelToolsMapping == null) {
                return null
            }
            return RepositoryMappingValue.Companion.DEFAULT_VALUE_FOR_BUILTINS_REPO.withAdditionalMappings(
                bazelToolsMapping.repositoryMapping
            )
        }

        val bazelDepGraphValue: BazelDepGraphValue? =
            env.getValue(BazelDepGraphValue.KEY) as BazelDepGraphValue?
        if (bazelDepGraphValue == null) {
            return null
        }

        // Try and see if this is a repo generated from a Bazel module.
        val mappingValue: java.util.Optional<RepositoryMappingValue?> =
            computeForBazelModuleRepo(repositoryName, bazelDepGraphValue)
        if (mappingValue.isPresent()) {
            return if (repositoryName.isMain())
                mappingValue.get().withCachedInverseMap()
            else
                mappingValue.get()
        }

        // Now try and see if this is a repo generated from a module extension.
        val moduleExtensionId: java.util.Optional<ModuleExtensionId?> =
            maybeGetModuleExtensionForRepo(repositoryName, bazelDepGraphValue)

        if (moduleExtensionId.isPresent()) {
            val repoMappingEntriesValue: ModuleExtensionRepoMappingEntriesValue? =
                env.getValue(ModuleExtensionRepoMappingEntriesValue.key(moduleExtensionId.get())) as ModuleExtensionRepoMappingEntriesValue?
            if (repoMappingEntriesValue == null) {
                return null
            }
            return RepositoryMappingValue.Companion.create(
                RepositoryMapping.create(repoMappingEntriesValue.entries(), repositoryName),
                repoMappingEntriesValue.moduleKey().name,
                repoMappingEntriesValue.moduleKey().version()
            )
        }

        return RepositoryMappingValue.Companion.NOT_FOUND_VALUE
    }

    /**
     * Calculates repo mappings for a repo generated from a Bazel module. Such a repo can see all its
     * `bazel_dep`s, as well as any repos generated by an extension it has a `use_repo`
     * clause for.
     * 
     * @return the repo mappings for the repo if it's generated from a Bazel module, otherwise return
     * Optional.empty().
     */
    private fun computeForBazelModuleRepo(
        repositoryName: RepositoryName?, bazelDepGraphValue: BazelDepGraphValue
    ): java.util.Optional<RepositoryMappingValue?> {
        val moduleKey: ModuleKey? = bazelDepGraphValue.canonicalRepoNameLookup.get(repositoryName)
        if (moduleKey == null) {
            return java.util.Optional.empty<RepositoryMappingValue?>()
        }
        val module: Module = bazelDepGraphValue.depGraph.get(moduleKey)
        return java.util.Optional.of<RepositoryMappingValue?>(
            RepositoryMappingValue.Companion.create(
                bazelDepGraphValue.getFullRepoMapping(moduleKey),
                module.getName(),
                module.getVersion()
            )
        )
    }

    private class RepositoryMappingFunctionException(e: NoSuchThingException?) :
        SkyFunctionException(e, Transience.PERSISTENT)

    companion object {
        private fun maybeGetModuleExtensionForRepo(
            repositoryName: RepositoryName, bazelDepGraphValue: BazelDepGraphValue
        ): java.util.Optional<ModuleExtensionId?> {
            return bazelDepGraphValue.getExtensionUniqueNames().entrySet().stream()
                .filter({ e -> repositoryName.name.startsWith(e.getValue() + "+") })
                .map({ obj: MutableMap.MutableEntry<*, *>? -> obj.getKey() })
                .findFirst()
        }
    }
}
