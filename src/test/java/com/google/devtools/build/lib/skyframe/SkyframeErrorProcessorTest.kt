// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.TargetAndConfiguration

/**
 * Tests for [SkyframeErrorProcessor].
 * 
 * 
 * TODO(b/221024798): Improve test coverage.
 */
@RunWith(TestParameterInjector::class)
class SkyframeErrorProcessorTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProcessErrors_analysisErrorNoKeepGoing_throwsException(
        @TestParameter includeExecutionPhase: Boolean
    ) {
        val analysisErrorKey: ConfiguredTargetKey? =
            ConfiguredTargetKey.builder()
                .setLabel(Label.parseCanonicalUnchecked("//analysis_err"))
                .build()
        val mockTargetAndConfiguration: TargetAndConfiguration =
            TargetAndConfiguration(< T > mock < T ? > (Target::class.java),  /* configuration= */null)
        val analysisException: ConfiguredValueCreationException =
            ConfiguredValueCreationException(
                mockTargetAndConfiguration.getTarget(), "analysis exception"
            )
        val analysisErrorInfo: ErrorInfo? =
            ErrorInfo.fromException(
                ReifiedSkyFunctionException(
                    com.google.devtools.build.lib.skyframe.SkyframeErrorProcessorTest.DummySkyFunctionException(
                        analysisException,
                        Transience.PERSISTENT
                    )
                ),  /*isTransitivelyTransient=*/
                false
            )

        val result: EvaluationResult<SkyValue?>? =
            EvaluationResult.builder().addError(analysisErrorKey, analysisErrorInfo).build()

        val thrown: ViewCreationFailedException? =
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable {
                    SkyframeErrorProcessor.processErrors(
                        result,  /* cyclesReporter= */
                        CyclesReporter(),  /* eventHandler= */
                        < T > mock < T ? > (ExtendedEventHandler::class.java),  /* keepGoing= */
                    false,  /* keepEdges= */
                    true,  /* eventBus= */
                    null,  /* bugReporter= */
                    null,
                    includeExecutionPhase)
                })
        assertThat(thrown).hasCauseThat().isEqualTo(analysisException)
    }

    private class DummySkyFunctionException(cause: java.lang.Exception?, transience: Transience?) :
        SkyFunctionException(cause, transience)
}
