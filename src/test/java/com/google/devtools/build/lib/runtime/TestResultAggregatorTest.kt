// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact

/** Tests for [TestResultAggregator].  */
@RunWith(JUnit4::class)
class TestResultAggregatorTest {
    private val mockParams: TestParams = Mockito.mock<TestParams>(TestParams::class.java)

    @Before
    fun configureMockParams() {
        Mockito.`when`<T?>(mockParams.runsDetectsFlakes()).thenReturn(false)
        Mockito.`when`<T?>(mockParams.getTimeout()).thenReturn(TestTimeout.LONG)
        Mockito.`when`<T?>(mockParams.getShards()).thenReturn(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonCachedResult_setsActionRanTrue() {
        val underTest: TestResultAggregator = createAggregatorWithTestRuns(1)

        underTest.testEvent(
            testResult(TestResultData.newBuilder().setRemotelyCached(false),  /*locallyCached=*/false)
        )

        assertThat(underTest.aggregateAndReportSummary(false).actionRan()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun locallyCachedTest_setsActionRanFalse() {
        val underTest: TestResultAggregator = createAggregatorWithTestRuns(1)

        underTest.testEvent(
            testResult(TestResultData.newBuilder().setRemotelyCached(false),  /*locallyCached=*/true)
        )

        assertThat(underTest.aggregateAndReportSummary(false).actionRan()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remotelyCachedTest_setsActionRanFalse() {
        val underTest: TestResultAggregator = createAggregatorWithTestRuns(1)

        underTest.testEvent(
            testResult(TestResultData.newBuilder().setRemotelyCached(true),  /*locallyCached=*/false)
        )

        assertThat(underTest.aggregateAndReportSummary(false).actionRan()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun newCachedResult_keepsActionRanTrueWhenAlreadyTrue() {
        val underTest: TestResultAggregator = createAggregatorWithTestRuns(2)

        underTest.testEvent(
            testResult(TestResultData.newBuilder().setRemotelyCached(false),  /*locallyCached=*/false)
        )
        underTest.testEvent(
            testResult(TestResultData.newBuilder().setRemotelyCached(true),  /*locallyCached=*/true)
        )

        assertThat(underTest.aggregateAndReportSummary(false).actionRan()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun timingAggregation() {
        val underTest: TestResultAggregator = createAggregatorWithTestRuns(2)

        underTest.testEvent(
            testResult(
                TestResultData.newBuilder().setStartTimeMillisEpoch(7).setRunDurationMillis(10),  /*locallyCached=*/
                true
            )
        )
        underTest.testEvent(
            testResult(
                TestResultData.newBuilder().setStartTimeMillisEpoch(12).setRunDurationMillis(1),  /*locallyCached=*/
                true
            )
        )

        val summary: TestSummary = underTest.aggregateAndReportSummary(false)
        assertThat(summary.getFirstStartTimeMillis()).isEqualTo(7)
        assertThat(summary.getLastStopTimeMillis()).isEqualTo(17)
        assertThat(summary.getTotalRunDurationMillis()).isEqualTo(11)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun attemptCount_aggregatesSingleShardSingleAttempt() {
        Mockito.`when`<T?>(mockParams.runsDetectsFlakes()).thenReturn(true)
        val underTest: TestResultAggregator = createAggregatorWithTestRuns(1)

        underTest.testEvent(
            shardedTestResult(
                TestResultData.newBuilder()
                    .addAllTestTimes(com.google.common.collect.ImmutableList.of<E?>(1L, 2L)),  /*shardNum=*/
                0
            )
        )

        assertThat(underTest.aggregateAndReportSummary(false).getNumAttempts()).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun attemptCount_aggregatesSingleShardMultipleAttempts() {
        Mockito.`when`<T?>(mockParams.runsDetectsFlakes()).thenReturn(true)
        val underTest: TestResultAggregator = createAggregatorWithTestRuns(2)

        underTest.testEvent(
            shardedTestResult(
                TestResultData.newBuilder()
                    .addAllTestTimes(com.google.common.collect.ImmutableList.of<E?>(1L, 2L)),  /*shardNum=*/
                0
            )
        )
        underTest.testEvent(
            shardedTestResult(
                TestResultData.newBuilder()
                    .addAllTestTimes(com.google.common.collect.ImmutableList.of<E?>(3L, 4L)),  /*shardNum=*/
                0
            )
        )

        assertThat(underTest.aggregateAndReportSummary(false).getNumAttempts()).isEqualTo(4)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun attemptCount_aggregatesMultipleShardsMultipleAttempts() {
        Mockito.`when`<T?>(mockParams.runsDetectsFlakes()).thenReturn(true)
        Mockito.`when`<T?>(mockParams.getShards()).thenReturn(2)
        val underTest: TestResultAggregator = createAggregatorWithTestRuns(3)

        underTest.testEvent(
            shardedTestResult(
                TestResultData.newBuilder()
                    .addAllTestTimes(com.google.common.collect.ImmutableList.of<E?>(1L, 2L, 3L)),  /*shardNum=*/
                0
            )
        )
        underTest.testEvent(
            shardedTestResult(
                TestResultData.newBuilder()
                    .addAllTestTimes(com.google.common.collect.ImmutableList.of<E?>(3L, 4L)),  /*shardNum=*/
                1
            )
        )
        underTest.testEvent(
            shardedTestResult(
                TestResultData.newBuilder()
                    .addAllTestTimes(com.google.common.collect.ImmutableList.of<E?>(3L, 4L)),  /*shardNum=*/
                1
            )
        )

        assertThat(underTest.aggregateAndReportSummary(false).getNumAttempts()).isEqualTo(4)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun attemptCount_aggregatesMultipleShardsSingleShardHasMostAttempts() {
        Mockito.`when`<T?>(mockParams.runsDetectsFlakes()).thenReturn(true)
        Mockito.`when`<T?>(mockParams.getShards()).thenReturn(2)
        val underTest: TestResultAggregator = createAggregatorWithTestRuns(3)

        underTest.testEvent(
            shardedTestResult(
                TestResultData.newBuilder()
                    .addAllTestTimes(com.google.common.collect.ImmutableList.of<E?>(1L, 2L, 3L, 4L, 5L)),  /*shardNum=*/
                0
            )
        )
        underTest.testEvent(
            shardedTestResult(
                TestResultData.newBuilder()
                    .addAllTestTimes(com.google.common.collect.ImmutableList.of<E?>(3L, 4L)),  /*shardNum=*/
                1
            )
        )
        underTest.testEvent(
            shardedTestResult(
                TestResultData.newBuilder()
                    .addAllTestTimes(com.google.common.collect.ImmutableList.of<E?>(3L, 4L)),  /*shardNum=*/
                1
            )
        )

        assertThat(underTest.aggregateAndReportSummary(false).getNumAttempts()).isEqualTo(5)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cancelConcurrentTests_cancellationAfterPassIgnored() {
        Mockito.`when`<T?>(mockParams.runsDetectsFlakes()).thenReturn(true)
        val underTest: TestResultAggregator = createAggregatorWithTestRuns(2)

        underTest.testEvent(
            testResult(
                TestResultData.newBuilder().setStatus(BlazeTestStatus.PASSED),  /* locallyCached= */
                true
            )
        )
        underTest.testEvent(
            testResult(
                TestResultData.newBuilder().setStatus(BlazeTestStatus.INCOMPLETE),  /*locallyCached=*/
                true
            )
        )

        assertThat(underTest.aggregateAndReportSummary(false).getStatus())
            .isEqualTo(BlazeTestStatus.PASSED)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun notAllTestRunsReported_skipTargetsOnFailure_noStatus() {
        val underTest: TestResultAggregator = createAggregatorWithTestRuns(2)

        underTest.testEvent(
            testResult(
                TestResultData.newBuilder().setStatus(BlazeTestStatus.PASSED),  /*locallyCached=*/
                false
            )
        )

        assertThat(underTest.aggregateAndReportSummary( /*skipTargetsOnFailure=*/true).getStatus())
            .isEqualTo(BlazeTestStatus.NO_STATUS)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun notAllTestRunsReported_noSkipTargetsOnFailure_incomplete() {
        val underTest: TestResultAggregator = createAggregatorWithTestRuns(2)

        underTest.testEvent(
            testResult(
                TestResultData.newBuilder().setStatus(BlazeTestStatus.PASSED),  /*locallyCached=*/
                false
            )
        )

        assertThat(underTest.aggregateAndReportSummary( /*skipTargetsOnFailure=*/false).getStatus())
            .isEqualTo(BlazeTestStatus.INCOMPLETE)
    }

    private fun createAggregatorWithTestRuns(testRuns: Int): TestResultAggregator {
        val root: ArtifactRoot? =
            ArtifactRoot.asDerivedRoot(
                InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/output_base"),
                RootType.OUTPUT,
                "execroot"
            )
        Mockito.`when`<T?>(mockParams.getTestStatusArtifacts())
            .thenReturn(
                IntStream.range(0, testRuns)
                    .mapToObj<Any?>(
                        java.util.function.IntFunction { i: Int ->
                            ActionsTestUtil.createArtifact(
                                root,
                                "status." + i
                            ) as DerivedArtifact?
                        })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            )
        Mockito.`when`<T?>(mockParams.getRuns()).thenReturn(testRuns)

        val mockTarget: ConfiguredTarget = Mockito.mock<ConfiguredTarget>(ConfiguredTarget::class.java)
        Mockito.`when`<T?>(mockTarget.getProvider(TestProvider::class.java)).thenReturn(TestProvider(mockParams))

        return TestResultAggregator(
            mockTarget,
            < T > mock < T ? > (BuildConfigurationValue::class.java),
        AggregationPolicy(
            com.google.common.eventbus.EventBus(),  /* testCheckUpToDate= */
            false,  /* testVerboseTimeoutWarnings= */
            false
        ),  /* skippedThisTest= */
        false)
    }

    companion object {
        @Throws(IOException::class)
        private fun testResult(data: TestResultData.Builder, locallyCached: Boolean): TestResult {
            val mockTestAction: TestRunnerAction = Mockito.mock<TestRunnerAction>(TestRunnerAction::class.java)
            Mockito.`when`<T?>(
                mockTestAction.getTestOutputsMapping(
                    ArgumentMatchers.any<T?>(),
                    ArgumentMatchers.any<T?>()
                )
            ).thenReturn(com.google.common.collect.ImmutableMultimap.of<K?, V?>())
            return TestResult(
                mockTestAction,
                data.build(),
                com.google.common.collect.ImmutableMultimap.of<K?, V?>(),
                locallyCached,  /* systemFailure= */
                null
            )
        }

        @Throws(IOException::class)
        private fun shardedTestResult(data: TestResultData.Builder, shardNum: Int): TestResult {
            val mockTestAction: TestRunnerAction = Mockito.mock<TestRunnerAction>(TestRunnerAction::class.java)
            Mockito.`when`<T?>(
                mockTestAction.getTestOutputsMapping(
                    ArgumentMatchers.any<T?>(),
                    ArgumentMatchers.any<T?>()
                )
            ).thenReturn(com.google.common.collect.ImmutableMultimap.of<K?, V?>())
            Mockito.`when`<T?>(mockTestAction.getShardNum()).thenReturn(shardNum)
            return TestResult(
                mockTestAction,
                data.build(),
                com.google.common.collect.ImmutableMultimap.of<K?, V?>(),  /* cached= */
                false,  /* systemFailure= */
                null
            )
        }
    }
}
