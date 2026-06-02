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

/** A simple SkyFunction that creates a [Registry] with a given URL.  */
class RegistryFunction(registryFactory: RegistryFactory, workspaceRoot: com.google.devtools.build.lib.vfs.Path) :
    SkyFunction {
    private val registryFactory: RegistryFactory
    private val workspaceRoot: com.google.devtools.build.lib.vfs.Path

    init {
        this.registryFactory = registryFactory
        this.workspaceRoot = workspaceRoot
    }

    @Throws(java.lang.InterruptedException::class, RegistryException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val lockfileMode: LockfileMode? = BazelLockFileFunction.Companion.LOCKFILE_MODE.get(env)
        val vendorDir: java.util.Optional<com.google.devtools.build.lib.vfs.Path?>? =
            RepositoryDirectoryValue.VENDOR_DIRECTORY.get(env)

        if (lockfileMode == LockfileMode.REFRESH) {
            LAST_INVALIDATION.get(env)
        }

        val lockfile: BazelLockFileValue? = env.getValue(BazelLockFileValue.Companion.KEY) as BazelLockFileValue?
        if (lockfile == null) {
            return null
        }

        val key: com.google.devtools.build.lib.bazel.bzlmod.RegistryKey =
            skyKey.argument() as com.google.devtools.build.lib.bazel.bzlmod.RegistryKey
        try {
            return registryFactory.createRegistry(
                key.url.replace("%workspace%", workspaceRoot.getPathString()),
                lockfileMode,
                lockfile.getRegistryFileHashes(),
                lockfile.getSelectedYankedVersions(),
                vendorDir,
                MODULE_MIRRORS.get(env).getOrDefault(key.url, com.google.common.collect.ImmutableSet.of<String?>())
            )
        } catch (e: URISyntaxException) {
            throw RegistryException(
                withCauseAndMessage(
                    FailureDetails.ExternalDeps.Code.INVALID_REGISTRY_URL,
                    e,
                    "Invalid registry URL: %s",
                    key.url
                )
            )
        }
    }

    internal class RegistryException(cause: ExternalDepsException?) : SkyFunctionException(cause, Transience.TRANSIENT)
    companion object {
        /**
         * Set to the current time in [com.google.devtools.build.lib.bazel.BazelRepositoryModule]
         * after [.INVALIDATION_INTERVAL] has passed. This is used to refresh the mutable registry
         * contents cached in memory from time to time.
         */
        @kotlin.jvm.JvmField
        val LAST_INVALIDATION: Precomputed<Instant?> = Precomputed<Instant?>("last_registry_invalidation")

        @kotlin.jvm.JvmField
        val MODULE_MIRRORS: Precomputed<com.google.common.collect.ImmutableMap<String?, com.google.common.collect.ImmutableSet<String?>?>?> =
            Precomputed<com.google.common.collect.ImmutableMap<String?, com.google.common.collect.ImmutableSet<String?>?>?>(
                "module_mirrors"
            )

        /**
         * The interval after which the mutable registry contents cached in memory should be refreshed.
         */
        @kotlin.jvm.JvmField
        val INVALIDATION_INTERVAL: java.time.Duration? = java.time.Duration.ofHours(1)
    }
}
