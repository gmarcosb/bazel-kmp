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

import com.google.devtools.build.lib.actions.Artifact

/**
 * A helper class to find dirty values by accessing the filesystem directly (contrast with [ ]).
 */
class FilesystemValueChecker(
    tsgm: TimestampGranularityMonitor?,
    syscallCache: SyscallCache?,
    xattrProviderOverrider: XattrProviderOverrider,
    numThreads: Int
) {
    /**
     * Allows to override the [XattrProvider] when getting xattr (or digest) for output files.
     */
    interface XattrProviderOverrider {
        fun getXattrProvider(syscallCache: SyscallCache?): XattrProvider?

        companion object {
            @kotlin.jvm.JvmField
            val NO_OVERRIDE: XattrProviderOverrider =
                XattrProviderOverrider { syscallCache: SyscallCache? -> syscallCache }
        }
    }

    private val tsgm: TimestampGranularityMonitor?
    private val syscallCache: SyscallCache?
    private val xattrProviderOverrider: XattrProviderOverrider
    private val numThreads: Int

    init {
        this.tsgm = tsgm
        this.syscallCache = syscallCache
        this.xattrProviderOverrider = xattrProviderOverrider
        this.numThreads = numThreads
    }

    /**
     * Returns a [Differencer.DiffWithDelta] containing keys from the give map that are dirty
     * according to the passed-in `dirtinessChecker`.
     */
    // TODO(bazel-team): Refactor these methods so that FilesystemValueChecker only operates on a
    // WalkableGraph.
    @Throws(java.lang.InterruptedException::class)
    fun getDirtyKeys(
        valuesMap: MutableMap<SkyKey?, SkyValue?>, dirtinessChecker: SkyValueDirtinessChecker
    ): ImmutableBatchDirtyResult {
        return getDirtyValues(
            MapBackedValueFetcher(valuesMap),
            valuesMap.keys,
            dirtinessChecker,  /* checkMissingValues= */
            false,  /* inMemoryGraph= */
            null
        )
    }

    @Throws(java.lang.InterruptedException::class)
    fun getDirtyKeys(
        inMemoryGraph: InMemoryGraph, dirtinessChecker: SkyValueDirtinessChecker
    ): ImmutableBatchDirtyResult {
        val valuesMap: MutableMap<SkyKey?, SkyValue?> = inMemoryGraph.getValues()
        return getDirtyValues(
            MapBackedValueFetcher(valuesMap),
            valuesMap.keys,
            dirtinessChecker,  /* checkMissingValues= */
            false,
            inMemoryGraph
        )
    }

    /**
     * Returns a [Differencer.DiffWithDelta] containing keys that are dirty according to the
     * passed-in `dirtinessChecker`.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getNewAndOldValues(
        walkableGraph: WalkableGraph,
        keys: MutableCollection<SkyKey>,
        dirtinessChecker: SkyValueDirtinessChecker
    ): DiffWithDelta {
        return getDirtyValues(
            WalkableGraphBackedValueFetcher(walkableGraph),
            keys,
            dirtinessChecker,  /* checkMissingValues= */
            true,  /* inMemoryGraph= */
            null
        )
    }

    private interface ValueFetcher {
        @Throws(java.lang.InterruptedException::class)
        fun get(key: SkyKey?): SkyValue?
    }

    private class WalkableGraphBackedValueFetcher(walkableGraph: WalkableGraph) : ValueFetcher {
        private val walkableGraph: WalkableGraph

        init {
            this.walkableGraph = walkableGraph
        }

        @Throws(java.lang.InterruptedException::class)
        override fun get(key: SkyKey?): SkyValue? {
            return walkableGraph.getValue(key)
        }
    }

    private class MapBackedValueFetcher(valuesMap: MutableMap<SkyKey?, SkyValue?>) : ValueFetcher {
        private val valuesMap: MutableMap<SkyKey?, SkyValue?>

        init {
            this.valuesMap = valuesMap
        }

        override fun get(key: SkyKey?): SkyValue? {
            return valuesMap.get(key)
        }
    }

    /** Callback for modified output files for logging/metrics.  */
    @ThreadSafe
    internal fun interface ModifiedOutputsReceiver {
        /**
         * Called on every modified artifact detected by [.getDirtyActionValues].
         * 
         * @param maybeModifiedTime Best effort modified time, -1 when not available/missing.
         * @param artifact Modified output artifact.
         */
        fun reportModifiedOutputFile(maybeModifiedTime: Long, artifact: Artifact?)
    }

    /**
     * Return a collection of action values which have output files that are not in-sync with the
     * on-disk file value (were modified externally).
     */
    @Throws(java.lang.InterruptedException::class)
    fun getDirtyActionValues(
        valuesMap: MutableMap<SkyKey?, SkyValue?>,
        batchStatter: BatchStat?,
        modifiedOutputFiles: ModifiedFileSet,
        outputChecker: OutputChecker,
        modifiedOutputsReceiver: ModifiedOutputsReceiver
    ): MutableCollection<SkyKey?> {
        if (modifiedOutputFiles === ModifiedFileSet.NOTHING_MODIFIED) {
            logger.atInfo().log("Not checking for dirty actions since nothing was modified")
            return com.google.common.collect.ImmutableList.of<SkyKey?>()
        }

        logger.atInfo().log("Accumulating dirty actions and batching them into shards")
        val numShards: Int = java.lang.Runtime.getRuntime().availableProcessors() * 4
        val actionKeyShards: MutableCollection<MutableList<MutableMap.MutableEntry<SkyKey?, ActionExecutionValue?>?>>
        Profiler.instance().profile("getDirtyActionValues/filterAndBatchActions").use { c ->
            actionKeyShards = batchActionKeysIntoShards(numShards, valuesMap)
        }
        val executor: ExecutorService =
            Executors.newFixedThreadPool(
                numShards,
                com.google.common.util.concurrent.ThreadFactoryBuilder()
                    .setNameFormat("FileSystem Output File Invalidator %d")
                    .build()
            )

        val dirtyKeys: MutableCollection<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()

        val knownModifiedOutputFiles: com.google.common.collect.ImmutableSet<PathFragment?>? =
            if (modifiedOutputFiles.treatEverythingAsModified())
                null
            else
                modifiedOutputFiles.modifiedSourceFiles()

        // Initialized lazily through a supplier because it is only used to check modified
        // TreeArtifacts, which are not frequently used in builds.
        val sortedKnownModifiedOutputFiles: com.google.common.base.Supplier<NavigableSet<PathFragment?>?> =
            com.google.common.base.Suppliers.memoize<NavigableSet<PathFragment?>?>(
                object : com.google.common.base.Supplier<NavigableSet<PathFragment?>?> {
                    override fun get(): NavigableSet<PathFragment?>? {
                        if (knownModifiedOutputFiles == null) {
                            return null
                        } else {
                            return com.google.common.collect.ImmutableSortedSet.copyOf<PathFragment?>(
                                knownModifiedOutputFiles
                            )
                        }
                    }
                })

        val interrupted: Boolean
        Profiler.instance().profile("getDirtyActionValues/statFiles").use { c ->
            for (shard in actionKeyShards) {
                val job: java.lang.Runnable =
                    if (batchStatter == null)
                        outputStatJob(
                            dirtyKeys,
                            shard,
                            knownModifiedOutputFiles,
                            sortedKnownModifiedOutputFiles,
                            outputChecker,
                            modifiedOutputsReceiver
                        )
                    else
                        batchStatJob(
                            dirtyKeys,
                            shard,
                            batchStatter,
                            knownModifiedOutputFiles,
                            sortedKnownModifiedOutputFiles,
                            outputChecker,
                            modifiedOutputsReceiver
                        )
                executor.execute(job)
            }
            interrupted = ExecutorUtil.interruptibleShutdown(executor)
        }
        if (dirtyKeys.isEmpty()) {
            logger.atInfo().log("Completed output file stat checks, no modified outputs found")
        } else {
            logger.atInfo().log(
                "Completed output file stat checks, %d actions' outputs changed, first few: %s",
                dirtyKeys.size, com.google.common.collect.Iterables.limit<SkyKey?>(dirtyKeys, 10)
            )
        }
        if (interrupted) {
            throw java.lang.InterruptedException()
        }
        return dirtyKeys
    }

    private fun batchActionKeysIntoShards(
        numShards: Int, valuesMap: MutableMap<SkyKey?, SkyValue?>
    ): MutableCollection<MutableList<MutableMap.MutableEntry<SkyKey?, ActionExecutionValue?>?>> {
        return valuesMap.entries.stream()
            .parallel()
            .filter { e: MutableMap.MutableEntry<SkyKey?, SkyValue?>? -> ACTION_FILTER.apply(e!!.key) }
            .map { e: MutableMap.MutableEntry<SkyKey?, SkyValue?>? -> e as MutableMap.MutableEntry<*, *> }
            .collect(Collectors.groupingByConcurrent(java.util.function.Function { k: MutableMap.MutableEntry<Any?, Any?>? ->
                java.util.concurrent.ThreadLocalRandom.current().nextInt(numShards)
            }))
            .values as MutableCollection<*>
    }

    private fun batchStatJob(
        dirtyKeys: MutableCollection<SkyKey?>,
        shard: MutableList<MutableMap.MutableEntry<SkyKey?, ActionExecutionValue?>>,
        batchStatter: BatchStat,
        knownModifiedOutputFiles: com.google.common.collect.ImmutableSet<PathFragment?>?,
        sortedKnownModifiedOutputFiles: com.google.common.base.Supplier<NavigableSet<PathFragment?>?>,
        outputChecker: OutputChecker,
        modifiedOutputsReceiver: ModifiedOutputsReceiver
    ): java.lang.Runnable {
        return java.lang.Runnable {
            val fileToKeyAndValue: MutableMap<Artifact?, MutableMap.MutableEntry<SkyKey?, ActionExecutionValue>> =
                HashMap<Artifact?, MutableMap.MutableEntry<SkyKey?, ActionExecutionValue>>()
            val treeArtifactsToKeyAndValue: MutableMap<Artifact?, MutableMap.MutableEntry<SkyKey?, ActionExecutionValue?>?> =
                HashMap<Artifact?, MutableMap.MutableEntry<SkyKey?, ActionExecutionValue?>?>()
            for (keyAndValue in shard) {
                val actionValue: ActionExecutionValue? = keyAndValue.value
                if (actionValue == null) {
                    dirtyKeys.add(keyAndValue.key)
                } else {
                    for (artifact in actionValue.allFileValues.keySet()) {
                        if (!artifact.isRunfilesTree() && shouldCheckFile(knownModifiedOutputFiles, artifact)) {
                            fileToKeyAndValue.put(artifact, keyAndValue)
                        }
                    }

                    for (entry in actionValue.getAllTreeArtifactValues().entrySet()) {
                        val treeArtifact: Artifact = entry.key
                        val tree: TreeArtifactValue = entry.value
                        for (child in tree.getChildren()) {
                            if (shouldCheckFile(knownModifiedOutputFiles, child)) {
                                fileToKeyAndValue.put(child, keyAndValue)
                            }
                        }
                        tree.getArchivedRepresentation()
                            .map<ArchivedTreeArtifact?>(ArchivedRepresentation::archivedTreeFileArtifact)
                            .filter(
                                java.util.function.Predicate { archivedTreeArtifact: ArchivedTreeArtifact? ->
                                    shouldCheckFile(
                                        knownModifiedOutputFiles,
                                        archivedTreeArtifact
                                    )
                                })
                            .ifPresent(
                                java.util.function.Consumer { archivedTreeArtifact: ArchivedTreeArtifact? ->
                                    fileToKeyAndValue.put(
                                        archivedTreeArtifact,
                                        keyAndValue
                                    )
                                })
                        if (shouldCheckTreeArtifact(sortedKnownModifiedOutputFiles.get(), treeArtifact)) {
                            treeArtifactsToKeyAndValue.put(treeArtifact, keyAndValue)
                        }
                    }
                }
            }

            val artifacts: MutableList<Artifact?> =
                com.google.common.collect.ImmutableList.copyOf<Artifact?>(fileToKeyAndValue.keys)
            val stats: MutableList<FileStatusWithDigest?>
            try {
                stats = batchStatter.batchStat(Artifact.asPathFragments(artifacts))
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log(
                    "Unable to process batch stat, falling back to individual stats"
                )
                outputStatJob(
                    dirtyKeys,
                    shard,
                    knownModifiedOutputFiles,
                    sortedKnownModifiedOutputFiles,
                    outputChecker,
                    modifiedOutputsReceiver
                )
                    .run()
                return@Runnable
            } catch (e: java.lang.InterruptedException) {
                logger.atInfo().log("Interrupted doing batch stat")
                java.lang.Thread.currentThread().interrupt()
                // We handle interrupt in the main thread.
                return@Runnable
            }

            com.google.common.base.Preconditions.checkState(
                artifacts.size == stats.size,
                "artifacts.size() == %s stats.size() == %s",
                artifacts.size,
                stats.size
            )
            for (i in artifacts.indices) {
                val artifact: Artifact? = artifacts.get(i)
                val stat: FileStatusWithDigest? = stats.get(i)
                val keyAndValue: MutableMap.MutableEntry<SkyKey?, ActionExecutionValue> =
                    fileToKeyAndValue.get(artifact)
                val actionValue: ActionExecutionValue = keyAndValue.value
                val key: SkyKey? = keyAndValue.key
                val lastKnownData: FileArtifactValue? = actionValue.getExistingFileArtifactValue(artifact)
                try {
                    val newData: FileArtifactValue =
                        ActionOutputMetadataStore.fileArtifactValueFromArtifact(
                            artifact, stat, xattrProviderOverrider.getXattrProvider(syscallCache), tsgm
                        )
                    if (newData.couldBeModifiedSince(lastKnownData)) {
                        var maybeModifiedTime: Long = -1
                        if (stat != null) {
                            try {
                                maybeModifiedTime = stat.getLastChangeTime()
                            } catch (ignored: java.lang.UnsupportedOperationException) {
                                // Not all filesystems support change time; falling back to -1.
                            }
                        }
                        modifiedOutputsReceiver.reportModifiedOutputFile(maybeModifiedTime, artifact)
                        dirtyKeys.add(key)
                    }
                } catch (e: IOException) {
                    logger.atWarning().withCause(e).log(
                        "Error for %s (%s %s %s)", artifact, stat, keyAndValue, lastKnownData
                    )
                    // This is an unexpected failure getting a digest or symlink target.
                    modifiedOutputsReceiver.reportModifiedOutputFile(-1, artifact)
                    dirtyKeys.add(key)
                }
            }

            // Unfortunately, there exists no facility to batch list directories.
            // We must use direct filesystem calls.
            for (entry in treeArtifactsToKeyAndValue.entries) {
                val artifact: Artifact = entry.key
                try {
                    if (treeArtifactIsDirty(
                            entry.key, entry.value!!.value.getTreeArtifactValue(artifact)
                        )
                    ) {
                        // Count the changed directory as one "file".
                        // TODO(bazel-team): There are no tests for this codepath.
                        modifiedOutputsReceiver.reportModifiedOutputFile(
                            getBestEffortModifiedTime(artifact.getPath()), artifact
                        )
                        dirtyKeys.add(entry.value!!.key)
                    }
                } catch (e: java.lang.InterruptedException) {
                    logger.atInfo().log("Interrupted doing batch stat")
                    java.lang.Thread.currentThread().interrupt()
                    // We handle interrupt in the main thread.
                    return@Runnable
                }
            }
        }
    }

    private fun outputStatJob(
        dirtyKeys: MutableCollection<SkyKey?>,
        shard: MutableList<MutableMap.MutableEntry<SkyKey?, ActionExecutionValue?>>,
        knownModifiedOutputFiles: com.google.common.collect.ImmutableSet<PathFragment?>?,
        sortedKnownModifiedOutputFiles: com.google.common.base.Supplier<NavigableSet<PathFragment?>?>,
        outputChecker: OutputChecker,
        modifiedOutputsReceiver: ModifiedOutputsReceiver
    ): java.lang.Runnable {
        return object : java.lang.Runnable {
            override fun run() {
                try {
                    for (keyAndValue in shard) {
                        val value: ActionExecutionValue? = keyAndValue.value
                        if (value == null
                            || actionValueIsDirtyWithDirectSystemCalls(
                                value,
                                knownModifiedOutputFiles,
                                sortedKnownModifiedOutputFiles,
                                outputChecker,
                                modifiedOutputsReceiver
                            )
                        ) {
                            dirtyKeys.add(keyAndValue.key)
                        }
                    }
                } catch (e: java.lang.InterruptedException) {
                    // This code is called from getDirtyActionValues() and is running under an Executor. This
                    // means that getDirtyActionValues() will take care of house-keeping in case of an
                    // interrupt; all that matters is that we exit as quickly as possible.
                    logger.atInfo().log("Interrupted doing non-batch stat")
                    java.lang.Thread.currentThread().interrupt()
                }
            }
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun treeArtifactIsDirty(artifact: Artifact, value: TreeArtifactValue): Boolean {
        val path: com.google.devtools.build.lib.vfs.Path = artifact.getPath()
        if (path.isSymbolicLink()) {
            return true // TreeArtifacts may not be symbolic links.
        }

        // This could be improved by short-circuiting as soon as we see a child that is not present in
        // the TreeArtifactValue, but it doesn't seem to be a major source of overhead.
        // visitTree() is called from multiple threads in parallel so this need to be a concurrent set
        val currentLocalChildren: MutableSet<PathFragment?> =
            com.google.common.collect.Sets.newConcurrentHashSet<PathFragment?>()
        try {
            TreeArtifactValue.visitTree(
                path,
                TreeArtifactVisitor { child: PathFragment?, type: com.google.devtools.build.lib.vfs.Dirent.Type?, traversedSymlink: Boolean ->
                    if (type != com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY) {
                        currentLocalChildren.add(child)
                    }
                })
        } catch (e: IOException) {
            return true
        }

        if (currentLocalChildren.isEmpty() && value.isEntirelyRemote()) {
            return false
        }

        val lastKnownLocalChildren: com.google.common.collect.ImmutableSet<Any?> =
            value.getChildValues().entries.stream()
                .filter { entry: MutableMap.MutableEntry<TreeFileArtifact?, FileArtifactValue>? ->
                    val metadata: FileArtifactValue = entry!!.value
                    !metadata.isRemote() || metadata.getContentsProxy() != null
                }
                .map<Any?> { entry: MutableMap.MutableEntry<TreeFileArtifact?, FileArtifactValue>? -> entry!!.key.getParentRelativePath() }
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())

        return currentLocalChildren != lastKnownLocalChildren
    }

    private fun artifactIsDirtyWithDirectSystemCalls(
        knownModifiedOutputFiles: com.google.common.collect.ImmutableSet<PathFragment?>?,
        outputChecker: OutputChecker,
        entry: MutableMap.MutableEntry<out Artifact, FileArtifactValue>,
        modifiedOutputsReceiver: ModifiedOutputsReceiver
    ): Boolean {
        val file: Artifact = entry.key
        val lastKnownData: FileArtifactValue = entry.value
        if (file.isRunfilesTree() || !shouldCheckFile(knownModifiedOutputFiles, file)) {
            return false
        }
        try {
            val fileMetadata: FileArtifactValue =
                ActionOutputMetadataStore.fileArtifactValueFromArtifact(
                    file, null, xattrProviderOverrider.getXattrProvider(syscallCache), tsgm
                )
            val isTrustedRemoteValue =
                fileMetadata.getType() === FileStateType.NONEXISTENT && lastKnownData.isRemote()
                        && outputChecker.shouldTrustMetadata(file, lastKnownData)
            if (!isTrustedRemoteValue && fileMetadata.couldBeModifiedSince(lastKnownData)) {
                modifiedOutputsReceiver.reportModifiedOutputFile(
                    if (fileMetadata.getType() !== FileStateType.NONEXISTENT)
                        file.getPath().getLastModifiedTime(Symlinks.FOLLOW)
                    else
                        -1,
                    file
                )
                return true
            }
            return false
        } catch (e: IOException) {
            // This is an unexpected failure getting a digest or symlink target.
            modifiedOutputsReceiver.reportModifiedOutputFile( /* maybeModifiedTime= */-1, file)
            return true
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun actionValueIsDirtyWithDirectSystemCalls(
        actionValue: ActionExecutionValue,
        knownModifiedOutputFiles: com.google.common.collect.ImmutableSet<PathFragment?>?,
        sortedKnownModifiedOutputFiles: com.google.common.base.Supplier<NavigableSet<PathFragment?>?>,
        outputChecker: OutputChecker,
        modifiedOutputsReceiver: ModifiedOutputsReceiver
    ): Boolean {
        var isDirty = false
        for (entry in actionValue.allFileValues.entrySet()) {
            if (artifactIsDirtyWithDirectSystemCalls(
                    knownModifiedOutputFiles, outputChecker, entry, modifiedOutputsReceiver
                )
            ) {
                isDirty = true
            }
        }

        for (entry in actionValue.getAllTreeArtifactValues().entrySet()) {
            val tree: TreeArtifactValue = entry.value

            for (childEntry in tree.getChildValues().entries) {
                if (artifactIsDirtyWithDirectSystemCalls(
                        knownModifiedOutputFiles, outputChecker, childEntry, modifiedOutputsReceiver
                    )
                ) {
                    isDirty = true
                }
            }
            isDirty =
                isDirty
                        || tree.getArchivedRepresentation()
                    .map<Boolean?>(
                        java.util.function.Function { archivedRepresentation: ArchivedRepresentation? ->
                            artifactIsDirtyWithDirectSystemCalls(
                                knownModifiedOutputFiles,
                                outputChecker,
                                com.google.common.collect.Maps.immutableEntry<Artifact?, FileArtifactValue?>(
                                    archivedRepresentation.archivedTreeFileArtifact,
                                    archivedRepresentation.archivedFileValue
                                ),
                                modifiedOutputsReceiver
                            )
                        })
                    .orElse(false)

            val treeArtifact: Artifact = entry.key
            if (shouldCheckTreeArtifact(sortedKnownModifiedOutputFiles.get(), treeArtifact)
                && treeArtifactIsDirty(treeArtifact, entry.value)
            ) {
                // Count the changed directory as one "file".
                modifiedOutputsReceiver.reportModifiedOutputFile(
                    getBestEffortModifiedTime(treeArtifact.getPath()), treeArtifact
                )
                isDirty = true
            }
        }

        return isDirty
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getDirtyValues(
        fetcher: ValueFetcher,
        keys: MutableCollection<SkyKey>,
        checker: SkyValueDirtinessChecker,
        checkMissingValues: Boolean,
        inMemoryGraph: InMemoryGraph?
    ): ImmutableBatchDirtyResult {
        val executor: ExecutorService =
            Executors.newFixedThreadPool(
                numThreads,
                com.google.common.util.concurrent.ThreadFactoryBuilder()
                    .setNameFormat("FileSystem Value Invalidator %d").build()
            )

        val numKeysChecked: AtomicInteger = AtomicInteger(0)
        val batchResult = MutableBatchDirtyResult(numKeysChecked)
        val elapsedTimeReceiver: ElapsedTimeReceiver =
            ElapsedTimeReceiver { elapsedTimeNanos ->
                if (elapsedTimeNanos > 0) {
                    logger.atInfo().log(
                        "Spent %d nanoseconds checking %d filesystem nodes (%d scanned)",
                        elapsedTimeNanos, numKeysChecked.get(), keys.size
                    )
                }
            }
        AutoProfiler.create(elapsedTimeReceiver).use { prof ->
            for (key in keys) {
                if (!checker.applies(key)) {
                    continue
                }
                com.google.common.base.Preconditions.checkState(
                    key.functionName().getHermeticity() == FunctionHermeticity.NONHERMETIC,
                    "Only non-hermetic keys can be dirty roots: %s",
                    key
                )
                executor.execute(
                    java.lang.Runnable {
                        val value: SkyValue?
                        try {
                            value = fetcher.get(key)
                        } catch (e: java.lang.InterruptedException) {
                            // Exit fast. Interrupt is handled below on the main thread.
                            return@execute
                        }
                        if (!checkMissingValues && value == null) {
                            return@execute
                        }
                        val oldMtsv: com.google.devtools.build.skyframe.Version? =
                            if (inMemoryGraph != null)
                                inMemoryGraph
                                    .get( /* requestor= */null, QueryableGraph.Reason.OTHER, key)
                                    .getMaxTransitiveSourceVersion()
                            else
                                null
                        numKeysChecked.incrementAndGet()
                        var result: DirtyResult
                        try {
                            result = checker.check(key, value, oldMtsv, syscallCache, tsgm)
                        } catch (e: IOException) {
                            // Treat IOException as dirty with an unknown value. If this key is requested during
                            // an evaluation, we'll attempt to evaluate it - the error may turn out to be
                            // permanent or transient.
                            result = DirtyResult.dirty()
                        }
                        if (result.isDirty()) {
                            batchResult.add(
                                key, value, result.getNewValue(), result.getNewMaxTransitiveSourceVersion()
                            )
                        }
                    })
            }
            // If a Runnable above crashes, this shutdown can still succeed but the whole server will come
            // down shortly.
            if (ExecutorUtil.interruptibleShutdown(executor)) {
                throw java.lang.InterruptedException()
            }
        }
        return batchResult.toImmutable()
    }

    /** An immutable [com.google.devtools.build.skyframe.Differencer.DiffWithDelta].  */
    class ImmutableBatchDirtyResult private constructor(
        dirtyKeysWithoutNewValues: MutableCollection<SkyKey?>?,
        dirtyKeysWithNewAndOldValues: MutableMap<SkyKey?, Delta?>?,
        numKeysChecked: Int
    ) : DiffWithDelta {
        private val dirtyKeysWithoutNewValues: MutableCollection<SkyKey?>?
        private val dirtyKeysWithNewAndOldValues: MutableMap<SkyKey?, Delta?>?
        @kotlin.jvm.JvmField
        val numKeysChecked: Int

        init {
            this.dirtyKeysWithoutNewValues = dirtyKeysWithoutNewValues
            this.dirtyKeysWithNewAndOldValues = dirtyKeysWithNewAndOldValues
            this.numKeysChecked = numKeysChecked
        }

        override fun changedKeysWithoutNewValues(): MutableCollection<SkyKey?>? {
            return dirtyKeysWithoutNewValues
        }

        override fun changedKeysWithNewValues(): MutableMap<SkyKey?, Delta?>? {
            return dirtyKeysWithNewAndOldValues
        }
    }

    /**
     * Result of a batch call to [SkyValueDirtinessChecker.check]. Partitions the dirty values
     * based on whether we have a new value available for them or not.
     */
    private class MutableBatchDirtyResult(numChecked: AtomicInteger) {
        private val concurrentDirtyKeysWithoutNewValues: MutableSet<SkyKey?> =
            Collections.newSetFromMap<SkyKey?>(ConcurrentHashMap<SkyKey?, Boolean?>())
        private val concurrentDirtyKeysWithNewAndOldValues: ConcurrentHashMap<SkyKey?, Delta?> =
            ConcurrentHashMap<SkyKey?, Delta?>()
        private val numChecked: AtomicInteger

        init {
            this.numChecked = numChecked
        }

        fun add(
            key: SkyKey?,
            oldValue: SkyValue?,
            newValue: SkyValue?,
            newMaxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?
        ) {
            if (newValue == null) {
                concurrentDirtyKeysWithoutNewValues.add(key)
            } else {
                // TODO(b/139545639) - handle old mtsv's and null mtsv's
                if (oldValue == null) {
                    concurrentDirtyKeysWithNewAndOldValues.put(
                        key, Delta.justNew(newValue, newMaxTransitiveSourceVersion)
                    )
                } else {
                    concurrentDirtyKeysWithNewAndOldValues.put(
                        key, Delta.changed(oldValue, newValue, newMaxTransitiveSourceVersion)
                    )
                }
            }
        }

        fun toImmutable(): ImmutableBatchDirtyResult {
            return ImmutableBatchDirtyResult(
                concurrentDirtyKeysWithoutNewValues,
                concurrentDirtyKeysWithNewAndOldValues,
                numChecked.get()
            )
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private val ACTION_FILTER: com.google.common.base.Predicate<SkyKey?> =
            SkyFunctionName.functionIs(SkyFunctions.ACTION_EXECUTION)

        private fun getBestEffortModifiedTime(path: com.google.devtools.build.lib.vfs.Path): Long {
            try {
                return if (path.exists()) path.getLastModifiedTime() else -1
            } catch (e: IOException) {
                logger.atWarning().atMostEvery(1, TimeUnit.MINUTES).withCause(e).log(
                    "Failed to get modified time for output at: %s", path
                )
                return -1
            }
        }

        private fun shouldCheckFile(
            knownModifiedOutputFiles: com.google.common.collect.ImmutableSet<PathFragment?>?, artifact: Artifact
        ): Boolean {
            return knownModifiedOutputFiles == null
                    || knownModifiedOutputFiles.contains(artifact.getExecPath())
        }

        private fun shouldCheckTreeArtifact(
            knownModifiedOutputFiles: NavigableSet<PathFragment?>?, treeArtifact: Artifact
        ): Boolean {
            // If null, everything needs to be checked.
            if (knownModifiedOutputFiles == null) {
                return true
            }

            // Here we do the following to see whether a TreeArtifact is modified:
            // 1. Sort the set of modified file paths in lexicographical order using TreeSet.
            // 2. Get the first modified output file path that is greater than or equal to the exec path of
            //    the TreeArtifact to check.
            // 3. Check whether the returned file path contains the exec path of the TreeArtifact as a
            //    prefix path.
            val artifactExecPath: PathFragment? = treeArtifact.getExecPath()
            val headPath: PathFragment? = knownModifiedOutputFiles.ceiling(artifactExecPath)

            return headPath != null && headPath.startsWith(artifactExecPath)
        }
    }
}
