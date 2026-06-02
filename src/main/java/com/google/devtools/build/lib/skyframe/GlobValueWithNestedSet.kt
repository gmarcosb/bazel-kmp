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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.collect.nestedset.NestedSet

/**
 * A value corresponding to a glob which uses [NestedSet] as the container to store matching
 * [PathFragment]s.
 * 
 * 
 * Used by [GlobFunctionWithRecursiveGlobbing] as a way to save memory when bubbling sub
 * glob node matches to parent glob nodes. All sub-glob node matches are stored only as a reference
 * in its parent [GlobValueWithNestedSet.matches] container.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
class GlobValueWithNestedSet(matches: NestedSet<PathFragment?>?) : GlobValue() {
    private val matches: NestedSet<PathFragment?>

    /**
     * Create a GlobValue wrapping `matches`. `matches` must have order [ ][Order.STABLE_ORDER].
     */
    init {
        this.matches = com.google.common.base.Preconditions.checkNotNull<NestedSet<PathFragment?>>(matches)
        com.google.common.base.Preconditions.checkState(
            matches.getOrder() === Order.STABLE_ORDER,
            "Only STABLE_ORDER is supported, but got %s",
            matches.getOrder()
        )
    }

    val matchesInNestedSet: NestedSet<PathFragment?>
        /**
         * Returns glob matches stored in [NestedSet]. The matches will be in a deterministic but
         * unspecified order. If a particular order is required, the returned iterable should be sorted.
         */
        get() = matches

    override fun getMatches(): com.google.common.collect.ImmutableSet<PathFragment?> {
        return matches.toSet()
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) {
            return true
        }
        if (other !is GlobValueWithNestedSet) {
            return false
        }
        // shallowEquals() may fail to detect that two equivalent (according to toString())
        // NestedSets are equal, but will always detect when two NestedSets are different.
        // This makes this implementation of equals() overly strict, but we only call this
        // method when doing change pruning, which can accept false negatives.
        return this.matchesInNestedSet
            .shallowEquals(other.matchesInNestedSet)
    }

    override fun hashCode(): Int {
        return matches.shallowHashCode()
    }

    companion object {
        val EMPTY: GlobValueWithNestedSet = GlobValueWithNestedSet(NestedSetBuilder.emptySet(Order.STABLE_ORDER))
    }
}
