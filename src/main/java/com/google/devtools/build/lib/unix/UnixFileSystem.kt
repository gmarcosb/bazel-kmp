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
package com.google.devtools.build.lib.unix

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/**
 * A disk-backed filesystem suitable for Unix systems, implemented using a mix of JNI and standard
 * library calls.
 */
@ThreadSafe
open class UnixFileSystem(
    hashFunction: DigestHashFunction?,
    protected val hashAttributeName: String,
    nativePosixFilesService: NativePosixFilesService
) : DiskBackedFileSystem(hashFunction) {
    protected val nativePosixFilesService: NativePosixFilesService

    init {
        this.nativePosixFilesService = nativePosixFilesService
    }

    @Throws(IOException::class)
    override fun getDirectoryEntries(path: PathFragment): MutableCollection<String?> {
        val name: String? = path.getPathString()
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        try {
            val dirents: Array<com.google.devtools.build.lib.unix.NativePosixFilesService.Dirent> =
                nativePosixFilesService.readdir(name)
            val builder: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<String?>(dirents.size)
            for (dirent in dirents) {
                builder.add(dirent.name)
            }
            return builder.build()
        } finally {
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_DIR, name)
        }
    }

    @Throws(IOException::class)
    override fun resolveOneLink(path: PathFragment): PathFragment? {
        // Beware, this seemingly simple code belies the complex specification of
        // FileSystem.resolveOneLink().
        return if (stat(path, false).isSymbolicLink()) readSymbolicLink(path) else null
    }

    @Throws(IOException::class)
    override fun readdir(
        path: PathFragment,
        followSymlinks: Boolean
    ): MutableCollection<com.google.devtools.build.lib.vfs.Dirent?> {
        val name: String? = path.getPathString()
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        try {
            val dirents: Array<com.google.devtools.build.lib.unix.NativePosixFilesService.Dirent> =
                nativePosixFilesService.readdir(name)
            val builder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.vfs.Dirent?> =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<com.google.devtools.build.lib.vfs.Dirent?>(
                    dirents.size
                )
            for (dirent in dirents) {
                var type: com.google.devtools.build.lib.vfs.Dirent.Type?
                // If the entry type is unknown, or if we're following symlinks and the entry is a symlink,
                // we need to stat the entry to get the type.
                if (dirent.type == com.google.devtools.build.lib.unix.NativePosixFilesService.Dirent.Type.UNKNOWN
                    || (followSymlinks && dirent.type == com.google.devtools.build.lib.unix.NativePosixFilesService.Dirent.Type.SYMLINK)
                ) {
                    try {
                        val stat: FileStatus? = statIfFound(path.getRelative(dirent.name), followSymlinks)
                        type =
                            if (stat != null) (stat as UnixFileStatus).getDirentType() else com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN
                    } catch (e: FileSymlinkLoopException) {
                        type = com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN
                    }
                } else {
                    type = convertDirentType(dirent.type)
                }
                builder.add(com.google.devtools.build.lib.vfs.Dirent(dirent.name, type))
            }
            return builder.build()
        } finally {
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_DIR, name)
        }
    }

    @Throws(IOException::class)
    override fun stat(path: PathFragment, followSymlinks: Boolean): FileStatus {
        val name: String? = path.getPathString()
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        val comp: Any? = com.google.devtools.build.lib.util.Blocker.begin()
        try {
            return UnixFileStatus(
                if (followSymlinks)
                    nativePosixFilesService.stat(name, StatErrorHandling.ALWAYS_THROW)
                else
                    nativePosixFilesService.lstat(name, StatErrorHandling.ALWAYS_THROW)
            )
        } finally {
            com.google.devtools.build.lib.util.Blocker.end(comp)
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_STAT, name)
        }
    }

    // Like stat(), but returns null instead of throwing.
    // This is a performance optimization in the case where clients
    // catch and don't re-throw.
    override fun statNullable(path: PathFragment, followSymlinks: Boolean): FileStatus? {
        val name: String? = path.getPathString()
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        val comp: Any? = com.google.devtools.build.lib.util.Blocker.begin()
        try {
            val stat: Stat? =
                if (followSymlinks)
                    nativePosixFilesService.stat(name, StatErrorHandling.NEVER_THROW)
                else
                    nativePosixFilesService.lstat(name, StatErrorHandling.NEVER_THROW)
            return if (stat != null) UnixFileStatus(stat) else null
        } catch (e: IOException) {
            throw java.lang.IllegalStateException("unexpected exception", e)
        } finally {
            com.google.devtools.build.lib.util.Blocker.end(comp)
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_STAT, name)
        }
    }

    override fun exists(path: PathFragment, followSymlinks: Boolean): Boolean {
        return statNullable(path, followSymlinks) != null
    }

    /**
     * Return true iff the `stat` of `path` resulted in an `ENOENT` or `ENOTDIR` error.
     */
    @Throws(IOException::class)
    override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus? {
        val name: String? = path.getPathString()
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        val comp: Any? = com.google.devtools.build.lib.util.Blocker.begin()
        try {
            val stat: Stat? =
                if (followSymlinks)
                    nativePosixFilesService.stat(name, StatErrorHandling.THROW_UNLESS_NOT_FOUND)
                else
                    nativePosixFilesService.lstat(name, StatErrorHandling.THROW_UNLESS_NOT_FOUND)
            return if (stat != null) UnixFileStatus(stat) else null
        } finally {
            com.google.devtools.build.lib.util.Blocker.end(comp)
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_STAT, name)
        }
    }

    @Throws(IOException::class)
    override fun isReadable(path: PathFragment): Boolean {
        return (stat(path, true).getPermissions() and 256) != 0
    }

    @Throws(IOException::class)
    override fun isWritable(path: PathFragment): Boolean {
        return (stat(path, true).getPermissions() and 128) != 0
    }

    @Throws(IOException::class)
    override fun isExecutable(path: PathFragment): Boolean {
        return (stat(path, true).getPermissions() and 64) != 0
    }

    /**
     * Adds or remove the bits specified in "permissionBits" to the permission mask of the file
     * specified by `path`. If the argument `add` is true, the specified permissions are
     * added, otherwise they are removed.
     * 
     * @throws IOException if there was an error writing the file's metadata
     */
    @Throws(IOException::class)
    private fun modifyPermissionBits(path: PathFragment, permissionBits: Int, add: Boolean) {
        val oldMode: Int = stat(path,  /* followSymlinks= */true).getPermissions()
        val newMode = if (add) (oldMode or permissionBits) else (oldMode and permissionBits.inv())
        chmod(path, newMode)
    }

    @Throws(IOException::class)
    override fun setReadable(path: PathFragment, readable: Boolean) {
        modifyPermissionBits(path, 256, readable)
    }

    @Throws(IOException::class)
    override fun setWritable(path: PathFragment, writable: Boolean) {
        modifyPermissionBits(path, 128, writable)
    }

    @Throws(IOException::class)
    override fun setExecutable(path: PathFragment, executable: Boolean) {
        modifyPermissionBits(path, 73, executable)
    }

    @Throws(IOException::class)
    override fun chmod(path: PathFragment, mode: Int) {
        val comp: Any? = com.google.devtools.build.lib.util.Blocker.begin()
        try {
            nativePosixFilesService.chmod(path.toString(), mode)
        } finally {
            com.google.devtools.build.lib.util.Blocker.end(comp)
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
        return com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.DARWIN
    }

    @Throws(IOException::class)
    override fun createDirectory(path: PathFragment): Boolean {
        val comp: Any? = com.google.devtools.build.lib.util.Blocker.begin()
        try {
            // Use 0777 so that the permissions can be overridden by umask(2).
            // Note: UNIX mkdir(2), FilesystemUtils.mkdir() and createDirectory all
            // have different ways of representing failure!
            if (nativePosixFilesService.mkdir(path.toString(), 511)) {
                return true // successfully created
            }
        } finally {
            com.google.devtools.build.lib.util.Blocker.end(comp)
        }

        // false => EEXIST: something is already in the way (file/dir/symlink)
        if (isDirectory(path, false)) {
            return false // directory already existed
        } else {
            throw IOException(path.toString() + " (File exists)")
        }
    }

    @Throws(IOException::class)
    override fun createDirectoryAndParents(path: PathFragment?) {
        val dirsToCreate: ArrayDeque<PathFragment?> = ArrayDeque<PathFragment?>()
        var dir: PathFragment? = path
        while (dir != null) {
            val stat: FileStatus? = statIfFound(dir,  /* followSymlinks= */true)
            if (stat != null) {
                if (stat.isDirectory()) {
                    break
                } else {
                    throw IOException(path.toString() + " (File exists)")
                }
            }
            dirsToCreate.addLast(dir)
            dir = dir.getParentDirectory()
        }
        while (!dirsToCreate.isEmpty()) {
            val unused = createDirectory(dirsToCreate.removeLast())
        }
    }

    @Throws(IOException::class)
    override fun createSymbolicLink(
        linkPath: PathFragment, targetFragment: PathFragment, type: SymlinkTargetType?
    ) {
        val comp: Any? = com.google.devtools.build.lib.util.Blocker.begin()
        try {
            nativePosixFilesService.symlink(targetFragment.getSafePathString(), linkPath.toString())
        } finally {
            com.google.devtools.build.lib.util.Blocker.end(comp)
        }
    }

    @Throws(IOException::class)
    override fun readSymbolicLink(path: PathFragment): PathFragment? {
        // Note that the default implementation of readSymbolicLinkUnchecked calls this method and thus
        // is optimal since we only make one system call in here.
        val name: String? = path.toString()
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        val comp: Any? = com.google.devtools.build.lib.util.Blocker.begin()
        try {
            val result: String? = nativePosixFilesService.readlink(name)
            if (result == null) {
                throw NotASymlinkException(path)
            }
            return PathFragment.create(result)
        } finally {
            com.google.devtools.build.lib.util.Blocker.end(comp)
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_READLINK, name)
        }
    }

    @Throws(IOException::class)
    override fun renameTo(sourcePath: PathFragment, targetPath: PathFragment) {
        val comp: Any? = com.google.devtools.build.lib.util.Blocker.begin()
        try {
            nativePosixFilesService.rename(sourcePath.toString(), targetPath.toString())
        } finally {
            com.google.devtools.build.lib.util.Blocker.end(comp)
        }
    }

    @Throws(IOException::class)
    override fun getFileSize(path: PathFragment, followSymlinks: Boolean): Long {
        return stat(path, followSymlinks).getSize()
    }

    @Throws(IOException::class)
    override fun delete(path: PathFragment): Boolean {
        val name: String? = path.toString()
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        val comp: Any? = com.google.devtools.build.lib.util.Blocker.begin()
        try {
            return nativePosixFilesService.remove(name)
        } finally {
            com.google.devtools.build.lib.util.Blocker.end(comp)
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_DELETE, name)
        }
    }

    @Throws(IOException::class)
    override fun getLastModifiedTime(path: PathFragment, followSymlinks: Boolean): Long {
        return stat(path, followSymlinks).getLastModifiedTime()
    }

    @Throws(IOException::class)
    override fun setLastModifiedTime(path: PathFragment, newTime: Long) {
        val comp: Any? = com.google.devtools.build.lib.util.Blocker.begin()
        try {
            nativePosixFilesService.utimensat(
                path.toString(), newTime == com.google.devtools.build.lib.vfs.Path.NOW_SENTINEL_TIME, newTime
            )
        } finally {
            com.google.devtools.build.lib.util.Blocker.end(comp)
        }
    }

    @Throws(IOException::class)
    override fun getxattr(path: PathFragment, name: String?, followSymlinks: Boolean): ByteArray? {
        val pathName: String? = path.toString()
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        val comp: Any? = com.google.devtools.build.lib.util.Blocker.begin()
        try {
            return if (followSymlinks)
                nativePosixFilesService.getxattr(pathName, name)
            else
                nativePosixFilesService.lgetxattr(pathName, name)
        } catch (e: java.lang.UnsupportedOperationException) {
            // getxattr() syscall is not supported by the underlying filesystem (it returned ENOTSUP).
            // Per method contract, treat this as ENODATA.
            return null
        } finally {
            com.google.devtools.build.lib.util.Blocker.end(comp)
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_XATTR, pathName)
        }
    }

    @Throws(IOException::class)
    override fun getFastDigest(path: PathFragment): ByteArray? {
        // Attempt to obtain the digest from an extended attribute attached to the file. This is much
        // faster than reading and digesting the file's contents on the fly, especially for large files.
        return if (hashAttributeName.isEmpty()) null else getxattr(path, hashAttributeName, true)
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

    @Throws(IOException::class)
    override fun createFSDependentHardLink(linkPath: PathFragment, originalPath: PathFragment) {
        val comp: Any? = com.google.devtools.build.lib.util.Blocker.begin()
        try {
            nativePosixFilesService.link(originalPath.toString(), linkPath.toString())
        } finally {
            com.google.devtools.build.lib.util.Blocker.end(comp)
        }
    }

    @Throws(IOException::class)
    override fun deleteTreesBelow(dir: PathFragment) {
        if (isDirectory(dir,  /* followSymlinks= */false)) {
            val startTime: Long = Profiler.instance().nanoTimeMaybe()
            val comp: Any? = com.google.devtools.build.lib.util.Blocker.begin()
            try {
                nativePosixFilesService.deleteTreesBelow(dir.toString())
            } finally {
                com.google.devtools.build.lib.util.Blocker.end(comp)
                Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_DELETE, dir.toString())
            }
        }
    }

    override fun getIoFile(path: PathFragment): java.io.File? {
        return java.io.File(StringEncoding.internalToPlatform(path.getPathString()))
    }

    override fun getNioPath(path: PathFragment): java.nio.file.Path? {
        return java.nio.file.Path.of(StringEncoding.internalToPlatform(path.getPathString()))
    }

    companion object {
        /** Converts from [NativePosixFilesService.Dirent.Type] to [Dirent.Type].  */
        private fun convertDirentType(type: com.google.devtools.build.lib.unix.NativePosixFilesService.Dirent.Type): com.google.devtools.build.lib.vfs.Dirent.Type {
            return when (type) {
                com.google.devtools.build.lib.unix.NativePosixFilesService.Dirent.Type.FILE -> com.google.devtools.build.lib.vfs.Dirent.Type.FILE
                com.google.devtools.build.lib.unix.NativePosixFilesService.Dirent.Type.DIRECTORY -> com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY
                com.google.devtools.build.lib.unix.NativePosixFilesService.Dirent.Type.SYMLINK -> com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK
                else -> com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN
            }
        }
    }
}
