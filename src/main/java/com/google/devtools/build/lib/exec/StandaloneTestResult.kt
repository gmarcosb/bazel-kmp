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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.SpawnResult

/**
 * Contains information about the results of test execution.
 * 
 * @param spawnResults Returns the SpawnResults created by the test, if any.
 * @param testResultDataBuilder Returns the TestResultData for the test.
 */
class StandaloneTestResult(
    spawnResults: com.google.common.collect.ImmutableList<SpawnResult?>?,
    testResultDataBuilder: TestResultData.Builder?,
    executionInfo: BuildEventStreamProtos.TestResult.ExecutionInfo?
) : TestActionContext.TestAttemptResult {
    public override fun result(): TestActionContext.TestAttemptResult.Result {
        // TODO(b/148785690): Establish proper retry policy for flaky tests in StandaloneTestStrategy.
        return if (this.testResultDataBuilder.getStatus() === BlazeTestStatus.PASSED)
            Result.PASSED
        else
            Result.FAILED_CAN_RETRY
    }

    /** Builder for a [StandaloneTestResult] instance, which is immutable once built.  */
    @AutoBuilder
    abstract class Builder {
        /** Returns the SpawnResults for the test, if any.  */
        abstract fun spawnResults(): com.google.common.collect.ImmutableList<SpawnResult?>?

        /** Sets the SpawnResults for the test.  */
        abstract fun setSpawnResults(spawnResults: com.google.common.collect.ImmutableList<SpawnResult?>?): Builder?

        /** Sets the TestResultData for the test.  */
        abstract fun setTestResultDataBuilder(testResultDataBuilder: TestResultData.Builder?): Builder?

        abstract fun setExecutionInfo(
            executionInfo: BuildEventStreamProtos.TestResult.ExecutionInfo?
        ): Builder?

        abstract fun realBuild(): StandaloneTestResult?

        /**
         * Returns an immutable StandaloneTestResult object.
         * 
         * 
         * The list of SpawnResults is also made immutable here.
         */
        fun build(): StandaloneTestResult? {
            return this.setSpawnResults(spawnResults())!!.realBuild()
        }
    }

    val spawnResults: com.google.common.collect.ImmutableList<SpawnResult?>?
    val testResultDataBuilder: TestResultData.Builder?
    val executionInfo: BuildEventStreamProtos.TestResult.ExecutionInfo?

    init {
        this.executionInfo = executionInfo
        this.testResultDataBuilder = testResultDataBuilder
        this.spawnResults = spawnResults
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<SpawnResult?>?>(
            spawnResults,
            "spawnResults"
        )
        java.util.Objects.requireNonNull<Any?>(testResultDataBuilder, "testResultDataBuilder")
        java.util.Objects.requireNonNull<Any?>(executionInfo, "executionInfo")
    }

    companion object {
        /** Returns a builder that can be used to construct a [StandaloneTestResult] object.  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return AutoBuilder_StandaloneTestResult_Builder()
        }
    }
}
