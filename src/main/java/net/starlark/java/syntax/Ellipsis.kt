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

/** Syntax node for the singleton ellipsis expression.  */
class Ellipsis internal constructor(locs: FileLocations?, startOffset: Int) : Expression(locs, Kind.ELLIPSIS) {
    private val startOffset: Int

    init {
        this.startOffset = startOffset
    }

    override fun getStartOffset(): Int {
        return startOffset
    }

    override fun getEndOffset(): Int {
        return startOffset + 3
    }

    override fun toString(): String {
        return "..."
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
