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

import com.google.common.collect.ImmutableList

/** Represents a type alias statement in the Starlark AST.  */
class TypeAliasStatement internal constructor(
    locs: FileLocations?,
    startOffset: Int,
    identifier: Identifier,
    parameters: ImmutableList<Identifier?>,
    definition: Expression
) : Statement(locs, Kind.TYPE_ALIAS) {
    private val startOffset: Int
    @kotlin.jvm.JvmField
    private val identifier: Identifier
    @kotlin.jvm.JvmField
    private val parameters: ImmutableList<Identifier?>
    @kotlin.jvm.JvmField
    private val definition: Expression

    init {
        this.startOffset = startOffset
        this.identifier = identifier
        this.parameters = parameters
        this.definition = definition
    }

    override fun toString(): String {
        val buf = StringBuilder()
        buf.append("type ")
        buf.append(identifier.getName())
        if (!parameters.isEmpty()) {
            buf.append('[')
            ListExpression.Companion.appendNodes(buf, parameters)
            buf.append(']')
        }
        buf.append(" = ...\n")
        return buf.toString()
    }

    fun getIdentifier(): Identifier {
        return identifier
    }

    fun getParameters(): ImmutableList<Identifier?> {
        return parameters
    }

    fun getDefinition(): Expression {
        return definition
    }

    /**
     * {@inheritDoc}
     * 
     * 
     * Note that this is the start offset of the statement's `type` keyword.
     */
    override fun getStartOffset(): Int {
        return startOffset
    }

    override fun getEndOffset(): Int {
        return definition.getEndOffset()
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
