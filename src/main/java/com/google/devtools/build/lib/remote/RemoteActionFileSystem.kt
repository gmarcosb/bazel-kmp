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
//
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.actions.Action

/**
 * An action filesystem suitable for use when building with disk/remote caching or execution.
 * 
 * 
 * It acts as a union filesystem over three different sources:
 * 
 * 
 *  * The action input map, providing read-only in-memory access to the metadata (but not the
 * contents) of the action's declared inputs.
 *  * The remote output tree, an in-memory filesystem providing read/write access to the metadata
 * (but not the contents) of remotely stored files injected during action execution.
 *  * The local filesystem, providing read/write access to the metadata and contents of files
 * residing on disk, including the inputs and outputs of local spawns.
 * 
 * 
 * 
 * Generally speaking, file operations consult the underlying sources in that order and operate
 * on the first result found, although some (e.g. readdir) collate information from all sources. The
 * contents of remotely stored files are transparently downloaded when an operation requires them.
 * 
 * 
 * Special care must be taken with operations that follow symlinks, as the symlink and its target
 * path may reside on different sources, with an arbitrary number of indirections in between. This
 * is required because some actions (notably SymlinkAction) may materialize an output as a symlink
 * to an input. Most operations call resolveSymbolicLinks upfront (which is able to canonicalize
 * paths taking every source into account) and only then delegate to the underlying sources.
 * 
 * 
 * The implementation assumes that an action never modifies its input paths, but may otherwise
 * modify any path in the output tree. Concurrent operations are supported as long as they don't
 * affect filesystem structure (i.e., create, move or delete paths). Otherwise, they might fail or
 * produce inconsistent results. No effort is made to detect irreconcilable differences between
 * sources, such as the same path existing in multiple underlying sources with different type or
 * contents.
 */
class RemoteActionFileSystem(
    localFs: com.google.devtools.build.lib.vfs.FileSystem,
    execRootFragment: PathFragment?,
    relativeOutputPath: String?,
    inputArtifactData: InputMetadataProvider?,
    inputFetcher: RemoteActionInputFetcher?
) : com.google.devtools.build.lib.vfs.FileSystem(localFs.getDigestFunction()),
    com.google.devtools.build.lib.remote.PathCanonicalizer.Resolver {
    private val execRoot: PathFragment
    private val outputBase: PathFragment?
    private val inputArtifactData: InputMetadataProvider
    private val inputTreeArtifactDirectoryCache: TreeArtifactDirectoryCache
    private val pathCanonicalizer: PathCanonicalizer
    private val inputFetcher: RemoteActionInputFetcher
    private val localFs: com.google.devtools.build.lib.vfs.FileSystem

    @get:com.google.common.annotations.VisibleForTesting
    val remoteOutputTree: RemoteInMemoryFileSystem

    // Concurrent access is rare and most builds don't have lost inputs.
    private val lostInputs: MutableList<LostArtifacts?> =
        Collections.synchronizedList<LostArtifacts?>(java.util.ArrayList<LostArtifacts?>(0))

    private var action: ActionExecutionMetadata? = null

    /** Describes how to handle symlinks when calling [.statInternal].  */
    private enum class FollowMode {
        /** Canonicalize the entire path. This is equivalent to [Symlinks.FOLLOW].  */
        FOLLOW_ALL,

        /** Canonicalize the parent path. This is equivalent to [Symlinks.NOFOLLOW].  */
        FOLLOW_PARENT,

        /** Do not canonicalize. This is only used internally to resolve symlinks efficiently.  */
        FOLLOW_NONE
    }

    /** Describes which sources to consider when calling [.statInternal].  */
    private enum class StatSources {
        /** Consider all sources (action input map, remote output tree and local filesystem).  */
        ALL,

        /** Consider only in-memory sources (action input map and remote output tree).  */
        IN_MEMORY_ONLY,
    }

    /**
     * Caches the contents of intermediate subdirectories of tree artifact inputs, to speed up [ ][FileSystem.stat] and [FileSystem.readdir] operations. Note that actions are not expected
     * to modify their inputs.
     * 
     * 
     * Safe for concurrent access.
     */
    private inner class TreeArtifactDirectoryCache {
        private val cachedTrees: MutableSet<SpecialArtifact?> = HashSet<SpecialArtifact?>()
        private val dirToEntries: HashMap<PathFragment?, HashSet<com.google.devtools.build.lib.vfs.Dirent?>> =
            HashMap<PathFragment?, HashSet<com.google.devtools.build.lib.vfs.Dirent?>>()

        @kotlin.jvm.Synchronized
        fun get(execPath: PathFragment?): MutableCollection<com.google.devtools.build.lib.vfs.Dirent>? {
            ensureCached(execPath)
            return dirToEntries.get(execPath)
        }

        fun ensureCached(execPath: PathFragment?) {
            val treeMetadata: TreeArtifactValue? = inputArtifactData.getEnclosingTreeMetadata(execPath)
            if (treeMetadata == null || treeMetadata.getChildren().isEmpty()) {
                return
            }
            val parent: SpecialArtifact? =
                com.google.common.collect.Iterables.getFirst<TreeFileArtifact?>(treeMetadata.getChildren(), null)
                    .getParent()
            if (!cachedTrees.contains(parent)) {
                insertTree(treeMetadata)
                cachedTrees.add(parent)
            }
        }

        fun insertTree(treeMetadata: TreeArtifactValue) {
            for (child in treeMetadata.getChildren()) {
                insertChild(child)
            }
        }

        fun insertChild(child: TreeFileArtifact) {
            val treeRoot: PathFragment? = child.getParent().getExecPath()
            var path: PathFragment? = child.getExecPath()

            while (path != treeRoot) {
                val parentPath: PathFragment? = path.getParentDirectory()
                val name: String? = path.getBaseName()
                val type: com.google.devtools.build.lib.vfs.Dirent.Type =
                    if (path == child.getExecPath()) com.google.devtools.build.lib.vfs.Dirent.Type.FILE else com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY

                val entries: HashSet<com.google.devtools.build.lib.vfs.Dirent?> =
                    dirToEntries.computeIfAbsent(
                        parentPath,
                        java.util.function.Function { unused: PathFragment? -> HashSet<com.google.devtools.build.lib.vfs.Dirent?>() })

                if (!entries.add(com.google.devtools.build.lib.vfs.Dirent(name, type))) {
                    // Avoid wasted work on common prefixes.
                    break
                }

                path = parentPath
            }
        }
    }

    init {
        this.execRoot =
            com.google.common.base.Preconditions.checkNotNull<PathFragment>(execRootFragment, "execRootFragment")
        this.outputBase = execRoot.getRelative(
            com.google.common.base.Preconditions.checkNotNull<String?>(
                relativeOutputPath,
                "relativeOutputPath"
            )
        )
        this.inputArtifactData = com.google.common.base.Preconditions.checkNotNull<InputMetadataProvider>(
            inputArtifactData,
            "inputArtifactData"
        )
        this.inputTreeArtifactDirectoryCache = TreeArtifactDirectoryCache()
        this.pathCanonicalizer = PathCanonicalizer(this)
        this.inputFetcher =
            com.google.common.base.Preconditions.checkNotNull<RemoteActionInputFetcher>(inputFetcher, "inputFetcher")
        this.localFs = com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.FileSystem>(
            localFs,
            "localFs"
        )
        this.remoteOutputTree = RemoteInMemoryFileSystem(getDigestFunction())
    }

    val hostFileSystem: com.google.devtools.build.lib.vfs.FileSystem?
        get() = localFs.getHostFileSystem()

    override fun supportsModifications(path: PathFragment?): Boolean {
        return localFs.supportsModifications(path)
    }

    override fun supportsSymbolicLinksNatively(path: PathFragment?): Boolean {
        return localFs.supportsSymbolicLinksNatively(path)
    }

    override fun supportsHardLinksNatively(path: PathFragment?): Boolean {
        return localFs.supportsHardLinksNatively(path)
    }

    override fun mayBeCaseOrNormalizationInsensitive(): Boolean {
        return localFs.mayBeCaseOrNormalizationInsensitive()
                || remoteOutputTree.mayBeCaseOrNormalizationInsensitive()
    }

    @get:com.google.common.annotations.VisibleForTesting
    val localFileSystem: com.google.devtools.build.lib.vfs.FileSystem
        get() = localFs

    /** Returns whether a path is stored remotely. Follows symlinks.  */
    @Throws(IOException::class)
    fun isRemote(path: com.google.devtools.build.lib.vfs.Path): Boolean {
        return isRemote(path.asFragment())
    }

    @Throws(IOException::class)
    private fun isRemote(path: PathFragment): Boolean {
        // Files in the local filesystem are non-remote by definition, so stat only in-memory sources.
        val status: FileStatus? = statInternal(path, FollowMode.FOLLOW_ALL, StatSources.IN_MEMORY_ONLY)
        return status is FileStatusWithMetadata
                && status.getMetadata().isRemote()
    }

    fun updateContext(action: ActionExecutionMetadata?) {
        this.action = action
    }

    @Throws(IOException::class)
    fun injectRemoteFile(path: PathFragment, digest: ByteArray?, size: Long, expirationTime: Instant?) {
        if (!isOutput(path)) {
            return
        }
        val metadata: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FileArtifactValue.createForRemoteFileWithMaterializationData(
                digest, size,  /* locationIndex= */1, expirationTime
            )
        remoteOutputTree.injectFile(path, metadata)
    }

    override fun getFileSystemType(path: PathFragment?): String {
        return "remoteActionFS"
    }

    @Throws(IOException::class)
    override fun resolveSymbolicLinks(path: PathFragment?): com.google.devtools.build.lib.vfs.Path? {
        return getPath(pathCanonicalizer.resolveSymbolicLinks(path))
    }

    @Throws(IOException::class)
    override fun resolveOneLink(path: PathFragment): PathFragment? {
        // The base implementation attempts to readSymbolicLink first and falls back to stat, but that
        // unnecessarily allocates a NotASymlinkException in the overwhelmingly likely non-symlink case.
        // It's more efficient to stat unconditionally.
        //
        // The parent path has already been canonicalized, so FOLLOW_NONE is effectively the same as
        // FOLLOW_PARENT, but much more efficient as it doesn't call stat recursively. Likewise for
        // readSymbolicLinkInternal instead of readSymbolicLink.
        val stat: FileStatus? = statInternal(path, FollowMode.FOLLOW_NONE, StatSources.ALL)
        if (stat == null) {
            throw FileNotFoundException(path.getPathString() + " (No such file or directory)")
        }
        return if (stat.isSymbolicLink()) readSymbolicLinkInternal(path) else null
    }

    // Like resolveSymbolicLinks(), except that only the parent path is canonicalized.
    @Throws(IOException::class)
    private fun resolveSymbolicLinksForParent(path: PathFragment): PathFragment {
        val parentPath: PathFragment? = path.getParentDirectory()
        if (parentPath != null) {
            return resolveSymbolicLinks(parentPath).asFragment().getChild(path.getBaseName())
        }
        return path
    }

    @Throws(IOException::class)
    override fun delete(path: PathFragment): Boolean {
        var path: PathFragment = path
        val originalPath: PathFragment? = path
        try {
            path = resolveSymbolicLinksForParent(path)
        } catch (ignored: FileNotFoundException) {
            // Failure to delete a nonexistent path is not an error.
            return false
        }

        // No action implementations call renameTo concurrently with other filesystem operations, so
        // there's no risk of a race condition below.
        pathCanonicalizer.clearPrefix(originalPath)

        var deleted: Boolean = localFs.getPath(path).delete()
        if (isOutput(path)) {
            deleted = remoteOutputTree.getPath(path).delete() || deleted
        }

        return deleted
    }

    @Throws(IOException::class)
    override fun getInputStream(path: PathFragment): java.io.InputStream? {
        try {
            com.google.devtools.build.lib.remote.util.Utils.getFromFuture<java.lang.Void?>(downloadIfRemote(path))
        } catch (e: java.lang.InterruptedException) {
            java.lang.Thread.currentThread().interrupt()
            throw IOException(java.lang.String.format("Received interrupt while fetching file '%s'", path), e)
        } catch (e: BulkTransferException) {
            val newlyLostInputs: LostArtifacts = e.getLostArtifacts(inputArtifactData::getInput)
            if (!newlyLostInputs.isEmpty()) {
                lostInputs.add(newlyLostInputs)
            }
            throw e
        }
        return localFs.getPath(path).getInputStream()
    }

    /** Downloads the file at `path` if it is remote.  */
    fun downloadIfRemote(path: PathFragment): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? {
        try {
            if (!isRemote(path)) {
                return com.google.common.util.concurrent.Futures.immediateVoidFuture()
            }
        } catch (e: IOException) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(e)
        }
        val execPath: PathFragment? = path.relativeTo(execRoot)
        val input: ActionInput? = inputArtifactData.getInput(execPath)
        if (input == null) {
            // TODO(tjgq): Also look up the remote output tree.
            return com.google.common.util.concurrent.Futures.immediateVoidFuture()
        }
        return inputFetcher.prefetchFiles(
            action,  /* spawn= */
            null,
            java.util.function.Supplier { com.google.common.collect.ImmutableList.of<ActionInput?>(input) },
            inputArtifactData,
            Priority.CRITICAL,
            Reason.INPUTS
        )
    }

    @Throws(IOException::class)
    override fun getOutputStream(path: PathFragment?, append: Boolean, internal: Boolean): java.io.OutputStream? {
        return localFs.getPath(path).getOutputStream(append, internal)
    }

    @Throws(IOException::class)
    override fun createReadWriteByteChannel(path: PathFragment?): SeekableByteChannel? {
        return localFs.getPath(path).createReadWriteByteChannel()
    }

    @Throws(IOException::class)
    override fun setLastModifiedTime(path: PathFragment?, newTime: Long) {
        var path: PathFragment? = path
        path = resolveSymbolicLinks(path).asFragment()

        var remoteException: FileNotFoundException? = null
        try {
            // We can't set mtime for a remote file, set mtime of in-memory file node instead.
            remoteOutputTree.setLastModifiedTime(path, newTime)
        } catch (e: FileNotFoundException) {
            remoteException = e
        }

        var localException: FileNotFoundException? = null
        try {
            localFs.getPath(path).setLastModifiedTime(newTime)
        } catch (e: FileNotFoundException) {
            localException = e
        }

        if (remoteException == null || localException == null) {
            return
        }

        localException.addSuppressed(remoteException)
        throw localException
    }

    @Throws(IOException::class)
    override fun getxattr(path: PathFragment?, name: String?, followSymlinks: Boolean): ByteArray? {
        return localFs
            .getPath(path)
            .getxattr(name, if (followSymlinks) Symlinks.FOLLOW else Symlinks.NOFOLLOW)
    }

    @Throws(IOException::class)
    override fun getFastDigest(path: PathFragment): ByteArray? {
        var path: PathFragment = path
        path = resolveSymbolicLinks(path).asFragment()
        // Try to obtain a fast digest through a stat. This is only possible for in-memory files.
        // The parent path has already been canonicalized by resolveSymbolicLinks, so FOLLOW_NONE is
        // effectively the same as FOLLOW_PARENT, but more efficient.
        val status: FileStatus? = statInternal(path, FollowMode.FOLLOW_NONE, StatSources.IN_MEMORY_ONLY)
        if (status is FileStatusWithDigest) {
            return status.getDigest()
        }
        return localFs.getPath(path).getFastDigest()
    }

    @Throws(IOException::class)
    override fun getDigest(path: PathFragment): ByteArray? {
        var path: PathFragment = path
        path = resolveSymbolicLinks(path).asFragment()
        // Try to obtain a fast digest through a stat. This is only possible for in-memory files.
        // The parent path has already been canonicalized by resolveSymbolicLinks, so FOLLOW_NONE is
        // effectively the same as FOLLOW_PARENT, but more efficient.
        val status: FileStatus? = statInternal(path, FollowMode.FOLLOW_NONE, StatSources.IN_MEMORY_ONLY)
        if (status is FileStatusWithDigest) {
            return status.getDigest()
        }
        return localFs.getPath(path).getDigest()
    }

    @Throws(IOException::class)
    override fun isReadable(path: PathFragment?): Boolean {
        var path: PathFragment? = path
        path = resolveSymbolicLinks(path).asFragment()
        try {
            return localFs.getPath(path).isReadable()
        } catch (e: FileNotFoundException) {
            // Remote files are always readable since we can't control their permissions.
            return true
        }
    }

    @Throws(IOException::class)
    override fun isWritable(path: PathFragment?): Boolean {
        var path: PathFragment? = path
        path = resolveSymbolicLinks(path).asFragment()
        try {
            return localFs.getPath(path).isWritable()
        } catch (e: FileNotFoundException) {
            // Remote files are always writable since we can't control their permissions.
            return true
        }
    }

    @Throws(IOException::class)
    override fun isExecutable(path: PathFragment?): Boolean {
        var path: PathFragment? = path
        path = resolveSymbolicLinks(path).asFragment()
        try {
            return localFs.getPath(path).isExecutable()
        } catch (e: FileNotFoundException) {
            // Remote files are always executable since we can't control their permissions.
            return true
        }
    }

    @Throws(IOException::class)
    override fun setReadable(path: PathFragment?, readable: Boolean) {
        var path: PathFragment? = path
        path = resolveSymbolicLinks(path).asFragment()
        try {
            localFs.getPath(path).setReadable(readable)
        } catch (e: FileNotFoundException) {
            // Intentionally ignored.
        }
    }

    @Throws(IOException::class)
    override fun setWritable(path: PathFragment?, writable: Boolean) {
        var path: PathFragment? = path
        path = resolveSymbolicLinks(path).asFragment()
        try {
            localFs.getPath(path).setWritable(writable)
        } catch (e: FileNotFoundException) {
            // Intentionally ignored.
        }
    }

    @Throws(IOException::class)
    override fun setExecutable(path: PathFragment?, executable: Boolean) {
        var path: PathFragment? = path
        path = resolveSymbolicLinks(path).asFragment()
        try {
            localFs.getPath(path).setExecutable(executable)
        } catch (e: FileNotFoundException) {
            // Intentionally ignored.
        }
    }

    @Throws(IOException::class)
    override fun chmod(path: PathFragment?, mode: Int) {
        var path: PathFragment? = path
        path = resolveSymbolicLinks(path).asFragment()
        try {
            localFs.getPath(path).chmod(mode)
        } catch (e: FileNotFoundException) {
            // Intentionally ignored.
        }
    }

    @Throws(IOException::class)
    override fun readSymbolicLink(path: PathFragment): PathFragment? {
        return readSymbolicLinkInternal(resolveSymbolicLinksForParent(path))
    }

    // Like readSymbolicLink(), except that the parent path is assumed to be already canonical.
    @Throws(IOException::class)
    private fun readSymbolicLinkInternal(path: PathFragment): PathFragment? {
        if (path.startsWith(execRoot)) {
            val execPath: PathFragment? = path.relativeTo(execRoot)
            val actionInput: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                inputArtifactData.getInput(execPath)
            val metadata: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                if (actionInput != null) inputArtifactData.getInputMetadata(actionInput) else null
            if (metadata != null && metadata.getType().isSymlink()) {
                return PathFragment.create(metadata.getUnresolvedSymlinkTarget())
            }
            if (metadata != null) {
                // Other input artifacts are never symlinks.
                throw NotASymlinkException(path)
            }
            if (inputTreeArtifactDirectoryCache.get(execPath) != null) {
                // Tree artifacts never contain symlinks.
                throw NotASymlinkException(path)
            }
        }

        if (isOutput(path)) {
            try {
                return remoteOutputTree.getPath(path).readSymbolicLink()
            } catch (e: FileNotFoundException) {
                // Intentionally ignored.
            }
        }

        return localFs.getPath(path).readSymbolicLink()
    }

    @Throws(IOException::class)
    override fun createSymbolicLink(
        linkPath: PathFragment, targetFragment: PathFragment?, type: SymlinkTargetType?
    ) {
        var linkPath: PathFragment = linkPath
        linkPath = resolveSymbolicLinksForParent(linkPath)

        if (isOutput(linkPath)) {
            remoteOutputTree.getPath(linkPath).createSymbolicLink(targetFragment, type)
        }

        localFs.getPath(linkPath).createSymbolicLink(targetFragment, type)
    }

    @Throws(IOException::class)
    override fun getLastModifiedTime(path: PathFragment, followSymlinks: Boolean): Long {
        val stat: FileStatus = stat(path, followSymlinks)
        return stat.getLastModifiedTime()
    }

    @Throws(IOException::class)
    override fun getFileSize(path: PathFragment, followSymlinks: Boolean): Long {
        val stat: FileStatus = stat(path, followSymlinks)
        return stat.getSize()
    }

    override fun exists(path: PathFragment, followSymlinks: Boolean): Boolean {
        try {
            return statIfFound(path, followSymlinks) != null
        } catch (e: IOException) {
            return false
        }
    }

    @Throws(IOException::class)
    override fun stat(path: PathFragment, followSymlinks: Boolean): FileStatus {
        val stat: FileStatus = statIfFound(path, followSymlinks)
        if (stat == null) {
            throw FileNotFoundException(path.getPathString() + " (No such file or directory)")
        }
        return stat
    }

    @Throws(IOException::class)
    override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus? {
        return statInternal(
            path, if (followSymlinks) FollowMode.FOLLOW_ALL else FollowMode.FOLLOW_PARENT, StatSources.ALL
        )
    }

    override fun statNullable(path: PathFragment, followSymlinks: Boolean): FileStatus? {
        try {
            return statIfFound(path, followSymlinks)
        } catch (e: IOException) {
            return null
        }
    }

    /**
     * Internal stat implementation.
     * 
     * @param path the path to stat
     * @param followMode whether and how to canonicalize the path
     * @param statSources which sources to consider
     * @return the file status on success, or null if the file was not found in any of the sources
     * under consideration
     * @throws IOException if an error other than file not found occurred
     */
    @Throws(IOException::class)
    private fun statInternal(path: PathFragment, followMode: FollowMode?, statSources: StatSources?): FileStatus? {
        // Canonicalize the path.
        var path: PathFragment = path
        try {
            if (followMode == FollowMode.FOLLOW_ALL) {
                path = resolveSymbolicLinks(path).asFragment()
            } else if (followMode == FollowMode.FOLLOW_PARENT) {
                val parent: PathFragment? = path.getParentDirectory()
                if (parent != null) {
                    path = resolveSymbolicLinks(parent).asFragment().getChild(path.getBaseName())
                }
            }
        } catch (e: FileNotFoundException) {
            return null
        }

        // Since the path has been canonicalized, the operations below never need to follow symlinks.
        if (path.startsWith(execRoot)) {
            val execPath: PathFragment? = path.relativeTo(execRoot)
            val actionInput: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                inputArtifactData.getInput(execPath)
            val metadata: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                if (actionInput != null) inputArtifactData.getInputMetadata(actionInput) else null
            if (metadata != null) {
                return statFromMetadata(metadata)
            }
            if (inputTreeArtifactDirectoryCache.get(execPath) != null) {
                return DIRECTORY_FILE_STATUS
            }
        }

        val stat: FileStatus? = remoteOutputTree.statIfFound(path,  /* followSymlinks= */false)
        if (stat != null) {
            return stat
        }

        if (statSources == StatSources.ALL) {
            return localFs.getPath(path).statIfFound(Symlinks.NOFOLLOW)
        }

        return null
    }

    private fun isOutput(path: PathFragment): Boolean {
        return path.startsWith(outputBase)
    }

    @Throws(IOException::class)
    override fun renameTo(srcPath: PathFragment, dstPath: PathFragment) {
        var srcPath: PathFragment = srcPath
        var dstPath: PathFragment = dstPath
        srcPath = resolveSymbolicLinksForParent(srcPath)
        dstPath = resolveSymbolicLinksForParent(dstPath)

        com.google.common.base.Preconditions.checkArgument(isOutput(srcPath), "srcPath must be an output path")
        com.google.common.base.Preconditions.checkArgument(isOutput(dstPath), "dstPath must be an output path")

        // No action implementations call renameTo concurrently with other filesystem operations, so
        // there's no risk of a race condition below.
        pathCanonicalizer.clearPrefix(srcPath)
        pathCanonicalizer.clearPrefix(dstPath)

        var remoteException: FileNotFoundException? = null
        try {
            remoteOutputTree.renameTo(srcPath, dstPath)
        } catch (e: FileNotFoundException) {
            remoteException = e
        }

        var localException: FileNotFoundException? = null
        try {
            localFs.renameTo(srcPath, dstPath)
        } catch (e: FileNotFoundException) {
            localException = e
        }

        if (remoteException == null || localException == null) {
            return
        }

        localException.addSuppressed(remoteException)
        throw localException
    }

    @Throws(IOException::class)
    override fun createDirectoryAndParents(path: PathFragment) {
        localFs.createDirectoryAndParents(path)
        if (isOutput(path)) {
            remoteOutputTree.createDirectoryAndParents(path)
        }
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    override fun createDirectory(path: PathFragment): Boolean {
        var created: Boolean = localFs.createDirectory(path)
        if (isOutput(path)) {
            created = remoteOutputTree.createDirectory(path) || created
        }
        return created
    }

    @Throws(IOException::class)
    override fun getDirectoryEntries(path: PathFragment): MutableCollection<String?> {
        return getDirectoryContents<String?>(
            path,  /* followSymlinks= */
            false,
            java.util.function.Function { obj: com.google.devtools.build.lib.vfs.Dirent? -> obj.getName() })
    }

    @Throws(IOException::class)
    override fun readdir(
        path: PathFragment,
        followSymlinks: Boolean
    ): MutableCollection<com.google.devtools.build.lib.vfs.Dirent?> {
        return getDirectoryContents<com.google.devtools.build.lib.vfs.Dirent?>(
            path,
            followSymlinks,
            java.util.function.Function.identity<com.google.devtools.build.lib.vfs.Dirent?>()
        )
    }

    @Throws(IOException::class)
    private fun <T : Comparable<T?>?> getDirectoryContents(
        path: PathFragment,
        followSymlinks: Boolean,
        transformer: java.util.function.Function<com.google.devtools.build.lib.vfs.Dirent?, T?>
    ): com.google.common.collect.ImmutableSortedSet<T?> {
        var path: PathFragment = path
        path = resolveSymbolicLinks(path).asFragment()

        val entries: HashMap<String?, com.google.devtools.build.lib.vfs.Dirent?> =
            HashMap<String?, com.google.devtools.build.lib.vfs.Dirent?>()
        var exists = false

        if (path.startsWith(execRoot)) {
            val execPath: PathFragment? = path.relativeTo(execRoot)
            val treeEntries: MutableCollection<com.google.devtools.build.lib.vfs.Dirent>? =
                inputTreeArtifactDirectoryCache.get(execPath)
            if (treeEntries != null) {
                for (entry in treeEntries) {
                    entries.put(entry.getName(), entry)
                }
                exists = true
            }
        }

        // Since actions are assumed not to modify their inputs, a directory belonging to an input tree
        // artifact cannot also contain an output, so we can safely skip the other sources.
        if (!exists) {
            if (isOutput(path)) {
                try {
                    for (entry in remoteOutputTree.getPath(path).readdir(Symlinks.NOFOLLOW)) {
                        var entry: com.google.devtools.build.lib.vfs.Dirent = entry
                        entry = maybeFollowSymlinkForDirent(path, entry, followSymlinks)
                        entries.put(entry.getName(), entry)
                    }
                    exists = true
                } catch (ignored: FileNotFoundException) {
                    // Will be rethrown below if directory does not exist in any of the sources.
                }
            }

            try {
                for (entry in localFs.getPath(path).readdir(Symlinks.NOFOLLOW)) {
                    var entry: com.google.devtools.build.lib.vfs.Dirent = entry
                    entry = maybeFollowSymlinkForDirent(path, entry, followSymlinks)
                    entries.put(entry.getName(), entry)
                }
                exists = true
            } catch (ignored: FileNotFoundException) {
                // Will be rethrown below if directory does not exist in any of the sources.
            }
        }

        if (!exists) {
            throw FileNotFoundException(path.getPathString() + " (No such file or directory)")
        }

        // Sort entries to get a deterministic order.
        val builder: com.google.common.collect.ImmutableSortedSet.Builder<T?> =
            com.google.common.collect.ImmutableSortedSet.naturalOrder<T?>()
        for (entry in entries.values()) {
            builder.add(transformer.apply(entry))
        }
        return builder.build()
    }

    private fun maybeFollowSymlinkForDirent(
        dirPath: PathFragment, entry: com.google.devtools.build.lib.vfs.Dirent, followSymlinks: Boolean
    ): com.google.devtools.build.lib.vfs.Dirent? {
        if (!followSymlinks || entry.getType() != com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK) {
            return entry
        }
        val path: PathFragment = dirPath.getChild(entry.getName())
        val st: FileStatus? = statNullable(path,  /* followSymlinks= */true)
        return com.google.devtools.build.lib.vfs.Dirent(
            entry.getName(),
            com.google.devtools.build.lib.vfs.FileSystem.direntFromStat(st)
        )
    }

    @Throws(IOException::class)
    override fun createFSDependentHardLink(linkPath: PathFragment?, originalPath: PathFragment?) {
        // Only called by the FileSystem#createHardLink base implementation, overridden below.
        throw java.lang.UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun createHardLink(linkPath: PathFragment?, originalPath: PathFragment?) {
        localFs.getPath(linkPath).createHardLink(getPath(originalPath))
    }

    @Throws(LostInputsActionExecutionException::class)
    fun checkForLostInputs(action: Action?) {
        val mergedException: java.util.Optional<Any?> =
            lostInputs.stream()
                .map<Any?>(java.util.function.Function { lostArtifacts: LostArtifacts? ->
                    LostInputsExecException(
                        lostArtifacts.byDigest()
                    )
                })
                .reduce(LostInputsExecException::combine)
        if (mergedException.isPresent()) {
            throw ActionExecutionException.fromExecException(
                mergedException.get(),
                action
            ) as LostInputsActionExecutionException?
        }
    }

    internal open class RemoteInMemoryFileSystem(hashFunction: DigestHashFunction?) : InMemoryFileSystem(hashFunction) {
        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun getOutputStream(
            path: PathFragment?, append: Boolean, internal: Boolean
        ): java.io.OutputStream? {
            // To get an output stream from remote file, we need to first stage it.
            throw java.lang.IllegalStateException("Shouldn't be called directly")
        }

        override fun newFile(
            clock: com.google.devtools.build.lib.clock.Clock?,
            path: PathFragment?
        ): com.google.devtools.build.lib.vfs.inmemoryfs.FileInfo {
            return RemoteInMemoryFileInfo(clock)
        }

        @Throws(IOException::class)
        fun injectFile(path: PathFragment, metadata: FileArtifactValue) {
            checkArgument(metadata.isRemote(), "metadata is not remote: %s", metadata)
            createDirectoryAndParents(path.getParentDirectory())
            val node: InMemoryContentInfo? = getOrCreateWritableInode(path)
            // If a node already exists but is not a regular file, throw an error.
            if (node !is RemoteInMemoryFileInfo) {
                throw IOException("Could not inject into " + node)
            }

            node.set(metadata)
        }
    }

    internal class RemoteInMemoryFileInfo(clock: com.google.devtools.build.lib.clock.Clock?) :
        com.google.devtools.build.lib.vfs.inmemoryfs.FileInfo(clock), FileStatusWithMetadata {
        private var metadata: FileArtifactValue? = null

        private fun set(metadata: FileArtifactValue) {
            this.metadata = metadata
        }

        @Throws(IOException::class)
        override fun getOutputStream(append: Boolean): java.io.OutputStream? {
            throw java.lang.IllegalStateException("Shouldn't be called directly")
        }

        @get:Throws(IOException::class)
        val inputStream: java.io.InputStream?
            get() {
                throw java.lang.IllegalStateException("Shouldn't be called directly")
            }

        @Throws(IOException::class)
        override fun createReadWriteByteChannel(): SeekableByteChannel? {
            throw java.lang.IllegalStateException("Shouldn't be called directly")
        }

        @Throws(IOException::class)
        override fun getxattr(name: String?): ByteArray? {
            throw java.lang.IllegalStateException("Shouldn't be called directly")
        }

        val fastDigest: ByteArray
            get() = metadata.getDigest()

        @get:Throws(IOException::class)
        val digest: ByteArray
            get() = metadata.getDigest()

        val size: Long
            get() = metadata.getSize()

        public override fun getMetadata(): FileArtifactValue {
            return metadata
        }
    }

    companion object {
        private val DIRECTORY_FILE_STATUS: FileStatus = object : FileStatus() {
            val isFile: Boolean
                get() = false

            val isDirectory: Boolean
                get() = true

            val isSymbolicLink: Boolean
                get() = false

            val isSpecialFile: Boolean
                get() = false

            val size: Long
                get() = 0

            val lastModifiedTime: Long
                get() {
                    throw java.lang.UnsupportedOperationException()
                }

            val lastChangeTime: Long
                get() {
                    throw java.lang.UnsupportedOperationException()
                }

            val nodeId: Long
                get() {
                    throw java.lang.UnsupportedOperationException()
                }
        }

        private fun statFromMetadata(m: FileArtifactValue): FileStatusWithMetadata {
            return object : FileStatusWithMetadata() {
                val digest: ByteArray
                    get() = m.getDigest()

                val isFile: Boolean
                    get() = m.getType().isFile()

                val isDirectory: Boolean
                    get() = m.getType().isDirectory()

                val isSymbolicLink: Boolean
                    get() = m.getType().isSymlink()

                val isSpecialFile: Boolean
                    get() = m.getType().isSpecialFile()

                val size: Long
                    get() = m.getSize()

                val lastModifiedTime: Long
                    get() {
                        try {
                            return m.getModifiedTime()
                        } catch (e: java.lang.UnsupportedOperationException) {
                            // Not every FileArtifactValue supports getModifiedTime.
                            return 0
                        }
                    }

                val lastChangeTime: Long
                    get() = this.lastModifiedTime

                val nodeId: Long
                    get() {
                        throw java.lang.UnsupportedOperationException("Cannot get node id for " + m)
                    }

                val metadata: FileArtifactValue
                    get() = m
            }
        }
    }
}
