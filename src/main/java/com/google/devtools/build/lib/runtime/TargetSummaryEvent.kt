// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil.configurationId

/** Event summarizing the building and testing (if applicable) of a given configured target.  */
@Immutable
class TargetSummaryEvent private constructor(
    id: BuildEventId,
    overallBuildSuccess: Boolean,
    overallTestStatus: BlazeTestStatus?,
    postedAfter: com.google.common.collect.ImmutableList<BuildEventId?>?
) : BuildEventWithOrderConstraint {
    private val id: BuildEventId

    @get:com.google.common.annotations.VisibleForTesting
    val isOverallBuildSuccess: Boolean
    private val overallTestStatus: BlazeTestStatus?
    private val postedAfter: com.google.common.collect.ImmutableList<BuildEventId?>?

    init {
        checkArgument(id.hasTargetSummary(), "Unexpected event id: %s", id)
        this.id = id
        this.isOverallBuildSuccess = overallBuildSuccess
        this.overallTestStatus = overallTestStatus
        this.postedAfter = postedAfter
    }

    @com.google.common.annotations.VisibleForTesting
    fun getOverallTestStatus(): BlazeTestStatus? {
        return overallTestStatus
    }

    public override fun postedAfter(): com.google.common.collect.ImmutableList<BuildEventId?>? {
        return postedAfter
    }

    public override fun asStreamProto(context: BuildEventContext?): BuildEvent {
        val summaryBuilder: BuildEventStreamProtos.TargetSummary.Builder =
            BuildEventStreamProtos.TargetSummary.newBuilder()
                .setOverallBuildSuccess(this.isOverallBuildSuccess)
        if (this.isOverallBuildSuccess && overallTestStatus != null) {
            summaryBuilder.setOverallTestStatus(BuildEventStreamerUtils.bepStatus(overallTestStatus))
        }
        return GenericBuildEvent.protoChaining(this).setTargetSummary(summaryBuilder.build()).build()
    }

    val eventId: BuildEventId
        get() = id

    val childrenEvents: com.google.common.collect.ImmutableList<BuildEventId?>
        get() = com.google.common.collect.ImmutableList.of<BuildEventId?>()

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("id", id)
            .add("overallBuildSuccess", this.isOverallBuildSuccess)
            .add("overallTestStatus", overallTestStatus)
            .add("postedAfter", postedAfter)
            .toString()
    }

    companion object {
        fun create(
            target: ConfiguredTarget,
            overallBuildSuccess: Boolean,
            expectTestSummary: Boolean,
            overallTestStatus: BlazeTestStatus?
        ): TargetSummaryEvent {
            val label: Label? = target.getOriginalLabel()
            val configId: BuildEventId? = configurationId(target.getLookupKey().getConfigurationKey())
            val postAfter: com.google.common.collect.ImmutableList.Builder<BuildEventId?> =
                com.google.common.collect.ImmutableList.builder<BuildEventId?>()
            postAfter.add(BuildEventIdUtil.targetCompleted(label, configId))
            if (expectTestSummary) {
                // Always post after test summary, even if we get here without having seen it yet
                postAfter.add(BuildEventIdUtil.testSummary(label, configId))
            }
            return TargetSummaryEvent(
                BuildEventIdUtil.targetSummary(label, configId),
                overallBuildSuccess,
                if (overallBuildSuccess && expectTestSummary) overallTestStatus else null,
                postAfter.build()
            )
        }
    }
}
