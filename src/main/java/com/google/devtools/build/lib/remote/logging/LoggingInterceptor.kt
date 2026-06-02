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
package com.google.devtools.build.lib.remote.logging

import build.bazel.remote.execution.v2.ActionCacheGrpc
import com.google.devtools.build.lib.clock.Clock
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status

/** Client interceptor for logging details of certain gRPC calls.  */
open class LoggingInterceptor(rpcLogFile: AsynchronousMessageOutputStream<LogEntry?>, clock: Clock) :
    ClientInterceptor {
    private val rpcLogFile: AsynchronousMessageOutputStream<LogEntry?>
    private val clock: Clock

    /** Constructs a LoggingInterceptor which logs RPC calls to the given file.  */
    init {
        this.rpcLogFile = rpcLogFile
        this.clock = clock
    }

    /**
     * Returns a [LoggingHandler] to handle logging details for the specified method. If there
     * is no handler for the given method, returns `null`.
     * 
     * @param method Method to return handler for.
     */
    protected open fun <ReqT, RespT> selectHandler(method: MethodDescriptor<ReqT?, RespT?>?): LoggingHandler<*, *>? {
        if (method == ExecutionGrpc.getExecuteMethod()) {
            return ExecuteHandler() // <ExecuteRequest, Operation>
        } else if (method == ExecutionGrpc.getWaitExecutionMethod()) {
            return WaitExecutionHandler() // <WaitExecutionRequest, Operation>
        } else if (method == ActionCacheGrpc.getGetActionResultMethod()) {
            return GetActionResultHandler() // <GetActionResultRequest, ActionResult>
        } else if (method == ActionCacheGrpc.getUpdateActionResultMethod()) {
            return UpdateActionResultHandler() // <UpdateActionResultRequest, ActionResult>
        } else if (method == ContentAddressableStorageGrpc.getFindMissingBlobsMethod()) {
            return FindMissingBlobsHandler() // <FindMissingBlobsRequest, FindMissingBlobsResponse>
        } else if (method == ContentAddressableStorageGrpc.getSplitBlobMethod()) {
            return SplitBlobHandler() // <SplitBlobRequest, SplitBlobResponse>
        } else if (method == ContentAddressableStorageGrpc.getSpliceBlobMethod()) {
            return SpliceBlobHandler() // <SpliceBlobRequest, SpliceBlobResponse>
        } else if (method == ByteStreamGrpc.getReadMethod()) {
            return ReadHandler() // <ReadRequest, ReadResponse>
        } else if (method == ByteStreamGrpc.getWriteMethod()) {
            return WriteHandler() // <WriteRequest, WriteResponse>
        } else if (method == ByteStreamGrpc.getQueryWriteStatusMethod()) {
            return QueryWriteStatusHandler() // <QueryWriteStatusRequest, QueryWriteStatusResponse>
        } else if (method == CapabilitiesGrpc.getGetCapabilitiesMethod()) {
            return GetCapabilitiesHandler() // <GetCapabilitiesRequest, ServerCapabilities>
        }
        return null
    }

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT?, RespT?>, callOptions: CallOptions?, next: Channel
    ): ClientCall<ReqT?, RespT?>? {
        val call: ClientCall<ReqT?, RespT?>? = next.newCall<ReqT?, RespT?>(method, callOptions)
        val handler:  // handler matches method, but that type is inexpressible
                LoggingHandler<ReqT?, RespT?>? = selectHandler<ReqT?, RespT?>(method)
        if (handler != null) {
            return LoggingForwardingCall<ReqT?, RespT?>(call, handler, method)
        } else {
            return call
        }
    }

    private val currentTimestamp: Timestamp
        /** Get current time as a Timestamp.  */
        get() {
            val time: Instant = Instant.ofEpochMilli(clock.currentTimeMillis())
            return Timestamp.newBuilder()
                .setSeconds(time.getEpochSecond())
                .setNanos(time.getNano())
                .build()
        }

    /**
     * Wraps client call to log call details by building a [LogEntry] and writing it to the RPC
     * log file.
     */
    private inner class LoggingForwardingCall<ReqT, RespT>
        (
        delegate: ClientCall<ReqT?, RespT?>?,
        private val handler: LoggingHandler<ReqT?, RespT?>,
        method: MethodDescriptor<ReqT?, RespT?>
    ) : SimpleForwardingClientCall<ReqT?, RespT?>(delegate) {
        private val entryBuilder: LogEntry.Builder

        init {
            this.entryBuilder = LogEntry.newBuilder().setMethodName(method.getFullMethodName())
        }

        override fun start(responseListener: ClientCall.Listener<RespT?>?, headers: Metadata) {
            entryBuilder.setStartTime(this.currentTimestamp)
            val metadata: RequestMetadata? = TracingMetadataUtils.requestMetadataFromHeaders(headers)
            if (metadata != null) {
                entryBuilder.setMetadata(metadata)
            }
            super.start(
                object : SimpleForwardingClientCallListener<RespT?>(
                    responseListener
                ) {
                    override fun onMessage(message: RespT?) {
                        handler.handleResp(message)
                        super.onMessage(message)
                    }

                    /**
                     * This method must not throw any exceptions! Doing so will cause the wrapped call to
                     * silently hang indefinitely: https://github.com/grpc/grpc-java/pull/6107
                     */
                    override fun onClose(status: Status, trailers: Metadata?) {
                        entryBuilder.setEndTime(this.currentTimestamp)
                        entryBuilder.setStatus(makeStatusProto(status))
                        entryBuilder.setDetails(handler.getDetails())
                        try {
                            rpcLogFile.write(entryBuilder.build())
                        } catch (e: RuntimeException) {
                            // e.g. the log file is already closed.
                            logger.atWarning().withCause(e).log(
                                "Unable to write RPC log entry for %s", entryBuilder.build()
                            )
                        }
                        super.onClose(status, trailers)
                    }
                },
                headers
            )
        }

        override fun sendMessage(message: ReqT?) {
            handler.handleReq(message)
            super.sendMessage(message)
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** Converts io.grpc.Status to com.google.rpc.Status proto for logging.  */
        private fun makeStatusProto(status: Status): com.google.rpc.Status {
            var message: String? = ""
            if (status.getCause() != null) {
                message = status.getCause().toString()
            } else if (status.getDescription() != null) {
                message = status.getDescription()
            }
            return com.google.rpc.Status.newBuilder()
                .setCode(status.getCode().value())
                .setMessage(message)
                .build()
        }
    }
}
