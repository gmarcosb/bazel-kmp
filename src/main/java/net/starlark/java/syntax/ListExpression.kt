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

/** Syntax node for list and tuple expressions.  */
class ListExpression internal constructor(
    locs: FileLocations?,
    isTuple: Boolean,
    lbracketOffset: Int,
    elements: ImmutableList<Expression?>,
    rbracketOffset: Int
) : Expression(locs, Kind.LIST_EXPR) {
    // TODO(adonovan): split class into {List,Tuple}Expression, as a tuple may have no parens.
    // Materialize all source-level expressions as a separate ParenExpression so that we can roundtrip
    // faithfully.
    @kotlin.jvm.JvmField
    private val isTuple: Boolean
    private val lbracketOffset: Int // -1 => unparenthesized non-empty tuple
    private val elements: ImmutableList<Expression?>
    private val rbracketOffset: Int // -1 => unparenthesized non-empty tuple

    init {
        // An unparenthesized tuple must be non-empty.
        Preconditions.checkArgument(
            !elements.isEmpty() || (lbracketOffset >= 0 && rbracketOffset >= 0)
        )
        this.lbracketOffset = lbracketOffset
        this.isTuple = isTuple
        this.elements = elements
        this.rbracketOffset = rbracketOffset
    }

    fun getElements(): MutableList<Expression?> {
        return elements
    }

    /** Reports whether this is a tuple expression.  */
    fun isTuple(): Boolean {
        return isTuple
    }

    override fun getStartOffset(): Int {
        return if (lbracketOffset < 0) elements.get(0)!!.getStartOffset() else lbracketOffset
    }

    override fun getEndOffset(): Int {
        // Unlike Python, trailing commas are not allowed in unparenthesized tuples.
        return if (rbracketOffset < 0)
            elements.get(elements.size - 1)!!.getEndOffset()
        else
            rbracketOffset + 1
    }

    override fun toString(): String {
        // Print [a, b, c, ...] up to a maximum of 4 elements or 32 chars.
        val buf = StringBuilder()
        buf.append(if (isTuple()) '(' else '[')
        appendNodes(buf, elements)
        if (isTuple() && elements.size == 1) {
            buf.append(',')
        }
        buf.append(if (isTuple()) ')' else ']')
        return buf.toString()
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }

    companion object {
        // Appends elements to buf, comma-separated, abbreviating if they are numerous or long.
        // (Also used by CallExpression.)
        fun appendNodes(buf: StringBuilder, elements: MutableList<out Node?>) {
            val n = elements.size
            for (i in 0..<n) {
                if (i > 0) {
                    buf.append(", ")
                }
                val mark = buf.length
                buf.append(elements.get(i))
                // Abbreviate, dropping this element, if we exceed 32 chars,
                // or 4 elements (with more elements following).
                if (buf.length >= 32 || (i == 4 && i + 1 < n)) {
                    buf.setLength(mark)
                    buf.append(String.format("+%d more", n - i))
                    break
                }
            }
        }
    }
}
