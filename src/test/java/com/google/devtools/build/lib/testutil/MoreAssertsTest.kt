// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.testutil.MoreAsserts.assertContainsSublist

/** Tests [com.google.devtools.build.lib.testutil.MoreAsserts].  */
@RunWith(JUnit4::class)
class MoreAssertsTest {
    @org.junit.Test
    fun testAssertContainsSublistSuccess() {
        val actual: MutableList<String?> = mutableListOf<String?>("a", "b", "c")

        // All single-string combinations.
        assertContainsSublist(actual, "a")
        assertContainsSublist(actual, "b")
        assertContainsSublist(actual, "c")

        // All two-string combinations.
        assertContainsSublist(actual, "a", "b")
        assertContainsSublist(actual, "b", "c")

        // The whole list.
        assertContainsSublist(actual, "a", "b", "c")
    }

    @org.junit.Test
    fun testAssertContainsSublistFailure() {
        val actual: MutableList<String?> = mutableListOf<String?>("a", "b", "c")

        var e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { assertContainsSublist(actual, "d") })
        Truth.assertThat(e).hasMessageThat().startsWith("Did not find [d] as a sublist of [a, b, c]")

        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { assertContainsSublist(actual, "a", "c") })
        Truth.assertThat(e).hasMessageThat().startsWith("Did not find [a, c] as a sublist of [a, b, c]")

        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { assertContainsSublist(actual, "b", "c", "d") })
        Truth.assertThat(e).hasMessageThat().startsWith("Did not find [b, c, d] as a sublist of [a, b, c]")
    }

    @org.junit.Test
    fun testAssertDoesNotContainSublistSuccess() {
        val actual: MutableList<String?> = mutableListOf<String?>("a", "b", "c")
        assertDoesNotContainSublist(actual, "d")
        assertDoesNotContainSublist(actual, "a", "c")
        assertDoesNotContainSublist(actual, "b", "c", "d")
    }

    @org.junit.Test
    fun testAssertDoesNotContainSublistFailure() {
        val actual: MutableList<String?> = mutableListOf<String?>("a", "b", "c")

        // All single-string combinations.
        var e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { assertDoesNotContainSublist(actual, "a") })
        Truth.assertThat(e).hasMessageThat().isEqualTo("Found [a] as a sublist of [a, b, c]")
        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { assertDoesNotContainSublist(actual, "b") })
        Truth.assertThat(e).hasMessageThat().isEqualTo("Found [b] as a sublist of [a, b, c]")
        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { assertDoesNotContainSublist(actual, "c") })
        Truth.assertThat(e).hasMessageThat().isEqualTo("Found [c] as a sublist of [a, b, c]")

        // All two-string combinations.
        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { assertDoesNotContainSublist(actual, "a", "b") })
        Truth.assertThat(e).hasMessageThat().isEqualTo("Found [a, b] as a sublist of [a, b, c]")
        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { assertDoesNotContainSublist(actual, "b", "c") })
        Truth.assertThat(e).hasMessageThat().isEqualTo("Found [b, c] as a sublist of [a, b, c]")

        // The whole list.
        e =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { assertDoesNotContainSublist(actual, "a", "b", "c") })
        Truth.assertThat(e).hasMessageThat().isEqualTo("Found [a, b, c] as a sublist of [a, b, c]")
    }
}
