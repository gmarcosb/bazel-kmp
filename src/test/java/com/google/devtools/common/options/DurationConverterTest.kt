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

import Converters.DurationConverter
import com.google.common.truth.Truth
import com.google.devtools.common.options.Converters.DurationConverter
import com.google.devtools.common.options.OptionsParsingException
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [DurationConverter].  */
@RunWith(JUnit4::class)
class DurationConverterTest {
    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testDurationConverter_zero() {
        val converter: DurationConverter = DurationConverter()

        Truth.assertThat<java.time.Duration?>(converter.convert("0")).isEqualTo(java.time.Duration.ZERO)
        Truth.assertThat<java.time.Duration?>(converter.convert("0d")).isEqualTo(java.time.Duration.ZERO)
        Truth.assertThat<java.time.Duration?>(converter.convert("0h")).isEqualTo(java.time.Duration.ZERO)
        Truth.assertThat<java.time.Duration?>(converter.convert("0m")).isEqualTo(java.time.Duration.ZERO)
        Truth.assertThat<java.time.Duration?>(converter.convert("0s")).isEqualTo(java.time.Duration.ZERO)
        Truth.assertThat<java.time.Duration?>(converter.convert("0ms")).isEqualTo(java.time.Duration.ZERO)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testDurationConverter_basic() {
        val converter: DurationConverter = DurationConverter()

        Truth.assertThat<java.time.Duration?>(converter.convert("10d")).isEqualTo(java.time.Duration.ofDays(10))
        Truth.assertThat<java.time.Duration?>(converter.convert("20h")).isEqualTo(java.time.Duration.ofHours(20))
        Truth.assertThat<java.time.Duration?>(converter.convert("30m")).isEqualTo(java.time.Duration.ofMinutes(30))
        Truth.assertThat<java.time.Duration?>(converter.convert("40s")).isEqualTo(java.time.Duration.ofSeconds(40))
        Truth.assertThat<java.time.Duration?>(converter.convert("50ms")).isEqualTo(java.time.Duration.ofMillis(50))
        Truth.assertThat<java.time.Duration?>(converter.convert("60ns")).isEqualTo(java.time.Duration.ofNanos(60))
    }

    @org.junit.Test
    fun testDurationConverter_invalidInputs() {
        val converter: DurationConverter = DurationConverter()

        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("") })

        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("-10d") })

        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("h") })

        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("1g") })
    }
}
