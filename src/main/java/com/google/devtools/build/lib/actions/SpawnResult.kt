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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable

/** The result of a [Spawn]'s execution.  */
// Use ints instead of Durations to improve build time (cl/505728570)
interface SpawnResult {
    /** The status of the attempted Spawn execution.  */
    enum class Status @kotlin.jvm.JvmOverloads constructor(@kotlin.jvm.JvmField private val isUserError: Boolean = false) {
        /** Subprocess executed successfully, and returned a zero exit code.  */
        SUCCESS,

        /** Subprocess executed successfully, but returned a non-zero exit code.  */
        NON_ZERO_EXIT(true),

        /** Subprocess execution timed out.  */
        TIMEOUT(true),

        /**
         * The subprocess ran out of memory. On Linux systems, the kernel may kill processes in
         * low-memory situations, and this status is intended to report such a case back to Bazel.
         */
        OUT_OF_MEMORY(true),

        /**
         * Subprocess did not execute, it's not the user's fault, and the error is not catastrophic. If
         * keep_going is enabled then Bazel will try to continue the build, possibly will attempt to
         * rerun the same spawn, and possibly will attempt to run other actions.
         */
        EXECUTION_FAILED,

        /**
         * Subprocess did not execute, it's not the user's fault, and the error is catastrophic. Bazel
         * will not rerun this spawn. Bazel will attempt to not run other actions (regardless of whether
         * keep_going is enabled).
         */
        EXECUTION_FAILED_CATASTROPHICALLY,

        /**
         * Subprocess did not execute, it may be the user's fault, and the error is not catastrophic.
         * The user may be able to fix it. For example, a remote system may have denied the execution
         * due to too many inputs or too large inputs.
         */
        EXECUTION_DENIED(true),

        /**
         * Subprocess did not execute, it may be the user's fault, and the error is catastrophic. The
         * user may be able to prevent it from reoccurring. For example, an input file's contents may
         * have been modified by the user intra-build.
         */
        EXECUTION_DENIED_CATASTROPHICALLY(true),

        /**
         * The result of the remotely executed Spawn could not be retrieved due to errors in the remote
         * caching layer.
         */
        REMOTE_CACHE_FAILED;

        fun isConsideredUserError(): Boolean {
            return isUserError
        }
    }

    /**
     * Returns whether the spawn was actually run, regardless of the exit code. I.e., returns `true` if [.status] is any of [Status.SUCCESS], [Status.NON_ZERO_EXIT], [ ][Status.TIMEOUT] or [Status.OUT_OF_MEMORY].
     * 
     * 
     * Returns false if there were errors that prevented the spawn from being run, such as network
     * errors, missing local files, errors setting up sandboxing, etc.
     */
    fun setupSuccess(): Boolean {
        val status = status()
        return status == com.google.devtools.build.lib.actions.SpawnResult.Status.SUCCESS || status == com.google.devtools.build.lib.actions.SpawnResult.Status.NON_ZERO_EXIT || status == com.google.devtools.build.lib.actions.SpawnResult.Status.TIMEOUT || status == com.google.devtools.build.lib.actions.SpawnResult.Status.OUT_OF_MEMORY
    }

    /**
     * Returns true if the status was [Status.EXECUTION_FAILED_CATASTROPHICALLY] or [ ][Status.EXECUTION_DENIED_CATASTROPHICALLY].
     */
    fun isCatastrophe(): Boolean {
        return status() == com.google.devtools.build.lib.actions.SpawnResult.Status.EXECUTION_FAILED_CATASTROPHICALLY
                || status() == com.google.devtools.build.lib.actions.SpawnResult.Status.EXECUTION_DENIED_CATASTROPHICALLY
    }

    /** Returns the status of the attempted Spawn execution.  */
    fun status(): Status?

    /**
     * Returns the exit code of the subprocess if the subprocess was executed.
     * 
     * 
     * Returns zero if [.status] returns [Status.SUCCESS].
     * 
     * 
     * Returns non-zero if [.status] returns [Status.NON_ZERO_EXIT] or [ ][Status.OUT_OF_MEMORY].
     * 
     * 
     * Returns 128 + 14 (corresponding to the Unix signal SIGALRM) if [.status] returns
     * [Status.TIMEOUT].
     * 
     * 
     * Otherwise, the returned value is not meaningful.
     */
    // TODO(mschaller): clean up all uses of this method when {@code !this.setupSuccess()}
    fun exitCode(): Int

    /**
     * A detailed representation of what failed if [.status] is not [Status.SUCCESS], and
     * `null` otherwise.
     */
    fun failureDetail(): FailureDetail?

    /**
     * Returns the host name of the executor or `null`. This information is intended for
     * debugging purposes, especially for remote execution systems. Remote caches usually do not store
     * the original host name, so this is generally `null` for cache hits.
     */
    fun getExecutorHostName(): String?

    /**
     * Returns the name of the SpawnRunner that executed the spawn. It should always be defined,
     * unless isCacheHit is true, in which case the spawn was not actually run.
     */
    fun getRunnerName(): String?

    /** Returns optional details about the runner.  */
    fun getRunnerSubtype(): String?

    /**
     * Returns the start time for the [Spawn]'s execution.
     * 
     * @return the measurement, or null in case of execution errors or when the measurement is not
     * implemented for the current platform
     */
    fun getStartTime(): Instant?

    /**
     * Returns the wall time taken by the [Spawn]'s execution.
     * 
     * @return the measurement, or 0 in case of execution errors or when the measurement is not
     * implemented for the current platform
     */
    fun getWallTimeInMs(): Int

    /**
     * Returns the user time taken by the [Spawn]'s execution.
     * 
     * @return the measurement, or 0 in case of execution errors or when the measurement is not
     * implemented for the current platform
     */
    fun getUserTimeInMs(): Int

    /**
     * Returns the system time taken by the [Spawn]'s execution.
     * 
     * @return the measurement, or 0 in case of execution errors or when the measurement is not
     * implemented for the current platform
     */
    fun getSystemTimeInMs(): Int

    /**
     * Returns the number of block output operations during the [Spawn]'s execution.
     * 
     * @return the measurement, or null in case of execution errors or when the measurement is not
     * implemented for the current platform
     */
    fun getNumBlockOutputOperations(): Long?

    /**
     * Returns the number of block input operations during the [Spawn]'s execution.
     * 
     * @return the measurement, or null in case of execution errors or when the measurement is not
     * implemented for the current platform
     */
    fun getNumBlockInputOperations(): Long?

    /**
     * Returns the number of involuntary context switches during the [Spawn]'s execution.
     * 
     * @return the measurement, or null in case of execution errors or when the measurement is not
     * implemented for the current platform
     */
    fun getNumInvoluntaryContextSwitches(): Long?

    /**
     * Returns the memory in Kilobytes used during the [Spawn]'s execution. The spawn memory
     * based on the maximum resident set size during command execution.
     * 
     * @return the measurement, or null in case of execution errors or when the measurement is not
     * implemented for the current platform
     */
    // TODO(b/181317827) implement for windows systems.
    fun getMemoryInKb(): Long?

    fun getMetrics(): SpawnMetrics?

    /** Returns whether the spawn result was a cache hit.  */
    fun isCacheHit(): Boolean

    /** Returns an optional custom failure message for the result.  */
    fun getFailureMessage(): String? {
        return ""
    }

    /**
     * Returns a [Spawn]'s output in-memory, if supported and available.
     * 
     * 
     * This behavior may be triggered with [ ][ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS].
     */
    fun getInMemoryOutput(output: ActionInput?): ByteString? {
        return null
    }

    fun getDetailMessage(message: String?, catastrophe: Boolean, forciblyRunRemotely: Boolean): String?

    /** Whether the spawn result was obtained through remote strategy.  */
    fun wasRemote(): Boolean

    /**
     * Returns the remote or disk cache digest.
     * 
     * 
     * Only available when remote execution, remote cache or disk cache was enabled for the spawn.
     */
    fun getDigest(): Digest? {
        return null
    }

    /** Basic implementation of [SpawnResult].  */
    @Immutable
    @ThreadSafe
    class SimpleSpawnResult internal constructor(builder: Builder) : SpawnResult {
        private val exitCode: Int
        private val status: Status
        private val failureDetail: FailureDetail?
        private val executorHostName: String?
        private val runnerName: String?
        private val runnerSubtype: String?
        private val spawnMetrics: SpawnMetrics?
        private val startTime: Instant?
        private val wallTimeInMs: Int
        private val userTimeInMs: Int
        private val systemTimeInMs: Int
        private val numBlockOutputOperations: Long?
        private val numBlockInputOperations: Long?
        private val numInvoluntaryContextSwitches: Long?
        private val memoryKb: Long?
        private val cacheHit: Boolean
        private val failureMessage: String?

        // Invariant: Either both have a value or both are null.
        private val inMemoryOutputFile: ActionInput?
        private val inMemoryContents: ByteString?

        private val remote: Boolean
        private val digest: Digest?

        init {
            this.exitCode = builder.exitCode
            this.status = com.google.common.base.Preconditions.checkNotNull<Status>(builder.status)
            this.failureDetail = builder.failureDetail
            this.executorHostName = builder.executorHostName
            this.runnerName = builder.runnerName
            this.runnerSubtype = builder.runnerSubtype
            this.spawnMetrics =
                if (builder.spawnMetrics != null)
                    builder.spawnMetrics
                else
                    SpawnMetrics.Companion.forLocalExecution(builder.wallTimeInMs)
            this.startTime = builder.startTime
            this.wallTimeInMs = builder.wallTimeInMs
            this.userTimeInMs = builder.userTimeInMs
            this.systemTimeInMs = builder.systemTimeInMs
            this.numBlockOutputOperations = builder.numBlockOutputOperations
            this.numBlockInputOperations = builder.numBlockInputOperations
            this.numInvoluntaryContextSwitches = builder.numInvoluntaryContextSwitches
            this.memoryKb = builder.memoryInKb
            this.cacheHit = builder.cacheHit
            this.failureMessage = builder.failureMessage
            this.inMemoryOutputFile = builder.inMemoryOutputFile
            this.inMemoryContents = builder.inMemoryContents
            this.remote = builder.remote
            this.digest = builder.digest
        }

        override fun exitCode(): Int {
            return exitCode
        }

        override fun status(): Status {
            return status
        }

        override fun failureDetail(): FailureDetail? {
            return failureDetail
        }

        override fun getExecutorHostName(): String? {
            return executorHostName
        }

        override fun getRunnerName(): String? {
            return runnerName
        }

        override fun getRunnerSubtype(): String? {
            return runnerSubtype
        }

        override fun getMetrics(): SpawnMetrics? {
            return spawnMetrics
        }

        override fun getStartTime(): Instant? {
            return startTime
        }

        override fun getWallTimeInMs(): Int {
            return wallTimeInMs
        }

        override fun getUserTimeInMs(): Int {
            return userTimeInMs
        }

        override fun getSystemTimeInMs(): Int {
            return systemTimeInMs
        }

        override fun getNumBlockOutputOperations(): Long? {
            return numBlockOutputOperations
        }

        override fun getNumBlockInputOperations(): Long? {
            return numBlockInputOperations
        }

        override fun getNumInvoluntaryContextSwitches(): Long? {
            return numInvoluntaryContextSwitches
        }

        override fun getMemoryInKb(): Long? {
            return memoryKb
        }

        override fun isCacheHit(): Boolean {
            return cacheHit
        }

        override fun getFailureMessage(): String? {
            return failureMessage
        }

        override fun getDetailMessage(
            message: String?, catastrophe: Boolean, forciblyRunRemotely: Boolean
        ): String {
            val status: TerminationStatus = TerminationStatus(
                exitCode(),
                status() == com.google.devtools.build.lib.actions.SpawnResult.Status.TIMEOUT
            )
            val reason = "(" + status.toShortString() + ")" // e.g. "(Exit 1)"
            var explanation = if (com.google.common.base.Strings.isNullOrEmpty(message)) "" else ": " + message

            if (status() == com.google.devtools.build.lib.actions.SpawnResult.Status.TIMEOUT) {
                // 0 wall time means no measurement
                if (getWallTimeInMs() != 0) {
                    explanation += String.format(
                        Locale.US,
                        " (failed due to timeout after %.2f seconds.)",
                        getWallTimeInMs() / 1000.0
                    )
                } else {
                    explanation += " (failed due to timeout.)"
                }
            } else if (status() == com.google.devtools.build.lib.actions.SpawnResult.Status.OUT_OF_MEMORY) {
                explanation += " (Remote action was terminated due to Out of Memory.)"
            }
            if (status() != com.google.devtools.build.lib.actions.SpawnResult.Status.TIMEOUT && forciblyRunRemotely) {
                explanation +=
                    (" Action tagged as local was forcibly run remotely and failed - it's "
                            + "possible that the action simply doesn't work remotely")
            }
            return reason + explanation
        }

        override fun getInMemoryOutput(output: ActionInput?): ByteString? {
            if (inMemoryOutputFile != null && inMemoryOutputFile == output) {
                return inMemoryContents
            }
            return null
        }

        override fun wasRemote(): Boolean {
            return remote
        }

        override fun getDigest(): Digest? {
            return digest
        }
    }

    /**
     * A helper class for wrapping an existing [SpawnResult] and modifying a subset of its
     * methods.
     */
    class DelegateSpawnResult(private val delegate: SpawnResult) : SpawnResult {
        override fun setupSuccess(): Boolean {
            return delegate.setupSuccess()
        }

        override fun isCatastrophe(): Boolean {
            return delegate.isCatastrophe()
        }

        override fun status(): Status? {
            return delegate.status()
        }

        override fun exitCode(): Int {
            return delegate.exitCode()
        }

        override fun failureDetail(): FailureDetail? {
            return delegate.failureDetail()
        }

        override fun getExecutorHostName(): String? {
            return delegate.getExecutorHostName()
        }

        override fun getRunnerName(): String? {
            return delegate.getRunnerName()
        }

        override fun getRunnerSubtype(): String? {
            return delegate.getRunnerSubtype()
        }

        override fun getStartTime(): Instant? {
            return delegate.getStartTime()
        }

        override fun getWallTimeInMs(): Int {
            return delegate.getWallTimeInMs()
        }

        override fun getUserTimeInMs(): Int {
            return delegate.getUserTimeInMs()
        }

        override fun getSystemTimeInMs(): Int {
            return delegate.getSystemTimeInMs()
        }

        override fun getNumBlockOutputOperations(): Long? {
            return delegate.getNumBlockOutputOperations()
        }

        override fun getNumBlockInputOperations(): Long? {
            return delegate.getNumBlockInputOperations()
        }

        override fun getNumInvoluntaryContextSwitches(): Long? {
            return delegate.getNumInvoluntaryContextSwitches()
        }

        override fun getMemoryInKb(): Long? {
            return delegate.getMemoryInKb()
        }

        override fun getMetrics(): SpawnMetrics? {
            return delegate.getMetrics()
        }

        override fun isCacheHit(): Boolean {
            return delegate.isCacheHit()
        }

        override fun getFailureMessage(): String? {
            return delegate.getFailureMessage()
        }

        override fun getInMemoryOutput(output: ActionInput?): ByteString? {
            return delegate.getInMemoryOutput(output)
        }

        override fun getDetailMessage(
            message: String?, catastrophe: Boolean, forciblyRunRemotely: Boolean
        ): String? {
            return delegate.getDetailMessage(message, catastrophe, forciblyRunRemotely)
        }

        override fun wasRemote(): Boolean {
            return delegate.wasRemote()
        }

        override fun getDigest(): Digest? {
            return delegate.getDigest()
        }
    }

    /** Builder class for [SpawnResult].  */
    class Builder {
        private var exitCode = 0
        private var status: Status? = null
        private var failureDetail: FailureDetail? = null
        private var executorHostName: String? = null
        private var runnerName = ""
        private val runnerSubtype = ""
        private var spawnMetrics: SpawnMetrics? = null
        private var startTime: Instant? = null
        private var wallTimeInMs = 0
        private var userTimeInMs = 0
        private var systemTimeInMs = 0
        private var numBlockOutputOperations: Long? = null
        private var numBlockInputOperations: Long? = null
        private var numInvoluntaryContextSwitches: Long? = null
        private var memoryInKb: Long? = null
        private var cacheHit = false
        private var failureMessage: String? = ""

        // Invariant: Either both have a value or both are null.
        private var inMemoryOutputFile: ActionInput? = null
        private var inMemoryContents: ByteString? = null

        private var remote = false
        private var digest: Digest? = null

        fun build(): SpawnResult {
            com.google.common.base.Preconditions.checkArgument(!runnerName.isEmpty())
            when (status) {
                com.google.devtools.build.lib.actions.SpawnResult.Status.SUCCESS -> {
                    com.google.common.base.Preconditions.checkArgument(exitCode == 0, exitCode)
                    com.google.common.base.Preconditions.checkArgument(failureDetail == null, failureDetail)
                }

                com.google.devtools.build.lib.actions.SpawnResult.Status.TIMEOUT -> {
                    com.google.common.base.Preconditions.checkArgument(exitCode == POSIX_TIMEOUT_EXIT_CODE, exitCode)
                    com.google.common.base.Preconditions.checkArgument(
                        exitCode != 0,
                        "Failed spawn with status %s had exit code 0 (%s %s)",
                        status,
                        failureMessage,
                        failureDetail
                    )
                    com.google.common.base.Preconditions.checkArgument(
                        failureDetail != null,
                        "Failed spawn with status %s and exit code %s had no failure detail (%s)",
                        status,
                        exitCode,
                        failureMessage
                    )
                    if (!status!!.isConsideredUserError()
                        && ExitCode.BUILD_FAILURE.equals(DetailedExitCode.getExitCode(failureDetail))
                    ) {
                        BugReport.sendBugReport(
                            java.lang.IllegalStateException(
                                String.format(
                                    "System error %s should not have failure detail %s with 'build failure'"
                                            + " exit code (%s)",
                                    status, failureDetail, failureMessage
                                )
                            )
                        )
                    }
                }

                else -> {
                    com.google.common.base.Preconditions.checkArgument(
                        exitCode != 0,
                        "Failed spawn with status %s had exit code 0 (%s %s)",
                        status,
                        failureMessage,
                        failureDetail
                    )
                    com.google.common.base.Preconditions.checkArgument(
                        failureDetail != null,
                        "Failed spawn with status %s and exit code %s had no failure detail (%s)",
                        status,
                        exitCode,
                        failureMessage
                    )
                    if (!status!!.isConsideredUserError()
                        && ExitCode.BUILD_FAILURE.equals(DetailedExitCode.getExitCode(failureDetail))
                    ) {
                        BugReport.sendBugReport(
                            java.lang.IllegalStateException(
                                String.format(
                                    "System error %s should not have failure detail %s with 'build failure'"
                                            + " exit code (%s)",
                                    status, failureDetail, failureMessage
                                )
                            )
                        )
                    }
                }
            }

            return SimpleSpawnResult(this)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExitCode(exitCode: Int): Builder {
            this.exitCode = exitCode
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setStatus(status: Status): Builder {
            this.status = status
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setFailureDetail(failureDetail: FailureDetail?): Builder {
            this.failureDetail = failureDetail
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutorHostname(executorHostName: String?): Builder {
            this.executorHostName = executorHostName
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRunnerName(runnerName: String): Builder {
            this.runnerName = runnerName
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setSpawnMetrics(spawnMetrics: SpawnMetrics?): Builder {
            this.spawnMetrics = spawnMetrics
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setStartTime(startTime: Instant?): Builder {
            this.startTime = startTime
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setWallTimeInMs(wallTimeInMs: Int): Builder {
            this.wallTimeInMs = wallTimeInMs
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setUserTimeInMs(userTimeInMs: Int): Builder {
            this.userTimeInMs = userTimeInMs
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setSystemTimeInMs(systemTimeInMs: Int): Builder {
            this.systemTimeInMs = systemTimeInMs
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setNumBlockOutputOperations(numBlockOutputOperations: Long): Builder {
            this.numBlockOutputOperations = numBlockOutputOperations
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setNumBlockInputOperations(numBlockInputOperations: Long): Builder {
            this.numBlockInputOperations = numBlockInputOperations
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setNumInvoluntaryContextSwitches(numInvoluntaryContextSwitches: Long): Builder {
            this.numInvoluntaryContextSwitches = numInvoluntaryContextSwitches
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setMemoryInKb(memoryInKb: Long): Builder {
            this.memoryInKb = memoryInKb
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setCacheHit(cacheHit: Boolean): Builder {
            this.cacheHit = cacheHit
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setFailureMessage(failureMessage: String?): Builder {
            this.failureMessage = failureMessage
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setInMemoryOutput(outputFile: ActionInput?, contents: ByteString?): Builder {
            this.inMemoryOutputFile = com.google.common.base.Preconditions.checkNotNull<ActionInput?>(outputFile)
            this.inMemoryContents = com.google.common.base.Preconditions.checkNotNull<ByteString?>(contents)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRemote(remote: Boolean): Builder {
            this.remote = remote
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setDigest(digest: Digest?): Builder {
            this.digest = digest
            return this
        }

        /** Adds execution statistics based on a `execution_statistics.proto` file.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(IOException::class)
        fun setResourceUsageFromProto(statisticsPath: Path?): Builder {
            ExecutionStatistics.getResourceUsage(statisticsPath)
                .ifPresent(
                    { resourceUsage ->
                        setUserTimeInMs(resourceUsage.getUserExecutionTime().toMillis() as Int)
                        setSystemTimeInMs(resourceUsage.getSystemExecutionTime().toMillis() as Int)
                        setNumBlockOutputOperations(resourceUsage.getBlockOutputOperations())
                        setNumBlockInputOperations(resourceUsage.getBlockInputOperations())
                        setNumInvoluntaryContextSwitches(resourceUsage.getInvoluntaryContextSwitches())
                        // The memory usage of the largest child process. For Darwin maxrss returns size in
                        // bytes.
                        if (OS.getCurrent() === OS.DARWIN) {
                            setMemoryInKb(resourceUsage.getMaximumResidentSetSize() / 1000)
                        } else {
                            setMemoryInKb(resourceUsage.getMaximumResidentSetSize())
                        }
                    })
            return this
        }
    }

    companion object {
        val POSIX_TIMEOUT_EXIT_CODE: Int =  /* SIGNAL_BASE= */128 +  /* SIGALRM= */14
    }
}
