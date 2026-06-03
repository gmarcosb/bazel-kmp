// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/** Test of type-conversions for build-specific types.  */
@RunWith(JUnit4::class)
class BuildTypeTest {
    private val labelConverter: LabelConverter =
        LabelConverter(PackageIdentifier.createInMainRepo("quux"), RepositoryMapping.EMPTY)

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepsDictOrdering() {
        val input: MutableMap<Any?, String?> = com.google.common.collect.ImmutableMap.Builder<Any?, String?>()
            .put("c", "//c")
            .put("b", "//b")
            .put("a", "//a")
            .put("f", "//f")
            .put("e", "//e")
            .put("d", "//d")
            .build()

        assertThat(BuildType.LABEL_DICT_UNARY.convert(input, null, labelConverter).keySet())
            .containsExactly("c", "b", "a", "f", "e", "d")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictConvertsToMapFromLabelToString() {
        val input: MutableMap<Any?, String?> =
            com.google.common.collect.ImmutableMap.Builder<Any?, String?>()
                .put("//absolute:label", "absolute value")
                .put(":relative", "theory of relativity")
                .put("nocolon", "colonial times")
                .put("//current/package:explicit", "explicit content")
                .put(Label.parseCanonical("//i/was/already/a/label"), "and that's okay")
                .build()
        val converter: LabelConverter =
            LabelConverter(PackageIdentifier.parse("//current/package"), RepositoryMapping.EMPTY)

        val expected: MutableMap<Label?, String?> =
            com.google.common.collect.ImmutableMap.Builder<Label?, String?>()
                .put(Label.parseCanonical("//absolute:label"), "absolute value")
                .put(Label.parseCanonical("//current/package:relative"), "theory of relativity")
                .put(Label.parseCanonical("//current/package:nocolon"), "colonial times")
                .put(Label.parseCanonical("//current/package:explicit"), "explicit content")
                .put(Label.parseCanonical("//i/was/already/a/label"), "and that's okay")
                .build()

        assertThat(BuildType.LABEL_KEYED_STRING_DICT.convert(input, null, converter))
            .containsExactlyEntriesIn(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictConvertingStringShouldFail() {
        val expected: ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                ConversionException::class.java,
                org.junit.function.ThrowingRunnable {
                    BuildType.LABEL_KEYED_STRING_DICT.convert(
                        "//actually/a:label", null, labelConverter
                    )
                })
        assertThat(expected)
            .hasMessageThat()
            .isEqualTo(
                "expected value of type 'dict(label, string)', "
                        + "but got \"//actually/a:label\" (string)"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictConvertingListShouldFail() {
        val expected: ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                ConversionException::class.java,
                org.junit.function.ThrowingRunnable {
                    BuildType.LABEL_KEYED_STRING_DICT.convert(
                        com.google.common.collect.ImmutableList.of<E?>("//actually/a:label"), null, labelConverter
                    )
                })
        assertThat(expected)
            .hasMessageThat()
            .isEqualTo(
                "expected value of type 'dict(label, string)', "
                        + "but got [\"//actually/a:label\"] (List)"
            )
    }

    @org.junit.Test
    fun testLabelKeyedStringDictConvertingMapWithNonStringKeyShouldFail() {
        val expected: ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                ConversionException::class.java,
                org.junit.function.ThrowingRunnable {
                    BuildType.LABEL_KEYED_STRING_DICT.convert(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(StarlarkInt.of(1), "OK"), null, labelConverter
                    )
                })
        assertThat(expected)
            .hasMessageThat()
            .isEqualTo("expected value of type 'string' for dict key element, but got 1 (int)")
    }

    @org.junit.Test
    fun testLabelKeyedStringDictConvertingMapWithNonStringValueShouldFail() {
        val expected: ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                ConversionException::class.java,
                org.junit.function.ThrowingRunnable {
                    BuildType.LABEL_KEYED_STRING_DICT.convert(
                        com.google.common.collect.ImmutableMap.of<K?, V?>("//actually/a:label", StarlarkInt.of(3)),
                        null,
                        labelConverter
                    )
                })
        assertThat(expected)
            .hasMessageThat()
            .isEqualTo("expected value of type 'string' for dict value element, but got 3 (int)")
    }

    @org.junit.Test
    fun testLabelKeyedStringDictConvertingMapWithInvalidLabelKeyShouldFail() {
        val expected: ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                ConversionException::class.java,
                org.junit.function.ThrowingRunnable {
                    BuildType.LABEL_KEYED_STRING_DICT.convert(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            "//uplevel/references/are:../../forbidden",
                            "OK"
                        ),
                        null,
                        labelConverter
                    )
                })
        assertThat(expected)
            .hasMessageThat()
            .isEqualTo(
                ("invalid label '//uplevel/references/are:../../forbidden' in "
                        + "dict key element: invalid target name '../../forbidden': "
                        + "target names may not contain up-level references '..'")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictConvertingMapWithMultipleEquivalentKeysShouldFail() {
        val converter: LabelConverter =
            LabelConverter(PackageIdentifier.parse("//current/package"), RepositoryMapping.EMPTY)
        val input: MutableMap<String?, String?> = com.google.common.collect.ImmutableMap.Builder<String?, String?>()
            .put(":reference", "value1")
            .put("//current/package:reference", "value2")
            .build()
        val expected: ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                ConversionException::class.java,
                org.junit.function.ThrowingRunnable {
                    BuildType.LABEL_KEYED_STRING_DICT.convert(
                        input,
                        null,
                        converter
                    )
                })
        assertThat(expected)
            .hasMessageThat()
            .isEqualTo(
                "duplicate labels: //current/package:reference "
                        + "(as [\":reference\", \"//current/package:reference\"])"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictConvertingMapWithMultipleSetsOfEquivalentKeysShouldFail() {
        val converter: LabelConverter =
            LabelConverter(PackageIdentifier.parse("//current/rule"), RepositoryMapping.EMPTY)
        val input: MutableMap<String?, String?> = com.google.common.collect.ImmutableMap.Builder<String?, String?>()
            .put(":rule", "first set")
            .put("//current/rule:rule", "also first set")
            .put("//other/package:package", "interrupting rule")
            .put("//other/package", "interrupting rule's friend")
            .put("//current/rule", "part of first set but non-contiguous in iteration order")
            .put("//not/involved/in/any:collisions", "same value")
            .put("//also/not/involved/in/any:collisions", "same value")
            .build()
        val expected: ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                ConversionException::class.java,
                org.junit.function.ThrowingRunnable {
                    BuildType.LABEL_KEYED_STRING_DICT.convert(
                        input,
                        null,
                        converter
                    )
                })
        assertThat(expected)
            .hasMessageThat()
            .isEqualTo(
                ("duplicate labels: //current/rule:rule "
                        + "(as [\":rule\", \"//current/rule:rule\", \"//current/rule\"]), "
                        + "//other/package:package "
                        + "(as [\"//other/package:package\", \"//other/package\"])")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictErrorConvertingMapWithMultipleEquivalentKeysIncludesContext() {
        val converter: LabelConverter =
            LabelConverter(PackageIdentifier.parse("//current/package"), RepositoryMapping.EMPTY)
        val input: MutableMap<String?, String?> = com.google.common.collect.ImmutableMap.Builder<String?, String?>()
            .put(":reference", "value1")
            .put("//current/package:reference", "value2")
            .build()
        val expected: ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                ConversionException::class.java,
                org.junit.function.ThrowingRunnable {
                    BuildType.LABEL_KEYED_STRING_DICT.convert(
                        input,
                        "flag map",
                        converter
                    )
                })
        assertThat(expected)
            .hasMessageThat()
            .isEqualTo(
                "duplicate labels in flag map: //current/package:reference "
                        + "(as [\":reference\", \"//current/package:reference\"])"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictCollectLabels() {
        val input: MutableMap<Label?, String?> =
            com.google.common.collect.ImmutableMap.Builder<Label?, String?>()
                .put(Label.parseCanonical("//absolute:label"), "absolute value")
                .put(Label.parseCanonical("//current/package:relative"), "theory of relativity")
                .put(Label.parseCanonical("//current/package:nocolon"), "colonial times")
                .put(Label.parseCanonical("//current/package:explicit"), "explicit content")
                .put(Label.parseCanonical("//i/was/already/a/label"), "and that's okay")
                .build()

        val expected: com.google.common.collect.ImmutableList<Label?> =
            com.google.common.collect.ImmutableList.of<E?>(
                Label.parseCanonical("//absolute:label"),
                Label.parseCanonical("//current/package:relative"),
                Label.parseCanonical("//current/package:nocolon"),
                Label.parseCanonical("//current/package:explicit"),
                Label.parseCanonical("//i/was/already/a/label")
            )

        TODO(
            """
            |Cannot convert element
            |With text:
            |assertThat(<Map<Label, String>>collectLabels(BuildType.LABEL_KEYED_STRING_DICT, input)
            """.trimMargin()
        )
        containsExactlyElementsIn(expected)
    }


    /**
     * Tests basic [Selector] functionality.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelector() {
        val input: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "//conditions:a", "//a:a",
                "//conditions:b", "//b:b",
                Selector.DEFAULT_CONDITION_KEY, "//d:d"
            )
        val selector: Selector<Label?> = Selector(input, null, labelConverter, BuildType.LABEL)
        assertThat(selector.getOriginalType()).isEqualTo(BuildType.LABEL)

        val expectedMap: MutableMap<Label?, Label?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                Label.parseCanonical("//conditions:a"),
                Label.create("@//a", "a"),
                Label.parseCanonical("//conditions:b"),
                Label.create("@//b", "b"),
                Label.parseCanonical(Selector.DEFAULT_CONDITION_KEY),
                Label.create("@//d", "d")
            )
        assertThat(selector.mapCopy()).isEqualTo(expectedMap)
    }

    /**
     * Tests that creating a [Selector] over a mismatching native type triggers an
     * exception.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelectorWrongType() {
        val input: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "//conditions:a", "not a/../label", "//conditions:b", "also not a/../label",
                BuildType.Selector.DEFAULT_CONDITION_KEY, "whatever"
            )
        val e: ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                ConversionException::class.java,
                org.junit.function.ThrowingRunnable { Selector(input, null, labelConverter, BuildType.LABEL) })
        assertThat(e).hasMessageThat().contains("invalid label 'not a/../label'")
    }

    /** Tests that non-label selector keys trigger an exception.  */
    @org.junit.Test
    fun testSelectorKeyIsNotALabel() {
        val input: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "not a/../label", "//a:a",
                BuildType.Selector.DEFAULT_CONDITION_KEY, "whatever"
            )
        val e: ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                ConversionException::class.java,
                org.junit.function.ThrowingRunnable { Selector(input, null, labelConverter, BuildType.LABEL) })
        assertThat(e).hasMessageThat().contains("invalid label 'not a/../label'")
    }

    /**
     * Tests that [Selector] correctly references its default value.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelectorDefault() {
        val input: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "//conditions:a", "//a:a",
                "//conditions:b", "//b:b",
                BuildType.Selector.DEFAULT_CONDITION_KEY, "//d:d"
            )
        val selector: Selector<Label?> = Selector(input, null, labelConverter, BuildType.LABEL)
        assertThat(selector.hasDefault()).isTrue()
        assertThat(selector.getDefault()).isEqualTo(Label.create("@//d", "d"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelectorDefault_null() {
        val input: com.google.common.collect.ImmutableMap<String?, Any?> =
            com.google.common.collect.ImmutableMap.of<String?, Any?>(
                "//conditions:a", "//a:a", BuildType.Selector.DEFAULT_CONDITION_KEY, Starlark.NONE
            )
        val selector: Selector<Label?> = Selector(input, null, labelConverter, BuildType.LABEL)
        assertThat(selector.hasDefault()).isTrue()
        assertThat(selector.isUnconditional()).isFalse()
        assertThat(selector.getDefault()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelectorDefault_null_singleton() {
        val input: com.google.common.collect.ImmutableMap<String?, Any?> =
            com.google.common.collect.ImmutableMap.of<String?, Any?>(
                BuildType.Selector.DEFAULT_CONDITION_KEY,
                Starlark.NONE
            )
        val selector: Selector<Label?> = Selector(input, null, labelConverter, BuildType.LABEL)
        assertThat(selector.hasDefault()).isTrue()
        assertThat(selector.isUnconditional()).isTrue()
        assertThat(selector.getDefault()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelectorList() {
        val selector1: Any = SelectorValue(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "//conditions:a",
                com.google.common.collect.ImmutableList.of<String?>("//a:a"),
                "//conditions:b",
                com.google.common.collect.ImmutableList.of<String?>("//b:b")
            ), ""
        )
        val selector2: Any = SelectorValue(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "//conditions:c",
                com.google.common.collect.ImmutableList.of<String?>("//c:c"),
                "//conditions:d",
                com.google.common.collect.ImmutableList.of<String?>("//d:d")
            ), ""
        )
        val selectorList: BuildType.SelectorList<MutableList<Label?>?> =
            SelectorList(
                com.google.common.collect.ImmutableList.of<E?>(selector1, selector2),
                null,
                labelConverter,
                BuildType.LABEL_LIST
            )

        assertThat(selectorList.getOriginalType()).isEqualTo(BuildType.LABEL_LIST)
        assertThat(selectorList.getKeyLabels())
            .containsExactly(
                Label.parseCanonical("//conditions:a"),
                Label.parseCanonical("//conditions:b"),
                Label.parseCanonical("//conditions:c"),
                Label.parseCanonical("//conditions:d")
            )

        val selectors: MutableList<Selector<MutableList<Label?>?>?> = selectorList.selectors
        assertThat(selectors.get(0).mapCopy())
            .containsExactly(
                Label.parseCanonical("//conditions:a"),
                com.google.common.collect.ImmutableList.of<E?>(Label.create("@//a", "a")),
                Label.parseCanonical("//conditions:b"),
                com.google.common.collect.ImmutableList.of<E?>(Label.create("@//b", "b"))
            )
        assertThat(selectors.get(1).mapCopy())
            .containsExactly(
                Label.parseCanonical("//conditions:c"),
                com.google.common.collect.ImmutableList.of<E?>(Label.create("@//c", "c")),
                Label.parseCanonical("//conditions:d"),
                com.google.common.collect.ImmutableList.of<E?>(Label.create("@//d", "d"))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelectorDict() {
        val selector1: Any =
            SelectorValue(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "//conditions:a",
                    com.google.common.collect.ImmutableMap.of<String?, String?>("//a:a", "a"),
                    "//conditions:b",
                    com.google.common.collect.ImmutableMap.of<String?, String?>("//b:b", "b")
                ),
                ""
            )
        val selector2: Any =
            SelectorValue(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "//conditions:c",
                    com.google.common.collect.ImmutableMap.of<String?, String?>("//c:c", "c"),
                    "//conditions:d",
                    com.google.common.collect.ImmutableMap.of<String?, String?>("//d:d", "d")
                ),
                ""
            )
        val selectorList: BuildType.SelectorList<MutableMap<Label?, String?>?> =
            SelectorList(
                com.google.common.collect.ImmutableList.of<E?>(selector1, selector2),
                null,
                labelConverter,
                BuildType.LABEL_KEYED_STRING_DICT
            )

        assertThat(selectorList.getOriginalType()).isEqualTo(BuildType.LABEL_KEYED_STRING_DICT)
        assertThat(selectorList.getKeyLabels())
            .containsExactly(
                Label.parseCanonical("//conditions:a"),
                Label.parseCanonical("//conditions:b"),
                Label.parseCanonical("//conditions:c"),
                Label.parseCanonical("//conditions:d")
            )

        val selectors: MutableList<Selector<MutableMap<Label?, String?>?>?> = selectorList.selectors
        assertThat(selectors.get(0).mapCopy())
            .containsExactly(
                Label.parseCanonical("//conditions:a"),
                com.google.common.collect.ImmutableMap.of<K?, V?>(Label.create("@//a", "a"), "a"),
                Label.parseCanonical("//conditions:b"),
                com.google.common.collect.ImmutableMap.of<K?, V?>(Label.create("@//b", "b"), "b")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelectorListMixedTypes() {
        val selector1: Any =
            SelectorValue(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "//conditions:a",
                    com.google.common.collect.ImmutableList.of<String?>("//a:a")
                ), ""
            )
        val selector2: Any =
            SelectorValue(com.google.common.collect.ImmutableMap.of<K?, V?>("//conditions:b", "//b:b"), "")
        val e: ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                ConversionException::class.java,
                org.junit.function.ThrowingRunnable {
                    SelectorList(
                        com.google.common.collect.ImmutableList.of<E?>(selector1, selector2),
                        null,
                        labelConverter,
                        BuildType.LABEL_LIST
                    )
                })
        assertThat(e).hasMessageThat().contains("expected value of type 'list(label)'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelectorList_concatenate_selectorList() {
        val selectorList: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a",
                        com.google.common.collect.ImmutableList.of<String?>("//a:a")
                    ), ""
                )
            )
        val list: MutableList<String?> = com.google.common.collect.ImmutableList.of<String?>("//a:a", "//b:b")

        // Creating a SelectorList from a SelectorList and a list should work properly.
        val result: SelectorList = SelectorList.concat(selectorList, list)
        assertThat(result).isNotNull()
        assertThat(result.getType()).isAssignableTo(MutableList::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelectorList_concatenate_selectorValue() {
        val selectorValue: SelectorValue =
            SelectorValue(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "//conditions:a",
                    com.google.common.collect.ImmutableList.of<String?>("//a:a")
                ), ""
            )
        val list: MutableList<String?> = com.google.common.collect.ImmutableList.of<String?>("//a:a", "//b:b")

        // Creating a SelectorList from a SelectorValue and a list should work properly.
        val result: SelectorList = SelectorList.concat(selectorValue, list)
        assertThat(result).isNotNull()
        assertThat(result.getType()).isAssignableTo(MutableList::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelectorList_concatenate_differentListTypes() {
        val list: MutableList<String?> = com.google.common.collect.ImmutableList.of<String?>("//a:a", "//b:b")
        val arrayList: MutableList<String?> = java.util.ArrayList<String?>()
        arrayList.add("//a:a")

        // Creating a SelectorList from two lists of different types should work properly.
        val result: SelectorList = SelectorList.concat(list, arrayList)
        assertThat(result).isNotNull()
        assertThat(result.getType()).isAssignableTo(MutableList::class.java)
    }

    // to simplify the test
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelectorList_concatenate_differentMappingTypes() {
        val immutableMap: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("a", "//a:a", "b", "//b:b")
        val hashMap: HashMap<String?, String?> = HashMap<String?, String?>()
        hashMap.put("c", "//c:c")

        // Creating a SelectorList from two mappings of different types should work properly.
        val result: SelectorList = SelectorList.concat(immutableMap, hashMap)
        assertThat(result).isNotNull()
        assertThat(result.getType()).isAssignableTo(MutableMap::class.java)
        Truth.assertThat(net.starlark.java.eval.Printer().repr(result, StarlarkSemantics.DEFAULT).toString())
            .isEqualTo("{\"a\": \"//a:a\", \"b\": \"//b:b\"} | {\"c\": \"//c:c\"}")
        val converted: Any? =
            BuildType.selectableConvert(
                Types.STRING_DICT,
                result,
                null,
                labelConverter,  /* simplifyUnconditionalSelects= */
                true
            )
        Truth.assertThat(converted).isInstanceOf(MutableMap::class.java)
        Truth.assertThat(converted as MutableMap<String?, String?>?)
            .containsExactly("a", "//a:a", "b", "//b:b", "c", "//c:c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelectorList_concatenate_invalidType() {
        val list: MutableList<String?> = com.google.common.collect.ImmutableList.of<String?>("//a:a", "//b:b")

        // Creating a SelectorList from a list and a non-list should fail.
        org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
            net.starlark.java.eval.EvalException::class.java,
            org.junit.function.ThrowingRunnable { SelectorList.concat(list, "A string") })
    }

    /**
     * Tests that [BuildType.selectableConvert] returns either the native type or a selector on
     * that type, in accordance with the provided input.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectableConvert_basicUsage() {
        val nativeInput: Any = mutableListOf<String?>("//a:a1", "//a:a2")
        val selectableInput: Any? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a", nativeInput,
                        BuildType.Selector.DEFAULT_CONDITION_KEY, nativeInput
                    ), ""
                )
            )
        val expectedLabels: MutableList<Label?> =
            com.google.common.collect.ImmutableList.of<E?>(Label.create("@//a", "a1"), Label.create("@//a", "a2"))

        // Conversion to direct type:
        var converted: Any? =
            BuildType.selectableConvert(
                BuildType.LABEL_LIST,
                nativeInput,
                null,
                labelConverter,  /* simplifyUnconditionalSelects= */
                false
            )
        Truth.assertThat(converted is MutableList<*>).isTrue()
        Truth.assertThat(converted as MutableList<Label>).containsExactlyElementsIn(expectedLabels)

        // Conversion to selectable type:
        converted =
            BuildType.selectableConvert(
                BuildType.LABEL_LIST,
                selectableInput,
                null,
                labelConverter,  /* simplifyUnconditionalSelects= */
                false
            )
        val selectorList: BuildType.SelectorList<*> = converted as BuildType.SelectorList<*>
        assertThat((selectorList.selectors.get(0) as Selector<Label?>).mapCopy())
            .containsExactly(
                Label.parseCanonical("//conditions:a"),
                expectedLabels,
                Label.parseCanonical(Selector.DEFAULT_CONDITION_KEY),
                expectedLabels
            )
    }

    /**
     * Tests that [BuildType.selectableConvert] with `simplifyUnconditionalSelects=true`
     * returns either the native type or a simplified selector on that type, in accordance with the
     * provided input.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectableConvert_simplifyingUnconditionals() {
        val valueA: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("//a")
        val unconditionalSelectorX: SelectorValue =
            SelectorValue(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    BuildType.Selector.DEFAULT_CONDITION_KEY,
                    com.google.common.collect.ImmutableList.of<String?>("//x")
                ), ""
            )
        val conditionalSelectorYz: SelectorValue =
            SelectorValue(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "//conditions:a",
                    com.google.common.collect.ImmutableList.of<String?>("//y"),
                    BuildType.Selector.DEFAULT_CONDITION_KEY,
                    com.google.common.collect.ImmutableList.of<String?>("//z")
                ),
                ""
            )
        val labelA: Label? = Label.create("@//a", "a")
        val labelX: Label = Label.create("@//x", "x")

        // select({"//conditions:default": ["//x"]}) simplified to ["//x"]
        assertThat(
            BuildType.selectableConvert(
                BuildType.LABEL_LIST,
                SelectorList.of(unconditionalSelectorX),
                null,
                labelConverter,  /* simplifyUnconditionalSelects= */
                true
            )
        )
            .isEqualTo(com.google.common.collect.ImmutableList.of<Any?>(labelX))

        // ["//a"] + select({"//conditions:default": ["//x"]}) simplified to ["//a", "//x"]
        assertThat(
            BuildType.selectableConvert(
                BuildType.LABEL_LIST,
                SelectorList.of(com.google.common.collect.ImmutableList.of<E?>(valueA, unconditionalSelectorX)),
                null,
                labelConverter,  /* simplifyUnconditionalSelects= */
                true
            )
        )
            .isEqualTo(com.google.common.collect.ImmutableList.of<Any?>(labelA, labelX))

        // ["//a"] + select({"//conditions:a": ["//y"], "//conditions:default": ["//z"]}) cannot be
        // simplified
        val unsimplified: Any =
            BuildType.selectableConvert(
                BuildType.LABEL_LIST,
                SelectorList.of(com.google.common.collect.ImmutableList.of<E?>(valueA, conditionalSelectorYz)),
                null,
                labelConverter,  /* simplifyUnconditionalSelects= */
                true
            )
        Truth.assertThat(unsimplified).isInstanceOf(BuildType.SelectorList::class.java)
        assertThat(
            (unsimplified as BuildType.SelectorList<*>).selectors.stream().map(Selector::mapCopy)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        )
            .containsExactlyElementsIn(
                (BuildType.selectableConvert(
                    BuildType.LABEL_LIST,
                    SelectorList.of(com.google.common.collect.ImmutableList.of<E?>(valueA, conditionalSelectorYz)),
                    null,
                    labelConverter,  /* simplifyUnconditionalSelects= */
                    false
                ) as BuildType.SelectorList<*>).selectors.stream().map(Selector::mapCopy)
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectableConvert_simplifyingUnconditionals_handlesUnconditionalNone() {
        val unconditionalSelectorNone: SelectorValue =
            SelectorValue(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    BuildType.Selector.DEFAULT_CONDITION_KEY,
                    Starlark.NONE
                ), ""
            )

        val allBuildTypes: com.google.common.collect.ImmutableList<Type<*>> =
            BuildTypeTestHelper.getAllBuildTypes( /* publicOnly= */false)
        // Verify that we really collected both scalar and non-scalar types from all classes.
        Truth.assertThat(allBuildTypes)
            .containsAtLeast(Type.STRING, Types.STRING_LIST, BuildType.LABEL, BuildType.LABEL_LIST)
        for (type in allBuildTypes) {
            // select({"//conditions:default": None}) simplifies to the type's default value.
            assertThat(
                BuildType.selectableConvert(
                    type,
                    SelectorList.of(unconditionalSelectorNone),
                    null,
                    labelConverter,  /* simplifyUnconditionalSelects= */
                    true
                )
            )
                .isEqualTo(type.getDefaultValue())

            // select({"//conditions:default": None}) + select({"//conditions:default": None}) either
            // simplifies to the type's non-null default value, or cleanly fails to concat.
            if (type.concat(com.google.common.collect.ImmutableList.of<E?>()) != null) {
                val concatenation: Any? =
                    BuildType.selectableConvert(
                        type,
                        SelectorList.of(
                            com.google.common.collect.ImmutableList.of<E?>(
                                unconditionalSelectorNone,
                                unconditionalSelectorNone
                            )
                        ),
                        null,
                        labelConverter,  /* simplifyUnconditionalSelects= */
                        true
                    )
                Truth.assertThat(concatenation).isEqualTo(type.getDefaultValue())
                Truth.assertThat(concatenation).isNotNull()
            } else {
                val exception: ConversionException? =
                    org.junit.Assert.assertThrows<T?>(
                        ConversionException::class.java,
                        org.junit.function.ThrowingRunnable {
                            BuildType.selectableConvert(
                                type,
                                SelectorList.of(
                                    com.google.common.collect.ImmutableList.of<E?>(
                                        unconditionalSelectorNone,
                                        unconditionalSelectorNone
                                    )
                                ),
                                null,
                                labelConverter,  /* simplifyUnconditionalSelects= */
                                true
                            )
                        })
                assertThat(exception).hasMessageThat().contains("doesn't support select concatenation")
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectableConvert_simplifyingUnconditionals_failsCleanlyOnInvalidConcatenation() {
        val exception: ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                ConversionException::class.java,
                org.junit.function.ThrowingRunnable {
                    BuildType.selectableConvert(
                        BuildType.LABEL,
                        SelectorList.of(
                            com.google.common.collect.ImmutableList.of<E?>(
                                "//a",
                                SelectorValue(
                                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                                        "//conditions:default",
                                        "//b"
                                    ), ""
                                )
                            )
                        ),
                        null,
                        labelConverter,  /* simplifyUnconditionalSelects= */
                        true
                    )
                })
        assertThat(exception)
            .hasMessageThat()
            .contains("type 'label' doesn't support select concatenation")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCopyAndLiftStarlarkList() {
        val starlarkList: Any = StarlarkList.immutableOf<String?>("//a:a1", "//a:a2")
        val expectedLabels: com.google.common.collect.ImmutableList<Label?> =
            com.google.common.collect.ImmutableList.of<E?>(Label.create("@//a", "a1"), Label.create("@//a", "a2"))

        val converted: Any? =
            BuildType.copyAndLiftStarlarkValue(
                "ruleClass",
                Attribute.attr("attrName", BuildType.LABEL_LIST).allowedFileTypes().build(),
                starlarkList,
                labelConverter
            )

        Truth.assertThat(converted is StarlarkList<*>).isTrue()
        Truth.assertThat(converted as MutableList<Label>).containsExactlyElementsIn(expectedLabels)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCopyAndLiftStarlarkDict() {
        val inputDict: Any? = Dict.immutableCopyOf<String?, String?>(
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "a",
                "b",
                "c",
                "d"
            )
        )

        val converted: Any? =
            BuildType.copyAndLiftStarlarkValue(
                "ruleClass",
                Attribute.attr("attrName", Types.STRING_DICT).build(),
                inputDict,
                labelConverter
            )

        Truth.assertThat(converted is Dict<*, *>).isTrue()
        Truth.assertThat(converted).isEqualTo(inputDict)
        Truth.assertThat(converted).isNotSameInstanceAs(inputDict)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCopyAndLiftSelectableStarlarkValue() {
        val starlarkList: Any = StarlarkList.immutableOf<String?>("//a:a1", "//a:a2")
        val selectableInput: Any? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a",
                        starlarkList,
                        BuildType.Selector.DEFAULT_CONDITION_KEY,
                        starlarkList
                    ),
                    ""
                )
            )
        val expectedLabels: StarlarkList<Label?> =
            StarlarkList.immutableOf<T?>(Label.create("@//a", "a1"), Label.create("@//a", "a2"))

        val converted: Any? =
            BuildType.copyAndLiftStarlarkValue(
                "ruleClass",
                Attribute.attr("attrName", BuildType.LABEL_LIST).allowedFileTypes().build(),
                selectableInput,
                labelConverter
            )

        Truth.assertThat(converted is SelectorList).isTrue()
        val selectorList: SelectorList = converted as SelectorList
        assertThat((selectorList.elements.get(0) as SelectorValue).getDictionary())
            .containsExactly(
                Label.parseCanonical("//conditions:a"),
                expectedLabels,
                Label.parseCanonical(Selector.DEFAULT_CONDITION_KEY),
                expectedLabels
            )
    }

    /**
     * Tests that [com.google.devtools.build.lib.packages.Type.convert] fails on selector
     * inputs.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConvertDoesNotAcceptSelectables() {
        val selectableInput: Any? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a",
                        mutableListOf<String?>("//a:a1", "//a:a2")
                    ), ""
                )
            )
        val e: ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                ConversionException::class.java,
                org.junit.function.ThrowingRunnable {
                    BuildType.LABEL_LIST.convert(
                        selectableInput,
                        null,
                        labelConverter
                    )
                })
        assertThat(e).hasMessageThat().contains("expected value of type 'list(label)'")
    }

    /** Test for the default condition key label which is not intended to map to an actual target.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultConditionLabel() {
        assertThat(BuildType.Selector.isDefaultConditionLabel(Label.parseCanonical("//condition:a")))
            .isFalse()
        assertThat(
            BuildType.Selector.isDefaultConditionLabel(
                Label.parseCanonical(Selector.DEFAULT_CONDITION_KEY)
            )
        )
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnconditionalSelects() {
        assertThat(
            Selector(
                com.google.common.collect.ImmutableMap.of<K?, V?>("//conditions:a", "//a:a"),
                null,
                labelConverter,
                BuildType.LABEL
            )
                .isUnconditional()
        )
            .isFalse()
        assertThat(
            Selector(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "//conditions:a",
                    "//a:a",
                    BuildType.Selector.DEFAULT_CONDITION_KEY,
                    "//b:b"
                ),
                null,
                labelConverter,
                BuildType.LABEL
            )
                .isUnconditional()
        )
            .isFalse()
        assertThat(
            Selector(
                com.google.common.collect.ImmutableMap.of<K?, V?>(BuildType.Selector.DEFAULT_CONDITION_KEY, "//b:b"),
                null,
                labelConverter,
                BuildType.LABEL
            )
                .isUnconditional()
        )
            .isTrue()
    }

    @org.junit.Test
    fun testSelectorValue_equals() {
        EqualsTester()
            .addEqualityGroup(
                SelectorValue(com.google.common.collect.ImmutableMap.of<K?, V?>("a", 1, "b", 2), ""),
                SelectorValue(com.google.common.collect.ImmutableMap.of<K?, V?>("b", 2, "a", 1), "")
            )
            .addEqualityGroup(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>("a", 1, "b", 2),
                    "Match failed"
                )
            )
            .addEqualityGroup(SelectorValue(com.google.common.collect.ImmutableMap.of<K?, V?>("a", 1, "c", 2), ""))
            .addEqualityGroup(SelectorValue(com.google.common.collect.ImmutableMap.of<K?, V?>("a", 1, "b", 3), ""))
            .testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDict() {
        val input: Any =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "foo",
                java.util.Arrays.asList<T?>(":foo", Label.parseCanonical("//foo:bar")),
                "wiz",
                mutableListOf<String?>("//bang")
            )
        val converted: MutableMap<String?, MutableList<Label?>?>? =
            BuildType.LABEL_LIST_DICT.convert(input, null, labelConverter)
        val expected: com.google.common.collect.ImmutableMap<*, *> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "foo",
                java.util.Arrays.asList<T?>(
                    Label.parseCanonical("//quux:foo"), Label.parseCanonical("//foo:bar")
                ),
                "wiz", java.util.Arrays.asList(Label.parseCanonical("//bang"))
            )
        Truth.assertThat(converted).isEqualTo(expected)
        Truth.assertThat(converted).isNotSameInstanceAs(expected)
        TODO(
            """
            |Cannot convert element
            |With text:
            |assertThat(<Map<String, List<Label>>>collectLabels(BuildType.LABEL_LIST_DICT, converted)
            """.trimMargin()
        )
        containsExactly(
            Label.parseCanonical("//quux:foo"),
            Label.parseCanonical("//foo:bar"),
            Label.parseCanonical("//bang:bang")
        )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDict_concat() {
        assertThat(BuildType.LABEL_LIST_DICT.concat(com.google.common.collect.ImmutableList.of<E?>())).isEmpty()

        val expected: com.google.common.collect.ImmutableMap<String?, MutableList<Label?>?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "foo", java.util.Arrays.asList<T?>(Label.parseCanonical("//foo"), Label.parseCanonical("//bar")),
                "wiz", java.util.Arrays.asList(Label.parseCanonical("//bang"))
            )
        assertThat(BuildType.LABEL_LIST_DICT.concat(com.google.common.collect.ImmutableList.of<E?>(expected))).isEqualTo(
            expected
        )

        val map1: com.google.common.collect.ImmutableMap<String?, MutableList<Label?>?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "foo", java.util.Arrays.asList<T?>(Label.parseCanonical("//a"), Label.parseCanonical("//b")),
                "bar", java.util.Arrays.asList<T?>(Label.parseCanonical("//c"), Label.parseCanonical("//d"))
            )
        val map2: com.google.common.collect.ImmutableMap<String?, MutableList<Label?>?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "bar", java.util.Arrays.asList<T?>(Label.parseCanonical("//x"), Label.parseCanonical("//y")),
                "baz", java.util.Arrays.asList(Label.parseCanonical("//z"))
            )

        val expectedAfterConcat: com.google.common.collect.ImmutableMap<String?, MutableList<Label?>?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "foo", java.util.Arrays.asList<T?>(Label.parseCanonical("//a"), Label.parseCanonical("//b")),
                "bar", java.util.Arrays.asList<T?>(Label.parseCanonical("//x"), Label.parseCanonical("//y")),
                "baz", java.util.Arrays.asList(Label.parseCanonical("//z"))
            )

        assertThat(BuildType.LABEL_LIST_DICT.concat(com.google.common.collect.ImmutableList.of<E?>(map1, map2)))
            .isEqualTo(expectedAfterConcat)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictBadFirstElement() {
        val input: Any =
            com.google.common.collect.ImmutableMap.of<Comparable<out Comparable<*>?>?, MutableList<String?>?>(
                StarlarkInt.of(2), mutableListOf<String?>("foo", "bar"), "wiz", mutableListOf<String?>("bang")
            )
        val e: Type.ConversionException?
        T > org.junit.Assert.assertThrows<T?>(
            Type.ConversionException::class.java,
            org.junit.function.ThrowingRunnable { BuildType.LABEL_LIST_DICT.convert(input, null, labelConverter) })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("expected value of type 'string' for dict key element, but got 2 (int)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictBadSecondElement() {
        val input: Any = com.google.common.collect.ImmutableMap.of<String?, Any?>(
            "foo",
            "bar",
            "wiz",
            mutableListOf<String?>("bang")
        )
        val e: Type.ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { BuildType.LABEL_LIST_DICT.convert(input, null, labelConverter) })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "expected value of type 'list(label)' for dict value element, "
                        + "but got \"bar\" (string)"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictBadElements1() {
        val input: Any = com.google.common.collect.ImmutableMap.of<Comparable<out Comparable<*>?>?, Tuple?>(
            Tuple.of("foo"),
            Tuple.of("bang"),
            "wiz",
            Tuple.of("bang")
        )
        val e: Type.ConversionException?
        T > org.junit.Assert.assertThrows<T?>(
            Type.ConversionException::class.java,
            org.junit.function.ThrowingRunnable { BuildType.LABEL_LIST_DICT.convert(input, null) })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "expected value of type 'string' for dict key element, but got "
                        + "(\"foo\",) (tuple)"
            )
    }

    companion object {
        private fun <T> collectLabels(type: Type<T?>, value: T?): com.google.common.collect.ImmutableList<Label?> {
            val result: com.google.common.collect.ImmutableList.Builder<Label?> =
                com.google.common.collect.ImmutableList.builder<Label?>()
            type.visitLabels({ label, dummy -> result.add(label) }, value,  /*context=*/null)
            return result.build()
        }
    }
}
