// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.producers

import com.google.devtools.build.lib.skyframe.SkyframeExecutor

/** Base class for tests of producers.  */
abstract class ProducerTestCase : BuildViewTestCase() {
    @Throws(Exception::class)
    override fun useConfiguration(vararg args: String?) {
        // Do nothing, some of the producers under test are used in standard configuration creation.
    }

    /**
     * Use a [StateMachineEvaluatorForTesting] to drive the given [StateMachine] until it
     * finishes (with a result or an error). Results should be retrieved from whatever result sink the
     * [StateMachine] is designed for.
     * 
     * @return `true` on success
     */
    @Throws(InterruptedException::class)
    fun executeProducer(producer: StateMachine?): Boolean {
        val context: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(true)
                .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
                .setEventHandler(reporter)
                .build()
        try {
            getSkyframeExecutor().getSkyframeBuildView().enableAnalysis(true)
            val result: EvaluationResult<SkyValue?>? =
                StateMachineEvaluatorForTesting.run(
                    producer, getSkyframeExecutor().getEvaluator(), context
                )
            if (result != null) {
                check(
                    !(result.hasError() && !result.getError().getCycleInfo().isEmpty())
                ) { "Cycle detected: " + result.getError().getCycleInfo() }
                return !result.hasError()
            }
        } finally {
            getSkyframeExecutor().getSkyframeBuildView().enableAnalysis(false)
        }
        return true
    }
}
