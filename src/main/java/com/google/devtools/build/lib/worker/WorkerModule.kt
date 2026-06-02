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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.exec.ExecutionOptions

/** A module that adds the WorkerActionContextProvider to the available action context providers.  */
class WorkerModule : BlazeModule() {
    private var env: CommandEnvironment? = null

    @kotlin.jvm.JvmField
    @com.google.common.annotations.VisibleForTesting
    var workerFactory: WorkerFactory? = null
    private var treeDeleter: AsynchronousTreeDeleter? = null

    var config: WorkerPoolConfig? = null

    @kotlin.jvm.JvmField
    @com.google.common.annotations.VisibleForTesting
    var workerPool: WorkerPool? = null
    private var workerLifecycleManager: WorkerLifecycleManager? = null

    public override fun getCommandOptions(commandName: String): Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
        return if (commandName == "build") com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
            WorkerOptions::class.java
        ) else com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()
    }

    public override fun beforeCommand(env: CommandEnvironment) {
        this.env = env
        env.getEventBus().register(this)
        WorkerProcessMetricsCollector.Companion.instance().beforeCommand()
        WorkerMultiplexerManager.beforeCommand(env.getReporter())
    }

    @com.google.common.eventbus.Subscribe
    fun cleanStarting(event: CleanStartingEvent) {
        if (workerPool != null) {
            val options: WorkerOptions? = event.optionsProvider.getOptions<WorkerOptions?>(WorkerOptions::class.java)
            workerFactory.setReporter(if (options.getWorkerVerbose()) env.getReporter() else null)
            shutdownPool(
                "Clean command is running, shutting down worker pool...",  /* alwaysLog= */
                false,
                options.getWorkerVerbose()
            )
        }
    }

    /**
     * Handles updating worker factories and pools when a build starts. If either the workerDir or the
     * sandboxing flag has changed, we need to recreate the factory, and we clear out logs at the same
     * time. If options affecting the pools have changed, we just change the pools.
     */
    @com.google.common.eventbus.Subscribe
    fun buildStarting(event: BuildStartingEvent) {
        val options: WorkerOptions =
            com.google.common.base.Preconditions.checkNotNull<T>(event.request().getOptions(WorkerOptions::class.java))
        if (workerFactory != null) {
            workerFactory.setReporter(if (options.getWorkerVerbose()) env.getReporter() else null)
        }
        val workerDir: com.google.devtools.build.lib.vfs.Path =
            env.getOutputBase().getRelative(env.getRuntime().productName + "-workers")
        val workspace: BlazeWorkspace? = env.getBlazeWorkspace()
        val workerSandboxOptions: WorkerSandboxOptions?
        val sandboxOptions: SandboxOptions? = event.request().getOptions(SandboxOptions::class.java)
        if (options.getSandboxHardening()) {
            workerSandboxOptions =
                WorkerSandboxOptions(
                    LinuxSandboxUtil.getLinuxSandbox(workspace),
                    sandboxOptions.sandboxFakeHostname,
                    sandboxOptions.sandboxFakeUsername,
                    sandboxOptions.sandboxDebug,
                    com.google.common.collect.ImmutableSet.copyOf(sandboxOptions.sandboxTmpfsPath),
                    com.google.common.collect.ImmutableSet.copyOf(sandboxOptions.sandboxWritablePath),
                    sandboxOptions.memoryLimitMb,
                    sandboxOptions.getInaccessiblePaths(env.getRuntime().getFileSystem()),
                    com.google.common.collect.ImmutableMap.builder<String?, String?>()
                        .putAll(sandboxOptions.sandboxAdditionalMounts)
                        .buildKeepingLast()
                )
        } else {
            workerSandboxOptions = null
        }
        val trashBase: com.google.devtools.build.lib.vfs.Path =
            workerDir.getRelative(AsynchronousTreeDeleter.MOVED_TRASH_DIR)
        if (treeDeleter == null) {
            treeDeleter = AsynchronousTreeDeleter(trashBase)
            if (trashBase.exists()) {
                removeStaleTrash(workerDir, trashBase)
            }
        }
        val cgroupFactory: VirtualCgroupFactory? =
            if (com.google.devtools.build.lib.util.OS.Companion.getCurrent() != com.google.devtools.build.lib.util.OS.LINUX || sandboxOptions == null || !sandboxOptions.useNewCgroupImplementation)
                null
            else
                VirtualCgroupFactory(
                    "worker_",
                    VirtualCgroup.getInstance(),
                    if (options.getSandboxHardening()) sandboxOptions.getLimitsMap() else com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    options.getUseCgroupsOnLinux()
                )

        val newWorkerFactory: WorkerFactory =
            WorkerFactory(workerDir, options, workerSandboxOptions, treeDeleter, cgroupFactory)
        if (newWorkerFactory != workerFactory) {
            if (workerDir.exists()) {
                try {
                    // Clean out old log files.
                    for (logFile in workerDir.getDirectoryEntries()) {
                        if (logFile.getBaseName().endsWith(".log")) {
                            try {
                                logFile.delete()
                            } catch (e: IOException) {
                                env.getReporter()
                                    .handle(
                                        com.google.devtools.build.lib.events.Event.warn(
                                            java.lang.String.format(
                                                "Could not delete old worker log '%s': %s",
                                                logFile, e.getMessage()
                                            )
                                        )
                                    )
                            }
                        }
                    }
                } catch (e: IOException) {
                    env.getReporter()
                        .handle(
                            com.google.devtools.build.lib.events.Event.warn(
                                java.lang.String.format(
                                    "Could not delete old worker logs in '%s': %s",
                                    workerDir, e.getMessage()
                                )
                            )
                        )
                }
            }

            shutdownPool(
                "Worker factory configuration has changed, restarting worker pool...",  /* alwaysLog= */
                true,
                options.getWorkerVerbose()
            )
            workerFactory = newWorkerFactory
            workerFactory.setReporter(if (options.getWorkerVerbose()) env.getReporter() else null)
        }

        val newConfig: WorkerPoolConfig =
            WorkerPoolConfig(
                options.getWorkerMaxInstances(), options.getWorkerMaxMultiplexInstances()
            )

        // If the config changed compared to the last run, we have to create a new pool.
        if (newConfig != config) {
            shutdownPool(
                "Worker pool configuration has changed, restarting worker pool...",  /* alwaysLog= */
                true,
                options.getWorkerVerbose()
            )
        }

        if (workerPool == null) {
            workerPool = WorkerPoolImpl(workerFactory, newConfig)
            config = newConfig
            // If workerPool is restarted then we should recreate metrics.
            WorkerProcessMetricsCollector.Companion.instance().clear()
        }

        // Override the flag value if we can't actually use cgroups so that we at least fallback to ps.
        val useCgroupsOnLinux =
            com.google.devtools.build.lib.util.OS.Companion.getCurrent() == com.google.devtools.build.lib.util.OS.LINUX && options.getUseCgroupsOnLinux()
                    && (if (sandboxOptions == null || !sandboxOptions.useNewCgroupImplementation)
                CgroupsInfo.isSupported()
            else
                VirtualCgroup.getInstance().memory() != null)
        WorkerProcessMetricsCollector.Companion.instance().setUseCgroupsOnLinux(useCgroupsOnLinux)

        // Start collecting after a pool is defined
        workerLifecycleManager = WorkerLifecycleManager(workerPool, options, env.getReporter())
        workerLifecycleManager.setDaemon(true)
        workerLifecycleManager.start()

        // Reset the pool at the beginning of each build.
        workerPool.reset()
    }

    private fun removeStaleTrash(
        workerDir: com.google.devtools.build.lib.vfs.Path?,
        trashBase: com.google.devtools.build.lib.vfs.Path
    ) {
        try {
            // The AsynchronousTreeDeleter relies on a counter for naming directories that will be
            // moved out of the way before being deleted asynchronously.
            // If there is trash on disk from a previous bazel server instance, the dirs will have
            // names not synced with the counter, therefore we may run the risk of moving a directory
            // in this server instance to a path of an existing directory. To solve this we rename
            // the trash directory that was on disk, create a new empty trash directory and delete
            // the old trash via the AsynchronousTreeDeleter. Before deletion the stale trash will be
            // moved to a directory named `0` under MOVED_TRASH_DIR.
            val staleTrash: com.google.devtools.build.lib.vfs.Path? = trashBase.getParentDirectory().getChild(
                STALE_TRASH
            )
            trashBase.renameTo(staleTrash)
            trashBase.createDirectory()
            treeDeleter.deleteTree(staleTrash)
        } catch (e: IOException) {
            env.getReporter()
                .handle(
                    com.google.devtools.build.lib.events.Event.error(
                        java.lang.String.format("Could not trash dir in '%s': %s", workerDir, e.getMessage())
                    )
                )
        }
    }

    public override fun registerSpawnStrategies(
        registryBuilder: SpawnStrategyRegistry.Builder, env: CommandEnvironment
    ) {
        com.google.common.base.Preconditions.checkNotNull<WorkerPool?>(workerPool)
        val localEnvProvider: LocalEnvProvider? = LocalEnvProvider.forCurrentOs(env.getClientEnv())
        val spawnRunner: WorkerSpawnRunner =
            WorkerSpawnRunner(
                env.getExecRoot(),
                workerPool,
                env.getReporter(),
                localEnvProvider,
                env.getBlazeWorkspace().getBinTools(),
                env.getLocalResourceManager(),
                RunfilesTreeUpdater.forCommandEnvironment(env),
                env.getOptions().getOptions(WorkerOptions::class.java),
                WorkerProcessMetricsCollector.Companion.instance(),
                env.getClock()
            )
        val executionOptions: ExecutionOptions =
            com.google.common.base.Preconditions.checkNotNull<T>(
                env.getOptions().getOptions(ExecutionOptions::class.java)
            )
        registryBuilder.registerStrategy(
            WorkerSpawnStrategy(spawnRunner, executionOptions), "worker"
        )
    }

    @com.google.common.eventbus.Subscribe
    @Throws(java.lang.InterruptedException::class)
    fun buildComplete(event: BuildCompleteEvent?) {
        val options: WorkerOptions? = env.getOptions().getOptions(WorkerOptions::class.java)
        if (options != null && options.getWorkerQuitAfterBuild()) {
            shutdownPool(
                "Build completed, shutting down worker pool...",  /* alwaysLog= */
                false,
                options.getWorkerVerbose()
            )
        }
        if (workerLifecycleManager != null) {
            workerLifecycleManager.stopProcessing()
            workerLifecycleManager.interrupt()
            workerLifecycleManager = null
        }
        WorkerProcessMetricsCollector.Companion.instance().clearKilledWorkerProcessMetrics()
    }

    /** Shuts down the worker pool and sets {#code workerPool} to null.  */
    private fun shutdownPool(reason: String, alwaysLog: Boolean, workerVerbose: Boolean) {
        com.google.common.base.Preconditions.checkArgument(!reason.isEmpty())

        if (workerPool != null) {
            if (workerVerbose || alwaysLog) {
                env.getReporter().handle(com.google.devtools.build.lib.events.Event.info(reason))
            }
            workerPool.close()
            workerPool = null
        }
    }

    public override fun afterCommand() {
        this.env = null

        if (this.workerFactory != null) {
            this.workerFactory.setReporter(null)
        }
        WorkerMultiplexerManager.afterCommand()
    }

    val workerPoolConfig: WorkerPoolConfig?
        get() = config

    companion object {
        private const val STALE_TRASH = "_stale_trash"
    }
}
