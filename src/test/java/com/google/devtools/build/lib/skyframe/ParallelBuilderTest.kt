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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.Action

/**
 * Test suite for ParallelBuilder.
 * 
 */
@RunWith(JUnit4::class)
open class ParallelBuilderTest : TimestampBuilderTestCase() {
    protected var cache: ActionCache? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        this.cache = InMemoryActionCache()
    }

    @Throws(java.lang.Exception::class)
    protected fun buildArtifacts(vararg artifacts: Artifact) {
        buildArtifacts(createBuilder(DEFAULT_NUM_JOBS, false), *artifacts)
    }

    @Throws(java.lang.Exception::class)
    private fun createBuilder(jobs: Int, keepGoing: Boolean): Builder? {
        return createBuilder(cache, jobs, keepGoing)
    }

    @kotlin.concurrent.Volatile
    private var runningFooAction = false

    @kotlin.concurrent.Volatile
    private var runningBarAction = false

    /**
     * Test that independent actions are run in parallel threads
     * that are scheduled concurrently.
     */
    @Throws(java.lang.Exception::class)
    fun runsInParallelWithBuilder(builder: Builder?) {
        // We create two actions, each of which waits (spinning) until the
        // other action has started.  If the two actions are not run
        // in parallel, the test will deadlock and time out.

        // This specifies how many iterations to run before timing out.
        // This should be large enough to ensure that that there is at
        // least one context switch, otherwise the test may spuriously fail.

        val maxIterations: Long = 100000000

        // This specifies how often to print out progress messages.
        // Uncomment this for debugging.
        //final long PRINT_FREQUENCY = maxIterations / 10;
        runningFooAction = false
        runningBarAction = false

        // [action] -> foo
        val foo: Artifact = createDerivedArtifact("foo")
        val makeFoo: java.lang.Runnable = object : java.lang.Runnable {
            override fun run() {
                runningFooAction = true
                for (i in 0..<maxIterations) {
                    java.lang.Thread.yield()
                    if (runningBarAction) {
                        return
                    }
                    // Uncomment this for debugging.
                    //if (i % PRINT_FREQUENCY == 0) {
                    //  String msg = "ParallelBuilderTest: foo: waiting for bar";
                    //  System.out.println(bar);
                    //}
                }
                org.junit.Assert.fail("ParallelBuilderTest: foo: waiting for bar: timed out")
            }
        }
        registerAction<T?>(
            TestAction(
                makeFoo,
                TimestampBuilderTestCase.emptyNestedSet,
                com.google.common.collect.ImmutableSet.of<Artifact>(foo)
            )
        )

        // [action] -> bar
        val bar: Artifact = createDerivedArtifact("bar")
        val makeBar: java.lang.Runnable = object : java.lang.Runnable {
            override fun run() {
                runningBarAction = true
                for (i in 0..<maxIterations) {
                    java.lang.Thread.yield()
                    if (runningFooAction) {
                        return
                    }
                    // Uncomment this for debugging.
                    //if (i % PRINT_FREQUENCY == 0) {
                    //  String msg = "ParallelBuilderTest: bar: waiting for foo";
                    //  System.out.println(msg);
                    //}
                }
                org.junit.Assert.fail("ParallelBuilderTest: bar: waiting for foo: timed out")
            }
        }
        registerAction<T?>(
            TestAction(
                makeBar,
                TimestampBuilderTestCase.emptyNestedSet,
                com.google.common.collect.ImmutableSet.of<Artifact>(bar)
            )
        )

        buildArtifacts(builder, foo, bar)
    }

    /**
     * Intercepts actionExecuted events, ordinarily written to the master log, for
     * use locally within this test suite.
     */
    class ActionEventRecorder {
        private val actionExecutedEvents: MutableList<ActionExecutedEvent?> =
            java.util.ArrayList<ActionExecutedEvent?>()

        @com.google.common.eventbus.Subscribe
        fun actionExecuted(event: ActionExecutedEvent?) {
            actionExecutedEvents.add(event)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReportsActionExecutedEvent() {
        val pear: Artifact = createDerivedArtifact("pear")
        val recorder: ActionEventRecorder =
            com.google.devtools.build.lib.skyframe.ParallelBuilderTest.ActionEventRecorder()
        eventBus.register(recorder)

        val action: Action? =
            registerAction<T?>(
                TestAction(
                    com.google.common.util.concurrent.Runnables.doNothing(),
                    TimestampBuilderTestCase.emptyNestedSet,
                    com.google.common.collect.ImmutableSet.of<Artifact>(pear)
                )
            )

        buildArtifacts(createBuilder(DEFAULT_NUM_JOBS, true), pear)
        Truth.assertThat(recorder.actionExecutedEvents).hasSize(1)
        assertThat(recorder.actionExecutedEvents.get(0).getAction()).isEqualTo(action)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunsInParallel() {
        runsInParallelWithBuilder(createBuilder(DEFAULT_NUM_JOBS, false))
    }

    /**
     * Test that we can recover properly after a failed build.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFailureRecovery() {
        // [action] -> foo

        val foo: Artifact = createDerivedArtifact("foo")
        val makeFoo: java.util.concurrent.Callable<java.lang.Void?> =
            object : java.util.concurrent.Callable<java.lang.Void?> {
                @Throws(IOException::class)
                override fun call(): java.lang.Void? {
                    throw IOException("building 'foo' is supposed to fail")
                }
            }
        registerAction<T?>(
            TestAction(
                makeFoo,
                TimestampBuilderTestCase.emptyNestedSet,
                com.google.common.collect.ImmutableSet.of<Artifact>(foo)
            )
        )

        // [action] -> bar
        val bar: Artifact = createDerivedArtifact("bar")
        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                TimestampBuilderTestCase.emptyNestedSet,
                com.google.common.collect.ImmutableSet.of<Artifact>(bar)
            )
        )

        // Don't fail fast when we encounter the error
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        // test that building 'foo' fails
        val e: BuildFailedException = org.junit.Assert.assertThrows<T>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildArtifacts(foo) })
        if (!e.getMessage().contains("building 'foo' is supposed to fail")) {
            throw e
        }
        // Make sure the reporter reported the error message.
        assertContainsEvent("building 'foo' is supposed to fail")
        // test that a subsequent build of 'bar' succeeds
        buildArtifacts(bar)
    }

    @org.junit.Test
    fun testUpdateCacheError() {
        val fs: FileSystem =
            object : InMemoryFileSystem(DigestHashFunction.SHA256) {
                @Throws(IOException::class)
                public override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus {
                    val stat: FileStatus = super.statIfFound(path, followSymlinks)
                    if (path.toString().endsWith("/out/foo")) {
                        return object : FileStatus() {
                            private val original: FileStatus = stat

                            public override fun isSymbolicLink(): Boolean {
                                return original.isSymbolicLink
                            }

                            public override fun isFile(): Boolean {
                                return original.isFile
                            }

                            public override fun isDirectory(): Boolean {
                                return original.isDirectory
                            }

                            public override fun isSpecialFile(): Boolean {
                                return original.isSpecialFile
                            }

                            @Throws(IOException::class)
                            public override fun getSize(): Long {
                                return original.size
                            }

                            @Throws(IOException::class)
                            public override fun getNodeId(): Long {
                                return original.nodeId
                            }

                            @Throws(IOException::class)
                            public override fun getLastModifiedTime(): Long {
                                throw IOException()
                            }

                            @Throws(IOException::class)
                            public override fun getLastChangeTime(): Long {
                                throw IOException()
                            }
                        }
                    }
                    return stat
                }
            }
        val foo: Artifact = TimestampBuilderTestCase.createDerivedArtifact(fs, "foo")
        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                TimestampBuilderTestCase.emptyNestedSet,
                com.google.common.collect.ImmutableSet.of<Artifact>(foo)
            )
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildArtifacts(foo) })
        assertContainsEvent("not all outputs were created or valid")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNullBuild() {
        // BuildTool.setupLogging(Level.FINEST);
        logger.atFine().log("Testing null build...")
        buildArtifacts()
    }

    /**
     * Test a randomly-generated complex dependency graph.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSmallRandomStressTest() {
        val numTrials = 1
        val numArtifacts = 30
        val randomSeed = 42
        val test = StressTest(numArtifacts, numTrials, randomSeed)
        test.runStressTest()
    }

    private enum class BuildKind {
        Clean, Incremental, Nop
    }

    /**
     * Sets up and manages stress tests of arbitrary size.
     */
    protected inner class StressTest(val numArtifacts: Int, val numTrials: Int, randomSeed: Int) {
        var random: Random
        var artifacts: Array<Artifact?>

        init {
            this.random = Random(randomSeed.toLong())
        }

        @Throws(java.lang.Exception::class)
        fun runStressTest() {
            for (trial in 0..<numTrials) {
                val counters: MutableList<TimestampBuilderTestCase.Counter> = buildRandomActionGraph(trial)

                // do a clean build
                logger.atFine().log("Testing clean build... (trial %d)", trial)
                var buildTargets: Array<Artifact> = chooseRandomBuild()
                buildArtifacts(*buildTargets)
                doSanityChecks(buildTargets, counters, BuildKind.Clean)
                resetCounters(counters)

                // Do an incremental build.
                //
                // BuildTool creates new instances of the Builder for each build request. It may rely on
                // that fact (that its state will be discarded after each build request) - thus
                // test should use same approach and ensure that a new instance is used each time.
                logger.atFine().log("Testing incremental build...")
                buildTargets = chooseRandomBuild()
                buildArtifacts(*buildTargets)
                doSanityChecks(buildTargets, counters, BuildKind.Incremental)
                resetCounters(counters)

                // do a do-nothing build
                logger.atFine().log("Testing do-nothing rebuild...")
                buildArtifacts(*buildTargets)
                doSanityChecks(buildTargets, counters, BuildKind.Nop)
                //resetCounters(counters);
            }
        }

        /**
         * Construct a random action graph, and initialize the file system
         * so that all of the input files exist and none of the output files
         * exist.
         */
        @Throws(IOException::class)
        fun buildRandomActionGraph(actionGraphNumber: Int): MutableList<TimestampBuilderTestCase.Counter> {
            val counters: MutableList<TimestampBuilderTestCase.Counter> =
                java.util.ArrayList<TimestampBuilderTestCase.Counter>(numArtifacts)

            artifacts = arrayOfNulls<Artifact>(numArtifacts)
            for (i in 0..<numArtifacts) {
                artifacts[i] = createDerivedArtifact("file" + actionGraphNumber + "-" + i)
            }

            var numOutputs: Int
            var i = 0
            while (i < artifacts.size) {
                val numInputs: Int = random.nextInt(3)
                numOutputs = 1 + random.nextInt(2)
                if (i + numOutputs >= artifacts.size) {
                    numOutputs = artifacts.size - i
                }

                val inputs: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
                for (j in 0..<numInputs) {
                    if (i != 0) {
                        val inputNum: Int = random.nextInt(i)
                        inputs.add(artifacts[inputNum])
                    }
                }
                val outputs: MutableCollection<Artifact> = java.util.ArrayList<Artifact>(numOutputs)
                for (j in 0..<numOutputs) {
                    outputs.add(artifacts[i + j])
                }
                counters.add(
                    createActionCounter(
                        inputs.build(),
                        com.google.common.collect.ImmutableSet.copyOf<Artifact?>(outputs)
                    )
                )
                if (inputs.isEmpty()) {
                    // source files -- create them
                    for (output in outputs) {
                        BlazeTestUtils.makeEmptyFile(output.getPath())
                    }
                } else {
                    // generated files -- delete them
                    for (output in outputs) {
                        try {
                            output.getPath().delete()
                        } catch (e: FileNotFoundException) {
                            // ok
                        }
                    }
                }
                i += numOutputs
            }
            return counters
        }

        /**
         * Choose a random set of targets to build.
         */
        fun chooseRandomBuild(): Array<Artifact> {
            val buildTargets: Array<Artifact>
            when (random.nextInt(4)) {
                0 -> {
                    // build the final output target
                    logger.atFine().log("Building final output target.")
                    buildTargets = arrayOf<Artifact>(artifacts[numArtifacts - 1])
                }

                1 -> {
                    // build all the targets (in random order);
                    logger.atFine().log("Building all the targets.")
                    val targets: MutableList<Artifact?> =
                        com.google.common.collect.Lists.newArrayList<Artifact?>(*artifacts)
                    Collections.shuffle(targets, random)
                    buildTargets = targets.toTypedArray<Artifact>()
                }

                2 -> {
                    // build a random target
                    logger.atFine().log("Building a random target.")
                    buildTargets = arrayOf<Artifact>(artifacts[random.nextInt(numArtifacts)])
                }

                3 -> {
                    // build a random subset of targets
                    logger.atFine().log("Building a random subset of targets.")
                    val targets: MutableList<Artifact?> =
                        com.google.common.collect.Lists.newArrayList<Artifact?>(*artifacts)
                    Collections.shuffle(targets, random)
                    val targetSubset: MutableList<Artifact?> = java.util.ArrayList<Artifact?>()
                    val numTargetsToTest: Int = random.nextInt(numArtifacts)
                    logger.atFine().log("numTargetsToTest = %d", numTargetsToTest)
                    val iterator: MutableIterator<Artifact?> = targets.iterator()
                    var i = 0
                    while (i < numTargetsToTest) {
                        targetSubset.add(iterator.next())
                        i++
                    }
                    buildTargets = targetSubset.toTypedArray<Artifact>()
                }

                else -> throw java.lang.IllegalStateException()
            }
            return buildTargets
        }

        fun doSanityChecks(
            targets: Array<Artifact>, counters: MutableList<TimestampBuilderTestCase.Counter>,
            kind: BuildKind
        ) {
            // Check that we really did build all the targets.
            for (file in targets) {
                assertThat(file.getPath().exists()).isTrue()
            }
            // Check that each action was executed the right number of times
            for (counter in counters) {
                when (kind) {
                    BuildKind.Clean, BuildKind.Incremental -> Truth.assertThat(counter.count).isAnyOf(0, 1)
                    BuildKind.Nop -> Truth.assertThat(counter.count).isEqualTo(0)
                }
            }
        }

        private fun resetCounters(counters: MutableList<TimestampBuilderTestCase.Counter>) {
            for (counter in counters) {
                counter.count = 0
            }
        }
    }

    // Regression test for bug fixed in CL 3548332: builder was not waiting for
    // all its subprocesses to terminate.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWaitsForSubprocesses() {
        val semaphore: Semaphore = Semaphore(1)
        val finished = booleanArrayOf(false)

        semaphore.acquireUninterruptibly() // t=0: semaphore acquired

        // This arrangement ensures that the "bar" action tries to run for about
        // 100ms after the "foo" action has completed (failed).

        // [action] -> foo
        val foo: Artifact = createDerivedArtifact("foo")
        val makeFoo: java.util.concurrent.Callable<java.lang.Void?> =
            object : java.util.concurrent.Callable<java.lang.Void?> {
                @Throws(IOException::class)
                override fun call(): java.lang.Void? {
                    semaphore.acquireUninterruptibly() // t=2: semaphore re-acquired
                    throw IOException("foo action failed")
                }
            }
        registerAction<T?>(
            TestAction(
                makeFoo,
                TimestampBuilderTestCase.emptyNestedSet,
                com.google.common.collect.ImmutableSet.of<Artifact>(foo)
            )
        )

        // [action] -> bar
        val bar: Artifact = createDerivedArtifact("bar")
        val makeBar: java.lang.Runnable = object : java.lang.Runnable {
            override fun run() {
                semaphore.release() // t=1: semaphore released
                try {
                    java.lang.Thread.sleep(100) // 100ms
                } catch (e: java.lang.InterruptedException) {
                    // This might happen (though not necessarily).  The
                    // ParallelBuilder interrupts all its workers at the first sign
                    // of trouble.
                }
                finished[0] = true
            }
        }
        registerAction<T?>(
            TestAction(
                makeBar,
                TimestampBuilderTestCase.emptyNestedSet,
                com.google.common.collect.ImmutableSet.of<Artifact>(bar)
            )
        )

        // Don't fail fast when we encounter the error
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        val e: BuildFailedException? =
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildArtifacts(foo, bar) })
        assertThat(e)
            .hasMessageThat()
            .contains("TestAction failed due to exception: foo action failed")
        assertContainsEvent("TestAction failed due to exception: foo action failed")

        Truth.assertWithMessage("bar action not finished, yet buildArtifacts has completed.")
            .that(finished[0])
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCyclicActionGraph() {
        // foo -> [action] -> bar
        // bar -> [action] -> baz
        // baz -> [action] -> foo
        val foo: Artifact = createDerivedArtifact("foo")
        val bar: Artifact = createDerivedArtifact("bar")
        val baz: Artifact = createDerivedArtifact("baz")
        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                Companion.asNestedSet<Any?>(foo),
                com.google.common.collect.ImmutableSet.of<Artifact>(bar)
            )
        )
        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                Companion.asNestedSet<Any?>(bar),
                com.google.common.collect.ImmutableSet.of<Artifact>(baz)
            )
        )
        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                Companion.asNestedSet<Any?>(baz),
                com.google.common.collect.ImmutableSet.of<Artifact>(foo)
            )
        )
        val e: BuildFailedException? =
            org.junit.Assert.assertThrows<T?>(
                "Builder failed to detect cyclic action graph",
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildArtifacts(foo) })
        assertThat(e).hasMessageThat().isEqualTo(TimestampBuilderTestCase.CYCLE_MSG)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelfCyclicActionGraph() {
        // foo -> [action] -> foo
        val foo: Artifact = createDerivedArtifact("foo")
        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                Companion.asNestedSet<Any?>(foo),
                com.google.common.collect.ImmutableSet.of<Artifact>(foo)
            )
        )
        val e: BuildFailedException? =
            org.junit.Assert.assertThrows<T?>(
                "Builder failed to detect cyclic action graph",
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildArtifacts(foo) })
        assertThat(e).hasMessageThat().isEqualTo(TimestampBuilderTestCase.CYCLE_MSG)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCycleInActionGraphBelowTwoActions() {
        // bar -> [action] -> foo1
        // bar -> [action] -> foo2
        // baz -> [action] -> bar
        // bar -> [action] -> baz
        val foo1: Artifact = createDerivedArtifact("foo1")
        val foo2: Artifact = createDerivedArtifact("foo2")
        val bar: Artifact = createDerivedArtifact("bar")
        val baz: Artifact = createDerivedArtifact("baz")
        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                Companion.asNestedSet<Any?>(bar),
                com.google.common.collect.ImmutableSet.of<Artifact>(foo1)
            )
        )
        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                Companion.asNestedSet<Any?>(bar),
                com.google.common.collect.ImmutableSet.of<Artifact>(foo2)
            )
        )
        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                Companion.asNestedSet<Any?>(baz),
                com.google.common.collect.ImmutableSet.of<Artifact>(bar)
            )
        )
        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                Companion.asNestedSet<Any?>(bar),
                com.google.common.collect.ImmutableSet.of<Artifact>(baz)
            )
        )
        val e: BuildFailedException? =
            org.junit.Assert.assertThrows<T?>(
                "Builder failed to detect cyclic action graph",
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildArtifacts(foo1, foo2) })
        assertThat(e).hasMessageThat().isEqualTo(TimestampBuilderTestCase.CYCLE_MSG)
    }


    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCyclicActionGraphWithTail() {
        // bar -> [action] -> foo
        // baz -> [action] -> bar
        // bat, foo -> [action] -> baz
        val foo: Artifact = createDerivedArtifact("foo")
        val bar: Artifact = createDerivedArtifact("bar")
        val baz: Artifact = createDerivedArtifact("baz")
        val bat: Artifact = createDerivedArtifact("bat")
        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                Companion.asNestedSet<Any?>(bar),
                com.google.common.collect.ImmutableSet.of<Artifact>(foo)
            )
        )
        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                Companion.asNestedSet<Any?>(baz),
                com.google.common.collect.ImmutableSet.of<Artifact>(bar)
            )
        )
        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                Companion.asNestedSet<Any?>(bat, foo),
                com.google.common.collect.ImmutableSet.of<Artifact>(baz)
            )
        )
        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                TimestampBuilderTestCase.emptyNestedSet,
                com.google.common.collect.ImmutableSet.of<Artifact>(bat)
            )
        )
        val e: BuildFailedException? =
            org.junit.Assert.assertThrows<T?>(
                "Builder failed to detect cyclic action graph",
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildArtifacts(foo) })
        assertThat(e).hasMessageThat().isEqualTo(TimestampBuilderTestCase.CYCLE_MSG)
    }

    // Regression test for bug #735765, "ParallelBuilder still issues new jobs
    // after one has failed, without --keep-going."  The incorrect behaviour is
    // that, when the first job fails, while no new jobs are added to the queue
    // of runnable jobs, the queue may have lots of work in it, and the
    // ParallelBuilder always completes these jobs before it returns.  The
    // correct behaviour is to discard all the jobs in the queue after the first
    // one fails.
    @Throws(java.lang.Exception::class)
    fun assertNoNewJobsAreRunAfterFirstFailure(catastrophe: Boolean, keepGoing: Boolean) {
        // Strategy: Limit parallelism to 3.  Enqueue 10 runnable tasks that run
        // for an appreciable period (say 100ms).  Ensure that at most 3 of those
        // tasks completed.  This proves that all runnable tasks were dropped from
        // the queue after the first batch (which included errors) was finished.
        // It should be pretty robust even in the face of timing variations.

        val completedTasks: AtomicInteger = AtomicInteger(0)

        val numJobs = 50
        val artifacts: Array<Artifact> = arrayOfNulls<Artifact>(numJobs)

        for (ii in 0..<numJobs) {
            val out: Artifact = createDerivedArtifact(ii.toString() + ".out")
            val inputs: NestedSet<Artifact?> =
                if (catastrophe && ii > 10) Companion.asNestedSet<Any?>(artifacts[0]) else TimestampBuilderTestCase.emptyNestedSet
            val iCopy = ii
            registerAction<T?>(
                object : TestAction(
                    object : java.util.concurrent.Callable<java.lang.Void?> {
                        @Throws(java.lang.Exception::class)
                        override fun call(): java.lang.Void? {
                            java.lang.Thread.sleep(100) // 100ms
                            completedTasks.getAndIncrement()
                            throw IOException("task failed")
                        }
                    },
                    inputs,
                    com.google.common.collect.ImmutableSet.of<Artifact>(out)
                ) {
                    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
                    override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
                        if (catastrophe && iCopy == 0) {
                            try {
                                java.lang.Thread.sleep(300) // 300ms
                            } catch (e: java.lang.InterruptedException) {
                                throw java.lang.RuntimeException(e)
                            }
                            completedTasks.getAndIncrement()
                            val code: DetailedExitCode? =
                                DetailedExitCode.of(
                                    FailureDetail.newBuilder()
                                        .setCrash(Crash.newBuilder().setCode(Code.CRASH_UNKNOWN))
                                        .build()
                                )
                            throw ActionExecutionException("This is a catastrophe", this, true, code)
                        }
                        return super.execute(actionExecutionContext)
                    }
                })
            artifacts[ii] = out
        }

        // Don't fail fast when we encounter the error
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildArtifacts(createBuilder(3, keepGoing), *artifacts) })
        assertContainsEvent("task failed")
        if (completedTasks.get() >= numJobs) {
            org.junit.Assert.fail("Expected early termination due to failed task, but all tasks ran to completion.")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoNewJobsAreRunAfterFirstFailure() {
        assertNoNewJobsAreRunAfterFirstFailure(false, false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoNewJobsAreRunAfterCatastrophe() {
        assertNoNewJobsAreRunAfterFirstFailure(true, true)
    }

    @Throws(IOException::class)
    private fun createInputFile(name: String?): Artifact {
        val artifact: Artifact = createSourceArtifact(name)
        val path: Path = artifact.getPath()
        path.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(path)
        return artifact
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProgressReporting() {
        // Build three artifacts in 3 separate actions (baz depends on bar and bar
        // depends on foo.  Make sure progress is reported at the beginning of all
        // three actions.
        val sourceFiles: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        for (i in 0..9) {
            sourceFiles.add(createInputFile("file" + i))
        }
        val foo: Artifact = createDerivedArtifact("foo")
        val bar: Artifact = createDerivedArtifact("bar")
        val baz: Artifact = createDerivedArtifact("baz")
        bar.getPath().delete()
        baz.getPath().delete()

        val messages: MutableList<String?> = java.util.ArrayList<String?>()
        val handler: com.google.devtools.build.lib.events.EventHandler =
            object : com.google.devtools.build.lib.events.EventHandler {
                override fun handle(event: com.google.devtools.build.lib.events.Event) {
                    val k: com.google.devtools.build.lib.events.EventKind? = event.getKind()
                    if (k == com.google.devtools.build.lib.events.EventKind.START || k == com.google.devtools.build.lib.events.EventKind.FINISH) {
                        // Remove the tmpDir as this is user specific and the assert would
                        // fail below.
                        messages.add(
                            event.getMessage().replaceFirst(
                                com.google.devtools.build.lib.testutil.TestUtils.tmpDir().toRegex(),
                                ""
                            ) + " " + event.getKind()
                        )
                    }
                }
            }
        reporter.addHandler(handler)
        reporter.addHandler(PrintingEventHandler(com.google.devtools.build.lib.events.EventKind.ALL_EVENTS))

        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                sourceFiles.build(),
                com.google.common.collect.ImmutableSet.of<E?>(foo)
            )
        )
        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                Companion.asNestedSet<Any?>(foo),
                com.google.common.collect.ImmutableSet.of<Artifact>(bar)
            )
        )
        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                Companion.asNestedSet<Any?>(bar),
                com.google.common.collect.ImmutableSet.of<Artifact>(baz)
            )
        )
        buildArtifacts(baz)
        // Check that the percentages increase non-linearly, because foo has 10 input files
        var expectedMessages: MutableList<String?> = com.google.common.collect.Lists.newArrayList<String?>(
            " Test foo START",
            " Test foo FINISH",
            " Test bar START",
            " Test bar FINISH",
            " Test baz START",
            " Test baz FINISH"
        )
        Truth.assertThat(messages).containsAtLeastElementsIn(expectedMessages)

        // Now do an incremental rebuild of bar and baz,
        // and check the incremental progress percentages.
        messages.clear()
        bar.getPath().delete()
        baz.getPath().delete()
        // This uses a new builder instance so that we refetch timestamps from
        // (in-memory) file system, rather than using cached entries.
        buildArtifacts(baz)
        expectedMessages = com.google.common.collect.Lists.newArrayList<String?>(
            " Test bar START",
            " Test bar FINISH",
            " Test baz START",
            " Test baz FINISH"
        )
        Truth.assertThat(messages).containsAtLeastElementsIn(expectedMessages)
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        protected const val DEFAULT_NUM_JOBS: Int = 100

        @java.lang.SafeVarargs
        protected fun <T> asSet(vararg elements: T?): MutableSet<T?> {
            return com.google.common.collect.Sets.newHashSet<T?>(*elements)
        }

        @java.lang.SafeVarargs
        protected fun <T> asNestedSet(vararg elements: T?): NestedSet<T?> {
            return NestedSetBuilder.create(Order.STABLE_ORDER, elements)
        }
    }
}
