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
import com.google.common.collect.Sets
import com.google.devtools.build.lib.cmdline.Label

/**
 * A loadfiles(x) query expression, which computes the set of .bzl files
 * for each target in set x.  The result is unordered.  This
 * operator is typically used for determining what files or packages to check
 * out.
 * 
 * <pre>expr ::= LOADFILES '(' expr ')'</pre>
 */
class LoadFilesFunction internal constructor() : QueryFunction {
    val name: String
        get() = "loadfiles"

    override fun <T> eval(
        env: QueryEnvironment<T?>,
        context: QueryExpressionContext<T?>?,
        expression: QueryExpression?,
        args: MutableList<QueryEnvironment.Argument?>,
        callback: Callback<T?>?
    ): QueryTaskFuture<Void?>? {
        val seenPackages: MutableSet<PackageIdentifier?> = Sets.newConcurrentHashSet<PackageIdentifier?>()
        val seenBzlLabels: MutableSet<Label?> = Sets.newConcurrentHashSet<Label?>()
        val uniquifier = env.createUniquifier()
        val helper: TransitiveLoadFilesHelper<T?>?
        try {
            helper = env.getTransitiveLoadFilesHelper()
        } catch (e: QueryException) {
            return env.immediateFailedFuture<Void?>(e)
        }
        return env.eval(
            args.get(0)!!.getExpression(),
            context,
            Callback { partialResult: Iterable<T?>? ->
                env.transitiveLoadFiles(
                    partialResult,  /* alsoAddBuildFiles= */
                    false,
                    seenPackages,
                    seenBzlLabels,
                    uniquifier,
                    helper,
                    callback
                )
            })
    }

    val mandatoryArguments: Int
        get() = 1

    val argumentTypes: MutableList<QueryEnvironment.ArgumentType?>
        get() = ImmutableList.of<QueryEnvironment.ArgumentType?>(
            QueryEnvironment.ArgumentType.EXPRESSION
        )
}
