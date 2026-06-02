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

import com.google.common.base.CharMatcher
import com.google.common.base.Preconditions
import com.google.devtools.build.lib.server.FailureDetails.Query

/**
 * A literal set of targets, using 'blaze build' syntax. Or, a reference to a variable name. (The
 * syntax of the string "pattern" determines which.)
 * 
 * 
 * TODO(bazel-team): Perhaps we should distinguish NAME from WORD in the parser, based on the
 * characters in it? Also, perhaps we should not allow NAMEs to be quoted like WORDs can be.
 * 
 * <pre>expr ::= NAME | WORD</pre>
 */
class TargetLiteral(pattern: String?) : QueryExpression() {
    val pattern: String

    init {
        this.pattern = Preconditions.checkNotNull<String>(pattern)
    }

    val isVariableReference: Boolean
        get() = LetExpression.Companion.isValidVarReference(pattern)

    private fun <T> evalVarReference(
        env: QueryEnvironment<T?>, context: QueryExpressionContext<T?>, callback: Callback<T?>
    ): QueryTaskFuture<Void?>? {
        val varName: String = LetExpression.Companion.getNameFromReference(pattern)
        val value = context.get(varName)
        if (value == null) {
            return env.immediateFailedFuture<Void?>(
                QueryException(
                    this, "undefined variable '" + varName + "'", Query.Code.VARIABLE_UNDEFINED
                )
            )
        }
        try {
            callback.process(value)
            return env.immediateSuccessfulFuture<Void?>(null)
        } catch (e: QueryException) {
            return env.immediateFailedFuture<Void?>(e)
        } catch (e: InterruptedException) {
            return env.immediateCancelledFuture<Void?>()
        }
    }

    override fun <T> eval(
        env: QueryEnvironment<T?>, context: QueryExpressionContext<T?>, callback: Callback<T?>
    ): QueryTaskFuture<Void?>? {
        if (this.isVariableReference) {
            return evalVarReference<T?>(env, context, callback)
        } else {
            return env.getTargetsMatchingPattern(this, pattern, callback)
        }
    }

    override fun collectTargetPatterns(literals: MutableCollection<String?>) {
        if (!this.isVariableReference) {
            literals.add(pattern)
        }
    }

    override fun <T, C> accept(visitor: QueryExpressionVisitor<T?, C?>, context: C?): T? {
        return visitor.visit(this, context)
    }

    override fun toString(): String {
        // The character matching has to be in sync with LabelValidator#PUNCTUATION_REQUIRING_QUOTING
        // except for the special characters that Lexer#scanWord *\/@.-_:$~ consider to be a word.
        val needsQuoting =
            Lexer.Companion.isReservedWord(pattern)
                    || pattern.isEmpty()
                    || pattern.startsWith("-")
                    || pattern.startsWith("*")
                    || CharMatcher.anyOf(" \"#&'()+,;<=>?[]{|}").matchesAnyOf(pattern)

        if (!needsQuoting) {
            return pattern
        }

        /**
         * If the word requires quoting, we want to quote the word such that the quoting character does
         * not lex the result differently if the result toString is fed back into the parser. For
         * example: If the following Java string that requires quoting is set("foo"), and we quote the
         * Java string with double quotes, "set("foo")" and feed it back into the lexer, the lexer will
         * parse the following word set(. So in this case we want to quote it with single quotes such
         * that it would look like 'set("foo")'. In the case that we find both single quote and double
         * quote, we would fail and use either of the two quotes.
         */
        val quote = if (pattern.contains("\"")) '\'' else '"'
        return quote.toString() + pattern + quote
    }
}
