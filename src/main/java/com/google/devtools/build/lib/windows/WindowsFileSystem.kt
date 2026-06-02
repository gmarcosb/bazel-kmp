// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.windows

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/** File system implementation for Windows.  */
@ThreadSafe
class WindowsFileSystem(hashFunction: DigestHashFunction?, private val createSymbolicLinks: Boolean) :
    JavaIoFileSystem(hashFunction) {
    override fun getFileSystemType(path: PathFragment?): String {
        // TODO(laszlocsomor): implement this properly, i.e. actually query this information from
        // somewhere (java.nio.Filesystem? System.getProperty? implement JNI method and use WinAPI?).
        return "ntfs"
    }

    @Throws(IOException::class)
    override fun delete(path: PathFragment): Boolean {
        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        try {
            return WindowsFileOperations.deletePath(
                StringEncoding.internalToPlatform(path.getPathString())
            )
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
        } finally {
            Profiler.instance().logSimpleTask(startTime, ProfilerTask.VFS_DELETE, path.getPathString())
        }
    }

    @Throws(IOException::class)
    override fun createSymbolicLink(
        linkPath: PathFragment, targetFragment: PathFragment, type: SymlinkTargetType?
    ) {
        val targetPath: PathFragment =
            if (targetFragment.isAbsolute())
                targetFragment
            else
                linkPath.getParentDirectory().getRelative(targetFragment)

        val stat: FileStatus? = statIfFound(targetPath,  /* followSymlinks= */true)
        val existingFile = stat != null && stat.isFile()
        val existingDirectory = stat != null && stat.isDirectory()

        try {
            val link: java.nio.file.Path? = getNioPath(linkPath)
            val target: java.nio.file.Path? = getNioPath(targetPath)

            if (!createSymbolicLinks && existingFile) {
                // If symlinks aren't enabled and the target is an existing file, fall back to a copy.
                java.nio.file.Files.copy(target, link)
            } else if (createSymbolicLinks
                && (existingFile || (!existingDirectory && type != SymlinkTargetType.DIRECTORY))
            ) {
                // If symlinks are enabled and the target is not an existing or future directory, create a
                // symlink.
                WindowsFileOperations.createSymlink(link.toString(), target.toString())
            } else {
                // Otherwise, create a junction.
                WindowsFileOperations.createJunction(link.toString(), target.toString())
            }
        } catch (e: IOException) {
            throw com.google.devtools.build.lib.vfs.FileSystem.Companion.translateNioToIoException(linkPath, e)
        }
    }

    @Throws(IOException::class)
    override fun readSymbolicLink(path: PathFragment): PathFragment? {
        val nioPath: java.nio.file.Path? = getNioPath(path)
        return PathFragment.Companion.create(
            StringEncoding.platformToInternal(
                WindowsFileOperations.readSymlinkOrJunction(nioPath.toString())
            )
        )
    }

    override fun supportsSymbolicLinksNatively(path: PathFragment?): Boolean {
        return createSymbolicLinks
    }

    override fun mayBeCaseOrNormalizationInsensitive(): Boolean {
        return true
    }

    override fun fileIsSymbolicLink(file: java.nio.file.Path): Boolean {
        try {
            if (com.google.devtools.build.lib.windows.WindowsFileSystem.Companion.isSymlinkOrJunction(file)) {
                return true
            }
        } catch (e: IOException) {
            // Did not work, try in another way
        }
        return super.fileIsSymbolicLink(file)
    }

    @Throws(IOException::class)
    override fun stat(path: PathFragment, followSymlinks: Boolean): FileStatus {
        val nioPath: java.nio.file.Path? = getNioPath(path)
        val attributes: DosFileAttributes
        try {
            attributes =
                com.google.devtools.build.lib.windows.WindowsFileSystem.Companion.getAttribs(nioPath, followSymlinks)
        } catch (e: IOException) {
            throw FileNotFoundException(path.toString() + com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_NO_SUCH_FILE_OR_DIR)
        }

        val status: FileStatus =
            object : FileStatus {
                @kotlin.concurrent.Volatile
                var isSymbolicLink: Boolean? = null // null if not yet known

                @kotlin.concurrent.Volatile
                var lastChangeTime: Long = -1

                val isFile: Boolean
                    get() = !isSymbolicLink() && (attributes.isRegularFile() || this.isSpecialFile)

                val isSpecialFile: Boolean
                    get() =// attributes.isOther() returns false for symlinks but returns true for junctions.
                    // Bazel treats junctions like symlinks. So let's return false here for junctions.
                        // This fixes https://github.com/bazelbuild/bazel/issues/9176
                        !isSymbolicLink() && attributes.isOther()

                val isDirectory: Boolean
                    get() = !isSymbolicLink() && attributes.isDirectory()

                override fun isSymbolicLink(): Boolean {
                    if (isSymbolicLink == null) {
                        isSymbolicLink = !followSymlinks && fileIsSymbolicLink(nioPath)
                    }
                    return isSymbolicLink!!
                }

                val size: Long
                    get() = attributes.size()

                val lastModifiedTime: Long
                    get() = attributes.lastModifiedTime().toMillis()

                @Throws(IOException::class)
                override fun getLastChangeTime(): Long {
                    if (lastChangeTime == -1L) {
                        lastChangeTime =
                            WindowsFileOperations.getLastChangeTime(
                                getNioPath(path).toString(), followSymlinks
                            )
                    }
                    return lastChangeTime
                }

                val nodeId: Long
                    get() =// TODO(bazel-team): Consider making use of attributes.fileKey().
                        -1

                val permissions: Int
                    get() =// Files on Windows are implicitly readable and executable.
                        365 or (if (attributes.isReadOnly()) 0 else 128)
            }

        return status
    }

    override fun isSymbolicLink(path: PathFragment): Boolean {
        return fileIsSymbolicLink(getNioPath(path))
    }

    override fun isDirectory(path: PathFragment, followSymlinks: Boolean): Boolean {
        if (!followSymlinks) {
            try {
                if (com.google.devtools.build.lib.windows.WindowsFileSystem.Companion.isSymlinkOrJunction(
                        getNioPath(
                            path
                        )
                    )
                ) {
                    return false
                }
            } catch (e: IOException) {
                return false
            }
        }
        return super.isDirectory(path, followSymlinks)
    }

    override fun setReadable(path: PathFragment?, readable: Boolean) {
        // Windows does not have a notion of readable files.
        // https://github.com/openjdk/jdk/blob/e52a2aeeacaeb26c801b6e31f8e67e61b1ea2de3/src/java.base/windows/native/libjava/WinNTFileSystem_md.c#L473-L476
    }

    override fun setExecutable(path: PathFragment?, executable: Boolean) {
        // Windows does not have a notion of executable files.
        // https://github.com/openjdk/jdk/blob/e52a2aeeacaeb26c801b6e31f8e67e61b1ea2de3/src/java.base/windows/native/libjava/WinNTFileSystem_md.c#L473-L476
    }

    @Throws(IOException::class)
    override fun setWritable(path: PathFragment, writable: Boolean) {
        // Windows does not have a notion of read-only directories.
        // See https://learn.microsoft.com/en-us/windows/win32/fileio/file-attribute-constants.
        // JavaIoFileSystem#setWritable(dir, true) would throw, so reimplement it here as a no-op.
        if (isDirectory(path,  /* followSymlinks= */true)) {
            return
        }
        super.setWritable(path, writable)
    }

    companion object {
        val NO_OPTIONS: Array<LinkOption?> = arrayOfNulls<LinkOption>(0)
        val NO_FOLLOW: Array<LinkOption?> = arrayOf<LinkOption>(LinkOption.NOFOLLOW_LINKS)

        @kotlin.jvm.JvmStatic
        fun symlinkOpts(followSymlinks: Boolean): Array<LinkOption?> {
            return if (followSymlinks) com.google.devtools.build.lib.windows.WindowsFileSystem.Companion.NO_OPTIONS else com.google.devtools.build.lib.windows.WindowsFileSystem.Companion.NO_FOLLOW
        }

        /**
         * Returns true if the path refers to a directory junction, directory symlink, or regular symlink.
         * 
         * 
         * Directory junctions are symbolic links created with "mklink /J" where the target is a
         * directory or another directory junction. Directory junctions can be created without any user
         * privileges.
         * 
         * 
         * Directory symlinks are symbolic links created with "mklink /D" where the target is a
         * directory or another directory symlink. Note that directory symlinks can only be created by
         * Administrators.
         * 
         * 
         * Normal symlinks are symbolic links created with "mklink". Normal symlinks should not point
         * at directories, because even though "mklink" can create the link, it will not be a functional
         * one (the linked directory's contents cannot be listed). Only Administrators may create regular
         * symlinks.
         * 
         * 
         * This method returns true for all three types as long as their target is a directory (even if
         * they are dangling), though only directory junctions and directory symlinks are useful.
         */
        @com.google.common.annotations.VisibleForTesting
        @Throws(IOException::class)
        fun isSymlinkOrJunction(file: java.nio.file.Path): Boolean {
            return WindowsFileOperations.isSymlinkOrJunction(file.toString())
        }

        @Throws(IOException::class)
        private fun getAttribs(file: java.nio.file.Path, followSymlinks: Boolean): DosFileAttributes {
            return java.nio.file.Files.readAttributes<DosFileAttributes>(
                file,
                DosFileAttributes::class.java,
                *com.google.devtools.build.lib.windows.WindowsFileSystem.Companion.symlinkOpts(followSymlinks)
            )
        }
    }
}
