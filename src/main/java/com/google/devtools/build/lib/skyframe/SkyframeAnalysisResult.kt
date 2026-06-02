// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.PackageRoots

/**
 * Encapsulates the raw analysis result of top level targets and aspects coming from Skyframe.
 */
open class SkyframeAnalysisResult internal constructor(
    private val hasLoadingError: Boolean,
    private val hasAnalysisError: Boolean,
    private val hasActionConflicts: Boolean,
    configuredTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?>,
    walkableGraph: WalkableGraph?,
    aspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>?,
    targetsWithConfiguration: com.google.common.collect.ImmutableList<TargetAndConfiguration?>?,
    packageRoots: PackageRoots?
) {
    private val configuredTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?>
    private val walkableGraph: WalkableGraph?
    private val aspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>?
    private val targetsWithConfiguration: com.google.common.collect.ImmutableList<TargetAndConfiguration?>?
    private val packageRoots: PackageRoots?

    init {
        this.configuredTargets = configuredTargets
        this.walkableGraph = walkableGraph
        this.aspects = aspects
        this.targetsWithConfiguration = targetsWithConfiguration
        this.packageRoots = packageRoots
    }

    /**
     * If the new simplified loading phase is enabled, then we can also see loading errors during the
     * analysis phase. This method returns true if any such errors were encountered. However, you also
     * always need to check if the loading result has an error! These will be merged eventually.
     */
    fun hasLoadingError(): Boolean {
        return hasLoadingError
    }

    fun hasAnalysisError(): Boolean {
        return hasAnalysisError
    }

    fun hasActionConflicts(): Boolean {
        return hasActionConflicts
    }

    fun getConfiguredTargets(): com.google.common.collect.ImmutableSet<ConfiguredTarget?> {
        return configuredTargets
    }

    fun getWalkableGraph(): WalkableGraph? {
        return walkableGraph
    }

    fun getAspects(): com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>? {
        return aspects
    }

    fun getTargetsWithConfiguration(): com.google.common.collect.ImmutableList<TargetAndConfiguration?>? {
        return targetsWithConfiguration
    }

    fun getPackageRoots(): PackageRoots? {
        return packageRoots
    }

    /**
     * Returns an equivalent [SkyframeAnalysisResult], except with errored targets removed from
     * the configured target list.
     */
    fun withAdditionalErroredTargets(
        erroredTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?>
    ): SkyframeAnalysisResult {
        return SkyframeAnalysisResult(
            hasLoadingError,  /* hasAnalysisError= */
            true,
            hasActionConflicts,
            com.google.common.collect.Sets.difference<ConfiguredTarget?>(configuredTargets, erroredTargets)
                .immutableCopy(),
            walkableGraph,
            aspects,
            targetsWithConfiguration,
            packageRoots
        )
    }
}
