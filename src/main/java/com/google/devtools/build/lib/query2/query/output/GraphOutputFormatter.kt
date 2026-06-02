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
package com.google.devtools.build.lib.query2.query.output

import com.google.common.hash.HashFunction
import com.google.devtools.build.lib.events.EventHandler
import com.google.devtools.build.lib.graph.Node
import java.io.OutputStream
import kotlin.Comparator
import kotlin.String
import kotlin.toString

/**
 * An output formatter that prints the result as factored graph in AT&amp;T
 * GraphViz format.
 */
internal class GraphOutputFormatter : OutputFormatter() {
    override fun getName(): String {
        return "graph"
    }

    override fun output(
        options: QueryOptions,
        result: Digraph<Target?>,
        out: OutputStream?,
        aspectProvider: AspectResolver?,
        eventHandler: EventHandler?,
        hashFunction: HashFunction?,
        labelPrinter: LabelPrinter?
    ) {
        val sortLabels = options.getOrderOutput() == OrderOutput.FULL
        val graphWriter =
            GraphOutputWriter<Target?>(
                NODE_READER,
                options.getLineTerminator(),
                sortLabels,
                options.getGraphNodeStringLimit(),
                options.getGraphConditionalEdgesLimit(),
                options.getGraphFactored(),
                labelPrinter
            )
        graphWriter.write(result, ConditionalEdges(result), out)
    }

    companion object {
        private val NODE_READER: NodeReader<Target?> = object : NodeReader<Target?> {
            private val targetOrdering: TargetOrdering = TargetOrdering()

            override fun getLabel(node: Node<Target?>, labelPrinter: LabelPrinter): String {
                // Node payloads are Targets. Output node labels are target labels.
                return labelPrinter.toString(node.label.getLabel())
            }

            override fun comparator(): Comparator<Target?> {
                return targetOrdering
            }
        }
    }
}
