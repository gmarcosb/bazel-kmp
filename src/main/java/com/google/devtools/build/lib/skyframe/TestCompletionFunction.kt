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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionExecutionException

/**
 * TestCompletionFunction builds all relevant test artifacts of a [ ]. This includes test shards and repeated
 * runs.
 */
class TestCompletionFunction : SkyFunction {
    @Throws(java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val key: TestCompletionKey =
            skyKey.argument() as TestCompletionKey
        val ctKey: ConfiguredTargetKey? = key.configuredTargetKey()
        val ctx: TopLevelArtifactContext? = key.topLevelArtifactContext()
        if (env.getValue(TargetCompletionValue.key(ctKey, ctx,  /* willTest= */true)) == null) {
            return null
        }

        val ctValue: ConfiguredTargetValue? = env.getValue(ctKey) as ConfiguredTargetValue?
        if (ctValue == null) {
            return null
        }

        val ct: ConfiguredTarget? = ctValue.getConfiguredTarget()
        if (key.exclusiveTesting()) {
            // Request test execution iteratively if testing exclusively.
            for (testArtifact in TestProvider.getTestStatusArtifacts(ct)) {
                env.getValue(testArtifact.getGeneratingActionKey())
                if (env.valuesMissing()) {
                    return null
                }
            }
        } else {
            val skyKeys: MutableList<SkyKey?> = Artifact.keys(TestProvider.getTestStatusArtifacts(ct))
            val result: SkyframeLookupResult = env.getValuesAndExceptions(skyKeys)
            if (env.valuesMissing()) {
                return null
            }
            for (actionKey in skyKeys) {
                try {
                    if (result.getOrThrow<E?>(actionKey, ActionExecutionException::class.java) == null) {
                        return null
                    }
                } catch (e: ActionExecutionException) {
                    val detailedExitCode: DetailedExitCode = e.getDetailedExitCode()
                    if (detailedExitCode.getExitCode() == ExitCode.BUILD_FAILURE
                        && ctValue is ActionLookupValue
                    ) {
                        postTestResultEventsForBuiltTestThatCouldNotBeRun(
                            env, actionKey as ActionLookupData?, ctValue, detailedExitCode
                        )
                    } else {
                        return null
                    }
                }
            }
        }
        return TestCompletionValue.TEST_COMPLETION_MARKER
    }

    override fun extractTag(skyKey: SkyKey): String {
        return Label.print((skyKey.argument() as ConfiguredTargetKey).getLabel())
    }

    companion object {
        /**
         * Posts events for test actions that could not run despite the fact that the test target built
         * successfully.
         * 
         * 
         * When we run this SkyFunction we will have already built the test executable and its inputs,
         * but we might be unable to run the test. The currently known scenarios where this happens are:
         * 
         * 
         *  1. A failure to build the exec-configured attributes providing inputs to the [       ] such as `$test_runtime`, `$test_wrapper`, `test_setup_script` and others.
         *  1. The test strategy throws an [ExecException] prior to running the test, for example
         * when some sort of validation fails.
         *  1. The test action observes lost input(s) and initiates action rewinding, but the lost
         * input(s) fail to build. Note that this implies action nondeterminism, since the lost
         * input(s) were previously built successfully.
         * 
         * 
         * 
         * In these scenarios, we do not get to use any `TestStrategy` that is responsible for
         * posting [TestAttempt] and [TestResult] events. We need to post minimal events here
         * indicating the test [FAILED_TO_BUILD][BlazeTestStatus.FAILED_TO_BUILD].
         */
        private fun postTestResultEventsForBuiltTestThatCouldNotBeRun(
            env: SkyFunction.Environment,
            actionKey: ActionLookupData,
            actionLookupValue: ActionLookupValue,
            detailedExitCode: DetailedExitCode
        ) {
            var status: BlazeTestStatus? = BlazeTestStatus.FAILED_TO_BUILD
            if (detailedExitCode
                    .getFailureDetail()
                    .getExecution()
                    .getCode()
                    .equals(Code.ACTION_NOT_UP_TO_DATE)
            ) {
                status = BlazeTestStatus.NO_STATUS
            }
            val testRunnerAction: TestRunnerAction? =
                actionLookupValue.getAction(actionKey.getActionIndex()) as TestRunnerAction?
            val testData: TestResultData? = TestResultData.newBuilder().setStatus(status).build()
            env.getListener().post(TestAttempt.forUnstartableTestResult(testRunnerAction, testData))
            env.getListener()
                .post(
                    TestResult(
                        testRunnerAction,
                        testData,
                        com.google.common.collect.ImmutableMultimap.of<K?, V?>(),  /* cached= */
                        false,
                        detailedExitCode
                    )
                )
        }
    }
}
