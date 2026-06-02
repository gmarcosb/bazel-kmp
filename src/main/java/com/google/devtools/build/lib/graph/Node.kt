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

import com.google.common.base.Preconditions

/**
 * A generic directed-graph Node class. Type parameter T is the type of the node's label.
 * 
 * 
 * Each node is identified by a label, which is unique within the graph owning the node.
 * 
 * 
 * Nodes are immutable, that is, their labels cannot be changed. However, their
 * predecessor/successor lists are mutable.
 * 
 * 
 * Nodes cannot be created directly by clients.
 * 
 * 
 * Clients should not confuse nodes belonging to two different graphs! (Use Digraph.checkNode()
 * to catch such errors.) There is no way to find the graph to which a node belongs; it is
 * intentionally not represented, to save space.
 * 
 * 
 * During adding or removing edge locks always hold in specific order: first=nodeFrom.succs then
 * second=nodeTo.preds. That's why reordering deadlock never happens.
 */
class Node<T> internal constructor(label: T?) {
    /**
     * Returns the label for this node.
     */
    @kotlin.jvm.JvmField
    val label: T?

    /** A duplicate-free collection of edges from this node. May be null, indicating the empty set.  */
    private val succs = ConcurrentCollectionWrapper<Node<T?>?>()

    /** A duplicate-free collection of edges to this node. May be null, indicating the empty set.  */
    private val preds = ConcurrentCollectionWrapper<Node<T?>?>()

    /**
     * Only Digraph.createNode() can call this!
     */
    init {
        this.label = Preconditions.checkNotNull<T?>(label, "label")
    }

    val successors: MutableCollection<Node<T?>?>?
        /**
         * Returns a duplicate-free collection of the nodes that this node links to.
         */
        get() = this.succs.get()

    /**
     * Remove all successors edges and return collection of its. Self edge removed but did not
     * returned in result collection.
     * 
     * @return all existed before successor nodes but this.
     */
    fun removeAllSuccessors(): MutableCollection<Node<T?>> {
        this.removeEdge(this) // remove self edge
        val successors = this.succs.clear()
        for (s in successors) {
            check(s.removePredecessor(this)) { "inconsistent graph state" }
        }
        return successors
    }

    /**
     * Equivalent to `getSuccessors().size()` but possibly more efficient.
     */
    fun numSuccessors(): Int {
        return this.succs.size()
    }

    val predecessors: MutableCollection<Node<T?>?>?
        /**
         * Returns an (unordered, possibly immutable) set of the nodes that link to
         * this node.
         */
        get() = this.preds.get()

    /**
     * Remove all predecessors edges and return collection of its. Self edge removed but did not
     * returned in result collection.
     * 
     * @return all existed before predecessor nodes but this.
     */
    fun removeAllPredecessors(): MutableCollection<Node<T?>> {
        this.removeEdge(this) // remove self edge
        val predecessors = this.preds.clear()
        for (p in predecessors) {
            check(p.removeSuccessor(this)) { "inconsistent graph state" }
        }
        return predecessors
    }

    /**
     * Equivalent to `!getPredecessors().isEmpty()` but possibly more
     * efficient.
     */
    fun hasPredecessors(): Boolean {
        return !preds.get().isEmpty()
    }

    /** Equivalent to `getPredecessors().size()` but possibly more efficient.  */
    fun numPredecessors(): Int {
        return this.preds.size()
    }

    /**
     * Adds edge from this node to target
     * 
     * 
     * In this method one lock held inside another lock. But it can not be reason of reordering
     * deadlock. Lock always holds in direction fromNode.succs -> toNode.preds.
     * @see .removeEdge
     * @return true if edge had been added. false - otherwise.
     */
    fun addEdge(target: Node<T?>): Boolean {
        synchronized(succs) {
            val isNewSuccessor = this.succs.add(target)
            val isNewPredecessor = target.addPredecessor(this)
            check(isNewPredecessor == isNewSuccessor) { "inconsistent graph state" }
            return isNewSuccessor
        }
    }

    /**
     * Adds edge from this node to target
     * 
     * 
     * In this method one lock held inside another lock. But it can not be reason of reordering
     * deadlock. Lock always holds in direction fromNode.succs -> toNode.preds.
     * @see .addEdge
     * @return true if edge had been removed. false - otherwise.
     */
    fun removeEdge(target: Node<T?>): Boolean {
        synchronized(succs) {
            val isSuccessorRemoved = this.succs.remove(target)
            if (isSuccessorRemoved) {
                val isPredecessorRemoved = target.removePredecessor(this)
                check(isPredecessorRemoved) { "inconsistent graph state" }
                return true
            }
            return false
        }
    }

    /**
     * Add 'from' as a predecessor of 'this' node. Returns true iff the graph changed. Private: breaks
     * graph invariant!
     */
    private fun addPredecessor(from: Node<T?>?): Boolean {
        return preds.add(from)
    }

    /**
     * Remove edge: toNode.preds = {n | n in toNode.preds && n != fromNode} Private: breaks graph
     * invariant!
     */
    private fun removePredecessor(from: Node<T?>?): Boolean {
        return preds.remove(from)
    }

    private fun removeSuccessor(to: Node<T?>?): Boolean {
        return succs.remove(to)
    }

    override fun toString(): String {
        return "node:" + label
    }

    override fun hashCode(): Int {
        return super.hashCode()
    }

    override fun equals(that: Any?): Boolean {
        return this === that // Nodes are unique for a given label
    }
}
