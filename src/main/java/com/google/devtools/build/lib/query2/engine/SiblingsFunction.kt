// Copyright 2017 The Bazel Authors. All rights reserved.
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
import com.google.common.collect.Sets
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryTaskFuture

/**
 * A "siblings" query expression, which computes all of the targets in all of the packages of all
 * the targets to which the argument evaluates.
 * 
 * <pre>expr ::= SIBLINGS '(' expr ')'</pre>
 */
class SiblingsFunction : QueryFunction {
    override fun getName(): String {
        return "siblings"
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
        val targetAccessor = env.getAccessor()
        val packageNames = Sets.newConcurrentHashSet<String?>()
        return env.eval(
            args.get(0)!!.getExpression(),
            context,
            object : Callback<T?> {
                @Throws(QueryException::class, InterruptedException::class)
                override fun process(partialResult: Iterable<T?>) {
                    for (target in partialResult) {
                        if (packageNames.add(targetAccessor.getPackage(target))) {
                            callback.process(env.getSiblingTargetsInPackage(target))
                        }
                    }
                }
            })
    }
}
