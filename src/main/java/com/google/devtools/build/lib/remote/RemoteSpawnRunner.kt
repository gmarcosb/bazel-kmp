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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.ExecuteOperationMetadata

/** A client for the remote execution service.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
class RemoteSpawnRunner internal constructor(
    remoteOptions: RemoteOptions,
    verboseFailures: Boolean,
    cmdlineReporter: com.google.devtools.build.lib.events.Reporter?,
    retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService?,
    logDir: com.google.devtools.build.lib.vfs.Path,
    remoteExecutionService: RemoteExecutionService,
    digestUtil: DigestUtil
) : SpawnRunner {
    private val remoteOptions: RemoteOptions
    private val verboseFailures: Boolean
    private val cmdlineReporter: com.google.devtools.build.lib.events.Reporter?
    private val retrier: RemoteRetrier
    private val logDir: com.google.devtools.build.lib.vfs.Path
    private val remoteExecutionService: RemoteExecutionService
    private val digestUtil: DigestUtil

    // Used to ensure that a warning is reported only once.
    private val warningReported: AtomicBoolean = AtomicBoolean()

    init {
        this.remoteOptions = remoteOptions
        this.verboseFailures = verboseFailures
        this.cmdlineReporter = cmdlineReporter
        this.retrier = createExecuteRetrier(remoteOptions, retryService)
        this.logDir = logDir
        this.remoteExecutionService = remoteExecutionService
        this.digestUtil = digestUtil
    }

    @com.google.common.annotations.VisibleForTesting
    fun getRemoteExecutionService(): RemoteExecutionService {
        return remoteExecutionService
    }

    val name: String
        get() = "remote"

    internal class ExecutingStatusReporter(context: SpawnExecutionContext) : OperationObserver {
        private var reportedExecuting = false
        private val context: SpawnExecutionContext

        init {
            this.context = context
        }

        @Throws(IOException::class)
        override fun onNext(o: Operation) {
            if (!reportedExecuting) {
                if (o.getMetadata().`is`(ExecuteOperationMetadata::class.java)) {
                    val metadata: ExecuteOperationMetadata =
                        o.getMetadata().unpack(ExecuteOperationMetadata::class.java)
                    if (metadata.getStage() === Value.EXECUTING) {
                        reportExecuting()
                    }
                } else {
                    // If the server didn't return metadata, we can't know the accurate execution status, so
                    // assuming that the action is accepted by the server and will be executed ASAP.
                    reportExecuting()
                }
            }
        }

        fun reportExecuting() {
            context.report(SPAWN_EXECUTING_EVENT)
            reportedExecuting = true
        }

        fun reportExecutingIfNot() {
            if (!reportedExecuting) {
                reportExecuting()
            }
        }
    }

    @Throws(ExecException::class, java.lang.InterruptedException::class, IOException::class)
    public override fun exec(spawn: Spawn, context: SpawnExecutionContext): SpawnResult? {
        com.google.common.base.Preconditions.checkArgument(
            remoteExecutionService.mayBeExecutedRemotely(spawn),
            "Spawn can't be executed remotely. This is a bug."
        )

        val totalTime: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
        var acceptCachedResult: Boolean = remoteExecutionService.getReadCachePolicy(spawn).allowAnyCache()
        val uploadLocalResults: Boolean = remoteExecutionService.getWriteCachePolicy(spawn).allowAnyCache()

        val action: RemoteAction
        try {
            action =
                remoteExecutionService.buildRemoteAction(
                    spawn,
                    context,
                    if (remoteOptions.getRemoteDiscardMerkleTrees())
                        BlobPolicy.DISCARD
                    else
                        BlobPolicy.KEEP
                )
        } catch (e: RemoteExecutionCapabilitiesException) {
            return execLocallyAndUploadOrFail(null, spawn, context, uploadLocalResults, e)
        }

        context.setDigest(digestUtil.asSpawnLogProto(action.getActionKey()))

        val spawnMetrics: SpawnMetrics.Builder =
            SpawnMetrics.Builder.forRemoteExec()
                .setInputBytes(action.getInputBytes())
                .setInputFiles(action.getInputFiles())

        remoteExecutionService.maybeWriteParamFilesLocally(spawn)

        spawnMetrics.setParseTime(totalTime.elapsed())

        val prof: com.google.devtools.build.lib.profiler.Profiler =
            com.google.devtools.build.lib.profiler.Profiler.instance()
        try {
            context.report(SPAWN_CHECKING_CACHE_EVENT)

            // Try to lookup the action in the action cache.
            val cachedResult: RemoteActionResult?
            prof.profile(ProfilerTask.REMOTE_CACHE_CHECK, "check cache hit").use { c ->
                cachedResult = if (acceptCachedResult) remoteExecutionService.lookupCache(action) else null
            }
            if (cachedResult != null) {
                if (cachedResult.getExitCode() != 0
                    || cachedResult.maybeGetMissingMandatoryOutput(action).isPresent()
                ) {
                    // Failed actions are treated as a cache miss mostly in order to avoid caching flaky
                    // actions (tests).
                    // Set acceptCachedResult to false in order to force the action re-execution
                    acceptCachedResult = false
                } else {
                    try {
                        return downloadAndFinalizeSpawnResult(
                            action,
                            cachedResult,  /* cacheHit= */
                            true,
                            cachedResult.cacheName(),
                            spawn,
                            totalTime,
                            java.util.function.Supplier { action.getNetworkTime().getDuration() },
                            spawnMetrics
                        )
                    } catch (e: BulkTransferException) {
                        if (!e.allCausedByCacheNotFoundException()) {
                            throw e
                        }
                        // No cache hit, so we fall through to local or remote execution.
                        // We set acceptCachedResult to false in order to force the action re-execution.
                        acceptCachedResult = false
                    }
                }
            }
        } catch (e: CredentialHelperException) {
            throw com.google.devtools.build.lib.remote.util.Utils.createExecExceptionForCredentialHelperException(e)
        } catch (e: IOException) {
            return execLocallyAndUploadOrFail(action, spawn, context, uploadLocalResults, e)
        }

        if (remoteOptions.getRemoteRequireCached()) {
            return Builder()
                .setStatus(SpawnResult.Status.EXECUTION_DENIED)
                .setExitCode(1)
                .setFailureMessage(
                    "Action must be cached due to --experimental_remote_require_cached but it is not"
                )
                .setFailureDetail(
                    FailureDetail.newBuilder()
                        .setSpawn(
                            FailureDetails.Spawn.newBuilder()
                                .setCode(FailureDetails.Spawn.Code.EXECUTION_DENIED)
                        )
                        .build()
                )
                .setRunnerName("remote")
                .build()
        }

        val useCachedResult: AtomicBoolean = AtomicBoolean(acceptCachedResult)
        val forceUploadInput: AtomicBoolean = AtomicBoolean(false)
        try {
            return retrier.execute<SpawnResult?, ExecException?>(
                RetryableCallable {
                    prof.profile(ProfilerTask.UPLOAD_TIME, "upload missing inputs").use { c ->
                        val networkTimeStart: java.time.Duration = action.getNetworkTime().getDuration()
                        val uploadTime: com.google.common.base.Stopwatch =
                            com.google.common.base.Stopwatch.createStarted()
                        // Upon retry, we force upload inputs
                        remoteExecutionService.uploadInputsIfNotPresent(
                            action, forceUploadInput.getAndSet(true)
                        )

                        // subtract network time consumed here to ensure wall clock during upload is not
                        // double
                        // counted, and metrics time computation does not exceed total time
                        spawnMetrics.setUploadTime(
                            uploadTime
                                .elapsed()
                                .minus(action.getNetworkTime().getDuration().minus(networkTimeStart))
                        )
                    }
                    context.report(SPAWN_SCHEDULING_EVENT)

                    val reporter = ExecutingStatusReporter(context)
                    val clampTimeNanos: Long // See comment in logProfileTask.
                    val result: RemoteActionResult
                    prof.profile(ProfilerTask.REMOTE_EXECUTION, "execute remotely").use { c ->
                        clampTimeNanos = com.google.devtools.build.lib.profiler.Profiler.instance().nanoTimeMaybe()
                        result =
                            remoteExecutionService.executeRemotely(action, useCachedResult.get(), reporter)
                    }
                    // In case of replies from server contains metadata, but none of them has EXECUTING
                    // status.
                    // It's already late at this stage, but we should at least report once.
                    reporter.reportExecutingIfNot()

                    if (result.cacheHit()
                        && (!result.success()
                                || result.maybeGetMissingMandatoryOutput(action).isPresent())
                    ) {
                        // Instead of failing in downloadAndFinalizeSpawnResult, retry with forced execution.
                        useCachedResult.set(false)
                        val status: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                            com.google.rpc.Status.newBuilder()
                                .setCode(com.google.rpc.Code.NOT_FOUND_VALUE)
                                .addDetails(Any.pack(RetryInfo.getDefaultInstance()))
                                .build()
                        throw StatusProto.toStatusRuntimeException(status)
                    }

                    maybePrintExecutionMessages(context, result.getMessage(), result.success())

                    profileAccounting(clampTimeNanos, result.getExecutionMetadata())
                    spawnMetricsAccounting(spawnMetrics, result.getExecutionMetadata())

                    prof.profile(ProfilerTask.REMOTE_DOWNLOAD, "download server logs").use { c ->
                        maybeDownloadServerLogs(action, result.getResponse())
                    }
                    try {
                        return@execute downloadAndFinalizeSpawnResult(
                            action,
                            result,
                            result.cacheHit(),
                            this.name,
                            spawn,
                            totalTime,
                            java.util.function.Supplier { action.getNetworkTime().getDuration() },
                            spawnMetrics
                        )
                    } catch (e: BulkTransferException) {
                        if (e.allCausedByCacheNotFoundException()) {
                            // No cache hit, so if we retry this execution, we must no longer accept
                            // cached results, it must be reexecuted
                            useCachedResult.set(false)
                        }
                        throw e
                    }
                })
        } catch (e: CredentialHelperException) {
            throw com.google.devtools.build.lib.remote.util.Utils.createExecExceptionForCredentialHelperException(e)
        } catch (e: IOException) {
            return execLocallyAndUploadOrFail(action, spawn, context, uploadLocalResults, e)
        }
    }

    @Throws(ExecException::class, IOException::class, java.lang.InterruptedException::class)
    private fun downloadAndFinalizeSpawnResult(
        action: RemoteAction,
        result: RemoteActionResult,
        cacheHit: Boolean,
        cacheName: String?,
        spawn: Spawn,
        totalTime: com.google.common.base.Stopwatch,
        networkTime: java.util.function.Supplier<java.time.Duration>,
        spawnMetrics: SpawnMetrics.Builder
    ): SpawnResult? {
        val networkTimeStart: java.time.Duration = networkTime.get()
        val fetchTime: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()

        val inMemoryOutput: InMemoryOutput?
        com.google.devtools.build.lib.profiler.Profiler.instance()
            .profile(ProfilerTask.REMOTE_DOWNLOAD, "download outputs").use { c ->
                inMemoryOutput = remoteExecutionService.downloadOutputs(action, result)
            }
        fetchTime.stop()
        totalTime.stop()
        val networkTimeEnd: java.time.Duration = networkTime.get()
        // subtract network time consumed here to ensure wall clock during fetch is not double
        // counted, and metrics time computation does not exceed total time
        return com.google.devtools.build.lib.remote.util.Utils.createSpawnResult(
            digestUtil,
            action.getActionKey(),
            result.getExitCode(),
            cacheHit,
            cacheName,
            inMemoryOutput,
            result.getExecutionMetadata().getExecutionStartTimestamp(),
            result.getExecutionMetadata().getExecutionCompletedTimestamp(),
            spawnMetrics
                .setFetchTimeInMs(
                    fetchTime.elapsed().minus(networkTimeEnd.minus(networkTimeStart)).toMillis().toInt()
                )
                .setTotalTimeInMs(totalTime.elapsed().toMillis().toInt())
                .setNetworkTimeInMs(networkTimeEnd.toMillis().toInt())
                .build(),
            spawn.getMnemonic()
        )
    }

    public override fun canExec(spawn: Spawn?): Boolean {
        return remoteExecutionService.mayBeExecutedRemotely(spawn)
    }

    public override fun handlesCaching(): Boolean {
        return true
    }

    private fun maybePrintExecutionMessages(
        context: SpawnExecutionContext, message: String, success: Boolean
    ) {
        val outErr: FileOutErr = context.fileOutErr
        val printMessage =
            remoteOptions.getRemotePrintExecutionMessages().shouldPrintMessages(success)
                    && !message.isEmpty()
        if (printMessage) {
            outErr.printErr("Remote server execution message: " + message + "\n")
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun maybeDownloadServerLogs(action: RemoteAction, resp: ExecuteResponse) {
        try {
            val serverLogs: ServerLogs = remoteExecutionService.maybeDownloadServerLogs(action, resp, logDir)
            if (serverLogs.logCount > 0 && verboseFailures) {
                report(
                    com.google.devtools.build.lib.events.Event.info(
                        "Remote server log of failing action:\n   "
                                + (if (serverLogs.logCount > 1) serverLogs.directory else serverLogs.lastLogPath)
                    )
                )
            }
        } catch (e: IOException) {
            reportOnce(com.google.devtools.build.lib.events.Event.warn("Failed downloading server logs from the remote cache."))
        }
    }

    @Throws(ExecException::class, java.lang.InterruptedException::class, IOException::class)
    private fun execLocallyAndUploadOrFail(
        action: RemoteAction?,
        spawn: Spawn?,
        context: SpawnExecutionContext,
        uploadLocalResults: Boolean,
        cause: IOException
    ): SpawnResult {
        // Regardless of cause, if we are interrupted, we should stop without displaying a user-visible
        // failure/stack trace.
        if (java.lang.Thread.interrupted()) {
            throw java.lang.InterruptedException()
        }
        // If the failure is caused by eviction of inputs to the current action that are only available
        // remotely, try to regenerate the lost inputs.
        if (cause is BulkTransferException) {
            cause.getLostArtifacts(context.inputMetadataProvider::getInput).throwIfNotEmpty()
        }
        if (remoteOptions.getRemoteLocalFallback() && !RemoteRetrierUtils.causedByExecTimeout(cause)) {
            return execLocallyAndUpload(action, spawn, context, uploadLocalResults)
        }
        return handleError(action, cause, context)
    }

    @Throws(ExecException::class, java.lang.InterruptedException::class, IOException::class)
    private fun handleError(
        action: RemoteAction, exception: IOException, context: SpawnExecutionContext
    ): SpawnResult {
        if (exception is RemoteExecutionCapabilitiesException) {
            throw com.google.devtools.build.lib.remote.util.Utils.createExecExceptionFromRemoteExecutionCapabilitiesException(
                exception
            )
        }
        if (exception.getCause() is ExecutionStatusException) {
            var result: RemoteActionResult? = null
            if (e.getResponse() != null) {
                val resp: ExecuteResponse? = e.getResponse()
                maybeDownloadServerLogs(action, resp)
                if (resp.hasResult()) {
                    result = RemoteActionResult.Companion.createFromResponse(resp)
                    try {
                        remoteExecutionService.downloadOutputs(action, result)
                    } catch (bulkTransferEx: BulkTransferException) {
                        exception.addSuppressed(bulkTransferEx)
                    }
                }
            }
            if (e.isExecutionTimeout()) {
                maybePrintExecutionMessages(context, e.getResponse().getMessage(),  /* success= */false)
                val resultBuilder: SpawnResult.Builder =
                    Builder()
                        .setRunnerName(this.name)
                        .setStatus(Status.TIMEOUT)
                        .setExitCode(SpawnResult.POSIX_TIMEOUT_EXIT_CODE)
                        .setFailureDetail(
                            FailureDetail.newBuilder()
                                .setMessage("remote spawn timed out")
                                .setSpawn(
                                    FailureDetails.Spawn.newBuilder()
                                        .setCode(FailureDetails.Spawn.Code.TIMEOUT)
                                )
                                .build()
                        )
                if (result != null) {
                    resultBuilder
                        .setWallTimeInMs(
                            java.time.Duration.between(
                                com.google.devtools.build.lib.remote.util.Utils.timestampToInstant(
                                    result.getExecutionMetadata().getExecutionStartTimestamp()
                                ),
                                com.google.devtools.build.lib.remote.util.Utils.timestampToInstant(
                                    result.getExecutionMetadata().getExecutionCompletedTimestamp()
                                )
                            )
                                .toMillis().toInt()
                        )
                        .setStartTime(
                            com.google.devtools.build.lib.remote.util.Utils.timestampToInstant(
                                result.getExecutionMetadata().getExecutionStartTimestamp()
                            )
                        )
                }
                return resultBuilder.build()
            }
        }
        val status: Status?
        val detailedCode: FailureDetails.Spawn.Code?
        val catastrophe: Boolean
        if (RemoteRetrierUtils.causedByStatus(exception, io.grpc.Status.Code.UNAVAILABLE)) {
            status = Status.EXECUTION_FAILED_CATASTROPHICALLY
            detailedCode = FailureDetails.Spawn.Code.EXECUTION_FAILED
            catastrophe = true
        } else if (BulkTransferException.Companion.allCausedByCacheNotFoundException(exception)) {
            // At this point, cache evictions that affect uploaded inputs have already been handled.
            // Cache evictions that affect the outputs of the current actions have also been retried with
            // a request that disallows reusing cached results. This means that there is no point in
            // retrying the entire build.
            status = Status.REMOTE_CACHE_FAILED
            detailedCode = FailureDetails.Spawn.Code.REMOTE_CACHE_FAILED
            catastrophe = false
        } else {
            status = Status.EXECUTION_FAILED
            detailedCode = FailureDetails.Spawn.Code.EXECUTION_FAILED
            catastrophe = false
        }

        var errorMessage: String? =
            com.google.devtools.build.lib.remote.util.Utils.grpcAwareErrorMessage(exception, verboseFailures)

        if (exception.getCause() is ExecutionStatusException) {
            if (e.getResponse() != null) {
                if (!e.getResponse().getMessage().isEmpty()) {
                    errorMessage += "\n" + e.getResponse().getMessage()
                }
            }
        }

        return Builder()
            .setRunnerName(this.name)
            .setStatus(status)
            .setExitCode(ExitCode.REMOTE_ERROR.getNumericExitCode())
            .setFailureMessage(errorMessage)
            .setFailureDetail(
                FailureDetail.newBuilder()
                    .setMessage("remote spawn failed: " + errorMessage)
                    .setSpawn(
                        FailureDetails.Spawn.newBuilder()
                            .setCode(detailedCode)
                            .setCatastrophic(catastrophe)
                    )
                    .build()
            )
            .build()
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(ExecException::class, IOException::class, java.lang.InterruptedException::class)
    fun execLocallyAndUpload(
        action: RemoteAction?,
        spawn: Spawn?,
        context: SpawnExecutionContext,
        uploadLocalResults: Boolean
    ): SpawnResult {
        val result: SpawnResult = execLocally(spawn, context)
        if (action != null && uploadLocalResults
            && result.status() == Status.SUCCESS
            && result.exitCode() === 0
        ) {
            remoteExecutionService.uploadOutputs(
                action, result, java.lang.Runnable {}, remoteOptions.getGuardAgainstConcurrentChanges()
            )
        }
        return result
    }

    private fun reportOnce(evt: com.google.devtools.build.lib.events.Event?) {
        if (warningReported.compareAndSet(false, true)) {
            report(evt)
        }
    }

    private fun report(evt: com.google.devtools.build.lib.events.Event?) {
        if (cmdlineReporter != null) {
            cmdlineReporter.handle(evt)
        }
    }

    companion object {
        private val SPAWN_CHECKING_CACHE_EVENT: SpawnCheckingCacheEvent? = SpawnCheckingCacheEvent.create("remote")

        private val SPAWN_SCHEDULING_EVENT: SpawnSchedulingEvent? = SpawnSchedulingEvent.create("remote")

        private val SPAWN_EXECUTING_EVENT: SpawnExecutingEvent? = SpawnExecutingEvent.create("remote")

        private fun profileAccounting(
            clampTimeNanos: Long, executedActionMetadata: ExecutedActionMetadata
        ) {
            val converter: com.google.devtools.build.lib.clock.BlazeClock.MillisSinceEpochToNanosConverter =
                com.google.devtools.build.lib.clock.BlazeClock.createMillisSinceEpochToNanosConverter()

            logProfileTask(
                converter,
                executedActionMetadata.getQueuedTimestamp(),
                executedActionMetadata.getWorkerStartTimestamp(),
                clampTimeNanos,
                ProfilerTask.REMOTE_QUEUE,
                "queue"
            )
            logProfileTask(
                converter,
                executedActionMetadata.getWorkerStartTimestamp(),
                executedActionMetadata.getInputFetchStartTimestamp(),
                clampTimeNanos,
                ProfilerTask.REMOTE_SETUP,
                "pre-fetch"
            )
            logProfileTask(
                converter,
                executedActionMetadata.getInputFetchStartTimestamp(),
                executedActionMetadata.getInputFetchCompletedTimestamp(),
                clampTimeNanos,
                ProfilerTask.FETCH,
                "fetch"
            )
            logProfileTask(
                converter,
                executedActionMetadata.getInputFetchCompletedTimestamp(),
                executedActionMetadata.getExecutionStartTimestamp(),
                clampTimeNanos,
                ProfilerTask.REMOTE_SETUP,
                "pre-execute"
            )
            logProfileTask(
                converter,
                executedActionMetadata.getExecutionStartTimestamp(),
                executedActionMetadata.getExecutionCompletedTimestamp(),
                clampTimeNanos,
                ProfilerTask.REMOTE_PROCESS_TIME,
                "execute"
            )
            logProfileTask(
                converter,
                executedActionMetadata.getExecutionCompletedTimestamp(),
                executedActionMetadata.getOutputUploadStartTimestamp(),
                clampTimeNanos,
                ProfilerTask.REMOTE_SETUP,
                "pre-upload"
            )
            logProfileTask(
                converter,
                executedActionMetadata.getOutputUploadStartTimestamp(),
                executedActionMetadata.getOutputUploadCompletedTimestamp(),
                clampTimeNanos,
                ProfilerTask.UPLOAD_TIME,
                "upload"
            )
        }

        private fun logProfileTask(
            converter: com.google.devtools.build.lib.clock.BlazeClock.MillisSinceEpochToNanosConverter,
            start: Timestamp?,
            end: Timestamp?,
            clampTimeNanos: Long,
            type: ProfilerTask?,
            description: String?
        ) {
            // If the remote execution request is deduped against an earlier request for the same action,
            // the start and end times may predate the start of the execution on our side. To avoid
            // confusion, clamp them so that they nest inside the parent profile span.
            var startTimeNanos: Long = converter.toNanos(Timestamps.toMillis(start))
            val endTimeNanos: Long = converter.toNanos(Timestamps.toMillis(end))
            if (endTimeNanos <= clampTimeNanos) {
                // Span lies entirely outside the parent.
                return
            }
            startTimeNanos = java.lang.Math.max(startTimeNanos, clampTimeNanos)
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .logSimpleTask(startTimeNanos, endTimeNanos, type, description)
        }

        /** conversion utility for protobuf Timestamp difference to java.time.Duration  */
        private fun between(from: Timestamp?, to: Timestamp?): java.time.Duration? {
            return java.time.Duration.ofNanos(Durations.toNanos(Timestamps.between(from, to)))
        }

        @com.google.common.annotations.VisibleForTesting
        fun spawnMetricsAccounting(
            spawnMetrics: SpawnMetrics.Builder, executionMetadata: ExecutedActionMetadata
        ) {
            // Expect that a non-empty worker indicates that all fields are populated.
            // If the bounded sides of these checkpoints are default timestamps, i.e. unset,
            // the phase durations can be extremely large. Unset pairs, or a fully unset
            // collection of timestamps, will result in zeroed durations, and no metrics
            // contributions for a phase or phases.
            if (!executionMetadata.getWorker().isEmpty()) {
                // Accumulate queueTime from any previous attempts
                val remoteQueueTime: java.time.Duration? =
                    between(
                        executionMetadata.getQueuedTimestamp(), executionMetadata.getWorkerStartTimestamp()
                    )
                spawnMetrics.addQueueTime(remoteQueueTime)
                // setup time does not include failed attempts
                val setupTime: java.time.Duration? =
                    between(
                        executionMetadata.getWorkerStartTimestamp(),
                        executionMetadata.getExecutionStartTimestamp()
                    )
                spawnMetrics.setSetupTime(setupTime)
                // execution time is unspecified for failures
                val executionWallTime: java.time.Duration? =
                    between(
                        executionMetadata.getExecutionStartTimestamp(),
                        executionMetadata.getExecutionCompletedTimestamp()
                    )
                spawnMetrics.setExecutionWallTime(executionWallTime)
                // remoteProcessOutputs time is unspecified for failures
                val remoteProcessOutputsTime: java.time.Duration? =
                    between(
                        executionMetadata.getOutputUploadStartTimestamp(),
                        executionMetadata.getOutputUploadCompletedTimestamp()
                    )
                spawnMetrics.setProcessOutputsTime(remoteProcessOutputsTime)
            }
        }

        @Throws(ExecException::class, java.lang.InterruptedException::class, IOException::class)
        private fun execLocally(spawn: Spawn?, context: SpawnExecutionContext): SpawnResult {
            val localFallbackRegistry: RemoteLocalFallbackRegistry? =
                context.getContext(RemoteLocalFallbackRegistry::class.java)
            com.google.common.base.Preconditions.checkNotNull<Any?>(
                localFallbackRegistry,
                "Expected a RemoteLocalFallbackRegistry to be registered"
            )
            val remoteLocalFallbackStrategy: AbstractSpawnStrategy? =
                localFallbackRegistry.getRemoteLocalFallbackStrategy(spawn)
            com.google.common.base.Preconditions.checkNotNull<Any?>(
                remoteLocalFallbackStrategy,
                "A remote local fallback strategy must be set if using remote fallback."
            )
            return remoteLocalFallbackStrategy.getSpawnRunner().exec(spawn, context)
        }

        private fun createExecuteRetrier(
            options: RemoteOptions, retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService?
        ): RemoteRetrier {
            return ExecuteRetrier(
                options.getRemoteMaxRetryAttempts(),
                retryService,
                CircuitBreakerFactory.createCircuitBreaker(options)
            )
        }
    }
}
