// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.FileStateType

/**
 * A helper class to find dirty [FileStateValue] and [DirectoryListingStateValue] nodes
 * based on a potentially incomplete diffs.
 * 
 * 
 * Infers directories from files meaning that it will work for diffs which exclude entries for
 * affected ancestor entries of nodes. It is also resilient to diffs which report only a root of
 * deleted subtree.
 */
class FileSystemValueCheckerInferringAncestors private constructor(
    tsgm: TimestampGranularityMonitor?,
    inMemoryGraph: InMemoryGraph,
    nodeStates: MutableMap<RootedPath?, NodeVisitState>,
    syscallCache: SyscallCache?,
    skyValueDirtinessChecker: SkyValueDirtinessChecker
) {
    private val tsgm: TimestampGranularityMonitor?
    private val inMemoryGraph: InMemoryGraph
    private val nodeStates: MutableMap<RootedPath?, NodeVisitState>
    private val syscallCache: SyscallCache?
    private val skyValueDirtinessChecker: SkyValueDirtinessChecker

    private val deletedDirectories: MutableSet<RootedPath> =
        com.google.common.collect.Sets.newConcurrentHashSet<RootedPath?>()
    private val valuesToInvalidate: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
    private val valuesToInject: ConcurrentMap<SkyKey?, Delta?> = ConcurrentHashMap<SkyKey?, Delta?>()

    private class NodeVisitState(collectMaybeDeletedChildren: Boolean) {
        private val childrenToProcess: AtomicInteger = AtomicInteger()

        // non-volatile since childrenToProcess ensures happens-before relationship.
        private var needsToBeVisited = false

        @kotlin.concurrent.Volatile
        private var isInferredDirectory = false

        @kotlin.concurrent.Volatile
        private var maybeDeletedChildren: MutableSet<String?>? = null

        init {
            if (collectMaybeDeletedChildren) {
                maybeDeletedChildren = ConcurrentHashMap.newKeySet<String?>()
            }
        }

        fun markInferredDirectory() {
            isInferredDirectory = true
            // maybeTypeChangedChildren is used to figure out if the entry is a directory, since we
            // already inferred it, we can stop collecting those.
            maybeDeletedChildren = null
        }

        fun addMaybeDeletedChild(child: String?) {
            val localMaybeDeletedChildren = maybeDeletedChildren
            if (localMaybeDeletedChildren != null) {
                localMaybeDeletedChildren.add(child)
            }
        }

        fun signalFinishedChild(needsToBeVisited: Boolean): Boolean {
            // The order is important, we must update this.needsToBeVisited before decrementing
            // childrenToProcess -- that operation ensures this change is visible to other threads doing
            // the same (including this thread picking up a true set by another one).
            if (needsToBeVisited) {
                this.needsToBeVisited = true
            }
            val childrenLeft: Int = childrenToProcess.decrementAndGet()
            // If we hit 0, we know that all other threads have set and propagated needsToBeVisited.
            return childrenLeft == 0 && this.needsToBeVisited
        }
    }

    init {
        this.tsgm = tsgm
        this.nodeStates = nodeStates
        this.syscallCache = syscallCache
        this.skyValueDirtinessChecker = skyValueDirtinessChecker
        this.inMemoryGraph = inMemoryGraph
    }

    @Throws(java.lang.InterruptedException::class)
    private fun processEntries(nThreads: Int): ImmutableDiff {
        val executor: ExecutorService = Executors.newFixedThreadPool(nThreads)

        // Materialize all leaves before scheduling them -- otherwise, we could race with the
        // processing code which decrements childrenToProcess.
        val leaves: com.google.common.collect.ImmutableList<java.util.concurrent.Callable<java.lang.Void?>?> =
            nodeStates.entries.stream()
                .filter { e: MutableMap.MutableEntry<RootedPath?, NodeVisitState?>? -> e!!.value.childrenToProcess.get() == 0 }
                .map<java.util.concurrent.Callable<java.lang.Void?>?> { e: MutableMap.MutableEntry<RootedPath?, NodeVisitState?>? ->
                    java.util.concurrent.Callable {
                        processEntry(e!!.key, e.value!!)
                        null
                    }
                }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<java.util.concurrent.Callable<java.lang.Void?>?>())

        val futures: MutableList<java.util.concurrent.Future<java.lang.Void?>> =
            executor.invokeAll<java.lang.Void?>(leaves)

        if (ExecutorUtil.interruptibleShutdown(executor)) {
            throw java.lang.InterruptedException()
        }

        for (future in futures) {
            try {
                com.google.common.util.concurrent.Futures.getDone(future)
            } catch (e: ExecutionException) {
                throw java.lang.IllegalStateException(e)
            }
        }

        // If any directory was deleted, invalidate all FSVs and DLSVs under it since the diff may only
        // report the root of the deleted subtree.
        if (!deletedDirectories.isEmpty()) {
            val treesToInvalidate: TreeSet<RootedPath> = TreeSet<RootedPath>(deletedDirectories)
            // Optimize the walk over all keys below by trimming those trees that are subtrees of other
            // trees to be invalidated. This allows for O(log r) lookup instead of O(n) for each key
            // where r is the number of deleted directories that aren't transitive subdirectories of other
            // deleted directories and n is the number of deleted directories.
            var lastTree: RootedPath? = null
            val treeIterator: MutableIterator<RootedPath> = treesToInvalidate.iterator()
            while (treeIterator.hasNext()) {
                val tree: RootedPath = treeIterator.next()
                if (lastTree != null && tree.asPath().startsWith(lastTree.asPath())) {
                    treeIterator.remove()
                } else {
                    lastTree = tree
                }
            }

            // FSVs and DLSVs do not track their parents, so we need to look at all keys.
            inMemoryGraph.parallelForEach(
                java.util.function.Consumer { entry: InMemoryNodeEntry? ->
                    val key: SkyKey? = entry.getKey()
                    val path: RootedPath
                    if (key is FileStateKey) {
                        path = key.argument()
                    } else if (key is com.google.devtools.build.lib.skyframe.DirectoryListingStateValue.Key) {
                        path = key.argument()
                    } else {
                        return@parallelForEach
                    }
                    val floorPath: RootedPath? = treesToInvalidate.floor(path)
                    if (floorPath != null && path.asPath().startsWith(floorPath.asPath())
                        && !valuesToInject.containsKey(key)
                    ) {
                        valuesToInvalidate.add(key)
                    }
                })
        }

        return ImmutableDiff(valuesToInvalidate, valuesToInject)
    }

    private fun processEntry(path: RootedPath, state: NodeVisitState) {
        var path: RootedPath = path
        var state = state
        val rootParentSentinel: NodeVisitState =
            com.google.devtools.build.lib.skyframe.FileSystemValueCheckerInferringAncestors.NodeVisitState( /* collectMaybeDeletedChildren= */
                false
            )

        while (state != rootParentSentinel) {
            val parentPath: RootedPath? = path.getParentDirectory()
            val parentState: NodeVisitState =
                (if (parentPath != null) nodeStates.get(parentPath) else rootParentSentinel)!!
            val visitParent =
                visitEntry(path, state.isInferredDirectory, state.maybeDeletedChildren, parentState)
            val processParent = parentState.signalFinishedChild(visitParent)

            if (!processParent) {
                // This is a tree, only one child can trigger parent processing.
                return
            }

            state = parentState
            path = path.getParentDirectory()
        }
    }

    /**
     * Visits the given node and return whether the type of it may have changed.
     * 
     * 
     * Returns false if we know that the type has not changed. It may however return true if the
     * type has not changed.
     * 
     * @param isInferredDirectory whether the node was already inferred as a directory from children.
     * @param maybeDeletedChildren if not null, exhaustive list of all children which may have their
     * file system type changed (including deletions).
     */
    private fun visitEntry(
        path: RootedPath,
        isInferredDirectory: Boolean,
        maybeDeletedChildren: MutableSet<String?>?,
        parentState: NodeVisitState
    ): Boolean {
        val key: FileStateKey = FileStateValue.key(path)
        val fsvNode: InMemoryNodeEntry? = inMemoryGraph.getIfPresent(key)
        val oldFsv: FileStateValue? = if (fsvNode != null) fsvNode.toValue() as FileStateValue? else null
        if (oldFsv == null) {
            try {
                visitUnknownEntry(key, isInferredDirectory, parentState)
            } catch (e: IOException) {
                valuesToInvalidate.add(key)
            }
            parentState.addMaybeDeletedChild(path.getRootRelativePath().getBaseName())
            return true
        }

        if (isInferredDirectory
            || (maybeDeletedChildren != null
                    && listingHasEntriesOutsideOf(path, maybeDeletedChildren))
        ) {
            parentState.markInferredDirectory()
            if (oldFsv.getType().isDirectory()) {
                return false
            }
            try {
                val directoryFileStateNodeMtsv: com.google.devtools.build.skyframe.Version? =
                    skyValueDirtinessChecker.getMaxTransitiveSourceVersionForNewValue(
                        key, FileStateValue.DIRECTORY_FILE_STATE_NODE
                    )
                valuesToInject.put(
                    key,
                    Delta.justNew(FileStateValue.DIRECTORY_FILE_STATE_NODE, directoryFileStateNodeMtsv)
                )
            } catch (e: IOException) {
                valuesToInvalidate.add(key)
            }
            parentListingKey(path).ifPresent(java.util.function.Consumer { e: com.google.devtools.build.lib.skyframe.DirectoryListingStateValue.Key? ->
                valuesToInvalidate.add(
                    e
                )
            })
            return true
        }

        val newFsv: FileStateValue
        try {
            newFsv = injectAndGetNewFileStateValueIfDirty(fsvNode, oldFsv)
        } catch (e: IOException) {
            valuesToInvalidate.add(key)
            parentState.addMaybeDeletedChild(path.getRootRelativePath().getBaseName())
            parentListingKey(path).ifPresent(java.util.function.Consumer { e: com.google.devtools.build.lib.skyframe.DirectoryListingStateValue.Key? ->
                valuesToInvalidate.add(
                    e
                )
            })
            if (oldFsv.isDirectory()) {
                deletedDirectories.add(path)
            }
            return true
        }
        if (newFsv.getType().exists()) {
            parentState.markInferredDirectory()
        } else if (oldFsv.getType().exists()) {
            // exists -> not exists -- deletion.
            parentState.addMaybeDeletedChild(path.getRootRelativePath().getBaseName())
        }

        val typeChanged = newFsv.getType() !== oldFsv.getType()
        if (typeChanged) {
            parentListingKey(path).ifPresent(java.util.function.Consumer { e: com.google.devtools.build.lib.skyframe.DirectoryListingStateValue.Key? ->
                valuesToInvalidate.add(
                    e
                )
            })
            if (oldFsv.isDirectory() && !newFsv.exists()) {
                deletedDirectories.add(path)
            }
        } else if (skyValueDirtinessChecker.invalidateListingsOnFileModification()) {
            parentListingKey(path).ifPresent(java.util.function.Consumer { e: com.google.devtools.build.lib.skyframe.DirectoryListingStateValue.Key? ->
                valuesToInvalidate.add(
                    e
                )
            })
        }
        return typeChanged
    }

    /**
     * Injects the new file state value if dirty. Returns the old file state value if not dirty and
     * the new file state value if dirty.
     */
    @Throws(IOException::class)
    private fun injectAndGetNewFileStateValueIfDirty(
        oldFsvNode: InMemoryNodeEntry, oldFsv: FileStateValue
    ): FileStateValue {
        com.google.common.base.Preconditions.checkState(oldFsv != null, "Unexpected null FileStateValue.")
        val oldMtsv: com.google.devtools.build.skyframe.Version? = oldFsvNode.getMaxTransitiveSourceVersion()
        val dirtyResult: DirtyResult =
            skyValueDirtinessChecker.check(oldFsvNode.getKey(), oldFsv, oldMtsv, syscallCache, tsgm)
        if (!dirtyResult.isDirty()) {
            return oldFsv
        }
        val newFsv: FileStateValue = dirtyResult.getNewValue() as FileStateValue
        val newMtsv: com.google.devtools.build.skyframe.Version? = dirtyResult.getNewMaxTransitiveSourceVersion()
        valuesToInject.put(oldFsvNode.getKey(), Delta.justNew(newFsv, newMtsv))
        return newFsv
    }

    @Throws(IOException::class)
    private fun visitUnknownEntry(
        key: FileStateKey, isInferredDirectory: Boolean, parentState: NodeVisitState
    ) {
        val path: RootedPath = key.argument()
        // Run stats on unknown files in order to preserve the parent listing if present unless we
        // already know it has changed.
        val parentListingKey: java.util.Optional<com.google.devtools.build.lib.skyframe.DirectoryListingStateValue.Key?> =
            parentListingKey(path)
        var parentListing: DirectoryListingStateValue? = null
        if (parentListingKey.isPresent()) {
            val entry: InMemoryNodeEntry? = inMemoryGraph.getIfPresent(parentListingKey.get())
            parentListing =
                if (entry != null && entry.isDone()) entry.getValue() as DirectoryListingStateValue? else null
        }

        // No listing/we already know it has changed -- nothing to gain from stats anymore.
        if (parentListing == null || valuesToInvalidate.contains(parentListingKey.get())) {
            if (isInferredDirectory) {
                parentState.markInferredDirectory()
            }
            valuesToInvalidate.add(key)
            parentListingKey.ifPresent(java.util.function.Consumer { e: com.google.devtools.build.lib.skyframe.DirectoryListingStateValue.Key? ->
                valuesToInvalidate.add(
                    e
                )
            })
            return
        }

        // We don't take advantage of isInferredDirectory because we set it only in cases of a present
        // descendant/done listing which normally cannot exist without having FileStateValue for
        // ancestors.
        val newValue: FileStateValue = injectAndGetNewFileStateValueForUnknownEntry(path, key)

        if (isInferredDirectory || newValue.getType().exists()) {
            parentState.markInferredDirectory()
        }

        val dirent: com.google.devtools.build.lib.vfs.Dirent? =
            parentListing.getDirents().maybeGetDirent(path.getRootRelativePath().getBaseName())
        val typeInListing: com.google.devtools.build.lib.vfs.Dirent.Type? =
            if (dirent != null) dirent.getType() else null
        if (typeInListing != direntTypeFromFileStateType(newValue.getType()) || skyValueDirtinessChecker.invalidateListingsOnFileModification()) {
            valuesToInvalidate.add(parentListingKey.get())
        }
    }

    /** Injects the new file state value for unknown entry.  */
    @Throws(IOException::class)
    private fun injectAndGetNewFileStateValueForUnknownEntry(path: RootedPath?, key: SkyKey?): FileStateValue {
        val newValue: FileStateValue =
            skyValueDirtinessChecker.createNewValue(path, syscallCache, tsgm) as FileStateValue
        val newMtsv: com.google.devtools.build.skyframe.Version? =
            skyValueDirtinessChecker.getMaxTransitiveSourceVersionForNewValue(key, newValue)
        valuesToInject.put(key, Delta.justNew(newValue, newMtsv))
        return newValue
    }

    private fun listingHasEntriesOutsideOf(path: RootedPath?, allAffectedEntries: MutableSet<String?>): Boolean {
        // TODO(192010830): Try looking up BUILD files if there is no listing -- this is a lookup we
        //  can speculatively try since those files are often checked against.
        val nodeEntry: InMemoryNodeEntry? = inMemoryGraph.getIfPresent(DirectoryListingStateValue.Companion.key(path))
        val listing: DirectoryListingStateValue? =
            if (nodeEntry != null && nodeEntry.isDone())
                nodeEntry.getValue() as DirectoryListingStateValue?
            else
                null
        if (listing == null) {
            return false
        }
        for (entry in listing.getDirents()) {
            if (!allAffectedEntries.contains(entry.getName())) {
                return true
            }
        }
        return false
    }

    companion object {
        @com.google.common.annotations.VisibleForTesting
        @Throws(java.lang.InterruptedException::class)
        fun getDiffWithInferredAncestors(
            tsgm: TimestampGranularityMonitor?,
            inMemoryGraph: InMemoryGraph,
            modifiedKeys: Iterable<FileStateKey>,
            nThreads: Int,
            syscallCache: SyscallCache?,
            skyValueDirtinessChecker: SkyValueDirtinessChecker
        ): ImmutableDiff {
            val nodeStates: MutableMap<RootedPath?, NodeVisitState?> = makeNodeVisitStates(modifiedKeys)
            return FileSystemValueCheckerInferringAncestors(
                tsgm,
                inMemoryGraph,
                Collections.unmodifiableMap<RootedPath?, NodeVisitState?>(nodeStates),
                syscallCache,
                skyValueDirtinessChecker
            )
                .processEntries(nThreads)
        }

        private fun makeNodeVisitStates(
            modifiedKeys: Iterable<FileStateKey>
        ): MutableMap<RootedPath?, NodeVisitState?> {
            val nodeStates: MutableMap<RootedPath?, NodeVisitState?> = HashMap<RootedPath?, NodeVisitState?>()
            for (fileStateKey in modifiedKeys) {
                val top: RootedPath = fileStateKey.argument()
                // Start with false since the reported diff does not mean we are adding a child.
                var lastCreated = false
                var path: RootedPath? = top
                while (path != null) {
                    val existingState = nodeStates.get(path)
                    val state: NodeVisitState?
                    // We disable the optimization which detects whether directory still exists based on the
                    // list of deleted children and listing. It is possible for the diff to report a deleted
                    // directory without listing all of the files under it as deleted.
                    if (existingState == null) {
                        state =
                            com.google.devtools.build.lib.skyframe.FileSystemValueCheckerInferringAncestors.NodeVisitState( /* collectMaybeDeletedChildren= */
                                path != top
                            )
                        nodeStates.put(path, state)
                    } else {
                        state = existingState
                        if (path === top) {
                            state.maybeDeletedChildren = null
                        }
                    }
                    if (lastCreated) {
                        state.childrenToProcess.incrementAndGet()
                    }
                    lastCreated = existingState == null
                    path = path.getParentDirectory()
                }
            }
            return nodeStates
        }

        private fun parentListingKey(path: RootedPath): java.util.Optional<com.google.devtools.build.lib.skyframe.DirectoryListingStateValue.Key?> {
            return java.util.Optional.ofNullable<RootedPath?>(path.getParentDirectory())
                .map<com.google.devtools.build.lib.skyframe.DirectoryListingStateValue.Key?>(java.util.function.Function { rootedPath: RootedPath? ->
                    DirectoryListingStateValue.Companion.key(rootedPath)
                })
        }

        private fun direntTypeFromFileStateType(type: FileStateType): com.google.devtools.build.lib.vfs.Dirent.Type? {
            return when (type) {
                NONEXISTENT -> null
                REGULAR_FILE -> com.google.devtools.build.lib.vfs.Dirent.Type.FILE
                SPECIAL_FILE -> com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN
                SYMLINK -> com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK
                DIRECTORY -> com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY
            }
        }
    }
}
