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

/** Manages completing builds for aspects.  */
internal class AspectCompletor

    : Completor<AspectValue?, AspectCompletionValue?, AspectCompletionKey?> {
    @Throws(java.lang.InterruptedException::class)
    override fun getRootCauseError(
        key: AspectCompletionKey, value: AspectValue?, rootCause: LabelCause, env: SkyFunction.Environment
    ): com.google.devtools.build.lib.events.Event? {
        val aspectKey: AspectKey = key.actionLookupKey()
        // Skyframe lookups here should not have large effect on the number of dependency edges as
        // they are only needed for failed top-level aspects.
        val baseTargetValue: ConfiguredTargetValue? =
            env.getValue(aspectKey.getBaseConfiguredTargetKey()) as ConfiguredTargetValue?
        com.google.common.base.Preconditions.checkNotNull<Any?>(
            baseTargetValue,
            "Base configured target value should be ready!"
        )

        val configuredTargetAndData: ConfiguredTargetAndData =
            ConfiguredTargetAndData.fromExistingConfiguredTargetInSkyframe(baseTargetValue, env)

        return com.google.devtools.build.lib.events.Event.error(
            configuredTargetAndData.getLocation(),
            java.lang.String.format(
                "%s, aspect %s: %s",
                aspectKey.getLabel(), aspectKey.getAspectClass().getName(), rootCause.message
            )
        )
    }

    override fun getLocationIdentifier(
        key: AspectCompletionKey,
        value: AspectValue?,
        env: SkyFunction.Environment?
    ): String {
        val aspectKey: AspectKey = key.actionLookupKey()
        return aspectKey.getLabel() + ", aspect " + aspectKey.getAspectClass().getName()
    }

    val result: AspectCompletionValue
        get() = AspectCompletionValue.INSTANCE

    override fun createFailed(
        skyKey: AspectCompletionKey,
        value: AspectValue,
        rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?,
        ctx: CompletionContext?,
        outputs: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?>?,
        env: SkyFunction.Environment?
    ): AspectCompleteEvent {
        return AspectCompleteEvent.createFailed(
            skyKey.actionLookupKey(), ctx, rootCauses, outputs, value.getWritesOutputToMasterLog()
        )
    }

    override fun createSucceeded(
        skyKey: AspectCompletionKey,
        value: AspectValue,
        completionContext: CompletionContext?,
        artifactsToBuild: ArtifactsToBuild,
        env: SkyFunction.Environment?
    ): AspectCompleteEvent {
        return AspectCompleteEvent.createSuccessful(
            skyKey.actionLookupKey(),
            completionContext,
            artifactsToBuild.getAllArtifactsByOutputGroup(),
            value.getWritesOutputToMasterLog()
        )
    }

    companion object {
        fun aspectCompletionFunction(
            pathResolverFactory: PathResolverFactory?,
            skyframeActionExecutor: SkyframeActionExecutor?,
            topLevelArtifactsMetric: MetadataConsumerForMetrics.FilesMetricConsumer?,
            actionRewindStrategy: ActionRewindStrategy?,
            bugReporter: BugReporter?
        ): SkyFunction {
            return CompletionFunction<ValueT?, ResultT?, KeyT?>(
                pathResolverFactory,
                AspectCompletor(),
                skyframeActionExecutor,
                topLevelArtifactsMetric,
                actionRewindStrategy,
                bugReporter
            )
        }
    }
}
