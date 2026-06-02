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
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Sets
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.*
import java.util.*

/**
 * A somepath(x, y) query expression, which computes the set of nodes on some arbitrary path from a
 * target in set x to a target in set y.
 * 
 * <pre>expr ::= SOMEPATH '(' expr ',' expr ')'</pre>
 */
class SomePathFunction : QueryFunction {
    override fun getName(): String {
        return "somepath"
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
                .somePath(
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
                object : QueryTaskCallable<Void?> {
                    @Throws(QueryException::class, InterruptedException::class)
                    override fun call(): Void? {
                        val fromValue = fromValueFuture!!.getIfSuccessful()
                        val toValue = toValueFuture!!.getIfSuccessful()
                        (env as CustomFunctionQueryEnvironment<T?>)
                            .somePath(fromValue, toValue, expression, callback)
                        return null
                    }
                })
        }
        return env.whenAllSucceedCall<Void?>(
            ImmutableList.of<QueryTaskFuture<ThreadSafeMutableSet<T?>?>?>(fromValueFuture, toValueFuture),
            object : QueryTaskCallable<Void?> {
                @Throws(QueryException::class, InterruptedException::class)
                override fun call(): Void? {
                    // Implementation strategy: for each x in "from", compute its forward
                    // transitive closure.  If it intersects "to", then do a path search from x
                    // to an arbitrary node in the intersection, and return the path.  This
                    // avoids computing the full transitive closure of "from" in some cases.

                    val fromValue = fromValueFuture!!.getIfSuccessful()
                    val toValue = toValueFuture!!.getIfSuccessful()

                    env.buildTransitiveClosure(expression, fromValue, OptionalInt.empty())

                    for (x in fromValue) {
                        // TODO(b/122548314): if x was already seen as part of a previous node's tc, we should
                        // skip it here. That's subsumed by the TODO below.
                        val xSet = env.createThreadSafeMutableSet()
                        xSet.add(x)
                        // TODO(b/122548314): this transitive closure building should stop at any nodes that
                        // have already been visited.
                        val xtc = env.getTransitiveClosure(xSet, context)
                        val result: Sets.SetView<T?>?
                        if (xtc.size > toValue.size) {
                            result = Sets.intersection<T?>(toValue, xtc)
                        } else {
                            result = Sets.intersection<T?>(xtc, toValue)
                        }
                        if (!result.isEmpty()) {
                            callback.process(env.getNodesOnPath(x, result.iterator().next(), context))
                            return null
                        }
                    }
                    callback.process(ImmutableSet.of<T?>())
                    return null
                }
            })
    }
}
