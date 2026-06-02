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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.cmdline.Label

/** cquery output formatter that prints the result as factored graph in AT&amp;T GraphViz format.  */
internal class GraphOutputFormatterCallback(
    eventHandler: ExtendedEventHandler?,
    options: CqueryOptions?,
    out: java.io.OutputStream?,
    skyframeExecutor: SkyframeExecutor?,
    accessor: TargetAccessor<CqueryNode?>?,
    private val depsRetriever: DepsRetriever,
    labelPrinter: LabelPrinter?
) : CqueryThreadsafeCallback(eventHandler, options, out, skyframeExecutor, accessor,  /* uniquifyResults= */false) {
    val name: String
        get() = "graph"

    /** Interface for finding a configured target's direct dependencies.  */
    fun interface DepsRetriever {
        @Throws(java.lang.InterruptedException::class)
        fun getDirectDeps(target: CqueryNode?): Iterable<CqueryNode?>?
    }

    private val nodeReader: NodeReader<CqueryNode?> = object : NodeReader<CqueryNode?> {
        private val configuredTargetOrdering: java.util.Comparator<CqueryNode?> =
            java.util.Comparator { ct1: CqueryNode?, ct2: CqueryNode? ->
                // Order graph output first by target label, then by configuration hash.
                val label1: Label = ct1.getOriginalLabel()
                val label2: Label = ct2.getOriginalLabel()
                if (!label1.equals(label2)) {
                    return@Comparator label1.compareTo(label2)
                }
                val checksum1: String? = ct1.getConfigurationChecksum()
                val checksum2: String? = ct2.getConfigurationChecksum()
                if (checksum1 == null) {
                    return@Comparator -1
                } else if (checksum2 == null) {
                    return@Comparator 1
                } else {
                    return@Comparator checksum1.compareTo(checksum2)
                }
            }

        override fun getLabel(
            node: com.google.devtools.build.lib.graph.Node<CqueryNode?>,
            labelPrinter: LabelPrinter
        ): String? {
            // Node payloads are ConfiguredTargets. Output node labels are target labels + config
            // hashes.
            val kct: CqueryNode? = node.label
            return java.lang.String.format(
                "%s (%s)",
                kct.getDescription(labelPrinter),
                CqueryThreadsafeCallback.Companion.shortId(getConfiguration(kct.getConfigurationKey()))
            )
        }

        override fun comparator(): java.util.Comparator<CqueryNode?> {
            return configuredTargetOrdering
        }
    }

    private val labelPrinter: LabelPrinter?

    init {
        this.labelPrinter = labelPrinter
    }

    @Throws(java.lang.InterruptedException::class)
    override fun processOutput(partialResult: Iterable<CqueryNode?>) {
        // Transform the cquery-backed graph into a Digraph to make it suitable for GraphOutputWriter.
        // Note that this involves an extra iteration over the entire query result subgraph. We could
        // conceptually merge transformation and output writing into the same iteration if needed.
        val graph: Digraph<CqueryNode?> = Digraph<CqueryNode?>()
        val allNodes: com.google.common.collect.ImmutableSet<CqueryNode?> =
            com.google.common.collect.ImmutableSet.copyOf<CqueryNode?>(partialResult)
        for (configuredTarget in partialResult) {
            val node: com.google.devtools.build.lib.graph.Node<CqueryNode?> = graph.createNode(configuredTarget)
            for (dep in depsRetriever.getDirectDeps(configuredTarget)!!) {
                if (allNodes.contains(dep)) {
                    val depNode: com.google.devtools.build.lib.graph.Node<CqueryNode?> = graph.createNode(dep)
                    graph.addEdge(node, depNode)
                }
            }
        }

        val graphWriter: GraphOutputWriter<CqueryNode?> =
            GraphOutputWriter<CqueryNode?>(
                nodeReader,
                options.getLineTerminator(),  /* sortLabels= */
                true,
                options.getGraphNodeStringLimit(),  // select() conditions don't matter for cquery because cquery operates post-analysis
                // phase, when select()s have been resolved and removed from the graph.
                /* maxConditionalEdges= */
                0,
                options.getGraphFactored(),
                labelPrinter
            )
        graphWriter.write(graph,  /*conditionalEdges=*/null, outputStream)
    }
}
