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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.DynamicCodec.FieldHandler

/** Tests for [DynamicCodec].  */
@RunWith(JUnit4::class)
class DynamicCodecTest {
    private open class SimpleExample(private val elt: String?, private val elt2: String?, private val x: Int) {
        override fun equals(other: Any?): Boolean {
            if (other !is SimpleExample) {
                return false
            }
            return elt == other.elt && elt2 == other.elt2 && x == other.x
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExample() {
        SerializationTester(SimpleExample("a", "b", -5), SimpleExample("a", null, 10))
            .addCodec(DynamicCodec(SimpleExample::class.java))
            .makeMemoizing()
            .runTests()
    }

    private class ExampleSubclass(
        elt1: String?, elt2: String?, // duplicate name with superclass
        private val elt: String?, x: Int
    ) : SimpleExample(elt1, elt2, x) {
        override fun equals(other: Any?): Boolean {
            if (other !is ExampleSubclass) {
                return false
            }
            if (!super.equals(other)) {
                return false
            }
            return elt == other.elt
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExampleSubclass() {
        SerializationTester(
            ExampleSubclass("a", "b", "c", 0), ExampleSubclass("a", null, null, 15)
        )
            .addCodec(DynamicCodec(ExampleSubclass::class.java))
            .makeMemoizing()
            .runTests()
    }

    private class ExampleSmallPrimitives(
        private val bit: Boolean,
        private val b: Byte,
        private val s: Short,
        private val c: Char
    ) {
        private val v: java.lang.Void? = null

        override fun equals(other: Any?): Boolean {
            if (other !is ExampleSmallPrimitives) {
                return false
            }
            return v == other.v && bit == other.bit && b == other.b && s == other.s && c == other.c
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExampleSmallPrimitives() {
        SerializationTester(
            ExampleSmallPrimitives(false, 0.toByte(), 0.toShort(), 'a'),
            ExampleSmallPrimitives(false, 120.toByte(), 18000.toShort(), 'x'),
            ExampleSmallPrimitives(
                true,
                java.lang.Byte.MIN_VALUE,
                java.lang.Short.MIN_VALUE,
                java.lang.Character.MIN_VALUE
            ),
            ExampleSmallPrimitives(
                true,
                java.lang.Byte.MAX_VALUE,
                java.lang.Short.MAX_VALUE,
                java.lang.Character.MAX_VALUE
            )
        )
            .addCodec(DynamicCodec(ExampleSmallPrimitives::class.java))
            .makeMemoizing()
            .runTests()
    }

    private class ExampleMediumPrimitives(private val i: Int, private val f: Float) {
        override fun equals(other: Any?): Boolean {
            if (other !is ExampleMediumPrimitives) {
                return false
            }
            return i == other.i && f == other.f
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExampleMediumPrimitives() {
        SerializationTester(
            ExampleMediumPrimitives(12345, 1e12f),
            ExampleMediumPrimitives(67890, -6e9f),
            ExampleMediumPrimitives(java.lang.Integer.MIN_VALUE, java.lang.Float.MIN_VALUE),
            ExampleMediumPrimitives(java.lang.Integer.MAX_VALUE, java.lang.Float.MAX_VALUE)
        )
            .addCodec(DynamicCodec(ExampleMediumPrimitives::class.java))
            .makeMemoizing()
            .runTests()
    }

    private class ExampleLargePrimitives(private val l: Long, private val d: Double) {
        override fun equals(other: Any?): Boolean {
            if (other !is ExampleLargePrimitives) {
                return false
            }
            return l == other.l && d == other.d
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExampleLargePrimitives() {
        SerializationTester(
            ExampleLargePrimitives(12345346523453L, 1e300),
            ExampleLargePrimitives(678900093045L, -9e180),
            ExampleLargePrimitives(java.lang.Long.MIN_VALUE, java.lang.Double.MIN_VALUE),
            ExampleLargePrimitives(java.lang.Long.MAX_VALUE, java.lang.Double.MAX_VALUE)
        )
            .addCodec(DynamicCodec(ExampleLargePrimitives::class.java))
            .makeMemoizing()
            .runTests()
    }

    private class ArrayExample(
        var text: Array<String?>?,
        var numbers: ByteArray?,
        var chars: CharArray?,
        var longs: LongArray?
    ) {
        override fun equals(other: Any?): Boolean {
            if (other !is ArrayExample) {
                return false
            }
            return java.util.Arrays.equals(text, other.text)
                    && java.util.Arrays.equals(numbers, other.numbers)
                    && java.util.Arrays.equals(chars, other.chars)
                    && java.util.Arrays.equals(longs, other.longs)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArray() {
        SerializationTester(
            ArrayExample(null, null, null, null),
            ArrayExample(arrayOf<String?>(), byteArrayOf(), charArrayOf(), longArrayOf()),
            ArrayExample(
                arrayOf<String>("a", "b", "cde"),
                byteArrayOf(-1, 0, 1),
                charArrayOf('a', 'b', 'c', 'x', 'y', 'z'),
                longArrayOf(java.lang.Long.MAX_VALUE, java.lang.Long.MIN_VALUE, 27983741982341L, 52893748523495834L)
            )
        )
            .addCodec(DynamicCodec(ArrayExample::class.java))
            .runTests()
    }

    private class NestedArrayExample(var numbers: Array<IntArray?>?) {
        override fun equals(other: Any?): Boolean {
            if (other !is NestedArrayExample) {
                return false
            }
            return java.util.Arrays.deepEquals(numbers, other.numbers)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNestedArray() {
        SerializationTester(
            NestedArrayExample(null),
            NestedArrayExample(
                arrayOf<IntArray?>(
                    intArrayOf(1, 2, 3),
                    intArrayOf(4, 5, 6, 9),
                    intArrayOf(7)
                )
            ),
            NestedArrayExample(arrayOf<IntArray?>(intArrayOf(1, 2, 3), null, intArrayOf(7)))
        )
            .addCodec(DynamicCodec(NestedArrayExample::class.java))
            .runTests()
    }

    private class CycleA(private val value: Int) {
        private var b: CycleB? = null

        override fun equals(other: Any?): Boolean {
            // Integrity check. Not really part of equals.
            Truth.assertThat(b.a).isEqualTo(this)
            if (other !is CycleA) {
                return false
            }
            // Consistency check. Not really part of equals.
            Truth.assertThat(other.b.a).isEqualTo(other)
            return value == other.value && b!!.value() == other.b.value
        }
    }

    private class CycleB(private val value: Int) {
        private var a: CycleA? = null

        fun value(): Int {
            return value
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCyclic() {
        SerializationTester(createCycle(1, 2), createCycle(3, 4))
            .addCodec(DynamicCodec(CycleA::class.java))
            .addCodec(DynamicCodec(CycleB::class.java))
            .makeMemoizing()
            .runTests()
    }

    internal enum class EnumExample {
        ZERO,
        ONE,
        TWO,
        THREE
    }

    internal class PrimitiveExample(
        private val booleanValue: Boolean,
        private val intValue: Int,
        private val doubleValue: Double,
        private val enumValue: EnumExample?,
        private val stringValue: String?
    ) {
        override fun equals(`object`: Any?): Boolean {
            if (`object` !is PrimitiveExample) {
                return false
            }
            return booleanValue == `object`.booleanValue && intValue == `object`.intValue && doubleValue == `object`.doubleValue && enumValue == `object`.enumValue
                    && stringValue == `object`.stringValue
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrimitiveExample() {
        SerializationTester(
            PrimitiveExample(true, 1, 1.1, EnumExample.ZERO, "foo"),
            PrimitiveExample(false, -1, -5.5, EnumExample.ONE, "bar"),
            PrimitiveExample(true, 5, 20.0, EnumExample.THREE, null),
            PrimitiveExample(true, 100, 100.0, null, "hello")
        )
            .addCodec(DynamicCodec(PrimitiveExample::class.java))
            .addCodec(EnumCodec(EnumExample::class.java))
            .setRepetitions(100000)
            .runTests()
    }

    private class NoCodecExample2 {
        @Suppress("unused")
        private val noCodec: BufferedInputStream = BufferedInputStream(null)
    }

    private class NoCodecExample1 {
        @Suppress("unused")
        private val noCodec = NoCodecExample2()
    }

    @org.junit.Test
    fun testNoCodecExample() {
        val codecs: ObjectCodecs =
            ObjectCodecs(AutoRegistry.get(), com.google.common.collect.ImmutableClassToInstanceMap.of<B?>())
        val expected: SerializationException.NoCodecException? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException.NoCodecException::class.java,
                org.junit.function.ThrowingRunnable { codecs.serializeMemoized(NoCodecExample1()) })
        assertThat(expected)
            .hasMessageThat()
            .contains(
                ("java.io.BufferedInputStream ["
                        + "java.io.BufferedInputStream, "
                        + "com.google.devtools.build.lib.skyframe.serialization."
                        + "DynamicCodecTest\$NoCodecExample2, "
                        + "com.google.devtools.build.lib.skyframe.serialization."
                        + "DynamicCodecTest\$NoCodecExample1]")
            )
    }

    private class SpecificObject

    private class SpecificObjectWrapper(@field:Suppress("unused") private val field: SpecificObject?)

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun overGeneralCodec() {
        // Class must be hidden from other tests.
        class OverGeneralCodec : ObjectCodec<Any?> {
            val encodedClass: java.lang.Class<*>
                get() = Any::class.java

            public override fun serialize(context: SerializationContext?, obj: Any?, codedOut: CodedOutputStream?) {}

            public override fun deserialize(context: DeserializationContext?, codedIn: CodedInputStream?): Any {
                return Any()
            }
        }

        val registry: ObjectCodecRegistry? =
            ObjectCodecRegistry.newBuilder()
                .add(DynamicCodec(SpecificObjectWrapper::class.java))
                .add(OverGeneralCodec())
                .build()
        val codecs: ObjectCodecs = ObjectCodecs(registry)
        val bytes: ByteString? = codecs.serializeMemoized(SpecificObjectWrapper(SpecificObject()))
        val expected: SerializationException? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable { codecs.deserializeMemoized(bytes) })
        assertThat(expected)
            .hasMessageThat()
            .contains(
                ("was not instance of class "
                        + "com.google.devtools.build.lib.skyframe.serialization."
                        + "DynamicCodecTest\$SpecificObject")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun overGeneralCodecOkWhenNull() {
        // Class must be hidden from other tests.
        class OverGeneralCodec : ObjectCodec<Any?> {
            val encodedClass: java.lang.Class<*>
                get() = Any::class.java

            public override fun serialize(context: SerializationContext?, obj: Any?, codedOut: CodedOutputStream?) {}

            public override fun deserialize(context: DeserializationContext?, codedIn: CodedInputStream?): Any {
                return Any()
            }
        }

        val registry: ObjectCodecRegistry? =
            ObjectCodecRegistry.newBuilder()
                .add(DynamicCodec(SpecificObjectWrapper::class.java))
                .add(OverGeneralCodec())
                .build()
        val codecs: ObjectCodecs = ObjectCodecs(registry)
        val bytes: ByteString? = codecs.serializeMemoized(SpecificObjectWrapper(null))
        val deserialized: Any = codecs.deserializeMemoized(bytes)
        Truth.assertThat(deserialized).isInstanceOf(SpecificObjectWrapper::class.java)
        Truth.assertThat((deserialized as SpecificObjectWrapper).field).isNull()
    }

    private class CustomHandlerExample(private val text: String?, private var tricky: Any?) {
        override fun equals(other: Any?): Boolean {
            if (other !is CustomHandlerExample) {
                return false
            }
            return text == other.text && tricky == other.tricky
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun customFieldHandler_counterfactual() {
        // Verifies that a naive DynamicCodec instance cannot serialize the `NOT_SERIALIZABLE` object.
        val codecs: ObjectCodecs =
            ObjectCodecs(
                ObjectCodecRegistry.newBuilder()
                    .add(DynamicCodec(CustomHandlerExample::class.java))
                    .build()
            )
        val expected: SerializationException? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable {
                    codecs.serialize(
                        CustomHandlerExample(
                            "hello",
                            NOT_SERIALIZABLE
                        )
                    )
                })
        assertThat(expected).hasMessageThat().contains("No default codec available")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun customFieldHandler() {
        // Overrides the handler for the field "tricky".
        val customCodec: DynamicCodec? =
            DynamicCodec.createWithOverrides(
                CustomHandlerExample::class.java,
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    CustomHandlerExample::class.java.getDeclaredField("tricky"),
                    object : FieldHandler() {
                        @Throws(IOException::class)
                        public override fun serialize(
                            context: SerializationContext?, codedOut: CodedOutputStream, obj: Any?
                        ) {
                            val subject = obj as CustomHandlerExample
                            codedOut.writeBoolNoTag(subject.tricky != null)
                        }

                        @Throws(IOException::class)
                        public override fun deserialize(
                            context: AsyncDeserializationContext?, codedIn: CodedInputStream, obj: Any
                        ) {
                            if (codedIn.readBool()) {
                                (obj as CustomHandlerExample).tricky = NOT_SERIALIZABLE
                            }
                        }
                    })
            )

        // The NOT_SERIALIZABLE object round-trips successfully with the custom handler.
        SerializationTester(
            CustomHandlerExample("a", null), CustomHandlerExample("b ", NOT_SERIALIZABLE)
        )
            .addCodec(customCodec)
            .runTests()
    }

    companion object {
        private fun createCycle(valueA: Int, valueB: Int): CycleA {
            val a = CycleA(valueA)
            a.b = CycleB(valueB)
            a.b.a = a
            return a
        }

        /** An object for testing that can't be serialized.  */
        private val NOT_SERIALIZABLE: Any = object : Any() {
            override fun toString(): String {
                return "not serializable"
            }
        }
    }
}
