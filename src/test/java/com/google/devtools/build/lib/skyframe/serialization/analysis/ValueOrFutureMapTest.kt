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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.common.truth.Truth
import com.google.devtools.build.lib.concurrent.SettableFutureKeyedValue
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(JUnit4::class)
class ValueOrFutureMapTest {
    private interface ValueOrFuture

    @kotlin.jvm.JvmRecord
    private data class Value(val text: String?) : ValueOrFuture

    private class FutureValue
        (key: String?, consumer: java.util.function.BiConsumer<String?, Value?>?) :
        SettableFutureKeyedValue<FutureValue?, String?, Value?>(key, consumer), ValueOrFuture

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incomplete_fails() {
        val map: ValueOrFutureMap =
            ValueOrFutureMap(
                ConcurrentHashMap<String?, ValueOrFuture?>(),
                { key: String?, consumer: java.util.function.BiConsumer<kotlin.String?, com.google.devtools.build.lib.skyframe.serialization.analysis.ValueOrFutureMapTest.Value?>? ->
                    FutureValue(
                        key,
                        consumer
                    )
                },
                { future -> future },  // a faulty, no-op populator
                com.google.devtools.build.lib.skyframe.serialization.analysis.ValueOrFutureMapTest.FutureValue::class.java
            )
        val result = map.getValueOrFuture("key") as FutureValue

        val thrown: ExecutionException? = org.junit.Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            org.junit.function.ThrowingRunnable { result.get() })
        Truth.assertThat(thrown).hasCauseThat().hasMessageThat().contains("future was unexpectedly unset")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun immediatePopulatorResult_returnsImmediateValue() {
        val value: Value =
            com.google.devtools.build.lib.skyframe.serialization.analysis.ValueOrFutureMapTest.Value("value")
        val map: ValueOrFutureMap =
            ValueOrFutureMap(
                ConcurrentHashMap<String?, ValueOrFuture?>(),
                { key: String?, consumer: java.util.function.BiConsumer<kotlin.String?, com.google.devtools.build.lib.skyframe.serialization.analysis.ValueOrFutureMapTest.Value?>? ->
                    FutureValue(
                        key,
                        consumer
                    )
                },  // populator that just returns value
                { future ->
                    assertThat(future.key()).isEqualTo("key")
                    future.completeWith(value)
                },
                com.google.devtools.build.lib.skyframe.serialization.analysis.ValueOrFutureMapTest.FutureValue::class.java
            )

        val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            map.getValueOrFuture("key")
        assertThat(result).isSameInstanceAs(value)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asyncCompletion_propagates() {
        val settable: com.google.common.util.concurrent.SettableFuture<Value?> =
            com.google.common.util.concurrent.SettableFuture.create<Value?>()
        val map: ValueOrFutureMap =
            ValueOrFutureMap(
                ConcurrentHashMap<String?, ValueOrFuture?>(),
                { key: String?, consumer: java.util.function.BiConsumer<kotlin.String?, com.google.devtools.build.lib.skyframe.serialization.analysis.ValueOrFutureMapTest.Value?>? ->
                    FutureValue(
                        key,
                        consumer
                    )
                },
                { future ->
                    assertThat(future.key()).isEqualTo("key")
                    future.completeWith(settable)
                },
                com.google.devtools.build.lib.skyframe.serialization.analysis.ValueOrFutureMapTest.FutureValue::class.java
            )

        val result = map.getValueOrFuture("key") as FutureValue
        Truth.assertThat(result.isDone()).isFalse()

        val value: Value =
            com.google.devtools.build.lib.skyframe.serialization.analysis.ValueOrFutureMapTest.Value("value")
        settable.set(value)

        Truth.assertThat(result.get()).isSameInstanceAs(value)

        // After completion, the map contains the value directly, not wrapped by the future.
        assertThat(map.getValueOrFuture("key")).isSameInstanceAs(value)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sameKey_returnsCachedValue() {
        val called: AtomicBoolean = AtomicBoolean(false)
        val value: Value =
            com.google.devtools.build.lib.skyframe.serialization.analysis.ValueOrFutureMapTest.Value("value")
        val map: ValueOrFutureMap =
            ValueOrFutureMap(
                ConcurrentHashMap<String?, ValueOrFuture?>(),
                { key: String?, consumer: java.util.function.BiConsumer<kotlin.String?, com.google.devtools.build.lib.skyframe.serialization.analysis.ValueOrFutureMapTest.Value?>? ->
                    FutureValue(
                        key,
                        consumer
                    )
                },
                { future ->
                    assertThat(future.key()).isEqualTo("key")
                    // Asserts that the populator is called just once.
                    Truth.assertThat(called.compareAndSet(false, true)).isTrue()
                    future.completeWith(value)
                },
                com.google.devtools.build.lib.skyframe.serialization.analysis.ValueOrFutureMapTest.FutureValue::class.java
            )

        val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            map.getValueOrFuture("key")
        assertThat(result).isSameInstanceAs(value)

        Truth.assertThat(called.get()).isTrue()

        val result2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            map.getValueOrFuture("key")
        assertThat(result2).isSameInstanceAs(value)
    }
}
