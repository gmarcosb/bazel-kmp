// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionOutputDirectoryHelper

/**
 * Encapsulates the state needed for a single command. The environment is dropped when the current
 * command is done and all corresponding objects are garbage collected.
 * 
 * 
 * This class is non-final for mocking purposes. DO NOT extend it in production code.
 */
class CommandEnvironment internal constructor(
    runtime: BlazeRuntime,
    workspace: BlazeWorkspace,
    eventBus: com.google.common.eventbus.EventBus,
    commandThread: java.lang.Thread,
    command: com.google.devtools.build.lib.runtime.Command,
    options: com.google.devtools.common.options.OptionsParsingResult,
    invocationPolicy: InvocationPolicy?,
    packageLocator: PathPackageLocator?,
    syscallCache: SyscallCache?,
    quiescingExecutors: QuiescingExecutors?,
    warnings: MutableList<String?>,
    waitTimeInMs: Long,
    commandStartTime: Long,
    idleTaskResultsFromPreviousIdlePeriod: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask.Result?>?,
    shutdownReasonConsumer: java.util.function.Consumer<String?>,
    commandExtensions: MutableList<Any?>,
    commandExtensionReporter: CommandExtensionReporter?,
    attemptNumber: Int,
    buildRequestIdOverride: String?,
    configFlagDefinitions: ConfigFlagDefinitions?,
    resourceManager: ResourceManager?
) {
    private val runtime: BlazeRuntime
    private val workspace: BlazeWorkspace
    private val directories: BlazeDirectories
    private val configFlagDefinitions: ConfigFlagDefinitions?

    private val commandId: UUID // Unique identifier for the command being run

    /**
     * Returns the ID that Blaze uses to identify everything logged from the current build request.
     * TODO(olaola): this should be a prefixed UUID, but some existing clients still use arbitrary
     * strings, so we accept these when passed by environment variable for compatibility.
     */
    val buildRequestId: String? // Unique identifier for the build being run
    private val reporter: com.google.devtools.build.lib.events.Reporter
    private val eventBus: com.google.common.eventbus.EventBus
    private val blazeModuleEnvironment: ModuleEnvironment
    private val clientEnv: com.google.common.collect.ImmutableMap<String?, String?>
    private val visibleActionEnv: MutableSet<String?> = TreeSet<String?>()
    private val visibleTestEnv: MutableSet<String?> = TreeSet<String?>()
    private val repoEnv: com.google.common.collect.ImmutableMap<String?, String?>
    private val nonstrictRepoEnv: com.google.common.collect.ImmutableMap<String?, String?>
    private val timestampGranularityMonitor: TimestampGranularityMonitor
    private val commandThread: java.lang.Thread
    private val command: com.google.devtools.build.lib.runtime.Command
    private val options: com.google.devtools.common.options.OptionsParsingResult
    private val invocationPolicy: InvocationPolicy?
    private val packageLocator: PathPackageLocator?
    private val workingDirectory: com.google.devtools.build.lib.vfs.Path?
    private val relativeWorkingDirectory: PathFragment?
    private val syscallCache: SyscallCache?
    private val quiescingExecutors: QuiescingExecutors?
    private val waitTime: java.time.Duration?
    @kotlin.jvm.JvmField
    val commandStartTime: Long
    private val commandExtensions: com.google.common.collect.ImmutableList<Any?>
    private val responseExtensions: com.google.common.collect.ImmutableList.Builder<Any?> =
        com.google.common.collect.ImmutableList.builder<Any?>()
    private val shutdownReasonConsumer: java.util.function.Consumer<String?>
    private val buildResultListener: BuildResultListener
    private val commandLinePathFactory: CommandLinePathFactory
    private val commandExtensionReporter: CommandExtensionReporter?

    /**
     * Returns the number of the invocation attempt, starting at 1 and increasing by 1 for each new
     * attempt. Can be used to determine if there is a build retry by `--experimental_remote_cache_eviction_retries`.
     */
    val attemptNumber: Int
    private val httpDownloader: HttpDownloader
    private val delegatingDownloader: DelegatingDownloader
    private val remoteAnalysisCachingEventListener: RemoteAnalysisCachingEventListener
    private val idleTaskResultsFromPreviousIdlePeriod: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask.Result?>?
    private val idleTasks: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.server.IdleTask?> =
        com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.server.IdleTask?>()
    private val resourceManager: ResourceManager?

    private var mergedAnalysisAndExecution = false

    private var outputService: OutputService? = null
    private var hasSyncedPackageLoading = false
    private var buildInfoPosted = false
    private var additionalConfigurationChangeEvent: java.util.Optional<AdditionalConfigurationChangeEvent?> =
        java.util.Optional.empty<AdditionalConfigurationChangeEvent?>()
    private var workspaceInfoFromDiff: WorkspaceInfoFromDiff? = null

    // This AtomicReference is set to:
    //   - null, if neither BlazeModuleEnvironment#exit nor #precompleteCommand have been called
    //   - Optional.of(e), if BlazeModuleEnvironment#exit has been called with value e
    //   - Optional.empty(), if #precompleteCommand was called before any call to
    //     BlazeModuleEnvironment#exit
    private val pendingException: AtomicReference<java.util.Optional<AbruptExitException?>?> =
        AtomicReference<java.util.Optional<AbruptExitException?>?>()

    private val fileCacheLock = Any()

    @kotlin.concurrent.Volatile
    var fileCache: InputMetadataProvider? = null
        /** Returns the file cache to use during this build.  */
        get() {
            if (field == null) {
                synchronized(fileCacheLock) {
                    if (field == null) {
                        field =
                            SingleBuildFileCache(
                                this.execRoot.getPathString(),
                                PathFragment.create(directories.getRelativeOutputPath()),
                                runtime.getFileSystem(),
                                syscallCache
                            )
                    }
                }
            }
            return field
        }
        private set

    private val outputDirectoryHelperLock = Any()

    @javax.annotation.concurrent.GuardedBy("outputDirectoryHelperLock")
    var outputDirectoryHelper: ActionOutputDirectoryHelper? = null
        get() {
            synchronized(outputDirectoryHelperLock) {
                if (field == null) {
                    val buildRequestOptions: O? = options.getOptions<O?>(BuildRequestOptions::class.java)
                    field =
                        ActionOutputDirectoryHelper(buildRequestOptions.directoryCreationCacheSpec)
                }
                return field
            }
        }
        private set

    // List of flags and their values that were added by invocation policy. May contain multiple
    // occurrences of the same flag.
    private var invocationPolicyFlags: com.google.common.collect.ImmutableList<com.google.devtools.common.options.OptionAndRawValue?>? =
        com.google.common.collect.ImmutableList.of<com.google.devtools.common.options.OptionAndRawValue?>()

    // Optionally set in `beforeCommand` phase.
    private var versionGetter: LongVersionGetter? = null
    @kotlin.jvm.JvmField
    var useFakeStampData: Boolean = false

    private var uiEventHandler: UiEventHandler? = null

    /**
     * Gets the [RemoteAnalysisCachingEventListener] for this invocation.
     * 
     * 
     * A new copy of the listener is instantiated for every new [CommandEnvironment], so
     * statistics are not retained between invocations.
     */
    fun getRemoteAnalysisCachingEventListener(): RemoteAnalysisCachingEventListener {
        return remoteAnalysisCachingEventListener
    }

    private inner class BlazeModuleEnvironment : ModuleEnvironment {
        override fun getFileFromWorkspace(label: Label): com.google.devtools.build.lib.vfs.Path? {
            val buildFile: com.google.devtools.build.lib.vfs.Path? =
                this.packageManager.getBuildFileForPackage(label.getPackageIdentifier())
            if (buildFile == null) {
                return null
            }
            return buildFile.getParentDirectory().getRelative(label.name)
        }

        override fun exit(exception: AbruptExitException?) {
            com.google.common.base.Preconditions.checkNotNull<AbruptExitException?>(exception)
            com.google.common.base.Preconditions.checkNotNull<ExitCode?>(exception.getExitCode())
            if (pendingException.compareAndSet(null, java.util.Optional.of<AbruptExitException?>(exception))
                && java.lang.Thread.currentThread() != commandThread
            ) {
                // There was no exception, so we're the first one to ask for an exit. Interrupt the command
                // if this exit is coming from a different thread, so that the command terminates promptly.
                commandThread.interrupt()
            }
        }
    }

    /**
     * Creates a new command environment which can be used for executing commands for the given
     * runtime in the given workspace, which will publish events on the given eventBus. The
     * commandThread passed is interrupted when a module requests an early exit.
     * 
     * @param warnings will be filled with any warnings from command environment initialization.
     */
    init {
        com.google.common.base.Preconditions.checkArgument(attemptNumber >= 1)

        this.runtime = runtime
        this.workspace = workspace
        this.directories = workspace.getDirectories()
        this.reporter = com.google.devtools.build.lib.events.Reporter(EventBusEventHandler(eventBus))
        this.eventBus = eventBus
        this.commandThread = commandThread
        this.command = command
        this.options = options
        this.invocationPolicy = invocationPolicy
        this.packageLocator = packageLocator
        this.idleTaskResultsFromPreviousIdlePeriod = idleTaskResultsFromPreviousIdlePeriod
        this.shutdownReasonConsumer = shutdownReasonConsumer
        this.syscallCache = syscallCache
        this.quiescingExecutors = quiescingExecutors
        this.commandExtensionReporter = commandExtensionReporter
        this.blazeModuleEnvironment = BlazeModuleEnvironment()
        this.timestampGranularityMonitor = TimestampGranularityMonitor(runtime.getClock())
        this.attemptNumber = attemptNumber
        this.configFlagDefinitions = configFlagDefinitions
        this.resourceManager = resourceManager

        // Record the command's starting time again, for use by
        // TimestampGranularityMonitor.waitForTimestampGranularity().
        // This should be done as close as possible to the start of
        // the command's execution.
        timestampGranularityMonitor.setCommandStartTime()

        val commandOptions: CommonCommandOptions =
            com.google.common.base.Preconditions.checkNotNull<CommonCommandOptions>(
                options.getOptions<CommonCommandOptions?>(CommonCommandOptions::class.java),
                "CommandEnvironment needs its options provider to have CommonCommandOptions loaded."
            )
        var workingDirectory: com.google.devtools.build.lib.vfs.Path
        try {
            workingDirectory = computeWorkingDirectory(commandOptions)
        } catch (e: AbruptExitException) {
            // We'll exit very soon, but set the working directory to something reasonable so remainder of
            // setup can finish.
            this.blazeModuleEnvironment.exit(e)
            workingDirectory = directories.getWorkingDirectory()
        }
        this.workingDirectory = workingDirectory
        if (getWorkspace() != null) {
            this.relativeWorkingDirectory = workingDirectory.relativeTo(getWorkspace())
        } else {
            this.relativeWorkingDirectory = PathFragment.EMPTY_FRAGMENT
        }

        this.waitTime = java.time.Duration.ofMillis(waitTimeInMs + commandOptions.getWaitTime())
        this.commandStartTime = commandStartTime - commandOptions.getStartupTime()
        this.commandExtensions = com.google.common.collect.ImmutableList.copyOf<Any?>(commandExtensions)
        workspace.getSkyframeExecutor().setEventBus(eventBus)
        eventBus.register(this)
        var httpTimeoutScaling: Float = commandOptions.getHttpTimeoutScaling().toFloat()
        if (commandOptions.getHttpTimeoutScaling() <= 0) {
            reporter.handle(
                com.google.devtools.build.lib.events.Event.warn("Ignoring request to scale http timeouts by a non-positive factor")
            )
            httpTimeoutScaling = 1.0f
        }
        if (commandOptions.getHttpMaxParallelDownloads() <= 0) {
            this.blazeModuleEnvironment.exit(
                AbruptExitException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(
                                "The maximum number of parallel downloads needs to be a positive number"
                            )
                            .setExternalRepository(
                                ExternalRepository.newBuilder().setCode(Code.BAD_DOWNLOADER_CONFIG)
                            )
                            .build()
                    )
                )
            )
        }

        this.httpDownloader =
            HttpDownloader(
                commandOptions.getHttpConnectorAttempts(),
                commandOptions.getHttpConnectorRetryMaxTimeout(),
                commandOptions.getHttpMaxParallelDownloads(),
                httpTimeoutScaling
            )
        this.delegatingDownloader = DelegatingDownloader(httpDownloader)

        val clientOptions: ClientOptions =
            com.google.common.base.Preconditions.checkNotNull<ClientOptions>(
                options.getOptions<ClientOptions?>(ClientOptions::class.java),
                "CommandEnvironment needs its options provider to have ClientOptions loaded."
            )

        this.clientEnv = makeMapFromMapEntries(clientOptions.getClientEnv())
        this.commandId = computeCommandId(commandOptions.getInvocationId(), warnings, attemptNumber)
        this.buildRequestId =
            if (commandOptions.getBuildRequestId() != null)
                commandOptions.getBuildRequestId()
            else
                if (buildRequestIdOverride != null)
                    buildRequestIdOverride
                else
                    UUID.randomUUID().toString()

        val repoEnvBuilder: TreeMap<String?, String?> =
            TreeMap<String?, String?>(
                if (commandOptions.getUseStrictRepoEnv())
                    com.google.common.collect.Maps.filterKeys<String?, String?>(
                        clientEnv,
                        com.google.common.base.Predicate { `object`: String? ->
                            ALWAYS_INHERITED_REPO_ENV.contains(`object`)
                        })
                else
                    clientEnv
            )
        val nonstrictRepoEnvBuilder: TreeMap<String?, String?> = TreeMap<String?, String?>(clientEnv)

        // TODO: This only needs to check for loads() rather than analyzes() due to
        //  the effect of --action_env on the repository env. Revert back to
        //  analyzes() when --action_env no longer affects it.
        if (command.buildPhase.loads() || command.name == "info") {
            // Compute the set of environment variables that are allowlisted on the commandline
            // for inheritance.
            for (envVar in options.getOptions<O?>(CoreOptions::class.java).getActionEnvironment()) {
                when (envVar) {
                    -> {
                        visibleActionEnv.remove(name)
                        if (!options.getOptions<CommonCommandOptions?>(CommonCommandOptions::class.java)
                                .getRepoEnvIgnoresActionEnv()
                        ) {
                            repoEnvBuilder.put(name, value)
                            nonstrictRepoEnvBuilder.put(name, value)
                        }
                    }

                    -> {
                        visibleActionEnv.add(name)
                    }

                    -> {
                        visibleActionEnv.remove(name)
                        if (!options.getOptions<CommonCommandOptions?>(CommonCommandOptions::class.java)
                                .getRepoEnvIgnoresActionEnv()
                        ) {
                            repoEnvBuilder.remove(name)
                            nonstrictRepoEnvBuilder.remove(name)
                        }
                    }
                }
            }
        }
        if (command.buildPhase.analyzes() || command.name == "info") {
            for (envVar in options.getOptions<O?>(TestOptions::class.java).getTestEnvironment()) {
                if (envVar is) {
                    visibleTestEnv.add(name)
                }
            }
        }

        var bazelWorkspace: String? = null
        if (workspace.getWorkspace() != null) {
            bazelWorkspace = workspace.getWorkspace().getPathString()
            // On Windows, convert forward slashes to backslashes for PATH-like variables.
            if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
                bazelWorkspace = bazelWorkspace.replace('/', '\\')
            }
        }
        for (envVar in commandOptions.getRepositoryEnvironment()) {
            when (envVar) {
                -> {
                    if (bazelWorkspace != null) {
                        value = value.replace("%bazel_workspace%", bazelWorkspace)
                    }
                    repoEnvBuilder.put(name, value)
                    nonstrictRepoEnvBuilder.put(name, value)
                }

                -> {
                    val value: String? = clientEnv.get(name)
                    if (value != null) {
                        repoEnvBuilder.put(name, value)
                        nonstrictRepoEnvBuilder.put(name, value)
                    }
                }

                -> {
                    repoEnvBuilder.remove(name)
                    nonstrictRepoEnvBuilder.remove(name)
                }
            }
        }
        this.repoEnv = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(repoEnvBuilder)
        this.nonstrictRepoEnv = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(nonstrictRepoEnvBuilder)
        this.buildResultListener = BuildResultListener()
        this.eventBus.register(this.buildResultListener)

        this.commandLinePathFactory =
            CommandLinePathFactory.create(runtime.getFileSystem(), directories)

        this.remoteAnalysisCachingEventListener = RemoteAnalysisCachingEventListener()
        this.eventBus.register(remoteAnalysisCachingEventListener)
    }

    @Throws(AbruptExitException::class)
    private fun computeWorkingDirectory(commandOptions: CommonCommandOptions): com.google.devtools.build.lib.vfs.Path {
        val workspace: com.google.devtools.build.lib.vfs.Path? = getWorkspace()
        val workingDirectory: com.google.devtools.build.lib.vfs.Path
        if (directories.inWorkspace()) {
            val clientCwd: PathFragment = commandOptions.getClientCwd()
            if (clientCwd.containsUplevelReferences()) {
                throw AbruptExitException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage("Client cwd '" + clientCwd + "' contains uplevel references")
                            .setClientEnvironment(
                                FailureDetails.ClientEnvironment.newBuilder()
                                    .setCode(FailureDetails.ClientEnvironment.Code.CLIENT_CWD_MALFORMED)
                                    .build()
                            )
                            .build()
                    )
                )
            }
            if (clientCwd.isAbsolute() && !clientCwd.startsWith(workspace.asFragment())) {
                throw AbruptExitException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(
                                java.lang.String.format(
                                    "Client cwd '%s' is not inside workspace '%s'", clientCwd, workspace
                                )
                            )
                            .setClientEnvironment(
                                FailureDetails.ClientEnvironment.newBuilder()
                                    .setCode(FailureDetails.ClientEnvironment.Code.CLIENT_CWD_MALFORMED)
                                    .build()
                            )
                            .build()
                    )
                )
            }
            workingDirectory = workspace.getRelative(clientCwd)
        } else {
            workingDirectory =
                com.google.devtools.build.lib.vfs.FileSystemUtils.getWorkingDirectory(runtime.getFileSystem())
        }
        return workingDirectory
    }

    fun getRuntime(): BlazeRuntime {
        return runtime
    }

    val clock: com.google.devtools.build.lib.clock.Clock?
        get() = runtime.getClock()

    fun getCommandExtensionReporter(): CommandExtensionReporter? {
        return commandExtensionReporter
    }

    fun notifyOnCrash(message: String?) {
        shutdownReasonConsumer.accept(message)
        if (java.lang.Thread.currentThread() != commandThread) {
            // Give shutdown hooks priority in JVM and stop generating more data for modules to consume.
            commandThread.interrupt()
        }
    }

    val startupOptionsProvider: com.google.devtools.common.options.OptionsProvider?
        get() = runtime.getStartupOptionsProvider()

    val blazeWorkspace: BlazeWorkspace
        get() = workspace

    fun getDirectories(): BlazeDirectories {
        return directories
    }

    // Null for commands that don't have PackageOptions (version, help, shutdown, etc).
    fun getPackageLocator(): PathPackageLocator? {
        return packageLocator
    }

    /** Returns the reporter for events.  */
    fun getReporter(): com.google.devtools.build.lib.events.Reporter {
        return reporter
    }

    val reporterOutErr: OutErr?
        // TODO: b/395157821 - Replace env.getReporter().getOutErr() with env.getReporterOutErr().
        get() = reporter.getOutErr()

    fun getEventBus(): com.google.common.eventbus.EventBus {
        return eventBus
    }

    fun getBlazeModuleEnvironment(): ModuleEnvironment {
        return blazeModuleEnvironment
    }

    /**
     * Return an unmodifiable view of the blaze client's environment when it invoked the current
     * command.
     */
    fun getClientEnv(): com.google.common.collect.ImmutableMap<String?, String?> {
        return clientEnv
    }

    fun getCommand(): com.google.devtools.build.lib.runtime.Command {
        return command
    }

    val commandName: String?
        get() = command.name

    fun getOptions(): com.google.devtools.common.options.OptionsParsingResult {
        return options
    }

    /** `--config` definitions for this invocation.  */
    fun getConfigFlagDefinitions(): ConfigFlagDefinitions? {
        return configFlagDefinitions
    }

    fun getInvocationPolicy(): InvocationPolicy? {
        return invocationPolicy
    }

    fun setInvocationPolicyFlags(invocationPolicyFlags: com.google.common.collect.ImmutableList<com.google.devtools.common.options.OptionAndRawValue?>?) {
        this.invocationPolicyFlags = invocationPolicyFlags
    }

    fun getInvocationPolicyFlags(): com.google.common.collect.ImmutableList<com.google.devtools.common.options.OptionAndRawValue?>? {
        return invocationPolicyFlags
    }

    val allowlistedActionEnv: MutableMap<String?, String?>
        /**
         * Return an ordered version of the client environment restricted to those variables allowlisted
         * by the command-line options to be inheritable by actions.
         */
        get() = filterClientEnv(visibleActionEnv)

    val allowlistedTestEnv: MutableMap<String?, String?>
        /**
         * Return an ordered version of the client environment restricted to those variables allowlisted
         * by the command-line options to be inheritable by actions.
         */
        get() = filterClientEnv(visibleTestEnv)

    /**
     * This should be the source of truth for whether this build should be run with merged analysis
     * and execution phases.
     */
    fun withMergedAnalysisAndExecutionSourceOfTruth(): Boolean {
        return mergedAnalysisAndExecution
    }

    fun setMergedAnalysisAndExecution(value: Boolean) {
        mergedAnalysisAndExecution = value
        this.skyframeExecutor
            .setMergedSkyframeAnalysisExecutionSupplier(
                java.util.function.Supplier { this.withMergedAnalysisAndExecutionSourceOfTruth() })
    }

    private fun filterClientEnv(vars: MutableSet<String?>): MutableMap<String?, String?> {
        val result: MutableMap<String?, String?> = TreeMap<String?, String?>()
        for (`var` in vars) {
            val value: String? = clientEnv.get(`var`)
            if (value != null) {
                result.put(`var`, value)
            }
        }
        return Collections.unmodifiableMap<String?, String?>(result)
    }

    private fun computeCommandId(idFromOptions: UUID?, warnings: MutableList<String?>, attemptNumber: Int): UUID {
        // TODO(b/67895628): Stop reading ids from the environment after the compatibility window has
        // passed.
        var commandId: UUID? = idFromOptions
        if (commandId == null) { // Try to set the clientId from the client environment.
            val uuidString: String? = clientEnv.getOrDefault("BAZEL_INTERNAL_INVOCATION_ID", "")
            if (!uuidString.isEmpty()) {
                try {
                    commandId = UUID.fromString(uuidString)
                    warnings.add(
                        "BAZEL_INTERNAL_INVOCATION_ID is set. This will soon be deprecated in favor of "
                                + "--invocation_id. Please switch to using the flag."
                    )
                } catch (e: java.lang.IllegalArgumentException) {
                    // String was malformed, so we will resort to generating a random UUID
                    return UUID.randomUUID()
                }
            } else {
                return UUID.randomUUID()
            }
        }
        // When retrying a command, the retry has to use a different command ID. BES backends can still
        // link the invocations since their build ID will be the same and the attempt number will be
        // increased.
        if (attemptNumber > 1) {
            return UUID.randomUUID()
        }
        return commandId
    }

    fun getTimestampGranularityMonitor(): TimestampGranularityMonitor {
        return timestampGranularityMonitor
    }

    val packageManager: PackageManager?
        get() = this.skyframeExecutor.getPackageManager()

    fun getRelativeWorkingDirectory(): PathFragment? {
        return relativeWorkingDirectory
    }

    fun getWaitTime(): java.time.Duration? {
        return waitTime
    }

    val outputListeners: MutableList<OutErr>
        get() {
            val result: MutableList<OutErr?> = java.util.ArrayList<OutErr?>()
            for (module in runtime.getBlazeModules()) {
                val listener: OutErr? = module.getOutputListener()
                if (listener != null) {
                    result.add(listener)
                }
            }
            return result
        }

    /**
     * Returns the UUID that Blaze uses to identify everything logged from the current build command.
     * It's also used to invalidate Skyframe nodes that are specific to a certain invocation, such as
     * the build info.
     */
    fun getCommandId(): UUID {
        return commandId
    }

    val skyframeExecutor: SkyframeExecutor
        get() = workspace.getSkyframeExecutor()

    val skyframeBuildView: SkyframeBuildView?
        get() = this.skyframeExecutor.getSkyframeBuildView()

    /**
     * Returns the working directory of the server.
     * 
     * 
     * This is often the first entry on the `--package_path`, but not always. Callers should
     * certainly not make this assumption. The Path returned may be null; for example, when the
     * command is invoked outside a workspace.
     */
    fun getWorkspace(): com.google.devtools.build.lib.vfs.Path? {
        return directories.getWorkingDirectory()
    }

    val workspaceName: String
        get() = runtime.getRuleClassProvider().getRunfilesPrefix()

    val outputBase: com.google.devtools.build.lib.vfs.Path
        /**
         * Returns the output base directory associated with this Blaze server process. This is the base
         * directory for shared Blaze state as well as tool and strategy specific subdirectories.
         */
        get() = directories.getOutputBase()

    val execRoot: com.google.devtools.build.lib.vfs.Path
        /**
         * Returns the execution root directory associated with this Blaze server process. This is where
         * all input and output files visible to the actual build reside.
         */
        get() = directories.getExecRoot(this.workspaceName)

    val actionTempsDirectory: com.google.devtools.build.lib.vfs.Path
        /**
         * Returns the directory where actions' temporary files will be written. Is below the directory
         * returned by [.getExecRoot].
         */
        get() = directories.getActionTempsDirectory(this.execRoot)

    /**
     * Returns the working directory of the `blaze` client process.
     * 
     * 
     * This may be equal to `BlazeRuntime#getWorkspace()`, or beneath it.
     * 
     * @see .getWorkspace
     */
    fun getWorkingDirectory(): com.google.devtools.build.lib.vfs.Path? {
        return workingDirectory
    }

    /**
     * Returns the [OutputService] to use, or `null` if this is not a command that
     * performs analysis according to [Command.buildPhase].
     */
    fun getOutputService(): OutputService? {
        return outputService
    }

    /**
     * Returns workspace information obtained from the [ ][com.google.devtools.build.lib.skyframe.DiffAwareness.View.getWorkspaceInfo] or null.
     * 
     * 
     * We store workspace info as an optimization to allow sharing of information about the
     * workspace if it was derived from the diff at the time of synchronizing the workspace. This way
     * we can make it available earlier during the build and avoid retrieving it again.
     */
    fun getWorkspaceInfoFromDiff(): WorkspaceInfoFromDiff? {
        return workspaceInfoFromDiff
    }

    val localResourceManager: ResourceManager?
        get() = resourceManager

    /**
     * Prevents any further interruption of this command by modules, and returns the final [ ] from modules, or null if no modules requested an abrupt exit.
     * 
     * 
     * Always returns the same value on subsequent calls.
     */
    fun finalizeDetailedExitCode(): DetailedExitCode? {
        // Set the pending exception so that further calls to exit(AbruptExitException) don't lead to
        // unwanted thread interrupts.
        if (pendingException.compareAndSet(null, java.util.Optional.empty<AbruptExitException?>())) {
            return null
        }
        if (java.lang.Thread.currentThread() === commandThread) {
            // We may have interrupted the thread in the process, so clear the interrupted bit.
            // Whether the command was interrupted or not, it's about to be over, so don't interrupt later
            // things happening on this thread.
            java.lang.Thread.interrupted()
        }
        // Extract the exit code (it can be null if someone has already called finalizeExitCode()).
        return this.pendingDetailedExitCode
    }

    private val pendingDetailedExitCode: DetailedExitCode?
        /** Returns the current exit code requested by modules, or null if no exit has been requested.  */
        get() {
            val exception: AbruptExitException? = getPendingException()
            return if (exception == null) null else exception.getDetailedExitCode()
        }

    /**
     * Retrieves the exception currently queued by a Blaze module.
     * 
     * 
     * Prefer [.getPendingDetailedExitCode] or [.throwPendingException] where
     * appropriate.
     */
    fun getPendingException(): AbruptExitException? {
        val abruptExitExceptionMaybe: java.util.Optional<AbruptExitException?>? = pendingException.get()
        return if (abruptExitExceptionMaybe == null) null else abruptExitExceptionMaybe.orElse(null)
    }

    /**
     * Throws the exception currently queued by a Blaze module.
     * 
     * 
     * This should be called as often as is practical so that errors are reported as soon as
     * possible. Ideally, we'd not need this, but the event bus swallows exceptions so we raise the
     * exception this way.
     */
    @Throws(AbruptExitException::class)
    fun throwPendingException() {
        val exception: AbruptExitException? = getPendingException()
        if (exception != null) {
            if (java.lang.Thread.currentThread() === commandThread) {
                // Throwing this exception counts as the requested interruption. Clear the interrupted bit.
                java.lang.Thread.interrupted()
            }
            throw exception
        }
    }

    /**
     * Initializes and syncs the graph with the given options, readying it for the next evaluation.
     * 
     * @throws IllegalStateException if the method has already been called in this environment.
     */
    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    fun syncPackageLoading(options: com.google.devtools.common.options.OptionsProvider) {
        // We want to ensure that we're never calling #syncPackageLoading twice in the same build
        // because it does the very expensive work of diffing the cache between incremental builds.
        // {@link SequencedSkyframeExecutor#handleDiffs} is the particular method we don't want to be
        // calling twice. We could feasibly factor it out of this call.
        check(!hasSyncedPackageLoading) { "We should never call this method more than once over the course of a single command" }
        hasSyncedPackageLoading = true
        workspaceInfoFromDiff =
            this.skyframeExecutor
                .sync(
                    reporter,
                    packageLocator,
                    commandId,
                    clientEnv,
                    timestampGranularityMonitor,
                    quiescingExecutors,
                    options,
                    this.commandName,
                    command.buildPhase.executes()
                )
    }

    /** Returns true if [.syncPackageLoading] has already been called.  */
    fun hasSyncedPackageLoading(): Boolean {
        return hasSyncedPackageLoading
    }

    fun recordLastExecutionTime() {
        workspace.recordLastExecutionTime(commandStartTime)
    }

    /**
     * Calls [SkyframeExecutor.decideKeepIncrementalState] with this command's options.
     * 
     * 
     * Must be called prior to [BlazeModule.beforeCommand] so that modules can use the result
     * of [SkyframeExecutor.tracksStateForIncrementality].
     */
    fun decideKeepIncrementalState() {
        val skyframeExecutor: SkyframeExecutor = this.skyframeExecutor
        skyframeExecutor.setActive(false)
        val commonOptions: CommonCommandOptions? =
            options.getOptions<CommonCommandOptions?>(CommonCommandOptions::class.java)
        val keepStateAfterBuildOption: KeepStateAfterBuildOption? =
            options.getOptions<KeepStateAfterBuildOption?>(KeepStateAfterBuildOption::class.java)
        val analysisOptions: O? = options.getOptions<O?>(AnalysisOptions::class.java)
        skyframeExecutor.decideKeepIncrementalState(
            runtime.getStartupOptionsProvider()
                .getOptions<BlazeServerStartupOptions?>(BlazeServerStartupOptions::class.java).getBatch(),
            keepStateAfterBuildOption.getKeepStateAfterBuild(),
            commonOptions.getTrackIncrementalState(),
            commonOptions.getHeuristicallyDropNodes(),
            analysisOptions != null && analysisOptions.getDiscardAnalysisCache(),
            reporter
        )
    }

    /**
     * Hook method called by the BlazeCommandDispatcher prior to the dispatch of each command.
     * 
     * 
     * Both [.decideKeepIncrementalState] and [BlazeModule.beforeCommand] on each
     * module should have already been called before this.
     * 
     * @throws AbruptExitException if this command is unsuitable to be run as specified
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(AbruptExitException::class)
    fun beforeCommand(invocationPolicy: InvocationPolicy?) {
        val commonOptions: CommonCommandOptions? =
            options.getOptions<CommonCommandOptions?>(CommonCommandOptions::class.java)
        eventBus.post(BuildMetadataEvent(makeMapFromMapEntries(commonOptions.getBuildMetadata())))
        eventBus.post(
            GotOptionsEvent(runtime.getStartupOptionsProvider(), options, invocationPolicy)
        )
        throwPendingException()

        outputService = null
        var outputModule: BlazeModule? = null
        if (command.buildPhase.analyzes() || command.name == "clean") {
            // Output service should only affect commands that execute actions, but due to the legacy
            // wiring of BuildTool.java, this covers analysis-only commands as well.
            //
            // TODO: fix this.
            for (module in runtime.getBlazeModules()) {
                val moduleService: OutputService? = module.getOutputService()
                if (moduleService != null) {
                    check(outputService == null) {
                        java.lang.String.format(
                            "More than one module (%s and %s) returns an output service",
                            module.getClass(), outputModule.getClass()
                        )
                    }
                    outputService = moduleService
                    outputModule = module
                }
            }
            if (outputService == null) {
                outputService = LocalOutputService(directories)
            }
        }

        val skyframeExecutor: SkyframeExecutor = this.skyframeExecutor
        skyframeExecutor.setOutputService(outputService)
        skyframeExecutor.noteCommandStart()

        // Start the performance and memory profilers.
        runtime.beforeCommand(this, commonOptions)

        eventBus.post(CommandStartEvent())

        // Modules that are subscribed to CommandStartEvent may create pending exceptions.
        throwPendingException()

        // Determine if Skyfocus will run for this command: Skyfocus runs only for commands that
        // execute actions. Throw an error if this is a command that is not guaranteed to work
        // correctly on a focused Skyframe graph.
        if (getCommand().buildPhase.executes()) {
            skyframeExecutor.prepareForSkyfocus(
                options.getOptions<SkyfocusOptions?>(SkyfocusOptions::class.java), reporter, runtime.productName
            )
        } else if (getCommand().buildPhase.loads()
            && !this.skyframeExecutor.getSkyfocusState().activeDirectories.isEmpty()
        ) {
            // A non-empty active directories implies a focused Skyframe state.
            throw AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(
                            (command.name
                                    + " is not supported after using Skyfocus because it can"
                                    + " return partial/incorrect results. Run clean or shutdown and try"
                                    + " again.")
                        )
                        .setSkyfocus(
                            Skyfocus.newBuilder()
                                .setCode(Skyfocus.Code.DISALLOWED_OPERATION_ON_FOCUSED_GRAPH)
                                .build()
                        )
                        .build()
                )
            )
        }
    }

    /**
     * Returns the environment for repository rules and module extensions, which is constructed as
     * follows:
     * 
     * 
     *  * the client environment as the base;
     *  * if `--experimental_strict_repo_env` is set, only the variables `PATH` and, on
     * Windows only, `PATHEXT` are kept;
     *  * if `--noincompatible_repo_env_ignores_action_env` is set, `--action_env` is
     * applied on top of that;
     *  * finally, `--repo_env` is applied on top of that.
     * 
     */
    fun getRepoEnv(): com.google.common.collect.ImmutableMap<String?, String?> {
        return repoEnv
    }

    /**
     * Returns the environment for inherently local, non-hermetic operations associated with
     * repository rules and module extensions, such as credential helpers. It is constructed as
     * follows:
     * 
     * 
     *  * the client environment as the base;
     *  * if `--noincompatible_repo_env_ignores_action_env` is set, `--action_env` is
     * applied on top of that;
     *  * finally, `--repo_env` is applied on top of that.
     * 
     * 
     * 
     * This differs from [.getRepoEnv] in that it does not apply `--experimental_strict_repo_env`, and thus always includes the full client environment as a
     * base.
     */
    fun getNonstrictRepoEnv(): com.google.common.collect.ImmutableMap<String?, String?> {
        return nonstrictRepoEnv
    }

    /** Use [.getXattrProvider] when possible: see documentation of [SyscallCache].  */
    fun getSyscallCache(): SyscallCache? {
        return syscallCache
    }

    val xattrProvider: XattrProvider?
        get() = syscallCache

    fun getQuiescingExecutors(): QuiescingExecutors? {
        return quiescingExecutors
    }

    /**
     * Returns the [ ][com.google.devtools.build.lib.server.CommandProtos.RunRequest.getCommandExtensions]
     * passed to the server for this command.
     * 
     * 
     * Extensions are arbitrary messages containing additional per-command information.
     */
    fun getCommandExtensions(): com.google.common.collect.ImmutableList<Any?> {
        return commandExtensions
    }

    /**
     * Returns the [ ][com.google.devtools.build.lib.server.CommandProtos.RunResponse.getCommandExtensions]
     * to be passed to the client for this command.
     * 
     * 
     * Extensions are arbitrary messages containing additional execution results.
     */
    fun getResponseExtensions(): com.google.common.collect.ImmutableList<Any?> {
        return responseExtensions.build()
    }

    fun addResponseExtensions(extensions: Iterable<Any?>) {
        responseExtensions.addAll(extensions)
    }

    fun getBuildResultListener(): BuildResultListener {
        return buildResultListener
    }

    fun getCommandLinePathFactory(): CommandLinePathFactory {
        return commandLinePathFactory
    }

    fun ensureBuildInfoPosted() {
        if (buildInfoPosted) {
            return
        }
        val workspaceStatus: com.google.common.collect.ImmutableSortedMap<String?, String?>? =
            workspace
                .getWorkspaceStatusActionFactory()
                .createDummyWorkspaceStatus(workspaceInfoFromDiff)
        eventBus.post(BuildInfoEvent(workspaceStatus))
    }

    @com.google.common.eventbus.Subscribe
    @Suppress("unused")
    fun gotBuildInfo(event: BuildInfoEvent?) {
        buildInfoPosted = true
    }

    @com.google.common.eventbus.Subscribe
    fun additionalConfigurationChangeEvent(event: AdditionalConfigurationChangeEvent) {
        additionalConfigurationChangeEvent = java.util.Optional.of<AdditionalConfigurationChangeEvent?>(event)
    }

    fun getAdditionalConfigurationChangeEvent(): java.util.Optional<AdditionalConfigurationChangeEvent?> {
        return additionalConfigurationChangeEvent
    }

    fun getHttpDownloader(): HttpDownloader {
        return httpDownloader
    }

    val downloaderDelegate: DelegatingDownloader
        get() = delegatingDownloader

    /**
     * Retrieves the idle tasks stats from a previous idle period, if this command was preceded by
     * one.
     */
    fun getIdleTaskResultsFromPreviousIdlePeriod(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask.Result?>? {
        return idleTaskResultsFromPreviousIdlePeriod
    }

    /** Registers a task to be executed during an idle period following this command.  */
    fun addIdleTask(idleTask: com.google.devtools.build.lib.server.IdleTask) {
        idleTasks.add(idleTask)
    }

    /** Returns the list of registered idle tasks.  */
    fun getIdleTasks(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask?> {
        return idleTasks.build()
    }

    fun setVersionGetter(versionGetter: LongVersionGetter?) {
        this.versionGetter = versionGetter
    }

    fun getVersionGetter(): LongVersionGetter? {
        return versionGetter
    }

    fun setUiEventHandler(uiEventHandler: UiEventHandler?) {
        com.google.common.base.Preconditions.checkState(this.uiEventHandler == null, "UiEventHandler already set")
        this.uiEventHandler = com.google.common.base.Preconditions.checkNotNull<UiEventHandler?>(uiEventHandler)
        eventBus.register(uiEventHandler)
        reporter.addHandler(uiEventHandler)
    }

    fun getUiEventHandler(): UiEventHandler {
        return com.google.common.base.Preconditions.checkNotNull<UiEventHandler>(
            uiEventHandler,
            "UiEventHandler was not set"
        )
    }

    companion object {
        private val ALWAYS_INHERITED_REPO_ENV: com.google.common.collect.ImmutableSet<String?> =
            if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) com.google.common.collect.ImmutableSet.of<String?>(
                "PATH",
                "PATHEXT"
            ) else com.google.common.collect.ImmutableSet.of<String?>("PATH")

        private fun makeMapFromMapEntries(
            mapEntryList: MutableList<MutableMap.MutableEntry<String?, String?>>
        ): com.google.common.collect.ImmutableMap<String?, String?> {
            val result: MutableMap<String?, String?> = TreeMap<String?, String?>()
            for (entry in mapEntryList) {
                result.put(entry.getKey(), entry.getValue())
            }
            return com.google.common.collect.ImmutableMap.copyOf<String?, String?>(result)
        }
    }
}
