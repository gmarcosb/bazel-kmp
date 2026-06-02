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
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import java.util.function.ToLongFunction

/**
 * `Digraph` a generic directed graph or "digraph", suitable for modeling asymmetric binary
 * relations.
 * 
 * 
 * An instance `G = <V,E>` consists of a set of nodes or vertices `V`
 * , and a set of directed edges `E`, which is a subset of `V  V`. This
 * permits self-edges but does not represent multiple edges between the same pair of nodes.
 * 
 * 
 * Nodes may be labeled with values of any type (type parameter T). All nodes within a graph have
 * distinct labels. The null pointer is not a valid label.
 * 
 * 
 * The package supports various operations for modeling partial order relations, and supports
 * input/output in AT&amp;T's 'dot' format. See http://www.research.att.com/sw/tools/graphviz/.
 * 
 * 
 * Some invariants:
 * 
 * 
 *  * Each graph instances "owns" the nodes is creates. The behaviour of operations on nodes a
 * graph does not own is undefined.
 *  * `Digraph` assumes immutability of node labels, much like [HashMap] assumes it
 * for keys.
 *  * Mutating the underlying graph invalidates any sets and iterators backed by it.
 *  * Nodes can be added and removed concurrently. Edges can be added and removed concurrently
 * too. While it is thread safe to add or remove edge, these operations are not atomic. Graph
 * can be observable in inconsistent state during this operations, for instance: edge linked
 * to only one node.
 *  * 
 * 
 * 
 * 
 * Each node stores successor and predecessor adjacency sets using a representation that
 * dynamically changes with size: small sets are stored as arrays, large sets using hash tables.
 * This representation provides significant space and time performance improvements upon two prior
 * versions: the earliest used only HashSets; a later version used linked lists, as described in
 * Cormen, Leiserson &amp; Rivest.
 */
class Digraph<T>
/**
 * Construct an empty Digraph.
 */
    : Cloneable {
    /** Maps labels to nodes, which are in strict 1:1 correspondence.  */
    private val nodes: MutableMap<T?, Node<T?>> = ConcurrentHashMap<T?, Node<T?>>()

    /**
     * Check that a node is indeed a member of this graph and not another one. Perform this check
     * whenever a function is supplied a node by the user.
     */
    private fun checkNode(node: Node<T?>) {
        require(getNode(node.getLabel()) === node) {
            ("node " + node
                    + " is not a member of this graph")
        }
    }

    /**
     * Adds a directed edge between the nodes labelled 'from' and 'to', creating
     * them if necessary.
     * 
     * @return true iff the edge was not already present.
     */
    fun addEdge(from: T?, to: T?): Boolean {
        val fromNode = createNode(from)
        val toNode = createNode(to)
        return addEdge(fromNode, toNode)
    }

    /**
     * Adds a directed edge between the specified nodes, which must exist and
     * belong to this graph.
     * 
     * @return true iff the edge was not already present.
     * 
     * Note: multi-edges are ignored.  Self-edges are permitted.
     */
    fun addEdge(fromNode: Node<T?>, toNode: Node<T?>): Boolean {
        checkNode(fromNode)
        checkNode(toNode)
        return fromNode.addEdge(toNode)
    }

    /**
     * Removes the edge between the specified nodes.  Idempotent: attempts to
     * remove non-existent edges have no effect.
     * 
     * @return true iff graph changed.
     */
    fun removeEdge(fromNode: Node<T?>, toNode: Node<T?>): Boolean {
        checkNode(fromNode)
        checkNode(toNode)
        return fromNode.removeEdge(toNode)
    }

    override fun toString(): String {
        return "Digraph[" + this.nodeCount + " nodes]"
    }

    override fun hashCode(): Int {
        throw UnsupportedOperationException() // avoid nondeterminism
    }

    /**
     * Returns true iff the two graphs are equivalent, i.e. have the same set
     * of node labels, with the same connectivity relation.
     * 
     * O(n^2) in the worst case, i.e. equivalence.  The algorithm could be speed up by
     * close to a factor 2 in the worst case by a more direct implementation instead
     * of using isSubgraph twice.
     */
    override fun equals(thatObject: Any): Boolean {
        /* If this graph is a subgraph of thatObject, then we know that thatObject is of
     * type Digraph<?> and thatObject can be cast to this type.
     */
        return isSubgraph(thatObject) && (thatObject as Digraph<*>).isSubgraph(this)
    }

    /**
     * Returns true iff this graph is a subgraph of the argument. This means that this graph's nodes
     * are a subset of those of the argument; moreover, for each node of this graph the set of
     * successors is a subset of those of the corresponding node in the argument graph.
     * 
     * This algorithm is O(n^2), but linear in the total sizes of the graphs.
     */
    fun isSubgraph(thatObject: Any?): Boolean {
        if (this === thatObject) {
            return true
        }
        if (thatObject !is Digraph<*>) {
            return false
        }

        val that = thatObject as Digraph<T?>
        if (this.nodeCount > that.nodeCount) {
            return false
        }
        for (n1 in nodes.values()) {
            val n2 = that.getNodeMaybe(n1.getLabel())
            if (n2 == null) {
                return false // 'that' is missing a node
            }

            // Now compare the successor relations.
            // Careful:
            // - We can't do simple equality on the succs-sets because the
            //   nodes belong to two different graphs!
            // - There's no need to check both predecessor and successor
            //   relations, either one is sufficient.
            val n1succs: MutableCollection<Node<T?>> = n1.getSuccessors()
            val n2succs = n2.getSuccessors()
            if (n1succs.size() > n2succs.size()) {
                return false
            }
            // foreach successor of n1, ensure n2 has a similarly-labeled succ.
            for (succ1 in n1succs) {
                val succ2 = that.getNodeMaybe(succ1.getLabel())
                if (succ2 == null) {
                    return false
                }
                if (!n2succs.contains(succ2)) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * Returns a duplicate graph with the same set of node labels and the same
     * connectivity relation.  The labels themselves are not cloned.
     */
    public override fun clone(): Digraph<T?> {
        val that = Digraph<T?>()
        visitNodesBeforeEdges(
            object : AbstractGraphVisitor<T?>() {
                override fun visitEdge(lhs: Node<T?>, rhs: Node<T?>) {
                    that.addEdge(lhs.getLabel(), rhs.getLabel())
                }

                override fun visitNode(node: Node<T?>) {
                    that.createNode(node.getLabel())
                }
            },
            nodes.values(),
            null
        )
        return that
    }

    /** Returns a deterministic immutable copy of the nodes of this graph.  */
    fun getNodes(comparator: Comparator<in T?>): MutableCollection<Node<T?>?> {
        return ImmutableList.sortedCopyOf<Node<T?>?>(TODO("Cannot convert element")) < Node<T>
        TODO(
            """
            |Cannot convert element
            |With text:
            |T>comparing(Node::getLabel, comparator), nodes.values()
            """.trimMargin()
        )
    }

    /**
     * Returns an immutable view of the nodes of this graph.
     * 
     * Note: we have to return Collection and not Set because values() returns
     * one: the 'nodes' HashMap doesn't know that it is injective.  :-(
     */
    fun getNodes(): MutableCollection<Node<T?>?> {
        return Collections.unmodifiableCollection<Node<T?>?>(nodes.values())
    }

    val roots: MutableSet<Node<T?>?>
        /**
         * @return the set of root nodes: those with no predecessors.
         * 
         * NOTE: in a cyclic graph, there may be nodes that are not reachable from
         * any "root".
         */
        get() {
            val roots: MutableSet<Node<T?>?> =
                HashSet<Node<T?>?>()
            for (node in nodes.values()) {
                if (!node.hasPredecessors()) {
                    roots.add(node)
                }
            }
            return roots
        }

    val labels: MutableSet<T?>
        /**
         * @return an immutable view of the set of labels of this graph's nodes.
         */
        get() = Collections.unmodifiableSet<T?>(nodes.keySet())

    /**
     * Finds and returns the node with the specified label.  If there is no such
     * node, an exception is thrown.  The null pointer is not a valid label.
     * 
     * @return the node whose label is "label".
     * @throws IllegalArgumentException if no node was found with the specified
     * label.
     */
    fun getNode(label: T?): Node<T?> {
        if (label == null) {
            throw NullPointerException()
        }
        val node: Node<T?> = nodes.get(label)!!
        requireNotNull(node) { "No such node label: " + label }
        return node
    }

    /**
     * Find the node with the specified label.  Returns null if it doesn't exist.
     * The null pointer is not a valid label.
     * 
     * @return the node whose label is "label", or null if it was not found.
     */
    fun getNodeMaybe(label: T?): Node<T?>? {
        if (label == null) {
            throw NullPointerException()
        }
        return nodes.get(label)
    }

    val nodeCount: Int
        /**
         * @return the number of nodes in the graph.
         */
        get() = nodes.size()

    val edgeCount: Int
        /**
         * @return the number of edges in the graph.
         * 
         * Note: expensive! Useful when asserting against mutations though.
         */
        get() {
            var edges = 0
            for (node in nodes.values()) {
                edges += node.getSuccessors().size()
            }
            return edges
        }

    /**
     * Find or create a node with the specified label. This is the *only* factory of Nodes. The
     * null pointer is not a valid label.
     */
    fun createNode(label: T?): Node<T?> {
        return nodes.computeIfAbsent(label, Function { label: T? -> createNodeNative(label) })
    }

    val strongComponentGraph: Digraph<MutableSet<Node<T?>?>?>
        /**
         * Returns the strong component graph of "this".  That is, returns a new
         * acyclic graph in which all strongly-connected components in the original
         * graph have been "fused" into a single node.
         * 
         * @return a new graph, whose node labels are sets of nodes of the
         * original graph.  (Do not get confused as to which graph each
         * set of Nodes belongs!)
         */
        get() {
            val sccs =
                this.stronglyConnectedComponents
            val scGraph =
                createImageUnderPartition(sccs)
            scGraph.removeSelfEdges() // scGraph should be acyclic: no self-edges
            return scGraph
        }

    val stronglyConnectedComponents: MutableCollection<MutableSet<Node<T?>?>>
        /**
         * Returns a partition of the nodes of this graph into sets, each set being
         * one strongly-connected component of the graph.
         */
        get() {
            val sccs: MutableList<MutableSet<Node<T?>?>> =
                ArrayList<MutableSet<Node<T?>?>>()
            val r: NodeSetReceiver<T?> =
                NodeSetReceiver { e: MutableSet<Node<T?>?>? ->
                    sccs.add(e!!)
                }
            val v = SccVisitor<T?>()
            for (node in nodes.values()) {
                v.visit(r, node)
            }
            return sccs
        }

    /**
     * 
     *  Given a partition of the graph into sets of nodes, returns the image
     * of this graph under the function which maps each node to the
     * partition-set in which it appears.  The labels of the new graph are the
     * (immutable) sets of the partition, and the edges of the new graph are the
     * edges of the original graph, mapped via the same function. 
     * 
     * 
     *  Note: the resulting graph may contain self-edges.  If these are not
     * wanted, call `removeSelfEdges()`> on the result. 
     * 
     * 
     *  Interesting special case: if the partition is the set of
     * strongly-connected components, the result of this function is the
     * strong-component graph. 
     */
    fun createImageUnderPartition(partition: MutableCollection<MutableSet<Node<T?>?>>): Digraph<MutableSet<Node<T?>?>?> {
        // Build mapping function: each node label is mapped to its equiv class:

        val labelToImage: MutableMap<T?, MutableSet<Node<T?>?>?> = HashMap<T?, MutableSet<Node<T?>?>?>()
        for (set in partition) {
            // It's important to use immutable sets of node labels when sets are keys
            // in a map; see ImmutableSet class for explanation.
            val imageSet: MutableSet<Node<T?>> = ImmutableSet.copyOf<Node<T?>?>(set)
            for (node in imageSet) {
                labelToImage.put(node.getLabel(), imageSet)
            }
        }

        require(labelToImage.size() == this.nodeCount) { "createImageUnderPartition(): argument is not a partition" }

        return createImageUnderMapping<MutableSet<Node<T?>?>?>(labelToImage)
    }

    /**
     * Returns the image of this graph in a given function, expressed as a mapping from labels to some
     * other domain.
     */
    fun <ImageT> createImageUnderMapping(map: MutableMap<T?, ImageT?>): Digraph<ImageT?> {
        val imageGraph = Digraph<ImageT?>()

        for (fromNode in nodes.values()) {
            val fromLabel: T? = fromNode.getLabel()

            val fromImage = map.get(fromLabel)
            requireNotNull(fromImage) { "Incomplete function: undefined for " + fromLabel }
            imageGraph.createNode(fromImage)

            for (toNode in fromNode.getSuccessors()) {
                val toLabel: T? = toNode.getLabel()

                val toImage = map.get(toLabel)
                requireNotNull(toImage) { "Incomplete function: undefined for " + toLabel }
                imageGraph.addEdge(fromImage, toImage)
            }
        }

        return imageGraph
    }

    /**
     * Removes any self-edges (x,x) in this graph.
     */
    fun removeSelfEdges() {
        for (node in nodes.values()) {
            removeEdge(node, node)
        }
    }

    /**
     * Finds the shortest directed path from "fromNode" to "toNode". The path is returned as an
     * ordered list of nodes, including both endpoints. Returns null if there is no path. Uses
     * breadth-first search. Running time is O(n).
     */
    fun getShortestPath(fromNode: Node<T?>, toNode: Node<T?>): MutableList<Node<T?>?>? {
        checkNode(fromNode)
        checkNode(toNode)

        if (fromNode === toNode) {
            return Collections.singletonList<Node<T?>?>(fromNode)
        }

        val pathPredecessor: MutableMap<Node<T?>?, Node<T?>?> = HashMap<Node<T?>?, Node<T?>?>()

        val marked: MutableSet<Node<T?>?> = HashSet<Node<T?>?>()

        val queue = LinkedList<Node<T?>>()
        queue.addLast(fromNode)
        marked.add(fromNode)

        while (!queue.isEmpty()) {
            val u = queue.removeFirst()
            for (v in u.getSuccessors()) {
                if (marked.add(v)) {
                    pathPredecessor.put(v, u)
                    if (v === toNode) {
                        return getPathToTreeNode<Node<T?>?>(pathPredecessor, v) // found a path
                    }
                    queue.addLast(v)
                }
            }
        }
        return null // no path
    }

    val topologicalOrder: MutableList<Node<T?>?>
        /**
         * Returns the nodes of an acyclic graph in topological order
         * [a.k.a "reverse post-order" of depth-first search.]
         * 
         * A topological order is one such that, if (u, v) is a path in
         * acyclic graph G, then u is before v in the topological order.
         * In other words "tails before heads" or "roots before leaves".
         * 
         * @return The nodes of the graph, in a topological order
         */
        get() {
            val order = this.postorder
            Collections.reverse(order)
            return order
        }

    /**
     * Returns the nodes of an acyclic graph in topological order
     * [a.k.a "reverse post-order" of depth-first search.]
     * 
     * A topological order is one such that, if (u, v) is a path in
     * acyclic graph G, then u is before v in the topological order.
     * In other words "tails before heads" or "roots before leaves".
     * 
     * If an ordering is given, returns a specific topological order from the set
     * of all topological orders; if no ordering given, returns an arbitrary
     * (nondeterministic) one, but is a bit faster because no sorting needs to be
     * done for each node.
     * 
     * @param edgeOrder the ordering in which edges originating from the same node
     * are visited.
     * @return The nodes of the graph, in a topological order
     */
    fun getTopologicalOrder(edgeOrder: Comparator<in T?>): MutableList<Node<T?>?> {
        val visitor = CollectingVisitor<T?>()
        val visitation = DFS<T?>(DFS.Order.POSTORDER, edgeOrder, false)
        visitor.beginVisit()
        for (node in getNodes(edgeOrder)) {
            visitation.visit(node, visitor)
        }
        visitor.endVisit()

        val order = visitor.getVisitedNodes()
        Collections.reverse(order)
        return order
    }

    val postorder: MutableList<Node<T?>?>
        /**
         * Returns the nodes of an acyclic graph in post-order.
         */
        get() {
            val collectingVisitor = CollectingVisitor<T?>()
            visitPostorder(collectingVisitor)
            return collectingVisitor.getVisitedNodes()
        }

    /**
     * Returns the (immutable) set of nodes reachable from any node in `startNodes` (reflexive transitive closure).
     */
    fun getFwdReachable(startNodes: MutableCollection<Node<T?>?>): MutableSet<Node<T?>?>? {
        // This method is intentionally not static, to permit future expansion.
        val dfs = DFS<T?>(DFS.Order.PREORDER, false)
        for (n in startNodes) {
            dfs.visit(n, AbstractGraphVisitor<T?>())
        }
        return dfs.getMarked()
    }

    /**
     * Removes the specified node in the graph.
     * 
     * 
     * If preserveOrder flag is set than after removing node this method connects all predecessors
     * and successors.
     * 
     * 
     * Let's consider graph
     * 
     * <pre>
     * a -> n -> c
     * b -> n -> d
    </pre> * 
     * 
     * After n removed the following edges will be added
     * 
     * <pre>
     * a -> c
     * a -> d
     * b -> c
     * b -> d
    </pre> * 
     * 
     * @param node the node to remove (must be in the graph).
     * @param preserveOrder see removeNode(T, boolean).
     */
    fun removeNode(node: Node<T?>, preserveOrder: Boolean): MutableCollection<Node<T?>?> {
        checkNode(node)

        val predecessors = node.removeAllPredecessors()
        val successors = node.removeAllSuccessors()

        var neighbours = Collections.emptyList<Node<T?>?>()

        if (preserveOrder) {
            neighbours = ArrayList<Node<T?>?>(successors.size() + predecessors.size())
            neighbours.addAll(successors)
            neighbours.addAll(predecessors)

            for (p in predecessors) {
                for (s in successors) {
                    p.addEdge(s)
                }
            }
        }

        val del: Any? = nodes.remove(node.getLabel())
        check(del === node) { del.toString() + " " + node }

        return neighbours
    }

    /**
     * Extracts the subgraph G' of this graph G, containing exactly the nodes
     * specified by the labels in V', and preserving the original
     * *transitive* graph relation among those nodes. 
     * 
     * @param subset a subset of the labels of this graph; the resulting graph
     * will have only the nodes with these labels.
     */
    fun extractSubgraph(subset: MutableSet<T?>): Digraph<T?> {
        val subgraph = this.clone()
        subgraph.subgraph(subset)
        return subgraph
    }

    /**
     * Removes all nodes from this graph except those whose label is an element of `keepLabels`.
     * Edges are added so as to preserve the *transitive* closure relation.
     * 
     * @param keepLabels a subset of the labels of this graph; the resulting graph will have only the
     * nodes with these labels.
     */
    private fun subgraph(keepLabels: MutableSet<T?>) {
        // This algorithm does the following:
        // Let keep = nodes that have labels in keepLabels.
        // Let toRemove = nodes \ keep. reachables = successors and predecessors of keep in nodes.
        // reachables is the subset of nodes of remove that are an immediate neighbor of some node in
        // keep.
        //
        // Removes all nodes of reachables from keepLabels.
        // Until reachables is empty:
        //   Takes n from reachables
        //   for all s in succ(n)
        //     for all p in pred(n)
        //       add the edge (p, s)
        //     add s to reachables
        //   for all p in pred(n)
        //     add p to reachables
        //   Remove n and its edges
        //
        // A few adjustments are needed to do the whole computation.

        val toRemove: MutableSet<Node<T?>> = HashSet<Node<T?>>()
        val keepNeighbors: MutableSet<Node<T?>?> = HashSet<Node<T?>?>()

        // Look for all nodes if they are to be kept or removed
        for (node in nodes.values()) {
            if (keepLabels.contains(node.getLabel())) {
                // Node is to be kept
                keepNeighbors.addAll(node.getPredecessors())
                keepNeighbors.addAll(node.getSuccessors())
            } else {
                // node is to be removed.
                toRemove.add(node)
            }
        }

        if (toRemove.isEmpty()) {
            // This premature return is needed to avoid 0-size priority queue creation.
            return
        }

        // We use a priority queue to look for low-order nodes first so we don't propagate the high
        // number of paths of high-order nodes making the time consumption explode.
        // For perfect results we should reorder the set each time we add a new edge but this would
        // be too expensive, so this is a good enough approximation.
        val reachables =
            PriorityQueue<Node<T?>>(
                toRemove.size(),
                Comparator.comparingLong<Node<T?>?>(ToLongFunction { arg: Node<T?>? ->
                    arg!!.numPredecessors().toLong() * arg.numSuccessors().toLong()
                })
            )

        // Construct the reachables queue with the list of successors and predecessors of keep in
        // toRemove.
        keepNeighbors.retainAll(toRemove)
        reachables.addAll(keepNeighbors)
        toRemove.removeAll(reachables)

        // Remove nodes, least connected first, preserving reachability.
        while (!reachables.isEmpty()) {
            val node = reachables.poll()

            val neighbours = removeNode(node,  /*preserveOrder*/true)

            for (neighbour in neighbours) {
                if (toRemove.remove(neighbour!!)) {
                    reachables.add(neighbour)
                }
            }
        }

        // Final cleanup for non-reachable nodes.
        for (node in toRemove) {
            removeNode(node, false)
        }
    }

    private fun interface NodeSetReceiver<T> {
        fun accept(nodes: MutableSet<Node<T?>?>?)
    }

    /**
     * Find strongly connected components using path-based strong component algorithm. This has the
     * advantage over the default method of returning the components in postorder.
     * 
     * 
     * We visit nodes depth-first, keeping track of the order that we visit them in (preorder). Our
     * goal is to find the smallest node (in this preorder of visitation) reachable from a given node.
     * We keep track of the smallest node pointed to so far at the top of a stack. If we ever find an
     * already-visited node, then if it is not already part of a component, we pop nodes from that
     * stack until we reach this already-visited node's number or an even smaller one.
     * 
     * 
     * Once the depth-first visitation of a node is complete, if this node's number is at the top
     * of the stack, then it is the "first" element visited in its strongly connected component. Hence
     * we pop all elements that were pushed onto the visitation stack and put them in a strongly
     * connected component with this one, then send a passed-in [Digraph.NodeSetReceiver] this
     * component.
     */
    private class SccVisitor<T2> {
        // Nodes already assigned to a strongly connected component.
        private val assigned: MutableSet<Node<T2?>?> = HashSet<Node<T2?>?>()

        // The order each node was visited in.
        private val preorder: MutableMap<Node<T2?>?, Int?> = HashMap<Node<T2?>?, Int?>()

        // Stack of all nodes visited whose SCC has not yet been determined. When an SCC is found,
        // that SCC is an initial segment of this stack, and is popped off. Every time a new node is
        // visited, it is put on this stack.
        private val stack: MutableList<Node<T2?>?> = ArrayList<Node<T2?>?>()

        // Stack of visited indices for the first-visited nodes in each of their known-so-far
        // strongly connected components. A node pushes its index on when it is visited. If any of
        // its successors have already been visited and are not in an already-found strongly connected
        // component, then, since the successor was already visited, it and this node must be part of a
        // cycle. So every node visited since the successor is actually in the same strongly connected
        // component. In this case, preorderStack is popped until the top is at most the successor's
        // index.
        //
        // After all descendants of a node have been visited, if the top element of preorderStack is
        // still the current node's index, then it was the first element visited of the current strongly
        // connected component. So all nodes on {@code stack} down to the current node are in its
        // strongly connected component. And the node's index is popped from preorderStack.
        private val preorderStack: MutableList<Int?> = ArrayList<Int?>()

        // Index of node being visited.
        private var counter = 0

        fun visit(visitor: NodeSetReceiver<T2?>, node: Node<T2?>) {
            if (preorder.containsKey(node)) {
                // This can only happen if this was a non-recursive call, and a previous
                // visit call had already visited node.
                return
            }
            preorder.put(node, counter)
            stack.add(node)
            preorderStack.add(counter++)
            val preorderLength: Int = preorderStack.size()
            for (succ in node.getSuccessors()) {
                val succPreorder = preorder.get(succ)
                if (succPreorder == null) {
                    visit(visitor, succ)
                } else {
                    // Does succ not already belong to an SCC? If it doesn't, then it
                    // must be in the same SCC as node. The "starting node" of this SCC
                    // must have been visited before succ (or is succ itself).
                    if (!assigned.contains(succ)) {
                        while (preorderStack.get(preorderStack.size() - 1)!! > succPreorder) {
                            preorderStack.remove(preorderStack.size() - 1)
                        }
                    }
                }
            }
            if (preorderLength == preorderStack.size()) {
                // If the length of the preorderStack is unchanged, we did not find any earlier-visited
                // nodes that were part of a cycle with this node. So this node is the first-visited
                // element in its strongly connected component, and we collect the component.
                preorderStack.remove(preorderStack.size() - 1)
                val scc: MutableSet<Node<T2?>?> = HashSet<Node<T2?>?>()
                var compNode: Node<T2?>?
                do {
                    compNode = stack.remove(stack.size() - 1)
                    assigned.add(compNode)
                    scc.add(compNode)
                } while (node != compNode)
                visitor.accept(scc)
            }
        }
    }

    /********************************************************************
     * *
     * Orders, traversals and visitors               *
     * *
     */
    /**
     * A visitation over all the nodes in the graph that invokes
     * `visitor.visitNode()` for each node in a depth-first
     * post-order: each node is visited *after* each of its successors; the
     * order in which edges are traversed is the order in which they were added
     * to the graph.  `visitor.visitEdge()` is not called.
     * 
     * @param startNodes the set of nodes from which to begin the visitation.
     */
    /**
     * Equivalent to `visitPostorder(visitor, getNodes())`.
     */
    @kotlin.jvm.JvmOverloads
    fun visitPostorder(
        visitor: GraphVisitor<T?>,
        startNodes: Iterable<Node<T?>?> = nodes.values()
    ) {
        visitDepthFirst(visitor, DFS.Order.POSTORDER, false, startNodes)
    }

    /**
     * A visitation over all the nodes in the graph in depth-first order.  See
     * DFS constructor for meaning of 'order' and 'transpose' parameters.
     * 
     * @param startNodes the set of nodes from which to begin the visitation.
     */
    fun visitDepthFirst(
        visitor: GraphVisitor<T?>,
        order: DFS.Order?,
        transpose: Boolean,
        startNodes: Iterable<Node<T?>?>
    ) {
        val visitation = DFS<T?>(order, transpose)
        visitor.beginVisit()
        for (node in startNodes) {
            visitation.visit(node, visitor)
        }
        visitor.endVisit()
    }

    private fun visitNodesBeforeEdges(
        visitor: GraphVisitor<T?>,
        startNodes: Iterable<Node<T?>>,
        comparator: Comparator<in T?>?
    ) {
        visitor.beginVisit()
        for (fromNode in startNodes) {
            visitor.visitNode(fromNode)
            for (toNode in maybeOrderCollection<T?>(fromNode.getSuccessors(), comparator)!!) {
                visitor.visitEdge(fromNode, toNode)
            }
        }
        visitor.endVisit()
    }

    /**
     * A visitation over the graph that visits all nodes and edges in topological order
     * such that each node is visited before any edge coming out of that node; ties among nodes are
     * broken using the provided `comparator` if not null; edges are visited in order specified
     * by the comparator, **not** topological order of the target nodes.
     */
    fun visitNodesBeforeEdges(
        visitor: GraphVisitor<T?>, comparator: Comparator<in T?>?
    ) {
        visitNodesBeforeEdges(
            visitor,
            if (comparator == null) this.topologicalOrder else getTopologicalOrder(comparator),
            comparator
        )
    }

    companion object {
        private fun <T> createNodeNative(label: T?): Node<T?> {
            Preconditions.checkNotNull<T?>(label)
            return Node<T?>(label)
        }

        /**
         * Given a tree (expressed as a map from each node to its parent), and a
         * starting node, returns the path from the root of the tree to 'node' as a
         * list.
         */
        fun <X> getPathToTreeNode(tree: MutableMap<X?, X?>, node: X?): MutableList<X?> {
            var node = node
            val path: MutableList<X?> = ArrayList<X?>()
            while (node != null) {
                path.add(node)
                node = tree.get(node) // get parent
            }
            Collections.reverse(path)
            return path
        }

        private fun <T> makeNodeComparator(
            comparator: Comparator<in T?>
        ): Comparator<Node<T?>?> {
            return
            T > Comparator.comparing(
                { obj: Node<*>? -> obj!!.getLabel() },
                { o1: T?, o2: T? -> comparator.compare(o1, o2) })
        }

        /**
         * Given `unordered`, a collection of nodes and a (possibly null) `comparator` for
         * their labels, returns a sorted collection if `comparator` is non-null, otherwise returns
         * `unordered`.
         */
        private fun <T> maybeOrderCollection(
            unordered: MutableCollection<Node<T?>>, comparator: Comparator<in T?>?
        ): MutableCollection<Node<T?>?>? {
            return if (comparator == null)
                unordered
            else
                ImmutableList.sortedCopyOf<Node<T?>?>(makeNodeComparator<T?>(comparator), unordered)
        }
    }
}
