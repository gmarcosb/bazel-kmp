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
package com.google.devtools.build.skyframe

import com.google.devtools.build.skyframe.MemoizingEvaluatorTest
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [InMemoryMemoizingEvaluator].  */
@RunWith(JUnit4::class)
class InMemoryMemoizingEvaluatorTest : MemoizingEvaluatorTest() {
    override fun getMemoizingEvaluator(
        functions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?,
        differencer: Differencer?,
        progressReceiver: EvaluationProgressReceiver?,
        graphInconsistencyReceiver: GraphInconsistencyReceiver?,
        eventFilter: EventFilter?
    ): InMemoryMemoizingEvaluator {
        return InMemoryMemoizingEvaluator(
            functions,
            differencer,
            progressReceiver,
            graphInconsistencyReceiver,
            eventFilter,
            emittedEventState,  /* keepEdges= */
            true,  /* usePooledInterning= */
            true
        )
    }
}
