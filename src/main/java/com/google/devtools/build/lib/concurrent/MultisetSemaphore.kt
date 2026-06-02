// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.concurrent

import java.util.concurrent.Semaphore
import java.util.function.ToIntFunction

/**
 * A concurrency primitive for managing access to at most K unique things at once, for a fixed K.
 * 
 * 
 * You can think of this as a pair of a [Semaphore] with K total permits and a
 * [Multiset], with permits being doled out and returned based on the current contents of the
 * [Multiset].
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
abstract class MultisetSemaphore<T> {
    /**
     * Blocks until permits are available for all the values in `valuesToAcquire`, and then
     * atomically acquires these permits.
     * 
     * 
     * `acquireAll(valuesToAcquire)` atomically does the following
     * 
     *  1. Computes `m`, the number of values in `valuesToAcquire` that are not
     * currently in the backing [Multiset].
     *  1. Adds `valuesToAcquire` to the backing [Multiset].
     *  1. Blocks until `m` permits are available from the backing [Semaphore].
     *  1. Acquires these permits.
     * 
     */
    @Throws(java.lang.InterruptedException::class)
    abstract fun acquireAll(valuesToAcquire: MutableSet<T?>?)

    /**
     * Atomically releases permits for all the values in `valuesToAcquire`.
     * 
     * 
     * `releaseAll(valuesToRelease)` atomically does the following
     * 
     *  1. Computes `m`, the number of values in `valuesToRelease` that are currently in
     * the backing [Multiset] with multiplicity 1.
     *  1. Removes `valuesToRelease` from the backing [Multiset].
     *  1. Release `m` permits from the backing [Semaphore].
     * 
     * 
     * 
     * Assumes that this [MultisetSemaphore] has already given out permits for all the
     * values in `valuesToAcquire`.
     */
    abstract fun releaseAll(valuesToRelease: MutableSet<T?>?)

    abstract fun estimateCurrentNumUniqueValues(): Int

    /** Builder for [MultisetSemaphore] instances.  */
    class Builder private constructor() {
        private var maxNumUniqueValues: Int =
            com.google.devtools.build.lib.concurrent.MultisetSemaphore.Builder.Companion.UNSET_INT

        /**
         * Sets the maximum number of unique values for which permits can be held at once in the
         * to-be-constructed [MultisetSemaphore].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun maxNumUniqueValues(maxNumUniqueValues: Int): Builder {
            com.google.common.base.Preconditions.checkState(
                maxNumUniqueValues > 0,
                "maxNumUniqueValues must be positive (was %s)",
                maxNumUniqueValues
            )
            this.maxNumUniqueValues = maxNumUniqueValues
            return this
        }

        fun <T> build(): MultisetSemaphore<T?> {
            com.google.common.base.Preconditions.checkState(
                maxNumUniqueValues != com.google.devtools.build.lib.concurrent.MultisetSemaphore.Builder.Companion.UNSET_INT,
                "maxNumUniqueValues(int) must be specified"
            )
            return com.google.devtools.build.lib.concurrent.MultisetSemaphore.NaiveMultisetSemaphore<T?>(
                maxNumUniqueValues
            )
        }

        companion object {
            private val UNSET_INT = -1
        }
    }

    private class UnboundedMultisetSemaphore<T> : MultisetSemaphore<T?>() {
        @Throws(java.lang.InterruptedException::class)
        override fun acquireAll(valuesToAcquire: MutableSet<T?>?) {
        }

        override fun releaseAll(valuesToRelease: MutableSet<T?>?) {
        }

        override fun estimateCurrentNumUniqueValues(): Int {
            // We can't give a good estimate since we don't track values at all.
            return 0
        }

        companion object {
            private val INSTANCE: UnboundedMultisetSemaphore<Any?> =
                com.google.devtools.build.lib.concurrent.MultisetSemaphore.UnboundedMultisetSemaphore<Any?>()

            private fun <T> instance(): UnboundedMultisetSemaphore<T?>? {
                return com.google.devtools.build.lib.concurrent.MultisetSemaphore.UnboundedMultisetSemaphore.Companion.INSTANCE as UnboundedMultisetSemaphore<T?>?
            }
        }
    }

    private class NaiveMultisetSemaphore<T>(private val maxNumUniqueValues: Int) : MultisetSemaphore<T?>() {
        private val semaphore: Semaphore
        private val lock = Any()

        // Protected by 'lock'.
        private val actualValues: com.google.common.collect.HashMultiset<T?> =
            com.google.common.collect.HashMultiset.create<T?>()

        init {
            this.semaphore = Semaphore(maxNumUniqueValues)
        }

        @Throws(java.lang.InterruptedException::class)
        override fun acquireAll(valuesToAcquire: MutableSet<T?>) {
            var oldNumNeededPermits: Int
            synchronized(lock) {
                oldNumNeededPermits = computeNumNeededPermitsLocked(valuesToAcquire)
            }
            while (true) {
                semaphore.acquire(oldNumNeededPermits)
                synchronized(lock) {
                    val newNumNeededPermits = computeNumNeededPermitsLocked(valuesToAcquire)
                    if (newNumNeededPermits != oldNumNeededPermits) {
                        // While we were doing 'acquire' above, another thread won the race to acquire the first
                        // usage of one of the values in 'valuesToAcquire' or release the last usage of one of
                        // the values. This means we either acquired too many or too few permits, respectively,
                        // above. Release the permits we did acquire, in order to restore the accuracy of the
                        // semaphore's current count, and then try again.
                        semaphore.release(oldNumNeededPermits)
                        oldNumNeededPermits = newNumNeededPermits
                        continue
                    } else {
                        // Our modification to the semaphore was correct, so it's sound to update the multiset.
                        valuesToAcquire.forEach(java.util.function.Consumer { element: T? -> actualValues.add(element) })
                        return
                    }
                }
            }
        }

        fun computeNumNeededPermitsLocked(valuesToAcquire: MutableSet<T?>): Int {
            // We need a permit for each value that is not already in the multiset.
            return valuesToAcquire.stream()
                .filter(java.util.function.Predicate { v: T? -> actualValues.count(v) == 0 })
                .count().toInt()
        }

        override fun releaseAll(valuesToRelease: MutableSet<T?>) {
            synchronized(lock) {
                // We need to release a permit for each value that currently has multiplicity 1.
                val numPermitsToRelease: Int =
                    valuesToRelease
                        .stream()
                        .mapToInt(ToIntFunction { v: T? -> if (actualValues.remove(v, 1) == 1) 1 else 0 })
                        .sum()
                semaphore.release(numPermitsToRelease)
            }
        }

        override fun estimateCurrentNumUniqueValues(): Int {
            return maxNumUniqueValues - semaphore.availablePermits()
        }
    }

    companion object {
        /**
         * Returns a [MultisetSemaphore] with a backing [Semaphore] that has an unbounded
         * number of permits; that is, [.acquireAll] will never block.
         */
        @kotlin.jvm.JvmStatic
        fun <T> unbounded(): MultisetSemaphore<T?>? {
            return com.google.devtools.build.lib.concurrent.MultisetSemaphore.UnboundedMultisetSemaphore.Companion.instance<T?>()
        }

        /** Returns a fresh [Builder].  */
        @kotlin.jvm.JvmStatic
        fun newBuilder(): Builder {
            return com.google.devtools.build.lib.concurrent.MultisetSemaphore.Builder()
        }
    }
}
