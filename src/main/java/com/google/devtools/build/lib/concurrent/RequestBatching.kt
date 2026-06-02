// Copyright 2026 The Bazel Authors. All rights reserved.
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

/** Shared API and internal components for request batching.  */
object RequestBatching {
    fun <RequestT, ResponseT>
            createBatchExecutionStrategy(
        multiplexer: Multiplexer<RequestT?, ResponseT?>, responseDistributionExecutor: java.util.concurrent.Executor
    ): BatchExecutionStrategy<RequestT?, ResponseT?> {
        return com.google.devtools.build.lib.concurrent.RequestBatching.MultiplexerAdapter<RequestT?, ResponseT?>(
            multiplexer,
            responseDistributionExecutor
        )
    }

    fun <RequestT, ResponseT>
            createCallbackBatchExecutionStrategy(
        multiplexer: CallbackMultiplexer<RequestT?, ResponseT?>
    ): BatchExecutionStrategy<RequestT?, ResponseT?> {
        return com.google.devtools.build.lib.concurrent.RequestBatching.CallbackMultiplexerAdapter<RequestT?, ResponseT?>(
            multiplexer
        )
    }

    fun <RequestT, ResponseT>
            createFutureBatchExecutionStrategy(
        multiplexer: FutureMultiplexer<RequestT?, ResponseT?>
    ): BatchExecutionStrategy<RequestT?, ResponseT?> {
        return com.google.devtools.build.lib.concurrent.RequestBatching.FutureMultiplexerAdapter<RequestT?, ResponseT?>(
            multiplexer
        )
    }

    /** Batching strategy where a single batch request returns a single batch future response.  */
    interface Multiplexer<RequestT, ResponseT> {
        /**
         * Evaluates `requests` as a batch.
         * 
         * @return a future containing a list of responses, positionally aligned with `requests`
         */
        fun execute(requests: MutableList<RequestT?>?): com.google.common.util.concurrent.ListenableFuture<MutableList<ResponseT?>?>
    }

    /**
     * A callback for a single request within a batch, which must be completed exactly once.
     * 
     * 
     * Used with [CallbackMultiplexer].
     */
    interface ResponseSink<RequestT, ResponseT> {
        /** Returns the original request associated with this sink.  */
        fun request(): RequestT?

        /** Returns true if the sink has been completed (success or failure).  */
        val isDone: Boolean

        /**
         * Fulfills the corresponding request with a successful response.
         * 
         * @param response the result of the operation. A `null` value is permitted and will be
         * forwarded to the original caller as a successful result.
         */
        fun acceptResponse(response: ResponseT?)

        /**
         * Fails the corresponding request with the given [Throwable].
         * 
         * 
         * A sink should only be completed once. Subsequent calls to this method after the sink has
         * already been completed will be ignored.
         */
        fun acceptFailure(t: Throwable?)
    }

    /**
     * A batching strategy where the implementation provides concrete response values asynchronously
     * via callbacks.
     */
    interface CallbackMultiplexer<RequestT, ResponseT> {
        /**
         * Executes the batch of `requests`, pushing results directly to the corresponding [ ] instances in the `sinks` list.
         * 
         * 
         * The supplied `sinks` list is co-indexed with the `requests` list. The
         * implementation of this method **must** ensure that for each request, the
         * corresponding sink is completed exactly once by calling either [ ][ResponseSink.acceptResponse] on success or [ResponseSink.acceptFailure] on failure.
         * 
         * 
         * The [RequestBatcher] internally monitors the completion of all sink operations for
         * the batch.
         * 
         * @return A non-null [Runnable] that the `RequestBatcher` will execute on behalf of
         * the client. The `RequestBatcher` guarantees it will run this callback after all
         * sinks for this specific batch have been completed, but **before** this
         * batch's concurrency slot is released. This provides a reliable mechanism for performing
         * batch-specific resource cleanup. For instance, if recycling identifiers used in the
         * requests, this guarantee ensures the identifiers are made available before a subsequent
         * batch could possibly use them. The callback should be lightweight.
         */
        fun execute(
            requests: MutableList<RequestT?>?,
            sinks: com.google.common.collect.ImmutableList<out ResponseSink<RequestT?, ResponseT?>>?
        ): java.lang.Runnable
    }

    /**
     * Accepts a future response value.
     * 
     * 
     * Used with [FutureMultiplexer].
     */
    interface FutureSink<ResponseT> {
        fun acceptFuture(future: com.google.common.util.concurrent.ListenableFuture<ResponseT?>?)
    }

    /** Batching strategy when a single batch request returns a response per future request.  */
    interface FutureMultiplexer<RequestT, ResponseT> {
        /** Executes `requests` in a batch and populates corresponding `responses`.  */
        fun execute(
            requests: MutableList<RequestT?>?,
            responses: com.google.common.collect.ImmutableList<out FutureSink<ResponseT?>>?
        )
    }

    internal interface BatchExecutionStrategy<RequestT, ResponseT> {
        fun executeBatch(
            requests: MutableList<RequestT?>?,
            operations: com.google.common.collect.ImmutableList<Operation<RequestT?, ResponseT?>?>?
        ): com.google.common.util.concurrent.ListenableFuture<*>?
    }

    internal class Operation<RequestT, ResponseT>(private val request: RequestT?) :
        com.google.common.util.concurrent.AbstractFuture<ResponseT?>(), ResponseSink<RequestT?, ResponseT?>,
        FutureSink<ResponseT?> {
        private var isFutureSet = false

        override fun request(): RequestT? {
            return request
        }

        private fun setResponse(response: ResponseT?) {
            // It's possible for the future to be cancelled by an external event (e.g., an interrupt).
            // `set` will return false if the future has already been completed or cancelled.
            // If `set` fails, we verify that the future was cancelled. This distinguishes
            // graceful cancellation from a bug where we try to set the response more than once.
            if (!set(response)) {
                com.google.common.base.Preconditions.checkState(
                    isCancelled(),
                    "response already set for request=%s, %s while trying to set future response %s",
                    request,
                    this,
                    response
                )
            }
        }

        override fun acceptResponse(response: ResponseT?) {
            setResponse(response)
        }

        override fun acceptFailure(t: Throwable) {
            setException(t)
        }

        override fun acceptFuture(future: com.google.common.util.concurrent.ListenableFuture<ResponseT?>) {
            setFuture(future)
            isFutureSet = true
        }

        fun errorIfFutureUnset() {
            if (!isFutureSet) {
                setException(
                    java.lang.IllegalStateException(
                        java.lang.String.format(
                            "Future for %s is unexpectedly not set. It should have been set by the"
                                    + " FutureMultiplexer.execute implementation",
                            request
                        )
                    )
                )
            }
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        public override fun setException(t: Throwable): Boolean {
            return super.setException(t)
        }
    }

    private class MultiplexerAdapter<RequestT, ResponseT>
        (
        private val multiplexer: Multiplexer<RequestT?, ResponseT?>,
        responseDistributionExecutor: java.util.concurrent.Executor
    ) : BatchExecutionStrategy<RequestT?, ResponseT?> {
        /**
         * Executor provided by the client to invoke callbacks for individual responses within a batched
         * response.
         * 
         * 
         * **Important:** For each batch, response callbacks are executed sequentially on a single
         * thread. If a callback involves significant processing, the client should offload the work to
         * separate threads to prevent delays in processing subsequent responses.
         */
        private val responseDistributionExecutor: java.util.concurrent.Executor

        init {
            this.responseDistributionExecutor = responseDistributionExecutor
        }

        override fun executeBatch(
            requests: MutableList<RequestT?>?,
            operations: com.google.common.collect.ImmutableList<Operation<RequestT?, ResponseT?>>
        ): com.google.common.util.concurrent.ListenableFuture<*> {
            val futureResponses: com.google.common.util.concurrent.ListenableFuture<MutableList<ResponseT?>?> =
                multiplexer.execute(
                    com.google.common.collect.Lists.transform<Operation<RequestT?, ResponseT?>?, RequestT?>(
                        operations,
                        com.google.common.base.Function { obj: Operation<RequestT?, ResponseT?>? -> obj!!.request() })
                )

            com.google.common.util.concurrent.Futures.addCallback<MutableList<ResponseT?>?>(
                futureResponses,
                object : com.google.common.util.concurrent.FutureCallback<MutableList<ResponseT?>?> {
                    override fun onFailure(t: Throwable) {
                        for (operation in operations) {
                            operation.setException(t)
                        }
                    }

                    override fun onSuccess(responses: MutableList<ResponseT?>) {
                        if (responses.size() != operations.size()) {
                            onFailure(
                                java.lang.AssertionError(
                                    ("RequestBatcher expected operations.size()="
                                            + operations.size()
                                            + " responses, but responses.size()="
                                            + responses.size())
                                )
                            )
                            return
                        }
                        for (i in responses.indices) {
                            operations.get(i).setResponse(responses.get(i))
                        }
                    }
                },
                responseDistributionExecutor
            )

            return futureResponses
        }
    }

    private class CallbackMultiplexerAdapter<RequestT, ResponseT>
        (private val multiplexer: CallbackMultiplexer<RequestT?, ResponseT?>) :
        BatchExecutionStrategy<RequestT?, ResponseT?> {
        override fun executeBatch(
            requests: MutableList<RequestT?>?,
            operations: com.google.common.collect.ImmutableList<Operation<RequestT?, ResponseT?>>
        ): com.google.common.util.concurrent.ListenableFuture<*> {
            val batchCompleteCallback: java.lang.Runnable =
                multiplexer.execute(
                    com.google.common.collect.Lists.transform<Operation<RequestT?, ResponseT?>?, RequestT?>(
                        operations,
                        com.google.common.base.Function { obj: Operation<RequestT?, ResponseT?>? -> obj!!.request() }),
                    operations
                )
            return com.google.common.util.concurrent.Futures.whenAllComplete<ResponseT?>(operations)
                .run(batchCompleteCallback, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        }
    }

    private class FutureMultiplexerAdapter<RequestT, ResponseT>
        (private val multiplexer: FutureMultiplexer<RequestT?, ResponseT?>) :
        BatchExecutionStrategy<RequestT?, ResponseT?> {
        override fun executeBatch(
            requests: MutableList<RequestT?>?,
            operations: com.google.common.collect.ImmutableList<Operation<RequestT?, ResponseT?>>
        ): com.google.common.util.concurrent.ListenableFuture<*> {
            multiplexer.execute(
                com.google.common.collect.Lists.transform<Operation<RequestT?, ResponseT?>?, RequestT?>(
                    operations,
                    com.google.common.base.Function { obj: Operation<RequestT?, ResponseT?>? -> obj!!.request() }),
                operations
            )
            for (operation in operations) {
                operation.errorIfFutureUnset()
            }
            return com.google.common.util.concurrent.Futures.whenAllComplete<ResponseT?>(operations)
                .run(java.lang.Runnable {}, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        }
    }
}
