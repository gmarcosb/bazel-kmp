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

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/**
 * A FileSystem that does not use any JNI and hence, does not require a shared library be present at
 * execution.
 * 
 * 
 * Note: Blaze profiler tasks are defined on the system call level - thus we do not distinguish
 * (from profiling perspective) between different methods on this class that end up doing stat()
 * system call - they all are associated with the VFS_STAT task.
 */
@ThreadSafe
open class JavaIoFileSystem(hashFunction: DigestHashFunction?) : DiskBackedFileSystem(hashFunction) {
    private val clock: com.google.devtools.build.lib.clock.Clock

    init {
        this.clock = com.google.devtools.build.lib.clock.JavaClock()
    }

    override fun getIoFile(path: PathFragment): java.io.File? {
        return java.io.File(StringEncoding.internalToPlatform(path.getPathString()))
    }

    /**
     * Returns a [java.nio.file.Path] representing the same path as provided `path`.
     * 
     * 
     * Note: while it's possible to use [FileSystem.getIoFile] in combination
     * with [File.toPath] to achieve essentially the same, using this method is preferable
     * because it avoids extra allocations and does not lose track of the underlying Java filesystem,
     * which is useful for some in-memory filesystem implementations like JimFS.
     */
    override fun getNioPath(path: PathFragment): java.nio.file.Path? {
        return Paths.get(StringEncoding.internalToPlatform(path.getPathString()))
    }

    private fun linkOpts(followSymlinks: Boolean): Array<LinkOption?> {
        return if (followSymlinks) NO_LINK_OPTION else NOFOLLOW_LINKS_OPTION
    }

    @Throws(IOException::class)
    override fun getDirectoryEntries(path: PathFragment): MutableCollection<String?> {
        val file: java.io.File? = getIoFile(path)
        var entries: Array<String>?
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        try {
            entries = file.list()
            if (entries == null) {
                if (file.exists()) {
                    throw IOException(path.toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_NOT_A_DIRECTORY)
                } else {
                    throw FileNotFoundException(path.toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_NO_SUCH_FILE_OR_DIR)
                }
            }
        } finally {
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_DIR, file.getPath())
        }
        return com.google.common.collect.Lists.transform<String?, String?>(
            java.util.Arrays.asList<String?>(*entries),
            com.google.common.base.Function { obj: StringEncoding?, s: String -> StringEncoding.platformToInternal(s) })
    }

    override fun exists(path: PathFragment, followSymlinks: Boolean): Boolean {
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        try {
            val nioPath: java.nio.file.Path? = getNioPath(path)
            return java.nio.file.Files.exists(nioPath, *linkOpts(followSymlinks))
        } catch (e: InvalidPathException) {
            return false
        } finally {
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_STAT, path.toString())
        }
    }

    @Throws(IOException::class)
    override fun isReadable(path: PathFragment): Boolean {
        val file: java.io.File? = getIoFile(path)
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        try {
            if (!file.exists()) {
                throw FileNotFoundException(path.toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_NO_SUCH_FILE_OR_DIR)
            }
            return file.canRead()
        } finally {
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_STAT, file.getPath())
        }
    }

    @Throws(IOException::class)
    override fun isWritable(path: PathFragment): Boolean {
        val file: java.io.File? = getIoFile(path)
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        try {
            if (!file.exists()) {
                if (linkExists(file)) {
                    throw IOException(path.toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_PERMISSION_DENIED)
                } else {
                    throw FileNotFoundException(path.toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_NO_SUCH_FILE_OR_DIR)
                }
            }
            return file.canWrite()
        } finally {
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_STAT, file.getPath())
        }
    }

    @Throws(IOException::class)
    override fun isExecutable(path: PathFragment): Boolean {
        val file: java.io.File? = getIoFile(path)
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        try {
            if (!file.exists()) {
                throw FileNotFoundException(path.toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_NO_SUCH_FILE_OR_DIR)
            }
            return file.canExecute()
        } finally {
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_STAT, file.getPath())
        }
    }

    @Throws(IOException::class)
    override fun setReadable(path: PathFragment, readable: Boolean) {
        val file: java.io.File? = getIoFile(path)
        if (!file.exists()) {
            throw FileNotFoundException(path.toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_NO_SUCH_FILE_OR_DIR)
        }
        if (!file.setReadable(readable) && readable) {
            throw IOException(java.lang.String.format("Failed to make %s readable", path))
        }
    }

    @Throws(IOException::class)
    override fun setWritable(path: PathFragment, writable: Boolean) {
        val file: java.io.File? = getIoFile(path)
        if (!file.exists()) {
            throw FileNotFoundException(path.toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_NO_SUCH_FILE_OR_DIR)
        }
        if (!file.setWritable(writable) && writable) {
            throw IOException(java.lang.String.format("Failed to make %s writable", path))
        }
    }

    @Throws(IOException::class)
    override fun setExecutable(path: PathFragment, executable: Boolean) {
        val file: java.io.File? = getIoFile(path)
        if (!file.exists()) {
            throw FileNotFoundException(path.toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_NO_SUCH_FILE_OR_DIR)
        }
        if (!file.setExecutable(executable) && executable) {
            throw IOException(java.lang.String.format("Failed to make %s executable", path))
        }
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
        return com.google.devtools.build.lib.util.OS.Companion.getCurrent() == com.google.devtools.build.lib.util.OS.DARWIN || com.google.devtools.build.lib.util.OS.Companion.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS
    }

    @Throws(IOException::class)
    override fun createDirectory(path: PathFragment): Boolean {
        val file: java.io.File? = getIoFile(path)
        if (file.mkdir()) {
            return true
        }

        if (fileIsSymbolicLink(file.toPath())) {
            throw IOException(path.toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_FILE_EXISTS)
        }
        if (file.isDirectory()) {
            return false // directory already existed
        } else if (file.exists()) {
            throw IOException(path.toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_FILE_EXISTS)
        } else if (!file.getParentFile().exists()) {
            throw FileNotFoundException(
                path.getParentDirectory()
                    .toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_NO_SUCH_FILE_OR_DIR
            )
        }
        // Parent directory apparently exists - try to create our directory again.
        if (file.mkdir()) {
            return true // Everything is fine finally.
        } else if (!file.getParentFile().canWrite()) {
            throw FileAccessException(path.toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_PERMISSION_DENIED)
        } else {
            // Parent exists, is writable, yet we can't create our directory.
            throw FileNotFoundException(
                path.getParentDirectory()
                    .toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_NOT_A_DIRECTORY
            )
        }
    }

    @Throws(IOException::class)
    override fun createDirectoryAndParents(path: PathFragment) {
        val nioPath: java.nio.file.Path? = getNioPath(path)
        try {
            java.nio.file.Files.createDirectories(nioPath)
        } catch (e: FileAlreadyExistsException) {
            // Files.createDirectories will handle this case normally, but if the existing
            // file is a symlink to a directory then it still throws. Swallow this.
            if (!isDirectory(path,  /* followSymlinks= */true)) {
                throw e
            }
        }
    }

    private fun linkExists(file: java.io.File): Boolean {
        val shortName: String? = file.getName()
        val parentFile: java.io.File? = file.getParentFile()
        if (parentFile == null) {
            return false
        }
        val filenames: Array<String>? = parentFile.list()
        if (filenames == null) {
            return false
        }
        for (name in filenames) {
            if (name == shortName) {
                return true
            }
        }
        return false
    }

    @Throws(IOException::class)
    override fun createSymbolicLink(
        linkPath: PathFragment, targetFragment: PathFragment, type: SymlinkTargetType?
    ) {
        val nioPath: java.nio.file.Path? = getNioPath(linkPath)
        try {
            // Files.createSymbolicLink does not let us specify the target type.
            java.nio.file.Files.createSymbolicLink(
                nioPath,
                Paths.get(StringEncoding.internalToPlatform(targetFragment.getSafePathString()))
            )
        } catch (e: IOException) {
            throw com.google.devtools.build.lib.vfs.FileSystem.Companion.translateNioToIoException(linkPath, e)
        }
    }

    @Throws(IOException::class)
    override fun readSymbolicLink(path: PathFragment): PathFragment? {
        val nioPath: java.nio.file.Path? = getNioPath(path)
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        try {
            val link: String? = java.nio.file.Files.readSymbolicLink(nioPath).toString()
            return PathFragment.Companion.create(StringEncoding.platformToInternal(link))
        } catch (e: IOException) {
            throw com.google.devtools.build.lib.vfs.FileSystem.Companion.translateNioToIoException(path, e)
        } finally {
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_READLINK, path.getPathString())
        }
    }

    @Throws(IOException::class)
    override fun renameTo(sourcePath: PathFragment, targetPath: PathFragment) {
        val source: java.nio.file.Path? = getNioPath(sourcePath)
        val target: java.nio.file.Path? = getNioPath(targetPath)
        try {
            java.nio.file.Files.move(
                source,
                target,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: IOException) {
            throw com.google.devtools.build.lib.vfs.FileSystem.Companion.translateNioToIoException(sourcePath, e)
        }
    }

    @Throws(IOException::class)
    override fun getFileSize(path: PathFragment, followSymlinks: Boolean): Long {
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        try {
            return stat(path, followSymlinks).getSize()
        } finally {
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_STAT, path.getPathString())
        }
    }

    @Throws(IOException::class)
    override fun delete(path: PathFragment): Boolean {
        val nioPath: java.nio.file.Path? = getNioPath(path)
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        try {
            return java.nio.file.Files.deleteIfExists(nioPath)
        } catch (e: DirectoryNotEmptyException) {
            throw IOException(
                path.getPathString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_DIRECTORY_NOT_EMPTY,
                e
            )
        } catch (e: AccessDeniedException) {
            throw IOException(
                path.getPathString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_PERMISSION_DENIED,
                e
            )
        } catch (e: AtomicMoveNotSupportedException) {
            // All known but unexpected subclasses of FileSystemException.
            throw IOException(path.getPathString() + ": unexpected FileSystemException", e)
        } catch (e: FileAlreadyExistsException) {
            throw IOException(path.getPathString() + ": unexpected FileSystemException", e)
        } catch (e: FileSystemLoopException) {
            throw IOException(path.getPathString() + ": unexpected FileSystemException", e)
        } catch (e: NoSuchFileException) {
            throw IOException(path.getPathString() + ": unexpected FileSystemException", e)
        } catch (e: NotDirectoryException) {
            throw IOException(path.getPathString() + ": unexpected FileSystemException", e)
        } catch (e: NotLinkException) {
            throw IOException(path.getPathString() + ": unexpected FileSystemException", e)
        } catch (e: FileSystemException) {
            // Files.deleteIfExists() throws FileSystemException on Linux if a path component is a file.
            // We caught all known subclasses of FileSystemException so `e` is either an unknown
            // subclass or it is indeed a "Not a directory" error. Non-English JDKs may use a different
            // error message than "Not a directory", so we should not look for that text. Checking the
            // parent directory if it's indeed a directory is unrealiable, because another process may
            // modify it concurrently... but we have no better choice.
            if (e.getClass() == FileSystemException::class.java
                && !nioPath.getParent().toFile().isDirectory()
            ) {
                // Hopefully the try-block failed because a parent directory was in fact not a directory.
                // Theoretically it's possible that the try-block failed for some other reason and all
                // parent directories were indeed directories, but another process changed a parent
                // directory into a file after the try-block failed but before this catch-block started, and
                // we return false here losing the real exception in `e`, but we cannot know.
                return false
            } else {
                throw IOException(path.getPathString() + ": unexpected FileSystemException", e)
            }
        } finally {
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_DELETE, path.getPathString())
        }
    }

    @Throws(IOException::class)
    override fun getLastModifiedTime(path: PathFragment, followSymlinks: Boolean): Long {
        val file: java.io.File? = getIoFile(path)
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        try {
            return stat(path, followSymlinks).getLastModifiedTime()
        } finally {
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_STAT, file.getPath())
        }
    }

    protected open fun fileIsSymbolicLink(file: java.nio.file.Path?): Boolean {
        return java.nio.file.Files.isSymbolicLink(file)
    }

    @Throws(IOException::class)
    override fun setLastModifiedTime(path: PathFragment, newTime: Long) {
        val file: java.io.File? = getIoFile(path)
        if (!file.setLastModified(
                if (newTime == com.google.devtools.build.lib.vfs.Path.Companion.NOW_SENTINEL_TIME) clock.currentTimeMillis() else newTime
            )
        ) {
            if (!file.exists()) {
                throw FileNotFoundException(path.toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_NO_SUCH_FILE_OR_DIR)
            } else if (!file.getParentFile().canWrite()) {
                throw FileAccessException(
                    path.getParentDirectory()
                        .toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_PERMISSION_DENIED
                )
            } else {
                throw FileAccessException(path.toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_PERMISSION_DENIED)
            }
        }
    }

    @Throws(IOException::class)
    override fun getDigest(path: PathFragment): ByteArray? {
        val name: String? = path.toString()
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        try {
            return super.getDigest(path)
        } finally {
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_MD5, name)
        }
    }

    /**
     * Returns the status of a file. See [Path.stat] for specification.
     * 
     * 
     * The default implementation of this method is a "lazy" one, based on other accessor methods
     * such as [.isFile], etc. Subclasses may provide more efficient specializations. However,
     * we still try to follow Unix-like semantics of failing fast in case of non-existent files (or in
     * case of permission issues).
     */
    @Throws(IOException::class)
    override fun stat(path: PathFragment, followSymlinks: Boolean): FileStatus {
        val nioPath: java.nio.file.Path? = getNioPath(path)
        val attributes: BasicFileAttributes
        try {
            attributes =
                java.nio.file.Files.readAttributes<BasicFileAttributes>(
                    nioPath,
                    BasicFileAttributes::class.java,
                    *linkOpts(followSymlinks)
                )
        } catch (e: FileSystemException) {
            throw FileNotFoundException(path.toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_NO_SUCH_FILE_OR_DIR)
        }
        val status: FileStatus =
            object : FileStatus {
                val isFile: Boolean
                    get() = attributes.isRegularFile() || this.isSpecialFile

                val isSpecialFile: Boolean
                    get() = attributes.isOther()

                val isDirectory: Boolean
                    get() = attributes.isDirectory()

                val isSymbolicLink: Boolean
                    get() = attributes.isSymbolicLink()

                val size: Long
                    get() = attributes.size()

                val lastModifiedTime: Long
                    get() = attributes.lastModifiedTime().toMillis()

                val lastChangeTime: Long
                    get() =// This is the best we can do with Java NIO...
                        attributes.lastModifiedTime().toMillis()

                val nodeId: Long
                    get() =// TODO(bazel-team): Consider making use of attributes.fileKey().
                        -1
            }

        return status
    }

    override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus? {
        try {
            return stat(path, followSymlinks)
        } catch (e: FileNotFoundException) {
            // JavaIoFileSystem#stat (incorrectly) only throws FileNotFoundException (because it calls
            // #getLastModifiedTime, which can only throw a FileNotFoundException), so we always hit this
            // codepath. Thus, this method will incorrectly not throw an exception for some filesystem
            // errors.
            return null
        } catch (e: IOException) {
            // If this codepath is ever hit, then this method should be rewritten to properly distinguish
            // between not-found exceptions and others.
            throw java.lang.IllegalStateException(e)
        }
    }

    @Throws(IOException::class)
    override fun createFSDependentHardLink(linkPath: PathFragment, originalPath: PathFragment) {
        java.nio.file.Files.createLink(getNioPath(linkPath), getNioPath(originalPath))
    }

    companion object {
        private val NO_LINK_OPTION: Array<LinkOption?> = arrayOfNulls<LinkOption>(0)

        // This isn't generally safe; we rely on the file system APIs not modifying the array.
        private val NOFOLLOW_LINKS_OPTION: Array<LinkOption?> = arrayOf<LinkOption>(LinkOption.NOFOLLOW_LINKS)
    }
}
