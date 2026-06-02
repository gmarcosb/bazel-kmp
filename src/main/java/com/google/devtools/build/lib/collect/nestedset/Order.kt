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

import com.google.devtools.build.lib.collect.nestedset.Depset
import com.google.devtools.build.lib.collect.nestedset.NestedSet
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant
import com.google.devtools.build.zip.ZipReader.entries
import java.util.HashMap

/**
 * Type of a nested set (defines order).
 * 
 * 
 * STABLE_ORDER: an unspecified traversal order. Use when the order of elements does not matter.
 * In Starlark it is called "default"; its older deprecated name is "stable".
 * 
 * 
 * COMPILE_ORDER: left-to-right postorder. In Starlark it is called "postorder"; its older
 * deprecated name is "compile".
 * 
 * 
 * For example, for the nested set {B, D, {A, C}}, the iteration order is "A C B D"
 * (child-first).
 * 
 * 
 * This type of set would typically be used for artifacts where elements of nested sets go before
 * the direct members of a set, for example in the case of Javascript dependencies.
 * 
 * 
 * LINK_ORDER: a variation of left-to-right preorder that enforces topological sorting. In
 * Starlark it is called "topological"; its older deprecated name is "link".
 * 
 * 
 * For example, for the nested set {A, C, {B, D}}, the iteration order is "A C B D"
 * (parent-first).
 * 
 * 
 * This type of set would typically be used for artifacts where elements of nested sets go after
 * the direct members of a set, for example when providing a list of libraries to the C++ compiler.
 * 
 * 
 * The custom ordering has the property that elements of nested sets always come before elements
 * of descendant nested sets. Left-to-right order is preserved if possible, both for items and for
 * references to nested sets.
 * 
 * 
 * The left-to-right pre-order-like ordering is implemented by running a right-to-left postorder
 * traversal and then reversing the result.
 * 
 * 
 * The reason naive left-to-right preordering is not used here is that it does not handle
 * diamond-like structures properly. For example, take the following structure (nesting downwards):
 * 
 * <pre>
 * A
 * / \
 * B   C
 * \ /
 * D
</pre> * 
 * 
 * 
 * Naive preordering would produce "A B D C", which does not preserve the "parent before child"
 * property: C is a parent of D, so C should come before D. Either "A B C D" or "A C B D" would be
 * acceptable. This implementation returns the first option of the two so that left-to-right order
 * is preserved.
 * 
 * 
 * In case the nested sets form a tree, the ordering algorithm is equivalent to standard
 * left-to-right preorder.
 * 
 * 
 * Sometimes it may not be possible to preserve left-to-right order:
 * 
 * <pre>
 * A
 * /   \
 * B     C
 * / \   / \
 * \   E   /
 * \     /
 * \   /
 * D
</pre> * 
 * 
 * 
 * The left branch (B) would indicate "D E" ordering and the right branch (C) dictates "E D". In
 * such cases ordering is decided by the rightmost branch because of the list reversing behind the
 * scenes, so the ordering in the final enumeration will be "E D".
 * 
 * 
 * NAIVE_LINK_ORDER: a left-to-right preordering. In Starlark it is called "preorder"; its older
 * deprecated name is "naive_link".
 * 
 * 
 * For example, for the nested set {B, D, {A, C}}, the iteration order is "B D A C".
 * 
 * 
 * The order is called naive because it does no special treatment of dependency graphs that are
 * not trees. For such graphs the property of parent-before-dependencies in the iteration order will
 * not be upheld. For example, the diamond-shape graph A->{B, C}, B->{D}, C->{D} will be enumerated
 * as "A B D C" rather than "A B C D" or "A C B D".
 * 
 * 
 * The difference from LINK_ORDER is that this order gives priority to left-to-right order over
 * dependencies-after-parent ordering. Note that the latter is usually more important, so please use
 * LINK_ORDER whenever possible.
 */
// TODO(bazel-team): Remove deprecated names from the documentation above.
enum class Order(starlarkName: String) {
    STABLE_ORDER("default"),
    COMPILE_ORDER("postorder"),
    LINK_ORDER("topological"),
    NAIVE_LINK_ORDER("preorder");

    @kotlin.jvm.JvmField
    val starlarkName: String?
    private val emptySet: NestedSet<*>
    private val emptyDepset: Depset

    /**
     * Returns an empty set of the given ordering.
     */
    fun  // Nested sets are immutable, so a downcast is fine.
            <E> emptySet(): NestedSet<E?>? {
        return emptySet as NestedSet<E?>?
    }

    /** Returns an empty depset of the given ordering.  */
    fun emptyDepset(): Depset {
        return emptyDepset
    }

    /**
     * Determines whether two orders are considered compatible.
     * 
     * 
     * An order is compatible with itself (reflexivity) and all orders are compatible with
     * [.STABLE_ORDER]; the rest of the combinations are incompatible.
     */
    fun isCompatible(other: Order?): Boolean {
        return this == other || this == com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER || other == com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER
    }

    init {
        this.starlarkName = starlarkName
        this.emptySet = NestedSet<Any?>(this)
        this.emptyDepset = Depset(null, this.emptySet)
    }

    companion object {
        private val VALUES: com.google.common.collect.ImmutableMap<String?, Order?>
        private val ORDINALS: Array<Order>

        @SerializationConstant
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        val STABLE_ORDER_CONSTANT: Order = com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER

        @SerializationConstant
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        val COMPILE_ORDER_CONSTANT: Order = com.google.devtools.build.lib.collect.nestedset.Order.COMPILE_ORDER

        @SerializationConstant
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        val LINK_ORDER_CONSTANT: Order = com.google.devtools.build.lib.collect.nestedset.Order.LINK_ORDER

        @SerializationConstant
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        val NAIVE_LINK_ORDER_CONSTANT: Order = com.google.devtools.build.lib.collect.nestedset.Order.NAIVE_LINK_ORDER

        @SerializationConstant
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        val EMPTY_STABLE: NestedSet<*>? =
            com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER.emptySet<Any?>()

        @SerializationConstant
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        val EMPTY_COMPILE: NestedSet<*>? =
            com.google.devtools.build.lib.collect.nestedset.Order.COMPILE_ORDER.emptySet<Any?>()

        @SerializationConstant
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        val EMPTY_LINK: NestedSet<*>? =
            com.google.devtools.build.lib.collect.nestedset.Order.LINK_ORDER.emptySet<Any?>()

        @SerializationConstant
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        val EMPTY_NAIVE_LINK: NestedSet<*>? =
            com.google.devtools.build.lib.collect.nestedset.Order.NAIVE_LINK_ORDER.emptySet<Any?>()

        /**
         * Parses the given string as a nested set order
         * 
         * @param name unique name of the order
         * @return the appropriate order instance
         * @throws IllegalArgumentException if the name is not valid
         */
        @kotlin.jvm.JvmStatic
        fun parse(name: String?): Order? {
            if (com.google.devtools.build.lib.collect.nestedset.Order.Companion.VALUES.containsKey(name)) {
                return com.google.devtools.build.lib.collect.nestedset.Order.Companion.VALUES.get(name)
            } else {
                throw java.lang.IllegalArgumentException("Invalid order: " + name)
            }
        }

        /**
         * Indexes all possible values by name and stores the results in a `ImmutableMap`
         */
        init {
            com.google.devtools.build.lib.collect.nestedset.Order.Companion.ORDINALS =
                com.google.devtools.build.lib.collect.nestedset.Order.entries.toTypedArray()
            val entries: HashMap<String?, Order?> =
                com.google.common.collect.Maps.newHashMapWithExpectedSize<String?, Order?>(com.google.devtools.build.lib.collect.nestedset.Order.Companion.ORDINALS.length)

            for (current in com.google.devtools.build.lib.collect.nestedset.Order.Companion.ORDINALS) {
                entries.put(current.getStarlarkName(), current)
            }

            com.google.devtools.build.lib.collect.nestedset.Order.Companion.VALUES =
                com.google.common.collect.ImmutableMap.copyOf<String?, Order?>(entries)
        }

        fun getOrder(ordinal: Int): Order? {
            return com.google.devtools.build.lib.collect.nestedset.Order.Companion.ORDINALS[ordinal]
        }
    }
}
