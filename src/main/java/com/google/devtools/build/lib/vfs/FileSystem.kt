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

/** This interface models a file system.  */
@ThreadSafe
abstract class FileSystem(digestFunction: DigestHashFunction?) {
    private val digestFunction: DigestHashFunction

    fun getDigestFunction(): DigestHashFunction {
        return digestFunction
    }

    /** An exception thrown when attempting to resolve an ordinary file as a symlink.  */
    class NotASymlinkException : IOException {
        constructor(path: PathFragment) : super(path.getPathString() + " is not a symlink")

        constructor(path: PathFragment, cause: Throwable?) : super(path.getPathString() + " is not a symlink", cause)
    }

    val absoluteRoot: Root = AbsoluteRoot(this)

    /**
     * Returns an absolute path instance, given an absolute path name, without double slashes, .., or
     * . segments. While this method will normalize the path representation by creating a
     * structured/parsed representation, it will not cause any IO. (e.g., it will not resolve symbolic
     * links if it's a Unix file system.
     */
    fun getPath(path: String?): com.google.devtools.build.lib.vfs.Path {
        return com.google.devtools.build.lib.vfs.Path.Companion.create(path, this)
    }

    /** Returns an absolute path instance, given an absolute path fragment.  */
    fun getPath(pathFragment: PathFragment?): com.google.devtools.build.lib.vfs.Path {
        return com.google.devtools.build.lib.vfs.Path.Companion.create(pathFragment, this)
    }

    val hostFileSystem: FileSystem
        /**
         * Returns the file system that the current file system is based on, if any, otherwise returns
         * this.
         * 
         * 
         * For an action file system, this should return the on-disk component (or the result of
         * getHostFileSystem() on that component if it is itself a composite file system).
         * 
         * 
         * Note that the returned file system may still be an in-memory file system (in tests, for
         * example), but should be treated as the "native" file system for the host machine.
         */
        get() = this

    /**
     * Returns whether or not the FileSystem supports modifications of files and file entries.
     * 
     * 
     * Returns true if FileSystem supports the following:
     * 
     * 
     *  * [.setWritable]
     *  * [.setExecutable]
     * 
     * 
     * The above calls will result in an [UnsupportedOperationException] on a FileSystem where
     * this method returns `false`.
     */
    abstract fun supportsModifications(path: PathFragment?): Boolean

    /**
     * Returns whether or not the FileSystem supports symbolic links.
     * 
     * 
     * Returns true if FileSystem supports the following:
     * 
     * 
     *  * [.createSymbolicLink]
     *  * [.getFileSize] where `followSymlinks=false`
     *  * [.getLastModifiedTime] where `followSymlinks=false`
     *  * [.readSymbolicLink] where the link points to a non-existent file
     * 
     * 
     * The above calls may result in an [UnsupportedOperationException] on a FileSystem where
     * this method returns `false`. The implementation can try to emulate these calls at its own
     * discretion.
     */
    abstract fun supportsSymbolicLinksNatively(path: PathFragment?): Boolean

    /**
     * Returns whether or not the FileSystem supports hard links.
     * 
     * 
     * Returns true if FileSystem supports the following:
     * 
     * 
     *  * [.createFSDependentHardLink]
     * 
     * 
     * The above calls may result in an [UnsupportedOperationException] on a FileSystem where
     * this method returns `false`. The implementation can try to emulate these calls at its own
     * discretion.
     */
    abstract fun supportsHardLinksNatively(path: PathFragment?): Boolean

    /***
     * Returns true if file paths that differ as raw byte strings may refer to the same file system
     * entry because of case insensitivity or Unicode normalization.
     * 
     * 
     * Note that common file systems on Windows and macOS that are case-insensitive by default
     * can be configured to be case-sensitive, possibly even on a per-directory basis. Since it is not
     * feasible for Bazel to detect this, these file systems must still return true.
     */
    abstract fun mayBeCaseOrNormalizationInsensitive(): Boolean

    /**
     * Returns the type of the file system path belongs to.
     * 
     * 
     * The string returned is obtained directly from the operating system, so it's a best guess in
     * absence of a guaranteed api.
     * 
     * 
     * This implementation uses `/proc/mounts` to determine the file system type.
     */
    open fun getFileSystemType(path: PathFragment): String? {
        var fileSystem: String? = "unknown"
        var bestMountPointSegmentCount = -1
        try {
            val canonicalPath: com.google.devtools.build.lib.vfs.Path = resolveSymbolicLinks(path)
            val mountTable: PathFragment? = PathFragment.Companion.createAlreadyNormalized("/proc/mounts")
            java.io.InputStreamReader(getInputStream(mountTable), java.nio.charset.StandardCharsets.ISO_8859_1)
                .use { reader ->
                    for (line in com.google.common.io.CharStreams.readLines(reader)) {
                        val words: Array<String?> = line.split("\\s+")
                        if (words.size >= 3) {
                            if (!words[1].startsWith("/")) {
                                continue
                            }
                            val mountPoint: PathFragment = PathFragment.Companion.create(words[1])
                            val segmentCount: Int = mountPoint.segmentCount()
                            if (canonicalPath.startsWith(mountPoint) && segmentCount > bestMountPointSegmentCount) {
                                bestMountPointSegmentCount = segmentCount
                                fileSystem = words[2]
                            }
                        }
                    }
                }
        } catch (e: IOException) {
            // pass
        }
        return fileSystem
    }

    /**
     * Creates a directory with the name of the current path. See [Path.createDirectory] for
     * specification.
     */
    @Throws(IOException::class)
    abstract fun createDirectory(path: PathFragment?): Boolean

    /**
     * Creates all directories up to the path. See [Path.createDirectoryAndParents] for
     * specification.
     */
    @Throws(IOException::class)
    abstract fun createDirectoryAndParents(path: PathFragment?)

    /**
     * Returns the size in bytes of the file denoted by `path`. See [ ][Path.getFileSize] for specification.
     * 
     * 
     * Note: for <@link FileSystem>s where [.supportsSymbolicLinksNatively]
     * returns false, this method will throw an [UnsupportedOperationException] if `followSymLinks=false`.
     */
    @Throws(IOException::class)
    abstract fun getFileSize(path: PathFragment?, followSymlinks: Boolean): Long

    /** Deletes the file denoted by `path`. See [Path.delete] for specification.  */
    @Throws(IOException::class)
    abstract fun delete(path: PathFragment?): Boolean

    /**
     * Deletes all directory trees recursively beneath the given path and removes that path as well.
     * 
     * @param path the directory hierarchy to remove
     * @throws IOException if the hierarchy cannot be removed successfully
     */
    @Throws(IOException::class)
    open fun deleteTree(path: PathFragment) {
        deleteTreesBelow(path)
        delete(path)
    }

    /**
     * Deletes all directory trees recursively beneath the given path. Does nothing if the given path
     * is not a directory.
     * 
     * 
     * This generic implementation is not as efficient as it could be: for example, we issue
     * separate stats for each directory entry to determine if they are directories or not (instead of
     * reusing the information that readdir returns), and we issue separate operations to toggle
     * different permissions while they could be done at once via chmod. Subclasses can optimize this
     * by taking advantage of platform-specific features.
     * 
     * @param dir the directory hierarchy to remove
     * @throws IOException if the hierarchy cannot be removed successfully
     */
    @Throws(IOException::class)
    open fun deleteTreesBelow(dir: PathFragment) {
        if (isDirectory(dir,  /* followSymlinks= */false)) {
            var entries: MutableCollection<String?>
            try {
                entries = getDirectoryEntries(dir)
            } catch (e: IOException) {
                // If we couldn't read the directory, it may be because it's not readable. Try granting this
                // permission and retry. If the retry fails, give up.
                setReadable(dir, true)
                setExecutable(dir, true)
                entries = getDirectoryEntries(dir)
            }

            val iterator = entries.iterator()
            if (iterator.hasNext()) {
                val first: PathFragment = dir.getChild(iterator.next())
                deleteTreesBelow(first)
                try {
                    // If the directory is not executable, delete(), depending on implementation, may decide
                    // that the directory entry does not exist and return false without throwing.
                    if (!delete(first)) {
                        throw IOException(
                            "Unable to delete \"" + first + "\": directory entry does not exist"
                        )
                    }
                } catch (e: IOException) {
                    // If we couldn't delete the first entry in a directory, it may be because the directory
                    // (not the entry!) is not writable or executable. Try granting this permission and retry.
                    // If the retry fails, give up. Note that we have to retry deleteTreesBelow() too in case
                    // first is itself a directory; if the directory were not executable, the initial
                    // first.deleteTreesBelow() call would have been a silent no-op (since first.isDirectory()
                    // would have returned false) and sub-entries of first would not have been deleted.
                    setWritable(dir, true)
                    setExecutable(dir, true)
                    deleteTreesBelow(first)
                    delete(first)
                }
            }
            while (iterator.hasNext()) {
                val path: PathFragment = dir.getChild(iterator.next())
                deleteTreesBelow(path)
                // No need to retry here: if needed, we already unprotected the directory earlier.
                delete(path)
            }
        }
    }

    /**
     * Returns the last modification time of the file denoted by `path`. See [ ][Path.getLastModifiedTime] for specification.
     * 
     * 
     * Note: for [FileSystem]s where [.supportsSymbolicLinksNatively]
     * returns false, this method will throw an [UnsupportedOperationException] if `followSymLinks=false`.
     */
    @Throws(IOException::class)
    abstract fun getLastModifiedTime(path: PathFragment?, followSymlinks: Boolean): Long

    /**
     * Sets the last modification time of the file denoted by `path`. See [ ][Path.setLastModifiedTime] for specification.
     */
    @Throws(IOException::class)
    abstract fun setLastModifiedTime(path: PathFragment?, newTime: Long)

    /**
     * Returns value of the given extended attribute name or null if attribute does not exist or file
     * system does not support extended attributes.
     * 
     * 
     * Default implementation assumes that file system does not support extended attributes and
     * always returns null. Specific file system implementations should override this method if they
     * do provide support for extended attributes.
     * 
     * @param path the file whose extended attribute is to be returned.
     * @param name the name of the extended attribute key.
     * @param followSymlinks whether to follow symlinks or not; if false, returns the xattr of the
     * link itself, not its target.
     * @return the value of the extended attribute associated with 'path', if any, or null if no such
     * attribute is defined (ENODATA) or file system does not support extended attributes at all.
     * @throws IOException if the call failed for any other reason.
     */
    @Throws(IOException::class)
    open fun getxattr(path: PathFragment?, name: String?, followSymlinks: Boolean): ByteArray? {
        return null
    }

    /**
     * Gets a fast digest for the given path, or `null` if there isn't one available or the
     * filesystem doesn't support them. This digest should be suitable for detecting changes to the
     * file.
     */
    @Throws(IOException::class)
    open fun getFastDigest(path: PathFragment?): ByteArray? {
        return null
    }

    /**
     * Returns the digest of the file denoted by the path, following symbolic links.
     * 
     * 
     * Subclasses may (and do) optimize this computation for a particular digest functions.
     * 
     * @return a new byte array containing the file's digest
     * @throws IOException if the digest could not be computed for any reason
     */
    @Throws(IOException::class)
    open fun getDigest(path: PathFragment?): ByteArray? {
        return object : com.google.common.io.ByteSource() {
            @Throws(IOException::class)
            override fun openStream(): java.io.InputStream {
                return getInputStream(path)
            }
        }.hash(digestFunction.getHashFunction()).asBytes()
    }

    /**
     * Appends a single regular path segment 'child' to 'dir', recursively resolving symbolic links in
     * 'child'. 'dir' must be canonical. 'maxLinks' is the maximum number of symbolic links that may
     * be traversed before it gives up.
     * 
     * 
     * (This method does not need to be synchronized; but the result may be stale in the case of
     * concurrent modification.)
     * 
     * @throws IOException if 'dir' is not an existing directory; or if stat(child) fails for any
     * reason, or if 'child' is a symlink and readlink(child) fails for any reason (e.g. ENOENT,
     * EACCES), or if the chain of symbolic links exceeds 'maxLinks'.
     */
    @Throws(IOException::class)
    protected fun appendSegment(dir: PathFragment, child: String?, maxLinks: Int): PathFragment {
        var dir: PathFragment = dir
        var maxLinks = maxLinks
        val naive: PathFragment = dir.getChild(child)

        val linkTarget: PathFragment? = resolveOneLink(naive)
        if (linkTarget == null) {
            return naive // regular file or directory
        }

        if (maxLinks-- == 0) {
            throw FileSymlinkLoopException(naive.getPathString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_TOO_MANY_SYMLINKS)
        }
        if (linkTarget.isAbsolute()) {
            dir = PathFragment.Companion.createAlreadyNormalized(linkTarget.getDriveStr())
        }
        for (name in linkTarget.segments()) {
            if (name == "." || name.isEmpty()) {
                // no-op
            } else if (name == "..") {
                val parent: PathFragment? = dir.getParentDirectory()
                // root's parent is root, when canonicalizing, so this is a no-op.
                if (parent != null) {
                    dir = parent
                }
            } else {
                dir = appendSegment(dir, name, maxLinks)
            }
        }
        return dir
    }

    /**
     * Helper method of [.resolveSymbolicLinks]. This method encapsulates the I/O
     * component of a full canonicalization operation. Subclasses can (and do) provide more efficient
     * implementations.
     * 
     * 
     * (This method does not need to be synchronized; but the result may be stale in the case of
     * concurrent modification.)
     * 
     * @param path a path, of which all but the last segment is guaranteed to be canonical
     * @return [.readSymbolicLink] iff path is a symlink or null iff path exists but is not a
     * symlink
     * @throws IOException if the file did not exist, or a parent directory could not be searched
     */
    @Throws(IOException::class)
    open fun resolveOneLink(path: PathFragment?): PathFragment? {
        try {
            return readSymbolicLink(path)
        } catch (e: NotASymlinkException) {
            // Not a symbolic link.  Check it exists.

            // (A simple call to lstat would replace all of this.)

            if (!exists(path, false)) {
                throw FileNotFoundException(path.toString() + " (No such file or directory)")
            }

            // TODO(bazel-team): (2009) ideally, throw ENOTDIR if dir is not a dir, but that
            // would require twice as many stats, or a much more convoluted
            // implementation (like glibc's canonicalize.c).
            return null //  exists.
        }
    }

    /**
     * Returns the canonical path for the given path, which must be absolute. See [ ][Path.resolveSymbolicLinks] for specification.
     */
    @Throws(IOException::class)
    open fun resolveSymbolicLinks(path: PathFragment): com.google.devtools.build.lib.vfs.Path {
        com.google.common.base.Preconditions.checkArgument(path.isAbsolute())
        val parentNode: PathFragment? = path.getParentDirectory()
        return if (parentNode == null)
            getPath(path) // (root)
        else
            getPath(
                appendSegment(
                    resolveSymbolicLinks(parentNode).asFragment(),
                    path.getBaseName(),
                    com.google.devtools.build.lib.vfs.FileSystem.Companion.MAX_SYMLINKS
                )
            )
    }

    /** Returns the status of a file. See [Path.stat] for specification.  */
    @Throws(IOException::class)
    abstract fun stat(path: PathFragment?, followSymlinks: Boolean): FileStatus?

    /** Like stat(), but returns null on failures instead of throwing.  */
    open fun statNullable(path: PathFragment?, followSymlinks: Boolean): FileStatus? {
        try {
            return stat(path, followSymlinks)
        } catch (e: IOException) {
            return null
        }
    }

    /**
     * Like [.stat], but returns null if the file is not found (corresponding to `ENOENT`
     * or `ENOTDIR` in Unix's stat(2) function) instead of throwing. Note that this
     * implementation does *not* successfully catch `ENOTDIR` exceptions. If the
     * instantiated filesystem can catch such errors, it should override this method to do so.
     */
    @Throws(IOException::class)
    open fun statIfFound(path: PathFragment?, followSymlinks: Boolean): FileStatus? {
        try {
            return stat(path, followSymlinks)
        } catch (e: FileNotFoundException) {
            return null
        }
    }

    /**
     * Returns true iff `path` denotes an existing regular or special file. See [ ][Path.isFile] for specification.
     */
    open fun isFile(path: PathFragment?, followSymlinks: Boolean): Boolean {
        val stat: FileStatus? = statNullable(path, followSymlinks)
        return stat != null && stat.isFile()
    }

    /**
     * Returns true iff `path` denotes an existing special file. See [ ][Path.isSpecialFile] for specification.
     */
    open fun isSpecialFile(path: PathFragment?, followSymlinks: Boolean): Boolean {
        val stat: FileStatus? = statNullable(path, followSymlinks)
        return stat != null && stat.isSpecialFile()
    }

    /**
     * Returns true iff `path` denotes an existing symbolic link. See [ ][Path.isSymbolicLink] for specification.
     */
    open fun isSymbolicLink(path: PathFragment?): Boolean {
        val stat: FileStatus? = statNullable(path, false)
        return stat != null && stat.isSymbolicLink()
    }

    /**
     * Returns true iff `path` denotes an existing directory. See [ ][Path.isDirectory] for specification.
     */
    open fun isDirectory(path: PathFragment?, followSymlinks: Boolean): Boolean {
        val stat: FileStatus? = statNullable(path, followSymlinks)
        return stat != null && stat.isDirectory()
    }

    /**
     * Creates a symbolic link. See [Path.createSymbolicLink] for
     * specification.
     * 
     * 
     * Note: for [FileSystem]s where [.supportsSymbolicLinksNatively]
     * returns false, this method will throw an [UnsupportedOperationException]
     */
    @Throws(IOException::class)
    abstract fun createSymbolicLink(
        linkPath: PathFragment?, targetFragment: PathFragment?, hint: SymlinkTargetType?
    )

    /**
     * Creates a symbolic link. See [Path.createSymbolicLink] for specification.
     * 
     * 
     * Note: for [FileSystem]s where [.supportsSymbolicLinksNatively]
     * returns false, this method will throw an [UnsupportedOperationException]
     */
    @Throws(IOException::class)
    fun createSymbolicLink(linkPath: PathFragment?, targetFragment: PathFragment?) {
        createSymbolicLink(linkPath, targetFragment, SymlinkTargetType.UNSPECIFIED)
    }

    /**
     * Returns the target of a symbolic link. See [Path.readSymbolicLink] for specification.
     * 
     * 
     * Note: for [FileSystem]s where [.supportsSymbolicLinksNatively]
     * returns false, this method will throw an [UnsupportedOperationException] if the link
     * points to a non-existent file.
     * 
     * @throws NotASymlinkException if the current path is not a symbolic link
     * @throws IOException if the contents of the link could not be read for any reason.
     */
    @Throws(IOException::class)
    abstract fun readSymbolicLink(path: PathFragment?): PathFragment?

    /**
     * Returns the target of a symbolic link, under the assumption that the given path is indeed a
     * symbolic link (this assumption permits efficient implementations). See [ ][Path.readSymbolicLinkUnchecked] for specification.
     * 
     * @throws IOException if the contents of the link could not be read for any reason.
     */
    @Throws(IOException::class)
    open fun readSymbolicLinkUnchecked(path: PathFragment?): PathFragment? {
        return readSymbolicLink(path)
    }

    /** Returns true iff this path denotes an existing file of any kind. Follows symbolic links.  */
    open fun exists(path: PathFragment?): Boolean {
        return exists(path, true)
    }

    /**
     * Returns true iff `path` denotes an existing file of any kind. See [ ][Path.exists] for specification.
     */
    abstract fun exists(path: PathFragment?, followSymlinks: Boolean): Boolean

    /**
     * Returns a collection containing the names of all entities within the directory denoted by the
     * `path`. Symlinks are followed when resolving the directory whose entries are to be read.
     * 
     * @throws IOException if there was an error reading the directory entries
     */
    @Throws(IOException::class)
    abstract fun getDirectoryEntries(path: PathFragment?): MutableCollection<String?>

    /**
     * Returns a Dirents structure, listing the names of all entries within the directory `path`, plus their types (file, directory, other).
     * 
     * @param followSymlinks whether to follow symlinks when determining the file types of individual
     * directory entries. No matter the value of this parameter, symlinks are followed when
     * resolving the directory whose entries are to be read.
     * @throws IOException if there was an error reading the directory entries
     */
    @Throws(IOException::class)
    open fun readdir(
        path: PathFragment,
        followSymlinks: Boolean
    ): MutableCollection<com.google.devtools.build.lib.vfs.Dirent?> {
        val children = getDirectoryEntries(path)
        val dirents: MutableList<com.google.devtools.build.lib.vfs.Dirent?> =
            com.google.common.collect.Lists.newArrayListWithCapacity<com.google.devtools.build.lib.vfs.Dirent?>(children.size())
        for (child in children) {
            val childPath: PathFragment = path.getChild(child)
            val type: com.google.devtools.build.lib.vfs.Dirent.Type =
                com.google.devtools.build.lib.vfs.FileSystem.Companion.direntFromStat(
                    statNullable(
                        childPath,
                        followSymlinks
                    )
                )
            dirents.add(com.google.devtools.build.lib.vfs.Dirent(child, type))
        }
        return dirents
    }

    /**
     * Returns true iff the file represented by `path` is readable.
     * 
     * @throws IOException if there was an error reading the file's metadata
     */
    @Throws(IOException::class)
    abstract fun isReadable(path: PathFragment?): Boolean

    /**
     * Sets the file to readable (if the argument is true) or non-readable (if the argument is false)
     * 
     * 
     * Note: for [FileSystem]s where [.supportsModifications] returns
     * false or which do not support unreadable files, this method will throw an [ ].
     * 
     * @throws IOException if there was an error reading or writing the file's metadata
     */
    @Throws(IOException::class)
    abstract fun setReadable(path: PathFragment?, readable: Boolean)

    /**
     * Returns true iff the file represented by `path` is writable.
     * 
     * @throws IOException if there was an error reading the file's metadata
     */
    @Throws(IOException::class)
    abstract fun isWritable(path: PathFragment?): Boolean

    /**
     * Sets the file to writable (if the argument is true) or non-writable (if the argument is false)
     * 
     * 
     * Note: for [FileSystem]s where [.supportsModifications] returns
     * false, this method will throw an [UnsupportedOperationException].
     * 
     * @throws IOException if there was an error reading or writing the file's metadata
     */
    @Throws(IOException::class)
    abstract fun setWritable(path: PathFragment?, writable: Boolean)

    /**
     * Returns true iff the file represented by the path is executable.
     * 
     * @throws IOException if there was an error reading the file's metadata
     */
    @Throws(IOException::class)
    abstract fun isExecutable(path: PathFragment?): Boolean

    /**
     * Sets the file to executable, if the argument is true. It is currently not supported to unset
     * the executable status of a file, so {code executable=false} yields an [ ].
     * 
     * 
     * Note: for [FileSystem]s where [.supportsModifications] returns
     * false, this method will throw an [UnsupportedOperationException].
     * 
     * @throws IOException if there was an error reading or writing the file's metadata
     */
    @Throws(IOException::class)
    abstract fun setExecutable(path: PathFragment?, executable: Boolean)

    /**
     * Sets the file permissions. If permission changes on this [FileSystem] are slow (e.g. one
     * syscall per change), this method should aim to be faster than setting each permission
     * individually. If this [FileSystem] does not support group or others permissions, those
     * bits will be ignored.
     * 
     * 
     * Note: for [FileSystem]s where [.supportsModifications] returns
     * false, this method will throw an [UnsupportedOperationException].
     * 
     * @throws IOException if there was an error reading or writing the file's metadata
     */
    @Throws(IOException::class)
    open fun chmod(path: PathFragment?, mode: Int) {
        setReadable(path, (mode and 256) != 0)
        setWritable(path, (mode and 128) != 0)
        setExecutable(path, (mode and 64) != 0)
    }

    /**
     * Creates an [InputStream].
     * 
     * @param path the path to open
     * @throws FileNotFoundException if the file does not exist
     * @throws IOException if there was an error opening the file for reading
     */
    @Throws(IOException::class)
    abstract fun getInputStream(path: PathFragment?): java.io.InputStream

    /**
     * Creates an [OutputStream].
     * 
     * @param path the path to open
     * @throws IOException if there was an error opening the file for writing
     */
    @Throws(IOException::class)
    protected fun getOutputStream(path: PathFragment?): java.io.OutputStream? {
        return getOutputStream(path,  /* append= */false)
    }

    /**
     * Creates an [OutputStream].
     * 
     * @param path the path to open
     * @param append whether to open the file in append mode
     * @throws IOException if there was an error opening the file for writing
     */
    @Throws(IOException::class)
    fun getOutputStream(path: PathFragment?, append: Boolean): java.io.OutputStream? {
        return getOutputStream(path, append,  /* internal= */false)
    }

    /**
     * Creates an [OutputStream].
     * 
     * @param path the path to open
     * @param append whether to open the file in append mode
     * @param internal whether the file is an internal file whose I/O should not be profiled
     * @throws IOException if there was an error opening the file for writing
     */
    @Throws(IOException::class)
    abstract fun getOutputStream(path: PathFragment?, append: Boolean, internal: Boolean): java.io.OutputStream?

    /**
     * Creates a [SeekableByteChannel], truncating the file if it already exists.
     * 
     * @param path the path to open
     * @throws IOException if there was an error opening the file for reading
     */
    @Throws(IOException::class)
    abstract fun createReadWriteByteChannel(path: PathFragment?): SeekableByteChannel?

    /**
     * Renames the file denoted by "sourceNode" to the location "targetNode". See [ ][Path.renameTo] for specification.
     * 
     * 
     * Implementations must be atomic.
     */
    @Throws(IOException::class)
    abstract fun renameTo(sourcePath: PathFragment?, targetPath: PathFragment?)

    /**
     * Create a new hard link file at "linkPath" for file at "originalPath".
     * 
     * @param linkPath The path of the new link file to be created
     * @param originalPath The path of the original file
     * @throws IOException if the original file does not exist or the link file already exists
     */
    @Throws(IOException::class)
    open fun createHardLink(linkPath: PathFragment, originalPath: PathFragment) {
        if (!exists(originalPath)) {
            throw FileNotFoundException(
                ("File \""
                        + originalPath.getBaseName()
                        + "\" linked from \""
                        + linkPath.getBaseName()
                        + "\" does not exist")
            )
        }

        if (exists(linkPath)) {
            throw FileAlreadyExistsException(
                "New link file \"" + linkPath.getBaseName() + "\" already exists"
            )
        }

        createFSDependentHardLink(linkPath, originalPath)
    }

    /**
     * Create a new hard link file at "linkPath" for file at "originalPath".
     * 
     * @param linkPath The path of the new link file to be created
     * @param originalPath The path of the original file
     * @throws IOException if there was an I/O error
     */
    @Throws(IOException::class)
    abstract fun createFSDependentHardLink(linkPath: PathFragment?, originalPath: PathFragment?)

    /**
     * Prefetch all directories and symlinks within the package rooted at "path". Enter at most
     * "maxDirs" total directories. Specializations for high-latency remote filesystems may wish to
     * implement this in order to warm the filesystem's internal caches.
     */
    open fun prefetchPackageAsync(path: PathFragment?, maxDirs: Int) {}

    /**
     * Returns a [File] object for the given path or null if this file system implementation is
     * not backed by the local file system.
     */
    open fun getIoFile(path: PathFragment?): java.io.File? {
        return null
    }

    /**
     * Returns a [java.nio.file.Path] object for the given path or null if this file system
     * implementation is not backed by the local file system.
     */
    open fun getNioPath(path: PathFragment?): java.nio.file.Path? {
        return null
    }

    init {
        this.digestFunction = com.google.common.base.Preconditions.checkNotNull<DigestHashFunction>(digestFunction)
    }

    /**
     * Returns the path of a new temporary directory with the given prefix created under the given
     * parent path, but **not** necessarily with secure permissions.
     */
    @Throws(IOException::class)
    open fun createTempDirectory(parent: PathFragment, prefix: String?): PathFragment? {
        val rand: java.security.SecureRandom = java.security.SecureRandom()
        while (true) {
            val candidate: PathFragment? = parent.getRelative(prefix + java.lang.Long.toUnsignedString(rand.nextLong()))
            if (createDirectory(candidate)) {
                chmod(candidate, 448)
                return candidate
            }
        }
    }

    companion object {
        // The maximum number of symbolic links that may be traversed by resolveSymbolicLinks() while
        // canonicalizing a path before it gives up and throws a FileSymlinkLoopException.
        const val MAX_SYMLINKS: Int = 32

        // Standard error message suffixes to be used for consistency across different FileSystem
        // implementations.
        protected const val ERR_DIRECTORY_NOT_EMPTY: String = " (Directory not empty)"
        protected const val ERR_FILE_EXISTS: String = " (File exists)"
        protected const val ERR_IS_DIRECTORY: String = " (Is a directory)"
        protected const val ERR_NOT_A_DIRECTORY: String = " (Not a directory)"
        protected const val ERR_NO_SUCH_FILE_OR_DIR: String = " (No such file or directory)"
        protected const val ERR_PERMISSION_DENIED: String = " (Permission denied)"
        const val ERR_TOO_MANY_SYMLINKS: String = " (Too many levels of symbolic links)"

        protected fun direntFromStat(stat: FileStatus?): com.google.devtools.build.lib.vfs.Dirent.Type {
            if (stat == null) {
                return com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN
            } else if (stat.isSpecialFile()) {
                return com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN
            } else if (stat.isFile()) {
                return com.google.devtools.build.lib.vfs.Dirent.Type.FILE
            } else if (stat.isDirectory()) {
                return com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY
            } else if (stat.isSymbolicLink()) {
                return com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK
            } else {
                return com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN
            }
        }

        // Mapping from FileSystemException reason strings on various platforms to the corresponding Unix
        // error message that Bazel's own filesystem implementations produce. Bazel forces the root locale
        // for the JVM, so the error messages should be stable per OS.
        // This map is best-effort and almost certainly incomplete, especially on Windows.
        private val reasonToUnixError: MutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>( // Unix
                "Is a directory",
                com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_IS_DIRECTORY,
                "Not a directory",
                com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_NOT_A_DIRECTORY,
                "Directory not empty",
                com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_DIRECTORY_NOT_EMPTY,  // Windows
                // https://github.com/bazelbuild/bazel/pull/27458#discussion_r2478544279
                // https://learn.microsoft.com/en-us/windows/win32/debug/system-error-codes--0-499-
                "The directory is not empty.",
                com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_DIRECTORY_NOT_EMPTY
            )

        /**
         * Translates common java.nio.file IOExceptions into the equivalent java.io IOExceptions with
         * consistent error messages.
         */
        fun translateNioToIoException(path: PathFragment, e: IOException?): IOException? {
            if (e !is FileSystemException) {
                return e
            }
            var prefix: String? = ""
            if (e.getFile() != null) {
                prefix = e.getFile()
            }
            if (e.getOtherFile() != null) {
                prefix += " -> " + e.getOtherFile()
            }
            return when (e) {
                -> {
                    val newException: FileAccessException =
                        FileAccessException(prefix + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_PERMISSION_DENIED)
                    newException.initCause(e)
                    newException
                }

                -> IOException(
                    prefix + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_DIRECTORY_NOT_EMPTY,
                    e
                )

                -> IOException(prefix + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_FILE_EXISTS, e)
                -> {
                    val newException: FileNotFoundException =
                        FileNotFoundException(prefix + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_NO_SUCH_FILE_OR_DIR)
                    newException.initCause(e)
                    newException
                }

                -> IOException(prefix + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_NOT_A_DIRECTORY, e)
                -> NotASymlinkException(path, e)
                -> {
                    val unixError: String? =
                        com.google.devtools.build.lib.vfs.FileSystem.Companion.reasonToUnixError.get(fse.getReason())
                    if (unixError != null) IOException(prefix + unixError, e) else e
                }

                else -> e
            }
        }
    }
}
