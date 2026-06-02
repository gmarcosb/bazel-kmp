// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.skyframe.NodeEntry
import com.google.devtools.build.skyframe.NodeEntry.DependencyState
import com.google.devtools.build.skyframe.NodeEntry.DirtyType
import com.google.devtools.build.skyframe.NodeEntry.MarkedDirtyResult
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/**
 * A [NodeEntry] that is stored in memory.
 * 
 * 
 * Supports several [NodeEntry] methods without throwing [InterruptedException].
 */
interface InMemoryNodeEntry : NodeEntry {
    /** Returns the [SkyKey] associated with this node.  */
    @kotlin.jvm.JvmField
    val key: SkyKey?

    /** Whether this node stores edges (deps and rdeps).  */
    fun keepsEdges(): Boolean

    /**
     * Returns the compressed [GroupedDeps] of direct deps. Can only be called if this node
     * [.isDone] and [.keepsEdges].
     */
    @kotlin.jvm.JvmField
    val compressedDirectDepsForDoneEntry: @`<error>` Any?

    @kotlin.jvm.JvmField
    val value: SkyValue?

    val valueMaybeWithMetadata: SkyValue?

    override fun toValue(): SkyValue?

    @kotlin.jvm.JvmField
    val errorInfo: com.google.devtools.build.skyframe.ErrorInfo?

    @kotlin.jvm.JvmField
    val directDeps: Iterable<SkyKey>?

    override fun hasAtLeastOneDep(): Boolean

    override fun removeReverseDep(reverseDep: SkyKey?)

    @kotlin.jvm.JvmField
    val reverseDepsForDoneEntry: MutableCollection<SkyKey>?

    override fun addReverseDepAndCheckIfDone(reverseDep: SkyKey?): DependencyState?

    override fun markDirty(dirtyType: DirtyType?): MarkedDirtyResult?
}
