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

import com.google.devtools.build.skyframe.AbstractInMemoryNodeEntry
import com.google.devtools.build.skyframe.GroupedDeps
import com.google.devtools.build.skyframe.GroupedDeps.Compressed
import com.google.devtools.build.skyframe.InitialBuildingState
import com.google.devtools.build.skyframe.NodeEntry.DependencyState
import com.google.devtools.build.skyframe.NodeEntry.DirtyType
import com.google.devtools.build.skyframe.NodeEntry.MarkedDirtyResult
import com.google.devtools.build.skyframe.NodeEntry.NodeValueAndRdepsToSignal
import com.google.devtools.build.skyframe.NonIncrementalInMemoryNodeEntry.NonIncrementalBuildingState
import com.google.devtools.build.skyframe.ReverseDepsUtility
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/**
 * An [InMemoryNodeEntry] that does not store edges (direct deps and reverse deps) once the
 * node is done. Used to save memory when the graph will not be reused for incremental builds.
 * 
 * 
 * Edges are stored as usual while the node is being built, but are discarded once the node is
 * done.
 * 
 * 
 * It is illegal to access edges once the node [.isDone].
 */
class NonIncrementalInMemoryNodeEntry
    (key: SkyKey?) : AbstractInMemoryNodeEntry<NonIncrementalBuildingState?>(key) {
    override fun keepsEdges(): Boolean {
        return false
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @kotlin.jvm.Synchronized
    override fun setValue(
        value: SkyValue?,
        graphVersion: com.google.devtools.build.skyframe.Version,
        maxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?
    ): com.google.common.collect.ImmutableSet<SkyKey?> {
        com.google.common.base.Preconditions.checkArgument(
            graphVersion == com.google.devtools.build.skyframe.Version.Companion.constant(),
            "Non-incremental evaluations must be at a constant version: %s",
            graphVersion
        )
        com.google.common.base.Preconditions.checkState(
            !hasUnsignaledDeps(),
            "Has unsignaled deps (this=%s, value=%s)",
            this,
            value
        )
        this.value = value
        val reverseDepsToSignal: com.google.common.collect.ImmutableSet<SkyKey?> =
            dirtyBuildingState.getReverseDeps(this)
        dirtyBuildingState = null
        return reverseDepsToSignal
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    override fun addReverseDepAndCheckIfDone(reverseDep: SkyKey?): DependencyState {
        // Fast path check before locking. If this node is already done, there is nothing to do since we
        // aren't storing reverse deps.
        if (isDone()) {
            return DependencyState.DONE
        }

        synchronized(this) {
            // Check again under a lock.
            if (isDone()) {
                return DependencyState.DONE
            }
            if (dirtyBuildingState == null) {
                dirtyBuildingState = NonIncrementalBuildingState()
            }
            if (reverseDep != null) {
                dirtyBuildingState.addReverseDep(reverseDep)
            }
            if (dirtyBuildingState.isEvaluating()) {
                return DependencyState.ALREADY_EVALUATING
            }
            dirtyBuildingState.startEvaluating()
            return DependencyState.NEEDS_SCHEDULING
        }
    }

    /**
     * {@inheritDoc}
     * 
     * 
     * A [NonIncrementalInMemoryNodeEntry] can only ever be at one of two versions: either
     * [Version.constant] when a value is available, or [Version.minimal] otherwise.
     * 
     * 
     * All non-incremental evaluations must use [Version.constant] as the graph version. This
     * is enforced in [.setValue].
     */
    override fun getVersion(): com.google.devtools.build.skyframe.Version? {
        return if (value != null) com.google.devtools.build.skyframe.Version.Companion.constant() else com.google.devtools.build.skyframe.Version.Companion.minimal()
    }

    @kotlin.jvm.Synchronized
    override fun getTemporaryDirectDeps(): GroupedDeps {
        return com.google.common.base.Preconditions.checkNotNull<NonIncrementalBuildingState?>(
            dirtyBuildingState,
            "Not evaluating: %s",
            this
        )
            .getTemporaryDirectDeps(this)
    }

    @kotlin.jvm.Synchronized
    override fun resetEvaluationFromScratch() {
        com.google.common.base.Preconditions.checkState(!hasUnsignaledDeps(), this)
        val rewoundValue: SkyValue? = dirtyBuildingState.getLastBuildValue()
        val newBuildingState =
            if (rewoundValue == null)
                NonIncrementalBuildingState()
            else
                RewoundNonIncrementalBuildingState(rewoundValue)
        newBuildingState.reverseDeps = dirtyBuildingState.reverseDeps
        newBuildingState.markRebuilding()
        newBuildingState.startEvaluating()
        dirtyBuildingState = newBuildingState
    }

    override fun getResetDirectDeps(): com.google.common.collect.ImmutableSet<SkyKey?> {
        return com.google.common.collect.ImmutableSet.of<SkyKey?>() // No accounting necessary since rdeps are not stored.
    }

    @kotlin.jvm.Synchronized
    override fun getNumTemporaryDirectDeps(): Int {
        if (dirtyBuildingState == null) {
            return 0
        }
        val directDeps: GroupedDeps? = dirtyBuildingState.directDeps
        return if (directDeps == null) 0 else directDeps.numElements()
    }

    @kotlin.jvm.Synchronized
    override fun markDirty(dirtyType: DirtyType?): MarkedDirtyResult? {
        com.google.common.base.Preconditions.checkArgument(
            dirtyType == DirtyType.REWIND,
            "Unexpected dirty type: %s",
            dirtyType
        )
        if (!isDone()) {
            return null // Tolerate concurrent requests to rewind.
        }
        if (getErrorInfo() != null) {
            return null // Rewinding errors is no-op.
        }
        dirtyBuildingState = RewoundNonIncrementalBuildingState(value)
        value = null
        return MarkedDirtyResult.Companion.forRewinding()
    }

    @kotlin.jvm.Synchronized
    override fun getInProgressReverseDeps(): MutableSet<SkyKey?>? {
        com.google.common.base.Preconditions.checkState(!isDone(), this)
        return if (dirtyBuildingState == null) com.google.common.collect.ImmutableSet.of<SkyKey?>() else dirtyBuildingState.getReverseDeps(
            this
        )
    }

    @kotlin.jvm.Synchronized
    override fun signalDep(
        childVersion: com.google.devtools.build.skyframe.Version?, childForDebugging: SkyKey?
    ): Boolean {
        com.google.common.base.Preconditions.checkState(
            !isDone(), "Value must not be done in signalDep %s child=%s", this, childForDebugging
        )
        com.google.common.base.Preconditions.checkNotNull<NonIncrementalBuildingState?>(
            dirtyBuildingState,
            "%s %s",
            this,
            childForDebugging
        )
            .signalDep(
                this,
                com.google.devtools.build.skyframe.Version.Companion.minimal(),
                childVersion,
                childForDebugging
            )
        return !hasUnsignaledDeps()
    }

    override fun removeReverseDep(reverseDep: SkyKey?) {
        com.google.common.base.Preconditions.checkNotNull<NonIncrementalBuildingState?>(
            dirtyBuildingState,
            "Not evaluating: %s",
            this
        ).removeReverseDep(reverseDep)
    }

    override fun getCompressedDirectDepsForDoneEntry(): @Compressed Any? {
        throw unsupported()
    }

    override fun getDirectDeps(): Iterable<SkyKey?>? {
        throw unsupported()
    }

    override fun getAllDirectDepsForIncompleteNode(): com.google.common.collect.ImmutableSet<SkyKey?>? {
        throw unsupported()
    }

    override fun hasAtLeastOneDep(): Boolean {
        throw unsupported()
    }

    override fun removeReverseDepsFromDoneEntryDueToDeletion(deletedKeys: MutableSet<SkyKey?>?) {
        throw unsupported()
    }

    override fun getReverseDepsForDoneEntry(): MutableCollection<SkyKey?>? {
        throw unsupported()
    }

    override fun getAllReverseDepsForNodeBeingDeleted(): MutableCollection<SkyKey?>? {
        throw unsupported()
    }

    override fun checkIfDoneForDirtyReverseDep(reverseDep: SkyKey?): DependencyState? {
        throw unsupported()
    }

    override fun markClean(): NodeValueAndRdepsToSignal? {
        throw unsupported()
    }

    override fun forceRebuild() {
        throw unsupported()
    }

    private fun unsupported(): java.lang.UnsupportedOperationException {
        return java.lang.UnsupportedOperationException("Not keeping edges: " + this)
    }

    /**
     * Specialized [DirtyBuildingState] for a non-incremental node.
     * 
     * 
     * The [.directDeps] and [.reverseDeps] fields are stored in this class instead of
     * in [NonIncrementalInMemoryNodeEntry] since they are not needed after the node is done.
     * This way we don't pay the memory cost of the fields for a done node.
     */
    internal open class NonIncrementalBuildingState private constructor() : InitialBuildingState() {
        private var directDeps: GroupedDeps? = null
        private var reverseDeps: MutableList<SkyKey?>? = null

        fun getTemporaryDirectDeps(entry: NonIncrementalInMemoryNodeEntry): GroupedDeps {
            if (directDeps == null) {
                directDeps = entry.newGroupedDeps()
            }
            return directDeps
        }

        fun addReverseDep(reverseDep: SkyKey?) {
            if (reverseDeps == null) {
                reverseDeps = java.util.ArrayList<SkyKey?>()
            }
            reverseDeps!!.add(reverseDep)
        }

        fun removeReverseDep(reverseDep: SkyKey?) {
            // Reverse dep removal on a non-incremental node is rare (only for cycles), so we can live
            // with inefficiently calling remove on an ArrayList.
            com.google.common.base.Preconditions.checkState(
                reverseDeps!!.remove(reverseDep),
                "Reverse dep not present: %s",
                reverseDep
            )
        }

        fun getReverseDeps(entry: NonIncrementalInMemoryNodeEntry?): com.google.common.collect.ImmutableSet<SkyKey?> {
            if (reverseDeps == null) {
                return com.google.common.collect.ImmutableSet.of<SkyKey?>()
            }
            val result: com.google.common.collect.ImmutableSet<SkyKey?> =
                com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(reverseDeps)
            ReverseDepsUtility.checkForDuplicates(result, reverseDeps, entry)
            return result
        }

        override fun getStringHelper(): com.google.common.base.MoreObjects.ToStringHelper {
            return super.getStringHelper().add("directDeps", directDeps).add("reverseDeps", reverseDeps)
        }
    }

    /**
     * State for a non-incremental node that was previously [done][.isDone] but was
     * [rewound][com.google.devtools.build.skyframe.NodeEntry.DirtyType.REWIND]. Stores the
     * previously built value for the sole purpose of servicing [.toValue].
     */
    private class RewoundNonIncrementalBuildingState
        (rewoundValue: SkyValue?) : NonIncrementalBuildingState() {
        private val rewoundValue: SkyValue?

        init {
            this.rewoundValue = rewoundValue
        }

        override fun getLastBuildValue(): SkyValue? {
            return rewoundValue
        }

        override fun getStringHelper(): com.google.common.base.MoreObjects.ToStringHelper {
            return super.getStringHelper().add("rewoundValue", rewoundValue)
        }
    }
}
