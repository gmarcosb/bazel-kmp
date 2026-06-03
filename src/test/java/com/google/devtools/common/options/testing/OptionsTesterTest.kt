// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options.testing

import org.junit.Assert
import org.junit.Test

/** Tests for the OptionsTester.  */
@RunWith(JUnit4::class)
class OptionsTesterTest {
    @Test
    @Throws(Exception::class)
    fun optionAnnotationCheck_PassesWhenAllOptionsAnnotated() {
        OptionsTester(OptionAnnotationCheckAllOptionsAnnotated::class.java).testAllOptions()
    }

    /** Test options class for optionAnnotationCheck_PassesWhenAllOptionsAnnotated.  */
    @OptionsClass
    abstract class BaseAllOptionsAnnotated : OptionsBase() {
        @get:Option(
            name = "public_inherited_option_with_annotation",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defaultFoo"
        )
        abstract val inheritedOptione: String?
    }

    /** Test options class for optionAnnotationCheck_PassesWhenAllOptionsAnnotated.  */
    @OptionsClass
    abstract class OptionAnnotationCheckAllOptionsAnnotated

        : BaseAllOptionsAnnotated() {
        @get:Option(
            name = "public_declared_option_with_annotation",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defaultFoo"
        )
        abstract val publicOption: String?

        @get:Option(
            name = "other_public_declared_option_with_annotation",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defaultFoo"
        )
        abstract val publicOption2: String?
    }

    class TestConverter : Contextless<String?>() {
        public override fun convert(input: String?): String? {
            return input
        }

        val typeDescription: String
            get() = "a string"
    }

    @Test
    fun defaultTestCheck_PassesIfAllDefaultsTestedIgnoringNullAndAllowMultiple() {
        OptionsTester(DefaultTestCheck::class.java)
            .testAllDefaultValuesTestedBy(
                ConverterTesterMap.Builder()
                    .add(
                        ConverterTester(TestConverter::class.java,  /*conversionContext=*/null)
                            .addEqualityGroup("testedDefault", "otherTestedDefault")
                    )
                    .build()
            )
    }

    /** Test options class for defaultTestCheck_PassesIfAllDefaultsTested.  */
    @OptionsClass
    abstract class DefaultTestCheck : OptionsBase() {
        @get:Option(
            name = "tested_option",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            converter = TestConverter::class,
            defaultValue = "testedDefault"
        )
        abstract val testedOption: String?

        @get:Option(
            name = "option_implicitly_using_default_converter",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "implicitConverterDefault"
        )
        abstract val implicitConverterOption: String?

        @get:Option(
            name = "other_tested_option",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            converter = TestConverter::class,
            defaultValue = "otherTestedDefault"
        )
        abstract val otherTestedOption: String?

        @get:Option(
            name = "option_with_null_default",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            converter = TestConverter::class,
            defaultValue = "null"
        )
        abstract val nullDefaultOption: String?

        @get:Option(
            name = "allowMultiple_option",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            converter = TestConverter::class,
            defaultValue = "null",
            allowMultiple = true
        )
        abstract val allowMultipleOption: MutableList<String?>?
    }

    @Test
    fun defaultTestCheck_FailsIfTesterIsPresentButValueIsNotTested() {
        try {
            OptionsTester(DefaultTestCheckUntestedOption::class.java)
                .testAllDefaultValuesTestedBy(
                    ConverterTesterMap.Builder()
                        .add(
                            ConverterTester(TestConverter::class.java,  /* conversionContext= */null)
                                .addEqualityGroup("testedDefault")
                        )
                        .build()
                )
        } catch (expected: AssertionError) {
            Truth.assertThat(expected).hasMessageThat().contains("getUntestedOption")
            Truth.assertThat(expected).hasMessageThat().contains("untestedDefault")
            return
        }
        Assert.fail("test is expected to have failed")
    }

    @Test
    fun defaultTestCheck_FailsIfTesterIsAbsent() {
        try {
            OptionsTester(DefaultTestCheckUntestedOption::class.java)
                .testAllDefaultValuesTestedBy(ConverterTesterMap.Builder().build())
        } catch (expected: AssertionError) {
            Truth.assertThat(expected).hasMessageThat().contains("TestConverter")
            return
        }
        Assert.fail("test is expected to have failed")
    }

    /**
     * Test options class for defaultTestCheck_FailsIfTesterIsPresentButValueIsNotTested and
     * defaultTestCheck_FailsIfTesterIsAbsent.
     */
    @OptionsClass
    abstract class DefaultTestCheckUntestedOption : OptionsBase() {
        @get:Option(
            name = "untested_option",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            converter = TestConverter::class,
            defaultValue = "untestedDefault"
        )
        abstract val untestedOption: String?
    }

    @Test
    fun defaultTestCheck_FailsIfTesterIsAbsentEvenForNullDefault() {
        try {
            OptionsTester(DefaultTestCheckUntestedNullOption::class.java)
                .testAllDefaultValuesTestedBy(ConverterTesterMap.Builder().build())
        } catch (expected: AssertionError) {
            Truth.assertThat(expected).hasMessageThat().contains("TestConverter")
            return
        }
        Assert.fail("test is expected to have failed")
    }

    /** Test options class for defaultTestCheck_FailsIfTesterIsAbsentEvenForNullDefault.  */
    @OptionsClass
    abstract class DefaultTestCheckUntestedNullOption : OptionsBase() {
        @get:Option(
            name = "untested_option",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            converter = TestConverter::class,
            defaultValue = "null"
        )
        abstract val untestedNullOption: String?
    }

    @Test
    fun defaultTestCheck_FailsIfTesterIsAbsentEvenForAllowMultiple() {
        try {
            OptionsTester(DefaultTestCheckUntestedMultipleOption::class.java)
                .testAllDefaultValuesTestedBy(ConverterTesterMap.Builder().build())
        } catch (expected: AssertionError) {
            Truth.assertThat(expected).hasMessageThat().contains("TestConverter")
            return
        }
        Assert.fail("test is expected to have failed")
    }

    /** Test options class for defaultTestCheck_FailsIfTesterIsAbsentEvenForAllowMultiple.  */
    @OptionsClass
    abstract class DefaultTestCheckUntestedMultipleOption : OptionsBase() {
        @get:Option(
            name = "untested_option",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            converter = TestConverter::class,
            defaultValue = "null",
            allowMultiple = true
        )
        abstract val untestedMultipleOption: MutableList<String?>?
    }
}
