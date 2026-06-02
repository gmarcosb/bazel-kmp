// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.includescanning

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.google.common.util.concurrent.Uninterruptibles
import com.google.devtools.build.lib.actions.ArtifactFactory
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.vfs.Path

/**
 * Cache to store file existence status for include paths. Only paths that are considered immutable
 * for the duration of the build (any path outside of blaze-out directory will satisfy that
 * criteria) are cached. This information is used by LegacyIncludeScanner class.
 */
@ThreadSafety.ThreadSafe
internal class PathExistenceCache(private val execRoot: Path, artifactFactory: ArtifactFactory) {
    private val artifactFactory: ArtifactFactory

    private val fileExistenceCache: MutableMap<PathFragment?, ListenableFuture<Boolean?>?> =
        ConcurrentHashMap<PathFragment?, ListenableFuture<Boolean?>?>()
    private val directoryExistenceCache: MutableMap<PathFragment?, ListenableFuture<Boolean?>?> =
        ConcurrentHashMap<PathFragment?, ListenableFuture<Boolean?>?>()

    init {
        this.artifactFactory = artifactFactory
    }

    /** Returns true if given path exists and is a file, false otherwise.  */
    fun fileExists(execPath: PathFragment?, isSource: Boolean): Boolean {
        // This is not using computeIfAbsent() as that can lead to substantial contention. As per the
        // CompactHashMap documentation, the computation for computeIfAbsent() "should be short and
        // simple", which file stat'ing is not.
        val newFuture = SettableFuture.create<Boolean?>()
        var existingFuture = fileExistenceCache.putIfAbsent(execPath, newFuture)
        if (existingFuture == null) {
            existingFuture = newFuture
            val path: Path =
                if (isSource)
                    artifactFactory.getPathFromSourceExecPath(execRoot, execPath)
                else
                    execRoot.getRelative(execPath)
            newFuture.set(path.isFile())
        }
        try {
            return Uninterruptibles.getUninterruptibly<Boolean?>(existingFuture)!!
        } catch (e: ExecutionException) {
            throw AssertionError("Unexpected ExecutionException", e)
        }
    }

    /** Returns true if given path exists and is a directory, false otherwise.  */
    fun directoryExists(execPath: PathFragment?): Boolean {
        // Like for fileExists(), do not use computeIfAbsent() to avoid contention (see comment there).
        val newFuture = SettableFuture.create<Boolean?>()
        var existingFuture = directoryExistenceCache.putIfAbsent(execPath, newFuture)
        if (existingFuture == null) {
            existingFuture = newFuture
            val path: Path = artifactFactory.getPathFromSourceExecPath(execRoot, execPath)
            newFuture.set(path.isDirectory())
        }
        try {
            return Uninterruptibles.getUninterruptibly<Boolean?>(existingFuture)!!
        } catch (e: ExecutionException) {
            throw AssertionError("Unexpected ExecutionException", e)
        }
    }
}
