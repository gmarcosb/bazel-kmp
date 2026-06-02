// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.ModuleFile
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.RepoSpec
import com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsValue
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager
import com.google.devtools.build.skyframe.NotComparableSkyValue
import java.io.IOException

/** A database where module metadata is stored.  */
interface Registry : NotComparableSkyValue {
    /** The URL that uniquely identifies the registry.  */
    val url: String?

    /** Thrown when a file is not found in the registry.  */
    class NotFoundException(message: String?) : java.lang.Exception(message)

    /**
     * Retrieves the contents of the module file of the module identified by `key` from the
     * registry.
     * 
     * @throws NotFoundException if the module file is not found in the registry
     */
    @Throws(IOException::class, java.lang.InterruptedException::class, NotFoundException::class)
    fun getModuleFile(
        key: ModuleKey?,
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler?,
        downloadManager: DownloadManager?
    ): ModuleFile?

    /**
     * Retrieves the [RepoSpec] object that indicates how the contents of the module identified
     * by `key` should be materialized as a repo.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun getRepoSpec(
        key: ModuleKey?,
        moduleFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?,
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler?,
        downloadManager: DownloadManager?
    ): RepoSpec?

    /**
     * Retrieves yanked versions of the module identified by `key.getName()` from the registry.
     * Returns `Optional.empty()` when the information is not found in the registry.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun getYankedVersions(
        moduleName: String?,
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler?,
        downloadManager: DownloadManager?
    ): java.util.Optional<com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.bazel.bzlmod.Version?, String?>?>?

    /**
     * Returns the yanked versions information, limited to the given selected module version, purely
     * based on the lockfile (if possible).
     */
    fun tryGetYankedVersionsFromLockfile(selectedModuleKey: ModuleKey?): java.util.Optional<YankedVersionsValue?>?
}
