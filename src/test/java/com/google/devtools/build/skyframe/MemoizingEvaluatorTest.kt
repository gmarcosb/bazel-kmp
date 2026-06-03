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

/** Tests for a [MemoizingEvaluator].  */
abstract class MemoizingEvaluatorTest {
    protected var tester: MemoizingEvaluatorTester? = null
    protected var eventCollector: EventCollector? = null
    protected var reporter: ExtendedEventHandler? = null
    protected var emittedEventState: EmittedEventState? = null

    @Before
    fun initializeTester() {
        initializeTester(null)
        initializeReporter()
    }

    private fun initializeTester(customProgressReceiver: TrackingProgressReceiver?) {
        emittedEventState = EmittedEventState()
        tester = MemoizingEvaluatorTester()
        if (customProgressReceiver != null) {
            tester!!.setProgressReceiver(customProgressReceiver)
        }
        tester!!.initialize()
    }

    protected fun createTrackingProgressReceiver(
        checkEvaluationResults: Boolean
    ): TrackingProgressReceiver {
        return TrackingProgressReceiver(checkEvaluationResults)
    }

    @org.junit.After
    fun assertNoTrackedErrors() {
        TrackingAwaiter.Companion.INSTANCE.assertNoErrors()
    }

    protected val recordingDifferencer: RecordingDifferencer
        get() = SequencedRecordingDifferencer()

    @com.google.errorprone.annotations.ForOverride
    protected abstract fun getMemoizingEvaluator(
        functions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?,
        differencer: Differencer?,
        progressReceiver: EvaluationProgressReceiver?,
        graphInconsistencyReceiver: GraphInconsistencyReceiver?,
        eventFilter: EventFilter?
    ): MemoizingEvaluator

    /** Invoked immediately before each call to [MemoizingEvaluator.evaluate].  */
    @com.google.errorprone.annotations.ForOverride
    protected fun beforeEvaluation() {
    }

    /**
     * Invoked immediately after [MemoizingEvaluator.evaluate] with the [EvaluationResult]
     * or `null` if an exception was thrown.
     */
    @com.google.errorprone.annotations.ForOverride
    @Throws(java.lang.InterruptedException::class)
    protected fun afterEvaluation(result: EvaluationResult<*>?, context: EvaluationContext?) {
    }

    @com.google.errorprone.annotations.ForOverride
    protected fun cyclesDetected(): Boolean {
        return true
    }

    @com.google.errorprone.annotations.ForOverride
    protected fun resetSupported(): Boolean {
        return true
    }

    // TODO(jhorvitz): Skip irrelevant test cases if this is false.
    @com.google.errorprone.annotations.ForOverride
    protected fun incrementalitySupported(): Boolean {
        return true
    }

    private fun initializeReporter() {
        eventCollector = EventCollector()
        reporter =
            com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus(), eventCollector)
        tester!!.resetPlayedEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smoke() {
        tester.set("x", com.google.devtools.build.skyframe.GraphTester.StringValue("y"))
        val value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("x") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("y")
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun evaluateEmptySet() {
        tester.eval<SkyValue?>(false, *arrayOfNulls<SkyKey>(0))
        tester.eval<SkyValue?>(true, *arrayOfNulls<SkyKey>(0))
    }

    @org.junit.Test
    fun injectGraphTransformer_transformedGraphUsedForInMemoryGraph() {
        TruthJUnit.assume().that(tester.evaluator).isInstanceOf(AbstractInMemoryMemoizingEvaluator::class.java)
        val realGraph: InMemoryGraph? = tester.evaluator.getInMemoryGraph()
        val mockGraph: InMemoryGraph? = Mockito.mock<InMemoryGraph?>(InMemoryGraph::class.java)

        tester.evaluator.injectGraphTransformerForTesting(
            object : GraphTransformerForTesting() {
                public override fun transform(graph: InMemoryGraph?): InMemoryGraph? {
                    assertThat(graph).isSameInstanceAs(realGraph)
                    return mockGraph
                }

                public override fun transform(graph: ProcessableGraph?): ProcessableGraph? {
                    throw java.lang.AssertionError(graph)
                }
            })

        assertThat(tester.evaluator.getInMemoryGraph()).isSameInstanceAs(mockGraph)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun injectGraphTransformer_transformedGraphUsedForEvaluation() {
        val listener: com.google.devtools.build.skyframe.NotifyingHelper.Listener? =
            Mockito.mock<com.google.devtools.build.skyframe.NotifyingHelper.Listener?>(com.google.devtools.build.skyframe.NotifyingHelper.Listener::class.java)
        tester.evaluator.injectGraphTransformerForTesting(
            NotifyingHelper.Companion.makeNotifyingTransformer(listener)
        )
        val key: SkyKey = GraphTester.Companion.skyKey("key")
        val `val`: SkyValue = com.google.devtools.build.skyframe.GraphTester.StringValue("val")
        tester.getOrCreate(key).setConstantValue(`val`)

        assertThat(tester.evalAndGet( /* keepGoing= */false, key)).isEqualTo(`val`)

        Mockito.verify<com.google.devtools.build.skyframe.NotifyingHelper.Listener?>(listener).accept(
            key,
            com.google.devtools.build.skyframe.NotifyingHelper.EventType.GET_BATCH,
            com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
            Reason.PRE_OR_POST_EVALUATION
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun injectGraphTransformer_multipleTransformersAppliedInOrder() {
        val inner: com.google.devtools.build.skyframe.NotifyingHelper.Listener? =
            Mockito.mock<com.google.devtools.build.skyframe.NotifyingHelper.Listener?>(com.google.devtools.build.skyframe.NotifyingHelper.Listener::class.java)
        val outer: com.google.devtools.build.skyframe.NotifyingHelper.Listener? =
            Mockito.mock<com.google.devtools.build.skyframe.NotifyingHelper.Listener?>(com.google.devtools.build.skyframe.NotifyingHelper.Listener::class.java)
        tester.evaluator.injectGraphTransformerForTesting(
            NotifyingHelper.Companion.makeNotifyingTransformer(inner)
        )
        tester.evaluator.injectGraphTransformerForTesting(
            NotifyingHelper.Companion.makeNotifyingTransformer(outer)
        )
        val key: SkyKey = GraphTester.Companion.skyKey("key")
        val `val`: SkyValue = com.google.devtools.build.skyframe.GraphTester.StringValue("val")
        tester.getOrCreate(key).setConstantValue(`val`)

        assertThat(tester.evalAndGet( /* keepGoing= */false, key)).isEqualTo(`val`)

        val inOrder: InOrder = Mockito.inOrder(inner, outer)
        inOrder.verify<com.google.devtools.build.skyframe.NotifyingHelper.Listener?>(outer).accept(
            key,
            com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE,
            com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
            `val`
        )
        inOrder.verify<com.google.devtools.build.skyframe.NotifyingHelper.Listener?>(inner).accept(
            key,
            com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE,
            com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
            `val`
        )
        inOrder.verify<com.google.devtools.build.skyframe.NotifyingHelper.Listener?>(inner).accept(
            key,
            com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE,
            com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER,
            `val`
        )
        inOrder.verify<com.google.devtools.build.skyframe.NotifyingHelper.Listener?>(outer).accept(
            key,
            com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE,
            com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER,
            `val`
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidationWithNothingChanged() {
        tester.set("x", com.google.devtools.build.skyframe.GraphTester.StringValue("y")).setWarning("fizzlepop")
        var value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("x") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("y")
        assertThatEvents(eventCollector).containsExactly("fizzlepop")

        initializeReporter()
        tester!!.invalidate()
        value = tester!!.evalAndGet("x") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("y")
    }

    @org.junit.Test // Regression test for bug: "[skyframe-m1]: registerIfDone() crash".
    @Throws(java.lang.Exception::class)
    fun bubbleRace() {
        // The top-level value declares dependencies on a "badValue" in error, and a "sleepyValue"
        // which is very slow. After "badValue" fails, the builder interrupts the "sleepyValue" and
        // attempts to re-run "top" for error bubbling. Make sure this doesn't cause a precondition
        // failure because "top" still has an outstanding dep ("sleepyValue").
        tester
            .getOrCreate("top")
            .setBuilder(
                SkyFunction { skyKey, env ->
                    env.getValue(GraphTester.Companion.skyKey("sleepyValue"))
                    try {
                        env.getValueOrThrow(GraphTester.Companion.skyKey("badValue"), SomeErrorException::class.java)
                    } catch (e: SomeErrorException) {
                        // In order to trigger this bug, we need to request a dep on an already computed
                        // value.
                        env.getValue(GraphTester.Companion.skyKey("otherValue1"))
                    }
                    if (!env.valuesMissing()) {
                        throw java.lang.AssertionError("SleepyValue should always be unavailable")
                    }
                    null
                })
        tester
            .getOrCreate("sleepyValue")
            .setBuilder(
                SkyFunction { skyKey, env ->
                    java.lang.Thread.sleep(99999)
                    throw java.lang.AssertionError("I should have been interrupted")
                })
        tester.getOrCreate("badValue").addDependency("otherValue1").setHasError(true)
        tester.getOrCreate("otherValue1")
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("otherVal1"))

        val result: EvaluationResult<SkyValue?> = tester!!.eval<SkyValue?>(false, "top")
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasSingletonErrorThat(GraphTester.Companion.skyKey("top"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvProvidesTemporaryDirectDeps() {
        val counter: AtomicInteger = AtomicInteger()
        val deps: MutableList<SkyKey?> = Collections.synchronizedList<SkyKey?>(java.util.ArrayList<SkyKey?>())
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        val bottomKey: SkyKey = GraphTester.Companion.skyKey("bottom")
        val bottomValue: SkyValue = com.google.devtools.build.skyframe.GraphTester.StringValue("bottom")
        tester
            .getOrCreate(topKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    if (counter.getAndIncrement() > 0) {
                        deps.addAll(env.getTemporaryDirectDeps().getDepGroup(0))
                    } else {
                        assertThat(env.getTemporaryDirectDeps().numGroups()).isEqualTo(0)
                    }
                    env.getValue(bottomKey)
                })
        tester.getOrCreate(bottomKey).setConstantValue(bottomValue)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/true, "top")
        assertThat(result.get(topKey)).isEqualTo(bottomValue)
        Truth.assertThat(deps).containsExactly(bottomKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cachedErrorShutsDownThreadpool() {
        // When a node throws an error on the first build,
        val cachedErrorKey: SkyKey = GraphTester.Companion.skyKey("error")
        tester.getOrCreate(cachedErrorKey).setHasError(true)
        assertThat(tester.evalAndGetError( /*keepGoing=*/true, cachedErrorKey)).isNotNull()
        // And on the second build, it is requested as a dep,
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        tester.getOrCreate(topKey).addDependency(cachedErrorKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        // And another node throws an error, but waits to throw until the child error is thrown,
        val newErrorKey: SkyKey = GraphTester.Companion.skyKey("newError")
        tester
            .getOrCreate(newErrorKey)
            .setBuilder(
                com.google.devtools.build.skyframe.ChainedFunction.Builder()
                    .setWaitForException(true)
                    .setWaitToFinish(CountDownLatch(0))
                    .setValue(null)
                    .build()
            )
        // Then when evaluation happens,
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, newErrorKey, topKey)
        // The result has an error,
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasError()
        // But the new error is not persisted to the graph, since the child error shut down evaluation.
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(newErrorKey)
            .isNull()
    }

    @org.junit.Test
    fun interruptBitCleared() {
        val interruptKey: SkyKey = GraphTester.Companion.skyKey("interrupt")
        tester.getOrCreate(interruptKey).setBuilder(INTERRUPT_BUILDER)
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { tester!!.eval<SkyValue?>( /* keepGoing= */true, interruptKey) })
        Truth.assertThat(java.lang.Thread.interrupted()).isFalse()
    }

    @org.junit.Test
    fun crashAfterInterruptCrashes() {
        val failKey: SkyKey = GraphTester.Companion.skyKey("fail")
        val badInterruptkey: SkyKey = GraphTester.Companion.skyKey("bad-interrupt")
        // Given a SkyFunction implementation which is improperly coded to throw a runtime exception
        // when it is interrupted,
        val badInterruptStarted: CountDownLatch = CountDownLatch(1)
        tester
            .getOrCreate(badInterruptkey)
            .setBuilder(
                object : SkyFunction() {
                    public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue? {
                        badInterruptStarted.countDown()
                        try {
                            java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                            throw java.lang.AssertionError("Shouldn't have slept so long")
                        } catch (e: java.lang.InterruptedException) {
                            throw java.lang.RuntimeException("I don't like being woken up!", e)
                        }
                    }
                })
        // And another SkyFunction that waits for the first to start, and then throws,
        tester
            .getOrCreate(failKey)
            .setBuilder(
                ChainedFunction(
                    null,
                    badInterruptStarted,
                    null,  /*waitForException=*/
                    false,
                    null,
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )

        // When it is interrupted during evaluation (here, caused by the failure of the throwing
        // SkyFunction during a no-keep-going evaluation), then the ParallelEvaluator#evaluate call
        // throws a RuntimeException e where e.getCause() is the RuntimeException thrown by that
        // SkyFunction.
        val e: java.lang.RuntimeException? =
            org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
                java.lang.RuntimeException::class.java,
                org.junit.function.ThrowingRunnable {
                    tester!!.eval<SkyValue?>( /*keepGoing=*/false,
                        badInterruptkey,
                        failKey
                    )
                })
        Truth.assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("I don't like being woken up!")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun interruptAfterFailFails() {
        val failKey: SkyKey = GraphTester.Companion.skyKey("fail")
        val interruptedKey: SkyKey = GraphTester.Companion.skyKey("interrupted")
        // Given a SkyFunction implementation that is properly coded to as not to throw a
        // runtime exception when it is interrupted,
        val interruptStarted: CountDownLatch = CountDownLatch(1)
        tester
            .getOrCreate(interruptedKey)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue? {
                        interruptStarted.countDown()
                        java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                        throw java.lang.AssertionError("Shouldn't have slept so long")
                    }
                })
        // And another SkyFunction that waits for the first to start, and then throws,
        tester
            .getOrCreate(failKey)
            .setBuilder(
                ChainedFunction(
                    null,
                    interruptStarted,
                    null,  /*waitForException=*/
                    false,
                    null,
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )

        // When it is interrupted during evaluation (here, caused by the failure of a sibling node
        // during a no-keep-going evaluation),
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, interruptedKey, failKey)
        // Then the ParallelEvaluator#evaluate call returns an EvaluationResult that has no error for
        // the interrupted SkyFunction.
        assertWithMessage(result.toString()).that(result.hasError()).isTrue()
        assertWithMessage(result.toString()).that(result.getError(failKey)).isNotNull()
        assertWithMessage(result.toString()).that(result.getError(interruptedKey)).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deleteValues() {
        tester
            .getOrCreate("top")
            .setComputedValue(GraphTester.Companion.CONCATENATE)
            .addDependency("d1")
            .addDependency("d2")
            .addDependency("d3")
        tester.set("d1", com.google.devtools.build.skyframe.GraphTester.StringValue("1"))
        val d2: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("2")
        tester.set("d2", d2)
        val d3: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("3")
        tester.set("d3", d3)
        tester!!.eval<SkyValue?>(true, "top")

        tester!!.delete("d1")
        tester!!.eval<SkyValue?>(true, "d3")

        Truth.assertThat(tester!!.dirtyKeys).isEmpty()
        Truth.assertThat(tester!!.deletedKeys).isEqualTo(
            com.google.common.collect.ImmutableSet.of<Any?>(
                GraphTester.Companion.skyKey("d1"),
                GraphTester.Companion.skyKey("top")
            )
        )
        assertThat(tester!!.getExistingValue("top")).isNull()
        assertThat(tester!!.getExistingValue("d1")).isNull()
        assertThat(tester!!.getExistingValue("d2")).isEqualTo(d2)
        assertThat(tester!!.getExistingValue("d3")).isEqualTo(d3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deleteOldNodesTest() {
        val d2Key: SkyKey = GraphTester.Companion.nonHermeticKey("d2")
        tester
            .getOrCreate("top")
            .setComputedValue(GraphTester.Companion.CONCATENATE)
            .addDependency("d1")
            .addDependency(d2Key)
        tester.set("d1", com.google.devtools.build.skyframe.GraphTester.StringValue("one"))
        tester.set(d2Key, com.google.devtools.build.skyframe.GraphTester.StringValue("two"))
        tester!!.eval<SkyValue?>(true, "top")

        tester.set(d2Key, com.google.devtools.build.skyframe.GraphTester.StringValue("three"))
        tester!!.invalidate()
        tester!!.eval<SkyValue?>(true, d2Key)

        // The graph now contains the three above nodes (and ERROR_TRANSIENCE).
        assertThat(tester.evaluator.getValues().keySet())
            .containsExactly(
                GraphTester.Companion.skyKey("top"),
                GraphTester.Companion.skyKey("d1"),
                d2Key,
                ErrorTransienceValue.KEY
            )

        val noKeys = arrayOf<String?>()
        tester.evaluator.deleteDirty(2)
        tester.eval<SkyValue?>(true, *noKeys)

        // The top node's value is dirty, but less than two generations old, so it wasn't deleted.
        assertThat(tester.evaluator.getValues().keySet())
            .containsExactly(
                GraphTester.Companion.skyKey("top"),
                GraphTester.Companion.skyKey("d1"),
                d2Key,
                ErrorTransienceValue.KEY
            )

        tester.evaluator.deleteDirty(2)
        tester.eval<SkyValue?>(true, *noKeys)

        // The top node's value was dirty, and was two generations old, so it was deleted.
        assertThat(tester.evaluator.getValues().keySet())
            .containsExactly(GraphTester.Companion.skyKey("d1"), d2Key, ErrorTransienceValue.KEY)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deleteDirtyCleanedValue() {
        val leafKey: SkyKey = GraphTester.Companion.nonHermeticKey("leafKey")
        tester.getOrCreate(leafKey)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("value"))
        val topKey: SkyKey = GraphTester.Companion.skyKey("topKey")
        tester.getOrCreate(topKey).addDependency(leafKey).setComputedValue(GraphTester.Companion.CONCATENATE)

        assertThat(
            tester.evalAndGet( /*keepGoing=*/false,
                topKey
            )
        ).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("value"))
        failBuildAndRemoveValue(leafKey)
        tester.evaluator.deleteDirty(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deleteNonexistentValues() {
        tester.getOrCreate("d1").setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("1"))
        tester!!.delete("d1")
        tester!!.delete("d2")
        tester!!.eval<SkyValue?>(true, "d1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun signalValueEnqueued() {
        tester
            .getOrCreate("top1")
            .setComputedValue(GraphTester.Companion.CONCATENATE)
            .addDependency("d1")
            .addDependency("d2")
        tester.getOrCreate("top2").setComputedValue(GraphTester.Companion.CONCATENATE).addDependency("d3")
        tester.getOrCreate("top3")
        Truth.assertThat(tester!!.enqueuedValues).isEmpty()

        tester.set("d1", com.google.devtools.build.skyframe.GraphTester.StringValue("1"))
        tester.set("d2", com.google.devtools.build.skyframe.GraphTester.StringValue("2"))
        tester.set("d3", com.google.devtools.build.skyframe.GraphTester.StringValue("3"))
        tester!!.eval<SkyValue?>(true, "top1")
        Truth.assertThat(tester!!.enqueuedValues)
            .containsExactlyElementsIn(GraphTester.Companion.toSkyKeys("top1", "d1", "d2"))

        tester!!.eval<SkyValue?>(true, "top2")
        Truth.assertThat(tester!!.enqueuedValues)
            .containsExactlyElementsIn(
                GraphTester.Companion.toSkyKeys("top1", "d1", "d2", "top2", "d3")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun warningViaMultiplePaths() {
        tester.set("d1", com.google.devtools.build.skyframe.GraphTester.StringValue("d1")).setWarning("warn-d1")
        tester.set("d2", com.google.devtools.build.skyframe.GraphTester.StringValue("d2")).setWarning("warn-d2")
        tester.getOrCreate("top").setComputedValue(GraphTester.Companion.CONCATENATE).addDependency("d1")
            .addDependency("d2")
        initializeReporter()
        tester!!.evalAndGet("top")
        assertThatEvents(eventCollector).containsExactly("warn-d1", "warn-d2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun warningBeforeErrorOnFailFastBuild() {
        tester.set("dep", com.google.devtools.build.skyframe.GraphTester.StringValue("dep")).setWarning("warn-dep")
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        tester.getOrCreate(topKey).setHasError(true).addDependency("dep")
        for (i in 0..1) {
            initializeReporter()
            val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
                tester!!.eval<SkyValue?>(false, "top")
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
                .hasSingletonErrorThat(topKey)
                .hasExceptionThat()
                .hasMessageThat()
                .isEqualTo(topKey.toString())
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
                .hasSingletonErrorThat(topKey)
                .hasExceptionThat()
                .isInstanceOf(SomeErrorException::class.java)
            if (i == 0) {
                assertThatEvents(eventCollector).containsExactly("warn-dep")
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun warningAndErrorOnFailFastBuild() {
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        tester.set(topKey, com.google.devtools.build.skyframe.GraphTester.StringValue("top")).setWarning("warning msg")
            .setHasError(true)
        for (i in 0..1) {
            initializeReporter()
            val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
                tester!!.eval<SkyValue?>(false, "top")
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
                .hasSingletonErrorThat(topKey)
                .hasExceptionThat()
                .hasMessageThat()
                .isEqualTo(topKey.toString())
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
                .hasSingletonErrorThat(topKey)
                .hasExceptionThat()
                .isInstanceOf(SomeErrorException::class.java)
            if (i == 0) {
                assertThatEvents(eventCollector).containsExactly("warning msg")
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun warningAndErrorOnFailFastBuildAfterKeepGoingBuild() {
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        tester.set(topKey, com.google.devtools.build.skyframe.GraphTester.StringValue("top")).setWarning("warning msg")
            .setHasError(true)
        for (i in 0..1) {
            initializeReporter()
            val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
                tester!!.eval<SkyValue?>(i == 0, "top")
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
                .hasSingletonErrorThat(topKey)
                .hasExceptionThat()
                .hasMessageThat()
                .isEqualTo(topKey.toString())
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
                .hasSingletonErrorThat(topKey)
                .hasExceptionThat()
                .isInstanceOf(SomeErrorException::class.java)
            if (i == 0) {
                assertThatEvents(eventCollector).containsExactly("warning msg")
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun twoTLTsOnOneWarningValue() {
        tester.set("t1", com.google.devtools.build.skyframe.GraphTester.StringValue("t1")).addDependency("dep")
        tester.set("t2", com.google.devtools.build.skyframe.GraphTester.StringValue("t2")).addDependency("dep")
        tester.set("dep", com.google.devtools.build.skyframe.GraphTester.StringValue("dep"))
            .setWarning("look both ways before crossing")
        initializeReporter()
        tester!!.eval<SkyValue?>( /* keepGoing= */false, "t1", "t2")
        assertThatEvents(eventCollector).containsExactly("look both ways before crossing")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorValueDepOnWarningValue() {
        tester.getOrCreate("error-value").setHasError(true).addDependency("warning-value")
        tester
            .set("warning-value", com.google.devtools.build.skyframe.GraphTester.StringValue("warning-value"))
            .setWarning("don't chew with your mouth open")

        initializeReporter()
        tester!!.evalAndGetError( /* keepGoing= */true, "error-value")
        assertThatEvents(eventCollector).containsExactly("don't chew with your mouth open")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun progressMessageOnlyPrintedTheFirstTime() {
        // Skyframe does not store progress messages. Here we only see the message on the first build.
        tester.set("x", com.google.devtools.build.skyframe.GraphTester.StringValue("y"))
            .setProgress("just letting you know")

        var value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("x") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("y")
        assertThatEvents(eventCollector).containsExactly("just letting you know")

        initializeReporter()
        value = tester!!.evalAndGet("x") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("y")
        assertThatEvents(eventCollector).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun depMessageBeforeNodeMessageOrNodeValue() {
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        val depWarningEmitted: AtomicBoolean = AtomicBoolean(false)
        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (key.equals(top) && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE) {
                    Truth.assertThat(depWarningEmitted.get()).isTrue()
                }
            },  /*deterministic=*/
            false
        )
        val depWarning = "dep warning"
        val topWarning: com.google.devtools.build.lib.events.Event =
            com.google.devtools.build.lib.events.Event.warn("top warning")
        reporter =
            object : DelegatingEventHandler(reporter) {
                override fun handle(e: com.google.devtools.build.lib.events.Event) {
                    if (e.getMessage() == depWarning) {
                        depWarningEmitted.set(true)
                    }
                    if (e == topWarning) {
                        Truth.assertThat(depWarningEmitted.get()).isTrue()
                    }
                    super.handle(e)
                }
            }
        val leaf: SkyKey = GraphTester.Companion.skyKey("leaf")
        tester.getOrCreate(leaf).setWarning(depWarning)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))
        tester
            .getOrCreate(top)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        val depValue: SkyValue? = env.getValue(leaf)
                        if (depValue != null) {
                            // Default GraphTester implementation warns before requesting deps, which doesn't
                            // work
                            // for ordering assertions with memoizing evaluator subclsses that don't store
                            // events
                            // and instead just pass them through directly. By warning after the dep is done
                            // we
                            // avoid that issue.
                            env.getListener().handle(topWarning)
                        }
                        return depValue
                    }
                })
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, top)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(top)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))
        assertThatEvents(eventCollector).containsExactly(depWarning, topWarning.getMessage()).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidationWithChangeAndThenNothingChanged() {
        val bKey: SkyKey = GraphTester.Companion.nonHermeticKey("b")
        tester.getOrCreate("a").addDependency(bKey).setComputedValue(GraphTester.Companion.COPY)
        tester.set(bKey, com.google.devtools.build.skyframe.GraphTester.StringValue("y"))
        val original: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("a") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(original.getValue()).isEqualTo("y")
        tester.set(bKey, com.google.devtools.build.skyframe.GraphTester.StringValue("z"))
        tester!!.invalidate()
        val old: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("a") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(old.getValue()).isEqualTo("z")
        tester!!.invalidate()
        val current: com.google.devtools.build.skyframe.GraphTester.StringValue? =
            tester!!.evalAndGet("a") as com.google.devtools.build.skyframe.GraphTester.StringValue?
        Truth.assertThat(current).isEqualTo(old)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noKeepGoingErrorAfterKeepGoingError() {
        val topKey: SkyKey = GraphTester.Companion.nonHermeticKey("top")
        val errorKey: SkyKey = GraphTester.Companion.skyKey("error")
        tester.getOrCreate(errorKey).setHasError(true)
        tester.getOrCreate(topKey).addDependency(errorKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /* keepGoing= */true, topKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(topKey)
        tester.getOrCreate(topKey,  /* markAsModified= */true)
        tester!!.invalidate()
        result = tester!!.eval<SkyValue?>( /* keepGoing= */false, topKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(topKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transientErrorValueInvalidation() {
        // Verify that invalidating errors causes all transient error values to be rerun.
        tester
            .getOrCreate("error-value")
            .setHasTransientError(true)
            .setProgress("just letting you know")

        tester!!.evalAndGetError( /*keepGoing=*/true, "error-value")
        assertThatEvents(eventCollector).containsExactly("just letting you know")

        // Change the progress message.
        tester
            .getOrCreate("error-value")
            .setHasTransientError(true)
            .setProgress("letting you know more")

        // Without invalidating errors, we shouldn't show the new progress message.
        for (i in 0..1) {
            initializeReporter()
            tester!!.evalAndGetError( /*keepGoing=*/true, "error-value")
            assertThatEvents(eventCollector).isEmpty()
        }

        // When invalidating errors, we should show the new progress message.
        initializeReporter()
        tester!!.invalidateTransientErrors()
        tester!!.evalAndGetError( /*keepGoing=*/true, "error-value")
        assertThatEvents(eventCollector).containsExactly("letting you know more")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transientPruning() {
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        tester.getOrCreate("top").setHasTransientError(true).addDependency(leaf)
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
        tester!!.evalAndGetError( /*keepGoing=*/true, "top")
        tester.getOrCreate(leaf,  /*markAsModified=*/true)
        tester!!.invalidate()
        tester!!.evalAndGetError( /*keepGoing=*/true, "top")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun simpleDependency() {
        tester.getOrCreate("ab").addDependency("a").setComputedValue(GraphTester.Companion.COPY)
        tester.set("a", com.google.devtools.build.skyframe.GraphTester.StringValue("me"))
        val value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("ab") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("me")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalSimpleDependency() {
        val aKey: SkyKey = GraphTester.Companion.nonHermeticKey("a")
        tester.getOrCreate("ab").addDependency(aKey).setComputedValue(GraphTester.Companion.COPY)
        tester.set(aKey, com.google.devtools.build.skyframe.GraphTester.StringValue("me"))
        tester!!.evalAndGet("ab")

        tester.set(aKey, com.google.devtools.build.skyframe.GraphTester.StringValue("other"))
        tester!!.invalidate()
        val value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("ab") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("other")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun diamondDependency() {
        val diamondBase: SkyKey = setupDiamondDependency()
        tester.set(diamondBase, com.google.devtools.build.skyframe.GraphTester.StringValue("me"))
        val value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("a") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("meme")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalDiamondDependency() {
        val diamondBase: SkyKey = setupDiamondDependency()
        tester.set(diamondBase, com.google.devtools.build.skyframe.GraphTester.StringValue("me"))
        tester!!.evalAndGet("a")

        tester.set(diamondBase, com.google.devtools.build.skyframe.GraphTester.StringValue("other"))
        tester!!.invalidate()
        val value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("a") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("otherother")
    }

    private fun setupDiamondDependency(): SkyKey {
        val diamondBase: SkyKey = GraphTester.Companion.nonHermeticKey("d")
        tester.getOrCreate("a").addDependency("b").addDependency("c")
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate("b").addDependency(diamondBase).setComputedValue(GraphTester.Companion.COPY)
        tester.getOrCreate("c").addDependency(diamondBase).setComputedValue(GraphTester.Companion.COPY)
        return diamondBase
    }

    // ParallelEvaluator notifies ValueProgressReceiver of already-built top-level values in error: we
    // built "top" and "mid" as top-level targets; "mid" contains an error. We make sure "mid" is
    // built as a dependency of "top" before enqueuing mid as a top-level target (by using a latch),
    // so that the top-level enqueuing finds that mid has already been built. The progress receiver
    // should be notified that mid has been built.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun alreadyAnalyzedBadTarget() {
        val mid: SkyKey = GraphTester.Companion.skyKey("zzmid")
        val valueSet: CountDownLatch = CountDownLatch(1)
        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (!key.equals(mid)) {
                    return@injectGraphListenerForTesting
                }
                when (type) {
                    com.google.devtools.build.skyframe.NotifyingHelper.EventType.ADD_REVERSE_DEP -> if (context == null) {
                        // Context is null when we are enqueuing this value as a top-level job.
                        TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(valueSet, "value not set")
                    }

                    com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE -> valueSet.countDown()
                    else -> {}
                }
            },  /* deterministic= */
            true
        )
        val top: SkyKey = GraphTester.Companion.skyKey("aatop")
        tester.getOrCreate(top).addDependency(mid).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(mid).setHasError(true)
        tester!!.eval<SkyValue?>( /* keepGoing= */false, top, mid)
        Truth.assertThat(valueSet.getCount()).isEqualTo(0L)
        Truth.assertThat(tester.progressReceiver.evaluated).containsExactly(mid)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun receiverToldOfVerifiedValueDependingOnCycle() {
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        val cycle: SkyKey = GraphTester.Companion.skyKey("cycle")
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))
        tester.getOrCreate(cycle).addDependency(cycle)
        tester.getOrCreate(top).addDependency(leaf).addDependency(cycle)
        tester!!.eval<SkyValue?>( /* keepGoing= */true, top)
        Truth.assertThat(tester.progressReceiver.evaluated).containsExactly(leaf, top, cycle)
        tester.progressReceiver.clear()
        tester.getOrCreate(leaf,  /* markAsModified= */true)
        tester!!.invalidate()
        tester!!.eval<SkyValue?>( /* keepGoing= */true, top)
        Truth.assertThat(tester.progressReceiver.evaluated).containsExactly(leaf, top)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalAddedDependency() {
        val aKey: SkyKey = GraphTester.Companion.nonHermeticKey("a")
        val bKey: SkyKey = GraphTester.Companion.nonHermeticKey("b")
        tester.getOrCreate(aKey).addDependency(bKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.set(bKey, com.google.devtools.build.skyframe.GraphTester.StringValue("first"))
        tester.set("c", com.google.devtools.build.skyframe.GraphTester.StringValue("second"))
        tester.evalAndGet( /* keepGoing= */false, aKey)

        tester.getOrCreate(aKey).addDependency("c")
        tester.set(bKey, com.google.devtools.build.skyframe.GraphTester.StringValue("now"))
        tester!!.invalidate()
        val value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester.evalAndGet( /* keepGoing= */false,
                aKey
            ) as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("nowsecond")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun manyValuesDependOnSingleValue() {
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        val values = arrayOfNulls<String>(TEST_NODE_COUNT)
        for (i in values.indices) {
            values[i] = i.toString()
            tester.getOrCreate(values[i]).addDependency(leaf).setComputedValue(GraphTester.Companion.COPY)
        }
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))

        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester.eval<SkyValue?>( /* keepGoing= */false, *values)
        for (value in values) {
            val actual: SkyValue? = result.get(GraphTester.Companion.skyKey(value))
            assertThat(actual).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))
        }

        for (j in 0..<TESTED_NODES) {
            tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("other" + j))
            tester!!.invalidate()
            result = tester.eval<SkyValue?>( /* keepGoing= */false, *values)
            for (i in values.indices) {
                val actual: SkyValue? = result.get(GraphTester.Companion.skyKey(values[i]))
                Truth.assertWithMessage("Run %s, value %s", j, i)
                    .that(actual)
                    .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("other" + j))
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun singleValueDependsOnManyValues() {
        val values: Array<SkyKey?> = arrayOfNulls<SkyKey>(TEST_NODE_COUNT)
        val expected: java.lang.StringBuilder = java.lang.StringBuilder()
        for (i in values.indices) {
            val iString = i.toString()
            values[i] = GraphTester.Companion.nonHermeticKey(iString)
            tester.set(values[i], com.google.devtools.build.skyframe.GraphTester.StringValue(iString))
            expected.append(iString)
        }
        val rootKey: SkyKey = GraphTester.Companion.skyKey("root")
        val value: TestFunction = tester.getOrCreate(rootKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        for (skyKey in values) {
            value.addDependency(skyKey)
        }

        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /* keepGoing= */false, rootKey)
        assertThat(result.get(rootKey)).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue(expected.toString()))

        for (j in 0..9) {
            expected.setLength(0)
            for (i in values.indices) {
                val s = "other" + i + " " + j
                tester.set(values[i], com.google.devtools.build.skyframe.GraphTester.StringValue(s))
                expected.append(s)
            }
            tester!!.invalidate()

            result = tester!!.eval<SkyValue?>( /* keepGoing= */false, rootKey)
            assertThat(result.get(rootKey)).isEqualTo(
                com.google.devtools.build.skyframe.GraphTester.StringValue(
                    expected.toString()
                )
            )
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun twoRailLeftRightDependencies() {
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        val leftValues = arrayOfNulls<String>(TEST_NODE_COUNT)
        val rightValues = arrayOfNulls<String>(TEST_NODE_COUNT)
        for (i in leftValues.indices) {
            leftValues[i] = "left-" + i
            rightValues[i] = "right-" + i
            if (i == 0) {
                tester.getOrCreate(leftValues[i]).addDependency(leaf).setComputedValue(GraphTester.Companion.COPY)
                tester.getOrCreate(rightValues[i]).addDependency(leaf).setComputedValue(GraphTester.Companion.COPY)
            } else {
                tester
                    .getOrCreate(leftValues[i])
                    .addDependency(leftValues[i - 1])
                    .addDependency(rightValues[i - 1])
                    .setComputedValue(PassThroughSelected(GraphTester.Companion.skyKey(leftValues[i - 1])))
                tester
                    .getOrCreate(rightValues[i])
                    .addDependency(leftValues[i - 1])
                    .addDependency(rightValues[i - 1])
                    .setComputedValue(PassThroughSelected(GraphTester.Companion.skyKey(rightValues[i - 1])))
            }
        }
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))

        val lastLeft = "left-" + (TEST_NODE_COUNT - 1)
        val lastRight = "right-" + (TEST_NODE_COUNT - 1)

        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /* keepGoing= */false, lastLeft, lastRight)
        assertThat(result.get(GraphTester.Companion.skyKey(lastLeft))).isEqualTo(
            com.google.devtools.build.skyframe.GraphTester.StringValue(
                "leaf"
            )
        )
        assertThat(result.get(GraphTester.Companion.skyKey(lastRight))).isEqualTo(
            com.google.devtools.build.skyframe.GraphTester.StringValue(
                "leaf"
            )
        )

        for (j in 0..<TESTED_NODES) {
            val value = "other" + j
            tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue(value))
            tester!!.invalidate()
            result = tester!!.eval<SkyValue?>( /* keepGoing= */false, lastLeft, lastRight)
            assertThat(result.get(GraphTester.Companion.skyKey(lastLeft))).isEqualTo(
                com.google.devtools.build.skyframe.GraphTester.StringValue(
                    value
                )
            )
            assertThat(result.get(GraphTester.Companion.skyKey(lastRight))).isEqualTo(
                com.google.devtools.build.skyframe.GraphTester.StringValue(
                    value
                )
            )
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noKeepGoingAfterKeepGoingCycle() {
        initializeTester()
        val aKey: SkyKey = GraphTester.Companion.skyKey("a")
        val bKey: SkyKey = GraphTester.Companion.skyKey("b")
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        val midKey: SkyKey = GraphTester.Companion.skyKey("mid")
        val goodKey: SkyKey = GraphTester.Companion.skyKey("good")
        val goodValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("good")
        tester.set(goodKey, goodValue)
        tester.getOrCreate(topKey).addDependency(midKey)
        tester.getOrCreate(midKey).addDependency(aKey)
        tester.getOrCreate(aKey).addDependency(bKey)
        tester.getOrCreate(bKey).addDependency(aKey)
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /* keepGoing= */true, topKey, goodKey)
        assertThat(result.get(goodKey)).isEqualTo(goodValue)
        assertThat(result.get(topKey)).isNull()
        var errorInfo: ErrorInfo = result.getError(topKey)
        var cycleInfo: CycleInfo? = com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo())
        if (cyclesDetected()) {
            assertThat(cycleInfo.cycle).containsExactly(aKey, bKey).inOrder()
            assertThat(cycleInfo.pathToCycle).containsExactly(topKey, midKey).inOrder()
        }

        tester!!.invalidate()
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey, goodKey)
        assertThat(result.get(topKey)).isNull()
        errorInfo = result.getError(topKey)
        cycleInfo = com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo())
        if (cyclesDetected()) {
            assertThat(cycleInfo.cycle).containsExactly(aKey, bKey).inOrder()
            assertThat(cycleInfo.pathToCycle).containsExactly(topKey, midKey).inOrder()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun keepGoingCycleAlreadyPresent() {
        val selfEdge: SkyKey = GraphTester.Companion.skyKey("selfEdge")
        tester.getOrCreate(selfEdge).addDependency(selfEdge).setComputedValue(GraphTester.Companion.CONCATENATE)
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/true, selfEdge)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasError()
        val cycleInfo: CycleInfo? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(result.getError(selfEdge).getCycleInfo())
        if (cyclesDetected()) {
            CycleInfoSubjectFactory.Companion.assertThat(cycleInfo).hasCycleThat().containsExactly(selfEdge)
            CycleInfoSubjectFactory.Companion.assertThat(cycleInfo).hasPathToCycleThat().isEmpty()
        }
        val parent: SkyKey = GraphTester.Companion.skyKey("parent")
        tester.getOrCreate(parent).addDependency(selfEdge).setComputedValue(GraphTester.Companion.CONCATENATE)
        val result2: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/true, parent)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasError()
        val cycleInfo2: CycleInfo? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(result2.getError(parent).getCycleInfo())
        if (cyclesDetected()) {
            CycleInfoSubjectFactory.Companion.assertThat(cycleInfo2).hasCycleThat().containsExactly(selfEdge)
            CycleInfoSubjectFactory.Companion.assertThat(cycleInfo2).hasPathToCycleThat().containsExactly(parent)
        }
    }

    @Throws(java.lang.Exception::class)
    private fun changeCycle(keepGoing: Boolean) {
        val aKey: SkyKey = GraphTester.Companion.skyKey("a")
        val bKey: SkyKey = GraphTester.Companion.nonHermeticKey("b")
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        val midKey: SkyKey = GraphTester.Companion.skyKey("mid")
        tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(GraphTester.Companion.COPY)
        tester.getOrCreate(midKey).addDependency(aKey).setComputedValue(GraphTester.Companion.COPY)
        tester.getOrCreate(aKey).addDependency(bKey).setComputedValue(GraphTester.Companion.COPY)
        tester.getOrCreate(bKey).addDependency(aKey)
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>(keepGoing, topKey)
        assertThat(result.get(topKey)).isNull()
        val errorInfo: ErrorInfo = result.getError(topKey)
        val cycleInfo: CycleInfo? = com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo())
        if (cyclesDetected()) {
            assertThat(cycleInfo.cycle).containsExactly(aKey, bKey).inOrder()
            assertThat(cycleInfo.pathToCycle).containsExactly(topKey, midKey).inOrder()
        }

        tester.getOrCreate(bKey).removeDependency(aKey)
        tester.set(bKey, com.google.devtools.build.skyframe.GraphTester.StringValue("bValue"))
        tester!!.invalidate()
        result = tester!!.eval<SkyValue?>(keepGoing, topKey)
        assertThat(result.get(topKey)).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("bValue"))
        assertThat(result.getError(topKey)).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun changeCycle_NoKeepGoing() {
        changeCycle(false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun changeCycle_KeepGoing() {
        changeCycle(true)
    }

    /**
     * @see ParallelEvaluatorTest.cycleAboveIndependentCycle
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cycleAboveIndependentCycle() {
        makeGraphDeterministic()
        val aKey: SkyKey = GraphTester.Companion.skyKey("a")
        val bKey: SkyKey = GraphTester.Companion.skyKey("b")
        val cKey: SkyKey = GraphTester.Companion.nonHermeticKey("c")
        val leafKey: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        // When aKey depends on leafKey and bKey,
        tester
            .getOrCreate(aKey)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        env.getValuesAndExceptions(com.google.common.collect.ImmutableList.of<E?>(leafKey, bKey))
                        return null
                    }
                })
        // And bKey depends on cKey,
        tester.getOrCreate(bKey).addDependency(cKey)
        // And cKey depends on aKey and bKey in that order,
        tester.getOrCreate(cKey).addDependency(aKey).addDependency(bKey)
        // And leafKey is a leaf node,
        tester.set(leafKey, com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
        // Then when we evaluate,
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/true, aKey)
        // aKey has an error,
        assertThat(result.get(aKey)).isNull()
        if (cyclesDetected()) {
            // And both cycles were found underneath aKey: the (aKey->bKey->cKey) cycle, and the
            // aKey->(bKey->cKey) cycle. This is because cKey depended on aKey and then bKey, so it pushed
            // them down on the stack in that order, so bKey was processed first. It found its cycle, then
            // popped off the stack, and then aKey was processed and found its cycle.
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
                .hasErrorEntryForKeyThat(aKey)
                .hasCycleInfoThat()
                .containsExactly(
                    CycleInfo.createCycleInfo(com.google.common.collect.ImmutableList.of<E?>(aKey, bKey, cKey)),
                    CycleInfo.createCycleInfo(
                        com.google.common.collect.ImmutableList.of<E?>(aKey),
                        com.google.common.collect.ImmutableList.of<E?>(bKey, cKey)
                    )
                )
        } else {
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
                .hasErrorEntryForKeyThat(aKey)
                .hasCycleInfoThat()
                .hasSize(1)
        }
        // When leafKey is changed, so that aKey will be marked as NEEDS_REBUILDING,
        tester.set(leafKey, com.google.devtools.build.skyframe.GraphTester.StringValue("crunchy"))
        // And cKey is invalidated, so that cycle checking will have to explore the full graph,
        tester.getOrCreate(cKey,  /*markAsModified=*/true)
        tester!!.invalidate()
        // Then when we evaluate,
        val result2: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/true, aKey)
        // Things are just as before.
        assertThat(result2.get(aKey)).isNull()
        if (cyclesDetected()) {
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
                .hasErrorEntryForKeyThat(aKey)
                .hasCycleInfoThat()
                .containsExactly(
                    CycleInfo.createCycleInfo(com.google.common.collect.ImmutableList.of<E?>(aKey, bKey, cKey)),
                    CycleInfo.createCycleInfo(
                        com.google.common.collect.ImmutableList.of<E?>(aKey),
                        com.google.common.collect.ImmutableList.of<E?>(bKey, cKey)
                    )
                )
        } else {
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
                .hasErrorEntryForKeyThat(aKey)
                .hasCycleInfoThat()
                .hasSize(1)
        }
    }

    /** Regression test: "crash in cycle checker with dirty values".  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cycleAndSelfEdgeWithDirtyValue() {
        initializeTester()
        // The cycle detection algorithm non-deterministically traverses into children nodes, so
        // use explicit determinism.
        makeGraphDeterministic()
        val cycleKey1: SkyKey = GraphTester.Companion.nonHermeticKey("ZcycleKey1")
        val cycleKey2: SkyKey = GraphTester.Companion.skyKey("AcycleKey2")
        tester
            .getOrCreate(cycleKey1)
            .addDependency(cycleKey2)
            .addDependency(cycleKey1)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(cycleKey2).addDependency(cycleKey1).setComputedValue(GraphTester.Companion.COPY)
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /* keepGoing= */true, cycleKey1)
        assertThat(result.get(cycleKey1)).isNull()
        var errorInfo: ErrorInfo = result.getError(cycleKey1)
        var cycleInfo: CycleInfo? = com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo())
        if (cyclesDetected()) {
            assertThat(cycleInfo.cycle).containsExactly(cycleKey1).inOrder()
            assertThat(cycleInfo.pathToCycle).isEmpty()
        }
        tester.getOrCreate(cycleKey1,  /*markAsModified=*/true)
        tester!!.invalidate()
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/true, cycleKey1, cycleKey2)
        assertThat(result.get(cycleKey1)).isNull()
        errorInfo = result.getError(cycleKey1)
        cycleInfo = com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo())
        if (cyclesDetected()) {
            assertThat(cycleInfo.cycle).containsExactly(cycleKey1).inOrder()
            assertThat(cycleInfo.pathToCycle).isEmpty()
        }
        cycleInfo =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                tester.evaluator.getExistingErrorForTesting(cycleKey2).getCycleInfo()
            )
        if (cyclesDetected()) {
            assertThat(cycleInfo.cycle).containsExactly(cycleKey1).inOrder()
            assertThat(cycleInfo.pathToCycle).containsExactly(cycleKey2).inOrder()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cycleAndSelfEdgeWithDirtyValueInSameGroup() {
        makeGraphDeterministic()
        val cycleKey1: SkyKey = GraphTester.Companion.skyKey("ZcycleKey1")
        val cycleKey2: SkyKey = GraphTester.Companion.skyKey("AcycleKey2")
        tester.getOrCreate(cycleKey2).addDependency(cycleKey2).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester
            .getOrCreate(cycleKey1)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        // The order here is important -- 2 before 1.
                        val result: SkyframeLookupResult? =
                            env.getValuesAndExceptions(
                                com.google.common.collect.ImmutableList.of<E?>(
                                    cycleKey2,
                                    cycleKey1
                                )
                            )
                        com.google.common.base.Preconditions.checkState(env.valuesMissing(), result)
                        return null
                    }
                })
        // Evaluate twice to make sure nothing strange happens with invalidation the second time.
        for (i in 0..1) {
            val result: EvaluationResult<SkyValue?> = tester!!.eval<SkyValue?>( /*keepGoing=*/true, cycleKey1)
            assertThat(result.get(cycleKey1)).isNull()
            val errorInfo: ErrorInfo = result.getError(cycleKey1)
            val cycleInfo: CycleInfo? = com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo())
            if (cyclesDetected()) {
                assertThat(cycleInfo.cycle).containsExactly(cycleKey1).inOrder()
                assertThat(cycleInfo.pathToCycle).isEmpty()
            }
        }
    }

    /** Regression test: "crash in cycle checker with dirty values".  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cycleWithDirtyValue() {
        val cycleKey1: SkyKey = GraphTester.Companion.nonHermeticKey("cycleKey1")
        val cycleKey2: SkyKey = GraphTester.Companion.skyKey("cycleKey2")
        tester.getOrCreate(cycleKey1).addDependency(cycleKey2).setComputedValue(GraphTester.Companion.COPY)
        tester.getOrCreate(cycleKey2).addDependency(cycleKey1).setComputedValue(GraphTester.Companion.COPY)
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /* keepGoing= */true, cycleKey1)
        assertThat(result.get(cycleKey1)).isNull()
        var errorInfo: ErrorInfo = result.getError(cycleKey1)
        var cycleInfo: CycleInfo? = com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo())
        if (cyclesDetected()) {
            assertThat(cycleInfo.cycle).containsExactly(cycleKey1, cycleKey2).inOrder()
            assertThat(cycleInfo.pathToCycle).isEmpty()
        }
        tester.getOrCreate(cycleKey1,  /*markAsModified=*/true)
        tester!!.invalidate()
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/true, cycleKey1)
        assertThat(result.get(cycleKey1)).isNull()
        errorInfo = result.getError(cycleKey1)
        cycleInfo = com.google.common.collect.Iterables.getOnlyElement<T?>(errorInfo.getCycleInfo())
        if (cyclesDetected()) {
            assertThat(cycleInfo.cycle).containsExactly(cycleKey1, cycleKey2).inOrder()
            assertThat(cycleInfo.pathToCycle).isEmpty()
        }
    }

    /**
     * [ParallelEvaluator] can be configured to not store errors alongside recovered values.
     * 
     * @param errorsStoredAlongsideValues true if we expect Skyframe to store the error for the cycle
     * in ErrorInfo. If true, supportsTransientExceptions must be true as well.
     * @param supportsTransientExceptions true if we expect Skyframe to mark an ErrorInfo as transient
     * for certain exception types.
     * @param useTransientError true if the test should set the [TestFunction] it creates to
     * throw a transient error.
     */
    @Throws(java.lang.Exception::class)
    protected fun parentOfCycleAndErrorInternal(
        errorsStoredAlongsideValues: Boolean,
        supportsTransientExceptions: Boolean,
        useTransientError: Boolean
    ) {
        initializeTester()
        if (errorsStoredAlongsideValues) {
            com.google.common.base.Preconditions.checkArgument(supportsTransientExceptions)
        }
        val cycleKey1: SkyKey = GraphTester.Companion.skyKey("cycleKey1")
        val cycleKey2: SkyKey = GraphTester.Companion.skyKey("cycleKey2")
        val mid: SkyKey = GraphTester.Companion.skyKey("mid")
        val errorKey: SkyKey = GraphTester.Companion.skyKey("errorKey")
        tester.getOrCreate(cycleKey1).addDependency(cycleKey2).setComputedValue(GraphTester.Companion.COPY)
        tester.getOrCreate(cycleKey2).addDependency(cycleKey1).setComputedValue(GraphTester.Companion.COPY)
        val errorFunction: TestFunction = tester.getOrCreate(errorKey)
        if (useTransientError) {
            errorFunction.setHasTransientError(true)
        } else {
            errorFunction.setHasError(true)
        }
        tester
            .getOrCreate(mid)
            .addErrorDependency(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("recovered"))
            .setComputedValue(GraphTester.Companion.COPY)
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        val topEvaluated: CountDownLatch = CountDownLatch(2)
        tester
            .getOrCreate(top)
            .setBuilder(
                ChainedFunction(
                    topEvaluated,
                    null,
                    null,
                    false,
                    com.google.devtools.build.skyframe.GraphTester.StringValue("unused"),
                    com.google.common.collect.ImmutableList.of<SkyKey?>(mid, cycleKey1)
                )
            )
        val evalResult: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>(true, top)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(evalResult).hasError()
        val errorInfo: ErrorInfo = evalResult.getError(top)
        Truth.assertThat(topEvaluated.getCount()).isEqualTo(1)
        if (errorsStoredAlongsideValues) {
            if (useTransientError) {
                // The parent should be transitively transient, since it transitively depends on a transient
                // error.
                assertThat(errorInfo.isTransitivelyTransient).isTrue()
            } else {
                ErrorInfoSubjectFactory.Companion.assertThatErrorInfo(errorInfo).isNotTransient()
            }
            assertThat(errorInfo.getException())
                .hasMessageThat()
                .isEqualTo(GraphTester.Companion.NODE_TYPE.name + ":errorKey")
        } else {
            // When errors are not stored alongside values, transient errors that are recovered from do
            // not make the parent transient
            if (supportsTransientExceptions) {
                ErrorInfoSubjectFactory.Companion.assertThatErrorInfo(errorInfo).isTransient()
                ErrorInfoSubjectFactory.Companion.assertThatErrorInfo(errorInfo).hasExceptionThat().isNotNull()
            } else {
                ErrorInfoSubjectFactory.Companion.assertThatErrorInfo(errorInfo).isNotTransient()
                ErrorInfoSubjectFactory.Companion.assertThatErrorInfo(errorInfo).hasExceptionThat().isNull()
            }
        }
        if (cyclesDetected()) {
            ErrorInfoSubjectFactory.Companion.assertThatErrorInfo(errorInfo)
                .hasCycleInfoThat()
                .containsExactly(
                    CycleInfo.createCycleInfo(
                        com.google.common.collect.ImmutableList.of<E?>(top),
                        com.google.common.collect.ImmutableList.of<E?>(cycleKey1, cycleKey2)
                    )
                )
        } else {
            ErrorInfoSubjectFactory.Companion.assertThatErrorInfo(errorInfo).hasCycleInfoThat().hasSize(1)
        }
        // But the parent itself shouldn't have a direct dep on the special error transience node.
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(evalResult)
            .hasDirectDepsInGraphThat(top)
            .doesNotContain(ErrorTransienceValue.KEY)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parentOfCycleAndError() {
        parentOfCycleAndErrorInternal( /*errorsStoredAlongsideValues=*/
            true,  /*supportsTransientExceptions=*/
            true,  /*useTransientError=*/
            true
        )
    }

    /**
     * Regression test: IllegalStateException in BuildingState.isReady(). The ParallelEvaluator used
     * to assume during cycle-checking that all values had been built as fully as possible -- that
     * evaluation had not been interrupted. However, we also do cycle-checking in nokeep-going mode
     * when a value throws an error (possibly prematurely shutting down evaluation) but that error
     * then bubbles up into a cycle.
     * 
     * 
     * We want to achieve the following state: we are checking for a cycle; the value we examine
     * has not yet finished checking its children to see if they are dirty; but all children checked
     * so far have been unchanged. This value is "otherTop". We first build otherTop, then mark its
     * first child changed (without actually changing it), and then do a second build. On the second
     * build, we also build "top", which requests a cycle that depends on an error. We wait to signal
     * otherTop that its first child is done until the error throws and shuts down evaluation. The
     * error then bubbles up to the cycle, and so the bubbling is aborted. Finally, cycle checking
     * happens, and otherTop is examined, as desired.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cycleAndErrorAndReady() {
        // This value will not have finished building on the second build when the error is thrown.
        val otherTop: SkyKey = GraphTester.Companion.skyKey("otherTop")
        val errorKey: SkyKey = GraphTester.Companion.skyKey("error")
        // Is the graph state all set up and ready for the error to be thrown? The three values are
        // exceptionMarker, cycle2Key, and dep1 (via signaling otherTop).
        val valuesReady: CountDownLatch = CountDownLatch(3)
        // Is evaluation being shut down? This is counted down by the exceptionMarker's builder, after
        // it has waited for the threadpool's exception latch to be released.
        val errorThrown: CountDownLatch = CountDownLatch(1)
        // We don't do anything on the first build.
        val secondBuild: AtomicBoolean = AtomicBoolean(false)
        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (!secondBuild.get()) {
                    return@injectGraphListenerForTesting
                }
                if (key.equals(otherTop) && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.SIGNAL) {
                    // otherTop is being signaled that dep1 is done. Tell the error value that it is ready,
                    // then wait until the error is thrown, so that otherTop's builder is not re-entered.
                    valuesReady.countDown()
                    TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(errorThrown, "error not thrown")
                }
            },  /* deterministic= */
            true
        )
        val dep1: SkyKey = GraphTester.Companion.nonHermeticKey("dep1")
        tester.set(dep1, com.google.devtools.build.skyframe.GraphTester.StringValue("dep1"))
        val dep2: SkyKey = GraphTester.Companion.skyKey("dep2")
        tester.set(dep2, com.google.devtools.build.skyframe.GraphTester.StringValue("dep2"))
        // otherTop should request the deps one at a time, so that it can be in the CHECK_DEPENDENCIES
        // state even after one dep is re-evaluated.
        tester
            .getOrCreate(otherTop)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    env.getValue(dep1)
                    if (env.valuesMissing()) {
                        return@setBuilder null
                    }
                    env.getValue(dep2)
                    if (env.valuesMissing()) null else com.google.devtools.build.skyframe.GraphTester.StringValue("otherTop")
                })
        // Prime the graph with otherTop, so we can dirty it next build.
        assertThat(tester.evalAndGet( /* keepGoing= */false, otherTop))
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("otherTop"))
        // Mark dep1 changed, so otherTop will be dirty and request re-evaluation of dep1.
        tester.getOrCreate(dep1,  /* markAsModified= */true)
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        // Note that since DeterministicHelper alphabetizes reverse deps, it is important that
        // "cycle2" comes before "top".
        val cycle1Key: SkyKey = GraphTester.Companion.skyKey("cycle1")
        val cycle2Key: SkyKey = GraphTester.Companion.skyKey("cycle2")
        tester.getOrCreate(topKey).addDependency(cycle1Key).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester
            .getOrCreate(cycle1Key)
            .addDependency(errorKey)
            .addDependency(cycle2Key)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        tester
            .getOrCreate(errorKey)
            .setBuilder(
                ChainedFunction( /* notifyStart= */
                    null,  /* waitToFinish= */
                    valuesReady,  /* notifyFinish= */
                    null,  /* waitForException= */
                    false,  /* value= */
                    null,
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        // Make sure cycle2Key has declared its dependence on cycle1Key before error throws.
        tester
            .getOrCreate(cycle2Key)
            .setBuilder(
                ChainedFunction( /*notifyStart=*/
                    valuesReady,
                    null,
                    null,
                    false,
                    com.google.devtools.build.skyframe.GraphTester.StringValue("never returned"),
                    com.google.common.collect.ImmutableList.of<SkyKey?>(cycle1Key)
                )
            )
        // Value that waits until an exception is thrown to finish building. We use it just to be
        // informed when the threadpool is shutting down.
        val exceptionMarker: SkyKey = GraphTester.Companion.skyKey("exceptionMarker")
        tester
            .getOrCreate(exceptionMarker)
            .setBuilder(
                ChainedFunction( /*notifyStart=*/
                    valuesReady,  /*waitToFinish=*/
                    CountDownLatch(0),  /*notifyFinish=*/
                    errorThrown,  /*waitForException=*/
                    true,
                    com.google.devtools.build.skyframe.GraphTester.StringValue("exception marker"),
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        tester!!.invalidate()
        secondBuild.set(true)
        // otherTop must be first, since we check top-level values for cycles in the order in which
        // they appear here.
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, otherTop, topKey, exceptionMarker)
        val cycleInfos: Iterable<CycleInfo?> = result.getError(topKey).getCycleInfo()
        assertWithMessage(result.toString()).that(cycleInfos).isNotEmpty()
        val cycleInfo: CycleInfo? = com.google.common.collect.Iterables.getOnlyElement<CycleInfo?>(cycleInfos)
        if (cyclesDetected()) {
            assertThat(result.errorMap().keySet()).containsExactly(topKey)
            assertThat(cycleInfo.pathToCycle).containsExactly(topKey)
            assertThat(cycleInfo.cycle).containsExactly(cycle1Key, cycle2Key)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun breakCycle() {
        val aKey: SkyKey = GraphTester.Companion.nonHermeticKey("a")
        val bKey: SkyKey = GraphTester.Companion.nonHermeticKey("b")
        // When aKey and bKey depend on each other,
        tester.getOrCreate(aKey).addDependency(bKey)
        tester.getOrCreate(bKey).addDependency(aKey)
        // And they are evaluated,
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /* keepGoing= */true, aKey, bKey)
        // Then the evaluation is in error,
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasError()
        // And each node has the expected cycle.
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(aKey)
            .hasCycleInfoThat()
            .isNotEmpty()
        val aCycleInfo: CycleInfo? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(result.getError(aKey).getCycleInfo())
        if (cyclesDetected()) {
            assertThat(aCycleInfo.cycle).containsExactly(aKey, bKey).inOrder()
            assertThat(aCycleInfo.pathToCycle).isEmpty()
        }
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(bKey)
            .hasCycleInfoThat()
            .isNotEmpty()
        val bCycleInfo: CycleInfo? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(result.getError(bKey).getCycleInfo())
        if (cyclesDetected()) {
            assertThat(bCycleInfo.cycle).containsExactly(bKey, aKey).inOrder()
            assertThat(bCycleInfo.pathToCycle).isEmpty()
        }

        // When both dependencies are broken,
        tester.getOrCreate(bKey).removeDependency(aKey)
        tester.set(bKey, com.google.devtools.build.skyframe.GraphTester.StringValue("bValue"))
        tester.getOrCreate(aKey).removeDependency(bKey)
        tester.set(aKey, com.google.devtools.build.skyframe.GraphTester.StringValue("aValue"))
        tester!!.invalidate()
        // And the nodes are re-evaluated,
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/true, aKey, bKey)
        // Then evaluation is successful and the nodes have the expected values.
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(aKey)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("aValue"))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(bKey)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("bValue"))
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun nodeInvalidatedThenDoubleCycle() {
        makeGraphDeterministic()
        // When topKey depends on depKey, and both are top-level nodes in the graph,
        val topKey: SkyKey = GraphTester.Companion.nonHermeticKey("bKey")
        val depKey: SkyKey = GraphTester.Companion.nonHermeticKey("aKey")
        tester.getOrCreate(topKey).addDependency(depKey)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("a"))
        tester.getOrCreate(depKey).setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("b"))
        // Then evaluation is as expected.
        val result1: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /* keepGoing= */true, topKey, depKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result1).hasEntryThat(topKey)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("a"))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result1).hasEntryThat(depKey)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("b"))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result1).hasNoError()
        // When both nodes acquire self-edges, with topKey still also depending on depKey, in the same
        // group,
        tester.getOrCreate(depKey,  /* markAsModified= */true).addDependency(depKey)
        tester
            .getOrCreate(topKey,  /* markAsModified= */true)
            .setConstantValue(null)
            .removeDependency(depKey)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        // Order depKey first - makeGraphDeterministic() only makes the batch maps returned
                        // by the graph deterministic, not the order of temporary direct deps. This makes
                        // the order of deps match (alphabetized by the string representation).
                        env.getValuesAndExceptions(com.google.common.collect.ImmutableList.of<E?>(depKey, topKey))
                        assertThat(env.valuesMissing()).isTrue()
                        return null
                    }
                })
        tester!!.invalidate()
        // Then evaluation is as expected -- topKey has removed its dep on depKey (since depKey was not
        // done when topKey found its cycle), and both topKey and depKey have cycles.
        val result2: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/true, topKey, depKey)
        if (cyclesDetected()) {
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result2)
                .hasErrorEntryForKeyThat(topKey)
                .hasCycleInfoThat()
                .containsExactly(CycleInfo.createCycleInfo(com.google.common.collect.ImmutableList.of<E?>(topKey)))
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result2)
                .hasDirectDepsInGraphThat(topKey).containsExactly(topKey)
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result2)
                .hasErrorEntryForKeyThat(depKey)
                .hasCycleInfoThat()
                .containsExactly(CycleInfo.createCycleInfo(com.google.common.collect.ImmutableList.of<E?>(depKey)))
        } else {
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result2)
                .hasErrorEntryForKeyThat(topKey)
                .hasCycleInfoThat()
                .hasSize(1)
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result2)
                .hasErrorEntryForKeyThat(depKey)
                .hasCycleInfoThat()
                .hasSize(1)
        }
        // When the nodes return to their original, error-free state,
        tester
            .getOrCreate(topKey,  /*markAsModified=*/true)
            .setBuilder(null)
            .addDependency(depKey)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("a"))
        tester.getOrCreate(depKey,  /*markAsModified=*/true).removeDependency(depKey)
        tester!!.invalidate()
        // Then evaluation is as expected.
        val result3: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/true, topKey, depKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result3).hasEntryThat(topKey)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("a"))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result3).hasEntryThat(depKey)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("b"))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result3).hasNoError()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun limitEvaluatorThreads() {
        initializeTester()

        val numKeys = 10
        val lock = Any()
        val inProgressCount: AtomicInteger = AtomicInteger()
        val maxValue = intArrayOf(0)

        val topLevel: SkyKey = GraphTester.Companion.skyKey("toplevel")
        val topLevelBuilder: TestFunction = tester.getOrCreate(topLevel)
        for (i in 0..<numKeys) {
            topLevelBuilder.addDependency("subKey" + i)
            tester
                .getOrCreate("subKey" + i)
                .setComputedValue(
                    ValueComputer { deps: MutableMap<SkyKey?, SkyValue?>?, env: SkyFunction.Environment? ->
                        val `val`: Int = inProgressCount.incrementAndGet()
                        synchronized(lock) {
                            if (`val` > maxValue[0]) {
                                maxValue[0] = `val`
                            }
                        }
                        com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly(5, TimeUnit.SECONDS)

                        inProgressCount.decrementAndGet()
                        com.google.devtools.build.skyframe.GraphTester.StringValue("abc")
                    })
        }
        topLevelBuilder.setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("xyz"))

        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /* keepGoing= */
                true,  /* mergingSkyframeAnalysisExecutionPhases= */
                false,  /* numThreads= */
                5,
                topLevel
            )
        assertThat(result.hasError()).isFalse()
        Truth.assertThat(maxValue[0]).isEqualTo(5)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nodeIsChangedWithoutBeingEvaluated() {
        val buildFile: SkyKey = GraphTester.Companion.nonHermeticKey("buildfile")
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        val dep: SkyKey = GraphTester.Companion.nonHermeticKey("dep")
        tester.set(buildFile, com.google.devtools.build.skyframe.GraphTester.StringValue("depend on dep"))
        val depVal: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("this is dep")
        tester.set(dep, depVal)
        tester
            .getOrCreate(top)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        val `val`: com.google.devtools.build.skyframe.GraphTester.StringValue =
                            env.getValue(buildFile) as com.google.devtools.build.skyframe.GraphTester.StringValue
                        if (env.valuesMissing()) {
                            return null
                        }
                        if (`val`.getValue() == "depend on dep") {
                            val result: com.google.devtools.build.skyframe.GraphTester.StringValue? =
                                env.getValue(dep) as com.google.devtools.build.skyframe.GraphTester.StringValue?
                            return if (env.valuesMissing()) null else result
                        }
                        throw GenericFunctionException(
                            SomeErrorException("bork"), Transience.PERSISTENT
                        )
                    }
                })
        assertThat(tester!!.evalAndGet("top")).isEqualTo(depVal)
        val newDepVal: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("this is new dep")
        tester.set(dep, newDepVal)
        tester.set(buildFile, com.google.devtools.build.skyframe.GraphTester.StringValue("don't depend on dep"))
        tester!!.invalidate()
        tester!!.eval<SkyValue?>( /*keepGoing=*/false, top)
        tester.set(buildFile, com.google.devtools.build.skyframe.GraphTester.StringValue("depend on dep"))
        tester!!.invalidate()
        assertThat(tester!!.evalAndGet("top")).isEqualTo(newDepVal)
    }

    /**
     * Regression test: error on clearMaybeDirtyValue. We do an evaluation of topKey, which registers
     * dependencies on midKey and errorKey. midKey enqueues slowKey, and waits. errorKey throws an
     * error, which bubbles up to topKey. If topKey does not unregister its dependence on midKey, it
     * will have a dangling reference to midKey after unfinished values are cleaned from the graph.
     * Note that slowKey will wait until errorKey has thrown and the threadpool has caught the
     * exception before returning, so the Evaluator will already have stopped enqueuing new jobs, so
     * midKey is not evaluated.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incompleteDirectDepsAreClearedBeforeInvalidation() {
        val slowStart: CountDownLatch = CountDownLatch(1)
        val errorFinish: CountDownLatch = CountDownLatch(1)
        val errorKey: SkyKey = GraphTester.Companion.nonHermeticKey("error")
        tester
            .getOrCreate(errorKey)
            .setBuilder(
                ChainedFunction( /*notifyStart=*/
                    null,  /*waitToFinish=*/
                    slowStart,  /*notifyFinish=*/
                    errorFinish,  /*waitForException=*/
                    false,  /*value=*/
                    null,  /*deps=*/
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        val slowKey: SkyKey = GraphTester.Companion.skyKey("slow")
        tester
            .getOrCreate(slowKey)
            .setBuilder(
                ChainedFunction( /*notifyStart=*/
                    slowStart,  /*waitToFinish=*/
                    errorFinish,  /*notifyFinish=*/
                    null,  /*waitForException=*/
                    true,
                    com.google.devtools.build.skyframe.GraphTester.StringValue("slow"),  /*deps=*/
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        val midKey: SkyKey = GraphTester.Companion.skyKey("mid")
        tester.getOrCreate(midKey).addDependency(slowKey).setComputedValue(GraphTester.Companion.COPY)
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        tester
            .getOrCreate(topKey)
            .addDependency(midKey)
            .addDependency(errorKey)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        // slowKey starts -> errorKey finishes, written to graph -> slowKey finishes & (Visitor aborts)
        // -> topKey builds.
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(topKey)
        // Make sure midKey didn't finish building.
        assertThat(tester.getExistingValue(midKey)).isNull()
        // Give slowKey a nice ordinary builder.
        tester
            .getOrCreate(slowKey,  /*markAsModified=*/false)
            .setBuilder(null)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("slow"))
        // Put midKey into the graph. It won't have a reverse dependence on topKey.
        tester.evalAndGet( /*keepGoing=*/false, midKey)
        tester.differencer.invalidate(com.google.common.collect.ImmutableList.of<E?>(errorKey))
        // topKey should not access midKey as if it were already registered as a dependency.
        tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey)
    }

    /**
     * Regression test: error on clearMaybeDirtyValue. Same as the previous test, but the second
     * evaluation is keepGoing, which should cause an access of the children of topKey.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incompleteDirectDepsAreClearedBeforeKeepGoing() {
        initializeTester()
        val slowStart: CountDownLatch = CountDownLatch(1)
        val errorFinish: CountDownLatch = CountDownLatch(1)
        val errorKey: SkyKey = GraphTester.Companion.skyKey("error")
        tester
            .getOrCreate(errorKey)
            .setBuilder(
                ChainedFunction( /*notifyStart=*/
                    null,  /*waitToFinish=*/
                    slowStart,  /*notifyFinish=*/
                    errorFinish,  /*waitForException=*/
                    false,  /*value=*/
                    null,  /*deps=*/
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        val slowKey: SkyKey = GraphTester.Companion.skyKey("slow")
        tester
            .getOrCreate(slowKey)
            .setBuilder(
                ChainedFunction( /*notifyStart=*/
                    slowStart,  /*waitToFinish=*/
                    errorFinish,  /*notifyFinish=*/
                    null,  /*waitForException=*/
                    true,
                    com.google.devtools.build.skyframe.GraphTester.StringValue("slow"),  /*deps=*/
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        val midKey: SkyKey = GraphTester.Companion.skyKey("mid")
        tester.getOrCreate(midKey).addDependency(slowKey).setComputedValue(GraphTester.Companion.COPY)
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        tester
            .getOrCreate(topKey)
            .addDependency(midKey)
            .addDependency(errorKey)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        // slowKey starts -> errorKey finishes, written to graph -> slowKey finishes & (Visitor aborts)
        // -> topKey builds.
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(topKey)
        // Make sure midKey didn't finish building.
        assertThat(tester.getExistingValue(midKey)).isNull()
        // Give slowKey a nice ordinary builder.
        tester
            .getOrCreate(slowKey,  /*markAsModified=*/false)
            .setBuilder(null)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("slow"))
        // Put midKey into the graph. It won't have a reverse dependence on topKey.
        tester.evalAndGet( /*keepGoing=*/false, midKey)
        // topKey should not access midKey as if it were already registered as a dependency.
        // We don't invalidate errors, but because topKey wasn't actually written to the graph last
        // build, it should be rebuilt here.
        tester!!.eval<SkyValue?>( /*keepGoing=*/true, topKey)
    }

    /**
     * Regression test: tests that pass before other build actions fail yield crash in non -k builds.
     */
    @Throws(java.lang.Exception::class)
    private fun passThenFailToBuild(successFirst: Boolean) {
        val blocker: CountDownLatch = CountDownLatch(1)
        val successKey: SkyKey = GraphTester.Companion.skyKey("success")
        tester
            .getOrCreate(successKey)
            .setBuilder(
                ChainedFunction( /*notifyStart=*/
                    null,  /*waitToFinish=*/
                    null,  /*notifyFinish=*/
                    blocker,  /*waitForException=*/
                    false,
                    com.google.devtools.build.skyframe.GraphTester.StringValue("yippee"),  /*deps=*/
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        val slowFailKey: SkyKey = GraphTester.Companion.skyKey("slow_then_fail")
        tester
            .getOrCreate(slowFailKey)
            .setBuilder(
                ChainedFunction( /*notifyStart=*/
                    null,  /*waitToFinish=*/
                    blocker,  /*notifyFinish=*/
                    null,  /*waitForException=*/
                    false,  /*value=*/
                    null,  /*deps=*/
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )

        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?>
        if (successFirst) {
            result = tester!!.eval<SkyValue?>( /*keepGoing=*/false, successKey, slowFailKey)
        } else {
            result = tester!!.eval<SkyValue?>( /*keepGoing=*/false, slowFailKey, successKey)
        }
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(slowFailKey)
        assertThat(result.values()).containsExactly(com.google.devtools.build.skyframe.GraphTester.StringValue("yippee"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun passThenFailToBuild() {
        passThenFailToBuild(true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun passThenFailToBuildAlternateOrder() {
        passThenFailToBuild(false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incompleteDirectDepsForDirtyValue() {
        val topKey: SkyKey = GraphTester.Companion.nonHermeticKey("top")
        tester.set(topKey, com.google.devtools.build.skyframe.GraphTester.StringValue("initial"))
        // Put topKey into graph so it will be dirtied on next run.
        assertThat(tester.evalAndGet( /*keepGoing=*/false, topKey))
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("initial"))
        val slowStart: CountDownLatch = CountDownLatch(1)
        val errorFinish: CountDownLatch = CountDownLatch(1)
        val errorKey: SkyKey = GraphTester.Companion.skyKey("error")
        tester
            .getOrCreate(errorKey)
            .setBuilder(
                ChainedFunction( /*notifyStart=*/
                    null,  /*waitToFinish=*/
                    slowStart,  /*notifyFinish=*/
                    errorFinish,  /*waitForException=*/
                    false,  /*value=*/
                    null,  /*deps=*/
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        val slowKey: SkyKey = GraphTester.Companion.skyKey("slow")
        tester
            .getOrCreate(slowKey)
            .setBuilder(
                ChainedFunction( /*notifyStart=*/
                    slowStart,  /*waitToFinish=*/
                    errorFinish,  /*notifyFinish=*/
                    null,  /*waitForException=*/
                    true,
                    com.google.devtools.build.skyframe.GraphTester.StringValue("slow"),  /*deps=*/
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        val midKey: SkyKey = GraphTester.Companion.skyKey("mid")
        tester.getOrCreate(midKey).addDependency(slowKey).setComputedValue(GraphTester.Companion.COPY)
        tester.set(topKey, null)
        tester
            .getOrCreate(topKey)
            .addDependency(midKey)
            .addDependency(errorKey)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        tester!!.invalidate()
        // slowKey starts -> errorKey finishes, written to graph -> slowKey finishes & (Visitor aborts)
        // -> topKey builds.
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(topKey)
        // Make sure midKey didn't finish building.
        assertThat(tester.getExistingValue(midKey)).isNull()
        // Give slowKey a nice ordinary builder.
        tester
            .getOrCreate(slowKey,  /*markAsModified=*/false)
            .setBuilder(null)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("slow"))
        // Put midKey into the graph. It won't have a reverse dependence on topKey.
        tester.evalAndGet( /*keepGoing=*/false, midKey)
        // topKey should not access midKey as if it were already registered as a dependency.
        // We don't invalidate errors, but since topKey wasn't actually written to the graph before, it
        // will be rebuilt.
        tester!!.eval<SkyValue?>( /*keepGoing=*/true, topKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun continueWithErrorDep() {
        val afterKey: SkyKey = GraphTester.Companion.nonHermeticKey("after")
        val errorKey: SkyKey = GraphTester.Companion.skyKey("my_error_value")
        tester.getOrCreate(errorKey).setHasError(true)
        tester.set(afterKey, com.google.devtools.build.skyframe.GraphTester.StringValue("after"))
        val parentKey: SkyKey = GraphTester.Companion.skyKey("parent")
        tester
            .getOrCreate(parentKey)
            .addErrorDependency(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("recovered"))
            .setComputedValue(GraphTester.Companion.CONCATENATE)
            .addDependency(afterKey)
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /* keepGoing= */true, parentKey)
        assertThat(result.errorMap()).isEmpty()
        assertThat(result.get(parentKey).getValue()).isEqualTo("recoveredafter")
        tester.set(afterKey, com.google.devtools.build.skyframe.GraphTester.StringValue("before"))
        tester!!.invalidate()
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/true, parentKey)
        assertThat(result.errorMap()).isEmpty()
        assertThat(result.get(parentKey).getValue()).isEqualTo("recoveredbefore")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun continueWithErrorDepTurnedGood() {
        val errorKey: SkyKey = GraphTester.Companion.nonHermeticKey("my_error_value")
        tester.getOrCreate(errorKey).setHasError(true)
        tester.set("after", com.google.devtools.build.skyframe.GraphTester.StringValue("after"))
        val parentKey: SkyKey = GraphTester.Companion.skyKey("parent")
        tester
            .getOrCreate(parentKey)
            .addErrorDependency(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("recovered"))
            .setComputedValue(GraphTester.Companion.CONCATENATE)
            .addDependency("after")
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/true, parentKey)
        assertThat(result.errorMap()).isEmpty()
        assertThat(result.get(parentKey).getValue()).isEqualTo("recoveredafter")
        tester.set(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("reformed")).setHasError(false)
        tester!!.invalidate()
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/true, parentKey)
        assertThat(result.errorMap()).isEmpty()
        assertThat(result.get(parentKey).getValue()).isEqualTo("reformedafter")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorDepAlreadyThereThenTurnedGood() {
        val errorKey: SkyKey = GraphTester.Companion.nonHermeticKey("my_error_value")
        tester.getOrCreate(errorKey).setHasError(true)
        val parentKey: SkyKey = GraphTester.Companion.skyKey("parent")
        tester
            .getOrCreate(parentKey)
            .addErrorDependency(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("recovered"))
            .setHasError(true)
        // Prime the graph by putting the error value in it beforehand.
        assertThat(tester.evalAndGetError( /*keepGoing=*/true, errorKey)).isNotNull()
        // Request the parent.
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, parentKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(parentKey)
        // Change the error value to no longer throw.
        tester.set(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("reformed")).setHasError(false)
        tester
            .getOrCreate(parentKey,  /*markAsModified=*/false)
            .setHasError(false)
            .setComputedValue(GraphTester.Companion.COPY)
        tester.differencer.invalidate(com.google.common.collect.ImmutableList.of<E?>(errorKey))
        tester!!.invalidate()
        // Request the parent again. This time it should succeed.
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/false, parentKey)
        assertThat(result.errorMap()).isEmpty()
        assertThat(result.get(parentKey).getValue()).isEqualTo("reformed")
        // Confirm that the parent no longer depends on the error transience value -- make it
        // unbuildable again, but without invalidating it, and invalidate transient errors. The parent
        // should not be rebuilt.
        tester.getOrCreate(parentKey,  /*markAsModified=*/false).setHasError(true)
        tester!!.invalidateTransientErrors()
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/false, parentKey)
        assertThat(result.errorMap()).isEmpty()
        assertThat(result.get(parentKey).getValue()).isEqualTo("reformed")
    }

    /**
     * Regression test for 2014 bug: error transience value is registered before newly requested deps.
     * A value requests a child, gets it back immediately, and then throws, causing the error
     * transience value to be registered as a dep. The following build, the error is invalidated via
     * that child.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun doubleDepOnErrorTransienceValue() {
        val leafKey: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        tester.set(leafKey, com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))
        // Prime the graph by putting leaf in beforehand.
        assertThat(
            tester.evalAndGet( /*keepGoing=*/false,
                leafKey
            )
        ).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        tester.getOrCreate(topKey).addDependency(leafKey).setHasError(true)
        // Build top -- it has an error.
        tester.evalAndGetError( /*keepGoing=*/true, topKey)
        // Invalidate top via leaf, and rebuild.
        tester.set(leafKey, com.google.devtools.build.skyframe.GraphTester.StringValue("leaf2"))
        tester!!.invalidate()
        tester.evalAndGetError( /*keepGoing=*/true, topKey)
    }

    /** Regression test for crash bug.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorTransienceDepCleared() {
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))
        tester.getOrCreate(top).addDependency(leaf).setHasTransientError(true)
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /* keepGoing= */false, top)
        assertWithMessage(result.toString()).that(result.hasError()).isTrue()
        tester.getOrCreate(leaf,  /* markAsModified= */true)
        tester!!.invalidate()
        val irrelevant: SkyKey = GraphTester.Companion.skyKey("irrelevant")
        tester.set(irrelevant, com.google.devtools.build.skyframe.GraphTester.StringValue("irrelevant"))
        tester!!.eval<SkyValue?>( /* keepGoing= */true, irrelevant)
        tester!!.invalidateTransientErrors()
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/true, top)
        assertWithMessage(result.toString()).that(result.hasError()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incompleteValueAlreadyThereNotUsed() {
        initializeTester()
        val errorKey: SkyKey = GraphTester.Companion.skyKey("my_error_value")
        tester.getOrCreate(errorKey).setHasError(true)
        val midKey: SkyKey = GraphTester.Companion.skyKey("mid")
        tester
            .getOrCreate(midKey)
            .addErrorDependency(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("recovered"))
            .setComputedValue(GraphTester.Companion.COPY)
        val parentKey: SkyKey = GraphTester.Companion.skyKey("parent")
        tester
            .getOrCreate(parentKey)
            .addErrorDependency(midKey, com.google.devtools.build.skyframe.GraphTester.StringValue("don't use this"))
            .setComputedValue(GraphTester.Companion.COPY)
        // Prime the graph by evaluating the mid-level value. It shouldn't be stored in the graph
        // because it was only called during the bubbling-up phase.
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, midKey)
        assertThat(result.get(midKey)).isNull()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(midKey)
        // In a keepGoing build, midKey should be re-evaluated.
        Truth.assertThat(
            (tester.evalAndGet( /*keepGoing=*/true,
                parentKey
            ) as com.google.devtools.build.skyframe.GraphTester.StringValue).getValue()
        )
            .isEqualTo("recovered")
    }

    /**
     * "top" requests a dependency group in which the first value, called "error", throws an
     * exception, so "mid" and "mid2", which depend on "slow", never get built.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorInDependencyGroup() {
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        val slowStart: CountDownLatch = CountDownLatch(1)
        val errorFinish: CountDownLatch = CountDownLatch(1)
        val errorKey: SkyKey = GraphTester.Companion.nonHermeticKey("error")
        tester
            .getOrCreate(errorKey)
            .setBuilder(
                ChainedFunction( /*notifyStart=*/
                    null,  /*waitToFinish=*/
                    slowStart,  /*notifyFinish=*/
                    errorFinish,  /*waitForException=*/
                    false,  // ChainedFunction throws when value is null.
                    /*value=*/
                    null,  /*deps=*/
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        val slowKey: SkyKey = GraphTester.Companion.skyKey("slow")
        tester
            .getOrCreate(slowKey)
            .setBuilder(
                ChainedFunction( /*notifyStart=*/
                    slowStart,  /*waitToFinish=*/
                    errorFinish,  /*notifyFinish=*/
                    null,  /*waitForException=*/
                    true,
                    com.google.devtools.build.skyframe.GraphTester.StringValue("slow"),  /*deps=*/
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        val midKey: SkyKey = GraphTester.Companion.skyKey("mid")
        tester.getOrCreate(midKey).addDependency(slowKey).setComputedValue(GraphTester.Companion.COPY)
        val mid2Key: SkyKey = GraphTester.Companion.skyKey("mid2")
        tester.getOrCreate(mid2Key).addDependency(slowKey).setComputedValue(GraphTester.Companion.COPY)
        tester.set(topKey, null)
        tester
            .getOrCreate(topKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    env.getValuesAndExceptions(
                        com.google.common.collect.ImmutableList.of<E?>(
                            errorKey,
                            midKey,
                            mid2Key
                        )
                    )
                    if (env.valuesMissing()) {
                        return@setBuilder null
                    }
                    com.google.devtools.build.skyframe.GraphTester.StringValue("top")
                })

        // Assert that build fails and "error" really is in error.
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey)
        assertThat(result.hasError()).isTrue()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(topKey)

        // Ensure that evaluation succeeds if errorKey does not throw an error.
        tester.getOrCreate(errorKey).setBuilder(null)
        tester.set(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("ok"))
        tester!!.invalidate()
        assertThat(tester!!.evalAndGet("top")).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("top"))
    }

    /**
     * Regression test -- if value top requests {depA, depB}, depC, with depA and depC there and depB
     * absent, and then throws an exception, the stored deps should be depA, depC (in different
     * groups), not {depA, depC} (same group).
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueInErrorWithGroups() {
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        val groupDepA: SkyKey = GraphTester.Companion.nonHermeticKey("groupDepA")
        val groupDepB: SkyKey = GraphTester.Companion.skyKey("groupDepB")
        val depC: SkyKey = GraphTester.Companion.nonHermeticKey("depC")
        tester.set(groupDepA, SkyKeyValue(depC))
        tester.set(groupDepB, com.google.devtools.build.skyframe.GraphTester.StringValue(""))
        tester.getOrCreate(depC).setHasError(true)
        tester
            .getOrCreate(topKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    val `val` =
                        env.getValuesAndExceptions(com.google.common.collect.ImmutableList.of<E?>(groupDepA, groupDepB))
                            .get(groupDepA) as SkyKeyValue
                    if (env.valuesMissing()) {
                        return@setBuilder null
                    }
                    try {
                        env.getValueOrThrow(`val`.key, SomeErrorException::class.java)
                    } catch (e: SomeErrorException) {
                        throw GenericFunctionException(e, Transience.PERSISTENT)
                    }
                    if (env.valuesMissing()) null else com.google.devtools.build.skyframe.GraphTester.StringValue("top")
                })

        var evaluationResult: EvaluationResult<SkyValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/true, groupDepA, depC)
        assertThat((evaluationResult.get(groupDepA) as SkyKeyValue).key).isEqualTo(depC)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(evaluationResult)
            .hasErrorEntryForKeyThat(depC)
        evaluationResult = tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(evaluationResult)
            .hasErrorEntryForKeyThat(topKey)

        tester.set(groupDepA, SkyKeyValue(groupDepB))
        tester.getOrCreate(depC,  /*markAsModified=*/true)
        tester!!.invalidate()
        evaluationResult = tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey)
        assertWithMessage(evaluationResult.toString()).that(evaluationResult.hasError()).isFalse()
        assertThat(evaluationResult.get(topKey)).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("top"))
    }

    private class SkyKeyValue(key: SkyKey?) : SkyValue {
        private val key: SkyKey?

        init {
            this.key = key
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorOnlyEmittedOnce() {
        initializeTester()
        tester.set("x", com.google.devtools.build.skyframe.GraphTester.StringValue("y")).setWarning("fizzlepop")
        var value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("x") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("y")
        assertThatEvents(eventCollector).containsExactly("fizzlepop")

        tester!!.invalidate()
        value = tester!!.evalAndGet("x") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("y")
        // No new events emitted.
    }

    /**
     * We are checking here that we are resilient to a race condition in which a value that is
     * checking its children for dirtiness is signaled by all of its children, putting it in a ready
     * state, before the thread has terminated. Optionally, one of its children may throw an error,
     * shutting down the threadpool. The essential race is that a child about to throw signals its
     * parent and the parent's builder restarts itself before the exception is thrown. Here, the
     * signaling happens while dirty dependencies are being checked. We control the timing by blocking
     * "top"'s registering itself on its deps.
     */
    @Throws(java.lang.Exception::class)
    private fun dirtyChildEnqueuesParentDuringCheckDependencies(throwError: Boolean) {
        // Value to be built. It will be signaled to rebuild before it has finished checking its deps.
        val top: SkyKey = GraphTester.Companion.skyKey("a_top")
        // otherTop is alphabetically after top.
        val otherTop: SkyKey = GraphTester.Companion.skyKey("z_otherTop")
        // Dep that blocks before it acknowledges being added as a dep by top, so the firstKey value has
        // time to signal top. (Importantly its key is alphabetically after 'firstKey').
        val slowAddingDep: SkyKey = GraphTester.Companion.skyKey("slowDep")
        // Value that is modified on the second build. Its thread won't finish until it signals top,
        // which will wait for the signal before it enqueues its next dep. We prevent the thread from
        // finishing by having the graph listener block on the second reverse dep to signal.
        val firstKey: SkyKey = GraphTester.Companion.nonHermeticKey("first")
        tester.set(firstKey, com.google.devtools.build.skyframe.GraphTester.StringValue("biding"))
        // Don't perform any blocking on the first build.
        val delayTopSignaling: AtomicBoolean = AtomicBoolean(false)
        val topSignaled: CountDownLatch = CountDownLatch(1)
        val topRequestedDepOrRestartedBuild: CountDownLatch = CountDownLatch(1)
        val parentsRequested: CountDownLatch = CountDownLatch(2)
        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (!delayTopSignaling.get()) {
                    return@injectGraphListenerForTesting
                }
                if (key.equals(otherTop) && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.SIGNAL) {
                    TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                        topRequestedDepOrRestartedBuild, "top's builder did not start in time"
                    )
                    return@injectGraphListenerForTesting
                }
                if (key.equals(firstKey) && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.ADD_REVERSE_DEP && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER) {
                    parentsRequested.countDown()
                    return@injectGraphListenerForTesting
                }
                if (key.equals(firstKey) && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.CHECK_IF_DONE && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER) {
                    parentsRequested.countDown()
                    if (throwError) {
                        topRequestedDepOrRestartedBuild.countDown()
                    }
                    return@injectGraphListenerForTesting
                }
                if (key.equals(top) && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.SIGNAL && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER) {
                    // top is signaled by firstKey (since slowAddingDep is blocking), so slowAddingDep
                    // is now free to acknowledge top as a parent.
                    topSignaled.countDown()
                    return@injectGraphListenerForTesting
                }
                if (key.equals(firstKey) && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE) {
                    // Make sure both parents add themselves as rdeps.
                    TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                        parentsRequested, "parents did not request dep in time"
                    )
                }
                if (key.equals(slowAddingDep)
                    && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.CHECK_IF_DONE && top.equals(
                        context
                    )
                    && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE
                ) {
                    // If top is trying to declare a dep on slowAddingDep, wait until firstKey has
                    // signaled top. Then this add dep will return DONE and top will be signaled,
                    // making it ready, so it will be enqueued.
                    TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                        topSignaled, "first key didn't signal top in time"
                    )
                }
            },  /* deterministic= */
            true
        )
        tester.set(slowAddingDep, com.google.devtools.build.skyframe.GraphTester.StringValue("dep"))
        val numTopInvocations: AtomicInteger = AtomicInteger(0)
        tester
            .getOrCreate(top)
            .setBuilder(
                SkyFunction { key, env ->
                    numTopInvocations.incrementAndGet()
                    if (delayTopSignaling.get()) {
                        // The graph listener will block on firstKey's signaling of otherTop above until
                        // this thread starts running.
                        topRequestedDepOrRestartedBuild.countDown()
                    }
                    // top's builder just requests both deps in a group.
                    env.getValuesAndExceptions(com.google.common.collect.ImmutableList.of<E?>(firstKey, slowAddingDep))
                    if (env.valuesMissing()) null else com.google.devtools.build.skyframe.GraphTester.StringValue("top")
                })
        // First build : just prime the graph.
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, top)
        assertThat(result.hasError()).isFalse()
        assertThat(result.get(top)).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("top"))
        Truth.assertThat(numTopInvocations.get()).isEqualTo(2)
        // Now dirty the graph, and maybe have firstKey throw an error.
        if (throwError) {
            tester
                .getOrCreate(firstKey,  /*markAsModified=*/true)
                .setConstantValue(null)
                .setBuilder(
                    object : SkyFunction() {
                        @Throws(SkyFunctionException::class)
                        public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue? {
                            TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                                parentsRequested, "both parents didn't request in time"
                            )
                            throw GenericFunctionException(
                                SomeErrorException(firstKey.toString()), Transience.PERSISTENT
                            )
                        }
                    })
        } else {
            tester
                .getOrCreate(firstKey,  /*markAsModified=*/true)
                .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("new"))
        }
        tester.getOrCreate(otherTop).addDependency(firstKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester!!.invalidate()
        delayTopSignaling.set(true)
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/false, top, otherTop)
        if (throwError) {
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasError()
            assertThat(result.keyNames()).isEmpty() // No successfully evaluated values.
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(top)
            Truth.assertWithMessage(
                "on the incremental build, top's builder should have only been used in error "
                        + "bubbling"
            )
                .that(numTopInvocations.get())
                .isEqualTo(3)
        } else {
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(top)
                .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("top"))
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasNoError()
            Truth.assertWithMessage(
                "on the incremental build, top's builder should have only been executed once in "
                        + "normal evaluation"
            )
                .that(numTopInvocations.get())
                .isEqualTo(3)
        }
        Truth.assertThat(topSignaled.getCount()).isEqualTo(0)
        Truth.assertThat(topRequestedDepOrRestartedBuild.getCount()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dirtyChildEnqueuesParentDuringCheckDependencies_ThrowDoesntEnqueue() {
        dirtyChildEnqueuesParentDuringCheckDependencies( /*throwError=*/true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dirtyChildEnqueuesParentDuringCheckDependencies_NoThrow() {
        dirtyChildEnqueuesParentDuringCheckDependencies( /*throwError=*/false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun removeReverseDepFromRebuildingNode() {
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        val midKey: SkyKey = GraphTester.Companion.nonHermeticKey("mid")
        val changedKey: SkyKey = GraphTester.Companion.nonHermeticKey("changed")
        tester.getOrCreate(changedKey)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("first"))
        // When top depends on mid,
        tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        // And mid depends on changed,
        tester.getOrCreate(midKey).addDependency(changedKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        val changedKeyStarted: CountDownLatch = CountDownLatch(1)
        val changedKeyCanFinish: CountDownLatch = CountDownLatch(1)
        val controlTiming: AtomicBoolean = AtomicBoolean(false)
        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (!controlTiming.get()) {
                    return@injectGraphListenerForTesting
                }
                if (key.equals(midKey) && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.CHECK_IF_DONE && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE) {
                    TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                        changedKeyStarted, "changed key didn't start"
                    )
                } else if (key.equals(changedKey)
                    && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.REMOVE_REVERSE_DEP && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER && midKey.equals(
                        context
                    )
                ) {
                    changedKeyCanFinish.countDown()
                }
            },  /* deterministic= */
            false
        )
        // Then top builds as expected.
        assertThat(
            tester.evalAndGet( /*keepGoing=*/false,
                topKey
            )
        ).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("first"))
        // When changed is modified,
        tester
            .getOrCreate(changedKey,  /*markAsModified=*/true)
            .setConstantValue(null)
            .setBuilder( // And changed is not allowed to finish building until it is released,
                ChainedFunction(
                    changedKeyStarted,
                    changedKeyCanFinish,
                    null,
                    false,
                    com.google.devtools.build.skyframe.GraphTester.StringValue("second"),
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        // And mid is independently marked as modified,
        tester
            .getOrCreate(midKey,  /*markAsModified=*/true)
            .removeDependency(changedKey)
            .setComputedValue(null)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("mid"))
        tester!!.invalidate()
        val newTopKey: SkyKey = GraphTester.Companion.skyKey("newTop")
        // And changed will start rebuilding independently of midKey, because it's requested directly by
        // newTop
        tester.getOrCreate(newTopKey).addDependency(changedKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        // And we control the timing using the graph listener above to make sure that:
        // (1) before we do anything with mid, changed has already started, and
        // (2) changed key can't finish until mid tries to remove its reverse dep from changed,
        controlTiming.set(true)
        // Then this evaluation completes without crashing.
        tester!!.eval<SkyValue?>( /*keepGoing=*/false, newTopKey, topKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dirtyThenDeleted() {
        val topKey: SkyKey = GraphTester.Companion.nonHermeticKey("top")
        val leafKey: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        tester.getOrCreate(topKey).addDependency(leafKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.set(leafKey, com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
        assertThat(tester.evalAndGet( /* keepGoing= */false, topKey))
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
        tester.getOrCreate(topKey,  /* markAsModified= */true)
        tester!!.invalidate()
        assertThat(tester.evalAndGet( /* keepGoing= */false, leafKey))
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
        tester!!.delete("top")
        tester.getOrCreate(leafKey,  /* markAsModified= */true)
        tester!!.invalidate()
        assertThat(tester.evalAndGet( /* keepGoing= */false, leafKey))
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
    }

    /**
     * Basic test for a [SkyFunction.Reset] with no rewinding of dependencies.
     * 
     * 
     * Ensures that [NodeEntry.getResetDirectDeps] is used correctly by Skyframe to avoid
     * registering duplicate rdep edges when `dep` is requested both before and after a reset.
     * 
     * 
     * This test covers the case where `dep` is newly requested post-reset during a [ ][SkyFunction.compute] invocation that returns a [SkyValue], which exercises a different
     * [AbstractParallelEvaluator] code path than the scenario covered by [ ][.resetSelfOnly_extraDepMissingAfterReset_initialBuild].
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resetSelfOnly_singleDep_initialBuild() {
        TruthJUnit.assume().that(resetSupported()).isTrue()

        val inconsistencyReceiver = recordInconsistencies()
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        val dep: SkyKey = GraphTester.Companion.skyKey("dep")

        tester
            .getOrCreate(top)
            .setBuilder(
                object : SkyFunction() {
                    private var alreadyReset = false

                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        val depValue: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                            env.getValue(dep)
                        if (depValue == null) {
                            return null
                        }
                        if (!alreadyReset) {
                            alreadyReset = true
                            return Reset.selfOnly(top)
                        }
                        return com.google.devtools.build.skyframe.GraphTester.StringValue("topVal")
                    }
                })
        tester.getOrCreate(dep).setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("depVal"))

        val result: EvaluationResult<T?> = tester!!.eval<SkyValue?>( /* keepGoing= */false, top)

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(top)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("topVal"))
        Truth.assertThat(inconsistencyReceiver).containsExactly(InconsistencyData.Companion.resetRequested(top))

        if (!incrementalitySupported()) {
            return  // Skip assertions on dependency edges when they aren't kept.
        }

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep)
            .containsExactly(top)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top)
            .containsExactly(dep)
    }

    /**
     * Similar to [.resetSelfOnly_singleDep_initialBuild] except that the reset occurs on the
     * node's incremental build.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resetSelfOnly_singleDep_incrementalBuild() {
        TruthJUnit.assume().that(resetSupported()).isTrue()
        TruthJUnit.assume().that(incrementalitySupported()).isTrue()

        val inconsistencyReceiver = recordInconsistencies()
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        val dep: SkyKey = GraphTester.Companion.nonHermeticKey("dep")

        tester
            .getOrCreate(top)
            .setBuilder(
                object : SkyFunction() {
                    private var alreadyReset = false

                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        val depValue: com.google.devtools.build.skyframe.GraphTester.StringValue? =
                            env.getValue(dep) as com.google.devtools.build.skyframe.GraphTester.StringValue?
                        if (depValue == null) {
                            return null
                        }
                        if (depValue.getValue() == "depVal2" && !alreadyReset) {
                            alreadyReset = true
                            return Reset.selfOnly(top)
                        }
                        return com.google.devtools.build.skyframe.GraphTester.StringValue("topVal")
                    }
                })

        tester.getOrCreate(dep).setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("depVal1"))
        tester!!.eval<SkyValue?>( /* keepGoing= */false, top)
        Truth.assertThat(inconsistencyReceiver).isEmpty()

        tester.set(dep, com.google.devtools.build.skyframe.GraphTester.StringValue("depVal2"))
        tester!!.invalidate()
        val result: EvaluationResult<T?> = tester!!.eval<SkyValue?>( /* keepGoing= */false, top)

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(top)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("topVal"))
        Truth.assertThat(inconsistencyReceiver).containsExactly(InconsistencyData.Companion.resetRequested(top))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep)
            .containsExactly(top)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top)
            .containsExactly(dep)
    }

    /**
     * Test for a [SkyFunction.Reset] with no rewinding of dependencies, with a missing
     * dependency requested post-reset.
     * 
     * 
     * Ensures that [NodeEntry.getResetDirectDeps] is used correctly by Skyframe to avoid
     * registering duplicate rdep edges when `dep` is requested both before and after a reset.
     * 
     * 
     * This test covers the case where `dep` is newly requested post-reset in a [ ][SkyFunction.compute] invocation that returns `null` (because `extraDep` is
     * missing), which exercises a different [AbstractParallelEvaluator] code path than the
     * scenario covered by [.resetSelfOnly_singleDep_initialBuild].
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resetSelfOnly_extraDepMissingAfterReset_initialBuild() {
        TruthJUnit.assume().that(resetSupported()).isTrue()

        val inconsistencyReceiver = recordInconsistencies()
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        val dep: SkyKey = GraphTester.Companion.skyKey("dep")
        val extraDep: SkyKey = GraphTester.Companion.skyKey("extraDep")

        tester
            .getOrCreate(top)
            .setBuilder(
                object : SkyFunction() {
                    private var alreadyReset = false

                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        val depValue: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                            env.getValue(dep)
                        if (depValue == null) {
                            return null
                        }
                        if (!alreadyReset) {
                            alreadyReset = true
                            return Reset.selfOnly(top)
                        }
                        val extraDepValue: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                            env.getValue(extraDep)
                        if (extraDepValue == null) {
                            return null
                        }
                        return com.google.devtools.build.skyframe.GraphTester.StringValue("topVal")
                    }
                })
        tester.getOrCreate(dep).setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("depVal"))
        tester.getOrCreate(extraDep)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("extraDepVal"))

        val result: EvaluationResult<T?> = tester!!.eval<SkyValue?>( /* keepGoing= */false, top)

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(top)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("topVal"))
        Truth.assertThat(inconsistencyReceiver).containsExactly(InconsistencyData.Companion.resetRequested(top))

        if (!incrementalitySupported()) {
            return  // Skip assertions on dependency edges when they aren't kept.
        }

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep)
            .containsExactly(top)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasDirectDepsInGraphThat(top)
            .containsExactly(dep, extraDep)
            .inOrder()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(extraDep)
            .containsExactly(top)
    }

    /**
     * Similar to [.resetSelfOnly_extraDepMissingAfterReset_initialBuild] except that the reset
     * occurs on the node's incremental build.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resetSelfOnly_extraDepMissingAfterReset_incrementalBuild() {
        TruthJUnit.assume().that(resetSupported()).isTrue()
        TruthJUnit.assume().that(incrementalitySupported()).isTrue()

        val inconsistencyReceiver = recordInconsistencies()
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        val dep: SkyKey = GraphTester.Companion.nonHermeticKey("dep")
        val extraDep: SkyKey = GraphTester.Companion.skyKey("extraDep")

        tester
            .getOrCreate(top)
            .setBuilder(
                object : SkyFunction() {
                    private var alreadyReset = false

                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        val depValue: com.google.devtools.build.skyframe.GraphTester.StringValue? =
                            env.getValue(dep) as com.google.devtools.build.skyframe.GraphTester.StringValue?
                        if (depValue == null) {
                            return null
                        }
                        if (depValue.getValue() == "depVal2" && !alreadyReset) {
                            alreadyReset = true
                            return Reset.selfOnly(top)
                        }
                        val extraDepValue: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                            env.getValue(extraDep)
                        if (extraDepValue == null) {
                            return null
                        }
                        return com.google.devtools.build.skyframe.GraphTester.StringValue("topVal")
                    }
                })
        tester.getOrCreate(dep).setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("depVal1"))
        tester.getOrCreate(extraDep)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("extraDepVal"))
        tester!!.eval<SkyValue?>( /* keepGoing= */false, top)
        Truth.assertThat(inconsistencyReceiver).isEmpty()

        tester.set(dep, com.google.devtools.build.skyframe.GraphTester.StringValue("depVal2"))
        tester!!.invalidate()
        val result: EvaluationResult<T?> = tester!!.eval<SkyValue?>( /* keepGoing= */false, top)

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(top)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("topVal"))
        Truth.assertThat(inconsistencyReceiver).containsExactly(InconsistencyData.Companion.resetRequested(top))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep)
            .containsExactly(top)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasDirectDepsInGraphThat(top)
            .containsExactly(dep, extraDep)
            .inOrder()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(extraDep)
            .containsExactly(top)
    }

    /**
     * Tests that if a dependency is requested prior to a [SkyFunction.Reset] but not after,
     * then the corresponding reverse dep edge is removed.
     * 
     * 
     * This happens in practice with input-discovering actions, which use mutable state to track
     * input discovery, resulting in unstable dependencies.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resetSelfOnly_depNotRequestedAgainAfterReset() {
        TruthJUnit.assume().that(resetSupported()).isTrue()

        val inconsistencyReceiver = recordInconsistencies()
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        val flakyDep: SkyKey = GraphTester.Companion.skyKey("flakyDep")
        val stableDep: SkyKey = GraphTester.Companion.skyKey("stableDep")

        tester
            .getOrCreate(top)
            .setBuilder(
                object : SkyFunction() {
                    private var alreadyReset = false

                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        env.getValuesAndExceptions(
                            if (alreadyReset)
                                com.google.common.collect.ImmutableList.of<E?>(stableDep)
                            else
                                com.google.common.collect.ImmutableList.of<E?>(stableDep, flakyDep)
                        )
                        if (env.valuesMissing()) {
                            return null
                        }
                        if (!alreadyReset) {
                            alreadyReset = true
                            return Reset.selfOnly(top)
                        }
                        return com.google.devtools.build.skyframe.GraphTester.StringValue("topVal")
                    }
                })
        tester.getOrCreate(stableDep)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("stableDepVal"))
        tester.getOrCreate(flakyDep)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("flakyDepVal"))

        val result: EvaluationResult<T?> = tester!!.eval<SkyValue?>( /* keepGoing= */false, top)

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(top)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("topVal"))
        Truth.assertThat(inconsistencyReceiver).containsExactly(InconsistencyData.Companion.resetRequested(top))

        if (!incrementalitySupported()) {
            return  // Skip assertions on dependency edges when they aren't kept.
        }

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top)
            .containsExactly(stableDep)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(stableDep)
            .containsExactly(top)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(flakyDep)
            .isEmpty()
    }

    /**
     * Tests that reset nodes are properly handled during invalidation after an aborted evaluation.
     * 
     * 
     * Invalidation deletes any nodes that are incomplete from the prior evaluation (in this case
     * `top`). It should also remove the corresponding reverse dep edge from `dep` even
     * though `top` does not have `dep` as a temporary direct dep when the evaluation is
     * aborted.
     * 
     * 
     * An aborted evaluation can happen in practice when there is an error on a `--nokeep_going` build or if the user hits ctrl+c.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resetSelfOnly_evaluationAborted() {
        TruthJUnit.assume().that(resetSupported()).isTrue()
        TruthJUnit.assume().that(incrementalitySupported()).isTrue()

        val inconsistencyReceiver = recordInconsistencies()
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        val dep: SkyKey = GraphTester.Companion.skyKey("dep")

        tester
            .getOrCreate(top)
            .setBuilder(
                object : SkyFunction() {
                    private var alreadyReset = false

                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        if (alreadyReset) {
                            throw java.lang.InterruptedException("Evaluation aborted")
                        }
                        val depValue: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                            env.getValue(dep)
                        if (depValue == null) {
                            return null
                        }
                        alreadyReset = true
                        return Reset.selfOnly(top)
                    }
                })
        tester.getOrCreate(dep).setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("depVal"))

        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { tester!!.eval<SkyValue?>( /* keepGoing= */false, top) })
        Truth.assertThat(inconsistencyReceiver).containsExactly(InconsistencyData.Companion.resetRequested(top))
        inconsistencyReceiver.clear()

        val result: EvaluationResult<T?> = tester!!.eval<SkyValue?>( /* keepGoing= */false, dep)

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(dep)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("depVal"))
        Truth.assertThat(inconsistencyReceiver).isEmpty()
        assertThat(tester.evaluator.getValues()).doesNotContainKey(top)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep)
            .isEmpty()
    }

    /** Basic test of rewinding.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun rewindOneDep() {
        TruthJUnit.assume().that(resetSupported()).isTrue()

        val inconsistencyReceiver = recordInconsistencies()
        val rewindableFunction = RewindableFunction()
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        val dep: SkyKey = GraphTester.Companion.skyKey("dep")

        tester.getOrCreate(dep).setBuilder(rewindableFunction)
        tester
            .getOrCreate(top)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    val depValue: com.google.devtools.build.skyframe.GraphTester.StringValue? =
                        env.getValue(dep) as com.google.devtools.build.skyframe.GraphTester.StringValue?
                    if (depValue == null) {
                        return@setBuilder null
                    }
                    if (depValue == RewindableFunction.Companion.STALE_VALUE) {
                        val rewindGraph: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                            Reset.newRewindGraphFor(top)
                        rewindGraph.putEdge(top, dep)
                        return@setBuilder Reset.of(rewindGraph)
                    }
                    Truth.assertThat(depValue).isEqualTo(RewindableFunction.Companion.FRESH_VALUE)
                    com.google.devtools.build.skyframe.GraphTester.StringValue("topVal")
                })

        val result: EvaluationResult<T?> = tester!!.eval<SkyValue?>( /* keepGoing= */false, top)

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(top)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("topVal"))
        Truth.assertThat(inconsistencyReceiver)
            .containsExactly(
                InconsistencyData.Companion.resetRequested(top),
                InconsistencyData.Companion.rewind(top, com.google.common.collect.ImmutableSet.of<SkyKey?>(dep))
            )
        Truth.assertThat(rewindableFunction.calls).isEqualTo(2)

        if (!incrementalitySupported()) {
            return  // Skip assertions on dependency edges when they aren't kept.
        }

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep)
            .containsExactly(top)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top)
            .containsExactly(dep)
    }

    /**
     * Tests the case where multiple parents attempt to rewind the same node concurrently, one
     * successfully dirties the node, and the other observes the node as already dirty.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun twoParentsRewindSameDep_markedDirtyOnce() {
        TruthJUnit.assume().that(resetSupported()).isTrue()

        val inconsistencyReceiver = recordInconsistencies()
        val rewindableFunction = RewindableFunction()
        val parentBarrier: CyclicBarrier = CyclicBarrier(2)
        val top1: SkyKey = GraphTester.Companion.skyKey("top1")
        val top2: SkyKey = GraphTester.Companion.skyKey("top2")
        val dep: SkyKey = GraphTester.Companion.skyKey("dep")

        tester.getOrCreate(dep).setBuilder(rewindableFunction)
        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.MARK_DIRTY && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER) {
                    awaitUnchecked(parentBarrier)
                }
            },  /* deterministic= */
            false
        )
        val parentFunction: SkyFunction =
            SkyFunction { skyKey, env ->
                val depValue: com.google.devtools.build.skyframe.GraphTester.StringValue? =
                    env.getValue(dep) as com.google.devtools.build.skyframe.GraphTester.StringValue?
                if (depValue == null) {
                    return@SkyFunction null
                }
                if (depValue == RewindableFunction.Companion.STALE_VALUE) {
                    awaitUnchecked(parentBarrier)
                    val rewindGraph: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        Reset.newRewindGraphFor(skyKey)
                    rewindGraph.putEdge(skyKey, dep)
                    return@SkyFunction Reset.of(rewindGraph)
                }
                Truth.assertThat(depValue).isEqualTo(RewindableFunction.Companion.FRESH_VALUE)
                com.google.devtools.build.skyframe.GraphTester.StringValue("topVal")
            }
        tester.getOrCreate(top1).setBuilder(parentFunction)
        tester.getOrCreate(top2).setBuilder(parentFunction)

        val result: EvaluationResult<T?> = tester!!.eval<SkyValue?>( /* keepGoing= */false, top1, top2)

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(top1)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("topVal"))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(top2)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("topVal"))
        Truth.assertThat(inconsistencyReceiver)
            .containsExactly(
                InconsistencyData.Companion.resetRequested(top1),
                InconsistencyData.Companion.rewind(top1, com.google.common.collect.ImmutableSet.of<SkyKey?>(dep)),
                InconsistencyData.Companion.resetRequested(top2),
                InconsistencyData.Companion.rewind(top2, com.google.common.collect.ImmutableSet.of<SkyKey?>(dep))
            )
        Truth.assertThat(rewindableFunction.calls).isEqualTo(2)

        if (!incrementalitySupported()) {
            return  // Skip assertions on dependency edges when they aren't kept.
        }

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top1)
            .containsExactly(dep)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top2)
            .containsExactly(dep)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep)
            .containsExactly(top1, top2)
    }

    /**
     * Tests the case where multiple parents attempt to rewind the same node concurrently and one
     * successfully dirties the node, which then completes before the second parent dirties the node
     * again.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun twoParentsRewindSameDep_markedDirtyTwice() {
        TruthJUnit.assume().that(resetSupported()).isTrue()

        val inconsistencyReceiver = recordInconsistencies()
        val rewindableFunction = RewindableFunction()
        val parentBarrier: CyclicBarrier = CyclicBarrier(2)
        val isFirstParent: AtomicBoolean = AtomicBoolean(true)
        val firstParentDone: CountDownLatch = CountDownLatch(1)
        val top1: SkyKey = GraphTester.Companion.skyKey("top1")
        val top2: SkyKey = GraphTester.Companion.skyKey("top2")
        val dep: SkyKey = GraphTester.Companion.skyKey("dep")

        tester.getOrCreate(dep).setBuilder(rewindableFunction)
        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.MARK_DIRTY && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE && !isFirstParent.getAndSet(
                        false
                    )
                ) {
                    // Lost the race. Wait for the first parent to finish so we rewind again. We could just
                    // wait for dep to finish, but then we might mark it dirty before the first parent uses
                    // it, which would lead to flaky BUILDING_PARENT_FOUND_UNDONE_CHILD inconsistencies.
                    com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly(firstParentDone)
                }
            },  /* deterministic= */
            false
        )
        val parentFunction: SkyFunction =
            SkyFunction { skyKey, env ->
                val depValue: com.google.devtools.build.skyframe.GraphTester.StringValue? =
                    env.getValue(dep) as com.google.devtools.build.skyframe.GraphTester.StringValue?
                if (depValue == null) {
                    return@SkyFunction null
                }
                if (depValue == RewindableFunction.Companion.STALE_VALUE) {
                    awaitUnchecked(parentBarrier)
                    val rewindGraph: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        Reset.newRewindGraphFor(skyKey)
                    rewindGraph.putEdge(skyKey, dep)
                    return@SkyFunction Reset.of(rewindGraph)
                }
                Truth.assertThat(depValue).isEqualTo(RewindableFunction.Companion.FRESH_VALUE)
                firstParentDone.countDown()
                com.google.devtools.build.skyframe.GraphTester.StringValue("topVal")
            }
        tester.getOrCreate(top1).setBuilder(parentFunction)
        tester.getOrCreate(top2).setBuilder(parentFunction)

        val result: EvaluationResult<T?> = tester!!.eval<SkyValue?>( /* keepGoing= */false, top1, top2)

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(top1)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("topVal"))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(top2)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("topVal"))
        Truth.assertThat(inconsistencyReceiver)
            .containsExactly(
                InconsistencyData.Companion.resetRequested(top1),
                InconsistencyData.Companion.rewind(top1, com.google.common.collect.ImmutableSet.of<SkyKey?>(dep)),
                InconsistencyData.Companion.resetRequested(top2),
                InconsistencyData.Companion.rewind(top2, com.google.common.collect.ImmutableSet.of<SkyKey?>(dep))
            )
        Truth.assertThat(rewindableFunction.calls).isEqualTo(3)

        if (!incrementalitySupported()) {
            return  // Skip assertions on dependency edges when they aren't kept.
        }

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top1)
            .containsExactly(dep)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top2)
            .containsExactly(dep)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep)
            .containsExactly(top1, top2)
    }

    /**
     * Regression test for b/315301248.
     * 
     * 
     * In a `--nokeep_going` build, multiple parents attempt to rewind the same node
     * concurrently. One successfully dirties the node, which then completes with an error before the
     * second parent attempts to dirty the node again. If the second rewinding attempt actually
     * transitions the node from done (in error) to dirty, we would crash during error bubbling, which
     * reasonably expects the errorful node to be done.
     * 
     * 
     * The solution is to ignore rewinding attempts on errorful nodes.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun twoParentsRewindSameDep_depEvaluatesToErrorAfterRewind() {
        TruthJUnit.assume().that(resetSupported()).isTrue()

        val inconsistencyReceiver = recordInconsistencies()
        val rewindableErrorFunction: SkyFunction? =
            object : SkyFunction() {
                private var calls = 0

                @Throws(SkyFunctionException::class)
                public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue? {
                    if (++calls == 1) {
                        return RewindableFunction.Companion.STALE_VALUE
                    }
                    throw GenericFunctionException(SomeErrorException("error"))
                }
            }
        val parentBarrier: CyclicBarrier = CyclicBarrier(2)
        val isFirstParent: AtomicBoolean = AtomicBoolean(true)
        val depErrorSet: CountDownLatch = CountDownLatch(1)
        val top1: SkyKey = GraphTester.Companion.skyKey("top1")
        val top2: SkyKey = GraphTester.Companion.skyKey("top2")
        val dep: SkyKey = GraphTester.Companion.skyKey("dep")

        tester.getOrCreate(dep).setBuilder(rewindableErrorFunction)
        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.MARK_DIRTY && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE && !isFirstParent.getAndSet(
                        false
                    )
                ) {
                    // Lost the race. Wait for dep have its error set so that we attempt to rewind a done
                    // node in error.
                    com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly(depErrorSet)
                } else if (key.equals(dep)
                    && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER && ValueWithMetadata.getMaybeErrorInfo(
                        context as SkyValue?
                    ) != null
                ) {
                    depErrorSet.countDown()
                }
            },  /* deterministic= */
            false
        )
        val parentFunction: SkyFunction =
            SkyFunction { skyKey, env ->
                val depValue: com.google.devtools.build.skyframe.GraphTester.StringValue? =
                    env.getValue(dep) as com.google.devtools.build.skyframe.GraphTester.StringValue?
                if (depValue == null) {
                    return@SkyFunction null
                }
                Truth.assertThat(depValue).isEqualTo(RewindableFunction.Companion.STALE_VALUE)
                awaitUnchecked(parentBarrier)
                val rewindGraph: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    Reset.newRewindGraphFor(skyKey)
                rewindGraph.putEdge(skyKey, dep)
                Reset.of(rewindGraph)
            }
        tester.getOrCreate(top1).setBuilder(parentFunction)
        tester.getOrCreate(top2).setBuilder(parentFunction)

        var result: EvaluationResult<T?> = tester!!.eval<SkyValue?>( /* keepGoing= */false, top1, top2)

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasError()
        assertThat(result.errorMap().keySet()).containsAnyOf(top1, top2)
        Truth.assertThat(inconsistencyReceiver)
            .containsExactly(
                InconsistencyData.Companion.resetRequested(top1),
                InconsistencyData.Companion.rewind(top1, com.google.common.collect.ImmutableSet.of<SkyKey?>(dep)),
                InconsistencyData.Companion.resetRequested(top2),
                InconsistencyData.Companion.rewind(top2, com.google.common.collect.ImmutableSet.of<SkyKey?>(dep))
            )

        if (!incrementalitySupported()) {
            return  // Skip assertions on dependency edges when they aren't kept.
        }

        result = tester!!.eval<SkyValue?>( /* keepGoing= */false, dep)

        // The parents never completed, so an incremental build deletes them. Check that they are no
        // longer in the graph and that rdeps are removed from dep.
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(dep)
            .isNotNull()
        assertThat(tester.evaluator.getValues().keySet()).containsNoneOf(top1, top2)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(dep)
            .isEmpty()
    }

    /**
     * Tests that when a node is rewound and evaluates to an error, its reverse transitive closure is
     * deleted from the graph, including parents that were done before rewinding took place.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun depWithDoneParentEvaluatesToErrorAfterRewind_reverseTransitiveClosureDeleted() {
        TruthJUnit.assume().that(resetSupported()).isTrue()
        TruthJUnit.assume().that(incrementalitySupported()).isTrue()

        val inconsistencyReceiver = recordInconsistencies()
        val rewindableErrorFunction: SkyFunction? =
            object : SkyFunction() {
                private var calls = 0

                @Throws(SkyFunctionException::class)
                public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue? {
                    if (++calls == 1) {
                        return RewindableFunction.Companion.STALE_VALUE
                    }
                    throw GenericFunctionException(SomeErrorException("error"))
                }
            }
        val goodTopDone: CountDownLatch = CountDownLatch(1)
        val goodTop: SkyKey = GraphTester.Companion.skyKey("goodTop")
        val badTop: SkyKey = GraphTester.Companion.skyKey("badTop")
        val dep: SkyKey = GraphTester.Companion.nonHermeticKey("dep")

        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (key.equals(goodTop) && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER) {
                    goodTopDone.countDown()
                }
            },  /* deterministic= */
            false
        )
        tester.getOrCreate(dep).setBuilder(rewindableErrorFunction)
        tester.getOrCreate(goodTop).addDependency(dep).setComputedValue(GraphTester.Companion.COPY)
        tester
            .getOrCreate(badTop)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    if (env.getValue(dep) == null) {
                        return@setBuilder null
                    }
                    goodTopDone.await()
                    val rewindGraph: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        Reset.newRewindGraphFor(badTop)
                    rewindGraph.putEdge(badTop, dep)
                    Reset.of(rewindGraph)
                })

        var result: EvaluationResult<T?> = tester!!.eval<SkyValue?>( /* keepGoing= */false, goodTop, badTop)

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasError()
        assertThat(result.errorMap().keySet()).containsExactly(badTop)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasEntryThat(goodTop)
            .isEqualTo(RewindableFunction.Companion.STALE_VALUE)
        Truth.assertThat(inconsistencyReceiver)
            .containsExactly(
                InconsistencyData.Companion.resetRequested(badTop),
                InconsistencyData.Companion.rewind(badTop, com.google.common.collect.ImmutableSet.of<SkyKey?>(dep))
            )

        result = tester.eval<SkyValue?>( /* keepGoing= */false, *arrayOfNulls<SkyKey>(0))

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasNoError()
        assertThat(tester!!.getEvaluator().getValues().keySet()).containsNoneOf(dep, badTop, goodTop)
    }

    /**
     * Test for a rewind graph with depth > 1.
     * 
     * 
     * Since `mid` simply propagates the value of `bottom`, `top` must rewind
     * both `mid` and `bottom`. See [ ][com.google.devtools.build.lib.actions.ActionExecutionMetadata.mayInsensitivelyPropagateInputs]
     * for the case that this test is simulating.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun rewindTransitiveDep() {
        TruthJUnit.assume().that(resetSupported()).isTrue()

        val inconsistencyReceiver = recordInconsistencies()
        val rewindableFunction = RewindableFunction()
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        val mid: SkyKey = GraphTester.Companion.skyKey("mid")
        val bottom: SkyKey = GraphTester.Companion.skyKey("bottom")

        tester.getOrCreate(bottom).setBuilder(rewindableFunction)
        tester.getOrCreate(mid).addDependency(bottom).setComputedValue(GraphTester.Companion.COPY)
        tester
            .getOrCreate(top)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        val midValue: com.google.devtools.build.skyframe.GraphTester.StringValue? =
                            env.getValue(mid) as com.google.devtools.build.skyframe.GraphTester.StringValue?
                        if (midValue == null) {
                            return null
                        }
                        if (midValue == RewindableFunction.Companion.STALE_VALUE) {
                            val rewindGraph: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                                Reset.newRewindGraphFor(top)
                            rewindGraph.putEdge(top, mid)
                            rewindGraph.putEdge(mid, bottom)
                            return Reset.of(rewindGraph)
                        }
                        Truth.assertThat(midValue).isEqualTo(RewindableFunction.Companion.FRESH_VALUE)
                        return com.google.devtools.build.skyframe.GraphTester.StringValue("topVal")
                    }
                })

        val result: EvaluationResult<T?> = tester!!.eval<SkyValue?>( /* keepGoing= */false, top)

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(top)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("topVal"))
        Truth.assertThat(inconsistencyReceiver)
            .containsExactly(
                InconsistencyData.Companion.resetRequested(top),
                InconsistencyData.Companion.rewind(top, com.google.common.collect.ImmutableSet.of<SkyKey?>(mid, bottom))
            )
        Truth.assertThat(rewindableFunction.calls).isEqualTo(2)

        if (!incrementalitySupported()) {
            return  // Skip assertions on dependency edges when they aren't kept.
        }

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasDirectDepsInGraphThat(top)
            .containsExactly(mid)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(mid)
            .containsExactly(top)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasDirectDepsInGraphThat(mid)
            .containsExactly(bottom)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(bottom)
            .containsExactly(mid)
    }

    /**
     * Tests that incompletely rewound nodes are properly handled during invalidation after an aborted
     * evaluation.
     * 
     * 
     * Covers the concern described at b/149243918#comment9: without rewinding, we have an
     * invariant that after an evaluation, a done node cannot depend on a dirty node. The invalidator
     * leverges this invariant by short-circuiting when it visits a dirty node, under the assumption
     * that any rdeps are either already dirty or present in the invalidation frontier.
     * 
     * 
     * In this test, a value propagates from `bottom` to `mid` to `goodTop`.
     * However, after `goodTop` is done, `badTop` rewinds `mid`, and then the
     * evaluation is aborted. On the incremental build, `bottom` changes. We must recompute
     * `goodTop`, but the only path from `bottom` to `goodTop` goes through `mid`, which is dirty, and so the invalidator will never visit `goodTop`.
     * 
     * 
     * The solution: instead of relying on bottom-up invalidation, rewound nodes are treated like
     * inflight nodes and deleted (along with their reverse transitive closure) prior to the next
     * evaluation.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun evaluationAbortedWithRewoundNodeOnInvalidationPath_dirty() {
        TruthJUnit.assume().that(resetSupported()).isTrue()
        TruthJUnit.assume().that(incrementalitySupported()).isTrue()

        val inconsistencyReceiver = recordInconsistencies()
        val goodTopDone: CountDownLatch = CountDownLatch(1)
        val goodTop: SkyKey = GraphTester.Companion.skyKey("goodTop")
        val badTop: SkyKey = GraphTester.Companion.skyKey("badTop")
        val mid: SkyKey = GraphTester.Companion.skyKey("mid")
        val bottom: SkyKey = GraphTester.Companion.nonHermeticKey("bottom")

        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (key.equals(goodTop) && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER) {
                    goodTopDone.countDown()
                }
            },  /* deterministic= */
            false
        )
        tester.getOrCreate(goodTop).addDependency(mid).setComputedValue(GraphTester.Companion.COPY)
        tester
            .getOrCreate(badTop)
            .setBuilder(
                object : SkyFunction() {
                    private var alreadyReset = false

                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        if (alreadyReset) {
                            throw java.lang.InterruptedException("Evaluation aborted")
                        }
                        if (env.getValue(mid) == null) {
                            return null
                        }
                        goodTopDone.await()
                        alreadyReset = true
                        val rewindGraph: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                            Reset.newRewindGraphFor(badTop)
                        rewindGraph.putEdge(badTop, mid)
                        return Reset.of(rewindGraph)
                    }
                })
        tester.getOrCreate(mid).addDependency(bottom).setComputedValue(GraphTester.Companion.COPY)
        tester.getOrCreate(bottom).setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("val1"))

        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { tester!!.eval<SkyValue?>( /* keepGoing= */false, goodTop, badTop) })
        Truth.assertThat(inconsistencyReceiver)
            .containsExactly(
                InconsistencyData.Companion.resetRequested(badTop),
                InconsistencyData.Companion.rewind(badTop, com.google.common.collect.ImmutableSet.of<SkyKey?>(mid))
            )

        tester.set(bottom, com.google.devtools.build.skyframe.GraphTester.StringValue("val2"))
        tester!!.invalidate()
        val result: EvaluationResult<T?> = tester!!.eval<SkyValue?>( /* keepGoing= */false, goodTop)

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(goodTop)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("val2"))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasDirectDepsInGraphThat(goodTop)
            .containsExactly(mid)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(mid)
            .containsExactly(goodTop)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasDirectDepsInGraphThat(mid)
            .containsExactly(bottom)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(bottom)
            .containsExactly(mid)
    }

    /**
     * Similar to [.evaluationAbortedWithRewoundNodeOnInvalidationPath_dirty] except that the
     * rewound node is inflight when the evaluation is aborted, so this actually works without the
     * special handling added for rewound nodes.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun evaluationAbortedWithRewoundNodeOnInvalidationPath_inflight() {
        TruthJUnit.assume().that(resetSupported()).isTrue()
        TruthJUnit.assume().that(incrementalitySupported()).isTrue()

        val inconsistencyReceiver = recordInconsistencies()
        val goodTopDone: CountDownLatch = CountDownLatch(1)
        val rewindingInProgress: AtomicBoolean = AtomicBoolean(false)
        val goodTop: SkyKey = GraphTester.Companion.skyKey("goodTop")
        val badTop: SkyKey = GraphTester.Companion.skyKey("badTop")
        val mid: SkyKey = GraphTester.Companion.nonHermeticKey("mid")
        val bottom: SkyKey = GraphTester.Companion.nonHermeticKey("bottom")

        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (key.equals(goodTop) && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER) {
                    goodTopDone.countDown()
                }
            },  /* deterministic= */
            false
        )
        tester.getOrCreate(goodTop).addDependency(mid).setComputedValue(GraphTester.Companion.COPY)
        tester
            .getOrCreate(badTop)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    if (env.getValue(mid) == null) {
                        return@setBuilder null
                    }
                    goodTopDone.await()
                    rewindingInProgress.set(true)
                    val rewindGraph: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        Reset.newRewindGraphFor(badTop)
                    rewindGraph.putEdge(badTop, mid)
                    Reset.of(rewindGraph)
                })
        tester
            .getOrCreate(mid)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    if (rewindingInProgress.get()) {
                        throw java.lang.InterruptedException("Evaluation aborted")
                    }
                    env.getValue(bottom)
                })
        tester.getOrCreate(bottom).setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("val1"))

        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { tester!!.eval<SkyValue?>( /* keepGoing= */false, goodTop, badTop) })
        Truth.assertThat(inconsistencyReceiver)
            .containsExactly(
                InconsistencyData.Companion.resetRequested(badTop),
                InconsistencyData.Companion.rewind(badTop, com.google.common.collect.ImmutableSet.of<SkyKey?>(mid))
            )

        rewindingInProgress.set(false)
        tester.set(bottom, com.google.devtools.build.skyframe.GraphTester.StringValue("val2"))
        tester!!.invalidate()
        val result: EvaluationResult<T?> = tester!!.eval<SkyValue?>( /* keepGoing= */false, goodTop)

        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(goodTop)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("val2"))
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasDirectDepsInGraphThat(goodTop)
            .containsExactly(mid)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(mid)
            .containsExactly(goodTop)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasDirectDepsInGraphThat(mid)
            .containsExactly(bottom)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasReverseDepsInGraphThat(bottom)
            .containsExactly(mid)
    }

    private fun recordInconsistencies(): RecordingInconsistencyReceiver {
        val inconsistencyReceiver = RecordingInconsistencyReceiver()
        tester!!.setGraphInconsistencyReceiver(inconsistencyReceiver)
        tester!!.initialize()
        return inconsistencyReceiver
    }

    private class RecordingInconsistencyReceiver

        : GraphInconsistencyReceiver, Iterable<InconsistencyData?> {
        private val inconsistencies: MutableList<InconsistencyData?> = java.util.ArrayList<InconsistencyData?>()

        @kotlin.jvm.Synchronized
        public override fun noteInconsistencyAndMaybeThrow(
            key: SkyKey?, otherKeys: MutableCollection<SkyKey?>?, inconsistency: Inconsistency?
        ) {
            inconsistencies.add(InconsistencyData.Companion.create(key, otherKeys, inconsistency))
        }

        override fun iterator(): MutableIterator<InconsistencyData?>? {
            return inconsistencies.iterator()
        }

        fun clear() {
            inconsistencies.clear()
        }
    }

    /**
     * [SkyFunction] for rewinding tests that returns [.STALE_VALUE] the first time and
     * [.FRESH_VALUE] thereafter.
     */
    private class RewindableFunction : SkyFunction {
        private var calls = 0

        public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue? {
            return if (++calls == 1) STALE_VALUE else FRESH_VALUE
        }

        companion object {
            val STALE_VALUE: com.google.devtools.build.skyframe.GraphTester.StringValue =
                com.google.devtools.build.skyframe.GraphTester.StringValue("stale")
            val FRESH_VALUE: com.google.devtools.build.skyframe.GraphTester.StringValue =
                com.google.devtools.build.skyframe.GraphTester.StringValue("fresh")
        }
    }

    /**
     * The same dep is requested in two groups, but its value determines what the other dep in the
     * second group is. When it changes, the other dep in the second group should not be requested.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sameDepInTwoGroups() {
        initializeTester()

        // leaf4 should not be built in the second build.
        val leaf4: SkyKey = GraphTester.Companion.skyKey("leaf4")
        val shouldNotBuildLeaf4: AtomicBoolean = AtomicBoolean(false)
        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                check(
                    !(shouldNotBuildLeaf4.get()
                            && key.equals(leaf4)
                            && type != com.google.devtools.build.skyframe.NotifyingHelper.EventType.REMOVE_REVERSE_DEP && type != com.google.devtools.build.skyframe.NotifyingHelper.EventType.GET_BATCH)
                ) {
                    ("leaf4 should not have been considered this build: "
                            + type
                            + ", "
                            + order
                            + ", "
                            + context)
                }
            },  /* deterministic= */
            false
        )
        tester.set(leaf4, com.google.devtools.build.skyframe.GraphTester.StringValue("leaf4"))

        // Create leaf0, leaf1 and leaf2 values with values "leaf2", "leaf3", "leaf4" respectively.
        // These will be requested as one dependency group. In the second build, leaf2 will have the
        // value "leaf5".
        val leaves: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()
        for (i in 0..2) {
            val leaf: SkyKey =
                if (i == 2) GraphTester.Companion.nonHermeticKey("leaf" + i) else GraphTester.Companion.skyKey("leaf" + i)
            leaves.add(leaf)
            tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leaf" + (i + 2)))
        }

        // Create "top" value. It depends on all leaf values in two overlapping dependency groups.
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        val topValue: SkyValue = com.google.devtools.build.skyframe.GraphTester.StringValue("top")
        tester
            .getOrCreate(topKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    // Request the first group, [leaf0, leaf1, leaf2].
                    // In the first build, it has values ["leaf2", "leaf3", "leaf4"].
                    // In the second build it has values ["leaf2", "leaf3", "leaf5"]
                    val values: SkyframeLookupResult = env.getValuesAndExceptions(leaves)
                    if (env.valuesMissing()) {
                        return@setBuilder null
                    }

                    // Request the second group. In the first build it's [leaf2, leaf4].
                    // In the second build it's [leaf2, leaf5]
                    env.getValuesAndExceptions(
                        com.google.common.collect.ImmutableList.of<E?>(
                            leaves.get(2),
                            GraphTester.Companion.skyKey((values.get(leaves.get(2)) as com.google.devtools.build.skyframe.GraphTester.StringValue).getValue())
                        )
                    )
                    if (env.valuesMissing()) {
                        return@setBuilder null
                    }
                    topValue
                })

        // First build: assert we can evaluate "top".
        assertThat(tester.evalAndGet( /*keepGoing=*/false, topKey)).isEqualTo(topValue)

        // Second build: replace "leaf4" by "leaf5" in leaf2's value. Assert leaf4 is not requested.
        val leaf5: SkyKey = GraphTester.Companion.skyKey("leaf5")
        tester.set(leaf5, com.google.devtools.build.skyframe.GraphTester.StringValue("leaf5"))
        tester.set(leaves.get(2), com.google.devtools.build.skyframe.GraphTester.StringValue("leaf5"))
        tester!!.invalidate()
        shouldNotBuildLeaf4.set(true)
        assertThat(tester.evalAndGet( /*keepGoing=*/false, topKey)).isEqualTo(topValue)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dirtyAndChanged() {
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        val mid: SkyKey = GraphTester.Companion.nonHermeticKey("mid")
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        tester.getOrCreate(top).addDependency(mid).setComputedValue(GraphTester.Companion.COPY)
        tester.getOrCreate(mid).addDependency(leaf).setComputedValue(GraphTester.Companion.COPY)
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
        // For invalidation.
        tester.set("dummy", com.google.devtools.build.skyframe.GraphTester.StringValue("dummy"))
        var topValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(topValue.getValue()).isEqualTo("leafy")
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("crunchy"))
        tester!!.invalidate()
        // For invalidation.
        tester!!.evalAndGet("dummy")
        tester.getOrCreate(mid,  /*markAsModified=*/true)
        tester!!.invalidate()
        topValue = tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(topValue.getValue()).isEqualTo("crunchy")
    }

    /**
     * Test whether a value that was already marked changed will be incorrectly marked dirty, not
     * changed, if another thread tries to mark it just dirty. To exercise this, we need to have a
     * race condition where both threads see that the value is not dirty yet, then the "changed"
     * thread marks the value changed before the "dirty" thread marks the value dirty. To accomplish
     * this, we use a countdown latch to make the "dirty" thread wait until the "changed" thread is
     * done, and another countdown latch to make both of them wait until they have both checked if the
     * value is currently clean.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dirtyAndChangedValueIsChanged() {
        val parent: SkyKey = GraphTester.Companion.nonHermeticKey("parent")
        val blockingEnabled: AtomicBoolean = AtomicBoolean(false)
        val waitForChanged: CountDownLatch = CountDownLatch(1)
        // changed thread checks value entry once (to see if it is changed). dirty thread checks twice,
        // to see if it is changed, and if it is dirty.
        val threadsStarted: CountDownLatch = CountDownLatch(3)
        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (!blockingEnabled.get()) {
                    return@injectGraphListenerForTesting
                }
                if (!key.equals(parent)) {
                    return@injectGraphListenerForTesting
                }
                if (type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.IS_CHANGED && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE) {
                    threadsStarted.countDown()
                }
                // Dirtiness only checked by dirty thread.
                if (type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.IS_DIRTY && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE) {
                    threadsStarted.countDown()
                }
                if (type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.MARK_DIRTY) {
                    TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                        threadsStarted, "Both threads did not query if value isChanged in time"
                    )
                    if (order == com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE) {
                        val dirtyType: DirtyType? = context as DirtyType?
                        if (dirtyType.equals(DirtyType.DIRTY)) {
                            TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                                waitForChanged, "'changed' thread did not mark value changed in time"
                            )
                            return@injectGraphListenerForTesting
                        }
                    }
                    if (order == com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER) {
                        val dirtyType: DirtyType = (context as MarkDirtyAfterContext).dirtyType
                        if (dirtyType.equals(DirtyType.CHANGE)) {
                            waitForChanged.countDown()
                        }
                    }
                }
            },  /* deterministic= */
            false
        )
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))
        tester.getOrCreate(parent).addDependency(leaf).setComputedValue(GraphTester.Companion.CONCATENATE)
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?>
        result = tester!!.eval<SkyValue?>( /* keepGoing= */false, parent)
        assertThat(result.get(parent).getValue()).isEqualTo("leaf")
        // Invalidate leaf, but don't actually change it. It will transitively dirty parent
        // concurrently with parent directly dirtying itself.
        tester.getOrCreate(leaf,  /* markAsModified= */true)
        val other2: SkyKey = GraphTester.Companion.skyKey("other2")
        tester.set(other2, com.google.devtools.build.skyframe.GraphTester.StringValue("other2"))
        // Invalidate parent, actually changing it.
        tester.getOrCreate(parent,  /* markAsModified= */true).addDependency(other2)
        tester!!.invalidate()
        blockingEnabled.set(true)
        result = tester!!.eval<SkyValue?>( /* keepGoing= */false, parent)
        assertThat(result.get(parent).getValue()).isEqualTo("leafother2")
        Truth.assertThat(waitForChanged.getCount()).isEqualTo(0)
        Truth.assertThat(threadsStarted.getCount()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hermeticSkyFunctionCanThrowTransientErrorThenRecover() {
        val leaf: SkyKey = GraphTester.Companion.skyKey("leaf")
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        // When top depends on leaf, but throws a transient error,
        tester
            .getOrCreate(top)
            .addDependency(leaf)
            .setHasTransientError(true)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        val value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("value")
        tester.getOrCreate(leaf).setConstantValue(value)
        // And the first build throws a transient error (as expected),
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/true, top)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(top)
            .hasExceptionThat().isNotNull()
        // And then top's transient error is removed,
        tester.getOrCreate(top,  /*markAsModified=*/false).setHasTransientError(false)
        tester!!.invalidateTransientErrors()
        // Then top evaluates successfully, even though it was hermetic and didn't give the same result
        // on successive evaluations with the same inputs.
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/true, top)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasNoError()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(top).isEqualTo(value)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun singleValueDependsOnManyDirtyValues() {
        val values: Array<SkyKey?> = arrayOfNulls<SkyKey>(TEST_NODE_COUNT)
        val expected: java.lang.StringBuilder = java.lang.StringBuilder()
        for (i in values.indices) {
            val valueName = i.toString()
            values[i] = GraphTester.Companion.nonHermeticKey(valueName)
            tester.set(values[i], com.google.devtools.build.skyframe.GraphTester.StringValue(valueName))
            expected.append(valueName)
        }
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        val value: TestFunction = tester.getOrCreate(topKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        for (skyKey in values) {
            value.addDependency(skyKey)
        }

        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey)
        assertThat(result.get(topKey)).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue(expected.toString()))

        for (j in 0..<RUNS) {
            for (skyKey in values) {
                tester.getOrCreate(skyKey,  /*markAsModified=*/true)
            }
            // This value has an error, but we should never discover it because it is not marked changed
            // and all of its dependencies re-evaluate to the same thing.
            tester.getOrCreate(topKey,  /*markAsModified=*/false).setHasError(true)
            tester!!.invalidate()

            result = tester!!.eval<SkyValue?>( /* keepGoing= */false, topKey)
            assertThat(result.get(topKey)).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue(expected.toString()))
        }
    }

    /**
     * Tests scenario where we have dirty values in the graph, and then one of them is deleted since
     * its evaluation did not complete before an error was thrown. Can either test the graph via an
     * evaluation of that deleted value, or an invalidation of a child, and can either remove the
     * thrown error or throw it again on that evaluation.
     */
    @Throws(java.lang.Exception::class)
    private fun dirtyValueChildrenProperlyRemovedOnEarlyBuildAbort(
        reevaluateMissingValue: Boolean, removeError: Boolean
    ) {
        val errorKey: SkyKey = GraphTester.Companion.nonHermeticKey("error")
        tester.set(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("biding time"))
        val slowKey: SkyKey = GraphTester.Companion.nonHermeticKey("slow")
        tester.set(slowKey, com.google.devtools.build.skyframe.GraphTester.StringValue("slow"))
        val midKey: SkyKey = GraphTester.Companion.skyKey("mid")
        tester.getOrCreate(midKey).addDependency(slowKey).setComputedValue(GraphTester.Companion.COPY)
        val lastKey: SkyKey = GraphTester.Companion.nonHermeticKey("last")
        tester.set(lastKey, com.google.devtools.build.skyframe.GraphTester.StringValue("last"))
        val motherKey: SkyKey = GraphTester.Companion.skyKey("mother")
        tester
            .getOrCreate(motherKey)
            .addDependency(errorKey)
            .addDependency(midKey)
            .addDependency(lastKey)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        val fatherKey: SkyKey = GraphTester.Companion.skyKey("father")
        tester
            .getOrCreate(fatherKey)
            .addDependency(errorKey)
            .addDependency(midKey)
            .addDependency(lastKey)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, motherKey, fatherKey)
        assertThat(result.get(motherKey).getValue()).isEqualTo("biding timeslowlast")
        assertThat(result.get(fatherKey).getValue()).isEqualTo("biding timeslowlast")
        tester.set(slowKey, null)
        // Each parent depends on errorKey, midKey, lastKey. We keep slowKey waiting until errorKey is
        // finished. So there is no way lastKey can be enqueued by either parent. Thus, the parent that
        // is cleaned has not interacted with lastKey this build. Still, lastKey's reverse dep on that
        // parent should be removed.
        val errorFinish: CountDownLatch = CountDownLatch(1)
        tester.set(errorKey, null)
        tester
            .getOrCreate(errorKey)
            .setBuilder(
                ChainedFunction( /*notifyStart=*/
                    null,  /*waitToFinish=*/
                    null,  /*notifyFinish=*/
                    errorFinish,  /*waitForException=*/
                    false,  /*value=*/
                    null,  /*deps=*/
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        tester
            .getOrCreate(slowKey)
            .setBuilder(
                ChainedFunction( /*notifyStart=*/
                    null,  /*waitToFinish=*/
                    errorFinish,  /*notifyFinish=*/
                    null,  /*waitForException=*/
                    true,
                    com.google.devtools.build.skyframe.GraphTester.StringValue("leaf2"),  /*deps=*/
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        tester!!.invalidate()
        // errorKey finishes, written to graph -> leafKey maybe starts+finishes & (Visitor aborts)
        // -> one of mother or father builds. The other one should be cleaned, and no references to it
        // left in the graph.
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/false, motherKey, fatherKey)
        assertThat(result.hasError()).isTrue()
        // Only one of mother or father should be in the graph.
        Truth.assertWithMessage("%s, %s", result.getError(motherKey), result.getError(fatherKey))
            .that((result.getError(motherKey) == null) != (result.getError(fatherKey) == null))
            .isTrue()
        val parentKey: SkyKey? =
            if (reevaluateMissingValue == (result.getError(motherKey) == null)) motherKey else fatherKey
        // Give slowKey a nice ordinary builder.
        tester
            .getOrCreate(slowKey,  /*markAsModified=*/false)
            .setBuilder(null)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("leaf2"))
        if (removeError) {
            tester
                .getOrCreate(errorKey,  /*markAsModified=*/true)
                .setBuilder(null)
                .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("reformed"))
        }
        var lastString = "last"
        if (!reevaluateMissingValue) {
            // Mark the last key modified if we're not trying the absent value again. This invalidation
            // will test if lastKey still has a reference to the absent value.
            lastString = "last2"
            tester.set(lastKey, com.google.devtools.build.skyframe.GraphTester.StringValue(lastString))
        }
        tester!!.invalidate()
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/false, parentKey)
        if (removeError) {
            assertThat(result.get(parentKey).getValue()).isEqualTo("reformedleaf2" + lastString)
        } else {
            assertThat(result.getError(parentKey)).isNotNull()
        }
    }

    /**
     * The following four tests (dirtyChildrenProperlyRemovedWith*) test the consistency of the graph
     * after a failed build in which a dirty value should have been deleted from the graph. The
     * consistency is tested via either evaluating the missing value, or the re-evaluating the present
     * value, and either clearing the error or keeping it. To evaluate the present value, we
     * invalidate the error value to force re-evaluation. Related to bug "skyframe m1: graph may not
     * be properly cleaned on interrupt or failure".
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dirtyChildrenProperlyRemovedWithInvalidateRemoveError() {
        dirtyValueChildrenProperlyRemovedOnEarlyBuildAbort( /*reevaluateMissingValue=*/
            false,  /*removeError=*/true
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dirtyChildrenProperlyRemovedWithInvalidateKeepError() {
        dirtyValueChildrenProperlyRemovedOnEarlyBuildAbort( /*reevaluateMissingValue=*/
            false,  /*removeError=*/false
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dirtyChildrenProperlyRemovedWithReevaluateRemoveError() {
        dirtyValueChildrenProperlyRemovedOnEarlyBuildAbort( /*reevaluateMissingValue=*/
            true,  /*removeError=*/true
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dirtyChildrenProperlyRemovedWithReevaluateKeepError() {
        dirtyValueChildrenProperlyRemovedOnEarlyBuildAbort( /*reevaluateMissingValue=*/
            true,  /*removeError=*/false
        )
    }

    /**
     * Regression test: enqueue so many values that some of them won't have started processing, and
     * then either interrupt processing or have a child throw an error. In the latter case, this also
     * tests that a value that hasn't started processing can still have a child error bubble up to it.
     * In both cases, it tests that the graph is properly cleaned of the dirty values and references
     * to them.
     */
    @Throws(java.lang.Exception::class)
    private fun manyDirtyValuesClearChildrenOnFail(interrupt: Boolean) {
        val leafKey: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        tester.set(leafKey, com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
        val lastKey: SkyKey = GraphTester.Companion.nonHermeticKey("last")
        tester.set(lastKey, com.google.devtools.build.skyframe.GraphTester.StringValue("last"))
        val tops: MutableList<SkyKey> = java.util.ArrayList<SkyKey>()
        // Request far more top-level values than there are threads, so some of them will block until
        // the
        // leaf child is enqueued for processing.
        for (i in 0..9999) {
            val topKey: SkyKey = GraphTester.Companion.skyKey("top" + i)
            tester
                .getOrCreate(topKey)
                .addDependency(leafKey)
                .addDependency(lastKey)
                .setComputedValue(GraphTester.Companion.CONCATENATE)
            tops.add(topKey)
        }
        tester.eval<SkyValue?>( /*keepGoing=*/false, *tops.toTypedArray<SkyKey?>())
        val notifyStart: CountDownLatch = CountDownLatch(1)
        tester.set(leafKey, null)
        if (interrupt) {
            // leaf will wait for an interrupt if desired. We cannot use the usual ChainedFunction
            // because we need to actually throw the interrupt.
            val shouldSleep: AtomicBoolean = AtomicBoolean(true)
            tester
                .getOrCreate(leafKey,  /*markAsModified=*/true)
                .setBuilder(
                    SkyFunction { skyKey, env ->
                        notifyStart.countDown()
                        if (shouldSleep.get()) {
                            // Should be interrupted within 5 seconds.
                            java.lang.Thread.sleep(5000)
                            throw java.lang.AssertionError("leaf was not interrupted")
                        }
                        com.google.devtools.build.skyframe.GraphTester.StringValue("crunchy")
                    })
            tester!!.invalidate()
            val evalThread: TestThread =
                TestThread(
                    TestRunnable {
                        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
                            java.lang.InterruptedException::class.java,
                            org.junit.function.ThrowingRunnable {
                                tester.eval<SkyValue?>( /*keepGoing=*/false,
                                    *tops.toTypedArray<SkyKey?>()
                                )
                            })
                    })
            evalThread.start()
            Truth.assertThat(
                notifyStart.await(
                    com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
            ).isTrue()
            evalThread.interrupt()
            evalThread.joinAndAssertState(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
            // Free leafKey to compute next time.
            shouldSleep.set(false)
        } else {
            // Non-interrupt case. Just throw an error in the child.
            tester.getOrCreate(leafKey,  /*markAsModified=*/true).setHasError(true)
            tester!!.invalidate()
            // The error thrown may non-deterministically bubble up to a parent that has not yet started
            // processing, but has been enqueued for processing.
            tester.eval<SkyValue?>( /*keepGoing=*/false, *tops.toTypedArray<SkyKey?>())
            tester.getOrCreate(leafKey,  /*markAsModified=*/true).setHasError(false)
            tester.set(leafKey, com.google.devtools.build.skyframe.GraphTester.StringValue("crunchy"))
        }
        // lastKey was not touched during the previous build, but its reverse deps on its parents should
        // still be accurate.
        tester.set(lastKey, com.google.devtools.build.skyframe.GraphTester.StringValue("new last"))
        tester!!.invalidate()
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester.eval<SkyValue?>( /*keepGoing=*/false, *tops.toTypedArray<SkyKey?>())
        for (topKey in tops) {
            assertWithMessage(topKey.toString())
                .that(result.get(topKey).getValue())
                .isEqualTo("crunchynew last")
        }
    }

    /**
     * Regression test: make sure that if an evaluation fails before a dirty value starts evaluation
     * (in particular, before it is reset), the graph remains consistent.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun manyDirtyValuesClearChildrenOnError() {
        manyDirtyValuesClearChildrenOnFail( /*interrupt=*/false)
    }

    /**
     * Regression test: Make sure that if an evaluation is interrupted before a dirty value starts
     * evaluation (in particular, before it is reset), the graph remains consistent.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun manyDirtyValuesClearChildrenOnInterrupt() {
        manyDirtyValuesClearChildrenOnFail( /*interrupt=*/true)
    }

    private fun makeTestKey(node0: SkyKey?): SkyKey? {
        var key: SkyKey? = null
        // Create a long chain of nodes. Most of them will not actually be dirtied, but the last one to
        // be dirtied will enqueue its parent for dirtying, so it will be in the queue for the next run.
        for (i in 0..<TEST_NODE_COUNT) {
            key = if (i == 0) node0 else GraphTester.Companion.skyKey("node" + i)
            if (i > 1) {
                tester.getOrCreate(key).addDependency("node" + (i - 1)).setComputedValue(GraphTester.Companion.COPY)
            } else if (i == 1) {
                tester.getOrCreate(key).addDependency(node0).setComputedValue(GraphTester.Companion.COPY)
            } else {
                tester.set(key, com.google.devtools.build.skyframe.GraphTester.StringValue("node0"))
            }
        }
        return key
    }

    /**
     * Regression test for case where the user requests that we delete nodes that are already in the
     * queue to be dirtied. We should handle that gracefully and not complain.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deletingDirtyNodes() {
        val node0: SkyKey = GraphTester.Companion.nonHermeticKey("node0")
        val key: SkyKey? = makeTestKey(node0)
        // Seed the graph.
        Truth.assertThat(
            (tester.evalAndGet( /*keepGoing=*/false,
                key
            ) as com.google.devtools.build.skyframe.GraphTester.StringValue).getValue()
        )
            .isEqualTo("node0")
        // Start the dirtying process.
        tester.set(node0, com.google.devtools.build.skyframe.GraphTester.StringValue("new"))
        tester!!.invalidate()

        // Interrupt current thread on a next invalidate call
        val thread: java.lang.Thread = java.lang.Thread.currentThread()
        tester.progressReceiver.setNextInvalidationCallback(java.lang.Runnable { thread.interrupt() })

        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { tester!!.eval<SkyValue?>( /*keepGoing=*/false, key) })

        // Cleanup + paranoid check
        tester.progressReceiver.setNextInvalidationCallback(null)
        // Now delete all the nodes. The node that was going to be dirtied is also deleted, which we
        // should handle.
        tester.evaluator.delete(com.google.common.base.Predicates.alwaysTrue<T?>())
        Truth.assertThat(
            (tester.evalAndGet( /*keepGoing=*/false,
                key
            ) as com.google.devtools.build.skyframe.GraphTester.StringValue).getValue()
        )
            .isEqualTo("new")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun changePruning() {
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        val mid: SkyKey = GraphTester.Companion.skyKey("mid")
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        tester.getOrCreate(top).addDependency(mid).setComputedValue(GraphTester.Companion.COPY)
        tester.getOrCreate(mid).addDependency(leaf).setComputedValue(GraphTester.Companion.COPY)
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
        var topValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(topValue.getValue()).isEqualTo("leafy")
        // Mark leaf changed, but don't actually change it.
        tester.getOrCreate(leaf,  /* markAsModified= */true)
        // mid will give an error if re-evaluated, but it shouldn't be because it is not marked changed,
        // and its dirty child will evaluate to the same element.
        tester.getOrCreate(mid,  /* markAsModified= */false).setHasError(true)
        tester!!.invalidate()
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, top)
        assertThat(result.hasError()).isFalse()
        topValue = result.get(top)
        Truth.assertThat(topValue.getValue()).isEqualTo("leafy")
        Truth.assertThat(tester!!.dirtyKeys).isEmpty()
        Truth.assertThat(tester!!.deletedKeys).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun changePruningWithDoneValue() {
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        val mid: SkyKey = GraphTester.Companion.skyKey("mid")
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        val suffix: SkyKey = GraphTester.Companion.skyKey("suffix")
        val suffixValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("suffix")
        tester.set(suffix, suffixValue)
        tester.getOrCreate(top).addDependency(mid).addDependency(suffix)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(mid).addDependency(leaf).addDependency(suffix)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        val leafyValue: SkyValue = com.google.devtools.build.skyframe.GraphTester.StringValue("leafy")
        tester.set(leaf, leafyValue)
        var value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("leafysuffixsuffix")
        // Mark leaf changed, but don't actually change it.
        tester.getOrCreate(leaf,  /* markAsModified= */true)
        // mid will give an error if re-evaluated, but it shouldn't be because it is not marked changed,
        // and its dirty child will evaluate to the same element.
        tester.getOrCreate(mid,  /*markAsModified=*/false).setHasError(true)
        tester!!.invalidate()
        value =
            tester.evalAndGet( /*keepGoing=*/false, leaf) as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("leafy")
        Truth.assertThat(tester!!.dirtyKeys).containsExactly(mid, top)
        Truth.assertThat(tester!!.deletedKeys).isEmpty()
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, top)
        assertWithMessage(result.toString()).that(result.hasError()).isFalse()
        value = result.get(top)
        Truth.assertThat(value.getValue()).isEqualTo("leafysuffixsuffix")
        Truth.assertThat(tester!!.dirtyKeys).isEmpty()
        Truth.assertThat(tester!!.deletedKeys).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun changePruningAfterParentPrunes() {
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
        // When top depends on leaf, but always returns the same value,
        val fixedTopValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("top")
        val topEvaluated: AtomicBoolean = AtomicBoolean(false)
        tester
            .getOrCreate(top)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    topEvaluated.set(true)
                    if (env.getValue(leaf) == null) null else fixedTopValue
                })
        // And top is evaluated,
        val topValue: com.google.devtools.build.skyframe.GraphTester.StringValue? =
            tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue?
        // Then top's value is as expected,
        Truth.assertThat(topValue).isEqualTo(fixedTopValue)
        // And top was actually evaluated.
        Truth.assertThat(topEvaluated.get()).isTrue()
        // When leaf is changed,
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("crunchy"))
        tester!!.invalidate()
        topEvaluated.set(false)
        // And top is evaluated,
        val topValue2: com.google.devtools.build.skyframe.GraphTester.StringValue? =
            tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue?
        // Then top's value is as expected,
        Truth.assertThat(topValue2).isEqualTo(fixedTopValue)
        // And top was actually evaluated.
        Truth.assertThat(topEvaluated.get()).isTrue()
        // When leaf is invalidated but not actually changed,
        tester.getOrCreate(leaf,  /*markAsModified=*/true)
        tester!!.invalidate()
        topEvaluated.set(false)
        // And top is evaluated,
        val topValue3: com.google.devtools.build.skyframe.GraphTester.StringValue? =
            tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue?
        // Then top's value is as expected,
        Truth.assertThat(topValue3).isEqualTo(fixedTopValue)
        // And top was *not* actually evaluated, because change pruning cut off evaluation.
        Truth.assertThat(topEvaluated.get()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun changePruningFromOtherNodeAfterParentPrunes() {
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        val other: SkyKey = GraphTester.Companion.nonHermeticKey("other")
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
        tester.set(other, com.google.devtools.build.skyframe.GraphTester.StringValue("other"))
        // When top depends on leaf and other, but always returns the same value,
        val fixedTopValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("top")
        val topEvaluated: AtomicBoolean = AtomicBoolean(false)
        tester
            .getOrCreate(top)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    topEvaluated.set(true)
                    if (env.getValue(other) == null || env.getValue(leaf) == null)
                        null
                    else
                        fixedTopValue
                })
        // And top is evaluated,
        val topValue: com.google.devtools.build.skyframe.GraphTester.StringValue? =
            tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue?
        // Then top's value is as expected,
        Truth.assertThat(topValue).isEqualTo(fixedTopValue)
        // And top was actually evaluated.
        Truth.assertThat(topEvaluated.get()).isTrue()
        // When leaf is changed,
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("crunchy"))
        tester!!.invalidate()
        topEvaluated.set(false)
        // And top is evaluated,
        val topValue2: com.google.devtools.build.skyframe.GraphTester.StringValue? =
            tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue?
        // Then top's value is as expected,
        Truth.assertThat(topValue2).isEqualTo(fixedTopValue)
        // And top was actually evaluated.
        Truth.assertThat(topEvaluated.get()).isTrue()
        // When other is invalidated but not actually changed,
        tester.getOrCreate(other,  /*markAsModified=*/true)
        tester!!.invalidate()
        topEvaluated.set(false)
        // And top is evaluated,
        val topValue3: com.google.devtools.build.skyframe.GraphTester.StringValue? =
            tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue?
        // Then top's value is as expected,
        Truth.assertThat(topValue3).isEqualTo(fixedTopValue)
        // And top was *not* actually evaluated, because change pruning cut off evaluation.
        Truth.assertThat(topEvaluated.get()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun changedChildChangesDepOfParent() {
        val buildFile: SkyKey = GraphTester.Companion.nonHermeticKey("buildFile")
        val authorDrink: ValueComputer =
            ValueComputer { deps: MutableMap<SkyKey?, SkyValue?>?, env: SkyFunction.Environment? ->
                val author: String =
                    (deps!!.get(buildFile) as com.google.devtools.build.skyframe.GraphTester.StringValue).getValue()
                val beverage: com.google.devtools.build.skyframe.GraphTester.StringValue?
                when (author) {
                    "hemingway" -> beverage =
                        env.getValue(GraphTester.Companion.skyKey("absinthe")) as com.google.devtools.build.skyframe.GraphTester.StringValue?

                    "joyce" -> beverage =
                        env.getValue(GraphTester.Companion.skyKey("whiskey")) as com.google.devtools.build.skyframe.GraphTester.StringValue?

                    else -> throw java.lang.IllegalStateException(author)
                }
                if (beverage == null) {
                    return@ValueComputer null
                }
                com.google.devtools.build.skyframe.GraphTester.StringValue(author + " drank " + beverage.getValue())
            }

        tester.set(buildFile, com.google.devtools.build.skyframe.GraphTester.StringValue("hemingway"))
        val absinthe: SkyKey = GraphTester.Companion.skyKey("absinthe")
        tester.set(absinthe, com.google.devtools.build.skyframe.GraphTester.StringValue("absinthe"))
        val whiskey: SkyKey = GraphTester.Companion.skyKey("whiskey")
        tester.set(whiskey, com.google.devtools.build.skyframe.GraphTester.StringValue("whiskey"))
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        tester.getOrCreate(top).addDependency(buildFile).setComputedValue(authorDrink)
        var topValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(topValue.getValue()).isEqualTo("hemingway drank absinthe")
        tester.set(buildFile, com.google.devtools.build.skyframe.GraphTester.StringValue("joyce"))
        // Don't evaluate absinthe successfully anymore.
        tester.getOrCreate(absinthe,  /*markAsModified=*/false).setHasError(true)
        tester!!.invalidate()
        topValue = tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(topValue.getValue()).isEqualTo("joyce drank whiskey")
        Truth.assertThat(tester!!.dirtyKeys).containsExactly(buildFile, top)
        Truth.assertThat(tester!!.deletedKeys).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dirtyDepIgnoresChildren() {
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        val mid: SkyKey = GraphTester.Companion.skyKey("mid")
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        tester.set(mid, com.google.devtools.build.skyframe.GraphTester.StringValue("ignore"))
        tester.getOrCreate(top).addDependency(mid).setComputedValue(GraphTester.Companion.COPY)
        tester.getOrCreate(mid).addDependency(leaf)
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
        var topValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(topValue.getValue()).isEqualTo("ignore")
        Truth.assertThat(tester!!.dirtyKeys).isEmpty()
        Truth.assertThat(tester!!.deletedKeys).isEmpty()
        // Change leaf.
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("crunchy"))
        tester!!.invalidate()
        topValue = tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(topValue.getValue()).isEqualTo("ignore")
        Truth.assertThat(tester!!.dirtyKeys).containsExactly(leaf)
        Truth.assertThat(tester!!.deletedKeys).isEmpty()
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("smushy"))
        tester!!.invalidate()
        topValue = tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(topValue.getValue()).isEqualTo("ignore")
        Truth.assertThat(tester!!.dirtyKeys).containsExactly(leaf)
        Truth.assertThat(tester!!.deletedKeys).isEmpty()
    }

    /**
     * Utility function to induce a graph clean of whatever value is requested, by trying to build
     * this value and interrupting the build as soon as this value's function evaluation starts.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun failBuildAndRemoveValue(value: SkyKey?) {
        tester.set(value, null)
        // Evaluator will think leaf was interrupted because it threw, so it will be cleaned from graph.
        tester.getOrCreate(value,  /* markAsModified= */true).setBuilder(INTERRUPT_BUILDER)
        tester!!.invalidate()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { tester!!.eval<SkyValue?>( /* keepGoing= */false, value) })
        tester.getOrCreate(value,  /* markAsModified= */false).setBuilder(null)
    }

    /**
     * Make sure that when a dirty value is building, the fact that a child may no longer exist in the
     * graph doesn't cause problems.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dirtyBuildAfterFailedBuild() {
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        tester.getOrCreate(top).addDependency(leaf).setComputedValue(GraphTester.Companion.COPY)
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
        var topValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(topValue.getValue()).isEqualTo("leafy")
        Truth.assertThat(tester!!.dirtyKeys).isEmpty()
        Truth.assertThat(tester!!.deletedKeys).isEmpty()
        failBuildAndRemoveValue(leaf)
        // Leaf should no longer exist in the graph. Check that this doesn't cause problems.
        tester.set(leaf, null)
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("crunchy"))
        tester!!.invalidate()
        topValue = tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(topValue.getValue()).isEqualTo("crunchy")
    }

    /**
     * Regression test: error when clearing reverse deps on dirty value about to be rebuilt, because
     * child values were deleted and recreated in interim, forgetting they had reverse dep on dirty
     * value in the first place.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun changedBuildAfterFailedThenSuccessfulBuild() {
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        val top: SkyKey = GraphTester.Companion.nonHermeticKey("top")
        tester.getOrCreate(top).addDependency(leaf).setComputedValue(GraphTester.Companion.COPY)
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
        var topValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester.evalAndGet( /* keepGoing= */false, top) as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(topValue.getValue()).isEqualTo("leafy")
        Truth.assertThat(tester!!.dirtyKeys).isEmpty()
        Truth.assertThat(tester!!.deletedKeys).isEmpty()
        failBuildAndRemoveValue(leaf)
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("crunchy"))
        tester!!.invalidate()
        tester!!.eval<SkyValue?>( /* keepGoing= */false, leaf)
        // Leaf no longer has reverse dep on top. Check that this doesn't cause problems, even if the
        // top value is evaluated unconditionally.
        tester.getOrCreate(top,  /*markAsModified=*/true)
        tester!!.invalidate()
        topValue =
            tester.evalAndGet( /*keepGoing=*/false, top) as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(topValue.getValue()).isEqualTo("crunchy")
    }

    /**
     * Regression test: child value that has been deleted since it and its parent were marked dirty no
     * longer knows it has a reverse dep on its parent.
     * 
     * 
     * Start with:
     * 
     * <pre>
     * top0  ... top1000
     * \  | /
     * leaf
    </pre> * 
     * 
     * Then fail to build leaf. Now the entry for leaf should have no "memory" that it was ever
     * depended on by tops. Now build tops, but fail again.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun manyDirtyValuesClearChildrenOnSecondFail() {
        val leafKey: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        tester.set(leafKey, com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
        val lastKey: SkyKey = GraphTester.Companion.skyKey("last")
        tester.set(lastKey, com.google.devtools.build.skyframe.GraphTester.StringValue("last"))
        val tops: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()
        // Request far more top-level values than there are threads, so some of them will block until
        // the leaf child is enqueued for processing.
        for (i in 0..9999) {
            val topKey: SkyKey = GraphTester.Companion.skyKey("top" + i)
            tester
                .getOrCreate(topKey)
                .addDependency(leafKey)
                .addDependency(lastKey)
                .setComputedValue(GraphTester.Companion.CONCATENATE)
            tops.add(topKey)
        }
        tester.eval<SkyValue?>( /*keepGoing=*/false, *tops.toTypedArray<SkyKey?>())
        failBuildAndRemoveValue(leafKey)
        // Request the tops. Since leaf was deleted from the graph last build, it no longer knows that
        // its parents depend on it. When leaf throws, at least one of its parents (hopefully) will not
        // have re-informed leaf that the parent depends on it, exposing the bug, since the parent
        // should then not try to clean the reverse dep from leaf.
        tester.set(leafKey, null)
        // Evaluator will think leaf was interrupted because it threw, so it will be cleaned from graph.
        tester.getOrCreate(leafKey,  /*markAsModified=*/true).setBuilder(INTERRUPT_BUILDER)
        tester!!.invalidate()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable {
                tester.eval<SkyValue?>( /*keepGoing=*/false,
                    *tops.toTypedArray<SkyKey?>()
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failedDirtyBuild() {
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        tester
            .getOrCreate(top)
            .addErrorDependency(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("recover"))
            .setComputedValue(GraphTester.Companion.COPY)
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
        val topValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(topValue.getValue()).isEqualTo("leafy")
        Truth.assertThat(tester!!.dirtyKeys).isEmpty()
        Truth.assertThat(tester!!.deletedKeys).isEmpty()
        // Change leaf.
        tester.getOrCreate(leaf,  /* markAsModified= */true).setHasError(true)
        tester.getOrCreate(top,  /* markAsModified= */false).setHasError(true)
        tester!!.invalidate()
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /* keepGoing= */false, top)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(top)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failedDirtyBuildInBuilder() {
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        val secondError: SkyKey = GraphTester.Companion.nonHermeticKey("secondError")
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        tester
            .getOrCreate(top)
            .addDependency(leaf)
            .addErrorDependency(secondError, com.google.devtools.build.skyframe.GraphTester.StringValue("recover"))
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.set(secondError, com.google.devtools.build.skyframe.GraphTester.StringValue("secondError"))
            .addDependency(leaf)
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("leafy"))
        val topValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            tester!!.evalAndGet("top") as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(topValue.getValue()).isEqualTo("leafysecondError")
        Truth.assertThat(tester!!.dirtyKeys).isEmpty()
        Truth.assertThat(tester!!.deletedKeys).isEmpty()
        // Invalidate leaf.
        tester.getOrCreate(leaf,  /*markAsModified=*/true)
        tester.set(leaf, com.google.devtools.build.skyframe.GraphTester.StringValue("crunchy"))
        tester.getOrCreate(secondError,  /*markAsModified=*/true).setHasError(true)
        tester.getOrCreate(top,  /*markAsModified=*/false).setHasError(true)
        tester!!.invalidate()
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, top)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(top)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dirtyErrorTransienceValue() {
        initializeTester()
        val error: SkyKey = GraphTester.Companion.skyKey("error")
        tester.getOrCreate(error).setHasError(true)
        assertThat(tester.evalAndGetError( /*keepGoing=*/true, error)).isNotNull()
        tester!!.invalidateTransientErrors()
        val secondError: SkyKey = GraphTester.Companion.skyKey("secondError")
        tester.getOrCreate(secondError).setHasError(true)
        // secondError declares a new dependence on ErrorTransienceValue, but not until it has already
        // thrown an error.
        assertThat(tester.evalAndGetError( /*keepGoing=*/true, secondError)).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dirtyDependsOnErrorTurningGood() {
        val error: SkyKey = GraphTester.Companion.nonHermeticKey("error")
        tester.getOrCreate(error).setHasError(true)
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        tester.getOrCreate(topKey).addDependency(error).setComputedValue(GraphTester.Companion.COPY)
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(topKey)
        tester.getOrCreate(error).setHasError(false)
        val `val`: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("reformed")
        tester.set(error, `val`)
        tester!!.invalidate()
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(topKey)
            .isEqualTo(`val`)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasNoError()
    }

    /** Regression test for crash bug.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dirtyWithOwnErrorDependsOnTransientErrorTurningGood() {
        val error: SkyKey = GraphTester.Companion.nonHermeticKey("error")
        tester.getOrCreate(error).setHasTransientError(true)
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        val errorFunction: SkyFunction =
            SkyFunction { skyKey, env ->
                try {
                    return@SkyFunction env.getValueOrThrow(error, SomeErrorException::class.java)
                } catch (e: SomeErrorException) {
                    throw GenericFunctionException(e, Transience.PERSISTENT)
                }
            }
        tester.getOrCreate(topKey).setBuilder(errorFunction)
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey)
        tester!!.invalidateTransientErrors()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(topKey)
        tester.getOrCreate(error).setHasTransientError(false)
        val reformed: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("reformed")
        tester.set(error, reformed)
        tester
            .getOrCreate(topKey,  /*markAsModified=*/false)
            .setBuilder(null)
            .addDependency(error)
            .setComputedValue(GraphTester.Companion.COPY)
        tester!!.invalidate()
        tester!!.invalidateTransientErrors()
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(topKey)
            .isEqualTo(reformed)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasNoError()
    }

    /**
     * Make sure that when an error is thrown, it is given for handling only to parents that have
     * already registered a dependence on the value that threw the error.
     * 
     * <pre>
     * topBubbleKey  topErrorFirstKey
     * |       \    /
     * midKey  errorKey
     * |
     * slowKey
    </pre> * 
     * 
     * On the second build, errorKey throws, and the threadpool aborts before midKey finishes.
     * topBubbleKey therefore has not yet requested errorKey this build. If errorKey bubbles up to it,
     * topBubbleKey must be able to handle that. (The evaluator can deal with this either by not
     * allowing errorKey to bubble up to topBubbleKey, or by dealing with that case.)
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorOnlyBubblesToRequestingParents() {
        // We need control over the order of reverse deps, so use a deterministic graph.
        makeGraphDeterministic()
        val errorKey: SkyKey = GraphTester.Companion.nonHermeticKey("error")
        tester.set(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("biding time"))
        val slowKey: SkyKey = GraphTester.Companion.nonHermeticKey("slow")
        tester.set(slowKey, com.google.devtools.build.skyframe.GraphTester.StringValue("slow"))
        val midKey: SkyKey = GraphTester.Companion.skyKey("mid")
        tester.getOrCreate(midKey).addDependency(slowKey).setComputedValue(GraphTester.Companion.COPY)
        val topErrorFirstKey: SkyKey = GraphTester.Companion.skyKey("2nd top alphabetically")
        tester.getOrCreate(topErrorFirstKey).addDependency(errorKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        val topBubbleKey: SkyKey = GraphTester.Companion.skyKey("1st top alphabetically")
        tester
            .getOrCreate(topBubbleKey)
            .addDependency(midKey)
            .addDependency(errorKey)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        // First error-free evaluation, to put all values in graph.
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, topErrorFirstKey, topBubbleKey)
        assertThat(result.get(topErrorFirstKey).getValue()).isEqualTo("biding time")
        assertThat(result.get(topBubbleKey).getValue()).isEqualTo("slowbiding time")
        // Set up timing of child values: slowKey waits to finish until errorKey has thrown an
        // exception that has been caught by the threadpool.
        tester.set(slowKey, null)
        val errorFinish: CountDownLatch = CountDownLatch(1)
        tester.set(errorKey, null)
        tester
            .getOrCreate(errorKey)
            .setBuilder(
                ChainedFunction( /*notifyStart=*/
                    null,  /*waitToFinish=*/
                    null,  /*notifyFinish=*/
                    errorFinish,  /*waitForException=*/
                    false,  /*value=*/
                    null,  /*deps=*/
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        tester
            .getOrCreate(slowKey)
            .setBuilder(
                ChainedFunction( /*notifyStart=*/
                    null,  /*waitToFinish=*/
                    errorFinish,  /*notifyFinish=*/
                    null,  /*waitForException=*/
                    true,
                    com.google.devtools.build.skyframe.GraphTester.StringValue("leaf2"),  /*deps=*/
                    com.google.common.collect.ImmutableList.of<SkyKey?>()
                )
            )
        tester!!.invalidate()
        // errorKey finishes, written to graph -> slowKey maybe starts+finishes & (Visitor aborts)
        // -> some top key builds.
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/false, topErrorFirstKey, topBubbleKey)
        assertThat(result.hasError()).isTrue()
        assertWithMessage(result.toString()).that(result.getError(topErrorFirstKey)).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dirtyWithRecoveryErrorDependsOnErrorTurningGood() {
        val error: SkyKey = GraphTester.Companion.nonHermeticKey("error")
        tester.getOrCreate(error).setHasError(true)
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        val recoveryErrorFunction: SkyFunction =
            SkyFunction { skyKey, env ->
                try {
                    env.getValueOrThrow(error, SomeErrorException::class.java)
                } catch (e: SomeErrorException) {
                    throw GenericFunctionException(e, Transience.PERSISTENT)
                }
                null
            }
        tester.getOrCreate(topKey).setBuilder(recoveryErrorFunction)
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(topKey)
        tester.getOrCreate(error).setHasError(false)
        val reformed: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("reformed")
        tester.set(error, reformed)
        tester.getOrCreate(topKey).setBuilder(null).addDependency(error).setComputedValue(GraphTester.Companion.COPY)
        tester!!.invalidate()
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasEntryThat(topKey)
            .isEqualTo(reformed)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasNoError()
    }

    /**
     * Similar to [ParallelEvaluatorTest.errorTwoLevelsDeep], except here we request multiple
     * toplevel values.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorPropagationToTopLevelValues() {
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        val midKey: SkyKey = GraphTester.Companion.skyKey("mid")
        val badKey: SkyKey = GraphTester.Companion.skyKey("bad")
        tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(midKey).addDependency(badKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(badKey).setHasError(true)
        var result: EvaluationResult<SkyValue?> = tester!!.eval<SkyValue?>( /* keepGoing= */false, topKey, midKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(midKey)
        // Do it again with keepGoing.  We should also see an error for the top key this time.
        result = tester!!.eval<SkyValue?>( /* keepGoing= */true, topKey, midKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(midKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(topKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun breakWithInterruptibleErrorDep() {
        val errorKey: SkyKey = GraphTester.Companion.skyKey("my_error_value")
        tester.getOrCreate(errorKey).setHasError(true)
        val parentKey: SkyKey = GraphTester.Companion.skyKey("parent")
        tester
            .getOrCreate(parentKey)
            .addErrorDependency(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("recovered"))
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        // When the error value throws, the propagation will cause an interrupted exception in parent.
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, parentKey)
        assertThat(result.keyNames()).isEmpty()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorMapThat().hasSize(1)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorMapThat()
            .containsKey(parentKey)
        Truth.assertThat(java.lang.Thread.interrupted()).isFalse()
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/true, parentKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasNoError()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasEntryThat(parentKey)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("recovered"))
    }

    /**
     * Regression test: "clearing incomplete values on --keep_going build is racy". Tests that if a
     * value is requested on the first (non-keep-going) build and its child throws an error, when the
     * second (keep-going) build runs, there is not a race that keeps it as a reverse dep of its
     * children.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun raceClearingIncompleteValues() {
        // Make sure top is enqueued before mid, to avoid a deadlock.
        val topKey: SkyKey = GraphTester.Companion.skyKey("aatop")
        val midKey: SkyKey = GraphTester.Companion.skyKey("zzmid")
        val badKey: SkyKey = GraphTester.Companion.skyKey("bad")
        val waitForSecondCall: AtomicBoolean = AtomicBoolean(false)
        val otherThreadWinning: CountDownLatch = CountDownLatch(1)
        val firstThread: AtomicReference<java.lang.Thread?> = AtomicReference<java.lang.Thread?>()
        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (!waitForSecondCall.get()) {
                    return@injectGraphListenerForTesting
                }
                if (key.equals(midKey)) {
                    if (type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.CREATE_IF_ABSENT) {
                        // The first thread to create midKey will not be the first thread to add a reverse dep
                        // to it.
                        firstThread.compareAndSet(null, java.lang.Thread.currentThread())
                        return@injectGraphListenerForTesting
                    }
                    if (type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.ADD_REVERSE_DEP) {
                        if (order == com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE && java.lang.Thread.currentThread() == firstThread.get()) {
                            // If this thread created midKey, block until the other thread adds a dep on it.
                            TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                                otherThreadWinning, "other thread didn't pass this one"
                            )
                        } else if (order == com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER
                            && java.lang.Thread.currentThread() != firstThread.get()
                        ) {
                            // This thread has added a dep. Allow the other thread to proceed.
                            otherThreadWinning.countDown()
                        }
                    }
                }
            },  /* deterministic= */
            true
        )
        tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(midKey).addDependency(badKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(badKey).setHasError(true)
        var result: EvaluationResult<SkyValue?> = tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey, midKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(midKey)
        waitForSecondCall.set(true)
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/true, topKey, midKey)
        Truth.assertThat(firstThread.get()).isNotNull()
        Truth.assertThat(otherThreadWinning.getCount()).isEqualTo(0)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(midKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(topKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun breakWithErrorDep() {
        val errorKey: SkyKey = GraphTester.Companion.skyKey("my_error_value")
        tester.getOrCreate(errorKey).setHasError(true)
        tester.set("after", com.google.devtools.build.skyframe.GraphTester.StringValue("after"))
        val parentKey: SkyKey = GraphTester.Companion.skyKey("parent")
        tester
            .getOrCreate(parentKey)
            .addErrorDependency(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("recovered"))
            .setComputedValue(GraphTester.Companion.CONCATENATE)
            .addDependency("after")
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, parentKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(parentKey)
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/true, parentKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasNoError()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasEntryThat(parentKey)
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("recoveredafter"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun raceConditionWithNoKeepGoingErrors_InflightError() {
        // Given a graph of two nodes, errorKey and otherErrorKey,
        val errorKey: SkyKey = GraphTester.Companion.skyKey("errorKey")
        val otherErrorKey: SkyKey = GraphTester.Companion.skyKey("otherErrorKey")

        val errorCommitted: CountDownLatch = CountDownLatch(1)

        val otherStarted: CountDownLatch = CountDownLatch(1)

        val otherDone: CountDownLatch = CountDownLatch(1)

        val numOtherInvocations: AtomicInteger = AtomicInteger(0)
        val bogusInvocationMessage: AtomicReference<String?> = AtomicReference<String?>(null)
        val nonNullValueMessage: AtomicReference<String?> = AtomicReference<String?>(null)

        tester
            .getOrCreate(errorKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    // Given that errorKey waits for otherErrorKey to begin evaluation before completing
                    // its evaluation,
                    TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                        otherStarted, "otherErrorKey's SkyFunction didn't start in time."
                    )
                    // And given that errorKey throws an error,
                    throw GenericFunctionException(
                        SomeErrorException("error"), Transience.PERSISTENT
                    )
                })
        tester
            .getOrCreate(otherErrorKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    otherStarted.countDown()
                    val invocations: Int = numOtherInvocations.incrementAndGet()
                    // And given that otherErrorKey waits for errorKey's error to be committed before
                    // trying to get errorKey's value,
                    TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                        errorCommitted, "errorKey's error didn't get committed to the graph in time"
                    )
                    try {
                        val value: SkyValue? = env.getValueOrThrow(errorKey, SomeErrorException::class.java)
                        if (value != null) {
                            nonNullValueMessage.set("bogus non-null value " + value)
                        }
                        if (invocations != 1) {
                            bogusInvocationMessage.set("bogus invocation count: " + invocations)
                        }
                        otherDone.countDown()
                        // And given that otherErrorKey throws an error,
                        throw GenericFunctionException(
                            SomeErrorException("other"), Transience.PERSISTENT
                        )
                    } catch (e: SomeErrorException) {
                        org.junit.Assert.fail()
                        return@setBuilder null
                    }
                })
        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (key.equals(errorKey) && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER) {
                    errorCommitted.countDown()
                    TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                        otherDone, "otherErrorKey's SkyFunction didn't finish in time."
                    )
                }
            },  /*deterministic=*/
            false
        )

        // When the graph is evaluated in noKeepGoing mode,
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, errorKey, otherErrorKey)

        // Then the result reports that an error occurred,
        assertThat(result.hasError()).isTrue()

        // And no value is committed for otherErrorKey,
        assertThat(tester.evaluator.getExistingErrorForTesting(otherErrorKey)).isNull()
        assertThat(tester.evaluator.getExistingValue(otherErrorKey)).isNull()

        // And no value was committed for errorKey,
        Truth.assertWithMessage(nonNullValueMessage.get()).that(nonNullValueMessage.get()).isNull()

        // And the SkyFunction for otherErrorKey was evaluated exactly once.
        Truth.assertThat(numOtherInvocations.get()).isEqualTo(1)
        Truth.assertWithMessage(bogusInvocationMessage.get()).that(bogusInvocationMessage.get()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun absentParent() {
        val errorKey: SkyKey = GraphTester.Companion.nonHermeticKey("my_error_value")
        tester.set(errorKey, com.google.devtools.build.skyframe.GraphTester.StringValue("biding time"))
        val absentParentKey: SkyKey = GraphTester.Companion.skyKey("absentParent")
        tester.getOrCreate(absentParentKey).addDependency(errorKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        assertThat(tester.evalAndGet( /*keepGoing=*/false, absentParentKey))
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("biding time"))
        tester.getOrCreate(errorKey,  /*markAsModified=*/true).setHasError(true)
        val newParent: SkyKey = GraphTester.Companion.skyKey("newParent")
        tester.getOrCreate(newParent).addDependency(errorKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester!!.invalidate()
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, newParent)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(newParent)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun notComparableNotPrunedNoEvent() {
        checkNotComparableNotPruned(false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun notComparableNotPrunedEvent() {
        checkNotComparableNotPruned(true)
    }

    @Throws(java.lang.Exception::class)
    private fun checkNotComparableNotPruned(hasEvent: Boolean) {
        val parent: SkyKey = GraphTester.Companion.skyKey("parent")
        val child: SkyKey = GraphTester.Companion.nonHermeticKey("child")
        val notComparableString: NotComparableStringValue = NotComparableStringValue("not comparable")
        if (hasEvent) {
            tester.getOrCreate(child).setConstantValue(notComparableString).setWarning("shmoop")
        } else {
            tester.getOrCreate(child).setConstantValue(notComparableString)
        }
        val parentEvaluated: AtomicInteger = AtomicInteger()
        val `val`: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("some val")
        tester
            .getOrCreate(parent)
            .addDependency(child)
            .setComputedValue(
                ValueComputer { deps: MutableMap<SkyKey?, SkyValue?>?, env: SkyFunction.Environment? ->
                    parentEvaluated.incrementAndGet()
                    `val`
                })
        assertThat(tester.evalAndGet( /* keepGoing= */false, parent)).isEqualTo(`val`)
        Truth.assertThat(parentEvaluated.get()).isEqualTo(1)
        if (hasEvent) {
            assertThatEvents(eventCollector).containsExactly("shmoop")
        } else {
            assertThatEvents(eventCollector).isEmpty()
        }
        eventCollector.clear()

        tester!!.resetPlayedEvents()
        tester.getOrCreate(child,  /*markAsModified=*/true)
        tester!!.invalidate()
        assertThat(tester.evalAndGet( /* keepGoing= */false, parent)).isEqualTo(`val`)
        Truth.assertThat(parentEvaluated.get()).isEqualTo(2)
        if (hasEvent) {
            assertThatEvents(eventCollector).containsExactly("shmoop")
        } else {
            assertThatEvents(eventCollector).isEmpty()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun changePruningWithEvent() {
        val parent: SkyKey = GraphTester.Companion.skyKey("parent")
        val child: SkyKey = GraphTester.Companion.nonHermeticKey("child")
        tester.getOrCreate(child).setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("child"))
            .setWarning("bloop")
        // Restart once because child isn't ready.
        val parentEvaluated: CountDownLatch = CountDownLatch(3)
        val parentVal: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("parent")
        tester
            .getOrCreate(parent)
            .setBuilder(
                ChainedFunction(
                    parentEvaluated,
                    null,
                    null,
                    false,
                    parentVal,
                    com.google.common.collect.ImmutableList.of<SkyKey?>(child)
                )
            )
        assertThat(tester.evalAndGet( /* keepGoing= */false, parent)).isEqualTo(parentVal)
        Truth.assertThat(parentEvaluated.getCount()).isEqualTo(1)
        assertThatEvents(eventCollector).containsExactly("bloop")
        eventCollector.clear()
        tester!!.resetPlayedEvents()
        tester.getOrCreate(child,  /*markAsModified=*/true)
        tester!!.invalidate()
        assertThat(tester.evalAndGet( /*keepGoing=*/false, parent)).isEqualTo(parentVal)
        assertThatEvents(eventCollector).containsExactly("bloop")
        Truth.assertThat(parentEvaluated.getCount()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun changePruningWithIntermittentEvent() {
        val parentEvent = "parent_event"
        val waitEvent = "wait_event"
        val childEvent = "child_event"
        val wait: SkyKey = GraphTester.Companion.skyKey("wait_key")
        val parent: SkyKey = GraphTester.Companion.skyKey("parent_key")
        val child: SkyKey = GraphTester.Companion.nonHermeticKey("child_key")
        val parentStringValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("parent_value")
        val waitStringValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("wait_value")
        val parentEvaluated: CountDownLatch = CountDownLatch(2)

        reporter =
            object : DelegatingEventHandler(reporter) {
                override fun handle(e: com.google.devtools.build.lib.events.Event) {
                    super.handle(e)
                    // Release the CountDownLatch every time the parent node fires the event
                    if (e.getMessage() == parentEvent) {
                        parentEvaluated.countDown()
                    }
                }
            }

        tester
            .getOrCreate(wait)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    // Wait for the parent and child actions to complete before computing wait node
                    parentEvaluated.await(
                        com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                    )
                    assertThatEvents(eventCollector).containsExactly(childEvent, parentEvent)

                    env.getListener().handle(com.google.devtools.build.lib.events.Event.progress(waitEvent))
                    waitStringValue
                })
        tester
            .getOrCreate(child)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("child_value"))
            .setWarning(childEvent)
        tester
            .getOrCreate(parent)
            .addDependency(child)
            .setConstantValue(parentStringValue)
            .setErrorEvent(parentEvent)

        assertThat(tester.evalAndGet( /*keepGoing=*/false, parent)).isEqualTo(parentStringValue)
        assertThatEvents(eventCollector).containsExactly(childEvent, parentEvent)
        Truth.assertThat(parentEvaluated.getCount()).isEqualTo(1)

        // Reset the event collector and mark the child as modified without actually changing values
        eventCollector.clear()
        tester!!.resetPlayedEvents()
        tester.getOrCreate(child,  /*markAsModified=*/true)
        tester!!.invalidate()

        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>(false, parent, wait)
        assertThat(result.values()).containsExactly(parentStringValue, waitStringValue)

        // These assertions are to check that all events fired at the end of evaluation.
        Truth.assertThat(parentEvaluated.getCount()).isEqualTo(0)
        assertThatEvents(eventCollector).containsExactly(childEvent, parentEvent, waitEvent)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun depEventPredicate() {
        val parent: SkyKey = GraphTester.Companion.skyKey("parent")
        val excludedDep: SkyKey = GraphTester.Companion.skyKey("excludedDep")
        val includedDep: SkyKey = GraphTester.Companion.skyKey("includedDep")
        tester!!.setEventFilter(
            object : EventFilter() {
                public override fun storeEvents(): Boolean {
                    return true
                }

                public override fun shouldPropagate(depKey: SkyKey, primaryKey: SkyKey): Boolean {
                    return !primaryKey.equals(parent) || depKey.equals(includedDep)
                }
            })
        tester!!.initialize()
        tester
            .getOrCreate(parent)
            .addDependency(excludedDep)
            .addDependency(includedDep)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        tester
            .getOrCreate(excludedDep)
            .setErrorEvent("excludedDep error message")
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("excludedDep"))
        tester
            .getOrCreate(includedDep)
            .setErrorEvent("includedDep error message")
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("includedDep"))
        tester!!.eval<SkyValue?>( /* keepGoing= */false, includedDep, excludedDep)
        assertThatEvents(eventCollector)
            .containsExactly("excludedDep error message", "includedDep error message")
        eventCollector.clear()
        emittedEventState.clear()
        tester!!.eval<SkyValue?>( /* keepGoing= */true, parent)
        assertThatEvents(eventCollector).containsExactly("includedDep error message")
        assertThat(
            ValueWithMetadata.getEvents(
                tester
                    .evaluator
                    .getExistingEntryAtCurrentlyEvaluatingVersion(parent)
                    .getValueMaybeWithMetadata()
            )
                .toList()
        )
            .containsExactly(com.google.devtools.build.lib.events.Event.error("includedDep error message"))
    }

    // Tests that we have a sane implementation of error transience.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorTransienceBug() {
        tester.getOrCreate("key").setHasTransientError(true)
        assertThat(tester!!.evalAndGetError( /*keepGoing=*/true, "key").getException()).isNotNull()
        val value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("hi")
        tester.getOrCreate("key").setHasTransientError(false).setConstantValue(value)
        tester!!.invalidateTransientErrors()
        assertThat(tester!!.evalAndGet("key")).isEqualTo(value)
        // This works because the version of the ValueEntry for the ErrorTransience value is always
        // increased on each InMemoryMemoizingEvaluator#evaluate call. But that's not the only way to
        // implement error transience; another valid implementation would be to unconditionally mark
        // values depending on the ErrorTransience value as being changed (rather than merely dirtied)
        // during invalidation.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transientErrorTurningGoodHasNoError() {
        initializeTester()
        val errorKey: SkyKey = GraphTester.Companion.skyKey("my_error_value")
        tester.getOrCreate(errorKey).setHasTransientError(true)
        var errorInfo: ErrorInfo = tester.evalAndGetError( /*keepGoing=*/true, errorKey)
        assertThat(errorInfo).isNotNull()
        // Re-evaluates to same thing when errors are invalidated
        tester!!.invalidateTransientErrors()
        errorInfo = tester.evalAndGetError( /*keepGoing=*/true, errorKey)
        assertThat(errorInfo).isNotNull()
        val value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("reformed")
        tester
            .getOrCreate(errorKey,  /*markAsModified=*/false)
            .setHasTransientError(false)
            .setConstantValue(value)
        tester!!.invalidateTransientErrors()
        var stringValue: com.google.devtools.build.skyframe.GraphTester.StringValue? = tester.evalAndGet( /*keepGoing=*/
            true,
            errorKey
        ) as com.google.devtools.build.skyframe.GraphTester.StringValue?
        Truth.assertThat(value).isSameInstanceAs(stringValue)
        // Value builder will now throw, but we should never get to it because it isn't dirty.
        tester.getOrCreate(errorKey,  /*markAsModified=*/false).setHasTransientError(true)
        tester!!.invalidateTransientErrors()
        stringValue = tester.evalAndGet( /*keepGoing=*/true,
            errorKey
        ) as com.google.devtools.build.skyframe.GraphTester.StringValue?
        Truth.assertThat(stringValue).isEqualTo(value)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transientErrorTurnsGoodOnSecondTry() {
        val leafKey: SkyKey = GraphTester.Companion.skyKey("leaf")
        val errorKey: SkyKey = GraphTester.Companion.skyKey("error")
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        val value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("val")
        tester.getOrCreate(topKey).addDependency(errorKey).setConstantValue(value)
        tester
            .getOrCreate(errorKey)
            .addDependency(leafKey)
            .setConstantValue(value)
            .setHasTransientError(true)
        tester.getOrCreate(leafKey).setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))
        var errorInfo: ErrorInfo = tester.evalAndGetError( /* keepGoing= */true, topKey)
        assertThat(errorInfo).isNotNull()
        ErrorInfoSubjectFactory.Companion.assertThatErrorInfo(errorInfo).isTransient()
        tester!!.invalidateTransientErrors()
        errorInfo = tester.evalAndGetError( /*keepGoing=*/true, topKey)
        assertThat(errorInfo).isNotNull()
        ErrorInfoSubjectFactory.Companion.assertThatErrorInfo(errorInfo).isTransient()
        tester!!.invalidateTransientErrors()
        tester.getOrCreate(errorKey,  /*markAsModified=*/false).setHasTransientError(false)
        assertThat(tester.evalAndGet( /*keepGoing=*/true, topKey)).isEqualTo(value)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deleteInvalidatedValue() {
        val top: SkyKey = GraphTester.Companion.skyKey("top")
        val toDelete: SkyKey = GraphTester.Companion.nonHermeticKey("toDelete")
        // Must be a concatenation -- COPY doesn't actually copy.
        tester.getOrCreate(top).addDependency(toDelete).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.set(toDelete, com.google.devtools.build.skyframe.GraphTester.StringValue("toDelete"))
        var value: SkyValue? = tester!!.evalAndGet("top")
        val forceInvalidation: SkyKey = GraphTester.Companion.skyKey("forceInvalidation")
        tester.set(forceInvalidation, com.google.devtools.build.skyframe.GraphTester.StringValue("forceInvalidation"))
        tester.getOrCreate(toDelete,  /* markAsModified= */true)
        tester!!.invalidate()
        tester!!.eval<SkyValue?>( /* keepGoing= */false, forceInvalidation)
        tester!!.delete("toDelete")
        val ref: java.lang.ref.WeakReference<SkyValue?> = java.lang.ref.WeakReference<SkyValue?>(value)
        value = null
        tester!!.eval<SkyValue?>( /*keepGoing=*/false, forceInvalidation)
        tester!!.invalidate() // So that invalidation receiver doesn't hang on to reference.
        GcFinalization.awaitClear(ref)
    }

    /**
     * General stress/fuzz test of the evaluator with failure. Construct a large graph, and then throw
     * exceptions during building at various points.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun twoRailLeftRightDependenciesWithFailure() {
        initializeTester()
        val leftValues: Array<SkyKey?> = arrayOfNulls<SkyKey>(TEST_NODE_COUNT)
        val rightValues: Array<SkyKey?> = arrayOfNulls<SkyKey>(TEST_NODE_COUNT)
        for (i in 0..<TEST_NODE_COUNT) {
            leftValues[i] = GraphTester.Companion.nonHermeticKey("left-" + i)
            rightValues[i] = GraphTester.Companion.skyKey("right-" + i)
            if (i == 0) {
                tester.getOrCreate(leftValues[i]).addDependency("leaf").setComputedValue(GraphTester.Companion.COPY)
                tester.getOrCreate(rightValues[i]).addDependency("leaf").setComputedValue(GraphTester.Companion.COPY)
            } else {
                tester
                    .getOrCreate(leftValues[i])
                    .addDependency(leftValues[i - 1])
                    .addDependency(rightValues[i - 1])
                    .setComputedValue(PassThroughSelected(leftValues[i - 1]))
                tester
                    .getOrCreate(rightValues[i])
                    .addDependency(leftValues[i - 1])
                    .addDependency(rightValues[i - 1])
                    .setComputedValue(PassThroughSelected(rightValues[i - 1]))
            }
        }
        tester.set("leaf", com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))

        val lastLeft: SkyKey = GraphTester.Companion.nonHermeticKey("left-" + (TEST_NODE_COUNT - 1))
        val lastRight: SkyKey = GraphTester.Companion.skyKey("right-" + (TEST_NODE_COUNT - 1))

        for (i in 0..<TESTED_NODES) {
            try {
                tester.getOrCreate(leftValues[i],  /* markAsModified= */true).setHasError(true)
                tester!!.invalidate()
                var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
                    tester!!.eval<SkyValue?>( /* keepGoing= */false, lastLeft, lastRight)
                assertThat(result.hasError()).isTrue()
                tester.differencer.invalidate(com.google.common.collect.ImmutableList.of<E?>(leftValues[i]))
                tester!!.invalidate()
                result = tester!!.eval<SkyValue?>( /* keepGoing= */false, lastLeft, lastRight)
                assertThat(result.hasError()).isTrue()
                tester.getOrCreate(leftValues[i],  /*markAsModified=*/true).setHasError(false)
                tester!!.invalidate()
                result = tester!!.eval<SkyValue?>( /* keepGoing= */false, lastLeft, lastRight)
                assertThat(result.get(lastLeft)).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))
                assertThat(result.get(lastRight)).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))
            } catch (e: java.lang.Exception) {
                java.lang.System.err.println("twoRailLeftRightDependenciesWithFailure exception on run " + i)
                throw e
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueInjection() {
        val key: SkyKey = GraphTester.Companion.nonHermeticKey("new_value")
        val delta: Delta = Delta.justNew(com.google.devtools.build.skyframe.GraphTester.StringValue("val"))

        tester.differencer.inject(com.google.common.collect.ImmutableMap.of<K?, V?>(key, delta))
        assertThat(tester.evalAndGet( /* keepGoing= */false, key)).isEqualTo(delta.newValue())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueInjectionOverExistingEntry() {
        val key: SkyKey = GraphTester.Companion.nonHermeticKey("key")
        val delta: Delta = Delta.justNew(com.google.devtools.build.skyframe.GraphTester.StringValue("val"))

        tester.getOrCreate(key).setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("old_val"))
        tester.differencer.inject(com.google.common.collect.ImmutableMap.of<K?, V?>(key, delta))
        assertThat(tester.evalAndGet( /* keepGoing= */false, key)).isEqualTo(delta.newValue())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueInjectionOverExistingDirtyEntry() {
        val key: SkyKey = GraphTester.Companion.nonHermeticKey("key")
        val delta: Delta = Delta.justNew(com.google.devtools.build.skyframe.GraphTester.StringValue("val"))

        tester.getOrCreate(key).setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("old_val"))
        tester.differencer.inject(com.google.common.collect.ImmutableMap.of<K?, V?>(key, delta))
        tester.eval<SkyValue?>( /*keepGoing=*/false, *arrayOfNulls<SkyKey>(0)) // Create the value.

        tester.differencer.invalidate(com.google.common.collect.ImmutableList.of<E?>(key))
        tester.eval<SkyValue?>( /*keepGoing=*/false, *arrayOfNulls<SkyKey>(0)) // Mark value as dirty.

        tester.differencer.inject(com.google.common.collect.ImmutableMap.of<K?, V?>(key, delta))
        tester.eval<SkyValue?>( /*keepGoing=*/false, *arrayOfNulls<SkyKey>(0)) // Inject again.
        assertThat(tester.evalAndGet( /* keepGoing= */false, key)).isEqualTo(delta.newValue())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueInjectionOverExistingEntryMarkedForInvalidation() {
        val key: SkyKey = GraphTester.Companion.nonHermeticKey("key")
        val delta: Delta = Delta.justNew(com.google.devtools.build.skyframe.GraphTester.StringValue("val"))

        tester.getOrCreate(key).setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("old_val"))
        tester.differencer.invalidate(com.google.common.collect.ImmutableList.of<E?>(key))
        tester.differencer.inject(com.google.common.collect.ImmutableMap.of<K?, V?>(key, delta))
        assertThat(tester.evalAndGet( /* keepGoing= */false, key)).isEqualTo(delta.newValue())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueInjectionOverExistingEntryMarkedForDeletion() {
        val key: SkyKey = GraphTester.Companion.nonHermeticKey("key")
        val delta: Delta = Delta.justNew(com.google.devtools.build.skyframe.GraphTester.StringValue("val"))

        tester.getOrCreate(key).setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("old_val"))
        tester.evaluator.delete(com.google.common.base.Predicates.alwaysTrue<T?>())
        tester.differencer.inject(com.google.common.collect.ImmutableMap.of<K?, V?>(key, delta))
        assertThat(tester.evalAndGet( /* keepGoing= */false, key)).isEqualTo(delta.newValue())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueInjectionOverExistingEqualEntryMarkedForInvalidation() {
        val key: SkyKey = GraphTester.Companion.nonHermeticKey("key")
        val delta: Delta = Delta.justNew(com.google.devtools.build.skyframe.GraphTester.StringValue("val"))

        tester.differencer.inject(com.google.common.collect.ImmutableMap.of<K?, V?>(key, delta))
        assertThat(tester.evalAndGet( /* keepGoing= */false, key)).isEqualTo(delta.newValue())

        tester.differencer.invalidate(com.google.common.collect.ImmutableList.of<E?>(key))
        tester.differencer.inject(com.google.common.collect.ImmutableMap.of<K?, V?>(key, delta))
        assertThat(tester.evalAndGet( /* keepGoing= */false, key)).isEqualTo(delta.newValue())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueInjectionOverExistingEqualEntryMarkedForDeletion() {
        val key: SkyKey = GraphTester.Companion.nonHermeticKey("key")
        val delta: Delta = Delta.justNew(com.google.devtools.build.skyframe.GraphTester.StringValue("val"))

        tester.differencer.inject(com.google.common.collect.ImmutableMap.of<K?, V?>(key, delta))
        assertThat(tester.evalAndGet( /* keepGoing= */false, key)).isEqualTo(delta.newValue())

        tester.evaluator.delete(com.google.common.base.Predicates.alwaysTrue<T?>())
        tester.differencer.inject(com.google.common.collect.ImmutableMap.of<K?, V?>(key, delta))
        assertThat(tester.evalAndGet( /* keepGoing= */false, key)).isEqualTo(delta.newValue())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueInjectionOverValueWithDeps() {
        val key: SkyKey = GraphTester.Companion.nonHermeticKey("key")
        val otherKey: SkyKey = GraphTester.Companion.nonHermeticKey("other")
        val delta: Delta = Delta.justNew(com.google.devtools.build.skyframe.GraphTester.StringValue("val"))
        val prevVal: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("foo")

        tester.getOrCreate(otherKey).setConstantValue(prevVal)
        tester.getOrCreate(key).addDependency(otherKey).setComputedValue(GraphTester.Companion.COPY)
        assertThat(tester.evalAndGet( /* keepGoing= */false, key)).isEqualTo(prevVal)
        tester.differencer.inject(com.google.common.collect.ImmutableMap.of<K?, V?>(key, delta))
        val depVal: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("newfoo")
        tester.getOrCreate(otherKey).setConstantValue(depVal)
        tester.differencer.invalidate(com.google.common.collect.ImmutableList.of<E?>(otherKey))
        // Injected value is ignored for value with deps.
        assertThat(tester.evalAndGet( /*keepGoing=*/false, key)).isEqualTo(depVal)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueInjectionOverEqualValueWithDeps() {
        val key: SkyKey = GraphTester.Companion.nonHermeticKey("key")
        val delta: Delta = Delta.justNew(com.google.devtools.build.skyframe.GraphTester.StringValue("val"))

        tester.getOrCreate("other").setConstantValue(delta.newValue())
        tester.getOrCreate(key).addDependency("other").setComputedValue(GraphTester.Companion.COPY)
        assertThat(tester.evalAndGet( /* keepGoing= */false, key)).isEqualTo(delta.newValue())
        tester.differencer.inject(com.google.common.collect.ImmutableMap.of<K?, V?>(key, delta))
        assertThat(tester.evalAndGet( /* keepGoing= */false, key)).isEqualTo(delta.newValue())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueInjectionOverValueWithErrors() {
        val key: SkyKey = GraphTester.Companion.nonHermeticKey("key")
        val delta: Delta = Delta.justNew(com.google.devtools.build.skyframe.GraphTester.StringValue("val"))

        tester.getOrCreate(key).setHasError(true)
        tester.evalAndGetError( /*keepGoing=*/true, key)

        tester.differencer.inject(com.google.common.collect.ImmutableMap.of<K?, V?>(key, delta))
        assertThat(tester.evalAndGet(false, key)).isEqualTo(delta.newValue())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueInjectionInvalidatesReverseDeps() {
        val childKey: SkyKey = GraphTester.Companion.nonHermeticKey("child")
        val parentKey: SkyKey = GraphTester.Companion.skyKey("parent")
        val oldVal: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("old_val")

        tester.getOrCreate(childKey).setConstantValue(oldVal)
        tester.getOrCreate(parentKey).addDependency(childKey).setComputedValue(GraphTester.Companion.COPY)

        val result: EvaluationResult<SkyValue?> = tester!!.eval<SkyValue?>(false, parentKey)
        assertThat(result.hasError()).isFalse()
        assertThat(result.get(parentKey)).isEqualTo(oldVal)

        val delta: Delta = Delta.justNew(com.google.devtools.build.skyframe.GraphTester.StringValue("val"))
        tester.differencer.inject(com.google.common.collect.ImmutableMap.of<K?, V?>(childKey, delta))
        assertThat(tester.evalAndGet( /* keepGoing= */false, childKey)).isEqualTo(delta.newValue())
        // Injecting a new child should have invalidated the parent.
        assertThat(tester!!.getExistingValue("parent")).isNull()

        tester!!.eval<SkyValue?>(false, childKey)
        assertThat(tester.getExistingValue(childKey)).isEqualTo(delta.newValue())
        assertThat(tester!!.getExistingValue("parent")).isNull()
        assertThat(tester!!.evalAndGet("parent")).isEqualTo(delta.newValue())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueInjectionOverExistingEqualEntryDoesNotInvalidate() {
        val childKey: SkyKey = GraphTester.Companion.nonHermeticKey("child")
        val parentKey: SkyKey = GraphTester.Companion.skyKey("parent")
        val delta: Delta = Delta.justNew(com.google.devtools.build.skyframe.GraphTester.StringValue("same_val"))

        tester.getOrCreate(parentKey).addDependency(childKey).setComputedValue(GraphTester.Companion.COPY)
        tester.getOrCreate(childKey)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("same_val"))
        assertThat(tester!!.evalAndGet("parent")).isEqualTo(delta.newValue())

        tester.differencer.inject(com.google.common.collect.ImmutableMap.of<K?, V?>(childKey, delta))
        assertThat(tester.getExistingValue(childKey)).isEqualTo(delta.newValue())
        // Since we are injecting an equal value, the parent should not have been invalidated.
        assertThat(tester!!.getExistingValue("parent")).isEqualTo(delta.newValue())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueInjectionInterrupt() {
        val key: SkyKey = GraphTester.Companion.nonHermeticKey("key")
        val delta: Delta = Delta.justNew(com.google.devtools.build.skyframe.GraphTester.StringValue("val"))

        tester.differencer.inject(com.google.common.collect.ImmutableMap.of<K?, V?>(key, delta))
        java.lang.Thread.currentThread().interrupt()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { tester.evalAndGet( /*keepGoing=*/false, key) })
        val newVal: SkyValue? = tester.evalAndGet( /*keepGoing=*/false, key)
        assertThat(newVal).isEqualTo(delta.newValue())
    }

    @Throws(java.lang.Exception::class)
    protected fun runTestPersistentErrorsNotRerun(includeTransientError: Boolean) {
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        val transientErrorKey: SkyKey = GraphTester.Companion.skyKey("transientError")
        val persistentErrorKey1: SkyKey = GraphTester.Companion.skyKey("persistentError1")
        val persistentErrorKey2: SkyKey = GraphTester.Companion.skyKey("persistentError2")

        val topFunction: TestFunction =
            tester
                .getOrCreate(topKey)
                .addErrorDependency(
                    persistentErrorKey1,
                    com.google.devtools.build.skyframe.GraphTester.StringValue("doesn't matter")
                )
                .setHasError(true)
        tester.getOrCreate(persistentErrorKey1).setHasError(true)
        if (includeTransientError) {
            topFunction.addErrorDependency(
                transientErrorKey,
                com.google.devtools.build.skyframe.GraphTester.StringValue("doesn't matter")
            )
            tester
                .getOrCreate(transientErrorKey)
                .addErrorDependency(
                    persistentErrorKey2,
                    com.google.devtools.build.skyframe.GraphTester.StringValue("doesn't matter")
                )
                .setHasTransientError(true)
        }
        tester.getOrCreate(persistentErrorKey2).setHasError(true)

        tester.evalAndGetError( /*keepGoing=*/true, topKey)
        if (includeTransientError) {
            Truth.assertThat(tester!!.enqueuedValues)
                .containsExactly(topKey, transientErrorKey, persistentErrorKey1, persistentErrorKey2)
        } else {
            Truth.assertThat(tester!!.enqueuedValues).containsExactly(topKey, persistentErrorKey1)
        }

        tester!!.invalidate()
        tester!!.invalidateTransientErrors()
        tester.evalAndGetError( /*keepGoing=*/true, topKey)
        if (includeTransientError) {
            // TODO(bazel-team): We can do better here once we implement change pruning for errors.
            Truth.assertThat(tester!!.enqueuedValues).containsExactly(topKey, transientErrorKey)
        } else {
            Truth.assertThat(tester!!.enqueuedValues).isEmpty()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun persistentErrorsNotRerun() {
        runTestPersistentErrorsNotRerun( /*includeTransientError=*/true)
    }

    /**
     * The following two tests check that the evaluator shuts down properly when encountering an error
     * that is marked dirty but later verified to be unchanged from a prior build. In that case, the
     * invariant that its parents are not enqueued for evaluation should be maintained.
     */
    /**
     * Test that a parent of a cached but invalidated error doesn't successfully build. First build
     * the error. Then invalidate the error via a dependency (so it will not actually change) and
     * build two new parents. Parent A will request error and abort since error isn't done yet. error
     * is then revalidated, and A is restarted. If A does not throw upon encountering the error, and
     * instead sets its value, then we throw in parent B, which waits for error to be done before
     * requesting it. Then there will be the impossible situation of a node that was built during this
     * evaluation depending on a node in error.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shutDownBuildOnCachedError_Done() {
        // errorKey will be invalidated due to its dependence on invalidatedKey, but later revalidated
        // since invalidatedKey re-evaluates to the same value on a subsequent build.
        val errorKey: SkyKey = GraphTester.Companion.skyKey("error")
        val invalidatedKey: SkyKey = GraphTester.Companion.nonHermeticKey("invalidated-leaf")
        tester.set(invalidatedKey, com.google.devtools.build.skyframe.GraphTester.StringValue("invalidated-leaf-value"))
        tester.getOrCreate(errorKey).addDependency(invalidatedKey).setHasError(true)
        // Names are alphabetized in reverse deps of errorKey.
        val fastToRequestSlowToSetValueKey: SkyKey = GraphTester.Companion.skyKey("A-slow-set-value-parent")
        val failingKey: SkyKey = GraphTester.Companion.skyKey("B-fast-fail-parent")
        tester
            .getOrCreate(fastToRequestSlowToSetValueKey)
            .addDependency(errorKey)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(failingKey).addDependency(errorKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        // We only want to force a particular order of operations at some points during evaluation.
        val synchronizeThreads: AtomicBoolean = AtomicBoolean(false)
        // We don't expect slow-set-value to actually be built, but if it is, we wait for it.
        val slowBuilt: CountDownLatch = CountDownLatch(1)
        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (!synchronizeThreads.get()) {
                    return@injectGraphListenerForTesting
                }
                if (type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.GET_LIFECYCLE_STATE && key.equals(
                        failingKey
                    )
                ) {
                    // Wait for the build to abort or for the other node to incorrectly build.
                    try {
                        Truth.assertThat(
                            slowBuilt.await(
                                com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                            )
                        )
                            .isTrue()
                    } catch (e: java.lang.InterruptedException) {
                        // This is ok, because it indicates the build is shutting down.
                        java.lang.Thread.currentThread().interrupt()
                    }
                } else if (type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE && key.equals(
                        fastToRequestSlowToSetValueKey
                    )
                    && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER
                ) {
                    // This indicates a problem -- this parent shouldn't be built since it depends on
                    // an error.
                    slowBuilt.countDown()
                    // Before this node actually sets its value (and then throws an exception) we wait
                    // for the other node to throw an exception.
                    try {
                        java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                        throw java.lang.IllegalStateException("uninterrupted in " + key)
                    } catch (e: java.lang.InterruptedException) {
                        java.lang.Thread.currentThread().interrupt()
                    }
                }
            },  /* deterministic= */
            true
        )
        // Initialize graph.
        tester!!.eval<SkyValue?>( /*keepGoing=*/true, errorKey)
        tester.getOrCreate(invalidatedKey,  /*markAsModified=*/true)
        tester!!.invalidate()
        synchronizeThreads.set(true)
        tester!!.eval<SkyValue?>( /*keepGoing=*/false, fastToRequestSlowToSetValueKey, failingKey)
    }

    /**
     * Test that the invalidated parent of a cached but invalidated error doesn't get marked clean.
     * First build the parent -- it will contain an error. Then invalidate the error via a dependency
     * (so it will not actually change) and then build the parent and another node that depends on the
     * error. The other node will wait to throw until the parent is signaled that all of its
     * dependencies are done, or until it is interrupted. If it throws, the parent will be
     * VERIFIED_CLEAN but not done, which is not a valid state once evaluation shuts down. The
     * evaluator avoids this situation by throwing when the error is encountered, even though the
     * error isn't evaluated or requested by an evaluating node.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shutDownBuildOnCachedError_Verified() {
        // TrackingProgressReceiver does unnecessary examination of node values.
        initializeTester(createTrackingProgressReceiver( /* checkEvaluationResults= */false))
        // errorKey will be invalidated due to its dependence on invalidatedKey, but later revalidated
        // since invalidatedKey re-evaluates to the same value on a subsequent build.
        val errorKey: SkyKey = GraphTester.Companion.skyKey("error")
        val invalidatedKey: SkyKey = GraphTester.Companion.nonHermeticKey("invalidated-leaf")
        val changedKey: SkyKey = GraphTester.Companion.nonHermeticKey("changed-leaf")
        tester.set(invalidatedKey, com.google.devtools.build.skyframe.GraphTester.StringValue("invalidated-leaf-value"))
        tester.set(changedKey, com.google.devtools.build.skyframe.GraphTester.StringValue("changed-leaf-value"))
        // Names are alphabetized in reverse deps of errorKey.
        val cachedParentKey: SkyKey = GraphTester.Companion.skyKey("A-cached-parent")
        val uncachedParentKey: SkyKey = GraphTester.Companion.skyKey("B-uncached-parent")
        tester.getOrCreate(errorKey).addDependency(invalidatedKey).setHasError(true)
        tester.getOrCreate(cachedParentKey).addDependency(errorKey).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester
            .getOrCreate(uncachedParentKey)
            .addDependency(changedKey)
            .addDependency(errorKey)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        // We only want to force a particular order of operations at some points during evaluation. In
        // particular, we don't want to force anything during error bubbling.
        val synchronizeThreads: AtomicBoolean = AtomicBoolean(false)
        val shutdownAwaiterStarted: CountDownLatch = CountDownLatch(1)
        injectGraphListenerForTesting(
            object : com.google.devtools.build.skyframe.NotifyingHelper.Listener {
                private val cachedSignaled: CountDownLatch = CountDownLatch(1)

                override fun accept(
                    key: SkyKey,
                    type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?,
                    order: com.google.devtools.build.skyframe.NotifyingHelper.Order?,
                    context: Any?
                ) {
                    if (!synchronizeThreads.get() || order != com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE || type != com.google.devtools.build.skyframe.NotifyingHelper.EventType.SIGNAL) {
                        return
                    }
                    TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                        shutdownAwaiterStarted, "shutdown awaiter not started"
                    )
                    if (key.equals(uncachedParentKey)) {
                        // When the uncached parent is first signaled by its changed dep, make sure that
                        // we wait until the cached parent is signaled too.
                        try {
                            Truth.assertThat(
                                cachedSignaled.await(
                                    com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                                    TimeUnit.SECONDS
                                )
                            )
                                .isTrue()
                        } catch (e: java.lang.InterruptedException) {
                            // Before the relevant bug was fixed, this code was not interrupted, and the
                            // uncached parent got to build, yielding an inconsistent state at a later point
                            // during evaluation. With the bugfix, the cached parent is never signaled
                            // before the evaluator shuts down, and so the above code is interrupted.
                            java.lang.Thread.currentThread().interrupt()
                        }
                    } else if (key.equals(cachedParentKey)) {
                        // This branch should never be reached by a well-behaved evaluator, since when the
                        // error node is reached, the evaluator should shut down. However, we don't test
                        // for that behavior here because that would be brittle and we expect that such an
                        // evaluator will crash hard later on in any case.
                        cachedSignaled.countDown()
                        try {
                            // Sleep until we're interrupted by the evaluator, so we know it's shutting
                            // down.
                            java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                            val currentThread: java.lang.Thread = java.lang.Thread.currentThread()
                            throw java.lang.IllegalStateException(
                                ("no interruption in time in "
                                        + key
                                        + " for "
                                        + (if (currentThread.isInterrupted()) "" else "un")
                                        + "interrupted "
                                        + currentThread
                                        + " with hash "
                                        + java.lang.System.identityHashCode(currentThread)
                                        + " at "
                                        + java.lang.System.currentTimeMillis())
                            )
                        } catch (e: java.lang.InterruptedException) {
                            java.lang.Thread.currentThread().interrupt()
                        }
                    }
                }
            },  /* deterministic= */
            true
        )
        // Initialize graph.
        tester!!.eval<SkyValue?>( /*keepGoing=*/true, cachedParentKey, uncachedParentKey)
        tester.getOrCreate(invalidatedKey,  /*markAsModified=*/true)
        tester.set(changedKey, com.google.devtools.build.skyframe.GraphTester.StringValue("new value"))
        tester!!.invalidate()
        synchronizeThreads.set(true)
        val waitForShutdownKey: SkyKey = GraphTester.Companion.skyKey("wait-for-shutdown")
        tester
            .getOrCreate(waitForShutdownKey)
            .setBuilder(
                SkyFunction { skyKey, env ->
                    shutdownAwaiterStarted.countDown()
                    TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                        (env as SkyFunctionEnvironment).getExceptionLatchForTesting(),
                        "exception not thrown"
                    )
                    // Threadpool is shutting down. Don't try to synchronize anything in the future
                    // during error bubbling.
                    synchronizeThreads.set(false)
                    throw java.lang.InterruptedException()
                })
        var result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, cachedParentKey, uncachedParentKey, waitForShutdownKey)
        assertWithMessage(result.toString()).that(result.hasError()).isTrue()
        tester.getOrCreate(invalidatedKey,  /*markAsModified=*/true)
        tester!!.invalidate()
        result = tester!!.eval<SkyValue?>( /*keepGoing=*/false, cachedParentKey, uncachedParentKey)
        assertWithMessage(result.toString()).that(result.hasError()).isTrue()
    }

    /**
     * Tests that a race between a node being marked clean and another node requesting it is benign.
     * Here, we first evaluate errorKey, depending on invalidatedKey. Then we invalidate
     * invalidatedKey (without actually changing it) and evaluate errorKey and topKey together.
     * Through forced synchronization, we make sure that the following sequence of events happens:
     * 
     * 
     *  1. topKey requests errorKey;
     *  1. errorKey is marked clean;
     *  1. topKey finishes its first evaluation and registers its deps;
     *  1. topKey restarts, since it sees that its only dep, errorKey, is done;
     *  1. topKey sees the error thrown by errorKey and throws the error, shutting down the
     * threadpool;
     * 
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cachedErrorCausesRestart() {
        // TrackingProgressReceiver does unnecessary examination of node values.
        initializeTester(createTrackingProgressReceiver( /* checkEvaluationResults= */false))
        val errorKey: SkyKey = GraphTester.Companion.skyKey("error")
        val invalidatedKey: SkyKey = GraphTester.Companion.nonHermeticKey("invalidated")
        val topKey: SkyKey = GraphTester.Companion.skyKey("top")
        tester.getOrCreate(errorKey).addDependency(invalidatedKey).setHasError(true)
        tester.getOrCreate(invalidatedKey)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("constant"))
        val topSecondEval: CountDownLatch = CountDownLatch(2)
        val topRequestedError: CountDownLatch = CountDownLatch(1)
        val errorMarkedClean: CountDownLatch = CountDownLatch(1)
        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (errorKey.equals(key) && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.MARK_CLEAN) {
                    if (order == com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE) {
                        TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                            topRequestedError, "top didn't request"
                        )
                    } else {
                        errorMarkedClean.countDown()
                        TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                            topSecondEval, "top didn't restart"
                        )
                        // Make sure that the other thread notices the error and interrupts this thread.
                        try {
                            java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                        } catch (e: java.lang.InterruptedException) {
                            java.lang.Thread.currentThread().interrupt()
                        }
                    }
                }
            },  /* deterministic= */
            false
        )
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, errorKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(errorKey)
            .hasExceptionThat()
            .isNotNull()
        tester
            .getOrCreate(topKey)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        topSecondEval.countDown()
                        env.getValue(errorKey)
                        topRequestedError.countDown()
                        assertThat(env.valuesMissing()).isTrue()
                        TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                            errorMarkedClean, "error not marked clean"
                        )
                        return null
                    }
                })
        tester.getOrCreate(invalidatedKey,  /*markAsModified=*/true)
        tester!!.invalidate()
        val result2: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, errorKey, topKey)
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result2).hasError()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result2)
            .hasErrorEntryForKeyThat(errorKey)
            .hasExceptionThat()
            .isNotNull()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result2)
            .hasErrorEntryForKeyThat(topKey)
            .hasExceptionThat()
            .isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cachedChildErrorDepWithSiblingDepOnNoKeepGoingEval() {
        val parent1Key: SkyKey = GraphTester.Companion.skyKey("parent1")
        val parent2Key: SkyKey = GraphTester.Companion.skyKey("parent2")
        val errorKey: SkyKey = GraphTester.Companion.nonHermeticKey("error")
        val otherKey: SkyKey = GraphTester.Companion.skyKey("other")
        val parentBuilder: SkyFunction =
            SkyFunction { skyKey, env ->
                env.getValue(errorKey)
                env.getValue(otherKey)
                if (env.valuesMissing()) {
                    return@SkyFunction null
                }
                com.google.devtools.build.skyframe.GraphTester.StringValue("parent")
            }
        tester.getOrCreate(parent1Key).setBuilder(parentBuilder)
        tester.getOrCreate(parent2Key).setBuilder(parentBuilder)
        tester.getOrCreate(errorKey)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("no error yet"))
        tester.getOrCreate(otherKey)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("other"))
        tester!!.eval<SkyValue?>( /*keepGoing=*/true, parent1Key)
        tester!!.eval<SkyValue?>( /*keepGoing=*/false, parent2Key)
        tester.getOrCreate(errorKey,  /*markAsModified=*/true).setHasError(true)
        tester!!.invalidate()
        tester!!.eval<SkyValue?>( /*keepGoing=*/true, parent1Key)
        tester!!.eval<SkyValue?>( /*keepGoing=*/false, parent2Key)
    }

    private fun injectGraphListenerForTesting(
        listener: com.google.devtools.build.skyframe.NotifyingHelper.Listener?,
        deterministic: Boolean
    ) {
        tester.evaluator.injectGraphTransformerForTesting(
            DeterministicHelper.Companion.makeTransformer(listener, deterministic)
        )
    }

    private fun makeGraphDeterministic() {
        tester.evaluator.injectGraphTransformerForTesting(DeterministicHelper.Companion.MAKE_DETERMINISTIC)
    }

    private class PassThroughSelected(key: SkyKey?) : ValueComputer {
        private val key: SkyKey?

        init {
            this.key = key
        }

        override fun compute(deps: MutableMap<SkyKey?, SkyValue?>, env: SkyFunction.Environment?): SkyValue? {
            return com.google.common.base.Preconditions.checkNotNull<SkyValue?>(deps.get(key))
        }
    }

    @Throws(java.lang.Exception::class)
    private fun removedNodeComesBack() {
        val top: SkyKey = GraphTester.Companion.nonHermeticKey("top")
        val mid: SkyKey = GraphTester.Companion.skyKey("mid")
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        // When top depends on mid, which depends on leaf,
        tester.getOrCreate(top).addDependency(mid).setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate(mid).addDependency(leaf).setComputedValue(GraphTester.Companion.CONCATENATE)
        val leafValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("leaf")
        tester.set(leaf, leafValue)
        // Then when top is evaluated, its value is as expected.
        assertThat(tester.evalAndGet( /* keepGoing= */true, top)).isEqualTo(leafValue)
        // When top is changed to no longer depend on mid,
        val topValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("top")
        tester
            .getOrCreate(top,  /* markAsModified= */true)
            .removeDependency(mid)
            .setComputedValue(null)
            .setConstantValue(topValue)
        // And leaf is invalidated,
        tester.getOrCreate(leaf,  /*markAsModified=*/true)
        // Then when top is evaluated, its value is as expected,
        tester!!.invalidate()
        assertThat(tester.evalAndGet( /*keepGoing=*/true, top)).isEqualTo(topValue)
        // And there is no value for mid in the graph,
        assertThat(tester.evaluator.getExistingValue(mid)).isNull()
        assertThat(tester.evaluator.getExistingErrorForTesting(mid)).isNull()
        // Or for leaf.
        assertThat(tester.evaluator.getExistingValue(leaf)).isNull()
        assertThat(tester.evaluator.getExistingErrorForTesting(leaf)).isNull()

        // When top is changed to depend directly on leaf,
        tester
            .getOrCreate(top,  /*markAsModified=*/true)
            .addDependency(leaf)
            .setConstantValue(null)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        // Then when top is evaluated, its value is as expected,
        tester!!.invalidate()
        assertThat(tester.evalAndGet( /*keepGoing=*/true, top)).isEqualTo(leafValue)
        // and there is no value for mid in the graph,
        assertThat(tester.evaluator.getExistingValue(mid)).isNull()
        assertThat(tester.evaluator.getExistingErrorForTesting(mid)).isNull()
    }

    // Tests that a removed and then reinstated node doesn't try to invalidate its erstwhile parent
    // when it is invalidated.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun removedNodeComesBackAndInvalidates() {
        removedNodeComesBack()
        // When leaf is invalidated again,
        tester.getOrCreate(GraphTester.Companion.skyKey("leaf"),  /* markAsModified= */true)
        // Then when top is evaluated, its value is as expected.
        tester!!.invalidate()
        Truth.assertThat(tester.evalAndGet( /* keepGoing= */true, GraphTester.Companion.nonHermeticKey("top")))
            .isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))
    }

    // Tests that a removed and then reinstated node behaves properly when its parent disappears and
    // then reappears.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun removedNodeComesBackAndOtherInvalidates() {
        removedNodeComesBack()
        val top: SkyKey = GraphTester.Companion.nonHermeticKey("top")
        val mid: SkyKey = GraphTester.Companion.skyKey("mid")
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        // When top is invalidated again,
        tester.getOrCreate(top,  /* markAsModified= */true).removeDependency(leaf).addDependency(mid)
        // Then when top is evaluated, its value is as expected.
        tester!!.invalidate()
        assertThat(
            tester.evalAndGet( /* keepGoing= */true,
                top
            )
        ).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))
    }

    // Tests that a removed and then reinstated node doesn't have a reverse dep on a former parent.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun removedInvalidatedNodeComesBackAndOtherInvalidates() {
        val top: SkyKey = GraphTester.Companion.nonHermeticKey("top")
        val leaf: SkyKey = GraphTester.Companion.nonHermeticKey("leaf")
        // When top depends on leaf,
        tester.getOrCreate(top).addDependency(leaf).setComputedValue(GraphTester.Companion.CONCATENATE)
        val leafValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("leaf")
        tester.set(leaf, leafValue)
        // Then when top is evaluated, its value is as expected.
        assertThat(tester.evalAndGet( /* keepGoing= */true, top)).isEqualTo(leafValue)
        // When top is changed to no longer depend on leaf,
        val topValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("top")
        tester
            .getOrCreate(top,  /* markAsModified= */true)
            .removeDependency(leaf)
            .setComputedValue(null)
            .setConstantValue(topValue)
        // And leaf is invalidated,
        tester.getOrCreate(leaf,  /*markAsModified=*/true)
        // Then when top is evaluated, its value is as expected,
        tester!!.invalidate()
        assertThat(tester.evalAndGet( /*keepGoing=*/true, top)).isEqualTo(topValue)
        // And there is no value for leaf in the graph.
        assertThat(tester.evaluator.getExistingValue(leaf)).isNull()
        assertThat(tester.evaluator.getExistingErrorForTesting(leaf)).isNull()
        // When leaf is evaluated, so that it is present in the graph again,
        assertThat(tester.evalAndGet( /*keepGoing=*/true, leaf)).isEqualTo(leafValue)
        // And top is changed to depend on leaf again,
        tester
            .getOrCreate(top,  /*markAsModified=*/true)
            .addDependency(leaf)
            .setConstantValue(null)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        // Then when top is evaluated, its value is as expected.
        tester!!.invalidate()
        assertThat(tester.evalAndGet( /*keepGoing=*/true, top)).isEqualTo(leafValue)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cleanReverseDepFromDirtyNodeNotInBuild() {
        val topKey: SkyKey = GraphTester.Companion.nonHermeticKey("top")
        val inactiveKey: SkyKey = GraphTester.Companion.nonHermeticKey("inactive")
        val mainThread: java.lang.Thread = java.lang.Thread.currentThread()
        val shouldInterrupt: AtomicBoolean = AtomicBoolean(false)
        injectGraphListenerForTesting(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                if (shouldInterrupt.get()
                    && key.equals(topKey)
                    && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.IS_READY && order == com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE
                ) {
                    mainThread.interrupt()
                    shouldInterrupt.set(false)
                    try {
                        // Make sure threadpool propagates interrupt.
                        java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                    } catch (e: java.lang.InterruptedException) {
                        java.lang.Thread.currentThread().interrupt()
                    }
                }
            },  /* deterministic= */
            false
        )
        // When top depends on inactive,
        tester.getOrCreate(topKey).addDependency(inactiveKey).setComputedValue(GraphTester.Companion.COPY)
        val `val`: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("inactive")
        // And inactive is constant,
        tester.set(inactiveKey, `val`)
        // Then top evaluates normally.
        assertThat(tester.evalAndGet( /*keepGoing=*/true, topKey)).isEqualTo(`val`)
        // When evaluation will be interrupted as soon as top starts evaluating,
        shouldInterrupt.set(true)
        // And inactive is dirty,
        tester.getOrCreate(inactiveKey,  /*markAsModified=*/true)
        // And so is top,
        tester.getOrCreate(topKey,  /*markAsModified=*/true)
        tester!!.invalidate()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { tester!!.eval<SkyValue?>( /*keepGoing=*/false, topKey) })
        // But inactive is still present,
        assertThat(tester.evaluator.getExistingEntryAtCurrentlyEvaluatingVersion(inactiveKey))
            .isNotNull()
        // And still dirty,
        assertThat(tester.evaluator.getExistingEntryAtCurrentlyEvaluatingVersion(inactiveKey).isDirty())
            .isTrue()
        // And re-evaluates successfully,
        assertThat(tester.evalAndGet( /*keepGoing=*/true, inactiveKey)).isEqualTo(`val`)
        // But top is gone from the graph,
        assertThat(tester.evaluator.getExistingEntryAtCurrentlyEvaluatingVersion(topKey)).isNull()
        // And we can successfully invalidate and re-evaluate inactive again.
        tester.getOrCreate(inactiveKey,  /*markAsModified=*/true)
        tester!!.invalidate()
        assertThat(tester.evalAndGet( /*keepGoing=*/true, inactiveKey)).isEqualTo(`val`)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorChanged() {
        val error: SkyKey = GraphTester.Companion.nonHermeticKey("error")
        tester.getOrCreate(error).setHasError(true)
        ErrorInfoSubjectFactory.Companion.assertThatErrorInfo(tester.evalAndGetError( /*keepGoing=*/true, error))
            .hasExceptionThat()
            .isNotNull()
        tester.getOrCreate(error,  /*markAsModified=*/true)
        tester!!.invalidate()
        ErrorInfoSubjectFactory.Companion.assertThatErrorInfo(tester.evalAndGetError( /*keepGoing=*/true, error))
            .hasExceptionThat()
            .isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicateUnfinishedDeps_NoKeepGoing() {
        runTestDuplicateUnfinishedDeps( /*keepGoing=*/false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicateUnfinishedDeps_KeepGoing() {
        runTestDuplicateUnfinishedDeps( /*keepGoing=*/true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun externalDep() {
        externalDep(1, 0)
        externalDep(2, 0)
        externalDep(1, 1)
        externalDep(1, 2)
        externalDep(2, 1)
        externalDep(2, 2)
    }

    @Throws(java.lang.Exception::class)
    private fun externalDep(firstPassCount: Int, secondPassCount: Int) {
        val parentKey: SkyKey = GraphTester.Companion.skyKey("parentKey")
        val firstPassLatch: CountDownLatch = CountDownLatch(1)
        val secondPassLatch: CountDownLatch = CountDownLatch(1)
        tester
            .getOrCreate(parentKey)
            .setBuilder(
                object : SkyFunction() {
                    // Skyframe doesn't have native support for continuations, so we use fields here. A
                    // simple continuation API in Skyframe could be Environment providing a
                    // setContinuation(SkyContinuation) method, where SkyContinuation provides a compute
                    // method similar to SkyFunction. When restarting the node, Skyframe would then call
                    // the continuation rather than the original SkyFunction. If we do that, we should
                    // consider only allowing calls to dependOnFuture in combination with setContinuation.
                    private var firstPass: MutableList<com.google.common.util.concurrent.SettableFuture<SkyValue?>?>? =
                        null
                    private var secondPass: MutableList<com.google.common.util.concurrent.SettableFuture<SkyValue?>?>? =
                        null

                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        if (firstPass == null) {
                            firstPass =
                                java.util.ArrayList<com.google.common.util.concurrent.SettableFuture<SkyValue?>?>()
                            for (i in 0..<firstPassCount) {
                                val future: com.google.common.util.concurrent.SettableFuture<SkyValue?> =
                                    com.google.common.util.concurrent.SettableFuture.create<SkyValue?>()
                                firstPass!!.add(future)
                                env.dependOnFuture(future)
                            }
                            assertThat(env.valuesMissing()).isTrue()
                            val helper: java.lang.Thread =
                                java.lang.Thread(
                                    java.lang.Runnable {
                                        try {
                                            firstPassLatch.await()
                                            for (i in 0..<firstPassCount) {
                                                firstPass!!.get(i)
                                                    .set(com.google.devtools.build.skyframe.GraphTester.StringValue("value1"))
                                            }
                                        } catch (e: java.lang.InterruptedException) {
                                            throw java.lang.RuntimeException(e)
                                        }
                                    })
                            helper.start()
                            return null
                        } else if (secondPass == null && secondPassCount > 0) {
                            for (i in 0..<firstPassCount) {
                                Truth.assertThat(firstPass!!.get(i).isDone()).isTrue()
                            }
                            secondPass =
                                java.util.ArrayList<com.google.common.util.concurrent.SettableFuture<SkyValue?>?>()
                            for (i in 0..<secondPassCount) {
                                val future: com.google.common.util.concurrent.SettableFuture<SkyValue?> =
                                    com.google.common.util.concurrent.SettableFuture.create<SkyValue?>()
                                secondPass!!.add(future)
                                env.dependOnFuture(future)
                            }
                            assertThat(env.valuesMissing()).isTrue()
                            val helper: java.lang.Thread =
                                java.lang.Thread(
                                    java.lang.Runnable {
                                        try {
                                            secondPassLatch.await()
                                            for (i in 0..<secondPassCount) {
                                                secondPass!!.get(i)
                                                    .set(com.google.devtools.build.skyframe.GraphTester.StringValue("value2"))
                                            }
                                        } catch (e: java.lang.InterruptedException) {
                                            throw java.lang.RuntimeException(e)
                                        }
                                    })
                            helper.start()
                            return null
                        }
                        for (i in 0..<secondPassCount) {
                            Truth.assertThat(secondPass!!.get(i).isDone()).isTrue()
                        }
                        return com.google.devtools.build.skyframe.GraphTester.StringValue("done!")
                    }
                })
        tester.evaluator.injectGraphTransformerForTesting(
            NotifyingHelper.Companion.makeNotifyingTransformer(
                object : com.google.devtools.build.skyframe.NotifyingHelper.Listener {
                    private var firstPassDone = false

                    override fun accept(
                        key: SkyKey?,
                        type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?,
                        order: com.google.devtools.build.skyframe.NotifyingHelper.Order?,
                        context: Any?
                    ) {
                        // NodeEntry.addExternalDep is called as part of bookkeeping at the end of
                        // AbstractParallelEvaluator.Evaluate#run.
                        if (key === parentKey && type == com.google.devtools.build.skyframe.NotifyingHelper.EventType.ADD_EXTERNAL_DEP) {
                            if (!firstPassDone) {
                                firstPassLatch.countDown()
                                firstPassDone = true
                            } else {
                                secondPassLatch.countDown()
                            }
                        }
                    }
                })
        )
        val result: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
            tester!!.eval<SkyValue?>( /*keepGoing=*/false, parentKey)
        assertThat(result.hasError()).isFalse()
        assertThat(result.get(parentKey)).isEqualTo(com.google.devtools.build.skyframe.GraphTester.StringValue("done!"))
    }

    @Throws(java.lang.Exception::class)
    private fun runTestDuplicateUnfinishedDeps(keepGoing: Boolean) {
        val parentKey: SkyKey = GraphTester.Companion.skyKey("parent")
        val childKey: SkyKey = GraphTester.Companion.skyKey("child")
        val childValue: SkyValue = com.google.devtools.build.skyframe.GraphTester.StringValue("child")
        tester
            .getOrCreate(childKey)
            .setBuilder(
                object : SkyFunction() {
                    public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue? {
                        if (keepGoing) {
                            return childValue
                        } else {
                            throw java.lang.IllegalStateException("shouldn't get here")
                        }
                    }
                })
        val parentExn: SomeErrorException = SomeErrorException("bad")
        val numParentComputeCalls: AtomicInteger = AtomicInteger(0)
        tester
            .getOrCreate(parentKey)
            .setBuilder(
                object : SkyFunction() {
                    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
                    public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                        numParentComputeCalls.incrementAndGet()
                        if (!keepGoing || numParentComputeCalls.get() == 1) {
                            com.google.common.base.Preconditions.checkState(env.getValue(childKey) == null)
                            com.google.common.base.Preconditions.checkState(env.getValue(childKey) == null)
                        } else {
                            com.google.common.base.Preconditions.checkState(env.getValue(childKey).equals(childValue))
                            com.google.common.base.Preconditions.checkState(env.getValue(childKey).equals(childValue))
                        }
                        throw GenericFunctionException(parentExn, Transience.PERSISTENT)
                    }
                })

        val exception: java.lang.Exception? = tester.evalAndGetError(keepGoing, parentKey).getException()
        Truth.assertThat(exception).isInstanceOf(SomeErrorException::class.java)
        Truth.assertThat(exception).hasMessageThat().isEqualTo("bad")
    }

    /** Data encapsulating a graph inconsistency found during evaluation.  */
    internal class InconsistencyData(
        key: SkyKey?,
        otherKeys: com.google.common.collect.ImmutableSet<SkyKey?>?,
        inconsistency: Inconsistency?
    ) {
        val key: SkyKey?
        val otherKeys: com.google.common.collect.ImmutableSet<SkyKey?>?
        val inconsistency: Inconsistency?

        init {
            this.inconsistency = inconsistency
            this.otherKeys = otherKeys
            this.key = key
            java.util.Objects.requireNonNull<Any?>(key, "key")
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<SkyKey?>?>(otherKeys, "otherKeys")
            java.util.Objects.requireNonNull<Any?>(inconsistency, "inconsistency")
        }

        companion object {
            fun resetRequested(key: SkyKey?): InconsistencyData {
                return create(key,  /* otherKeys= */null, Inconsistency.RESET_REQUESTED)
            }

            fun rewind(parent: SkyKey?, children: com.google.common.collect.ImmutableSet<SkyKey?>?): InconsistencyData {
                return create(parent, children, Inconsistency.PARENT_FORCE_REBUILD_OF_CHILD)
            }

            fun create(
                key: SkyKey?, otherKeys: MutableCollection<SkyKey?>?, inconsistency: Inconsistency?
            ): InconsistencyData {
                return InconsistencyData(
                    key,
                    if (otherKeys == null) com.google.common.collect.ImmutableSet.of<SkyKey?>() else com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(
                        otherKeys
                    ),
                    inconsistency
                )
            }
        }
    }

    /** A graph tester that is specific to the memoizing evaluator, with some convenience methods.  */
    protected inner class MemoizingEvaluatorTester : GraphTester() {
        private var differencer: RecordingDifferencer? = null
        private var evaluator: MemoizingEvaluator? = null
        private var progressReceiver: TrackingProgressReceiver =
            createTrackingProgressReceiver( /*checkEvaluationResults=*/true)
        private var graphInconsistencyReceiver: GraphInconsistencyReceiver? = GraphInconsistencyReceiver.THROWING
        private var eventFilter: EventFilter? = EventFilter.FULL_STORAGE

        /** Constructs a new [.evaluator], so call before injecting a transformer into it!  */
        fun initialize() {
            this.differencer = this.recordingDifferencer
            this.evaluator =
                getMemoizingEvaluator(
                    getSkyFunctionMap(),
                    differencer,
                    progressReceiver,
                    graphInconsistencyReceiver,
                    eventFilter
                )
        }

        /**
         * Sets the [.progressReceiver]. [.initialize] must be called after this to have any
         * effect.
         */
        fun setProgressReceiver(progressReceiver: TrackingProgressReceiver) {
            this.progressReceiver = progressReceiver
        }

        /**
         * Sets the [.eventFilter]. [.initialize] must be called after this to have any
         * effect.
         */
        fun setEventFilter(eventFilter: EventFilter?) {
            this.eventFilter = eventFilter
        }

        /**
         * Sets the [.graphInconsistencyReceiver]. [.initialize] must be called after this
         * to have any effect.
         */
        fun setGraphInconsistencyReceiver(
            graphInconsistencyReceiver: GraphInconsistencyReceiver?
        ) {
            this.graphInconsistencyReceiver = graphInconsistencyReceiver
        }

        fun getEvaluator(): MemoizingEvaluator {
            return evaluator
        }

        @Throws(java.lang.InterruptedException::class)
        fun invalidate() {
            evaluator.noteEvaluationsAtSameVersionMayBeFinished(reporter)
            differencer.invalidate(getModifiedValues())
            clearModifiedValues()
            progressReceiver.clear()
        }

        fun invalidateTransientErrors() {
            differencer.invalidateTransientErrors()
        }

        fun delete(key: String?) {
            evaluator.delete(com.google.common.base.Predicates.equalTo<T?>(GraphTester.Companion.skyKey(key)))
        }

        fun resetPlayedEvents() {
            emittedEventState.clear()
        }

        val dirtyKeys: MutableSet<SkyKey>?
            get() = progressReceiver.dirty

        val deletedKeys: MutableSet<SkyKey>?
            get() = progressReceiver.deleted

        val enqueuedValues: MutableSet<SkyKey>?
            get() = progressReceiver.enqueued

        @Throws(java.lang.InterruptedException::class)
        fun <T : SkyValue?> eval(
            keepGoing: Boolean,
            mergingSkyframeAnalysisExecutionPhases: Boolean,
            numThreads: Int,
            vararg keys: SkyKey?
        ): EvaluationResult<T?> {
            Truth.assertThat(getModifiedValues()).isEmpty()
            val evaluationContext: EvaluationContext? =
                EvaluationContext.newBuilder()
                    .setKeepGoing(keepGoing)
                    .setMergingSkyframeAnalysisExecutionPhases(mergingSkyframeAnalysisExecutionPhases)
                    .setParallelism(numThreads)
                    .setEventHandler(reporter)
                    .build()
            BugReport.maybePropagateLastCrashIfInTest()
            var result: EvaluationResult<T?>? = null
            beforeEvaluation()
            try {
                result = evaluator.evaluate(
                    com.google.common.collect.ImmutableList.< E > copyOf < E ? > (keys),
                    evaluationContext
                )
                return result
            } finally {
                afterEvaluation(result, evaluationContext)
            }
        }

        @Throws(java.lang.InterruptedException::class)
        fun <T : SkyValue?> eval(keepGoing: Boolean, vararg keys: SkyKey?): EvaluationResult<T?> {
            return eval<SkyValue?>(keepGoing,  /* mergingSkyframeAnalysisExecutionPhases= */false, 100, *keys)
        }

        @Throws(java.lang.InterruptedException::class)
        fun <T : SkyValue?> eval(
            keepGoing: Boolean, mergingSkyframeAnalysisExecutionPhases: Boolean, vararg keys: SkyKey?
        ): EvaluationResult<T?> {
            return eval<SkyValue?>(keepGoing, mergingSkyframeAnalysisExecutionPhases, 100, *keys)
        }

        @Throws(java.lang.InterruptedException::class)
        fun <T : SkyValue?> eval(keepGoing: Boolean, vararg keys: String?): EvaluationResult<T?> {
            return eval<SkyValue?>(keepGoing, *GraphTester.Companion.toSkyKeys(*keys).toTypedArray<SkyKey?>())
        }

        @Throws(java.lang.InterruptedException::class)
        fun evalAndGet(keepGoing: Boolean, key: String?): SkyValue? {
            return evalAndGet(keepGoing, GraphTester.Companion.skyKey(key))
        }

        @Throws(java.lang.InterruptedException::class)
        fun evalAndGet(key: String?): SkyValue? {
            return evalAndGet( /*keepGoing=*/false, key)
        }

        @Throws(java.lang.InterruptedException::class)
        fun evalAndGet(keepGoing: Boolean, key: SkyKey?): SkyValue? {
            val evaluationResult: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
                eval<SkyValue?>(keepGoing, key)
            val result: SkyValue? = evaluationResult.get(key)
            assertWithMessage(evaluationResult.toString()).that(result).isNotNull()
            return result
        }

        @Throws(java.lang.InterruptedException::class)
        fun evalAndGetError(keepGoing: Boolean, key: SkyKey?): ErrorInfo {
            val evaluationResult: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
                eval<SkyValue?>(keepGoing, key)
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(evaluationResult)
                .hasErrorEntryForKeyThat(key)
            return evaluationResult.getError(key)
        }

        @Throws(java.lang.InterruptedException::class)
        fun evalAndGetError(
            keepGoing: Boolean, mergingSkyframeAnalysisExecutionPhases: Boolean, key: SkyKey?
        ): ErrorInfo {
            val evaluationResult: EvaluationResult<com.google.devtools.build.skyframe.GraphTester.StringValue?> =
                eval<SkyValue?>(keepGoing, mergingSkyframeAnalysisExecutionPhases, key)
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(evaluationResult)
                .hasErrorEntryForKeyThat(key)
            return evaluationResult.getError(key)
        }

        @Throws(java.lang.InterruptedException::class)
        fun evalAndGetError(keepGoing: Boolean, key: String?): ErrorInfo {
            return evalAndGetError(keepGoing, GraphTester.Companion.skyKey(key))
        }

        @Throws(java.lang.InterruptedException::class)
        fun getExistingValue(key: SkyKey?): SkyValue? {
            return evaluator.getExistingValue(key)
        }

        @Throws(java.lang.InterruptedException::class)
        fun getExistingValue(key: String?): SkyValue? {
            return getExistingValue(GraphTester.Companion.skyKey(key))
        }
    }

    companion object {
        // Knobs that control the size / duration of larger tests.
        private const val TEST_NODE_COUNT = 100
        private const val TESTED_NODES = 10
        private const val RUNS = 10

        private fun awaitUnchecked(barrier: CyclicBarrier) {
            try {
                barrier.await()
            } catch (e: java.lang.InterruptedException) {
                throw java.lang.IllegalStateException(e)
            } catch (e: BrokenBarrierException) {
                throw java.lang.IllegalStateException(e)
            }
        }

        private val INTERRUPT_BUILDER: SkyFunction = SkyFunction { skyKey, env ->
            throw java.lang.InterruptedException()
        }
    }
}
