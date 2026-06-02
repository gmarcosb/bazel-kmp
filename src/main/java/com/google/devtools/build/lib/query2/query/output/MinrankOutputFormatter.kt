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
import com.google.devtools.build.lib.cmdline.Label
import com.google.devtools.build.lib.graph.Node
import java.io.OutputStream
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableSet

/**
 * An output formatter that prints the labels in minimum rank order, preceded by
 * their rank number.  "Roots" have rank 0, their direct prerequisites have
 * rank 1, etc.  All nodes in a cycle are considered of equal rank.  MINRANK
 * shows the lowest rank for a given node, i.e. the length of the shortest
 * path from a zero-rank node to it.
 * 
 * 
 * If the result came from a `deps(x)` query, then the MINRANKs
 * correspond to the shortest path from x to each of its prerequisites.
 */
internal class MinrankOutputFormatter : OutputFormatter() {
    override fun getName(): String {
        return "minrank"
    }

    @Throws(IOException::class)
    override fun output(
        options: QueryOptions,
        result: Digraph<Target?>,
        out: OutputStream,
        aspectResolver: AspectResolver?,
        eventHandler: EventHandler?,
        hashFunction: HashFunction?,
        labelPrinter: LabelPrinter
    ) {
        val printStream: PrintStream = PrintStream(out)

        // getRoots() isn't defined for cyclic graphs, so in order to handle
        // cycles correctly, we need work on the strong component graph, as
        // cycles should be treated a "clump" of nodes all on the same rank.
        // Graphs may contain cycles because there are errors in BUILD files.
        val outputToOrder: MutableList<RankAndLabel>? =
            if (options.getOrderOutput() == OrderOutput.FULL) ArrayList<RankAndLabel>() else null
        val scGraph: Digraph<MutableSet<Node<Target?>?>?> = result.strongComponentGraph
        var rankNodes: MutableSet<Node<MutableSet<Node<Target?>?>?>> = scGraph.roots
        val seen: MutableSet<Node<MutableSet<Node<Target?>?>?>?> = HashSet<Node<MutableSet<Node<Target?>?>?>?>()
        seen.addAll(rankNodes)
        val lineTerm = options.getLineTerminator()
        var rank = 0
        while (!rankNodes.isEmpty()) {
            // Print out this rank:
            for (xScc in rankNodes) {
                for (x in xScc.label!!) {
                    outputToStreamOrSave(
                        rank, x!!.label.getLabel(), printStream, outputToOrder, lineTerm, labelPrinter
                    )
                }
            }

            // Find the next rank:
            val nextRankNodes: MutableSet<Node<MutableSet<Node<Target?>?>?>> =
                LinkedHashSet<Node<MutableSet<Node<Target?>?>?>>()
            for (x in rankNodes) {
                for (y in x.successors!!) {
                    if (seen.add(y)) {
                        nextRankNodes.add(y!!)
                    }
                }
            }
            rankNodes = nextRankNodes
            rank++
        }
        if (outputToOrder != null) {
            Collections.sort<RankAndLabel?>(outputToOrder)
            for (item in outputToOrder) {
                printStream.print(item.toString(labelPrinter) + lineTerm)
            }
        }

        flushAndCheckError(printStream)
    }

    companion object {
        private fun outputToStreamOrSave(
            rank: Int,
            label: Label?,
            out: PrintStream,
            toSave: MutableList<RankAndLabel>?,
            lineTerminator: String?,
            labelPrinter: LabelPrinter
        ) {
            if (toSave != null) {
                toSave.add(RankAndLabel(rank, label))
            } else {
                out.print(rank.toString() + " " + labelPrinter.toString(label) + lineTerminator)
            }
        }

        @Throws(IOException::class)
        private fun flushAndCheckError(printStream: PrintStream) {
            if (printStream.checkError()) {
                throw IOException("PrintStream encountered an error")
            }
        }
    }
}