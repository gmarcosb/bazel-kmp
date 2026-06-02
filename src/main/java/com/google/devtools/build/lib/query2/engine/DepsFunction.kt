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
import com.google.devtools.build.lib.profiler.Profiler

/**
 * A "deps" query expression, which computes the dependencies of the argument. An optional
 * integer-literal second argument may be specified; its value bounds the search from the arguments.
 * 
 * <pre>expr ::= DEPS '(' expr ')'</pre>
 * 
 * <pre>       | DEPS '(' expr ',' WORD ')'</pre>
 */
internal class DepsFunction : QueryFunction {
    val name: String
        get() = "deps"

    val mandatoryArguments: Int
        get() = 1 // last argument is optional

    val argumentTypes: MutableList<QueryEnvironment.ArgumentType?>
        get() = ImmutableList.of<QueryEnvironment.ArgumentType?>(
            QueryEnvironment.ArgumentType.EXPRESSION,
            QueryEnvironment.ArgumentType.INTEGER
        )

    override fun requiresEdges(): Boolean {
        return true
    }

    /** Breadth-first search from the arguments.  */
    override fun <T> eval(
        env: QueryEnvironment<T?>,
        context: QueryExpressionContext<T?>?,
        expression: QueryExpression?,
        args: MutableList<QueryEnvironment.Argument?>,
        callback: Callback<T?>
    ): QueryTaskFuture<Void?>? {
        val queryExpression = args.get(0)!!.getExpression()
        val maxDepth: OptionalInt =
            if (args.size > 1) OptionalInt.of(args.get(1)!!.getInteger()) else OptionalInt.empty()
        if (env is StreamableQueryEnvironment<*>) {
            return if (maxDepth.isPresent())
                (env as StreamableQueryEnvironment<T?>)
                    .getDepsBounded(queryExpression, context, callback, maxDepth.getAsInt(), expression)
            else
                (env as StreamableQueryEnvironment<T?>)
                    .getDepsUnboundedParallel(queryExpression, context, callback, expression)
        }

        if (env is CustomFunctionQueryEnvironment<*>) {
            // Not all expressions generate a single future (e.g. SetExpression), as such, we should batch
            // them here before the heavy blocking work is done in the callback to deps.
            return (env as CustomFunctionQueryEnvironment<*>)
                .eval(
                    queryExpression,
                    context,
                    Callback { result: Iterable<*>? ->
                        (env as CustomFunctionQueryEnvironment<T?>)
                            .deps(result, maxDepth, expression, callback)
                    },  /* batch= */
                    true
                )
        }

        val minDepthUniquifier = env.createMinDepthUniquifier()
        return env.eval(
            queryExpression,
            context,
            Callback { partialResult: Iterable<T?>? ->
                var current: ThreadSafeMutableSet<T?> = env.createThreadSafeMutableSet()
                Iterables.addAll<T?>(current, partialResult)
                Profiler.instance().profile("env.buildTransitiveClosure").use { closeable ->
                    env.buildTransitiveClosure(expression, current, maxDepth)
                }
                var i = 0
                while (QueryEnvironment.Companion.shouldVisit(maxDepth, i++)) {
                    // Filter already visited nodes: if we see a node in a later round, then we don't need
                    // to visit it again, because the depth at which we see it at must be greater than or
                    // equal to the last visit.
                    val toProcess: ImmutableList<T?>? =
                        minDepthUniquifier.uniqueAtDepthLessThanOrEqualTo(current, i)
                    callback.process(toProcess)
                    current = env.createThreadSafeMutableSet()
                    Profiler.instance().profile("env.getFwdDeps").use { closeable ->
                        Iterables.addAll<T?>(current, env.getFwdDeps(toProcess, context))
                    }
                    if (current.isEmpty()) {
                        // Exit when there are no more nodes to visit.
                        break
                    }
                }
            })
    }
}
