// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlarkdocextract

import com.google.devtools.build.lib.packages.StarlarkProvider

/**
 * Shared settings used by documentation extractors for transforming Starlark data structures into
 * StardocOutputProtos.* protos.
 */
class ExtractorContext(
    labelRenderer: LabelRenderer?,
    providerQualifiedNames: com.google.common.collect.ImmutableMap<StarlarkProvider.Key?, String?>?,
    val extractNativelyDefinedAttrs: Boolean
) {
    fun toBuilder(): Builder? {
        return builder()
            .labelRenderer(labelRenderer)!!
            .providerQualifiedNames(providerQualifiedNames)!!
            .extractNativelyDefinedAttrs(extractNativelyDefinedAttrs)
    }

    /** Builder for [ExtractorContext].  */
    @AutoBuilder
    interface Builder {
        fun labelRenderer(labelRenderer: LabelRenderer?): Builder?

        fun providerQualifiedNames(
            providerQualifiedNames: com.google.common.collect.ImmutableMap<StarlarkProvider.Key?, String?>?
        ): Builder?

        fun extractNativelyDefinedAttrs(extractNativelyDefinedAttrs: Boolean): Builder?

        fun build(): ExtractorContext?
    }

    /**
     * Returns the human-readable provider name suitable for use in a given module's documentation.
     * For a provider loadable from that module, this is intended to be the qualified name (or more
     * precisely, the first qualified name) under which a user of this module may access it. For local
     * providers and for providers loaded but not re-exported via a global, it's the provider key name
     * (a.k.a. `provider.toString()`). For legacy struct providers, it's the legacy ID (which
     * also happens to be `provider.toString()`).
     */
    fun getDocumentedProviderName(provider: StarlarkProviderIdentifier): String? {
        val qualifiedName: String? = providerQualifiedNames.get(provider.getKey())
        if (qualifiedName != null) {
            return qualifiedName
        }
        return provider.toString()
    }

    val labelRenderer: LabelRenderer?
    val providerQualifiedNames: com.google.common.collect.ImmutableMap<StarlarkProvider.Key?, String?>?

    init {
        this.providerQualifiedNames = providerQualifiedNames
        this.labelRenderer = labelRenderer
        LabelRenderer > com.google.common.base.Preconditions.checkNotNull<LabelRenderer?>(
            labelRenderer,
            "labelRenderer cannot be null."
        )
        com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableMap<StarlarkProvider.Key?, String?>?>(
            providerQualifiedNames,
            "providerQualifiedNames cannot be null."
        )
    }

    companion object {
        /** Returns a new [Builder] instance.  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return AutoBuilder_ExtractorContext_Builder()
                .providerQualifiedNames(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .extractNativelyDefinedAttrs(false)
        }

        /**
         * Returns true if the name should be, by default, considered for documentation extraction or for
         * recursing into.
         */
        fun isPublicName(name: String): Boolean {
            return name.length() > 0 && java.lang.Character.isAlphabetic(name.charAt(0).code)
        }
    }
}
