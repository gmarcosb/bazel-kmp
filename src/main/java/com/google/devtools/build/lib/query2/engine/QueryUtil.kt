// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.engine

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSortedSet
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.cmdline.Label
import com.google.devtools.build.lib.collect.compacthashset.CompactHashSet
import kotlin.Any
import kotlin.Boolean
import kotlin.Comparator
import kotlin.Int
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableIterator
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set

/** Several query utilities to make easier to work with query callbacks and uniquifiers.  */
object QueryUtil {
    /**
     * Returns a fresh [AggregateAllOutputFormatterCallback] instance whose
     * [AggregateAllCallback.getResult] returns all the elements of the result in the order they
     * were processed.
     */
    fun <T> newOrderedAggregateAllOutputFormatterCallback(env: QueryEnvironment<T?>): AggregateAllOutputFormatterCallback<T?, MutableSet<T?>?> {
        return OrderedAggregateAllOutputFormatterCallbackImpl<T?>(env)
    }

    /**
     * Returns a fresh [AggregateAllOutputFormatterCallback] instance whose [ ][AggregateAllCallback.getResult] returns all the targets in the result sorted lexicographically
     * by [Label].
     */
    @kotlin.jvm.JvmStatic
    fun newLexicographicallySortedTargetAggregator(): AggregateAllOutputFormatterCallback<Target?, MutableSet<Target?>?> {
        return LexicographicallySortedTargetAggregator()
    }

    /**
     * Returns a fresh [AggregateAllCallback] instance that aggregates all of the values into an
     * [ThreadSafeMutableSet].
     */
    fun <T> newAggregateAllCallback(
        env: QueryEnvironment<T?>
    ): AggregateAllCallback<T?, ThreadSafeMutableSet<T?>?> {
        return AggregateAllOutputFormatterCallbackImpl<T?>(env)
    }

    /**
     * Returns a [QueryTaskFuture] representing the evaluation of `expr` as a mutable,
     * thread safe [Set] comprised of all the results.
     * 
     * 
     * Should only be used by QueryExpressions when it is the only way of achieving correctness.
     */
    fun <T> evalAll(
        env: QueryEnvironment<T?>, context: QueryExpressionContext<T?>?, expr: QueryExpression?
    ): QueryTaskFuture<ThreadSafeMutableSet<T?>?>? {
        val callback: AggregateAllCallback<T?, ThreadSafeMutableSet<T?>?> = newAggregateAllCallback<T?>(env)
        return env.whenSucceedsCall<ThreadSafeMutableSet<T?>?>(
            env.eval(expr, context, callback),
            object : QueryTaskCallable<ThreadSafeMutableSet<T?>?> {
                override fun call(): ThreadSafeMutableSet<T?>? {
                    return callback.result
                }
            })
    }

    /** A [Callback] that can aggregate all the partial results into a single value.  */
    interface AggregateAllCallback<T, V> : Callback<T?> {
        /** Returns a value representing a combination of all the partial results.  */
        @kotlin.jvm.JvmField
        val result: V?
    }

    /** A [OutputFormatterCallback] that is also a [AggregateAllCallback].  */
    abstract class AggregateAllOutputFormatterCallback<T, S : MutableSet<T?>?>
        : ThreadSafeOutputFormatterCallback<T?>(), AggregateAllCallback<T?, S?>

    private class AggregateAllOutputFormatterCallbackImpl<T>
        (env: QueryEnvironment<T?>) : AggregateAllOutputFormatterCallback<T?, ThreadSafeMutableSet<T?>?>() {
        private val result: ThreadSafeMutableSet<T?>

        init {
            this.result = env.createThreadSafeMutableSet()
        }

        override fun processOutput(partialResult: Iterable<T?>) {
            Iterables.addAll<T?>(result, partialResult)
        }

        override fun getResult(): ThreadSafeMutableSet<T?> {
            return result
        }
    }

    private class OrderedAggregateAllOutputFormatterCallbackImpl<T>
        (env: QueryEnvironment<T?>) : AggregateAllOutputFormatterCallback<T?, MutableSet<T?>?>() {
        private val resultSet: MutableSet<T?>
        private val resultList: MutableList<T?>

        init {
            this.resultSet = env.createThreadSafeMutableSet()
            this.resultList = ArrayList<T?>()
        }

        @kotlin.jvm.Synchronized
        override fun processOutput(partialResult: Iterable<T?>) {
            for (element in partialResult) {
                if (resultSet.add(element)) {
                    resultList.add(element)
                }
            }
        }

        @kotlin.jvm.Synchronized
        override fun getResult(): MutableSet<T?> {
            // A CompactHashSet's iteration order is the same as its insertion order.
            val result = CompactHashSet.createWithExpectedSize<T?>(resultList.size)
            result.addAll(resultList)
            return result
        }
    }

    private class LexicographicallySortedTargetAggregator

        : AggregateAllOutputFormatterCallback<Target?, MutableSet<Target?>?>() {
        private val resultMap: MutableMap<Label?, Target?> = HashMap<Label?, Target?>()

        @kotlin.jvm.Synchronized
        override fun processOutput(partialResult: Iterable<Target>) {
            for (target in partialResult) {
                resultMap.put(target.getLabel(), target)
            }
        }

        @kotlin.jvm.Synchronized
        override fun getResult(): ImmutableSortedSet<Target?> {
            return ImmutableSortedSet.copyOf<Target?>(
                Comparator { t1: Target?, t2: Target? -> Companion.compareTargetsByLabel(t1!!, t2!!) }, resultMap.values
            )
        }

        companion object {
            // A reference to this method is significantly more efficient than using Comparator#comparing.
            private fun compareTargetsByLabel(t1: Target, t2: Target): Int {
                return t1.getLabel().compareTo(t2.getLabel())
            }
        }
    }

    /**
     * A mutable thread safe [Set] that uses a [KeyExtractor] for determining equality of
     * its elements. This is useful e.g. when `T` isn't guaranteed to have a useful
     * [Object.equals] and [Object.hashCode] but `K` is.
     */
    class ThreadSafeMutableKeyExtractorBackedSetImpl<T, K>
    @kotlin.jvm.JvmOverloads constructor(
        private val extractor: KeyExtractor<T?, K?>,
        private val elementClass: Class<T?>,
        concurrencyLevel: Int = 1
    ) : AbstractSet<T?>(), ThreadSafeMutableSet<T?> {
        private val map: ConcurrentMap<K?, T?>

        init {
            this.map =
                ConcurrentHashMap<K?, T?>( /*initialCapacity=*/concurrencyLevel,  /*loadFactor=*/0.75f)
        }

        override fun iterator(): MutableIterator<T?>? {
            return map.values.iterator()
        }

        override fun size(): Int {
            return map.size
        }

        override fun add(element: T?): Boolean {
            return map.putIfAbsent(extractor.extractKey(element), element) == null
        }

        override fun contains(obj: Any?): Boolean {
            if (!elementClass.isInstance(obj)) {
                return false
            }
            val element = elementClass.cast(obj)
            return map.containsKey(extractor.extractKey(element))
        }

        override fun remove(obj: Any?): Boolean {
            if (!elementClass.isInstance(obj)) {
                return false
            }
            val element = elementClass.cast(obj)
            return map.remove(extractor.extractKey(element)) != null
        }
    }

    /** A [Uniquifier] whose methods do not throw [QueryException].  */
    interface NonExceptionalUniquifier<T> : Uniquifier<T?> {
        override fun unique(newElement: T?): Boolean

        override fun unique(newElements: Iterable<T?>?): ImmutableList<T?>?
    }

    /**
     * A [NonExceptionalUniquifier] that doesn't do anything and always says an element is
     * unique.
     */
    class NullUniquifierImpl<T> private constructor() : NonExceptionalUniquifier<T?> {
        override fun uniquePure(newElement: T?): Boolean {
            return true
        }

        override fun unique(newElement: T?): Boolean {
            return true
        }

        override fun unique(newElements: Iterable<T?>): ImmutableList<T?> {
            return ImmutableList.copyOf<T?>(newElements)
        }

        companion object {
            private val INSTANCE = NullUniquifierImpl<Any?>()

            fun <T> instance(): NullUniquifierImpl<T?>? {
                return INSTANCE as NullUniquifierImpl<T?>?
            }
        }
    }

    /** A trivial [Uniquifier] implementation.  */
    class UniquifierImpl<T, K> @kotlin.jvm.JvmOverloads constructor(
        private val extractor: KeyExtractor<T?, K?>,
        queryEvaluationParallelismLevel: Int = 1
    ) : NonExceptionalUniquifier<T?> {
        private val alreadySeen: MutableSet<K?>

        init {
            this.alreadySeen =
                Collections.newSetFromMap<K?>( // Note that ConcurrentHashMap sadly only uses these 3 parameters as an *initial*
                    // sizing hint.
                    ConcurrentHashMap<K?, Boolean?>( /*initialCapacity=*/
                        16,  /*loadFactor=*/
                        0.75f,  /*concurrencyLevel=*/
                        queryEvaluationParallelismLevel
                    )
                )
        }

        override fun uniquePure(element: T?): Boolean {
            return !alreadySeen.contains(extractor.extractKey(element))
        }

        override fun unique(element: T?): Boolean {
            return alreadySeen.add(extractor.extractKey(element))
        }

        override fun unique(newElements: Iterable<T?>): ImmutableList<T?> {
            val result = ImmutableList.builder<T?>()
            for (element in newElements) {
                if (unique(element)) {
                    result.add(element)
                }
            }
            return result.build()
        }
    }

    /** A trivial [MinDepthUniquifier] implementation.  */
    class MinDepthUniquifierImpl<T, K>(private val extractor: KeyExtractor<T?, K?>, concurrencyLevel: Int) :
        MinDepthUniquifier<T?> {
        private val alreadySeenAtDepth: ConcurrentMap<K?, KeyState?>

        init {
            this.alreadySeenAtDepth =
                ConcurrentHashMap<K?, KeyState?>( /*initialCapacity=*/concurrencyLevel,  /*loadFactor=*/0.75f)
        }

        override fun uniqueAtDepthLessThanOrEqualTo(
            newElements: Iterable<T?>, depth: Int
        ): ImmutableList<T?> {
            val resultBuilder = ImmutableList.builder<T?>()
            for (newElement in newElements) {
                if (uniqueAtDepthLessThanOrEqualTo(newElement, depth)) {
                    resultBuilder.add(newElement)
                }
            }
            return resultBuilder.build()
        }

        override fun uniqueAtDepthLessThanOrEqualTo(newElement: T?, depth: Int): Boolean {
            val newState = KeyState(AtomicInteger(depth), AtomicBoolean(false))
            val previousState =
                alreadySeenAtDepth.putIfAbsent(extractor.extractKey(newElement), newState)
            if (previousState == null) {
                return true
            }
            if (depth < previousState.depth.get()) {
                synchronized(previousState) {
                    if (depth < previousState.depth.get()) {
                        // We've seen the element before, but never at a depth this shallow.
                        previousState.depth.set(depth)
                        return true
                    }
                }
            }
            return false
        }

        override fun uniqueAtDepthLessThanOrEqualToPure(newElement: T?, depth: Int): Boolean {
            val previousState = alreadySeenAtDepth.get(extractor.extractKey(newElement))
            return previousState == null || depth < previousState.depth.get()
        }

        override fun uniqueForOutput(element: T?): Boolean {
            val keyState = alreadySeenAtDepth.get(extractor.extractKey(element))
            Preconditions.checkNotNull<KeyState?>(keyState, "Must visit an element before outputting that element.")
            return !keyState!!.hasBeenOutput.getAndSet(true)
        }

        override fun uniqueElementsCount(): Int {
            return alreadySeenAtDepth.size
        }

        /** State tracked for each key tracked by the uniquifier.  */
        private class KeyState(depth: AtomicInteger?, hasBeenOutput: AtomicBoolean?) {
            val depth: AtomicInteger?
            val hasBeenOutput: AtomicBoolean?

            init {
                this.depth = depth
                this.hasBeenOutput = hasBeenOutput
            }
        }
    }
}
