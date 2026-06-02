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

import com.google.devtools.build.lib.collect.compacthashset.CompactHashSet
import java.util.*
import java.util.function.Supplier
import java.util.stream.Collectors
import kotlin.collections.MutableList
import kotlin.collections.MutableSet

/**
 * 
 *  The DFS class encapsulates a depth-first search visitation, including
 * the order in which nodes are to be visited relative to their successors
 * (PREORDER/POSTORDER), whether the forward or transposed graph is to be
 * used, and which nodes have been seen already. 
 * 
 * 
 *  A variety of common uses of DFS are offered through methods of
 * Digraph; however clients can use this class directly for maximum
 * flexibility.  See the implementation of
 * Digraph.getStronglyConnectedComponents() for an example. 
 * 
 * 
 *  Clients should not modify the enclosing Digraph instance of a DFS
 * while a traversal is in progress. 
 */
class DFS<T>(// = (PREORDER|POSTORDER)
    private val order: Order?, edgeOrder: Comparator<in T?>?, private val transpose: Boolean
) {
    // (Preferred over a boolean to avoid parameter confusion.)
    enum class Order {
        PREORDER,
        POSTORDER
    }

    private val edgeOrder: Comparator<Node<T?>?>? = null

    private val marked: MutableSet<Node<T?>?> = CompactHashSet.create<Node<T?>?>()

    /**
     * Constructs a DFS instance for searching over the enclosing Digraph
     * instance, using the specified visitation parameters.
     * 
     * @param order PREORDER or POSTORDER, determines node visitation order
     * @param edgeOrder an ordering in which the edges originating from the same
     * node should be visited (if null, the order is unspecified)
     * @param transpose iff true, the graph is implicitly transposed during
     * visitation.
     */
    init {
        TODO(
            """
            |Cannot convert element
            |With text:
            |this.edgeOrder = (edgeOrder == null) ? null : <Node<T>, T>comparing(Node::getLabel, edgeOrder::compare);
            """.trimMargin()
        )
    }

    constructor(order: Order?, transpose: Boolean) : this(order, null, transpose)

    /**
     * Returns the (immutable) set of nodes visited so far.
     */
    fun getMarked(): MutableSet<Node<T?>?> {
        return Collections.unmodifiableSet<Node<T?>?>(marked)
    }

    fun visit(node: Node<T?>, visitor: GraphVisitor<T?>) {
        if (!marked.add(node)) {
            return
        }

        if (order == Order.PREORDER) {
            visitor.visitNode(node)
        }

        var edgeTargets = if (transpose)
            node.getPredecessors()
        else
            node.getSuccessors()
        if (edgeOrder != null) {
            val mutableNodeList: MutableList<Node<T?>> =
                edgeTargets.stream().sorted(edgeOrder).collect(Collectors.toCollection(Supplier { ArrayList() }))
            edgeTargets = mutableNodeList
        }

        for (v in edgeTargets) {
            visit(v, visitor)
        }

        if (order == Order.POSTORDER) {
            visitor.visitNode(node)
        }
    }
}
