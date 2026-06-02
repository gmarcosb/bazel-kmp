// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.primitives.Booleans
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction
import kotlin.collections.HashMap
import kotlin.collections.Iterable
import kotlin.collections.MutableMap

/**
 * An implementation of [QueryExpressionVisitor] which recursively visits all nested [ ]s.
 */
abstract class AggregatingQueryExpressionVisitor<T, C>
    : QueryExpressionVisitor<T?, C?> {
    override fun visit(binaryOperatorExpression: BinaryOperatorExpression, context: C?): T? {
        val queryExpressionMapping: MutableMap<QueryExpression?, T?> = HashMap<QueryExpression?, T?>()
        for (expr in binaryOperatorExpression.getOperands()) {
            queryExpressionMapping.put(expr, expr.accept<T?, C?>(this, context))
        }
        return aggregate(ImmutableMap.copyOf<QueryExpression?, T?>(queryExpressionMapping))
    }

    override fun visit(functionExpression: FunctionExpression, context: C?): T? {
        val queryExpressionMapping: MutableMap<QueryExpression?, T?> = HashMap<QueryExpression?, T?>()
        for (argument in functionExpression.getArgs()) {
            if (argument.getType() == QueryEnvironment.ArgumentType.EXPRESSION) {
                queryExpressionMapping.put(
                    argument.getExpression(), argument.getExpression().accept<T?, C?>(this, context)
                )
            }
        }

        return aggregate(ImmutableMap.copyOf<QueryExpression?, T?>(queryExpressionMapping))
    }

    override fun visit(letExpression: LetExpression, context: C?): T? {
        return aggregate(
            ImmutableMap.of<QueryExpression?, T?>(
                letExpression.getVarExpr(), letExpression.getVarExpr().accept<T?, C?>(this, context),
                letExpression.getBodyExpr(), letExpression.getBodyExpr().accept<T?, C?>(this, context)
            )
        )
    }

    override fun visit(setExpression: SetExpression, context: C?): T? {
        val queryExpressionMapping: MutableMap<QueryExpression?, T?> = HashMap<QueryExpression?, T?>()
        for (targetLiteral in setExpression.getWords()) {
            queryExpressionMapping.put(targetLiteral, targetLiteral.accept<T?, C?>(this, context))
        }

        return aggregate(ImmutableMap.copyOf<QueryExpression?, T?>(queryExpressionMapping))
    }

    /**
     * Aggregates results from all sub-expression visitations. The map is guaranteed to include
     * results for all direct sub-expressions.
     */
    protected abstract fun aggregate(resultMap: ImmutableMap<QueryExpression?, T?>?): T?

    /**
     * Returns `true` when the query expression contains at least one [QueryFunction]
     * whose name is in the set of `functionName`.
     */
    class ContainsFunctionQueryExpressionVisitor
        (functionNames: Iterable<String?>) : AggregatingQueryExpressionVisitor<Boolean?, Void?>(),
        QueryExpressionVisitor<Boolean?, Void?> {
        private val functionNames: ImmutableSet<String?>

        init {
            this.functionNames = ImmutableSet.copyOf<String?>(functionNames)
        }

        override fun visit(targetLiteral: TargetLiteral?, context: Void?): Boolean {
            return false
        }

        override fun visit(setExpression: SetExpression?, context: Void?): Boolean {
            return false
        }

        override fun visit(functionExpression: FunctionExpression, context: Void?): Boolean? {
            val function = functionExpression.getFunction()
            if (functionNames.contains(function.getName())) {
                return true
            } else {
                return super.visit(functionExpression, context)
            }
        }

        override fun aggregate(resultMap: ImmutableMap<QueryExpression?, Boolean?>): Boolean {
            return Booleans.contains(Booleans.toArray(resultMap.values), true)
        }
    }

    /** Returns true when the query expression contains at least one function that requires edges.  */
    class RequiresEdgesQueryExpressionVisitor

        : AggregatingQueryExpressionVisitor<Boolean?, Void?>(), QueryExpressionVisitor<Boolean?, Void?> {
        override fun visit(targetLiteral: TargetLiteral?, context: Void?): Boolean {
            return false
        }

        override fun visit(setExpression: SetExpression?, context: Void?): Boolean {
            return false
        }

        override fun visit(functionExpression: FunctionExpression, context: Void?): Boolean? {
            if (functionExpression.getFunction().requiresEdges()) {
                return true
            } else {
                return super.visit(functionExpression, context)
            }
        }

        override fun aggregate(resultMap: ImmutableMap<QueryExpression?, Boolean?>): Boolean {
            return Booleans.contains(Booleans.toArray(resultMap.values), true)
        }
    }
}
