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
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.FilteringQueryFunction

/**
 * A kind(pattern, argument) filter expression, which computes the set of subset of nodes in
 * 'argument' whose kind matches the unanchored regexp 'pattern'.
 * 
 * <pre>expr ::= KIND '(' WORD ',' expr ')'</pre>
 * 
 * Example patterns:
 * 
 * <pre>
 * ' file'              Match all file targets.
 * 'source file'        Match all test source file targets.
 * 'generated file'     Match all test generated file targets.
 * ' rule'              Match all rule targets.
 * 'foo_*'              Match all rules starting with "foo_",
 * 'test'               Match all test (rule) targets.
</pre> * 
 * 
 * Note, the space before "file" is needed to prevent unwanted matches against (e.g.) "filegroup
 * rule".
 */
class KindFunction : RegexFilterExpression {
    constructor() : super( /*invert=*/false)

    private constructor(invert: Boolean) : super(invert)

    override fun invert(): FilteringQueryFunction {
        return KindFunction(!invert)
    }

    override fun getName(): String {
        return (if (invert) "no" else "") + "kind"
    }

    override fun getPattern(args: MutableList<QueryEnvironment.Argument?>): String? {
        return args.get(0)!!.getWord()
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

    override fun <T> getFilterString(
        env: QueryEnvironment<T?>,
        args: MutableList<QueryEnvironment.Argument?>?,
        target: T?
    ): String? {
        return env.getAccessor().getTargetKind(target)
    }
}
