// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.Artifact

/**
 * Return value for [com.google.devtools.build.lib.buildtool.AnalysisAndExecutionPhaseRunner].
 * This is meant to be the drop-in replacement for AnalysisResult later on. This is part of
 * https://github.com/bazelbuild/bazel/issues/14057. Internal: b/147350683.
 */
class AnalysisAndExecutionResult internal constructor(
    configuration: BuildConfigurationValue?,
    targetsToBuild: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
    aspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>?,
    targetsToTest: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
    targetsToSkip: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
    analysisFailureDetail: FailureDetail?,
    executionDetailedExitCode: DetailedExitCode?,
    artifactsToBuild: com.google.common.collect.ImmutableSet<Artifact?>?,
    parallelTests: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
    exclusiveTests: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
    exclusiveIfLocalTests: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
    topLevelContext: TopLevelArtifactContext?,
    topLevelTargetsWithConfigs: MutableCollection<TargetAndConfiguration?>?
) : com.google.devtools.build.lib.analysis.AnalysisResult(
    configuration,
    targetsToBuild,
    aspects,
    targetsToTest,
    targetsToSkip,
    analysisFailureDetail,  /* actionGraph= */
    null,
    artifactsToBuild,
    parallelTests,
    exclusiveTests,
    exclusiveIfLocalTests,
    topLevelContext,  /* packageRoots= */
    null,
    topLevelTargetsWithConfigs
) {
    private val executionDetailedExitCode: DetailedExitCode?

    init {
        this.executionDetailedExitCode = executionDetailedExitCode
    }

    fun getExecutionDetailedExitCode(): DetailedExitCode? {
        return executionDetailedExitCode
    }
}
