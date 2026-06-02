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

import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList

/** A block of Sphinx autodoc-style doc comments.  */
class DocComments(lines: MutableList<Comment?>) {
    val lines: ImmutableList<Comment?>

    init {
        Preconditions.checkArgument(!lines.isEmpty())
        Preconditions.checkArgument(lines.stream().allMatch { obj: Comment? -> obj!!.hasDocCommentPrefix() })
        this.lines = ImmutableList.copyOf<Comment?>(lines)
    }

    val startLocation: Location
        get() = lines.getFirst().getStartLocation()

    val endLocation: Location
        get() = lines.getLast().getEndLocation()

    val text: String
        /**
         * Returns the text content (trimmed of the leading `#: ` or `#:` prefixes, and joined
         * with newlines) of the doc comment block.
         */
        get() = Joiner.on("\n").join(
            lines.stream().map<String?> { obj: Comment? -> obj!!.getDocCommentText() }
                .iterator())

    override fun toString(): String {
        return Joiner.on("\n").join(lines.stream().map<String?> { obj: Comment? -> obj.toString() }.iterator())
    }
}
