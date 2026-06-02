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
import com.google.common.collect.ImmutableList

/** Syntax node for a for loop statement, `for vars in iterable: ...`.  */
class ForStatement internal constructor(
    locs: FileLocations?,
    forOffset: Int,
    vars: Expression?,
    iterable: Expression?,
    body: ImmutableList<Statement?>
) : Statement(locs, Kind.FOR) {
    private val forOffset: Int

    /**
     * Returns variables assigned by each iteration. May be a compound target such as `(a[b], c.d)`.
     */
    @kotlin.jvm.JvmField
    val vars: Expression

    /** Returns the iterable value.  */ // TODO(adonovan): rename to getIterable.
    val iterable: Expression
        /** Returns the iterable value.  */
        get() {
            return field
        }

    /** Returns the statements of the loop body. Non-empty if parsing succeeded.  */
    @kotlin.jvm.JvmField
    val body: ImmutableList<Statement?> // non-empty if well formed

    /** Constructs a for loop statement.  */
    init {
        this.forOffset = forOffset
        this.vars = Preconditions.checkNotNull<Expression>(vars)
        this.iterable = Preconditions.checkNotNull<Expression>(iterable)
        this.body = body
    }

    override fun getStartOffset(): Int {
        return forOffset
    }

    override fun getEndOffset(): Int {
        return if (body.isEmpty())
            iterable.getEndOffset() // wrong, but tree is ill formed
        else
            body.get(body.size - 1)!!.getEndOffset()
    }

    override fun toString(): String {
        return "for " + vars + " in " + iterable + ": ...\n"
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
