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

/** Syntax node for a slice expression, `object[start:stop:step]`.  */
class SliceExpression internal constructor(
    locs: FileLocations?,
    `object`: Expression,
    lbracketOffset: Int,
    start: Expression?,
    stop: Expression?,
    step: Expression?,
    rbracketOffset: Int
) : Expression(locs, Kind.SLICE) {
    private val `object`: Expression
    private val lbracketOffset: Int
    @kotlin.jvm.JvmField
    private val start: Expression?
    @kotlin.jvm.JvmField
    private val stop: Expression?
    @kotlin.jvm.JvmField
    private val step: Expression?
    private val rbracketOffset: Int

    init {
        this.`object` = `object`
        this.lbracketOffset = lbracketOffset
        this.start = start
        this.stop = stop
        this.step = step
        this.rbracketOffset = rbracketOffset
    }

    fun getObject(): Expression {
        return `object`
    }

    fun getStart(): Expression? {
        return start
    }

    fun getStop(): Expression? {
        return stop
    }

    fun getStep(): Expression? {
        return step
    }

    override fun getStartOffset(): Int {
        return `object`.getStartOffset()
    }

    override fun getEndOffset(): Int {
        return rbracketOffset + 1
    }

    fun getLbracketLocation(): Location {
        return locs.getLocation(lbracketOffset)
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
