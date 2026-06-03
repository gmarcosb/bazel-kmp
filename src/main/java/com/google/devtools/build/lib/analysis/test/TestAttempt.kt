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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.actions.Artifact

/** This event is raised whenever an individual test attempt is completed.  */
class TestAttempt private constructor(
    cachedLocally: Boolean,
    testAction: TestRunnerAction,
    executionInfo: BuildEventStreamProtos.TestResult.ExecutionInfo?,
    attempt: Int,
    status: BlazeTestStatus?,
    statusDetails: String?,
    startTimeMillis: Long,
    durationMillis: Long,
    files: com.google.common.collect.ImmutableMultimap<String?, Path?>?,
    testWarnings: MutableList<String?>?,
    lastAttempt: Boolean
) : BuildEventWithOrderConstraint {
    private val testAction: TestRunnerAction
    private val status: TestStatus?
    private val statusDetails: String?
    private val cachedLocally: Boolean
    private val attempt: Int
    private val lastAttempt: Boolean
    private val files: com.google.common.collect.ImmutableMultimap<String?, Path?>
    private val testWarnings: MutableList<String?>
    private val durationMillis: Long
    private val startTimeMillis: Long
    private val executionInfo: BuildEventStreamProtos.TestResult.ExecutionInfo

    /**
     * Construct the event given the test action and attempt number.
     * 
     * @param cachedLocally True if the reported attempt is taken from the tool's local cache.
     * @param testAction The test that was run.
     * @param attempt The number of the attempt for this action.
     */
    init {
        this.testAction = testAction
        this.executionInfo =
            com.google.common.base.Preconditions.checkNotNull<BuildEventStreamProtos.TestResult.ExecutionInfo>(
                executionInfo
            )
        this.attempt = attempt
        this.status = BuildEventStreamerUtils.bepStatus(com.google.common.base.Preconditions.checkNotNull<T?>(status))
        this.statusDetails = statusDetails
        this.cachedLocally = cachedLocally
        this.startTimeMillis = startTimeMillis
        this.durationMillis = durationMillis
        this.files =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableMultimap<String?, Path?>>(
                files
            )
        this.testWarnings = com.google.common.base.Preconditions.checkNotNull<MutableList<String?>>(testWarnings)
        this.lastAttempt = lastAttempt
    }

    @com.google.common.annotations.VisibleForTesting
    fun getTestStatusArtifact(): Artifact? {
        return testAction.getCacheStatusArtifact()
    }

    @com.google.common.annotations.VisibleForTesting
    fun getFiles(): com.google.common.collect.ImmutableMultimap<String?, Path?> {
        return files
    }

    @com.google.common.annotations.VisibleForTesting
    fun getExecutionInfo(): BuildEventStreamProtos.TestResult.ExecutionInfo {
        return executionInfo
    }

    @com.google.common.annotations.VisibleForTesting
    fun getStatus(): TestStatus? {
        return status
    }

    @com.google.common.annotations.VisibleForTesting
    fun isCachedLocally(): Boolean {
        return cachedLocally
    }

    @com.google.common.annotations.VisibleForTesting
    fun getAttempt(): Int {
        return attempt
    }

    public override fun getEventId(): BuildEventId {
        return BuildEventIdUtil.testResult(
            testAction.getOwner().getLabel(),
            testAction.getRunNumber(),
            testAction.getShardNum(),
            attempt,
            BuildConfigurationValue.Companion.configurationId(testAction.getConfiguration())
        )
    }

    public override fun postedAfter(): MutableCollection<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<E?>(
            BuildEventIdUtil.targetCompleted(
                testAction.getOwner().getLabel(),
                BuildConfigurationValue.Companion.configurationId(testAction.getConfiguration())
            )
        )
    }

    public override fun getChildrenEvents(): MutableCollection<BuildEventId?> {
        if (lastAttempt) {
            return com.google.common.collect.ImmutableList.of<BuildEventId?>()
        } else {
            return com.google.common.collect.ImmutableList.of<E?>(
                BuildEventIdUtil.testResult(
                    testAction.getOwner().getLabel(),
                    testAction.getRunNumber(),
                    testAction.getShardNum(),
                    attempt + 1,
                    BuildConfigurationValue.Companion.configurationId(testAction.getConfiguration())
                )
            )
        }
    }

    public override fun referencedLocalFiles(): com.google.common.collect.ImmutableList<LocalFile?> {
        val localFileType: LocalFileType? =
            if (status === TestStatus.PASSED)
                LocalFileType.SUCCESSFUL_TEST_OUTPUT
            else
                LocalFileType.FAILED_TEST_OUTPUT
        val localFiles: com.google.common.collect.ImmutableList.Builder<LocalFile?> =
            com.google.common.collect.ImmutableList.builder<LocalFile?>()
        for (file in files.entries()) {
            if (file.getValue() != null) {
                // TODO(b/199940216): Can we populate metadata for these files?
                localFiles.add(LocalFile(file.getValue(), localFileType,  /* artifactMetadata= */null))
            }
        }
        return localFiles.build()
    }

    public override fun asStreamProto(converters: BuildEventContext): BuildEventStreamProtos.BuildEvent {
        return GenericBuildEvent.protoChaining(this).setTestResult(asTestResult(converters)).build()
    }

    @com.google.common.annotations.VisibleForTesting
    fun asTestResult(converters: BuildEventContext): BuildEventStreamProtos.TestResult {
        val pathConverter: PathConverter = converters.pathConverter()
        val builder: BuildEventStreamProtos.TestResult.Builder =
            BuildEventStreamProtos.TestResult.newBuilder()
        builder.setStatus(status)
        builder.setStatusDetails(statusDetails)
        builder.setExecutionInfo(executionInfo)
        builder.setCachedLocally(cachedLocally)
        if (startTimeMillis != 0L) {
            builder.setTestAttemptStart(Timestamps.fromMillis(startTimeMillis))
        }
        builder.setTestAttemptStartMillisEpoch(startTimeMillis)
        if (durationMillis != 0L) {
            builder.setTestAttemptDuration(Durations.fromMillis(durationMillis))
        }
        builder.setTestAttemptDurationMillis(durationMillis)
        builder.addAllWarning(testWarnings)
        var pathPrefix: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>()
        if (testAction.getOwner() != null) {
            pathPrefix =
                com.google.common.collect.ImmutableList.copyOf(
                    testAction
                        .getConfiguration()
                        .getTestLogsDirectory(testAction.getOwner().getLabel().getRepository())
                        .getExecPath()
                        .segments()
                )
        }
        for (file in files.entries()) {
            val uri: String? = pathConverter.apply(file.getValue())
            if (uri != null) {
                builder.addTestActionOutput(
                    BuildEventStreamProtos.File.newBuilder()
                        .setName(file.getKey())
                        .setUri(uri)
                        .addAllPathPrefix(pathPrefix)
                        .build()
                )
            }
        }
        return builder.build()
    }

    companion object {
        /**
         * Creates a test attempt result instance for a test that was not locally cached; it may have been
         * locally executed, remotely executed, or remotely cached.
         */
        fun forExecutedTestResult(
            testAction: TestRunnerAction,
            attemptData: TestResultData,
            attempt: Int,
            files: com.google.common.collect.ImmutableMultimap<String?, Path?>?,
            executionInfo: BuildEventStreamProtos.TestResult.ExecutionInfo?,
            lastAttempt: Boolean
        ): TestAttempt {
            return TestAttempt(
                false,
                testAction,
                executionInfo,
                attempt,
                attemptData.getStatus(),
                attemptData.getStatusDetails(),
                attemptData.getStartTimeMillisEpoch(),
                attemptData.getRunDurationMillis(),
                files,
                attemptData.getWarningList(),
                lastAttempt
            )
        }

        /**
         * Creates a test attempt result from cached test data, providing a result while indicating to
         * consumers that the test did not actually execute.
         */
        fun fromCachedTestResult(
            testAction: TestRunnerAction,
            attemptData: TestResultData,
            attempt: Int,
            files: com.google.common.collect.ImmutableMultimap<String?, Path?>?,
            executionInfo: BuildEventStreamProtos.TestResult.ExecutionInfo?,
            lastAttempt: Boolean
        ): TestAttempt {
            return TestAttempt(
                true,
                testAction,
                executionInfo,
                attempt,
                attemptData.getStatus(),
                attemptData.getStatusDetails(),
                attemptData.getStartTimeMillisEpoch(),
                attemptData.getRunDurationMillis(),
                files,
                attemptData.getWarningList(),
                lastAttempt
            )
        }

        /**
         * Creates a test result for rare cases where the test itself was built, but the [ ] could not be started by a test strategy.
         * 
         * 
         * This overload should be very rarely used, and in particular must not be used by an
         * implementation of a [TestStrategy].
         */
        fun forUnstartableTestResult(
            testAction: TestRunnerAction, attemptData: TestResultData
        ): TestAttempt {
            return TestAttempt(
                false,
                testAction,  /* executionInfo= */
                BuildEventStreamProtos.TestResult.ExecutionInfo.getDefaultInstance(),  /* attempt= */
                1,
                attemptData.getStatus(),
                attemptData.getStatusDetails(),
                attemptData.getStartTimeMillisEpoch(),
                attemptData.getRunDurationMillis(),  /* files= */
                com.google.common.collect.ImmutableMultimap.of<String?, Path?>(),
                attemptData.getWarningList(),  /* lastAttempt= */
                true
            )
        }
    }
}
