// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.server.FailureDetails

/**
 * Runs module discovery. This step of module resolution reads the module file of the root module
 * (i.e. the current workspace), adds its direct `bazel_dep`s to the dependency graph, and
 * repeats the step for any added dependencies until the entire graph is discovered.
 */
internal object Discovery {
    /**
     * Runs module discovery. This function follows SkyFunction semantics (returns null if a Skyframe
     * dependency is missing and this function needs a restart).
     */
    @Throws(java.lang.InterruptedException::class, ExternalDepsException::class)
    fun run(env: SkyFunction.Environment, root: RootModuleFileValue): Result? {
        // Because of the possible existence of nodep edges, we do multiple rounds of discovery.
        // In each round, we keep track of unfulfilled nodep edges, and at the end of the round, if any
        // unfulfilled nodep edge can now be fulfilled, we run another round.
        var prevRoundModuleNames: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>(root.module.getName())
        while (true) {
            val discoveryRound = DiscoveryRound(env, root, prevRoundModuleNames)
            val result = discoveryRound.run()
            if (result == null) {
                return null
            }
            prevRoundModuleNames =
                result.depGraph.values().stream()
                    .map<String?>(java.util.function.Function { obj: InterimModule? -> obj.getName() })
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())
            if (discoveryRound.unfulfilledNodepEdgeModuleNames.stream()
                    .noneMatch(java.util.function.Predicate { `object`: String? ->
                        prevRoundModuleNames.contains(
                            `object`
                        )
                    })
            ) {
                return result
            }
        }
    }

    class Result(
        depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>?,
        registryFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?
    ) {
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>?
        val registryFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?

        init {
            this.depGraph = depGraph
            this.registryFileHashes = registryFileHashes
        }
    }

    private class DiscoveryRound(
        env: SkyFunction.Environment,
        root: RootModuleFileValue,
        prevRoundModuleNames: com.google.common.collect.ImmutableSet<String?>
    ) {
        private val env: SkyFunction.Environment
        private val root: RootModuleFileValue
        private val prevRoundModuleNames: com.google.common.collect.ImmutableSet<String?>
        private val depGraph: MutableMap<ModuleKey?, InterimModule> = LinkedHashMap<ModuleKey?, InterimModule>()

        /**
         * Stores a mapping from a module to its "predecessor" -- that is, its first dependent in BFS
         * order. This is used to report a dependency chain in errors (see [ ][.maybeReportDependencyChain].
         */
        private val predecessors: MutableMap<ModuleKey?, ModuleKey?> = HashMap<ModuleKey?, ModuleKey?>()

        /**
         * For all unfulfilled nodep edges seen during this round, this set stores the module names of
         * those nodep edges. Remember that whether a nodep edge can be fulfilled depends on whether the
         * module it names already exists in the dep graph.
         */
        private val unfulfilledNodepEdgeModuleNames: MutableSet<String?> = HashSet<String?>()

        /**
         * Runs one round of discovery. At its core, this is a simple breadth-first search: we start
         * from the "horizon" of just the root module, and advance the horizon by discovering the
         * dependencies of modules in the current horizon. Keep doing this until the horizon is empty.
         */
        @Throws(java.lang.InterruptedException::class, ExternalDepsException::class)
        fun run(): Result? {
            val registryFileHashes: SequencedMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?> =
                LinkedHashMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>()
            depGraph.put(
                ModuleKey.Companion.ROOT,
                root.module.withDepsTransformed(UnaryOperator { depKey: ModuleKey -> this.applyOverrides(depKey) })
            )
            var horizon: com.google.common.collect.ImmutableSet<ModuleKey?> =
                com.google.common.collect.ImmutableSet.of<ModuleKey?>(ModuleKey.Companion.ROOT)
            while (!horizon.isEmpty()) {
                val nextHorizonSkyKeys: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.Key> =
                    advanceHorizon(horizon)
                val result: SkyframeLookupResult = env.getValuesAndExceptions(nextHorizonSkyKeys)
                val nextHorizon: com.google.common.collect.ImmutableSet.Builder<ModuleKey?> =
                    com.google.common.collect.ImmutableSet.builder<ModuleKey?>()
                for (skyKey in nextHorizonSkyKeys) {
                    val depKey: ModuleKey? = skyKey.moduleKey
                    val moduleFileValue: ModuleFileValue?
                    try {
                        moduleFileValue =
                            result.getOrThrow<ExternalDepsException?>(
                                skyKey,
                                ExternalDepsException::class.java
                            ) as ModuleFileValue?
                    } catch (e: ExternalDepsException) {
                        throw maybeReportDependencyChain(e, depKey)
                    }
                    if (moduleFileValue == null) {
                        // Don't return yet. Try to expand any other unexpanded nodes before returning.
                        depGraph.put(depKey, null)
                    } else {
                        depGraph.put(
                            depKey,
                            moduleFileValue.module()
                                .withDepsTransformed(UnaryOperator { depKey: ModuleKey -> this.applyOverrides(depKey) })
                        )
                        registryFileHashes.putAll(moduleFileValue.registryFileHashes())
                        nextHorizon.add(depKey)
                    }
                }
                horizon = nextHorizon.build()
            }
            if (env.valuesMissing()) {
                return null
            }
            // Remove all unfulfilled nodep edges from the dep graph. It should be just as if they never
            // existed.
            val result: com.google.common.collect.ImmutableMap.Builder<ModuleKey?, InterimModule?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<ModuleKey?, InterimModule?>(depGraph.size())
            for (entry in depGraph.entrySet()) {
                val module: InterimModule = entry.getValue()
                if (module.getNodepDeps().stream()
                        .allMatch(java.util.function.Predicate { key: ModuleKey? -> depGraph.containsKey(key) })
                ) {
                    result.put(entry.getKey(), module)
                } else {
                    result.put(
                        entry.getKey(),
                        module.toBuilder()
                            .setNodepDeps(
                                module.getNodepDeps().stream()
                                    .filter(java.util.function.Predicate { key: ModuleKey? -> depGraph.containsKey(key) })
                                    .collect(com.google.common.collect.ImmutableList.toImmutableList<ModuleKey?>())
                            )
                            .build()
                    )
                }
            }
            return Result(
                result.buildOrThrow(),
                com.google.common.collect.ImmutableMap.copyOf<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>(
                    registryFileHashes
                )
            )
        }

        /**
         * Returns a new [ModuleKey] that is transformed according to any existing overrides on
         * the dependency module.
         */
        fun applyOverrides(depKey: ModuleKey): ModuleKey {
            if (root.module.getName() == depKey.name) {
                return ModuleKey.Companion.ROOT
            }
            return ModuleKey(
                depKey.name,
                when (root.overrides.get(depKey.name)) {
                    -> com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.EMPTY
                    -> svo.version
                    null -> depKey.version
                }
            )
        }

        /**
         * Given a set of module keys to discover (the current "horizon"), return the next horizon
         * consisting of newly discovered module keys from the current set (mostly, their dependencies).
         * 
         * 
         * The current horizon contains keys to modules that are already in the `depGraph`.
         * Note also that this method mutates `predecessors` and `unfulfilledNodepEdgeModuleNames`.
         */
        fun advanceHorizon(horizon: com.google.common.collect.ImmutableSet<ModuleKey?>): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.Key> {
            val nextHorizon: com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.Key?> =
                com.google.common.collect.ImmutableSet.builder<com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.Key?>()
            for (moduleKey in horizon) {
                val module: InterimModule = depGraph.get(moduleKey)
                // The main group of module keys to discover are the current horizon's normal deps.
                for (depKey in module.getDeps().values()) {
                    if (depGraph.containsKey(depKey)) {
                        continue
                    }
                    predecessors.putIfAbsent(depKey, module.getKey())
                    nextHorizon.add(ModuleFileValue.Companion.key(depKey))
                }
                // Any of the current horizon's nodep deps should also be discovered ("fulfilled"), iff the
                // module they refer to already exists in the dep graph. Otherwise, record these unfulfilled
                // nodep edges, so that we can later decide whether to run another round of discovery.
                for (depKey in module.getNodepDeps()) {
                    if (depGraph.containsKey(depKey)) {
                        continue
                    }
                    if (!prevRoundModuleNames.contains(depKey.name)) {
                        unfulfilledNodepEdgeModuleNames.add(depKey.name)
                        continue
                    }
                    predecessors.putIfAbsent(depKey, module.getKey())
                    nextHorizon.add(ModuleFileValue.Companion.key(depKey))
                }
            }
            return nextHorizon.build()
        }

        init {
            this.env = env
            this.root = root
            this.prevRoundModuleNames = prevRoundModuleNames
        }

        /**
         * When an exception occurs while discovering a new dep, try to add information about the
         * dependency chain that led to that dep.
         */
        fun maybeReportDependencyChain(
            e: ExternalDepsException, depKey: ModuleKey?
        ): ExternalDepsException? {
            if (e.getDetailedExitCode().getFailureDetail() == null
                || !SHOW_DEP_CHAIN.contains(
                    e.getDetailedExitCode().getFailureDetail().getExternalDeps().getCode()
                )
            ) {
                // This covers cases such as a parse error in the lockfile or an I/O exception during
                // registry access, which aren't related to any particular module dep.
                return e
            }
            // Trace back a dependency chain to the root module. There can be multiple paths to the
            // failing module, but any of those is useful for debugging.
            val depChain: MutableList<ModuleKey?> = java.util.ArrayList<ModuleKey?>()
            depChain.add(depKey)
            var predecessor: ModuleKey? = depKey
            while ((predecessors.get(predecessor).also { predecessor = it }) != null) {
                depChain.add(predecessor)
            }
            Collections.reverse(depChain)
            val depChainString: String? =
                depChain.stream().map<String?>(java.util.function.Function { obj: ModuleKey? -> obj.toString() })
                    .collect(Collectors.joining(" -> "))
            return withCauseAndMessage(
                FailureDetails.ExternalDeps.Code.BAD_MODULE,
                e,
                "in module dependency chain %s",
                depChainString
            )
        }

        companion object {
            private val SHOW_DEP_CHAIN: com.google.common.collect.ImmutableSet<FailureDetails.ExternalDeps.Code?> =
                Code > immutableEnumSet<FailureDetails.ExternalDeps.Code?>(
                    FailureDetails.ExternalDeps.Code.BAD_MODULE,
                    FailureDetails.ExternalDeps.Code.MODULE_NOT_FOUND
                )
        }
    }
}
