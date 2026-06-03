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

import com.google.devtools.build.skyframe.DeterministicHelper.DeterministicProcessableGraph
import com.google.devtools.build.skyframe.NotifyingHelper

/**
 * [DeterministicHelper.DeterministicProcessableGraph] that implements the [ ] interface. Sadly, cannot be a [NotifyingInMemoryGraph] due to Java's
 * forbidding multiple inheritance.
 */
internal class DeterministicInMemoryGraph(
    delegate: InMemoryGraph?,
    graphListener: com.google.devtools.build.skyframe.NotifyingHelper.Listener?
) : DeterministicProcessableGraph(delegate, graphListener), InMemoryGraph {
    override fun createIfAbsentBatch(
        requestor: SkyKey?, reason: Reason?, keys: Iterable<out SkyKey?>
    ): NodeBatch? {
        try {
            return super.createIfAbsentBatch(requestor, reason, keys)
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    override fun get(requestor: SkyKey?, reason: Reason?, key: SkyKey?): NodeEntry? {
        try {
            return super.get(requestor, reason, key)
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    override fun getBatch(
        requestor: SkyKey?, reason: Reason?, keys: Iterable<out SkyKey?>?
    ): NodeBatch? {
        try {
            return super.getBatch(requestor, reason, keys)
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    override fun getBatchMap(
        requestor: SkyKey?, reason: Reason?, keys: Iterable<out SkyKey?>?
    ): MutableMap<SkyKey?, out NodeEntry?>? {
        try {
            return super.getBatchMap(requestor, reason, keys)
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    val values: MutableMap<SkyKey, SkyValue>
        get() = (delegate as InMemoryGraph).values

    val doneValues: MutableMap<SkyKey, SkyValue>
        get() = (delegate as InMemoryGraph).doneValues

    val allNodeEntries: MutableCollection<InMemoryNodeEntry>
        get() = (delegate as InMemoryGraph).allNodeEntries

    public override fun parallelForEach(consumer: java.util.function.Consumer<InMemoryNodeEntry?>?) {
        (delegate as InMemoryGraph).parallelForEach(consumer)
    }

    public override fun cleanupInterningPools() {
        (delegate as InMemoryGraph).cleanupInterningPools()
    }

    public override fun removeIfDone(key: SkyKey?) {
        (delegate as InMemoryGraph).removeIfDone(key)
    }

    public override fun getIfPresent(key: SkyKey?): InMemoryNodeEntry? {
        return (delegate as InMemoryGraph).getIfPresent(key)
    }

    public override fun shrinkNodeMap() {
        (delegate as InMemoryGraph).shrinkNodeMap()
    }
}
