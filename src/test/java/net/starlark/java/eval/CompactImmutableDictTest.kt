// Copyright 2026 The Bazel Authors. All rights reserved.
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
package net.starlark.java.eval

import com.google.common.testing.EqualsTester
import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache.put
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import com.google.devtools.common.options.testing.ConverterTester.addEqualityGroup
import net.starlark.java.eval.Dict
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.StarlarkSemantics
import net.starlark.java.eval.StarlarkThread
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.HashMap
import java.util.LinkedHashMap

/** Tests for [CompactImmutableDict].  */
@RunWith(JUnit4::class)
class CompactImmutableDictTest {
    @org.junit.Test
    fun emptyDict() {
        val dict: CompactImmutableDict<String?, Int?> =
            CompactImmutableDict.copyOf(com.google.common.collect.ImmutableMap.of<K?, V?>())
        performUniversalChecks(dict)

        assertThat(dict.isEmpty()).isTrue()
        assertThat(dict.size()).isEqualTo(0)
        assertThat(dict.get("a")).isNull()
        assertThat(dict.containsKey("a")).isFalse()
        assertThat(dict.containsValue(1)).isFalse()

        assertThat(dict.keySet()).isEmpty()
        assertThat(dict.values()).isEmpty()
        assertThat(dict.entrySet()).isEmpty()

        dict.forEach(
            { k, v ->
                throw java.lang.AssertionError("Should not be called")
            })
    }

    @org.junit.Test
    fun singletonDict() {
        val dict: CompactImmutableDict<String?, Int?> =
            CompactImmutableDict.copyOf(com.google.common.collect.ImmutableMap.of<K?, V?>("a", 1))
        performUniversalChecks(dict)

        assertThat(dict.isEmpty()).isFalse()
        assertThat(dict.size()).isEqualTo(1)
        assertThat(dict.get("a")).isEqualTo(1)
        assertThat(dict.get("b")).isNull()
        assertThat(dict.containsKey("a")).isTrue()
        assertThat(dict.containsKey("b")).isFalse()
        assertThat(dict.containsValue(1)).isTrue()
        assertThat(dict.containsValue(2)).isFalse()

        assertThat(dict.keySet()).containsExactly("a")
        assertThat(dict.values()).containsExactly(1)
        assertThat(dict.entrySet()).hasSize(1)

        val visited: MutableMap<String?, Int?> = HashMap<String?, Int?>()
        dict.forEach({ key: K?, value: V? -> visited.put(key, value) })
        Truth.assertThat(visited).containsExactly("a", 1)
    }

    @org.junit.Test
    fun doubletonDict() {
        val dict: CompactImmutableDict<String?, Int?> =
            CompactImmutableDict.copyOf(com.google.common.collect.ImmutableMap.of<K?, V?>("a", 1, "b", 2))
        performUniversalChecks(dict)

        assertThat(dict.isEmpty()).isFalse()
        assertThat(dict.size()).isEqualTo(2)
        assertThat(dict.get("a")).isEqualTo(1)
        assertThat(dict.get("b")).isEqualTo(2)
        assertThat(dict.get("c")).isNull()
        assertThat(dict.containsKey("a")).isTrue()
        assertThat(dict.containsKey("b")).isTrue()
        assertThat(dict.containsKey("c")).isFalse()
        assertThat(dict.containsValue(1)).isTrue()
        assertThat(dict.containsValue(2)).isTrue()
        assertThat(dict.containsValue(3)).isFalse()

        assertThat(dict.keySet()).containsExactly("a", "b").inOrder()
        assertThat(dict.values()).containsExactly(1, 2).inOrder()
        assertThat(dict.entrySet()).hasSize(2)

        val visited: MutableMap<String?, Int?> = LinkedHashMap<String?, Int?>()
        dict.forEach({ key: K?, value: V? -> visited.put(key, value) })
        Truth.assertThat(visited).containsExactly("a", 1, "b", 2).inOrder()
    }

    @org.junit.Test
    fun linearDict() {
        val source: MutableMap<String?, Int?> = LinkedHashMap<String?, Int?>()
        for (i in 0..5) {
            source.put("k" + i, i)
        }
        val dict: CompactImmutableDict<String?, Int?> = CompactImmutableDict.copyOf(source)
        performUniversalChecks(dict)

        assertThat(dict.isEmpty()).isFalse()
        assertThat(dict.size()).isEqualTo(6)
        assertThat(dict.get("k3")).isEqualTo(3)
        assertThat(dict.get("k9")).isNull()
        assertThat(dict.containsKey("k3")).isTrue()
        assertThat(dict.containsKey("k9")).isFalse()
        assertThat(dict.containsValue(3)).isTrue()
        assertThat(dict.containsValue(9)).isFalse()

        assertThat(dict.keySet()).containsExactly("k0", "k1", "k2", "k3", "k4", "k5").inOrder()
        assertThat(dict.values()).containsExactly(0, 1, 2, 3, 4, 5).inOrder()

        val visited: MutableMap<String?, Int?> = LinkedHashMap<String?, Int?>()
        dict.forEach({ key: K?, value: V? -> visited.put(key, value) })
        Truth.assertThat(visited).containsExactlyEntriesIn(source).inOrder()
    }

    @org.junit.Test
    fun hashDict() {
        val source: MutableMap<String?, Int?> = LinkedHashMap<String?, Int?>()
        for (i in 0..11) {
            source.put("k" + i, i)
        }
        val dict: CompactImmutableDict<String?, Int?> = CompactImmutableDict.copyOf(source)
        performUniversalChecks(dict)

        assertThat(dict.isEmpty()).isFalse()
        assertThat(dict.size()).isEqualTo(12)
        assertThat(dict.get("k7")).isEqualTo(7)
        assertThat(dict.get("k99")).isNull()
        assertThat(dict.containsKey("k7")).isTrue()
        assertThat(dict.containsKey("k99")).isFalse()
        assertThat(dict.containsValue(7)).isTrue()
        assertThat(dict.containsValue(99)).isFalse()

        assertThat(dict.keySet())
            .containsExactly("k0", "k1", "k2", "k3", "k4", "k5", "k6", "k7", "k8", "k9", "k10", "k11")
            .inOrder()
        assertThat(dict.values()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11).inOrder()

        val visited: MutableMap<String?, Int?> = LinkedHashMap<String?, Int?>()
        dict.forEach({ key: K?, value: V? -> visited.put(key, value) })
        Truth.assertThat(visited).containsExactlyEntriesIn(source).inOrder()
    }

    @org.junit.Test
    fun iteratorNoSuchElementException() {
        val dict: CompactImmutableDict<String?, Int?> =
            CompactImmutableDict.copyOf(com.google.common.collect.ImmutableMap.of<K?, V?>("a", 1, "b", 2, "c", 3))
        val it: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            dict.entrySet().iterator()

        assertThat(it.hasNext()).isTrue()
        it.next()
        assertThat(it.hasNext()).isTrue()
        it.next()
        assertThat(it.hasNext()).isTrue()
        it.next()
        assertThat(it.hasNext()).isFalse()

        org.junit.Assert.assertThrows<java.util.NoSuchElementException?>(
            java.util.NoSuchElementException::class.java,
            it::next
        )
    }

    @org.junit.Test
    fun hashCollisions() {
        class BadHashKey internal constructor(private val `val`: String) {
            override fun hashCode(): Int {
                return 42 // Force every key to hash to the same primary slot!
            }

            override fun equals(obj: Any?): Boolean {
                return obj is BadHashKey && obj.`val` == `val`
            }
        }

        val source: MutableMap<BadHashKey?, Int?> = LinkedHashMap<BadHashKey?, Int?>()
        for (i in 0..8) {
            source.put(BadHashKey("key" + i), i)
        }

        val dict: CompactImmutableDict<BadHashKey?, Int?> = CompactImmutableDict.copyOf(source)

        // Verify all can be retrieved correctly (requires probing since every key collides!)
        for (i in 0..8) {
            val k = BadHashKey("key" + i)
            assertThat(dict.get(k)).isEqualTo(i)
            assertThat(dict.containsKey(k)).isTrue()
        }

        // Verify absent key lookup works
        assertThat(dict.get(BadHashKey("absent"))).isNull()
        assertThat(dict.containsKey(BadHashKey("absent"))).isFalse()
    }

    @org.junit.Test
    fun equalsAndHashCode() {
        EqualsTester() // Empty
            .addEqualityGroup(*createMapEqualityGroup(com.google.common.collect.ImmutableMap.of<String?, String?>())) // Singleton
            .addEqualityGroup(
                *createMapEqualityGroup(
                    com.google.common.collect.ImmutableMap.of<String?, String?>(
                        "a",
                        "1"
                    )
                )
            )
            .addEqualityGroup(
                *createMapEqualityGroup(
                    com.google.common.collect.ImmutableMap.of<String?, String?>(
                        "b",
                        "2"
                    )
                )
            ) // Doubleton
            .addEqualityGroup(
                *createMapEqualityGroup(
                    com.google.common.collect.ImmutableMap.of<String?, String?>(
                        "a",
                        "1",
                        "b",
                        "2"
                    )
                )
            )
            .addEqualityGroup(
                *createMapEqualityGroup(
                    com.google.common.collect.ImmutableMap.of<String?, String?>(
                        "c",
                        "3",
                        "d",
                        "4"
                    )
                )
            ) // Linear
            .addEqualityGroup(
                *createMapEqualityGroup(
                    com.google.common.collect.ImmutableMap.of<String?, String?>(
                        "a", "1",
                        "b", "2",
                        "c", "3"
                    )
                )
            )
            .addEqualityGroup(
                *createMapEqualityGroup(
                    com.google.common.collect.ImmutableMap.of<String?, String?>(
                        "d", "4",
                        "e", "5",
                        "f", "6"
                    )
                )
            ) // Hash
            .addEqualityGroup(
                *createMapEqualityGroup(
                    com.google.common.collect.ImmutableMap.of<String?, String?>(
                        "a", "1",
                        "b", "2",
                        "c", "3",
                        "d", "4",
                        "e", "5",
                        "f", "6",
                        "g", "7",
                        "h", "8",
                        "i", "9"
                    )
                )
            )
            .addEqualityGroup(
                *createMapEqualityGroup(
                    com.google.common.collect.ImmutableMap.of<String?, String?>(
                        "j", "10",
                        "k", "11",
                        "l", "12",
                        "m", "13",
                        "n", "14",
                        "o", "15",
                        "p", "16",
                        "q", "17",
                        "r", "18",
                        "s", "19"
                    )
                )
            )
            .testEquals()
    }

    companion object {
        private fun createMapEqualityGroup(m: com.google.common.collect.ImmutableMap<String?, String?>): Array<Any?> {
            return arrayOf<Any?>( // ImmutableMap
                m,  // ImmutableMap with reverse order
                com.google.common.collect.ImmutableMap.copyOf<String?, String?>(
                    m.entries.asList().reverse()
                ),  // MutableDict
                Dict.copyOf(Mutability.create(), m),  // ImmutableMapBackedDict
                Dict.immutableCopyOf(m),  // CompactImmutableDict
                CompactImmutableDict.copyOf(m)
            )
        }

        private fun performUniversalChecks(dict: Dict<String?, Int?>) {
            assertImmutable(dict)
            assertStarlarkMethodsRespectGivenMutablility(dict)
            assertNullSafeQueries(dict)
        }

        private fun assertImmutable(dict: Dict<String?, Int?>) {
            org.junit.Assert.assertThrows<T?>(
                EvalException::class.java,
                org.junit.function.ThrowingRunnable { dict.putEntry("b", 2) })
            org.junit.Assert.assertThrows<T?>(
                EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    dict.putEntries(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            "b",
                            2
                        )
                    )
                })
            org.junit.Assert.assertThrows<T?>(EvalException::class.java, dict::clearEntries)
            org.junit.Assert.assertThrows<T?>(
                EvalException::class.java,
                org.junit.function.ThrowingRunnable { dict.pop("a", null, null) })
            org.junit.Assert.assertThrows<T?>(EvalException::class.java, dict::popitem)
            org.junit.Assert.assertThrows<T?>(
                EvalException::class.java,
                org.junit.function.ThrowingRunnable { dict.setdefault("b", 2) })

            org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                java.lang.UnsupportedOperationException::class.java,
                org.junit.function.ThrowingRunnable { dict.put("b", 2) })
            org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                java.lang.UnsupportedOperationException::class.java,
                org.junit.function.ThrowingRunnable {
                    dict.putAll(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            "b",
                            2
                        )
                    )
                })
            org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                java.lang.UnsupportedOperationException::class.java,
                org.junit.function.ThrowingRunnable { dict.remove("a") })
            org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                java.lang.UnsupportedOperationException::class.java,
                dict::clear
            )
        }

        private fun assertStarlarkMethodsRespectGivenMutablility(dict: Dict<*, *>) {
            val mu: Mutability? = Mutability.create()
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            assertThat(dict.keys(thread).mutability()).isSameInstanceAs(mu)
            assertThat(dict.values0(thread).mutability()).isSameInstanceAs(mu)
            assertThat(dict.items(thread).mutability()).isSameInstanceAs(mu)
        }

        private fun assertNullSafeQueries(dict: Dict<*, *>) {
            assertThat(dict.containsKey(null)).isFalse()
            assertThat(dict.containsValue(null)).isFalse()
            assertThat(dict.get(null)).isNull()
            assertThat(dict.keySet().contains(null)).isFalse()
            assertThat(dict.values().contains(null)).isFalse()
            assertThat(dict.entrySet().contains(null)).isFalse()
            assertThat(
                dict.entrySet().contains(com.google.common.collect.Maps.immutableEntry<K?, V?>(null, null))
            ).isFalse()
            if (!dict.isEmpty()) {
                val presentKeyNullValueEntry: MutableMap.MutableEntry<K?, V?> =
                    com.google.common.collect.Maps.immutableEntry<K?, V?>(dict.iterator().next(), null)
                assertThat(dict.entrySet().contains(presentKeyNullValueEntry)).isFalse()
            }
        }
    }
}
