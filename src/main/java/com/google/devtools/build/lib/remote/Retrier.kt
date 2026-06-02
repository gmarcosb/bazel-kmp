// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.remote.Retrier
import com.google.devtools.build.lib.remote.Retrier.ResultClassifier
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Supports retrying the execution of a [Callable] in case of failure.
 * 
 * 
 * The errors that are retried are configurable via a [ResultClassifier]. The delay between
 * executions is specified by a [Backoff]. Additionally, the retrier supports circuit breaking
 * to stop execution in case of high failure rates.
 */
@javax.annotation.concurrent.ThreadSafe
open class Retrier @com.google.common.annotations.VisibleForTesting internal constructor(
    backoffSupplier: java.util.function.Supplier<Backoff?>,
    resultClassifier: ResultClassifier,
    retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService,
    circuitBreaker: CircuitBreaker,
    sleeper: Sleeper
) {
    /** A backoff strategy.  */
    interface Backoff {
        /**
         * Returns the next delay in milliseconds, or a value less than `0` if we should stop
         * retrying.
         */
        fun nextDelayMillis(e: java.lang.Exception?): Long

        /**
         * Returns the number of calls to [.nextDelayMillis] thus far, not counting any
         * calls that returned less than `0`.
         */
        val retryAttempts: Int
    }

    /**
     * The circuit breaker allows to reject execution when failure rates are high.
     * 
     * 
     * The initial state of a circuit breaker is the [State.ACCEPT_CALLS]. Calls are executed
     * and retried in this state. However, if error rates are high a circuit breaker can choose to
     * transition into [State.REJECT_CALLS]. In this state any calls are rejected with a [ ] immediately. A circuit breaker in state [State.REJECT_CALLS] can
     * periodically return a `TRIAL_CALL` state, in which case a call will be executed once and
     * in case of success the circuit breaker may return to state `ACCEPT_CALLS`.
     * 
     * 
     * A circuit breaker implementation must be thread-safe.
     * 
     * @see [CircuitBreaker](https://martinfowler.com/bliki/CircuitBreaker.html)
     */
    interface CircuitBreaker {
        /** The state of the circuit breaker.  */
        enum class State {
            /**
             * Calls are executed and retried in case of failure.
             * 
             * 
             * The circuit breaker can transition into state [State.REJECT_CALLS].
             */
            ACCEPT_CALLS,

            /**
             * A call is executed and not retried in case of failure.
             * 
             * 
             * The circuit breaker can transition into any state.
             */
            TRIAL_CALL,

            /**
             * All calls are rejected.
             * 
             * 
             * The circuit breaker can transition into state [State.TRIAL_CALL].
             */
            REJECT_CALLS
        }

        /** Returns the current [State] of the circuit breaker.  */
        fun state(): State

        /** Called after an execution failed.  */
        fun recordFailure()

        /** Called after an execution succeeded.  */
        fun recordSuccess()
    }

    /** Thrown if the call was stopped by a circuit breaker.  */
    class CircuitBreakerException private constructor() : IOException("Call not executed due to a high failure rate.")

    /** Determines whether the result of a call is success, retriable failure or permanent failure.  */
    fun interface ResultClassifier {
        /** The result of a call execution.  */
        enum class Result {
            /** A call is executed successfully.  */
            SUCCESS,

            /** A call execution is failed with retriable error.  */
            TRANSIENT_FAILURE,

            /** A call execution is failed with permanent error.  */
            PERMANENT_FAILURE
        }

        /** Returns the [Result] of the call execution.  */
        fun test(e: java.lang.Exception?): Result
    }

    /**
     * [Sleeper.sleep] is called to pause between synchronous retries ([ ][.execute].
     */
    interface Sleeper {
        @Throws(java.lang.InterruptedException::class)
        fun sleep(millis: Long)
    }

    /** No backoff.  */
    class ZeroBackoff(private val maxRetries: Int) : Backoff {
        private var retries = 0

        override fun nextDelayMillis(e: java.lang.Exception?): Long {
            if (retries >= maxRetries) {
                return -1
            }
            retries++
            return 0
        }

        override fun getRetryAttempts(): Int {
            return retries
        }
    }

    private val backoffSupplier: java.util.function.Supplier<Backoff?>
    private val resultClassifier: ResultClassifier
    val circuitBreaker: CircuitBreaker
    private val retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService
    private val sleeper: Sleeper

    constructor(
        backoffSupplier: java.util.function.Supplier<Backoff?>,
        resultClassifier: ResultClassifier,
        retryScheduler: com.google.common.util.concurrent.ListeningScheduledExecutorService,
        circuitBreaker: CircuitBreaker
    ) : this(
        backoffSupplier,
        resultClassifier,
        retryScheduler,
        circuitBreaker,
        com.google.devtools.build.lib.remote.Retrier.Sleeper { timeout: Long -> TimeUnit.MILLISECONDS.sleep(timeout) })

    init {
        this.backoffSupplier = backoffSupplier
        this.resultClassifier = resultClassifier
        this.retryService = retryService
        this.circuitBreaker = circuitBreaker
        this.sleeper = sleeper
    }

    /** A [Callable] that can be retried in case of transient failure.  */
    @java.lang.FunctionalInterface
    interface RetryableCallable<T, E : java.lang.Exception?> : java.util.concurrent.Callable<T?> {
        @Throws(IOException::class, java.lang.InterruptedException::class, E::class)
        override fun call(): T?
    }

    /**
     * Execute a [RetryableCallable], retrying execution in case of transient failure and
     * returning the result in case of success.
     */
    @Throws(E::class, IOException::class, java.lang.InterruptedException::class)
    open fun <T, E : java.lang.Exception?> execute(call: RetryableCallable<T?, E?>): T? {
        return execute<T?, E?>(call, newBackoff())
    }

    /**
     * Execute a [RetryableCallable], retrying execution in case of transient failure and
     * returning the result in case of success with give [Backoff].
     * 
     * 
     * [InterruptedException] is not retried.
     * 
     * @param call the [Callable] to execute.
     * @throws E or [IOException] if the `call` didn't succeed within the framework
     * specified by `backoffSupplier` and `resultClassifier`.
     * @throws CircuitBreakerException in case a call was rejected because the circuit breaker
     * tripped.
     * @throws InterruptedException if the `call` throws an [InterruptedException] or the
     * current thread's interrupted flag is set.
     */
    @Throws(E::class, IOException::class, java.lang.InterruptedException::class)
    fun <T, E : java.lang.Exception?> execute(call: RetryableCallable<T?, E?>, backoff: Backoff): T? {
        while (true) {
            val circuitState = circuitBreaker.state()
            if (com.google.devtools.build.lib.remote.Retrier.CircuitBreaker.State.REJECT_CALLS == circuitState) {
                throw CircuitBreakerException()
            }
            try {
                if (java.lang.Thread.interrupted()) {
                    throw java.lang.InterruptedException()
                }
                val r = call.call()
                circuitBreaker.recordSuccess()
                return r
            } catch (e: java.lang.InterruptedException) {
                throw e
            } catch (e: java.lang.Exception) {
                val r = resultClassifier.test(e)
                if (r == com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.SUCCESS) {
                    circuitBreaker.recordSuccess()
                } else {
                    circuitBreaker.recordFailure()
                }
                if (r != com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.TRANSIENT_FAILURE || circuitState == com.google.devtools.build.lib.remote.Retrier.CircuitBreaker.State.TRIAL_CALL) {
                    throw e
                }
                val delayMillis = backoff.nextDelayMillis(e)
                if (delayMillis < 0) {
                    throw e
                }
                sleeper.sleep(delayMillis)
            }
        }
    }

    /** Executes an [AsyncCallable], retrying execution in case of transient failure.  */
    fun <T> executeAsync(call: com.google.common.util.concurrent.AsyncCallable<T?>): com.google.common.util.concurrent.ListenableFuture<T?>? {
        return executeAsync<T?>(call, newBackoff()!!)
    }

    /**
     * Executes an [AsyncCallable], retrying execution in case of transient failure with the
     * given backoff.
     */
    fun <T> executeAsync(
        call: com.google.common.util.concurrent.AsyncCallable<T?>,
        backoff: Backoff
    ): com.google.common.util.concurrent.ListenableFuture<T?>? {
        val circuitState = circuitBreaker.state()
        if (com.google.devtools.build.lib.remote.Retrier.CircuitBreaker.State.REJECT_CALLS == circuitState) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<T?>(CircuitBreakerException())
        }
        try {
            val future: com.google.common.util.concurrent.ListenableFuture<T?> =
                com.google.common.util.concurrent.Futures.transformAsync<T?, T?>(
                    call.call(),
                    com.google.common.util.concurrent.AsyncFunction { f: T? ->
                        circuitBreaker.recordSuccess()
                        com.google.common.util.concurrent.Futures.immediateFuture<T?>(f)
                    },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
            return com.google.common.util.concurrent.Futures.catchingAsync<T?, java.lang.Exception?>(
                future,
                java.lang.Exception::class.java,
                com.google.common.util.concurrent.AsyncFunction { t: java.lang.Exception? ->
                    onExecuteAsyncFailure<T?>(
                        t,
                        call,
                        backoff,
                        circuitState
                    )
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        } catch (e: java.lang.Exception) {
            return onExecuteAsyncFailure<T?>(e, call, backoff, circuitState)
        }
    }

    private fun <T> onExecuteAsyncFailure(
        t: java.lang.Exception,
        call: com.google.common.util.concurrent.AsyncCallable<T?>,
        backoff: Backoff,
        circuitState: CircuitBreaker.State
    ): com.google.common.util.concurrent.ListenableFuture<T?> {
        val r = resultClassifier.test(t)
        if (r == com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.TRANSIENT_FAILURE) {
            circuitBreaker.recordFailure()
            if (circuitState == com.google.devtools.build.lib.remote.Retrier.CircuitBreaker.State.TRIAL_CALL) {
                return com.google.common.util.concurrent.Futures.immediateFailedFuture<T?>(t)
            }
            val waitMillis = backoff.nextDelayMillis(t)
            if (waitMillis >= 0) {
                try {
                    return com.google.common.util.concurrent.Futures.scheduleAsync<T?>(
                        com.google.common.util.concurrent.AsyncCallable { executeAsync<T?>(call, backoff) },
                        waitMillis,
                        TimeUnit.MILLISECONDS,
                        retryService
                    )
                } catch (e: RejectedExecutionException) {
                    // May be thrown by .scheduleAsync(...) if i.e. the executor is shutdown.
                    return com.google.common.util.concurrent.Futures.immediateFailedFuture<T?>(IOException(e))
                }
            } else {
                return com.google.common.util.concurrent.Futures.immediateFailedFuture<T?>(t)
            }
        } else {
            if (r == com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.SUCCESS) {
                circuitBreaker.recordSuccess()
            } else {
                circuitBreaker.recordFailure()
            }
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<T?>(t)
        }
    }

    fun newBackoff(): Backoff? {
        return backoffSupplier.get()
    }

    fun isRetriable(e: java.lang.Exception?): Boolean {
        return resultClassifier.test(e) == com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.TRANSIENT_FAILURE
    }

    companion object {
        /** Disables circuit breaking.  */
        val ALLOW_ALL_CALLS: CircuitBreaker = object : CircuitBreaker {
            override fun state(): CircuitBreaker.State {
                return com.google.devtools.build.lib.remote.Retrier.CircuitBreaker.State.ACCEPT_CALLS
            }

            override fun recordFailure() {}

            override fun recordSuccess() {}
        }

        /** Disables retries.  */
        val RETRIES_DISABLED: Backoff = object : Backoff {
            override fun nextDelayMillis(e: java.lang.Exception?): Long {
                return -1
            }

            override fun getRetryAttempts(): Int {
                return 0
            }
        }
    }
}
