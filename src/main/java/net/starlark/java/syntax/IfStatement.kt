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

import com.google.common.collect.ImmutableList

/** Syntax node for an if or elif statement.  */
class IfStatement internal constructor(
    locs: FileLocations?,
    token: TokenKind?,
    ifOffset: Int,
    condition: Expression,
    thenBlock: MutableList<Statement?>
) : Statement(locs, Kind.IF) {
    private val token: TokenKind? // IF or ELIF
    private val ifOffset: Int
    private val condition: Expression

    // These blocks may be non-null but empty after a misparse:
    private val thenBlock: ImmutableList<Statement?> // non-empty
    @kotlin.jvm.JvmField
    var elseBlock: ImmutableList<Statement?>? = null // non-empty if non-null; set after construction

    init {
        this.token = token
        this.ifOffset = ifOffset
        this.condition = condition
        this.thenBlock = ImmutableList.copyOf<Statement?>(thenBlock)
    }

    /**
     * Reports whether this is an 'elif' statement.
     * 
     * 
     * An elif statement may appear only as the sole statement in the "else" block of another
     * IfStatement.
     */
    fun isElif(): Boolean {
        return token == TokenKind.ELIF
    }

    fun getCondition(): Expression {
        return condition
    }

    fun getThenBlock(): ImmutableList<Statement?> {
        return thenBlock
    }

    fun getElseBlock(): ImmutableList<Statement?>? {
        return elseBlock
    }

    fun setElseBlock(elseBlock: ImmutableList<Statement?>?) {
        this.elseBlock = elseBlock
    }

    override fun getStartOffset(): Int {
        return ifOffset
    }

    override fun getEndOffset(): Int {
        val body: MutableList<Statement?> = (if (elseBlock != null) elseBlock else thenBlock)!!
        return if (body.isEmpty())
            condition.getEndOffset() // wrong, but tree is ill formed
        else
            body.get(body.size - 1)!!.getEndOffset()
    }

    override fun toString(): String {
        return String.format("if %s: ...\n", condition)
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
