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
package com.google.devtools.common.options

import com.google.common.truth.Truth
import com.google.devtools.common.options.GenericTypeHelper
import net.starlark.java.syntax.TypeTable.getType
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Tests [GenericTypeHelper].
 */
@RunWith(JUnit4::class)
class GenericTypeHelperTest {
    private interface DoSomething<T> {
        fun doIt(): T?
    }

    private class StringSomething : DoSomething<String?> {
        override fun doIt(): String? {
            return null
        }
    }

    private open class EnumSomething<T> : DoSomething<T?> {
        override fun doIt(): T? {
            return null
        }
    }

    private open class AlphabetSomething : EnumSomething<String?>()

    private class AlphabetTwoSomething : AlphabetSomething()

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val converterType: Unit
        get() {
            assertDoIt(String::class.java, StringSomething::class.java)
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val converterTypeForGenericExtension: Unit
        get() {
            assertDoIt(String::class.java, AlphabetSomething::class.java)
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val converterTypeForGenericExtensionSecondGrade: Unit
        get() {
            assertDoIt(String::class.java, AlphabetTwoSomething::class.java)
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val converterTypeForParameterizedType: Unit
        get() {
            val enSthTypeToken: com.google.common.reflect.TypeToken<EnumSomething<String?>?> =
                object : com.google.common.reflect.TypeToken<EnumSomething<String?>?>() {}
            val instance = EnumSomething<String?>()
            Truth.assertThat(
                GenericTypeHelper.getActualReturnType(
                    enSthTypeToken.getType(), instance.javaClass.getMethod("doIt")
                )
            )
                .isEqualTo(String::class.java)
        }

    @org.junit.Test
    fun assignableFromPrimitive() {
        Truth.assertThat(GenericTypeHelper.isAssignableFrom(java.lang.Integer.TYPE, Int::class.java)).isTrue()
        Truth.assertThat(GenericTypeHelper.isAssignableFrom(java.lang.Integer.TYPE, java.lang.Long.TYPE)).isFalse()
        Truth.assertThat(GenericTypeHelper.isAssignableFrom(java.lang.Integer.TYPE, java.lang.Integer.TYPE)).isFalse()
    }

    @org.junit.Test
    fun assignableFromSuper() {
        Truth.assertThat(GenericTypeHelper.isAssignableFrom(DoSomething::class.java, EnumSomething::class.java))
            .isTrue()
        Truth.assertThat(GenericTypeHelper.isAssignableFrom(EnumSomething::class.java, AlphabetSomething::class.java))
            .isTrue()
    }

    @org.junit.Test
    fun assignableFromSuperSecondGrade() {
        Truth.assertThat(
            GenericTypeHelper.isAssignableFrom(
                EnumSomething::class.java,
                AlphabetTwoSomething::class.java
            )
        )
            .isTrue()
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun assertDoIt(
            expected: java.lang.Class<*>?,
            implementingClass: java.lang.Class<out DoSomething<*>?>
        ) {
            Truth.assertThat(
                GenericTypeHelper.getActualReturnType(
                    implementingClass, implementingClass.getMethod("doIt")
                )
            )
                .isEqualTo(expected)
        }
    }
}
