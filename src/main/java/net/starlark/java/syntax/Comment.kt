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

/** Syntax node for comments.  */
class Comment internal constructor(locs: FileLocations?, offset: Int, text: String) : Node(locs) {
    private val offset: Int

    /** Returns the text of the comment, including the leading '#' but not the trailing newline.  */
    @kotlin.jvm.JvmField
    val text: String

    init {
        this.offset = offset
        this.text = text
    }

    /**
     * Returns true if the comment starts with `#:`, like a Sphinx autodoc-style doc comment.
     */
    fun hasDocCommentPrefix(): Boolean {
        return text.startsWith("#:")
    }

    val docCommentText: String?
        /**
         * If the comment starts with a `#: ` or `#:` prefix, returns the text following it;
         * otherwise, returns null.
         */
        get() {
            if (hasDocCommentPrefix()) {
                return if (text.startsWith("#: ")) text.substring(3) else text.substring(2)
            }
            return null
        }

    override fun getStartOffset(): Int {
        return offset
    }

    override fun getEndOffset(): Int {
        return offset + text.length
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }

    override fun toString(): String {
        return text
    }
}
