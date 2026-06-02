// Copyright 2016 The Bazel Authors. All rights reserved.
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

import build.bazel.remote.execution.v2.ExecuteRequest

/** A remote work executor that uses gRPC for communicating the work, inputs and outputs.  */
@ThreadSafe
internal class GrpcRemoteExecutor(
    channel: ReferenceCountedChannel,
    callCredentialsProvider: CallCredentialsProvider,
    retrier: RemoteRetrier
) : RemoteExecutionClient {
    private val channel: ReferenceCountedChannel
    private val callCredentialsProvider: CallCredentialsProvider
    private val retrier: RemoteRetrier

    private val closed: AtomicBoolean = AtomicBoolean()

    init {
        this.channel = channel
        this.callCredentialsProvider = callCredentialsProvider
        this.retrier = retrier
    }

    private fun execBlockingStub(metadata: RequestMetadata?, channel: io.grpc.Channel?): ExecutionBlockingStub {
        return ExecutionGrpc.newBlockingStub(channel)
            .withInterceptors(TracingMetadataUtils.attachMetadataInterceptor(metadata))
            .withCallCredentials(callCredentialsProvider.callCredentials)
    }

    private fun handleStatus(statusProto: Status, resp: ExecuteResponse?) {
        if (statusProto.getCode() === io.grpc.Status.Code.OK.value()) {
            return
        }
        throw ExecutionStatusException(statusProto, resp)
    }

    @Throws(IOException::class)
    private fun getOperationResponse(op: Operation): ExecuteResponse? {
        if (op.getResultCase() === Operation.ResultCase.ERROR) {
            handleStatus(op.getError(), null)
        }
        if (op.getDone()) {
            com.google.common.base.Preconditions.checkState(
                op.getResultCase() !== Operation.ResultCase.RESULT_NOT_SET,
                "Unexpected result of remote execution: result not set"
            )
            val resp: ExecuteResponse = op.getResponse().unpack(ExecuteResponse::class.java)
            if (resp.hasStatus()) {
                handleStatus(resp.getStatus(), resp)
            }
            com.google.common.base.Preconditions.checkState(
                resp.hasResult(), "Unexpected result of remote execution: no result"
            )
            return resp
        }
        return null
    }

    @get:Throws(IOException::class)
    val serverCapabilities: ServerCapabilities?
        get() = channel.getServerCapabilities()

    /* Execute has two components: the Execute call and (optionally) the WaitExecution call.
   * This is the simple flow without any errors:
   *
   * - A call to Execute returns streamed updates on an Operation object.
   * - We wait until the Operation is finished.
   *
   * Error possibilities:
   * - An Execute call may fail with a retriable error (raise a StatusRuntimeException).
   *   - If the failure occurred before the first Operation is returned, we retry the call.
   *   - Otherwise, we call WaitExecution on the Operation.
   * - A WaitExecution call may fail with a retriable error (raise a StatusRuntimeException).
   *   In that case, we retry the WaitExecution call on the same operation object.
   * - A WaitExecution call may fail with a NOT_FOUND error (raise a StatusRuntimeException).
   *   That means the Operation was lost on the server, and we will retry to Execute.
   * - Any call can return an Operation object with an error status in the result. Such Operations
   *   are completed and failed; however, some of these errors may be retriable. These errors should
   *   trigger a retry of the Execute call, resulting in a new Operation.
   * */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun executeRemotely(
        context: RemoteActionExecutionContext, request: ExecuteRequest?, observer: OperationObserver
    ): ExecuteResponse? {
        // Execute has two components: the Execute call and (optionally) the WaitExecution call.
        // This is the simple flow without any errors:
        //
        // - A call to Execute returns streamed updates on an Operation object.
        // - We wait until the Operation is finished.
        //
        // Error possibilities:
        // - An Execute call may fail with a retriable error (raise a StatusRuntimeException).
        //   - If the failure occurred before the first Operation is returned, we retry the call.
        //   - Otherwise, we call WaitExecution on the Operation.
        // - A WaitExecution call may fail with a retriable error (raise a StatusRuntimeException).
        //   In that case, we retry the WaitExecution call on the same operation object.
        // - A WaitExecution call may fail with a NOT_FOUND error (raise a StatusRuntimeException).
        //   That means the Operation was lost on the server, and we will retry to Execute.
        // - Any call can return an Operation object with an error status in the result. Such Operations
        //   are completed and failed; however, some of these errors may be retriable. These errors
        //   should trigger a retry of the Execute call, resulting in a new Operation.

        // Will be modified by the retried handler.

        val operation: AtomicReference<Operation?> =
            AtomicReference<V?>(Operation.getDefaultInstance())
        val waitExecution: AtomicBoolean =
            AtomicBoolean(false) // Whether we should call WaitExecution.
        try {
            return com.google.devtools.build.lib.remote.util.Utils.refreshIfUnauthenticated<ExecuteResponse?>(
                java.util.concurrent.Callable {
                    retrier.execute<ExecuteResponse?, java.lang.RuntimeException?>(
                        RetryableCallable {
                            // Retry calls to Execute()/WaitExecute() "infinitely" if the server terminates
                            // one of
                            // them status OK and an Operation that does not have done=True set. This is
                            // legal
                            // according to the remote execution protocol i.e. if the execution takes longer
                            // than a connection timeout. This is not an error condition and is thus handled
                            // outside of the retrier.
                            while (true) {
                                val replies: MutableIterator<Operation>
                                if (waitExecution.get()) {
                                    val wr: WaitExecutionRequest? =
                                        WaitExecutionRequest.newBuilder()
                                            .setName(operation.get().getName())
                                            .build()
                                    replies =
                                        channel.withChannelBlocking<MutableIterator<Operation>>(
                                            com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { channel: io.grpc.Channel? ->
                                                execBlockingStub(context.getRequestMetadata(), channel)
                                                    .waitExecution(wr)
                                            })
                                } else {
                                    Profiler.instance()
                                        .profile(ProfilerTask.REMOTE_EXECUTION, "send Execute request").use { c ->
                                            replies =
                                                channel.withChannelBlocking<MutableIterator<Operation>>(
                                                    com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { channel: io.grpc.Channel? ->
                                                        execBlockingStub(context.getRequestMetadata(), channel)
                                                            .execute(request)
                                                    })
                                        }
                                }
                                try {
                                    while (replies.hasNext()) {
                                        val o: Operation = replies.next()
                                        operation.set(o)
                                        waitExecution.set(!operation.get().getDone())

                                        // Update execution progress to the caller.
                                        //
                                        // After called `execute` above, the action is actually waiting for an
                                        // available
                                        // gRPC connection to be sent. Once we get a reply from server, we know
                                        // the
                                        // connection is up and indicate to the caller the fact by forwarding the
                                        // `operation`.
                                        //
                                        // The accurate execution status of the action relies on the server
                                        // implementation:
                                        //   1. Server can reply the accurate status in
                                        // `operation.metadata.stage`;
                                        //   2. Server may send a reply without metadata. In this case, we assume
                                        // the
                                        //      action is accepted by the server and will be executed ASAP;
                                        //   3. Server may execute the action silently and send a reply once it is
                                        // done.
                                        observer.onNext(o)

                                        val r: ExecuteResponse? = getOperationResponse(o)
                                        if (r != null) {
                                            return@execute r
                                        }
                                    }
                                    // The operation completed successfully but without a result.
                                    if (!waitExecution.get()) {
                                        throw IOException(
                                            java.lang.String.format(
                                                "Remote server error: execution request for %s terminated with no"
                                                        + " result.",
                                                operation.get().getName()
                                            )
                                        )
                                    }
                                } catch (e: StatusRuntimeException) {
                                    if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                                        // Operation was lost on the server. Retry Execute.
                                        waitExecution.set(false)
                                    }
                                    throw e
                                } finally {
                                    // The blocking streaming call closes correctly only when trailers and a
                                    // Status
                                    // are received from the server so that onClose() is called on this call's
                                    // CallListener. Under normal circumstances (no cancel/errors), these are
                                    // guaranteed to be sent by the server only if replies.hasNext() has been
                                    // called
                                    // after all replies from the stream have been consumed.
                                    try {
                                        while (replies.hasNext()) {
                                            replies.next()
                                        }
                                    } catch (e: StatusRuntimeException) {
                                        // Cleanup: ignore exceptions, because the meaningful errors have already
                                        // been
                                        // propagated.
                                    }
                                }
                            }
                        })
                },
                callCredentialsProvider
            )
        } catch (e: StatusRuntimeException) {
            throw IOException(e)
        }
    }

    override fun close() {
        if (closed.getAndSet(true)) {
            return
        }
        channel.release()
    }

    fun getRetrier(): RemoteRetrier {
        return this.retrier
    }
}
