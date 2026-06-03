// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.proto

import com.google.devtools.build.lib.analysis.FilesToRunProvider

// Note: AutoValue v1.4-rc1 has AutoValue.CopyAnnotations which makes it work with Starlark. No need
// to un-AutoValue this class to expose it to Starlark.
/**
 * Specifies how to generate language-specific code from .proto files. Used by LANG_proto_library
 * rules.
 */
@AutoValue
abstract class ProtoLangToolchainProvider {
    // Format string used when passing output to the plugin used by proto compiler.
    abstract fun outReplacementFormatFlag(): String?

    // Format string used when passing plugin to proto compiler.
    abstract fun pluginFormatFlag(): String?

    // Proto compiler plugin.
    abstract fun pluginExecutable(): FilesToRunProvider?

    abstract fun runtime(): TransitiveInfoCollection?

    // Proto compiler.
    abstract fun protoc(): FilesToRunProvider?

    abstract fun protocOpts(): com.google.common.collect.ImmutableList<String?>?

    // Progress message to set on the proto compiler action.
    abstract fun progressMessage(): String?

    // Mnemonic to set on the proto compiler action.
    abstract fun mnemonic(): String?

    companion object {
        private const val PROVIDER_NAME = "ProtoLangToolchainInfo"

        val protobufProtoLangToolchainKey: StarlarkProvider.Key =
            Key(ProtoConstants.PROTO_LANG_TOOLCHAIN_INFO, PROVIDER_NAME)

        fun get(prerequisite: TransitiveInfoCollection): ProtoLangToolchainProvider? {
            val provider: StarlarkInfo? = prerequisite.get(protobufProtoLangToolchainKey) as StarlarkInfo?
            return wrapStarlarkProviderWithNativeProvider(provider)
        }

        fun wrapStarlarkProviderWithNativeProvider(provider: StarlarkInfo?): ProtoLangToolchainProvider? {
            if (provider != null) {
                try {
                    return AutoValue_ProtoLangToolchainProvider(
                        provider.getValue("out_replacement_format_flag", String::class.java),
                        provider.getNoneableValue("plugin_format_flag", String::class.java),
                        provider.getNoneableValue("plugin", FilesToRunProvider::class.java),
                        provider.getNoneableValue("runtime", TransitiveInfoCollection::class.java),
                        provider.getValue("proto_compiler", FilesToRunProvider::class.java),
                        com.google.common.collect.ImmutableList.< E > copyOf < E ? > (provider.getValue("protoc_opts") as StarlarkList<String?>?),
                        provider.getValue("progress_message", String::class.java),
                        provider.getValue("mnemonic", String::class.java)
                    )
                } catch (e: net.starlark.java.eval.EvalException) {
                    return null
                }
            }
            return null
        }
    }
}
