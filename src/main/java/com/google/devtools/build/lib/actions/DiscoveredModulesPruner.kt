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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.collect.nestedset.NestedSet

/**
 * Prunes discovered CPP modules by filtering out modules which are already accounted for
 * transitively.
 * 
 * 
 * [.DEFAULT] should be used except when special handling is required for [NestedSet]
 * data backed by remote storage.
 */
interface DiscoveredModulesPruner {
    /**
     * Computes top-level only modules, i.e. used modules that aren't also dependencies of other used
     * modules.
     * 
     * 
     * The returned set's iteration order should match that of `usedModules`.
     * 
     * @param action the action requesting module pruning
     * @param usedModules set of all modules used by `action`
     * @param transitivelyUsedModules map from module to its transitive module dependencies
     * @return a subset of `usedModules` without elements that are already accounted for via
     * transitive dependencies
     * @throws InterruptedException if [NestedSet] data in `transitivelyUsedModules` is
     * backed by remote storage and an interruption occurs during retrieval
     * @throws LostInputsActionExecutionException if [NestedSet] data in `transitivelyUsedModules` is backed by remote storage and retrieval fails (e.g. due to
     * timeout)
     */
    @Throws(java.lang.InterruptedException::class, LostInputsActionExecutionException::class)
    fun computeTopLevelModules(
        action: com.google.devtools.build.lib.actions.Action?,
        usedModules: MutableSet<out Artifact?>?,
        transitivelyUsedModules: com.google.common.collect.ImmutableMap<Artifact?, NestedSet<Artifact?>?>?
    ): MutableSet<Artifact?>?

    companion object {
        /** Default implementation of module pruning for in-memory [NestedSet] data.  */
        val DEFAULT:  // See comment on topLevel.remove().
                DiscoveredModulesPruner =
            DiscoveredModulesPruner { action: com.google.devtools.build.lib.actions.Action?, usedModules: MutableSet<out Artifact?>?, transitivelyUsedModules: com.google.common.collect.ImmutableMap<Artifact?, NestedSet<Artifact?>?>? ->
                val topLevel: MutableSet<Artifact?> = LinkedHashSet<Artifact?>(usedModules)
                // It is better to iterate over each nested set here instead of creating a joint one and
                // iterating over it, as this makes use of NestedSet's memoization (each of them has likely
                // been iterated over before).
                for (entry in transitivelyUsedModules.entries) {
                    val directDep: Artifact? = entry.key
                    if (!topLevel.contains(directDep)) {
                        // If this module was removed from topLevel because it is a dependency of another
                        // module, we can safely ignore it now as all of its dependants have also been removed.
                        continue
                    }
                    val transitiveDeps: MutableList<Artifact?> = entry.value.toList()

                    // Don't use Set.removeAll() here as that iterates over the smaller set (topLevel, which
                    // would support efficient lookup) and looks up in the larger one (transitiveDeps, which
                    // is a linear scan).
                    for (module in transitiveDeps) {
                        topLevel.remove(module)
                    }
                }
                topLevel
            }
    }
}
