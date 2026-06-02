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
package com.google.devtools.build.lib.query2.engine

import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.server.FailureDetails.Query
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableList

/**
 * A some(x) filter expression, which returns certain number of arbitrary nodes in set x, or fails
 * if x is empty. An optional integer-literal second argument may be specified; it specifies number
 * of arbitrary nodes to be returned. If second argument is empty, return one node.
 * 
 * <pre>expr ::= SOME '(' expr ')'</pre>
 * 
 * <pre>       | SOME '(' expr ',' count ')'</pre>
 */
internal class SomeFunction : QueryFunction {
    val name: String
        get() = "some"

    val mandatoryArguments: Int
        get() = 1 // last argument is optional

    val argumentTypes: MutableList<QueryEnvironment.ArgumentType?>
        get() = ImmutableList.of<QueryEnvironment.ArgumentType?>(
            QueryEnvironment.ArgumentType.EXPRESSION,
            QueryEnvironment.ArgumentType.INTEGER
        )

    override fun <T> eval(
        env: QueryEnvironment<T?>,
        context: QueryExpressionContext<T?>?,
        expression: QueryExpression?,
        args: MutableList<QueryEnvironment.Argument?>,
        callback: Callback<T?>
    ): QueryTaskFuture<Void?>? {
        // Add a second optional integer parameter indicating return size.

        val resultMaxSize = if (args.size > 1) args.get(1)!!.getInteger() else 1

        // Since the callback will be executed multiple times, so we need to avoid some target
        // being added multiple times in different callback execution. So we need to have a state
        // variable (`targetsSet` below) to track which ones are already added in order to avoid adding
        // duplicates.
        val targetsSet: ThreadSafeMutableSet<T?> = env.createThreadSafeMutableSet()

        val evaluateExpression: EvaluateExpression<T?> =
            env.createEvaluateExpression(args.get(0)!!.getExpression(), context)
        val queryTaskFuture: QueryTaskFuture<Void?>? =
            evaluateExpression.eval(
                Callback { partialResult: Iterable<T?>? ->
                    if (Iterables.isEmpty(partialResult)) {
                        return@eval
                    }
                    var shouldCancel = false
                    synchronized(targetsSet) {
                        val current = ArrayList<T?>()
                        for (nextTarget in partialResult!!) {
                            if (targetsSet.size >= resultMaxSize) {
                                break
                            }
                            if (targetsSet.add(nextTarget)) {
                                current.add(nextTarget)
                            }
                        }

                        if (!current.isEmpty()) {
                            callback.process(current)
                        }
                        if (targetsSet.size >= resultMaxSize) {
                            shouldCancel = true
                        }
                    }
                    if (shouldCancel) {
                        val unused: Boolean = evaluateExpression.gracefullyCancel()
                    }
                })

        return env.whenSucceedsOrIsCancelledCall<Void?>(
            queryTaskFuture,
            QueryTaskCallable {
                if (evaluateExpression.isUngracefullyCancelled()) {
                    throw CancellationException()
                }
                if (targetsSet.isEmpty()) {
                    throw QueryException(
                        expression, "argument set is empty", Query.Code.ARGUMENTS_MISSING
                    )
                }
                null
            })
    }
}
