// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.testutils

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper.dumpStructure
import org.junit.Test
import java.io.Serializable
import java.lang.Byte
import java.lang.Double
import java.lang.Float
import java.lang.ref.WeakReference
import kotlin.Any
import kotlin.Array
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Int
import kotlin.String
import kotlin.Unit
import kotlin.arrayOf
import kotlin.arrayOfNulls
import kotlin.byteArrayOf
import kotlin.collections.ArrayList
import kotlin.doubleArrayOf
import kotlin.intArrayOf

@RunWith(TestParameterInjector::class)
class DumperTest {
    @Test
    fun testNull() {
        assertThat(dumpStructure(null)).isEqualTo("null")
    }

    @Test
    fun testWeakReference() {
        val referent = 10
        val ref = WeakReference<Int>(referent)
        assertThat(dumpStructure(ref)).isEqualTo("java.lang.ref.WeakReference")
    }

    @Test
    fun testInlinedTypes() {
        assertThat(dumpStructure(Byte.valueOf(0x10.toByte()))).isEqualTo("16")
        assertThat(dumpStructure(java.lang.Short.valueOf(12345.toShort()))).isEqualTo("12345")
        assertThat(dumpStructure(Integer.valueOf(65536))).isEqualTo("65536")
        assertThat(dumpStructure(java.lang.Long.valueOf(4294967296L))).isEqualTo("4294967296")
        assertThat(dumpStructure(Float.valueOf(0.01f))).isEqualTo("0.01")
        assertThat(dumpStructure(Double.valueOf(12.123456789))).isEqualTo("12.123456789")
        assertThat(dumpStructure(java.lang.Boolean.TRUE)).isEqualTo("true")
        assertThat(dumpStructure(Character.valueOf('c'))).isEqualTo("c")

        assertThat(dumpStructure("text")).isEqualTo("text")
        assertThat(dumpStructure(Thread::class.java)).isEqualTo("class java.lang.Thread")

        // Lambdas are also inlined because they qualify as sythetic.
        val lambda = Runnable {}
        Truth.assertThat(lambda.getClass().isSynthetic()).isTrue()
        // The string representation of lambdas is not stable, and has additional variability across
        // JDK versions. It should always start with the namespace.
        assertThat(dumpStructure(lambda)).matches(NAMESPACE + ".*")
    }

    @Test
    fun testByteArray() {
        val bytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        // Byte array output is special cased.
        assertThat(dumpStructure(bytes)).isEqualTo("byte[](0) [DEADBEEF]")
    }

    @Test
    fun nestedByteArrays() {
        val bytes1 = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val bytes2 = byteArrayOf(0xFA.toByte(), 0xCE.toByte(), 0xCA.toByte(), 0xFE.toByte())

        val nestedBytes = arrayOf<ByteArray?>(bytes1, bytes2, bytes1, null)

        assertThat(dumpStructure(nestedBytes))
            .isEqualTo(
                ("byte[][](0) [\n"
                        + "  byte[](1) [DEADBEEF]\n"
                        + "  byte[](2) [FACECAFE]\n"
                        + "  byte[](1)\n" // backreference
                        + "  null\n"
                        + "]")
            )
    }

    @Test
    fun testInlineArray() {
        val input: Array<String?> = arrayOf<String>("abc", "def", "hij")
        // Arrays declared with types that are inlined (such as String) are special cased.
        assertThat(dumpStructure(input)).isEqualTo("java.lang.String[](0) [abc, def, hij]")
    }

    @Test
    fun testMap() {
        val input: LinkedHashMap<Any?, Any?> = LinkedHashMap<Any?, Any?>()
        input.put("abc", "def")
        input.put(10, 12.5)
        input.put(null, 'c')
        input.put("k1", null)
        input.put(Position(5, 10), "Value")

        assertThat(dumpStructure(input))
            .isEqualTo(
                ("java.util.LinkedHashMap(0) [\n"
                        + "  key=abc\n"
                        + "  value=def\n"
                        + "  key=10\n"
                        + "  value=12.5\n"
                        + "  key=null\n"
                        + "  value=c\n"
                        + "  key=k1\n"
                        + "  value=null\n"
                        + ("  key=" + NAMESPACE + ".Position(1) [\n")
                        + "    x=5\n"
                        + "    y=10\n"
                        + "  ]\n"
                        + "  value=Value\n"
                        + "]")
            )
    }

    @Test
    fun testCollection() {
        val input = ArrayList<Any?>()
        input.add("abc")
        input.add(10)
        input.add(Position(12, 24))
        input.add(input) // cyclic
        input.add(null)

        assertThat(dumpStructure(input))
            .isEqualTo(
                ("java.util.ArrayList(0) [\n"
                        + "  abc\n"
                        + "  10\n"
                        + ("  " + NAMESPACE + ".Position(1) [\n")
                        + "    x=12\n"
                        + "    y=24\n"
                        + "  ]\n"
                        + "  java.util.ArrayList(0)\n" // cyclic backreference
                        + "  null\n"
                        + "]")
            )
    }

    @Test
    fun testPlainFields() {
        assertThat(dumpStructure(ExamplePojo()))
            .isEqualTo(
                (NAMESPACE
                        + ".ExamplePojo(0) [\n"
                        + "  booleanValue=false\n"
                        + "  byteValue=16\n"
                        + "  charValue=c\n"
                        + "  classValue=interface java.lang.Runnable\n"
                        + "  doubleValue=12.123456789\n"
                        + "  floatValue=0.01\n"
                        + "  intValue=65536\n"
                        + "  longValue=4294967296\n"
                        + "  nullClass=null\n"
                        + "  nullString=null\n"
                        + "  shortValue=12345\n"
                        + "  stringValue=text\n"
                        + "]")
            )
    }

    private class ExamplePojo {
        private val booleanValue = false
        private val byteValue = 0x10.toByte()
        private val shortValue = 12345.toShort()
        private val charValue = 'c'
        private val intValue = 65536
        private val longValue = 4294967296L
        private val floatValue = 0.01f
        private val doubleValue = 12.123456789
        private val stringValue = "text"
        private val nullString: String? = null
        private val classValue: Class<*> = Runnable::class.java
        private val nullClass: Class<*>? = null
    }

    @Test
    fun testShadowedFields() {
        assertThat(dumpStructure(ShadowedFieldsGrandchild()))
            .isEqualTo(
                (NAMESPACE
                        + ".ShadowedFieldsGrandchild(0) [\n"
                        + "  shadowed=1\n"
                        + "  shadowed=2\n"
                        + "  shadowed=3\n"
                        + "]")
            )
    }

    internal enum class DeduplicationMode {
        REFERENCE_DEDUPLICATION {
            override fun dumpStructure(registry: ObjectCodecRegistry?, obj: Any?): String {
                return Dumper.dumpStructure(registry, obj)
            }
        },
        VALUE_DEDUPLICATION {
            override fun dumpStructure(registry: ObjectCodecRegistry?, obj: Any?): String {
                return Dumper.dumpStructureWithEquivalenceReduction(registry, obj)
            }
        };

        abstract fun dumpStructure(registry: ObjectCodecRegistry?, obj: Any?): String?
    }

    @Test
    fun dump_handlesReferenceConstants(@TestParameter mode: DeduplicationMode) {
        val subject = "constant"
        val registry: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ObjectCodecRegistry.newBuilder().addReferenceConstant(subject).build()
        Truth.assertThat(mode.dumpStructure(registry, subject))
            .isEqualTo("java.lang.String[SERIALIZATION_CONSTANT:1]")
    }

    @Test
    fun dump_handlesReference_withoutReferenceConstant(
        @TestParameter mode: DeduplicationMode, @TestParameter emptyRegistry: Boolean
    ) {
        val subject = "constant"
        val registry: ObjectCodecRegistry? = if (emptyRegistry) ObjectCodecRegistry.newBuilder().build() else null
        Truth.assertThat(mode.dumpStructure(registry, subject)).isEqualTo("constant")
    }

    @Test
    fun dump_handlesMultipleReferenceConstants(@TestParameter mode: DeduplicationMode) {
        val constant1 = "constant1"
        val constant2 = 256
        val registry: ObjectCodecRegistry? =
            ObjectCodecRegistry.newBuilder()
                .addReferenceConstant(constant1)
                .addReferenceConstant(constant2)
                .build()
        val subject: ImmutableList<Serializable?> = ImmutableList.of(constant1, "a", constant2, constant1)
        Truth.assertThat(mode.dumpStructure(registry, subject))
            .isEqualTo(
                """
com.google.common.collect.ImmutableList(0) [
  java.lang.String[SERIALIZATION_CONSTANT:1]
  a
  java.lang.Integer[SERIALIZATION_CONSTANT:2]
  java.lang.String[SERIALIZATION_CONSTANT:1]
]
""".trimIndent()
            )
    }

    private open class ShadowedFields {
        private val shadowed = 1
    }

    private open class ShadowedFieldsChild : ShadowedFields() {
        private val shadowed = 2
    }

    private class ShadowedFieldsGrandchild : ShadowedFieldsChild() {
        private val shadowed = 3
    }

    @Test
    fun testComposition() {
        // This test verifies that cross-nesting of the special cased types works as expected.
        val input = ArrayList<Any?>()

        // An array that contains an array, map, iterable and simple object.
        val arrayInput: Array<Any?> =
            arrayOf<Any>(
                arrayOf<String>("abc", "def"),
                ImmutableMap.of<Any?, Any?>(10, true, 12, 0),
                ImmutableList.of<Any?>(false, 0, -1),
                Position(15, 64)
            )
        input.add(arrayInput)

        // A map that contains an array, map, iterable and simple object.
        val mapInput: LinkedHashMap<Any?, Any?> = LinkedHashMap<Any?, Any?>()
        mapInput.put(arrayOf<Position?>(), ImmutableMap.of<Any?, Any?>(0, false, 1, true, 2, false))
        mapInput.put(ImmutableList.of<Any?>("xyz", 0.1), Position(1, 3))
        input.add(mapInput)

        // An iterable that contains an array, map, iterable and simple object.
        val iterableInput = ArrayList<Any?>()
        iterableInput.add(intArrayOf(1, 2, 3, 4, 5))
        iterableInput.add(ImmutableMap.of<String?, String?>("a", "A", "b", "B", "c", "C"))
        iterableInput.add(ImmutableList.of<Any?>("a", 1, 0.1))
        iterableInput.add(Position(-1, 5))
        input.add(iterableInput)

        // An object that contains an array, map, iterable and simple object.
        input.add(CompositeObject())

        assertThat(dumpStructure(input))
            .isEqualTo(
                ("java.util.ArrayList(0) [\n"
                        + "  java.lang.Object[](1) [\n"
                        + "    java.lang.String[](2) [abc, def]\n"
                        + "    com.google.common.collect.RegularImmutableMap(3) [\n"
                        + "      key=10\n"
                        + "      value=true\n"
                        + "      key=12\n"
                        + "      value=0\n"
                        + "    ]\n"
                        + "    com.google.common.collect.ImmutableList(4) [\n"
                        + "      false\n"
                        + "      0\n"
                        + "      -1\n"
                        + "    ]\n"
                        + ("    " + NAMESPACE + ".Position(5) [\n")
                        + "      x=15\n"
                        + "      y=64\n"
                        + "    ]\n"
                        + "  ]\n"
                        + "  java.util.LinkedHashMap(6) [\n"
                        + ("    key=" + NAMESPACE + ".Position[](7) []\n")
                        + "    value=com.google.common.collect.RegularImmutableMap(8) [\n"
                        + "      key=0\n"
                        + "      value=false\n"
                        + "      key=1\n"
                        + "      value=true\n"
                        + "      key=2\n"
                        + "      value=false\n"
                        + "    ]\n"
                        + "    key=com.google.common.collect.ImmutableList(9) [\n"
                        + "      xyz\n"
                        + "      0.1\n"
                        + "    ]\n"
                        + ("    value=" + NAMESPACE + ".Position(10) [\n")
                        + "      x=1\n"
                        + "      y=3\n"
                        + "    ]\n"
                        + "  ]\n"
                        + "  java.util.ArrayList(11) [\n"
                        + "    int[](12) [1, 2, 3, 4, 5]\n"
                        + "    com.google.common.collect.RegularImmutableMap(13) [\n"
                        + "      key=a\n"
                        + "      value=A\n"
                        + "      key=b\n"
                        + "      value=B\n"
                        + "      key=c\n"
                        + "      value=C\n"
                        + "    ]\n"
                        + "    com.google.common.collect.ImmutableList(14) [\n"
                        + "      a\n"
                        + "      1\n"
                        + "      0.1\n"
                        + "    ]\n"
                        + ("    " + NAMESPACE + ".Position(15) [\n")
                        + "      x=-1\n"
                        + "      y=5\n"
                        + "    ]\n"
                        + "  ]\n"
                        + ("  " + NAMESPACE + ".CompositeObject(16) [\n")
                        + "    doubles=double[](17) [0.1, 0.2, 0.3]\n"
                        + "    iterable=com.google.common.collect.ImmutableList(18) [\n"
                        + "      interface java.lang.Runnable\n"
                        + "      class java.lang.Thread\n"
                        + "    ]\n"
                        + "    map=com.google.common.collect.RegularImmutableMap(19) [\n"
                        + "      key=x\n"
                        + "      value=0\n"
                        + "      key=y\n"
                        + "      value=1\n"
                        + "    ]\n"
                        + ("    obj=" + NAMESPACE + ".Position(20) [\n")
                        + "      x=12\n"
                        + "      y=13\n"
                        + "    ]\n"
                        + "  ]\n"
                        + "]")
            )
    }

    /** A class that contains an array, map, iterable and simple object.  */
    private class CompositeObject {
        private val doubles = doubleArrayOf(0.1, 0.2, 0.3)
        private val map: ImmutableMap<String?, Int?> = ImmutableMap.of<String?, Int?>("x", 0, "y", 1)
        private val iterable: ImmutableList<Class<*>?> =
            ImmutableList.of<Class<*>?>(Runnable::class.java, Thread::class.java)
        private val obj = Position(12, 13)
    }

    @Test
    fun equivalenceReduction() {
        val subject = ImmutableList.of<Position?>(Position(4, 5), Position(4, 5))

        assertThat(dumpStructure(subject))
            .isEqualTo(
                """
com.google.common.collect.ImmutableList(0) [
  com.google.devtools.build.lib.skyframe.serialization.testutils.DumperTest.Position(1) [
    x=4
    y=5
  ]
  com.google.devtools.build.lib.skyframe.serialization.testutils.DumperTest.Position(2) [
    x=4
    y=5
  ]
]
""".trimIndent()
            )

        // With equivalence reduction, the duplicate position is turned into a backreference.
        assertThat(dumpStructureWithEquivalenceReduction(subject))
            .isEqualTo(
                """
com.google.common.collect.ImmutableList(0) [
  com.google.devtools.build.lib.skyframe.serialization.testutils.DumperTest.Position(1) [
    x=4
    y=5
  ]
  com.google.devtools.build.lib.skyframe.serialization.testutils.DumperTest.Position(1)
]
""".trimIndent()
            )
    }

    @Test
    fun equivalentCycles() {
        // `cycle1` and `cycle2` are equivalent, but different references.

        // Note that both `cycle1` and `cycle2` have strong self-symmetry and exercise a special
        // fallback codepath in `Fingerprinter.handleStronglyConnectedComponent`.

        val cycle1 = ArrayList<Any?>()
        val one = ArrayList<Any?>()
        cycle1.add(one)
        one.add(cycle1)

        val cycle2 = ArrayList<Any?>()
        val two = ArrayList<Any?>()
        cycle2.add(two)
        two.add(cycle2)

        val subject = ImmutableList.of<ArrayList<Any?>?>(cycle1, cycle2)

        assertThat(dumpStructure(subject))
            .isEqualTo(
                """
            com.google.common.collect.ImmutableList(0) [
              java.util.ArrayList(1) [
                java.util.ArrayList(2) [
                  java.util.ArrayList(1)
                ]
              ]
              java.util.ArrayList(3) [
                java.util.ArrayList(4) [
                  java.util.ArrayList(3)
                ]
              ]
            ]
            """.trimIndent()
            )
        // Equivalence reduction deduplicates the 2nd cycle.
        assertThat(dumpStructureWithEquivalenceReduction(subject))
            .isEqualTo(
                """
            com.google.common.collect.ImmutableList(0) [
              java.util.ArrayList(1) [
                java.util.ArrayList(1)
              ]
              java.util.ArrayList(1)
            ]
            """.trimIndent()
            )
    }

    @Test
    fun emptyArray_dumps() {
        val subject = arrayOfNulls<Int>(0)
        assertThat(dumpStructureWithEquivalenceReduction(subject))
            .isEqualTo("java.lang.Integer[](0) []")
    }

    @Test
    fun nullContainingArray_dumps() {
        val subject = arrayOf<Any?>(null, true, null, false)
        assertThat(dumpStructureWithEquivalenceReduction(subject))
            .isEqualTo(
                """
java.lang.Object[](0) [
  null
  true
  null
  false
]
""".trimIndent()
            )
    }

    @Test
    fun rotations_areDeduplicated() {
        // `cycle1` and `cycle2` are isomorphic, but rotated.
        val cycle1 = ArrayList<Any?>()
        val one = ArrayList<Any?>()
        cycle1.add(1)
        cycle1.add(one)
        one.add(2)
        one.add(cycle1)

        val cycle2 = ArrayList<Any?>()
        val two = ArrayList<Any?>()
        cycle2.add(2)
        cycle2.add(two)
        two.add(1)
        two.add(cycle2)

        val subject = ImmutableList.of<ArrayList<Any?>?>(cycle1, cycle2)
        assertThat(dumpStructureWithEquivalenceReduction(subject))
            .isEqualTo(
                """
com.google.common.collect.ImmutableList(0) [
  java.util.ArrayList(1) [
    1
    java.util.ArrayList(2) [
      2
      java.util.ArrayList(1)
    ]
  ]
  java.util.ArrayList(2)
]
""".trimIndent()
            )
    }

    @Test
    fun referenceRotationsDeduplicated() {
        // This test case contrasts the previous test case. The rotation can't be deduplicated by
        // fingerprint, but it can still be deduplicated by reference.

        val cycle = ArrayList<Any?>()
        val one = ArrayList<Any?>()
        cycle.add('A')
        cycle.add(one)
        one.add('B')
        one.add(cycle)

        val subject = ImmutableList.of<ArrayList<Any?>?>(cycle, one)
        assertThat(dumpStructureWithEquivalenceReduction(subject))
            .isEqualTo(
                """
            com.google.common.collect.ImmutableList(0) [
              java.util.ArrayList(1) [
                A
                java.util.ArrayList(2) [
                  B
                  java.util.ArrayList(1)
                ]
              ]
              java.util.ArrayList(2)
            ]
            """.trimIndent()
            )
    }

    /** An arbitrary class used as test data.  */
    @kotlin.jvm.JvmRecord
    private data class Position(val x: Int, val y: Int)
    companion object {
        private val NAMESPACE: String = DumperTest::class.java.getCanonicalName()
    }
}
