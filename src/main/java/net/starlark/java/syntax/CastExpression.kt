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

/** Syntax node for cast() expressions.  */
class CastExpression internal constructor(
    locs: FileLocations?,
    startOffset: Int,
    type: Expression?,
    value: Expression?,
    rparenOffset: Int
) : Expression(locs, Kind.CAST) {
    private val startOffset: Int
    @kotlin.jvm.JvmField
    val type: Expression?
    @kotlin.jvm.JvmField
    val value: Expression?
    private val rparenOffset: Int
    /**
     * Returns the Starlark type extracted from the [.getType] expression. Non-null after type
     * tagging.
     */
    /** Intended for use by [TypeTagger].  */
    // Set by type tagging.
    var starlarkType: StarlarkType? = null

    init {
        this.startOffset = startOffset
        this.type = type
        this.value = value
        this.rparenOffset = rparenOffset
    }

    override fun getStartOffset(): Int {
        return startOffset
    }

    override fun getEndOffset(): Int {
        return rparenOffset + 1
    }

    override fun toString(): String {
        val buf = StringBuilder()
        buf.append("cast(")
        buf.append(type)
        buf.append(", ")
        buf.append(value)
        buf.append(')')
        return buf.toString()
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
