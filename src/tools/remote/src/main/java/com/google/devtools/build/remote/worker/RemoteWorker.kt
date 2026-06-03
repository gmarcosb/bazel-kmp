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
package com.google.devtools.build.remote.worker

import com.google.devtools.build.lib.util.StringEncoding.internalToPlatform

/**
 * Implements a remote worker that accepts work items as protobufs. The server implementation is
 * based on gRPC.
 */
class RemoteWorker(
    fs: FileSystem,
    workerOptions: RemoteWorkerOptions,
    cache: OnDiskBlobStoreCache?,
    sandboxPath: Path?,
    digestUtil: DigestUtil?
) {
    private val workerOptions: RemoteWorkerOptions
    private val actionCacheServer: ActionCacheImplBase
    private val bsServer: ByteStreamImplBase
    private val casServer: ContentAddressableStorageImplBase
    private val execServer: ExecutionImplBase?
    private val capabilitiesServer: CapabilitiesImplBase
    private val fetchServer: FetchImplBase

    /** A [ServerInterceptor] that rejects requests unless an authorization token is present.  */
    private class AuthorizationTokenInterceptor(private val expectedToken: String) : ServerInterceptor {
        fun getTokenFromMetadata(headers: io.grpc.Metadata): java.util.Optional<String?> {
            val `val`: String? = headers.get<String?>(AUTHORIZATION_HEADER_KEY)
            if (`val` != null && `val`.startsWith(BEARER_PREFIX)) {
                return java.util.Optional.of<String?>(`val`.substring(BEARER_PREFIX.length))
            }
            return java.util.Optional.empty<String?>()
        }

        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT?, RespT?>, headers: io.grpc.Metadata, next: ServerCallHandler<ReqT?, RespT?>
        ): io.grpc.ServerCall.Listener<ReqT?> {
            val actualToken: java.util.Optional<String?> = getTokenFromMetadata(headers)
            if (expectedToken != actualToken.get()) {
                call.close(io.grpc.Status.PERMISSION_DENIED, io.grpc.Metadata())
                return object : io.grpc.ServerCall.Listener<ReqT?>() {}
            }
            return Contexts.interceptCall<ReqT?, RespT?>(io.grpc.Context.current(), call, headers, next)
        }

        companion object {
            private val AUTHORIZATION_HEADER_KEY: io.grpc.Metadata.Key<String?>? =
                io.grpc.Metadata.Key.of<String?>("Authorization", io.grpc.Metadata.ASCII_STRING_MARSHALLER)

            private const val BEARER_PREFIX = "Bearer "
        }
    }

    private class UnavailableInterceptor : ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT?, RespT?>, headers: io.grpc.Metadata?, next: ServerCallHandler<ReqT?, RespT?>
        ): io.grpc.ServerCall.Listener<ReqT?> {
            if (!call.getMethodDescriptor().getServiceName().contains("Capabilities")) {
                call.close(io.grpc.Status.UNAVAILABLE, io.grpc.Metadata())
                return object : io.grpc.ServerCall.Listener<ReqT?>() {}
            }
            return Contexts.interceptCall<ReqT?, RespT?>(io.grpc.Context.current(), call, headers, next)
        }
    }

    init {
        this.workerOptions = workerOptions
        this.actionCacheServer = ActionCacheServer(cache, digestUtil)
        val workPath: Path
        if (workerOptions.getWorkPath() != null) {
            workPath = fs.getPath(workerOptions.getWorkPath())
        } else {
            // TODO(ulfjack): The plan is to make the on-disk storage the default, so we always need to
            // provide a path to the remote worker, and we can then also use that as the work path. E.g.:
            // /given/path/cas/
            // /given/path/upload/
            // /given/path/work/
            // We could technically use a different path for temporary files and execution, but we want
            // the cas/ directory to be on the same file system as the upload/ and work/ directories so
            // that we can atomically move files between them, and / or use hard-links for the exec
            // directories.
            // For now, we use a temporary path if no work path was provided.
            workPath = fs.getPath("/tmp/remote-worker")
        }
        this.bsServer = ByteStreamServer(cache, workPath, digestUtil)
        this.casServer = CasServer(cache)

        if (workerOptions.getWorkPath() != null) {
            val operationsCache: ConcurrentHashMap<String?, com.google.common.util.concurrent.ListenableFuture<ActionResult?>?> =
                ConcurrentHashMap<String?, com.google.common.util.concurrent.ListenableFuture<ActionResult?>?>()
            workPath.createDirectoryAndParents()
            execServer =
                ExecutionServer(
                    workPath, sandboxPath, workerOptions, cache, operationsCache, digestUtil
                )
        } else {
            execServer = null
        }
        this.capabilitiesServer = CapabilitiesServer(digestUtil, execServer != null, workerOptions)
        this.fetchServer = FetchServer(cache, digestUtil, workPath.getRelative("fetch-temp"))
    }

    @Throws(IOException::class)
    fun startServer(): io.grpc.Server {
        val interceptors: MutableList<ServerInterceptor?> = java.util.ArrayList<ServerInterceptor?>()
        if (workerOptions.getUnavailable()) {
            interceptors.add(UnavailableInterceptor())
        }
        interceptors.add(ServerHeadersInterceptor())
        if (workerOptions.getExpectedAuthorizationToken() != null) {
            interceptors.add(
                AuthorizationTokenInterceptor(workerOptions.getExpectedAuthorizationToken())
            )
        }

        val b: NettyServerBuilder =
            NettyServerBuilder.forPort(workerOptions.getListenPort())
                .addService(ServerInterceptors.intercept(actionCacheServer, interceptors))
                .addService(ServerInterceptors.intercept(bsServer, interceptors))
                .addService(ServerInterceptors.intercept(casServer, interceptors))
                .addService(ServerInterceptors.intercept(capabilitiesServer, interceptors))
                .addService(ServerInterceptors.intercept(fetchServer, interceptors))

        if (workerOptions.getTlsCertificate() != null) {
            b.sslContext(getSslContextBuilder(workerOptions).build())
        }

        if (execServer != null) {
            b.addService(ServerInterceptors.intercept(execServer, interceptors))
        } else {
            logger.atInfo().log("Execution disabled, only serving cache requests")
        }

        val server: io.grpc.Server = b.build()
        logger.atInfo().log("Starting gRPC server on port %d", workerOptions.getListenPort())
        server.start()

        return server
    }

    private fun getSslContextBuilder(workerOptions: RemoteWorkerOptions): io.netty.handler.ssl.SslContextBuilder {
        val sslContextBuilder: io.netty.handler.ssl.SslContextBuilder =
            io.netty.handler.ssl.SslContextBuilder.forServer(
                java.io.File(internalToPlatform(workerOptions.getTlsCertificate().getPathString())),
                java.io.File(internalToPlatform(workerOptions.getTlsPrivateKey().getPathString()))
            )
        if (workerOptions.getTlsCaCertificate() != null) {
            sslContextBuilder.clientAuth(io.netty.handler.ssl.ClientAuth.REQUIRE)
            sslContextBuilder.trustManager(
                java.io.File(internalToPlatform(workerOptions.getTlsCaCertificate().getPathString()))
            )
        }
        return GrpcSslContexts.configure(sslContextBuilder, io.netty.handler.ssl.SslProvider.OPENSSL)
    }

    @Throws(IOException::class)
    private fun createPidFile() {
        if (workerOptions.getPidFile() == null) {
            return
        }

        val pidFile: Path = fileSystem.getPath(workerOptions.getPidFile())
        OutputStreamWriter(pidFile.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8).use { writer ->
            writer.write(java.lang.ProcessHandle.current().pid().toString())
            writer.write("\n")
        }
        java.lang.Runtime.getRuntime()
            .addShutdownHook(
                object : java.lang.Thread() {
                    override fun run() {
                        try {
                            pidFile.delete()
                        } catch (e: IOException) {
                            java.lang.System.err.println("Cannot remove pid file: " + pidFile)
                        }
                    }
                })
    }

    companion object {
        // We need to keep references to the root and netty loggers to prevent them from being garbage
        // collected, which would cause us to loose their configuration.
        private val rootLogger: java.util.logging.Logger = java.util.logging.Logger.getLogger("")
        private val nettyLogger: java.util.logging.Logger = java.util.logging.Logger.getLogger("io.grpc.netty")
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        val fileSystem: FileSystem
            get() {
                val hashFunction: DigestHashFunction?
                var value: String? = null
                try {
                    value = java.lang.System.getProperty("bazel.DigestFunction", "SHA256")
                    hashFunction = DigestFunctionConverter().convert(value)
                } catch (e: OptionsParsingException) {
                    throw java.lang.IllegalStateException(
                        "The specified hash function '" + value + "' is not supported.", e
                    )
                }
                return if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS)
                    WindowsFileSystem(hashFunction,  /* createSymbolicLinks= */true)
                else
                    UnixFileSystem(
                        hashFunction,  /* hashAttributeName= */"", NativePosixFilesServiceImpl()
                    )
            }

        @Throws(java.lang.Exception::class)
        @kotlin.jvm.JvmStatic
        fun main(args: Array<String>) {
            val parser: OptionsParser =
                OptionsParser.builder().optionsClasses(RemoteWorkerOptions::class.java).build()
            parser.parseAndExitUponError(args)
            val remoteWorkerOptions: RemoteWorkerOptions? =
                parser.getOptions<RemoteWorkerOptions?>(RemoteWorkerOptions::class.java)

            rootLogger.getHandlers()[0].setFormatter(SingleLineFormatter())
            if (remoteWorkerOptions.getDebug()) {
                rootLogger.getHandlers()[0].setLevel(java.util.logging.Level.FINE)
            }

            // Only log severe log messages from Netty. Otherwise it logs warnings that look like this:
            //
            // 170714 08:16:28.552:WT 18 [io.grpc.netty.NettyServerHandler.onStreamError] Stream Error
            // io.netty.handler.codec.http2.Http2Exception$StreamException: Received DATA frame for an
            // unknown stream 11369
            //
            // As far as we can tell, these do not indicate any problem with the connection. We believe they
            // happen when the local side closes a stream, but the remote side hasn't received that
            // notification yet, so there may still be packets for that stream en-route to the local
            // machine. The wording 'unknown stream' is misleading - the stream was previously known, but
            // was recently closed. I'm told upstream discussed this, but didn't want to keep information
            // about closed streams around.
            nettyLogger.setLevel(java.util.logging.Level.SEVERE)

            // Set the default subprocess factory to the Windows-specific implementation if the host OS is
            // Windows. See Bazel.java for more details.
            WindowsSubprocessFactory.maybeInstallWindowsSubprocessFactory()

            val fs: FileSystem = fileSystem
            var sandboxPath: Path? = null
            if (remoteWorkerOptions.getSandboxing()) {
                sandboxPath = prepareSandboxRunner(fs, remoteWorkerOptions)
            }

            if (remoteWorkerOptions.getCasPath() == null
                || !remoteWorkerOptions.getCasPath().isAbsolute()
            ) {
                logger.atSevere().log("--cas_path must be set to an absolute path")
                java.lang.System.exit(1)
                return
            }

            val casPath: Path = fs.getPath(remoteWorkerOptions.getCasPath())
            casPath.createDirectoryAndParents()

            val digestUtil: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, fs.getDigestFunction())
            val cache: OnDiskBlobStoreCache = OnDiskBlobStoreCache(casPath, digestUtil, remoteWorkerOptions)
            val retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService =
                com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))
            val worker = RemoteWorker(fs, remoteWorkerOptions, cache, sandboxPath, digestUtil)

            val server: io.grpc.Server = worker.startServer()

            var bossGroup: io.netty.channel.EventLoopGroup? = null
            var workerGroup: io.netty.channel.EventLoopGroup? = null
            var ch: io.netty.channel.Channel? = null
            if (remoteWorkerOptions.getHttpListenPort() != 0) {
                // Configure the server.
                bossGroup = io.netty.channel.nio.NioEventLoopGroup(1)
                workerGroup = io.netty.channel.nio.NioEventLoopGroup()
                val b: io.netty.bootstrap.ServerBootstrap = io.netty.bootstrap.ServerBootstrap()
                b.group(bossGroup, workerGroup)
                    .channel(io.netty.channel.socket.nio.NioServerSocketChannel::class.java)
                    .handler(io.netty.handler.logging.LoggingHandler(io.netty.handler.logging.LogLevel.INFO))
                    .childHandler(HttpCacheServerInitializer(OnDiskHttpCacheServerHandler(cache)))
                ch = b.bind(remoteWorkerOptions.getHttpListenPort()).sync().channel()
                logger.atInfo().log(
                    "Started HTTP cache server on port %d", remoteWorkerOptions.getHttpListenPort()
                )
            } else {
                logger.atInfo().log("Not starting HTTP cache server")
            }

            worker.createPidFile()

            server.awaitTermination()
            if (ch != null) {
                ch.closeFuture().sync().get()
            }

            retryService.shutdownNow()
            if (bossGroup != null) {
                bossGroup.shutdownGracefully()
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully()
            }
        }

        @Throws(java.lang.InterruptedException::class)
        private fun prepareSandboxRunner(fs: FileSystem, remoteWorkerOptions: RemoteWorkerOptions): Path {
            if (com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.LINUX) {
                logger.atSevere().log("Sandboxing requested, but it is currently only available on Linux")
                java.lang.System.exit(1)
            }

            if (remoteWorkerOptions.getWorkPath() == null
                || !remoteWorkerOptions.getWorkPath().isAbsolute()
            ) {
                logger.atSevere().log(
                    "Sandboxing requested, but --work_path was not set to an absolute path"
                )
                java.lang.System.exit(1)
            }

            val sandbox: java.io.InputStream? =
                RemoteWorker::class.java.getResourceAsStream("/main/tools/linux-sandbox")
            if (sandbox == null) {
                logger.atSevere().log(
                    "Sandboxing requested, but could not find bundled linux-sandbox binary. "
                            + "Please rebuild a worker_deploy.jar on Linux to make this work"
                )
                java.lang.System.exit(1)
            }

            var sandboxPath: Path? = null
            try {
                sandboxPath = fs.getPath(remoteWorkerOptions.getWorkPath()).getChild("linux-sandbox")
                FileOutputStream(sandboxPath.getPathString()).use { fos ->
                    com.google.common.io.ByteStreams.copy(sandbox, fos)
                }
                sandboxPath.setExecutable(true)
            } catch (e: IOException) {
                logger.atSevere().withCause(e).log(
                    "Could not extract the bundled linux-sandbox binary to %s", sandboxPath
                )
                java.lang.System.exit(1)
            }

            var cmdResult: CommandResult? = null
            val cmd: Command =
                Command(
                    LinuxSandboxCommandLineBuilder.commandLineBuilder(sandboxPath)
                        .buildForCommand(com.google.common.collect.ImmutableList.of<E?>("true")),
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    sandboxPath.getParentDirectory().getPathFile(),
                    java.lang.System.getenv()
                )
            try {
                cmdResult = cmd.execute()
            } catch (e: CommandException) {
                logger.atSevere().withCause(e).log(
                    "Sandboxing requested, but it failed to execute 'true' as a self-check: %s",
                    String(cmdResult.getStderr(), java.nio.charset.StandardCharsets.UTF_8)
                )
                java.lang.System.exit(1)
            }

            return sandboxPath
        }
    }
}
