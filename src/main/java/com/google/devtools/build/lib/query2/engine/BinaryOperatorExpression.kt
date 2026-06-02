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
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.*
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableCollection
import kotlin.collections.MutableList
import kotlin.collections.MutableSet

/**
 * A binary algebraic set operation.
 * 
 * <pre>
 * expr ::= expr (INTERSECT expr)+
 * | expr ('^' expr)+
 * | expr (UNION expr)+
 * | expr ('+' expr)+
 * | expr (EXCEPT expr)+
 * | expr ('-' expr)+
</pre> * 
 */
class BinaryOperatorExpression(operator: Lexer.TokenKind, operands: MutableList<QueryExpression?>) : QueryExpression() {
    val operator: Lexer.TokenKind // ::= INTERSECT/CARET | UNION/PLUS | EXCEPT/MINUS
    val operands: ImmutableList<QueryExpression>

    init {
        Preconditions.checkState(operands.size > 1)
        this.operator = operator
        this.operands = ImmutableList.copyOf<QueryExpression?>(operands)
    }

    override fun <T> eval(
        env: QueryEnvironment<T?>, context: QueryExpressionContext<T?>?, callback: Callback<T?>
    ): QueryTaskFuture<Void?>? {
        return when (operator) {
            Lexer.TokenKind.PLUS, Lexer.TokenKind.UNION -> evalPlus<T?>(operands, env, context, callback)
            Lexer.TokenKind.MINUS, Lexer.TokenKind.EXCEPT -> evalMinus<T?>(operands, env, context, callback)
            Lexer.TokenKind.INTERSECT, Lexer.TokenKind.CARET -> evalIntersect<T?>(env, context, callback)
            else -> throw IllegalStateException(operator.toString())
        }
    }

    private fun <T> evalIntersect(
        env: QueryEnvironment<T?>,
        context: QueryExpressionContext<T?>?,
        callback: Callback<T?>
    ): QueryTaskFuture<Void?>? {
        // For each right-hand side operand, intersection cannot be performed in a streaming manner; the
        // entire result of that operand is needed. So, in order to avoid pinning too much in memory at
        // once, we process each right-hand side operand one at a time and throw away that operand's
        // result.
        // TODO(bazel-team): Consider keeping just the name / label of the right-hand side results
        // instead of the potentially heavy-weight instances of type T. This would let us process all
        // right-hand side operands in parallel without worrying about memory usage.
        var rollingResultFuture =
            QueryUtil.evalAll<T?>(env, context, operands.get(0))
        for (i in 1..<operands.size) {
            val index = i
            val evalOperandAndIntersectAsyncFunction: com.google.common.base.Function<ThreadSafeMutableSet<T?>, QueryTaskFuture<ThreadSafeMutableSet<T?>?>?> =
                Function { rollingResult: ThreadSafeMutableSet<T?> ->
                    val rhsOperandValueFuture =
                        QueryUtil.evalAll<T?>(env, context, operands.get(index))
                    env.whenSucceedsCall<ThreadSafeMutableSet<T?>?>(
                        rhsOperandValueFuture,
                        object : QueryTaskCallable<ThreadSafeMutableSet<T?>?> {
                            @Throws(QueryException::class, InterruptedException::class)
                            override fun call(): ThreadSafeMutableSet<T?> {
                                rollingResult.retainAll(rhsOperandValueFuture.getIfSuccessful()!!)
                                return@Function rollingResult
                            }
                        })
                }
            rollingResultFuture =
                env.transformAsync<ThreadSafeMutableSet<T?>?, ThreadSafeMutableSet<T?>?>(
                    rollingResultFuture,
                    evalOperandAndIntersectAsyncFunction
                )
        }
        val resultFuture = rollingResultFuture
        return env.whenSucceedsCall<Void?>(
            resultFuture,
            object : QueryTaskCallable<Void?> {
                @Throws(QueryException::class, InterruptedException::class)
                override fun call(): Void? {
                    callback.process(resultFuture.getIfSuccessful())
                    return null
                }
            })
    }

    override fun collectTargetPatterns(literals: MutableCollection<String?>?) {
        for (subExpression in operands) {
            subExpression.collectTargetPatterns(literals)
        }
    }

    override fun <T, C> accept(visitor: QueryExpressionVisitor<T?, C?>, context: C?): T? {
        return visitor.visit(this, context)
    }

    override fun toString(): String {
        val result = StringBuilder()
        result.append("(")
        result.append(operands.get(0))
        for (expr in operands.subList(1, operands.size)) {
            result.append(" ").append(operator.getPrettyName()).append(" ").append(expr)
        }
        result.append(")")
        return result.toString()
    }

    companion object {
        /**
         * Evaluates an expression of the form "e1 + e2 + ... + eK" by evaluating all the subexpressions
         * separately.
         * 
         * 
         * N.B. `operands.size()` may be `1`.
         */
        private fun <T> evalPlus(
            operands: ImmutableList<QueryExpression>,
            env: QueryEnvironment<T?>,
            context: QueryExpressionContext<T?>?,
            callback: Callback<T?>?
        ): QueryTaskFuture<Void?>? {
            val queryTasks = ArrayList<QueryTaskFuture<Void?>?>(operands.size)
            for (operand in operands) {
                queryTasks.add(env.eval(operand, context, callback))
            }
            return env.whenAllSucceed(queryTasks)
        }

        /**
         * Evaluates an expression of the form "e1 - e2 - ... - eK" by noting its equivalence to "e1 - (e2
         * + ... + eK)" and evaluating the subexpressions on the right-hand-side separately.
         */
        private fun <T> evalMinus(
            operands: ImmutableList<QueryExpression>,
            env: QueryEnvironment<T?>,
            context: QueryExpressionContext<T?>?,
            callback: Callback<T?>
        ): QueryTaskFuture<Void?> {
            val lhsValueFuture =
                QueryUtil.evalAll<T?>(env, context, operands.get(0))
            val subtractAsyncFunction =
                Function { lhsValue: ThreadSafeMutableSet<T?>? ->
                    val threadSafeLhsValue: MutableSet<T?>? = lhsValue
                    val subtractionCallback: Callback<T?> =
                        object : Callback<T?> {
                            override fun process(partialResult: Iterable<T?>) {
                                for (target in partialResult) {
                                    threadSafeLhsValue!!.remove(target)
                                }
                            }
                        }
                    val rhsEvaluatedFuture: QueryTaskFuture<Void?>? =
                        evalPlus<T?>(operands.subList(1, operands.size), env, context, subtractionCallback)
                    env.whenSucceedsCall<Void?>(
                        rhsEvaluatedFuture,
                        object : QueryTaskCallable<Void?> {
                            @Throws(QueryException::class, InterruptedException::class)
                            override fun call(): Void? {
                                callback.process(threadSafeLhsValue)
                                return@Function null
                            }
                        })
                }
            return env.transformAsync<ThreadSafeMutableSet<T?>?, Void?>(lhsValueFuture, subtractAsyncFunction)
        }
    }
}
