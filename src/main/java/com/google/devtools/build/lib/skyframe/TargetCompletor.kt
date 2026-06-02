// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.CompletionContext

/** Manages completing builds for configured targets.  */
internal class TargetCompletor
private constructor(announceTargetSummaries: SkyframeActionExecutor) :
    Completor<ConfiguredTargetValue?, TargetCompletionValue?, TargetCompletionKey?> {
    private val skyframeActionExecutor: SkyframeActionExecutor

    init {
        // SkyframeActionExecutor.options not populated yet, so store and query lazily later
        this.skyframeActionExecutor = announceTargetSummaries
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getRootCauseError(
        key: TargetCompletionKey, value: ConfiguredTargetValue?, rootCause: LabelCause, env: SkyFunction.Environment?
    ): com.google.devtools.build.lib.events.Event? {
        return com.google.devtools.build.lib.events.Event.error(
            getLocationIdentifier(key, value, env),
            java.lang.String.format("%s: %s", key.actionLookupKey().getLabel(), rootCause.message)
        )
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getLocationIdentifier(
        key: TargetCompletionKey?, value: ConfiguredTargetValue?, env: SkyFunction.Environment?
    ): net.starlark.java.syntax.Location {
        return ConfiguredTargetAndData.fromExistingConfiguredTargetInSkyframe(value, env).getLocation()
    }

    val result: TargetCompletionValue
        get() = TargetCompletionValue.INSTANCE

    @Throws(java.lang.InterruptedException::class)
    override fun createFailed(
        skyKey: TargetCompletionKey?,
        value: ConfiguredTargetValue?,
        rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?,
        ctx: CompletionContext?,
        outputs: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?>?,
        env: SkyFunction.Environment?
    ): TargetCompleteEvent {
        return TargetCompleteEvent.createFailed(
            ConfiguredTargetAndData.fromExistingConfiguredTargetInSkyframe(value, env),
            ctx,
            rootCauses,
            outputs,
            skyframeActionExecutor.publishTargetSummaries()
        )
    }

    @Throws(java.lang.InterruptedException::class)
    override fun createSucceeded(
        skyKey: TargetCompletionKey,
        value: ConfiguredTargetValue,
        completionContext: CompletionContext?,
        artifactsToBuild: ArtifactsToBuild,
        env: SkyFunction.Environment
    ): TargetCompleteEvent? {
        val target: ConfiguredTarget = value.getConfiguredTarget()
        val configuredTargetAndData: ConfiguredTargetAndData =
            ConfiguredTargetAndData.fromExistingConfiguredTargetInSkyframe(value, env)
        if (skyKey.willTest()) {
            return TargetCompleteEvent.successfulBuildSchedulingTest(
                configuredTargetAndData,
                completionContext,
                artifactsToBuild.getAllArtifactsByOutputGroup(),
                skyframeActionExecutor.publishTargetSummaries()
            )
        } else {
            if (target is InputFileConfiguredTarget) {
                env.getListener()
                    .handle(
                        com.google.devtools.build.lib.events.Event.warn(
                            configuredTargetAndData.getLocation(),
                            (target.getLabel()
                                    + " is a source file, nothing will be built for it. If you want to build a"
                                    + " target that consumes this file, try --compile_one_dependency")
                        )
                    )
            }
            return TargetCompleteEvent.successfulBuild(
                configuredTargetAndData,
                completionContext,
                artifactsToBuild.getAllArtifactsByOutputGroup(),
                skyframeActionExecutor.publishTargetSummaries()
            )
        }
    }

    companion object {
        fun targetCompletionFunction(
            pathResolverFactory: PathResolverFactory?,
            skyframeActionExecutor: SkyframeActionExecutor,
            topLevelArtifactsMetric: MetadataConsumerForMetrics.FilesMetricConsumer?,
            actionRewindStrategy: ActionRewindStrategy?,
            bugReporter: BugReporter?
        ): SkyFunction {
            return CompletionFunction<ValueT?, ResultT?, KeyT?>(
                pathResolverFactory,
                TargetCompletor(skyframeActionExecutor),
                skyframeActionExecutor,
                topLevelArtifactsMetric,
                actionRewindStrategy,
                bugReporter
            )
        }
    }
}
