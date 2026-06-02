// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.runtime.CommandEnvironment

/** The utility class for [AqueryCommand] and [CqueryCommand]  */
object QueryCommandUtils {
    /**
     * Get the list of top-level targets of the query from universe scope and the query expression.
     * 
     * @throws QueryException if targets were specified in the query expression together with
     * --skyframe_state flag
     */
    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
    fun getTopLevelTargets(
        universeScope: MutableList<String?>, expr: QueryExpression?, queryCurrentSkyframeState: Boolean
    ): com.google.common.collect.ImmutableList<String?> {
        if (expr == null) {
            return com.google.common.collect.ImmutableList.copyOf<String?>(universeScope)
        }

        val topLevelTargets: com.google.common.collect.ImmutableList<String?>
        if (universeScope.isEmpty()) {
            val targetPatternSet: MutableSet<String?> = LinkedHashSet<String?>()
            expr.collectTargetPatterns(targetPatternSet)
            topLevelTargets = com.google.common.collect.ImmutableList.copyOf<String?>(targetPatternSet)
        } else {
            topLevelTargets = com.google.common.collect.ImmutableList.copyOf<String?>(universeScope)
        }

        if (queryCurrentSkyframeState && !topLevelTargets.isEmpty()) {
            throw com.google.devtools.build.lib.query2.engine.QueryException(
                ("Error while parsing '"
                        + expr.toTrunctatedString()
                        + "': Specifying build target(s) "
                        + topLevelTargets
                        + " with --skyframe_state is currently not supported."),
                ActionQuery.Code.TOP_LEVEL_TARGETS_WITH_SKYFRAME_STATE_NOT_SUPPORTED
            )
        }

        return topLevelTargets
    }

    /**
     * Delete any keys that have been deserialized by Skycache from the evaluator so that query
     * commands evaluate them again. We know which keys have been deserialized because they are
     * instances of DeserializedSkyValue
     */
    @com.google.common.annotations.VisibleForTesting
    fun resetDeserializedKeysFromRemoteAnalysisCache(env: CommandEnvironment) {
        val evaluator: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            env.getSkyframeExecutor().getEvaluator()
        val deserializedKeysToDelete: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            evaluator.getDoneValues().entrySet().stream()
                .filter({ e -> e.getValue() is DeserializedSkyValue })
                .map({ java.util.Map.Entry.key })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
        evaluator.delete(deserializedKeysToDelete::contains)
    }
}
