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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.query2.common.CqueryNode
import com.google.devtools.build.lib.query2.cquery.ConfiguredTargetQueryEnvironment
import com.google.devtools.build.lib.query2.engine.QueryEnvironment
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryTaskFuture
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ThreadSafeMutableSet
import com.google.devtools.build.lib.query2.engine.QueryExpression
import com.google.devtools.build.lib.query2.engine.QueryExpressionContext
import com.google.devtools.build.lib.query2.engine.QueryUtil

/**
 * A "config" query expression for cquery. The first argument is the expression to be evaluated. The
 * second argument is "target", "null", or an arbitrary configuration's hash (the same hash cquery
 * annotates label outputs with) to specify which configuration the user is seeking to query in. If
 * some but not all results of expr can be found in the specified config, the subset that can be is
 * returned. If no results of expr can be found in the specified config, an error is thrown.
 * 
 * <pre> expr ::= CONFIG '(' expr ',' word ')'</pre>
 */
class ConfigFunction : QueryFunction {
    val name: String
        get() = "config"

    val mandatoryArguments: Int
        get() = 2

    val argumentTypes: MutableList<com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType>
        get() = com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType?>(
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType.EXPRESSION,
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType.WORD
        )

    /**
     * This function is only viable with ConfiguredTargetQueryEnvironment which extends [ ].
     */
    override fun <T> eval(
        env: QueryEnvironment<T?>,
        context: QueryExpressionContext<T?>?,
        expression: QueryExpression?,
        args: MutableList<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument>,
        callback: com.google.devtools.build.lib.query2.engine.Callback<T?>?
    ): QueryTaskFuture<java.lang.Void?>? {
        val targetExpression: com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument = args.get(0)
        var configuration = args.get(1).toString()
        // Turn "'string'" to "string" (remove the surrounding apostrophes).
        configuration = configuration.substring(1, configuration.length - 1)

        val targetsFuture: QueryTaskFuture<ThreadSafeMutableSet<T?>?>? =
            QueryUtil.evalAll<T?>(env, context, targetExpression.getExpression())

        return env.whenSucceedsCall<java.lang.Void?>(
            targetsFuture,
            (env as ConfiguredTargetQueryEnvironment)
                .getConfiguredTargetsForConfigFunction<T?>(
                    targetExpression.toString(),
                    targetsFuture,
                    configuration,
                    callback as com.google.devtools.build.lib.query2.engine.Callback<CqueryNode?>?
                )
        )
    }
}
