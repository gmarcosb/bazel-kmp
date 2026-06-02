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
package com.google.devtools.build.lib.graph

import com.google.devtools.build.lib.collect.compacthashset.CompactHashSet
import java.util.*

/**
 * Wraps collection implementation. Automatically switches from one to another implementation
 * depending on the number of storing elements.
 * 
 * 
 * Effective collection implementation depends on the size of collection.
 * 
 * 
 *  * For 1 - singleton immutable List.
 *  * For [2..6] - ArrayList.
 *  * For [7...) - CompactHashSet.
 * 
 * 
 * @param <T>
</T> */
internal class ConcurrentCollectionWrapper<T> {
    // The succs and preds set representation changes depending on its size.
    // It is implemented using the following collections:
    // - null for size = 0.
    // - Collections$SingletonList for size = 1.
    // - ArrayList(6) for size = [2..6].
    // - CompactHashSet(12) for size > 6.
    // These numbers were chosen based on profiling.
    // TODO(dbabkin): according to VCS history this profiling info was obtained for
    // ArrayList/HashSet. Then HashSet had been replaced by CompactHashSet. Optimal threshold for
    // ArrayList/CompactHashSet may differ from 6.
    @kotlin.concurrent.Volatile
    private var collection: MutableCollection<T?>? = null

    /**
     * Returns `Collections.unmodifiableCollection` wrapper around collection. Iteration over
     * returned collection at the same time with concurrent modification will cause `java.util.ConcurrentModificationException`
     */
    fun get(): MutableCollection<T?> {
        val collection = this.collection
        return if (collection == null)
            Collections.emptyList<T?>()
        else
            Collections.unmodifiableCollection<T?>(collection)
    }

    @kotlin.jvm.Synchronized
    fun clear(): MutableCollection<T?> {
        val old = collection
        collection = null
        return if (old != null) old else Collections.emptyList<T?>()
    }

    fun size(): Int {
        val collection = this.collection
        return if (collection == null) 0 else collection.size()
    }

    /**
     * Adds 'value' to wrapped collection. Replacing this collection instance for CompactHashSet from
     * ArrayList.
     * 
     * @return `true` if the collection was modified; `false` if the collection was not
     * modified
     */
    @kotlin.jvm.Synchronized
    fun add(value: T?): Boolean {
        val collection = this.collection

        if (collection == null) {
            // null -> SingletonList
            this.collection = Collections.singletonList<T?>(value)
            return true
        }
        if (collection.contains(value)) {
            // already exists in this collection
            return false
        }
        val previousSize: Int = collection.size()

        if (previousSize == 1) {
            // SingletonList -> ArrayList
            val newList: MutableCollection<T?> = ArrayList<T?>(ARRAYLIST_THRESHOLD)
            newList.addAll(collection)
            newList.add(value)
            this.collection = newList
        } else if (previousSize < ARRAYLIST_THRESHOLD) {
            // ArrayList
            collection.add(value)
        } else if (previousSize == ARRAYLIST_THRESHOLD) {
            // ArrayList -> CompactHashSet
            val newSet: MutableCollection<T?> = CompactHashSet.createWithExpectedSize<T?>(INITIAL_HASHSET_CAPACITY)
            newSet.addAll(collection)
            newSet.add(value)
            this.collection = newSet
        } else {
            // HashSet
            collection.add(value)
        }
        return true
    }

    /**
     * Removes 'value' from wrapped collection. Replacing this collection instance for ArrayList from
     * CompactHashSet.
     * 
     * @return `true` if the collection was modified; `false` if the set collection not
     * modified
     */
    @kotlin.jvm.Synchronized
    fun remove(value: T?): Boolean {
        val collection = this.collection
        if (collection == null) {
            // null
            return false
        }

        val previousSize: Int = collection.size()
        if (previousSize == 1) {
            if (collection.contains(value)) {
                // -> null
                this.collection = null
                return true
            } else {
                return false
            }
        }
        // now remove the value
        if (collection.remove(value)) {
            // may need to change representation
            if (previousSize == 2) {
                // -> SingletonList
                val list = Collections.singletonList<T?>(collection.iterator().next())
                this.collection = list
                return true
            } else if (previousSize == 1 + ARRAYLIST_THRESHOLD) {
                // -> ArrayList
                val newArrayList: MutableCollection<T?> = ArrayList<T?>(ARRAYLIST_THRESHOLD)
                newArrayList.addAll(collection)
                this.collection = newArrayList
                return true
            }
            return true
        }
        return false
    }

    companion object {
        private const val ARRAYLIST_THRESHOLD = 6
        private const val INITIAL_HASHSET_CAPACITY = 12
    }
}
