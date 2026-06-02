// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.actions.Action

/**
 * Abstract implementation of [ActionInputPrefetcher] which implements the orchestration of
 * prefeching multiple inputs so subclasses can focus on prefetching / downloading single input.
 */
abstract class AbstractActionInputPrefetcher protected constructor(
    reporter: com.google.devtools.build.lib.events.Reporter?,
    execRoot: com.google.devtools.build.lib.vfs.Path,
    tempPathGenerator: TempPathGenerator,
    remoteOutputChecker: RemoteOutputChecker,
    outputDirectoryHelper: ActionOutputDirectoryHelper?,
    outputPermissions: OutputPermissions
) : ActionInputPrefetcher {
    private val reporter: com.google.devtools.build.lib.events.Reporter?
    private val downloadCache: NoResult<com.google.devtools.build.lib.vfs.Path?> =
        NoResult.Companion.create<com.google.devtools.build.lib.vfs.Path?>()
    private val tempPathGenerator: TempPathGenerator
    private val outputPermissions: OutputPermissions

    protected val execRoot: com.google.devtools.build.lib.vfs.Path
    protected val remoteOutputChecker: RemoteOutputChecker

    protected val outputDirectoryHelper: ActionOutputDirectoryHelper?

    /** The state of a directory tracked by [DirectoryTracker], as explained below.  */
    internal enum class DirectoryState {
        PERMANENTLY_WRITABLE,
        TEMPORARILY_WRITABLE,
        OUTPUT_PERMISSIONS
    }

    /**
     * Returns the metadata for an [ActionInput].
     * 
     * 
     * This will generally call through to a [InputMetadataProvider] or [ ] and ask for the metadata of either an input or an output artifact.
     */
    @com.google.common.annotations.VisibleForTesting
    interface MetadataSupplier {
        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun getMetadata(actionInput: ActionInput?): FileArtifactValue?
    }

    /**
     * Tracks directory permissions to minimize filesystem operations.
     * 
     * 
     * Throughout the prefetcher, [Path.setWritable] and [Path.chmod] calls on output
     * directories must go through the methods in this class.
     */
    private inner class DirectoryTracker {
        private val directoryStateMap: ConcurrentHashMap<com.google.devtools.build.lib.vfs.Path?, DirectoryState?> =
            ConcurrentHashMap<com.google.devtools.build.lib.vfs.Path?, DirectoryState?>()

        /**
         * Marks a directory as temporarily writable.
         * 
         * 
         * A temporarily writable directory may have its output permissions set by a later call to
         * [.setOutputPermissions], unless [.setPermanentlyWritable] is called in the
         * interim.
         */
        @Throws(IOException::class)
        fun setTemporarilyWritable(dir: com.google.devtools.build.lib.vfs.Path) {
            setWritable(dir, DirectoryState.TEMPORARILY_WRITABLE)
        }

        /**
         * Marks a directory as permanently writable.
         * 
         * 
         * A permanently writable directory will never have its output permissions set by a later
         * call to [.setOutputPermissions].
         */
        @Throws(IOException::class)
        fun setPermanentlyWritable(dir: com.google.devtools.build.lib.vfs.Path) {
            setWritable(dir, DirectoryState.PERMANENTLY_WRITABLE)
        }

        @Throws(IOException::class)
        fun setWritable(dir: com.google.devtools.build.lib.vfs.Path, newState: DirectoryState?) {
            // Compare as fragments since execRoot may be located on a file system overlaying the host
            // file system where downloads are written to.
            if (!dir.asFragment().startsWith(execRoot.asFragment())) {
                return
            }
            val caughtException: AtomicReference<IOException?> = AtomicReference<IOException?>()

            directoryStateMap.compute(
                dir,
                java.util.function.BiFunction { unusedKey: com.google.devtools.build.lib.vfs.Path?, oldState: DirectoryState? ->
                    if (!forceRefetch(dir)
                        && (oldState == DirectoryState.TEMPORARILY_WRITABLE
                                || oldState == DirectoryState.PERMANENTLY_WRITABLE)
                    ) {
                        // Already writable, but must potentially upgrade from temporary to permanent.
                        return@compute if (newState == DirectoryState.PERMANENTLY_WRITABLE) newState else oldState
                    }
                    try {
                        if (outputDirectoryHelper != null) {
                            outputDirectoryHelper.createOutputDirectory(dir, execRoot)
                        } else {
                            dir.createDirectoryAndParents()
                        }
                        dir.setWritable(true)
                    } catch (e: IOException) {
                        caughtException.set(e)
                        return@compute oldState
                    }
                    newState
                })

            if (caughtException.get() != null) {
                throw caughtException.get()
            }
        }

        /**
         * Sets the output permissions on a directory.
         * 
         * 
         * If [.setPermanentlyWritable] has been previously called on this directory, or if no
         * [.setTemporarilyWritable] call has intervened since the last call to [ ][.setOutputPermissions], this is a no-op. Otherwise, the output permissions are set.
         */
        @Throws(IOException::class)
        fun setOutputPermissions(dir: com.google.devtools.build.lib.vfs.Path) {
            val caughtException: AtomicReference<IOException?> = AtomicReference<IOException?>()

            directoryStateMap.compute(
                dir,
                java.util.function.BiFunction { unusedKey: com.google.devtools.build.lib.vfs.Path?, oldState: DirectoryState? ->
                    if (!forceRefetch(dir)
                        && (oldState == DirectoryState.OUTPUT_PERMISSIONS
                                || oldState == DirectoryState.PERMANENTLY_WRITABLE)
                    ) {
                        // Either the output permissions have already been set, or we're not changing the
                        // permissions ever again.
                        return@compute oldState
                    }
                    try {
                        dir.chmod(outputPermissions.getPermissionsMode())
                    } catch (e: IOException) {
                        caughtException.set(e)
                        return@compute oldState
                    }
                    DirectoryState.OUTPUT_PERMISSIONS
                })

            if (caughtException.get() != null) {
                throw caughtException.get()
            }
        }
    }

    private val directoryTracker = DirectoryTracker()

    /** A symlink in the output tree that points to another artifact's absolute path.  */
    internal class Symlink(
        linkPath: com.google.devtools.build.lib.vfs.Path?,
        targetPath: com.google.devtools.build.lib.vfs.Path?
    ) {
        val linkPath: com.google.devtools.build.lib.vfs.Path?
        val targetPath: com.google.devtools.build.lib.vfs.Path?

        init {
            this.targetPath = targetPath
            this.linkPath = linkPath
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(
                linkPath,
                "linkPath"
            )
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(
                targetPath,
                "targetPath"
            )
            com.google.common.base.Preconditions.checkArgument(
                linkPath != targetPath,
                "linkPath and targetPath must differ"
            )
        }

        companion object {
            fun of(
                linkPath: com.google.devtools.build.lib.vfs.Path?,
                targetPath: com.google.devtools.build.lib.vfs.Path?
            ): Symlink {
                return com.google.devtools.build.lib.remote.AbstractActionInputPrefetcher.Symlink(linkPath, targetPath)
            }
        }
    }

    init {
        this.reporter = reporter
        this.execRoot = execRoot
        this.tempPathGenerator = tempPathGenerator
        this.remoteOutputChecker = remoteOutputChecker
        this.outputDirectoryHelper = outputDirectoryHelper
        this.outputPermissions = outputPermissions
    }

    protected abstract fun canDownloadFile(
        path: com.google.devtools.build.lib.vfs.Path?,
        metadata: FileArtifactValue?
    ): Boolean

    /**
     * If true, then all previously acquired knowledge of the file system state of this path (e.g. the
     * existence of tree artifact directories or previously downloaded files) must be discarded.
     */
    protected abstract fun forceRefetch(path: com.google.devtools.build.lib.vfs.Path?): Boolean

    /**
     * Downloads file to the given path via its metadata.
     * 
     * @param tempPath the temporary path which the input should be written to.
     */
    @Throws(IOException::class)
    abstract fun doDownloadFile(
        action: ActionExecutionMetadata?,
        reporter: com.google.devtools.build.lib.events.Reporter?,
        input: ActionInput?,
        tempPath: com.google.devtools.build.lib.vfs.Path?,
        metadata: FileArtifactValue?,
        priority: Priority?,
        reason: Reason?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?

    @Throws(IOException::class)
    protected open fun prefetchVirtualActionInput(input: VirtualActionInput?) {
    }

    /**
     * Fetches remotely stored action outputs and stores them under their path in the output base.
     * 
     * 
     * The `inputs` may not contain any unexpanded directories.
     * 
     * 
     * This method is safe to be called concurrently from spawn runners before running any local
     * spawn.
     * 
     * @return a future that is completed once all downloads have finished.
     */
    public override fun prefetchFiles(
        action: ActionExecutionMetadata?,
        spawn: Spawn?,
        expandedInputs: java.util.function.Supplier<Iterable<out ActionInput?>?>,
        metadataProvider: InputMetadataProvider,
        priority: Priority?,
        reason: Reason?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        return prefetchFilesInterruptibly(
            action, expandedInputs.get(), metadataProvider::getInputMetadata, priority, reason
        )
    }

    /**
     * Fetches remotely stored action outputs and stores them under their path in the output base.
     * 
     * 
     * The `inputs` may not contain any unexpanded directories.
     * 
     * 
     * This method is safe to be called concurrently from spawn runners before running any local
     * spawn.
     * 
     * 
     * This method is similar to #prefetchFiles() above, but note that `metadataSupplier` may
     * throw [InterruptedException]. If it does, this method will propagate this exception in
     * the returned future.
     * 
     * @return a future that is completed once all downloads have finished.
     */
    fun prefetchFilesInterruptibly(
        action: ActionExecutionMetadata?,
        inputs: Iterable<out ActionInput>,
        metadataSupplier: MetadataSupplier,
        priority: Priority?,
        reason: Reason?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        val files: MutableList<ActionInput> = java.util.ArrayList<ActionInput>()

        for (input in inputs) {
            if (!RemoteOutputChecker.Companion.mayBeRemote(input)) {
                continue
            }

            // Skip empty tree artifacts (non-empty tree artifacts should have already been expanded).
            if (input.isDirectory()) {
                continue
            }

            files.add(input)
        }

        if (files.isEmpty()) {
            return com.google.common.util.concurrent.Futures.immediateVoidFuture()
        }

        // Collect the set of directories whose output permissions must be set at the end of this call.
        // This responsibility cannot lie with the downloading of an individual file, because multiple
        // files may be concurrently downloaded into the same directory within a single call to
        // prefetchFiles, and two concurrent calls to prefetchFiles may prefetch the same file. In the
        // latter case, the second call will have its downloads deduplicated against the first call, but
        // it must still synchronize on the output permissions having been set.
        val dirsWithOutputPermissions: MutableSet<com.google.devtools.build.lib.vfs.Path> =
            com.google.common.collect.Sets.newConcurrentHashSet<com.google.devtools.build.lib.vfs.Path?>()

        // Using plain futures to avoid RxJava overheads.
        val transfers: MutableList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
            java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>(files.size())
        Profiler.instance().profile("compose prefetches").use { s ->
            for (file in files) {
                transfers.add(
                    prefetchFile(
                        action, dirsWithOutputPermissions, metadataSupplier, file, priority, reason
                    )
                )
            }
        }
        val mergedTransfer: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?
        Profiler.instance().profile("mergeBulkTransfer").use { s ->
            mergedTransfer = com.google.devtools.build.lib.remote.util.Utils.mergeBulkTransfer(transfers)
        }
        return com.google.common.util.concurrent.Futures.transformAsync<java.lang.Void?, java.lang.Void?>(
            mergedTransfer,
            com.google.common.util.concurrent.AsyncFunction { unused: java.lang.Void? ->
                try {
                    // Set output permissions on tree artifact subdirectories, matching the behavior of
                    // SkyframeActionExecutor#checkOutputs for artifacts produced by local actions.
                    for (dir in dirsWithOutputPermissions) {
                        directoryTracker.setOutputPermissions(dir)
                    }
                } catch (e: IOException) {
                    return@transformAsync com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
                        e
                    )
                }
                com.google.common.util.concurrent.Futures.immediateVoidFuture()
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    private fun prefetchFile(
        action: ActionExecutionMetadata?,
        dirsWithOutputPermissions: MutableSet<com.google.devtools.build.lib.vfs.Path>,
        metadataSupplier: MetadataSupplier,
        input: ActionInput,
        priority: Priority?,
        reason: Reason?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        try {
            if (input is VirtualActionInput) {
                prefetchVirtualActionInput(input)
                return com.google.common.util.concurrent.Futures.immediateVoidFuture()
            }

            var inputPath: com.google.devtools.build.lib.vfs.Path =
                if (input is Artifact)
                    input.getPath()
                else
                    execRoot.getRelative(input.getExecPath())

            // Metadata may legitimately be missing, e.g. if this is an optional test output.
            val metadata: FileArtifactValue? = metadataSupplier.getMetadata(input)
            if (metadata == null) {
                return com.google.common.util.concurrent.Futures.immediateVoidFuture()
            }
            if (metadata.getType() === FileStateType.SYMLINK && !inputPath.startsWith(execRoot)) {
                return RxFutures.toListenableFuture(
                    plantUnresolvedSymlink(
                        inputPath.forHostFileSystem(),
                        PathFragment.create(metadata.getUnresolvedSymlinkTarget())
                    )
                )
            }
            if (!canDownloadFile(inputPath, metadata)) {
                return com.google.common.util.concurrent.Futures.immediateVoidFuture()
            }

            val symlink = maybeGetSymlink(input, inputPath, metadata, metadataSupplier)

            if (symlink != null) {
                // Symlink tracks the parent of a TreeFileArtifact, so the parent relative path has to be
                // translated relative to it.
                val parentRelativePath: PathFragment? = inputPath.relativeTo(symlink.linkPath)
                inputPath = symlink.targetPath.getRelative(parentRelativePath)
            }

            val treeRootPath: com.google.devtools.build.lib.vfs.Path? = maybeGetTreeRoot(input, metadataSupplier)

            var result: Completable =
                downloadFileNoCheckRx(
                    action,
                    input,
                    inputPath,
                    treeRootPath,
                    dirsWithOutputPermissions,
                    input,
                    metadata,
                    priority,
                    reason
                )

            if (symlink != null) {
                result = result.andThen(plantSymlink(symlink))
            }

            return RxFutures.toListenableFuture(result)
        } catch (e: IOException) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(e)
        } catch (e: java.lang.InterruptedException) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(e)
        }
    }

    /**
     * For an input belonging to a tree artifact, returns the resolved path of the tree artifact root.
     * Otherwise, returns null.
     * 
     * 
     * Some artifacts (notably, those created by `ctx.actions.symlink`) are materialized in
     * the output tree as a symlink to another artifact, as indicated by the [ ][FileArtifactValue.getResolvedPath] field in their metadata.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun maybeGetTreeRoot(
        input: ActionInput?,
        metadataSupplier: MetadataSupplier
    ): com.google.devtools.build.lib.vfs.Path? {
        if (input !is TreeFileArtifact) {
            return null
        }
        val treeArtifact: SpecialArtifact = input.getParent()
        val treeMetadata: FileArtifactValue? = metadataSupplier.getMetadata(treeArtifact)
        if (treeMetadata == null) {
            // There are two cases where tree metadata is legitimately not available:
            // (1) If the file is the output of an action expanded from an action template. In this
            //     case, the symlink optimization is intentionally not supported.
            // (2) If the file is part of an input fileset. In this case, a symlink has already been
            //     created, but we're currently unable to prefetch the file(s) it points to.
            // TODO: b/401575099 - Treating fileset more like runfiles could make the tree metadata
            //  available for case (2).
            return null
        }
        val resolvedPath: PathFragment? = treeMetadata.getResolvedPath()
        if (resolvedPath != null) {
            return treeArtifact.getPath().getFileSystem().getPath(resolvedPath)
        }
        return treeArtifact.getPath()
    }

    /**
     * Returns the symlink to be planted in the output tree for artifacts that are prefetched into a
     * different location.
     * 
     * 
     * Some artifacts (notably, those created by `ctx.actions.symlink`) are materialized in
     * the output tree as a symlink to another artifact, as indicated by the [ ][FileArtifactValue.getResolvedPath] field in their (or their parent tree artifact's) metadata.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun maybeGetSymlink(
        input: ActionInput?,
        inputPath: com.google.devtools.build.lib.vfs.Path,
        metadata: FileArtifactValue,
        metadataSupplier: MetadataSupplier
    ): Symlink? {
        if (input is TreeFileArtifact) {
            val treeArtifact: SpecialArtifact = input.getParent()
            val treeMetadata: FileArtifactValue? = metadataSupplier.getMetadata(treeArtifact)
            if (treeMetadata == null) {
                // There are two cases where tree metadata is legitimately not available:
                // (1) If the file is the output of an action expanded from an action template. In this
                //     case, the symlink optimization is intentionally not supported.
                // (2) If the file is part of an input fileset. In this case, a symlink has already been
                //     created, but we're currently unable to prefetch the file(s) it points to.
                // TODO: b/401575099 - Treating fileset more like runfiles could make the tree metadata
                //  available for case (2).
                return null
            }
            return maybeGetSymlink(treeArtifact, treeArtifact.getPath(), treeMetadata, metadataSupplier)
        }
        if (metadata.getResolvedPath() == null) {
            return null
        }
        val resolvedPath: com.google.devtools.build.lib.vfs.Path =
            inputPath.getFileSystem().getPath(metadata.getResolvedPath())
        if (resolvedPath == inputPath) {
            return null
        }
        return com.google.devtools.build.lib.remote.AbstractActionInputPrefetcher.Symlink.Companion.of(
            inputPath,
            resolvedPath
        )
    }

    private fun downloadFileNoCheckRx(
        action: ActionExecutionMetadata?,
        input: ActionInput?,
        path: com.google.devtools.build.lib.vfs.Path,
        treeRoot: com.google.devtools.build.lib.vfs.Path?,
        dirsWithOutputPermissions: MutableSet<com.google.devtools.build.lib.vfs.Path>,
        actionInput: ActionInput?,
        metadata: FileArtifactValue,
        priority: Priority?,
        reason: Reason?
    ): Completable {
        // If the path to be prefetched is a non-dangling symlink, prefetch its target path instead.
        // Note that this only applies to symlinks created by spawns (or, currently, with the internal
        // version of BwoB); symlinks created in-process through an ActionFileSystem should have already
        // been canonicalized by maybeGetSymlink.
        var path: com.google.devtools.build.lib.vfs.Path = path
        var treeRoot: com.google.devtools.build.lib.vfs.Path? = treeRoot
        try {
            if (treeRoot != null) {
                val treeRootRelativePath: PathFragment? = path.relativeTo(treeRoot)
                treeRoot = maybeResolveSymlink(treeRoot)
                path = treeRoot.getRelative(treeRootRelativePath)
            } else {
                path = maybeResolveSymlink(path)
            }
        } catch (e: IOException) {
            return Completable.error(e)
        }

        if (treeRoot != null && actionInput is Artifact
            && actionInput.isChildOfDeclaredDirectory()
        ) {
            // Arrange for the output permissions to be set on every directory inside the tree artifact.
            // This must be done at assembly time to ensure that the permissions are set before the
            // prefetchFiles call returns, even when the actual downloads are deduplicated against a
            // concurrent call. See finalizeDownload for why we don't do so in other cases.
            var dir: com.google.devtools.build.lib.vfs.Path? = path.getParentDirectory()
            while (dir.startsWith(treeRoot)
            ) {
                if (!dirsWithOutputPermissions.add(dir)) {
                    break
                }
                dir = dir.getParentDirectory()
            }
        }

        // Downloads should always be written to the "actual" host file system, not any overlays.
        val finalPath: com.google.devtools.build.lib.vfs.Path = path.forHostFileSystem()

        val download: Completable? =
            usingTempPath(
                TaskWithTempPath { tempPath: com.google.devtools.build.lib.vfs.Path?, alreadyDeleted: AtomicBoolean? ->
                    RxFutures.toCompletable(
                        io.reactivex.rxjava3.functions.Supplier {
                            doDownloadFile(
                                action,
                                reporter,
                                input,
                                tempPath.forHostFileSystem(),
                                metadata,
                                priority,
                                reason
                            )
                        },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )
                        .doOnComplete(
                            io.reactivex.rxjava3.functions.Action {
                                finalizeDownload(
                                    metadata,
                                    tempPath.forHostFileSystem(),
                                    finalPath,
                                    dirsWithOutputPermissions
                                )
                                alreadyDeleted.set(true)
                            })
                })

        return downloadCache.execute(
            finalPath,
            Completable.defer(
                io.reactivex.rxjava3.functions.Supplier {
                    if (shouldDownloadFile(finalPath, metadata)) {
                        return@defer download
                    }
                    Completable.complete()
                }),
            forceRefetch(finalPath)
        )
    }

    @Throws(IOException::class)
    private fun finalizeDownload(
        metadata: FileArtifactValue,
        tmpPath: com.google.devtools.build.lib.vfs.Path,
        finalPath: com.google.devtools.build.lib.vfs.Path,
        dirsWithOutputPermissions: MutableSet<com.google.devtools.build.lib.vfs.Path>
    ) {
        val parentDir: com.google.devtools.build.lib.vfs.Path =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path>(finalPath.getParentDirectory())

        // Compare as fragments since execRoot may be located on a file system overlaying the host
        // file system where the download is written to.
        if (finalPath.asFragment().startsWith(execRoot.asFragment())) {
            // Ensure the parent directory exists and is writable. We cannot rely on this precondition to
            // have been established by the execution of the owning action in a previous invocation, since
            // the output tree may have been externally modified in between invocations.
            if (dirsWithOutputPermissions.contains(parentDir)) {
                // The file belongs to a tree artifact created by an action that declared an output
                // directory (as opposed to an action template expansion). The output permissions should be
                // set on the parent directory after prefetching.
                directoryTracker.setTemporarilyWritable(parentDir)
            } else {
                // One of the following must apply:
                //   (1) The file does not belong to a tree artifact.
                //   (2) The file belongs to a tree artifact created by an action template expansion.
                // In case (1), the parent directory is a package or a subdirectory of a package, and should
                // remain writable. In case (2), even though we arguably ought to set the output permissions
                // on the parent directory to match local execution, we choose not to do it and avoid the
                // additional implementation complexity required to detect a race condition between
                // concurrent calls touching the same directory.
                directoryTracker.setPermanentlyWritable(parentDir)
            }
        } else {
            parentDir.createDirectoryAndParents()
        }

        // Set output permissions on files, matching the behavior of SkyframeActionExecutor#checkOutputs
        // for artifacts produced by local actions.
        tmpPath.chmod(outputPermissions.getPermissionsMode())
        com.google.devtools.build.lib.vfs.FileSystemUtils.moveFile(tmpPath, finalPath)

        // Set the contents proxy when supported, to make future modification checks cheaper.
        metadata.setContentsProxy(FileContentsProxy.create(finalPath.stat()))
    }

    private interface TaskWithTempPath {
        fun run(tempPath: com.google.devtools.build.lib.vfs.Path?, alreadyDeleted: AtomicBoolean?): Completable?
    }

    /**
     * Runs a task with a temporary path.
     * 
     * 
     * The temporary path will be deleted once the task is done. Set `alreadyDeleted` to
     * signal that deletion is no longer needed.
     */
    private fun usingTempPath(task: TaskWithTempPath): Completable? {
        val alreadyDeleted: AtomicBoolean = AtomicBoolean(false)
        return Completable.using<com.google.devtools.build.lib.vfs.Path?>(
            io.reactivex.rxjava3.functions.Supplier { tempPathGenerator.generateTempPath() },
            io.reactivex.rxjava3.functions.Function { tempPath: com.google.devtools.build.lib.vfs.Path? ->
                task.run(
                    tempPath,
                    alreadyDeleted
                )
            },
            io.reactivex.rxjava3.functions.Consumer { tempPath: com.google.devtools.build.lib.vfs.Path? ->
                if (!alreadyDeleted.get()) {
                    deletePartialDownload(tempPath)
                }
            },  // Clean up after the upstream is disposed to ensure tempPath won't be touched further.
            /* eager= */
            false
        )
    }

    private fun plantSymlink(symlink: Symlink): Completable {
        return downloadCache.execute(
            symlink.linkPath,
            Completable.defer(
                io.reactivex.rxjava3.functions.Supplier {
                    // Delete the link path if it already exists. This is the case for tree artifacts,
                    // whose root directory is created before the action runs.
                    symlink.linkPath.delete()
                    symlink.linkPath.createSymbolicLink(symlink.targetPath)
                    Completable.complete()
                }),
            forceRefetch(symlink.linkPath)
        )
    }

    private fun plantUnresolvedSymlink(
        linkPath: com.google.devtools.build.lib.vfs.Path,
        target: PathFragment?
    ): Completable? {
        return downloadCache.executeIfNot(
            linkPath,
            Completable.defer(
                io.reactivex.rxjava3.functions.Supplier {
                    linkPath.delete()
                    linkPath.createSymbolicLink(target)
                    Completable.complete()
                })
        )
    }

    fun downloadedFiles(): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?> {
        return downloadCache.getFinishedTasks()
    }

    fun downloadsInProgress(): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?> {
        return downloadCache.getInProgressTasks()
    }

    @com.google.common.annotations.VisibleForTesting
    fun getDownloadCache(): NoResult<com.google.devtools.build.lib.vfs.Path?> {
        return downloadCache
    }

    fun shutdown() {
        downloadCache.shutdown()
        while (true) {
            try {
                downloadCache.awaitTermination()
                break
            } catch (ignored: java.lang.InterruptedException) {
                downloadCache.shutdownNow()
            }
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun finalizeAction(action: Action, outputMetadataStore: OutputMetadataStore) {
        val outputsToDownload: MutableList<Artifact> = java.util.ArrayList<Artifact>()
        for (output in action.getOutputs()) {
            if (outputMetadataStore.artifactOmitted(output)) {
                continue
            }

            val metadata: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                outputMetadataStore.getOutputMetadata(output)
            if (!canDownloadFile(output.getPath(), metadata)) {
                continue
            }

            if (output.isTreeArtifact()) {
                outputMetadataStore
                    .getTreeArtifactValue(output as SpecialArtifact)
                    .getChildValues()
                    .forEach(
                        { child, childMetadata ->
                            if (remoteOutputChecker.shouldDownloadOutput(child, childMetadata)) {
                                outputsToDownload.add(child)
                            }
                        })
            } else {
                if (remoteOutputChecker.shouldDownloadOutput(output, metadata)) {
                    outputsToDownload.add(output)
                }
            }
        }

        if (!outputsToDownload.isEmpty()) {
            Profiler.instance().profile(ProfilerTask.REMOTE_DOWNLOAD, "Download outputs").use { s ->
                com.google.devtools.build.lib.remote.util.Utils.getFromFuture<java.lang.Void?>(
                    prefetchFilesInterruptibly(
                        action,
                        outputsToDownload,
                        MetadataSupplier { output: ActionInput? -> outputMetadataStore.getOutputMetadata(output as Artifact?) },
                        Priority.HIGH,
                        Reason.OUTPUTS
                    )
                )
            }
        }
    }

    @Throws(java.lang.InterruptedException::class)
    fun flushOutputTree() {
        downloadCache.awaitInProgressTasks()
    }

    fun getRemoteOutputChecker(): RemoteOutputChecker {
        return remoteOutputChecker
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        @Throws(IOException::class)
        private fun shouldDownloadFile(
            path: com.google.devtools.build.lib.vfs.Path,
            metadata: FileArtifactValue
        ): Boolean {
            val stat: FileStatus? = path.statIfFound()
            if (stat == null) {
                return true
            }

            // If an action output is stale, Skyframe will delete it prior to action execution. However,
            // this doesn't apply to spawn outputs that aren't action outputs, or to files in external repos
            // that are remote repo contents cache hits. To avoid incorrectly reusing one such stale file,
            // check for its up-to-dateness here.
            if (stat.getSize() != metadata.getSize()) {
                return true
            }
            val contentsProxy: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                metadata.getContentsProxy()
            if (contentsProxy != null && contentsProxy.equals(FileContentsProxy.create(stat))) {
                return false
            }

            var digest: ByteArray? = path.getFastDigest()
            if (digest == null) {
                digest = path.getDigest()
            }
            return !java.util.Arrays.equals(digest, metadata.getDigest())
        }

        @Throws(IOException::class)
        private fun resolveOneSymlink(path: com.google.devtools.build.lib.vfs.Path): com.google.devtools.build.lib.vfs.Path {
            val targetPathFragment: PathFragment = path.readSymbolicLink()
            if (targetPathFragment.isAbsolute()) {
                return path.getFileSystem().getPath(targetPathFragment)
            } else {
                return com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(path.getParentDirectory())
                    .getRelative(targetPathFragment)
            }
        }

        @Throws(IOException::class)
        private fun maybeResolveSymlink(path: com.google.devtools.build.lib.vfs.Path): com.google.devtools.build.lib.vfs.Path {
            // Potentially resolves a symlink to its target path. This differs from
            // Path#resolveSymbolicLinks() that:
            //   1. Path#resolveSymbolicLinks() checks each segment of the path, but we assume there is no
            //      intermediate symlink because they should've been already normalized for outputs.
            //   2. In case of dangling symlink, we return the target path instead of throwing
            //      FileNotFoundException because we want to download output to that target path.
            var path: com.google.devtools.build.lib.vfs.Path = path
            var maxAttempt = 32
            while (path.isSymbolicLink() && maxAttempt-- > 0) {
                val resolvedPath: com.google.devtools.build.lib.vfs.Path = resolveOneSymlink(path)
                if (resolvedPath.asFragment() == path.asFragment()) {
                    throw FileSymlinkLoopException(path.getPathString() + com.google.devtools.build.lib.vfs.FileSystem.ERR_TOO_MANY_SYMLINKS)
                }
                path = resolvedPath
            }
            if (maxAttempt <= 0) {
                throw FileSymlinkLoopException(path.getPathString() + com.google.devtools.build.lib.vfs.FileSystem.ERR_TOO_MANY_SYMLINKS)
            }
            return path
        }

        private fun deletePartialDownload(path: com.google.devtools.build.lib.vfs.Path) {
            try {
                path.delete()
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log(
                    "Failed to delete output file after incomplete download: %s", path
                )
            }
        }
    }
}
