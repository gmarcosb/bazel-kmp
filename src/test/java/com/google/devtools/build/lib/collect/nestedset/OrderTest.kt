// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.collect.nestedset

import com.google.common.truth.Truth
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Tests for [com.google.devtools.build.lib.collect.nestedset.Order].
 */
@RunWith(JUnit4::class)
class OrderTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParsing() {
        for (current in Order.values()) {
            assertThat(Order.parse(current.starlarkName)).isEqualTo(current)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForErrors() {
        causeError(null)
        causeError("")
        causeError("lol")
        causeError("naive")
        causeError("naivelink")
    }

    @Throws(java.lang.Exception::class)
    private fun causeError(invalidName: String?) {
        val ex: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { Order.parse(invalidName) })
        Truth.assertThat(ex).hasMessageThat().startsWith("Invalid order")
    }
}
