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
package com.google.devtools.build.lib.supplier

import com.google.common.truth.Truth
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicInteger

/** Tests for [EvictableSupplier].  */
@RunWith(JUnit4::class)
class EvictableSupplierTest {
    @Test
    @Throws(Exception::class)
    fun usesInitialCachedValueIfStillInMemory() {
        val initialCachedValue = Any()
        val supplier: EvictableSupplier<*> =
            object : EvictableSupplier<Any?>(initialCachedValue) {
                override fun computeValue(): Any? {
                    throw AssertionError("Should not be called")
                }
            }

        val result: Any? = supplier.get()

        Truth.assertThat(result).isSameInstanceAs(initialCachedValue)
    }

    @Test
    @Throws(Exception::class)
    fun computesValue() {
        val computedValue = Any()
        val supplier: EvictableSupplier<*> =
            object : EvictableSupplier<Any?>( /*cachedValue=*/null) {
                override fun computeValue(): Any {
                    return computedValue
                }
            }

        val result: Any? = supplier.get()

        Truth.assertThat(result).isSameInstanceAs(computedValue)
    }

    @Test
    @Throws(Exception::class)
    fun reusesComputedValueIfStillInMemory() {
        val supplier: EvictableSupplier<*> =
            object : EvictableSupplier<Any?>( /*cachedValue=*/null) {
                override fun computeValue(): Any {
                    return Any()
                }
            }

        val result1: Any? = supplier.get()
        val result2: Any? = supplier.get()

        Truth.assertThat(result2).isSameInstanceAs(result1)
    }

    @Test
    @Throws(Exception::class)
    fun onlyCallsComputeOnceIfResultStillInMemory() {
        val callCount = AtomicInteger(0)
        val supplier: EvictableSupplier<*> =
            object : EvictableSupplier<Any?>( /*cachedValue=*/null) {
                override fun computeValue(): Any {
                    callCount.incrementAndGet()
                    return Any()
                }
            }

        @Suppress("unused") val result:  // Holding a strong reference.
                Any? = supplier.get()
        supplier.get()

        Truth.assertThat(callCount.get()).isEqualTo(1)
    }

    @Test
    fun canPeekAtInitialCachedValue() {
        val initialCachedValue = Any()
        val supplier: EvictableSupplier<*> =
            object : EvictableSupplier<Any?>(initialCachedValue) {
                override fun computeValue(): Any? {
                    throw AssertionError("Should not be called")
                }
            }

        val cachedValue: Any? = supplier.peekCachedValue()

        Truth.assertThat(cachedValue).isSameInstanceAs(initialCachedValue)
    }

    @Test
    @Throws(Exception::class)
    fun canPeekAtComputedValue() {
        val supplier: EvictableSupplier<*> =
            object : EvictableSupplier<Any?>( /*cachedValue=*/null) {
                override fun computeValue(): Any {
                    return Any()
                }
            }

        val result: Any? = supplier.get()
        val cachedValue: Any? = supplier.peekCachedValue()

        Truth.assertThat(cachedValue).isSameInstanceAs(result)
    }

    @Test
    fun peekReturnsNullWhenValueNotComputed() {
        val supplier: EvictableSupplier<*> =
            object : EvictableSupplier<Any?>( /*cachedValue=*/null) {
                override fun computeValue(): Any? {
                    throw AssertionError("Should not be called")
                }
            }

        val cachedValue: Any? = supplier.peekCachedValue()

        Truth.assertThat(cachedValue).isNull()
    }

    @Test
    fun peekReturnsNullWhenInitialCachedValueEvicted() {
        val supplier: EvictableSupplier<*> =
            object : EvictableSupplier<Any?>(Any()) {
                override fun computeValue(): Any? {
                    throw AssertionError("Should not be called")
                }
            }

        supplier.evictForTesting()
        val cachedValue: Any? = supplier.peekCachedValue()

        Truth.assertThat(cachedValue).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun peekReturnsNullWhenComputedValueEvicted() {
        val supplier: EvictableSupplier<*> =
            object : EvictableSupplier<Any?>( /*cachedValue=*/null) {
                override fun computeValue(): Any {
                    return Any()
                }
            }

        supplier.get()
        supplier.evictForTesting()
        val cachedValue: Any? = supplier.peekCachedValue()

        Truth.assertThat(cachedValue).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun recomputesAfterEvictionOfInitialCachedValue() {
        val initialCachedValue = Any()
        val computedValue = Any()
        val supplier: EvictableSupplier<*> =
            object : EvictableSupplier<Any?>(initialCachedValue) {
                override fun computeValue(): Any {
                    return computedValue
                }
            }

        supplier.evictForTesting()
        val result: Any? = supplier.get()

        Truth.assertThat(result).isSameInstanceAs(computedValue)
    }

    @Test
    @Throws(Exception::class)
    fun recomputesAfterEvictionOfComputedValue() {
        val computedValue1 = Any()
        val computedValue2 = Any()
        val supplier: EvictableSupplier<*> =
            object : EvictableSupplier<Any?>( /*cachedValue=*/null) {
                var calls: Int = 0

                override fun computeValue(): Any {
                    ++calls
                    if (calls == 1) {
                        return computedValue1
                    }
                    if (calls == 2) {
                        return computedValue2
                    }
                    throw AssertionError("Called " + calls + " times")
                }
            }

        val result1: Any? = supplier.get()
        supplier.evictForTesting()
        val result2: Any? = supplier.get()

        Truth.assertThat(result1).isSameInstanceAs(computedValue1)
        Truth.assertThat(result2).isSameInstanceAs(computedValue2)
    }

    @Test
    fun computeValueCannotReturnNull() {
        val supplier: EvictableSupplier<*> =
            object : EvictableSupplier<Any?>( /*cachedValue=*/null) {
                override fun computeValue(): Any? {
                    return null
                }
            }

        Assert.assertThrows<NullPointerException?>(
            NullPointerException::class.java,
            ThrowingRunnable { supplier.get() })
    }
}
