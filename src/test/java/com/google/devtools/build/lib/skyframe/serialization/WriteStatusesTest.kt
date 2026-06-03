// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.aggregateWriteStatuses

@RunWith(JUnit4::class)
class WriteStatusesTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun immediateWriteStatus_isDone() {
        val status: WriteStatus = immediateWriteStatus()

        assertThat(status.isDone()).isTrue()
        assertThat(status.isCancelled()).isFalse()
        assertThat(status.cancel(true)).isFalse()
        assertThat(status.cancel(false)).isFalse()

        assertThat(status.get()).isTrue()
        assertThat(status.get(0, TimeUnit.SECONDS)).isTrue()

        assertListenerExecutesImmediately(status)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failedWriteStatus_isDone() {
        val exception: java.lang.Exception = java.lang.Exception()
        val status: WriteStatus = immediateFailedWriteStatus(exception)

        assertThat(status.isDone()).isTrue()
        assertThat(status.isCancelled()).isFalse()
        assertThat(status.cancel(true)).isFalse()
        assertThat(status.cancel(false)).isFalse()

        var thrown: ExecutionException? =
            org.junit.Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, status::get)
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception)

        thrown = org.junit.Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            org.junit.function.ThrowingRunnable { status.get(0, TimeUnit.SECONDS) })
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception)

        assertListenerExecutesImmediately(status)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun settableWriteStatus_markSuccess_isDone() {
        val status: SettableWriteStatus = SettableWriteStatus()

        assertThat(status.isDone()).isFalse()
        val setOnRun = SetOnRun()
        status.addListener(setOnRun, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        Truth.assertThat(setOnRun.isSet).isFalse()

        status.markSuccess()
        Truth.assertThat(setOnRun.isSet).isTrue()

        assertThat(status.isDone()).isTrue()
        assertThat(status.get()).isTrue()

        assertListenerExecutesImmediately(status)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun settableWriteStatus_failWith() {
        val status: SettableWriteStatus = SettableWriteStatus()

        assertThat(status.isDone()).isFalse()
        val setOnRun = SetOnRun()
        status.addListener(setOnRun, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        Truth.assertThat(setOnRun.isSet).isFalse()

        val exception: java.lang.Exception = java.lang.Exception()
        status.failWith(exception)
        Truth.assertThat(setOnRun.isSet).isTrue()
        assertThat(status.isDone()).isTrue()

        var thrown: ExecutionException? =
            org.junit.Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, status::get)
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception)

        thrown = org.junit.Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            org.junit.function.ThrowingRunnable { status.get(0, TimeUnit.SECONDS) })
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception)

        assertListenerExecutesImmediately(status)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun settableWriteStatus_failWith_cancellationException() {
        val status: SettableWriteStatus = SettableWriteStatus()

        assertThat(status.isDone()).isFalse()
        val setOnRun = SetOnRun()
        status.addListener(setOnRun, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        Truth.assertThat(setOnRun.isSet).isFalse()

        status.failWith(CancellationException())

        Truth.assertThat(setOnRun.isSet).isTrue()
        assertThat(status.isDone()).isTrue()
        assertThat(status.isCancelled()).isTrue()

        org.junit.Assert.assertThrows<CancellationException?>(CancellationException::class.java, status::get)
        org.junit.Assert.assertThrows<CancellationException?>(
            CancellationException::class.java,
            org.junit.function.ThrowingRunnable { status.get(0, TimeUnit.SECONDS) })

        assertListenerExecutesImmediately(status)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun settableWriteStatus_completeWith_successfulFuture() {
        val status: SettableWriteStatus = SettableWriteStatus()

        assertThat(status.isDone()).isFalse()
        val setOnRun = SetOnRun()
        status.addListener(setOnRun, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        Truth.assertThat(setOnRun.isSet).isFalse()

        status.completeWith(immediateWriteStatus())
        Truth.assertThat(setOnRun.isSet).isTrue()

        assertThat(status.isDone()).isTrue()
        assertThat(status.get()).isTrue()
        assertThat(status.get(0, TimeUnit.SECONDS)).isTrue()

        assertListenerExecutesImmediately(status)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun settableWriteStatus_completeWith_failingFuture() {
        val status: SettableWriteStatus = SettableWriteStatus()

        assertThat(status.isDone()).isFalse()
        val setOnRun = SetOnRun()
        status.addListener(setOnRun, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        Truth.assertThat(setOnRun.isSet).isFalse()

        val exception: java.lang.Exception = java.lang.Exception()
        status.completeWith(immediateFailedWriteStatus(exception))

        assertThat(status.isDone()).isTrue()
        Truth.assertThat(setOnRun.isSet).isTrue()

        var thrown: ExecutionException? =
            org.junit.Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, status::get)
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception)

        thrown = org.junit.Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            org.junit.function.ThrowingRunnable { status.get(0, TimeUnit.SECONDS) })
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception)

        assertListenerExecutesImmediately(status)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aggregateWriteStatus_waitsForInputs() {
        val input: SettableWriteStatus = SettableWriteStatus()

        val status: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            aggregateWriteStatuses(com.google.common.collect.ImmutableList.of<E?>(input, immediateWriteStatus()))

        assertThat(status.isDone()).isFalse()
        val setOnRun = SetOnRun()
        status.addListener(setOnRun, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        Truth.assertThat(setOnRun.isSet).isFalse()

        input.markSuccess()
        Truth.assertThat(setOnRun.isSet).isTrue()
        assertThat(status.isDone()).isTrue()

        assertThat(status.get()).isTrue()
        assertThat(status.get(0, TimeUnit.SECONDS)).isTrue()

        assertListenerExecutesImmediately(status)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aggregateWriteStatus_failsOnFailedInput() {
        val input: SettableWriteStatus = SettableWriteStatus()

        val status: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            aggregateWriteStatuses(com.google.common.collect.ImmutableList.of<E?>(input, immediateWriteStatus()))

        assertThat(status.isDone()).isFalse()
        val setOnRun = SetOnRun()
        status.addListener(setOnRun, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        Truth.assertThat(setOnRun.isSet).isFalse()

        val exception: java.lang.Exception = java.lang.Exception()
        input.failWith(exception)
        Truth.assertThat(setOnRun.isSet).isTrue()

        assertThat(status.isDone()).isTrue()
        assertListenerExecutesImmediately(status)

        var thrown: ExecutionException? =
            org.junit.Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, status::get)
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception)

        thrown = org.junit.Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            org.junit.function.ThrowingRunnable { status.get(0, TimeUnit.SECONDS) })
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aggregateWriteStatus_multipleFailedInputs() {
        val input: SettableWriteStatus = SettableWriteStatus()

        val exception1: java.lang.Exception = java.lang.Exception()
        val status: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            aggregateWriteStatuses(
                com.google.common.collect.ImmutableList.of<E?>(
                    input,
                    immediateFailedWriteStatus(exception1)
                )
            )

        assertThat(status.isDone()).isTrue()
        assertListenerExecutesImmediately(status)

        var thrown: ExecutionException? =
            org.junit.Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, status::get)
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception1)

        thrown = org.junit.Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            org.junit.function.ThrowingRunnable { status.get(0, TimeUnit.SECONDS) })
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception1)

        val exception2: java.lang.Exception = java.lang.Exception()
        input.failWith(exception2)

        thrown = org.junit.Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, status::get)
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception1)
        thrown = org.junit.Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            org.junit.function.ThrowingRunnable { status.get(0, TimeUnit.SECONDS) })
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aggregateWriteStatus_alreadyCancelledInput_propagates() {
        val input: SettableWriteStatus = SettableWriteStatus()
        input.cancel( /* mayInterruptIfRunning= */false)
        assertThat(input.isCancelled()).isTrue()
        var unused: CancellationException? =
            org.junit.Assert.assertThrows<CancellationException?>(CancellationException::class.java, input::get)

        val status: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            aggregateWriteStatuses(com.google.common.collect.ImmutableList.of<E?>(input, immediateWriteStatus()))

        assertThat(status.isDone()).isTrue()
        assertThat(status.isCancelled()).isTrue()
        unused = org.junit.Assert.assertThrows<CancellationException?>(CancellationException::class.java, status::get)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aggregateWriteStatus_cancellingInput_propagates() {
        val input: SettableWriteStatus = SettableWriteStatus()

        val status: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            aggregateWriteStatuses(com.google.common.collect.ImmutableList.of<E?>(input, immediateWriteStatus()))

        assertThat(status.isDone()).isFalse()

        input.cancel( /* mayInterruptIfRunning= */false)

        assertThat(status.isDone()).isTrue()
        assertThat(status.isCancelled()).isTrue()
        val unused: CancellationException? =
            org.junit.Assert.assertThrows<CancellationException?>(CancellationException::class.java, status::get)
    }

    // This test case and the following one exercise the use of SparseAggregateWriteStatus as a
    // SettableFuture.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregate_notifyWriteSucceeded_completes() {
        val status: SparseAggregateWriteStatus = SparseAggregateWriteStatus()

        assertThat(status.isDone()).isFalse()
        val setOnRun = SetOnRun()
        status.addListener(setOnRun, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        Truth.assertThat(setOnRun.isSet).isFalse()

        status.notifyWriteSucceeded()
        Truth.assertThat(setOnRun.isSet).isTrue()

        assertThat(status.isDone()).isTrue()
        assertThat(status.get()).isTrue()
        assertThat(status.get(0, TimeUnit.SECONDS)).isTrue()

        assertListenerExecutesImmediately(status)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregate_notifyWriteFailed_completes() {
        val status: SparseAggregateWriteStatus = SparseAggregateWriteStatus()

        assertThat(status.isDone()).isFalse()
        val setOnRun = SetOnRun()
        status.addListener(setOnRun, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        Truth.assertThat(setOnRun.isSet).isFalse()

        val exception: java.lang.Exception = java.lang.Exception()
        status.notifyWriteFailed(exception)

        Truth.assertThat(setOnRun.isSet).isTrue()
        assertThat(status.isDone()).isTrue()

        var thrown: ExecutionException? =
            org.junit.Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, status::get)
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception)
        thrown = org.junit.Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            org.junit.function.ThrowingRunnable { status.get(0, TimeUnit.SECONDS) })
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception)

        assertListenerExecutesImmediately(status)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregate_empty_isImmediate() {
        assertThat(sparselyAggregateWriteStatuses(com.google.common.collect.ImmutableList.of<E?>()))
            .isSameInstanceAs(immediateWriteStatus())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregate_singleton_forwards() {
        val inner: SettableWriteStatus = SettableWriteStatus()
        assertThat(sparselyAggregateWriteStatuses(com.google.common.collect.ImmutableList.of<E?>(inner))).isSameInstanceAs(
            inner
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregate_doneInputs_isDone() {
        val status: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            sparselyAggregateWriteStatuses(
                com.google.common.collect.ImmutableList.of<E?>(immediateWriteStatus(), immediateWriteStatus())
            )

        assertThat(status.isDone()).isTrue()
        assertThat(status.isCancelled()).isFalse()
        assertThat(status.cancel(true)).isFalse()
        assertThat(status.cancel(false)).isFalse()

        assertThat(status.get()).isTrue()
        assertThat(status.get(0, TimeUnit.SECONDS)).isTrue()

        assertListenerExecutesImmediately(status)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregate_sparseleyPropagatesSuccess() {
        val status: SparseAggregateWriteStatus = SparseAggregateWriteStatus()

        // Constructing the aggregate requires at least 2 inputs to avoid short-circuit behavior.
        val aggregate1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            sparselyAggregateWriteStatuses(
                com.google.common.collect.ImmutableList.of<E?>(
                    status,
                    immediateWriteStatus()
                )
            )
        assertThat(aggregate1.isDone()).isFalse()
        val setOnRun = SetOnRun()
        status.addListener(setOnRun, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        Truth.assertThat(setOnRun.isSet).isFalse()

        val aggregate2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            sparselyAggregateWriteStatuses(
                com.google.common.collect.ImmutableList.of<E?>(
                    status,
                    immediateWriteStatus()
                )
            )
        // The edge from `status` to `aggregate2` is dropped for sparsity. The only child of aggregate2
        // is immediateWriteStatus, which is already done. It completes inside
        // SparseAggregateWriteStatus.create once the pre-increment is cancelled.
        assertThat(aggregate2.isDone()).isTrue()

        status.notifyWriteSucceeded()
        Truth.assertThat(setOnRun.isSet).isTrue()
        assertThat(aggregate1.isDone()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregate_sparseleyPropagatesException() {
        val status: SparseAggregateWriteStatus = SparseAggregateWriteStatus()

        // Constructing the aggregate requires at least 2 inputs to avoid short-circuit behavior.
        val aggregate1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            sparselyAggregateWriteStatuses(
                com.google.common.collect.ImmutableList.of<E?>(
                    status,
                    immediateWriteStatus()
                )
            )
        assertThat(aggregate1.isDone()).isFalse()
        val setOnRun = SetOnRun()
        status.addListener(setOnRun, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        Truth.assertThat(setOnRun.isSet).isFalse()

        val aggregate2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            sparselyAggregateWriteStatuses(
                com.google.common.collect.ImmutableList.of<E?>(
                    status,
                    immediateWriteStatus()
                )
            )
        // The edge from `status` to `aggregate2` is dropped for sparsity.
        assertThat(aggregate2.isDone()).isTrue()

        val exception: java.lang.Exception = java.lang.Exception()
        status.notifyWriteFailed(exception)
        Truth.assertThat(setOnRun.isSet).isTrue()
        assertThat(aggregate1.isDone()).isTrue()

        var thrown: ExecutionException? =
            org.junit.Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, status::get)
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception)
        thrown = org.junit.Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            org.junit.function.ThrowingRunnable { status.get(0, TimeUnit.SECONDS) })
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregate_cancelledInput_propagates() {
        val cancelledInput: SettableWriteStatus = SettableWriteStatus()
        cancelledInput.cancel( /* mayInterruptIfRunning= */false)

        val status: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            sparselyAggregateWriteStatuses(
                com.google.common.collect.ImmutableList.of<E?>(
                    cancelledInput,
                    immediateWriteStatus()
                )
            )
        assertThat(status.isDone()).isTrue()
        assertThat(status.isCancelled()).isTrue()
        var unused: CancellationException? =
            org.junit.Assert.assertThrows<CancellationException?>(CancellationException::class.java, status::get)

        val settable: SettableWriteStatus = SettableWriteStatus()
        settable.completeWith(status)
        assertThat(settable.isCancelled()).isTrue()
        unused = org.junit.Assert.assertThrows<CancellationException?>(CancellationException::class.java, settable::get)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregate_cancellingInput_propagatesSparsely() {
        val input: SparseAggregateWriteStatus = SparseAggregateWriteStatus()

        val consumer1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            sparselyAggregateWriteStatuses(
                com.google.common.collect.ImmutableList.of<E?>(
                    input,
                    immediateWriteStatus()
                )
            )
        assertThat(consumer1.isDone()).isFalse()

        val consumer2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            sparselyAggregateWriteStatuses(
                com.google.common.collect.ImmutableList.of<E?>(
                    input,
                    immediateWriteStatus()
                )
            )
        assertThat(consumer2.isDone()).isTrue() // input ignored due to sparse aggregation
        assertThat(consumer2.isCancelled()).isFalse()

        input.cancel( /* mayInterruptIfRunning= */false)
        assertThat(input.isCancelled()).isTrue()
        var unused: CancellationException? =
            org.junit.Assert.assertThrows<CancellationException?>(CancellationException::class.java, input::get)

        assertThat(consumer1.isCancelled()).isTrue()
        unused =
            org.junit.Assert.assertThrows<CancellationException?>(CancellationException::class.java, consumer1::get)
    }

    private class SetOnRun : java.lang.Runnable {
        private var isSet = false

        override fun run() {
            isSet = true
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aggregateWriteStatusBuilder_empty() {
        val builder: WriteStatuses.AggregateWriteStatusBuilder = AggregateWriteStatusBuilder()
        val status: WriteStatus = builder.build()

        assertThat(status.isDone()).isTrue()
        assertThat(status.get()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aggregateWriteStatusBuilder_addDone() {
        val builder: WriteStatuses.AggregateWriteStatusBuilder = AggregateWriteStatusBuilder()
        builder.add(immediateWriteStatus())
        val exception: java.lang.Exception = java.lang.Exception("test")
        builder.add(immediateFailedWriteStatus(exception))

        val status: WriteStatus = builder.build()
        assertThat(status.isDone()).isTrue()
        val thrown: ExecutionException? =
            org.junit.Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, status::get)
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aggregateWriteStatusBuilder_addPending() {
        val builder: WriteStatuses.AggregateWriteStatusBuilder = AggregateWriteStatusBuilder()
        val pending1: SettableWriteStatus = SettableWriteStatus()
        val pending2: SettableWriteStatus = SettableWriteStatus()
        builder.add(pending1)
        builder.add(pending2)

        val status: WriteStatus = builder.build()
        assertThat(status.isDone()).isFalse()

        pending1.markSuccess()
        assertThat(status.isDone()).isFalse()

        pending2.markSuccess()
        assertThat(status.isDone()).isTrue()
        assertThat(status.get()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aggregateWriteStatusBuilder_addAll() {
        val builder: WriteStatuses.AggregateWriteStatusBuilder = AggregateWriteStatusBuilder()
        val pending1: SettableWriteStatus = SettableWriteStatus()
        val exception: java.lang.Exception = java.lang.Exception("test")
        builder.addAll(
            com.google.common.collect.ImmutableList.of<E?>(
                pending1,
                immediateWriteStatus(),
                immediateFailedWriteStatus(exception)
            )
        )

        val status: WriteStatus = builder.build()
        assertThat(status.isDone()).isTrue() // Fails fast
        val thrown: ExecutionException? =
            org.junit.Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, status::get)
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aggregateWriteStatusBuilder_buildTwice_throwsException() {
        val builder: WriteStatuses.AggregateWriteStatusBuilder = AggregateWriteStatusBuilder()
        builder.add(immediateWriteStatus())
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = builder.build()

        val thrown: java.lang.IllegalStateException? = org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            builder::build
        )
        Truth.assertThat(thrown).hasMessageThat().contains("build must only be called once")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregateWriteStatusBuilder_empty() {
        val builder: WriteStatuses.SparseAggregateWriteStatusBuilder = SparseAggregateWriteStatusBuilder()
        val status: WriteStatus = builder.build()

        assertThat(status.isDone()).isTrue()
        assertThat(status.get()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregateWriteStatusBuilder_addDone() {
        val builder: WriteStatuses.SparseAggregateWriteStatusBuilder = SparseAggregateWriteStatusBuilder()
        builder.add(immediateWriteStatus())
        val exception: java.lang.Exception = java.lang.Exception("test")
        builder.add(immediateFailedWriteStatus(exception))

        val status: WriteStatus = builder.build()
        assertThat(status.isDone()).isTrue()
        val thrown: ExecutionException? =
            org.junit.Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, status::get)
        Truth.assertThat(thrown).hasCauseThat().hasCauseThat().isSameInstanceAs(exception)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregateWriteStatusBuilder_addPending() {
        val builder: WriteStatuses.SparseAggregateWriteStatusBuilder = SparseAggregateWriteStatusBuilder()
        val pending1: SettableWriteStatus = SettableWriteStatus()
        val pending2: SettableWriteStatus = SettableWriteStatus()
        builder.add(pending1)
        builder.add(pending2)

        val status: WriteStatus = builder.build()
        assertThat(status.isDone()).isFalse()

        pending1.markSuccess()
        assertThat(status.isDone()).isFalse()

        pending2.markSuccess()
        assertThat(status.isDone()).isTrue()
        assertThat(status.get()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregateWriteStatusBuilder_addAll() {
        val builder: WriteStatuses.SparseAggregateWriteStatusBuilder = SparseAggregateWriteStatusBuilder()
        val pending1: SettableWriteStatus = SettableWriteStatus()
        val exception: java.lang.Exception = java.lang.Exception("test")
        builder.addAll(
            com.google.common.collect.ImmutableList.of<E?>(
                pending1,
                immediateWriteStatus(),
                immediateFailedWriteStatus(exception)
            )
        )

        val status: WriteStatus = builder.build()
        assertThat(status.isDone()).isTrue() // Fails fast on done inputs
        val thrown: ExecutionException? =
            org.junit.Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, status::get)
        Truth.assertThat(thrown).hasCauseThat().hasCauseThat().isSameInstanceAs(exception)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregateWriteStatusBuilder_buildTwice_throwsException() {
        val builder: WriteStatuses.SparseAggregateWriteStatusBuilder = SparseAggregateWriteStatusBuilder()
        builder.add(immediateWriteStatus())
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = builder.build()

        val thrown: java.lang.IllegalStateException? = org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            builder::build
        )
        Truth.assertThat(thrown).hasMessageThat().contains("build must only be called once")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregateWriteStatusBuilder_sparsity() {
        val sharedPending: SparseAggregateWriteStatus = SparseAggregateWriteStatus()
        val other1: SettableWriteStatus = SettableWriteStatus()
        val other2: SettableWriteStatus = SettableWriteStatus()

        val builder1: WriteStatuses.SparseAggregateWriteStatusBuilder = SparseAggregateWriteStatusBuilder()
        builder1.add(sharedPending)
        builder1.add(other1)
        val status1: WriteStatus = builder1.build()

        val builder2: WriteStatuses.SparseAggregateWriteStatusBuilder = SparseAggregateWriteStatusBuilder()
        builder2.add(sharedPending)
        builder2.add(other2)
        val status2: WriteStatus = builder2.build()

        assertThat(status1.isDone()).isFalse()
        assertThat(status2.isDone()).isFalse()

        other1.markSuccess()
        assertThat(status1.isDone()).isFalse()
        assertThat(status2.isDone()).isFalse()

        other2.markSuccess()
        assertThat(status1.isDone()).isFalse()
        // Sparsity means that sharedPending is only added to status1.
        assertThat(status2.isDone()).isTrue()

        sharedPending.notifyWriteSucceeded()
        assertThat(status1.isDone()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aggregate_propagatesNovelty() {
        val n1: SettableWriteStatus = SettableWriteStatus()
        val n2: SettableWriteStatus = SettableWriteStatus()
        val aggregate: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            aggregateWriteStatuses(com.google.common.collect.ImmutableList.of<E?>(n1, n2))

        assertThat(aggregate.isDone()).isFalse()

        n1.markSuccess(true)
        assertThat(aggregate.isDone()).isFalse()

        n2.markSuccess(false)
        assertThat(aggregate.isDone()).isTrue()
        assertThat(aggregate.get()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aggregate_allFalse_isFalse() {
        val n1: SettableWriteStatus = SettableWriteStatus()
        val n2: SettableWriteStatus = SettableWriteStatus()
        val aggregate: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            aggregateWriteStatuses(com.google.common.collect.ImmutableList.of<E?>(n1, n2))

        n1.markSuccess(false)
        n2.markSuccess(false)
        assertThat(aggregate.get()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregate_propagatesNovelty() {
        val n1: SparseAggregateWriteStatus = SparseAggregateWriteStatus()
        val n2: SparseAggregateWriteStatus = SparseAggregateWriteStatus()
        val aggregate: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            sparselyAggregateWriteStatuses(com.google.common.collect.ImmutableList.of<E?>(n1, n2))

        assertThat(aggregate.isDone()).isFalse()

        n1.notifyWriteSucceeded(true)
        assertThat(aggregate.isDone()).isFalse()

        n2.notifyWriteSucceeded(false)
        assertThat(aggregate.isDone()).isTrue()
        assertThat(aggregate.get()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregate_allFalse_isFalse() {
        val n1: SparseAggregateWriteStatus = SparseAggregateWriteStatus()
        val n2: SparseAggregateWriteStatus = SparseAggregateWriteStatus()
        val aggregate: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            sparselyAggregateWriteStatuses(com.google.common.collect.ImmutableList.of<E?>(n1, n2))

        n1.notifyWriteSucceeded(false)
        n2.notifyWriteSucceeded(false)
        assertThat(aggregate.get()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregateWriteStatusBuilder_addDoneNovel() {
        val builder: WriteStatuses.SparseAggregateWriteStatusBuilder = SparseAggregateWriteStatusBuilder()
        val novel: SettableWriteStatus = SettableWriteStatus()
        novel.markSuccess(true)
        builder.add(novel)
        builder.add(immediateWriteStatus()) // Ensure it's an aggregate

        val status: WriteStatus = builder.build()
        assertThat(status.isDone()).isTrue()
        assertThat(status.get()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseBuilder_propagatesNoveltyFromDoneInput() {
        val doneNovel: SettableWriteStatus = SettableWriteStatus()
        doneNovel.markSuccess(true)

        val builder: WriteStatuses.SparseAggregateWriteStatusBuilder =
            SparseAggregateWriteStatusBuilder()
        builder.add(doneNovel)
        builder.add(immediateWriteStatus()) // need at least one more to not short-circuit?

        // Actually SparseAggregateWriteStatusBuilder always creates a SparseAggregateWriteStatus if not
        // empty.
        val aggregate: WriteStatus = builder.build()
        assertThat(aggregate.get()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aggregateWriteStatus_mixedPendingAndImmediate_trueThenFalse() {
        val pending: SettableWriteStatus = SettableWriteStatus()
        val status: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            aggregateWriteStatuses(com.google.common.collect.ImmutableList.of<E?>(pending, immediateWriteStatus()))

        assertThat(status.isDone()).isFalse()

        pending.markSuccess(false)

        assertThat(status.isDone()).isTrue()
        // immediateWriteStatus() is true. (true OR false) is true.
        assertThat(status.get()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aggregateWriteStatus_mixedPendingAndImmediate_falseThenTrue() {
        val pending: SettableWriteStatus = SettableWriteStatus()
        val immediateFalse: SettableWriteStatus = SettableWriteStatus()
        immediateFalse.markSuccess(false)
        val status: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            aggregateWriteStatuses(com.google.common.collect.ImmutableList.of<E?>(pending, immediateFalse))

        assertThat(status.isDone()).isFalse()

        pending.markSuccess(true)

        assertThat(status.isDone()).isTrue()
        assertThat(status.get()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregate_notifyWriteSucceeded_directMixedValues() {
        var builder: WriteStatuses.SparseAggregateWriteStatusBuilder = SparseAggregateWriteStatusBuilder()
        builder.add(SettableWriteStatus())
        builder.add(SettableWriteStatus())
        var aggregate: SparseAggregateWriteStatus = builder.build()

        aggregate.notifyWriteSucceeded(true)
        assertThat(aggregate.isDone()).isFalse()
        aggregate.notifyWriteSucceeded(false)
        assertThat(aggregate.isDone()).isTrue()
        assertThat(aggregate.get()).isTrue()

        // Reverse order
        builder = SparseAggregateWriteStatusBuilder()
        builder.add(SettableWriteStatus())
        builder.add(SettableWriteStatus())
        aggregate = builder.build()
        aggregate.notifyWriteSucceeded(false)
        assertThat(aggregate.isDone()).isFalse()
        aggregate.notifyWriteSucceeded(true)
        assertThat(aggregate.isDone()).isTrue()
        assertThat(aggregate.get()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregateWriteStatusBuilder_multipleDoneFutures_mixedValues() {
        val trueStatus: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            immediateWriteStatus()
        val falseStatus: SettableWriteStatus = SettableWriteStatus()
        falseStatus.markSuccess(false)

        // True then False
        var builder: WriteStatuses.SparseAggregateWriteStatusBuilder = SparseAggregateWriteStatusBuilder()
        builder.add(trueStatus)
        builder.add(falseStatus)
        var aggregate: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = builder.build()
        assertThat(aggregate.isDone()).isTrue()
        assertThat(aggregate.get()).isTrue()

        // False then True
        builder = SparseAggregateWriteStatusBuilder()
        builder.add(falseStatus)
        builder.add(trueStatus)
        aggregate = builder.build()
        assertThat(aggregate.isDone()).isTrue()
        assertThat(aggregate.get()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sparseAggregate_addToAggregator_propagatesFalseNovelty() {
        val child: SparseAggregateWriteStatus = SparseAggregateWriteStatus()
        val builder: WriteStatuses.SparseAggregateWriteStatusBuilder = SparseAggregateWriteStatusBuilder()
        builder.add(child)
        val parent: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = builder.build()

        assertThat(parent.isDone()).isFalse()
        child.notifyWriteSucceeded(false)
        assertThat(parent.isDone()).isTrue()
        assertThat(parent.get()).isFalse()
    }

    companion object {
        private fun assertListenerExecutesImmediately(status: WriteStatus) {
            val captured: AtomicReference<java.lang.Runnable?> = AtomicReference<java.lang.Runnable?>()
            val capturingExecutor: java.util.concurrent.Executor =
                java.util.concurrent.Executor { command: java.lang.Runnable? ->
                    Truth.assertThat(
                        captured.compareAndSet(
                            null,
                            command
                        )
                    ).isTrue()
                }

            val runnable: java.lang.Runnable = java.lang.Runnable {}
            status.addListener(runnable, capturingExecutor)
            Truth.assertThat(captured.get()).isSameInstanceAs(runnable)
        }
    }
}
