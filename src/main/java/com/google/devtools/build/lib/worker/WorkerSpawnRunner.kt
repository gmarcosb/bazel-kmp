// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.actions.ActionExecutionMetadata

/**
 * A spawn runner that launches Spawns the first time they are used in a persistent mode and then
 * shards work over all the processes.
 */
internal class WorkerSpawnRunner(
    execRoot: com.google.devtools.build.lib.vfs.Path?,
    workers: WorkerPool?,
    reporter: ExtendedEventHandler?,
    localEnvProvider: LocalEnvProvider?,
    binTools: BinTools?,
    resourceManager: ResourceManager,
    runfilesTreeUpdater: RunfilesTreeUpdater,
    workerOptions: WorkerOptions,
    workerProcessMetricsCollector: WorkerProcessMetricsCollector,
    clock: com.google.devtools.build.lib.clock.Clock?
) : SpawnRunner {
    private val execRoot: com.google.devtools.build.lib.vfs.Path?
    private val reporter: ExtendedEventHandler?
    private val resourceManager: ResourceManager
    private val runfilesTreeUpdater: RunfilesTreeUpdater
    private val workerOptions: WorkerOptions
    private val workerParser: WorkerParser
    private val metricsCollector: WorkerProcessMetricsCollector

    init {
        this.execRoot = execRoot
        this.reporter = reporter
        this.resourceManager = resourceManager
        this.runfilesTreeUpdater = runfilesTreeUpdater
        this.workerParser = WorkerParser(execRoot, workerOptions, localEnvProvider, binTools)
        this.workerOptions = workerOptions
        this.resourceManager.setWorkerPool(workers)
        this.metricsCollector = workerProcessMetricsCollector
        this.metricsCollector.setClock(clock)
    }

    val name: String
        get() = "worker"

    public override fun canExec(spawn: Spawn): Boolean {
        if (!Spawns.supportsWorkers(spawn) && !Spawns.supportsMultiplexWorkers(spawn)) {
            return false
        }
        // Note: `allowlist` is sorted, we could binary search.
        if (workerOptions.getAllowlist() != null && !workerOptions.getAllowlist()
                .isEmpty() && !workerOptions.getAllowlist().contains(Spawns.getWorkerKeyMnemonic(spawn))
        ) {
            return false
        }
        if (spawn.getToolFiles().isEmpty()) {
            return false
        }
        return true
    }

    public override fun handlesCaching(): Boolean {
        return false
    }

    @Throws(ExecException::class, IOException::class, java.lang.InterruptedException::class)
    public override fun exec(spawn: Spawn, context: SpawnExecutionContext): SpawnResult {
        context.report(
            SpawnSchedulingEvent.create(
                WorkerKey.Companion.makeWorkerTypeName(
                    Spawns.supportsMultiplexWorkers(spawn) && workerOptions.getWorkerMultiplex(),
                    context.speculating()
                )
            )
        )
        if (spawn.getToolFiles().isEmpty()) {
            throw createUserExecException(
                java.lang.String.format(ERROR_MESSAGE_PREFIX + REASON_NO_TOOLS, spawn.getMnemonic()),
                Code.NO_TOOLS
            )
        }

        val startTime: Instant = Instant.now()
        val spawnMetrics: SpawnMetrics.Builder
        val response: WorkResponse

        com.google.devtools.build.lib.profiler.Profiler.instance()
            .profile(
                java.lang.String.format(
                    "%s worker %s", spawn.getMnemonic(), spawn.getResourceOwner().describe()
                )
            ).use { c ->
                com.google.devtools.build.lib.profiler.Profiler.instance().profile("updateRunfiles").use { s ->
                    val runfilesTrees: MutableList<RunfilesTree?> = java.util.ArrayList<RunfilesTree?>()
                    for (toolFile in spawn.getToolFiles().toList()) {
                        if ((toolFile is Artifact) && (toolFile as Artifact).isRunfilesTree()) {
                            runfilesTrees.add(
                                context.inputMetadataProvider.getRunfilesMetadata(toolFile).getRunfilesTree()
                            )
                        }
                    }
                    runfilesTreeUpdater.updateRunfiles(runfilesTrees)
                }
                val inputFileCache: InputMetadataProvider = context.inputMetadataProvider

                val inputFiles: SandboxInputs
                com.google.devtools.build.lib.profiler.Profiler.instance()
                    .profile(ProfilerTask.WORKER_SETUP, "Setting up inputs").use { c1 ->
                        inputFiles =
                            SandboxHelpers.processInputFiles(
                                context.getInputMapping(
                                    PathFragment.Companion.EMPTY_FRAGMENT,  /* willAccessRepeatedly= */true
                                ),
                                execRoot
                            )
                    }
                val outputs: SandboxOutputs? = SandboxHelpers.getOutputs(spawn)

                val workerConfig: WorkerConfig = workerParser.compute(spawn, context)
                val key: WorkerKey = workerConfig.getWorkerKey()
                val flagFiles: MutableList<String> = workerConfig.getFlagFiles()

                spawnMetrics =
                    SpawnMetrics.Builder.forWorkerExec()
                        .setInputFiles(inputFiles.getFiles().size() + inputFiles.getSymlinks().size())
                response =
                    execInWorker(
                        spawn, key, context, inputFiles, outputs, flagFiles, inputFileCache, spawnMetrics
                    )

                val outErr: FileOutErr = context.fileOutErr
                response.getOutputBytes().writeTo(outErr.getErrorStream())
            }
        val wallTime: java.time.Duration = java.time.Duration.between(startTime, Instant.now())

        val exitCode: Int = response.getExitCode()
        val builder: SpawnResult.Builder =
            getSpawnResultBuilder(context)
                .setExitCode(exitCode)
                .setStatus(if (exitCode == 0) Status.SUCCESS else Status.NON_ZERO_EXIT)
                .setStartTime(startTime)
                .setWallTimeInMs(wallTime.toMillis().toInt())
                .setSpawnMetrics(spawnMetrics.setTotalTime(wallTime).build())
        if (exitCode != 0) {
            builder.setFailureDetail(
                FailureDetail.newBuilder()
                    .setMessage("worker spawn failed for " + spawn.getMnemonic())
                    .setSpawn(
                        FailureDetails.Spawn.newBuilder()
                            .setCode(FailureDetails.Spawn.Code.NON_ZERO_EXIT)
                            .setSpawnExitCode(exitCode)
                    )
                    .build()
            )
        }
        return builder.build()
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun createWorkRequest(
        spawn: Spawn,
        context: SpawnExecutionContext,
        inputFiles: SandboxInputs,
        flagfiles: MutableList<String>,
        virtualInputDigests: MutableMap<VirtualActionInput?, ByteArray?>,
        inputFileCache: InputMetadataProvider,
        key: WorkerKey
    ): WorkRequest {
        val requestBuilder: WorkRequest.Builder = WorkRequest.newBuilder()
        for (flagfile in flagfiles) {
            expandArgument(inputFiles, flagfile, requestBuilder)
        }

        val inputs: MutableList<ActionInput> =
            InputMetadataProvider.expandArtifacts(
                context.inputMetadataProvider,
                spawn.getInputFiles(),  /* keepEmptyTreeArtifacts= */
                false,  /* keepRunfilesTrees= */
                false
            )

        for (input in inputs) {
            val digestBytes: ByteArray?
            if (input is VirtualActionInput) {
                digestBytes =
                    com.google.common.base.Preconditions.checkNotNull<ByteArray?>(
                        virtualInputDigests.get(input),
                        "missing metadata for virtual input"
                    )
            } else {
                val metadata: FileArtifactValue =
                    checkNotNull(inputFileCache.getInputMetadata(input), "missing metadata for input")
                digestBytes = metadata.getDigest()
            }
            val digest: ByteString?
            if (digestBytes == null || digestBytes.size == 0) {
                digest = ByteString.EMPTY
            } else {
                digest = ByteString.copyFromUtf8(com.google.common.hash.HashCode.fromBytes(digestBytes).toString())
            }

            requestBuilder
                .addInputsBuilder()
                .setPath(StringEncoding.internalToUnicode(input.getExecPathString()))
                .setDigest(digest)
        }
        if (workerOptions.getWorkerVerbose()) {
            requestBuilder.setVerbosity(VERBOSE_LEVEL)
        }
        if (key.isMultiplex()) {
            requestBuilder.setRequestId(requestIdCounter.getAndIncrement())
        }
        return requestBuilder.build()
    }

    @Throws(ExecException::class, IOException::class, java.lang.InterruptedException::class)
    fun execInWorker(
        spawn: Spawn,
        key: WorkerKey,
        context: SpawnExecutionContext,
        inputFiles: SandboxInputs,
        outputs: SandboxOutputs?,
        flagFiles: MutableList<String>,
        inputFileCache: InputMetadataProvider,
        spawnMetrics: SpawnMetrics.Builder
    ): WorkResponse {
        var workerOwner: WorkerOwner? = null
        var response: WorkResponse
        val request: WorkRequest
        val owner: ActionExecutionMetadata? = spawn.getResourceOwner()
        val virtualInputDigests: com.google.common.collect.ImmutableMap<VirtualActionInput?, ByteArray?> =
            inputFiles.getVirtualInputDigests()

        val setupInputsStopwatch: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
        var hasOutputFileLock = false

        com.google.devtools.build.lib.profiler.Profiler.instance()
            .profile(ProfilerTask.WORKER_SETUP, "Preparing inputs").use { c ->
                context.prefetchInputsAndWait()
            }
        val setupInputsTime: java.time.Duration = setupInputsStopwatch.elapsed()
        spawnMetrics.setSetupTime(setupInputsTime)

        val queueStopwatch: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
        val resourceSet: ResourceSet? =
            ResourceSet.create(
                spawn.getLocalResources().getResources(),
                spawn.getLocalResources().getLocalTestCount(),
                key
            )

        // Worker doesn't automatically return to pool after closing of the handle.
        var handle: ResourceHandle? = null
        try {
            handle =
                resourceManager.acquireResources(
                    owner,
                    resourceSet,
                    if (context.speculating()) ResourcePriority.DYNAMIC_WORKER else ResourcePriority.LOCAL
                )
            workerOwner = WorkerOwner(handle.getWorker())
            workerOwner.getWorker().setReporter(if (workerOptions.getWorkerVerbose()) reporter else null)
            request =
                createWorkRequest(
                    spawn, context, inputFiles, flagFiles, virtualInputDigests, inputFileCache, key
                )

            // We acquired a worker and resources -- mark that as queuing time.
            spawnMetrics.setQueueTime(queueStopwatch.elapsed())
            response =
                executeRequest(
                    spawn, context, inputFiles, outputs, workerOwner, key, request, spawnMetrics, handle
                )

            if (response == null) {
                throw createEmptyResponseException(workerOwner.getWorker().getLogFile())
            }

            if (response.getWasCancelled()) {
                throw createUserExecException(
                    "Received cancel response for " + response.getRequestId() + " without having cancelled",
                    Code.FINISH_FAILURE
                )
            }

            try {
                com.google.devtools.build.lib.profiler.Profiler.instance()
                    .profile(
                        ProfilerTask.WORKER_COPYING_OUTPUTS,
                        java.lang.String.format(
                            "Worker #%d copying output files", workerOwner.getWorker().getWorkerId()
                        )
                    ).use { c ->
                        if (workerOwner.getWorker() != null) {
                            val processOutputsStopwatch: com.google.common.base.Stopwatch =
                                com.google.common.base.Stopwatch.createStarted()
                            context.lockOutputFiles(response.getExitCode(), response.getOutput(), null)
                            hasOutputFileLock = true
                            workerOwner.getWorker().finishExecution(execRoot, outputs)
                            WorkerProcessMetricsCollector.Companion.instance()
                                .onWorkerFinishExecution(workerOwner.getWorker().getProcessId())
                            spawnMetrics.setProcessOutputsTime(processOutputsStopwatch.elapsed())
                        } else {
                            throw createUserExecException(
                                "The response finished successfully, but worker is taken by finishAsync",
                                Code.FINISH_FAILURE
                            )
                        }
                    }
            } catch (e: IOException) {
                restoreInterrupt(e)
                val message: String? =
                    ErrorMessage.Companion.builder()
                        .message("IOException while finishing worker execution:")
                        .logFile(workerOwner.getWorker().getLogFile())
                        .exception(e)
                        .build()
                        .toString()
                throw createUserExecException(message, Code.FINISH_FAILURE)
            }
        } catch (e: IOException) {
            restoreInterrupt(e)
            val message = "IOException during worker execution:"
            throw createUserExecException(e, message, Code.BORROW_FAILURE)
        } catch (e: UserExecException) {
            val worker: com.google.devtools.build.lib.worker.Worker? =
                if (workerOwner == null) null else workerOwner.getWorker()
            if (handle != null && worker != null) {
                try {
                    if (e is java.lang.InterruptedException && context.speculating()) {
                        // When interrupted, we don't want to invalidate and kill the worker only to start it up
                        // later again (in dynamic execution). Just #close() the handle to release the acquired
                        // resources.
                        handle.close()
                    } else {
                        handle.invalidateAndClose(e)
                    }
                    if (!hasOutputFileLock && worker.getExitValue().isPresent()) {
                        // If the worker has died, we take the lock to a) fail earlier and b) have a chance
                        // to let the other dynamic execution branch take over if the error can be ignored.
                        context.lockOutputFiles(worker.getExitValue().get(), e.getMessage(), null)
                    }
                } catch (e1: IOException) {
                    // The original exception is more important / helpful, so we'll just ignore this one.
                    restoreInterrupt(e1)
                } finally {
                    workerOwner!!.setWorker(null)
                }
            }
            throw e
        } catch (e: java.lang.InterruptedException) {
            val worker: com.google.devtools.build.lib.worker.Worker? =
                if (workerOwner == null) null else workerOwner.getWorker()
            if (handle != null && worker != null) {
                try {
                    if (e is java.lang.InterruptedException && context.speculating()) {
                        handle.close()
                    } else {
                        handle.invalidateAndClose(e)
                    }
                    if (!hasOutputFileLock && worker.getExitValue().isPresent()) {
                        context.lockOutputFiles(worker.getExitValue().get(), e.getMessage(), null)
                    }
                } catch (e1: IOException) {
                    restoreInterrupt(e1)
                } finally {
                    workerOwner!!.setWorker(null)
                }
            }
            throw e
        } finally {
            // if worker owner haven't initialized or we still haven't relased worker, than we need to
            // return resources.
            if (handle != null && (workerOwner == null || workerOwner.getWorker() != null)) {
                try {
                    handle.close()
                } catch (e: IOException) {
                    restoreInterrupt(e)
                    val message = "IOException while returning a worker from the pool:"
                    throw createUserExecException(e, message, Code.BORROW_FAILURE)
                }
            }
        }
        return response
    }

    /**
     * Executes worker request in worker, waits until the response is ready. Worker and resources
     * should be allocated before call.
     */
    @Throws(ExecException::class, java.lang.InterruptedException::class)
    private fun executeRequest(
        spawn: Spawn?,
        context: SpawnExecutionContext,
        inputFiles: SandboxInputs?,
        outputs: SandboxOutputs?,
        workerOwner: WorkerOwner,
        key: WorkerKey,
        request: WorkRequest,
        spawnMetrics: SpawnMetrics.Builder,
        handle: ResourceHandle
    ): WorkResponse {
        val response: WorkResponse
        context.report(SpawnExecutingEvent.create(key.getWorkerTypeName()))
        val worker: com.google.devtools.build.lib.worker.Worker = workerOwner.getWorker()

        try {
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .profile(
                    ProfilerTask.WORKER_SETUP,
                    java.lang.String.format("Worker #%d preparing execution", worker.getWorkerId())
                ).use { c ->
                    // We consider `prepareExecution` to be also part of setup.
                    val prepareExecutionStopwatch: com.google.common.base.Stopwatch =
                        com.google.common.base.Stopwatch.createStarted()
                    worker.prepareExecution(
                        inputFiles, outputs, key.getWorkerFilesWithDigests().keySet(), context.clientEnv
                    )
                    initializeMetrics(key, worker)
                    spawnMetrics.addSetupTime(prepareExecutionStopwatch.elapsed())
                }
        } catch (e: IOException) {
            restoreInterrupt(e)
            val message: String? =
                ErrorMessage.Companion.builder()
                    .message("IOException while preparing the execution environment of a worker:")
                    .logFile(worker.getLogFile())
                    .exception(e)
                    .build()
                    .toString()
            throw createUserExecException(message, Code.PREPARE_FAILURE)
        }

        val executionStopwatch: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
        try {
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .profile(ProfilerTask.WORKER_SETUP, "sending request").use { c ->
                    worker.putRequest(request)
                }
        } catch (e: IOException) {
            restoreInterrupt(e)
            val message: String? =
                ErrorMessage.Companion.builder()
                    .message(
                        "Worker process quit or closed its stdin stream when we tried to send a"
                                + " WorkRequest:"
                    )
                    .logFile(worker.getLogFile())
                    .exception(e)
                    .build()
                    .toString()
            throw createUserExecException(message, Code.REQUEST_FAILURE)
        }

        try {
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .profile(
                    ProfilerTask.WORKER_WORKING,
                    java.lang.String.format("Worker #%d working", worker.getWorkerId())
                ).use { c ->
                    response = worker.getResponse(request.getRequestId())
                }
        } catch (e: java.lang.InterruptedException) {
            if (worker.isSandboxed()) {
                // Sandboxed workers can safely finish their work async.
                finishWorkAsync(
                    worker,
                    request,
                    workerOptions.getWorkerCancellation() && Spawns.supportsWorkerCancellation(spawn),
                    handle
                )
                workerOwner.setWorker(null)
                resourceManager.releaseResourceOwnership()
            }
            throw e
        } catch (e: IOException) {
            restoreInterrupt(e)
            // If protobuf or json reader couldn't parse the response, try to print whatever the
            // failing worker wrote to stdout - it's probably a stack trace or some kind of error
            // message that will help the user figure out why the compiler is failing.
            val recordingStreamMessage: String = worker.getRecordingStreamMessage()
            if (recordingStreamMessage.isEmpty()) {
                throw createEmptyResponseException(worker.getLogFile())
            } else {
                throw createUnparsableResponseException(recordingStreamMessage, worker.getLogFile(), e)
            }
        }

        spawnMetrics.setExecutionWallTime(executionStopwatch.elapsed())

        return response
    }

    private fun initializeMetrics(workerKey: WorkerKey, worker: com.google.devtools.build.lib.worker.Worker) {
        this.metricsCollector.registerWorker(
            worker.getWorkerId(),
            worker.getProcessId(),
            worker.getStatus(),
            workerKey.getMnemonic(),
            workerKey.isMultiplex(),
            workerKey.isSandboxed(),
            workerKey.hashCode(),
            worker.getCgroup()
        )
    }

    /**
     * Starts a thread to collect the response from a worker when it's no longer of interest.
     * 
     * 
     * This can happen either when we lost the race in dynamic execution or the build got
     * interrupted. This takes ownership of the worker for purposes of returning it to the worker
     * pool.
     */
    private fun finishWorkAsync(
        worker: com.google.devtools.build.lib.worker.Worker,
        request: WorkRequest,
        canCancel: Boolean,
        resourceHandle: ResourceHandle
    ) {
        val reaper: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    resourceManager.acquireResourceOwnership()
                    var w: com.google.devtools.build.lib.worker.Worker? = worker
                    try {
                        if (canCancel) {
                            val cancelRequest: WorkRequest? =
                                WorkRequest.newBuilder()
                                    .setRequestId(request.getRequestId())
                                    .setCancel(true)
                                    .build()
                            w.putRequest(cancelRequest)
                        }
                        w.getResponse(request.getRequestId())
                    } catch (e1: IOException) {
                        // If this happens, we either can't trust the output of the worker, or we got
                        // interrupted while handling being interrupted. In the latter case, let's stop
                        // trying and just destroy the worker. If it's a singleplex worker, there will
                        // be a dangling response that we don't want to keep trying to read, so we destroy
                        // the worker.
                        try {
                            resourceHandle.invalidateAndClose(e1)

                            w = null
                        } catch (e2: IOException) {
                            // The reaper thread can't do anything useful about this.
                        } catch (e2: java.lang.InterruptedException) {
                        } catch (e2: UserExecException) {
                        }
                    } catch (e1: java.lang.InterruptedException) {
                        try {
                            resourceHandle.invalidateAndClose(e1)

                            w = null
                        } catch (e2: IOException) {
                        } catch (e2: java.lang.InterruptedException) {
                        } catch (e2: UserExecException) {
                        }
                    } finally {
                        if (w != null) {
                            try {
                                resourceHandle.close()
                            } catch (e: IOException) {
                                // Error while returning worker to the pool. Could not do anything.
                            } catch (e: java.lang.InterruptedException) {
                            } catch (e: java.lang.IllegalStateException) {
                            } catch (e: UserExecException) {
                            }
                        }
                    }
                },
                "AsyncFinish-Worker-" + worker.workerId
            )
        reaper.start()
    }

    /**
     * The structure helps to pass the worker's ownership from one function to another. If worker is
     * set to null, then the ownership is taken by another function. E.g. used in finishWorkAsync.
     */
    private class WorkerOwner(worker: com.google.devtools.build.lib.worker.Worker) {
        var worker: com.google.devtools.build.lib.worker.Worker

        init {
            this.worker = worker
        }

        fun setWorker(worker: com.google.devtools.build.lib.worker.Worker) {
            this.worker = worker
        }

        fun getWorker(): com.google.devtools.build.lib.worker.Worker {
            return worker
        }
    }

    companion object {
        const val ERROR_MESSAGE_PREFIX: String = "Worker strategy cannot execute this %s action, "
        const val REASON_NO_TOOLS: String = "because the action has no tools"

        /**
         * The verbosity level implied by `--worker_verbose`. This value allows for manually setting some
         * only-slightly-verbose levels.
         */
        private const val VERBOSE_LEVEL = 10

        /**
         * The next work request ID to use. This field is static so we don't reuse work request IDs across
         * Bazel invocations. Although that shouldn't happen under normal circumstances because we wait
         * until a request finishes before exiting, it can happen if dynamic execution is enabled and one
         * branch beats the other in the race.
         */
        private val requestIdCounter: AtomicInteger = AtomicInteger(1)

        /**
         * Recursively expands arguments by replacing @filename args with the contents of the referenced
         * files. The @ itself can be escaped with @@. This deliberately does not expand --flagfile= style
         * arguments, because we want to get rid of the expansion entirely at some point in time.
         * 
         * 
         * Also check that the argument is not an external repository label, because they start with
         * `@` and are not flagfile locations.
         * 
         * @param inputs the inputs to locate flag files in.
         * @param arg the argument to expand.
         * @param requestBuilder the WorkRequest to whose arguments the expanded arguments will be added.
         * @throws java.io.IOException if one of the files containing options cannot be read.
         */
        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun expandArgument(inputs: SandboxInputs, arg: String, requestBuilder: WorkRequest.Builder) {
            if (arg.startsWith("@") && !arg.startsWith("@@") && !isExternalRepositoryLabel(arg)) {
                if (java.lang.Thread.interrupted()) {
                    throw java.lang.InterruptedException()
                }
                val argValue: String = arg.substring(1)
                val path: com.google.devtools.build.lib.vfs.Path =
                    inputs.getFiles().get(PathFragment.Companion.create(argValue))
                if (path == null) {
                    throw IOException(
                        java.lang.String.format(
                            "Failed to read @-argument '%s': file is not a declared input", argValue
                        )
                    )
                }
                try {
                    for (line in com.google.devtools.build.lib.vfs.FileSystemUtils.readLines(
                        path,
                        java.nio.charset.StandardCharsets.UTF_8
                    )) {
                        expandArgument(inputs, line, requestBuilder)
                    }
                } catch (e: IOException) {
                    throw IOException(
                        java.lang.String.format(
                            "Failed to read @-argument '%s' from file '%s'.", argValue, path.getPathString()
                        ),
                        e
                    )
                }
            } else {
                requestBuilder.addArguments(arg)
            }
        }

        private fun isExternalRepositoryLabel(arg: String): Boolean {
            return arg.matches("^@.*//.*")
        }

        private fun createEmptyResponseException(logfile: com.google.devtools.build.lib.vfs.Path?): UserExecException {
            val message: String? =
                ErrorMessage.Companion.builder()
                    .message("Worker process did not return a WorkResponse:")
                    .logFile(logfile)
                    .logSizeLimit(8192)
                    .build()
                    .toString()
            return createUserExecException(message, Code.NO_RESPONSE)
        }

        private fun createUnparsableResponseException(
            recordingStreamMessage: String?, logfile: com.google.devtools.build.lib.vfs.Path?, e: java.lang.Exception?
        ): UserExecException {
            val message: String? =
                ErrorMessage.Companion.builder()
                    .message(
                        ("Worker process returned an unparseable WorkResponse!\n\n"
                                + "Did you try to print something to stdout? Workers aren't allowed to "
                                + "do this, as it breaks the protocol between Bazel and the worker "
                                + "process.\n\n"
                                + "---8<---8<--- Start of response ---8<---8<---\n"
                                + recordingStreamMessage
                                + "---8<---8<--- End of response ---8<---8<---\n\n")
                    )
                    .logFile(logfile)
                    .logSizeLimit(8192)
                    .exception(e)
                    .build()
                    .toString()
            return createUserExecException(message, Code.PARSE_RESPONSE_FAILURE)
        }

        private fun restoreInterrupt(e: IOException?) {
            if (e is InterruptedIOException) {
                java.lang.Thread.currentThread().interrupt()
            }
        }

        private fun createUserExecException(
            e: IOException?, message: String?, detailedCode: Code?
        ): UserExecException {
            return createUserExecException(
                ErrorMessage.Companion.builder().message(message).exception(e).build().toString(), detailedCode
            )
        }

        private fun createUserExecException(message: String?, detailedCode: Code?): UserExecException {
            return UserExecException(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setWorker(FailureDetails.Worker.newBuilder().setCode(detailedCode))
                    .build()
            )
        }
    }
}
