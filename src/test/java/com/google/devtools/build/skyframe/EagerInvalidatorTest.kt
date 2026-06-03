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

import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor

/**
 * Tests for [InvalidatingNodeVisitor].
 */
@RunWith(Enclosed::class)
open class EagerInvalidatorTest {
    protected var graph: InMemoryGraphImpl? = null
    protected var tester: GraphTester? = GraphTester()
    protected var state: InvalidationState = newInvalidationState()
    protected var visitor: AtomicReference<InvalidatingNodeVisitor<*>?> = AtomicReference<InvalidatingNodeVisitor<*>?>()
    protected var progressReceiver: DirtyAndInflightTrackingProgressReceiver? = null
    private var graphVersion: IntVersion = IntVersion.of(0)

    @org.junit.After
    fun assertNoTrackedErrors() {
        TrackingAwaiter.Companion.INSTANCE.assertNoErrors()
    }

    // The following three methods should be abstract, but junit4 does not allow us to run inner
    // classes in an abstract outer class. Thus, we provide implementations. These methods will never
    // be run because only the inner classes, annotated with @RunWith, will actually be executed.
    open fun expectedState(): InvalidationState? {
        throw java.lang.UnsupportedOperationException()
    }

    @Suppress("unused")
    @Throws(java.lang.InterruptedException::class)
    open fun invalidate(
        graph: InMemoryGraph?,
        progressReceiver: DirtyAndInflightTrackingProgressReceiver?,
        vararg keys: SkyKey?
    ) {
        throw java.lang.UnsupportedOperationException()
    }

    open fun gcExpected(): Boolean {
        throw java.lang.UnsupportedOperationException()
    }

    private fun isInvalidated(key: SkyKey?): Boolean {
        val entry: NodeEntry? = graph.get(null, Reason.OTHER, key)
        if (gcExpected()) {
            return entry == null
        } else {
            return entry == null || entry.isDirty()
        }
    }

    private fun assertChanged(key: SkyKey?) {
        val entry: NodeEntry = graph.get(null, Reason.OTHER, key)
        if (gcExpected()) {
            assertThat(entry).isNull()
        } else {
            assertThat(entry.isChanged()).isTrue()
        }
    }

    private fun assertDirtyAndNotChanged(key: SkyKey?) {
        val entry: NodeEntry = graph.get(null, Reason.OTHER, key)
        if (gcExpected()) {
            assertThat(entry).isNull()
        } else {
            assertThat(entry.isDirty()).isTrue()
            assertThat(entry.isChanged()).isFalse()
        }
    }

    protected open fun newInvalidationState(): InvalidationState {
        throw java.lang.UnsupportedOperationException("Subclasses must override")
    }

    protected open fun defaultInvalidationType(): InvalidationType? {
        throw java.lang.UnsupportedOperationException("Subclasses must override")
    }

    protected open fun reverseDepsPresent(): Boolean {
        throw java.lang.UnsupportedOperationException("Subclasses must override")
    }

    // Convenience method for eval-ing a single value.
    @Throws(java.lang.InterruptedException::class)
    protected fun eval(keepGoing: Boolean, key: SkyKey?): SkyValue {
        val keys: Array<SkyKey?> = arrayOf<SkyKey?>(key)
        return eval<SkyValue?>(keepGoing, *keys).get(key)
    }

    @Throws(java.lang.InterruptedException::class)
    protected fun <T : SkyValue?> eval(keepGoing: Boolean, vararg keys: SkyKey?): EvaluationResult<T?>? {
        return<SkyValue> eval < SkyValue ? > (keepGoing, GraphInconsistencyReceiver.THROWING, keys)
    }

    @Throws(java.lang.InterruptedException::class)
    protected fun <T : SkyValue?> eval(
        keepGoing: Boolean, inconsistencyReceiver: GraphInconsistencyReceiver?, vararg keys: SkyKey?
    ): EvaluationResult<T?> {
        val reporter: com.google.devtools.build.lib.events.Reporter =
            com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
        val evaluator: ParallelEvaluator =
            ParallelEvaluator(
                graph,
                graphVersion,
                Version.minimal(),
                tester.getSkyFunctionMap(),
                reporter,
                EmittedEventState(),
                EventFilter.FULL_STORAGE,
                ErrorInfoManager.UseChildErrorInfoIfNecessary.INSTANCE,
                DirtyAndInflightTrackingProgressReceiver(EvaluationProgressReceiver.NULL),
                inconsistencyReceiver,
                AbstractQueueVisitor.create(
                    "test-pool", 200, ParallelEvaluatorErrorClassifier.instance()
                ),
                SimpleCycleDetector( /* storeExactCycles= */true),
                UnnecessaryTemporaryStateDropperReceiver.NULL,
                { unused -> keepGoing })
        graphVersion = graphVersion.next()
        return evaluator.eval(com.google.common.collect.ImmutableList.< E > copyOf < E ? > (keys))
    }

    @Throws(java.lang.InterruptedException::class)
    fun invalidateWithoutError(
        progressReceiver: DirtyAndInflightTrackingProgressReceiver?, vararg keys: SkyKey?
    ) {
        invalidate(graph, progressReceiver, keys)
        assertThat(state.isEmpty()).isTrue()
    }

    protected fun set(name: String?, value: String?) {
        tester.set(name, com.google.devtools.build.skyframe.GraphTester.StringValue(value))
    }

    @Throws(java.lang.InterruptedException::class)
    private fun assertValueValue(name: String?, expectedValue: String?) {
        val value: com.google.devtools.build.skyframe.GraphTester.StringValue = eval(
            false,
            GraphTester.Companion.skyKey(name)
        ) as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo(expectedValue)
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        progressReceiver =
            DirtyAndInflightTrackingProgressReceiver(EvaluationProgressReceiver.NULL)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun receiverWorks() {
        val invalidated: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
        val receiver: DirtyAndInflightTrackingProgressReceiver =
            DirtyAndInflightTrackingProgressReceiver(
                object : InvalidationProgressReceiver() {
                    override fun invalidated(skyKey: SkyKey?, state: InvalidationState?) {
                        com.google.common.base.Preconditions.checkState(state == expectedState())
                        invalidated.add(skyKey)
                    }
                })
        graph = InMemoryGraphImpl()
        val aKey: SkyKey? = GraphTester.Companion.nonHermeticKey("a")
        val bKey: SkyKey? = GraphTester.Companion.nonHermeticKey("b")
        tester.set(aKey, com.google.devtools.build.skyframe.GraphTester.StringValue("a"))
        tester.set(bKey, com.google.devtools.build.skyframe.GraphTester.StringValue("b"))
        tester.getOrCreate("ab").addDependency(aKey).addDependency(bKey)
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        assertValueValue("ab", "ab")

        tester.set(aKey, com.google.devtools.build.skyframe.GraphTester.StringValue("c"))
        invalidateWithoutError(receiver, aKey)
        Truth.assertThat(invalidated).containsExactly(aKey, GraphTester.Companion.skyKey("ab"))
        assertValueValue("ab", "cb")
        tester.set(bKey, com.google.devtools.build.skyframe.GraphTester.StringValue("d"))
        invalidateWithoutError(receiver, bKey)
        Truth.assertThat(invalidated).containsExactly(aKey, GraphTester.Companion.skyKey("ab"), bKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun receiverIsNotifiedAboutNodesInError() {
        val invalidated: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
        val receiver: DirtyAndInflightTrackingProgressReceiver =
            DirtyAndInflightTrackingProgressReceiver(
                object : InvalidationProgressReceiver() {
                    override fun invalidated(skyKey: SkyKey?, state: InvalidationState?) {
                        com.google.common.base.Preconditions.checkState(state == expectedState())
                        invalidated.add(skyKey)
                    }
                })

        // Given a graph consisting of two nodes, "a" and "ab" such that "ab" depends on "a",
        // And given "ab" is in error,
        graph = InMemoryGraphImpl()
        val aKey: SkyKey? = GraphTester.Companion.nonHermeticKey("a")
        tester.set(aKey, com.google.devtools.build.skyframe.GraphTester.StringValue("a"))
        tester.getOrCreate("ab").addDependency(aKey).setHasError(true)
        eval(false, GraphTester.Companion.skyKey("ab"))

        // When "a" is invalidated,
        invalidateWithoutError(receiver, aKey)

        // Then the invalidation receiver is notified of both "a" and "ab"'s invalidations.
        Truth.assertThat(invalidated).containsExactly(aKey, GraphTester.Companion.skyKey("ab"))

        // Note that this behavior isn't strictly required for correctness. This test is
        // meant to document current behavior and protect against programming error.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidateValuesNotInGraph() {
        val invalidated: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
        val receiver: DirtyAndInflightTrackingProgressReceiver =
            DirtyAndInflightTrackingProgressReceiver(
                object : InvalidationProgressReceiver() {
                    override fun invalidated(skyKey: SkyKey?, state: InvalidationState?) {
                        com.google.common.base.Preconditions.checkState(state == InvalidationState.DIRTY)
                        invalidated.add(skyKey)
                    }
                })
        graph = InMemoryGraphImpl()
        val aKey: SkyKey? = GraphTester.Companion.nonHermeticKey("a")
        invalidateWithoutError(receiver, aKey)
        Truth.assertThat(invalidated).isEmpty()
        tester.set(aKey, com.google.devtools.build.skyframe.GraphTester.StringValue("a"))
        val value: com.google.devtools.build.skyframe.GraphTester.StringValue =
            eval(false, aKey) as com.google.devtools.build.skyframe.GraphTester.StringValue
        Truth.assertThat(value.getValue()).isEqualTo("a")
        invalidateWithoutError(receiver, GraphTester.Companion.nonHermeticKey("b"))
        Truth.assertThat(invalidated).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidatedValuesAreGCedAsExpected() {
        val key: SkyKey? = GraphTester.Companion.nonHermeticKey("a")
        var heavyValue: HeavyValue? = HeavyValue()
        val weakRef: java.lang.ref.WeakReference<HeavyValue?> = java.lang.ref.WeakReference<HeavyValue?>(heavyValue)
        tester.set(key, heavyValue)

        graph = InMemoryGraphImpl()
        eval(false, key)
        invalidate(
            graph, DirtyAndInflightTrackingProgressReceiver(EvaluationProgressReceiver.NULL), key
        )

        tester = null
        heavyValue = null
        if (gcExpected()) {
            GcFinalization.awaitClear(weakRef)
        } else {
            // Not a reliable check, but better than nothing.
            java.lang.System.gc()
            java.lang.Thread.sleep(300)
            Truth.assertThat(weakRef.get()).isNotNull()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun reverseDepsConsistent() {
        graph = InMemoryGraphImpl()
        set("a", "a")
        set("b", "b")
        set("c", "c")
        val abKey: SkyKey? = GraphTester.Companion.nonHermeticKey("ab")
        tester.getOrCreate(abKey).addDependency("a").addDependency("b")
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        tester.getOrCreate("bc").addDependency("b").addDependency("c")
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        tester
            .getOrCreate("ab_c")
            .addDependency(abKey)
            .addDependency("c")
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        eval<SkyValue?>(false, GraphTester.Companion.skyKey("ab_c"), GraphTester.Companion.skyKey("bc"))

        assertThat(graph.get(null, Reason.OTHER, GraphTester.Companion.skyKey("a")).getReverseDepsForDoneEntry())
            .containsExactly(abKey)
        assertThat(graph.get(null, Reason.OTHER, GraphTester.Companion.skyKey("b")).getReverseDepsForDoneEntry())
            .containsExactly(abKey, GraphTester.Companion.skyKey("bc"))
        assertThat(graph.get(null, Reason.OTHER, GraphTester.Companion.skyKey("c")).getReverseDepsForDoneEntry())
            .containsExactly(GraphTester.Companion.skyKey("ab_c"), GraphTester.Companion.skyKey("bc"))

        invalidateWithoutError(
            DirtyAndInflightTrackingProgressReceiver(EvaluationProgressReceiver.NULL), abKey
        )
        eval<SkyValue?>(false)

        // The graph values should be gone.
        Truth.assertThat(isInvalidated(abKey)).isTrue()
        Truth.assertThat(isInvalidated(GraphTester.Companion.skyKey("abc"))).isTrue()

        // The reverse deps to ab and ab_c should have been removed if reverse deps are cleared.
        val reverseDeps: MutableSet<SkyKey?> = HashSet<SkyKey?>()
        if (reverseDepsPresent()) {
            reverseDeps.add(abKey)
        }
        assertThat(graph.get(null, Reason.OTHER, GraphTester.Companion.skyKey("a")).getReverseDepsForDoneEntry())
            .containsExactlyElementsIn(reverseDeps)
        reverseDeps.add(GraphTester.Companion.skyKey("bc"))
        assertThat(graph.get(null, Reason.OTHER, GraphTester.Companion.skyKey("b")).getReverseDepsForDoneEntry())
            .containsExactlyElementsIn(reverseDeps)
        reverseDeps.clear()
        if (reverseDepsPresent()) {
            reverseDeps.add(GraphTester.Companion.skyKey("ab_c"))
        }
        reverseDeps.add(GraphTester.Companion.skyKey("bc"))
        assertThat(graph.get(null, Reason.OTHER, GraphTester.Companion.skyKey("c")).getReverseDepsForDoneEntry())
            .containsExactlyElementsIn(reverseDeps)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun interruptChild() {
        graph = InMemoryGraphImpl()
        val numValues = 50 // More values than the invalidator has threads.
        val family: Array<SkyKey?> = arrayOfNulls<SkyKey>(numValues)
        val child: SkyKey? = GraphTester.Companion.nonHermeticKey("child")
        val childValue: com.google.devtools.build.skyframe.GraphTester.StringValue =
            com.google.devtools.build.skyframe.GraphTester.StringValue("child")
        tester.set(child, childValue)
        family[0] = child
        for (i in 1..<numValues) {
            val member: SkyKey? = GraphTester.Companion.skyKey(i.toString())
            tester.getOrCreate(member).addDependency(family[i - 1]).setComputedValue(GraphTester.Companion.CONCATENATE)
            family[i] = member
        }
        val parent: SkyKey? = GraphTester.Companion.skyKey("parent")
        tester.getOrCreate(parent).addDependency(family[numValues - 1])
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        eval( /* keepGoing= */false, parent)
        val mainThread: java.lang.Thread = java.lang.Thread.currentThread()
        val badKey: AtomicReference<SkyKey?> = AtomicReference<SkyKey?>()
        val receiver: DirtyAndInflightTrackingProgressReceiver =
            DirtyAndInflightTrackingProgressReceiver(
                object : InvalidationProgressReceiver() {
                    override fun invalidated(skyKey: SkyKey, state: InvalidationState?) {
                        if (skyKey.equals(child)) {
                            // Interrupt on the very first invalidate
                            mainThread.interrupt()
                        } else if (!skyKey.functionName().equals(GraphTester.Companion.NODE_TYPE)) {
                            // All other invalidations should have the GraphTester's key type.
                            // Exceptions thrown here may be silently dropped, so keep track of errors
                            // ourselves.
                            badKey.set(skyKey)
                        }
                        try {
                            assertThat(
                                visitor
                                    .get()
                                    .getInterruptionLatchForTestingOnly()
                                    .await(2, TimeUnit.HOURS)
                            )
                                .isTrue()
                        } catch (e: java.lang.InterruptedException) {
                            // We may well have thrown here because by the time we try to await, the main
                            // thread is already interrupted.
                            java.lang.Thread.currentThread().interrupt()
                        }
                    }
                })
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { invalidateWithoutError(receiver, child) })
        assertThat(badKey.get()).isNull()
        assertThat(state.isEmpty()).isFalse()
        val invalidated: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
        Truth.assertThat(isInvalidated(parent)).isFalse()
        assertThat(graph.get(null, Reason.OTHER, parent).getValue()).isNotNull()
        val receiver2: DirtyAndInflightTrackingProgressReceiver =
            DirtyAndInflightTrackingProgressReceiver(
                object : InvalidationProgressReceiver() {
                    override fun invalidated(skyKey: SkyKey?, state: InvalidationState?) {
                        invalidated.add(skyKey)
                    }
                })
        invalidateWithoutError(receiver2)
        Truth.assertThat(invalidated).contains(parent)
        assertThat(state.getInvalidationsForTesting()).isEmpty()

        // Regression test coverage:
        // "all pending values are marked changed on interrupt".
        Truth.assertThat(isInvalidated(child)).isTrue()
        assertChanged(child)
        for (i in 1..<numValues) {
            assertDirtyAndNotChanged(family[i])
        }
        assertDirtyAndNotChanged(parent)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deepGraph() {
        graph = InMemoryGraphImpl()
        val depth = 1 shl 15
        val leafKey: SkyKey? = GraphTester.Companion.nonHermeticKey(depth.toString())
        for (i in 0..<depth) {
            tester
                .getOrCreate(i.toString())
                .addDependency(if (i + 1 == depth) leafKey else GraphTester.Companion.skyKey((i + 1).toString()))
                .setComputedValue(GraphTester.Companion.CONCATENATE)
        }
        tester.set(leafKey, com.google.devtools.build.skyframe.GraphTester.StringValue("leaf"))
        eval( /* keepGoing= */false, GraphTester.Companion.skyKey(0.toString()))
        invalidateWithoutError(
            DirtyAndInflightTrackingProgressReceiver(EvaluationProgressReceiver.NULL), leafKey
        )
    }

    private fun constructLargeGraph(size: Int): Array<SkyKey?> {
        val random: Random = Random(com.google.devtools.build.lib.testutil.TestUtils.getRandomSeed().toLong())
        val values: Array<SkyKey?> = arrayOfNulls<SkyKey>(size)
        for (i in 0..<size) {
            val iString = i.toString()
            val iKey: SkyKey? = GraphTester.Companion.nonHermeticKey(iString)
            tester.set(iKey, com.google.devtools.build.skyframe.GraphTester.StringValue(iString))
            set(iString, iString)
            for (j in 0..<i) {
                if (random.nextInt(3) == 0) {
                    tester.getOrCreate(iKey).addDependency(GraphTester.Companion.nonHermeticKey(j.toString()))
                }
            }
            values[i] = iKey
        }
        return values
    }

    /** Returns a subset of `nodes` that are still valid and so can be invalidated.  */
    private fun getValuesToInvalidate(nodes: Array<SkyKey?>): MutableSet<Pair<SkyKey?, InvalidationType?>?> {
        val result: MutableSet<Pair<SkyKey?, InvalidationType?>?> = HashSet<Pair<SkyKey?, InvalidationType?>?>()
        val random: Random = Random(com.google.devtools.build.lib.testutil.TestUtils.getRandomSeed().toLong())
        for (node in nodes) {
            if (!isInvalidated(node)) {
                if (result.isEmpty() || random.nextInt(3) == 0) {
                    // Add at least one node, if we can.
                    result.add(Pair.of(node, defaultInvalidationType()))
                }
            }
        }
        return result
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allNodesProcessed() {
        graph = InMemoryGraphImpl()
        val keysToDelete: com.google.common.collect.ImmutableList.Builder<SkyKey?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<SkyKey?>(InvalidatingNodeVisitor.DEFAULT_THREAD_COUNT - 1)
        for (i in 0..<InvalidatingNodeVisitor.DEFAULT_THREAD_COUNT - 1) {
            keysToDelete.add(GraphTester.Companion.nonHermeticKey("key" + i))
        }
        invalidate(graph, progressReceiver, keysToDelete.build().< T > toArray < T ? > (arrayOfNulls<SkyKey>(0)))
        assertThat(state.isEmpty()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deletingInsideForkJoinPoolWorks() {
        graph = InMemoryGraphImpl()
        val outerPool: ForkJoinPool = ForkJoinPool(1)
        outerPool
            .submit(
                java.lang.Runnable {
                    try {
                        invalidate(graph, progressReceiver, GraphTester.Companion.nonHermeticKey("a"))
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.IllegalStateException(e)
                    }
                })
            .get()
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun interruptRecoversNextTime() {
        graph = InMemoryGraphImpl()
        val dep: SkyKey? = GraphTester.Companion.nonHermeticKey("dep")
        val toDelete: SkyKey? = GraphTester.Companion.nonHermeticKey("top")
        tester.getOrCreate(toDelete).addDependency(dep)
            .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("top"))
        tester.set(dep, com.google.devtools.build.skyframe.GraphTester.StringValue("dep"))
        eval( /*keepGoing=*/false, toDelete)
        val mainThread: java.lang.Thread = java.lang.Thread.currentThread()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable {
                invalidateWithoutError(
                    DirtyAndInflightTrackingProgressReceiver(
                        object : InvalidationProgressReceiver() {
                            override fun invalidated(skyKey: SkyKey?, state: InvalidationState?) {
                                mainThread.interrupt()
                                // Wait for the main thread to be interrupted uninterruptibly, because the
                                // main thread is going to interrupt us, and we don't want to get into an
                                // interrupt fight. Only if we get interrupted without the main thread also
                                // being interrupted will this throw an InterruptedException.
                                TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                                    visitor.get().getInterruptionLatchForTestingOnly(),
                                    "Main thread was not interrupted"
                                )
                            }
                        }),
                    toDelete
                )
            })
        invalidateWithoutError(
            DirtyAndInflightTrackingProgressReceiver(EvaluationProgressReceiver.NULL)
        )
        eval( /* keepGoing= */false, toDelete)
        invalidateWithoutError(
            DirtyAndInflightTrackingProgressReceiver(EvaluationProgressReceiver.NULL), toDelete
        )
        eval( /* keepGoing= */false, toDelete)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun interruptThreadInReceiver() {
        val random: Random = Random(com.google.devtools.build.lib.testutil.TestUtils.getRandomSeed().toLong())
        val graphSize = 1000
        val tries = 5
        graph = InMemoryGraphImpl()
        val values: Array<SkyKey?> = constructLargeGraph(graphSize)
        eval<SkyValue?>( /*keepGoing=*/false, *values)
        val mainThread: java.lang.Thread = java.lang.Thread.currentThread()
        for (run in 0..<tries + 1) {
            val valuesToInvalidate: MutableSet<Pair<SkyKey?, InvalidationType?>?> = getValuesToInvalidate(values)
            // Find how many invalidations will actually be enqueued for invalidation in the first round,
            // so that we can interrupt before all of them are done.
            var validValuesToDo: Int =
                com.google.common.collect.Sets.difference<E?>(
                    valuesToInvalidate,
                    state.getInvalidationsForTesting()
                ).size
            for (pair in state.getInvalidationsForTesting()) {
                if (!isInvalidated(pair.first)) {
                    validValuesToDo++
                }
            }
            val countDownStart = if (validValuesToDo > 0) random.nextInt(validValuesToDo) else 0
            val countDownToInterrupt: CountDownLatch = CountDownLatch(countDownStart)
            // Make sure final invalidation finishes.
            val receiver: DirtyAndInflightTrackingProgressReceiver =
                if (run == tries)
                    DirtyAndInflightTrackingProgressReceiver(EvaluationProgressReceiver.NULL)
                else
                    DirtyAndInflightTrackingProgressReceiver(
                        object : InvalidationProgressReceiver() {
                            override fun invalidated(skyKey: SkyKey?, state: InvalidationState?) {
                                countDownToInterrupt.countDown()
                                if (countDownToInterrupt.getCount() == 0L) {
                                    mainThread.interrupt()
                                    // Wait for the main thread to be interrupted uninterruptibly, because the
                                    // main thread is going to interrupt us, and we don't want to get into an
                                    // interrupt fight. Only if we get interrupted without the main thread also
                                    // being interrupted will this throw an InterruptedException.
                                    TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                                        visitor.get().getInterruptionLatchForTestingOnly(),
                                        "Main thread was not interrupted"
                                    )
                                }
                            }
                        })
            try {
                invalidate(
                    graph,
                    receiver,
                    com.google.common.collect.Sets.newHashSet<Any?>(
                        com.google.common.collect.Iterables.transform<Pair<SkyKey?, InvalidationType?>?, Any?>(
                            valuesToInvalidate,
                            com.google.common.base.Function { pair: Pair<SkyKey?, InvalidationType?>? -> pair.first })
                    )
                        .< T > toArray < T ? > (arrayOfNulls<SkyKey>(0))
                )
                assertThat(state.getInvalidationsForTesting()).isEmpty()
            } catch (e: java.lang.InterruptedException) {
                Truth.assertThat(run).isLessThan(tries)
            }
            if (state.isEmpty()) {
                // Ran out of values to invalidate.
                break
            }
        }

        eval<SkyValue?>( /*keepGoing=*/false, *values)
    }

    @Throws(java.lang.Exception::class)
    fun setupInvalidatableGraph() {
        graph = InMemoryGraphImpl()
        val aKey: SkyKey? = GraphTester.Companion.nonHermeticKey("a")
        tester.set(aKey, com.google.devtools.build.skyframe.GraphTester.StringValue("a"))
        set("b", "b")
        tester.getOrCreate("ab").addDependency(aKey).addDependency("b")
            .setComputedValue(GraphTester.Companion.CONCATENATE)
        assertValueValue("ab", "ab")
        tester.set(aKey, com.google.devtools.build.skyframe.GraphTester.StringValue("c"))
    }

    private class HeavyValue : SkyValue

    /** Test suite for the deleting invalidator.  */
    @RunWith(JUnit4::class)
    class DeletingInvalidatorTest : EagerInvalidatorTest() {
        @Throws(java.lang.InterruptedException::class)
        protected override fun invalidate(
            graph: InMemoryGraph?,
            progressReceiver: DirtyAndInflightTrackingProgressReceiver?,
            vararg keys: SkyKey?
        ) {
            val diff: Iterable<SkyKey?> = com.google.common.collect.ImmutableList.copyOf<SkyKey?>(keys)
            val deletingNodeVisitor: DeletingNodeVisitor? =
                EagerInvalidator.createDeletingVisitorIfNeeded(
                    graph,
                    diff,
                    DirtyAndInflightTrackingProgressReceiver(progressReceiver),
                    state as InvalidatingNodeVisitor.DeletingInvalidationState?,
                    true
                )
            if (deletingNodeVisitor != null) {
                visitor.set(deletingNodeVisitor)
                deletingNodeVisitor.run()
            }
        }

        override fun expectedState(): InvalidationState {
            return InvalidationState.DELETED
        }

        override fun gcExpected(): Boolean {
            return true
        }

        override fun newInvalidationState(): InvalidatingNodeVisitor.DeletingInvalidationState? {
            return DeletingInvalidationState()
        }

        override fun defaultInvalidationType(): InvalidationType {
            return InvalidationType.DELETED
        }

        override fun reverseDepsPresent(): Boolean {
            return false
        }

        /**
         * Regression test for b/316606228.
         * 
         * 
         * Evaluation of `top1` is interrupted after it was already signaled by `dep`,
         * but meanwhile `dep` was rewound by `top2`, and never gets a chance to complete.
         */
        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun interruptDuringRewind_parentAlreadySignaled() {
            graph = InMemoryGraphImpl()
            val top1: SkyKey? = GraphTester.Companion.skyKey("top1")
            val top2: SkyKey? = GraphTester.Companion.skyKey("top2")
            val dep: SkyKey? = GraphTester.Companion.skyKey("dep")
            val depDoneObservedByTop1: CountDownLatch = CountDownLatch(1)
            val top2Reset: CountDownLatch = CountDownLatch(1)
            tester
                .getOrCreate(top1)
                .setBuilder(
                    SkyFunction { skyKey, env ->
                        if (env.getValue(dep) == null) {
                            return@setBuilder null
                        }
                        depDoneObservedByTop1.countDown()
                        top2Reset.await()
                        throw java.lang.InterruptedException()
                    })
            tester
                .getOrCreate(top2)
                .setBuilder(
                    object : SkyFunction() {
                        private var alreadyReset = false

                        @Throws(java.lang.InterruptedException::class)
                        public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
                            if (alreadyReset) {
                                top2Reset.countDown()
                                throw java.lang.InterruptedException()
                            }
                            if (env.getValue(dep) == null) {
                                return null
                            }
                            depDoneObservedByTop1.await()
                            alreadyReset = true
                            val rewindGraph: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                                Reset.newRewindGraphFor(top2)
                            rewindGraph.putEdge(top2, dep)
                            return Reset.of(rewindGraph)
                        }
                    })
            tester.getOrCreate(dep)
                .setConstantValue(com.google.devtools.build.skyframe.GraphTester.StringValue("depVal"))

            org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
                java.lang.InterruptedException::class.java,
                org.junit.function.ThrowingRunnable {
                    eval( /* keepGoing= */
                        false,  // Tolerate inconsistencies.
                        { key, otherKeys, inconsistency -> },
                        top1,
                        top2
                    )
                })

            // Split invalidation into two calls to guarantee that top1 is visited before dep. In
            // practice, deletion is done in a single parallel traversal, but the crash from b/316606228
            // only reproduces when visiting in this order.
            invalidateWithoutError(
                DirtyAndInflightTrackingProgressReceiver(EvaluationProgressReceiver.NULL), top1
            )
            invalidateWithoutError(
                DirtyAndInflightTrackingProgressReceiver(EvaluationProgressReceiver.NULL), dep
            )
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun dirtyTrackingProgressReceiverWorksWithDeletingInvalidator() {
            setupInvalidatableGraph()
            val receiver: DirtyAndInflightTrackingProgressReceiver =
                DirtyAndInflightTrackingProgressReceiver(EvaluationProgressReceiver.NULL)

            // Dirty the node, and ensure that the tracker is aware of it:
            val diff: com.google.common.collect.ImmutableList<SkyKey?> =
                com.google.common.collect.ImmutableList.of<E?>(GraphTester.Companion.nonHermeticKey("a"))
            val state1: InvalidationState = DirtyingInvalidationState()
            com.google.common.base.Preconditions.checkNotNull<T?>(
                EagerInvalidator.createInvalidatingVisitorIfNeeded(graph, diff, receiver, state1)
            )
                .run()
            assertThat(receiver.getUnenqueuedDirtyKeys()).containsExactly(
                diff.get(0),
                GraphTester.Companion.skyKey("ab")
            )

            // Delete the node, and ensure that the tracker is no longer tracking it:
            com.google.common.base.Preconditions.checkNotNull<T?>(
                EagerInvalidator.createDeletingVisitorIfNeeded(
                    graph,
                    diff,
                    receiver,
                    state as InvalidatingNodeVisitor.DeletingInvalidationState?,
                    true
                )
            )
                .run()
            assertThat(receiver.getUnenqueuedDirtyKeys()).isEmpty()
        }
    }

    /**
     * Test suite for the dirtying invalidator.
     */
    @RunWith(JUnit4::class)
    class DirtyingInvalidatorTest : EagerInvalidatorTest() {
        @Throws(java.lang.InterruptedException::class)
        protected override fun invalidate(
            graph: InMemoryGraph?,
            progressReceiver: DirtyAndInflightTrackingProgressReceiver?,
            vararg keys: SkyKey?
        ) {
            val diff: Iterable<SkyKey?> = com.google.common.collect.ImmutableList.copyOf<SkyKey?>(keys)
            val dirtyingNodeVisitor: DirtyingNodeVisitor? =
                EagerInvalidator.createInvalidatingVisitorIfNeeded(graph, diff, progressReceiver, state)
            if (dirtyingNodeVisitor != null) {
                visitor.set(dirtyingNodeVisitor)
                dirtyingNodeVisitor.run()
            }
        }

        override fun expectedState(): InvalidationState {
            return InvalidationState.DIRTY
        }

        override fun gcExpected(): Boolean {
            return false
        }

        override fun newInvalidationState(): InvalidationState? {
            return DirtyingInvalidationState()
        }

        override fun defaultInvalidationType(): InvalidationType {
            return InvalidationType.CHANGED
        }

        override fun reverseDepsPresent(): Boolean {
            return true
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun dirtyTrackingProgressReceiverWorksWithDirtyingInvalidator() {
            setupInvalidatableGraph()
            val receiver: DirtyAndInflightTrackingProgressReceiver =
                DirtyAndInflightTrackingProgressReceiver(EvaluationProgressReceiver.NULL)

            // Dirty the node, and ensure that the tracker is aware of it:
            invalidate(graph, receiver, GraphTester.Companion.nonHermeticKey("a"))
            assertThat(receiver.getUnenqueuedDirtyKeys()).hasSize(2)
        }
    }
}
