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

import com.google.devtools.build.lib.actions.ActionGraph

/** Return value for [com.google.devtools.build.lib.buildtool.AnalysisPhaseRunner].  */
open class AnalysisResult internal constructor(
    configuration: BuildConfigurationValue?,
    targetsToBuild: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
    aspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>?,
    targetsToTest: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
    targetsToSkip: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
    failureDetail: FailureDetail?,
    actionGraph: ActionGraph?,
    artifactsToBuild: com.google.common.collect.ImmutableSet<Artifact?>?,
    parallelTests: com.google.common.collect.ImmutableSet<ConfiguredTarget?>,
    exclusiveTests: com.google.common.collect.ImmutableSet<ConfiguredTarget?>,
    exclusiveIfLocalTests: com.google.common.collect.ImmutableSet<ConfiguredTarget?>,
    topLevelContext: TopLevelArtifactContext?,
    packageRoots: PackageRoots?,
    topLevelTargetsWithConfigs: MutableCollection<TargetAndConfiguration?>?
) {
    private val configuration: BuildConfigurationValue?
    private val targetsToBuild: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?
    private val targetsToTest: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?
    private val targetsToSkip: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?
    private val failureDetail: FailureDetail?
    private val actionGraph: ActionGraph?
    private val artifactsToBuild: com.google.common.collect.ImmutableSet<Artifact?>?
    private val parallelTests: com.google.common.collect.ImmutableSet<ConfiguredTarget?>
    private val exclusiveTests: com.google.common.collect.ImmutableSet<ConfiguredTarget?>
    private val exclusiveIfLocalTests: com.google.common.collect.ImmutableSet<ConfiguredTarget?>
    private val topLevelContext: TopLevelArtifactContext?
    private val aspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>?
    private val packageRoots: PackageRoots?
    private val topLevelTargetsWithConfigs: MutableCollection<TargetAndConfiguration?>?

    init {
        this.configuration = configuration
        this.targetsToBuild = targetsToBuild
        this.aspects = aspects
        this.targetsToTest = targetsToTest
        this.targetsToSkip = targetsToSkip
        this.failureDetail = failureDetail
        this.actionGraph = actionGraph
        this.artifactsToBuild = artifactsToBuild
        this.parallelTests = parallelTests
        this.exclusiveTests = exclusiveTests
        this.exclusiveIfLocalTests = exclusiveIfLocalTests
        this.topLevelContext = topLevelContext
        this.packageRoots = packageRoots
        this.topLevelTargetsWithConfigs = topLevelTargetsWithConfigs
    }

    fun getConfiguration(): BuildConfigurationValue? {
        return configuration
    }

    /**
     * Returns configured targets to build.
     */
    fun getTargetsToBuild(): com.google.common.collect.ImmutableSet<ConfiguredTarget?>? {
        return targetsToBuild
    }

    /** @see PackageRoots
     */
    fun getPackageRoots(): PackageRoots? {
        return packageRoots
    }

    /** Returns aspects to build.  */
    fun getAspectsMap(): com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>? {
        return aspects
    }

    /**
     * Returns the configured targets to run as tests, or `null` if testing was not requested
     * (e.g. "build" command rather than "test" command).
     */
    fun getTargetsToTest(): com.google.common.collect.ImmutableSet<ConfiguredTarget?>? {
        return targetsToTest
    }

    /**
     * Returns the configured targets that should not be executed because they're not
     * platform-compatible with the current build.
     * 
     * 
     * For example: tests that aren't intended for the designated CPU.
     */
    fun getTargetsToSkip(): com.google.common.collect.ImmutableSet<ConfiguredTarget?>? {
        return targetsToSkip
    }

    fun getArtifactsToBuild(): com.google.common.collect.ImmutableSet<Artifact?>? {
        return artifactsToBuild
    }

    fun getExclusiveTests(): com.google.common.collect.ImmutableSet<ConfiguredTarget?> {
        return exclusiveTests
    }

    fun getExclusiveIfLocalTests(): com.google.common.collect.ImmutableSet<ConfiguredTarget?> {
        return exclusiveIfLocalTests
    }

    fun getParallelTests(): com.google.common.collect.ImmutableSet<ConfiguredTarget?> {
        return parallelTests
    }

    /** Returns a [FailureDetail], if any failures occurred.  */
    fun getFailureDetail(): FailureDetail? {
        return failureDetail
    }

    fun hasError(): Boolean {
        return failureDetail != null
    }

    /**
     * Returns the action graph.
     */
    fun getActionGraph(): ActionGraph? {
        return actionGraph
    }

    fun getTopLevelContext(): TopLevelArtifactContext? {
        return topLevelContext
    }

    fun getTopLevelTargetsWithConfigs(): MutableCollection<TargetAndConfiguration?>? {
        return topLevelTargetsWithConfigs
    }

    /**
     * Returns an equivalent [AnalysisResult], except with exclusive tests treated as parallel
     * tests.
     */
    fun withExclusiveTestsAsParallelTests(): AnalysisResult {
        return com.google.devtools.build.lib.analysis.AnalysisResult(
            configuration,
            targetsToBuild,
            aspects,
            targetsToTest,
            targetsToSkip,
            failureDetail,
            actionGraph,
            artifactsToBuild,
            com.google.common.collect.Sets.union<ConfiguredTarget?>(parallelTests, exclusiveTests)
                .immutableCopy(),  /* exclusiveTests= */
            com.google.common.collect.ImmutableSet.of<ConfiguredTarget?>(),
            exclusiveIfLocalTests,
            topLevelContext,
            packageRoots,
            topLevelTargetsWithConfigs
        )
    }

    /**
     * Returns an equivalent [AnalysisResult], except with exclusive tests treated as parallel
     * tests.
     */
    fun withExclusiveIfLocalTestsAsParallelTests(): AnalysisResult {
        return com.google.devtools.build.lib.analysis.AnalysisResult(
            configuration,
            targetsToBuild,
            aspects,
            targetsToTest,
            targetsToSkip,
            failureDetail,
            actionGraph,
            artifactsToBuild,
            com.google.common.collect.Sets.union<ConfiguredTarget?>(parallelTests, exclusiveIfLocalTests)
                .immutableCopy(),
            exclusiveTests,  /* exclusiveIfLocalTests= */
            com.google.common.collect.ImmutableSet.of<ConfiguredTarget?>(),
            topLevelContext,
            packageRoots,
            topLevelTargetsWithConfigs
        )
    }
}
