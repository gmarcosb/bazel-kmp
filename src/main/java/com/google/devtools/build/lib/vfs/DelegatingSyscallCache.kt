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

/** [SyscallCache] that delegates to an injectable one.  */
class DelegatingSyscallCache : SyscallCache {
    private var delegate: SyscallCache = SyscallCache.Companion.NO_CACHE

    fun setDelegate(syscallCache: SyscallCache?) {
        this.delegate = com.google.common.base.Preconditions.checkNotNull<SyscallCache>(syscallCache)
    }

    @Throws(IOException::class)
    override fun readdir(path: com.google.devtools.build.lib.vfs.Path?): MutableCollection<com.google.devtools.build.lib.vfs.Dirent?>? {
        return delegate.readdir(path)
    }

    @Throws(IOException::class)
    override fun statIfFound(path: com.google.devtools.build.lib.vfs.Path?, symlinks: Symlinks?): FileStatus? {
        return delegate.statIfFound(path, symlinks)
    }

    @Throws(IOException::class)
    override fun getType(path: com.google.devtools.build.lib.vfs.Path?, symlinks: Symlinks?): DirentTypeWithSkip? {
        return delegate.getType(path, symlinks)
    }

    @Throws(IOException::class)
    override fun getFastDigest(path: com.google.devtools.build.lib.vfs.Path): ByteArray? {
        return delegate.getFastDigest(path)
    }

    @Throws(IOException::class)
    override fun getxattr(path: com.google.devtools.build.lib.vfs.Path, xattrName: String?): ByteArray? {
        return delegate.getxattr(path, xattrName)
    }

    @Throws(IOException::class)
    override fun getxattr(
        path: com.google.devtools.build.lib.vfs.Path,
        xattrName: String?,
        followSymlinks: Symlinks?
    ): ByteArray? {
        return delegate.getxattr(path, xattrName, followSymlinks)
    }

    override fun noteAnalysisPhaseEnded() {
        delegate.noteAnalysisPhaseEnded()
    }

    override fun clear() {
        delegate.clear()
    }
}
