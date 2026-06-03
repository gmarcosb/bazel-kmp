// Copyright 2019 The Bazel Authors. All rights reserved.
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
import com.google.devtools.common.options.Converters.StringToStringListConverter
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Test for [Converters.AssignmentToListOfValuesConverter].  */
@RunWith(JUnit4::class)
class StringToStringListConverterTest {
    protected var converter: StringToStringListConverter = StringToStringListConverter()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nameEqualsValue() {
        Truth.assertThat(converter.convert("name=value"))
            .isEqualTo(
                com.google.common.collect.Maps.immutableEntry<String?, com.google.common.collect.ImmutableList<String?>?>(
                    "name",
                    com.google.common.collect.ImmutableList.of<String?>("value")
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nameEqualsMultipleValues() {
        Truth.assertThat(converter.convert("name=value1,value2"))
            .isEqualTo(
                com.google.common.collect.Maps.immutableEntry<String?, com.google.common.collect.ImmutableList<String?>?>(
                    "name",
                    com.google.common.collect.ImmutableList.of<String?>("value1", "value2")
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nameEqualsNoValue_setsEmptyValue() {
        Truth.assertThat(converter.convert("name="))
            .isEqualTo(
                com.google.common.collect.Maps.immutableEntry<String?, com.google.common.collect.ImmutableList<Any?>?>(
                    "name",
                    com.google.common.collect.ImmutableList.of<Any?>()
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun equalsValue_setsEmptyKey() {
        Truth.assertThat(converter.convert("=value"))
            .isEqualTo(
                com.google.common.collect.Maps.immutableEntry<String?, com.google.common.collect.ImmutableList<String?>?>(
                    "",
                    com.google.common.collect.ImmutableList.of<String?>("value")
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun justValue_setsEmptyKey() {
        Truth.assertThat(converter.convert("value"))
            .isEqualTo(
                com.google.common.collect.Maps.immutableEntry<String?, com.google.common.collect.ImmutableList<String?>?>(
                    "",
                    com.google.common.collect.ImmutableList.of<String?>("value")
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noNameMultipleValues() {
        Truth.assertThat(converter.convert("value1,value2"))
            .isEqualTo(
                com.google.common.collect.Maps.immutableEntry<String?, com.google.common.collect.ImmutableList<String?>?>(
                    "",
                    com.google.common.collect.ImmutableList.of<String?>("value1", "value2")
                )
            )
    }
}
