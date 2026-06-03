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

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** Unit tests for [TargetSummaryPublisher].  */
@RunWith(JUnit4::class)
class TargetSummaryEventTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetEventId() {
        val event: TargetSummaryEvent =
            TargetSummaryEvent.create(target(PATH, TARGET_NAME, CONFIGURATION_KEY), false, false, null)
        assertThat(event.getEventId())
            .isEqualTo(
                BuildEventIdUtil.targetSummary(
                    Label.create(PATH, TARGET_NAME), BuildEventIdUtil.configurationId(CHECKSUM)
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetEventId_nullConfig() {
        val event: TargetSummaryEvent =
            TargetSummaryEvent.create(target(PATH, TARGET_NAME, null), false, false, null)
        assertThat(event.getEventId())
            .isEqualTo(
                BuildEventIdUtil.targetSummary(
                    Label.create(PATH, TARGET_NAME), BuildEventIdUtil.nullConfigurationId()
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPostedAfter_noTestSummary() {
        val event: TargetSummaryEvent = TargetSummaryEvent.create(stubTarget(), false, false, null)
        assertThat(event.postedAfter())
            .containsExactly(
                BuildEventIdUtil.targetCompleted(
                    Label.create(PATH, TARGET_NAME), BuildEventIdUtil.configurationId(CHECKSUM)
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPostedAfter_expectTestSummary() {
        val event: TargetSummaryEvent = TargetSummaryEvent.create(stubTarget(), false, true, null)
        assertThat(event.postedAfter())
            .containsExactly(
                BuildEventIdUtil.targetCompleted(
                    Label.create(PATH, TARGET_NAME), BuildEventIdUtil.configurationId(CHECKSUM)
                ),
                BuildEventIdUtil.testSummary(
                    Label.create(PATH, TARGET_NAME), BuildEventIdUtil.configurationId(CHECKSUM)
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPostedAfter_nullConfig() {
        val event: TargetSummaryEvent =
            TargetSummaryEvent.create(target(PATH, TARGET_NAME, null), false, true, null)
        assertThat(event.postedAfter())
            .containsExactly(
                BuildEventIdUtil.targetCompleted(
                    Label.create(PATH, TARGET_NAME), BuildEventIdUtil.nullConfigurationId()
                ),
                BuildEventIdUtil.testSummary(
                    Label.create(PATH, TARGET_NAME), BuildEventIdUtil.nullConfigurationId()
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAsStreamProto_forTest() {
        val event: TargetSummaryEvent =
            TargetSummaryEvent.create(stubTarget(), true, true, BlazeTestStatus.FLAKY)
        val proto: BuildEvent = event.asStreamProto(null)
        assertThat(proto.getId()).isEqualTo(event.getEventId())
        assertThat(proto.getTargetSummary().getOverallBuildSuccess()).isTrue()
        assertThat(proto.getTargetSummary().getOverallTestStatus()).isEqualTo(TestStatus.FLAKY)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAsStreamProto_forBuildSuccess() {
        val event: TargetSummaryEvent = TargetSummaryEvent.create(stubTarget(), true, false, null)
        val proto: BuildEvent = event.asStreamProto(null)
        assertThat(proto.getId()).isEqualTo(event.getEventId())
        assertThat(proto.getTargetSummary().getOverallBuildSuccess()).isTrue()
        assertThat(proto.getTargetSummary().getOverallTestStatus()).isEqualTo(TestStatus.NO_STATUS)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAsStreamProto_failedBuildIgnoresTestResult() {
        val event: TargetSummaryEvent =
            TargetSummaryEvent.create(stubTarget(), false, true, BlazeTestStatus.PASSED)
        val proto: BuildEvent = event.asStreamProto(null)
        assertThat(proto.getId()).isEqualTo(event.getEventId())
        assertThat(proto.getTargetSummary().getOverallBuildSuccess()).isFalse()
        assertThat(proto.getTargetSummary().getOverallTestStatus()).isEqualTo(TestStatus.NO_STATUS)
    }

    companion object {
        private const val PATH = "package"
        private const val TARGET_NAME = "name"

        private val CONFIGURATION_KEY: BuildConfigurationKey =
            BuildConfigurationKey.create(BuildOptions.builder().build())

        private val CHECKSUM: String? = CONFIGURATION_KEY.getOptions().checksum()

        @Throws(java.lang.Exception::class)
        private fun stubTarget(): ConfiguredTarget {
            return target(PATH, TARGET_NAME, CONFIGURATION_KEY)
        }

        @Throws(java.lang.Exception::class)
        private fun target(
            path: String?, targetName: String?, configurationKey: BuildConfigurationKey?
        ): ConfiguredTarget {
            val target: ConfiguredTarget = Mockito.mock<ConfiguredTarget>(ConfiguredTarget::class.java)
            Mockito.`when`<T?>(target.getOriginalLabel()).thenReturn(Label.create(path, targetName))
            Mockito.`when`<T?>(target.getConfigurationChecksum())
                .thenReturn(if (configurationKey == null) null else configurationKey.getOptions().checksum())
            val key: ConfiguredTargetKey? =
                ConfiguredTargetKey.builder()
                    .setLabel(Label.create(path, targetName))
                    .setConfigurationKey(configurationKey)
                    .build()
            Mockito.`when`<T?>(target.getLookupKey()).thenReturn(key)
            return target
        }
    }
}
