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

import com.google.common.base.Preconditions

/**
 * Syntax node for an assignment statement (`lhs = rhs`) or augmented assignment statement
 * (`lhs op= rhs`).
 */
class AssignmentStatement internal constructor(
    locs: FileLocations?,
    /** Returns the LHS of the assignment.  */
    val lHS: Expression, // = IDENTIFIER | DOT | INDEX | LIST_EXPR
    /** Returns the type expression (if present) of the variable on the LHS.  */
    // non-null only when lhs is an identifier and we're not augmented
    @kotlin.jvm.JvmField val type: Expression?,
    op: TokenKind?,
    opOffset: Int,
    rhs: Expression,
    docComments: DocComments?
) : Statement(locs, Kind.ASSIGNMENT) {
    /** Returns the operator of an augmented assignment, or null for an ordinary assignment.  */
    val operator: TokenKind? // TODO(adonovan): make this mandatory even when '='.
    private val opOffset: Int

    /** Returns the RHS of the assignment.  */
    val rHS: Expression

    /** Returns the Sphinx autodoc-style doc comments attached to this statement, if any.  */
    @kotlin.jvm.JvmField
    val docComments: DocComments?

    /**
     * Constructs an assignment statement. For an ordinary assignment (`op == null`), the LHS
     * expression must be of the form `id`, `x.y`, `x[i]`, `[e, ...]`, or
     * `(e, ...)`, where x, i, and e are arbitrary expressions. For an augmented assignment, the
     * list and tuple forms are disallowed.
     * 
     * 
     * If a type annotation is present (`x : T = ...`), the LHS expression must be an
     * identifier, and the assignment must not be augmented.
     */
    init {
        this.type = type
        this.operator = op
        this.opOffset = opOffset
        this.rHS = rhs
        this.docComments = docComments
        if (type != null) {
            Preconditions.checkState(
                lHS.kind() == Expression.Kind.IDENTIFIER, "Can't have type annotation on complex LHS"
            )
            Preconditions.checkState(op == null, "Can't have augmented assignment with type annotation")
        }
    }

    val operatorLocation: Location
        /** Returns the location of the assignment operator.  */
        get() = locs.getLocation(opOffset)

    override fun getStartOffset(): Int {
        return lHS.getStartOffset()
    }

    override fun getEndOffset(): Int {
        return rHS.getEndOffset()
    }

    val isAugmented: Boolean
        /** Reports whether this is an augmented assignment (`getOperator() != null`).  */
        get() = this.operator != null

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
