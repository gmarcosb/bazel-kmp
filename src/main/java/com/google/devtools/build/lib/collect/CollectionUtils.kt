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
package com.google.devtools.build.lib.collect

import java.util.HashMap
import java.util.HashSet

/** Utilities for collection classes.  */
object CollectionUtils {
    /** Returns the set of all elements in the given list that appear more than once.  */
    fun <T> duplicatedElementsOf(input: MutableList<T?>): MutableSet<T?> {
        val count: Int = input.size()
        if (count < 2) {
            return com.google.common.collect.ImmutableSet.of<T?>()
        }
        var duplicates: MutableSet<T?>? = null
        val elementSet: MutableSet<T?> =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.createWithExpectedSize<T?>(
                count
            )
        for (el in input) {
            if (!elementSet.add(el)) {
                if (duplicates == null) {
                    duplicates = HashSet<T?>()
                }
                duplicates!!.add(el)
            }
        }
        return if (duplicates == null) com.google.common.collect.ImmutableSet.of<T?>() else duplicates
    }

    /**
     * Returns an immutable set of all non-null parameters in the order in which they are specified.
     */
    fun <T> asSetWithoutNulls(vararg elements: T?): com.google.common.collect.ImmutableSet<T?> {
        return java.util.Arrays.stream<T?>(elements)
            .filter(java.util.function.Predicate { obj: T? -> java.util.Objects.nonNull(obj) })
            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<T?>())
    }

    /** Returns a copy of the Map of Maps parameter.  */
    fun <KEY_1, KEY_2, VALUE> copyOf(
        map: MutableMap<KEY_1?, out MutableMap<KEY_2?, VALUE?>?>
    ): MutableMap<KEY_1?, MutableMap<KEY_2?, VALUE?>?> {
        return HashMap<KEY_1?, MutableMap<KEY_2?, VALUE?>?>(
            com.google.common.collect.Maps.transformValues(
                map,
                { m: MutableMap<out K?, out V?>? -> HashMap(m) })
        )
    }

    /**
     * Checks whether the given collection is either `null` or [ empty][Collection.isEmpty].
     */
    fun isNullOrEmpty(collection: MutableCollection<*>?): Boolean {
        return collection == null || collection.isEmpty()
    }
}
