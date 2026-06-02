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

import com.google.devtools.build.lib.analysis.BlazeVersionInfo

/**
 * Discovers the whole dependency graph and runs selection algorithm on it to produce the pruned
 * dependency graph and runs checks on it.
 */
class BazelModuleResolutionFunction : SkyFunction {
    private class Result(
        selectionResult: com.google.devtools.build.lib.bazel.bzlmod.Selection.Result?,
        registryFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?,
        selectedYankedVersions: com.google.common.collect.ImmutableMap<ModuleKey?, String?>?
    ) {
        val selectionResult: com.google.devtools.build.lib.bazel.bzlmod.Selection.Result?
        val registryFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?
        val selectedYankedVersions: com.google.common.collect.ImmutableMap<ModuleKey?, String?>?

        init {
            this.selectionResult = selectionResult
            this.registryFileHashes = registryFileHashes
            this.selectedYankedVersions = selectedYankedVersions
        }
    }

    private class ModuleResolutionComputeState : SkyKeyComputeState {
        var discoverAndSelectResult: Result? = null
    }

    @Throws(BazelModuleResolutionFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val allowedYankedVersionsFromEnv: EnvironmentVariableValue? =
            env.getValue(ClientEnvironmentFunction.key(YankedVersionsUtil.BZLMOD_ALLOWED_YANKED_VERSIONS_ENV)) as EnvironmentVariableValue?
        if (allowedYankedVersionsFromEnv == null) {
            return null
        }

        val allowedYankedVersions: java.util.Optional<com.google.common.collect.ImmutableSet<ModuleKey?>?>
        try {
            allowedYankedVersions =
                YankedVersionsUtil.parseAllowedYankedVersions(
                    allowedYankedVersionsFromEnv.value,
                    java.util.Objects.requireNonNull<MutableList<String?>?>(
                        YankedVersionsUtil.ALLOWED_YANKED_VERSIONS.get(
                            env
                        )
                    )
                )
        } catch (e: ExternalDepsException) {
            throw BazelModuleResolutionFunctionException(e, Transience.PERSISTENT)
        }

        val root: RootModuleFileValue? =
            env.getValue(ModuleFileValue.Companion.KEY_FOR_ROOT_MODULE) as RootModuleFileValue?
        if (root == null) {
            return null
        }

        val state: ModuleResolutionComputeState =
            env.getState<ModuleResolutionComputeState>(java.util.function.Supplier { ModuleResolutionComputeState() })
        if (state.discoverAndSelectResult == null) {
            state.discoverAndSelectResult = discoverAndSelect(env, root, allowedYankedVersions)
            if (state.discoverAndSelectResult == null) {
                return null
            }
        }

        val registryFileHashes: SequencedMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?> =
            LinkedHashMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>(
                state.discoverAndSelectResult!!.registryFileHashes
            )
        val repoSpecKeys: com.google.common.collect.ImmutableSet<RepoSpecKey> =
            state.discoverAndSelectResult!!.selectionResult.resolvedDepGraph.values()
                .stream() // Modules with a null registry have a non-registry override. We don't need to
                // fetch or store the repo spec in this case.
                .filter(java.util.function.Predicate { module: InterimModule? -> module.getRegistry() != null })
                .map<RepoSpecKey?>(java.util.function.Function { module: InterimModule? ->
                    RepoSpecKey.Companion.of(
                        module
                    )
                })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<RepoSpecKey?>())
        val repoSpecResults: SkyframeLookupResult = env.getValuesAndExceptions(repoSpecKeys)
        val remoteRepoSpecs: com.google.common.collect.ImmutableMap.Builder<ModuleKey?, RepoSpec?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, RepoSpec?>()
        for (repoSpecKey in repoSpecKeys) {
            val repoSpecValue: RepoSpecValue? = repoSpecResults.get(repoSpecKey) as RepoSpecValue?
            if (repoSpecValue == null) {
                return null
            }
            remoteRepoSpecs.put(repoSpecKey.moduleKey, repoSpecValue.repoSpec)
            registryFileHashes.putAll(repoSpecValue.registryFileHashes)
        }

        val finalDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Module?>?
        com.google.devtools.build.lib.profiler.Profiler.instance()
            .profile(com.google.devtools.build.lib.profiler.ProfilerTask.BZLMOD, "compute final dep graph").use { c ->
                finalDepGraph =
                    computeFinalDepGraph(
                        state.discoverAndSelectResult!!.selectionResult.resolvedDepGraph,
                        root.overrides,
                        remoteRepoSpecs.buildOrThrow()
                    )
            }
        return BazelModuleResolutionValue.Companion.create(
            finalDepGraph,
            state.discoverAndSelectResult!!.selectionResult.unprunedDepGraph,
            com.google.common.collect.ImmutableMap.copyOf<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>(
                registryFileHashes
            ),
            state.discoverAndSelectResult!!.selectedYankedVersions
        )
    }

    internal class BazelModuleResolutionFunctionException(e: ExternalDepsException?, transience: Transience?) :
        SkyFunctionException(e, transience)

    companion object {
        @kotlin.jvm.JvmField
        val CHECK_DIRECT_DEPENDENCIES: Precomputed<CheckDirectDepsMode?> =
            Precomputed<CheckDirectDepsMode?>("check_direct_dependency")
        @kotlin.jvm.JvmField
        val BAZEL_COMPATIBILITY_MODE: Precomputed<BazelCompatibilityMode?> =
            Precomputed<BazelCompatibilityMode?>("bazel_compatibility_mode")

        @Throws(BazelModuleResolutionFunctionException::class, java.lang.InterruptedException::class)
        private fun discoverAndSelect(
            env: SkyFunction.Environment,
            root: RootModuleFileValue,
            allowedYankedVersions: java.util.Optional<com.google.common.collect.ImmutableSet<ModuleKey?>?>
        ): Result? {
            val discoveryResult: Discovery.Result?
            try {
                com.google.devtools.build.lib.profiler.Profiler.instance()
                    .profile(com.google.devtools.build.lib.profiler.ProfilerTask.BZLMOD, "discovery").use { c ->
                        discoveryResult = Discovery.run(env, root)
                    }
            } catch (e: ExternalDepsException) {
                throw BazelModuleResolutionFunctionException(e, Transience.PERSISTENT)
            }
            if (discoveryResult == null) {
                return null
            }

            verifyAllOverridesAreOnExistentModules(discoveryResult.depGraph, root.overrides)

            val selectionResult: com.google.devtools.build.lib.bazel.bzlmod.Selection.Result?
            try {
                com.google.devtools.build.lib.profiler.Profiler.instance()
                    .profile(com.google.devtools.build.lib.profiler.ProfilerTask.BZLMOD, "selection").use { c ->
                        selectionResult = com.google.devtools.build.lib.bazel.bzlmod.Selection.run(
                            discoveryResult.depGraph,
                            root.overrides
                        )
                    }
            } catch (e: ExternalDepsException) {
                throw BazelModuleResolutionFunctionException(e, Transience.PERSISTENT)
            }
            val resolvedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
                selectionResult.resolvedDepGraph

            val yankedVersionsValues: com.google.common.collect.ImmutableMap<ModuleKey, YankedVersionsValue>?
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .profile(com.google.devtools.build.lib.profiler.ProfilerTask.BZLMOD, "collect yanked versions")
                .use { c ->
                    yankedVersionsValues = collectYankedVersionsValues(env, resolvedDepGraph.values())
                }
            if (yankedVersionsValues == null) {
                return null
            }

            com.google.devtools.build.lib.profiler.Profiler.instance()
                .profile(com.google.devtools.build.lib.profiler.ProfilerTask.BZLMOD, "verify root module direct deps")
                .use { c ->
                    verifyRootModuleDirectDepsAreAccurate(
                        discoveryResult.depGraph.get(ModuleKey.Companion.ROOT),
                        resolvedDepGraph.get(ModuleKey.Companion.ROOT),
                        java.util.Objects.requireNonNull<CheckDirectDepsMode?>(CHECK_DIRECT_DEPENDENCIES.get(env)),
                        env.getListener()
                    )
                }
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .profile(com.google.devtools.build.lib.profiler.ProfilerTask.BZLMOD, "check bazel compatibility")
                .use { c ->
                    checkBazelCompatibility(
                        resolvedDepGraph.values(),
                        java.util.Objects.requireNonNull<BazelCompatibilityMode?>(BAZEL_COMPATIBILITY_MODE.get(env)),
                        env.getListener()
                    )
                }
            val selectedYankedVersions: com.google.common.collect.ImmutableMap<ModuleKey?, String?> =
                checkNoYankedVersions(yankedVersionsValues, allowedYankedVersions)
            return com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionFunction.Result(
                selectionResult, discoveryResult.registryFileHashes, selectedYankedVersions
            )
        }

        @Throws(java.lang.InterruptedException::class)
        private fun collectYankedVersionsValues(
            env: SkyFunction.Environment, modules: com.google.common.collect.ImmutableCollection<InterimModule>
        ): com.google.common.collect.ImmutableMap<ModuleKey, YankedVersionsValue>? {
            val yankedVersionsValues: com.google.common.collect.ImmutableMap.Builder<ModuleKey?, YankedVersionsValue?> =
                com.google.common.collect.ImmutableMap.builder<ModuleKey?, YankedVersionsValue?>()
            val yankedVersionsKeys: MutableMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsValue.Key?> =
                HashMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsValue.Key?>()
            for (m in modules) {
                if (m.getRegistry() == null) {
                    // Modules with a non-registry override are never yanked.
                    yankedVersionsValues.put(m.getKey(), YankedVersionsValue.Companion.NONE_YANKED)
                    continue
                }
                val lockfileYankedVersionsValue: java.util.Optional<YankedVersionsValue?> =
                    m.getRegistry().tryGetYankedVersionsFromLockfile(m.getKey())
                if (lockfileYankedVersionsValue.isPresent()) {
                    yankedVersionsValues.put(m.getKey(), lockfileYankedVersionsValue.get())
                } else {
                    // We need to download the list of yanked versions from the registry.
                    yankedVersionsKeys.put(
                        m.getKey(),
                        com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsValue.Key.Companion.create(
                            m.getName(),
                            m.getRegistry().getUrl()
                        )
                    )
                }
            }
            val yankedVersionsResult: SkyframeLookupResult =
                env.getValuesAndExceptions(yankedVersionsKeys.values())
            if (env.valuesMissing()) {
                return null
            }
            for (entry in yankedVersionsKeys.entrySet()) {
                val yankedVersionsValue: YankedVersionsValue? =
                    yankedVersionsResult.get(entry.getValue()) as YankedVersionsValue?
                if (yankedVersionsValue == null) {
                    return null
                }
                yankedVersionsValues.put(entry.getKey(), yankedVersionsValue)
            }
            return yankedVersionsValues.buildOrThrow()
        }

        @Throws(BazelModuleResolutionFunctionException::class)
        private fun verifyAllOverridesAreOnExistentModules(
            initialDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>,
            overrides: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?>
        ) {
            val existentModules: com.google.common.collect.ImmutableSet<String?> =
                initialDepGraph.values().stream()
                    .map<String?>(java.util.function.Function { obj: InterimModule? -> obj.getName() })
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())
            val nonexistentModules: MutableSet<String?> =
                com.google.common.collect.Sets.difference<String?>(overrides.keySet(), existentModules)
            if (!nonexistentModules.isEmpty()) {
                throw BazelModuleResolutionFunctionException(
                    withMessage(
                        Code.BAD_MODULE,
                        "the root module specifies overrides on nonexistent module(s): %s",
                        com.google.common.base.Joiner.on(", ").join(nonexistentModules)
                    ),
                    Transience.PERSISTENT
                )
            }
        }

        @Throws(BazelModuleResolutionFunctionException::class)
        private fun verifyRootModuleDirectDepsAreAccurate(
            discoveredRootModule: InterimModule,
            resolvedRootModule: InterimModule,
            mode: CheckDirectDepsMode?,
            eventHandler: com.google.devtools.build.lib.events.EventHandler
        ) {
            if (mode == CheckDirectDepsMode.OFF) {
                return
            }

            var failure = false
            for (dep in discoveredRootModule.getDeps().entrySet()) {
                val resolved: ModuleKey? = resolvedRootModule.getDeps().get(dep.getKey())
                if (dep.getValue() != resolved) {
                    val message: String? =
                        java.lang.String.format(
                            ("For repository '%s', the root module requires module version %s, but got %s in the"
                                    + " resolved dependency graph. Please update the version in your MODULE.bazel"
                                    + " or set --check_direct_dependencies=off"),
                            dep.getKey(), dep.getValue(), resolved
                        )
                    if (mode == CheckDirectDepsMode.WARNING) {
                        eventHandler.handle(com.google.devtools.build.lib.events.Event.warn(message))
                    } else {
                        eventHandler.handle(com.google.devtools.build.lib.events.Event.error(message))
                        failure = true
                    }
                }
            }

            if (failure) {
                throw BazelModuleResolutionFunctionException(
                    ExternalDepsException.Companion.withMessage(
                        Code.VERSION_RESOLUTION_ERROR, "Direct dependency check failed."
                    ),
                    Transience.PERSISTENT
                )
            }
        }

        @Throws(BazelModuleResolutionFunctionException::class)
        fun checkBazelCompatibility(
            modules: com.google.common.collect.ImmutableCollection<InterimModule>,
            mode: BazelCompatibilityMode?,
            eventHandler: com.google.devtools.build.lib.events.EventHandler
        ) {
            if (mode == BazelCompatibilityMode.OFF) {
                return
            }

            val currentBazelVersion: String? = BlazeVersionInfo.instance().getVersion()
            if (com.google.common.base.Strings.isNullOrEmpty(currentBazelVersion)) {
                return
            }

            val curVersion: BazelVersion = BazelVersion.Companion.parse(currentBazelVersion)
            for (module in modules) {
                for (compatVersion in module.getBazelCompatibility()) {
                    if (!curVersion.satisfiesCompatibility(compatVersion)) {
                        val message: String? =
                            java.lang.String.format(
                                "Bazel version %s is not compatible with module \"%s\" (bazel_compatibility: %s)",
                                curVersion.getOriginal(), module.getKey(), module.getBazelCompatibility()
                            )

                        if (mode == BazelCompatibilityMode.WARNING) {
                            eventHandler.handle(com.google.devtools.build.lib.events.Event.warn(message))
                        } else {
                            eventHandler.handle(com.google.devtools.build.lib.events.Event.error(message))
                            throw BazelModuleResolutionFunctionException(
                                ExternalDepsException.Companion.withMessage(
                                    Code.VERSION_RESOLUTION_ERROR, "Bazel compatibility check failed"
                                ),
                                Transience.PERSISTENT
                            )
                        }
                    }
                }
            }
        }

        /**
         * Fail if any selected module is yanked and not explicitly allowed.
         * 
         * @return the yanked info for each yanked but explicitly allowed module
         */
        @Throws(BazelModuleResolutionFunctionException::class)
        private fun checkNoYankedVersions(
            yankedVersionValues: com.google.common.collect.ImmutableMap<ModuleKey, YankedVersionsValue>,
            allowedYankedVersions: java.util.Optional<com.google.common.collect.ImmutableSet<ModuleKey?>?>
        ): com.google.common.collect.ImmutableMap<ModuleKey?, String?> {
            val selectedYankedVersions: com.google.common.collect.ImmutableMap.Builder<ModuleKey?, String?> =
                com.google.common.collect.ImmutableMap.builder<ModuleKey?, String?>()
            for (entry in yankedVersionValues.entrySet()) {
                val key: ModuleKey = entry.getKey()
                val yankedVersionsValue: YankedVersionsValue = entry.getValue()
                if (yankedVersionsValue.yankedVersions.isEmpty()) {
                    // No yanked version information available for this module.
                    continue
                }
                val yankedInfo: String? = yankedVersionsValue.yankedVersions.get().get(key.version)
                if (yankedInfo == null) {
                    // The selected version is not yanked.
                    continue
                }
                if (allowedYankedVersions.isEmpty() || allowedYankedVersions.get().contains(key)) {
                    // The selected version is yanked but explicitly allowed.
                    selectedYankedVersions.put(key, yankedInfo)
                    continue
                }
                throw BazelModuleResolutionFunctionException(
                    ExternalDepsException.Companion.withMessage(
                        Code.VERSION_RESOLUTION_ERROR,
                        ("Yanked version detected in your resolved dependency graph: %s, for the reason: "
                                + "%s.\nYanked versions may contain serious vulnerabilities and should not be "
                                + "used. To fix this, use a bazel_dep on a newer version of this module. To "
                                + "continue using this version, allow it using the --allow_yanked_versions "
                                + "flag or the BZLMOD_ALLOW_YANKED_VERSIONS env variable."),
                        key,
                        yankedInfo
                    ),
                    Transience.PERSISTENT
                )
            }
            return selectedYankedVersions.buildOrThrow()
        }

        private fun computeFinalDepGraph(
            resolvedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>,
            overrides: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?>,
            remoteRepoSpecs: com.google.common.collect.ImmutableMap<ModuleKey?, RepoSpec?>
        ): com.google.common.collect.ImmutableMap<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Module?> {
            val finalDepGraph: com.google.common.collect.ImmutableMap.Builder<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Module?> =
                com.google.common.collect.ImmutableMap.builder<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Module?>()
            for (entry in resolvedDepGraph.entrySet()) {
                finalDepGraph.put(
                    entry.getKey(),
                    InterimModule.Companion.toModule(
                        entry.getValue(),
                        overrides.get(entry.getKey().name),
                        remoteRepoSpecs.get(entry.getKey())
                    )
                )
            }
            return finalDepGraph.buildOrThrow()
        }
    }
}
