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
package com.google.devtools.build.lib.rules.apple

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Ascii
import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.LabelConverter
import com.google.devtools.build.lib.util.CPU
import com.google.devtools.common.options.*

/** Command-line options for building for Apple platforms.  */
@OptionsClass
abstract class AppleCommandLineOptions : FragmentOptions() {
    @get:Option(
        name = "xcode_version",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("If specified, uses Xcode of the given version for relevant build actions. "
                + "If unspecified, uses the executor default version of Xcode.")
    )
    abstract val xcodeVersion: String?

    @get:Option(
        name = "ios_sdk_version",
        defaultValue = "null",
        converter = DottedVersionConverter::class,
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("Specifies the version of the iOS SDK to use to build iOS applications. "
                + "If unspecified, uses the default iOS SDK version from 'xcode_version'.")
    )
    abstract val iosSdkVersion: DottedVersion.Option?

    @get:Option(
        name = "watchos_sdk_version",
        defaultValue = "null",
        converter = DottedVersionConverter::class,
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("Specifies the version of the watchOS SDK to use to build watchOS applications. "
                + "If unspecified, uses the default watchOS SDK version from 'xcode_version'.")
    )
    abstract val watchOsSdkVersion: DottedVersion.Option?

    @get:Option(
        name = "tvos_sdk_version",
        defaultValue = "null",
        converter = DottedVersionConverter::class,
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("Specifies the version of the tvOS SDK to use to build tvOS applications. "
                + "If unspecified, uses the default tvOS SDK version from 'xcode_version'.")
    )
    abstract val tvOsSdkVersion: DottedVersion.Option?

    @get:Option(
        name = "macos_sdk_version",
        defaultValue = "null",
        converter = DottedVersionConverter::class,
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("Specifies the version of the macOS SDK to use to build macOS applications. "
                + "If unspecified, uses the default macOS SDK version from 'xcode_version'.")
    )
    abstract val macOsSdkVersion: DottedVersion.Option?

    @kotlin.jvm.JvmField
    @get:Option(
        name = "ios_minimum_os",
        defaultValue = "null",
        converter = DottedVersionConverter::class,
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("Minimum compatible iOS version for target simulators and devices. "
                + "If unspecified, uses 'ios_sdk_version'.")
    )
    abstract val iosMinimumOs: DottedVersion.Option?

    @get:Option(
        name = "watchos_minimum_os",
        defaultValue = "null",
        converter = DottedVersionConverter::class,
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("Minimum compatible watchOS version for target simulators and devices. "
                + "If unspecified, uses 'watchos_sdk_version'.")
    )
    abstract val watchosMinimumOs: DottedVersion.Option?

    @get:Option(
        name = "tvos_minimum_os",
        defaultValue = "null",
        converter = DottedVersionConverter::class,
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("Minimum compatible tvOS version for target simulators and devices. "
                + "If unspecified, uses 'tvos_sdk_version'.")
    )
    abstract val tvosMinimumOs: DottedVersion.Option?

    @get:Option(
        name = "macos_minimum_os",
        defaultValue = "null",
        converter = DottedVersionConverter::class,
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("Minimum compatible macOS version for targets. "
                + "If unspecified, uses 'macos_sdk_version'.")
    )
    abstract val macosMinimumOs: DottedVersion.Option?

    @get:Option(
        name = "host_macos_minimum_os",
        defaultValue = "null",
        converter = DottedVersionConverter::class,
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("Minimum compatible macOS version for host targets. "
                + "If unspecified, uses 'macos_sdk_version'.")
    )
    abstract val hostMacosMinimumOs: DottedVersion.Option?

    @get:Option(
        name = "experimental_prefer_mutual_xcode",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = ("If true, use the most recent Xcode that is available both locally and remotely. If"
                + " false, or if there are no mutual available versions, use the local Xcode version"
                + " selected via xcode-select.")
    )
    abstract val preferMutualXcode: Boolean

    @get:Option(
        name = "incompatible_remove_ctx_apple_fragment",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("When true, Apple build flags are defined with Apple rules (in BUIILD files) and"
                + " ctx.fragments.apple is undefined. This is a migration flag to move all Apple"
                + " flags from core Bazel to Apple rules.")
    )
    abstract val disableAppleFragment: Boolean

    @get:Option(
        name = "apple_platform_type",
        defaultValue = "macos",
        converter = PlatformTypeConverter::class,
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = ("Don't set this value from the command line - it is derived from other flags and "
                + "configuration transitions derived from rule attributes")
    )
    abstract val applePlatformType: String?

    @get:Option(
        name = "apple_split_cpu",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = ("Don't set this value from the command line - it is derived from other flags and "
                + "configuration transitions derived from rule attributes")
    )
    abstract val appleSplitCpu: String?

    @get:Option(
        name = "ios_multi_cpus",
        allowMultiple = true,
        converter = Converters.CommaSeparatedOptionListConverter::class,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE, OptionEffectTag.LOADING_AND_ANALYSIS],
        help = ("Comma-separated list of architectures to build an ios_application with. The result "
                + "is a universal binary containing all specified architectures.")
    )
    abstract val iosMultiCpus: MutableList<String?>?

    @get:Option(
        name = "visionos_cpus",
        allowMultiple = true,
        converter = Converters.CommaSeparatedOptionListConverter::class,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE, OptionEffectTag.LOADING_AND_ANALYSIS],
        help = "Comma-separated list of architectures for which to build Apple visionOS binaries."
    )
    abstract val visionosCpus: MutableList<String?>?

    @get:Option(
        name = "watchos_cpus",
        allowMultiple = true,
        converter = Converters.CommaSeparatedOptionListConverter::class,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE, OptionEffectTag.LOADING_AND_ANALYSIS],
        help = "Comma-separated list of architectures for which to build Apple watchOS binaries."
    )
    abstract val watchosCpus: MutableList<String?>?

    @get:Option(
        name = "tvos_cpus",
        allowMultiple = true,
        converter = Converters.CommaSeparatedOptionListConverter::class,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE, OptionEffectTag.LOADING_AND_ANALYSIS],
        help = "Comma-separated list of architectures for which to build Apple tvOS binaries."
    )
    abstract val tvosCpus: MutableList<String?>?

    @get:Option(
        name = "macos_cpus",
        allowMultiple = true,
        converter = Converters.CommaSeparatedOptionListConverter::class,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE, OptionEffectTag.LOADING_AND_ANALYSIS],
        help = "Comma-separated list of architectures for which to build Apple macOS binaries."
    )
    abstract val macosCpus: MutableList<String?>?

    @get:Option(
        name = "xcode_version_config",
        defaultValue = "@bazel_tools//tools/cpp:host_xcodes",
        converter = LabelConverter::class,
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE, OptionEffectTag.LOADING_AND_ANALYSIS],
        help = ("The label of the xcode_config rule to be used for selecting the Xcode version "
                + "in the build configuration.")
    )
    abstract val xcodeVersionConfig: Label?

    @get:Option(
        name = "experimental_include_xcode_execution_requirements",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE, OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.EXECUTION
        ],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = ("If set, add a \"requires-xcode:{version}\" execution requirement to every Xcode action."
                + "  If the Xcode version has a hyphenated label,  also add a"
                + " \"requires-xcode-label:{version_label}\" execution requirement.")
    )
    abstract val includeXcodeExecutionRequirements: Boolean

    @get:Option(
        name = "apple_platforms",
        converter = LabelListConverter::class,
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE, OptionEffectTag.LOADING_AND_ANALYSIS],
        help = "Comma-separated list of platforms to use when building Apple binaries."
    )
    abstract val applePlatforms: MutableList<Label>?

    @get:Option(
        name = "use_platforms_in_apple_crosstool_transition",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        help = ("Makes apple_crosstool_transition fall back to using the value of `--platforms` flag"
                + " instead of legacy `--cpu` when needed.")
    )
    abstract val usePlatformsInAppleCrosstoolTransition: Boolean

    val minimumOsVersion: DottedVersion?
        /** Returns whether the minimum OS version is explicitly set for the current platform.  */
        get() {
            val option: DottedVersion.Option?
            when (this.applePlatformType) {
                PlatformType.IOS, PlatformType.CATALYST -> option = this.iosMinimumOs
                PlatformType.MACOS -> option = this.macosMinimumOs
                PlatformType.TVOS -> option = this.tvosMinimumOs
                PlatformType.VISIONOS ->         // TODO: Replace with CppOptions.minimumOsVersion
                    option = DottedVersion.Companion.option(
                        DottedVersion.Companion.fromStringUnchecked("1.0")
                    )

                PlatformType.WATCHOS -> option = this.watchosMinimumOs
                else -> throw IllegalStateException()
            }

            return DottedVersion.Companion.maybeUnwrap(option)
        }

    /** Flag converter for PlatformType string flag, just converting to lowercase.  */
    class PlatformTypeConverter : Converter.Contextless<String?>() {
        override fun convert(input: String): String {
            return Ascii.toLowerCase(input)
        }

        override fun getTypeDescription(): String {
            return "a string"
        }
    }

    companion object {
        @VisibleForTesting
        const val DEFAULT_IOS_SDK_VERSION: String = "8.4"

        @VisibleForTesting
        const val DEFAULT_WATCHOS_SDK_VERSION: String = "2.0"

        @VisibleForTesting
        const val DEFAULT_MACOS_SDK_VERSION: String = "10.11"

        @VisibleForTesting
        const val DEFAULT_TVOS_SDK_VERSION: String = "9.0"

        @VisibleForTesting
        const val DEFAULT_IOS_CPU: String = "x86_64"

        /** The default visionOS CPU value.  */
        const val DEFAULT_VISIONOS_CPU: String = "sim_arm64"

        /** The default watchos CPU value.  */
        val DEFAULT_WATCHOS_CPU: String = if (CPU.getCurrent() == CPU.AARCH64) "arm64" else "x86_64"

        /** The default tvOS CPU value.  */
        val DEFAULT_TVOS_CPU: String = if (CPU.getCurrent() == CPU.AARCH64) "sim_arm64" else "x86_64"

        /** The default macOS CPU value.  */
        val DEFAULT_MACOS_CPU: String = if (CPU.getCurrent() == CPU.AARCH64) "arm64" else "x86_64"

        /** The default Catalyst CPU value.  */
        const val DEFAULT_CATALYST_CPU: String = "x86_64"

        /**
         * The default label of the build-wide `xcode_config` configuration rule. This can be
         * changed from the default using the `xcode_version_config` build flag.
         */
        // TODO(cparsons): Update all callers to reference the actual xcode_version_config flag value.
        @VisibleForTesting
        const val DEFAULT_XCODE_VERSION_CONFIG_LABEL: String = "//tools/objc:host_xcodes"
    }
}
