// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.actions.ActionLookupData

/**
 * Implements frontier serialization with pprof dumping using `--experimental_remote_analysis_cache_mode=upload`.
 */
object FrontierSerializer {
    /**
     * Serializes the frontier contained in the current Skyframe graph into a [ProfileCollector]
     * writing the resulting proto to `path`.
     * 
     * @return empty if successful, otherwise a result containing the appropriate error
     */
    @Throws(java.lang.InterruptedException::class)
    fun serializeAndUploadFrontier(
        serializationDependenciesProvider: SerializationDependenciesProvider,
        evaluator: MemoizingEvaluator,
        versionGetter: LongVersionGetter?,
        reporter: com.google.devtools.build.lib.events.Reporter,
        eventBus: com.google.common.eventbus.EventBus?,
        keepStateAfterBuild: Boolean
    ): java.util.Optional<FailureDetail?> {
        var versionGetter: LongVersionGetter? = versionGetter
        val graph: InMemoryGraph = evaluator.getInMemoryGraph()
        val stopwatch: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()

        val selectionResult =
            computeSelectionResult(
                graph,
                serializationDependenciesProvider.getActiveDirectoriesMatcher(),  /* traversalMode= */
                TraversalMode.FOR_SERIALIZATION,
                serializationDependenciesProvider.getSkycacheAnalysisOnly()
            )
        val selectedKeys: com.google.common.collect.ImmutableSet<SkyKey?> = selectionResult.selectedKeys()
        if (!keepStateAfterBuild && serializationDependenciesProvider.shouldMinimizeMemory()) {
            clearActionLookupValues(graph, selectedKeys)
        }

        reporter.handle(
            com.google.devtools.build.lib.events.Event.info(
                java.lang.String.format(
                    "Found %d active or frontier keys in %s", selectedKeys.size(), stopwatch
                )
            )
        )
        stopwatch.reset().start()

        if (serializationDependenciesProvider.mode()
            == RemoteAnalysisCacheMode.DUMP_UPLOAD_MANIFEST_ONLY
        ) {
            reporter.handle(
                com.google.devtools.build.lib.events.Event.warn("Dry run of upload, dumping selection to stdout (warning: can be large!)")
            )
            dumpUploadManifest(
                PrintStream(
                    BufferedOutputStream(reporter.getOutErr().getOutputStream(), 1024 * 1024)
                ),
                selectionResult.selection
            )
            return java.util.Optional.empty<FailureDetail?>()
        }

        val codecs: ObjectCodecs =
            java.util.Objects.requireNonNull<ObjectCodecs>(serializationDependenciesProvider.getObjectCodecs())
        val frontierVersion: FrontierNodeVersion? = serializationDependenciesProvider.getSkyValueVersion()
        val profilePath: String = serializationDependenciesProvider.getSerializedFrontierProfile()
        val profileCollector: ProfileCollector? = if (profilePath.isEmpty()) null else ProfileCollector()
        val serializationStats: SerializationStats = SerializationStats()

        if (versionGetter == null) {
            if (TestType.isInTest()) {
                versionGetter = LongVersionGetterTestInjection.getVersionGetterForTesting()
            } else {
                throw java.lang.NullPointerException("missing versionGetter")
            }
        }

        if (!keepStateAfterBuild) {
            // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
            // INCREMENTALITY PITFALLS WARNING
            // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
            //
            // The following code is not safe to run if the Skyframe graph needs to be
            // incrementally correct after this point.
            //
            // We only do this if --nokeep_state_after_build is set.
            Profiler.instance().profile(null, "reclaimMemoryFromSkyframe").use { ignored ->
                // TODO: More ideas, while keeping the constraint that we need the
                // structure of the selection and DTC to be able to compute the
                // FileOpNodes MTSV metadata (File, DirectoryListing) for invalidation.
                // - Delete SkyValues of nodes in the DTC, because the values are not needed by
                // SelectedEntrySerializer.
                // - Delete entire nodes not in selection and DTC, because they are never traversed in
                // SelectedEntrySerializer.
                stopwatch.reset().start()
                // saves about 8% RAM b/418730298#comment26
                val deletionStats =
                    deleteNodesAndRdeps(graph, evaluator.getNodesToRemoveBeforeFrontierSerialization())
                reporter.handle(
                    com.google.devtools.build.lib.events.Event.info(
                        java.lang.String.format(
                            "%s nodes and %s rdeps deleted to reclaim memory, took %s",
                            deletionStats.nodes, deletionStats.rdeps, stopwatch
                        )
                    )
                )
            }
        }

        stopwatch.reset().start()
        val writeStatus: com.google.common.util.concurrent.ListenableFuture<com.google.common.collect.ImmutableList<Throwable?>?> =
            SelectedEntrySerializer.Companion.uploadSelection(
                graph,
                versionGetter,
                codecs,
                frontierVersion,
                selectedKeys,
                serializationDependenciesProvider.getFingerprintValueService(),
                serializationDependenciesProvider.getFileInvalidationWriter(),
                eventBus,
                profileCollector,
                serializationStats
            )

        try {
            // Waits for the write to complete uninterruptibly. This avoids returning to the caller
            // while underlying worker threads are still processing.
            val errors: com.google.common.collect.ImmutableList<Throwable?>? =
                com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly<com.google.common.collect.ImmutableList<Throwable?>?>(
                    writeStatus
                )
            if (!errors.isEmpty()) {
                val message: String = ErrorMessageHelper.getErrorMessage(errors)
                reporter.error( /* location= */null, message, errors.get(0))
                return java.util.Optional.of<FailureDetail?>(
                    createFailureDetail(
                        message,
                        Code.SERIALIZED_FRONTIER_PROFILE_FAILED
                    )
                )
            }

            val stats: FingerprintValueStore.Stats =
                serializationDependenciesProvider.getFingerprintValueService().getStats()

            reporter.handle(
                com.google.devtools.build.lib.events.Event.info(
                    java.lang.String.format(
                        "Serialized %s/%s analysis/execution nodes into %s/%s key/value bytes and %s"
                                + " entries (%s batches) in %s",
                        serializationStats.analysisNodes(),
                        serializationStats.executionNodes(),
                        stats.keyBytesSent,
                        stats.valueBytesSent,
                        stats.entriesWritten,
                        stats.setBatches,
                        stopwatch
                    )
                )
            )
        } catch (e: ExecutionException) {
            // The writeStatus future is not known to throw any ExecutionExceptions.
            val cause: Throwable = e.getCause()
            val message =
                ("with unexpected exception type "
                        + cause.getClass().getName()
                        + ": "
                        + cause.getMessage())
            reporter.error( /* location= */null, message, cause)
            return java.util.Optional.of<FailureDetail?>(
                createFailureDetail(
                    message,
                    Code.SERIALIZED_FRONTIER_PROFILE_FAILED
                )
            )
        }

        if (profilePath.isEmpty()) {
            return java.util.Optional.empty<FailureDetail?>()
        }

        try {
            FileOutputStream(profilePath).use { fileOutput ->
                BufferedOutputStream(fileOutput).use { bufferedOutput ->
                    profileCollector.toProto().writeTo(bufferedOutput)
                }
            }
        } catch (e: IOException) {
            val message = "Error writing serialization profile to file: " + e.getMessage()
            reporter.error(null, message, e)
            return java.util.Optional.of<FailureDetail?>(
                createFailureDetail(
                    message,
                    Code.SERIALIZED_FRONTIER_PROFILE_FAILED
                )
            )
        }
        return java.util.Optional.empty<FailureDetail?>()
    }

    /**
     * Discards unneeded `PackageValue`s and `BzlLoadValue`s from the graph after
     * analysis.
     * 
     * 
     * This is a memory optimization to reduce peak heap before the execution phase. It finds all
     * packages associated with selected `ConfiguredTargetValue`s and clears the values of all
     * other `PackageValue` nodes. The graph is mutilated after this and incremental builds are
     * not possible.
     */
    fun computeSelectionAndMinimizeMemory(
        graph: InMemoryGraph,
        topLevelTargets: MutableCollection<Label?>,
        activeDirectoriesMatcher: java.util.Optional<java.util.function.Predicate<PackageIdentifier?>?>
    ) {
        // At this point (post-analysis), ExecutionPhaseSkyKeys do not exist in the graph,
        // making the skycacheAnalysisOnly setting technically non-consequential.
        val selectionResult =
            computeSelectionResult(
                graph,
                activeDirectoriesMatcher,  /* traversalMode= */
                TraversalMode.FOR_POST_ANALYSIS_MINIMIZE_MEMORY,  /* skycacheAnalysisOnly= */
                false
            )
        val selectedKeys: com.google.common.collect.ImmutableSet<SkyKey?> = selectionResult.selection.keySet()
        val packageIdentifierSet: MutableSet<PackageIdentifier?> =
            com.google.common.collect.Sets.newConcurrentHashSet<PackageIdentifier?>()
        graph.parallelForEach(
            java.util.function.Consumer { node: InMemoryNodeEntry? ->
                when (node.getKey()) {
                    -> (node as IncrementalInMemoryNodeEntry).clearSkyValue()
                    -> {
                        if (!node.isDone() || !selectedKeys.contains(key)) {
                            break
                        }
                        // An ActionLookupKey can point to ActionLookupValues or
                        // ConfiguredTargetValues. If the key is selected, we must retain
                        // the value. If it's a ConfiguredTargetValue, we also record its
                        // package to prevent clearing the corresponding PackageValue
                        // later.
                        val value: SkyValue? = node.getValue()
                        if (value is ConfiguredTargetValue) {
                            val label: Label =
                                if (value.getConfiguredTarget()
                                            is AliasConfiguredTarget
                                )
                                    alias.getLabel()
                                else
                                    key.getLabel()
                            packageIdentifierSet.add(label.getPackageIdentifier())
                        }
                    }

                    else -> {}
                }
            })
        packageIdentifierSet.addAll(
            topLevelTargets.stream()
                .map<Any?>(Label::getPackageIdentifier)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
        )
        // We can clear the values of PackageIdentifier nodes whose package we have not seen in any
        // selected ConfiguredTargetValues.
        graph.parallelForEach(
            java.util.function.Consumer { node: InMemoryNodeEntry? ->
                if (node.getKey() !is PackageIdentifier) {
                    return@parallelForEach
                }
                if (packageIdentifierSet.contains(key)) {
                    return@parallelForEach
                }
                (node as IncrementalInMemoryNodeEntry).clearSkyValue()
            })
    }

    /** If there are no active directories then it falls back to full selection.  */
    private fun computeSelectionResult(
        graph: InMemoryGraph,
        activeDirectoriesMatcher: java.util.Optional<java.util.function.Predicate<PackageIdentifier?>?>,
        traversalMode: TraversalMode?,
        skycacheAnalysisOnly: Boolean
    ): SelectionResult {
        if (activeDirectoriesMatcher.isPresent()) {
            val selection: com.google.common.collect.ImmutableMap<SkyKey?, SelectionMarking?> =
                computeSelection(
                    graph, activeDirectoriesMatcher.get(), traversalMode, skycacheAnalysisOnly
                )
            return SelectionResult(selection)
        } else {
            val selection: com.google.common.collect.ImmutableMap<SkyKey?, SelectionMarking?> =
                computeFullSelection(graph, traversalMode, skycacheAnalysisOnly)
            return SelectionResult(selection)
        }
    }

    private fun computeSelection(
        graph: InMemoryGraph,
        matcher: java.util.function.Predicate<PackageIdentifier?>,
        traversalMode: TraversalMode?,
        skycacheAnalysisOnly: Boolean
    ): com.google.common.collect.ImmutableMap<SkyKey?, SelectionMarking?> {
        val selection: ConcurrentHashMap<SkyKey?, SelectionMarking?> = ConcurrentHashMap<SkyKey?, SelectionMarking?>()
        graph.parallelForEach(
            java.util.function.Consumer { node: InMemoryNodeEntry? ->
                when (node.getKey()) {
                    -> {
                        val label: Label? = key.getLabel()
                        if (label != null && matcher.test(label.getPackageIdentifier())) {
                            markActiveAndTraverseEdges(graph, key, selection, traversalMode)
                        }
                    }

                    -> {
                        if (shouldUpload(data, node)) {
                            // Notably, we don't check the `matcher` for execution values, because we want to
                            // serialize all ActionLookupData even if they're below the frontier, because the
                            // owning ActionLookupValue will be pruned.
                            selection.putIfAbsent(data, SelectionMarking.FRONTIER_CANDIDATE)
                        }
                    }

                    -> {
                        val artifactKey: SkyKey? = selectArtifactKey(artifact)
                        if (artifactKey != null) {
                            // TODO: b/441769854 - add test coverage
                            selection.putIfAbsent(artifactKey, SelectionMarking.FRONTIER_CANDIDATE)
                        }
                    }

                    -> markAnalysisDirectDepsAsFrontierCandidates(key, graph, selection)
                    -> markAnalysisDirectDepsAsFrontierCandidates(key, graph, selection)
                    -> markAnalysisDirectDepsAsFrontierCandidates(key, graph, selection)
                    else -> {}
                }
            })
        return com.google.common.collect.ImmutableMap.copyOf<SkyKey?, SelectionMarking?>(selection)
    }

    private fun computeFullSelection(
        graph: InMemoryGraph, traversalMode: TraversalMode?, skycacheAnalysisOnly: Boolean
    ): com.google.common.collect.ImmutableMap<SkyKey?, SelectionMarking?> {
        val selection: ConcurrentHashMap<SkyKey?, SelectionMarking?> = ConcurrentHashMap<SkyKey?, SelectionMarking?>()
        graph.parallelForEach(
            java.util.function.Consumer { node: InMemoryNodeEntry? ->
                when (node.getKey()) {
                    -> {
                        if (key.getLabel() != null) {
                            selection.putIfAbsent(key, SelectionMarking.FRONTIER_CANDIDATE)
                        }
                    }

                    -> {
                        if (shouldUpload(data, node)) {
                            selection.putIfAbsent(data, SelectionMarking.FRONTIER_CANDIDATE)
                        }
                    }

                    -> {
                        val artifactKey: SkyKey? = selectArtifactKey(artifact)
                        if (artifactKey != null) {
                            selection.putIfAbsent(artifactKey, SelectionMarking.FRONTIER_CANDIDATE)
                        }
                    }

                    else -> {}
                }
            })
        return com.google.common.collect.ImmutableMap.copyOf<SkyKey?, SelectionMarking?>(selection)
    }

    /**
     * Clears the SkyValues of non-selected [ActionLookupKey]s.
     * 
     * 
     * This is needed to reduce peak heap usage in non-incremental builds.
     */
    private fun clearActionLookupValues(
        graph: InMemoryGraph, selectedKeys: com.google.common.collect.ImmutableSet<SkyKey?>
    ) {
        graph.parallelForEach(
            java.util.function.Consumer { node: InMemoryNodeEntry? ->
                if (node.getKey() !is ActionLookupKey || selectedKeys.contains(key)) {
                    return@parallelForEach
                }
                // If the ActionLookupKey is NOT selected, its value is not needed for serialization.
                // We can clear both ActionLookupValues and ConfiguredTargetValues associated with
                // unselected keys to reduce peak heap usage, except for InputFileConfiguredTargets.
                val isInputConfiguredTarget =
                    (node.getValue() is ConfiguredTargetValue)
                            && (ctv.getConfiguredTarget() is InputFileConfiguredTarget)
                if (!isInputConfiguredTarget) {
                    (node as IncrementalInMemoryNodeEntry).clearSkyValue()
                }
            })
    }

    private fun dumpUploadManifest(out: PrintStream, selection: MutableMap<SkyKey?, SelectionMarking?>) {
        val frontierCandidates: com.google.common.collect.ImmutableList.Builder<Any?> =
            com.google.common.collect.ImmutableList.builder<Any?>()
        val activeSet: com.google.common.collect.ImmutableList.Builder<Any?> =
            com.google.common.collect.ImmutableList.builder<Any?>()
        selection
            .entrySet()
            .forEach(
                java.util.function.Consumer { entry: MutableMap.MutableEntry<SkyKey?, SelectionMarking?>? ->
                    when (entry.getValue()) {
                        SelectionMarking.ACTIVE -> activeSet.add(entry.getKey().getCanonicalName())
                        SelectionMarking.FRONTIER_CANDIDATE -> frontierCandidates.add(entry.getKey().getCanonicalName())
                    }
                })
        frontierCandidates.build().stream()
            .sorted()
            .forEach(java.util.function.Consumer { k: Any? -> out.println("FRONTIER_CANDIDATE: " + k) })
        activeSet.build().stream().sorted()
            .forEach(java.util.function.Consumer { k: Any? -> out.println("ACTIVE: " + k) })
        out.flush()
    }

    private fun shouldUpload(data: ActionLookupData, node: InMemoryNodeEntry): Boolean {
        // `valueIsShareable` is used by a different system that does not serialize
        // RunfilesArtifactValue, but the FrontierSerializer should do so. A `WithRichData`
        // value type can be used to distinguish this case.
        return data.valueIsShareable() || node.getValue() is WithRichData
    }

    private fun selectArtifactKey(artifact: Artifact): SkyKey? {
        if (!artifact.valueIsShareable()) {
            // TODO: b/441769854 - add test coverage
            return null
        }
        return when (artifact) {
            -> {
                // Artifact#key is the canonical function to produce the SkyKey that will build this
                // artifact. We want to avoid serializing ordinary DerivedArtifacts, which are never built
                // by Skyframe directly, and the function will return ActionLookupData as the canonical key
                // for those artifacts instead.
                val artifactKey: SkyKey? = Artifact.key(derived)
                if (artifactKey is ActionLookupData) {
                    null // Handled independently.
                }
                artifactKey
            }

            -> null
        }
    }

    private fun markActiveAndTraverseEdges(
        graph: InMemoryGraph,
        root: ActionLookupKey,
        selection: ConcurrentHashMap<SkyKey?, SelectionMarking?>,
        traversalMode: TraversalMode?
    ) {
        if (root.getLabel() == null) {
            return
        }

        val node: InMemoryNodeEntry =
            com.google.common.base.Preconditions.checkNotNull<InMemoryNodeEntry>(graph.getIfPresent(root), root)
        // Right after analysis we want to discard PackageValues that are no longer needed. While
        // traversing the graph we may encounter execution nodes that are not yet finished. It is safe
        // to skip them.
        if (traversalMode == TraversalMode.FOR_POST_ANALYSIS_MINIMIZE_MEMORY && !node.isDone()) {
            com.google.common.base.Preconditions.checkState(
                node.getKey() !is ActionLookupKey || node.getKey() is ActionTemplateExpansionKey
            )
            return
        }

        if (selection.put(root, SelectionMarking.ACTIVE) == SelectionMarking.ACTIVE) {
            return
        }

        for (dep in node.getDirectDeps()) {
            if (dep !is ActionLookupKey) {
                continue
            }

            // Three cases where a child node is disqualified to be a frontier candidate:
            //
            // 1) It doesn't have a label (e.g. BuildInfoKey). These nodes are not deserialized by the
            // analysis functions we care about.
            // 2) It is _already_ marked as ACTIVE, which means it was visited as an rdep from an active
            // root. putIfAbsent will be a no-op.
            // 3) It _will_ be marked as ACTIVE when visited as a rdep from an active root later, and
            // overrides its FRONTIER_CANDIDATE state.
            //
            // In all cases, frontier candidates will never include nodes in the active directories. This
            // is enforced after selection completes.
            if (dep.getLabel() != null) {
                selection.putIfAbsent(dep, SelectionMarking.FRONTIER_CANDIDATE)
            }
        }
        for (rdep in node.getReverseDepsForDoneEntry()) {
            if (rdep !is ActionLookupKey) {
                continue
            }
            // The active set can include nodes outside of the active directories iff they are in the UTC
            // of a root in the active directories.
            markActiveAndTraverseEdges(graph, rdep, selection, traversalMode)
        }
    }

    /**
     * Iterates over the direct analysis deps of a node, and include them into the frontier if they've
     * not been seen before.
     */
    private fun markAnalysisDirectDepsAsFrontierCandidates(
        key: SkyKey?, graph: InMemoryGraph, selection: ConcurrentHashMap<SkyKey?, SelectionMarking?>
    ) {
        graph
            .getIfPresent(key)
            .getDirectDeps()
            .forEach(
                java.util.function.Consumer { depKey: SkyKey? ->
                    if (depKey is ActionLookupKey) {
                        selection.putIfAbsent(depKey, SelectionMarking.FRONTIER_CANDIDATE)
                    }
                })
    }

    fun createFailureDetail(message: String?, detailedCode: Code?): FailureDetail {
        return FailureDetail.newBuilder()
            .setMessage(message)
            .setRemoteAnalysisCaching(RemoteAnalysisCaching.newBuilder().setCode(detailedCode))
            .build()
    }

    /**
     * Deletes select nodes and all rdeps from the graph.
     * 
     * 
     * This is not safe to call if the Skyframe graph needs to be incrementally correct after this
     * point.
     * 
     * @return the number of rdeps deleted.
     */
    private fun deleteNodesAndRdeps(
        graph: InMemoryGraph, nodesToRemove: com.google.common.collect.ImmutableSet<SkyFunctionName?>
    ): DeletionStats {
        val deletedNodes: AtomicLong = AtomicLong()
        val deletedRdeps: AtomicLong = AtomicLong()
        graph.parallelForEach(
            java.util.function.Consumer { node: InMemoryNodeEntry? ->
                val incrementalInMemoryNodeEntry: IncrementalInMemoryNodeEntry? =
                    node as IncrementalInMemoryNodeEntry?
                if (nodesToRemove.contains(node.getKey().functionName())) {
                    graph.remove(node.getKey())
                    deletedNodes.incrementAndGet()
                } else {
                    for (rdep in incrementalInMemoryNodeEntry.getReverseDepsForDoneEntry()) {
                        incrementalInMemoryNodeEntry.removeReverseDep(rdep)
                        deletedRdeps.incrementAndGet()
                    }
                    incrementalInMemoryNodeEntry.consolidateReverseDeps()
                }
            })
        return DeletionStats(deletedNodes.get(), deletedRdeps.get())
    }

    internal enum class TraversalMode {
        FOR_POST_ANALYSIS_MINIMIZE_MEMORY,
        FOR_SERIALIZATION,
    }

    @com.google.common.annotations.VisibleForTesting
    internal enum class SelectionMarking {
        /**
         * The entry is a frontier candidate.
         * 
         * 
         * If a node is still a frontier candidate at the end of the selection process, it is a
         * frontier node.
         */
        FRONTIER_CANDIDATE,

        /** The node is part of the active set.  */
        ACTIVE
    }

    @kotlin.jvm.JvmRecord
    private data class DeletionStats(val nodes: Long, val rdeps: Long)

    private class SelectionResult(selection: com.google.common.collect.ImmutableMap<SkyKey?, SelectionMarking?>?) {
        fun selectedKeys(): com.google.common.collect.ImmutableSet<SkyKey?> {
            return selection.keySet()
        }

        val selection: com.google.common.collect.ImmutableMap<SkyKey?, SelectionMarking?>?

        init {
            this.selection = selection
        }
    }
}
