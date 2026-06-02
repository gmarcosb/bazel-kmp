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

import com.google.devtools.build.lib.actions.Artifact

/**
 * Provides the ability to augment the functionality of the Logical Component (LC).
 * 
 * 
 * The augmentation is done by implementing one or more of the methods in this class, which are
 * called at well-defined points during the server's lifecycle.
 * 
 * 
 * The set of modules is passed into [BlazeRuntime.main] and is fixed for the lifetime of
 * the server. A module can be obtained by calling [BlazeRuntime.getBlazeModule].
 * 
 * 
 * The constructors of individual Bazel modules must take no arguments and be empty. All work
 * should be done in the methods (e.g. [.blazeStartup]).
 */
abstract class BlazeModule : com.google.devtools.build.lib.runtime.OptionsSupplier {
    val startupOptions: Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase>>
        get() = com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()

    /**
     * Called at the beginning of Bazel startup, before [.getFileSystem] and [ ][.blazeStartup].
     * 
     * @param startupOptions the server's startup options
     * @param blazeServices the available services
     * @throws AbruptExitException to shut down the server immediately
     */
    @Throws(AbruptExitException::class)
    open fun globalInit(
        startupOptions: com.google.devtools.common.options.OptionsParsingResult?,
        blazeServices: Iterable<com.google.devtools.build.lib.runtime.BlazeService?>?
    ) {
    }

    /**
     * Returns the file system implementation used by Bazel.
     * 
     * 
     * Exactly one module must return a non-null value from this method, or an error will occur.
     * 
     * 
     * This method will be called at the beginning of Bazel startup (in-between [.globalInit]
     * and [.blazeStartup]).
     * 
     * @param startupOptions the server's startup options
     */
    @Throws(AbruptExitException::class)
    fun getFileSystem(startupOptions: com.google.devtools.common.options.OptionsParsingResult?): ModuleFileSystem? {
        return null
    }

    /**
     * Returns the file system implementation used by Bazel to read or write build artifacts.
     * 
     * 
     * At most one module may return a non-null value from this method, or an error will occur. If
     * no module returns a non-null value, the file system returned by [.getFileSystem] from
     * this or another module will be used.
     * 
     * 
     * This method will be called at the beginning of Bazel startup (in-between [.globalInit]
     * and [.blazeStartup]).
     * 
     * @param fileSystem the file system returned by [.getFileSystem] from this or another
     * module
     */
    fun getFileSystemForBuildArtifacts(fileSystem: com.google.devtools.build.lib.vfs.FileSystem?): com.google.devtools.build.lib.vfs.FileSystem? {
        return null
    }

    /** Tuple returned by [.getFileSystem].  */
    @AutoValue
    abstract class ModuleFileSystem {
        abstract fun fileSystem(): com.google.devtools.build.lib.vfs.FileSystem?

        /**
         * Present if this filesystem virtualizes the source root. See [ ][ServerDirectories.getVirtualSourceRoot].
         */
        abstract fun virtualSourceRoot(): java.util.Optional<Root?>?

        companion object {
            fun createWithVirtualization(
                fileSystem: com.google.devtools.build.lib.vfs.FileSystem, virtualSourceRoot: PathFragment?
            ): ModuleFileSystem {
                return AutoValue_BlazeModule_ModuleFileSystem(
                    fileSystem, java.util.Optional.of<T?>(Root.fromPath(fileSystem.getPath(virtualSourceRoot)))
                )
            }

            fun create(fileSystem: com.google.devtools.build.lib.vfs.FileSystem?): ModuleFileSystem {
                return AutoValue_BlazeModule_ModuleFileSystem(
                    fileSystem,  /* virtualSourceRoot= */java.util.Optional.empty<T?>()
                )
            }
        }
    }

    val eventBusAndAsyncExceptionHandler: com.google.common.eventbus.SubscriberExceptionHandler?
        /**
         * Returns handler for [com.google.common.eventbus.EventBus] subscriber and async thread
         * exceptions. For async thread exceptions, [ ][SubscriberExceptionHandler.handleException] will be called with null [ ]. If all modules return null, a handler
         * that crashes on all async exceptions and files bug reports for all EventBus subscriber
         * exceptions will be used.
         */
        get() = null

    /**
     * Called when Bazel starts up after [.getStartupOptions], [.globalInit] and [ ][.getFileSystem].
     * 
     * @param startupOptions the server's startup options
     * @param versionInfo the Bazel version currently running
     * @param instanceId the id of the current Bazel server
     * @param directories the install directory
     * @throws AbruptExitException to shut down the server immediately
     */
    @Throws(AbruptExitException::class)
    fun blazeStartup(
        startupOptions: com.google.devtools.common.options.OptionsParsingResult?,
        versionInfo: BlazeVersionInfo?,
        instanceId: UUID?,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
        directories: ServerDirectories?,
        clock: com.google.devtools.build.lib.clock.Clock?
    ) {
    }

    /**
     * Called to initialize a new server ([BlazeRuntime]). Modules can override this method to
     * affect how the server is configured. This is called after the startup options have been
     * collected and parsed, and after the file system was setup.
     * 
     * @param startupOptions the server startup options
     * @param builder builder class that collects the server configuration
     * @throws AbruptExitException to shut down the server immediately
     */
    @Throws(AbruptExitException::class)
    open fun serverInit(
        startupOptions: com.google.devtools.common.options.OptionsParsingResult?,
        builder: com.google.devtools.build.lib.runtime.ServerBuilder?
    ) {
    }

    /**
     * Sets up the configured rule class provider, which contains the built-in rule classes, aspects,
     * configuration fragments, and other things; called during Blaze startup (after [ ][.blazeStartup]).
     * 
     * 
     * Bazel only creates one provider per server, so it is not possible to have different contents
     * for different workspaces.
     * 
     * @param builder the configured rule class provider builder
     */
    open fun initializeRuleClasses(builder: ConfiguredRuleClassProvider.Builder?) {}

    /**
     * Called when Bazel initializes a new workspace; this is only called after [.serverInit],
     * and only if the server initialization was successful. Modules can override this method to
     * affect how the workspace is configured.
     * 
     * @param runtime the blaze runtime
     * @param directories the workspace directories
     * @param builder the workspace builder
     */
    open fun workspaceInit(
        runtime: BlazeRuntime?, directories: BlazeDirectories?, builder: WorkspaceBuilder?
    ) {
    }

    /**
     * Called to notify modules that the given command is about to be executed. This allows capturing
     * the [com.google.common.eventbus.EventBus], [Command], or [ ].
     * 
     * @param env the command
     * @throws AbruptExitException modules can throw this exception to abort the command
     */
    @Throws(AbruptExitException::class)
    open fun beforeCommand(env: CommandEnvironment?) {
    }

    open val outputListener: OutErr?
        /**
         * Returns additional listeners to the console output stream. Called at the beginning of each
         * command (after #beforeCommand).
         */
        get() = null

    @get:Throws(AbruptExitException::class)
    open val outputService: OutputService?
        /**
         * Returns the [OutputService] to be used.
         * 
         * 
         * It is an error if more than one module returns a non-null output service. If all modules
         * return `null`, then [com.google.devtools.build.lib.vfs.LocalOutputService] will be
         * used.
         * 
         * 
         * This method is called at the beginning of each command (after [.beforeCommand]).
         */
        get() = null

    override fun getCommandOptions(commandName: String?): Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
        return com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()
    }

    val commonCommandOptions: Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase>>
        get() = com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()

    /**
     * Called after Bazel analyzes the build's top-level targets. This is called once per build if
     * --analyze is enabled. Modules can override this to perform extra checks on analysis results.
     * 
     * @param env the command environment
     * @param request the build request
     * @param buildOptions the build's top-level options
     * @param analysisResult the build's analysis result
     */
    @Throws(java.lang.InterruptedException::class, ViewCreationFailedException::class)
    fun afterAnalysis(
        env: CommandEnvironment?,
        request: BuildRequest?,
        buildOptions: BuildOptions?,
        analysisResult: AnalysisResult?
    ) {
    }

    /**
     * Called after Bazel analyzes a single top-level target.
     * 
     * @param env the command environment
     * @param request the build request
     * @param buildOptions the build's top-level options
     * @param configuredTarget the analyzed top-level target
     */
    @Throws(java.lang.InterruptedException::class, ViewCreationFailedException::class)
    fun afterTopLevelTargetAnalysis(
        env: CommandEnvironment?,
        request: BuildRequest?,
        buildOptions: BuildOptions?,
        configuredTarget: ConfiguredTarget?
    ) {
    }

    fun afterSingleAspectAnalysis(request: BuildRequest?, configuredTarget: ConfiguredAspect?) {}

    fun afterSingleTestAnalysis(request: BuildRequest?, configuredTarget: ConfiguredTarget?) {}

    fun coverageArtifactsKnown(coverageArtifacts: com.google.common.collect.ImmutableSet<Artifact?>?) {}

    /**
     * Called when Bazel initializes the action execution subsystem. This is called once per build if
     * action execution is enabled. Modules can override this method to affect how execution is
     * performed.
     * 
     * @param env the command environment
     * @param request the build request
     * @param builder the builder to add action context providers and consumers to
     */
    @Throws(AbruptExitException::class)
    open fun executorInit(env: CommandEnvironment?, request: BuildRequest?, builder: ExecutorBuilder?) {
    }

    /**
     * Registers any action contexts this module provides with the execution phase. They will be
     * available for [ ][com.google.devtools.build.lib.actions.ActionContext.ActionContextRegistry.getContext]
     * to actions and other action contexts.
     * 
     * 
     * This method is invoked before actions are executed but after [.executorInit].
     * 
     * @param registryBuilder builder with which to register action contexts
     * @param env environment for the current command
     * @param buildRequest the current build request
     * @throws AbruptExitException if there are fatal issues creating or registering action contexts
     */
    @Throws(AbruptExitException::class)
    open fun registerActionContexts(
        registryBuilder: ModuleActionContextRegistry.Builder?,
        env: CommandEnvironment?,
        buildRequest: BuildRequest?
    ) {
    }

    /**
     * Registers any spawn strategies this module provides with the execution phase.
     * 
     * 
     * This method is invoked before actions are executed but after [.executorInit].
     * 
     * @param registryBuilder builder with which to register strategies
     * @param env environment for the current command
     * @throws AbruptExitException if there are fatal issues creating or registering strategies
     */
    @Throws(AbruptExitException::class, java.lang.InterruptedException::class)
    open fun registerSpawnStrategies(
        registryBuilder: SpawnStrategyRegistry.Builder?, env: CommandEnvironment?
    ) {
    }

    /**
     * Called after each command.
     * 
     * @throws AbruptExitException modules can throw this exception to modify the command exit code
     */
    @Throws(AbruptExitException::class)
    open fun afterCommand() {
    }

    /**
     * Called after [.afterCommand]. This method can be used to close and cleanup resources
     * specific to the command.
     * 
     * 
     * This method must not throw any exceptions, report any errors or generate any stdout/stderr.
     * Any of the above will make Bazel crash occasionally. Please use [.afterCommand]
     * instead.
     */
    open fun commandComplete() {}

    /**
     * Called when Blaze shuts down.
     * 
     * 
     * If you are also implementing [.blazeShutdownOnCrash], consider putting the common
     * shutdown code in the latter and calling that other hook from here.
     * 
     * 
     * This is also called after each test case in [ ] and can be used to avoid
     * leaking resources when this module instance is thrown away between tests.
     */
    open fun blazeShutdown() {}

    /**
     * Called when Blaze shuts down due to a crash.
     * 
     * 
     * Modules may use this to flush pending state, but they must be careful to only do a minimal
     * number of things. Keep in mind that we are crashing so who knows what state we are in. Modules
     * rarely need to implement this.
     */
    open fun blazeShutdownOnCrash(exitCode: DetailedExitCode?) {}

    /**
     * Returns true if the module will arrange for a `BuildMetricsEvent` to be posted after the
     * build completes.
     * 
     * 
     * The Blaze runtime ensures that it has exactly one module for which this method returns true,
     * substituting its own module if none is supplied explicitly.
     * 
     * 
     * It is an error if multiple modules return true.
     */
    fun postsBuildMetricsEvent(): Boolean {
        return false
    }

    val queryRuntimeHelperFactory: com.google.devtools.build.lib.runtime.QueryRuntimeHelper.Factory?
        /**
         * Returns a [QueryRuntimeHelper.Factory] that will be used by the query, cquery, and aquery
         * commands.
         * 
         * 
         * It is an error if multiple modules return non-null values.
         */
        get() = null

    val packageSettings: PackageSettings?
        /**
         * Returns [PackageSettings] for creating packages.
         * 
         * 
         * Called once during server startup some time after [.serverInit].
         * 
         * 
         * Note that only one helper per Bazel/Blaze runtime is allowed.
         */
        get() = null

    val packageValidator: PackageValidator?
        /**
         * Returns a [PackageValidator] to be used to validate loaded packages, or null if the
         * module does not provide any validator.
         * 
         * 
         * Called once during server startup some time after [.serverInit].
         * 
         * 
         * Note that only one instance per Bazel/Blaze runtime is allowed.
         */
        get() = null

    val packageOverheadEstimator: PackageOverheadEstimator?
        /**
         * Returns a [PackageOverheadEstimator] to be used to estimate the cost of loaded packages,
         * or null if the module does not provide any such functionality.
         * 
         * 
         * Called once during server startup some time after [.serverInit].
         * 
         * 
         * Note that only one instance per Bazel/Blaze runtime is allowed
         */
        get() = null

    /**
     * Returns a [PackageLoadingListener] for observing successful package loading, or null if
     * the module does not provide any validator.
     * 
     * 
     * Called once during server startup some time after [.serverInit].
     */
    fun getPackageLoadingListener(
        packageSettings: PackageSettings?,
        ruleClassProvider: ConfiguredRuleClassProvider?,
        fs: com.google.devtools.build.lib.vfs.FileSystem?
    ): PackageLoadingListener? {
        return null
    }

    val slowThreadInterruptMessageSuffix: String?
        get() = null

    /**
     * Optionally returns a provider for project files that can be used to bundle targets and
     * command-line options.
     */
    fun createProjectFileProvider(): com.google.devtools.build.lib.runtime.ProjectFile.Provider? {
        return null
    }

    /**
     * Optionally returns a factory to create coverage report actions; this is called once per build,
     * such that it can be affected by command options.
     * 
     * 
     * It is an error if multiple modules return non-null values.
     * 
     * @param commandOptions the options for the current command
     */
    fun getCoverageReportFactory(commandOptions: com.google.devtools.common.options.OptionsProvider?): CoverageReportActionFactory? {
        return null
    }

    /** Services provided for Blaze modules via BlazeRuntime.  */
    interface ModuleEnvironment {
        /**
         * Gets a file from the depot based on its label and returns the [Path] where it can be
         * found.
         * 
         * 
         * Returns null when the package designated by the label does not exist.
         */
        fun getFileFromWorkspace(label: Label?): com.google.devtools.build.lib.vfs.Path?

        /** Exits Blaze as early as possible by sending an interrupt to the command's main thread.  */
        fun exit(exception: AbruptExitException?)
    }

    val precomputedValues: com.google.common.collect.ImmutableList<Injected?>
        /**
         * Provides additional precomputed values to inject into the skyframe graph. Called on every
         * command execution.
         */
        get() = com.google.common.collect.ImmutableList.of<Injected?>()

    override fun toString(): String {
        return this.getClass().getSimpleName()
    }
}
