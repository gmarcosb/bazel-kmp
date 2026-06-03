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

import Converter.Contextless
import com.google.common.truth.Truth
import com.google.devtools.common.options.Converter.Contextless
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** A test for [Converters.CommaSeparatedOptionListConverter].  */
@RunWith(JUnit4::class)
class CommaSeparatedOptionListConverterTest {
    private val converter: Contextless<com.google.common.collect.ImmutableList<String?>?> =
        CommaSeparatedOptionListConverter()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyStringYieldsEmptyList() {
        Truth.assertThat(converter.convert("")).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun commaTwoEmptyStrings() {
        Truth.assertThat(converter.convert(",")).containsExactly("", "").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun leadingCommaYieldsLeadingSpace() {
        Truth.assertThat(converter.convert(",leading,comma"))
            .containsExactly("", "leading", "comma").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun trailingCommaYieldsTrailingSpace() {
        Truth.assertThat(converter.convert("trailing,comma,"))
            .containsExactly("trailing", "comma", "").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun singleWord() {
        Truth.assertThat(converter.convert("lonely")).containsExactly("lonely")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multiWords() {
        Truth.assertThat(converter.convert("one,two,three"))
            .containsExactly("one", "two", "three").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun spaceIsIgnored() {
        Truth.assertThat(converter.convert("one two three")).containsExactly("one two three")
    }
}
