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
 * Syntax node for an argument to a function.
 * 
 * 
 * Arguments may be of four forms, as in `f(expr, id=expr, *expr, **expr)`. These are
 * represented by the subclasses Positional, Keyword, Star, and StarStar.
 */
abstract class Argument internal constructor(locs: FileLocations?, value: Expression?) : Node(locs) {
    @kotlin.jvm.JvmField
    val value: Expression

    init {
        this.value = Preconditions.checkNotNull<Expression>(value)
    }

    override fun getEndOffset(): Int {
        return value.getEndOffset()
    }

    open val name: String?
        /** Return the name of this argument's parameter, or null if it is not a Keyword argument.  */
        get() = null

    /** Syntax node for a positional argument, `f(expr)`.  */
    class Positional internal constructor(locs: FileLocations?, value: Expression?) : Argument(locs, value) {
        override fun getStartOffset(): Int {
            return value.getStartOffset()
        }
    }

    /** Syntax node for a keyword argument, `f(id=expr)`.  */
    class Keyword internal constructor(
        locs: FileLocations?, // Unlike in Python, keyword arguments in Bazel BUILD files
        // are about 10x more numerous than positional arguments.
        val identifier: Identifier, value: Expression?
    ) : Argument(locs, value) {
        override fun getName(): String? {
            return identifier.getName()
        }

        override fun getStartOffset(): Int {
            return identifier.getStartOffset()
        }
    }

    /** Syntax node for an argument of the form `f(*expr)`.  */
    class Star internal constructor(locs: FileLocations?, private val starOffset: Int, value: Expression?) :
        Argument(locs, value) {
        override fun getStartOffset(): Int {
            return starOffset
        }
    }

    /** Syntax node for an argument of the form `f(**expr)`.  */
    class StarStar internal constructor(locs: FileLocations?, private val starStarOffset: Int, value: Expression?) :
        Argument(locs, value) {
        override fun getStartOffset(): Int {
            return starStarOffset
        }
    }

    override fun accept(visitor: NodeVisitor) {
        // All Argument subclasses dispatch to NodeVisitor#visit(Argument).
        visitor.visit(this)
    }
}
