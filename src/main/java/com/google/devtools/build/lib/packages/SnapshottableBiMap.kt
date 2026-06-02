// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import java.util.AbstractMap
import java.util.AbstractSet
import java.util.Collections
import java.util.HashMap

/**
 * A bimap with the following features and restrictions:
 * 
 * 
 *  * it (lazily) tracks the order in which keys were inserted;
 *  * ... but only for entries whose values satisfy a predicate;
 *  * it's append-only, i.e. it supports addition of new key-value pairs, or replacement of the
 * value of an existing key, but not deletion of key-value pairs;
 *  * ... with the restriction that replacement is not allowed to make a previously tracked entry
 * become untracked.
 * 
 * 
 * 
 * Tracking the insertion order and prohibiting key deletion allows this bimap to provide a
 * lightweight snapshot view for iterating (in key insertion order) over entries which existed at a
 * given point in time.
 * 
 * 
 * Intended to be used by `native.existing_rules` in Starlark, which needs to be able to
 * iterate, at some later point in time, over the rules which existed in a [Package.Builder]
 * at the time of the `existing_rules` call. We do not want to track insertion orders of
 * numerous non-rule targets (e.g. files) - hence, filtering by a predicate. And in the common case
 * where `existing_rules` is never called in a package, we want to avoid the overhead of
 * keeping track of insertion orders - hence, laziness.
 * 
 * 
 * In packages with a large number of targets, the use of lightweight snapshots instead of
 * copying results in a noticeable improvement in loading times, e.g. 2.2 times faster loading for a
 * package with 4400 targets and 300 `native.existing_rules` calls.
 */
internal class SnapshottableBiMap<K, V>(track: java.util.function.Predicate<V?>) :
    com.google.common.collect.BiMap<K?, V?> {
    private val contents: com.google.common.collect.BiMap<K?, V?> = com.google.common.collect.HashBiMap.create<K?, V?>()
    private val track: java.util.function.Predicate<V?>

    // trackedKeys and trackedKeyOrders are initialized lazily by ensureOrderTracking(). In the case
    // where the order-tracking map represents a package builder's targets, ensureOrderTracking() is
    // intended to be triggered only by a call to {@code native.existing_rules} in Starlark.
    //
    // Holds all keys being tracked, in their relative insertion order.
    private var trackedKeys: java.util.ArrayList<K?>? = null

    // Maps all keys being tracked to their index in trackedKeys.
    private var trackedKeyOrders: MutableMap<K?, Int?>? = null

    init {
        this.track = track
    }

    /**
     * Returns the underlying contents bimap.
     * 
     * 
     * Mutating the underlying bimap will violate the guarantees of this class and possibly cause
     * inconsistent behavior in snapshot views. Therefore, the recommended usage pattern is to replace
     * any references to the `SnapshottableBiMap` with the underlying map, and ensure that any
     * snapshots of the map are no longer in use at that point.
     * 
     * 
     * An optimization hack intended only for use from [Package.Builder.beforeBuild].
     */
    fun getUnderlyingBiMap(): com.google.common.collect.BiMap<K?, V?> {
        return contents
    }

    override fun size(): Int {
        return contents.size()
    }

    private fun sizeTracked(): Int {
        ensureOrderTracking()
        return trackedKeyOrders.size()
    }

    override fun isEmpty(): Boolean {
        return contents.isEmpty()
    }

    override fun containsKey(key: Any?): Boolean {
        return contents.containsKey(key)
    }

    override fun containsValue(value: Any?): Boolean {
        return contents.containsValue(value)
    }

    override fun get(key: Any?): V? {
        return contents.get(key)
    }

    /**
     * Returns the insertion order of the specified key (relative to other tracked keys), or -1 if the
     * key was never inserted into the map or corresponds to a key-value pair whose insertion order we
     * do not track. Replacing a key's value does not change this order if tracking has already begun.
     */
    private fun getTrackedKeyOrder(key: Any?): Int {
        ensureOrderTracking()
        val order = trackedKeyOrders!!.get(key)
        return if (order == null) -1 else order
    }

    /**
     * Returns the tracked key with the specified insertion order (as determined by [ ][.getTrackedKeyOrder]).
     * 
     * @throws IndexOutOfBoundsException if the specified insertion order is out of bounds
     */
    private fun getTrackedKey(order: Int): K? {
        ensureOrderTracking()
        return trackedKeys.get(order)
    }

    /**
     * {@inheritDoc}
     * 
     * 
     * Note that once key insertion order tracking has started, overriding a key with a different
     * value will not change the key's insertion order.
     * 
     * @throws IllegalArgumentException if attempting to replace a key-value pair whose insertion
     * order was tracked with a key-value pair whose insertion order is not tracked, or if the
     * given value is already bound to a different key in this map.
     */
    override fun put(key: K?, value: V?): V? {
        if (startedOrderTracking()) {
            val oldWasTracked = getTrackedKeyOrder(key) >= 0
            val newIsTracked: Boolean = track.test(value)
            if (oldWasTracked) {
                com.google.common.base.Preconditions.checkArgument(
                    newIsTracked,
                    "Cannot replace a key-value pair which is tracked with a key-value pair which is"
                            + " not tracked"
                )
            } else {
                if (newIsTracked) {
                    recordKeyOrder(key)
                }
            }
        }
        return contents.put(key, value)
    }

    /**
     * @throws UnsupportedOperationException always.
     */
    @Deprecated(
        """Not supported, since it's morally equivalent to preceding a {@link #put} call with
        a silent {@code this.values().remove(value)}.
    """
    )
    override fun forcePut(key: K?, value: V?): V? {
        throw java.lang.UnsupportedOperationException("Append-only data structure")
    }

    /**
     * @throws UnsupportedOperationException always.
     */
    @Deprecated(
        """Not supported.
    """
    )
    override fun remove(key: Any?): V? {
        throw java.lang.UnsupportedOperationException("Append-only data structure")
    }

    override fun putAll(map: MutableMap<out K?, out V?>) {
        for (entry in map.entrySet()) {
            put(entry.getKey(), entry.getValue())
        }
    }

    /**
     * @throws UnsupportedOperationException always.
     */
    @Deprecated(
        """Not supported.
    """
    )
    override fun clear() {
        throw java.lang.UnsupportedOperationException("Append-only data structure")
    }

    /**
     * {@inheritDoc}
     * 
     * 
     * Removing a key from the set does not change the key's order if it was tracked prior to
     * removal. Removal is supported only for consistency with [values].
     */
    override fun keySet(): MutableSet<K?> {
        return Collections.unmodifiableSet<K?>(contents.keySet())
    }

    /**
     * {@inheritDoc}
     * 
     * 
     * Removing a value from the set does not change the key's order if it was tracked prior to
     * removal. Ideally, we would not want to support removal, but it is required for [ ][PackageFunction.handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions].
     */
    override fun values(): MutableSet<V?> {
        return Collections.unmodifiableSet<V?>(contents.values())
    }

    /**
     * {@inheritDoc}
     * 
     * 
     * Removing an entry from the set does not change the key's order if it was tracked prior to
     * removal. Removal is supported only for consistency with [values].
     */
    override fun entrySet(): MutableSet<MutableMap.MutableEntry<K?, V?>?> {
        return Collections.unmodifiableSet<MutableMap.MutableEntry<K?, V?>?>(contents.entrySet())
    }

    /**
     * {@inheritDoc}
     * 
     * 
     * The returned map is unmodifiable (all modifications will throw an [ ].
     */
    override fun inverse(): com.google.common.collect.BiMap<V?, K?> {
        return com.google.common.collect.Maps.unmodifiableBiMap<V?, K?>(contents.inverse())
    }

    private fun startedOrderTracking(): Boolean {
        com.google.common.base.Preconditions.checkState((trackedKeys == null) == (trackedKeyOrders == null))
        return trackedKeys != null
    }

    private fun ensureOrderTracking() {
        if (!startedOrderTracking()) {
            trackedKeys = java.util.ArrayList<K?>()
            trackedKeyOrders = HashMap<K?, Int?>()

            contents.forEach(
                java.util.function.BiConsumer { key: K?, value: V? ->
                    if (track.test(value)) {
                        recordKeyOrder(key)
                    }
                })
        }
    }

    private fun recordKeyOrder(key: K?) {
        val order: Int = trackedKeys.size()
        trackedKeys.add(key)
        trackedKeyOrders!!.put(key, order)
    }

    /**
     * Returns a lightweight snapshot view of the tracked entries existing in the bimap at the time
     * this method is called.
     * 
     * 
     * Most method calls on the view returned by this method will start insertion order tracking if
     * it has not been started already. In particular, that implies that after this method had been
     * called, a value whose insertion order was tracked may no longer be replaceable with a value
     * whose insertion order is not tracked. See [.put] for details.
     */
    fun getTrackedSnapshot(): MutableMap<K?, V?> {
        return TrackedSnapshot<K?, V?>(this)
    }

    /**
     * A view of a [SnapshottableBiMap]'s contents existing at a certain point in time.
     * 
     * 
     * Iterators over the view's [.keySet], [.entrySet], or [.values] iterate in
     * key insertion order.
     */
    internal class TrackedSnapshot<K, V> private constructor(underlying: SnapshottableBiMap<K?, V?>) :
        AbstractMap<K?, V?>() {
        private val underlying: SnapshottableBiMap<K?, V?>

        // The number of initial elements from `underlying`'s `trackedKeys` list that should be
        // considered to be present in this view. Note that we don't snapshot values, so we'll use
        // whatever the most recent value in `underlying` is even if it changed after this snapshot
        // was created.
        private val sizeTracked: Int

        init {
            this.underlying = underlying
            this.sizeTracked = underlying.sizeTracked()
        }

        override fun containsKey(key: Any?): Boolean {
            val order = underlying.getTrackedKeyOrder(key)
            return order >= 0 && order < sizeTracked
        }

        override fun containsValue(value: Any?): Boolean {
            val key: Any? = underlying.inverse().get(value)
            if (key != null) {
                val order = underlying.getTrackedKeyOrder(key)
                return order >= 0 && order < sizeTracked
            } else {
                return false
            }
        }

        override fun get(key: Any?): V? {
            if (containsKey(key)) {
                return underlying.get(key)
            } else {
                return null
            }
        }

        /**
         * @throws UnsupportedOperationException always.
         */
        @Deprecated(
            """Unsupported operation.
      """
        )
        override fun put(key: K?, value: V?): V? {
            throw java.lang.UnsupportedOperationException("Read-only snapshot")
        }

        /**
         * @throws UnsupportedOperationException always.
         */
        @Deprecated(
            """Unsupported operation.
      """
        )
        override fun remove(key: Any?): V? {
            throw java.lang.UnsupportedOperationException("Read-only snapshot")
        }

        /**
         * @throws UnsupportedOperationException always.
         */
        @Deprecated(
            """Unsupported operation.
      """
        )
        override fun putAll(m: MutableMap<out K?, out V?>?) {
            throw java.lang.UnsupportedOperationException("Read-only snapshot")
        }

        /**
         * @throws UnsupportedOperationException always.
         */
        @Deprecated(
            """Unsupported operation.
      """
        )
        override fun clear() {
            throw java.lang.UnsupportedOperationException("Read-only snapshot")
        }

        override fun entrySet(): MutableSet<MutableMap.MutableEntry<K?, V?>?> {
            return object : UnmodifiableSet<MutableMap.MutableEntry<K?, V?>?>() {
                override fun size(): Int {
                    return sizeTracked
                }

                override fun isEmpty(): Boolean {
                    return sizeTracked == 0
                }

                override fun contains(`object`: Any?): Boolean {
                    if (`object` !is MutableMap.MutableEntry<*, *>) {
                        return false
                    }
                    val entry = `object`
                    return this@TrackedSnapshot.containsKey(entry.getKey())
                            && this@TrackedSnapshot.containsValue(entry.getValue())
                }

                override fun iterator(): MutableIterator<MutableMap.MutableEntry<K?, V?>?> {
                    return object : com.google.common.collect.UnmodifiableIterator<MutableMap.MutableEntry<K?, V?>?>() {
                        private var nextOrder = 0

                        override fun hasNext(): Boolean {
                            return nextOrder < this@TrackedSnapshot.sizeTracked
                        }

                        override fun next(): MutableMap.MutableEntry<K?, V?> {
                            if (!hasNext()) {
                                throw java.util.NoSuchElementException()
                            }
                            val key = this@TrackedSnapshot.underlying.getTrackedKey(nextOrder)
                            val value = this@TrackedSnapshot.underlying.get(key)
                            nextOrder++
                            return AbstractMap.SimpleEntry<K?, V?>(key, value)
                        }
                    }
                }
            }
        }

        private abstract class UnmodifiableSet<E> : AbstractSet<E?>() {
            /**
             * @throws UnsupportedOperationException always.
             */
            @Deprecated(
                """Unsupported operation.
        """
            )
            override fun add(entry: E?): Boolean {
                throw java.lang.UnsupportedOperationException()
            }

            /**
             * @throws UnsupportedOperationException always.
             */
            @Deprecated(
                """Unsupported operation.
        """
            )
            override fun remove(o: Any?): Boolean {
                throw java.lang.UnsupportedOperationException()
            }

            /**
             * @throws UnsupportedOperationException always.
             */
            @Deprecated(
                """Not implemented due to lack of need.
        """
            )
            override fun containsAll(c: MutableCollection<*>?): Boolean {
                throw java.lang.UnsupportedOperationException()
            }

            /**
             * @throws UnsupportedOperationException always.
             */
            @Deprecated(
                """Unsupported operation.
        """
            )
            override fun addAll(c: MutableCollection<out E?>?): Boolean {
                throw java.lang.UnsupportedOperationException()
            }

            /**
             * @throws UnsupportedOperationException always.
             */
            @Deprecated(
                """Unsupported operation.
        """
            )
            override fun retainAll(c: MutableCollection<*>?): Boolean {
                throw java.lang.UnsupportedOperationException()
            }

            /**
             * @throws UnsupportedOperationException always.
             */
            @Deprecated(
                """Unsupported operation.
        """
            )
            override fun removeAll(c: MutableCollection<*>?): Boolean {
                throw java.lang.UnsupportedOperationException()
            }

            /**
             * @throws UnsupportedOperationException always.
             */
            @Deprecated(
                """Unsupported operation.
        """
            )
            override fun clear() {
                throw java.lang.UnsupportedOperationException()
            }
        }
    }
}
