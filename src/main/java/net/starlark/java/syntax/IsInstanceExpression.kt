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

/** Syntax node for isinstance() expressions.  */
class IsInstanceExpression internal constructor(
    locs: FileLocations?,
    startOffset: Int,
    value: Expression?,
    type: Expression?,
    rparenOffset: Int
) : Expression(locs, Kind.ISINSTANCE) {
    private val startOffset: Int
    @kotlin.jvm.JvmField
    private val value: Expression?
    @kotlin.jvm.JvmField
    private val type: Expression?
    private val rparenOffset: Int

    init {
        this.startOffset = startOffset
        this.value = value
        this.type = type
        this.rparenOffset = rparenOffset
    }

    override fun getStartOffset(): Int {
        return startOffset
    }

    override fun getEndOffset(): Int {
        return rparenOffset + 1
    }

    fun getValue(): Expression? {
        return value
    }

    fun getType(): Expression? {
        return type
    }

    override fun toString(): String {
        val buf = StringBuilder()
        buf.append("isinstance(")
        buf.append(value)
        buf.append(", ")
        buf.append(type)
        buf.append(')')
        return buf.toString()
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
