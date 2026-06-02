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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/**
 * A local file path representing a file on the host machine. You should use this when you want to
 * access local files via the file system.
 * 
 * 
 * Paths are always absolute.
 * 
 * 
 * Strings are normalized with '.' and '..' removed and resolved (if possible), any multiple
 * slashes ('/') removed, and any trailing slash also removed. Windows drive letters are uppercased.
 * The current implementation does not touch the incoming path string unless the string actually
 * needs to be normalized.
 * 
 * 
 * There is some limited support for Windows-style paths. Most importantly, drive identifiers in
 * front of a path (c:/abc) are supported and such paths are correctly recognized as absolute, as
 * are paths with backslash separators (C:\\foo\\bar). However, advanced Windows-style features like
 * \\\\network\\paths and \\\\?\\unc\\paths are not supported. We are currently using forward
 * slashes ('/') even on Windows, so backslashes '\' get converted to forward slashes during
 * normalization.
 * 
 * 
 * All paths are case-sensitive.
 */
@ThreadSafe
@AutoCodec
class Path private constructor(pathFragment: PathFragment, fileSystem: com.google.devtools.build.lib.vfs.FileSystem) :
    Comparable<Path?>, FileType.HasFileType {
    private val pathFragment: PathFragment
    private val fileSystem: com.google.devtools.build.lib.vfs.FileSystem

    /** This method expects path to already be normalized.  */
    init {
        com.google.common.base.Preconditions.checkArgument(
            pathFragment.isAbsolute(),
            "Paths must be absolute: '%s'",
            pathFragment
        )
        this.pathFragment = pathFragment
        this.fileSystem = fileSystem
    }

    val pathString: String?
        get() = pathFragment.getPathString()

    public override fun filePathForFileTypeMatcher(): String? {
        return pathFragment.getPathString()
    }

    val baseName: String?
        /**
         * Returns the name of the leaf file or directory.
         * 
         * 
         * If called on a [Path] instance for a mount name (eg. '/' or 'C:/'), the empty string
         * is returned.
         */
        get() = pathFragment.getBaseName()

    /**
     * Returns a [Path] formed by appending `newName` to this [Path]'s parent
     * directory. If this [Path] has zero segments, returns `null`. If `newName` is
     * absolute, the value of `this` will be ignored and a [Path] corresponding to `newName` will be returned. This is consistent with the behavior of [ ][.getRelative].
     */
    fun replaceName(newName: String?): Path? {
        val parent = this.parentDirectory
        return if (parent != null) parent.getRelative(newName) else null
    }

    /** Synonymous with [Path.getRelative].  */
    fun getChild(child: String): Path {
        com.google.devtools.build.lib.vfs.FileSystemUtils.checkBaseName(child)
        return getRelative(child)
    }

    /**
     * Returns a [Path] instance representing the relative path between this [Path] and
     * the given path.
     */
    fun getRelative(other: PathFragment?): Path {
        com.google.common.base.Preconditions.checkNotNull<PathFragment?>(other)
        return com.google.devtools.build.lib.vfs.Path(pathFragment.getRelative(other), fileSystem)
    }

    /**
     * Returns a [Path] instance representing the relative path between this [Path] and
     * the given path.
     */
    fun getRelative(other: String?): Path {
        com.google.common.base.Preconditions.checkNotNull<String?>(other)
        return com.google.devtools.build.lib.vfs.Path(pathFragment.getRelative(other), fileSystem)
    }

    val parentDirectory: Path?
        /**
         * Returns the parent directory of this [Path].
         * 
         * 
         * If called on a root (like '/'), it returns null.
         */
        get() {
            val parentPath: PathFragment? = pathFragment.getParentDirectory()
            if (parentPath == null) {
                return null
            }
            return com.google.devtools.build.lib.vfs.Path(parentPath, fileSystem)
        }

    /**
     * Returns the [Path] relative to the base [Path].
     * 
     * 
     * For example, `Path.create("foo/bar/wiz").relativeTo(Path.create("foo"))
    ` *  returns `Path.create("bar/wiz")`.
     * 
     * 
     * If the [Path] is not a child of the passed [Path] an [ ] is thrown. In particular, this will happen whenever the two [ ] instances aren't both absolute or both relative.
     */
    fun relativeTo(base: Path?): PathFragment? {
        com.google.common.base.Preconditions.checkNotNull<Path?>(base)
        checkSameFileSystem(base!!)
        return pathFragment.relativeTo(base.pathFragment)
    }

    /**
     * Returns whether another path is an ancestor of this path.
     * 
     * 
     * A path is considered an ancestor of itself.
     */
    fun startsWith(other: Path): Boolean {
        if (fileSystem !== other.fileSystem) {
            return false
        }
        return pathFragment.startsWith(other.pathFragment)
    }

    /**
     * Returns whether another path is an ancestor of this path, ignoring case.
     * 
     * 
     * A path is considered an ancestor of itself.
     */
    fun startsWithIgnoringCase(other: Path): Boolean {
        if (fileSystem !== other.fileSystem) {
            return false
        }
        return pathFragment.startsWithIgnoringCase(other.pathFragment)
    }

    /**
     * Returns whether another path is an ancestor of this path.
     * 
     * 
     * A path is considered an ancestor of itself.
     * 
     * 
     * An absolute path can never be an ancestor of a relative path fragment.
     */
    fun startsWith(other: PathFragment?): Boolean {
        return pathFragment.startsWith(other)
    }

    fun getFileSystem(): com.google.devtools.build.lib.vfs.FileSystem {
        return fileSystem
    }

    fun asFragment(): PathFragment {
        return pathFragment
    }

    override fun toString(): String {
        return pathFragment.getPathString()
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is Path) {
            return false
        }
        return fileSystem === o.fileSystem && pathFragment == o.pathFragment
    }

    override fun hashCode(): Int {
        // Do not include file system for efficiency.
        // In practice, we don't expect paths on different file systems to be contained in the same
        // collection.
        return pathFragment.hashCode()
    }

    override fun compareTo(o: Path): Int {
        // If they are on different file systems, the file system decides the ordering.
        val otherFs: com.google.devtools.build.lib.vfs.FileSystem? = o.fileSystem
        if (fileSystem != otherFs) {
            val thisFileSystemHash: Int = java.lang.System.identityHashCode(fileSystem)
            val otherFileSystemHash: Int = java.lang.System.identityHashCode(otherFs)
            if (thisFileSystemHash < otherFileSystemHash) {
                return -1
            } else if (thisFileSystemHash > otherFileSystemHash) {
                return 1
            }
        }
        return pathFragment.compareTo(o.pathFragment)
    }

    /**
     * Returns the same path on the file system that the current file system is based on, if any.
     * Otherwise, returns the current path unchanged.
     * 
     * 
     * For an action file system, this should return the on-disk component (or the result of
     * getHostFileSystem() on that component if it is itself a composite file system).
     * 
     * 
     * Note that the returned path may still reference an in-memory file system (in tests, for
     * example), but should be treated as being on the "native" file system for the host machine.
     */
    fun forHostFileSystem(): Path {
        val hostFs: com.google.devtools.build.lib.vfs.FileSystem = fileSystem.getHostFileSystem()
        if (hostFs == fileSystem) {
            return this
        }
        return com.google.devtools.build.lib.vfs.Path.Companion.create(asFragment(), hostFs)
    }

    /** Returns true iff this path denotes an existing file of any kind. Follows symbolic links.  */
    fun exists(): Boolean {
        return fileSystem.exists(asFragment(), true)
    }

    /**
     * Returns true iff this path denotes an existing file of any kind.
     * 
     * @param followSymlinks if [Symlinks.FOLLOW], and this path denotes a symbolic link, the
     * link is dereferenced until a file other than a symbolic link is found
     */
    fun exists(followSymlinks: Symlinks): Boolean {
        return fileSystem.exists(asFragment(), followSymlinks.toBoolean())
    }

    @get:Throws(IOException::class, FileNotFoundException::class)
    val directoryEntries: MutableCollection<Path?>
        /**
         * Returns a new, immutable collection containing the names of all entities within the directory
         * denoted by the current path. Follows symbolic links.
         * 
         * @throws FileNotFoundException If the directory is not found
         * @throws IOException If the path does not denote a directory
         */
        get() {
            val entries: MutableCollection<String> =
                fileSystem.getDirectoryEntries(asFragment())
            val result: MutableCollection<Path?> =
                java.util.ArrayList<Path?>(entries.size())
            for (entry in entries) {
                result.add(getChild(entry))
            }
            return result
        }

    /**
     * Returns a collection of the names and types of all entries within the directory denoted by the
     * current path. Follows symbolic links if `followSymlinks` is true. Note that the order of
     * the returned entries is not guaranteed.
     * 
     * @param followSymlinks whether to follow symlinks or not
     * @throws FileNotFoundException If the directory is not found
     * @throws IOException If the path does not denote a directory
     */
    @Throws(IOException::class)
    fun readdir(followSymlinks: Symlinks): MutableCollection<com.google.devtools.build.lib.vfs.Dirent?>? {
        return fileSystem.readdir(asFragment(), followSymlinks.toBoolean())
    }

    /**
     * Returns the status of a file, following symbolic links.
     * 
     * @throws IOException if there was an error obtaining the file status. Note, some implementations
     * may defer the I/O, and hence the throwing of the exception, until the accessor methods of
     * `FileStatus` are called.
     */
    @Throws(IOException::class)
    fun stat(): FileStatus? {
        return fileSystem.stat(asFragment(), true)
    }

    /**
     * Returns the status of a file, optionally following symbolic links.
     * 
     * @param followSymlinks if [Symlinks.FOLLOW], and this path denotes a symbolic link, the
     * link is dereferenced until a file other than a symbolic link is found
     * @throws IOException if there was an error obtaining the file status. Note, some implementations
     * may defer the I/O, and hence the throwing of the exception, until the accessor methods of
     * `FileStatus` are called
     */
    @Throws(IOException::class)
    fun stat(followSymlinks: Symlinks): FileStatus? {
        return fileSystem.stat(asFragment(), followSymlinks.toBoolean())
    }

    /**
     * Like stat(), but returns null in case of any error instead of throwing.
     * 
     * 
     * Use [.statIfFound] instead to throw for errors due to any causes other than
     * non-existence.
     */
    fun statNullable(): FileStatus? {
        return statNullable(Symlinks.FOLLOW)
    }

    /**
     * Like stat(), but returns null in case of any error instead of throwing.
     * 
     * 
     * Use [.statIfFound] instead to throw for errors due to any causes other than
     * non-existence.
     */
    fun statNullable(symlinks: Symlinks): FileStatus? {
        return fileSystem.statNullable(asFragment(), symlinks.toBoolean())
    }

    /**
     * Like [.stat], but may return null if the file is not found (corresponding to `ENOENT` and `ENOTDIR` in Unix's stat(2) function) instead of throwing. Follows symbolic
     * links.
     * 
     * 
     * Use [.statNullable] instead to ignore all types of errors.
     */
    @Throws(IOException::class)
    fun statIfFound(): FileStatus? {
        return fileSystem.statIfFound(asFragment(), true)
    }

    /**
     * Like [.stat], but may return null if the file is not found (corresponding to `ENOENT` and `ENOTDIR` in Unix's stat(2) function) instead of throwing.
     * 
     * 
     * Use [.statNullable] instead to ignore all types of errors.
     * 
     * @param followSymlinks if [Symlinks.FOLLOW], and this path denotes a symbolic link, the
     * link is dereferenced until a file other than a symbolic link is found
     */
    @Throws(IOException::class)
    fun statIfFound(followSymlinks: Symlinks): FileStatus? {
        return fileSystem.statIfFound(asFragment(), followSymlinks.toBoolean())
    }

    val isDirectory: Boolean
        /** Returns true iff this path denotes an existing directory. Follows symbolic links.  */
        get() = fileSystem.isDirectory(asFragment(), true)

    /**
     * Returns true iff this path denotes an existing directory.
     * 
     * @param followSymlinks if [Symlinks.FOLLOW], and this path denotes a symbolic link, the
     * link is dereferenced until a file other than a symbolic link is found
     */
    fun isDirectory(followSymlinks: Symlinks): Boolean {
        return fileSystem.isDirectory(asFragment(), followSymlinks.toBoolean())
    }

    val isFile: Boolean
        /**
         * Returns true iff this path denotes an existing regular or special file. Follows symbolic links.
         * 
         * 
         * For our purposes, "file" includes special files (socket, fifo, block or char devices) too;
         * it excludes symbolic links and directories.
         */
        get() = fileSystem.isFile(asFragment(), true)

    /**
     * Returns true iff this path denotes an existing regular or special file.
     * 
     * 
     * For our purposes, a "file" includes special files (socket, fifo, block or char devices) too;
     * it excludes symbolic links and directories.
     * 
     * @param followSymlinks if [Symlinks.FOLLOW], and this path denotes a symbolic link, the
     * link is dereferenced until a file other than a symbolic link is found.
     */
    fun isFile(followSymlinks: Symlinks): Boolean {
        return fileSystem.isFile(asFragment(), followSymlinks.toBoolean())
    }

    val isSpecialFile: Boolean
        /**
         * Returns true iff this path denotes an existing special file (e.g. fifo). Follows symbolic
         * links.
         */
        get() = fileSystem.isSpecialFile(asFragment(), true)

    /**
     * Returns true iff this path denotes an existing special file (e.g. fifo).
     * 
     * @param followSymlinks if [Symlinks.FOLLOW], and this path denotes a symbolic link, the
     * link is dereferenced until a path other than a symbolic link is found.
     */
    fun isSpecialFile(followSymlinks: Symlinks): Boolean {
        return fileSystem.isSpecialFile(asFragment(), followSymlinks.toBoolean())
    }

    val isSymbolicLink: Boolean
        /**
         * Returns true iff this path denotes an existing symbolic link. Does not follow symbolic links.
         */
        get() = fileSystem.isSymbolicLink(asFragment())

    @get:Throws(IOException::class)
    val outputStream: java.io.OutputStream?
        /**
         * Returns an output stream to the file denoted by the current path, creating it and truncating it
         * if necessary. The stream is opened for writing.
         * 
         * @throws FileNotFoundException If the file cannot be found or created.
         * @throws IOException If a different error occurs.
         */
        get() = getOutputStream(false)

    /**
     * Returns an output stream to the file denoted by the current path, creating it and truncating it
     * if necessary. The stream is opened for writing.
     * 
     * @param append whether to open the file in append mode.
     * @throws FileNotFoundException If the file cannot be found or created.
     * @throws IOException If a different error occurs.
     */
    @Throws(IOException::class)
    fun getOutputStream(append: Boolean): java.io.OutputStream? {
        return fileSystem.getOutputStream(asFragment(), append)
    }

    /**
     * Returns an output stream to the file denoted by the current path, creating it and truncating it
     * if necessary. The stream is opened for writing.
     * 
     * @param append whether to open the file in append mode.
     * @param internal whether the file is a Bazel internal file.
     * @throws FileNotFoundException If the file cannot be found or created.
     * @throws IOException If a different error occurs.
     */
    @Throws(IOException::class)
    fun getOutputStream(append: Boolean, internal: Boolean): java.io.OutputStream? {
        return fileSystem.getOutputStream(asFragment(), append, internal)
    }

    /**
     * Ensures that a directory exists with the name of the current path, not following symbolic
     * links. If necessary, creates the directory.
     * 
     * @throws IOException if the directory creation failed
     * @return whether the directory was created by this call
     */
    @Throws(IOException::class)
    fun createDirectory(): Boolean {
        return fileSystem.createDirectory(asFragment())
    }

    /**
     * Ensures that a directory exists with the name of the current path, following symbolic links. If
     * necessary, creates the directory and any missing ancestor directories.
     * 
     * @throws IOException if the directory creation failed
     */
    @Throws(IOException::class)
    fun createDirectoryAndParents() {
        fileSystem.createDirectoryAndParents(asFragment())
    }

    /**
     * Returns the path of a new temporary directory with the given prefix created under the given
     * parent path, but **not** necessarily with secure permissions.
     */
    @Throws(IOException::class)
    fun createTempDirectory(prefix: String?): Path? {
        return fileSystem.getPath(fileSystem.createTempDirectory(asFragment(), prefix))
    }

    /**
     * Creates a symbolic link with the name of the current path, following symbolic links. The
     * referent of the created symlink is is the absolute path "target"; it is not possible to create
     * relative symbolic links via this method.
     * 
     * 
     * The `type` argument denotes the file type of the target, if known. Some filesystems
     * require this information to correctly create a symlink. This argument may be ignored if the
     * target can be observed to exist and is of a different type.
     * 
     * @param type the target file type
     * @throws IOException if the creation of the symbolic link was unsuccessful for any reason
     */
    /**
     * Creates a symbolic link with the name of the current path, following symbolic links. The
     * referent of the created symlink is is the absolute path "target"; it is not possible to create
     * relative symbolic links via this method.
     * 
     * @throws IOException if the creation of the symbolic link was unsuccessful for any reason
     */
    @kotlin.jvm.JvmOverloads
    @Throws(IOException::class)
    fun createSymbolicLink(target: Path, type: SymlinkTargetType? = SymlinkTargetType.UNSPECIFIED) {
        checkSameFileSystem(target)
        fileSystem.createSymbolicLink(asFragment(), target.asFragment(), type)
    }

    /**
     * Creates a symbolic link with the name of the current path, following symbolic links. The
     * referent of the created symlink is is the path fragment "target", which may be absolute or
     * relative.
     * 
     * 
     * The `type` argument denotes the file type of the target, if known. Some filesystems
     * require this information to correctly create a symlink. This argument may be ignored if the
     * target can be observed to exist and is of a different type.
     * 
     * @param type the target file type
     * @throws IOException if the creation of the symbolic link was unsuccessful for any reason
     */
    /**
     * Creates a symbolic link with the name of the current path, following symbolic links. The
     * referent of the created symlink is is the path fragment "target", which may be absolute or
     * relative.
     * 
     * @throws IOException if the creation of the symbolic link was unsuccessful for any reason
     */
    @kotlin.jvm.JvmOverloads
    @Throws(IOException::class)
    fun createSymbolicLink(target: PathFragment?, type: SymlinkTargetType? = SymlinkTargetType.UNSPECIFIED) {
        fileSystem.createSymbolicLink(asFragment(), target, type)
    }

    /**
     * Returns the target of the current path, which must be a symbolic link. The link contents are
     * returned exactly, and may contain an absolute or relative path. Analogous to readlink(2).
     * 
     * 
     * Note: for [FileSystem]s where [ ][FileSystem.supportsSymbolicLinksNatively] returns false, this method will throw
     * an [UnsupportedOperationException] if the link points to a non-existent file.
     * 
     * @return the content (i.e. target) of the symbolic link
     * @throws FileSystem.NotASymlinkException if the current path is not a symbolic link.
     * @throws IOException if the contents of the link could not be read for any reason
     */
    @Throws(IOException::class)
    fun readSymbolicLink(): PathFragment? {
        return fileSystem.readSymbolicLink(asFragment())
    }

    /**
     * If the current path is a symbolic link, returns the target of this symbolic link. The semantics
     * are intentionally left underspecified otherwise to permit efficient implementations.
     * 
     * @return the content (i.e. target) of the symbolic link
     * @throws FileSystem.NotASymlinkException if the current path is not a symbolic link.
     * @throws IOException if the contents of the link could not be read for any reason
     */
    @Throws(IOException::class)
    fun readSymbolicLinkUnchecked(): PathFragment? {
        return fileSystem.readSymbolicLinkUnchecked(asFragment())
    }

    /**
     * Create a hard link for the current path.
     * 
     * @param link the path of the new link
     * @throws IOException if there was an error executing [FileSystem.createHardLink]
     */
    @Throws(IOException::class)
    fun createHardLink(link: Path) {
        fileSystem.createHardLink(link.asFragment(), asFragment())
    }

    /**
     * Returns the canonical path for this path, by repeatedly replacing symbolic links with their
     * referents. Analogous to realpath(3).
     * 
     * @return the canonical path for this path
     * @throws IOException if any symbolic link could not be resolved, or other error occurred (for
     * example, the path does not exist)
     */
    @Throws(IOException::class)
    fun resolveSymbolicLinks(): Path? {
        return fileSystem.resolveSymbolicLinks(asFragment())
    }

    /**
     * Atomically renames the file denoted by the current path to the location "target", not following
     * symbolic links.
     * 
     * 
     * Files cannot be atomically renamed across devices; copying is required. Use [ ][FileSystemUtils.moveFile] instead.
     * 
     * 
     * A non-directory cannot be renamed into an existing directory, or vice-versa. A directory can
     * be renamed into an existing directory if and only if the latter is empty.
     * 
     * @throws FileNotFoundException if the file denoted by the current path does not exist, or the
     * parent directory of the target path does not exist
     * @throws IOException if the rename failed for any other reason
     */
    @Throws(IOException::class)
    fun renameTo(target: Path) {
        checkSameFileSystem(target)
        fileSystem.renameTo(asFragment(), target.asFragment())
    }

    @get:Throws(IOException::class, FileNotFoundException::class)
    val fileSize: Long
        /**
         * Returns the size in bytes of the file denoted by the current path, following symbolic links.
         * 
         * 
         * The size of a directory or special file is undefined and should not be used.
         * 
         * @throws FileNotFoundException if the file denoted by the current path does not exist
         * @throws IOException if the file's metadata could not be read, or some other error occurred
         */
        get() = fileSystem.getFileSize(asFragment(), true)

    /**
     * Returns the size in bytes of the file denoted by the current path.
     * 
     * 
     * The size of directory or special file is undefined. The size of a symbolic link is the
     * length of the name of its referent.
     * 
     * @param followSymlinks if [Symlinks.FOLLOW], and this path denotes a symbolic link, the
     * link is deferenced until a file other than a symbol link is found
     * @throws FileNotFoundException if the file denoted by the current path does not exist
     * @throws IOException if the file's metadata could not be read, or some other error occurred
     */
    @Throws(IOException::class, FileNotFoundException::class)
    fun getFileSize(followSymlinks: Symlinks): Long {
        return fileSystem.getFileSize(asFragment(), followSymlinks.toBoolean())
    }

    /**
     * Deletes the file denoted by this path, not following symbolic links. Returns normally iff the
     * file doesn't exist after the call: true if this call deleted the file, false if the file
     * already didn't exist. Throws an exception if the file could not be deleted but was present
     * prior to this call.
     * 
     * @return true iff the file was actually deleted by this call
     * @throws IOException if the deletion failed but the file was present prior to the call
     */
    @Throws(IOException::class)
    fun delete(): Boolean {
        return fileSystem.delete(asFragment())
    }

    /**
     * Deletes all directory trees recursively beneath this path and removes the path as well.
     * 
     * @throws IOException if the hierarchy cannot be removed successfully
     */
    @Throws(IOException::class)
    fun deleteTree() {
        fileSystem.deleteTree(asFragment())
    }

    /**
     * Deletes all directory trees recursively beneath this path. Does nothing if the path is not a
     * directory.
     * 
     * @throws IOException if the hierarchy cannot be removed successfully
     */
    @Throws(IOException::class)
    fun deleteTreesBelow() {
        fileSystem.deleteTreesBelow(asFragment())
    }

    @get:Throws(IOException::class)
    @set:Throws(IOException::class)
    var lastModifiedTime: Long
        /**
         * Returns the last modification time of the file, in milliseconds since the UNIX epoch, of the
         * file denoted by the current path, following symbolic links.
         * 
         * 
         * Caveat: many filesystems store file times in seconds, so do not rely on the millisecond
         * precision.
         * 
         * @throws IOException if the operation failed for any reason
         */
        get() = fileSystem.getLastModifiedTime(asFragment(), true)
        /**
         * Sets the modification time of the file denoted by the current path. Follows symbolic links. If
         * newTime is [.NOW_SENTINEL_TIME], the current time according to the kernel is used; this
         * may differ from the JVM's clock.
         * 
         * 
         * Caveat: many filesystems store file times in seconds, so do not rely on the millisecond
         * precision.
         * 
         * @param newTime time, in milliseconds since the UNIX epoch, or [.NOW_SENTINEL_TIME],
         * meaning use the kernel's current time
         * @throws IOException if the modification time for the file could not be set for any reason
         */
        set(newTime) {
            fileSystem.setLastModifiedTime(asFragment(), newTime)
        }

    /**
     * Returns the last modification time of the file, in milliseconds since the UNIX epoch, of the
     * file denoted by the current path.
     * 
     * 
     * Caveat: many filesystems store file times in seconds, so do not rely on the millisecond
     * precision.
     * 
     * @param followSymlinks if [Symlinks.FOLLOW], and this path denotes a symbolic link, the
     * link is dereferenced until a file other than a symbolic link is found
     * @throws IOException if the modification time for the file could not be obtained for any reason
     */
    @Throws(IOException::class)
    fun getLastModifiedTime(followSymlinks: Symlinks): Long {
        return fileSystem.getLastModifiedTime(asFragment(), followSymlinks.toBoolean())
    }

    /**
     * Returns the value of the given extended attribute name or null if the attribute does not exist
     * or the file system does not support extended attributes.
     * 
     * @param followSymlinks whether to follow symlinks or not
     */
    /**
     * Returns the value of the given extended attribute name or null if the attribute does not exist
     * or the file system does not support extended attributes. Follows symlinks.
     */
    @kotlin.jvm.JvmOverloads
    @Throws(IOException::class)
    fun getxattr(name: String?, followSymlinks: Symlinks = Symlinks.FOLLOW): ByteArray? {
        return fileSystem.getxattr(asFragment(), name, followSymlinks.toBoolean())
    }

    @get:Throws(IOException::class)
    val fastDigest: ByteArray?
        /**
         * Gets a fast digest for the given path, or `null` if there isn't one available. The digest
         * should be suitable for detecting changes to the file.
         */
        get() = fileSystem.getFastDigest(asFragment())

    @get:Throws(IOException::class)
    val digest: ByteArray?
        /**
         * Returns the digest of the file denoted by the current path, following symbolic links. Is not
         * guaranteed to call [.getFastDigest] internally, even if a fast digest is likely
         * available. Callers should prefer [DigestUtils.getDigestWithManualFallback] to this method
         * unless they know that a fast digest is unavailable and do not need the other features
         * (disk-read rate-limiting, global cache) that [DigestUtils] provides.
         * 
         * @return a new byte array containing the file's digest
         * @throws IOException if the digest could not be computed for any reason
         */
        get() = fileSystem.getDigest(asFragment())

    @get:Throws(IOException::class)
    val inputStream: java.io.InputStream?
        /**
         * Opens the file denoted by this path, following symbolic links, for reading, and returns an
         * input stream to it.
         * 
         * @throws IOException if the file was not found or could not be opened for reading
         */
        get() = fileSystem.getInputStream(asFragment())

    /**
     * Opens the file denoted by this path, following symbolic links, for reading and writing and
     * returns a file channel for it.
     * 
     * 
     * Truncates the file, therefore it cannot be used to read already existing files.
     */
    @Throws(IOException::class)
    fun createReadWriteByteChannel(): SeekableByteChannel? {
        return fileSystem.createReadWriteByteChannel(asFragment())
    }

    val pathFile: java.io.File
        /**
         * Returns a java.io.File representation of this path.
         * 
         * 
         * Caveat: the result may be useless if this path's getFileSystem() is not the UNIX filesystem.
         */
        get() = java.io.File(StringEncoding.internalToPlatform(this.pathString))

    @get:Throws(IOException::class)
    @set:Throws(IOException::class, FileNotFoundException::class)
    var isWritable: Boolean
        /**
         * Returns true if the file denoted by the current path, following symbolic links, is writable for
         * the current user.
         * 
         * @throws FileNotFoundException if the file does not exist, a dangling symbolic link was
         * encountered, or the file's metadata could not be read
         */
        get() = fileSystem.isWritable(asFragment())
        /**
         * Sets the write permissions of the file denoted by the current path, following symbolic links.
         * Permissions apply to the current user.
         * 
         * 
         * TODO(bazel-team): (2009) what about owner/group/others?
         * 
         * @param writable if true, the file is set to writable; otherwise the file is made non-writable
         * @throws FileNotFoundException if the file does not exist
         * @throws IOException If the action cannot be taken (ie. permissions)
         */
        set(writable) {
            fileSystem.setWritable(asFragment(), writable)
        }

    @get:Throws(IOException::class, FileNotFoundException::class)
    @set:Throws(IOException::class, FileNotFoundException::class)
    var isExecutable: Boolean
        /**
         * Returns true iff the file specified by the current path, following symbolic links, is
         * executable by the current user.
         * 
         * @throws FileNotFoundException if the file does not exist or a dangling symbolic link was
         * encountered
         * @throws IOException if some other I/O error occurred
         */
        get() = fileSystem.isExecutable(asFragment())
        /**
         * Sets the execute permission on the file specified by the current path, following symbolic
         * links. Permissions apply to the current user.
         * 
         * @throws FileNotFoundException if the file does not exist or a dangling symbolic link was
         * encountered
         * @throws IOException if the metadata change failed, for example because of permissions
         */
        set(executable) {
            fileSystem.setExecutable(asFragment(), executable)
        }

    @get:Throws(IOException::class, FileNotFoundException::class)
    @set:Throws(IOException::class, FileNotFoundException::class)
    var isReadable: Boolean
        /**
         * Returns true iff the file specified by the current path, following symbolic links, is readable
         * by the current user.
         * 
         * @throws FileNotFoundException if the file does not exist or a dangling symbolic link was
         * encountered
         * @throws IOException if some other I/O error occurred
         */
        get() = fileSystem.isReadable(asFragment())
        /**
         * Sets the read permissions of the file denoted by the current path, following symbolic links.
         * Permissions apply to the current user.
         * 
         * @param readable if true, the file is set to readable; otherwise the file is made non-readable
         * @throws FileNotFoundException if the file does not exist
         * @throws IOException If the action cannot be taken (ie. permissions)
         */
        set(readable) {
            fileSystem.setReadable(asFragment(), readable)
        }

    /**
     * Sets the permissions on the file specified by the current path, following symbolic links. If
     * permission changes on this path's [FileSystem] are slow (e.g. one syscall per change),
     * this method should aim to be faster than setting each permission individually. If this path's
     * [FileSystem] does not support group and others permissions, those bits will be ignored.
     * 
     * @throws FileNotFoundException if the file does not exist or a dangling symbolic link was
     * encountered
     * @throws IOException if the metadata change failed, for example because of permissions
     */
    @Throws(IOException::class)
    fun chmod(mode: Int) {
        fileSystem.chmod(asFragment(), mode)
    }

    fun prefetchPackageAsync(maxDirs: Int) {
        fileSystem.prefetchPackageAsync(asFragment(), maxDirs)
    }

    private fun checkSameFileSystem(that: Path) {
        require(this.fileSystem === that.fileSystem) {
            "Files are on different filesystems: %s (on %s), %s (on %s)"
                .formatted(this, this.fileSystem, that, that.fileSystem)
        }
    }

    companion object {
        @kotlin.jvm.JvmField
        val NOW_SENTINEL_TIME: Long = -1L

        /** Creates a local path that is specific to the host OS.  */
        fun create(path: String?, fileSystem: com.google.devtools.build.lib.vfs.FileSystem): Path {
            com.google.common.base.Preconditions.checkNotNull<String?>(path)
            return com.google.devtools.build.lib.vfs.Path.Companion.create(
                PathFragment.Companion.create(path),
                fileSystem
            )
        }

        @AutoCodec.Instantiator
        fun create(pathFragment: PathFragment, fileSystem: com.google.devtools.build.lib.vfs.FileSystem): Path {
            return com.google.devtools.build.lib.vfs.Path(pathFragment, fileSystem)
        }
    }
}
