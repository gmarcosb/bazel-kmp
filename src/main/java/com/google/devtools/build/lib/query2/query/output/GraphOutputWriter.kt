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
package com.google.devtools.build.lib.query2.query.output

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import com.google.common.collect.Ordering
import com.google.devtools.build.lib.cmdline.Label
import com.google.devtools.build.lib.graph.Node
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Optional
import kotlin.Any
import kotlin.Boolean
import kotlin.Comparator
import kotlin.Int
import kotlin.String
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableCollection
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet

/**
 * Generic logic for writing query expression results to [GraphViz](http://graphviz.org/doc/info/lang.html) format.
 * 
 * 
 * This can be used by any query implementation that can provide results as a [Digraph].
 */
class GraphOutputWriter<T>(
    private val nodeReader: NodeReader<T?>,
    private val lineTerminator: String?,
    private val sortLabels: Boolean,
    private val maxLabelSize: Int,
    private val maxConditionalEdges: Int,
    private val mergeEquivalentNodes: Boolean,
    labelPrinter: LabelPrinter?
) {
    /** Interface for reading the contents of a [Digraph] [Node].  */
    interface NodeReader<T> {
        /**
         * Returns the label to associate with a GraphViz node.
         * 
         * 
         * This is not the same as a build [Label]. This is just the text associated with a
         * node in a GraphViz graph.
         */
        fun getLabel(node: Node<T?>?, labelPrinter: LabelPrinter?): String

        /** Returns a comparator for the build graph nodes that form the payloads of GraphViz nodes.  */
        fun comparator(): Comparator<T?>?
    }

    private val nodeComparator: Ordering<Node<T?>?>
    private val labelPrinter: LabelPrinter?

    /**
     * Constructors a new writer.
     * 
     * @param nodeReader [NodeReader] for reading node content
     * @param lineTerminator line string terminator
     * @param sortLabels if true, output nodes in sorted order with [NodeReader.comparator])
     * @param maxLabelSize maximum characters in label output. Longer labels are truncated. -1 means
     * no limit.
     * @param maxConditionalEdges maximum number of `select() conditional labels` to show on
     * each edge. -1 means no limit. 0 means no labels.
     * @param mergeEquivalentNodes if true, topologically equivalent nodes are merged together as
     * multiple labels in the same node. This condenses the graph. For example, given graph `(nodes=[A, B, C], edges=[A->B, A->C]) `, the output has two nodes: "A" and "B,C".
     */
    init {
        this.labelPrinter = labelPrinter
        nodeComparator = Ordering.from<T?>(nodeReader.comparator()).onResultOf<Node<T?>?>(Node::label)
    }

    /**
     * Writes the given graph.
     * 
     * @param graph build graph to write
     * @param conditionalEdges edges corresponding to select()s (see [ConditionalEdges])
     * @param out output stream to write to
     */
    fun write(
        graph: Digraph<T?>, conditionalEdges: ConditionalEdges?, out: OutputStream
    ) {
        val printWriter: PrintWriter = PrintWriter(OutputStreamWriter(out, StandardCharsets.UTF_8))
        if (mergeEquivalentNodes) {
            outputFactored(graph, conditionalEdges, printWriter)
        } else {
            outputUnfactored(graph, conditionalEdges, printWriter)
        }
    }

    private fun outputUnfactored(
        graph: Digraph<T?>, conditionalEdges: ConditionalEdges?, out: PrintWriter
    ) {
        graph.visitNodesBeforeEdges(
            object : DotOutputVisitor<T?>(
                out, lineTerminator, LabelSerializer { node: Node<T?>? -> nodeReader.getLabel(node, labelPrinter) }) {
                public override fun beginVisit() {
                    super.beginVisit()
                    // TODO(bazel-team): (2009) make this the default in Digraph.
                    out.printf("  node [shape=box];%s", lineTerminator)
                }

                public override fun visitEdge(lhs: Node<T?>, rhs: Node<T?>) {
                    super.visitEdge(lhs, rhs)
                    val outputLabel =
                        getConditionsGraphLabel(
                            ImmutableSet.of<Node<T?>?>(lhs), ImmutableSet.of<Node<T?>?>(rhs), conditionalEdges
                        )
                    if (!outputLabel.isEmpty()) {
                        out.printf("  [label=\"%s\"];%s", outputLabel, lineTerminator)
                    }
                }
            },
            if (sortLabels) nodeReader.comparator() else null
        )
    }

    /**
     * Given `collectionOfUnorderedSets`, a collection of sets of nodes, returns a collection of
     * sets with the same elements as `collectionOfUnorderedSets` but with a stable iteration
     * order within each set given by the target ordering, and the collection ordered by the same
     * induced order.
     */
    private fun orderPartition(
        collectionOfUnorderedSets: MutableCollection<MutableSet<Node<T?>?>>
    ): MutableCollection<MutableSet<Node<T?>?>> {
        val result: MutableList<MutableSet<Node<T?>?>> = ArrayList<MutableSet<Node<T?>?>>()
        for (part in collectionOfUnorderedSets) {
            val toSort: MutableList<Node<T?>?> = ArrayList<Node<T?>?>(part)
            Collections.sort<Node<T?>?>(toSort, nodeComparator)
            result.add(ImmutableSet.copyOf<Node<T?>?>(toSort))
        }
        Collections.sort<MutableSet<Node<T?>?>?>(result, nodeComparator.lexicographical<Node<T?>?>())
        return result
    }

    private fun outputFactored(
        graph: Digraph<T?>, conditionalEdges: ConditionalEdges?, out: PrintWriter
    ) {
        var partition: MutableCollection<MutableSet<Node<T?>?>> = partitionFactored(graph)
        if (sortLabels) {
            partition = orderPartition(partition)
        }

        val factoredGraph: Digraph<MutableSet<Node<T?>?>?> = graph.createImageUnderPartition(partition)

        // Concatenate the labels of all topologically-equivalent nodes.
        val labelSerializer: LabelSerializer<MutableSet<Node<T?>?>?> =
            LabelSerializer { node: Node<MutableSet<Node<T?>?>?>? ->
                val actualLimit: Int = maxLabelSize - RESERVED_LABEL_CHARS
                var firstItem = true
                val buf = StringBuilder()
                var count = 0
                for (eqNode in node!!.label!!) {
                    val labelString = nodeReader.getLabel(eqNode, labelPrinter)
                    if (!firstItem) {
                        buf.append("\\n")

                        // Use -1 to denote no limit, as it is easier than trying to pass MAX_INT on the
                        // cmdline
                        if (maxLabelSize != -1 && (buf.length + labelString.length > actualLimit)) {
                            buf.append("...and ")
                            buf.append(node.label!!.size - count)
                            buf.append(" more items")
                            break
                        }
                    }

                    buf.append(labelString)
                    count++
                    firstItem = false
                }
                buf.toString()
            }

        factoredGraph.visitNodesBeforeEdges(
            object : DotOutputVisitor<MutableSet<Node<T?>?>?>(out, lineTerminator, labelSerializer) {
                public override fun beginVisit() {
                    super.beginVisit()
                    // TODO(bazel-team): (2009) make this the default in Digraph.
                    out.printf("  node [shape=box];%s", lineTerminator)
                }

                public override fun visitEdge(lhs: Node<MutableSet<Node<T?>?>?>, rhs: Node<MutableSet<Node<T?>?>?>) {
                    super.visitEdge(lhs, rhs)

                    val outputLabel =
                        getConditionsGraphLabel(lhs.label, rhs.label, conditionalEdges)
                    if (!outputLabel.isEmpty()) {
                        out.printf("  [label=\"%s\"];%s", outputLabel, lineTerminator)
                    }
                }
            },
            if (sortLabels) nodeComparator.lexicographical<Node<T?>?>() else null
        )
    }

    /**
     * Partitions the graph into equivalence classes of topologically equivalent nodes.
     * 
     * 
     * Algorithm: Visit each node, comparing children with each other based on the eq relation to
     * put them into their eq classes. Compare top-level nodes as well as though they were children of
     * a fake root node.
     * 
     * 
     * Invariant: Two nodes are in the same equivalence class -> they have the same parents (and
     * children).
     * 
     * 
     * Contrapositive: If two nodes do not have the same parents (or children) -> they are not in
     * the same equivalence class.
     * 
     * 
     * Because of the contrapositive, we only need to compare children nodes of each parent node
     * (rather than each node with every other node). This allows us to significantly reduce the
     * number of comparisons between nodes.
     * 
     * @param graph the graph to partition.
     * @return a collection of equivalence classes (sets of nodes).
     */
    private fun partitionFactored(graph: Digraph<T?>): ImmutableList<MutableSet<Node<T?>?>> {
        // Two nodes are equivalent iff they have the same successors and predecessors.
        val equivalenceRelation: EquivalenceRelation<Node<T?>?> =
            EquivalenceRelation { x, y ->
                if (x == y) {
                    return@EquivalenceRelation 0
                }
                if (x.numPredecessors() !== y.numPredecessors()
                    || x.numSuccessors() !== y.numSuccessors()
                ) {
                    return@EquivalenceRelation -1
                }

                val xpred: MutableSet<Node<T?>?> = HashSet<Any?>(x.getPredecessors())
                val ypred: MutableSet<Node<T?>?> = HashSet<Any?>(y.getPredecessors())
                if (xpred != ypred) {
                    return@EquivalenceRelation -1
                }

                val xsucc: MutableSet<Node<T?>?> = HashSet<Any?>(x.getSuccessors())
                val ysucc: MutableSet<Node<T?>?> = HashSet<Any?>(y.getSuccessors())
                if (xsucc != ysucc) {
                    return@EquivalenceRelation -1
                }
                0
            }

        // Keep a map of equivalence classes that each node belongs to, so that we know whether a node
        // already belongs to one.
        val eqClasses: HashMap<Node<T?>?, MutableSet<Node<T?>?>?> = HashMap<Node<T?>?, MutableSet<Node<T?>?>?>()

        // Top-level nodes need to be compared amongst each other because they can form an equivalence
        // class amongst themselves too.
        processSuccessors(ImmutableList.copyOf<Node<T?>?>(graph.roots), eqClasses, equivalenceRelation)

        // For each node, compare its children with each other to put them into equivalence classes.
        for (node in graph.getNodes()) {
            processSuccessors(ImmutableList.copyOf<Node<T?>?>(node.successors), eqClasses, equivalenceRelation)
        }

        return eqClasses.values.stream().distinct().collect(ImmutableList.toImmutableList<MutableSet<Node<T?>?>?>())
    }

    /**
     * Compares a list of successors of a parent node amongst each other and adds them to their
     * equivalence classes.
     * 
     * @param successors list of successors to compare.
     * @param eqClasses map containing the equivalence class that a node belongs to.
     * @param equivalenceRelation the equivalence relation by which the equivalence classes are *
     * defined.
     */
    private fun processSuccessors(
        successors: MutableList<Node<T?>?>,
        eqClasses: MutableMap<Node<T?>?, MutableSet<Node<T?>?>?>,
        equivalenceRelation: EquivalenceRelation<Node<T?>?>
    ) {
        val numSuccessors = successors.size

        for (i in 0..<numSuccessors) {
            val child = successors.get(i)
            if (eqClasses.containsKey(child)) {
                // This child has already been added to an equivalence class, there is no need to compare
                // because all members in that equivalence class would have already been added.
                continue
            }

            // Put the child in its own equivalence class and compare with its siblings.
            val eqClass: MutableSet<Node<T?>?> = HashSet<Node<T?>?>()
            eqClass.add(child)
            eqClasses.put(child, eqClass)

            // Start at i+1, since j <= i has already been checked.
            for (j in i + 1..<numSuccessors) {
                val sibling = successors.get(j)
                if (eqClasses.containsKey(sibling)) {
                    // The sibling has already been added to another equivalence class, no need to compare.
                    continue
                }

                // This is expensive, so we want to minimize this as much as possible.
                if (equivalenceRelation.compare(child, sibling) === 0) {
                    eqClass.add(sibling)
                    eqClasses.put(sibling, eqClass)
                }
            }
        }
    }

    private fun getConditionsGraphLabel(
        lhs: Iterable<Node<T?>>, rhs: Iterable<Node<T?>>, conditionalEdges: ConditionalEdges?
    ): String {
        val buf = StringBuilder()
        if (conditionalEdges == null || maxConditionalEdges == 0) {
            return buf.toString()
        }

        val annotatedLabels: MutableSet<Label?> = HashSet<Label?>()
        for (src in lhs) {
            val srcLabel: Label? = (src.label as Target).getLabel()
            for (dest in rhs) {
                val destLabel: Label? = (dest.label as Target).getLabel()
                val conditions: Optional<MutableSet<Label?>?> = conditionalEdges.get(srcLabel, destLabel)
                if (conditions.isPresent()) {
                    var firstItem = true

                    val limit =
                        if (maxConditionalEdges == -1) conditions.get().size else (maxConditionalEdges - 1)

                    for (conditionLabel in Iterables.limit<Label>(conditions.get(), limit)) {
                        if (!annotatedLabels.add(conditionLabel)) {
                            // duplicate label; skip.
                            continue
                        }

                        if (!firstItem) {
                            buf.append("\\n")
                        }

                        buf.append(conditionLabel.getCanonicalForm())
                        firstItem = false
                    }
                    if (conditions.get().size > limit) {
                        buf.append("...")
                    }
                }
            }
        }
        return buf.toString()
    }

    companion object {
        private val RESERVED_LABEL_CHARS = "\\n...and 9999999 more items".length
    }
}
