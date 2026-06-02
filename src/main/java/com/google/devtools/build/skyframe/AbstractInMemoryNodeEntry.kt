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

import com.google.devtools.build.skyframe.DirtyBuildingState
import com.google.devtools.build.skyframe.GroupedDeps
import com.google.devtools.build.skyframe.GroupedDeps.WithHashSet
import com.google.devtools.build.skyframe.InMemoryNodeEntry
import com.google.devtools.build.skyframe.NodeEntry.LifecycleState
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.ValueWithMetadata

/**
 * Partial implementation of [InMemoryNodeEntry] containing behavior common to both [ ] and [NonIncrementalInMemoryNodeEntry]. All operations on this
 * class are thread-safe.
 * 
 * 
 * Care was taken to provide certain compound operations to avoid certain check-then-act races.
 * That means this class is somewhat closely tied to the exact Evaluator implementation.
 * 
 * 
 * Consider the example with two threads working on two nodes, where one depends on the other,
 * say b depends on a. If a completes first, it's done. If it completes second, it needs to signal
 * b, and potentially re-schedule it. If b completes first, it must exit, because it will be
 * signaled (and re-scheduled) by a. If it completes second, it must signal (and re-schedule)
 * itself. However, if the Evaluator supported re-entrancy for a node, then this wouldn't have to be
 * so strict, because duplicate scheduling would be less problematic.
 * 
 * 
 * During its life, a node can go through states as follows:
 * 
 * 
 *  1. Non-existent
 *  1. Just created or marked as affected ([.isDone] is false; [.isDirty] is false)
 *  1. Evaluating ([.isDone] is false; [.isDirty] is true)
 *  1. Done ([.isDone] is true; [.isDirty] is false)
 * 
 * 
 * 
 * The "just created" state is there to allow the [ProcessableGraph.createIfAbsentBatch]
 * and [NodeEntry.addReverseDepAndCheckIfDone] methods to be separate. All callers have to
 * call both methods in that order if they want to create a node. The second method returns the
 * NEEDS_SCHEDULING state only on the first time it was called. A caller that gets NEEDS_SCHEDULING
 * back from that call must start the evaluation of this node, while any subsequent callers must
 * not.
 * 
 * 
 * An entry is set to ALREADY_EVALUATING as soon as it is scheduled for evaluation. Thus, even a
 * node that is never actually built (for instance, a dirty node that is verified as clean) is in
 * the ALREADY_EVALUATING state until it is DONE.
 * 
 * 
 * From the DONE state, the node can go back to the "marked as affected" state.
 * 
 * @param <D> the type of [DirtyBuildingState] used by the [AbstractInMemoryNodeEntry]
 * subclass
</D> */
internal abstract class AbstractInMemoryNodeEntry<D : DirtyBuildingState?>
    (key: SkyKey?) : InMemoryNodeEntry {
    private val key: SkyKey

    /** Actual data stored in this entry when it is done.  */
    @kotlin.concurrent.Volatile
    protected var value: SkyValue? = null

    /**
     * Tracks state of this entry while it is evaluating (either on its initial build or after being
     * marked dirty).
     */
    @kotlin.concurrent.Volatile
    var dirtyBuildingState: D? = null

    init {
        this.key = com.google.common.base.Preconditions.checkNotNull<SkyKey>(key)
    }

    override fun getKey(): SkyKey {
        return key
    }

    private val isEvaluating: Boolean
        get() = dirtyBuildingState != null

    val isDone: Boolean
        get() = value != null && dirtyBuildingState == null

    @get:kotlin.jvm.Synchronized
    val isReadyToEvaluate: Boolean
        get() = !this.isDone && this.isEvaluating
                && (dirtyBuildingState.isReady(this.numTemporaryDirectDeps)
                || key.supportsPartialReevaluation())

    @kotlin.jvm.Synchronized
    override fun hasUnsignaledDeps(): Boolean {
        com.google.common.base.Preconditions.checkState(!this.isDone, this)
        com.google.common.base.Preconditions.checkState(this.isEvaluating, this)
        return !dirtyBuildingState.isReady(this.numTemporaryDirectDeps)
    }

    val isDirty: Boolean
        get() = !this.isDone

    @get:kotlin.jvm.Synchronized
    val isChanged: Boolean
        get() = !this.isDone && dirtyBuildingState != null && dirtyBuildingState.isChanged()

    @kotlin.jvm.Synchronized
    override fun getValue(): SkyValue? {
        com.google.common.base.Preconditions.checkState(this.isDone, "no value until done. ValueEntry: %s", this)
        return ValueWithMetadata.Companion.justValue(value)
    }

    val valueMaybeWithMetadata: SkyValue?
        get() = value

    override fun toValue(): SkyValue? {
        var lastBuildValue: SkyValue? = value
        if (lastBuildValue == null) {
            synchronized(this) {
                if (value != null) {
                    lastBuildValue = value
                } else if (dirtyBuildingState != null) {
                    try {
                        lastBuildValue = dirtyBuildingState.getLastBuildValue()
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.IllegalStateException("Interruption unexpected: " + this, e)
                    }
                } else {
                    return null // An evaluation was never started.
                }
            }
        }

        return if (lastBuildValue != null) ValueWithMetadata.Companion.justValue(lastBuildValue) else null
    }

    @get:kotlin.jvm.Synchronized
    val errorInfo: com.google.devtools.build.skyframe.ErrorInfo?
        get() {
            com.google.common.base.Preconditions.checkState(this.isDone, "no errors until done. NodeEntry: %s", this)
            return ValueWithMetadata.Companion.getMaybeErrorInfo(value)
        }

    @kotlin.jvm.Synchronized
    override fun addExternalDep() {
        com.google.common.base.Preconditions.checkNotNull<D?>(dirtyBuildingState, this)
        dirtyBuildingState.addExternalDep()
    }

    @get:kotlin.jvm.Synchronized
    val lifecycleState: LifecycleState?
        get() {
            if (this.isDone) {
                return LifecycleState.DONE
            } else if (dirtyBuildingState == null) {
                return LifecycleState.NOT_YET_EVALUATING
            } else {
                return dirtyBuildingState.getLifecycleState()
            }
        }

    @get:Throws(java.lang.InterruptedException::class)
    @get:kotlin.jvm.Synchronized
    val nextDirtyDirectDeps: MutableList<SkyKey>?
        get() {
            com.google.common.base.Preconditions.checkState(!hasUnsignaledDeps(), this)
            com.google.common.base.Preconditions.checkNotNull<D?>(dirtyBuildingState, this)
            com.google.common.base.Preconditions.checkState(
                dirtyBuildingState.isEvaluating(),
                "Not evaluating during getNextDirty? %s",
                this
            )
            return dirtyBuildingState.getNextDirtyDirectDeps()
        }

    @get:Throws(java.lang.InterruptedException::class)
    @get:kotlin.jvm.Synchronized
    val allRemainingDirtyDirectDeps: com.google.common.collect.ImmutableSet<SkyKey?>?
        get() {
            com.google.common.base.Preconditions.checkNotNull<D?>(dirtyBuildingState, this)
            com.google.common.base.Preconditions.checkState(
                dirtyBuildingState.isEvaluating(),
                "Not evaluating for remaining dirty? %s",
                this
            )
            if (this.isDirty) {
                com.google.common.base.Preconditions.checkState(
                    dirtyBuildingState.getLifecycleState() == LifecycleState.REBUILDING,
                    this
                )
                return dirtyBuildingState.getAllRemainingDirtyDirectDeps( /* preservePosition= */true)
            } else {
                return com.google.common.collect.ImmutableSet.of<SkyKey?>()
            }
        }

    @kotlin.jvm.Synchronized
    override fun markRebuilding() {
        com.google.common.base.Preconditions.checkNotNull<D?>(dirtyBuildingState, this).markRebuilding()
    }

    fun newGroupedDeps(): GroupedDeps {
        // If the key skips batch prefetching and possibly opts into partial reevaluation, there will be
        // no environment-scoped map storing previously requested deps values after
        // `SkyFunctionEnvironment` instantiation. So tracking deps with a HashSet is worth the extra
        // memory cost for efficient query -- see SkyFunctionEnvironment.SkipsBatchPrefetch.
        // TODO: b/324948927#comment8 - (1) Determine whether to skip batch prefetch using some logic
        // other than the `SkyKey#skipBatchPrefetch()` method; (2) Consider creating and using the set
        // on demand by calling `GroupedDeps#toSet()` when `SkyFunctionEnvironment#SkipsBatchPrefetch`
        // is created. Whether calling `GroupedDeps#toSet()` introduces performance regression requires
        // some benchmarkings.
        return if (key.skipsBatchPrefetch()) WithHashSet() else GroupedDeps()
    }

    abstract val numTemporaryDirectDeps: Int

    @kotlin.jvm.Synchronized
    override fun noDepsLastBuild(): Boolean {
        com.google.common.base.Preconditions.checkState(this.isEvaluating, this)
        return dirtyBuildingState.noDepsLastBuild()
    }

    @kotlin.jvm.Synchronized
    override fun removeUnfinishedDeps(unfinishedDeps: MutableSet<SkyKey?>) {
        getTemporaryDirectDeps().remove(unfinishedDeps)
    }

    @kotlin.jvm.Synchronized
    override fun addSingletonTemporaryDirectDep(dep: SkyKey?) {
        getTemporaryDirectDeps().appendSingleton(dep)
    }

    @kotlin.jvm.Synchronized
    override fun addTemporaryDirectDepGroup(group: MutableList<SkyKey?>) {
        getTemporaryDirectDeps().appendGroup(group)
    }

    @kotlin.jvm.Synchronized
    override fun addTemporaryDirectDepsInGroups(
        deps: MutableSet<SkyKey?>, groupSizes: MutableList<Int?>?
    ) {
        getTemporaryDirectDeps().appendGroups(deps, groupSizes)
    }

    @kotlin.jvm.Synchronized
    protected open fun toStringHelper(): com.google.common.base.MoreObjects.ToStringHelper? {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("key", key)
            .add("value", value)
            .add("dirtyBuildingState", dirtyBuildingState)
    }

    @kotlin.jvm.Synchronized
    override fun toString(): String {
        return toStringHelper().toString()
    }
}
