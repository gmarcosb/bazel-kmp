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

import com.google.devtools.build.lib.remote.RemoteRetrierUtils
import com.google.devtools.build.lib.remote.Retrier
import com.google.devtools.build.lib.remote.Retrier.Backoff
import com.google.devtools.build.lib.remote.Retrier.ResultClassifier
import com.google.devtools.build.lib.remote.Retrier.RetryableCallable
import com.google.devtools.build.lib.remote.options.RemoteOptions
import io.grpc.StatusRuntimeException
import java.io.IOException

/** Specific retry logic for remote execution/caching.  */
open class RemoteRetrier : Retrier {
    constructor(
        options: RemoteOptions,
        resultClassifier: ResultClassifier?,
        retryScheduler: com.google.common.util.concurrent.ListeningScheduledExecutorService?,
        circuitBreaker: com.google.devtools.build.lib.remote.Retrier.CircuitBreaker?
    ) : this(
        if (options.getRemoteMaxRetryAttempts() > 0)
            java.util.function.Supplier { ExponentialBackoff(options) }
        else
            java.util.function.Supplier { Retrier.Companion.RETRIES_DISABLED },
        resultClassifier,
        retryScheduler,
        circuitBreaker
    )

    constructor(
        backoff: java.util.function.Supplier<Backoff?>?,
        resultClassifier: ResultClassifier?,
        retryScheduler: com.google.common.util.concurrent.ListeningScheduledExecutorService?,
        circuitBreaker: com.google.devtools.build.lib.remote.Retrier.CircuitBreaker?
    ) : super(backoff, resultClassifier, retryScheduler, circuitBreaker)

    @com.google.common.annotations.VisibleForTesting
    constructor(
        backoff: java.util.function.Supplier<Backoff?>?,
        resultClassifier: ResultClassifier?,
        retryScheduler: com.google.common.util.concurrent.ListeningScheduledExecutorService?,
        circuitBreaker: com.google.devtools.build.lib.remote.Retrier.CircuitBreaker?,
        sleeper: com.google.devtools.build.lib.remote.Retrier.Sleeper?
    ) : super(backoff, resultClassifier, retryScheduler, circuitBreaker, sleeper)

    /** Execute a callable with retries.  */
    @Throws(E::class, IOException::class, java.lang.InterruptedException::class)
    override fun <T, E : java.lang.Exception?> execute(call: RetryableCallable<T?, E?>?): T? {
        return execute<T?, E?>(call, newBackoff())
    }

    /** Backoff strategy that backs off exponentially.  */
    class ExponentialBackoff internal constructor(
        initial: java.time.Duration,
        max: java.time.Duration,
        multiplier: Double,
        jitter: Double,
        maxAttempts: Int
    ) : Backoff {
        private val maxMillis: Long
        private var nextDelayMillis: Long
        var retryAttempts: Int = 0
            private set
        private val multiplier: Double
        private val jitter: Double
        private val maxAttempts: Int

        /**
         * Creates a Backoff supplier for an optionally jittered exponential backoff. The supplier is
         * ThreadSafe (non-synchronized calls to get() are fine), but the returned Backoff is not.
         * 
         * @param initial The initial backoff duration.
         * @param max The maximum backoff duration.
         * @param multiplier The amount the backoff should increase in each iteration. Must be >1.
         * @param jitter The amount the backoff should be randomly varied (0-1), with 0 providing no
         * jitter, and 1 providing a duration that is 0-200% of the non-jittered duration.
         * @param maxAttempts Maximal times to attempt a retry 0 means no retries.
         */
        init {
            com.google.common.base.Preconditions.checkArgument(multiplier > 1, "multipler must be > 1")
            com.google.common.base.Preconditions.checkArgument(
                jitter >= 0 && jitter <= 1,
                "jitter must be in the range (0, 1)"
            )
            com.google.common.base.Preconditions.checkArgument(maxAttempts >= 0, "maxAttempts must be >= 0")
            nextDelayMillis = initial.toMillis()
            maxMillis = java.lang.Math.max(max.toMillis(), nextDelayMillis)
            this.multiplier = multiplier
            this.jitter = jitter
            this.maxAttempts = maxAttempts
        }

        constructor(options: RemoteOptions) : this( /* initial= */
            java.time.Duration.ofMillis(100),  /* max= */
            options.getRemoteRetryMaxDelay(),  /* multiplier= */
            2.0,  /* jitter= */
            0.1,
            options.getRemoteMaxRetryAttempts()
        )

        override fun nextDelayMillis(e: java.lang.Exception?): Long {
            if (this.retryAttempts == maxAttempts) {
                return -1
            }
            this.retryAttempts++
            val jitterRatio: Double = jitter * (java.util.concurrent.ThreadLocalRandom.current().nextDouble(2.0) - 1)
            val result = (nextDelayMillis * (1 + jitterRatio)).toLong()
            // Advance current by the non-jittered result.
            nextDelayMillis = (nextDelayMillis * multiplier).toLong()
            if (nextDelayMillis > maxMillis) {
                nextDelayMillis = maxMillis
            }
            return result
        }
    }

    internal class ProgressiveBackoff(backoffSupplier: java.util.function.Supplier<Backoff?>) : Backoff {
        private val backoffSupplier: java.util.function.Supplier<Backoff?>
        private var currentBackoff: Backoff? = null
        private var retries = 0

        /**
         * Creates a resettable Backoff for progressive reads. After a reset, the nextDelay returned
         * indicates an immediate retry. Initially and after indicating an immediate retry, a delegate
         * is generated to provide nextDelay until reset.
         * 
         * @param backoffSupplier Delegate Backoff generator
         */
        init {
            this.backoffSupplier = backoffSupplier
            currentBackoff = backoffSupplier.get()
        }

        fun reset() {
            if (currentBackoff != null) {
                retries += currentBackoff.getRetryAttempts()
            }
            currentBackoff = null
        }

        override fun nextDelayMillis(e: java.lang.Exception?): Long {
            if (currentBackoff == null) {
                currentBackoff = backoffSupplier.get()
                retries++
                return 0
            }
            return currentBackoff.nextDelayMillis(e)
        }

        val retryAttempts: Int
            get() {
                var retryAttempts = retries
                if (currentBackoff != null) {
                    retryAttempts += currentBackoff.getRetryAttempts()
                }
                return retryAttempts
            }
    }

    companion object {
        private fun fromException(e: java.lang.Exception?): io.grpc.Status? {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is StatusRuntimeException) {
                    return cause.getStatus()
                }
                cause = cause.getCause()
            }
            return null
        }

        /** A ResultClassifier suitable to be used by ExperimentalGrpcRemoteExecutor.  */
        val EXPERIMENTAL_GRPC_RESULT_CLASSIFIER: ResultClassifier = ResultClassifier { e: java.lang.Exception? ->
            val s: io.grpc.Status? = fromException(e)
            if (s == null) {
                // It's not a gRPC error.
                return@ResultClassifier com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.PERMANENT_FAILURE
            }
            when (s.getCode()) {
                io.grpc.Status.Code.CANCELLED -> if (!java.lang.Thread.currentThread().isInterrupted())
                    com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.TRANSIENT_FAILURE
                else
                    com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.PERMANENT_FAILURE

                io.grpc.Status.Code.NOT_FOUND, io.grpc.Status.Code.ALREADY_EXISTS -> com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.SUCCESS
                io.grpc.Status.Code.UNKNOWN, io.grpc.Status.Code.DEADLINE_EXCEEDED, io.grpc.Status.Code.ABORTED, io.grpc.Status.Code.INTERNAL, io.grpc.Status.Code.UNAVAILABLE, io.grpc.Status.Code.RESOURCE_EXHAUSTED -> com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.TRANSIENT_FAILURE
                else -> com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.PERMANENT_FAILURE
            }
        }

        /** A ResultClassifier suitable to be used by GrpcRemoteExecutor.  */
        val GRPC_RESULT_CLASSIFIER: ResultClassifier = ResultClassifier { e: java.lang.Exception? ->
            if (RemoteRetrierUtils.causedByStatus(e, io.grpc.Status.Code.NOT_FOUND))
                com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.TRANSIENT_FAILURE
            else
                EXPERIMENTAL_GRPC_RESULT_CLASSIFIER.test(e)
        }
    }
}
