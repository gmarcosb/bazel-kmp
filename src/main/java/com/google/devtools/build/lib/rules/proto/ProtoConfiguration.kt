// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.analysis.config.BuildOptions
import com.google.devtools.common.options.*
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.StarlarkThread

/** Configuration for Protocol Buffer Libraries.  */
@Immutable // This module needs to be exported to Starlark so it can be passed as a mandatory exec/target
// configuration fragment in aspect definitions.
@RequiresOptions(options = [ProtoConfiguration.Options::class])
class ProtoConfiguration(buildOptions: BuildOptions) : Fragment(), ProtoConfigurationApi {
    /** Command line options.  */
    @OptionsClass
    abstract class Options : FragmentOptions() {
        @Option(
            name = "protocopt",
            allowMultiple = true,
            defaultValue = "null",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.AFFECTS_OUTPUTS],
            help = "Additional options to pass to the protobuf compiler."
        )
        abstract fun getProtocOpts(): MutableList<String?>?

        @get:Option(
            name = "experimental_proto_descriptor_sets_include_source_info",
            defaultValue = "false",
            documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.LOADING_AND_ANALYSIS],
            metadataTags = [OptionMetadataTag.EXPERIMENTAL],
            help = "Run extra actions for alternative Java api versions in a proto_library."
        )
        abstract val experimentalProtoDescriptorSetsIncludeSourceInfo: Boolean

        @get:Option(
            name = "proto_compiler",
            defaultValue = ProtoConstants.DEFAULT_PROTOC_LABEL,
            converter = CoreOptionConverters.LabelConverter::class,
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.LOADING_AND_ANALYSIS],
            help = "The label of the proto-compiler."
        )
        abstract val protoCompiler: Label?

        @get:Option(
            name = "proto_toolchain_for_javalite",
            defaultValue = ProtoConstants.DEFAULT_JAVA_LITE_PROTO_LABEL,
            converter = CoreOptionConverters.LabelConverter::class,
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.LOADING_AND_ANALYSIS],
            help = "Label of proto_lang_toolchain() which describes how to compile JavaLite protos"
        )
        abstract val protoToolchainForJavaLite: Label?

        @get:Option(
            name = "proto_toolchain_for_java",
            defaultValue = ProtoConstants.DEFAULT_JAVA_PROTO_LABEL,
            converter = CoreOptionConverters.EmptyToNullLabelConverter::class,
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.LOADING_AND_ANALYSIS],
            help = "Label of proto_lang_toolchain() which describes how to compile Java protos"
        )
        abstract val protoToolchainForJava: Label?

        @get:Option(
            name = "proto_toolchain_for_cc",
            defaultValue = ProtoConstants.DEFAULT_CC_PROTO_LABEL,
            converter = CoreOptionConverters.EmptyToNullLabelConverter::class,
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.LOADING_AND_ANALYSIS],
            help = "Label of proto_lang_toolchain() which describes how to compile C++ protos"
        )
        abstract val protoToolchainForCc: Label?

        @get:Option(
            name = "strict_proto_deps",
            defaultValue = "error",
            converter = CoreOptionConverters.StrictDepsConverter::class,
            documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
            effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS, OptionEffectTag.EAGERNESS_TO_EXIT],
            metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
            help = ("Unless OFF, checks that a proto_library target explicitly declares all directly "
                    + "used targets as dependencies.")
        )
        abstract val strictProtoDeps: StrictDepsMode?

        @get:Option(
            name = "strict_public_imports",
            defaultValue = "off",
            converter = CoreOptionConverters.StrictDepsConverter::class,
            documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
            effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS, OptionEffectTag.EAGERNESS_TO_EXIT],
            metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
            help = ("Unless OFF, checks that a proto_library target explicitly declares all targets used "
                    + "in 'import public' as exported.")
        )
        abstract val strictPublicImports: StrictDepsMode?

        @Option(
            name = "cc_proto_library_header_suffixes",
            defaultValue = ".pb.h",
            documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.LOADING_AND_ANALYSIS],
            help = "Sets the suffixes of header files that a cc_proto_library creates.",
            converter = Converters.CommaSeparatedOptionSetConverter::class
        )
        abstract fun getCcProtoLibraryHeaderSuffixes(): MutableList<String?>?

        @Option(
            name = "cc_proto_library_source_suffixes",
            defaultValue = ".pb.cc",
            documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.LOADING_AND_ANALYSIS],
            help = "Sets the suffixes of source files that a cc_proto_library creates.",
            converter = Converters.CommaSeparatedOptionSetConverter::class
        )
        abstract fun getCcProtoLibrarySourceSuffixes(): MutableList<String?>?
    }

    private val protocOpts: ImmutableList<String?>
    private val ccProtoLibraryHeaderSuffixes: ImmutableList<String?>
    private val ccProtoLibrarySourceSuffixes: ImmutableList<String?>
    private val options: Options

    init {
        val options: Options = buildOptions.get(Options::class.java)
        this.protocOpts = ImmutableList.copyOf<String?>(options.getProtocOpts())
        this.ccProtoLibraryHeaderSuffixes =
            ImmutableList.copyOf<String?>(options.getCcProtoLibraryHeaderSuffixes())
        this.ccProtoLibrarySourceSuffixes =
            ImmutableList.copyOf<String?>(options.getCcProtoLibrarySourceSuffixes())
        this.options = options
    }

    @StarlarkMethod(name = "experimental_protoc_opts", structField = true, documented = false)
    @Throws(EvalException::class)
    fun protocOptsForStarlark(): ImmutableList<String?> {
        return protocOpts
    }

    @StarlarkMethod(
        name = "experimental_proto_descriptorsets_include_source_info",
        useStarlarkThread = true,
        documented = false
    )
    @Throws(
        EvalException::class
    )
    fun experimentalProtoDescriptorSetsIncludeSourceInfoForStarlark(thread: StarlarkThread?): Boolean {
        BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        return experimentalProtoDescriptorSetsIncludeSourceInfo()
    }

    fun experimentalProtoDescriptorSetsIncludeSourceInfo(): Boolean {
        return options.experimentalProtoDescriptorSetsIncludeSourceInfo
    }

    @StarlarkConfigurationField(
        name = "proto_compiler",
        doc = "Label for the proto compiler.",
        defaultLabel = ProtoConstants.DEFAULT_PROTOC_LABEL
    )
    fun protoCompiler(): Label? {
        return options.protoCompiler
    }

    @StarlarkConfigurationField(
        name = "proto_toolchain_for_java",
        doc = "Label for the java proto toolchains.",
        defaultLabel = ProtoConstants.DEFAULT_JAVA_PROTO_LABEL
    )
    fun protoToolchainForJava(): Label? {
        return options.protoToolchainForJava
    }

    @StarlarkConfigurationField(
        name = "proto_toolchain_for_java_lite",
        doc = "Label for the java lite proto toolchains.",
        defaultLabel = ProtoConstants.DEFAULT_JAVA_LITE_PROTO_LABEL
    )
    fun protoToolchainForJavaLite(): Label? {
        return options.protoToolchainForJavaLite
    }

    @StarlarkConfigurationField(
        name = "proto_toolchain_for_cc",
        doc = "Label for the cc proto toolchains.",
        defaultLabel = ProtoConstants.DEFAULT_CC_PROTO_LABEL
    )
    fun protoToolchainForCc(): Label? {
        return options.protoToolchainForCc
    }

    @StarlarkMethod(name = "strict_proto_deps", useStarlarkThread = true, documented = false)
    @Throws(EvalException::class)
    fun strictProtoDepsForStarlark(thread: StarlarkThread?): String {
        BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        return options.strictProtoDeps.toString()
    }

    @StarlarkMethod(name = "strict_public_imports", useStarlarkThread = true, documented = false)
    @Throws(EvalException::class)
    fun strictPublicImportsForStarlark(thread: StarlarkThread?): String {
        BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        return options.strictPublicImports.toString()
    }

    @StarlarkMethod(name = "cc_proto_library_header_suffixes", structField = true, documented = false)
    fun ccProtoLibraryHeaderSuffixesForStarlark(): MutableList<String?> {
        return ccProtoLibraryHeaderSuffixes
    }

    @StarlarkMethod(name = "cc_proto_library_source_suffixes", structField = true, documented = false)
    fun ccProtoLibrarySourceSuffixesForStarlark(): MutableList<String?> {
        return ccProtoLibrarySourceSuffixes
    }
}
