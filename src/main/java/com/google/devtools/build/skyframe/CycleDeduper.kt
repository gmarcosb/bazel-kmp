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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get

/**
 * Dedupes C candidate cycles of size O(L) in O(CL) time and memory in the common case and
 * O(C^2 * L) time and O(CL) memory in the extreme case.
 * 
 * Two cycles are considered duplicates if they are exactly the same except for the entry point.
 * For example, 'a' -> 'b' -> 'c' -> 'a' is the considered the same as 'b' -> 'c' -> 'a' -> 'b'.
 */
internal class CycleDeduper<T> {
    private val knownCyclesByMembers: com.google.common.collect.SetMultimap<com.google.common.collect.ImmutableSet<T?>?, com.google.common.collect.ImmutableList<T?>> =
        com.google.common.collect.HashMultimap.create<com.google.common.collect.ImmutableSet<T?>?, com.google.common.collect.ImmutableList<T?>?>()

    /**
     * Marks a non-empty list representing a cycle of unique values as being seen and returns true iff
     * the cycle has been seen before, accounting for logical equivalence of cycles.
     * 
     * 
     * For example, the cycle 'a' -> 'b' -> 'c' -> 'a' is represented by the list ['a', 'b', 'c']
     * and is logically equivalent to the cycle represented by the list ['b', 'c', 'a'].
     */
    fun alreadySeen(cycle: com.google.common.collect.ImmutableList<T?>): Boolean {
        val cycleMembers: com.google.common.collect.ImmutableSet<T?> =
            com.google.common.collect.ImmutableSet.copyOf<T?>(cycle)
        com.google.common.base.Preconditions.checkState(!cycle.isEmpty())
        com.google.common.base.Preconditions.checkState(
            cycle.size() == cycleMembers.size(), "cycle doesn't have unique members: %s", cycle
        )

        if (knownCyclesByMembers.containsEntry(cycleMembers, cycle)) {
            return true
        }

        // Of the C cycles, suppose there are D cycles that have the same members (but are in an
        // incompatible order). This code path takes O(D * L) time. The common case is that D is
        // very small.
        var found = false
        for (candidateCycle in knownCyclesByMembers.get(cycleMembers)) {
            val startPos: Int = candidateCycle.indexOf(cycle.get(0))
            // The use of a multimap keyed by cycle members guarantees that the first element of 'cycle'
            // is present in 'candidateCycle'.
            com.google.common.base.Preconditions.checkState(startPos >= 0)
            if (equalsWithSingleLoopFrom(cycle, candidateCycle, startPos)) {
                found = true
                break
            }
        }
        // We add the cycle even if it's a duplicate so that future exact copies of this can be
        // processed in O(L) time. We are already using O(CL) memory, and this optimization doesn't
        // change that.
        knownCyclesByMembers.put(cycleMembers, cycle)
        return found
    }

    /**
     * Returns true iff
     * listA[0], listA[1], ..., listA[listA.size()]
     * is the same as
     * listB[start], listB[start+1], ..., listB[listB.size()-1], listB[0], ..., listB[start-1]
     */
    private fun equalsWithSingleLoopFrom(
        listA: com.google.common.collect.ImmutableList<T?>, listB: com.google.common.collect.ImmutableList<T?>,
        start: Int
    ): Boolean {
        if (listA.size() != listB.size()) {
            return false
        }
        val length: Int = listA.size()
        for (i in 0..<length) {
            if (listA.get(i) != listB.get((i + start) % length)) {
                return false
            }
        }
        return true
    }
}
