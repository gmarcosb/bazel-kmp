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

import com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsValue
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager
import com.google.devtools.build.skyframe.SkyFunction
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import java.io.IOException

/**
 * A simple SkyFunction that fetches the yanked versions for a given module from its [ ].
 */
class YankedVersionsFunction : SkyFunction {
    private var downloadManager: DownloadManager? = null

    @Throws(java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val key: com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsValue.Key =
            skyKey.argument() as com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsValue.Key

        val registry: com.google.devtools.build.lib.bazel.bzlmod.Registry? =
            env.getValue(com.google.devtools.build.lib.bazel.bzlmod.RegistryKey.Companion.create(key.registryUrl)) as com.google.devtools.build.lib.bazel.bzlmod.Registry?
        if (registry == null) {
            return null
        }

        try {
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .profile(
                    com.google.devtools.build.lib.profiler.ProfilerTask.BZLMOD,
                    java.util.function.Supplier { "getting yanked versions: " + key.moduleName }).use { c ->
                    return YankedVersionsValue.Companion.create(
                        registry.getYankedVersions(key.moduleName, env.getListener(), downloadManager)
                    )
                }
        } catch (e: IOException) {
            env.getListener()
                .handle(
                    com.google.devtools.build.lib.events.Event.warn(
                        java.lang.String.format(
                            "Could not read metadata file for module %s from registry %s: %s",
                            key.moduleName, key.registryUrl, e.getMessage()
                        )
                    )
                )
            // This is failing open: If we can't read the metadata file, we allow yanked modules to be
            // fetched.
            return YankedVersionsValue.Companion.create(java.util.Optional.empty<com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.bazel.bzlmod.Version?, String?>?>())
        }
    }

    fun setDownloadManager(downloadManager: DownloadManager?) {
        this.downloadManager = downloadManager
    }
}
