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
package com.google.devtools.build.lib.sandbox

import com.google.devtools.build.lib.exec.ExecutionOptions

/** This module provides the Sandbox spawn strategy.  */
class SandboxModule : BlazeModule() {
    /** Environment for the running command.  */
    private var env: CommandEnvironment? = null

    /** Path to the location of the sandboxes.  */
    private var sandboxBase: com.google.devtools.build.lib.vfs.Path? = null

    /**
     * Collection of spawn runner instantiated during the executor setup.
     * 
     * 
     * We need this information to clean up the heavy subdirectories of the sandbox base on build
     * completion but to avoid wiping the whole sandbox base itself, which could be problematic across
     * builds.
     */
    private val spawnRunners: MutableSet<SpawnRunner> = HashSet<SpawnRunner>()

    /**
     * Handler to process expensive tree deletions, potentially outside of the critical path.
     * 
     * 
     * Sandboxing creates one separate tree for each action, and this tree is used to run the
     * action commands in. These trees are disjoint for all actions and have unique identifiers.
     * Therefore, there is no need for their deletion (which can be very expensive) to happen in the
     * critical path -- so if the user so wishes, we process those deletions asynchronously.
     */
    private var treeDeleter: TreeDeleter? = null

    /**
     * Whether to remove the sandbox worker directories after a build or not. Useful for debugging to
     * inspect the state of files on failures.
     */
    private var shouldCleanupSandboxBase = false

    public override fun getCommandOptions(commandName: String): Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
        return if (commandName == "build")
            com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
                SandboxOptions::class.java
            )
        else
            com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()
    }

    public override fun beforeCommand(env: CommandEnvironment) {
        // We can't assert that env is null because the Blaze runtime does not guarantee that
        // afterCommand() will be called if the command fails due to, e.g. a syntax error.
        this.env = env
        env.getEventBus().register(this)

        // Don't attempt cleanup unless the executor is initialized.
        shouldCleanupSandboxBase = false
    }

    @Throws(AbruptExitException::class, java.lang.InterruptedException::class)
    public override fun registerSpawnStrategies(
        registryBuilder: SpawnStrategyRegistry.Builder, env: CommandEnvironment?
    ) {
        com.google.common.base.Preconditions.checkNotNull<Any?>(env, "env not initialized; was beforeCommand called?")
        try {
            setup(env, registryBuilder)
        } catch (e: IOException) {
            throw AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(String.format("Failed to initialize sandbox: %s", e.message))
                        .setSandbox(
                            Sandbox.newBuilder().setCode(Sandbox.Code.INITIALIZATION_FAILURE).build()
                        )
                        .build()
                ),
                e
            )
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun setup(cmdEnv: CommandEnvironment, builder: SpawnStrategyRegistry.Builder) {
        val options: SandboxOptions = com.google.common.base.Preconditions.checkNotNull<T>(
            env.getOptions().getOptions(SandboxOptions::class.java)
        )
        sandboxBase = computeSandboxBase(options, env)
        val trashBase: com.google.devtools.build.lib.vfs.Path =
            sandboxBase.getRelative(AsynchronousTreeDeleter.Companion.MOVED_TRASH_DIR)

        // Do not remove the sandbox base when --sandbox_debug was specified so that people can check
        // out the contents of the generated sandbox directories.
        shouldCleanupSandboxBase = !options.getSandboxDebug()

        // If there happens to be any live tree deleter from a previous build and it's different than
        // the one we want now, leave it alone (i.e. don't attempt to wait for pending deletions). Its
        // deletions shouldn't overlap any new directories we create during this build (because the
        // identifiers in the subdirectories will be different).
        if (options.getAsyncTreeDeleteIdleThreads() == 0) {
            if (treeDeleter !is SynchronousTreeDeleter) {
                treeDeleter = SynchronousTreeDeleter()
            }
        } else {
            if (treeDeleter !is AsynchronousTreeDeleter || treeDeleter.getTrashBase() != trashBase) {
                if (treeDeleter != null) {
                    treeDeleter.shutdown()
                }
                treeDeleter = AsynchronousTreeDeleter(trashBase)
                firstBuild = true
            }
        }
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("SandboxStash.initialize").use { c ->
            SandboxStash.Companion.initialize(env.getWorkspaceName(), sandboxBase, options, treeDeleter)
        }
        // SpawnExecutionPolicy#getId returns unique base directories for each sandboxed action during
        // the life of a Bazel server instance so we don't need to worry about stale directories from
        // previous builds. However, on the very first build of an instance of the server, we must
        // wipe old contents to avoid reusing stale directories.
        if (firstBuild && sandboxBase.exists()) {
            try {
                com.google.devtools.build.lib.profiler.Profiler.instance().profile("clean sandbox on first build")
                    .use { c ->
                        if (trashBase.exists()) {
                            // Delete stale trash from a previous server instance.
                            val staleTrash: com.google.devtools.build.lib.vfs.Path = getStaleTrashDir(trashBase)
                            trashBase.renameTo(staleTrash)
                            trashBase.createDirectory()
                            treeDeleter.deleteTree(staleTrash)
                        } else {
                            trashBase.createDirectory()
                        }
                        // We can delete other dirs asynchronously (if the flag is on).
                        for (dirent in sandboxBase.readdir(Symlinks.NOFOLLOW)) {
                            val childPath: com.google.devtools.build.lib.vfs.Path =
                                sandboxBase.getChild(dirent.getName())
                            if (childPath.getBaseName() == AsynchronousTreeDeleter.Companion.MOVED_TRASH_DIR) {
                                continue
                            }
                            if (childPath.getBaseName() == SandboxHelpers.INACCESSIBLE_HELPER_DIR) {
                                childPath.deleteTree()
                            } else if (dirent.getType() == com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY) {
                                treeDeleter.deleteTree(childPath)
                            } else {
                                childPath.delete()
                            }
                        }
                    }
            } catch (e: IOException) {
                // We have observed asynchronous deletion failing when running Bazel under Docker, see
                // #21719. Different RUN commands with `bazel build` will write to different layers in the
                // docker image. The overlay filesystem is different and the renaming of the directories
                // that we need to do for asynchronous deletion will fail. When that happens we fall back to
                // synchronous deletion here.
                sandboxBase.deleteTree()
            }
        }
        firstBuild = false
        sandboxBase.createDirectoryAndParents()
        trashBase.createDirectory()

        val windowsSandboxPath: PathFragment = PathFragment.create(options.getWindowsSandboxPath())
        val windowsSandboxSupported: Boolean
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("shouldUseWindowsSandbox").use { c ->
            windowsSandboxSupported =
                shouldUseWindowsSandbox(
                    options.getUseWindowsSandbox(), windowsSandboxPath, cmdEnv.getClientEnv()
                )
        }
        val timeoutKillDelay: java.time.Duration? =
            cmdEnv
                .getOptions()
                .getOptions(LocalExecutionOptions::class.java)
                .getLocalSigkillGraceSecondsDuration()

        val processWrapperSupported: Boolean = ProcessWrapperSandboxedSpawnRunner.Companion.isSupported(cmdEnv)
        val linuxSandboxSupported: Boolean = LinuxSandboxedSpawnRunner.Companion.isSupported(cmdEnv)
        val darwinSandboxSupported: Boolean = DarwinSandboxedSpawnRunner.Companion.isSupported(cmdEnv)

        val executionOptions: ExecutionOptions =
            com.google.common.base.Preconditions.checkNotNull<T>(
                cmdEnv.getOptions().getOptions(ExecutionOptions::class.java)
            )
        // This works on most platforms, but isn't the best choice, so we put it first and let later
        // platform-specific sandboxing strategies become the default.
        if (processWrapperSupported) {
            val spawnRunner: SpawnRunner =
                ProcessWrapperSandboxedSpawnRunner(cmdEnv, sandboxBase, treeDeleter)
            spawnRunners.add(spawnRunner)
            builder.registerStrategy(
                ProcessWrapperSandboxedStrategy(spawnRunner, executionOptions),
                "sandboxed",
                "processwrapper-sandbox"
            )
        }

        if (options.getEnableDockerSandbox()) {
            // This strategy uses Docker to execute spawns. It should work on all platforms that support
            // Docker.
            val pathToDocker: com.google.devtools.build.lib.vfs.Path? = getPathToDockerClient(cmdEnv)
            // DockerSandboxedSpawnRunner.isSupported is expensive! It runs docker as a subprocess, and
            // docker hangs sometimes.
            if (pathToDocker != null && DockerSandboxedSpawnRunner.Companion.isSupported(cmdEnv, pathToDocker)) {
                val defaultImage: String? = options.getDockerImage()
                val useCustomizedImages: Boolean = options.getDockerUseCustomizedImages()
                val spawnRunner: SpawnRunner =
                    DockerSandboxedSpawnRunner(
                        cmdEnv, pathToDocker, sandboxBase, defaultImage, useCustomizedImages, treeDeleter
                    )
                spawnRunners.add(spawnRunner)
                builder.registerStrategy(
                    DockerSandboxedStrategy(spawnRunner, executionOptions), "docker"
                )
            }
        } else if (options.getDockerVerbose()) {
            cmdEnv
                .getReporter()
                .handle(
                    com.google.devtools.build.lib.events.Event.info(
                        "Docker sandboxing disabled. Use the '--experimental_enable_docker_sandbox'"
                                + " command line option to enable it"
                    )
                )
        }

        // This is the preferred sandboxing strategy on Linux.
        if (linuxSandboxSupported) {
            val spawnRunner: SpawnRunner =
                LinuxSandboxedStrategy.Companion.create(
                    cmdEnv, sandboxBase, timeoutKillDelay, treeDeleter, options
                )
            spawnRunners.add(spawnRunner)
            builder.registerStrategy(
                LinuxSandboxedStrategy(spawnRunner, executionOptions), "sandboxed", "linux-sandbox"
            )
        }

        // This is the preferred sandboxing strategy on macOS.
        if (darwinSandboxSupported) {
            val spawnRunner: SpawnRunner = DarwinSandboxedSpawnRunner(cmdEnv, sandboxBase, treeDeleter)
            spawnRunners.add(spawnRunner)
            builder.registerStrategy(
                DarwinSandboxedStrategy(spawnRunner, executionOptions),
                "sandboxed",
                "darwin-sandbox"
            )
        }

        if (windowsSandboxSupported) {
            val spawnRunner: SpawnRunner =
                WindowsSandboxedSpawnRunner(cmdEnv, timeoutKillDelay, windowsSandboxPath)
            spawnRunners.add(spawnRunner)
            builder.registerStrategy(
                WindowsSandboxedStrategy(spawnRunner, executionOptions),
                "sandboxed",
                "windows-sandbox"
            )
        }

        if (processWrapperSupported
            || linuxSandboxSupported
            || darwinSandboxSupported
            || windowsSandboxSupported
        ) {
            // This makes the "sandboxed" strategy the default Spawn strategy, unless it is
            // overridden by a later BlazeModule.
            builder.setDefaultStrategies(com.google.common.collect.ImmutableList.of<E?>("sandboxed"))
        }
    }

    @com.google.common.eventbus.Subscribe
    fun cleanStarting(@Suppress("unused") event: CleanStartingEvent?) {
        if (sandboxBase != null) {
            SandboxStash.Companion.clean(treeDeleter, sandboxBase)
        }
    }

    public override fun afterCommand() {
        com.google.common.base.Preconditions.checkNotNull<Any?>(env, "env not initialized; was beforeCommand called?")

        val options: SandboxOptions? = env.getOptions().getOptions(SandboxOptions::class.java)
        val asyncTreeDeleteThreads = if (options != null) options.getAsyncTreeDeleteIdleThreads() else 0

        // If asynchronous deletions were requested, they may still be ongoing so let them be: trying
        // to delete the base tree synchronously could fail as we can race with those other deletions,
        // and scheduling an asynchronous deletion could race with future builds.
        if (asyncTreeDeleteThreads > 0 && treeDeleter is AsynchronousTreeDeleter) {
            treeDeleter.setThreads(asyncTreeDeleteThreads)
        }

        // `treeDeleter` might not be an AsynchronousTreeDeleter if the user changed the option but
        // then interrupted the build before the start of the execution phase. But that's OK, there
        // will be nothing new to delete. See #13240.
        if (shouldCleanupSandboxBase) {
            try {
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(
                    sandboxBase,
                    "shouldCleanupSandboxBase implies sandboxBase has been set"
                )
                for (spawnRunner in spawnRunners) {
                    spawnRunner.cleanupSandboxBase(sandboxBase, treeDeleter)
                    sandboxBase.getChild(spawnRunner.name).delete()
                }
            } catch (e: IOException) {
                env.getReporter()
                    .handle(com.google.devtools.build.lib.events.Event.warn("Failed to delete contents of sandbox " + sandboxBase + ": " + e))
            }
            shouldCleanupSandboxBase = false

            checkSandboxBaseTopOnlyContainsPersistentDirs(sandboxBase)
            // We intentionally keep sandboxBase around, without resetting it to null, in case we have
            // asynchronous deletions going on. In that case, we'd still want to retry this during
            // shutdown.
        }

        spawnRunners.clear()

        env.getEventBus().unregister(this)
        env = null
    }

    private fun commonShutdown() {
        // Try to clean up as much garbage as possible, if there happens to be any. This will delay
        // server termination but it's the nice thing to do. If the user gets impatient, they can always
        // kill us again.
        if (treeDeleter != null) {
            try {
                treeDeleter.shutdown()
            } finally {
                treeDeleter = null // Avoid potential reexecution if we crash.
            }
        }

        SandboxStash.Companion.shutdown()
    }

    public override fun blazeShutdown() {
        commonShutdown()
    }

    public override fun blazeShutdownOnCrash(exitCode: DetailedExitCode?) {
        commonShutdown()
    }

    private fun getStaleTrashDir(trashBase: com.google.devtools.build.lib.vfs.Path): com.google.devtools.build.lib.vfs.Path {
        var i = 0
        while (trashBase.getParentDirectory().getChild("stale-trash-" + i++).exists()) {
        }
        return trashBase.getParentDirectory().getChild("stale-trash-" + --i)
    }

    companion object {
        private const val MAC_INDEX_FILE = ".DS_Store"

        private val SANDBOX_BASE_PERSISTENT_DIRS: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>(
                MAC_INDEX_FILE,
                SandboxStash.Companion.SANDBOX_STASH_BASE,
                SandboxStash.Companion.TEMPORARY_SANDBOX_STASH_BASE,
                AsynchronousTreeDeleter.Companion.MOVED_TRASH_DIR
            )

        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** Tracks whether we are issuing the very first build within this Bazel server instance.  */
        private var firstBuild = true

        /** Computes the path to the sandbox base tree for the given running command.  */
        @Throws(IOException::class)
        private fun computeSandboxBase(
            options: SandboxOptions,
            env: CommandEnvironment
        ): com.google.devtools.build.lib.vfs.Path {
            if (options.getSandboxBase().isEmpty()) {
                return env.getOutputBase().getRelative("sandbox")
            } else {
                val dirName: String? =
                    java.lang.String.format(
                        "%s-sandbox.%s",
                        env.getRuntime().productName,
                        Fingerprint.getHexDigest(env.getOutputBase().toString())
                    )
                val fileSystem: com.google.devtools.build.lib.vfs.FileSystem = env.getRuntime().getFileSystem()
                if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.DARWIN) {
                    // Don't resolve symlinks on macOS: See https://github.com/bazelbuild/bazel/issues/13766
                    return fileSystem.getPath(options.getSandboxBase()).getRelative(dirName)
                }
                val resolvedSandboxBase: com.google.devtools.build.lib.vfs.Path =
                    fileSystem.getPath(options.getSandboxBase()).resolveSymbolicLinks()
                return resolvedSandboxBase.getRelative(dirName)
            }
        }

        /**
         * Returns true if windows-sandbox should be used for this build.
         * 
         * 
         * Returns true if requested in ["auto", "yes"] and binary is valid. Throws an error if state
         * is "yes" and binary is not valid.
         * 
         * @param requested whether windows-sandbox use was requested or not
         * @param binary path of the windows-sandbox binary to use, can be absolute or relative path
         * @return true if windows-sandbox can and should be used; false otherwise
         * @throws IOException if there are problems trying to determine the status of windows-sandbox
         */
        @Throws(IOException::class)
        private fun shouldUseWindowsSandbox(
            requested: com.google.devtools.common.options.TriState,
            binary: PathFragment,
            clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?
        ): Boolean {
            return when (requested) {
                com.google.devtools.common.options.TriState.AUTO -> WindowsSandboxUtil.isAvailable(binary, clientEnv)
                com.google.devtools.common.options.TriState.NO -> false
                com.google.devtools.common.options.TriState.YES -> {
                    if (!WindowsSandboxUtil.isAvailable(binary, clientEnv)) {
                        throw IOException(
                            ("windows-sandbox explicitly requested but \""
                                    + binary
                                    + "\" could not be found or is not valid")
                        )
                    }
                    true
                }
            }
        }

        private fun getPathToDockerClient(cmdEnv: CommandEnvironment): com.google.devtools.build.lib.vfs.Path? {
            val path: String = cmdEnv.getClientEnv().getOrDefault("PATH", "")

            // TODO(philwo): Does this return the correct result if one of the elements intentionally ends
            // in white space?
            val pathSplitter: com.google.common.base.Splitter =
                com.google.common.base.Splitter.on(if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) ';' else ':')
                    .trimResults().omitEmptyStrings()

            val fs: com.google.devtools.build.lib.vfs.FileSystem = cmdEnv.getRuntime().getFileSystem()

            for (pathElement in pathSplitter.split(path)) {
                // Sometimes the PATH contains the non-absolute entry "." - this resolves it against the
                // current working directory.
                var pathElement: String = pathElement
                pathElement = java.io.File(pathElement).getAbsolutePath()
                try {
                    for (dentry in fs.getPath(pathElement).getDirectoryEntries()) {
                        if (dentry.getBaseName().replace(".exe", "") == "docker") {
                            return dentry
                        }
                    }
                } catch (e: IOException) {
                    // Intentionally ignored.
                }
            }

            return null
        }

        /**
         * If there is anything other than SANDBOX_BASE_PERSISTENT_DIRS in sandboxBase when we hit this
         * precondition then there is a programming error somewhere (or I made a wrong assumption that
         * wasn't caught by any of our tests).
         */
        private fun checkSandboxBaseTopOnlyContainsPersistentDirs(sandboxBase: com.google.devtools.build.lib.vfs.Path) {
            try {
                val directoryEntries: MutableList<String?> =
                    sandboxBase.getDirectoryEntries().stream()
                        .map<String?> { obj: com.google.devtools.build.lib.vfs.Path? -> obj.getBaseName() }
                        .collect(Collectors.toList())
                // If sandbox initialization failed in-between creating the inaccessible dir/file and adding
                // the Linux sandboxing strategy to spawnRunners, then the sandbox base will be in a bad
                // state. We check for that here and clean up.
                if (directoryEntries.contains(SandboxHelpers.INACCESSIBLE_HELPER_DIR)) {
                    val inaccessibleHelperDir: com.google.devtools.build.lib.vfs.Path =
                        sandboxBase.getChild(SandboxHelpers.INACCESSIBLE_HELPER_DIR)
                    inaccessibleHelperDir.chmod(448)
                    directoryEntries.remove(SandboxHelpers.INACCESSIBLE_HELPER_DIR)
                    inaccessibleHelperDir.deleteTree()
                }
                if (directoryEntries.contains(SandboxHelpers.INACCESSIBLE_HELPER_FILE)) {
                    val inaccessibleHelperFile: com.google.devtools.build.lib.vfs.Path =
                        sandboxBase.getChild(SandboxHelpers.INACCESSIBLE_HELPER_FILE)
                    directoryEntries.remove(SandboxHelpers.INACCESSIBLE_HELPER_FILE)
                    inaccessibleHelperFile.delete()
                }

                if (!SANDBOX_BASE_PERSISTENT_DIRS.containsAll(directoryEntries)) {
                    val message: java.lang.StringBuilder =
                        java.lang.StringBuilder(
                            "Found unexpected entries in sandbox base. Please report this in"
                                    + " https://github.com/bazelbuild/bazel/issues."
                        )
                    message.append(" The entries are: ")
                    com.google.common.base.Joiner.on(", ").appendTo(message, directoryEntries)
                    throw java.lang.IllegalStateException(message.toString())
                }
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log("Failed to clean up sandbox base %s", sandboxBase)
            }
        }
    }
}
