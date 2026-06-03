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
package com.google.devtools.build.remote.worker

import com.google.devtools.build.lib.remote.util.Utils.getFromFuture

/** A basic implementation of an [ExecutionImplBase] service.  */
internal class ExecutionServer(
    workPath: Path,
    sandboxPath: Path?,
    workerOptions: RemoteWorkerOptions,
    cache: OnDiskBlobStoreCache,
    operationsCache: ConcurrentHashMap<String?, com.google.common.util.concurrent.ListenableFuture<ActionResult?>?>,
    digestUtil: DigestUtil
) : ExecutionImplBase() {
    private val workPath: Path
    private val sandboxPath: Path?
    private val workerOptions: RemoteWorkerOptions
    private val cache: OnDiskBlobStoreCache
    private val operationsCache: ConcurrentHashMap<String?, com.google.common.util.concurrent.ListenableFuture<ActionResult?>?>
    private val executorService: com.google.common.util.concurrent.ListeningExecutorService
    private val digestUtil: DigestUtil
    private val localEnvProvider: LocalEnvProvider
    private val binTools: BinTools?

    init {
        this.workPath = workPath
        this.sandboxPath = sandboxPath
        this.workerOptions = workerOptions
        this.cache = cache
        this.operationsCache = operationsCache
        this.digestUtil = digestUtil
        val realExecutor: ThreadPoolExecutor =
            ThreadPoolExecutor( // This is actually the max number of concurrent jobs.
                workerOptions.getJobs(),  // Since we use an unbounded queue, the executor ignores this value, but it still checks
                // that it is greater or equal to the value above.
                workerOptions.getJobs(),  // Shut down idle threads after one minute. Threads aren't all that expensive, but we
                // also
                // don't need to keep them around if we don't need them.
                1,
                TimeUnit.MINUTES,  // We use an unbounded queue for now.
                // TODO(ulfjack): We need to reject work eventually.
                LinkedBlockingQueue<java.lang.Runnable?>(),
                com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("subprocess-handler-%d").build()
            )
        // Allow the core threads to die.
        realExecutor.allowCoreThreadTimeOut(true)
        this.executorService = com.google.common.util.concurrent.MoreExecutors.listeningDecorator(realExecutor)
        this.localEnvProvider = LocalEnvProvider.forCurrentOs(java.lang.System.getenv())
        val xcodeLocator: String?
        try {
            xcodeLocator =
                Runfiles.preload()
                    .withSourceRepository("")
                    .rlocation(
                        "io_bazel/src/tools/remote/src/main/java/com/google/devtools/build/remote/worker/xcode-locator"
                    )
        } catch (e: IOException) {
            throw java.lang.IllegalStateException(e)
        }
        this.binTools =
            BinTools.forEmbeddedBin(
                workPath.getFileSystem().getPath(xcodeLocator).getParentDirectory(),
                com.google.common.collect.ImmutableList.of<E?>("xcode-locator")
            )
    }

    public override fun waitExecution(wr: WaitExecutionRequest, responseObserver: StreamObserver<Operation?>) {
        val opName: String? = wr.getName()
        val future: com.google.common.util.concurrent.ListenableFuture<ActionResult?>? = operationsCache.get(opName)
        if (future == null) {
            responseObserver.onError(
                StatusProto.toStatusRuntimeException(
                    Status.newBuilder()
                        .setCode(Code.NOT_FOUND.getNumber())
                        .setMessage("Operation not found: " + opName)
                        .build()
                )
            )
            return
        }
        (responseObserver as ServerCallStreamObserver<Operation?>)
            .setOnCancelHandler(java.lang.Runnable { operationsCache.remove(opName) })
        waitExecution(opName, future, responseObserver)
    }

    private fun waitExecution(
        opName: String?,
        future: com.google.common.util.concurrent.ListenableFuture<ActionResult?>,
        responseObserver: StreamObserver<Operation?>
    ) {
        future.addListener(
            java.lang.Runnable {
                try {
                    try {
                        val result: ActionResult? = future.get()
                        responseObserver.onNext(
                            Operation.newBuilder()
                                .setName(opName)
                                .setDone(true)
                                .setResponse(Any.pack(ExecuteResponse.newBuilder().setResult(result).build()))
                                .build()
                        )
                        responseObserver.onCompleted()
                    } catch (e: ExecutionException) {
                        com.google.common.base.Throwables.throwIfUnchecked(e.cause)
                        throw e.cause as java.lang.Exception?
                    }
                } catch (e: java.lang.Exception) {
                    val resp: ExecuteResponse?
                    if (e is ExecutionStatusException) {
                        resp = e.getResponse()
                    } else {
                        logger.atSevere().withCause(e).log("Work failed: %s", opName)
                        resp =
                            ExecuteResponse.newBuilder()
                                .setStatus(StatusUtils.internalErrorStatus(e))
                                .build()
                    }
                    responseObserver.onNext(
                        Operation.newBuilder()
                            .setName(opName)
                            .setDone(true)
                            .setResponse(Any.pack(resp))
                            .build()
                    )
                    responseObserver.onCompleted()
                    if (e is java.lang.InterruptedException) {
                        java.lang.Thread.currentThread().interrupt()
                    }
                } finally {
                    operationsCache.remove(opName)
                }
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    public override fun execute(request: ExecuteRequest, responseObserver: StreamObserver<Operation?>) {
        val metadata: RequestMetadata? = TracingMetadataUtils.fromCurrentContext()
        val context: RemoteActionExecutionContext = RemoteActionExecutionContext.create(metadata)

        val opName: String? = UUID.randomUUID().toString()
        val future: com.google.common.util.concurrent.ListenableFuture<ActionResult?> =
            executorService.submit<ActionResult?>(java.util.concurrent.Callable { execute(context, request, opName) })
        operationsCache.put(opName, future)
        (responseObserver as ServerCallStreamObserver<Operation?>)
            .setOnCancelHandler(java.lang.Runnable { operationsCache.remove(opName) })
        // Send the first operation.
        responseObserver.onNext(Operation.newBuilder().setName(opName).build())
        // When the operation completes, send the result.
        waitExecution(opName, future, responseObserver)
    }

    @Throws(IOException::class, java.lang.InterruptedException::class, StatusException::class)
    private fun execute(
        context: RemoteActionExecutionContext, request: ExecuteRequest, id: String?
    ): ActionResult? {
        val tempRoot: Path = workPath.getRelative("build-" + id)
        var workDetails: String? = ""
        try {
            tempRoot.createDirectory()
            val meta: RequestMetadata = context.getRequestMetadata()
            workDetails =
                java.lang.String.format(
                    "build-request-id: %s command-id: %s action-id: %s",
                    meta.getCorrelatedInvocationsId(), meta.getToolInvocationId(), meta.getActionId()
                )
            logger.atFine().log("Received work for: %s", workDetails)
            val result: ActionResult? =
                execute(
                    context,
                    request.getActionDigest(),
                    com.google.common.collect.ImmutableSet.copyOf(request.getInlineOutputFilesList()),
                    tempRoot
                )
            logger.atFine().log("Completed %s", workDetails)
            return result
        } catch (e: java.lang.Exception) {
            logger.atSevere().withCause(e).log("Work failed: %s", workDetails)
            throw e
        } finally {
            if (workerOptions.getDebug()) {
                logger.atInfo().log("Preserving work directory %s", tempRoot)
            } else {
                try {
                    tempRoot.deleteTree()
                } catch (e: IOException) {
                    logger.atSevere().withCause(e).log("Failed to delete tmp directory %s", tempRoot)
                }
            }
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class, StatusException::class)
    private fun execute(
        context: RemoteActionExecutionContext?,
        actionDigest: Digest?,
        inlineOutputFiles: MutableSet<String?>,
        execRoot: Path
    ): ActionResult? {
        val command: Command
        val action: Action
        val actionKey: ActionKey? = digestUtil.asActionKey(actionDigest)
        try {
            action =
                Action.parseFrom(
                    getFromFuture(cache.downloadBlob(context, actionDigest)),
                    ExtensionRegistry.getEmptyRegistry()
                )
            command =
                Command.parseFrom(
                    getFromFuture(cache.downloadBlob(context, action.getCommandDigest())),
                    ExtensionRegistry.getEmptyRegistry()
                )
            cache.downloadTree(context, action.getInputRootDigest(), execRoot)
        } catch (e: CacheNotFoundException) {
            throw StatusUtils.missingBlobError(e.getMissingDigest())
        }

        val workingDirectory: Path = execRoot.getRelative(unicodeToInternal(command.getWorkingDirectory()))
        workingDirectory.createDirectoryAndParents()

        val outputs: MutableList<Path?>?
        if (command.getOutputPathsCount() === 0) {
            outputs =
                java.util.ArrayList<Path?>(command.getOutputDirectoriesCount() + command.getOutputFilesCount())
            for (output in command.getOutputFilesList()) {
                val file: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    workingDirectory.getRelative(unicodeToInternal(output))
                if (file.exists()) {
                    throw FileAlreadyExistsException("Output file already exists: " + file)
                }
                file.getParentDirectory().createDirectoryAndParents()
                outputs!!.add(file)
            }
            for (output in command.getOutputDirectoriesList()) {
                val file: Path = workingDirectory.getRelative(unicodeToInternal(output))
                if (file.exists()) {
                    if (!file.isDirectory()) {
                        throw FileAlreadyExistsException(
                            "Non-directory exists at output directory path: " + file
                        )
                    }
                }
                file.getParentDirectory().createDirectoryAndParents()
                outputs!!.add(file)
            }
        } else {
            outputs = java.util.ArrayList<Any?>(command.getOutputPathsCount())
            for (output in command.getOutputPathsList()) {
                val file: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    workingDirectory.getRelative(unicodeToInternal(output))
                // Since https://github.com/bazelbuild/bazel/pull/15818,
                // Bazel includes all expected output directories as part of Action's inputs.
                //
                // Ensure no output file exists before execution happen.
                // Ignore if output directories pre-exist.
                if (file.exists() && !file.isDirectory()) {
                    throw FileAlreadyExistsException("Output file already exists: " + file)
                }
                file.getParentDirectory().createDirectoryAndParents()
                outputs!!.add(file)
            }
        }

        // TODO(ulfjack): This is basically a copy of LocalSpawnRunner. Ideally, we'd use that
        // implementation instead of copying it.
        val cmd: com.google.devtools.build.lib.shell.Command =
            getCommand(command, workingDirectory.asFragment())
        val startTime: Instant = Instant.now()
        var cmdResult: CommandResult? = null

        val uuid: String? = UUID.randomUUID().toString()
        val stdout: Path? = execRoot.getChild("stdout-" + uuid)
        val stderr: Path? = execRoot.getChild("stderr-" + uuid)
        FileOutErr(stdout, stderr).use { outErr ->
            var futureCmdResult: FutureCommandResult? = null
            try {
                futureCmdResult = cmd.executeAsync(outErr.getOutputStream(), outErr.getErrorStream())
            } catch (e: CommandException) {
                com.google.common.base.Throwables.throwIfInstanceOf<X?>(e.getCause(), IOException::class.java)
            }

            if (futureCmdResult != null) {
                try {
                    cmdResult = futureCmdResult.get()
                } catch (e: AbnormalTerminationException) {
                    cmdResult = e.getResult()
                }
            }

            val wallTime: java.time.Duration = java.time.Duration.between(startTime, Instant.now())
            val timeoutMillis: Long =
                if (action.hasTimeout())
                    Durations.toMillis(action.getTimeout())
                else
                    TimeUnit.MINUTES.toMillis(15)
            val wasTimeout =
                (cmdResult != null && cmdResult.terminationStatus().timedOut())
                        || wasTimeout(timeoutMillis, wallTime.toMillis())
            val exitCode: Int
            var errStatus: Status? = null
            val resp: ExecuteResponse.Builder = ExecuteResponse.newBuilder()
            if (wasTimeout) {
                val errMessage: String? = String.format(
                    "Command:\n%s\nexceeded deadline of %f seconds.",
                    getArguments(command), timeoutMillis / 1000.0
                )
                logger.atWarning().log("%s", errMessage)
                errStatus =
                    Status.newBuilder()
                        .setCode(Code.DEADLINE_EXCEEDED.getNumber())
                        .setMessage(errMessage)
                        .build()
                exitCode = LOCAL_EXEC_ERROR
            } else if (cmdResult == null) {
                exitCode = LOCAL_EXEC_ERROR
            } else {
                exitCode = cmdResult.terminationStatus().getRawExitCode()
            }

            var result: ActionResult? = null
            try {
                val manifest: UploadManifest =
                    UploadManifest.create(
                        cache.getRemoteCacheCapabilities(),
                        digestUtil,
                        RemotePathResolver.createDefault(workingDirectory),
                        actionKey,
                        action,
                        command,
                        outputs,
                        outErr,
                        exitCode,
                        startTime,
                        wallTime.toMillis().toInt(),  /* preserveExecutableBit= */
                        false
                    )
                result = manifest.upload(context, cache, NullEventHandler.INSTANCE)
            } catch (e: ExecException) {
                if (errStatus == null) {
                    errStatus =
                        Status.newBuilder()
                            .setCode(Code.FAILED_PRECONDITION.getNumber())
                            .setMessage(e.getMessage())
                            .build()
                }
            }

            if (result == null) {
                result = ActionResult.newBuilder().setExitCode(exitCode).build()
            }

            var i = 0
            while (i < result.getOutputFilesCount()) {
                val outputFile: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    result.getOutputFiles(i)
                if (inlineOutputFiles.contains(outputFile.getPath())) {
                    try {
                        val content: ByteString =
                            ByteString.copyFrom(cache.downloadBlob(context, outputFile.getDigest()).get())
                        result =
                            result.toBuilder()
                                .setOutputFiles(i, outputFile.toBuilder().setContents(content))
                                .build()
                    } catch (e: ExecutionException) {
                        // Inlining is best-effort. If it fails, we just don't inline the file.
                    }
                    break
                }
                i++
            }

            resp.setResult(result)

            if (errStatus != null) {
                resp.setStatus(errStatus)
                throw ExecutionStatusException(errStatus, resp.build())
            }
            return result
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun getEnvironmentVariables(command: Command): MutableMap<String?, String?> {
        val result: HashMap<String?, String?> = HashMap<String?, String?>()
        for (v in command.getEnvironmentVariablesList()) {
            result.put(unicodeToInternal(v.getName()), unicodeToInternal(v.getValue()))
        }
        return HashMap<Any?, Any?>(localEnvProvider.rewriteLocalEnv(result, binTools, "/tmp"))
    }

    // Converts the Command proto into the shell Command object.
    // If no docker container is specified, creates a Command straight from the
    // arguments. Otherwise, returns a Command that would run the specified command inside the
    // specified docker container.
    @Throws(StatusException::class, java.lang.InterruptedException::class, IOException::class)
    private fun getCommand(
        cmd: Command, workingDirectory: PathFragment
    ): com.google.devtools.build.lib.shell.Command {
        val arguments: com.google.common.collect.ImmutableList<String?> = getArguments(cmd)
        val environmentVariables = getEnvironmentVariables(cmd)
        // This allows Bazel's integration tests to test for the remote platform.
        environmentVariables.put("BAZEL_REMOTE_PLATFORM", platformAsString(cmd.getPlatform()))
        val container = dockerContainer(cmd)
        if (container != null) {
            // Run command inside a docker container.
            val newCommandLineElements: java.util.ArrayList<String?> = java.util.ArrayList<String?>(arguments.size)
            newCommandLineElements.add("docker")
            newCommandLineElements.add("run")

            // -u doesn't currently make sense for Windows:
            // https://github.com/docker/for-win/issues/636#issuecomment-293653788
            if (!isWindows) {
                val uid = uid
                if (uid >= 0) {
                    newCommandLineElements.add("-u")
                    newCommandLineElements.add(uid.toString())
                }
            }

            val dockerPathString = workingDirectory.getPathString() + "-docker"

            newCommandLineElements.add("-v")
            newCommandLineElements.add(workingDirectory.getPathString() + ":" + dockerPathString)
            newCommandLineElements.add("-w")
            newCommandLineElements.add(dockerPathString)

            for (entry in environmentVariables.entries) {
                val key = entry.key
                val value = entry.value

                newCommandLineElements.add("-e")
                newCommandLineElements.add(key + "=" + value)
            }

            newCommandLineElements.add(container)

            newCommandLineElements.addAll(arguments)

            return Command(
                newCommandLineElements,
                null,
                java.io.File(internalToPlatform(workingDirectory.getPathString())),
                java.lang.System.getenv()
            )
        } else if (sandboxPath != null) {
            // Run command with sandboxing.
            val newCommandLineElements: java.util.ArrayList<String?> = java.util.ArrayList<String?>(arguments.size)
            newCommandLineElements.add(sandboxPath.getPathString())
            if (workerOptions.getSandboxingBlockNetwork()) {
                newCommandLineElements.add("-N")
            }
            for (writablePath in workerOptions.getSandboxingWritablePaths()) {
                newCommandLineElements.add("-w")
                newCommandLineElements.add(writablePath.getPathString())
            }
            for (tmpfsDir in workerOptions.getSandboxingTmpfsDirs()) {
                newCommandLineElements.add("-e")
                newCommandLineElements.add(tmpfsDir.getPathString())
            }
            newCommandLineElements.add("--")
            newCommandLineElements.addAll(arguments)
            return Command(
                newCommandLineElements,
                environmentVariables,
                java.io.File(internalToPlatform(workingDirectory.getPathString())),
                java.lang.System.getenv()
            )
        } else {
            // Just run the command.
            return Command(
                arguments,
                environmentVariables,
                java.io.File(internalToPlatform(workingDirectory.getPathString())),
                java.lang.System.getenv()
            )
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        // The name of the container image entry in the Platform proto
        // (see third_party/googleapis/devtools/remoteexecution/*/remote_execution.proto and
        // remote_default_exec_properties in
        // src/main/java/com/google/devtools/build/lib/remote/RemoteOptions.java)
        private const val CONTAINER_IMAGE_ENTRY_NAME = "container-image"
        private const val DOCKER_IMAGE_PREFIX = "docker://"

        // How long to wait for the uid command.
        private val uidTimeout: java.time.Duration? = java.time.Duration.ofMillis(30)

        private val LOCAL_EXEC_ERROR = -1

        private val isWindows: Boolean
            // Returns true if the OS being run on is Windows (or some close approximation thereof).
            get() = java.lang.System.getProperty("os.name").startsWith("Windows")

        private fun wasTimeout(timeoutMillis: Long, wallTimeMillis: Long): Boolean {
            return timeoutMillis > 0 && wallTimeMillis > timeoutMillis
        }

        private fun getArguments(command: Command): com.google.common.collect.ImmutableList<String?> {
            val result: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            for (arg in command.getArgumentsList()) {
                result.add(unicodeToInternal(arg))
            }
            return result.build()
        }

        @get:Throws(java.lang.InterruptedException::class)
        private val uid: Long
            // Gets the uid of the current user. If uid could not be successfully fetched (e.g., on other
            get() {
                val cmd: com.google.devtools.build.lib.shell.Command =
                    Command(
                        com.google.common.collect.ImmutableList.of<E?>("id", "-u"),  /* environmentVariables= */
                        null,  /* workingDirectory= */
                        null,
                        uidTimeout,
                        java.lang.System.getenv()
                    )
                try {
                    val stdout: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
                    val stderr: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
                    cmd.execute(stdout, stderr)
                    return stdout.toString().trim { it <= ' ' }.toLong()
                } catch (e: CommandException) {
                    logger.atWarning().withCause(e).log(
                        "Could not get UID for passing to Docker container. Proceeding without it"
                    )
                    return -1
                } catch (e: java.lang.NumberFormatException) {
                    logger.atWarning().withCause(e).log(
                        "Could not get UID for passing to Docker container. Proceeding without it"
                    )
                    return -1
                }
            }

        // Checks Action for docker container definition. If no docker container specified, returns
        // null. Otherwise returns docker container name from the parameters.
        @Throws(StatusException::class)
        private fun dockerContainer(cmd: Command): String? {
            var result: String? = null
            for (property in cmd.getPlatform().getPropertiesList()) {
                val name: String = unicodeToInternal(property.getName())
                val value: String? = unicodeToInternal(property.getValue())
                if (name == CONTAINER_IMAGE_ENTRY_NAME) {
                    if (result != null) {
                        // Multiple container name entries
                        throw StatusUtils.invalidArgumentError(
                            "platform",  // Field name.
                            String.format(
                                "Multiple entries for %s in action.Platform", CONTAINER_IMAGE_ENTRY_NAME
                            )
                        )
                    }
                    result = value
                    if (!result.startsWith(DOCKER_IMAGE_PREFIX)) {
                        throw StatusUtils.invalidArgumentError(
                            "platform",  // Field name.
                            String.format(
                                "%s: Docker images must be stored in gcr.io with an image spec in the form "
                                        + "'docker://gcr.io/{IMAGE_NAME}'",
                                CONTAINER_IMAGE_ENTRY_NAME
                            )
                        )
                    }
                    result = result.substring(DOCKER_IMAGE_PREFIX.length)
                }
            }
            return result
        }

        private fun platformAsString(platform: Platform?): String {
            if (platform == null) {
                return ""
            }

            var separator = ""
            val result: java.lang.StringBuilder = java.lang.StringBuilder()
            for (property in platform.getPropertiesList()) {
                val name: String? = unicodeToInternal(property.getName())
                val value: String? = unicodeToInternal(property.getValue())
                result.append(separator).append(name).append("=").append(value)
                separator = ","
            }
            return result.toString()
        }
    }
}
