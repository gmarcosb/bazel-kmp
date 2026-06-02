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

import com.google.common.collect.Iterables
import com.google.common.hash.HashFunction
import com.google.devtools.build.lib.events.EventHandler
import com.google.devtools.build.lib.graph.Node
import java.io.OutputStream

internal abstract class AbstractUnorderedFormatter : OutputFormatter(), StreamedFormatter {
    override fun setOptions(
        options: CommonQueryOptions?, aspectResolver: AspectResolver?, hashFunction: HashFunction?
    ) {
    }

    /** Optionally sets a handler for reporting status output / errors.  */
    override fun setEventHandler(eventHandler: EventHandler?) {}

    @Throws(IOException::class, InterruptedException::class)
    override fun output(
        options: QueryOptions,
        result: Digraph<Target?>,
        out: OutputStream?,
        aspectResolver: AspectResolver?,
        eventHandler: EventHandler?,
        hashFunction: HashFunction?,
        labelPrinter: LabelPrinter?
    ) {
        setOptions(options, aspectResolver, hashFunction)
        setEventHandler(eventHandler)
        OutputFormatterCallback.Companion.processAllTargets<Target?>(
            createPostFactoStreamCallback(out, options, labelPrinter),
            getOrderedTargets(result, options)
        )
    }

    protected fun getOrderedTargets(result: Digraph<Target?>, options: QueryOptions): Iterable<Target?> {
        if (options.getOrderOutput() == OrderOutput.FULL) {
            // Get targets in total order, the difference here from topological ordering is the sorting of
            // nodes before post-order visitation (which ensures determinism at a time cost).
            return Iterables.transform<Node<Target?>?, Target?>(
                result.getTopologicalOrder(TargetOrdering()), Node::label
            )
        } else if (options.getOrderOutput() == OrderOutput.DEPS) {
            // Get targets in topological order.
            return Iterables.transform<Node<Target?>?, Target?>(result.topologicalOrder, Node::label)
        }
        return result.labels
    }

    companion object {
        fun getKind(options: QueryOptions, target: Target): String {
            if (options.getDisplayFullKind() && target is Rule) {
                val ruleClassId: RuleClassId = target.getRuleClassObject().getRuleClassId()
                return ruleClassId.key() + Rule.targetKindSuffix()
            }

            return target.getTargetKind()
        }
    }
}
