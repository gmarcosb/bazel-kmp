// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2

import com.google.devtools.build.lib.cmdline.PackageIdentifier

internal object RdepsVisitorUtils {
    @Throws(java.lang.InterruptedException::class)
    fun getMaybeFilteredRdeps(
        depAndRdeps: Iterable<DepAndRdep>, env: SkyQueryEnvironment
    ): Iterable<SkyKey?> {
        return if (env.hasDependencyFilter()) getFilteredRdeps(depAndRdeps, env) else getRdeps(depAndRdeps)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getFilteredRdeps(
        depAndRdeps: Iterable<DepAndRdep>, env: SkyQueryEnvironment
    ): Iterable<SkyKey?> {
        val filteredRdeps: java.util.ArrayList<SkyKey?> = java.util.ArrayList<SkyKey?>()

        val reverseDepMultimap: com.google.common.collect.Multimap<SkyKey?, SkyKey?> =
            com.google.common.collect.ArrayListMultimap.create<SkyKey?, SkyKey?>()
        for (depAndRdep in depAndRdeps) {
            if (depAndRdep.dep == null) {
                filteredRdeps.add(depAndRdep.rdep)
            } else {
                reverseDepMultimap.put(depAndRdep.dep, depAndRdep.rdep)
            }
        }

        val packageKeyToTargetKeyMap: com.google.common.collect.Multimap<SkyKey?, SkyKey?> =
            SkyQueryEnvironment.Companion.makePackageKeyToTargetKeyMap(
                com.google.common.collect.Iterables.concat<SkyKey?>(reverseDepMultimap.values())
            )
        val pkgIdsNeededForTargetification: MutableSet<PackageIdentifier?>? =
            SkyQueryEnvironment.Companion.getPkgIdsNeededForTargetification(packageKeyToTargetKeyMap)

        val packageSemaphore: MultisetSemaphore<PackageIdentifier?> = env.getPackageMultisetSemaphore()
        packageSemaphore.acquireAll(pkgIdsNeededForTargetification)
        try {
            if (!reverseDepMultimap.isEmpty()) {
                val filteredTargets: MutableCollection<Target?> =
                    env.filterRawReverseDepsOfTransitiveTraversalKeys(
                        reverseDepMultimap.asMap(), packageKeyToTargetKeyMap
                    )
                filteredTargets.stream()
                    .map<SkyKey?>(SkyQueryEnvironment.Companion.TARGET_TO_SKY_KEY)
                    .forEachOrdered { e: SkyKey? -> filteredRdeps.add(e) }
            }
        } finally {
            packageSemaphore.releaseAll(pkgIdsNeededForTargetification)
        }

        return filteredRdeps
    }

    private fun getRdeps(depAndRdeps: Iterable<DepAndRdep>): Iterable<SkyKey?> {
        return com.google.common.collect.Iterables.transform<DepAndRdep?, SkyKey?>(
            depAndRdeps,
            com.google.common.base.Function { depAndRdep: DepAndRdep? -> depAndRdep.rdep })
    }
}
