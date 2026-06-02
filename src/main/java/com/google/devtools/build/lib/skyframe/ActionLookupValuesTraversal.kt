// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.ArtifactConflictFinder.NUM_JOBS

/** Represents the traversal of the ActionLookupValues in a build.  */
class ActionLookupValuesTraversal {
    // Some metrics indicate this is a rough average # of ALVs in a build.
    private val actionLookupValueShards: Sharder<ActionLookupValue?> =
        Sharder<ActionLookupValue?>(NUM_JOBS,  /* expectedTotalSize= */200000)

    // Metrics.
    private val configuredObjectCount: AtomicInteger = AtomicInteger()
    private val configuredTargetCount: AtomicInteger = AtomicInteger()
    private val actionCount: java.util.concurrent.atomic.LongAdder = java.util.concurrent.atomic.LongAdder()
    private val actionCountNotIncludingAspects: java.util.concurrent.atomic.LongAdder =
        java.util.concurrent.atomic.LongAdder()
    private val inputFileConfiguredTargetCount: AtomicInteger = AtomicInteger()
    private val outputFileConfiguredTargetCount: AtomicInteger = AtomicInteger()
    private val otherConfiguredTargetCount: AtomicInteger = AtomicInteger()

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    fun accumulate(key: ActionLookupKey, value: SkyValue) {
        if (value is RemoteConfiguredTargetValue) {
            // Remotely fetched values do not have actions.
            return
        }

        val isConfiguredTarget = value is ConfiguredTargetValue
        val isActionLookupValue = value is ActionLookupValue
        if (!isConfiguredTarget && !isActionLookupValue) {
            BugReport.sendBugReport(
                java.lang.IllegalStateException(
                    java.lang.String.format(
                        "Should only be called with ConfiguredTargetValue or ActionLookupValue: %s %s"
                                + " %s",
                        value.getClass(), key, value
                    )
                )
            )
            return
        }
        if (isConfiguredTarget
            && key.getConfigurationKey() != (value as ConfiguredTargetValue).getConfiguredTarget().getConfigurationKey()
        ) {
            // The configuration of the key doesn't match the configuration of the value. This means that
            // the ConfiguredTargetValue is delegated from a different key. This ConfiguredTargetValue
            // will show up again under its own key. Avoids double counting by skipping accumulation.
            return
        }
        configuredObjectCount.incrementAndGet()
        if (isConfiguredTarget) {
            configuredTargetCount.incrementAndGet()
        }
        if (isActionLookupValue) {
            val alv: ActionLookupValue = value as ActionLookupValue
            val numActions: Int = alv.getActions().size()
            actionCount.add(numActions.toLong())
            if (isConfiguredTarget) {
                actionCountNotIncludingAspects.add(numActions.toLong())
            }
            actionLookupValueShards.add(alv)
            return
        }
        if (value !is NonRuleConfiguredTargetValue) {
            BugReport.sendBugReport(
                java.lang.IllegalStateException(
                    java.lang.String.format("Unexpected value type: %s %s %s", value.getClass(), key, value)
                )
            )
            return
        }
        val counter: AtomicInteger =
            when (value.getConfiguredTarget()) {
                -> inputFileConfiguredTargetCount
                -> outputFileConfiguredTargetCount
                else -> otherConfiguredTargetCount
            }
        counter.incrementAndGet()
    }

    fun getActionLookupValueShards(): Sharder<ActionLookupValue?> {
        return actionLookupValueShards
    }

    fun getActionCount(): Int {
        return actionCount.intValue()
    }

    val metrics: BuildEventStreamProtos.BuildMetrics.BuildGraphMetrics.Builder
        get() = BuildEventStreamProtos.BuildMetrics.BuildGraphMetrics.newBuilder()
            .setActionLookupValueCount(configuredObjectCount.get())
            .setActionLookupValueCountNotIncludingAspects(configuredTargetCount.get())
            .setActionCount(actionCount.intValue())
            .setActionCountNotIncludingAspects(actionCountNotIncludingAspects.intValue())
            .setInputFileConfiguredTargetCount(inputFileConfiguredTargetCount.get())
            .setOutputFileConfiguredTargetCount(outputFileConfiguredTargetCount.get())
            .setOtherConfiguredTargetCount(otherConfiguredTargetCount.get())
}
