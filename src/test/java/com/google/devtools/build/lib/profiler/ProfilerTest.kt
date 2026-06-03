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
package com.google.devtools.build.lib.profiler

import com.google.devtools.build.lib.worker.WorkerProcessMetrics

/** Unit tests for the profiler.  */
@RunWith(JUnit4::class)
class ProfilerTest {
    @org.junit.After
    fun forceStopToAvoidPoisoningTheProfiler() {
        // If a test does not stop the profiler, e.g., due to a test failure, all subsequent tests fail
        // because the profiler is still running, so we force-stop the profiler here.
        try {
            profiler.stop()
            profiler.clear()
        } catch (e: IOException) {
            throw java.lang.RuntimeException(e)
        }
    }

    @Throws(IOException::class)
    private fun start(
        tasks: com.google.common.collect.ImmutableSet<ProfilerTask?>?, format: TraceProfilerService.Format?
    ): java.io.ByteArrayOutputStream {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        profiler.start(
            tasks,
            buffer,
            format,
            "dummy_output_base",
            UUID.randomUUID(),
            false,
            com.google.devtools.build.lib.clock.BlazeClock.instance(),
            com.google.devtools.build.lib.clock.BlazeClock.nanoTime(),  /* slimProfile= */
            false,  /* includePrimaryOutput= */
            false,  /* includeTargetLabel= */
            false,  /* includeConfiguration */
            false,  /* collectTaskHistograms= */
            true
        )
        return buffer
    }

    @Throws(IOException::class)
    private fun startUnbuffered(tasks: com.google.common.collect.ImmutableSet<ProfilerTask?>?) {
        profiler.start(
            tasks,
            null,
            null,
            "dummy_output_base",
            UUID.randomUUID(),
            false,
            com.google.devtools.build.lib.clock.BlazeClock.instance(),
            com.google.devtools.build.lib.clock.BlazeClock.nanoTime(),  /* slimProfile= */
            false,  /* includePrimaryOutput= */
            false,  /* includeTargetLabel= */
            false,  /* includeConfiguration */
            false,  /* collectTaskHistograms= */
            true
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProfilerActivation() {
        Truth.assertThat(profiler.isActive()).isFalse()
        val unused: java.io.ByteArrayOutputStream =
            start(allProfilerTasks, TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT)
        Truth.assertThat(profiler.isActive()).isTrue()

        profiler.stop()
        Truth.assertThat(profiler.isActive()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProfiler() {
        val buffer: java.io.ByteArrayOutputStream =
            start(allProfilerTasks, TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT)
        profiler.logSimpleTask(
            com.google.devtools.build.lib.clock.BlazeClock.instance().nanoTime(),
            ProfilerTask.PHASE,
            "profiler start"
        )
        profiler.profile(ProfilerTask.ACTION, "complex task").use { c ->
            profiler.logEvent(ProfilerTask.PHASE, "event1")
            profiler.profile(ProfilerTask.ACTION_CHECK, "complex subtask").use { c2 ->
                // next task takes less than 10 ms and should be only aggregated
                profiler.logSimpleTask(
                    com.google.devtools.build.lib.clock.BlazeClock.instance().nanoTime(),
                    ProfilerTask.VFS_STAT,
                    "stat1"
                )
                val startTime: Long = com.google.devtools.build.lib.clock.BlazeClock.instance().nanoTime()
                clock.advanceMillis(20)
                // this one will take at least 20 ms and should be present
                profiler.logSimpleTask(startTime, ProfilerTask.VFS_STAT, "stat2")
            }
        }
        profiler.stop()
        // all other calls to profiler should be ignored
        profiler.logEvent(ProfilerTask.PHASE, "should be ignored")

        val jsonProfile: JsonProfile = JsonProfile(ByteArrayInputStream(buffer.toByteArray()))
        val filteredEvents: MutableList<TraceEvent> = removeCounterEvents(jsonProfile.getTraceEvents())
        Truth.assertThat(filteredEvents)
            .hasSize(
                (2 /* thread names */
                        + 2 /* thread indices */
                        + 2 /* build phase marker */
                        + 1 /* VFS event, the first is too short */
                        + 2 /* action + action dependency checking */
                        + 1) /* finishing */
            )

        assertThat(
            jsonProfile.getTraceEvents().stream()
                .filter({ traceEvent -> "thread_name" == traceEvent.name() })
                .collect(Collectors.toList())
        )
            .hasSize(2)

        assertThat(
            jsonProfile.getTraceEvents().stream()
                .filter({ traceEvent -> "thread_sort_index" == traceEvent.name() })
                .collect(Collectors.toList())
        )
            .hasSize(2)

        assertThat(
            jsonProfile.getTraceEvents().stream()
                .filter({ traceEvent -> ProfilerTask.PHASE.description == traceEvent.category() })
                .collect(Collectors.toList())
        )
            .hasSize(2)

        val vfsStat: TraceEvent? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                jsonProfile.getTraceEvents().stream()
                    .filter(
                        { traceEvent -> ProfilerTask.VFS_STAT.description == traceEvent.category() })
                    .collect(Collectors.toList())
            )
        assertThat(vfsStat.duration()).isEqualTo(java.time.Duration.ofMillis(20))

        assertThat(
            jsonProfile.getTraceEvents().stream()
                .filter(
                    { traceEvent -> traceEvent.category() != null && traceEvent.category().startsWith("action") })
                .collect(Collectors.toList())
        )
            .hasSize(2)

        Truth.assertThat(
            com.google.common.collect.Iterables.filter<T?>(
                jsonProfile.getTraceEvents(),
                com.google.common.base.Predicate { t: T? -> t.name().equals("action count") })
        )
            .hasSize(1)

        assertThat(
            jsonProfile.getTraceEvents().stream()
                .filter({ traceEvent -> "Finishing" == traceEvent.name() })
                .collect(Collectors.toList())
        )
            .hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProfilerRecordingAllEvents() {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        profiler.start(
            allProfilerTasks,
            buffer,
            TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT,
            "dummy_output_base",
            UUID.randomUUID(),
            true,
            clock,
            clock.nanoTime(),  /* slimProfile= */
            false,  /* includePrimaryOutput= */
            false,  /* includeTargetLabel= */
            false,  /* includeConfiguration */
            false,  /* collectTaskHistograms= */
            true
        )
        profiler.profile(ProfilerTask.ACTION, "action task").use { c ->
            // Next task takes less than 10 ms but should be recorded anyway.
            val before: Long = clock.nanoTime()
            clock.advanceMillis(1)
            profiler.logSimpleTask(before, ProfilerTask.VFS_STAT, "stat1")
        }
        profiler.stop()

        val jsonProfile: JsonProfile = JsonProfile(ByteArrayInputStream(buffer.toByteArray()))
        val filteredEvents: MutableList<TraceEvent> = removeCounterEvents(jsonProfile.getTraceEvents())
        Truth.assertThat(filteredEvents)
            .hasSize(
                (2 /* thread names */
                        + 2 /* thread sort indices */
                        + 1 /* VFS */
                        + 1 /* action */
                        + 1) /* finishing */
            )

        val vfsStat: TraceEvent? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                jsonProfile.getTraceEvents().stream()
                    .filter(
                        { traceEvent -> ProfilerTask.VFS_STAT.description == traceEvent.category() })
                    .collect(Collectors.toList())
            )
        assertThat(vfsStat.duration().toMillis()).isLessThan(ProfilerTask.VFS_STAT.minDuration)
        // There is only one action count event since we only let the clock run for 1ms.
        Truth.assertThat(
            com.google.common.collect.Iterables.filter<T?>(
                jsonProfile.getTraceEvents(),
                com.google.common.base.Predicate { t: T? -> t.name().equals("action count") })
        )
            .hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProfilerWorkerMetrics() {
        if (com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.LINUX && com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.DARWIN) {
            // We disable the WorkerMemoryUsageCollector on Windows, so we should skip the test if the
            // current OS is not Linux and Darwin.
            return
        }
        val collectionTime: Instant? = com.google.devtools.build.lib.clock.BlazeClock.instance().now()
        val workerMetric1: WorkerProcessMetrics =
            WorkerProcessMetrics( /* workerId= */
                1,  /* processId= */
                1,  /* status= */
                WorkerProcessStatus(),  /* mnemonic= */
                "dummy1",  /* isMultiplex= */
                true,  /* isSandbox= */
                true,  /* workerKeyHash= */
                1
            )
        workerMetric1.addCollectedMetrics(1024, collectionTime)

        val workerMetric2: WorkerProcessMetrics =
            WorkerProcessMetrics( /* workerId= */
                2,  /* processId= */
                2,  /* status= */
                WorkerProcessStatus(),  /* mnemonic= */
                "dummy2",  /* isMultiplex= */
                false,  /* isSandbox= */
                false,  /* workerKeyHash= */
                2
            )
        workerMetric2.addCollectedMetrics(2048, collectionTime)

        val workerMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> =
            com.google.common.collect.ImmutableList.of<WorkerProcessMetrics?>(workerMetric1, workerMetric2)
        val workerProcessMetricsCollector: WorkerProcessMetricsCollector =
            Mockito.mock<WorkerProcessMetricsCollector>(WorkerProcessMetricsCollector::class.java)
        val metricsCollected: CountDownLatch = CountDownLatch(1)
        Mockito.`when`<T?>(workerProcessMetricsCollector.getLiveWorkerProcessMetrics())
            .thenAnswer(
                Answer { unused: InvocationOnMock? ->
                    metricsCollected.countDown()
                    workerMetrics
                })

        val localCollectors: LocalResourceUsageCollectors =
            LocalResourceUsageCollectors(null, null, workerProcessMetricsCollector, null, null)
        localCollectors.addCollectors( /* collectWorkerDataInProfiler= */
            true,  /* collectLoadAverage= */
            false,  /* collectSystemNetworkUsage= */
            false,  /* collectResourceManagerEstimation= */
            false,  /* collectPressureStallIndicators= */
            false,  /* collectSkyframeCounts= */
            false
        )
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        profiler.start(
            allProfilerTasks,
            buffer,
            TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT,
            "dummy_output_base",
            UUID.randomUUID(),
            true,
            clock,
            clock.nanoTime(),  /* slimProfile= */
            false,  /* includePrimaryOutput= */
            false,  /* includeTargetLabel= */
            false,  /* includeConfiguration */
            false,  /* collectTaskHistograms= */
            true
        )
        metricsCollected.await(10, TimeUnit.SECONDS)
        profiler.stop()

        val jsonProfile: JsonProfile = JsonProfile(ByteArrayInputStream(buffer.toByteArray()))
        val totalWorkerMemoryUsageEvents: com.google.common.collect.ImmutableList<TraceEvent>? =
            jsonProfile.getTraceEvents().stream()
                .filter({ e -> e.name().contains("Total worker memory usage") })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        val perMnemonicWorkerMemoryUsageEvents: com.google.common.collect.ImmutableList<TraceEvent>? =
            jsonProfile.getTraceEvents().stream()
                .filter({ e -> e.name().contains("Per-mnemonic worker memory usage") })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())

        Truth.assertThat(totalWorkerMemoryUsageEvents).hasSize(1)
        Truth.assertThat(perMnemonicWorkerMemoryUsageEvents).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProfilerRecordingOnlySlowestEvents() {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()

        profiler.start(
            slowestProfilerTasks,
            buffer,
            TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT,
            "dummy_output_base",
            UUID.randomUUID(),
            true,
            com.google.devtools.build.lib.clock.BlazeClock.instance(),
            com.google.devtools.build.lib.clock.BlazeClock.instance().nanoTime(),  /* slimProfile= */
            false,  /* includePrimaryOutput= */
            false,  /* includeTargetLabel= */
            false,  /* includeConfiguration */
            false,  /* collectTaskHistograms= */
            true
        )
        profiler.logSimpleTask(10000, 20000, ProfilerTask.VFS_STAT, "stat")
        // Unlike the VFS_STAT event above, the remote execution event will not be recorded since we
        // don't record the slowest remote exec events (see ProfilerTask.java).
        profiler.logSimpleTask(20000, 30000, ProfilerTask.REMOTE_EXECUTION, "remote execution")

        Truth.assertThat(profiler.isProfiling(ProfilerTask.VFS_STAT)).isTrue()
        Truth.assertThat(profiler.isProfiling(ProfilerTask.REMOTE_EXECUTION)).isFalse()

        profiler.stop()

        val jsonProfile: JsonProfile = JsonProfile(ByteArrayInputStream(buffer.toByteArray()))
        val filteredEvents: MutableList<TraceEvent> = removeCounterEvents(jsonProfile.getTraceEvents())
        Truth.assertThat(filteredEvents).hasSize(2 /*threads */ + 2 /*threads sort index */ + 1 /*VFS */)

        Truth.assertThat(
            filteredEvents.stream()
                .filter { traceEvent: TraceEvent -> "thread_name" != traceEvent.name() && "thread_sort_index" != traceEvent.name() }
                .collect(Collectors.toList()))
            .hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSlowestTasks() {
        startUnbuffered(allProfilerTasks)
        profiler.logSimpleTaskDuration(
            profiler.nanoTimeMaybe(), java.time.Duration.ofSeconds(10), ProfilerTask.LOCAL_PARSE, "foo"
        )
        val slowestTasks: Iterable<SlowTask>? = profiler.getSlowestTasks()
        Truth.assertThat(slowestTasks).hasSize(1)
        val task: SlowTask = slowestTasks!!.iterator().next()
        Truth.assertThat<ProfilerTask?>(task.type()).isEqualTo(ProfilerTask.LOCAL_PARSE)
        profiler.stop()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetSlowestTasksCapped() {
        startUnbuffered(slowestProfilerTasks)

        // Add some fast tasks - these shouldn't show up in the slowest.
        for (i in 0..29) {
            profiler.logSimpleTask( /* startTimeNanos= */
                1,  /* stopTimeNanos= */
                ProfilerTask.VFS_STAT.minDuration + 10,
                ProfilerTask.VFS_STAT,
                "stat"
            )
        }

        // Add some slow tasks we expect to show up in the slowest.
        val expectedSlowestDurations: MutableList<Long?> = java.util.ArrayList<Long?>()
        for (i in 0..29) {
            val fakeDuration: Long = ProfilerTask.VFS_STAT.minDuration + i + 10000
            profiler.logSimpleTask( /* startTimeNanos= */
                1,  /* stopTimeNanos= */
                fakeDuration + 1,
                ProfilerTask.VFS_STAT,
                "stat"
            )
            expectedSlowestDurations.add(fakeDuration)
        }

        // Sprinkle in a whole bunch of fast tasks from different thread ids - necessary because
        // internally aggregation is sharded across several aggregators, sharded by thread id.
        // It's possible all these threads wind up in the same shard, we'll take our chances.
        val threadsBuilder: com.google.common.collect.ImmutableList.Builder<java.lang.Thread?> =
            com.google.common.collect.ImmutableList.builder<java.lang.Thread?>()
        try {
            for (i in 0..31) {
                val thread: java.lang.Thread =
                    java.lang.Thread(
                        java.lang.Runnable {
                            for (j in 0..99) {
                                profiler.logSimpleTask( /* startTimeNanos= */
                                    1,  /* stopTimeNanos= */
                                    ProfilerTask.VFS_STAT.minDuration + j + 1,
                                    ProfilerTask.VFS_STAT,
                                    "stat"
                                )
                            }
                        })
                threadsBuilder.add(thread)
                thread.start()
            }
        } finally {
            threadsBuilder
                .build()
                .forEach(
                    java.util.function.Consumer { t: java.lang.Thread? ->
                        try {
                            t.join(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                        } catch (e: java.lang.InterruptedException) {
                            t.interrupt()
                            // This'll go ahead and interrupt all the others. The thread we just interrupted
                            // is
                            // lightweight enough that it's reasonable to assume it'll exit.
                            java.lang.Thread.currentThread().interrupt()
                        }
                    })
        }

        val slowTasks: com.google.common.collect.ImmutableList<SlowTask> =
            com.google.common.collect.ImmutableList.copyOf<SlowTask?>(
                profiler.getSlowestTasks()
            )
        Truth.assertThat(slowTasks).hasSize(30)

        val slowestDurations: com.google.common.collect.ImmutableList<Long> =
            slowTasks.stream().map<Long?> { obj: SlowTask? -> obj.durationNanos() }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Long?>())
        Truth.assertThat(slowestDurations).containsExactlyElementsIn(expectedSlowestDurations)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProfilerRecordsNothing() {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        profiler.start(
            com.google.common.collect.ImmutableSet.of<ProfilerTask?>(),
            buffer,
            TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT,
            "dummy_output_base",
            UUID.randomUUID(),
            true,
            com.google.devtools.build.lib.clock.BlazeClock.instance(),
            com.google.devtools.build.lib.clock.BlazeClock.instance().nanoTime(),  /* slimProfile= */
            false,  /* includePrimaryOutput= */
            false,  /* includeTargetLabel= */
            false,  /* includeConfiguration */
            false,  /* collectTaskHistograms= */
            true
        )
        profiler.logSimpleTask(10000, 20000, ProfilerTask.VFS_STAT, "stat")

        Truth.assertThat(ProfilerTask.VFS_STAT.collectsSlowestInstances()).isTrue()
        Truth.assertThat(profiler.isProfiling(ProfilerTask.VFS_STAT)).isFalse()

        profiler.stop()

        val jsonProfile: JsonProfile = JsonProfile(ByteArrayInputStream(buffer.toByteArray()))

        // Filter out thread metadata and counter data and make sure that the remaining list is empty,
        // i.e. there is no individual task recorded.
        val traceEvents: MutableList<TraceEvent>? =
            jsonProfile.getTraceEvents().stream()
                .filter({ traceEvent -> "M" != traceEvent.type() && "C" != traceEvent.type() })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        Truth.assertThat(traceEvents).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConcurrentProfiling() {
        val buffer: java.io.ByteArrayOutputStream =
            start(allProfilerTasks, TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT)

        val thread1: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    for (i in 0..9999) {
                        com.google.devtools.build.lib.profiler.Profiler.instance()
                            .logEvent(ProfilerTask.INFO, "thread1")
                    }
                })
        val thread2: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    for (i in 0..9999) {
                        com.google.devtools.build.lib.profiler.Profiler.instance()
                            .logEvent(ProfilerTask.INFO, "thread2")
                    }
                })

        profiler.profile(ProfilerTask.PHASE, "main task").use { c ->
            profiler.logEvent(ProfilerTask.INFO, "starting threads")
            thread1.start()
            thread2.start()
            thread2.join()
            thread1.join()
            profiler.logEvent(ProfilerTask.INFO, "joined")
        }
        profiler.stop()

        val jsonProfile: JsonProfile = JsonProfile(ByteArrayInputStream(buffer.toByteArray()))
        Truth.assertThat(removeCounterEvents(jsonProfile.getTraceEvents()))
            .hasSize(
                (4 /* thread names */
                        + 4 /* thread indices */
                        + 1 /* main task phase marker */
                        + 2 /* starting, joining events */
                        + 2 * 10000 /* thread1/thread2 events */ + 1) /* finishing */
            )

        val tid1: Long =
            jsonProfile.getTraceEvents().stream()
                .filter({ traceEvent -> "thread1" == traceEvent.name() })
                .map(TraceEvent::threadId)
                .distinct()
                .collect(com.google.common.collect.MoreCollectors.onlyElement<T?>())
        val tid2: Long =
            jsonProfile.getTraceEvents().stream()
                .filter({ traceEvent -> "thread2" == traceEvent.name() })
                .map(TraceEvent::threadId)
                .distinct()
                .collect(com.google.common.collect.MoreCollectors.onlyElement<T?>())
        Truth.assertThat(tid1).isNotEqualTo(tid2)
        Truth.assertThat(tid1).isEqualTo(thread1.getId())
        Truth.assertThat(tid2).isEqualTo(thread2.getId())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPhaseTasks() {
        val buffer: java.io.ByteArrayOutputStream =
            start(allProfilerTasks, TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT)
        val thread1: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    for (i in 0..99) {
                        com.google.devtools.build.lib.profiler.Profiler.instance()
                            .logEvent(ProfilerTask.INFO, "thread1")
                    }
                })
        profiler.markPhase(ProfilePhase.INIT) // Empty phase.
        profiler.markPhase(ProfilePhase.TARGET_PATTERN_EVAL)
        thread1.start()
        thread1.join()
        clock.advanceMillis(1)
        profiler.markPhase(ProfilePhase.ANALYZE)
        val thread2: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    profiler.profile(ProfilerTask.INFO, "complex task").use { c ->
                        for (i in 0..99) {
                            com.google.devtools.build.lib.profiler.Profiler.instance()
                                .logEvent(ProfilerTask.INFO, "thread2a")
                        }
                    }
                    try {
                        profiler.markPhase(ProfilePhase.EXECUTE)
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.IllegalStateException(e)
                    }
                    for (i in 0..99) {
                        com.google.devtools.build.lib.profiler.Profiler.instance()
                            .logEvent(ProfilerTask.INFO, "thread2b")
                    }
                })
        thread2.start()
        thread2.join()
        profiler.logEvent(ProfilerTask.INFO, "last task")
        clock.advanceMillis(1)
        profiler.stop()

        val jsonProfile: JsonProfile = JsonProfile(ByteArrayInputStream(buffer.toByteArray()))
        val filteredEvents: MutableList<TraceEvent> = removeCounterEvents(jsonProfile.getTraceEvents())
        Truth.assertThat(filteredEvents)
            .hasSize(
                (4 /* thread names */
                        + 4 /* threads sort index */
                        + 4 /* build phase marker */
                        + 3 * 100 /* thread1, thread2a, thread2b */ + 1 /* complex task */
                        + 1 /* last task */
                        + 1) /* finishing */
            )
        Truth.assertThat(getTraceEventsForPhase(ProfilePhase.INIT, filteredEvents)).isEmpty()
        Truth.assertThat(getTraceEventsForPhase(ProfilePhase.TARGET_PATTERN_EVAL, filteredEvents))
            .hasSize(100) // thread1
        Truth.assertThat(getTraceEventsForPhase(ProfilePhase.ANALYZE, filteredEvents))
            .hasSize(101) // complex task and thread2a
        Truth.assertThat(getTraceEventsForPhase(ProfilePhase.EXECUTE, filteredEvents))
            .hasSize(102) // thread2b + last task + finishing
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResilenceToNonDecreasingNanoTimes() {
        val initialNanoTime: Long = com.google.devtools.build.lib.clock.BlazeClock.instance().nanoTime()
        val numNanoTimeCalls: AtomicInteger = AtomicInteger(0)
        val badClock: com.google.devtools.build.lib.clock.Clock =
            object : com.google.devtools.build.lib.clock.Clock {
                override fun currentTimeMillis(): Long {
                    return com.google.devtools.build.lib.clock.BlazeClock.instance().currentTimeMillis()
                }

                override fun nanoTime(): Long {
                    return initialNanoTime - numNanoTimeCalls.addAndGet(1)
                }
            }
        profiler.start(
            allProfilerTasks,
            java.io.ByteArrayOutputStream(),
            TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT,
            "dummy_output_base",
            UUID.randomUUID(),
            false,
            badClock,
            initialNanoTime,  /* slimProfile= */
            false,  /* includePrimaryOutput= */
            false,  /* includeTargetLabel= */
            false,  /* includeConfiguration */
            false,  /* collectTaskHistograms= */
            true
        )
        profiler.logSimpleTask(badClock.nanoTime(), ProfilerTask.INFO, "some task")
        profiler.stop()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTaskHistograms() {
        startUnbuffered(allProfilerTasks)
        profiler.logSimpleTaskDuration(
            profiler.nanoTimeMaybe(), java.time.Duration.ofSeconds(10), ProfilerTask.INFO, "foo"
        )
        val histograms: MutableList<StatRecorder> = profiler.getTasksHistograms()
        val infoStatRecorder: StatRecorder = histograms.get(ProfilerTask.INFO.ordinal)
        Truth.assertThat(infoStatRecorder.isEmpty()).isFalse()
        // This is the only provided API to get the contents of the StatRecorder.
        Truth.assertThat(infoStatRecorder.toString()).contains("'INFO'")
        Truth.assertThat(infoStatRecorder.toString()).contains("Count: 1")
        Truth.assertThat(infoStatRecorder.toString()).contains("[8192..16384 ms]")
        // The stop() call is here because the histograms are cleared in the stop call. See the
        // documentation of {@link Profiler#getTasksHistograms}.
        profiler.stop()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIOExceptionInOutputStreamBinaryFormat() {
        val failingOutputStream: java.io.OutputStream =
            object : java.io.OutputStream() {
                @Throws(IOException::class)
                override fun write(b: Int) {
                    throw IOException("Expected failure.")
                }
            }
        profiler.start(
            allProfilerTasks,
            failingOutputStream,
            TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT,
            "dummy_output_base",
            UUID.randomUUID(),
            false,
            com.google.devtools.build.lib.clock.BlazeClock.instance(),
            com.google.devtools.build.lib.clock.BlazeClock.instance().nanoTime(),  /* slimProfile= */
            false,  /* includePrimaryOutput= */
            false,  /* includeTargetLabel= */
            false,  /* includeConfiguration */
            false,  /* collectTaskHistograms= */
            true
        )
        profiler.logSimpleTaskDuration(
            profiler.nanoTimeMaybe(), java.time.Duration.ofSeconds(10), ProfilerTask.INFO, "foo"
        )
        val expected: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { profiler.stop() })
        Truth.assertThat(expected).hasMessageThat().isEqualTo("Expected failure.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIOExceptionInOutputStreamJsonFormat() {
        val failingOutputStream: java.io.OutputStream =
            object : java.io.OutputStream() {
                @Throws(IOException::class)
                override fun write(b: Int) {
                    throw IOException("Expected failure.")
                }
            }
        profiler.start(
            allProfilerTasks,
            failingOutputStream,
            TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT,
            "dummy_output_base",
            UUID.randomUUID(),
            false,
            com.google.devtools.build.lib.clock.BlazeClock.instance(),
            com.google.devtools.build.lib.clock.BlazeClock.instance().nanoTime(),  /* slimProfile= */
            false,  /* includePrimaryOutput= */
            false,  /* includeTargetLabel= */
            false,  /* includeConfiguration */
            false,  /* collectTaskHistograms= */
            true
        )
        profiler.logSimpleTaskDuration(
            profiler.nanoTimeMaybe(), java.time.Duration.ofSeconds(10), ProfilerTask.INFO, "foo"
        )
        val expected: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { profiler.stop() })
        Truth.assertThat(expected).hasMessageThat().isEqualTo("Expected failure.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrimaryOutputForAction() {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()

        profiler.start(
            allProfilerTasks,
            buffer,
            TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT,
            "dummy_output_base",
            UUID.randomUUID(),
            true,
            clock,
            clock.nanoTime(),  /* slimProfile= */
            false,  /* includePrimaryOutput= */
            true,  /* includeTargetLabel= */
            false,  /* includeConfiguration */
            false,  /* collectTaskHistograms= */
            true
        )
        profiler.profileAction(
            ProfilerTask.ACTION,  /* mnemonic */
            null,
            "test",
            "foo.out",
            "",  /* configuration */
            null
        ).use { c ->
            profiler.logEvent(ProfilerTask.PHASE, "event1")
        }
        profiler.stop()

        val jsonProfile: JsonProfile = JsonProfile(ByteArrayInputStream(buffer.toByteArray()))

        assertThat(
            jsonProfile.getTraceEvents().stream()
                .filter({ traceEvent -> "foo.out" == traceEvent.primaryOutputPath() })
                .collect(Collectors.toList())
        )
            .hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetLabelForAction() {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()

        profiler.start(
            allProfilerTasks,
            buffer,
            TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT,
            "dummy_output_base",
            UUID.randomUUID(),
            true,
            clock,
            clock.nanoTime(),  /* slimProfile= */
            false,  /* includePrimaryOutput= */
            false,  /* includeTargetLabel= */
            true,  /* includeConfiguration */
            false,  /* collectTaskHistograms= */
            true
        )
        profiler.profileAction(
            ProfilerTask.ACTION,  /* mnemonic */
            null,
            "test",
            "foo.out",
            "//foo:bar",  /* configuration */
            null
        ).use { c ->
            profiler.logEvent(ProfilerTask.PHASE, "event1")
        }
        profiler.stop()

        val jsonProfile: JsonProfile = JsonProfile(ByteArrayInputStream(buffer.toByteArray()))

        assertThat(
            jsonProfile.getTraceEvents().stream()
                .filter({ traceEvent -> "//foo:bar" == traceEvent.targetLabel() })
                .collect(Collectors.toList())
        )
            .hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetConfigurationForAction() {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()

        profiler.start(
            allProfilerTasks,
            buffer,
            TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT,
            "dummy_output_base",
            UUID.randomUUID(),
            true,
            clock,
            clock.nanoTime(),  /* slimProfile= */
            false,  /* includePrimaryOutput= */
            false,  /* includeTargetLabel= */
            false,  /* includeConfiguration */
            true,  /* collectTaskHistograms= */
            true
        )
        profiler.profileAction(
            ProfilerTask.ACTION,  /* mnemonic */null, "test", "foo.out", "//foo:bar", "012345"
        ).use { c ->
            profiler.logEvent(ProfilerTask.PHASE, "event1")
        }
        profiler.stop()

        val jsonProfile: JsonProfile = JsonProfile(ByteArrayInputStream(buffer.toByteArray()))

        assertThat(
            jsonProfile.getTraceEvents().stream()
                .filter({ traceEvent -> "012345" == traceEvent.configuration() })
                .collect(Collectors.toList())
        )
            .hasSize(1)
    }

    @Throws(IOException::class)
    private fun getJsonProfileOutputStream(slimProfile: Boolean): java.io.ByteArrayOutputStream {
        val outputStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        profiler.start(
            allProfilerTasks,
            outputStream,
            TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT,
            "dummy_output_base",
            UUID.randomUUID(),
            false,
            com.google.devtools.build.lib.clock.BlazeClock.instance(),
            com.google.devtools.build.lib.clock.BlazeClock.instance().nanoTime(),
            slimProfile,  /* includePrimaryOutput= */
            false,  /* includeTargetLabel= */
            false,  /* includeConfiguration */
            false,  /* collectTaskHistograms= */
            true
        )
        var curTime: Long = profiler.nanoTimeMaybe()
        for (i in 0..100000 - 1) {
            val duration: java.time.Duration
            if (i % 100 == 0) {
                duration = java.time.Duration.ofSeconds(1)
            } else {
                duration = java.time.Duration.ofMillis((i % 250).toLong())
            }
            profiler.logSimpleTaskDuration(curTime, duration, ProfilerTask.INFO, "foo")
            curTime += duration.toNanos()
        }
        profiler.stop()
        return outputStream
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSlimProfileSize() {
        val fatOutputStream: java.io.ByteArrayOutputStream = getJsonProfileOutputStream( /* slimProfile= */false)
        val fatOutput = fatOutputStream.toString()
        Truth.assertThat(fatOutput).doesNotContain("x foo")

        val slimOutputStream: java.io.ByteArrayOutputStream = getJsonProfileOutputStream( /* slimProfile= */true)
        val slimOutput = slimOutputStream.toString()
        Truth.assertThat(slimOutput).contains("x foo")

        val fatProfileLen: Long = fatOutputStream.size().toLong()
        val slimProfileLen: Long = slimOutputStream.size().toLong()
        Truth.assertThat(fatProfileLen).isAtLeast(8 * slimProfileLen)

        val fatProfileLineCount: Long =
            fatOutput.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray().size.toLong()
        val slimProfileLineCount: Long =
            slimOutput.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray().size.toLong()
        Truth.assertThat(fatProfileLineCount).isAtLeast(8 * slimProfileLineCount)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProfileMnemonicIncluded() {
        val buffer: java.io.ByteArrayOutputStream =
            start(allProfilerTasks, TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT)
        profiler.profileAction(
            ProfilerTask.ACTION,  /* mnemonic */
            null,
            "without mnemonic",
            "",
            "",  /* configuration */
            null
        ).use { c ->
            clock.advanceMillis(100)
        }
        profiler.profileAction(
            ProfilerTask.ACTION, "foo", "with mnemonic", "", "",  /* configuration */null
        ).use { c ->
            clock.advanceMillis(100)
        }
        profiler.stop()

        // Make sure both actions were registered
        val jsonProfile: JsonProfile = JsonProfile(ByteArrayInputStream(buffer.toByteArray()))
        val traceEvents: MutableList<TraceEvent> = jsonProfile.getTraceEvents()
        val names: MutableList<String> = traceEvents.stream().map<Any?>(TraceEvent::name).collect(Collectors.toList())
        Truth.assertThat(names).contains("without mnemonic")
        Truth.assertThat(names).contains("with mnemonic")

        var withoutMnemonic: TraceEvent? = null
        var withMnemonic: TraceEvent? = null
        for (traceEvent in traceEvents) {
            val name: String = traceEvent.name()
            if (name == "without mnemonic") {
                withoutMnemonic = traceEvent
            } else if (name == "with mnemonic") {
                withMnemonic = traceEvent
            }
        }

        // Make sure both events were profiled
        assertThat(withoutMnemonic).isNotNull()
        assertThat(withMnemonic).isNotNull()

        // Make sure that one has been assigned a mnemonics field in args and the other hasn't
        val mnemonicWithoutMnemonic: String? = withoutMnemonic.mnemonic()
        val mnemonicWithMnemonic: String? = withMnemonic.mnemonic()
        Truth.assertThat(mnemonicWithoutMnemonic).isNull()
        Truth.assertThat(mnemonicWithMnemonic).isNotNull()

        // Make sure the mnemonic has been set to the specified value
        Truth.assertThat(mnemonicWithMnemonic).isEqualTo("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionCacheHitsCounted() {
        val buffer: java.io.ByteArrayOutputStream =
            start(allProfilerTasks, TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT)
        profiler.profileAction(
            ProfilerTask.ACTION_CHECK,
            "bar action",
            "with mnemonic",
            "",
            "",  /* configuration */
            null
        ).use { c ->
            profiler.profileAction(
                ProfilerTask.ACTION,  /* mnemonic */
                null,
                "foo action",
                "",
                "",  /* configuration */
                null
            ).use { c2 ->
                profiler.profileAction(
                    ProfilerTask.ACTION_CHECK,  /* mnemonic */
                    null,
                    "bar action",
                    "",
                    "",  /* configuration */
                    null
                ).use { c3 ->
                    clock.advanceMillis(200)
                }
            }
            clock.advanceMillis(100)
        }
        profiler.stop()

        val jsonProfile: JsonProfile = JsonProfile(ByteArrayInputStream(buffer.toByteArray()))
        val actionCountEvents: Array<Any?> =
            jsonProfile.getTraceEvents().stream()
                .filter({ e -> "action count" == e.name() })
                .toArray()

        Truth.assertThat<Any?>(actionCountEvents).hasLength(2)

        val first: TraceEvent = actionCountEvents[0] as TraceEvent
        assertThat(first.processId()).isEqualTo(CounterSeriesTraceData.PROCESS_ID)
        assertThat(first.threadId()).isEqualTo(java.lang.Thread.currentThread().getId())
        // Two cache hit checks and one executed action.
        assertThat(first.args()).containsExactly("action", 1.0, "local action cache", 2.0)

        val second: TraceEvent = actionCountEvents[1] as TraceEvent
        assertThat(first.processId()).isEqualTo(CounterSeriesTraceData.PROCESS_ID)
        assertThat(first.threadId()).isEqualTo(java.lang.Thread.currentThread().getId())
        // One of the cache hit checks spilled over and used half of the second bucket.
        assertThat(second.args()).containsExactly("action", 0.0, "local action cache", 0.5)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLocalActionsCounted() {
        // Given 2 subsequent local actions with length of 200 ms and 100ms
        val buffer: java.io.ByteArrayOutputStream =
            start(allProfilerTasks, TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT)

        val startTime1: Long = clock.nanoTime()
        clock.advanceMillis(200)
        profiler.completeTask(startTime1, ProfilerTask.LOCAL_ACTION_COUNTS, "resource usage 1")
        val startTime2: Long = clock.nanoTime()
        clock.advanceMillis(100)
        profiler.completeTask(startTime2, ProfilerTask.LOCAL_ACTION_COUNTS, "resource usage 2")

        // When profiler builded the profile.
        profiler.stop()

        // Then find 2 records of local actions of length 1 and 0.5 (because size of window is 200 ms)
        val jsonProfile: JsonProfile = JsonProfile(ByteArrayInputStream(buffer.toByteArray()))
        val actionCountEvents: Array<Any?> =
            jsonProfile.getTraceEvents().stream()
                .filter({ e -> e.name().equals("action count (local)") })
                .toArray()

        Truth.assertThat<Any?>(actionCountEvents).hasLength(2)

        val first: TraceEvent = actionCountEvents[0] as TraceEvent
        assertThat(first.processId()).isEqualTo(CounterSeriesTraceData.PROCESS_ID)
        assertThat(first.threadId()).isEqualTo(java.lang.Thread.currentThread().getId())
        assertThat(first.args()).containsExactly("local action", 1.0)

        val second: TraceEvent = actionCountEvents[1] as TraceEvent
        assertThat(first.processId()).isEqualTo(CounterSeriesTraceData.PROCESS_ID)
        assertThat(first.threadId()).isEqualTo(java.lang.Thread.currentThread().getId())
        assertThat(second.args()).containsExactly("local action", 0.5)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVirtualThread() {
        val buffer: java.io.ByteArrayOutputStream =
            start(allProfilerTasks, TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT)

        val threadFactory1: ThreadFactory = java.lang.Thread.ofVirtual().name("foo-", 0).factory()
        val threadFactory2: ThreadFactory = java.lang.Thread.ofVirtual().name("bar-", 0).factory()
        Executors.newThreadPerTaskExecutor(threadFactory1).use { executor1 ->
            Executors.newThreadPerTaskExecutor(threadFactory2).use { executor2 ->
                executor1
                    .submit(
                        java.lang.Runnable {
                            profiler.profile(ProfilerTask.PHASE, "virtual task 1").use { c ->
                                clock.advanceMillis(100)
                            }
                        })
                    .get()
                executor2
                    .submit(
                        java.lang.Runnable {
                            profiler.profile(ProfilerTask.PHASE, "virtual task 2").use { c ->
                                clock.advanceMillis(100)
                            }
                        })
                    .get()
                executor1
                    .submit(
                        java.lang.Runnable {
                            profiler.profile(ProfilerTask.PHASE, "virtual task 3").use { c ->
                                clock.advanceMillis(100)
                            }
                        })
                    .get()
                executor2
                    .submit(
                        java.lang.Runnable {
                            profiler.profile(ProfilerTask.PHASE, "virtual task 4").use { c ->
                                clock.advanceMillis(100)
                            }
                        })
                    .get()
            }
        }
        profiler.stop()

        val jsonProfile: JsonProfile = JsonProfile(ByteArrayInputStream(buffer.toByteArray()))
        val events: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            jsonProfile.getTraceEvents().stream()
                .filter({ e -> ProfilerTask.PHASE.description == e.category() })
                .toArray()

        assertThat(events).hasLength(4)

        val first: TraceEvent = events[0] as TraceEvent
        assertThat(first.processId()).isEqualTo(CounterSeriesTraceData.PROCESS_ID)
        assertThat(first.name()).isEqualTo("virtual task 1")

        val second: TraceEvent = events[1] as TraceEvent
        assertThat(first.processId()).isEqualTo(CounterSeriesTraceData.PROCESS_ID)
        assertThat(second.name()).isEqualTo("virtual task 2")

        val third: TraceEvent = events[2] as TraceEvent
        assertThat(first.processId()).isEqualTo(CounterSeriesTraceData.PROCESS_ID)
        assertThat(third.name()).isEqualTo("virtual task 3")

        val fourth: TraceEvent = events[3] as TraceEvent
        assertThat(first.processId()).isEqualTo(CounterSeriesTraceData.PROCESS_ID)
        assertThat(fourth.name()).isEqualTo("virtual task 4")

        assertThat(first.threadId()).isEqualTo(third.threadId())
        assertThat(second.threadId()).isEqualTo(fourth.threadId())
        assertThat(first.threadId()).isNotEqualTo(second.threadId())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVirtualThreadTaskStartedAfterStop() {
        val buffer: java.io.ByteArrayOutputStream =
            start(allProfilerTasks, TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT)
        profiler.stop()

        val threadFactory: ThreadFactory = java.lang.Thread.ofVirtual().name("foo-", 0).factory()
        Executors.newThreadPerTaskExecutor(threadFactory).use { executor ->
            executor
                .submit(
                    java.lang.Runnable {
                        profiler.profile(ProfilerTask.PHASE, "virtual task 1").use { c ->
                            clock.advanceMillis(100)
                        }
                    })
                .get()
        }
        val jsonProfile: JsonProfile = JsonProfile(ByteArrayInputStream(buffer.toByteArray()))
        val events: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            jsonProfile.getTraceEvents().stream()
                .filter({ e -> ProfilerTask.PHASE.description == e.category() })
                .toArray()

        assertThat(events).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVirtualThreadTaskEndedAfterStop() {
        val buffer: java.io.ByteArrayOutputStream =
            start(allProfilerTasks, TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT)

        val profilerStoppedLatch: CountDownLatch = CountDownLatch(1)
        val threadFactory: ThreadFactory = java.lang.Thread.ofVirtual().name("foo-", 0).factory()
        Executors.newThreadPerTaskExecutor(threadFactory).use { executor ->
            val future: java.util.concurrent.Future<*> =
                executor.submit(
                    java.lang.Runnable {
                        profiler.profile(ProfilerTask.PHASE, "virtual task 1").use { c ->
                            try {
                                profilerStoppedLatch.await()
                            } catch (e: java.lang.InterruptedException) {
                                throw java.lang.IllegalStateException(e)
                            }
                        }
                    })
            profiler.stop()
            profilerStoppedLatch.countDown()
            future.get()
        }
        val jsonProfile: JsonProfile = JsonProfile(ByteArrayInputStream(buffer.toByteArray()))
        val events: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            jsonProfile.getTraceEvents().stream()
                .filter({ e -> ProfilerTask.PHASE.description == e.category() })
                .toArray()

        assertThat(events).isEmpty()
    }

    companion object {
        private val profiler: com.google.devtools.build.lib.profiler.Profiler =
            com.google.devtools.build.lib.profiler.Profiler.instance()
        private val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()

        @BeforeClass
        fun setUp() {
            com.google.devtools.build.lib.profiler.Profiler.setTraceProfilerService(TraceProfilerServiceImpl())
            com.google.devtools.build.lib.clock.BlazeClock.setClock(clock)
        }

        @AfterClass
        fun tearDownClass() {
            com.google.devtools.build.lib.clock.BlazeClock.setClock(com.google.devtools.build.lib.clock.JavaClock())
        }

        private val allProfilerTasks: com.google.common.collect.ImmutableSet<ProfilerTask?>
            get() = com.google.common.collect.ImmutableSet.copyOf<ProfilerTask?>(ProfilerTask.entries.toTypedArray())

        private val slowestProfilerTasks: com.google.common.collect.ImmutableSet<ProfilerTask?>
            get() {
                val profiledTasksBuilder: com.google.common.collect.ImmutableSet.Builder<ProfilerTask?> =
                    com.google.common.collect.ImmutableSet.builder<ProfilerTask?>()
                for (profilerTask in ProfilerTask.entries) {
                    if (profilerTask.collectsSlowestInstances()) {
                        profiledTasksBuilder.add(profilerTask)
                    }
                }
                return profiledTasksBuilder.build()
            }

        // Filter out counter series events such as CPU usage/memory usage/load average events. These are
        // non-deterministic depending on the duration of the profile.
        private fun removeCounterEvents(events: MutableList<TraceEvent?>): com.google.common.collect.ImmutableList<TraceEvent> {
            return events.stream().filter { e: TraceEvent? -> "C" != e.type() }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<TraceEvent?>())
        }

        /**
         * Extracts all events for a given phase.
         * 
         * 
         * Excludes thread_name and thread_sort_index events.
         */
        private fun getTraceEventsForPhase(
            phase: ProfilePhase, traceEvents: MutableList<TraceEvent>
        ): MutableList<TraceEvent> {
            val filteredEvents: MutableList<TraceEvent> = java.util.ArrayList<TraceEvent>()
            var foundPhase = false
            for (traceEvent in traceEvents) {
                if (ProfilerTask.PHASE.description == traceEvent.category()) {
                    if (foundPhase) {
                        break
                    } else if (phase.description == traceEvent.name()) {
                        foundPhase = true
                        continue
                    }
                }
                if (foundPhase
                    && ("thread_name" != traceEvent.name()) && ("thread_sort_index" != traceEvent.name())
                ) {
                    filteredEvents.add(traceEvent)
                }
            }
            return filteredEvents
        }
    }
}
