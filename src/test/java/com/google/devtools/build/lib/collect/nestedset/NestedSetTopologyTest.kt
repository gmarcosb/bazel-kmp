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

import com.google.common.truth.Truth
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests of NestedSet topology methods: toNode, getNonLeaves, getLeaves.  */
@RunWith(JUnit4::class)
class NestedSetTopologyTest {
    @org.junit.Test
    fun testToNode() {
        val inner: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").add("b").build()
        val outer: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().addTransitive(inner).add("c").build()
        val flat: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").add("b").add("c").build()

        assertThat(inner.toNode()).isEqualTo(inner.toNode())

        // Sets with different internal structure should have different nodes
        assertThat(flat.toNode()).isNotEqualTo(outer.toNode())

        // Decomposing a set, the transitive sets should be correctly identified.
        val succs: MutableList<NestedSet<String?>>? = outer.getNonLeaves()
        Truth.assertThat(succs).hasSize(1)
        val succ0: NestedSet<String?> = succs!!.get(0)
        assertThat(succ0.toNode()).isEqualTo(inner.toNode())
    }

    @org.junit.Test
    fun testGetLeaves() {
        val inner: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").add("b").build()
        val outer: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ()
                .add("c")
                .addTransitive(inner)
                .add("d")
                .add("e")
                .build()

        // The direct members should correctly be identified.
        assertThat(outer.getLeaves()).containsExactly("c", "d", "e")
    }

    @org.junit.Test
    fun testGetNonLeaves() {
        // The inner sets must have at least two elements, as NestedSet inlines singleton sets.
        val innerA: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a1").add("a2").build()
        val innerB: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("b1").add("b2").build()
        val innerC: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("c1").add("c2").build()
        val outer: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ()
                .add("x")
                .add("y")
                .addTransitive(innerA)
                .addTransitive(innerB)
                .addTransitive(innerC)
                .add("z")
                .build()

        // Decomposing the nested set should give us the correct list of transitive members.
        // Compare using strings as NestedSet.equals uses identity.
        assertThat(outer.getNonLeaves().toString())
            .isEqualTo(com.google.common.collect.ImmutableList.of<Any?>(innerA, innerB, innerC).toString())
    }

    @org.junit.Test
    fun testContents() {
        // Verify that the elements reachable from view are the correct ones, regardless if singletons
        // are inlined or not. Also verify that sets with at least two elements are never inlined.
        val singleA: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").build()
        val singleB: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("b").build()
        val multi: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("c1").add("c2").build()
        val outer: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ()
                .add("x")
                .add("y")
                .addTransitive(multi)
                .addTransitive(singleA)
                .addTransitive(singleB)
                .add("z")
                .build()

        Truth.assertThat(contents(outer)).containsExactly("a", "b", "c1", "c2", "x", "y", "z")
        Truth.assertThat(nodes(outer.getNonLeaves())).contains(multi.toNode())
    }

    @org.junit.Test
    fun testSplitFails() {
        val a: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").add("b").build()
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { a.splitIfExceedsMaximumSize(-100) })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { a.splitIfExceedsMaximumSize(1) })
    }

    @org.junit.Test
    fun testSplitNoSplit() {
        val a: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").add("b").build()
        assertThat(a.splitIfExceedsMaximumSize(2)).isSameInstanceAs(a)
        assertThat(a.splitIfExceedsMaximumSize(100)).isSameInstanceAs(a)
    }

    @org.junit.Test
    fun testSplit() {
        val a: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ()
                .addAll(mutableListOf<T?>("a", "b", "c"))
                .build()
        val v: NestedSet<String?> = a
        val s: NestedSet<String?> = v.splitIfExceedsMaximumSize(2)
        assertThat(s).isNotSameInstanceAs(v)
        Truth.assertThat(collectCheckSize<Any?>(s, 2)).containsExactly("a", "b", "c")
    }

    @org.junit.Test
    fun testRecursiveSplit() {
        val a: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ()
                .addAll(mutableListOf<T?>("a", "b", "c", "d", "e"))
                .build()
        val v: NestedSet<String?> = a
        val s: NestedSet<String?> = v.splitIfExceedsMaximumSize(2)
        assertThat(s).isNotSameInstanceAs(v)
        Truth.assertThat(collectCheckSize<Any?>(s, 2)).containsExactly("a", "b", "c", "d", "e")

        // Splitting may increment the graph depth, possibly more than once.
        assertThat(v.getApproxDepth()).isEqualTo(2)
        assertThat(s.getApproxDepth()).isEqualTo(4)
    }

    companion object {
        /** Naively traverse a view and collect all elements reachable.  */
        private fun contents(set: NestedSet<String?>): com.google.common.collect.ImmutableSet<String> {
            val builder: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.Builder<String?>()
            builder.addAll(set.getLeaves())
            for (nonleaf in set.getNonLeaves()) {
                builder.addAll(contents(nonleaf))
            }
            return builder.build()
        }

        private fun nodes(sets: MutableCollection<NestedSet<String?>>): com.google.common.collect.ImmutableSet<Any> {
            val builder: com.google.common.collect.ImmutableSet.Builder<Any?> =
                com.google.common.collect.ImmutableSet.Builder<Any?>()
            for (set in sets) {
                builder.add(set.toNode())
            }
            return builder.build()
        }

        private fun <T> collectCheckSize(set: NestedSet<T?>, maxSize: Int): MutableList<T?> {
            return collectCheckSize<T?>(java.util.ArrayList<T?>(), set, maxSize)
        }

        private fun <T> collectCheckSize(result: MutableList<T?>, set: NestedSet<T?>, maxSize: Int): MutableList<T?> {
            assertThat(set.getLeaves().size()).isAtMost(maxSize)
            assertThat(set.getNonLeaves().size()).isAtMost(maxSize)
            for (nonleaf in set.getNonLeaves()) {
                collectCheckSize<T?>(result, nonleaf, maxSize)
            }
            result.addAll(set.getLeaves())
            return result
        }
    }
}
