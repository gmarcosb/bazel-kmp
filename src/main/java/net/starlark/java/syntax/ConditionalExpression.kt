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

/** Syntax node for an expression of the form `t if cond else f`.  */
class ConditionalExpression internal constructor(
    locs: FileLocations?,
    t: Expression,
    cond: Expression?,
    f: Expression
) : Expression(locs, Kind.CONDITIONAL) {
    val thenCase: Expression
    val condition: Expression?
    val elseCase: Expression

    /** Constructor for a conditional expression  */
    init {
        this.thenCase = t
        this.condition = cond
        this.elseCase = f
    }

    override fun getStartOffset(): Int {
        return thenCase.getStartOffset()
    }

    override fun getEndOffset(): Int {
        return elseCase.getEndOffset()
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
