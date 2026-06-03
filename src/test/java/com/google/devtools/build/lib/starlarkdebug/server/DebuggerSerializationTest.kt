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
package com.google.devtools.build.lib.starlarkdebug.server

import com.google.devtools.build.lib.collect.nestedset.Depset

/** Unit tests for [DebuggerSerialization].  */
@RunWith(JUnit4::class)
class DebuggerSerializationTest {
    private val dummyObjectMap: ThreadObjectMap = ThreadObjectMap()

    /**
     * Returns the [Value] proto message corresponding to the given object and label. Subsequent
     * calls may return values with different IDs.
     */
    private fun getValueProto(label: String?, value: Any): Value {
        return DebuggerSerialization.getValueProto(dummyObjectMap, label, value)
    }

    private fun getChildren(value: Value): com.google.common.collect.ImmutableList<Value?> {
        val `object`: Any? = dummyObjectMap.getValue(value.getId())
        return if (`object` != null)
            DebuggerSerialization.getChildren(dummyObjectMap, `object`)
        else
            com.google.common.collect.ImmutableList.of<Value?>()
    }

    @org.junit.Test
    fun testSimpleNestedSet() {
        val children: MutableSet<String?> = com.google.common.collect.ImmutableSet.of<String?>("a", "b")
        val set: Depset =
            Depset.of(
                String::class.java,
                NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().addAll(children).build()
            )

        val value: Value = getValueProto("name", set)

        assertTypeAndDescription(set, value)
        assertThat(value.getHasChildren()).isTrue()
        assertThat(value.getLabel()).isEqualTo("name")

        val childValues: MutableList<Value?> = getChildren(value)

        assertThat(childValues.get(0))
            .isEqualTo(
                Value.newBuilder()
                    .setLabel("order")
                    .setType("string")
                    .setDescription("default")
                    .build()
            )
        assertEqualIgnoringTypeDescriptionAndId(childValues.get(1), getValueProto("directs", children))
        assertEqualIgnoringTypeDescriptionAndId(
            childValues.get(2), getValueProto("transitives", com.google.common.collect.ImmutableList.of<Any?>())
        )
    }

    @org.junit.Test
    fun testNestedSetWithNestedChildren() {
        val innerNestedSet: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("inner1").add("inner2").build()
        val directChildren: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>("a", "b")
        val outerSet: Depset =
            Depset.of(
                String::class.java,
                NestedSetBuilder.< String > linkOrder < kotlin . String ? > ()
                    .addAll(directChildren)
                    .addTransitive(innerNestedSet)
                    .build()
            )

        val value: Value = getValueProto("name", outerSet)
        val childValues: MutableList<Value?> = getChildren(value)

        assertTypeAndDescription(outerSet, value)
        Truth.assertThat(childValues).hasSize(3)
        assertThat(childValues.get(0))
            .isEqualTo(
                Value.newBuilder()
                    .setLabel("order")
                    .setType("string")
                    .setDescription("topological")
                    .build()
            )
        assertEqualIgnoringTypeDescriptionAndId(
            childValues.get(1), getValueProto("directs", directChildren)
        )
        assertEqualIgnoringTypeDescriptionAndId(
            childValues.get(2),
            getValueProto("transitives", com.google.common.collect.ImmutableList.of<Any?>(innerNestedSet))
        )
    }

    @org.junit.Test
    fun testSimpleMap() {
        val map: MutableMap<String?, Int?> = com.google.common.collect.ImmutableMap.of<String?, Int?>("a", 1, "b", 2)

        val value: Value = getValueProto("name", map)
        val childValues: MutableList<Value?> = getChildren(value)

        assertTypeAndDescription(map, value)
        Truth.assertThat(childValues).hasSize(2)
        assertThat(childValues.get(0).getLabel()).isEqualTo("[0]")
        Truth.assertThat(getChildren(childValues.get(0)))
            .isEqualTo(
                com.google.common.collect.ImmutableList.of<Any?>(
                    getValueProto("key", "a"),
                    getValueProto("value", 1)
                )
            )
        assertThat(childValues.get(1).getLabel()).isEqualTo("[1]")
        Truth.assertThat(getChildren(childValues.get(1)))
            .isEqualTo(
                com.google.common.collect.ImmutableList.of<Any?>(
                    getValueProto("key", "b"),
                    getValueProto("value", 2)
                )
            )
    }

    @org.junit.Test
    fun testNestedMap() {
        val set: MutableSet<String?> = com.google.common.collect.ImmutableSet.of<String?>("a", "b")
        val map: MutableMap<String?, Any?> = com.google.common.collect.ImmutableMap.of<String?, Any?>("a", set)

        val value: Value = getValueProto("name", map)
        val childValues: MutableList<Value?> = getChildren(value)

        assertTypeAndDescription(map, value)
        Truth.assertThat(childValues).hasSize(1)
        assertThat(childValues.get(0).getLabel()).isEqualTo("[0]")
        Truth.assertThat(clearIds(getChildren(childValues.get(0))))
            .isEqualTo(
                com.google.common.collect.ImmutableList.of<Any?>(
                    getValueProto("key", "a"),
                    clearId(getValueProto("value", set))
                )
            )
    }

    @org.junit.Test
    fun testSimpleIterable() {
        val iter: Iterable<Int?> = com.google.common.collect.ImmutableList.of<Int?>(1, 2)

        val value: Value = getValueProto("name", iter)
        val childValues: MutableList<Value?> = getChildren(value)

        assertTypeAndDescription(iter, value)
        Truth.assertThat(childValues).hasSize(2)
        assertThat(childValues.get(0)).isEqualTo(getValueProto("[0]", 1))
        assertThat(childValues.get(1)).isEqualTo(getValueProto("[1]", 2))
    }

    @org.junit.Test
    fun testNestedIterable() {
        val iter: Iterable<Any?> =
            com.google.common.collect.ImmutableList.of<Any?>(com.google.common.collect.ImmutableList.of<Int?>(1, 2))

        val value: Value = getValueProto("name", iter)
        val childValues: MutableList<Value?> = getChildren(value)

        assertTypeAndDescription(iter, value)
        Truth.assertThat(childValues).hasSize(1)
        assertValuesEqualIgnoringId(
            childValues.get(0),
            getValueProto("[0]", com.google.common.collect.ImmutableList.of<Int?>(1, 2))
        )
    }

    @org.junit.Test
    fun testSimpleArray() {
        val array = intArrayOf(1, 2)

        val value: Value = getValueProto("name", array)
        val childValues: MutableList<Value?> = getChildren(value)

        assertTypeAndDescription(array, value)
        Truth.assertThat(childValues).hasSize(2)
        assertThat(childValues.get(0)).isEqualTo(getValueProto("[0]", 1))
        assertThat(childValues.get(1)).isEqualTo(getValueProto("[1]", 2))
    }

    @org.junit.Test
    fun testNestedArray() {
        val array: Array<Any?> = arrayOf<Any>(1, com.google.common.collect.ImmutableList.of<Int?>(2, 3))

        val value: Value = getValueProto("name", array)
        val childValues: MutableList<Value?> = getChildren(value)

        assertTypeAndDescription(array, value)
        Truth.assertThat(childValues).hasSize(2)
        assertThat(childValues.get(0)).isEqualTo(getValueProto("[0]", 1))
        assertValuesEqualIgnoringId(
            childValues.get(1),
            getValueProto("[1]", com.google.common.collect.ImmutableList.of<Int?>(2, 3))
        )
    }

    @org.junit.Test
    fun testUnrecognizedObjectOrStarlarkPrimitiveHasNoChildren() {
        assertThat(getValueProto("name", 1).getHasChildren()).isFalse()
        assertThat(getValueProto("name", "string").getHasChildren()).isFalse()
        assertThat(getValueProto("name", Any()).getHasChildren()).isFalse()
    }

    @org.junit.Test
    fun testStarlarkValue() {
        val dummy = DummyType()

        val value: Value = getValueProto("name", dummy)
        assertTypeAndDescription(dummy, value)
        Truth.assertThat(getChildren(value)).containsExactly(getValueProto("bool", true))
    }

    private class DummyType : StarlarkValue {
        public override fun repr(printer: Printer, semantics: StarlarkSemantics?) {
            printer.append("DummyType")
        }

        @StarlarkMethod(name = "bool", doc = "Returns True", structField = true)
        fun bool(): Boolean {
            return true
        }
    }

    @org.junit.Test
    fun testSkipStarlarkCallableThrowingException() {
        val dummy = DummyTypeWithException()

        val value: Value = getValueProto("name", dummy)
        assertTypeAndDescription(dummy, value)
        Truth.assertThat(getChildren(value)).containsExactly(getValueProto("bool", true))
    }

    private class DummyTypeWithException : StarlarkValue {
        public override fun repr(printer: Printer, semantics: StarlarkSemantics?) {
            printer.append("DummyTypeWithException")
        }

        @StarlarkMethod(name = "bool", doc = "Returns True", structField = true)
        fun bool(): Boolean {
            return true
        }

        @StarlarkMethod(name = "invalid", doc = "Throws exception!", structField = true)
        fun invalid(): Boolean {
            throw java.lang.IllegalArgumentException()
        }
    }

    // Type, description, and ID are implementation dependent.
    private fun assertEqualIgnoringTypeDescriptionAndId(value1: Value, value2: Value) {
        assertThat(value1.getLabel()).isEqualTo(value2.getLabel())

        val children1: MutableList<Value?> = getChildren(value1)
        val children2: MutableList<Value?> = getChildren(value2)

        Truth.assertThat(children1).hasSize(children2.size)
        for (i in children1.indices) {
            assertEqualIgnoringTypeDescriptionAndId(children1.get(i), children2.get(i))
        }
    }

    private fun assertValuesEqualIgnoringId(value1: Value, value2: Value) {
        assertThat(clearId(value1)).isEqualTo(clearId(value2))
    }

    private fun clearId(value: Value): Value {
        return value.toBuilder().clearId().build()
    }

    private fun clearIds(values: MutableList<Value?>): MutableList<Value?> {
        return values.stream().map<Value?> { value: Value? -> this.clearId(value) }.collect(Collectors.toList())
    }

    companion object {
        private fun assertTypeAndDescription(`object`: Any?, value: Value) {
            assertThat(value.getType()).isEqualTo(Starlark.type(`object`))
            assertThat(value.getDescription()).isEqualTo(Starlark.repr(`object`, StarlarkSemantics.DEFAULT))
        }
    }
}
