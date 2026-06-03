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
package com.google.devtools.build.lib.collect.nestedset

import com.google.devtools.build.lib.actions.CommandLineExpansionException

/** Tests for [NestedSetFingerprintCache].  */
@RunWith(JUnit4::class)
class NestedSetFingerprintCacheTest {
    private inner class TestNestedSetFingerprintCache : NestedSetFingerprintCache() {
        private val fingerprinted: com.google.common.collect.Multiset<Any?> =
            com.google.common.collect.HashMultiset.create<Any?>()

        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        public override fun <T> addToFingerprint(mapFn: MapFn<in T?>?, fingerprint: Fingerprint?, `object`: T?) {
            super.addToFingerprint(mapFn, fingerprint, `object`)
            fingerprinted.add(`object`)
        }
    }

    private var cache: TestNestedSetFingerprintCache? = null

    @Before
    fun setup() {
        cache = TestNestedSetFingerprintCache()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBasic() {
        val nestedSet: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").add("b").build()

        // This test does reimplement the inner algorithm of the cache, but serves
        // as a simple check that the basic operations do something sensible
        val fingerprint: Fingerprint = Fingerprint()
        fingerprint.addInt(nestedSet.getOrder().ordinal())
        val subFingerprint: Fingerprint = Fingerprint()
        subFingerprint.addString("a")
        subFingerprint.addString("b")
        fingerprint.addBytes(subFingerprint.digestAndReset())
        val controlDigest: String? = fingerprint.hexDigestAndReset()

        val nestedSetFingerprint: Fingerprint = Fingerprint()
        cache.addNestedSetToFingerprint(nestedSetFingerprint, nestedSet)
        val nestedSetDigest: String? = nestedSetFingerprint.hexDigestAndReset()

        Truth.assertThat(controlDigest).isEqualTo(nestedSetDigest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOnlyFingerprintedOncePerString() {
        // Leaving leaf nodes with a single item will defeat this check
        // The nested set builder will effectively inline single-item objects into their parent,
        // meaning they will get hashed multiple times.
        val a: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a0").add("a1").build()
        val b: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("b0").add("b1").build()
        val c: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("c").addTransitive(a).addTransitive(b)
                .build()
        val d: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("d").addTransitive(a).addTransitive(b)
                .build()
        val e: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("e").addTransitive(c).addTransitive(d)
                .build()
        cache.addNestedSetToFingerprint(Fingerprint(), e)
        Truth.assertThat(cache.fingerprinted.elementSet())
            .containsExactly("a0", "a1", "b0", "b1", "c", "d", "e")
        for (entry in cache.fingerprinted.entrySet()) {
            Truth.assertThat(entry.getCount()).isEqualTo(1)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMapFn() {
        // Make sure that the map function assigns completely different key spaces
        val a: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a0").add("a1").build()

        val defaultMapFnFingerprint: Fingerprint = Fingerprint()
        cache.addNestedSetToFingerprint(defaultMapFnFingerprint, a)
        val explicitDefaultMapFnFingerprint: Fingerprint = Fingerprint()
        cache.addNestedSetToFingerprint(
            CommandLineItem.MapFn.DEFAULT, explicitDefaultMapFnFingerprint, a
        )
        val mappedFingerprint: Fingerprint = Fingerprint()
        cache.addNestedSetToFingerprint({ s, args -> args.accept(s.toString() + "_mapped") }, mappedFingerprint, a)

        val defaultMapFnDigest: String? = defaultMapFnFingerprint.hexDigestAndReset()
        val explicitDefaultMapFnDigest: String? = explicitDefaultMapFnFingerprint.hexDigestAndReset()
        val mappedDigest: String? = mappedFingerprint.hexDigestAndReset()
        Truth.assertThat(defaultMapFnDigest).isEqualTo(explicitDefaultMapFnDigest)
        Truth.assertThat(mappedDigest).isNotEqualTo(defaultMapFnDigest)

        Truth.assertThat(cache.fingerprinted.elementSet()).containsExactly("a0", "a1")
        for (entry in cache.fingerprinted.entrySet()) {
            Truth.assertThat(entry.getCount()).isEqualTo(2)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleInstancesOfMapFnThrows() {
        val nestedSet: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a0").add("a1").build()

        // Make sure a normal method reference doesn't get denied.
        for (i in 0..1) {
            cache.addNestedSetToFingerprint(
                { o: String?, args: java.util.function.Consumer<kotlin.String?> -> simpleExpand(o, args) },
                Fingerprint(),
                nestedSet
            )
        }

        // Try again to make sure Java synthesizes a new class for a second method reference.
        for (i in 0..1) {
            cache.addNestedSetToFingerprint(
                { o: String?, args: java.util.function.Consumer<kotlin.String?> -> simpleExpand2(o, args) },
                Fingerprint(),
                nestedSet
            )
        }

        // Make sure a non-capturing lambda doesn't get denied
        for (i in 0..1) {
            cache.addNestedSetToFingerprint(
                { s, args -> args.accept(s.toString() + "_mapped") }, Fingerprint(), nestedSet
            )
        }

        // Make sure a ParametrizedMapFn doesn't get denied until it exceeds its instance count
        cache.addNestedSetToFingerprint(IntParametrizedMapFn(1), Fingerprint(), nestedSet)
        cache.addNestedSetToFingerprint(IntParametrizedMapFn(2), Fingerprint(), nestedSet)
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                cache.addNestedSetToFingerprint(
                    IntParametrizedMapFn(3), Fingerprint(), nestedSet
                )
            })

        // Make sure a capturing method reference gets denied. The for loop causes the variable i
        // to be captured, so that str::expand becomes a capturing lambda, not a plain method reference.
        // This test case ensures that the captured lambda cannot be used twice.
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                for (i in 0..1) {
                    val str: StringJoiner =
                        com.google.devtools.build.lib.collect.nestedset.NestedSetFingerprintCacheTest.StringJoiner("hello")
                    cache.addNestedSetToFingerprint({ other: String?, args: java.util.function.Consumer<kotlin.String?> ->
                        str.expand(
                            other,
                            args
                        )
                    }, Fingerprint(), nestedSet)
                }
            })

        // Do make sure that a capturing lambda gets denied. The loop exists for the same reason as
        // the above case.
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                for (i in 0..1) {
                    val capturedVariable = i
                    cache.addNestedSetToFingerprint(
                        { s, args -> args.accept(s + capturedVariable) }, Fingerprint(), nestedSet
                    )
                }
            })
    }

    private class IntParametrizedMapFn(private val i: Int) : CommandLineItem.ParametrizedMapFn<String?>() {
        public override fun expandToCommandLine(`object`: String?, args: java.util.function.Consumer<String?>) {
            args.accept(`object` + i)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || javaClass != o.javaClass) {
                return false
            }
            val that = o as IntParametrizedMapFn
            return i == that.i
        }

        public override fun maxInstancesAllowed(): Int {
            return 2
        }

        override fun hashCode(): Int {
            return com.google.common.base.Objects.hashCode(i)
        }
    }

    private class StringJoiner(private val str: String?) {
        fun expand(other: String?, args: java.util.function.Consumer<String?>) {
            args.accept(str + other)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFingerprintDeduplicationOfIdenticalTransitiveSets() {
        val a: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").add("b").build()
        val b: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").add("b").build()

        // Verify assumption that a and b are distinct objects (otherwise this test is trivial)
        assertThat(a).isNotSameInstanceAs(b)
        // Verify assumption that they have the same content
        assertThat(a.toList()).containsExactly("a", "b").inOrder()
        assertThat(b.toList()).containsExactly("a", "b").inOrder()

        // Verify fingerprints of transitive sets are identical
        val fA: Fingerprint = Fingerprint()
        cache.addNestedSetToFingerprint(fA, a)
        val hexA: String? = fA.hexDigestAndReset()

        val fB: Fingerprint = Fingerprint()
        cache.addNestedSetToFingerprint(fB, b)
        val hexB: String? = fB.hexDigestAndReset()

        Truth.assertThat(hexA).isEqualTo(hexB)

        // Add a leaf to ensure that the NestedSet is not optimized to just return the transitive set.
        val includesBoth: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ()
                .add("leaf")
                .addTransitive(a)
                .addTransitive(b)
                .build()
        val includesOne: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("leaf").addTransitive(a).build()

        val fingerprintOne: Fingerprint = Fingerprint()
        cache.addNestedSetToFingerprint(fingerprintOne, includesOne)
        val digestOne: String? = fingerprintOne.hexDigestAndReset()

        val fingerprintBoth: Fingerprint = Fingerprint()
        cache.addNestedSetToFingerprint(fingerprintBoth, includesBoth)
        val digestBoth: String? = fingerprintBoth.hexDigestAndReset()

        Truth.assertThat(digestBoth).isEqualTo(digestOne)

        val c: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").add("c").build()
        val includesDifferent: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ()
                .add("leaf")
                .addTransitive(a)
                .addTransitive(c)
                .build()

        val fingerprintDifferent: Fingerprint = Fingerprint()
        cache.addNestedSetToFingerprint(fingerprintDifferent, includesDifferent)
        val digestDifferent: String? = fingerprintDifferent.hexDigestAndReset()

        Truth.assertThat(digestBoth).isNotEqualTo(digestDifferent)
    }

    companion object {
        private fun simpleExpand(o: String?, args: java.util.function.Consumer<String?>) {
            args.accept(o + "_mapped")
        }

        private fun simpleExpand2(o: String?, args: java.util.function.Consumer<String?>) {
            args.accept(o + "_mapped2")
        }
    }
}
