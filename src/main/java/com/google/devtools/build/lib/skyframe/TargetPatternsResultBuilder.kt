// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

/**
 * This class encapsulates logic behind computing final target set based on separate results from a
 * list of target patterns (eg, //foo:all -//bar/... //foo:test).
 */
internal class TargetPatternsResultBuilder {
    private val resolvedLabelsBuilder: MutableSet<Label> = CompactHashSet.create()
    private var packages: MutableMap<PackageIdentifier?, Package?>? = null

    /** Returns final set of targets and sets error flag if required.  */
    @Throws(java.lang.InterruptedException::class)
    fun build(walkableGraph: WalkableGraph): MutableCollection<Target?> {
        precomputePackages(walkableGraph)
        return transformLabelsIntoTargets(resolvedLabelsBuilder)
    }

    /**
     * Transforms `ResolvedTargets<Label>` to `ResolvedTargets<Target>`. Note that this
     * method is using information about packages, so [.precomputePackages] has to be called
     * before this method.
     */
    private fun transformLabelsIntoTargets(resolvedLabels: MutableSet<Label>): MutableCollection<Target?> {
        // precomputePackages has to be called before this method.
        val targets: MutableSet<Target?> = CompactHashSet.create()
        com.google.common.base.Preconditions.checkNotNull<MutableMap<PackageIdentifier?, Package?>?>(packages)
        for (label in resolvedLabels) {
            targets.add(getExistingTarget(label))
        }
        return targets
    }

    @Throws(java.lang.InterruptedException::class)
    private fun precomputePackages(walkableGraph: WalkableGraph) {
        val packagesToRequest: MutableSet<PackageIdentifier?> = this.packagesIdentifiers
        packages =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<PackageIdentifier?, Package?>(packagesToRequest.size())
        for (pkgIdentifier in packagesToRequest) {
            packages!!.put(pkgIdentifier, findPackageInGraph(pkgIdentifier, walkableGraph))
        }
    }

    private fun getExistingTarget(label: Label): Target {
        val pkg: Package = com.google.common.base.Preconditions.checkNotNull<Package>(
            packages!!.get(label.getPackageIdentifier()),
            label
        )
        try {
            return pkg.getTarget(label.name)
        } catch (e: NoSuchTargetException) {
            // This exception should not raise, because we are processing it during TargetPatternValues
            // evaluation in SkyframeTargetPatternEvaluator#parseTargetPatternKeys and values with errors
            // are not added to final result.
            throw java.lang.IllegalStateException(e)
        }
    }

    private val packagesIdentifiers: MutableSet<PackageIdentifier>
        get() {
            val packagesIdentifiers: MutableSet<PackageIdentifier?> = HashSet<PackageIdentifier?>()
            for (label in resolvedLabelsBuilder) {
                packagesIdentifiers.add(label.getPackageIdentifier())
            }
            return packagesIdentifiers
        }

    /** Adds the result from expansion of negative target pattern (eg, "-//foo:all").  */
    fun addLabelsOfPositivePattern(labels: ResolvedTargets<Label?>) {
        com.google.common.base.Preconditions.checkArgument(labels.getFilteredTargets().isEmpty())
        resolvedLabelsBuilder.addAll(labels.getTargets())
    }

    companion object {
        @Throws(java.lang.InterruptedException::class)
        private fun findPackageInGraph(
            pkgIdentifier: PackageIdentifier?, walkableGraph: WalkableGraph
        ): Package {
            return com.google.common.base.Preconditions.checkNotNull<Any?>(
                walkableGraph.getValue(pkgIdentifier) as PackageValue?, pkgIdentifier
            )
                .getPackage()
        }
    }
}
