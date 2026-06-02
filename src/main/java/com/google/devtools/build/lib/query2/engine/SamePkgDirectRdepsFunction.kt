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
package com.google.devtools.build.lib.query2.engine

import com.google.common.base.Predicate
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.*
import java.util.*

/**
 * A "same_pkg_direct_rdeps" query expression, which computes all of the targets in the same package
 * of the given targets which directly depend on them.
 * 
 * <pre>expr ::= SAME_PKG_DIRECT_RDEPS '(' expr ')'</pre>
 */
class SamePkgDirectRdepsFunction : QueryFunction {
    override fun getName(): String {
        return "same_pkg_direct_rdeps"
    }

    override fun requiresEdges(): Boolean {
        return true
    }

    override fun getMandatoryArguments(): Int {
        return 1
    }

    override fun getArgumentTypes(): Iterable<QueryEnvironment.ArgumentType?> {
        return ImmutableList.of<QueryEnvironment.ArgumentType?>(QueryEnvironment.ArgumentType.EXPRESSION)
    }

    override fun <T> eval(
        env: QueryEnvironment<T?>,
        context: QueryExpressionContext<T?>?,
        expression: QueryExpression?,
        args: MutableList<QueryEnvironment.Argument?>,
        callback: Callback<T?>
    ): QueryTaskFuture<Void?>? {
        if (env is CustomFunctionQueryEnvironment<*>) {
            return env.eval(
                args.get(0)!!.getExpression(),
                context,
                Callback { partialResult: Iterable<T?>? ->
                    (env as CustomFunctionQueryEnvironment<T?>)
                        .samePkgDirectRdeps(partialResult, expression, callback)
                })
        }
        val uniquifier = env.createUniquifier()
        return env.eval(
            args.get(0)!!.getExpression(),
            context,
            Callback { partialResult: Iterable<T?>? ->
                for (target in partialResult!!) {
                    val siblings = env.createThreadSafeMutableSet()
                    siblings.addAll(env.getSiblingTargetsInPackage(target))
                    env.buildTransitiveClosure(expression, siblings, DEPTH_ONE)
                    val rdeps = env.getReverseDeps(mutableSetOf<T?>(target), context)
                    callback.process(
                        uniquifier.unique(
                            Iterables.filter<T?>(
                                rdeps,
                                Predicate { o: T? -> siblings.contains(o) })
                        )
                    )
                }
            })
    }

    companion object {
        val DEPTH_ONE: OptionalInt = OptionalInt.of(1)
    }
}
