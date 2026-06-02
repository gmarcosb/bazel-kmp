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

import com.google.devtools.build.lib.analysis.NoBuildEvent

/**
 * Dispatches to the Blaze commands; that is, given a command line, this abstraction looks up the
 * appropriate command object, parses the options required by the object, and calls its exec method.
 * Also, this object provides the runtime state (BlazeRuntime) to the commands.
 */
class BlazeCommandDispatcher private constructor(runtime: BlazeRuntime, serverPid: Int, bugReporter: BugReporter) :
    CommandDispatcher {
    private val runtime: BlazeRuntime
    private val serverPid: Int
    private val bugReporter: BugReporter
    private val commandLock: Any
    private var currentClientDescription: String? = null
    private val shutdownReason: AtomicReference<String?> = AtomicReference<String?>()
    private var logOutputStream: java.io.OutputStream? = null
    private val optionsDataCache: com.github.benmanes.caffeine.cache.LoadingCache<BlazeCommand?, com.google.devtools.common.options.OpaqueOptionsData?> =
        Caffeine.newBuilder()
            .build<BlazeCommand?, com.google.devtools.common.options.OpaqueOptionsData?>(
                object :
                    com.github.benmanes.caffeine.cache.CacheLoader<BlazeCommand?, com.google.devtools.common.options.OpaqueOptionsData?> {
                    override fun load(command: BlazeCommand): com.google.devtools.common.options.OpaqueOptionsData? {
                        return com.google.devtools.common.options.OptionsParser.getOptionsData(
                            BlazeCommandUtils.getOptions(
                                command.getClass(),
                                runtime.getOptionsSuppliers(),
                                runtime.getRuleClassProvider()
                            )
                        )
                    }
                })

    internal constructor(runtime: BlazeRuntime, serverPid: Int) : this(runtime, serverPid, runtime.getBugReporter())

    /** Convenience test-only constructor.  */
    @com.google.common.annotations.VisibleForTesting
    constructor(runtime: BlazeRuntime) : this(runtime, UNKNOWN_SERVER_PID, runtime.getBugReporter())

    /** Convenience test-only constructor.  */
    @com.google.common.annotations.VisibleForTesting
    internal constructor(runtime: BlazeRuntime, bugReporter: BugReporter) : this(
        runtime,
        UNKNOWN_SERVER_PID,
        bugReporter
    )

    init {
        this.runtime = runtime
        this.serverPid = serverPid
        this.bugReporter = bugReporter
        this.commandLock = Any()
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    override fun exec(
        invocationPolicy: InvocationPolicy?,
        args: MutableList<String>,
        outErr: OutErr,
        lockingMode: com.google.devtools.build.lib.runtime.CommandDispatcher.LockingMode,
        uiVerbosity: UiVerbosity?,
        clientDescription: String?,
        firstContactTimeMillis: Long,
        startupOptionsTaggedWithBazelRc: java.util.Optional<MutableList<com.google.devtools.build.lib.util.Pair<String?, String?>>?>,
        idleTaskResultsSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask.Result?>?>,
        commandExtensions: MutableList<Any?>?,
        commandExtensionReporter: CommandExtensionReporter?
    ): BlazeCommandResult {
        var args = args
        com.google.common.base.Preconditions.checkNotNull<String?>(clientDescription)
        if (args.isEmpty()) { // Default to help command if no arguments specified.
            args = HELP_COMMAND
        }

        var commandName = args.get(0)

        // Be gentle to users who want to find out about Blaze invocation.
        if (ALL_HELP_OPTIONS.contains(commandName)) {
            commandName = "help"
        }

        val command: BlazeCommand? = runtime.getCommandMap().get(commandName)
        if (command == null) {
            outErr.printErrLn(
                java.lang.String.format(
                    "Command '%s' not found. Try '%s help'.", commandName, runtime.productName
                )
            )
            return createDetailedCommandResult(
                java.lang.String.format("Command '%s' not found.", commandName),
                FailureDetails.Command.Code.COMMAND_NOT_FOUND
            )
        }

        // Take the exclusive server lock.  If we fail, we busy-wait until the lock becomes available.
        //
        // We used to rely on commandLock.wait() to lazy-wait for the lock to become available, which is
        // theoretically fine, but doing so prevents us from determining if the PID of the server
        // holding the lock has changed under the hood.  There have been multiple bug reports where
        // users (especially macOS ones) mention that the Blaze invocation hangs on a non-existent PID.
        // This should help troubleshoot those scenarios in case there really is a bug somewhere.
        var multipleAttempts = false
        val clockBefore: Long = com.google.devtools.build.lib.clock.BlazeClock.nanoTime()
        var otherClientDescription: String? = ""
        // TODO(ulfjack): Add lock acquisition to the profiler.
        synchronized(commandLock) {
            while (currentClientDescription != null) {
                when (lockingMode) {
                    com.google.devtools.build.lib.runtime.CommandDispatcher.LockingMode.WAIT -> {
                        if (otherClientDescription != currentClientDescription) {
                            val serverDescription =
                                if (serverPid == UNKNOWN_SERVER_PID) "" else (" (server_pid=" + serverPid + ")")
                            outErr.printErrLn(
                                java.lang.String.format(
                                    "Another command (%s) is running. Waiting for it to complete on the"
                                            + " server%s...",
                                    currentClientDescription, serverDescription
                                )
                            )
                            otherClientDescription = currentClientDescription
                        }
                        commandLock.wait(500)
                    }

                    com.google.devtools.build.lib.runtime.CommandDispatcher.LockingMode.ERROR_OUT -> {
                        val message: String? =
                            java.lang.String.format(
                                "Another command (%s) is running. Exiting immediately.",
                                currentClientDescription
                            )
                        outErr.printErrLn(message)
                        return createDetailedCommandResult(
                            message, FailureDetails.Command.Code.ANOTHER_COMMAND_RUNNING
                        )
                    }
                }

                multipleAttempts = true
            }
            currentClientDescription = clientDescription
        }
        // If we took the lock on the first try, force the reported wait time to 0 to avoid unnecessary
        // noise in the logs.  In this metric, we are only interested in knowing how long it took for
        // other commands to complete, not how fast acquiring a lock is.
        val waitTimeInMs: Long =
            if (!multipleAttempts) 0 else (com.google.devtools.build.lib.clock.BlazeClock.nanoTime() - clockBefore) / (1000L * 1000L)

        // Retrieve information about idle tasks that ran during a previous idle period.
        // We do this after obtaining the lock so that a non-blocking command doesn't cause this
        // information to be lost (instead, it will be forwarded to the next command).
        val idleTaskResultsFromPreviousIdlePeriod: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask.Result?>? =
            idleTaskResultsSupplier.get()

        try {
            val retrievedShutdownReason: String? = this.shutdownReason.get()
            if (retrievedShutdownReason != null) {
                outErr.printErrLn(retrievedShutdownReason)
                return createDetailedCommandResult(
                    retrievedShutdownReason, FailureDetails.Command.Code.PREVIOUSLY_SHUTDOWN
                )
            }
            var result: BlazeCommandResult
            var attemptNumber = 0
            val attemptedCommandIds: MutableSet<UUID?> = HashSet<UUID?>()
            var buildRequestIdOverride: String? = null
            while (true) {
                attemptNumber += 1
                try {
                    result =
                        execExclusively(
                            invocationPolicy,
                            args,
                            outErr,
                            uiVerbosity == UiVerbosity.QUIET,
                            firstContactTimeMillis,
                            commandName,
                            command,
                            waitTimeInMs,
                            startupOptionsTaggedWithBazelRc,
                            idleTaskResultsFromPreviousIdlePeriod,
                            commandExtensions,
                            attemptNumber,
                            attemptedCommandIds,
                            buildRequestIdOverride,
                            commandExtensionReporter
                        )
                    break
                } catch (e: RemoteCacheTransientErrorException) {
                    attemptedCommandIds.add(e.getCommandId())
                    // Use a fixed build request ID across cache eviction retries to tie together the
                    // individual invocations, which all have different invocation IDs.
                    buildRequestIdOverride = e.buildRequestId
                }
            }
            if (result.shutdown()) {
                setShutdownReason(
                    "Server shut down "
                            + (if (result.getExitCode().isInfrastructureFailure())
                        "due to a crash: " + result.getFailureDetail().getMessage()
                    else
                        "explicitly by client " + clientDescription)
                )
            }
            if (!result.getDetailedExitCode().isSuccess()) {
                logger.atInfo().log("Exit status was %s", result.getDetailedExitCode())
            }
            return result
        } finally {
            synchronized(commandLock) {
                currentClientDescription = null
                commandLock.notify()
            }
        }
    }

    /**
     * For testing ONLY. Same as [CommandDispatcher.exec] but automatically uses
     * the current time.
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(java.lang.InterruptedException::class)
    fun exec(args: MutableList<String>, clientDescription: String?, originalOutErr: OutErr): BlazeCommandResult {
        return exec(
            InvocationPolicy.getDefaultInstance(),
            args,
            originalOutErr,
            CommandDispatcher.LockingMode.ERROR_OUT,
            UiVerbosity.NORMAL,
            clientDescription,
            runtime.getClock().currentTimeMillis(),  /* startupOptionsTaggedWithBazelRc= */
            java.util.Optional.empty<MutableList<com.google.devtools.build.lib.util.Pair<String?, String?>?>?>(),  /* idleTaskResultsSupplier= */
            java.util.function.Supplier { com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.server.IdleTask.Result?>() },  /* commandExtensions= */
            com.google.common.collect.ImmutableList.of<Any?>(),  /* commandExtensionReporter= */
            CommandExtensionReporter { ext: Any? -> })
    }

    @Throws(RemoteCacheTransientErrorException::class)
    private fun execExclusively(
        invocationPolicy: InvocationPolicy?,
        args: MutableList<String>?,
        outErr: OutErr,
        quiet: Boolean,
        firstContactTime: Long,
        commandName: String,
        command: BlazeCommand,
        waitTimeInMs: Long,
        startupOptionsTaggedWithBazelRc: java.util.Optional<MutableList<com.google.devtools.build.lib.util.Pair<String?, String?>>?>,
        idleTaskResultsFromPreviousIdlePeriod: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask.Result?>?,
        commandExtensions: MutableList<Any?>?,
        attemptNumber: Int,
        attemptedCommandIds: MutableSet<UUID?>,
        buildRequestIdOverride: String?,
        commandExtensionReporter: CommandExtensionReporter?
    ): BlazeCommandResult {
        // Record the start time for the profiler. Do not put anything before this!
        var outErr: OutErr = outErr
        val execStartTimeNanos: Long = runtime.getClock().nanoTime()

        val commandAnnotation: com.google.devtools.build.lib.runtime.Command = command.getClass()
            .getAnnotation<com.google.devtools.build.lib.runtime.Command>(com.google.devtools.build.lib.runtime.Command::class.java)
        val workspace: BlazeWorkspace = runtime.getWorkspace()

        val storedEventHandler: StoredEventHandler = StoredEventHandler()
        // Provide the options parser so that we can cache OptionsData here.
        var optionsParser: com.google.devtools.common.options.OptionsParser = createOptionsParser(command)
        var optionHandler: BlazeOptionHandler =
            BlazeOptionHandler(
                runtime, workspace, command, commandAnnotation, optionsParser, invocationPolicy
            )
        val parseResults: DetailedParseResults =
            optionHandler.parseOptionsAndGetConfigDefinitions(
                args,
                storedEventHandler,  /* invocationPolicyFlagListBuilder= */
                com.google.common.collect.ImmutableList.builder<com.google.devtools.common.options.OptionAndRawValue?>()
            )
        var earlyExitCode: DetailedExitCode? = parseResults.detailedExitCode
        var options: com.google.devtools.common.options.OptionsParsingResult = optionHandler.getOptionsResult()

        // The initCommand call also records the start time for the timestamp granularity monitor.
        val commandEnvWarnings: MutableList<String?> = java.util.ArrayList<String?>()
        val env: CommandEnvironment =
            workspace.initCommand(
                commandAnnotation,
                options,
                invocationPolicy,
                commandEnvWarnings,
                waitTimeInMs,
                firstContactTime,
                idleTaskResultsFromPreviousIdlePeriod,
                java.util.function.Consumer { shutdownReason: String? -> this.setShutdownReason(shutdownReason) },
                commandExtensions,
                commandExtensionReporter,
                attemptNumber,
                buildRequestIdOverride,
                parseResults.configFlagDefinitions
            )

        if (attemptNumber > 1) {
            outErr.printErrLn("Found transient remote cache error, retrying the build...")
        }

        val commonOptions: CommonCommandOptions? =
            options.getOptions<CommonCommandOptions?>(CommonCommandOptions::class.java)
        var tracerEnabled = false
        if (commonOptions.getEnableTracer() == com.google.devtools.common.options.TriState.YES) {
            tracerEnabled = true
        } else if (commonOptions.getEnableTracer() == com.google.devtools.common.options.TriState.AUTO) {
            val commandSupportsProfile =
                commandName == "query" || commandAnnotation.buildPhase.analyzes()
            tracerEnabled = commandSupportsProfile || commonOptions.getProfilePath() != null
        }

        // TODO(ulfjack): Move the profiler initialization as early in the startup sequence as possible.
        // Profiler setup and shutdown must always happen in pairs. Shutdown is currently performed in
        // the afterCommand call in the finally block below.
        val profilerStartedEvent: ProfilerStartedEvent =
            runtime.initProfiler(
                tracerEnabled,
                storedEventHandler,
                workspace,
                options,
                env,
                execStartTimeNanos,
                waitTimeInMs
            )
        storedEventHandler.post(profilerStartedEvent)

        // Enable Starlark CPU profiling (--starlark_cpu_profile=/tmp/foo.pprof.gz)
        var success = false
        if (!commonOptions.getStarlarkCpuProfile().isEmpty()) {
            val out: java.io.OutputStream?
            try {
                val starlarkCpuProfile: InstrumentationOutput =
                    runtime
                        .getInstrumentationOutputFactory()
                        .createInstrumentationOutput( /* name= */
                            "starlarkCpuProfile",
                            PathFragment.create(commonOptions.getStarlarkCpuProfile()),
                            DestinationRelativeTo.WORKING_DIRECTORY_OR_HOME,
                            env,
                            storedEventHandler,  /* append= */
                            null,  /* internal= */
                            null
                        )
                out = starlarkCpuProfile.createOutputStream()
            } catch (ex: IOException) {
                val message = "Starlark CPU profiler: " + ex.getMessage()
                outErr.printErrLn(message)
                return createDetailedCommandResult(
                    message, FailureDetails.Command.Code.STARLARK_CPU_PROFILE_FILE_INITIALIZATION_FAILURE
                )
            }
            try {
                success = net.starlark.java.eval.Starlark.startCpuProfile(out, java.time.Duration.ofMillis(10))
            } catch (ex: java.lang.IllegalStateException) { // e.g. SIGPROF in use
                val message: String = com.google.common.base.Strings.nullToEmpty(ex.getMessage())
                outErr.printErrLn(message)
                return createDetailedCommandResult(
                    message, FailureDetails.Command.Code.STARLARK_CPU_PROFILING_INITIALIZATION_FAILURE
                )
            }
        }

        var result: BlazeCommandResult =
            createDetailedCommandResult(
                "Unknown command failure", FailureDetails.Command.Code.COMMAND_FAILURE_UNKNOWN
            )
        var needToCallAfterCommand = true
        val reporter: com.google.devtools.build.lib.events.Reporter = env.getReporter()
        val systemOutErrPatcher: SystemPatcher = reporter.getOutErr().getSystemPatcher()
        try {
            // Both the call to env.decideKeepIncrementalState() and module.beforeCommand() may emit
            // events, but the reporter isn't setup yet. Use a stored event handler to catch those events.
            reporter.addHandler(storedEventHandler)
            env.decideKeepIncrementalState()
            for (module in runtime.getBlazeModules()) {
                try {
                    com.google.devtools.build.lib.profiler.Profiler.instance()
                        .profile(module.toString() + ".beforeCommand").use { closeable ->
                            module.beforeCommand(env)
                        }
                } catch (e: AbruptExitException) {
                    logger.atInfo().withCause(e).log("Error in %s", module)
                    // Don't let one module's complaints prevent the other modules from doing necessary
                    // setup. We promised to call beforeCommand exactly once per-module before each command
                    // and will be calling afterCommand soon in the future - a module's afterCommand might
                    // rightfully assume its beforeCommand has already been called.
                    storedEventHandler.handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))

                    // Use the highest priority exit code, or the first one that is encountered if all exit
                    // codes have equivalent priority.
                    earlyExitCode = DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                        earlyExitCode,
                        e.getDetailedExitCode()
                    )
                }
            }
            reporter.removeHandler(storedEventHandler)

            // Setup stdout / stderr.
            outErr = tee(outErr, env.getOutputListeners())

            // Early exit. We need to guarantee that the ErrOut and Reporter setup below never error out,
            // so any invariants they need must be checked before this point.
            if (!earlyExitCode.isSuccess()) {
                replayEarlyExitEvents(
                    outErr,
                    optionHandler,
                    storedEventHandler,
                    env,
                    NoBuildEvent(
                        commandName, firstContactTime, false, true, env.getCommandId().toString()
                    )
                )
                result = BlazeCommandResult.Companion.detailedExitCode(earlyExitCode)
                return result
            }

            com.google.devtools.build.lib.profiler.Profiler.instance().profile("setup event handler").use { closeable ->
                val eventHandlerOptions: UiOptions? = options.getOptions<UiOptions?>(UiOptions::class.java)
                var colorfulOutErr: OutErr = outErr

                if (!eventHandlerOptions.useColor()) {
                    if (!commandAnnotation.binaryStdOut) {
                        outErr = ansiStripOut(outErr)
                        colorfulOutErr = ansiStripOut(colorfulOutErr)
                    }
                    if (!commandAnnotation.binaryStdErr) {
                        outErr = ansiStripErr(outErr)
                        colorfulOutErr = ansiStripErr(colorfulOutErr)
                    }
                }

                if (!commandAnnotation.binaryStdOut) {
                    outErr = bufferOut(outErr)
                }

                if (!commandAnnotation.binaryStdErr) {
                    outErr = bufferErr(outErr)
                }

                DebugLoggerConfigurator.setupLogging(commonOptions.getVerbosity())

                val newStatsSummary =
                    options.getOptions<O?>(ExecutionOptions::class.java) != null
                            && options.getOptions<O?>(ExecutionOptions::class.java).statsSummary
                val handler: UiEventHandler =
                    createEventHandler(outErr, eventHandlerOptions, quiet, env, newStatsSummary)
                env.setUiEventHandler(handler)

                // We register an ANSI-allowing handler associated with {@code handler} so that ANSI control
                // codes can be re-introduced later even if blaze is invoked with --color=no. This is useful
                // for commands such as 'blaze run' where the output of the final executable shouldn't be
                // modified.
                if (!eventHandlerOptions.useColor()) {
                    val ansiAllowingHandler: UiEventHandler =
                        createEventHandler(colorfulOutErr, eventHandlerOptions, quiet, env, newStatsSummary)
                    reporter.registerAnsiAllowingHandler(handler, ansiAllowingHandler)
                    env.getEventBus().register(PassiveExperimentalEventHandler(ansiAllowingHandler))
                }
            }
            warnIfUsingUnusupportedEncoding(runtime.productName, reporter)

            com.google.devtools.build.lib.profiler.Profiler.instance().profile("replay stored events")
                .use { closeable ->
                    // Now we're ready to replay the events.
                    storedEventHandler.replayOn(reporter)
                    for (warning in commandEnvWarnings) {
                        reporter.handle(com.google.devtools.build.lib.events.Event.warn(warning))
                    }
                }
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("announce rc options").use { closeable ->
                if (commonOptions.getAnnounceRcOptions()) {
                    if (startupOptionsTaggedWithBazelRc.isPresent()) {
                        var lastBlazerc = ""
                        var accumulatedStartupOptions: MutableList<String?> = java.util.ArrayList<String?>()
                        for (option in startupOptionsTaggedWithBazelRc.get()) {
                            // Do not include the command line options, marked by the empty string.
                            if (option.getFirst().isEmpty()) {
                                continue
                            }

                            // If we've moved to a new blazerc in the list, print out the info from the last one,
                            // and clear the accumulated list.
                            if (!lastBlazerc.isEmpty() && option.getFirst() != lastBlazerc) {
                                val logMessage: String? =
                                    java.lang.String.format(
                                        "Reading 'startup' options from %s: %s",
                                        lastBlazerc, java.lang.String.join(", ", accumulatedStartupOptions)
                                    )
                                reporter.handle(com.google.devtools.build.lib.events.Event.info(logMessage))
                                accumulatedStartupOptions = java.util.ArrayList<String?>()
                            }

                            lastBlazerc = option.getFirst()
                            accumulatedStartupOptions.add(option.getSecond())
                        }
                        // Print out the final blazerc-grouped list, if any startup options were provided by
                        // blazerc.
                        if (!lastBlazerc.isEmpty()) {
                            val logMessage: String? =
                                java.lang.String.format(
                                    "Reading 'startup' options from %s: %s",
                                    lastBlazerc, java.lang.String.join(", ", accumulatedStartupOptions)
                                )
                            reporter.handle(com.google.devtools.build.lib.events.Event.info(logMessage))
                        }
                    }
                    for (note in optionHandler.getRcfileNotes()) {
                        reporter.handle(com.google.devtools.build.lib.events.Event.info(note))
                    }
                }
            }
            // While a Blaze command is active, direct all errors to the client's event handler (and
            // out/err streams).
            systemOutErrPatcher.start()

            try {
                com.google.devtools.build.lib.profiler.Profiler.instance().profile("CommandEnv.beforeCommand")
                    .use { closeable ->
                        // Notify the BlazeRuntime, so it can do some initial setup.
                        env.beforeCommand(invocationPolicy)
                    }
            } catch (e: AbruptExitException) {
                logger.atInfo().withCause(e).log("Error before command")
                reporter.handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                result = BlazeCommandResult.Companion.detailedExitCode(e.getDetailedExitCode())
                return result
            }

            for (module in runtime.getBlazeModules()) {
                com.google.devtools.build.lib.profiler.Profiler.instance()
                    .profile(module.toString() + ".injectExtraPrecomputedValues").use { closeable ->
                        env.getSkyframeExecutor().injectExtraPrecomputedValues(module.getPrecomputedValues())
                    }
            }

            if (env.getCommand().buildPhase.analyzes()) {
                try {
                    env.syncPackageLoading(options)
                } catch (e: java.lang.InterruptedException) {
                    java.lang.Thread.currentThread().interrupt()
                    val message = "command interrupted while syncing package loading"
                    reporter.handle(com.google.devtools.build.lib.events.Event.error(message))
                    earlyExitCode = InterruptedFailureDetails.detailedExitCode(message)
                } catch (e: AbruptExitException) {
                    logger.atInfo().withCause(e).log("Error package loading")
                    reporter.handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                    earlyExitCode = e.getDetailedExitCode()
                }
                if (!earlyExitCode.isSuccess()) {
                    reporter.post(
                        NoBuildEvent(
                            commandName, firstContactTime, false, true, env.getCommandId().toString()
                        )
                    )
                    result = BlazeCommandResult.Companion.detailedExitCode(earlyExitCode)
                    return result
                }

                // Compute the repo mapping of the main repo and re-parse options so that we get correct
                // values for label-typed options.
                env.getEventBus().post(MainRepoMappingComputationStartingEvent())
                try {
                    com.google.devtools.build.lib.profiler.Profiler.instance()
                        .profile(ProfilerTask.BZLMOD, "compute main repo mapping").use { c ->
                            val mainRepoMapping: RepositoryMapping? =
                                env.getSkyframeExecutor().getMainRepoMapping(reporter)
                            optionsParser = optionsParser.toBuilder().withConversionContext(mainRepoMapping).build()
                            // Collect MODULE.bazel flag_alias(name = "foo", starlark_flag = "//bar") entries, so when
                            // builds set "--foo=1", that maps to "--//bar=1". Inject this as an implicit
                            // "--flag_alias=foo=//bar" flag. This is because select()s and configuration transitions
                            // really on that flag (CoreOptions.getCommandLineFlagAliases()) to properly handle
                            // aliases.
                            optionsParser.parse(
                                com.google.devtools.common.options.OptionPriority.PriorityCategory.RC_FILE,
                                "module resolution",
                                env.getSkyframeExecutor().getFlagAliases(reporter).entrySet().stream()
                                    .map<String?>(java.util.function.Function { e: MutableMap.MutableEntry<String?, String?>? ->
                                        java.lang.String.format(
                                            "--flag_alias=%s=%s",
                                            e.getKey(),
                                            e.getValue()
                                        )
                                    })
                                    .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
                            )
                        }
                } catch (e: java.lang.InterruptedException) {
                    java.lang.Thread.currentThread().interrupt()
                    val message = "command interrupted while computing main repo mapping"
                    logger.atInfo().withCause(e).log("%s", message)
                    reporter.handle(com.google.devtools.build.lib.events.Event.error(message))
                    earlyExitCode = InterruptedFailureDetails.detailedExitCode(message)
                } catch (e: RepositoryMappingResolutionException) {
                    logger.atInfo().withCause(e).log("Error computing main repo mapping")
                    reporter.handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                    earlyExitCode = e.getDetailedExitCode()
                }
                if (!earlyExitCode.isSuccess()) {
                    reporter.post(
                        NoBuildEvent(
                            commandName, firstContactTime, false, true, env.getCommandId().toString()
                        )
                    )
                    result = BlazeCommandResult.Companion.detailedExitCode(earlyExitCode)
                    return result
                }
                com.google.devtools.build.lib.profiler.Profiler.instance()
                    .profile(ProfilerTask.BZLMOD, "reparse options with main repo mapping").use { c ->
                        optionHandler =
                            BlazeOptionHandler(
                                runtime, workspace, command, commandAnnotation, optionsParser, invocationPolicy
                            )
                        val invocationPolicyFlagListBuilder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.common.options.OptionAndRawValue?> =
                            com.google.common.collect.ImmutableList.builder<com.google.devtools.common.options.OptionAndRawValue?>()
                        // Do not handle any events since this is the second time we parse the options.
                        earlyExitCode =
                            optionHandler.parseOptions(
                                args, ExtendedEventHandler.NOOP, invocationPolicyFlagListBuilder
                            )
                        env.setInvocationPolicyFlags(invocationPolicyFlagListBuilder.build())
                    }
                if (!earlyExitCode.isSuccess()) {
                    reporter.post(
                        NoBuildEvent(
                            commandName, firstContactTime, false, true, env.getCommandId().toString()
                        )
                    )
                    result = BlazeCommandResult.Companion.detailedExitCode(earlyExitCode)
                    return result
                }
            }

            com.google.devtools.build.lib.profiler.Profiler.instance()
                .profile(ProfilerTask.BZLMOD, "parse starlark options").use { c ->
                    earlyExitCode = optionHandler.parseStarlarkOptions(env, args)
                }
            if (!earlyExitCode.isSuccess()) {
                reporter.post(
                    NoBuildEvent(
                        commandName, firstContactTime, false, true, env.getCommandId().toString()
                    )
                )
                result = BlazeCommandResult.Companion.detailedExitCode(earlyExitCode)
                return result
            }
            options = optionHandler.getOptionsResult()

            val includeResidueInRunBepEvent =
                env.getOptions().getOptions<O?>(BuildEventProtocolOptions::class.java) != null
                        && env.getOptions()
                    .getOptions<O?>(BuildEventProtocolOptions::class.java).includeResidueInRunBepEvent
            // Log the command line now that the modules have all had a change to register their listeners
            // to the event bus, and the flags have been re-parsed.
            val originalCommandLineEvent: CommandLineEvent =
                OriginalCommandLineEvent(
                    runtime.productName,
                    runtime.getStartupOptionsProvider(),
                    commandName,
                    options.getResidue(),
                    includeResidueInRunBepEvent,
                    options.asListOfExplicitOptions(),
                    options.getExplicitCommandLineStarlarkOptions(),
                    options.getStarlarkOptionsAllowingMultiple(),
                    startupOptionsTaggedWithBazelRc
                )
            val canonicalCommandLineEvent: CommandLineEvent =
                CanonicalCommandLineEvent(
                    runtime.productName,
                    runtime.getStartupOptionsProvider(),
                    commandName,
                    options.getResidue(),
                    includeResidueInRunBepEvent,
                    options.getExplicitCommandLineStarlarkOptions(),
                    options.getStarlarkOptions(),
                    options.getStarlarkOptionsAllowingMultiple(),
                    options.asListOfCanonicalOptions(),  // If this is a command that analyzes with BuildTool, PROJECT.scl might set extra
                    // canonical flags. In that case give BuildTool a chance to post a final updated
                    // CanonicalCommandLineEvent. Then this one is dropped. But if that event doesn't post
                    // for any reason, including a build error or crash, post this one so the build still
                    // registers a canonical command line. That guarantees BuildEventStream always posts
                    // exactly one CanonicalCommandLineEvent message for all builds.
                    /* replaceable= */
                    commandAnnotation.buildPhase.analyzes()
                )
            val unstructuredServerCommandLineEvent: OriginalUnstructuredCommandLineEvent?
            if (commandName == "run" && !includeResidueInRunBepEvent) {
                unstructuredServerCommandLineEvent =
                    OriginalUnstructuredCommandLineEvent.REDACTED_UNSTRUCTURED_COMMAND_LINE_EVENT
            } else {
                unstructuredServerCommandLineEvent = OriginalUnstructuredCommandLineEvent(args)
            }
            env.getEventBus().post(unstructuredServerCommandLineEvent)
            env.getEventBus().post(originalCommandLineEvent)
            env.getEventBus().post(canonicalCommandLineEvent)
            env.getEventBus().post(commonOptions.getToolCommandLine())

            // Run the command.
            result = command.exec(env, options)

            val moduleExitCode: DetailedExitCode? = env.finalizeDetailedExitCode()
            // If Blaze did not suffer an infrastructure failure, check for errors in modules.
            if (!result.getExitCode().isInfrastructureFailure() && moduleExitCode != null) {
                result = BlazeCommandResult.Companion.detailedExitCode(moduleExitCode)
            }

            // Finalize the Starlark CPU profile.
            if (!commonOptions.getStarlarkCpuProfile().isEmpty() && success) {
                try {
                    net.starlark.java.eval.Starlark.stopCpuProfile()
                } catch (ex: IOException) {
                    val message = "Starlark CPU profiler: " + ex.getMessage()
                    reporter.handle(com.google.devtools.build.lib.events.Event.error(message))
                    if (result.getDetailedExitCode().isSuccess()) { // don't clobber existing error
                        result =
                            createDetailedCommandResult(
                                message, FailureDetails.Command.Code.STARLARK_CPU_PROFILE_FILE_WRITE_FAILURE
                            )
                    }
                }
            }

            needToCallAfterCommand = false
            val newResult: BlazeCommandResult = runtime.afterCommand( /* forceKeepStateForTesting= */false, env, result)
            if (newResult.getExitCode() == ExitCode.REMOTE_CACHE_EVICTED) {
                val executionOptions: T =
                    com.google.common.base.Preconditions.checkNotNull<T>(options.getOptions<O?>(ExecutionOptions::class.java))
                if (attemptedCommandIds.size() < executionOptions.remoteRetryOnTransientCacheError) {
                    throw RemoteCacheTransientErrorException(env.getBuildRequestId(), env.getCommandId())
                }
            }

            return newResult
        } catch (e: RemoteCacheTransientErrorException) {
            throw e
        } catch (e: Throwable) {
            logger.atSevere().withCause(e).log("Shutting down due to exception")
            val crash: Crash = Crash.from(e)
            bugReporter.handleCrash(crash, CrashContext.keepAlive().withArgs(args))
            needToCallAfterCommand = false // We are crashing.
            result = BlazeCommandResult.Companion.createShutdown(crash)
            return result
        } finally {
            try {
                // Profiler might still be running when an exception is thrown before BuildCompleteEvent is
                // emitted or BlazeModule#completeCommand() is called. So we still need to try to stop the
                // profiler here.
                com.google.devtools.build.lib.profiler.Profiler.instance().stop()
                if (profilerStartedEvent.profile is LocalInstrumentationOutput) {
                    profile.makeConvenienceLink()
                }
            } catch (e: IOException) {
                env.getReporter()
                    .handle(com.google.devtools.build.lib.events.Event.error("Error while writing profile file: " + e.getMessage()))
            }
            com.google.devtools.build.lib.profiler.Profiler.instance().clear()

            if (needToCallAfterCommand) {
                val newResult: BlazeCommandResult = runtime.afterCommand(false, env, result)
                if (newResult != result) {
                    logger.atWarning().log("afterCommand yielded different result: %s %s", result, newResult)
                }
            }

            MemoryProfiler.instance().stop()

            // Swallow IOException, as we are already in a finally clause
            com.google.common.io.Flushables.flushQuietly(outErr.getOutputStream())
            com.google.common.io.Flushables.flushQuietly(outErr.getErrorStream())

            systemOutErrPatcher.close()

            env.getTimestampGranularityMonitor().waitForTimestampGranularity(outErr)
        }
    }

    private class RemoteCacheTransientErrorException(// Remains constant across retries.
        val buildRequestId: String?, commandId: UUID?
    ) : IOException() {
        // Changes across retries.
        private val commandId: UUID?

        init {
            this.commandId = commandId
        }

        fun getCommandId(): UUID? {
            return commandId
        }
    }

    private fun bufferOut(outErr: OutErr): OutErr {
        val wrappedOut: java.io.OutputStream = BufferedOutputStream(outErr.getOutputStream())
        return OutErr.create(wrappedOut, outErr.getErrorStream())
    }

    private fun bufferErr(outErr: OutErr): OutErr {
        val wrappedErr: java.io.OutputStream = BufferedOutputStream(outErr.getErrorStream())
        return OutErr.create(outErr.getOutputStream(), wrappedErr)
    }

    private fun ansiStripOut(outErr: OutErr): OutErr {
        val wrappedOut: java.io.OutputStream = AnsiStrippingOutputStream(outErr.getOutputStream())
        return OutErr.create(wrappedOut, outErr.getErrorStream())
    }

    private fun ansiStripErr(outErr: OutErr): OutErr {
        val wrappedErr: java.io.OutputStream = AnsiStrippingOutputStream(outErr.getErrorStream())
        return OutErr.create(outErr.getOutputStream(), wrappedErr)
    }

    private fun tee(outErr: OutErr, additionalOutErrs: MutableList<OutErr>): OutErr {
        if (additionalOutErrs.isEmpty()) {
            return outErr
        }
        val result: DelegatingOutErr = DelegatingOutErr()
        result.addSink(outErr)
        for (additionalOutErr in additionalOutErrs) {
            result.addSink(additionalOutErr)
        }
        return result
    }

    private fun closeSilently(logOutputStream: java.io.OutputStream?) {
        if (logOutputStream != null) {
            try {
                logOutputStream.close()
            } catch (e: IOException) {
                LoggingUtil.logToRemote(java.util.logging.Level.WARNING, "Unable to close command.log", e)
            }
        }
    }

    /**
     * Creates an option parser using the common options classes and the command-specific options
     * classes.
     * 
     * 
     * An overriding method should first call this method and can then override default values
     * directly or by calling [BlazeOptionHandler.parseOptions] for command-specific options.
     */
    @Throws(com.google.devtools.common.options.ConstructionException::class)
    private fun createOptionsParser(command: BlazeCommand): com.google.devtools.common.options.OptionsParser {
        val optionsData: com.google.devtools.common.options.OpaqueOptionsData?
        optionsData = optionsDataCache.get(command)
        val annotation: com.google.devtools.build.lib.runtime.Command = command.getClass()
            .getAnnotation<com.google.devtools.build.lib.runtime.Command>(com.google.devtools.build.lib.runtime.Command::class.java)
        val parser: com.google.devtools.common.options.OptionsParser =
            com.google.devtools.common.options.OptionsParser.builder()
                .optionsData(optionsData)
                .skipStarlarkOptionPrefixes()
                .allowResidue(annotation.allowResidue)
                .withAliasFlag(CoreOptionConverters.BLAZE_ALIASING_FLAG)
                .build()
        return parser
    }

    /** Returns the event handler to use for this Blaze command.  */
    private fun createEventHandler(
        outErr: OutErr,
        eventOptions: UiOptions,
        quiet: Boolean,
        env: CommandEnvironment,
        newStatsSummary: Boolean
    ): UiEventHandler {
        val workspacePath: com.google.devtools.build.lib.vfs.Path? =
            runtime.getWorkspace().getDirectories().getWorkspace()
        val workspacePathFragment: PathFragment? = if (workspacePath == null) null else workspacePath.asFragment()
        return UiEventHandler(
            outErr,
            eventOptions,
            quiet,
            runtime.getClock(),
            env.getEventBus(),
            workspacePathFragment,
            env.withMergedAnalysisAndExecutionSourceOfTruth(),
            newStatsSummary
        )
    }

    /** Returns the runtime instance shared by the commands that this dispatcher dispatches to.  */
    @com.google.common.annotations.VisibleForTesting
    fun getRuntime(): BlazeRuntime {
        return runtime
    }

    /**
     * Shuts down all the registered commands to give them a chance to cleanup or close resources.
     * Should be called by the owner of this command dispatcher in all termination cases.
     */
    fun shutdown() {
        closeSilently(logOutputStream)
        logOutputStream = null
    }

    private fun setShutdownReason(shutdownReason: String?) {
        this.shutdownReason.compareAndSet(null, shutdownReason)
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        val UNKNOWN_SERVER_PID: Int = -1

        private val HELP_COMMAND: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("help")

        private val ALL_HELP_OPTIONS: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>("--help", "-help", "-h")

        private fun replayEarlyExitEvents(
            outErr: OutErr?,
            optionHandler: BlazeOptionHandler,
            storedEventHandler: StoredEventHandler,
            env: CommandEnvironment,
            noBuildEvent: NoBuildEvent
        ) {
            val printingEventHandler: PrintingEventHandler =
                PrintingEventHandler(outErr, com.google.devtools.build.lib.events.EventKind.ALL_EVENTS)

            val badOption: java.util.Optional<String?> = retrieveBadOption(storedEventHandler.getEvents())

            for (note in optionHandler.getRcfileNotes()) {
                if (badOption.isPresent()) {
                    if (note.contains(badOption.get())) {
                        printingEventHandler.handle(com.google.devtools.build.lib.events.Event.info(note))
                    }
                }
            }
            for (event in storedEventHandler.getEvents()) {
                printingEventHandler.handle(event)
            }
            for (post in storedEventHandler.getPosts()) {
                env.getEventBus().post(post)
            }
            env.getEventBus().post(noBuildEvent)
        }

        private fun retrieveBadOption(events: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?>): java.util.Optional<String?> {
            return events.stream()
                .filter(java.util.function.Predicate { e: com.google.devtools.build.lib.events.Event? -> e.getTag() != null && e.getTag() == BlazeOptionHandler.Companion.BAD_OPTION_TAG })
                .map<String?>(java.util.function.Function { obj: com.google.devtools.build.lib.events.Event? -> obj.getMessage() })
                .filter(java.util.function.Predicate { message: String? -> message.contains(BlazeOptionHandler.Companion.ERROR_SEPARATOR) })
                .map<String?>(java.util.function.Function { message: String? ->
                    message.substring(
                        0,
                        message.indexOf(BlazeOptionHandler.Companion.ERROR_SEPARATOR)
                    )
                })
                .findFirst()
        }

        private fun createDetailedCommandResult(
            message: String?, detailedCode: FailureDetails.Command.Code?
        ): BlazeCommandResult {
            return BlazeCommandResult.Companion.detailedExitCode(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setCommand(FailureDetails.Command.newBuilder().setCode(detailedCode))
                        .build()
                )
            )
        }

        private fun warnIfUsingUnusupportedEncoding(
            productName: String?,
            reporter: com.google.devtools.build.lib.events.Reporter
        ) {
            // The user can only influence the JVM's encoding on Linux. See blaze.cc for details.
            if (com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.LINUX) {
                return
            }
            val sunJnuEncoding: java.nio.charset.Charset =
                java.nio.charset.Charset.forName(java.lang.System.getProperty("sun.jnu.encoding"))
            if (sunJnuEncoding != java.nio.charset.StandardCharsets.UTF_8 && sunJnuEncoding != java.nio.charset.StandardCharsets.ISO_8859_1) {
                reporter.handle(
                    com.google.devtools.build.lib.events.Event.warn(
                        "%1\$s has been started with an unsupported encoding (%2\$s) and may not support Unicode filenames. Make sure that the C.UTF-8 or en_US.UTF-8 locale is installed on your system and restart %1\$s."
                            .formatted(productName, sunJnuEncoding)
                    )
                )
            }
        }
    }
}
