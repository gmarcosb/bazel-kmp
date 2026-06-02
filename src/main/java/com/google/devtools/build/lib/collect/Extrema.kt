// Copyright 2018 The Bazel Authors. All rights reserved.
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

/**
 * A streaming aggregator that, given a `k`, allows a streaming aggregation of `n`
 * elements into the `min(k, n)` most extreme, in `O(min(k, n))` memory and `O(n * log(min(k, n)))` time.
 */
abstract class Extrema<T> {
    /**
     * Aggregates the given element.
     * 
     * 
     * See [.getExtremeElements].
     */
    abstract fun aggregate(element: T?)

    /**
     * For an [Extrema] created with `k` and with `n` calls to [.aggregate]
     * since the most recent call to [.clear], returns the `min(k, n)` most extreme of the
     * those elements, sorted from most extreme to least extreme.
     */
    @kotlin.jvm.JvmField
    abstract val extremeElements: com.google.common.collect.ImmutableList<T?>?

    /** Returns true iff [.getExtremeElements] would return an empty result.  */
    @kotlin.jvm.JvmField
    abstract val isEmpty: Boolean

    /**
     * Disregards all the elements [.aggregate]'ed already.
     * 
     * 
     * See [.getExtremeElements].
     */
    abstract fun clear()

    private class EmptyExtrema<T> : Extrema<T?>() {
        override fun aggregate(element: T?) {
            // no-op.
        }

        override fun getExtremeElements(): com.google.common.collect.ImmutableList<T?> {
            return com.google.common.collect.ImmutableList.of<T?>()
        }

        override fun clear() {
            // no-op.
        }

        override fun isEmpty(): Boolean {
            return true
        }
    }

    private class RegularExtrema<T>(private val k: Int, extremaComparator: java.util.Comparator<T?>) : Extrema<T?>() {
        private val extremaComparator: java.util.Comparator<T?>
        private val priorityQueue: java.util.PriorityQueue<T?>

        /**
         * @param k the number of extreme elements to compute
         * @param extremaComparator a comparator such that `extremaComparator(a, b) < 0` iff
         * `a` is more extreme than `b`
         */
        init {
            this.extremaComparator = extremaComparator
            this.priorityQueue =
                java.util.PriorityQueue<T?>( /*initialCapacity=*/
                    k,  // Our implementation strategy is to keep a priority queue of the k most extreme
                    // elements
                    // encountered, ordered backwards; this way we have constant-time access to the least
                    // extreme among these elements.
                    extremaComparator.reversed()
                )
        }

        override fun aggregate(element: T?) {
            if (priorityQueue.size() < k) {
                priorityQueue.add(element)
            } else {
                if (extremaComparator.compare(element, priorityQueue.peek()) < 0) {
                    // Suppose the least extreme of the current k most extreme elements is e. If the new
                    // element
                    // is more extreme than e, then (i) it must be among the new k most extreme among the (2)
                    // e
                    // must not be.
                    priorityQueue.remove()
                    priorityQueue.add(element)
                }
            }
        }

        override fun getExtremeElements(): com.google.common.collect.ImmutableList<T?> {
            return com.google.common.collect.ImmutableList.sortedCopyOf<T?>(extremaComparator, priorityQueue)
        }

        override fun clear() {
            priorityQueue.clear()
        }

        override fun isEmpty(): Boolean {
            return priorityQueue.isEmpty()
        }
    }

    companion object {
        private val EMPTY: Extrema<Any?> = EmptyExtrema<Any?>()

        /**
         * Creates an [Extrema] that can aggregate `n` elements into the `min(k, n)`
         * smallest.
         */
        fun <T : Comparable<T?>?> min(k: Int): Extrema<T?> {
            return min<T?>(k, java.util.Comparator.naturalOrder<T?>())
        }

        /**
         * Creates an [Extrema] that can aggregate `n` elements into the `min(k, n)`
         * smallest, per the given `comparator`.
         */
        @kotlin.jvm.JvmStatic
        fun <T> min(k: Int, comparator: java.util.Comparator<T?>): Extrema<T?> {
            return create<T?>(k, comparator)
        }

        /**
         * Creates an [Extrema] that can aggregate `n` elements into the `min(k, n)`
         * largest.
         */
        fun <T : Comparable<T?>?> max(k: Int): Extrema<T?> {
            return max<T?>(k, java.util.Comparator.naturalOrder<T?>())
        }

        /**
         * Creates an [Extrema] that can aggregate `n` elements into the `min(k, n)`
         * largest, per the given `comparator`.
         */
        @kotlin.jvm.JvmStatic
        fun <T> max(k: Int, comparator: java.util.Comparator<T?>): Extrema<T?> {
            return create<T?>(k, comparator.reversed())
        }

        private fun <T> create(k: Int, comparator: java.util.Comparator<T?>): Extrema<T?> {
            com.google.common.base.Preconditions.checkArgument(k >= 0, "invalid k (%s), must be >=0", k)
            return if (k == 0) EMPTY as Extrema<T?> else RegularExtrema<T?>(k, comparator)
        }
    }
}
