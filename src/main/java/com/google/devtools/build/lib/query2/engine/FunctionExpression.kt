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

import com.google.common.base.Functions
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.profiler.Profiler

/**
 * A query expression for user-defined query functions.
 */
class FunctionExpression(function: QueryFunction, args: MutableList<QueryEnvironment.Argument?>) : QueryExpression() {
    var function: QueryFunction
    var args: MutableList<QueryEnvironment.Argument>

    init {
        this.function = function
        this.args = ImmutableList.copyOf<QueryEnvironment.Argument?>(args)
    }

    fun getFunction(): QueryFunction {
        return function
    }

    override fun <T> eval(
        env: QueryEnvironment<T?>?, context: QueryExpressionContext<T?>?, callback: Callback<T?>?
    ): QueryTaskFuture<Void?>? {
        val result: QueryTaskFuture<Void?>?
        Profiler.instance().profile("function.eval/" + function.getName()).use { closeable ->
            result = function.eval<T?>(env, context, this, args, callback)
        }
        return result
    }

    override fun collectTargetPatterns(literals: MutableCollection<String?>?) {
        for (arg in args) {
            if (arg.getType() == QueryEnvironment.ArgumentType.EXPRESSION) {
                arg.getExpression().collectTargetPatterns(literals)
            }
        }
    }

    override fun <T, C> accept(visitor: QueryExpressionVisitor<T?, C?>, context: C?): T? {
        return visitor.visit(this, context)
    }

    override fun toString(): String {
        return (function.getName()
                + "("
                + args.stream().map<String?>(Functions.toStringFunction()).collect(Collectors.joining(", "))
                + ")")
    }
}
