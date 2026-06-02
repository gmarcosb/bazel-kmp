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

import com.google.common.collect.ImmutableList

/** Syntax node for dict expressions.  */
class DictExpression internal constructor(
    locs: FileLocations?,
    lbraceOffset: Int,
    entries: MutableList<Entry?>,
    rbraceOffset: Int
) : Expression(locs, Kind.DICT_EXPR) {
    /** A key/value pair in a dict expression or comprehension.  */
    class Entry internal constructor(locs: FileLocations?, key: Expression, colonOffset: Int, value: Expression) :
        Node(locs) {
        @kotlin.jvm.JvmField
        val key: Expression
        private val colonOffset: Int
        @kotlin.jvm.JvmField
        val value: Expression

        init {
            this.key = key
            this.colonOffset = colonOffset
            this.value = value
        }

        override fun getStartOffset(): Int {
            return key.getStartOffset()
        }

        override fun getEndOffset(): Int {
            return value.getEndOffset()
        }

        val colonLocation: Location
            get() = locs.getLocation(colonOffset)

        override fun accept(visitor: NodeVisitor) {
            visitor.visit(this)
        }
    }

    private val lbraceOffset: Int
    @kotlin.jvm.JvmField
    val entries: ImmutableList<Entry?>
    private val rbraceOffset: Int

    init {
        this.lbraceOffset = lbraceOffset
        this.entries = ImmutableList.copyOf<Entry?>(entries)
        this.rbraceOffset = rbraceOffset
    }

    override fun getStartOffset(): Int {
        return lbraceOffset
    }

    override fun getEndOffset(): Int {
        return rbraceOffset + 1
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
