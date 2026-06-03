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
package com.google.devtools.common.options

import com.google.common.truth.Truth
import com.google.devtools.common.options.Converters.ByteSizeConverter
import com.google.devtools.common.options.OptionsParsingException
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.math.BigInteger

/** Tests for [ByteSizeConverterTest].  */
@RunWith(JUnit4::class)
class ByteSizeConverterTest {
    var converter: ByteSizeConverter = ByteSizeConverter()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun empty() {
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun zero() {
        Truth.assertThat(converter.convert("0")).isEqualTo(0L)
        Truth.assertThat(converter.convert("00")).isEqualTo(0L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun negative() {
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("-1") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fractional() {
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("1.1") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonDecimal() {
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("1f") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noSuffix() {
        Truth.assertThat(converter.convert("123")).isEqualTo(123L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun kiloSuffix() {
        Truth.assertThat(converter.convert("123K")).isEqualTo(123 * 1024L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun megaSuffix() {
        Truth.assertThat(converter.convert("123M")).isEqualTo(123 * 1024L * 1024L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun gigaSuffix() {
        Truth.assertThat(converter.convert("123G")).isEqualTo(123 * 1024L * 1024L * 1024L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun teraSuffix() {
        Truth.assertThat(converter.convert("123T")).isEqualTo(123 * 1024L * 1024L * 1024L * 1024L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noSuffixOverflow() {
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert(BigInteger.valueOf(2).pow(63).toString()) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun kiloOverflow() {
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert(BigInteger.valueOf(2).pow(53).toString() + "K") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun megaOverflow() {
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert(BigInteger.valueOf(2).pow(43).toString() + "M") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun gigaOverflow() {
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert(BigInteger.valueOf(2).pow(33).toString() + "G") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun teraOverflow() {
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert(BigInteger.valueOf(2).pow(23).toString() + "T") })
    }
}
