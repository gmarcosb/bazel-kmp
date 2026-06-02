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
package net.starlark.java.syntax

import java.util.*

/** A BinaryExpression represents a binary operator expression 'x op y'.  */
class BinaryOperatorExpression internal constructor(
    locs: FileLocations?,
    x: Expression,
    op: TokenKind?,
    opOffset: Int,
    y: Expression
) : Expression(locs, Kind.BINARY_OPERATOR) {
    /** Returns the left operand.  */
    @kotlin.jvm.JvmField
    val x: Expression

    /** Returns the operator.  */
    val operator: TokenKind? // one of 'operators'
    private val opOffset: Int

    /** Returns the right operand.  */
    @kotlin.jvm.JvmField
    val y: Expression

    init {
        this.x = x
        this.operator = op
        this.opOffset = opOffset
        this.y = y
    }

    val operatorLocation: Location
        get() = locs.getLocation(opOffset)

    override fun getStartOffset(): Int {
        return x.getStartOffset()
    }

    override fun getEndOffset(): Int {
        return y.getEndOffset()
    }

    override fun toString(): String {
        // This omits the parentheses for brevity, but is not correct in general due to operator
        // precedence rules.
        return x.toString() + " " + this.operator + " " + y
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }

    companion object {
        /** operators is the set of valid binary operators.  */
        val operators: EnumSet<TokenKind?> = EnumSet.of<TokenKind?>(
            TokenKind.AND,
            TokenKind.EQUALS_EQUALS,
            TokenKind.GREATER,
            TokenKind.GREATER_EQUALS,
            TokenKind.IN,
            TokenKind.LESS,
            TokenKind.LESS_EQUALS,
            TokenKind.MINUS,
            TokenKind.NOT_EQUALS,
            TokenKind.NOT_IN,
            TokenKind.OR,
            TokenKind.PERCENT,
            TokenKind.SLASH,
            TokenKind.SLASH_SLASH,
            TokenKind.PLUS,
            TokenKind.PIPE,
            TokenKind.STAR
        )
    }
}
