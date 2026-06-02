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

/** Encapsulates the raw analysis result of top level targets and aspects coming from Skyframe.  */
class SkyframeAnalysisAndExecutionResult private constructor(
    hasLoadingError: Boolean,
    hasAnalysisError: Boolean,
    hasActionConflicts: Boolean,
    configuredTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
    walkableGraph: WalkableGraph?,
    aspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>?,
    targetsWithConfiguration: com.google.common.collect.ImmutableList<TargetAndConfiguration?>?,
    packageRoots: PackageRoots?,
    representativeExecutionExitCode: DetailedExitCode?
) : SkyframeAnalysisResult(
    hasLoadingError,
    hasAnalysisError,
    hasActionConflicts,
    configuredTargets,
    walkableGraph,
    aspects,
    targetsWithConfiguration,
    packageRoots
) {
    private val representativeExecutionExitCode: DetailedExitCode?

    init {
        this.representativeExecutionExitCode = representativeExecutionExitCode
    }

    fun getRepresentativeExecutionExitCode(): DetailedExitCode? {
        return representativeExecutionExitCode
    }

    companion object {
        fun success(
            configuredTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
            walkableGraph: WalkableGraph?,
            aspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>?,
            targetsWithConfiguration: com.google.common.collect.ImmutableList<TargetAndConfiguration?>?,
            packageRoots: PackageRoots?
        ): SkyframeAnalysisAndExecutionResult {
            return SkyframeAnalysisAndExecutionResult( /* hasLoadingError= */
                false,  /* hasAnalysisError= */
                false,  /* hasActionConflicts= */
                false,
                configuredTargets,
                walkableGraph,
                aspects,
                targetsWithConfiguration,
                packageRoots,  /* representativeExecutionExitCode= */
                null
            )
        }

        fun withErrors(
            hasLoadingError: Boolean,
            hasAnalysisError: Boolean,
            hasActionConflicts: Boolean,
            configuredTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
            walkableGraph: WalkableGraph?,
            aspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>?,
            targetsWithConfiguration: com.google.common.collect.ImmutableList<TargetAndConfiguration?>?,
            packageRoots: PackageRoots?,
            representativeExecutionExitCode: DetailedExitCode?
        ): SkyframeAnalysisAndExecutionResult {
            return SkyframeAnalysisAndExecutionResult(
                hasLoadingError,
                hasAnalysisError,
                hasActionConflicts,
                configuredTargets,
                walkableGraph,
                aspects,
                targetsWithConfiguration,
                packageRoots,
                representativeExecutionExitCode
            )
        }
    }
}
