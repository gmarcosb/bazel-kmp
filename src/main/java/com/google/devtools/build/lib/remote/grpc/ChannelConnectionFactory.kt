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
package com.google.devtools.build.lib.remote.grpc

import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.reactivex.rxjava3.core.Single
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A [ConnectionFactory] which creates [ChannelConnection].  */
interface ChannelConnectionFactory : ConnectionFactory {
    override fun create(): Single<out ChannelConnection?>?

    /** Returns the max concurrency supported by the underlying [ManagedChannel].  */
    fun maxConcurrency(): Int

    /** A [Connection] which wraps around [ManagedChannel].  */
    class ChannelConnection(val channel: ManagedChannel) : Connection {
        override fun <ReqT, RespT> call(
            method: MethodDescriptor<ReqT?, RespT?>?, options: CallOptions?
        ): ClientCall<ReqT?, RespT?>? {
            return channel.newCall<ReqT?, RespT?>(method, options)
        }

        @Throws(IOException::class)
        override fun close() {
            // Clear interrupted status to prevent failure to await, indicated with #13512
            val wasInterrupted = Thread.interrupted()
            // There is a bug (b/183340374) in gRPC that client doesn't try to close connections with
            // shutdown() if the channel received GO_AWAY frames. Using shutdownNow() here as a
            // workaround.
            try {
                channel.shutdownNow()
                channel.awaitTermination(Integer.MAX_VALUE.toLong(), TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                throw IOException(e.getMessage(), e)
            } finally {
                if (wasInterrupted) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }
}
