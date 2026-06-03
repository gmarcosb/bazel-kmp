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
import com.google.devtools.common.options.Converters.LogLevelConverter
import com.google.devtools.common.options.OptionsParsingException
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** A test for [LogLevelConverter].  */
@RunWith(JUnit4::class)
class LogLevelConverterTest {
    private val converter: LogLevelConverter = LogLevelConverter()

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun convertsIntsToLevels() {
        var levelId = 0
        for (level in LogLevelConverter.LEVELS) {
            Truth.assertThat(converter.convert((levelId++).toString())).isEqualTo(level)
        }
    }

    @org.junit.Test
    fun throwsExceptionWhenInputIsNotANumber() {
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { converter.convert("oops - not a number.") })
        Truth.assertThat(e).hasMessageThat().isEqualTo("Not a log level: oops - not a number.")
    }

    @org.junit.Test
    fun throwsExceptionWhenInputIsInvalidInteger() {
        for (example in intArrayOf(-1, 100, 50000)) {
            val e: OptionsParsingException? =
                org.junit.Assert.assertThrows<OptionsParsingException?>(
                    OptionsParsingException::class.java,
                    org.junit.function.ThrowingRunnable { converter.convert(example.toString()) })
            val expected = "Not a log level: " + example.toString()
            Truth.assertThat(e).hasMessageThat().isEqualTo(expected)
        }
    }
}
