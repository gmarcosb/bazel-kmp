// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.devtools.build.lib.actions.FullSpawnMetrics
import com.google.devtools.build.lib.actions.SpawnMetrics
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import java.util.HashMap
import java.util.Locale

/** Timing, size, and memory statistics for a Spawn execution.  */
// Use ints instead of Durations to improve build time (cl/505728570)
open class SpawnMetrics internal constructor(builder: Builder) {
    /** Indicates whether the metrics correspond to the remote, local or worker execution.  */
    enum class ExecKind(name: String) {
        REMOTE("Remote"),
        LOCAL("Local"),
        WORKER("Worker"),

        /**
         * Other kinds of execution (or when it's not clear whether something happened locally or
         * remotely).
         */
        OTHER("Other");

        private val name: String?

        init {
            this.name = name
        }

        override fun toString(): String {
            return name!!
        }
    }

    private val execKind: ExecKind?
    private val totalTimeInMs: Int
    private val parseTimeInMs: Int
    private val fetchTimeInMs: Int
    private val queueTimeInMs: Int
    private val uploadTimeInMs: Int
    private val setupTimeInMs: Int
    private val executionWallTimeInMs: Int
    private val processOutputsTimeInMs: Int
    private val networkTimeInMs: Int

    // error code to duration in ms
    private val retryTimeInMs: com.google.common.collect.ImmutableMap<Int?, Int?>
    private val inputBytes: Long
    private val inputFiles: Long
    private val memoryEstimateBytes: Long

    init {
        this.execKind = builder.execKind
        this.totalTimeInMs = builder.totalTimeInMs
        this.parseTimeInMs = builder.parseTimeInMs
        this.networkTimeInMs = builder.networkTimeInMs
        this.fetchTimeInMs = builder.fetchTimeInMs
        this.queueTimeInMs = builder.queueTimeInMs
        this.setupTimeInMs = builder.setupTimeInMs
        this.uploadTimeInMs = builder.uploadTimeInMs
        this.executionWallTimeInMs = builder.executionWallTimeInMs
        this.retryTimeInMs = com.google.common.collect.ImmutableMap.copyOf<Int?, Int?>(builder.retryTimeInMs)
        this.processOutputsTimeInMs = builder.processOutputsTimeInMs
        this.inputBytes = builder.inputBytes
        this.inputFiles = builder.inputFiles
        this.memoryEstimateBytes = builder.memoryEstimateBytes
    }

    /**
     * Generates a String representation of the stats.
     * 
     * @param total total time in milliseconds used to compute the percentages
     * @param summary whether to exclude input file count and sizes, and memory estimates
     */
    fun toString(total: Int, summary: Boolean): String {
        val sb: java.lang.StringBuilder = java.lang.StringBuilder()
        sb.append("(")
        sb.append(prettyPercentage(totalTimeInMs, total))
        sb.append(" of the time): [")
        val stats: MutableList<String?> = java.util.ArrayList<String?>(8)
        addStatToString(stats, "parse", !summary, parseTimeInMs, total)
        addStatToString(stats, "queue", true, queueTimeInMs, total)
        addStatToString(stats, "network", !summary, networkTimeInMs, total)
        addStatToString(stats, "upload", !summary, uploadTimeInMs, total)
        addStatToString(stats, "setup", true, setupTimeInMs, total)
        addStatToString(stats, "process", true, executionWallTimeInMs, total)
        addStatToString(stats, "fetch", !summary, fetchTimeInMs, total)
        addStatToString(stats, "retry", !summary, retryTimeInMs(), total)
        addStatToString(stats, "processOutputs", !summary, processOutputsTimeInMs, total)
        addStatToString(stats, "other", !summary, otherTimeInMs(), total)
        if (!summary) {
            stats.add("input files: " + inputFiles)
            stats.add("input bytes: " + inputBytes)
            stats.add("memory bytes: " + memoryEstimateBytes)
        }
        com.google.common.base.Joiner.on(", ").appendTo(sb, stats)
        sb.append("]")
        return sb.toString()
    }

    /** The kind of execution the metrics refer to (remote/local/worker).  */
    fun execKind(): ExecKind? {
        return execKind
    }

    /** Returns true if [.totalTimeInMs] is zero.  */
    fun isEmpty(): Boolean {
        return totalTimeInMs == 0
    }

    /**
     * Total (measured locally) wall time in milliseconds spent running a spawn. This should be at
     * least as large as all the other times summed together.
     */
    fun totalTimeInMs(): Int {
        return totalTimeInMs
    }

    /**
     * Total time in milliseconds spent getting on network. This includes time getting network-side
     * errors and the time of the round-trip, found by taking the difference of wall time here and the
     * server time reported by the RPC. This is 0 for locally executed spawns.
     */
    fun networkTimeInMs(): Int {
        return networkTimeInMs
    }

    /** Total time in milliseconds waiting in queues. Includes queue time for any failed attempts.  */
    fun queueTimeInMs(): Int {
        return queueTimeInMs
    }

    /**
     * The time in milliseconds spent transferring files to the backends. This is 0 for locally
     * executed spawns.
     */
    fun uploadTimeInMs(): Int {
        return uploadTimeInMs
    }

    /**
     * The time in milliseconds required to setup the environment in which the spawn is run. This may
     * be 0 for locally executed spawns, or may include time to setup a sandbox or other environment.
     * Does not include failed attempts.
     */
    fun setupTimeInMs(): Int {
        return setupTimeInMs
    }

    /** Time spent running the subprocess.  */
    fun executionWallTimeInMs(): Int {
        return executionWallTimeInMs
    }

    /**
     * The time in milliseconds taken to convert the spawn into a network request, e.g., collecting
     * runfiles, and digests for all input files.
     */
    fun parseTimeInMs(): Int {
        return parseTimeInMs
    }

    /** Total time in milliseconds spent fetching remote outputs.  */
    fun fetchTimeInMs(): Int {
        return fetchTimeInMs
    }

    /** Time spent in previous failed attempts. Does not include queue time.  */
    fun retryTimeInMs(): Int {
        return retryTimeInMs.values.stream().reduce(0) { a: Int, b: Int -> java.lang.Integer.sum(a, b) }
    }

    /** Time spent in previous failed attempts, keyed by error code. Does not include queue time.  */
    fun retryTimeByError(): MutableMap<Int?, Int?> {
        return retryTimeInMs
    }

    /** Time spend by the execution framework on processing outputs.  */
    fun processOutputsTimeInMs(): Int {
        return processOutputsTimeInMs
    }

    /**
     * Any time in milliseconds that is not measured by a more specific component, out of `totalTime()`.
     */
    fun otherTimeInMs(): Int {
        return (totalTimeInMs
                - parseTimeInMs
                - networkTimeInMs
                - queueTimeInMs
                - uploadTimeInMs
                - setupTimeInMs
                - executionWallTimeInMs
                - fetchTimeInMs
                - retryTimeInMs()
                - processOutputsTimeInMs)
    }

    /** Total size in bytes of inputs or 0 if unavailable.  */
    fun inputBytes(): Long {
        return inputBytes
    }

    /** Total number of input files or 0 if unavailable.  */
    fun inputFiles(): Long {
        return inputFiles
    }

    /** Estimated memory usage or 0 if unavailable.  */
    fun memoryEstimate(): Long {
        return memoryEstimateBytes
    }

    /** Limit of total size in bytes of inputs or 0 if unavailable.  */
    open fun inputBytesLimit(): Long {
        return 0
    }

    /** Limit of total number of input files or 0 if unavailable.  */
    open fun inputFilesLimit(): Long {
        return 0
    }

    /** Limit of total size in bytes of outputs or 0 if unavailable.  */
    open fun outputBytesLimit(): Long {
        return 0
    }

    /** Limit of total number of output files or 0 if unavailable.  */
    open fun outputFilesLimit(): Long {
        return 0
    }

    /** Memory limit or 0 if unavailable.  */
    open fun memoryLimit(): Long {
        return 0
    }

    /** Time limit in milliseconds or 0 if unavailable.  */
    open fun timeLimitInMs(): Int {
        return 0
    }

    /** Builder class for SpawnMetrics.  */
    class Builder  // Make the constructor private to force users to set the ExecKind by using one of the factory
    // methods.
    private constructor() {
        private var execKind: ExecKind? = null
        private var totalTimeInMs = 0
        private var parseTimeInMs = 0
        private var networkTimeInMs = 0
        private var fetchTimeInMs = 0
        private var queueTimeInMs = 0
        private var setupTimeInMs = 0
        private var uploadTimeInMs = 0
        private var executionWallTimeInMs = 0
        private var processOutputsTimeInMs = 0
        private var retryTimeInMs: MutableMap<Int?, Int?> = HashMap<Int?, Int?>()
        private var inputBytes: Long = 0
        private var inputFiles: Long = 0
        private var memoryEstimateBytes: Long = 0
        var inputBytesLimit: Long = 0
        var inputFilesLimit: Long = 0
        var outputBytesLimit: Long = 0
        var outputFilesLimit: Long = 0
        var memoryBytesLimit: Long = 0
        var timeLimitInMs: Int = 0

        fun build(): SpawnMetrics {
            com.google.common.base.Preconditions.checkNotNull<ExecKind?>(
                execKind,
                "ExecKind must be explicitly set using `setExecKind`"
            )
            // TODO(ulfjack): Add consistency checks here?
            if (inputBytesLimit == 0L && inputFilesLimit == 0L && outputBytesLimit == 0L && outputFilesLimit == 0L && memoryBytesLimit == 0L && timeLimitInMs == 0) {
                return SpawnMetrics(this)
            }
            return FullSpawnMetrics(this)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecKind(execKind: ExecKind?): Builder {
            this.execKind = execKind
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setTotalTime(totalTime: java.time.Duration): Builder {
            return setTotalTimeInMs(com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.toMs(totalTime))
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setTotalTimeInMs(totalTimeInMs: Int): Builder {
            this.totalTimeInMs = totalTimeInMs
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setParseTime(parseTime: java.time.Duration): Builder {
            return setParseTimeInMs(com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.toMs(parseTime))
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setParseTimeInMs(parseTimeInMs: Int): Builder {
            this.parseTimeInMs = parseTimeInMs
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setNetworkTime(networkTime: java.time.Duration): Builder {
            return setNetworkTimeInMs(
                com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.toMs(
                    networkTime
                )
            )
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setNetworkTimeInMs(networkTimeInMs: Int): Builder {
            this.networkTimeInMs = networkTimeInMs
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setFetchTime(fetchTime: java.time.Duration): Builder {
            return setFetchTimeInMs(com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.toMs(fetchTime))
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setFetchTimeInMs(fetchTimeInMs: Int): Builder {
            this.fetchTimeInMs = fetchTimeInMs
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setQueueTime(queueTime: java.time.Duration): Builder {
            return setQueueTimeInMs(com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.toMs(queueTime))
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setQueueTimeInMs(queueTimeInMs: Int): Builder {
            this.queueTimeInMs = queueTimeInMs
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addQueueTime(queueTime: java.time.Duration): Builder {
            return addQueueTimeInMs(com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.toMs(queueTime))
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addQueueTimeInMs(queueTimeInMs: Int): Builder {
            this.queueTimeInMs += queueTimeInMs
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setSetupTime(setupTime: java.time.Duration): Builder {
            return setSetupTimeInMs(com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.toMs(setupTime))
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setSetupTimeInMs(setupTimeInMs: Int): Builder {
            this.setupTimeInMs = setupTimeInMs
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addSetupTime(setupTime: java.time.Duration): Builder {
            return addSetupTimeInMs(com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.toMs(setupTime))
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addSetupTimeInMs(setupTimeInMs: Int): Builder {
            this.setupTimeInMs += setupTimeInMs
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setUploadTime(uploadTime: java.time.Duration): Builder {
            return setUploadTimeInMs(
                com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.toMs(
                    uploadTime
                )
            )
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setUploadTimeInMs(uploadTimeInMs: Int): Builder {
            this.uploadTimeInMs = uploadTimeInMs
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutionWallTime(executionWallTime: java.time.Duration): Builder {
            return setExecutionWallTimeInMs(
                com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.toMs(
                    executionWallTime
                )
            )
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutionWallTimeInMs(executionWallTimeInMs: Int): Builder {
            this.executionWallTimeInMs = executionWallTimeInMs
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addRetryTime(errorCode: Int, retryTime: java.time.Duration): Builder {
            return addRetryTimeInMs(
                errorCode,
                com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.toMs(retryTime)
            )
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addRetryTimeInMs(errorCode: Int, retryTimeInMs: Int): Builder {
            this.retryTimeInMs.merge(errorCode, retryTimeInMs) { a: Int?, b: Int? -> java.lang.Integer.sum(a, b) }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRetryTimeInMs(retryTimeInMs: com.google.common.collect.ImmutableMap<Int?, Int?>): Builder {
            this.retryTimeInMs = retryTimeInMs
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setProcessOutputsTime(processOutputsTime: java.time.Duration): Builder {
            return setProcessOutputsTimeInMs(
                com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.toMs(
                    processOutputsTime
                )
            )
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setProcessOutputsTimeInMs(processOutputsTimeInMs: Int): Builder {
            this.processOutputsTimeInMs = processOutputsTimeInMs
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setInputBytes(inputBytes: Long): Builder {
            this.inputBytes = inputBytes
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setInputFiles(inputFiles: Long): Builder {
            this.inputFiles = inputFiles
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setMemoryEstimateBytes(memoryEstimateBytes: Long): Builder {
            this.memoryEstimateBytes = memoryEstimateBytes
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setInputBytesLimit(inputBytesLimit: Long): Builder {
            this.inputBytesLimit = inputBytesLimit
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setInputFilesLimit(inputFilesLimit: Long): Builder {
            this.inputFilesLimit = inputFilesLimit
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setOutputBytesLimit(outputBytesLimit: Long): Builder {
            this.outputBytesLimit = outputBytesLimit
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setOutputFilesLimit(outputFilesLimit: Long): Builder {
            this.outputFilesLimit = outputFilesLimit
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setMemoryBytesLimit(memoryBytesLimit: Long): Builder {
            this.memoryBytesLimit = memoryBytesLimit
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setTimeLimitInMs(timeLimitInMs: Int): Builder {
            this.timeLimitInMs = timeLimitInMs
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDurations(metric: SpawnMetrics): Builder {
            totalTimeInMs += metric.totalTimeInMs()
            parseTimeInMs += metric.parseTimeInMs()
            networkTimeInMs += metric.networkTimeInMs()
            fetchTimeInMs += metric.fetchTimeInMs()
            queueTimeInMs += metric.queueTimeInMs()
            uploadTimeInMs += metric.uploadTimeInMs()
            setupTimeInMs += metric.setupTimeInMs()
            executionWallTimeInMs += metric.executionWallTimeInMs()
            for (entry in metric.retryTimeInMs.entries) {
                addRetryTimeInMs(entry.key, entry.value)
            }
            processOutputsTimeInMs += metric.processOutputsTimeInMs()
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addNonDurations(metric: SpawnMetrics): Builder {
            inputFiles += metric.inputFiles()
            inputBytes += metric.inputBytes()
            memoryEstimateBytes += metric.memoryEstimate()
            inputFilesLimit += metric.inputFilesLimit()
            inputBytesLimit += metric.inputBytesLimit()
            outputFilesLimit += metric.outputFilesLimit()
            outputBytesLimit += metric.outputBytesLimit()
            memoryBytesLimit += metric.memoryLimit()
            timeLimitInMs += metric.timeLimitInMs()
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun maxNonDurations(metric: SpawnMetrics): Builder {
            inputFiles = java.lang.Long.max(inputFiles, metric.inputFiles())
            inputBytes = java.lang.Long.max(inputBytes, metric.inputBytes())
            memoryEstimateBytes = java.lang.Long.max(memoryEstimateBytes, metric.memoryEstimate())
            inputFilesLimit = java.lang.Long.max(inputFilesLimit, metric.inputFilesLimit())
            inputBytesLimit = java.lang.Long.max(inputBytesLimit, metric.inputBytesLimit())
            outputFilesLimit = java.lang.Long.max(outputFilesLimit, metric.outputFilesLimit())
            outputBytesLimit = java.lang.Long.max(outputBytesLimit, metric.outputBytesLimit())
            memoryBytesLimit = java.lang.Long.max(memoryBytesLimit, metric.memoryLimit())
            timeLimitInMs = java.lang.Integer.max(timeLimitInMs, metric.timeLimitInMs())
            return this
        }

        companion object {
            fun forLocalExec(): Builder {
                return com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.forExec(ExecKind.LOCAL)
            }

            fun forRemoteExec(): Builder {
                return com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.forExec(ExecKind.REMOTE)
            }

            fun forWorkerExec(): Builder {
                return com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.forExec(ExecKind.WORKER)
            }

            fun forOtherExec(): Builder {
                return com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.forExec(ExecKind.OTHER)
            }

            fun forExec(kind: ExecKind?): Builder {
                return com.google.devtools.build.lib.actions.SpawnMetrics.Builder().setExecKind(kind)
            }

            private fun toMs(duration: java.time.Duration): Int {
                return com.google.common.primitives.Ints.saturatedCast(duration.toMillis())
            }
        }
    }

    companion object {
        /** Any non-important stats < than 10% will not be shown in the summary.  */
        private const val STATS_SHOW_THRESHOLD = 0.10

        fun forLocalExecution(wallTimeInMs: Int): SpawnMetrics {
            return com.google.devtools.build.lib.actions.SpawnMetrics.Builder.Companion.forLocalExec()
                .setTotalTimeInMs(wallTimeInMs)
                .setExecutionWallTimeInMs(wallTimeInMs)
                .build()
        }

        /**
         * Add to `strings` the string representation of `name` component. If `forceShow` is set to false it will only show if it is above certain threshold.
         */
        private fun addStatToString(
            strings: MutableList<String?>, name: String?, forceShow: Boolean, time: Int, totalTime: Int
        ) {
            if (forceShow || isAboveThreshold(time, totalTime)) {
                strings.add(name + ": " + prettyPercentage(time, totalTime))
            }
        }

        private fun isAboveThreshold(time: Int, totalTime: Int): Boolean {
            return totalTime > 0 && ((time.toFloat() / totalTime) >= STATS_SHOW_THRESHOLD)
        }

        /**
         * Converts relative duration to the percentage string.
         * 
         * @return formatted percentage string or "N/A" if result is undefined
         */
        private fun prettyPercentage(duration: Int, total: Int): String? {
            // Duration.toMillis() != 0 does not imply !Duration.isZero() (due to truncation).
            if (total == 0) {
                // Return "not available" string if total is 0 and result is undefined.
                return "N/A"
            }
            return String.format(Locale.US, "%.2f%%", duration * 100.0 / total)
        }
    }
}
