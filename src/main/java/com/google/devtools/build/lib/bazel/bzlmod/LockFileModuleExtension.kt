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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.auto.value.AutoValue
import com.google.devtools.build.lib.bazel.bzlmod.LockfileModuleExtensionMetadata
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionEvalFactors
import com.google.devtools.build.lib.bazel.bzlmod.RepoSpec
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput.WithValue
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.ryanharter.auto.value.gson.GenerateTypeAdapter

/**
 * This object serves as a container for the transitive digest (obtained from transitive .bzl files)
 * and the generated repositories from evaluating a module extension. Its purpose is to store this
 * information within the lockfile.
 */
@AutoValue
@GenerateTypeAdapter
abstract class LockFileModuleExtension {
    abstract fun getBzlTransitiveDigest(): ByteArray?

    abstract fun getUsagesDigest(): ByteArray?

    abstract fun getRecordedInputs(): com.google.common.collect.ImmutableList<WithValue?>?

    abstract fun getGeneratedRepoSpecs(): com.google.common.collect.ImmutableMap<String?, RepoSpec?>?

    abstract fun getModuleExtensionMetadata(): java.util.Optional<LockfileModuleExtensionMetadata?>?

    fun isReproducible(): Boolean {
        return getModuleExtensionMetadata()
            .map<Boolean?>(java.util.function.Function { obj: LockfileModuleExtensionMetadata? -> obj.getReproducible() })
            .orElse(false)
    }

    /** Builder type for [LockFileModuleExtension].  */
    @AutoValue.Builder
    abstract class Builder {
        abstract fun setBzlTransitiveDigest(digest: ByteArray?): Builder?

        abstract fun setUsagesDigest(digest: ByteArray?): Builder?

        abstract fun setRecordedInputs(value: com.google.common.collect.ImmutableList<WithValue?>?): Builder?

        abstract fun setGeneratedRepoSpecs(value: com.google.common.collect.ImmutableMap<String?, RepoSpec?>?): Builder?

        abstract fun setModuleExtensionMetadata(
            value: java.util.Optional<LockfileModuleExtensionMetadata?>?
        ): Builder?

        abstract fun build(): LockFileModuleExtension?
    }

    /**
     * A [LockFileModuleExtension] together with its [ModuleExtensionEvalFactors],
     * comprising a single lockfile entry for a certain extension.
     */
    @AutoCodec
    class WithFactors(extensionFactors: ModuleExtensionEvalFactors?, moduleExtension: LockFileModuleExtension?) {
        val extensionFactors: ModuleExtensionEvalFactors?
        val moduleExtension: LockFileModuleExtension?

        init {
            this.extensionFactors = extensionFactors
            this.moduleExtension = moduleExtension
        }
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return Builder()
                .setModuleExtensionMetadata(java.util.Optional.empty<T?>())!!
        }
    }
}
