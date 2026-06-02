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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/**
 * A node in the graph. All operations on this class are thread-safe.
 * 
 * 
 * This interface is public only for the benefit of alternative graph implementations outside of
 * the package.
 * 
 * 
 * Certain graph implementations' node entries can throw [InterruptedException] on various
 * accesses. Such exceptions should not be caught locally -- they should be allowed to propagate up.
 */
interface NodeEntry {
    /**
     * Return code for [.addReverseDepAndCheckIfDone] and [ ][.checkIfDoneForDirtyReverseDep].
     */
    enum class DependencyState {
        /** The node is done.  */
        DONE,

        /**
         * The node has not started evaluating, and needs to be scheduled for its first evaluation pass.
         * The caller getting this return value is responsible for scheduling its evaluation and
         * signaling the reverse dependency node when this node is done.
         */
        NEEDS_SCHEDULING,

        /**
         * The node was already created, but isn't done yet. The evaluator is responsible for signaling
         * the reverse dependency node.
         */
        ALREADY_EVALUATING
    }

    /** Represents the various states in a node's lifecycle.  */
    enum class LifecycleState {
        /**
         * The entry has never started evaluating. The next call to [.addReverseDepAndCheckIfDone]
         * will put the entry into the [.NEEDS_REBUILDING] state and return [ ][DependencyState.NEEDS_SCHEDULING].
         */
        NOT_YET_EVALUATING,

        /**
         * The node's dependencies need to be checked to see if it needs to be rebuilt. The dependencies
         * must be obtained through calls to [.getNextDirtyDirectDeps] and checked.
         */
        CHECK_DEPENDENCIES,

        /**
         * All of the node's dependencies are unchanged, and the value itself was not marked changed, so
         * its current value is still valid -- it need not be rebuilt.
         */
        VERIFIED_CLEAN,

        /**
         * A rebuilding is required for one of the following reasons:
         * 
         * 
         *  1. One of the node's dependencies changed.
         *  1. The node is built by a [FunctionHermeticity.NONHERMETIC] function and its value
         * is known to have changed due to state outside of Skyframe.
         *  1. The node was [rewound][DirtyType.REWIND].
         * 
         */
        NEEDS_REBUILDING,

        /** A rebuilding is in progress.  */
        REBUILDING,

        /** The node [.isDone].  */
        DONE,
    }

    /** Ways that a node may be dirtied.  */
    enum class DirtyType {
        /**
         * Indicates that the node is being marked dirty because it has a dependency that was marked
         * dirty.
         * 
         * 
         * A node P dirtied with `DIRTY` is re-evaluated during the evaluation phase if it is
         * requested and directly depends on some node C whose value changed since the last evaluation
         * of P. If it is requested and there is no such node C, P is [marked][.markClean].
         */
        DIRTY,

        /**
         * Indicates that the node is being marked dirty because its value from a previous evaluation is
         * no longer valid, even if none of its dependencies change.
         * 
         * 
         * This is typically used to indicate that a value produced by a [ ][FunctionHermeticity.NONHERMETIC] function is no longer valid because some state outside of
         * Skyframe has changed (e.g. a change to the filesystem).
         * 
         * 
         * A node dirtied with `CHANGE` is re-evaluated during the evaluation phase if it is
         * requested, regardless of the state of its dependencies. If it re-evaluates to the same value,
         * dirty parents are not necessarily re-evaluated.
         */
        CHANGE,

        /**
         * Similar to [.CHANGE] except may be used intra-evaluation to indicate that the node's
         * value (which may be from either a previous evaluation or the current evaluation) is no longer
         * valid.
         * 
         * 
         * A node dirtied with `REWIND` is re-evaluated during the evaluation phase if it is
         * requested, regardless of the state of its dependencies. Even if it re-evaluates to the same
         * value, dirty parents are re-evaluated.
         * 
         * 
         * Rewinding is tolerated but no-op if the node is already dirty or is done with an
         * [error][.getErrorInfo] (regardless of the error's [ ]).
         */
        REWIND
    }

    /** Returns whether the entry has been built and is finished evaluating.  */
    @ThreadSafe
    fun isDone(): Boolean

    /** Inverse of [.isDone].  */
    @ThreadSafe
    fun isDirty(): Boolean

    /**
     * Returns true if the entry is marked changed, meaning that it must be re-evaluated even if its
     * dependencies' values have not changed.
     */
    @ThreadSafe
    fun isChanged(): Boolean

    /**
     * Marks this node dirty as specified by the provided [DirtyType].
     * 
     * 
     * `markDirty(DirtyType.DIRTY)` may only be called on a node P for which `P.isDone() || P.isChanged()` (the latter is permitted but has no effect). Similarly, `markDirty(DirtyType.CHANGE)` may only be called on a node P for which `P.isDone() || !P.isChanged()`. Otherwise, this will throw [IllegalStateException].
     * 
     * 
     * `markDirty(DirtyType.REWIND)` may be called at any time (even multiple times
     * concurrently), although it only has an effect if the node [.isDone] with no error.
     * 
     * @return if the node transitioned from done to dirty as a result of this call, a [     ] which may include the node's reverse deps; otherwise `null`
     */
    @ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun markDirty(dirtyType: DirtyType?): MarkedDirtyResult?

    /**
     * Returned by [.markDirty] if that call changed the node from done to dirty.
     * 
     * 
     * For nodes marked dirty during invalidation ([DirtyType.DIRTY] and [ ][DirtyType.CHANGE]), contains a [Collection] of the node's reverse deps for efficiency, so
     * that the invalidator can schedule the invalidation of a node's reverse deps immediately
     * afterwards.
     * 
     * 
     * For nodes marked dirty intra-evaluation ([DirtyType.REWIND]), reverse deps are not
     * needed by the caller, so [.getReverseDepsUnsafe] must not be called.
     * 
     * 
     * Warning: [.getReverseDepsUnsafe] may return a live view of the reverse deps
     * collection of the marked-dirty node. The consumer of this data must be careful only to iterate
     * over and consume its values while that collection is guaranteed not to change. This is true
     * during invalidation, because reverse deps don't change during invalidation.
     */
    class MarkedDirtyResult private constructor() {
        abstract fun getReverseDepsUnsafe(): MutableCollection<SkyKey?>?

        private class ResultWithReverseDeps(reverseDepsUnsafe: MutableCollection<SkyKey?>?) : MarkedDirtyResult() {
            private val reverseDepsUnsafe: MutableCollection<SkyKey?>

            init {
                this.reverseDepsUnsafe =
                    com.google.common.base.Preconditions.checkNotNull<MutableCollection<SkyKey?>>(reverseDepsUnsafe)
            }

            override fun getReverseDepsUnsafe(): MutableCollection<SkyKey?> {
                return reverseDepsUnsafe
            }
        }

        companion object {
            private val RESULT_FOR_REWINDING: MarkedDirtyResult = object : MarkedDirtyResult() {
                override fun getReverseDepsUnsafe(): MutableCollection<SkyKey?>? {
                    throw java.lang.IllegalStateException("Should not need reverse deps for rewinding")
                }
            }

            fun withReverseDeps(reverseDepsUnsafe: MutableCollection<SkyKey?>?): MarkedDirtyResult {
                return ResultWithReverseDeps(reverseDepsUnsafe)
            }

            fun forRewinding(): MarkedDirtyResult {
                return RESULT_FOR_REWINDING
            }
        }
    }

    /**
     * Returns the value stored in this entry, or `null` if it has only an error.
     * 
     * 
     * This method may only be called when the node [.isDone].
     */
    @ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun getValue(): SkyValue?

    /**
     * Returns an immutable iterable of the direct deps of this node. This method may only be called
     * after the evaluation of this node is complete.
     * 
     * 
     * This method is not very efficient, but is only be called in limited circumstances -- when
     * the node is about to be deleted, or when the node is expected to have no direct deps (in which
     * case the overhead is not so bad). It should not be called repeatedly for the same node, since
     * each call takes time proportional to the number of direct deps of the node.
     */
    @ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun getDirectDeps(): Iterable<SkyKey?>?

    /**
     * Returns `true` if this node has at least one direct dep.
     * 
     * 
     * Prefer calling this over [.getDirectDeps] if possible.
     * 
     * 
     * This method may only be called after the evaluation of this node is complete.
     */
    @ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun hasAtLeastOneDep(): Boolean

    /** Removes a reverse dependency, which must be present.  */
    @ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun removeReverseDep(reverseDep: SkyKey?)

    /**
     * Removes any reverse dependencies that are in `deletedKeys`. Must only be called from an
     * invalidation that is deleting nodes from the graph. Sacrifices correctness checks (that the
     * deleted rdeps were actually rdeps of this entry) for better performance.
     */
    @ThreadSafe
    fun removeReverseDepsFromDoneEntryDueToDeletion(deletedKeys: MutableSet<SkyKey?>?)

    /**
     * Returns a copy of the set of reverse dependencies. Note that this introduces a potential
     * check-then-act race; [.removeReverseDep] may fail for a key that is returned here.
     * 
     * 
     * May only be called on a done node entry.
     */
    @ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun getReverseDepsForDoneEntry(): MutableCollection<SkyKey?>?

    /**
     * Returns raw [SkyValue] stored in this entry, which may include metadata associated with
     * it (like events and errors).
     * 
     * 
     * This method returns `null` if the evaluation of this node is not complete, i.e., after
     * node creation or dirtying and before [.setValue] has been called. Callers should assert
     * that the returned value is not `null` whenever they expect the node should be done.
     * 
     * 
     * Use the static methods of [ValueWithMetadata] to extract metadata if necessary.
     */
    @ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun getValueMaybeWithMetadata(): SkyValue?

    /**
     * Returns the last known value of this node, even if it was [marked dirty][.markDirty].
     * 
     * 
     * If this node [.isDone], this is equivalent to [.getValue]. Unlike [ ][.getValue], however, this method may be called at any point in the node's lifecycle. Returns
     * `null` if this node was never built or has no value because it is in error.
     */
    @ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun toValue(): SkyValue?

    /**
     * Returns the error, if any, associated to this node. This method may only be called after the
     * evaluation of this node is complete, i.e., after [.setValue] has been called.
     */
    @ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun getErrorInfo(): com.google.devtools.build.skyframe.ErrorInfo?

    /**
     * Returns the set of reverse deps that have been declared so far this build. Only for use in
     * debugging and when bubbling errors up in the --nokeep_going case, where we need to know what
     * parents this entry has.
     */
    @ThreadSafe
    fun getInProgressReverseDeps(): MutableSet<SkyKey?>?

    /**
     * Transitions the node from the EVALUATING to the DONE state and simultaneously sets it to the
     * given value and error state. It then returns the set of reverse dependencies that need to be
     * signaled.
     * 
     * 
     * This is an atomic operation to avoid a race where two threads work on two nodes, where one
     * node depends on another (b depends on a). When a finishes, it signals **exactly** the set of
     * reverse dependencies that are registered at the time of the `setValue` call. If b comes
     * in before a, it is signaled (and re-scheduled) by a, otherwise it needs to do that itself.
     * 
     * 
     * Nodes may elect to use either `graphVersion` or `maxTransitiveSourceVersion` (if
     * not `null`) for their [version][.getVersion]. The choice can be distinguished
     * by calling [.getMaxTransitiveSourceVersion] - a return of `null` indicates that the
     * node uses the graph version.
     * 
     * 
     * If the entry determines that the new value is equal to the previous value, the entry may
     * keep its current version. Callers can query that version to see if the node considers its value
     * to have changed.
     * 
     * @param value the new value of this node
     * @param graphVersion the version of the graph at which this node is being written
     * @param maxTransitiveSourceVersion the maximal version of this node's dependencies from source,
     * or `null` if source versions are not being tracked
     */
    @ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun setValue(
        value: SkyValue?,
        graphVersion: com.google.devtools.build.skyframe.Version?,
        maxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?
    ): MutableSet<SkyKey?>?

    /**
     * Sets the max transitive source version of this node so far while it is being evaluated. May
     * only be called when [.isDirty] is `true`.
     * 
     * 
     * This method helps to track the in-progress max transitive source version across Skyframe
     * restarts. The eventual max transitive source version is set when [.setValue] is called.
     * 
     * 
     * This function is a no-op if source versions are not being tracked.
     */
    fun setTemporaryMaxTransitiveSourceVersion(
        maxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?
    ) {
    }

    /**
     * Queries if the node is done and adds the given key as a reverse dependency. The return code
     * indicates whether a) the node is done, b) the reverse dependency is the first one, so the node
     * needs to be scheduled, or c) the reverse dependency was added, and the node does not need to be
     * scheduled.
     * 
     * 
     * This method **must** be called before any processing of the entry. This encourages
     * callers to check that the entry is ready to be processed.
     * 
     * 
     * Adding the dependency and checking if the node needs to be scheduled is an atomic operation
     * to avoid a race where two threads work on two nodes, where one depends on the other (b depends
     * on a). In that case, we need to ensure that b is re-scheduled exactly once when a is done.
     * However, a may complete first, in which case b has to re-schedule itself. Also see [ ][.setValue].
     * 
     * 
     * If the parameter is `null`, then no reverse dependency is added, but we still check if
     * the node needs to be scheduled.
     * 
     * 
     * If `reverseDep` is a rebuilding dirty entry that was already a reverse dep of this
     * entry, then [.checkIfDoneForDirtyReverseDep] must be called instead.
     */
    @ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun addReverseDepAndCheckIfDone(reverseDep: SkyKey?): DependencyState?

    /**
     * Similar to [.addReverseDepAndCheckIfDone], except that `reverseDep` must already be
     * a reverse dep of this entry. Should be used when reverseDep has been marked dirty and is
     * checking its dependencies for changes or is rebuilding. The caller must treat the return value
     * just as they would the return value of [.addReverseDepAndCheckIfDone] by scheduling this
     * node for evaluation if needed.
     */
    @ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun checkIfDoneForDirtyReverseDep(reverseDep: SkyKey?): DependencyState?

    fun getAllReverseDepsForNodeBeingDeleted(): MutableCollection<SkyKey?>?

    /**
     * Tell this entry that one of its dependencies is now done. Callers must check the return value,
     * and if true, they must re-schedule this node for evaluation.
     * 
     * 
     * Even if `childVersion` is not at most [.getVersion], this entry may not rebuild,
     * in the case that the entry already rebuilt at `childVersion` and discovered that it had
     * the same value as at an earlier version. For instance, after evaluating at version v1, at
     * version v2, child has a new value, but parent re-evaluates and finds it has the same value,
     * child.getVersion() will return v2 and parent.getVersion() will return v1. At v3 parent is
     * dirtied and checks its dep on child. child signals parent with version v2. That should not in
     * and of itself trigger a rebuild, since parent has already rebuilt with child at v2.
     * 
     * @param childVersion If this entry [.isDirty] and the last version at which this entry was
     * evaluated did not include the changes at version `childVersion` (for instance, if
     * `childVersion` is after the last version at which this entry was evaluated), then
     * this entry records that one of its children has changed since it was last evaluated. Thus,
     * the next call to [.getLifecycleState] will return [     ][LifecycleState.NEEDS_REBUILDING].
     * @param childForDebugging for use in debugging (can be used to identify specific children that
     * invalidate this node)
     */
    @ThreadSafe
    fun signalDep(childVersion: com.google.devtools.build.skyframe.Version?, childForDebugging: SkyKey?): Boolean

    /**
     * Marks this entry as up-to-date at this version.
     * 
     * @return [NodeValueAndRdepsToSignal] containing the SkyValue and reverse deps to signal.
     */
    @ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun markClean(): NodeValueAndRdepsToSignal?

    /**
     * Returned by [.markClean] after making a node as clean. This is an aggregate object that
     * contains the NodeEntry's SkyValue and its reverse dependencies that signal this node is done (a
     * subset of all of the node's reverse dependencies).
     */
    class NodeValueAndRdepsToSignal(value: SkyValue?, rDepsToSignal: MutableSet<SkyKey?>?) {
        private val value: SkyValue?
        private val rDepsToSignal: MutableSet<SkyKey?>?

        init {
            this.value = value
            this.rDepsToSignal = rDepsToSignal
        }

        fun getValue(): SkyValue? {
            return this.value
        }

        fun getRdepsToSignal(): MutableSet<SkyKey?>? {
            return this.rDepsToSignal
        }
    }

    /**
     * Called on a dirty node during [dependency][LifecycleState.CHECK_DEPENDENCIES] to force the node to be re-evaluated, even if none of its dependencies are known to
     * have changed.
     * 
     * 
     * Used when a caller has reason to believe that re-evaluating may yield a new result, such as
     * when the prior evaluation encountered a transient error.
     */
    @ThreadSafe
    fun forceRebuild()

    /** Returns the current version of this node.  */
    @ThreadSafe
    fun getVersion(): com.google.devtools.build.skyframe.Version?

    /**
     * Returns the maximal version of this node's dependencies from source.
     * 
     * 
     * This version should only be tracked when non-hermetic functions [ ][SkyFunction.Environment.injectVersionForNonHermeticFunction] source versions. Otherwise,
     * returns `null` to signal that source versions are not being tracked.
     */
    @ThreadSafe
    fun getMaxTransitiveSourceVersion(): com.google.devtools.build.skyframe.Version? {
        return null
    }

    /**
     * Returns the state of this entry as enumerated by [LifecycleState].
     * 
     * 
     * This method may be called at any time. Returns [LifecycleState.DONE] iff the node
     * [.isDone].
     */
    @ThreadSafe
    fun getLifecycleState(): LifecycleState?

    /**
     * Should only be called if the entry is in the [LifecycleState.CHECK_DEPENDENCIES] state.
     * During the examination to see if the entry must be re-evaluated, this method returns the next
     * group of children to be checked. Callers should have already called [.getLifecycleState]
     * and received a return value of [LifecycleState.CHECK_DEPENDENCIES] before calling this
     * method -- any other return value from [.getLifecycleState] means that this method must
     * not be called, since whether or not the node needs to be rebuilt is already known.
     * 
     * 
     * Deps are returned in groups. The deps in each group were requested in parallel by the `SkyFunction` last build, meaning independently of the values of any other deps in this group
     * (although possibly depending on deps in earlier groups). Thus the caller may check all the deps
     * in this group in parallel, since the deps in all previous groups are verified unchanged. See
     * [SkyFunction.Environment.getValuesAndExceptions] for more on dependency groups.
     * 
     * @see DirtyBuildingState.getNextDirtyDirectDeps
     */
    @ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun getNextDirtyDirectDeps(): MutableList<SkyKey?>?

    /**
     * Returns all deps of a node that has not yet finished evaluating. In other words, if a node has
     * a reverse dep on this node, its key will be in the returned set here.
     * 
     * 
     * The returned set is the union of:
     * 
     * 
     *  * This node's [temporary direct deps][.getTemporaryDirectDeps].
     *  * Deps from a previous evaluation, if this this node was [marked][.markDirty] (all the elements that would have been returned by successive calls to [       ][.getNextDirtyDirectDeps] or, equivalently, one call to [       ][.getAllRemainingDirtyDirectDeps]).
     *  * This node's [reset direct deps][.getResetDirectDeps].
     * 
     * 
     * 
     * This method should only be called when this node is about to be deleted after an aborted
     * evaluation. After such an evaluation, any nodes that did not finish evaluating are deleted, as
     * are any nodes that depend on them, which are necessarily also not done. If this node is to be
     * deleted because of this, we must delete it as a reverse dep from other nodes. This method
     * returns that list of other nodes. This method may not be called on done nodes, since they do
     * not need to be deleted after aborted evaluations.
     * 
     * 
     * This method must not be called twice: the next thing done to this node after this method is
     * called should be the removal of the node from the graph.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getAllDirectDepsForIncompleteNode(): com.google.common.collect.ImmutableSet<SkyKey?>?

    /**
     * If an entry [.isDirty], returns all direct deps that were present last build, but have
     * not yet been verified to be present during the current build. Implementations may lazily remove
     * these deps, since in many cases they will be added back during this build, even though the node
     * may have a changed value. However, any elements of this returned set that have not been added
     * back by the end of evaluation *must* be removed from any done nodes, in order to preserve
     * graph consistency.
     * 
     * 
     * Returns the empty set if an entry is not dirty. In either case, the entry must already have
     * started evaluation.
     * 
     * 
     * This method does not mutate the entry. In particular, multiple calls to this method will
     * always produce the same result until the entry finishes evaluation. Contrast with [ ][.getAllDirectDepsForIncompleteNode].
     */
    @Throws(java.lang.InterruptedException::class)
    fun getAllRemainingDirtyDirectDeps(): com.google.common.collect.ImmutableSet<SkyKey?>?

    /**
     * Notifies a node that it is about to be rebuilt. This method can only be called if the node
     * [LifecycleState.NEEDS_REBUILDING]. After this call, this node is ready to be rebuilt (it
     * will be in [LifecycleState.REBUILDING]).
     */
    fun markRebuilding()

    /**
     * Returns the [GroupedDeps] of direct dependencies. This may only be called while the node
     * is being evaluated (i.e. before [.setValue] and after [.markDirty].
     */
    @ThreadSafe
    fun getTemporaryDirectDeps(): GroupedDeps?

    @ThreadSafe
    fun noDepsLastBuild(): Boolean

    /**
     * Remove dep from direct deps. This should only be called if this entry is about to be committed
     * as a cycle node, but some of its children were not checked for cycles, either because the cycle
     * was discovered before some children were checked; some children didn't have a chance to finish
     * before the evaluator aborted; or too many cycles were found when it came time to check the
     * children.
     */
    @ThreadSafe
    fun removeUnfinishedDeps(unfinishedDeps: MutableSet<SkyKey?>?)

    /**
     * Prepares this node to reset its evaluation from scratch in order to recover from an
     * inconsistency.
     * 
     * 
     * Temporary direct deps should be cleared by this call, as they will be added again when
     * requested during the restarted evaluation of this node. If the graph keeps dependency edges,
     * however, the temporary direct deps must be accounted for in [.getResetDirectDeps].
     * 
     * 
     * Called on a [LifecycleState.REBUILDING] node when one of the following scenarios is
     * observed:
     * 
     * 
     *  1. One or more already requested dependencies are not done. This may happen when a
     * dependency's node was dropped from the graph to save memory, or if a dependency was
     * [rewound][DirtyType.REWIND] by another node.
     *  1. The corresponding [SkyFunction] for this node returned [Reset] to indicate
     * that one or more dependencies were done but are in need of [       rewinding][DirtyType.REWIND] to regenerate their values.
     * 
     * 
     * 
     * This method is similar to calling [.markDirty] with [DirtyType.REWIND] with an
     * important distinction: rewinding is initiated on a *done* node because of an issue with
     * its *value*, while this method is called on a *building* node because of an issue
     * with a *dependency*. The dependency will be rewound if we are in scenario 2 above.
     * 
     * 
     * Reverse deps on the other hand should be preserved - parents waiting on this node are
     * unaware that it is being restarted and will not register themselves again, yet they still need
     * to be signaled when this node is done.
     */
    @ThreadSafe
    fun resetEvaluationFromScratch()

    /**
     * If the graph keeps dependency edges and [.resetEvaluationFromScratch] has been called on
     * this node since it was last done, returns the set of temporary direct deps that were registered
     * prior to the restart. Otherwise, returns an empty set.
     * 
     * 
     * Called on a [LifecycleState.REBUILDING] node when it is about to finish evaluating.
     * Used to determine which of its [temporary direct deps][.getTemporaryDirectDeps] have
     * already registered a corresponding reverse dep, in order to avoid creating duplicate rdep
     * edges.
     * 
     * 
     * Like [.getAllRemainingDirtyDirectDeps], keys in the returned set are assumed to have
     * already registered an rdep on this node. Unlike [.getAllRemainingDirtyDirectDeps],
     * however, deps in the returned set may have only been registered at the current evaluation
     * version, not a previous one.
     * 
     * 
     * If this node was reset multiple times since it was last done, must return deps requested
     * prior to *any* of those restarts, not just the most recent one.
     */
    @ThreadSafe
    fun getResetDirectDeps(): com.google.common.collect.ImmutableSet<SkyKey?>?

    /**
     * Adds a temporary direct dep in its own group.
     * 
     * 
     * The given dep must not be present in this node's existing temporary direct deps.
     */
    @ThreadSafe
    fun addSingletonTemporaryDirectDep(dep: SkyKey?)

    /**
     * Adds a temporary direct group.
     * 
     * 
     * The group must be duplicate-free and not contain any deps in common with this node's
     * existing temporary direct deps.
     */
    @ThreadSafe
    fun addTemporaryDirectDepGroup(group: MutableList<SkyKey?>?)

    /**
     * Adds temporary direct deps in groups.
     * 
     * 
     * The iteration order of the given deps along with the `groupSizes` parameter dictate
     * how deps are grouped. For example, if `deps = {a,b,c}` and `groupSizes = [2, 1]`,
     * then there will be two groups: `[a,b]` and `[c]`. The sum of `groupSizes`
     * must equal the size of `deps`. Note that it only makes sense to call this method with a
     * set implementation that has a stable iteration order.
     * 
     * 
     * The given set of deps must not contain any deps in common with this node's existing
     * temporary direct deps.
     */
    @ThreadSafe
    fun addTemporaryDirectDepsInGroups(deps: MutableSet<SkyKey?>?, groupSizes: MutableList<Int?>?)

    fun addExternalDep()

    /**
     * Returns true if the node has been signaled exactly as many times as it has temporary
     * dependencies, or if `getKey().supportsPartialReevaluation()`. This may only be called
     * while the node is being evaluated (i.e. before [.setValue] and after [.markDirty]).
     */
    @ThreadSafe
    fun isReadyToEvaluate(): Boolean

    /**
     * Returns true if the node has not been signaled exactly as many times as it has temporary
     * dependencies. This may only be called while the node is being evaluated (i.e. before [ ][.setValue] and after [.markDirty]).
     * 
     * 
     * The node must not complete or be reset while in this state because it may yet be signaled.
     */
    @ThreadSafe
    fun hasUnsignaledDeps(): Boolean
}
