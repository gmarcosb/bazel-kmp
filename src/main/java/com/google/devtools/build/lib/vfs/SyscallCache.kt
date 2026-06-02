// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.vfs.FileStatus
import com.google.devtools.build.lib.vfs.Symlinks
import com.google.devtools.build.lib.vfs.UnixGlob.FilesystemOps
import com.google.devtools.build.lib.vfs.XattrProvider
import java.io.IOException

/**
 * Centralized point to perform filesystem calls, to promote caching. Ideally all filesystem
 * operations would be cached in Skyframe, but even then, implementations of this interface may do
 * batch operations and prefetching to improve performance.
 * 
 * 
 * There is typically one [SyscallCache] instance in effect for the lifetime of the Bazel
 * server, set in [com.google.devtools.build.lib.runtime.WorkspaceBuilder]. Between commands,
 * [.clear] is called to drop cached data from the previous command.
 * 
 * 
 * See the note in [XattrProvider] about caching in implementations. Do not call the
 * methods in this interface on files that may change *during* a build, like outputs or
 * external repository files. Calling these methods on source files is allowed.
 */
interface SyscallCache : XattrProvider, FilesystemOps {
    @Throws(IOException::class)
    override fun statIfFound(path: com.google.devtools.build.lib.vfs.Path?): FileStatus? {
        return statIfFound(path, Symlinks.FOLLOW)
    }

    /** Returns the stat() for the given path, or null.  */
    @Throws(IOException::class)
    fun statIfFound(path: com.google.devtools.build.lib.vfs.Path?, symlinks: Symlinks?): FileStatus?

    /**
     * Returns the type of a specific file. This may be answered using stat() or readdir(). Returns
     * null if the path does not exist. Returns [DirentTypeWithSkip.FILESYSTEM_OP_SKIPPED] if
     * cache had no data for path and chose not to do filesystem access to determine the type. Callers
     * should call [.statIfFound] and then [.statusToDirentType] if needed in that case.
     */
    @Throws(IOException::class)
    fun getType(path: com.google.devtools.build.lib.vfs.Path?, symlinks: Symlinks?): DirentTypeWithSkip?

    /** Called before each build. Implementations should flush their caches at that point.  */
    fun clear()

    /**
     * Called at the end of the analysis phase (if not doing merged analysis/execution). Cache may
     * choose to drop some data then.
     */
    fun noteAnalysisPhaseEnded() {
        clear()
    }

    /**
     * A [Dirent.Type] with an additional element signifying that the type is unknown because
     * this [SyscallCache] implementation skipped filesystem access.
     */
    enum class DirentTypeWithSkip(type: com.google.devtools.build.lib.vfs.Dirent.Type?) {
        FILE(com.google.devtools.build.lib.vfs.Dirent.Type.FILE),
        DIRECTORY(com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY),
        SYMLINK(com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK),
        UNKNOWN(com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN),
        FILESYSTEM_OP_SKIPPED(null);

        private val type: com.google.devtools.build.lib.vfs.Dirent.Type?

        init {
            this.type = type
        }

        fun getType(): com.google.devtools.build.lib.vfs.Dirent.Type? {
            com.google.common.base.Preconditions.checkState(
                this != DirentTypeWithSkip.FILESYSTEM_OP_SKIPPED,
                "No type if filesystem op skipped"
            )
            return type
        }

        companion object {
            fun of(type: com.google.devtools.build.lib.vfs.Dirent.Type?): DirentTypeWithSkip? {
                if (type == null) {
                    return null
                }
                when (type) {
                    com.google.devtools.build.lib.vfs.Dirent.Type.FILE -> return DirentTypeWithSkip.FILE
                    com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY -> return DirentTypeWithSkip.DIRECTORY
                    com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK -> return DirentTypeWithSkip.SYMLINK
                    com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN -> return DirentTypeWithSkip.UNKNOWN
                }
                throw java.lang.IllegalStateException("Got unrecognized type " + type)
            }
        }
    }

    companion object {
        fun statusToDirentType(status: FileStatus?): com.google.devtools.build.lib.vfs.Dirent.Type? {
            if (status == null) {
                return null
            } else if (status.isFile()) {
                return com.google.devtools.build.lib.vfs.Dirent.Type.FILE
            } else if (status.isDirectory()) {
                return com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY
            } else if (status.isSymbolicLink()) {
                return com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK
            }
            return com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN
        }

        @kotlin.jvm.JvmField
        val NO_CACHE: SyscallCache = object : SyscallCache {
            @Throws(IOException::class)
            override fun readdir(path: com.google.devtools.build.lib.vfs.Path): MutableCollection<com.google.devtools.build.lib.vfs.Dirent?>? {
                return path.readdir(Symlinks.NOFOLLOW)
            }

            @Throws(IOException::class)
            override fun statIfFound(path: com.google.devtools.build.lib.vfs.Path, symlinks: Symlinks): FileStatus? {
                return path.statIfFound(symlinks)
            }

            override fun getType(
                path: com.google.devtools.build.lib.vfs.Path?,
                symlinks: Symlinks?
            ): DirentTypeWithSkip? {
                return DirentTypeWithSkip.FILESYSTEM_OP_SKIPPED
            }

            override fun clear() {}
        }
    }
}
