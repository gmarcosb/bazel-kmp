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
package com.google.devtools.build.lib.util

import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.util.ShallowObjectSizeComputerTest
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit tests for [ShallowObjectSizeComputer].
 * 
 * 
 * Tests by comparing the computed object size against measurements on the JVM.
 */
@RunWith(JUnit4::class)
class ShallowObjectSizeComputerTest {
    private class Empty

    @org.junit.Test
    fun testEmptyClass() {
        assertComputedSizeIsCorrect(java.util.function.Supplier { com.google.devtools.build.lib.util.ShallowObjectSizeComputerTest.Empty() })
    }

    @Suppress("unused")
    private class OneReference {
        private val ref: Any? = null
    }

    @org.junit.Test
    fun testOneReference() {
        assertComputedSizeIsCorrect(java.util.function.Supplier { OneReference() })
    }

    @Suppress("unused")
    private class TwoReferences {
        private val ref1: Any? = null
        private val ref2: Any? = null
    }

    @org.junit.Test
    fun testTwoReferences() {
        assertComputedSizeIsCorrect(java.util.function.Supplier { TwoReferences() })
    }

    @Suppress("unused")
    private class ThreeBooleans {
        private val bool1 = false
        private val bool2 = false
        private val bool3 = false
    }

    @org.junit.Test
    fun testThreeBooleans() {
        assertComputedSizeIsCorrect(java.util.function.Supplier { ThreeBooleans() })
    }

    @org.junit.Test
    fun testObjectArray() {
        assertComputedSizeIsCorrect(java.util.function.Supplier { arrayOfNulls<Any>(4) })
    }

    @org.junit.Test
    fun testBooleanArray() {
        assertComputedSizeIsCorrect(java.util.function.Supplier { BooleanArray(4) })
    }

    // TODO(lberki): Lambdas without any values closed over must (eventually) be special-cased since
    // they don't require heap.
    @org.junit.Test
    fun testClosureWithOneValue() {
        val o = Any()
        assertComputedSizeIsCorrect(java.util.function.Supplier { java.util.function.Supplier { o } as java.util.function.Supplier<Any?> })
    }

    @org.junit.Test
    fun testClosureWithThreeValues() {
        val o1 = Any()
        val o2 = Any()
        val o3 = Any()
        assertComputedSizeIsCorrect(java.util.function.Supplier {
            java.util.function.Supplier {
                com.google.common.collect.ImmutableList.of<Any?>(
                    o1,
                    o2,
                    o3
                )
            } as java.util.function.Supplier<Any?>
        })
    }

    private fun assertComputedSizeIsCorrect(createInstance: java.util.function.Supplier<Any?>) {
        val sampleToCompute: Any? = createInstance.get()
        val computedSize: Long = ShallowObjectSizeComputer.getShallowSize(sampleToCompute)
        val measuredSize = measureSize(createInstance)
        Truth.assertThat(computedSize).isEqualTo(measuredSize)
    }

    companion object {
        private fun measureSize(createInstance: java.util.function.Supplier<Any?>): Long {
            val storage = arrayOfNulls<Any>(1)

            // NB: this is com.sun.management.ThreadMXBean, NOT java.lang.management.ThreadMXBean
            val bean: com.sun.management.ThreadMXBean =
                java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            bean.setThreadAllocatedMemoryEnabled(true)

            // One would think that this is at least somewhat inaccurate, but according to measurements it's
            // accurate to the last byte. The mind boggles.
            val before: Long = bean.getCurrentThreadAllocatedBytes()
            storage[0] = createInstance.get()
            val after: Long = bean.getCurrentThreadAllocatedBytes()

            return after - before
        }
    }
}
