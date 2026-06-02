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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.skyframe.GlobFunctionWithMultipleRecursiveFunctions
import com.google.devtools.build.lib.skyframe.GlobFunctionWithRecursionInSingleFunction
import com.google.devtools.build.skyframe.SkyFunction
import java.util.concurrent.ConcurrentHashMap

/**
 * A [SkyFunction] for [GlobValue]s.
 * 
 * 
 * This code drives the glob matching process. It has two subclasses, [ ] and [ ].
 * 
 * 
 * [GlobFunctionWithMultipleRecursiveFunctions] is the canonical implementation of [ ] computation. It recursively creates sub-Glob nodes when handling subdirectories
 * under a package. Although evaluating package glob patterns using such a sub-Glob nodes tree is
 * performance friendly for incremental evaluation, it potentially introduced significant memory
 * overhead when the sub-Glob nodes tree becomes extremely large.
 * 
 * 
 * [GlobFunctionWithRecursionInSingleFunction] is introduced due to two major advantages:
 * 
 * 
 *  * It can mitigate the memory overhead introduced by the giant sub-Glob nodes tree. [       ] can store
 * computation state between skyframe restarts and is discarded after evaluating the glob
 * node. So there is only one Glob node stored in skyframe per glob pattern.
 *  * `StateMachine` which enables structured concurrency when querying dependent `SkyKey`s. This leads to much less frequency of skyframe restarts when evaluating a glob
 * pattern.
 * 
 * 
 * 
 * Currently, [GlobFunctionWithRecursionInSingleFunction] does not work well with
 * incremental blaze query. Since [ ] is not stored
 * between blaze invocations, so skyframe incrementality is totally lost compared to [ ]. Experiments have also shown significant performance
 * regression when using [GlobFunctionWithMultipleRecursiveFunctions] to incrementally
 * evaluate glob pattern in a package with directory structure which is too wide and too deep. So we
 * still decide to keep using [GlobFunctionWithMultipleRecursiveFunctions] in such a scenario.
 */
abstract class GlobFunction : SkyFunction {
    protected var regexPatternCache: ConcurrentHashMap<String?, java.util.regex.Pattern?> =
        ConcurrentHashMap<String?, java.util.regex.Pattern?>()

    fun complete() {
        this.regexPatternCache = ConcurrentHashMap<String?, java.util.regex.Pattern?>()
    }

    companion object {
        /**
         * Creates the [GlobFunction] variant based on the type of [SkyframeExecutor].
         * 
         * 
         * [GlobFunctionWithRecursionInSingleFunction] is not fully supported for incremental
         * evaluation due to performance regression. So in the case when the performance requirement for
         * incremental evaluation is strict, creates the canonical [ ].
         */
        @kotlin.jvm.JvmStatic
        fun create(recursionInSingleFunction: Boolean): GlobFunction {
            return if (recursionInSingleFunction)
                GlobFunctionWithRecursionInSingleFunction()
            else
                GlobFunctionWithMultipleRecursiveFunctions()
        }
    }
}
