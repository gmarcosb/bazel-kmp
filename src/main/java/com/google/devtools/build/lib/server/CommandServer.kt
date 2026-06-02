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

import com.google.devtools.build.lib.runtime.BlazeCommandResult

/**
 * Manages the client request/response loop.
 * 
 * 
 * In addition to the request threads (managed by [GrpcCommandServer]), we maintain one
 * extra thread for handling the server timeout, and an interrupt watcher thread is started for each
 * interrupt request that logs if it takes too long to take effect.
 * 
 * 
 * Each running RPC has a UUID associated with it that is used to identify it when a client wants
 * to cancel it. Cancellation is done by the client sending the server a Cancel RPC, which results
 * in the main thread of the command being interrupted.
 */
class CommandServer @com.google.common.annotations.VisibleForTesting internal constructor(
    grpcCommandServer: GrpcCommandServer,
    dispatcher: CommandDispatcher,
    shutdownHooks: com.google.devtools.build.lib.server.ShutdownHooks,
    pidFileWatcher: PidFileWatcher,
    clock: com.google.devtools.build.lib.clock.Clock,
    port: Int,
    requestCookie: String,
    responseCookie: String,
    serverDirectory: com.google.devtools.build.lib.vfs.Path,
    serverPid: Int,
    maxIdleSeconds: Int,
    shutdownOnLowSysMem: Boolean,
    doIdleServerTasks: Boolean,
    slowInterruptMessageSuffix: String?
) : com.google.devtools.build.lib.server.GrpcCommandServer.Callback {
    @com.google.common.annotations.VisibleForTesting
    internal enum class StreamType {
        STDOUT,
        STDERR,
    }

    /** Command extension reporter that packs the protobuf into a RunResponse and sends it.  */
    private class RpcCommandExtensionReporter(commandId: String, responseCookie: String, responder: Responder) :
        CommandExtensionReporter {
        // Store commandId and responseCookie as ByteStrings to avoid String -> UTF8 bytes conversion
        // for each serialized chunk of output.
        private val commandIdBytes: ByteString
        private val responseCookieBytes: ByteString

        private val responder: Responder

        init {
            this.commandIdBytes = ByteString.copyFromUtf8(commandId)
            this.responseCookieBytes = ByteString.copyFromUtf8(responseCookie)
            this.responder = responder
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun report(commandExtension: Any?) {
            responder.onNext(
                RunResponse.newBuilder()
                    .setCookieBytes(responseCookieBytes)
                    .setCommandIdBytes(commandIdBytes)
                    .setStandardOutput(ByteString.EMPTY)
                    .addCommandExtensions(commandExtension)
                    .build()
                    .toByteArray()
            )
        }
    }

    /**
     * An output stream that forwards the data written to it over the gRPC command stream.
     * 
     * 
     * Note that wraping this class with a `Channel` can cause a deadlock if there is an
     * [OutputStream] in between that synchronizes both on `#close()` and `#write()`
     * because then if an interrupt happens in `FlowControl#onNext`, the thread on which `interrupt()` was called will wait until the `Channel` closes itself while holding a lock
     * for interrupting the thread on which `FlowControl#onNext` is being executed and that
     * thread will hold a lock that is needed for the `Channel` to be closed and call `interrupt()` in `FlowControl#onNext`, which will in turn try to acquire the interrupt
     * lock.
     */
    private class RpcOutputStream(
        commandId: String,
        responseCookie: String,
        private val type: StreamType,
        responder: Responder
    ) : java.io.OutputStream() {
        // Store commandId and responseCookie as ByteStrings to avoid String -> UTF8 bytes conversion
        // for each serialized chunk of output.
        private val commandIdBytes: ByteString
        private val responseCookieBytes: ByteString

        private val responder: Responder

        init {
            this.commandIdBytes = ByteString.copyFromUtf8(commandId)
            this.responseCookieBytes = ByteString.copyFromUtf8(responseCookie)
            this.responder = responder
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray, off: Int, inlen: Int) {
            var i = 0
            while (i < inlen) {
                val input: ByteString = ByteString.copyFrom(b, off + i, min(CHUNK_SIZE, inlen - i))
                val response: RunResponse.Builder =
                    RunResponse.newBuilder()
                        .setCookieBytes(responseCookieBytes)
                        .setCommandIdBytes(commandIdBytes)

                when (type) {
                    StreamType.STDOUT -> response.setStandardOutput(input)
                    StreamType.STDERR -> response.setStandardError(input)
                }

                try {
                    // This can block waiting for the client to read the available data.
                    responder.onNext(response.build().toByteArray())
                } catch (e: IOException) {
                    // I am not sure whether there are any circumstances under which this call could throw an
                    // exception, but I'd rather it be logged than that we crash silently. The documentation
                    // only says that onNext does not throw a CancelledException if the stream is canceled,
                    // but otherwise does not say anything about exceptions that can be thrown from onNext.
                    // Note that Blaze redirects System.{out,err} to this output stream, so attempting to call
                    // printStackTrace() from here could go into an infinite loop.
                    BugReport.sendBugReport(e)
                    java.lang.Thread.currentThread().interrupt()
                }
                i += CHUNK_SIZE
            }
        }

        @Throws(IOException::class)
        override fun write(byteAsInt: Int) {
            write(byteArrayOf(byteAsInt.toByte()), 0, 1)
        }

        companion object {
            private const val CHUNK_SIZE = 8192
        }
    }

    private val grpcCommandServer: GrpcCommandServer
    private val commandManager: CommandManager
    private val dispatcher: CommandDispatcher
    private val shutdownHooks: com.google.devtools.build.lib.server.ShutdownHooks
    private val clock: com.google.devtools.build.lib.clock.Clock
    private val serverDirectory: com.google.devtools.build.lib.vfs.Path
    private val requestCookie: String
    private val responseCookie: String
    private val maxIdleSeconds: Int
    private val shutdownOnLowSysMem: Boolean
    private val pidFileWatcher: PidFileWatcher
    private val serverPid: Int
    private val port: Int

    init {
        this.grpcCommandServer = grpcCommandServer
        this.dispatcher = dispatcher
        this.shutdownHooks = shutdownHooks
        this.pidFileWatcher = pidFileWatcher
        this.clock = clock
        this.port = port
        this.requestCookie = requestCookie
        this.responseCookie = responseCookie
        this.serverDirectory = serverDirectory
        this.serverPid = serverPid
        this.maxIdleSeconds = maxIdleSeconds
        this.shutdownOnLowSysMem = shutdownOnLowSysMem

        commandManager = CommandManager(doIdleServerTasks, slowInterruptMessageSuffix)
    }

    /**
     * This is called when the server is shut down as a result of a "clean --expunge".
     * 
     * 
     * In this case, no files should be deleted on shutdown hooks, since clean also deletes the
     * lock file, and there is a small possibility of the following sequence of events:
     * 
     * 
     *  1. Client 1 runs "blaze clean --expunge"
     *  1. Client 2 runs a command and waits for client 1 to finish
     *  1. The clean command deletes everything including the lock file
     *  1. Client 2 starts running and since the output base is empty, starts up a new server, which
     * creates its own socket and PID files
     *  1. The server used by client runs its shutdown hooks, deleting the PID files created by the
     * new server
     * 
     * 
     * It also disables the "die when the PID file changes" handler so that it doesn't kill the server
     * while the "clean --expunge" command is running.
     */
    fun prepareForAbruptShutdown() {
        shutdownHooks.disable()
        pidFileWatcher.signalShutdown()
    }

    /** Interrupts (cancels) in-flight commands.  */
    fun interrupt() {
        commandManager.interruptInflightCommands()
    }

    /**
     * Starts serving, writes the server status files, and blocks until the shutdown command is
     * received.
     */
    @Throws(AbruptExitException::class)
    fun serveAndAwaitTermination() {
        val address: java.net.SocketAddress
        try {
            address = serve()
        } catch (e: IOException) {
            throw AbruptExitException(
                DetailedExitCode.of(
                    createFailureDetail(e.message, GrpcServer.Code.SERVER_BIND_FAILURE)
                ),
                e
            )
        }

        if (maxIdleSeconds > 0) {
            val timeoutAndMemoryCheckingThread: java.lang.Thread =
                java.lang.Thread(
                    ServerWatcherRunnable(
                        grpcCommandServer, maxIdleSeconds.toLong(), shutdownOnLowSysMem, commandManager
                    )
                )
            timeoutAndMemoryCheckingThread.setName("grpc-timeout-and-memory")
            timeoutAndMemoryCheckingThread.setDaemon(true)
            timeoutAndMemoryCheckingThread.start()
        }

        writeServerStatusFiles(address)

        try {
            awaitTermination()
        } catch (e: java.lang.InterruptedException) {
            // TODO(lberki): Handle SIGINT in a reasonable way
            throw java.lang.IllegalStateException(e)
        }
    }

    @com.google.common.annotations.VisibleForTesting
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    fun serve(): java.net.SocketAddress {
        return grpcCommandServer.serve(port, this)
    }

    @com.google.common.annotations.VisibleForTesting
    fun shutdown() {
        grpcCommandServer.shutdown()
    }

    @com.google.common.annotations.VisibleForTesting
    fun shutdownNow() {
        grpcCommandServer.shutdownNow()
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(java.lang.InterruptedException::class)
    fun awaitTermination() {
        grpcCommandServer.awaitTermination()
    }

    @Throws(AbruptExitException::class)
    private fun writeServerStatusFiles(address: java.net.SocketAddress) {
        val addressString = formatAddress(address)

        writeServerFile(PORT_FILE, addressString)
        writeServerFile(REQUEST_COOKIE_FILE, requestCookie)
        writeServerFile(RESPONSE_COOKIE_FILE, responseCookie)

        val info: ServerInfo =
            ServerInfo.newBuilder()
                .setPid(serverPid)
                .setAddress(addressString)
                .setRequestCookie(requestCookie)
                .setResponseCookie(responseCookie)
                .build()

        // Write then mv so the user never sees incomplete contents.
        val serverInfoTmpFile: com.google.devtools.build.lib.vfs.Path =
            serverDirectory.getChild(SERVER_INFO_FILE + ".tmp")
        try {
            serverInfoTmpFile.getOutputStream().use { out ->
                info.writeTo(out)
            }
            val serverInfoFile: com.google.devtools.build.lib.vfs.Path = serverDirectory.getChild(SERVER_INFO_FILE)
            serverInfoTmpFile.renameTo(serverInfoFile)
            shutdownHooks.deleteAtExit(serverInfoFile)
        } catch (e: IOException) {
            throw createFilesystemFailureException("Failed to write server info file", e)
        }
    }

    private fun formatAddress(address: java.net.SocketAddress): String? {
        if (address is InetSocketAddress) {
            if (address.getAddress() is Inet4Address) {
                return inet4Addr.getHostAddress() + ":" + address.getPort()
            } else if (address.getAddress() is Inet6Address) {
                return "[" + inet6Addr.getHostAddress() + "]:" + address.getPort()
            }
        }
        // Can only happen in tests using an in-memory implementation; representation doesn't matter.
        return address.toString()
    }

    @Throws(AbruptExitException::class)
    private fun writeServerFile(name: String?, contents: String?) {
        val file: com.google.devtools.build.lib.vfs.Path = serverDirectory.getChild(name)
        try {
            com.google.devtools.build.lib.vfs.FileSystemUtils.writeContentAsLatin1(file, contents)
        } catch (e: IOException) {
            throw createFilesystemFailureException("Server file (" + file + ") write failed", e)
        }
        shutdownHooks.deleteAtExit(file)
    }

    override fun run(serializedRequest: ByteArray?, responder: Responder) {
        val request: RunRequest
        try {
            request = RunRequest.parseFrom(serializedRequest, ExtensionRegistry.getEmptyRegistry())
        } catch (e: InvalidProtocolBufferException) {
            // Programming error: the SC proto must remain backwards-compatible with the LC proto.
            throw java.lang.IllegalStateException(e)
        }
        val badCookie = !isValidRequestCookie(request.getCookie())
        if (badCookie || request.getClientDescription().isEmpty()) {
            try {
                val failureDetail: FailureDetail =
                    if (badCookie)
                        createFailureDetail("Invalid RunRequest: bad cookie", GrpcServer.Code.BAD_COOKIE)
                    else
                        createFailureDetail(
                            "Invalid RunRequest: no client description",
                            GrpcServer.Code.NO_CLIENT_DESCRIPTION
                        )
                responder.onNext(
                    RunResponse.newBuilder()
                        .setFinished(true)
                        .setExitCode(ExitCode.LOCAL_ENVIRONMENTAL_ERROR.getNumericExitCode())
                        .setFailureDetail(failureDetail)
                        .build()
                        .toByteArray()
                )
                responder.onCompleted()
            } catch (e: IOException) {
                logger.atInfo().withCause(e).log("Error while sending RunResponse")
            }
            return
        }

        var commandId: String?
        var result: BlazeCommandResult

        // TODO(b/63925394): This information needs to be passed to the GotOptionsEvent, which does not
        // currently have the explicit startup options. See Improved Command Line Reporting design doc
        // for details.
        // Convert the startup options record to Java strings, source first.
        val startupOptions: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.util.Pair<String?, String?>?> =
            com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.util.Pair<String?, String?>?>()
        for (option in request.getStartupOptionsList()) {
            // UTF-8 won't do because we want to be able to pass arbitrary binary strings.
            startupOptions.add(
                com.google.devtools.build.lib.util.Pair<String?, String?>(
                    platformBytesToInternalString(option.getSource()),
                    platformBytesToInternalString(option.getOption())
                )
            )
        }

        commandManager.preemptEligibleCommands()

        try {
            if (request.getPreemptible())
                commandManager.createPreemptibleCommand()
            else
                commandManager.createCommand().use { command ->
                    commandId = command.getId()
                    try {
                        // Send the client the command id as soon as we know it.
                        responder.onNext(
                            RunResponse.newBuilder()
                                .setCookie(responseCookie)
                                .setCommandId(commandId)
                                .build()
                                .toByteArray()
                        )
                    } catch (e: IOException) {
                        logger.atInfo().withCause(e).log("Error while sending initial RunResponse")
                    }

                    val rpcOutErr: OutErr =
                        OutErr.create(
                            RpcOutputStream(command.getId(), responseCookie, StreamType.STDOUT, responder),
                            RpcOutputStream(command.getId(), responseCookie, StreamType.STDERR, responder)
                        )

                    try {
                        // Transform args into Bazel's internal string representation.
                        val args: com.google.common.collect.ImmutableList<String?>? =
                            request.getArgList().stream()
                                .map({ bytes: ByteString -> platformBytesToInternalString(bytes) })
                                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())

                        val policy: InvocationPolicy? =
                            InvocationPolicyParser.parsePolicy(request.getInvocationPolicy())
                        logger.atInfo().log("Executing command %s", SafeRequestLogging.getRequestLogString(args))
                        result =
                            dispatcher.exec(
                                policy,
                                args,
                                rpcOutErr,
                                if (request.getBlockForLock()) LockingMode.WAIT else LockingMode.ERROR_OUT,
                                if (request.getQuiet()) UiVerbosity.QUIET else UiVerbosity.NORMAL,
                                request.getClientDescription(),
                                clock.currentTimeMillis(),
                                java.util.Optional.of<T?>(startupOptions.build()),
                                { commandManager.getIdleTaskResults() },
                                request.getCommandExtensionsList(),
                                RpcCommandExtensionReporter(command.getId(), responseCookie, responder)
                            )
                    } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                        rpcOutErr.printErrLn(e.message)
                        result =
                            BlazeCommandResult.detailedExitCode(
                                DetailedExitCode.of(
                                    FailureDetail.newBuilder()
                                        .setMessage("Invocation policy parsing failed: " + e.message)
                                        .setCommand(
                                            Command.newBuilder()
                                                .setCode(Command.Code.INVOCATION_POLICY_PARSE_FAILURE)
                                        )
                                        .build()
                                )
                            )
                    }

                    // Record tasks to be run by IdleTaskManager. This is triggered in RunningCommand#close()
                    // (as a Closeable), as we go out of scope immediately after this.
                    command.setIdleTasks(result.getIdleTasks())
                }
        } catch (e: java.lang.InterruptedException) {
            result =
                BlazeCommandResult.detailedExitCode(
                    InterruptedFailureDetails.detailedExitCode("Command dispatch interrupted")
                )
            commandId = "" // The default value, the client will ignore it
        }
        val response: RunResponse.Builder =
            RunResponse.newBuilder()
                .setCookie(responseCookie)
                .setCommandId(commandId)
                .setFinished(true)
                .setTerminationExpected(result.shutdown())

        if (result.getExecRequest() != null) {
            response.setExitCode(result.getExitCode().getNumericExitCode())
            response.setExecRequest(result.getExecRequest())
            if (result.getFailureDetail() != null) {
                response.setFailureDetail(result.getFailureDetail())
            }
        } else {
            response.setExitCode(result.getExitCode().getNumericExitCode())
            if (result.getFailureDetail() != null) {
                response.setFailureDetail(result.getFailureDetail())
            }
        }

        try {
            responder.onNext(
                response.addAllCommandExtensions(result.getResponseExtensions()).build().toByteArray()
            )
            responder.onCompleted()
        } catch (e: IOException) {
            logger.atInfo().withCause(e).log("Error while sending RunResponse")
        }
        if (result.shutdown()) {
            grpcCommandServer.shutdown()
        }
    }

    override fun ping(serializedRequest: ByteArray?, responder: Responder) {
        logger.atInfo().log("Got PingRequest")
        val request: PingRequest
        try {
            request = PingRequest.parseFrom(serializedRequest, ExtensionRegistry.getEmptyRegistry())
        } catch (e: InvalidProtocolBufferException) {
            // Programming error: the SC proto must remain backwards-compatible with the LC proto.
            throw java.lang.IllegalStateException(e)
        }
        try {
            commandManager.createCommand().use { command ->
                val response: PingResponse.Builder = PingResponse.newBuilder()
                if (isValidRequestCookie(request.getCookie())) {
                    response.setCookie(responseCookie)
                }
                responder.onNext(response.build().toByteArray())
                responder.onCompleted()
            }
        } catch (e: IOException) {
            // There is no one to report the failure to.
            logger.atInfo().withCause(e).log("Error while sending PingResponse")
        }
    }

    override fun cancel(serializedRequest: ByteArray?, responder: Responder) {
        val request: CancelRequest
        try {
            request = CancelRequest.parseFrom(serializedRequest, ExtensionRegistry.getEmptyRegistry())
        } catch (e: InvalidProtocolBufferException) {
            // Programming error: the SC proto must remain backwards-compatible with the LC proto.
            throw java.lang.IllegalStateException(e)
        }
        logger.atInfo().log("Got CancelRequest for command id %s", request.getCommandId())
        try {
            if (isValidRequestCookie(request.getCookie())) {
                commandManager.doCancel(request)
                responder.onNext(
                    CancelResponse.newBuilder().setCookie(responseCookie).build().toByteArray()
                )
            }
            responder.onCompleted()
        } catch (e: IOException) {
            // There is no one to report the failure to.
            logger.atInfo().withCause(e).log("Error while sending CancelResponse")
        }
    }

    /**
     * Returns whether or not the provided cookie is valid for this server using a constant-time
     * comparison in order to guard against timing attacks.
     */
    private fun isValidRequestCookie(incomingRequestCookie: String): Boolean {
        // Note that cookie file was written as latin-1, so use that here.
        return MessageDigest.isEqual(
            incomingRequestCookie.toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1),
            requestCookie.toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1)
        )
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        fun create(
            grpcCommandServer: GrpcCommandServer,
            dispatcher: CommandDispatcher,
            shutdownHooks: com.google.devtools.build.lib.server.ShutdownHooks,
            pidFileWatcher: PidFileWatcher,
            clock: com.google.devtools.build.lib.clock.Clock,
            port: Int,
            serverDirectory: com.google.devtools.build.lib.vfs.Path,
            serverPid: Int,
            maxIdleSeconds: Int,
            shutdownOnLowSysMem: Boolean,
            idleServerTasks: Boolean,
            slowInterruptMessageSuffix: String?
        ): CommandServer {
            val random: java.security.SecureRandom = java.security.SecureRandom()
            return CommandServer(
                grpcCommandServer,
                dispatcher,
                shutdownHooks,
                pidFileWatcher,
                clock,
                port,
                generateCookie(random, 16),
                generateCookie(random, 16),
                serverDirectory,
                serverPid,
                maxIdleSeconds,
                shutdownOnLowSysMem,
                idleServerTasks,
                slowInterruptMessageSuffix
            )
        }

        // These paths are all relative to the server directory
        private const val PORT_FILE = "command_port"
        private const val REQUEST_COOKIE_FILE = "request_cookie"
        private const val RESPONSE_COOKIE_FILE = "response_cookie"
        private const val SERVER_INFO_FILE = "server_info.rawproto"

        private fun generateCookie(random: java.security.SecureRandom, byteCount: Int): String {
            val bytes = ByteArray(byteCount)
            random.nextBytes(bytes)
            val result: java.lang.StringBuilder = java.lang.StringBuilder()
            for (b in bytes) {
                result.append(java.lang.Integer.toHexString(b + 128))
            }

            return result.toString()
        }

        private fun createFilesystemFailureException(
            message: String?, e: IOException
        ): AbruptExitException {
            return AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(
                            message + (if (com.google.common.base.Strings.isNullOrEmpty(e.message)) "" else ": " + e.message)
                        )
                        .setFilesystem(Filesystem.newBuilder().setCode(Code.SERVER_FILE_WRITE_FAILURE))
                        .build()
                ),
                e
            )
        }

        private fun createFailureDetail(message: String?, detailedCode: GrpcServer.Code?): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage(message)
                .setGrpcServer(GrpcServer.newBuilder().setCode(detailedCode))
                .build()
        }

        private fun platformBytesToInternalString(bytes: ByteString): String? {
            return bytes.toString(java.nio.charset.StandardCharsets.ISO_8859_1)
        }
    }
}
