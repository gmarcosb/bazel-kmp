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
package com.google.devtools.build.lib.concurrent

import com.google.devtools.build.lib.concurrent.PooledInterner.Pool
import org.junit.Test

/** Unit tests for [PooledInterner] class, with and without global pool set.  */
@RunWith(JUnit4::class)
class PooledInternerTest {
    private var pool: Pool<ObjectForInternerTests?>? = null

    private val interner: PooledInterner<ObjectForInternerTests?> = object : PooledInterner() {
        protected val pool: Pool<ObjectForInternerTests?>?
    }

    private fun createInterned(arg: String): ObjectForInternerTests {
        return interner.intern(ObjectForInternerTests(arg))
    }

    @Test
    fun pooledInternerInterner_noGlobalPoolTestIntern() {
        val keyToIntern1 = createInterned( /* arg= */"HelloWorld")

        // Interning a duplicate instance will result the same instance to be returned.
        Truth.assertThat(createInterned( /* arg= */"HelloWorld")).isSameInstanceAs(keyToIntern1)
    }

    @Test
    fun pooledInternerInterner_noGlobalPoolTestRemoval() {
        val keyToIntern1 = createInterned( /* arg= */"HelloWorld")

        Truth.assertThat(createInterned( /* arg= */"HelloWorld")).isSameInstanceAs(keyToIntern1)

        // Remove one instance from the interner and re-intern a duplicate one. The newly interned
        // instance is different from the previous one, which confirms that the previous interned
        // instance has already been successfully removed from the interner.
        interner.removeWeak(keyToIntern1)
        Truth.assertThat(createInterned( /* arg= */"HelloWorld")).isNotSameInstanceAs(keyToIntern1)
    }

    @Test
    fun pooledInternerInterner_withGlobalPool() {
        val keyInPool = ObjectForInternerTests( /* arg= */"FooBar")
        pool =
            Pool? { sample ->
            if (sample.arg.equals("FooBar")) {
                return@Pool keyInPool
            } else {
                return@Pool interner.weakIntern(sample)
            }
        }

        // If interned instance already exists in the pool, expect to get the pooled instance.
        Truth.assertThat(createInterned( /* arg= */"FooBar")).isSameInstanceAs(keyInPool)

        // If interned instance does not exist in the pool, expect it to be weak interned. So interning
        // a duplicate instance will result the same instance to be returned.
        val keyToIntern1 = createInterned( /* arg= */"HelloWorld")
        Truth.assertThat(createInterned( /* arg= */"HelloWorld")).isSameInstanceAs(keyToIntern1)
    }

    @Test
    fun pooledInterner_sizeOfMap_afterExplicitRemoval() {
        val keyInPool = ObjectForInternerTests( /* arg= */"FooBar")
        pool =
            Pool? { sample ->
            if (sample.arg.equals("FooBar")) {
                return@Pool keyInPool
            } else {
                return@Pool interner.weakIntern(sample)
            }
        }

        val unused = createInterned("FooBar")
        val weakInterned = createInterned("BazQux")

        // Only BazQux is in the interner
        assertThat(interner.size()).isEqualTo(1)

        interner.removeWeak(weakInterned)

        assertThat(interner.size()).isEqualTo(0)
    }

    @Test
    fun pooledInterner_sizeOfMapReduced_withShrinkAll() {
        var unusedKeyToIntern = createInterned( /* arg= */"HelloWorld")
        // If interned instance already exists in the pool, expect to get the pooled instance.
        Truth.assertThat(createInterned("HelloWorld")).isSameInstanceAs(unusedKeyToIntern)
        assertThat(interner.size()).isEqualTo(1)

        PooledInterner.shrinkAll()
        // Does nothing, because the reference is still held.
        assertThat(interner.size()).isEqualTo(1)

        // Delete the only reference to interned object, and run GC. Without GC, the assertion will
        // fail.
        unusedKeyToIntern = null
        System.gc()
        PooledInterner.shrinkAll()

        assertThat(interner.size()).isEqualTo(0)
    }

    internal class ObjectForInternerTests private constructor(private val arg: String) {
        override fun hashCode(): Int {
            return arg.hashCode()
        }

        override fun equals(obj: Any?): Boolean {
            return obj is ObjectForInternerTests
                    && arg == obj.arg
        }
    }
}
