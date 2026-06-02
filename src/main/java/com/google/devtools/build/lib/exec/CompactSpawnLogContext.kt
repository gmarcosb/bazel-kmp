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
package com.google.devtools.build.lib.exec

import com.github.luben.zstd.ZstdOutputStream

/** A [SpawnLogContext] implementation that produces a log in compact format.  */
class CompactSpawnLogContext(
    out: BufferedOutputStream?,
    displayName: String?,
    execRoot: PathFragment,
    workspaceName: String,
    siblingRepositoryLayout: Boolean,
    remoteOptions: RemoteOptions?,
    digestHashFunction: DigestHashFunction,
    xattrProvider: XattrProvider?,
    invocationId: UUID,
    reporter: com.google.devtools.build.lib.events.ExtendedEventHandler,
    logSpawnPredicate: java.util.function.Predicate<Spawn?>?
) : SpawnLogContext(logSpawnPredicate) {
    /** Visitor for use in [.visitDirectory].  */
    protected interface DirectoryChildVisitor {
        @Throws(IOException::class)
        fun visit(path: com.google.devtools.build.lib.vfs.Path?)
    }

    private class DirectoryVisitor(
        rootDir: com.google.devtools.build.lib.vfs.Path?,
        childVisitor: DirectoryChildVisitor?
    ) : com.google.devtools.build.lib.concurrent.AbstractQueueVisitor(
        VISITOR_POOL,
        com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExecutorOwnership.SHARED,
        com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExceptionHandlingMode.FAIL_FAST,
        com.google.devtools.build.lib.concurrent.ErrorClassifier.Companion.DEFAULT
    ) {
        private val rootDir: com.google.devtools.build.lib.vfs.Path
        private val childVisitor: DirectoryChildVisitor

        init {
            this.rootDir =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path>(rootDir)
            this.childVisitor = com.google.common.base.Preconditions.checkNotNull<DirectoryChildVisitor>(childVisitor)
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun run() {
            execute(java.lang.Runnable { visitSubdirectory(rootDir) })
            try {
                awaitQuiescence(true)
            } catch (e: UncheckedIOException) {
                throw e.getCause()
            }
        }

        fun visitSubdirectory(dir: com.google.devtools.build.lib.vfs.Path) {
            try {
                for (dirent in dir.readdir(Symlinks.FOLLOW)) {
                    val child: com.google.devtools.build.lib.vfs.Path = dir.getChild(dirent.getName())
                    if (dirent.getType() == com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY) {
                        execute(java.lang.Runnable { visitSubdirectory(child) })
                        continue
                    }
                    childVisitor.visit(child)
                }
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }
        }
    }

    /**
     * Visits a directory hierarchy in parallel.
     * 
     * 
     * Calls `childVisitor` for every descendant path of `rootDir` that isn't itself a
     * directory, following symlinks. The visitor may be concurrently called by multiple threads, and
     * must synchronize accesses to shared data.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun visitDirectory(rootDir: com.google.devtools.build.lib.vfs.Path?, childVisitor: DirectoryChildVisitor?) {
        DirectoryVisitor(rootDir, childVisitor).run()
    }

    private interface ExecLogEntrySupplier {
        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun get(): ExecLogEntry.Builder
    }

    private val execRoot: PathFragment
    private val workspaceName: String
    private val siblingRepositoryLayout: Boolean
    private val remoteOptions: RemoteOptions?
    private val digestHashFunction: DigestHashFunction
    private val xattrProvider: XattrProvider?
    private val invocationId: UUID
    private val reporter: com.google.devtools.build.lib.events.ExtendedEventHandler
    private val outputLoggingFailed: AtomicBoolean = AtomicBoolean(false)

    // Maps a key identifying an entry into its ID.
    // Each key is either a NestedSet.Node or the String path of a file, directory, symlink or
    // runfiles tree.
    // Only entries that are likely to be referenced by future entries are stored.
    // Use a specialized map for minimal memory footprint.
    @javax.annotation.concurrent.GuardedBy("this")
    private val entryMap: Object2IntOpenHashMap<Any?> = Object2IntOpenHashMap<Any?>()

    // The next available entry ID.
    @javax.annotation.concurrent.GuardedBy("this")
    var nextEntryId: Int = 1

    // Output stream to write to.
    private val outputStream: MessageOutputStream<ExecLogEntry?>

    init {
        this.execRoot = execRoot
        this.workspaceName = workspaceName
        this.siblingRepositoryLayout = siblingRepositoryLayout
        this.remoteOptions = remoteOptions
        this.digestHashFunction = digestHashFunction
        this.xattrProvider = xattrProvider
        this.invocationId = invocationId
        this.reporter = reporter
        this.outputStream = getOutputStream(out, displayName)

        logInvocation()
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun logInvocation() {
        logEntryWithoutId(
            ExecLogEntrySupplier {
                ExecLogEntry.newBuilder()
                    .setInvocation(
                        ExecLogEntry.Invocation.newBuilder()
                            .setHashFunctionName(StringEncoding.internalToUnicode(digestHashFunction.toString()))
                            .setWorkspaceRunfilesDirectory(StringEncoding.internalToUnicode(workspaceName))
                            .setSiblingRepositoryLayout(siblingRepositoryLayout)
                            .setId(StringEncoding.internalToUnicode(invocationId.toString()))
                    )
            })
    }

    override fun shouldPublish(): Boolean {
        // The compact log is small enough to be uploaded to a remote store.
        return true
    }

    @Throws(IOException::class, java.lang.InterruptedException::class, ExecException::class)
    override fun logSpawn(
        spawn: Spawn,
        inputMetadataProvider: InputMetadataProvider,
        inputMap: java.util.function.Supplier<SortedMap<PathFragment?, ActionInput?>?>?,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem,
        timeout: java.time.Duration,
        result: SpawnResult
    ) {
        if (!shouldLog(spawn)) {
            return
        }
        com.google.devtools.build.lib.profiler.Profiler.instance()
            .profile(com.google.devtools.build.lib.profiler.ProfilerTask.SPAWN_LOG, "logSpawn").use { c ->
                val builder: ExecLogEntry.Spawn.Builder = ExecLogEntry.Spawn.newBuilder()
                builder.addAllArgs(
                    com.google.common.collect.Lists.transform<F?, T?>(
                        spawn.getArguments(),
                        com.google.common.base.Function { s: F? -> StringEncoding.internalToUnicode(s) })
                )
                builder.addAllEnvVars(getEnvironmentVariables(spawn))
                val platform: Platform? = getPlatform(spawn, remoteOptions)
                if (platform != null) {
                    builder.setPlatform(platform)
                }

                builder.setInputSetId(logInputs(spawn, inputMetadataProvider, fileSystem))
                builder.setToolSetId(logTools(spawn, inputMetadataProvider, fileSystem))

                if (spawn.getTargetLabel() != null) {
                    builder.setTargetLabel(StringEncoding.internalToUnicode(spawn.getTargetLabel().getCanonicalForm()))
                }
                builder.setMnemonic(StringEncoding.internalToUnicode(spawn.getMnemonic()))

                var warned = false
                for (output in spawn.getOutputFiles()) {
                    val path: com.google.devtools.build.lib.vfs.Path =
                        fileSystem.getPath(execRoot.getRelative(output.getExecPath()))
                    val outputBuilder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        ExecLogEntry.Output.newBuilder()
                    try {
                        if (!output.isDirectory() && !output.isSymlink() && path.isFile()) {
                            outputBuilder.setOutputId(logFile(output, path,  /* inputMetadataProvider= */null))
                        } else if (output.isDirectory() && path.isDirectory()) {
                            outputBuilder.setOutputId(
                                logDirectory(output, path,  /* inputMetadataProvider= */null)
                            )
                        } else if (output.isSymlink() && path.isSymbolicLink()) {
                            outputBuilder.setOutputId(
                                logUnresolvedSymlink(output, path,  /* inputMetadataProvider= */null)
                            )
                        } else {
                            outputBuilder.setInvalidOutputPath(StringEncoding.internalToUnicode(output.getExecPathString()))
                        }
                    } catch (e: IOException) {
                        if (!warned) {
                            outputLoggingFailed.set(true)
                            warned = true
                            logger.atInfo().withCause(e).log(
                                "Failed to log outputs of spawn with mnemonic %s and primary output %s",
                                spawn.getMnemonic(),
                                com.google.common.collect.Iterables.getFirst<T?>(
                                    spawn.getOutputFiles(),  /* not reached */
                                    null
                                )
                            )
                        }
                        outputBuilder.setInvalidOutputPath(StringEncoding.internalToUnicode(output.getExecPathString()))
                    }
                    builder.addOutputs(outputBuilder)
                }

                builder.setExitCode(result.exitCode())
                if (result.status() !== SpawnResult.Status.SUCCESS) {
                    builder.setStatus(StringEncoding.internalToUnicode(result.status().toString()))
                }
                builder.setRunner(StringEncoding.internalToUnicode(result.getRunnerName()))
                builder.setCacheHit(result.isCacheHit())
                builder.setRemotable(Spawns.mayBeExecutedRemotely(spawn))
                builder.setCacheable(Spawns.mayBeCached(spawn))
                builder.setRemoteCacheable(Spawns.mayBeCachedRemotely(spawn))

                if (result.getDigest() != null) {
                    builder.setDigest(result.getDigest().toBuilder().clearHashFunctionName().build())
                }

                builder.setTimeoutMillis(timeout.toMillis())
                builder.setMetrics(SpawnLogContext.Companion.getSpawnMetricsProto(result))
                logEntryWithoutId(ExecLogEntrySupplier { ExecLogEntry.newBuilder().setSpawn(builder) })
            }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun logSymlinkAction(action: AbstractAction) {
        com.google.devtools.build.lib.profiler.Profiler.instance()
            .profile(com.google.devtools.build.lib.profiler.ProfilerTask.SPAWN_LOG, "logSymlinkAction").use { c ->
                val builder: ExecLogEntry.SymlinkAction.Builder = ExecLogEntry.SymlinkAction.newBuilder()
                val input: Artifact? = action.getPrimaryInput()
                if (input == null) {
                    // Symlinks to absolute paths are only used by FDO and not worth logging as they can be
                    // treated just like source files.
                    return
                }
                builder.setInputPath(StringEncoding.internalToUnicode(input.getExecPathString()))
                builder.setOutputPath(StringEncoding.internalToUnicode(action.getPrimaryOutput().getExecPathString()))

                val label: Label? = action.getOwner().getLabel()
                if (label != null) {
                    builder.setTargetLabel(StringEncoding.internalToUnicode(label.getCanonicalForm()))
                }
                builder.setMnemonic(StringEncoding.internalToUnicode(action.getMnemonic()))
                logEntryWithoutId(ExecLogEntrySupplier { ExecLogEntry.newBuilder().setSymlinkAction(builder) })
            }
    }

    /**
     * Logs the inputs.
     * 
     * @return the entry ID of the [ExecLogEntry.InputSet] describing the inputs, or 0 if there
     * are no inputs.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun logInputs(
        spawn: Spawn,
        inputMetadataProvider: InputMetadataProvider,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem
    ): Int {
        return logInputSet(
            spawn.getInputFiles(),
            inputMetadataProvider,
            fileSystem,  /* shared= */
            false,
            "TestRunner" == spawn.getMnemonic()
        )
    }

    /**
     * Logs the tool inputs.
     * 
     * @return the entry ID of the [ExecLogEntry.InputSet] describing the tool inputs, or 0 if
     * there are no tool inputs.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun logTools(
        spawn: Spawn,
        inputMetadataProvider: InputMetadataProvider,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem
    ): Int {
        return logInputSet(
            spawn.getToolFiles(),
            inputMetadataProvider,
            fileSystem,  /* shared= */
            true,
            "TestRunner" == spawn.getMnemonic()
        )
    }

    /**
     * Logs a nested set.
     * 
     * @param set the nested set
     * @param shared whether this nested set is likely to be a transitive member of other sets
     * @param isTestRunnerSpawn whether this nested set is logged for a test runner spawn
     * @return the entry ID of the [ExecLogEntry.InputSet] describing the nested set, or 0 if
     * the nested set is empty.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun logInputSet(
        set: NestedSet<out ActionInput?>,
        inputMetadataProvider: InputMetadataProvider,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem,
        shared: Boolean,
        isTestRunnerSpawn: Boolean
    ): Int {
        if (set.isEmpty()) {
            return 0
        }

        return logEntry(
            if (shared) set.toNode() else null,
            ExecLogEntrySupplier {
                val builder: ExecLogEntry.InputSet.Builder = ExecLogEntry.InputSet.newBuilder()
                for (transitive in set.getNonLeaves()) {
                    com.google.common.base.Preconditions.checkState(!transitive.isEmpty())
                    builder.addTransitiveSetIds(
                        logInputSet(
                            transitive,
                            inputMetadataProvider,
                            fileSystem,  /* shared= */
                            true,
                            isTestRunnerSpawn
                        )
                    )
                }

                for (input in set.getLeaves()) {
                    if (input is Artifact && input.isRunfilesTree()) {
                        val runfilesTree: RunfilesTree =
                            inputMetadataProvider.getRunfilesMetadata(input).getRunfilesTree()
                        builder.addInputIds(
                            logRunfilesTree(
                                runfilesTree,
                                inputMetadataProvider,
                                fileSystem,  // Runfiles of non-test spawns are tool inputs and thus potentially reused
                                // between spawns. Runfiles of test spawns are reused if the test is attempted
                                // multiple times in the same build; in this case, the runfiles tree caches
                                // its mapping.
                                !isTestRunnerSpawn || runfilesTree.isMappingCached()
                            )
                        )
                        continue
                    }

                    if (input is Artifact && input.isFileset()) {
                        // The fileset symlink tree is always materialized on disk.
                        builder.addInputIds(
                            logDirectory(
                                input,
                                fileSystem.getPath(execRoot.getRelative(input.getExecPath())),
                                inputMetadataProvider
                            )
                        )
                    }

                    builder.addInputIds(logInput(input, inputMetadataProvider, fileSystem))
                }
                ExecLogEntry.newBuilder().setInputSet(builder)
            })
    }

    /**
     * Logs a nested set of [SymlinkEntry].
     * 
     * @return the entry ID of the [ExecLogEntry.SymlinkEntrySet] describing the nested set, or
     * 0 if the nested set is empty.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun logSymlinkEntries(
        symlinks: NestedSet<SymlinkEntry?>,
        inputMetadataProvider: InputMetadataProvider?,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem
    ): Int {
        if (symlinks.isEmpty()) {
            return 0
        }

        return logEntry(
            symlinks.toNode(),
            ExecLogEntrySupplier {
                val builder: ExecLogEntry.SymlinkEntrySet.Builder = ExecLogEntry.SymlinkEntrySet.newBuilder()
                for (transitive in symlinks.getNonLeaves()) {
                    com.google.common.base.Preconditions.checkState(!transitive.isEmpty())
                    builder.addTransitiveSetIds(
                        logSymlinkEntries(transitive, inputMetadataProvider, fileSystem)
                    )
                }

                for (input in symlinks.getLeaves()) {
                    builder.putDirectEntries(
                        StringEncoding.internalToUnicode(input.getPathString()),
                        logInput(input.getArtifact(), inputMetadataProvider, fileSystem)
                    )
                }
                ExecLogEntry.newBuilder().setSymlinkEntrySet(builder)
            })
    }

    /**
     * Logs a single input that is either a file, a directory or a symlink.
     * 
     * @return the entry ID of the [ExecLogEntry] describing the input.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun logInput(
        input: ActionInput,
        inputMetadataProvider: InputMetadataProvider?,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem
    ): Int {
        val path: com.google.devtools.build.lib.vfs.Path = fileSystem.getPath(execRoot.getRelative(input.getExecPath()))
        if (isInputDirectory(input, inputMetadataProvider)) {
            return logDirectory(input, path, inputMetadataProvider)
        } else if (input.isSymlink()) {
            return logUnresolvedSymlink(input, path, inputMetadataProvider)
        } else {
            return logFile(input, path, inputMetadataProvider)
        }
    }

    /**
     * Logs a file.
     * 
     * @param input the input representing the file.
     * @param path the path to the file, which must have already been verified to be of the correct
     * type.
     * @param inputMetadataProvider provides metadata for inputs; null if logging an output
     * @return the entry ID of the [ExecLogEntry.File] describing the file.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun logFile(
        input: ActionInput, path: com.google.devtools.build.lib.vfs.Path?, inputMetadataProvider: InputMetadataProvider?
    ): Int {
        com.google.common.base.Preconditions.checkState(input !is VirtualActionInput.EmptyActionInput)

        return logEntry( // A ParamFileActionInput is never shared between spawns.
            if (input is ParamFileActionInput) null else input.getExecPathString(),
            ExecLogEntrySupplier {
                val builder: ExecLogEntry.File.Builder = ExecLogEntry.File.newBuilder()
                builder.setPath(StringEncoding.internalToUnicode(input.getExecPathString()))

                val digest: Digest? =
                    computeDigest(
                        input,
                        path,
                        inputMetadataProvider,
                        xattrProvider,
                        digestHashFunction,  /* includeHashFunctionName= */
                        false
                    )

                builder.setDigest(digest)
                ExecLogEntry.newBuilder().setFile(builder)
            })
    }

    /**
     * Logs a directory.
     * 
     * 
     * This may be either a source directory, a fileset or an output directory. For runfiles,
     * [.logRunfilesTree] must be used instead.
     * 
     * @param input the input representing the directory.
     * @param root the path to the directory, which must have already been verified to be of the
     * correct type.
     * @param inputMetadataProvider provides metadata for inputs; null if logging an output
     * @return the entry ID of the [ExecLogEntry.Directory] describing the directory.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun logDirectory(
        input: ActionInput, root: com.google.devtools.build.lib.vfs.Path?, inputMetadataProvider: InputMetadataProvider?
    ): Int {
        return logEntry(
            input.getExecPathString(),
            ExecLogEntrySupplier {
                ExecLogEntry.newBuilder()
                    .setDirectory(
                        ExecLogEntry.Directory.newBuilder()
                            .setPath(StringEncoding.internalToUnicode(input.getExecPathString()))
                            .addAllFiles(expandDirectory(root, inputMetadataProvider))
                    )
            })
    }

    /**
     * Logs a runfiles directory by storing the information in its [RunfilesTree].
     * 
     * 
     * Since runfiles trees can be very large and, for tests, are only used by a single spawn, we
     * store them in the log as a special entry that references the nested set of artifacts instead of
     * as a flat directory.
     * 
     * @param shared whether this runfiles tree is likely to be contained in more than one Spawn's
     * inputs
     * @param inputMetadataProvider provides metadata for inputs
     * @return the entry ID of the [ExecLogEntry.RunfilesTree] describing the directory.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun logRunfilesTree(
        runfilesTree: RunfilesTree,
        inputMetadataProvider: InputMetadataProvider,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem,
        shared: Boolean
    ): Int {
        return logEntry(
            if (shared) runfilesTree.getExecPath().getPathString() else null,
            ExecLogEntrySupplier {
                com.google.common.base.Preconditions.checkState(workspaceName == runfilesTree.getWorkspaceName())
                val builder: ExecLogEntry.RunfilesTree.Builder =
                    ExecLogEntry.RunfilesTree.newBuilder()
                        .setPath(StringEncoding.internalToUnicode(runfilesTree.getExecPath().getPathString()))

                builder.setInputSetId(
                    logInputSet(
                        runfilesTree.getArtifactsAtCanonicalLocationsForLogging(),
                        inputMetadataProvider,
                        fileSystem,  // The runfiles tree itself is shared, but the nested set is unique to the tree as
                        // it contains the executable.
                        /* shared= */
                        false,  // This value only matters for nested sets that may contain runfiles trees, but
                        // these are never nested.
                        /* isTestRunnerSpawn= */
                        false
                    )
                )
                builder.setSymlinksId(
                    logSymlinkEntries(
                        runfilesTree.getSymlinksForLogging(), inputMetadataProvider, fileSystem
                    )
                )
                builder.setRootSymlinksId(
                    logSymlinkEntries(
                        runfilesTree.getRootSymlinksForLogging(), inputMetadataProvider, fileSystem
                    )
                )
                builder.addAllEmptyFiles(
                    com.google.common.collect.Iterables.transform<F?, T?>(
                        runfilesTree.getEmptyFilenamesForLogging(),
                        com.google.common.base.Function { obj: F? -> obj.getPathString() })
                )
                val repoMappingManifest: Artifact? = runfilesTree.getRepoMappingManifestForLogging()
                if (repoMappingManifest != null) {
                    builder.setRepoMappingManifest(
                        ExecLogEntry.File.newBuilder()
                            .setDigest(
                                computeDigest(
                                    repoMappingManifest,
                                    repoMappingManifest.getPath(),
                                    inputMetadataProvider,
                                    xattrProvider,
                                    digestHashFunction,  /* includeHashFunctionName= */
                                    false
                                )
                            )
                    )
                }
                ExecLogEntry.newBuilder().setRunfilesTree(builder)
            })
    }

    /**
     * Expands a directory.
     * 
     * @param root the path to the directory
     * @param inputMetadataProvider provides metadata for inputs; null if logging an output
     * @return the list of files transitively contained in the directory
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun expandDirectory(
        root: com.google.devtools.build.lib.vfs.Path?, inputMetadataProvider: InputMetadataProvider?
    ): MutableList<ExecLogEntry.File?> {
        val files: java.util.ArrayList<ExecLogEntry.File?> = java.util.ArrayList<ExecLogEntry.File?>()
        visitDirectory(
            root,
            DirectoryChildVisitor { child: com.google.devtools.build.lib.vfs.Path? ->
                val digest: Digest? =
                    computeDigest( /* input= */
                        null,
                        child,
                        inputMetadataProvider,
                        xattrProvider,
                        digestHashFunction,  /* includeHashFunctionName= */
                        false
                    )
                val file: ExecLogEntry.File? =
                    ExecLogEntry.File.newBuilder()
                        .setPath(StringEncoding.internalToUnicode(child.relativeTo(root).getPathString()))
                        .setDigest(digest)
                        .build()
                synchronized(files) {
                    files.add(file)
                }
            })

        files.sort(EXEC_LOG_ENTRY_FILE_COMPARATOR)

        return files
    }

    /**
     * Logs an unresolved symlink.
     * 
     * @param input the input representing the unresolved symlink.
     * @param path the path to the unresolved symlink, which must have already been verified to be of
     * the correct type.
     * @param inputMetadataProvider provides metadata for inputs; null if logging an output
     * @return the entry ID of the [ExecLogEntry.UnresolvedSymlink] describing the unresolved
     * symlink.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun logUnresolvedSymlink(
        input: ActionInput, path: com.google.devtools.build.lib.vfs.Path, inputMetadataProvider: InputMetadataProvider?
    ): Int {
        return logEntry(
            input.getExecPathString(),
            ExecLogEntrySupplier {
                var metadata: FileArtifactValue? = null
                if (inputMetadataProvider != null) {
                    metadata = inputMetadataProvider.getInputMetadata(input)
                }
                val targetPath: String?
                if (metadata != null) {
                    checkState(metadata.getType().isSymlink(), metadata)
                    targetPath = metadata.getUnresolvedSymlinkTarget()
                } else {
                    targetPath = path.readSymbolicLink().getPathString()
                }
                ExecLogEntry.newBuilder()
                    .setUnresolvedSymlink(
                        ExecLogEntry.UnresolvedSymlink.newBuilder()
                            .setPath(StringEncoding.internalToUnicode(input.getExecPathString()))
                            .setTargetPath(StringEncoding.internalToUnicode(targetPath))
                    )
            })
    }

    /**
     * Ensures an entry is written to the log without an ID.
     * 
     * @param supplier called to compute the entry; may cause other entries to be logged
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun logEntryWithoutId(supplier: ExecLogEntrySupplier) {
        com.google.devtools.build.lib.profiler.Profiler.instance()
            .profile(com.google.devtools.build.lib.profiler.ProfilerTask.SPAWN_LOG, "logEntryWithoutId").use { c ->
                logEntryWithoutIdSynchronized(supplier)
            }
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun logEntryWithoutIdSynchronized(supplier: ExecLogEntrySupplier) {
        com.google.devtools.build.lib.profiler.Profiler.instance()
            .profile(com.google.devtools.build.lib.profiler.ProfilerTask.SPAWN_LOG, "logEntryWithoutId/synchronized")
            .use { c ->
                outputStream.write(supplier.get().build())
            }
    }

    /**
     * Ensures an entry is written to the log and returns its assigned ID.
     * 
     * 
     * If an entry with the same non-null key was previously added to the log, its recorded ID is
     * returned. Otherwise, the entry is computed, assigned an ID, and written to the log.
     * 
     * @param key the key, or null if the ID shouldn't be recorded
     * @param supplier called to compute the entry; may cause other entries to be logged
     * @return the entry ID
     */
    @com.google.errorprone.annotations.CheckReturnValue
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun logEntry(key: Any?, supplier: ExecLogEntrySupplier): Int {
        com.google.devtools.build.lib.profiler.Profiler.instance()
            .profile(com.google.devtools.build.lib.profiler.ProfilerTask.SPAWN_LOG, "logEntry").use { c ->
                return logEntrySynchronized(key, supplier)
            }
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun logEntrySynchronized(key: Any?, supplier: ExecLogEntrySupplier): Int {
        com.google.devtools.build.lib.profiler.Profiler.instance()
            .profile(com.google.devtools.build.lib.profiler.ProfilerTask.SPAWN_LOG, "logEntry/synchronized").use { c ->
                if (key == null) {
                    // No need to check for a previously added entry.
                    val entry: ExecLogEntry.Builder = supplier.get()
                    val id = nextEntryId++
                    outputStream.write(entry.setId(id).build())
                    return id
                }
                com.google.common.base.Preconditions.checkState(key is NestedSet.Node || key is String)

                // Check for a previously added entry.
                var id: Int = entryMap.getOrDefault(key, 0)
                if (id != 0) {
                    return id
                }

                // Compute a fresh entry and log it.
                // The following order of operations is crucial to ensure that this entry is preceded by any
                // entries it references, which in turn ensures the log can be parsed in a single pass.
                val entry: ExecLogEntry.Builder = supplier.get()
                id = nextEntryId++
                entryMap.put(key, id)
                outputStream.write(entry.setId(id).build())
                return id
            }
    }

    @Throws(IOException::class)
    override fun close() {
        if (outputLoggingFailed.get()) {
            reporter.handle(
                com.google.devtools.build.lib.events.Event.Companion.warn(
                    "The compact execution log is incomplete because some outputs could not be read."
                            + " Refer to the server log file for details."
                )
            )
        }
        outputStream.close()
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private val EXEC_LOG_ENTRY_FILE_COMPARATOR: java.util.Comparator<ExecLogEntry.File?>? =
            java.util.Comparator.comparing<ExecLogEntry.File?, Any?>(ExecLogEntry.File::getPath)

        private val VISITOR_POOL: ForkJoinPool =
            com.google.devtools.build.lib.concurrent.NamedForkJoinPool.Companion.newNamedPool(
                "execlog-directory-visitor", java.lang.Runtime.getRuntime().availableProcessors()
            )

        @Throws(IOException::class)
        private fun getOutputStream(out: java.io.OutputStream?, name: String?): MessageOutputStream<ExecLogEntry?> {
            // Use an AsynchronousMessageOutputStream so that compression and I/O occur in a separate
            // thread. This ensures concurrent writes don't tear and avoids blocking execution.
            return AsynchronousMessageOutputStream<T?>(name, ZstdOutputStream(out))
        }
    }
}
