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
package com.google.devtools.build.lib.skyframe

/**
 * SkyframeFocuser is a minimizing optimizer (i.e. garbage collector) for the Skyframe graph, based
 * on a set of known inputs known as active directories, while ensuring correct incremental builds.
 * 
 * 
 * This is also a subclass of [AbstractQueueVisitor] to take advantage of highly
 * parallelizable operations over the Skyframe graph.
 */
class SkyframeFocuser private constructor(graph: InMemoryGraph, actionCache: ActionCache?) :
    AbstractQueueVisitor( /* parallelism= */
        java.lang.Runtime.getRuntime().availableProcessors(),  /* keepAliveTime= */
        2,
        TimeUnit.MINUTES,
        ExceptionHandlingMode.FAIL_FAST,  /* poolName= */
        "skyframe-focuser",
        ErrorClassifier.DEFAULT
    ) {
    // The in-memory Skyframe graph
    private val graph: InMemoryGraph

    // Can be null with --nouse_action_cache.
    private val actionCache: ActionCache?

    init {
        this.graph = graph
        this.actionCache = actionCache
    }

    /**
     * The result of running Skyfocus. The actual changes are done in place with the in-memory graph.
     * 
     * @param roots the SkyKeys of the roots to be kept, i.e. the top level keys.
     * @param leafs the SkyKeys of the leafs to be kept. This is the "active directories".
     * @param deps the SkyKeys that are in the dependencies of all roots, and rdeps from the leafs.
     * May contain transitive dependencies, in cases where certain functions use them without
     * establishing a Skyframe dependency.
     * @param rdeps the SkyKeys that are in the reverse dependencies of the leafs.
     * @param verificationSet the SkyKeys that are in the transitive closure of the roots, but not in
     * the active directories. These SkyKeys are also retained in the graph, because [     ] uses them to check for dirty keys to be invalidated on each new
     * build.
     * @param rdepEdgesBefore The number of reverse edges in the visited nodes by Skyfocus (before
     * removal).
     * @param rdepEdgesAfter The number of reverse edges in the visited nodes after Skyfocus completes
     * (after removal).
     */
    class FocusResult(
        roots: com.google.common.collect.ImmutableSet<SkyKey?>?,
        leafs: com.google.common.collect.ImmutableSet<SkyKey?>?,
        rdeps: com.google.common.collect.ImmutableSet<SkyKey?>?,
        deps: com.google.common.collect.ImmutableSet<SkyKey?>?,
        verificationSet: com.google.common.collect.ImmutableSet<SkyKey?>?,
        rdepEdgesBefore: Long,
        rdepEdgesAfter: Long
    ) {
        val roots: com.google.common.collect.ImmutableSet<SkyKey?>?
        val leafs: com.google.common.collect.ImmutableSet<SkyKey?>?
        val rdeps: com.google.common.collect.ImmutableSet<SkyKey?>?
        val deps: com.google.common.collect.ImmutableSet<SkyKey?>?
        val verificationSet: com.google.common.collect.ImmutableSet<SkyKey?>?
        val rdepEdgesBefore: Long
        val rdepEdgesAfter: Long

        init {
            this.roots = roots
            this.leafs = leafs
            this.rdeps = rdeps
            this.deps = deps
            this.verificationSet = verificationSet
            this.rdepEdgesBefore = rdepEdgesBefore
            this.rdepEdgesAfter = rdepEdgesAfter
        }
    }

    /**
     * NodeVisitor is parallelizable graph visitor that's applied transitively upwards from leafs to
     * the roots, while marking rdeps and all direct deps of those rdeps to be kept by [ ].
     * 
     * 
     * It also collects the verification set in the downward transitive closure along the way. See
     * [CollectVerificationSet].
     */
    private inner class SkyfocusNodeVisitor(
        key: SkyKey,
        keptRdeps: MutableSet<SkyKey>,
        keptDeps: MutableSet<SkyKey>,
        verificationSet: MutableSet<SkyKey>,
        verificationSetSeen: MutableSet<SkyKey?>
    ) : java.lang.Runnable {
        // The SkyKey that this NodeVisitor is responsible for.
        private val key: SkyKey

        // Threadsafe set of keys that depend on this key. May be modified by multiple NodeVisitors
        // concurrently.
        private val keptRdeps: MutableSet<SkyKey>

        // Threadsafe set of (mostly direct) dep keys that this key depends on. May be modified by
        // multiple NodeVisitors concurrently.
        private val keptDeps: MutableSet<SkyKey>

        // Threadsafe set of *leaf* keys that this key depends on, but are external to the active
        // directories.
        private val verificationSet: MutableSet<SkyKey>

        // Threadsafe set of keys that keeps track of the keys that have been visited while
        // constructing the verification set, so we do not visit the same subgraph more than once.
        // May be modified by multiple CollectVerificationSet visitors concurrently.
        private val verificationSetSeen: MutableSet<SkyKey?>

        init {
            this.key = key
            this.keptRdeps = keptRdeps
            this.keptDeps = keptDeps
            this.verificationSet = verificationSet
            this.verificationSetSeen = verificationSetSeen
        }

        override fun run() {
            val nodeEntry: InMemoryNodeEntry? = graph.getIfPresent(key)
            checkNotNull(nodeEntry) { "nodeEntry not found for: " + key.getCanonicalName() }

            if (!nodeEntry.isDone()) {
                if (nodeEntry.getLifecycleState() == LifecycleState.CHECK_DEPENDENCIES) {
                    // When building a new top level target, the updated BUILD_ID precomputed value will
                    // invalidate all of its reverse dependencies, and depending on what's being built,
                    // some of them may remain in the CHECK_DEPENDENCIES state, and not done.
                    //
                    // For these, just ignore them and keep them in the graph, since they may be used for
                    // a subsequent build.
                    keptRdeps.remove(key)
                    return
                }

                // TODO: b/312819241 - handle this gracefully without throwing.
                throw java.lang.IllegalStateException("nodeEntry not done: " + key.getCanonicalName())
            }

            var rdepCount = 0
            for (rdep in nodeEntry.getReverseDepsForDoneEntry()) {
                rdepCount++
                if (!keptRdeps.add(rdep)) {
                    // Memoization. Already processed.
                    continue
                }

                // Queue a traversal up the graph. This will not create duplicate NodeVisitors on the
                // same rdep due to the atomic keptRdeps.add check above.
                execute(
                    SkyfocusNodeVisitor(
                        rdep, keptRdeps, keptDeps, verificationSet, verificationSetSeen
                    )
                )
            }
            if (rdepCount > RDEP_WARNING_THRESHOLD) {
                logger.atWarning().log(
                    "%s has %d rdeps, which is more than the threshold at %d.",
                    key.getCanonicalName(), rdepCount, RDEP_WARNING_THRESHOLD
                )
            }

            var depCount = 0
            for (dep in nodeEntry.getDirectDeps()) {
                depCount++
                if (!keptDeps.add(dep)) {
                    // Memoization. Already processed.
                    continue
                }

                maybeCollectVerificationSet(dep)

                // This is necessary to keep the action inputs encapsulated by a NestedSet. Otherwise,
                // those inputs will be missing. ActionExecutionFunction#lookupInput allows getting a
                // transitive dep without adding a SkyframeDependency on it.
                if (dep is ArtifactNestedSetKey) {
                    for (a in dep.expandToArtifacts()) {
                        val aKey: SkyKey? = Artifact.key(a)
                        if (keptDeps.add(aKey)) {
                            maybeCollectVerificationSet(aKey)
                        }
                    }
                }
            }
            if (depCount > DEP_WARNING_THRESHOLD) {
                logger.atWarning().log(
                    "%s has %d deps, which is more than the threshold at %d.",
                    key.getCanonicalName(), depCount, DEP_WARNING_THRESHOLD
                )
            }
        }

        /**
         * Pre-check optimizations to avoid creating new CollectVerificationSet Runnables, instead of
         * returning early after creating and executing one.
         */
        fun maybeCollectVerificationSet(k: SkyKey?) {
            if (keptRdeps.contains(k)) {
                // In the active directories reverse TC, already visited.
                return
            }

            if (isVerificationSetKeyType(k)) {
                verificationSet.add(k)
                return
            }

            if (!verificationSetSeen.add(k)) {
                // This contains all visited keys, so we don't visit the same key twice if
                // CollectVerificationSet was called from multiple rdeps on the same key.
                return
            }

            execute(CollectVerificationSet(k))
        }

        /**
         * The verification set keeps track when a file outside the active directories is changed,
         * because those builds will not be incrementally correct unless a reanalysis is done to restore
         * the Skyframe graph of those files.
         * 
         * 
         * Technically, CollectVerificationSet is applied downwards on the indirect dependencies of
         * the active directories's reverse transitive closure, and is responsible for collecting the
         * necessary leaf SkyKeys, except the active directories itself.
         * 
         * 
         * TODO: b/327545930 - make this run faster.
         */
        private inner class CollectVerificationSet(key: SkyKey?) : java.lang.Runnable {
            private val key: SkyKey?

            init {
                this.key = key
            }

            /**
             * Continue downward traversal. The collection is done in [ ][SkyfocusNodeVisitor.maybeCollectVerificationSet].
             */
            override fun run() {
                val nodeEntry: InMemoryNodeEntry? = graph.getIfPresent(key)
                com.google.common.base.Preconditions.checkNotNull<InMemoryNodeEntry?>(nodeEntry)
                nodeEntry.getDirectDeps().forEach(java.util.function.Consumer { k: SkyKey? ->
                    this@SkyfocusNodeVisitor.maybeCollectVerificationSet(k)
                })
            }
        }
    }


    /** Entry point of the Skyframe garbage collection algorithm.  */
    @Throws(java.lang.InterruptedException::class)
    private fun run(roots: MutableSet<SkyKey>, leafs: MutableSet<SkyKey>): FocusResult {
        val keptDeps: MutableSet<SkyKey> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
        val keptRdeps: MutableSet<SkyKey> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
        val verificationSet: MutableSet<SkyKey> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()

        // All leafs are automatically considered as rdeps.
        keptRdeps.addAll(leafs)

        // All roots are automatically considered as deps.
        //
        // Some roots are re-evaluated on every build. These roots may not be in the reverse TC
        // of leafs (active directories), but may influence how the active directories is evaluated
        // (e.g. platform
        // mapping). If we remove them from the graph, those keys may be re-evaluated anyway (along with
        // their TC) on subsequent invocations, leading to wasted compute and RAM.
        // The exercise of ensuring which roots should be kept is left to the caller of
        // this function, but we ensure that all specified ones are kept here.
        keptDeps.addAll(roots)

        Profiler.instance().profile("focus.mark").use { c ->
            val verificationSetSeen: MutableSet<SkyKey?> =
                com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
            // Start traversal from leafs.
            for (leaf in leafs) {
                execute(
                    SkyfocusNodeVisitor(
                        leaf, keptRdeps, keptDeps, verificationSet, verificationSetSeen
                    )
                )
            }
            awaitQuiescenceWithoutShutdown(true)
        }
        // Keep the rdeps transitive closure from leafs distinct from the deps.
        keptDeps.removeAll(keptRdeps)

        // Ensure that the verification set doesn't contain any direct deps to build the
        // active directories.
        verificationSet.removeAll(keptDeps)

        val rdepEdgesBefore: AtomicLong = AtomicLong()
        val rdepEdgesAfter: AtomicLong = AtomicLong()

        Profiler.instance().profile("focus.sweep").use { c ->
            graph.parallelForEach(
                java.util.function.Consumer { inMemoryNodeEntry: InMemoryNodeEntry? ->
                    val key: SkyKey? = inMemoryNodeEntry.getKey()
                    if (keptRdeps.contains(key)) {
                        return@parallelForEach
                    }

                    if (keptDeps.contains(key)) {
                        val incrementalInMemoryNodeEntry: IncrementalInMemoryNodeEntry =
                            inMemoryNodeEntry as IncrementalInMemoryNodeEntry

                        // No need to keep the direct deps edges of existing deps. For example:
                        //
                        //    B
                        //  / |\
                        // A C  \
                        //    \ |
                        //     D
                        //
                        // B is the root, and A is the only leaf. We can throw out the CD edge, even
                        // though both C and D are still used by B. This is because no changes are expected to
                        // C and D, so it's unnecessary to maintain the edges.
                        incrementalInMemoryNodeEntry.clearDirectDepsForSkyfocus()

                        // No need to keep the rdep edges of the deps if they do not point to an rdep
                        // reachable (hence, dirty-able) by the active directories.
                        //
                        // This accounts for nearly 5% of 9+GB retained heap on a large server build.
                        val existingRdeps: MutableCollection<SkyKey?> =
                            incrementalInMemoryNodeEntry.getReverseDepsForDoneEntry()
                        rdepEdgesBefore.getAndAdd(existingRdeps.size.toLong())
                        var rdepEdgesKept = 0
                        for (rdep in existingRdeps) {
                            if (keptRdeps.contains(rdep)) {
                                rdepEdgesKept++
                            } else {
                                incrementalInMemoryNodeEntry.removeReverseDep(rdep)
                            }
                        }
                        rdepEdgesAfter.getAndAdd(rdepEdgesKept.toLong())

                        // This calls ReverseDepsUtility.consolidateData().
                        incrementalInMemoryNodeEntry.consolidateReverseDeps()

                        return@parallelForEach
                    }

                    if (verificationSet.contains(key)) {
                        // TODO: b/327545930 - fsvc supports checking keys with missing values in the graph
                        // using `FileSystemValueCheckerInferringAncestors#visitUnknownEntry`, so perhaps we
                        // could drop the nodes here, but that doesn't (yet) work with LocalDiffAwareness.
                        //
                        // For now, keep the nodes in the verification set because the
                        // fsvc#getDirtyKeys relies on their existence in the graph to check for
                        // dirty keys to invalidate.
                        //
                        // Also remove all rdep edges from the verification set and make it flat.
                        val rdeps: MutableCollection<SkyKey> = inMemoryNodeEntry.getReverseDepsForDoneEntry()
                        rdepEdgesBefore.getAndAdd(rdeps.size.toLong())
                        for (rdep in rdeps) {
                            inMemoryNodeEntry.removeReverseDep(rdep)
                        }

                        return@parallelForEach
                    }

                    if (!inMemoryNodeEntry.isDone()) {
                        // Don't remove undone nodes -- these are nodes that were already in the graph,
                        // but invalidated and not evaluated in this current invocation.
                        return@parallelForEach
                    }

                    if (actionCache != null
                        && inMemoryNodeEntry.getValue() is ActionLookupValue
                    ) {
                        for (a in alv.getActions()) {
                            for (output in a.getOutputs()) {
                                actionCache.remove(output.getExecPathString())
                            }
                        }
                    }
                    graph.remove(key)
                })
            graph.shrinkNodeMap()

            awaitQuiescence(true) // and shut down the ExecutorService.
        }
        return FocusResult(
            com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(roots),
            com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(leafs),
            com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(keptRdeps),
            com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(keptDeps),
            com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(verificationSet),
            rdepEdgesBefore.get(),
            rdepEdgesAfter.get()
        )
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private const val RDEP_WARNING_THRESHOLD = 10000
        private const val DEP_WARNING_THRESHOLD = 10000

        private fun isVerificationSetKeyType(k: SkyKey?): Boolean {
            return k is RootedPath || k is DirectoryListingStateValue.Key
        }

        /**
         * Minimize the Skyframe graph by traverse it to prune nodes and edges that are not necessary for
         * the build correctness of a active directories of files. The graph focusing algorithm pseudocode
         * is as follows.
         * 
         * 
         *  1. Mark all the leafs and their transitive rdeps. For each marked node, also mark all their
         * direct dependencies. An injectable function can also mark additional nodes reachable from
         * the node itself.
         *  1. For each marked node, remove all direct deps edges. Also remove all rdep edges unless
         * they point to a rdep that should be kept. This creates the "flattened verification set".
         * 
         * 
         * @param graph the in-memory graph to operate on
         * @param roots the SkyKeys of the roots to be kept, i.e. the top level keys.
         * @param leafs the SkyKeys of the leafs to be kept. This is the "active directories".
         * @return the set of kept SkyKeys in the in-memory graph, categorized by deps and rdeps.
         */
        @Throws(java.lang.InterruptedException::class)
        fun focus(
            graph: InMemoryGraph, actionCache: ActionCache?, roots: MutableSet<SkyKey>, leafs: MutableSet<SkyKey>
        ): FocusResult {
            val focuser = SkyframeFocuser(graph, actionCache)
            return focuser.run(roots, leafs)
        }
    }
}
