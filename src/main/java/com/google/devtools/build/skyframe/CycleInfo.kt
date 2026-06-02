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
import com.google.devtools.build.skyframe.SkyKey
import java.util.HashSet

/**
 * Data for a single cycle in the graph, together with the path to the cycle. For any value, the
 * head of path to the cycle should be the value itself, or, if the value is actually in the cycle,
 * the cycle should start with the value.
 */
abstract class CycleInfo {
    @kotlin.jvm.JvmField
    abstract val cycle: com.google.common.collect.ImmutableList<SkyKey?>?

    @kotlin.jvm.JvmField
    abstract val pathToCycle: com.google.common.collect.ImmutableList<SkyKey?>?

    abstract val topKey: SkyKey?

    abstract fun hasCycleDetails(): Boolean

    private class CycleInfoNoDetails : CycleInfo() {
        override fun getCycle(): com.google.common.collect.ImmutableList<SkyKey?>? {
            throw java.lang.UnsupportedOperationException("unexpected access of cycle details")
        }

        override fun getPathToCycle(): com.google.common.collect.ImmutableList<SkyKey?>? {
            throw java.lang.UnsupportedOperationException("unexpected access of cycle details")
        }

        override fun getTopKey(): SkyKey? {
            throw java.lang.UnsupportedOperationException("unexpected access of cycle details")
        }

        override fun hasCycleDetails(): Boolean {
            return false
        }
    }

    private class CycleInfoWithDetails : CycleInfo {
        private val cycle: com.google.common.collect.ImmutableList<SkyKey>
        private val pathToCycle: com.google.common.collect.ImmutableList<SkyKey>

        private constructor(pathToCycle: Iterable<SkyKey?>, cycle: Iterable<SkyKey?>) {
            this.pathToCycle = com.google.common.collect.ImmutableList.copyOf<SkyKey?>(pathToCycle)
            this.cycle = com.google.common.collect.ImmutableList.copyOf<SkyKey?>(cycle)
            com.google.common.base.Preconditions.checkArgument(!this.cycle.isEmpty(), "Cycle cannot be empty: %s", this)
        }

        // If a cycle is already known, but we are processing a value in the middle of the cycle, we
        // need to shift the cycle so that the value is at the head.
        private constructor(cycle: Iterable<SkyKey>, cycleStart: Int) {
            com.google.common.base.Preconditions.checkState(cycleStart >= 0, cycleStart)
            val cycleTail: com.google.common.collect.ImmutableList.Builder<SkyKey?> =
                com.google.common.collect.ImmutableList.builder<SkyKey?>()
            val cycleHead: com.google.common.collect.ImmutableList.Builder<SkyKey?> =
                com.google.common.collect.ImmutableList.builder<SkyKey?>()
            var index = 0
            for (key in cycle) {
                if (index >= cycleStart) {
                    cycleHead.add(key)
                } else {
                    cycleTail.add(key)
                }
                index++
            }
            com.google.common.base.Preconditions.checkState(cycleStart < index, "%s >= %s ??", cycleStart, index)
            this.cycle = cycleHead.addAll(cycleTail.build()).build()
            this.pathToCycle = com.google.common.collect.ImmutableList.of<SkyKey?>()
        }

        override fun getCycle(): com.google.common.collect.ImmutableList<SkyKey> {
            return cycle
        }

        override fun getPathToCycle(): com.google.common.collect.ImmutableList<SkyKey> {
            return pathToCycle
        }

        override fun getTopKey(): SkyKey {
            return if (pathToCycle.isEmpty()) cycle.get(0) else pathToCycle.get(0)
        }

        override fun hasCycleDetails(): Boolean {
            return true
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(cycle, pathToCycle)
        }

        override fun equals(that: Any?): Boolean {
            if (this === that) {
                return true
            }
            if (that !is CycleInfoWithDetails) {
                return false
            }

            return that.cycle == this.cycle && that.pathToCycle == this.pathToCycle
        }

        override fun toString(): String {
            return com.google.common.collect.Iterables.toString(pathToCycle) + " -> " + com.google.common.collect.Iterables.toString(
                cycle
            )
        }
    }

    companion object {
        @com.google.common.annotations.VisibleForTesting
        fun createCycleInfo(cycle: Iterable<SkyKey?>): CycleInfo {
            return CycleInfoWithDetails(com.google.common.collect.ImmutableList.of<SkyKey?>(), cycle)
        }

        fun createCycleInfo(pathToCycle: Iterable<SkyKey?>, cycle: Iterable<SkyKey?>): CycleInfo {
            return CycleInfoWithDetails(pathToCycle, cycle)
        }

        private val NO_DETAILS_INSTANCE = CycleInfoNoDetails()

        fun cycleInfoNoDetails(): CycleInfo {
            return NO_DETAILS_INSTANCE
        }

        // Given a cycle and a value, if the value is part of the cycle, shift the cycle. Otherwise,
        // prepend the value to the head of pathToCycle.
        private fun normalizeCycle(value: SkyKey, cycle: CycleInfo): CycleInfo? {
            val index: Int = cycle.cycle.indexOf(value)
            if (index > -1) {
                if (!cycle.pathToCycle.isEmpty()) {
                    // The head value we are considering is already part of a cycle, but we have reached it by a
                    // roundabout way. Since we should have reached it directly as well, filter this roundabout
                    // way out. Example (c has a dependence on top):
                    //          top
                    //         /  ^
                    //        a   |
                    //       / \ /
                    //      b-> c
                    // In the traversal, we start at top, visit a, then c, then top. This yields the
                    // cycle {top,a,c}. Then we visit b, getting (b, {top,a,c}). Then we construct the full
                    // error for a. The error should just be the cycle {top,a,c}, but we have an extra copy of
                    // it via the path through b.
                    return null
                }
                return CycleInfoWithDetails(cycle.cycle, index)
            }
            return createCycleInfo(
                com.google.common.collect.ImmutableList.builderWithExpectedSize<SkyKey?>(cycle.pathToCycle.size() + 1)
                    .add(value)
                    .addAll(cycle.pathToCycle)
                    .build(),
                cycle.cycle
            )
        }

        /**
         * Normalize multiple cycles. This includes removing multiple paths to the same cycle, so that a
         * value does not depend on the same cycle multiple ways through the same child value. Note that a
         * value can still depend on the same cycle multiple ways, it's just that each way must be through
         * a different child value (a path with a different first element).
         * 
         * 
         * If any of the given cycles are without details (created using [.cycleInfoNoDetails])
         * then a single [.cycleInfoNoDetails] will be returned.
         */
        fun prepareCycles(value: SkyKey, cycles: Iterable<CycleInfo>): Iterable<CycleInfo?> {
            val alreadyDoneCycles: MutableSet<com.google.common.collect.ImmutableList<SkyKey?>?> =
                HashSet<com.google.common.collect.ImmutableList<SkyKey?>?>()
            val result: com.google.common.collect.ImmutableList.Builder<CycleInfo?> =
                com.google.common.collect.ImmutableList.builder<CycleInfo?>()
            for (cycle in cycles) {
                if (!cycle.hasCycleDetails()) {
                    return com.google.common.collect.ImmutableList.of<CycleInfo?>(cycle)
                }
                val normalized = normalizeCycle(value, cycle)
                if (normalized != null && alreadyDoneCycles.add(normalized.cycle)) {
                    result.add(normalized)
                }
            }
            return result.build()
        }
    }
}
