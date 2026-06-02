// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package net.starlark.java.syntax

/** A class for flow statements (break, continue, and pass)  */
class FlowStatement internal constructor(locs: FileLocations?, flowKind: TokenKind, offset: Int) :
    Statement(locs, Kind.FLOW) {
    @kotlin.jvm.JvmField
    val flowKind: TokenKind // BREAK | CONTINUE | PASS
    private val offset: Int

    /**
     * Constructs a new flow control statement.
     * 
     * @param flowKind The specific kind of flow control statement (break, continue, or pass)
     */
    init {
        this.flowKind = flowKind
        this.offset = offset
    }

    override fun toString(): String {
        return flowKind.toString() + "\n"
    }

    override fun getStartOffset(): Int {
        return offset
    }

    override fun getEndOffset(): Int {
        return offset + flowKind.toString().length
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
