// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.dynamic

import com.google.common.truth.Truth
import com.google.devtools.build.lib.dynamic.DynamicExecutionOptions.SignalListConverter
import com.google.devtools.build.lib.dynamic.DynamicExecutionOptions.SignalListConverter.convert
import com.google.devtools.common.options.OptionsParsingException
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DynamicExecutionOptionsTest {
    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testSignalNameConverter_convertsIntegers() {
        val converter: SignalListConverter = SignalListConverter()
        Truth.assertThat(converter.convert("9", null)).containsExactly(9)
        Truth.assertThat(converter.convert("1,12,64", null)).containsExactly(1, 12, 64)
        Truth.assertThat(converter.convert("1,2,1,4", null)).containsExactly(1, 2, 4)
        Truth.assertThat(converter.convert("9", null)).containsExactly(9)
        Truth.assertThat(converter.convert("  12 , 14\t", null)).containsExactly(12, 14)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testSignalNameConverter_badInputs() {
        val converter: SignalListConverter = SignalListConverter()
        Truth.assertThat(converter.convert(null, null)).isEmpty()
        Truth.assertThat(converter.convert("null", null)).isEmpty()
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("\t  ", null) })
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("", null) })
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("-1", null) })
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("5,,6", null) })
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("5.3", null) })
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("", null) })
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { converter.convert("SIGTERM", null) })
    }
}
