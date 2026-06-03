// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.buildeventservice

import com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient.CommandContext

/** Tests [BuildEventServiceProtoUtil]. *  */
@RunWith(JUnit4::class)
class BuildEventServiceProtoUtilTest {
    private val clock: com.google.devtools.build.lib.testutil.ManualClock =
        com.google.devtools.build.lib.testutil.ManualClock()

    @org.junit.Test
    fun testBuildEnqueued() {
        clock.advanceMillis(100)
        val expected: Instant? = clock.now()
        assertThat(BuildEventServiceProtoUtil.buildEnqueued(COMMAND_CONTEXT, expected))
            .isEqualTo(
                PublishLifecycleEventRequest.newBuilder()
                    .setServiceLevel(ServiceLevel.INTERACTIVE)
                    .setProjectId(PROJECT_ID)
                    .addAllNotificationKeywords(KEYWORDS)
                    .setBuildEvent(
                        OrderedBuildEvent.newBuilder()
                            .setStreamId(
                                StreamId.newBuilder()
                                    .setBuildId(BUILD_REQUEST_ID)
                                    .setComponent(BuildComponent.CONTROLLER)
                            )
                            .setSequenceNumber(1)
                            .setEvent(
                                BuildEvent.newBuilder()
                                    .setEventTime(toProtoTimestamp(expected))
                                    .setBuildEnqueued(BuildEnqueued.getDefaultInstance())
                            )
                    )
                    .build()
            )
    }

    @org.junit.Test
    fun testInvocationAttemptStarted() {
        clock.advanceMillis(100)
        val expected: Instant? = clock.now()
        assertThat(BuildEventServiceProtoUtil.invocationStarted(COMMAND_CONTEXT, expected))
            .isEqualTo(
                PublishLifecycleEventRequest.newBuilder()
                    .setServiceLevel(ServiceLevel.INTERACTIVE)
                    .setProjectId(PROJECT_ID)
                    .addAllNotificationKeywords(KEYWORDS)
                    .setBuildEvent(
                        OrderedBuildEvent.newBuilder()
                            .setStreamId(
                                StreamId.newBuilder()
                                    .setBuildId(BUILD_REQUEST_ID)
                                    .setInvocationId(BUILD_INVOCATION_ID)
                                    .setComponent(BuildComponent.CONTROLLER)
                            )
                            .setSequenceNumber(1)
                            .setEvent(
                                BuildEvent.newBuilder()
                                    .setEventTime(toProtoTimestamp(expected))
                                    .setInvocationAttemptStarted(
                                        InvocationAttemptStarted.newBuilder().setAttemptNumber(1)
                                    )
                            )
                    )
                    .build()
            )
    }

    @org.junit.Test
    fun invocationAttemptStarted_attemptNumber() {
        val commandContext: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CommandContext.builder()
                .setBuildId(BUILD_REQUEST_ID)
                .setInvocationId(BUILD_INVOCATION_ID)
                .setAttemptNumber(2)
                .setKeywords(KEYWORDS)
                .setProjectId(PROJECT_ID)
                .setCheckPrecedingLifecycleEvents(false)
                .build()
        clock.advanceMillis(100)
        val expected: Instant? = clock.now()
        assertThat(BuildEventServiceProtoUtil.invocationStarted(commandContext, expected))
            .isEqualTo(
                PublishLifecycleEventRequest.newBuilder()
                    .setServiceLevel(ServiceLevel.INTERACTIVE)
                    .setProjectId(PROJECT_ID)
                    .addAllNotificationKeywords(KEYWORDS)
                    .setBuildEvent(
                        OrderedBuildEvent.newBuilder()
                            .setStreamId(
                                StreamId.newBuilder()
                                    .setBuildId(BUILD_REQUEST_ID)
                                    .setInvocationId(BUILD_INVOCATION_ID)
                                    .setComponent(BuildComponent.CONTROLLER)
                            )
                            .setSequenceNumber(1)
                            .setEvent(
                                BuildEvent.newBuilder()
                                    .setEventTime(toProtoTimestamp(expected))
                                    .setInvocationAttemptStarted(
                                        InvocationAttemptStarted.newBuilder().setAttemptNumber(2)
                                    )
                            )
                    )
                    .build()
            )
    }

    @org.junit.Test
    fun testInvocationAttemptFinished() {
        clock.advanceMillis(100)
        val expected: Instant? = clock.now()
        assertThat(
            BuildEventServiceProtoUtil.invocationFinished(
                COMMAND_CONTEXT, expected, InvocationStatus.SUCCEEDED
            )
        )
            .isEqualTo(
                PublishLifecycleEventRequest.newBuilder()
                    .setServiceLevel(ServiceLevel.INTERACTIVE)
                    .setProjectId(PROJECT_ID)
                    .setBuildEvent(
                        OrderedBuildEvent.newBuilder()
                            .setStreamId(
                                StreamId.newBuilder()
                                    .setBuildId(BUILD_REQUEST_ID)
                                    .setInvocationId(BUILD_INVOCATION_ID)
                                    .setComponent(BuildComponent.CONTROLLER)
                            )
                            .setSequenceNumber(2)
                            .setEvent(
                                BuildEvent.newBuilder()
                                    .setEventTime(toProtoTimestamp(expected))
                                    .setInvocationAttemptFinished(
                                        InvocationAttemptFinished.newBuilder()
                                            .setInvocationStatus(
                                                BuildStatus.newBuilder()
                                                    .setResult(Result.COMMAND_SUCCEEDED)
                                            )
                                    )
                            )
                    )
                    .build()
            )
    }

    @org.junit.Test
    fun testBuildFinished() {
        clock.advanceMillis(100)
        val expected: Instant? = clock.now()
        assertThat(
            BuildEventServiceProtoUtil.buildFinished(
                COMMAND_CONTEXT, expected, InvocationStatus.SUCCEEDED
            )
        )
            .isEqualTo(
                PublishLifecycleEventRequest.newBuilder()
                    .setServiceLevel(ServiceLevel.INTERACTIVE)
                    .setProjectId(PROJECT_ID)
                    .addAllNotificationKeywords(KEYWORDS)
                    .setBuildEvent(
                        OrderedBuildEvent.newBuilder()
                            .setStreamId(
                                StreamId.newBuilder()
                                    .setBuildId(BUILD_REQUEST_ID)
                                    .setComponent(BuildComponent.CONTROLLER)
                            )
                            .setSequenceNumber(2)
                            .setEvent(
                                BuildEvent.newBuilder()
                                    .setEventTime(toProtoTimestamp(expected))
                                    .setBuildFinished(
                                        BuildFinished.newBuilder()
                                            .setStatus(
                                                BuildStatus.newBuilder()
                                                    .setResult(Result.COMMAND_SUCCEEDED)
                                            )
                                    )
                            )
                    )
                    .build()
            )
    }

    @org.junit.Test
    fun testStreamEvents() {
        clock.advanceMillis(100)
        val firstEventTimestamp: Instant? = clock.now()
        val payload: ByteString = ByteString.fromHex("deadbeef")
        assertThat(
            BuildEventServiceProtoUtil.bazelEvent(
                COMMAND_CONTEXT, firstEventTimestamp, 1, payload.toByteArray()
            )
        )
            .isEqualTo(
                PublishBuildToolEventStreamRequest.newBuilder()
                    .addAllNotificationKeywords(KEYWORDS)
                    .setProjectId(PROJECT_ID)
                    .setOrderedBuildEvent(
                        OrderedBuildEvent.newBuilder()
                            .setStreamId(
                                StreamId.newBuilder()
                                    .setBuildId(BUILD_REQUEST_ID)
                                    .setInvocationId(BUILD_INVOCATION_ID)
                                    .setComponent(BuildComponent.TOOL)
                            )
                            .setSequenceNumber(1)
                            .setEvent(
                                BuildEvent.newBuilder()
                                    .setEventTime(toProtoTimestamp(firstEventTimestamp))
                                    .setBazelEvent(
                                        Any.newBuilder()
                                            .setTypeUrl(
                                                "type.googleapis.com/build_event_stream.BuildEvent"
                                            )
                                            .setValue(payload)
                                    )
                            )
                            .build()
                    )
                    .build()
            )

        clock.advanceMillis(100)
        val secondEventTimestamp: Instant? = clock.now()
        assertThat(
            BuildEventServiceProtoUtil.bazelEvent(
                COMMAND_CONTEXT, secondEventTimestamp, 2, payload.toByteArray()
            )
        )
            .isEqualTo(
                PublishBuildToolEventStreamRequest.newBuilder()
                    .setProjectId(PROJECT_ID)
                    .setOrderedBuildEvent(
                        OrderedBuildEvent.newBuilder()
                            .setStreamId(
                                StreamId.newBuilder()
                                    .setBuildId(BUILD_REQUEST_ID)
                                    .setInvocationId(BUILD_INVOCATION_ID)
                                    .setComponent(BuildComponent.TOOL)
                            )
                            .setSequenceNumber(2)
                            .setEvent(
                                BuildEvent.newBuilder()
                                    .setEventTime(toProtoTimestamp(secondEventTimestamp))
                                    .setBazelEvent(
                                        Any.newBuilder()
                                            .setTypeUrl(
                                                "type.googleapis.com/build_event_stream.BuildEvent"
                                            )
                                            .setValue(payload)
                                    )
                            )
                            .build()
                    )
                    .build()
            )

        clock.advanceMillis(100)
        val thirdEventTimestamp: Instant? = clock.now()
        assertThat(BuildEventServiceProtoUtil.streamFinished(COMMAND_CONTEXT, thirdEventTimestamp, 3))
            .isEqualTo(
                PublishBuildToolEventStreamRequest.newBuilder()
                    .setProjectId(PROJECT_ID)
                    .setOrderedBuildEvent(
                        OrderedBuildEvent.newBuilder()
                            .setStreamId(
                                StreamId.newBuilder()
                                    .setBuildId(BUILD_REQUEST_ID)
                                    .setInvocationId(BUILD_INVOCATION_ID)
                                    .setComponent(BuildComponent.TOOL)
                            )
                            .setSequenceNumber(3)
                            .setEvent(
                                BuildEvent.newBuilder()
                                    .setEventTime(toProtoTimestamp(thirdEventTimestamp))
                                    .setComponentStreamFinished(
                                        BuildComponentStreamFinished.newBuilder()
                                            .setType(FinishType.FINISHED)
                                    )
                            )
                            .build()
                    )
                    .build()
            )
    }

    @org.junit.Test
    fun testStreamEventsWithCheckPrecedingLifecycleEventsEnabled() {
        val payload: ByteArray? = ByteString.fromHex("deadbeef").toByteArray()
        val commandContext: CommandContext? =
            CommandContext.builder()
                .setBuildId(BUILD_REQUEST_ID)
                .setInvocationId(BUILD_INVOCATION_ID)
                .setAttemptNumber(ATTEMPT_NUMBER)
                .setKeywords(KEYWORDS)
                .setProjectId(PROJECT_ID)
                .setCheckPrecedingLifecycleEvents(true)
                .build()
        assertThat(
            BuildEventServiceProtoUtil.bazelEvent(
                commandContext, Instant.ofEpochMilli(100), 1, payload
            )
                .getCheckPrecedingLifecycleEventsPresent()
        )
            .isTrue()
        // check_preceding_lifecycle_events_present is always false for events with sequence_number > 1.
        assertThat(
            BuildEventServiceProtoUtil.bazelEvent(
                commandContext, Instant.ofEpochMilli(100), 2, payload
            )
                .getCheckPrecedingLifecycleEventsPresent()
        )
            .isFalse()
        assertThat(
            BuildEventServiceProtoUtil.bazelEvent(
                commandContext, Instant.ofEpochMilli(100), 3, payload
            )
                .getCheckPrecedingLifecycleEventsPresent()
        )
            .isFalse()
    }

    companion object {
        private const val BUILD_REQUEST_ID = "feedbeef-dead-4321-beef-deaddeaddead"
        private const val BUILD_INVOCATION_ID = "feedbeef-dead-4444-beef-deaddeaddead"
        private const val ATTEMPT_NUMBER = 1
        private const val PROJECT_ID = "my_project"
        private val KEYWORDS: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>("foo=bar", "spam=eggs")
        private val COMMAND_CONTEXT: CommandContext? = CommandContext.builder()
            .setBuildId(BUILD_REQUEST_ID)
            .setInvocationId(BUILD_INVOCATION_ID)
            .setAttemptNumber(ATTEMPT_NUMBER)
            .setKeywords(KEYWORDS)
            .setProjectId(PROJECT_ID)
            .setCheckPrecedingLifecycleEvents(false)
            .build()

        private fun toProtoTimestamp(instant: Instant): Timestamp {
            return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build()
        }
    }
}
