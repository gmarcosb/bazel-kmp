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
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryTaskFuture
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableList

/**
 * A label(attr_name, argument) expression, which computes the set of targets whose labels appear in
 * the specified attribute of some rule in 'argument'.
 * 
 * <pre>expr ::= LABELS '(' WORD ',' expr ')'</pre>
 * 
 * Example:
 * 
 * <pre>
 * labels(srcs, //foo)      The 'srcs' source files to the //foo rule.
</pre> * 
 */
class LabelsFunction internal constructor() : QueryFunction {
    override fun getName(): String {
        return "labels"
    }

    override fun getMandatoryArguments(): Int {
        return 2
    }

    override fun getArgumentTypes(): MutableList<QueryEnvironment.ArgumentType?> {
        return ImmutableList.of<QueryEnvironment.ArgumentType?>(
            QueryEnvironment.ArgumentType.WORD,
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
        val attrName = args.get(0)!!.getWord()
        val uniquifier = env.createUniquifier()
        return env.eval(
            args.get(1)!!.getExpression(),
            context,
            object : Callback<T?> {
                @Throws(QueryException::class, InterruptedException::class)
                override fun process(partialResult: Iterable<T?>) {
                    for (input in partialResult) {
                        if (env.getAccessor().isRule(input)) {
                            val targets: MutableList<T?> =
                                uniquifier.unique(
                                    env.getAccessor()
                                        .getPrerequisites(
                                            expression,
                                            input,
                                            attrName,
                                            ("in '"
                                                    + attrName
                                                    + "' of rule "
                                                    + env.getAccessor().getLabel(input)
                                                    + ": ")
                                        )
                                )
                            val result: MutableList<T?> = ArrayList<T?>(targets.size)
                            for (target in targets) {
                                result.add(env.getOrCreate(target))
                            }
                            callback.process(result)
                        }
                    }
                }
            })
    }
}
