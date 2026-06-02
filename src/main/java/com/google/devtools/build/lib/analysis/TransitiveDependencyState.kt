// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.cmdline.PackageIdentifier
import com.google.devtools.build.lib.collect.nestedset.NestedSet
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey
import com.google.devtools.build.lib.skyframe.PrerequisitePackageFunction
import java.util.Collections
import java.util.TreeMap

/** Groups state associated with transitive dependencies.  */
class TransitiveDependencyState(storeTransitivePackages: Boolean, prerequisitePackages: PrerequisitePackageFunction) {
    private val transitiveRootCauses: NestedSetBuilder<com.google.devtools.build.lib.causes.Cause?>

    /**
     * State for constructing the packages transitively loaded for the value being built.
     * 
     * 
     * See [ ][com.google.devtools.build.lib.analysis.ConfiguredObjectValue.getTransitivePackages].
     * 
     * 
     * Non-null when transitive packages are tracked, determined by [ ][com.google.devtools.build.lib.skyframe.SkyframeExecutor.shouldStoreTransitivePackagesInLoadingAndAnalysis].
     */
    private val packageCollector: PackageCollector?

    /**
     * Retrieves packages that were previously requested by transitive dependencies.
     * 
     * 
     * When the [ConfiguredTargetFunction] computes a value, it depends on properties of its
     * dependencies. In some cases, those values are read directly out of the dependency's underlying
     * [Target]. All instances of this are to be restricted to where [ ][ConfiguredTargetAndData.target] is read.
     * 
     * 
     * More ideally, those properties would be conveyed via providers of those dependencies, but
     * doing so would adversely affect resting heap usage whereas [ConfiguredTargetAndData] is
     * ephemeral. Distributed implementations will include these properties in an extra provider. It
     * won't affect memory because the underlying package won't exist on the node loading it remotely.
     * 
     * 
     * It's valid to obtain [Package]s of dependencies from this function instead of creating
     * an edge in `Skyframe` due to the transitive dependency through the [ ]. Invalidation of the [Package] propagates upwards through the
     * dependency. This is compatible with bottom-up change pruning because [ ] uses identity equals.
     */
    private val prerequisitePackages: PrerequisitePackageFunction

    init {
        this.transitiveRootCauses = NestedSetBuilder.stableOrder<com.google.devtools.build.lib.causes.Cause?>()
        this.packageCollector = if (storeTransitivePackages) PackageCollector() else null
        this.prerequisitePackages = prerequisitePackages
    }

    fun transitiveRootCauses(): NestedSetBuilder<com.google.devtools.build.lib.causes.Cause?> {
        return transitiveRootCauses
    }

    fun transitivePackages(): NestedSet<com.google.devtools.build.lib.packages.Package.Metadata?>? {
        if (packageCollector == null) {
            return null
        }
        return packageCollector.buildSet()
    }

    fun addTransitiveCauses(transitiveCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?) {
        transitiveRootCauses.addTransitive(transitiveCauses)
    }

    fun addTransitiveCause(cause: com.google.devtools.build.lib.causes.Cause?) {
        transitiveRootCauses.add(cause)
    }

    fun hasRootCause(): Boolean {
        return !transitiveRootCauses.isEmpty()
    }

    fun storeTransitivePackages(): Boolean {
        return packageCollector != null
    }

    /** Adds to the set of transitive package metadata if [.storeTransitivePackages] is true.  */
    fun updateTransitivePackages(pkg: com.google.devtools.build.lib.packages.Package.Metadata?) {
        if (packageCollector == null) {
            return
        }
        packageCollector.packages.add(pkg)
    }

    /** Adds to the set of transitive package metadata if [.storeTransitivePackages] is true.  */
    fun updateTransitivePackages(
        key: ConfiguredTargetKey?, packages: NestedSet<com.google.devtools.build.lib.packages.Package.Metadata?>?
    ) {
        if (packageCollector == null) {
            return
        }
        packageCollector.configuredTargetPackages.put(key, packages)
    }

    /** Adds to the set of transitive package metadata if [.storeTransitivePackages] is true.  */
    fun updateTransitivePackages(
        key: AspectKey?,
        packages: NestedSet<com.google.devtools.build.lib.packages.Package.Metadata?>?
    ) {
        if (packageCollector == null) {
            return
        }
        packageCollector.aspectPackages.put(key, packages)
    }

    @Throws(java.lang.InterruptedException::class)
    fun getDependencyPackage(packageId: PackageIdentifier?): com.google.devtools.build.lib.packages.Package? {
        return prerequisitePackages.getExistingPackage(packageId)
    }

    /**
     * Collects package metadata of dependencies to be unified in a [NestedSet].
     * 
     * 
     * Performs bookkeeping so the result is deterministic.
     * 
     * 
     * Work in Skyframe may complete in arbitrary order due to missing values and restarts. For
     * example, if a client requests `//foo` and `//bar`, it could receive any of the
     * following: `(//foo, null), (null, //bar), (//foo, //bar) or (null, null)`.
     * 
     * 
     * This class tracks how the [Package]s are added so they can be given a deterministic
     * order. This is required for determinism of [ActionKeyComputer.computeKey].
     */
    private class PackageCollector {
        /**
         * Keeps packages that were added directly as a list.
         * 
         * 
         * These will be sorted.
         */
        private val packages: java.util.ArrayList<com.google.devtools.build.lib.packages.Package.Metadata?> =
            java.util.ArrayList<com.google.devtools.build.lib.packages.Package.Metadata?>()

        /** Stores transitive [Package.Metadata]s of [ConfiguredTargetValues]s.  */
        private val configuredTargetPackages: TreeMap<ConfiguredTargetKey?, NestedSet<com.google.devtools.build.lib.packages.Package.Metadata?>?> =
            TreeMap<ConfiguredTargetKey?, NestedSet<com.google.devtools.build.lib.packages.Package.Metadata?>?>(
                ConfiguredTargetKey.ORDERING
            )

        /** Stores transitive [Package.Metadata]s of [AspectValue]s.  */
        private val aspectPackages: TreeMap<AspectKey?, NestedSet<com.google.devtools.build.lib.packages.Package.Metadata?>?> =
            TreeMap<AspectKey?, NestedSet<com.google.devtools.build.lib.packages.Package.Metadata?>?>(AspectKey.ORDERING)

        /**
         * Constructs the deterministically ordered result.
         * 
         * 
         * It's safe to call this multiple times.
         */
        fun buildSet(): NestedSet<com.google.devtools.build.lib.packages.Package.Metadata?>? {
            val result: NestedSetBuilder<com.google.devtools.build.lib.packages.Package.Metadata?> =
                NestedSetBuilder.stableOrder<com.google.devtools.build.lib.packages.Package.Metadata?>()

            Collections.sort<com.google.devtools.build.lib.packages.Package.Metadata?>(
                packages,
                java.util.Comparator.comparing<com.google.devtools.build.lib.packages.Package.Metadata?, PackageIdentifier?>(
                    com.google.devtools.build.lib.packages.Package.Metadata::packageIdentifier
                )
            )
            result.addAll(packages)

            for (packageSet in configuredTargetPackages.values) {
                result.addTransitive(packageSet)
            }
            for (packageSet in aspectPackages.values) {
                result.addTransitive(packageSet)
            }

            return result.build()
        }
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun createForTesting(): TransitiveDependencyState {
            return TransitiveDependencyState( /* storeTransitivePackages= */
                false,  // Always returning null here causes the underlying code to fall back on declaring Package
                // edges for prerequisites, which is benign.
                /* prerequisitePackages= */
                PrerequisitePackageFunction { p: PackageIdentifier? -> null })
        }
    }
}
