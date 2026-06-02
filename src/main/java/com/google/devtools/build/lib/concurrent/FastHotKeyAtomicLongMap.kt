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
package com.google.devtools.build.lib.concurrent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A map of atomic long counters. A key whose counter's value is currently zero is _not_
 * automatically removed from the map; use [.clear] to clear the entire map.
 * 
 * 
 * This is very similar to Guava's AtomicLongMap, but optimized for the case where keys are hot,
 * e.g. a high number of concurrent calls to `map.incrementAndGet(k)` and/or
 * `map.decrementAndGet(k)`, for the same key `k)`. Guava's AtomicLongMap uses
 * ConcurrentHashMap#compute, whose implementation unfortunately has internal synchronization even
 * when there's already an internal entry for the key in question.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
class FastHotKeyAtomicLongMap<T> private constructor() {
    private val map: ConcurrentMap<T?, AtomicLong?>

    init {
        this.map = ConcurrentHashMap<T?, AtomicLong?>()
    }

    fun incrementAndGet(key: T?): Long {
        return getCounter(key).incrementAndGet()
    }

    fun decrementAndGet(key: T?): Long {
        return getCounter(key).decrementAndGet()
    }

    fun asImmutableMap(): com.google.common.collect.ImmutableMap<T?, Long?> {
        return com.google.common.collect.ImmutableMap.copyOf<T?, Long?>(
            com.google.common.collect.Maps.transformValues<T?, AtomicLong?, Long?>(
                map,
                com.google.common.base.Function { obj: AtomicLong? -> obj.get() })
        )
    }

    /**
     * Returns the [AtomicLong] for the given `element`. Mutations to this
     * [AtomicLong] will be reflected in the [FastHotKeyAtomicLongMap]: for example,
     * `map.getCounter(e).incrementAndGet()` has exactly the same side effects as
     * `map.incrementAndGet(e)`.
     * 
     * 
     * Consider using this method when you have a super-hot key that you know about a priori.
     * Prefer [.incrementAndGet] and [.decrementAndGet] otherwise.
     */
    fun getCounter(element: T?): AtomicLong {
        // Optimize for the case where 'element' is already in our map. See the class javadoc.
        val counter: AtomicLong? = map.get(element)
        return if (counter != null) counter else map.computeIfAbsent(
            element,
            java.util.function.Function { s: T? -> AtomicLong(0) })
    }

    /**
     * Clears the [FastHotKeyAtomicLongMap].
     * 
     * 
     * Any [AtomicLong] instances previously returned by a call to [.getCounter] are
     * now meaningless: mutations to them will not be reflected in the
     * [FastHotKeyAtomicLongMap].
     */
    fun clear() {
        map.clear()
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun <T> create(): FastHotKeyAtomicLongMap<T?> {
            return com.google.devtools.build.lib.concurrent.FastHotKeyAtomicLongMap<T?>()
        }

        // TODO(kak): Delete this in favor of create()
        fun <T> create(concurrencyLevel: Int /* ignored */): FastHotKeyAtomicLongMap<T?> {
            return com.google.devtools.build.lib.concurrent.FastHotKeyAtomicLongMap<T?>()
        }
    }
}
