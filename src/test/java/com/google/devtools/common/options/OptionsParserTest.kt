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
import OptionFilters.OptionEffectTag
import OptionFilters.OptionMetadataTag
import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache.put
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.Converter.Contextless
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter
import com.google.devtools.common.options.DuplicateOptionDeclarationException
import com.google.devtools.common.options.HelpVerbosity
import com.google.devtools.common.options.MethodOptionDefinition
import com.google.devtools.common.options.OpaqueOptionsData
import com.google.devtools.common.options.OptionDefinition
import com.google.devtools.common.options.OptionDocumentationCategory
import com.google.devtools.common.options.OptionEffectTag
import com.google.devtools.common.options.OptionInstanceOrigin
import com.google.devtools.common.options.OptionMetadataTag
import com.google.devtools.common.options.OptionPriority
import com.google.devtools.common.options.OptionPriority.PriorityCategory
import com.google.devtools.common.options.OptionValueDescription
import com.google.devtools.common.options.OptionsBase
import com.google.devtools.common.options.OptionsClass
import com.google.devtools.common.options.OptionsParser
import com.google.devtools.common.options.OptionsParser.ArgAndFallbackData
import com.google.devtools.common.options.OptionsParserTest
import com.google.devtools.common.options.OptionsParsingException
import com.google.devtools.common.options.ParsedOptionDescription
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.HashMap

/** Tests [OptionsParser].  */
@RunWith(JUnit4::class)
class OptionsParserTest {
    /** Dummy comment (linter suppression)  */
    @OptionsClass
    abstract class BadOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val foo1: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "foo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val foo2: Boolean
    }

    @org.junit.Test
    fun errorsDuringConstructionAreWrapped() {
        val e: com.google.devtools.common.options.ConstructionException? =
            org.junit.Assert.assertThrows<com.google.devtools.common.options.ConstructionException?>(
                com.google.devtools.common.options.ConstructionException::class.java,
                org.junit.function.ThrowingRunnable {
                    OptionsParser.builder().optionsClasses(BadOptions::class.java).build()
                })
        Truth.assertThat(e).hasCauseThat().isInstanceOf(DuplicateOptionDeclarationException::class.java)
    }

    enum class TestEnum {
        DEFAULT,
        EXPLICIT
    }

    class TestEnumConverter :
        com.google.devtools.common.options.EnumConverter<TestEnum?>(TestEnum::class.java, "test enum")

    class ChoosyConverter : com.google.devtools.common.options.Converter<String?> {
        @Throws(OptionsParsingException::class)
        override fun convert(input: String, conversionContext: Any?): String {
            when (input) {
                "default" -> return "default"
                " explicit" -> return "explicit"
                else -> throw OptionsParsingException("illegal")
            }
        }

        val typeDescription: String
            get() = "choosy"
    }

    @OptionsClass
    abstract class ChoosyOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "choosy",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "default",
            converter = ChoosyConverter::class
        )
        abstract val choosy: String?
    }

    @OptionsClass
    abstract class ExampleFoo : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            category = "one",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defaultFoo"
        )
        abstract val foo: String?

        @get:com.google.devtools.common.options.Option(
            name = "bar",
            category = "two",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "42"
        )
        abstract val bar: Int

        @get:com.google.devtools.common.options.Option(
            name = "bing",
            category = "one",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            allowMultiple = true
        )
        abstract val bing: MutableList<String>?

        @get:com.google.devtools.common.options.Option(
            name = "bang",
            category = "one",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            converter = com.google.devtools.common.options.OptionsParserTest.StringConverter::class,
            allowMultiple = true
        )
        abstract val bang: MutableList<String>?

        @get:com.google.devtools.common.options.Option(
            name = "nodoc",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = ""
        )
        abstract val nodoc: String?
    }

    @OptionsClass
    abstract class ExampleBaz : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "baz",
            category = "one",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defaultBaz"
        )
        abstract val baz: String?
    }

    /** Subclass of an options class.  */
    @OptionsClass
    abstract class ExampleBazSubclass : ExampleBaz() {
        @get:com.google.devtools.common.options.Option(
            name = "baz_subclass",
            category = "one",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defaultBazSubclass"
        )
        abstract val bazSubclass: String?
    }

    /** Example with empty to null string converter  */
    @OptionsClass
    abstract class ExampleBoom : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "boom",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defaultBoom",
            converter = com.google.devtools.common.options.OptionsParserTest.EmptyToNullStringConverter::class
        )
        abstract val boom: String?
    }

    /** Example with internal options  */
    @OptionsClass
    abstract class ExampleInternalOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "internal_boolean",
            metadataTags = [OptionMetadataTag.INTERNAL],
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "true"
        )
        abstract val privateBoolean: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "internal_string",
            metadataTags = [OptionMetadataTag.INTERNAL],
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "super secret"
        )
        abstract val privateString: String?
    }

    @OptionsClass
    abstract class ExampleEquivalentWithFoo : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            category = "one",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "differentDefault"
        )
        abstract val foo: String?

        @get:com.google.devtools.common.options.Option(
            name = "bar",
            category = "one",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "differentDefault"
        )
        abstract val bar: String?

        @get:com.google.devtools.common.options.Option(
            name = "ignored_with_value",
            category = "one",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "differentDefault"
        )
        abstract val ignoredWithValue: String?

        @get:com.google.devtools.common.options.Option(
            name = "ignored_without_value",
            category = "one",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val ignoredWithoutValue: Boolean
    }

    @OptionsClass
    abstract class BooleanAliasOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "true"
        )
        abstract val foo: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "bar",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "true"
        )
        abstract val bar: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "flag_alias",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            allowMultiple = true
        )
        abstract val flagAlias: MutableList<String?>?
    }

    @OptionsClass
    abstract class DeprecatedAliasOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "true",
            deprecationWarning = "Don't use foo."
        )
        abstract val foo: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "bar",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "true"
        )
        abstract val bar: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "flag_alias",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            allowMultiple = true
        )
        abstract val flagAlias: MutableList<String?>?
    }

    @OptionsClass
    abstract class ExampleIncompatibleWithFoo : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            category = "one",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "true"
        )
        abstract val foo: Boolean
    }

    open class StringConverter : Contextless<String?>() {
        override fun convert(input: String?): String? {
            return input
        }

        val typeDescription: String
            get() = "a string"
    }

    /**
     * A converter that defaults to null if the input is the empty string
     */
    class EmptyToNullStringConverter : StringConverter() {
        override fun convert(input: String): String? {
            return if (input.isEmpty()) null else input
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultValueOfBadOptionRemains() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ChoosyOptions::class.java).build()

        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { parser.parse("--choosy=wat") })
        Truth.assertThat(parser.getOptions<ChoosyOptions?>(ChoosyOptions::class.java).getChoosy()).isEqualTo("default")
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun parseWithMultipleOptionsInterfaces() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExampleFoo::class.java, ExampleBaz::class.java).build()
        parser.parse("--baz=oops", "--bar", "17")
        val foo: ExampleFoo? = parser.getOptions<ExampleFoo?>(ExampleFoo::class.java)
        Truth.assertThat(foo!!.foo).isEqualTo("defaultFoo")
        Truth.assertThat(foo.bar).isEqualTo(17)
        val baz: ExampleBaz? = parser.getOptions<ExampleBaz?>(ExampleBaz::class.java)
        Truth.assertThat(baz!!.baz).isEqualTo("oops")
    }

    @org.junit.Test
    fun parseWithSourceFunctionThrowsExceptionIfResidueIsNotAllowed() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(ExampleFoo::class.java, ExampleBaz::class.java)
                .allowResidue(false)
                .build()
        val sourceFunction: java.util.function.Function<OptionDefinition?, String?> =
            java.util.function.Function { option: OptionDefinition? -> "command line" }

        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parser.parseWithSourceFunction(
                        PriorityCategory.COMMAND_LINE,
                        sourceFunction,
                        com.google.common.collect.ImmutableList.of<String?>(
                            "residue",
                            "not",
                            "allowed",
                            "in",
                            "parseWithSource"
                        ),  /* fallbackData= */
                        null
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo("Unrecognized arguments: residue not allowed in parseWithSource")
        Truth.assertThat(parser.getResidue())
            .containsExactly("residue", "not", "allowed", "in", "parseWithSource")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseWithSourceFunctionDoesntThrowExceptionIfResidueIsAllowed() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(ExampleFoo::class.java, ExampleBaz::class.java)
                .allowResidue(true)
                .build()
        val sourceFunction: java.util.function.Function<OptionDefinition?, String?> =
            java.util.function.Function { option: OptionDefinition? -> "command line" }

        parser.parseWithSourceFunction(
            PriorityCategory.COMMAND_LINE,
            sourceFunction,
            com.google.common.collect.ImmutableList.of<String?>(
                "residue",
                "is",
                "allowed",
                "in",
                "parseWithSource"
            ),  /* fallbackData= */
            null
        )
        Truth.assertThat(parser.getResidue())
            .containsExactly("residue", "is", "allowed", "in", "parseWithSource")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseArgsAsExpansionOfOptionThrowsExceptionIfResidueIsNotAllowed() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExpansionOptions::class.java).allowResidue(false).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--expands")
        )
        val expansionDescription: OptionValueDescription = parser.getOptionValueDescription("expands")
        Truth.assertThat(expansionDescription).isNotNull()

        val optionValue: OptionValueDescription = parser.getOptionValueDescription("underlying")
        Truth.assertThat(optionValue).isNotNull()

        val optionToExpand: ParsedOptionDescription? = optionValue.getCanonicalInstances().get(0)

        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parser.parseArgsAsExpansionOfOption(
                        optionToExpand,
                        "source",
                        ArgAndFallbackData.wrapWithFallbackData(
                            com.google.common.collect.ImmutableList.of<String?>(
                                "--underlying=direct_value",
                                "residue",
                                "in",
                                "expansion"
                            ),  /* fallbackData= */
                            null
                        )
                    )
                })
        Truth.assertThat(parser.getResidue()).isNotEmpty()
        Truth.assertThat(e).hasMessageThat().isEqualTo("Unrecognized arguments: residue in expansion")
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun parseWithOptionsInheritance() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExampleBazSubclass::class.java).build()
        parser.parse("--baz_subclass=cat", "--baz=dog")
        val subclassOptions: ExampleBazSubclass? =
            parser.getOptions<ExampleBazSubclass?>(ExampleBazSubclass::class.java)
        Truth.assertThat(subclassOptions!!.bazSubclass).isEqualTo("cat")
        Truth.assertThat(subclassOptions.baz).isEqualTo("dog")
        val options: ExampleBaz? = parser.getOptions<ExampleBaz?>(ExampleBaz::class.java)
        Truth.assertThat(options).isNotNull()
        Truth.assertThat(options!!.baz).isEqualTo("dog")
    }

    @org.junit.Test
    fun parserWithUnknownOption() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExampleFoo::class.java, ExampleBaz::class.java).build()
        val e: OptionsParsingException =
            org.junit.Assert.assertThrows<OptionsParsingException>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parser.parse("--unknown", "option") })
        assertThat(e.invalidArgument).isEqualTo("--unknown")
        Truth.assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: --unknown")
        Truth.assertThat(parser.getResidue()).isEmpty()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun parserWithSingleDashOption_notAllowed() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExampleFoo::class.java, ExampleBaz::class.java).build()
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { parser.parse("-baz=oops", "-bar", "17") })
    }

    @org.junit.Test
    fun parsingFailsWithUnknownOptions() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExampleFoo::class.java, ExampleBaz::class.java).build()
        val unknownOpts: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("--unknown", "option", "--more_unknowns")
        val e: OptionsParsingException =
            org.junit.Assert.assertThrows<OptionsParsingException>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parser.parse(unknownOpts) })
        assertThat(e.invalidArgument).isEqualTo("--unknown")
        Truth.assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: --unknown")
        Truth.assertThat(parser.getOptions<ExampleFoo?>(ExampleFoo::class.java)).isNotNull()
        Truth.assertThat(parser.getOptions<ExampleBaz?>(ExampleBaz::class.java)).isNotNull()
    }

    @org.junit.Test
    fun parsingFailsWithInternalBooleanOptionAsIfUnknown() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExampleInternalOptions::class.java).build()
        val internalOpts: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("--internal_boolean")
        val e: OptionsParsingException =
            org.junit.Assert.assertThrows<OptionsParsingException>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parser.parse(internalOpts) })
        assertThat(e.invalidArgument).isEqualTo("--internal_boolean")
        Truth.assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: --internal_boolean")
        Truth.assertThat(parser.getOptions<ExampleInternalOptions?>(ExampleInternalOptions::class.java)).isNotNull()
    }

    @org.junit.Test
    fun parsingFailsWithNegatedInternalBooleanOptionAsIfUnknown() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExampleInternalOptions::class.java).build()
        val internalOpts: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("--nointernal_boolean")
        val e: OptionsParsingException =
            org.junit.Assert.assertThrows<OptionsParsingException>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parser.parse(internalOpts) })
        assertThat(e.invalidArgument).isEqualTo("--nointernal_boolean")
        Truth.assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: --nointernal_boolean")
        Truth.assertThat(parser.getOptions<ExampleInternalOptions?>(ExampleInternalOptions::class.java)).isNotNull()
    }

    @org.junit.Test
    fun parsingFailsForInternalOptionWithValueInSameArgAsIfUnknown() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExampleInternalOptions::class.java).build()
        val internalOpts: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("--internal_string=any_value")
        val e: OptionsParsingException =
            org.junit.Assert.assertThrows<OptionsParsingException>(
                "parsing should have failed for including a private option",
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parser.parse(internalOpts) })
        assertThat(e.invalidArgument).isEqualTo("--internal_string=any_value")
        Truth.assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: --internal_string=any_value")
        Truth.assertThat(parser.getOptions<ExampleInternalOptions?>(ExampleInternalOptions::class.java)).isNotNull()
    }

    @org.junit.Test
    fun parsingFailsForInternalOptionWithValueInSeparateArgAsIfUnknown() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExampleInternalOptions::class.java).build()
        val internalOpts: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("--internal_string", "any_value")
        val e: OptionsParsingException =
            org.junit.Assert.assertThrows<OptionsParsingException>(
                "parsing should have failed for including a private option",
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parser.parse(internalOpts) })
        assertThat(e.invalidArgument).isEqualTo("--internal_string")
        Truth.assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: --internal_string")
        Truth.assertThat(parser.getOptions<ExampleInternalOptions?>(ExampleInternalOptions::class.java)).isNotNull()
    }

    @org.junit.Test
    fun parseKnownAndUnknownOptions() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExampleFoo::class.java, ExampleBaz::class.java).build()
        val opts: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("--bar", "17", "--unknown", "option")
        val e: OptionsParsingException =
            org.junit.Assert.assertThrows<OptionsParsingException>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parser.parse(opts) })
        assertThat(e.invalidArgument).isEqualTo("--unknown")
        Truth.assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: --unknown")
        Truth.assertThat(parser.getOptions<ExampleFoo?>(ExampleFoo::class.java)).isNotNull()
        Truth.assertThat(parser.getOptions<ExampleBaz?>(ExampleBaz::class.java)).isNotNull()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun parseAndOverrideWithEmptyStringToObtainNullValueInOption() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExampleBoom::class.java).build()
        // Override --boom value to the empty string
        parser.parse("--boom=")
        val boom: ExampleBoom? = parser.getOptions<ExampleBoom?>(ExampleBoom::class.java)
        // The converted value is intentionally null since boom uses the EmptyToNullStringConverter
        Truth.assertThat(boom!!.boom).isNull()
    }

    @OptionsClass
    abstract class CategoryTest : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "swiss_bank_account_number",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "123456789"
        )
        abstract val swissBankAccountNumber: Int

        @get:com.google.devtools.common.options.Option(
            name = "student_bank_account_number",
            category = "one",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "987654321"
        )
        abstract val studentBankAccountNumber: Int
    }

    @get:org.junit.Test
    val optionsAndGetResidueWithNoCallToParse: Unit
        get() {
            // With no call to parse(), all options are at default values, and there's
            // no reside.
            Truth.assertThat(
                OptionsParser.builder()
                    .optionsClasses(ExampleFoo::class.java)
                    .build()
                    .getOptions<ExampleFoo?>(ExampleFoo::class.java)
                    .getFoo()
            )
                .isEqualTo("defaultFoo")
            Truth.assertThat(OptionsParser.builder().optionsClasses(ExampleFoo::class.java).build().getResidue())
                .isEmpty()
        }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun parserCanBeCalledRepeatedly() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExampleFoo::class.java).build()
        parser.parse("--foo", "foo1")
        Truth.assertThat(parser.getOptions<ExampleFoo?>(ExampleFoo::class.java).getFoo()).isEqualTo("foo1")
        parser.parse()
        Truth.assertThat(parser.getOptions<ExampleFoo?>(ExampleFoo::class.java).getFoo()).isEqualTo("foo1") // no change
        parser.parse("--foo", "foo2")
        Truth.assertThat(parser.getOptions<ExampleFoo?>(ExampleFoo::class.java).getFoo()).isEqualTo("foo2") // updated
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun multipleOccurringOption() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExampleFoo::class.java).build()
        parser.parse("--bing", "abcdef", "--foo", "foo1", "--bing", "123456")
        Truth.assertThat(parser.getOptions<ExampleFoo?>(ExampleFoo::class.java).getBing())
            .containsExactly("abcdef", "123456")
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun multipleOccurringOptionWithConverter() {
        // --bang is the same as --bing except that it has a "converter" specified.
        // This test also tests option values with embedded commas and spaces.
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExampleFoo::class.java).build()
        parser.parse("--bang", "abc,def ghi", "--foo", "foo1", "--bang", "123456")
        Truth.assertThat(parser.getOptions<ExampleFoo?>(ExampleFoo::class.java).getBang())
            .containsExactly("abc,def ghi", "123456")
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun parserIgnoresOptionsAfterMinusMinus() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExampleFoo::class.java, ExampleBaz::class.java).build()
        parser.parse("--foo", "well", "--baz", "here", "--", "--bar", "ignore")
        val foo: ExampleFoo? = parser.getOptions<ExampleFoo?>(ExampleFoo::class.java)
        val baz: ExampleBaz? = parser.getOptions<ExampleBaz?>(ExampleBaz::class.java)
        Truth.assertThat(foo!!.foo).isEqualTo("well")
        Truth.assertThat(baz!!.baz).isEqualTo("here")
        Truth.assertThat(foo.bar).isEqualTo(42) // the default!
        Truth.assertThat(parser.getResidue()).containsExactly("--bar", "ignore").inOrder()
    }

    @org.junit.Test
    fun parserThrowsExceptionIfResidueIsNotAllowed() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExampleFoo::class.java).allowResidue(false).build()
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parser.parse("residue", "is", "not", "OK") })
        Truth.assertThat(e).hasMessageThat().isEqualTo("Unrecognized arguments: residue is not OK")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleCallsToParse() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExampleFoo::class.java).allowResidue(true).build()
        parser.parse("--foo", "one", "--bar", "43", "unknown1")
        parser.parse("--foo", "two", "unknown2")
        val foo: ExampleFoo? = parser.getOptions<ExampleFoo?>(ExampleFoo::class.java)
        Truth.assertThat(foo!!.foo).isEqualTo("two") // second call takes precedence
        Truth.assertThat(foo.bar).isEqualTo(43)
        Truth.assertThat(parser.getResidue()).containsExactly("unknown1", "unknown2").inOrder()
    }

    // Regression test for a subtle bug!  The toString of each options interface
    // instance was printing out key=value pairs for all flags in the
    // OptionsParser, not just those belonging to the specific interface type.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toStringDoesntIncludeFlagsForOtherOptionsInParserInstance() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExampleFoo::class.java, ExampleBaz::class.java).build()
        parser.parse("--foo", "foo", "--bar", "43", "--baz", "baz")

        val fooString = parser.getOptions<ExampleFoo?>(ExampleFoo::class.java).toString()
        if (!fooString.contains("foo=foo") || !fooString.contains("bar=43") || !fooString.contains("ExampleFoo") ||
            fooString.contains("baz=baz")
        ) {
            org.junit.Assert.fail("ExampleFoo.toString() is incorrect: " + fooString)
        }

        val bazString = parser.getOptions<ExampleBaz?>(ExampleBaz::class.java).toString()
        if (!bazString.contains("baz=baz") || !bazString.contains("ExampleBaz") ||
            bazString.contains("foo=foo") ||
            bazString.contains("bar=43")
        ) {
            org.junit.Assert.fail("ExampleBaz.toString() is incorrect: " + bazString)
        }
    }

    // Regression test for another subtle bug!  The toString was printing all the
    // explicitly-specified options, even if they were at their default values,
    // causing toString equivalence to diverge from equals().
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toStringIsIndependentOfExplicitCommandLineOptions() {
        val foo1: ExampleFoo =
            com.google.devtools.common.options.Options.parse<ExampleFoo?>(ExampleFoo::class.java).options
        val foo2: ExampleFoo =
            com.google.devtools.common.options.Options.parse<ExampleFoo?>(ExampleFoo::class.java, "--bar", "42").options
        Truth.assertThat(foo2).isEqualTo(foo1)
        Truth.assertThat(foo2.toString()).isEqualTo(foo1.toString())

        val expectedMap: MutableMap<String?, Any?> =
            com.google.common.collect.ImmutableMap.Builder<String?, Any?>()
                .put("bing", mutableListOf<Any?>())
                .put("bar", 42)
                .put("nodoc", "")
                .put("bang", mutableListOf<Any?>())
                .put("foo", "defaultFoo")
                .buildOrThrow()

        Truth.assertThat(com.google.devtools.common.options.Options.toMap<ExampleFoo?>(foo1)).isEqualTo(expectedMap)
        Truth.assertThat(com.google.devtools.common.options.Options.toMap<ExampleFoo?>(foo2)).isEqualTo(expectedMap)
    }

    @OptionsClass
    abstract class BaseClass : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "base",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "baseDefault"
        )
        abstract var base: String?
    }

    @OptionsClass
    abstract class DerivedClass : BaseClass() {
        @get:com.google.devtools.common.options.Option(
            name = "derived",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "derivedDefault"
        )
        abstract var derived: String?
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val optionsWithInheritance: Unit
        get() {
            val parser: OptionsParser = OptionsParser.builder().optionsClasses(DerivedClass::class.java).build()
            parser.parse("--base=b", "--derived=d")
            val base: BaseClass? =
                parser.getOptions<BaseClass?>(com.google.devtools.common.options.OptionsParserTest.BaseClass::class.java)
            Truth.assertThat(base!!.base).isEqualTo("b")

            val derived: DerivedClass? = parser.getOptions<DerivedClass?>(DerivedClass::class.java)
            Truth.assertThat(derived!!.base).isEqualTo("b")
            Truth.assertThat(derived.derived).isEqualTo("d")
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun setOptionsWithInheritance() {
        val derived: DerivedClass =
            com.google.devtools.common.options.Options.getDefaults<DerivedClass>(DerivedClass::class.java)
        derived.base = "b"
        derived.derived = "d"
        Truth.assertThat(derived.base).isEqualTo("b")
        Truth.assertThat(derived.derived).isEqualTo("d")
    }

    // Checks that fallback data can contain options classes where one is the ancestor of another
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseOptionsWithInheritance() {
        val fallbackData: OpaqueOptionsData? =
            OptionsParser.getFallbackOptionsData(
                com.google.common.collect.ImmutableList.of<java.lang.Class<out OptionsBase?>?>(
                    com.google.devtools.common.options.OptionsParserTest.BaseClass::class.java,
                    DerivedClass::class.java
                )
            )

        val parser: OptionsParser = OptionsParser.builder().optionsClasses().build()
        parser.parseWithSourceFunction(
            PriorityCategory.RC_FILE,
            java.util.function.Function { o: OptionDefinition? -> ".bazelrc" },
            com.google.common.collect.ImmutableList.of<String?>("--base", "b", "--derived", "d"),
            fallbackData
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun describeOptionsWithInheritance() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(DerivedClass::class.java).build()
        val usage: String? =
            parser.describeOptionsWithDeprecatedCategories(
                com.google.common.collect.ImmutableMap.of<String?, String?>(),
                HelpVerbosity.LONG
            )
        Truth.assertThat(usage).contains("--base")
        Truth.assertThat(usage).contains("--derived")

        // Check that --base is not duplicated.
        val firstBase: Int = usage.indexOf("--base")
        val secondBase: Int = usage.indexOf("--base", firstBase + 1)
        Truth.assertThat(secondBase).isEqualTo(-1)
    }

    // Regression test for yet another subtle bug!  The inherited options weren't
    // being printed by toString.  One day, a real rain will come and wash all
    // this scummy code off the streets.
    @OptionsClass
    abstract class DerivedBaz : ExampleBaz() {
        @get:com.google.devtools.common.options.Option(
            name = "derived",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defaultDerived"
        )
        abstract val derived: String?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toStringPrintsInheritedOptionsToo_Duh() {
        val derivedBaz: DerivedBaz =
            com.google.devtools.common.options.Options.parse<DerivedBaz?>(DerivedBaz::class.java).options
        val derivedBazString = derivedBaz.toString()
        if (!derivedBazString.contains("derived=defaultDerived") ||
            !derivedBazString.contains("baz=defaultBaz")
        ) {
            org.junit.Assert.fail("DerivedBaz.toString() is incorrect: " + derivedBazString)
        }
    }

    @OptionsClass
    abstract class CustomOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "simple",
            category = "custom",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "simple default"
        )
        abstract val simple: String?

        @get:com.google.devtools.common.options.Option(
            name = "multipart_name",
            category = "custom",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "multipart default"
        )
        abstract val multipartName: String?
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun assertDefaultStringsForCustomOptions() {
        val options: CustomOptions =
            com.google.devtools.common.options.Options.parse<CustomOptions?>(CustomOptions::class.java).options
        Truth.assertThat(options.simple).isEqualTo("simple default")
        Truth.assertThat(options.multipartName).isEqualTo("multipart default")
    }

    @OptionsClass
    abstract class NullTestOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "simple",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val simple: String?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultNullStringGivesNull() {
        val options: NullTestOptions =
            com.google.devtools.common.options.Options.parse<NullTestOptions?>(com.google.devtools.common.options.OptionsParserTest.NullTestOptions::class.java).options
        Truth.assertThat(options.simple).isNull()
    }

    @OptionsClass
    abstract class ConverterWithContextTestOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            converter = ConverterWithContext::class,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "bar"
        )
        abstract val foo: String?

        class ConverterWithContext : com.google.devtools.common.options.Converter<String?> {
            @Throws(OptionsParsingException::class)
            override fun convert(input: String?, conversionContext: Any?): String? {
                if (conversionContext != null) {
                    return conversionContext.toString() + input
                }
                return input
            }

            val typeDescription: String
                get() = "a funky string"
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun convertWithContext() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(ConverterWithContextTestOptions::class.java)
                .withConversionContext("bleh ")
                .build()
        parser.parse("--foo", "quux")
        val options: ConverterWithContextTestOptions? =
            parser.getOptions<ConverterWithContextTestOptions?>(ConverterWithContextTestOptions::class.java)
        Truth.assertThat(options!!.foo).isEqualTo("bleh quux")
    }

    @OptionsClass
    abstract class ImplicitDependencyOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "first",
            implicitRequirements = ["--second=second"],
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val first: String?

        @get:com.google.devtools.common.options.Option(
            name = "second",
            implicitRequirements = ["--third=third"],
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val second: String?

        @get:com.google.devtools.common.options.Option(
            name = "third",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val third: String?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun implicitDependencyHasImplicitDependency() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ImplicitDependencyOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--first=first")
        )
        Truth.assertThat(
            parser.getOptions<ImplicitDependencyOptions?>(ImplicitDependencyOptions::class.java).getFirst()
        ).isEqualTo("first")
        Truth.assertThat(
            parser.getOptions<ImplicitDependencyOptions?>(ImplicitDependencyOptions::class.java).getSecond()
        ).isEqualTo("second")
        Truth.assertThat(
            parser.getOptions<ImplicitDependencyOptions?>(ImplicitDependencyOptions::class.java).getThird()
        ).isEqualTo("third")
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @OptionsClass
    abstract class BadImplicitDependencyOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "first",
            implicitRequirements = ["xxx"],
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val first: String?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun badImplicitDependency() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(BadImplicitDependencyOptions::class.java).build()
        try {
            parser.parse(
                OptionPriority.PriorityCategory.COMMAND_LINE,
                null,
                com.google.common.collect.ImmutableList.of<String?>("--first=first")
            )
        } catch (e: java.lang.AssertionError) {
            /* Expected error. */
            return
        }
        org.junit.Assert.fail()
    }

    @OptionsClass
    abstract class BadExpansionOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "first",
            expansion = ["xxx"],
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val first: java.lang.Void?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun badExpansionOptions() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(BadExpansionOptions::class.java).build()
        try {
            parser.parse(
                OptionPriority.PriorityCategory.COMMAND_LINE,
                null,
                com.google.common.collect.ImmutableList.of<String?>("--first")
            )
        } catch (e: java.lang.AssertionError) {
            /* Expected error. */
            return
        }
        org.junit.Assert.fail()
    }

    /** ExpansionOptions  */
    @OptionsClass
    abstract class ExpansionOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "underlying",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val underlying: String?

        @get:com.google.devtools.common.options.Option(
            name = "expands",
            expansion = ["--underlying=from_expansion"],
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val expands: java.lang.Void?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun describeOptionsWithExpansion() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExpansionOptions::class.java).build()
        val usage: String? =
            parser.describeOptionsWithDeprecatedCategories(
                com.google.common.collect.ImmutableMap.of<String?, String?>(),
                HelpVerbosity.LONG
            )
        Truth.assertThat(usage).contains("  --expands\n      Expands to: --underlying=from_expansion")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun overrideExpansionWithExplicit() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExpansionOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--expands", "--underlying=direct_value")
        )
        val options: ExpansionOptions? = parser.getOptions<ExpansionOptions?>(ExpansionOptions::class.java)
        Truth.assertThat(options!!.underlying).isEqualTo("direct_value")
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testExpansionOriginIsPropagatedToOption() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExpansionOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--expands")
        )
        val expansionDescription: OptionValueDescription = parser.getOptionValueDescription("expands")
        Truth.assertThat(expansionDescription).isNotNull()

        // In order to have access to the ParsedOptionDescription tracked by the value of 'underlying'
        // we have to know that this option is a "single valued" option.
        val optionValue: OptionValueDescription = parser.getOptionValueDescription("underlying")
        Truth.assertThat(optionValue).isNotNull()
        Truth.assertThat(optionValue.getSourceString()).matches("expanded from option '--expands'")
        Truth.assertThat(optionValue.getCanonicalInstances()).isNotNull()
        Truth.assertThat(optionValue.getCanonicalInstances()).hasSize(1)

        val effectiveInstance: ParsedOptionDescription = optionValue.getCanonicalInstances().get(0)
        Truth.assertThat<OptionDefinition?>(effectiveInstance.getExpandedFrom().getOptionDefinition())
            .isSameInstanceAs(expansionDescription.getOptionDefinition())
        Truth.assertThat(effectiveInstance.getImplicitDependent()).isNull()

        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun overrideExplicitWithExpansion() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExpansionOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--underlying=direct_value", "--expands")
        )
        val options: ExpansionOptions? = parser.getOptions<ExpansionOptions?>(ExpansionOptions::class.java)
        Truth.assertThat(options!!.underlying).isEqualTo("from_expansion")
        Truth.assertThat(parser.getWarnings())
            .containsExactly(
                "option '--expands' was expanded and now overrides the explicit option "
                        + "--underlying=direct_value with --underlying=from_expansion"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noWarningsWhenOverrideExplicitWithExpansion() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExpansionOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.RC_FILE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--underlying=direct_value", "--expands")
        )
        val options: ExpansionOptions? = parser.getOptions<ExpansionOptions?>(ExpansionOptions::class.java)
        Truth.assertThat(options!!.underlying).isEqualTo("from_expansion")
        Truth.assertThat(parser.getWarnings())
            .doesNotContain(
                "option '--expands' was expanded and now overrides the explicit option "
                        + "--underlying=direct_value with --underlying=from_expansion"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noWarningsWhenValueNotChanged() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExpansionOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--underlying=from_expansion", "--expands")
        )
        val options: ExpansionOptions? = parser.getOptions<ExpansionOptions?>(ExpansionOptions::class.java)
        Truth.assertThat(options!!.underlying).isEqualTo("from_expansion")
        // The expansion option overrides the explicit option, but it is the same value, so expect
        // no warning.
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    /** ExpansionOptions to allow-multiple values.  */
    @OptionsClass
    abstract class ExpansionOptionsToMultiple : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "underlying",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            allowMultiple = true
        )
        abstract val underlying: MutableList<String>?

        @get:com.google.devtools.common.options.Option(
            name = "expands",
            expansion = ["--underlying=from_expansion"],
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val expands: java.lang.Void?
    }

    /**
     * Makes sure the expansion options are expanded in the right order if they affect flags that
     * allow multiples.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleExpansionOptionsWithValue() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExpansionOptionsToMultiple::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--expands", "--underlying=direct_value", "--expands")
        )
        val options: ExpansionOptionsToMultiple? =
            parser.getOptions<ExpansionOptionsToMultiple?>(ExpansionOptionsToMultiple::class.java)
        Truth.assertThat(options!!.underlying)
            .containsExactly("from_expansion", "direct_value", "from_expansion")
            .inOrder()
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkExpansionValueWarning() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExpansionOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--expands=no")
        )
        val options: ExpansionOptions? = parser.getOptions<ExpansionOptions?>(ExpansionOptions::class.java)
        Truth.assertThat(options!!.underlying).isEqualTo("from_expansion")
        Truth.assertThat(parser.getWarnings())
            .containsExactly(
                ("option '--expands' is an expansion option. It does not accept values, "
                        + "and does not change its expansion based on the value provided. "
                        + "Value 'no' will be ignored.")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun overrideWithHigherPriority() {
        val parser: OptionsParser = OptionsParser.builder()
            .optionsClasses(com.google.devtools.common.options.OptionsParserTest.NullTestOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.RC_FILE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--simple=a")
        )
        Truth.assertThat(
            parser.getOptions<NullTestOptions?>(com.google.devtools.common.options.OptionsParserTest.NullTestOptions::class.java)
                .getSimple()
        ).isEqualTo("a")
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--simple=b")
        )
        Truth.assertThat(
            parser.getOptions<NullTestOptions?>(com.google.devtools.common.options.OptionsParserTest.NullTestOptions::class.java)
                .getSimple()
        ).isEqualTo("b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun overrideWithLowerPriority() {
        val parser: OptionsParser = OptionsParser.builder()
            .optionsClasses(com.google.devtools.common.options.OptionsParserTest.NullTestOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--simple=a")
        )
        Truth.assertThat(
            parser.getOptions<NullTestOptions?>(com.google.devtools.common.options.OptionsParserTest.NullTestOptions::class.java)
                .getSimple()
        ).isEqualTo("a")
        parser.parse(
            OptionPriority.PriorityCategory.RC_FILE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--simple=b")
        )
        Truth.assertThat(
            parser.getOptions<NullTestOptions?>(com.google.devtools.common.options.OptionsParserTest.NullTestOptions::class.java)
                .getSimple()
        ).isEqualTo("a")
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val optionValueDescriptionWithNonExistingOption: Unit
        get() {
            val parser: OptionsParser = OptionsParser.builder()
                .optionsClasses(com.google.devtools.common.options.OptionsParserTest.NullTestOptions::class.java)
                .build()
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { parser.getOptionValueDescription("notexisting") })
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val optionValueDescriptionWithoutValue: Unit
        get() {
            val parser: OptionsParser = OptionsParser.builder()
                .optionsClasses(com.google.devtools.common.options.OptionsParserTest.NullTestOptions::class.java)
                .build()
            Truth.assertThat(parser.getOptionValueDescription("simple")).isNull()
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val optionValueDescriptionWithValue: Unit
        get() {
            val parser: OptionsParser = OptionsParser.builder()
                .optionsClasses(com.google.devtools.common.options.OptionsParserTest.NullTestOptions::class.java)
                .build()
            parser.parse(
                OptionPriority.PriorityCategory.COMMAND_LINE,
                "my description",
                com.google.common.collect.ImmutableList.of<String?>("--simple=abc")
            )
            val result: OptionValueDescription = parser.getOptionValueDescription("simple")
            Truth.assertThat(result).isNotNull()
            Truth.assertThat(result.getOptionDefinition().getOptionName()).isEqualTo("simple")
            Truth.assertThat(result.getValue()).isEqualTo("abc")
            Truth.assertThat(result.getSourceString()).isEqualTo("my description")
            Truth.assertThat(result.getCanonicalInstances()).isNotNull()
            Truth.assertThat(result.getCanonicalInstances()).hasSize(1)

            val singleOptionInstance: ParsedOptionDescription = result.getCanonicalInstances().get(0)
            assertThat(singleOptionInstance.getPriority().priorityCategory)
                .isEqualTo(OptionPriority.PriorityCategory.COMMAND_LINE)
            Truth.assertThat(singleOptionInstance.getOptionDefinition().isExpansionOption()).isFalse()
            Truth.assertThat(singleOptionInstance.getImplicitDependent()).isNull()
            Truth.assertThat(singleOptionInstance.getExpandedFrom()).isNull()
        }

    @OptionsClass
    abstract class ImplicitDependencyWarningOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "first",
            implicitRequirements = ["--second=requiredByFirst"],
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val first: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "second",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val second: String?

        @get:com.google.devtools.common.options.Option(
            name = "third",
            implicitRequirements = ["--second=requiredByThird"],
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val third: String?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun warningForImplicitOverridingExplicitOption() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ImplicitDependencyWarningOptions::class.java).build()
        parser.parse("--second=second", "--first")
        Truth.assertThat(parser.getWarnings())
            .containsExactly(
                "option '--second' is implicitly defined by option '--first'; the implicitly set value "
                        + "overrides the previous one"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun warningForExplicitOverridingImplicitOption() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ImplicitDependencyWarningOptions::class.java).build()
        parser.parse("--first")
        Truth.assertThat(parser.getWarnings()).isEmpty()
        parser.parse("--second=second")
        Truth.assertThat(parser.getWarnings())
            .containsExactly(
                "A new value for option '--second' overrides a previous implicit setting of that "
                        + "option by option '--first'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun warningForExplicitOverridingImplicitOptionInSameCall() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ImplicitDependencyWarningOptions::class.java).build()
        parser.parse("--first", "--second=second")
        Truth.assertThat(parser.getWarnings())
            .containsExactly(
                "A new value for option '--second' overrides a previous implicit setting of that "
                        + "option by option '--first'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun warningForImplicitOverridingImplicitOption() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ImplicitDependencyWarningOptions::class.java).build()
        parser.parse("--first")
        Truth.assertThat(parser.getWarnings()).isEmpty()
        parser.parse("--third=third")
        Truth.assertThat(parser.getWarnings())
            .containsExactly(
                "option '--second' is implicitly defined by both option '--first' and "
                        + "option '--third=third'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noWarningsForNonConflictingOverrides() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ImplicitDependencyWarningOptions::class.java).build()
        parser.parse("--first", "--second=requiredByFirst")
        val options: ImplicitDependencyWarningOptions? =
            parser.getOptions<ImplicitDependencyWarningOptions?>(ImplicitDependencyWarningOptions::class.java)
        Truth.assertThat(options!!.first).isTrue()
        Truth.assertThat(options.second).isEqualTo("requiredByFirst")
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun warningForImplicitRequirementsExpandedForDefaultValue() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ImplicitDependencyWarningOptions::class.java).build()
        parser.parse("--nofirst")
        val options: ImplicitDependencyWarningOptions? =
            parser.getOptions<ImplicitDependencyWarningOptions?>(ImplicitDependencyWarningOptions::class.java)
        Truth.assertThat(options!!.first).isFalse()
        Truth.assertThat(options.second).isEqualTo("requiredByFirst")
        Truth.assertThat(parser.getWarnings())
            .containsExactly(
                ("--nofirst sets option '--first' to its default value. Since this option has implicit "
                        + "requirements that are set whenever the option is explicitly provided, "
                        + "regardless of the value, this will behave differently than letting a default "
                        + "be a default. Specifically, this options expands to "
                        + "{--second=requiredByFirst}.")
            )
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testDependentOriginIsPropagatedToOption() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ImplicitDependencyWarningOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--first")
        )
        val first: OptionValueDescription = parser.getOptionValueDescription("first")
        Truth.assertThat(first).isNotNull()
        Truth.assertThat(first.getCanonicalInstances()).hasSize(1)

        val second: OptionValueDescription = parser.getOptionValueDescription("second")
        Truth.assertThat(second).isNotNull()
        Truth.assertThat(second.getSourceString()).matches("implicit requirement of option '--first'")
        // Implicit requirements don't get listed as canonical. Check that this claims to be empty,
        // which tells us that the option instance is correctly tracking that is originated as an
        // implicit requirement.
        Truth.assertThat(second.getCanonicalInstances()).isNotNull()
        Truth.assertThat(second.getCanonicalInstances()).hasSize(0)
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    /**
     * Options for testing the behavior of canonicalization when an option implicitly requires a
     * repeatable option.
     */
    @OptionsClass
    abstract class ImplicitDependencyOnAllowMultipleOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "first",
            implicitRequirements = ["--second=requiredByFirst"],
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val first: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "second",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            allowMultiple = true
        )
        abstract val second: MutableList<String>?

        @get:com.google.devtools.common.options.Option(
            name = "third",
            implicitRequirements = ["--second=requiredByThird"],
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val third: String?
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testCanonicalizeExcludesImplicitDependencyOnRepeatableOption() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(ImplicitDependencyOnAllowMultipleOptions::class.java)
                .build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--first", "--second=explicitValue")
        )
        val first: OptionValueDescription = parser.getOptionValueDescription("first")
        Truth.assertThat(first).isNotNull()
        Truth.assertThat(first.getCanonicalInstances()).hasSize(1)

        val second: OptionValueDescription = parser.getOptionValueDescription("second")
        Truth.assertThat(second).isNotNull()
        Truth.assertThat(second.getSourceString()).matches("implicit requirement of option '--first', null")
        // Implicit requirements don't get listed as canonical. Check that this excludes the implicit
        // value, but still tracks the explicit one.
        Truth.assertThat(second.getCanonicalInstances()).isNotNull()
        Truth.assertThat(second.getCanonicalInstances()).hasSize(1)
        Truth.assertThat(parser.canonicalize()).containsExactly("--first=1", "--second=explicitValue")

        val options: ImplicitDependencyOnAllowMultipleOptions? =
            parser.getOptions<ImplicitDependencyOnAllowMultipleOptions?>(ImplicitDependencyOnAllowMultipleOptions::class.java)
        Truth.assertThat(options!!.first).isTrue()
        Truth.assertThat(options.second).containsExactly("explicitValue", "requiredByFirst")
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testCanonicalizeExcludesImplicitDependencyForOtherwiseUnmentionedRepeatableOption() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(ImplicitDependencyOnAllowMultipleOptions::class.java)
                .build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--first")
        )
        val first: OptionValueDescription = parser.getOptionValueDescription("first")
        Truth.assertThat(first).isNotNull()
        Truth.assertThat(first.getCanonicalInstances()).hasSize(1)

        val second: OptionValueDescription = parser.getOptionValueDescription("second")
        Truth.assertThat(second).isNotNull()
        Truth.assertThat(second.getSourceString()).matches("implicit requirement of option '--first'")
        // Implicit requirements don't get listed as canonical. Check that this excludes the implicit
        // value, leaving behind no mention of second.
        Truth.assertThat(second.getCanonicalInstances()).isNotNull()
        Truth.assertThat(second.getCanonicalInstances()).isEmpty()
        Truth.assertThat(parser.canonicalize()).containsExactly("--first=1")

        val options: ImplicitDependencyOnAllowMultipleOptions? =
            parser.getOptions<ImplicitDependencyOnAllowMultipleOptions?>(ImplicitDependencyOnAllowMultipleOptions::class.java)
        Truth.assertThat(options!!.first).isTrue()
        Truth.assertThat(options.second).containsExactly("requiredByFirst")
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @OptionsClass
    abstract class WarningOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "first",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            metadataTags = [OptionMetadataTag.DEPRECATED],
            defaultValue = "null"
        )
        @get:Deprecated("")
        abstract val first: java.lang.Void?

        @get:com.google.devtools.common.options.Option(
            name = "second",
            allowMultiple = true,
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            metadataTags = [OptionMetadataTag.DEPRECATED],
            defaultValue = "null"
        )
        @get:Deprecated("")
        abstract val second: MutableList<String?>?

        @get:com.google.devtools.common.options.Option(
            name = "third",
            expansion = ["--fourth=true"],
            abbrev = 't',
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            metadataTags = [OptionMetadataTag.DEPRECATED],
            defaultValue = "null"
        )
        @get:Deprecated("")
        abstract val third: java.lang.Void?

        @get:com.google.devtools.common.options.Option(
            name = "fourth",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val fourth: Boolean
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deprecationWarning() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(WarningOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--first")
        )
        Truth.assertThat(parser.getWarnings()).containsExactly("Option 'first' is deprecated")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deprecationWarningForListOption() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(WarningOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--second=a")
        )
        Truth.assertThat(parser.getWarnings())
            .isEqualTo(com.google.common.collect.ImmutableList.of<String?>("Option 'second' is deprecated"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deprecationWarningForExpansionOption() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(WarningOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--third")
        )
        Truth.assertThat(parser.getWarnings())
            .isEqualTo(com.google.common.collect.ImmutableList.of<String?>("Option 'third' is deprecated"))
        Truth.assertThat(parser.getOptions<WarningOptions?>(WarningOptions::class.java).getFourth()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deprecationWarningForAbbreviatedExpansionOption() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(WarningOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("-t")
        )
        Truth.assertThat(parser.getWarnings())
            .isEqualTo(com.google.common.collect.ImmutableList.of<String?>("Option 'third' is deprecated"))
        Truth.assertThat(parser.getOptions<WarningOptions?>(WarningOptions::class.java).getFourth()).isTrue()
    }

    @OptionsClass
    abstract class NewWarningOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "first",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            deprecationWarning = "it's gone"
        )
        abstract val first: java.lang.Void?

        @get:com.google.devtools.common.options.Option(
            name = "second",
            allowMultiple = true,
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            deprecationWarning = "sorry, no replacement"
        )
        abstract val second: MutableList<String?>?

        @get:com.google.devtools.common.options.Option(
            name = "third",
            expansion = ["--fourth=true"],
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            deprecationWarning = "use --forth instead"
        )
        abstract val third: java.lang.Void?

        @get:com.google.devtools.common.options.Option(
            name = "fourth",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val fourth: Boolean
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun newDeprecationWarning() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(NewWarningOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--first")
        )
        Truth.assertThat(parser.getWarnings())
            .isEqualTo(com.google.common.collect.ImmutableList.of<String?>("Option 'first' is deprecated: it's gone"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun newDeprecationWarningForListOption() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(NewWarningOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--second=a")
        )
        Truth.assertThat(parser.getWarnings())
            .isEqualTo(com.google.common.collect.ImmutableList.of<String?>("Option 'second' is deprecated: sorry, no replacement"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun newDeprecationWarningForExpansionOption() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(NewWarningOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--third")
        )
        Truth.assertThat(parser.getWarnings())
            .isEqualTo(com.google.common.collect.ImmutableList.of<String?>("Option 'third' is deprecated: use --forth instead"))
        Truth.assertThat(parser.getOptions<NewWarningOptions?>(NewWarningOptions::class.java).getFourth()).isTrue()
    }

    @OptionsClass
    abstract class ExpansionWarningOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "first",
            expansion = ["--underlying=expandedFromFirst"],
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val first: java.lang.Void?

        @get:com.google.devtools.common.options.Option(
            name = "second",
            expansion = ["--underlying=expandedFromSecond"],
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val second: java.lang.Void?

        @get:com.google.devtools.common.options.Option(
            name = "underlying",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val underlying: String?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun warningForExpansionOverridingExplicitOption() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExpansionWarningOptions::class.java).build()
        parser.parse("--underlying=underlying", "--first")
        Truth.assertThat(parser.getWarnings())
            .containsExactly(
                "option '--first' was expanded and now overrides the explicit option "
                        + "--underlying=underlying with --underlying=expandedFromFirst"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun warningForTwoConflictingExpansionOptions() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExpansionWarningOptions::class.java).build()
        parser.parse("--first", "--second")
        Truth.assertThat(parser.getWarnings())
            .contains(
                "option '--underlying' was expanded from both option '--first' and option "
                        + "'--second'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noWarningForTwoConflictingExpansionOptionsFromRcFile() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExpansionWarningOptions::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.RC_FILE,
            null,
            com.google.common.collect.ImmutableList.of<String?>("--first", "--second")
        )
        Truth.assertThat(parser.getWarnings())
            .doesNotContain(
                "option '--underlying' was expanded from both option '--first' and option "
                        + "'--second'"
            )
    }

    // This test is here to make sure that nobody accidentally changes the
    // order of the enum values and breaks the implicit assumptions elsewhere
    // in the code.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun optionPrioritiesAreCorrectlyOrdered() {
        Truth.assertThat<PriorityCategory?>(OptionPriority.PriorityCategory.PriorityCategory.entries.toTypedArray())
            .hasLength(6)
        Truth.assertThat<PriorityCategory?>(OptionPriority.PriorityCategory.DEFAULT)
            .isLessThan(OptionPriority.PriorityCategory.COMPUTED_DEFAULT)
        Truth.assertThat<PriorityCategory?>(OptionPriority.PriorityCategory.COMPUTED_DEFAULT)
            .isLessThan(OptionPriority.PriorityCategory.RC_FILE)
        Truth.assertThat<PriorityCategory?>(OptionPriority.PriorityCategory.RC_FILE)
            .isLessThan(OptionPriority.PriorityCategory.COMMAND_LINE)
        Truth.assertThat<PriorityCategory?>(OptionPriority.PriorityCategory.COMMAND_LINE)
            .isLessThan(OptionPriority.PriorityCategory.INVOCATION_POLICY)
        Truth.assertThat<PriorityCategory?>(OptionPriority.PriorityCategory.INVOCATION_POLICY)
            .isLessThan(OptionPriority.PriorityCategory.SOFTWARE_REQUIREMENT)
    }

    @OptionsClass
    abstract class IntrospectionExample : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "alpha",
            category = "one",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "alphaDefaultValue"
        )
        abstract val alpha: String?

        @get:com.google.devtools.common.options.Option(
            name = "beta",
            category = "one",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "betaDefaultValue"
        )
        abstract val beta: String?

        @get:com.google.devtools.common.options.Option(
            name = "gamma",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "gammaDefaultValue"
        )
        abstract val gamma: String?

        @get:com.google.devtools.common.options.Option(
            name = "delta",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "deltaDefaultValue"
        )
        abstract val delta: String?

        @get:com.google.devtools.common.options.Option(
            name = "echo",
            metadataTags = [OptionMetadataTag.HIDDEN],
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "echoDefaultValue"
        )
        abstract val echo: String?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asListOfUnparsedOptions() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(com.google.devtools.common.options.OptionsParserTest.IntrospectionExample::class.java)
                .build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            "source",
            com.google.common.collect.ImmutableList.of<String?>("--alpha=one", "--gamma=two", "--echo=three")
        )
        val result: MutableList<ParsedOptionDescription>? = parser.asCompleteListOfParsedOptions()
        Truth.assertThat(result).isNotNull()
        Truth.assertThat(result).hasSize(3)

        Truth.assertThat(result!!.get(0).getOptionDefinition().getOptionName()).isEqualTo("alpha")
        Truth.assertThat(result.get(0).isDocumented()).isTrue()
        Truth.assertThat(result.get(0).isHidden()).isFalse()
        assertThat(result.get(0).unconvertedValue).isEqualTo("one")
        Truth.assertThat(result.get(0).getSource()).isEqualTo("source")
        assertThat(result.get(0).getPriority().priorityCategory)
            .isEqualTo(OptionPriority.PriorityCategory.COMMAND_LINE)

        Truth.assertThat(result.get(1).getOptionDefinition().getOptionName()).isEqualTo("gamma")
        Truth.assertThat(result.get(1).isDocumented()).isFalse()
        Truth.assertThat(result.get(1).isHidden()).isFalse()
        assertThat(result.get(1).unconvertedValue).isEqualTo("two")
        Truth.assertThat(result.get(1).getSource()).isEqualTo("source")
        assertThat(result.get(1).getPriority().priorityCategory)
            .isEqualTo(OptionPriority.PriorityCategory.COMMAND_LINE)

        Truth.assertThat(result.get(2).getOptionDefinition().getOptionName()).isEqualTo("echo")
        Truth.assertThat(result.get(2).isDocumented()).isFalse()
        Truth.assertThat(result.get(2).isHidden()).isTrue()
        assertThat(result.get(2).unconvertedValue).isEqualTo("three")
        Truth.assertThat(result.get(2).getSource()).isEqualTo("source")
        assertThat(result.get(2).getPriority().priorityCategory)
            .isEqualTo(OptionPriority.PriorityCategory.COMMAND_LINE)

        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asListOfExplicitOptions() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(com.google.devtools.common.options.OptionsParserTest.IntrospectionExample::class.java)
                .build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            "source",
            com.google.common.collect.ImmutableList.of<String?>("--alpha=one", "--gamma=two")
        )
        val result: MutableList<ParsedOptionDescription>? = parser.asListOfExplicitOptions()
        Truth.assertThat(result).isNotNull()
        Truth.assertThat(result).hasSize(2)

        Truth.assertThat(result!!.get(0).getOptionDefinition().getOptionName()).isEqualTo("alpha")
        Truth.assertThat(result.get(0).isDocumented()).isTrue()
        assertThat(result.get(0).unconvertedValue).isEqualTo("one")
        Truth.assertThat(result.get(0).getSource()).isEqualTo("source")
        assertThat(result.get(0).getPriority().priorityCategory)
            .isEqualTo(OptionPriority.PriorityCategory.COMMAND_LINE)

        Truth.assertThat(result.get(1).getOptionDefinition().getOptionName()).isEqualTo("gamma")
        Truth.assertThat(result.get(1).isDocumented()).isFalse()
        assertThat(result.get(1).unconvertedValue).isEqualTo("two")
        Truth.assertThat(result.get(1).getSource()).isEqualTo("source")
        assertThat(result.get(1).getPriority().priorityCategory)
            .isEqualTo(OptionPriority.PriorityCategory.COMMAND_LINE)

        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asListOfEffectiveOptions() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(com.google.devtools.common.options.OptionsParserTest.IntrospectionExample::class.java)
                .build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            "command line source",
            com.google.common.collect.ImmutableList.of<String?>(
                "--alpha=alphaValueSetOnCommandLine", "--gamma=gammaValueSetOnCommandLine"
            )
        )
        val result: MutableList<OptionValueDescription>? = parser.asListOfOptionValues()
        Truth.assertThat(result).isNotNull()
        Truth.assertThat(result).hasSize(5)
        val map: HashMap<String?, OptionValueDescription?> = HashMap<String?, OptionValueDescription?>()
        for (description in result!!) {
            map.put(description.getOptionDefinition().getOptionName(), description)
        }

        // All options in IntrospectionExample are single-valued options, and so have a 1:1 relationship
        // with the --flag=value option instance they came from (if any).
        assertOptionValue(
            "alpha",
            "alphaValueSetOnCommandLine",
            OptionPriority.PriorityCategory.COMMAND_LINE,
            "command line source",
            map.get("alpha")
        )
        assertOptionValue(
            "gamma",
            "gammaValueSetOnCommandLine",
            OptionPriority.PriorityCategory.COMMAND_LINE,
            "command line source",
            map.get("gamma")
        )
        assertOptionValue("beta", "betaDefaultValue", map.get("beta"))
        assertOptionValue("delta", "deltaDefaultValue", map.get("delta"))
        assertOptionValue("echo", "echoDefaultValue", map.get("echo"))
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @OptionsClass
    abstract class ListExample : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "alpha",
            converter = com.google.devtools.common.options.OptionsParserTest.StringConverter::class,
            allowMultiple = true,
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val alpha: MutableList<String>?
    }

    // Regression tests for bug:
    // "--option from blazerc unexpectedly overrides --option from command line"
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun overrideListOptions() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ListExample::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            "command line source",
            com.google.common.collect.ImmutableList.of<String?>("--alpha=cli")
        )
        parser.parse(
            OptionPriority.PriorityCategory.RC_FILE,
            "rc file origin",
            com.google.common.collect.ImmutableList.of<String?>("--alpha=rc1", "--alpha=rc2")
        )
        Truth.assertThat(parser.getOptions<ListExample?>(ListExample::class.java).getAlpha())
            .isEqualTo(com.google.common.collect.ImmutableList.of<String?>("rc1", "rc2", "cli"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDashDash() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExampleFoo::class.java).build()

        parser.parse(
            PriorityCategory.COMMAND_LINE,
            "command line source",
            com.google.common.collect.ImmutableList.of<String?>("--foo=woohoo", "residue", "--", "--bar=42")
        )

        Truth.assertThat(parser.getResidue()).hasSize(2)
        Truth.assertThat(parser.getResidue()).containsExactly("residue", "--bar=42")
        Truth.assertThat(parser.getPreDoubleDashResidue()).hasSize(1)
        Truth.assertThat(parser.getPreDoubleDashResidue()).containsExactly("residue")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun listOptionsHaveCorrectPriorities() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ListExample::class.java).build()
        parser.parse(
            PriorityCategory.COMMAND_LINE,
            "command line source, part 1",
            com.google.common.collect.ImmutableList.of<String?>("--alpha=cli1", "--alpha=cli2")
        )
        parser.parse(
            PriorityCategory.COMMAND_LINE,
            "command line source, part 2",
            com.google.common.collect.ImmutableList.of<String?>("--alpha=cli3", "--alpha=cli4")
        )
        parser.parse(
            PriorityCategory.RC_FILE,
            "rc file origin",
            com.google.common.collect.ImmutableList.of<String?>("--alpha=rc1", "--alpha=rc2")
        )

        val alphaValue: OptionValueDescription = parser.getOptionValueDescription("alpha")

        val parsedOptions: MutableList<ParsedOptionDescription>? = alphaValue.getCanonicalInstances()
        println("parsedOptions:\n" + parsedOptions)

        Truth.assertThat(parsedOptions).hasSize(6)
        Truth.assertThat(parsedOptions!!.get(0).getSource()).matches("rc file origin")
        assertThat(parsedOptions.get(0).unconvertedValue).matches("rc1")
        Truth.assertThat(parsedOptions.get(1).getSource()).matches("rc file origin")
        assertThat(parsedOptions.get(1).unconvertedValue).matches("rc2")
        Truth.assertThat(parsedOptions.get(2).getSource()).matches("command line source, part 1")
        assertThat(parsedOptions.get(2).unconvertedValue).matches("cli1")
        Truth.assertThat(parsedOptions.get(3).getSource()).matches("command line source, part 1")
        assertThat(parsedOptions.get(3).unconvertedValue).matches("cli2")
        Truth.assertThat(parsedOptions.get(4).getSource()).matches("command line source, part 2")
        assertThat(parsedOptions.get(4).unconvertedValue).matches("cli3")
        Truth.assertThat(parsedOptions.get(5).getSource()).matches("command line source, part 2")
        assertThat(parsedOptions.get(5).unconvertedValue).matches("cli4")
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @OptionsClass
    abstract class CommaSeparatedOptionsExample : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "alpha",
            converter = CommaSeparatedOptionListConverter::class,
            allowMultiple = true,
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val alpha: MutableList<String>?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun commaSeparatedOptionsWithAllowMultiple() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(CommaSeparatedOptionsExample::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            "command line source",
            com.google.common.collect.ImmutableList.of<String?>("--alpha=one", "--alpha=two,three")
        )
        parser.parse(
            OptionPriority.PriorityCategory.RC_FILE,
            "rc file origin",
            com.google.common.collect.ImmutableList.of<String?>("--alpha=rc1,rc2")
        )
        Truth.assertThat(
            parser.getOptions<CommaSeparatedOptionsExample?>(CommaSeparatedOptionsExample::class.java).getAlpha()
        )
            .isEqualTo(com.google.common.collect.ImmutableList.of<String?>("rc1", "rc2", "one", "two", "three"))
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun commaSeparatedListOptionsHaveCorrectPriorities() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(CommaSeparatedOptionsExample::class.java).build()
        parser.parse(
            OptionPriority.PriorityCategory.COMMAND_LINE,
            "command line source",
            com.google.common.collect.ImmutableList.of<String?>("--alpha=one", "--alpha=two,three")
        )
        parser.parse(
            OptionPriority.PriorityCategory.RC_FILE,
            "rc file origin",
            com.google.common.collect.ImmutableList.of<String?>("--alpha=rc1,rc2,rc3")
        )

        val alphaValue: OptionValueDescription = parser.getOptionValueDescription("alpha")
        val parsedOptions: MutableList<ParsedOptionDescription>? = alphaValue.getCanonicalInstances()

        Truth.assertThat(parsedOptions).hasSize(3)
        Truth.assertThat(parsedOptions!!.get(0).getSource()).matches("rc file origin")
        assertThat(parsedOptions.get(0).unconvertedValue).matches("rc1,rc2,rc3")
        Truth.assertThat(parsedOptions.get(1).getSource()).matches("command line source")
        assertThat(parsedOptions.get(1).unconvertedValue).matches("one")
        Truth.assertThat(parsedOptions.get(2).getSource()).matches("command line source")
        assertThat(parsedOptions.get(2).unconvertedValue).matches("two,three")
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @OptionsClass
    abstract class Yesterday : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "a",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "a"
        )
        abstract val a: String?

        @get:com.google.devtools.common.options.Option(
            name = "b",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "b"
        )
        abstract val b: String?

        @get:com.google.devtools.common.options.Option(
            name = "c",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            expansion = ["--a=cExpansion"]
        )
        abstract val c: java.lang.Void?

        @get:com.google.devtools.common.options.Option(
            name = "d",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            allowMultiple = true
        )
        abstract val d: MutableList<String?>?

        @get:com.google.devtools.common.options.Option(
            name = "e",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            implicitRequirements = ["--a=eRequirement"]
        )
        abstract val e: String?

        @get:com.google.devtools.common.options.Option(
            name = "f",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            implicitRequirements = ["--b=fRequirement"]
        )
        abstract val f: String?

        @get:com.google.devtools.common.options.Option(
            name = "g",
            abbrev = 'h',
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val g: Boolean
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun canonicalizeEasy() {
        Truth.assertThat(canonicalize(Yesterday::class.java, "--a=x")).containsExactly("--a=x")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun canonicalizeSkipDuplicate() {
        Truth.assertThat(canonicalize(Yesterday::class.java, "--a=y", "--a=x")).containsExactly("--a=x")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun canonicalizeExpands() {
        Truth.assertThat(canonicalize(Yesterday::class.java, "--c")).containsExactly("--a=cExpansion")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun canonicalizeExpansionOverridesExplicit() {
        Truth.assertThat(canonicalize(Yesterday::class.java, "--a=x", "--c")).containsExactly("--a=cExpansion")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun canonicalizeExplicitOverridesExpansion() {
        Truth.assertThat(canonicalize(Yesterday::class.java, "--c", "--a=x")).containsExactly("--a=x")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun canonicalizeDoesNotReorder() {
        Truth.assertThat(canonicalize(Yesterday::class.java, "--b=y", "--d=x", "--a=z"))
            .containsExactly("--b=y", "--d=x", "--a=z")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun canonicalizeImplicitDepsNotListed() {
        // e's requirement overrides the explicit "a" here, so the "a" value is not in the canonical
        // form - the effective value is implied and the overridden value is lost.
        Truth.assertThat(canonicalize(Yesterday::class.java, "--a=x", "--e=y")).containsExactly("--e=y")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun canonicalizeSkipsDuplicateAndStillOmitsImplicitDeps() {
        Truth.assertThat(canonicalize(Yesterday::class.java, "--e=x", "--e=y")).containsExactly("--e=y")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun implicitDepsAreNotInTheCanonicalOrderWhenTheyAreOverridden() {
        Truth.assertThat(canonicalize(Yesterday::class.java, "--e=y", "--a=x"))
            .containsExactly("--e=y", "--a=x")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun implicitDepsAreNotInTheCanonicalOrder() {
        // f requires a value of b, that is absent because it is implied.
        Truth.assertThat(canonicalize(Yesterday::class.java, "--f=z", "--a=x"))
            .containsExactly("--f=z", "--a=x")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun canonicalizeDoesNotSkipAllowMultiple() {
        Truth.assertThat(canonicalize(Yesterday::class.java, "--d=a", "--d=b"))
            .containsExactly("--d=a", "--d=b").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun canonicalizeReplacesAbbrevWithName() {
        Truth.assertThat(canonicalize(Yesterday::class.java, "-h")).containsExactly("--g=1")
    }

    /**
     * Check that all forms of boolean flags are canonicalizes to the same form.
     * 
     * The list of accepted values is from
     * [com.google.devtools.common.options.Converters.BooleanConverter], and the value-less
     * --[no] form is controlled by [OptionsParserImpl.identifyOptionAndPossibleArgument].
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun canonicalizeNormalizesBooleanFlags() {
        Truth.assertThat(canonicalize(Yesterday::class.java, "--g")).containsExactly("--g=1")
        Truth.assertThat(canonicalize(Yesterday::class.java, "--g=1")).containsExactly("--g=1")
        Truth.assertThat(canonicalize(Yesterday::class.java, "--g=true")).containsExactly("--g=1")
        Truth.assertThat(canonicalize(Yesterday::class.java, "--g=t")).containsExactly("--g=1")
        Truth.assertThat(canonicalize(Yesterday::class.java, "--g=yes")).containsExactly("--g=1")
        Truth.assertThat(canonicalize(Yesterday::class.java, "--g=y")).containsExactly("--g=1")

        Truth.assertThat(canonicalize(Yesterday::class.java, "--nog")).containsExactly("--g=0")
        Truth.assertThat(canonicalize(Yesterday::class.java, "--g=0")).containsExactly("--g=0")
        Truth.assertThat(canonicalize(Yesterday::class.java, "--g=false")).containsExactly("--g=0")
        Truth.assertThat(canonicalize(Yesterday::class.java, "--g=f")).containsExactly("--g=0")
        Truth.assertThat(canonicalize(Yesterday::class.java, "--g=no")).containsExactly("--g=0")
        Truth.assertThat(canonicalize(Yesterday::class.java, "--g=n")).containsExactly("--g=0")
    }

    @OptionsClass
    abstract class LongValueExample : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "longval",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "2147483648"
        )
        abstract val longval: Long

        @get:com.google.devtools.common.options.Option(
            name = "intval",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "2147483647"
        )
        abstract val intval: Int
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun parseLong() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(LongValueExample::class.java).build()
        parser.parse("")
        var result: LongValueExample? = parser.getOptions<LongValueExample?>(LongValueExample::class.java)
        Truth.assertThat(result!!.longval).isEqualTo(2147483648L)
        Truth.assertThat(result.intval).isEqualTo(2147483647)

        parser.parse("--longval", Long.Companion.MIN_VALUE.toString())
        result = parser.getOptions<LongValueExample?>(LongValueExample::class.java)
        Truth.assertThat(result!!.longval).isEqualTo(Long.Companion.MIN_VALUE)

        parser.parse("--longval", "100")
        result = parser.getOptions<LongValueExample?>(LongValueExample::class.java)
        Truth.assertThat(result!!.longval).isEqualTo(100)
    }

    @org.junit.Test
    fun intOutOfBounds() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(LongValueExample::class.java).build()
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parser.parse("--intval=2147483648") })
        Truth.assertThat(e).hasMessageThat().contains("'2147483648' is not an int")
    }

    @OptionsClass
    abstract class OldNameExample : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "new_name",
            oldName = "old_name",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defaultValue"
        )
        abstract val flag: String?

        @get:com.google.devtools.common.options.Option(
            name = "new_boolean_name",
            oldName = "old_boolean_name",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val booleanFlag: Boolean
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testOldName() {
        var parser: OptionsParser = OptionsParser.builder().optionsClasses(OldNameExample::class.java).build()
        parser.parse("--old_name=foo")
        var result: OldNameExample? = parser.getOptions<OldNameExample?>(OldNameExample::class.java)
        Truth.assertThat(result!!.flag).isEqualTo("foo")
        // Using old option name should cause a warning
        Truth.assertThat(parser.getWarnings())
            .contains("Option 'old_name' is deprecated: Use --new_name instead")
        Truth.assertThat(parser.getWarnings()).containsNoDuplicates()

        // Should also work by its new name.
        parser = OptionsParser.builder().optionsClasses(OldNameExample::class.java).build()
        parser.parse("--new_name=foo")
        result = parser.getOptions<OldNameExample?>(OldNameExample::class.java)
        Truth.assertThat(result!!.flag).isEqualTo("foo")
        // Should be no warnings if the new name is used.
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testOldName_repeatedFlag() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(OldNameExample::class.java).build()
        parser.parse("--old_name=foo", "--old_name=bar")
        val result: OldNameExample? = parser.getOptions<OldNameExample?>(OldNameExample::class.java)
        Truth.assertThat(result!!.flag).isEqualTo("bar")
        // Using old option name should cause a warning
        Truth.assertThat(parser.getWarnings())
            .contains("Option 'old_name' is deprecated: Use --new_name instead")
        Truth.assertThat(parser.getWarnings()).containsNoDuplicates()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOldNameCanonicalization() {
        Truth.assertThat(canonicalize(OldNameExample::class.java, "--old_name=foo"))
            .containsExactly("--new_name=foo")
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testOldName_booleanTrue() {
        var parser: OptionsParser = OptionsParser.builder().optionsClasses(OldNameExample::class.java).build()
        parser.parse("--old_boolean_name=true")
        var result: OldNameExample? = parser.getOptions<OldNameExample?>(OldNameExample::class.java)
        Truth.assertThat(result!!.booleanFlag).isTrue()
        // Using old option name should cause a warning.
        Truth.assertThat(parser.getWarnings())
            .contains("Option 'old_boolean_name' is deprecated: Use --new_boolean_name instead")
        Truth.assertThat(parser.getWarnings()).containsNoDuplicates()

        parser = OptionsParser.builder().optionsClasses(OldNameExample::class.java).build()
        parser.parse("--new_boolean_name=true")
        result = parser.getOptions<OldNameExample?>(OldNameExample::class.java)
        Truth.assertThat(result!!.booleanFlag).isTrue()
        // Should be no warnings if the new name is used.
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testOldName_booleanFalse() {
        var parser: OptionsParser = OptionsParser.builder().optionsClasses(OldNameExample::class.java).build()
        parser.parse("--old_boolean_name=false")
        var result: OldNameExample? = parser.getOptions<OldNameExample?>(OldNameExample::class.java)
        Truth.assertThat(result!!.booleanFlag).isFalse()
        // Using old option name should cause a warning.
        Truth.assertThat(parser.getWarnings())
            .contains("Option 'old_boolean_name' is deprecated: Use --new_boolean_name instead")
        Truth.assertThat(parser.getWarnings()).containsNoDuplicates()

        parser = OptionsParser.builder().optionsClasses(OldNameExample::class.java).build()
        parser.parse("--new_boolean_name=false")
        result = parser.getOptions<OldNameExample?>(OldNameExample::class.java)
        Truth.assertThat(result!!.booleanFlag).isFalse()
        // Should be no warnings if the new name is used.
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testOldName_specialBooleanSyntax() {
        var parser: OptionsParser = OptionsParser.builder().optionsClasses(OldNameExample::class.java).build()
        parser.parse("--old_boolean_name")
        var result: OldNameExample? = parser.getOptions<OldNameExample?>(OldNameExample::class.java)
        Truth.assertThat(result!!.booleanFlag).isTrue()
        // Using old option name should cause a warning.
        Truth.assertThat(parser.getWarnings())
            .contains("Option 'old_boolean_name' is deprecated: Use --new_boolean_name instead")
        Truth.assertThat(parser.getWarnings()).containsNoDuplicates()

        parser = OptionsParser.builder().optionsClasses(OldNameExample::class.java).build()
        parser.parse("--new_boolean_name")
        result = parser.getOptions<OldNameExample?>(OldNameExample::class.java)
        Truth.assertThat(result!!.booleanFlag).isTrue()
        // Should be no warnings if the new name is used.
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testOldName_negatedSpecialBooleanSyntax() {
        var parser: OptionsParser = OptionsParser.builder().optionsClasses(OldNameExample::class.java).build()
        parser.parse("--noold_boolean_name")
        var result: OldNameExample? = parser.getOptions<OldNameExample?>(OldNameExample::class.java)
        Truth.assertThat(result!!.booleanFlag).isFalse()
        // Using old option name should cause a warning.
        Truth.assertThat(parser.getWarnings())
            .contains("Option 'old_boolean_name' is deprecated: Use --new_boolean_name instead")
        Truth.assertThat(parser.getWarnings()).containsNoDuplicates()

        parser = OptionsParser.builder().optionsClasses(OldNameExample::class.java).build()
        parser.parse("--nonew_boolean_name")
        result = parser.getOptions<OldNameExample?>(OldNameExample::class.java)
        Truth.assertThat(result!!.booleanFlag).isFalse()
        // Should be no warnings if the new name is used.
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testOldName_repeatedBooleanFlag() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(OldNameExample::class.java).build()
        parser.parse("--old_boolean_name=false", "--old_boolean_name")
        val result: OldNameExample? = parser.getOptions<OldNameExample?>(OldNameExample::class.java)
        Truth.assertThat(result!!.booleanFlag).isTrue()
        // Using old option name should cause a single warning even if the old name was specified
        // multiple times.
        Truth.assertThat(parser.getWarnings())
            .contains("Option 'old_boolean_name' is deprecated: Use --new_boolean_name instead")
        Truth.assertThat(parser.getWarnings()).containsNoDuplicates()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testOldName_overriddenByNewName() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(OldNameExample::class.java).build()
        parser.parse("--old_boolean_name=false", "--new_boolean_name")
        val result: OldNameExample? = parser.getOptions<OldNameExample?>(OldNameExample::class.java)
        Truth.assertThat(result!!.booleanFlag).isTrue()
        // Using old option name should cause a warning even when overridden by new name.
        Truth.assertThat(parser.getWarnings())
            .contains("Option 'old_boolean_name' is deprecated: Use --new_boolean_name instead")
        Truth.assertThat(parser.getWarnings()).containsNoDuplicates()
    }

    @OptionsClass
    abstract class OldNameNoWarningExample : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "new_name",
            oldName = "old_name",
            oldNameWarning = false,
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defaultValue"
        )
        abstract val flag: String?
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testOldName_noWarning() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(OldNameNoWarningExample::class.java).build()
        parser.parse("--old_name=foo")
        val result: OldNameNoWarningExample? =
            parser.getOptions<OldNameNoWarningExample?>(OldNameNoWarningExample::class.java)
        Truth.assertThat(result!!.flag).isEqualTo("foo")
        // Using old option name should not cause a warning
        Truth.assertThat(parser.getWarnings()).isEmpty()
    }

    @OptionsClass
    abstract class ExampleBooleanFooOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val foo: Boolean
    }

    @org.junit.Test
    fun testBooleanUnderscorePrefixError() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(com.google.devtools.common.options.OptionsParserTest.ExampleBooleanFooOptions::class.java)
                .build()
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                "--no_foo should fail to parse.",
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parser.parse("--no_foo") })
        Truth.assertThat(e).hasMessageThat().contains("Unrecognized option: --no_foo")
    }

    /** Dummy options for testing getHelpCompletion() and visitOptions().  */
    @OptionsClass
    abstract class CompletionOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "secret",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val secret: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "b",
            documentationCategory = OptionDocumentationCategory.LOGGING,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val b: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "a",
            documentationCategory = OptionDocumentationCategory.QUERY,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val a: Boolean
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val optionsCompletionShouldFilterUndocumentedOptions: Unit
        get() {
            val parser: OptionsParser = OptionsParser.builder().optionsClasses(CompletionOptions::class.java).build()
            Truth.assertThat<String?>(
                parser.getOptionsCompletion().split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            )
                .isEqualTo(arrayOf<String>("--a", "--noa", "--b", "--nob"))
        }

    @org.junit.Test
    fun visitOptionsShouldFailWithoutPredicate() {
        checkThatVisitOptionsThrowsNullPointerException(
            null,
            java.util.function.Consumer { option: OptionDefinition? -> },
            "Missing predicate."
        )
    }

    @org.junit.Test
    fun visitOptionsShouldFailWithoutVisitor() {
        checkThatVisitOptionsThrowsNullPointerException(
            java.util.function.Predicate { option: OptionDefinition? -> true },
            null,
            "Missing visitor."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun visitOptionsShouldReturnAllOptionsInOrder() {
        Truth.assertThat(visitOptionsToCollectTheirNames(java.util.function.Predicate { option: OptionDefinition? -> true }))
            .containsExactly("a", "b", "secret")
    }

    @org.junit.Test
    fun visitOptionsShouldObeyPredicate() {
        Truth.assertThat(visitOptionsToCollectTheirNames(java.util.function.Predicate { option: OptionDefinition? -> false }))
            .isEmpty()
        Truth.assertThat(visitOptionsToCollectTheirNames(java.util.function.Predicate { option: OptionDefinition? -> option.getOptionName().length > 1 }))
            .containsExactly("secret")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun setOptionValueAtSpecificPriorityWithoutExpansion_setsOptionAndAddsParsedValue() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExampleFoo::class.java).build()
        val origin: OptionInstanceOrigin =
            OptionInstanceOrigin(
                OptionPriority.lowestOptionPriorityAtCategory(PriorityCategory.INVOCATION_POLICY),
                "invocation policy",  /*implicitDependent=*/
                null,  /*expandedFrom=*/
                null
            )
        val optionDefinition: OptionDefinition? = MethodOptionDefinition.get(ExampleFoo::class.java, "getFoo")

        parser.setOptionValueAtSpecificPriorityWithoutExpansion(origin, optionDefinition, "hello")

        Truth.assertThat(parser.getOptions<ExampleFoo?>(ExampleFoo::class.java).getFoo()).isEqualTo("hello")
        Truth.assertThat(
            parser.asCompleteListOfParsedOptions().stream()
                .map<String?> { obj: ParsedOptionDescription? -> obj.getCommandLineForm() })
            .containsExactly("--foo=hello")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun setOptionValueAtSpecificPriorityWithoutExpansion_addsFlagAlias() {
        val parser: OptionsParser =
            OptionsParser.builder().withAliasFlag("foo").optionsClasses(ExampleFoo::class.java).build()
        val origin: OptionInstanceOrigin =
            OptionInstanceOrigin(
                OptionPriority.lowestOptionPriorityAtCategory(PriorityCategory.INVOCATION_POLICY),
                "invocation policy",  /*implicitDependent=*/
                null,  /*expandedFrom=*/
                null
            )
        val optionDefinition: OptionDefinition? = MethodOptionDefinition.get(ExampleFoo::class.java, "getFoo")

        parser.setOptionValueAtSpecificPriorityWithoutExpansion(origin, optionDefinition, "hi=bar")
        parser.parse("--hi=123")

        Truth.assertThat(parser.getOptions<ExampleFoo?>(ExampleFoo::class.java).getFoo()).isEqualTo("hi=bar")
        Truth.assertThat(parser.getOptions<ExampleFoo?>(ExampleFoo::class.java).getBar()).isEqualTo(123)
        Truth.assertThat(
            parser.asCompleteListOfParsedOptions().stream()
                .map<String?> { obj: ParsedOptionDescription? -> obj.getCommandLineForm() })
            .containsExactly("--bar=123", "--foo=hi=bar")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun setOptionValueAtSpecificPriorityWithoutExpansion_implicitReqs_setsTopFlagOnly() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ImplicitDependencyOptions::class.java).build()
        val origin: OptionInstanceOrigin = createInvocationPolicyOrigin()
        val optionDefinition: OptionDefinition? =
            MethodOptionDefinition.get(ImplicitDependencyOptions::class.java, "getFirst")

        parser.setOptionValueAtSpecificPriorityWithoutExpansion(origin, optionDefinition, "hello")

        val options: ImplicitDependencyOptions? =
            parser.getOptions<ImplicitDependencyOptions?>(ImplicitDependencyOptions::class.java)
        Truth.assertThat(options!!.first).isEqualTo("hello")
        Truth.assertThat(options.second).isNull()
        Truth.assertThat(options.third).isNull()
        Truth.assertThat(
            parser.asCompleteListOfParsedOptions().stream()
                .map<String?> { obj: ParsedOptionDescription? -> obj.getCommandLineForm() })
            .containsExactly("--first=hello")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun setOptionValueAtSpecificPriorityWithoutExpansion_impliedFlag_setsValueSkipsParsed() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ImplicitDependencyOptions::class.java).build()
        val first: ParsedOptionDescription? =
            ParsedOptionDescription.newDummyInstance(
                MethodOptionDefinition.get(ImplicitDependencyOptions::class.java, "getFirst"),
                createInvocationPolicyOrigin(),  /* conversionContext= */
                null
            )
        val origin: OptionInstanceOrigin =
            createInvocationPolicyOrigin( /*implicitDependent=*/first,  /*expandedFrom=*/null)

        val optionDefinition: OptionDefinition? =
            MethodOptionDefinition.get(ImplicitDependencyOptions::class.java, "getSecond")

        parser.setOptionValueAtSpecificPriorityWithoutExpansion(origin, optionDefinition, "hello")

        val options: ImplicitDependencyOptions? =
            parser.getOptions<ImplicitDependencyOptions?>(ImplicitDependencyOptions::class.java)
        Truth.assertThat(options!!.second).isEqualTo("hello")
        Truth.assertThat(options.third).isNull()
        Truth.assertThat(
            parser.asCompleteListOfParsedOptions().stream()
                .map<String?> { obj: ParsedOptionDescription? -> obj.getCommandLineForm() })
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun setOptionValueAtSpecificPriorityWithoutExpansion_expandedFlag_setsValueAndParsed() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ImplicitDependencyOptions::class.java).build()
        val first: ParsedOptionDescription? =
            ParsedOptionDescription.newDummyInstance(
                MethodOptionDefinition.get(ImplicitDependencyOptions::class.java, "getFirst"),
                createInvocationPolicyOrigin(),  /* conversionContext= */
                null
            )
        val origin: OptionInstanceOrigin =
            createInvocationPolicyOrigin( /*implicitDependent=*/null,  /*expandedFrom=*/first)

        val optionDefinition: OptionDefinition? =
            MethodOptionDefinition.get(ImplicitDependencyOptions::class.java, "getSecond")

        parser.setOptionValueAtSpecificPriorityWithoutExpansion(origin, optionDefinition, "hello")

        val options: ImplicitDependencyOptions? =
            parser.getOptions<ImplicitDependencyOptions?>(ImplicitDependencyOptions::class.java)
        Truth.assertThat(options!!.second).isEqualTo("hello")
        Truth.assertThat(options.third).isNull()
        Truth.assertThat(
            parser.asCompleteListOfParsedOptions().stream()
                .map<String?> { obj: ParsedOptionDescription? -> obj.getCommandLineForm() })
            .containsExactly("--second=hello")
    }

    @org.junit.Test
    fun negativeTargetPatternsInOptions_failsDistinctively() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExampleFoo::class.java).build()
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parser.parse("//foo", "-//bar", "//baz") })
        Truth.assertThat(e).hasMessageThat().contains("-//bar")
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Negative target patterns can only appear after the end of options marker")
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Flags corresponding to Starlark-defined build settings always start with '--'")
    }

    @org.junit.Test
    fun negativeExternalTargetPatternsInOptions_failsDistinctively() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExampleFoo::class.java).build()
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parser.parse("//foo", "-@repo//bar", "//baz") })
        Truth.assertThat(e).hasMessageThat().contains("-@repo//bar")
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Negative target patterns can only appear after the end of options marker")
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Flags corresponding to Starlark-defined build settings always start with '--'")
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun fallbackOptions_optionsParsingEquivalently() {
        val fallbackData: OpaqueOptionsData? =
            OptionsParser.getFallbackOptionsData(
                com.google.common.collect.ImmutableList.of<java.lang.Class<out OptionsBase?>?>(
                    ExampleFoo::class.java,
                    ExampleEquivalentWithFoo::class.java
                )
            )
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExampleFoo::class.java).build()
        parser.parseWithSourceFunction(
            PriorityCategory.RC_FILE,
            java.util.function.Function { o: OptionDefinition? -> ".bazelrc" },
            com.google.common.collect.ImmutableList.of<String?>(
                "--ignored_with_value", "--foo", "--foo=bar", "--ignored_without_value", "--bar", "1"
            ),
            fallbackData
        )

        Truth.assertThat(parser.getOptions<ExampleFoo?>(ExampleFoo::class.java)).isNotNull()
        Truth.assertThat(parser.getOptions<ExampleFoo?>(ExampleFoo::class.java).getFoo()).isEqualTo("bar")
        Truth.assertThat(parser.getOptions<ExampleFoo?>(ExampleFoo::class.java).getBar()).isEqualTo(1)

        Truth.assertThat(parser.getOptions<ExampleEquivalentWithFoo?>(ExampleEquivalentWithFoo::class.java)).isNull()
    }

    @org.junit.Test
    fun fallbackOptions_optionsParsingDifferently() {
        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<com.google.devtools.common.options.ConstructionException?>(
                com.google.devtools.common.options.ConstructionException::class.java,
                org.junit.function.ThrowingRunnable {
                    OptionsParser.getFallbackOptionsData(
                        com.google.common.collect.ImmutableList.of<java.lang.Class<out OptionsBase?>?>(
                            ExampleFoo::class.java,
                            ExampleIncompatibleWithFoo::class.java
                        )
                    )
                })
        Truth.assertThat(e).hasCauseThat().isInstanceOf(DuplicateOptionDeclarationException::class.java)
    }

    @OptionsClass
    abstract class ExpandingOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            category = "one",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            expansion = ["--nobar"],
            defaultValue = "null"
        )
        abstract val foo: java.lang.Void?
    }

    @OptionsClass
    abstract class ExpandingOptionsFallback : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "bar",
            category = "one",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "true"
        )
        abstract val bar: Boolean
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun fallbackOptions_expansionToNegativeBooleanFlag() {
        val fallbackData: OpaqueOptionsData? =
            OptionsParser.getFallbackOptionsData(
                com.google.common.collect.ImmutableList.of<java.lang.Class<out OptionsBase?>?>(
                    ExpandingOptions::class.java,
                    ExpandingOptionsFallback::class.java
                )
            )
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExpandingOptions::class.java).build()
        parser.parseWithSourceFunction(
            PriorityCategory.RC_FILE,
            java.util.function.Function { o: OptionDefinition? -> ".bazelrc" },
            com.google.common.collect.ImmutableList.of<String?>("--foo"),
            fallbackData
        )

        Truth.assertThat(parser.getOptions<ExpandingOptions?>(ExpandingOptions::class.java)).isNotNull()
        Truth.assertThat(parser.getOptions<ExpandingOptionsFallback?>(ExpandingOptionsFallback::class.java)).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOptionsParser_getUserOptions_excludesClientOptions() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(ExpandingOptions::class.java, ExpandingOptionsFallback::class.java)
                .build()
        parser.parseWithSourceFunction(
            PriorityCategory.RC_FILE,
            java.util.function.Function { o: OptionDefinition? -> "client" },
            com.google.common.collect.ImmutableList.of<String?>("--foo"),
            null
        )
        Truth.assertThat(parser.getUserOptions()).isEmpty()

        parser.parseWithSourceFunction(
            PriorityCategory.RC_FILE,
            java.util.function.Function { o: OptionDefinition? -> ".bazelrc" },
            com.google.common.collect.ImmutableList.of<String?>("--foo"),
            null
        )

        Truth.assertThat(parser.getUserOptions().keys).containsExactly("--foo", "--nobar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOptionsParser_explicitOptions_excludesFlagsetOptions() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(ExampleFoo::class.java).build()
        parser.parse(
            PriorityCategory.RC_FILE,
            "//test:PROJECT.scl",
            com.google.common.collect.ImmutableList.of<String?>("--foo=set_by_flagset")
        )
        Truth.assertThat(parser.asListOfExplicitOptions()).isEmpty()
        Truth.assertThat(parser.canonicalize()).contains("--foo=set_by_flagset")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOptionsParser_getUserOptions_excludesInvocationPolicy() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(ExpandingOptions::class.java, ExpandingOptionsFallback::class.java)
                .build()
        parser.parseWithSourceFunction(
            PriorityCategory.RC_FILE,
            java.util.function.Function { o: OptionDefinition? -> "Invocation policy" },
            com.google.common.collect.ImmutableList.of<String?>("--foo"),
            null
        )
        Truth.assertThat(parser.getUserOptions()).isEmpty()

        parser.parseWithSourceFunction(
            PriorityCategory.RC_FILE,
            java.util.function.Function { o: OptionDefinition? -> ".bazelrc" },
            com.google.common.collect.ImmutableList.of<String?>("--foo"),
            null
        )

        Truth.assertThat(parser.getUserOptions().keys).containsExactly("--foo", "--nobar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasWithNoPrefix_emitsWarningIfNative() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .withAliasFlag("flag_alias")
                .optionsClasses(BooleanAliasOptions::class.java)
                .build()
        parser.parse("--flag_alias=foo=bar")

        parser.parse("--nofoo")

        // The actual flag should not change from default.
        Truth.assertThat(parser.getOptions<BooleanAliasOptions?>(BooleanAliasOptions::class.java).getBar()).isTrue()
        // The alias flag should change.
        Truth.assertThat(parser.getOptions<BooleanAliasOptions?>(BooleanAliasOptions::class.java).getFoo()).isFalse()
        Truth.assertThat(parser.getWarnings())
            .contains("Flag --nofoo is deprecated. Use --foo=false instead.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasWithNoPrefix_failsIfNotNative() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .withAliasFlag("flag_alias")
                .optionsClasses(BooleanAliasOptions::class.java)
                .build()
        // Set up alias: baz=bar. baz is NOT a native flag.
        parser.parse("--flag_alias=baz=bar")

        // Use --nobaz. It should NOT swap and should fail as unrecognized.
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { parser.parse("--nobaz") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasWithNoPrefixAndCustomWarning_emitsCustomWarning() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .withAliasFlag("flag_alias")
                .optionsClasses(DeprecatedAliasOptions::class.java)
                .build()
        parser.parse("--flag_alias=foo=bar")

        parser.parse("--nofoo")

        Truth.assertThat(parser.getWarnings()).contains("Option 'foo' is deprecated: Don't use foo.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasWithNoPrefix_emitsCustomWarningIfAvailable() {
        val parser: OptionsParser =
            OptionsParser.builder()
                .withAliasFlag("flag_alias")
                .optionsClasses(DeprecatedAliasOptions::class.java)
                .build()
        parser.parse("--flag_alias=foo=bar")

        parser.parse("--nofoo")

        // The actual flag should not change from default.
        Truth.assertThat(parser.getOptions<DeprecatedAliasOptions?>(DeprecatedAliasOptions::class.java).getBar())
            .isTrue()
        // The alias flag should change.
        Truth.assertThat(parser.getOptions<DeprecatedAliasOptions?>(DeprecatedAliasOptions::class.java).getFoo())
            .isFalse()
        // Should show custom warning.
        Truth.assertThat(parser.getWarnings()).contains("Option 'foo' is deprecated: Don't use foo.")
        // Should NOT show generalized warning because a custom one was present.
        Truth.assertThat(parser.getWarnings())
            .doesNotContain("Flag --nofoo is deprecated. Use --foo=false instead.")
    }

    companion object {
        private fun assertOptionValue(
            expectedName: String?, expectedValue: Any?, actual: OptionValueDescription?
        ) {
            Truth.assertThat(actual).isNotNull()
            Truth.assertThat(actual.getOptionDefinition().getOptionName()).isEqualTo(expectedName)
            Truth.assertThat(actual.getValue()).isEqualTo(expectedValue)
        }

        private fun assertOptionValue(
            expectedName: String?,
            expectedValue: Any?,
            expectedPriority: PriorityCategory?,
            expectedSource: String?,
            actual: OptionValueDescription
        ) {
            assertOptionValue(expectedName, expectedValue, actual)
            Truth.assertThat(actual.getSourceString()).isEqualTo(expectedSource)
            Truth.assertThat(actual.getCanonicalInstances()).isNotEmpty()
            assertThat(actual.getCanonicalInstances().get(0).getPriority().priorityCategory)
                .isEqualTo(expectedPriority)
        }

        @Throws(OptionsParsingException::class)
        fun canonicalize(optionsClass: java.lang.Class<out OptionsBase?>?, vararg args: String?): MutableList<String>? {
            val parser: OptionsParser =
                OptionsParser.builder().optionsClasses(optionsClass).allowResidue(false).build()
            parser.parse(*args)
            return parser.canonicalize()
        }

        private fun checkThatVisitOptionsThrowsNullPointerException(
            predicate: java.util.function.Predicate<OptionDefinition?>?,
            visitor: java.util.function.Consumer<OptionDefinition?>?,
            expectedMessage: String?
        ) {
            val ex: java.lang.NullPointerException? =
                org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
                    java.lang.NullPointerException::class.java,
                    org.junit.function.ThrowingRunnable {
                        OptionsParser.builder()
                            .optionsClasses(CompletionOptions::class.java)
                            .build()
                            .visitOptions(predicate, visitor)
                    })
            Truth.assertThat(ex).hasMessageThat().isEqualTo(expectedMessage)
        }

        private fun visitOptionsToCollectTheirNames(
            predicate: java.util.function.Predicate<OptionDefinition?>?
        ): MutableList<String> {
            val names: MutableList<String> = java.util.ArrayList<String>()
            val visitor: java.util.function.Consumer<OptionDefinition?> =
                java.util.function.Consumer { option: OptionDefinition? -> names.add(option.getOptionName()) }

            val parser: OptionsParser = OptionsParser.builder().optionsClasses(CompletionOptions::class.java).build()
            parser.visitOptions(predicate, visitor)

            return names
        }

        private fun createInvocationPolicyOrigin(
            implicitDependent: ParsedOptionDescription? = null, expandedFrom: ParsedOptionDescription? = null
        ): OptionInstanceOrigin {
            return OptionInstanceOrigin(
                OptionPriority.lowestOptionPriorityAtCategory(PriorityCategory.INVOCATION_POLICY),
                "invocation policy",
                implicitDependent,
                expandedFrom
            )
        }
    }
}
