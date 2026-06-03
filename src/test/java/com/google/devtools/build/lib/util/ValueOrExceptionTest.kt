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

import com.google.common.testing.EqualsTester
import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.testing.ConverterTester.addEqualityGroup
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ValueOrExceptionTest {
    @org.junit.Test
    fun factoryMethods_requireNonNull() {
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { ValueOrException.ofValue(null) })
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { ValueOrException.ofException(null) })
    }

    @get:org.junit.Test
    val isPresent_basicBehavior: Unit
        get() {
            assertThat(ValueOrException.ofValue(TestValue(123)).isPresent).isTrue()
            assertThat(
                ValueOrException.ofValue(com.google.devtools.build.lib.util.ValueOrExceptionTest.TestException("error") /* as value, not as exception */).isPresent
            )
                .isTrue()
            assertThat(
                ValueOrException.ofException(
                    com.google.devtools.build.lib.util.ValueOrExceptionTest.TestException(
                        "error"
                    )
                ).isPresent
            ).isFalse()
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun get_ofValue_succeeds() {
        val value = TestValue(42)
        val valueOrException: ValueOrException<TestValue?, TestException?> = ValueOrException.ofValue(value)
        assertThat(valueOrException.get()).isSameInstanceAs(value)
        assertThat(valueOrException.getUnchecked()).isSameInstanceAs(value)
    }

    @org.junit.Test
    fun get_ofException_throws() {
        val exception: TestException =
            com.google.devtools.build.lib.util.ValueOrExceptionTest.TestException("i/o error")
        val valueOrException: ValueOrException<TestValue?, TestException?> =
            ValueOrException.ofException(exception)
        Truth.assertThat(
            org.junit.Assert.assertThrows<TestException?>(
                com.google.devtools.build.lib.util.ValueOrExceptionTest.TestException::class.java,
                valueOrException::get
            )
        )
            .isSameInstanceAs(exception)
        Truth.assertThat(
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                valueOrException::getUnchecked
            )
        )
            .hasCauseThat()
            .isSameInstanceAs(exception)
    }

    @get:org.junit.Test
    val exception_basicBehavior: Unit
        get() {
            val value = TestValue(42)
            val exception: TestException =
                com.google.devtools.build.lib.util.ValueOrExceptionTest.TestException("i/o error")
            org.junit.Assert.assertThrows<T?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable { ValueOrException.ofValue(value).exception })
            org.junit.Assert.assertThrows<T?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable { ValueOrException.ofValue(exception /* as value, not as exception */).exception })
            assertThat(ValueOrException.ofException(exception).exception).isSameInstanceAs(exception)
        }

    @org.junit.Test
    fun toString_basicFunctionality() {
        assertThat(ValueOrException.ofValue(TestValue(42)).toString())
            .isEqualTo("ValueOrException.OfValue[TestValue(42)]")
        assertThat(
            ValueOrException.ofValue(com.google.devtools.build.lib.util.ValueOrExceptionTest.TestException("failure"))
                .toString()
        )
            .isEqualTo("ValueOrException.OfValue[TestException('failure')]")
        assertThat(
            ValueOrException.ofException(com.google.devtools.build.lib.util.ValueOrExceptionTest.TestException("failure"))
                .toString()
        )
            .isEqualTo("ValueOrException.OfException[TestException('failure')]")
    }

    @org.junit.Test
    fun hashCode_basicFunctionality() {
        val unused: Int = ValueOrException.ofValue(TestValue(42)).hashCode() // Should not throw.
        val unused2: Int =
            ValueOrException.ofException(com.google.devtools.build.lib.util.ValueOrExceptionTest.TestException("fail"))
                .hashCode() // Should not throw.
    }

    @org.junit.Test
    fun equals() {
        val value12345 = TestValue(12345)
        val failure: TestException = com.google.devtools.build.lib.util.ValueOrExceptionTest.TestException("failure")

        EqualsTester()
            .addEqualityGroup(
                ValueOrException.ofValue(value12345), ValueOrException.ofValue(TestValue(12345))
            )
            .addEqualityGroup(ValueOrException.ofValue(TestValue(12346)))
            .addEqualityGroup(
                ValueOrException.ofException(failure),
                ValueOrException.ofException(com.google.devtools.build.lib.util.ValueOrExceptionTest.TestException("failure"))
            )
            .addEqualityGroup(ValueOrException.ofValue(failure /* as _value_, not exception! */))
            .addEqualityGroup(
                ValueOrException.ofException(
                    com.google.devtools.build.lib.util.ValueOrExceptionTest.TestException(
                        "other failure"
                    )
                )
            )
            .testEquals()
    }

    private class TestValue(private val content: Int) {
        override fun equals(o: Any?): Boolean {
            if (o is TestValue) {
                return o.content == content
            } else {
                return false
            }
        }

        override fun hashCode(): Int {
            return content.hashCode()
        }

        override fun toString(): String {
            return String.format("TestValue(%d)", content)
        }
    }

    // toString() overridden for testing
    private class TestException(message: String?) : java.lang.Exception(message) {
        override fun equals(o: Any?): Boolean {
            if (o is TestException) {
                return o.message == message
            } else {
                return false
            }
        }

        override fun hashCode(): Int {
            return message.hashCode()
        }

        override fun toString(): String {
            return String.format("TestException('%s')", message)
        }
    }
}
