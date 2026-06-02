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

import com.google.common.base.Function
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.*

/**
 * A visible(x, y) query expression, which computes the subset of nodes in y that are visible from
 * all nodes in x.
 * 
 * <pre>expr ::= VISIBILE '(' expr ',' expr ')'</pre>
 * 
 * 
 * Example: return targets from the package //bar/baz that are visible to //foo.
 * 
 * <pre>
 * visible(//foo, //bar/baz:*)
</pre> * 
 */
class VisibleFunction private constructor(private val invert: Boolean) : FilteringQueryFunction() {
    internal constructor() : this( /*invert=*/false)

    override fun invert(): FilteringQueryFunction {
        return VisibleFunction(!invert)
    }

    override fun getName(): String {
        return (if (invert) "no" else "") + "visible"
    }

    override fun getMandatoryArguments(): Int {
        return 2
    }

    override fun getExpressionToFilterIndex(): Int {
        return 1
    }

    override fun getArgumentTypes(): MutableList<QueryEnvironment.ArgumentType?> {
        return ImmutableList.of<QueryEnvironment.ArgumentType?>(
            QueryEnvironment.ArgumentType.EXPRESSION,
            QueryEnvironment.ArgumentType.EXPRESSION
        )
    }

    override fun <T> eval(
        env: QueryEnvironment<T?>,
        context: QueryExpressionContext<T?>?,
        expression: QueryExpression?,
        args: MutableList<QueryEnvironment.Argument?>,
        callback: Callback<T?>
    ): QueryTaskFuture<Void?>? {
        val toSetFuture =
            QueryUtil.evalAll<T?>(env, context, args.get(0)!!.getExpression())
        val computeVisibleNodesAsyncFunction =
            Function { toSet: ThreadSafeMutableSet<T?>? ->
                env.eval(
                    args.get(1)!!.getExpression(),
                    context,
                    Callback { partialResult: Iterable<T?>? ->
                        for (t in partialResult!!) {
                            if (invert xor Companion.visibleToAll<T?>(expression, env, toSet!!, t)) {
                                callback.process(ImmutableList.of<T?>(t))
                            }
                        }
                    })
            }
        return env.transformAsync<ThreadSafeMutableSet<T?>?, Void?>(toSetFuture, computeVisibleNodesAsyncFunction)
    }

    companion object {
        /** Returns true if `target` is visible to all targets in `toSet`.  */
        @Throws(QueryException::class, InterruptedException::class)
        private fun <T> visibleToAll(
            caller: QueryExpression?, env: QueryEnvironment<T?>, toSet: MutableSet<T?>, target: T?
        ): Boolean {
            for (to in toSet) {
                if (!visible<T?>(caller, env, to, target)) {
                    return false
                }
            }
            return true
        }

        /** Returns true if the target `from` is visible to the target `to`.  */
        @Throws(QueryException::class, InterruptedException::class)
        fun <T> visible(caller: QueryExpression?, env: QueryEnvironment<T?>, to: T?, from: T?): Boolean {
            val visiblePackages: MutableSet<QueryVisibility<T?>> = env.getAccessor().getVisibility(caller, from)
            for (spec in visiblePackages) {
                if (spec.contains(to)) {
                    return true
                }
            }
            return false
        }
    }
}
