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
package com.google.devtools.build.lib.actions

import java.util.HashMap

/**
 * A visitor helper class for bipartite graphs. The alternate kinds of nodes are arbitrarily
 * designated "black" or "white".
 * 
 * 
 * Subclasses implement the black() and white() hook functions which are called as nodes are
 * visited. The class holds a mapping from each node to a small integer; this is available to
 * subclasses if they wish.
 */
internal abstract class BipartiteVisitor<BLACK, WHITE> protected constructor() {
    private var nextNodeId = 0

    // Maps each visited black node to a small integer.
    protected val visitedBlackNodes: MutableMap<BLACK?, Int?> = HashMap<BLACK?, Int?>()

    // Maps each visited white node to a small integer.
    protected val visitedWhiteNodes: MutableMap<WHITE?, Int?> = HashMap<WHITE?, Int?>()

    /**
     * Visit the specified black node. If this node has not already been visited, the black() hook is
     * called and true is returned; otherwise, false is returned.
     */
    @Throws(java.lang.InterruptedException::class)
    protected fun visitBlackNode(blackNode: BLACK?): Boolean {
        if (blackNode == null) {
            throw java.lang.NullPointerException()
        }
        if (!visitedBlackNodes.containsKey(blackNode)) {
            visitedBlackNodes.put(blackNode, nextNodeId++)
            black(blackNode)
            return true
        }
        return false
    }

    /** Visit all specified black nodes.  */
    @Throws(java.lang.InterruptedException::class)
    protected fun visitBlackNodes(blackNodes: Iterable<BLACK?>) {
        for (blackNode in blackNodes) {
            visitBlackNode(blackNode)
        }
    }

    /**
     * Visit the specified white node. If this node has not already been visited, the white() hook is
     * called and true is returned; otherwise, false is returned.
     */
    @Throws(java.lang.InterruptedException::class)
    protected fun visitWhiteNode(whiteNode: WHITE?): Boolean {
        if (whiteNode == null) {
            throw java.lang.NullPointerException()
        }
        if (!visitedWhiteNodes.containsKey(whiteNode)) {
            visitedWhiteNodes.put(whiteNode, nextNodeId++)
            white(whiteNode)
            return true
        }
        return false
    }

    /** Visit all specified white nodes.  */
    @Throws(java.lang.InterruptedException::class)
    fun visitWhiteNodes(whiteNodes: Iterable<WHITE?>) {
        for (whiteNode in whiteNodes) {
            visitWhiteNode(whiteNode)
        }
    }

    /** Called whenever a white node is visited. Hook for subclasses.  */
    @Throws(java.lang.InterruptedException::class)
    protected abstract fun white(whiteNode: WHITE?)

    /** Called whenever a black node is visited. Hook for subclasses.  */
    @Throws(java.lang.InterruptedException::class)
    protected abstract fun black(blackNode: BLACK?)
}
