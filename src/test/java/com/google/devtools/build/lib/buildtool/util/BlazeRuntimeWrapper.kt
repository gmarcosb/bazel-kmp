// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool.util

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import com.google.common.eventbus.EventBus
import com.google.devtools.build.lib.clock.JavaClock
import com.google.devtools.build.lib.events.Reporter
import com.google.devtools.build.lib.profiler.Profiler
import com.google.devtools.build.lib.query2.engine.QueryParser
import com.google.devtools.build.lib.runtime.Command.BuildPhase.NONE
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.*
import java.util.Map
import java.util.function.Function
import java.util.logging.Level
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet

/**
 * A wrapper for [BlazeRuntime] for testing purposes that makes it possible to exercise (most)
 * of the build machinery in integration tests. Note that `BlazeCommandDispatcher` is not
 * exercised here.
 */
open class BlazeRuntimeWrapper internal constructor(
    events: EventCollectionApparatus,
    serverDirectories: ServerDirectories?,
    directories: BlazeDirectories?,
    binTools: BinTools?,
    builder: BlazeRuntime.Builder
) {
    private val runtime: BlazeRuntime
    private var env: CommandEnvironment? = null
    private val events: EventCollectionApparatus
    private var command: BlazeCommand? = null

    private var lastRequest: BuildRequest? = null
    private var lastResult: BuildResult? = null
    private var lastCommandResult: BlazeCommandResult? = null
    private var configuration: BuildConfigurationValue? = null

    private var optionsParser: OptionsParser? = null
    private val optionsToParse: MutableList<String?> = ArrayList<String?>()
    private val starlarkOptions: MutableMap<String?, Any?> = HashMap<String?, Any?>()
    private val starlarkOptionAllowingMultiple: MutableSet<String?> = HashSet<String?>()
    private val additionalOptionsClasses: MutableList<Class<out OptionsBase?>?> = ArrayList<Class<out OptionsBase?>?>()
    val crashMessages: MutableList<String?> = ArrayList<String?>()

    private val eventBusSubscribers: MutableList<Any> = ArrayList<Any>()

    private val workspaceSetupWarnings: MutableList<String?> = ArrayList<String?>()

    init {
        this.events = events
        runtime =
            builder
                .setServerDirectories(serverDirectories)
                .addBlazeModule(
                    object : BlazeModule() {
                        public override fun beforeCommand(env: CommandEnvironment) {
                            // This only does something interesting for tests that create their own
                            // BlazeCommandDispatcher. :-(
                            if (this@BlazeRuntimeWrapper.env !== env) {
                                this@BlazeRuntimeWrapper.env = env
                                this@BlazeRuntimeWrapper.lastRequest = null
                                this@BlazeRuntimeWrapper.lastResult = null
                                resetOptions()
                                env.getEventBus().register(this)
                            }
                        }
                    })
                .addBlazeModule(
                    object : BlazeModule() {
                        public override fun beforeCommand(env: CommandEnvironment) {
                            this@BlazeRuntimeWrapper.events.initExternal(env.getReporter())
                        }
                    })
                .build()
        runtime.initWorkspace(directories, binTools)
    }

    fun getRuntime(): BlazeRuntime {
        return runtime
    }

    /**
     * Registers the given `subscriber` with the [EventBus] before each command and during
     * the current command if one is in progress.
     */
    fun registerSubscriber(subscriber: Any?) {
        eventBusSubscribers.add(subscriber!!)
        if (env != null) {
            env.getEventBus().register(subscriber)
        }
    }

    @Throws(Exception::class)
    fun newCommand(): CommandEnvironment? {
        return newCommand(BuildCommand::class.java)
    }

    @Throws(Exception::class)
    fun newCommand(ignoreUserOptions: Boolean): CommandEnvironment? {
        return newCommandWithExtensions(
            BuildCommand::class.java,  /* extensions= */ImmutableList.of<Message?>(), ignoreUserOptions
        )
    }

    /** Creates a new command environment; executeBuild does this automatically if you do not.  */
    @Throws(Exception::class)
    fun newCommand(command: Class<out BlazeCommand?>): CommandEnvironment? {
        return newCommandWithExtensions(command,  /* extensions= */ImmutableList.of<Message?>())
    }

    /**
     * Creates a new command environment with additional proto extensions as if they were passed to
     * the Blaze server.
     * 
     * @param command the command instance for which to create a new environment.
     * @param extensions additional proto extensions to pass to the command.
     * @return the new command environment.
     */
    @CanIgnoreReturnValue
    @Throws(Exception::class)
    fun newCustomCommandWithExtensions(
        command: BlazeCommand, extensions: MutableList<Message?>, ignoreUserOptions: Boolean
    ): CommandEnvironment? {
        val commandAnnotation: Command =
            checkNotNull(
                command.getClass().getAnnotation(Command::class.java),
                "BlazeCommand %s missing command annotation",
                command.getClass()
            )
        this.command = command

        additionalOptionsClasses.addAll(
            BlazeCommandUtils.getOptions(
                command.getClass(), runtime.getOptionsSuppliers(), runtime.getRuleClassProvider()
            )
        )
        initializeOptionsParser(commandAnnotation, ignoreUserOptions)

        Preconditions.checkNotNull<OptionsParser?>(
            optionsParser,
            "The options parser must be initialized before creating a new command environment"
        )
        optionsParser.setStarlarkOptions(starlarkOptions, starlarkOptionAllowingMultiple)

        env =
            runtime
                .getWorkspace()
                .initCommand(
                    commandAnnotation,
                    optionsParser,
                    InvocationPolicy.getDefaultInstance(),
                    workspaceSetupWarnings,  /* waitTimeInMs= */
                    0L,  /* commandStartTime= */
                    runtime.getClock().currentTimeMillis(),  /* idleTaskResultsFromPreviousIdlePeriod= */
                    ImmutableList.of<E?>(),
                    this.crashMessages::add,
                    extensions.stream().map<Any?>(Any::pack).collect(ImmutableList.toImmutableList<E?>()),
                    NO_OP_COMMAND_EXTENSION_REPORTER,  /* attemptNumber= */
                    1,  /* buildRequestIdOverride= */
                    null,
                    ConfigFlagDefinitions.NONE
                )
        return env
    }

    /**
     * Creates a new command environment with additional proto extensions as if they were passed to
     * the Blaze server. This method creates a new instance of the provided command class via its
     * default constructor. For command classes with constructor parameters, use [ ][.newCustomCommandWithExtensions] and pass in a pre-existing [BlazeCommand] instance.
     * 
     * @param command the command class for which to create a new environment. This class must have a
     * default constructor or this method will throw an exception.
     * @param extensions additional proto extensions to pass to the command.
     */
    @Throws(Exception::class)
    fun newCommandWithExtensions(
        command: Class<out BlazeCommand?>, extensions: MutableList<Message?>
    ): CommandEnvironment? {
        return newCommandWithExtensions(command, extensions,  /* ignoreUserOptions= */true)
    }

    @Throws(Exception::class)
    private fun newCommandWithExtensions(
        command: Class<out BlazeCommand?>, extensions: MutableList<Message?>, ignoreUserOptions: Boolean
    ): CommandEnvironment? {
        return newCustomCommandWithExtensions(
            command.getDeclaredConstructor().newInstance(), extensions, ignoreUserOptions
        )
    }

    val commandEnvironment: CommandEnvironment?
        /**
         * Returns the command environment. You must call [.newCommand] before calling this
         * method.
         */
        get() = env

    val skyframeExecutor: SkyframeExecutor
        get() = runtime.getWorkspace().getSkyframeExecutor()

    fun resetOptions() {
        optionsToParse.clear()
        starlarkOptions.clear()
    }

    fun addOptions(vararg args: String?) {
        addOptions(ImmutableList.copyOf<String?>(args))
    }

    fun addOptions(args: MutableList<String?>?) {
        optionsToParse.addAll(args!!)
    }

    fun setOptionsParserResidue(residue: MutableList<String?>?, postDoubleDashResidue: MutableList<String?>?) {
        optionsParser.setResidue(residue, postDoubleDashResidue)
    }

    fun setConfiguration(configuration: BuildConfigurationValue?) {
        this.configuration = configuration
    }

    fun addStarlarkOption(label: String?, optionValue: Any?) {
        starlarkOptions.put(Label.parseCanonicalUnchecked(label).getCanonicalForm(), optionValue)
    }

    fun addStarlarkOptions(starlarkOptions: MutableMap<String?, Any?>) {
        starlarkOptions.forEach { (label: String?, optionValue: Any?) -> this.addStarlarkOption(label, optionValue) }
    }

    val options: ImmutableList<String?>
        get() = ImmutableList.copyOf<String?>(optionsToParse)

    fun <O : OptionsBase?> getOptions(optionsClass: Class<O?>?): O? {
        return optionsParser.getOptions<O?>(optionsClass)
    }

    fun getStarlarkOptions(): ImmutableMap<String?, Any?> {
        return ImmutableMap.copyOf<String?, Any?>(starlarkOptions)
    }

    fun getStarlarkOptionAllowingMultiple(): ImmutableSet<String?> {
        return ImmutableSet.copyOf<String?>(starlarkOptionAllowingMultiple)
    }

    fun addOptionsClass(optionsClass: Class<out OptionsBase?>?) {
        additionalOptionsClasses.add(optionsClass)
    }

    open fun finalizeBuildResult(@Suppress("unused") request: BuildResult?) {}

    /**
     * Initializes a new options parser, parsing all the options set by [ ][.addOptions].
     */
    @Throws(OptionsParsingException::class)
    private fun initializeOptionsParser(commandAnnotation: Command, ignoreUserOptions: Boolean) {
        // Create the options parser and parse all the options collected so far
        optionsParser = createOptionsParser(commandAnnotation, ignoreUserOptions)
        optionsParser.parse(optionsToParse)

        // The exec transition has to know Starlark flags' scope types to figure out which flags should
        // pass to the exec configuration vs. not. In production builds OptionsParser and
        // StarlarkOptionsParser handle this. But BlazeRuntimeWrapper injects Starlark flags directly
        // without going through normal parsing. So we have this extra step to provide default scope
        // values. Ideally we could more closely match production parsing and avoid extra logic.
        optionsParser.setScopesAttributes(
            getStarlarkOptions().entries.stream()
                .collect(
                    ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                        Function { Map.Entry.key },
                        Function { entry: Any? -> ScopeType.DEFAULT.toString() })
                )
        )

        // Allow the command to edit the options.
        command.editOptions(optionsParser)

        // Enforce the test invocation policy once the options have been added
        val optionsPolicyEnforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcer(
                runtime.getModuleInvocationPolicy(), Level.FINE,  /* conversionContext= */null
            )
        try {
            optionsPolicyEnforcer.enforce(
                optionsParser,
                commandAnnotation.name(),  /* invocationPolicyFlagListBuilder= */
                ImmutableList.builder<E?>()
            )
        } catch (e: OptionsParsingException) {
            throw IllegalStateException(e)
        }
    }

    private fun createOptionsParser(commandAnnotation: Command, ignoreUserOptions: Boolean): OptionsParser {
        val options: MutableSet<Class<out OptionsBase?>?> =
            HashSet<E?>(
                ImmutableList.of<Class<out Any?>?>(
                    BuildRequestOptions::class.java,
                    BuildEventProtocolOptions::class.java,
                    ExecutionOptions::class.java,
                    LocalExecutionOptions::class.java,
                    CommonCommandOptions::class.java,
                    ClientOptions::class.java,
                    LoadingOptions::class.java,
                    AnalysisOptions::class.java,
                    KeepGoingOption::class.java,
                    LoadingPhaseThreadsOption::class.java,
                    PackageOptions::class.java,
                    BuildLanguageOptions::class.java,
                    UiOptions::class.java,
                    SandboxOptions::class.java
                )
            )
        options.addAll(additionalOptionsClasses)

        for (supplier in runtime.getOptionsSuppliers()) {
            Iterables.addAll<Class<out OptionsBase?>?>(options, supplier.getCommonCommandOptions())
            Iterables.addAll<Class<out OptionsBase?>?>(options, supplier.getCommandOptions(commandAnnotation.name()))
        }
        options.addAll(runtime.getRuleClassProvider().getFragmentRegistry().getOptionsClasses())
        // Because the tests that use this class don't set sources for their options, the normal logic
        // for determining user options assumes that all options are user options. This causes tests
        // that enable PROJECT.scl files to fail, so ignore user options instead.
        val optionserParserBuilder: OptionsParser.Builder = OptionsParser.builder().optionsClasses(options)
        if (ignoreUserOptions) {
            optionserParserBuilder.ignoreUserOptions()
        }
        optionserParserBuilder.skipStarlarkOptionPrefixes()
        return optionserParserBuilder.build()
    }

    @Throws(Exception::class)
    fun executeCustomCommand() {
        Preconditions.checkNotNull<Any?>(command, "No command created, try calling newCommand()")
        checkState(
            env.getCommand().buildPhase() === NONE || env.getCommandName().equals("run")
                    || env.getCommandName().equals("javahotswap"),
            "%s is a build command, did you mean to call executeBuild()?",
            env.getCommandName()
        )

        var result: BlazeCommandResult = BlazeCommandResult.success()

        try {
            beforeCommand()

            lastRequest = null
            lastResult = null

            try {
                var crash: Crash? = null
                try {
                    if (env.getCommandName().equals("run") || env.getCommandName().equals("javahotswap")) {
                        Profiler.instance().profile("syncPackageLoading").use { c ->
                            env.syncPackageLoading(optionsParser)
                        }
                    }
                    result = command.exec(env, optionsParser)
                } catch (e: RuntimeException) {
                    crash = Crash.from(e)
                    result = BlazeCommandResult.detailedExitCode(crash.detailedExitCode)
                    throw e
                } catch (e: Error) {
                    crash = Crash.from(e)
                    result = BlazeCommandResult.detailedExitCode(crash.detailedExitCode)
                    throw e
                } finally {
                    commandComplete(crash)
                }
                checkState(
                    result.getDetailedExitCode().equals(DetailedExitCode.success()),
                    "%s command resulted in %s",
                    env.getCommandName(),
                    result
                )
            } finally {
                afterCommand(result)
            }
        } finally {
            Profiler.instance().stop()
        }
    }

    /**
     * Runs a aquery command with the given target.
     * 
     * @param target the target to run the aquery against.
     */
    @Throws(Exception::class)
    fun runAqueryExprCommand(target: String) {
        newCommand(AqueryCommand::class.java)
        // Resetting the deserialized keys is necessary to avoid aquery using the pruned graph and
        // missing entries in its output. Since BlazeRuntimeWrapper is written using the method
        // buildTargets from BuildTool directly and skips going through the entry class
        QueryCommandUtils.resetDeserializedKeysFromRemoteAnalysisCache(this.commandEnvironment)

        val aqueryProcessor: AqueryProcessor =
            AqueryProcessor(getQueryExpression(target), TargetPattern.defaultParser())
        executeBuild(Arrays.asList<String?>(target), aqueryProcessor)
    }

    /**
     * Runs a cquery command with the given expression and target.
     * 
     * @param cqueryExpr the cquery expression to evaluate.
     * @param target the target to run the cquery against.
     */
    @Throws(Exception::class)
    fun runCqueryExprCommand(cqueryExpr: String?, target: String) {
        newCommand(CqueryCommand::class.java)
        // Resetting the deserialized keys is necessary to avoid cquery using the pruned graph and
        // missing targets in its output. Since BlazeRuntimeWrapper is written using the method
        // buildTargets from BuildTool directly and skips going through the entry class
        // CqueryCommand, we have to reimplement some of the logic here for cquery expressions like
        // "deps(//foo)" to work in the integration tests. The alternative is a bigger refactoring
        // rewriting BlazeRuntimeWrapper to use the *Command.java classes with the possibility of
        // increasing overall complexity.
        QueryCommandUtils.resetDeserializedKeysFromRemoteAnalysisCache(this.commandEnvironment)

        val parser: TargetPattern.Parser =
            Parser(
                PathFragment.EMPTY_FRAGMENT,
                RepositoryName.MAIN,
                RepositoryMapping.create(
                    ImmutableMap.of<K?, V?>("repo", RepositoryName.createUnvalidated("canonical_repo")),
                    RepositoryName.MAIN
                )
            )
        val cqueryProcessor: CqueryProcessor = CqueryProcessor(getQueryExpression(cqueryExpr), parser)

        executeBuild(Arrays.asList<String?>(target), cqueryProcessor)
    }

    @Throws(Exception::class)
    private fun getQueryExpression(cqueryExpr: String?): QueryExpression {
        val functions: HashMap<String?, QueryFunction?> = HashMap<String?, QueryFunction?>()
        for (queryFunction in ConfiguredTargetQueryEnvironment.FUNCTIONS) {
            functions.put(queryFunction.name, queryFunction)
        }
        for (queryFunction in getRuntime().getQueryFunctions()) {
            functions.put(queryFunction.name, queryFunction)
        }
        return QueryParser.parse(cqueryExpr, functions)
    }

    @kotlin.jvm.JvmOverloads
    @Throws(Exception::class)
    fun executeBuild(targets: MutableList<String?>?, analysisPostProcessor: AnalysisPostProcessor? = null) {
        if (command == null) {
            newCommand(BuildCommand::class.java) // If you didn't create a command we do it for you.
        }
        checkState(
            env.getCommand().buildPhase().loads(),
            "%s is not a build command, did you mean to call executeNonBuildCommand()?",
            env.getCommandName()
        )

        try {
            beforeCommand()

            try {
                lastRequest = createRequest(env.getCommandName(), targets)
                lastResult = BuildResult(lastRequest.getStartTime())

                var crash: Crash? = null
                var detailedExitCode: DetailedExitCode? = DetailedExitCode.of(createGenericDetailedFailure())
                val buildTool: BuildTool?
                if (analysisPostProcessor == null) {
                    buildTool = BuildTool(env)
                } else {
                    buildTool = BuildTool(env, analysisPostProcessor)
                }
                try {
                    Profiler.instance().profile("syncPackageLoading").use { c ->
                        env.syncPackageLoading(lastRequest)
                    }
                    buildTool.buildTargets(
                        lastRequest,
                        lastResult,
                        null,
                        optionsParser,  /* targetsForProjectResolution= */
                        null
                    )
                    detailedExitCode = DetailedExitCode.success()
                } catch (e: BuildFailedException) {
                    // This corresponds to the logic in BuildTool#processRequest that calls
                    // BuildTool#buildTargets. There are many other cases omitted. This only seems relevant
                    // for tests verifying the contents of the BuildFinished BEP event.
                    detailedExitCode = e.getDetailedExitCode()
                    throw e
                } catch (e: RuntimeException) {
                    crash = Crash.from(e)
                    detailedExitCode = crash.detailedExitCode
                    throw e
                } catch (e: Error) {
                    crash = Crash.from(e)
                    detailedExitCode = crash.detailedExitCode
                    throw e
                } finally {
                    env.getTimestampGranularityMonitor().waitForTimestampGranularity(lastRequest.getOutErr())
                    configuration = lastResult.getBuildConfiguration()
                    finalizeBuildResult(lastResult)
                    buildTool.stopRequest(
                        lastResult, if (crash != null) crash.throwable else null, detailedExitCode
                    )
                    commandComplete(crash)
                }
            } finally {
                afterCommand(BlazeCommandResult.detailedExitCode(lastResult.getDetailedExitCode()))
            }
        } finally {
            Profiler.instance().stop()
        }
    }

    @Throws(Exception::class)
    private fun beforeCommand() {
        events.clear()
        val reporter: Reporter = env.getReporter()
        Profiler.instance()
            .start( /* profiledTasks= */
                ImmutableSet.of<ProfilerTask?>(),  /* stream= */
                null,  /* format= */
                null,  /* outputBase= */
                null,  /* buildID= */
                null,  /* recordAllDurations= */
                false,
                JavaClock(),  /* execStartTimeNanos= */
                42,  /* slimProfile= */
                false,  /* includePrimaryOutput= */
                false,  /* includeTargetLabel= */
                false,  /* includeConfiguration= */
                false,  /* collectTaskHistograms= */
                true
            )

        val storedEventHandler: StoredEventHandler = StoredEventHandler()
        reporter.addHandler(storedEventHandler)

        env.decideKeepIncrementalState()

        val eventBus: EventBus = env.getEventBus()
        for (subscriber in eventBusSubscribers) {
            eventBus.register(subscriber)
        }

        // This cannot go into newCommand, because we hook up the EventCollectionApparatus as a module,
        // and after that ran, further changes to the apparatus aren't reflected on the reporter.
        for (module in runtime.getBlazeModules()) {
            module.beforeCommand(env)
        }
        reporter.removeHandler(storedEventHandler)

        // Replay events from decideKeepIncrementalState and beforeCommand, just as
        // BlazeCommandDispatcher does.
        storedEventHandler.replayOn(reporter)

        env.beforeCommand(InvocationPolicy.getDefaultInstance())

        for (module in runtime.getBlazeModules()) {
            env.getSkyframeExecutor().injectExtraPrecomputedValues(module.getPrecomputedValues())
        }
    }

    @Throws(Exception::class)
    private fun commandComplete(crash: Crash?) {
        val reporter: Reporter? = env.getReporter()
        if (crash != null) {
            runtime.getBugReporter().handleCrash(crash, CrashContext.keepAlive().reportingTo(reporter))
        }
    }

    private fun afterCommand(result: BlazeCommandResult?) {
        command = null
        lastCommandResult = runtime.afterCommand( /* forceKeepStateForTesting= */true, env, result)
    }

    private fun createRequest(commandName: String, targets: MutableList<String?>?): BuildRequest {
        val builder: BuildRequest.Builder =
            BuildRequest.builder()
                .setCommandName(commandName)
                .setId(env.getCommandId())
                .setOptions(optionsParser)
                .setStartupOptions(null)
                .setOutErr(env.getReporter().getOutErr())
                .setTargets(targets)
                .setStartTimeMillis(runtime.getClock().currentTimeMillis())
        if (commandName == "test" || commandName == "coverage") {
            builder.setRunTests(true)
        }
        return builder.build()
    }

    // Null if no build has been run.
    fun getLastRequest(): BuildRequest? {
        return lastRequest
    }

    // Null if no build has been run.
    fun getLastResult(): BuildResult? {
        return lastResult
    }

    // Null if no build has been run.
    fun getLastCommandResult(): BlazeCommandResult? {
        return lastCommandResult
    }

    // Null if no build has been run.
    fun getConfiguration(): BuildConfigurationValue? {
        return configuration
    }

    companion object {
        private fun createGenericDetailedFailure(): FailureDetail {
            return FailureDetail.newBuilder()
                .setSpawn(Spawn.newBuilder().setCode(Code.NON_ZERO_EXIT))
                .build()
        }
    }
}
