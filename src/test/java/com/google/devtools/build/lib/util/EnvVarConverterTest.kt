// Copyright 2026 The Bazel Authors. All rights reserved.
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

import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [EnvVar.Converter].  */
@RunWith(JUnit4::class)
class EnvVarConverterTest {
    private val converter: EnvVar.Converter = Converter()

    @Throws(java.lang.Exception::class)
    private fun convert(input: String?): EnvVar {
        return converter.convert(input)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assignment() {
        assertThat(convert("A=1")).isEqualTo(Set("A", "1"))
        assertThat(convert("A=ABC")).isEqualTo(Set("A", "ABC"))
        assertThat(convert("A=")).isEqualTo(Set("A", ""))
        assertThat(convert("A=B,C=D")).isEqualTo(Set("A", "B,C=D"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun missingName() {
        assertThat(convert("=NAME")).isEqualTo(Unset("NAME"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun missingValue() {
        assertThat(convert("NAME")).isEqualTo(Inherit("NAME"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun reverseConversionForStarlark() {
        assertThat(converter.reverseForStarlark(converter.convert("a"))).isEqualTo("a")
        assertThat(converter.reverseForStarlark(converter.convert("a=1"))).isEqualTo("a=1")
        assertThat(converter.reverseForStarlark(converter.convert("=a"))).isEqualTo("=a")
    }
}
