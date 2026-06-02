// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec.local

import com.google.devtools.build.lib.actions.ActionExecutionMetadata

/**
 * A class that runs local commands. Each request follows state transitions from "parsing" to
 * completion.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
open class LocalSpawnRunner(
    execRoot: com.google.devtools.build.lib.vfs.Path,
    localExecutionOptions: LocalExecutionOptions?,
    resourceManager: ResourceManager,
    localEnvProvider: LocalEnvProvider,
    binTools: BinTools?,
    processWrapper: ProcessWrapper?,
    runfilesTreeUpdater: RunfilesTreeUpdater
) : SpawnRunner {
    private val execRoot: com.google.devtools.build.lib.vfs.Path
    private val resourceManager: ResourceManager

    private val hostName: String?

    private val localExecutionOptions: LocalExecutionOptions

    private val processWrapper: ProcessWrapper?

    private val localEnvProvider: LocalEnvProvider
    private val binTools: BinTools?

    private val runfilesTreeUpdater: RunfilesTreeUpdater

    init {
        this.execRoot = execRoot
        this.processWrapper = processWrapper
        this.localExecutionOptions =
            com.google.common.base.Preconditions.checkNotNull<LocalExecutionOptions>(localExecutionOptions)
        this.hostName = com.google.devtools.build.lib.util.NetUtil.getCachedShortHostName()
        this.resourceManager = resourceManager
        this.localEnvProvider = localEnvProvider
        this.binTools = binTools
        this.runfilesTreeUpdater = runfilesTreeUpdater
    }

    val name: String
        get() = "local"

    @Throws(IOException::class, java.lang.InterruptedException::class, ExecException::class)
    override fun exec(spawn: Spawn, context: SpawnExecutionContext): SpawnResult {
        val spawnMetrics: SpawnMetrics.Builder = SpawnMetrics.Builder.forLocalExec()
        val totalTimeStopwatch: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
        val setupTimeStopwatch: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
        if (Spawns.shouldPrefetchInputsForLocalExecution(spawn)) {
            context.prefetchInputsAndWait()
        }
        spawnMetrics.addSetupTimeInMs(setupTimeStopwatch.elapsed().toMillis().toInt())

        com.google.devtools.build.lib.profiler.Profiler.instance()
            .profile(
                com.google.devtools.build.lib.profiler.ProfilerTask.LOCAL_EXECUTION,
                spawn.getResourceOwner().getMnemonic()
            ).use { c ->
                val owner: ActionExecutionMetadata? = spawn.getResourceOwner()
                context.report(SpawnSchedulingEvent.Companion.create(this.name))

                val queueStopwatch: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
                resourceManager.acquireResources(
                    owner,
                    spawn.getLocalResources(),
                    if (context.speculating())
                        ResourcePriority.DYNAMIC_STANDALONE
                    else
                        ResourcePriority.LOCAL
                ).use { handle ->
                    spawnMetrics.setQueueTime(queueStopwatch.elapsed())
                    context.report(SpawnExecutingEvent.Companion.create(this.name))
                    if (!localExecutionOptions.getLocalLockfreeOutput()) {
                        // Without local-lockfree, we grab the lock before running the action, so we can't
                        // check for failures while taking the lock.
                        context.lockOutputFiles(0, "", context.getFileOutErr())
                    }
                    val result: SpawnResult = SubprocessHandler(spawn, context, spawnMetrics, totalTimeStopwatch).run()
                    if (result.exitCode() !== 0 && localExecutionOptions.getLocalLockfreeOutput()
                        && context.speculating()
                    ) {
                        // We aren't going to write any output, but we should either abort the remote branch early
                        // or let it finish if this error can be ignored. If the latter, this call will throw
                        // DynamicInterruptedException.
                        context.lockOutputFiles(result.exitCode(), "", context.getFileOutErr())
                    }
                    return result
                }
            }
    }

    override fun canExec(spawn: Spawn?): Boolean {
        return !Spawns.usesPathMapping(spawn)
    }

    override fun handlesCaching(): Boolean {
        return false
    }

    @Throws(IOException::class)
    protected open fun createActionTemp(execRoot: com.google.devtools.build.lib.vfs.Path): com.google.devtools.build.lib.vfs.Path {
        return execRoot.createTempDirectory("local-spawn-runner.")
    }

    private inner class SubprocessHandler(
        spawn: Spawn,
        context: SpawnExecutionContext,
        spawnMetrics: SpawnMetrics.Builder,
        totalTimeStopwatch: com.google.common.base.Stopwatch
    ) {
        private val spawn: Spawn
        private val context: SpawnExecutionContext
        private val spawnMetrics: SpawnMetrics.Builder
        private val totalTimeStopwatch: com.google.common.base.Stopwatch

        private val creationTime: Long = java.lang.System.currentTimeMillis()
        private var stateStartTime = creationTime
        private var currentState: State? = com.google.devtools.build.lib.exec.local.LocalSpawnRunner.State.INITIALIZING
        private val stateTimes: MutableMap<State?, Long?> =
            java.util.EnumMap<State?, Long?>(com.google.devtools.build.lib.exec.local.LocalSpawnRunner.State::class.java)

        /**
         * If true, the local subprocess has already started, which means we need to clean up the output
         * tree once we get interrupted.
         */
        private var needCleanup = false

        private val id: Int

        init {
            com.google.common.base.Preconditions.checkArgument(!spawn.getArguments().isEmpty())
            this.spawn = spawn
            this.totalTimeStopwatch = totalTimeStopwatch
            this.context = context
            this.spawnMetrics = spawnMetrics
            this.id = context.getId()
            setState(com.google.devtools.build.lib.exec.local.LocalSpawnRunner.State.PARSING)
        }

        @Throws(java.lang.InterruptedException::class, ExecException::class, IOException::class)
        fun run(): SpawnResult {
            if (localExecutionOptions.getLocalRetriesOnCrash() == 0) {
                return runOnce()
            } else {
                var attempts = 0
                while (true) {
                    // Assume that any exceptions from runOnce() come from the Java side of things, not the
                    // subprocess, so let them bubble up on first occurrence. In particular, we need this to
                    // be true for InterruptedException to ensure that the dynamic scheduler can stop us
                    // quickly.
                    val rertyStopwatch: com.google.common.base.Stopwatch =
                        com.google.common.base.Stopwatch.createStarted()
                    val result: SpawnResult = runOnce()
                    if (attempts == localExecutionOptions.getLocalRetriesOnCrash()
                        || !TerminationStatus.crashed(result.exitCode())
                    ) {
                        return result
                    }
                    stepLog(
                        java.util.logging.Level.SEVERE,
                        "Retrying crashed subprocess due to exit code %s (attempt %s)",
                        result.exitCode(),
                        attempts
                    )
                    java.lang.Thread.sleep(attempts * 1000L)
                    spawnMetrics.addRetryTimeInMs(
                        result.exitCode(), rertyStopwatch.elapsed().toMillis().toInt()
                    )
                    attempts++
                }
            }
        }

        @Throws(java.lang.InterruptedException::class, ExecException::class, IOException::class)
        fun runOnce(): SpawnResult {
            try {
                return start()
            } catch (e: java.lang.InterruptedException) {
                maybeCleanupOnInterrupt()
                // Logging the exception causes a lot of noise in builds using the dynamic scheduler, and
                // the information is not very interesting, so avoid that.
                stepLog(java.util.logging.Level.SEVERE, "Interrupted (and cleanup finished)")
                throw e
            } catch (e: InterruptedIOException) {
                maybeCleanupOnInterrupt()
                stepLog(java.util.logging.Level.SEVERE, "Interrupted (and cleanup finished)")
                throw e
            } catch (e: java.lang.Error) {
                stepLog(java.util.logging.Level.SEVERE, e, UNHANDLED_EXCEPTION_MSG)
                throw e
            } catch (e: IOException) {
                stepLog(java.util.logging.Level.SEVERE, e, "Local I/O error")
                throw e
            } catch (e: java.lang.RuntimeException) {
                stepLog(java.util.logging.Level.SEVERE, e, UNHANDLED_EXCEPTION_MSG)
                throw java.lang.RuntimeException(UNHANDLED_EXCEPTION_MSG, e)
            }
        }

        @com.google.errorprone.annotations.FormatMethod
        fun stepLog(
            level: java.util.logging.Level?,
            @com.google.errorprone.annotations.FormatString fmt: String,
            vararg args: Any?
        ) {
            stepLog(level,  /* cause= */null, fmt, *args)
        }

        @com.google.errorprone.annotations.FormatMethod
        fun stepLog(
            level: java.util.logging.Level?,
            cause: Throwable?,
            @com.google.errorprone.annotations.FormatString fmt: String,
            vararg args: Any?
        ) {
            val msg: String? = java.lang.String.format(fmt, *args)
            val toLog: String? = java.lang.String.format("%s (#%d %s)", msg, id, desc())
            logger.at(level).withCause(cause).log("%s", toLog)
        }

        fun desc(): String {
            val progressMessage: String? = spawn.getResourceOwner().getProgressMessage()
            return if (progressMessage != null)
                progressMessage
            else
                "ActionType=" + spawn.getResourceOwner().getMnemonic()
        }

        fun setState(newState: State?) {
            val now: Long = java.lang.System.currentTimeMillis()
            val stepDelta = now - stateStartTime
            stateStartTime = now

            val stateTimeBoxed = stateTimes.get(currentState)
            val stateTime = if (stateTimeBoxed == null) 0 else stateTimeBoxed
            stateTimes.put(currentState, stateTime + stepDelta)

            currentState = newState
        }

        fun debugCmdString(): String {
            val cmd: String = SPACE_JOINER.join(spawn.getArguments())
            if (cmd.length() > 500) {
                // Shrink argstr by replacing middle of string with "...".
                return cmd.substring(0, 250) + "..." + cmd.substring(cmd.length() - 250)
            }
            return cmd
        }

        /** Parse the request and run it locally.  */
        @Throws(java.lang.InterruptedException::class, ExecException::class, IOException::class)
        fun start(): SpawnResult {
            logger.atInfo().log("starting local subprocess #%d, argv: %s", id, debugCmdString())

            val spawnResultBuilder: SpawnResult.Builder =
                getSpawnResultBuilder(context).setExecutorHostname(hostName)

            val outErr: FileOutErr = context.getFileOutErr()
            val actionType: String? = spawn.getResourceOwner().getMnemonic()
            if (localExecutionOptions.getAllowedLocalAction() != null
                && !localExecutionOptions.getAllowedLocalAction().matcher().test(actionType)
            ) {
                setState(com.google.devtools.build.lib.exec.local.LocalSpawnRunner.State.PERMANENT_ERROR)
                outErr
                    .getErrorStream()
                    .write(
                        (("Action type "
                                + actionType
                                + " is not allowed to run locally due to regex filter: "
                                + StringEncoding.unicodeToInternal(
                            localExecutionOptions.getAllowedLocalAction().regexPattern().toString()
                        )
                                + "\n"))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    )
                spawnMetrics.setTotalTime(totalTimeStopwatch.elapsed())
                return spawnResultBuilder
                    .setStatus(Status.EXECUTION_DENIED)
                    .setExitCode(LOCAL_EXEC_ERROR)
                    .setFailureDetail(
                        makeFailureDetail(LOCAL_EXEC_ERROR, Status.EXECUTION_DENIED, actionType)
                    )
                    .setSpawnMetrics(spawnMetrics.build())
                    .build()
            }

            spawnMetrics.setInputFiles(spawn.getInputFiles().memoizedFlattenAndGetSize())
            val setupTimeStopwatch: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
            val runfilesTrees: MutableList<RunfilesTree?> = java.util.ArrayList<RunfilesTree?>()

            for (input in spawn.getInputFiles().toList()) {
                if (input is VirtualActionInput) {
                    input.atomicallyWriteRelativeTo(execRoot)
                } else if ((input is Artifact) && (input as Artifact).isRunfilesTree()) {
                    runfilesTrees.add(
                        context.getInputMetadataProvider().getRunfilesMetadata(input).getRunfilesTree()
                    )
                }
            }

            com.google.devtools.build.lib.profiler.Profiler.instance().profile("updateRunfiles").use { s ->
                runfilesTreeUpdater.updateRunfiles(runfilesTrees)
            }
            stepLog(java.util.logging.Level.INFO, "running locally")
            setState(com.google.devtools.build.lib.exec.local.LocalSpawnRunner.State.LOCAL_ACTION_RUNNING)

            val tmpDir: com.google.devtools.build.lib.vfs.Path = createActionTemp(execRoot)
            var statisticsPath: com.google.devtools.build.lib.vfs.Path? = null
            try {
                val commandTmpDir: com.google.devtools.build.lib.vfs.Path = tmpDir.getRelative("work")
                commandTmpDir.createDirectory()
                val environment: com.google.common.collect.ImmutableMap<String?, String?>? =
                    localEnvProvider.rewriteLocalEnv(
                        spawn.getEnvironment(), binTools, commandTmpDir.getPathString()
                    )

                val subprocessBuilder: SubprocessBuilder = SubprocessBuilder(context.getClientEnv())
                subprocessBuilder.setWorkingDirectory(execRoot.getPathFile())
                subprocessBuilder.setStdout(outErr.getOutputPath().getPathFile())
                subprocessBuilder.setStderr(outErr.getErrorPath().getPathFile())
                subprocessBuilder.setEnv(environment)
                var args: com.google.common.collect.ImmutableList<String?>
                if (processWrapper != null) {
                    // If the process wrapper is enabled, we use its timeout feature, which first interrupts
                    // the subprocess and only kills it after a grace period so that the subprocess can output
                    // a stack trace, test log or similar, which is incredibly helpful for debugging.
                    val commandLineBuilder: com.google.devtools.build.lib.runtime.ProcessWrapper.CommandLineBuilder =
                        processWrapper
                            .commandLineBuilder(spawn.getArguments())
                            .addExecutionInfo(spawn.getExecutionInfo())
                            .setTimeout(context.getTimeout())
                    statisticsPath = tmpDir.getRelative("stats.out")
                    commandLineBuilder.setStatisticsPath(statisticsPath.asFragment())
                    args = com.google.common.collect.ImmutableList.copyOf<String?>(commandLineBuilder.build())
                } else {
                    subprocessBuilder.setTimeoutMillis(context.getTimeout().toMillis())
                    args = spawn.getArguments()
                }
                // SubprocessBuilder does not accept relative paths for the first argument, even though
                // Command does. We sometimes get relative paths here, so we need to handle it.
                val argv0: java.io.File = java.io.File(args.get(0))
                if (!argv0.isAbsolute() && argv0.getParent() != null) {
                    val newArgs: MutableList<String?> = java.util.ArrayList<String?>(args)
                    newArgs.set(0, java.io.File(execRoot.getPathFile(), newArgs.get(0)).getAbsolutePath())
                    args = com.google.common.collect.ImmutableList.copyOf<String?>(newArgs)
                }
                subprocessBuilder.setArgv(args)
                spawnMetrics.addSetupTime(setupTimeStopwatch.elapsed())

                spawnResultBuilder.setStartTime(Instant.now())
                val executionStopwatch: com.google.common.base.Stopwatch =
                    com.google.common.base.Stopwatch.createStarted()
                val terminationStatus: TerminationStatus?
                try {
                    com.google.devtools.build.lib.profiler.Profiler.instance()
                        .profile(
                            com.google.devtools.build.lib.profiler.ProfilerTask.LOCAL_PROCESS_TIME,
                            spawn.getResourceOwner().getMnemonic()
                        ).use { c ->
                            needCleanup = true
                            val subprocess: Subprocess = subprocessBuilder.start()
                            try {
                                subprocess.getOutputStream().close()
                                subprocess.waitFor()
                                terminationStatus =
                                    TerminationStatus(subprocess.exitValue(), subprocess.timedout())
                            } catch (e: java.lang.InterruptedException) {
                                subprocess.destroyAndWait()
                                throw e
                            } catch (e: IOException) {
                                subprocess.destroyAndWait()
                                throw e
                            }
                            if (java.lang.Thread.interrupted()) {
                                stepLog(
                                    java.util.logging.Level.SEVERE,
                                    "Interrupted but didn't throw; status %s",
                                    terminationStatus
                                )
                                throw java.lang.InterruptedException()
                            }
                        }
                } catch (e: InterruptedIOException) {
                    throw java.lang.InterruptedException(e.getMessage())
                } catch (e: IOException) {
                    val msg: String? = if (e.getMessage() == null) e.getClass().getName() else e.getMessage()
                    setState(com.google.devtools.build.lib.exec.local.LocalSpawnRunner.State.PERMANENT_ERROR)
                    outErr
                        .getErrorStream()
                        .write(
                            ("Action failed to execute: java.io.IOException: " + msg + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        )
                    outErr.getErrorStream().flush()
                    spawnMetrics.setTotalTime(totalTimeStopwatch.elapsed())
                    return spawnResultBuilder
                        .setStatus(Status.EXECUTION_FAILED)
                        .setExitCode(LOCAL_EXEC_ERROR)
                        .setFailureDetail(
                            makeFailureDetail(LOCAL_EXEC_ERROR, Status.EXECUTION_FAILED, actionType)
                        )
                        .setSpawnMetrics(spawnMetrics.build())
                        .build()
                }
                setState(com.google.devtools.build.lib.exec.local.LocalSpawnRunner.State.SUCCESS)
                // TODO(b/62588075): Calculate wall time inside commands instead?
                val wallTime: java.time.Duration = executionStopwatch.elapsed()
                spawnMetrics.setExecutionWallTime(wallTime)

                val wasTimeout =
                    terminationStatus.timedOut()
                            || (processWrapper != null && wasTimeout(context.getTimeout(), wallTime))
                val exitCode: Int =
                    if (wasTimeout) SpawnResult.POSIX_TIMEOUT_EXIT_CODE else terminationStatus.getRawExitCode()
                val status: Status =
                    if (wasTimeout) Status.TIMEOUT else (if (exitCode == 0) Status.SUCCESS else Status.NON_ZERO_EXIT)
                spawnResultBuilder
                    .setStatus(status)
                    .setExitCode(exitCode)
                    .setWallTimeInMs(wallTime.toMillis().toInt())
                if (status !== Status.SUCCESS) {
                    spawnResultBuilder.setFailureDetail(makeFailureDetail(exitCode, status, actionType))
                }
                if (statisticsPath != null) {
                    spawnResultBuilder.setResourceUsageFromProto(statisticsPath)
                }
                spawnMetrics.setTotalTime(totalTimeStopwatch.elapsed())
                spawnResultBuilder.setSpawnMetrics(spawnMetrics.build())
                return spawnResultBuilder.build()
            } finally {
                // Delete the temp directory tree, so the next action that this thread executes will get a
                // fresh, empty temp directory.
                // File deletion tends to be slow on Windows, so deleting this tree may take several
                // seconds. Delete it after having measured the wallTime.
                try {
                    tmpDir.deleteTree()
                } catch (ignored: IOException) {
                    // We can't handle this exception in any meaningful way, nor should we, but let's log it.
                    stepLog(
                        java.util.logging.Level.WARNING,
                        ("failed to delete temp directory '%s'; this might indicate that the action "
                                + "created subprocesses that didn't terminate and hold files open in that "
                                + "directory"),
                        tmpDir
                    )
                }
            }
        }

        /**
         * Clean up any known side-effects that the running spawn may have had on the output tree.
         * 
         * 
         * This is supposed to leave the output tree as it was right after [ ] created the output directories
         * for the spawn, which means that any outputs have to be deleted but any top-level directory
         * for tree artifacts has to be kept behind (and empty).
         */
        fun maybeCleanupOnInterrupt() {
            if (!localExecutionOptions.getLocalLockfreeOutput()) {
                // If we don't allow lockfree executions of local subprocesses, there is no need to clean up
                // anything: we would have already locked the output tree upfront, so we "own" it.
                return
            }
            if (!needCleanup) {
                // If the subprocess has not yet started, there is no need to worry about checking on-disk
                // state.
                return
            }

            for (output in spawn.getOutputFiles()) {
                val path: com.google.devtools.build.lib.vfs.Path = context.getPathResolver().toPath(output)
                try {
                    if (path.exists()) {
                        stepLog(java.util.logging.Level.INFO, "Clearing output %s after interrupt", path)
                        if (output is Artifact && (output as Artifact).isTreeArtifact()) {
                            path.deleteTreesBelow()
                        } else {
                            path.deleteTree()
                        }
                    }
                } catch (e: IOException) {
                    stepLog(java.util.logging.Level.SEVERE, e, "Cannot delete local output %s after interrupt", path)
                }
            }
        }

        companion object {
            private fun wasTimeout(timeout: java.time.Duration, wallTime: java.time.Duration): Boolean {
                return !timeout.isZero() && wallTime.compareTo(timeout) > 0
            }
        }
    }

    private enum class State {
        INITIALIZING,
        PARSING,
        PREFETCHING_LOCAL_INPUTS,
        LOCAL_ACTION_RUNNING,
        PERMANENT_ERROR,
        SUCCESS
    }

    companion object {
        private val SPACE_JOINER: com.google.common.base.Joiner = com.google.common.base.Joiner.on(' ')
        private const val UNHANDLED_EXCEPTION_MSG = "Unhandled exception running a local spawn"
        private val LOCAL_EXEC_ERROR = -1

        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private fun makeFailureDetail(exitCode: Int, status: Status, actionType: String?): FailureDetail {
            val spawnFailure: FailureDetails.Spawn.Builder = FailureDetails.Spawn.newBuilder()
            when (status) {
                SUCCESS -> throw java.lang.AssertionError("makeFailureDetail() called with Status == SUCCESS")
                NON_ZERO_EXIT -> spawnFailure.setCode(Code.NON_ZERO_EXIT).setSpawnExitCode(exitCode)
                TIMEOUT -> spawnFailure.setCode(Code.TIMEOUT)
                OUT_OF_MEMORY -> spawnFailure.setCode(Code.OUT_OF_MEMORY)
                EXECUTION_FAILED -> spawnFailure.setCode(Code.EXECUTION_FAILED)
                EXECUTION_FAILED_CATASTROPHICALLY -> spawnFailure.setCode(Code.EXECUTION_FAILED).setCatastrophic(true)
                EXECUTION_DENIED -> spawnFailure.setCode(Code.EXECUTION_DENIED)
                EXECUTION_DENIED_CATASTROPHICALLY -> spawnFailure.setCode(Code.EXECUTION_DENIED).setCatastrophic(true)
                REMOTE_CACHE_FAILED -> spawnFailure.setCode(Code.REMOTE_CACHE_FAILED)
            }
            return FailureDetail.newBuilder()
                .setMessage("local spawn failed for " + actionType)
                .setSpawn(spawnFailure)
                .build()
        }
    }
}
