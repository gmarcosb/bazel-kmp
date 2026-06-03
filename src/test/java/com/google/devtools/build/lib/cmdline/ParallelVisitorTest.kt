// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.cmdline

import com.google.common.truth.Truth
import com.google.devtools.build.lib.cmdline.BatchCallback.SafeBatchCallback
import com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface.MarkerRuntimeException
import com.google.devtools.build.lib.testutil.TestThread
import com.google.devtools.build.lib.testutil.TestThread.TestRunnable
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Unit tests for [ParallelVisitor].  */
@RunWith(JUnit4::class)
class ParallelVisitorTest {
    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    private interface TestCallback<T> : SafeBatchCallback<T?> {
        override fun process(partialResult: Iterable<T?>?)
    }

    /**
     * A dummy [ParallelVisitor] which waits for signal from a [CountDownLatch] when
     * [.getVisitResult] is invoked. It allows us to test interruptibility.
     */
    private class DelayGettingVisitResultParallelVisitor
        (invocationLatch: CountDownLatch, delayLatch: CountDownLatch) :
        ParallelVisitor<String?, String?, String?, String?, MarkerRuntimeException?, TestCallback<String?>?>(
            { targets -> },
            MarkerRuntimeException::class.java,  /*visitBatchSize=*/
            1,  /*processResultsBatchSize=*/
            1,  /*minPendingTasks=*/
            MIN_PENDING_TASKS,  /*batchCallbackSize=*/
            BATCH_CALLBACK_SIZE,
            Executors.newFixedThreadPool(3),
            VisitTaskStatusCallback.NULL_INSTANCE
        ) {
        private val invocationLatch: CountDownLatch
        private val delayLatch: CountDownLatch

        init {
            this.invocationLatch = invocationLatch
            this.delayLatch = delayLatch
        }

        @Throws(java.lang.InterruptedException::class)
        protected override fun getVisitResult(values: Iterable<String?>?): Visit {
            invocationLatch.countDown()
            delayLatch.await()
            return Visit(com.google.common.collect.ImmutableList.of<E?>(), values)
        }

        protected override fun preprocessInitialVisit(visitationKeys: Iterable<String?>?): Iterable<String?>? {
            return visitationKeys
        }

        protected override fun outputKeysToOutputValues(targetKeys: Iterable<String?>?): Iterable<String?> {
            return com.google.common.collect.ImmutableList.of<String?>()
        }

        protected override fun noteAndReturnUniqueVisitationKeys(
            prospectiveVisitationKeys: Iterable<String?>
        ): Iterable<String?> {
            return com.google.common.collect.ImmutableList.copyOf<String?>(prospectiveVisitationKeys)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInterrupt() {
        // This test verifies that visitations by ParallelVisitor can be interrupted. It also serves as
        // a regression test of b/62221332.
        val invocationLatch: CountDownLatch = CountDownLatch(1)
        val delayLatch: CountDownLatch = CountDownLatch(1)
        val visitor =
            DelayGettingVisitResultParallelVisitor(invocationLatch, delayLatch)
        val keysToVisit: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("for_testing")

        val testThread: TestThread = TestThread(TestRunnable { visitor.visitAndWaitForCompletion(keysToVisit) })
        testThread.start()

        // Send an interrupt signal to the visitor after #visitAndWaitForCompletion is invoked.
        invocationLatch.await()
        testThread.interrupt()

        // Verify that the thread is interruptible (unit test will time out if it's not interruptible).
        testThread.join()
    }

    private class RecordingParallelVisitor
        (
        successors: com.google.common.collect.ImmutableMultimap<String?, String?>,
        recordingCallback: RecordingCallback?,
        visitBatchSize: Int,
        processResultsBatchSize: Int
    ) : ParallelVisitor<InputKey?, String?, String?, String?, MarkerRuntimeException?, TestCallback<String?>?>(
        recordingCallback,
        MarkerRuntimeException::class.java,
        visitBatchSize,
        processResultsBatchSize,
        MIN_PENDING_TASKS,
        BATCH_CALLBACK_SIZE,
        Executors.newFixedThreadPool(3),
        VisitTaskStatusCallback.NULL_INSTANCE
    ) {
        private val visits: java.util.ArrayList<Iterable<String?>> = java.util.ArrayList<Iterable<String?>>()
        private val successorMap: com.google.common.collect.ImmutableMultimap<String?, String?>
        private val visited: MutableSet<String?> = com.google.common.collect.Sets.newConcurrentHashSet<String?>()

        init {
            this.successorMap = successors
        }

        protected override fun getVisitResult(values: Iterable<String?>): Visit {
            synchronized(this) {
                visits.add(values)
            }
            return Visit(
                values,
                com.google.common.collect.Iterables.< T > concat < T ? > (com.google.common.collect.Iterables.transform<String?, com.google.common.collect.ImmutableCollection<String?>?>(
                    values,
                    com.google.common.base.Function { key: String? -> successorMap.get(key) }))
            )
        }

        protected override fun noteAndReturnUniqueVisitationKeys(
            prospectiveVisitationKeys: Iterable<String?>
        ): Iterable<String?> {
            return com.google.common.collect.Iterables.filter<String?>(
                prospectiveVisitationKeys,
                com.google.common.base.Predicate { e: String? -> visited.add(e) })
        }

        protected override fun outputKeysToOutputValues(targetKeys: Iterable<String?>?): Iterable<String?>? {
            return targetKeys
        }

        protected override fun preprocessInitialVisit(visitationKeys: Iterable<InputKey?>): Iterable<String?> {
            return com.google.common.collect.Iterables.transform<InputKey?, String?>(
                visitationKeys,
                com.google.common.base.Function { key: InputKey? -> InputKey.Companion.extract(key) })
        }
    }

    private class RecordingCallback : TestCallback<String?> {
        private val results: java.util.ArrayList<Iterable<String?>> = java.util.ArrayList<Iterable<String?>>()

        @kotlin.jvm.Synchronized
        override fun process(partialResult: Iterable<String?>?) {
            results.add(partialResult)
        }
    }

    private class InputKey(private val str: String) {
        override fun equals(obj: Any?): Boolean {
            if (obj !is InputKey) {
                return false
            }
            return this.str == obj.str
        }

        override fun hashCode(): Int {
            return str.hashCode()
        }

        companion object {
            private fun extract(key: InputKey): String {
                return key.str
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRespectsBatchSizes() {
        val visitBatchSize = 2
        val processResultsBatchSize = 1
        val callback = RecordingCallback()
        val visitor =
            RecordingParallelVisitor(
                com.google.common.collect.ImmutableMultimap.builder<String?, String?>()
                    .putAll("k1", com.google.common.collect.ImmutableList.of<String?>("k2", "k3", "k4", "k5"))
                    .putAll("k2", com.google.common.collect.ImmutableList.of<String?>("k6", "k7", "k8", "k9"))
                    .putAll(
                        "k3",
                        com.google.common.collect.ImmutableList.of<String?>("k4", "k5", "k6", "k7", "k8", "k9")
                    )
                    .build(),
                callback,
                visitBatchSize,
                processResultsBatchSize
            )
        visitor.visitAndWaitForCompletion(com.google.common.collect.ImmutableList.of<E?>(InputKey("k1")))

        for (visitBatch in visitor.visits) {
            Truth.assertThat(com.google.common.collect.Iterables.size(visitBatch)).isAtMost(visitBatchSize)
        }
        for (resultBatch in callback.results) {
            Truth.assertThat(com.google.common.collect.Iterables.size(resultBatch)).isAtMost(processResultsBatchSize)
        }
    }

    companion object {
        private const val BATCH_CALLBACK_SIZE = 10000
        private const val MIN_PENDING_TASKS = 3L
    }
}
