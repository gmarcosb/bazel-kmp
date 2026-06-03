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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.actions.Artifact

/**
 * This is the event passed from the various test strategies to the `RecordingTestListener`
 * upon test completion.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class TestResult(
    testAction: TestRunnerAction?,
    data: TestResultData?,
    testOutputs: com.google.common.collect.ImmutableMultimap<String?, Path?>?,
    private val cached: Boolean,
    execRoot: Path?,
    systemFailure: DetailedExitCode?
) : Postable {
    private val testAction: TestRunnerAction
    private val data: TestResultData
    private val testOutputs: com.google.common.collect.ImmutableMultimap<String?, Path?>
    protected val execRoot: Path?
    private val systemFailure: DetailedExitCode?

    /**
     * Construct the TestResult for the given test / status.
     * 
     * @param testAction The test that was run.
     * @param data test result protobuffer.
     * @param cached true if this is a locally cached test result.
     * @param execRoot The execution root in which the action was carried out; can be null, in which
     * case everything depending on the execution root is ignored.
     * @param systemFailure Description of the system failure responsible for the test not succeeding;
     * null if no such failure occurred
     */
    init {
        this.testAction = com.google.common.base.Preconditions.checkNotNull<TestRunnerAction>(testAction)
        this.data = com.google.common.base.Preconditions.checkNotNull<TestResultData>(data)
        this.testOutputs =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableMultimap<String?, Path?>>(
                testOutputs
            )
        this.execRoot = execRoot
        this.systemFailure = systemFailure
    }

    constructor(
        testAction: TestRunnerAction?,
        data: TestResultData?,
        testOutputs: com.google.common.collect.ImmutableMultimap<String?, Path?>?,
        cached: Boolean,
        systemFailure: DetailedExitCode?
    ) : this(testAction, data, testOutputs, cached, null, systemFailure)

    /** Returns the test action.  */
    fun getTestAction(): TestRunnerAction {
        return testAction
    }

    /**
     * Returns the test log path. Note, that actual log file may no longer correspond to this
     * artifact. Use getActualLogPath() method if you need log location.
     */
    fun getTestLogPath(): Path {
        val testLogPath: Path = testAction.getTestLog().getPath()
        // If we have an exec root we'll use its fileSystem
        if (execRoot != null) {
            val fileSystem: FileSystem = execRoot.getFileSystem()
            return fileSystem.getPath(testLogPath.asFragment())
        }
        return testLogPath
    }

    /** Returns whether the result was loaded from local action cache.  */
    fun isCached(): Boolean {
        return cached
    }

    /**
     * Returns the list of locally cached test attempts. This method must only be called if [ ][.isCached] returns `true`.
     */
    fun getCachedTestAttempts(): MutableList<TestAttempt?> {
        com.google.common.base.Preconditions.checkState(isCached())
        return com.google.common.collect.ImmutableList.of<TestAttempt?>(
            TestAttempt.Companion.fromCachedTestResult(
                testAction,
                data,
                1,
                testOutputs,
                BuildEventStreamProtos.TestResult.ExecutionInfo.getDefaultInstance(),  /* lastAttempt= */
                true
            )
        )
    }

    /** Returns the coverage data artifact, if available, and null otherwise.  */
    fun getCoverageData(): Path? {
        if (data.getHasCoverage()) {
            return testAction.getCoverageData().getPath()
        }
        return null
    }

    /** Returns the test status artifact.  */
    fun getTestStatusArtifact(): Artifact? {
        // these artifacts are used to keep track of the number of pending and completed tests.
        return testAction.getCacheStatusArtifact()
    }

    /**
     * Returns the test name in a user-friendly format. Will generally include the target name and
     * shard number, if applicable.
     */
    fun getTestName(): String? {
        return testAction.getTestName()
    }

    /** Returns the test label.  */
    fun getLabel(): String {
        return Label.print(testAction.getOwner().getLabel())
    }

    /** Returns the test shard number.  */
    fun getShardNum(): Int {
        return testAction.getShardNum()
    }

    /**
     * Returns the total number of test shards. 0 means no sharding, whereas 1 means degenerate
     * sharding.
     */
    fun getTotalShards(): Int {
        return testAction.getExecutionSettings().getTotalShards()
    }

    fun getData(): TestResultData {
        return data
    }

    /**
     * Returns the description of the system failure responsible for the test not succeeding or `null` if no such failure occurred.
     */
    fun getSystemFailure(): DetailedExitCode? {
        return systemFailure
    }

    companion object {
        fun isBlazeTestStatusPassed(status: BlazeTestStatus?): Boolean {
            return status === BlazeTestStatus.PASSED || status === BlazeTestStatus.FLAKY
        }
    }
}
