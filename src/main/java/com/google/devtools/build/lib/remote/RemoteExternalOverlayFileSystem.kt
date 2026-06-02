// Copyright 2025 The Bazel Authors. All rights reserved.
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

import build.bazel.remote.execution.v2.Digest

/**
 * A file system that overlays the native file system with a [RemoteExternalFileSystem] for
 * the "external" directory, which contains the contents of external repositories.
 * 
 * 
 * Each external repository can either be materialized to the native file system or kept in
 * memory in the [RemoteExternalFileSystem].
 */
class RemoteExternalOverlayFileSystem(
    externalDirectory: PathFragment,
    nativeFs: com.google.devtools.build.lib.vfs.FileSystem
) : com.google.devtools.build.lib.vfs.FileSystem(nativeFs.getDigestFunction()) {
    private val externalDirectory: PathFragment
    private val externalDirectorySegmentCount: Int
    private val nativeFs: com.google.devtools.build.lib.vfs.FileSystem
    private val externalFs: RemoteExternalFileSystem
    private val materializations: ConcurrentHashMap<String?, java.util.concurrent.Future<java.lang.Void?>?> =
        ConcurrentHashMap<String?, java.util.concurrent.Future<java.lang.Void?>?>()

    // As long as a repo name appears as a key in this map, the repo contents are available in
    // externalFs.
    private val markerFileContents: ConcurrentHashMap<String?, String?> = ConcurrentHashMap<String?, String?>()
    private val reposWithLostFiles: MutableSet<String?> = ConcurrentHashMap.newKeySet<String?>()

    // Per-build information that is set in beforeCommand and cleared in afterCommand.
    private var cache: CombinedCache? = null
    private var inputPrefetcher: AbstractActionInputPrefetcher? = null
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null
    private var buildRequestId: String? = null
    private var commandId: String? = null
    private var evaluator: MemoizingEvaluator? = null
    private var remoteCacheTtl: java.time.Duration? = null
    private var materializationExecutor: ExecutorService? = null

    init {
        this.externalDirectory = externalDirectory
        this.externalDirectorySegmentCount = externalDirectory.segmentCount()
        this.nativeFs = nativeFs
        this.externalFs = RemoteExternalFileSystem(nativeFs.getDigestFunction())
    }

    fun beforeCommand(
        cache: CombinedCache?,
        inputPrefetcher: AbstractActionInputPrefetcher?,
        reporter: com.google.devtools.build.lib.events.Reporter?,
        buildRequestId: String?,
        commandId: String?,
        evaluator: MemoizingEvaluator?,
        remoteCacheTtl: java.time.Duration?
    ) {
        com.google.common.base.Preconditions.checkState(
            this.cache == null && this.inputPrefetcher == null && this.reporter == null && this.buildRequestId == null && this.commandId == null && this.evaluator == null && this.remoteCacheTtl == null && this.materializationExecutor == null
        )
        this.cache = cache
        this.inputPrefetcher = inputPrefetcher
        this.reporter = reporter
        this.buildRequestId = buildRequestId
        this.commandId = commandId
        this.evaluator = evaluator
        this.remoteCacheTtl = remoteCacheTtl
        this.materializationExecutor =
            Executors.newThreadPerTaskExecutor(
                java.lang.Thread.ofVirtual().name("remote-repo-materialization-", 0).factory()
            )
    }

    fun afterCommand() {
        if (cache == null) {
            // Not all commands cause beforeCommand to be called, but afterCommand is called
            // unconditionally.
            return
        }
        this.cache = null
        this.inputPrefetcher = null
        this.reporter = null
        this.buildRequestId = null
        this.commandId = null
        this.remoteCacheTtl = null
        // Materializations happen synchronously and upon request by other repo rules, so there is no
        // reason to await their orderly completion in afterCommand.
        materializationExecutor.shutdownNow()
        materializationExecutor = null
        // Clean up the in-memory contents of materialized repos to save memory, or those that need to
        // be refetched to recover files that the remote cache has lost. This wouldn't be safe to do
        // eagerly as ongoing repo rule evaluations may still refer to the in-memory content and
        // refetching is not atomic.
        materializations.forEach<String?>(
            1,
            java.util.function.BiFunction { repoName: String?, materializationState: java.util.concurrent.Future<java.lang.Void?>? ->
                if (materializationState.state() == java.util.concurrent.Future.State.SUCCESS
                    || reposWithLostFiles.contains(repoName)
                )
                    repoName
                else
                    null
            },
            java.util.function.Consumer { repoName: String? ->
                try {
                    externalFs.deleteTree(externalDirectory.getChild(repoName))
                } catch (e: IOException) {
                    throw java.lang.IllegalStateException("In-memory file system is not expected to throw", e)
                }
                materializations.remove(repoName)
                markerFileContents.remove(repoName)
            })
        if (!reposWithLostFiles.isEmpty()) {
            evaluator.delete(
                java.util.function.Predicate { k: SkyKey? ->
                    k.functionName() == SkyFunctions.REPOSITORY_DIRECTORY
                            && reposWithLostFiles.contains((k.argument() as RepositoryName).name)
                })
        }
        reposWithLostFiles.clear()
        this.evaluator = null
    }

    /**
     * Injects the given remote contents, possibly prefetching some files, and returns true on
     * success.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun injectRemoteRepo(repo: RepositoryName, remoteContents: Tree, markerFile: String?): Boolean {
        val repoDir: PathFragment = externalDirectory.getChild(repo.name)
        deleteTree(repoDir)
        val unused = delete(externalDirectory.getChild(repo.getMarkerFileName()))
        val childMap: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            remoteContents.getChildrenList().stream()
                .collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<T?, K?, V?>(
                        cache.digestUtil::compute,
                        java.util.function.Function { directory: T? -> directory },
                        BinaryOperator { a: V?, b: V? -> a })
                )
        val filesToPrefetch: java.util.ArrayList<PathFragment?> = java.util.ArrayList<PathFragment?>()
        injectRecursively(
            externalFs,
            repoDir,
            remoteContents.getRoot(),
            childMap,
            java.util.function.Consumer { e: PathFragment? -> filesToPrefetch.add(e) },
            Instant.now().plus(remoteCacheTtl)
        )
        try {
            // TODO: This prefetches a large number of small files. Investigate whether BatchReadBlobs
            // would be more efficient.
            prefetch(filesToPrefetch)
        } catch (e: BulkTransferException) {
            if (e.allCausedByCacheNotFoundException()) {
                // The cache has lost the .bzl files, which should be treated just like a cache miss.
                externalFs.deleteTree(repoDir)
                return false
            }
            throw e
        }
        // Create the repo directory on disk so that readdir reflects the overlaid state of the external
        // directory.
        nativeFs.createDirectoryAndParents(repoDir)
        // Keep the marker file contents in memory so that it can be written out when the repo is
        // materialized. This doubles as a presence marker for the in-memory repo contents.
        markerFileContents.put(repo.name, markerFile)
        return true
    }

    /**
     * Materializes the given external repository to the native file system if it hasn't been
     * materialized yet. This method blocks until the materialization is complete.
     * 
     * 
     * This should only be used for cases in which the given repo is accessed non-hermetically,
     * such as when another repo rule that depends on its files executes a command. Selective reads by
     * Bazel or local actions are handled automatically by the file system or [ ].
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun ensureMaterialized(repo: RepositoryName, reporter: ExtendedEventHandler) {
        if (!markerFileContents.containsKey(repo.name)) {
            // The repo has not been injected into the in-memory file system.
            return
        }
        val unused: java.lang.Void? =
            com.google.devtools.build.lib.remote.util.Utils.getFromFuture<java.lang.Void?>(
                materializations.computeIfAbsent(
                    repo.name,
                    java.util.function.Function { unusedRepoName: String? ->
                        materializationExecutor.submit<java.lang.Void?>(
                            java.util.concurrent.Callable {
                                doMaterialize(repo, reporter)
                                null
                            })
                    })
            )
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun doMaterialize(repo: RepositoryName, reporter: ExtendedEventHandler) {
        reporter.handle(com.google.devtools.build.lib.events.Event.debug("Materializing remote repo %s".formatted(repo)))
        val repoPath: PathFragment = externalDirectory.getChild(repo.name)
        val remoteRepo: com.google.devtools.build.lib.vfs.Path = externalFs.getPath(repoPath)
        val walkResult = walk(remoteRepo)
        for (directory in walkResult.directories!!) {
            nativeFs.getPath(directory).createDirectory()
        }
        prefetch(walkResult.files)
        // Create symlinks last as some platforms don't allow creating a symlink to a non-existent
        // target.
        prefetch(walkResult.symlinks)

        // After the repo has been copied, atomically materialize the marker file. This ensures that the
        // repo doesn't have to be refetched after the next server restart.
        val markerFile: com.google.devtools.build.lib.vfs.Path =
            nativeFs.getPath(externalDirectory.getChild(repo.getMarkerFileName()))
        val markerFileSibling: com.google.devtools.build.lib.vfs.Path =
            nativeFs.getPath(externalDirectory.getChild(repo.getMarkerFileName() + ".tmp"))
        com.google.devtools.build.lib.vfs.FileSystemUtils.writeContentAsLatin1(
            markerFileSibling, markerFileContents.remove(repo.name)
        )
        markerFileSibling.renameTo(markerFile)
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun prefetch(paths: MutableList<PathFragment?>) {
        val unused: java.lang.Void? =
            com.google.devtools.build.lib.remote.util.Utils.getFromFuture<java.lang.Void?>(
                inputPrefetcher.prefetchFilesInterruptibly( /* action= */
                    null,
                    com.google.common.collect.Lists.transform<PathFragment?, ActionInput?>(
                        paths,
                        ActionInputHelper::fromPath
                    ),
                    MetadataSupplier { actionInput: ActionInput? -> externalFs.getMetadata(actionInput.getExecPath()) },
                    ActionInputPrefetcher.Priority.CRITICAL,
                    ActionInputPrefetcher.Reason.INPUTS
                )
            )
    }

    private class WalkResult(
        files: MutableList<PathFragment?>?,
        symlinks: MutableList<PathFragment?>?,
        directories: MutableList<PathFragment?>?
    ) {
        val files: MutableList<PathFragment?>?
        val symlinks: MutableList<PathFragment?>?
        val directories: MutableList<PathFragment?>?

        init {
            this.files = files
            this.symlinks = symlinks
            this.directories = directories
        }
    }

    val hostFileSystem: com.google.devtools.build.lib.vfs.FileSystem?
        get() = nativeFs.getHostFileSystem()

    // Always mirror tree deletions to the underlying native file system to support bazel clean and
    // repository refetching.
    @Throws(IOException::class)
    override fun deleteTree(path: PathFragment?) {
        nativeFs.deleteTree(path)
        externalFs.deleteTree(path)
    }

    @Throws(IOException::class)
    override fun deleteTreesBelow(dir: PathFragment?) {
        nativeFs.deleteTreesBelow(dir)
        externalFs.deleteTreesBelow(dir)
    }

    // All other methods delegate to the file system given by this method. It is important to override
    // each non-final FileSystem method to benefit from optimizations implemented in the respective
    // underlying file systems.
    private fun fsForPath(path: PathFragment): com.google.devtools.build.lib.vfs.FileSystem {
        if (path.startsWith(externalDirectory) && path != externalDirectory) {
            val repoName: String? = path.getSegment(externalDirectorySegmentCount)
            val hasBeenInjected: Boolean = markerFileContents.containsKey(repoName)
            val hasBeenMaterialized =
                (materializations.getOrDefault(
                    repoName,
                    com.google.common.util.concurrent.Futures.immediateCancelledFuture<java.lang.Void?>()
                ).state()
                        == java.util.concurrent.Future.State.SUCCESS)
            if (hasBeenInjected && !hasBeenMaterialized) {
                // The repo may have been deleted due to refetching. Clean up in-memory state if that is the
                // case.
                if (externalFs.getPath(externalDirectory.getChild(repoName)).exists()) {
                    return externalFs
                }
                materializations.remove(repoName)
                markerFileContents.remove(repoName)
            }
            // Fall back to the native file system if the repo has been materialized, deleted, or never
            // injected.
        }
        return nativeFs
    }

    @Throws(IOException::class)
    override fun delete(path: PathFragment): Boolean {
        return fsForPath(path).delete(path)
    }

    @Throws(IOException::class)
    override fun getDigest(path: PathFragment): ByteArray? {
        return fsForPath(path).getDigest(path)
    }

    @Throws(IOException::class)
    override fun getFastDigest(path: PathFragment): ByteArray? {
        return fsForPath(path).getFastDigest(path)
    }

    override fun supportsModifications(path: PathFragment): Boolean {
        return fsForPath(path).supportsModifications(path)
    }

    override fun supportsSymbolicLinksNatively(path: PathFragment): Boolean {
        return fsForPath(path).supportsSymbolicLinksNatively(path)
    }

    override fun supportsHardLinksNatively(path: PathFragment): Boolean {
        return fsForPath(path).supportsHardLinksNatively(path)
    }

    override fun mayBeCaseOrNormalizationInsensitive(): Boolean {
        return fsForPath(externalDirectory).mayBeCaseOrNormalizationInsensitive()
    }

    @Throws(IOException::class)
    override fun createDirectory(path: PathFragment): Boolean {
        return fsForPath(path).createDirectory(path)
    }

    @Throws(IOException::class)
    override fun createDirectoryAndParents(path: PathFragment) {
        fsForPath(path).createDirectoryAndParents(path)
    }

    @Throws(IOException::class)
    override fun getFileSize(path: PathFragment, followSymlinks: Boolean): Long {
        return fsForPath(path).getFileSize(path, followSymlinks)
    }

    @Throws(IOException::class)
    override fun getLastModifiedTime(path: PathFragment, followSymlinks: Boolean): Long {
        return fsForPath(path).getLastModifiedTime(path, followSymlinks)
    }

    @Throws(IOException::class)
    override fun setLastModifiedTime(path: PathFragment, newTime: Long) {
        fsForPath(path).setLastModifiedTime(path, newTime)
    }

    @Throws(IOException::class)
    override fun stat(path: PathFragment, followSymlinks: Boolean): FileStatus? {
        return fsForPath(path).stat(path, followSymlinks)
    }

    @Throws(IOException::class)
    override fun createSymbolicLink(
        linkPath: PathFragment, targetFragment: PathFragment?, hint: SymlinkTargetType?
    ) {
        fsForPath(linkPath).createSymbolicLink(linkPath, targetFragment, hint)
    }

    @Throws(IOException::class)
    override fun readSymbolicLink(path: PathFragment): PathFragment? {
        return fsForPath(path).readSymbolicLink(path)
    }

    override fun exists(path: PathFragment, followSymlinks: Boolean): Boolean {
        return fsForPath(path).exists(path, followSymlinks)
    }

    override fun exists(path: PathFragment): Boolean {
        return fsForPath(path).exists(path)
    }

    @Throws(IOException::class)
    override fun getDirectoryEntries(path: PathFragment): MutableCollection<String?>? {
        return fsForPath(path).getDirectoryEntries(path)
    }

    @Throws(IOException::class)
    override fun isReadable(path: PathFragment): Boolean {
        return fsForPath(path).isReadable(path)
    }

    @Throws(IOException::class)
    override fun setReadable(path: PathFragment, readable: Boolean) {
        fsForPath(path).setReadable(path, readable)
    }

    @Throws(IOException::class)
    override fun isWritable(path: PathFragment): Boolean {
        return fsForPath(path).isWritable(path)
    }

    @Throws(IOException::class)
    override fun setWritable(path: PathFragment, writable: Boolean) {
        fsForPath(path).setWritable(path, writable)
    }

    @Throws(IOException::class)
    override fun isExecutable(path: PathFragment): Boolean {
        return fsForPath(path).isExecutable(path)
    }

    @Throws(IOException::class)
    override fun setExecutable(path: PathFragment, executable: Boolean) {
        fsForPath(path).setExecutable(path, executable)
    }

    @Throws(IOException::class)
    override fun getInputStream(path: PathFragment): java.io.InputStream? {
        return fsForPath(path).getInputStream(path)
    }

    @Throws(IOException::class)
    override fun createReadWriteByteChannel(path: PathFragment): SeekableByteChannel? {
        return fsForPath(path).createReadWriteByteChannel(path)
    }

    @Throws(IOException::class)
    override fun getOutputStream(path: PathFragment, append: Boolean, internal: Boolean): java.io.OutputStream? {
        return fsForPath(path).getOutputStream(path, append, internal)
    }

    @Throws(IOException::class)
    override fun renameTo(sourcePath: PathFragment, targetPath: PathFragment?) {
        fsForPath(sourcePath).renameTo(sourcePath, targetPath)
    }

    @Throws(IOException::class)
    override fun createFSDependentHardLink(linkPath: PathFragment?, originalPath: PathFragment) {
        fsForPath(originalPath).createFSDependentHardLink(linkPath, originalPath)
    }

    override fun getIoFile(path: PathFragment): java.io.File? {
        return fsForPath(path).getIoFile(path)
    }

    override fun getNioPath(path: PathFragment): java.nio.file.Path? {
        return fsForPath(path).getNioPath(path)
    }

    override fun getFileSystemType(path: PathFragment): String? {
        return fsForPath(path).getFileSystemType(path)
    }

    @Throws(IOException::class)
    override fun getxattr(path: PathFragment, name: String?, followSymlinks: Boolean): ByteArray? {
        return fsForPath(path).getxattr(path, name, followSymlinks)
    }

    @Throws(IOException::class)
    override fun resolveOneLink(path: PathFragment): PathFragment? {
        return fsForPath(path).resolveOneLink(path)
    }

    @Throws(IOException::class)
    override fun resolveSymbolicLinks(path: PathFragment): com.google.devtools.build.lib.vfs.Path {
        // Ensure that the return value doesn't leave the overlay file system.
        return getPath(fsForPath(path).resolveSymbolicLinks(path).asFragment())
    }

    override fun statNullable(path: PathFragment, followSymlinks: Boolean): FileStatus? {
        return fsForPath(path).statNullable(path, followSymlinks)
    }

    @Throws(IOException::class)
    override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus? {
        return fsForPath(path).statIfFound(path, followSymlinks)
    }

    override fun isFile(path: PathFragment, followSymlinks: Boolean): Boolean {
        return fsForPath(path).isFile(path, followSymlinks)
    }

    override fun isSpecialFile(path: PathFragment, followSymlinks: Boolean): Boolean {
        return fsForPath(path).isSpecialFile(path, followSymlinks)
    }

    override fun isSymbolicLink(path: PathFragment): Boolean {
        return fsForPath(path).isSymbolicLink(path)
    }

    override fun isDirectory(path: PathFragment, followSymlinks: Boolean): Boolean {
        return fsForPath(path).isDirectory(path, followSymlinks)
    }

    @Throws(IOException::class)
    override fun readSymbolicLinkUnchecked(path: PathFragment): PathFragment? {
        return fsForPath(path).readSymbolicLinkUnchecked(path)
    }

    @Throws(IOException::class)
    override fun readdir(
        path: PathFragment,
        followSymlinks: Boolean
    ): MutableCollection<com.google.devtools.build.lib.vfs.Dirent?>? {
        return fsForPath(path).readdir(path, followSymlinks)
    }

    @Throws(IOException::class)
    override fun chmod(path: PathFragment, mode: Int) {
        fsForPath(path).chmod(path, mode)
    }

    @Throws(IOException::class)
    override fun createHardLink(linkPath: PathFragment, originalPath: PathFragment?) {
        fsForPath(linkPath).createHardLink(linkPath, originalPath)
    }

    override fun prefetchPackageAsync(path: PathFragment, maxDirs: Int) {
        fsForPath(path).prefetchPackageAsync(path, maxDirs)
    }

    @Throws(IOException::class)
    override fun createTempDirectory(parent: PathFragment, prefix: String?): PathFragment? {
        return fsForPath(parent).createTempDirectory(parent, prefix)
    }

    private inner class RemoteExternalFileSystem
        (hashFunction: DigestHashFunction?) : RemoteInMemoryFileSystem(hashFunction) {
        fun makeRemoteContext(relativePath: PathFragment): RemoteActionExecutionContext? {
            val repoName: String? = relativePath.subFragment(0, 1).getBaseName()
            val metadata: RequestMetadata? =
                TracingMetadataUtils.buildMetadata(
                    buildRequestId, commandId, repoName,  /* actionMetadata= */null
                )
            // Files in the remote external repo that Bazel reads are worth writing through to the
            // disk cache, as they are likely to be read again on future cold builds.
            return RemoteActionExecutionContext.Companion.create(metadata)
                .withReadCachePolicy(CachePolicy.ANY_CACHE)
                .withWriteCachePolicy(CachePolicy.ANY_CACHE)
        }

        @Throws(IOException::class)
        fun getMetadata(path: PathFragment?): FileArtifactValue? {
            val status: FileStatus = stat(path,  /* followSymlinks= */false)
            if (!status.isSymbolicLink()) {
                return (status as RemoteInMemoryFileInfo).getMetadata()
            }
            return FileArtifactValue.createForUnresolvedSymlink(externalFs.getPath(path))
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun getInputStream(path: PathFragment): java.io.InputStream? {
            if (shouldPrefetch(path)) {
                return nativeFs.getInputStream(path)
            }
            val relativePath: PathFragment = path.relativeTo(externalDirectory)
            val info: RemoteInMemoryFileInfo =
                stat(path,  /* followSymlinks= */true) as RemoteInMemoryFileInfo
            reporter.post(
                object : FetchProgress() {
                    val resourceIdentifier: String?
                        get() = relativePath.getPathString()

                    val progress: String?
                        get() = "(%s)".formatted(
                            com.google.devtools.build.lib.util.StringUtilities.bytesCountToDisplayString(
                                info.getSize()
                            )
                        )

                    val isFinished: Boolean
                        get() = false
                })
            val digest: Digest = DigestUtil.buildDigest(info.getMetadata().getDigest(), info.getSize())
            try {
                val contentFuture: com.google.common.util.concurrent.ListenableFuture<ByteArray?> =
                    cache.downloadBlob(
                        makeRemoteContext(relativePath),
                        path.getPathString(),  /* execPath= */
                        null,
                        digest
                    )
                com.google.devtools.build.lib.remote.util.Utils.waitForBulkTransfer(
                    com.google.common.collect.ImmutableList.of<com.google.common.util.concurrent.ListenableFuture<ByteArray?>?>(
                        contentFuture
                    )
                )
                return ByteArrayInputStream(contentFuture.get())
            } catch (e: java.lang.InterruptedException) {
                java.lang.Thread.currentThread().interrupt()
                throw InterruptedIOException("interrupted while waiting for remote file transfer")
            } catch (e: BulkTransferException) {
                if (e.allCausedByCacheNotFoundException()) {
                    reposWithLostFiles.add(relativePath.getSegment(0))
                    throw DetailedIOException(
                        "%s/%s with digest %s is no longer available in the remote cache"
                            .formatted(
                                externalDirectory.getBaseName(), relativePath, DigestUtil.toString(digest)
                            ),
                        e,
                        FailureDetails.Filesystem.Code.REMOTE_FILE_EVICTED,
                        SkyFunctionException.Transience.TRANSIENT
                    )
                }
                throw e
            } catch (e: ExecutionException) {
                throw java.lang.IllegalStateException("waitForBulkTransfer should have thrown", e)
            } finally {
                reporter.post(
                    object : FetchProgress() {
                        val resourceIdentifier: String?
                            get() = relativePath.getPathString()

                        val progress: String
                            get() = ""

                        val isFinished: Boolean
                            get() = true
                    })
            }
        }

        @Throws(IOException::class)
        override fun getDigest(path: PathFragment?): ByteArray {
            val info: RemoteInMemoryFileInfo =
                stat(path,  /* followSymlinks= */true) as RemoteInMemoryFileInfo
            return info.getMetadata().getDigest()
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun getFastDigest(path: PathFragment?): ByteArray {
            return getDigest(path)
        }
    }

    companion object {
        @Throws(IOException::class)
        private fun injectRecursively(
            fs: RemoteExternalFileSystem,
            path: PathFragment,
            dir: Directory,
            childMap: com.google.common.collect.ImmutableMap<Digest?, Directory?>,
            filesToPrefetch: java.util.function.Consumer<PathFragment?>,
            expirationTime: Instant?
        ) {
            fs.createDirectoryAndParents(path)
            for (file in dir.getFilesList()) {
                val filePath: PathFragment = path.getRelative(StringEncoding.unicodeToInternal(file.getName()))
                if (shouldPrefetch(filePath)) {
                    filesToPrefetch.accept(filePath)
                }
                fs.injectFile(
                    filePath,  // Using the *WithMaterializationData variant ensures that the file benefits from the
                    // FileContentsProxy optimization to avoid widespread invalidation when it is
                    // materialized later, even if expiration times aren't relevant (depends on the usage
                    // of the lease extension).
                    FileArtifactValue.createForRemoteFileWithMaterializationData(
                        DigestUtil.toBinaryDigest(file.getDigest()),
                        file.getDigest().getSizeBytes(),  /* locationIndex= */
                        1,
                        expirationTime
                    )
                )
                fs.setExecutable(filePath, file.getIsExecutable())
                // The RE API does not track whether a file is readable or writable. We choose to make all
                // files readable and not writable to ensure that other repo rules can't accidentally modify
                // the cached repo.
                fs.setWritable(filePath, false)
            }
            for (symlink in dir.getSymlinksList()) {
                fs.createSymbolicLink(
                    path.getRelative(StringEncoding.unicodeToInternal(symlink.getName())),
                    PathFragment.create(StringEncoding.unicodeToInternal(symlink.getTarget()))
                )
            }
            for (subdirNode in dir.getDirectoriesList()) {
                val subdirPath: PathFragment = path.getRelative(StringEncoding.unicodeToInternal(subdirNode.getName()))
                val subdir: Directory? = childMap.get(subdirNode.getDigest())
                if (subdir == null) {
                    throw IOException(
                        "Directory %s with digest %s not found in tree"
                            .formatted(subdirPath, subdirNode.getDigest().getHash())
                    )
                }
                injectRecursively(fs, subdirPath, subdir, childMap, filesToPrefetch, expirationTime)
            }
        }

        @Throws(IOException::class)
        private fun walk(root: com.google.devtools.build.lib.vfs.Path): WalkResult {
            val result = WalkResult(
                java.util.ArrayList<PathFragment?>(),
                java.util.ArrayList<PathFragment?>(),
                java.util.ArrayList<PathFragment?>()
            )
            walk(root, result)
            return result
        }

        @Throws(IOException::class)
        private fun walk(root: com.google.devtools.build.lib.vfs.Path, result: WalkResult) {
            for (dirent in root.readdir(Symlinks.NOFOLLOW)) {
                val fromChild: com.google.devtools.build.lib.vfs.Path = root.getChild(dirent.getName())
                when (dirent.getType()) {
                    com.google.devtools.build.lib.vfs.Dirent.Type.FILE -> result.files!!.add(fromChild.asFragment())
                    com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK -> result.symlinks!!.add(fromChild.asFragment())
                    com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY -> {
                        result.directories!!.add(fromChild.asFragment())
                        walk(fromChild, result)
                    }

                    else -> throw IOException("Unsupported file type: " + dirent)
                }
            }
        }

        /** Whether the file with the given path should be materialized eagerly when injecting a repo.  */
        private fun shouldPrefetch(path: PathFragment): Boolean {
            // .bzl files are typically small and the loads between them can form complex DAGs that can only
            // be discovered layer by layer, so prefetching is worthwhile to reduce the number of sequential
            // cache requests.
            // The REPO.bazel file, if present, is a dependency of any package and will thus have to be
            // fetched anyway.
            return path.getFileExtension() == "bzl" || path.getBaseName() == "REPO.bazel"
        }
    }
}
