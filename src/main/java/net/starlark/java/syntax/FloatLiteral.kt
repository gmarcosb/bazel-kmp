// Copyright 2020 The Bazel Authors. All rights reserved.
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
 * Syntax node for a float literal. The literal's value may be negative, since the parser simplifies
 * a unary minus operation applied on a positive float literal into a negative float literal.
 */
class FloatLiteral internal constructor(locs: FileLocations?, tokenOffset: Int, endOffset: Int, value: Double) :
    Expression(locs, Kind.FLOAT_LITERAL) {
    private val tokenOffset: Int
    private val endOffset: Int

    /** Returns the value denoted by this literal.  */
    @kotlin.jvm.JvmField
    val value: Double

    init {
        this.tokenOffset = tokenOffset
        this.endOffset = endOffset
        this.value = value
    }

    override fun getStartOffset(): Int {
        return tokenOffset
    }

    override fun getEndOffset(): Int {
        return endOffset
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
