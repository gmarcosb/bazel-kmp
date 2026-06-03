// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.util.ResourceConverter

/** Tests [MultiResourceConverter].  */
@RunWith(JUnit4::class)
class MultiResourceConverterTest {
    var multiResourceConverter: MultiResourceConverter? = null
    var resourceConverter: ResourceConverter<*>? = null

    @Before
    fun setUp() {
        multiResourceConverter = MultiResourceConverter()
        resourceConverter = IntegerConverter({ null }, 1, Int.Companion.MAX_VALUE)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun convert_mnemonicEqualsAuto_returnsDefault() {
        assertThat(multiResourceConverter.convert("someMnemonic=auto").getValue()).isNull()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun convert_mnemonicEqualsKeyword_equalsResourceConverterConvertKeyword() {
        assertThat(multiResourceConverter.convert("someMnemonic=HOST_CPUS-1").getValue())
            .isEqualTo(resourceConverter.convert("HOST_CPUS-1"))
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun convert_auto_returnsDefault() {
        assertThat(multiResourceConverter.convert("auto").getValue()).isNull()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun convert_keyword_equalsResourceConverterConvertKeyword() {
        assertThat(multiResourceConverter.convert("HOST_CPUS-1").getValue())
            .isEqualTo(resourceConverter.convert("HOST_CPUS-1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun convert_mnemonic_savesCorrectKey() {
        assertThat(multiResourceConverter.convert("someMnemonic=10").getKey())
            .isEqualTo("someMnemonic")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun convert_auto_setsEmptyStringAKADefaultAsKey() {
        assertThat(multiResourceConverter.convert("auto").getKey()).isNull()
    }
}
