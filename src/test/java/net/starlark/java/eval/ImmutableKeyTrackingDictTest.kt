// Copyright 2021 The Bazel Authors. All rights reserved.
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

import net.starlark.java.eval.Dict.ImmutableKeyTrackingDict

/** Tests for [ImmutableKeyTrackingDict].  */
@RunWith(JUnit4::class)
class ImmutableKeyTrackingDictTest {
    private val dict: ImmutableKeyTrackingDict<String?, StarlarkInt?> =
        Dict.< String, StarlarkInt>builder<kotlin.String?, StarlarkInt?>()
    .put("a", StarlarkInt.of(1))
    .put("b", StarlarkInt.of(2))
    .put("c", StarlarkInt.of(3))
    .put("d", StarlarkInt.of(4))
    .buildImmutableWithKeyTracking()

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val isImmutable: Unit
        get() {
            assertThat(dict.mutability()).isEqualTo(Mutability.IMMUTABLE)
            org.junit.Assert.assertThrows<T?>(
                EvalException::class.java,
                org.junit.function.ThrowingRunnable { dict.putEntry("e", StarlarkInt.of(5)) })
            assertThat(dict.containsKey("e")).isFalse()
            assertThat(dict.getAccessedKeys()).isEmpty()
        }

    @org.junit.Test
    fun containsKey_tracksPresentKeys() {
        assertThat(dict.containsKey("a")).isTrue()
        assertThat(dict.containsKey("b")).isTrue()
        assertThat(dict.getAccessedKeys()).containsExactly("a", "b")
    }

    @org.junit.Test
    fun containsKey_ignoresAbsentKeys() {
        assertThat(dict.containsKey("absent")).isFalse()
        assertThat(dict.containsKey(Any())).isFalse()
        assertThat(dict.getAccessedKeys()).isEmpty()
    }

    @org.junit.Test
    fun get_tracksPresentKeys() {
        assertThat(dict.get("a")).isEqualTo(StarlarkInt.of(1))
        assertThat(dict.get("b")).isEqualTo(StarlarkInt.of(2))
        assertThat(dict.getAccessedKeys()).containsExactly("a", "b")
    }

    @org.junit.Test
    fun get_ignoresAbsentKeys() {
        assertThat(dict.get("absent")).isNull()
        assertThat(dict.get(Any())).isNull()
        assertThat(dict.getAccessedKeys()).isEmpty()
    }

    @org.junit.Test
    fun keySet_reportsAllKeys() {
        assertThat(dict.keySet()).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(dict.getAccessedKeys()).isEqualTo(dict.keySet())
    }

    @org.junit.Test
    fun entrySet_reportsAllKeys() {
        assertThat(dict.entrySet()).hasSize(4)
        assertThat(dict.getAccessedKeys()).isEqualTo(dict.keySet())
    }

    @org.junit.Test
    fun iteration_reportsAllKeys() {
        for (key in dict) {
            Truth.assertThat(key).isAnyOf("a", "b", "c", "d")
        }
        assertThat(dict.getAccessedKeys()).isEqualTo(dict.keySet())
    }

    @org.junit.Test
    fun repr_reportsAllKeys() {
        val sb: java.lang.StringBuilder = java.lang.StringBuilder()
        dict.repr(Printer(sb), StarlarkSemantics.DEFAULT)
        Truth.assertThat(sb.toString()).isEqualTo("{\"a\": 1, \"b\": 2, \"c\": 3, \"d\": 4}")
        assertThat(dict.getAccessedKeys()).isEqualTo(dict.keySet())
    }

    @org.junit.Test
    fun mutableCopy_reportsAllKeys() {
        val copy: MutableMap<String?, StarlarkInt?>? = Dict.copyOf(Mutability.create("mutable"), dict)
        Truth.assertThat(copy).isNotSameInstanceAs(dict)
        assertThat(dict.getAccessedKeys()).isEqualTo(dict.keySet())
    }
}
