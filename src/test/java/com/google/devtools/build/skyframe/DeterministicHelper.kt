// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache.put
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.skyframe.DeterministicInMemoryGraph
import com.google.devtools.build.skyframe.NotifyingHelper
import com.google.devtools.build.skyframe.NotifyingHelper.NotifyingNodeEntry
import com.google.devtools.build.skyframe.NotifyingHelper.NotifyingProcessableGraph
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.addAll
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import java.util.TreeMap
import java.util.TreeSet

/**
 * [NotifyingHelper] that returns reverse deps, temporary direct deps, and the results of
 * batch requests ordered alphabetically by sky key string representation.
 */
class DeterministicHelper private constructor(listener: com.google.devtools.build.skyframe.NotifyingHelper.Listener?) :
    NotifyingHelper(listener) {
    override fun wrapEntry(key: SkyKey?, entry: NodeEntry?): DeterministicNodeEntry? {
        return if (entry == null) null else DeterministicNodeEntry(key, entry)
    }

    internal open class DeterministicProcessableGraph(
        delegate: ProcessableGraph?,
        graphListener: com.google.devtools.build.skyframe.NotifyingHelper.Listener?
    ) : NotifyingProcessableGraph(delegate, DeterministicHelper(graphListener)) {
        constructor(delegate: ProcessableGraph?) : this(
            delegate,
            com.google.devtools.build.skyframe.NotifyingHelper.Listener.Companion.NULL_LISTENER
        )

        @Throws(java.lang.InterruptedException::class)
        override fun getBatch(
            requestor: SkyKey?, reason: Reason?, keys: Iterable<out SkyKey?>
        ): NodeBatch? {
            val batch: NodeBatch = super.getBatch(requestor, reason, keys)
            val result: TreeMap<SkyKey?, NodeEntry?> = TreeMap<SkyKey?, NodeEntry?>(ALPHABETICAL_SKYKEY_COMPARATOR)
            for (key in keys) {
                val entry: NodeEntry? = batch.get(key)
                if (entry != null) {
                    result.put(key, entry)
                }
            }
            return result::get
        }

        @Throws(java.lang.InterruptedException::class)
        override fun getBatchMap(
            requestor: SkyKey?, reason: Reason?, keys: Iterable<out SkyKey?>
        ): MutableMap<SkyKey?, out NodeEntry?> {
            return makeDeterministic(super.getBatchMap(requestor, reason, keys))
        }
    }

    /**
     * This class uses TreeSet to store reverse dependencies of NodeEntry. As a result all values are
     * lexicographically sorted.
     */
    private inner class DeterministicNodeEntry(myKey: SkyKey?, delegate: NodeEntry?) :
        NotifyingNodeEntry(myKey, delegate) {
        @get:Throws(java.lang.InterruptedException::class)
        @get:kotlin.jvm.Synchronized
        val reverseDepsForDoneEntry: MutableCollection<SkyKey>
            get() {
                val result: TreeSet<SkyKey?> =
                    TreeSet<SkyKey?>(ALPHABETICAL_SKYKEY_COMPARATOR)
                com.google.common.collect.Iterables.addAll<T?>(result, super.getReverseDepsForDoneEntry())
                return result
            }

        @get:kotlin.jvm.Synchronized
        val inProgressReverseDeps: MutableSet<SkyKey>
            get() {
                val result: TreeSet<SkyKey?> =
                    TreeSet<SkyKey?>(ALPHABETICAL_SKYKEY_COMPARATOR)
                result.addAll(super.getInProgressReverseDeps())
                return result
            }

        @Throws(java.lang.InterruptedException::class)
        override fun setValue(
            value: SkyValue?, graphVersion: Version?, maxTransitiveSourceVersion: Version?
        ): MutableSet<SkyKey?> {
            val result: TreeSet<SkyKey?> = TreeSet<SkyKey?>(ALPHABETICAL_SKYKEY_COMPARATOR)
            result.addAll(super.setValue(value, graphVersion, maxTransitiveSourceVersion))
            return result
        }

        @Throws(java.lang.InterruptedException::class)
        override fun markClean(): NodeValueAndRdepsToSignal {
            val result: TreeSet<SkyKey?> = TreeSet<SkyKey?>(ALPHABETICAL_SKYKEY_COMPARATOR)
            val nodeValueAndRdepsToSignal: NodeValueAndRdepsToSignal = super.markClean()
            result.addAll(nodeValueAndRdepsToSignal.getRdepsToSignal())
            return NodeValueAndRdepsToSignal(nodeValueAndRdepsToSignal.getValue(), result)
        }
    }

    companion object {
        val MAKE_DETERMINISTIC: MemoizingEvaluator.GraphTransformerForTesting = makeTransformer(
            com.google.devtools.build.skyframe.NotifyingHelper.Listener.Companion.NULL_LISTENER,  /*deterministic=*/
            true
        )

        fun makeTransformer(
            listener: com.google.devtools.build.skyframe.NotifyingHelper.Listener?, deterministic: Boolean
        ): MemoizingEvaluator.GraphTransformerForTesting {
            if (deterministic) {
                return object : GraphTransformerForTesting() {
                    public override fun transform(graph: InMemoryGraph?): InMemoryGraph? {
                        return DeterministicInMemoryGraph(graph, listener)
                    }

                    public override fun transform(graph: ProcessableGraph?): ProcessableGraph? {
                        return DeterministicProcessableGraph(graph, listener)
                    }
                }
            } else {
                return NotifyingHelper.Companion.makeNotifyingTransformer(listener)
            }
        }

        /** Compare using SkyKey argument first, so that tests can easily order keys.  */
        private val ALPHABETICAL_SKYKEY_COMPARATOR: java.util.Comparator<SkyKey?>? =
            java.util.Comparator.comparing<SkyKey?, String?>(java.util.function.Function { key: SkyKey? ->
                key.argument().toString()
            })
                .thenComparing<Any?>(java.util.function.Function { key: SkyKey? -> key.functionName().toString() })

        private fun makeDeterministic(
            map: MutableMap<SkyKey?, out NodeEntry?>
        ): MutableMap<SkyKey?, out NodeEntry?> {
            val result: MutableMap<SkyKey?, NodeEntry?> = TreeMap<SkyKey?, NodeEntry?>(ALPHABETICAL_SKYKEY_COMPARATOR)
            result.putAll(map)
            com.google.common.base.Preconditions.checkState(
                map.size == result.size,
                "Different sky keys with identical toString results! Before=%s After=%s",
                result,
                map
            )
            return result
        }
    }
}
