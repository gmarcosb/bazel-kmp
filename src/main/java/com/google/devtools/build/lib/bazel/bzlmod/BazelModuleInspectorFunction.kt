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

import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule.ResolutionReason
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionValue
import com.google.devtools.build.lib.bazel.bzlmod.ExternalDepsException
import com.google.devtools.build.lib.bazel.bzlmod.InterimModule
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.RootModuleFileValue
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionValue
import com.google.devtools.build.skyframe.SkyFunction
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.SkyframeLookupResult
import java.util.HashMap
import java.util.stream.Collectors

/**
 * Precomputes an augmented version of the un-pruned dep graph that is used for dep graph
 * inspection. By this stage, the Bazel module resolution should have been completed.
 */
class BazelModuleInspectorFunction : SkyFunction {
    @Throws(java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val root: RootModuleFileValue? =
            env.getValue(ModuleFileValue.Companion.KEY_FOR_ROOT_MODULE) as RootModuleFileValue?
        if (root == null) {
            return null
        }
        val depGraphValue: BazelDepGraphValue? = env.getValue(BazelDepGraphValue.Companion.KEY) as BazelDepGraphValue?
        if (depGraphValue == null) {
            return null
        }
        val resolutionValue: BazelModuleResolutionValue? =
            env.getValue(BazelModuleResolutionValue.Companion.KEY) as BazelModuleResolutionValue?
        if (resolutionValue == null) {
            return null
        }
        val overrides: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?> =
            root.overrides
        val unprunedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            resolutionValue.getUnprunedDepGraph()
        val resolvedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Module?> =
            resolutionValue.getResolvedDepGraph()

        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, AugmentedModule?> =
            computeAugmentedGraph(unprunedDepGraph, resolvedDepGraph.keySet(), overrides)

        val extensionRepos = computExtensionRepos(depGraphValue, env)
        if (extensionRepos == null) {
            return null
        }

        // Group all ModuleKeys seen by their module name for easy lookup
        val modulesIndex: com.google.common.collect.ImmutableMap<String?, com.google.common.collect.ImmutableSet<ModuleKey?>?> =
            com.google.common.collect.ImmutableMap.copyOf<String?, com.google.common.collect.ImmutableSet<ModuleKey?>?>(
                depGraph.values().stream()
                    .collect(
                        Collectors.groupingBy(
                            AugmentedModule::name,
                            Collectors.mapping(
                                AugmentedModule::key,
                                com.google.common.collect.ImmutableSet.toImmutableSet<ModuleKey?>()
                            )
                        )
                    )
            )

        return BazelModuleInspectorValue.Companion.create(
            depGraph,
            modulesIndex,
            extensionRepos.extensionToRepoInternalNames,
            depGraphValue.getCanonicalRepoNameLookup().inverse(),
            extensionRepos.errors
        )
    }

    private class ExtensionRepos(
        extensionToRepoInternalNames: com.google.common.collect.ImmutableSetMultimap<ModuleExtensionId?, String?>?,
        errors: com.google.common.collect.ImmutableList<ExternalDepsException?>?
    ) {
        val extensionToRepoInternalNames: com.google.common.collect.ImmutableSetMultimap<ModuleExtensionId?, String?>?
        val errors: com.google.common.collect.ImmutableList<ExternalDepsException?>?

        init {
            this.extensionToRepoInternalNames = extensionToRepoInternalNames
            this.errors = errors
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun computExtensionRepos(depGraphValue: BazelDepGraphValue, env: SkyFunction.Environment): ExtensionRepos? {
        val extensionEvalKeys: com.google.common.collect.ImmutableSet<ModuleExtensionId?> =
            depGraphValue.getExtensionUsagesTable().rowKeySet()
        val singleExtensionKeys: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionValue.Key> =
            extensionEvalKeys.stream()
                .map<com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionValue.Key?>(java.util.function.Function { id: ModuleExtensionId? ->
                    SingleExtensionValue.Companion.key(id)
                })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionValue.Key?>())
        val singleExtensionValues: SkyframeLookupResult = env.getValuesAndExceptions(singleExtensionKeys)

        val extensionToRepoInternalNames: com.google.common.collect.ImmutableSetMultimap.Builder<ModuleExtensionId?, String?> =
            com.google.common.collect.ImmutableSetMultimap.builder<ModuleExtensionId?, String?>()
        val errors: com.google.common.collect.ImmutableList.Builder<ExternalDepsException?> =
            com.google.common.collect.ImmutableList.builder<ExternalDepsException?>()
        for (singleExtensionKey in singleExtensionKeys) {
            val singleExtensionValue: SingleExtensionValue?
            try {
                singleExtensionValue =
                    singleExtensionValues.getOrThrow<ExternalDepsException?>(
                        singleExtensionKey,
                        ExternalDepsException::class.java
                    ) as SingleExtensionValue?
            } catch (e: ExternalDepsException) {
                // The extension failed, so we can't report its generated repos. We can still report the
                // imported repos in keep going mode, so don't fail and just skip this extension.
                errors.add(e)
                env.getListener().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                continue
            }
            if (singleExtensionValue == null) {
                return null
            }
            extensionToRepoInternalNames.putAll(
                singleExtensionKey.argument(), singleExtensionValue.generatedRepoSpecs.keySet()
            )
        }
        return ExtensionRepos(extensionToRepoInternalNames.build(), errors.build())
    }

    companion object {
        fun computeAugmentedGraph(
            unprunedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>,
            usedModules: com.google.common.collect.ImmutableSet<ModuleKey?>,
            overrides: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?>
        ): com.google.common.collect.ImmutableMap<ModuleKey?, AugmentedModule?> {
            val depGraphAugmentBuilder: MutableMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule.Builder> =
                HashMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule.Builder>()

            // For all Modules in the un-pruned dep graph, inspect their dependencies and add themselves
            // to their children AugmentedModule as dependant. Also fill in their own AugmentedModule
            // with a map from their dependencies to the resolution reason that was applied to each.
            // The newly created graph will also contain ModuleAugments for non-loaded modules.
            for (e in unprunedDepGraph.entrySet()) {
                val parentKey: ModuleKey? = e.getKey()
                val parentModule: InterimModule = e.getValue()

                val parentBuilder: com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule.Builder =
                    depGraphAugmentBuilder
                        .computeIfAbsent(
                            parentKey,
                            java.util.function.Function { k: ModuleKey? ->
                                AugmentedModule.Companion.builder(k)
                                    .setName(parentModule.getName())
                                    .setRepoName(parentModule.getRepoName())
                            })
                        .setVersion(parentModule.getVersion())
                        .setLoaded(true)

                for (childDep in parentModule.getDeps().keySet()) {
                    val originalKey: ModuleKey? = parentModule.getOriginalDeps().get(childDep)
                    val originalModule: InterimModule? = unprunedDepGraph.get(originalKey)
                    val key: ModuleKey? = parentModule.getDeps().get(childDep)
                    val module: InterimModule? = unprunedDepGraph.get(key)

                    val originalChildBuilder: com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule.Builder =
                        depGraphAugmentBuilder.computeIfAbsent(
                            originalKey,
                            java.util.function.Function { key: ModuleKey? -> AugmentedModule.Companion.builder(key) })
                    if (originalModule != null) {
                        originalChildBuilder
                            .setName(originalModule.getName())
                            .setVersion(originalModule.getVersion())
                            .setRepoName(originalModule.getRepoName())
                            .setLoaded(true)
                    }

                    val newChildBuilder: com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule.Builder =
                        depGraphAugmentBuilder.computeIfAbsent(
                            key,
                            java.util.function.Function { k: ModuleKey? ->
                                AugmentedModule.Companion.builder(k)
                                    .setName(module.getName())
                                    .setVersion(module.getVersion())
                                    .setRepoName(module.getRepoName())
                                    .setLoaded(true)
                            })

                    // originalDependants and dependants can differ because
                    // parentModule could have had originalChild in the unresolved graph, but in the resolved
                    // graph the originalChild could have become orphan due to an override or selection
                    originalChildBuilder.addOriginalDependant(parentKey)
                    // also, even if the dep has not changed, the parentModule may not be referenced
                    // anymore in the resolved graph, so parentModule will only be added above
                    if (usedModules.contains(parentKey)) {
                        newChildBuilder.addDependant(parentKey)
                    }

                    val reason: ResolutionReason?
                    if (key.version == originalKey.version) {
                        reason = ResolutionReason.ORIGINAL
                    } else {
                        reason =
                            when (overrides.get(key.name)) {
                                -> ResolutionReason.SINGLE_VERSION_OVERRIDE
                                -> ResolutionReason.MULTIPLE_VERSION_OVERRIDE
                                -> ResolutionReason.NON_REGISTRY_OVERRIDE
                                null -> ResolutionReason.MINIMAL_VERSION_SELECTION
                            }
                    }

                    if (reason != ResolutionReason.ORIGINAL) {
                        parentBuilder.addUnusedDep(childDep, originalKey)
                    }
                    parentBuilder.addDep(childDep, key)
                    parentBuilder.addDepReason(childDep, reason)
                }
            }

            return depGraphAugmentBuilder.entrySet().stream()
                .collect(TODO("Cannot convert element")) < Entry < ModuleKey
            TODO(
                """
                |Cannot convert element
                |With text:
                |AugmentedModule.Builder>, ModuleKey, AugmentedModule>toImmutableMap(Entry::getKey, e -> e.getValue().build())
                """.trimMargin()
            )
        }
    }
}
