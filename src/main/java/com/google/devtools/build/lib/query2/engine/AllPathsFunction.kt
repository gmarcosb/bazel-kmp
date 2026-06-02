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

import com.google.common.base.Predicates
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.common.collect.Sets
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.*
import java.util.*

/**
 * Implementation of the `allpaths()` function.
 */
class AllPathsFunction : QueryFunction {
    override fun getName(): String {
        return "allpaths"
    }

    override fun getMandatoryArguments(): Int {
        return 2
    }

    override fun getArgumentTypes(): MutableList<QueryEnvironment.ArgumentType?> {
        return ImmutableList.of<QueryEnvironment.ArgumentType?>(
            QueryEnvironment.ArgumentType.EXPRESSION,
            QueryEnvironment.ArgumentType.EXPRESSION
        )
    }

    override fun requiresEdges(): Boolean {
        return true
    }

    override fun <T> eval(
        env: QueryEnvironment<T?>,
        context: QueryExpressionContext<T?>?,
        expression: QueryExpression?,
        args: MutableList<QueryEnvironment.Argument?>,
        callback: Callback<T?>
    ): QueryTaskFuture<Void?>? {
        if (env is StreamableQueryEnvironment<*>) {
            return (env as StreamableQueryEnvironment<T?>)
                .allPaths(
                    args.get(0)!!.getExpression(),
                    args.get(1)!!.getExpression(),
                    context,
                    callback,
                    expression
                )
        }

        val fromValueFuture =
            QueryUtil.evalAll<T?>(env, context, args.get(0)!!.getExpression())
        val toValueFuture =
            QueryUtil.evalAll<T?>(env, context, args.get(1)!!.getExpression())

        if (env is CustomFunctionQueryEnvironment<*>) {
            return env.whenAllSucceedCall<Void?>(
                ImmutableList.of<QueryTaskFuture<ThreadSafeMutableSet<T?>?>?>(fromValueFuture, toValueFuture),
                QueryTaskCallable {
                    val fromValue = fromValueFuture!!.getIfSuccessful()
                    val toValue = toValueFuture!!.getIfSuccessful()
                    (env as CustomFunctionQueryEnvironment<T?>)
                        .allPaths(fromValue, toValue, expression, callback)
                    null
                })
        }
        return env.whenAllSucceedCall<Void?>(
            ImmutableList.of<QueryTaskFuture<ThreadSafeMutableSet<T?>?>?>(fromValueFuture, toValueFuture),
            QueryTaskCallable {
                // Algorithm: compute "reachableFromX", the forward transitive closure of the "from" set,
                // then find the intersection of "reachableFromX" with the reverse transitive closure of
                // the "to" set.  The reverse transitive closure and intersection operations are
                // interleaved for efficiency. "result" holds the intersection.
                val fromValue = fromValueFuture!!.getIfSuccessful()
                val toValue = toValueFuture!!.getIfSuccessful()

                env.buildTransitiveClosure(expression, fromValue, OptionalInt.empty())

                val reachableFromX: MutableSet<T?> = env.getTransitiveClosure(fromValue, context)
                val reachable = Predicates.`in`<T?>(reachableFromX)
                val uniquifier = env.createUniquifier()
                val result: ImmutableList<T?> = uniquifier.unique(intersection<T?>(reachableFromX, toValue))
                callback.process(result)
                var worklist = result
                while (!worklist.isEmpty()) {
                    val reverseDeps = env.getReverseDeps(worklist, context)
                    worklist = uniquifier.unique(Iterables.filter<T?>(reverseDeps, reachable))
                    callback.process(worklist)
                }
                null
            })
    }

    companion object {
        /**
         * Returns a (new, mutable, unordered) set containing the intersection of the
         * two specified sets.
         */
        private fun <T> intersection(x: MutableSet<T?>, y: MutableSet<T?>): MutableSet<T?> {
            val result: MutableSet<T?> = HashSet<T?>()
            if (x.size > y.size) {
                Sets.intersection<T?>(y, x).copyInto<MutableSet<T?>?>(result)
            } else {
                Sets.intersection<T?>(x, y).copyInto<MutableSet<T?>?>(result)
            }
            return result
        }
    }
}
