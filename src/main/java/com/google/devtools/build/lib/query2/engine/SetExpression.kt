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

import com.google.common.base.Joiner
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryTaskFuture
import kotlin.collections.ArrayList
import kotlin.collections.MutableCollection
import kotlin.collections.MutableList

/**
 * A set(word, ..., word) expression, which computes the union of zero or more
 * target patterns separated by whitespace.  This is intended to support the
 * use-case in which a set of labels written to a file by a previous query
 * expression can be modified externally, then used as input to another query,
 * like so:
 * 
 * <pre>
 * % blaze query 'somepath(foo, bar)' | grep ... | sed ... | awk ... >file
 * % blaze query "kind(qux_library, set($(<file></file>)))"
</pre> * 
 * 
 * 
 * The grammar currently restricts the operands of set() to being zero or
 * more words (target patterns), with no intervening punctuation.  In principle
 * this could be extended to arbitrary expressions without grammatical
 * ambiguity, but this seems excessively general for now.
 * 
 * <pre>expr ::= SET '(' WORD * ')'</pre>
 */
class SetExpression(
    /** Gets the list of [TargetLiteral]s contained in the expression.  */
    val words: MutableList<TargetLiteral>
) : QueryExpression() {
    override fun <T> eval(
        env: QueryEnvironment<T?>, context: QueryExpressionContext<T?>?, callback: Callback<T?>?
    ): QueryTaskFuture<Void?>? {
        val queryTasks = ArrayList<QueryTaskFuture<Void?>?>(words.size)
        for (expr in words) {
            queryTasks.add(env.eval(expr, context, callback))
        }
        return env.whenAllSucceed(queryTasks)
    }

    override fun collectTargetPatterns(literals: MutableCollection<String?>?) {
        for (expr in words) {
            expr.collectTargetPatterns(literals)
        }
    }

    override fun <T, C> accept(visitor: QueryExpressionVisitor<T?, C?>, context: C?): T? {
        return visitor.visit(this, context)
    }

    override fun toString(): String {
        return "set(" + Joiner.on(' ').join(words) + ")"
    }
}
