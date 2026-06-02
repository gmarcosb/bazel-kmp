// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildeventservice.client

import com.google.devtools.build.v1.BuildEvent.BuildComponentStreamFinished.FinishType.FINISHED

/** Utility methods to create BES proto messages.  */
object BuildEventServiceProtoUtil {
    private val TYPE_URL = "type.googleapis.com/" + BuildEventStreamProtos.BuildEvent.getDescriptor().getFullName()

    /** Creates a [PublishLifecycleEventRequest] from a [LifecycleEvent].  */
    fun publishLifecycleEventRequest(
        commandContext: CommandContext, lifecycleEvent: LifecycleEvent
    ): PublishLifecycleEventRequest? {
        return when (lifecycleEvent) {
            -> buildEnqueued(commandContext, eventTime)
            -> invocationStarted(commandContext, eventTime)
            -> invocationFinished(commandContext, eventTime, status)
            -> buildFinished(commandContext, eventTime, status)
        }
    }

    fun buildEnqueued(
        commandContext: CommandContext, instant: Instant
    ): PublishLifecycleEventRequest {
        return lifecycleRequest(
            commandContext,
            1,
            BuildEvent.newBuilder()
                .setEventTime(toProtoTimestamp(instant))
                .setBuildEnqueued(BuildEnqueued.getDefaultInstance())
        )
            .build()
    }

    fun buildFinished(
        commandContext: CommandContext, eventTime: Instant, status: InvocationStatus
    ): PublishLifecycleEventRequest {
        return lifecycleRequest(
            commandContext,
            2,
            BuildEvent.newBuilder()
                .setEventTime(toProtoTimestamp(eventTime))
                .setBuildFinished(BuildFinished.newBuilder().setStatus(buildStatus(status)))
        )
            .build()
    }

    fun invocationStarted(
        commandContext: CommandContext, instant: Instant
    ): PublishLifecycleEventRequest {
        return lifecycleRequest(
            commandContext,
            1,
            BuildEvent.newBuilder()
                .setEventTime(toProtoTimestamp(instant))
                .setInvocationAttemptStarted(
                    InvocationAttemptStarted.newBuilder()
                        .setAttemptNumber(commandContext.attemptNumber)
                )
        )
            .build()
    }

    fun invocationFinished(
        commandContext: CommandContext, eventTime: Instant, status: InvocationStatus
    ): PublishLifecycleEventRequest {
        return lifecycleRequest(
            commandContext,
            2,
            BuildEvent.newBuilder()
                .setEventTime(toProtoTimestamp(eventTime))
                .setInvocationAttemptFinished(
                    InvocationAttemptFinished.newBuilder()
                        .setInvocationStatus(buildStatus(status))
                )
        )
            .build()
    }

    private fun buildStatus(status: InvocationStatus): BuildStatus? {
        return when (status) {
            InvocationStatus.UNKNOWN -> BuildStatus.newBuilder().setResult(Result.UNKNOWN_STATUS).build()
            InvocationStatus.SUCCEEDED -> BuildStatus.newBuilder().setResult(Result.COMMAND_SUCCEEDED).build()
            InvocationStatus.FAILED -> BuildStatus.newBuilder().setResult(Result.COMMAND_FAILED).build()
        }
    }

    /** Creates a [PublishBuildToolEventStreamRequest] from a [StreamEvent].  */
    fun publishBuildToolEventStreamRequest(
        commandContext: CommandContext, streamEvent: StreamEvent
    ): PublishBuildToolEventStreamRequest? {
        return when (streamEvent) {
            -> bazelEvent(commandContext, eventTime, sequenceNumber, payload)
            -> streamFinished(commandContext, eventTime, sequenceNumber)
        }
    }

    fun bazelEvent(
        commandContext: CommandContext, eventTime: Instant, sequenceNumber: Long, payload: ByteArray
    ): PublishBuildToolEventStreamRequest {
        // Any.pack() would require us to parse the payload into a Message, which is wasteful.
        // Implement it manually instead.
        val packed: Any? =
            Any.newBuilder().setTypeUrl(TYPE_URL).setValue(ByteString.copyFrom(payload)).build()
        return streamRequest(
            commandContext,
            sequenceNumber,
            toProtoTimestamp(eventTime),
            BuildEvent.newBuilder().setBazelEvent(packed)
        )
    }

    fun streamFinished(
        commandContext: CommandContext, eventTime: Instant, sequenceNumber: Long
    ): PublishBuildToolEventStreamRequest {
        return streamRequest(
            commandContext,
            sequenceNumber,
            toProtoTimestamp(eventTime),
            BuildEvent.newBuilder()
                .setComponentStreamFinished(
                    BuildComponentStreamFinished.newBuilder().setType(FINISHED)
                )
        )
    }

    @com.google.common.annotations.VisibleForTesting
    fun streamRequest(
        commandContext: CommandContext,
        sequenceNumber: Long,
        timestamp: Timestamp?,
        besEvent: BuildEvent.Builder
    ): PublishBuildToolEventStreamRequest {
        val builder: PublishBuildToolEventStreamRequest.Builder =
            PublishBuildToolEventStreamRequest.newBuilder()
                .setOrderedBuildEvent(
                    OrderedBuildEvent.newBuilder()
                        .setSequenceNumber(sequenceNumber)
                        .setEvent(besEvent.setEventTime(timestamp))
                        .setStreamId(streamId(commandContext, besEvent.getEventCase()))
                )
        if (sequenceNumber == 1L) {
            builder
                .addAllNotificationKeywords(commandContext.keywords)
                .setCheckPrecedingLifecycleEventsPresent(commandContext.checkPrecedingLifecycleEvents)
        }
        if (commandContext.projectId != null) {
            builder.setProjectId(commandContext.projectId)
        }
        return builder.build()
    }

    @com.google.common.annotations.VisibleForTesting
    fun lifecycleRequest(
        commandContext: CommandContext, sequenceNumber: Int, lifecycleEvent: BuildEvent.Builder
    ): PublishLifecycleEventRequest.Builder {
        val builder: PublishLifecycleEventRequest.Builder =
            PublishLifecycleEventRequest.newBuilder()
                .setServiceLevel(PublishLifecycleEventRequest.ServiceLevel.INTERACTIVE)
                .setBuildEvent(
                    OrderedBuildEvent.newBuilder()
                        .setSequenceNumber(sequenceNumber)
                        .setStreamId(streamId(commandContext, lifecycleEvent.getEventCase()))
                        .setEvent(lifecycleEvent)
                )
        if (commandContext.projectId != null) {
            builder.setProjectId(commandContext.projectId)
        }
        when (lifecycleEvent.getEventCase()) {
            BUILD_ENQUEUED, INVOCATION_ATTEMPT_STARTED, BUILD_FINISHED -> builder.addAllNotificationKeywords(
                commandContext.keywords
            )

            else -> {}
        }
        return builder
    }

    @com.google.common.annotations.VisibleForTesting
    fun streamId(commandContext: CommandContext, eventCase: EventCase): StreamId {
        val streamId: StreamId.Builder = StreamId.newBuilder().setBuildId(commandContext.buildId)
        when (eventCase) {
            BUILD_ENQUEUED, BUILD_FINISHED -> streamId.setComponent(BuildComponent.CONTROLLER)
            INVOCATION_ATTEMPT_STARTED, INVOCATION_ATTEMPT_FINISHED -> {
                streamId
                    .setInvocationId(commandContext.invocationId)
                    .setComponent(BuildComponent.CONTROLLER)
            }

            BAZEL_EVENT, COMPONENT_STREAM_FINISHED -> {
                streamId.setInvocationId(commandContext.invocationId).setComponent(BuildComponent.TOOL)
            }

            else -> throw java.lang.IllegalArgumentException("Illegal EventCase " + eventCase)
        }
        return streamId.build()
    }

    private fun toProtoTimestamp(instant: Instant): Timestamp {
        return Timestamp.newBuilder()
            .setSeconds(instant.getEpochSecond())
            .setNanos(instant.getNano())
            .build()
    }
}
