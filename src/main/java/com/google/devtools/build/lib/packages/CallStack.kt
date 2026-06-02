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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.concurrent.BlazeInterners

/**
 * Creates compact representations of Starlark call stacks for rule instantiations.
 * 
 * 
 * Implementation is optimized for minimizing memory overhead by sharing [Node] instances
 * when two call stacks have a common tail. For example, two different BUILD files that call into
 * the same macro can share [Node] instances.
 * 
 * 
 * The sharing rate of interior nodes is expected to be high, so nodes are implemented as a
 * linked list to eliminate array cost.
 */
internal object CallStack {
    /**
     * Returns the *full* call stack of the given rule, including both [Rule.getLocation]
     * and [Rule.getInteriorCallStack].
     */
    fun getFullCallStack(rule: com.google.devtools.build.lib.packages.Rule): Node {
        return CallStack.Node(
            net.starlark.java.eval.StarlarkThread.TOP_LEVEL,
            rule.getLocation(),
            rule.getInteriorCallStack()
        )
    }

    private val nodeInterner: com.google.common.collect.Interner<Node?> = BlazeInterners.newWeakInterner()

    /**
     * Returns a compact representation of the given call stack, optionally ignoring the outermost
     * frame.
     * 
     * @param start index of frame at which to start; in other words, skip this many outermost frames.
     * This is useful for skipping the outermost frame in BUILD file thread stacks, since the
     * BUILD file location is already stored in [Rule.getLocation] and [     ][MacroInstance.getBuildFileLocation].
     * @return `null` for call stacks with fewer than two frames.
     */
    fun compact(stack: MutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry>, start: Int): Node? {
        var node: Node? = null
        for (i in stack.size() - 1 downTo start) {
            val entry: net.starlark.java.eval.StarlarkThread.CallStackEntry = stack.get(i)
            node = nodeInterner.intern(CallStack.Node(entry.name, entry.location, node))
        }
        return node
    }

    /**
     * Returns a concatenation of two compact call stacks.
     * 
     * 
     * The result will contain `inner` stack appended unmodified to a new copy of the `outer` stack.
     * 
     * @return `null` if both of the inputs are `null` - in other words, if both of the
     * inputs are empty stacks.
     */
    @kotlin.jvm.JvmStatic
    fun concatenate(outer: Node?, inner: Node?): Node? {
        var outer = outer
        val outerReversed: Deque<Node> = ArrayDeque<Node>()
        while (outer != null) {
            outerReversed.addFirst(outer)
            outer = outer.next()
        }
        var node = inner
        for (origOuterNode in outerReversed) {
            node =
                nodeInterner.intern(
                    CallStack.Node(
                        origOuterNode.name,
                        origOuterNode.file,
                        origOuterNode.line,
                        origOuterNode.col,
                        node
                    )
                )
        }
        return node
    }

    /** Compact representation of a call stack entry.  */
    @AutoCodec
    internal class Node private constructor(name: String, file: String, line: Int, col: Int, next: Node?) {
        /** Function name.  */
        private val name: String

        /** File name.  */
        private val file: String

        private val line: Int
        private val col: Int
        private val next: Node?

        private constructor(name: String, location: net.starlark.java.syntax.Location, next: Node?) : this(
            name,
            location.file(),
            location.line(),
            location.column(),
            next
        )

        init {
            this.name = name
            this.file = file
            this.line = line
            this.col = col
            this.next = next
        }

        fun toLocation(): net.starlark.java.syntax.Location {
            return net.starlark.java.syntax.Location.fromFileLineColumn(file, line, col)
        }

        fun toCallStackEntry(): net.starlark.java.eval.StarlarkThread.CallStackEntry {
            return net.starlark.java.eval.StarlarkThread.callStackEntry(name, toLocation())
        }

        fun functionName(): String {
            return name
        }

        fun next(): Node? {
            return next
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is Node) {
                return false
            }
            val node = o
            return line == node.line && col == node.col && name == node.name
                    && file == node.file
                    && next == node.next
        }

        override fun hashCode(): Int {
            var result: Int = HashCodes.hashObjects(name, file, next)
            result = result * 31 + java.lang.Integer.hashCode(line)
            result = result * 31 + java.lang.Integer.hashCode(col)
            return result
        }

        companion object {
            @AutoCodec.Instantiator
            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            fun createForDeserialization(
                name: String, file: String, line: Int, col: Int, next: Node?
            ): Node {
                // Use common canonicalizer based on assertion that most strings (function names, locations)
                // were already shared across packages to some degree.
                return Node(name.intern(), file.intern(), line, col, next)
            }
        }
    }
}
