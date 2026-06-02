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

import com.google.devtools.build.skyframe.AbstractInMemoryNodeEntry
import com.google.devtools.build.skyframe.GroupedDeps
import com.google.devtools.build.skyframe.NodeEntry.DirtyType
import com.google.devtools.build.skyframe.NodeEntry.LifecycleState
import com.google.devtools.build.skyframe.NodeVersion
import com.google.devtools.build.skyframe.NotComparableSkyValue
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/**
 * State for a node that either has not been built yet or has been dirtied.
 * 
 * 
 * If the node has previously been built and the state tracks the previous value and dependencies
 * for purposes of pruning, [.isIncremental] returns true. Deps are checked to see if
 * re-evaluation is needed, and the node will either marked clean or re-evaluated.
 * 
 * 
 * This class does not attempt to synchronize operations. It is assumed that the calling [ ] performs the appropriate synchronization when necessary.
 * 
 * 
 * This class is public only for the benefit of alternative graph implementations outside of the
 * package.
 */
abstract class DirtyBuildingState protected constructor(dirtyType: DirtyType) {
    /**
     * The state of a dirty node.
     * 
     * 
     * Initialized to either [LifecycleState.CHECK_DEPENDENCIES] or [ ][LifecycleState.NEEDS_REBUILDING] depending on the [DirtyType] (see [ ][.initialState]). May take on any [LifecycleState] value except [ ][LifecycleState.NOT_YET_EVALUATING] and [LifecycleState.DONE].
     */
    private var state: LifecycleState?

    /**
     * The number of dependencies that are known to be done in a [NodeEntry].
     * 
     * 
     * There is a potential check-then-act race here during evaluation, so we need to make sure
     * that when this is increased, we always check if the new value is equal to the number of
     * required dependencies, and if so, we must re-schedule the node for evaluation.
     * 
     * 
     * There are two potential pitfalls here: 1) If multiple dependencies signal this node in close
     * succession, this node should be scheduled exactly once. 2) If a thread is still working on this
     * node, it should not be scheduled.
     * 
     * 
     * To solve the first problem, the [NodeEntry.signalDep] method also returns if the node
     * needs to be re-scheduled, and ensures that only one thread gets a true return value.
     * 
     * 
     * The second problem is solved by first adding the newly discovered deps to a node's [ ][IncrementalInMemoryNodeEntry.directDeps], and then looping through the direct deps and
     * registering this node as a reverse dependency. This ensures that the signaledDeps counter can
     * only reach [GroupedDeps.numElements] on the very last iteration of the loop, i.e., the
     * thread is not working on the node anymore. Note that this requires that there is no code after
     * the loop in [ParallelEvaluator.Evaluate.run].
     */
    private var signaledDeps = NOT_EVALUATING_SENTINEL

    /**
     * The number of external dependencies (in contrast to the number of internal dependencies which
     * are tracked in NodeEntry). We never keep information about external dependencies across
     * Skyframe calls.
     */
    // We do not strictly require a counter here; all external deps from one SkyFunction evaluation
    // pass are registered as a single logical dependency, and the SkyFunction is only re-evaluated if
    // all of them complete. Therefore, we only need a single bit to track this fact. If the mere
    // existence of this field turns out to be a significant memory burden, we could change the
    // implementation by moving to a single-bit approach, and then store that bit as part of the
    // state field, e.g., by adding a REBUILDING_WAITING_FOR_EXTERNAL_DEPS enum value, as this can
    // only happen during evaluation.
    private var externalDeps = 0

    @get:Throws(java.lang.InterruptedException::class)
    abstract val lastBuildDirectDeps: GroupedDeps?

    /**
     * The number of groups of the dependencies requested last time when the node was built, or `0` if on its initial build.
     * 
     * 
     * Getting the number of last-built dependencies should not throw [InterruptedException].
     */
    protected abstract val numOfGroupsInLastBuildDirectDeps: Int

    @get:Throws(java.lang.InterruptedException::class)
    abstract val lastBuildValue: SkyValue?

    /**
     * Group of children to be checked next in the process of determining if this entry needs to be
     * re-evaluated. Used by [DirtyBuildingState.getNextDirtyDirectDeps] and [.signalDep].
     */
    var dirtyDirectDepIndex: Int = 0

    init {
        state = initialState(dirtyType)
    }

    /** Returns true if this state has information about a previously built version.  */
    abstract val isIncremental: Boolean

    fun markChanged() {
        com.google.common.base.Preconditions.checkState(state == LifecycleState.CHECK_DEPENDENCIES, this)
        com.google.common.base.Preconditions.checkState(dirtyDirectDepIndex == 0, "Unexpected evaluation: %s", this)
        state = LifecycleState.NEEDS_REBUILDING
    }

    fun forceRebuild(numTemporaryDirectDeps: Int) {
        com.google.common.base.Preconditions.checkState(state == LifecycleState.CHECK_DEPENDENCIES, this)
        com.google.common.base.Preconditions.checkState(numTemporaryDirectDeps + externalDeps == signaledDeps, this)
        com.google.common.base.Preconditions.checkState(
            this.numOfGroupsInLastBuildDirectDeps == dirtyDirectDepIndex,
            this
        )
        state = LifecycleState.REBUILDING
    }

    val isEvaluating: Boolean
        get() = signaledDeps > NOT_EVALUATING_SENTINEL

    val isChanged: Boolean
        get() = state == LifecycleState.NEEDS_REBUILDING || state == LifecycleState.REBUILDING

    private fun checkFinishedBuildingWhenAboutToSetValue() {
        com.google.common.base.Preconditions.checkState(
            state == LifecycleState.VERIFIED_CLEAN || state == LifecycleState.REBUILDING,
            "not done building %s",
            this
        )
    }

    /**
     * Signals that a child is done.
     * 
     * 
     * If this node is not yet known to need rebuilding, sets [.state] to [ ][LifecycleState.NEEDS_REBUILDING] if the child has changed, and [ ][LifecycleState.VERIFIED_CLEAN] if the child has not changed and this was the last child to be
     * checked (as determined by `isReady` and comparing [.dirtyDirectDepIndex] and [ ][DirtyBuildingState.getNumOfGroupsInLastBuildDirectDeps].
     */
    fun signalDep(
        entry: AbstractInMemoryNodeEntry<*>,
        version: NodeVersion,
        childVersion: com.google.devtools.build.skyframe.Version,
        childForDebugging: SkyKey?
    ) {
        com.google.common.base.Preconditions.checkState(this.isEvaluating, "%s %s", entry, childForDebugging)
        signaledDeps++
        if (this.isChanged) {
            return
        }

        // childVersion > version.lastEvaluated() means the child has changed since the last evaluation.
        val childChanged: Boolean = !childVersion.atMost(version.lastEvaluated())
        if (childChanged) {
            state = LifecycleState.NEEDS_REBUILDING
        } else if (state == LifecycleState.CHECK_DEPENDENCIES && isReady(entry.getNumTemporaryDirectDeps())
            && this.numOfGroupsInLastBuildDirectDeps == dirtyDirectDepIndex
        ) {
            // No other dep already marked this as NEEDS_REBUILDING, no deps outstanding, and this was the
            // last block of deps to be checked.
            state = LifecycleState.VERIFIED_CLEAN
        }
    }

    fun addExternalDep() {
        com.google.common.base.Preconditions.checkState(this.isEvaluating)
        externalDeps++
    }

    /**
     * Returns true if `newValue`.equals the value from the last time this node was built.
     * Should only be used by [NodeEntry.setValue].
     * 
     * 
     * Changes in direct deps do *not* force this to return false. Only the value is
     * considered.
     */
    @Throws(java.lang.InterruptedException::class)
    fun unchangedFromLastBuild(newValue: SkyValue?): Boolean {
        checkFinishedBuildingWhenAboutToSetValue()
        return (newValue !is NotComparableSkyValue) && this.lastBuildValue != null && this.lastBuildValue == newValue
    }

    /**
     * Returns true if the deps requested during this evaluation (`directDeps`) are exactly
     * those requested the last time this node was built, in the same order.
     */
    @Throws(java.lang.InterruptedException::class)
    fun depsUnchangedFromLastBuild(directDeps: GroupedDeps?): Boolean {
        checkFinishedBuildingWhenAboutToSetValue()
        return this.lastBuildDirectDeps == directDeps
    }

    fun noDepsLastBuild(): Boolean {
        return this.numOfGroupsInLastBuildDirectDeps == 0
    }

    val lifecycleState: LifecycleState?
        /** Returns the [LifecycleState] as documented by [NodeEntry.getLifecycleState].  */
        get() = state

    @get:Throws(java.lang.InterruptedException::class)
    val nextDirtyDirectDeps: MutableList<SkyKey>?
        /**
         * Gets the next children to be re-evaluated to see if this dirty node needs to be re-evaluated.
         * 
         * 
         * See [NodeEntry.getNextDirtyDirectDeps].
         */
        get() {
            com.google.common.base.Preconditions.checkState(state == LifecycleState.CHECK_DEPENDENCIES, this)
            com.google.common.base.Preconditions.checkState(
                dirtyDirectDepIndex < this.numOfGroupsInLastBuildDirectDeps,
                this
            )
            return this.lastBuildDirectDeps.getDepGroup(dirtyDirectDepIndex++)
        }

    /**
     * Returns the remaining direct deps that have not been checked. If `preservePosition` is
     * true, this method is non-mutating. If `preservePosition` is false, the caller must
     * process the returned set, and so subsequent calls to this method will return the empty set.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getAllRemainingDirtyDirectDeps(preservePosition: Boolean): com.google.common.collect.ImmutableSet<SkyKey?> {
        if (this.lastBuildDirectDeps == null) {
            return com.google.common.collect.ImmutableSet.of<SkyKey?>()
        }
        val result: com.google.common.collect.ImmutableSet.Builder<SkyKey?> =
            com.google.common.collect.ImmutableSet.builder<SkyKey?>()
        for (ind in dirtyDirectDepIndex..<this.numOfGroupsInLastBuildDirectDeps) {
            result.addAll(this.lastBuildDirectDeps.getDepGroup(ind))
        }
        if (!preservePosition) {
            dirtyDirectDepIndex = this.numOfGroupsInLastBuildDirectDeps
        }
        return result.build()
    }

    open val resetDirectDeps: com.google.common.collect.ImmutableSet<SkyKey?>
        get() = com.google.common.collect.ImmutableSet.of<SkyKey?>()

    fun markRebuilding() {
        com.google.common.base.Preconditions.checkState(state == LifecycleState.NEEDS_REBUILDING, this)
        state = LifecycleState.REBUILDING
    }

    fun startEvaluating() {
        com.google.common.base.Preconditions.checkState(!this.isEvaluating, this)
        signaledDeps = 0
    }

    /** Returns whether all known children of this node have signaled that they are done.  */
    fun isReady(numDirectDeps: Int): Boolean {
        // Avoids calling Preconditions.checkState because it showed up in garbage profiles due to
        // boxing of the int format args.
        check(signaledDeps <= numDirectDeps + externalDeps) {
            java.lang.String.format(
                "%s %s %s",
                numDirectDeps,
                externalDeps,
                this
            )
        }
        return signaledDeps == numDirectDeps + externalDeps
    }

    protected open val stringHelper: com.google.common.base.MoreObjects.ToStringHelper?
        get() = com.google.common.base.MoreObjects.toStringHelper(this)
            .add("state", state)
            .add("signaledDeps", signaledDeps)
            .add("externalDeps", externalDeps)
            .add("dirtyDirectDepIndex", dirtyDirectDepIndex)

    override fun toString(): String {
        return this.stringHelper.toString()
    }

    companion object {
        private val NOT_EVALUATING_SENTINEL = -1

        private fun initialState(dirtyType: DirtyType): LifecycleState {
            when (dirtyType) {
                DirtyType.DIRTY -> return LifecycleState.CHECK_DEPENDENCIES
                DirtyType.CHANGE, DirtyType.REWIND -> return LifecycleState.NEEDS_REBUILDING
            }
            throw java.lang.AssertionError(dirtyType)
        }
    }
}
