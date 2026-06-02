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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/**
 * Contains information about the result of a build. While BuildRequest is immutable, this class is
 * mutable.
 */
class BuildResult(startTimeMillis: Long) {
    private var startTimeMillis: Long = 0 // milliseconds since UNIX epoch.
    /**
     * Return the time (according to System.currentTimeMillis()) at which the service of this request
     * was completed.
     */
    /**
     * Record the time (according to System.currentTimeMillis()) at which the service of this request
     * was completed.
     */
    var stopTime: Long = 0

    private var crash: Throwable? = null
    private var catastrophe = false
    /** Whether some targets were skipped because of `setStopOnFirstFailure`.  */
    /**
     * Indicates that remaining targets should be skipped once a target breaks/fails. This will be set
     * when --nokeep_going or --notest_keep_going is set.
     */
    @kotlin.jvm.JvmField
    var stopOnFirstFailure: Boolean = false
    private var detailedExitCode: DetailedExitCode? = null

    private var configuration: BuildConfigurationValue? = null
    private var convenienceSymlinks: com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>? =
        com.google.common.collect.ImmutableMap.of<PathFragment?, PathFragment?>()
    private var actualTargets: MutableCollection<ConfiguredTarget?>? = null
    private var testTargets: MutableCollection<ConfiguredTarget?>? = null
    private var successfulTargets: MutableCollection<ConfiguredTarget?>? = null
    private var skippedTargets: MutableCollection<ConfiguredTarget?>? = null
    private var successfulAspects: com.google.common.collect.ImmutableSet<AspectKey?>? = null

    /**
     * Collection of data for the build tool logs event. This may only be modified until the
     * BuildCompleteEvent is posted; any changes after that event is handled will not be included in
     * the build tool logs event.
     */
    @kotlin.jvm.JvmField
    val buildToolLogCollection: BuildToolLogCollection = BuildToolLogCollection()

    private var postBuildCallbackFailureDetail: FailureDetail? = null

    init {
        this.startTimeMillis = startTimeMillis
    }

    val elapsedSeconds: Double
        /**
         * Returns the elapsed time in seconds for the service of this request. Not defined for requests
         * that have not been serviced.
         */
        get() {
            check(!(startTimeMillis == 0L || this.stopTime == 0L)) { "BuildRequest has not been serviced" }
            return (this.stopTime - startTimeMillis) / 1000.0
        }

    fun setDetailedExitCode(detailedExitCode: DetailedExitCode?) {
        this.detailedExitCode = detailedExitCode
    }

    val success: Boolean
        /** True iff the build request has been successfully completed.  */
        get() = detailedExitCode != null && detailedExitCode.isSuccess()

    /**
     * Gets the [DetailedExitCode] containing the [ExitCode] and optional failure detail
     * to complete the command with.
     */
    fun getDetailedExitCode(): DetailedExitCode {
        if (detailedExitCode != null) {
            return detailedExitCode
        }
        return CrashFailureDetails.detailedExitCodeForThrowable(
            java.lang.IllegalStateException("Unspecified DetailedExitCode")
        )
    }

    /** Sets a "catastrophe": A build failure severe enough to halt a keep_going build.  */
    fun setCatastrophe() {
        this.catastrophe = true
    }

    /** Was the build a "catastrophe": A build failure severe enough to halt a keep_going build.  */
    fun wasCatastrophe(): Boolean {
        return catastrophe
    }

    var unhandledThrowable: Throwable?
        /** Gets the Blaze crash Throwable. Null if Blaze did not crash.  */
        get() = crash
        /** Sets the RuntimeException / Error that induced a Blaze crash.  */
        set(crash) {
            com.google.common.base.Preconditions.checkState(
                crash == null || ((crash is java.lang.RuntimeException) || (crash is java.lang.Error))
            )
            this.crash = crash
        }

    fun setBuildConfiguration(configuration: BuildConfigurationValue?) {
        this.configuration = configuration
    }

    val buildConfiguration: BuildConfigurationValue?
        /** Returns the build configuration collection used for the build.  */
        get() = configuration

    fun setConvenienceSymlinks(convenienceSymlinks: com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>?) {
        this.convenienceSymlinks = convenienceSymlinks
    }

    /**
     * Returns the convenience symlinks for this build in name -> target format (eg blaze-out ->
     * /symlink/target).
     */
    fun getConvenienceSymlinks(): com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>? {
        return convenienceSymlinks
    }

    /** @see .getActualTargets
     */
    fun setActualTargets(actualTargets: MutableCollection<ConfiguredTarget?>?) {
        this.actualTargets = actualTargets
    }

    /**
     * Returns the actual set of targets which we attempted to build. This value is set during the
     * build, after the target patterns have been parsed and resolved. If --keep_going is specified,
     * this set may exclude targets that could not be found or successfully analyzed. It may be
     * examined after the build. May be null even after the build, if there were errors in the loading
     * or analysis phases.
     */
    fun getActualTargets(): MutableCollection<ConfiguredTarget?>? {
        return actualTargets
    }

    /** @see .getTestTargets
     */
    fun setTestTargets(testTargets: MutableCollection<ConfiguredTarget?>?) {
        this.testTargets =
            if (testTargets == null) null else Collections.unmodifiableCollection<ConfiguredTarget?>(testTargets)
    }

    /**
     * Returns the actual unmodifiable collection of targets which we attempted to test. This value is
     * set at the end of the build analysis phase, after the test target patterns have been parsed and
     * resolved. If --keep_going is specified, this collection may exclude targets that could not be
     * found or successfully analyzed. It may be examined after the build. May be null even after the
     * build, if there were errors in the loading or analysis phases or if testing was not requested.
     */
    fun getTestTargets(): MutableCollection<ConfiguredTarget?>? {
        return testTargets
    }

    /** @see .getSuccessfulTargets
     */
    fun setSuccessfulTargets(successfulTargets: MutableCollection<ConfiguredTarget?>?) {
        this.successfulTargets = successfulTargets
    }

    /** See #getSuccessfulAspects  */
    fun setSuccessfulAspects(successfulAspects: com.google.common.collect.ImmutableSet<AspectKey?>?) {
        this.successfulAspects = successfulAspects
    }

    fun setPostBuildCallbackFailureDetail(failureDetail: FailureDetail?) {
        this.postBuildCallbackFailureDetail = failureDetail
    }

    val postBuildCallBackFailureDetail: FailureDetail?
        /** @return only set if build was successful; if callback is successful as well, returns null.
         */
        get() = postBuildCallbackFailureDetail

    /**
     * Returns the set of targets that were successfully built. This value is set at the end of the
     * build, after the target patterns have been parsed and resolved and after attempting to build
     * the targets. If --keep_going is specified, this set may exclude targets that could not be found
     * or successfully analyzed, or could not be built. It may be examined after the build. May be
     * null if the execution phase was not attempted, as may happen if there are errors in the loading
     * phase, for example.
     */
    fun getSuccessfulTargets(): MutableCollection<ConfiguredTarget?>? {
        return successfulTargets
    }

    /**
     * Returns the set of aspects that were successfully built. This value is set at the end of the
     * build, after the target patterns have been parsed and resolved and after attempting to build
     * the targets. If --keep_going is specified, this set may exclude targets that could not be found
     * or successfully analyzed, or could not be built. It may be examined after the build. May be
     * null if the execution phase was not attempted, as may happen if there are errors in the loading
     * phase, for example.
     */
    fun getSuccessfulAspects(): com.google.common.collect.ImmutableSet<AspectKey?>? {
        return successfulAspects
    }

    /** See [.getSkippedTargets].  */
    fun setSkippedTargets(skippedTargets: MutableCollection<ConfiguredTarget?>?) {
        this.skippedTargets = skippedTargets
    }

    /**
     * Returns the set of targets which were skipped (Blaze didn't attempt to execute them) because
     * they're not compatible with the build's target platform.
     */
    fun getSkippedTargets(): MutableCollection<ConfiguredTarget?>? {
        return skippedTargets
    }

    /** For debugging.  */
    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("startTimeMillis", startTimeMillis)
            .add("stopTimeMillis", this.stopTime)
            .add("crash", crash)
            .add("catastrophe", catastrophe)
            .add("detailedExitCode", detailedExitCode)
            .add("actualTargets", actualTargets)
            .add("testTargets", testTargets)
            .add("successfulTargets", successfulTargets)
            .add("buildToolLogCollection", buildToolLogCollection)
            .toString()
    }

    /** Collection of data for the build tool logs event. See [BuildToolLogs] for details.  */
    class BuildToolLogCollection {
        private val directValues: MutableList<com.google.devtools.build.lib.util.Pair<String?, ByteString?>?> =
            java.util.ArrayList<com.google.devtools.build.lib.util.Pair<String?, ByteString?>?>()
        private val futureUris: MutableList<com.google.devtools.build.lib.util.Pair<String?, com.google.common.util.concurrent.ListenableFuture<String?>?>?> =
            java.util.ArrayList<com.google.devtools.build.lib.util.Pair<String?, com.google.common.util.concurrent.ListenableFuture<String?>?>?>()
        private val localFiles: MutableList<LogFileEntry?> = java.util.ArrayList<LogFileEntry?>()
        private var frozen = false

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun freeze(): BuildToolLogCollection {
            frozen = true
            return this
        }

        @com.google.common.annotations.VisibleForTesting
        fun getLocalFiles(): MutableList<LogFileEntry?> {
            return localFiles
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDirectValue(name: String?, data: ByteArray): BuildToolLogCollection {
            com.google.common.base.Preconditions.checkState(!frozen)
            this.directValues.add(
                com.google.devtools.build.lib.util.Pair.of<String?, ByteString?>(
                    name,
                    ByteString.copyFrom(data)
                )
            )
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addUri(name: String?, uri: String?): BuildToolLogCollection {
            com.google.common.base.Preconditions.checkState(!frozen)
            this.futureUris.add(
                com.google.devtools.build.lib.util.Pair.of<String?, com.google.common.util.concurrent.ListenableFuture<String?>?>(
                    name,
                    com.google.common.util.concurrent.Futures.immediateFuture<String?>(uri)
                )
            )
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addUriFuture(
            name: String?,
            uriFuture: com.google.common.util.concurrent.ListenableFuture<String?>?
        ): BuildToolLogCollection {
            com.google.common.base.Preconditions.checkState(!frozen)
            this.futureUris.add(
                com.google.devtools.build.lib.util.Pair.of<String?, com.google.common.util.concurrent.ListenableFuture<String?>?>(
                    name,
                    uriFuture
                )
            )
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addLocalFile(name: String?, path: com.google.devtools.build.lib.vfs.Path?): BuildToolLogCollection {
            return addLocalFile(name, path, LocalFileType.LOG, LocalFileCompression.NONE)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addLocalFile(
            name: String?,
            path: com.google.devtools.build.lib.vfs.Path?,
            localFileType: LocalFileType?,
            compression: LocalFileCompression?
        ): BuildToolLogCollection {
            return addLocalFile(
                name, LocalFile(path, localFileType, compression,  /* artifactMetadata= */null)
            )
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addLocalFile(name: String?, localFile: LocalFile): BuildToolLogCollection {
            var name = name
            com.google.common.base.Preconditions.checkState(!frozen)
            if (localFile.compression == LocalFileCompression.GZIP) {
                name += ".gz"
            }
            this.localFiles.add(LogFileEntry(name, localFile))
            return this
        }

        fun toEvent(): BuildToolLogs {
            com.google.common.base.Preconditions.checkState(frozen)
            return BuildToolLogs(directValues, futureUris, localFiles)
        }

        /** For debugging.  */
        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("directValues", directValues)
                .add("futureUris", futureUris)
                .add("localFiles", localFiles)
                .toString()
        }
    }
}
