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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.analysis.constraints.ConstraintConstants.getOsFromConstraintsOrHost

/**
 * A layer between spawn execution and remote execution exposing primitive operations for remote
 * cache and execution with spawn specific types.
 */
class RemoteExecutionService(
    reporter: com.google.devtools.build.lib.events.Reporter,
    verboseFailures: Boolean,
    execRoot: com.google.devtools.build.lib.vfs.Path,
    remotePathResolver: RemotePathResolver?,
    buildRequestId: String?,
    commandId: String?,
    workspaceName: String?,
    digestUtil: DigestUtil,
    remoteOptions: RemoteOptions,
    executionOptions: ExecutionOptions,
    combinedCache: CombinedCache?,
    remoteExecutor: RemoteExecutionClient?,
    tempPathGenerator: TempPathGenerator,
    captureCorruptedOutputsDir: com.google.devtools.build.lib.vfs.Path?,
    remoteOutputChecker: RemoteOutputChecker?,
    outputService: OutputService?,
    knownMissingCasDigests: MutableSet<Digest?>
) {
    fun <String, String> comparing()

    private val reporter: com.google.devtools.build.lib.events.Reporter
    private val verboseFailures: Boolean
    private val execRoot: com.google.devtools.build.lib.vfs.Path

    /**
     * Do not use directly, instead use the per-spawn resolver created in [ ][.buildRemoteAction].
     */
    private val baseRemotePathResolver: RemotePathResolver?

    private val buildRequestId: String?
    private val commandId: String?
    private val digestUtil: DigestUtil
    private val merkleTreeComputer: MerkleTreeComputer
    private val remoteOptions: RemoteOptions
    private val executionOptions: ExecutionOptions
    private val combinedCache: CombinedCache?
    private val remoteExecutor: RemoteExecutionClient?
    private val tempPathGenerator: TempPathGenerator
    private val captureCorruptedOutputsDir: com.google.devtools.build.lib.vfs.Path?
    private val reportedErrors: MutableSet<String?> = HashSet<String?>()

    private val backgroundTaskExecutor: com.google.common.util.concurrent.ListeningExecutorService =
        com.google.common.util.concurrent.MoreExecutors.listeningDecorator(
            Executors.newThreadPerTaskExecutor(
                java.lang.Thread.ofVirtual().name("remote-execution-bg-", 0).factory()
            )
        )

    private val shutdown: AtomicBoolean = AtomicBoolean(false)
    private val buildInterrupted: AtomicBoolean = AtomicBoolean(false)

    private val remoteOutputChecker: RemoteOutputChecker?
    private val outputService: OutputService?

    private val scrubber: Scrubber?
    private val knownMissingCasDigests: MutableSet<Digest?>

    private var useOutputPaths: Boolean? = null

    private fun buildCommand(
        useOutputPaths: Boolean,
        outputs: MutableCollection<out ActionInput>,
        arguments: MutableList<String?>,
        env: com.google.common.collect.ImmutableMap<String?, String?>,
        platform: Platform?,
        remotePathResolver: RemotePathResolver,
        spawnScrubber: SpawnScrubber?,
        executionPlatform: PlatformInfo?
    ): Command {
        val command: Command.Builder = Command.newBuilder()
        if (useOutputPaths) {
            val outputPaths: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
            for (output in outputs) {
                val pathString: String? =
                    StringEncoding.internalToUnicode(remotePathResolver.localPathToOutputPath(output))
                outputPaths.add(pathString)
            }
            outputPaths.sort(PROTO_STRING_COMPARATOR)
            command.addAllOutputPaths(outputPaths)
        } else {
            val outputFiles: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
            val outputDirectories: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
            for (output in outputs) {
                val pathString: String? =
                    StringEncoding.internalToUnicode(remotePathResolver.localPathToOutputPath(output))
                if (output.isDirectory()) {
                    outputDirectories.add(pathString)
                } else {
                    outputFiles.add(pathString)
                }
            }
            outputFiles.sort(PROTO_STRING_COMPARATOR)
            outputDirectories.sort(PROTO_STRING_COMPARATOR)
            command.addAllOutputFiles(outputFiles).addAllOutputDirectories(outputDirectories)
        }

        if (platform != null) {
            command.setPlatform(platform)
        }
        var first = true
        for (arg in arguments) {
            var arg = arg
            if (spawnScrubber != null) {
                arg = spawnScrubber.transformArgument(arg)
            }
            if (first && executionPlatform != null) {
                first = false
                val executionOs: com.google.devtools.build.lib.util.OS? = getOsFromConstraintsOrHost(executionPlatform)
                arg = OsPathPolicy.of(executionOs).postProcessPathStringForExecution(arg)
            }
            command.addArguments(StringEncoding.internalToUnicode(arg))
        }
        // Sorting the environment pairs by variable name.
        val variables: TreeSet<String?> = TreeSet<String?>(env.keySet())
        for (`var` in variables) {
            command
                .addEnvironmentVariablesBuilder()
                .setName(StringEncoding.internalToUnicode(`var`))
                .setValue(StringEncoding.internalToUnicode(env.get(`var`)))
        }

        return command
            .setWorkingDirectory(
                StringEncoding.internalToUnicode(remotePathResolver.getWorkingDirectory().getPathString())
            )
            .build()
    }

    private fun useRemoteCache(): Boolean {
        return combinedCache != null && combinedCache.hasRemoteCache()
    }

    private fun useDiskCache(): Boolean {
        return combinedCache != null && combinedCache.hasDiskCache()
    }

    fun getReadCachePolicy(spawn: Spawn?): CachePolicy? {
        if (combinedCache == null) {
            return CachePolicy.NO_CACHE
        }

        val allowRemoteCache =
            useRemoteCache()
                    && remoteOptions.getRemoteAcceptCached()
                    && Spawns.mayBeCachedRemotely(spawn)
        val allowDiskCache = useDiskCache() && Spawns.mayBeCached(spawn)

        return CachePolicy.Companion.create(allowRemoteCache, allowDiskCache)
    }

    fun getWriteCachePolicy(spawn: Spawn): CachePolicy? {
        if (combinedCache == null) {
            return CachePolicy.NO_CACHE
        }

        val allowRemoteCache =
            useRemoteCache()
                    && com.google.devtools.build.lib.remote.util.Utils.shouldUploadLocalResultsToRemoteCache(
                remoteOptions,
                spawn.getExecutionInfo()
            )
                    && combinedCache.remoteActionCacheSupportsUpdate()
        val allowDiskCache = useDiskCache() && Spawns.mayBeCached(spawn)

        return CachePolicy.Companion.create(allowRemoteCache, allowDiskCache)
    }

    /** Returns `true` if the spawn may be executed remotely.  */
    fun mayBeExecutedRemotely(spawn: Spawn?): Boolean {
        return combinedCache is RemoteExecutionCache
                && remoteExecutor != null && Spawns.mayBeExecutedRemotely(spawn)
                && !isScrubbedSpawn(spawn, scrubber)
    }

    /**
     * Semaphore for limiting the concurrent number of Merkle tree input roots we compute and keep in
     * memory.
     * 
     * 
     * When --jobs is set to a high value to let the remote execution service runs many actions in
     * parallel, there is no point in letting the local system compute Merkle trees of input roots
     * with the same amount of parallelism. Not only does this make Bazel feel sluggish and slow to
     * respond to being interrupted, it causes it to exhaust memory.
     * 
     * 
     * As there is no point in letting Merkle tree input root computation use a higher concurrency
     * than the number of CPUs in the system, use a semaphore to limit the concurrency of
     * buildRemoteAction().
     */
    private val remoteActionBuildingSemaphore: Semaphore =
        Semaphore(java.lang.Runtime.getRuntime().availableProcessors(), true)

    @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
    private fun getToolSignature(spawn: Spawn, context: SpawnExecutionContext): ToolSignature? {
        return if (remoteOptions.getMarkToolInputs()
            && Spawns.supportsWorkers(spawn)
            && !spawn.getToolFiles().isEmpty()
        )
            computePersistentWorkerSignature(spawn, context)
        else
            null
    }

    @Throws(java.lang.InterruptedException::class)
    private fun maybeAcquireRemoteActionBuildingSemaphore(task: ProfilerTask?) {
        if (!remoteOptions.getThrottleRemoteActionBuilding()) {
            return
        }

        com.google.devtools.build.lib.profiler.Profiler.instance().profile(task, "acquiring semaphore").use { c ->
            remoteActionBuildingSemaphore.acquire()
        }
    }

    private fun maybeReleaseRemoteActionBuildingSemaphore() {
        if (!remoteOptions.getThrottleRemoteActionBuilding()) {
            return
        }

        remoteActionBuildingSemaphore.release()
    }

    private fun useOutputPaths(): Boolean {
        if (this.useOutputPaths == null) {
            initUseOutputPaths()
        }
        return this.useOutputPaths!!
    }

    @kotlin.jvm.Synchronized
    private fun initUseOutputPaths() {
        // If this has already been initialized, return
        if (this.useOutputPaths != null) {
            return
        }
        var serverHighestVersion: ApiVersion? = null
        try {
            // If both Remote Executor and Remote Cache are configured,
            // use the highest version supported by both.

            var executorSupportStatus: ServerSupportedStatus? = null
            if (remoteExecutor != null) {
                val serverCapabilities: ServerCapabilities? = remoteExecutor.getServerCapabilities()
                if (serverCapabilities != null) {
                    executorSupportStatus =
                        ClientApiVersion.Companion.current.checkServerSupportedVersions(serverCapabilities)
                }
            }

            var cacheSupportStatus: ServerSupportedStatus? = null
            if (combinedCache != null) {
                val serverCapabilities: ServerCapabilities? = combinedCache.getRemoteServerCapabilities()
                if (serverCapabilities != null) {
                    cacheSupportStatus =
                        ClientApiVersion.Companion.current.checkServerSupportedVersions(serverCapabilities)
                }
            }

            var executorHighestVersion: ApiVersion? = null
            if (executorSupportStatus != null && executorSupportStatus.isSupported()) {
                executorHighestVersion = executorSupportStatus.getHighestSupportedVersion()
            }

            var cacheHighestVersion: ApiVersion? = null
            if (cacheSupportStatus != null && cacheSupportStatus.isSupported()) {
                cacheHighestVersion = cacheSupportStatus.getHighestSupportedVersion()
            }

            if (executorHighestVersion != null && cacheHighestVersion != null) {
                serverHighestVersion = Collections.min<ApiVersion?>(
                    com.google.common.collect.ImmutableList.of<ApiVersion?>(
                        executorHighestVersion,
                        cacheHighestVersion
                    )
                )
            } else if (executorHighestVersion != null) {
                serverHighestVersion = executorHighestVersion
            } else if (cacheHighestVersion != null) {
                serverHighestVersion = cacheHighestVersion
            }
        } catch (e: IOException) {
            // Intentionally ignored.
        }
        this.useOutputPaths =
            serverHighestVersion == null || serverHighestVersion.compareTo(ApiVersion.Companion.twoPointOne) >= 0
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
    fun buildRemoteAction(spawn: Spawn, context: SpawnExecutionContext): RemoteAction {
        return buildRemoteAction(
            spawn,
            context,
            if (remoteOptions.getRemoteDiscardMerkleTrees())
                BlobPolicy.DISCARD
            else
                BlobPolicy.KEEP_AND_REUPLOAD
        )
    }

    /** Creates a new [RemoteAction] instance from spawn.  */
    @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
    fun buildRemoteAction(
        spawn: Spawn, context: SpawnExecutionContext, blobPolicy: BlobPolicy?
    ): RemoteAction {
        maybeAcquireRemoteActionBuildingSemaphore(ProfilerTask.REMOTE_SETUP)
        try {
            // Create a remote path resolver that is aware of the spawn's path mapper, which rewrites
            // the paths of the inputs and outputs as well as paths appearing in the command line for
            // execution. This is necessary to ensure that artifacts are correctly emitted into and staged
            // from the unmapped location locally.
            val remotePathResolver: RemotePathResolver =
                RemotePathResolver.Companion.createMapped(baseRemotePathResolver, execRoot, spawn.getPathMapper())
            val toolSignature = getToolSignature(spawn, context)
            val merkleTree: MerkleTree
            try {
                merkleTree =
                    merkleTreeComputer.buildForSpawn(
                        spawn,
                        if (toolSignature != null) toolSignature.toolInputs else com.google.common.collect.ImmutableSet.of<PathFragment?>(),
                        scrubber,
                        context,
                        remotePathResolver,
                        blobPolicy
                    )
            } catch (e: CredentialHelperException) {
                throw com.google.devtools.build.lib.remote.util.Utils.createExecExceptionForCredentialHelperException(e)
            }

            // Get the remote platform properties.
            val platform: Platform?
            val additionalPropertiesBuilder: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
                com.google.common.collect.ImmutableMap.builder<String?, String?>()
            if (toolSignature != null) {
                additionalPropertiesBuilder.put(
                    PlatformProperties.PERSISTENT_WORKER_KEY, toolSignature.key
                )
            }
            if (spawn.getExecutionInfo().containsKey(ExecutionRequirements.REQUIRES_WORKER_PROTOCOL)) {
                additionalPropertiesBuilder.put(
                    PlatformProperties.PERSISTENT_WORKER_PROTOCOL,
                    spawn.getExecutionInfo().get(ExecutionRequirements.REQUIRES_WORKER_PROTOCOL)
                )
            }
            platform =
                PlatformUtils.getPlatformProto(spawn, remoteOptions, additionalPropertiesBuilder.build())

            val spawnScrubber: SpawnScrubber? = if (scrubber != null) scrubber.forSpawn(spawn) else null
            val command: Command =
                buildCommand(
                    useOutputPaths(),
                    spawn.getOutputFiles(),
                    spawn.getArguments(),
                    spawn.getEnvironment(),
                    platform,
                    remotePathResolver,
                    spawnScrubber,
                    spawn.getExecutionPlatform()
                )
            val commandHash: Digest? = digestUtil.compute(command)
            val action: Action? =
                com.google.devtools.build.lib.remote.util.Utils.buildAction(
                    commandHash,
                    merkleTree.digest(),
                    platform,
                    context.timeout,
                    Spawns.mayBeCachedRemotely(spawn),
                    buildSalt(spawn, spawnScrubber)
                )

            val actionKey: ActionKey = digestUtil.computeActionKey(action)

            val metadata: RequestMetadata? =
                TracingMetadataUtils.buildMetadata(
                    buildRequestId, commandId, actionKey.digest.getHash(), spawn.getResourceOwner()
                )
            val remoteActionExecutionContext: RemoteActionExecutionContext =
                RemoteActionExecutionContext.Companion.create(
                    spawn, context, metadata, getWriteCachePolicy(spawn), getReadCachePolicy(spawn)
                )
            return RemoteAction(
                spawn,
                context,
                remoteActionExecutionContext,
                remotePathResolver,
                merkleTree,
                commandHash,
                command,
                action,
                actionKey
            )
        } finally {
            maybeReleaseRemoteActionBuildingSemaphore()
        }
    }

    @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
    private fun computePersistentWorkerSignature(spawn: Spawn?, context: SpawnExecutionContext): ToolSignature {
        val workerParser: WorkerParser =
            WorkerParser(
                execRoot,
                com.google.devtools.common.options.Options.getDefaults<WorkerOptions?>(WorkerOptions::class.java),
                LocalEnvProvider.NOOP,
                null
            )
        val workerKey: WorkerKey = workerParser.compute(spawn, context).getWorkerKey()
        val fingerprint: Fingerprint = Fingerprint()
        // getWorkerFilesCombinedHash always uses SHA-256, so the hash is always 32 bytes.
        fingerprint.addBytes(workerKey.getWorkerFilesCombinedHash().asBytes())
        fingerprint.addStrings(workerKey.getArgs())
        fingerprint.addStringMap(workerKey.getEnv())
        return ToolSignature(
            fingerprint.hexDigestAndReset(), workerKey.getWorkerFilesWithDigests().keySet()
        )
    }

    /** A value class representing the result of remotely executed [RemoteAction].  */
    class RemoteActionResult(
        actionResult: ActionResult,
        executeResponse: ExecuteResponse?,
        cacheName: String?
    ) {
        private val actionResult: ActionResult
        private val executeResponse: ExecuteResponse?
        private val cacheName: String?
        private var metadata: ActionResultMetadata? = null

        init {
            this.actionResult = actionResult
            this.executeResponse = executeResponse
            this.cacheName = cacheName
        }

        val exitCode: Int
            /** Returns the exit code of remote executed action.  */
            get() = actionResult.getExitCode()

        val outputFiles: MutableList<OutputFile>
            get() = actionResult.getOutputFilesList()

        val outputFileSymlinks: MutableList<OutputSymlink>
            get() = actionResult.getOutputFileSymlinksList()

        val outputDirectories: MutableList<OutputDirectory>
            get() = actionResult.getOutputDirectoriesList()

        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun getOrParseActionResultMetadata(
            combinedCache: CombinedCache?,
            digestUtil: DigestUtil,
            context: RemoteActionExecutionContext?,
            remotePathResolver: RemotePathResolver
        ): ActionResultMetadata {
            if (metadata == null) {
                com.google.devtools.build.lib.profiler.Profiler.instance().profile("Remote.parseActionResultMetadata")
                    .use { c ->
                        metadata =
                            parseActionResultMetadata(
                                combinedCache, digestUtil, context, actionResult, remotePathResolver
                            )
                    }
            }
            return metadata!!
        }

        val outputDirectorySymlinks: MutableList<OutputSymlink>
            get() = actionResult.getOutputDirectorySymlinksList()

        val outputSymlinks: MutableList<OutputSymlink>
            get() = actionResult.getOutputSymlinksList()

        val message: String?
            /**
             * Returns the freeform informational message with details on the execution of the action that
             * may be displayed to the user upon failure or when requested explicitly.
             */
            get() = if (executeResponse != null) executeResponse.getMessage() else ""

        val executionMetadata: ExecutedActionMetadata
            /** Returns the details of the execution that originally produced this result.  */
            get() = actionResult.getExecutionMetadata()

        /** Returns whether the action is executed successfully.  */
        fun success(): Boolean {
            if (executeResponse != null) {
                if (executeResponse.getStatus().getCode() !== io.grpc.Status.Code.OK.value()) {
                    return false
                }
            }

            return actionResult.getExitCode() === 0
        }

        /**
         * Returns an output that is mandatory for the given spawn but missing from this result, if any.
         */
        fun maybeGetMissingMandatoryOutput(action: RemoteAction): java.util.Optional<out ActionInput?> {
            val outputFiles: Iterable<Any?> = com.google.common.collect.Iterables.transform<OutputFile?, Any?>(
                this.outputFiles, OutputFile::getPath
            )
            val outputDirPaths: Iterable<Any?> = com.google.common.collect.Iterables.transform<OutputDirectory?, Any?>(
                this.outputDirectories, OutputDirectory::getPath
            )
            val outputSymlinkPaths: Iterable<Any?> =
                com.google.common.collect.Iterables.transform<OutputSymlink?, Any?>(
                    com.google.common.collect.Iterables.concat<OutputSymlink?>(
                        this.outputSymlinks, this.outputFileSymlinks, this.outputDirectorySymlinks
                    ),
                    OutputSymlink::getPath
                )
            val allOutputPaths: com.google.common.collect.ImmutableSet<String?> =
                com.google.common.collect.ImmutableSet.copyOf<E?>(
                    com.google.common.collect.Iterables.transform<F?, T?>(
                        com.google.common.collect.Iterables.< T > concat < T ? > (outputFiles,
                        outputDirPaths,
                        outputSymlinkPaths
                    ),
                    com.google.common.base.Function { s: F? -> StringEncoding.unicodeToInternal(s) }))
            // Check that all mandatory outputs are created.
            val spawn: Spawn = action.getSpawn()
            val remotePathResolver: RemotePathResolver = action.getRemotePathResolver()
            return spawn.getOutputFiles().stream()
                .filter(spawn::isMandatoryOutput)
                .filter(
                    { output -> !allOutputPaths.contains(remotePathResolver.localPathToOutputPath(output)) })
                .findFirst()
        }

        /** Returns `true` if this result is from a cache.  */
        fun cacheHit(): Boolean {
            if (executeResponse == null) {
                return true
            }

            return executeResponse.getCachedResult()
        }

        /** Returns cache name (disk/remote) when `cacheHit()` or `null` when not  */
        fun cacheName(): String? {
            return cacheName
        }

        val response: ExecuteResponse?
            /**
             * Returns the underlying [ExecuteResponse] or `null` if this result is from a
             * cache.
             */
            get() = executeResponse

        override fun equals(`object`: Any?): Boolean {
            if (`object` !is RemoteActionResult) {
                return false
            }

            return actionResult == `object`.actionResult
                    && executeResponse == `object`.executeResponse
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(actionResult, executeResponse)
        }

        companion object {
            /** Creates a new [RemoteActionResult] instance from a cached result.  */
            fun createFromCache(cachedActionResult: CachedActionResult): RemoteActionResult {
                com.google.common.base.Preconditions.checkArgument(
                    cachedActionResult != null,
                    "cachedActionResult is null"
                )
                return RemoteActionResult(
                    cachedActionResult.actionResult, null, cachedActionResult.cacheName
                )
            }

            /** Creates a new [RemoteActionResult] instance from a execute response.  */
            fun createFromResponse(response: ExecuteResponse): RemoteActionResult {
                checkArgument(response.hasResult(), "response doesn't have result")
                return RemoteActionResult(response.getResult(), response,  /* cacheName */null)
            }
        }
    }

    /** Lookup the remote cache for the given [RemoteAction]. `null` if not found.  */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun lookupCache(action: RemoteAction): RemoteActionResult? {
        com.google.common.base.Preconditions.checkState(
            action.getRemoteActionExecutionContext().getReadCachePolicy().allowAnyCache(),
            "spawn doesn't accept cached result"
        )

        var inlineOutputFiles: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>()
        val inMemoryOutputPath: PathFragment? = getInMemoryOutputPath(action.getSpawn())
        if (inMemoryOutputPath != null) {
            inlineOutputFiles =
                com.google.common.collect.ImmutableSet.of<String?>(
                    action.getRemotePathResolver().localPathToOutputPath(inMemoryOutputPath)
                )
        }

        val cachedActionResult: CachedActionResult? =
            combinedCache.downloadActionResult(
                action.getRemoteActionExecutionContext(),
                action.getActionKey(),  /* inlineOutErr= */
                false,
                inlineOutputFiles
            )

        if (cachedActionResult == null) {
            return null
        }

        val result = RemoteActionResult.Companion.createFromCache(cachedActionResult)

        // We only add digests to `knownMissingCasDigests` when LostInputsEvent occurs which will cause
        // the build to abort and rewind, so there is no data race here. This allows us to avoid the
        // check until cache eviction happens.
        if (!knownMissingCasDigests.isEmpty()) {
            val metadata =
                result.getOrParseActionResultMetadata(
                    combinedCache,
                    digestUtil,
                    action.getRemoteActionExecutionContext(),
                    action.getRemotePathResolver()
                )

            // If we already know digests referenced by this AC is missing from remote cache, ignore it so
            // that we can fall back to execution. This could happen when the remote cache is an HTTP
            // cache, or doesn't implement AC integrity check.
            //
            // See https://github.com/bazelbuild/bazel/issues/18696.
            if (updateKnownMissingCasDigests(knownMissingCasDigests, metadata)) {
                return null
            }
        }

        return result
    }

    private fun downloadFile(
        context: RemoteActionExecutionContext?,
        progressStatusListener: ProgressStatusListener?,
        file: FileMetadata,
        tmpPath: com.google.devtools.build.lib.vfs.Path?,
        remotePathResolver: RemotePathResolver
    ): com.google.common.util.concurrent.ListenableFuture<FileMetadata?> {
        com.google.common.base.Preconditions.checkNotNull<CombinedCache?>(combinedCache, "combinedCache can't be null")

        try {
            val future: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
                combinedCache.downloadFile(
                    context,
                    StringEncoding.internalToUnicode(remotePathResolver.localPathToOutputPath(file.path())),
                    remotePathResolver.localPathToExecPath(file.path().asFragment()),
                    tmpPath,
                    file.digest(),
                    DownloadProgressReporter(
                        progressStatusListener,
                        StringEncoding.internalToUnicode(remotePathResolver.localPathToOutputPath(file.path())),
                        file.digest().getSizeBytes()
                    )
                )
            return com.google.common.util.concurrent.Futures.transform<java.lang.Void?, FileMetadata?>(
                future,
                com.google.common.base.Function { d: java.lang.Void? -> file },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        } catch (e: IOException) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<FileMetadata?>(e)
        }
    }

    private fun captureCorruptedOutputs(e: java.lang.Exception?) {
        if (captureCorruptedOutputsDir != null) {
            if (e is BulkTransferException) {
                for (suppressed in e.getSuppressed()) {
                    if (suppressed is OutputDigestMismatchException) {
                        // Capture corrupted outputs
                        try {
                            val outputPath: String? = (suppressed as OutputDigestMismatchException).getOutputPath()
                            val localPath: com.google.devtools.build.lib.vfs.Path? =
                                (suppressed as OutputDigestMismatchException).getLocalPath()
                            val dst: com.google.devtools.build.lib.vfs.Path =
                                captureCorruptedOutputsDir.getRelative(outputPath)
                            dst.createDirectoryAndParents()

                            // Make sure dst is still under captureCorruptedOutputsDir, otherwise
                            // IllegalArgumentException will be thrown.
                            dst.relativeTo(captureCorruptedOutputsDir)

                            com.google.devtools.build.lib.vfs.FileSystemUtils.copyFile(localPath, dst)
                        } catch (ee: java.lang.Exception) {
                            e.addSuppressed(ee)
                        }
                    }
                }
            }
        }
    }

    @Throws(ExecException::class)
    private fun deletePartialDownloadedOutputs(
        realToTmpPath: MutableMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path>,
        tmpOutErr: FileOutErr,
        e: java.lang.Exception
    ) {
        try {
            // Delete any (partially) downloaded output files.
            for (tmpPath in realToTmpPath.values()) {
                tmpPath.delete()
            }

            tmpOutErr.clearOut()
            tmpOutErr.clearErr()
        } catch (ioEx: IOException) {
            ioEx.addSuppressed(e)

            // If deleting of output files failed, we abort the build with a decent error message as
            // any subsequent local execution failure would likely be incomprehensible.
            val execEx: ExecException =
                EnvironmentalExecException(
                    ioEx,
                    CombinedCache.Companion.createFailureDetail(
                        "Failed to delete output files after incomplete download",
                        RemoteExecution.Code.INCOMPLETE_OUTPUT_DOWNLOAD_CLEANUP_FAILURE
                    )
                )
            execEx.addSuppressed(e)
            throw execEx
        }
    }

    /** Moves the locally created outputs from their temporary location to their declared location.  */
    @Throws(IOException::class)
    private fun moveOutputsToFinalLocation(
        localOutputs: Iterable<com.google.devtools.build.lib.vfs.Path>,
        realToTmpPath: MutableMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path>
    ) {
        // Move the output files from their temporary name to the actual output file name. Executable
        // bit is ignored since the file permission will be changed to 0555 after execution.
        for (realPath in localOutputs) {
            val tmpPath: com.google.devtools.build.lib.vfs.Path =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path>(
                    realToTmpPath.get(realPath)
                )
            realPath.getParentDirectory().createDirectoryAndParents()
            com.google.devtools.build.lib.vfs.FileSystemUtils.moveFile(tmpPath, realPath)
        }
    }

    @Throws(IOException::class)
    private fun createSymlinks(symlinks: Iterable<SymlinkMetadata>) {
        for (symlink in symlinks) {
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(
                symlink.path().getParentDirectory(),
                "Failed creating directory and parents for %s",
                symlink.path()
            )
                .createDirectoryAndParents()
            // If a directory output is being materialized as a symlink, creating the symlink fails as we
            // must first delete the preexisting empty directory. Since this is rare (and in the future
            // BwoB may no longer eagerly create these directories), we don't delete the directory
            // beforehand.
            try {
                symlink.path().createSymbolicLink(symlink.target())
            } catch (e: IOException) {
                if (!symlink.path().isDirectory(Symlinks.NOFOLLOW)) {
                    throw e
                }
                // Retry after deleting the directory.
                symlink.path().delete()
                symlink.path().createSymbolicLink(symlink.target())
            }
        }
    }

    /** In-memory representation of action result metadata.  */
    internal class ActionResultMetadata private constructor(
        files: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.vfs.Path?, FileMetadata?>,
        symlinks: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.vfs.Path?, SymlinkMetadata?>,
        directories: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.vfs.Path?, DirectoryMetadata?>
    ) {
        internal class SymlinkMetadata private constructor(
            path: com.google.devtools.build.lib.vfs.Path?,
            target: PathFragment?
        ) {
            private val path: com.google.devtools.build.lib.vfs.Path?
            private val target: PathFragment?

            init {
                this.path = path
                this.target = target
            }

            fun path(): com.google.devtools.build.lib.vfs.Path? {
                return path
            }

            fun target(): PathFragment? {
                return target
            }
        }

        class FileMetadata private constructor(
            path: com.google.devtools.build.lib.vfs.Path,
            digest: Digest,
            isExecutable: Boolean,
            contents: ByteString
        ) {
            private val path: com.google.devtools.build.lib.vfs.Path
            private val digest: Digest
            val isExecutable: Boolean
            private val contents: ByteString

            init {
                this.path = path
                this.digest = digest
                this.isExecutable = isExecutable
                this.contents = contents
            }

            fun path(): com.google.devtools.build.lib.vfs.Path {
                return path
            }

            fun digest(): Digest {
                return digest
            }

            fun content(): ByteString {
                return contents
            }
        }

        internal class DirectoryMetadata private constructor(
            files: com.google.common.collect.ImmutableList<FileMetadata>?,
            symlinks: com.google.common.collect.ImmutableList<SymlinkMetadata>?
        ) {
            private val files: com.google.common.collect.ImmutableList<FileMetadata>?
            private val symlinks: com.google.common.collect.ImmutableList<SymlinkMetadata>?

            init {
                this.files = files
                this.symlinks = symlinks
            }

            fun files(): com.google.common.collect.ImmutableList<FileMetadata>? {
                return files
            }

            fun symlinks(): com.google.common.collect.ImmutableList<SymlinkMetadata>? {
                return symlinks
            }
        }

        private val files: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.vfs.Path?, FileMetadata?>
        private val symlinks: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.vfs.Path?, SymlinkMetadata?>
        private val directories: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.vfs.Path?, DirectoryMetadata?>

        init {
            this.files = files
            this.symlinks = symlinks
            this.directories = directories
        }

        fun file(path: com.google.devtools.build.lib.vfs.Path?): FileMetadata? {
            return files.get(path)
        }

        fun directory(path: com.google.devtools.build.lib.vfs.Path?): DirectoryMetadata? {
            return directories.get(path)
        }

        fun files(): MutableCollection<FileMetadata> {
            return files.values()
        }

        fun directories(): com.google.common.collect.ImmutableSet<MutableMap.MutableEntry<com.google.devtools.build.lib.vfs.Path?, DirectoryMetadata?>> {
            return directories.entrySet()
        }

        fun symlinks(): MutableCollection<SymlinkMetadata?> {
            return symlinks.values()
        }
    }

    init {
        this.reporter = reporter
        this.verboseFailures = verboseFailures
        this.execRoot = execRoot
        this.baseRemotePathResolver = remotePathResolver
        this.buildRequestId = buildRequestId
        this.commandId = commandId
        this.digestUtil = digestUtil
        this.remoteOptions = remoteOptions
        this.executionOptions = executionOptions
        this.combinedCache = combinedCache
        this.remoteExecutor = remoteExecutor
        this.merkleTreeComputer =
            MerkleTreeComputer(
                digestUtil,  // Merkle trees only need to be uploaded for actions that are executed remotely.
                if (combinedCache is RemoteExecutionCache)
                    combinedCache
                else
                    null,
                buildRequestId,
                commandId,
                workspaceName
            )

        this.scrubber = remoteOptions.getScrubber()

        this.tempPathGenerator = tempPathGenerator
        this.captureCorruptedOutputsDir = captureCorruptedOutputsDir

        this.remoteOutputChecker = remoteOutputChecker
        this.outputService = outputService
        this.knownMissingCasDigests = knownMissingCasDigests
    }

    /**
     * Downloads the outputs of a remotely executed action and injects their metadata.
     * 
     * 
     * For a successful action, the [RemoteOutputChecker] is consulted to determine which of
     * the outputs should be downloaded. For a failed action, all outputs are downloaded. The action
     * stdout and stderr, as well as the in-memory output when present, are always downloaded even in
     * the success case. Any outputs that are not downloaded have their metadata injected into the
     * [RemoteActionFileSystem].
     * 
     * 
     * In case of download failure, all of the already downloaded outputs are deleted.
     * 
     * @return The in-memory output if the spawn had one, otherwise null.
     */
    @Throws(java.lang.InterruptedException::class, IOException::class, ExecException::class)
    fun downloadOutputs(action: RemoteAction, result: RemoteActionResult): InMemoryOutput? {
        com.google.common.base.Preconditions.checkState(!shutdown.get(), "shutdown")
        CombinedCache > com.google.common.base.Preconditions.checkNotNull<CombinedCache?>(
            combinedCache,
            "combinedCache can't be null"
        )

        val remoteActionFileSystem: RemoteActionFileSystem? = null
        val hasBazelOutputService = outputService is BazelOutputService
        if (!hasBazelOutputService) {
            val actionFileSystem: com.google.devtools.build.lib.vfs.FileSystem? =
                action.getSpawnExecutionContext().actionFileSystem
            com.google.common.base.Preconditions.checkState(
                actionFileSystem is RemoteActionFileSystem,
                "expected the ActionFileSystem to be a RemoteActionFileSystem"
            )
            remoteActionFileSystem = actionFileSystem as RemoteActionFileSystem
        }

        val progressStatusListener: ProgressStatusListener = action.getSpawnExecutionContext()::report
        val context: RemoteActionExecutionContext = action.getRemoteActionExecutionContext()
        if (result.executeResponse != null) {
            // Always read from remote cache for just remotely executed action.
            context = context.withReadCachePolicy(context.getReadCachePolicy().addRemoteCache())
        }

        val metadata =
            result.getOrParseActionResultMetadata(
                combinedCache, digestUtil, context, action.getRemotePathResolver()
            )

        // The expiration time for remote cache entries.
        val expirationTime: Instant? = Instant.now().plus(remoteOptions.getRemoteCacheTtl())

        val inMemoryOutput: ActionInput? = null
        val inMemoryOutputData: AtomicReference<ByteString?> = AtomicReference<ByteString?>(null)
        val inMemoryOutputPath: PathFragment? = getInMemoryOutputPath(action.getSpawn())
        if (inMemoryOutputPath != null) {
            for (output in action.getSpawn().getOutputFiles()) {
                if (output.getExecPath().equals(inMemoryOutputPath)) {
                    inMemoryOutput = output
                    break
                }
            }
        }

        // Collect the set of files to download.
        val downloadsBuilder: com.google.common.collect.ImmutableList.Builder<com.google.common.util.concurrent.ListenableFuture<FileMetadata?>?> =
            com.google.common.collect.ImmutableList.builder<com.google.common.util.concurrent.ListenableFuture<FileMetadata?>?>()

        // Download into temporary paths, then move everything at the end.
        // This avoids holding the output lock while downloading, which would prevent the local branch
        // from completing sooner under the dynamic execution strategy.
        val realToTmpPath: MutableMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path> =
            HashMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path>()

        for (file in metadata.files()) {
            if (realToTmpPath.containsKey(file.path)) {
                continue
            }

            val execPath: PathFragment = file.path.relativeTo(execRoot)
            val isInMemoryOutputFile = inMemoryOutput != null && execPath == inMemoryOutputPath
            if (!isInMemoryOutputFile && shouldDownload(result, execPath,  /* treeRootExecPath= */null)) {
                val tmpPath: com.google.devtools.build.lib.vfs.Path = tempPathGenerator.generateTempPath()
                realToTmpPath.put(file.path, tmpPath)
                downloadsBuilder.add(
                    downloadFile(
                        context, progressStatusListener, file, tmpPath, action.getRemotePathResolver()
                    )
                )
            } else {
                if (hasBazelOutputService) {
                    downloadsBuilder.add(com.google.common.util.concurrent.Futures.immediateFuture<FileMetadata?>(file))
                } else {
                    com.google.common.base.Preconditions.checkNotNull<RemoteActionFileSystem?>(remoteActionFileSystem)
                        .injectRemoteFile(
                            file.path().asFragment(),
                            DigestUtil.toBinaryDigest(file.digest()),
                            file.digest().getSizeBytes(),
                            expirationTime
                        )
                }

                if (isInMemoryOutputFile) {
                    // Download into memory only; do not write to disk.
                    remoteOutputChecker.skipDownload(inMemoryOutputPath)
                    if (file.contents.isEmpty()) {
                        // As the contents field doesn't have presence information, we use the digest size to
                        // distinguish between an empty file and one that wasn't inlined.
                        if (file.digest.getSizeBytes() === 0) {
                            inMemoryOutputData.set(ByteString.EMPTY)
                        } else {
                            downloadsBuilder.add(
                                TODO("Cannot convert element")
                            )<
                                    FileMetadata> com . google . common . util . concurrent . Futures . transform < ByteArray ?, kotlin.Any?>(
                            combinedCache.downloadBlob(
                                context,
                                inMemoryOutputPath.getPathString(),
                                inMemoryOutputPath,
                                file.digest()
                            ),
                            com.google.common.base.Function { data: ByteArray? ->
                                inMemoryOutputData.set(ByteString.copyFrom(data))
                                null
                            },
                            com.google.common.util.concurrent.MoreExecutors.directExecutor())
                        }
                    } else {
                        inMemoryOutputData.set(file.contents)
                    }
                }
            }
        }

        for (entry in metadata.directories()) {
            val treeRootExecPath: PathFragment = entry.getKey().relativeTo(execRoot)

            for (file in entry.getValue().files()) {
                if (realToTmpPath.containsKey(file.path)) {
                    continue
                }

                if (shouldDownload(result, file.path.relativeTo(execRoot), treeRootExecPath)) {
                    val tmpPath: com.google.devtools.build.lib.vfs.Path = tempPathGenerator.generateTempPath()
                    realToTmpPath.put(file.path, tmpPath)
                    downloadsBuilder.add(
                        downloadFile(
                            context, progressStatusListener, file, tmpPath, action.getRemotePathResolver()
                        )
                    )
                } else if (hasBazelOutputService) {
                    downloadsBuilder.add(com.google.common.util.concurrent.Futures.immediateFuture<FileMetadata?>(file))
                } else {
                    com.google.common.base.Preconditions.checkNotNull<RemoteActionFileSystem?>(remoteActionFileSystem)
                        .injectRemoteFile(
                            file.path().asFragment(),
                            DigestUtil.toBinaryDigest(file.digest()),
                            file.digest().getSizeBytes(),
                            expirationTime
                        )
                }
            }
        }

        val outErr: FileOutErr = action.getSpawnExecutionContext().fileOutErr

        // Always download the action stdout/stderr.
        val tmpOutErr: FileOutErr = outErr.childOutErr()
        val outErrDownloads: MutableList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>> =
            combinedCache.downloadOutErr(context, result.actionResult, tmpOutErr)
        for (future in outErrDownloads) {
            downloadsBuilder.add(
                com.google.common.util.concurrent.Futures.transform<java.lang.Void?, FileMetadata?>(
                    future,
                    com.google.common.base.Function { v: java.lang.Void? -> null },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
            )
        }

        val downloads: com.google.common.collect.ImmutableList<com.google.common.util.concurrent.ListenableFuture<FileMetadata?>?> =
            downloadsBuilder.build()
        try {
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("Remote.download").use { c ->
                com.google.devtools.build.lib.remote.util.Utils.waitForBulkTransfer(downloads)
            }
        } catch (e: java.lang.Exception) {
            // TODO(bazel-team): Consider adding better case-by-case exception handling instead of just
            // rethrowing
            captureCorruptedOutputs(e)
            deletePartialDownloadedOutputs(realToTmpPath, tmpOutErr, e)
            throw e
        }

        FileOutErr.dump(tmpOutErr, outErr)

        // Ensure that we are the only ones writing to the output files when using the dynamic spawn
        // strategy.
        action
            .getSpawnExecutionContext()
            .lockOutputFiles(result.exitCode, result.message, tmpOutErr)
        // Will these be properly garbage-collected if the above throws an exception?
        tmpOutErr.clearOut()
        tmpOutErr.clearErr()

        val finishedDownloads: MutableList<FileMetadata?> = java.util.ArrayList<FileMetadata?>(downloads.size())
        for (finishedDownload in downloads) {
            val outputFile: FileMetadata? =
                com.google.devtools.build.lib.remote.util.Utils.getFromFuture<FileMetadata?>(finishedDownload)
            if (outputFile != null) {
                finishedDownloads.add(outputFile)
            }
        }

        if (hasBazelOutputService) {
            // TODO(chiwang): Stage directories directly
            (outputService as BazelOutputService).stageArtifacts(finishedDownloads)
        } else {
            moveOutputsToFinalLocation(
                com.google.common.collect.Iterables.transform<FileMetadata?, com.google.devtools.build.lib.vfs.Path?>(
                    finishedDownloads,
                    com.google.common.base.Function { obj: FileMetadata? -> obj.path() }), realToTmpPath
            )
        }

        val symlinksInDirectories: MutableList<SymlinkMetadata?> = java.util.ArrayList<SymlinkMetadata?>()
        for (entry in metadata.directories()) {
            for (symlink in entry.getValue().symlinks()) {
                symlinksInDirectories.add(symlink)
            }
        }

        val symlinks: Iterable<SymlinkMetadata> =
            com.google.common.collect.Iterables.concat<SymlinkMetadata?>(metadata.symlinks(), symlinksInDirectories)

        // Create the symbolic links after all downloads are finished, because dangling symlinks
        // might not be supported on all platforms.
        createSymlinks(symlinks)

        if (result.success()) {
            // Check that all mandatory outputs are created.
            val missingMandatoryOutput: java.util.Optional<out ActionInput?> =
                result.maybeGetMissingMandatoryOutput(action)
            if (missingMandatoryOutput.isPresent()) {
                throw IOException(
                    "mandatory output %s was not created"
                        .formatted(prettyPrint(missingMandatoryOutput.get()))
                )
            }

            if (result.executeResponse != null && !knownMissingCasDigests.isEmpty()) {
                // A succeeded execution uploads outputs to CAS. Refresh our knowledge about missing
                // digests.
                val unused = updateKnownMissingCasDigests(knownMissingCasDigests, metadata)
            }

            // When downloading outputs from just remotely executed action, the action result comes from
            // Execution response which means, if disk cache is enabled, action result hasn't been
            // uploaded to it. Upload action result to disk cache here so next build could hit it.
            if (useDiskCache() && result.executeResponse != null) {
                com.google.devtools.build.lib.remote.util.Utils.getFromFuture<java.lang.Void?>(
                    combinedCache.uploadActionResult(
                        context.withWriteCachePolicy(CachePolicy.DISK_CACHE_ONLY),
                        action.getActionKey(),
                        result.actionResult
                    )
                )
            }
        }

        if (inMemoryOutput != null && inMemoryOutputData.get() != null) {
            return InMemoryOutput(inMemoryOutput, inMemoryOutputData.get())
        }

        return null
    }

    /** An ongoing local execution of a spawn.  */
    class LocalExecution private constructor(action: RemoteAction, onClose: java.lang.Runnable) : SilentCloseable {
        private val action: RemoteAction
        private val spawnResultFuture: com.google.common.util.concurrent.SettableFuture<SpawnResult?>
        private val onClose: java.lang.Runnable
        private val closeManually: AtomicBoolean = AtomicBoolean(false)
        private val spawnResultConsumers: Phaser = object : Phaser(1) {
            override fun onAdvance(phase: Int, registeredParties: Int): Boolean {
                // We only use a single phase.
                return true
            }
        }

        init {
            this.action = action
            this.spawnResultFuture = com.google.common.util.concurrent.SettableFuture.create<SpawnResult?>()
            this.onClose = onClose
        }

        /**
         * Attempts to register a thread waiting for the [.spawnResultFuture] to become available
         * and returns true if successful.
         * 
         * 
         * Every call to this method must be matched by a call to [.unregister] via
         * try-finally.
         * 
         * 
         * This always returns true for actions that do not modify their spawns' outputs after
         * execution.
         */
        fun registerForOutputReuse(): Boolean {
            // We only use a single phase.
            return spawnResultConsumers.register() == 0
        }

        /**
         * Unregisters a thread waiting for the [.spawnResultFuture], either after successful
         * reuse of the outputs or upon failure.
         */
        fun unregister() {
            spawnResultConsumers.arriveAndDeregister()
        }

        /**
         * Waits for all potential consumers of the [.spawnResultFuture] to be done with their
         * output reuse.
         */
        fun awaitAllOutputReuse() {
            spawnResultConsumers.arriveAndAwaitAdvance()
        }

        /**
         * Signals to all potential consumers of the [.spawnResultFuture] that this execution has
         * finished or been canceled and that the result will no longer be available.
         */
        override fun close() {
            if (!closeManually.get()) {
                doClose()
            }
        }

        /**
         * Returns a [Runnable] that will close this [LocalExecution] instance when called.
         * After this method is called, the [LocalExecution] instance will not be closed by the
         * [.close] method.
         */
        fun delayClose(): java.lang.Runnable {
            check(closeManually.compareAndSet(false, true)) { "delayClose has already been called" }
            return java.lang.Runnable { this.doClose() }
        }

        private fun doClose() {
            spawnResultFuture.cancel(true)
            onClose.run()
        }

        companion object {
            /**
             * Creates a new [LocalExecution] instance tracking the potential local execution of the
             * given [RemoteAction] if there is a chance that the same action will be executed by a
             * different Spawn.
             * 
             * 
             * This is only done for local (as in, non-remote) execution as remote executors are expected
             * to already have deduplication mechanisms for actions in place, perhaps even across different
             * builds and clients.
             */
            fun createIfDeduplicatable(action: RemoteAction, onClose: java.lang.Runnable): LocalExecution? {
                if (action.getSpawn().getPathMapper().isNoop()) {
                    return null
                }
                return LocalExecution(action, onClose)
            }
        }
    }

    /**
     * Makes the [SpawnResult] available to all parallel [Spawn]s for the same [ ] waiting for it or notifies them that the spawn failed.
     * 
     * @return Whether the spawn result should be uploaded to the cache.
     */
    fun commitResultAndDecideWhetherToUpload(
        result: SpawnResult, execution: LocalExecution?
    ): Boolean {
        if (result.status().equals(SpawnResult.Status.SUCCESS) && result.exitCode() === 0) {
            if (execution != null) {
                execution.spawnResultFuture.set(result)
            }
            return true
        } else {
            if (execution != null) {
                execution.spawnResultFuture.cancel(true)
            }
            return false
        }
    }

    /**
     * Reuses the outputs of a concurrent local execution of the same RemoteAction in a different
     * spawn.
     * 
     * 
     * Since each output file is generated by a unique action and actions generally take care to
     * run a unique spawn for each output file, this method is only useful with path mapping enabled,
     * which allows different spawns in a single build to have the same RemoteAction.ActionKey.
     * 
     * @return The [SpawnResult] of the previous execution if it was successful, otherwise null.
     */
    @Throws(java.lang.InterruptedException::class, IOException::class)
    fun waitForAndReuseOutputs(action: RemoteAction, previousExecution: LocalExecution): SpawnResult? {
        com.google.common.base.Preconditions.checkState(!shutdown.get(), "shutdown")

        val previousSpawnResult: SpawnResult?
        try {
            previousSpawnResult = previousExecution.spawnResultFuture.get()
        } catch (e: CancellationException) {
            if (e.getCause() != null) {
                com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                    e.getCause(),
                    java.lang.InterruptedException::class.java
                )
                com.google.common.base.Throwables.throwIfUnchecked(e.getCause())
            }
            // The spawn this action was deduplicated against failed due to an exception or
            // non-zero exit code. Since it isn't possible to transparently replay its failure for the
            // current spawn, we rerun the action instead.
            return null
        } catch (e: ExecutionException) {
            if (e.getCause() != null) {
                com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                    e.getCause(),
                    java.lang.InterruptedException::class.java
                )
                com.google.common.base.Throwables.throwIfUnchecked(e.getCause())
            }
            return null
        }

        com.google.common.base.Preconditions.checkArgument(
            action.getActionKey() == previousExecution.action.getActionKey()
        )

        val previousOutputs: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.vfs.Path?, ActionInput?> =
            previousExecution.action.getSpawn().getOutputFiles().stream()
                .collect(com.google.common.collect.ImmutableMap.toImmutableMap<T?, K?, V?>(java.util.function.Function { output: T? ->
                    execRoot.getRelative(
                        output.getExecPath()
                    )
                }, java.util.function.Function { o: T? -> o }))
        val realToTmpPath: MutableMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path> =
            HashMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path>()
        var inMemoryOutputContent: ByteString? = null
        var inMemoryOutputPath: String? = null
        val outputPathsList: MutableList<T?> =
            if (useOutputPaths())
                action.getCommand().getOutputPathsList()
            else
                java.util.stream.Stream.concat<T?>(
                    action.getCommand().getOutputFilesList().stream(),
                    action.getCommand().getOutputDirectoriesList().stream()
                )
                    .toList()
        try {
            for (output in outputPathsList) {
                val reencodedOutput: String? = StringEncoding.unicodeToInternal(output)
                val sourcePath: com.google.devtools.build.lib.vfs.Path =
                    previousExecution.action.getRemotePathResolver().outputPathToLocalPath(reencodedOutput)
                val outputArtifact: ActionInput? = previousOutputs.get(sourcePath)
                val targetPath: com.google.devtools.build.lib.vfs.Path =
                    action.getRemotePathResolver().outputPathToLocalPath(reencodedOutput)
                inMemoryOutputContent = previousSpawnResult.getInMemoryOutput(outputArtifact)
                if (inMemoryOutputContent != null) {
                    inMemoryOutputPath = targetPath.relativeTo(execRoot).getPathString()
                    continue
                }
                val tmpPath: com.google.devtools.build.lib.vfs.Path = tempPathGenerator.generateTempPath()
                tmpPath.getParentDirectory().createDirectoryAndParents()
                try {
                    if (outputArtifact.isDirectory()) {
                        tmpPath.createDirectory()
                        com.google.devtools.build.lib.vfs.FileSystemUtils.copyTreesBelow(sourcePath, tmpPath)
                    } else if (outputArtifact.isSymlink()) {
                        com.google.devtools.build.lib.vfs.FileSystemUtils.ensureSymbolicLink(
                            tmpPath,
                            sourcePath.readSymbolicLink()
                        )
                    } else {
                        com.google.devtools.build.lib.vfs.FileSystemUtils.copyFile(sourcePath, tmpPath)
                    }
                    realToTmpPath.put(targetPath, tmpPath)
                } catch (e: FileNotFoundException) {
                    // The spawn this action was deduplicated against failed to create an output file. If the
                    // output is mandatory, we cannot reuse the previous execution.
                    if (action.getSpawn().isMandatoryOutput(outputArtifact)) {
                        return null
                    }
                }
            }

            // TODO: FileOutErr is action-scoped, not spawn-scoped, but this is not a problem for the
            //  current use case of supporting deduplication of path mapped spawns:
            //  1. Starlark and C++ compilation actions always create a single spawn.
            //  2. Java compilation actions may run a fallback spawn, but reset the FileOutErr before
            //     running it.
            //  If this changes, we will need to introduce a spawn-scoped OutErr.
            FileOutErr.dump(
                previousExecution.action.getSpawnExecutionContext().fileOutErr,
                action.getSpawnExecutionContext().fileOutErr
            )

            action
                .getSpawnExecutionContext()
                .lockOutputFiles(
                    previousSpawnResult.exitCode(),
                    previousSpawnResult.getFailureMessage(),
                    action.getSpawnExecutionContext().fileOutErr
                )
            // All outputs are created locally.
            moveOutputsToFinalLocation(realToTmpPath.keySet(), realToTmpPath)
        } catch (e: java.lang.InterruptedException) {
            // Delete any copied output files.
            try {
                for (tmpPath in realToTmpPath.values()) {
                    tmpPath.delete()
                }
            } catch (ignored: IOException) {
                // Best effort, will be cleaned up at server restart.
            }
            throw e
        } catch (e: IOException) {
            try {
                for (tmpPath in realToTmpPath.values()) {
                    tmpPath.delete()
                }
            } catch (ignored: IOException) {
            }
            throw e
        }

        if (inMemoryOutputPath != null) {
            val finalInMemoryOutputPath: String? = inMemoryOutputPath
            val finalInMemoryOutputContent: ByteString? = inMemoryOutputContent
            return object : DelegateSpawnResult(previousSpawnResult) {
                public override fun getInMemoryOutput(output: ActionInput): ByteString? {
                    if (output.getExecPathString().equals(finalInMemoryOutputPath)) {
                        return finalInMemoryOutputContent
                    }
                    return null
                }
            }
        }

        return previousSpawnResult
    }

    private fun shouldDownload(
        result: RemoteActionResult, execPath: PathFragment?, treeRootExecPath: PathFragment?
    ): Boolean {
        if (outputService is BazelOutputService) {
            return false
        }

        // In case the action failed, download all outputs. It might be helpful for debugging and there
        // is no point in injecting output metadata of a failed action.
        if (result.exitCode != 0) {
            return true
        }
        return remoteOutputChecker.shouldDownloadOutput(execPath, treeRootExecPath)
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
    fun buildUploadManifest(action: RemoteAction, spawnResult: SpawnResult): UploadManifest {
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("build upload manifest").use { c ->
            val outputFiles: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.vfs.Path?> =
                com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.vfs.Path?>()
            // Check that all mandatory outputs are created.
            for (outputFile in action.getSpawn().getOutputFiles()) {
                val followSymlinks: Symlinks = if (outputFile.isSymlink()) Symlinks.NOFOLLOW else Symlinks.FOLLOW
                val localPath: com.google.devtools.build.lib.vfs.Path = execRoot.getRelative(outputFile.getExecPath())
                if (action.getSpawn().isMandatoryOutput(outputFile) && !localPath.exists(followSymlinks)) {
                    throw IOException(
                        "Expected output " + prettyPrint(outputFile) + " was not created locally."
                    )
                }
                outputFiles.add(localPath)
            }
            return UploadManifest.Companion.create(
                combinedCache.getRemoteCacheCapabilities(),
                digestUtil,
                action.getRemotePathResolver(),
                action.getActionKey(),
                action.getAction(),
                action.getCommand(),
                outputFiles.build(),
                action.getSpawnExecutionContext().fileOutErr,
                spawnResult.exitCode(),
                spawnResult.getStartTime(),
                spawnResult.getWallTimeInMs(),  /* preserveExecutableBit= */
                false
            )
        }
    }

    /** Upload outputs of a remote action which was executed locally to remote cache.  */
    @Throws(java.lang.InterruptedException::class, ExecException::class)
    fun uploadOutputs(
        action: RemoteAction,
        spawnResult: SpawnResult,
        onUploadComplete: java.lang.Runnable,
        concurrentChangesCheckLevel: ConcurrentChangesCheckLevel?
    ) {
        com.google.common.base.Preconditions.checkState(!shutdown.get(), "shutdown")
        com.google.common.base.Preconditions.checkState(
            action.getRemoteActionExecutionContext().getWriteCachePolicy().allowAnyCache(),
            "spawn shouldn't upload local result"
        )
        com.google.common.base.Preconditions.checkState(
            SpawnResult.Status.SUCCESS.equals(spawnResult.status()) && spawnResult.exitCode() === 0,
            "shouldn't upload outputs of failed local action"
        )

        try {
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("checkForConcurrentModifications")
                .use { c ->
                    checkForConcurrentModifications(action, concurrentChangesCheckLevel)
                }
        } catch (e: IOException) {
            report(
                com.google.devtools.build.lib.events.Event.warn(
                    java.lang.String.format(
                        "%s: Skipping uploading outputs because of concurrent modifications with"
                                + " --guard_against_concurrent_changes enabled: %s",
                        action.getSpawn().getTargetLabel(), e.getMessage()
                    )
                )
            )
            onUploadComplete.run()
            return
        }

        if (remoteOptions.getRemoteCacheAsync()
            && !action.getSpawn().getResourceOwner().mayModifySpawnOutputsAfterExecution()
        ) {
            val uploadDone: CountDownLatch = CountDownLatch(1)
            val future: com.google.common.util.concurrent.ListenableFuture<*> =
                backgroundTaskExecutor.submit(
                    java.lang.Runnable {
                        try {
                            doUploadOutputs(action, spawnResult, onUploadComplete)
                        } catch (e: ExecException) {
                            reportUploadError(e)
                        } catch (ignored: java.lang.InterruptedException) {
                            // ThreadPerTaskExecutor does not care about interrupt status.
                        } finally {
                            uploadDone.countDown()
                        }
                    })

            if (outputService is RemoteOutputService
                && outputService.getRewoundActionSynchronizer()
                        is RemoteRewoundActionSynchronizer
            ) {
                remoteRewoundActionSynchronizer.registerOutputUploadTask(
                    action.getRemoteActionExecutionContext().getSpawnOwner(),
                    com.google.devtools.build.lib.remote.RemoteRewoundActionSynchronizer.Cancellable {
                        future.cancel(true)
                        uploadDone.await()
                    })
            }
        } else {
            doUploadOutputs(action, spawnResult, onUploadComplete)
        }
    }

    @Throws(ExecException::class, java.lang.InterruptedException::class)
    private fun doUploadOutputs(
        action: RemoteAction, spawnResult: SpawnResult, onUploadComplete: java.lang.Runnable
    ) {
        try {
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .profile(ProfilerTask.UPLOAD_TIME, "upload outputs").use { c ->
                    val manifest: UploadManifest = buildUploadManifest(action, spawnResult)
                    val unused: ActionResult? =
                        manifest.upload(action.getRemoteActionExecutionContext(), combinedCache, reporter)
                }
        } catch (e: IOException) {
            reportUploadError(e)
        } finally {
            onUploadComplete.run()
        }
    }

    @Throws(IOException::class)
    private fun checkForConcurrentModifications(
        action: RemoteAction, level: ConcurrentChangesCheckLevel?
    ) {
        if (level == ConcurrentChangesCheckLevel.OFF) {
            return
        }

        // As this check runs after the action has been executed, we can reuse the input map if it
        // has already been created with willAccessRepeatedly = true, but do not need to force its
        // retention.
        for (input in action.getInputMap( /* willAccessRepeatedly= */false).values()) {
            // In lite mode, only check source artifacts in the main repository for modifications.
            // Non-source artifacts are made read-only after execution, and external repositories are
            // rarely modified, with local_repository being the notable exception.
            // TODO: Find a way to include repositories that are symlinks to source directories.
            // On Bazel itself, this reduces the number of wasModifiedSinceDigest calls by 99% compared to
            // the full check. By not checking output files, this mode also avoids spurious false
            // positives (see https://github.com/bazelbuild/bazel/issues/3360).
            if (level == ConcurrentChangesCheckLevel.LITE
                && !(input is Artifact
                        && input.isSourceArtifact()
                        && !input.getRoot().isExternal())
            ) {
                continue
            } else if (input is VirtualActionInput) {
                continue
            }
            val metadata: FileArtifactValue =
                action.getSpawnExecutionContext().inputMetadataProvider.getInputMetadata(input)
            val path: com.google.devtools.build.lib.vfs.Path? = execRoot.getRelative(input.getExecPath())
            if (metadata.wasModifiedSinceDigest(path)) {
                throw IOException(path.toString() + " was modified during execution")
            }
        }
    }

    private fun reportUploadError(error: Throwable?) {
        if (buildInterrupted.get()) {
            // If build interrupted, ignores all the errors
            return
        }

        val errorMessage = "Remote Cache: " + com.google.devtools.build.lib.remote.util.Utils.grpcAwareErrorMessage(
            error,
            verboseFailures
        )

        report(com.google.devtools.build.lib.events.Event.warn(errorMessage))
    }

    /**
     * Upload inputs of a remote action to remote cache if they are not presented already.
     * 
     * 
     * Must be called before calling [.executeRemotely].
     */
    @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
    fun uploadInputsIfNotPresent(action: RemoteAction, force: Boolean) {
        com.google.common.base.Preconditions.checkState(!shutdown.get(), "shutdown")
        com.google.common.base.Preconditions.checkState(
            mayBeExecutedRemotely(action.getSpawn()),
            "spawn can't be executed remotely"
        )

        val remoteExecutionCache: RemoteExecutionCache? = combinedCache as RemoteExecutionCache?
        // Upload the command and all the inputs into the remote cache.
        val additionalInputs: MutableMap<Digest?, Message?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<Digest?, Message?>(2)
        additionalInputs.put(action.getActionKey().digest, action.getAction())
        additionalInputs.put(action.getCommandHash(), action.getCommand())

        // As uploading depends on having the full input root in memory, limit
        // concurrency. This prevents memory exhaustion. We assume that
        // ensureInputsPresent() provides enough parallelism to saturate the
        // network connection.
        maybeAcquireRemoteActionBuildingSemaphore(ProfilerTask.UPLOAD_TIME)
        try {
            val merkleTree: Uploadable?
            if (action.getMerkleTree() is Uploadable && !force) {
                merkleTree = uploadable
            } else {
                // --experimental_remote_discard_merkle_trees was provided or the remote lost a shared
                // subtree uploaded previously. Recompute the tree - including all subtrees in the latter
                // case.
                val spawn: Spawn = action.getSpawn()
                val context: SpawnExecutionContext = action.getSpawnExecutionContext()
                val toolSignature = getToolSignature(spawn, context)
                merkleTree =
                    merkleTreeComputer.buildForSpawn(
                        spawn,
                        if (toolSignature != null) toolSignature.toolInputs else com.google.common.collect.ImmutableSet.of<PathFragment?>(),
                        scrubber,
                        context,
                        action.getRemotePathResolver(),
                        if (force)
                            BlobPolicy.KEEP_AND_REUPLOAD
                        else
                            BlobPolicy.KEEP
                    ) as Uploadable?
            }

            remoteExecutionCache.ensureInputsPresent(
                action
                    .getRemoteActionExecutionContext()
                    .withWriteCachePolicy(CachePolicy.REMOTE_CACHE_ONLY),  // Only upload to remote cache
                merkleTree,
                additionalInputs,
                force,
                action.getRemotePathResolver()
            )
        } finally {
            maybeReleaseRemoteActionBuildingSemaphore()
        }
    }

    /**
     * Executes the remote action remotely and returns the result.
     * 
     * @param acceptCachedResult tells remote execution server whether it should used cached result.
     * @param observer receives status updates during the execution.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun executeRemotely(
        action: RemoteAction, acceptCachedResult: Boolean, observer: OperationObserver?
    ): RemoteActionResult {
        com.google.common.base.Preconditions.checkState(!shutdown.get(), "shutdown")
        com.google.common.base.Preconditions.checkState(
            mayBeExecutedRemotely(action.getSpawn()),
            "spawn can't be executed remotely"
        )

        val requestBuilder: ExecuteRequest.Builder =
            ExecuteRequest.newBuilder()
                .setInstanceName(remoteOptions.getRemoteInstanceName())
                .setDigestFunction(digestUtil.getDigestFunction())
                .setActionDigest(action.getActionKey().digest)
                .setSkipCacheLookup(!acceptCachedResult)
        if (remoteOptions.getRemoteResultCachePriority() != 0) {
            requestBuilder
                .getResultsCachePolicyBuilder()
                .setPriority(remoteOptions.getRemoteResultCachePriority())
        }
        if (remoteOptions.getRemoteExecutionPriority() != 0) {
            requestBuilder
                .getExecutionPolicyBuilder()
                .setPriority(remoteOptions.getRemoteExecutionPriority())
        }
        val inMemoryOutputPath: PathFragment? = getInMemoryOutputPath(action.getSpawn())
        if (inMemoryOutputPath != null) {
            requestBuilder.addInlineOutputFiles(
                StringEncoding.internalToUnicode(
                    action.getRemotePathResolver().localPathToOutputPath(inMemoryOutputPath)
                )
            )
        }

        val request: ExecuteRequest? = requestBuilder.build()

        val reply: ExecuteResponse =
            remoteExecutor.executeRemotely(action.getRemoteActionExecutionContext(), request, observer)

        return RemoteActionResult.Companion.createFromResponse(reply)
    }

    /** A value classes representing downloaded server logs.  */
    class ServerLogs {
        var logCount: Int = 0
        var directory: com.google.devtools.build.lib.vfs.Path? = null
        var lastLogPath: com.google.devtools.build.lib.vfs.Path? = null
    }

    /** Downloads server logs from a remotely executed action if any.  */
    @Throws(java.lang.InterruptedException::class, IOException::class)
    fun maybeDownloadServerLogs(
        action: RemoteAction,
        resp: ExecuteResponse,
        logDir: com.google.devtools.build.lib.vfs.Path
    ): ServerLogs {
        com.google.common.base.Preconditions.checkState(!shutdown.get(), "shutdown")
        com.google.common.base.Preconditions.checkNotNull<CombinedCache?>(combinedCache, "combinedCache can't be null")
        val serverLogs = ServerLogs()
        serverLogs.directory = logDir.getRelative(action.getActionId())

        val actionResult: ActionResult = resp.getResult()
        if (resp.getServerLogsCount() > 0
            && (actionResult.getExitCode() !== 0 || resp.getStatus().getCode() !== io.grpc.Status.Code.OK.value())
        ) {
            for (e in resp.getServerLogsMap().entrySet()) {
                if (e.getValue().getHumanReadable()) {
                    serverLogs.lastLogPath = serverLogs.directory.getRelative(e.getKey())
                    serverLogs.logCount++
                    com.google.devtools.build.lib.remote.util.Utils.getFromFuture<java.lang.Void?>(
                        combinedCache.downloadFile(
                            action.getRemoteActionExecutionContext(),
                            serverLogs.lastLogPath,
                            e.getValue().getDigest()
                        )
                    )
                }
            }
        }

        return serverLogs
    }

    @com.google.common.eventbus.Subscribe
    fun onBuildInterrupted(event: BuildInterruptedEvent?) {
        buildInterrupted.set(true)
    }

    @com.google.common.eventbus.Subscribe
    fun onBuildComplete(event: BuildCompleteEvent) {
        if (event.getResult().getSuccess()) {
            // If build succeeded, clear knownMissingCasDigests in case there are missing digests from
            // other targets from previous builds which are not relevant anymore.
            knownMissingCasDigests.clear()
        }
    }

    @com.google.common.eventbus.Subscribe
    fun onLostInputs(event: LostInputsEvent) {
        for (digest in event.missingDigests) {
            knownMissingCasDigests.add(DigestUtil.fromString(digest))
        }
    }

    /** Shuts the service down.  */
    fun shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return
        }

        if (buildInterrupted.get()) {
            backgroundTaskExecutor.shutdownNow()
            if (combinedCache != null) {
                combinedCache.shutdownNow()
            }
            java.lang.Thread.currentThread().interrupt()
        }

        // Waits for all background tasks to finish and interrupts them if there is another interrupt.
        backgroundTaskExecutor.close()

        // Release the cache only after background tasks are done as they might be using it.
        if (combinedCache != null) {
            combinedCache.release()
        }

        if (remoteExecutor != null) {
            remoteExecutor.close()
        }
    }

    /**
     * Whether parameter files should be written locally, even when using remote execution or caching.
     */
    @Throws(IOException::class)
    fun maybeWriteParamFilesLocally(spawn: Spawn) {
        if (!executionOptions.shouldMaterializeParamFiles()) {
            return
        }
        for (actionInput in spawn.getInputFiles().toList()) {
            if (actionInput is CommandLines.ParamFileActionInput) {
                actionInput.atomicallyWriteRelativeTo(execRoot)
            }
        }
    }

    fun report(evt: com.google.devtools.build.lib.events.Event) {
        synchronized(this) {
            if (reportedErrors.contains(evt.getMessage())) {
                return
            }
            reportedErrors.add(evt.getMessage())
            reporter.handle(evt)
        }
    }

    /**
     * A simple value class combining a hash of the tool inputs (and their digests) as well as a set
     * of the relative paths of all tool inputs.
     */
    private class ToolSignature(private val key: String?, toolInputs: MutableSet<PathFragment?>?) {
        private val toolInputs: MutableSet<PathFragment?>?

        init {
            this.toolInputs = toolInputs
        }
    }

    companion object {
        private val PROTO_STRING_COMPARATOR: java.util.Comparator<String?>? = null
        private fun buildSalt(spawn: Spawn, spawnScrubber: SpawnScrubber?): ByteString? {
            val saltBuilder: CacheSalt.Builder =
                CacheSalt.newBuilder().setMayBeExecutedRemotely(Spawns.mayBeExecutedRemotely(spawn))

            val workspace: String? =
                spawn.getExecutionInfo().get(ExecutionRequirements.DIFFERENTIATE_WORKSPACE_CACHE)
            if (workspace != null) {
                saltBuilder.setWorkspace(workspace)
            }

            if (spawnScrubber != null) {
                saltBuilder.setScrubSalt(
                    CacheSalt.ScrubSalt.newBuilder().setSalt(spawnScrubber.getSalt()).build()
                )
            }

            return saltBuilder.build().toByteString()
        }

        /**
         * Returns the (exec root relative) path of a spawn output that should be made available via
         * [SpawnResult.getInMemoryOutput].
         */
        private fun getInMemoryOutputPath(spawn: Spawn): PathFragment? {
            val outputPath: String? =
                spawn.getExecutionInfo().get(ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS)
            if (outputPath != null) {
                return PathFragment.create(outputPath)
            }
            return null
        }

        /**
         * Removes digests referenced by `metadata` from `knownMissingCasDigests` and returns
         * whether any were removed
         */
        private fun updateKnownMissingCasDigests(
            knownMissingCasDigests: MutableSet<Digest?>, metadata: ActionResultMetadata
        ): Boolean {
            // Using `remove` below because we assume the missing blob will be uploaded afterwards.
            var result = false
            for (file in metadata.files()) {
                if (knownMissingCasDigests.remove(file.digest())) {
                    result = true
                }
            }
            for (entry in metadata.directories()) {
                for (file in entry.getValue().files()) {
                    if (knownMissingCasDigests.remove(file.digest())) {
                        result = true
                    }
                }
            }
            return result
        }

        private fun parseDirectory(
            parent: com.google.devtools.build.lib.vfs.Path,
            dir: Directory,
            childDirectoriesMap: MutableMap<Digest?, Directory?>
        ): DirectoryMetadata {
            val filesBuilder: com.google.common.collect.ImmutableList.Builder<FileMetadata?> =
                com.google.common.collect.ImmutableList.builder<FileMetadata?>()
            for (file in dir.getFilesList()) {
                filesBuilder.add(
                    FileMetadata(
                        parent.getRelative(StringEncoding.unicodeToInternal(file.getName())),
                        file.getDigest(),
                        file.getIsExecutable(),
                        ByteString.EMPTY
                    )
                )
            }

            val symlinksBuilder: com.google.common.collect.ImmutableList.Builder<SymlinkMetadata?> =
                com.google.common.collect.ImmutableList.builder<SymlinkMetadata?>()
            for (symlink in dir.getSymlinksList()) {
                symlinksBuilder.add(
                    SymlinkMetadata(
                        parent.getRelative(StringEncoding.unicodeToInternal(symlink.getName())),
                        PathFragment.create(StringEncoding.unicodeToInternal(symlink.getTarget()))
                    )
                )
            }

            for (directoryNode in dir.getDirectoriesList()) {
                val childPath: com.google.devtools.build.lib.vfs.Path =
                    parent.getRelative(StringEncoding.unicodeToInternal(directoryNode.getName()))
                val childDir: Directory =
                    com.google.common.base.Preconditions.checkNotNull<Directory>(childDirectoriesMap.get(directoryNode.getDigest()))
                val childMetadata: DirectoryMetadata = parseDirectory(childPath, childDir, childDirectoriesMap)
                filesBuilder.addAll(childMetadata.files())
                symlinksBuilder.addAll(childMetadata.symlinks())
            }

            return DirectoryMetadata(filesBuilder.build(), symlinksBuilder.build())
        }

        // The Tree message representing an empty directory.
        private val EMPTY_DIRECTORY: Tree = Tree.newBuilder().setRoot(Directory.getDefaultInstance()).build()

        init {
            // See logic in parseActionResultMetadata below.
            com.google.common.base.Preconditions.checkState(EMPTY_DIRECTORY.toByteString().size() === 2)
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun parseActionResultMetadata(
            combinedCache: CombinedCache?,
            digestUtil: DigestUtil,
            context: RemoteActionExecutionContext?,
            result: ActionResult,
            remotePathResolver: RemotePathResolver
        ): ActionResultMetadata {
            CombinedCache > com.google.common.base.Preconditions.checkNotNull<CombinedCache?>(
                combinedCache,
                "combinedCache can't be null"
            )

            val dirMetadataDownloads: MutableMap<com.google.devtools.build.lib.vfs.Path?, com.google.common.util.concurrent.ListenableFuture<Tree?>?> =
                com.google.common.collect.Maps.newHashMapWithExpectedSize<K?, V?>(result.getOutputDirectoriesCount())
            for (dir in result.getOutputDirectoriesList()) {
                val outputPath: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    dir.getPath()
                val localPath: com.google.devtools.build.lib.vfs.Path =
                    remotePathResolver.outputPathToLocalPath(StringEncoding.unicodeToInternal(outputPath))
                if (dir.getTreeDigest().getSizeBytes() === 2) {
                    // A valid Tree message contains at least a non-empty root field. The only way for a Tree
                    // message to have a size of 2 bytes is if the root field is the only non-empty field and
                    // the Directory message in the root field is empty, which corresponds to one byte for the
                    // LEN tag and field number and one byte for the zero-length varint. Since empty tree
                    // artifacts are relatively common (e.g., as the undeclared test output directory), we avoid
                    // downloading these messages here.
                    dirMetadataDownloads.put(
                        localPath, com.google.common.util.concurrent.Futures.immediateFuture<Tree?>(
                            EMPTY_DIRECTORY
                        )
                    )
                } else {
                    dirMetadataDownloads.put(
                        localPath,
                        com.google.common.util.concurrent.Futures.transformAsync<ByteArray?, Tree?>(
                            combinedCache.downloadBlob(
                                context,
                                outputPath,
                                remotePathResolver.localPathToExecPath(localPath.asFragment()),
                                dir.getTreeDigest()
                            ),
                            { treeBytes -> }
                            <V> com . google . common . util . concurrent . Futures . immediateFuture < V ? > (
                                    Tree.parseFrom(treeBytes, ExtensionRegistry.getEmptyRegistry())),
                            com.google.common.util.concurrent.MoreExecutors.directExecutor()))
                }
            }

            com.google.devtools.build.lib.remote.util.Utils.waitForBulkTransfer(dirMetadataDownloads.values())

            val directories: com.google.common.collect.ImmutableMap.Builder<com.google.devtools.build.lib.vfs.Path?, DirectoryMetadata?> =
                com.google.common.collect.ImmutableMap.builder<com.google.devtools.build.lib.vfs.Path?, DirectoryMetadata?>()
            for (metadataDownload in dirMetadataDownloads.entrySet()) {
                val path: com.google.devtools.build.lib.vfs.Path? = metadataDownload.getKey()
                val directoryTree: Tree =
                    com.google.devtools.build.lib.remote.util.Utils.getFromFuture<Tree>(metadataDownload.getValue())
                val childrenMap: MutableMap<Digest?, Directory?> = HashMap<Digest?, Directory?>()
                for (childDir in directoryTree.getChildrenList()) {
                    childrenMap.put(digestUtil.compute(childDir), childDir)
                }

                directories.put(path, parseDirectory(path, directoryTree.getRoot(), childrenMap))
            }

            val files: com.google.common.collect.ImmutableMap.Builder<com.google.devtools.build.lib.vfs.Path?, FileMetadata?> =
                com.google.common.collect.ImmutableMap.builder<com.google.devtools.build.lib.vfs.Path?, FileMetadata?>()
            for (outputFile in result.getOutputFilesList()) {
                val localPath: com.google.devtools.build.lib.vfs.Path =
                    remotePathResolver.outputPathToLocalPath(StringEncoding.unicodeToInternal(outputFile.getPath()))
                files.put(
                    localPath,
                    FileMetadata(
                        localPath,
                        outputFile.getDigest(),
                        outputFile.getIsExecutable(),
                        outputFile.getContents()
                    )
                )
            }

            val symlinkMap: HashMap<com.google.devtools.build.lib.vfs.Path?, SymlinkMetadata?> =
                HashMap<com.google.devtools.build.lib.vfs.Path?, SymlinkMetadata?>()
            val outputSymlinks: Iterable<out Any?> =
                com.google.common.collect.Iterables.concat(
                    result.getOutputFileSymlinksList(),
                    result.getOutputDirectorySymlinksList(),
                    result.getOutputSymlinksList()
                )
            for (symlink in outputSymlinks) {
                val localPath: com.google.devtools.build.lib.vfs.Path =
                    remotePathResolver.outputPathToLocalPath(StringEncoding.unicodeToInternal(symlink.getPath()))
                val target: PathFragment = PathFragment.create(StringEncoding.unicodeToInternal(symlink.getTarget()))
                val existingMetadata: SymlinkMetadata? = symlinkMap.get(localPath)
                if (existingMetadata != null) {
                    if (target != existingMetadata.target()) {
                        throw IOException(
                            java.lang.String.format(
                                "Symlink path collision: '%s' is mapped to both '%s' and '%s'. Action Result"
                                        + " should not contain multiple targets for the same symlink.",
                                localPath, existingMetadata.target(), target
                            )
                        )
                    }
                    continue
                }

                symlinkMap.put(localPath, SymlinkMetadata(localPath, target))
            }

            return ActionResultMetadata(
                files.buildOrThrow(),
                com.google.common.collect.ImmutableMap.copyOf<com.google.devtools.build.lib.vfs.Path?, SymlinkMetadata?>(
                    symlinkMap
                ),
                directories.buildOrThrow()
            )
        }

        private fun prettyPrint(actionInput: ActionInput): String {
            if (actionInput is Artifact) {
                return actionInput.prettyPrint()
            } else {
                return actionInput.getExecPathString()
            }
        }

        private fun isScrubbedSpawn(spawn: Spawn?, scrubber: Scrubber?): Boolean {
            return scrubber != null && scrubber.forSpawn(spawn) != null
        }
    }
}
