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

import com.google.devtools.build.lib.cmdline.Label

/**
 * Starlark API documentation extractor for a provider group described by a rule's `provides`
 * or an attribute's `providers` declaration.
 */
internal object ProviderNameGroupExtractor {
    fun buildProviderNameGroup(
        context: ExtractorContext, providerGroup: com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier>
    ): ProviderNameGroup {
        val providerNameGroupBuilder: ProviderNameGroup.Builder = ProviderNameGroup.newBuilder()
        for (provider in providerGroup) {
            providerNameGroupBuilder.addProviderName(
                StringEncoding.internalToUnicode(context.getDocumentedProviderName(provider))
            )
            val providerKeyBuilder: OriginKey.Builder =
                OriginKey.newBuilder().setName(StringEncoding.internalToUnicode(provider.toString()))
            if (provider.getKey() is StarlarkProvider.Key) {
                val definingModule: Label? = (provider.getKey() as StarlarkProvider.Key).getExtensionLabel()
                providerKeyBuilder.setFile(
                    StringEncoding.internalToUnicode(context.labelRenderer.render(definingModule))
                )
            } else if (provider.getKey() is BuiltinProvider.Key) {
                providerKeyBuilder.setFile("<native>")
            }
            providerNameGroupBuilder.addOriginKey(providerKeyBuilder.build())
        }
        return providerNameGroupBuilder.build()
    }
}
