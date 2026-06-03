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
import com.google.common.base.Throwables
import com.google.common.collect.*
import com.google.common.eventbus.Subscribe
import com.google.common.eventbus.SubscriberExceptionContext
import com.google.common.eventbus.SubscriberExceptionHandler
import com.google.devtools.build.lib.actions.Action
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.events.EventKind
import com.google.devtools.build.lib.testutil.TestUtils
import com.google.devtools.build.lib.util.OS
import com.google.devtools.build.lib.vfs.util.FileSystems
import com.google.errorprone.annotations.CanIgnoreReturnValue
import com.google.errorprone.annotations.ForOverride
import com.google.errorprone.annotations.FormatMethod
import com.google.errorprone.annotations.Keep
import org.junit.After
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.function.IntFunction
import java.util.function.Predicate
import java.util.logging.Level
import java.util.logging.Logger
import javax.annotation.concurrent.GuardedBy

/**
 * A base class for integration tests that use the [BuildTool]. These tests basically run a
 * little build and check what happens.
 * 
 * 
 * All integration tests are at least size medium.
 */
abstract class BuildIntegrationTestCase {
    /** Thrown when an integration test case fails.  */
    class IntegrationTestExecException : ExecException {
        constructor(message: String?) : super(message)

        constructor(message: String?, cause: Throwable?) : super(message, cause)

        protected override fun getFailureDetail(message: String?): FailureDetail {
            return FailureDetail.newBuilder()
                .setSpawn(Spawn.newBuilder().setCode(Code.NON_ZERO_EXIT))
                .setMessage(message)
                .build()
        }
    }

    @kotlin.jvm.JvmField
    protected var fileSystem: FileSystem? = null
    @kotlin.jvm.JvmField
    protected val events: EventCollectionApparatus = EventCollectionApparatus(
        Sets.union<EventKind?>(EventKind.ERRORS_WARNINGS_AND_INFO, additionalEventsToCollect())
    )
    @kotlin.jvm.JvmField
    protected var outErr: OutErr? = OutErr.SYSTEM_OUT_ERR
    @kotlin.jvm.JvmField
    protected var testRoot: Path? = null
    protected var serverDirectories: ServerDirectories? = null
    @kotlin.jvm.JvmField
    protected var directories: BlazeDirectories? = null
    protected var mockToolsConfig: MockToolsConfig? = null
    protected var binTools: BinTools? = null
    private var bugReporter: BugReporter? = BugReporter.defaultInstance()

    var runtimeWrapper: BlazeRuntimeWrapper? = null
        protected set
    protected var outputBase: Path? = null
    protected var outputBaseName: String = "outputBase"

    private var workspace: Path? = null
    protected var subscriberException: RecordingExceptionHandler = RecordingExceptionHandler()

    private var oldExceptionHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * Returns additional types of events for [.events] to collect.
     * 
     * 
     * [EventKind.ERRORS_WARNINGS_AND_INFO] are always collected by default. Collected events
     * can be asserted on using [.assertContainsEvent] and [ ][.assertDoesNotContainEvent].
     */
    @ForOverride
    protected open fun additionalEventsToCollect(): MutableSet<EventKind?>? {
        return ImmutableSet.of<EventKind?>()
    }

    @Before
    @Throws(Exception::class)
    fun createFilesAndMocks() {
        runPriorToBeforeMethods()
        events.setFailFast(false)

        // TODO(mschaller): This will ignore any attempt by Blaze modules to provide a filesystem;
        // consider something better.
        val nativeFileSystem: FileSystem = createFileSystem()
        this.fileSystem = createFileSystemForBuildArtifacts(nativeFileSystem)
        this.testRoot = createTestRoot(fileSystem)

        outputBase = fileSystem.getPath(testRoot.getRelative(outputBaseName).asFragment())
        outputBase.createDirectoryAndParents()
        workspace =
            nativeFileSystem.getPath(testRoot.getRelative(this.desiredWorkspaceRelative).asFragment())
        beforeCreatingWorkspace(workspace)
        workspace.createDirectoryAndParents()
        serverDirectories =
            ServerDirectories( /* installBase= */
                outputBase,  /* outputBase= */
                outputBase,  /* outputUserRoot= */
                outputBase,  /* execRootBase= */
                outputBase.getRelative(ServerDirectories.EXECROOT),  /* virtualSourceRoot= */
                this.virtualSourceRoot,  // Arbitrary install base hash.
                /* installMD5= */
                "83bc4458738962b9b77480bac76164a9"
            )
        directories = BlazeDirectories(serverDirectories, workspace, TestConstants.PRODUCT_NAME)
        binTools = IntegrationMock.Companion.get().getIntegrationBinTools(fileSystem, directories)
        mockToolsConfig = MockToolsConfig(workspace, realFileSystem())
        setupMockTools()
        createRuntimeWrapper()

        AnalysisMock.get().setupMockToolsRepository(mockToolsConfig)
    }

    @Before
    fun setUncaughtExceptionHandler() {
        oldExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(createUncaughtExceptionHandler())
    }

    @After
    fun restoreUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(oldExceptionHandler)
    }

    /**
     * Lazily injects the given listener at the start of the next build.
     * 
     * 
     * Injecting the listener immediately would reach the *current* evaluator, but the next
     * build may create a new evaluator, which happens when [ ] is not tracking incremental
     * state.
     */
    fun injectListenerAtStartOfNextBuild(listener: NotifyingHelper.Listener?) {
        runtimeWrapper!!.registerSubscriber(
            object : Any() {
                private var injected = false

                @Subscribe
                @Keep
                fun buildStarting(@Suppress("unused") event: BuildStartingEvent?) {
                    if (!injected) {
                        this.skyframeExecutor
                            .getEvaluator()
                            .injectGraphTransformerForTesting(
                                NotifyingHelper.makeNotifyingTransformer(listener)
                            )
                        injected = true
                    }
                }
            })
    }

    /**
     * Creates an uncaught exception handler to be used in [ ][Thread.setDefaultUncaughtExceptionHandler].
     * 
     * 
     * Returns `null` if ne exception handler should be used.
     */
    protected open fun createUncaughtExceptionHandler(): Thread.UncaughtExceptionHandler? {
        return Thread.UncaughtExceptionHandler? { ignored: Thread?, exception: Throwable? ->
            BugReport.handleCrash(
                Crash.from(
                    exception
                ), CrashContext.keepAlive()
            )
        }
    }

    @get:ForOverride
    protected val virtualSourceRoot: Root?
        get() = null

    @Throws(Exception::class)
    protected fun createRuntimeWrapper() {
        if (runtimeWrapper != null) {
            cleanupInterningPools()
        }
        val builder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            this.runtimeBuilder.setEventBusExceptionHandler(subscriberException)
        prepareRuntimeBuilder(builder)
        runtimeWrapper =
            object : BlazeRuntimeWrapper(events, serverDirectories, directories, binTools, builder) {
                protected override fun finalizeBuildResult(result: BuildResult?) {
                    finishBuildResult(result)
                }
            }
        setupOptions()
    }

    @Throws(AbruptExitException::class)
    protected fun prepareRuntimeBuilder(builder: BlazeRuntime.Builder) {
        val startupOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            builder.getStartupOptionsProvider()
        val blazeServices: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            builder.getBlazeServices()
        for (blazeService in blazeServices) {
            try {
                blazeService.globalInit(startupOptions, blazeServices)
            } catch (e: SerializedAbruptExitException) {
                try {
                    val failureDetail: FailureDetail? =
                        FailureDetail.parseFrom(
                            e.serializedFailureDetail, ExtensionRegistryLite.getEmptyRegistry()
                        )
                    throw AbruptExitException(DetailedExitCode.of(failureDetail), e)
                } catch (ipbe: InvalidProtocolBufferException) {
                    throw AbruptExitException(
                        DetailedExitCode.of(
                            FailureDetail.newBuilder()
                                .setMessage(
                                    "Failed to parse FailureDetail from SerializedAbruptExitException: "
                                            + ipbe.message
                                )
                                .setCommand(
                                    com.google.devtools.build.lib.server.FailureDetails.Command.newBuilder()
                                        .setCode(
                                            com.google.devtools.build.lib.server.FailureDetails.Command.Code
                                                .COMMAND_FAILURE_UNKNOWN
                                        )
                                )
                                .build()
                        ),
                        ipbe
                    )
                }
            }
        }
        for (blazeModule in builder.getBlazeModules()) {
            blazeModule.globalInit(startupOptions, blazeServices)
        }
    }

    /**
     * Configures the server to record bug reports using the returned [RecordingBugReporter].
     * 
     * 
     * The server is reinitialized so that this change is picked up.
     */
    @Throws(Exception::class)
    fun recordBugReportsAndReinitialize(): RecordingBugReporter {
        val recordingBugReporter = RecordingBugReporter()
        setCustomBugReporterAndReinitialize(recordingBugReporter)
        return recordingBugReporter
    }

    /**
     * Configures the server to record bug reports using the given [BugReporter].
     * 
     * 
     * The server is reinitialized so that this change is picked up.
     */
    @Throws(Exception::class)
    fun setCustomBugReporterAndReinitialize(bugReporter: BugReporter?) {
        this.bugReporter = Preconditions.checkNotNull<BugReporter?>(bugReporter)
        reinitializeAndPreserveOptions()
    }

    @Throws(Exception::class)
    protected fun reinitializeAndPreserveOptions() {
        val options = runtimeWrapper!!.getOptions()
        val starlarkOptions = runtimeWrapper!!.getStarlarkOptions()
        createFilesAndMocks()
        runtimeWrapper!!.resetOptions()
        runtimeWrapper!!.addOptions(options)
        runtimeWrapper!!.addStarlarkOptions(starlarkOptions)
    }

    @Throws(Exception::class)
    protected fun runPriorToBeforeMethods() {
    }

    @After
    fun cleanupInterningPools() {
        this.skyframeExecutor.getEvaluator().cleanupInterningPools()
    }

    @After
    @Throws(Exception::class)
    fun cleanUp() {
        try {
            doCleanup()
        } finally {
            this.runtime.getBlazeModules().forEach(BlazeModule::blazeShutdown)
        }
    }

    @Throws(Exception::class)
    private fun doCleanup() {
        if (subscriberException.exception != null) {
            Throwables.throwIfUnchecked(subscriberException.exception)
            throw RuntimeException(subscriberException.exception)
        }
        LoggingUtil.installRemoteLoggerForTesting(null)

        if (OS.getCurrent() == OS.WINDOWS) {
            bestEffortDeleteTreesBelow(
                testRoot,
                Predicate { filename: String? ->
                    // Bazel runtime still holds the file handle of windows_jni.dll or libzstd-jni-xxxx.dll
                    // making it impossible to delete on Windows.
                    if (filename == "windows_jni.dll") {
                        return@bestEffortDeleteTreesBelow true
                    }
                    if (filename.startsWith("libzstd-jni") && filename.endsWith(".dll")) {
                        return@bestEffortDeleteTreesBelow true
                    }

                    // mockito's inline mock maker manipulates byte code of mocked methods and output new
                    // byte code in a temporary jarfile with pattern mockitobootXXXXXXX.jar. It then loads
                    // these jar files into JVM to make mock effective which means Bazel runtime still holds
                    // handles of these files making it impossible to delete on Windows.
                    //
                    // See https://github.com/mockito/mockito/issues/1379#issuecomment-466372914 and
                    // https://github.com/mockito/mockito/blob/91f18ea1648e389bea06289d818def7978e82288/src/main/java/org/mockito/internal/creation/bytebuddy/InlineDelegateByteBuddyMockMaker.java#L123C10-L123C10.
                    if (filename.startsWith("mockitoboot") && filename.endsWith(".jar")) {
                        return@bestEffortDeleteTreesBelow true
                    }
                    false
                })
        } else {
            testRoot.deleteTreesBelow() // (comment out during debugging)
        }

        // Make sure that a test which crashes with on a bug report does not taint following ones with
        // an unprocessed exception stored statically in BugReport.
        BugReport.maybePropagateLastCrashIfInTest()
        Thread.interrupted() // If there was a crash in test case, main thread was interrupted.
    }

    /**
     * A helper class that can be used to record exceptions that occur on the event bus, by passing an
     * instance of it to BlazeRuntime#setEventBusExceptionHandler.
     */
    class RecordingExceptionHandler : SubscriberExceptionHandler {
        var exception: Throwable? = null
            private set

        override fun handleException(exception: Throwable, context: SubscriberExceptionContext) {
            System.err.println("subscriber exception: ")
            exception.printStackTrace()
            if (this.exception == null) {
                this.exception = exception
            }
        }
    }

    protected val desiredWorkspaceRelative: PathFragment
        /**
         * Returns the relative path (from `testRoot`) to the desired workspace. This method may be
         * called in [.createFilesAndMocks], so overrides this method should not use any variables
         * that may not have been initialized yet.
         */
        get() = PathFragment.create(TestConstants.WORKSPACE_NAME)

    /**
     * Called in #setUp before creating the workspace directory. Subclasses should override this if
     * they want to a non-standard filesystem setup, e.g. introduce symlinked directories.
     */
    @Throws(Exception::class)
    protected fun beforeCreatingWorkspace(@Suppress("unused") workspace: Path?) {
    }

    protected fun finishBuildResult(@Suppress("unused") result: BuildResult?) {}

    protected open fun realFileSystem(): Boolean {
        return true
    }

    @Throws(Exception::class)
    protected open fun createFileSystem(): FileSystem {
        return FileSystems.getNativeFileSystem(this.digestHashFunction)
    }

    protected fun createFileSystemForBuildArtifacts(fileSystem: FileSystem): FileSystem {
        return fileSystem
    }

    protected val digestHashFunction: DigestHashFunction
        get() = DigestHashFunction.SHA256

    protected open fun createTestRoot(fileSystem: FileSystem): Path {
        return fileSystem.getPath(TestUtils.tmpDir())
    }

    // This is only here to support HaskellNonIntegrationTest. You should not call or override this
    // method.
    @Throws(IOException::class)
    protected open fun setupMockTools() {
        // (Almost) every integration test calls BuildView.doLoadingPhase, which loads the default
        // crosstool, etc.  So we create these package here.
        AnalysisMock.get().setupMockClient(mockToolsConfig)
    }

    protected fun getFileSystem(): FileSystem {
        return fileSystem
    }

    protected open val buildInfoModule: BlazeModule?
        get() = object : BlazeModule() {
            public override fun workspaceInit(
                runtime: BlazeRuntime?, directories: BlazeDirectories?, builder: WorkspaceBuilder
            ) {
                builder.setWorkspaceStatusActionFactory(
                    DummyWorkspaceStatusActionFactory()
                )
            }

            public override fun registerActionContexts(
                registryBuilder: ModuleActionContextRegistry.Builder,
                env: CommandEnvironment?,
                buildRequest: BuildRequest?
            ) {
                registryBuilder.register(
                    WorkspaceStatusAction.Context::class.java, DummyWorkspaceStatusActionContext()
                )
            }
        }

    protected open val spawnModules: ImmutableList<BlazeModule>?
        /**
         * Returns modules necessary for configuring spawn strategies.
         * 
         * 
         * These modules are registered *before* [.getStrategyModule].
         */
        get() = if (AnalysisMock.get().isThisBazel())
            ImmutableList.of<E?>(StandaloneModule(), SandboxModule())
        else
            ImmutableList.of<E?>(StandaloneModule())

    protected open val rulesModule: BlazeModule?
        /** Gets a module containing rules (by default, using the TestRuleClassProvider)  */
        get() = TestRuleModule.getModule()

    protected val strategyModule: BlazeModule?
        /** Gets a module to set up the strategies.  */
        get() = TestStrategyModule.getModule()

    protected open val connectivityModule: BlazeModule?
        /**
         * Gets a module that returns a connectivity status.
         * 
         * @return a Blaze module that implements [ConnectivityStatusProvider]
         */
        get() = NoOpConnectivityModule()

    @get:Throws(Exception::class)
    protected open val runtimeBuilder: BlazeRuntime.Builder
        get() {
            val startupOptionsProvider: OptionsParsingResult = this.startupOptionsProvider
            val connectivityModule: BlazeModule? = this.connectivityModule
            Preconditions.checkState(
                connectivityModule is ConnectivityStatusProvider,
                "Module returned by getConnectivityModule() does not implement ConnectivityStatusProvider"
            )
            val builder: BlazeRuntime.Builder =
                Builder()
                    .setFileSystem(fileSystem)
                    .setProductName(TestConstants.PRODUCT_NAME)
                    .setBugReporter(bugReporter)
                    .setStartupOptionsProvider(startupOptionsProvider)
                    .addBlazeModule(BuildIntegrationTestCommandsModule())
                    .addBlazeModule(OutputFilteringModule())
                    .addBlazeModule(connectivityModule)
                    .addBlazeModule(SkymeldModule())
                    .addBlazeModule(CredentialModule())
            for (service in TestServices.BLAZE_SERVICES) {
                builder.addBlazeService(service)
            }
            this.spawnModules.forEach(builder::addBlazeModule)
            builder
                .addBlazeModule(this.buildInfoModule)
                .addBlazeModule(this.rulesModule)
                .addBlazeModule(this.strategyModule)

            if (AnalysisMock.get().isThisBazel()) {
                // Add in modules implicitly added in internal integration test case.
                builder.addBlazeModule(NoSpawnCacheModule()).addBlazeModule(WorkerModule())
            }

            // Get BlazeModule for external repository, which is different internally.
            builder.addBlazeModule(AnalysisMock.get().getBazelRepositoryModule(directories))

            // Modules that are involved in the collection of heap-related metrics of a
            // build. They need to be last in the modules order, so when the GCs happen
            // at the end of the build, we mitigate the risk that objects are still held
            // onto by the other modules.
            // TODO(b/253394502): remove this when we have a better solution.
            builder.addBlazeModule(PostGCMemoryUseRecorderModule())
            builder.addBlazeModule(GcAfterBuildModule())
            builder.addBlazeModule(MetricsModule())

            return builder
        }

    @get:Throws(Exception::class)
    private val startupOptionsProvider: OptionsParsingResult
        get() {
            val startupOptionsParser: OptionsParser =
                OptionsParser.builder().optionsClasses(this.startupOptionClasses).build()
            startupOptionsParser.parse(this.startupOptions)
            return startupOptionsParser
        }

    protected open val startupOptions: MutableList<String?>?
        get() = ImmutableList.of<String?>()

    protected open val startupOptionClasses: ImmutableList<Class<out OptionsBase>?>?
        get() = ImmutableList.of<E?>(BlazeServerStartupOptions::class.java)

    @Throws(Exception::class)
    protected open fun setupOptions() {
        runtimeWrapper!!.resetOptions()

        runtimeWrapper!!.addOptions( // Set visibility to public so that test cases don't have to bother
            // with visibility declarations
            "--default_visibility=public",  // Don't show progress messages unless we need to, to keep the noise down.

            "--noshow_progress",  // Don't use ijars, because we don't have the executable in these tests

            "--nouse_ijars",  // Disable system network usage collection so we don't need the SystemNetworkStatsService.

            "--noexperimental_collect_system_network_usage"
        )

        runtimeWrapper!!.addOptions("--experimental_extended_sanity_checks")
        runtimeWrapper.addOptions(TestConstants.PRODUCT_SPECIFIC_FLAGS)
        runtimeWrapper.addOptions(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS)

        if (AnalysisMock.get().isThisBazel()) {
            // We have to explicitly override @bazel_tools to the version in the workspace (which is where
            // we usually set up mocks), instead of the install base, where it is normally looked up from.
            // This needs to be done for all BuildIntegrationTestCase subclasses, because the setup here
            // requires that the install base be separate from the workspace (unlike, say,
            // BuildViewTestCase).
            runtimeWrapper!!.addOptions("--override_repository=bazel_tools=embedded_tools")
        }

        // Integration tests currently pretend that they run on a Linux host platform on all OSes. This
        // is a gross hack, but while it is in place, we need to manually set the shell path to a valid
        // one for the actual host OS. macOS shares Linux's shell path, but Windows needs a different
        // one.
        if (OS.getCurrent() == OS.WINDOWS) {
            runtimeWrapper!!.addOptions("--shell_executable=c:/msys64/usr/bin/bash.exe")
        }
    }

    protected fun resetOptions() {
        runtimeWrapper!!.resetOptions()
    }

    fun addOptions(vararg args: String?) {
        runtimeWrapper!!.addOptions(*args)
    }

    protected fun addOptions(args: MutableList<String?>?) {
        runtimeWrapper!!.addOptions(args)
    }

    protected fun addStarlarkOption(label: String?, value: Any?) {
        runtimeWrapper!!.addStarlarkOption(label, value)
    }

    protected fun getGeneratingAction(artifact: Artifact?): Action? {
        val action: ActionAnalysisMetadata? = this.actionGraph.getGeneratingAction(artifact)

        if (action != null) {
            checkState(
                action is Action, "%s is not a proper Action object", action.prettyPrint()
            )
            return action as Action
        } else {
            return null
        }
    }

    /**
     * Returns the path to the executable that label "target" identifies.
     * 
     * 
     * Assumes that the specified target is executable, i.e. defines getExecutable; use [ ][.getArtifacts] instead if this is not the case.
     * 
     * @param target the label of the target whose executable location is requested.
     */
    @Throws(
        LabelSyntaxException::class,
        NoSuchPackageException::class,
        NoSuchTargetException::class,
        InterruptedException::class,
        TransitionException::class,
        InvalidConfigurationException::class
    )
    protected fun getExecutableLocation(target: String?): Path {
        return getExecutable(getConfiguredTarget(target)).getPath()
    }

    /**
     * Given a label (which has typically, but not necessarily, just been built), returns the
     * collection of files that it produces.
     * 
     * @param target the label of the target whose artifacts are requested
     */
    @Throws(
        LabelSyntaxException::class,
        NoSuchPackageException::class,
        NoSuchTargetException::class,
        InterruptedException::class,
        TransitionException::class,
        InvalidConfigurationException::class
    )
    protected fun getArtifacts(target: String?): ImmutableList<Artifact?> {
        return getFilesToBuild(getConfiguredTarget(target)).toList()
    }

    /**
     * Given a label (which has typically, but not necessarily, just been built), returns the file it
     * produces ending with the given suffix.
     * 
     * 
     * It is an error if the given target produces multiple files with the given suffix.
     * 
     * @param target the label of the target whose artifact is requested
     * @param suffix suffix of the artifact requested
     */
    @Throws(
        LabelSyntaxException::class,
        NoSuchPackageException::class,
        NoSuchTargetException::class,
        InterruptedException::class,
        TransitionException::class,
        InvalidConfigurationException::class
    )
    protected fun getArtifact(target: String?, suffix: String?): Artifact? {
        return getArtifacts(target).stream()
            .filter { a: Artifact? -> a.getExecPathString().endsWith(suffix) }
            .collect(MoreCollectors.onlyElement<Artifact?>())
    }

    /**
     * Given a label (which has typically, but not necessarily, just been built), returns the
     * configured target for it using the request configuration.
     * 
     * @param target the label of the requested target.
     */
    @Throws(
        LabelSyntaxException::class,
        NoSuchPackageException::class,
        NoSuchTargetException::class,
        InterruptedException::class,
        TransitionException::class,
        InvalidConfigurationException::class
    )
    protected fun getConfiguredTarget(target: String?): ConfiguredTarget {
        this.packageManager.getTarget(events.reporter(), label(target))
        return this.skyframeExecutor
            .getConfiguredTargetForTesting(events.reporter(), label(target), this.targetConfiguration)
    }

    @Throws(TransitionException::class, InvalidConfigurationException::class, InterruptedException::class)
    protected fun getConfiguredTarget(
        eventHandler: ExtendedEventHandler?, label: Label?, config: BuildConfigurationValue?
    ): ConfiguredTarget {
        return this.skyframeExecutor.getConfiguredTargetForTesting(eventHandler, label, config)
    }

    protected val allConfiguredTargets: ImmutableList<ConfiguredTarget>
        /** Gets all the already computed configured targets.  */
        get() = SkyframeExecutorTestUtils.getAllExistingConfiguredTargets(this.skyframeExecutor)

    /** Gets an existing configured target.  */
    @Throws(InterruptedException::class, LabelSyntaxException::class)
    protected fun getExistingConfiguredTarget(target: String?): ConfiguredTarget? {
        val existingConfiguredTarget: ConfiguredTarget? =
            SkyframeExecutorTestUtils.getExistingConfiguredTarget(
                this.skyframeExecutor, label(target), this.targetConfiguration
            )
        Truth.assertWithMessage(target).that(existingConfiguredTarget).isNotNull()
        return existingConfiguredTarget
    }

    val targetConfigurationFromLastBuildResult: BuildConfigurationValue?
        // Null if no build has been run.
        get() = runtimeWrapper!!.getConfiguration()

    protected fun getConfigurationFromLastBuildResult(ct: ConfiguredTarget): BuildConfigurationValue {
        return this.skyframeExecutor
            .getConfiguration(NullEventHandler.INSTANCE, ct.getConfigurationKey())
    }

    protected val targetConfiguration: BuildConfigurationValue?
        /**
         * Returns the target configuration for the most recent build, as created in Blaze's master
         * configuration creation phase.
         * 
         * 
         * Tries to find the configuration used by all of the top-level targets in the last invocation.
         * If they used multiple different configurations, or if none of them had a configuration, then
         * falls back to the base top-level configuration.
         */
        get() {
            val baseConfiguration: BuildConfigurationValue? = this.targetConfigurationFromLastBuildResult
            val result: BuildResult? = this.result
            if (result == null) {
                return baseConfiguration
            }
            val topLevelTargetConfigurations: ImmutableSet<BuildConfigurationValue?> =
                result.getActualTargets().stream()
                    .map({ ct: ConfiguredTarget -> this.getConfigurationFromLastBuildResult(ct) })
                    .filter({ obj: Any? -> Objects.nonNull(obj) })
                    .collect(ImmutableSet.toImmutableSet<E?>())
            if (topLevelTargetConfigurations.size != 1) {
                return baseConfiguration
            }
            return Iterables.getOnlyElement<BuildConfigurationValue?>(
                topLevelTargetConfigurations
            )
        }

    protected val topLevelArtifactContext: TopLevelArtifactContext
        get() = this.request.getTopLevelArtifactContext()

    @CanIgnoreReturnValue
    @Throws(Exception::class)
    fun buildTarget(vararg targets: String?): BuildResult? {
        events.setOutErr(outErr)
        runtimeWrapper!!.executeBuild(Arrays.asList<String?>(*targets))
        return runtimeWrapper!!.getLastResult()
    }

    @CanIgnoreReturnValue
    @Throws(Exception::class)
    fun buildTarget(targets: MutableList<String?>): BuildResult? {
        return buildTarget(*targets.toArray<String?>(IntFunction { _Dummy_.__Array__() }))
    }

    /** Runs the `info` command.  */
    @Throws(Exception::class)
    protected fun info() {
        events.setOutErr(outErr)
        runtimeWrapper.newCommand(InfoCommand::class.java)
        runtimeWrapper!!.executeCustomCommand()
    }

    /** Runs the `clean` command.  */
    @Throws(Exception::class)
    fun clean() {
        events.setOutErr(outErr)
        runtimeWrapper.newCommand(CleanCommand::class.java)
        runtimeWrapper!!.executeCustomCommand()
    }

    /** Utility function: parse a string as a label.  */
    @Throws(LabelSyntaxException::class, InterruptedException::class)
    protected fun label(labelString: String?): Label {
        val mainRepoMapping: RepositoryMapping? =
            (this.skyframeExecutor
                .getEvaluator()
                .getExistingValue(RepositoryMappingValue.key(RepositoryName.MAIN)) as RepositoryMappingValue)
                .repositoryMapping()
        return Label.parseWithRepoContext(
            labelString, Label.RepoContext.of(RepositoryName.MAIN, mainRepoMapping)
        )
    }

    @Throws(Exception::class)
    protected fun run(executable: Artifact, vararg arguments: String?): String {
        val environment: MutableMap<String?, String?>? = null
        return run(executable.getPath(), null, environment, arguments)
    }

    /** This runs an executable using the executor instance configured for this test.  */
    @Throws(Exception::class)
    protected fun run(executable: Path, vararg arguments: String?): String {
        val environment: MutableMap<String?, String?>? = null
        return run(executable, null, environment, *arguments)
    }

    @Throws(ExecException::class, InterruptedException::class)
    protected fun run(executable: Path, workingDirectory: Path?, vararg arguments: String?): String {
        return run(executable, workingDirectory, null, *arguments)
    }

    @Throws(ExecException::class, InterruptedException::class)
    protected fun run(
        executable: Path, workingDirectory: Path?, environment: MutableMap<String?, String?>?, vararg arguments: String?
    ): String {
        val outErr: RecordingOutErr = RecordingOutErr()
        try {
            run(executable, workingDirectory, outErr, environment, arguments)
        } catch (e: ExecException) {
            throw IntegrationTestExecException(
                ("failed to execute '"
                        + executable.getPathString()
                        + "'\n----- captured stdout:\n"
                        + outErr.outAsLatin1()
                        + "\n----- captured stderr:"
                        + outErr.errAsLatin1()
                        + "\n----- Reason"),
                e.getCause()
            )
        }

        return outErr.outAsLatin1()
    }

    @Throws(Exception::class)
    protected fun run(executable: Path, outErr: OutErr, vararg arguments: String?) {
        run(executable, null, outErr, null, *arguments)
    }

    @Throws(ExecException::class, InterruptedException::class)
    private fun run(
        executable: Path,
        workingDirectory: Path?,
        outErr: OutErr,
        environment: MutableMap<String?, String?>?,
        vararg arguments: String?
    ) {
        var workingDirectory: Path? = workingDirectory
        if (workingDirectory == null) {
            workingDirectory = fileSystem.getPath(directories.getWorkspace().asFragment())
        }
        val argv: MutableList<String?> = Lists.newArrayList<String?>(*arguments)
        argv.add(0, executable.toString())
        val env: MutableMap<String?, String?>? =
            (if (environment != null) environment else this.targetConfiguration.getLocalShellEnvironment())
        val testOutErr: TestFileOutErr = TestFileOutErr()
        try {
            execute(workingDirectory, env, argv, testOutErr,  /* verboseFailures= */false)
        } finally {
            testOutErr.dumpOutAsLatin1(outErr.getOutputStream())
            testOutErr.dumpErrAsLatin1(outErr.getErrorStream())
        }
    }

    /**
     * Writes a number of lines of text to a source file using [ ][StandardCharsets.UTF_8] encoding.
     * 
     * @param relativePath the path relative to the workspace root.
     * @param lines the lines of text to write to the file.
     * @return the path of the created file.
     * @throws IOException if the file could not be written.
     */
    @Throws(IOException::class)
    fun write(relativePath: String?, vararg lines: String?): Path {
        val path: Path = workspace.getRelative(relativePath)
        return writeAbsolute(path, *lines)
    }

    /** Same as [.write], but with an absolute path.  */
    @Throws(IOException::class)
    protected fun writeAbsolute(path: Path, vararg lines: String?): Path {
        // Check that the path string encoding matches what is returned by NativePosixFiles. Otherwise,
        // tests may lose fidelity.
        val pathStr: String = path.getPathString()
        Preconditions.checkArgument(
            pathStr == String(pathStr.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1),
            "Path strings must be encoded as latin-1: %s",
            path
        )
        FileSystemUtils.writeLinesAs(path, StandardCharsets.UTF_8, lines)
        return path
    }

    /**
     * Creates folders on the path to `relativeLinkPath` and a symlink to `target` at
     * `relativeLinkPath` (equivalent to `ln -s <target> <relativeLinkPath>`).
     */
    @Throws(IOException::class)
    protected fun createSymlink(target: String?, relativeLinkPath: String?): Path {
        val path: Path = workspace.getRelative(relativeLinkPath)
        path.getParentDirectory().createDirectoryAndParents()
        path.createSymbolicLink(PathFragment.create(target))
        return path
    }

    @Throws(Exception::class)
    protected fun assertContents(expectedContents: String?, target: String?) {
        assertContents(expectedContents, Iterables.getOnlyElement<Artifact?>(getArtifacts(target)).getPath())
    }

    @Throws(Exception::class)
    protected fun assertContentsContainsAtLeast(expectedContents: String?, target: String?) {
        val actualContents = String(
            FileSystemUtils.readContentAsLatin1(
                Iterables.getOnlyElement<Artifact?>(getArtifacts(target)).getPath()
            )
        )
        Truth.assertThat(actualContents).contains(expectedContents)
    }

    @Throws(Exception::class)
    protected fun assertContents(expectedContents: String?, path: Path?) {
        val actualContents = String(FileSystemUtils.readContentAsLatin1(path))
        // .indent(0) doesn't change the indentation, but normalizes all OS-specific endings.
        Truth.assertThat(actualContents.indent(0).trim { it <= ' ' }).isEqualTo(expectedContents)
    }

    @Throws(IOException::class)
    protected fun readContentAsLatin1String(artifact: Artifact): String {
        return String(FileSystemUtils.readContentAsLatin1(artifact.getPath()))
    }

    @Throws(IOException::class)
    protected fun readContentAsByteArray(artifact: Artifact): ByteString? {
        return ByteString.copyFrom(FileSystemUtils.readContent(artifact.getPath()))
    }

    @Throws(IOException::class, InterruptedException::class)
    protected fun readInlineOutput(output: Artifact): String {
        val metadata: FileArtifactValue = getOutputMetadata(output)
        assertThat(metadata.isInline()).isTrue()
        return String(FileSystemUtils.readContentAsLatin1(metadata.getInputStream()))
    }

    @Throws(InterruptedException::class)
    protected fun getOutputMetadata(output: Artifact): FileArtifactValue {
        return getActionExecutionValue(output).getExistingFileArtifactValue(output)
    }

    @Throws(InterruptedException::class)
    protected fun getTreeArtifactValue(treeArtifact: Artifact): TreeArtifactValue? {
        return checkNotNull(
            getActionExecutionValue(treeArtifact).getAllTreeArtifactValues().get(treeArtifact),
            treeArtifact
        )
    }

    @Throws(InterruptedException::class)
    protected fun getSourceArtifactMetadata(sourceArtifact: Artifact?): FileArtifactValue? {
        assertThat(sourceArtifact).isInstanceOf(SourceArtifact::class.java)
        val sourceArtifactValue: SkyValue? =
            this.skyframeExecutor.getEvaluator().getExistingValue(sourceArtifact)
        assertThat(sourceArtifactValue).isInstanceOf(FileArtifactValue::class.java)
        return sourceArtifactValue as FileArtifactValue?
    }

    @Throws(InterruptedException::class)
    protected fun getActionExecutionValue(output: Artifact): ActionExecutionValue? {
        assertThat(output).isInstanceOf(DerivedArtifact::class.java)
        val actionExecutionValue: SkyValue? =
            this.skyframeExecutor
                .getEvaluator()
                .getExistingValue((output as DerivedArtifact).getGeneratingActionKey())
        assertThat(actionExecutionValue).isInstanceOf(ActionExecutionValue::class.java)
        return actionExecutionValue as ActionExecutionValue?
    }

    /**
     * Given a collection of Artifacts, returns a corresponding set of strings of the form "<root>
     * <relpath>", such as "bin x/libx.a". Such strings make assertions easier to write.
     * 
     * 
     * The returned set preserves the order of the input.
    </relpath></root> */
    protected fun artifactsToStrings(artifacts: NestedSet<Artifact?>): MutableSet<String?> {
        return AnalysisTestUtil.artifactsToStrings(
            this.targetConfigurationFromLastBuildResult, artifacts.toList()
        )
    }

    protected fun actionsTestUtil(): ActionsTestUtil {
        return ActionsTestUtil(this.actionGraph)
    }

    protected fun getExecutable(target: TransitiveInfoCollection): Artifact {
        return target.getProvider(FilesToRunProvider::class.java).getExecutable()
    }

    protected fun getFilesToBuild(target: TransitiveInfoCollection): NestedSet<Artifact?> {
        return target.getProvider(FileProvider::class.java).getFilesToBuild()
    }

    protected val request: BuildRequest?
        /** Returns the BuildRequest of the last call to buildTarget().  */
        get() = runtimeWrapper!!.getLastRequest()

    protected val result: BuildResult?
        /** Returns the BuildResultof the last call to buildTarget().  */
        get() = runtimeWrapper!!.getLastResult()

    protected val runtime: BlazeRuntime?
        /** Returns the [BlazeRuntime] in use.  */
        get() = runtimeWrapper!!.getRuntime()

    protected val blazeWorkspace: BlazeWorkspace
        get() = runtimeWrapper!!.getRuntime().getWorkspace()

    @Throws(TransitionException::class, InvalidConfigurationException::class, InterruptedException::class)
    protected fun getConfiguredTargetAndTarget(
        eventHandler: ExtendedEventHandler?, label: Label?, config: BuildConfigurationValue?
    ): ConfiguredTargetAndData {
        return this.skyframeExecutor.getConfiguredTargetAndDataForTesting(eventHandler, label, config)
    }

    protected val actionGraph: ActionGraph
        get() = this.skyframeExecutor.getActionGraph(events.reporter())

    protected open val commandEnvironment: CommandEnvironment?
        get() = runtimeWrapper!!.getCommandEnvironment()

    val skyframeExecutor: SkyframeExecutor?
        get() = runtimeWrapper!!.getSkyframeExecutor()

    protected val packageManager: PackageManager
        get() = this.skyframeExecutor.getPackageManager()

    protected fun getOutputBase(): Path {
        return outputBase
    }

    protected fun getDirectories(): BlazeDirectories {
        return directories
    }

    protected fun getWorkspace(): Path {
        return workspace
    }

    protected val buildResultListener: BuildResultListener
        get() = this.commandEnvironment.getBuildResultListener()

    protected val labelsOfAnalyzedTargets: ImmutableList<String?>
        get() = this.buildResultListener.getAnalyzedTargets().stream()
            .map({ x -> x.getLabel().toString() })
            .collect(ImmutableList.toImmutableList<E?>())

    protected val labelsOfAnalyzedAspects: ImmutableList<String?>
        get() = this.buildResultListener.getAnalyzedAspects().keySet().stream()
            .map({ x -> x.getLabel().toString() })
            .collect(ImmutableList.toImmutableList<E?>())

    protected val labelsOfBuiltTargets: ImmutableList<String?>
        get() = this.buildResultListener.getBuiltTargets().stream()
            .map({ x -> x.getLabel().toString() })
            .collect(ImmutableList.toImmutableList<E?>())

    protected val labelsOfBuiltAspects: ImmutableList<String?>
        get() = this.buildResultListener.getBuiltAspects().stream()
            .map({ x -> x.getLabel().toString() })
            .collect(ImmutableList.toImmutableList<E?>())

    protected val labelsOfSkippedTargets: ImmutableList<String?>
        get() = this.buildResultListener.getSkippedTargets().stream()
            .map({ x -> x.getLabel().toString() })
            .collect(ImmutableList.toImmutableList<E?>())

    protected val labelsOfAnalyzedTests: ImmutableList<String?>
        get() = this.buildResultListener.getAnalyzedTests().stream()
            .map({ x -> x.getLabel().toString() })
            .collect(ImmutableList.toImmutableList<E?>())

    @CanIgnoreReturnValue
    fun assertContainsEvent(expectedEvent: String?): Event? {
        return Companion.assertContainsEvent(events.collector(), expectedEvent)
    }

    @CanIgnoreReturnValue
    fun assertContainsEvent(kind: EventKind?, expectedEvent: String?): Event? {
        return MoreAsserts.assertContainsEvent(events.collector(), expectedEvent, kind)
    }

    fun assertDoesNotContainEvent(unexpectedEvent: String?) {
        assertDoesNotContainEvent(events.collector(), unexpectedEvent)
    }

    fun assertContainsError(expectedError: String?) {
        events.assertContainsError(expectedError)
    }

    /** [BugReporter] that stores bug reports for later inspection.  */
    class RecordingBugReporter : BugReporter {
        @GuardedBy("this")
        private val exceptions: MutableList<Throwable> = ArrayList<Throwable>()

        @kotlin.jvm.Synchronized
        override fun sendBugReport(
            exception: Throwable?, args: MutableList<String?>?, vararg values: String?
        ) {
            exceptions.add(exception!!)
        }

        @kotlin.jvm.Synchronized
        override fun sendNonFatalBugReport(exception: Throwable?) {
            exceptions.add(exception!!)
        }

        @FormatMethod
        override fun logUnexpected(message: String, vararg args: Any?) {
            sendBugReport(IllegalStateException(String.format(message, *args)))
        }

        @FormatMethod
        override fun logUnexpected(e: Exception?, message: String, vararg args: Any?) {
            sendBugReport(IllegalStateException(String.format(message, *args), e))
        }

        override fun handleCrash(crash: Crash, ctx: CrashContext) {
            // Unexpected: try to crash JVM.
            BugReport.handleCrash(crash, ctx)
        }

        @kotlin.jvm.Synchronized
        fun getExceptions(): ImmutableList<Throwable?> {
            return ImmutableList.copyOf<Throwable?>(exceptions)
        }

        @get:kotlin.jvm.Synchronized
        val firstCause: Throwable?
            get() {
                Truth.assertThat(exceptions).isNotEmpty()
                val first = exceptions.get(0)
                Truth.assertThat(first).hasCauseThat().isNotNull()
                return first.cause
            }

        @kotlin.jvm.Synchronized
        fun assertNoExceptions() {
            Truth.assertThat(exceptions).isEmpty()
        }

        @kotlin.jvm.Synchronized
        fun clear() {
            exceptions.clear()
        }
    }

    /**
     * Performs command registration to the extent that is necessary for test execution. The list of
     * commands isn't comprehensive and a command needn't be registered to be used. The purpose of
     * this module is to ensure that functionality that requires commands to be explicitly registered
     * (for example, per-command invocation policies) is sufficiently configured.
     */
    private class BuildIntegrationTestCommandsModule : BlazeModule() {
        public override fun serverInit(startupOptions: OptionsParsingResult?, builder: ServerBuilder) {
            builder.addCommands(
                BuildCommand(),
                QueryCommand(),
                CqueryCommand(),
                InfoCommand(),
                TestCommand(),
                CoverageCommand(),
                CleanCommand()
            )
        }
    }

    /** Redirect logging output to the given outErr stream at the given log level.  */
    protected fun divertLogging(level: Level?, outErr: OutErr, loggers: Iterable<Logger>) {
        val streamHandler: StreamHandler =
            object : StreamHandler(outErr.getErrorStream(), this.formatterForLogging) {
                @kotlin.jvm.Synchronized
                override fun publish(record: LogRecord?) {
                    super.publish(record)
                    flush()
                }

                @kotlin.jvm.Synchronized
                override fun close() {
                    throw UnsupportedOperationException()
                }
            }
        streamHandler.setLevel(Level.ALL)

        for (logger in loggers) {
            for (handler in logger.getHandlers()) {
                logger.removeHandler(handler)
            }
            logger.addHandler(streamHandler)
            logger.setLevel(level)
        }
    }

    protected val formatterForLogging: Formatter
        get() = SimpleFormatter()

    protected val allKeysInGraph: MutableSet<SkyKey>
        get() = this.skyframeExecutor.getEvaluator().getValues().keySet()

    /**
     * Copies the protolark-provided `project` scl definition into the given scratch file path.
     * 
     * 
     * `PROJECT.scl` files load this file to define their configuration. This method loads
     * the actual (non-mocked) file, so tests can effectively match production code.
     */
    @Throws(IOException::class)
    fun writeProjectSclDefinition(dest: String, alsoWriteBuildFile: Boolean) {
        write(
            dest,
            Files.readString(
                Path.of(
                    Runfiles.preload()
                        .withSourceRepository("")
                        .rlocation(
                            (TestConstants.WORKSPACE_NAME
                                    + "/"
                                    + TestConstants.PROJECT_SCL_DEFINITION_PATH)
                        )
                )
            )
        )
        if (alsoWriteBuildFile) {
            write(dest.substring(0, dest.lastIndexOf('/') + 1) + "BUILD")
        }
    }

    companion object {
        init {
            WindowsSubprocessFactory.maybeInstallWindowsSubprocessFactory()
        }

        @Throws(IOException::class)
        private fun bestEffortDeleteTreesBelow(path: Path, canSkip: Predicate<String?>) {
            for (dirent in path.readdir(Symlinks.NOFOLLOW)) {
                val child: Path = path.getRelative(dirent.name)
                if (dirent.type === Dirent.Type.DIRECTORY) {
                    try {
                        child.deleteTree()
                    } catch (e: IOException) {
                        bestEffortDeleteTreesBelow(child, canSkip)
                    }
                    continue
                }
                try {
                    child.delete()
                } catch (e: IOException) {
                    if (!canSkip.test(child.getBaseName())) {
                        throw e
                    }
                }
            }
        }

        /**
         * Check and clear crash was reported in [BugReport].
         * 
         * 
         * [BugReport] stores information about crashes in a static variable when running tests.
         * Tests which deliberately cause crashes, need to clear that flag not to taint the environment.
         */
        fun assertAndClearBugReporterStoredCrash(expected: Class<out Throwable?>?) {
            Truth.assertThat(BugReport.andResetLastCrashingThrowableIfInTest).isInstanceOf(expected)
        }

        /**
         * The TimestampGranularityMonitor operates on the files created by the request and thus does not
         * help here. Calling this method ensures that files we modify as part of the test environment are
         * considered as changed.
         */
        @Throws(Exception::class)
        protected fun waitForTimestampGranularity() {
            // Ext4 has a nanosecond granularity. Empirically, tmpfs supports ~5ms increments on
            // Ubuntu Trusty.
            Thread.sleep(10 /*ms*/)
        }

        /**
         * Performs a local direct spawn execution given spawn information broken out into individual
         * arguments. Directs standard out/err to `outErr`.
         * 
         * @param workingDirectory the directory from which to execute the subprocess
         * @param environment the environment map to provide to the subprocess. If null, the environment
         * is inherited from the parent process.
         * @param argv the argument vector including the command itself
         * @param outErr the out+err stream pair to receive stdout and stderr from the subprocess
         * @throws ExecException if any kind of abnormal termination or command exception occurs
         */
        @Throws(ExecException::class, InterruptedException::class)
        fun execute(
            workingDirectory: Path?,
            environment: MutableMap<String?, String?>?,
            argv: MutableList<String?>?,
            outErr: FileOutErr,
            verboseFailures: Boolean
        ) {
            val command: Command =
                CommandBuilder(System.getenv())
                    .addArgs(argv)
                    .setEnv(environment)
                    .setWorkingDir(workingDirectory)
                    .build()
            try {
                command.execute(outErr.getOutputStream(), outErr.getErrorStream())
            } catch (e: AbnormalTerminationException) { // non-zero exit or signal or I/O problem
                val e2 =
                    IntegrationTestExecException(CommandUtils.describeCommandFailure(verboseFailures, e))
                e2.initCause(e) // We don't pass cause=e to the ExecException constructor
                // since we don't want it to contribute to the exception
                // message again; it's already in describeCommandFailure().
                throw e2
            } catch (e: CommandException) {
                val e2 =
                    IntegrationTestExecException(CommandUtils.describeCommandFailure(verboseFailures, e))
                e2.initCause(e) // We don't pass cause=e to the ExecException constructor
                // since we don't want it to contribute to the exception
                // message again; it's already in describeCommandFailure().
                throw e2
            }
        }

        @CanIgnoreReturnValue
        fun assertContainsEvent(eventCollector: EventCollector?, expectedEvent: String?): Event? {
            return MoreAsserts.assertContainsEvent(eventCollector, expectedEvent)
        }

        fun assertDoesNotContainEvent(
            eventCollector: EventCollector?, unexpectedEvent: String?
        ) {
            MoreAsserts.assertDoesNotContainEvent(eventCollector, unexpectedEvent)
        }
    }
}
