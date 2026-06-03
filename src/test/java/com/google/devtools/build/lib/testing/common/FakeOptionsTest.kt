// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.testing.common

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.TestAspects.DepsVisitingFileAspect.name
import com.google.devtools.build.lib.exec.util.TestExecutorBuilder.build
import com.google.devtools.build.lib.testing.common.FakeOptionsTest
import com.google.devtools.common.options.OptionDocumentationCategory
import com.google.devtools.common.options.OptionEffectTag
import com.google.devtools.common.options.OptionsBase
import com.google.devtools.common.options.OptionsClass
import com.google.devtools.common.options.OptionsProvider
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [FakeOptions] utility.  */
@RunWith(JUnit4::class)
class FakeOptionsTest {
    @get:org.junit.Test
    val options_unspecifiedClass_returnsNull: Unit
        get() {
            val optionsProvider: OptionsProvider =
                FakeOptions.builder()
                    .put(com.google.devtools.common.options.Options.getDefaults<O?>(com.google.devtools.build.lib.testing.common.FakeOptionsTest.TestOptions::class.java))
                    .build()

            Truth.assertThat(optionsProvider.getOptions<TestOptions2?>(TestOptions2::class.java)).isNull()
        }

    @get:org.junit.Test
    val options_returnsProvidedValue: Unit
        get() {
            val options: TestOptions =
                com.google.devtools.common.options.Options.getDefaults<TestOptions>(
                    com.google.devtools.build.lib.testing.common.FakeOptionsTest.TestOptions::class.java
                )
            options.value = "value"
            val optionsProvider: OptionsProvider = FakeOptions.builder().put(options).build()

            Truth.assertThat(
                optionsProvider.getOptions<TestOptions?>(
                    com.google.devtools.build.lib.testing.common.FakeOptionsTest.TestOptions::class.java
                )
            ).isEqualTo(options)
        }

    @get:org.junit.Test
    val options_of_returnsOnlyProvidedValue: Unit
        get() {
            val options: TestOptions =
                com.google.devtools.common.options.Options.getDefaults<TestOptions>(
                    com.google.devtools.build.lib.testing.common.FakeOptionsTest.TestOptions::class.java
                )
            options.value = "value"
            val optionsProvider: OptionsProvider = FakeOptions.of(options)

            Truth.assertThat(
                optionsProvider.getOptions<TestOptions?>(
                    com.google.devtools.build.lib.testing.common.FakeOptionsTest.TestOptions::class.java
                )
            ).isEqualTo(options)
            Truth.assertThat(optionsProvider.getOptions<TestOptions2?>(TestOptions2::class.java)).isNull()
        }

    @get:org.junit.Test
    val options_specifiedDefaultsClass_returnsDefaultOptions: Unit
        get() {
            assertGetOptionsReturnsDefaults(
                FakeOptions.builder().putDefaults(
                    com.google.devtools.build.lib.testing.common.FakeOptionsTest.TestOptions::class.java,
                    TestOptions2::class.java
                ).build()
            )
        }

    @get:org.junit.Test
    val options_ofDefaults_returnsDefaultOptions: Unit
        get() {
            assertGetOptionsReturnsDefaults(
                FakeOptions.ofDefaults(
                    com.google.devtools.build.lib.testing.common.FakeOptionsTest.TestOptions::class.java,
                    TestOptions2::class.java
                )
            )
        }

    @get:org.junit.Test
    val starlarkOptions_returnsEmpty: Unit
        get() {
            val optionsProvider: OptionsProvider =
                FakeOptions.builder()
                    .put(com.google.devtools.common.options.Options.getDefaults<O?>(com.google.devtools.build.lib.testing.common.FakeOptionsTest.TestOptions::class.java))
                    .putDefaults(TestOptions2::class.java)
                    .build()

            Truth.assertThat(optionsProvider.getStarlarkOptions()).isEmpty()
        }

    @get:org.junit.Test
    val starlarkOptions_emptyOptions_returnsEmpty: Unit
        get() {
            assertThat(FakeOptions.builder().build().getStarlarkOptions()).isEmpty()
        }

    @org.junit.Test
    fun build_specifiedValueTwiceForSameClass_fails() {
        val builder: FakeOptions.Builder =
            FakeOptions.builder()
                .put(com.google.devtools.common.options.Options.getDefaults<O?>(com.google.devtools.build.lib.testing.common.FakeOptionsTest.TestOptions::class.java))
                .put(com.google.devtools.common.options.Options.getDefaults<O?>(com.google.devtools.build.lib.testing.common.FakeOptionsTest.TestOptions::class.java))

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            builder::build
        )
    }

    @org.junit.Test
    fun build_specifiedValueAndDefaultsForSameClass_fails() {
        val builder: FakeOptions.Builder =
            FakeOptions.builder()
                .put(com.google.devtools.common.options.Options.getDefaults<O?>(com.google.devtools.build.lib.testing.common.FakeOptionsTest.TestOptions::class.java))
                .putDefaults(com.google.devtools.build.lib.testing.common.FakeOptionsTest.TestOptions::class.java)

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            builder::build
        )
    }

    @org.junit.Test
    fun build_defaultsTwiceForSameClass_fails() {
        val builder: FakeOptions.Builder =
            FakeOptions.builder().putDefaults(
                com.google.devtools.build.lib.testing.common.FakeOptionsTest.TestOptions::class.java,
                com.google.devtools.build.lib.testing.common.FakeOptionsTest.TestOptions::class.java
            )

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            builder::build
        )
    }

    /** Simple test option class example.  */
    @OptionsClass
    abstract class TestOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "option1",
            defaultValue = "TestOptions default",
            effectTags = [OptionEffectTag.NO_OP],
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED
        )
        abstract var value: String?
    }

    /** Simple test option class, different from [TestOptions].  */
    @OptionsClass
    abstract class TestOptions2 : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "option2",
            defaultValue = "TestOptions2 default",
            effectTags = [OptionEffectTag.NO_OP],
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED
        )
        abstract var value: String?
    }

    companion object {
        private fun assertGetOptionsReturnsDefaults(optionsProvider: OptionsProvider) {
            Truth.assertThat(optionsProvider.getOptions<TestOptions?>(com.google.devtools.build.lib.testing.common.FakeOptionsTest.TestOptions::class.java))
                .isEqualTo(com.google.devtools.common.options.Options.getDefaults<TestOptions?>(com.google.devtools.build.lib.testing.common.FakeOptionsTest.TestOptions::class.java))
            Truth.assertThat(optionsProvider.getOptions<TestOptions2?>(TestOptions2::class.java))
                .isEqualTo(com.google.devtools.common.options.Options.getDefaults<TestOptions2?>(TestOptions2::class.java))
        }
    }
}
