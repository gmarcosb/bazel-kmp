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

import com.google.devtools.build.lib.actions.ActionContext

/** A context for the execution of test actions ([TestRunnerAction]).  */
interface TestActionContext : ActionContext {
    /**
     * A group of attempts for a single test shard, ran either sequentially or in parallel.
     * 
     * 
     * When one attempt matches the result specified by [ ],
     * threads running the other attempts get an [InterruptedException] and [.cancelled]
     * will in the future return true. When a thread joins an attempt group that is already cancelled,
     * [InterruptedException] will be thrown on the call to [.register].
     */
    interface AttemptGroup {
        /**
         * Registers a thread to the attempt group.
         * 
         * 
         * If the attempt group is already cancelled, throw [InterruptedException].
         */
        @Throws(java.lang.InterruptedException::class)
        fun register()

        /** Unregisters a thread from the attempt group.  */
        fun unregister()

        /**
         * Signal that the attempt run by this thread has the desired result and cancel all the others.
         */
        fun cancelOthers()

        /** Whether the attempt group has been cancelled.  */
        fun cancelled(): Boolean

        companion object {
            /** A dummy attempt group used when no flaky test attempt cancellation is done.  */
            val NOOP: AttemptGroup = object : AttemptGroup {
                override fun register() {}

                override fun unregister() {}

                override fun cancelOthers() {}

                override fun cancelled(): Boolean {
                    return false
                }
            }
        }
    }

    @Throws(ExecException::class, java.lang.InterruptedException::class)
    fun createTestRunnerSpawn(
        testRunnerAction: TestRunnerAction?, actionExecutionContext: ActionExecutionContext?
    ): TestRunnerSpawn?

    /** Returns whether test_keep_going is enabled.  */
    fun isTestKeepGoing(): Boolean

    /**
     * Returns `true` to indicate that exclusive tests should be treated as regular parallel
     * tests.
     * 
     * 
     * Returning `true` may make sense for certain forced remote test execution strategies
     * where running tests in sequence would be wasteful.
     */
    fun forceExclusiveTestsInParallel(): Boolean {
        return false
    }

    /**
     * Returns `true` to indicate that "exclusive-if-local" tests should be treated as regular
     * parallel tests.
     * 
     * 
     * Returning `true` may make sense for certain remote test execution strategies where
     * running tests in sequence would be wasteful.
     */
    fun forceExclusiveIfLocalTestsInParallel(): Boolean {
        return false
    }

    /** Creates a cached test result.  */
    @Throws(IOException::class)
    fun newCachedTestResult(
        execRoot: Path?,
        action: TestRunnerAction?,
        cached: TestResultData?,
        testOutputs: com.google.common.collect.ImmutableMultimap<String?, Path?>?
    ): com.google.devtools.build.lib.analysis.test.TestResult?

    /** Returns the attempt group associaed with the given shard.  */
    fun getAttemptGroup(owner: ActionOwner?, shardNum: Int): AttemptGroup?

    /** An individual test attempt result.  */
    interface TestAttemptResult {
        /** Test attempt result classification, splitting failures into permanent vs retriable.  */
        enum class Result {
            /** Test attempt successful.  */
            PASSED,

            /** Test failed, potentially due to test flakiness, can be retried.  */
            FAILED_CAN_RETRY,

            /** Permanent failure.  */
            FAILED;

            fun canRetry(): Boolean {
                return this == com.google.devtools.build.lib.analysis.test.TestActionContext.TestAttemptResult.Result.FAILED_CAN_RETRY
            }
        }

        /** Returns the overall test result.  */
        fun result(): Result?

        /** Returns a list of spawn results for this test attempt.  */
        fun spawnResults(): com.google.common.collect.ImmutableList<SpawnResult>?

        /**
         * Returns a description of the system failure associated with the primary spawn result, if any.
         */
        fun primarySystemFailure(): DetailedExitCode? {
            if (spawnResults().isEmpty()) {
                return null
            }
            val primarySpawnResult: SpawnResult = spawnResults().get(0)
            if (primarySpawnResult.status() === Status.SUCCESS) {
                return null
            }
            if (primarySpawnResult.status().isConsideredUserError) {
                return null
            }
            return DetailedExitCode.of(primarySpawnResult.failureDetail())
        }
    }

    /**
     * The result of passing a [TestAttemptResult] through [ ][TestRunnerSpawn.finalizeFailedTestAttempt], which is later passed to [ ][TestRunnerSpawn.finalizeTest] or [TestRunnerSpawn.finalizeCancelledTest].
     * 
     * 
     * This exists so that implementations may replace or augment the result with their own data.
     */
    interface ProcessedAttemptResult

    /** A delegate to run a test. This may include running multiple spawns, renaming outputs, etc.  */
    interface TestRunnerSpawn {
        fun getActionExecutionContext(): ActionExecutionContext?

        /** Run the test attempt. Blocks until the attempt is complete.  */
        @Throws(java.lang.InterruptedException::class, IOException::class, ExecException::class)
        fun execute(): TestAttemptResult?

        /**
         * After the first attempt has run, this method is called to determine the maximum number of
         * attempts for this test.
         */
        fun getMaxAttempts(firstTestAttemptResult: TestAttemptResult?): Int

        /** Rename the output files if the test attempt failed, and post the test attempt result.  */
        @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
        fun finalizeFailedTestAttempt(
            testAttemptResult: TestAttemptResult?, attempt: Int
        ): ProcessedAttemptResult?

        /** Post the final test result based on the last attempt and the list of failed attempts.  */
        @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
        fun finalizeTest(
            lastTestAttemptResult: TestAttemptResult?, failedAttempts: MutableList<ProcessedAttemptResult?>?
        )

        /** Post the final test result based on the last attempt and the list of failed attempts.  */
        @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
        fun finalizeCancelledTest(failedAttempts: MutableList<ProcessedAttemptResult?>?)

        /**
         * Return a [TestRunnerSpawn] object if test fallback is enabled, or `null`
         * otherwise. Test fallback is a feature to allow a test to run with one strategy until the max
         * attempts are exhausted and then run with another strategy for another set of attempts. This
         * is rarely used, and should ideally be removed.
         */
        @Throws(ExecException::class, java.lang.InterruptedException::class)
        fun getFallbackRunner(): TestRunnerSpawn? {
            return null
        }

        /**
         * Return a [TestRunnerSpawn] object that is used on flaky retries. Flaky retry runner
         * allows a test to run with a different strategy on flaky retries (for example, enabling test
         * fail-fast mode to save up resources).
         */
        @Throws(ExecException::class, java.lang.InterruptedException::class)
        fun getFlakyRetryRunner(previousAttemptResults: MutableList<SpawnResult?>?): TestRunnerSpawn {
            return this
        }
    }
}
