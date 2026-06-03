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
package net.starlark.java.eval

import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache.put
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.eval.Dict
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [StarlarkMutable].  */
@RunWith(JUnit4::class)
class StarlarkMutableTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListViewsCheckMutability() {
        val mutability: Mutability = Mutability.create("test")
        val list: StarlarkList<Any?> =
            StarlarkList.copyOf(
                mutability,
                com.google.common.collect.ImmutableList.of<E?>(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))
            )
        mutability.freeze()

        run {
            val it: MutableIterator<*> = list.iterator()
            it.next()
            org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                java.lang.UnsupportedOperationException::class.java,
                org.junit.function.ThrowingRunnable { it.remove() })
        }
        run {
            org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                java.lang.UnsupportedOperationException::class.java,
                list::listIterator
            )
        }
        run {
            org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                java.lang.UnsupportedOperationException::class.java,
                org.junit.function.ThrowingRunnable { list.listIterator(1) })
        }
        run {
            org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                java.lang.UnsupportedOperationException::class.java,
                org.junit.function.ThrowingRunnable { list.subList(1, 2) })
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictViewsCheckMutability() {
        val mutability: Mutability = Mutability.create("test")
        val dict: Dict<Any?, Any?> =
            Dict.copyOf(
                mutability,
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3), StarlarkInt.of(4)
                )
            )
        mutability.freeze()

        run {
            val it: MutableIterator<MutableMap.MutableEntry<Any?, Any?>> = dict.entrySet().iterator()
            val entry = it.next()
            org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                java.lang.UnsupportedOperationException::class.java,
                org.junit.function.ThrowingRunnable { entry.setValue(5) })
        }
        run {
            val it: MutableIterator<Any?> = dict.keySet().iterator()
            it.next()
            org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                java.lang.UnsupportedOperationException::class.java,
                org.junit.function.ThrowingRunnable { it.remove() })
        }
        run {
            val it: MutableIterator<Any?> = dict.values().iterator()
            it.next()
            org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
                java.lang.UnsupportedOperationException::class.java,
                org.junit.function.ThrowingRunnable { it.remove() })
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictBuilder() {
        // put
        val dict1: Dict<String?, String?> =
            Dict.< String, String>builder<kotlin.String?, kotlin.String?>()
        .put("one", "1")
            .put("two", "2.0")
            .put("two", "2") // overwrites previous entry
            .put("three", "3")
            .buildImmutable()
        assertThat(dict1.toString()).isEqualTo("{\"one\": \"1\", \"two\": \"2\", \"three\": \"3\"}")
        org.junit.Assert.assertThrows<T?>(EvalException::class.java, dict1::clearEntries) // immutable

        // putAll
        val dict2: Dict<String?, String?> =
            Dict.< String, String>builder<kotlin.String?, kotlin.String?>()
        .putAll(dict1)
            .putAll(com.google.common.collect.ImmutableMap.of<K?, V?>("four", "4", "five", "5"))
            .buildImmutable()
        assertThat(dict2.toString())
            .isEqualTo(
                "{\"one\": \"1\", \"two\": \"2\", \"three\": \"3\", \"four\": \"4\", \"five\": \"5\"}"
            )

        // builder reuse and mutability
        val builder: Dict.Builder<String?, String?> =
            Dict.< String, String>builder<kotlin.String?, kotlin.String?>().putAll(dict1)
        builder.put("three", "33") // overwrites previous entry
        val mu: Mutability = Mutability.create("test")
        val dict3: Dict<String?, String?> = builder.build(mu)
        dict3.putEntry("four", "4") // new entry
        dict3.putEntry("two", "22") // overwrites previous entry
        assertThat(dict3.toString())
            .isEqualTo("{\"one\": \"1\", \"two\": \"22\", \"three\": \"33\", \"four\": \"4\"}")
        mu.close()
        org.junit.Assert.assertThrows<T?>(EvalException::class.java, dict1::clearEntries) // frozen
        builder.put("five", "5") // keep building
        val dict4: Dict<String?, String?> = builder.buildImmutable()
        assertThat(dict4.toString())
            .isEqualTo("{\"one\": \"1\", \"two\": \"2\", \"three\": \"33\", \"five\": \"5\"}")
        assertThat(dict3.toString())
            .isEqualTo(
                "{\"one\": \"1\", \"two\": \"22\", \"three\": \"33\", \"four\": \"4\"}"
            ) // unchanged
    }
}
