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

import com.google.common.base.Preconditions

/**
 * An [InterruptibleSupplier] which should cache the instance retrieved during the first call
 * to [.get] and returns that value on subsequent calls to [.get].
 * 
 * 
 * This is similar to, but not exactly the same as, what is returned by [ ][com.google.common.base.Suppliers.memoize].
 * 
 * 
 * Implementations should be thread-safe.
 * 
 * 
 * Unlike that implementation, this is not serializable, and its initialized state (whether an
 * instance has been retrieved) is visible via [.isInitialized].
 */
interface MemoizingInterruptibleSupplier<T> : InterruptibleSupplier<T?> {
    /** Returns `true` if the result of [.get] is readily available.  */
    @kotlin.jvm.JvmField
    val isInitialized: Boolean

    /** Memoizes the result of `delegate` after the first call to [.get].  */
    class DelegatingMemoizingSupplier<T> private constructor(delegate: InterruptibleSupplier<T?>?) :
        MemoizingInterruptibleSupplier<T?> {
        private var delegate: InterruptibleSupplier<T?>?

        @kotlin.concurrent.Volatile
        private var value: T? = null

        init {
            this.delegate = Preconditions.checkNotNull<InterruptibleSupplier<T?>?>(delegate)
        }

        @Throws(InterruptedException::class)
        override fun get(): T? {
            if (value != null) {
                return value
            }
            synchronized(this) {
                if (value == null) {
                    value = delegate!!.get()
                    delegate = null // Free up for GC.
                }
            }
            return value
        }

        override fun isInitialized(): Boolean {
            return value != null
        }
    }

    companion object {
        fun <T> of(delegate: InterruptibleSupplier<T?>): MemoizingInterruptibleSupplier<T?> {
            if (delegate is MemoizingInterruptibleSupplier<*>) {
                return delegate as MemoizingInterruptibleSupplier<T?>
            }
            return DelegatingMemoizingSupplier<T?>(delegate)
        }
    }
}
