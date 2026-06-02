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
import kotlin.Int
import kotlin.String
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet

/**
 * An output formatter that prints the labels in maximum rank order, preceded
 * by their rank number.  "Roots" have rank 0, all other nodes have a rank
 * which is one greater than the maximum rank of each of their predecessors.
 * All nodes in a cycle are considered of equal rank.  MAXRANK shows the
 * highest rank for a given node, i.e. the length of the longest non-cyclic
 * path from a zero-rank node to it.
 * 
 * 
 * If the result came from a `deps(x)` query, then the MAXRANKs
 * correspond to the longest path from x to each of its prerequisites.
 */
internal class MaxrankOutputFormatter : OutputFormatter() {
    override fun getName(): String {
        return "maxrank"
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
        // In order to handle cycles correctly, we need work on the strong
        // component graph, as cycles should be treated a "clump" of nodes all on
        // the same rank. Graphs may contain cycles because there are errors in BUILD files.

        // Dynamic programming algorithm:
        // rank(x) = max(rank(p)) + 1 foreach p in preds(x)
        // TODO(bazel-team): Move to Digraph.

        class DP {
            val ranks: MutableMap<Node<MutableSet<Node<Target?>?>?>?, Int?> =
                HashMap<Node<MutableSet<Node<Target?>?>?>?, Int?>()

            fun rank(node: Node<MutableSet<Node<Target?>>?>): Int {
                var rank = ranks.get(node)
                if (rank == null) {
                    var maxPredRank = -1
                    for (p in node.predecessors!!) {
                        maxPredRank = max(maxPredRank, rank(p!!))
                    }
                    rank = maxPredRank + 1
                    ranks.put(node, rank)
                }
                return rank
            }
        }

        val dp = DP()

        // Now sort by rank...
        val output: MutableList<RankAndLabel> = ArrayList<RankAndLabel>()
        for (x in result.strongComponentGraph.getNodes()) {
            val rank = dp.rank(x)
            for (y in x.label) {
                output.add(RankAndLabel(rank, y.label.getLabel()))
            }
        }
        if (options.getOrderOutput() == OrderOutput.FULL) {
            // Use the natural order for RankAndLabels, which breaks ties alphabetically.
            Collections.sort<RankAndLabel?>(output)
        } else {
            Collections.sort<RankAndLabel?>(
                output,
                Comparator.comparingInt<RankAndLabel?>(ToIntFunction { obj: RankAndLabel? -> obj!!.getRank() })
            )
        }
        val lineTerm = options.getLineTerminator()
        val printStream: PrintStream = PrintStream(out)
        for (item in output) {
            printStream.print(item.toString(labelPrinter) + lineTerm)
        }
        flushAndCheckError(printStream)
    }

    companion object {
        @Throws(IOException::class)
        private fun flushAndCheckError(printStream: PrintStream) {
            if (printStream.checkError()) {
                throw IOException("PrintStream encountered an error")
            }
        }
    }
}