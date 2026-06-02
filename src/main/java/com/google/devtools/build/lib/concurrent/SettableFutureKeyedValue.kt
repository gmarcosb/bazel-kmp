// Copyright 2024 The Bazel Authors. All rights reserved.
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

/**
 * A specialized settable future designed for use within recursive future-valued [ ]s.
 * 
 * 
 * This class optimizes scenarios where a map stores either futures or computed values. It aims
 * to save memory by discarding futures and directly replacing them with their computed values upon
 * successful completion. The generic type parameters facilitate this in a type-safe manner.
 * 
 * 
 * Consider a key type `KeyT` and a sealed interface `FutureOrValueT` that permits
 * either a future `FutureT` or a concrete value `ValueT`. The corresponding map would
 * be of type `ConcurrentMap<KeyT, FutureOrValueT>`. `FutureT` would be a subclass of
 * `SettableFutureKeyedValue<FutureT, KeyT, ValueT>` and implement `FutureOrValueT`.
 * 
 * 
 * **Typical Usage:**
 * 
 * 
 *  1. Utilize [ConcurrentMap.computeIfAbsent] to retrieve an existing or create a new
 * `FutureT` instance.
 *  1. Call [.tryTakeOwnership] to establish ownership of the future.
 *  1. If ownership is not established, return the existing `FutureT` instance.
 *  1. If ownership is acquired, the owning thread is responsible for populating the `FutureT` by invoking either [.completeWith] or [.failWith].
 *  1. The completion methods return a `ValueT` or a `FutureT`. The caller should
 * directly return this value.
 * 
 * 
 * 
 * Note that `<T>` is a Curiously Recurring Template Pattern (CRTP) parameter. It enables
 * [.completeWith] and [.failWith] to return the exact
 * `FutureT` type.
 * 
 * 
 * This class is declared as abstract solely to accommodate the type configuration described
 * above, despite having no abstract methods or overridable behavior.
 * 
 * @param <T> The concrete type of the future, following the CRTP (e.g., `MyFuture`).
 * @param <K> The type of the key used in the map.
 * @param <V> The type of the value that the future will eventually hold.
</V></K></T> */
abstract class SettableFutureKeyedValue<T : SettableFutureKeyedValue<T?, K?, V?>?, K, V>
protected constructor(private val key: K?, consumer: java.util.function.BiConsumer<K?, V?>) :
    com.google.common.util.concurrent.AbstractFuture<V?>(), com.google.common.util.concurrent.FutureCallback<V?> {
    private val consumer: java.util.function.BiConsumer<K?, V?>

    /** Used to establish exactly-once ownership of this future with [.tryTakeOwnership].  */
    // set with OWNED_HANDLE
    private val owned = false

    /** See comment at [.verifyComplete].  */
    private var isSet = false

    /** The map key associated with this value.  */
    fun key(): K? {
        return key
    }

    /**
     * Returns true once.
     * 
     * 
     * When using [com.github.benmanes.caffeine.cache.Cache.get] with future values and a
     * mapping function, there's a need to determine which thread owns the future. This method
     * provides such a mechanism.
     * 
     * 
     * When this returns true, the caller must call either [.completeWith] or [ ][.failWith].
     */
    fun tryTakeOwnership(): Boolean {
        return com.google.devtools.build.lib.concurrent.SettableFutureKeyedValue.Companion.OWNED_HANDLE.compareAndSet(
            this,
            false,
            true
        )
    }

    /** Completes this future with a successfully computed value.  */
    // caller should handle return value
    fun completeWith(value: V?): V? {
        com.google.common.base.Preconditions.checkState(set(value), "already set %s", this)
        consumer.accept(key, value)
        isSet = true
        return value
    }

    /**
     * Completes this future with the result of another future.
     * 
     * 
     * This method is used when the computation of the value involves another asynchronous
     * operation. The provided future's result will be used to complete this future, either
     * successfully or exceptionally.
     */
    fun completeWith(future: com.google.common.util.concurrent.ListenableFuture<V?>): T? {
        com.google.common.base.Preconditions.checkState(setFuture(future), "already set %s", this)
        com.google.common.util.concurrent.Futures.addCallback<V?>(
            future,
            this,
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
        isSet = true
        val result = this as T?
        return result
    }

    /** Completes this future with an exception.  */
    fun failWith(e: Throwable): T? {
        // The return value could be false if there are multiple errors.
        if (setException(e)) {
            isSet = true
        }
        val result = this as T?
        return result
    }

    /**
     * Verifies that this future has been completed, either successfully or exceptionally.
     * 
     * 
     * This method should be called in a `finally` block after attempting to complete the
     * future. It helps detect situations where the future was inadvertently left incomplete, which
     * could lead to subtle bugs or deadlocks.
     * 
     * 
     * Note: This check is distinct from checking if the future is done. A future can be completed
     * with another future that is still in progress.
     */
    fun verifyComplete() {
        if (!isSet) {
            com.google.common.base.Preconditions.checkState(
                setException(
                    java.lang.IllegalStateException(
                        "future was unexpectedly unset for " + key + ", look for unchecked exceptions"
                    )
                ),
                this
            )
        }
    }

    /**
     * Implementation of [<].
     * 
     */
    @Deprecated("only for use by {@link #completeWith(ListenableFuture<V>)}")
    override fun onSuccess(value: V?) {
        consumer.accept(key, value) // discards the future wrapper
    }

    /**
     * Implementation of [<].
     * 
     */
    @Deprecated("do not use")
    override fun onFailure(t: Throwable) {
        // Keeps the error in the future.
    }

    /**
     * Creates the future.
     * 
     * @param key The key associated with this future in the map.
     * @param consumer A consumer that accepts the key and the computed value upon successful
     * completion. This is typically used to update the map with the final value, discarding the
     * future. Abstracting this as a consumer accomodates storing values directly in the key,
     * which is cheaper than a separate map when applicable.
     */
    init {
        this.consumer = consumer
    }

    companion object {
        private val OWNED_HANDLE: java.lang.invoke.VarHandle

        init {
            try {
                com.google.devtools.build.lib.concurrent.SettableFutureKeyedValue.Companion.OWNED_HANDLE =
                    java.lang.invoke.MethodHandles.lookup()
                        .findVarHandle(
                            com.google.devtools.build.lib.concurrent.SettableFutureKeyedValue::class.java,
                            "owned",
                            Boolean::class.javaPrimitiveType
                        )
            } catch (e: java.lang.ReflectiveOperationException) {
                throw java.lang.ExceptionInInitializerError(e)
            }
        }
    }
}
