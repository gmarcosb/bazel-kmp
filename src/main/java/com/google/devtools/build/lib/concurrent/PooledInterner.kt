// Copyright 2023 The Bazel Authors. All rights reserved.
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

import java.util.Collections

/**
 * An extension of the weak interner which uses a global pool in addition to the weak interner's
 * `ConcurrentHashMap` to store instances.
 * 
 * 
 * The reason of implementing [PooledInterner] is that the same object can be stored in
 * both weak interner and some other container (`InMemoryGraphImple#nodeMap`) in blaze with
 * two equal references, causing some memory overhead.
 * 
 * 
 * [PooledInterner] enables the client to manage where a single object is stored,
 * addressing the memory overhead issue. In more detail,
 * 
 * 
 *  * If the object is already canonicalized in the global pool, it should not be stored in
 * [.weakInterner] again, thus removing the storage overhead of using a traditional weak
 * interner;
 *  * User can also remove the object from [.weakInterner]'s underlying [       ][.internerAsMap] when the object appears in the global pool.
 * 
 * 
 * 
 * Subclasses are only responsible for providing the appropriate [Pool] by overriding
 * [.getPool] method.
 */
abstract class PooledInterner<T> protected constructor() : com.google.common.collect.Interner<T?> {
    private var weakInterner: com.google.common.collect.Interner<T?> =
        com.google.devtools.build.lib.concurrent.BlazeInterners.newWeakInterner<T?>()
    private var internerAsMap: MutableMap<*, *>? = null
    fun <Object> getMapReflectively()

    init {
        com.google.devtools.build.lib.concurrent.PooledInterner.Companion.instances.add(this)
    }

    /**
     * Interns `sample` directly into [.weakInterner] without checking the global pool and
     * returns the canonical instance of `sample`.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun weakIntern(sample: T?): T? {
        return weakInterner.intern(sample)
    }

    /**
     * Removes sample from the weak interner. Client can call this method when the sample is already
     * stored in the global pool in order to reduce the memory overhead.
     */
    fun removeWeak(sample: Any?) {
        internerAsMap!!.remove(sample)
    }

    /**
     * Returns the canonical instance of `sample` from either global pool or [ ][.weakInterner].
     */
    override fun intern(sample: T?): T? {
        val pool = this.pool
        return if (pool != null) pool.getOrWeakIntern(sample) else weakInterner.intern(sample)
    }

    @get:com.google.errorprone.annotations.ForOverride
    protected abstract val pool: Pool<T?>?

    fun size(): Int {
        return internerAsMap.size()
    }

    /** Shrinks the weak interner and obtain a new reference to the newly shrunk map.  */
    private fun shrink() {
        this.weakInterner =
            com.google.devtools.build.lib.concurrent.PooledInterner.Companion.shrinkAsNewWeakInterner<T?>(weakInterner)
        this.internerAsMap =
            com.google.devtools.build.lib.concurrent.PooledInterner.Companion.getMapReflectively<Any?>(weakInterner)
    }

    /**
     * An alternative container to the weak interner for storing type T instance.
     * 
     * 
     * A pool is a storage space that already exists during normal program execution and provides
     * lookup functionality for interning, thus eliminating storage overhead from using a classic weak
     * interner.
     */
    interface Pool<T> {
        /**
         * Returns the canonical instance for the given key in the pool if it is present, otherwise
         * interns the key using its [weak interner][.weakIntern].
         * 
         * 
         * To ensure a single canonical instance, if the key is not present in the pool, it should be
         * weakly interned using synchronization so that it is not concurrently [ removed from the weak interner][.removeWeak].
         */
        fun getOrWeakIntern(sample: T?): T?
    }

    companion object {
        /**
         * Holds all PooledInterner instances registered in the lifetime of this Blaze server as a weak
         * collection to prevent memory leaks. This is also thread-safe to handle multiple PooledInterners
         * from being instantiated concurrently, for instance during class initializations.
         */
        private val instances: MutableSet<PooledInterner<*>?> = Collections.newSetFromMap<PooledInterner<*>?>(
            com.google.common.collect.MapMaker().weakKeys().makeMap<PooledInterner<*>?, Boolean?>()
        )

        /**
         * Shrinks all interner instances' backing map to reclaim memory.
         * 
         * 
         * This needs a prior GC to be effective.
         * 
         * 
         * WARNING: This must not be called concurrently with any interning operations, because it
         * provides unsynchronized access to multiple mutable static interners.
         */
        @kotlin.jvm.JvmStatic
        @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadHostile
        fun shrinkAll() {
            com.google.devtools.build.lib.concurrent.PooledInterner.Companion.instances.forEach(java.util.function.Consumer { obj: PooledInterner<*>? -> obj!!.shrink() })
        }

        /**
         * Shrink an interner by rebuilding a new weak interner and backing map/array. Use this if you
         * expect a GC to clear references into an interner's backing map.
         * 
         * 
         * If there are references to the backing map, use `getMapReflectively` to update them.
         * 
         * 
         * This is created because backing maps do not automatically resize after removing entries.
         */
        private fun <T> shrinkAsNewWeakInterner(fromInterner: com.google.common.collect.Interner<T?>): com.google.common.collect.Interner<T?> {
            val toInterner: com.google.common.collect.Interner<T?> =
                com.google.devtools.build.lib.concurrent.BlazeInterners.newWeakInterner<T?>()
            val map: MutableMap<T?, *> =
                com.google.devtools.build.lib.concurrent.PooledInterner.Companion.getMapReflectively<T?>(fromInterner)
            map.keySet().parallelStream()
                .forEach(
                    java.util.function.Consumer { k: T? ->
                        val unused: T? = toInterner.intern(k)
                    })
            return toInterner
        }

        // Returns the backing map of an interner.
        //
        // There was a Guava API review to include the feature of removing from an interner, and the
        // outcome was that we should just get and manipulate the map reflectively.
        //
        // See the description for cl/623798951 for additional context.
        private fun <T> getMapReflectively(interner: com.google.common.collect.Interner<*>): MutableMap<T?, *> {
            try {
                val field: java.lang.reflect.Field = interner.getClass().getDeclaredField("map")
                field.setAccessible(true)
                return (field.get(interner) as kotlin.collections.MutableMap<T?, *>?)!!
            } catch (e: java.lang.ReflectiveOperationException) {
                throw java.lang.IllegalStateException(e)
            }
        }
    }
}
