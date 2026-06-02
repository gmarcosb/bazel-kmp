// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.supplier

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Preconditions
import com.google.errorprone.annotations.ForOverride
import java.lang.ref.SoftReference

/**
 * An [InterruptibleSupplier] which holds a [SoftReference] to a cached value. The value
 * can be evicted from memory by GC, and [computed][.computeValue] if requested again.
 * 
 * 
 * It is not guaranteed that the value will be equal to the previously cached one. This behavior
 * is determined by the subclass which implements [.computeValue].
 */
abstract class EvictableSupplier<T> protected constructor(cachedValue: T?) : InterruptibleSupplier<T?> {
    @kotlin.concurrent.Volatile
    private var valueReference: SoftReference<T?>

    /**
     * Creates an `EvictableSupplier`.
     * 
     * @param cachedValue an already known cached value, or `null` if the value should always be
     * computed on the first call to [.get]
     */
    init {
        this.valueReference = SoftReference<T?>(cachedValue)
    }

    @Throws(InterruptedException::class)
    override fun get(): T? {
        var value = valueReference.get()
        if (value != null) {
            return value
        }

        // Ensure that at most one thread is computing the value.
        synchronized(this) {
            value = valueReference.get()
            if (value != null) {
                return value
            }

            value = Preconditions.checkNotNull<T?>(computeValue())
            valueReference = SoftReference<T?>(value)
            return value
        }
    }

    /**
     * Computes the supplied value.
     * 
     * 
     * This method is called (under a lock on `this`) when the cached value is unavailable,
     * either because it was not initially supplied via the constructor, or because it was evicted by
     * GC.
     * 
     * 
     * Must not return `null`.
     */
    @ForOverride
    @Throws(InterruptedException::class)
    protected abstract fun computeValue(): T?

    /** Clears the soft reference. Only used in tests.  */
    @VisibleForTesting
    fun evictForTesting() {
        valueReference.clear()
    }

    /**
     * Returns the value if it is currently in memory.
     * 
     * 
     * If the value is not in memory, `null` will be returned. No attempt will be made to
     * [compute][.computeValue] the value.
     */
    fun peekCachedValue(): T? {
        return valueReference.get()
    }
}
