// Copyright 2016 The Bazel Authors. All rights reserved.
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

/**
 * Performs an arbitrary contextual transformation of a [QueryExpression].
 * 
 * 
 * For each subclass of [QueryExpression], there's a corresponding [.visit] overload
 * that transforms a node of that type. By default, this method recursively applies this [ ]'s transformation in a structure-preserving manner (trying to maintain
 * reference-equality, as an optimization). Subclasses of [QueryExpressionMapper] can override
 * these methods in order to implement an arbitrary transformation.
 */
abstract class QueryExpressionMapper<C>
    : QueryExpressionVisitor<QueryExpression?, C?> {
    override fun visit(targetLiteral: TargetLiteral?, context: C?): QueryExpression? {
        return targetLiteral
    }

    override fun visit(binaryOperatorExpression: BinaryOperatorExpression, context: C?): QueryExpression? {
        var changed = false
        val mappedOperandsBuilder = ImmutableList.builder<QueryExpression?>()
        for (operand in binaryOperatorExpression.getOperands()) {
            val mappedOperand = operand.accept<QueryExpression, C?>(this, context)
            if (mappedOperand !== operand) {
                changed = true
            }
            mappedOperandsBuilder.add(mappedOperand)
        }
        return if (changed)
            BinaryOperatorExpression(
                binaryOperatorExpression.getOperator(), mappedOperandsBuilder.build()
            )
        else
            binaryOperatorExpression
    }

    override fun visit(functionExpression: FunctionExpression, context: C?): QueryExpression? {
        var changed = false
        val mappedArgumentBuilder = ImmutableList.builder<QueryEnvironment.Argument?>()
        for (argument in functionExpression.getArgs()) {
            when (argument.getType()) {
                QueryEnvironment.ArgumentType.EXPRESSION -> {
                    val expr = argument.getExpression()
                    val mappedExpression = expr.accept<QueryExpression?, C?>(this, context)
                    mappedArgumentBuilder.add(QueryEnvironment.Argument.Companion.of(mappedExpression))
                    if (expr !== mappedExpression) {
                        changed = true
                    }
                }

                else -> mappedArgumentBuilder.add(argument)
            }
        }
        return if (changed)
            FunctionExpression(functionExpression.getFunction(), mappedArgumentBuilder.build())
        else
            functionExpression
    }

    override fun visit(letExpression: LetExpression, context: C?): QueryExpression? {
        var changed = false
        val mappedVarExpr = letExpression.getVarExpr().accept<QueryExpression?, C?>(this, context)
        if (mappedVarExpr !== letExpression.getVarExpr()) {
            changed = true
        }
        val mappedBodyExpr = letExpression.getBodyExpr().accept<QueryExpression?, C?>(this, context)
        if (mappedBodyExpr !== letExpression.getBodyExpr()) {
            changed = true
        }
        return if (changed)
            LetExpression(letExpression.getVarName(), mappedVarExpr, mappedBodyExpr)
        else
            letExpression
    }

    override fun visit(setExpression: SetExpression?, context: C?): QueryExpression? {
        return setExpression
    }

    private class ComposedQueryExpressionMapper<C>(private val mappers: ImmutableList<QueryExpressionMapper<C?>?>) :
        QueryExpressionMapper<C?>() {
        override fun visit(targetLiteral: TargetLiteral, context: C?): QueryExpression {
            return mapAll<C?>(targetLiteral, mappers, context)
        }

        override fun visit(binaryOperatorExpression: BinaryOperatorExpression, context: C?): QueryExpression {
            return mapAll<C?>(binaryOperatorExpression, mappers, context)
        }

        override fun visit(functionExpression: FunctionExpression, context: C?): QueryExpression {
            return mapAll<C?>(functionExpression, mappers, context)
        }

        override fun visit(letExpression: LetExpression, context: C?): QueryExpression {
            return mapAll<C?>(letExpression, mappers, context)
        }

        override fun visit(setExpression: SetExpression, context: C?): QueryExpression {
            return mapAll<C?>(setExpression, mappers, context)
        }

        companion object {
            private fun <C> mapAll(
                expression: QueryExpression, mappers: ImmutableList<QueryExpressionMapper<C?>?>, context: C?
            ): QueryExpression {
                var expr = expression
                for (i in mappers.indices.reversed()) {
                    expr = expr.accept<QueryExpression, C?>(mappers.get(i), context)
                }

                return expr
            }
        }
    }

    private class IdentityMapper : QueryExpressionMapper<Void?>() {
        override fun visit(targetLiteral: TargetLiteral?, context: Void?): QueryExpression? {
            return targetLiteral
        }

        override fun visit(binaryOperatorExpression: BinaryOperatorExpression?, context: Void?): QueryExpression? {
            return binaryOperatorExpression
        }

        override fun visit(functionExpression: FunctionExpression?, context: Void?): QueryExpression? {
            return functionExpression
        }

        override fun visit(letExpression: LetExpression?, context: Void?): QueryExpression? {
            return letExpression
        }

        override fun visit(setExpression: SetExpression?, context: Void?): QueryExpression? {
            return setExpression
        }

        companion object {
            private val INSTANCE = IdentityMapper()
        }
    }

    companion object {
        fun identity(): QueryExpressionMapper<Void?> {
            return IdentityMapper.Companion.INSTANCE
        }

        /**
         * Returns a [QueryExpressionMapper] which applies all the mappings provided by `mappers`, in the reverse order of mapper array.
         */
        fun <C> compose(
            mappers: ImmutableList<QueryExpressionMapper<C?>?>
        ): QueryExpressionMapper<C?> {
            return ComposedQueryExpressionMapper<C?>(mappers)
        }
    }
}

