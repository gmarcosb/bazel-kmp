// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.concurrent

import com.google.common.truth.Truth
import com.google.common.util.concurrent.Futures
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer

@RunWith(JUnit4::class)
class SettableFutureKeyedValueTest {
    @kotlin.jvm.JvmRecord
    private data class Value(val text: String?)

    private class FutureValue
        (key: String?, consumer: BiConsumer<String?, Value?>?) :
        SettableFutureKeyedValue<FutureValue?, String?, Value?>(key, consumer)

    @Test
    @Throws(Exception::class)
    fun takingOwnership_occursExactlyOnce() {
        val future = FutureValue("key", BiConsumer { unusedA: String?, unusedB: Value? -> })
        val tryOwnSuccessCount = AtomicInteger(0)
        val taskCount = 100
        val allDone = CountDownLatch(taskCount)
        for (i in 0..<taskCount) {
            ForkJoinPool.commonPool()
                .execute(
                    Runnable {
                        if (future.tryTakeOwnership()) {
                            tryOwnSuccessCount.getAndIncrement()
                        }
                        allDone.countDown()
                    })
        }
        allDone.await()
        Truth.assertThat(tryOwnSuccessCount.get()).isEqualTo(1)
    }

    @Test
    fun futureFails_ifUnset() {
        val future = FutureValue("key", BiConsumer { unusedA: String?, unusedB: Value? -> })
        future.verifyComplete()

        val thrown = Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, future::get)

        Truth.assertThat(thrown)
            .hasMessageThat()
            .contains("future was unexpectedly unset for key, look for unchecked exceptions")
    }

    @Test
    @Throws(Exception::class)
    fun completeWithValue_propagates() {
        val setValue = AtomicReference<Value?>()
        val future =
            FutureValue(
                "key",
                BiConsumer { key: String?, value: Value? ->
                    Truth.assertThat(key).isEqualTo("key")
                    Truth.assertThat(setValue.compareAndSet(null, value)).isTrue()
                })
        val value = Value("value")

        assertThat(future.completeWith(value)).isEqualTo(value)
        Truth.assertThat(setValue.get()).isEqualTo(value)

        future.verifyComplete()
        assertThat(future.get()).isEqualTo(value)
    }

    @Test
    @Throws(Exception::class)
    fun completeWithFuture_propagates() {
        val setValue = AtomicReference<Value?>()
        val future =
            FutureValue(
                "key",
                BiConsumer { key: String?, value: Value? ->
                    Truth.assertThat(key).isEqualTo("key")
                    Truth.assertThat(setValue.compareAndSet(null, value)).isTrue()
                })
        val value = Value("value")

        assertThat(future.completeWith(Futures.immediateFuture<V?>(value)).get()).isEqualTo(value)
        Truth.assertThat(setValue.get()).isEqualTo(value)

        future.verifyComplete()
        assertThat(future.get()).isEqualTo(value)
    }

    @Test
    fun failWith_propagates() {
        val setValue = AtomicReference<Value?>()
        val future =
            FutureValue(
                "key",
                BiConsumer { key: String?, value: Value? ->
                    Truth.assertThat(key).isEqualTo("key")
                    Truth.assertThat(setValue.compareAndSet(null, value)).isTrue()
                })

        val result: FutureValue = future.failWith(IllegalStateException("injected failure"))
        val thrown = Assert.assertThrows<ExecutionException>(ExecutionException::class.java, result::get)

        Truth.assertThat(thrown).hasCauseThat().isInstanceOf(IllegalStateException::class.java)
        Truth.assertThat(thrown).hasCauseThat().hasMessageThat().contains("injected failure")

        Truth.assertThat(setValue.get()).isNull()

        future.verifyComplete()
        val thrown2 = Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, future::get)
        Truth.assertThat(thrown2).hasCauseThat().isSameInstanceAs(thrown.cause)
    }
}
