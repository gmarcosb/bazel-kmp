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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.actions.ResourceManager

/**
 * This class represents a workspace, and contains operations and data related to it. In contrast,
 * the BlazeRuntime class represents the Blaze server, and contains operations and data that are
 * (supposed to be) independent of the workspace or the current command.
 * 
 * 
 * At this time, there is still a 1:1 relationship between the BlazeRuntime and the
 * BlazeWorkspace, but the introduction of this class is a step towards allowing 1:N relationships.
 */
class BlazeWorkspace(
    runtime: BlazeRuntime,
    directories: BlazeDirectories,
    skyframeExecutor: SkyframeExecutor,
    eventBusExceptionHandler: com.google.common.eventbus.SubscriberExceptionHandler?,
    workspaceStatusActionFactory: WorkspaceStatusAction.Factory?,
    binTools: BinTools?,
    allocationTracker: AllocationTracker?,
    syscallCache: SyscallCache?,
    analysisCodecRegistrySupplier: java.util.function.Supplier<ObjectCodecRegistry?>?,
    remoteAnalysisCachingServicesSupplier: RemoteAnalysisCachingServicesSupplier?,
    allowExternalRepositories: Boolean
) {
    private val runtime: BlazeRuntime
    private val eventBusExceptionHandler: com.google.common.eventbus.SubscriberExceptionHandler
    private val workspaceStatusActionFactory: WorkspaceStatusAction.Factory?
    private val binTools: BinTools?
    private val allocationTracker: AllocationTracker?

    private val directories: BlazeDirectories
    private val skyframeExecutor: SkyframeExecutor
    private val syscallCache: SyscallCache?
    private val quiescingExecutors: QuiescingExecutorsImpl
    private val analysisCodecRegistrySupplier: java.util.function.Supplier<ObjectCodecRegistry?>?

    /**
     * Null only during tests; should be created by a BlazeModule#workspaceInit hook for regular
     * operations.
     */
    private val remoteAnalysisCachingServicesSupplier: RemoteAnalysisCachingServicesSupplier?

    /**
     * The action cache, or null if it hasn't been loaded yet.
     * 
     * 
     * Loaded lazily by the first build command that enables the action cache. Cleared by a clean
     * command or by a build command that disables the action cache. Trimmed and reloaded by the
     * garbage collection idle task.
     */
    private var actionCache: ActionCache? = null

    /** The execution time range of the previous build command in this server, if any.  */
    private var lastExecutionRange: com.google.common.collect.Range<Long?>? = null

    /**
     * Returns the cached value of `getOutputBase().getFilesystem().getFileSystemType(getOutputBase())`, which is assumed to be
     * constant for a fixed workspace for the life of the Blaze server.
     */
    val outputBaseFilesystemTypeName: String?
    private val allowExternalRepositories: Boolean
    private val virtualPackageLocator: PathPackageLocator?

    /** An [IdleTask] to garbage collect the action cache.  */
    @com.google.common.annotations.VisibleForTesting
    internal inner class ActionCacheGarbageCollectorIdleTask(
        delay: java.time.Duration?,
        threshold: Float,
        maxAge: java.time.Duration?
    ) : com.google.devtools.build.lib.server.IdleTask {
        private val delay: java.time.Duration?

        @kotlin.jvm.JvmField
        @get:com.google.common.annotations.VisibleForTesting
        val threshold: Float
        private val maxAge: java.time.Duration?

        init {
            this.delay = delay
            this.threshold = threshold
            this.maxAge = maxAge
        }

        override fun displayName(): String {
            return "Action cache garbage collector"
        }

        override fun delay(): java.time.Duration? {
            return delay
        }

        @com.google.common.annotations.VisibleForTesting
        fun getMaxAge(): java.time.Duration? {
            return maxAge
        }

        @Throws(com.google.devtools.build.lib.server.IdleTaskException::class, java.lang.InterruptedException::class)
        override fun run() {
            try {
                if (actionCache == null) {
                    // Do not load the action cache just to garbage collect it.
                    return
                }
                // Note that this reads and writes to the field in the outer class.
                actionCache = actionCache.trim(threshold, maxAge)
            } catch (e: IOException) {
                throw com.google.devtools.build.lib.server.IdleTaskException(e)
            }
        }
    }

    init {
        this.runtime = runtime
        this.eventBusExceptionHandler =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.eventbus.SubscriberExceptionHandler>(
                eventBusExceptionHandler
            )
        this.workspaceStatusActionFactory = workspaceStatusActionFactory
        this.binTools = binTools
        this.allocationTracker = allocationTracker

        this.directories = directories
        this.skyframeExecutor = skyframeExecutor
        this.syscallCache = syscallCache
        this.quiescingExecutors = QuiescingExecutorsImpl.createDefault()
        this.allowExternalRepositories = allowExternalRepositories
        this.virtualPackageLocator = createPackageLocatorIfVirtual(directories, skyframeExecutor)
        this.analysisCodecRegistrySupplier = analysisCodecRegistrySupplier
        this.remoteAnalysisCachingServicesSupplier = remoteAnalysisCachingServicesSupplier

        if (directories.inWorkspace()) {
            writeOutputBaseReadmeFile()
            writeDoNotBuildHereFile()
        }

        // Here we use outputBase instead of outputPath because we need a file system to create the
        // latter.
        this.outputBaseFilesystemTypeName =
            com.google.devtools.build.lib.vfs.FileSystemUtils.getFileSystem(this.outputBase)
    }

    fun getRuntime(): BlazeRuntime {
        return runtime
    }

    /**
     * Returns the Blaze directories object for this runtime.
     */
    fun getDirectories(): BlazeDirectories {
        return directories
    }

    fun getSkyframeExecutor(): SkyframeExecutor {
        return skyframeExecutor
    }

    fun getWorkspaceStatusActionFactory(): WorkspaceStatusAction.Factory? {
        return workspaceStatusActionFactory
    }

    fun getBinTools(): BinTools? {
        return binTools
    }

    val workspace: com.google.devtools.build.lib.vfs.Path
        /**
         * Returns the working directory of the server.
         * 
         * 
         * This is often the first entry on the `--package_path`, but not always.
         * Callers should certainly not make this assumption. The Path returned may be null.
         */
        get() = directories.getWorkingDirectory()

    val outputBase: com.google.devtools.build.lib.vfs.Path
        /**
         * Returns the output base directory associated with this Blaze server
         * process. This is the base directory for shared Blaze state as well as tool
         * and strategy specific subdirectories.
         */
        get() = directories.getOutputBase()

    val installBase: com.google.devtools.build.lib.vfs.Path
        get() = directories.getInstallBase()

    private val actionCacheDirectory: com.google.devtools.build.lib.vfs.Path?
        /**
         * Returns the path to the action cache directory.
         * 
         * 
         * This path must be a descendant of the output base, as the action cache cannot be safely
         * shared between different workspaces.
         */
        get() = this.outputBase.getChild("action_cache")

    private val corruptedActionCacheDirectory: com.google.devtools.build.lib.vfs.Path?
        /**
         * Returns the path where an action cache previously determined to be corrupted is stored. *
         * 
         * 
         * This path must be a descendant of the output base, as the action cache cannot be safely
         * shared between different workspaces.
         */
        get() = this.outputBase.getChild("action_cache.bad")

    private val actionCacheTmpDirectory: com.google.devtools.build.lib.vfs.Path?
        /**
         * Returns the path where the action cache may temporarily store data during garbage collection.
         * 
         * 
         * This path must be a descendant of the output base, as the action cache cannot be safely
         * shared between different workspaces.
         */
        get() = this.outputBase.getChild("action_cache.tmp")

    /** Returns an [IdleTask] to garbage collect the action cache.  */
    fun getActionCacheGcIdleTask(
        delay: java.time.Duration?,
        threshold: Float,
        maxAge: java.time.Duration?
    ): com.google.devtools.build.lib.server.IdleTask {
        return ActionCacheGarbageCollectorIdleTask(delay, threshold, maxAge)
    }

    fun recordLastExecutionTime(commandStartTime: Long) {
        val currentTimeMillis: Long = runtime.getClock().currentTimeMillis()
        lastExecutionRange =
            if (currentTimeMillis >= commandStartTime)
                com.google.common.collect.Range.closed<Long?>(commandStartTime, currentTimeMillis)
            else
                null
    }

    val lastExecutionTimeRange: com.google.common.collect.Range<Long?>?
        /**
         * Range that represents the last execution time of a build in millis since epoch.
         */
        get() = lastExecutionRange

    /**
     * Initializes a CommandEnvironment to execute a command in this workspace.
     * 
     * 
     * This method should be called from the "main" thread on which the command will execute; that
     * thread will receive interruptions if a module requests an early exit.
     * 
     * @param warnings a list of warnings to which the CommandEnvironment can add any warning
     * generated during initialization. This is needed because Blaze's output handling is not yet
     * fully configured at this point.
     */
    fun initCommand(
        command: com.google.devtools.build.lib.runtime.Command?,
        options: com.google.devtools.common.options.OptionsParsingResult,
        invocationPolicy: InvocationPolicy?,
        warnings: MutableList<String?>?,
        waitTimeInMs: Long,
        commandStartTime: Long,
        idleTaskResultsFromPreviousIdlePeriod: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask.Result?>?,
        shutdownReasonConsumer: java.util.function.Consumer<String?>?,
        commandExtensions: MutableList<Any?>?,
        commandExtensionReporter: CommandExtensionReporter?,
        attemptNumber: Int,
        buildRequestIdOverride: String?,
        configFlagDefinitions: ConfigFlagDefinitions?
    ): CommandEnvironment {
        quiescingExecutors.resetParameters(options)
        val env: CommandEnvironment =
            CommandEnvironment(
                runtime,
                this,
                com.google.common.eventbus.EventBus(eventBusExceptionHandler),
                java.lang.Thread.currentThread(),
                command,
                options,
                invocationPolicy,
                getOrCreatePackageLocatorForCommand(options),
                syscallCache,
                quiescingExecutors,
                warnings,
                waitTimeInMs,
                commandStartTime,
                idleTaskResultsFromPreviousIdlePeriod,
                shutdownReasonConsumer,
                commandExtensions,
                commandExtensionReporter,
                attemptNumber,
                buildRequestIdOverride,
                configFlagDefinitions,
                ResourceManager()
            )
        skyframeExecutor.setClientEnv(env.getClientEnv())
        val buildRequestOptions: BuildRequestOptions? = options.getOptions<O?>(BuildRequestOptions::class.java)
        if (buildRequestOptions != null && !buildRequestOptions.useActionCache) {
            // Drop the action cache reference to save memory since we don't need it for this build. If a
            // subsequent build needs it, getOrLoadPersistentActionCache will reload it from disk.
            actionCache = null
        }
        return env
    }

    fun clearEventBus() {
        // EventBus does not have an unregister() method, so this is how we release memory associated
        // with handlers.
        skyframeExecutor.setEventBus(null)
    }

    /**
     * Reinitializes the Skyframe evaluator.
     */
    fun resetEvaluator() {
        skyframeExecutor.resetEvaluator()
    }

    /** Removes in-memory and on-disk action caches.  */
    @Throws(IOException::class)
    fun clearCaches() {
        if (actionCache != null) {
            actionCache.clear()
        }
        actionCache = null
        this.actionCacheDirectory.deleteTree()
        this.corruptedActionCacheDirectory.deleteTree()
        this.actionCacheTmpDirectory.deleteTree()
    }

    /**
     * Returns the action cache, loading it from disk if it isn't already loaded.
     * 
     * 
     * The returned reference is only valid for the current build request, as build options may
     * affect the presence of an action cache.
     */
    @Throws(IOException::class)
    fun getOrLoadPersistentActionCache(reporter: com.google.devtools.build.lib.events.Reporter?): ActionCache? {
        if (actionCache == null) {
            GoogleAutoProfilerUtils.profiledAndLogged("Loading action cache", ProfilerTask.INFO).use { p ->
                actionCache =
                    CompactPersistentActionCache.create(
                        this.actionCacheDirectory,
                        this.corruptedActionCacheDirectory,
                        this.actionCacheTmpDirectory,
                        runtime.getClock(),
                        reporter
                    )
            }
        }
        return actionCache
    }

    val persistentActionCache: ActionCache?
        /**
         * Returns the action cache, or null if it isn't already loaded.
         * 
         * 
         * The returned reference is only valid for the current build request, as build options may
         * affect the presence of an action cache.
         */
        get() = actionCache

    /**
     * Generates a README file in the output base directory. This README file
     * contains the name of the workspace directory, so that users can figure out
     * which output base directory corresponds to which workspace.
     */
    private fun writeOutputBaseReadmeFile() {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(this.workspace)
        val outputBaseReadmeFile: com.google.devtools.build.lib.vfs.Path? = this.outputBase.getRelative("README")
        try {
            com.google.devtools.build.lib.vfs.FileSystemUtils.writeIsoLatin1(
                outputBaseReadmeFile,
                "WORKSPACE: " + this.workspace,
                "",
                "The first line of this file is intentionally easy to parse for various",
                "interactive scripting and debugging purposes.  But please DO NOT write programs",
                "that exploit it, as they will be broken by design: it is not possible to",
                "reverse engineer the set of source trees or the --package_path from the output",
                "tree, and if you attempt it, you will fail, creating subtle and",
                "hard-to-diagnose bugs, that will no doubt get blamed on changes made by the",
                "Bazel team.",
                "",
                "This directory was generated by Bazel.",
                "Do not attempt to modify or delete any files in this directory.",
                "Among other issues, Bazel's file system caching assumes that",
                "only Bazel will modify this directory and the files in it,",
                "so if you change anything here you may mess up Bazel's cache."
            )
        } catch (e: IOException) {
            logger.atWarning().withCause(e).log("Couldn't write to '%s'", outputBaseReadmeFile)
        }
    }

    private fun writeDoNotBuildHereFile(filePath: com.google.devtools.build.lib.vfs.Path) {
        try {
            filePath.getParentDirectory().createDirectoryAndParents()
            com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent(
                filePath,
                java.nio.charset.StandardCharsets.ISO_8859_1,
                this.workspace.toString()
            )
        } catch (e: IOException) {
            logger.atWarning().withCause(e).log("Couldn't write to '%s'", filePath)
        }
    }

    private fun writeDoNotBuildHereFile() {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(this.workspace)
        writeDoNotBuildHereFile(this.outputBase.getRelative(DO_NOT_BUILD_FILE_NAME))
        writeDoNotBuildHereFile(
            this.outputBase.getRelative("execroot").getRelative(DO_NOT_BUILD_FILE_NAME)
        )
    }

    fun getAllocationTracker(): AllocationTracker? {
        return allocationTracker
    }

    fun doesAllowExternalRepositories(): Boolean {
        return allowExternalRepositories
    }

    val analysisObjectCodecRegistrySupplier: java.util.function.Supplier<ObjectCodecRegistry?>?
        get() = analysisCodecRegistrySupplier

    fun remoteAnalysisCachingServicesSupplier(): RemoteAnalysisCachingServicesSupplier? {
        return remoteAnalysisCachingServicesSupplier
    }

    // Null for commands that don't have PackageOptions (version, help, shutdown, etc).
    private fun getOrCreatePackageLocatorForCommand(options: com.google.devtools.common.options.OptionsParsingResult): PathPackageLocator? {
        val packageOptions: O? = options.getOptions<O?>(PackageOptions::class.java)
        val workspace: com.google.devtools.build.lib.vfs.Path? = directories.getWorkspace()
        if (packageOptions == null || workspace == null) {
            return null
        }
        if (virtualPackageLocator != null) {
            return virtualPackageLocator
        }
        return PathPackageLocator.create(
            directories.getOutputBase(),
            packageOptions.getPackagePath(),
            NullEventHandler.INSTANCE,
            workspace.asFragment(),
            workspace,
            skyframeExecutor.getBuildFilesByPriority()
        )
    }

    companion object {
        const val DO_NOT_BUILD_FILE_NAME: String = "DO_NOT_BUILD_HERE"

        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private fun createPackageLocatorIfVirtual(
            directories: BlazeDirectories, skyframeExecutor: SkyframeExecutor
        ): PathPackageLocator? {
            val virtualSourceRoot: Root? = directories.getVirtualSourceRoot()
            if (virtualSourceRoot == null) {
                return null
            }
            return PathPackageLocator.createWithoutExistenceCheck( /* outputBase= */
                null,
                com.google.common.collect.ImmutableList.of<E?>(virtualSourceRoot),
                skyframeExecutor.getBuildFilesByPriority()
            )
        }
    }
}
