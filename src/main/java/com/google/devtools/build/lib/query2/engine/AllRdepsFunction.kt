// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Predicate
import com.google.common.base.Predicates
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryTaskFuture
import java.util.*

/**
 * An "allrdeps" query expression, which computes the reverse dependencies of the argument within
 * the currently known universe. An optional integer-literal second argument may be specified; its
 * value bounds the search from the arguments.
 * 
 * <pre>expr ::= ALLRDEPS '(' expr ')'</pre>
 * <pre>       | ALLRDEPS '(' expr ',' WORD ')'</pre>
 */
// Public because SkyQueryEnvironment needs to refer to it directly.
open class AllRdepsFunction : QueryFunction {
    override fun getName(): String {
        return "allrdeps"
    }

    override fun requiresEdges(): Boolean {
        return true
    }

    override fun getMandatoryArguments(): Int {
        return 1 // last argument is optional
    }

    override fun getArgumentTypes(): MutableList<QueryEnvironment.ArgumentType?> {
        return ImmutableList.of<QueryEnvironment.ArgumentType?>(
            QueryEnvironment.ArgumentType.EXPRESSION,
            QueryEnvironment.ArgumentType.INTEGER
        )
    }

    override fun <T> eval(
        env: QueryEnvironment<T?>,
        context: QueryExpressionContext<T?>?,
        expression: QueryExpression?,
        args: MutableList<QueryEnvironment.Argument?>,
        callback: Callback<T?>
    ): QueryTaskFuture<Void?>? {
        val maxDepth =
            if (args.size == 1) OptionalInt.empty() else OptionalInt.of(args.get(1)!!.getInteger())
        val argumentExpression = args.get(0)!!.getExpression()
        if (env is StreamableQueryEnvironment<T?>) {
            return if (maxDepth.isPresent())
                env.getAllRdepsBoundedParallel(
                    argumentExpression, maxDepth.getAsInt(), context, callback
                )
            else
                env.getAllRdepsUnboundedParallel(argumentExpression, context, callback)
        } else {
            return eval<T?>(env, argumentExpression, Predicates.alwaysTrue<T?>(), context, callback, maxDepth)
        }
    }

    companion object {
        /** Common non-parallel implementation of depth-bounded allrdeps/deps.  */
        fun <T> eval(
            env: QueryEnvironment<T?>,
            expression: QueryExpression?,
            universe: Predicate<T?>,
            context: QueryExpressionContext<T?>?,
            callback: Callback<T?>,
            depth: OptionalInt
        ): QueryTaskFuture<Void?>? {
            val minDepthUniquifier = env.createMinDepthUniquifier()
            return env.eval(
                expression,
                context,
                Callback { partialResult: Iterable<T?>? ->
                    var current: Iterable<T?> = partialResult!!
                    var i = 0
                    while (QueryEnvironment.Companion.shouldVisit(depth, i++)) {
                        val next: MutableList<T?> = ArrayList<T?>()
                        // Restrict to nodes satisfying the universe predicate.
                        val currentInUniverse = Iterables.filter<T?>(current, universe)
                        // Filter already visited nodes: if we see a node in a later round, then we don't
                        // need to visit it again, because the depth at which we see it must be greater
                        // than or equal to the last visit.
                        Iterables.addAll<T?>(
                            next,
                            env.getReverseDeps(
                                minDepthUniquifier.uniqueAtDepthLessThanOrEqualTo(currentInUniverse, i),
                                context
                            )
                        )
                        callback.process(currentInUniverse)
                        if (next.isEmpty()) {
                            // Exit when there are no more nodes to visit.
                            break
                        }
                        current = next
                    }
                })
        }
    }
}
