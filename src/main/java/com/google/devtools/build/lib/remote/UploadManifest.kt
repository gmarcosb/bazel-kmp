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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.Action

/** UploadManifest adds output metadata to a [ActionResult].  */
class UploadManifest(
    digestUtil: DigestUtil,
    remotePathResolver: RemotePathResolver,
    result: ActionResult.Builder,
    allowAbsoluteSymlinks: Boolean,
    preserveExecutableBit: Boolean
) {
    private val digestUtil: DigestUtil
    private val remotePathResolver: RemotePathResolver
    private val result: ActionResult.Builder
    private val allowAbsoluteSymlinks: Boolean
    private val preserveExecutableBit: Boolean
    private val digestToFile: ConcurrentHashMap<Digest?, com.google.devtools.build.lib.vfs.Path?> =
        ConcurrentHashMap<Digest?, com.google.devtools.build.lib.vfs.Path?>()
    private val digestToBlobs: ConcurrentHashMap<Digest?, ByteString?> = ConcurrentHashMap<Digest?, ByteString?>()
    private var actionKey: ActionKey? = null
    private var stderrDigest: Digest? = null
    private var stdoutDigest: Digest? = null

    /**
     * Create an UploadManifest from an ActionResult builder and an exec root. The ActionResult
     * builder is populated through a call to [.addFiles].
     * 
     * @param allowAbsoluteSymlinks whether the remote allows uploading absolute symlinks
     */
    @com.google.common.annotations.VisibleForTesting
    constructor(
        digestUtil: DigestUtil,
        remotePathResolver: RemotePathResolver,
        result: ActionResult.Builder,
        allowAbsoluteSymlinks: Boolean
    ) : this(
        digestUtil,
        remotePathResolver,
        result,
        allowAbsoluteSymlinks,  /* preserveExecutableBit= */
        false
    )

    @Throws(IOException::class)
    private fun setStdoutStderr(outErr: FileOutErr) {
        if (outErr.getErrorPath().exists()) {
            stderrDigest = digestUtil.compute(outErr.getErrorPath())
            digestToFile.put(stderrDigest, outErr.getErrorPath())
        }
        if (outErr.getOutputPath().exists()) {
            stdoutDigest = digestUtil.compute(outErr.getOutputPath())
            digestToFile.put(stdoutDigest, outErr.getOutputPath())
        }
    }

    /**
     * Add a collection of files, directories or symlinks to the manifest.
     * 
     * 
     * Adding a directory has the effect of:
     * 
     * 
     *  1. uploading a [Tree] protobuf message from which the whole structure of the
     * directory, including the descendants, can be reconstructed.
     *  1. uploading all of the non-directory descendant files.
     * 
     * 
     * 
     * Note that the manifest describes the outcome of a spawn, not of an action. In particular,
     * it's possible for an output to be missing or to have been created with an unsuitable file type
     * for the corresponding [Artifact] (e.g., a directory where a file was expected, or a
     * non-symlink where a symlink was expected). Except for the oddity noted in the next paragraph,
     * outputs are always uploaded according to the filesystem state. A type mismatch may later cause
     * execution to fail, but that's an action-level concern.
     * 
     * 
     * For historical reasons, non-dangling absolute symlinks are uploaded as the file or directory
     * they point to. This is inconsistent with the treatment of non-dangling relative symlinks, which
     * are uploaded as such, but fixing it would now require an incompatible change. For the purposes
     * of this check, a looping symlink is considered dangling.
     * 
     * 
     * All files are uploaded with the executable bit set, in accordance with input Merkle trees.
     * This does not affect correctness since we always set the output permissions to 0555 or 0755
     * after execution, both for cache hits and misses.
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(ExecException::class, IOException::class, java.lang.InterruptedException::class)
    fun addFiles(files: MutableCollection<com.google.devtools.build.lib.vfs.Path>) {
        for (file in files) {
            // TODO(ulfjack): Maybe pass in a SpawnResult here, add a list of output files to that, and
            // rely on the local spawn runner to stat the files, instead of statting here.
            val statNoFollow: FileStatus? = file.statIfFound(Symlinks.NOFOLLOW)
            // TODO(#6547): handle the case where the parent directory of the output file is an
            // output symlink.
            if (statNoFollow == null) {
                // Ignore missing outputs.
                continue
            }
            if (statNoFollow.isFile() && !statNoFollow.isSpecialFile()) {
                val digest: Digest? = digestUtil.compute(file, statNoFollow)
                addFile(digest, file, statNoFollow)
                continue
            }
            if (statNoFollow.isDirectory()) {
                addDirectory(file)
                continue
            }
            if (statNoFollow.isSymbolicLink()) {
                val target: PathFragment = file.readSymbolicLink()
                // Need to resolve the symbolic link to know what to add, file or directory.
                var statFollow: FileStatus? = null
                try {
                    statFollow = file.statIfFound(Symlinks.FOLLOW)
                } catch (e: FileSymlinkLoopException) {
                    // Treat a looping symlink as a dangling symlink.
                }
                if (statFollow == null) {
                    // Symlink uploaded as a symlink. Report it as a file since we don't know any better.
                    if (target.isAbsolute()) {
                        checkAbsoluteSymlinkAllowed(file, target)
                    }
                    addFileSymbolicLink(file, target)
                    continue
                }
                if (statFollow.isFile() && !statFollow.isSpecialFile()) {
                    if (target.isAbsolute()) {
                        // Symlink to file uploaded as a file.
                        addFile(digestUtil.compute(file, statFollow), file, statNoFollow)
                    } else {
                        // Symlink to file uploaded as a symlink.
                        addFileSymbolicLink(file, target)
                    }
                    continue
                }
                if (statFollow.isDirectory()) {
                    if (target.isAbsolute()) {
                        // Symlink to directory uploaded as a directory.
                        addDirectory(file)
                    } else {
                        // Symlink to directory uploaded as a symlink.
                        addDirectorySymbolicLink(file, target)
                    }
                    continue
                }
            }
            // Special file or dereferenced symlink to special file.
            rejectSpecialFile(file)
        }
    }

    /**
     * Adds an action and command protos to upload. They need to be uploaded as part of the action
     * result.
     */
    private fun addAction(actionKey: ActionKey, action: Action, command: Command) {
        com.google.common.base.Preconditions.checkState(this.actionKey == null, "Already added an action")
        this.actionKey = actionKey
        digestToBlobs.put(actionKey.digest, action.toByteString())
        digestToBlobs.put(action.getCommandDigest(), command.toByteString())
    }

    /** Map of digests to file paths to upload.  */
    @com.google.common.annotations.VisibleForTesting
    fun getDigestToFile(): MutableMap<Digest?, com.google.devtools.build.lib.vfs.Path?> {
        return digestToFile
    }

    fun getStdoutDigest(): Digest? {
        return stdoutDigest
    }

    fun getStderrDigest(): Digest? {
        return stderrDigest
    }

    private fun addFileSymbolicLink(file: com.google.devtools.build.lib.vfs.Path?, target: PathFragment) {
        val outputSymlink: OutputSymlink? =
            OutputSymlink.newBuilder()
                .setPath(StringEncoding.internalToUnicode(remotePathResolver.localPathToOutputPath(file)))
                .setTarget(StringEncoding.internalToUnicode(target.toString()))
                .build()
        result.addOutputFileSymlinks(outputSymlink)
        result.addOutputSymlinks(outputSymlink)
    }

    private fun addDirectorySymbolicLink(file: com.google.devtools.build.lib.vfs.Path?, target: PathFragment) {
        val outputSymlink: OutputSymlink? =
            OutputSymlink.newBuilder()
                .setPath(StringEncoding.internalToUnicode(remotePathResolver.localPathToOutputPath(file)))
                .setTarget(StringEncoding.internalToUnicode(target.toString()))
                .build()
        result.addOutputDirectorySymlinks(outputSymlink)
        result.addOutputSymlinks(outputSymlink)
    }

    private fun addFile(digest: Digest?, file: com.google.devtools.build.lib.vfs.Path?, statNoFollow: FileStatus) {
        result
            .addOutputFilesBuilder()
            .setPath(StringEncoding.internalToUnicode(remotePathResolver.localPathToOutputPath(file)))
            .setDigest(digest)
            .setIsExecutable(!preserveExecutableBit || (statNoFollow.getPermissions() and 64) != 0)

        digestToFile.put(digest, file)
    }

    private class WrappedException(wrapped: java.lang.Exception?) : java.lang.RuntimeException(wrapped) {
        private val wrapped: java.lang.Exception?

        init {
            this.wrapped = wrapped
        }

        fun unwrap(): java.lang.Exception? {
            return wrapped
        }
    }

    /**
     * A [DirectoryBuilder] constructs a [Tree] message for an output directory, doing as
     * much as possible in parallel.
     */
    private inner class DirectoryBuilder(rootDir: com.google.devtools.build.lib.vfs.Path?) : AbstractQueueVisitor(
        VISITOR_POOL,
        ExecutorOwnership.SHARED,
        ExceptionHandlingMode.FAIL_FAST,
        ErrorClassifier.DEFAULT
    ) {
        private val rootDir: com.google.devtools.build.lib.vfs.Path

        // Directories found during the traversal, including the root.
        // Sorted in reverse so that children iterate before parents.
        private val dirs: SortedSet<com.google.devtools.build.lib.vfs.Path?> =
            Collections.synchronizedSortedSet<com.google.devtools.build.lib.vfs.Path?>(
                TreeSet<com.google.devtools.build.lib.vfs.Path?>(java.util.Comparator.reverseOrder<com.google.devtools.build.lib.vfs.Path?>())
            )

        // Maps each directory found during the traversal to its subdirectories.
        private val dirToSubdirs: com.google.common.collect.SortedSetMultimap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path> =
            com.google.common.collect.Multimaps.synchronizedSortedSetMultimap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?>(
                com.google.common.collect.TreeMultimap.create<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?>()
            )

        // Maps each directory found during the traversal to its files.
        private val dirToFiles: com.google.common.collect.SortedSetMultimap<com.google.devtools.build.lib.vfs.Path?, FileNode?> =
            com.google.common.collect.Multimaps.synchronizedSortedSetMultimap<com.google.devtools.build.lib.vfs.Path?, FileNode?>(
                com.google.common.collect.TreeMultimap.create<com.google.devtools.build.lib.vfs.Path?, FileNode?>(
                    java.util.Comparator.naturalOrder<T?>(),
                    java.util.Comparator.comparing<Any?, Any?>(FileNode::getName)
                )
            )

        // Maps each directory found during the traversal to its symlinks.
        private val dirToSymlinks: com.google.common.collect.SortedSetMultimap<com.google.devtools.build.lib.vfs.Path?, SymlinkNode?> =
            com.google.common.collect.Multimaps.synchronizedSortedSetMultimap<com.google.devtools.build.lib.vfs.Path?, SymlinkNode?>(
                com.google.common.collect.TreeMultimap.create<com.google.devtools.build.lib.vfs.Path?, SymlinkNode?>(
                    java.util.Comparator.naturalOrder<T?>(),
                    java.util.Comparator.comparing<Any?, Any?>(SymlinkNode::getName)
                )
            )

        init {
            this.rootDir =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path>(rootDir)
        }

        /**
         * Returns a [Tree] message in wire format describing the directory contents, obeying the
         * requirements of the `OutputDirectory.is_topologically_sorted` field.
         */
        @Throws(ExecException::class, IOException::class, java.lang.InterruptedException::class)
        fun build(): ByteString {
            // Collect directory entries (subdirectories, files, symlinks) in parallel.
            // This is a major speedup for large tree artifacts with hundreds of thousands of files.
            execute({ visit(rootDir, com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY) })
            try {
                awaitQuiescence(true)
            } catch (e: WrappedException) {
                com.google.common.base.Throwables.throwIfInstanceOf<X?>(e.unwrap(), ExecException::class.java)
                com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(e.unwrap(), IOException::class.java)
                throw java.lang.AssertionError("unexpected exception", e.unwrap())
            }

            // Compute the Directory message for every node, including the root. Since each directory
            // references its subdirectories by their digest, the messages must be computed in topological
            // order (children before parents). In addition, the contents of each Directory message must
            // be sorted, which is already ensured by the use of sorted maps.
            val dirToDigest: HashMap<com.google.devtools.build.lib.vfs.Path?, Digest?> =
                HashMap.newHashMap<com.google.devtools.build.lib.vfs.Path?, Digest?>(dirs.size())
            val dirBlobs: LinkedHashSet<ByteString?> = LinkedHashSet.newLinkedHashSet<ByteString?>(dirs.size())

            for (dir in dirs) {
                val builder: Directory.Builder = Directory.newBuilder()
                builder.addAllFiles(dirToFiles.get(dir))
                builder.addAllSymlinks(dirToSymlinks.get(dir))
                for (subdir in dirToSubdirs.get(dir)) {
                    com.google.common.base.Preconditions.checkState(subdir.getParentDirectory() == dir)
                    builder
                        .addDirectoriesBuilder()
                        .setName(StringEncoding.internalToUnicode(subdir.getBaseName()))
                        .setDigest(dirToDigest.get(subdir))
                }
                val dirBlob: ByteString = builder.build().toByteString()

                dirToDigest.put(dir, digestUtil.compute(dirBlob.toByteArray()))
                dirBlobs.add(dirBlob)
            }

            // Convert individual Directory messages to a Tree message. As we want the records to be
            // topologically sorted (parents before children), we iterate over the directories in reverse
            // insertion order. We construct the message through direct byte manipulation to ensure that
            // the strict requirements on the encoding are observed.
            val out: com.google.protobuf.ByteString.Output = ByteString.newOutput()
            val codedOutputStream: CodedOutputStream = CodedOutputStream.newInstance(out)
            var fieldNumber = TREE_ROOT_FIELD_NUMBER
            for (directory in dirBlobs.reversed()) {
                codedOutputStream.writeBytes(fieldNumber, directory)
                fieldNumber = TREE_CHILDREN_FIELD_NUMBER
            }
            codedOutputStream.flush()

            return out.toByteString()
        }

        fun visit(path: com.google.devtools.build.lib.vfs.Path, type: com.google.devtools.build.lib.vfs.Dirent.Type?) {
            try {
                if (type == com.google.devtools.build.lib.vfs.Dirent.Type.FILE) {
                    visitAsFile(path)
                    return
                }
                if (type == com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY) {
                    visitAsDirectory(path)
                    for (dirent in path.readdir(Symlinks.NOFOLLOW)) {
                        val childPath: com.google.devtools.build.lib.vfs.Path = path.getChild(dirent.getName())
                        val childType: com.google.devtools.build.lib.vfs.Dirent.Type? = dirent.getType()
                        execute({ visit(childPath, childType) })
                    }
                    return
                }
                if (type == com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK) {
                    val target: PathFragment = path.readSymbolicLink()
                    var statFollow: FileStatus? = null
                    try {
                        statFollow = path.statIfFound(Symlinks.FOLLOW)
                    } catch (e: FileSymlinkLoopException) {
                        // Treat a looping symlink as a dangling symlink.
                    }
                    if (statFollow == null || !target.isAbsolute()) {
                        // Symlink uploaded as a symlink.
                        if (target.isAbsolute()) {
                            checkAbsoluteSymlinkAllowed(path, target)
                        }
                        visitAsSymlink(path, target)
                        return
                    }
                    if (statFollow.isFile() && !statFollow.isSpecialFile()) {
                        // Symlink to file uploaded as a file.
                        execute({ visit(path, com.google.devtools.build.lib.vfs.Dirent.Type.FILE) })
                        return
                    }
                    if (statFollow.isDirectory()) {
                        // Symlink to directory uploaded as a directory.
                        execute({ visit(path, com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY) })
                        return
                    }
                }
                rejectSpecialFile(path)
            } catch (e: ExecException) {
                // We can't throw checked exceptions here since AQV expects Runnables
                throw com.google.devtools.build.lib.remote.UploadManifest.WrappedException(e)
            } catch (e: IOException) {
                throw com.google.devtools.build.lib.remote.UploadManifest.WrappedException(e)
            }
        }

        fun visitAsDirectory(path: com.google.devtools.build.lib.vfs.Path) {
            dirs.add(path)
            if (path != rootDir) {
                dirToSubdirs.put(path.getParentDirectory(), path)
            }
        }

        @Throws(IOException::class)
        fun visitAsFile(path: com.google.devtools.build.lib.vfs.Path) {
            val parentPath: com.google.devtools.build.lib.vfs.Path? = path.getParentDirectory()
            val stat: FileStatus = path.statIfFound(Symlinks.NOFOLLOW)
            val digest: Digest? = digestUtil.compute(path)
            val node: FileNode? =
                FileNode.newBuilder()
                    .setName(StringEncoding.internalToUnicode(path.getBaseName()))
                    .setDigest(digest)
                    .setIsExecutable(!preserveExecutableBit || (stat.getPermissions() and 64) != 0)
                    .build()
            digestToFile.put(digest, path)
            dirToFiles.put(parentPath, node)
        }

        fun visitAsSymlink(path: com.google.devtools.build.lib.vfs.Path, target: PathFragment) {
            val parentPath: com.google.devtools.build.lib.vfs.Path? = path.getParentDirectory()
            val node: SymlinkNode? =
                SymlinkNode.newBuilder()
                    .setName(StringEncoding.internalToUnicode(path.getBaseName()))
                    .setTarget(StringEncoding.internalToUnicode(target.toString()))
                    .build()
            dirToSymlinks.put(parentPath, node)
        }
    }

    init {
        this.digestUtil = digestUtil
        this.remotePathResolver = remotePathResolver
        this.result = result
        this.allowAbsoluteSymlinks = allowAbsoluteSymlinks
        this.preserveExecutableBit = preserveExecutableBit
    }

    @Throws(ExecException::class, IOException::class, java.lang.InterruptedException::class)
    private fun addDirectory(dir: com.google.devtools.build.lib.vfs.Path?) {
        val treeBlob: ByteString = DirectoryBuilder(dir).build()
        val treeDigest: Digest? = digestUtil.compute(treeBlob.toByteArray())

        result
            .addOutputDirectoriesBuilder()
            .setPath(StringEncoding.internalToUnicode(remotePathResolver.localPathToOutputPath(dir)))
            .setTreeDigest(treeDigest)
            .setIsTopologicallySorted(true)

        digestToBlobs.put(treeDigest, treeBlob)
    }

    @Throws(IOException::class)
    private fun checkAbsoluteSymlinkAllowed(file: com.google.devtools.build.lib.vfs.Path?, target: PathFragment?) {
        if (!allowAbsoluteSymlinks) {
            throw IOException(
                java.lang.String.format(
                    "Spawn output %s is an absolute symbolic link to %s, which is not allowed by"
                            + " the remote cache",
                    file, target
                )
            )
        }
    }

    @Throws(ExecException::class)
    private fun rejectSpecialFile(path: com.google.devtools.build.lib.vfs.Path?) {
        // TODO(tjgq): Consider treating special files as regular, following Skyframe.
        // (On the other hand, they seem to be only useful for testing purposes, so we might instead
        // want to forbid them entirely.)
        val message: String? =
            java.lang.String.format(
                "Spawn output %s is a special file. Only regular files, directories or symlinks may be "
                        + "uploaded to a remote cache.",
                path
            )

        val failureDetail: FailureDetail? =
            FailureDetail.newBuilder()
                .setMessage(message)
                .setRemoteExecution(RemoteExecution.newBuilder().setCode(Code.ILLEGAL_OUTPUT))
                .build()
        throw UserExecException(failureDetail)
    }

    @get:com.google.common.annotations.VisibleForTesting
    val actionResult: ActionResult
        get() = result.build()

    /** Uploads outputs and action result (if exit code is 0) to the remote and/or disk cache.  */
    @Throws(IOException::class, java.lang.InterruptedException::class, ExecException::class)
    fun upload(
        context: RemoteActionExecutionContext,
        combinedCache: CombinedCache,
        reporter: ExtendedEventHandler
    ): ActionResult? {
        val action: ActionExecutionMetadata? = context.getSpawnOwner()
        val allDigests: com.google.common.collect.ImmutableSet<Digest?> =
            com.google.common.collect.Sets.union<Digest?>(digestToBlobs.keySet(), digestToFile.keySet()).immutableCopy()
        val missingDigests: com.google.common.collect.ImmutableSet<Digest>
        Profiler.instance().profile(ProfilerTask.INFO, "findMissingDigests").use { s ->
            missingDigests =
                com.google.devtools.build.lib.remote.util.Utils.getFromFuture<com.google.common.collect.ImmutableSet<Digest>>(
                    combinedCache.findMissingDigests(context, allDigests)
                )
        }
        Profiler.instance()
            .profile(
                ProfilerTask.UPLOAD_TIME,
                { "upload %d missing blobs".formatted(missingDigests.size()) }).use { s ->
                val uploadFutures: java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
                    java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>(
                        missingDigests.size()
                    )
                for (digest in missingDigests) {
                    uploadFutures.add(
                        Companion.decorateUploadFuture<java.lang.Void?>(
                            uploadSingleDigest(context, combinedCache, digest),
                            reporter,
                            action,
                            com.google.devtools.build.lib.remote.Store.CAS,
                            digest
                        )
                    )
                }
                com.google.devtools.build.lib.remote.util.Utils.waitForBulkTransfer(uploadFutures)
            }
        // The action result must be uploaded after the Action and Command protos per the REAPI
        // protocol. We choose to upload it after all other blobs since this has historically been the
        // case and action results may fail to validate server-side if they are accessed before all
        // blobs they refer to are present.
        val actionResult: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            result.build()
        if (actionResult.getExitCode() === 0 && actionKey != null) {
            Profiler.instance().profile(ProfilerTask.UPLOAD_TIME, "upload action result").use { s ->
                com.google.devtools.build.lib.remote.util.Utils.getFromFuture<java.lang.Void?>(
                    Companion.decorateUploadFuture<java.lang.Void?>(
                        combinedCache.uploadActionResult(context, actionKey, actionResult),
                        reporter,
                        action,
                        com.google.devtools.build.lib.remote.Store.AC,
                        actionKey.digest
                    )
                )
            }
        }
        return actionResult
    }

    private fun uploadSingleDigest(
        context: RemoteActionExecutionContext?, combinedCache: CombinedCache, digest: Digest
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? {
        val file: com.google.devtools.build.lib.vfs.Path? = digestToFile.get(digest)
        if (file != null) {
            return combinedCache.uploadFile(context, digest, file)
        }

        val blob: ByteString? = digestToBlobs.get(digest)
        if (blob == null) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
                IOException("FindMissingBlobs call returned an unknown digest: " + digest)
            )
        }

        return combinedCache.uploadBlob(context, digest, blob)
    }

    companion object {
        @Throws(ExecException::class, IOException::class, java.lang.InterruptedException::class)
        fun create(
            cacheCapabilities: CacheCapabilities,
            digestUtil: DigestUtil,
            remotePathResolver: RemotePathResolver,
            actionKey: ActionKey,
            action: Action,
            command: Command,
            outputFiles: MutableCollection<com.google.devtools.build.lib.vfs.Path>,
            outErr: FileOutErr?,
            exitCode: Int,
            startTime: Instant?,
            wallTimeInMs: Int,
            preserveExecutableBit: Boolean
        ): UploadManifest {
            val result: ActionResult.Builder = ActionResult.newBuilder()
            result.setExitCode(exitCode)

            val manifest =
                UploadManifest(
                    digestUtil,
                    remotePathResolver,
                    result,  /* allowAbsoluteSymlinks= */
                    cacheCapabilities
                        .getSymlinkAbsolutePathStrategy()
                        .equals(SymlinkAbsolutePathStrategy.Value.ALLOWED),
                    preserveExecutableBit
                )
            manifest.addFiles(outputFiles)
            if (outErr != null) {
                manifest.setStdoutStderr(outErr)
            }
            manifest.addAction(actionKey, action, command)
            if (manifest.getStderrDigest() != null) {
                result.setStderrDigest(manifest.getStderrDigest())
            }
            if (manifest.getStdoutDigest() != null) {
                result.setStdoutDigest(manifest.getStdoutDigest())
            }

            // if wallTime is zero, than it's not set
            if (startTime != null && wallTimeInMs != 0) {
                val startTimestamp: Timestamp = instantToTimestamp(startTime)
                val completedTimestamp: Timestamp = instantToTimestamp(startTime.plusMillis(wallTimeInMs.toLong()))
                result
                    .getExecutionMetadataBuilder()
                    .setWorkerStartTimestamp(startTimestamp)
                    .setExecutionStartTimestamp(startTimestamp)
                    .setExecutionCompletedTimestamp(completedTimestamp)
                    .setWorkerCompletedTimestamp(completedTimestamp)
            }

            return manifest
        }

        private fun instantToTimestamp(instant: Instant): Timestamp {
            return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build()
        }

        /** A thread pool shared by all [DirectoryBuilder] instances.  */
        private val VISITOR_POOL: ForkJoinPool? = NamedForkJoinPool.newNamedPool(
            "upload-manifest-directory-visitor", java.lang.Runtime.getRuntime().availableProcessors()
        )

        // Field numbers of the 'root' and 'directory' fields in the Tree message.
        private val TREE_ROOT_FIELD_NUMBER: Int = Tree.getDescriptor().findFieldByName("root").getNumber()
        private val TREE_CHILDREN_FIELD_NUMBER: Int = Tree.getDescriptor().findFieldByName("children").getNumber()

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        private fun <T> decorateUploadFuture(
            future: com.google.common.util.concurrent.ListenableFuture<T?>,
            reporter: ExtendedEventHandler,
            action: ActionExecutionMetadata?,
            store: com.google.devtools.build.lib.remote.Store?,
            digest: Digest?
        ): com.google.common.util.concurrent.ListenableFuture<T?> {
            if (action == null) {
                return future
            }
            reporter.post(ActionUploadStartedEvent.create(action, store, digest))
            future.addListener(
                java.lang.Runnable { reporter.post(ActionUploadFinishedEvent.create(action, store, digest)) },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
            return future
        }
    }
}
