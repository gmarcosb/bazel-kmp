// Copyright 2020 The Bazel Authors. All rights reserved.
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

import build.bazel.remote.execution.v2.ExecutionGrpc

/** The ClientInterceptor used to track network time.  */
class NetworkTimeInterceptor(networkTimeSupplier: java.util.function.Supplier<NetworkTime?>) : ClientInterceptor {
    private val networkTimeSupplier: java.util.function.Supplier<NetworkTime?>

    init {
        this.networkTimeSupplier = networkTimeSupplier
    }

    override fun <ReqT, RespT> interceptCall(
        method: io.grpc.MethodDescriptor<ReqT?, RespT?>?, callOptions: CallOptions?, next: io.grpc.Channel
    ): ClientCall<ReqT?, RespT?>? {
        var call: ClientCall<ReqT?, RespT?>? = next.newCall<ReqT?, RespT?>(method, callOptions)
        // prevent accounting for execution wait time
        if (method != ExecutionGrpc.getExecuteMethod()
            && method != ExecutionGrpc.getWaitExecutionMethod()
        ) {
            val networkTime: NetworkTime? = networkTimeSupplier.get()
            if (networkTime != null) {
                call = NetworkTimeCall<ReqT?, RespT?>(call, networkTime)
            }
        }
        return call
    }

    private class NetworkTimeCall<ReqT, RespT>
        (delegate: ClientCall<ReqT?, RespT?>?, networkTime: NetworkTime) :
        SimpleForwardingClientCall<ReqT?, RespT?>(delegate) {
        private val networkTime: NetworkTime
        private var firstMessage = true

        init {
            this.networkTime = networkTime
        }

        override fun start(responseListener: io.grpc.ClientCall.Listener<RespT?>?, headers: io.grpc.Metadata?) {
            super.start(
                object : SimpleForwardingClientCallListener<RespT?>(
                    responseListener
                ) {
                    override fun onClose(status: io.grpc.Status?, trailers: io.grpc.Metadata?) {
                        try {
                            networkTime.stop()
                        } catch (e: java.lang.RuntimeException) {
                            // An unchecked exception means we have bugs in the above try block, force crash
                            // Bazel so we can have a chance to look into.
                            throw java.lang.AssertionError(
                                "networkTime.stop() must not throw unchecked exception: " + networkTime, e
                            )
                        } finally {
                            // Make sure to call super.onClose, otherwise gRPC will silently hang indefinitely.
                            // See https://github.com/grpc/grpc-java/pull/6107.
                            super.onClose(status, trailers)
                        }
                    }
                },
                headers
            )
        }

        override fun sendMessage(message: ReqT?) {
            if (firstMessage) {
                networkTime.start()
                firstMessage = false
            }
            super.sendMessage(message)
        }
    }
}
