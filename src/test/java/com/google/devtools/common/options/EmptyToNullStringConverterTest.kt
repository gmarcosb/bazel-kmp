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
package com.google.devtools.common.options

import com.google.common.truth.Truth
import com.google.devtools.common.options.OptionsParsingException
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class EmptyToNullStringConverterTest {
    private val converter: com.google.devtools.common.options.Converters.EmptyToNullStringConverter =
        com.google.devtools.common.options.Converters.EmptyToNullStringConverter()

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun emptyStringReturnsNull() {
        Truth.assertThat(converter.convert("")).isNull()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun literalNullStringPassesThrough() {
        Truth.assertThat(converter.convert("null")).isEqualTo("null")
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun regularPathPassesThrough() {
        Truth.assertThat(converter.convert("/path/to/cert.pem")).isEqualTo("/path/to/cert.pem")
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun arbitraryStringPassesThrough() {
        Truth.assertThat(converter.convert("some-value")).isEqualTo("some-value")
    }
}
