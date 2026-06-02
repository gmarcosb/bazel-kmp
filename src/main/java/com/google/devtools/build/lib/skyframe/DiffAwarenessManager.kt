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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories

/**
 * Helper class to make it easier to correctly use the [DiffAwareness] interface in a
 * sequential manner.
 */
class DiffAwarenessManager(diffAwarenessFactories: Iterable<out DiffAwareness.Factory>) {
    // The manager attempts to instantiate these in the order in which they are passed to the
    // constructor; this is critical in the case where a factory always succeeds.
    private val diffAwarenessFactories: com.google.common.collect.ImmutableList<out DiffAwareness.Factory>

    /** The unique key to retrieve a DiffAwarenessState.  */
    @AutoValue
    internal abstract class StateKey {
        abstract fun root(): Root?

        abstract fun ignoredPaths(): IgnoredSubdirectories?

        companion object {
            private fun create(root: Root?, ignoredPaths: IgnoredSubdirectories?): StateKey {
                return AutoValue_DiffAwarenessManager_StateKey(root, ignoredPaths)
            }
        }
    }

    private val currentDiffAwarenessStates: MutableMap<StateKey?, DiffAwarenessState> =
        com.google.common.collect.Maps.newHashMap<StateKey?, DiffAwarenessState?>()

    init {
        this.diffAwarenessFactories = com.google.common.collect.ImmutableList.copyOf(diffAwarenessFactories)
    }

    private class DiffAwarenessState(diffAwareness: DiffAwareness, baselineView: View?) {
        private val diffAwareness: DiffAwareness

        /**
         * The [View] that should be the baseline for the next [.getDiff] call, or `null` if the next [.getDiff] will be the first incremental one.
         */
        private var baselineView: View?

        /**
         * Cached new [View] from a call to [.getEvaluatingVersionDiff], for the next [ ][.getDiff] call.
         */
        private var cachedNewView: View? = null

        init {
            this.diffAwareness = diffAwareness
            this.baselineView = baselineView
        }
    }

    /** Reset internal [DiffAwareness] state.  */
    fun reset() {
        for (diffAwarenessState in currentDiffAwarenessStates.values) {
            diffAwarenessState.diffAwareness.close()
        }
        currentDiffAwarenessStates.clear()
    }

    /** A set of modified files that should be marked as processed.  */
    interface ProcessableModifiedFileSet {
        @kotlin.jvm.JvmField
        val modifiedFileSet: ModifiedFileSet?

        @kotlin.jvm.JvmField
        val workspaceInfo: WorkspaceInfoFromDiff?

        /**
         * This should be called when the changes have been noted. Otherwise, the result from the next
         * call to [.getDiff] will be from the baseline of the old, unprocessed, diff.
         */
        fun markProcessed()
    }

    /**
     * Represents old and new evaluating versions as per [ ][WorkspaceInfoFromDiff.getEvaluatingVersion].
     */
    class EvaluatingVersionDiff(from: IntVersion?, to: IntVersion?) : Postable {
        val numericalDiff: Long
            get() = to.getVal() - from.getVal()
        val from: IntVersion?
        val to: IntVersion?

        init {
            this.to = to
            this.from = from
            com.google.common.base.Preconditions.checkNotNull<IntVersion?>(from)
            com.google.common.base.Preconditions.checkNotNull<IntVersion?>(to)
        }
    }

    /**
     * Returns an [EvaluatingVersionDiff] corresponding to the current diff.
     * 
     * 
     * Returns an empty optional if there is no baseline view or if the views do not support
     * evaluating versions.
     */
    fun getEvaluatingVersionDiff(
        pathEntry: Root?, options: com.google.devtools.common.options.OptionsProvider?
    ): java.util.Optional<EvaluatingVersionDiff?> {
        val diffAwarenessState =
            maybeGetDiffAwarenessState(pathEntry, IgnoredSubdirectories.EMPTY, options)
        if (diffAwarenessState == null || diffAwarenessState.baselineView == null) {
            return java.util.Optional.empty<EvaluatingVersionDiff?>()
        }

        val baselineWorkspaceInfo: WorkspaceInfoFromDiff? =
            diffAwarenessState.baselineView.getWorkspaceInfo()
        if (baselineWorkspaceInfo == null) {
            return java.util.Optional.empty<EvaluatingVersionDiff?>()
        }

        val newView: View
        try {
            Profiler.instance().profile("diffAwareness.getCurrentView").use { c ->
                newView = diffAwarenessState.diffAwareness.getCurrentView(options)
                diffAwarenessState.cachedNewView = newView
            }
        } catch (e: BrokenDiffAwarenessException) {
            return java.util.Optional.empty<EvaluatingVersionDiff?>()
        }

        val newWorkspaceInfo: WorkspaceInfoFromDiff? = newView.getWorkspaceInfo()
        if (newWorkspaceInfo == null) {
            return java.util.Optional.empty<EvaluatingVersionDiff?>()
        }

        return java.util.Optional.of<EvaluatingVersionDiff?>(
            EvaluatingVersionDiff(
                baselineWorkspaceInfo.getEvaluatingVersion(), newWorkspaceInfo.getEvaluatingVersion()
            )
        )
    }

    /**
     * Gets the set of changed files since the last call with this path entry, or `ModifiedFileSet.EVERYTHING_MODIFIED` if this is the first such call.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getDiff(
        eventHandler: EventHandler,
        pathEntry: Root?,
        ignoredPaths: IgnoredSubdirectories?,
        options: com.google.devtools.common.options.OptionsProvider?
    ): ProcessableModifiedFileSet {
        val diffAwarenessState =
            maybeGetDiffAwarenessState(pathEntry, ignoredPaths, options)
        if (diffAwarenessState == null) {
            return BrokenProcessableModifiedFileSet.Companion.INSTANCE
        }
        val diffAwareness: DiffAwareness = diffAwarenessState.diffAwareness

        var newView: View? = diffAwarenessState.cachedNewView
        if (newView == null) {
            try {
                Profiler.instance().profile("diffAwareness.getCurrentView").use { c ->
                    newView = diffAwarenessState.diffAwareness.getCurrentView(options)
                }
            } catch (e: BrokenDiffAwarenessException) {
                handleBrokenDiffAwareness(eventHandler, pathEntry, ignoredPaths, e)
                return BrokenProcessableModifiedFileSet.Companion.INSTANCE
            }
        } else {
            diffAwarenessState.cachedNewView = null
        }

        val baselineView: View? = diffAwarenessState.baselineView
        val diff: ModifiedFileSet?
        logger.atInfo().log(
            "About to compute diff between %s and %s for %s", baselineView, newView, pathEntry
        )
        try {
            Profiler.instance().profile("diffAwareness.getDiff").use { c ->
                diff = diffAwareness.getDiff(baselineView, newView)
            }
        } catch (e: BrokenDiffAwarenessException) {
            handleBrokenDiffAwareness(eventHandler, pathEntry, ignoredPaths, e)
            return BrokenProcessableModifiedFileSet.Companion.INSTANCE
        } catch (e: IncompatibleViewException) {
            throw java.lang.IllegalStateException(pathEntry.toString() + " " + baselineView + " " + newView, e)
        }

        return ProcessableModifiedFileSetImpl(diff, pathEntry, ignoredPaths, newView)
    }

    private fun handleBrokenDiffAwareness(
        eventHandler: EventHandler,
        pathEntry: Root?,
        ignoredPaths: IgnoredSubdirectories?,
        e: BrokenDiffAwarenessException
    ) {
        val stateKey = StateKey.Companion.create(pathEntry, ignoredPaths)
        currentDiffAwarenessStates.remove(stateKey)
        logger.atInfo().withCause(e).log("Broken diff awareness for %s", pathEntry)
        eventHandler.handle(
            Event.warn(
                (e.getMessage() + "... temporarily falling back to manually "
                        + "checking files for changes")
            )
        )
    }

    /**
     * Returns the current diff awareness for the given path entry, or a fresh one if there is no
     * current one, or otherwise `null` if no factory could make a fresh one.
     */
    private fun maybeGetDiffAwarenessState(
        pathEntry: Root?,
        ignoredPaths: IgnoredSubdirectories?,
        options: com.google.devtools.common.options.OptionsProvider?
    ): DiffAwarenessState? {
        val stateKey = StateKey.Companion.create(pathEntry, ignoredPaths)
        var diffAwarenessState = currentDiffAwarenessStates.get(stateKey)
        if (diffAwarenessState != null) {
            return diffAwarenessState
        }

        for (factory in diffAwarenessFactories) {
            val newDiffAwareness: DiffAwareness? = factory.maybeCreate(pathEntry, ignoredPaths, options)
            if (newDiffAwareness != null) {
                logger.atInfo().log(
                    "Using %s DiffAwareness strategy for %s", newDiffAwareness.name(), pathEntry
                )
                diffAwarenessState = DiffAwarenessState(newDiffAwareness,  /*baselineView=*/null)
                currentDiffAwarenessStates.put(stateKey, diffAwarenessState)
                return diffAwarenessState
            }
        }
        return null
    }

    private inner class ProcessableModifiedFileSetImpl(
        modifiedFileSet: ModifiedFileSet?,
        pathEntry: Root?,
        ignoredPaths: IgnoredSubdirectories?,
        nextView: View
    ) : ProcessableModifiedFileSet {
        private val modifiedFileSet: ModifiedFileSet?
        private val pathEntry: Root?

        /**
         * The [View] that should be the baseline on the next [.getDiff] call after
         * [.markProcessed] is called.
         */
        private val nextView: View

        private val ignoredPaths: IgnoredSubdirectories?

        init {
            this.modifiedFileSet = modifiedFileSet
            this.pathEntry = pathEntry
            this.ignoredPaths = ignoredPaths
            this.nextView = nextView
        }

        override fun getModifiedFileSet(): ModifiedFileSet? {
            return modifiedFileSet
        }

        override fun getWorkspaceInfo(): WorkspaceInfoFromDiff? {
            return nextView.getWorkspaceInfo()
        }

        override fun markProcessed() {
            val stateKey = StateKey.Companion.create(pathEntry, ignoredPaths)
            val diffAwarenessState = currentDiffAwarenessStates.get(stateKey)
            if (diffAwarenessState != null) {
                diffAwarenessState.baselineView = nextView
                diffAwarenessState.cachedNewView = null
            }
        }
    }

    private class BrokenProcessableModifiedFileSet : ProcessableModifiedFileSet {
        override fun getModifiedFileSet(): ModifiedFileSet {
            return ModifiedFileSet.EVERYTHING_MODIFIED
        }

        override fun getWorkspaceInfo(): WorkspaceInfoFromDiff? {
            return null
        }

        override fun markProcessed() {}

        companion object {
            private val INSTANCE = BrokenProcessableModifiedFileSet()
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
