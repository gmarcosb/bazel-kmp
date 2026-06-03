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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** Test class for [StarlarkInfo] and its subclasses.  */
@RunWith(JUnit4::class)
class StarlarkInfoTest {
    @org.junit.Test
    fun instancesOfUnexportedProvidersAreMutable() {
        val provider: StarlarkProvider = makeProvider()
        val info: StarlarkInfo = makeInfoWithF1F2Values(provider, StarlarkInt.of(5), null)
        assertThat(info.isImmutable()).isFalse()
    }

    @org.junit.Test
    fun instancesOfExportedProvidersMayBeImmutable() {
        val provider: StarlarkProvider = makeExportedProvider()
        val info: StarlarkInfo = makeInfoWithF1F2Values(provider, StarlarkInt.of(5), null)
        assertThat(info.isImmutable()).isTrue()
    }

    @org.junit.Test
    fun mutableIfContentsAreMutable() {
        val provider: StarlarkProvider = makeExportedProvider()
        val v: StarlarkValue = object : StarlarkValue {}
        val info: StarlarkInfo = makeInfoWithF1F2Values(provider, StarlarkInt.of(5), v)
        assertThat(info.isImmutable()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun equivalence() {
        val provider1: StarlarkProvider = makeProvider()
        val provider2: StarlarkProvider = makeProvider()
        // equal providers and fields
        assertThat(makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), StarlarkInt.of(5)))
            .isEqualTo(makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), StarlarkInt.of(5)))
        // different providers => unequal
        assertThat(makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), StarlarkInt.of(5)))
            .isNotEqualTo(makeInfoWithF1F2Values(provider2, StarlarkInt.of(4), StarlarkInt.of(5)))
        // different fields => unequal
        assertThat(makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), StarlarkInt.of(5)))
            .isNotEqualTo(makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), StarlarkInt.of(6)))
        // different sets of fields => unequal
        assertThat(makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), StarlarkInt.of(5)))
            .isNotEqualTo(makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), null))
    }

    @org.junit.Test
    fun concatWithDifferentProvidersFails() {
        val provider1: StarlarkProvider = makeProvider()
        val provider2: StarlarkProvider = makeProvider()
        val info1: StarlarkInfo = makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), StarlarkInt.of(5))
        val info2: StarlarkInfo = makeInfoWithF1F2Values(provider2, StarlarkInt.of(4), StarlarkInt.of(5))
        val expected: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    info1.binaryOp(
                        net.starlark.java.syntax.TokenKind.PLUS,
                        info2,
                        true
                    )
                })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("Cannot use '+' operator on instances of different providers")
    }

    @org.junit.Test
    fun concatWithOverlappingFieldsFails() {
        val provider1: StarlarkProvider = makeProvider()
        val info1: StarlarkInfo = makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), StarlarkInt.of(5))
        val info2: StarlarkInfo = makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), null)
        val expected: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    info1.binaryOp(
                        net.starlark.java.syntax.TokenKind.PLUS,
                        info2,
                        true
                    )
                })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("cannot add struct instances with common field 'f1'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun concatWithSameFields() {
        val provider: StarlarkProvider = makeProvider()
        val info1: StarlarkInfo = makeInfoWithF1F2Values(provider, StarlarkInt.of(4), null)
        val info2: StarlarkInfo = makeInfoWithF1F2Values(provider, null, StarlarkInt.of(5))
        val result: StarlarkInfo = info1.binaryOp(net.starlark.java.syntax.TokenKind.PLUS, info2, true) as StarlarkInfo
        assertThat(result.getFieldNames()).containsExactly("f1", "f2")
        assertThat(result.getValue("f1")).isEqualTo(StarlarkInt.of(4))
        assertThat(result.getValue("f2")).isEqualTo(StarlarkInt.of(5))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun concatWithDifferentFields() {
        val provider: StarlarkProvider = makeProvider()
        val info1: StarlarkInfo = makeInfoWithF1F2Values(provider, StarlarkInt.of(4), null)
        val info2: StarlarkInfo = makeInfoWithF1F2Values(provider, null, StarlarkInt.of(5))
        val result: StarlarkInfo = info1.binaryOp(net.starlark.java.syntax.TokenKind.PLUS, info2, true) as StarlarkInfo
        assertThat(result.getFieldNames()).containsExactly("f1", "f2")
        assertThat(result.getValue("f1")).isEqualTo(StarlarkInt.of(4))
        assertThat(result.getValue("f2")).isEqualTo(StarlarkInt.of(5))
    }

    // Tests sortPairs using arrays of various lengths from Fibonacci sequence.
    @org.junit.Test
    fun testSortPairs() {
        var ok = true
        val rand: Random = Random(0)

        // (a, b) is the Fibonacci generator. We use a as the array length.
        var a = 0
        var b = 1
        while (a < 1000) {
            // generate random array of a pairs.
            val array = arrayOfNulls<Any>(2 * a)
            for (i in 0..<a) {
                val r: Int = rand.nextInt(1000000)
                array[i] = String.format("key%06d", r)
                array[a + i] = r
            }

            // Sort keys and values using reference implementation.
            val origKeys =
                java.util.ArrayList<Any?>(
                    java.util.Arrays.asList<Any?>(*array).subList(0, a)
                ) as MutableList<*> as MutableList<String?>
            Collections.sort<String?>(origKeys)
            val origValues =
                java.util.ArrayList<Any?>(
                    java.util.Arrays.asList<Any?>(*array).subList(a, 2 * a)
                ) as MutableList<*> as MutableList<Int?>
            Collections.sort<Int?>(origValues)

            // Sort using sortPairs.
            if (a > 0) {
                StarlarkInfoNoSchema.sortPairs(array, 0, a - 1)
            }

            // Assert sorted keys match reference implementation.
            val keys: MutableList<*> = java.util.Arrays.asList<Any?>(*array).subList(0, a)
            if (keys != origKeys) {
                java.lang.System.err.printf("a=%d: keys not in order: got %s, want %s\n", a, keys, origKeys)
                ok = false
            }

            // Assert sorted values match reference implementation.
            val values: MutableList<*> = java.util.Arrays.asList<Any?>(*array).subList(a, 2 * a)
            if (values != origValues) {
                java.lang.System.err.printf("a=%d: values not in order: got %s, want %s\n", a, values, origValues)
                ok = false
            }

            // next Fibonacci number
            val c = a + b
            a = b
            b = c
        }
        if (!ok) {
            throw java.lang.AssertionError("failed")
        }
    }

    companion object {
        /** Creates an unexported schemaless provider type with builtin location.  */
        private fun makeProvider(): StarlarkProvider {
            return StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .buildWithIdentityToken(SymbolGenerator.createTransient().generate())
        }

        /** Creates an exported schemaless provider type with builtin location.  */
        private fun makeExportedProvider(): StarlarkProvider {
            val key: StarlarkProvider.Key =
                Key(
                    keyForBuild(Label.parseCanonicalUnchecked("//package:target")), "provider"
                )
            return StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN).buildExported(key)
        }

        /**
         * Creates an instance of a provider with the given values for fields f1 and f2. Either field
         * value may be null, in which case it is omitted.
         */
        private fun makeInfoWithF1F2Values(
            provider: StarlarkProvider?, v1: Any?, v2: Any?
        ): StarlarkInfo {
            val values: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.builder<String?, Any?>()
            if (v1 != null) {
                values.put("f1", v1)
            }
            if (v2 != null) {
                values.put("f2", v2)
            }
            return StarlarkInfo.create(provider, values.buildOrThrow())
        }
    }
}
