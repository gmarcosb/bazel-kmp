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
package com.google.devtools.build.lib.bazel.rules

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider

/** Module implementing the rule set of Bazel.  */
class BazelRulesModule : BlazeModule() {
    /**
     * This is where deprecated options used by both Bazel and Blaze but only needed for the build
     * command go to die.
     * 
     * 
     * To deprecate Bazel-only build command options, use [BazelBuildGraveyardOptions].
     * 
     * 
     * To deprecate Bazel+Blaze options common to all commands, use [ ].
     */
    @com.google.devtools.common.options.OptionsClass
    abstract class BuildGraveyardOptions : com.google.devtools.common.options.OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "incompatible_enable_apple_toolchain_resolution",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val incompatibleUseToolchainResolution: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_objc_provider_from_linked",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val objcProviderFromLinked: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "build_python_zip",
            defaultValue = "auto",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            deprecationWarning = ("The '--no' prefix is no longer supported for this flag. Please use"
                    + " --build_python_zip=false instead."),
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val buildPythonZip: com.google.devtools.common.options.TriState?

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_default_to_explicit_init_py",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val incompatibleDefaultToExplicitInitPy: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "python_native_rules_allowlist",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            converter = LabelConverter::class,
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val nativeRulesAllowlist: com.google.devtools.build.lib.cmdline.Label?

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_python_disallow_native_rules",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val disallowNativeRules: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_remove_ctx_py_fragment",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val disablePyFragment: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_use_python_toolchains",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val incompatibleUsePythonToolchains: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_starlark_cc_import",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val experimentalStarlarkCcImport: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_platform_cc_test",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val experimentalPlatformCcTest: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "j2objc_dead_code_removal",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val removeDeadCode: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "j2objc_translation_flags",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            allowMultiple = true,
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val translationFlags: MutableList<String?>?

        @get:com.google.devtools.common.options.Option(
            name = "j2objc_dead_code_report",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val deadCodeReport: String?

        @get:com.google.devtools.common.options.Option(
            name = "experimental_proto_extra_actions",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val experimentalProtoExtraActions: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "enable_fdo_profile_absolute_path",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val enableFdoProfileAbsolutePath: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_genquery_use_graphless_query",
            defaultValue = "auto",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val useGraphlessQuery: com.google.devtools.common.options.TriState?

        @get:com.google.devtools.common.options.Option(
            name = "use_top_level_targets_for_symlinks",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val useTopLevelTargetsForSymlinks: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_skyframe_prepare_analysis",
            deprecationWarning = "This flag is a no-op and will be deleted in a future release.",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val skyframePrepareAnalysis: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_disable_legacy_flags_cc_toolchain_api",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val disableLegacyFlagsCcToolchainApi: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_enable_profile_by_default",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val enableProfileByDefault: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_override_toolchain_transition",
            defaultValue = "true",
            deprecationWarning = "This is now always set, please remove this flag.",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated, this is no longer in use and should be removed."
        )
        @get:Deprecated("")
        abstract val overrideToolchainTransition: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_parse_headers_skipped_if_corresponding_srcs_found",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val parseHeadersSkippedIfCorrespondingSrcsFound: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_worker_as_resource",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op, will be removed soon."
        )
        @get:Deprecated("")
        abstract val workerAsResource: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "high_priority_workers",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op, will be removed soon.",
            allowMultiple = true
        )
        @get:Deprecated("")
        abstract val highPriorityWorkers: MutableList<String?>?

        @get:com.google.devtools.common.options.Option(
            name = "target_platform_fallback",
            defaultValue = "",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "This option is deprecated and has no effect."
        )
        @get:Deprecated("")
        abstract val targetPlatformFallback: String?

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_auto_configure_host_platform",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "This option is deprecated and has no effect."
        )
        @get:Deprecated("")
        abstract val autoConfigureHostPlatform: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_require_availability_info",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            defaultValue = "false",
            help = "Deprecated no-op."
        )
        @get:Deprecated("")
        abstract val requireAvailabilityInfo: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_collect_local_action_metrics",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated no-op."
        )
        @get:Deprecated("")
        abstract val collectLocalExecutionStatistics: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_collect_local_sandbox_action_metrics",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated no-op."
        )
        @get:Deprecated("")
        abstract val collectLocalSandboxExecutionStatistics: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_enable_starlark_doc_extract",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated no-op."
        )
        @get:Deprecated("")
        abstract val enableBzlDocDump: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_parallel_aquery_output",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val parallelAqueryOutput: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_show_artifacts",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated no-op."
        )
        @get:Deprecated("")
        abstract val showArtifacts: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "announce",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op.",
            deprecationWarning = "This option is now deprecated and is a no-op"
        )
        @get:Deprecated("")
        abstract val announce: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "action_cache_store_output_metadata",
            oldName = "experimental_action_cache_store_output_metadata",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "no-op"
        )
        @get:Deprecated("")
        abstract val actionCacheStoreOutputMetadata: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "discard_actions_after_execution",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            help = "This option is deprecated and has no effect."
        )
        @get:Deprecated("")
        abstract val discardActionsAfterExecution: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "defer_param_files",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "This option is deprecated and has no effect and will be removed in the future."
        )
        @get:Deprecated("")
        abstract val deferParamFiles: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "check_fileset_dependencies_recursively",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            deprecationWarning = ("This flag is a no-op and fileset dependencies are always checked "
                    + "to ensure correctness of builds."),
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED]
        )
        @get:Deprecated("")
        abstract val checkFilesetDependenciesRecursively: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_skyframe_native_filesets",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            deprecationWarning = "This flag is a no-op and skyframe-native-filesets is always true."
        )
        @get:Deprecated("")
        abstract val skyframeNativeFileset: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "collapse_duplicate_defines",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "no-op"
        )
        @get:Deprecated("")
        abstract val collapseDuplicateDefines: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_require_javaplugininfo_in_javacommon",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val requireJavaPluginInfo: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_build_transitive_python_runfiles",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op",
            oldName = "incompatible_build_transitive_python_runfiles"
        )
        @get:Deprecated("")
        abstract val buildTransitiveRunfilesTrees: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_use_new_worker_pool",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val useNewWorkerPool: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "host_crosstool_top",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val hostCrosstoolTop: String?

        @get:com.google.devtools.common.options.Option(
            name = "experimental_use_semaphore_for_jobs",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val useSemaphoreForJobs: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_skip_ttvs_for_genquery",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op. Will be removed soon."
        )
        @get:Deprecated("")
        abstract val skipTtvs: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_remote_analysis_cache",
            defaultValue = "",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val remoteAnalysisCache: String?

        @get:com.google.devtools.common.options.Option(
            name = "experimental_remote_analysis_unreachable_cache_retry_interval",
            defaultValue = "",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val unreachableCacheRetryInterval: String?

        @get:com.google.devtools.common.options.Option(
            name = "remote_analysis_json_log",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val jsonLog: String?
    }

    /** This is where deprecated Bazel-specific options only used by the build command go to die.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class BazelBuildGraveyardOptions : BuildGraveyardOptions() {
        @get:com.google.devtools.common.options.Option(
            name = "python_path",
            defaultValue = "",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
            help = "Deprecated. No-op."
        )
        abstract val pythonPath: String?

        @get:com.google.devtools.common.options.Option(
            name = "experimental_python_import_all_repositories",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val experimentalPythonImportAllRepositories: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "python_top",
            defaultValue = "",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val pythonTop: String?

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_load_python_rules_from_bzl",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated no-op."
        )
        @get:Deprecated("")
        abstract val loadPythonRulesFromBzl: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_load_proto_rules_from_bzl",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated no-op."
        )
        @get:Deprecated("")
        abstract val loadProtoRulesFromBzl: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_load_java_rules_from_bzl",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated no-op."
        )
        @get:Deprecated("")
        abstract val loadJavaRulesFromBzl: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "make_variables_source",
            defaultValue = "configuration",
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP]
        )
        @get:Deprecated("")
        abstract val makeVariableSource: String?

        @get:com.google.devtools.common.options.Option(
            name = "force_ignore_dash_static",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "noop"
        )
        @get:Deprecated("")
        abstract val forceIgnoreDashStatic: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_profile_action_counts",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val enableActionCountProfile: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_remove_binary_profile",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val removeBinaryProfile: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_post_profile_started_event",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val postProfileStartedEvent: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_dont_use_javasourceinfoprovider",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val dontUseJavaSourceInfoProvider: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "android_sdk",
            defaultValue = "",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op",
            deprecationWarning = ANDROID_FLAG_DEPRECATION
        )
        @get:Deprecated("")
        abstract val sdk: String?

        @get:com.google.devtools.common.options.Option(
            name = "android_cpu",
            defaultValue = "",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op",
            deprecationWarning = ANDROID_FLAG_DEPRECATION
        )
        @get:Deprecated("")
        abstract val cpu: String?

        @get:com.google.devtools.common.options.Option(
            name = "android_crosstool_top",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op",
            deprecationWarning = ANDROID_FLAG_DEPRECATION
        )
        @get:Deprecated("")
        abstract val androidCrosstoolTop: String?

        @get:com.google.devtools.common.options.Option(
            name = "android_grte_top",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op",
            deprecationWarning = ANDROID_FLAG_DEPRECATION
        )
        @get:Deprecated("")
        abstract val androidLibcTopLabel: String?

        @get:com.google.devtools.common.options.Option(
            name = "fat_apk_cpu",
            converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionSetConverter::class,
            defaultValue = "armeabi-v7a",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op",
            deprecationWarning = ANDROID_FLAG_DEPRECATION
        )
        @get:Deprecated("")
        abstract val fatApkCpus: MutableList<String?>?

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_enable_cgo_toolchain_resolution",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val incompatibleEnableGoToolchainResolution: Boolean

        companion object {
            private val ANDROID_FLAG_DEPRECATION = ("Legacy Android flags have been deprecated. See"
                    + " https://blog.bazel.build/2023/11/15/android-platforms.html for details and"
                    + " migration directions")
        }
    }

    /**
     * This is where deprecated options which need to be available for all commands go to die. If you
     * want to graveyard an all-command option specific to Blaze or Bazel, create a subclass.
     */
    @com.google.devtools.common.options.OptionsClass
    abstract class AllCommandGraveyardOptions : com.google.devtools.common.options.OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "incompatible_autoload_externally",
            converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionSetConverter::class,
            defaultValue = "",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val incompatibleAutoloadExternally: MutableList<String?>?

        @get:com.google.devtools.common.options.Option(
            name = "repositories_without_autoloads",
            converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionSetConverter::class,
            defaultValue = "",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val repositoriesWithoutAutoloads: MutableList<String?>?

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_disable_autoloads_in_main_repo",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Deprecated. No-op."
        )
        @get:Deprecated("")
        abstract val incompatibleDisableAutoloadsInMainRepo: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_py_binaries_include_label",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val includeLabelInPyBinariesLinkstamp: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_python_disable_py2",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val disablePy2: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "force_python",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val forcePython: String?

        @get:com.google.devtools.common.options.Option(
            name = "host_force_python",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val hostForcePython: String?

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_py3_is_default",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val incompatiblePy3IsDefault: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_py2_outputs_are_suffixed",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val incompatiblePy2OutputsAreSuffixed: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "python_version",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val pythonVersion: String?

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_remove_old_python_version_api",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val incompatibleRemoveOldPythonVersionApi: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_allow_python_version_transitions",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val incompatibleAllowPythonVersionTransitions: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_disallow_legacy_py_provider",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val incompatibleDisallowLegacyPyProvider: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_disallow_legacy_java_toolchain_flags",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val experimentalDisallowLegacyJavaToolchainFlags: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "javabase",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val javabase: String?

        @get:com.google.devtools.common.options.Option(
            name = "java_toolchain",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val javaToolchain: String?

        @get:com.google.devtools.common.options.Option(
            name = "host_java_toolchain",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val hostJavaToolchain: String?

        @get:com.google.devtools.common.options.Option(
            name = "host_javabase",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val hostJavabase: String?

        @get:com.google.devtools.common.options.Option(
            name = "apple_crosstool_top",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val appleCrosstoolTop: String?

        @get:com.google.devtools.common.options.Option(
            name = "legacy_bazel_java_test",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val legacyBazelJavaTest: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "strict_deps_java_protos",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val strictDepsJavaProtos: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "disallow_strict_deps_for_jpl",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val isDisallowStrictDepsForJpl: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_import_deps_checking",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val importDepsCheckingLevel: String?

        @get:com.google.devtools.common.options.Option(
            name = "experimental_allow_runtime_deps_on_neverlink",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val allowRuntimeDepsOnNeverLink: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_limit_android_lint_to_android_constrained_java",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val limitAndroidLintToAndroidCompatible: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_java_header_input_pruning",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val experimentalJavaHeaderInputPruning: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_dont_collect_native_libraries_in_data",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val dontCollectDataLibraries: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_java_header_compilation_direct_deps",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val javaHeaderCompilationDirectDeps: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "jplPropagateCcLinkParamsStore",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val jplPropagateCcLinkParamsStore: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_require_linker_input_cc_api",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val incompatibleRequireLinkerInputCcApi: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_depset_for_libraries_to_link_getter",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val incompatibleDepsetForLibrariesToLinkGetter: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "legacy_external_runfiles",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val legacyExternalRunfiles: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_disable_target_provider_fields",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val incompatibleDisableTargetProviderFields: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_disallow_struct_provider_syntax",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val incompatibleDisallowStructProviderSyntax: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "auto_cpu_environment_group",
            defaultValue = "",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL, com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED
            ],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val autoCpuEnvironmentGroup: String?

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_top_level_aspects_require_providers",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val incompatibleTopLevelAspectsRequireProviders: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "separate_aspect_deps",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val separateAspectDeps: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_visibility_private_attributes_at_definition",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val incompatibleVisibilityPrivateAttributesAtDefinition: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_new_actions_api",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val incompatibleNewActionsApi: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_enable_aspect_hints",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED]
        )
        @get:Deprecated("")
        abstract val enableAspectHints: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "bes_best_effort",
            defaultValue = "false",
            deprecationWarning = ("BES best effort upload has been removed. The flag has no more "
                    + "functionality attached to it and will be removed in a future release."),
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val besBestEffort: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_use_event_based_build_completion_status",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.EXECUTION],
            help = "No-op"
        )
        @get:Deprecated("")
        abstract val useEventBasedBuildCompletionStatus: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_java_proto_add_allowed_public_imports",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "This flag is a noop and scheduled for removal."
        )
        @get:Deprecated("")
        abstract val experimentalJavaProtoAddAllowedPublicImports: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "java_optimization_mode",
            defaultValue = "",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "Do not use."
        )
        @get:Deprecated("")
        abstract val javaOptimizationMode: String?

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_depset_for_java_output_source_jars",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val incompatibleDepsetForJavaOutputSourceJars: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_use_plus_in_repo_names",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val incompatibleUsePlusInRepoNames: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "enable_bzlmod",
            oldName = "experimental_enable_bzlmod",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val enableBzlmod: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "enable_workspace",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val enableWorkspace: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_announce_profile_path",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val announceProfilePath: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_existing_rules_immutable_view",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val incompatibleExistingRulesImmutableView: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_disable_native_repo_rules",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val incompatibleDisableNativeRepoRules: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_no_package_distribs",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val incompatibleNoPackageDistribs: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_action_resource_set",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val experimentalActionResourceSet: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_macos_set_install_name",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val macosSetInstallName: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "verbose_explanations",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val verboseExplanations: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_cc_static_library",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val experimentalCcStaticLibrary: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_legacy_local_fallback",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val legacyLocalFallback: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "proto_toolchain_for_j2objc",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            converter = LabelConverter::class,
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val protoToolchainForJ2Objc: com.google.devtools.build.lib.cmdline.Label?

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_use_cc_configure_from_rules_cc",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "No-op."
        )
        @get:Deprecated("")
        abstract val incompatibleUseCcConfigureFromRulesCc: Boolean
    }

    override fun initializeRuleClasses(builder: ConfiguredRuleClassProvider.Builder) {
        BazelRuleClassProvider.setup(builder)
    }

    override fun getCommandOptions(commandName: String): Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
        return if (commandName == "build")
            com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
                BazelBuildGraveyardOptions::class.java,
                AllCommandGraveyardOptions::class.java
            )
        else
            com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
                AllCommandGraveyardOptions::class.java
            )
    }

    override fun registerActionContexts(
        registryBuilder: com.google.devtools.build.lib.exec.ModuleActionContextRegistry.Builder,
        env: CommandEnvironment?,
        buildRequest: BuildRequest?
    ) {
        registryBuilder.register<T?>(JavaCompileActionContext::class.java, JavaCompileActionContext())
    }
}
