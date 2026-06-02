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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.Digest

/** RemoteModule provides distributed cache and remote execution for Bazel.  */
class RemoteModule : BlazeModule() {
    private val retryScheduler: com.google.common.util.concurrent.ListeningScheduledExecutorService =
        com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))

    private val knownMissingCasDigests: MutableSet<Digest?> =
        com.google.common.collect.Sets.newConcurrentHashSet<Digest?>()
    private var useRemoteRepoContentsCache = false

    private var outputBase: PathFragment? = null
    private var rpcLogFile: AsynchronousMessageOutputStream<LogEntry?>? = null
    private var executorService: ExecutorService? = null
    private var actionContextProvider: RemoteActionContextProvider? = null
    private var actionInputFetcher: RemoteActionInputFetcher? = null
    private var remoteOptions: RemoteOptions? = null
    private var env: CommandEnvironment? = null
    private var outputService: OutputService? = null
    private var tempPathGenerator: TempPathGenerator? = null
    private var blockWaitingModule: BlockWaitingModule? = null
    private var remoteOutputChecker: RemoteOutputChecker? = null
    private var lastRemoteOutputChecker: RemoteOutputChecker? = null
    private var lastBuildId: String? = null

    private var channelFactory: com.google.devtools.build.lib.remote.ChannelFactory? =
        object : com.google.devtools.build.lib.remote.ChannelFactory {
            @Throws(IOException::class)
            override fun newChannel(
                target: String?,
                proxy: String?,
                options: AuthAndTLSOptions?,
                interceptors: MutableList<ClientInterceptor?>
            ): ManagedChannel {
                return GoogleAuthUtils.newChannel(
                    executorService,
                    target,
                    proxy,
                    options,
                    if (interceptors.isEmpty()) null else interceptors
                )
            }
        }

    private val buildEventArtifactUploaderFactoryDelegate = BuildEventArtifactUploaderFactoryDelegate()

    private val repositoryRemoteHelpersFactoryDelegate = RepositoryRemoteHelpersFactoryDelegate()

    private var remoteDownloader: Downloader? = null

    private var credentialModule: CredentialModule? = null

    val startupOptions: com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>
        get() = com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
            RemoteStartupOptions::class.java
        )

    override fun globalInit(
        startupOptions: com.google.devtools.common.options.OptionsParsingResult,
        blazeServices: Iterable<com.google.devtools.build.lib.runtime.BlazeService?>?
    ) {
        outputBase =
            startupOptions.getOptions<BlazeServerStartupOptions?>(BlazeServerStartupOptions::class.java).getOutputBase()
        useRemoteRepoContentsCache =
            startupOptions.getOptions<RemoteStartupOptions?>(RemoteStartupOptions::class.java)
                .getUseRemoteRepoContentsCache()
    }

    override fun getFileSystemForBuildArtifacts(nativeFs: com.google.devtools.build.lib.vfs.FileSystem): com.google.devtools.build.lib.vfs.FileSystem? {
        if (!useRemoteRepoContentsCache) {
            return null
        }
        return RemoteExternalOverlayFileSystem(
            outputBase.getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION), nativeFs
        )
    }

    override fun serverInit(
        startupOptions: com.google.devtools.common.options.OptionsParsingResult?,
        builder: com.google.devtools.build.lib.runtime.ServerBuilder
    ) {
        builder.addBuildEventArtifactUploaderFactory(
            buildEventArtifactUploaderFactoryDelegate, "remote"
        )
        builder.setRepositoryHelpersFactory(repositoryRemoteHelpersFactoryDelegate)
    }

    private fun initHttpAndDiskCache(
        env: CommandEnvironment,
        credentials: com.google.auth.Credentials?,
        authAndTlsOptions: AuthAndTLSOptions?,
        remoteOptions: RemoteOptions,
        diskCachePath: PathFragment?,
        digestUtil: DigestUtil?
    ) {
        val combinedCacheClient: CombinedCacheClient?
        val circuitBreaker: com.google.devtools.build.lib.remote.Retrier.CircuitBreaker? =
            CircuitBreakerFactory.createCircuitBreaker(remoteOptions)
        try {
            combinedCacheClient =
                CombinedCacheClientFactory.create(
                    remoteOptions,
                    diskCachePath,
                    credentials,
                    authAndTlsOptions,
                    com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(
                        env.getWorkingDirectory(),
                        "workingDirectory"
                    ),
                    digestUtil,
                    RemoteRetrier(
                        remoteOptions, HTTP_RESULT_CLASSIFIER, retryScheduler, circuitBreaker
                    )
                )
        } catch (e: IOException) {
            handleInitFailure(env, e, Code.CACHE_INIT_FAILURE)
            return
        }
        val combinedCache: CombinedCache =
            CombinedCache(
                combinedCacheClient.remoteCacheClient,
                combinedCacheClient.diskCacheClient,
                com.google.common.base.Strings.emptyToNull(remoteOptions.getRemoteDownloadSymlinkTemplate()),
                digestUtil,
                remoteOptions.getExperimentalRemoteCacheChunking()
            )
        actionContextProvider =
            RemoteActionContextProvider.Companion.createForRemoteCaching(
                env,
                combinedCache,  /* retryScheduler= */
                null,
                digestUtil,
                remoteOutputChecker,
                outputService,
                knownMissingCasDigests
            )
        actionInputFetcher = createActionInputFetcher(combinedCache)
    }

    private fun createActionInputFetcher(combinedCache: CombinedCache?): RemoteActionInputFetcher? {
        if (combinedCache == null) {
            return null
        }
        val coreOptions: O? = env.getOptions().getOptions<O?>(CoreOptions::class.java)
        val outputPermissions: OutputPermissions =
            if (coreOptions != null && coreOptions.getExperimentalWritableOutputs())
                OutputPermissions.WRITABLE
            else
                OutputPermissions.READONLY
        return RemoteActionInputFetcher(
            env.getReporter(),
            env.getBuildRequestId(),
            env.getCommandId().toString(),
            combinedCache,
            env.getExecRoot(),
            tempPathGenerator,
            remoteOutputChecker,
            if (env.getOptions().getOptions<O?>(BuildRequestOptions::class.java) != null)
                env.getOutputDirectoryHelper()
            else
                null,
            outputPermissions
        )
    }

    override fun workspaceInit(
        runtime: BlazeRuntime, directories: BlazeDirectories?, builder: WorkspaceBuilder?
    ) {
        com.google.common.base.Preconditions.checkState(blockWaitingModule == null, "blockWaitingModule must be null")
        com.google.common.base.Preconditions.checkState(credentialModule == null, "credentialModule must be null")
        blockWaitingModule =
            com.google.common.base.Preconditions.checkNotNull<BlockWaitingModule?>(
                runtime.getBlazeModule<BlockWaitingModule?>(
                    BlockWaitingModule::class.java
                )
            )
        credentialModule = com.google.common.base.Preconditions.checkNotNull<CredentialModule?>(
            runtime.getBlazeModule<CredentialModule?>(CredentialModule::class.java)
        )
    }

    @Throws(AbruptExitException::class)
    override fun beforeCommand(env: CommandEnvironment) {
        com.google.common.base.Preconditions.checkState(
            actionContextProvider == null,
            "actionContextProvider must be null"
        )
        com.google.common.base.Preconditions.checkState(actionInputFetcher == null, "actionInputFetcher must be null")
        com.google.common.base.Preconditions.checkState(remoteOptions == null, "remoteOptions must be null")
        com.google.common.base.Preconditions.checkState(this.env == null, "env must be null")
        com.google.common.base.Preconditions.checkState(tempPathGenerator == null, "tempPathGenerator must be null")
        com.google.common.base.Preconditions.checkState(remoteOutputChecker == null, "remoteOutputChecker must be null")
        com.google.common.base.Preconditions.checkState(outputService == null, "remoteOutputService must be null")

        if ("clean" == env.getCommandName()) {
            knownMissingCasDigests.clear()
        }

        val remoteOptions: RemoteOptions? = env.getOptions().getOptions<RemoteOptions?>(RemoteOptions::class.java)
        if (remoteOptions == null) {
            // Quit if no supported command is being used. See getCommandOptions for details.
            return
        }

        this.remoteOptions = remoteOptions
        this.env = env

        // Resolve default disk cache location from --disk_cache / --disk_cache=true, etc.
        var diskCachePath: PathFragment? =
            remoteOptions.getDiskCachePath(
                env.getDirectories().getServerDirectories().getOutputUserRoot()
            )

        val authAndTlsOptions: AuthAndTLSOptions? = env.getOptions().getOptions<O?>(AuthAndTLSOptions::class.java)
        val hashFn: DigestHashFunction? = env.getRuntime().getFileSystem().getDigestFunction()
        val digestUtil: DigestUtil = DigestUtil(env.getXattrProvider(), hashFn)

        var verboseFailures = false
        val executionOptions: ExecutionOptions? = env.getOptions().getOptions<O?>(ExecutionOptions::class.java)
        if (executionOptions != null) {
            verboseFailures = executionOptions.verboseFailures
        }

        // If --remote_cache is empty but --remote_executor is not, reuse the latter for the former.
        if (!com.google.common.base.Strings.isNullOrEmpty(remoteOptions.getRemoteExecutor())
            && com.google.common.base.Strings.isNullOrEmpty(remoteOptions.getRemoteCache())
        ) {
            remoteOptions.setRemoteCache(remoteOptions.getRemoteExecutor())
        }

        if (shouldEnableRemoteOutputService(remoteOptions)) {
            if (diskCachePath != null) {
                diskCachePath = null
                env.getReporter()
                    .handle(
                        com.google.devtools.build.lib.events.Event.warn(
                            "--disk_cache is ignored when --experimental_remote_output_service is set."
                        )
                    )
            }

            if (com.google.common.base.Strings.isNullOrEmpty(remoteOptions.getRemoteCache())) {
                throw createOptionsExitException(
                    "--experimental_remote_output_service must be used in combination with one of"
                            + " --remote_cache or --remote_executor.",
                    FailureDetails.RemoteOptions.Code.EXECUTION_WITH_INVALID_CACHE
                )
            }
        }

        val enableDiskCache = diskCachePath != null
        val enableHttpCache: Boolean = CombinedCacheClientFactory.isHttpCache(remoteOptions)
        val enableRemoteExecution = shouldEnableRemoteExecution(remoteOptions)
        val enableGrpcCache: Boolean = GrpcCacheClient.Companion.isRemoteCacheOptions(remoteOptions)
        val enableRemoteDownloader = shouldEnableRemoteDownloader(remoteOptions)

        if (enableDiskCache) {
            // Check that the disk cache directory, which is managed by a garbage collecting idle task,
            // does not contain the output base. Since the specified output base path may be a symlink,
            // we resolve it fully. Intermediate symlinks do not have to be checked as the garbage
            // collector ignores symlinks. We also resolve the disk cache directory, where intermediate
            // symlinks also don't matter since deletion only occurs under the fully resolved path.
            var resolvedOutputBase: com.google.devtools.build.lib.vfs.Path = env.getOutputBase()
            try {
                resolvedOutputBase = resolvedOutputBase.resolveSymbolicLinks()
            } catch (ignored: FileNotFoundException) {
                // Will be created later.
            } catch (e: IOException) {
                throw createOptionsExitException(
                    "Failed to resolve output base: %s".formatted(e.getMessage()),
                    FailureDetails.RemoteOptions.Code.EXECUTION_WITH_INVALID_CACHE
                )
            }
            var resolvedDiskCache: com.google.devtools.build.lib.vfs.Path =
                env.getWorkingDirectory().getRelative(diskCachePath)
            try {
                resolvedDiskCache = resolvedDiskCache.resolveSymbolicLinks()
            } catch (ignored: FileNotFoundException) {
                // Will be created later.
            } catch (e: IOException) {
                throw createOptionsExitException(
                    "Failed to resolve disk cache directory: %s".formatted(e.getMessage()),
                    FailureDetails.RemoteOptions.Code.EXECUTION_WITH_INVALID_CACHE
                )
            }
            if (resolvedOutputBase.startsWith(resolvedDiskCache)) {
                // This is dangerous as the disk cache GC may delete files in the output base.
                throw createOptionsExitException(
                    "The output base [%s] cannot be a subdirectory of the --disk_cache directory [%s]"
                        .formatted(resolvedOutputBase, resolvedDiskCache),
                    FailureDetails.RemoteOptions.Code.EXECUTION_WITH_INVALID_CACHE
                )
            }
            val gcIdleTask: DiskCacheGarbageCollectorIdleTask? =
                DiskCacheGarbageCollectorIdleTask.Companion.create(
                    remoteOptions, diskCachePath, env.getWorkingDirectory()
                )
            if (gcIdleTask != null) {
                env.addIdleTask(gcIdleTask)
            }
        }

        if (enableRemoteDownloader && !enableGrpcCache) {
            throw createOptionsExitException(
                "The remote downloader can only be used in combination with gRPC caching",
                FailureDetails.RemoteOptions.Code.DOWNLOADER_WITHOUT_GRPC_CACHE
            )
        }

        tempPathGenerator = getTempPathGenerator(env)

        if (!enableDiskCache && !enableHttpCache && !enableGrpcCache && !enableRemoteExecution) {
            // Quit if no remote caching or execution was enabled.
            actionContextProvider =
                RemoteActionContextProvider.Companion.createForPlaceholder(
                    env, retryScheduler, digestUtil, knownMissingCasDigests
                )
            return
        }

        if (enableHttpCache && enableRemoteExecution) {
            throw createOptionsExitException(
                "Cannot combine gRPC based remote execution with HTTP-based caching",
                FailureDetails.RemoteOptions.Code.EXECUTION_WITH_INVALID_CACHE
            )
        }

        val enableScrubbing = remoteOptions.getScrubber() != null
        if (enableScrubbing && enableRemoteExecution) {
            env.getReporter()
                .handle(
                    com.google.devtools.build.lib.events.Event.warn(
                        ("Cache key scrubbing is incompatible with remote execution. Actions that are"
                                + " scrubbed per the --experimental_remote_scrubbing_config configuration"
                                + " file will be executed locally instead.")
                    )
                )
        }

        if (digestUtil.getDigestFunction() === DigestFunction.Value.UNKNOWN) {
            throw AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(java.lang.String.format("Unsupported digest function: %s", hashFn))
                        .setExecution(Execution.newBuilder().setCode(Execution.Code.EXECUTION_UNKNOWN))
                        .build()
                )
            )
        }

        // TODO(bazel-team): Consider adding a warning or more validation if the remoteDownloadRegex is
        // used without Build without the Bytes.
        val patternsToDownloadBuilder: com.google.common.collect.ImmutableList.Builder<java.util.function.Predicate<String?>?> =
            com.google.common.collect.ImmutableList.builder<java.util.function.Predicate<String?>?>()
        if (remoteOptions.getRemoteOutputsMode() != RemoteOutputsMode.ALL) {
            for (patternOption in remoteOptions.getRemoteDownloadRegex()) {
                patternsToDownloadBuilder.add(patternOption.matcher())
            }
        }

        remoteOutputChecker =
            RemoteOutputChecker(
                env.getCommandName(),
                remoteOptions.getRemoteOutputsMode(),
                patternsToDownloadBuilder.build(),
                lastRemoteOutputChecker
            )
        remoteOutputChecker.maybeInvalidateSkyframeValues(env.getSkyframeExecutor().getEvaluator())

        env.getEventBus().register(this)
        val invocationId: String? = env.getCommandId().toString()
        val buildRequestId: String? = env.getBuildRequestId()
        env.getReporter().handle(
            com.google.devtools.build.lib.events.Event.info(
                java.lang.String.format(
                    "Invocation ID: %s",
                    invocationId
                )
            )
        )

        RxJavaPlugins.setErrorHandler(
            io.reactivex.rxjava3.functions.Consumer { error: Throwable? ->
                env.getReporter().handle(
                    com.google.devtools.build.lib.events.Event.error(
                        com.google.common.base.Throwables.getStackTraceAsString(error)
                    )
                )
            })

        val logDir: com.google.devtools.build.lib.vfs.Path =
            env.getOutputBase().getRelative(env.getRuntime().productName + "-remote-logs")
        cleanAndCreateRemoteLogsDir(logDir)

        val buildRequestOptions: BuildRequestOptions? =
            env.getOptions().getOptions<O?>(BuildRequestOptions::class.java)

        var jobs = 0
        if (buildRequestOptions != null) {
            jobs = buildRequestOptions.jobs
        }

        val threadFactory: ThreadFactory =
            com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("remote-executor-%d").build()
        if (jobs != 0) {
            val tpe: ThreadPoolExecutor =
                ThreadPoolExecutor(
                    jobs, jobs, 60L, TimeUnit.SECONDS, LinkedBlockingQueue<java.lang.Runnable?>(), threadFactory
                )
            tpe.allowCoreThreadTimeOut(true)
            executorService = tpe
        } else {
            executorService = Executors.newCachedThreadPool(threadFactory)
        }

        val credentials: com.google.auth.Credentials?
        try {
            credentials =
                createCredentials(
                    CredentialHelperEnvironment.newBuilder()
                        .setEventReporter(env.getReporter())
                        .setWorkspacePath(env.getWorkspace())
                        .setClientEnvironment(env.getClientEnv())
                        .setHelperExecutionTimeout(authAndTlsOptions.credentialHelperTimeout)
                        .build(),
                    credentialModule.getCredentialCache(),
                    env.getCommandLinePathFactory(),
                    env.getRuntime().getFileSystem(),
                    authAndTlsOptions,
                    remoteOptions
                )
        } catch (e: IOException) {
            handleInitFailure(env, e, Code.CREDENTIALS_INIT_FAILURE)
            return
        }

        var maxConcurrencyPerConnection = 0
        if (remoteOptions.getRemoteMaxConcurrencyPerConnection() > 0) {
            maxConcurrencyPerConnection = remoteOptions.getRemoteMaxConcurrencyPerConnection()
        }
        var maxConnections = 0
        if (remoteOptions.getRemoteMaxConnections() > 0) {
            maxConnections = remoteOptions.getRemoteMaxConnections()
        }

        val circuitBreaker: com.google.devtools.build.lib.remote.Retrier.CircuitBreaker? =
            CircuitBreakerFactory.createCircuitBreaker(remoteOptions)
        val retrier: RemoteRetrier =
            RemoteRetrier(
                remoteOptions,
                RemoteRetrier.Companion.EXPERIMENTAL_GRPC_RESULT_CLASSIFIER,
                retryScheduler,
                circuitBreaker
            )

        if (!com.google.common.base.Strings.isNullOrEmpty(remoteOptions.getRemoteOutputService())) {
            val bazelOutputServiceChannel: ReferenceCountedChannel =
                createChannel(
                    executorService,
                    remoteOptions,  // Don't use auth flags for remote output service
                    com.google.devtools.common.options.Options.getDefaults<O?>(AuthAndTLSOptions::class.java),
                    null,
                    null,
                    channelFactory,
                    remoteOptions.getRemoteOutputService(),
                    null,
                    maxConcurrencyPerConnection,
                    maxConnections,
                    verboseFailures,
                    env.getReporter(),
                    null,
                    digestUtil.getDigestFunction(),
                    ServerCapabilitiesRequirement.NONE
                )

            outputService =
                BazelOutputService(
                    env.getOutputBase(),
                    java.util.function.Supplier { env.getExecRoot() },
                    java.util.function.Supplier { env.getDirectories().getOutputPath(env.getWorkspaceName()) },
                    digestUtil.getDigestFunction(),
                    remoteOptions.getRemoteCache(),
                    remoteOptions.getRemoteInstanceName(),
                    remoteOptions.getRemoteOutputServiceOutputPathPrefix(),
                    verboseFailures,
                    retrier,
                    bazelOutputServiceChannel,
                    lastBuildId
                )
        } else {
            outputService =
                RemoteOutputService(
                    env.getDirectories(),
                    buildRequestOptions != null && buildRequestOptions.rewindLostInputs
                )
        }

        if ((enableHttpCache || enableDiskCache) && !enableGrpcCache) {
            initHttpAndDiskCache(
                env, credentials, authAndTlsOptions, remoteOptions, diskCachePath, digestUtil
            )
            return
        }

        var loggingInterceptor: ClientInterceptor? = null
        if (remoteOptions.getRemoteGrpcLog() != null) {
            try {
                rpcLogFile =
                    AsynchronousMessageOutputStream<LogEntry?>(
                        env.getWorkingDirectory().getRelative(remoteOptions.getRemoteGrpcLog())
                    )
            } catch (e: IOException) {
                handleInitFailure(env, e, Code.RPC_LOG_FAILURE)
                return
            }
            loggingInterceptor = LoggingInterceptor(rpcLogFile, env.getRuntime().getClock())
        }

        val callCredentialsProvider: CallCredentialsProvider =
            GoogleAuthUtils.newCallCredentialsProvider(credentials)
        val callCredentials: CallCredentials? = callCredentialsProvider.callCredentials

        val rsc: RemoteServerCapabilities =
            RemoteServerCapabilities(
                buildRequestId,
                invocationId,
                remoteOptions.getRemoteInstanceName(),
                callCredentials,
                remoteOptions.getRemoteTimeout().toSeconds(),
                retrier
            )

        var execChannel: ReferenceCountedChannel? = null
        var cacheChannel: ReferenceCountedChannel? = null
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("init channel and check server capabilities")
            .use { s ->
                if (enableRemoteExecution) {
                    // Create a separate channel if --remote_executor and --remote_cache point to different
                    // endpoints.
                    if (remoteOptions.getRemoteCache() == remoteOptions.getRemoteExecutor()) {
                        execChannel =
                            createChannel(
                                executorService,
                                remoteOptions,
                                authAndTlsOptions,
                                TracingMetadataUtils.newExecHeadersInterceptor(remoteOptions),
                                loggingInterceptor,
                                channelFactory,
                                remoteOptions.getRemoteExecutor(),
                                remoteOptions.getRemoteProxy(),
                                maxConcurrencyPerConnection,
                                maxConnections,
                                verboseFailures,
                                env.getReporter(),
                                rsc,
                                digestUtil.getDigestFunction(),
                                ServerCapabilitiesRequirement.EXECUTION_AND_CACHE
                            )
                        cacheChannel = execChannel.retain()
                    } else {
                        execChannel =
                            createChannel(
                                executorService,
                                remoteOptions,
                                authAndTlsOptions,
                                TracingMetadataUtils.newExecHeadersInterceptor(remoteOptions),
                                loggingInterceptor,
                                channelFactory,
                                remoteOptions.getRemoteExecutor(),
                                remoteOptions.getRemoteProxy(),
                                maxConcurrencyPerConnection,
                                maxConnections,
                                verboseFailures,
                                env.getReporter(),
                                rsc,
                                digestUtil.getDigestFunction(),
                                ServerCapabilitiesRequirement.EXECUTION
                            )
                    }
                }
                if (cacheChannel == null) {
                    cacheChannel =
                        createChannel(
                            executorService,
                            remoteOptions,
                            authAndTlsOptions,
                            TracingMetadataUtils.newCacheHeadersInterceptor(remoteOptions),
                            loggingInterceptor,
                            channelFactory,
                            remoteOptions.getRemoteCache(),
                            remoteOptions.getRemoteProxy(),
                            maxConcurrencyPerConnection,
                            maxConnections,
                            verboseFailures,
                            env.getReporter(),
                            rsc,
                            digestUtil.getDigestFunction(),
                            ServerCapabilitiesRequirement.CACHE
                        )
                }
            }
        val remoteCacheClient: RemoteCacheClient =
            GrpcCacheClient(
                cacheChannel.retain(), callCredentialsProvider, remoteOptions, retrier, digestUtil
            )
        cacheChannel.release()
        var diskCacheClient: DiskCacheClient? = null

        if (enableRemoteExecution) {
            if (enableDiskCache) {
                try {
                    diskCacheClient =
                        CombinedCacheClientFactory.createDiskCache(
                            env.getWorkingDirectory(), diskCachePath, digestUtil
                        )
                } catch (e: java.lang.Exception) {
                    handleInitFailure(env, e, Code.CACHE_INIT_FAILURE)
                    return
                }
            }

            val execRetrier: RemoteRetrier =
                RemoteRetrier(
                    remoteOptions, RemoteRetrier.Companion.GRPC_RESULT_CLASSIFIER, retryScheduler, circuitBreaker
                )
            val remoteExecutor: RemoteExecutionClient =
                GrpcRemoteExecutor(execChannel.retain(), callCredentialsProvider, execRetrier)
            execChannel.release()
            val remoteCache: RemoteExecutionCache =
                RemoteExecutionCache(
                    remoteCacheClient,
                    diskCacheClient,
                    com.google.common.base.Strings.emptyToNull(remoteOptions.getRemoteDownloadSymlinkTemplate()),
                    digestUtil,
                    remoteOptions.getExperimentalRemoteCacheChunking()
                )
            actionContextProvider =
                RemoteActionContextProvider.Companion.createForRemoteExecution(
                    env,
                    remoteCache,
                    remoteExecutor,
                    retryScheduler,
                    digestUtil,
                    logDir,
                    remoteOutputChecker,
                    outputService,
                    knownMissingCasDigests
                )
        } else {
            if (enableDiskCache) {
                try {
                    diskCacheClient =
                        CombinedCacheClientFactory.createDiskCache(
                            env.getWorkingDirectory(), diskCachePath, digestUtil
                        )
                } catch (e: java.lang.Exception) {
                    handleInitFailure(env, e, Code.CACHE_INIT_FAILURE)
                    return
                }
            }

            val combinedCache: CombinedCache =
                CombinedCache(
                    remoteCacheClient,
                    diskCacheClient,
                    com.google.common.base.Strings.emptyToNull(remoteOptions.getRemoteDownloadSymlinkTemplate()),
                    digestUtil,
                    remoteOptions.getExperimentalRemoteCacheChunking()
                )
            actionContextProvider =
                RemoteActionContextProvider.Companion.createForRemoteCaching(
                    env,
                    combinedCache,
                    retryScheduler,
                    digestUtil,
                    remoteOutputChecker,
                    outputService,
                    knownMissingCasDigests
                )
        }

        actionInputFetcher = createActionInputFetcher(actionContextProvider.getCombinedCache())

        repositoryRemoteHelpersFactoryDelegate.init(
            RepositoryRemoteHelpersFactoryImpl(
                env.getDirectories(),
                actionContextProvider.getCombinedCache(),
                actionContextProvider.getRemoteExecutionClient(),
                buildRequestId,
                invocationId,
                env.getWorkspaceName(),
                remoteOptions.getRemoteInstanceName(),
                remoteOptions.getRemoteAcceptCached(),
                remoteOptions.getRemoteUploadLocalResults(),
                verboseFailures
            )
        )
        if (env.getDirectories().getOutputBase().getFileSystem()
                    is RemoteExternalOverlayFileSystem
        ) {
            remoteFs.beforeCommand(
                actionContextProvider.getCombinedCache(),
                actionInputFetcher,
                env.getReporter(),
                buildRequestId,
                invocationId,
                env.getSkyframeExecutor().getEvaluator(),
                remoteOptions.getRemoteCacheTtl()
            )
        }

        buildEventArtifactUploaderFactoryDelegate.init(
            ByteStreamBuildEventArtifactUploaderFactory(
                executorService,
                env.getReporter(),
                verboseFailures,
                actionContextProvider.getCombinedCache(),
                remoteOptions.getRemoteInstanceName(),
                remoteOptions.getRemoteBytestreamUriPrefix(),
                buildRequestId,
                invocationId,
                remoteOptions.getRemoteBuildEventUploadMode()
            )
        )

        if (enableRemoteDownloader) {
            val downloaderChannel: ReferenceCountedChannel
            // Create a separate channel if --remote_downloader and --remote_cache point to different
            // endpoints.
            if (remoteOptions.getRemoteDownloader() == remoteOptions.getRemoteCache()) {
                downloaderChannel = cacheChannel.retain()
            } else {
                downloaderChannel =
                    createChannel(
                        executorService,
                        remoteOptions,
                        authAndTlsOptions,  /* headersInterceptor= */
                        null,
                        loggingInterceptor,
                        channelFactory,
                        remoteOptions.getRemoteDownloader(),
                        remoteOptions.getRemoteProxy(),
                        maxConcurrencyPerConnection,
                        maxConnections,
                        verboseFailures,
                        env.getReporter(),
                        rsc,
                        digestUtil.getDigestFunction(),
                        ServerCapabilitiesRequirement.NONE
                    )
            }

            remoteDownloader =
                GrpcRemoteDownloader(
                    buildRequestId,
                    invocationId,
                    downloaderChannel.retain(),
                    java.util.Optional.ofNullable<CallCredentials?>(callCredentials),
                    retrier,
                    remoteCacheClient,
                    digestUtil.getDigestFunction(),
                    remoteOptions,
                    verboseFailures,
                    env.getHttpDownloader(),
                    remoteOptions.getRemoteDownloaderLocalFallback()
                )
            downloaderChannel.release()
            env.getDownloaderDelegate().setDelegate(remoteDownloader)
        }
    }

    // This is a Skymeld-only code path. At the same time, afterAnalysis is exclusive to the
    // non-Skymeld code path.
    override fun afterTopLevelTargetAnalysis(
        env: CommandEnvironment?,
        request: BuildRequest,
        buildOptions: BuildOptions?,
        configuredTarget: ConfiguredTarget?
    ) {
        if (remoteOutputChecker != null) {
            remoteOutputChecker.afterTopLevelTargetAnalysis(
                configuredTarget, request::getTopLevelArtifactContext
            )
        }
    }

    override fun afterSingleAspectAnalysis(request: BuildRequest, configuredTarget: ConfiguredAspect?) {
        if (remoteOutputChecker != null) {
            remoteOutputChecker.afterAspectAnalysis(
                configuredTarget, request::getTopLevelArtifactContext
            )
        }
    }

    override fun afterSingleTestAnalysis(request: BuildRequest?, configuredTarget: ConfiguredTarget?) {
        if (remoteOutputChecker != null) {
            remoteOutputChecker.afterTestAnalyzedEvent(configuredTarget)
        }
    }

    override fun coverageArtifactsKnown(coverageArtifacts: com.google.common.collect.ImmutableSet<Artifact?>?) {
        if (remoteOutputChecker != null) {
            remoteOutputChecker.coverageArtifactsKnown(coverageArtifacts)
        }
    }

    override fun afterAnalysis(
        env: CommandEnvironment?,
        request: BuildRequest?,
        buildOptions: BuildOptions?,
        analysisResult: AnalysisResult?
    ) {
        if (remoteOutputChecker != null) {
            remoteOutputChecker.afterAnalysis(analysisResult)
        }
    }

    override fun afterCommand() {
        com.google.common.base.Preconditions.checkNotNull<BlockWaitingModule?>(
            blockWaitingModule,
            "blockWaitingModule must not be null"
        )

        // Some cleanup tasks must wait until every other BlazeModule's afterCommand() has run, as
        // otherwise we might interfere with asynchronous remote downloads that are in progress.
        val actionContextProviderRef: RemoteActionContextProvider? = actionContextProvider
        val tempPathGeneratorRef: TempPathGenerator? = tempPathGenerator
        val rpcLogFileRef: AsynchronousMessageOutputStream<LogEntry?>? = rpcLogFile
        if (actionContextProviderRef != null || tempPathGeneratorRef != null || rpcLogFileRef != null) {
            blockWaitingModule.submit(
                com.google.devtools.build.lib.runtime.BlockWaitingModule.Task {
                    afterCommandTask(
                        actionContextProviderRef,
                        tempPathGeneratorRef,
                        rpcLogFileRef
                    )
                })
        }

        lastRemoteOutputChecker = remoteOutputChecker
        lastBuildId =
            com.google.common.base.Preconditions.checkNotNull<CommandEnvironment?>(env).getCommandId().toString()

        buildEventArtifactUploaderFactoryDelegate.reset()
        repositoryRemoteHelpersFactoryDelegate.reset()
        if (env.getDirectories().getOutputBase().getFileSystem()
                    is RemoteExternalOverlayFileSystem
        ) {
            remoteFs.afterCommand()
        }
        remoteDownloader = null
        actionContextProvider = null
        actionInputFetcher = null
        remoteOptions = null
        env = null
        outputService = null
        tempPathGenerator = null
        rpcLogFile = null
        remoteOutputChecker = null
    }

    override fun registerSpawnStrategies(
        registryBuilder: SpawnStrategyRegistry.Builder, env: CommandEnvironment
    ) {
        if (actionContextProvider == null) {
            return
        }
        val remoteOptions: RemoteOptions =
            com.google.common.base.Preconditions.checkNotNull<RemoteOptions>(
                env.getOptions().getOptions<RemoteOptions?>(RemoteOptions::class.java), "RemoteOptions"
            )
        registryBuilder.setRemoteLocalFallbackStrategyIdentifier(
            remoteOptions.getRemoteLocalFallbackStrategy()
        )
        actionContextProvider.registerRemoteSpawnStrategy(registryBuilder)
    }

    override fun registerActionContexts(
        registryBuilder: ModuleActionContextRegistry.Builder,
        env: CommandEnvironment,
        buildRequest: BuildRequest?
    ) {
        if (actionContextProvider == null) {
            return
        }
        actionContextProvider.registerSpawnCache(registryBuilder)

        // For skymeld, a non-toplevel target might become a toplevel after it has been executed. This
        // is the last chance to download the missing toplevel outputs in this case before sending out
        // TargetCompleteEvent. See https://github.com/bazelbuild/bazel/issues/20737.
        if (env.withMergedAnalysisAndExecutionSourceOfTruth()
            && actionInputFetcher != null && remoteOutputChecker != null
        ) {
            registryBuilder.register(
                ImportantOutputHandler::class.java,
                RemoteImportantOutputHandler(
                    SkyframeExecutorWrappingWalkableGraph.of(env.getSkyframeExecutor()),
                    remoteOutputChecker,
                    actionInputFetcher,
                    com.google.common.base.Preconditions.checkNotNull<OutputService?>(outputService)
                        .getRewoundActionSynchronizer()
                )
            )
        }
    }

    @Throws(AbruptExitException::class)
    private fun getTempPathGenerator(env: CommandEnvironment): TempPathGenerator {
        val tempDir: com.google.devtools.build.lib.vfs.Path = env.getActionTempsDirectory().getChild("remote")
        if (tempDir.exists()) {
            env.getReporter()
                .handle(com.google.devtools.build.lib.events.Event.warn("Found stale downloads from previous build, deleting..."))
            try {
                tempDir.deleteTree()
            } catch (e: IOException) {
                throw AbruptExitException(
                    DetailedExitCode.of(
                        ExitCode.LOCAL_ENVIRONMENTAL_ERROR,
                        FailureDetail.newBuilder()
                            .setMessage(
                                java.lang.String.format("Failed to delete stale downloads: %s", e.getMessage())
                            )
                            .setRemoteExecution(
                                RemoteExecution.newBuilder()
                                    .setCode(Code.DOWNLOADED_INPUTS_DELETION_FAILURE)
                            )
                            .build()
                    )
                )
            }
        }

        return TempPathGenerator(tempDir)
    }

    override fun executorInit(env: CommandEnvironment, request: BuildRequest?, builder: ExecutorBuilder) {
        com.google.common.base.Preconditions.checkNotNull<RemoteOptions?>(
            remoteOptions,
            "remoteOptions must not be null"
        )

        if (actionContextProvider == null) {
            return
        }

        actionContextProvider.setTempPathGenerator(tempPathGenerator)

        if (actionContextProvider.getCombinedCache() != null) {
            com.google.common.base.Preconditions.checkNotNull<RemoteOutputChecker?>(
                remoteOutputChecker,
                "remoteOutputChecker must not be null"
            )
            com.google.common.base.Preconditions.checkNotNull<OutputService?>(
                outputService,
                "remoteOutputService must not be null"
            )
            com.google.common.base.Preconditions.checkNotNull<RemoteActionInputFetcher?>(
                actionInputFetcher,
                "actionInputFetcher must not be null"
            )

            env.getEventBus().register(actionInputFetcher)
            builder.setActionInputPrefetcher(actionInputFetcher)
            actionContextProvider.setActionInputFetcher(actionInputFetcher)

            var leaseExtension: LeaseExtension? = null
            if (remoteOptions.getRemoteCacheLeaseExtension()) {
                leaseExtension =
                    RemoteLeaseExtension(
                        env.getSkyframeExecutor().getEvaluator(),
                        env.getBlazeWorkspace().getPersistentActionCache(),
                        env.getBuildRequestId(),
                        env.getCommandId().toString(),
                        actionContextProvider.getCombinedCache(),
                        remoteOptions.getRemoteCacheTtl()
                    )
            }
            val leaseService: LeaseService =
                LeaseService(
                    env.getSkyframeExecutor().getEvaluator(),
                    java.util.function.Supplier { env.getBlazeWorkspace().getPersistentActionCache() },
                    leaseExtension
                )
            env.getEventBus().register(leaseService)

            if (outputService is RemoteOutputService) {
                outputService.setRemoteOutputChecker(remoteOutputChecker)
                outputService.setActionInputFetcher(actionInputFetcher)
                outputService.setLeaseService(leaseService)
                env.getEventBus().register(outputService)
            }
        }

        builder.setActionExecutionSalt(computeActionExecutionSalt(remoteOptions))
    }

    override fun getOutputService(): OutputService? {
        return outputService
    }

    val commonCommandOptions: Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase>>
        get() = com.google.common.collect.ImmutableList.of<E?>(
            RemoteOptions::class.java,
            AuthAndTLSOptions::class.java
        )

    private class BuildEventArtifactUploaderFactoryDelegate

        : BuildEventArtifactUploaderFactory {
        private var uploaderFactory: ByteStreamBuildEventArtifactUploaderFactory? = null

        fun init(uploaderFactory: ByteStreamBuildEventArtifactUploaderFactory?) {
            com.google.common.base.Preconditions.checkState(this.uploaderFactory == null)
            this.uploaderFactory = uploaderFactory
        }

        fun reset() {
            this.uploaderFactory = null
        }

        @Throws(InvalidPackagePathSymlinkException::class)
        override fun create(env: CommandEnvironment?): BuildEventArtifactUploader? {
            val uploaderFactory0: BuildEventArtifactUploaderFactory? = this.uploaderFactory
            if (uploaderFactory0 == null) {
                return LocalFilesArtifactUploader()
            }
            return uploaderFactory0.create(env)
        }
    }

    private class RepositoryRemoteHelpersFactoryDelegate

        : RepositoryRemoteHelpersFactory {
        @kotlin.concurrent.Volatile
        private var delegate: RepositoryRemoteHelpersFactory? = null

        fun init(delegate: RepositoryRemoteHelpersFactory?) {
            com.google.common.base.Preconditions.checkState(this.delegate == null)
            this.delegate = delegate
        }

        fun reset() {
            this.delegate = null
        }

        override fun createExecutor(): RepositoryRemoteExecutor? {
            val delegate: RepositoryRemoteHelpersFactory? = this.delegate
            if (delegate == null) {
                return null
            }
            return delegate.createExecutor()
        }

        override fun createRepoContentsCache(): RemoteRepoContentsCache? {
            val delegate: RepositoryRemoteHelpersFactory? = this.delegate
            if (delegate == null) {
                return null
            }
            return delegate.createRepoContentsCache()
        }
    }

    @com.google.common.annotations.VisibleForTesting
    fun setChannelFactory(channelFactory: com.google.devtools.build.lib.remote.ChannelFactory?) {
        this.channelFactory = channelFactory
    }

    @com.google.common.annotations.VisibleForTesting
    fun getActionContextProvider(): RemoteActionContextProvider? {
        return actionContextProvider
    }

    @com.google.common.annotations.VisibleForTesting
    fun getRemoteDownloader(): Downloader? {
        return remoteDownloader
    }

    companion object {
        /** Returns whether remote execution should be enabled.  */
        fun shouldEnableRemoteExecution(options: RemoteOptions): Boolean {
            return !com.google.common.base.Strings.isNullOrEmpty(options.getRemoteExecutor())
        }

        /** Returns whether the remote downloader should be enabled.  */
        private fun shouldEnableRemoteDownloader(options: RemoteOptions): Boolean {
            return !com.google.common.base.Strings.isNullOrEmpty(options.getRemoteDownloader())
        }

        /** Returns whether the remote output service should be enabled.  */
        private fun shouldEnableRemoteOutputService(options: RemoteOptions): Boolean {
            return !com.google.common.base.Strings.isNullOrEmpty(options.getRemoteOutputService())
        }

        val HTTP_RESULT_CLASSIFIER: ResultClassifier = ResultClassifier { e: java.lang.Exception? ->
            var retry = false
            if (e is ClosedChannelException) {
                retry = true
            } else if (e is DownloadTimeoutException) {
                retry = true
            } else if (e is HttpException) {
                val status: Int = e.response().status().code()
                if (status == io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND.code()) {
                    return@ResultClassifier com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.SUCCESS
                }
                retry =
                    status == io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR.code() || status == io.netty.handler.codec.http.HttpResponseStatus.BAD_GATEWAY.code() || status == io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE.code() || status == io.netty.handler.codec.http.HttpResponseStatus.GATEWAY_TIMEOUT.code()
            } else if (e is IOException) {
                val msg: String = com.google.common.base.Ascii.toLowerCase(e.getMessage())
                if (msg.contains("connection reset")) {
                    retry = true
                } else if (msg.contains("operation timed out")) {
                    retry = true
                }
            } else {
                // Workaround for a netty bug: https://github.com/netty/netty/issues/11815. Remove this
                // once it is fixed in the upstream.
                if (e is io.netty.handler.codec.DecoderException
                    && e.getMessage().endsWith("functions:OPENSSL_internal:BAD_DECRYPT")
                ) {
                    retry = true
                }
            }
            if (retry) com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.TRANSIENT_FAILURE else com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.PERMANENT_FAILURE
        }

        private fun createChannel(
            executorService: ExecutorService,
            remoteOptions: RemoteOptions?,
            authAndTlsOptions: AuthAndTLSOptions?,
            headersInterceptor: ClientInterceptor?,
            loggingInterceptor: ClientInterceptor?,
            channelFactory: com.google.devtools.build.lib.remote.ChannelFactory?,
            target: String?,
            proxy: String?,
            maxConcurrencyPerConnection: Int,
            maxConnections: Int,
            verboseFailures: Boolean,
            reporter: com.google.devtools.build.lib.events.Reporter?,
            remoteServerCapabilities: RemoteServerCapabilities?,
            digestFunction: DigestFunction.Value?,
            requirement: ServerCapabilitiesRequirement?
        ): ReferenceCountedChannel {
            val interceptors: com.google.common.collect.ImmutableList.Builder<ClientInterceptor?> =
                com.google.common.collect.ImmutableList.builder<ClientInterceptor?>()
            if (headersInterceptor != null) {
                interceptors.add(headersInterceptor)
            }
            if (loggingInterceptor != null) {
                interceptors.add(loggingInterceptor)
            }
            val channel: ReferenceCountedChannel =
                ReferenceCountedChannel(
                    GoogleChannelConnectionFactory(
                        channelFactory,
                        target,
                        proxy,
                        remoteOptions,
                        authAndTlsOptions,
                        interceptors.build(),
                        maxConcurrencyPerConnection,
                        verboseFailures,
                        reporter,
                        remoteServerCapabilities,
                        digestFunction,
                        requirement
                    ),
                    maxConnections
                )
            // Eagerly start creating the channel and verifying the capabilities in the background.
            // TODO(tjgq): Make sure this task doesn't linger beyond afterCommand().
            val unused: java.util.concurrent.Future<*>? =
                executorService.submit(
                    java.lang.Runnable {
                        val unused2: com.google.common.util.concurrent.ListenableFuture<Any?>? =
                            channel.withChannelFuture<Any?>(com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { c: io.grpc.Channel? -> null })
                    })
            return channel
        }

        private fun handleInitFailure(
            env: CommandEnvironment, e: java.lang.Exception, remoteExecutionCode: Code?
        ) {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
            env.getBlazeModuleEnvironment()
                .exit(
                    createExitException(
                        "Error initializing RemoteModule",
                        ExitCode.COMMAND_LINE_ERROR,
                        remoteExecutionCode
                    )
                )
        }

        @Throws(AbruptExitException::class)
        private fun cleanAndCreateRemoteLogsDir(logDir: com.google.devtools.build.lib.vfs.Path) {
            try {
                // Clean out old logs files.
                if (logDir.exists()) {
                    logDir.deleteTree()
                }
                logDir.createDirectory()
            } catch (e: IOException) {
                val message: String? =
                    java.lang.String.format("Could not create base directory for remote logs: %s", logDir)
                throw createExitException(
                    message, ExitCode.LOCAL_ENVIRONMENTAL_ERROR, Code.LOG_DIR_CLEANUP_FAILURE
                )
            }
        }

        @Throws(AbruptExitException::class)
        private fun afterCommandTask(
            actionContextProvider: RemoteActionContextProvider?,
            tempPathGenerator: TempPathGenerator?,
            rpcLogFile: AsynchronousMessageOutputStream<LogEntry?>?
        ) {
            if (actionContextProvider != null) {
                actionContextProvider.afterCommand()
            }

            if (tempPathGenerator != null) {
                val tempDir: com.google.devtools.build.lib.vfs.Path = tempPathGenerator.getTempDir()
                try {
                    tempDir.deleteTree()
                } catch (ignored: IOException) {
                    // Intentionally ignored.
                }
            }

            if (rpcLogFile != null) {
                try {
                    rpcLogFile.close()
                } catch (e: IOException) {
                    throw createExitException(
                        "Partially wrote RPC log file",
                        ExitCode.LOCAL_ENVIRONMENTAL_ERROR,
                        Code.RPC_LOG_FAILURE
                    )
                }
            }
        }

        private fun computeActionExecutionSalt(remoteOptions: RemoteOptions?): String {
            val fp: Fingerprint = Fingerprint()

            // When building without a remote cache following a build with one, cached actions may reference
            // remotely stored files which cannot be downloaded. For simplicity we also invalidate in the
            // reverse situation (building with a remote cache after building without one) even though
            // cached local actions don't need it.
            // TODO(chiwang): Solve this with build/action rewinding instead. The main difficulty is that if
            // no remote options are set, we lack a prefetcher and cannot trigger rewinding.
            fp.addBoolean(remoteOptions != null && remoteOptions.isRemoteCacheEnabled())

            // The default exec properties may affect how a spawn is remotely executed without affecting the
            // action key. In practice, only spawns with no execution platform or whose execution platform
            // has no exec properties are affected, but we don't have access to this information at the
            // time we're computing the action key, so we unconditionally include the defaults. This
            // shouldn't be too bad, as we don't expect the defaults to change very often.
            fp.addStringMap(
                if (remoteOptions != null) remoteOptions.getRemoteDefaultExecProperties() else com.google.common.collect.ImmutableMap.of<String?, String?>()
            )

            return fp.hexDigestAndReset()
        }

        private fun createOptionsExitException(
            message: String?, remoteExecutionCode: FailureDetails.RemoteOptions.Code?
        ): AbruptExitException {
            return AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setRemoteOptions(
                            FailureDetails.RemoteOptions.newBuilder().setCode(remoteExecutionCode)
                        )
                        .build()
                )
            )
        }

        private fun createExitException(
            message: String?, exitCode: ExitCode?, remoteExecutionCode: Code?
        ): AbruptExitException {
            return AbruptExitException(
                DetailedExitCode.of(
                    exitCode,
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setRemoteExecution(RemoteExecution.newBuilder().setCode(remoteExecutionCode))
                        .build()
                )
            )
        }

        @com.google.common.annotations.VisibleForTesting
        @Throws(IOException::class)
        fun createCredentials(
            credentialHelperEnvironment: CredentialHelperEnvironment,
            credentialCache: com.github.benmanes.caffeine.cache.Cache<java.net.URI?, GetCredentialsResponse?>?,
            commandLinePathFactory: CommandLinePathFactory?,
            fileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
            authAndTlsOptions: AuthAndTLSOptions?,
            remoteOptions: RemoteOptions
        ): com.google.auth.Credentials {
            val credentials: com.google.auth.Credentials =
                GoogleAuthUtils.newCredentials(
                    credentialHelperEnvironment,
                    credentialCache,
                    commandLinePathFactory,
                    fileSystem,
                    authAndTlsOptions
                )

            try {
                if (remoteOptions.getRemoteCache() != null && com.google.common.base.Ascii.toLowerCase(remoteOptions.getRemoteCache())
                        .startsWith("http://")
                    && !credentials.getRequestMetadata(java.net.URI(remoteOptions.getRemoteCache())).isEmpty()
                ) {
                    // TODO(yannic): Make this a error aborting the build.
                    credentialHelperEnvironment
                        .eventReporter()
                        .handle(
                            com.google.devtools.build.lib.events.Event.warn(
                                ("Credentials are transmitted in plaintext to "
                                        + remoteOptions.getRemoteCache()
                                        + ". Please consider using an HTTPS endpoint.")
                            )
                        )
                }
            } catch (e: URISyntaxException) {
                throw IOException(e.getMessage(), e)
            }

            return credentials
        }
    }
}
