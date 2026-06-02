// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.actions.ActionKeyContext

/**
 * The BlazeRuntime class encapsulates the immutable configuration of the current instance. These
 * runtime settings and services are available to most parts of any Blaze application for the
 * duration of the batch run or server lifetime.
 * 
 * 
 * The parts specific to the current command are stored in [CommandEnvironment].
 */
class BlazeRuntime private constructor(
    fileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
    queryEnvironmentFactory: QueryEnvironmentFactory?,
    queryFunctions: com.google.common.collect.ImmutableList<QueryFunction?>?,
    queryOutputFormatters: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.query2.query.output.OutputFormatter?>?,
    pkgFactory: PackageFactory?,
    ruleClassProvider: ConfiguredRuleClassProvider,
    infoItems: com.google.common.collect.ImmutableMap<String?, InfoItem?>?,
    actionKeyContext: ActionKeyContext,
    clock: com.google.devtools.build.lib.clock.Clock,
    abruptShutdownHandler: java.lang.Runnable?,
    startupOptionsProvider: com.google.devtools.common.options.OptionsParsingResult,
    blazeModules: com.google.common.collect.ImmutableList<BlazeModule>,
    blazeServices: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.runtime.BlazeService>,
    eventBusExceptionHandler: com.google.common.eventbus.SubscriberExceptionHandler?,
    bugReporter: BugReporter?,
    projectFileProvider: com.google.devtools.build.lib.runtime.ProjectFile.Provider?,
    queryRuntimeHelperFactory: com.google.devtools.build.lib.runtime.QueryRuntimeHelper.Factory?,
    moduleInvocationPolicy: InvocationPolicy?,
    commands: Iterable<BlazeCommand>,
    productName: String?,
    buildEventArtifactUploaderFactoryMap: BuildEventArtifactUploaderFactoryMap,
    repositoryRemoteHelpersFactory: RepositoryRemoteHelpersFactory?,
    instrumentationOutputFactory: InstrumentationOutputFactory,
    installBaseLock: FileSystemLock?
) : BlazeRuntimeInterface {
    private val fileSystem: com.google.devtools.build.lib.vfs.FileSystem?
    private val blazeModules: com.google.common.collect.ImmutableList<BlazeModule>
    private val blazeServices: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.runtime.BlazeService>
    private val commandMap: MutableMap<String?, BlazeCommand?> = LinkedHashMap<String?, BlazeCommand?>()
    private val clock: com.google.devtools.build.lib.clock.Clock
    private val abruptShutdownHandler: java.lang.Runnable?

    private val packageFactory: PackageFactory?
    private val ruleClassProvider: ConfiguredRuleClassProvider

    // For bazel info.
    private val infoItems: com.google.common.collect.ImmutableMap<String?, InfoItem?>?

    // For bazel query.
    private val queryEnvironmentFactory: QueryEnvironmentFactory?
    private val queryFunctions: com.google.common.collect.ImmutableList<QueryFunction?>?
    private val queryOutputFormatters: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.query2.query.output.OutputFormatter?>?

    private val storedExitCode: AtomicReference<DetailedExitCode?> = AtomicReference<DetailedExitCode?>()

    // TODO(b/1030062): If multiple commands can ever run simultaneously, this should be a set of
    //  command environments, with environments removed in some after-command hook.
    // Null if a command is not in progress.
    @kotlin.concurrent.Volatile
    private var env: CommandEnvironment? = null

    // We pass this through here to make it available to the MasterLogWriter.
    private val startupOptionsProvider: com.google.devtools.common.options.OptionsParsingResult

    private val projectFileProvider: com.google.devtools.build.lib.runtime.ProjectFile.Provider?
    private val queryRuntimeHelperFactory: com.google.devtools.build.lib.runtime.QueryRuntimeHelper.Factory?
    private val moduleInvocationPolicy: InvocationPolicy?
    private val eventBusExceptionHandler: com.google.common.eventbus.SubscriberExceptionHandler?
    private val bugReporter: BugReporter?
    @kotlin.jvm.JvmField
    val productName: String?
    private val buildEventArtifactUploaderFactoryMap: BuildEventArtifactUploaderFactoryMap
    private val actionKeyContext: ActionKeyContext
    private val repositoryRemoteHelpersFactory: RepositoryRemoteHelpersFactory?

    // Workspace state (currently exactly one workspace per server)
    private var workspace: BlazeWorkspace? = null

    private val instrumentationOutputFactory: InstrumentationOutputFactory

    @Suppress("unused")
    private val installBaseLock: FileSystemLock?

    private val cgroupsInfo: CgroupsInfo?

    init {
        // Server state
        this.fileSystem = fileSystem
        this.blazeModules = blazeModules
        this.blazeServices = blazeServices
        overrideCommands(commands)

        this.packageFactory = pkgFactory
        this.projectFileProvider = projectFileProvider
        this.queryRuntimeHelperFactory = queryRuntimeHelperFactory
        this.moduleInvocationPolicy = moduleInvocationPolicy

        this.ruleClassProvider = ruleClassProvider
        this.infoItems = infoItems
        this.actionKeyContext = actionKeyContext
        this.clock = clock
        this.abruptShutdownHandler = abruptShutdownHandler
        this.startupOptionsProvider = startupOptionsProvider
        this.queryEnvironmentFactory = queryEnvironmentFactory
        this.queryFunctions = queryFunctions
        this.queryOutputFormatters = queryOutputFormatters
        this.eventBusExceptionHandler = eventBusExceptionHandler
        this.bugReporter = bugReporter

        com.google.devtools.common.options.CommandNameCache.CommandNameCacheInstance.INSTANCE.setCommandNameCache(
            CommandNameCacheImpl(commandMap)
        )
        this.productName = productName
        this.buildEventArtifactUploaderFactoryMap = buildEventArtifactUploaderFactoryMap
        this.repositoryRemoteHelpersFactory = repositoryRemoteHelpersFactory
        this.instrumentationOutputFactory = instrumentationOutputFactory
        this.installBaseLock = installBaseLock
        this.cgroupsInfo = VirtualCgroup.getInstance().cgroupsInfo()
    }

    @Throws(AbruptExitException::class)
    fun initWorkspace(directories: BlazeDirectories?, binTools: BinTools?): BlazeWorkspace {
        com.google.common.base.Preconditions.checkState(this.workspace == null)
        val builder: WorkspaceBuilder = WorkspaceBuilder(directories, binTools)
        for (module in blazeModules) {
            module.workspaceInit(this, directories, builder)
        }
        this.workspace = builder.build(this, packageFactory, eventBusExceptionHandler)
        return workspace
    }

    fun getCoverageReportActionFactory(
        commandOptions: com.google.devtools.common.options.OptionsProvider?
    ): CoverageReportActionFactory? {
        var firstFactory: CoverageReportActionFactory? = null
        for (module in blazeModules) {
            val factory: CoverageReportActionFactory? = module.getCoverageReportFactory(commandOptions)
            if (factory != null) {
                com.google.common.base.Preconditions.checkState(
                    firstFactory == null, "only one Bazel Module can have a Coverage Report Factory"
                )
                firstFactory = factory
            }
        }
        return firstFactory
    }

    /**
     * Adds the given command under the given name to the map of commands.
     * 
     * @throws AssertionError if the name is already used by another command.
     */
    private fun addCommand(command: BlazeCommand) {
        val name: String? = command.getClass()
            .getAnnotation<com.google.devtools.build.lib.runtime.Command?>(com.google.devtools.build.lib.runtime.Command::class.java).name
        check(!commandMap.containsKey(name)) { "Command name or alias " + name + " is already used." }
        commandMap.put(name, command)
    }

    @com.google.common.annotations.VisibleForTesting
    fun overrideCommands(commands: Iterable<BlazeCommand>) {
        commandMap.clear()
        for (command in commands) {
            addCommand(command)
        }
    }

    fun getModuleInvocationPolicy(): InvocationPolicy? {
        return moduleInvocationPolicy
    }

    @Throws(IOException::class)
    private fun newUploader(
        env: CommandEnvironment?, buildEventUploadStrategy: String?
    ): BuildEventArtifactUploader? {
        return buildEventArtifactUploaderFactoryMap.select(buildEventUploadStrategy).create(env)
    }

    /** Configure profiling based on the provided options.  */
    fun initProfiler(
        tracerEnabled: Boolean,
        eventHandler: ExtendedEventHandler,
        workspace: BlazeWorkspace,
        options: com.google.devtools.common.options.OptionsProvider,
        env: CommandEnvironment,
        execStartTimeNanos: Long,
        waitTimeInMs: Long
    ): ProfilerStartedEvent {
        val bepOptions: BuildEventProtocolOptions? = options.getOptions<O?>(BuildEventProtocolOptions::class.java)
        val commandOptions: CommonCommandOptions? =
            options.getOptions<CommonCommandOptions?>(CommonCommandOptions::class.java)
        var out: java.io.OutputStream? = null
        var recordFullProfilerData: Boolean = commandOptions.getRecordFullProfilerData()
        val profiledTasksBuilder: com.google.common.collect.ImmutableSet.Builder<ProfilerTask?> =
            com.google.common.collect.ImmutableSet.builder<ProfilerTask?>()
        var format: com.google.devtools.build.lib.profiler.TraceProfilerService.Format =
            TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT
        var profile: InstrumentationOutput? = null
        try {
            if (tracerEnabled) {
                if (commandOptions.getProfilePath() == null) {
                    val profileName = "command.profile.gz"
                    format = TraceProfilerService.Format.JSON_TRACE_FILE_COMPRESSED_FORMAT
                    if (bepOptions != null && bepOptions.streamingLogFileUploads) {
                        profile =
                            instrumentationOutputFactory.createBuildEventArtifactInstrumentationOutput(
                                profileName, newUploader(env, bepOptions.buildEventUploadStrategy)
                            )
                    } else if (commandOptions.getRedirectLocalInstrumentationOutputWrites()) {
                        profile =
                            instrumentationOutputFactory.createInstrumentationOutput(
                                profileName,
                                PathFragment.create(profileName),
                                DestinationRelativeTo.OUTPUT_BASE,
                                env,
                                eventHandler,  /* append= */
                                null,  /* internal= */
                                null
                            )
                    } else {
                        val profilePath: com.google.devtools.build.lib.vfs.Path =
                            manageProfiles(
                                workspace.getOutputBase(),
                                env.getCommandId().toString(),
                                commandOptions.getProfilesToRetain()
                            )
                        profile =
                            instrumentationOutputFactory.createLocalOutputWithConvenientName(
                                profileName, profilePath,  /* convenienceName= */profileName
                            )
                    }
                } else {
                    format =
                        if (commandOptions.getProfilePath().toString().endsWith(".gz"))
                            TraceProfilerService.Format.JSON_TRACE_FILE_COMPRESSED_FORMAT
                        else
                            TraceProfilerService.Format.JSON_TRACE_FILE_FORMAT
                    profile =
                        instrumentationOutputFactory.createInstrumentationOutput(
                            if (format == TraceProfilerService.Format.JSON_TRACE_FILE_COMPRESSED_FORMAT)
                                "command.profile.gz"
                            else
                                "command.profile.json",  /* destination= */
                            commandOptions.getProfilePath(),
                            DestinationRelativeTo.WORKSPACE_OR_HOME,
                            env,
                            eventHandler,  /* append= */
                            false,  /* internal= */
                            true
                        )
                }
                out = profile.createOutputStream()
                for (profilerTask in ProfilerTask.entries) {
                    if (!profilerTask.isVfs // CRITICAL_PATH corresponds to writing the file.
                        && profilerTask != ProfilerTask.CRITICAL_PATH && profilerTask != ProfilerTask.SKYFUNCTION
                    ) {
                        profiledTasksBuilder.add(profilerTask)
                    }
                }
                profiledTasksBuilder.addAll(commandOptions.getAdditionalProfileTasks())
                if (commandOptions.getRecordFullProfilerData()) {
                    profiledTasksBuilder.addAll(EnumSet.allOf<ProfilerTask?>(ProfilerTask::class.java))
                }
            } else if (commandOptions.getAlwaysProfileSlowOperations()) {
                recordFullProfilerData = false
                out = null
                for (profilerTask in ProfilerTask.entries) {
                    if (profilerTask.collectsSlowestInstances()) {
                        profiledTasksBuilder.add(profilerTask)
                    }
                }
            }
            val profiledTasks: com.google.common.collect.ImmutableSet<ProfilerTask?> = profiledTasksBuilder.build()
            if (!profiledTasks.isEmpty()) {
                if (commandOptions.getSlimProfile() && commandOptions.getIncludePrimaryOutput()) {
                    eventHandler.handle(
                        com.google.devtools.build.lib.events.Event.warn(
                            ("Enabling both --slim_profile and"
                                    + " --experimental_profile_include_primary_output: the \"out\" field"
                                    + " will be omitted in merged actions.")
                        )
                    )
                }
                val workerProcessMetricsCollector: WorkerProcessMetricsCollector =
                    WorkerProcessMetricsCollector.instance()
                workerProcessMetricsCollector.setClock(clock)

                val localResourceUsageCollectors: LocalResourceUsageCollectors =
                    LocalResourceUsageCollectors(
                        bugReporter,
                        if (commandOptions.getCollectSkyframeCounts())
                            env.getSkyframeExecutor().getEvaluator().getInMemoryGraph()
                        else
                            null,
                        workerProcessMetricsCollector,
                        env.getLocalResourceManager(),
                        getBlazeService<T?>(SystemNetworkStatsService::class.java)
                    )

                localResourceUsageCollectors.addCollectors( /* collectWorkerDataInProfiler= */
                    commandOptions.getCollectWorkerDataInProfiler(),  /* collectLoadAverage= */
                    commandOptions.getCollectLoadAverageInProfiler(),  /* collectSystemNetworkUsage= */
                    commandOptions.getCollectSystemNetworkUsage(),  /* collectResourceManagerEstimation= */
                    commandOptions.getCollectResourceEstimation(),  /* collectPressureStallIndicators= */
                    commandOptions
                        .getCollectPressureStallIndicators(),  /* collectSkyframeCounts= */
                    commandOptions.getCollectSkyframeCounts()
                )

                // TODO(b/457644247): Encapsulate the start params into a config object.
                com.google.devtools.build.lib.profiler.Profiler.instance()
                    .start(
                        profiledTasks,
                        out,
                        format,
                        workspace.getOutputBase().toString(),
                        env.getCommandId(),
                        recordFullProfilerData,
                        clock,
                        execStartTimeNanos,  /* slimProfile= */
                        commandOptions.getSlimProfile(),  /* includePrimaryOutput= */
                        commandOptions.getIncludePrimaryOutput(),  /* includeTargetLabel= */
                        commandOptions.getProfileIncludeTargetLabel(),  /* includeConfiguration= */
                        commandOptions.getProfileIncludeTargetConfiguration(),  /* collectTaskHistograms= */
                        commandOptions.getAlwaysProfileSlowOperations()
                    )

                // Instead of logEvent() we're calling the low level function to pass the timings we took in
                // the launcher. We're setting the INIT phase marker so that it follows immediately the
                // LAUNCH phase.
                val startupTimeNanos: Long = commandOptions.getStartupTime() * 1000000L
                val waitTimeNanos = waitTimeInMs * 1000000L
                val clientStartTimeNanos = execStartTimeNanos - startupTimeNanos - waitTimeNanos
                com.google.devtools.build.lib.profiler.Profiler.instance()
                    .logSimpleTaskDuration(
                        clientStartTimeNanos,
                        java.time.Duration.ofNanos(startupTimeNanos),
                        ProfilerTask.PHASE,
                        ProfilePhase.LAUNCH.description
                    )
                if (commandOptions.getExtractDataTime() > 0) {
                    com.google.devtools.build.lib.profiler.Profiler.instance()
                        .logSimpleTaskDuration(
                            clientStartTimeNanos,
                            java.time.Duration.ofMillis(commandOptions.getExtractDataTime()),
                            ProfilerTask.PHASE,
                            "Extracting Bazel binary"
                        )
                }
                if (commandOptions.getWaitTime() > 0) {
                    com.google.devtools.build.lib.profiler.Profiler.instance()
                        .logSimpleTaskDuration(
                            clientStartTimeNanos,
                            java.time.Duration.ofMillis(commandOptions.getWaitTime()),
                            ProfilerTask.PHASE,
                            "Blocking on busy Bazel server (in client)"
                        )
                }
                if (waitTimeInMs > 0) {
                    com.google.devtools.build.lib.profiler.Profiler.instance()
                        .logSimpleTaskDuration(
                            clientStartTimeNanos + startupTimeNanos,
                            java.time.Duration.ofMillis(waitTimeInMs),
                            ProfilerTask.PHASE,
                            "Blocking on busy Bazel server (in server)"
                        )
                }
                com.google.devtools.build.lib.profiler.Profiler.instance()
                    .logSimpleTaskDuration(
                        execStartTimeNanos,
                        java.time.Duration.ZERO,
                        ProfilerTask.PHASE,
                        ProfilePhase.INIT.description
                    )
            }
        } catch (e: IOException) {
            eventHandler.handle(com.google.devtools.build.lib.events.Event.error("Error while creating profile file: " + e.getMessage()))
            profile = null
        }
        return ProfilerStartedEvent(profile)
    }

    fun getFileSystem(): com.google.devtools.build.lib.vfs.FileSystem? {
        return fileSystem
    }

    fun getWorkspace(): BlazeWorkspace? {
        return workspace
    }

    fun getActionKeyContext(): ActionKeyContext {
        return actionKeyContext
    }

    val serverDirectory: com.google.devtools.build.lib.vfs.Path
        /** The directory in which blaze stores the server state - that is, the socket file and a log.  */
        get() = workspace.getDirectories().getOutputBase().getChild("server")

    /**
     * Returns the [QueryEnvironmentFactory] that should be used to create a [ ], whenever one is
     * needed.
     */
    fun getQueryEnvironmentFactory(): QueryEnvironmentFactory? {
        return queryEnvironmentFactory
    }

    fun getQueryFunctions(): com.google.common.collect.ImmutableList<QueryFunction?>? {
        return queryFunctions
    }

    fun getQueryOutputFormatters(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.query2.query.output.OutputFormatter?>? {
        return queryOutputFormatters
    }

    /** Returns the package factory.  */
    fun getPackageFactory(): PackageFactory? {
        return packageFactory
    }

    /** Returns the rule class provider.  */
    fun getRuleClassProvider(): ConfiguredRuleClassProvider {
        return ruleClassProvider
    }

    fun getInfoItems(): com.google.common.collect.ImmutableMap<String?, InfoItem?>? {
        return infoItems
    }

    fun getBlazeModules(): Iterable<BlazeModule> {
        return blazeModules
    }

    val optionsSuppliers: Iterable<com.google.devtools.build.lib.runtime.OptionsSupplier>
        get() = com.google.common.collect.Iterables.concat<com.google.devtools.build.lib.runtime.OptionsSupplier?>(
            blazeModules,
            blazeServices
        )

    /**
     * Returns the first [BlazeModule] that is an instance of a given class or interface.
     * 
     * @param moduleClass a class or interface that we want to match to a module
     * @param <T> the type of the module's class
     * @return a module that is an instance of the given class or interface, or null if no such module
     * exists
    </T> */
    fun <T> getBlazeModule(moduleClass: java.lang.Class<T?>): T? {
        for (module in blazeModules) {
            if (moduleClass.isInstance(module)) {
                return moduleClass.cast(module)
            }
        }
        return null
    }

    /**
     * Returns the first [BlazeService] that is an instance of a given class or interface.
     * 
     * @param serviceClass a class or interface that we want to match to a service
     * @param <T> the type of the service's class
     * @return a service that is an instance of the given class or interface, or null if no such
     * service exists
    </T> */
    fun <T : com.google.devtools.build.lib.runtime.BlazeService?> getBlazeService(serviceClass: java.lang.Class<T?>): T? {
        for (service in blazeServices) {
            if (serviceClass.isInstance(service)) {
                return serviceClass.cast(service)
            }
        }
        return null
    }

    /**
     * Returns a provider for project file objects. Can be null if no such provider was set by any of
     * the modules.
     */
    fun getProjectFileProvider(): com.google.devtools.build.lib.runtime.ProjectFile.Provider? {
        return projectFileProvider
    }

    fun getQueryRuntimeHelperFactory(): com.google.devtools.build.lib.runtime.QueryRuntimeHelper.Factory? {
        return queryRuntimeHelperFactory
    }

    /**
     * Hook method called by the BlazeCommandDispatcher prior to the dispatch of each command.
     * 
     * @param options The CommonCommandOptions used by every command.
     */
    fun beforeCommand(env: CommandEnvironment, options: CommonCommandOptions) {
        this.env = env
        if (options.getMemoryProfilePath() != null) {
            val memoryProfilePath: com.google.devtools.build.lib.vfs.Path =
                env.getWorkingDirectory().getRelative(options.getMemoryProfilePath())
            MemoryProfiler.instance()
                .setStableMemoryParameters(
                    options.getMemoryProfileStableHeapParameters(),
                    env.getOptions()
                        .getOptions<MemoryPressureOptions?>(MemoryPressureOptions::class.java)
                        .getJvmHeapHistogramInternalObjectPattern()
                        .regexPattern()
                )
            try {
                MemoryProfiler.instance().start(memoryProfilePath.getOutputStream())
            } catch (e: IOException) {
                env.getReporter()
                    .handle(com.google.devtools.build.lib.events.Event.error("Error while creating memory profile file: " + e.getMessage()))
            }
        }

        val stateKeptAfterBuild =
            env.getCommandName() != "clean" && env.getOptions()
                .getOptions<KeepStateAfterBuildOption?>(KeepStateAfterBuildOption::class.java)
                .getKeepStateAfterBuild()
        env.addIdleTask(GcAndInternerShrinkingIdleTask(stateKeptAfterBuild))

        if (options.getInstallBaseGcMaxAge() != null && !options.getInstallBaseGcMaxAge().isZero()) {
            env.addIdleTask(
                InstallBaseGarbageCollectorIdleTask.create(
                    workspace.getDirectories().getInstallBase(), options.getInstallBaseGcMaxAge()
                )
            )
        }

        if (options.getActionCacheGcMaxAge() != null && !options.getActionCacheGcMaxAge().isZero()) {
            env.addIdleTask(
                workspace.getActionCacheGcIdleTask(
                    options.getActionCacheGcIdleDelay(),
                    options.getActionCacheGcThreshold() / 100.0f,
                    options.getActionCacheGcMaxAge()
                )
            )
        }
    }

    public override fun cleanUpForCrash(exitCode: DetailedExitCode) {
        logger.atInfo().log("Cleaning up in crash: %s", exitCode)
        if (declareExitCode(exitCode)) {
            val localEnv: CommandEnvironment? = env
            if (localEnv != null) {
                localEnv.notifyOnCrash(
                    productName + " is crashing: " + exitCode.getFailureDetail().getMessage()
                )
            }
            // Only try to publish events if we won the exit code race. Otherwise someone else is already
            // exiting for us.
            if (workspace == null) {
                return  // A crash during server startup.
            }
            val eventBus: com.google.common.eventbus.EventBus? = workspace.getSkyframeExecutor().getEventBus()
            if (eventBus != null) {
                workspace
                    .getSkyframeExecutor()
                    .postLoggingStatsWhenCrashing(
                        object : ExtendedEventHandler() {
                            override fun post(obj: Postable) {
                                eventBus.post(obj)
                            }

                            override fun handle(event: com.google.devtools.build.lib.events.Event?) {}
                        })
                eventBus.post(CrashEvent())
                eventBus.post(CommandCompleteEvent(exitCode))
            }
        }
        // We don't call #shutDown() here because all it does is shut down the modules, and who knows if
        // they can be trusted.  Instead, we call runtime#shutdownOnCrash() which attempts to cleanly
        // shut down those modules that might have something pending to do as a best-effort operation.
        shutDownModulesOnCrash(exitCode)
    }

    val crashExitCode: DetailedExitCode?
        get() = storedExitCode.get()

    private fun declareExitCode(detailedExitCode: DetailedExitCode?): Boolean {
        return storedExitCode.compareAndSet(null, detailedExitCode)
    }

    /**
     * Posts the [CommandCompleteEvent], so that listeners can tidy up. Called by [ ][.afterCommand], and by BugReport when crashing from an exception in an async thread.
     * 
     * 
     * Returns null if `exitCode` was registered as the exit code, and the [ ] to use if another thread already registered an exit code.
     */
    private fun notifyCommandComplete(exitCode: DetailedExitCode?): DetailedExitCode? {
        if (!declareExitCode(exitCode)) {
            // This command has already been called, presumably because there is a race between the main
            // thread and a worker thread that crashed. Don't try to arbitrate the dispute. If the main
            // thread won the race (unlikely, but possible), this may be incorrectly logged as a success.
            return storedExitCode.get()
        }
        workspace.getSkyframeExecutor().getEventBus().post(CommandCompleteEvent(exitCode))
        return null
    }

    /**
     * Hook method called by the BlazeCommandDispatcher after the dispatch of each command. Returns a
     * new exit code in case exceptions were encountered during cleanup.
     * 
     * @param forceKeepStateForTesting ensure that Skyframe state is not cleared despite what the
     * command line says. This is useful for some tests that exercise `--nokeep_state_after_build` but still want to make assertions over said state. Should only
     * ever be true for tests.
     */
    @com.google.common.annotations.VisibleForTesting
    fun afterCommand(
        forceKeepStateForTesting: Boolean, env: CommandEnvironment, commandResult: BlazeCommandResult
    ): BlazeCommandResult {
        this.env = null

        // Remove any filters that the command might have added to the reporter.
        env.getReporter().setOutputFilter(com.google.devtools.build.lib.events.OutputFilter.OUTPUT_EVERYTHING)

        var moduleExitCode: DetailedExitCode? = null

        try {
            workspace.getSkyframeExecutor().notifyCommandComplete(env.getReporter())
        } catch (e: java.lang.InterruptedException) {
            logger.atInfo().withCause(e).log("Interrupted in afterCommand")
            moduleExitCode =
                DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                    moduleExitCode,
                    InterruptedFailureDetails.detailedExitCode("executor completion interrupted")
                )
            java.lang.Thread.currentThread().interrupt()
        }

        // Ensure deterministic ordering of doing the metrics upload before everything else that
        // happens when BuildCompleteEvent is posted.
        env.getEventBus().post(CommandPrecompleteEvent())

        for (module in blazeModules) {
            try {
                com.google.devtools.build.lib.profiler.Profiler.instance().profile(module.toString() + ".afterCommand")
                    .use { c ->
                        module.afterCommand()
                    }
            } catch (e: AbruptExitException) {
                env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                logger.atWarning().withCause(e).log("While running afterCommand() on %s", module)
                moduleExitCode = DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                    moduleExitCode,
                    e.getDetailedExitCode()
                )
            }
        }

        env.getEventBus().post(AfterCommandEvent())

        // Wipe the dependency graph if requested. Note that this method always runs at the end of
        // a commands unless the server crashes, in which case no inmemory state will linger for the
        // next build anyway.
        val keepStateAfterBuildOption: KeepStateAfterBuildOption? =
            env.getOptions().getOptions<KeepStateAfterBuildOption?>(KeepStateAfterBuildOption::class.java)
        if (!keepStateAfterBuildOption.getKeepStateAfterBuild() && !forceKeepStateForTesting) {
            workspace.getSkyframeExecutor().resetEvaluator()
        }

        var finalCommandResult: BlazeCommandResult?
        if (!commandResult.getExitCode().isInfrastructureFailure() && moduleExitCode != null) {
            if (commandResult.getExecRequest() != null) {
                finalCommandResult =
                    BlazeCommandResult.Companion.execute(commandResult.getExecRequest(), moduleExitCode)
            } else {
                finalCommandResult = BlazeCommandResult.Companion.detailedExitCode(moduleExitCode)
            }
        } else {
            finalCommandResult = commandResult
        }
        val otherThreadWonExitCode: DetailedExitCode? =
            notifyCommandComplete(finalCommandResult.getDetailedExitCode())
        if (otherThreadWonExitCode != null) {
            finalCommandResult = BlazeCommandResult.Companion.detailedExitCode(otherThreadWonExitCode)
        }
        env.getBlazeWorkspace().clearEventBus()

        // Some module's commandComplete() relies on the stoppage of profiler. And it is impossible the
        // profiler is needed after all `BlazeModule.afterCommand`s are executed.
        // See b/331203854#comment124 for more details.
        try {
            com.google.devtools.build.lib.profiler.Profiler.instance().stop()
        } catch (e: IOException) {
            env.getReporter()
                .handle(com.google.devtools.build.lib.events.Event.error("Error while writing profile file: " + e.getMessage()))
        }

        for (module in blazeModules) {
            com.google.devtools.build.lib.profiler.Profiler.instance().profile(module.toString() + ".commandComplete")
                .use { closeable ->
                    module.commandComplete()
                }
        }

        val idleTasks: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask?> =
            env.getIdleTasks()
        if (!idleTasks.isEmpty()) {
            finalCommandResult = BlazeCommandResult.Companion.withIdleTasks(finalCommandResult, idleTasks)
        }

        env.getReporter().cleanup()
        actionKeyContext.clear()
        DebugLoggerConfigurator.flushServerLog()
        storedExitCode.set(null)
        return BlazeCommandResult.Companion.withResponseExtensions(
            finalCommandResult, env.getResponseExtensions()
        )
    }

    /**
     * Returns the Clock-instance used for the entire build. Before, individual classes (such as
     * Profiler) used to specify the type of clock (e.g. EpochClock) they wanted to use. This made it
     * difficult to get Blaze working on Windows as some of the clocks available for Linux aren't
     * (directly) available on Windows. Setting the Blaze-wide clock upon construction of BlazeRuntime
     * allows injecting whatever Clock instance should be used from BlazeMain.
     * 
     * @return The Blaze-wide clock
     */
    fun getClock(): com.google.devtools.build.lib.clock.Clock {
        return clock
    }

    /**
     * Returns the [BugReporter] that should be used when filing bug reports, if possible. Use
     * this in preference to [BugReport.sendBugReport] for ease of testing codepaths that file
     * bug reports.
     */
    fun getBugReporter(): BugReporter? {
        return bugReporter
    }

    fun getStartupOptionsProvider(): com.google.devtools.common.options.OptionsParsingResult {
        return startupOptionsProvider
    }

    fun getCommandMap(): MutableMap<String?, BlazeCommand?> {
        return commandMap
    }

    /** Invokes [BlazeModule.blazeShutdown] on all registered modules.  */
    fun shutdown() {
        try {
            for (module in blazeModules) {
                module.blazeShutdown()
            }
        } finally {
            DebugLoggerConfigurator.flushServerLog()
        }
    }

    fun prepareForAbruptShutdown() {
        if (abruptShutdownHandler != null) {
            abruptShutdownHandler.run()
        }
    }

    /** Invokes [BlazeModule.blazeShutdownOnCrash] on all registered modules.  */
    private fun shutDownModulesOnCrash(exitCode: DetailedExitCode?) {
        // TODO(b/167592709): remove verbose logging when bug resolved.
        logger.atInfo().log("Shutting down modules on crash: %s", blazeModules)
        try {
            for (module in blazeModules) {
                logger.atInfo().log("Shutting down %s on crash", module)
                module.blazeShutdownOnCrash(exitCode)
            }
        } finally {
            DebugLoggerConfigurator.flushServerLog()
        }
    }

    /** Creates a BuildOptions class for the given options taken from an [OptionsProvider].  */
    fun createBuildOptions(optionsProvider: com.google.devtools.common.options.OptionsProvider?): BuildOptions {
        return BuildOptions.of(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses(), optionsProvider
        )
    }

    /** Command line options split in to two parts: startup options and everything else.  */
    @com.google.common.annotations.VisibleForTesting
    internal class CommandLineOptions(
        startupArgs: com.google.common.collect.ImmutableList<String?>?,
        otherArgs: com.google.common.collect.ImmutableList<String?>?
    ) {
        private val startupArgs: com.google.common.collect.ImmutableList<String?>
        private val otherArgs: com.google.common.collect.ImmutableList<String?>

        init {
            this.startupArgs =
                com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<String?>>(
                    startupArgs
                )
            this.otherArgs =
                com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<String?>>(
                    otherArgs
                )
        }

        fun getStartupArgs(): com.google.common.collect.ImmutableList<String?> {
            return startupArgs
        }

        fun getOtherArgs(): com.google.common.collect.ImmutableList<String?> {
            return otherArgs
        }
    }

    override fun fillInCrashContext(ctx: CrashContext) {
        val localEnv: CommandEnvironment? = env
        if (localEnv == null) {
            return
        }
        val options: CommonCommandOptions? =
            localEnv.getOptions().getOptions<CommonCommandOptions?>(CommonCommandOptions::class.java)
        if (options.getHeapDumpOnOom()) {
            ctx.heapDumpPath = workspace
                .getOutputBase()
                .getRelative(env.getCommandId().toString() + ".heapdump.hprof") // Must end in .hprof.
                .getPathString()
        }
        ctx.withExtraOomInfo(options.getOomMessage()).reportingTo(localEnv.getReporter())
    }

    fun getBuildEventArtifactUploaderFactoryMap(): BuildEventArtifactUploaderFactoryMap {
        return buildEventArtifactUploaderFactoryMap
    }

    val repositoryHelpersFactory: RepositoryRemoteHelpersFactory?
        get() = repositoryRemoteHelpersFactory

    fun getInstrumentationOutputFactory(): InstrumentationOutputFactory {
        return instrumentationOutputFactory
    }

    fun getCgroupsInfo(): CgroupsInfo? {
        return cgroupsInfo
    }

    /**
     * A builder for [BlazeRuntime] objects. The only required fields are the [ ], and the [com.google.devtools.build.lib.packages.RuleClassProvider]
     * (except for testing). All other fields have safe default values.
     * 
     * 
     * The default behavior of the BlazeRuntime's EventBus is to exit the JVM when a subscriber
     * throws an exception. Please plan appropriately.
     */
    class Builder {
        private var fileSystem: com.google.devtools.build.lib.vfs.FileSystem? = null
        private var serverDirectories: ServerDirectories? = null
        private var clock: com.google.devtools.build.lib.clock.Clock? = null
        private var abruptShutdownHandler: java.lang.Runnable? = null
        private var startupOptionsProvider: com.google.devtools.common.options.OptionsParsingResult? = null
        private val blazeModules: java.util.ArrayList<BlazeModule> = java.util.ArrayList<BlazeModule>()
        private val blazeServices: java.util.ArrayList<com.google.devtools.build.lib.runtime.BlazeService> =
            java.util.ArrayList<com.google.devtools.build.lib.runtime.BlazeService>()
        private var eventBusExceptionHandler: com.google.common.eventbus.SubscriberExceptionHandler? =
            com.google.common.eventbus.SubscriberExceptionHandler { throwable: Throwable?, context: com.google.common.eventbus.SubscriberExceptionContext? ->
                BugReport.handleCrash(throwable)
            }
        private var instanceId: UUID? = null
        private var productName: String? = null
        private var actionKeyContext: ActionKeyContext? = null
        private var bugReporter: BugReporter? = BugReporter.defaultInstance()
        private var installBaseLock: FileSystemLock? = null

        @com.google.common.annotations.VisibleForTesting
        @Throws(AbruptExitException::class)
        fun build(): BlazeRuntime {
            com.google.common.base.Preconditions.checkNotNull<String?>(productName)
            com.google.common.base.Preconditions.checkNotNull<Any?>(serverDirectories)
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.common.options.OptionsParsingResult?>(
                startupOptionsProvider
            )
            val actionKeyContext: ActionKeyContext =
                if (this.actionKeyContext != null) this.actionKeyContext else ActionKeyContext()
            val clock: com.google.devtools.build.lib.clock.Clock =
                if (this.clock == null) com.google.devtools.build.lib.clock.BlazeClock.instance() else this.clock
            val instanceId: UUID = if (this.instanceId == null) UUID.randomUUID() else this.instanceId

            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.clock.Clock?>(clock)

            var metricsModule: BlazeModule? = null
            for (module in blazeModules) {
                if (module.postsBuildMetricsEvent()) {
                    com.google.common.base.Preconditions.checkState(
                        metricsModule == null,
                        "more than one module may post a BuildMetricsEvent"
                    )
                    metricsModule = module
                }
            }

            for (module in blazeModules) {
                module.blazeStartup(
                    startupOptionsProvider,
                    BlazeVersionInfo.instance(),
                    instanceId,
                    fileSystem,
                    serverDirectories,
                    clock
                )
            }
            val serverBuilder: com.google.devtools.build.lib.runtime.ServerBuilder =
                com.google.devtools.build.lib.runtime.ServerBuilder()
            serverBuilder.addQueryOutputFormatters(com.google.devtools.build.lib.query2.query.output.OutputFormatters.defaultFormatters)
            serverBuilder
                .getInstrumentationOutputFactoryBuilder()
                .setLocalInstrumentationOutputBuilderSupplier(java.util.function.Supplier { com.google.devtools.build.lib.runtime.LocalInstrumentationOutput.Builder() })
            serverBuilder
                .getInstrumentationOutputFactoryBuilder()
                .setBuildEventArtifactInstrumentationOutputBuilderSupplier(
                    java.util.function.Supplier { com.google.devtools.build.lib.runtime.BuildEventArtifactInstrumentationOutput.Builder() })
            for (module in blazeModules) {
                module.serverInit(startupOptionsProvider, serverBuilder)
            }

            val ruleClassBuilder: ConfiguredRuleClassProvider.Builder =
                Builder()
            for (module in blazeModules) {
                module.initializeRuleClasses(ruleClassBuilder)
            }

            val ruleClassProvider: ConfiguredRuleClassProvider = ruleClassBuilder.build()

            val packageSettings: PackageSettings? =
                com.google.devtools.build.lib.runtime.BlazeRuntime.Builder.Companion.getPackageSettings(blazeModules)
            val packageFactory: PackageFactory =
                PackageFactory(
                    ruleClassProvider,
                    PackageFactory.makeDefaultSizedForkJoinPoolForGlobbing(),
                    packageSettings,
                    com.google.devtools.build.lib.runtime.BlazeRuntime.Builder.Companion.getPackageValidator(
                        blazeModules
                    ),
                    com.google.devtools.build.lib.runtime.BlazeRuntime.Builder.Companion.getPackageOverheadEstimator(
                        blazeModules
                    ),
                    getPackageLoadingListener(
                        blazeModules, packageSettings, ruleClassProvider, fileSystem
                    )
                )

            var projectFileProvider: com.google.devtools.build.lib.runtime.ProjectFile.Provider? = null
            for (module in blazeModules) {
                val candidate: com.google.devtools.build.lib.runtime.ProjectFile.Provider? =
                    module.createProjectFileProvider()
                if (candidate != null) {
                    com.google.common.base.Preconditions.checkState(
                        projectFileProvider == null, "more than one module defines a project file provider"
                    )
                    projectFileProvider = candidate
                }
            }

            var queryRuntimeHelperFactory: com.google.devtools.build.lib.runtime.QueryRuntimeHelper.Factory? = null
            for (module in blazeModules) {
                val candidateFactory: com.google.devtools.build.lib.runtime.QueryRuntimeHelper.Factory? =
                    module.getQueryRuntimeHelperFactory()
                if (candidateFactory != null) {
                    com.google.common.base.Preconditions.checkState(
                        queryRuntimeHelperFactory == null,
                        "more than one module defines a query helper factory"
                    )
                    queryRuntimeHelperFactory = candidateFactory
                }
            }
            if (queryRuntimeHelperFactory == null) {
                queryRuntimeHelperFactory = StdoutQueryRuntimeHelperFactory.Companion.INSTANCE
            }

            val runtime =
                BlazeRuntime(
                    fileSystem,
                    serverBuilder.getQueryEnvironmentFactory(),
                    serverBuilder.getQueryFunctions(),
                    serverBuilder.getQueryOutputFormatters(),
                    packageFactory,
                    ruleClassProvider,
                    serverBuilder.getInfoItems(),
                    actionKeyContext,
                    clock,
                    abruptShutdownHandler,
                    startupOptionsProvider,
                    com.google.common.collect.ImmutableList.copyOf<BlazeModule?>(blazeModules),
                    com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.runtime.BlazeService?>(
                        blazeServices
                    ),
                    eventBusExceptionHandler,
                    bugReporter,
                    projectFileProvider,
                    queryRuntimeHelperFactory,
                    serverBuilder.getInvocationPolicy(),
                    serverBuilder.getCommands(),
                    productName,
                    serverBuilder.getBuildEventArtifactUploaderMap(),
                    serverBuilder.getRepositoryHelpersFactory(),
                    serverBuilder.createInstrumentationOutputFactory(),
                    installBaseLock
                )
            BugReport.setRuntime(runtime)
            return runtime
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setProductName(productName: String?): Builder {
            this.productName = productName
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setFileSystem(fileSystem: com.google.devtools.build.lib.vfs.FileSystem?): Builder {
            this.fileSystem = fileSystem
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setServerDirectories(serverDirectories: ServerDirectories?): Builder {
            this.serverDirectories = serverDirectories
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setClock(clock: com.google.devtools.build.lib.clock.Clock?): Builder {
            this.clock = clock
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setAbruptShutdownHandler(handler: java.lang.Runnable?): Builder {
            this.abruptShutdownHandler = handler
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setStartupOptionsProvider(startupOptionsProvider: com.google.devtools.common.options.OptionsParsingResult): Builder {
            this.startupOptionsProvider = startupOptionsProvider
            return this
        }

        fun getStartupOptionsProvider(): com.google.devtools.common.options.OptionsParsingResult {
            return startupOptionsProvider
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addBlazeModule(blazeModule: BlazeModule?): Builder {
            blazeModules.add(blazeModule)
            return this
        }

        fun getBlazeModules(): com.google.common.collect.ImmutableList<BlazeModule?> {
            return com.google.common.collect.ImmutableList.copyOf<BlazeModule?>(blazeModules)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addBlazeService(blazeService: com.google.devtools.build.lib.runtime.BlazeService?): Builder {
            blazeServices.add(blazeService)
            return this
        }

        fun getBlazeServices(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.runtime.BlazeService?> {
            return com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.runtime.BlazeService?>(
                blazeServices
            )
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun <T : com.google.devtools.build.lib.runtime.BlazeService?> overrideBlazeServiceForTesting(
            clazz: java.lang.Class<T?>, blazeService: com.google.devtools.build.lib.runtime.BlazeService?
        ): Builder {
            var index = -1
            for (i in blazeServices.indices) {
                if (clazz.isInstance(blazeServices.get(i))) {
                    index = i
                    break
                }
            }
            if (index == -1) {
                blazeServices.add(blazeService)
            } else {
                blazeServices.set(index, blazeService)
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setInstanceId(id: UUID?): Builder {
            instanceId = id
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @com.google.common.annotations.VisibleForTesting
        fun setEventBusExceptionHandler(
            eventBusExceptionHandler: com.google.common.eventbus.SubscriberExceptionHandler?
        ): Builder {
            this.eventBusExceptionHandler = eventBusExceptionHandler
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setInstallBaseLock(installBaseLock: FileSystemLock?): Builder {
            this.installBaseLock = installBaseLock
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @com.google.common.annotations.VisibleForTesting
        fun setBugReporter(bugReporter: BugReporter?): Builder {
            this.bugReporter = bugReporter
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setActionKeyContext(actionKeyContext: ActionKeyContext?): Builder {
            this.actionKeyContext = actionKeyContext
            return this
        }

        companion object {
            private fun getPackageSettings(blazeModules: MutableList<BlazeModule>): PackageSettings? {
                val packageSettingss: MutableList<PackageSettings?> =
                    blazeModules.stream()
                        .map<PackageSettings?>(java.util.function.Function { obj: BlazeModule? -> obj.getPackageSettings() })
                        .filter(java.util.function.Predicate { obj: PackageSettings? -> java.util.Objects.nonNull(obj) })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<PackageSettings?>())
                com.google.common.base.Preconditions.checkState(
                    packageSettingss.size() <= 1, "more than one module defines a PackageSettings"
                )
                return com.google.common.collect.Iterables.getFirst<PackageSettings?>(
                    packageSettingss,
                    PackageSettings.DEFAULTS
                )
            }

            private fun getPackageValidator(blazeModules: MutableList<BlazeModule>): PackageValidator? {
                val packageValidators: MutableList<PackageValidator?> =
                    blazeModules.stream()
                        .map<PackageValidator?>(java.util.function.Function { obj: BlazeModule? -> obj.getPackageValidator() })
                        .filter(java.util.function.Predicate { obj: PackageValidator? -> java.util.Objects.nonNull(obj) })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<PackageValidator?>())
                com.google.common.base.Preconditions.checkState(
                    packageValidators.size() <= 1, "more than one module defined a PackageValidator"
                )
                return com.google.common.collect.Iterables.getFirst<PackageValidator?>(
                    packageValidators,
                    PackageValidator.NOOP_VALIDATOR
                )
            }

            private fun getPackageOverheadEstimator(
                blazeModules: MutableList<BlazeModule>
            ): PackageOverheadEstimator? {
                val packageOverheadEstimators: MutableList<PackageOverheadEstimator?> =
                    blazeModules.stream()
                        .map<PackageOverheadEstimator?>(java.util.function.Function { obj: BlazeModule? -> obj.getPackageOverheadEstimator() })
                        .filter(java.util.function.Predicate { obj: PackageOverheadEstimator? ->
                            java.util.Objects.nonNull(
                                obj
                            )
                        })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<PackageOverheadEstimator?>())
                com.google.common.base.Preconditions.checkState(
                    packageOverheadEstimators.size() <= 1,
                    "more than one module defined a PackageOverheadEstimator"
                )
                return com.google.common.collect.Iterables.getFirst<PackageOverheadEstimator?>(
                    packageOverheadEstimators,
                    PackageOverheadEstimator.NOOP_ESTIMATOR
                )
            }
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * Implements automatic profile management.
         * 
         * 
         *  * computes the path to write the profile for the current command to
         *  * does garbage collection for profiles from previous commands based on the `
         * --profiles_to_retain` flag
         * 
         * 
         * @return the path this command's profile should be written to
         */
        @com.google.common.annotations.VisibleForTesting
        @Throws(IOException::class)
        fun manageProfiles(
            dir: com.google.devtools.build.lib.vfs.Path,
            commandId: String,
            retentionWindow: Int
        ): com.google.devtools.build.lib.vfs.Path {
            val prefix = "command-"
            val suffix = ".profile.gz"

            class PathAndMtime(path: com.google.devtools.build.lib.vfs.Path?, mtime: Long) {
                val path: com.google.devtools.build.lib.vfs.Path?
                val mtime: Long

                init {
                    this.path = path
                    this.mtime = mtime
                }
            }

            val old: java.util.ArrayList<PathAndMtime?> = java.util.ArrayList<PathAndMtime?>()
            for (dirent in dir.readdir(Symlinks.FOLLOW)) {
                if (dirent.getName().startsWith(prefix) && dirent.getName().endsWith(suffix)) {
                    val path: com.google.devtools.build.lib.vfs.Path = dir.getChild(dirent.getName())
                    old.add(PathAndMtime(path, path.stat().getLastModifiedTime()))
                }
            }
            old.sort(java.util.Comparator.comparingLong<PathAndMtime?>(PathAndMtime::mtime))
            val toRemove: Int = java.lang.Math.max(old.size() - retentionWindow + 1, 0)
            for (i in 0..<toRemove) {
                old.get(i).path.delete()
            }
            val profileName = prefix + commandId + suffix
            return dir.getChild(profileName)
        }

        /**
         * Main method for the Blaze server startup. Note: This method logs exceptions to remote servers.
         * Do not add this to a unittest.
         */
        fun main(
            blazeModuleClasses: Iterable<java.lang.Class<out BlazeModule?>>,
            blazeServices: Iterable<com.google.devtools.build.lib.runtime.BlazeService>,
            args: Array<String?>,
            delayedJniLinkingError: Throwable?
        ) {
            // Transform args into Bazel's internal string representation.
            var args = args
            args = java.util.Arrays.stream<String?>(args)
                .map<String?>(java.util.function.Function { s: String? -> StringEncoding.platformToInternal(s) })
                .toArray<String?>(java.util.function.IntFunction { _Dummy_.__Array__() })
            setupUncaughtHandlerAtStartup(args)
            val blazeModules: com.google.common.collect.ImmutableList<BlazeModule> =
                createBlazeModules(blazeModuleClasses)
            // blaze.cc will put --batch first if the user set it.
            if (args.size >= 1 && args[0] == "--batch") {
                // Run Blaze in batch mode.
                exit(batchMain(blazeModules, blazeServices, args, delayedJniLinkingError))
            }
            logger.atInfo().log(
                "Starting Bazel server with pid %d, args %s",
                java.lang.ProcessHandle.current().pid(), java.util.Arrays.toString(args)
            )
            try {
                // Run Blaze in server mode.
                exit(
                    serverMain(
                        blazeModules, blazeServices, OutErr.SYSTEM_OUT_ERR, args, delayedJniLinkingError
                    )
                )
            } catch (e: java.lang.RuntimeException) { // A definite bug...
                val crash: Crash = Crash.from(e)
                BugReport.handleCrash(crash, CrashContext.keepAlive().withArgs(args))
                exit(crash.detailedExitCode.getExitCode().getNumericExitCode())
            } catch (e: java.lang.Error) {
                val crash: Crash = Crash.from(e)
                BugReport.handleCrash(crash, CrashContext.keepAlive().withArgs(args))
                exit(crash.detailedExitCode.getExitCode().getNumericExitCode())
            }
        }

        private fun exit(exitCode: Int) {
            // b/177077523: Best effort to kill all child processes. If there is any child process running,
            // System.exit will block forever.
            java.lang.ProcessHandle.current().children()
                .forEach(java.util.function.Consumer { obj: java.lang.ProcessHandle? -> obj.destroyForcibly() })
            java.lang.System.exit(exitCode)
        }

        @com.google.common.annotations.VisibleForTesting
        fun createBlazeModules(
            moduleClasses: Iterable<java.lang.Class<out BlazeModule?>>
        ): com.google.common.collect.ImmutableList<BlazeModule> {
            val result: com.google.common.collect.ImmutableList.Builder<BlazeModule?> =
                com.google.common.collect.ImmutableList.builder<BlazeModule?>()
            for (moduleClass in moduleClasses) {
                try {
                    val module: BlazeModule = moduleClass.getConstructor().newInstance()
                    result.add(module)
                } catch (e: Throwable) {
                    throw java.lang.IllegalStateException("Cannot instantiate module " + moduleClass.getName(), e)
                }
            }

            return result.build()
        }

        /**
         * Splits given options into two lists - arguments matching options defined in this class and
         * everything else, while preserving order in each list.
         * 
         * 
         * Note that this method relies on the startup options always being in the `--flag=ARG
        ` *  form (instead of `--flag ARG`). This is enforced by `GetArgumentArray()
        ` *  in `blaze.cc` by reconstructing the startup options from their parsed
         * versions instead of using `argv` verbatim.
         */
        fun splitStartupOptions(
            suppliers: Iterable<com.google.devtools.build.lib.runtime.OptionsSupplier?>, vararg args: String?
        ): CommandLineOptions {
            val prefixes: MutableList<String?> = java.util.ArrayList<String?>()
            val startupOptions: MutableList<com.google.devtools.common.options.OptionDefinition> =
                java.util.ArrayList<com.google.devtools.common.options.OptionDefinition>()
            for (defaultOptions in BlazeCommandUtils.getStartupOptions(suppliers)) {
                startupOptions.addAll(
                    com.google.devtools.common.options.OptionDefinition.getOptionDefinitions(
                        defaultOptions
                    )
                )
            }

            for (optionDefinition in startupOptions) {
                val optionType: java.lang.reflect.Type? = optionDefinition.getType()
                prefixes.add("--" + optionDefinition.getOptionName())
                if (optionType === Boolean::class.javaPrimitiveType || optionType === com.google.devtools.common.options.TriState::class.java) {
                    prefixes.add("--no" + optionDefinition.getOptionName())
                }
            }

            val startupArgs: MutableList<String> = java.util.ArrayList<String>()
            val otherArgs: MutableList<String> = com.google.common.collect.Lists.newArrayList<String?>(*args)

            val argi = otherArgs.iterator()
            while (argi.hasNext()) {
                val arg = argi.next()
                if (!arg.startsWith("--")) {
                    break // stop at command - all startup options would be specified before it.
                }
                for (prefix in prefixes) {
                    if (arg.startsWith(prefix)) {
                        startupArgs.add(arg)
                        argi.remove()
                        break
                    }
                }
            }
            return com.google.devtools.build.lib.runtime.BlazeRuntime.CommandLineOptions(
                com.google.common.collect.ImmutableList.copyOf<String?>(startupArgs),
                com.google.common.collect.ImmutableList.copyOf<String?>(otherArgs)
            )
        }

        private fun getSlowInterruptMessageSuffix(modules: Iterable<BlazeModule>): String? {
            var slowInterruptMessageSuffix: String? = null
            for (module in modules) {
                val message: String? = module.getSlowThreadInterruptMessageSuffix()
                if (message != null) {
                    com.google.common.base.Preconditions.checkState(
                        slowInterruptMessageSuffix == null,
                        "Two messages: %s %s (%s)",
                        slowInterruptMessageSuffix,
                        message,
                        module
                    )
                    slowInterruptMessageSuffix = message
                }
            }
            return slowInterruptMessageSuffix
        }

        private fun captureSigint(slowInterruptMessage: String?): InterruptSignalHandler {
            val mainThread: java.lang.Thread = java.lang.Thread.currentThread()
            val numInterrupts: AtomicInteger = AtomicInteger()

            val interruptWatcher: java.lang.Runnable =
                java.lang.Runnable {
                    var count = 0
                    // Not an actual infinite loop because it's run in a daemon thread.
                    while (true) {
                        count++
                        com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly(10, TimeUnit.SECONDS)
                        logger.atWarning().log("Slow interrupt number %d in batch mode", count)
                        com.google.devtools.build.lib.util.ThreadUtils.warnAboutSlowInterrupt(slowInterruptMessage)
                    }
                }

            return object : InterruptSignalHandler() {
                override fun run() {
                    logger.atInfo().log("User interrupt")
                    OutErr.SYSTEM_OUT_ERR.printErrLn("Bazel received an interrupt")
                    mainThread.interrupt()

                    val curNumInterrupts: Int = numInterrupts.incrementAndGet()
                    if (curNumInterrupts == 1) {
                        val interruptWatcherThread: java.lang.Thread =
                            java.lang.Thread(interruptWatcher, "interrupt-watcher")
                        interruptWatcherThread.setDaemon(true)
                        interruptWatcherThread.start()
                    } else if (curNumInterrupts == 2) {
                        logger.atWarning().log("Second --batch interrupt: Reverting to JVM SIGINT handler")
                        uninstall()
                    }
                }
            }
        }

        /**
         * A main method that runs blaze commands in batch mode. The return value indicates the desired
         * exit status of the program.
         */
        private fun batchMain(
            blazeModules: Iterable<BlazeModule>,
            blazeServices: Iterable<com.google.devtools.build.lib.runtime.BlazeService>,
            args: Array<String?>,
            delayedJniLinkingError: Throwable?
        ): Int {
            val signalHandler: InterruptSignalHandler =
                captureSigint(getSlowInterruptMessageSuffix(blazeModules))
            val commandLineOptions =
                splitStartupOptions(
                    com.google.common.collect.Iterables.concat<com.google.devtools.build.lib.runtime.OptionsSupplier?>(
                        blazeModules,
                        blazeServices
                    ), *args
                )
            logger.atInfo().log(
                "Running Bazel in batch mode with pid %d, startup args %s",
                java.lang.ProcessHandle.current().pid(), commandLineOptions.getStartupArgs()
            )

            val runtime: BlazeRuntime?
            val policy: InvocationPolicy?
            val startupOptions: BlazeServerStartupOptions?

            try {
                runtime =
                    newRuntime(
                        blazeModules,
                        blazeServices,
                        commandLineOptions.getStartupArgs(),
                        delayedJniLinkingError,  /* abruptShutdownHandler= */
                        null
                    )
                startupOptions =
                    runtime.startupOptionsProvider.getOptions<BlazeServerStartupOptions?>(BlazeServerStartupOptions::class.java)
                policy = InvocationPolicyParser.parsePolicy(startupOptions.getInvocationPolicy())
            } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                OutErr.SYSTEM_OUT_ERR.printErrLn(e.getMessage())
                return ExitCode.COMMAND_LINE_ERROR.getNumericExitCode()
            } catch (e: AbruptExitException) {
                OutErr.SYSTEM_OUT_ERR.printErrLn(e.getMessage())
                return e.getExitCode().getNumericExitCode()
            }

            val startupOptionsFromCommandLine: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.util.Pair<String?, String?>?> =
                com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.util.Pair<String?, String?>?>()
            for (option in commandLineOptions.getStartupArgs()) {
                startupOptionsFromCommandLine.add(com.google.devtools.build.lib.util.Pair<String?, String?>("", option))
            }

            val dispatcher: BlazeCommandDispatcher =
                BlazeCommandDispatcher(runtime, BlazeCommandDispatcher.Companion.UNKNOWN_SERVER_PID)
            var shutdownDone = false

            try {
                logger.atInfo().log(
                    "%s", SafeRequestLogging.getRequestLogString(commandLineOptions.getOtherArgs())
                )
                val result: BlazeCommandResult =
                    dispatcher.exec(
                        policy,
                        commandLineOptions.getOtherArgs(),
                        OutErr.SYSTEM_OUT_ERR,
                        CommandDispatcher.LockingMode.ERROR_OUT,
                        if (startupOptions.getQuiet()) UiVerbosity.QUIET else UiVerbosity.NORMAL,
                        "batch client",
                        runtime.clock.currentTimeMillis(),
                        java.util.Optional.of<MutableList<com.google.devtools.build.lib.util.Pair<String?, String?>?>?>(
                            startupOptionsFromCommandLine.build()
                        ),  /* idleTaskResultsSupplier= */
                        java.util.function.Supplier { com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.server.IdleTask.Result?>() },  /* commandExtensions= */
                        com.google.common.collect.ImmutableList.of<Any?>(),  /* commandExtensionReporter= */
                        CommandExtensionReporter { ext: Any? -> })
                if (result.getExecRequest() == null) {
                    // Simple case: we are given an exit code
                    return result.getExitCode().getNumericExitCode()
                }

                // Not so simple case: we need to execute a binary on shutdown. exec() is not accessible from
                // Java and is impossible on Windows in any case, so we just execute the binary after getting
                // out of the way as completely as possible and forward its exit code.
                // When this code is executed, no locks are held: the client lock is released by the client
                // before it executes any command and the server lock is handled by BlazeCommandDispatcher,
                // whose job is done by the time we get here.
                runtime.shutdown()
                dispatcher.shutdown()
                shutdownDone = true
                signalHandler.uninstall()
                val request: ExecRequest = result.getExecRequest()

                val argv = arrayOfNulls<String>(request.getArgvCount())
                for (i in argv.indices) {
                    argv[i] = internalBytesToPlatformString(request.getArgv(i))
                }
                val workingDirectory = internalBytesToPlatformString(request.getWorkingDirectory())
                try {
                    val process: java.lang.ProcessBuilder =
                        java.lang.ProcessBuilder().command(*argv).directory(java.io.File(workingDirectory)).inheritIO()

                    for (i in 0..<request.getEnvironmentVariableToClearCount()) {
                        process
                            .environment()
                            .remove(internalBytesToPlatformString(request.getEnvironmentVariableToClear(i)))
                    }

                    for (i in 0..<request.getEnvironmentVariableCount()) {
                        val variable: EnvironmentVariable = request.getEnvironmentVariable(i)
                        process
                            .environment()
                            .put(
                                internalBytesToPlatformString(variable.getName()),
                                internalBytesToPlatformString(variable.getValue())
                            )
                    }

                    return process.start().waitFor()
                } catch (e: IOException) {
                    // We are in batch mode, thus, stdout/stderr are the same as that of the client.
                    java.lang.System.err.println("Cannot execute process for 'run' command: " + e.getMessage())
                    logger.atSevere().withCause(e).log("Exception while executing binary from 'run' command")
                    return ExitCode.LOCAL_ENVIRONMENTAL_ERROR.getNumericExitCode()
                }
            } catch (e: java.lang.InterruptedException) {
                // This is almost main(), so it's okay to just swallow it. We are exiting soon.
                return ExitCode.INTERRUPTED.getNumericExitCode()
            } finally {
                if (!shutdownDone) {
                    runtime.shutdown()
                    dispatcher.shutdown()
                }
            }
        }

        /**
         * A main method that does not send email. The return value indicates the desired exit status of
         * the program.
         */
        private fun serverMain(
            blazeModules: Iterable<BlazeModule>,
            blazeServices: Iterable<com.google.devtools.build.lib.runtime.BlazeService>,
            outErr: OutErr,
            args: Array<String?>,
            delayedJniLinkingError: Throwable?
        ): Int {
            var sigintHandler: InterruptSignalHandler? = null
            try {
                val commandServerRef: AtomicReference<CommandServer?> = AtomicReference<CommandServer?>()
                val prepareForAbruptShutdown: java.lang.Runnable =
                    java.lang.Runnable { commandServerRef.get().prepareForAbruptShutdown() }
                val runtime =
                    newRuntime(
                        blazeModules,
                        blazeServices,
                        java.util.Arrays.asList<String?>(*args),
                        delayedJniLinkingError,
                        prepareForAbruptShutdown
                    )

                // server.pid was written in the C++ launcher after fork() but before exec(). The client only
                // accesses the pid file after connecting to the socket which ensures that it gets the correct
                // pid value.
                val pidFile: com.google.devtools.build.lib.vfs.Path? =
                    runtime.serverDirectory.getRelative("server.pid.txt")
                val serverPid = readPidFile(pidFile)
                val pidFileWatcher: PidFileWatcher = PidFileWatcher(pidFile, serverPid)
                pidFileWatcher.start()

                val shutdownHooks: com.google.devtools.build.lib.server.ShutdownHooks =
                    com.google.devtools.build.lib.server.ShutdownHooks.createAndRegister()
                shutdownHooks.cleanupPidFile(pidFile, pidFileWatcher)

                val grpcCommandServerService: GrpcCommandServerService =
                    com.google.common.base.Preconditions.checkNotNull<GrpcCommandServerService>(
                        runtime.getBlazeService<GrpcCommandServerService?>(
                            GrpcCommandServerService::class.java
                        )
                    )

                val dispatcher: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime, serverPid)
                val startupOptions: BlazeServerStartupOptions? =
                    runtime.startupOptionsProvider.getOptions<BlazeServerStartupOptions?>(BlazeServerStartupOptions::class.java)
                val commandServer: CommandServer =
                    CommandServer.create(
                        grpcCommandServerService.getGrpcCommandServer(),
                        dispatcher,
                        shutdownHooks,
                        pidFileWatcher,
                        runtime.clock,
                        startupOptions.getCommandPort(),
                        runtime.serverDirectory,
                        serverPid,
                        startupOptions.getMaxIdleSeconds(),
                        startupOptions.getShutdownOnLowSysMem(),
                        startupOptions.getIdleServerTasks(),
                        getSlowInterruptMessageSuffix(blazeModules)
                    )
                commandServerRef.set(commandServer)

                // Register the signal handler.
                sigintHandler =
                    object : InterruptSignalHandler() {
                        override fun run() {
                            logger.atSevere().log("User interrupt")
                            commandServer.interrupt()
                        }
                    }

                commandServer.serveAndAwaitTermination()
                runtime.shutdown()
                dispatcher.shutdown()
                return ExitCode.SUCCESS.getNumericExitCode()
            } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                outErr.printErrLn(e.getMessage())
                return ExitCode.COMMAND_LINE_ERROR.getNumericExitCode()
            } catch (e: AbruptExitException) {
                outErr.printErrLn(e.getMessage())
                e.printStackTrace(PrintStream(outErr.getErrorStream(), true))
                val failureDetail: FailureDetail? = e.getDetailedExitCode().getFailureDetail()
                if (failureDetail != null) {
                    CustomFailureDetailPublisher.maybeWriteFailureDetailFile(failureDetail)
                }
                return e.getExitCode().getNumericExitCode()
            } finally {
                if (sigintHandler != null) {
                    sigintHandler.uninstall()
                }
            }
        }

        /**
         * Parses the command line arguments into a [OptionsParser] object.
         * 
         * 
         * This function needs to parse the --option_sources option manually so that the real option
         * parser can set the source for every option correctly. If that cannot be parsed or is missing,
         * we just report an unknown source for every startup option.
         */
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        private fun parseStartupOptions(
            suppliers: Iterable<com.google.devtools.build.lib.runtime.OptionsSupplier?>, args: MutableList<String?>?
        ): com.google.devtools.common.options.OptionsParsingResult {
            val optionClasses: com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> =
                BlazeCommandUtils.getStartupOptions(suppliers)

            // First parse the command line so that we get the option_sources argument
            var parser: com.google.devtools.common.options.OptionsParser =
                com.google.devtools.common.options.OptionsParser.builder().optionsClasses(optionClasses)
                    .allowResidue(false).build()
            parser.parse(com.google.devtools.common.options.OptionPriority.PriorityCategory.COMMAND_LINE, null, args)
            val optionSources: MutableMap<String?, String?> =
                parser.getOptions<BlazeServerStartupOptions?>(BlazeServerStartupOptions::class.java).getOptionSources()
            val sourceFunction: java.util.function.Function<com.google.devtools.common.options.OptionDefinition?, String?> =
                java.util.function.Function { option: com.google.devtools.common.options.OptionDefinition? ->
                    if (!optionSources.containsKey(option.getOptionName()))
                        "default"
                    else
                        if (optionSources.get(option.getOptionName()).isEmpty())
                            "command line"
                        else
                            optionSources.get(option.getOptionName())
                }

            // Then parse the command line again, this time with the correct option sources
            parser = com.google.devtools.common.options.OptionsParser.builder().optionsClasses(optionClasses)
                .allowResidue(false).build()
            parser.parseWithSourceFunction(
                com.google.devtools.common.options.OptionPriority.PriorityCategory.COMMAND_LINE,
                sourceFunction,
                args,  /* fallbackData= */
                null
            )
            return parser
        }

        /**
         * Creates a new blaze runtime, given the install and output base directories.
         * 
         * 
         * Note: This method can and should only be called once per startup, as it also creates the
         * filesystem object that will be used for the runtime. So it should only ever be called from the
         * main method of the Blaze program.
         * 
         * @param args Blaze startup options.
         * @return a new BlazeRuntime instance initialized with the given filesystem and directories, and
         * an error string that, if not null, describes a fatal initialization failure that makes this
         * runtime unsuitable for real commands
         */
        @Throws(AbruptExitException::class, com.google.devtools.common.options.OptionsParsingException::class)
        private fun newRuntime(
            blazeModules: Iterable<BlazeModule>,
            blazeServices: Iterable<com.google.devtools.build.lib.runtime.BlazeService>,
            args: MutableList<String?>?,
            delayedJniLinkingError: Throwable?,
            abruptShutdownHandler: java.lang.Runnable?
        ): BlazeRuntime {
            val options: com.google.devtools.common.options.OptionsParsingResult =
                parseStartupOptions(
                    com.google.common.collect.Iterables.concat<com.google.devtools.build.lib.runtime.OptionsSupplier?>(
                        blazeModules,
                        blazeServices
                    ), args
                )
            val startupOptions: BlazeServerStartupOptions? =
                options.getOptions<BlazeServerStartupOptions?>(BlazeServerStartupOptions::class.java)

            // Set up the failure detail path first, so that it can communicate problems with other flags
            // and module initialization.
            val failureDetailOut: PathFragment = startupOptions.getFailureDetailOut()
            require(!(failureDetailOut == null || !failureDetailOut.isAbsolute())) { "Bad --failure_detail_out option specified: '" + failureDetailOut + "'" }
            CustomFailureDetailPublisher.setFailureDetailFilePath(failureDetailOut.getPathString())

            for (service in blazeServices) {
                try {
                    service.globalInit(options, blazeServices)
                } catch (e: com.google.devtools.build.lib.util.SerializedAbruptExitException) {
                    throw AbruptExitException.fromSerialized(e)
                }
            }

            for (module in blazeModules) {
                module.globalInit(options, blazeServices)
            }

            val productName: String = startupOptions.getProductName().toLowerCase(Locale.US)

            val workspaceDirectory: PathFragment = startupOptions.getWorkspaceDirectory()

            val outputUserRoot: PathFragment? = startupOptions.getOutputUserRoot()
            val installBase: PathFragment = startupOptions.getInstallBase()
            val outputBase: PathFragment = startupOptions.getOutputBase()
            val execRootBase: PathFragment? = outputBase.getRelative(ServerDirectories.EXECROOT)

            // Emit a helpful error message (now that we have the install base path handy) if we detected a
            // JNI linking error earlier.
            if (delayedJniLinkingError != null) {
                java.lang.System.err.printf(
                    "JNI initialization failed: %s. Possibly your installation has been corrupted; if this"
                            + " problem persists, try 'rm -fr %s'.\n",
                    delayedJniLinkingError.getMessage(), installBase
                )
                throw AbruptExitException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(delayedJniLinkingError.getMessage())
                            .setJniLinking(JniLinking.newBuilder().setCode(JniLinking.Code.JNI_LINKING_ERROR))
                            .build()
                    )
                )
            }

            // From the point of view of the Java program --install_base, --output_base, --output_user_root,
            // and --failure_detail_out are mandatory options, despite the comment in their declarations.
            require(!(installBase == null || !installBase.isAbsolute())) { "Bad --install_base option specified: '" + installBase + "'" }
            require(!(outputUserRoot != null && !outputUserRoot.isAbsolute())) { "Bad --output_user_root option specified: '" + outputUserRoot + "'" }
            require(!(outputBase != null && !outputBase.isAbsolute())) { "Bad --output_base option specified: '" + outputBase + "'" }

            var nativeFs: com.google.devtools.build.lib.vfs.FileSystem? = null
            var virtualSourceRoot: java.util.Optional<Root?> = java.util.Optional.empty<Root?>()
            for (module in blazeModules) {
                val moduleFs: ModuleFileSystem? = module.getFileSystem(options)
                if (moduleFs != null) {
                    com.google.common.base.Preconditions.checkState(
                        nativeFs == null,
                        "more than one module returns a file system"
                    )
                    nativeFs = moduleFs.fileSystem()
                    virtualSourceRoot = moduleFs.virtualSourceRoot()
                }
            }

            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.FileSystem?>(
                nativeFs,
                "No module set the file system"
            )

            var maybeFsForBuildArtifacts: com.google.devtools.build.lib.vfs.FileSystem? = null
            for (module in blazeModules) {
                val maybeFs: com.google.devtools.build.lib.vfs.FileSystem? =
                    module.getFileSystemForBuildArtifacts(nativeFs)
                if (maybeFs != null) {
                    com.google.common.base.Preconditions.checkState(
                        maybeFsForBuildArtifacts == null,
                        "more than one module returns a file system for build artifacts"
                    )
                    maybeFsForBuildArtifacts = maybeFs
                }
            }

            val fs: com.google.devtools.build.lib.vfs.FileSystem =
                com.google.common.base.MoreObjects.firstNonNull<com.google.devtools.build.lib.vfs.FileSystem>(
                    maybeFsForBuildArtifacts,
                    nativeFs
                )

            var installBaseLock: FileSystemLock? = null
            if (startupOptions.getLockInstallBase()) {
                // Acquire a shared lock on the install base to prevent it from being garbage collected by
                // another server while this server is running. Note that the client must already hold a
                // shared lock on the install base at this time (which it will release once it successfully
                // connects to the server), so failure to obtain the lock is not expected.
                // The lock is never released explicitly, so as not to risk releasing it while the install
                // base is still in use. It goes away when the server process dies.
                try {
                    installBaseLock =
                        FileSystemLock.tryGet(
                            nativeFs.getPath(installBase.replaceName(installBase.getBaseName() + ".lock")),
                            LockMode.SHARED
                        )
                } catch (e: IOException) {
                    throw createFilesystemExitException(
                        "Failed to acquire shared lock on install base: " + e.getMessage(),
                        Filesystem.Code.FAILED_TO_LOCK_INSTALL_BASE,
                        e
                    )
                }
            }

            var currentHandlerValue: com.google.common.eventbus.SubscriberExceptionHandler? = null
            for (module in blazeModules) {
                val newHandler: com.google.common.eventbus.SubscriberExceptionHandler? =
                    module.getEventBusAndAsyncExceptionHandler()
                if (newHandler != null) {
                    com.google.common.base.Preconditions.checkState(
                        currentHandlerValue == null, "Two handlers given. Last module: %s", module
                    )
                    currentHandlerValue = newHandler
                }
            }
            if (currentHandlerValue == null) {
                if (startupOptions.getFatalEventBusExceptions()) {
                    currentHandlerValue =
                        com.google.common.eventbus.SubscriberExceptionHandler? { exception: Throwable?, context: com.google.common.eventbus.SubscriberExceptionContext? ->
                        BugReport.handleCrash(
                            exception
                        )
                    }
                } else {
                    currentHandlerValue =
                        com.google.common.eventbus.SubscriberExceptionHandler? { exception: Throwable?, context: com.google.common.eventbus.SubscriberExceptionContext? ->
                        if (context == null) {
                            BugReport.handleCrash(exception)
                        } else {
                            BugReport.sendBugReport(
                                exception,
                                com.google.common.collect.ImmutableList.of<String?>(),
                                "Failure in EventBus subscriber"
                            )
                        }
                    }
                }
            }
            val subscriberExceptionHandler: com.google.common.eventbus.SubscriberExceptionHandler? = currentHandlerValue
            java.lang.Thread.setDefaultUncaughtExceptionHandler(
                object : java.lang.Thread.UncaughtExceptionHandler {
                    override fun uncaughtException(thread: java.lang.Thread?, throwable: Throwable) {
                        subscriberExceptionHandler.handleException(throwable, null)
                    }
                })

            // Set the hook used to display Starlark source lines in a stack trace.
            net.starlark.java.eval.EvalException.setSourceReaderSupplier(
                java.util.function.Supplier {
                    net.starlark.java.eval.EvalException.SourceReader { loc: net.starlark.java.syntax.Location? ->
                        try {
                            // TODO(adonovan): opt: cache seen files, as the stack often repeats the same files.
                            val path: com.google.devtools.build.lib.vfs.Path? =
                                fs.getPath(PathFragment.create(loc.file()))
                            // Reading the file as Latin-1 is equivalent to reading raw bytes, which matches
                            // Bazel's internal encoding for strings (see StringEncoding).
                            val lines: com.google.common.collect.ImmutableList<String?> =
                                com.google.devtools.build.lib.vfs.FileSystemUtils.readLinesAsLatin1(path)
                            return@SourceReader if (lines.size() >= loc.line()) lines.get(loc.line() - 1) else null
                        } catch (unused: Throwable) {
                            // ignore any failure (e.g. ENOENT, security manager rejecting I/O)
                        }
                        null
                    }
                })

            val outputUserRootPath: com.google.devtools.build.lib.vfs.Path? = fs.getPath(outputUserRoot)
            val installBasePath: com.google.devtools.build.lib.vfs.Path? = fs.getPath(installBase)
            val outputBasePath: com.google.devtools.build.lib.vfs.Path? = fs.getPath(outputBase)
            val execRootBasePath: com.google.devtools.build.lib.vfs.Path? = fs.getPath(execRootBase)
            var workspaceDirectoryPath: com.google.devtools.build.lib.vfs.Path? = null
            if (workspaceDirectory != PathFragment.EMPTY_FRAGMENT) {
                workspaceDirectoryPath = nativeFs.getPath(workspaceDirectory)
            }

            val serverDirectories: ServerDirectories =
                ServerDirectories(
                    installBasePath,
                    outputBasePath,
                    outputUserRootPath,
                    execRootBasePath,
                    virtualSourceRoot.orElse(null),
                    startupOptions.getInstallMD5()
                )
            val clock: com.google.devtools.build.lib.clock.Clock =
                com.google.devtools.build.lib.clock.BlazeClock.instance()
            val runtimeBuilder: Builder =
                com.google.devtools.build.lib.runtime.BlazeRuntime.Builder()
                    .setProductName(productName)
                    .setFileSystem(fs)
                    .setServerDirectories(serverDirectories)
                    .setActionKeyContext(ActionKeyContext())
                    .setStartupOptionsProvider(options)
                    .setClock(clock)
                    .setAbruptShutdownHandler(abruptShutdownHandler)
                    .setEventBusExceptionHandler(subscriberExceptionHandler)
                    .setInstallBaseLock(installBaseLock)

            if (com.google.devtools.build.lib.util.TestType.isInTest() && java.lang.System.getenv("NO_CRASH_ON_LOGGING_IN_TEST") == null) {
                LoggingUtil.installRemoteLogger(testCrashLogger)
            }

            for (blazeModule in blazeModules) {
                runtimeBuilder.addBlazeModule(blazeModule)
            }

            for (blazeService in blazeServices) {
                runtimeBuilder.addBlazeService(blazeService)
            }

            val runtime = runtimeBuilder.build()

            CustomExitCodePublisher.setAbruptExitStatusFileDir(
                serverDirectories.getOutputBase().getPathString()
            )
            // Delete the previous file, if any, in case this server is reusing an existing output base
            // from a previous server that had an abrupt exit.
            CustomExitCodePublisher.maybeDeleteAbruptExitStatusFile()

            val directories: BlazeDirectories =
                BlazeDirectories(serverDirectories, workspaceDirectoryPath, productName)
            val binTools: BinTools?
            try {
                binTools = BinTools.forProduction(directories)
            } catch (e: IOException) {
                throw createFilesystemExitException(
                    "Cannot enumerate embedded binaries: " + e.getMessage(),
                    Filesystem.Code.EMBEDDED_BINARIES_ENUMERATION_FAILURE,
                    e
                )
            }
            // Keep this line last in this method, so that all other initialization is available to it.
            runtime.initWorkspace(directories, binTools)
            return runtime
        }

        private fun createFilesystemExitException(
            message: String?, detailedCode: Filesystem.Code?, e: java.lang.Exception?
        ): AbruptExitException {
            return AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setFilesystem(Filesystem.newBuilder().setCode(detailedCode))
                        .build()
                ),
                e
            )
        }

        @get:com.google.common.annotations.VisibleForTesting
        val testCrashLogger: java.util.concurrent.Future<java.util.logging.Logger?>
            /**
             * Returns a logger that crashes as soon as it's written to, since tests should not cause events
             * that would be logged.
             */
            get() {
                val crashLogger: java.util.logging.Logger = java.util.logging.Logger.getAnonymousLogger()
                crashLogger.addHandler(
                    object : java.util.logging.Handler() {
                        override fun publish(record: LogRecord) {
                            java.lang.System.err.println("Remote logging disabled for testing, forcing abrupt shutdown.")
                            java.lang.System.err.printf(
                                "%s#%s: %s\n",
                                record.getSourceClassName(), record.getSourceMethodName(), record.getMessage()
                            )

                            val e: Throwable? = record.getThrown()
                            if (e != null) {
                                e.printStackTrace()
                            }

                            java.lang.Runtime.getRuntime().halt(ExitCode.BLAZE_INTERNAL_ERROR.getNumericExitCode())
                        }

                        override fun flush() {
                            throw java.lang.IllegalStateException()
                        }

                        override fun close() {
                            throw java.lang.IllegalStateException()
                        }
                    })
                return com.google.common.util.concurrent.Futures.immediateFuture<java.util.logging.Logger?>(crashLogger)
            }

        /**
         * Make sure async threads cannot be orphaned at startup. This method makes sure bugs are reported
         * to telemetry and the proper exit code is reported. Will be overwritten with better handler.
         */
        private fun setupUncaughtHandlerAtStartup(args: Array<String?>) {
            java.lang.Thread.setDefaultUncaughtExceptionHandler(
                java.lang.Thread.UncaughtExceptionHandler { thread: java.lang.Thread?, throwable: Throwable? ->
                    BugReport.handleCrash(
                        throwable,
                        *args
                    )
                })
        }

        private fun getPackageLoadingListener(
            blazeModules: MutableList<BlazeModule>,
            packageBuilderHelper: PackageSettings?,
            ruleClassProvider: ConfiguredRuleClassProvider?,
            fs: com.google.devtools.build.lib.vfs.FileSystem?
        ): PackageLoadingListener {
            val listeners: com.google.common.collect.ImmutableList<PackageLoadingListener?> =
                blazeModules.stream()
                    .map<Any?>(
                        java.util.function.Function { module: BlazeModule? ->
                            module.getPackageLoadingListener(
                                packageBuilderHelper,
                                ruleClassProvider,
                                fs
                            )
                        })
                    .filter(java.util.function.Predicate { obj: Any? -> java.util.Objects.nonNull(obj) })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
            return PackageLoadingListener.create(listeners)
        }

        @Throws(AbruptExitException::class)
        private fun readPidFile(pidFile: com.google.devtools.build.lib.vfs.Path?): Int {
            try {
                return java.lang.Integer.parseInt(
                    String(
                        com.google.devtools.build.lib.vfs.FileSystemUtils.readContentAsLatin1(
                            pidFile
                        )
                    )
                )
            } catch (e: IOException) {
                throw createFilesystemExitException(
                    "Server pid file read failed: " + e.getMessage(),
                    Filesystem.Code.SERVER_PID_TXT_FILE_READ_FAILURE,
                    e
                )
            } catch (e: java.lang.NumberFormatException) {
                // Invalid contents (not a number) is more likely than not a filesystem issue.
                throw createFilesystemExitException(
                    "Server pid file corrupted: " + e.getMessage(),
                    Filesystem.Code.SERVER_PID_TXT_FILE_READ_FAILURE,
                    IOException(e)
                )
            }
        }

        private fun internalBytesToPlatformString(bytes: ByteString): String {
            return StringEncoding.internalToPlatform(bytes.toString(java.nio.charset.StandardCharsets.ISO_8859_1))
        }
    }
}
