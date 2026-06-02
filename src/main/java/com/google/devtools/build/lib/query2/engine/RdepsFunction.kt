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

import com.google.common.base.Function
import com.google.common.base.Predicate
import com.google.common.base.Predicates
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.*
import java.util.*

/**
 * An "rdeps" query expression, which computes the reverse dependencies of the argument within the
 * transitive closure of the universe. An optional integer-literal third argument may be
 * specified; its value bounds the search from the arguments.
 * 
 * <pre>expr ::= RDEPS '(' expr ',' expr ')'</pre>
 * <pre>       | RDEPS '(' expr ',' expr ',' WORD ')'</pre>
 */
class RdepsFunction : AllRdepsFunction() {
    override fun getName(): String {
        return "rdeps"
    }

    override fun requiresEdges(): Boolean {
        return true
    }

    override fun getMandatoryArguments(): Int {
        return super.getMandatoryArguments() + 1 // +1 for the universe.
    }

    override fun getArgumentTypes(): MutableList<QueryEnvironment.ArgumentType?> {
        return ImmutableList.builder<QueryEnvironment.ArgumentType?>()
            .add(QueryEnvironment.ArgumentType.EXPRESSION).addAll(super.getArgumentTypes()).build()
    }

    override fun <T> eval(
        env: QueryEnvironment<T?>,
        context: QueryExpressionContext<T?>?,
        expression: QueryExpression?,
        args: MutableList<QueryEnvironment.Argument?>,
        callback: Callback<T?>?
    ): QueryTaskFuture<Void?>? {
        val depth =
            if (args.size == 2) OptionalInt.empty() else OptionalInt.of(args.get(2)!!.getInteger())
        val universeExpression = args.get(0)!!.getExpression()
        val argumentExpression = args.get(1)!!.getExpression()
        if (env is StreamableQueryEnvironment<T?>) {
            return if (depth.isPresent())
                env.getRdepsBoundedParallel(
                    argumentExpression, depth.getAsInt(), universeExpression, context, callback
                )
            else
                env.getRdepsUnboundedParallel(
                    argumentExpression, universeExpression, context, callback
                )
        } else {
            return evalWithBoundedDepth<T?>(
                env, expression, context, argumentExpression, depth, universeExpression, callback
            )
        }
    }

    companion object {
        /**
         * Compute the transitive closure of the universe, then breadth-first search from the argument
         * towards the universe while staying within the transitive closure.
         */
        private fun <T> evalWithBoundedDepth(
            env: QueryEnvironment<T?>,
            rdepsFunctionExpressionForErrorMessages: QueryExpression?,
            context: QueryExpressionContext<T?>?,
            argumentExpression: QueryExpression?,
            depth: OptionalInt?,
            universeExpression: QueryExpression?,
            callback: Callback<T?>?
        ): QueryTaskFuture<Void?>? {
            val universeValueFuture =
                QueryUtil.evalAll<T?>(env, context, universeExpression)

            if (env is CustomFunctionQueryEnvironment<*>) {
                val fromValueFuture =
                    QueryUtil.evalAll<T?>(env, context, argumentExpression)
                return env.whenAllSucceedCall<Void?>(
                    ImmutableList.of<QueryTaskFuture<ThreadSafeMutableSet<T?>?>?>(fromValueFuture, universeValueFuture),
                    QueryTaskCallable {
                        val fromValue = fromValueFuture!!.getIfSuccessful()
                        val universeValue = universeValueFuture!!.getIfSuccessful()
                        (env as CustomFunctionQueryEnvironment<T?>)
                            .rdeps(
                                fromValue,
                                universeValue,
                                depth,
                                rdepsFunctionExpressionForErrorMessages,
                                callback
                            )
                        null
                    })
            }

            val evalInUniverseAsyncFunction =
                Function { universeValue: ThreadSafeMutableSet<T?>? ->
                    val universe: Predicate<T?>?
                    try {
                        env.buildTransitiveClosure(
                            rdepsFunctionExpressionForErrorMessages, universeValue, OptionalInt.empty()
                        )
                        universe = Predicates.`in`<T?>(env.getTransitiveClosure(universeValue, context))
                    } catch (e: InterruptedException) {
                        return@Function env.immediateCancelledFuture<Void?>()
                    } catch (e: QueryException) {
                        return@Function env.immediateFailedFuture<Void?>(e)
                    }
                    AllRdepsFunction.Companion.eval<T?>(env, argumentExpression, universe, context, callback, depth)
                }
            return env.transformAsync<ThreadSafeMutableSet<T?>?, Void?>(
                universeValueFuture,
                evalInUniverseAsyncFunction
            )
        }
    }
}
