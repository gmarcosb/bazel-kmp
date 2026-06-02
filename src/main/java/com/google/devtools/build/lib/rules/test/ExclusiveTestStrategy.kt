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
package com.google.devtools.build.lib.rules.test

import com.google.devtools.build.lib.actions.ActionExecutionContext

/**
 * Test strategy wrapper called 'exclusive'. It should delegate to a test strategy for local
 * execution.
 * 
 * 
 * This strategy should be registered with a command line identifier of 'exclusive' which will
 * trigger behavior in SkyframeExecutor to schedule test execution sequentially after non-test
 * actions. This ensures streamed test output is not polluted by other action output.
 * 
 * 
 * Note: It's expected that this strategy is largely identical to the one it wraps. Most of the
 * behavior specific to the 'exclusive' strategy is enabled based on the value of the `
 * --test_strategy` flag, not instance methods of this class.
 */
class ExclusiveTestStrategy(parent: TestActionContext) : TestActionContext {
    private val parent: TestActionContext

    init {
        this.parent = parent
    }

    @Throws(ExecException::class, java.lang.InterruptedException::class)
    public override fun createTestRunnerSpawn(
        testRunnerAction: TestRunnerAction?, actionExecutionContext: ActionExecutionContext?
    ): TestRunnerSpawn {
        return parent.createTestRunnerSpawn(testRunnerAction, actionExecutionContext)
    }

    val isTestKeepGoing: Boolean
        get() = parent.isTestKeepGoing()

    @Throws(IOException::class)
    public override fun newCachedTestResult(
        execRoot: com.google.devtools.build.lib.vfs.Path?,
        action: TestRunnerAction?,
        cachedResult: TestResultData?,
        testOutputs: com.google.common.collect.ImmutableMultimap<String?, com.google.devtools.build.lib.vfs.Path?>?
    ): TestResult {
        return parent.newCachedTestResult(execRoot, action, cachedResult, testOutputs)
    }

    public override fun getAttemptGroup(owner: ActionOwner?, shard: Int): AttemptGroup {
        // TODO(ulfjack): Exclusive tests run sequentially, and this feature exists to allow faster
        //  aborts of concurrent actions. It's not clear what, if anything, we should do here.
        return AttemptGroup.NOOP
    }
}
