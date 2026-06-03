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
import com.google.devtools.common.options.Converters.AssignmentConverter
import com.google.devtools.common.options.OptionsParsingException
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [Converters.AssignmentConverter].  */
@RunWith(JUnit4::class)
class AssignmentConverterTest {
    private val converter: AssignmentConverter = AssignmentConverter()

    @Throws(java.lang.Exception::class)
    private fun convert(input: String?): MutableMap.MutableEntry<String?, String?>? {
        return converter.convert(input)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assignment() {
        Truth.assertThat(convert("A=1"))
            .isEqualTo(com.google.common.collect.Maps.immutableEntry<String?, String?>("A", "1"))
        Truth.assertThat(convert("A=ABC"))
            .isEqualTo(com.google.common.collect.Maps.immutableEntry<String?, String?>("A", "ABC"))
        Truth.assertThat(convert("A="))
            .isEqualTo(com.google.common.collect.Maps.immutableEntry<String?, String?>("A", ""))
        Truth.assertThat(convert("A=B,C=D"))
            .isEqualTo(com.google.common.collect.Maps.immutableEntry<String?, String?>("A", "B,C=D"))
    }

    @org.junit.Test
    fun missingName() {
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { convert("=VALUE") })
    }

    @org.junit.Test
    fun missingValue() {
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { convert("NAME") })
    }

    @org.junit.Test
    fun immutability() {
        org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
            java.lang.UnsupportedOperationException::class.java,
            org.junit.function.ThrowingRunnable { convert("A=B")!!.setValue("C") })
    }

    @org.junit.Test
    fun emptyString() {
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { convert("") })
    }
}
