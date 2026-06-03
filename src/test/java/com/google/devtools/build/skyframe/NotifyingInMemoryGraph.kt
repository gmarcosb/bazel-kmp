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

import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.skyframe.NotifyingHelper
import com.google.devtools.build.skyframe.NotifyingHelper.NotifyingProcessableGraph
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build

/** [NotifyingHelper] that additionally implements the [InMemoryGraph] interface.  */
internal class NotifyingInMemoryGraph(
    delegate: InMemoryGraph?,
    graphListener: com.google.devtools.build.skyframe.NotifyingHelper.Listener?
) : NotifyingProcessableGraph(delegate, graphListener), InMemoryGraph {
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
        requestor: SkyKey?, reason: Reason?, keys: Iterable<out SkyKey?>
    ): NodeBatch? {
        try {
            return super.getBatch(requestor, reason, keys)
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    override fun getBatchMap(
        requestor: SkyKey?, reason: Reason?, keys: Iterable<out SkyKey?>
    ): MutableMap<SkyKey?, out NodeEntry?>? {
        try {
            return super.getBatchMap(requestor, reason, keys)
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    val values: MutableMap<SkyKey, SkyValue>
        get() {
            notifyingHelper.graphListener.accept( // Be gentle to tests that assume the key is not null
                /* key= */
                SkyKey { SkyFunctionName.FOR_TESTING },
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.GET_VALUES,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,  /* context= */
                null
            )
            return (delegate as InMemoryGraph).values
        }

    override fun remove(key: SkyKey?) {
        notifyingHelper.graphListener.accept(
            key,
            com.google.devtools.build.skyframe.NotifyingHelper.EventType.REMOVE,
            com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,  /* context= */
            null
        )
        delegate.remove(key)
    }

    public override fun valuesSize(): Int {
        return (delegate as InMemoryGraph).valuesSize()
    }

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
