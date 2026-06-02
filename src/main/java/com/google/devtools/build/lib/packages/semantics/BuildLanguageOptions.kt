// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages.semantics

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.google.common.collect.ImmutableList
import com.google.common.collect.Interner
import com.google.devtools.build.lib.concurrent.BlazeInterners
import com.google.devtools.common.options.*
import net.starlark.java.eval.StarlarkSemantics

/**
 * Options that affect the semantics of Bazel's build language.
 * 
 * 
 * These are injected into Skyframe (as an instance of [StarlarkSemantics]) when a new
 * build invocation occurs. Changing these options between builds will therefore trigger a
 * reevaluation of everything that depends on the Starlark interpreter  in particular,
 * evaluation of all BUILD and .bzl files.
 * 
 * 
 * *To add a new option, update the following:*
 * 
 * 
 *  * Add a new `@Option`-annotated field to this class. The field name and default value
 * should be the same as in [StarlarkSemantics], and the option name in the annotation
 * should be that name written in snake_case. Add a line to set the new field in [       ][.toStarlarkSemantics]. New options should always default to `false` and have no
 * observable effect when disabled.
 *  * Define a new `StarlarkSemantics.Key` or `StarlarkSemantics` boolean flag
 * identifier.
 *  * Add a line to set the new field in both [ConsistencyTest.buildRandomOptions] and
 * [ConsistencyTest.buildRandomSemantics].
 *  * Update manual documentation in site/docs/skylark/backward-compatibility.md. Also remember
 * to update this when flipping a flag's default value.
 *  * Boolean semantic flags can toggle StarlarkMethod-annotated Java methods (or their
 * parameters) on or off, making them selectively invisible to Starlark. To do this, add a new
 * entry to [BuildLanguageOptions], then specify the identifier in [       ][net.starlark.java.annot.StarlarkMethod.enableOnlyWithFlag] or [       ][net.starlark.java.annot.StarlarkMethod.disableWithFlag].
 * 
 */
@OptionsClass
abstract class BuildLanguageOptions : OptionsBase() {
    @Option(
        name = "incompatible_stop_exporting_language_modules",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("If enabled, certain language-specific modules (such as `cc_common`) are unavailable in"
                + " user .bzl files and may only be called from their respective rules repositories.")
    )
    abstract fun getIncompatibleStopExportingLanguageModules(): Boolean

    // TODO(#11437): Delete the special empty string value so that it's on unconditionally.
    @Option(
        name = "experimental_builtins_bzl_path",
        defaultValue = "%bundled%",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.LOSES_INCREMENTAL_STATE, OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = ("This flag tells Bazel how to find the \"@_builtins\" .bzl files that govern how "
                + "predeclared symbols for BUILD and .bzl files are defined. This flag is only "
                + "intended for Bazel developers, to help when writing @_builtins .bzl code. "
                + "Ordinarily this value is set to \"%bundled%\", which means to use the "
                + "builtins_bzl/ directory packaged in the Bazel binary. However, it can be set to "
                + "the path (relative to the root of the current workspace) of an alternate "
                + "builtins_bzl/ directory, such as one in a Bazel source tree workspace. A literal "
                + "value of \"%workspace%\" is equivalent to the relative package path of "
                + "builtins_bzl/ within a Bazel source tree; this should only be used when running "
                + "Bazel within its own source tree. Finally, a value of the empty string disables "
                + "the builtins injection mechanism entirely.")
    )
    abstract fun getExperimentalBuiltinsBzlPath(): String?

    @Option(
        name = "experimental_builtins_dummy",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = "Enables an internal dummy symbol used to test builtins injection."
    )
    abstract fun getExperimentalBuiltinsDummy(): Boolean

    @Option(
        name = "experimental_builtins_injection_override",
        converter = Converters.CommaSeparatedNonEmptyOptionListConverter::class,
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = ("A comma-separated list of symbol names prefixed by a + or - character, indicating which"
                + " symbols from `@_builtins//:exports.bzl` to inject, overriding their default"
                + " injection status. Precisely, this works as follows. Each dict key of"
                + " `exported_toplevels` or `exported_rules` has the form `foo`, `+foo`, or `-foo`."
                + " The first two forms mean it gets injected by default, while the last form means"
                + " it does not get injected by default. In the first case (unprefixed), the default"
                + " is absolute and cannot be overridden. Otherwise, we then consult this options"
                + " list, and if we see foo occur here, we take the prefix of its last occurrence and"
                + " use that to decide whether or not to inject. It is a no-op to specify an unknown"
                + " symbol, or to attempt to not inject a symbol that occurs unprefixed in a dict"
                + " key.")
    )
    abstract fun getExperimentalBuiltinsInjectionOverride(): MutableList<String?>?

    @Option(
        name = "experimental_bzl_visibility",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = ("If enabled, adds a `visibility()` function that .bzl files may call during top-level"
                + " evaluation to set their visibility for the purpose of load() statements.")
    )
    abstract fun getExperimentalBzlVisibility(): Boolean

    @Option(
        name = "experimental_single_package_toolchain_binding",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("If enabled, the register_toolchain function may not include target patterns which may "
                + "refer to more than one package.")
    )
    abstract fun getExperimentalSinglePackageToolchainBinding(): Boolean

    @Option(
        name = "allow_experimental_loads",
        documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        defaultValue = "false",
        help = "If enabled, issue only a warning instead of an error for loads of experimental .bzls."
    )
    abstract fun getAllowExperimentalLoads(): Boolean

    @Option(
        name = "check_bzl_visibility",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        help = "If disabled, .bzl load visibility errors are demoted to warnings."
    )
    abstract fun getCheckBzlVisibility(): Boolean

    @Option(
        name = "experimental_cc_skylark_api_enabled_packages",
        converter = Converters.CommaSeparatedOptionListConverter::class,
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = ("Passes list of packages that can use the C++ Starlark API. Don't enable this flag yet, "
                + "we will be making breaking changes.")
    )
    abstract fun getExperimentalCcStarlarkApiEnabledPackages(): MutableList<String?>?

    @Option(
        name = "experimental_enable_android_migration_apis",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        help = "If set to true, enables the APIs required to support the Android Starlark migration."
    )
    abstract fun getExperimentalEnableAndroidMigrationApis(): Boolean

    @Option(
        name = "experimental_enable_first_class_macros",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        help = "If set to true, enables the `macro()` construct for defining symbolic macros."
    )
    abstract fun getExperimentalEnableFirstClassMacros(): Boolean

    @Option(
        name = "experimental_enable_scl_dialect",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        help = "If set to true, .scl files may be used in load() statements."
    )
    abstract fun getExperimentalEnableSclDialect(): Boolean

    abstract fun setExperimentalEnableSclDialect(value: Boolean)

    @Option(
        name = "experimental_isolated_extension_usages",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        help = ("If true, enables the `isolate` parameter in the"
                + " [`use_extension`](https://bazel.build/rules/lib/globals/module#use_extension)"
                + " function.")
    )
    abstract fun getExperimentalIsolatedExtensionUsages(): Boolean

    @Option(
        name = "incompatible_no_implicit_watch_label",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        help = ("If true, then methods on `repository_ctx` that are passed a Label will no"
                + " longer automatically watch the file under that label for changes even if"
                + " `watch = \"no\"`, and `repository_ctx.path` no longer"
                + " causes the returned path to be watched. Use `repository_ctx.watch` instead.")
    )
    abstract fun getIncompatibleNoImplicitWatchLabel(): Boolean

    @Option(
        name = "incompatible_stop_exporting_build_file_path",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("If set to true, deprecated ctx.build_file_path will not be available. ctx.label.package"
                + " + '/BUILD' can be used instead.")
    )
    abstract fun getIncompatibleStopExportingBuildFilePath(): Boolean

    @Option(
        name = "experimental_google_legacy_api",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = ("If set to true, exposes a number of experimental pieces of Starlark build API "
                + "pertaining to Google legacy code.")
    )
    abstract fun getExperimentalGoogleLegacyApi(): Boolean

    abstract fun setExperimentalGoogleLegacyApi(value: Boolean)

    @Option(
        name = "experimental_platforms_api",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = ("If set to true, enables a number of platform-related Starlark APIs useful for "
                + "debugging.")
    )
    abstract fun getExperimentalPlatformsApi(): Boolean

    @Option(
        name = "experimental_cc_shared_library",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS, OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL
        ],
        help = ("If set to true, rule attributes and Starlark API methods needed for the rule "
                + "cc_shared_library will be available")
    )
    abstract fun getExperimentalCcSharedLibrary(): Boolean

    @Option(
        name = "experimental_repo_remote_exec",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS, OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL
        ],
        help = "If set to true, repository_rule gains some remote execution capabilities."
    )
    abstract fun getExperimentalRepoRemoteExec(): Boolean

    @Option(
        name = "experimental_disable_external_package",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.LOSES_INCREMENTAL_STATE],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL
        ],
        help = ("If set to true, the auto-generated //external package will not be available anymore. "
                + "Bazel will still be unable to parse the file 'external/BUILD', but globs reaching "
                + "into external/ from the unnamed package will work.")
    )
    abstract fun getExperimentalDisableExternalPackage(): Boolean

    @Option(
        name = "experimental_sibling_repository_layout",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.ACTION_COMMAND_LINES, OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION, OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.LOSES_INCREMENTAL_STATE
        ],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL
        ],
        help = ("If set to true, non-main repositories are planted as symlinks to the main repository in"
                + " the execution root. That is, all repositories are direct children of the"
                + " \$output_base/execution_root directory. This has the side effect of freeing up"
                + " \$output_base/execution_root/__main__/external for the real top-level 'external' "
                + "directory.")
    )
    abstract fun getExperimentalSiblingRepositoryLayout(): Boolean

    @Option(
        name = "incompatible_allow_tags_propagation",
        oldName = "experimental_allow_tags_propagation",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL
        ],
        help = ("If set to true, tags will be propagated from a target to the actions' execution"
                + " requirements; otherwise tags are not propagated. See"
                + " https://github.com/bazelbuild/bazel/issues/8830 for details.")
    )
    abstract fun getExperimentalAllowTagsPropagation(): Boolean

    @Option(
        name = "incompatible_always_check_depset_elements",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("Check the validity of elements added to depsets, in all constructors. Elements must be"
                + " immutable, but historically the depset(direct=...) constructor forgot to check."
                + " Use tuples instead of lists in depset elements."
                + " See https://github.com/bazelbuild/bazel/issues/10313 for details.")
    )
    abstract fun getIncompatibleAlwaysCheckDepsetElements(): Boolean

    @Option(
        name = "incompatible_disable_target_default_provider_fields",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("If set to true, disable the ability to utilize the default provider via field "
                + "syntax. Use provider-key syntax instead. For example, instead of using "
                + "`ctx.attr.dep.files` to access `files`, utilize `ctx.attr.dep[DefaultInfo].files "
                + "See "
                + "https://github.com/bazelbuild/bazel/issues/9014 for details.")
    )
    abstract fun getIncompatibleDisableTargetDefaultProviderFields(): Boolean

    @Option(
        name = "incompatible_disallow_empty_glob",
        defaultValue = DISALLOW_EMPTY_GLOB_DEFAULT,
        category = "incompatible changes",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = "If set to true, the default value of the `allow_empty` argument of glob() is False."
    )
    abstract fun getIncompatibleDisallowEmptyGlob(): Boolean

    @Option(
        name = "incompatible_package_group_has_public_syntax",
        defaultValue = FlagConstants.DEFAULT_INCOMPATIBLE_PACKAGE_GROUP_HAS_PUBLIC_SYNTAX,
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("In package_group's `packages` attribute, allows writing \"public\" or \"private\" to"
                + " refer to all packages or no packages respectively.")
    )
    abstract fun getIncompatiblePackageGroupHasPublicSyntax(): Boolean

    @Option(
        name = "incompatible_fix_package_group_reporoot_syntax",
        defaultValue = FlagConstants.DEFAULT_INCOMPATIBLE_FIX_PACKAGE_GROUP_REPOROOT_SYNTAX,
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("In package_group's `packages` attribute, changes the meaning of the value \"//...\" to"
                + " refer to all packages in the current repository instead of all packages in any"
                + " repository. You can use the special value \"public\" in place of \"//...\" to"
                + " obtain the old behavior. This flag requires"
                + " that --incompatible_package_group_has_public_syntax also be enabled.")
    )
    abstract fun getIncompatibleFixPackageGroupReporootSyntax(): Boolean

    @Option(
        name = "incompatible_no_attr_license",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = "If set to true, disables the function `attr.license`."
    )
    abstract fun getIncompatibleNoAttrLicense(): Boolean

    @Option(
        name = "incompatible_no_implicit_file_export",
        defaultValue = FlagConstants.DEFAULT_INCOMPATIBLE_NO_IMPLICIT_FILE_EXPORT,
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("If set, (used) source files are package private unless exported explicitly. See "
                + "https://github.com/bazelbuild/proposals/blob/master/designs/"
                + "2019-10-24-file-visibility.md")
    )
    abstract fun getIncompatibleNoImplicitFileExport(): Boolean

    @Option(
        name = "incompatible_no_rule_outputs_param",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = "If set to true, disables the `outputs` parameter of the `rule()` Starlark function."
    )
    abstract fun getIncompatibleNoRuleOutputsParam(): Boolean

    @Option(
        name = "incompatible_run_shell_command_string",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = "If set to true, the command parameter of actions.run_shell will only accept string"
    )
    abstract fun getIncompatibleRunShellCommandString(): Boolean

    @Option(
        name = "incompatible_require_mnemonic_for_run_actions",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("If set to true, ctx.actions.run and ctx.actions.run_shell will require an explicit"
                + " mnemonic")
    )
    abstract fun getIncompatibleRequireMnemonicForRunActions(): Boolean

    /** Used in an integration test to confirm that flags are visible to the interpreter.  */
    @Option(
        name = "internal_starlark_flag_test_canary",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.UNKNOWN]
    )
    abstract fun getInternalStarlarkFlagTestCanary(): Boolean

    @Option(
        name = "incompatible_do_not_split_linking_cmdline",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("When true, Bazel no longer modifies command line flags used for linking, and also "
                + "doesn't selectively decide which flags go to the param file and which don't.  "
                + "See https://github.com/bazelbuild/bazel/issues/7670 for details.")
    )
    abstract fun getIncompatibleDoNotSplitLinkingCmdline(): Boolean

    @Option(
        name = "incompatible_unambiguous_label_stringification",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("When true, Bazel will stringify the label @//foo:bar to @//foo:bar, instead of"
                + " //foo:bar. This only affects the behavior of str(), the % operator, and so on;"
                + " the behavior of repr() is unchanged. See"
                + " https://github.com/bazelbuild/bazel/issues/15916 for more information.")
    )
    abstract fun getIncompatibleUnambiguousLabelStringification(): Boolean

    @Option(
        name = "incompatible_java_info_merge_runtime_module_flags",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.UNKNOWN],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("If set to true, the JavaInfo constructor will merge add_exports and "
                + "add_opens of runtime_deps in addition to deps and exports.")
    )
    abstract fun getIncompatibleJavaInfoMergeRuntimeModuleFlags(): Boolean

    @Option(
        name = "max_computation_steps",
        defaultValue = "0",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        help = ("The maximum number of Starlark computation steps that may be executed by a BUILD file"
                + " (zero means no limit).")
    )
    abstract fun getMaxComputationSteps(): Long

    @Option(
        name = "nested_set_depth_limit",
        defaultValue = "3500",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        help = ("The maximum depth of the graph internal to a depset (also known as NestedSet), above"
                + " which the depset() constructor will fail.")
    )
    abstract fun getNestedSetDepthLimit(): Int

    @Option(
        name = "incompatible_disable_starlark_host_transitions",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        help = ("If set to true, rule attributes cannot set 'cfg = \"host\"'. Rules should set "
                + "'cfg = \"exec\"' instead.")
    )
    abstract fun getIncompatibleDisableStarlarkHostTransitions(): Boolean

    @Option(
        name = "incompatible_disable_objc_library_transition",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("Disable objc_library's custom transition and inherit "
                + "from the top level target instead (No-op in Bazel)")
    )
    abstract fun getIncompatibleDisableObjcLibraryTransition(): Boolean

    @Option(
        name = "incompatible_disable_transitions_on",
        converter = Converters.CommaSeparatedOptionSetConverter::class,
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE, OptionMetadataTag.NON_CONFIGURABLE],
        help = "A comma-separated list of flags that cannot be used in transitions inputs or outputs."
    )
    abstract fun getIncompatibleDisableTransitionsOn(): ImmutableList<String?>?

    @Option(
        name = "add_go_exec_groups_to_binary_rules",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        help = ("When enabled, 'go_build' and 'go_link' execution groups are added to binary rules like"
                + " 'cc_binary'.")
    )
    abstract fun getAddGoExecGroupsToBinaryRules(): Boolean

    // remove after Bazel LTS in Nov 2023
    @Option(
        name = "incompatible_fail_on_unknown_attributes",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = "If enabled, targets that have unknown attributes set to None fail."
    )
    abstract fun getIncompatibleFailOnUnknownAttributes(): Boolean

    // Flip when dependencies to rules_* repos are upgraded and protobuf registers toolchains
    @Option(
        name = "incompatible_enable_proto_toolchain_resolution",
        defaultValue = FlagConstants.DEFAULT_INCOMPATIBLE_ENABLE_PROTO_TOOLCHAIN_RESOLUTION,
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = "If true, proto lang rules define toolchains from protobuf repository."
    )
    abstract fun getIncompatibleEnableProtoToolchainResolution(): Boolean

    // Flip when java_single_jar is feature complete
    @Option(
        name = "incompatible_disable_non_executable_java_binary",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = "If true, java_binary is always executable. create_executable attribute is removed."
    )
    abstract fun getIncompatibleDisableNonExecutableJavaBinary(): Boolean

    @Option(
        name = "experimental_rule_extension_api",
        defaultValue = FlagConstants.DEFAULT_EXPERIMENTAL_RULE_EXTENSION_API,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = "Enable experimental rule extension API and subrule APIs"
    )
    abstract fun getExperimentalRuleExtensionApi(): Boolean

    @Option(
        name = "experimental_dormant_deps",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = (" If set to true, attr.label(materializer=), attr(for_dependency_resolution=),"
                + " attr.dormant_label(), attr.dormant_label_list() and"
                + " rule(for_dependency_resolution=) are allowed.")
    )
    abstract fun getExperimentalDormantDeps(): Boolean

    abstract fun setExperimentalDormantDeps(value: Boolean)

    @Option(
        name = "experimental_starlark_type_syntax",
        defaultValue = FlagConstants.DEFAULT_EXPERIMENTAL_STARLARK_TYPE_SYNTAX,
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = """
          Enables type annotations and related syntax in .bzl files. Locations of files where these are allowed is further restricted by `--experimental_starlark_types_allowed_paths`.
          Type syntax is never permitted in .scl files regardless of this flag.
          
          """.trimIndent()
    )
    abstract fun getExperimentalStarlarkTypeSyntax(): Boolean

    @Option(
        name = "experimental_starlark_static_type_checking",
        defaultValue = FlagConstants.DEFAULT_EXPERIMENTAL_STARLARK_TYPE_CHECKING,
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = ("Enables static type checking in files and functions that contain type annotations or "
                + "related syntax.")
    )
    abstract fun getExperimentalStarlarkStaticTypeChecking(): Boolean

    @Option(
        name = "experimental_starlark_dynamic_type_checking",
        defaultValue = FlagConstants.DEFAULT_EXPERIMENTAL_STARLARK_TYPE_CHECKING,
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = ("Enables dynamic type checking of arguments and return values for functions that contain "
                + "type annotations or related syntax.")
    )
    abstract fun getExperimentalStarlarkDynamicTypeChecking(): Boolean

    @Option(
        name = "experimental_starlark_type_checking",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        expansion = ["--experimental_starlark_static_type_checking", "--experimental_starlark_dynamic_type_checking"
        ],
        help = ("Enables both static and dynamic type checking in files and functions that contain type"
                + " annotations or related syntax. This is an expansion flag for"
                + " --experimental_starlark_static_type_checking and "
                + "--experimental_starlark_dynamic_type_checking."
                + " (When both flags are disabled, Bazel is more forgiving of invalid types in type"
                + " annotations.)")
    )
    abstract fun getExperimentalStarlarkTypeChecking(): Void?

    // TODO: b/350661266 - Delete this flag.
    @Option(
        name = "experimental_starlark_types",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = ("No-op. Previously used as --experimental_starlark_type_syntax +"
                + " --experimental_starlark_type_checking")
    )
    abstract fun getExperimentalStarlarkTypes(): Boolean

    @Option(
        name = "experimental_starlark_types_allowed_paths",
        converter = Converters.CommaSeparatedOptionListConverter::class,
        defaultValue = FlagConstants.DEFAULT_EXPERIMENTAL_STARLARK_TYPES_ALLOWED_PATHS,
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = "List of canonical Label prefixes under which Starlark type annotations are allowed."
    )
    abstract fun getExperimentalStarlarkTypesAllowedPaths(): MutableList<String?>?

    @Option(
        name = "incompatible_enable_deprecated_label_apis",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        help = ("If enabled, certain deprecated APIs (native.repository_name, Label.workspace_name,"
                + " Label.relative) can be used.")
    )
    abstract fun getEnableDeprecatedLabelApis(): Boolean

    @Option(
        name = "incompatible_disallow_ctx_resolve_tools",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("If set to true, calling the deprecated ctx.resolve_tools API always fails. Uses of this"
                + " API should be replaced by an executable or tools argument to ctx.actions.run or"
                + " ctx.actions.run_shell.")
    )
    abstract fun getIncompatibleDisallowCtxResolveTools(): Boolean

    @Option(
        name = "incompatible_simplify_unconditional_selects_in_rule_attrs",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("If true, simplify configurable rule attributes which contain only unconditional selects;"
                + " for example, if [\"a\"] + select(\"//conditions:default\", [\"b\"]) is assigned"
                + " to a rule attribute, it is stored as [\"a\", \"b\"]. This option does not affect"
                + " attributes of symbolic macros or attribute default values.")
    )
    abstract fun getIncompatibleSimplifyUnconditionalSelectsInRuleAttrs(): Boolean

    @Option(
        name = "experimental_enable_starlark_set",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = "If true, enable the set data type and set() constructor in Starlark."
    )
    abstract fun getExperimentalEnableStarlarkSet(): Boolean

    @Option(
        name = "incompatible_locations_prefers_executable",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("Whether a target that provides an executable expands to the executable rather than the"
                + " files in `DefaultInfo.files` under $(locations ...) expansion if the"
                + " number of files is not 1.")
    )
    abstract fun getIncompatibleLocationsPrefersExecutable(): Boolean

    @Option(
        name = "internal_starlark_utf_8_byte_strings",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        metadataTags = [OptionMetadataTag.HIDDEN],
        help = ("Internal use only. Forces the Starlark implementation to operate on strings as raw"
                + " UTF-8 byte arrays, matching Bazel's internal string encoding.")
    )
    abstract fun getInternalStarlarkUtf8ByteStrings(): Boolean

    /** An enum for specifying different modes for UTF-8 checking of Starlark files.  */
    enum class Utf8EnforcementMode {
        OFF,
        WARNING,
        ERROR;

        /** Converts to [Utf8EnforcementMode].  */
        class Converter : BoolOrEnumConverter<Utf8EnforcementMode?>(
            Utf8EnforcementMode::class.java,
            "UTF-8 enforcement mode",
            Utf8EnforcementMode.ERROR,
            Utf8EnforcementMode.OFF
        )
    }

    @Option(
        name = "incompatible_enforce_starlark_utf8",
        defaultValue = "warning",
        converter = Utf8EnforcementMode.Converter::class,
        documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("If enabled (or set to 'error'), fail if Starlark files are not UTF-8 encoded. If set to"
                + " 'warning', emit a warning instead. If set to 'off', Bazel assumes that Starlark"
                + " files are UTF-8 encoded but does not verify this assumption. Note that Starlark"
                + " files which are not UTF-8 encoded can cause Bazel to behave inconsistently.")
    )
    abstract fun getIncompatibleEnforceStarlarkUtf8(): Utf8EnforcementMode?

    @Option(
        name = "experimental_repository_ctx_execute_wasm",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = "If true enables the repository_ctx `load_wasm` and `execute_wasm` methods."
    )
    abstract fun getRepositoryCtxExecuteWasm(): Boolean

    @Option(
        name = "experimental_repository_ctx_wasm_compilation",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = "If true enables compilation of WebAssembly modules."
    )
    abstract fun getRepositoryCtxWasmCompilation(): Boolean

    @Option(
        name = "incompatible_resolve_select_keys_eagerly",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("If enabled, string keys in dicts passed to select() in .bzl files are immediately"
                + " resolved to Labels relative to the file instead of being interpreted relative to"
                + " the BUILD file they are ultimately loaded from.")
    )
    abstract fun getIncompatibleResolveSelectKeysEagerly(): Boolean

    @Option(
        name = "force_starlark_stack_trace",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        help = ("If --force_starlark_stack_trace=true, Starlark stace traces will always be printed from"
                + " calls to fail(), including those normally supressed with fail(..., stack_trace ="
                + " False)")
    )
    abstract fun getForceStarlarkStackTrace(): Boolean

    private interface FlagConsumer {
        fun <T> set(key: StarlarkSemantics.Key<T?>?, value: T?): FlagConsumer?

        fun setBool(key: String?, enabled: Boolean): FlagConsumer?
    }

    private fun setFlags(consumer: FlagConsumer) {
        val unused =
            consumer
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_STOP_EXPORTING_LANGUAGE_MODULES,
                    getIncompatibleStopExportingLanguageModules()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_ALLOW_TAGS_PROPAGATION,
                    getExperimentalAllowTagsPropagation()
                )!!
                .set<String?>(
                    BuildLanguageOptions.Companion.EXPERIMENTAL_BUILTINS_BZL_PATH,
                    getExperimentalBuiltinsBzlPath()
                )!!
                .setBool(EXPERIMENTAL_BUILTINS_DUMMY, getExperimentalBuiltinsDummy())!!
                .set<MutableList<String?>?>(
                    BuildLanguageOptions.Companion.EXPERIMENTAL_BUILTINS_INJECTION_OVERRIDE,
                    getExperimentalBuiltinsInjectionOverride()
                )!!
                .setBool(EXPERIMENTAL_BZL_VISIBILITY, getExperimentalBzlVisibility())!!
                .setBool(ALLOW_EXPERIMENTAL_LOADS, getAllowExperimentalLoads())!!
                .setBool(CHECK_BZL_VISIBILITY, getCheckBzlVisibility())!!
                .setBool(
                    BuildLanguageOptions.Companion.EXPERIMENTAL_ENABLE_ANDROID_MIGRATION_APIS,
                    getExperimentalEnableAndroidMigrationApis()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.EXPERIMENTAL_SINGLE_PACKAGE_TOOLCHAIN_BINDING,
                    getExperimentalSinglePackageToolchainBinding()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.EXPERIMENTAL_ENABLE_FIRST_CLASS_MACROS,
                    getExperimentalEnableFirstClassMacros()
                )!!
                .setBool(EXPERIMENTAL_ENABLE_SCL_DIALECT, getExperimentalEnableSclDialect())!!
                .setBool(
                    BuildLanguageOptions.Companion.EXPERIMENTAL_ISOLATED_EXTENSION_USAGES,
                    getExperimentalIsolatedExtensionUsages()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_NO_IMPLICIT_WATCH_LABEL,
                    getIncompatibleNoImplicitWatchLabel()
                )!!
                .setBool(EXPERIMENTAL_GOOGLE_LEGACY_API, getExperimentalGoogleLegacyApi())!!
                .setBool(EXPERIMENTAL_PLATFORMS_API, getExperimentalPlatformsApi())!!
                .setBool(EXPERIMENTAL_CC_SHARED_LIBRARY, getExperimentalCcSharedLibrary())!!
                .setBool(EXPERIMENTAL_REPO_REMOTE_EXEC, getExperimentalRepoRemoteExec())!!
                .setBool(
                    BuildLanguageOptions.Companion.EXPERIMENTAL_DISABLE_EXTERNAL_PACKAGE,
                    getExperimentalDisableExternalPackage()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT,
                    getExperimentalSiblingRepositoryLayout()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_ALWAYS_CHECK_DEPSET_ELEMENTS,
                    getIncompatibleAlwaysCheckDepsetElements()
                )!!
                .setBool(INCOMPATIBLE_DISALLOW_EMPTY_GLOB, getIncompatibleDisallowEmptyGlob())!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_PACKAGE_GROUP_HAS_PUBLIC_SYNTAX,
                    getIncompatiblePackageGroupHasPublicSyntax()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_FIX_PACKAGE_GROUP_REPOROOT_SYNTAX,
                    getIncompatibleFixPackageGroupReporootSyntax()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_JAVA_INFO_MERGE_RUNTIME_MODULE_FLAGS,
                    getIncompatibleJavaInfoMergeRuntimeModuleFlags()
                )!!
                .setBool(INCOMPATIBLE_NO_ATTR_LICENSE, getIncompatibleNoAttrLicense())!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_NO_IMPLICIT_FILE_EXPORT,
                    getIncompatibleNoImplicitFileExport()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_NO_RULE_OUTPUTS_PARAM,
                    getIncompatibleNoRuleOutputsParam()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_RUN_SHELL_COMMAND_STRING,
                    getIncompatibleRunShellCommandString()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_REQUIRE_MNEMONIC_FOR_RUN_ACTIONS,
                    getIncompatibleRequireMnemonicForRunActions()
                )!!
                .setBool(StarlarkSemantics.PRINT_TEST_MARKER, getInternalStarlarkFlagTestCanary())!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_DO_NOT_SPLIT_LINKING_CMDLINE,
                    getIncompatibleDoNotSplitLinkingCmdline()
                )!!
                .set<Utf8EnforcementMode?>(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_ENFORCE_STARLARK_UTF8,
                    getIncompatibleEnforceStarlarkUtf8()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_UNAMBIGUOUS_LABEL_STRINGIFICATION,
                    getIncompatibleUnambiguousLabelStringification()
                )!!
                .set<Long?>(MAX_COMPUTATION_STEPS, getMaxComputationSteps())!!
                .set<Int?>(NESTED_SET_DEPTH_LIMIT, getNestedSetDepthLimit())!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_DISABLE_STARLARK_HOST_TRANSITIONS,
                    getIncompatibleDisableStarlarkHostTransitions()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_DISABLE_OBJC_LIBRARY_TRANSITION,
                    getIncompatibleDisableObjcLibraryTransition()
                )!!
                .set<MutableList<String?>?>(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_DISABLE_TRANSITIONS_OPTIONS,
                    getIncompatibleDisableTransitionsOn()
                )!!
                .setBool(ADD_GO_EXEC_GROUPS_TO_BINARY_RULES, getAddGoExecGroupsToBinaryRules())!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_FAIL_ON_UNKNOWN_ATTRIBUTES,
                    getIncompatibleFailOnUnknownAttributes()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_ENABLE_PROTO_TOOLCHAIN_RESOLUTION,
                    getIncompatibleEnableProtoToolchainResolution()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_DISABLE_NON_EXECUTABLE_JAVA_BINARY,
                    getIncompatibleDisableNonExecutableJavaBinary()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_DISABLE_TARGET_DEFAULT_PROVIDER_FIELDS,
                    getIncompatibleDisableTargetDefaultProviderFields()
                )!!
                .setBool(EXPERIMENTAL_RULE_EXTENSION_API, getExperimentalRuleExtensionApi())!!
                .setBool(EXPERIMENTAL_DORMANT_DEPS, getExperimentalDormantDeps())!!
                .setBool(
                    BuildLanguageOptions.Companion.EXPERIMENTAL_STARLARK_TYPE_SYNTAX,
                    getExperimentalStarlarkTypeSyntax()
                )!!
                .setBool(
                    net.starlark.java.eval.StarlarkSemantics.EXPERIMENTAL_STARLARK_STATIC_TYPE_CHECKING,
                    getExperimentalStarlarkStaticTypeChecking()
                )!!
                .setBool(
                    net.starlark.java.eval.StarlarkSemantics.EXPERIMENTAL_STARLARK_DYNAMIC_TYPE_CHECKING,
                    getExperimentalStarlarkDynamicTypeChecking()
                )!!
                .set<MutableList<String?>?>(
                    BuildLanguageOptions.Companion.EXPERIMENTAL_STARLARK_TYPES_ALLOWED_PATHS,
                    getExperimentalStarlarkTypesAllowedPaths()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_ENABLE_DEPRECATED_LABEL_APIS,
                    getEnableDeprecatedLabelApis()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_STOP_EXPORTING_BUILD_FILE_PATH,
                    getIncompatibleStopExportingBuildFilePath()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_DISALLOW_CTX_RESOLVE_TOOLS,
                    getIncompatibleDisallowCtxResolveTools()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_SIMPLIFY_UNCONDITIONAL_SELECTS_IN_RULE_ATTRS,
                    getIncompatibleSimplifyUnconditionalSelectsInRuleAttrs()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_LOCATIONS_PREFERS_EXECUTABLE,
                    getIncompatibleLocationsPrefersExecutable()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.INCOMPATIBLE_RESOLVE_SELECT_KEYS_EAGERLY,
                    getIncompatibleResolveSelectKeysEagerly()
                )!!
                .setBool(
                    net.starlark.java.eval.StarlarkSemantics.EXPERIMENTAL_ENABLE_STARLARK_SET,
                    getExperimentalEnableStarlarkSet()
                )!!
                .setBool(
                    net.starlark.java.eval.StarlarkSemantics.INTERNAL_BAZEL_ONLY_UTF_8_BYTE_STRINGS,
                    getInternalStarlarkUtf8ByteStrings()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.EXPERIMENTAL_REPOSITORY_CTX_EXECUTE_WASM,
                    getRepositoryCtxExecuteWasm()
                )!!
                .setBool(
                    BuildLanguageOptions.Companion.EXPERIMENTAL_REPOSITORY_CTX_WASM_COMPILATION,
                    getRepositoryCtxWasmCompilation()
                )!!
                .setBool(StarlarkSemantics.FORCE_STARLARK_STACK_TRACE, getForceStarlarkStackTrace())
    }

    /** Constructs a [StarlarkSemantics] object corresponding to this set of option values.  */
    fun toStarlarkSemantics(): StarlarkSemantics {
        // This function connects command-line flags to their corresponding StarlarkSemantics keys.
        val builder = StarlarkSemantics.builder()
        setFlags(
            object : FlagConsumer {
                override fun <T> set(key: StarlarkSemantics.Key<T?>, value: T?): FlagConsumer {
                    builder.set<T?>(key, value)
                    return this
                }

                override fun setBool(key: String, enabled: Boolean): FlagConsumer {
                    builder.setBool(key, enabled)
                    return this
                }
            })
        return INTERNER.intern(builder.build())
    }

    companion object {
        // DO NOT inline this constant: it's used by copybara to alter the default value. It's different
        // in Google and in OSS Bazel.
        private const val DISALLOW_EMPTY_GLOB_DEFAULT = "true"

        /**
         * An interner to reduce the number of StarlarkSemantics instances. A single Blaze instance should
         * never accumulate a large number of these and being able to shortcut on object identity makes a
         * comparison later much faster. In particular, the semantics become part of the
         * MethodDescriptorKey in CallExpression and are thus compared for every function call.
         */
        private val INTERNER: Interner<StarlarkSemantics> = BlazeInterners.newWeakInterner()

        /**
         * Returns a fingerprint of the given [StarlarkSemantics] object that can be compared across
         * Bazel versions.
         */
        @kotlin.jvm.JvmStatic
        fun stableFingerprint(semantics: StarlarkSemantics?): ByteString {
            return FINGERPRINT_CACHE.getUnchecked(semantics)
        }

        // See the comment on INTERNER above, this cache should be very small.
        private val FINGERPRINT_CACHE: LoadingCache<StarlarkSemantics?, ByteString> = CacheBuilder.newBuilder()
            .weakKeys()
            .build<StarlarkSemantics?, ByteString?>(
                object : CacheLoader<StarlarkSemantics?, ByteString?>() {
                    override fun load(key: StarlarkSemantics): ByteString {
                        return computeFingerprint(key)
                    }
                })

        private fun computeFingerprint(semantics: StarlarkSemantics): ByteString {
            val enabledBoolFlags: TreeSet<String?> = TreeSet<String?>()
            val otherFlags: TreeMap<String?, String?> = TreeMap<String?, String?>()
            // We only care about the keys of the map, so the BuildLanguageOptions instance doesn't matter.
            Options.getDefaults<BuildLanguageOptions?>(BuildLanguageOptions::class.java)
                .setFlags(
                    object : FlagConsumer {
                        override fun <T> set(key: StarlarkSemantics.Key<T?>, ignored: T?): FlagConsumer {
                            // This assumes that all non-boolean values have a stable and unique string
                            // representation, which seems likely to remain true over time.
                            otherFlags.put(key.name, semantics.get<T?>(key).toString())
                            return this
                        }

                        override fun setBool(key: String, ignored: Boolean): FlagConsumer {
                            // Only fingerprint enabled options so that the fingerprint is stable across Bazel
                            // versions that only add new options (e.g., minor and patch versions). This relies
                            // on the assumption that disabled new options have no observable effect.
                            if (semantics.getBool(key)) {
                                // Trim the leading '+' or '-' from the flag names - the default value doesn't
                                // matter for the current value, which is what we need to fingerprint.
                                enabledBoolFlags.add(key.substring(1))
                            }
                            return this
                        }
                    })
            return ByteString.copyFrom(
                Fingerprint().addStrings(enabledBoolFlags).addStringMap(otherFlags).digestAndReset()
            )
        }

        // StarlarkSemantics keys used by Bazel
        //
        // Sadly it is impossible to move most of these declarations closer to the
        // code they affect: the toStarlarkSemantics function above must depend
        // on every key that is bound to a command-line flag, which means those keys must
        // live in this package, not in the application logic above.
        // (In principle, a key not associated with a command-line flag may be declared anywhere.)
        // booleans: the +/- prefix indicates the default value (true/false).
        const val INCOMPATIBLE_STOP_EXPORTING_LANGUAGE_MODULES: String = "-incompatible_stop_exporting_language_modules"
        const val INCOMPATIBLE_ALLOW_TAGS_PROPAGATION: String = "+incompatible_allow_tags_propagation"
        const val EXPERIMENTAL_BUILTINS_DUMMY: String = "-experimental_builtins_dummy"
        const val EXPERIMENTAL_BZL_VISIBILITY: String = "+experimental_bzl_visibility"
        const val ALLOW_EXPERIMENTAL_LOADS: String = "-allow_experimental_loads"
        const val CHECK_BZL_VISIBILITY: String = "+check_bzl_visibility"
        const val EXPERIMENTAL_CC_SHARED_LIBRARY: String = "-experimental_cc_shared_library"
        const val EXPERIMENTAL_DISABLE_EXTERNAL_PACKAGE: String = "-experimental_disable_external_package"
        const val EXPERIMENTAL_ENABLE_ANDROID_MIGRATION_APIS: String = "-experimental_enable_android_migration_apis"
        const val EXPERIMENTAL_SINGLE_PACKAGE_TOOLCHAIN_BINDING: String =
            "-experimental_single_package_toolchain_binding"
        const val EXPERIMENTAL_ENABLE_FIRST_CLASS_MACROS: String = "+experimental_enable_first_class_macros"
        const val EXPERIMENTAL_ENABLE_SCL_DIALECT: String = "+experimental_enable_scl_dialect"
        const val EXPERIMENTAL_ISOLATED_EXTENSION_USAGES: String = "-experimental_isolated_extension_usages"
        const val INCOMPATIBLE_NO_IMPLICIT_WATCH_LABEL: String = "+incompatible_no_implicit_watch_label"
        const val EXPERIMENTAL_GOOGLE_LEGACY_API: String = "-experimental_google_legacy_api"
        const val EXPERIMENTAL_PLATFORMS_API: String = "-experimental_platforms_api"
        const val EXPERIMENTAL_REPO_REMOTE_EXEC: String = "-experimental_repo_remote_exec"
        const val EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT: String = "-experimental_sibling_repository_layout"
        const val INCOMPATIBLE_ALWAYS_CHECK_DEPSET_ELEMENTS: String = "+incompatible_always_check_depset_elements"

        // Note that INCOMPATIBLE_DISALLOW_EMPTY_GLOB differs in Google and in OSS Bazel.
        const val INCOMPATIBLE_DISALLOW_EMPTY_GLOB: String = "+incompatible_disallow_empty_glob"
        val INCOMPATIBLE_PACKAGE_GROUP_HAS_PUBLIC_SYNTAX: String =
            FlagConstants.INCOMPATIBLE_PACKAGE_GROUP_HAS_PUBLIC_SYNTAX
        val INCOMPATIBLE_FIX_PACKAGE_GROUP_REPOROOT_SYNTAX: String =
            FlagConstants.INCOMPATIBLE_FIX_PACKAGE_GROUP_REPOROOT_SYNTAX
        const val INCOMPATIBLE_DO_NOT_SPLIT_LINKING_CMDLINE: String = "+incompatible_do_not_split_linking_cmdline"
        const val INCOMPATIBLE_JAVA_INFO_MERGE_RUNTIME_MODULE_FLAGS: String =
            "-incompatible_java_info_merge_runtime_module_flags"
        const val INCOMPATIBLE_NO_ATTR_LICENSE: String = "+incompatible_no_attr_license"
        @kotlin.jvm.JvmField
        val INCOMPATIBLE_NO_IMPLICIT_FILE_EXPORT: String =
            FlagConstants.DEFAULT_INCOMPATIBLE_NO_IMPLICIT_FILE_EXPORT_NAME
        const val INCOMPATIBLE_NO_RULE_OUTPUTS_PARAM: String = "-incompatible_no_rule_outputs_param"
        const val INCOMPATIBLE_RUN_SHELL_COMMAND_STRING: String = "+incompatible_run_shell_command_string"
        const val INCOMPATIBLE_REQUIRE_MNEMONIC_FOR_RUN_ACTIONS: String =
            "-incompatible_require_mnemonic_for_run_actions"

        const val INCOMPATIBLE_UNAMBIGUOUS_LABEL_STRINGIFICATION: String =
            "+incompatible_unambiguous_label_stringification"
        const val INCOMPATIBLE_DISABLE_STARLARK_HOST_TRANSITIONS: String =
            "-incompatible_disable_starlark_host_transitions"
        const val INCOMPATIBLE_DISABLE_OBJC_LIBRARY_TRANSITION: String = "+incompatible_disable_objc_library_transition"
        const val ADD_GO_EXEC_GROUPS_TO_BINARY_RULES: String = "-add_go_exec_groups_to_binary_rules"
        const val INCOMPATIBLE_FAIL_ON_UNKNOWN_ATTRIBUTES: String = "+incompatible_fail_on_unknown_attributes"
        @kotlin.jvm.JvmField
        val INCOMPATIBLE_ENABLE_PROTO_TOOLCHAIN_RESOLUTION: String =
            FlagConstants.DEFAULT_INCOMPATIBLE_ENABLE_PROTO_TOOLCHAIN_RESOLUTION_NAME
        const val INCOMPATIBLE_DISABLE_NON_EXECUTABLE_JAVA_BINARY: String =
            "-incompatible_disable_non_executable_java_binary"
        const val INCOMPATIBLE_DISABLE_TARGET_DEFAULT_PROVIDER_FIELDS: String =
            "-incompatible_disable_target_default_provider_fields"
        val EXPERIMENTAL_RULE_EXTENSION_API: String = FlagConstants.DEFAULT_EXPERIMENTAL_RULE_EXTENSION_API_NAME
        const val EXPERIMENTAL_DORMANT_DEPS: String = "-experimental_dormant_deps"

        @kotlin.jvm.JvmField
        val EXPERIMENTAL_STARLARK_TYPE_SYNTAX: String = FlagConstants.EXPERIMENTAL_STARLARK_TYPE_SYNTAX_FLAG_NAME
        const val INCOMPATIBLE_ENABLE_DEPRECATED_LABEL_APIS: String = "+incompatible_enable_deprecated_label_apis"
        const val INCOMPATIBLE_STOP_EXPORTING_BUILD_FILE_PATH: String = "-incompatible_stop_exporting_build_file_path"
        const val INCOMPATIBLE_DISALLOW_CTX_RESOLVE_TOOLS: String = "+incompatible_disallow_ctx_resolve_tools"
        const val INCOMPATIBLE_SIMPLIFY_UNCONDITIONAL_SELECTS_IN_RULE_ATTRS: String =
            "+incompatible_simplify_unconditional_selects_in_rule_attrs"
        const val INCOMPATIBLE_LOCATIONS_PREFERS_EXECUTABLE: String = "+incompatible_locations_prefers_executable"
        const val EXPERIMENTAL_REPOSITORY_CTX_EXECUTE_WASM: String = "-experimental_repository_ctx_execute_wasm"
        const val EXPERIMENTAL_REPOSITORY_CTX_WASM_COMPILATION: String = "-experimental_repository_ctx_wasm_compilation"
        const val INCOMPATIBLE_RESOLVE_SELECT_KEYS_EAGERLY: String = "-incompatible_resolve_select_keys_eagerly"

        // non-booleans
        @kotlin.jvm.JvmField
        val INCOMPATIBLE_DISABLE_TRANSITIONS_OPTIONS: StarlarkSemantics.Key<MutableList<String?>?> =
            StarlarkSemantics.Key<MutableList<String?>?>(
                "incompatible_disable_transitions_on",
                ImmutableList.of<String?>()
            )
        @kotlin.jvm.JvmField
        val EXPERIMENTAL_BUILTINS_BZL_PATH: StarlarkSemantics.Key<String?> =
            StarlarkSemantics.Key<String?>("experimental_builtins_bzl_path", "%bundled%")
        @kotlin.jvm.JvmField
        val EXPERIMENTAL_BUILTINS_INJECTION_OVERRIDE: StarlarkSemantics.Key<MutableList<String?>?> =
            StarlarkSemantics.Key<MutableList<String?>?>(
                "experimental_builtins_injection_override",
                ImmutableList.of<String?>()
            )
        @kotlin.jvm.JvmField
        val INCOMPATIBLE_ENFORCE_STARLARK_UTF8: StarlarkSemantics.Key<Utf8EnforcementMode?> =
            StarlarkSemantics.Key<Utf8EnforcementMode?>(
                "incompatible_enforce_starlark_utf8", Utf8EnforcementMode.WARNING
            )
        @kotlin.jvm.JvmField
        val EXPERIMENTAL_STARLARK_TYPES_ALLOWED_PATHS: StarlarkSemantics.Key<MutableList<String?>?> =
            StarlarkSemantics.Key<MutableList<String?>?>(
                "experimental_starlark_types",
                if (FlagConstants.DEFAULT_EXPERIMENTAL_STARLARK_TYPES_ALLOWED_PATHS.isEmpty())
                    ImmutableList.of<String?>()
                else
                    ImmutableList.copyOf<String?>(
                        FlagConstants.DEFAULT_EXPERIMENTAL_STARLARK_TYPES_ALLOWED_PATHS.split(",")
                    )
            )

        @kotlin.jvm.JvmField
        val MAX_COMPUTATION_STEPS: StarlarkSemantics.Key<Long?> =
            StarlarkSemantics.Key<Long?>("max_computation_steps", 0L)
        val NESTED_SET_DEPTH_LIMIT: StarlarkSemantics.Key<Int?> =
            StarlarkSemantics.Key<Int?>("nested_set_depth_limit", 3500)
    }
}
