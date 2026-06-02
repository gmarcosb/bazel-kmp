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
// All Rights Reserved.
package com.google.devtools.build.lib.graph

import java.io.PrintWriter

/**
 * 
 *  An implementation of GraphVisitor for displaying graphs in dot
 * format. 
 */
open class DotOutputVisitor<T>
/**
 * Constructs a dot output visitor.
 * 
 * 
 * The visitor writes to writer 'out', and rendering node labels as strings using the specified
 * displayer, 'disp'.
 */(@kotlin.jvm.JvmField protected val out: PrintWriter, @kotlin.jvm.JvmField protected val lineTerminator: String?, private val disp: LabelSerializer<T?>) :
    GraphVisitor<T?> {
    private val closeAtEnd = false

    override fun beginVisit() {
        out.printf("digraph mygraph {%s", lineTerminator)
    }

    override fun endVisit() {
        out.printf("}%s", lineTerminator)
        out.flush()
        if (closeAtEnd) {
            out.close()
        }
    }

    override fun visitEdge(lhs: Node<T?>?, rhs: Node<T?>?) {
        out.printf("  \"%s\" -> \"%s\"%s", disp.serialize(lhs), disp.serialize(rhs), lineTerminator)
    }

    override fun visitNode(node: Node<T?>?) {
        out.printf("  \"%s\"%s", disp.serialize(node), lineTerminator)
    }
}
