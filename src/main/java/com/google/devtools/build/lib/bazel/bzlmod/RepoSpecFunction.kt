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

import com.google.devtools.build.lib.server.FailureDetails

/**
 * A simple SkyFunction that computes a [RepoSpec] for the given [InterimModule] by
 * fetching required information from its [Registry].
 */
class RepoSpecFunction : SkyFunction {
    private var downloadManager: DownloadManager? = null

    @Throws(java.lang.InterruptedException::class, RepoSpecException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val key: RepoSpecKey = skyKey.argument() as RepoSpecKey

        val registry: com.google.devtools.build.lib.bazel.bzlmod.Registry? =
            env.getValue(com.google.devtools.build.lib.bazel.bzlmod.RegistryKey.Companion.create(key.registryUrl)) as com.google.devtools.build.lib.bazel.bzlmod.Registry?
        if (registry == null) {
            return null
        }
        val moduleFileValue: ModuleFileValue? =
            env.getValue(ModuleFileValue.Companion.key(key.moduleKey)) as ModuleFileValue?
        if (moduleFileValue == null) {
            return null
        }

        val downloadEvents: com.google.devtools.build.lib.events.StoredEventHandler =
            com.google.devtools.build.lib.events.StoredEventHandler()
        val repoSpec: RepoSpec?
        try {
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .profile(
                    com.google.devtools.build.lib.profiler.ProfilerTask.BZLMOD,
                    java.util.function.Supplier { "compute repo spec: " + key.moduleKey }).use { c ->
                    repoSpec =
                        registry.getRepoSpec(
                            key.moduleKey,
                            moduleFileValue.registryFileHashes(),
                            downloadEvents,
                            this.downloadManager
                        )
                }
        } catch (e: IOException) {
            throw RepoSpecException(
                withCauseAndMessage(
                    FailureDetails.ExternalDeps.Code.ERROR_ACCESSING_REGISTRY,
                    e,
                    "Unable to get module repo spec for %s from registry",
                    key.moduleKey
                )
            )
        }
        return RepoSpecValue.Companion.create(
            repoSpec, RegistryFileDownloadEvent.Companion.collectToMap(downloadEvents.getPosts())
        )
    }

    fun setDownloadManager(downloadManager: DownloadManager?) {
        this.downloadManager = downloadManager
    }

    internal class RepoSpecException(cause: ExternalDepsException?) : SkyFunctionException(cause, Transience.TRANSIENT)
}
