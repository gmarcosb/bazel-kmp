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

import OptionFilters.OptionEffectTag
import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.OptionDocumentationCategory
import com.google.devtools.common.options.OptionEffectTag
import com.google.devtools.common.options.OptionsBase
import com.google.devtools.common.options.OptionsClass
import com.google.devtools.common.options.OptionsParser
import com.google.devtools.common.options.OptionsParsingException
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** A test for [EnumConverter].  */
@RunWith(JUnit4::class)
class EnumConverterTest {
    private enum class CompilationMode {
        DBG,
        OPT
    }

    private class CompilationModeConverter : com.google.devtools.common.options.EnumConverter<CompilationMode?>(
        com.google.devtools.common.options.EnumConverterTest.CompilationMode::class.java,
        "compilation mode"
    )

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun converterForEnumWithTwoValues() {
        val converter: CompilationModeConverter =
            com.google.devtools.common.options.EnumConverterTest.CompilationModeConverter()
        Truth.assertThat<CompilationMode?>(converter.convert("dbg"))
            .isEqualTo(com.google.devtools.common.options.EnumConverterTest.CompilationMode.DBG)
        Truth.assertThat<CompilationMode?>(converter.convert("opt"))
            .isEqualTo(com.google.devtools.common.options.EnumConverterTest.CompilationMode.OPT)
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { converter.convert("none") })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo("Not a valid compilation mode: 'none' (should be dbg or opt)")
        Truth.assertThat(converter.getTypeDescription()).isEqualTo("dbg or opt")
    }

    private enum class Fruit {
        APPLE,
        BANANA,
        CHERRY
    }

    private class FruitConverter : com.google.devtools.common.options.EnumConverter<Fruit?>(Fruit::class.java, "fruit")

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun typeDescriptionForEnumWithThreeValues() {
        val converter = FruitConverter()
        // We always use lowercase in the user-visible messages:
        Truth.assertThat(converter.getTypeDescription()).isEqualTo("apple, banana or cherry")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun converterIsCaseInsensitive() {
        val converter = FruitConverter()
        Truth.assertThat<Fruit?>(converter.convert("bAnANa")).isSameInstanceAs(Fruit.BANANA)
    }

    // Regression test: lists of enum using a subclass of EnumConverter don't work
    private class AlphabetEnumConverter :
        com.google.devtools.common.options.EnumConverter<AlphabetEnum?>(AlphabetEnum::class.java, "alphabet enum")

    enum class AlphabetEnum {
        ALPHA,
        BRAVO,
        CHARLY,
        DELTA,
        ECHO
    }

    @OptionsClass
    abstract class EnumListTestOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "goo",
            allowMultiple = true,
            converter = AlphabetEnumConverter::class,
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val goo: MutableList<AlphabetEnum?>?
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun enumList() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(EnumListTestOptions::class.java).build()
        parser.parse("--goo=ALPHA", "--goo=BRAVO")
        val options: EnumListTestOptions? = parser.getOptions<EnumListTestOptions?>(EnumListTestOptions::class.java)
        Truth.assertThat(options!!.goo).isNotNull()
        Truth.assertThat(options.goo).hasSize(2)
        Truth.assertThat<AlphabetEnum?>(options.goo!!.get(0)).isEqualTo(AlphabetEnum.ALPHA)
        Truth.assertThat<AlphabetEnum?>(options.goo!!.get(1)).isEqualTo(AlphabetEnum.BRAVO)
    }

    private enum class NonUniqueStringRepresentationEnum(str: String) {
        X("DUPLICATE"),
        Y("DuPlIcAtE");

        private val str: String?

        init {
            this.str = str
        }

        override fun toString(): String {
            return str!!
        }
    }

    private class NonUniqueStringRepresentationEnumConverter

        : com.google.devtools.common.options.EnumConverter<NonUniqueStringRepresentationEnum?>(
        NonUniqueStringRepresentationEnum::class.java,
        "enum with non-unique string representations"
    )

    @org.junit.Test
    fun enumWithNonUniqueStringRepresentation_throws() {
        Truth.assertThat(
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { NonUniqueStringRepresentationEnumConverter() })
        )
            .hasMessageThat()
            .contains(
                "NonUniqueStringRepresentationEnum values X and Y collide in their case-insensitive"
                        + " string representation 'duplicate'"
            )
    }
}
