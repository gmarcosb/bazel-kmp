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
package com.google.devtools.build.lib.remote.util

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.CompletableEmitter
import io.reactivex.rxjava3.core.CompletableObserver
import io.reactivex.rxjava3.core.CompletableOnSubscribe
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.SingleEmitter
import io.reactivex.rxjava3.core.SingleObserver
import io.reactivex.rxjava3.core.SingleOnSubscribe
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.exceptions.Exceptions
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Methods for interoperating between Rx and ListenableFuture.  */
object RxFutures {
    /**
     * Returns a [Completable] that is complete once the supplied [ListenableFuture] has
     * completed.
     * 
     * 
     * A [ListenableFuture] represents some computation that is already in progress. We use
     * [Supplier] here to defer the execution of the thing that produces ListenableFuture until
     * there is subscriber.
     * 
     * 
     * Errors are also propagated except for certain "fatal" exceptions defined by rxjava. Multiple
     * subscriptions are not allowed.
     * 
     * 
     * Disposes the Completable to cancel the underlying ListenableFuture.
     */
    fun toCompletable(
        supplier: io.reactivex.rxjava3.functions.Supplier<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>>,
        executor: java.util.concurrent.Executor
    ): Completable? {
        return Completable.create(OnceCompletableOnSubscribe(supplier, executor))
    }

    /**
     * Returns a [Single] that is complete once the supplied [ListenableFuture] has
     * completed.
     * 
     * 
     * A [ListenableFuture] represents some computation that is already in progress. We use
     * [Supplier] here to defer the execution of the thing that produces ListenableFuture until
     * there is subscriber.
     * 
     * 
     * Errors are also propagated except for certain "fatal" exceptions defined by rxjava. Multiple
     * subscriptions are not allowed.
     * 
     * 
     * Disposes the Single to cancel the underlying ListenableFuture.
     */
    fun <T> toSingle(
        supplier: io.reactivex.rxjava3.functions.Supplier<com.google.common.util.concurrent.ListenableFuture<T?>>,
        executor: java.util.concurrent.Executor
    ): Single<T?>? {
        return Single.create<T?>(OnceSingleOnSubscribe<T?>(supplier, executor))
    }

    /**
     * Returns a [ListenableFuture] that is complete once the [Completable] has completed.
     * 
     * 
     * Errors are also propagated. If the [ListenableFuture] is canceled, the subscription to
     * the [Completable] will automatically be cancelled.
     */
    fun toListenableFuture(completable: Completable): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        completable.subscribe(
            object : CompletableObserver() {
                override fun onSubscribe(d: Disposable) {
                    future.addListener(
                        java.lang.Runnable {
                            if (future.isCancelled()) {
                                d.dispose()
                            }
                        },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )
                }

                override fun onComplete() {
                    // Making the Completable as complete.
                    future.set(null)
                }

                override fun onError(e: Throwable) {
                    if (e is java.lang.InterruptedException) {
                        future.cancel(true)
                    } else if (e is CancellationException) {
                        future.cancel(true)
                    } else {
                        future.setException(e)
                    }
                }
            })
        return future
    }

    /**
     * Returns a [ListenableFuture] that is complete once the [Single] has succeeded.
     * 
     * 
     * Errors are also propagated. If the [ListenableFuture] is canceled, the subscription to
     * the [Single] will automatically be cancelled.
     */
    fun <T> toListenableFuture(single: Single<T?>): com.google.common.util.concurrent.ListenableFuture<T?> {
        val future: com.google.common.util.concurrent.SettableFuture<T?> =
            com.google.common.util.concurrent.SettableFuture.create<T?>()
        single.subscribe(
            object : SingleObserver<T?>() {
                override fun onSubscribe(d: Disposable) {
                    future.addListener(
                        java.lang.Runnable {
                            if (future.isCancelled()) {
                                d.dispose()
                            }
                        },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )
                }

                override fun onSuccess(t: T) {
                    future.set(t)
                }

                override fun onError(e: Throwable) {
                    if (e is CancellationException) {
                        future.cancel(true)
                    } else {
                        future.setException(e)
                    }
                }
            })
        return future
    }

    private class OnceCompletableOnSubscribe(
        supplier: io.reactivex.rxjava3.functions.Supplier<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>>,
        executor: java.util.concurrent.Executor
    ) : CompletableOnSubscribe {
        private val subscribed: AtomicBoolean = AtomicBoolean(false)

        private val supplier: io.reactivex.rxjava3.functions.Supplier<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>>
        private val executor: java.util.concurrent.Executor

        init {
            this.supplier = supplier
            this.executor = executor
        }

        @Throws(Throwable::class)
        override fun subscribe(emitter: CompletableEmitter) {
            try {
                com.google.common.base.Preconditions.checkState(
                    !subscribed.getAndSet(true),
                    "This completable cannot be subscribed to twice"
                )
                val future: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> = supplier.get()
                com.google.common.util.concurrent.Futures.addCallback<java.lang.Void?>(
                    future,
                    object : com.google.common.util.concurrent.FutureCallback<java.lang.Void?> {
                        override fun onSuccess(t: java.lang.Void?) {
                            emitter.onComplete()
                        }

                        override fun onFailure(throwable: Throwable) {
                            /*
                 * CancellationException can be thrown in two cases:
                 *   1. The ListenableFuture itself is cancelled.
                 *   2. Completable is disposed by downstream.
                 *
                 * This check is used to prevent propagating CancellationException to downstream
                 * when it has already disposed the Completable.
                 */
                            if (throwable is CancellationException && emitter.isDisposed()) {
                                return
                            }

                            emitter.onError(throwable)
                        }
                    },
                    executor
                )
                emitter.setCancellable(io.reactivex.rxjava3.functions.Cancellable { future.cancel(true) })
            } catch (t: Throwable) {
                // We failed to construct and listen to the LF. Following RxJava's own behaviour, prefer
                // to pass RuntimeExceptions and Errors down to the subscriber except for certain
                // "fatal" exceptions.
                Exceptions.throwIfFatal(t)
                executor.execute(java.lang.Runnable { emitter.onError(t) })
            }
        }
    }

    private class OnceSingleOnSubscribe<T>(
        supplier: io.reactivex.rxjava3.functions.Supplier<com.google.common.util.concurrent.ListenableFuture<T?>>,
        executor: java.util.concurrent.Executor
    ) : SingleOnSubscribe<T?> {
        private val subscribed: AtomicBoolean = AtomicBoolean(false)

        private val supplier: io.reactivex.rxjava3.functions.Supplier<com.google.common.util.concurrent.ListenableFuture<T?>>
        private val executor: java.util.concurrent.Executor

        init {
            this.supplier = supplier
            this.executor = executor
        }

        @Throws(Throwable::class)
        override fun subscribe(emitter: SingleEmitter<T?>) {
            try {
                com.google.common.base.Preconditions.checkState(
                    !subscribed.getAndSet(true),
                    "This single cannot be subscribed to twice"
                )
                val future: com.google.common.util.concurrent.ListenableFuture<T?> = supplier.get()
                com.google.common.util.concurrent.Futures.addCallback<T?>(
                    future,
                    object : com.google.common.util.concurrent.FutureCallback<T?> {
                        override fun onSuccess(t: T?) {
                            emitter.onSuccess(t)
                        }

                        override fun onFailure(throwable: Throwable) {
                            /*
                 * CancellationException can be thrown in two cases:
                 *   1. The ListenableFuture itself is cancelled.
                 *   2. Single is disposed by downstream.
                 *
                 * This check is used to prevent propagating CancellationException to downstream
                 * when it has already disposed the Single.
                 */
                            if (throwable is CancellationException && emitter.isDisposed()) {
                                return
                            }

                            emitter.onError(throwable)
                        }
                    },
                    executor
                )
                emitter.setCancellable(io.reactivex.rxjava3.functions.Cancellable { future.cancel(true) })
            } catch (t: Throwable) {
                // We failed to construct and listen to the LF. Following RxJava's own behaviour, prefer
                // to pass RuntimeExceptions and Errors down to the subscriber except for certain
                // "fatal" exceptions.
                Exceptions.throwIfFatal(t)
                executor.execute(java.lang.Runnable { emitter.onError(t) })
            }
        }
    }
}
