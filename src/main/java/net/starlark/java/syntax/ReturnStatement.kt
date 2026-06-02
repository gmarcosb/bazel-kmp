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

/** A syntax node for return statements.  */
class ReturnStatement internal constructor(locs: FileLocations?, returnOffset: Int, result: Expression?) :
    Statement(locs, Kind.RETURN) {
    private val returnOffset: Int
    @kotlin.jvm.JvmField
    private val result: Expression?

    init {
        this.returnOffset = returnOffset
        this.result = result
    }

    fun getResult(): Expression? {
        return result
    }

    override fun getStartOffset(): Int {
        return returnOffset
    }

    override fun getEndOffset(): Int {
        return if (result != null) result.getEndOffset() else returnOffset + "return".length()
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }

    companion object {
        /**
         * Returns a new return statement that returns expr. It is provided only for use by the evaluator,
         * and will be removed when it switches to a compiled representation.
         */
        fun make(expr: Expression): ReturnStatement {
            return ReturnStatement(expr.locs, expr.getStartOffset(), expr)
        }
    }
}
