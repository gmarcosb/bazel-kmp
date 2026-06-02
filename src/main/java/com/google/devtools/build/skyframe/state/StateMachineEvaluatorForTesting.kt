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
package com.google.devtools.build.skyframe.state

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.skyframe.EvaluationResult
import com.google.devtools.build.skyframe.MemoizingEvaluator
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.state.EnvironmentForUtilities
import com.google.devtools.build.skyframe.state.EnvironmentForUtilities.ResultProvider
import com.google.devtools.build.skyframe.state.StateMachine
import java.util.HashMap

/**
 * Evaluates [StateMachine] using a given [MemoizingEvaluator] for testing.
 * 
 * 
 * As the [StateMachine] requests dependencies, delegates requests to the underlying graph
 * and records missing values. Then evaluates any missing dependencies before resuming the [ ].
 * 
 * 
 * Only supports `keepGoing` evaluations.
 */
class StateMachineEvaluatorForTesting private constructor(root: StateMachine?, evaluator: MemoizingEvaluator) {
    private val evaluator: MemoizingEvaluator
    private val driver: com.google.devtools.build.skyframe.state.Driver

    /** Values are either [SkyValue] or [Exception].  */
    private val previousResults: HashMap<SkyKey?, Any?> = HashMap<SkyKey?, Any?>()

    init {
        this.driver = com.google.devtools.build.skyframe.state.Driver(root)
        this.evaluator = evaluator
    }

    @Throws(java.lang.InterruptedException::class)
    private fun evaluate(context: com.google.devtools.build.skyframe.EvaluationContext?): EvaluationResult<SkyValue?>? {
        val missing: java.util.ArrayList<SkyKey?> = java.util.ArrayList<SkyKey?>()
        val env: EnvironmentForUtilities =
            EnvironmentForUtilities(
                ResultProvider { skyKey: SkyKey? ->
                    val value: Any? = previousResults.get(skyKey)
                    if (value != null) {
                        return@ResultProvider value
                    }
                    missing.add(skyKey)
                    null
                })

        var result: EvaluationResult<SkyValue?>? = null
        var hasError = false
        while (!driver.drive(env)) {
            if (hasError) {
                return result // Exits if there was an error in the previous round.
            }

            result = evaluator.evaluate<SkyValue?>(missing, context)
            for (key in missing) {
                val value: SkyValue? = result.get(key)
                if (value != null) {
                    previousResults.put(key, value)
                    continue
                }
                // Marks an error. The state machine will run one more time for "error bubbling" before
                // exiting.
                hasError = true
                val error: com.google.devtools.build.skyframe.ErrorInfo? = result.getError(key)
                if (error == null) {
                    continue
                }
                val exception: java.lang.Exception? = error.getException()
                if (exception != null) {
                    previousResults.put(key, exception)
                }
                // Otherwise, there might be a cycle.
            }
            missing.clear()
        }
        return result
    }

    companion object {
        /**
         * Runs the given [StateMachine].
         * 
         * @return the result of the last evalution, if any, for error handling.
         */
        @Throws(java.lang.InterruptedException::class)  // Null if there were no evaluations.
        fun run(
            root: StateMachine?,
            evaluator: MemoizingEvaluator,
            context: com.google.devtools.build.skyframe.EvaluationContext?
        ): EvaluationResult<SkyValue?>? {
            return StateMachineEvaluatorForTesting(root, evaluator).evaluate(context)
        }
    }
}
