// Copyright 2022 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.util

/**
 * A set that either contains some elements or is the *complete* set (semantically contains
 * every possible value of the element type).
 */
class MaybeCompleteSet<T> private constructor(nullableSet: com.google.common.collect.ImmutableSet<T?>?) {
    private val internalSet: com.google.common.collect.ImmutableSet<T?>?

    init {
        this.internalSet = nullableSet
    }

    fun contains(value: T?): Boolean {
        return internalSet == null || internalSet.contains(value)
    }

    val isComplete: Boolean
        get() = internalSet == null

    val isEmpty: Boolean
        get() = internalSet != null && internalSet.isEmpty()

    val elementsIfNotComplete: com.google.common.collect.ImmutableSet<T?>
        get() {
            com.google.common.base.Preconditions.checkArgument(internalSet != null)
            return internalSet
        }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is MaybeCompleteSet<*>) {
            return false
        }
        return com.google.common.base.Objects.equal(internalSet, o.internalSet)
    }

    override fun hashCode(): Int {
        return com.google.common.base.Objects.hashCode("MaybeCompleteSet", internalSet)
    }

    companion object {
        private val COMPLETE = MaybeCompleteSet<Any?>(null)

        fun <T> copyOf(nonNullableSet: MutableSet<T?>): MaybeCompleteSet<T?> {
            return MaybeCompleteSet<T?>(com.google.common.collect.ImmutableSet.copyOf<T?>(nonNullableSet))
        }

        fun <T> completeSet(): MaybeCompleteSet<T?>? {
            return COMPLETE as MaybeCompleteSet<T?>?
        }

        fun <T> unionElements(set1: MaybeCompleteSet<T?>, set2: MutableSet<T?>): MaybeCompleteSet<T?>? {
            if (set1.isComplete) {
                return completeSet<T?>()
            }
            return copyOf<T?>(com.google.common.collect.Sets.union<T?>(set1.elementsIfNotComplete, set2))
        }
    }
}
