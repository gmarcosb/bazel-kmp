// Copyright 2025 The Bazel Authors. All rights reserved.
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

/** Syntax node for a type application expression.  */
class TypeApplication internal constructor(
    locs: FileLocations?,
    constructor: Identifier?,
    arguments: ImmutableList<Expression?>,
    rbracketOffset: Int
) : Expression(locs, Kind.TYPE_APPLICATION) {
    private val constructor: Identifier
    private val arguments: ImmutableList<Expression?>
    private val rbracketOffset: Int

    init {
        this.constructor = Preconditions.checkNotNull<Identifier>(constructor)
        this.arguments = arguments
        this.rbracketOffset = rbracketOffset
    }

    /** Returns the type constructor.  */
    fun getConstructor(): Identifier {
        return this.constructor
    }

    /** Returns the type arguments.  */
    fun getArguments(): ImmutableList<Expression?> {
        return arguments
    }

    override fun getStartOffset(): Int {
        return constructor.getStartOffset()
    }

    override fun getEndOffset(): Int {
        return rbracketOffset + 1
    }

    override fun toString(): String {
        val buf = StringBuilder()
        buf.append(constructor)
        buf.append('[')
        ListExpression.Companion.appendNodes(buf, arguments)
        buf.append(']')
        return buf.toString()
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
