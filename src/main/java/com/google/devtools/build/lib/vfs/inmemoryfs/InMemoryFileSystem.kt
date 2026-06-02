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
package com.google.devtools.build.lib.vfs.inmemoryfs

import com.google.common.base.MoreObjects
import com.google.common.base.Preconditions
import com.google.common.collect.Iterators
import com.google.devtools.build.lib.clock.Clock
import com.google.devtools.build.lib.clock.JavaClock
import com.google.devtools.build.lib.collect.CollectionUtils.isNullOrEmpty
import com.google.devtools.build.lib.vfs.FileSystem
import com.google.devtools.build.lib.vfs.Path
import com.google.errorprone.annotations.CheckReturnValue
import java.io.InputStream
import java.io.OutputStream
import kotlin.collections.ArrayList
import kotlin.collections.MutableCollection
import kotlin.collections.MutableIterator
import kotlin.collections.MutableList

/**
 * This class provides a complete in-memory file system.
 * 
 * 
 * Naming convention: we use "path" for all [Path] variables, since these represent *names*
 * and we use "node" or "inode" for InMemoryContentInfo variables, since these correspond to inodes
 * in the UNIX file system.
 * 
 * 
 * The code is structured to be as similar to the implementation of UNIX "namei" as is reasonably
 * possibly. This provides a firm reference point for many concepts and makes compatibility easier
 * to achieve.
 * 
 * 
 * Although this filesystem stores all 9 permission bits (user, group and other), only the user
 * bits are considered for the purpose of determining whether a file is accessible.
 */
@ThreadSafe
open class InMemoryFileSystem(@kotlin.jvm.JvmField protected val clock: Clock, hashFunction: DigestHashFunction?) :
    FileSystem(hashFunction) {
    // The root inode (a directory).
    private val rootInode: InMemoryDirectoryInfo

    /**
     * Creates a new `InMemoryFileSystem` with default clock and given hash function.
     * 
     * @param hashFunction the function to use for calculating digests.
     */
    constructor(hashFunction: DigestHashFunction?) : this(JavaClock(), hashFunction)

    /** Creates a new `InMemoryFileSystem` with the given clock and hash function.  */
    init {
        this.rootInode = newRootInode(clock)
    }

    /** The errors that [InMemoryFileSystem] might issue for different sorts of IO failures.  */
    protected enum class Errno(message: String) : InodeOrErrno {
        ENOENT("No such file or directory"),
        EACCES("Permission denied"),
        ENOTDIR("Not a directory"),
        EEXIST("File exists"),
        EBUSY("Device or resource busy"),
        ENOTEMPTY("Directory not empty"),
        EISDIR("Is a directory"),
        ELOOP("Too many levels of symbolic links");

        private val message: String?

        init {
            this.message = message
        }

        @Throws(IOException::class)
        override fun inodeOrThrow(path: PathFragment?): InMemoryContentInfo? {
            throw exception(path)
        }

        override fun toString(): String {
            return message!!
        }

        /**
         * Throws a new [IOException] for this error. The exception message contains `path`,
         * and is consistent with the messages returned by [ ].
         */
        @Throws(IOException::class)
        fun exception(path: PathFragment?): IOException? {
            val m = path.toString() + " (" + message + ")"
            when (this) {
                Errno.EACCES -> throw FileAccessException(m)
                Errno.ENOENT -> throw FileNotFoundException(m)
                Errno.ELOOP -> throw FileSymlinkLoopException(m)
                else -> throw IOException(m)
            }
        }
    }

    /**
     * {@inheritDoc}
     * 
     * 
     * If `/proc/mounts` does not exist return `"inmemoryfs"`.
     */
    override fun getFileSystemType(path: PathFragment): String? {
        return if (exists(path.getRelative("/proc/mounts"))) super.getFileSystemType(path) else "inmemoryfs"
    }

    protected fun newFile(clock: Clock?, path: PathFragment?): FileInfo {
        return InMemoryFileInfo(clock)
    }

    /** How to handle [Errno.ENOENT] during [.pathWalkErrno].  */
    private enum class OnEnoent {
        /** Halt the walk with [Errno.ENOENT].  */
        HALT,

        /**
         * Create a file node if at the last segment of the walk, otherwise halt with [ ][Errno.ENOENT].
         */
        CREATE_FILE,

        /** Create a directory node.  */
        CREATE_DIRECTORY_AND_PARENTS
    }

    /**
     * Low-level path-to-inode lookup routine. Analogous to path_walk() in many UNIX kernels. Given
     * 'path', walks the directory tree from the root, resolving all symbolic links, and returns the
     * designated inode.
     * 
     * 
     * ENOENT along the walk is handled according to the given [OnEnoent].
     * 
     * 
     * May fail with ENOTDIR, ENOENT, EACCES, ELOOP.
     */
    @kotlin.jvm.Synchronized
    private fun pathWalkErrno(path: PathFragment, behavior: OnEnoent?): InodeOrErrno? {
        var it: MutableIterator<String?> = path.segments().iterator()

        // Prepend the Windows drive if there is one.
        if (path.getDriveStrLength() > 1) {
            it = Iterators.concat<String?>(Iterators.singletonIterator<String?>(path.getDriveStr()), it)
        }

        var inode: InMemoryContentInfo = rootInode
        var traversals = 0

        // Stack of symlink targets. Lazily initialized because we probably won't see any.
        var symlinks: Deque<String?>? = null

        while (it.hasNext() || !isNullOrEmpty(symlinks)) {
            traversals++

            val name = if (!isNullOrEmpty(symlinks)) symlinks.pop() else it.next()

            val childOrError: InodeOrErrno = directoryLookupErrno(inode, name)

            val child: InMemoryContentInfo
            if (childOrError is InMemoryContentInfo) {
                child = childOrError
            } else if (childOrError === Errno.ENOENT && behavior != OnEnoent.HALT) {
                val parent = inode.asDirectory()
                val error: Errno?
                if (behavior == OnEnoent.CREATE_DIRECTORY_AND_PARENTS) {
                    // ENOENT anywhere with Create.DIRECTORY_AND_PARENTS => create a new directory.
                    val newDir = InMemoryDirectoryInfo(clock)
                    error = insertChildDirectory(parent, newDir, name)
                    child = newDir
                } else if (!it.hasNext() && isNullOrEmpty(symlinks)) {
                    // ENOENT on last segment with Create.FILE => create a new file.
                    child = newFile(clock, path)
                    error = insert(parent, name, child)
                } else {
                    return childOrError
                }
                if (error != null) {
                    return error
                }
            } else {
                return childOrError
            }

            if (!child.isSymbolicLink()) {
                inode = child
            } else {
                val linkTarget: PathFragment = (child as InMemoryLinkInfo).getNormalizedLinkContent()
                if (linkTarget.isAbsolute()) {
                    inode = rootInode
                }
                if (traversals > MAX_TRAVERSALS) {
                    return Errno.ELOOP
                }

                val segments: MutableList<String?> = linkTarget.splitToListOfSegments() // May include ".." segments.
                if (symlinks == null) {
                    symlinks = ArrayDeque<String?>(segments)
                } else {
                    for (ii in segments.indices.reversed()) {
                        symlinks.push(segments.get(ii))
                    }
                }
                // Push Windows drive if there is one.
                if (linkTarget.getDriveStrLength() > 1) {
                    symlinks.push(linkTarget.getDriveStr())
                }
            }
        }
        return inode
    }

    /**
     * Given 'path', returns the existing directory inode it designates, following symbolic links.
     * 
     * 
     * May fail with ENOTDIR, or any exception from pathWalk.
     */
    private fun getDirectoryErrno(path: PathFragment): InodeOrErrno {
        return when (pathWalkErrno(path, OnEnoent.HALT)) {
            -> error
            -> if (dirInfo.isDirectory()) dirInfo else Errno.ENOTDIR
        }
    }

    /**
     * Given 'path', returns the existing directory inode it designates, following symbolic links.
     * 
     * 
     * May fail with ENOTDIR, or any exception from pathWalk.
     */
    @Throws(IOException::class)
    private fun getDirectory(path: PathFragment): InMemoryDirectoryInfo {
        return getDirectoryErrno(path).inodeOrThrow(path).asDirectory()
    }

    /** Helper method for stat and inodeStat: return the path's (no symlink-followed) stat.  */
    @kotlin.jvm.Synchronized
    private fun noFollowStatErrno(path: PathFragment): InodeOrErrno? {
        return when (getDirectoryErrno(path.getParentDirectory())) {
            -> error
            -> directoryLookupErrno(dirInfo, baseNameOrWindowsDrive(path))
        }
    }

    /**
     * Given 'path', returns the existing inode it designates, optionally following symbolic links.
     * Analogous to UNIX stat(2)/lstat(2), except that it returns a mutable inode we can modify
     * directly.
     */
    @Throws(IOException::class)
    override fun stat(path: PathFragment, followSymlinks: Boolean): FileStatus {
        return inodeStatErrno(path, followSymlinks)!!.inodeOrThrow(path)
    }

    @Throws(IOException::class)
    override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus? {
        return when (inodeStatErrno(path, followSymlinks)) {
            -> inode
            Errno.ENOENT, Errno.ENOTDIR -> null
            -> throw error.exception(path)
        }
    }

    override fun statNullable(path: PathFragment, followSymlinks: Boolean): FileStatus? {
        return when (inodeStatErrno(path, followSymlinks)) {
            -> inode
            -> null
        }
    }

    /** Version of stat that returns an InodeOrErrno of the input path.  */
    @CheckReturnValue
    protected fun inodeStatErrno(path: PathFragment, followSymlinks: Boolean): InodeOrErrno? {
        if (followSymlinks) {
            return pathWalkErrno(path, OnEnoent.HALT)
        }
        return if (isRootDirectory(path)) rootInode else noFollowStatErrno(path)
    }

    @Throws(IOException::class)
    private fun inodeStat(path: PathFragment, followSymlinks: Boolean): InMemoryContentInfo {
        return inodeStatErrno(path, followSymlinks)!!.inodeOrThrow(path)
    }

    /*
   ***************************************************************************
   * FileSystem methods
   */
    /**
     * This is a helper routing for [.resolveSymbolicLinks], i.e. the "user-mode"
     * routing for canonicalizing paths. It is analogous to the code in glibc's realpath(3).
     * 
     * 
     * Just like realpath, resolveSymbolicLinks requires a quadratic number of directory lookups: n
     * path segments are statted, and each stat requires a linear amount of work in the "kernel"
     * routine.
     */
    @Throws(IOException::class)
    override fun resolveOneLink(path: PathFragment): PathFragment? {
        // Beware, this seemingly simple code belies the complex specification of
        // FileSystem.resolveOneLink().
        val status = inodeStat(path, false)
        return if (status.isSymbolicLink()) (status as InMemoryLinkInfo).getLinkContent() else null
    }

    override fun exists(path: PathFragment, followSymlinks: Boolean): Boolean {
        return statNullable(path, followSymlinks) != null
    }

    @Throws(IOException::class)
    override fun chmod(path: PathFragment, permissions: Int) {
        val status = inodeStat(path, true)
        status.chmod(permissions)
    }

    @Throws(IOException::class)
    override fun isReadable(path: PathFragment): Boolean {
        val status = inodeStat(path, true)
        return status.isReadable()
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun setReadable(path: PathFragment, readable: Boolean) {
        val status = inodeStat(path, true)
        status.setReadable(readable)
    }

    @Throws(IOException::class)
    override fun isWritable(path: PathFragment): Boolean {
        val status = inodeStat(path, true)
        return status.isWritable()
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun setWritable(path: PathFragment, writable: Boolean) {
        val status = inodeStat(path, true)
        status.setWritable(writable)
    }

    @Throws(IOException::class)
    override fun isExecutable(path: PathFragment): Boolean {
        val status = inodeStat(path, true)
        return status.isExecutable()
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun setExecutable(path: PathFragment, executable: Boolean) {
        val status = inodeStat(path, true)
        status.setExecutable(executable)
    }

    override fun supportsModifications(path: PathFragment?): Boolean {
        return true
    }

    override fun supportsSymbolicLinksNatively(path: PathFragment?): Boolean {
        return true
    }

    override fun supportsHardLinksNatively(path: PathFragment?): Boolean {
        return true
    }

    override fun mayBeCaseOrNormalizationInsensitive(): Boolean {
        return false
    }

    @Throws(IOException::class)
    override fun createDirectory(path: PathFragment?): Boolean {
        if (isRootDirectory(path)) {
            throw Errno.EACCES.exception(path)
        }

        val parentDir: PathFragment? = path.getParentDirectory()
        val name: String? = baseNameOrWindowsDrive(path)
        val error: Errno?
        synchronized(this) {
            val parent = getDirectory(parentDir)
            val child = parent.getChild(name)
            if (child != null) { // already exists
                if (!child.isDirectory()) {
                    throw Errno.EEXIST.exception(path)
                }
                return false
            }
            error = insertChildDirectory(parent, InMemoryDirectoryInfo(clock), name)
        }
        if (error != null) {
            throw error.exception(path)
        }
        return true
    }

    @Throws(IOException::class)
    override fun createDirectoryAndParents(path: PathFragment) {
        val result =
            pathWalkErrno(path, OnEnoent.CREATE_DIRECTORY_AND_PARENTS)!!.inodeOrThrow(path)
        if (!result.isDirectory()) {
            throw IOException("Not a directory: " + path)
        }
    }

    @Throws(IOException::class)
    override fun createSymbolicLink(
        path: PathFragment?, targetFragment: PathFragment?, type: SymlinkTargetType?
    ) {
        if (isRootDirectory(path)) {
            throw Errno.EACCES.exception(path)
        }

        synchronized(this) {
            val parent = getDirectory(path.getParentDirectory())
            if (parent.getChild(baseNameOrWindowsDrive(path)) != null) {
                throw Errno.EEXIST.exception(path)
            }
            insert(
                parent, baseNameOrWindowsDrive(path), InMemoryLinkInfo(clock, targetFragment), path
            )
        }
    }

    @Throws(IOException::class)
    override fun readSymbolicLink(path: PathFragment): PathFragment? {
        val status = inodeStat(path, false)
        if (status.isSymbolicLink()) {
            Preconditions.checkState(status is InMemoryLinkInfo, status)
            return (status as InMemoryLinkInfo).getLinkContent()
        }
        throw NotASymlinkException(path)
    }

    @Throws(IOException::class)
    override fun getFileSize(path: PathFragment, followSymlinks: Boolean): Long {
        return stat(path, followSymlinks).getSize()
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun getDirectoryEntries(path: PathFragment): MutableCollection<String?> {
        val dirInfo = getDirectory(path)
        if (!dirInfo.isReadable()) {
            throw Errno.EACCES.exception(path)
        }

        val allChildren = dirInfo.getAllChildren()
        val result: MutableList<String?> = ArrayList<String?>(allChildren.size())
        for (child in allChildren) {
            if (child != "." && child != "..") {
                result.add(child)
            }
        }
        return result
    }

    @Throws(IOException::class)
    override fun delete(path: PathFragment?): Boolean {
        if (isRootDirectory(path)) {
            throw Errno.EBUSY.exception(path)
        }

        synchronized(this) {
            when (getDirectoryErrno(path.getParentDirectory())) {
                -> {
                    val child: InMemoryContentInfo? = parent.getChild(baseNameOrWindowsDrive(path))
                    if (child == null) {
                        return false
                    }
                    if (child.isDirectory() && child.getSize() > 2) {
                        throw Errno.ENOTEMPTY.exception(path)
                    }
                    unlink(parent, baseNameOrWindowsDrive(path), path)
                    return true
                }

                -> {
                    return false
                }

                Errno.ENOENT, Errno.ENOTDIR -> {
                    return false
                }

                -> {
                    throw error.exception(path)
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun getLastModifiedTime(path: PathFragment, followSymlinks: Boolean): Long {
        return stat(path, followSymlinks).getLastModifiedTime()
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun setLastModifiedTime(path: PathFragment, newTime: Long) {
        val status = inodeStat(path, true)
        status.setLastModifiedTime(
            if (newTime == Path.Companion.NOW_SENTINEL_TIME) clock.currentTimeMillis() else newTime
        )
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun getInputStream(path: PathFragment): InputStream? {
        return statFile(path).getInputStream()
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun createReadWriteByteChannel(path: PathFragment): SeekableByteChannel? {
        val status = getOrCreateWritableInode(path)
        return (status as FileInfo).createReadWriteByteChannel()
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun getFastDigest(path: PathFragment): ByteArray? {
        return statFile(path).getFastDigest()
    }

    @Throws(IOException::class)
    private fun statFile(path: PathFragment): FileInfo {
        val status = inodeStat(path,  /*followSymlinks=*/true)
        if (status.isDirectory()) {
            throw Errno.EISDIR.exception(path)
        }
        if (!status.isReadable()) {
            throw Errno.EACCES.exception(path)
        }
        Preconditions.checkState(status is FileInfo, status)
        return status as FileInfo
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun getxattr(path: PathFragment, name: String?, followSymlinks: Boolean): ByteArray? {
        val status = inodeStat(path, followSymlinks)
        if (status.isDirectory()) {
            throw Errno.EISDIR.exception(path)
        }
        if (!isReadable(path)) {
            throw Errno.EACCES.exception(path)
        }
        if (!followSymlinks && status.isSymbolicLink()) {
            return null // xattr on symlinks not supported.
        }
        Preconditions.checkState(status is FileInfo, status)
        return (status as FileInfo).getxattr(name)
    }

    /** Creates a new file at the given path and returns its inode.  */
    @Throws(IOException::class)
    protected fun getOrCreateWritableInode(path: PathFragment): InMemoryContentInfo {
        // open(WR_ONLY) of a dangling link writes through the link.  That means
        // that the usual path lookup operations have to behave differently when
        // resolving a path with the intent to create it: instead of failing with
        // ENOENT they have to return an open file.  This is exactly how UNIX
        // kernels do it, which is what we're trying to emulate.
        val child = pathWalkErrno(path, OnEnoent.CREATE_FILE)!!.inodeOrThrow(path)
        if (child.isDirectory()) {
            throw Errno.EISDIR.exception(path)
        }
        if (!child.isWritable()) {
            throw Errno.EACCES.exception(path)
        }
        return child
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun getOutputStream(
        path: PathFragment, append: Boolean, internal: Boolean
    ): OutputStream? {
        val status = getOrCreateWritableInode(path)
        return (status as FileInfo).getOutputStream(append)
    }

    @Throws(IOException::class)
    override fun renameTo(sourcePath: PathFragment?, targetPath: PathFragment?) {
        if (isRootDirectory(sourcePath)) {
            throw Errno.EACCES.exception(sourcePath)
        }
        if (isRootDirectory(targetPath)) {
            throw Errno.EACCES.exception(targetPath)
        }
        synchronized(this) {
            val sourceParent = getDirectory(sourcePath.getParentDirectory())
            val targetParent = getDirectory(targetPath.getParentDirectory())

            val sourceInode = sourceParent.getChild(baseNameOrWindowsDrive(sourcePath))
            if (sourceInode == null) {
                throw Errno.ENOENT.exception(sourcePath)
            }
            val targetInode = targetParent.getChild(baseNameOrWindowsDrive(targetPath))

            unlink(sourceParent, baseNameOrWindowsDrive(sourcePath), sourcePath)
            try {
                // TODO(bazel-team): (2009) test with symbolic links.

                // Precondition checks:

                if (targetInode != null) { // already exists
                    if (targetInode.isDirectory()) {
                        if (!sourceInode.isDirectory()) {
                            throw IOException(sourcePath.toString() + " -> " + targetPath + " (" + Errno.EISDIR + ")")
                        }
                        if (targetInode.getSize() > 2) {
                            throw Errno.ENOTEMPTY.exception(targetPath)
                        }
                    } else if (sourceInode.isDirectory()) {
                        throw IOException(sourcePath.toString() + " -> " + targetPath + " (" + Errno.ENOTDIR + ")")
                    }
                    unlink(targetParent, baseNameOrWindowsDrive(targetPath), targetPath)
                }
                insert(targetParent, baseNameOrWindowsDrive(targetPath), sourceInode, targetPath)
            } catch (e: IOException) {
                insert(
                    sourceParent,
                    baseNameOrWindowsDrive(sourcePath),
                    sourceInode,
                    sourcePath
                ) // restore source
                throw e
            }
        }
    }

    @Throws(IOException::class)
    override fun createFSDependentHardLink(linkPath: PathFragment, originalPath: PathFragment?) {
        // Same check used when creating a symbolic link

        if (isRootDirectory(originalPath)) {
            throw Errno.EACCES.exception(originalPath)
        }

        synchronized(this) {
            val linkParent = getDirectory(linkPath.getParentDirectory())
            // Same check used when creating a symbolic link
            if (linkParent.getChild(baseNameOrWindowsDrive(linkPath)) != null) {
                throw Errno.EEXIST.exception(linkPath)
            }
            insert(
                linkParent,
                baseNameOrWindowsDrive(linkPath),
                getDirectory(originalPath.getParentDirectory())
                    .getChild(baseNameOrWindowsDrive(originalPath)),
                linkPath
            )
        }
    }

    /** Represents either an [Errno] or an [InMemoryContentInfo].  */
    interface InodeOrErrno {
        /**
         * Returns the underlying [InMemoryContentInfo] unless this is an [Errno], in which
         * case [IOException] is thrown, using the given path to construct an error message.
         */
        @Throws(IOException::class)
        fun inodeOrThrow(path: PathFragment?): InMemoryContentInfo
    }

    companion object {
        // Maximum number of traversals before ELOOP is thrown.
        private const val MAX_TRAVERSALS = 256

        private fun newRootInode(clock: Clock?): InMemoryDirectoryInfo {
            val rootInode = InMemoryDirectoryInfo(clock)
            rootInode.addChild(".", rootInode)
            rootInode.addChild("..", rootInode)
            return rootInode
        }

        /*
   ***************************************************************************
   * "Kernel" primitives: basic directory lookup primitives, in topological order.
   */
        /**
         * Unlinks the entry 'child' from its existing parent directory 'dir'. Dual to insert. This
         * succeeds even if 'child' names a non-empty directory; we need that for renameTo. 'child' must
         * be a member of its parent directory, however. Fails if the directory was read-only.
         */
        @Throws(IOException::class)
        private fun unlink(dir: InMemoryDirectoryInfo, child: String?, errorPath: PathFragment?) {
            if (!dir.isExecutable() || !dir.isWritable()) {
                throw Errno.EACCES.exception(errorPath)
            }
            dir.removeChild(child)
        }

        /**
         * Inserts inode 'childInode' into the existing directory 'dir' under the specified 'name'. Dual
         * to unlink. Fails if the directory was read-only.
         */
        @CheckReturnValue
        private fun insert(
            dir: InMemoryDirectoryInfo, child: String?, childInode: InMemoryContentInfo?
        ): Errno? {
            if (!dir.isWritable()) {
                return Errno.EACCES
            }
            dir.addChild(child, childInode)
            return null
        }

        @Throws(IOException::class)
        private fun insert(
            dir: InMemoryDirectoryInfo,
            child: String?,
            childInode: InMemoryContentInfo?,
            errorPath: PathFragment?
        ) {
            val error: Errno? = insert(dir, child, childInode)
            if (error != null) {
                throw error.exception(errorPath)
            }
        }

        /**
         * Given an existing directory 'dir', looks up 'name' within it and returns its inode. May fail
         * with ENOTDIR, EACCES, ENOENT. Error messages will be reported against file 'path'.
         */
        private fun directoryLookupErrno(dir: InMemoryContentInfo, name: String?): InodeOrErrno {
            if (!dir.isDirectory()) {
                return Errno.ENOTDIR
            }
            if (!dir.isExecutable()) {
                return Errno.EACCES
            }
            return MoreObjects.firstNonNull<InodeOrErrno>(dir.asDirectory().getChild(name), Errno.ENOENT)
        }

        private fun insertChildDirectory(
            parent: InMemoryDirectoryInfo, newDir: InMemoryDirectoryInfo, name: String?
        ): Errno? {
            newDir.addChild(".", newDir)
            newDir.addChild("..", parent)
            return insert(parent, name, newDir)
        }

        /**
         * On Unix the root directory is "/". On Windows there isn't one, so we reach null from
         * getParentDirectory.
         */
        private fun isRootDirectory(path: PathFragment?): Boolean {
            return path == null || path.getPathString() == "/"
        }

        /**
         * Returns either the base name of the path, or the drive (if referring to a Windows drive).
         * 
         * 
         * This allows the file system to treat windows drives much like directories.
         */
        private fun baseNameOrWindowsDrive(path: PathFragment): String? {
            val name: String = path.getBaseName()
            return if (!name.isEmpty()) name else path.getDriveStr()
        }
    }
}
