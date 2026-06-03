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

import OptionFilters.OptionEffectTag
import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.MethodOptionDefinition
import com.google.devtools.common.options.OptionDefinition
import com.google.devtools.common.options.OptionDocumentationCategory
import com.google.devtools.common.options.OptionEffectTag
import com.google.devtools.common.options.OptionsBase
import com.google.devtools.common.options.OptionsClass
import com.google.devtools.common.options.OptionsParser
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [MethodOptionDefinition].  */
@RunWith(JUnit4::class)
class MethodOptionDefinitionTest {
    /** Dummy options class for testing method-based options.  */
    @OptionsClass
    abstract class MethodOptionsTest : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.AFFECTS_OUTPUTS],
            defaultValue = "42"
        )
        abstract var foo: Int
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMethodOptionParsing() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(MethodOptionsTest::class.java).build()

        parser.parse("--foo=123")

        val options: MethodOptionsTest? = parser.getOptions<MethodOptionsTest?>(MethodOptionsTest::class.java)
        Truth.assertThat(options!!.foo).isEqualTo(123)
    }

    @org.junit.Test
    fun testGeneratedClassGettersAndSetters() {
        val options: MethodOptionsTest = MethodOptionDefinitionTest_MethodOptionsTestImpl()
        options.foo = 123
        Truth.assertThat(options.foo).isEqualTo(123)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMethodOptionDefinitionAccess() {
        val options: MethodOptionsTest = MethodOptionDefinitionTest_MethodOptionsTestImpl()
        val fooDefinition: OptionDefinition = MethodOptionDefinition.get(MethodOptionsTest::class.java, "getFoo")

        fooDefinition.setValue(options, 456)
        Truth.assertThat(options.foo).isEqualTo(456)
        Truth.assertThat(fooDefinition.getRawValue(options)).isEqualTo(456)
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val declaringClass_returnsDeclaringClass: Unit
        get() {
            val definition: MethodOptionDefinition =
                MethodOptionDefinition.get(MethodOptionsTest::class.java, "getFoo")

            // The important part is that the return value of getDeclaringClass() can be passed to
            // getOptions(), but it's nice to not have this test depend on OptionsParser.
            Truth.assertThat(definition.getDeclaringClass<OptionsBase?>(OptionsBase::class.java))
                .isEqualTo(MethodOptionsTest::class.java)
        }
}
