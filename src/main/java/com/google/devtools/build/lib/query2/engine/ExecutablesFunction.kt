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

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Predicate
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryTaskFuture

/**
 * An executables(x) filter expression, which returns all the executables (not including tests) in
 * set x.
 * 
 * <pre>expr ::= EXECUTABLES '(' expr ')'</pre>
 */
class ExecutablesFunction @VisibleForTesting constructor() : QueryFunction {
    override fun getName(): String {
        return "executables"
    }

    override fun getMandatoryArguments(): Int {
        return 1
    }

    override fun getArgumentTypes(): MutableList<QueryEnvironment.ArgumentType?> {
        return ImmutableList.of<QueryEnvironment.ArgumentType?>(QueryEnvironment.ArgumentType.EXPRESSION)
    }

    override fun <T> eval(
        env: QueryEnvironment<T?>,
        context: QueryExpressionContext<T?>?,
        expression: QueryExpression?,
        args: MutableList<QueryEnvironment.Argument?>,
        callback: Callback<T?>
    ): QueryTaskFuture<Void?>? {
        val accessor = env.getAccessor()

        return env.eval(
            args.get(0)!!.getExpression(),
            context,
            Callback { partialResult: Iterable<T?>? ->
                callback.process(
                    Iterables.filter<T?>(
                        partialResult,
                        Predicate { target: T? -> accessor.isExecutableNonTestRule(target) })
                )
            })
    }
}
