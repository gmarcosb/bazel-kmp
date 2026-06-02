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

/** Syntax node for a 'def' statement, which defines a function.  */
class DefStatement internal constructor(
    locs: FileLocations?,
    defOffset: Int,
    identifier: Identifier,
    typeParameters: ImmutableList<Identifier?>?,
    parameters: ImmutableList<Parameter?>?,
    returnType: Expression?,
    body: ImmutableList<Statement?>?
) : Statement(locs, Kind.DEF) {
    private val defOffset: Int
    @kotlin.jvm.JvmField
    val identifier: Identifier
    val typeParameters: ImmutableList<Identifier?>? // No type params => empty list
    @kotlin.jvm.JvmField
    val body: ImmutableList<Statement?> // non-empty if well formed
    @kotlin.jvm.JvmField
    val parameters: ImmutableList<Parameter?>
    val returnType: Expression? // No return type => null

    /** Returns information about the resolved function. Set by the resolver.  */
    // set by resolver
    var resolvedFunction: Resolver.Function? = null

    init {
        this.defOffset = defOffset
        this.identifier = identifier
        this.typeParameters = typeParameters
        this.parameters = Preconditions.checkNotNull<ImmutableList<Parameter?>>(parameters)
        this.returnType = returnType
        this.body = Preconditions.checkNotNull<ImmutableList<Statement?>>(body)
    }

    override fun toString(): String {
        // "def f(...): \n"
        val buf = StringBuilder()
        NodePrinter(buf).printDefSignature(this)
        buf.append(" ...\n")
        return buf.toString()
    }

    override fun getStartOffset(): Int {
        return defOffset
    }

    override fun getEndOffset(): Int {
        return if (body.isEmpty())
            identifier.getEndOffset() // wrong, but tree is ill formed
        else
            body.get(body.size - 1)!!.getEndOffset()
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
