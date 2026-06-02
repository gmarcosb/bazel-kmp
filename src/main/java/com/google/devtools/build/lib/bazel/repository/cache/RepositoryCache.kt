// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository.cache

import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache
import com.google.devtools.build.lib.bazel.repository.cache.LocalRepoContentsCache

/**
 * A cache directory related to repositories, containing both the [DownloadCache] and the
 * [LocalRepoContentsCache].
 */
class RepositoryCache {
    private val downloadCache: DownloadCache
    private val repoContentsCache: LocalRepoContentsCache

    private var path: com.google.devtools.build.lib.vfs.Path? = null

    init {
        downloadCache = DownloadCache()
        repoContentsCache = LocalRepoContentsCache()
    }

    fun setPath(path: com.google.devtools.build.lib.vfs.Path?) {
        this.path = path
        if (path != null) {
            downloadCache.setPath(path.getRelative(CAS_DIR))
            repoContentsCache.setPath(path.getRelative(CONTENTS_DIR))
        } else {
            downloadCache.setPath(null)
            repoContentsCache.setPath(null)
        }
    }

    fun getDownloadCache(): DownloadCache {
        return downloadCache
    }

    fun getRepoContentsCache(): LocalRepoContentsCache {
        return repoContentsCache
    }

    fun getPath(): com.google.devtools.build.lib.vfs.Path? {
        return path
    }

    companion object {
        // Repository cache subdirectories
        private const val CAS_DIR = "content_addressable"
        private const val CONTENTS_DIR = "contents"
    }
}
