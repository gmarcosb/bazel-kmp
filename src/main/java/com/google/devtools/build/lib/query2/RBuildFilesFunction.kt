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
package com.google.devtools.build.lib.query2

/**
 * An "rbuildfiles" query expression, which computes the set of packages (as represented by their
 * BUILD source file targets) that depend on the given set of files, either as BUILD files directly
 * or as subincludes. Is morally the inverse of the "buildfiles" operator, although that operator
 * takes targets and returns subinclude targets, while this takes files and returns BUILD file
 * targets.
 * 
 * <pre>expr ::= RBUILDFILES '(' WORD, ... ')'</pre>
 * 
 * 
 * This expression can only be used with SkyQueryEnvironment.
 */
class RBuildFilesFunction : QueryFunction {
    val name: String
        get() = "rbuildfiles"

    val mandatoryArguments: Int
        get() = 1

    val argumentTypes: Iterable<com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType>
        get() = com.google.common.collect.Iterables.cycle<com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType?>(
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType.WORD
        )

    // Cast from <T> to <Target>. This will only be used with <Target>.
    override fun <T> eval(
        env: QueryEnvironment<T?>,
        context: QueryExpressionContext<T?>?,
        expression: QueryExpression?,
        args: MutableList<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument?>,
        callback: com.google.devtools.build.lib.query2.engine.Callback<T?>?
    ): QueryTaskFuture<java.lang.Void?>? {
        if (env !is SkyQueryEnvironment) {
            return env.immediateFailedFuture<java.lang.Void?>(
                com.google.devtools.build.lib.query2.engine.QueryException(
                    "rbuildfiles can only be used with SkyQueryEnvironment",
                    Query.Code.RBUILDFILES_FUNCTION_REQUIRES_SKYQUERY
                )
            )
        }
        return env.getRBuildFiles(
            args.stream()
                .map<PathFragment?> { argument: com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument? ->
                    PathFragment.create(
                        argument.getWord()
                    )
                }
                .collect(Collectors.toList()),
            context as QueryExpressionContext<Target?>?,
            callback as com.google.devtools.build.lib.query2.engine.Callback<Target?>?)
    }
}
