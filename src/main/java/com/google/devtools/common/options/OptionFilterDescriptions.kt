// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options

/**
 * Provides descriptions of the options filters, for use in generated documentation and usage text.
 */
object OptionFilterDescriptions {
    /** The order that the categories should be listed in.  */
    @kotlin.jvm.JvmField
    var documentationOrder: Array<com.google.devtools.common.options.OptionDocumentationCategory?> =
        arrayOf<com.google.devtools.common.options.OptionDocumentationCategory?>(
            com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
            com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
            com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
            com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
            com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
            com.google.devtools.common.options.OptionDocumentationCategory.INPUT_STRICTNESS,
            com.google.devtools.common.options.OptionDocumentationCategory.SIGNING,
            com.google.devtools.common.options.OptionDocumentationCategory.STARLARK_SEMANTICS,
            com.google.devtools.common.options.OptionDocumentationCategory.TESTING,
            com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
            com.google.devtools.common.options.OptionDocumentationCategory.MOD_COMMAND,
            com.google.devtools.common.options.OptionDocumentationCategory.BZLMOD,
            com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
            com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
            com.google.devtools.common.options.OptionDocumentationCategory.GENERIC_INPUTS,
            com.google.devtools.common.options.OptionDocumentationCategory.REMOTE,
            com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED
        )

    fun getOptionCategoriesEnumDescription(): com.google.common.collect.ImmutableMap<com.google.devtools.common.options.OptionDocumentationCategory?, String?> {
        val optionCategoriesBuilder: com.google.common.collect.ImmutableMap.Builder<com.google.devtools.common.options.OptionDocumentationCategory?, String?> =
            com.google.common.collect.ImmutableMap.builder<com.google.devtools.common.options.OptionDocumentationCategory?, String?>()
        optionCategoriesBuilder
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
                "Miscellaneous options, not otherwise categorized."
            )
            .put( // Here for completeness, the help output should not include this option.
                com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
                "This feature should not be documented, as it is not meant for general use"
            )
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
                "Options that appear before the command and are parsed by the client"
            )
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
                "Options that affect the verbosity, format or location of logging"
            )
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
                "Options that control build execution"
            )
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
                "Options that trigger optimizations of the build time"
            )
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
                "Options that control the output of the command"
            )
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
                "Options that let the user configure the intended output, affecting its value, as "
                        + "opposed to its existence"
            )
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.INPUT_STRICTNESS,
                "Options that affect how strictly Bazel enforces valid build inputs (rule definitions, "
                        + " flag combinations, etc.)"
            )
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.SIGNING,
                "Options that affect the signing outputs of a build"
            )
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.STARLARK_SEMANTICS,
                "This option affects semantics of the Starlark language or the build API accessible to "
                        + "BUILD files, .bzl files, or WORKSPACE files."
            )
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.TESTING,
                "Options that govern the behavior of the test environment or test runner"
            )
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
                "Options that configure the toolchain used for action execution"
            )
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.QUERY,
                "Options relating to query output and semantics"
            )
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.MOD_COMMAND,
                "Options relating to the output and semantics of the `mod` subcommand"
            )
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.BZLMOD,
                "Options relating to Bzlmod output and semantics"
            )
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.GENERIC_INPUTS,
                "Options specifying or altering a generic input to a Bazel command that does not fall "
                        + "into other categories."
            )
            .put(
                com.google.devtools.common.options.OptionDocumentationCategory.REMOTE,
                "Remote caching and execution options"
            )
        return optionCategoriesBuilder.build()
    }

    @kotlin.jvm.JvmStatic
    fun getOptionEffectTagDescription(
        productName: String?
    ): com.google.common.collect.ImmutableMap<com.google.devtools.common.options.OptionEffectTag?, String?> {
        val effectTagDescriptionBuilder: com.google.common.collect.ImmutableMap.Builder<com.google.devtools.common.options.OptionEffectTag?, String?> =
            com.google.common.collect.ImmutableMap.builder<com.google.devtools.common.options.OptionEffectTag?, String?>()
        effectTagDescriptionBuilder
            .put(
                com.google.devtools.common.options.OptionEffectTag.UNKNOWN,
                "This option has unknown, or undocumented, effect."
            )
            .put(com.google.devtools.common.options.OptionEffectTag.NO_OP, "This option has literally no effect.")
            .put(
                com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE,
                ("Changing the value of this option can cause significant loss of incremental "
                        + "state, which slows builds. State could be lost due to a server restart or to "
                        + "invalidation of a large part of the dependency graph.")
            )
            .put(
                com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS,
                ("This option actively changes the inputs that "
                        + productName
                        + " considers for the build, such as filesystem restrictions, repository versions, "
                        + "or other options.")
            )
            .put(
                com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS,
                ("This option affects "
                        + productName
                        + "'s outputs. This tag is intentionally broad, can include transitive affects, "
                        + "and does not specify the type of output it affects.")
            )
            .put(
                com.google.devtools.common.options.OptionEffectTag.BUILD_FILE_SEMANTICS,
                "This option affects the semantics of BUILD or .bzl files."
            )
            .put(
                com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION,
                ("This option affects settings of "
                        + productName
                        + "-internal machinery. This tag does not, on its own, mean that build artifacts "
                        + "are affected.")
            )
            .put(
                com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS,
                "This option affects the loading and analysis of dependencies, and the building "
                        + "of the dependency graph."
            )
            .put(
                com.google.devtools.common.options.OptionEffectTag.EXECUTION,
                "This option affects the execution phase, such as sandboxing or remote execution "
                        + "related options."
            )
            .put(
                com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS,
                ("This option triggers an optimization that may be machine specific and is not "
                        + "guaranteed to work on all machines. The optimization could include a tradeoff "
                        + "with other aspects of performance, such as memory or cpu cost.")
            )
            .put(
                com.google.devtools.common.options.OptionEffectTag.EAGERNESS_TO_EXIT,
                ("This option changes how eagerly "
                        + productName
                        + " will exit from a failure, where a choice between continuing despite the "
                        + "failure and ending the invocation exists.")
            )
            .put(
                com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING,
                "This option is used to monitor " + productName + "'s behavior and performance."
            )
            .put(
                com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT,
                "This option affects " + productName + "'s terminal output."
            )
            .put(
                com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES,
                "This option changes the command line arguments of one or more build actions."
            )
            .put(
                com.google.devtools.common.options.OptionEffectTag.TEST_RUNNER,
                "This option changes the testrunner environment of the build."
            )
        return effectTagDescriptionBuilder.build()
    }

    @kotlin.jvm.JvmStatic
    fun getOptionMetadataTagDescription(
        productName: String?
    ): com.google.common.collect.ImmutableMap<com.google.devtools.common.options.OptionMetadataTag?, String?> {
        val effectTagDescriptionBuilder: com.google.common.collect.ImmutableMap.Builder<com.google.devtools.common.options.OptionMetadataTag?, String?> =
            com.google.common.collect.ImmutableMap.builder<com.google.devtools.common.options.OptionMetadataTag?, String?>()
        effectTagDescriptionBuilder
            .put(
                com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL,
                "This option triggers an experimental feature with no guarantees of functionality."
            )
            .put(
                com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE,
                "This option triggers a breaking change. Use this option to test your migration "
                        + "readiness or get early access to the new feature"
            )
            .put(
                com.google.devtools.common.options.OptionMetadataTag.DEPRECATED,
                "This option is deprecated. It might be that the feature it affects is deprecated, "
                        + "or that another method of supplying the information is preferred."
            )
            .put(
                com.google.devtools.common.options.OptionMetadataTag.HIDDEN,  // Here for completeness, these options are UNDOCUMENTED.
                "This option should not be used by a user, and should not be logged."
            )
            .put(
                com.google.devtools.common.options.OptionMetadataTag.INTERNAL,  // Here for completeness, these options are UNDOCUMENTED.
                "This option isn't even a option, and should not be logged."
            )
            .put(
                com.google.devtools.common.options.OptionMetadataTag.NON_CONFIGURABLE,
                "This option cannot be changed in a transition or be used in a select() statement."
            )
        return effectTagDescriptionBuilder.build()
    }
}
