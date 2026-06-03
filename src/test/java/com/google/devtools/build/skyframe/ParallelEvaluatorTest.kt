// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.testutil.EventIterableSubjectFactory.assertThatEvents

/** Tests for [ParallelEvaluator].  */
@RunWith(TestParameterInjector::class)
class ParallelEvaluatorTest {
    @TestParameter
    private val useQueryDep = false

    /**
     * If true, [.skyKey] creates the [SkipBatchPrefetchKey] so that [ ] is created and previously requested deps values are not batch
     * prefetched.
     */
    // TODO: b/324948927 - Remove this test parameter along with `SkyKey#skipBatchPrefetch()` method.
    // Design another approach to cover scenarios when batch prefetch does and does not happen in
    // `ParallelEvaluatorTest`.
    @TestParameter
    private val useSkipBatchPrefetchKey = false

    private fun skyKey(key: String?): SkyKey {
        return if (useSkipBatchPrefetchKey)
            GraphTester.Companion.skipBatchPrefetchKey(key)
        else
            GraphTester.Companion.skyKey(key)
    }

    protected var graph: ProcessableGraph? = null
    protected var graphVersion: IntVersion = IntVersion.of(0)
    protected var tester: GraphTester = GraphTester()

    private val reportedEvents: StoredEventHandler = StoredEventHandler()

    private var revalidationReceiver: DirtyAndInflightTrackingProgressReceiver =
        DirtyAndInflightTrackingProgressReceiver(EvaluationProgressReceiver.NULL)

    @Before
    fun configureTesterUseLookup() {
        tester.setUseQueryDep(useQueryDep)
    }

    @org.junit.After
    fun assertNoTrackedErrors() {
        TrackingAwaiter.Companion.INSTANCE.assertNoErrors()
    }

    private fun makeEvaluator(
        graph: ProcessableGraph?,
        builders: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?,
        keepGoing: Boolean,
        storedEventFilter: EventFilter?,
        evaluationVersion: Version?
    ): ParallelEvaluator {
        return makeEvaluator(
            graph,
            builders,
            storedEventFilter,
            evaluationVersion,
            java.util.function.Predicate { unused: SkyKey? -> keepGoing })
    }

    private fun makeEvaluator(
        graph: ProcessableGraph?,
        builders: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?,
        storedEventFilter: EventFilter?,
        evaluationVersion: Version?,
        keepGoingPredicate: java.util.function.Predicate<SkyKey?>?
    ): ParallelEvaluator {
        return ParallelEvaluator(
            graph,
            evaluationVersion,
            Version.minimal(),
            builders,
            reportedEvents,
            EmittedEventState(),
            storedEventFilter,
            ErrorInfoManager.UseChildErrorInfoIfNecessary.INSTANCE,
            revalidationReceiver,
            GraphInconsistencyReceiver.THROWING,
            AbstractQueueVisitor.create("test-pool", 200, ParallelEvaluatorErrorClassifier.instance()),
            SimpleCycleDetector( /* storeExactCycles= */true),
            UnnecessaryTemporaryStateDropperReceiver.NULL,
            keepGoingPredicate
        )
    }

    private fun makeEvaluator(
        graph: ProcessableGraph?,
        builders: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?,
        keepGoing: Boolean,
        storedEventFilter: EventFilter?
    ): ParallelEvaluator {
        val oldGraphVersion: Version? = graphVersion
        graphVersion = graphVersion.next()
        return makeEvaluator(graph, builders, keepGoing, storedEventFilter, oldGraphVersion)
    }

    private fun makeEvaluator(
        graph: ProcessableGraph?,
        builders: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?,
        keepGoing: Boolean
    ): ParallelEvaluator {
        return makeEvaluator(graph, builders, keepGoing, EventFilter.FULL_STORAGE)
    }

    /** Convenience method for eval-ing a single value.  */
    @Throws(java.lang.InterruptedException::class)
    protected fun eval(keepGoing: Boolean, key: SkyKey): SkyValue {
        return eval<SkyValue?>(keepGoing, com.google.common.collect.ImmutableList.of<SkyKey?>(key)).get(key)
    }

    @Throws(java.lang.InterruptedException::class)
    protected fun <T : SkyValue?> eval(keepGoing: Boolean, vararg keys: SkyKey?): EvaluationResult<T?> {
        return eval<SkyValue?>(keepGoing, com.google.common.collect.ImmutableList.copyOf<SkyKey?>(keys))
    }

    @Throws(java.lang.InterruptedException::class)
    protected fun <T : SkyValue?> eval(keepGoing: Boolean, keys: Iterable<SkyKey?>?): EvaluationResult<T?> {
        val evaluator: ParallelEvaluator = makeEvaluator(graph, tester.getSkyFunctionMap(), keepGoing)
        return evaluator.eval(keys)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun evalValueInError(key: SkyKey): ErrorInfo {
        return eval<SkyValue?>(true, com.google.common.collect.ImmutableList.of<SkyKey?>(key)).getError(key)
    }

    protected fun set(name: String?, value: String?): TestFunction? {
        return tester.set(skyKey(name), com.google.devtools.build.skyframe.GraphTester.StringValue(value))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smoke() {
        graph = InMemoryGraphImpl()
        set("a", "a")
        set("b", "b")

        val abKey: SkyKey = skyKey("ab")
        tester
            .getOrCreate(abKey)
            .addDependency(skyKey("a"))
            .addDependency(skyKey("b"))
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        val value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            eval(false, abKey) as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("ab")
        Truth.assertThat(reportedEvents.getEvents()).isEmpty()
        Truth.assertThat(reportedEvents.getPosts()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enqueueDoneFuture() {
        val parentKey: SkyKey = skyKey("parentKey")
        tester
            .getOrCreate(parentKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    val future: com.google.common.util.concurrent.SettableFuture<SkyValue?> =
                        com.google.common.util.concurrent.SettableFuture.create<SkyValue?>()
                    future.set(com.google.devtools.build.skyframe.GraphTester.StringValue("good"))
                    env.dependOnFuture(future)
                    assertThat(env.valuesMissing()).isFalse()
                    try {
                        return@setBuilder future.get()
                    } catch (e: ExecutionException) {
                        throw java.lang.RuntimeException(e)
                    }
                })
        graph = InMemoryGraphImpl()
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */false, com.google.common.collect.ImmutableList.of<SkyKey?>(parentKey))
        assertThat(result.hasError()).isFalse()
        assertThat(result.get(parentKey)).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("good"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enqueueBadFuture() {
        val parentKey: SkyKey = skyKey("parentKey")
        val doneLatch: CountDownLatch = CountDownLatch(1)
        val executor: com.google.common.util.concurrent.ListeningExecutorService =
            com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1))
        tester
            .getOrCreate(parentKey)
            .setBuilder(
                object : SkyFunction() {
                    private var future: com.google.common.util.concurrent.ListenableFuture<SkyValue?>? = null

                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        if (future == null) {
                            future =
                                executor.submit<SkyValue?>(
                                    java.util.concurrent.Callable {
                                        doneLatch.await()
                                        throw java.lang.UnsupportedOperationException()
                                    })
                            env.dependOnFuture(future)
                            assertThat(env.valuesMissing()).isTrue()
                            return null
                        }
                        Truth.assertThat(future.isDone()).isTrue()
                        val expected: ExecutionException =
                            org.junit.Assert.assertThrows<ExecutionException>(
                                ExecutionException::class.java,
                                org.junit.function.ThrowingRunnable { future.get() })
                        Truth.assertThat(expected.cause)
                            .isInstanceOf(java.lang.UnsupportedOperationException::class.java)
                        return com.google.devtools.build.skyframe.GraphTester.StringValue("Caught!")
                    }
                })
        graph =
            NotifyingHelper.Companion.makeNotifyingTransformer(
                com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                    // NodeEntry.addExternalDep is called as part of bookkeeping at the end of
                    // AbstractParallelEvaluator.Evaluate#run.
                    if (key === parentKey && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.ADD_EXTERNAL_DEP) {
                        doneLatch.countDown()
                    }
                })
                .transform(InMemoryGraphImpl())
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */false, com.google.common.collect.ImmutableList.of<SkyKey?>(parentKey))
        assertThat(result.hasError()).isFalse()
        assertThat(result.get(parentKey)).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("Caught!"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dependsOnKeyAndFuture() {
        val parentKey: SkyKey = skyKey("parentKey")
        val childKey: SkyKey = skyKey("childKey")
        val doneLatch: CountDownLatch = CountDownLatch(1)
        tester.getOrCreate(childKey)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("child"))
        tester
            .getOrCreate(parentKey)
            .setBuilder(
                object : SkyFunction() {
                    private var future: com.google.common.util.concurrent.SettableFuture<SkyValue?>? = null

                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        val child: SkyValue? = env.getValue(childKey)
                        if (future == null) {
                            assertThat(child).isNull()
                            future = com.google.common.util.concurrent.SettableFuture.create<SkyValue?>()
                            env.dependOnFuture(future)
                            assertThat(env.valuesMissing()).isTrue()
                            java.lang.Thread(
                                java.lang.Runnable {
                                    try {
                                        doneLatch.await()
                                    } catch (e: java.lang.InterruptedException) {
                                        throw java.lang.RuntimeException(e)
                                    }
                                    future.set(com.google.devtools.build.skyframe.GraphTester.StringValue("future"))
                                })
                                .start()
                            return null
                        }
                        assertThat(child).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("child"))
                        Truth.assertThat(future.isDone()).isTrue()
                        try {
                            assertThat(future.get()).isEqualTo(
                                com.google.devtools.build.skyframe.GraphTester.StringValue(
                                    "future"
                                )
                            )
                        } catch (e: ExecutionException) {
                            throw java.lang.RuntimeException(e)
                        }
                        return com.google.devtools.build.skyframe.GraphTester.StringValue("All done!")
                    }
                })
        graph =
            NotifyingHelper.Companion.makeNotifyingTransformer(
                com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                    if (key === childKey && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE) {
                        doneLatch.countDown()
                    }
                })
                .transform(InMemoryGraphImpl())
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */false, com.google.common.collect.ImmutableList.of<SkyKey?>(parentKey))
        assertThat(result.hasError()).isFalse()
        assertThat(result.get(parentKey)).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("All done!"))
    }

    /** Test interruption handling when a long-running SkyFunction gets interrupted.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun interruptedFunction() {
        runInterruptionTest(
            SkyFunctionFactory { threadStarted: Semaphore?, errorMessage: Array<String?>? ->
                SkyFunction { key, env ->
                    // Signal the waiting test thread that the evaluator thread has really started.
                    threadStarted.release()

                    // Simulate a SkyFunction that runs for 10 seconds (this number was chosen
                    // arbitrarily). The main thread should interrupt it shortly after it got started.
                    java.lang.Thread.sleep((10 * 1000).toLong())

                    // Set an error message to indicate that the expected interruption didn't happen.
                    // We can't use Assert.fail(String) on an async thread.
                    errorMessage!![0] = "SkyFunction should have been interrupted"
                    null
                }
            })
    }

    /**
     * Test interruption handling when the Evaluator is in-between running SkyFunctions.
     * 
     * 
     * This is the point in time after a SkyFunction requested a dependency which is not yet built
     * so the builder returned null to the Evaluator, and the latter is about to schedule evaluation
     * of the missing dependency but gets interrupted before the dependency's SkyFunction could start.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun interruptedEvaluatorThread() {
        runInterruptionTest(
            SkyFunctionFactory { threadStarted: Semaphore?, errorMessage: Array<String?>? ->
                object : SkyFunction() {
                    // No need to synchronize access to this field; we always request just one more
                    // dependency, so it's only one SkyFunction running at any time.
                    private var valueIdCounter = 0

                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(key: SkyKey?, env: Environment): SkyValue? {
                        // Signal the waiting test thread that the Evaluator thread has really started.
                        threadStarted.release()

                        // Keep the evaluator busy until the test's thread gets scheduled and can
                        // interrupt the Evaluator's thread.
                        env.getValue(skyKey("a" + valueIdCounter++))

                        // This method never throws InterruptedException, therefore it's the responsibility
                        // of the Evaluator to detect the interrupt and avoid calling subsequent
                        // SkyFunctions.
                        return@runInterruptionTest null
                    }
                }
            })
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun interruptedEvaluatorThreadAfterEnqueueBeforeWaitForCompletionAndConstructResult() {
        // This is a regression test for a crash bug in
        // AbstractExceptionalParallelEvaluator#doMutatingEvaluation in a very specific window of time
        // between enqueueing one top-level node for evaluation and checking if another top-level node
        // is done.

        // When we have two top-level nodes, A and B,

        val keyA: SkyKey = skyKey("a")
        val keyB: SkyKey = skyKey("b")

        // And rig the graph and node entries, such that B's addReverseDepAndCheckIfDone waits for A to
        // start computing and then tries to observe an interrupt (which will happen on the calling
        // thread, aka the main Skyframe evaluation thread),
        val keyAStartedComputingLatch: CountDownLatch = CountDownLatch(1)
        val keyBAddReverseDepAndCheckIfDoneLatch: CountDownLatch = CountDownLatch(1)
        val nodeEntryB: InMemoryNodeEntry? = spy(IncrementalInMemoryNodeEntry(keyB))
        val keyBAddReverseDepAndCheckIfDoneInterrupted: AtomicBoolean = AtomicBoolean(false)
        Mockito.doAnswer(
            Answer { invocation: InvocationOnMock? ->
                keyAStartedComputingLatch.await()
                keyBAddReverseDepAndCheckIfDoneLatch.countDown()
                try {
                    java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                    throw java.lang.IllegalStateException("shouldn't get here")
                } catch (e: java.lang.InterruptedException) {
                    keyBAddReverseDepAndCheckIfDoneInterrupted.set(true)
                    throw e
                }
            })
            .`when`<Any?>(nodeEntryB)
            .addReverseDepAndCheckIfDone(ArgumentMatchers.eq<T?>(null))
        graph =
            object : InMemoryGraphImpl() {
                protected override fun newNodeEntry(key: SkyKey): InMemoryNodeEntry? {
                    return if (key.equals(keyB)) nodeEntryB else super.newNodeEntry(key)
                }
            }
        // And A's SkyFunction tries to observe an interrupt after it starts computing,
        val keyAComputeInterrupted: AtomicBoolean = AtomicBoolean(false)
        tester
            .getOrCreate(keyA)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    keyAStartedComputingLatch.countDown()
                    try {
                        java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                        throw java.lang.IllegalStateException("shouldn't get here")
                    } catch (e: java.lang.InterruptedException) {
                        keyAComputeInterrupted.set(true)
                        throw e
                    }
                })

        // And we have a dedicated thread that kicks off the evaluation of A and B together (in that
        // order).
        val evalThread: TestThread =
            TestThread(
                TestRunnable {
                    org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
                        java.lang.InterruptedException::class.java,
                        org.junit.function.ThrowingRunnable { eval<SkyValue?>( /* keepGoing= */true, keyA, keyB) })
                })

        // Then when we start that thread,
        evalThread.start()
        // We (the thread running the test) are able to observe that B's addReverseDepAndCheckIfDone has
        // just been called (implying that A has started to be computed).
        Truth.assertThat(
            keyBAddReverseDepAndCheckIfDoneLatch.await(
                com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
            )
        )
            .isTrue()
        // Then when we interrupt the evaluation thread,
        evalThread.interrupt()
        // The evaluation thread eventually terminates.
        evalThread.joinAndAssertState(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
        // And we are able to verify both that A's SkyFunction had observed an interrupt,
        Truth.assertThat(keyAComputeInterrupted.get()).isTrue()
        // And also that B's addReverseDepAndCheckIfDoneInterrupted had observed an interrupt.
        Truth.assertThat(keyBAddReverseDepAndCheckIfDoneInterrupted.get()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun runPartialResultOnInterruption(@TestParameter buildFastFirst: Boolean) {
        graph = InMemoryGraphImpl()
        // Two runs for fastKey's builder and one for the start of waitKey's builder.
        val allValuesReady: CountDownLatch = CountDownLatch(3)
        val waitKey: SkyKey = skyKey("wait")
        val fastKey: SkyKey = skyKey("fast")
        val leafKey: SkyKey = skyKey("leaf")
        tester
            .getOrCreate(waitKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    allValuesReady.countDown()
                    java.lang.Thread.sleep(10000)
                    throw java.lang.AssertionError("Should have been interrupted")
                })
        tester
            .getOrCreate(fastKey)
            .setBuilder(
                ChainedFunction(
                    null,
                    null,
                    allValuesReady,
                    false,
                    com.google.devtools.build.skyframe.GraphTester.StringValue("fast"),
                    com.google.common.collect.ImmutableList.of<SkyKey?>(leafKey)
                )
            )
        tester.set(leafKey, com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))
        if (buildFastFirst) {
            eval( /* keepGoing= */false, fastKey)
        }
        val receivedValues: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
        revalidationReceiver =
            DirtyAndInflightTrackingProgressReceiver(
                object : EvaluationProgressReceiver() {
                    public override fun evaluated(
                        skyKey: SkyKey?,
                        state: EvaluationState?,
                        newValue: SkyValue?,
                        newError: ErrorInfo?,
                        directDeps: GroupedDeps?
                    ) {
                        receivedValues.add(skyKey)
                    }
                })
        val evalThread: TestThread =
            TestThread(
                TestRunnable {
                    org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
                        java.lang.InterruptedException::class.java,
                        org.junit.function.ThrowingRunnable {
                            eval<SkyValue?>( /* keepGoing= */true,
                                waitKey,
                                fastKey
                            )
                        })
                })
        evalThread.start()
        Truth.assertThat(
            allValuesReady.await(
                com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
        ).isTrue()
        evalThread.interrupt()
        evalThread.join(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
        Truth.assertThat(evalThread.isAlive()).isFalse()
        if (buildFastFirst) {
            // If leafKey was already built, it is not reported to the receiver.
            Truth.assertThat(receivedValues).containsExactly(fastKey)
        } else {
            // On first time being built, leafKey is registered too.
            Truth.assertThat(receivedValues).containsExactly(fastKey, leafKey)
        }
    }

    /** Factory for SkyFunctions for interruption testing (see [.runInterruptionTest]).  */
    private interface SkyFunctionFactory {
        /**
         * Creates a SkyFunction suitable for a specific test scenario.
         * 
         * @param threadStarted a latch which the returned SkyFunction must [     release][Semaphore.release] once it started (otherwise the test won't work)
         * @param errorMessage a single-element array; the SkyFunction can put a error message in it to
         * indicate that an assertion failed (calling `fail` from async thread doesn't work)
         */
        fun create(threadStarted: Semaphore?, errorMessage: Array<String?>?): SkyFunction?
    }

    /**
     * Test that we can handle the Evaluator getting interrupted at various points.
     * 
     * 
     * This method creates an Evaluator with the specified SkyFunction for GraphTested.NODE_TYPE,
     * then starts a thread, requests evaluation and asserts that evaluation started. It then
     * interrupts the Evaluator thread and asserts that it acknowledged the interruption.
     * 
     * @param valueBuilderFactory creates a SkyFunction which may or may not handle interruptions
     * (depending on the test)
     */
    @Throws(java.lang.Exception::class)
    private fun runInterruptionTest(valueBuilderFactory: SkyFunctionFactory) {
        val threadStarted: Semaphore = Semaphore(0)
        val threadInterrupted: Semaphore = Semaphore(0)
        val wasError = arrayOf<String?>(null)
        val evaluator: ParallelEvaluator =
            makeEvaluator(
                InMemoryGraphImpl(),
                com.google.common.collect.ImmutableMap.of<SkyFunctionName?, SkyFunction?>(
                    GraphTester.Companion.NODE_TYPE, valueBuilderFactory.create(threadStarted, wasError)
                ),
                false
            )

        val t: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        evaluator.eval(com.google.common.collect.ImmutableList.of<E?>(skyKey("a")))

                        // There's no real need to set an error here. If the thread is not interrupted then
                        // threadInterrupted is not released and the test thread will fail to acquire it.
                        wasError[0] = "evaluation should have been interrupted"
                    } catch (e: java.lang.InterruptedException) {
                        // This is the interrupt we are waiting for. It should come straight from the
                        // evaluator (more precisely, the AbstractQueueVisitor).
                        // Signal the waiting test thread that the interrupt was acknowledged.
                        threadInterrupted.release()
                    }
                })

        // Start the thread and wait for a semaphore. This ensures that the thread was really started.
        t.start()
        Truth.assertThat(
            threadStarted.tryAcquire(
                com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS,
                TimeUnit.MILLISECONDS
            )
        )
            .isTrue()

        // Interrupt the thread and wait for a semaphore. This ensures that the thread was really
        // interrupted and this fact was acknowledged.
        t.interrupt()
        Truth.assertThat(
            threadInterrupted.tryAcquire(
                com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS
            )
        )
            .isTrue()

        // The SkyFunction may have reported an error.
        if (wasError[0] != null) {
            org.junit.Assert.fail(wasError[0])
        }

        // Wait for the thread to finish.
        t.join(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
    }

    @org.junit.Test
    fun unrecoverableError() {
        class CustomRuntimeException : java.lang.RuntimeException()

        val expected = CustomRuntimeException()

        val builder: SkyFunction =
            object : SkyFunction() {
                public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue? {
                    throw expected
                }
            }

        val evaluator: ParallelEvaluator =
            makeEvaluator(
                InMemoryGraphImpl(),
                com.google.common.collect.ImmutableMap.of<SkyFunctionName?, SkyFunction?>(
                    GraphTester.Companion.NODE_TYPE,
                    builder
                ),
                false
            )

        val valueToEval: SkyKey = skyKey("a")
        val re: java.lang.RuntimeException? =
            org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
                java.lang.RuntimeException::class.java,
                org.junit.function.ThrowingRunnable {
                    evaluator.eval(
                        com.google.common.collect.ImmutableList.of<E?>(valueToEval)
                    )
                })
        Truth.assertThat(re)
            .hasMessageThat()
            .contains("Unrecoverable error while evaluating node '" + valueToEval + "'")
        Truth.assertThat(re).hasCauseThat().isInstanceOf(CustomRuntimeException::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun simpleWarning() {
        graph = InMemoryGraphImpl()
        set("a", "a").setWarning("warning on 'a'")
        val value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            eval(false, skyKey("a")) as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("a")
        assertThatEvents(reportedEvents.getEvents()).containsExactly("warning on 'a'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorOfTopLevelTargetReported() {
        graph = InMemoryGraphImpl()
        val a: SkyKey = skyKey("a")
        val b: SkyKey = skyKey("b")
        tester.getOrCreate(b).setHasError(true)
        val errorEvent: com.google.devtools.build.lib.events.Event? =
            com.google.devtools.build.lib.events.Event.error("foobar")
        tester
            .getOrCreate(a)
            .setBuilder(
                SkyFunction { key, env ->
                    try {
                        if (env.getValueOrThrow(b, SomeErrorException::class.java) == null) {
                            return@setBuilder null
                        }
                    } catch (ignored: SomeErrorException) {
                        // Continue silently.
                    }
                    env.getListener().handle(errorEvent)
                    throw object : SkyFunctionException(
                        SomeErrorException("bazbar"), Transience.PERSISTENT
                    ) {}
                })
        eval(false, a)
        Truth.assertThat(reportedEvents.getEvents()).containsExactly(errorEvent)
    }

    private class ExamplePost(private val storeForReplay: Boolean) : Postable {
        override fun storeForReplay(): Boolean {
            return storeForReplay
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).add("storeForReplay", storeForReplay)
                .toString()
        }
    }

    private enum class SkyframeEventType {
        EVENT {
            override fun createUnstored(): com.google.devtools.build.lib.events.Event? {
                return com.google.devtools.build.lib.events.Event.progress("analyzing")
            }

            override fun createStored(): Reportable? {
                return com.google.devtools.build.lib.events.Event.error("broken")
            }

            override fun getResults(reportedEvents: StoredEventHandler): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?>? {
                return reportedEvents.getEvents()
            }
        },
        POST {
            override fun createUnstored(): Postable {
                return ExamplePost(false)
            }

            override fun createStored(): Postable {
                return ExamplePost(true)
            }

            override fun getResults(reportedEvents: StoredEventHandler): com.google.common.collect.ImmutableList<Postable?>? {
                return reportedEvents.getPosts()
            }
        };

        abstract fun createUnstored(): Reportable

        abstract fun createStored(): Reportable

        abstract fun getResults(reportedEvents: StoredEventHandler?): com.google.common.collect.ImmutableList<out Reportable?>?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fullEventStorage_unstoredEvent_reportedImmediately_notReplayed(
        @TestParameter eventType: SkyframeEventType
    ) {
        graph = InMemoryGraphImpl()
        val key: SkyKey = skyKey("key")
        val evaluated: AtomicBoolean = AtomicBoolean(false)
        val unstoredEvent: Reportable = eventType.createUnstored()
        Truth.assertThat(unstoredEvent.storeForReplay()).isFalse()
        tester
            .getOrCreate(key)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    evaluated.set(true)
                    unstoredEvent.reportTo(env.getListener())
                    Truth.assertThat(eventType.getResults(reportedEvents)).containsExactly(unstoredEvent)
                    com.google.devtools.build.skyframe.GraphTester.StringValue("value")
                })
        var evaluator: ParallelEvaluator =
            makeEvaluator(
                graph, tester.getSkyFunctionMap(),  /* keepGoing= */false, EventFilter.FULL_STORAGE
            )
        evaluator.eval(com.google.common.collect.ImmutableList.of<E?>(key))
        Truth.assertThat(evaluated.get()).isTrue()
        Truth.assertThat(eventType.getResults(reportedEvents)).containsExactly(unstoredEvent)

        reportedEvents.clear()
        evaluated.set(false)

        evaluator =
            makeEvaluator(
                graph, tester.getSkyFunctionMap(),  /* keepGoing= */false, EventFilter.FULL_STORAGE
            )
        evaluator.eval(com.google.common.collect.ImmutableList.of<E?>(key))
        Truth.assertThat(evaluated.get()).isFalse()
        Truth.assertThat(eventType.getResults(reportedEvents)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fullEventStorage_storedEvent_reportedAfterSkyFunctionCompletes_replayed(
        @TestParameter eventType: SkyframeEventType
    ) {
        graph = InMemoryGraphImpl()
        val top: SkyKey = skyKey("top")
        val mid: SkyKey = skyKey("mid")
        val bottom: SkyKey = skyKey("bottom")
        val tag = "this is the tag"
        val evaluatedMid: AtomicBoolean = AtomicBoolean(false)
        val storedEvent: Reportable = eventType.createStored()
        Truth.assertThat(storedEvent.storeForReplay()).isTrue()
        val taggedEvent: Reportable? = storedEvent.withTag(tag)
        tester
            .getOrCreate(top)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    val midValue: SkyValue? = env.getValue(mid)
                    if (midValue == null) {
                        return@setBuilder null
                    }
                    Truth.assertThat(eventType.getResults(reportedEvents)).containsExactly(taggedEvent)
                    com.google.devtools.build.skyframe.GraphTester.StringValue("topValue")
                })
        tester
            .getOrCreate(mid)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        evaluatedMid.set(true)
                        storedEvent.reportTo(env.getListener())
                        Truth.assertThat(eventType.getResults(reportedEvents)).isEmpty()
                        val bottomValue: SkyValue? = env.getValue(bottom)
                        if (bottomValue == null) {
                            return null
                        }
                        return com.google.devtools.build.skyframe.GraphTester.StringValue("midValue")
                    }

                    public override fun extractTag(skyKey: SkyKey?): String {
                        assertThat(skyKey).isEqualTo(mid)
                        return tag
                    }
                })
        tester.getOrCreate(bottom)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("depValue"))
        var evaluator: ParallelEvaluator =
            makeEvaluator(
                graph, tester.getSkyFunctionMap(),  /* keepGoing= */false, EventFilter.FULL_STORAGE
            )
        evaluator.eval(com.google.common.collect.ImmutableList.of<E?>(top))
        Truth.assertThat(evaluatedMid.get()).isTrue()
        Truth.assertThat(eventType.getResults(reportedEvents)).containsExactly(taggedEvent)

        reportedEvents.clear()
        evaluatedMid.set(false)

        evaluator =
            makeEvaluator(
                graph, tester.getSkyFunctionMap(),  /* keepGoing= */false, EventFilter.FULL_STORAGE
            )
        evaluator.eval(com.google.common.collect.ImmutableList.of<E?>(top))
        Truth.assertThat(evaluatedMid.get()).isFalse()
        Truth.assertThat(eventType.getResults(reportedEvents)).containsExactly(taggedEvent)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noEventStorage_unstoredEvent_reportedImmediately_notReplayed(
        @TestParameter eventType: SkyframeEventType
    ) {
        graph = InMemoryGraphImpl()
        val key: SkyKey = skyKey("key")
        val evaluated: AtomicBoolean = AtomicBoolean(false)
        val unstoredEvent: Reportable = eventType.createUnstored()
        Truth.assertThat(unstoredEvent.storeForReplay()).isFalse()
        tester
            .getOrCreate(key)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    evaluated.set(true)
                    unstoredEvent.reportTo(env.getListener())
                    Truth.assertThat(eventType.getResults(reportedEvents)).containsExactly(unstoredEvent)
                    com.google.devtools.build.skyframe.GraphTester.StringValue("value")
                })
        var evaluator: ParallelEvaluator =
            makeEvaluator(
                graph, tester.getSkyFunctionMap(),  /* keepGoing= */false, EventFilter.NO_STORAGE
            )
        evaluator.eval(com.google.common.collect.ImmutableList.of<E?>(key))
        Truth.assertThat(evaluated.get()).isTrue()
        Truth.assertThat(eventType.getResults(reportedEvents)).containsExactly(unstoredEvent)

        reportedEvents.clear()
        evaluated.set(false)

        evaluator =
            makeEvaluator(
                graph, tester.getSkyFunctionMap(),  /* keepGoing= */false, EventFilter.NO_STORAGE
            )
        evaluator.eval(com.google.common.collect.ImmutableList.of<E?>(key))
        Truth.assertThat(evaluated.get()).isFalse()
        Truth.assertThat(eventType.getResults(reportedEvents)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noEventStorage_storedEvent_reportedAfterSkyFunctionCompletes_notReplayed(
        @TestParameter eventType: SkyframeEventType
    ) {
        graph = InMemoryGraphImpl()
        val top: SkyKey = skyKey("top")
        val mid: SkyKey = skyKey("mid")
        val bottom: SkyKey = skyKey("bottom")
        val tag = "this is the tag"
        val evaluatedMid: AtomicBoolean = AtomicBoolean(false)
        val storedEvent: Reportable = eventType.createStored()
        Truth.assertThat(storedEvent.storeForReplay()).isTrue()
        val taggedEvent: Reportable? = storedEvent.withTag(tag)
        tester
            .getOrCreate(top)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    val midValue: SkyValue? = env.getValue(mid)
                    if (midValue == null) {
                        return@setBuilder null
                    }
                    Truth.assertThat(eventType.getResults(reportedEvents)).containsExactly(taggedEvent)
                    com.google.devtools.build.skyframe.GraphTester.StringValue("topValue")
                })
        tester
            .getOrCreate(mid)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        evaluatedMid.set(true)
                        storedEvent.reportTo(env.getListener())
                        Truth.assertThat(eventType.getResults(reportedEvents)).isEmpty()
                        val bottomValue: SkyValue? = env.getValue(bottom)
                        if (bottomValue == null) {
                            return null
                        }
                        return com.google.devtools.build.skyframe.GraphTester.StringValue("midValue")
                    }

                    public override fun extractTag(skyKey: SkyKey?): String {
                        assertThat(skyKey).isEqualTo(mid)
                        return tag
                    }
                })
        tester.getOrCreate(bottom)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("depValue"))
        var evaluator: ParallelEvaluator =
            makeEvaluator(
                graph, tester.getSkyFunctionMap(),  /* keepGoing= */false, EventFilter.NO_STORAGE
            )
        evaluator.eval(com.google.common.collect.ImmutableList.of<E?>(top))
        Truth.assertThat(evaluatedMid.get()).isTrue()
        Truth.assertThat(eventType.getResults(reportedEvents)).containsExactly(taggedEvent)

        reportedEvents.clear()
        evaluatedMid.set(false)

        evaluator =
            makeEvaluator(
                graph, tester.getSkyFunctionMap(),  /* keepGoing= */false, EventFilter.NO_STORAGE
            )
        evaluator.eval(com.google.common.collect.ImmutableList.of<E?>(top))
        Truth.assertThat(evaluatedMid.get()).isFalse()
        Truth.assertThat(eventType.getResults(reportedEvents)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shouldCreateErrorValueWithRootCause() {
        graph = InMemoryGraphImpl()
        set("a", "a")
        val parentErrorKey: SkyKey = skyKey("parent")
        val errorKey: SkyKey = skyKey("error")
        tester
            .getOrCreate(parentErrorKey)
            .addDependency(skyKey("a"))
            .addDependency(errorKey)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(errorKey).setHasError(true)
        evalValueInError(parentErrorKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shouldBuildOneTarget() {
        graph = InMemoryGraphImpl()
        set("a", "a")
        set("b", "b")
        val parentErrorKey: SkyKey = skyKey("parent")
        val errorFreeKey: SkyKey = skyKey("ab")
        val errorKey: SkyKey = skyKey("error")
        tester
            .getOrCreate(parentErrorKey)
            .addDependency(errorKey)
            .addDependency(skyKey("a"))
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(errorKey).setHasError(true)
        tester
            .getOrCreate(errorFreeKey)
            .addDependency(skyKey("a"))
            .addDependency(skyKey("b"))
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(true, parentErrorKey, errorFreeKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(parentErrorKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(errorFreeKey)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("ab"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun catastrophicBuild(@TestParameter keepGoing: Boolean, @TestParameter keepEdges: Boolean) {
        Assume.assumeTrue(keepGoing || keepEdges)

        graph =
            if (keepEdges)
                InMemoryGraph.create( /* usePooledInterning= */true)
            else
                InMemoryGraph.createEdgeless( /* usePooledInterning= */true)

        val catastropheKey: SkyKey = skyKey("catastrophe")
        val otherKey: SkyKey = skyKey("someKey")

        val catastrophe: java.lang.Exception = SomeErrorException("bad")
        tester
            .getOrCreate(catastropheKey)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(SkyFunctionException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue? {
                        throw object : SkyFunctionException(catastrophe, Transience.PERSISTENT) {
                            val isCatastrophic: Boolean
                                get() = true
                        }
                    }
                })

        tester
            .getOrCreate(otherKey)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue? {
                        CountDownLatch(1).await()
                        throw java.lang.RuntimeException("can't get here")
                    }
                })

        val topKey: SkyKey = skyKey("top")
        tester.getOrCreate(topKey).addDependency(catastropheKey).setComputedValue(GraphTester.Companion.CONCATENATE)

        val evaluator: ParallelEvaluator =
            makeEvaluator(
                graph,
                tester.getSkyFunctionMap(),
                keepGoing,
                EventFilter.FULL_STORAGE,
                if (keepEdges) graphVersion else Version.constant()
            )

        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            evaluator.eval(com.google.common.collect.ImmutableList.of<E?>(topKey, otherKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(topKey)
        if (keepGoing) {
            assertThat(result.getCatastrophe()).isSameInstanceAs(catastrophe)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topCatastrophe() {
        graph = InMemoryGraphImpl()
        val catastropheKey: SkyKey = skyKey("catastrophe")
        val catastrophe: java.lang.Exception = SomeErrorException("bad")
        tester
            .getOrCreate(catastropheKey)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(SkyFunctionException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue? {
                        throw object : SkyFunctionException(catastrophe, Transience.PERSISTENT) {
                            val isCatastrophic: Boolean
                                get() = true
                        }
                    }
                })

        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(catastropheKey))
        assertThat(result.getCatastrophe()).isEqualTo(catastrophe)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun catastropheBubblesIntoNonCatastrophe() {
        graph = InMemoryGraphImpl()
        val catastropheKey: SkyKey = skyKey("catastrophe")
        val catastrophe: java.lang.Exception = SomeErrorException("bad")
        tester
            .getOrCreate(catastropheKey)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(SkyFunctionException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue? {
                        throw object : SkyFunctionException(catastrophe, Transience.PERSISTENT) {
                            val isCatastrophic: Boolean
                                get() = true
                        }
                    }
                })
        val topKey: SkyKey = skyKey("top")
        tester
            .getOrCreate(topKey)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        try {
                            env.getValueOrThrow(catastropheKey, SomeErrorException::class.java)
                        } catch (e: SomeErrorException) {
                            throw object : SkyFunctionException(
                                SomeErrorException("We got: " + e.message),
                                Transience.PERSISTENT
                            ) {}
                        }
                        return null
                    }
                })
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey))

        assertThat(result.getError(topKey).getException()).isInstanceOf(SomeErrorException::class.java)
        assertThat(result.getError(topKey).getException()).hasMessageThat().isEqualTo("We got: bad")
        assertThat(result.getCatastrophe()).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalCycleWithCatastropheAndFailedBubbleUp() {
        val topKey: SkyKey = skyKey("top")
        // Comes alphabetically before "top".
        val cycleKey: SkyKey = skyKey("cycle")
        val catastropheKey: SkyKey = skyKey("catastrophe")
        graph = DeterministicProcessableGraph(InMemoryGraphImpl())
        val topValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("top")
        tester
            .getOrCreate(topKey)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        env.getValue(cycleKey)
                        return if (env.valuesMissing()) null else topValue
                    }
                })
        tester
            .getOrCreate(cycleKey)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        env.getValuesAndExceptions(
                            com.google.common.collect.ImmutableList.of<E?>(
                                cycleKey,
                                catastropheKey
                            )
                        )
                        com.google.common.base.Preconditions.checkState(env.valuesMissing())
                        return null
                    }
                })
        tester
            .getOrCreate(catastropheKey)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(SkyFunctionException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue? {
                        throw object : SkyFunctionException(
                            SomeErrorException("catastrophe"), Transience.TRANSIENT
                        ) {
                            val isCatastrophic: Boolean
                                get() = true
                        }
                    }
                })
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(topKey)
            .hasCycleInfoThat()
            .containsExactly(
                CycleInfo.createCycleInfo(
                    com.google.common.collect.ImmutableList.of<E?>(topKey),
                    com.google.common.collect.ImmutableList.of<E?>(cycleKey)
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parentFailureDoesntAffectChild() {
        graph = InMemoryGraphImpl()
        val parentKey: SkyKey = skyKey("parent")
        tester.getOrCreate(parentKey).setHasError(true)
        val childKey: SkyKey = skyKey("child")
        set("child", "onions")
        tester.getOrCreate(parentKey).addDependency(childKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, parentKey, childKey)
        // Child is guaranteed to complete successfully before parent can run (and fail),
        // since parent depends on it.
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(childKey)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("onions"))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(parentKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun newParentOfErrorShouldHaveError() {
        graph = InMemoryGraphImpl()
        val errorKey: SkyKey = skyKey("error")
        tester.getOrCreate(errorKey).setHasError(true)
        evalValueInError(errorKey)
        val parentKey: SkyKey = skyKey("parent")
        tester.getOrCreate(parentKey).addDependency(errorKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        evalValueInError(parentKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorTwoLevelsDeep() {
        graph = InMemoryGraphImpl()
        val parentKey: SkyKey = skyKey("parent")
        val errorKey: SkyKey = skyKey("error")
        tester.getOrCreate(errorKey).setHasError(true)
        tester.getOrCreate("mid").addDependency(errorKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(parentKey).addDependency("mid").setComputedValue(GraphTester.Companion.CONCATENATE)
        evalValueInError(parentKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueNotUsedInFailFastErrorRecovery() {
        graph = InMemoryGraphImpl()
        val topKey: SkyKey = skyKey("top")
        val recoveryKey: SkyKey = skyKey("midRecovery")
        val badKey: SkyKey = skyKey("bad")

        tester.getOrCreate(topKey).addDependency(recoveryKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester
            .getOrCreate(recoveryKey)
            .addErrorDependency(badKey, com.google.devtools.build.skyframe.GraphTester.StringValue("i recovered"))
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(badKey).setHasError(true)

        var result: EvaluationResult<SkyValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(recoveryKey))
        assertThat(result.errorMap()).isEmpty()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasNoError()
        assertThat(result.get(recoveryKey)).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("i recovered"))

        result = eval<SkyValue?>( /* keepGoing= */false, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasError()
        assertThat(result.keyNames()).isEmpty()
        assertThat(result.errorMap()).hasSize(1)
        assertThat(result.getError(topKey).getException()).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleRootCauses() {
        graph = InMemoryGraphImpl()
        val parentKey: SkyKey = skyKey("parent")
        val errorKey: SkyKey = skyKey("error")
        val errorKey2: SkyKey = skyKey("error2")
        val errorKey3: SkyKey = skyKey("error3")
        tester.getOrCreate(errorKey).setHasError(true)
        tester.getOrCreate(errorKey2).setHasError(true)
        tester.getOrCreate(errorKey3).setHasError(true)
        tester
            .getOrCreate("mid")
            .addDependency(errorKey)
            .addDependency(errorKey2)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        tester
            .getOrCreate(parentKey)
            .addDependency("mid")
            .addDependency(errorKey2)
            .addDependency(errorKey3)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        evalValueInError(parentKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun rootCauseWithNoKeepGoing() {
        graph = InMemoryGraphImpl()
        val parentKey: SkyKey = skyKey("parent")
        val errorKey: SkyKey = skyKey("error")
        tester.getOrCreate(errorKey).setHasError(true)
        tester.getOrCreate("mid").addDependency(errorKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(parentKey).addDependency("mid").setComputedValue(GraphTester.Companion.CONCATENATE)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(false, com.google.common.collect.ImmutableList.of<SkyKey?>(parentKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(parentKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorBubblesToParentsOfTopLevelValue() {
        val parentKey: SkyKey = skyKey("parent")
        val errorKey: SkyKey = skyKey("error")
        val latch: CountDownLatch = CountDownLatch(1)
        graph =
            NotifyingProcessableGraph(
                InMemoryGraphImpl(),
                com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                    if (key.equals(errorKey)
                        && parentKey.equals(context)
                        && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.ADD_REVERSE_DEP && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER
                    ) {
                        latch.countDown()
                    }
                })
        tester
            .getOrCreate(errorKey)
            .setBuilder(
                ChainedFunction(
                    null,  /* waitToFinish= */
                    latch,
                    null,
                    false,  /* value= */
                    null,
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        tester.getOrCreate(parentKey).addDependency(errorKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */false,
                com.google.common.collect.ImmutableList.of<SkyKey?>(parentKey, errorKey)
            )
        assertWithMessage(result.toString()).that(result.errorMap().size()).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noKeepGoingAfterKeepGoingFails() {
        graph = InMemoryGraphImpl()
        val errorKey: SkyKey = skyKey("my_error_value")
        tester.getOrCreate(errorKey).setHasError(true)
        val parentKey: SkyKey = skyKey("parent")
        tester.getOrCreate(parentKey).addDependency(errorKey)
        evalValueInError(parentKey)
        val list: Array<SkyKey?> = arrayOf<SkyKey?>(parentKey)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(false, *list)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasSingletonErrorThat(parentKey)
            .hasExceptionThat()
            .hasMessageThat()
            .isEqualTo(errorKey.toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun twoErrors() {
        graph = InMemoryGraphImpl()
        val firstError: SkyKey = skyKey("error1")
        val secondError: SkyKey = skyKey("error2")
        val firstStart: CountDownLatch = CountDownLatch(1)
        val secondStart: CountDownLatch = CountDownLatch(1)
        tester
            .getOrCreate(firstError)
            .setBuilder(
                ChainedFunction(
                    firstStart,
                    secondStart,  /* notifyFinish= */
                    null,  /* waitForException= */
                    false,  /* value= */
                    null,
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        tester
            .getOrCreate(secondError)
            .setBuilder(
                ChainedFunction(
                    secondStart,
                    firstStart,  /* notifyFinish= */
                    null,  /* waitForException= */
                    false,  /* value= */
                    null,
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */false, firstError, secondError)
        assertWithMessage(result.toString()).that(result.hasError()).isTrue()
        // With keepGoing=false, the eval call will terminate with exactly one error (the first one
        // thrown). But the first one thrown here is non-deterministic since we synchronize the
        // builders so that they run at roughly the same time.
        Truth.assertThat(com.google.common.collect.ImmutableSet.of<Any?>(firstError, secondError))
            .contains(com.google.common.collect.Iterables.getOnlyElement<T?>(result.errorMap().keySet()))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun simpleCycle() {
        graph = InMemoryGraphImpl()
        val aKey: SkyKey = skyKey("a")
        val bKey: SkyKey = skyKey("b")
        tester.getOrCreate(aKey).addDependency(bKey)
        tester.getOrCreate(bKey).addDependency(aKey)
        val errorInfo: ErrorInfo =
            eval<SkyValue?>(false, com.google.common.collect.ImmutableList.of<SkyKey?>(aKey)).getError()
        assertThat(errorInfo.getException()).isNull()
        val cycleInfo: CycleInfo? = com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo())
        assertThat(cycleInfo.cycle).containsExactly(aKey, bKey).inOrder()
        assertThat(cycleInfo.pathToCycle).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cycleWithHead() {
        graph = InMemoryGraphImpl()
        val aKey: SkyKey = skyKey("a")
        val bKey: SkyKey = skyKey("b")
        val topKey: SkyKey = skyKey("top")
        val midKey: SkyKey = skyKey("mid")
        tester.getOrCreate(topKey).addDependency(midKey)
        tester.getOrCreate(midKey).addDependency(aKey)
        tester.getOrCreate(aKey).addDependency(bKey)
        tester.getOrCreate(bKey).addDependency(aKey)
        val errorInfo: ErrorInfo =
            eval<SkyValue?>(false, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey)).getError()
        assertThat(errorInfo.getException()).isNull()
        val cycleInfo: CycleInfo? = com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo())
        assertThat(cycleInfo.cycle).containsExactly(aKey, bKey).inOrder()
        assertThat(cycleInfo.pathToCycle).containsExactly(topKey, midKey).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selfEdgeWithHead() {
        graph = InMemoryGraphImpl()
        val aKey: SkyKey = skyKey("a")
        val topKey: SkyKey = skyKey("top")
        val midKey: SkyKey = skyKey("mid")
        tester.getOrCreate(topKey).addDependency(midKey)
        tester.getOrCreate(midKey).addDependency(aKey)
        tester.getOrCreate(aKey).addDependency(aKey)
        val errorInfo: ErrorInfo =
            eval<SkyValue?>(false, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey)).getError()
        assertThat(errorInfo.getException()).isNull()
        val cycleInfo: CycleInfo? = com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo())
        assertThat(cycleInfo.cycle).containsExactly(aKey).inOrder()
        assertThat(cycleInfo.pathToCycle).containsExactly(topKey, midKey).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cycleWithKeepGoing() {
        graph = InMemoryGraphImpl()
        val aKey: SkyKey = skyKey("a")
        val bKey: SkyKey = skyKey("b")
        val topKey: SkyKey = skyKey("top")
        val midKey: SkyKey = skyKey("mid")
        val goodKey: SkyKey = skyKey("good")
        val goodValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("good")
        tester.set(goodKey, goodValue)
        tester.getOrCreate(topKey).addDependency(midKey)
        tester.getOrCreate(midKey).addDependency(aKey)
        tester.getOrCreate(aKey).addDependency(bKey)
        tester.getOrCreate(bKey).addDependency(aKey)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(true, topKey, goodKey)
        assertThat(result.get(goodKey)).isEqualTo(goodValue)
        assertThat(result.get(topKey)).isNull()
        val errorInfo: ErrorInfo = result.getError(topKey)
        val cycleInfo: CycleInfo? = com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo())
        assertThat(cycleInfo.cycle).containsExactly(aKey, bKey).inOrder()
        assertThat(cycleInfo.pathToCycle).containsExactly(topKey, midKey).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun twoCycles() {
        graph = InMemoryGraphImpl()
        val aKey: SkyKey = skyKey("a")
        val bKey: SkyKey = skyKey("b")
        val cKey: SkyKey = skyKey("c")
        val dKey: SkyKey = skyKey("d")
        val topKey: SkyKey = skyKey("top")
        tester.getOrCreate(topKey).addDependency(aKey).addDependency(cKey)
        tester.getOrCreate(aKey).addDependency(bKey)
        tester.getOrCreate(bKey).addDependency(aKey)
        tester.getOrCreate(cKey).addDependency(dKey)
        tester.getOrCreate(dKey).addDependency(cKey)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(false, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey))
        assertThat(result.get(topKey)).isNull()
        val errorInfo: ErrorInfo = result.getError(topKey)
        val cycles: Iterable<CycleInfo?>? =
            CycleInfo.prepareCycles(
                topKey,
                com.google.common.collect.ImmutableList.of<E?>(
                    CycleInfo.createCycleInfo(com.google.common.collect.ImmutableList.of<E?>(aKey, bKey)),
                    CycleInfo.createCycleInfo(com.google.common.collect.ImmutableList.of<E?>(cKey, dKey))
                )
            )
        Truth.assertThat(cycles)
            .contains(com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo()))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun twoCyclesKeepGoing() {
        graph = InMemoryGraphImpl()
        val aKey: SkyKey = skyKey("a")
        val bKey: SkyKey = skyKey("b")
        val cKey: SkyKey = skyKey("c")
        val dKey: SkyKey = skyKey("d")
        val topKey: SkyKey = skyKey("top")
        tester.getOrCreate(topKey).addDependency(aKey).addDependency(cKey)
        tester.getOrCreate(aKey).addDependency(bKey)
        tester.getOrCreate(bKey).addDependency(aKey)
        tester.getOrCreate(cKey).addDependency(dKey)
        tester.getOrCreate(dKey).addDependency(cKey)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(true, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey))
        assertThat(result.get(topKey)).isNull()
        val errorInfo: ErrorInfo = result.getError(topKey)
        val aCycle: CycleInfo? =
            CycleInfo.createCycleInfo(
                com.google.common.collect.ImmutableList.of<E?>(topKey),
                com.google.common.collect.ImmutableList.of<E?>(aKey, bKey)
            )
        val cCycle: CycleInfo? =
            CycleInfo.createCycleInfo(
                com.google.common.collect.ImmutableList.of<E?>(topKey),
                com.google.common.collect.ImmutableList.of<E?>(cKey, dKey)
            )
        assertThat(errorInfo.getCycleInfo()).containsExactly(aCycle, cCycle)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun triangleBelowHeadCycle() {
        graph = DeterministicProcessableGraph(InMemoryGraphImpl())
        val aKey: SkyKey = skyKey("a")
        val bKey: SkyKey = skyKey("b")
        val cKey: SkyKey = skyKey("c")
        val topKey: SkyKey = skyKey("top")
        tester.getOrCreate(topKey).addDependency(aKey)
        tester.getOrCreate(aKey).addDependency(bKey).addDependency(cKey)
        tester.getOrCreate(bKey).addDependency(cKey)
        tester.getOrCreate(cKey).addDependency(topKey)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(true, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey))
        assertThat(result.get(topKey)).isNull()
        val errorInfo: ErrorInfo = result.getError(topKey)
        val topCycle: CycleInfo? =
            CycleInfo.createCycleInfo(com.google.common.collect.ImmutableList.of<E?>(topKey, aKey, cKey))
        assertThat(errorInfo.getCycleInfo()).containsExactly(topCycle)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun longCycle() {
        graph = InMemoryGraphImpl()
        val aKey: SkyKey = skyKey("a")
        val bKey: SkyKey = skyKey("b")
        val cKey: SkyKey = skyKey("c")
        val topKey: SkyKey = skyKey("top")
        tester.getOrCreate(topKey).addDependency(aKey)
        tester.getOrCreate(aKey).addDependency(bKey)
        tester.getOrCreate(bKey).addDependency(cKey)
        tester.getOrCreate(cKey).addDependency(topKey)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(true, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey))
        assertThat(result.get(topKey)).isNull()
        val errorInfo: ErrorInfo = result.getError(topKey)
        val topCycle: CycleInfo? =
            CycleInfo.createCycleInfo(com.google.common.collect.ImmutableList.of<E?>(topKey, aKey, bKey, cKey))
        assertThat(errorInfo.getCycleInfo()).containsExactly(topCycle)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cycleWithTail() {
        graph = InMemoryGraphImpl()
        val aKey: SkyKey = skyKey("a")
        val bKey: SkyKey = skyKey("b")
        val cKey: SkyKey = skyKey("c")
        val topKey: SkyKey = skyKey("top")
        tester.getOrCreate(topKey).addDependency(aKey).addDependency(cKey)
        tester.getOrCreate(aKey).addDependency(bKey)
        tester.getOrCreate(bKey).addDependency(aKey).addDependency(cKey)
        tester.getOrCreate(cKey)
        tester.set(cKey, com.google.devtools.build.skyframe.GraphTester.StringValue("cValue"))
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(false, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey))
        assertThat(result.get(topKey)).isNull()
        val errorInfo: ErrorInfo = result.getError(topKey)
        val cycleInfo: CycleInfo? = com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo())
        assertThat(cycleInfo.cycle).containsExactly(aKey, bKey).inOrder()
        assertThat(cycleInfo.pathToCycle).containsExactly(topKey).inOrder()
    }

    /** Regression test: "value cannot be ready in a cycle".  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selfEdgeWithExtraChildrenUnderCycle() {
        graph = DeterministicProcessableGraph(InMemoryGraphImpl())
        val aKey: SkyKey = skyKey("a")
        val zKey: SkyKey = skyKey("z")
        val cKey: SkyKey = skyKey("c")
        tester.getOrCreate(aKey).addDependency(zKey)
        tester.getOrCreate(zKey).addDependency(cKey).addDependency(zKey)
        tester.getOrCreate(cKey).addDependency(aKey)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(aKey))
        assertThat(result.get(aKey)).isNull()
        val errorInfo: ErrorInfo = result.getError(aKey)
        val cycleInfo: CycleInfo? = com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo())
        assertThat(cycleInfo.cycle).containsExactly(zKey).inOrder()
        assertThat(cycleInfo.pathToCycle).containsExactly(aKey).inOrder()
    }

    /** Regression test: "value cannot be ready in a cycle".  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cycleWithExtraChildrenUnderCycle() {
        graph = DeterministicProcessableGraph(InMemoryGraphImpl())
        val aKey: SkyKey = skyKey("a")
        val bKey: SkyKey = skyKey("b")
        val cKey: SkyKey = skyKey("c")
        val dKey: SkyKey = skyKey("d")
        tester.getOrCreate(aKey).addDependency(bKey)
        tester.getOrCreate(bKey).addDependency(cKey).addDependency(dKey)
        tester.getOrCreate(cKey).addDependency(aKey)
        tester.getOrCreate(dKey).addDependency(bKey)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(aKey))
        assertThat(result.get(aKey)).isNull()
        val errorInfo: ErrorInfo = result.getError(aKey)
        val cycleInfo: CycleInfo? = com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo())
        assertThat(cycleInfo.cycle).containsExactly(bKey, dKey).inOrder()
        assertThat(cycleInfo.pathToCycle).containsExactly(aKey).inOrder()
    }

    /** Regression test: "value cannot be ready in a cycle".  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cycleAboveIndependentCycle() {
        graph = DeterministicProcessableGraph(InMemoryGraphImpl())
        val aKey: SkyKey = skyKey("a")
        val bKey: SkyKey = skyKey("b")
        val cKey: SkyKey = skyKey("c")
        tester.getOrCreate(aKey).addDependency(bKey)
        tester.getOrCreate(bKey).addDependency(cKey)
        tester.getOrCreate(cKey).addDependency(aKey).addDependency(bKey)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(aKey))
        assertThat(result.get(aKey)).isNull()
        assertThat(result.getError(aKey).getCycleInfo())
            .containsExactly(
                CycleInfo.createCycleInfo(com.google.common.collect.ImmutableList.of<E?>(aKey, bKey, cKey)),
                CycleInfo.createCycleInfo(
                    com.google.common.collect.ImmutableList.of<E?>(aKey),
                    com.google.common.collect.ImmutableList.of<E?>(bKey, cKey)
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueAboveCycleAndExceptionReportsException() {
        graph = InMemoryGraphImpl()
        val aKey: SkyKey = skyKey("a")
        val errorKey: SkyKey = skyKey("error")
        val bKey: SkyKey = skyKey("b")
        tester.getOrCreate(aKey).addDependency(bKey).addDependency(errorKey)
        tester.getOrCreate(bKey).addDependency(bKey)
        tester.getOrCreate(errorKey).setHasError(true)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(aKey))
        assertThat(result.get(aKey)).isNull()
        assertThat(result.getError(aKey).getException()).isNotNull()
        val cycleInfo: CycleInfo? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(result.getError(aKey).getCycleInfo())
        assertThat(cycleInfo.cycle).containsExactly(bKey).inOrder()
        assertThat(cycleInfo.pathToCycle).containsExactly(aKey).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorValueStored() {
        graph = InMemoryGraphImpl()
        val errorKey: SkyKey = skyKey("my_error_value")
        tester.getOrCreate(errorKey).setHasError(true)
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(false, com.google.common.collect.ImmutableList.of<SkyKey?>(errorKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(errorKey)
        // Update value. But builder won't rebuild it.
        tester.getOrCreate(errorKey).setHasError(false)
        tester.set(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("no error?"))
        result = eval<SkyValue?>(false, com.google.common.collect.ImmutableList.of<SkyKey?>(errorKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(errorKey)
    }

    /**
     * Regression test: "OOM in Skyframe cycle detection". We only store the first 20 cycles found
     * below any given root value.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun manyCycles() {
        graph = InMemoryGraphImpl()
        val topKey: SkyKey = skyKey("top")
        for (i in 0..99) {
            val dep: SkyKey = skyKey(i.toString())
            tester.getOrCreate(topKey).addDependency(dep)
            tester.getOrCreate(dep).addDependency(dep)
        }
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey))
        assertThat(result.get(topKey)).isNull()
        assertManyCycles(result.getError(topKey), topKey,  /* selfEdge= */false)
    }

    /**
     * Regression test: "OOM in Skyframe cycle detection". We filter out multiple paths to a cycle
     * that go through the same child value.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun manyPathsToCycle() {
        graph = InMemoryGraphImpl()
        val topKey: SkyKey = skyKey("top")
        val midKey: SkyKey = skyKey("mid")
        val cycleKey: SkyKey = skyKey("cycle")
        tester.getOrCreate(topKey).addDependency(midKey)
        tester.getOrCreate(cycleKey).addDependency(cycleKey)
        for (i in 0..99) {
            val dep: SkyKey = skyKey(i.toString())
            tester.getOrCreate(midKey).addDependency(dep)
            tester.getOrCreate(dep).addDependency(cycleKey)
        }
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey))
        assertThat(result.get(topKey)).isNull()
        val cycleInfo: CycleInfo? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(result.getError(topKey).getCycleInfo())
        assertThat(cycleInfo.cycle).hasSize(1)
        assertThat(cycleInfo.pathToCycle).hasSize(3)
        assertThat(cycleInfo.pathToCycle.subList(0, 2)).containsExactly(topKey, midKey).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun manyUnprocessedValuesInCycle() {
        graph = DeterministicProcessableGraph(InMemoryGraphImpl())
        val lastSelfKey: SkyKey = skyKey("zlastSelf")
        val firstSelfKey: SkyKey = skyKey("afirstSelf")
        val midSelfKey: SkyKey = skyKey("midSelf9")
        // We add firstSelf first so that it is processed last in cycle detection (LIFO), meaning that
        // none of the dep values have to be cleared from firstSelf.
        tester.getOrCreate(firstSelfKey).addDependency(firstSelfKey)
        for (i in 0..99) {
            val firstDep: SkyKey = skyKey("first" + i)
            val midDep: SkyKey = skyKey("midSelf" + i + "dep")
            val lastDep: SkyKey = skyKey("last" + i)
            tester.getOrCreate(firstSelfKey).addDependency(firstDep)
            tester.getOrCreate(midSelfKey).addDependency(midDep)
            tester.getOrCreate(lastSelfKey).addDependency(lastDep)
            if (i == 90) {
                // Most of the deps will be cleared from midSelf.
                tester.getOrCreate(midSelfKey).addDependency(midSelfKey)
            }
            tester.getOrCreate(firstDep).addDependency(firstDep)
            tester.getOrCreate(midDep).addDependency(midDep)
            tester.getOrCreate(lastDep).addDependency(lastDep)
        }
        // All the deps will be cleared from lastSelf.
        tester.getOrCreate(lastSelfKey).addDependency(lastSelfKey)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true,
                com.google.common.collect.ImmutableList.of<SkyKey?>(lastSelfKey, firstSelfKey, midSelfKey)
            )
        assertWithMessage(result.toString()).that(result.keyNames()).isEmpty()
        assertThat(result.errorMap().keySet()).containsExactly(lastSelfKey, firstSelfKey, midSelfKey)

        // Check lastSelfKey.
        val errorInfo: ErrorInfo = result.getError(lastSelfKey)
        assertWithMessage(errorInfo.toString())
            .that(com.google.common.collect.Iterables.size(errorInfo.getCycleInfo()))
            .isEqualTo(1)
        val cycleInfo: CycleInfo? = com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo())
        assertThat(cycleInfo.cycle).containsExactly(lastSelfKey)
        assertThat(cycleInfo.pathToCycle).isEmpty()

        // Check firstSelfKey. It should not have discovered its own self-edge, because there were too
        // many other values before it in the queue.
        assertManyCycles(result.getError(firstSelfKey), firstSelfKey,  /* selfEdge= */false)

        // Check midSelfKey. It should have discovered its own self-edge.
        assertManyCycles(result.getError(midSelfKey), midSelfKey,  /* selfEdge= */true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorValueStoredWithKeepGoing() {
        graph = InMemoryGraphImpl()
        val errorKey: SkyKey = skyKey("my_error_value")
        tester.getOrCreate(errorKey).setHasError(true)
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(true, com.google.common.collect.ImmutableList.of<SkyKey?>(errorKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(errorKey)
        // Update value. But builder won't rebuild it.
        tester.getOrCreate(errorKey).setHasError(false)
        tester.set(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("no error?"))
        result = eval<SkyValue?>(true, com.google.common.collect.ImmutableList.of<SkyKey?>(errorKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(errorKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun continueWithErrorDep() {
        graph = InMemoryGraphImpl()
        val errorKey: SkyKey = skyKey("my_error_value")
        tester.getOrCreate(errorKey).setHasError(true)
        tester.set("after", com.google.devtools.build.skyframe.GraphTester.StringValue("after"))
        val parentKey: SkyKey = skyKey("parent")
        tester
            .getOrCreate(parentKey)
            .addErrorDependency(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("recovered"))
            .setComputedValue(GraphTester.Companion.CONCATENATE)
            .addDependency("after")
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(parentKey))
        assertThat(result.errorMap()).isEmpty()
        assertThat(result.get(parentKey).getValue()).isEqualTo("recoveredafter")
        result = eval<SkyValue?>( /* keepGoing= */false, com.google.common.collect.ImmutableList.of<SkyKey?>(parentKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(parentKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transformErrorDep(@TestParameter keepGoing: Boolean) {
        graph = InMemoryGraphImpl()
        val errorKey: SkyKey = skyKey("my_error_value")
        tester.getOrCreate(errorKey).setHasError(true)
        val parentErrorKey: SkyKey = skyKey("parent")
        tester
            .getOrCreate(parentErrorKey)
            .addErrorDependency(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("recovered"))
            .setHasError(true)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(keepGoing, com.google.common.collect.ImmutableList.of<SkyKey?>(parentErrorKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasSingletonErrorThat(parentErrorKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transformErrorDepOneLevelDownKeepGoing() {
        graph = InMemoryGraphImpl()
        val errorKey: SkyKey = skyKey("my_error_value")
        tester.getOrCreate(errorKey).setHasError(true)
        tester.set("after", com.google.devtools.build.skyframe.GraphTester.StringValue("after"))
        val parentErrorKey: SkyKey = skyKey("parent")
        tester.getOrCreate(parentErrorKey)
            .addErrorDependency(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("recovered"))
        tester.set(parentErrorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("parent value"))
        val topKey: SkyKey = skyKey("top")
        tester
            .getOrCreate(topKey)
            .addDependency(parentErrorKey)
            .addDependency("after")
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey))
        Truth.assertThat(com.google.common.collect.ImmutableList.< String > copyOf < kotlin . String ? > (result.keyNames()))
            .containsExactly("top")
        assertThat(result.get(topKey).getValue()).isEqualTo("parent valueafter")
        assertThat(result.errorMap()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transformErrorDepOneLevelDownNoKeepGoing() {
        graph = InMemoryGraphImpl()
        val errorKey: SkyKey = skyKey("my_error_value")
        tester.getOrCreate(errorKey).setHasError(true)
        tester.set("after", com.google.devtools.build.skyframe.GraphTester.StringValue("after"))
        val parentErrorKey: SkyKey = skyKey("parent")
        tester.getOrCreate(parentErrorKey)
            .addErrorDependency(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("recovered"))
        tester.set(parentErrorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("parent value"))
        val topKey: SkyKey = skyKey("top")
        tester
            .getOrCreate(topKey)
            .addDependency(parentErrorKey)
            .addDependency("after")
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */false, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(topKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorDepDoesntStopOtherDep() {
        graph = InMemoryGraphImpl()
        val errorKey: SkyKey = skyKey("error")
        tester.getOrCreate(errorKey).setHasError(true)
        val result1: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(errorKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result1).hasError()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result1)
            .hasErrorEntryForKeyThat(errorKey)
            .hasExceptionThat()
            .isNotNull()
        val otherKey: SkyKey = skyKey("other")
        tester.getOrCreate(otherKey)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("other"))
        val topKey: SkyKey = skyKey("top")
        val topException: java.lang.Exception = SomeErrorException("top exception")
        val numComputes: AtomicInteger = AtomicInteger(0)
        tester
            .getOrCreate(topKey)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        val values: SkyframeLookupResult =
                            env.getValuesAndExceptions(
                                com.google.common.collect.ImmutableList.of<E?>(
                                    errorKey,
                                    otherKey
                                )
                            )
                        if (numComputes.incrementAndGet() == 1) {
                            assertThat(env.valuesMissing()).isTrue()
                        } else {
                            Truth.assertThat(numComputes.get()).isEqualTo(2)
                            assertThat(env.valuesMissing()).isFalse()
                        }
                        try {
                            values.getOrThrow(errorKey, SomeErrorException::class.java)
                            throw java.lang.AssertionError("Should have thrown")
                        } catch (e: SomeErrorException) {
                            throw object : SkyFunctionException(topException, Transience.PERSISTENT) {}
                        }
                    }
                })
        val result2: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result2).hasError()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result2)
            .hasErrorEntryForKeyThat(topKey)
            .hasExceptionThat()
            .isSameInstanceAs(topException)
        Truth.assertThat(numComputes.get()).isEqualTo(2)
    }

    private fun createCycleKey(keyName: String?, isCycleNodePartialReevaluation: Boolean): SkyKey? {
        if (isCycleNodePartialReevaluation) {
            tester.putDelegateFunction(PartialReevaluationKey.Companion.FUNCTION_NAME)
            return PartialReevaluationKey(keyName)
        }
        return skyKey(keyName)
    }

    /** Make sure that multiple unfinished children can be cleared from a cycle value.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cycleWithMultipleUnfinishedChildren(
        @TestParameter isCycleNodePartialReevaluation: Boolean
    ) {
        graph = DeterministicProcessableGraph(InMemoryGraphImpl())
        val cycleKey: SkyKey? = createCycleKey("cycle", isCycleNodePartialReevaluation)
        val midKey: SkyKey = skyKey("mid")
        val topKey: SkyKey = skyKey("top")
        val selfEdge1: SkyKey = skyKey("selfEdge1")
        val selfEdge2: SkyKey = skyKey("selfEdge2")
        tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        // selfEdge* come before cycleKey, so cycleKey's path will be checked first (LIFO), and the
        // cycle with mid will be detected before the selfEdge* cycles are.
        tester
            .getOrCreate(midKey)
            .addDependency(selfEdge1)
            .addDependency(selfEdge2)
            .addDependency(cycleKey)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(cycleKey).addDependency(midKey)
        tester.getOrCreate(selfEdge1).addDependency(selfEdge1)
        tester.getOrCreate(selfEdge2).addDependency(selfEdge2)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableSet.of<SkyKey?>(topKey))
        assertThat(result.errorMap().keySet()).containsExactly(topKey)
        val cycleInfos: Iterable<CycleInfo?> = result.getError(topKey).getCycleInfo()
        val cycleInfo: CycleInfo? = com.google.common.collect.Iterables.getOnlyElement<CycleInfo?>(cycleInfos)
        assertThat(cycleInfo.pathToCycle).containsExactly(topKey)
        assertThat(cycleInfo.cycle).containsExactly(midKey, cycleKey)
    }

    /**
     * Regression test: "value in cycle depends on error". The mid value will have two parents -- top
     * and cycle. Error bubbles up from mid to cycle, and we should detect cycle.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cycleAndErrorInBubbleUp(
        @TestParameter keepGoing: Boolean, @TestParameter isCycleNodePartialReevaluation: Boolean
    ) {
        graph = DeterministicProcessableGraph(InMemoryGraphImpl())
        tester = GraphTester()
        val errorKey: SkyKey = skyKey("error")
        val cycleKey: SkyKey? = createCycleKey("cycle", isCycleNodePartialReevaluation)
        val midKey: SkyKey = skyKey("mid")
        val topKey: SkyKey = skyKey("top")
        tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester
            .getOrCreate(midKey)
            .addDependency(errorKey)
            .addDependency(cycleKey)
            .setComputedValue(GraphTester.Companion.CONCATENATE)

        // We need to ensure that cycle value has finished its work, and we have recorded dependencies
        val cycleFinish: CountDownLatch = CountDownLatch(1)
        tester
            .getOrCreate(cycleKey)
            .setBuilder(
                ChainedFunction(
                    null,
                    null,
                    cycleFinish,
                    false,
                    com.google.devtools.build.skyframe.GraphTester.StringValue(""),
                    com.google.common.collect.ImmutableSet.of<SkyKey?>(midKey)
                )
            )
        tester
            .getOrCreate(errorKey)
            .setBuilder(
                ChainedFunction(
                    null,
                    cycleFinish,
                    null,  /* waitForException= */
                    false,
                    null,
                    com.google.common.collect.ImmutableSet.of<SkyKey?>()
                )
            )

        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(keepGoing, com.google.common.collect.ImmutableSet.of<SkyKey?>(topKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasSingletonErrorThat(topKey)
            .hasCycleInfoThat()
            .containsExactly(
                CycleInfo.createCycleInfo(
                    com.google.common.collect.ImmutableList.of<E?>(topKey),
                    com.google.common.collect.ImmutableList.of<E?>(midKey, cycleKey)
                )
            )
    }

    /**
     * Regression test: "value in cycle depends on error". We add another value that won't finish
     * building before the threadpool shuts down, to check that the cycle detection can handle
     * unfinished values.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cycleAndErrorAndOtherInBubbleUp() {
        graph = DeterministicProcessableGraph(InMemoryGraphImpl())
        tester = GraphTester()
        val errorKey: SkyKey = skyKey("error")
        val cycleKey: SkyKey = skyKey("cycle")
        val midKey: SkyKey = skyKey("mid")
        val topKey: SkyKey = skyKey("top")
        tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        // We should add cycleKey first and errorKey afterwards. Otherwise there is a chance that
        // during error propagation cycleKey will not be processed, and we will not detect the cycle.
        tester
            .getOrCreate(midKey)
            .addDependency(errorKey)
            .addDependency(cycleKey)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        val otherTop: SkyKey = skyKey("otherTop")
        val topStartAndCycleFinish: CountDownLatch = CountDownLatch(2)
        // In nokeep_going mode, otherTop will wait until the threadpool has received an exception,
        // then request its own dep. This guarantees that there is a value that is not finished when
        // cycle detection happens.
        tester
            .getOrCreate(otherTop)
            .setBuilder(
                ChainedFunction(
                    topStartAndCycleFinish,
                    CountDownLatch(0),
                    null,  /* waitForException= */
                    true,
                    com.google.devtools.build.skyframe.GraphTester.StringValue("never returned"),
                    com.google.common.collect.ImmutableSet.of<SkyKey?>(skyKey("dep that never builds"))
                )
            )

        tester
            .getOrCreate(cycleKey)
            .setBuilder(
                ChainedFunction(
                    null,
                    null,
                    topStartAndCycleFinish,  /* waitForException= */
                    false,
                    com.google.devtools.build.skyframe.GraphTester.StringValue(""),
                    com.google.common.collect.ImmutableSet.of<SkyKey?>(midKey)
                )
            )
        // error waits until otherTop starts and cycle finishes, to make sure otherTop will request
        // its dep before the threadpool shuts down.
        tester
            .getOrCreate(errorKey)
            .setBuilder(
                ChainedFunction(
                    null,
                    topStartAndCycleFinish,
                    null,  /* waitForException= */
                    false,
                    null,
                    com.google.common.collect.ImmutableSet.of<SkyKey?>()
                )
            )
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */false,
                com.google.common.collect.ImmutableSet.of<SkyKey?>(topKey, otherTop)
            )
        assertThat(result.errorMap().keySet()).containsExactly(topKey)
        val cycleInfos: Iterable<CycleInfo?>? = result.getError(topKey).getCycleInfo()
        Truth.assertThat(cycleInfos).isNotEmpty()
        val cycleInfo: CycleInfo? = com.google.common.collect.Iterables.getOnlyElement<CycleInfo?>(cycleInfos)
        assertThat(cycleInfo.pathToCycle).containsExactly(topKey)
        assertThat(cycleInfo.cycle).containsExactly(midKey, cycleKey)
    }

    /**
     * Regression test: "value in cycle depends on error". Here, we add an additional top-level key in
     * error, just to mix it up.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cycleAndErrorAndError(
        @TestParameter keepGoing: Boolean, @TestParameter isCycleNodePartialReevaluation: Boolean
    ) {
        graph = DeterministicProcessableGraph(InMemoryGraphImpl())
        tester = GraphTester()
        val errorKey: SkyKey = skyKey("error")
        val cycleKey: SkyKey? = createCycleKey("cycle", isCycleNodePartialReevaluation)
        val midKey: SkyKey = skyKey("mid")
        val topKey: SkyKey = skyKey("top")
        tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester
            .getOrCreate(midKey)
            .addDependency(errorKey)
            .addDependency(cycleKey)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        val otherTop: SkyKey = skyKey("otherTop")
        val topStartAndCycleFinish: CountDownLatch = CountDownLatch(2)
        // In nokeep_going mode, otherTop will wait until the threadpool has received an exception,
        // then throw its own exception. This guarantees that its exception will not be the one
        // bubbling up, but that there is a top-level value with an exception by the time the bubbling
        // up starts.
        tester
            .getOrCreate(otherTop)
            .setBuilder(
                ChainedFunction(
                    topStartAndCycleFinish,
                    CountDownLatch(0),
                    null,  /* waitForException= */
                    !keepGoing,
                    null,
                    com.google.common.collect.ImmutableSet.of<SkyKey?>()
                )
            )
        // error waits until otherTop starts and cycle finishes, to make sure otherTop will request
        // its dep before the threadpool shuts down.
        tester
            .getOrCreate(errorKey)
            .setBuilder(
                ChainedFunction(
                    null,
                    topStartAndCycleFinish,
                    null,  /* waitForException= */
                    false,
                    null,
                    com.google.common.collect.ImmutableSet.of<SkyKey?>()
                )
            )
        tester
            .getOrCreate(cycleKey)
            .setBuilder(
                ChainedFunction(
                    null,
                    null,
                    topStartAndCycleFinish,  /* waitForException= */
                    false,
                    com.google.devtools.build.skyframe.GraphTester.StringValue(""),
                    com.google.common.collect.ImmutableSet.of<SkyKey?>(midKey)
                )
            )
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(keepGoing, com.google.common.collect.ImmutableSet.of<SkyKey?>(topKey, otherTop))
        if (keepGoing) {
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorMapThat().hasSize(2)
        }
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(topKey)
            .hasCycleInfoThat()
            .containsExactly(
                CycleInfo.createCycleInfo(
                    com.google.common.collect.ImmutableList.of<E?>(topKey),
                    com.google.common.collect.ImmutableList.of<E?>(midKey, cycleKey)
                )
            )
    }

    @org.junit.Test
    fun testFunctionCrashTrace() {
        class ChildFunction : SkyFunction {
            public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue? {
                throw java.lang.IllegalStateException("I WANT A PONY!!!")
            }
        }

        class ParentFunction : SkyFunction {
            @Throws(java.lang.InterruptedException::class)
            public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                val dep: SkyValue? = env.getValue(ChildKey.Companion.create("billy the kid"))
                if (dep == null) {
                    return null
                }
                throw java.lang.IllegalStateException() // Should never get here.
            }
        }

        val skyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?> =
            com.google.common.collect.ImmutableMap.of<SkyFunctionName?, V?>(
                CHILD_TYPE, ChildFunction(),
                PARENT_TYPE, ParentFunction()
            )
        val evaluator: ParallelEvaluator = makeEvaluator(InMemoryGraphImpl(), skyFunctions, false)

        val e: java.lang.RuntimeException? =
            org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
                java.lang.RuntimeException::class.java,
                org.junit.function.ThrowingRunnable {
                    evaluator.eval(
                        com.google.common.collect.ImmutableList.of<E?>(
                            ParentKey.Companion.create("octodad")
                        )
                    )
                })
        Truth.assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("I WANT A PONY!!!")
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "Unrecoverable error while evaluating node 'child:billy the kid' "
                        + "(requested by nodes 'parent:octodad')"
            )
    }

    private class SomeOtherErrorException(msg: String?) : java.lang.Exception(msg)

    /**
     * This and the following tests are in response to a bug: "Skyframe error propagation model is
     * problematic". They ensure that exceptions a child throws that a value does not specify it can
     * handle in getValueOrThrow do not cause a crash.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unexpectedErrorDep(@TestParameter keepGoing: Boolean) {
        graph = InMemoryGraphImpl()
        val errorKey: SkyKey = skyKey("my_error_value")
        val exception: SomeOtherErrorException =
            com.google.devtools.build.skyframe.ParallelEvaluatorTest.SomeOtherErrorException("error exception")
        tester
            .getOrCreate(errorKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    throw object : SkyFunctionException(exception, Transience.PERSISTENT) {}
                })
        val topKey: SkyKey = skyKey("top")
        tester
            .getOrCreate(topKey)
            .addErrorDependency(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("recovered"))
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(keepGoing, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(topKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unexpectedErrorDepOneLevelDown(@TestParameter keepGoing: Boolean) {
        graph = InMemoryGraphImpl()
        val errorKey: SkyKey = skyKey("my_error_value")
        val exception: SomeErrorException = SomeErrorException("error exception")
        val topException: SomeErrorException = SomeErrorException("top exception")
        val topValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("top")
        tester
            .getOrCreate(errorKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    throw GenericFunctionException(exception, Transience.PERSISTENT)
                })
        val topKey: SkyKey = skyKey("top")
        val parentKey: SkyKey = skyKey("parent")
        tester.getOrCreate(parentKey).addDependency(errorKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester
            .getOrCreate(topKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    try {
                        if (env.getValueOrThrow(parentKey, SomeErrorException::class.java) == null) {
                            return@setBuilder null
                        }
                    } catch (e: SomeErrorException) {
                        Truth.assertWithMessage(e.toString()).that(e).isEqualTo(exception)
                    }
                    if (keepGoing) {
                        return@setBuilder topValue
                    } else {
                        throw GenericFunctionException(topException, Transience.PERSISTENT)
                    }
                })
        tester
            .getOrCreate(topKey)
            .addErrorDependency(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("recovered"))
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(keepGoing, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey))
        if (!keepGoing) {
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(topKey)
        } else {
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasNoError()
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(topKey)
                .isSameInstanceAs(topValue)
        }
    }

    /**
     * Exercises various situations involving groups of deps that overlap -- request one group, then
     * request another group that has a dep in common with the first group.
     * 
     * @param sameFirst whether the dep in common in the two groups should be the first dep.
     * @param twoCalls whether the two groups should be requested in two different builder calls.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sameDepInTwoGroups(@TestParameter sameFirst: Boolean, @TestParameter twoCalls: Boolean) {
        graph = InMemoryGraphImpl()
        val topKey: SkyKey = skyKey("top")
        val leaves: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()
        for (i in 1..3) {
            val leaf: SkyKey = skyKey("leaf" + i)
            leaves.add(leaf)
            tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leaf" + i))
        }
        val leaf4: SkyKey = skyKey("leaf4")
        tester.set(leaf4, com.google.devtools.build.skyframe.GraphTester.StringValue("leaf" + 4))
        tester
            .getOrCreate(topKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    env.getValuesAndExceptions(leaves)
                    if (twoCalls && env.valuesMissing()) {
                        return@setBuilder null
                    }
                    val first: SkyKey? = if (sameFirst) leaves.get(0) else leaf4
                    val second: SkyKey? = if (sameFirst) leaf4 else leaves.get(2)
                    val secondRequest: com.google.common.collect.ImmutableList<SkyKey?> =
                        com.google.common.collect.ImmutableList.of<SkyKey?>(first, second)
                    env.getValuesAndExceptions(secondRequest)
                    if (env.valuesMissing()) {
                        return@setBuilder null
                    }
                    com.google.devtools.build.skyframe.GraphTester.StringValue("top")
                })
        eval( /* keepGoing= */false, topKey)
        assertThat(
            eval( /* keepGoing= */false,
                topKey
            )
        ).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("top"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getValueOrThrowWithErrors(@TestParameter keepGoing: Boolean) {
        graph = InMemoryGraphImpl()
        val parentKey: SkyKey = skyKey("parent")
        val errorDep: SkyKey = skyKey("errorChild")
        val childExn: SomeErrorException = SomeErrorException("child error")
        tester
            .getOrCreate(errorDep)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    throw GenericFunctionException(childExn, Transience.PERSISTENT)
                })
        val deps: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()
        for (i in 1..3) {
            val dep: SkyKey = skyKey("child" + i)
            deps.add(dep)
            tester.set(dep, com.google.devtools.build.skyframe.GraphTester.StringValue("child" + i))
        }
        val parentExn: SomeErrorException = SomeErrorException("parent error")
        tester
            .getOrCreate(parentKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    try {
                        val value: SkyValue? = env.getValueOrThrow(errorDep, SomeErrorException::class.java)
                        if (value == null) {
                            return@setBuilder null
                        }
                    } catch (e: SomeErrorException) {
                        // Recover from the child error.
                    }
                    env.getValuesAndExceptions(deps)
                    if (env.valuesMissing()) {
                        return@setBuilder null
                    }
                    throw GenericFunctionException(parentExn, Transience.PERSISTENT)
                })
        val evaluationResult: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(keepGoing, com.google.common.collect.ImmutableList.of<SkyKey?>(parentKey))
        assertThat(evaluationResult.hasError()).isTrue()
        assertThat(evaluationResult.getError().getException())
            .isEqualTo(if (keepGoing) parentExn else childExn)
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val valuesAndExceptions: Unit
        get() {
            graph = InMemoryGraphImpl()
            val otherKey: SkyKey = skyKey("other")
            val anotherKey: SkyKey = skyKey("another")
            val errorExpectedKey: SkyKey = skyKey("errorExpected")
            val topKey: SkyKey = skyKey("top")
            val topException: java.lang.Exception = SomeErrorException("top exception")
            val numComputes: AtomicInteger = AtomicInteger(0)

            tester.set(otherKey, com.google.devtools.build.skyframe.GraphTester.StringValue("other"))
            tester.set(anotherKey, com.google.devtools.build.skyframe.GraphTester.StringValue("another"))
            tester.getOrCreate(errorExpectedKey).setHasError(true)
            tester
                .getOrCreate(topKey)
                .setBuilder(
                    object : SkyFunction() {
                        @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
                        public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                            val depKeys: com.google.common.collect.ImmutableList<SkyKey?> =
                                com.google.common.collect.ImmutableList.of<SkyKey?>(
                                    otherKey,
                                    anotherKey,
                                    errorExpectedKey
                                )
                            val skyframeLookupResult: SkyframeLookupResult = env.getValuesAndExceptions(depKeys)
                            if (numComputes.incrementAndGet() == 1) {
                                assertThat(env.valuesMissing()).isTrue()
                                for (depKey in depKeys.reverse()) {
                                    try {
                                        assertThat(
                                            skyframeLookupResult.getOrThrow(
                                                depKey,
                                                SomeErrorException::class.java
                                            )
                                        )
                                            .isNull()
                                    } catch (e: SomeErrorException) {
                                        throw java.lang.AssertionError("should not have thrown", e)
                                    }
                                }
                                return null
                            } else {
                                Truth.assertThat(numComputes.get()).isEqualTo(2)
                                val value1: SkyValue? = skyframeLookupResult.get(otherKey)
                                assertThat(value1).isNotNull()
                                assertThat(env.valuesMissing()).isFalse()
                                try {
                                    val value2: SkyValue? =
                                        skyframeLookupResult.getOrThrow(anotherKey, SomeErrorException::class.java)
                                    assertThat(value2).isNotNull()
                                    assertThat(env.valuesMissing()).isFalse()
                                } catch (e: SomeErrorException) {
                                    throw java.lang.AssertionError("Should not have thrown", e)
                                }
                                try {
                                    skyframeLookupResult.getOrThrow(errorExpectedKey, SomeErrorException::class.java)
                                    throw java.lang.AssertionError("Should throw")
                                } catch (e: SomeErrorException) {
                                    assertThat(env.valuesMissing()).isFalse()
                                }
                                throw object : SkyFunctionException(topException, Transience.PERSISTENT) {}
                            }
                        }
                    })
            val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
                eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(topKey))

            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasError()
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
                .hasErrorEntryForKeyThat(topKey)
                .hasExceptionThat()
                .isSameInstanceAs(topException)
            Truth.assertThat(numComputes.get()).isEqualTo(2)
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val valuesAndExceptionsWithErrors: Unit
        get() {
            graph = InMemoryGraphImpl()
            val childKey: SkyKey = skyKey("error")
            val childExn: SomeErrorException = SomeErrorException("child error")
            tester
                .getOrCreate(childKey)
                .setBuilder(
                    SkyFunction { skyKey, env ->
                        throw GenericFunctionException(childExn, Transience.PERSISTENT)
                    })
            val parentKey: SkyKey = skyKey("parent")
            val numComputes: AtomicInteger = AtomicInteger(0)
            tester
                .getOrCreate(parentKey)
                .setBuilder(
                    SkyFunction { skyKey, env ->
                        try {
                            val value: SkyValue? =
                                env.getValuesAndExceptions(com.google.common.collect.ImmutableList.of<E?>(childKey))
                                    .getOrThrow(
                                        childKey,
                                        com.google.devtools.build.skyframe.ParallelEvaluatorTest.SomeOtherErrorException::class.java
                                    )
                            assertThat(value).isNull()
                        } catch (e: SomeOtherErrorException) {
                            throw java.lang.AssertionError("Should not have thrown", e)
                        }
                        numComputes.incrementAndGet()
                        assertThat(env.valuesMissing()).isTrue()
                        null
                    })
            val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
                eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(parentKey))
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasError()
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
                .hasErrorEntryForKeyThat(parentKey)
                .hasExceptionThat()
                .isSameInstanceAs(childExn)
            Truth.assertThat(numComputes.get()).isEqualTo(2)
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declareDependenciesAndCheckIfValuesMissing() {
        graph = InMemoryGraphImpl()
        val childKey: SkyKey = skyKey("error")
        val childExn: SomeErrorException = SomeErrorException("child error")
        tester
            .getOrCreate(childKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    throw GenericFunctionException(childExn, Transience.PERSISTENT)
                })
        val parentKey: SkyKey = skyKey("parent")
        val numComputes: AtomicInteger = AtomicInteger(0)
        val mockReporter: BugReporter? = Mockito.mock<BugReporter?>(BugReporter::class.java)
        tester
            .getOrCreate(parentKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    val valuesMissing: Boolean =
                        GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing(
                            env,
                            com.google.common.collect.ImmutableList.of<E?>(childKey),
                            com.google.devtools.build.skyframe.ParallelEvaluatorTest.SomeOtherErrorException::class.java,  /* exceptionClass2= */
                            null,
                            mockReporter
                        )
                    numComputes.incrementAndGet()
                    Truth.assertThat(valuesMissing).isTrue()
                    null
                })
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(parentKey))
        Mockito.verify<BugReporter?>(mockReporter)
            .logUnexpected("Value for: '%s' was missing, this should never happen", childKey)
        Mockito.verifyNoMoreInteractions(mockReporter)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(parentKey)
            .hasExceptionThat()
            .isSameInstanceAs(childExn)
        Truth.assertThat(numComputes.get()).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declareDependenciesAndCheckIfNotValuesMissing() {
        graph = InMemoryGraphImpl()
        val otherKey: SkyKey = skyKey("other")
        val childKey: SkyKey = skyKey("error")
        val childExn: SomeErrorException = SomeErrorException("child error")
        tester.set(otherKey, com.google.devtools.build.skyframe.GraphTester.StringValue("other"))
        tester
            .getOrCreate(childKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    throw GenericFunctionException(childExn, Transience.PERSISTENT)
                })
        val parentKey: SkyKey = skyKey("parent")
        val numComputes: AtomicInteger = AtomicInteger(0)
        tester
            .getOrCreate(parentKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    if (numComputes.incrementAndGet() == 1) {
                        val valuesMissing: Boolean =
                            GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing(
                                env,
                                com.google.common.collect.ImmutableList.of<E?>(otherKey, childKey),
                                SomeErrorException::class.java
                            )
                        Truth.assertThat(valuesMissing).isTrue()
                    } else {
                        val valuesMissing: Boolean =
                            GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing(
                                env,
                                com.google.common.collect.ImmutableList.of<E?>(otherKey, childKey),
                                SomeErrorException::class.java
                            )
                        Truth.assertThat(valuesMissing).isFalse()
                    }
                    null
                })
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(parentKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(parentKey)
            .hasExceptionThat()
            .isSameInstanceAs(childExn)
        Truth.assertThat(numComputes.get()).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validateExceptionTypeInDifferentPosition(
        @TestParameter("0", "1", "2", "3") exceptionIndex: Int
    ) {
        val exceptions: com.google.common.collect.ImmutableList<java.lang.Class<out java.lang.Exception?>?> =
            com.google.common.collect.ImmutableList.of<java.lang.Class<out java.lang.Exception?>?>(
                java.lang.Exception::class.java,
                com.google.devtools.build.skyframe.ParallelEvaluatorTest.SomeOtherErrorException::class.java,
                IOException::class.java,
                SomeErrorException::class.java
            )
        graph = InMemoryGraphImpl()
        val otherKey: SkyKey = skyKey("other")
        tester.set(otherKey, com.google.devtools.build.skyframe.GraphTester.StringValue("other"))
        val parentKey: SkyKey = skyKey("parent")
        val parentExn: SomeErrorException = SomeErrorException("parent error")
        tester
            .getOrCreate(parentKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    val illegalStateException: java.lang.IllegalStateException? =
                        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                            java.lang.IllegalStateException::class.java,
                            org.junit.function.ThrowingRunnable {
                                env.getValueOrThrow(
                                    otherKey,
                                    exceptions.get(exceptionIndex % 4),
                                    exceptions.get((exceptionIndex + 1) % 4),
                                    exceptions.get((exceptionIndex + 2) % 4),
                                    exceptions.get((exceptionIndex + 3) % 4)
                                )
                            })
                    Truth.assertThat(illegalStateException)
                        .hasMessageThat()
                        .contains("is a supertype of RuntimeException")
                    assertThat(env.valuesMissing()).isFalse()
                    throw GenericFunctionException(parentExn, Transience.PERSISTENT)
                })
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(parentKey))
        assertThat(result.hasError()).isTrue()
        assertThat(result.getError().getException()).isEqualTo(parentExn)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validateExceptionTypeWithDifferentException(
        @TestParameter exceptionOption: ExceptionOption
    ) {
        graph = InMemoryGraphImpl()
        val otherKey: SkyKey = skyKey("other")
        tester.set(otherKey, com.google.devtools.build.skyframe.GraphTester.StringValue("other"))
        val parentKey: SkyKey = skyKey("parent")
        val parentExn: SomeErrorException = SomeErrorException("parent error")
        tester
            .getOrCreate(parentKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    val illegalStateException: java.lang.IllegalStateException? =
                        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                            java.lang.IllegalStateException::class.java,
                            org.junit.function.ThrowingRunnable {
                                env.getValueOrThrow(
                                    otherKey,
                                    exceptionOption.exceptionClass
                                )
                            })
                    Truth.assertThat(illegalStateException)
                        .hasMessageThat()
                        .contains(exceptionOption.errorMessage)
                    assertThat(env.valuesMissing()).isFalse()
                    throw GenericFunctionException(parentExn, Transience.PERSISTENT)
                })
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(parentKey))
        assertThat(result.hasError()).isTrue()
        assertThat(result.getError().getException()).isEqualTo(parentExn)
    }

    private enum class ExceptionOption(
        exceptionClass: java.lang.Class<out java.lang.Exception?>,
        errorMessage: String
    ) {
        EXCEPTION(java.lang.Exception::class.java, "is a supertype of RuntimeException"),
        NULL_POINTER_EXCEPTION(java.lang.NullPointerException::class.java, "is a subtype of RuntimeException"),
        INTERRUPTED_EXCEPTION(java.lang.InterruptedException::class.java, "is a subtype of InterruptedException");

        val exceptionClass: java.lang.Class<out java.lang.Exception?>?
        val errorMessage: String?

        init {
            this.exceptionClass = exceptionClass
            this.errorMessage = errorMessage
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicateCycles() {
        graph = InMemoryGraphImpl()
        val grandparentKey: SkyKey = skyKey("grandparent")
        val parentKey1: SkyKey = skyKey("parent1")
        val parentKey2: SkyKey = skyKey("parent2")
        val loopKey1: SkyKey = skyKey("loop1")
        val loopKey2: SkyKey = skyKey("loop2")
        tester.getOrCreate(loopKey1).addDependency(loopKey2)
        tester.getOrCreate(loopKey2).addDependency(loopKey1)
        tester.getOrCreate(parentKey1).addDependency(loopKey1)
        tester.getOrCreate(parentKey2).addDependency(loopKey2)
        tester.getOrCreate(grandparentKey).addDependency(parentKey1)
        tester.getOrCreate(grandparentKey).addDependency(parentKey2)

        val errorInfo: ErrorInfo = evalValueInError(grandparentKey)
        val cycles: MutableList<com.google.common.collect.ImmutableList<SkyKey?>?> =
            java.util.ArrayList<com.google.common.collect.ImmutableList<SkyKey?>?>()
        for (cycleInfo in errorInfo.getCycleInfo()) {
            cycles.add(cycleInfo.cycle)
        }
        // Skyframe doesn't automatically dedupe cycles that are the same except for entry point.
        Truth.assertThat(cycles).hasSize(2)
        var numUniqueCycles = 0
        val cycleDeduper: CycleDeduper<SkyKey?> = CycleDeduper()
        for (cycle in cycles) {
            if (!cycleDeduper.alreadySeen(cycle)) {
                numUniqueCycles++
            }
        }
        Truth.assertThat(numUniqueCycles).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun signalValueEnqueuedAndEvaluated() {
        val enqueuedValues: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
        val evaluatedValues: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
        val progressReceiver: EvaluationProgressReceiver =
            object : EvaluationProgressReceiver() {
                public override fun enqueueing(skyKey: SkyKey?) {
                    enqueuedValues.add(skyKey)
                }

                public override fun evaluated(
                    skyKey: SkyKey?,
                    state: EvaluationState?,
                    newValue: SkyValue?,
                    newError: ErrorInfo?,
                    directDeps: GroupedDeps?
                ) {
                    evaluatedValues.add(skyKey)
                }
            }

        val reporter: ExtendedEventHandler =
            com.google.devtools.build.lib.events.Reporter(
                EventBusEventHandler.createWithNewEventBus(),
                com.google.devtools.build.lib.events.EventHandler { e: com.google.devtools.build.lib.events.Event? ->
                    throw java.lang.IllegalStateException()
                })

        val evaluator: MemoizingEvaluator =
            InMemoryMemoizingEvaluator(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    GraphTester.Companion.NODE_TYPE,
                    tester.getFunction()
                ),
                SequencedRecordingDifferencer(),
                progressReceiver
            )

        tester
            .getOrCreate(skyKey("top1"))
            .setComputedValue(GraphTester.Companion.CONCATENATE)
            .addDependency(skyKey("d1"))
            .addDependency(skyKey("d2"))
        tester.getOrCreate(skyKey("top2")).setComputedValue(GraphTester.Companion.CONCATENATE)
            .addDependency(skyKey("d3"))
        tester.getOrCreate(skyKey("top3"))
        Truth.assertThat(enqueuedValues).isEmpty()
        Truth.assertThat(evaluatedValues).isEmpty()

        tester.set(skyKey("d1"), com.google.devtools.build.skyframe.GraphTester.StringValue("1"))
        tester.set(skyKey("d2"), com.google.devtools.build.skyframe.GraphTester.StringValue("2"))
        tester.set(skyKey("d3"), com.google.devtools.build.skyframe.GraphTester.StringValue("3"))

        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(200)
                .setEventHandler(reporter)
                .build()
        evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(skyKey("top1")), evaluationContext)
        Truth.assertThat(enqueuedValues)
            .containsExactlyElementsIn(
                GraphTester.Companion.toSkyKeys(useSkipBatchPrefetchKey, "top1", "d1", "d2")
            )
        Truth.assertThat(evaluatedValues)
            .containsExactlyElementsIn(
                GraphTester.Companion.toSkyKeys(useSkipBatchPrefetchKey, "top1", "d1", "d2")
            )
        enqueuedValues.clear()
        evaluatedValues.clear()

        evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(skyKey("top2")), evaluationContext)
        Truth.assertThat(enqueuedValues)
            .containsExactlyElementsIn(GraphTester.Companion.toSkyKeys(useSkipBatchPrefetchKey, "top2", "d3"))
        Truth.assertThat(evaluatedValues)
            .containsExactlyElementsIn(GraphTester.Companion.toSkyKeys(useSkipBatchPrefetchKey, "top2", "d3"))
        enqueuedValues.clear()
        evaluatedValues.clear()

        evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(skyKey("top1")), evaluationContext)
        Truth.assertThat(enqueuedValues).isEmpty()
        Truth.assertThat(evaluatedValues)
            .containsExactlyElementsIn(GraphTester.Companion.toSkyKeys(useSkipBatchPrefetchKey, "top1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun runDepOnErrorHaltsNoKeepGoingBuildEagerly(
        @TestParameter childErrorCached: Boolean, @TestParameter handleChildError: Boolean
    ) {
        graph = InMemoryGraphImpl()
        val parentKey: SkyKey = skyKey("parent")
        val childKey: SkyKey = skyKey("child")
        tester.getOrCreate(childKey).setHasError( /* hasError= */true)
        // The parent should be built exactly twice: once during normal evaluation and once
        // during error bubbling.
        val numParentInvocations: AtomicInteger = AtomicInteger(0)
        tester
            .getOrCreate(parentKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    val invocations: Int = numParentInvocations.incrementAndGet()
                    if (handleChildError) {
                        try {
                            val value: SkyValue? = env.getValueOrThrow(childKey, SomeErrorException::class.java)
                            // On the first invocation, either the child error should already be cached and
                            // not propagated, or it should be computed freshly and not propagated. On the
                            // second build (error bubbling), the child error should be propagated.
                            Truth.assertWithMessage("bogus non-null value %s", value).that(value == null).isTrue()
                            Truth.assertWithMessage("parent incorrectly re-computed during normal evaluation")
                                .that(invocations)
                                .isEqualTo(1)
                            Truth.assertWithMessage("child error not propagated during error bubbling")
                                .that(env.inErrorBubbling())
                                .isFalse()
                            return@setBuilder value
                        } catch (e: SomeErrorException) {
                            Truth.assertWithMessage("child error propagated during normal evaluation")
                                .that(env.inErrorBubbling())
                                .isTrue()
                            Truth.assertThat(invocations).isEqualTo(2)
                            return@setBuilder null
                        }
                    } else {
                        if (invocations == 1) {
                            Truth.assertWithMessage("parent's first computation should be during normal evaluation")
                                .that(env.inErrorBubbling())
                                .isFalse()
                            return@setBuilder env.getValue(childKey)
                        } else {
                            Truth.assertThat(invocations).isEqualTo(2)
                            Truth.assertWithMessage("parent incorrectly re-computed during normal evaluation")
                                .that(env.inErrorBubbling())
                                .isTrue()
                            return@setBuilder env.getValue(childKey)
                        }
                    }
                })
        if (childErrorCached) {
            // Ensure that the child is already in the graph.
            evalValueInError(childKey)
        }
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */false, com.google.common.collect.ImmutableList.of<SkyKey?>(parentKey))
        Truth.assertThat(numParentInvocations.get()).isEqualTo(2)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(parentKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun raceConditionWithNoKeepGoingErrors_FutureError() {
        val errorCommitted: CountDownLatch = CountDownLatch(1)
        val otherStarted: CountDownLatch = CountDownLatch(1)
        val otherParentSignaled: CountDownLatch = CountDownLatch(1)
        val errorParentKey: SkyKey = skyKey("errorParentKey")
        val errorKey: SkyKey = skyKey("errorKey")
        val otherParentKey: SkyKey = skyKey("otherParentKey")
        val otherKey: SkyKey = skyKey("otherKey")
        val numOtherParentInvocations: AtomicInteger = AtomicInteger(0)
        val numErrorParentInvocations: AtomicInteger = AtomicInteger(0)
        tester
            .getOrCreate(otherParentKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    val invocations: Int = numOtherParentInvocations.incrementAndGet()
                    Truth.assertWithMessage("otherParentKey should not be restarted")
                        .that(invocations)
                        .isEqualTo(1)
                    env.getValue(otherKey)
                })
        tester
            .getOrCreate(otherKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    otherStarted.countDown()
                    TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                        errorCommitted, "error didn't get committed to the graph in time"
                    )
                    com.google.devtools.build.skyframe.GraphTester.StringValue("other")
                })
        tester
            .getOrCreate(errorKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                        otherStarted, "other didn't start in time"
                    )
                    throw GenericFunctionException(
                        SomeErrorException("error"), Transience.PERSISTENT
                    )
                })
        tester
            .getOrCreate(errorParentKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    val invocations: Int = numErrorParentInvocations.incrementAndGet()
                    try {
                        val value: SkyValue? = env.getValueOrThrow(errorKey, SomeErrorException::class.java)
                        Truth.assertWithMessage("bogus non-null value %s", value).that(value == null).isTrue()
                        if (invocations == 1) {
                            return@setBuilder null
                        } else {
                            assertThat(env.inErrorBubbling()).isFalse()
                            org.junit.Assert.fail("RACE CONDITION: errorParentKey was restarted!")
                            return@setBuilder null
                        }
                    } catch (e: SomeErrorException) {
                        Truth.assertWithMessage("child error propagated during normal evaluation")
                            .that(env.inErrorBubbling())
                            .isTrue()
                        Truth.assertThat(invocations).isEqualTo(2)
                        return@setBuilder null
                    }
                })
        graph =
            NotifyingProcessableGraph(
                InMemoryGraphImpl(),
                com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                    if (key.equals(errorKey) && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER) {
                        errorCommitted.countDown()
                        TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                            otherParentSignaled, "otherParent didn't get signaled in time"
                        )
                        // We try to give some time for ParallelEvaluator to incorrectly re-evaluate
                        // 'otherParentKey'. This test case is testing for a real race condition and the
                        // 10ms time was chosen experimentally to give a true positive rate of 99.8%
                        // (without a sleep it has a 1% true positive rate). There's no good way to do
                        // this without sleeping. We *could* introspect ParallelEvaluator's
                        // AbstractQueueVisitor to see if the re-evaluation has been enqueued, but that's
                        // relying on pretty low-level implementation details.
                        com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly(
                            10,
                            TimeUnit.MILLISECONDS
                        )
                    }
                    if (key.equals(otherParentKey) && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.SIGNAL && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER) {
                        otherParentSignaled.countDown()
                    }
                })
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */false,
                com.google.common.collect.ImmutableList.of<SkyKey?>(otherParentKey, errorParentKey)
            )
        assertThat(result.hasError()).isTrue()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(errorParentKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cachedErrorsFromKeepGoingUsedOnNoKeepGoing() {
        graph = DeterministicProcessableGraph(InMemoryGraphImpl())
        tester = GraphTester()
        val errorKey: SkyKey = skyKey("error")
        val parent1Key: SkyKey = skyKey("parent1")
        val parent2Key: SkyKey = skyKey("parent2")
        tester
            .getOrCreate(parent1Key)
            .addDependency(errorKey)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("parent1"))
        tester
            .getOrCreate(parent2Key)
            .addDependency(errorKey)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("parent2"))
        tester.getOrCreate(errorKey).setHasError(true)
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(parent1Key))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(parent1Key)
        result =
            eval<SkyValue?>( /* keepGoing= */false, com.google.common.collect.ImmutableList.of<SkyKey?>(parent2Key))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(parent2Key)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cachedTopLevelErrorsShouldHaltNoKeepGoingBuildEarly() {
        graph = DeterministicProcessableGraph(InMemoryGraphImpl())
        tester = GraphTester()
        val errorKey: SkyKey = skyKey("error")
        tester.getOrCreate(errorKey).setHasError(true)
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(errorKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(errorKey)
        val rogueKey: SkyKey = skyKey("rogue")
        tester
            .getOrCreate(rogueKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    // This SkyFunction could do an arbitrarily bad computation, e.g. loop-forever. So we
                    // want to make sure that it is never run when we want to fail-fast anyway.
                    org.junit.Assert.fail("eval call should have already terminated")
                    null
                })
        result = eval<SkyValue?>( /* keepGoing= */false,
            com.google.common.collect.ImmutableList.of<SkyKey?>(errorKey, rogueKey)
        )
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorMapThat().hasSize(1)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(errorKey)
        assertThat(result.errorMap()).doesNotContainKey(rogueKey)
    }

    // Explicit test that we tolerate a SkyFunction that declares different [sequences of] deps each
    // restart. Such behavior from a SkyFunction isn't desired, but Bazel-on-Skyframe does indeed do
    // this.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaresDifferentDepsAfterRestart() {
        graph = DeterministicProcessableGraph(InMemoryGraphImpl())
        tester = GraphTester()
        val grandChild1Key: SkyKey = skyKey("grandChild1")
        tester.getOrCreate(grandChild1Key)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("grandChild1"))
        val child1Key: SkyKey = skyKey("child1")
        tester
            .getOrCreate(child1Key)
            .addDependency(grandChild1Key)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("child1"))
        val grandChild2Key: SkyKey = skyKey("grandChild2")
        tester.getOrCreate(grandChild2Key)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("grandChild2"))
        val child2Key: SkyKey = skyKey("child2")
        tester.getOrCreate(child2Key)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("child2"))
        val parentKey: SkyKey = skyKey("parent")
        val numComputes: AtomicInteger = AtomicInteger(0)
        tester
            .getOrCreate(parentKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    when (numComputes.incrementAndGet()) {
                        1 -> {
                            env.getValue(child1Key)
                            com.google.common.base.Preconditions.checkState(env.valuesMissing())
                            return@setBuilder null
                        }

                        2 -> {
                            env.getValue(child2Key)
                            com.google.common.base.Preconditions.checkState(env.valuesMissing())
                            return@setBuilder null
                        }

                        3 -> return@setBuilder com.google.devtools.build.skyframe.GraphTester.StringValue("the third time's the charm!")
                        else -> throw java.lang.IllegalStateException()
                    }
                })
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */false, com.google.common.collect.ImmutableList.of<SkyKey?>(parentKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasNoError()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasEntryThat(parentKey)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("the third time's the charm!"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun runUnhandledTransitiveErrors(
        @TestParameter keepGoing: Boolean, @TestParameter explicitlyPropagateError: Boolean
    ) {
        graph = DeterministicProcessableGraph(InMemoryGraphImpl())
        tester = GraphTester()
        val grandparentKey: SkyKey = skyKey("grandparent")
        val parentKey: SkyKey = skyKey("parent")
        val childKey: SkyKey = skyKey("child")
        val errorPropagated: AtomicBoolean = AtomicBoolean(false)
        tester
            .getOrCreate(grandparentKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    try {
                        return@setBuilder env.getValueOrThrow(parentKey, SomeErrorException::class.java)
                    } catch (e: SomeErrorException) {
                        errorPropagated.set(true)
                        throw GenericFunctionException(e, Transience.PERSISTENT)
                    }
                })
        tester
            .getOrCreate(parentKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    if (explicitlyPropagateError) {
                        try {
                            return@setBuilder env.getValueOrThrow(childKey, SomeErrorException::class.java)
                        } catch (e: SomeErrorException) {
                            throw GenericFunctionException(e)
                        }
                    } else {
                        return@setBuilder env.getValue(childKey)
                    }
                })
        tester.getOrCreate(childKey).setHasError( /* hasError= */true)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>(keepGoing, com.google.common.collect.ImmutableList.of<SkyKey?>(grandparentKey))
        Truth.assertThat(errorPropagated.get()).isTrue()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasSingletonErrorThat(grandparentKey)
    }

    private class ChildKey(arg: String?) : AbstractSkyKey<String?>(arg) {
        public override fun functionName(): SkyFunctionName {
            return CHILD_TYPE
        }

        companion object {
            private val interner: com.google.common.collect.Interner<ChildKey> = BlazeInterners.newWeakInterner()

            fun create(arg: String?): ChildKey {
                return interner.intern(ChildKey(arg))
            }
        }
    }

    private class ParentKey(arg: String?) : AbstractSkyKey<String?>(arg) {
        public override fun functionName(): SkyFunctionName {
            return PARENT_TYPE
        }

        companion object {
            private val interner: com.google.common.collect.Interner<ParentKey> = BlazeInterners.newWeakInterner()

            private fun create(arg: String?): ParentKey {
                return interner.intern(ParentKey(arg))
            }
        }
    }

    private class SkyKeyForSkyKeyComputeStateTests(arg: String?) : AbstractSkyKey<String?>(arg) {
        public override fun functionName(): SkyFunctionName? {
            return FUNCTION_NAME
        }

        companion object {
            private val FUNCTION_NAME: SkyFunctionName? = SkyFunctionName.createHermetic("SKY_KEY_COMPUTE_STATE_TESTS")
        }
    }

    // Test for the basic functionality of SkyKeyComputeState.
    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun skyKeyComputeState() {
        // When we have 3 nodes: key1, key2, key3.
        // (with dependency graph key1 -> key2; key2 -> key3, to be declared later in this test)
        // (and we'll be evaluating key1 later in this test)
        val key1: SkyKey = SkyKeyForSkyKeyComputeStateTests("key1")
        val key2: SkyKey = SkyKeyForSkyKeyComputeStateTests("key2")
        val key3: SkyKey = SkyKeyForSkyKeyComputeStateTests("key3")

        // And an SkyKeyComputeState implementation that tracks global instance counts and per-instance
        // usage counts,
        val globalStateInstanceCounter: AtomicInteger = AtomicInteger()

        class State : SkyKeyComputeState {
            val instanceCount: Int = globalStateInstanceCounter.incrementAndGet()
            var usageCount: Int = 0
        }

        // And a SkyFunction for these nodes,
        val numCalls: com.google.common.util.concurrent.AtomicLongMap<SkyKey?> =
            com.google.common.util.concurrent.AtomicLongMap.create<SkyKey?>()
        val stateForKey2Ref: AtomicReference<java.lang.ref.WeakReference<State?>?> =
            AtomicReference<java.lang.ref.WeakReference<State?>?>()
        val stateForKey3Ref: AtomicReference<java.lang.ref.WeakReference<State?>?> =
            AtomicReference<java.lang.ref.WeakReference<State?>?>()
        val skyFunctionForTest: SkyFunction =  // Whose #compute is such that
            SkyFunction { skyKey, env ->
                val state: State = env.getState({ State() })
                state.usageCount++
                val numCallsForKey: Int = numCalls.incrementAndGet(skyKey).toInt()
                // The number of calls to #compute is expected to be equal to the number of usages of
                // the state for that key,
                Truth.assertThat(state.usageCount).isEqualTo(numCallsForKey)
                if (skyKey.equals(key1)) {
                    // And the semantics for key1 are:

                    // The state for key1 is expected to be the first one created (since key1 is expected
                    // to be the first node we attempt to compute).

                    Truth.assertThat(state.instanceCount).isEqualTo(1)
                    // And key1 declares a dep on key2,
                    if (env.getValue(key2) == null) {
                        // (And that dep is expected to be missing on the initial #compute call for key1)
                        Truth.assertThat(numCallsForKey).isEqualTo(1)
                        return@SkyFunction null
                    }
                    // And if that dep is not missing, then we expect:
                    //   - We're on the second #compute call for key1
                    Truth.assertThat(numCallsForKey).isEqualTo(2)
                    //   - The state for key2 should have been eligible for GC. This is because the node
                    //     for key2 must have been fully computed, meaning its compute state is no longer
                    //     needed, and so ParallelEvaluator ought to have made it eligible for GC.
                    GcFinalization.awaitClear(stateForKey2Ref.get())
                    return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue("value1")
                } else if (skyKey.equals(key2)) {
                    // And the semantics for key2 are:

                    // The state for key2 is expected to be the second one created.

                    Truth.assertThat(state.instanceCount).isEqualTo(2)
                    stateForKey2Ref.set(java.lang.ref.WeakReference<State?>(state))
                    // And key2 declares a dep on key3,
                    if (env.getValue(key3) == null) {
                        // (And that dep is expected to be missing on the initial #compute call for key2)
                        Truth.assertThat(numCallsForKey).isEqualTo(1)
                        return@SkyFunction null
                    }
                    // And if that dep is not missing, then we expect the same sort of things we expected
                    // for key1 in this situation.
                    Truth.assertThat(numCallsForKey).isEqualTo(2)
                    GcFinalization.awaitClear(stateForKey3Ref.get())
                    return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue("value2")
                } else if (skyKey.equals(key3)) {
                    // And the semantics for key3 are:

                    // The state for key3 is expected to be the third one created.

                    Truth.assertThat(state.instanceCount).isEqualTo(3)
                    stateForKey3Ref.set(java.lang.ref.WeakReference<State?>(state))
                    // And key3 declares no deps.
                    return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue("value3")
                }
                throw java.lang.IllegalStateException()
            }

        tester.putSkyFunction(SkyKeyForSkyKeyComputeStateTests.Companion.FUNCTION_NAME, skyFunctionForTest)
        graph = InMemoryGraphImpl()
        // Then, when we evaluate key1,
        val resultValue: SkyValue = eval( /* keepGoing= */true, key1)
        // It successfully produces the value we expect, confirming all our other expectations about
        // the compute states were correct.
        assertThat(resultValue).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("value1"))
    }

    private class SkyFunctionExceptionForTest(message: String?) :
        SkyFunctionException(SomeErrorException(message), Transience.PERSISTENT)

    // Test for SkyKeyComputeState in the situation of an error for one node causing normal evaluation
    // to fail-fast, but when there are SkyKeyComputeState instances for other inflight nodes.
    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun skyKeyComputeState_noKeepGoingWithAnError() {
        // When we have 3 nodes: key1, key2, key3.
        // (with dependency graph key1 -> key2; key3, to be declared later in this test)
        // (and we'll be evaluating key1 & key3 in parallel later in this test)
        val key1: SkyKey = SkyKeyForSkyKeyComputeStateTests("key1")
        val key2: SkyKey = SkyKeyForSkyKeyComputeStateTests("key2")
        val key3: SkyKey = SkyKeyForSkyKeyComputeStateTests("key3")

        class State : SkyKeyComputeState

        // And a SkyFunction for these nodes,
        val stateForKey1Ref: AtomicReference<java.lang.ref.WeakReference<State?>?> =
            AtomicReference<java.lang.ref.WeakReference<State?>?>()
        val stateForKey3Ref: AtomicReference<java.lang.ref.WeakReference<State?>?> =
            AtomicReference<java.lang.ref.WeakReference<State?>?>()
        val key3SleepingLatch: CountDownLatch = CountDownLatch(1)
        val onNormalEvaluation: AtomicBoolean = AtomicBoolean(true)
        val skyFunctionForTest: SkyFunction =  // Whose #compute is such that
            SkyFunction { skyKey, env ->
                if (onNormalEvaluation.get()) {
                    // When we're on the normal evaluation:

                    val state: State? = env.getState({ State() })
                    if (skyKey.equals(key1)) {
                        // For key1:

                        stateForKey1Ref.set(java.lang.ref.WeakReference<State?>(state))
                        // We declare a dep on key.
                        return@SkyFunction env.getValue(key2)
                    } else if (skyKey.equals(key2)) {
                        // For key2:

                        // We wait for the thread computing key3 to be sleeping

                        key3SleepingLatch.await()
                        // And then we throw an error, which will fail the normal evaluation and trigger
                        // error bubbling.
                        onNormalEvaluation.set(false)
                        throw SkyFunctionExceptionForTest("normal evaluation")
                    } else if (skyKey.equals(key3)) {
                        // For key3:

                        stateForKey3Ref.set(java.lang.ref.WeakReference<State?>(state))
                        key3SleepingLatch.countDown()
                        // We sleep forever. (To be interrupted by ParallelEvaluator when the normal
                        // evaluation fails).
                        java.lang.Thread.sleep(Long.Companion.MAX_VALUE)
                    }
                    throw java.lang.IllegalStateException()
                } else {
                    // When we're in error bubbling:

                    // The states for the nodes from normal evaluation should have been eligible for GC.
                    // This is because ParallelEvaluator ought to have them eligible for GC before
                    // starting error bubbling.

                    GcFinalization.awaitClear(stateForKey1Ref.get())
                    GcFinalization.awaitClear(stateForKey3Ref.get())

                    // We bubble up a unique error message.
                    throw SkyFunctionExceptionForTest("error bubbling for " + skyKey.argument())
                }
            }

        tester.putSkyFunction(SkyKeyForSkyKeyComputeStateTests.Companion.FUNCTION_NAME, skyFunctionForTest)
        graph = InMemoryGraphImpl()
        // Then, when we do a nokeep_going evaluation of key1 and key3 in parallel,
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(
            eval<SkyValue?>( /* keepGoing= */false,
                key1,
                key3
            )
        ) // The evaluation fails (as expected),
            .hasErrorEntryForKeyThat(key1)
            .hasExceptionThat()
            .hasMessageThat() // And the error message for key1 is from error bubbling,
            .isEqualTo("error bubbling for key1")
        // Confirming that all our other expectations about the compute states were correct.
    }

    // Demonstrates we're able to drop SkyKeyCompute state intra-evaluation and post-evaluation.
    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun skyKeyComputeState_unnecessaryTemporaryStateDropperReceiver() {
        // When we have 2 nodes: key1, key2
        // (with dependency graph key1 -> key2, to be declared later in this test)
        // (and we'll be evaluating key1 later in this test)
        val key1: SkyKey = SkyKeyForSkyKeyComputeStateTests("key1")
        val key2: SkyKey = SkyKeyForSkyKeyComputeStateTests("key2")

        // And an SkyKeyComputeState implementation that tracks global instance counts and cleanup call
        // counts,
        val globalStateInstanceCounter: AtomicInteger = AtomicInteger()
        val globalStateCleanupCounter: AtomicInteger = AtomicInteger()

        class State : SkyKeyComputeState {
            val instanceCount: Int = globalStateInstanceCounter.incrementAndGet()

            public override fun close() {
                globalStateCleanupCounter.incrementAndGet()
            }
        }

        // And an UnnecessaryTemporaryStateDropperReceiver that,
        val dropperRef: AtomicReference<UnnecessaryTemporaryStateDropper?> =
            AtomicReference<UnnecessaryTemporaryStateDropper?>()
        val dropperReceiver: UnnecessaryTemporaryStateDropperReceiver =
            object : UnnecessaryTemporaryStateDropperReceiver() {
                public override fun onEvaluationStarted(dropper: UnnecessaryTemporaryStateDropper?) {
                    // Captures the UnnecessaryTemporaryStateDropper (for our use intra-evaluation)
                    dropperRef.set(dropper)
                }

                public override fun onEvaluationFinished() {
                    // And then drops everything when the evaluation is done.
                    dropperRef.get().drop()
                }
            }

        val stateForKey1Ref: AtomicReference<java.lang.ref.WeakReference<State?>?> =
            AtomicReference<java.lang.ref.WeakReference<State?>?>()

        // And a SkyFunction for these nodes,
        val skyFunctionForTest: SkyFunction =  // Whose #compute is such that
            SkyFunction { skyKey, env ->
                val state: State = env.getState({ State() })
                if (skyKey.equals(key1)) {
                    // The semantics for key1 are:

                    // We declare a dep on key2.

                    if (env.getValue(key2) == null) {
                        // If key2 is missing, that means we're on the initial #compute call for key1,
                        // And so we expect the compute state to be the first instance ever.
                        Truth.assertThat(state.instanceCount).isEqualTo(1)
                        stateForKey1Ref.set(java.lang.ref.WeakReference<State?>(state))

                        return@SkyFunction null
                    } else {
                        // But if key2 is not missing, that means we're on the subsequent #compute call for
                        // key1. That means we expect the compute state to be the third instance ever,
                        // because...
                        Truth.assertThat(state.instanceCount).isEqualTo(3)

                        return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue("value1")
                    }
                } else if (skyKey.equals(key2)) {
                    // ... The semantics for key2 are:

                    // Drop all compute states.

                    dropperRef.get().drop()
                    // Confirm the old compute state for key1 was GC'd.
                    GcFinalization.awaitClear(stateForKey1Ref.get())
                    // At this point, both state objects have been cleaned up.
                    Truth.assertThat(globalStateCleanupCounter.get()).isEqualTo(2)
                    // Also confirm key2's compute state is the second instance ever.
                    Truth.assertThat(state.instanceCount).isEqualTo(2)

                    return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue("value2")
                }
                throw java.lang.IllegalStateException()
            }

        tester.putSkyFunction(SkyKeyForSkyKeyComputeStateTests.Companion.FUNCTION_NAME, skyFunctionForTest)
        graph = InMemoryGraphImpl()

        val parallelEvaluator: ParallelEvaluator =
            ParallelEvaluator(
                graph,
                graphVersion,
                Version.minimal(),
                tester.getSkyFunctionMap(),
                reportedEvents,
                EmittedEventState(),
                EventFilter.FULL_STORAGE,
                ErrorInfoManager.UseChildErrorInfoIfNecessary.INSTANCE,  // Doesn't matter for this test case.
                revalidationReceiver,
                GraphInconsistencyReceiver.THROWING,  // We ought not need more than 1 thread for this test case.
                AbstractQueueVisitor.create(
                    "test-pool", 1, ParallelEvaluatorErrorClassifier.instance()
                ),
                SimpleCycleDetector( /* storeExactCycles= */true),
                dropperReceiver,  /* keepGoing= */
                com.google.common.base.Predicates.alwaysFalse<T?>()
            )
        // Then, when we evaluate key1,
        val resultValue: SkyValue? =
            parallelEvaluator.eval(com.google.common.collect.ImmutableList.of<E?>(key1)).get(key1)
        // It successfully produces the value we expect, confirming all our other expectations about
        // the compute states were correct.
        assertThat(resultValue).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("value1"))
        // And all state objects have been dropped, confirming the #onEvaluationFinished method was
        // called.
        Truth.assertThat(globalStateCleanupCounter.get()).isEqualTo(3)
    }

    // Test for the basic functionality of ClassToInstanceMapSkyKeyComputeState, demonstrating
    // that it can hold state associated with different classes and those states are independent.
    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun classToInstanceMapSkyKeyComputeState(
        @TestParameter touchStateA: Boolean, @TestParameter touchStateB: Boolean
    ) {
        class StateA : SkyKeyComputeState {
            var touched: Boolean = false
        }

        class StateB : SkyKeyComputeState {
            var touched: Boolean = false
        }

        val key1: SkyKey = SkyKeyForSkyKeyComputeStateTests("key1")
        val key2: SkyKey = SkyKeyForSkyKeyComputeStateTests("key2")
        val numCalls: com.google.common.util.concurrent.AtomicLongMap<SkyKey?> =
            com.google.common.util.concurrent.AtomicLongMap.create<SkyKey?>()
        val skyFunctionForTest: SkyFunction =
            SkyFunction { skyKey, env ->
                val numCallsForKey: Int = numCalls.incrementAndGet(skyKey).toInt()
                if (skyKey.equals(key1)) {
                    if (numCallsForKey == 1) {
                        if (touchStateA) {
                            env.getState({ ClassToInstanceMapSkyKeyComputeState() })
                                .getInstance(StateA::class.java, { StateA() })
                                .touched =
                                true
                        }
                        if (touchStateB) {
                            env.getState({ ClassToInstanceMapSkyKeyComputeState() })
                                .getInstance(StateB::class.java, { StateB() })
                                .touched =
                                true
                        }
                        assertThat(env.getValue(key2)).isNull()
                        return@SkyFunction null
                    }
                    assertThat(
                        env.getState({ ClassToInstanceMapSkyKeyComputeState() })
                            .getInstance(StateA::class.java, { StateA() })
                            .touched
                    )
                        .isEqualTo(touchStateA)
                    assertThat(
                        env.getState({ ClassToInstanceMapSkyKeyComputeState() })
                            .getInstance(StateB::class.java, { StateB() })
                            .touched
                    )
                        .isEqualTo(touchStateB)
                    val value: SkyValue? = env.getValue(key2)
                    assertThat(value).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("value"))
                    return@SkyFunction value
                }
                if (skyKey.equals(key2)) {
                    return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue("value")
                }
                throw java.lang.IllegalStateException()
            }
        tester.putSkyFunction(SkyKeyForSkyKeyComputeStateTests.Companion.FUNCTION_NAME, skyFunctionForTest)
        graph = InMemoryGraphImpl()
        val resultValue: SkyValue = eval( /* keepGoing= */true, key1)
        assertThat(resultValue).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("value"))
    }

    private class PartialReevaluationKey(arg: String?) : AbstractSkyKey<String?>(arg) {
        public override fun functionName(): SkyFunctionName? {
            return FUNCTION_NAME
        }

        public override fun supportsPartialReevaluation(): Boolean {
            return true
        }

        companion object {
            private val FUNCTION_NAME: SkyFunctionName? = SkyFunctionName.createHermetic("PARTIAL_REEVALUATION")
        }
    }

    /**
     * A bundle of [SkyframeLookupResult]s that can be consumed from as if it was a single one.
     */
    internal class DelegatingSkyframeLookupResult private constructor(resultsByKey: com.google.common.collect.ImmutableMap<SkyKey?, SkyframeLookupResult?>) :
        SkyframeLookupResult {
        private val resultsByKey: com.google.common.collect.ImmutableMap<SkyKey?, SkyframeLookupResult?>

        init {
            this.resultsByKey = resultsByKey
        }

        @Throws(E1::class, E2::class, E3::class)
        public override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?> getOrThrow(
            skyKey: SkyKey?,
            exceptionClass1: java.lang.Class<E1?>?,
            exceptionClass2: java.lang.Class<E2?>?,
            exceptionClass3: java.lang.Class<E3?>?
        ): SkyValue? {
            return com.google.common.base.Preconditions.checkNotNull<Any?>(resultsByKey.get(skyKey))
                .getOrThrow(skyKey, exceptionClass1, exceptionClass2, exceptionClass3)
        }

        public override fun queryDep(key: SkyKey?, resultCallback: QueryDepCallback?): Boolean {
            return com.google.common.base.Preconditions.checkNotNull<Any?>(resultsByKey.get(key))
                .queryDep(key, resultCallback)
        }

        companion object {
            private val BATCH_SPLITTER: com.google.common.base.Splitter = com.google.common.base.Splitter.on(';')
            private val KEY_SPLITTER: com.google.common.base.Splitter = com.google.common.base.Splitter.on(',')

            /**
             * Makes [SkyFunction.Environment.getValuesAndExceptions] calls in batches as described by
             * `requestBatches` and returns them bundled up.
             * 
             * 
             * The `requestBatches` string is split first by ';' to define an order-sensitive
             * sequence of batches, then by ',' to define an order-insensitive collection of keys.
             */
            @Throws(java.lang.InterruptedException::class)
            fun fromRequestBatches(
                requestBatches: String,
                env: SkyFunction.Environment,
                keys: com.google.common.collect.ImmutableList<out AbstractSkyKey<String?>?>
            ): DelegatingSkyframeLookupResult {
                val keysByArg: com.google.common.collect.ImmutableMap<String?, out AbstractSkyKey<String?>?> =
                    keys.stream().collect(
                        com.google.common.collect.ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                            AbstractSkyKey::argument,
                            java.util.function.Function { k: Any? -> k })
                    )

                val builder: com.google.common.collect.ImmutableMap.Builder<SkyKey?, SkyframeLookupResult?> =
                    com.google.common.collect.ImmutableMap.Builder<SkyKey?, SkyframeLookupResult?>()
                val batches: Iterable<String> = BATCH_SPLITTER.split(requestBatches)
                for (batch in batches) {
                    val batchKeys: Iterable<String> = KEY_SPLITTER.split(batch)
                    val result: SkyframeLookupResult? =
                        env.getValuesAndExceptions(
                            com.google.common.collect.Streams.stream<String?>(batchKeys)
                                .map { key: Any? -> keysByArg.get(key) }
                                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>()))
                    for (batchKey in batchKeys) {
                        builder.put(com.google.common.base.Preconditions.checkNotNull(keysByArg.get(batchKey)), result)
                    }
                }
                val resultsByKey: com.google.common.collect.ImmutableMap<SkyKey?, SkyframeLookupResult?> =
                    builder.buildOrThrow()

                Truth.assertThat(resultsByKey).hasSize(keys.size)
                return DelegatingSkyframeLookupResult(resultsByKey)
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun partialReevaluationOneButNotAllDeps(
        @TestParameter("key2,key3", "key2;key3", "key3;key2") requestBatches: String
    ) {
        // The parameterization of this test ensures that the partial reevaluation implementation is
        // insensitive to dep grouping.

        // This test illustrates the basic functionality of partial reevaluation: that a
        // function opting into partial reevaluation can be resumed when one, but not all, of its
        // previously requested-and-not-already-evaluated dependencies finishes evaluation.

        // Graph structure:
        // * key1 depends on key2 and key3

        // Evaluation behavior:
        // * key3 will not finish evaluating until key1 has been restarted with the result of key2

        val key1 = PartialReevaluationKey("key1")
        val key2 = PartialReevaluationKey("key2")
        val key3 = PartialReevaluationKey("key3")
        val key1EvaluationCount: AtomicInteger = AtomicInteger()
        val key1ObservesTheValueOfKey2: CountDownLatch = CountDownLatch(1)
        val f: SkyFunction =
            SkyFunction { skyKey, env ->
                val mailbox: PartialReevaluationMailbox =
                    PartialReevaluationMailbox.from(
                        env.getState({ ClassToInstanceMapSkyKeyComputeState() })
                    )
                val mail: Mail = mailbox.getMail()
                assertThat(mailbox.getMail().kind()).isEqualTo(Kind.EMPTY)

                if (skyKey.equals(key1)) {
                    val c: Int = key1EvaluationCount.incrementAndGet()
                    val result: SkyframeLookupResult =
                        DelegatingSkyframeLookupResult.Companion.fromRequestBatches(
                            requestBatches,
                            env,
                            com.google.common.collect.ImmutableList.of<PartialReevaluationKey?>(key2, key3)
                        )
                    if (c == 1) {
                        assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                        assertThat(result.get(key2)).isNull()
                        assertThat(result.get(key3)).isNull()
                        return@SkyFunction null
                    }
                    if (c == 2) {
                        assertThat(mail.kind()).isEqualTo(Kind.CAUSES)
                        assertThat(mail.causes().signaledDeps()).containsExactly(key2)
                        assertThat(result.get(key2)).isEqualTo(
                            com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                                "val2"
                            )
                        )
                        assertThat(result.get(key3)).isNull()
                        key1ObservesTheValueOfKey2.countDown()
                        return@SkyFunction null
                    }
                    Truth.assertThat(c).isEqualTo(3)
                    assertThat(mail.kind()).isEqualTo(Kind.CAUSES)
                    assertThat(mail.causes().signaledDeps()).containsExactly(key3)
                    assertThat(result.get(key2)).isEqualTo(
                        com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                            "val2"
                        )
                    )
                    assertThat(result.get(key3)).isEqualTo(
                        com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                            "val3"
                        )
                    )
                    return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val1")
                }
                if (skyKey.equals(key2)) {
                    assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                    return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val2")
                }
                assertThat(skyKey).isEqualTo(key3)
                assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                Truth.assertThat(
                    key1ObservesTheValueOfKey2.await(
                        com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
                    )
                )
                    .isTrue()
                com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val3")
            }
        tester.putSkyFunction(PartialReevaluationKey.Companion.FUNCTION_NAME, f)
        graph = InMemoryGraphImpl()
        val resultValue: SkyValue? = eval( /* keepGoing= */true, key1)
        assertThat(resultValue).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val1"))
        Truth.assertThat(key1EvaluationCount.get()).isEqualTo(3)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun partialReevaluationOneDuringAReevaluation(
        @TestParameter(
            "key2,key3,key4", "key2,key3;key4", "key2;key3,key4", "key2;key3;key4" // permute (2,3):
            , "key3;key2,key4", "key3;key2;key4" // permute (2,4):
            , "key4,key3;key2", "key4;key3,key2", "key4;key3;key2" // permute (3,4):
            , "key2,key4;key3", "key2;key4;key3" // permute (2,3,4):
            , "key4;key2;key3"
        ) requestBatches: String
    ) {
        // The parameterization of this test ensures that the partial reevaluation implementation is
        // insensitive to dep grouping.

        // This test illustrates another bit of partial reevaluation functionality: when a partial
        // reevaluation is currently underway, and when another one of its previously
        // requested-and-not-already-evaluated dependencies finishes evaluation (but not all of them),
        // another partial reevaluation will run when the current one finishes.

        // Graph structure:
        // * key1 depends on key2, key3, and key4
        // * key5 depends on key3 (it's here only to detect when key3 signals its parents)

        // Evaluation behavior:
        // * key4 will not finish evaluating until key1 has been restarted with the result of key3
        // * key1's partial reevaluation observing key2 does not finish until key3 has signaled its
        //   parents (i.e. key1 and key5)
        // * key3 will not finish evaluating until key1 has been restarted with the result of key2

        val key1 = PartialReevaluationKey("key1")
        val key2 = PartialReevaluationKey("key2")
        val key3 = PartialReevaluationKey("key3")
        val key4 = PartialReevaluationKey("key4")
        val key5 = PartialReevaluationKey("key5")
        val key1EvaluationCount: AtomicInteger = AtomicInteger()
        val key5EvaluationCount: AtomicInteger = AtomicInteger()
        val key1EvaluationsInflight: AtomicInteger = AtomicInteger()
        val key1ObservesTheValueOfKey2: CountDownLatch = CountDownLatch(1)
        val key5ObservesTheAbsenceOfKey3: CountDownLatch = CountDownLatch(1)
        val key1ObservesTheValueOfKey3: CountDownLatch = CountDownLatch(1)
        val key3SignaledItsParents: CountDownLatch = CountDownLatch(1)
        val f: SkyFunction =
            SkyFunction { skyKey, env ->
                val mail: Mail =
                    PartialReevaluationMailbox.from(
                        env.getState({ ClassToInstanceMapSkyKeyComputeState() })
                    )
                        .getMail()
                if (skyKey.equals(key1)) {
                    Truth.assertThat(key1EvaluationsInflight.incrementAndGet()).isEqualTo(1)
                    try {
                        val c: Int = key1EvaluationCount.incrementAndGet()
                        val result: SkyframeLookupResult =
                            DelegatingSkyframeLookupResult.Companion.fromRequestBatches(
                                requestBatches,
                                env,
                                com.google.common.collect.ImmutableList.of<PartialReevaluationKey?>(key2, key3, key4)
                            )
                        if (c == 1) {
                            assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                            assertThat(result.get(key2)).isNull()
                            assertThat(result.get(key3)).isNull()
                            assertThat(result.get(key4)).isNull()
                            return@SkyFunction null
                        }
                        if (c == 2) {
                            assertThat(mail.kind()).isEqualTo(Kind.CAUSES)
                            assertThat(mail.causes().signaledDeps()).containsExactly(key2)
                            assertThat(result.get(key2)).isEqualTo(
                                com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                                    "val2"
                                )
                            )
                            assertThat(result.get(key3)).isNull()
                            assertThat(result.get(key4)).isNull()
                            key1ObservesTheValueOfKey2.countDown()
                            Truth.assertThat(
                                key3SignaledItsParents.await(
                                    com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                                    TimeUnit.SECONDS
                                )
                            )
                                .isTrue()
                            return@SkyFunction null
                        }
                        if (c == 3) {
                            assertThat(mail.kind()).isEqualTo(Kind.CAUSES)
                            assertThat(mail.causes().signaledDeps()).containsExactly(key3)
                            assertThat(result.get(key2)).isEqualTo(
                                com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                                    "val2"
                                )
                            )
                            assertThat(result.get(key3)).isEqualTo(
                                com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                                    "val3"
                                )
                            )
                            assertThat(result.get(key4)).isNull()
                            key1ObservesTheValueOfKey3.countDown()
                            return@SkyFunction null
                        }
                        Truth.assertThat(c).isEqualTo(4)
                        assertThat(mail.kind()).isEqualTo(Kind.CAUSES)
                        assertThat(mail.causes().signaledDeps()).containsExactly(key4)
                        assertThat(result.get(key2)).isEqualTo(
                            com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                                "val2"
                            )
                        )
                        assertThat(result.get(key3)).isEqualTo(
                            com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                                "val3"
                            )
                        )
                        assertThat(result.get(key4)).isEqualTo(
                            com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                                "val4"
                            )
                        )
                        return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val1")
                    } finally {
                        Truth.assertThat(key1EvaluationsInflight.decrementAndGet()).isEqualTo(0)
                    }
                }

                if (skyKey.equals(key2)) {
                    assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                    return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val2")
                }

                if (skyKey.equals(key3)) {
                    assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                    Truth.assertThat(
                        key1ObservesTheValueOfKey2.await(
                            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
                        )
                    )
                        .isTrue()
                    Truth.assertThat(
                        key5ObservesTheAbsenceOfKey3.await(
                            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
                        )
                    )
                        .isTrue()
                    return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val3")
                }

                if (skyKey.equals(key4)) {
                    assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                    Truth.assertThat(
                        key1ObservesTheValueOfKey3.await(
                            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
                        )
                    )
                        .isTrue()
                    return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val4")
                }

                assertThat(skyKey).isEqualTo(key5)
                val c: Int = key5EvaluationCount.incrementAndGet()
                if (c == 1) {
                    assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                    val value: SkyValue? = env.getValue(key3)
                    assertThat(value).isNull()
                    key5ObservesTheAbsenceOfKey3.countDown()
                    return@SkyFunction null
                }
                Truth.assertThat(c).isEqualTo(2)
                assertThat(mail.kind()).isEqualTo(Kind.CAUSES)
                assertThat(mail.causes().signaledDeps()).containsExactly(key3)
                val value: SkyValue? = env.getValue(key3)
                assertThat(value).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val3"))
                key3SignaledItsParents.countDown()
                com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val5")
            }
        tester.putSkyFunction(PartialReevaluationKey.Companion.FUNCTION_NAME, f)
        graph = InMemoryGraphImpl()
        val resultValues: EvaluationResult<SkyValue?> = eval<T?>( /* keepGoing= */true, key1, key5)
        assertThat(resultValues.get(key1)).isEqualTo(
            com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                "val1"
            )
        )
        assertThat(resultValues.get(key5)).isEqualTo(
            com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                "val5"
            )
        )
        Truth.assertThat(key1EvaluationsInflight.get()).isEqualTo(0)
        Truth.assertThat(key1EvaluationCount.get()).isEqualTo(4)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun partialReevaluationErrorDuringReevaluation(@TestParameter keepGoing: Boolean) {
        // This test illustrates how the partial reevaluation implementation handles the case in which a
        // SkyFunction throws an error during a partial reevaluation: the error is ignored if the
        // partially-reevaluated node has not yet been fully signaled. This maintains the invariant that
        // nodes are not committed with a value or error while they have deps which may signal them.

        // Note that Skyframe policy encourages SkyFunction behavior such as this: SkyFunction
        // implementations should eagerly throw errors even when not all their deps are done, to better
        // support error contextualization during no-keep_going builds.

        // Graph structure:
        // * key1 depends on key2, key3, and key4
        // * key5 depends on key3 (it's here only to detect when key3 signals its parents)

        // Evaluation behavior:
        // * key4 will not finish evaluating until key1 has been restarted with the result of key3
        // * key1's partial reevaluation observing key2 does not finish until key3 has signaled its
        //   parents (i.e. key1 and key5)
        // * key3 will not finish evaluating until key1 has been restarted with the result of key2

        val key1 = PartialReevaluationKey("key1")
        val key2 = PartialReevaluationKey("key2")
        val key3 = PartialReevaluationKey("key3")
        val key4 = PartialReevaluationKey("key4")
        val key5 = PartialReevaluationKey("key5")
        val key1EvaluationCount: AtomicInteger = AtomicInteger()
        val key5EvaluationCount: AtomicInteger = AtomicInteger()
        val key1EvaluationsInflight: AtomicInteger = AtomicInteger()
        val key1ObservesTheValueOfKey2: CountDownLatch = CountDownLatch(1)
        val key5ObservesTheAbsenceOfKey3: CountDownLatch = CountDownLatch(1)
        val key1ObservesTheValueOfKey3: CountDownLatch = CountDownLatch(1)
        val key3SignaledItsParents: CountDownLatch = CountDownLatch(1)
        val f: SkyFunction =
            SkyFunction { skyKey, env ->
                val mail: Mail =
                    PartialReevaluationMailbox.from(
                        env.getState({ ClassToInstanceMapSkyKeyComputeState() })
                    )
                        .getMail()
                if (skyKey.equals(key1)) {
                    Truth.assertThat(key1EvaluationsInflight.incrementAndGet()).isEqualTo(1)
                    try {
                        val c: Int = key1EvaluationCount.incrementAndGet()
                        val result: SkyframeLookupResult =
                            env.getValuesAndExceptions(com.google.common.collect.ImmutableList.of<E?>(key2, key3, key4))
                        if (c == 1) {
                            assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                            assertThat(result.get(key2)).isNull()
                            assertThat(result.get(key3)).isNull()
                            assertThat(result.get(key4)).isNull()
                            return@SkyFunction null
                        }
                        if (c == 2) {
                            assertThat(mail.kind()).isEqualTo(Kind.CAUSES)
                            assertThat(mail.causes().signaledDeps()).containsExactly(key2)
                            assertThat(result.get(key2)).isEqualTo(
                                com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                                    "val2"
                                )
                            )
                            assertThat(result.get(key3)).isNull()
                            assertThat(result.get(key4)).isNull()
                            key1ObservesTheValueOfKey2.countDown()
                            Truth.assertThat(
                                key3SignaledItsParents.await(
                                    com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                                    TimeUnit.SECONDS
                                )
                            )
                                .isTrue()
                            throw SkyFunctionExceptionForTest(
                                "Error thrown during partial reevaluation (1)"
                            )
                        }
                        if (c == 3) {
                            // The Skyframe stateCache invalidates its entry for a node when it throws an error:
                            assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                            assertThat(result.get(key2)).isEqualTo(
                                com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                                    "val2"
                                )
                            )
                            assertThat(result.get(key3)).isEqualTo(
                                com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                                    "val3"
                                )
                            )
                            assertThat(result.get(key4)).isNull()
                            key1ObservesTheValueOfKey3.countDown()
                            throw SkyFunctionExceptionForTest(
                                "Error thrown during partial reevaluation (2)"
                            )
                        }
                        Truth.assertThat(c).isEqualTo(4)
                        assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                        assertThat(result.get(key2)).isEqualTo(
                            com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                                "val2"
                            )
                        )
                        assertThat(result.get(key3)).isEqualTo(
                            com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                                "val3"
                            )
                        )
                        assertThat(result.get(key4)).isEqualTo(
                            com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                                "val4"
                            )
                        )
                        throw SkyFunctionExceptionForTest("Error thrown during final full evaluation")
                    } finally {
                        Truth.assertThat(key1EvaluationsInflight.decrementAndGet()).isEqualTo(0)
                    }
                }

                if (skyKey.equals(key2)) {
                    assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                    return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val2")
                }

                if (skyKey.equals(key3)) {
                    assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                    Truth.assertThat(
                        key1ObservesTheValueOfKey2.await(
                            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
                        )
                    )
                        .isTrue()
                    Truth.assertThat(
                        key5ObservesTheAbsenceOfKey3.await(
                            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
                        )
                    )
                        .isTrue()
                    return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val3")
                }

                if (skyKey.equals(key4)) {
                    assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                    Truth.assertThat(
                        key1ObservesTheValueOfKey3.await(
                            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
                        )
                    )
                        .isTrue()
                    return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val4")
                }

                assertThat(skyKey).isEqualTo(key5)
                val c: Int = key5EvaluationCount.incrementAndGet()
                if (c == 1) {
                    assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                    val value: SkyValue? = env.getValue(key3)
                    assertThat(value).isNull()
                    key5ObservesTheAbsenceOfKey3.countDown()
                    return@SkyFunction null
                }
                Truth.assertThat(c).isEqualTo(2)
                assertThat(mail.kind()).isEqualTo(Kind.CAUSES)
                assertThat(mail.causes().signaledDeps()).containsExactly(key3)
                val value: SkyValue? = env.getValue(key3)
                assertThat(value).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val3"))
                key3SignaledItsParents.countDown()
                com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val5")
            }
        tester.putSkyFunction(PartialReevaluationKey.Companion.FUNCTION_NAME, f)
        graph = InMemoryGraphImpl()
        val resultValues: EvaluationResult<SkyValue?> = eval<T?>(keepGoing, key1, key5)
        assertThat(resultValues.getError(key1).getException()).isInstanceOf(SomeErrorException::class.java)

        // key4's signal to key1 races with key1's readiness check after completing with an error during
        // partial reevaluation 2. If the signal wins, then key1 should be allowed to complete, because
        // it has no outstanding signals. If the signal loses, then key1 will reevaluate one last time.
        assertThat(resultValues.getError(key1).getException())
            .hasMessageThat()
            .isIn(
                com.google.common.collect.ImmutableList.of<E?>(
                    "Error thrown during partial reevaluation (2)",
                    "Error thrown during final full evaluation"
                )
            )
        if (keepGoing) {
            assertThat(resultValues.get(key5)).isEqualTo(
                com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                    "val5"
                )
            )
        }
        Truth.assertThat(key1EvaluationsInflight.get()).isEqualTo(0)
        Truth.assertThat(key1EvaluationCount.get()).isIn(com.google.common.collect.ImmutableList.of<Int?>(3, 4))
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun partialReevaluationOneErrorButNotAllDeps(
        @TestParameter keepGoing: Boolean, @TestParameter enrichError: Boolean
    ) {
        // The parameterization of this test ensures that the partial reevaluation implementation is
        // insensitive to dep grouping.

        // This test illustrates partial reevaluation's handling of errors in multiple contexts, with
        // and without keepGoing, and with the error-observing function enriching or recovering from the
        // error.

        // Graph structure:
        // * key1 depends on key2 and key3

        // Evaluation behavior:
        // * key3 will not finish evaluating until key1 has been restarted with the error result of key2
        //   (which notably will never happen in no-keepGoing because by then key2 should have
        //   interrupted evaluations)

        val key1 = PartialReevaluationKey("key1")
        val key2 = PartialReevaluationKey("key2")
        val key3 = PartialReevaluationKey("key3")
        val key1EvaluationCount: AtomicInteger = AtomicInteger()
        val key1ObservesTheErrorOfKey2: CountDownLatch = CountDownLatch(1)
        val f: SkyFunction =
            SkyFunction { skyKey, env ->
                val mail: Mail =
                    PartialReevaluationMailbox.from(
                        env.getState({ ClassToInstanceMapSkyKeyComputeState() })
                    )
                        .getMail()
                if (skyKey.equals(key1)) {
                    val c: Int = key1EvaluationCount.incrementAndGet()
                    val result: SkyframeLookupResult =
                        env.getValuesAndExceptions(com.google.common.collect.ImmutableList.of<E?>(key2, key3))
                    if (c == 1) {
                        assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                        assertThat(result.get(key2)).isNull()
                        assertThat(result.get(key3)).isNull()
                        return@SkyFunction null
                    }
                    if (c == 2) {
                        if (keepGoing) {
                            assertThat(mail.kind()).isEqualTo(Kind.CAUSES)
                            assertThat(mail.causes().signaledDeps()).containsExactly(key2)
                        } else {
                            // The Skyframe stateCache invalidates everything when starting error bubbling:
                            assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                            assertThat(env.inErrorBubbling()).isTrue()
                        }
                        org.junit.Assert.assertThrows<SomeErrorException?>(
                            "key2",
                            SomeErrorException::class.java,
                            org.junit.function.ThrowingRunnable {
                                result.getOrThrow(
                                    key2,
                                    SomeErrorException::class.java
                                )
                            })
                        assertThat(result.get(key3)).isNull()
                        key1ObservesTheErrorOfKey2.countDown()
                        if (enrichError) {
                            throw SkyFunctionExceptionForTest("key1 observed key2 exception (w/o key3)")
                        } else {
                            return@SkyFunction null
                        }
                    }
                    Truth.assertThat(c).isEqualTo(3)
                    if (enrichError) {
                        // The Skyframe stateCache invalidates its entry for a node when it throws an error:
                        assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                    } else {
                        assertThat(mail.kind()).isEqualTo(Kind.CAUSES)
                        assertThat(mail.causes().signaledDeps()).containsExactly(key3)
                    }
                    Truth.assertThat(keepGoing).isTrue()
                    org.junit.Assert.assertThrows<SomeErrorException?>(
                        "key2",
                        SomeErrorException::class.java,
                        org.junit.function.ThrowingRunnable { result.getOrThrow(key2, SomeErrorException::class.java) })
                    assertThat(result.get(key3)).isEqualTo(
                        com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                            "val3"
                        )
                    )
                    if (enrichError) {
                        throw SkyFunctionExceptionForTest("key1 observed key2 exception (w/ key3)")
                    } else {
                        return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val1")
                    }
                }
                if (skyKey.equals(key2)) {
                    assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                    throw SkyFunctionExceptionForTest("key2")
                }
                assertThat(skyKey).isEqualTo(key3)
                assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                Truth.assertThat(
                    key1ObservesTheErrorOfKey2.await(
                        com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
                    )
                )
                    .isTrue()
                Truth.assertThat(keepGoing).isTrue()
                com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val3")
            }
        tester.putSkyFunction(PartialReevaluationKey.Companion.FUNCTION_NAME, f)
        graph = InMemoryGraphImpl()
        val result: EvaluationResult<SkyValue?> =
            eval( /* keepGoing= */keepGoing, com.google.common.collect.ImmutableList.of<E?>(key1))

        if (keepGoing && enrichError) {
            assertThat(result.getError(key1).getException()).isInstanceOf(SomeErrorException::class.java)
            assertThat(result.getError(key1).getException())
                .hasMessageThat()
                .isEqualTo("key1 observed key2 exception (w/ key3)")
        } else if (keepGoing) {
            // !enrichError -- expect key1 to have recovered
            assertThat(result.get(key1)).isEqualTo(
                com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                    "val1"
                )
            )
        } else if (enrichError) {
            // !keepGoing -- expect key3 to have not completed
            assertThat(result.getError(key1).getException()).isInstanceOf(SomeErrorException::class.java)
            assertThat(result.getError(key1).getException())
                .hasMessageThat()
                .isEqualTo("key1 observed key2 exception (w/o key3)")
        } else {
            // !keepGoing && !enrichError -- expect key2's error
            assertThat(result.getError(key1).getException()).isInstanceOf(SomeErrorException::class.java)
            assertThat(result.getError(key1).getException()).hasMessageThat().isEqualTo("key2")
        }

        Truth.assertThat(key1EvaluationCount.get()).isEqualTo(if (keepGoing) 3 else 2)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun partialReevaluationErrorObservedDuringReevaluation() {
        // This test illustrates how the partial reevaluation implementation handles the case in which a
        // SkyFunction doing a partial reevaluation has a dep which completes with an error during a
        // no-keep_going build: that partial reevaluation ends, because
        // ParallelEvaluatorContext.signalParentsOnAbort completely ignores the value returned by
        // entry.signalDep. The node undergoing partial evaluation may or may not be chosen when
        // bubbling up the dep's error; in this test it's chosen because it's the only parent.

        // Note that the partial reevaluation implementation is *not* responsible for this behavior;
        // rather, it is due to "core" Skyframe evaluation policy. However, this test documents the
        // expected behavior of partial reevaluation implementations, in case of future changes to
        // Skyframe.

        // Graph structure:
        // * key1 depends on key2, key3, and key4

        // Evaluation behavior:
        // * key4 will not finish evaluating -- it gets interrupted by key2's error and is never resumed
        // * key3 will not finish evaluating until key1 has been restarted with the result of key2
        // * key1 only observes key2's error during error bubbling

        val key1 = PartialReevaluationKey("key1")
        val key2 = PartialReevaluationKey("key2")
        val key3 = PartialReevaluationKey("key3")
        val key4 = PartialReevaluationKey("key4")
        val key1EvaluationCount: AtomicInteger = AtomicInteger()
        val key4EvaluationCount: AtomicInteger = AtomicInteger()
        val key1EvaluationsInflight: AtomicInteger = AtomicInteger()
        val key1ObservesTheValueOfKey2: CountDownLatch = CountDownLatch(1)
        val key4WaitsUntilInterruptedByNoKeepGoingEvaluationShutdown: CountDownLatch = CountDownLatch(1)
        val f: SkyFunction =
            SkyFunction { skyKey, env ->
                val mail: Mail =
                    PartialReevaluationMailbox.from(
                        env.getState({ ClassToInstanceMapSkyKeyComputeState() })
                    )
                        .getMail()
                if (skyKey.equals(key1)) {
                    Truth.assertThat(key1EvaluationsInflight.incrementAndGet()).isEqualTo(1)
                    try {
                        val c: Int = key1EvaluationCount.incrementAndGet()
                        val result: SkyframeLookupResult =
                            env.getValuesAndExceptions(com.google.common.collect.ImmutableList.of<E?>(key2, key3, key4))
                        if (c == 1) {
                            assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                            assertThat(result.get(key2)).isNull()
                            assertThat(result.get(key3)).isNull()
                            assertThat(result.get(key4)).isNull()
                            return@SkyFunction null
                        }
                        if (c == 2) {
                            assertThat(mail.kind()).isEqualTo(Kind.CAUSES)
                            assertThat(mail.causes().signaledDeps()).containsExactly(key2)
                            assertThat(result.get(key2)).isEqualTo(
                                com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                                    "val2"
                                )
                            )
                            assertThat(result.get(key3)).isNull()
                            assertThat(result.get(key4)).isNull()
                            key1ObservesTheValueOfKey2.countDown()
                            return@SkyFunction null
                        }
                        Truth.assertThat(c).isEqualTo(3)
                        // The Skyframe stateCache invalidates everything when starting error bubbling:
                        assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                        assertThat(env.inErrorBubbling()).isTrue()
                        assertThat(result.get(key2)).isEqualTo(
                            com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                                "val2"
                            )
                        )
                        org.junit.Assert.assertThrows<SomeErrorException?>(
                            "key3",
                            SomeErrorException::class.java,
                            org.junit.function.ThrowingRunnable {
                                result.getOrThrow(
                                    key3,
                                    SomeErrorException::class.java
                                )
                            })
                        assertThat(result.get(key4)).isNull()
                        throw SkyFunctionExceptionForTest("Error thrown after partial reevaluation")
                    } finally {
                        Truth.assertThat(key1EvaluationsInflight.decrementAndGet()).isEqualTo(0)
                    }
                }

                if (skyKey.equals(key2)) {
                    assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                    return@SkyFunction com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of("val2")
                }

                if (skyKey.equals(key3)) {
                    assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                    Truth.assertThat(
                        key1ObservesTheValueOfKey2.await(
                            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
                        )
                    )
                        .isTrue()
                    throw SkyFunctionExceptionForTest("key3")
                }

                assertThat(skyKey).isEqualTo(key4)
                assertThat(mail.kind()).isEqualTo(Kind.FRESHLY_INITIALIZED)
                Truth.assertThat(key4EvaluationCount.incrementAndGet()).isEqualTo(1)
                throw org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
                    java.lang.InterruptedException::class.java,
                    org.junit.function.ThrowingRunnable {
                        key4WaitsUntilInterruptedByNoKeepGoingEvaluationShutdown.await(
                            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
                        )
                    })
            }
        tester.putSkyFunction(PartialReevaluationKey.Companion.FUNCTION_NAME, f)
        graph = InMemoryGraphImpl()
        val resultValues: EvaluationResult<SkyValue?> =
            eval( /* keepGoing= */false, com.google.common.collect.ImmutableList.of<E?>(key1))
        assertThat(resultValues.getError(key1).getException()).isInstanceOf(SomeErrorException::class.java)
        assertThat(resultValues.getError(key1).getException())
            .hasMessageThat()
            .isEqualTo("Error thrown after partial reevaluation")
        Truth.assertThat(key1EvaluationsInflight.get()).isEqualTo(0)
        Truth.assertThat(key1EvaluationCount.get()).isEqualTo(3)
    }

    // Regression test for b/225877591 ("Unexpected missing value in PrepareDepsOfPatternsFunction
    // when there's both a dep with a cached cycle and another dep with an error").
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDepOnCachedNodeThatItselfDependsOnBothCycleAndError() {
        graph = InMemoryGraphImpl()
        val parentKey: SkyKey = skyKey("parent")
        val childKey: SkyKey = skyKey("child")
        val cycleKey: SkyKey = skyKey("cycle")
        val errorKey: SkyKey = skyKey("error")
        tester.getOrCreate(cycleKey).addDependency(cycleKey)
        tester.getOrCreate(errorKey).setHasError(true)
        tester.getOrCreate(childKey).addDependency(cycleKey).addDependency(errorKey)

        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(childKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(childKey)
            .hasCycleInfoThat()
            .containsExactly(
                CycleInfo.createCycleInfo(
                    com.google.common.collect.ImmutableList.of<E?>(childKey),
                    com.google.common.collect.ImmutableList.of<E?>(cycleKey)
                )
            )
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(childKey)
            .hasExceptionThat()
            .hasMessageThat()
            .contains(errorKey.toString())

        tester
            .getOrCreate(parentKey)
            .setBuilder(
                SkyFunction { key, env ->
                    val unusedLookupResult: SkyframeLookupResult? =
                        env.getValuesAndExceptions(com.google.common.collect.ImmutableList.of<E?>(childKey))
                    // env.valuesMissing() should always return true given the graph structure staged
                    // above, regardless of the cached state. (This test case specifically stages a fully
                    // cached graph state and picks on the getValuesAndExceptions method, because that was
                    // the situation of the bug for which this is a regression test.)
                    //
                    // If childKey is legit not yet in the graph, then of course env.valuesMissing()
                    // should return true.
                    //
                    // If childKey is in the graph, then env.valuesMissing() should still return true
                    // because childKey transitively depends on a cycle.
                    assertThat(env.valuesMissing()).isTrue()
                    null
                })
        result = eval<SkyValue?>( /* keepGoing= */true, com.google.common.collect.ImmutableList.of<SkyKey?>(parentKey))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(parentKey)
            .hasCycleInfoThat()
            .containsExactly(
                CycleInfo.createCycleInfo(
                    com.google.common.collect.ImmutableList.of<E?>(parentKey, childKey),
                    com.google.common.collect.ImmutableList.of<E?>(cycleKey)
                )
            )
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(parentKey)
            .hasExceptionThat()
            .hasMessageThat()
            .contains(errorKey.toString())
    }

    @Throws(java.lang.InterruptedException::class)
    private fun evalParallelSkyFunctionAndVerifyResults(
        testFunction: SkyFunction?,
        testExecutor: QuiescingExecutor?,
        actualRunnableCount: AtomicInteger,
        expectRunnableCount: Int
    ) {
        val parentKey: SkyKey = skyKey("parentKey")
        tester.getOrCreate(parentKey).setBuilder(testFunction)

        graph = InMemoryGraphImpl()
        val parallelEvaluator: ParallelEvaluator =
            ParallelEvaluator(
                graph,
                graphVersion,
                Version.minimal(),
                tester.getSkyFunctionMap(),
                reportedEvents,
                EmittedEventState(),
                EventFilter.FULL_STORAGE,
                ErrorInfoManager.UseChildErrorInfoIfNecessary.INSTANCE,  // Doesn't matter for this test case.
                revalidationReceiver,
                GraphInconsistencyReceiver.THROWING,  // We ought not need more than 1 thread for this test case.
                testExecutor,
                SimpleCycleDetector( /* storeExactCycles= */true),
                UnnecessaryTemporaryStateDropperReceiver.NULL,  /* keepGoing= */
                com.google.common.base.Predicates.alwaysFalse<T?>()
            )

        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            parallelEvaluator.eval(com.google.common.collect.ImmutableList.of<E?>(parentKey))
        assertThat(result.hasError()).isFalse()
        assertThat(result.get(parentKey)).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("parentValue"))
        Truth.assertThat(actualRunnableCount.get()).isEqualTo(expectRunnableCount)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun testParallelSkyFunctionComputation_runnablesOnBothCurrentAndExternalThreads() {
        val testExecutor: QuiescingExecutor? =
            AbstractQueueVisitor.create("test-pool", 10, ParallelEvaluatorErrorClassifier.instance())
        val actualRunnableCount: AtomicInteger = AtomicInteger(0)

        // Let's arbitrarily set the expected size of Runnables as a random number between 10 and 30.
        val expectRunnableCount: Int = 10 + Random().nextInt(20)

        val testFunction: SkyFunction =
            SkyFunction { key, env ->
                val countDownLatch: CountDownLatch = CountDownLatch(expectRunnableCount)
                val runnablesQueue: BlockingQueue<java.lang.Runnable?> = LinkedBlockingQueue<java.lang.Runnable?>()
                for (i in 0..<expectRunnableCount) {
                    runnablesQueue.put(
                        java.lang.Runnable {
                            actualRunnableCount.incrementAndGet()
                            countDownLatch.countDown()
                        })
                }

                val drainQueue: java.lang.Runnable =
                    java.lang.Runnable {
                        var next: java.lang.Runnable?
                        while ((runnablesQueue.poll().also { next = it }) != null) {
                            next.run()
                        }
                    }

                val executor: QuiescingExecutor = env.getParallelEvaluationExecutor()
                assertThat(executor).isSameInstanceAs(testExecutor)
                for (i in 0..<expectRunnableCount) {
                    executor.execute(drainQueue)
                }

                // After dispatching Runnables to threads on the executor, it is preferred that
                // current thread also participates in executing some (or even all) runnables. It is
                // possible that other threads on the executor are all busy and will not be available
                // for a fairly long time. So having the current thread participate will prevent
                // current thread from being completely idle while waiting for the runnableQueue to be
                // fully drained.
                drainQueue.run()

                // It is possible that the last runnable executed on the current thread ends earlier
                // than what are executed on the other threads. So we need to wait for all necessary
                // computations to complete before returning.
                countDownLatch.await()
                com.google.devtools.build.skyframe.GraphTester.StringValue("parentValue")
            }

        evalParallelSkyFunctionAndVerifyResults(
            testFunction, testExecutor, actualRunnableCount, expectRunnableCount
        )
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun testParallelSkyFunctionComputation_runnablesOnExternalThreadsOnly() {
        val testExecutor: QuiescingExecutor? =
            AbstractQueueVisitor.create("test-pool", 10, ParallelEvaluatorErrorClassifier.instance())
        val actualRunnableCount: AtomicInteger = AtomicInteger(0)

        // Let's arbitrarily set the expected size of Runnables as a random number between 10 and 30.
        val expectRunnableCount: Int = 10 + Random().nextInt(20)

        val testFunction: SkyFunction =
            SkyFunction { key, env ->
                val countDownLatch: CountDownLatch = CountDownLatch(expectRunnableCount)
                val executor: QuiescingExecutor = env.getParallelEvaluationExecutor()
                assertThat(executor).isSameInstanceAs(testExecutor)
                for (i in 0..<expectRunnableCount) {
                    executor.execute(
                        {
                            actualRunnableCount.incrementAndGet()
                            countDownLatch.countDown()
                        })
                }

                // We have to wait for all execution dispatched to external threads to complete before
                // returning.
                countDownLatch.await()
                com.google.devtools.build.skyframe.GraphTester.StringValue("parentValue")
            }

        evalParallelSkyFunctionAndVerifyResults(
            testFunction, testExecutor, actualRunnableCount, expectRunnableCount
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun customKeepGoingPredicate_sameForAllKeys(@TestParameter keepGoing: Boolean) {
        val evaluatedValues: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
        revalidationReceiver =
            DirtyAndInflightTrackingProgressReceiver(
                object : EvaluationProgressReceiver() {
                    public override fun evaluated(
                        skyKey: SkyKey?,
                        state: EvaluationState?,
                        newValue: SkyValue?,
                        newError: ErrorInfo?,
                        directDeps: GroupedDeps?
                    ) {
                        evaluatedValues.add(skyKey)
                    }
                })
        graph = InMemoryGraphImpl()
        val parentKey: SkyKey = skyKey("parent")
        val midKey: SkyKey = skyKey("mid")
        val errorKey: SkyKey = skyKey("error")
        tester.getOrCreate(errorKey).setHasError(true)
        tester.getOrCreate(parentKey).addDependency(midKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(midKey).addDependency(errorKey).setComputedValue(GraphTester.Companion.CONCATENATE)

        val keepGoingPredicate: java.util.function.Predicate<SkyKey?> =
            java.util.function.Predicate { key: SkyKey? -> keepGoing }
        val evaluator: ParallelEvaluator =
            makeEvaluator(
                graph,
                tester.getSkyFunctionMap(),
                EventFilter.FULL_STORAGE,
                Version.constant(),
                keepGoingPredicate
            )

        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            evaluator.eval(com.google.common.collect.ImmutableList.of<E?>(parentKey))

        assertThat(result.hasError()).isTrue()
        if (keepGoing) {
            Truth.assertThat(evaluatedValues).hasSize(3)
        } else {
            Truth.assertThat(evaluatedValues).hasSize(1) // errorKey
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun customKeepGoingPredicate_differentPerKey() {
        val evaluatedValues: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
        revalidationReceiver =
            DirtyAndInflightTrackingProgressReceiver(
                object : EvaluationProgressReceiver() {
                    public override fun evaluated(
                        skyKey: SkyKey?,
                        state: EvaluationState?,
                        newValue: SkyValue?,
                        newError: ErrorInfo?,
                        directDeps: GroupedDeps?
                    ) {
                        evaluatedValues.add(skyKey)
                    }
                })
        graph = InMemoryGraphImpl()
        val parentKey: SkyKey = skyKey("parent")
        val midKey: SkyKey = skyKey("mid")
        val errorKey: SkyKey = skyKey("error")
        tester.getOrCreate(errorKey).setHasError(true)
        tester.getOrCreate(parentKey).addDependency(midKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(midKey).addDependency(errorKey).setComputedValue(GraphTester.Companion.CONCATENATE)

        val forceKeepGoingOnErrorKeyPredicate: java.util.function.Predicate<SkyKey?> =
            java.util.function.Predicate { key: SkyKey? -> key.equals(errorKey) }
        val evaluator: ParallelEvaluator =
            makeEvaluator(
                graph,
                tester.getSkyFunctionMap(),
                EventFilter.FULL_STORAGE,
                Version.constant(),
                forceKeepGoingOnErrorKeyPredicate
            )

        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            evaluator.eval(com.google.common.collect.ImmutableList.of<E?>(parentKey))

        assertThat(result.hasError()).isTrue()
        Truth.assertThat(evaluatedValues).hasSize(2) // errorKey and midKey
    }

    companion object {
        private val CHILD_TYPE: SkyFunctionName = SkyFunctionName.createHermetic("child")
        private val PARENT_TYPE: SkyFunctionName = SkyFunctionName.createHermetic("parent")

        /**
         * Checks that errorInfo has many self-edge cycles, and that one of them is a self-edge of topKey,
         * if `selfEdge` is true.
         */
        private fun assertManyCycles(errorInfo: ErrorInfo, topKey: SkyKey?, selfEdge: Boolean) {
            Truth.assertThat(com.google.common.collect.Iterables.size(errorInfo.getCycleInfo())).isGreaterThan(1)
            Truth.assertThat(com.google.common.collect.Iterables.size(errorInfo.getCycleInfo())).isLessThan(50)
            var foundSelfEdge = false
            for (cycle in errorInfo.getCycleInfo()) {
                assertThat(cycle.cycle).hasSize(1) // Self-edge.
                if (!com.google.common.collect.Iterables.isEmpty(cycle.pathToCycle)) {
                    assertThat(cycle.pathToCycle).containsExactly(topKey).inOrder()
                } else {
                    assertThat(cycle.cycle).containsExactly(topKey).inOrder()
                    foundSelfEdge = true
                }
            }
            Truth.assertWithMessage("%s, %s", errorInfo, topKey).that(foundSelfEdge).isEqualTo(selfEdge)
        }
    }
}
