// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor
import com.google.devtools.build.skyframe.StateMachineTest.Companion.DONE_VALUE

@RunWith(TestParameterInjector::class)
class StateMachineTest {
    private val graph: ProcessableGraph = InMemoryGraphImpl()
    private val tester: GraphTester = GraphTester()

    private val reportedEvents: StoredEventHandler = StoredEventHandler()
    private val revalidationReceiver: DirtyAndInflightTrackingProgressReceiver =
        DirtyAndInflightTrackingProgressReceiver(EvaluationProgressReceiver.NULL)

    // TODO(shahan): consider factoring this boilerplate out to a common location.
    @Throws(java.lang.InterruptedException::class)
    private fun <T : SkyValue?> eval(root: SkyKey, keepGoing: Boolean): EvaluationResult<T?> {
        return ParallelEvaluator(
            graph,
            VERSION,
            Version.minimal(),
            tester.getSkyFunctionMap(),
            reportedEvents,
            EmittedEventState(),
            EventFilter.FULL_STORAGE,
            ErrorInfoManager.UseChildErrorInfoIfNecessary.INSTANCE,
            revalidationReceiver,
            GraphInconsistencyReceiver.THROWING,
            AbstractQueueVisitor.create(
                "test-pool", TEST_PARALLELISM, ParallelEvaluatorErrorClassifier.instance()
            ),
            SimpleCycleDetector( /* storeExactCycles= */true),
            UnnecessaryTemporaryStateDropperReceiver.NULL,  /* keepGoing= */
            { skyKey -> keepGoing })
            .eval(com.google.common.collect.ImmutableList.of<E?>(root))
    }

    @TestParameter
    private val rootKeySkipsBatchPrefetch = false

    private var rootKey: SkyKey? = null

    @Before
    fun predefineCommonEntries() {
        tester.getOrCreate(KEY_A1).setConstantValue(VALUE_A1)
        tester.getOrCreate(KEY_A2).setConstantValue(VALUE_A2)
        tester.getOrCreate(KEY_A3).setConstantValue(VALUE_A3)
        tester.getOrCreate(KEY_B1).setConstantValue(VALUE_B1)
        tester.getOrCreate(KEY_B2).setConstantValue(VALUE_B2)
        tester.getOrCreate(KEY_B3).setConstantValue(VALUE_B3)
        rootKey =
            if (rootKeySkipsBatchPrefetch)
                GraphTester.Companion.skipBatchPrefetchKey("root")
            else
                GraphTester.Companion.skyKey("root")
    }

    private class StateMachineWrapper(machine: StateMachine?) : SkyKeyComputeState {
        private val driver: Driver

        init {
            this.driver = Driver(machine)
        }

        @Throws(java.lang.InterruptedException::class)
        fun drive(env: Environment?): Boolean {
            return driver.drive(env)
        }
    }

    /**
     * Defines a [SkyFunction] that executes the gives state machine.
     * 
     * 
     * The function always has key [rootKey] and value [DONE_VALUE]. State machine
     * internals can be observed with consumers.
     * 
     * @return a counter that stores the restart count.
     */
    private fun defineRootMachine(rootMachineSupplier: java.util.function.Supplier<StateMachine?>): AtomicInteger {
        val restartCount: AtomicInteger = AtomicInteger()
        tester
            .getOrCreate(rootKey)
            .setBuilder(
                SkyFunction { k, env ->
                    if (!env.getState({ StateMachineWrapper(rootMachineSupplier.get()) })
                            .drive(env)
                    ) {
                        restartCount.getAndIncrement()
                        return@setBuilder null
                    }
                    DONE_VALUE
                })
        return restartCount
    }

    @Throws(java.lang.InterruptedException::class)
    private fun evalMachine(rootMachineSupplier: java.util.function.Supplier<StateMachine?>): Int {
        val restartCount: AtomicInteger = defineRootMachine(rootMachineSupplier)
        assertThat(eval<SkyValue?>(rootKey,  /* keepGoing= */false).get(rootKey)).isEqualTo(DONE_VALUE)
        return restartCount.get()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun runMachine(root: StateMachine?): Boolean {
        return !StateMachineEvaluatorForTesting.run(
            root,
            InMemoryMemoizingEvaluator(
                tester.getSkyFunctionMap(), SequencedRecordingDifferencer()
            ),
            EvaluationContext.newBuilder()
                .setKeepGoing(true)
                .setParallelism(TEST_PARALLELISM)
                .setEventHandler(reportedEvents)
                .build()
        )
            .hasError()
    }

    /**
     * A simple machine having two states, fetching one value from each.
     * 
     * 
     * This machine causes two restarts, one for each of the lookups from the two states.
     */
    private class TwoStepMachine(
        sink1: java.util.function.Consumer<SkyValue?>?,
        sink2: java.util.function.Consumer<SkyValue?>?
    ) : StateMachine {
        private val sink1: java.util.function.Consumer<SkyValue?>?
        private val sink2: java.util.function.Consumer<SkyValue?>?

        init {
            this.sink1 = sink1
            this.sink2 = sink2
        }

        public override fun step(tasks: Tasks): StateMachine {
            tasks.lookUp(KEY_A1, sink1)
            return StateMachine { tasks: Tasks -> this.step2(tasks) }
        }

        fun step2(tasks: Tasks): StateMachine {
            tasks.lookUp(KEY_A2, sink2)
            return DONE
        }
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun smoke(@TestParameter useTestingEvaluator: Boolean) {
        val v1Sink = SkyValueSink()
        val v2Sink = SkyValueSink()
        val factory: java.util.function.Supplier<StateMachine?> =
            java.util.function.Supplier { TwoStepMachine(v1Sink, v2Sink) }
        if (useTestingEvaluator) {
            Truth.assertThat(runMachine(factory.get())).isTrue()
        } else {
            Truth.assertThat(evalMachine(factory)).isEqualTo(2)
        }
        assertThat(v1Sink.get()).isEqualTo(VALUE_A1)
        assertThat(v2Sink.get()).isEqualTo(VALUE_A2)
    }

    /** Example modeled after the one described in the documentation of [StateMachine].  */
    private class ExampleWithSubmachines(
        sinkA1: java.util.function.Consumer<SkyValue?>?,
        sinkA2: java.util.function.Consumer<SkyValue?>?,
        sinkA3: java.util.function.Consumer<SkyValue?>?,
        sinkB1: java.util.function.Consumer<SkyValue?>?,
        sinkB2: java.util.function.Consumer<SkyValue?>?,
        sinkB3: java.util.function.Consumer<SkyValue?>?
    ) : StateMachine, SkyKeyComputeState {
        private val sinkA1: java.util.function.Consumer<SkyValue?>?
        private val sinkA2: java.util.function.Consumer<SkyValue?>?
        private val sinkA3: java.util.function.Consumer<SkyValue?>?
        private val sinkB1: java.util.function.Consumer<SkyValue?>?
        private val sinkB2: java.util.function.Consumer<SkyValue?>?
        private val sinkB3: java.util.function.Consumer<SkyValue?>?

        init {
            this.sinkA1 = sinkA1
            this.sinkA2 = sinkA2
            this.sinkA3 = sinkA3
            this.sinkB1 = sinkB1
            this.sinkB2 = sinkB2
            this.sinkB3 = sinkB3
        }

        public override fun step(tasks: Tasks): StateMachine {
            // Starts submachines in parallel.
            tasks.enqueue({ tasks: Tasks -> this.stepA1(tasks) })
            tasks.enqueue({ tasks: Tasks -> this.stepB1(tasks) })
            return DONE
        }

        fun stepA1(tasks: Tasks): StateMachine {
            tasks.lookUp(KEY_A1, sinkA1)
            return StateMachine { tasks: Tasks -> this.stepA2(tasks) }
        }

        fun stepA2(tasks: Tasks): StateMachine {
            tasks.lookUp(KEY_A2, sinkA2)
            return StateMachine { tasks: Tasks -> this.stepA3(tasks) }
        }

        fun stepA3(tasks: Tasks): StateMachine {
            tasks.lookUp(KEY_A3, sinkA3)
            return DONE
        }

        fun stepB1(tasks: Tasks): StateMachine {
            tasks.lookUp(KEY_B1, sinkB1)
            return StateMachine { tasks: Tasks -> this.stepB2(tasks) }
        }

        fun stepB2(tasks: Tasks): StateMachine {
            tasks.lookUp(KEY_B2, sinkB2)
            return StateMachine { tasks: Tasks -> this.stepB3(tasks) }
        }

        fun stepB3(tasks: Tasks): StateMachine {
            tasks.lookUp(KEY_B3, sinkB3)
            return DONE
        }
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun parallelSubmachines_runInParallel(@TestParameter useTestingEvaluator: Boolean) {
        val a1Sink = SkyValueSink()
        val a2Sink = SkyValueSink()
        val a3Sink = SkyValueSink()
        val b1Sink = SkyValueSink()
        val b2Sink = SkyValueSink()
        val b3Sink = SkyValueSink()

        val factory: java.util.function.Supplier<StateMachine?> =
            java.util.function.Supplier { ExampleWithSubmachines(a1Sink, a2Sink, a3Sink, b1Sink, b2Sink, b3Sink) }
        if (useTestingEvaluator) {
            Truth.assertThat(runMachine(factory.get())).isTrue()
        } else {
            Truth.assertThat(evalMachine(factory)).isEqualTo(3)
        }

        assertThat(a1Sink.get()).isEqualTo(VALUE_A1)
        assertThat(a2Sink.get()).isEqualTo(VALUE_A2)
        assertThat(a3Sink.get()).isEqualTo(VALUE_A3)
        assertThat(b1Sink.get()).isEqualTo(VALUE_B1)
        assertThat(b2Sink.get()).isEqualTo(VALUE_B2)
        assertThat(b3Sink.get()).isEqualTo(VALUE_B3)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun parallelSubmachines_shorteningBothPathsReducesRestarts() {
        val a1Sink = SkyValueSink()
        val a2Sink = SkyValueSink()
        val a3Sink = SkyValueSink()
        val b1Sink = SkyValueSink()
        val b2Sink = SkyValueSink()
        val b3Sink = SkyValueSink()

        // Shortens both paths by 1, but at different execution steps.
        assertThat(eval<SkyValue?>(KEY_A1,  /* keepGoing= */false).get(KEY_A1)).isEqualTo(VALUE_A1)
        assertThat(eval<SkyValue?>(KEY_B3,  /* keepGoing= */false).get(KEY_B3)).isEqualTo(VALUE_B3)

        Truth.assertThat(
            evalMachine(
                java.util.function.Supplier { ExampleWithSubmachines(a1Sink, a2Sink, a3Sink, b1Sink, b2Sink, b3Sink) })
        )
            .isEqualTo(2)

        assertThat(a1Sink.get()).isEqualTo(VALUE_A1)
        assertThat(a2Sink.get()).isEqualTo(VALUE_A2)
        assertThat(a3Sink.get()).isEqualTo(VALUE_A3)
        assertThat(b1Sink.get()).isEqualTo(VALUE_B1)
        assertThat(b2Sink.get()).isEqualTo(VALUE_B2)
        assertThat(b3Sink.get()).isEqualTo(VALUE_B3)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun unhandledException(@TestParameter keepGoing: Boolean) {
        val a1Sink = SkyValueSink()
        val a2Sink = SkyValueSink()
        val a3Sink = SkyValueSink()
        val b1Sink = SkyValueSink()
        val b2Sink = SkyValueSink()
        val b3Sink = SkyValueSink()

        tester.getOrCreate(KEY_A1).unsetConstantValue().setHasError(true)

        val instantiationCount: AtomicInteger = AtomicInteger()
        val restartCount: AtomicInteger =
            defineRootMachine(
                java.util.function.Supplier {
                    instantiationCount.getAndIncrement()
                    ExampleWithSubmachines(a1Sink, a2Sink, a3Sink, b1Sink, b2Sink, b3Sink)
                })
        assertThat(eval<SkyValue?>(rootKey, keepGoing).getError(rootKey)).isNotNull()

        Truth.assertThat(restartCount.get()).isEqualTo(2)
        assertThat(a1Sink.get()).isNull()
        if (keepGoing) {
            // On restart, all values are processed before failing, so B1 is observed after restarting and
            // after A1's unhandled error.
            assertThat(b1Sink.get()).isEqualTo(VALUE_B1)
        }

        // In noKeepGoing, error bubbling resets the state cache and B1 is sometimes observed on the
        // first pass by a re-instantiated state machine. However, B1 can be slow and there is no
        // guarantee that it is available.
        assertThat(b2Sink.get()).isNull()

        if (keepGoing) {
            Truth.assertThat(instantiationCount.get()).isEqualTo(1)
        } else {
            // The state cache is dropped in noKeepGoing during error bubbling, resulting in a new
            // instantiation of the state machine.
            Truth.assertThat(instantiationCount.get()).isEqualTo(2)
        }
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun handledException(@TestParameter keepGoing: Boolean) {
        tester.getOrCreate(KEY_A1).unsetConstantValue().setHasError(true)

        val a1Sink = SkyValueSink()
        val errorSink: AtomicReference<SomeErrorException?> = AtomicReference<SomeErrorException?>()
        val restartCount: AtomicInteger =
            defineRootMachine(
                java.util.function.Supplier {
                    StateMachine { tasks ->
                        // Fully swallows the error.
                        tasks.lookUp(
                            KEY_A1,
                            SomeErrorException::class.java,
                            { v, e ->
                                if (v != null) {
                                    a1Sink.accept(v)
                                    return@lookUp
                                }
                                errorSink.set(e)
                            })
                        StateMachine.DONE
                    }
                })
        val result: EvaluationResult<T?> = eval<SkyValue?>(rootKey, keepGoing)
        if (keepGoing) {
            // In keepGoing mode, the swallowed error vanishes.
            assertThat(result.get(rootKey)).isEqualTo(DONE_VALUE)
            assertThat(result.hasError()).isFalse()
        } else {
            // In nokeepGoing mode, the error is processed in error bubbling, but the function does not
            // complete and the error is still propagated to the top level.
            assertThat(result.get(rootKey)).isNull()
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(KEY_A1)
        }
        Truth.assertThat(restartCount.get()).isEqualTo(1)
        assertThat(a1Sink.get()).isNull()
        Truth.assertThat(errorSink.get()).isNotNull()
    }

    private class StringOrExceptionProducer

        : ValueOrExceptionProducer<com.google.devtools.build.skyframe.GraphTester.StringValue?, SomeErrorException?>(),
        SkyKeyComputeState {
        public override fun step(tasks: Tasks): StateMachine {
            tasks.lookUp(
                KEY_A1,
                SomeErrorException::class.java,
                { v, e ->
                    if (v != null) {
                        setValue(v as com.google.devtools.build.skyframe.GraphTester.StringValue)
                        return@lookUp
                    }
                    setException(e)
                })
            return StateMachine { t ->
                isProcessValueOrExceptionCalled = true
                DONE
            }
        }

        companion object {
            // Static boolean isProcessValueOrExceptionCalled is added to verify StateMachine chained after
            // `step()` is invoked regardless of KEY_A1 looks up ends with a value or an exception.
            // See b/290998109#comment6.
            var isProcessValueOrExceptionCalled: Boolean = false
        }
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun valueOrExceptionProducer_propagatesValues() {
        tester
            .getOrCreate(rootKey)
            .setBuilder(
                SkyFunction { k, env ->
                    val producer: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        env.getState({ StringOrExceptionProducer() })
                    val value: SkyValue?
                    try {
                        if ((producer.tryProduceValue(env).also { value = it }) == null) {
                            return@setBuilder null
                        }
                    } catch (e: SomeErrorException) {
                        org.junit.Assert.fail("Unexpecteded exception: " + e)
                    }
                    DONE_VALUE
                })
        assertThat(eval<SkyValue?>(rootKey,  /* keepGoing= */false).get(rootKey)).isEqualTo(DONE_VALUE)
        Truth.assertThat(StringOrExceptionProducer.Companion.isProcessValueOrExceptionCalled).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun valueOrExceptionProducer_propagatesExceptions(@TestParameter keepGoing: Boolean) {
        val hasRestarted: AtomicBoolean = AtomicBoolean(false)
        tester.getOrCreate(KEY_A1).unsetConstantValue().setHasError(true)
        tester
            .getOrCreate(rootKey)
            .setBuilder(
                SkyFunction { k, env ->
                    val producer: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        env.getState({ StringOrExceptionProducer() })
                    if (!hasRestarted.getAndSet(true)) {
                        try {
                            // The first call returns null because a restart is needed to compute the
                            // requested key.
                            assertThat(producer.tryProduceValue(env)).isNull()
                        } catch (e: SomeErrorException) {
                            org.junit.Assert.fail("Unexpecteded exception: " + e)
                        }
                        return@setBuilder null
                    }
                    org.junit.Assert.assertThrows<SomeErrorException?>(
                        SomeErrorException::class.java,
                        org.junit.function.ThrowingRunnable { producer.tryProduceValue(env) })
                    DONE_VALUE
                })
        val result: EvaluationResult<T?> = eval<SkyValue?>(rootKey, keepGoing)
        if (keepGoing) {
            assertThat(result.get(rootKey)).isEqualTo(DONE_VALUE)
            assertThat(result.hasError()).isFalse()
        } else {
            assertThat(result.get(rootKey)).isNull()
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(KEY_A1)
        }
        Truth.assertThat(StringOrExceptionProducer.Companion.isProcessValueOrExceptionCalled).isTrue()
    }

    /**
     * This producer performs two concurrent lookups.
     * 
     * 
     * It is used to test the case where one of the two lookups succeeds with exception but the
     * other value is not available. The expected result is the exception propagates.
     * 
     * 
     * This scenario may occur during error bubbling.
     */
    private class TwoLookupProducer

        : ValueOrExceptionProducer<com.google.devtools.build.skyframe.GraphTester.StringValue?, SomeErrorException?>(),
        SkyKeyComputeState {
        public override fun step(tasks: Tasks): StateMachine {
            tasks.lookUp(KEY_A1, { unusedValue -> org.junit.Assert.fail("should not be reachable") })
            tasks.lookUp(
                KEY_A2,
                SomeErrorException::class.java,
                { v, e ->
                    if (v != null) {
                        setValue(v as com.google.devtools.build.skyframe.GraphTester.StringValue)
                        return@lookUp
                    }
                    setException(e)
                })
            return DONE
        }
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun valueOrExceptionProducer_throwsExceptionsEvenWithIncompleteDeps() {
        val hasRestarted: AtomicBoolean = AtomicBoolean(false)
        val gotError: AtomicBoolean = AtomicBoolean(false)
        tester.getOrCreate(KEY_A2).unsetConstantValue().setHasError(true)
        tester
            .getOrCreate(rootKey)
            .setBuilder(
                SkyFunction { unusedKey, env ->
                    // Primes KEY_A2, making the error available.
                    if (!hasRestarted.getAndSet(true)) {
                        assertThat(env.getValue(KEY_A2)).isNull()
                        return@setBuilder null
                    }
                    val producer: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        env.getState({ TwoLookupProducer() })
                    // At this point, KEY_A2 is available but KEY_A1 is not. The state machine is in an
                    // incomplete state, but throws the exception anyway.
                    val error: SomeErrorException? =
                        org.junit.Assert.assertThrows<SomeErrorException?>(
                            SomeErrorException::class.java,
                            org.junit.function.ThrowingRunnable { producer.tryProduceValue(env) })
                    gotError.set(true)
                    throw GenericFunctionException(error)
                })
        // keepGoing must be false below, otherwise the state machine will be run a second time when
        // KEY_A1 becomes available.
        val result: EvaluationResult<T?> = eval<SkyValue?>(rootKey,  /* keepGoing= */false)
        Truth.assertThat(gotError.get()).isTrue()
        assertThat(result.get(rootKey)).isNull()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(KEY_A2)
    }

    private class SomeErrorException1(msg: String?) : SomeErrorException(msg)

    private class SomeErrorException2(msg: String?) : SomeErrorException(msg)

    private class SomeErrorException3(msg: String?) : SomeErrorException(msg)

    private class StringOrException2Producer

        :
        ValueOrException2Producer<com.google.devtools.build.skyframe.GraphTester.StringValue?, SomeErrorException1?, SomeErrorException2?>(),
        SkyKeyComputeState {
        public override fun step(tasks: Tasks): StateMachine {
            tasks.lookUp(
                KEY_A1,
                SomeErrorException::class.java,
                { v, e ->
                    if (e != null) {
                        setException1(SomeErrorException1(e.getMessage()))
                    }
                })
            tasks.lookUp(
                KEY_B1,
                SomeErrorException::class.java,
                { v, e ->
                    if (e != null) {
                        setException2(SomeErrorException2(e.getMessage()))
                    }
                })
            return StateMachine { t ->
                if (exception1 == null && exception2 == null) {
                    setValue(SUCCESS_VALUE)
                }
                DONE
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun valueOrException2Producer_propagatesValues() {
        tester
            .getOrCreate(rootKey)
            .setBuilder(
                SkyFunction { k, env ->
                    val producer: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        env.getState({ StringOrException2Producer() })
                    val value: SkyValue?
                    try {
                        if ((producer.tryProduceValue(env).also { value = it }) == null) {
                            return@setBuilder null
                        }
                        assertThat(value).isEqualTo(SUCCESS_VALUE)
                    } catch (e: SomeErrorException) {
                        org.junit.Assert.fail("Unexpecteded exception: " + e)
                    }
                    DONE_VALUE
                })
        assertThat(eval<SkyValue?>(rootKey,  /* keepGoing= */false).get(rootKey)).isEqualTo(DONE_VALUE)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun valueOrException2Producer_propagatesExceptions(
        @TestParameter trueForException1: Boolean, @TestParameter keepGoing: Boolean
    ) {
        val hasRestarted: AtomicBoolean = AtomicBoolean(false)
        val errorKey: SkyKey? = if (trueForException1) KEY_A1 else KEY_B1
        tester.getOrCreate(errorKey).unsetConstantValue().setHasError(true)
        tester
            .getOrCreate(rootKey)
            .setBuilder(
                SkyFunction { k, env ->
                    val producer: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        env.getState({ StringOrException2Producer() })
                    if (!hasRestarted.getAndSet(true)) {
                        try {
                            assertThat(producer.tryProduceValue(env)).isNull()
                        } catch (e: SomeErrorException) {
                            org.junit.Assert.fail("Unexpecteded exception: " + e)
                        }
                        return@setBuilder null
                    }
                    if (trueForException1) {
                        org.junit.Assert.assertThrows<SomeErrorException1?>(
                            SomeErrorException1::class.java,
                            org.junit.function.ThrowingRunnable { producer.tryProduceValue(env) })
                    } else {
                        org.junit.Assert.assertThrows<SomeErrorException2?>(
                            SomeErrorException2::class.java,
                            org.junit.function.ThrowingRunnable { producer.tryProduceValue(env) })
                    }
                    DONE_VALUE
                })
        val result: EvaluationResult<T?> = eval<SkyValue?>(rootKey, keepGoing)
        if (keepGoing) {
            assertThat(result.get(rootKey)).isEqualTo(DONE_VALUE)
            assertThat(result.hasError()).isFalse()
        } else {
            assertThat(result.get(rootKey)).isNull()
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(errorKey)
        }
    }

    /**
     * [.valueOrException2Producer_singleLookup_propagatesValuesAndInvokesRunAfter] and [ ][.valueOrException2Producer_singleLookup_propagatesExceptionsAndInvokesRunAfter] are added in
     * order to verify that if looking up the SkyKey throws an exception, the runAfter [ ] defined as the return of [StringOrException2ProducerWithSingleLookup.step]
     * is invoked.
     * 
     * 
     * These tests are designed not to be integrated into [ ][.valueOrException2Producer_propagatesValues] and [ ][.valueOrException2Producer_propagatesExceptions]. The reason is that only when [ ][Driver.drive] looks up **one** newly added [SkyKey], will [Lookup.doLookup] be
     * called. And these tests aim at covering calling this method.
     * 
     * 
     * Similar tests for [ValueOrException3Producer] are also added below.
     * 
     * 
     * See b/290998109#comment6 for more details.
     */
    private class StringOrException2ProducerWithSingleLookup

        :
        ValueOrException2Producer<com.google.devtools.build.skyframe.GraphTester.StringValue?, SomeErrorException1?, SomeErrorException2?>(),
        SkyKeyComputeState {
        public override fun step(tasks: Tasks): StateMachine {
            tasks.lookUp(
                KEY_A1,
                SomeErrorException1::class.java,
                SomeErrorException2::class.java,
                { v, e1, e2 ->
                    if (v != null) {
                        setValue(v as com.google.devtools.build.skyframe.GraphTester.StringValue)
                    }
                    if (e1 != null) {
                        setException1(SomeErrorException1(e1.getMessage()))
                    }
                    if (e2 != null) {
                        setException2(SomeErrorException2(e2.getMessage()))
                    }
                })
            return StateMachine { t ->
                if (exception1 == null && exception2 == null) {
                    setValue(SUCCESS_VALUE)
                }
                isProcessValueOrExceptionCalled = true
                DONE
            }
        }

        companion object {
            var isProcessValueOrExceptionCalled: Boolean = false
        }
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun valueOrException2Producer_singleLookup_propagatesValuesAndInvokesRunAfter() {
        tester
            .getOrCreate(rootKey)
            .setBuilder(
                SkyFunction { k, env ->
                    val producer: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        env.getState({ StringOrException2ProducerWithSingleLookup() })
                    val value: SkyValue?
                    try {
                        if ((producer.tryProduceValue(env).also { value = it }) == null) {
                            return@setBuilder null
                        }
                        assertThat(value).isEqualTo(SUCCESS_VALUE)
                    } catch (e: SomeErrorException) {
                        org.junit.Assert.fail("Unexpecteded exception: " + e)
                    }
                    DONE_VALUE
                })
        assertThat(eval<SkyValue?>(rootKey,  /* keepGoing= */false).get(rootKey)).isEqualTo(DONE_VALUE)
        Truth.assertThat(StringOrException2ProducerWithSingleLookup.Companion.isProcessValueOrExceptionCalled).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun valueOrException2Producer_singleLookup_propagatesExceptionsAndInvokesRunAfter(
        @TestParameter trueForException1: Boolean
    ) {
        val hasRestarted: AtomicBoolean = AtomicBoolean(false)
        tester
            .getOrCreate(KEY_A1)
            .unsetConstantValue()
            .setBuilder(
                SkyFunction { k, env ->
                    throw ExceptionWrapper(
                        if (trueForException1)
                            SomeErrorException1("Exception 1")
                        else
                            SomeErrorException2("Exception 2")
                    )
                })

        tester
            .getOrCreate(rootKey)
            .setBuilder(
                SkyFunction { k, env ->
                    val producer: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        env.getState({ StringOrException2ProducerWithSingleLookup() })
                    if (!hasRestarted.getAndSet(true)) {
                        try {
                            assertThat(producer.tryProduceValue(env)).isNull()
                        } catch (e: SomeErrorException) {
                            org.junit.Assert.fail("Unexpecteded exception: " + e)
                        }
                        return@setBuilder null
                    }
                    if (trueForException1) {
                        org.junit.Assert.assertThrows<SomeErrorException1?>(
                            SomeErrorException1::class.java,
                            org.junit.function.ThrowingRunnable { producer.tryProduceValue(env) })
                    } else {
                        org.junit.Assert.assertThrows<SomeErrorException2?>(
                            SomeErrorException2::class.java,
                            org.junit.function.ThrowingRunnable { producer.tryProduceValue(env) })
                    }
                    DONE_VALUE
                })

        val result: EvaluationResult<T?> = eval<SkyValue?>(rootKey,  /* keepGoing= */false)

        assertThat(result.get(rootKey)).isNull()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(KEY_A1)
        Truth.assertThat(StringOrException2ProducerWithSingleLookup.Companion.isProcessValueOrExceptionCalled).isTrue()
    }

    private class StringOrException3Producer

        :
        ValueOrException3Producer<com.google.devtools.build.skyframe.GraphTester.StringValue?, SomeErrorException1?, SomeErrorException2?, SomeErrorException3?>(),
        SkyKeyComputeState {
        public override fun step(tasks: Tasks): StateMachine {
            tasks.lookUp(
                KEY_A1,
                SomeErrorException::class.java,
                { v, e ->
                    if (e != null) {
                        setException1(SomeErrorException1(e.getMessage()))
                    }
                })
            tasks.lookUp(
                KEY_A2,
                SomeErrorException::class.java,
                { v, e ->
                    if (e != null) {
                        setException2(SomeErrorException2(e.getMessage()))
                    }
                })
            tasks.lookUp(
                KEY_A3,
                SomeErrorException::class.java,
                { v, e ->
                    if (e != null) {
                        setException3(SomeErrorException3(e.getMessage()))
                    }
                })
            return StateMachine { t ->
                if (exception1 == null && exception2 == null && exception3 == null) {
                    setValue(SUCCESS_VALUE)
                }
                DONE
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun valueOrException3Producer_propagatesValues() {
        tester
            .getOrCreate(rootKey)
            .setBuilder(
                SkyFunction { k, env ->
                    val producer: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        env.getState({ StringOrException3Producer() })
                    val value: SkyValue?
                    try {
                        if ((producer.tryProduceValue(env).also { value = it }) == null) {
                            return@setBuilder null
                        }
                        assertThat(value).isEqualTo(SUCCESS_VALUE)
                    } catch (e: SomeErrorException) {
                        org.junit.Assert.fail("Unexpecteded exception: " + e)
                    }
                    DONE_VALUE
                })
        assertThat(eval<SkyValue?>(rootKey,  /* keepGoing= */false).get(rootKey)).isEqualTo(DONE_VALUE)
    }

    internal enum class ValueOrException3ExceptionCase {
        ONE {
            override fun errorKey(): SkyKey {
                return KEY_A1
            }
        },
        TWO {
            override fun errorKey(): SkyKey {
                return KEY_A2
            }
        },
        THREE {
            override fun errorKey(): SkyKey {
                return KEY_A3
            }
        };

        abstract fun errorKey(): SkyKey?
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun valueOrException3Producer_propagatesExceptions(
        @TestParameter exceptionCase: ValueOrException3ExceptionCase, @TestParameter keepGoing: Boolean
    ) {
        val hasRestarted: AtomicBoolean = AtomicBoolean(false)
        val errorKey: SkyKey? = exceptionCase.errorKey()
        tester.getOrCreate(errorKey).unsetConstantValue().setHasError(true)
        tester
            .getOrCreate(rootKey)
            .setBuilder(
                SkyFunction { k, env ->
                    val producer: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        env.getState({ StringOrException3Producer() })
                    if (!hasRestarted.getAndSet(true)) {
                        try {
                            assertThat(producer.tryProduceValue(env)).isNull()
                        } catch (e: SomeErrorException) {
                            org.junit.Assert.fail("Unexpecteded exception: " + e)
                        }
                        return@setBuilder null
                    }
                    when (exceptionCase) {
                        ValueOrException3ExceptionCase.ONE -> org.junit.Assert.assertThrows<SomeErrorException1?>(
                            SomeErrorException1::class.java,
                            org.junit.function.ThrowingRunnable { producer.tryProduceValue(env) })

                        ValueOrException3ExceptionCase.TWO -> org.junit.Assert.assertThrows<SomeErrorException2?>(
                            SomeErrorException2::class.java,
                            org.junit.function.ThrowingRunnable { producer.tryProduceValue(env) })

                        ValueOrException3ExceptionCase.THREE -> org.junit.Assert.assertThrows<SomeErrorException3?>(
                            SomeErrorException3::class.java,
                            org.junit.function.ThrowingRunnable { producer.tryProduceValue(env) })
                    }
                    DONE_VALUE
                })
        val result: EvaluationResult<T?> = eval<SkyValue?>(rootKey, keepGoing)
        if (keepGoing) {
            assertThat(result.get(rootKey)).isEqualTo(DONE_VALUE)
            assertThat(result.hasError()).isFalse()
        } else {
            assertThat(result.get(rootKey)).isNull()
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(errorKey)
        }
    }

    /** See the comments above [StringOrException2ProducerWithSingleLookup] for more details.  */
    private class StringOrException3ProducerWithSingleLookup

        :
        ValueOrException3Producer<com.google.devtools.build.skyframe.GraphTester.StringValue?, SomeErrorException1?, SomeErrorException2?, SomeErrorException3?>(),
        SkyKeyComputeState {
        public override fun step(tasks: Tasks): StateMachine {
            tasks.lookUp(
                KEY_A1,
                SomeErrorException1::class.java,
                SomeErrorException2::class.java,
                SomeErrorException3::class.java,
                { v, e1, e2, e3 ->
                    if (v != null) {
                        setValue(v as com.google.devtools.build.skyframe.GraphTester.StringValue)
                    }
                    if (e1 != null) {
                        setException1(SomeErrorException1(e1.getMessage()))
                    }
                    if (e2 != null) {
                        setException2(SomeErrorException2(e2.getMessage()))
                    }
                    if (e3 != null) {
                        setException3(SomeErrorException3(e3.getMessage()))
                    }
                })
            return StateMachine { t ->
                if (exception1 == null && exception2 == null && exception3 == null) {
                    setValue(SUCCESS_VALUE)
                }
                isProcessValueOrExceptionCalled = true
                DONE
            }
        }

        companion object {
            var isProcessValueOrExceptionCalled: Boolean = false
        }
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun valueOrException3Producer_singleLookup_propagatesValues() {
        tester
            .getOrCreate(rootKey)
            .setBuilder(
                SkyFunction { k, env ->
                    val producer: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        env.getState({ StringOrException3ProducerWithSingleLookup() })
                    val value: SkyValue?
                    try {
                        if ((producer.tryProduceValue(env).also { value = it }) == null) {
                            return@setBuilder null
                        }
                        assertThat(value).isEqualTo(SUCCESS_VALUE)
                    } catch (e: SomeErrorException) {
                        org.junit.Assert.fail("Unexpecteded exception: " + e)
                    }
                    DONE_VALUE
                })
        assertThat(eval<SkyValue?>(rootKey,  /* keepGoing= */false).get(rootKey)).isEqualTo(DONE_VALUE)
        Truth.assertThat(StringOrException3ProducerWithSingleLookup.Companion.isProcessValueOrExceptionCalled).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun valueOrException3Producer_singleLookup_propagatesExceptionsAndExecuteRunAfter(
        @TestParameter exceptionCase: ValueOrException3ExceptionCase
    ) {
        val hasRestarted: AtomicBoolean = AtomicBoolean(false)
        tester
            .getOrCreate(KEY_A1)
            .unsetConstantValue()
            .setBuilder(
                SkyFunction { k, env ->
                    var exception: java.lang.Exception? = null
                    when (exceptionCase) {
                        ValueOrException3ExceptionCase.ONE -> exception = SomeErrorException1("Exception 1")
                        ValueOrException3ExceptionCase.TWO -> exception = SomeErrorException2("Exception 2")
                        ValueOrException3ExceptionCase.THREE -> exception = SomeErrorException3("Exception 3")
                    }
                    throw ExceptionWrapper(exception)
                })

        tester
            .getOrCreate(rootKey)
            .setBuilder(
                SkyFunction { k, env ->
                    val producer: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        env.getState({ StringOrException3ProducerWithSingleLookup() })
                    if (!hasRestarted.getAndSet(true)) {
                        try {
                            assertThat(producer.tryProduceValue(env)).isNull()
                        } catch (e: SomeErrorException) {
                            org.junit.Assert.fail("Unexpecteded exception: " + e)
                        }
                        return@setBuilder null
                    }
                    when (exceptionCase) {
                        ValueOrException3ExceptionCase.ONE -> org.junit.Assert.assertThrows<SomeErrorException1?>(
                            SomeErrorException1::class.java,
                            org.junit.function.ThrowingRunnable { producer.tryProduceValue(env) })

                        ValueOrException3ExceptionCase.TWO -> org.junit.Assert.assertThrows<SomeErrorException2?>(
                            SomeErrorException2::class.java,
                            org.junit.function.ThrowingRunnable { producer.tryProduceValue(env) })

                        ValueOrException3ExceptionCase.THREE -> org.junit.Assert.assertThrows<SomeErrorException3?>(
                            SomeErrorException3::class.java,
                            org.junit.function.ThrowingRunnable { producer.tryProduceValue(env) })
                    }
                    DONE_VALUE
                })

        val result: EvaluationResult<T?> = eval<SkyValue?>(rootKey,  /* keepGoing= */false)

        assertThat(result.get(rootKey)).isNull()
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(KEY_A1)
        Truth.assertThat(StringOrException3ProducerWithSingleLookup.Companion.isProcessValueOrExceptionCalled).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun lookupValue_matrix(
        @TestParameter lookupType: LookupType,
        @TestParameter useBatch: Boolean,
        @TestParameter useTestingEvaluator: Boolean
    ) {
        val sink = OmniSink()
        val rootSupplier: java.util.function.Supplier<StateMachine?> =
            java.util.function.Supplier {
                val lookup: StateMachine? = lookupType.newLookup(KEY_A1, sink)
                if (!useBatch) {
                    return@Supplier lookup
                }
                BatchPair(lookup)
            }
        if (useTestingEvaluator) {
            Truth.assertThat(runMachine(rootSupplier.get())).isTrue()
        } else {
            val unused: AtomicInteger = defineRootMachine(rootSupplier)
            // There are no errors in this test so the keepGoing value is arbitrary.
            assertThat(eval<SkyValue?>(rootKey,  /* keepGoing= */true).get(rootKey)).isEqualTo(DONE_VALUE)
        }
        assertThat(sink.value).isEqualTo(VALUE_A1)
        Truth.assertThat(sink.exception).isNull()
    }

    internal enum class EvaluationMode {
        NO_KEEP_GOING,
        KEEP_GOING,
        TEST_EVALUATOR,
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun lookupErrors_matrix(
        @TestParameter lookupType: LookupType,
        @TestParameter exceptionCase: ExceptionCase,
        @TestParameter useBatch: Boolean,
        @TestParameter evaluationMode: EvaluationMode
    ) {
        val exception: java.lang.Exception? = exceptionCase.exception
        tester
            .getOrCreate(KEY_A1)
            .unsetConstantValue()
            .setBuilder(
                SkyFunction { k, env ->
                    throw ExceptionWrapper(exception)
                })
        val sink = OmniSink()
        val rootSupplier: java.util.function.Supplier<StateMachine?> =
            java.util.function.Supplier {
                val lookup: StateMachine? = lookupType.newLookup(KEY_A1, sink)
                if (!useBatch) {
                    return@Supplier lookup
                }
                BatchPair(lookup)
            }

        var keepGoing = false
        when (evaluationMode) {
            EvaluationMode.TEST_EVALUATOR -> {
                Truth.assertThat(runMachine(rootSupplier.get())).isFalse()
                if (exceptionCase.exceptionOrdinal() > lookupType.exceptionCount()) {
                    // Undeclared exception is not handled.
                    Truth.assertThat(sink.exception).isNull()
                } else {
                    // Declared exception is captured.
                    Truth.assertThat(sink.exception).isEqualTo(exception)
                }
                return
            }

            EvaluationMode.KEEP_GOING -> keepGoing = true
            EvaluationMode.NO_KEEP_GOING -> {}
        }

        val unused: AtomicInteger = defineRootMachine(rootSupplier)
        val result: EvaluationResult<T?> = eval<SkyValue?>(rootKey, keepGoing)
        assertThat(sink.value).isNull()
        if (exceptionCase.exceptionOrdinal() > lookupType.exceptionCount()) {
            // The exception was not handled.
            Truth.assertThat(sink.exception).isNull()
            assertThat(result.get(rootKey)).isNull()
            EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(KEY_A1)
            return
        }
        Truth.assertThat(sink.exception).isEqualTo(exception)
        if (keepGoing) {
            // The error is completely handled.
            assertThat(result.get(rootKey)).isEqualTo(DONE_VALUE)
            return
        }
        EvaluationResultSubjectFactory.Companion.assertThatEvaluationResult(result).hasSingletonErrorThat(KEY_A1)
        assertThat(result.get(rootKey)).isNull()
    }

    /**
     * Sink for [SkyValue]s.
     * 
     * 
     * Verifies that the value is set no more than once.
     */
    private class SkyValueSink : java.util.function.Consumer<SkyValue?> {
        private var value: SkyValue? = null

        override fun accept(value: SkyValue?) {
            assertThat(this.value).isNull()
            this.value = value
        }

        fun get(): SkyValue? {
            return value
        }
    }

    // -------------------- Helpers for lookupErrors_matrix --------------------
    private class Exception1 : java.lang.Exception()

    private class Exception2 : java.lang.Exception()

    private class Exception3 : java.lang.Exception()

    private class Exception4 : java.lang.Exception()

    private class ExceptionWrapper(e: java.lang.Exception?) : SkyFunctionException(e, Transience.PERSISTENT)

    /**
     * Adds a secondary lookup in parallel with a given [StateMachine].
     * 
     * 
     * This causes the [Environment.getValuesAndExceptions] codepath in [Driver.drive]
     * to be used instead of the [Lookup.doLookup] when there is a single lookup.
     */
    private class BatchPair(other: StateMachine?) : StateMachine {
        private val other: StateMachine?

        init {
            this.other = other
        }

        public override fun step(tasks: Tasks): StateMachine {
            tasks.enqueue(other)
            tasks.lookUp(KEY_B1, { v -> assertThat(v).isEqualTo(VALUE_B1) })
            return DONE
        }
    }

    private class Lookup0(key: SkyKey?, sink: java.util.function.Consumer<SkyValue?>?) : StateMachine {
        private val key: SkyKey?
        private val sink: java.util.function.Consumer<SkyValue?>?

        init {
            this.key = key
            this.sink = sink
        }

        public override fun step(tasks: Tasks): StateMachine {
            tasks.lookUp(key, sink)
            return DONE
        }
    }

    private class Lookup1(key: SkyKey?, sink: ValueOrExceptionSink<Exception1?>?) : StateMachine {
        private val key: SkyKey?
        private val sink: ValueOrExceptionSink<Exception1?>?

        init {
            this.key = key
            this.sink = sink
        }

        public override fun step(tasks: Tasks): StateMachine {
            tasks.lookUp(key, Exception1::class.java, sink)
            return DONE
        }
    }

    private class Lookup2(key: SkyKey?, sink: ValueOrException2Sink<Exception1?, Exception2?>?) : StateMachine {
        private val key: SkyKey?
        private val sink: ValueOrException2Sink<Exception1?, Exception2?>?

        init {
            this.key = key
            this.sink = sink
        }

        public override fun step(tasks: Tasks): StateMachine {
            tasks.lookUp(key, Exception1::class.java, Exception2::class.java, sink)
            return DONE
        }
    }

    private class Lookup3(key: SkyKey?, sink: ValueOrException3Sink<Exception1?, Exception2?, Exception3?>?) :
        StateMachine {
        private val key: SkyKey?
        private val sink: ValueOrException3Sink<Exception1?, Exception2?, Exception3?>?

        init {
            this.key = key
            this.sink = sink
        }

        public override fun step(tasks: Tasks): StateMachine {
            tasks.lookUp(key, Exception1::class.java, Exception2::class.java, Exception3::class.java, sink)
            return DONE
        }
    }

    private class OmniSink

        : java.util.function.Consumer<SkyValue?>, StateMachine.ValueOrExceptionSink<Exception1?>,
        StateMachine.ValueOrException2Sink<Exception1?, Exception2?>,
        StateMachine.ValueOrException3Sink<Exception1?, Exception2?, Exception3?> {
        private var value: SkyValue? = null
        private var exception: java.lang.Exception? = null

        override fun accept(value: SkyValue?) {
            com.google.common.base.Preconditions.checkState(this.value == null && exception == null)
            this.value = com.google.common.base.Preconditions.checkNotNull<SkyValue?>(value)
        }

        public override fun acceptValueOrException(value: SkyValue?, exception1: Exception1?) {
            com.google.common.base.Preconditions.checkState(this.value == null && exception == null)
            if (value != null) {
                this.value = value
                return
            }
            if (exception1 != null) {
                com.google.common.base.Preconditions.checkState(value == null)
                this.exception = exception1
            }
        }

        public override fun acceptValueOrException2(
            value: SkyValue?,
            exception1: Exception1?,
            exception2: Exception2?
        ) {
            com.google.common.base.Preconditions.checkState(this.value == null && exception == null)
            if (value != null) {
                com.google.common.base.Preconditions.checkState(exception1 == null && exception2 == null)
                this.value = value
                return
            }
            if (exception1 != null) {
                com.google.common.base.Preconditions.checkState(value == null && exception2 == null)
                this.exception = exception1
                return
            }
            if (exception2 != null) {
                com.google.common.base.Preconditions.checkState(value == null && exception1 == null)
                this.exception = exception2
            }
        }

        public override fun acceptValueOrException3(
            value: SkyValue?,
            exception1: Exception1?,
            exception2: Exception2?,
            exception3: Exception3?
        ) {
            com.google.common.base.Preconditions.checkState(this.value == null && exception == null)
            if (value != null) {
                com.google.common.base.Preconditions.checkState(exception1 == null && exception2 == null && exception3 == null)
                this.value = value
                return
            }
            if (exception1 != null) {
                com.google.common.base.Preconditions.checkState(value == null && exception2 == null && exception3 == null)
                this.exception = exception1
                return
            }
            if (exception2 != null) {
                com.google.common.base.Preconditions.checkState(value == null && exception1 == null && exception3 == null)
                this.exception = exception2
                return
            }
            if (exception3 != null) {
                com.google.common.base.Preconditions.checkState(value == null && exception1 == null && exception2 == null)
                this.exception = exception3
            }
        }
    }

    private enum class LookupType {
        LOOKUP0 {
            override fun newLookup(key: SkyKey?, sink: OmniSink?): StateMachine? {
                return Lookup0(key, sink)
            }

            override fun exceptionCount(): Int {
                return 0
            }
        },
        LOOKUP1 {
            override fun newLookup(key: SkyKey?, sink: OmniSink?): StateMachine? {
                return Lookup1(key, sink)
            }

            override fun exceptionCount(): Int {
                return 1
            }
        },
        LOOKUP2 {
            override fun newLookup(key: SkyKey?, sink: OmniSink?): StateMachine? {
                return Lookup2(key, sink)
            }

            override fun exceptionCount(): Int {
                return 2
            }
        },
        LOOKUP3 {
            override fun newLookup(key: SkyKey?, sink: OmniSink?): StateMachine? {
                return Lookup3(key, sink)
            }

            override fun exceptionCount(): Int {
                return 3
            }
        };

        abstract fun newLookup(key: SkyKey?, sink: OmniSink?): StateMachine?

        abstract fun exceptionCount(): Int
    }

    private enum class ExceptionCase {
        EXCEPTION1 {
            override fun getException(): java.lang.Exception {
                return Exception1()
            }

            override fun exceptionOrdinal(): Int {
                return 1
            }
        },
        EXCEPTION2 {
            override fun getException(): java.lang.Exception {
                return Exception2()
            }

            override fun exceptionOrdinal(): Int {
                return 2
            }
        },
        EXCEPTION3 {
            override fun getException(): java.lang.Exception {
                return Exception3()
            }

            override fun exceptionOrdinal(): Int {
                return 3
            }
        },
        EXCEPTION4 {
            override fun getException(): java.lang.Exception {
                return Exception4()
            }

            override fun exceptionOrdinal(): Int {
                return 4
            }
        };

        abstract val exception: java.lang.Exception?

        abstract fun exceptionOrdinal(): Int
    }

    private class StateMachineWithMultipleConcurrentDriverWrapper
        (stateMachines: MutableList<StateMachine?>) : SkyKeyComputeState {
        private val drivers: MutableList<Driver> = java.util.ArrayList<Driver>()

        init {
            for (stateMachine in stateMachines) {
                drivers.add(Driver(stateMachine))
            }
        }

        @Throws(java.lang.InterruptedException::class)
        fun drive(env: LookupEnvironment?): Boolean {
            val executor: ExecutorService = Executors.newFixedThreadPool(4)
            val allCompletes: AtomicBoolean = AtomicBoolean(true)
            val concurrentEnvironment: ConcurrentSkyFunctionEnvironment =
                ConcurrentSkyFunctionEnvironment(env as SkyFunctionEnvironment?)
            for (driver in drivers) {
                val unused: java.util.concurrent.Future<*>? =
                    executor.submit(
                        java.lang.Runnable {
                            try {
                                if (!driver.drive(concurrentEnvironment)) {
                                    allCompletes.set(false)
                                }
                            } catch (e: java.lang.InterruptedException) {
                                throw java.lang.AssertionError("No exception is expected to be thrown", e)
                            }
                        })
            }

            executor.shutdown()
            executor.awaitTermination(Long.Companion.MAX_VALUE, TimeUnit.NANOSECONDS)
            return allCompletes.get()
        }
    }

    private fun defineRootMachineWithMultipleDriver(
        rootMachineSupplier: java.util.function.Supplier<MutableList<StateMachine?>?>
    ): AtomicInteger {
        val restartCount: AtomicInteger = AtomicInteger()
        tester
            .getOrCreate(rootKey)
            .setBuilder(
                SkyFunction { k, env ->
                    if (!env.getState(
                            {
                                StateMachineWithMultipleConcurrentDriverWrapper(
                                    rootMachineSupplier.get()
                                )
                            })
                            .drive(env)
                    ) {
                        restartCount.getAndIncrement()
                        return@setBuilder null
                    }
                    DONE_VALUE
                })
        return restartCount
    }

    @Throws(java.lang.InterruptedException::class)
    private fun evalMachineWithMultipleDrivers(rootMachineSupplier: java.util.function.Supplier<MutableList<StateMachine?>?>): Int {
        val restartCount: AtomicInteger = defineRootMachineWithMultipleDriver(rootMachineSupplier)
        assertThat(eval<SkyValue?>(rootKey,  /* keepGoing= */false).get(rootKey)).isEqualTo(DONE_VALUE)
        return restartCount.get()
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun test_multipleStateMachinesInParallelDriver() {
        for (i in 0..99) {
            graph.remove(rootKey)
            graph.remove(KEY_A1)
            graph.remove(KEY_A2)
            val v1Sink = SkyValueSink()
            val v2Sink = SkyValueSink()
            val v3Sink = SkyValueSink()
            val v4Sink = SkyValueSink()
            val v5Sink = SkyValueSink()
            val v6Sink = SkyValueSink()
            val factory: java.util.function.Supplier<MutableList<StateMachine?>?> =
                java.util.function.Supplier {
                    java.util.Arrays.asList<T?>(
                        TwoStepMachine(v1Sink, v2Sink),
                        TwoStepMachine(v3Sink, v4Sink),
                        TwoStepMachine(v5Sink, v6Sink)
                    )
                }
            Truth.assertThat(evalMachineWithMultipleDrivers(factory)).isEqualTo(2)
        }
    }

    companion object {
        private const val TEST_PARALLELISM = 5

        private val VERSION: Version? = IntVersion.of(0)

        private val KEY_A1: SkyKey = GraphTester.Companion.skyKey("A1")
        private val VALUE_A1: SkyValue = com.google.devtools.build.skyframe.GraphTester.StringValue("A1")
        private val KEY_A2: SkyKey = GraphTester.Companion.skyKey("A2")
        private val VALUE_A2: SkyValue = com.google.devtools.build.skyframe.GraphTester.StringValue("A2")
        private val KEY_A3: SkyKey = GraphTester.Companion.skyKey("A3")
        private val VALUE_A3: SkyValue = com.google.devtools.build.skyframe.GraphTester.StringValue("A3")
        private val KEY_B1: SkyKey = GraphTester.Companion.skyKey("B1")
        private val VALUE_B1: SkyValue = com.google.devtools.build.skyframe.GraphTester.StringValue("B1")
        private val KEY_B2: SkyKey = GraphTester.Companion.skyKey("B2")
        private val VALUE_B2: SkyValue = com.google.devtools.build.skyframe.GraphTester.StringValue("B2")
        private val KEY_B3: SkyKey = GraphTester.Companion.skyKey("B3")
        private val VALUE_B3: SkyValue = com.google.devtools.build.skyframe.GraphTester.StringValue("B3")

        private val DONE_VALUE: SkyValue = com.google.devtools.build.skyframe.GraphTester.StringValue("DONE")
        private val SUCCESS_VALUE: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("SUCCESS")
    }
}
