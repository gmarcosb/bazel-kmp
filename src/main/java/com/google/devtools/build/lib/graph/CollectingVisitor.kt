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
package com.google.devtools.build.lib.graph

import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/**
 * A graph visitor that collects the visited nodes in the order in which
 * they were visited, and allows them to be accessed as a list.
 */
class CollectingVisitor<T> : AbstractGraphVisitor<T?>() {
    /**
     * Returns a reference to (not a copy of) the list of visited nodes in the
     * order they were visited.
     */
    val visitedNodes: MutableList<Node<T?>?> = ArrayList<Node<T?>?>()

    override fun visitNode(node: Node<T?>?) {
        visitedNodes.add(node)
    }
}
