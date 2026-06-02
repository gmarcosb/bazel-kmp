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

import com.google.common.base.Function
import com.google.devtools.build.lib.server.FailureDetails.Query
import java.util.regex.Pattern

/**
 * A let expression.
 * 
 * <pre>expr ::= LET WORD = expr IN expr</pre>
 */
class LetExpression(val varName: String, val varExpr: QueryExpression, val bodyExpr: QueryExpression) :
    QueryExpression() {
    override fun <T> eval(
        env: QueryEnvironment<T?>,
        context: QueryExpressionContext<T?>,
        callback: Callback<T?>?
    ): QueryTaskFuture<Void?>? {
        if (!NAME_PATTERN.matcher(varName).matches()) {
            return env.immediateFailedFuture<Void?>(
                QueryException(
                    this,
                    "invalid variable name '" + varName + "' in let expression",
                    Query.Code.VARIABLE_NAME_INVALID
                )
            )
        }
        val varValueFuture: QueryTaskFuture<ThreadSafeMutableSet<T?>?>? =
            QueryUtil.evalAll<T?>(env, context, varExpr)
        val evalBodyAsyncFunction: Function<ThreadSafeMutableSet<T?>?, QueryTaskFuture<Void?>?> =
            Function { varValue: ThreadSafeMutableSet<T?>? ->
                val bodyContext = context.with(varName, varValue)
                env.eval(bodyExpr, bodyContext, callback)
            }
        return env.transformAsync<ThreadSafeMutableSet<T?>?, Void?>(varValueFuture, evalBodyAsyncFunction)
    }

    override fun collectTargetPatterns(literals: MutableCollection<String?>?) {
        varExpr.collectTargetPatterns(literals)
        bodyExpr.collectTargetPatterns(literals)
    }

    override fun <T, C> accept(visitor: QueryExpressionVisitor<T?, C?>, context: C?): T? {
        return visitor.visit(this, context)
    }

    override fun toString(): String {
        return "let " + varName + " = " + varExpr + " in " + bodyExpr
    }

    companion object {
        private const val VAR_NAME_PATTERN = "[a-zA-Z_][a-zA-Z0-9_]*$"

        // Variables names may be any legal identifier in the C programming language
        private val NAME_PATTERN: Pattern = Pattern.compile("^" + VAR_NAME_PATTERN)

        // Variable references are prepended with the "$" character.
        // A variable named "x" is referenced as "$x".
        private val REF_PATTERN: Pattern = Pattern.compile("^\\$" + VAR_NAME_PATTERN)

        fun isValidVarReference(varName: String?): Boolean {
            return REF_PATTERN.matcher(varName).matches()
        }

        fun getNameFromReference(reference: String): String {
            return reference.substring(1)
        }
    }
}
