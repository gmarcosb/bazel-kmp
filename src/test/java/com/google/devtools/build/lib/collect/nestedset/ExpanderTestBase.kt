// Copyright 2014 The Bazel Authors. All rights reserved.
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

/**
 * Base class for tests of [NestedSet] iteration behavior.
 * 
 * 
 * This class provides test cases for representative nested set structures; the expected results
 * must be provided by overriding the corresponding methods.
 */
abstract class ExpanderTestBase {
    /**
     * Returns the type of the expander under test.
     */
    protected abstract fun expanderOrder(): Order?

    @org.junit.Test
    fun simple() {
        val s: NestedSet<String?> = prepareBuilder("c", "a", "b").build()

        assertThat(s.toList()).isEqualTo(simpleResult())
        assertSetContents(simpleResult(), s)
    }

    @org.junit.Test
    fun simpleNoDuplicates() {
        val s: NestedSet<String?> = prepareBuilder("c", "a", "a", "a", "b").build()

        assertThat(s.toList()).isEqualTo(simpleResult())
        assertSetContents(simpleResult(), s)
    }

    @org.junit.Test
    fun nesting() {
        val subset: NestedSet<String?>? = prepareBuilder("c", "a", "e").build()
        val s: NestedSet<String?> = prepareBuilder("b", "d").addTransitive(subset).build()

        assertSetContents(nestedResult(), s)
    }

    @org.junit.Test
    fun builderReuse() {
        val builder: NestedSetBuilder<String?> = prepareBuilder()
        assertSetContents(mutableListOf<String?>(), builder.build())

        builder.add("b")
        assertSetContents(com.google.common.collect.ImmutableList.of<String?>("b"), builder.build())

        builder.addAll(com.google.common.collect.ImmutableList.of<E?>("d"))
        val expected: MutableList<String?>? = prepareBuilder("b", "d").build().toList()
        assertSetContents(expected, builder.build())

        val child: NestedSet<String?>? = prepareBuilder("c", "a", "e").build()
        builder.addTransitive(child)
        assertSetContents(nestedResult(), builder.build())
    }

    @org.junit.Test
    fun builderChaining() {
        val s: NestedSet<String?> =
            prepareBuilder().add("b").addAll(com.google.common.collect.ImmutableList.of<E?>("d"))
                .addTransitive(prepareBuilder("c", "a", "e").build()).build()
        assertSetContents(nestedResult(), s)
    }

    @org.junit.Test
    fun addAllOrdering() {
        val s1: NestedSet<String?> = prepareBuilder().add("a").add("c").add("b").build()
        val s2: NestedSet<String?> =
            prepareBuilder().addAll(com.google.common.collect.ImmutableList.of<E?>("a", "c", "b")).build()

        assertCollectionsEqual(s1.toList(), s2.toList())
    }

    @org.junit.Test
    fun mixedAddAllOrdering() {
        val s1: NestedSet<String?> = prepareBuilder().add("a").add("b").add("c").add("d").build()
        val s2: NestedSet<String?> =
            prepareBuilder().add("a").addAll(com.google.common.collect.ImmutableList.of<E?>("b", "c")).add("d")
                .build()

        assertCollectionsEqual(s1.toList(), s2.toList())
    }

    @org.junit.Test
    fun transitiveDepsHandledSeparately() {
        val subset: NestedSet<String?>? = prepareBuilder("c", "a", "e").build()
        val b: NestedSetBuilder<String?> = prepareBuilder()
        // The fact that we add the transitive subset between the add("b") and add("d") calls should
        // not change the result.
        b.add("b")
        b.addTransitive(subset)
        b.add("d")
        val s: NestedSet<String?> = b.build()

        assertSetContents(nestedResult(), s)
    }

    @org.junit.Test
    fun nestingNoDuplicates() {
        val subset: NestedSet<String?>? = prepareBuilder("c", "a", "e").build()
        val s: NestedSet<String?> = prepareBuilder("b", "d", "e").addTransitive(subset).build()

        assertSetContents(nestedDuplicatesResult(), s)
    }

    @org.junit.Test
    fun chain() {
        val c: NestedSet<String?>? = prepareBuilder("c").build()
        val b: NestedSet<String?>? = prepareBuilder("b").addTransitive(c).build()
        val a: NestedSet<String?> = prepareBuilder("a").addTransitive(b).build()

        assertSetContents(chainResult(), a)
    }

    @org.junit.Test
    fun diamond() {
        val d: NestedSet<String?>? = prepareBuilder("d").build()
        val c: NestedSet<String?>? = prepareBuilder("c").addTransitive(d).build()
        val b: NestedSet<String?>? = prepareBuilder("b").addTransitive(d).build()
        val a: NestedSet<String?> = prepareBuilder("a").addTransitive(b).addTransitive(c).build()

        assertSetContents(diamondResult(), a)
    }

    @org.junit.Test
    fun extendedDiamond() {
        val d: NestedSet<String?>? = prepareBuilder("d").build()
        val e: NestedSet<String?>? = prepareBuilder("e").build()
        val b: NestedSet<String?>? = prepareBuilder("b").addTransitive(d).addTransitive(e).build()
        val c: NestedSet<String?>? = prepareBuilder("c").addTransitive(e).addTransitive(d).build()
        val a: NestedSet<String?> = prepareBuilder("a").addTransitive(b).addTransitive(c).build()
        assertSetContents(extendedDiamondResult(), a)
    }

    @org.junit.Test
    fun extendedDiamondRightArm() {
        val d: NestedSet<String?>? = prepareBuilder("d").build()
        val e: NestedSet<String?>? = prepareBuilder("e").build()
        val b: NestedSet<String?>? = prepareBuilder("b").addTransitive(d).addTransitive(e).build()
        val c2: NestedSet<String?>? = prepareBuilder("c2").addTransitive(e).addTransitive(d).build()
        val c: NestedSet<String?>? = prepareBuilder("c").addTransitive(c2).build()
        val a: NestedSet<String?> = prepareBuilder("a").addTransitive(b).addTransitive(c).build()
        assertSetContents(extendedDiamondRightArmResult(), a)
    }

    @org.junit.Test
    fun orderConflict() {
        val child1: NestedSet<String?>? = prepareBuilder("a", "b").build()
        val child2: NestedSet<String?>? = prepareBuilder("b", "a").build()
        val parent: NestedSet<String?> = prepareBuilder().addTransitive(child1).addTransitive(child2).build()
        assertSetContents(orderConflictResult(), parent)
    }

    @org.junit.Test
    fun orderConflictNested() {
        val a: NestedSet<String?>? = prepareBuilder("a").build()
        val b: NestedSet<String?>? = prepareBuilder("b").build()
        val child1: NestedSet<String?>? = prepareBuilder().addTransitive(a).addTransitive(b).build()
        val child2: NestedSet<String?>? = prepareBuilder().addTransitive(b).addTransitive(a).build()
        val parent: NestedSet<String?> = prepareBuilder().addTransitive(child1).addTransitive(child2).build()
        assertSetContents(orderConflictResult(), parent)
    }

    @get:org.junit.Test
    val orderingEmpty: Unit
        get() {
            val s: NestedSet<String?> = prepareBuilder().build()
            assertThat(s.isEmpty()).isTrue()
            assertThat(s.getOrder()).isEqualTo(expanderOrder())
        }

    @get:org.junit.Test
    val ordering: Unit
        get() {
            val s: NestedSet<String?> = prepareBuilder("a", "b").build()
            assertThat(s.isEmpty()).isFalse()
            assertThat(s.getOrder()).isEqualTo(expanderOrder())
        }

    @org.junit.Test
    fun nestingValidation() {
        for (ordering in Order.values()) {
            val a: NestedSet<String?>? = prepareBuilder("a", "b").build()
            val b: NestedSetBuilder<String?> = NestedSetBuilder.newBuilder(ordering)
            try {
                b.addTransitive(a)
                if (ordering !== expanderOrder() && ordering !== Order.STABLE_ORDER) {
                    org.junit.Assert.fail() // An exception was expected.
                }
            } catch (e: java.lang.IllegalArgumentException) {
                if (ordering === expanderOrder() || ordering === Order.STABLE_ORDER) {
                    org.junit.Assert.fail() // No exception was expected.
                }
            }
        }
    }

    private fun prepareBuilder(vararg directMembers: String?): NestedSetBuilder<String?> {
        val builder: NestedSetBuilder<String?> = NestedSetBuilder.newBuilder(expanderOrder())
        builder.addAll(com.google.common.collect.Lists.< E > newArrayList < E ? > (directMembers))
        return builder
    }

    protected fun assertSetContents(expected: MutableList<String?>?, set: NestedSet<String?>) {
        Truth.assertThat(java.util.ArrayList<Any?>(set.toList())).isEqualTo(expected)
        Truth.assertThat(java.util.ArrayList<Any?>(set.toSet())).isEqualTo(expected)
    }

    protected fun assertCollectionsEqual(
        expected: MutableCollection<String?>, actual: MutableCollection<String?>
    ) {
        Truth.assertThat(java.util.ArrayList<String?>(actual)).isEqualTo(java.util.ArrayList<String?>(expected))
    }

    /**
     * Returns the enumeration of the nested set {"c", "a", "b"} in the implementation's enumeration
     * order.
     * 
     * @see .simple
     * @see .simpleNoDuplicates
     */
    protected fun simpleResult(): MutableList<String?> {
        return com.google.common.collect.ImmutableList.of<String?>("c", "a", "b")
    }

    /**
     * Returns the enumeration of the nested set {"b", "d", {"c", "a", "e"}} in the implementation's
     * enumeration order.
     * 
     * @see .nesting
     */
    protected abstract fun nestedResult(): MutableList<String?>?

    /**
     * Returns the enumeration of the nested set {"b", "d", "e", {"c", "a", "e"}} in the
     * implementation's enumeration order.
     * 
     * @see .nestingNoDuplicates
     */
    protected abstract fun nestedDuplicatesResult(): MutableList<String?>?

    /**
     * Returns the enumeration of nested set {"a", {"b", {"c"}}} in the implementation's enumeration
     * order.
     * 
     * @see .chain
     */
    protected abstract fun chainResult(): MutableList<String?>?

    /**
     * Returns the enumeration of the nested set {"a", {"b", D}, {"c", D}}, where D is {"d"}, in the
     * implementation's enumeration order.
     * 
     * @see .diamond
     */
    protected abstract fun diamondResult(): MutableList<String?>?

    /**
     * Returns the enumeration of the nested set {"a", {"b", E, D}, {"c", D, E}}, where D is {"d"} and
     * E is {"e"}, in the implementation's enumeration order.
     * 
     * @see .extendedDiamond
     */
    protected abstract fun extendedDiamondResult(): MutableList<String?>?

    /**
     * Returns the enumeration of the nested set {"a", {"b", E, D}, {"c", C2}}, where D is {"d"}, E is
     * {"e"} and C2 is {"c2", D, E}, in the implementation's enumeration order.
     * 
     * @see .extendedDiamondRightArm
     */
    protected abstract fun extendedDiamondRightArmResult(): MutableList<String?>?

    /**
     * Returns the enumeration of the nested set {{"a", "b"}, {"b", "a"}}.
     * 
     * @see .orderConflict
     * @see .orderConflictNested
     */
    protected open fun orderConflictResult(): MutableList<String?>? {
        return com.google.common.collect.ImmutableList.of<String?>("a", "b")
    }
}
