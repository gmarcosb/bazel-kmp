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
 * Syntax node for a parameter in a function definition.
 * 
 * 
 * Parameters may be of four forms, as in `def f(a, b=c, *args, **kwargs)`. They are
 * represented by the subclasses Mandatory, Optional, Star, and StarStar.
 * 
 * 
 * Each parameter may have a type annotation. Star parameter without id/name, `(..., *, ...)`,
 * cannot be annotated.
 */
abstract class Parameter private constructor(locs: FileLocations?, id: Identifier?, type: Expression?) : Node(locs) {
    // Null in the case of a bare * parameter, non-null for any other case.
    @kotlin.jvm.JvmField
    private val id: Identifier?

    private val type: Expression?

    init {
        this.id = id
        this.type = type
    }

    fun getName(): String? {
        return if (id != null) id.getName() else null
    }

    fun getIdentifier(): Identifier? {
        return id
    }

    open fun getDefaultValue(): Expression? {
        return null
    }

    fun getType(): Expression? {
        return type
    }

    /**
     * Syntax node for a mandatory parameter, `f(id)`. It may be positional or keyword-only
     * depending on its position.
     */
    class Mandatory internal constructor(locs: FileLocations?, id: Identifier?, type: Expression?) :
        Parameter(locs, id, type) {
        override fun getStartOffset(): Int {
            return getIdentifier()!!.getStartOffset()
        }

        override fun getEndOffset(): Int {
            return if (getType() != null) getType()!!.getEndOffset() else getIdentifier()!!.getEndOffset()
        }
    }

    /**
     * Syntax node for an optional parameter, `f(id=expr).`. It may be positional or
     * keyword-only depending on its position.
     */
    class Optional internal constructor(
        locs: FileLocations?,
        id: Identifier?,
        type: Expression?,
        defaultValue: Expression?
    ) : Parameter(locs, id, type) {
        val defaultValue: Expression?

        init {
            this.defaultValue = defaultValue
        }

        override fun getDefaultValue(): Expression? {
            return defaultValue
        }

        override fun getStartOffset(): Int {
            return getIdentifier()!!.getStartOffset()
        }

        override fun getEndOffset(): Int {
            return getDefaultValue()!!.getEndOffset()
        }

        override fun toString(): String {
            return getName() + "=" + defaultValue
        }
    }

    /** Syntax node for a star parameter, `f(*id)` or `f(..., *, ...)`.  */
    class Star internal constructor(locs: FileLocations?, starOffset: Int, id: Identifier?, type: Expression?) :
        Parameter(locs, id, type) {
        private val starOffset: Int

        init {
            Preconditions.checkArgument(
                id != null || type == null, "Star parameter without id cannot have a type"
            )
            this.starOffset = starOffset
        }

        override fun getStartOffset(): Int {
            return starOffset
        }

        override fun getEndOffset(): Int {
            return if (getType() != null) getType()!!.getEndOffset() else getIdentifier()!!.getEndOffset()
        }
    }

    /** Syntax node for a parameter of the form `f(**id)`.  */
    class StarStar internal constructor(locs: FileLocations?, starStarOffset: Int, id: Identifier?, type: Expression?) :
        Parameter(locs, id, type) {
        private val starStarOffset: Int

        init {
            this.starStarOffset = starStarOffset
        }

        override fun getStartOffset(): Int {
            return starStarOffset
        }

        override fun getEndOffset(): Int {
            return if (getType() != null) getType()!!.getEndOffset() else getIdentifier()!!.getEndOffset()
        }
    }

    override fun accept(visitor: NodeVisitor) {
        // All Parameter subclasses dispatch to NodeVisitor#visit(Parameter).
        visitor.visit(this)
    }
}
