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

/** Syntax node for a function call expression.  */
class CallExpression internal constructor(
    locs: FileLocations?,
    function: Expression?,
    lparenLocation: Location?,
    arguments: ImmutableList<Argument?>,
    rparenOffset: Int
) : Expression(locs, Kind.CALL) {
    /** Returns the function that is called.  */
    @kotlin.jvm.JvmField
    val function: Expression

    // Unlike all other getXXXLocation methods, this one returns a reference to
    // a previously materialized Location. getLparenLocation is unique among
    // locations because the tree-walking evaluator needs it frequently even
    // in the absence of errors. When we switch to a compiled representation
    // we can dispense with this optimization.
    val lparenLocation: Location?

    /**
     * Returns the function call's arguments.
     * 
     * 
     * The [Resolver] verifies that the arguments are in the following order:
     * 
     * 
     *  1. [Argument.Positional] arguments (arbitrary number)
     *  1. [Argument.Keyword] arguments (arbitrary number, must have unique names)
     *  1. [Argument.Star] (at most one)
     *  1. [Argument.StarStar] (at most one)
     * 
     */
    @kotlin.jvm.JvmField
    val arguments: ImmutableList<Argument?>
    private val rparenOffset: Int

    /** Returns the number of arguments of type `Argument.Positional`.  */
    val numPositionalArguments: Int

    init {
        this.function = Preconditions.checkNotNull<Expression>(function)
        this.lparenLocation = lparenLocation
        this.arguments = arguments
        this.rparenOffset = rparenOffset

        var n = 0
        for (arg in arguments) {
            if (arg is Argument.Positional) {
                n++
            }
        }
        this.numPositionalArguments = n
    }

    override fun getStartOffset(): Int {
        return function.getStartOffset()
    }

    override fun getEndOffset(): Int {
        return rparenOffset + 1
    }

    override fun toString(): String {
        val buf = StringBuilder()
        buf.append(function)
        buf.append('(')
        ListExpression.Companion.appendNodes(buf, arguments)
        buf.append(')')
        return buf.toString()
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
