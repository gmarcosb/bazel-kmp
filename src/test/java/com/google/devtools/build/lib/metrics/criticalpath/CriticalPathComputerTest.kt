// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.metrics.criticalpath

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.actions.Action
import com.google.devtools.build.lib.clock.BlazeClock
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache
import com.google.devtools.build.lib.testutil.ManualClock
import com.google.errorprone.annotations.CanIgnoreReturnValue
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.time.Duration

/** Unit tests for [CriticalPathComputer].  */
@RunWith(TestParameterInjector::class)
class CriticalPathComputerTest : FoundationTestCase() {
    private var clock: ManualClock? = null
    private var computer: CriticalPathComputer? = null
    private var artifactRoot: ArtifactRoot? = null
    private var derivedArtifactRoot: ArtifactRoot? = null

    @Before
    fun initializeRoots() {
        val workspaceRoot: Path? = scratch.resolve("/workspace")
        derivedArtifactRoot = ArtifactRoot.asDerivedRoot(workspaceRoot, RootType.OUTPUT, "test")
        artifactRoot = ArtifactRoot.asSourceRoot(Root.fromPath(workspaceRoot))
    }

    @Before
    fun createComputer() {
        clock = ManualClock()
        computer = CriticalPathComputer(ActionKeyContext(),  /* graph= */null)
    }

    @Test
    fun testNoSpawnMetrics() {
        val cp = CriticalPathComponent(1, NullAction(), 0)
        assertThat(cp.getSpawnMetrics()).isEqualTo(AggregatedSpawnMetrics.EMPTY)
        Truth.assertThat(cp.longestPhaseSpawnRunnerName).isNull()
    }

    @Test
    fun testMultipleSpawnMetrics() {
        val cp = CriticalPathComponent(1, NullAction(), 0)
        cp.addSpawnResult(
            SpawnMetrics.Builder.forRemoteExec().setTotalTimeInMs(10 * 1000).build(),
            "first",
            "",
            false
        )
        cp.addSpawnResult(
            SpawnMetrics.Builder.forRemoteExec().setTotalTimeInMs(30 * 1000).build(),
            "second",
            "",
            false
        )
        cp.addSpawnResult(
            SpawnMetrics.Builder.forRemoteExec().setTotalTimeInMs(20 * 1000).build(),
            "third",
            "",
            false
        )
        cp.finishActionExecution(0, 40, "test")
        // The current implementation keeps the maximum spawn metrics because we do not differentiate
        // between sequential or parallel spawn invocations within a single Bazel action. So while it is
        // still 'incorrect', it is more fair than keeping the latest invocation data.
        assertThat(cp.getSpawnMetrics().getRemoteMetrics().totalTimeInMs()).isEqualTo(30 * 1000)
        Truth.assertThat(cp.longestPhaseSpawnRunnerName).isEqualTo("second")
    }

    /**
     * Test that 'other' time is correctly computed as any time not measured by the rest of the stats.
     */
    @Test
    fun testSpawnMetricsOtherTimeComputed() {
        val spawnMetrics: SpawnMetrics =
            SpawnMetrics.Builder.forRemoteExec()
                .setTotalTimeInMs(100 * 1000)
                .setParseTimeInMs(1 * 1000)
                .setNetworkTimeInMs(2 * 1000)
                .setFetchTimeInMs(3 * 1000)
                .setQueueTimeInMs(4 * 1000)
                .setSetupTimeInMs(5 * 1000)
                .setUploadTimeInMs(6 * 1000)
                .setExecutionWallTimeInMs(7 * 1000)
                .setRetryTimeInMs(ImmutableMap.of<K?, V?>(1, 8 * 1000))
                .setProcessOutputsTimeInMs(9 * 1000)
                .build()
        assertThat(spawnMetrics.otherTimeInMs()).isEqualTo(55 * 1000)
    }

    @Test
    @Throws(Exception::class)
    fun testCriticalPathOneAction() {
        simulateActionExec(NullAction(), 2 * 1000, 1 * 1000, true)
        checkCriticalPath(
            Duration.ofSeconds(2),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            "2.00",
            "50.00",
            "50.00"
        )
        Companion.checkTopComponentsTimes(computer!!, 2000L)
    }

    @Test
    @Throws(Exception::class)
    fun testCriticalPathQueueTimeWithoutRetries() {
        val spawnResult: SpawnResult.Builder =
            createSpawnResult()
                .setSpawnMetrics(
                    SpawnMetrics.Builder.forRemoteExec()
                        .setTotalTimeInMs(4 * 1000)
                        .setExecutionWallTimeInMs(1 * 1000)
                        .setQueueTimeInMs(1 * 1000)
                        .build()
                )
        simulateActionExec(NullAction(), 8 * 1000, spawnResult.build())
        val stats =
            checkCriticalPath(
                Duration.ofSeconds(8),
                Duration.ofSeconds(4),
                Duration.ofSeconds(1),
                "8.00",
                "50.00",
                "12.50"
            )
        assertThat(stats.getSpawnMetrics().getRemoteMetrics().queueTimeInMs()).isEqualTo(1 * 1000)
    }

    /**
     * Check that the timing stats are printed correctly, that the printed values correctly match
     * their label.
     */
    @Test
    @Throws(Exception::class)
    fun testCriticalPathToString() {
        val actionA: MockAction = MockAction(ImmutableSet.of<Artifact>(), ImmutableSet.of<Artifact>(artifact("a.out")))
        val spawnResult: SpawnResult.Builder? =
            createSpawnResult()
                .setSpawnMetrics(
                    SpawnMetrics.Builder.forRemoteExec()
                        .setParseTimeInMs(5 * 1000)
                        .setNetworkTimeInMs(6 * 1000)
                        .setFetchTimeInMs(7 * 1000)
                        .setQueueTimeInMs(8 * 1000)
                        .setSetupTimeInMs(9 * 1000)
                        .setUploadTimeInMs(10 * 1000)
                        .setProcessOutputsTimeInMs(4 * 1000)
                        .setExecutionWallTimeInMs(40 * 1000)
                        .setTotalTimeInMs(100 * 1000)
                        .build()
                )
        simulateActionExec(actionA, spawnResult)
        val stats = computer!!.aggregate()
        Truth.assertThat(stats).isNotNull()

        val toString = stats.toString()
        Truth.assertThat(toString).contains("parse: 5.00%")
        Truth.assertThat(toString).contains("network: 6.00%")
        Truth.assertThat(toString).contains("fetch: 7.00%")
        Truth.assertThat(toString).contains("queue: 8.00%")
        Truth.assertThat(toString).contains("setup: 9.00%")
        Truth.assertThat(toString).contains("upload: 10.00%")
        Truth.assertThat(toString).contains("processOutputs: 4.00%")
        Truth.assertThat(toString).contains("process: 40.00%")
        Truth.assertThat(toString).contains("other: 11.00%")
    }

    /**
     * Check that we only print certain critical parts of the timing stats when they are below a
     * certain threshold, to avoid spamming the user.
     */
    @Test
    @Throws(Exception::class)
    fun testCriticalPathToStringSummary() {
        val actionA: MockAction = MockAction(ImmutableSet.of<Artifact>(), ImmutableSet.of<Artifact>(artifact("a.out")))
        val actionB: MockAction =
            MockAction(
                mutableSetOf<Artifact>(artifact("a.out")), ImmutableSet.of<Artifact>(artifact("b.out"))
            )

        var spawnResult: SpawnResult.Builder? =
            createSpawnResult()
                .setSpawnMetrics(
                    SpawnMetrics.Builder.forRemoteExec()
                        .setNetworkTimeInMs(10 * 1000)
                        .setParseTimeInMs(10 * 1000)
                        .setFetchTimeInMs(10 * 1000)
                        .setQueueTimeInMs(10 * 1000)
                        .setSetupTimeInMs(10 * 1000)
                        .setProcessOutputsTimeInMs(10 * 1000)
                        .setExecutionWallTimeInMs(20 * 1000)
                        .setUploadTimeInMs(10 * 1000)
                        .setTotalTimeInMs(100 * 1000)
                        .build()
                )
        simulateActionExec(actionA, spawnResult)
        var stats = computer!!.aggregate()
        Truth.assertThat(stats).isNotNull()
        var summary = stats.toString()
        Truth.assertThat(summary).contains("network: 10.00%")
        Truth.assertThat(summary).contains("parse: 10.00%")
        Truth.assertThat(summary).contains("queue: 10.00%")
        Truth.assertThat(summary).contains("upload: 10.00%")
        Truth.assertThat(summary).contains("setup: 10.00%")
        Truth.assertThat(summary).contains("processOutputs: 10.00%")
        Truth.assertThat(summary).contains("process: 20.00%")
        Truth.assertThat(summary).contains("fetch: 10.00%")
        Truth.assertThat(summary).contains("other: 10.00%")

        // Add another action execution so that now the critical path is A + B, and the 10 second stats
        // each are bumped below 10%, bringing them below the "summary" threshold.
        spawnResult = createSpawnResult(10 * 1000)
        simulateActionExec(actionB, spawnResult)
        stats = computer!!.aggregate()
        Truth.assertThat(stats).isNotNull()
        summary = stats.toStringSummary()
        Truth.assertThat(summary).doesNotContain("network:")
        Truth.assertThat(summary).doesNotContain("parse:")
        Truth.assertThat(summary).contains("queue:")
        Truth.assertThat(summary).doesNotContain("upload:")
        Truth.assertThat(summary).contains("setup:")
        Truth.assertThat(summary).contains("process:")
        Truth.assertThat(summary).doesNotContain("fetch:")
        Truth.assertThat(summary).doesNotContain("processOutputs:")
        Truth.assertThat(summary).doesNotContain("other:")
    }

    // The real value of durations are not important for the test, using the same unit for all
    // declarations makes it easier to verify the aggregated values are correct.
    @Test
    @Throws(Exception::class)
    fun testAggregateMetrics() {
        val actionA: MockAction = MockAction(ImmutableList.of<Artifact>(), ImmutableSet.of<Artifact>(artifact("a.out")))
        val actionB: MockAction =
            MockAction(ImmutableList.of<Artifact>(artifact("a.out")), ImmutableSet.of<Artifact>(artifact("b.out")))
        val actionC: MockAction =
            MockAction(ImmutableList.of<Artifact>(artifact("b.out")), ImmutableSet.of<Artifact>(artifact("c.out")))
        val actionD: MockAction =
            MockAction(ImmutableList.of<Artifact>(artifact("c.out")), ImmutableSet.of<Artifact>(artifact("d.out")))

        simulateActionExec(
            actionA,
            createSpawnResult()
                .setSpawnMetrics(
                    SpawnMetrics.Builder.forRemoteExec()
                        .setNetworkTimeInMs(1 * 1000)
                        .setParseTimeInMs(2 * 1000)
                        .setFetchTimeInMs(3 * 1000)
                        .setQueueTimeInMs(4 * 1000)
                        .setSetupTimeInMs(5 * 1000)
                        .setProcessOutputsTimeInMs(6 * 1000)
                        .setExecutionWallTimeInMs(7 * 1000)
                        .setUploadTimeInMs(8 * 1000)
                        .setTotalTimeInMs(100 * 1000)
                        .build()
                )
        )

        simulateActionExec(
            actionB,
            createSpawnResult()
                .setSpawnMetrics(
                    SpawnMetrics.Builder.forRemoteExec()
                        .setNetworkTimeInMs(20 * 1000)
                        .setParseTimeInMs(30 * 1000)
                        .setFetchTimeInMs(40 * 1000)
                        .setQueueTimeInMs(50 * 1000)
                        .setSetupTimeInMs(60 * 1000)
                        .setProcessOutputsTimeInMs(70 * 1000)
                        .setExecutionWallTimeInMs(80 * 1000)
                        .setUploadTimeInMs(90 * 1000)
                        .setTotalTimeInMs(1000 * 1000)
                        .build()
                )
        )

        simulateActionExec(
            actionC,
            createSpawnResult()
                .setSpawnMetrics(
                    SpawnMetrics.Builder.forWorkerExec()
                        .setNetworkTimeInMs(10 * 1000)
                        .setParseTimeInMs(20 * 1000)
                        .setFetchTimeInMs(30 * 1000)
                        .setQueueTimeInMs(40 * 1000)
                        .setSetupTimeInMs(50 * 1000)
                        .setProcessOutputsTimeInMs(60 * 1000)
                        .setExecutionWallTimeInMs(70 * 1000)
                        .setUploadTimeInMs(80 * 1000)
                        .setTotalTimeInMs(1000 * 1000)
                        .build()
                )
        )

        simulateActionExec(
            actionD,
            createSpawnResult()
                .setSpawnMetrics(
                    SpawnMetrics.Builder.forWorkerExec()
                        .setNetworkTimeInMs(200 * 1000)
                        .setParseTimeInMs(300 * 1000)
                        .setFetchTimeInMs(400 * 1000)
                        .setQueueTimeInMs(500 * 1000)
                        .setSetupTimeInMs(600 * 1000)
                        .setProcessOutputsTimeInMs(700 * 1000)
                        .setExecutionWallTimeInMs(800 * 1000)
                        .setUploadTimeInMs(900 * 1000)
                        .setTotalTimeInMs(10000 * 1000)
                        .build()
                )
        )

        val aggregated: AggregatedSpawnMetrics = computer!!.aggregate().getSpawnMetrics()
        val remoteMetrics: SpawnMetrics = aggregated.getMetrics(SpawnMetrics.ExecKind.REMOTE)
        assertThat(remoteMetrics.networkTimeInMs()).isEqualTo(21 * 1000)
        assertThat(remoteMetrics.parseTimeInMs()).isEqualTo(32 * 1000)
        assertThat(remoteMetrics.fetchTimeInMs()).isEqualTo(43 * 1000)
        assertThat(remoteMetrics.queueTimeInMs()).isEqualTo(54 * 1000)
        assertThat(remoteMetrics.setupTimeInMs()).isEqualTo(65 * 1000)
        assertThat(remoteMetrics.processOutputsTimeInMs()).isEqualTo(76 * 1000)
        assertThat(remoteMetrics.executionWallTimeInMs()).isEqualTo(87 * 1000)
        assertThat(remoteMetrics.uploadTimeInMs()).isEqualTo(98 * 1000)
        assertThat(remoteMetrics.totalTimeInMs()).isEqualTo(1100 * 1000)

        val workerMetrics: SpawnMetrics = aggregated.getMetrics(SpawnMetrics.ExecKind.WORKER)
        assertThat(workerMetrics.networkTimeInMs()).isEqualTo(210 * 1000)
        assertThat(workerMetrics.parseTimeInMs()).isEqualTo(320 * 1000)
        assertThat(workerMetrics.fetchTimeInMs()).isEqualTo(430 * 1000)
        assertThat(workerMetrics.queueTimeInMs()).isEqualTo(540 * 1000)
        assertThat(workerMetrics.setupTimeInMs()).isEqualTo(650 * 1000)
        assertThat(workerMetrics.processOutputsTimeInMs()).isEqualTo(760 * 1000)
        assertThat(workerMetrics.executionWallTimeInMs()).isEqualTo(870 * 1000)
        assertThat(workerMetrics.uploadTimeInMs()).isEqualTo(980 * 1000)
        assertThat(workerMetrics.totalTimeInMs()).isEqualTo(11000 * 1000)
    }

    @Test
    fun testEmptyCriticalPath() {
        val empty = computer!!.aggregate()
        Truth.assertThat(empty.components()).isEmpty()
        assertThat(empty.aggregatedElapsedTime.toMillis()).isEqualTo(0)
        Companion.checkTopComponentsTimes(computer!!)
    }

    /** Tests that we only record the top slowest components and that we drop the rest.  */
    @Test
    @Throws(Exception::class)
    fun testTopComponentsOverflow() {
        for (i in 0..1000) {
            val action: MockAction =
                MockAction(ImmutableSet.of<Artifact>(), ImmutableSet.of<Artifact>(artifact(i.toString() + ".out")))
            simulateActionExec(action, i)
        }
        val topTimes = LongArray(CriticalPathComputer.SLOWEST_COMPONENTS_SIZE)
        for (i in 0..<CriticalPathComputer.SLOWEST_COMPONENTS_SIZE) {
            topTimes[i] = 1000L - i
        }
        Companion.checkTopComponentsTimes(computer!!, *topTimes)
    }

    @Test
    @Throws(Exception::class)
    fun testLargestMemoryComponentsOverflow() {
        for (i in 0..999) {
            val action: MockAction =
                MockAction(ImmutableSet.of<Artifact>(), ImmutableSet.of<Artifact>(artifact(i.toString() + ".out")))
            // the largest actions are in the middle
            simulateActionExec(
                action,
                createSpawnResult()
                    .setSpawnMetrics(
                        SpawnMetrics.Builder.forRemoteExec()
                            .setMemoryEstimateBytes(if (500 < i && i < 600) i else 0)
                            .setExecutionWallTimeInMs(1 * 1000)
                            .setTotalTimeInMs(i * 1000)
                            .build()
                    )
            )
        }

        val result: MutableList<CriticalPathComponent> = computer!!.getLargestMemoryComponents()

        Truth.assertThat(result).hasSize(20)
        assertThat(result.get(0).getSpawnMetrics().getRemoteMetrics().memoryEstimate()).isEqualTo(599)
    }

    @Test
    @Throws(Exception::class)
    fun testLargestInputSizeComponentsOverflow() {
        for (i in 0..999) {
            val action: MockAction =
                MockAction(ImmutableSet.of<Artifact>(), ImmutableSet.of<Artifact>(artifact(i.toString() + ".out")))
            simulateActionExec(
                action,
                createSpawnResult()
                    .setSpawnMetrics(
                        SpawnMetrics.Builder.forRemoteExec()
                            .setInputBytes(if (500 < i && i < 600) i else 0)
                            .setExecutionWallTimeInMs(1 * 1000)
                            .setTotalTimeInMs(i * 1000)
                            .build()
                    )
            )
        }

        val result: MutableList<CriticalPathComponent> = computer!!.getLargestInputSizeComponents()

        Truth.assertThat(result).hasSize(20)
        assertThat(result.get(0).getSpawnMetrics().getRemoteMetrics().inputBytes()).isEqualTo(599)
    }

    @Test
    @Throws(Exception::class)
    fun testLargestInputCountComponentsOverflow() {
        for (i in 0..999) {
            val action: MockAction =
                MockAction(ImmutableSet.of<Artifact>(), ImmutableSet.of<Artifact>(artifact(i.toString() + ".out")))
            simulateActionExec(
                action,
                createSpawnResult()
                    .setSpawnMetrics(
                        SpawnMetrics.Builder.forRemoteExec()
                            .setInputFiles(if (500 < i && i < 600) i else 0)
                            .setExecutionWallTimeInMs(1 * 1000)
                            .setTotalTimeInMs(i * 1000)
                            .build()
                    )
            )
        }

        val result: MutableList<CriticalPathComponent> = computer!!.getLargestInputCountComponents()

        Truth.assertThat(result).hasSize(20)
        assertThat(result.get(0).getSpawnMetrics().getRemoteMetrics().inputFiles()).isEqualTo(599)
    }

    @Test
    @Throws(Exception::class)
    fun testActionCached() {
        val cachedAction: MockAction =
            MockAction(ImmutableSet.of<Artifact>(), ImmutableSet.of<Artifact>(artifact("cached.out")))

        val topLevelAction: MockAction =
            MockAction(
                mutableSetOf<Artifact>(artifact("cached.out")), ImmutableSet.of<Artifact>(artifact("top.out"))
            )

        computer!!.actionCached(
            CachedActionEvent(
                cachedAction, FakeActionInputFileCache(), clock!!.nanoTime(), clock!!.nanoTime()
            )
        )
        simulateActionExec(topLevelAction, 1000)

        val aggregated = computer!!.aggregate()

        Truth.assertThat(aggregated.components()).hasSize(2)
        Companion.assertActionMatches(topLevelAction, aggregated.components().get(0)!!)
        Companion.assertActionMatches(cachedAction, aggregated.components().get(1)!!)
        Truth.assertThat<Duration?>(aggregated.components().get(0)!!.getElapsedTime()).isEqualTo(Duration.ofSeconds(1))
        Truth.assertThat<Duration?>(aggregated.components().get(1)!!.getElapsedTime()).isEqualTo(Duration.ZERO)

        Companion.checkTopComponentsTimes(computer!!, 1000, 0L)
    }

    /** Test that wall time is not computed using nanotime.  */
    @Test
    @Throws(Exception::class)
    fun testWallTime() {
        simulateActionExec(NullAction(), 2000)
        checkCriticalPath(2000, "2.00")
        Companion.checkTopComponentsTimes(computer!!, 2000L)
        val converter =
            BlazeClock.createNanosToMillisSinceEpochConverter(clock!!)
        Truth.assertThat(computer!!.getMaxCriticalPath()!!.getStartTimeMillisSinceEpoch(converter)).isEqualTo(0L)
    }

    /**
     * When running shared actions concurrently we might end up receiving multiple events, one per
     * shared action. In that case we record a single component and we update the time of the maximum
     * elapsed time.
     */
    @Test
    @Throws(Exception::class)
    fun testConcurrentSharedActions() {
        val shared1: MockAction =
            MockAction(ImmutableSet.of<Artifact>(), ImmutableSet.of<Artifact>(artifact("shared.out")))
        val shared2: MockAction =
            MockAction(ImmutableSet.of<Artifact>(), ImmutableSet.of<Artifact>(artifact("shared.out")))

        val action1: MockAction =
            MockAction(
                mutableSetOf<Artifact>(artifact("shared.out")),
                ImmutableSet.of<Artifact>(artifact("action1.out"))
            )

        val action2: MockAction =
            MockAction(
                mutableSetOf<Artifact>(artifact("shared.out")),
                ImmutableSet.of<Artifact>(artifact("action2.out"))
            )

        val shared1Start = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(shared1, shared1Start))
        clock!!.advanceMillis(1000)
        val shared2Start = clock!!.nanoTime()
        // We concurrently execute shared2 before shared1 could finish. But we record it as a cache hit.
        computer!!.actionCached(
            CachedActionEvent(
                shared2, FakeActionInputFileCache(), clock!!.nanoTime(), shared2Start
            )
        )
        clock!!.advanceMillis(1)
        // Action2 depends on shared2, so it can start executing without waiting to shared1. This will
        // prevent us from identifying the critical path in some circumstance, but we are OK with that.
        simulateActionExec(action2, 11)

        computer!!.actionComplete(
            ActionCompletionEvent(
                shared1Start,
                clock!!.nanoTime(),
                shared1,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))
        simulateActionExec(action1, 10)
        val criticalPath = computer!!.aggregate()

        // Yes, this is not correct but expected. While action2.time > action1.time, because
        // action2 executed before shared1 finishes it incorrectly gets the time set by shared2.
        Companion.assertActionMatches(action1, criticalPath.components().get(0)!!)
        // We expect that the component used for any critical path is shared1, as it is the first that
        // was started.
        Companion.assertActionMatches(shared1, criticalPath.components().get(1)!!)
        Truth.assertThat<Duration?>(criticalPath.components().get(1)!!.getElapsedTime())
            .isEqualTo(Duration.ofMillis(1012))

        val slowest: MutableList<CriticalPathComponent> = computer!!.getSlowestComponents()
        Truth.assertThat(slowest).hasSize(3)
        for (cpath in slowest) {
            if (actionMatches(shared1, cpath)) {
                Truth.assertThat<Duration?>(cpath.getElapsedTime()).isEqualTo(Duration.ofMillis(1012))
            }
            // While shared2 was a cache hit, because it was executed concurrently with shared1 we
            // keep one component with the maximum time.
            if (actionMatches(shared2, cpath)) {
                Truth.assertThat<Duration?>(cpath.getElapsedTime()).isEqualTo(Duration.ofMillis(1012))
            }
            if (actionMatches(action1, cpath)) {
                Truth.assertThat<Duration?>(cpath.getElapsedTime()).isEqualTo(Duration.ofMillis(10))
            }
            if (actionMatches(action2, cpath)) {
                Truth.assertThat<Duration?>(cpath.getElapsedTime()).isEqualTo(Duration.ofMillis(11))
                Truth.assertThat<Duration?>(cpath.child!!.getElapsedTime()).isEqualTo(Duration.ofMillis(1012))
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun testTotalAggregateRunTimeWithGaps() {
        val action1: MockAction =
            MockAction(ImmutableSet.of<Artifact>(), ImmutableSet.of<Artifact>(artifact("action1.out")))
        val action2: MockAction =
            MockAction(
                ImmutableSet.of<Artifact>(artifact("action1.out")), ImmutableSet.of<Artifact>(artifact("action2.out"))
            )
        val action3: MockAction =
            MockAction(
                ImmutableSet.of<Artifact>(artifact("action2.out")), ImmutableSet.of<Artifact>(artifact("action3.out"))
            )

        val action1Start = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(action1, action1Start))
        clock!!.advanceMillis(1000)
        computer!!.actionComplete(
            ActionCompletionEvent(
                action1Start,
                clock!!.nanoTime(),
                action1,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))

        clock!!.advanceMillis(2000)
        val action2Start = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(action2, action2Start))
        clock!!.advanceMillis(3000)
        computer!!.actionComplete(
            ActionCompletionEvent(
                action2Start,
                clock!!.nanoTime(),
                action2,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))

        clock!!.advanceMillis(2000)
        val action3Start = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(action3, action3Start))
        clock!!.advanceMillis(4000)
        computer!!.actionComplete(
            ActionCompletionEvent(
                action3Start,
                clock!!.nanoTime(),
                action3,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))

        // The runtime of the critical path ignoring gaps is 8 seconds.
        Truth.assertThat<Duration?>(computer!!.getMaxCriticalPath()!!.getAggregatedElapsedTime())
            .isEqualTo(Duration.ofSeconds(8))
        Truth.assertThat<Duration?>(Duration.ofNanos(clock!!.nanoTime() - action1Start))
            .isEqualTo(Duration.ofSeconds(12))
    }

    @Test
    @Throws(Exception::class)
    fun testTotalAggregateRunTimeWithOverlappingTimes() {
        val action1: MockAction =
            MockAction(ImmutableSet.of<Artifact>(), ImmutableSet.of<Artifact>(artifact("action1.out")))
        val action2: MockAction =
            MockAction(
                ImmutableSet.of<Artifact>(artifact("action1.out")), ImmutableSet.of<Artifact>(artifact("action2.out"))
            )

        val action1Start = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(action1, action1Start))
        clock!!.advanceMillis(1000)
        val action2Start = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(action2, action2Start))
        clock!!.advanceMillis(2000)
        computer!!.actionComplete(
            ActionCompletionEvent(
                action1Start,
                clock!!.nanoTime(),
                action1,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))
        clock!!.advanceMillis(2000)
        computer!!.actionComplete(
            ActionCompletionEvent(
                action2Start,
                clock!!.nanoTime(),
                action2,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))

        // The total run time of all actions in the critical path is 5 seconds.
        Truth.assertThat<Duration?>(computer!!.getMaxCriticalPath()!!.getAggregatedElapsedTime())
            .isEqualTo(Duration.ofSeconds(5))
        val criticalPath = computer!!.aggregate()
        Truth.assertThat(criticalPath.components()).hasSize(2)
        // Action 2  has a run time of 4 seconds
        Truth.assertThat<Duration?>(criticalPath.components().get(0)!!.getElapsedTime())
            .isEqualTo(Duration.ofSeconds(4))
        // Action 1 has a run time of 3 seconds
        Truth.assertThat<Duration?>(criticalPath.components().get(1)!!.getElapsedTime())
            .isEqualTo(Duration.ofSeconds(3))
    }

    @Test
    @Throws(Exception::class)
    fun testTotalAggregateRunTimeWithParallelRuns() {
        val action1: MockAction =
            MockAction(ImmutableSet.of<Artifact>(), ImmutableSet.of<Artifact>(artifact("action1.out")))
        val action2: MockAction =
            MockAction(
                ImmutableSet.of<Artifact>(artifact("action1.out")), ImmutableSet.of<Artifact>(artifact("action2.out"))
            )

        val action2Start = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(action2, action2Start))
        clock!!.advanceMillis(1000)
        val action1Start = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(action1, action1Start))
        clock!!.advanceMillis(2000)
        computer!!.actionComplete(
            ActionCompletionEvent(
                action1Start,
                clock!!.nanoTime(),
                action1,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))
        clock!!.advanceMillis(2000)
        computer!!.actionComplete(
            ActionCompletionEvent(
                action2Start,
                clock!!.nanoTime(),
                action2,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))

        // The total run time of all actions in the critical path is 5 seconds.
        Truth.assertThat<Duration?>(computer!!.getMaxCriticalPath()!!.getAggregatedElapsedTime())
            .isEqualTo(Duration.ofSeconds(5))
        val criticalPath = computer!!.aggregate()
        Truth.assertThat(criticalPath.components()).hasSize(2)
        // Action 2 has a run time of 5 seconds
        Truth.assertThat<Duration?>(criticalPath.components().get(0)!!.getElapsedTime())
            .isEqualTo(Duration.ofSeconds(5))
        // Action 1 has a run time of 2 seconds
        Truth.assertThat<Duration?>(criticalPath.components().get(1)!!.getElapsedTime())
            .isEqualTo(Duration.ofSeconds(2))
    }

    @Test
    @Throws(Exception::class)
    fun testLongestTotalTime() {
        val action1: MockAction =
            MockAction(ImmutableSet.of<Artifact>(), ImmutableSet.of<Artifact>(artifact("action1.out")))
        val action2: MockAction =
            MockAction(ImmutableSet.of<Artifact>(), ImmutableSet.of<Artifact>(artifact("action2.out")))
        val action3: MockAction =
            MockAction(
                ImmutableList.of<Artifact>(artifact("action1.out"), artifact("action2.out")),
                ImmutableSet.of<Artifact>(artifact("action3.out"))
            )

        // Action 1 - 0s - 3s
        val action1Start = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(action1, action1Start))
        clock!!.advanceMillis(3000)
        computer!!.actionComplete(
            ActionCompletionEvent(
                action1Start,
                clock!!.nanoTime(),
                action1,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))
        // Action 2 - 3s - 7s
        val action2Start = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(action2, action2Start))
        clock!!.advanceMillis(1000)
        // Action 3 - 4s - 7s
        val action3Start = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(action3, action3Start))
        clock!!.advanceMillis(3000)
        computer!!.actionComplete(
            ActionCompletionEvent(
                action2Start,
                clock!!.nanoTime(),
                action2,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))
        computer!!.actionComplete(
            ActionCompletionEvent(
                action3Start,
                clock!!.nanoTime(),
                action3,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))

        // The total run time should be 6s (Action 1 + Action 3) since Action 2 overlaps with
        // action 3, they will not be aggregated.
        Truth.assertThat<Duration?>(computer!!.getMaxCriticalPath()!!.getAggregatedElapsedTime())
            .isEqualTo(Duration.ofSeconds(6))
        val criticalPath = computer!!.aggregate()
        Truth.assertThat(criticalPath.components()).hasSize(2)
        // Action 3 has a run time of 3 seconds
        Truth.assertThat<Duration?>(criticalPath.components().get(0)!!.getElapsedTime())
            .isEqualTo(Duration.ofSeconds(3))
        // Action 1 has a run time of 3 seconds
        Truth.assertThat<Duration?>(criticalPath.components().get(1)!!.getElapsedTime())
            .isEqualTo(Duration.ofSeconds(3))
    }

    @Test
    @Throws(Exception::class)
    fun rewoundActionMayStartTwice() {
        // This test demonstrates that a rewound action can cause two ActionStartedEvents to be emitted,
        // one paired with an ActionRewoundEvent and the other with an ActionCompletedEvent, and the
        // CriticalPathComputer handles it.
        val producer: MockAction =
            MockAction(ImmutableSet.of<Artifact>(), ImmutableSet.of<Artifact>(artifact("shared.out")))
        val consumer: MockAction =
            MockAction(
                mutableSetOf<Artifact>(artifact("shared.out")),
                ImmutableSet.of<Artifact>(artifact("consumer.out"))
            )

        simulateActionExec(producer, 10)
        val consumerFirstStart = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(consumer, consumerFirstStart))
        clock!!.advanceMillis(5)
        computer!!.actionRewound(ActionRewoundEvent(consumerFirstStart, clock!!.nanoTime(), consumer))

        // In a real rewinding case, "producer" would be re-evaluated, and the events for that
        // re-evaluation would be suppressed. This statement simulates that process by advancing the
        // clock without any associated events.
        clock!!.advanceMillis(10)
        simulateActionExec(consumer, 20)

        val criticalPath = computer!!.aggregate()

        Companion.assertActionMatches(consumer, criticalPath.components().get(0)!!)
        Companion.assertActionMatches(producer, criticalPath.components().get(1)!!)

        Truth.assertThat<Duration?>(criticalPath.components().get(0)!!.getElapsedTime())
            .isEqualTo(Duration.ofMillis(20))
        Truth.assertThat<Duration?>(criticalPath.components().get(1)!!.getElapsedTime())
            .isEqualTo(Duration.ofMillis(10))

        val slowest: MutableList<CriticalPathComponent> = computer!!.getSlowestComponents()
        Truth.assertThat(slowest).hasSize(2)
        for (cpath in slowest) {
            if (actionMatches(producer, cpath)) {
                Truth.assertThat<Duration?>(cpath.getElapsedTime()).isEqualTo(Duration.ofMillis(10))
            }
            if (actionMatches(consumer, cpath)) {
                Truth.assertThat<Duration?>(cpath.getElapsedTime()).isEqualTo(Duration.ofMillis(20))
            }
        }
    }

    /**
     * Check that the slowest components list does not duplicate entries when an action has multiple
     * outputs.
     */
    @Test
    @Throws(Exception::class)
    fun testSlowestComponentsNoDuplicates() {
        val action: MockAction =
            MockAction(ImmutableList.of<Artifact>(), ImmutableSet.of<Artifact>(artifact("a.out"), artifact("b.out")))
        simulateActionExec(action, 123)

        val slowest: MutableList<CriticalPathComponent> = computer!!.getSlowestComponents()
        Truth.assertThat(slowest).hasSize(1)
    }

    @Test
    @Throws(Exception::class)
    fun testSequentialActionExec() {
        simulateSequentialAndParallelActionExec(
            MockAction(ImmutableList.of<Artifact>(), ImmutableSet.of<Artifact>(artifact("a.out"))),
            ImmutableList.of<ImmutableList<Int?>?>(
                ImmutableList.of<Int?>(2 * 1000), ImmutableList.of<Int?>(3 * 1000), ImmutableList.of<Int?>(4 * 1000)
            )
        )
        val metrics: SpawnMetrics = computer!!.getMaxCriticalPath()!!.getSpawnMetrics().getRemoteMetrics()
        assertThat(metrics.totalTimeInMs()).isEqualTo(9 * 1000)
    }

    @Test
    @Throws(Exception::class)
    fun testMaximumSequentialAndParallelActionMetrics() {
        val action: MockAction = MockAction(ImmutableList.of<Artifact>(), ImmutableSet.of<Artifact>(artifact("a.out")))

        val seqAndParallelSeries =
            ImmutableList.of<ImmutableList<Int?>?>(
                ImmutableList.of<Int?>(5 * 1000),  // +5
                ImmutableList.of<Int?>(1 * 1000, 3 * 1000),  // +3
                ImmutableList.of<Int?>(7 * 1000) // +7
            )

        simulateSequentialAndParallelActionExec(action, seqAndParallelSeries)
        val metrics: SpawnMetrics = computer!!.getMaxCriticalPath()!!.getSpawnMetrics().getRemoteMetrics()
        assertThat(metrics.totalTimeInMs()).isEqualTo(15 * 1000)
    }

    @Test
    @Throws(Exception::class)
    fun testInputDiscoveryAndAction() {
        val action: Action = MockAction(ImmutableList.of<Artifact>(), ImmutableSet.of<Artifact>(artifact("a.out")))
        simulateActionExec(action, 2 * 1000, 2 * 1000, true, 5 * 1000)
        val metrics: SpawnMetrics = computer!!.getMaxCriticalPath()!!.getSpawnMetrics().getRemoteMetrics()
        assertThat(metrics.parseTimeInMs()).isEqualTo(5 * 1000)
        assertThat(metrics.executionWallTimeInMs()).isEqualTo(2 * 1000)
        assertThat(metrics.totalTimeInMs()).isEqualTo(7 * 1000)
    }

    @Test
    @Throws(Exception::class)
    fun testInputDiscoveryBeforeActionStarted() {
        val artifact: Artifact = artifact("a.out")
        val action: Action = MockAction(ImmutableList.of<Artifact>(), ImmutableSet.of<Artifact>(artifact))
        computer!!.discoverInputs(
            DiscoveredInputsEvent(
                SpawnMetrics.Builder.forRemoteExec()
                    .setParseTimeInMs(5 * 1000)
                    .setTotalTimeInMs(5 * 1000)
                    .build(),
                action,  /* startTimeNanos= */
                0
            )
        )

        computer!!.actionComplete(
            ActionCompletionEvent(
                0,
                clock!!.nanoTime(),
                action,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))
        val metrics: SpawnMetrics = computer!!.getMaxCriticalPath()!!.getSpawnMetrics().getRemoteMetrics()
        assertThat(metrics.parseTimeInMs()).isEqualTo(5 * 1000)
        assertThat(metrics.totalTimeInMs()).isEqualTo(5 * 1000)
    }

    @Test
    @Throws(Exception::class)
    fun testTryAddComponentShouldAddNonSharedActions() {
        val artifact: Artifact = artifact("a.out")
        val sharedAction: MockAction = MockAction(ImmutableList.of<Artifact>(), ImmutableSet.of<Artifact>(artifact))
        val nonSharedAction: MockAction =
            MockAction(ImmutableList.of<Artifact>(), ImmutableSet.of<Artifact>(artifact),  /* isShareable= */false)
        computer!!.actionStarted(ActionStartedEvent(sharedAction, clock!!.nanoTime()))
        val exception =
            Assert.assertThrows<IllegalStateException?>(
                IllegalStateException::class.java,
                ThrowingRunnable { computer!!.actionStarted(ActionStartedEvent(nonSharedAction, clock!!.nanoTime())) })
        Truth.assertThat(exception)
            .hasMessageThat()
            .contains("Duplicate output artifact found for unsharable actions.")
    }

    @Test
    @Throws(Exception::class)
    fun toleratesCriticalPathInconsistency() {
        val depArtifact: Artifact = derivedArtifact("test/a.out")
        val parentArtifact: Artifact = derivedArtifact("test/b.out")
        val depAction: MockAction = MockAction(ImmutableList.of<Artifact>(), ImmutableSet.of<Artifact>(depArtifact))
        val parentAction: MockAction =
            MockAction(ImmutableList.of<Artifact>(depArtifact), ImmutableSet.of<Artifact>(parentArtifact))

        computer!!.actionStarted(ActionStartedEvent(depAction, clock!!.nanoTime()))
        clock!!.advanceMillis(1000)
        computer!!.actionStarted(ActionStartedEvent(parentAction, clock!!.nanoTime()))

        // Complete the parent action while the dep action is still running and check that the resulting
        // critical path ignores the still-running dep.
        computer!!.actionComplete(
            ActionCompletionEvent(
                clock!!.nanoTime(),
                clock!!.nanoTime(),
                parentAction,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))
        assertThat(Iterables.getOnlyElement<CriticalPathComponent?>(computer!!.aggregate().components())!!.getAction())
            .isEqualTo(parentAction)
    }

    @Test
    @Throws(Exception::class)
    fun testChangePruning() {
        val action1: MockAction =
            MockAction(ImmutableSet.of<Artifact>(), ImmutableSet.of<Artifact>(derivedArtifact("test/action1.out")))
        val action2: MockAction =
            MockAction(
                ImmutableSet.of<Artifact>(derivedArtifact("test/action1.out")),
                ImmutableSet.of<Artifact>(derivedArtifact("test/action2.out"))
            )
        val action3: MockAction =
            MockAction(
                ImmutableList.of<Artifact>(derivedArtifact("test/action2.out")),
                ImmutableSet.of<Artifact>(derivedArtifact("test/action3.out"))
            )
        val action4: MockAction =
            MockAction(
                ImmutableList.of<Artifact>(
                    derivedArtifact("test/action1.out"), derivedArtifact("test/action3.out")
                ),
                ImmutableSet.of<Artifact>(derivedArtifact("test/action4.out"))
            )

        computer =
            CriticalPathComputer(
                ActionKeyContext(),
                object : WalkableGraph() {
                    @Throws(InterruptedException::class)
                    public override fun getValue(key: SkyKey?): SkyValue? {
                        if (key is ActionLookupKey) {
                            return object : ActionLookupValue() {
                                val actions: ImmutableList<ActionAnalysisMetadata>
                                    get() = ImmutableList.of<E?>(action3)
                            }
                        }
                        throw UnsupportedOperationException()
                    }

                    @Throws(InterruptedException::class)
                    public override fun getSuccessfulValues(keys: Iterable<out SkyKey?>?): MutableMap<SkyKey?, SkyValue?>? {
                        throw UnsupportedOperationException()
                    }

                    @Throws(InterruptedException::class)
                    public override fun getMissingAndExceptions(keys: Iterable<SkyKey?>?): MutableMap<SkyKey?, Exception?>? {
                        throw UnsupportedOperationException()
                    }

                    @Throws(InterruptedException::class)
                    public override fun getException(key: SkyKey?): Exception? {
                        throw UnsupportedOperationException()
                    }

                    @Throws(InterruptedException::class)
                    public override fun isCycle(key: SkyKey?): Boolean {
                        throw UnsupportedOperationException()
                    }

                    @Throws(InterruptedException::class)
                    public override fun getDirectDeps(keys: Iterable<SkyKey?>?): MutableMap<SkyKey?, Iterable<SkyKey?>?>? {
                        throw UnsupportedOperationException()
                    }

                    @Throws(InterruptedException::class)
                    public override fun getDirectDeps(key: SkyKey?): Iterable<SkyKey?>? {
                        throw UnsupportedOperationException()
                    }

                    @Throws(InterruptedException::class)
                    public override fun getReverseDeps(keys: Iterable<out SkyKey?>?): MutableMap<SkyKey?, Iterable<SkyKey?>?>? {
                        throw UnsupportedOperationException()
                    }

                    @Throws(InterruptedException::class)
                    public override fun getValueAndRdeps(
                        keys: Iterable<SkyKey?>?
                    ): MutableMap<SkyKey?, Pair<SkyValue?, Iterable<SkyKey?>?>?>? {
                        throw UnsupportedOperationException()
                    }
                })

        // Action 1 - 0s - 1s
        val action1Start = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(action1, action1Start))
        clock!!.advanceMillis(1000)
        computer!!.actionComplete(
            ActionCompletionEvent(
                action1Start,
                clock!!.nanoTime(),
                action1,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))
        // Action 2 - 1s - 3s
        val action2Start = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(action2, action2Start))
        clock!!.advanceMillis(2000)
        computer!!.actionComplete(
            ActionCompletionEvent(
                action2Start,
                clock!!.nanoTime(),
                action2,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))
        // Action 3 - 3s - 3s, change pruned, no events
        computer!!.actionChangePruned(
            ActionChangePrunedEvent(ActionsTestUtil.NULL_ACTION_LOOKUP_DATA, clock!!.nanoTime())
        )
        // Action 4 - 3s - 6s
        val action4Start = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(action4, action4Start))
        clock!!.advanceMillis(3000)
        computer!!.actionComplete(
            ActionCompletionEvent(
                action4Start,
                clock!!.nanoTime(),
                action4,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))

        // The total run time should be 6s (Action 1 + Action 2 + Action 4) since Action 3 is
        // change-pruned.
        Truth.assertThat<Duration?>(computer!!.getMaxCriticalPath()!!.getAggregatedElapsedTime())
            .isEqualTo(Duration.ofSeconds(6))
        val criticalPath = computer!!.aggregate()
        Truth.assertThat(criticalPath.components()).hasSize(4)
        // Action 4 has a run time of 3 seconds
        Truth.assertThat(criticalPath.components().get(0)!!.prettyPrintAction()).contains("action4.out")
        Truth.assertThat<Duration?>(criticalPath.components().get(0)!!.getElapsedTime())
            .isEqualTo(Duration.ofSeconds(3))
        // Action 3 has a run time of 0 seconds
        Truth.assertThat(criticalPath.components().get(1)!!.prettyPrintAction()).contains("action3.out")
        Truth.assertThat<Duration?>(criticalPath.components().get(1)!!.getElapsedTime()).isEqualTo(Duration.ZERO)
        // Action 2 has a run time of 2 seconds
        Truth.assertThat(criticalPath.components().get(2)!!.prettyPrintAction()).contains("action2.out")
        Truth.assertThat<Duration?>(criticalPath.components().get(2)!!.getElapsedTime())
            .isEqualTo(Duration.ofSeconds(2))
        // Action 1 has a run time of 1 seconds
        Truth.assertThat(criticalPath.components().get(3)!!.prettyPrintAction()).contains("action1.out")
        Truth.assertThat<Duration?>(criticalPath.components().get(3)!!.getElapsedTime())
            .isEqualTo(Duration.ofSeconds(1))
    }

    @Throws(InterruptedException::class)
    private fun simulateActionExec(action: Action?, totalTime: Int) {
        val nanoTimeStart = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(action, nanoTimeStart))
        clock!!.advanceMillis(totalTime.toLong())
        computer!!.actionComplete(
            ActionCompletionEvent(
                nanoTimeStart,
                clock!!.nanoTime(),
                action,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))
    }

    @Throws(InterruptedException::class)
    private fun simulateActionExec(
        action: Action,
        totalTimeInMs: Int,
        processTimeInMs: Int,
        completeAction: Boolean,
        discoverInputsDurationInMs: Int
    ) {
        computer!!.discoverInputs(
            DiscoveredInputsEvent(
                SpawnMetrics.Builder.forRemoteExec()
                    .setParseTimeInMs(discoverInputsDurationInMs)
                    .setTotalTimeInMs(discoverInputsDurationInMs)
                    .build(),
                action,  /* startTimeNanos= */
                0
            )
        )
        simulateActionExec(action, totalTimeInMs, processTimeInMs, completeAction)
    }

    @Throws(InterruptedException::class)
    private fun simulateActionExec(
        action: Action, totalTimeInMs: Int, processTimeInMs: Int, completeAction: Boolean
    ) {
        val spawnResult: SpawnResult? =
            createSpawnResult()
                .setSpawnMetrics(
                    SpawnMetrics.Builder.forRemoteExec()
                        .setTotalTimeInMs(processTimeInMs)
                        .setExecutionWallTimeInMs(processTimeInMs)
                        .build()
                )
                .build()
        simulateActionExec(action, totalTimeInMs, spawnResult, completeAction)
    }

    @Throws(InterruptedException::class)
    private fun simulateActionExec(action: Action, spawnResult: SpawnResult.Builder) {
        simulateActionExec(
            action, spawnResult.build().getMetrics().totalTimeInMs(), spawnResult.build()
        )
    }

    @Throws(InterruptedException::class)
    private fun simulateActionExec(action: Action, totalTimeInMs: Int, spawnResult: SpawnResult?) {
        simulateActionExec(action, totalTimeInMs, spawnResult, true)
    }

    @Throws(InterruptedException::class)
    private fun simulateActionExec(
        action: Action, totalTimeInMs: Int, spawnResult: SpawnResult?, completeAction: Boolean
    ) {
        val startTime = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(action, startTime))
        clock!!.advanceMillis(totalTimeInMs.toLong())
        val spawn: Spawn =
            SimpleSpawn(
                action,  /* arguments= */
                ImmutableList.of<E?>(),  /* environment= */
                ImmutableMap.of<K?, V?>(),  /* executionInfo= */
                ImmutableMap.of<K?, V?>(),
                action.getInputs(),
                action.getOutputs(),
                ResourceSet.ZERO
            )
        computer!!.spawnExecuted(
            SpawnExecutedEvent(
                spawn,
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                spawnResult,
                Instant.now(),  /* spawnIdentifier= */
                "1"
            )
        )
        if (completeAction) {
            computer!!.actionComplete(
                ActionCompletionEvent(
                    startTime,
                    clock!!.nanoTime(),
                    action,
                    FakeActionInputFileCache(),
                    < T > mock < T ? > (OutputMetadataStore::class.java),
                < T > mock < T ? > (ActionLookupData::class.java)))
        }
    }

    @Throws(InterruptedException::class)
    private fun simulateSequentialAndParallelActionExec(
        action: Action, totalTimesInMs: ImmutableList<ImmutableList<Int?>>
    ) {
        val startTime = clock!!.nanoTime()
        for (parallelDuration in totalTimesInMs) {
            for (phaseDuration in parallelDuration) {
                simulateActionExec(action, phaseDuration, phaseDuration, false)
            }
            computer!!.nextCriticalPathPhase(ChangePhase(action))
        }
        computer!!.actionComplete(
            ActionCompletionEvent(
                startTime,
                clock!!.nanoTime(),
                action,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))
    }

    private fun derivedArtifact(path: String?): Artifact {
        val artifact: DerivedArtifact =
            ActionsTestUtil.createArtifactWithExecPath(
                derivedArtifactRoot, PathFragment.create(path)
            ) as DerivedArtifact
        artifact.setGeneratingActionKey(ActionsTestUtil.NULL_ACTION_LOOKUP_DATA)
        return artifact
    }

    private fun artifact(path: String?): Artifact {
        return ActionsTestUtil.createArtifactWithExecPath(artifactRoot, PathFragment.create(path))
    }

    private fun checkCriticalPath(totalWallTimeInMillis: Int, totalWallTimeStr: String?) {
        val criticalPath = computer!!.aggregate()

        Truth.assertThat(criticalPath).isNotNull()
        assertThat(criticalPath.aggregatedElapsedTime)
            .isEqualTo(Duration.ofMillis(totalWallTimeInMillis.toLong()))

        val summary = criticalPath.toStringSummary()
        Truth.assertThat(summary).contains("Critical Path: " + totalWallTimeStr + "s")
    }

    @CanIgnoreReturnValue
    private fun checkCriticalPath(
        totalWallTime: Duration,
        totalTime: Duration,
        totalProcessTime: Duration,
        totalWallTimeStr: String?,
        totalTimePercent: String?,
        totalProcessPercent: String?
    ): AggregatedCriticalPath {
        val criticalPath = computer!!.aggregate()

        Truth.assertThat(criticalPath).isNotNull()
        assertThat(criticalPath.aggregatedElapsedTime)
            .isEqualTo(Duration.ofMillis(totalWallTime.toMillis()))
        assertThat(criticalPath.getSpawnMetrics().getRemoteMetrics().totalTimeInMs())
            .isEqualTo(totalTime.toMillis())
        assertThat(criticalPath.getSpawnMetrics().getRemoteMetrics().executionWallTimeInMs())
            .isEqualTo(totalProcessTime.toMillis())

        val summary = criticalPath.toStringSummary()
        Truth.assertThat(summary).contains("Critical Path: " + totalWallTimeStr + "s")
        Truth.assertThat(summary).contains("Remote (" + totalTimePercent + "% of the time)")
        Truth.assertThat(summary).contains("process: " + totalProcessPercent + "%")

        return criticalPath
    }

    @Test
    @Throws(Exception::class)
    fun testTreeFileDependency() {
        val tree: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(derivedArtifactRoot, "tree")

        // Action A produces the TreeArtifact
        val actionA: MockAction = MockAction(ImmutableList.of<Artifact>(), ImmutableSet.of<Artifact>(tree))

        // Action B depends on a file INSIDE the TreeArtifact
        val child: Artifact.TreeFileArtifact = Artifact.TreeFileArtifact.createTreeOutput(tree, "file.txt")
        val actionB: MockAction =
            MockAction(
                ImmutableList.of<Artifact>(child),
                ImmutableSet.of<E?>(ActionsTestUtil.createArtifact(derivedArtifactRoot, "b.out"))
            )

        // Set up metadata
        child.getPath().getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(child.getPath(), "content")
        val childMetadata: FileArtifactValue? = FileArtifactValue.createForTesting(child)
        val treeMetadata: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(tree).putChild(child, childMetadata).build()
        val cache = FakeActionInputFileCache()
        cache.putTreeArtifact(tree, treeMetadata)

        // Simulate execution
        val startTimeA = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(actionA, startTimeA))
        clock!!.advanceMillis(1000)
        computer!!.actionComplete(
            ActionCompletionEvent(
                startTimeA,
                clock!!.nanoTime(),
                actionA,
                cache,
                null,
                ActionsTestUtil.NULL_ACTION_LOOKUP_DATA
            )
        )

        val startTimeB = clock!!.nanoTime()
        computer!!.actionStarted(ActionStartedEvent(actionB, startTimeB))
        clock!!.advanceMillis(1000)
        computer!!.actionComplete(
            ActionCompletionEvent(
                startTimeB,
                clock!!.nanoTime(),
                actionB,
                cache,
                null,
                ActionsTestUtil.NULL_ACTION_LOOKUP_DATA
            )
        )

        checkCriticalPath(2000, "2.00")
    }

    companion object {
        private fun assertActionMatches(action: Action?, component: CriticalPathComponent) {
            if (!actionMatches(action, component)) {
                Assert.fail("Action " + action + " did not match one in " + component)
            }
        }

        private fun actionMatches(action: Action?, component: CriticalPathComponent): Boolean {
            return component.getAction() === action
        }

        private fun checkTopComponentsTimes(computer: CriticalPathComputer, vararg times: Long) {
            val topComponents: MutableList<CriticalPathComponent> = computer.getSlowestComponents()
            Truth.assertThat(topComponents).hasSize(times.size)

            for (i in times.indices) {
                Truth.assertThat<Duration?>(topComponents.get(i).getElapsedTime())
                    .isEqualTo(Duration.ofMillis(times[i]))
            }
        }

        private fun createSpawnResult(processTimeInMs: Int): SpawnResult.Builder {
            val spawnResult: SpawnResult.Builder = Builder()
            spawnResult.setStatus(SpawnResult.Status.SUCCESS)
            spawnResult.setExitCode(0)
            spawnResult.setWallTimeInMs(processTimeInMs)
            spawnResult.setRunnerName("test")
            return spawnResult
        }

        private fun createSpawnResult(): SpawnResult.Builder {
            return createSpawnResult(0)
        }
    }
}
