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


import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code

/**
 * Runs module selection. This step of module resolution reads the output of [Discovery] and
 * applies the Minimal Version Selection algorithm to it, removing unselected modules from the
 * dependency graph and rewriting dependencies to point to the selected versions. It also returns an
 * un-pruned version of the dep graph for inspection purpose.
 * 
 * 
 * Minimal Version Selection (MVS) is used to select a single version for each module.
 * 
 * 
 *  * In the most basic case, only one version of each module is selected (ie. remains in the dep
 * graph). The selected version is simply the highest among all existing versions in the dep
 * graph. In other words, each module name forms a "selection group". If foo@1.5 is selected,
 * then any other foo@X is removed from the dep graph, and any module depending on foo@X will
 * depend on foo@1.5 instead.
 *  * As an extension of the above, we also remove any module that becomes unreachable from the
 * root module because of the removal of some other module.
 *  * Things get more complicated with multiple-version overrides. If module foo has a
 * multiple-version override which allows versions [1.3, 1.5, 2.0], then we further split the
 * selection groups by the target allowed version (keep in mind that versions are upgraded to
 * the nearest higher-or-equal allowed version). If, for example, some module depends on
 * foo@1.0, then it'll depend on foo@1.3 post-selection instead (and foo@1.0 will be removed).
 * If any of foo@2.2, or foo@3.0 exist in the dependency graph before selection, they must be
 * removed before the end of selection (by becoming unreachable, for example), otherwise it'll
 * be an error since they're not allowed by the override (these versions are in selection
 * groups that have no valid target allowed version).
 * 
 */
internal object Selection {
    /**
     * For the given module, compute its selection group. Versions of the same module that fall into
     * different "allowed version buckets" (defined by `multiple_version_override`) belong to
     * different selection groups.
     */
    private fun computeSelectionGroup(
        module: InterimModule,
        allowedVersionSets: com.google.common.collect.ImmutableMap<String?, com.google.common.collect.ImmutableSortedSet<com.google.devtools.build.lib.bazel.bzlmod.Version?>?>
    ): SelectionGroup {
        return com.google.devtools.build.lib.bazel.bzlmod.Selection.computeSelectionGroup(
            module.getKey().name, module.getKey().version, allowedVersionSets
        )
    }

    private fun computeSelectionGroup(
        name: String?,
        version: com.google.devtools.build.lib.bazel.bzlmod.Version?,
        allowedVersionSets: com.google.common.collect.ImmutableMap<String?, com.google.common.collect.ImmutableSortedSet<com.google.devtools.build.lib.bazel.bzlmod.Version?>?>
    ): SelectionGroup {
        val allowedVersions: com.google.common.collect.ImmutableSortedSet<com.google.devtools.build.lib.bazel.bzlmod.Version?>? =
            allowedVersionSets.get(name)
        var target: com.google.devtools.build.lib.bazel.bzlmod.Version? =
            com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.EMPTY
        if (allowedVersions != null) {
            // We use the `ceiling` method here to quickly locate the lowest allowed version
            // that's still no lower than this module's version.
            // If this module's version is higher than any allowed version (in which case EMPTY is
            // returned), it should result in an error. We don't immediately throw here because it might
            // still become unreferenced later.
            target = allowedVersions.ceiling(version)
            if (target == null) {
                target = com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.EMPTY
            }
        }
        return SelectionGroup(name, target)
    }

    @Throws(ExternalDepsException::class)
    private fun computeAllowedVersionSets(
        overrides: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?>,
        depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>
    ): com.google.common.collect.ImmutableMap<String?, com.google.common.collect.ImmutableSortedSet<com.google.devtools.build.lib.bazel.bzlmod.Version?>?> {
        val moduleToVersionsInDepGraph: MutableMap<String?, MutableSet<com.google.devtools.build.lib.bazel.bzlmod.Version?>?> =
            HashMap<String?, MutableSet<com.google.devtools.build.lib.bazel.bzlmod.Version?>?>()
        for (key in depGraph.keySet()) {
            moduleToVersionsInDepGraph
                .computeIfAbsent(
                    key.name,
                    java.util.function.Function { k: String? -> HashSet<com.google.devtools.build.lib.bazel.bzlmod.Version?>() })
                .add(key.version)
        }

        val allowedVersionSets: com.google.common.collect.ImmutableMap.Builder<String?, com.google.common.collect.ImmutableSortedSet<com.google.devtools.build.lib.bazel.bzlmod.Version?>?> =
            com.google.common.collect.ImmutableMap.builder<String?, com.google.common.collect.ImmutableSortedSet<com.google.devtools.build.lib.bazel.bzlmod.Version?>?>()
        for (entry in overrides.entrySet()) {
            if (entry.getValue() !is MultipleVersionOverride) {
                continue
            }
            val moduleName: String? = entry.getKey()
            for (v in override.versions) {
                if (!moduleToVersionsInDepGraph.getOrDefault(
                        moduleName,
                        com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.bazel.bzlmod.Version?>()
                    )!!.contains(v)
                ) {
                    throw ExternalDepsException.Companion.withMessage(
                        Code.VERSION_RESOLUTION_ERROR,
                        "multiple_version_override for module %s contains version %s, but it doesn't exist"
                                + " in the dependency graph",
                        moduleName,
                        v
                    )
                }
            }
            allowedVersionSets.put(
                moduleName,
                com.google.common.collect.ImmutableSortedSet.copyOf<com.google.devtools.build.lib.bazel.bzlmod.Version?>(
                    override.versions
                )
            )
        }
        return allowedVersionSets.buildOrThrow()
    }

    /** Runs module selection (aka version resolution).  */
    @Throws(ExternalDepsException::class)
    fun run(
        depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>,
        overrides: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?>
    ): Result {
        // Compute the allowed version sets for each module, and check that all versions listed in
        // multiple-version overrides exist in the dep graph.
        val allowedVersionSets: com.google.common.collect.ImmutableMap<String?, com.google.common.collect.ImmutableSortedSet<com.google.devtools.build.lib.bazel.bzlmod.Version?>?> =
            com.google.devtools.build.lib.bazel.bzlmod.Selection.computeAllowedVersionSets(overrides, depGraph)

        // For each module in the dep graph, pre-compute its selection group.
        val selectionGroups: com.google.common.collect.ImmutableMap<ModuleKey?, SelectionGroup?> =
            com.google.common.collect.ImmutableMap.copyOf<ModuleKey?, SelectionGroup?>(
                com.google.common.collect.Maps.transformValues<ModuleKey?, InterimModule?, SelectionGroup?>(
                    depGraph,
                    com.google.common.base.Function { module: InterimModule? ->
                        com.google.devtools.build.lib.bazel.bzlmod.Selection.computeSelectionGroup(
                            module,
                            allowedVersionSets
                        )
                    })
            )

        // Figure out the version to select for every selection group.
        val selectedVersions: MutableMap<SelectionGroup?, com.google.devtools.build.lib.bazel.bzlmod.Version?> =
            HashMap<SelectionGroup?, com.google.devtools.build.lib.bazel.bzlmod.Version?>()
        for (entry in selectionGroups.entrySet()) {
            val key: ModuleKey = entry.getKey()
            val selectionGroup: SelectionGroup? = entry.getValue()
            selectedVersions.merge(
                selectionGroup,
                key.version,
                java.util.function.BiFunction { a: com.google.devtools.build.lib.bazel.bzlmod.Version?, b: com.google.devtools.build.lib.bazel.bzlmod.Version? ->
                    com.google.common.collect.Comparators.max(
                        a,
                        b
                    )
                })
        }

        val resolutionStrategy: java.util.function.Function<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Version?> =
            java.util.function.Function { depKey: ModuleKey? ->
                selectedVersions.get(
                    com.google.devtools.build.lib.bazel.bzlmod.Selection.computeSelectionGroup(
                        depKey.name,
                        depKey.version,
                        allowedVersionSets
                    )
                )
            }

        val depGraphWalker = DepGraphWalker(depGraph, overrides, selectionGroups)

        // Walk the graph taking nodep edges into account.
        // If we selected a version that doesn't exist (e.g. because of multiple_version_override
        // snapping to a non-existent version), the walker will throw.
        val unused: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            depGraphWalker.walk(resolutionStrategy,  /* ignoreNodeps= */false)

        // Walk the graph again, this time ignoring nodeps, so that we don't end up with modules that
        // are only reachable via nodep edges.
        // This call _cannot_ throw because the "stricter" walk above already succeeded.
        // For example:
        //     A --> B 1.0 --> D 1.0 --> E 1.0
        //       `-> C 1.0 --> D 2.0 -nodep-> E 1.0
        // In this case, E should not show up in the final dep graph because it's only reachable
        // via a nodep edge (D 1.0 will have been pruned).
        val prunedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            depGraphWalker.walk(resolutionStrategy,  /* ignoreNodeps= */true)

        val unprunedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.copyOf<ModuleKey?, InterimModule?>(
                com.google.common.collect.Maps.transformValues<ModuleKey?, InterimModule?, InterimModule?>(
                    depGraph,
                    com.google.common.base.Function { module: InterimModule? ->
                        module.withDepsTransformed(
                            UnaryOperator { depKey: ModuleKey? ->
                                ModuleKey(
                                    depKey.name,
                                    resolutionStrategy.apply(depKey)
                                )
                            })
                    })
            )

        return com.google.devtools.build.lib.bazel.bzlmod.Selection.Result(prunedDepGraph, unprunedDepGraph)
    }

    /**
     * The result of selection.
     * 
     * @param resolvedDepGraph Final dep graph sorted in BFS iteration order, with unused modules
     * removed.
     * @param unprunedDepGraph Un-pruned dep graph, with updated dep keys, and additionally containing
     * the unused modules which were initially discovered (and their MODULE.bazel files loaded).
     * Does not contain modules overridden by `single_version_override` or [     ], only by `multiple_version_override`.
     */
    internal class Result(
        resolvedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>?,
        unprunedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>?
    ) {
        val resolvedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>?
        val unprunedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>?

        init {
            this.resolvedDepGraph = resolvedDepGraph
            this.unprunedDepGraph = unprunedDepGraph
        }
    }

    /**
     * During selection, a version is selected for each distinct "selection group".
     * 
     * @param targetAllowedVersion This is only used for modules with multiple-version overrides.
     */
    private class SelectionGroup(
        val moduleName: String?,
        targetAllowedVersion: com.google.devtools.build.lib.bazel.bzlmod.Version?
    ) {
        val targetAllowedVersion: com.google.devtools.build.lib.bazel.bzlmod.Version?

        init {
            this.targetAllowedVersion = targetAllowedVersion
        }
    }

    /**
     * Walks the dependency graph from the root node, collecting any reachable nodes through deps into
     * a new dep graph and checking that nothing conflicts.
     */
    internal class DepGraphWalker(
        oldDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>,
        overrides: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?>,
        selectionGroups: com.google.common.collect.ImmutableMap<ModuleKey?, SelectionGroup?>
    ) {
        private val oldDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>
        private val overrides: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?>
        private val selectionGroups: com.google.common.collect.ImmutableMap<ModuleKey?, SelectionGroup?>

        init {
            this.oldDepGraph = oldDepGraph
            this.overrides = overrides
            this.selectionGroups = selectionGroups
        }

        /**
         * Walks the old dep graph and builds a new dep graph containing only deps reachable from the
         * root module. The returned map has a guaranteed breadth-first iteration order.
         */
        @Throws(ExternalDepsException::class)
        fun walk(
            resolutionStrategy: java.util.function.Function<ModuleKey?, com.google.devtools.build.lib.bazel.bzlmod.Version?>,
            ignoreNodeps: Boolean
        ): com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> {
            val newDepGraph: com.google.common.collect.ImmutableMap.Builder<ModuleKey?, InterimModule?> =
                com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
            val known: MutableSet<ModuleKey?> = HashSet<ModuleKey?>()
            val toVisit: java.util.Queue<ModuleKeyAndDependent> = ArrayDeque<ModuleKeyAndDependent>()
            toVisit.add(ModuleKeyAndDependent(ModuleKey.Companion.ROOT, null))
            known.add(ModuleKey.Companion.ROOT)
            while (!toVisit.isEmpty()) {
                val moduleKeyAndDependent: ModuleKeyAndDependent = toVisit.remove()
                val key: ModuleKey = moduleKeyAndDependent.moduleKey
                val oldModule: InterimModule? = oldDepGraph.get(key)
                // Every selected version must exist in the original dependency graph.
                com.google.common.base.Preconditions.checkState(
                    oldModule != null, "Module %s unexpectedly missing from the dependency graph", key
                )
                val module: InterimModule =
                    oldModule.withDepsTransformed(
                        UnaryOperator { depKey: ModuleKey? ->
                            ModuleKey(
                                depKey.name,
                                resolutionStrategy.apply(depKey)
                            )
                        })
                visit(key, module, moduleKeyAndDependent.dependent)

                for (depKey in if (ignoreNodeps)
                    module.getDeps().values()
                else
                    com.google.common.collect.Iterables.concat<ModuleKey>(
                        module.getDeps().values(),
                        module.getNodepDeps()
                    )) {
                    if (known.add(depKey)) {
                        toVisit.add(ModuleKeyAndDependent(depKey, key))
                    }
                }
                newDepGraph.put(key, module)
            }
            return newDepGraph.buildOrThrow()
        }

        @Throws(ExternalDepsException::class)
        fun visit(key: ModuleKey, module: InterimModule, from: ModuleKey?) {
            if (overrides.get(key.name) is MultipleVersionOverride) {
                if (selectionGroups.get(key).targetAllowedVersion.isEmpty()) {
                    // This module has no target allowed version, which means that there's no higher allowed
                    // version.
                    com.google.common.base.Preconditions.checkState(
                        from != null, "the root module cannot have a multiple version override"
                    )
                    throw ExternalDepsException.Companion.withMessage(
                        Code.VERSION_RESOLUTION_ERROR,
                        "%s depends on %s which is not allowed by the multiple_version_override on %s,"
                                + " which allows only [%s]",
                        from,
                        key,
                        key.name,
                        JOINER.join(override.versions)
                    )
                }
            }

            // Make sure that we don't have `module` depending on the same dependency version twice.
            val depKeyToRepoName: HashMap<ModuleKey?, String?> = HashMap<ModuleKey?, String?>()
            for (depEntry in module.getDeps().entrySet()) {
                val repoName: String? = depEntry.getKey()
                val depKey: ModuleKey = depEntry.getValue()
                val previousRepoName: String? = depKeyToRepoName.put(depKey, repoName)
                if (previousRepoName != null) {
                    throw ExternalDepsException.Companion.withMessage(
                        Code.VERSION_RESOLUTION_ERROR,
                        ("%s depends on %s at least twice (with repo names %s and %s). Consider adding a"
                                + " multiple_version_override if you want to depend on multiple versions of"
                                + " %s simultaneously"),
                        key,
                        depKey,
                        repoName,
                        previousRepoName,
                        depKey.name
                    )
                }
            }
        }

        internal class ModuleKeyAndDependent(moduleKey: ModuleKey, dependent: ModuleKey?) {
            val moduleKey: ModuleKey
            val dependent: ModuleKey?

            init {
                this.moduleKey = moduleKey
                this.dependent = dependent
            }
        }


        companion object {
            private val JOINER: com.google.common.base.Joiner = com.google.common.base.Joiner.on(", ")
        }
    }
}
