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

import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil.configurationId

/**
 * This event is fired during the build, when it becomes known that the analysis of a top-level
 * target cannot be completed because of an error in one of its dependencies.
 */
class AnalysisFailureEvent private constructor(
    failedTarget: ConfiguredTargetKey,
    failedAspect: AspectKey?,
    isConfigured: Boolean,
    rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>
) : BuildEvent {
    private val failedTarget: ConfiguredTargetKey
    private val failedAspect: AspectKey?

    /**
     * True if the target is configured.
     * 
     * 
     * The configuration of a target is undefined until its analysis is complete so this is often
     * false, but true for aspects and action conflict errors, both of which occur after the
     * configuration is determined.
     */
    private val isConfigured: Boolean

    private val rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>

    init {
        this.failedTarget = failedTarget
        this.failedAspect = failedAspect
        this.isConfigured = isConfigured
        this.rootCauses = rootCauses
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("failedAspect", failedAspect)
            .add("failedTarget", failedTarget)
            .add("isConfigured", isConfigured)
            .add("legacyFailureReason", getLegacyFailureReason())
            .toString()
    }

    fun getFailedTarget(): ConfiguredTargetKey {
        return failedTarget
    }

    @com.google.common.annotations.VisibleForTesting
    fun getConfigurationId(): BuildEventId? {
        return if (isConfigured) configurationId(failedTarget.getConfigurationKey()) else null
    }

    /**
     * Returns the label of a single root cause. Use [.getRootCauses] to report all root causes.
     */
    fun getLegacyFailureReason(): Label? {
        if (rootCauses.isEmpty()) {
            return null
        }
        return rootCauses.toList().get(0).getLabel()
    }

    fun getRootCauses(): NestedSet<com.google.devtools.build.lib.causes.Cause?> {
        return rootCauses
    }

    public override fun getEventId(): BuildEventId {
        val label: Label? = failedTarget.getLabel()
        if (!isConfigured) {
            return BuildEventIdUtil.targetConfigured(label)
        }
        if (failedAspect == null) {
            return BuildEventIdUtil.targetCompleted(
                label, configurationId(failedTarget.getConfigurationKey())
            )
        }
        return BuildEventIdUtil.aspectCompleted(
            label, configurationId(failedAspect.getConfigurationKey()), failedAspect.getAspectName()
        )
    }

    public override fun getChildrenEvents(): MutableCollection<BuildEventId?> {
        return com.google.common.collect.ImmutableList.copyOf<E?>(
            com.google.common.collect.Iterables.transform<F?, T?>(
                rootCauses.toList(),
                com.google.common.base.Function { cause: F? -> cause.getIdProto() })
        )
    }

    public override fun asStreamProto(converters: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
        return GenericBuildEvent.protoChaining(this)
            .setAborted(
                BuildEventStreamProtos.Aborted.newBuilder()
                    .setReason(BuildEventStreamProtos.Aborted.AbortReason.ANALYSIS_FAILURE)
                    .build()
            )
            .build()
    }

    companion object {
        fun whileAnalyzingTarget(
            failedTarget: ConfiguredTargetKey, rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>
        ): AnalysisFailureEvent {
            return AnalysisFailureEvent(
                failedTarget,  /* failedAspect= */null,  /* isConfigured= */false, rootCauses
            )
        }

        fun actionConflict(
            failedTarget: ActionLookupKey?, rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>
        ): AnalysisFailureEvent {
            com.google.common.base.Preconditions.checkArgument(
                failedTarget is ConfiguredTargetKey || failedTarget is AspectKey
            )
            if (failedTarget is ConfiguredTargetKey) {
                return AnalysisFailureEvent(
                    failedTarget as ConfiguredTargetKey?,  /* failedAspect= */
                    null,  /* isConfigured= */
                    true,
                    rootCauses
                )
            }
            val failedAspect: AspectKey = failedTarget as AspectKey
            return AnalysisFailureEvent(
                failedAspect.getBaseConfiguredTargetKey(),
                failedAspect,  /* isConfigured= */
                true,
                rootCauses
            )
        }
    }
}
