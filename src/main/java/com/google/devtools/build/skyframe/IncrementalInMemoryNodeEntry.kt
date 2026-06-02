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

import com.google.devtools.build.lib.skyframe.serialization.DeserializedSkyValue

/** An [InMemoryNodeEntry] that [.keepsEdges] for use in incremental evaluations.  */
class IncrementalInMemoryNodeEntry(key: SkyKey?) : AbstractInMemoryNodeEntry<DirtyBuildingState?>(key) {
    @kotlin.concurrent.Volatile
    protected var version: NodeVersion = com.google.devtools.build.skyframe.Version.Companion.minimal()

    /**
     * This object represents the direct deps of the node, in groups if the `SkyFunction`
     * requested them that way. It contains either the in-progress direct deps, stored as a [ ] (constructed via [GroupedDeps.WithHashSet] if `key.supportsPartialReevaluation()`) before the node is finished building, or the full direct
     * deps, compressed in a memory-efficient way (via [GroupedDeps.compress]), after the node
     * is done.
     * 
     * 
     * It is initialized lazily in getTemporaryDirectDeps() to save a little memory.
     */
    var directDeps: Any? = null

    /**
     * This list stores the reverse dependencies of this node that have been declared so far.
     * 
     * 
     * In case of a single object we store the object unwrapped, without the list, for
     * memory-efficiency.
     * 
     * 
     * When an entry is being re-evaluated, this object stores the reverse deps from the previous
     * evaluation. At the end of evaluation, the changed reverse dep operations from [ ][.reverseDepsDataToConsolidate] are merged in here.
     */
    @get:kotlin.jvm.Synchronized
    var reverseDepsRawForReverseDepsUtil: Any? = com.google.common.collect.ImmutableList.of<Any?>()
        protected set

    /** Sets [.reverseDepsDataToConsolidate]. Does not alter [.reverseDeps].  */
    /**
     * This list stores objects returned by [KeyToConsolidate.create]. Morally they are [ ] objects, but since some operations are stored bare, we can only declare that
     * this list holds [Object] references. Created lazily to save memory.
     * 
     * 
     * This list serves double duty. For a done node, when a reverse dep is removed, checked for
     * presence, or possibly added, we store the mutation in this object instead of immediately doing
     * the operation. That is because removals/checks in reverseDeps are O(N). Originally reverseDeps
     * was a HashSet, but because of memory consumption we switched to a list.
     * 
     * 
     * Internally, [ReverseDepsUtility] consolidates this data periodically, and when the set
     * of reverse deps is requested. While this operation is not free, it can be done more effectively
     * than trying to remove/check each dirty reverse dependency individually (O(N) each time).
     * 
     * 
     * When the node entry is evaluating, this list serves to declare the reverse dep operations
     * that have taken place on it during this evaluation. When evaluation finishes, this list will be
     * merged into the existing reverse deps if any, but furthermore, this list will also be used to
     * calculate the set of reverse deps to signal when this entry finishes evaluation. That is done
     * by [ReverseDepsUtility.consolidateDataAndReturnNewElements].
     */
    @get:kotlin.jvm.Synchronized
    @set:kotlin.jvm.Synchronized
    var reverseDepsDataToConsolidateForReverseDepsUtil: MutableList<Any?>? = null

    /**
     * Replaces the SkyValue with a placeholder value indicating that it has been cleared.
     * 
     * 
     * Almost all SkyFunctions will break if they receive a cleared value and it should only be
     * used in situations where that is known to be impossible. It should never be used in cases where
     * a Bazel server instance will be kept running for incremental builds since the graph would be
     * mutilated.
     * 
     * 
     * One appropriate use case is an optimization for Skycache primer builds (which are always
     * cold) that reduces peak heap by discarding unneeded values before serialization.
     */
    fun clearSkyValue() {
        com.google.common.base.Preconditions.checkState(isDone())
        this.value = CLEARED_SKY_VALUE
    }

    override fun keepsEdges(): Boolean {
        return true
    }

    override fun getDirectDeps(): Iterable<SkyKey?> {
        return GroupedDeps.Companion.compressedToIterable(this.compressedDirectDepsForDoneEntry)
    }

    override fun hasAtLeastOneDep(): Boolean {
        return !GroupedDeps.Companion.isEmpty(this.compressedDirectDepsForDoneEntry)
    }

    @get:kotlin.jvm.Synchronized
    val compressedDirectDepsForDoneEntry: @`<error>` Any?
        get() {
            com.google.common.base.Preconditions.checkState(isDone(), "no deps until done. NodeEntry: %s", this)
            com.google.common.base.Preconditions.checkNotNull<Any?>(directDeps, "deps can't be null: %s", this)
            return GroupedDeps.Companion.castAsCompressed(directDeps)
        }

    /**
     * Puts entry in "done" state, as checked by [.isDone]. Subclasses that override one may
     * need to override the other.
     */
    @com.google.errorprone.annotations.ForOverride
    protected fun markDone() {
        dirtyBuildingState = null
    }

    @kotlin.jvm.Synchronized
    protected fun setStateFinishedAndReturnReverseDepsToSignal(): MutableSet<SkyKey?>? {
        val reverseDepsToSignal: MutableSet<SkyKey?>? = ReverseDepsUtility.consolidateDataAndReturnNewElements(this)
        directDeps = this.temporaryDirectDeps.compress()
        markDone()
        return reverseDepsToSignal
    }

    @get:kotlin.jvm.Synchronized
    val inProgressReverseDeps: MutableSet<SkyKey>?
        get() {
            com.google.common.base.Preconditions.checkState(!isDone(), this)
            return ReverseDepsUtility.returnNewElements(this)
        }

    /**
     * {@inheritDoc}
     * 
     * 
     * In this method it is crucial that [.version] is set prior to [.value] because
     * although this method itself is synchronized, there are unsynchronized consumers of the version
     * and the value.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @kotlin.jvm.Synchronized
    @Throws(java.lang.InterruptedException::class)
    override fun setValue(
        value: SkyValue?,
        graphVersion: com.google.devtools.build.skyframe.Version,
        maxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?
    ): MutableSet<SkyKey?>? {
        com.google.common.base.Preconditions.checkState(
            !hasUnsignaledDeps(),
            "Has unsignaled deps (this=%s, value=%s)",
            this,
            value
        )
        com.google.common.base.Preconditions.checkState(
            version.lastChanged().atMost(graphVersion) && version.lastEvaluated().atMost(graphVersion),
            "Bad version (this=%s, version=%s, value=%s)",
            this,
            graphVersion,
            value
        )

        if (dirtyBuildingState.unchangedFromLastBuild(value)) {
            // If the value is the same as before, prefer the old value. Note that we don't prefer the new
            // value, because preserving == equality is even better than .equals() equality. The exception
            // is when comparing a regular SkyValue vs an otherwise equal DeserializedSkyValue:
            //  - If old computed -> new deserialized, we need the deserialized value for proper
            //    invalidation on subsequent evaluations, since we don't have proper deps. See
            //    SkyframeExecutor#invalidateWithExternalService.
            //  - If old deserialized -> new computed, we prefer the computed value since we do have
            //    proper deps and can therefore rely on the more precise classic bottom-up invalidation.
            val lastChanged: com.google.devtools.build.skyframe.Version = version.lastChanged()
            version = NodeVersion.Companion.of(lastChanged, graphVersion)
            val oldValue: SkyValue? = dirtyBuildingState.getLastBuildValue()
            this.value =
                if (value is DeserializedSkyValue != oldValue is DeserializedSkyValue)
                    value
                else
                    oldValue
        } else {
            // If this is a new value, or it has changed since the last build, set the version to the
            // current graph version.
            version = graphVersion
            this.value = value
        }
        return setStateFinishedAndReturnReverseDepsToSignal()
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    override fun addReverseDepAndCheckIfDone(reverseDep: SkyKey?): DependencyState {
        if (reverseDep == null && isDone()) {
            return DependencyState.DONE
        }

        synchronized(this) {
            val done: Boolean = isDone()
            if (!done && dirtyBuildingState == null) {
                dirtyBuildingState = InitialBuildingState()
            }
            if (reverseDep != null) {
                if (done) {
                    ReverseDepsUtility.addReverseDep(this, reverseDep)
                } else {
                    appendToReverseDepOperations(reverseDep, com.google.devtools.build.skyframe.KeyToConsolidate.Op.ADD)
                }
            }
            if (done) {
                return DependencyState.DONE
            }
            if (dirtyBuildingState.isEvaluating()) {
                return DependencyState.ALREADY_EVALUATING
            }
            dirtyBuildingState.startEvaluating()
            return DependencyState.NEEDS_SCHEDULING
        }
    }

    /** Sets [.reverseDeps]. Does not alter [.reverseDepsDataToConsolidate].  */
    @kotlin.jvm.Synchronized
    fun setReverseDepsForReverseDepsUtil(reverseDeps: Any?) {
        this.reverseDepsRawForReverseDepsUtil = reverseDeps
    }

    @kotlin.jvm.Synchronized
    private fun appendToReverseDepOperations(
        reverseDep: SkyKey?,
        op: com.google.devtools.build.skyframe.KeyToConsolidate.Op?
    ) {
        com.google.common.base.Preconditions.checkState(
            !isDone(),
            "Don't append to done %s %s %s",
            this,
            reverseDep,
            op
        )
        if (this.reverseDepsDataToConsolidateForReverseDepsUtil == null) {
            this.reverseDepsDataToConsolidateForReverseDepsUtil = java.util.ArrayList<Any?>()
        }
        com.google.common.base.Preconditions.checkState(
            isDirty() || op != com.google.devtools.build.skyframe.KeyToConsolidate.Op.CHECK,
            "Not dirty check %s %s",
            this,
            reverseDep
        )
        reverseDepsDataToConsolidateForReverseDepsUtil!!.add(KeyToConsolidate.Companion.create(reverseDep, op, this))
    }

    @kotlin.jvm.Synchronized
    override fun checkIfDoneForDirtyReverseDep(reverseDep: SkyKey?): DependencyState? {
        com.google.common.base.Preconditions.checkNotNull<SkyKey?>(reverseDep, this)
        if (isDone()) {
            return DependencyState.DONE
        }
        appendToReverseDepOperations(reverseDep, com.google.devtools.build.skyframe.KeyToConsolidate.Op.CHECK)
        return addReverseDepAndCheckIfDone(null)
    }

    @kotlin.jvm.Synchronized
    override fun removeReverseDep(reverseDep: SkyKey?) {
        if (isDone()) {
            ReverseDepsUtility.removeReverseDep(this, reverseDep)
        } else {
            // Removing a reverse dep from an in-flight node is rare -- it should only happen when there
            // is a cycle or this node is about to be cleaned from the graph.
            appendToReverseDepOperations(reverseDep, com.google.devtools.build.skyframe.KeyToConsolidate.Op.REMOVE)
        }
    }

    @kotlin.jvm.Synchronized
    override fun removeReverseDepsFromDoneEntryDueToDeletion(deletedKeys: MutableSet<SkyKey?>?) {
        com.google.common.base.Preconditions.checkState(isDone(), this)
        ReverseDepsUtility.removeReverseDepsMatching(this, deletedKeys)
    }

    @get:kotlin.jvm.Synchronized
    val reverseDepsForDoneEntry: MutableCollection<SkyKey>?
        get() {
            com.google.common.base.Preconditions.checkState(isDone(), "Called on not done %s", this)
            return ReverseDepsUtility.consolidateAndGetReverseDeps(this,  /* checkConsistency= */true)
        }

    @get:kotlin.jvm.Synchronized
    val allReverseDepsForNodeBeingDeleted: MutableCollection<SkyKey>?
        get() {
            if (!isDone()) {
                // This consolidation loses information about pending reverse deps to signal, but that is
                // unimportant since this node is being deleted.
                ReverseDepsUtility.consolidateDataAndReturnNewElements(this)
            }
            return ReverseDepsUtility.consolidateAndGetReverseDeps(this,  /* checkConsistency= */false)
        }

    @kotlin.jvm.Synchronized
    override fun signalDep(
        childVersion: com.google.devtools.build.skyframe.Version?,
        childForDebugging: SkyKey?
    ): Boolean {
        com.google.common.base.Preconditions.checkState(
            !isDone(), "Value must not be done in signalDep %s child=%s", this, childForDebugging
        )
        com.google.common.base.Preconditions.checkNotNull<DirtyBuildingState?>(
            dirtyBuildingState,
            "%s %s",
            this,
            childForDebugging
        )
        dirtyBuildingState.signalDep(this, version, childVersion, childForDebugging)
        return !hasUnsignaledDeps()
    }

    /**
     * Creates a [DirtyBuildingState] for the case where this node is done and is being marked
     * dirty.
     */
    @com.google.errorprone.annotations.ForOverride
    protected fun createDirtyBuildingStateForDoneNode(
        dirtyType: DirtyType?, directDeps: GroupedDeps, value: SkyValue?
    ): DirtyBuildingState {
        return IncrementalBuildingState(dirtyType, directDeps, value)
    }

    @kotlin.jvm.Synchronized
    override fun markDirty(dirtyType: DirtyType?): MarkedDirtyResult? {
        com.google.common.base.Preconditions.checkNotNull<DirtyType?>(dirtyType, this)

        if (isDone()) {
            if (dirtyType == DirtyType.REWIND && getErrorInfo() != null) {
                return null // Rewinding errors is no-op.
            }
            val directDeps: GroupedDeps = GroupedDeps.Companion.decompress(this.compressedDirectDepsForDoneEntry)
            com.google.common.base.Preconditions.checkState(
                dirtyType != DirtyType.DIRTY || !directDeps.isEmpty(),
                "%s is being marked dirty but has no children that could have dirtied it",
                getKey()
            )
            dirtyBuildingState = createDirtyBuildingStateForDoneNode(dirtyType, directDeps, value)
            value = null
            this.directDeps = null
            if (dirtyType == DirtyType.REWIND) {
                // For rewinding, the reverse deps don't need to be included in the MarkedDirtyResult, but
                // they do need to be consolidated so that ReverseDepsUtility considers only rdep operations
                // that occur after the rewind to be "new elements." This is important because only rdeps
                // registered after the rewind should be signalled when the rewound evaluation completes.
                ReverseDepsUtility.consolidateData(this)
                return MarkedDirtyResult.Companion.forRewinding()
            } else {
                return MarkedDirtyResult.Companion.withReverseDeps(
                    ReverseDepsUtility.consolidateAndGetReverseDeps(this,  /* checkConsistency= */true)
                )
            }
        }

        // The caller may be simultaneously trying to mark this node dirty and changed, and the dirty
        // thread may have lost the race, but it is the caller's responsibility not to try to mark this
        // node changed twice. The end result of racing markers must be a changed node, since one of the
        // markers is trying to mark the node changed.
        com.google.common.base.Preconditions.checkState(value == null, "Value should have been reset already %s", this)
        when (dirtyType) {
            DirtyType.CHANGE -> {
                com.google.common.base.Preconditions.checkState(
                    !isChanged(),
                    "Cannot mark node changed twice: %s",
                    this
                )
                com.google.common.base.Preconditions.checkNotNull<DirtyBuildingState?>(dirtyBuildingState, this)
                    .markChanged()
            }

            DirtyType.DIRTY -> com.google.common.base.Preconditions.checkState(
                isChanged(),
                "Cannot mark node dirty twice: %s",
                this
            )

            DirtyType.REWIND -> {}
        }
        return null
    }

    @kotlin.jvm.Synchronized
    @Throws(java.lang.InterruptedException::class)
    override fun markClean(): NodeValueAndRdepsToSignal {
        com.google.common.base.Preconditions.checkNotNull<DirtyBuildingState?>(dirtyBuildingState, this)
        this.value =
            com.google.common.base.Preconditions.checkNotNull<SkyValue?>(dirtyBuildingState.getLastBuildValue())
        com.google.common.base.Preconditions.checkState(!hasUnsignaledDeps(), this)
        com.google.common.base.Preconditions.checkState(
            dirtyBuildingState.depsUnchangedFromLastBuild(this.temporaryDirectDeps),
            "Direct deps must be the same as those found last build for node to be marked clean: %s",
            this
        )
        com.google.common.base.Preconditions.checkState(isDirty(), this)
        com.google.common.base.Preconditions.checkState(
            !dirtyBuildingState.isChanged(),
            "shouldn't be changed: %s",
            this
        )
        val rDepsToSignal: MutableSet<SkyKey?>? = setStateFinishedAndReturnReverseDepsToSignal()
        return NodeValueAndRdepsToSignal(this.value, rDepsToSignal)
    }

    override fun getVersion(): com.google.devtools.build.skyframe.Version {
        return version.lastChanged()
    }

    @get:Throws(java.lang.InterruptedException::class)
    @get:kotlin.jvm.Synchronized
    val allDirectDepsForIncompleteNode: com.google.common.collect.ImmutableSet<SkyKey?>
        get() {
            com.google.common.base.Preconditions.checkState(!isDone(), this)
            if (dirtyBuildingState == null) {
                return com.google.common.collect.ImmutableSet.of<SkyKey?>()
            }
            return com.google.common.collect.ImmutableSet.builder<SkyKey?>()
                .addAll(this.temporaryDirectDeps.getAllElementsAsIterable())
                .addAll(dirtyBuildingState.getAllRemainingDirtyDirectDeps( /* preservePosition= */false))
                .addAll(this.resetDirectDeps)
                .build()
        }

    @get:kotlin.jvm.Synchronized
    val temporaryDirectDeps: GroupedDeps
        get() {
            com.google.common.base.Preconditions.checkState(!isDone(), "temporary shouldn't be done: %s", this)
            if (directDeps == null) {
                // Initialize lazily, to save a little memory.
                directDeps = newGroupedDeps()
            }
            return directDeps as GroupedDeps
        }

    @kotlin.jvm.Synchronized
    override fun forceRebuild() {
        com.google.common.base.Preconditions.checkNotNull<DirtyBuildingState?>(dirtyBuildingState, this).forceRebuild(
            this.numTemporaryDirectDeps
        )
    }

    @get:kotlin.jvm.Synchronized
    val numTemporaryDirectDeps: Int
        get() = if (directDeps == null) 0 else this.temporaryDirectDeps.numElements()

    @kotlin.jvm.Synchronized
    override fun resetEvaluationFromScratch() {
        com.google.common.base.Preconditions.checkState(!hasUnsignaledDeps(), this)

        val resetDeps: com.google.common.collect.ImmutableSet<SkyKey?> =
            com.google.common.collect.ImmutableSet.builder<SkyKey?>()
                .addAll(this.resetDirectDeps) // In case this isn't the first reset.
                .addAll(this.temporaryDirectDeps.getAllElementsAsIterable())
                .build()

        if (dirtyBuildingState.isIncremental()) {
            val incrementalBuildingState = dirtyBuildingState as IncrementalBuildingState?
            dirtyBuildingState =
                ResetIncrementalBuildingState(
                    incrementalBuildingState.lastBuildDirectDeps,
                    incrementalBuildingState.lastBuildValue,
                    incrementalBuildingState.dirtyDirectDepIndex,
                    resetDeps
                )
        } else {
            dirtyBuildingState = ResetInitialBuildingState(resetDeps)
        }
        directDeps = null
    }

    val resetDirectDeps: com.google.common.collect.ImmutableSet<SkyKey?>?
        get() = com.google.common.base.Preconditions.checkNotNull<DirtyBuildingState?>(dirtyBuildingState, this)
            .getResetDirectDeps()

    /**
     * For Skyfocus only: clears out all direct dep edges of this node. It is not safe to call this
     * otherwise.
     */
    @kotlin.jvm.Synchronized
    fun clearDirectDepsForSkyfocus() {
        com.google.common.base.Preconditions.checkState(isDone(), this)
        this.directDeps = GroupedDeps.Companion.EMPTY_COMPRESSED
    }

    /** Flushes pending reverse dep operations, which potentially saves memory.  */
    @kotlin.jvm.Synchronized
    fun consolidateReverseDeps() {
        com.google.common.base.Preconditions.checkState(isDone(), this)
        ReverseDepsUtility.consolidateData(this)
    }

    @kotlin.jvm.Synchronized
    override fun toStringHelper(): com.google.common.base.MoreObjects.ToStringHelper {
        return super.toStringHelper()
            .add("version", version)
            .add(
                "directDeps",
                if (isDone()) GroupedDeps.Companion.decompress(this.compressedDirectDepsForDoneEntry) else directDeps
            )
            .add("reverseDeps", ReverseDepsUtility.toString(this))
    }

    /** [DirtyBuildingState] for a node on an incremental build.  */
    private open class IncrementalBuildingState(
        dirtyType: DirtyType?,
        lastBuildDirectDeps: GroupedDeps,
        lastBuildValue: SkyValue?
    ) : DirtyBuildingState(dirtyType) {
        private val lastBuildDirectDeps: GroupedDeps
        private val lastBuildValue: SkyValue?

        init {
            this.lastBuildDirectDeps = lastBuildDirectDeps
            this.lastBuildValue = lastBuildValue
        }

        protected val isIncremental: Boolean
            get() = true

        override fun getLastBuildValue(): SkyValue? {
            return lastBuildValue
        }

        override fun getLastBuildDirectDeps(): GroupedDeps {
            return lastBuildDirectDeps
        }

        val numOfGroupsInLastBuildDirectDeps: Int
            get() = lastBuildDirectDeps.numGroups()

        val stringHelper: com.google.common.base.MoreObjects.ToStringHelper
            get() = super.getStringHelper()
                .add("lastBuildDirectDeps", lastBuildDirectDeps)
                .add("lastBuildValue", lastBuildValue)
    }

    /**
     * Used to track already registered deps when there is a [ reset][.resetEvaluationFromScratch] on a node's initial build.
     */
    private class ResetInitialBuildingState(resetDeps: com.google.common.collect.ImmutableSet<SkyKey?>?) :
        InitialBuildingState() {
        private val resetDeps: com.google.common.collect.ImmutableSet<SkyKey?>?

        init {
            this.resetDeps = resetDeps
            markRebuilding()
            startEvaluating()
        }

        val resetDirectDeps: com.google.common.collect.ImmutableSet<SkyKey?>?
            get() = resetDeps

        val stringHelper: com.google.common.base.MoreObjects.ToStringHelper
            get() = super.getStringHelper().add("resetDeps", resetDeps)
    }

    /**
     * Used to track already registered deps when there is a [ reset][.resetEvaluationFromScratch] on a node's incremental build.
     */
    private class ResetIncrementalBuildingState(
        lastBuildDirectDeps: GroupedDeps,
        lastBuildValue: SkyValue?,
        dirtyDirectDepIndex: Int,
        resetDeps: com.google.common.collect.ImmutableSet<SkyKey?>?
    ) : IncrementalBuildingState(DirtyType.CHANGE, lastBuildDirectDeps, lastBuildValue) {
        private val resetDeps: com.google.common.collect.ImmutableSet<SkyKey?>?

        init {
            // CHANGE (not DIRTY) since we already know it needs rebuilding.
            this.dirtyDirectDepIndex = dirtyDirectDepIndex
            this.resetDeps = resetDeps
            markRebuilding()
            startEvaluating()
        }

        val resetDirectDeps: com.google.common.collect.ImmutableSet<SkyKey?>?
            get() = resetDeps

        override fun getStringHelper(): com.google.common.base.MoreObjects.ToStringHelper {
            return super.stringHelper.add("resetDeps", resetDeps)
        }
    }

    companion object {
        val CLEARED_SKY_VALUE: EmptySkyValue = EmptySkyValue()
    }
}
