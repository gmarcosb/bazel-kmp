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
import com.google.devtools.common.options.BoolOrEnumConverter
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

/**
 * A test for [BoolOrEnumConverter].
 */
@RunWith(JUnit4::class)
class BoolOrEnumConverterTest {
    enum class CompilationMode {
        DBG, OPT
    }

    private class CompilationModeConverter

        : BoolOrEnumConverter<CompilationMode?>(
        com.google.devtools.common.options.BoolOrEnumConverterTest.CompilationMode::class.java,
        "compilation mode",
        com.google.devtools.common.options.BoolOrEnumConverterTest.CompilationMode.DBG,
        com.google.devtools.common.options.BoolOrEnumConverterTest.CompilationMode.OPT
    )

    /** The test options for the CompilationMode hybrid converter.  */
    @OptionsClass
    abstract class CompilationModeTestOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "compile_mode",
            converter = com.google.devtools.common.options.BoolOrEnumConverterTest.CompilationModeConverter::class,
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "dbg"
        )
        abstract val compileMode: CompilationMode?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun converterFromEnum() {
        val converter: CompilationModeConverter =
            com.google.devtools.common.options.BoolOrEnumConverterTest.CompilationModeConverter()
        Truth.assertThat<CompilationMode?>(converter.convert("dbg"))
            .isEqualTo(com.google.devtools.common.options.BoolOrEnumConverterTest.CompilationMode.DBG)
        Truth.assertThat<CompilationMode?>(converter.convert("opt"))
            .isEqualTo(com.google.devtools.common.options.BoolOrEnumConverterTest.CompilationMode.OPT)

        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { converter.convert("none") })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo("Not a valid compilation mode: 'none' (should be dbg or opt)")
        Truth.assertThat(converter.getTypeDescription()).isEqualTo("dbg or opt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun convertFromBooleanValues() {
        val falseValues: Array<String?> = arrayOf<String>("false", "0")
        val trueValues: Array<String?> = arrayOf<String>("true", "1")
        val converter: CompilationModeConverter =
            com.google.devtools.common.options.BoolOrEnumConverterTest.CompilationModeConverter()

        for (falseValue in falseValues) {
            Truth.assertThat<CompilationMode?>(converter.convert(falseValue))
                .isEqualTo(com.google.devtools.common.options.BoolOrEnumConverterTest.CompilationMode.OPT)
        }

        for (trueValue in trueValues) {
            Truth.assertThat<CompilationMode?>(converter.convert(trueValue))
                .isEqualTo(com.google.devtools.common.options.BoolOrEnumConverterTest.CompilationMode.DBG)
        }
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun prefixedWithNo() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(CompilationModeTestOptions::class.java).build()
        parser.parse("--nocompile_mode")
        val options: CompilationModeTestOptions? =
            parser.getOptions<CompilationModeTestOptions?>(CompilationModeTestOptions::class.java)
        Truth.assertThat<CompilationMode?>(options!!.compileMode).isNotNull()
        Truth.assertThat<CompilationMode?>(options.compileMode)
            .isEqualTo(com.google.devtools.common.options.BoolOrEnumConverterTest.CompilationMode.OPT)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun missingValueAsBoolConversion() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(CompilationModeTestOptions::class.java).build()
        parser.parse("--compile_mode")
        val options: CompilationModeTestOptions? =
            parser.getOptions<CompilationModeTestOptions?>(CompilationModeTestOptions::class.java)
        Truth.assertThat<CompilationMode?>(options!!.compileMode).isNotNull()
        Truth.assertThat<CompilationMode?>(options.compileMode)
            .isEqualTo(com.google.devtools.common.options.BoolOrEnumConverterTest.CompilationMode.DBG)
    }
}
