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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.concurrent.ThreadSafety.ConditionallyThreadSafe

/** Helper functions that implement often-used complex operations on file systems.  */
@ConditionallyThreadSafe
object FileSystemUtils {
    /**
     * Throws exceptions if `baseName` is not a valid base name. A valid
     * base name:
     * 
     *  * Is not null
     *  * Is not an empty string
     *  * Is not "." or ".."
     *  * Does not contain a slash
     * 
     */
    @ThreadSafe
    fun checkBaseName(baseName: String) {
        require(baseName.length() != 0) { "Child must not be empty string ('')" }
        require(!(baseName == "." || baseName == "..")) { "baseName must not be '" + baseName + "'" }
        require(baseName.indexOf('/'.code) == -1) { "baseName must not contain a slash: '" + baseName + "'" }
    }

    /**
     * Returns the common ancestor between two paths, or null if none (including
     * if they are on different filesystems).
     */
    fun commonAncestor(
        a: com.google.devtools.build.lib.vfs.Path?,
        b: com.google.devtools.build.lib.vfs.Path
    ): com.google.devtools.build.lib.vfs.Path? {
        var a: com.google.devtools.build.lib.vfs.Path? = a
        while (a != null && !b.startsWith(a)) {
            a = a.getParentDirectory() // returns null at root
        }
        return a
    }

    /**
     * Returns the longest common ancestor of the two path fragments, or either "/" or "" (depending
     * on whether `a` is absolute or relative) if there is none.
     */
    fun commonAncestor(a: PathFragment?, b: PathFragment): PathFragment? {
        var a: PathFragment? = a
        while (a != null && !b.startsWith(a)) {
            a = a.getParentDirectory()
        }

        return a
    }

    /**
     * Returns a path fragment from a given from-dir to a given to-path.
     */
    fun relativePath(fromDir: PathFragment?, to: PathFragment): PathFragment? {
        if (to == fromDir) {
            return PathFragment.Companion.EMPTY_FRAGMENT
        }
        if (to.startsWith(fromDir)) {
            return to.relativeTo(fromDir) // easy case--it's a descendant
        }
        val ancestor: PathFragment? = com.google.devtools.build.lib.vfs.FileSystemUtils.commonAncestor(fromDir, to)
        if (ancestor == null) {
            return to // no common ancestor, use 'to'
        }
        val levels: Int = fromDir.relativeTo(ancestor).segmentCount()
        val dotdots: java.lang.StringBuilder = java.lang.StringBuilder()
        for (i in 0..<levels) {
            dotdots.append("../")
        }
        return PathFragment.Companion.create(dotdots.toString()).getRelative(to.relativeTo(ancestor))
    }

    /**
     * Removes the shortest suffix beginning with '.' from the basename of the
     * filename string. If the basename contains no '.', the filename is returned
     * unchanged.
     * 
     * 
     * e.g. "foo/bar.x" -> "foo/bar"
     * 
     * 
     * Note that if the filename is composed entirely of ".", this method will return the string
     * with one fewer ".", which may have surprising effects.
     */
    @kotlin.jvm.JvmStatic
    @ThreadSafe
    fun removeExtension(filename: String): String {
        val lastDotIndex: Int = filename.lastIndexOf('.'.code)
        if (lastDotIndex == -1) {
            return filename
        }
        val lastSlashIndex: Int = filename.lastIndexOf('/'.code)
        if (lastSlashIndex > lastDotIndex) {
            return filename
        }
        return filename.substring(0, lastDotIndex)
    }

    /**
     * Removes the shortest suffix beginning with '.' from the basename of the
     * PathFragment. If the basename contains no '.', the filename is returned
     * unchanged.
     * 
     * 
     * e.g. "foo/bar.x" -> "foo/bar"
     * 
     * 
     * Note that if the base filename is composed entirely of ".", this method will return the
     * filename with one fewer "." in the base filename, which may have surprising effects.
     */
    @ThreadSafe
    fun removeExtension(path: PathFragment): PathFragment? {
        return path.replaceName(com.google.devtools.build.lib.vfs.FileSystemUtils.removeExtension(path.getBaseName()))
    }

    /**
     * Removes the shortest suffix beginning with '.' from the basename of the
     * Path. If the basename contains no '.', the filename is returned
     * unchanged.
     * 
     * 
     * e.g. "foo/bar.x" -> "foo/bar"
     * 
     * 
     * Note that if the base filename is composed entirely of ".", this method will return the
     * filename with one fewer "." in the base filename, which may have surprising effects.
     */
    @ThreadSafe
    fun removeExtension(path: com.google.devtools.build.lib.vfs.Path): com.google.devtools.build.lib.vfs.Path? {
        return path.getFileSystem()
            .getPath(com.google.devtools.build.lib.vfs.FileSystemUtils.removeExtension(path.asFragment()))
    }

    /**
     * Returns a new `PathFragment` formed by replacing the extension of the
     * last path segment of `path` with `newExtension`. Null is
     * returned iff `path` has zero segments.
     */
    fun replaceExtension(path: PathFragment, newExtension: String?): PathFragment? {
        return path.replaceName(com.google.devtools.build.lib.vfs.FileSystemUtils.removeExtension(path.getBaseName()) + newExtension)
    }

    /**
     * Returns a new `PathFragment` formed by replacing the extension of the last path segment
     * of `path` with `newExtension`. Null is returned iff `path` has zero segments
     * or it doesn't end with `oldExtension`.
     */
    fun replaceExtension(
        path: PathFragment, newExtension: String, oldExtension: String?
    ): PathFragment? {
        val base: String = path.getBaseName()
        if (!base.endsWith(oldExtension)) {
            return null
        }
        val newBase = base.substring(0, base.length() - oldExtension.length()) + newExtension
        return path.replaceName(newBase)
    }

    /**
     * Returns a new `Path` formed by replacing the extension of the last path segment of `path` with `newExtension`. Null is returned iff `path` has zero segments.
     */
    fun replaceExtension(
        path: com.google.devtools.build.lib.vfs.Path,
        newExtension: String?
    ): com.google.devtools.build.lib.vfs.Path? {
        val fragment: PathFragment? =
            com.google.devtools.build.lib.vfs.FileSystemUtils.replaceExtension(path.asFragment(), newExtension)
        return if (fragment == null) null else path.getFileSystem().getPath(fragment)
    }

    /**
     * Returns a new `PathFragment` formed by adding the extension to the last path segment of
     * `path`. Null is returned if `path` has zero segments.
     */
    fun appendExtension(path: PathFragment, newExtension: String?): PathFragment? {
        return path.replaceName(path.getBaseName() + newExtension)
    }

    /**
     * Returns a new `PathFragment` formed by appending the given string to the last path
     * segment of `path` without removing the extension.  Returns null if `path`
     * has zero segments.
     */
    fun appendWithoutExtension(path: PathFragment, toAppend: String): PathFragment? {
        return path.replaceName(
            com.google.devtools.build.lib.vfs.FileSystemUtils.appendWithoutExtension(
                path.getBaseName(),
                toAppend
            )
        )
    }

    /**
     * Given a string that represents a file with an extension separated by a '.' and a string
     * to append, return a string in which `toAppend` has been appended to `name`
     * before the last '.' character.  If `name` does not include a '.', appends `toAppend` at the end.
     * 
     * 
     * For example,
     * ("libfoo.jar", "-src") ==> "libfoo-src.jar"
     * ("libfoo", "-src") ==> "libfoo-src"
     */
    private fun appendWithoutExtension(name: String, toAppend: String): String {
        val dotIndex: Int = name.lastIndexOf('.'.code)
        if (dotIndex > 0) {
            val baseName: String = name.substring(0, dotIndex)
            val extension: String = name.substring(dotIndex)
            return baseName + toAppend + extension
        } else {
            return name + toAppend
        }
    }

    /**
     * Return the current working directory as expressed by the System property
     * 'user.dir'.
     */
    fun getWorkingDirectory(fs: com.google.devtools.build.lib.vfs.FileSystem): com.google.devtools.build.lib.vfs.Path? {
        return fs.getPath(com.google.devtools.build.lib.vfs.FileSystemUtils.getWorkingDirectory())
    }

    @kotlin.jvm.JvmStatic
    val workingDirectory: PathFragment?
        /**
         * Returns the current working directory as expressed by the System property
         * 'user.dir'. This version does not require a [FileSystem].
         */
        get() =// System properties obtained from host are encoded using sun.jnu.encoding, so reencode them to
        // the internal representation.
            // https://github.com/openjdk/jdk/blob/285385247aaa262866697ed848040f05f4d94988/src/java.base/share/native/libjava/System.c#L121
            PathFragment.Companion.create(
                StringEncoding.platformToInternal(java.lang.System.getProperty("user.dir", "/"))
            )

    /**
     * "Touches" the file or directory specified by the path, following symbolic
     * links. If it does not exist, it is created as an empty file; otherwise, the
     * time of last access is updated to the current time.
     * 
     * @throws IOException if there was an error while touching the file
     */
    @ThreadSafe
    @Throws(IOException::class)
    fun touchFile(path: com.google.devtools.build.lib.vfs.Path) {
        if (path.exists()) {
            path.setLastModifiedTime(com.google.devtools.build.lib.vfs.Path.Companion.NOW_SENTINEL_TIME)
        } else {
            com.google.devtools.build.lib.vfs.FileSystemUtils.createEmptyFile(path)
        }
    }

    /**
     * Creates an empty regular file with the name of the current path, following
     * symbolic links.
     * 
     * @throws IOException if the file could not be created for any reason
     * (including that there was already a file at that location)
     */
    @Throws(IOException::class)
    fun createEmptyFile(path: com.google.devtools.build.lib.vfs.Path) {
        path.getOutputStream().close()
    }

    /**
     * Creates or updates an existing symbolic link from 'link' to 'target'. Missing ancestor
     * directories of 'link' will also be created.
     * 
     * 
     * This operation is not atomic.
     * 
     * @throws NotASymlinkException if the path already exists and is not a symbolic link
     * @throws IOException if creating the symbolic link or its ancestor directories failed for any
     * other reason
     */
    @ThreadSafe // but not atomic
    @Throws(IOException::class)
    fun ensureSymbolicLink(
        link: com.google.devtools.build.lib.vfs.Path,
        target: com.google.devtools.build.lib.vfs.Path
    ) {
        com.google.devtools.build.lib.vfs.FileSystemUtils.ensureSymbolicLink(link, target.asFragment())
    }

    /**
     * Creates or updates an existing symbolic link from 'link' to 'target'. Missing ancestor
     * directories of 'link' will also be created.
     * 
     * 
     * This operation is not atomic.
     * 
     * @throws NotASymlinkException if the path already exists and is not a symbolic link
     * @throws IOException if creating the symbolic link or its ancestor directories failed for any
     * other reason
     */
    @ThreadSafe // but not atomic
    @Throws(IOException::class)
    fun ensureSymbolicLink(link: com.google.devtools.build.lib.vfs.Path, target: String?) {
        com.google.devtools.build.lib.vfs.FileSystemUtils.ensureSymbolicLink(
            link,
            PathFragment.Companion.create(target)
        )
    }

    /**
     * Creates or updates an existing symbolic link from 'link' to 'target'. Missing ancestor
     * directories of 'link' will also be created.
     * 
     * 
     * This operation is not atomic.
     * 
     * @throws NotASymlinkException if the path already exists and is not a symbolic link
     * @throws IOException if creating the symbolic link or its ancestor directories failed for any
     * other reason
     */
    @ThreadSafe // but not atomic
    @Throws(IOException::class)
    fun ensureSymbolicLink(link: com.google.devtools.build.lib.vfs.Path, target: PathFragment?) {
        // TODO(bazel-team): (2009) consider adding the logic for recovering from the case when
        // we have already created a parent directory symlink earlier.
        var parentKnownToExist = false
        try {
            // This will throw if the path already exists and is not a symbolic link.
            if (link.readSymbolicLink() == target) {
                // Nothing to do.
                return
            }
            // The symlink exists, but points elsewhere.
            link.delete()
            parentKnownToExist = true
        } catch (e: FileNotFoundException) {
            // Path does not exist; fall through.
        }
        if (!parentKnownToExist) {
            link.getParentDirectory().createDirectoryAndParents()
        }
        link.createSymbolicLink(target)
    }

    fun asByteSource(path: com.google.devtools.build.lib.vfs.Path): com.google.common.io.ByteSource {
        return object : com.google.common.io.ByteSource() {
            @Throws(IOException::class)
            override fun openStream(): java.io.InputStream {
                return path.getInputStream()
            }
        }
    }

    @kotlin.jvm.JvmOverloads
    fun asByteSink(
        path: com.google.devtools.build.lib.vfs.Path,
        append: Boolean = false
    ): com.google.common.io.ByteSink {
        return object : com.google.common.io.ByteSink() {
            @Throws(IOException::class)
            override fun openStream(): java.io.OutputStream {
                return path.getOutputStream(append)
            }
        }
    }

    /**
     * Copies a file, potentially overwriting the destination. Preserves the modification time and
     * permissions.
     * 
     * 
     * If the source is a symbolic link, it will be followed. If the destination is a symbolic
     * link, it will be replaced.
     * 
     * 
     * Copying directories is not supported.
     * 
     * @param from the source path
     * @param to the destination path
     * @throws FileNotFoundException if the source does not exist, or the parent directory of the
     * destination does not exist
     * @throws IOException if the copy fails for any other reason
     */
    @ThreadSafe // but not atomic
    @Throws(IOException::class)
    fun copyFile(from: com.google.devtools.build.lib.vfs.Path, to: com.google.devtools.build.lib.vfs.Path) {
        com.google.devtools.build.lib.vfs.FileSystemUtils.copyFile(from, to, from.stat())
    }

    @Throws(IOException::class)
    private fun copyFile(
        from: com.google.devtools.build.lib.vfs.Path,
        to: com.google.devtools.build.lib.vfs.Path,
        stat: FileStatus
    ) {
        if (!stat.isFile()) {
            throw IOException("don't know how to copy " + from)
        }
        val fromNio: java.nio.file.Path? = from.getFileSystem().getNioPath(from.asFragment())
        val toNio: java.nio.file.Path? = to.getFileSystem().getNioPath(to.asFragment())
        if (fromNio != null && toNio != null) {
            // Fast path: Files.copy uses various optimizations such as kernel buffers (sendfile on Unix)
            // or copy-on-write (clonefile on macOS, copy_file_range on Linux with a supported file
            // system).
            try {
                java.nio.file.Files.copy(
                    fromNio,
                    toNio,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
                )
            } catch (e: IOException) {
                throw com.google.devtools.build.lib.vfs.FileSystem.Companion.translateNioToIoException(
                    from.asFragment(),
                    e
                )
            }
            return
        }
        // Target may be a symlink, in which case we should not follow it.
        to.delete()
        from.getInputStream().use { `in` ->
            to.getOutputStream().use { out ->
                // This may use a faster copy method (such as via an in-kernel buffer) if both streams are
                // backed by files.
                `in`.transferTo(out)
            }
        }
        to.setLastModifiedTime(stat.getLastModifiedTime())
        val perms: Int = stat.getPermissions()
        if (perms != -1) {
            to.chmod(perms)
        } else {
            to.setReadable(from.isReadable())
            to.setWritable(from.isWritable())
            to.setExecutable(from.isExecutable())
        }
    }

    /**
     * Moves a file or symbolic link, potentially overwriting the destination. Does not follow
     * symbolic links.
     * 
     * 
     * This method is not guaranteed to be atomic. Use [Path.renameTo] instead.
     * 
     * 
     * If the move fails (usually because the source and destination are in different filesystems),
     * falls back to copying the file, preserving its permissions and modification time. Note that the
     * fallback has very different performance characteristics, which is why this method reports what
     * actually happened back to the caller.
     * 
     * @param from the source path
     * @param to the destination path
     * @return a description of how the move was performed
     * @throws FileNotFoundException if the source does not exist, or the parent directory of the
     * destination does not exit
     * @throws IOException if the move fails for any other reason
     */
    @ThreadSafe // but not atomic
    @Throws(IOException::class)
    fun moveFile(from: com.google.devtools.build.lib.vfs.Path, to: com.google.devtools.build.lib.vfs.Path): MoveResult {
        try {
            from.renameTo(to)
            return MoveResult.FILE_MOVED
        } catch (ignored: IOException) {
            // Fallback to a copy.
            val stat: FileStatus = from.stat(Symlinks.NOFOLLOW)
            if (stat.isFile()) {
                com.google.devtools.build.lib.vfs.FileSystemUtils.copyFile(from, to, stat)
            } else if (stat.isSymbolicLink()) {
                val targetPath: PathFragment? = from.readSymbolicLink()
                try {
                    to.createSymbolicLink(targetPath)
                } catch (ignored2: IOException) {
                    // May have failed due the target file existing, but not being a symlink.
                    // TODO: Only catch FileAlreadyExistsException once we throw that.
                    to.delete()
                    to.createSymbolicLink(targetPath)
                }
            } else {
                // TODO(tjgq): The move/copy cases should have a consistent result for a directory.
                throw IOException("Don't know how to move " + from, ignored)
            }
            try {
                from.delete()
            } catch (e: IOException) {
                // If we fail to delete the source, then delete the destination.
                try {
                    to.delete()
                } catch (e2: IOException) {
                    e.addSuppressed(e2)
                }
                throw e
            }
            return MoveResult.FILE_COPIED
        }
    }

    /**
     * Atomically renames a source file to a target file, tolerating the case where another thread has
     * concurrently created the target file (e.g. because it is known to have the same content in a
     * CAS-like structure).
     * 
     * 
     * This handles a Windows-specific edge case: when the target file is being read by another
     * process (e.g., during a concurrent cache lookup), the rename operation fails with a [ ]. If the target file already exists when this happens, it means another
     * thread won the race to create it, so we can safely delete the source file.
     * 
     * 
     * The parent directories of the target file must already exist.
     * 
     * @param source the file to rename
     * @param target the destination path
     */
    @ThreadSafe
    @Throws(IOException::class)
    fun renameToleratingConcurrentCreation(
        source: com.google.devtools.build.lib.vfs.Path,
        target: com.google.devtools.build.lib.vfs.Path
    ) {
        try {
            source.renameTo(target)
        } catch (e: FileAccessException) {
            // On Windows, atomically replacing a file that is currently opened (e.g. due to a concurrent
            // get on the cache) results in renameTo throwing this exception, which wraps an
            // AccessDeniedException. This case is benign since if the target path already exists, we know
            // that another thread won the race to place the file in the cache. As the exception is rather
            // generic and could result from other failure types, we rethrow the exception if the cache
            // entry hasn't been created.
            if (com.google.devtools.build.lib.util.OS.Companion.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS || !target.exists()) {
                throw e
            }
            source.delete()
        }
    }

    /* Directory tree operations. */
    /**
     * Returns a new collection containing all of the paths below a given root path, for which the
     * given predicate is true. Symbolic links are not followed, and may appear in the result.
     * 
     * @throws IOException If the root does not denote a directory
     */
    @ThreadSafe
    @Throws(IOException::class)
    fun traverseTree(
        root: com.google.devtools.build.lib.vfs.Path,
        predicate: java.util.function.Predicate<com.google.devtools.build.lib.vfs.Path?>
    ): MutableCollection<com.google.devtools.build.lib.vfs.Path?> {
        val paths: MutableList<com.google.devtools.build.lib.vfs.Path?> =
            java.util.ArrayList<com.google.devtools.build.lib.vfs.Path?>()
        com.google.devtools.build.lib.vfs.FileSystemUtils.traverseTree(paths, root, predicate)
        return paths
    }

    /**
     * Populates an existing Path List, adding all of the paths below a given root path for which the
     * given predicate is true. Symbolic links are not followed, and may appear in the result.
     * 
     * @throws IOException If the root does not denote a directory
     */
    @ThreadSafe
    @Throws(IOException::class)
    fun traverseTree(
        paths: MutableCollection<com.google.devtools.build.lib.vfs.Path?>,
        root: com.google.devtools.build.lib.vfs.Path,
        predicate: java.util.function.Predicate<com.google.devtools.build.lib.vfs.Path?>
    ) {
        for (dirent in root.readdir(Symlinks.NOFOLLOW)) {
            val childPath: com.google.devtools.build.lib.vfs.Path = root.getChild(dirent.getName())
            if (predicate.test(childPath)) {
                paths.add(childPath)
            }
            if (dirent.getType() == com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY) {
                com.google.devtools.build.lib.vfs.FileSystemUtils.traverseTree(paths, childPath, predicate)
            }
        }
    }

    /**
     * Copies all dir trees under a given 'from' dir to location 'to', while overwriting all files in
     * the potentially existing 'to'. Symlinks are copied as-is.
     * 
     * 
     * The source and the destination must be non-overlapping, otherwise an
     * IllegalArgumentException will be thrown. This method cannot be used to copy a dir tree to a sub
     * tree of itself.
     * 
     * 
     * If no error occurs, the method returns normally. If the given 'from' does not exist, a
     * FileNotFoundException is thrown. An IOException is thrown when other erroneous situations
     * occur. (e.g. read errors)
     */
    @ThreadSafe
    @Throws(IOException::class)
    fun copyTreesBelow(from: com.google.devtools.build.lib.vfs.Path, to: com.google.devtools.build.lib.vfs.Path) {
        require(!to.startsWith(from)) { to.toString() + " is a subdirectory of " + from }

        for (dirent in from.readdir(Symlinks.NOFOLLOW)) {
            val fromChild: com.google.devtools.build.lib.vfs.Path = from.getChild(dirent.getName())
            val toChild: com.google.devtools.build.lib.vfs.Path = to.getChild(dirent.getName())
            when (dirent.getType()) {
                com.google.devtools.build.lib.vfs.Dirent.Type.FILE -> com.google.devtools.build.lib.vfs.FileSystemUtils.copyFile(
                    fromChild,
                    toChild
                )

                com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK -> com.google.devtools.build.lib.vfs.FileSystemUtils.ensureSymbolicLink(
                    toChild,
                    fromChild.readSymbolicLink()
                )

                com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY -> {
                    toChild.createDirectory()
                    com.google.devtools.build.lib.vfs.FileSystemUtils.copyTreesBelow(fromChild, toChild)
                }

                else -> throw IOException("Don't know how to copy " + fromChild)
            }
        }
    }

    /**
     * Moves all dir trees under a given 'from' dir to location 'to', while overwriting
     * all files in the potentially existing 'to'. Doesn't resolve symbolic links.
     * 
     * 
     * The source and the destination must be non-overlapping, otherwise an
     * IllegalArgumentException will be thrown. This method cannot be used to copy
     * a dir tree to a sub tree of itself.
     * 
     * 
     * If no error occurs, the method returns normally. If the given 'from' does
     * not exist, a FileNotFoundException is thrown. An IOException is thrown when
     * other erroneous situations occur. (e.g. read errors)
     */
    @ThreadSafe
    @Throws(IOException::class)
    fun moveTreesBelow(from: com.google.devtools.build.lib.vfs.Path, to: com.google.devtools.build.lib.vfs.Path) {
        require(!to.startsWith(from)) { to.toString() + " is a subdirectory of " + from }

        // Actions can make output directories inaccessible, which would cause the move to fail.
        from.chmod(493)

        // TODO(tjgq): Don't leave an empty directory behind.
        val entries: MutableCollection<com.google.devtools.build.lib.vfs.Path> = from.getDirectoryEntries()
        for (entry in entries) {
            if (entry.isDirectory(Symlinks.NOFOLLOW)) {
                val subDir: com.google.devtools.build.lib.vfs.Path = to.getChild(entry.getBaseName())
                subDir.createDirectory()
                com.google.devtools.build.lib.vfs.FileSystemUtils.moveTreesBelow(entry, subDir)
            } else {
                val newEntry: com.google.devtools.build.lib.vfs.Path = to.getChild(entry.getBaseName())
                com.google.devtools.build.lib.vfs.FileSystemUtils.moveFile(entry, newEntry)
            }
        }
    }

    /**
     * Attempts to remove a relative chain of directories under a given base. Returns `true` if
     * the removal was successful, and returns `false` if the removal fails because a directory
     * was not empty. An [IOException] is thrown for any other errors.
     */
    @ThreadSafe
    fun removeDirectoryAndParents(base: com.google.devtools.build.lib.vfs.Path, toRemove: PathFragment): Boolean {
        var toRemove: PathFragment = toRemove
        if (toRemove.isAbsolute()) {
            return false
        }
        try {
            while (!toRemove.isEmpty()) {
                val p: com.google.devtools.build.lib.vfs.Path = base.getRelative(toRemove)
                if (p.exists()) {
                    p.delete()
                }
                toRemove = toRemove.getParentDirectory()
            }
        } catch (e: IOException) {
            return false
        }
        return true
    }

    /**
     * Decodes the given byte array assumed to be encoded with ISO-8859-1 encoding (isolatin1).
     */
    fun convertFromLatin1(content: ByteArray): CharArray {
        val latin1 = CharArray(content.size)
        for (i in latin1.indices) { // yeah, latin1 is this easy! :-)
            latin1[i] = (0xff and content[i].toInt()).toChar()
        }
        return latin1
    }

    /**
     * Writes lines to file using ISO-8859-1 encoding (isolatin1).
     */
    @ThreadSafe // but not atomic
    @Throws(IOException::class)
    fun writeIsoLatin1(file: com.google.devtools.build.lib.vfs.Path, vararg lines: String?) {
        com.google.devtools.build.lib.vfs.FileSystemUtils.writeLinesAs(
            file,
            java.nio.charset.StandardCharsets.ISO_8859_1,
            *lines
        )
    }

    /**
     * Append lines to file using ISO-8859-1 encoding (isolatin1).
     */
    @ThreadSafe // but not atomic
    @Throws(IOException::class)
    fun appendIsoLatin1(file: com.google.devtools.build.lib.vfs.Path, vararg lines: String?) {
        com.google.devtools.build.lib.vfs.FileSystemUtils.appendLinesAs(
            file,
            java.nio.charset.StandardCharsets.ISO_8859_1,
            *lines
        )
    }

    /**
     * Writes the specified String as ISO-8859-1 (latin1) encoded bytes to the
     * file. Follows symbolic links.
     * 
     * @throws IOException if there was an error
     */
    @Throws(IOException::class)
    fun writeContentAsLatin1(outputFile: com.google.devtools.build.lib.vfs.Path, content: String) {
        com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent(
            outputFile,
            java.nio.charset.StandardCharsets.ISO_8859_1,
            content
        )
    }

    /**
     * Writes the specified String using the specified encoding to the file. Follows symbolic links.
     * 
     * @throws IOException if there was an error
     */
    @Throws(IOException::class)
    fun writeContent(
        outputFile: com.google.devtools.build.lib.vfs.Path,
        charset: java.nio.charset.Charset,
        content: String
    ) {
        asByteSink(outputFile).asCharSink(charset).write(content)
    }

    /**
     * Writes the specified byte array to the output file. Follows symbolic links.
     * 
     * @throws IOException if there was an error
     */
    @Throws(IOException::class)
    fun writeContent(outputFile: com.google.devtools.build.lib.vfs.Path, content: ByteArray) {
        asByteSink(outputFile).write(content)
    }

    /** Writes lines to file using the given encoding, ending every line with '\n'.  */
    @ThreadSafe // but not atomic
    @Throws(IOException::class)
    fun writeLinesAs(
        file: com.google.devtools.build.lib.vfs.Path,
        charset: java.nio.charset.Charset,
        vararg lines: String?
    ) {
        com.google.devtools.build.lib.vfs.FileSystemUtils.writeLinesAs(
            file,
            charset,
            java.util.Arrays.asList<String?>(*lines)
        )
    }

    /** Writes lines to file using the given encoding, ending every line with '\n'.  */
    @ThreadSafe // but not atomic
    @Throws(IOException::class)
    fun writeLinesAs(
        file: com.google.devtools.build.lib.vfs.Path,
        charset: java.nio.charset.Charset,
        lines: Iterable<String?>
    ) {
        file.getParentDirectory().createDirectoryAndParents()
        asByteSink(file).asCharSink(charset).writeLines(lines, "\n")
    }

    /** Appends lines to file using the given encoding, ending every line with '\n'.  */
    @ThreadSafe // but not atomic
    @Throws(IOException::class)
    fun appendLinesAs(
        file: com.google.devtools.build.lib.vfs.Path,
        charset: java.nio.charset.Charset,
        vararg lines: String?
    ) {
        file.getParentDirectory().createDirectoryAndParents()
        com.google.devtools.build.lib.vfs.FileSystemUtils.asByteSink(file, true).asCharSink(charset)
            .writeLines(java.util.Arrays.asList<String?>(*lines), "\n")
    }

    /**
     * Updates the contents of the output file if they do not match the given array, thus maintaining
     * the mtime and ctime in case of no updates. Follows symbolic links.
     * 
     * 
     * If the output file already exists but is unreadable, this tries to overwrite it with the new
     * contents. In other words: unreadable or missing files are considered to be non-matching.
     * 
     * @throws IOException if there was an error
     */
    @Throws(IOException::class)
    fun maybeUpdateContent(outputFile: com.google.devtools.build.lib.vfs.Path, newContent: ByteArray) {
        var currentContent: ByteArray?
        try {
            currentContent = com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(outputFile)
        } catch (e: IOException) {
            // Ignore error per the rationale given in the docstring. Keep in mind that what we are doing
            // here is for performance reasons only so we should only break if the real action (that is,
            // the write) fails -- not any of the optimization steps.
            currentContent = null
        }

        if (currentContent == null) {
            com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent(outputFile, newContent)
        } else {
            if (!java.util.Arrays.equals(newContent, currentContent)) {
                if (!outputFile.isWritable()) {
                    outputFile.delete()
                }
                com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent(outputFile, newContent)
            }
        }
    }

    /**
     * Returns the entirety of the specified input stream and returns it as a char
     * array, decoding characters using ISO-8859-1 (Latin1).
     * 
     * @throws IOException if there was an error
     */
    @Throws(IOException::class)
    fun readContentAsLatin1(`in`: java.io.InputStream): CharArray {
        return com.google.devtools.build.lib.vfs.FileSystemUtils.convertFromLatin1(
            com.google.common.io.ByteStreams.toByteArray(
                `in`
            )
        )
    }

    /**
     * Returns the entirety of the specified file and returns it as a char array,
     * decoding characters using ISO-8859-1 (Latin1).
     * 
     * @throws IOException if there was an error
     */
    @Throws(IOException::class)
    fun readContentAsLatin1(inputFile: com.google.devtools.build.lib.vfs.Path): CharArray {
        return com.google.devtools.build.lib.vfs.FileSystemUtils.convertFromLatin1(
            com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(
                inputFile
            )
        )
    }

    /**
     * Returns a list of the lines in an ISO-8859-1 (Latin1) text file. If the file ends in a line
     * break, the list will contain an empty string as the last element.
     * 
     * @throws IOException if there was an error
     */
    @Throws(IOException::class)
    fun readLinesAsLatin1(inputFile: com.google.devtools.build.lib.vfs.Path): com.google.common.collect.ImmutableList<String?> {
        return com.google.devtools.build.lib.vfs.FileSystemUtils.readLines(
            inputFile,
            java.nio.charset.StandardCharsets.ISO_8859_1
        )
    }

    /**
     * Returns a list of the lines in a text file in the given [Charset]. If the file ends in a
     * line break, the list will contain an empty string as the last element.
     * 
     * @throws IOException if there was an error
     */
    @Throws(IOException::class)
    fun readLines(
        inputFile: com.google.devtools.build.lib.vfs.Path,
        charset: java.nio.charset.Charset
    ): com.google.common.collect.ImmutableList<String?> {
        return com.google.devtools.build.lib.vfs.FileSystemUtils.asByteSource(inputFile).asCharSource(charset)
            .readLines()
    }

    /**
     * Returns the entirety of the specified file and returns it as a byte array.
     * 
     * @throws IOException if there was an error
     */
    @Throws(IOException::class)
    fun readContent(inputFile: com.google.devtools.build.lib.vfs.Path): ByteArray {
        return com.google.devtools.build.lib.vfs.FileSystemUtils.asByteSource(inputFile).read()
    }

    /**
     * Reads the entire file using the given charset and returns the contents as a string
     */
    @Throws(IOException::class)
    fun readContent(inputFile: com.google.devtools.build.lib.vfs.Path, charset: java.nio.charset.Charset): String {
        return com.google.devtools.build.lib.vfs.FileSystemUtils.asByteSource(inputFile).asCharSource(charset).read()
    }

    /**
     * Reads the given file `path`, assumed to have size `fileSize`, and does a check on
     * the number of bytes read.
     * 
     * 
     * Use this method when you already know the size of the file. The check is intended to catch
     * issues where the filesystem incorrectly returns truncated file contents, or where an external
     * modification has concurrently truncated or appended to the file.
     * 
     * @throws IOException if there was an error, or if fewer than `fileSize` bytes were read.
     */
    @Throws(IOException::class)
    fun readWithKnownFileSize(path: com.google.devtools.build.lib.vfs.Path, fileSize: Long): ByteArray {
        com.google.common.base.Preconditions.checkArgument(
            fileSize >= 0,
            "fileSize needs to be >=0, but it is %s",
            fileSize
        )
        if (fileSize > java.lang.Integer.MAX_VALUE) {
            throw IOException("Cannot read file with size larger than 2GB")
        }
        val size = fileSize.toInt()
        val bytes = ByteArray(size)
        com.google.devtools.build.lib.vfs.FileSystemUtils.asByteSource(path).openBufferedStream().use { `in` ->
            val read: Int = com.google.common.io.ByteStreams.read(`in`, bytes, 0, size)
            if (read != size) {
                throw ShortReadIOException(path, size, read)
            }
            val eof: Int = `in`.read()
            if (eof != -1) {
                throw LongReadIOException(path, size)
            }
        }
        return bytes
    }

    /**
     * Returns the type of the file system path belongs to.
     */
    fun getFileSystem(path: com.google.devtools.build.lib.vfs.Path): String? {
        return path.getFileSystem().getFileSystemType(path.asFragment())
    }

    /** Returns whether the given path starts with any of the paths in the given list of prefixes.  */
    fun startsWithAny(
        path: com.google.devtools.build.lib.vfs.Path,
        prefixes: Iterable<com.google.devtools.build.lib.vfs.Path>
    ): Boolean {
        for (prefix in prefixes) {
            if (path.startsWith(prefix)) {
                return true
            }
        }
        return false
    }

    /**
     * Returns whether the given path starts with any of the paths in the given list of prefixes,
     * ignoring case.
     */
    fun startsWithAnyIgnoringCase(
        path: com.google.devtools.build.lib.vfs.Path,
        prefixes: Iterable<com.google.devtools.build.lib.vfs.Path>
    ): Boolean {
        for (prefix in prefixes) {
            if (path.startsWithIgnoringCase(prefix)) {
                return true
            }
        }
        return false
    }

    /** Returns whether the given path starts with any of the paths in the given list of prefixes.  */
    fun startsWithAny(path: PathFragment, prefixes: Iterable<PathFragment?>): Boolean {
        for (prefix in prefixes) {
            if (path.startsWith(prefix)) {
                return true
            }
        }
        return false
    }


    /**
     * Create a new hard link file at "linkPath" for file at "originalPath". If "originalPath" is a
     * directory, then for each entry, create link under "linkPath" recursively.
     * 
     * @param linkPath The path of the new link file to be created
     * @param originalPath The path of the original file
     * @throws IOException if there was an error executing [Path.createHardLink]
     */
    @Throws(IOException::class)
    fun createHardLink(
        linkPath: com.google.devtools.build.lib.vfs.Path,
        originalPath: com.google.devtools.build.lib.vfs.Path
    ) {
        // Directory

        if (originalPath.isDirectory()) {
            for (originalSubpath in originalPath.getDirectoryEntries()) {
                val linkSubpath: com.google.devtools.build.lib.vfs.Path =
                    linkPath.getRelative(originalSubpath.relativeTo(originalPath))
                com.google.devtools.build.lib.vfs.FileSystemUtils.createHardLink(linkSubpath, originalSubpath)
            }
            // Other types of file
        } else {
            val parentDir: com.google.devtools.build.lib.vfs.Path? = linkPath.getParentDirectory()
            if (!parentDir.exists()) {
                parentDir.createDirectoryAndParents()
            }
            originalPath.createHardLink(linkPath)
        }
    }

    /** Describes the behavior of a [.moveFile] operation.  */
    enum class MoveResult {
        /** The file was moved at the file system level.  */
        FILE_MOVED,

        /** The file had to be copied and then deleted because the move failed.  */
        FILE_COPIED,
    }

    /**
     * The type of [IOException] thrown by [.readWithKnownFileSize] when fewer bytes than
     * expected are read.
     */
    class ShortReadIOException private constructor(
        path: com.google.devtools.build.lib.vfs.Path?,
        fileSize: Int,
        numBytesRead: Int
    ) : IOException(
        ("Unexpected short read from file '" + path + "' (expected " + fileSize + ", got "
                + numBytesRead + " bytes)")
    ) {
        val path: com.google.devtools.build.lib.vfs.Path?
        val fileSize: Int
        val numBytesRead: Int

        init {
            this.path = path
            this.fileSize = fileSize
            this.numBytesRead = numBytesRead
        }
    }

    /**
     * The type of [IOException] thrown by [.readWithKnownFileSize] when more bytes than
     * expected could be read.
     */
    class LongReadIOException private constructor(path: com.google.devtools.build.lib.vfs.Path?, fileSize: Int) :
        IOException("File '" + path + "' is unexpectedly longer than " + fileSize + " bytes)") {
        val path: com.google.devtools.build.lib.vfs.Path?
        val fileSize: Int

        init {
            this.path = path
            this.fileSize = fileSize
        }
    }
}
