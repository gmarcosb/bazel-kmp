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
package com.google.devtools.common.options

import Converter.Contextless
import OptionFilters.OptionEffectTag
import com.google.common.truth.Correspondence
import com.google.common.truth.Correspondence.BinaryPredicate
import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.Converter.Contextless
import com.google.devtools.common.options.DuplicateOptionDeclarationException
import com.google.devtools.common.options.IsolatedOptionsData
import com.google.devtools.common.options.OptionDefinition
import com.google.devtools.common.options.OptionDocumentationCategory
import com.google.devtools.common.options.OptionEffectTag
import com.google.devtools.common.options.OptionsBase
import com.google.devtools.common.options.OptionsClass
import com.google.devtools.common.options.OptionsData
import com.google.devtools.common.options.OptionsDataTest
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.add
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.addAll
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [IsolatedOptionsData] and [OptionsData].  */
@RunWith(JUnit4::class)
class OptionsDataTest {
    /** Dummy options class.  */
    @OptionsClass
    abstract class ExampleNameConflictOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "1"
        )
        abstract val foo: Int

        @get:com.google.devtools.common.options.Option(
            name = "foo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "I should conflict with foo"
        )
        abstract val anotherFoo: String?
    }

    @org.junit.Test
    fun testNameConflictInSingleClass() {
        val e: com.google.devtools.common.options.ConstructionException? =
            org.junit.Assert.assertThrows<com.google.devtools.common.options.ConstructionException?>(
                "foo should conflict with the previous flag foo",
                com.google.devtools.common.options.ConstructionException::class.java,
                org.junit.function.ThrowingRunnable { construct(ExampleNameConflictOptions::class.java) })
        Truth.assertThat(e).hasCauseThat().isInstanceOf(DuplicateOptionDeclarationException::class.java)
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Duplicate option name, due to option name collision: --foo")
    }

    /** Dummy options class.  */
    @OptionsClass
    abstract class ExampleIntegerFooOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "5"
        )
        abstract val foo: Int
    }

    /** Dummy options class.  */
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
    fun testNameConflictInTwoClasses() {
        val e: com.google.devtools.common.options.ConstructionException? =
            org.junit.Assert.assertThrows<com.google.devtools.common.options.ConstructionException?>(
                "foo should conflict with the previous flag foo",
                com.google.devtools.common.options.ConstructionException::class.java,
                org.junit.function.ThrowingRunnable {
                    construct(
                        ExampleIntegerFooOptions::class.java,
                        com.google.devtools.common.options.OptionsDataTest.ExampleBooleanFooOptions::class.java
                    )
                })
        Truth.assertThat(e).hasCauseThat().isInstanceOf(DuplicateOptionDeclarationException::class.java)
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Duplicate option name, due to option name collision: --foo")
    }

    /** Dummy options class.  */
    @OptionsClass
    abstract class ExamplePrefixedFooOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "nofoo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val noFoo: Boolean
    }

    @org.junit.Test
    fun testBooleanPrefixNameConflict() {
        // Try the same test in both orders, the parser should fail if the overlapping flag is defined
        // before or after the boolean flag introduces the alias.
        var e: com.google.devtools.common.options.ConstructionException? =
            org.junit.Assert.assertThrows<com.google.devtools.common.options.ConstructionException?>(
                "nofoo should conflict with the previous flag foo, "
                        + "since foo, as a boolean flag, can be written as --nofoo",
                com.google.devtools.common.options.ConstructionException::class.java,
                org.junit.function.ThrowingRunnable {
                    construct(
                        com.google.devtools.common.options.OptionsDataTest.ExampleBooleanFooOptions::class.java,
                        ExamplePrefixedFooOptions::class.java
                    )
                })
        Truth.assertThat(e).hasCauseThat().isInstanceOf(DuplicateOptionDeclarationException::class.java)
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "Duplicate option name, due to option --nofoo, it "
                        + "conflicts with a negating alias for boolean flag --foo"
            )

        e =
            org.junit.Assert.assertThrows<com.google.devtools.common.options.ConstructionException?>(
                "option nofoo should conflict with the previous flag foo, "
                        + "since foo, as a boolean flag, can be written as --nofoo",
                com.google.devtools.common.options.ConstructionException::class.java,
                org.junit.function.ThrowingRunnable {
                    construct(
                        ExamplePrefixedFooOptions::class.java,
                        com.google.devtools.common.options.OptionsDataTest.ExampleBooleanFooOptions::class.java
                    )
                })
        Truth.assertThat(e).hasCauseThat().isInstanceOf(DuplicateOptionDeclarationException::class.java)
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Duplicate option name, due to boolean option alias: --nofoo")
    }

    /** Dummy options class.  */
    @OptionsClass
    abstract class ExampleBarWasNamedFooOption : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "bar",
            oldName = "foo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val bar: Boolean
    }

    @org.junit.Test
    fun testBooleanAliasWithOldNameConflict() {
        // Try the same test in both orders, the parser should fail if the overlapping flag is defined
        // before or after the boolean flag introduces the alias.
        var e: com.google.devtools.common.options.ConstructionException? =
            org.junit.Assert.assertThrows<com.google.devtools.common.options.ConstructionException?>(
                "bar has old name foo, which is a boolean flag and can be named as nofoo, so it "
                        + "should conflict with the previous option --nofoo",
                com.google.devtools.common.options.ConstructionException::class.java,
                org.junit.function.ThrowingRunnable {
                    construct(
                        ExamplePrefixedFooOptions::class.java,
                        ExampleBarWasNamedFooOption::class.java
                    )
                })
        Truth.assertThat(e).hasCauseThat().isInstanceOf(DuplicateOptionDeclarationException::class.java)
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Duplicate option name, due to boolean option alias: --nofoo")

        e =
            org.junit.Assert.assertThrows<com.google.devtools.common.options.ConstructionException?>(
                "nofoo should conflict with the previous flag bar that has old name foo, "
                        + "since foo, as a boolean flag, can be written as --nofoo",
                com.google.devtools.common.options.ConstructionException::class.java,
                org.junit.function.ThrowingRunnable {
                    construct(
                        ExampleBarWasNamedFooOption::class.java,
                        ExamplePrefixedFooOptions::class.java
                    )
                })
        Truth.assertThat(e).hasCauseThat().isInstanceOf(DuplicateOptionDeclarationException::class.java)
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "Duplicate option name, due to option --nofoo, it conflicts with a negating "
                        + "alias for boolean flag --foo"
            )
    }

    /** Dummy options class.  */
    @OptionsClass
    abstract class ExampleBarWasNamedNoFooOption : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "bar",
            oldName = "nofoo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val bar: Boolean
    }

    @org.junit.Test
    fun testBooleanWithOldNameAsAliasOfBooleanConflict() {
        // Try the same test in both orders, the parser should fail if the overlapping flag is defined
        // before or after the boolean flag introduces the alias.
        var e: com.google.devtools.common.options.ConstructionException? =
            org.junit.Assert.assertThrows<com.google.devtools.common.options.ConstructionException?>(
                "nofoo, the old name for bar, should conflict with the previous flag foo, "
                        + "since foo, as a boolean flag, can be written as --nofoo",
                com.google.devtools.common.options.ConstructionException::class.java,
                org.junit.function.ThrowingRunnable {
                    construct(
                        com.google.devtools.common.options.OptionsDataTest.ExampleBooleanFooOptions::class.java,
                        ExampleBarWasNamedNoFooOption::class.java
                    )
                })
        Truth.assertThat(e).hasCauseThat().isInstanceOf(DuplicateOptionDeclarationException::class.java)
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "Duplicate option name, due to old option name --nofoo, it conflicts with a "
                        + "negating alias for boolean flag --foo"
            )

        e =
            org.junit.Assert.assertThrows<com.google.devtools.common.options.ConstructionException?>(
                "foo, as a boolean flag, can be written as --nofoo and should conflict with the "
                        + "previous option bar that has old name nofoo",
                com.google.devtools.common.options.ConstructionException::class.java,
                org.junit.function.ThrowingRunnable {
                    construct(
                        ExampleBarWasNamedNoFooOption::class.java,
                        com.google.devtools.common.options.OptionsDataTest.ExampleBooleanFooOptions::class.java
                    )
                })
        Truth.assertThat(e).hasCauseThat().isInstanceOf(DuplicateOptionDeclarationException::class.java)
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Duplicate option name, due to boolean option alias: --nofoo")
    }

    /** Dummy options class.  */
    @OptionsClass
    abstract class ExampleFooBooleanConflictsWithOwnOldName : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "nofoo",
            oldName = "foo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val foo: Boolean
    }

    @org.junit.Test
    fun testSelfConflictBooleanAliases() {
        // Try the same test in both orders, the parser should fail if the overlapping flag is defined
        // before or after the boolean flag introduces the alias.
        val e: com.google.devtools.common.options.ConstructionException? =
            org.junit.Assert.assertThrows<com.google.devtools.common.options.ConstructionException?>(
                "foo, the old name for boolean option nofoo, should conflict with its own new name.",
                com.google.devtools.common.options.ConstructionException::class.java,
                org.junit.function.ThrowingRunnable { construct(ExampleFooBooleanConflictsWithOwnOldName::class.java) })
        Truth.assertThat(e).hasCauseThat().isInstanceOf(DuplicateOptionDeclarationException::class.java)
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Duplicate option name, due to boolean option alias: --nofoo")
    }

    /** Dummy options class.  */
    @OptionsClass
    abstract class OldNameToCanonicalNameConflictExample : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "new_name",
            oldName = "old_name",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defaultValue"
        )
        abstract val flag1: String?

        @get:com.google.devtools.common.options.Option(
            name = "old_name",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defaultValue"
        )
        abstract val flag2: String?
    }

    @org.junit.Test
    fun testOldNameToCanonicalNameConflict() {
        val expected: com.google.devtools.common.options.ConstructionException? =
            org.junit.Assert.assertThrows<com.google.devtools.common.options.ConstructionException?>(
                "old_name should conflict with the flag already named old_name",
                com.google.devtools.common.options.ConstructionException::class.java,
                org.junit.function.ThrowingRunnable { construct(OldNameToCanonicalNameConflictExample::class.java) })
        Truth.assertThat(expected).hasCauseThat().isInstanceOf(DuplicateOptionDeclarationException::class.java)
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains(
                "Duplicate option name, due to option name collision with another option's old name:"
                        + " --old_name"
            )
    }

    /** Dummy options class.  */
    @OptionsClass
    abstract class OldNameConflictExample : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "new_name",
            oldName = "old_name",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defaultValue"
        )
        abstract val flag1: String?

        @get:com.google.devtools.common.options.Option(
            name = "another_name",
            oldName = "old_name",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defaultValue"
        )
        abstract val flag2: String?
    }

    @org.junit.Test
    fun testOldNameToOldNameConflict() {
        val expected: com.google.devtools.common.options.ConstructionException? =
            org.junit.Assert.assertThrows<com.google.devtools.common.options.ConstructionException?>(
                "old_name should conflict with the flag already named old_name",
                com.google.devtools.common.options.ConstructionException::class.java,
                org.junit.function.ThrowingRunnable { construct(OldNameConflictExample::class.java) })
        Truth.assertThat(expected).hasCauseThat().isInstanceOf(DuplicateOptionDeclarationException::class.java)
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains(
                "Duplicate option name, due to old option name collision with another "
                        + "old option name: --old_name"
            )
    }

    /** Dummy options class.  */
    class StringConverter : Contextless<String?>() {
        override fun convert(input: String?): String? {
            return input
        }

        val typeDescription: String
            get() = "a string"
    }

    /**
     * Dummy options class.
     * 
     * 
     * Option name order is different from field name order.
     * 
     * 
     * There are four fields to increase the likelihood of a non-deterministic order being noticed.
     */
    @OptionsClass
    abstract class FieldNamesDifferOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "0"
        )
        abstract val aFoo: Int

        @get:com.google.devtools.common.options.Option(
            name = "bar",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "0"
        )
        abstract val bBar: Int

        @get:com.google.devtools.common.options.Option(
            name = "baz",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "0"
        )
        abstract val cBaz: Int

        @get:com.google.devtools.common.options.Option(
            name = "qux",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "0"
        )
        abstract val dQux: Int
    }

    /** Dummy options class.  */
    @OptionsClass
    abstract class EndOfAlphabetOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "X",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "0"
        )
        abstract val x: Int

        @get:com.google.devtools.common.options.Option(
            name = "Y",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "0"
        )
        abstract val y: Int
    }

    /** Dummy options class.  */
    @OptionsClass
    abstract class ReverseOrderedOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "C",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "0"
        )
        abstract val c: Int

        @get:com.google.devtools.common.options.Option(
            name = "B",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "0"
        )
        abstract val b: Int

        @get:com.google.devtools.common.options.Option(
            name = "A",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "0"
        )
        abstract val a: Int
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun optionsClassesIsOrdered() {
        val data: IsolatedOptionsData = construct(
            FieldNamesDifferOptions::class.java,
            EndOfAlphabetOptions::class.java,
            ReverseOrderedOptions::class.java
        )
        Truth.assertThat(data.getOptionsClasses()).containsExactly(
            FieldNamesDifferOptions::class.java,
            EndOfAlphabetOptions::class.java,
            ReverseOrderedOptions::class.java
        ).inOrder()
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val allNamedFieldsIsOrdered: Unit
        get() {
            val data: IsolatedOptionsData = construct(
                FieldNamesDifferOptions::class.java,
                EndOfAlphabetOptions::class.java,
                ReverseOrderedOptions::class.java
            )
            val names: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
            for (entry in data.getAllOptionDefinitions()) {
                names.add(entry.key)
            }
            Truth.assertThat(names).containsExactly(
                "bar", "baz", "foo", "qux", "X", "Y", "A", "B", "C"
            ).inOrder()
        }

    private fun getOptionNames(optionsBase: java.lang.Class<out OptionsBase?>?): MutableList<String?> {
        val result: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
        for (optionDefinition in OptionsData.getAllOptionDefinitionsForClass(optionsBase)) {
            result.add(optionDefinition.getOptionName())
        }
        return result
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val fieldsForClassIsOrdered: Unit
        get() {
            Truth.assertThat(getOptionNames(FieldNamesDifferOptions::class.java))
                .containsExactly("bar", "baz", "foo", "qux")
                .inOrder()
            Truth.assertThat(getOptionNames(EndOfAlphabetOptions::class.java)).containsExactly("X", "Y").inOrder()
            Truth.assertThat(getOptionNames(ReverseOrderedOptions::class.java))
                .containsExactly("A", "B", "C")
                .inOrder()
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun optionsDefinitionsAreSharedBetweenOptionsBases() {
        val class1: java.lang.Class<FieldNamesDifferOptions?> = FieldNamesDifferOptions::class.java
        val class2: java.lang.Class<EndOfAlphabetOptions?> = EndOfAlphabetOptions::class.java
        val class3: java.lang.Class<ReverseOrderedOptions?> = ReverseOrderedOptions::class.java

        // Construct the definitions once and accumulate them so we can test that these are not
        // recomputed during the construction of the options data.
        val optionDefinitions: com.google.common.collect.ImmutableList<OptionDefinition?> =
            com.google.common.collect.ImmutableList.Builder<OptionDefinition?>()
                .addAll(OptionsData.getAllOptionDefinitionsForClass(class1))
                .addAll(OptionsData.getAllOptionDefinitionsForClass(class2))
                .addAll(OptionsData.getAllOptionDefinitionsForClass(class3))
                .build()

        // Construct the data all together.
        val data: IsolatedOptionsData = construct(class1, class2, class3)
        val optionDefinitionsFromData: java.util.ArrayList<OptionDefinition?> =
            java.util.ArrayList<OptionDefinition?>(optionDefinitions.size)
        data.getAllOptionDefinitions()
            .forEach(java.util.function.Consumer { entry: MutableMap.MutableEntry<String?, OptionDefinition?>? ->
                optionDefinitionsFromData.add(
                    entry!!.value
                )
            })

        val referenceEquality: Correspondence<Any?, Any?> =
            Correspondence.from<Any?, Any?>(
                BinaryPredicate { obj1: Any?, obj2: Any? -> obj1 === obj2 },
                "is the same object as"
            )
        Truth.assertThat(optionDefinitionsFromData)
            .comparingElementsUsing<Any?, Any?>(referenceEquality)
            .containsAtLeastElementsIn(optionDefinitions)

        // Construct options data for each class separately, and check again.
        val data1: IsolatedOptionsData = construct(class1)
        val data2: IsolatedOptionsData = construct(class2)
        val data3: IsolatedOptionsData = construct(class3)
        val optionDefinitionsFromGroupedData: java.util.ArrayList<OptionDefinition?> =
            java.util.ArrayList<OptionDefinition?>(optionDefinitions.size)
        data1
            .getAllOptionDefinitions()
            .forEach(java.util.function.Consumer { entry: MutableMap.MutableEntry<String?, OptionDefinition?>? ->
                optionDefinitionsFromGroupedData.add(
                    entry!!.value
                )
            })
        data2
            .getAllOptionDefinitions()
            .forEach(java.util.function.Consumer { entry: MutableMap.MutableEntry<String?, OptionDefinition?>? ->
                optionDefinitionsFromGroupedData.add(
                    entry!!.value
                )
            })
        data3
            .getAllOptionDefinitions()
            .forEach(java.util.function.Consumer { entry: MutableMap.MutableEntry<String?, OptionDefinition?>? ->
                optionDefinitionsFromGroupedData.add(
                    entry!!.value
                )
            })

        Truth.assertThat(optionDefinitionsFromGroupedData)
            .comparingElementsUsing<Any?, Any?>(referenceEquality)
            .containsAtLeastElementsIn(optionDefinitions)
    }

    /** Dummy options class.  */
    @OptionsClass
    abstract class ValidExpansionOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "1"
        )
        abstract val foo: Int

        @get:com.google.devtools.common.options.Option(
            name = "bar",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            expansion = ["--foo=42"]
        )
        abstract val bar: java.lang.Void?
    }

    @org.junit.Test
    fun staticExpansionOptionsCanBeVoidType() {
        construct(ValidExpansionOptions::class.java)
    }

    companion object {
        @Throws(com.google.devtools.common.options.ConstructionException::class)
        private fun construct(optionsClass: java.lang.Class<out OptionsBase?>): IsolatedOptionsData {
            return IsolatedOptionsData.from(
                com.google.common.collect.ImmutableList.of<java.lang.Class<out OptionsBase?>?>(optionsClass),  /* allowDuplicatesParsingEquivalently= */
                false
            )
        }

        @Throws(com.google.devtools.common.options.ConstructionException::class)
        private fun construct(
            optionsClass1: java.lang.Class<out OptionsBase?>?, optionsClass2: java.lang.Class<out OptionsBase?>?
        ): IsolatedOptionsData {
            return IsolatedOptionsData.from(
                com.google.common.collect.ImmutableList.of<java.lang.Class<out OptionsBase?>?>(
                    optionsClass1,
                    optionsClass2
                ),  /* allowDuplicatesParsingEquivalently= */
                false
            )
        }

        @Throws(com.google.devtools.common.options.ConstructionException::class)
        private fun construct(
            optionsClass1: java.lang.Class<out OptionsBase?>?,
            optionsClass2: java.lang.Class<out OptionsBase?>?,
            optionsClass3: java.lang.Class<out OptionsBase?>?
        ): IsolatedOptionsData {
            return IsolatedOptionsData.from(
                com.google.common.collect.ImmutableList.of<java.lang.Class<out OptionsBase?>?>(
                    optionsClass1,
                    optionsClass2,
                    optionsClass3
                ),  /* allowDuplicatesParsingEquivalently= */
                false
            )
        }
    }
}
