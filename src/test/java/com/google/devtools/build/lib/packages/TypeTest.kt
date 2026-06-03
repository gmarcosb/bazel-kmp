// Copyright 2006 The Bazel Authors. All rights reserved.
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

/** Test of type-conversions using Type.  */ // TODO: blaze-team - Rewrite to use TestParameterInjector
@RunWith(JUnit4::class)
class TypeTest {
    private val labelConverter: LabelConverter =
        LabelConverter(PackageIdentifier.createInMainRepo("quux"), RepositoryMapping.EMPTY)

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInteger() {
        val x: Any? = StarlarkInt.of(3)
        assertThat(Type.INTEGER.convert(x, null)).isEqualTo(x)
        Truth.assertThat(collectLabels<Any?>(Type.INTEGER, x)).isEmpty()

        // INTEGER rule attributes must be in signed 32-bit value range.
        // (If we ever relax this, we'll need to audit every place that
        // converts an attribute to an int using toIntUnchecked, since
        // that operation might then fail, and extend the Package
        // serialization protocol to support bigint.)
        val big: StarlarkInt? = StarlarkInt.of(111111111)
        val e: Type.ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { Type.INTEGER.convert(StarlarkInt.multiply(big, big), "param") })
        assertThat(e)
            .hasMessageThat()
            .contains("for param, got 12345678987654321, want value in signed 32-bit range")

        // Ensure that the range of INTEGER.concat is int32.
        assertThat(Type.INTEGER.concat(java.util.Arrays.asList<T?>(StarlarkInt.of(0x7fffffff), StarlarkInt.of(1))))
            .isEqualTo(StarlarkInt.of(--0x80000000))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonInteger() {
        val e: Type.ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { Type.INTEGER.convert("foo", null) })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("expected value of type 'int', but got \"foo\" (string)")
    }

    // Ensure that types are reported correctly.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeErrorMessage() {
        val e: Type.ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { Types.STRING_LIST.convert("[(1,2), 3, 4]", "myexpr") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "expected value of type 'list(string)' for myexpr, "
                        + "but got \"[(1,2), 3, 4]\" (string)"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testString() {
        val s: Any = "foo"
        assertThat(Type.STRING.convert(s, null)).isEqualTo(s)
        Truth.assertThat(collectLabels<Any?>(Type.STRING, s)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonString() {
        val e: Type.ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { Type.STRING.convert(StarlarkInt.of(3), null) })
        assertThat(e).hasMessageThat().isEqualTo("expected value of type 'string', but got 3 (int)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBoolean() {
        val myTrue: Any = true
        val myFalse: Any = false
        assertThat(Type.BOOLEAN.convert(StarlarkInt.of(1), null)).isEqualTo(java.lang.Boolean.TRUE)
        assertThat(Type.BOOLEAN.convert(StarlarkInt.of(0), null)).isEqualTo(java.lang.Boolean.FALSE)
        assertThat(Type.BOOLEAN.convert(true, null)).isTrue()
        assertThat(Type.BOOLEAN.convert(myTrue, null)).isTrue()
        assertThat(Type.BOOLEAN.convert(false, null)).isFalse()
        assertThat(Type.BOOLEAN.convert(myFalse, null)).isFalse()
        Truth.assertThat(collectLabels<Any?>(Type.BOOLEAN, myTrue)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonBoolean() {
        var e: Type.ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { Type.BOOLEAN.convert("unexpected", null) })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("expected one of [False, True, 0, 1], but got \"unexpected\" (string)")
        // Integers other than [0, 1] should fail.
        e =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { Type.BOOLEAN.convert(StarlarkInt.of(2), null) })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("expected one of [False, True, 0, 1], but got 2 (int)")
        e =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { Type.BOOLEAN.convert(StarlarkInt.of(-1), null) })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("expected one of [False, True, 0, 1], but got -1 (int)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTriState() {
        assertThat(BuildType.TRISTATE.convert(StarlarkInt.of(1), null)).isEqualTo(TriState.YES)
        assertThat(BuildType.TRISTATE.convert(StarlarkInt.of(0), null)).isEqualTo(TriState.NO)
        assertThat(BuildType.TRISTATE.convert(StarlarkInt.of(-1), null)).isEqualTo(TriState.AUTO)
        assertThat(BuildType.TRISTATE.convert(TriState.YES, null)).isEqualTo(TriState.YES)
        assertThat(BuildType.TRISTATE.convert(TriState.NO, null)).isEqualTo(TriState.NO)
        assertThat(BuildType.TRISTATE.convert(TriState.AUTO, null)).isEqualTo(TriState.AUTO)
        Truth.assertThat(collectLabels<Any?>(BuildType.TRISTATE, TriState.YES)).isEmpty()

        // deprecated:
        assertThat(BuildType.TRISTATE.convert(true, null)).isEqualTo(TriState.YES)
        assertThat(BuildType.TRISTATE.convert(false, null)).isEqualTo(TriState.NO)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTriStateDoesNotAcceptArbitraryIntegers() {
        for (i in com.google.common.collect.Lists.newArrayList<Int>(2, 3, -5, -2, 20)) {
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { BuildType.TRISTATE.convert(StarlarkInt.of(i), null) })
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTriStateDoesNotAcceptStrings() {
        val listOfCases: MutableList<*> =
            com.google.common.collect.Lists.newArrayList<String?>("bad", "true", "auto", "false")
        // TODO(adonovan): add booleans true, false to this list; see b/116691720.
        for (entry in listOfCases) {
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { BuildType.TRISTATE.convert(entry, null) })
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTagConversion() {
        assertThat(Type.BOOLEAN.toTagSet(true, "attribute"))
            .containsExactlyElementsIn(com.google.common.collect.Sets.< E > newHashSet < E ? > ("attribute"))
        assertThat(Type.BOOLEAN.toTagSet(false, "attribute"))
            .containsExactlyElementsIn(com.google.common.collect.Sets.< E > newHashSet < E ? > ("noattribute"))

        assertThat(Type.STRING.toTagSet("whiskey", "preferred_cocktail"))
            .containsExactlyElementsIn(com.google.common.collect.Sets.< E > newHashSet < E ? > ("whiskey"))

        assertThat(
            Types.STRING_LIST.toTagSet(
                com.google.common.collect.Lists.newArrayList<E?>("cheddar", "ementaler", "gruyere"), "cheeses"
            )
        )
            .containsExactlyElementsIn(com.google.common.collect.Sets.newHashSet<E?>("cheddar", "ementaler", "gruyere"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIllegalTagConversionByType() {
        org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
            java.lang.UnsupportedOperationException::class.java,
            org.junit.function.ThrowingRunnable { BuildType.TRISTATE.toTagSet(TriState.AUTO, "some_tristate") })
        org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
            java.lang.UnsupportedOperationException::class.java,
            org.junit.function.ThrowingRunnable { BuildType.LICENSE.toTagSet(License.NO_LICENSE, "output_license") })
    }

    @org.junit.Test
    fun testIllegalTagConversIonFromNullOnSupportedType() {
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { Type.BOOLEAN.toTagSet(null, "a_boolean") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabel() {
        val label: Label? = Label.parseCanonical("//foo:bar")
        assertThat(BuildType.LABEL.convert("//foo:bar", null, labelConverter)).isEqualTo(label)
        Truth.assertThat(collectLabels<Any?>(BuildType.LABEL, label)).containsExactly(label)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNodepLabel() {
        val label: Label? = Label.parseCanonical("//foo:bar")
        assertThat(BuildType.NODEP_LABEL.convert("//foo:bar", null, labelConverter)).isEqualTo(label)
        Truth.assertThat(collectLabels<Any?>(BuildType.NODEP_LABEL, label)).containsExactly(label)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativeLabel() {
        assertThat(BuildType.LABEL.convert(":wiz", null, labelConverter))
            .isEqualTo(Label.parseCanonical("//quux:wiz"))
        assertThat(BuildType.LABEL.convert("wiz", null, labelConverter))
            .isEqualTo(Label.parseCanonical("//quux:wiz"))
        org.junit.Assert.assertThrows<T?>(
            Type.ConversionException::class.java,
            org.junit.function.ThrowingRunnable { BuildType.LABEL.convert("wiz", null) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidLabel() {
        val e: Type.ConversionException =
            org.junit.Assert.assertThrows<T>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { BuildType.LABEL.convert("not//a label", null, labelConverter) })
        MoreAsserts.assertContainsWordsWithQuotes(e.getMessage(), "not//a label")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonLabel() {
        val e: Type.ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { BuildType.LABEL.convert(StarlarkInt.of(3), null) })
        assertThat(e).hasMessageThat().isEqualTo("expected value of type 'string', but got 3 (int)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringList() {
        val input: Any = mutableListOf<String?>("foo", "bar", "wiz")
        val converted: MutableList<String?>? = Types.STRING_LIST.convert(input, null)
        Truth.assertThat(converted).isEqualTo(input)
        Truth.assertThat(converted).isNotSameInstanceAs(input)
        Truth.assertThat(collectLabels<Any?>(Types.STRING_LIST, input)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringDict() {
        val input: Any = com.google.common.collect.ImmutableMap.of<String?, String?>("foo", "bar", "wiz", "bang")
        val converted: MutableMap<String?, String?>? = Types.STRING_DICT.convert(input, null)
        Truth.assertThat(converted).isEqualTo(input)
        Truth.assertThat(converted).isNotSameInstanceAs(input)
        Truth.assertThat(collectLabels<Any?>(Types.STRING_DICT, converted)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringDictBadElements() {
        val input: Any = com.google.common.collect.ImmutableMap.of<String?, Comparable<out Comparable<*>?>?>(
            "foo",
            StarlarkList.of<String?>(null, "bar", "baz"),
            "wiz",
            "bang"
        )
        val e: Type.ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { Types.STRING_DICT.convert(input, null) })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "expected value of type 'string' for dict value element, "
                        + "but got [\"bar\", \"baz\"] (list)"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonStringList() {
        val e: Type.ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { Types.STRING_LIST.convert(StarlarkInt.of(3), "blah") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("expected value of type 'list(string)' for blah, but got 3 (int)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringListBadElements() {
        val input: Any = java.util.Arrays.asList<Any?>("foo", "bar", StarlarkInt.of(1))
        val e: Type.ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { Types.STRING_LIST.convert(input, "argument quux") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "expected value of type 'string' for element 2 of argument quux, but got 1 (int)"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListDepsetConversion() {
        val input: Any? =
            Depset.of(String::class.java, NestedSetBuilder.create(Order.STABLE_ORDER, "a", "b", "c"))
        Types.STRING_LIST.convert(input, null)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelList() {
        val input: Any = mutableListOf<String?>("//foo:bar", ":wiz")
        val converted: MutableList<Label?>? = BuildType.LABEL_LIST.convert(input, null, labelConverter)
        val expected: MutableList<Label?> =
            java.util.Arrays.asList<T?>(Label.parseCanonical("//foo:bar"), Label.parseCanonical("//quux:wiz"))
        Truth.assertThat(converted).isEqualTo(expected)
        Truth.assertThat(converted).isNotSameInstanceAs(expected)
        Truth.assertThat(collectLabels<Any?>(BuildType.LABEL_LIST, converted)).containsExactlyElementsIn(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonLabelList() {
        val e: Type.ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable {
                    BuildType.LABEL_LIST.convert(
                        StarlarkInt.of(3),
                        "foo",
                        labelConverter
                    )
                })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("expected value of type 'list(label)' for foo, but got 3 (int)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListBadElements() {
        val list: Any = java.util.Arrays.asList<Any?>("//foo:bar", StarlarkInt.of(2), "foo")
        val e: Type.ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { BuildType.LABEL_LIST.convert(list, null, labelConverter) })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("expected value of type 'string' for element 1 of null, but got 2 (int)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListSyntaxError() {
        val list: Any = mutableListOf<Any?>("//foo:bar/..", "foo")
        val e: Type.ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { BuildType.LABEL_LIST.convert(list, "myexpr", labelConverter) })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                ("invalid label '//foo:bar/..' in element 0 of myexpr: "
                        + "invalid target name 'bar/..': "
                        + "target names may not contain up-level references '..'")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringListDict() {
        val input: Any =
            com.google.common.collect.ImmutableMap.of<String?, MutableList<String?>?>(
                "foo",
                mutableListOf<String?>("foo", "bar"),
                "wiz",
                mutableListOf<String?>("bang")
            )
        val converted: MutableMap<String?, MutableList<String?>?>? =
            Types.STRING_LIST_DICT.convert(input, null, labelConverter)
        val expected: MutableMap<*, *> =
            com.google.common.collect.ImmutableMap.of<String?, MutableList<String?>?>(
                "foo", mutableListOf<String?>("foo", "bar"),
                "wiz", mutableListOf<String?>("bang")
            )
        Truth.assertThat(converted).isEqualTo(expected)
        Truth.assertThat(converted).isNotSameInstanceAs(expected)
        Truth.assertThat(collectLabels<Any?>(Types.STRING_LIST_DICT, converted)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringListDict_concat() {
        assertThat(Types.STRING_LIST_DICT.concat(com.google.common.collect.ImmutableList.of<E?>())).isEmpty()

        val expected: com.google.common.collect.ImmutableMap<String?, MutableList<String?>?> =
            com.google.common.collect.ImmutableMap.of<String?, MutableList<String?>?>(
                "foo", mutableListOf<String?>("foo", "bar"),
                "wiz", mutableListOf<String?>("bang")
            )
        assertThat(Types.STRING_LIST_DICT.concat(com.google.common.collect.ImmutableList.of<E?>(expected))).isEqualTo(
            expected
        )

        val map1: com.google.common.collect.ImmutableMap<String?, MutableList<String?>?> =
            com.google.common.collect.ImmutableMap.of<String?, MutableList<String?>?>(
                "foo", mutableListOf<String?>("a", "b"),
                "bar", mutableListOf<String?>("c", "d")
            )
        val map2: com.google.common.collect.ImmutableMap<String?, MutableList<String?>?> =
            com.google.common.collect.ImmutableMap.of<String?, MutableList<String?>?>(
                "bar", mutableListOf<String?>("x", "y"),
                "baz", mutableListOf<String?>("z")
            )

        val expectedAfterConcat: com.google.common.collect.ImmutableMap<String?, MutableList<String?>?> =
            com.google.common.collect.ImmutableMap.of<String?, MutableList<String?>?>(
                "foo", mutableListOf<String?>("a", "b"),
                "bar", mutableListOf<String?>("x", "y"),
                "baz", mutableListOf<String?>("z")
            )

        assertThat(Types.STRING_LIST_DICT.concat(com.google.common.collect.ImmutableList.of<E?>(map1, map2)))
            .isEqualTo(expectedAfterConcat)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringListDictBadFirstElement() {
        val input: Any =
            com.google.common.collect.ImmutableMap.of<Comparable<out Comparable<*>?>?, MutableList<String?>?>(
                StarlarkInt.of(2), mutableListOf<String?>("foo", "bar"), "wiz", mutableListOf<String?>("bang")
            )
        val e: Type.ConversionException?
        T > org.junit.Assert.assertThrows<T?>(
            Type.ConversionException::class.java,
            org.junit.function.ThrowingRunnable { Types.STRING_LIST_DICT.convert(input, null, labelConverter) })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("expected value of type 'string' for dict key element, but got 2 (int)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringListDictBadSecondElement() {
        val input: Any = com.google.common.collect.ImmutableMap.of<String?, Any?>(
            "foo",
            "bar",
            "wiz",
            mutableListOf<String?>("bang")
        )
        val e: Type.ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { Types.STRING_LIST_DICT.convert(input, null, labelConverter) })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "expected value of type 'list(string)' for dict value element, "
                        + "but got \"bar\" (string)"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringListDictBadElements1() {
        val input: Any = com.google.common.collect.ImmutableMap.of<Comparable<out Comparable<*>?>?, Tuple?>(
            Tuple.of("foo"),
            Tuple.of("bang"),
            "wiz",
            Tuple.of("bang")
        )
        val e: Type.ConversionException?
        T > org.junit.Assert.assertThrows<T?>(
            Type.ConversionException::class.java,
            org.junit.function.ThrowingRunnable { Types.STRING_LIST_DICT.convert(input, null) })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "expected value of type 'string' for dict key element, but got "
                        + "(\"foo\",) (tuple)"
            )
    }

    @org.junit.Test
    fun testStringDictThrowsConversionException() {
        val e: Type.ConversionException? =
            org.junit.Assert.assertThrows<T?>(
                Type.ConversionException::class.java,
                org.junit.function.ThrowingRunnable { Types.STRING_DICT.convert("some string", null) })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "expected value of type 'dict(string, string)', but got \"some string\" (string)"
            )
    }

    companion object {
        private fun <T> collectLabels(type: Type<T?>, value: Any?): com.google.common.collect.ImmutableList<Label?> {
            val result: com.google.common.collect.ImmutableList.Builder<Label?> =
                com.google.common.collect.ImmutableList.builder<Label?>()
            type.visitLabels({ label, dummy -> result.add(label) }, type.cast(value),  /*context=*/null)
            return result.build()
        }
    }
}
