// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.collect

import com.google.common.truth.Truth
import com.google.devtools.build.lib.metrics.criticalpath.CriticalPathComputer.aggregate
import com.google.devtools.build.lib.packages.metrics.PackageMetricsRecorder.clear
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.Collections
import java.util.stream.Collectors
import java.util.stream.IntStream

/** Tests for [Extrema].  */
@RunWith(JUnit4::class)
class ExtremaTest {
    @org.junit.Test
    fun handlesDupes() {
        val extrema: Extrema<Int?> = Extrema.min(3)
        extrema.aggregate(4)
        extrema.aggregate(3)
        extrema.aggregate(1)
        extrema.aggregate(2)
        extrema.aggregate(1)
        extrema.aggregate(3)
        extrema.aggregate(1)
        assertThat(extrema.extremeElements).containsExactly(1, 1, 1)
    }

    @org.junit.Test
    fun testIsEmpty() {
        val extrema: Extrema<Int?> = Extrema.max(2)
        assertThat(extrema.isEmpty).isTrue()

        extrema.aggregate(1)
        assertThat(extrema.isEmpty).isFalse()
    }

    @org.junit.Test
    fun testClear() {
        val extrema: Extrema<Int?> = Extrema.max(2)

        extrema.aggregate(1)
        extrema.clear()
        assertThat(extrema.isEmpty).isTrue()
    }

    @org.junit.Test
    fun customComparator() {
        class BoxedInt private constructor(private val i: Int)

        val extrema: Extrema<BoxedInt?> =
            Extrema.max(
                2,
                object : java.util.Comparator<BoxedInt?> {
                    override fun compare(bi1: BoxedInt, bi2: BoxedInt): Int {
                        return java.lang.Integer.compare(bi1.i, bi2.i)
                    }
                })
        extrema.aggregate(BoxedInt(4))
        extrema.aggregate(BoxedInt(1))
        extrema.aggregate(BoxedInt(2))
        extrema.aggregate(BoxedInt(3))
        extrema.aggregate(BoxedInt(5))
        val extremeElements: com.google.common.collect.ImmutableList<Int?>? =
            extrema.extremeElements.stream()
                .map({ bi -> bi.i })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        Truth.assertThat(extremeElements).containsExactly(5, 4).inOrder()
    }

    @org.junit.Test
    fun minExtremaSmallK() {
        runRangeTest(Extrema.min(5), 1, 100, com.google.common.collect.ImmutableList.of<Int?>(1, 2, 3, 4, 5))
    }

    @org.junit.Test
    fun minExtremaLargeK() {
        runRangeTest(Extrema.min(10), 1, 5, com.google.common.collect.ImmutableList.of<Int?>(1, 2, 3, 4, 5))
    }

    @org.junit.Test
    fun maxExtremaSmallK() {
        runRangeTest(Extrema.max(5), 1, 100, com.google.common.collect.ImmutableList.of<Int?>(100, 99, 98, 97, 96))
    }

    @org.junit.Test
    fun maxExtremaLargeK() {
        runRangeTest(Extrema.max(10), 1, 5, com.google.common.collect.ImmutableList.of<Int?>(5, 4, 3, 2, 1))
    }

    @org.junit.Test
    fun testEmptyExtrema() {
        val extrema: Extrema<Int?> = Extrema.max(0)
        extrema.aggregate(1)
        assertThat(extrema.isEmpty).isTrue()
        assertThat(extrema.extremeElements).isEmpty()

        extrema.clear()
        assertThat(extrema.isEmpty).isTrue()
        assertThat(extrema.extremeElements).isEmpty()
    }

    @org.junit.Test
    fun testNegativeExtremaDisallowed() {
        val thrown: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { Extrema.min(-1) })
        Truth.assertThat(thrown).hasMessageThat().isEqualTo("invalid k (-1), must be >=0")
    }

    private fun runRangeTest(
        extrema: Extrema<Int?>,
        leftEndpointInclusive: Int,
        rightEndpointInclusive: Int,
        expected: com.google.common.collect.ImmutableList<Int?>?
    ) {
        assertThat(extrema.extremeElements).isEmpty()
        closedRangeShuffled(leftEndpointInclusive, rightEndpointInclusive).forEach(extrema::aggregate)
        assertThat(extrema.extremeElements).containsExactlyElementsIn(expected).inOrder()
        extrema.clear()
        assertThat(extrema.extremeElements).isEmpty()
    }

    companion object {
        private fun closedRangeShuffled(
            leftEndpointInclusive: Int, rightEndpointInclusive: Int
        ): java.util.stream.Stream<Int?>? {
            val list: MutableList<Int?> =
                IntStream.rangeClosed(leftEndpointInclusive, rightEndpointInclusive).boxed().collect(
                    Collectors.toList()
                )
            Collections.shuffle(list)
            return list.stream()
        }
    }
}
