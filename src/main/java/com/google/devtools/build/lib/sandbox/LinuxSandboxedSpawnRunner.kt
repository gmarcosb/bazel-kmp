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
package com.google.devtools.build.lib.sandbox

import com.google.devtools.build.lib.actions.ActionInput

/** Spawn runner that uses linux sandboxing APIs to execute a local subprocess.  */
internal class LinuxSandboxedSpawnRunner(
    cmdEnv: CommandEnvironment,
    sandboxBase: com.google.devtools.build.lib.vfs.Path,
    inaccessibleHelperFile: com.google.devtools.build.lib.vfs.Path?,
    inaccessibleHelperDir: com.google.devtools.build.lib.vfs.Path?,
    timeoutKillDelay: java.time.Duration?,
    treeDeleter: TreeDeleter?
) : AbstractSandboxSpawnRunner(cmdEnv) {
    private val fileSystem: com.google.devtools.build.lib.vfs.FileSystem
    private val execRoot: com.google.devtools.build.lib.vfs.Path
    private val allowNetwork: Boolean
    private val linuxSandbox: com.google.devtools.build.lib.vfs.Path?
    private val sandboxBase: com.google.devtools.build.lib.vfs.Path
    private val inaccessibleHelperFile: com.google.devtools.build.lib.vfs.Path?
    private val inaccessibleHelperDir: com.google.devtools.build.lib.vfs.Path?
    private val localEnvProvider: LocalEnvProvider
    private val timeoutKillDelay: java.time.Duration?
    private val treeDeleter: TreeDeleter?
    private val slashTmp: com.google.devtools.build.lib.vfs.Path
    private val knownPathsToMountUnderHermeticTmp: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?>
    private val cgroupsDir: String? = null
    private val cgroupFactory: VirtualCgroupFactory?

    /**
     * Creates a sandboxed spawn runner that uses the `linux-sandbox` tool.
     * 
     * @param cmdEnv the command environment to use
     * @param sandboxBase path to the sandbox base directory
     * @param inaccessibleHelperFile path to a file that is (already) inaccessible
     * @param inaccessibleHelperDir path to a directory that is (already) inaccessible
     * @param timeoutKillDelay an additional grace period before killing timing out commands
     */
    init {
        val sandboxOptions: SandboxOptions? = cmdEnv.getOptions().getOptions(SandboxOptions::class.java)
        this.cgroupFactory =
            if (sandboxOptions == null || !sandboxOptions.getUseNewCgroupImplementation())
                null
            else
                VirtualCgroupFactory(
                    "sandbox_",
                    VirtualCgroup.Companion.getInstance(),
                    getSandboxOptions().getLimitsMap(),  /* alwaysCreate= */
                    false
                )
        this.fileSystem = cmdEnv.getRuntime().getFileSystem()
        this.execRoot = cmdEnv.getExecRoot()
        this.allowNetwork = SandboxHelpers.shouldAllowNetwork(cmdEnv.getOptions())
        this.linuxSandbox = LinuxSandboxUtil.getLinuxSandbox(cmdEnv.getBlazeWorkspace())
        this.sandboxBase = sandboxBase
        this.inaccessibleHelperFile = inaccessibleHelperFile
        this.inaccessibleHelperDir = inaccessibleHelperDir
        this.timeoutKillDelay = timeoutKillDelay
        this.localEnvProvider = PosixLocalEnvProvider(cmdEnv.getClientEnv())
        this.treeDeleter = treeDeleter
        this.slashTmp = cmdEnv.getRuntime().getFileSystem().getPath("/tmp")
        this.knownPathsToMountUnderHermeticTmp = collectPathsToMountUnderHermeticTmp(cmdEnv)
    }

    private fun collectPathsToMountUnderHermeticTmp(cmdEnv: CommandEnvironment): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?> {
        // If any path managed or tracked by Bazel is under /tmp, it needs to be explicitly mounted
        // into the sandbox when using hermetic /tmp. We attempt to collect an over-approximation of
        // these paths, as the main goal of hermetic /tmp is to avoid inheriting any direct
        // or well-known children of /tmp from the host.
        // TODO(bazel-team): Review all flags whose path may have to be considered here.
        return java.util.stream.Stream.concat<T?>(
            java.util.stream.Stream.of<T?>(sandboxBase, cmdEnv.getOutputBase()),
            cmdEnv.getPackageLocator().getPathEntries().stream().map({ obj: Root? -> obj.asPath() })
        )
            .filter { p: T? -> p.startsWith(slashTmp) }  // For any path /tmp/dir1/dir2 we encounter, we instead mount /tmp/dir1 (first two
            // path segments). This is necessary to gracefully handle an edge case:
            // - A workspace contains a subdirectory (e.g. examples) that is itself a workspace.
            // - The child workspace brings in the parent workspace as a local_repository with
            //   an up-level reference.
            // - The parent workspace is checked out under /tmp.
            // In this scenario, the parent workspace's external source root points to the parent
            // workspace's source directory under /tmp, but this directory is neither under the
            // output base nor on the package path. While it would be possible to track the
            // external roots of all inputs and mount their entire symlink chain, this would be
            // very invasive to do in the face of resolved symlink artifacts (and impossible with
            // unresolved symlinks).
            // Instead, by mounting the direct children of /tmp that are parents of the source
            // roots, we attempt to cover all reasonable cases in which repositories symlink
            // paths relative to themselves and workspaces are checked out into subdirectories of
            // /tmp. All explicit references to paths under /tmp must be handled by the user via
            // --sandbox_add_mount_pair.
            .map<Any?> { p: T? ->
                p.getFileSystem()
                    .getPath(
                        p.asFragment().subFragment(0, java.lang.Math.min(2, p.asFragment().segmentCount()))
                    )
            }
            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
    }

    private fun useHermeticTmp(): Boolean {
        if (getSandboxOptions().getUseHermetic()) {
            // The hermetic sandbox is, well, already hermetic. Also, it creates an empty /tmp by default
            // so nothing needs to be done to achieve a /tmp that is also hermetic.
            return false
        }

        val tmpExplicitlyBindMounted: Boolean =
            getSandboxOptions().getSandboxAdditionalMounts().stream()
                .anyMatch { e: MutableMap.MutableEntry<String?, String?>? -> e!!.key == "/tmp" }
        if (tmpExplicitlyBindMounted) {
            // An explicit mount on /tmp is an explicit way to make it non-hermetic.
            return false
        }

        if (knownPathsToMountUnderHermeticTmp.contains(slashTmp)) {
            // /tmp as a package path entry or output base seems very unlikely to work, but the bind
            // mounting logic is not prepared for it and we don't want to crash, so just disable hermetic
            // tmp in this case.
            return false
        }

        if (getSandboxOptions().getSandboxTmpfsPath().contains(slashTmp.asFragment())) {
            // A tmpfs path under /tmp is as hermetic as "hermetic /tmp".
            return false
        }

        return true
    }

    @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
    public override fun prepareSpawn(spawn: Spawn, context: SpawnExecutionContext): SandboxedSpawn {
        // Each invocation of "exec" gets its own sandbox base.
        // Note that the value returned by context.getId() is only unique inside one given SpawnRunner,
        // so we have to prefix our name to turn it into a globally unique value.

        val sandboxPath: com.google.devtools.build.lib.vfs.Path =
            sandboxBase.getRelative(this.name).getRelative(context.id.toString())

        // b/64689608: The execroot of the sandboxed process must end with the workspace name, just like
        // the normal execroot does.
        val workspaceName: String? = execRoot.getBaseName()
        val sandboxExecRoot: com.google.devtools.build.lib.vfs.Path =
            sandboxPath.getRelative("execroot").getRelative(workspaceName)
        sandboxExecRoot.createDirectoryAndParents()

        val inputs: SandboxInputs =
            SandboxHelpers.processInputFiles(
                context.getInputMapping(PathFragment.EMPTY_FRAGMENT,  /* willAccessRepeatedly= */true),
                execRoot
            )

        val environment: com.google.common.collect.ImmutableMap<String?, String?>? =
            localEnvProvider.rewriteLocalEnv(spawn.getEnvironment(), binTools, "/tmp")
        val writableDirs: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?> =
            getWritableDirs(sandboxExecRoot, environment)

        var sandboxTmp: com.google.devtools.build.lib.vfs.Path? = null
        var pathsUnderTmpToMount: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?> =
            com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.vfs.Path?>()
        if (useHermeticTmp()) {
            // Special paths under /tmp are treated exactly like a user mount under /tmp to ensure that
            // they are visible at the same path after mounting the hermetic tmp.
            pathsUnderTmpToMount = knownPathsToMountUnderHermeticTmp

            // The initially empty directory that will be mounted as /tmp in the sandbox.
            sandboxTmp = sandboxPath.getRelative("_hermetic_tmp")
            sandboxTmp.createDirectoryAndParents()

            for (pathFragment in com.google.common.collect.Iterables.concat<PathFragment?>(
                getSandboxOptions().getSandboxTmpfsPath(),
                com.google.common.collect.Iterables.transform<com.google.devtools.build.lib.vfs.Path?, PathFragment?>(
                    writableDirs,
                    com.google.common.base.Function { obj: com.google.devtools.build.lib.vfs.Path? -> obj.asFragment() })
            )) {
                val path: com.google.devtools.build.lib.vfs.Path = fileSystem.getPath(pathFragment)
                if (path.startsWith(slashTmp)) {
                    // tmpfs mount points and writable dirs must exist, which is usually the user's
                    // responsibility. But if the user requests a path mount under /tmp, we have to create it
                    // under the sandbox tmp directory.
                    sandboxTmp.getRelative(path.relativeTo(slashTmp)).createDirectoryAndParents()
                }
            }
        }

        val outputs: SandboxOutputs = SandboxHelpers.getOutputs(spawn)
        val timeout: java.time.Duration = context.timeout
        val sandboxOptions: SandboxOptions = getSandboxOptions()

        val createNetworkNamespace =
            !(allowNetwork
                    || Spawns.requiresNetwork(spawn, sandboxOptions.getDefaultSandboxAllowNetwork()))
        val commandLineBuilder: LinuxSandboxCommandLineBuilder =
            LinuxSandboxCommandLineBuilder.Companion.commandLineBuilder(linuxSandbox)
                .addExecutionInfo(spawn.getExecutionInfo())
                .setWritableFilesAndDirectories(writableDirs)
                .setTmpfsDirectories(com.google.common.collect.ImmutableSet.copyOf<PathFragment?>(getSandboxOptions().getSandboxTmpfsPath()))
                .setBindMounts(
                    prepareAndGetBindMounts(sandboxExecRoot, sandboxTmp, pathsUnderTmpToMount)
                )
                .setUseFakeHostname(getSandboxOptions().getSandboxFakeHostname())
                .setEnablePseudoterminal(getSandboxOptions().getSandboxExplicitPseudoterminal())
                .setCreateNetworkNamespace(if (createNetworkNamespace) this.networkNamespace else NetworkNamespace.NO_NETNS)
                .setKillDelay(timeoutKillDelay)

        var sandboxDebugPath: com.google.devtools.build.lib.vfs.Path? = null
        if (sandboxOptions.getSandboxDebug()) {
            sandboxDebugPath = sandboxPath.getRelative("debug.out")
            commandLineBuilder.setSandboxDebugPath(sandboxDebugPath.getPathString())
        }

        if (cgroupFactory != null) {
            var spawnResourceLimits: com.google.common.collect.ImmutableMap<String?, Double?>? =
                com.google.common.collect.ImmutableMap.of<String?, Double?>()
            if (sandboxOptions.getEnforceResources().matcher().test(spawn.getMnemonic())) {
                spawnResourceLimits = spawn.getLocalResources().getResources()
            }
            val cgroup: VirtualCgroup = cgroupFactory.create(context.id, spawnResourceLimits)
            commandLineBuilder.setCgroupsDirs(cgroup.paths())
        } else if (sandboxOptions.getMemoryLimitMb() > 0) {
            // We put the sandbox inside a unique subdirectory using the context's ID. This ID is
            // unique per spawn run by this spawn runner.
            val sandboxCgroup: CgroupsInfo =
                CgroupsInfo.Companion.getBlazeSpawnsCgroup()
                    .createIndividualSpawnCgroup(
                        "sandbox_" + context.id, sandboxOptions.getMemoryLimitMb()
                    )
            if (sandboxCgroup.exists()) {
                commandLineBuilder.setCgroupsDirs(
                    com.google.common.collect.ImmutableSet.of<java.nio.file.Path?>(
                        sandboxCgroup.getCgroupDir().toPath()
                    )
                )
            }
        }

        if (!timeout.isZero()) {
            commandLineBuilder.setTimeout(timeout)
        }
        if (spawn.getExecutionInfo().containsKey(ExecutionRequirements.REQUIRES_FAKEROOT)) {
            commandLineBuilder.setUseFakeRoot(true)
        } else if (sandboxOptions.getSandboxFakeUsername()) {
            commandLineBuilder.setUseFakeUsername(true)
        }
        val statisticsPath: com.google.devtools.build.lib.vfs.Path = sandboxPath.getRelative("stats.out")
        commandLineBuilder.setStatisticsPath(statisticsPath)
        if (sandboxOptions.getUseHermetic()) {
            commandLineBuilder.setHermeticSandboxPath(sandboxPath)
            return HardlinkedSandboxedSpawn(
                sandboxPath,
                sandboxExecRoot,
                commandLineBuilder.buildForCommand(spawn.getArguments()),
                environment,
                inputs,
                outputs,
                writableDirs,
                treeDeleter,
                sandboxDebugPath,
                statisticsPath,
                sandboxOptions.getSandboxDebug(),
                makeInteractiveDebugArguments(commandLineBuilder, sandboxOptions),
                spawn.getMnemonic()
            )
        } else {
            return SymlinkedSandboxedSpawn(
                sandboxPath,
                sandboxExecRoot,
                commandLineBuilder.buildForCommand(spawn.getArguments()),
                environment,
                inputs,
                outputs,
                writableDirs,
                treeDeleter,
                sandboxDebugPath,
                statisticsPath,
                makeInteractiveDebugArguments(commandLineBuilder, sandboxOptions),
                spawn.getMnemonic(),
                spawn.getTargetLabel()
            )
        }
    }

    val name: String
        get() = "linux-sandbox"

    @Throws(IOException::class)
    public override fun getWritableDirs(
        sandboxExecRoot: com.google.devtools.build.lib.vfs.Path,
        env: MutableMap<String?, String?>?
    ): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?> {
        val writableDirs: MutableSet<com.google.devtools.build.lib.vfs.Path?> =
            TreeSet<com.google.devtools.build.lib.vfs.Path?>(super.getWritableDirs(sandboxExecRoot, env))
        val fs: com.google.devtools.build.lib.vfs.FileSystem = sandboxExecRoot.getFileSystem()
        val devShm: com.google.devtools.build.lib.vfs.Path = fs.getPath("/dev/shm")
        if (devShm.exists()) {
            writableDirs.add(devShm.resolveSymbolicLinks())
        }
        writableDirs.add(fs.getPath("/tmp"))
        return com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.vfs.Path?>(writableDirs)
    }

    @Throws(UserExecException::class, IOException::class)
    private fun prepareAndGetBindMounts(
        sandboxExecRoot: com.google.devtools.build.lib.vfs.Path,
        sandboxTmp: com.google.devtools.build.lib.vfs.Path?,
        pathsUnderTmpToMount: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?>
    ): com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?> {
        val userBindMounts: SortedMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?> =
            TreeMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?>()
        SandboxHelpers.mountAdditionalPaths(
            com.google.common.collect.ImmutableMap.builder<String?, String?>()
                .putAll(getSandboxOptions().getSandboxAdditionalMounts())
                .buildKeepingLast(),
            sandboxExecRoot,
            userBindMounts
        )

        val inaccessiblePaths: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path> =
            getInaccessiblePaths()
        com.google.common.base.Preconditions.checkState(
            inaccessiblePaths.isEmpty()
                    || (inaccessibleHelperDir != null && inaccessibleHelperFile != null)
        )
        for (inaccessiblePath in inaccessiblePaths) {
            if (!inaccessiblePath.exists()) {
                // No need to make non-existent paths inaccessible (this would make the bind mount fail).
                continue
            }

            if (inaccessiblePath.isDirectory(Symlinks.NOFOLLOW)) {
                userBindMounts.put(inaccessiblePath, inaccessibleHelperDir)
            } else {
                userBindMounts.put(inaccessiblePath, inaccessibleHelperFile)
            }
        }

        LinuxSandboxUtil.validateBindMounts(userBindMounts)

        if (sandboxTmp == null) {
            return com.google.common.collect.ImmutableMap.copyOf<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?>(
                userBindMounts
            )
        }

        val bindMounts: SortedMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?> =
            TreeMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?>()
        for (entry in com.google.common.collect.Iterables.concat<MutableMap.MutableEntry<com.google.devtools.build.lib.vfs.Path, com.google.devtools.build.lib.vfs.Path?>>(
            userBindMounts.entries,
            com.google.common.collect.Maps.asMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?>(
                pathsUnderTmpToMount,
                com.google.common.base.Function { p: com.google.devtools.build.lib.vfs.Path? -> p }).entries
        )) {
            var mountPoint: com.google.devtools.build.lib.vfs.Path = entry.key
            val content: com.google.devtools.build.lib.vfs.Path? = entry.value
            if (mountPoint.startsWith(slashTmp)) {
                // sandboxTmp is null if /tmp is an explicit mount point.
                if (mountPoint == slashTmp) {
                    throw IOException(
                        "Cannot mount /tmp explicitly with hermetic /tmp. Please file a bug at"
                                + " https://github.com/bazelbuild/bazel/issues/new/choose."
                    )
                }
                // We need to rewrite the mount point to be under the sandbox tmp directory, which will be
                // mounted onto /tmp as the final mount.
                mountPoint = sandboxTmp.getRelative(mountPoint.relativeTo(slashTmp))
                mountPoint.createDirectoryAndParents()
            }
            bindMounts.put(mountPoint, content)
        }

        // Mount $SANDBOX/_hermetic_tmp at /tmp as the final mount.
        return com.google.common.collect.ImmutableMap.builder<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?>()
            .putAll(bindMounts)
            .put(slashTmp, sandboxTmp)
            .buildOrThrow()
    }

    @Throws(IOException::class)
    override fun verifyPostCondition(
        originalSpawn: Spawn?, sandbox: SandboxedSpawn?, context: SpawnExecutionContext
    ) {
        if (getSandboxOptions().getUseHermetic()) {
            checkForConcurrentModifications(context)
        }
        // We cannot leave the cgroups around and delete them only when we delete the sandboxes
        // because linux has a hard limit of 65535 memory controllers.
        // Ref.
        // https://github.com/torvalds/linux/blob/58d4e450a490d5f02183f6834c12550ba26d3b47/include/linux/memcontrol.h#L69
        if (cgroupFactory != null) {
            cgroupFactory.remove(context.id)
        }
    }

    @Throws(IOException::class)
    private fun checkForConcurrentModifications(context: SpawnExecutionContext) {
        for (input in context
            .getInputMapping(PathFragment.EMPTY_FRAGMENT,  /* willAccessRepeatedly= */true)
            .values()) {
            if (input is VirtualActionInput) {
                // Virtual inputs are not existing in file system and can't be tampered with via sandbox. No
                // need to check them.
                continue
            }

            val metadata: FileArtifactValue? = context.inputMetadataProvider.getInputMetadata(input)
            if (metadata == null) {
                // This can happen if we are executing a spawn in an action that has multiple spawns and
                // the output of one is the input of another. In this case, we assume that no one modifies
                // an output of the first spawn before the action is completed (which requires the
                // the completion of the second spawn, which happens after this point is reached in the
                // code)
                continue
            }
            if (!metadata.getType().isFile()) {
                // The hermetic sandbox creates hardlinks from files inside sandbox to files outside
                // sandbox. The content of the files outside the sandbox could have been tampered with via
                // the hardlinks. Therefore files are checked for modifications. On the other hand,
                // directories and unresolved symlinks are not represented as hardlinks, and don't have to
                // be checked.
                continue
            }

            val path: com.google.devtools.build.lib.vfs.Path = execRoot.getRelative(input.getExecPath())
            if (wasModifiedSinceDigest(metadata.getContentsProxy(), path)) {
                throw IOException("input dependency " + path + " was modified during execution.")
            }
        }
    }

    @Throws(IOException::class)
    private fun wasModifiedSinceDigest(
        proxy: FileContentsProxy?,
        path: com.google.devtools.build.lib.vfs.Path
    ): Boolean {
        if (proxy == null) {
            // Metadata is not available (likely because this is not a regular file).
            return false
        }
        val stat: FileStatus? = path.statIfFound(Symlinks.FOLLOW)
        return stat == null || !stat.isFile() || proxy.isModified(FileContentsProxy.create(stat))
    }

    @Throws(IOException::class)
    override fun cleanupSandboxBase(sandboxBase: com.google.devtools.build.lib.vfs.Path, treeDeleter: TreeDeleter?) {
        if (cgroupsDir != null) {
            java.io.File(cgroupsDir).delete()
        }
        VirtualCgroup.Companion.deleteInstance()
        // Delete the inaccessible files synchronously, bypassing the treeDeleter. They are only a
        // couple of files that can be deleted fast, and ensuring they are gone at the end of every
        // build avoids annoying permission denied errors if the user happens to run "rm -rf" on the
        // output base. (We have some tests that do that.)
        if (inaccessibleHelperDir != null && inaccessibleHelperDir.exists()) {
            inaccessibleHelperDir.chmod(448)
            inaccessibleHelperDir.deleteTree()
        }
        if (inaccessibleHelperFile != null && inaccessibleHelperFile.exists()) {
            inaccessibleHelperFile.chmod(384)
            inaccessibleHelperFile.delete()
        }

        super.cleanupSandboxBase(sandboxBase, treeDeleter)
    }

    private fun makeInteractiveDebugArguments(
        commandLineBuilder: LinuxSandboxCommandLineBuilder, sandboxOptions: SandboxOptions
    ): com.google.common.collect.ImmutableList<String?>? {
        if (!sandboxOptions.getSandboxDebug()) {
            return null
        }
        return commandLineBuilder.buildForCommand(com.google.common.collect.ImmutableList.of<String?>("/bin/sh", "-i"))
    }

    private val networkNamespace: NetworkNamespace
        get() {
            if (getSandboxOptions().getSandboxEnableLoopbackDevice()) {
                return NetworkNamespace.NETNS_WITH_LOOPBACK
            }
            return NetworkNamespace.NETNS
        }

    companion object {
        // Since checking if sandbox is supported is expensive, we remember what we've checked.
        private val isSupportedMap: MutableMap<com.google.devtools.build.lib.vfs.Path?, Boolean?> =
            HashMap<com.google.devtools.build.lib.vfs.Path?, Boolean?>()

        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * Returns whether the linux sandbox is supported on the local machine by running a small command
         * in it.
         */
        @Throws(java.lang.InterruptedException::class)
        fun isSupported(cmdEnv: CommandEnvironment): Boolean {
            if (com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.LINUX) {
                return false
            }
            if (!LinuxSandboxUtil.isSupported(cmdEnv.getBlazeWorkspace())) {
                return false
            }
            val linuxSandbox: com.google.devtools.build.lib.vfs.Path? =
                LinuxSandboxUtil.getLinuxSandbox(cmdEnv.getBlazeWorkspace())
            var isSupported: Boolean?
            synchronized(isSupportedMap) {
                isSupported = isSupportedMap.get(linuxSandbox)
                if (isSupported != null) {
                    return isSupported!!
                }
                isSupported = computeIsSupported(cmdEnv, linuxSandbox)
                isSupportedMap.put(linuxSandbox, isSupported)
            }
            return isSupported!!
        }

        @Throws(java.lang.InterruptedException::class)
        private fun computeIsSupported(
            cmdEnv: CommandEnvironment,
            linuxSandbox: com.google.devtools.build.lib.vfs.Path?
        ): Boolean {
            val options: LocalExecutionOptions = cmdEnv.getOptions().getOptions(LocalExecutionOptions::class.java)
            val linuxSandboxArgv: com.google.common.collect.ImmutableList<String?>? =
                LinuxSandboxCommandLineBuilder.Companion.commandLineBuilder(linuxSandbox)
                    .setTimeout(options.getLocalSigkillGraceSecondsDuration())
                    .buildForCommand(com.google.common.collect.ImmutableList.of<String?>("/bin/true"))
            val env: com.google.common.collect.ImmutableMap<String?, String?> =
                com.google.common.collect.ImmutableMap.of<String?, String?>()
            val execRoot: com.google.devtools.build.lib.vfs.Path = cmdEnv.getExecRoot()
            val cwd: java.io.File? = execRoot.getPathFile()

            val cmd: com.google.devtools.build.lib.shell.Command =
                com.google.devtools.build.lib.shell.Command(linuxSandboxArgv, env, cwd, cmdEnv.getClientEnv())
            try {
                com.google.devtools.build.lib.profiler.Profiler.instance()
                    .profile("LinuxSandboxedSpawnRunner.isSupported").use { c ->
                        cmd.execute(
                            com.google.common.io.ByteStreams.nullOutputStream(),
                            com.google.common.io.ByteStreams.nullOutputStream()
                        )
                    }
            } catch (e: com.google.devtools.build.lib.shell.CommandException) {
                logger.atWarning().withCause(e).log(
                    "Checking for linux sandbox support failed: %s", e.message
                )
                return false
            }

            return true
        }
    }
}
