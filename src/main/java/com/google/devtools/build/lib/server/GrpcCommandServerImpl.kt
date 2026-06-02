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
package com.google.devtools.build.lib.server

import com.google.devtools.build.lib.server.CommandProtos.CancelRequest

/**
 * The [GrpcCommandServer] implementation.
 * 
 * 
 * Only this class should depend on gRPC so that we only need to exclude this during
 * bootstrapping.
 * 
 * 
 * Every gRPC call is transferred to a separate thread in `commandExecutorPool` so that
 * long-lived calls don't block the event loop. We do this instead of setting an executor on the
 * server object because gRPC insists on serializing calls within a single RPC call, which means
 * that the Runnable passed to `setOnReadyHandler` doesn't get called while the main RPC
 * method is running, which means we can't use flow control, which we need so that gRPC doesn't
 * buffer an unbounded amount of outgoing data.
 */
open class GrpcCommandServerImpl : CommandServerGrpc.CommandServerImplBase(), GrpcCommandServer {
    /**
     * A wrapper for [StreamObserver] that blocks on [.onNext] calls if the underlying
     * observer is not ready.
     * 
     * 
     * It does not react to the interrupt flag in order to allow Bazel to complete the current
     * command while printing output as well as sending the final exit code to the client. However, it
     * maintains the interrupt flag if it is already set.
     */
    @com.google.common.annotations.VisibleForTesting
    internal class BlockingStreamObserver<T : Message?>(observer: ServerCallStreamObserver<T?>, responseType: T?) :
        Responder {
        private val observer: ServerCallStreamObserver<T?>
        private val parser: com.google.protobuf.Parser<T?>

        constructor(observer: StreamObserver<T?>?, responseType: T?) : this(
            observer as ServerCallStreamObserver<T?>?,
            responseType
        )

        init {
            this.observer = observer
            this.observer.setOnReadyHandler(java.lang.Runnable { this.notifyWaiters() })
            this.observer.setOnCancelHandler(java.lang.Runnable { this.notifyWaiters() })
            this.parser = responseType.getParserForType() as com.google.protobuf.Parser<T?>
        }

        @kotlin.jvm.Synchronized
        private fun notifyWaiters() {
            // This class does not restrict the number of concurrent calls to onNext, so we call notifyAll
            // here. In practice we'll usually only see one concurrent call; the ExperimentalEventHandler
            // uses synchronization to prevent multiple concurrent calls, but let's not rely on that here.
            (this as java.lang.Object).notifyAll()
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun onNext(response: ByteArray?) {
            var interrupted = false
            while (!observer.isReady() && !observer.isCancelled()) {
                try {
                    (this as java.lang.Object).wait()
                } catch (e: java.lang.InterruptedException) {
                    // We intentionally do not break or return here. The interrupt signal can be due the user
                    // pressing ctrl-c: it can take Bazel a while to shut down (e.g., it is not currently
                    // possible to interrupt persistent workers), and we must allow it to continue printing
                    // output until the current operation comes to a finish.
                    interrupted = true
                }
            }
            try {
                // According to the documentation, if onNext is called in a canceled stream, it will be
                // silently ignored.
                observer.onNext(parser.parseFrom(response, ExtensionRegistry.getEmptyRegistry()))
            } catch (e: InvalidProtocolBufferException) {
                // Programming error: the SC proto must remain backwards-compatible with the LC proto.
                throw java.lang.IllegalStateException(e)
            } catch (e: StatusRuntimeException) {
                throw IOException(e.message, e)
            } finally {
                // Restore the interrupt bit.
                if (interrupted || observer.isCancelled()) {
                    java.lang.Thread.currentThread().interrupt()
                }
            }
        }

        @Throws(IOException::class)
        override fun onCompleted() {
            try {
                observer.onCompleted()
            } catch (e: StatusRuntimeException) {
                throw IOException(e.message, e)
            }
        }
    }

    private val callbackExecutorPool: java.util.concurrent.Executor = io.grpc.Context.currentContextExecutor(
        Executors.newCachedThreadPool(
            com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("grpc-command-%d").setDaemon(true)
                .build()
        )
    )

    private var server: io.grpc.Server? = null
    private var callback: com.google.devtools.build.lib.server.GrpcCommandServer.Callback? = null

    @Throws(IOException::class)
    private fun bindWithRetries(address: InetSocketAddress?, maxRetries: Int): io.grpc.Server? {
        var server: io.grpc.Server? = null
        for (attempt in 1..maxRetries) {
            try {
                server =
                    NettyServerBuilder.forAddress(address)
                        .addService(this)
                        .directExecutor()
                        .build()
                        .start()
                break
            } catch (e: IOException) {
                // NettyServerBuilder.build() can throw a RuntimeException on epoll failures.
                if (attempt == maxRetries) {
                    throw e
                }
            } catch (e: java.lang.RuntimeException) {
                if (attempt == maxRetries) {
                    throw e
                }
            }
        }
        return server
    }

    @Throws(IOException::class)  // intentional use of [::1] and 127.0.0.1
    protected open fun bind(port: Int): io.grpc.Server? {
        // For reasons only Apple knows, you cannot bind to IPv4-localhost when you run in a sandbox
        // that only allows loopback traffic, but binding to IPv6-localhost works fine. This would
        // however break on systems that don't support IPv6. So what we'll do is to try to bind to IPv6
        // and if that fails, try again with IPv4.
        var address: InetSocketAddress = InetSocketAddress("[::1]", port)
        try {
            // TODO(bazel-team): Remove the following check after upgrading netty to a version with a fix
            //   for https://github.com/netty/netty/issues/10402
            if (Epoll.isAvailable() && !io.netty.channel.unix.Socket.isIPv6Preferred()) {
                throw IOException("ipv6 is not preferred on the system.")
            }
            // For some strange reasons, Bazel server sometimes fails to bind to IPv6 localhost when
            // running in macOS sandbox-exec with internet blocked. Retrying seems to help.
            // See https://github.com/bazelbuild/bazel/issues/20743
            return bindWithRetries(
                address,
                if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.DARWIN) 3 else 1
            )
        } catch (ipv6Exception: IOException) {
            address = InetSocketAddress("127.0.0.1", port)
            try {
                return NettyServerBuilder.forAddress(address)
                    .addService(this)
                    .directExecutor()
                    .build()
                    .start()
            } catch (ipv4Exception: IOException) {
                // NettyServerBuilder.build() can throw a RuntimeException on epoll failures.
                throw IOException(
                    "gRPC server failed to bind to localhost on port %d:\n[IPv4] %s\n[IPv6] %s"
                        .formatted(port, ipv4Exception.message, ipv6Exception.message)
                )
            } catch (ipv4Exception: java.lang.RuntimeException) {
                throw IOException(
                    "gRPC server failed to bind to localhost on port %d:\n[IPv4] %s\n[IPv6] %s"
                        .formatted(port, ipv4Exception.message, ipv6Exception.message)
                )
            }
        }
    }

    @Throws(IOException::class)
    override fun serve(
        port: Int,
        callback: com.google.devtools.build.lib.server.GrpcCommandServer.Callback?
    ): java.net.SocketAddress? {
        com.google.common.base.Preconditions.checkState(server == null, "serve() already called")
        this.callback = callback
        this.server = bind(port)
        return com.google.common.collect.Iterables.getOnlyElement(server.getListenSockets())
    }

    override fun shutdown() {
        com.google.common.base.Preconditions.checkNotNull<io.grpc.Server?>(server, "shutdown() called before serve()")
        if (server != null) {
            server.shutdown()
        }
    }

    override fun shutdownNow() {
        com.google.common.base.Preconditions.checkNotNull<io.grpc.Server?>(
            server,
            "shutdownNow() called before serve()"
        )
        if (server != null) {
            server.shutdownNow()
        }
    }

    @Throws(java.lang.InterruptedException::class)
    override fun awaitTermination() {
        com.google.common.base.Preconditions.checkNotNull<io.grpc.Server?>(
            server,
            "awaitTermination() called before serve()"
        )
        server.awaitTermination()
    }

    public override fun run(request: RunRequest, streamObserver: StreamObserver<RunResponse?>?) {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.server.GrpcCommandServer.Callback?>(
            callback,
            "run() called before serve()"
        )
        val blockingObserver: BlockingStreamObserver<RunResponse?> =
            BlockingStreamObserver<Any?>(streamObserver, RunResponse.getDefaultInstance())
        val serializedRequest: ByteArray? = request.toByteArray()
        callbackExecutorPool.execute(java.lang.Runnable { callback.run(serializedRequest, blockingObserver) })
    }

    public override fun ping(pingRequest: PingRequest, streamObserver: StreamObserver<PingResponse?>?) {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.server.GrpcCommandServer.Callback?>(
            callback,
            "ping() called before serve()"
        )
        val blockingObserver: BlockingStreamObserver<PingResponse?> =
            BlockingStreamObserver<Any?>(streamObserver, PingResponse.getDefaultInstance())
        val serializedRequest: ByteArray? = pingRequest.toByteArray()
        callbackExecutorPool.execute(java.lang.Runnable { callback.ping(serializedRequest, blockingObserver) })
    }

    public override fun cancel(cancelRequest: CancelRequest, streamObserver: StreamObserver<CancelResponse?>?) {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.server.GrpcCommandServer.Callback?>(
            callback,
            "cancel() called before serve()"
        )
        val blockingObserver: BlockingStreamObserver<CancelResponse?> =
            BlockingStreamObserver<Any?>(streamObserver, CancelResponse.getDefaultInstance())
        val serializedRequest: ByteArray? = cancelRequest.toByteArray()
        callbackExecutorPool.execute(java.lang.Runnable { callback.cancel(serializedRequest, blockingObserver) })
    }
}
