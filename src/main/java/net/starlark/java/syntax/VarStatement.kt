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

/**
 * Syntax node for a variable type annotation appearing as its own statement (`foo : int`), as
 * opposed to in an assignment statement where there's an initializer on the right-hand side.
 * 
 * 
 * (The name of this class is meant to be reminiscent of the `var` keyword that some languages
 * use, although Python and Starlark have no special keyword for variable declarations.)
 */
class VarStatement internal constructor(
    locs: FileLocations?,
    identifier: Identifier,
    type: Expression,
    docComments: DocComments?
) : Statement(locs, Kind.VAR) {
    @kotlin.jvm.JvmField
    private val identifier: Identifier

    @kotlin.jvm.JvmField
    private val type: Expression

    private val docComments: DocComments?

    /** Constructs a `VarStatement`.  */
    init {
        this.identifier = identifier
        this.type = type
        this.docComments = docComments
    }

    override fun getStartOffset(): Int {
        return identifier.getStartOffset()
    }

    override fun getEndOffset(): Int {
        return type.getEndOffset()
    }

    /** Returns the variable being declared and annotated.  */
    fun getIdentifier(): Identifier {
        return identifier
    }

    /** Returns the type expression associated with the variable.  */
    fun getType(): Expression {
        return type
    }

    /** Returns the Sphinx autodoc-style doc comments attached to this statement, if any.  */
    fun getDocComments(): DocComments? {
        return docComments
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
