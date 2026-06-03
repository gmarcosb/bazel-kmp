// Copyright 2017 The Bazel Authors. All rights reserved.
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
import com.google.devtools.common.options.Converters.PercentageConverter
import com.google.devtools.common.options.OptionsParsingException
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * A test for [PercentageConverter].
 */
@RunWith(JUnit4::class)
class PercentageConverterTest {
    private val converter: PercentageConverter = PercentageConverter()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shouldReturnIntegerValue() {
        val percentage = 50
        org.junit.Assert.assertEquals(percentage, converter.convert(percentage.toString()))
    }

    @org.junit.Test
    fun throwsExceptionWhenInputIsLessThanZero() {
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { converter.convert("-1") })
        Truth.assertThat(e).hasMessageThat().isEqualTo("'-1' should be >= 0")
    }

    @org.junit.Test
    fun throwsExceptionWhenInputIsGreaterThanHundred() {
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { converter.convert("101") })
        Truth.assertThat(e).hasMessageThat().isEqualTo("'101' should be <= 100")
    }

    @org.junit.Test
    fun throwsExceptionWhenInputIsNotANumber() {
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { converter.convert("oops - not a number.") })
        Truth.assertThat(e).hasMessageThat().isEqualTo("'oops - not a number.' is not an int")
    }
}
