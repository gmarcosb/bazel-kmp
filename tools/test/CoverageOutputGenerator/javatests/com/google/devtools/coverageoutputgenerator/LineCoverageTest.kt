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
package com.google.devtools.coverageoutputgenerator

import com.google.common.truth.Truth
import com.google.devtools.coverageoutputgenerator.LineCoverage
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class LineCoverageTest {
    @org.junit.Test
    fun testLineRetrieval() {
        val lineCoverage: LineCoverage = LineCoverage.Companion.create()
        lineCoverage.addLine(0, 0)
        lineCoverage.addLine(1, 1)
        lineCoverage.addLine(3, 5)
        lineCoverage.addLine(15, 2)

        Truth.assertThat(lineCoverage)
            .containsExactly(
                java.util.Map.entry<Int?, Long?>(0, 0L),
                java.util.Map.entry<Int?, Long?>(1, 1L),
                java.util.Map.entry<Int?, Long?>(3, 5L),
                java.util.Map.entry<Int?, Long?>(15, 2L)
            )
    }

    @org.junit.Test
    fun testLargeLineNumbers() {
        val lineCoverage: LineCoverage = LineCoverage.Companion.create()
        lineCoverage.addLine(1000, 1)
        lineCoverage.addLine(1000000, 2)
        lineCoverage.addLine(1499999, 3)
        lineCoverage.addLine(1500000, 4)
        lineCoverage.addLine(1500001, 5)

        Truth.assertThat(lineCoverage)
            .containsExactly(
                java.util.Map.entry<Int?, Long?>(1000, 1L),
                java.util.Map.entry<Int?, Long?>(1000000, 2L),
                java.util.Map.entry<Int?, Long?>(1499999, 3L),
                java.util.Map.entry<Int?, Long?>(1500000, 4L),
                java.util.Map.entry<Int?, Long?>(1500001, 5L)
            )
    }

    @org.junit.Test
    fun testLineIterator() {
        val lineCoverage: LineCoverage = LineCoverage.Companion.create()
        lineCoverage.addLine(1, 1)
        lineCoverage.addLine(3, 5)
        lineCoverage.addLine(15, 2)

        val iterator: MutableIterator<MutableMap.MutableEntry<Int?, Long?>?> = lineCoverage.iterator()

        Truth.assertThat(iterator.hasNext()).isTrue()
        Truth.assertThat(iterator.next()).isEqualTo(java.util.Map.entry<Int?, Long?>(1, 1L))
        Truth.assertThat(iterator.hasNext()).isTrue()
        Truth.assertThat(iterator.next()).isEqualTo(java.util.Map.entry<Int?, Long?>(3, 5L))
        Truth.assertThat(iterator.hasNext()).isTrue()
        Truth.assertThat(iterator.next()).isEqualTo(java.util.Map.entry<Int?, Long?>(15, 2L))
        Truth.assertThat(iterator.hasNext()).isFalse()
        org.junit.Assert.assertThrows<java.util.NoSuchElementException?>(
            java.util.NoSuchElementException::class.java,
            org.junit.function.ThrowingRunnable { iterator.next() })
    }

    @org.junit.Test
    fun testNegativeLineNumberThrows() {
        val lineCoverage: LineCoverage = LineCoverage.Companion.create()
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { lineCoverage.addLine(-1, 1) })
    }
}
