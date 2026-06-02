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
import com.google.devtools.build.lib.vfs.SyscallCache
import com.google.devtools.build.lib.vfs.SyscallCache.DirentTypeWithSkip
import java.io.IOException

/**
 * A [SyscallCache] that delegates to a caching implementation only for paths with a
 * particular [FileSystem].
 * 
 * 
 * Any calls that pass a [Path] backed by a different [FileSystem] are routed to
 * [SyscallCache.NO_CACHE]. This can be used to ensure that only calls for the build's main
 * [FileSystem] are cached. Common alternative filesystems for which caching is wasteful
 * include:
 * 
 * 
 *  * [       ], an
 * in-memory filesystem (no real filesystem ops to save).
 *  * An [       action-scoped filesystem][com.google.devtools.build.lib.vfs.OutputService.ActionFileSystemType] where there is no potential for reuse outside a single action's
 * execution, and caching prolongs the lifetime of the instance.
 */
class SingleFileSystemSyscallCache(delegate: SyscallCache?, fs: com.google.devtools.build.lib.vfs.FileSystem?) :
    SyscallCache {
    private val delegate: SyscallCache
    private val fs: com.google.devtools.build.lib.vfs.FileSystem

    init {
        this.delegate = com.google.common.base.Preconditions.checkNotNull<SyscallCache>(delegate)
        this.fs = com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.FileSystem>(fs)
    }

    @Throws(IOException::class)
    override fun readdir(path: com.google.devtools.build.lib.vfs.Path): MutableCollection<com.google.devtools.build.lib.vfs.Dirent?>? {
        return delegateFor(path).readdir(path)
    }

    @Throws(IOException::class)
    override fun statIfFound(path: com.google.devtools.build.lib.vfs.Path, symlinks: Symlinks?): FileStatus? {
        return delegateFor(path).statIfFound(path, symlinks)
    }

    @Throws(IOException::class)
    override fun getType(path: com.google.devtools.build.lib.vfs.Path, symlinks: Symlinks?): DirentTypeWithSkip? {
        return delegateFor(path).getType(path, symlinks)
    }

    @Throws(IOException::class)
    override fun getFastDigest(path: com.google.devtools.build.lib.vfs.Path): ByteArray? {
        return delegateFor(path).getFastDigest(path)
    }

    @Throws(IOException::class)
    override fun getxattr(path: com.google.devtools.build.lib.vfs.Path, xattrName: String?): ByteArray? {
        return delegateFor(path).getxattr(path, xattrName)
    }

    @Throws(IOException::class)
    override fun getxattr(
        path: com.google.devtools.build.lib.vfs.Path,
        xattrName: String?,
        followSymlinks: Symlinks?
    ): ByteArray? {
        return delegateFor(path).getxattr(path, xattrName, followSymlinks)
    }

    override fun noteAnalysisPhaseEnded() {
        delegate.noteAnalysisPhaseEnded()
    }

    override fun clear() {
        delegate.clear()
    }

    private fun delegateFor(path: com.google.devtools.build.lib.vfs.Path): SyscallCache? {
        return if (path.getFileSystem() == fs) delegate else SyscallCache.Companion.NO_CACHE
    }
}
