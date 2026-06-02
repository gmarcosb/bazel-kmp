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
package com.google.devtools.build.lib.concurrent

import java.util.concurrent.ExecutionException

/** Utility class for working with futures.  */
object MoreFutures {
    /**
     * Creates a new `ListenableFuture` whose value is a list containing the values of all its
     * input futures, if all succeed. If any input fails, the returned future fails. If any of the
     * futures fails, it cancels all the other futures.
     * 
     * 
     * This method is similar to `Futures.allAsList` but additionally it cancels all the
     * futures in case any of them fails.
     */
    fun <V> allAsListOrCancelAll(
        futures: Iterable<out com.google.common.util.concurrent.ListenableFuture<out V?>>
    ): com.google.common.util.concurrent.ListenableFuture<MutableList<V?>?> {
        val combinedFuture: com.google.common.util.concurrent.ListenableFuture<MutableList<V?>?> =
            com.google.common.util.concurrent.Futures.allAsList<V?>(futures)
        com.google.common.util.concurrent.Futures.addCallback<MutableList<V?>?>(
            combinedFuture,
            object : com.google.common.util.concurrent.FutureCallback<MutableList<V?>?> {
                override fun onSuccess(vs: MutableList<V?>?) {}

                /**
                 * In case of a failure of any of the futures (that gets propagated to combinedFuture) we
                 * cancel all the futures in the list.
                 */
                override fun onFailure(ignore: Throwable) {
                    for (future in futures) {
                        future.cancel(true)
                    }
                }
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
        return combinedFuture
    }

    /**
     * Returns the result of `future`. If it threw an [InterruptedException] (wrapped in
     * an [ExecutionException]), throws that underlying [InterruptedException]. Crashes on
     * all other exceptions.
     * 
     * 
     * If `cancelOnInterrupt` is true, the future is cancelled if it threw an [ ].
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    fun <R> waitForFutureAndGet(future: java.util.concurrent.Future<R?>, cancelOnInterrupt: Boolean): R? {
        try {
            return future.get()
        } catch (e: ExecutionException) {
            com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                e.getCause(),
                java.lang.InterruptedException::class.java
            )
            com.google.common.base.Throwables.throwIfUnchecked(e.getCause())
            throw java.lang.IllegalStateException(e)
        } catch (e: java.lang.InterruptedException) {
            if (cancelOnInterrupt) {
                future.cancel( /* mayInterruptIfRunning= */true)
            }
            throw e
        }
    }

    @Throws(E::class, java.lang.InterruptedException::class)
    fun <R, E : java.lang.Exception?> waitForFutureAndGetWithCheckedException(
        future: java.util.concurrent.Future<R?>, cancelOnInterrupt: Boolean, exceptionClass: java.lang.Class<E?>
    ): R? {
        return com.google.devtools.build.lib.concurrent.MoreFutures.waitForFutureAndGetWithCheckedException<R?, E?, java.lang.RuntimeException?>(
            future,
            cancelOnInterrupt,
            exceptionClass,
            null
        )
    }

    @Throws(E1::class, E2::class, java.lang.InterruptedException::class)
    fun <R, E1 : java.lang.Exception?, E2 : java.lang.Exception?>
            waitForFutureAndGetWithCheckedException(
        future: java.util.concurrent.Future<R?>,
        cancelOnInterrupt: Boolean,
        exceptionClass1: java.lang.Class<E1?>,
        exceptionClass2: java.lang.Class<E2?>?
    ): R? {
        return com.google.devtools.build.lib.concurrent.MoreFutures.waitForFutureAndGetWithCheckedException<R?, E1?, E2?, java.lang.RuntimeException?>(
            future, cancelOnInterrupt, exceptionClass1, exceptionClass2, null
        )
    }

    @Throws(E1::class, E2::class, E3::class, java.lang.InterruptedException::class)
    fun <R, E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?>
            waitForFutureAndGetWithCheckedException(
        future: java.util.concurrent.Future<R?>,
        cancelOnInterrupt: Boolean,
        exceptionClass1: java.lang.Class<E1?>,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?
    ): R? {
        try {
            return future.get()
        } catch (e: ExecutionException) {
            com.google.common.base.Throwables.throwIfInstanceOf<E1?>(e.getCause(), exceptionClass1)
            if (exceptionClass2 != null) {
                com.google.common.base.Throwables.throwIfInstanceOf<E2?>(e.getCause(), exceptionClass2)
            }
            if (exceptionClass3 != null) {
                com.google.common.base.Throwables.throwIfInstanceOf<E3?>(e.getCause(), exceptionClass3)
            }
            com.google.common.base.Throwables.throwIfUnchecked(e.getCause())
            com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                e.getCause(),
                java.lang.InterruptedException::class.java
            )
            throw java.lang.IllegalStateException(e)
        } catch (e: java.lang.InterruptedException) {
            if (cancelOnInterrupt) {
                future.cancel( /* mayInterruptIfRunning= */true)
            }
            throw e
        }
    }
}
