// Copyright 2024 The Bazel Authors. All rights reserved.
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

/**
 * A [QueryExpressionVisitor] that cheaply estimates the size of a [QueryExpression]
 * without stringify-ing it.
 */
class TotalWeightQueryExpressionVisitor : QueryExpressionVisitor<Long?, Void?> {
    override fun visit(targetLiteral: TargetLiteral, context: Void?): Long {
        return targetLiteral.getPattern().length.toLong()
    }

    override fun visit(binaryOperatorExpression: BinaryOperatorExpression, context: Void?): Long {
        var totalWeight = 0L
        for (operand in binaryOperatorExpression.getOperands()) {
            totalWeight += operand.accept<Long?>(this)
        }
        return totalWeight
    }

    override fun visit(functionExpression: FunctionExpression, context: Void?): Long {
        var totalWeight = 0L
        for (arg in functionExpression.getArgs()) {
            totalWeight +=
                when (arg.getType()) {
                    QueryEnvironment.ArgumentType.WORD -> arg.getWord().length
                    QueryEnvironment.ArgumentType.INTEGER -> 1L
                    QueryEnvironment.ArgumentType.EXPRESSION -> arg.getExpression().accept<Long?>(this)
                }
        }
        return totalWeight
    }

    override fun visit(letExpression: LetExpression, context: Void?): Long {
        return (letExpression.getVarName().length
                + letExpression.getVarExpr().accept<Long?>(this)
                + letExpression.getBodyExpr().accept<Long?>(this))
    }

    override fun visit(setExpression: SetExpression, context: Void?): Long {
        var totalWeight = 0L
        for (word in setExpression.getWords()) {
            totalWeight += word.getPattern().length.toLong()
        }
        return totalWeight
    }
}
