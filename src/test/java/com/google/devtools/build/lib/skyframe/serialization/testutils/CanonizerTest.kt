// Copyright 2024 The Bazel Authors. All rights reserved.
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
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.skyframe.serialization.testutils.Canonizer.computeIdentifiers
import com.google.devtools.build.lib.skyframe.serialization.testutils.CanonizerTest.Companion.computeBreakdown
import org.junit.Test
import java.io.Serializable
import java.lang.ref.WeakReference
import java.util.function.Function
import kotlin.collections.ArrayList

@RunWith(JUnit4::class)
class CanonizerTest {
    @Test
    fun inlinedValues_leaveNoFingerprints() {
        val contents = "contents"
        val subject =
            TypeWithInlinedData(
                0x10.toByte(),
                12345.toShort(),
                65536,
                4294967296L,
                0.01f,
                12.123456789,
                true,
                'c',
                "text",
                WeakReference<Any?>(contents),
                ImmutableList.of<Any?>("x")
            )

        val identifiers: IdentityHashMap<Any?, Any?> = IdentityHashMap<Any?, Any?>()
        val key: IsomorphismKey = Canonizer.computePartitions( /* registry= */null, subject, identifiers)
        assertThat(key.fingerprint())
            .isEqualTo(
                fingerprintString(
                    (NAMESPACE
                            + "TypeWithInlinedData, boolValue=java.lang.Boolean:true,"
                            + " byteValue=java.lang.Byte:16, charValue=java.lang.Character:c,"
                            + " doubleValue=java.lang.Double:12.123456789, floatValue=java.lang.Float:0.01,"
                            + " intValue=java.lang.Integer:65536, longValue=java.lang.Long:4294967296,"
                            + " nonInlinedValue=TESTUTILS_CANONIZER_PLACEHOLDER,"
                            + " shortValue=java.lang.Short:12345, stringValue=java.lang.String:text,"
                            + " weakReferenceValue=java.lang.ref.WeakReference")
                )
            )

        assertThat(key.getLinksCount()).isEqualTo(1)
        val nonInlinedValueKey: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            key.getLink(0)
        assertThat(nonInlinedValueKey.fingerprint())
            .isEqualTo(
                fingerprintString("com.google.common.collect.ImmutableList, java.lang.String:x")
            )
        assertThat(nonInlinedValueKey.getLinksCount()).isEqualTo(0)

        Truth.assertThat(Companion.computeBreakdown(identifiers))
            .isEqualTo(
                Breakdown( // There are no fingerprints.
                    ImmutableMap.of<Any?, String?>(),  // There's a partition for `subject` and the `nonInlinedValue` field.
                    ImmutableSet.of<ImmutableSet<Any?>?>(
                        ImmutableSet.of<Any?>(subject), ImmutableSet.of<Any?>(subject.nonInlinedValue)
                    )
                )
            )
    }

    private class TypeWithInlinedData(
        private val byteValue: Byte,
        private val shortValue: Short,
        private val intValue: Int,
        private val longValue: Long,
        private val floatValue: Float,
        private val doubleValue: Double,
        private val boolValue: Boolean,
        private val charValue: Char,
        private val stringValue: String?,
        private val weakReferenceValue: WeakReference<Any?>?,
        /** A non-inlined value for contrast.  */
        private val nonInlinedValue: ImmutableList<Any?>
    )

    @Test
    fun specialCaseArrays() {
        val subject =
            TypeWithSpecialArrays(
                byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
                arrayOf<String>("abc", "def", "hij")
            )

        val identifiers: IdentityHashMap<Any?, Any?> = IdentityHashMap<Any?, Any?>()
        val key: IsomorphismKey = Canonizer.computePartitions( /* registry= */null, subject, identifiers)
        assertThat(key.fingerprint())
            .isEqualTo(
                fingerprintString(
                    (NAMESPACE
                            + "TypeWithSpecialArrays, byteArray=63deb80b9d484a1af76057b22fa1f403,"
                            + " stringArray=9efba83af9eba70ede40159aa606209c")
                )
            )
        assertThat(key.getLinksCount()).isEqualTo(0)

        Truth.assertThat(Companion.computeBreakdown(identifiers))
            .isEqualTo(
                Breakdown(
                    ImmutableMap.of<K?, V?>( // byte[] inlines as hex.
                        subject.byteArray,
                        fingerprintString("byte[]: [DEADBEEF]"),  // String[] values inline.
                        subject.stringArray,
                        fingerprintString(
                            "java.lang.String[]: [java.lang.String:abc, java.lang.String:def,"
                                    + " java.lang.String:hij]"
                        )
                    ),
                    ImmutableSet.of<ImmutableSet<Any?>?>(ImmutableSet.of<TypeWithSpecialArrays?>(subject))
                )
            )
    }

    private class TypeWithSpecialArrays(private val byteArray: ByteArray, private val stringArray: Array<String?>)

    @Test
    fun placeholder_correctlyFingerprints() {
        val subject: Array<Any?> = arrayOf<Any>(Position(1, 2), Position(3, 4), Position(1, 2))

        val key: IsomorphismKey =
            Canonizer.computePartitions( /* registry= */null, subject, IdentityHashMap<K?, V?>())
        assertThat(key.fingerprint())
            .isEqualTo(
                fingerprintString(
                    "java.lang.Object[], TESTUTILS_CANONIZER_PLACEHOLDER,"
                            + " TESTUTILS_CANONIZER_PLACEHOLDER, TESTUTILS_CANONIZER_PLACEHOLDER"
                )
            )
        assertThat(key.getLinksCount()).isEqualTo(3)
        assertThat(key.getLink(0).fingerprint())
            .isEqualTo(fingerprintString(NAMESPACE + "Position, x=1, y=2"))
        assertThat(key.getLink(1).fingerprint())
            .isEqualTo(fingerprintString(NAMESPACE + "Position, x=3, y=4"))
        assertThat(key.getLink(2).fingerprint())
            .isEqualTo(fingerprintString(NAMESPACE + "Position, x=1, y=2"))
    }

    @Test
    fun selfReferenceArray_reduces() {
        val subject = arrayOfNulls<Any>(1)
        subject[0] = subject

        val identifiers: IdentityHashMap<Any?, Any?> = IdentityHashMap<Any?, Any?>()
        val key: IsomorphismKey = Canonizer.computePartitions( /* registry= */null, subject, identifiers)

        assertThat(key.fingerprint())
            .isEqualTo(fingerprintString("java.lang.Object[], TESTUTILS_CANONIZER_PLACEHOLDER"))
        assertThat(key.getLinksCount()).isEqualTo(1)
        assertThat(key.getLink(0)).isEqualTo(key) // The key is cyclic.

        Truth.assertThat(Companion.computeBreakdown(identifiers))
            .isEqualTo(
                Breakdown(
                    ImmutableMap.of<Any?, String?>(),
                    ImmutableSet.of<ImmutableSet<Any?>?>(ImmutableSet.of<Any?>(subject))
                )
            )
    }

    @Test
    fun symmetricalArrays_reduce() {
        val subject = arrayOfNulls<Any>(1)
        val reflection: Array<Any?> = arrayOf<Any>(subject)
        subject[0] = reflection

        val identifiers: IdentityHashMap<Any?, Any?> = IdentityHashMap<Any?, Any?>()
        val key: IsomorphismKey = Canonizer.computePartitions( /* registry= */null, subject, identifiers)

        assertThat(key.fingerprint())
            .isEqualTo(fingerprintString("java.lang.Object[], TESTUTILS_CANONIZER_PLACEHOLDER"))
        assertThat(key.getLinksCount()).isEqualTo(1)
        assertThat(key.getLink(0)).isEqualTo(key) // The key is cyclic.

        Truth.assertThat(Companion.computeBreakdown(identifiers))
            .isEqualTo(
                Breakdown(
                    ImmutableMap.of<Any?, String?>(),
                    ImmutableSet.of<ImmutableSet<Any?>?>(ImmutableSet.of<Any?>(subject, reflection))
                )
            )
    }

    @Test
    fun distinctLocalFingerprintArrayCycle_fullyPartitions() {
        val pointA = Position(0, 1)
        val pointB = Position(1, 2)

        // Constructs a cycle, a1 -> a2 -> b -> a1.
        // (a1, a2) will have the same local fingerprint, but b1's local fingerprint is unique.
        val a1 = arrayOfNulls<Any>(2)
        val a2 = arrayOfNulls<Any>(2)
        val b = arrayOfNulls<Any>(2)

        a1[0] = pointA
        a1[1] = a2
        a2[0] = pointA
        a2[1] = b

        b[0] = pointB
        b[1] = a1

        val breakdown =
            Breakdown(
                ImmutableMap.of<Any?, String?>(),
                ImmutableSet.of<ImmutableSet<Any?>?>(
                    ImmutableSet.of<Any?>(pointA),
                    ImmutableSet.of<Any?>(pointB),
                    ImmutableSet.of<Any?>(a1),
                    ImmutableSet.of<Any?>(a2),
                    ImmutableSet.of<Any?>(b)
                )
            )

        // Verifies that partitioning is independent of starting node.
        for (root in ImmutableList.of<Array<Any?>?>(a1, a2, b)) {
            Truth.assertThat(Companion.computeBreakdown(root)).isEqualTo(breakdown)
        }
    }

    @Test
    fun localFingerprintIndistinguishableArrayCycle_fullyPartitions() {
        val pointA = Position(0, 1)
        val pointB = Position(1, 2)

        // Constructs a cycle, a1 -> a2 -> a3 -> b1 -> b2 -> a1.
        // (a1, a2, a3) and (b1, b2) have the same local fingerprints so local fingerprinting is not
        // enough to resolve this cycle. However, the cycle is slightly asymmetrical so every element
        // receives its own partition.
        val a1 = arrayOfNulls<Any>(2)
        val a2 = arrayOfNulls<Any>(2)
        val a3 = arrayOfNulls<Any>(2)
        val b1 = arrayOfNulls<Any>(2)
        val b2 = arrayOfNulls<Any>(2)

        a1[0] = pointA
        a2[0] = pointA
        a3[0] = pointA
        b1[0] = pointB
        b2[0] = pointB

        a1[1] = a2
        a2[1] = a3
        a3[1] = b1
        b1[1] = b2
        b2[1] = a1

        val breakdown =
            Breakdown(
                ImmutableMap.of<Any?, String?>(),
                ImmutableSet.of<ImmutableSet<Any?>?>(
                    ImmutableSet.of<Any?>(pointA),
                    ImmutableSet.of<Any?>(pointB),
                    ImmutableSet.of<Any?>(a1),
                    ImmutableSet.of<Any?>(a2),
                    ImmutableSet.of<Any?>(a3),
                    ImmutableSet.of<Any?>(b1),
                    ImmutableSet.of<Any?>(b2)
                )
            )

        // Verifies that partitioning is independent of starting node.
        for (root in ImmutableList.of<Array<Any?>?>(a1, a2, a3, b1, b2)) {
            Truth.assertThat(Companion.computeBreakdown(root)).isEqualTo(breakdown)
        }
    }

    @Test
    fun overlySymmetricalArrayCycle_reduces() {
        val pointA = Position(0, 1)
        val pointB = Position(1, 2)

        // Constructs the cycle a1 -> b1 -> a2 -> b2 -> a1.
        // This cycle is perfectly symmetrical so it reduces.
        val a1 = arrayOfNulls<Any>(2)
        val a2 = arrayOfNulls<Any>(2)
        val b1 = arrayOfNulls<Any>(2)
        val b2 = arrayOfNulls<Any>(2)

        a1[0] = pointA
        a2[0] = pointA
        b1[0] = pointB
        b2[0] = pointB

        a1[1] = b1
        b1[1] = a2
        a2[1] = b2
        b2[1] = a1

        val breakdown =
            Breakdown(
                ImmutableMap.of<Any?, String?>(),
                ImmutableSet.of<ImmutableSet<Any?>?>(
                    ImmutableSet.of<Any?>(pointA),
                    ImmutableSet.of<Any?>(pointB),
                    ImmutableSet.of<Any?>(a1, a2),
                    ImmutableSet.of<Any?>(b1, b2)
                )
            )

        // Verifies that partitioning is independent of starting node.
        for (root in ImmutableList.of<Array<Any?>?>(a1, a2, b1, b2)) {
            Truth.assertThat(Companion.computeBreakdown(root)).isEqualTo(breakdown)
        }
    }

    @Test
    fun map() {
        val subject: LinkedHashMap<Any?, Any?> = LinkedHashMap<Any?, Any?>()
        subject.put("abc", "def")
        subject.put(10, 12.5)
        subject.put(null, 'c')
        subject.put("k1", null)
        val position = Position(5, 10)
        subject.put(position, "Value")

        Truth.assertThat(Companion.computeBreakdown(subject))
            .isEqualTo(
                Breakdown(
                    ImmutableMap.of<Any?, String?>(),
                    ImmutableSet.of<ImmutableSet<Any?>?>(
                        ImmutableSet.of<Any?>(position),
                        ImmutableSet.of<Any?>(subject)
                    )
                )
            )
    }

    @Test
    fun symmetricalMapCycle_reduces() {
        // Constructs the cycle a1 -> b1 -> a2 -> b2 -> a1.
        // This cycle is perfectly symmetrical so it is reduced.
        val a1: LinkedHashMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        val b1: LinkedHashMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        val a2: LinkedHashMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        val b2: LinkedHashMap<String?, Any?> = LinkedHashMap<String?, Any?>()

        a1.put("A", b1)
        b1.put("B", a2)
        a2.put("A", b2)
        b2.put("B", a1)

        val aKey: IsomorphismKey =
            Canonizer.computePartitions( /* registry= */null, a1, IdentityHashMap<K?, V?>())
        assertThat(aKey.fingerprint())
            .isEqualTo(
                fingerprintString(
                    "java.util.LinkedHashMap, key=java.lang.String:A,"
                            + " value=TESTUTILS_CANONIZER_PLACEHOLDER"
                )
            )
        assertThat(aKey.getLinksCount()).isEqualTo(1)
        val bKey: IsomorphismKey = aKey.getLink(0)
        assertThat(bKey.fingerprint())
            .isEqualTo(
                fingerprintString(
                    "java.util.LinkedHashMap, key=java.lang.String:B,"
                            + " value=TESTUTILS_CANONIZER_PLACEHOLDER"
                )
            )
        assertThat(bKey.getLinksCount()).isEqualTo(1)
        assertThat(bKey.getLink(0)).isEqualTo(aKey) // The key has a cyclic structure.

        // Since it is a cycle, it is independent of starting node.
        for (root in ImmutableList.of<LinkedHashMap<String?, Any?>?>(a1, a2, b1, b2)) {
            // These checks are manual because the cyclic hash maps cannot be hashed.
            val identifiers: IdentityHashMap<Any?, Any?>? = computeIdentifiers( /* registry= */null, root)
            Truth.assertThat(identifiers).hasSize(4)
            Truth.assertThat(ImmutableSet.copyOf<Any?>(identifiers.values())).hasSize(2) // There are 2 partitions.

            Truth.assertThat(identifiers.get(a1)).isEqualTo(identifiers.get(a2))
            Truth.assertThat(identifiers.get(b1)).isEqualTo(identifiers.get(b2))
        }
    }

    @Test
    fun collection_partitions() {
        val subject = ArrayList<Any?>()
        subject.add("abc")
        subject.add(10)
        val position = Position(12, 24)
        subject.add(position)
        subject.add(subject) // Creates a cycle.
        subject.add(null)

        val identifiers: IdentityHashMap<Any?, Any?> = IdentityHashMap<Any?, Any?>()
        val key: IsomorphismKey = Canonizer.computePartitions( /* registry= */null, subject, identifiers)

        assertThat(key.fingerprint())
            .isEqualTo(
                fingerprintString(
                    "java.util.ArrayList, java.lang.String:abc, java.lang.Integer:10,"
                            + " TESTUTILS_CANONIZER_PLACEHOLDER, TESTUTILS_CANONIZER_PLACEHOLDER, null"
                )
            )

        assertThat(key.getLinksCount()).isEqualTo(2)

        val positionKey: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            key.getLink(0)
        assertThat(positionKey.fingerprint())
            .isEqualTo(fingerprintString(NAMESPACE + "Position, x=12, y=24"))
        assertThat(positionKey.getLinksCount()).isEqualTo(0)

        assertThat(key.getLink(1)).isEqualTo(key) // The key reflects the cyclic structure.

        // These checks are performed element-wise because the cyclic ArrayList cannot be hashed.
        Truth.assertThat(identifiers).hasSize(2)
        val subjectId: Any? = identifiers.get(subject)
        Truth.assertThat(subjectId).isInstanceOf(Canonizer.Partition::class.java)
        val positionId: Any? = identifiers.get(position)
        Truth.assertThat(positionId).isInstanceOf(Canonizer.Partition::class.java)

        Truth.assertThat(subjectId).isNotEqualTo(positionId)
    }

    @Test
    fun plainObject() {
        val subject = ExamplePlainObject()
        val key: IsomorphismKey =
            Canonizer.computePartitions( /* registry= */null, subject, IdentityHashMap<K?, V?>())
        assertThat(key.getLinksCount()).isEqualTo(0)
        assertThat(key.fingerprint())
            .isEqualTo(
                fingerprintString(
                    (NAMESPACE
                            + "ExamplePlainObject,"
                            + " booleanValue=false, byteValue=16, charValue=c,"
                            + " classValue=java.lang.Class:interface java.lang.Runnable,"
                            + " doubleValue=12.123456789, floatValue=0.01, intValue=65536,"
                            + " longValue=4294967296, nullClass=null, nullString=null, shortValue=12345,"
                            + " stringValue=java.lang.String:text")
                )
            )
    }

    private class ExamplePlainObject {
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
    fun singleReferenceConstant() {
        val constant = "constant"
        val registry: ObjectCodecRegistry? =
            ObjectCodecRegistry.newBuilder().addReferenceConstant(constant).build()

        // As expected, there are no identifiers for fully inlined objects.
        assertThat(computeIdentifiers(registry, constant)).isEmpty()
        assertThat(Canonizer.computePartitions(registry, constant, IdentityHashMap<K?, V?>())).isNull()

        val subject: Array<Any?> = arrayOf<Any>(constant)
        val key: IsomorphismKey = Canonizer.computePartitions(registry, subject, IdentityHashMap<K?, V?>())

        assertThat(key.getLinksCount()).isEqualTo(0)
        assertThat(key.fingerprint())
            .isEqualTo(
                fingerprintString("java.lang.Object[], java.lang.String[SERIALIZATION_CONSTANT:1]")
            )
    }

    @Test
    fun singleReferenceConstant_defaultRegistry() {
        val constant = "constant"
        val registry: ObjectCodecRegistry? = ObjectCodecRegistry.newBuilder().build()

        val subject: Array<Any?> = arrayOf<Any>(constant)
        val key: IsomorphismKey = Canonizer.computePartitions(registry, subject, IdentityHashMap<K?, V?>())
        assertThat(key.getLinksCount()).isEqualTo(0)
        assertThat(key.fingerprint())
            .isEqualTo(fingerprintString("java.lang.Object[], java.lang.String:constant"))
    }

    @Test
    fun singleReferenceConstant_nullRegistry() {
        val constant = "constant"
        val subject: Array<Any?> = arrayOf<Any>(constant)

        val key: IsomorphismKey =
            Canonizer.computePartitions( /* registry= */null, subject, IdentityHashMap<K?, V?>())
        assertThat(key.getLinksCount()).isEqualTo(0)
        assertThat(key.fingerprint())
            .isEqualTo(fingerprintString("java.lang.Object[], java.lang.String:constant"))
    }

    @Test
    fun multipleReferenceConstants() {
        val constant1 = "constant1"
        val constant2 = 256
        val registry: ObjectCodecRegistry? =
            ObjectCodecRegistry.newBuilder()
                .addReferenceConstant(constant1)
                .addReferenceConstant(constant2)
                .build()
        val subject: ImmutableList<Serializable?> = ImmutableList.of(constant1, "a", constant2, constant1)
        val key: IsomorphismKey = Canonizer.computePartitions(registry, subject, IdentityHashMap<K?, V?>())
        assertThat(key.getLinksCount()).isEqualTo(0)
        assertThat(key.fingerprint())
            .isEqualTo(
                fingerprintString(
                    ("com.google.common.collect.ImmutableList,"
                            + " java.lang.String[SERIALIZATION_CONSTANT:1], java.lang.String:a,"
                            + " java.lang.Integer[SERIALIZATION_CONSTANT:2],"
                            + " java.lang.String[SERIALIZATION_CONSTANT:1]")
                )
            )
    }

    @Test
    fun cyclicComplex_reduces() {
        val zero = Node(0)
        val one = Node(0)
        val two = Node(0)
        val three = Node(0)
        val four = Node(0)

        // The example consists of two overlapping cycles.
        // 0 -> 1 -> 2 -> 0
        zero.left = one
        one.left = two
        two.left = zero
        // 0 -> 1 -> 3 -> 4 -> 0
        one.right = three
        three.left = four
        four.left = zero

        val breakdown =
            Breakdown(
                ImmutableMap.of<Any?, String?>(),
                ImmutableSet.of<ImmutableSet<Any?>?>(
                    ImmutableSet.of<Any?>(zero),
                    ImmutableSet.of<Any?>(one),  // 2 and 4 are equivalent because they both transition to 0 on left.
                    ImmutableSet.of<Any?>(two, four),
                    ImmutableSet.of<Any?>(three)
                )
            )

        // Verifies that all rotations of the cyclic complex result in the same breakdown.
        for (rotated in arrayOf<Node>(zero, one, two, three, four)) {
            Truth.assertThat(Companion.computeBreakdown(rotated)).isEqualTo(breakdown)
        }
    }

    @Test
    fun childCycle_reduces() {
        val zero = Node(0)
        val one = Node(0)
        val two = Node(0)
        val three = Node(0)
        val four = Node(0)
        val five = Node(0)

        // The example consists of one cycle hanging off from another one.
        // 0 -> 1 -> 2 -> 0
        zero.left = one
        one.left = two
        two.left = zero
        // 1 -> 3 -> 4 -> 5 -> 3
        one.right = three
        three.left = four
        four.left = five
        five.left = three

        Truth.assertThat(Companion.computeBreakdown(zero))
            .isEqualTo(
                Breakdown(
                    ImmutableMap.of<Any?, String?>(),
                    ImmutableSet.of<ImmutableSet<Any?>?>(
                        ImmutableSet.of<Any?>(zero),
                        ImmutableSet.of<Any?>(one),
                        ImmutableSet.of<Any?>(two),
                        ImmutableSet.of<Any?>(three, four, five)
                    )
                )
            )
    }

    @Test
    fun peerCycles_reduce() {
        val zero = Node(0)
        val one = Node(0)
        val two = Node(0)
        val three = Node(0)
        val four = Node(0)
        val five = Node(0)

        // The example consists of two cycles hanging off a common root, 0. The cycles at the leaves are
        // identical in structure.
        // (left) 0 -> 1 -> 2 -> 1
        zero.left = one
        one.left = two
        two.left = one
        // (right) 0 -> 3 -> 4 -> 5 -> 4
        zero.right = three
        three.left = four
        four.left = five
        five.left = four

        Truth.assertThat(Companion.computeBreakdown(zero))
            .isEqualTo(
                Breakdown(
                    ImmutableMap.of<Any?, String?>(),
                    ImmutableSet.of<ImmutableSet<Any?>?>(
                        ImmutableSet.of<Any?>(zero),  // It's clear that the cycles (1, 2) and (4, 5) are indistuishable. Furthermore,
                        // 1 is indistinguishable from 2. Somewhat surprisingly, 3 is also
                        // indistinguishable. There are only two local fingerprints in this graph, call
                        // them fp0 and fp1. So node 3 looks looks like (fp1 · fp1*)?, which is
                        // indistinguishable from fp1*.
                        ImmutableSet.of<Any?>(one, two, three, four, five)
                    )
                )
            )
    }

    @Test
    fun unlabeledCyclicComplex_reduces() {
        val zero = Node(0)
        val one = Node(0)
        val two = Node(0)
        val three = Node(0)
        val four = Node(0)

        // The example consists of a somewhat complex graph.
        //    0
        //  ↗ ↓ ↖
        //  | 1  \
        //  |↙ ↘  \
        //  2   3  |
        //   ↖  ↓ /
        //      4
        // Note that (0, 2, 3) and (1, 4) are indistinguishable by local fingerprint. Let fp0 and fp1
        // be the local fingerprints, respectively. There's symmetry here, but it's hard to see at first
        // glance.
        //
        // 1 and 4 are equivalent. From either 1 or 4, taking the left branch, two hops through an fp0
        // node are required to reach 1 again. Taking the right branch, one hop is needed to reach 1 or
        // 4 again. Equivalence of 0 and 3 follows from that.
        zero.left = one
        one.left = two
        one.right = three
        two.left = zero
        three.left = four
        four.left = two
        four.right = zero

        val breakdown =
            Breakdown(
                ImmutableMap.of<Any?, String?>(),
                ImmutableSet.of<ImmutableSet<Any?>?>(
                    ImmutableSet.of<Any?>(zero, three), ImmutableSet.of<Any?>(one, four), ImmutableSet.of<Any?>(two)
                )
            )

        // Verfies all starting points produce the identical fingerprint maps.
        for (rotated in arrayOf<Node>(zero, one, two, three, four)) {
            Truth.assertThat(Companion.computeBreakdown(rotated)).isEqualTo(breakdown)
        }
    }

    @Test
    fun depthOverlappingRecursion_reduces() {
        // Sets up a graph consisting of a cycle with another cycle hanging off of it.
        //   A -> B -> C -> A            (first cycle)
        //        B -> D -> E -> F -> D  (second cycle)
        val a = arrayOfNulls<Any>(2)
        val b = arrayOfNulls<Any>(3)
        val c = arrayOfNulls<Any>(2)
        val d = arrayOfNulls<Any>(2)
        val e = arrayOfNulls<Any>(2)
        val f = arrayOfNulls<Any>(2)

        a[0] = "A"
        b[0] = "B"
        c[0] = "C"
        d[0] = "D" // D, E, and F are locally ambiguous.
        e[0] = "D"
        f[0] = "D"

        a[1] = b
        b[1] = c
        b[2] = d
        c[1] = a
        d[1] = e
        e[1] = f
        f[1] = d

        Truth.assertThat(Companion.computeBreakdown(a))
            .isEqualTo(
                Breakdown(
                    ImmutableMap.of<Any?, String?>(),
                    ImmutableSet.of<ImmutableSet<Any?>?>(
                        ImmutableSet.of<Any?>(a),
                        ImmutableSet.of<Any?>(b),
                        ImmutableSet.of<Any?>(c),
                        ImmutableSet.of<Any?>(d, e, f)
                    )
                )
            )
    }

    @kotlin.jvm.JvmRecord
    private data class Breakdown(
        val inlineFingerprints: ImmutableMap<Any?, String?>?,
        val partitions: ImmutableSet<ImmutableSet<Any?>?>?
    )

    /** Class for demonstrating reference cycles.  */
    private class Node(private val id: Int) {
        private var left: Node? = null
        private var right: Node? = null

        override fun toString(): String {
            return Integer.toString(id)
        }
    }

    /** An arbitrary class used as test data.  */
    @kotlin.jvm.JvmRecord
    private data class Position(val x: Int, val y: Int)
    companion object {
        // This string is very long:
        // com.google.devtools.build.lib.skyframe.serialization.testutils.CanonizerTest.
        // and ruins the spacing of assertions.
        private val NAMESPACE = CanonizerTest::class.java.getCanonicalName() + "."

        /** Computes identifiers, then separates fingerprinted objects from partitions.  */
        private fun computeBreakdown(subject: Any?): Breakdown? {
            return computeBreakdown(computeIdentifiers( /* registry= */null, subject))
        }

        private fun computeBreakdown(identifiers: IdentityHashMap<Any?, Any?>): Breakdown {
            val fingerprints = ImmutableMap.builder<Any?, String?>()
            val partitions: HashMap<Any?, HashSet<Any?>?> = HashMap<Any?, HashSet<Any?>?>()
            for (entry in identifiers.entrySet()) {
                val id: Any? = entry.getValue()
                val obj: Any? = entry.getKey()
                if (id is String) {
                    fingerprints.put(obj, id)
                    continue
                }
                partitions.computeIfAbsent(id, Function { unused: Any? -> HashSet<Any?>() }).add(obj)
            }
            return Breakdown(
                fingerprints.buildOrThrow(),
                partitions.values().stream()
                    .map<ImmutableSet<Any?>?>(Function { elements: HashSet<kotlin.Any?>? -> ImmutableSet.copyOf(elements) })
                    .collect(
                        ImmutableSet.toImmutableSet<ImmutableSet<Any?>?>()
                    )
            )
        }
    }
}
