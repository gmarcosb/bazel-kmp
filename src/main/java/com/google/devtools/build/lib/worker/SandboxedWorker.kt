// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.sandbox.LinuxSandboxCommandLineBuilder.NetworkNamespace.NETNS

/** A [SingleplexWorker] that runs inside a sandboxed execution root.  */
internal class SandboxedWorker(
    workerKey: WorkerKey?,
    workerId: Int,
    workDir: com.google.devtools.build.lib.vfs.Path?,
    logFile: com.google.devtools.build.lib.vfs.Path?,
    workerOptions: WorkerOptions?,
    hardenedSandboxOptions: WorkerSandboxOptions?,
    treeDeleter: TreeDeleter?,
    useInMemoryTracking: Boolean,
    cgroupFactory: VirtualCgroupFactory?
) : SingleplexWorker(workerKey, workerId, workDir, logFile, workerOptions, cgroupFactory) {
    // Need to have this data class because we can't depend on SandboxOptions in here.
    internal class WorkerSandboxOptions(
        sandboxBinary: com.google.devtools.build.lib.vfs.Path?,
        fakeHostname: Boolean,
        fakeUsername: Boolean,
        debugMode: Boolean,
        tmpfsPath: com.google.common.collect.ImmutableSet<PathFragment?>?,
        writablePaths: com.google.common.collect.ImmutableSet<String?>?,
        memoryLimit: Int,
        inaccessiblePaths: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path>?,
        additionalMountPaths: com.google.common.collect.ImmutableMap<String?, String?>?
    ) {
        val sandboxBinary: com.google.devtools.build.lib.vfs.Path?
        val fakeHostname: Boolean
        val fakeUsername: Boolean
        val debugMode: Boolean
        val tmpfsPath: com.google.common.collect.ImmutableSet<PathFragment?>?
        val writablePaths: com.google.common.collect.ImmutableSet<String?>?
        val memoryLimit: Int
        val inaccessiblePaths: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path>?
        val additionalMountPaths: com.google.common.collect.ImmutableMap<String?, String?>?

        init {
            this.sandboxBinary = sandboxBinary
            this.fakeHostname = fakeHostname
            this.fakeUsername = fakeUsername
            this.debugMode = debugMode
            this.tmpfsPath = tmpfsPath
            this.writablePaths = writablePaths
            this.memoryLimit = memoryLimit
            this.inaccessiblePaths = inaccessiblePaths
            this.additionalMountPaths = additionalMountPaths
        }
    }

    private val workerExecRoot: WorkerExecRoot

    /** Options specific to hardened sandbox, null if not using that.  */
    private val hardenedSandboxOptions: WorkerSandboxOptions?

    private var inaccessibleHelperDir: com.google.devtools.build.lib.vfs.Path? = null
    private var inaccessibleHelperFile: com.google.devtools.build.lib.vfs.Path? = null
    private val treeDeleter: TreeDeleter?

    init {
        val tmpDirPath: com.google.devtools.build.lib.vfs.Path = SandboxHelpers.getTmpDirPath(workDir)
        this.workerExecRoot =
            WorkerExecRoot(
                workDir,
                if (hardenedSandboxOptions != null)
                    com.google.common.collect.ImmutableList.of<PathFragment?>(PathFragment.Companion.create(tmpDirPath.getPathString()))
                else
                    com.google.common.collect.ImmutableList.of<PathFragment?>(),
                useInMemoryTracking
            )
        this.hardenedSandboxOptions = hardenedSandboxOptions
        this.treeDeleter = treeDeleter
    }

    val isSandboxed: Boolean
        get() = true

    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class)
    fun getWritableDirs(sandboxExecRoot: com.google.devtools.build.lib.vfs.Path?): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?> {
        val writableDirs: com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.vfs.Path?> =
            com.google.common.collect.ImmutableSet.builder<com.google.devtools.build.lib.vfs.Path?>()
                .add(sandboxExecRoot)

        val fs: com.google.devtools.build.lib.vfs.FileSystem = sandboxExecRoot.getFileSystem()
        for (writablePath in hardenedSandboxOptions!!.writablePaths) {
            val path: com.google.devtools.build.lib.vfs.Path = fs.getPath(writablePath)
            writableDirs.add(path)
            if (path.isSymbolicLink()) {
                writableDirs.add(path.resolveSymbolicLinks())
            }
        }

        val devShm: com.google.devtools.build.lib.vfs.Path = fs.getPath("/dev/shm")
        if (devShm.exists()) {
            writableDirs.add(devShm.resolveSymbolicLinks())
        }
        writableDirs.add(fs.getPath("/tmp"))
        return writableDirs.build()
    }

    @Throws(UserExecException::class, IOException::class)
    private fun getBindMounts(
        sandboxExecRoot: com.google.devtools.build.lib.vfs.Path,
        sandboxTmp: com.google.devtools.build.lib.vfs.Path?
    ): SortedMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?> {
        val fs: com.google.devtools.build.lib.vfs.FileSystem = sandboxExecRoot.getFileSystem()
        val tmpPath: com.google.devtools.build.lib.vfs.Path = fs.getPath("/tmp")
        val bindMounts: SortedMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?> =
            com.google.common.collect.Maps.newTreeMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?>()
        bindMounts.put(tmpPath, sandboxTmp)
        SandboxHelpers.mountAdditionalPaths(
            hardenedSandboxOptions!!.additionalMountPaths, sandboxExecRoot, bindMounts
        )

        inaccessibleHelperFile = LinuxSandboxUtil.getInaccessibleHelperFile(sandboxExecRoot)
        inaccessibleHelperDir = LinuxSandboxUtil.getInaccessibleHelperDir(sandboxExecRoot)
        for (inaccessiblePath in hardenedSandboxOptions.inaccessiblePaths) {
            if (inaccessiblePath.isDirectory(Symlinks.NOFOLLOW)) {
                bindMounts.put(inaccessiblePath, inaccessibleHelperDir)
            } else {
                bindMounts.put(inaccessiblePath, inaccessibleHelperFile)
            }
        }
        // TODO(larsrc): Handle hermetic tmp
        LinuxSandboxUtil.validateBindMounts(bindMounts)
        return bindMounts
    }

    @Throws(IOException::class, UserExecException::class)
    override fun createProcess(clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?): Subprocess {
        var args: com.google.common.collect.ImmutableList<String?>? = makeExecPathAbsolute(workerKey.getArgs())

        // We put the sandbox inside a unique subdirectory using the worker's ID.
        if (cgroupFactory != null) {
            cgroup = cgroupFactory.create(workerId, com.google.common.collect.ImmutableMap.of<K?, V?>())
        } else if (options.getUseCgroupsOnLinux() || hardenedSandboxOptions != null) {
            // In the event that the memory limit is 0, we defer to using Blaze's WorkerLifecycleManager
            // to kill workers rather than cgroup's OOM killer.
            cgroup =
                CgroupsInfo.getBlazeSpawnsCgroup()
                    .createIndividualSpawnCgroup(
                        "worker_sandbox_" + workerId,
                        if (hardenedSandboxOptions != null) hardenedSandboxOptions.memoryLimit else 0
                    )
        }

        // TODO(larsrc): Check that execRoot and outputBase are not under /tmp
        if (hardenedSandboxOptions != null) {
            val sandboxTmp: com.google.devtools.build.lib.vfs.Path = SandboxHelpers.getTmpDirPath(workDir)
            sandboxTmp.createDirectoryAndParents()

            // Mostly tests require network, and some blaze run commands, but no workers.
            val commandLineBuilder: LinuxSandboxCommandLineBuilder =
                LinuxSandboxCommandLineBuilder.commandLineBuilder(
                    this.hardenedSandboxOptions.sandboxBinary
                )
                    .setWritableFilesAndDirectories(getWritableDirs(workDir))
                    .setTmpfsDirectories(hardenedSandboxOptions.tmpfsPath)
                    .setPersistentProcess(true)
                    .setBindMounts(getBindMounts(workDir, sandboxTmp))
                    .setUseFakeHostname(hardenedSandboxOptions.fakeHostname)
                    .setCreateNetworkNamespace(NETNS)

            if (cgroup != null && cgroup.exists()) {
                commandLineBuilder.setCgroupsDirs(cgroup.paths())
            }

            if (this.hardenedSandboxOptions.fakeUsername) {
                commandLineBuilder.setUseFakeUsername(true)
            }

            args = commandLineBuilder.buildForCommand(args)
        }

        val process: Subprocess = createProcessBuilder(args, clientEnv).start()

        // If using hardened sandbox (aka linux-sandbox), the linux-sandbox parent process moves the
        // sandboxed children processes (pid 1, 2) into the cgroup. But we still need to move the
        // linux-sandbox process into the worker cgroup. On the other hand, without linux-sandbox, Blaze
        // needs to do this itself for the spawned worker process.
        if (cgroup != null && cgroup.exists()) {
            cgroup.addProcess(process.processId)
        }
        return process
    }

    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    override fun prepareExecution(
        inputFiles: SandboxInputs,
        outputs: SandboxOutputs?,
        workerFiles: MutableSet<PathFragment?>?,
        clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?
    ) {
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("workerExecRoot.createFileSystem").use { c ->
            workerExecRoot.createFileSystem(workerFiles, inputFiles, outputs, treeDeleter)
        }
        super.prepareExecution(inputFiles, outputs, workerFiles, clientEnv)
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun finishExecution(execRoot: com.google.devtools.build.lib.vfs.Path?, outputs: SandboxOutputs?) {
        super.finishExecution(execRoot, outputs)
        if (cgroup != null && cgroup.exists()) {
            // This is only to not leave too much behind in the cgroups tree, can ignore errors.
            cgroup.destroy()
        }
        workerExecRoot.copyOutputs(execRoot, outputs)
    }

    @kotlin.jvm.Synchronized
    override fun destroy() {
        super.destroy()
        try {
            if (inaccessibleHelperFile != null) {
                inaccessibleHelperFile.delete()
            }
            if (inaccessibleHelperDir != null) {
                inaccessibleHelperDir.delete()
            }
            if (cgroup != null && cgroup.exists()) {
                // This is only to not leave too much behind in the cgroups tree, can ignore errors.
                cgroup.destroy()
            }
            workDir.deleteTree()
            if (hardenedSandboxOptions != null) {
                SandboxHelpers.getTmpDirPath(workDir).deleteTree()
            }
        } catch (e: IOException) {
            logger.atWarning().withCause(e).log("Caught IOException while deleting workdir.")
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
