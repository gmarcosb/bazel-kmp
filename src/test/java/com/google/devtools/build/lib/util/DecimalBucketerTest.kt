// Copyright 2026 The Bazel Authors. All rights reserved.
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

import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.add
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DecimalBucketerTest {
    @org.junit.Test
    fun testEmpty() {
        val bucketer: DecimalBucketer = DecimalBucketer()
        assertThat(bucketer.getBuckets()).isEmpty()
    }

    @org.junit.Test
    fun testSingleValue() {
        val bucketer: DecimalBucketer = DecimalBucketer()
        bucketer.add(5)
        assertThat(bucketer.getBuckets()).containsExactly(Bucket(5, 6, 1))
    }

    @org.junit.Test
    fun testZero() {
        val bucketer: DecimalBucketer = DecimalBucketer()
        bucketer.add(0)
        assertThat(bucketer.getBuckets()).containsExactly(Bucket(0, 1, 1))
    }

    @org.junit.Test
    fun testMultipleValuesSameBucket() {
        val bucketer: DecimalBucketer = DecimalBucketer()
        bucketer.add(10)
        bucketer.add(15)
        bucketer.add(19)
        assertThat(bucketer.getBuckets()).containsExactly(Bucket(10, 20, 3))
    }

    @org.junit.Test
    fun testMultipleBuckets() {
        val bucketer: DecimalBucketer = DecimalBucketer()
        bucketer.add(5)
        bucketer.add(12)
        bucketer.add(15)
        bucketer.add(25)
        bucketer.add(99)
        bucketer.add(100)

        assertThat(bucketer.getBuckets())
            .containsExactly(
                Bucket(5, 6, 1),
                Bucket(10, 20, 2),
                Bucket(20, 30, 1),
                Bucket(90, 100, 1),
                Bucket(100, 200, 1)
            )
            .inOrder()
    }

    @org.junit.Test
    fun bucketsWithGap() {
        val bucketer: DecimalBucketer = DecimalBucketer()
        bucketer.add(5)
        bucketer.add(61234)
        bucketer.add(69999)

        assertThat(bucketer.getBuckets())
            .containsExactly(Bucket(5, 6, 1), Bucket(60000, 70000, 2))
            .inOrder()
    }

    @org.junit.Test
    fun testNegativeValue() {
        val bucketer: DecimalBucketer = DecimalBucketer()
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { bucketer.add(-1) })
    }

    @org.junit.Test
    fun testLargeValues() {
        val bucketer: DecimalBucketer = DecimalBucketer()
        val `val` = 9000000000000000000L // 9 * 10^18
        bucketer.add(`val`)

        assertThat(bucketer.getBuckets()).containsExactly(Bucket(`val`, Long.Companion.MAX_VALUE, 1))
    }
}
