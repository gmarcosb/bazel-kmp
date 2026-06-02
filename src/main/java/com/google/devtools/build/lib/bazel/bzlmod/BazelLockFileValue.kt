// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.auto.value.AutoValue
import com.google.devtools.build.lib.bazel.bzlmod.Facts
import com.google.devtools.build.lib.bazel.bzlmod.LockFileModuleExtension
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionEvalFactors
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.ryanharter.auto.value.gson.GenerateTypeAdapter

/**
 * The result of reading a lockfile. Contains the lockfile version as well as registry and module
 * extensions data (ID, usages hash, generated repos, ...).
 * 
 * 
 * Bazel maintains two separate lockfiles:
 * 
 * 
 *  * the (regular) lockfile stored as MODULE.bazel.lock under the workspace directory;
 *  * the hidden lockfile stored as MODULE.bazel.lock under the output base.
 * 
 * 
 * See the javadoc of the two [SkyKey]s for more information.
 */
@AutoValue
@GenerateTypeAdapter
abstract class BazelLockFileValue : SkyValue {
    /** Current version of the lock file  */
    abstract fun getLockFileVersion(): Int

    /** Hashes of files retrieved from registries.  */
    abstract fun getRegistryFileHashes(): com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?

    /**
     * Selected module versions that are known to be yanked (and hence must have been explicitly
     * allowed by the user).
     */
    abstract fun getSelectedYankedVersions(): com.google.common.collect.ImmutableMap<ModuleKey?, String?>?

    /** Mapping the extension id to the module extension data  */
    abstract fun getModuleExtensions(): com.google.common.collect.ImmutableMap<ModuleExtensionId?, com.google.common.collect.ImmutableMap<ModuleExtensionEvalFactors?, LockFileModuleExtension?>?>?

    /**
     * Per-extension perpetually true facts that are passed to extensions at evaluation time without
     * any invalidation (except for [.getFactsVersions]).
     * 
     * 
     * These are not stored in LockFileModuleExtension as they are intended to be independent of
     * the eval factors.
     */
    abstract fun getFacts(): com.google.common.collect.ImmutableMap<ModuleExtensionId?, Facts?>?

    /**
     * The `factsVersion` parameter that `module_extension` declared at the time the
     * corresponding [.getFacts] entry was written. Compared against the current value before
     * an extension runs; on mismatch the persisted facts are discarded and the extension is invoked
     * with empty facts. Missing entries default to version 0.
     * 
     * 
     * This is not stored in Facts to ensure a legible, mergeable JSON representation for facts
     * that is only as indented as absolutely necessary.
     */
    abstract fun getFactsVersions(): com.google.common.collect.ImmutableMap<ModuleExtensionId?, Int?>?

    abstract fun toBuilder(): Builder?

    /** Builder type for [BazelLockFileValue].  */
    @AutoValue.Builder
    abstract class Builder {
        abstract fun setLockFileVersion(value: Int): Builder?

        abstract fun setRegistryFileHashes(value: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?): Builder?

        abstract fun setSelectedYankedVersions(value: com.google.common.collect.ImmutableMap<ModuleKey?, String?>?): Builder?

        abstract fun setModuleExtensions(
            value: com.google.common.collect.ImmutableMap<ModuleExtensionId?, com.google.common.collect.ImmutableMap<ModuleExtensionEvalFactors?, LockFileModuleExtension?>?>?
        ): Builder?

        abstract fun setFacts(value: com.google.common.collect.ImmutableMap<ModuleExtensionId?, Facts?>?): Builder?

        abstract fun setFactsVersions(value: com.google.common.collect.ImmutableMap<ModuleExtensionId?, Int?>?): Builder?

        abstract fun build(): BazelLockFileValue?
    }

    companion object {
        // NOTE: See "HACK" note on 7.x:
        // https://cs.opensource.google/bazel/bazel/+/release-7.3.0:src/main/java/com/google/devtools/build/lib/bazel/bzlmod/BazelLockFileModule.java;l=120-127;drc=5f5355b75c7c93fba1e15f6658f308953f4baf51
        // While this hack exists on 7.x, lockfile version increments should be done 2 at a time (i.e.
        // keep this number even).
        const val LOCK_FILE_VERSION: Int = 28

        /** A valid empty lockfile.  */
        val EMPTY_LOCKFILE: BazelLockFileValue? = builder().build()

        /**
         * The (regular) lockfile, stored as MODULE.bazel.lock under the workspace directory. This file is
         * visible to the user and meant to be committed to source control. Thus, it
         * 
         * 
         *  * should only contain the minimal amount of information necessary to make module resolution
         * and module extension evaluation deterministic;
         *  * should be as deterministic as possible to reduce the risk of merge conflicts.
         * 
         */
        @SerializationConstant
        val KEY: SkyKey = object : SkyKey {
            override fun functionName(): SkyFunctionName? {
                return SkyFunctions.BAZEL_LOCK_FILE
            }

            override fun toString(): String {
                return "BazelLockFileValue.KEY"
            }
        }

        /**
         * The hidden lockfile, stored as MODULE.bazel.lock under the output base. This file is not
         * visible to the user and is only removed on a `bazel clean --expunge`, similar to the
         * persistent action cache. Thus, it
         * 
         * 
         *  * should only contain information known to be correct indefinitely and never needs to be
         * invalidated for a correct build;
         *  * is not subject to the same space and mergeability constraints as the regular lockfile and
         * can thus contain more extensive information;
         *  * may differ between users and checkouts of the same project as long as it doesn't affect
         * the outcome of the build, with one exception: the build may fail with an error due to
         * additional information in the hidden lockfile, e.g. if a module in a registry is changed
         * retroactively and thus causes a mismatch with the hash in the persistent lockfile.
         * 
         */
        @SerializationConstant
        val HIDDEN_KEY: SkyKey = object : SkyKey {
            override fun functionName(): SkyFunctionName? {
                return SkyFunctions.BAZEL_LOCK_FILE
            }

            override fun toString(): String {
                return "BazelLockFileValue.HIDDEN_KEY"
            }
        }

        fun builder(): Builder {
            return Builder()
                .setLockFileVersion(BazelLockFileValue.Companion.LOCK_FILE_VERSION)
                .setRegistryFileHashes(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .setSelectedYankedVersions(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .setModuleExtensions(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .setFacts(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .setFactsVersions(com.google.common.collect.ImmutableMap.of<K?, V?>())!!
        }
    }
}
