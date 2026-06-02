// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.skyframe.EvaluationResult
import com.google.devtools.build.skyframe.ParallelEvaluatorContext
import com.google.devtools.build.skyframe.SkyKey

/**
 * Detects cycles after a [ParallelEvaluator] evaluation that did not complete due to cycles.
 * 
 * 
 * Public only for the benefit of alternative graph implementations outside the skyframe package.
 */
interface CycleDetector {
    @Throws(java.lang.InterruptedException::class)
    fun checkForCycles(
        badRoots: Iterable<SkyKey?>?,
        result: com.google.devtools.build.skyframe.EvaluationResult.Builder<*>?,
        evaluatorContext: ParallelEvaluatorContext?
    )
}
