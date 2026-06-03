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
package com.google.devtools.build.lib.collect

import com.google.devtools.build.lib.collect.PathFragmentPrefixTrie.PathFragmentAlreadyAddedException

/** Unit tests for [PathFragmentPrefixTrie].  */
@RunWith(JUnit4::class)
class PathFragmentPrefixTrieTest {
    @org.junit.Test
    fun testEmpty() {
        val trie: PathFragmentPrefixTrie = PathFragmentPrefixTrie()

        assertThat(trie.includes(PathFragment.create("a"))).isFalse()
        assertThat(trie.includes(PathFragment.create("a/b"))).isFalse()
    }

    @org.junit.Test
    fun testEmptyPathFragment_isDisallowedForPut() {
        val trie: PathFragmentPrefixTrie = PathFragmentPrefixTrie()

        val e: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { trie.put(PathFragment.EMPTY_FRAGMENT, true) })
        Truth.assertThat(e).hasMessageThat().contains("path fragment cannot be the empty fragment.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleInclusions() {
        val trie: PathFragmentPrefixTrie = PathFragmentPrefixTrie()

        trie.put(PathFragment.create("a"), true)
        trie.put(PathFragment.create("b"), true)

        assertThat(trie.includes(PathFragment.EMPTY_FRAGMENT)).isFalse()
        assertThat(trie.includes(PathFragment.create("a"))).isTrue()
        assertThat(trie.includes(PathFragment.create("a/b"))).isTrue()
        assertThat(trie.includes(PathFragment.create("a/b/c"))).isTrue()
        assertThat(trie.includes(PathFragment.create("b"))).isTrue()
        assertThat(trie.includes(PathFragment.create("c"))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleExclusions() {
        val trie: PathFragmentPrefixTrie = PathFragmentPrefixTrie()

        trie.put(PathFragment.create("a"), true)
        trie.put(PathFragment.create("a/b"), false)
        trie.put(PathFragment.create("a/b/c"), true)

        assertThat(trie.includes(PathFragment.create("a"))).isTrue()
        assertThat(trie.includes(PathFragment.create("a/b"))).isFalse()
        assertThat(trie.includes(PathFragment.create("a/b/c"))).isTrue()
        assertThat(trie.includes(PathFragment.create("a/b/d"))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAncestors() {
        val trie: PathFragmentPrefixTrie = PathFragmentPrefixTrie()

        trie.put(PathFragment.create("a/b/c"), true)
        assertThat(trie.includes(PathFragment.create("a"))).isFalse()
        assertThat(trie.includes(PathFragment.create("a/b"))).isFalse()
        assertThat(trie.includes(PathFragment.create("a/b/c"))).isTrue() // toggled explicitly
        assertThat(trie.includes(PathFragment.create("a/b/c/d"))).isTrue() // toggled automatically

        trie.put(PathFragment.create("a/b/c/d"), false)
        assertThat(trie.includes(PathFragment.create("a"))).isFalse()
        assertThat(trie.includes(PathFragment.create("a/b"))).isFalse()
        assertThat(trie.includes(PathFragment.create("a/b/c"))).isTrue()
        assertThat(trie.includes(PathFragment.create("a/b/c/d"))).isFalse() // toggled explicitly

        trie.put(PathFragment.create("a"), true)

        assertThat(trie.includes(PathFragment.create("a"))).isTrue() // toggled explicitly
        assertThat(trie.includes(PathFragment.create("a/b"))).isTrue() // toggled automatically
        assertThat(trie.includes(PathFragment.create("a/b/c"))).isTrue()
        assertThat(trie.includes(PathFragment.create("a/b/c/d"))).isFalse()

        trie.put(PathFragment.create("a/b"), false)

        assertThat(trie.includes(PathFragment.create("a"))).isTrue()
        assertThat(trie.includes(PathFragment.create("a/b"))).isFalse() // toggled explicitly
        assertThat(trie.includes(PathFragment.create("a/b/c"))).isTrue()
        assertThat(trie.includes(PathFragment.create("a/b/c/d"))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSamePathFragmentIncludedAndExcluded_isDisallowed() {
        val trie: PathFragmentPrefixTrie = PathFragmentPrefixTrie()

        trie.put(PathFragment.create("a/b/c"), false)
        var e: PathFragmentAlreadyAddedException? =
            org.junit.Assert.assertThrows<T?>(
                PathFragmentAlreadyAddedException::class.java,
                org.junit.function.ThrowingRunnable { trie.put(PathFragment.create("a/b/c"), true) })
        assertThat(e)
            .hasMessageThat()
            .contains(
                "a/b/c has already been explicitly marked as excluded. Current state: [included:"
                        + " [], excluded: [a/b/c]]"
            )

        trie.put(PathFragment.create("a/b"), true)

        e =
            org.junit.Assert.assertThrows<T?>(
                PathFragmentAlreadyAddedException::class.java,
                org.junit.function.ThrowingRunnable { trie.put(PathFragment.create("a/b"), false) })
        assertThat(e)
            .hasMessageThat()
            .contains(
                "a/b has already been explicitly marked as included. Current state: [included:"
                        + " [a/b], excluded: [a/b/c]]"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepr() {
        val trie: PathFragmentPrefixTrie = PathFragmentPrefixTrie()

        trie.put(PathFragment.create("a"), true)
        trie.put(PathFragment.create("a/b"), false)
        trie.put(PathFragment.create("a/b/c"), true)
        trie.put(PathFragment.create("a/b/d"), false)
        trie.put(PathFragment.create("e"), true)

        assertThat(trie.toString()).isEqualTo("[included: [a, a/b/c, e], excluded: [a/b, a/b/d]]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHasIncludedPaths() {
        val trie: PathFragmentPrefixTrie = PathFragmentPrefixTrie()

        assertThat(trie.hasIncludedPaths()).isFalse()

        trie.put(PathFragment.create("a"), true)
        assertThat(trie.hasIncludedPaths()).isTrue()
    }
}
