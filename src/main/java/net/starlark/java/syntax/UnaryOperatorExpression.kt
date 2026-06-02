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
package net.starlark.java.syntax

/** A UnaryOperatorExpression represents a unary operator expression, 'op x'.  */
class UnaryOperatorExpression internal constructor(locs: FileLocations?, op: TokenKind, opOffset: Int, x: Expression) :
    Expression(locs, Kind.UNARY_OPERATOR) {
    @kotlin.jvm.JvmField
    private val op: TokenKind // NOT, TILDE, MINUS or PLUS
    private val opOffset: Int
    @kotlin.jvm.JvmField
    private val x: Expression

    init {
        this.op = op
        this.opOffset = opOffset
        this.x = x
    }

    /** Returns the operator.  */
    fun getOperator(): TokenKind {
        return op
    }

    override fun getStartOffset(): Int {
        return opOffset
    }

    override fun getEndOffset(): Int {
        return x.getEndOffset()
    }

    /** Returns the operand.  */
    fun getX(): Expression {
        return x
    }

    override fun toString(): String {
        // Note that this omits the parentheses for brevity, but is not correct in general due to
        // operator precedence rules. For example, "(not False) in mylist" prints as
        // "not False in mylist", which evaluates to opposite results in the case that mylist is empty.
        // TODO(adonovan): record parentheses explicitly in syntax tree.
        return (if (op == TokenKind.NOT) "not " else op.toString()) + x
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
