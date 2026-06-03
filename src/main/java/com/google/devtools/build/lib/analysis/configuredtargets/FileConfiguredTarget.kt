// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.configuredtargets

import com.google.devtools.build.lib.actions.ActionLookupKey

/** A configured target representing a source or derived / generated file.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
abstract class FileConfiguredTarget internal constructor(
    lookupKey: ActionLookupKey?,
    visibility: NestedSet<PackageGroupContents?>?,
    artifact: Artifact?
) : AbstractConfiguredTarget(lookupKey, visibility), FileType.HasFileType {
    private val singleFile: NestedSet<Artifact?>

    init {
        this.singleFile = NestedSetBuilder.create(Order.STABLE_ORDER, artifact)
    }

    open fun getArtifact(): Artifact {
        return singleFile.getSingleton()
    }

    /** Returns the file name of this file target.  */
    fun getFilename(): String {
        return getLabel().getName()
    }

    public override fun filePathForFileTypeMatcher(): String {
        return getFilename()
    }

    override fun <P : TransitiveInfoProvider?> getProvider(providerClass: java.lang.Class<P?>): P? {
        AnalysisUtils.Companion.checkProvider<P?>(providerClass)
        if (providerClass == TransitiveVisibilityProvider::class.java) {
            return providerClass.cast(createTransitiveVisibilityProvider())
        }
        return providerClass.cast(getProviderInternal(providerClass))
    }

    private fun getProviderInternal(
        providerClass: java.lang.Class<out TransitiveInfoProvider?>?
    ): TransitiveInfoProvider? {
        // The set of possible providers is small and predictable, so to save memory, this method does
        // simple identity checks so that we don't need to store a TransitiveInfoProviderMap.
        // Additionally, file providers are created on-demand when requested. These optimizations
        // combine to save over 1% of analysis heap.
        if (providerClass == VisibilityProvider::class.java) {
            return this
        }
        if (providerClass == FileProvider::class.java) {
            return createFileProvider()
        }
        if (providerClass == FilesToRunProvider::class.java) {
            return createFilesToRunProvider()
        }
        if (providerClass == TransitiveVisibilityProvider::class.java) {
            return createTransitiveVisibilityProvider()
        }
        return null
    }

    private fun createFileProvider(): FileProvider {
        return FileProvider.of(singleFile)
    }

    private fun createFilesToRunProvider(): FilesToRunProvider? {
        return FilesToRunProvider.Companion.create(
            singleFile,  /* runfilesSupport= */null,  /* executable= */getArtifact()
        )
    }

    protected abstract fun createTransitiveVisibilityProvider(): TransitiveVisibilityProvider?

    override fun rawGetStarlarkProvider(providerKey: String?): Any? {
        return null
    }

    public override fun getProvidersDictForQuery(): Dict<String?, Any?>? {
        val dict: net.starlark.java.eval.Dict.Builder<String?, Any?> = Dict.builder<String?, Any?>()
        AbstractConfiguredTarget.Companion.tryAddProviderForQuery(dict, VisibilityProvider::class.java, this)
        AbstractConfiguredTarget.Companion.tryAddProviderForQuery(dict, FileProvider::class.java, createFileProvider())
        AbstractConfiguredTarget.Companion.tryAddProviderForQuery(
            dict,
            FilesToRunProvider::class.java,
            createFilesToRunProvider()
        )
        // DefaultInfo is not stored as a provider, but Starlark targets still observe it on
        // dependencies.
        AbstractConfiguredTarget.Companion.tryAddProviderForQuery(
            dict,
            DefaultInfo.Companion.PROVIDER.getKey(),
            DefaultInfo.Companion.build(this)
        )
        return dict.buildImmutable()
    }
}
