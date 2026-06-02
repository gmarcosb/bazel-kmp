// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.buildeventstream.ArtifactGroupNamer

/** Conversion of paths to URIs.  */
@javax.annotation.concurrent.ThreadSafe
class CountingArtifactGroupNamer : ArtifactGroupNamer {
    private val nodeNames: ConcurrentMap<NestedSet.Node?, LatchedGroupName?> =
        ConcurrentHashMap<NestedSet.Node?, LatchedGroupName?>()

    public override fun apply(id: NestedSet.Node?): NamedSetOfFilesId? {
        val name: LatchedGroupName? = nodeNames.get(id)
        if (name == null) {
            return null
        }
        return NamedSetOfFilesId.newBuilder().setId(name.getName()).build()
    }

    /**
     * If the [NestedSet] has no name already, return a new name for it. Return null otherwise.
     */
    fun maybeName(set: NestedSet<*>): LatchedGroupName? {
        val id: NestedSet.Node? = set.toNode()
        val existingGroupName: LatchedGroupName?
        val newGroupName: LatchedGroupName?
        // synchronized necessary only to ensure node names are chosen uniquely and compactly.
        // TODO(adgar): consider dropping compactness and unconditionally increment an AtomicLong to
        // pick unique node names.
        synchronized(this) {
            newGroupName = LatchedGroupName(nodeNames.size())
            existingGroupName = nodeNames.putIfAbsent(id, newGroupName)
        }
        if (existingGroupName != null) {
            existingGroupName.waitUntilWritten()
            return null
        }
        return newGroupName
    }

    /**
     * A name for a `NestedSet<?>` that the constructor must [.close] after the set is
     * written, allowing all other consumers to [.waitUntilWritten].
     */
    class LatchedGroupName(name: Int) : java.lang.AutoCloseable {
        private val latch: CountDownLatch
        private val name: Int

        init {
            this.latch = CountDownLatch(1)
            this.name = name
        }

        override fun close() {
            latch.countDown()
        }

        fun getName(): String {
            return java.lang.Integer.toString(name)
        }

        private fun waitUntilWritten() {
            com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly(latch)
        }
    }
}
