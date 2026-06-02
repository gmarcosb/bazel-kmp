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
package com.google.devtools.build.lib.sandbox

import com.google.devtools.build.lib.actions.ActionExecutionMetadata

/** Abstract common ancestor for sandbox spawn runners implementing the common parts.  */
internal abstract class AbstractSandboxSpawnRunner(cmdEnv: CommandEnvironment) : SpawnRunner {
    private val sandboxOptions: SandboxOptions
    private val verboseFailures: Boolean
    private val inaccessiblePaths: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?>?
    protected val binTools: BinTools?
    private val execRoot: com.google.devtools.build.lib.vfs.Path?
    private val resourceManager: ResourceManager
    private val reporter: com.google.devtools.build.lib.events.Reporter
    protected val clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?

    init {
        this.sandboxOptions = cmdEnv.getOptions().getOptions(SandboxOptions::class.java)
        this.verboseFailures =
            cmdEnv.getOptions().getOptions(ExecutionOptions::class.java).verboseFailures
        this.inaccessiblePaths =
            sandboxOptions.getInaccessiblePaths(cmdEnv.getRuntime().getFileSystem())
        this.binTools = cmdEnv.getBlazeWorkspace().getBinTools()
        this.execRoot = cmdEnv.getExecRoot()
        this.resourceManager = cmdEnv.getLocalResourceManager()
        this.reporter = cmdEnv.getReporter()
        this.clientEnv = cmdEnv.getClientEnv()
    }

    @Throws(ExecException::class, java.lang.InterruptedException::class)
    public override fun exec(spawn: Spawn, context: SpawnExecutionContext): SpawnResult {
        val owner: ActionExecutionMetadata? = spawn.getResourceOwner()
        context.report(SpawnSchedulingEvent.create(name))

        try {
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("context.prefetchInputs").use { c ->
                context.prefetchInputsAndWait()
            }
            resourceManager.acquireResources(
                owner,
                spawn.getLocalResources(),
                if (context.speculating())
                    ResourcePriority.DYNAMIC_STANDALONE
                else
                    ResourcePriority.LOCAL
            ).use { ignored ->
                context.report(SpawnExecutingEvent.create(name))
                val sandbox: SandboxedSpawn = prepareSpawn(spawn, context)
                return runSpawn(spawn, sandbox, context)
            }
        } catch (e: IOException) {
            val failureDetail: FailureDetail? =
                SandboxHelpers.createFailureDetail(
                    "I/O exception during sandboxed execution", Code.EXECUTION_IO_EXCEPTION
                )
            throw UserExecException(e, failureDetail)
        }
    }

    public override fun canExec(spawn: Spawn?): Boolean {
        return Spawns.mayBeSandboxed(spawn)
    }

    public override fun handlesCaching(): Boolean {
        return false
    }

    @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
    protected abstract fun prepareSpawn(spawn: Spawn?, context: SpawnExecutionContext?): SandboxedSpawn

    @Throws(ExecException::class, IOException::class, java.lang.InterruptedException::class)
    private fun runSpawn(
        originalSpawn: Spawn, sandbox: SandboxedSpawn, context: SpawnExecutionContext
    ): SpawnResult {
        try {
            try {
                com.google.devtools.build.lib.profiler.Profiler.instance().profile("sandbox.createFileSystem")
                    .use { c ->
                        sandbox.createFileSystem()
                    }
            } catch (e: IOException) {
                val failureDetail: FailureDetail? =
                    SandboxHelpers.createFailureDetail(
                        "Could not copy inputs into sandbox", Code.COPY_INPUTS_IO_EXCEPTION
                    )
                throw EnvironmentalExecException(e, failureDetail)
            }
            val result: SpawnResult
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("subprocess.run").use { c ->
                result = run(originalSpawn, sandbox, context)
            }
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("sandbox.verifyPostCondition").use { c ->
                verifyPostCondition(originalSpawn, sandbox, context)
            }
            context.lockOutputFiles(
                result.exitCode(),
                if (result.failureDetail() != null) result.failureDetail().getMessage() else "",
                context.fileOutErr
            )
            try {
                com.google.devtools.build.lib.profiler.Profiler.instance().profile("sandbox.copyOutputs").use { c ->
                    // We copy the outputs even when the command failed.
                    sandbox.copyOutputs(execRoot)
                }
            } catch (e: IOException) {
                val failureDetail: FailureDetail? =
                    SandboxHelpers.createFailureDetail(
                        "Could not copy outputs from sandbox", Code.COPY_OUTPUTS_IO_EXCEPTION
                    )
                throw EnvironmentalExecException(e, failureDetail)
            }
            return result
        } finally {
            if (!sandboxOptions.getSandboxDebug()) {
                com.google.devtools.build.lib.profiler.Profiler.instance().profile("sandbox.delete").use { c ->
                    sandbox.delete()
                }
            }
        }
    }

    /** Override this method if you need to run a post condition after the action has executed  */
    @Throws(IOException::class)
    open fun verifyPostCondition(
        originalSpawn: Spawn?, sandbox: SandboxedSpawn?, context: SpawnExecutionContext?
    ) {
    }

    private fun makeFailureMessage(originalSpawn: Spawn, sandbox: SandboxedSpawn): String {
        if (sandboxOptions.getSandboxDebug()) {
            return CommandFailureUtils.describeCommandFailure(
                true, sandbox.getSandboxExecRoot().getPathString(), sandbox
            )
        } else {
            return (CommandFailureUtils.describeCommandFailure(
                verboseFailures, sandbox.getSandboxExecRoot().getPathString(), originalSpawn
            )
                    + SANDBOX_DEBUG_SUGGESTION)
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun run(
        originalSpawn: Spawn, sandbox: SandboxedSpawn, context: SpawnExecutionContext
    ): SpawnResult {
        val spawnResultBuilder: SpawnResult.Builder = getSpawnResultBuilder(context)

        val outErr: FileOutErr = context.fileOutErr
        val timeout: java.time.Duration = context.timeout

        val subprocessBuilder: SubprocessBuilder = SubprocessBuilder(clientEnv)
        subprocessBuilder.setWorkingDirectory(sandbox.getSandboxExecRoot().getPathFile())
        subprocessBuilder.setStdout(outErr.getOutputPath().getPathFile())
        subprocessBuilder.setStderr(outErr.getErrorPath().getPathFile())
        subprocessBuilder.setEnv(sandbox.getEnvironment())
        subprocessBuilder.setArgv(com.google.common.collect.ImmutableList.copyOf<String?>(sandbox.getArguments()))
        val useSubprocessTimeout: Boolean = sandbox.useSubprocessTimeout()
        if (useSubprocessTimeout) {
            subprocessBuilder.setTimeoutMillis(timeout.toMillis())
        }
        val startTime: Instant = Instant.now()
        val terminationStatus: TerminationStatus?
        try {
            val subprocess: Subprocess = subprocessBuilder.start()
            subprocess.getOutputStream().close()
            try {
                subprocess.waitFor()
                terminationStatus = TerminationStatus(subprocess.exitValue(), subprocess.timedout())
            } catch (e: java.lang.InterruptedException) {
                subprocess.destroyAndWait()
                throw e
            }
        } catch (e: IOException) {
            val exceptionMsg: String? = if (e.message == null) e.javaClass.getName() else e.message
            val sandboxDebugOutput = getSandboxDebugOutput(sandbox)

            val msg: java.lang.StringBuilder =
                java.lang.StringBuilder("Action failed to execute: java.io.IOException: ")
            msg.append(exceptionMsg)
            msg.append("\n")
            if (!sandboxDebugOutput.isEmpty()) {
                msg.append("Sandbox debug output:\n")
                msg.append(sandboxDebugOutput)
                msg.append("\n")
            }

            outErr.getErrorStream().write(msg.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            outErr.getErrorStream().flush()
            val message = makeFailureMessage(originalSpawn, sandbox)
            return spawnResultBuilder
                .setStatus(Status.EXECUTION_FAILED)
                .setExitCode(LOCAL_EXEC_ERROR)
                .setFailureMessage(message)
                .setFailureDetail(
                    SandboxHelpers.createFailureDetail(message, Code.SUBPROCESS_START_FAILED)
                )
                .build()
        }

        // TODO(b/62588075): Calculate wall time inside Subprocess instead?
        val wallTime: java.time.Duration = java.time.Duration.between(startTime, Instant.now())
        val wasTimeout =
            (useSubprocessTimeout && terminationStatus.timedOut())
                    || (!useSubprocessTimeout && wasTimeout(timeout, wallTime))

        val exitCode: Int
        val status: Status?
        val failureMessage: String?
        val failureDetail: FailureDetail?
        if (wasTimeout) {
            exitCode = SpawnResult.POSIX_TIMEOUT_EXIT_CODE
            status = Status.TIMEOUT
            failureMessage = makeFailureMessage(originalSpawn, sandbox)
            failureDetail =
                FailureDetail.newBuilder()
                    .setMessage(failureMessage)
                    .setSpawn(
                        FailureDetails.Spawn.newBuilder().setCode(FailureDetails.Spawn.Code.TIMEOUT)
                    )
                    .build()
        } else {
            exitCode = terminationStatus.getRawExitCode()
            if (exitCode == 0) {
                status = Status.SUCCESS
                failureMessage = ""
                failureDetail = null
            } else {
                status = Status.NON_ZERO_EXIT
                failureMessage = makeFailureMessage(originalSpawn, sandbox)
                failureDetail =
                    FailureDetail.newBuilder()
                        .setMessage(failureMessage)
                        .setSpawn(
                            FailureDetails.Spawn.newBuilder()
                                .setCode(FailureDetails.Spawn.Code.NON_ZERO_EXIT)
                                .setSpawnExitCode(exitCode)
                        )
                        .build()
            }
        }

        spawnResultBuilder
            .setStatus(status)
            .setExitCode(exitCode)
            .setStartTime(startTime)
            .setWallTimeInMs(wallTime.toMillis().toInt())
            .setFailureMessage(failureMessage)

        if (failureDetail != null) {
            spawnResultBuilder.setFailureDetail(failureDetail)
        }

        val sandboxDebugOutput = getSandboxDebugOutput(sandbox)
        if (!sandboxDebugOutput.isEmpty()) {
            reporter.handle(
                com.google.devtools.build.lib.events.Event.of(
                    com.google.devtools.build.lib.events.EventKind.DEBUG,
                    java.lang.String.format(
                        "Sandbox debug output for %s %s:\n%s",
                        originalSpawn.getMnemonic(),
                        originalSpawn.getTargetLabel(),
                        sandboxDebugOutput
                    )
                )
            )
        }

        val statisticsPath: com.google.devtools.build.lib.vfs.Path? = sandbox.getStatisticsPath()
        if (statisticsPath != null) {
            spawnResultBuilder.setResourceUsageFromProto(statisticsPath)
        }

        return spawnResultBuilder.build()
    }

    /**
     * Gets the list of directories that the spawn will assume to be writable.
     * 
     * @param sandboxExecRoot the exec root of the sandbox
     * @param env the environment of the sandboxed processes
     * @throws IOException because we might resolve symlinks, which throws [IOException].
     */
    @Throws(IOException::class)
    protected open fun getWritableDirs(
        sandboxExecRoot: com.google.devtools.build.lib.vfs.Path,
        env: MutableMap<String?, String?>
    ): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?> {
        // We have to make the TEST_TMPDIR directory writable if it is specified.
        val writablePaths: com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.vfs.Path?> =
            com.google.common.collect.ImmutableSet.builder<com.google.devtools.build.lib.vfs.Path?>()

        // On Windows, sandboxExecRoot is actually the main execroot. We will specify
        // exactly which output path is writable.
        if (com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS) {
            writablePaths.add(sandboxExecRoot)
        }

        val testTmpdir = env.get("TEST_TMPDIR")
        if (testTmpdir != null) {
            addWritablePath(
                sandboxExecRoot,
                writablePaths,
                testTmpdir,
                "Cannot resolve symlinks in TEST_TMPDIR because it doesn't exist: \"%s\""
            )
        }
        // As of 2019-07-08:
        // - every caller of `getWritableDirs` passes a LocalEnvProvider-processed environment as
        //   `env`, therefore `env` surely has an entry for TMPDIR on Unix and TEMP/TMP on Windows.
        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            addWritablePath(
                sandboxExecRoot,
                writablePaths,
                com.google.common.base.Preconditions.checkNotNull<String?>(env.get("TEMP")),
                "Cannot resolve symlinks in TEMP because it doesn't exist: \"%s\""
            )
            addWritablePath(
                sandboxExecRoot,
                writablePaths,
                com.google.common.base.Preconditions.checkNotNull<String?>(env.get("TMP")),
                "Cannot resolve symlinks in TMP because it doesn't exist: \"%s\""
            )
        } else {
            addWritablePath(
                sandboxExecRoot,
                writablePaths,
                com.google.common.base.Preconditions.checkNotNull<String?>(env.get("TMPDIR")),
                "Cannot resolve symlinks in TMPDIR because it doesn't exist: \"%s\""
            )
        }

        val fileSystem: com.google.devtools.build.lib.vfs.FileSystem = sandboxExecRoot.getFileSystem()
        for (writablePath in sandboxOptions.getSandboxWritablePath()) {
            val path: com.google.devtools.build.lib.vfs.Path? = fileSystem.getPath(writablePath)
            writablePaths.add(path)
            // TODO(laszlocsomor): Remove if guard when path.resolveSymbolicLinks supports non-symlink
            // TODO(laszlocsomor): Figure out why OS.getCurrent() != OS.WINDOWS is required, and remove it
            if (com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS || path.isSymbolicLink()) {
                writablePaths.add(path.resolveSymbolicLinks())
            }
        }

        return writablePaths.build()
    }

    protected fun getInaccessiblePaths(): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?>? {
        return inaccessiblePaths
    }

    protected fun getSandboxOptions(): SandboxOptions {
        return sandboxOptions
    }

    @Throws(IOException::class)
    public override fun cleanupSandboxBase(
        sandboxBase: com.google.devtools.build.lib.vfs.Path,
        treeDeleter: TreeDeleter
    ) {
        val root: com.google.devtools.build.lib.vfs.Path = sandboxBase.getChild(name)
        if (root.exists()) {
            for (child in root.getDirectoryEntries()) {
                treeDeleter.deleteTree(child)
            }
        }
    }

    companion object {
        private val LOCAL_EXEC_ERROR = -1

        private val SANDBOX_DEBUG_SUGGESTION = ("\n\nUse --sandbox_debug to see verbose messages from the sandbox "
                + "and retain the sandbox build root for debugging")

        @Throws(IOException::class)
        private fun getSandboxDebugOutput(sandbox: SandboxedSpawn): String {
            var sandboxDebugOutput: java.util.Optional<String?> = java.util.Optional.empty<String?>()
            val sandboxDebugPath: com.google.devtools.build.lib.vfs.Path? = sandbox.getSandboxDebugPath()
            if (sandboxDebugPath != null && sandboxDebugPath.exists()) {
                sandboxDebugPath.getInputStream().use { inputStream ->
                    val msg = String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    if (!msg.isEmpty()) {
                        sandboxDebugOutput = java.util.Optional.of<String?>(msg)
                    }
                }
            }
            val interactiveDebugInstructions: java.util.Optional<String?>? = sandbox.getInteractiveDebugInstructions()
            return java.util.stream.Stream.of<java.util.Optional<String?>?>(
                sandboxDebugOutput,
                interactiveDebugInstructions
            )
                .flatMap<String?> { obj: java.util.Optional<kotlin.String?>? -> obj.stream() }
                .collect(Collectors.joining("\n"))
        }

        private fun wasTimeout(timeout: java.time.Duration, wallTime: java.time.Duration): Boolean {
            return !timeout.isZero() && wallTime.compareTo(timeout) > 0
        }

        @Throws(IOException::class)
        private fun addWritablePath(
            sandboxExecRoot: com.google.devtools.build.lib.vfs.Path,
            writablePaths: com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.vfs.Path?>,
            pathString: String?,
            pathDoesNotExistErrorTemplate: String
        ) {
            val path: com.google.devtools.build.lib.vfs.Path = sandboxExecRoot.getRelative(pathString)
            if (path.startsWith(sandboxExecRoot)) {
                // We add this path even though it is below sandboxExecRoot (and thus already writable as a
                // subpath) to take advantage of the side-effect that SymlinkedExecRoot also creates this
                // needed directory if it doesn't exist yet.
                writablePaths.add(path)
            } else if (path.exists()) {
                // If `path` itself is a symlink, then adding it to `writablePaths` would result in making
                // the symlink itself writable, not what it points to. Therefore we need to resolve symlinks
                // in `path`, however for that we need `path` to exist.
                //
                // TODO(laszlocsomor): Remove if guard when path.resolveSymbolicLinks supports non-symlink
                // TODO(laszlocsomor): Figure out why OS.getCurrent() != OS.WINDOWS is required, and remove it
                if (com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS || path.isSymbolicLink()) {
                    writablePaths.add(path.resolveSymbolicLinks())
                } else {
                    writablePaths.add(path)
                }
            } else {
                throw IOException(String.format(pathDoesNotExistErrorTemplate, path.getPathString()))
            }
        }
    }
}
