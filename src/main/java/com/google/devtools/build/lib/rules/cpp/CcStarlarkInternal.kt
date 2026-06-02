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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.analysis.constraints.ConstraintConstants.getOsFromConstraintsOrHost

/** Utility methods for rules in Starlark Builtins  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "cc_internal",
    category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
    documented = false
)
class CcStarlarkInternal : net.starlark.java.eval.StarlarkValue {
    @net.starlark.java.annot.StarlarkMethod(
        name = "check_private_api",
        documented = false,
        useStarlarkThread = true,
        parameters = [net.starlark.java.annot.Param(
            name = "allowlist",
            documented = false,
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = net.starlark.java.eval.Tuple::class
            )]
        ), net.starlark.java.annot.Param(
            name = "depth",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "1"
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun checkPrivateApi(allowlistObject: Any?, depth: Any?, thread: net.starlark.java.eval.StarlarkThread) {
        // This method may be called anywhere from builtins, but not outside (because it's not exposed
        // in cc_common.bzl
        val module: net.starlark.java.eval.Module? =
            net.starlark.java.eval.Module.ofInnermostEnclosingStarlarkFunction(
                thread, if (depth == null) 1 else (depth as net.starlark.java.eval.StarlarkInt).toIntUnchecked()
            )
        if (module == null) {
            // The module is null when the call is coming from one of the callbacks passed to execution
            // phase
            return
        }

        val bazelModuleContext: BazelModuleContext? = module.getClientData() as BazelModuleContext?
        val allowlist: BuiltinRestriction.Allowlist? = allowlistFromStarlark(allowlistObject)
        BuiltinRestriction.failIfModuleOutsideAllowlist(bazelModuleContext, allowlist)
    }

    /** Wraps a dictionary of build variables into CcToolchainVariables.  */
    @net.starlark.java.annot.StarlarkMethod(
        name = "cc_toolchain_variables",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "vars", positional = false, named = true)]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getCcToolchainVariables(buildVariables: net.starlark.java.eval.Dict<*, *>?): CcToolchainVariables {
        return MapVariables(
            null,
            net.starlark.java.eval.Dict.cast<String?, Any?>(buildVariables, String::class.java, Any::class.java, "vars")
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "combine_cc_toolchain_variables",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "parent",
            allowedTypes = [net.starlark.java.annot.ParamType(type = CcToolchainVariables::class)]
        )],
        extraPositionals = net.starlark.java.annot.Param(
            name = "variables",
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = CcToolchainVariables::class
            )]
        )
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun combineCcToolchainVariables(
        parent: CcToolchainVariables?, variablesSequenceUnchecked: net.starlark.java.eval.Sequence<*>?
    ): CcToolchainVariables? {
        val variablesSequence: net.starlark.java.eval.Sequence<CcToolchainVariables> =
            net.starlark.java.eval.Sequence.cast<CcToolchainVariables?>(
                variablesSequenceUnchecked,
                CcToolchainVariables::class.java,
                "variables"
            )
        val builder: com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Builder =
            CcToolchainVariables.Companion.builder(parent)
        for (variables in variablesSequence) {
            builder.addAllNonTransitive(variables)
        }
        return builder.build()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "intern_string_sequence_variable_value",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "string_sequence")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun internStringSequenceVariableValue(stringSequence: net.starlark.java.eval.Sequence<*>?): net.starlark.java.eval.Sequence<String?> {
        return net.starlark.java.eval.Sequence.cast<String?>(
            interner.intern(
                net.starlark.java.eval.StarlarkList.immutableCopyOf<String?>(
                    net.starlark.java.eval.Sequence.cast<String?>(stringSequence, String::class.java, "string_sequence")
                )
            ),
            String::class.java,
            "string_sequence"
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "solib_symlink_action",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "ctx",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "artifact",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "solib_directory",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(name = "runtime_solib_dir_base", positional = false, named = true)]
    )
    fun solibSymlinkAction(
        ruleContext: StarlarkRuleContext,
        artifact: Artifact,
        solibDirectory: String?,
        runtimeSolibDirBase: String?
    ): Artifact? {
        return SolibSymlinkAction.Companion.getCppRuntimeSymlink(
            ruleContext.getRuleContext(), artifact, solibDirectory, runtimeSolibDirBase
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "dynamic_library_symlink",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "actions"), net.starlark.java.annot.Param(name = "library"), net.starlark.java.annot.Param(
            name = "solib_directory"
        ), net.starlark.java.annot.Param(name = "preserve_name"), net.starlark.java.annot.Param(name = "prefix_consumer")]
    )
    fun dynamicLibrarySymlinkAction(
        actions: StarlarkActionFactory,
        library: Artifact,
        solibDirectory: String?,
        preserveName: Boolean,
        prefixConsumer: Boolean
    ): Artifact? {
        return SolibSymlinkAction.Companion.getDynamicLibrarySymlink(
            actions.getRuleContext(), solibDirectory, library, preserveName, prefixConsumer
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "dynamic_library_symlink2",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "actions"), net.starlark.java.annot.Param(name = "library"), net.starlark.java.annot.Param(
            name = "solib_directory"
        ), net.starlark.java.annot.Param(name = "path")]
    )
    fun dynamicLibrarySymlinkAction2(
        actions: StarlarkActionFactory, library: Artifact, solibDirectory: String?, path: String?
    ): Artifact? {
        return SolibSymlinkAction.Companion.getDynamicLibrarySymlink(
            actions.getRuleContext(), solibDirectory, library, PathFragment.create(path)
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "dynamic_library_soname",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "actions"), net.starlark.java.annot.Param(name = "path"), net.starlark.java.annot.Param(
            name = "preserve_name"
        )]
    )
    fun dynamicLibrarySoname(
        actions: WrappedStarlarkActionFactory, path: String?, preserveName: Boolean
    ): String? {
        return SolibSymlinkAction.Companion.getDynamicLibrarySoname(
            PathFragment.create(path),
            preserveName,
            actions.construction.getContext().getConfiguration().getMnemonic()
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "cc_toolchain_features",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "toolchain_config_info",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(name = "tools_directory", positional = false, named = true)]
    )
    @Throws(net.starlark.java.eval.EvalException::class, RuleErrorException::class)
    fun ccToolchainFeatures(
        ccToolchainConfigInfo: StarlarkInfo?, toolsDirectoryPathString: String?
    ): CcToolchainFeatures {
        return CcToolchainFeatures(
            CcToolchainConfigInfo.Companion.PROVIDER.wrap(ccToolchainConfigInfo),
            PathFragment.create(toolsDirectoryPathString)
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "is_package_headers_checking_mode_set",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "ctx", positional = false, named = true)]
    )
    fun isPackageHeadersCheckingModeSetForStarlark(
        starlarkRuleContext: StarlarkRuleContext
    ): Boolean {
        return starlarkRuleContext
            .getRuleContext()
            .getRule()
            .getPackageDeclarations()
            .getPackageArgs()
            .isDefaultHdrsCheckSet()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "package_headers_checking_mode",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "ctx", positional = false, named = true)]
    )
    fun getPackageHeadersCheckingModeForStarlark(starlarkRuleContext: StarlarkRuleContext): String {
        return starlarkRuleContext
            .getRuleContext()
            .getRule()
            .getPackageDeclarations()
            .getPackageArgs()
            .getDefaultHdrsCheck()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "is_package_headers_checking_mode_set_for_aspect",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "ctx", positional = false, named = true)]
    )
    fun isPackageHeadersCheckingModeSetForStarlarkAspect(
        starlarkRuleContext: StarlarkRuleContext
    ): Boolean {
        return starlarkRuleContext
            .getRuleContext()
            .getTarget()
            .getPackageDeclarations()
            .getPackageArgs()
            .isDefaultHdrsCheckSet()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "package_headers_checking_mode_for_aspect",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "ctx", positional = false, named = true)]
    )
    fun getPackageHeadersCheckingModeForStarlarkAspect(
        starlarkRuleContext: StarlarkRuleContext
    ): String {
        return starlarkRuleContext
            .getRuleContext()
            .getTarget()
            .getPackageDeclarations()
            .getPackageArgs()
            .getDefaultHdrsCheck()
    }

    /**
     * TODO(bazel-team): This can be re-written directly to Starlark but it will cause a memory
     * regression due to the way StarlarkComputedDefault is stored for each rule.
     */
    internal class StlComputedDefault : ComputedDefault(), NativeComputedDefaultApi {
        public override fun getDefault(rule: AttributeMap): Any? {
            return if (rule.getOrDefault("tags", Types.STRING_LIST, com.google.common.collect.ImmutableList.of<E?>())
                    .contains("__CC_STL__")
            )
                null
            else
                Label.parseCanonicalUnchecked("@//third_party/stl")
        }
    }

    @get:net.starlark.java.annot.StarlarkMethod(name = "stl_computed_default", documented = false)
    val stlComputedDefault: ComputedDefault?
        get() = StlComputedDefault()

    @net.starlark.java.annot.StarlarkMethod(
        name = "get_artifact_name_for_category",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "cc_toolchain",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "category",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(name = "output_name", positional = false, named = true)]
    )
    @Throws(RuleErrorException::class, net.starlark.java.eval.EvalException::class)
    fun getArtifactNameForCategory(ccToolchainInfo: Info, category: String?, outputName: String?): String? {
        val ccToolchain: CcToolchainProvider = CcToolchainProvider.Companion.wrap(ccToolchainInfo)
        return ccToolchain
            .getFeatures()
            .getArtifactNameForCategory(
                com.google.devtools.build.lib.rules.cpp.ArtifactCategory.valueOf(category),
                outputName
            )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "get_artifact_name_extension_for_category",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "cc_toolchain", named = true), net.starlark.java.annot.Param(
            name = "category",
            named = true
        )]
    )
    @Throws(RuleErrorException::class, net.starlark.java.eval.EvalException::class)
    fun getArtifactNameExtensionForCategory(ccToolchainInfo: Info, category: String?): String? {
        val ccToolchain: CcToolchainProvider = CcToolchainProvider.Companion.wrap(ccToolchainInfo)
        return ccToolchain
            .getFeatures()
            .getArtifactNameExtensionForCategory(
                com.google.devtools.build.lib.rules.cpp.ArtifactCategory.valueOf(
                    category
                )
            )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "absolute_symlink",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "ctx",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "output",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "target_path",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(name = "progress_message", positional = false, named = true)]
    ) // TODO(b/333997009): remove command line flags that specify FDO with absolute path
    fun absoluteSymlink(
        ctx: StarlarkActionContext, output: Artifact?, targetPath: String?, progressMessage: String?
    ) {
        val action: SymlinkAction? =
            SymlinkAction.toAbsolutePath(
                ctx.getRuleContext().getActionOwner(),
                PathFragment.create(targetPath),
                output,
                progressMessage
            )
        ctx.getRuleContext().registerAction(action)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "intern_seq",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "seq")]
    )
    fun internList(seq: net.starlark.java.eval.Sequence<*>): net.starlark.java.eval.Sequence<*>? {
        return net.starlark.java.eval.Tuple.copyOf(
            com.google.common.collect.Iterables.transform(
                seq,
                { sample: E? -> interner.intern(sample) })
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "get_link_args",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "action_name",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "feature_configuration",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "build_variables",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "parameter_file_type",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )]
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getArgs(
        actionName: String?,
        featureConfiguration: FeatureConfigurationForStarlark,
        buildVariables: CcToolchainVariables?,
        paramFileType: Any?
    ): Args {
        val linkCommandLineBuilder: com.google.devtools.build.lib.rules.cpp.LinkCommandLine.Builder =
            com.google.devtools.build.lib.rules.cpp.LinkCommandLine.Builder()
                .setActionName(actionName)
                .setBuildVariables(buildVariables)
                .setFeatureConfiguration(featureConfiguration.getFeatureConfiguration())
        if (paramFileType is String) {
            linkCommandLineBuilder
                .setParameterFileType(ParameterFileType.valueOf(paramFileType))
                .setSplitCommandLine(true)
        }
        val linkCommandLine: LinkCommandLine = linkCommandLineBuilder.build()
        return Args.forRegisteredAction(
            CommandLineAndParamFileInfo(linkCommandLine, linkCommandLine.getParamFileInfo()),
            com.google.common.collect.ImmutableSet.of<E?>()
        )
    }

    internal class WrappedStarlarkActionFactory(parent: StarlarkActionFactory?, construction: LinkActionConstruction) :
        StarlarkActionFactory(parent) {
        val construction: LinkActionConstruction

        init {
            this.construction = construction
        }

        public override fun createShareableArtifact(
            path: String?, artifactRoot: Any?, thread: net.starlark.java.eval.StarlarkThread?
        ): FileApi? {
            return construction.create(PathFragment.create(path))
        }

        @net.starlark.java.annot.StarlarkMethod(
            name = "declare_shareable_directory",
            parameters = [net.starlark.java.annot.Param(name = "path")],
            documented = false
        )
        fun createShareableDirectory(path: String?): FileApi? {
            return construction.createTreeArtifact(PathFragment.create(path))
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "wrap_link_actions",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "actions"), net.starlark.java.annot.Param(
            name = "build_configuration",
            defaultValue = "None"
        ), net.starlark.java.annot.Param(name = "sharable_artifacts", defaultValue = "False")]
    )
    fun wrapLinkActions(
        actions: StarlarkActionFactory, config: Any?, shareableArtifacts: Boolean
    ): WrappedStarlarkActionFactory {
        val construction: LinkActionConstruction =
            CppLinkActionBuilder.newActionConstruction(
                actions.getRuleContext(),
                if (config is BuildConfigurationValue)
                    config as BuildConfigurationValue?
                else
                    actions.getRuleContext().getConfiguration(),
                shareableArtifacts
            )
        return WrappedStarlarkActionFactory(actions, construction)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "actions2ctx_cheat",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "actions")]
    )
    fun getStarlarkRuleContext(actions: StarlarkActionFactory): StarlarkRuleContext {
        return actions.getRuleContext().getStarlarkRuleContext()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "exec_os",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "ctx")]
    )
    fun getExecOs(ctx: StarlarkRuleContext): String {
        return getOsFromConstraintsOrHost(ctx.getRuleContext().getExecutionPlatform()).name()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "rule_class",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "ctx")]
    )
    fun getRuleClass(ctx: StarlarkRuleContext): String {
        return ctx.getRuleContext().getRule().getRuleClass()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "aspect_class",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "ctx")],
        allowReturnNones = true
    )
    fun getAspectClass(ctx: StarlarkRuleContext): String? {
        if (ctx.getAspectDescriptor() == null) {
            return null
        }
        var aspectName: String = ctx.getAspectDescriptor().getAspectClass().getName()
        // Starlark aspects names are of the form //my/aspect.bzl%aspect
        if (aspectName.contains("%")) {
            aspectName = aspectName.split("%", -1)[1]
        }
        return aspectName
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "collect_per_file_lto_backend_opts",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "cpp_config"), net.starlark.java.annot.Param(name = "obj")]
    )
    fun collectPerFileLtoBackendOpts(
        cppConfiguration: CppConfiguration, objectFile: Artifact?
    ): com.google.common.collect.ImmutableList<String?> {
        return cppConfiguration.getPerFileLtoBackendOpts().stream()
            .filter(java.util.function.Predicate { perLabelOptions: PerLabelOptions? ->
                perLabelOptions.isIncluded(
                    objectFile
                )
            })
            .map<Any?>(PerLabelOptions::getOptions)
            .flatMap<Any?>(java.util.function.Function { options: Any? -> options.stream() })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
    }

    // TODO(b/396122076): Test whether this can be replaced with artifact.is_directory().
    @net.starlark.java.annot.StarlarkMethod(
        name = "is_tree_artifact",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "artifact",
            allowedTypes = [net.starlark.java.annot.ParamType(type = Artifact::class)]
        )]
    )
    fun isTreeArtifact(artifact: Artifact): Boolean {
        return artifact.isTreeArtifact()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "compute_output_name_prefix_dir",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "configuration",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(name = "purpose", positional = false, named = true)]
    )
    fun computeOutputNamePrefixDir(configuration: BuildConfigurationValue, purpose: String?): String? {
        var outputNamePrefixDir: String? = null
        // purpose is only used by objc rules; if set it ends with either "_non_objc_arc" or
        // "_objc_arc", and it is used to override configuration.getMnemonic() to prefix the output
        // dir with "non_arc" or "arc".
        var mnemonic: String? = configuration.getMnemonic()
        if (purpose != null) {
            mnemonic = purpose
        }
        if (mnemonic.endsWith("_objc_arc")) {
            outputNamePrefixDir = if (mnemonic.endsWith("_non_objc_arc")) "non_arc" else "arc"
        }
        return java.util.Objects.requireNonNullElse<String?>(outputNamePrefixDir, "")
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "create_cc_compile_action",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "action_construction_context",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = StarlarkRuleContext::class), net.starlark.java.annot.ParamType(
                type = StarlarkTemplateContext::class
            )]
        ), net.starlark.java.annot.Param(
            name = "cc_compilation_context",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "cc_toolchain",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "configuration",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "copts_filter",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "feature_configuration",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "source",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "additional_compilation_inputs",
            positional = false,
            named = true,
            defaultValue = "[]"
        ), net.starlark.java.annot.Param(
            name = "additional_compilation_inputs_set",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = Depset::class,
                generic1 = Artifact::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "additional_include_scanning_roots",
            positional = false,
            named = true,
            defaultValue = "[]"
        ), net.starlark.java.annot.Param(
            name = "output_file",
            positional = false,
            named = true,
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "dotd_file",
            positional = false,
            named = true,
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "diagnostics_file",
            positional = false,
            named = true,
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "gcno_file",
            positional = false,
            named = true,
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "dwo_file",
            positional = false,
            named = true,
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "lto_indexing_file",
            positional = false,
            named = true,
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "use_pic",
            positional = false,
            named = true,
            defaultValue = "False"
        ), net.starlark.java.annot.Param(
            name = "compile_build_variables",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "cache_key_inputs",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = Depset::class,
                generic1 = Artifact::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "build_info_header_files",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = Artifact::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "additional_prunable_headers",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = Depset::class,
                generic1 = Artifact::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "action_name",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "progress_message_prefix",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "should_scan_includes",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "shareable",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "module_files",
            positional = false,
            named = true,
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "modmap_file",
            positional = false,
            named = true,
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "modmap_input_file",
            positional = false,
            named = true,
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "additional_outputs",
            positional = false,
            named = true,
            defaultValue = "[]"
        ), net.starlark.java.annot.Param(
            name = "needs_include_validation",
            positional = false,
            named = true,
            defaultValue = "False"
        ), net.starlark.java.annot.Param(name = "toolchain_type", positional = false, named = true)]
    )
    @Throws(net.starlark.java.eval.EvalException::class, TypeException::class)
    fun createCppCompileAction(
        actionConstructionContextUnchecked: Any?,
        ccCompilationContext: StarlarkInfo?,
        ccToolchain: StarlarkInfo?,
        configuration: BuildConfigurationValue,
        coptsFilterObject: Any?,
        featureConfigurationForStarlark: FeatureConfigurationForStarlark,
        sourceArtifact: Artifact?,
        additionalCompilationInputs: net.starlark.java.eval.Sequence<*>,
        additionalCompilationInputsSet: Any?,
        additionalIncludeScanningRoots: net.starlark.java.eval.Sequence<*>,
        outputFile: Any?,
        dotdFile: Any?,
        diagnosticsFile: Any?,
        gcnoFile: Any?,
        dwoFile: Any?,
        ltoIndexingFile: Any?,
        usePic: Boolean,
        compileBuildVariables: CcToolchainVariables?,
        cacheKeyInputs: Any?,
        buildInfoHeaderArtifacts: Any?,
        additionalPrunableHeaders: Any?,
        actionName: Any?,
        progressMessagePrefix: Any?,
        shouldScanIncludes: Any?,
        shareable: Any?,
        moduleFiles: Any?,
        modmapFile: Any?,
        modmapInputFile: Any?,
        additionalOutputs: net.starlark.java.eval.Sequence<*>?,
        needsIncludeValidation: Boolean,
        toolchainType: String?
    ) {
        val ccActionContext: CcActionContext?
        if (actionConstructionContextUnchecked is StarlarkRuleContext) {
            ccActionContext = CcRuleContext(actionConstructionContextUnchecked.getRuleContext(), toolchainType)
        } else if (actionConstructionContextUnchecked
                    is StarlarkTemplateContext
        ) {
            ccActionContext = CcTemplateContext(actionConstructionContextUnchecked)
        } else {
            throw net.starlark.java.eval.EvalException(
                "action_construction_context must be either StarlarkRuleContext or"
                        + " StarlarkTemplateContext"
            )
        }
        val coptsFilter: CoptsFilter =
            createCoptsFilter(
                if (net.starlark.java.eval.Starlark.isNullOrNone(coptsFilterObject)) null else coptsFilterObject as String
            )
        val builder: CppCompileActionBuilder =
            createCppCompileActionBuilder(
                ccActionContext.actionOwner,
                CcCompilationContext.Companion.of(ccCompilationContext),
                ccToolchain,
                configuration,
                coptsFilter,
                featureConfigurationForStarlark,
                sourceArtifact,
                additionalCompilationInputs,
                additionalIncludeScanningRoots,
                outputFile,
                dotdFile,
                diagnosticsFile,
                gcnoFile,
                dwoFile,
                ltoIndexingFile,
                usePic,
                needsIncludeValidation,
                ccActionContext.executionInfo
            )
        if (additionalCompilationInputsSet is Depset) {
            builder.addMandatoryInputs(additionalCompilationInputsSet.getSet(Artifact::class.java))
        }
        builder.setVariables(compileBuildVariables)
        if (cacheKeyInputs !== net.starlark.java.eval.Starlark.NONE) {
            builder.setCacheKeyInputs(Depset.cast(cacheKeyInputs, Artifact::class.java, "cache_key_inputs"))
        }
        if (buildInfoHeaderArtifacts !== net.starlark.java.eval.Starlark.NONE) {
            builder.setBuildInfoHeaderArtifacts(
                net.starlark.java.eval.Sequence.cast<Artifact?>(
                    buildInfoHeaderArtifacts,
                    Artifact::class.java,
                    "builtin_header_files"
                )
                    .getImmutableList()
            )
        }
        if (actionName is String) {
            builder.setActionName(actionName)
        }
        if (progressMessagePrefix is String) {
            builder.setProgressMessagePrefix(progressMessagePrefix)
        }
        if (shouldScanIncludes is Boolean) {
            builder.setShouldScanIncludes(shouldScanIncludes)
        }
        if (shareable is Boolean) {
            builder.setShareable(shareable)
        }
        if (additionalPrunableHeaders is Depset) {
            builder.setAdditionalPrunableHeaders(additionalPrunableHeaders.getSet(Artifact::class.java))
        }
        builder.setModuleFiles(Depset.noneableCast(moduleFiles, Artifact::class.java, "module_files"))
        builder.setModmapFile(CcModule.Companion.nullIfNone<Artifact?>(modmapFile, Artifact::class.java))
        builder.setModmapInputFile(CcModule.Companion.nullIfNone<Artifact?>(modmapInputFile, Artifact::class.java))
        builder.setAdditionalOutputs(
            net.starlark.java.eval.Sequence.cast<Artifact?>(
                additionalOutputs,
                Artifact::class.java,
                "additional_outputs"
            ).getImmutableList()
        )
        try {
            val compileAction: CppCompileAction = builder.buildAndVerify()
            ccActionContext.registerAction(compileAction)
        } catch (e: UnconfiguredActionConfigException) {
            throw net.starlark.java.eval.EvalException(
                java.lang.String.format(
                    "Expected action_config for '%s' to be configured", builder.getActionName()
                ),
                e
            )
        }
    }

    // CcActionContext encapsulates the differences between using a RuleContext (the regular case) vs.
    // a StarlarkTemplateContext (invoked inside map_directory()) when creating a CppCompileAction.
    private interface CcActionContext {
        fun registerAction(action: CppCompileAction?)

        val actionOwner: ActionOwner?

        val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?
    }

    private class CcRuleContext(ruleContext: RuleContext, toolchainType: String?) : CcActionContext {
        private val ruleContext: RuleContext
        private val toolchainType: String?

        init {
            this.ruleContext = ruleContext
            this.toolchainType = toolchainType
        }

        override fun registerAction(action: CppCompileAction?) {
            ruleContext.registerAction(action)
        }

        override fun getActionOwner(): ActionOwner? {
            var actionOwner: ActionOwner? = null
            if (ruleContext.useAutoExecGroups()) {
                actionOwner =
                    ruleContext.getActionOwner(Label.parseCanonicalUnchecked(toolchainType).toString())
            }
            return if (actionOwner == null) ruleContext.getActionOwner() else actionOwner
        }

        override fun getExecutionInfo(): com.google.common.collect.ImmutableMap<String?, String?> {
            return TargetUtils.getExecutionInfo(
                ruleContext.getRule(), ruleContext.isAllowTagsPropagation()
            )
        }
    }

    private class CcTemplateContext(starlarkTemplateContext: StarlarkTemplateContext) : CcActionContext {
        private val starlarkTemplateContext: StarlarkTemplateContext

        init {
            this.starlarkTemplateContext = starlarkTemplateContext
        }

        override fun registerAction(action: CppCompileAction?) {
            starlarkTemplateContext.registerAction(action)
        }

        override fun getActionOwner(): ActionOwner {
            return starlarkTemplateContext.getActionOwner()
        }

        override fun getExecutionInfo(): com.google.common.collect.ImmutableMap<String?, String?> {
            return starlarkTemplateContext.getExecutionInfo()
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun createCoptsFilter(coptsFilterString: String?): CoptsFilter {
        if (com.google.common.base.Strings.isNullOrEmpty(coptsFilterString)) {
            return CoptsFilter.Companion.alwaysPasses()
        } else {
            try {
                return CoptsFilter.Companion.fromRegex(java.util.regex.Pattern.compile(coptsFilterString))
            } catch (e: PatternSyntaxException) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "invalid regular expression '%s': %s", coptsFilterString, e.getMessage()
                )
            }
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "create_cc_compile_action_template",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "action_construction_context",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "cc_compilation_context",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "cc_toolchain",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "configuration",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "feature_configuration",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "compile_build_variables",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "source",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "additional_compilation_inputs",
            positional = false,
            named = true,
            defaultValue = "[]"
        ), net.starlark.java.annot.Param(
            name = "additional_include_scanning_roots",
            positional = false,
            named = true,
            defaultValue = "[]"
        ), net.starlark.java.annot.Param(
            name = "use_pic",
            positional = false,
            named = true,
            defaultValue = "False"
        ), net.starlark.java.annot.Param(
            name = "output_categories",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "output_files",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "dotd_tree_artifact",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "diagnostics_tree_artifact",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "lto_indexing_tree_artifact",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "copts_filter",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "needs_include_validation",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(name = "toolchain_type", positional = false, named = true)]
    )
    @Throws(RuleErrorException::class, net.starlark.java.eval.EvalException::class)
    fun createCppCompileActionTemplate(
        starlarkRuleContext: StarlarkRuleContext,
        ccCompilationContext: StarlarkInfo?,
        ccToolchain: StarlarkInfo?,
        configuration: BuildConfigurationValue,
        featureConfigurationForStarlark: FeatureConfigurationForStarlark,
        compileBuildVariables: CcToolchainVariables?,
        source: Artifact?,
        additionalCompilationInputs: net.starlark.java.eval.Sequence<*>,
        additionalIncludeScanningRoots: net.starlark.java.eval.Sequence<*>,
        usePic: Boolean,
        outputCategoriesUnchecked: net.starlark.java.eval.Sequence<*>,
        outputFiles: SpecialArtifact?,
        dotdTreeArtifact: Any?,
        diagnosticsTreeArtifact: Any?,
        ltoIndexingTreeArtifact: Any?,
        coptsFilterObject: Any?,
        needsIncludeValidation: Boolean,
        toolchainType: String?
    ) {
        val coptsFilter: CoptsFilter =
            createCoptsFilter(
                if (net.starlark.java.eval.Starlark.isNullOrNone(coptsFilterObject)) null else coptsFilterObject as String
            )
        val outputCategories: com.google.common.collect.ImmutableList.Builder<ArtifactCategory?> =
            com.google.common.collect.ImmutableList.builder<ArtifactCategory?>()
        for (outputCategoryObject in outputCategoriesUnchecked) {
            if (outputCategoryObject is String) {
                try {
                    outputCategories.add(
                        com.google.devtools.build.lib.rules.cpp.ArtifactCategory.valueOf(
                            outputCategoryObject
                        )
                    )
                } catch (e: java.lang.IllegalArgumentException) {
                    val evalException: net.starlark.java.eval.EvalException =
                        net.starlark.java.eval.EvalException(
                            java.lang.String.format(
                                "Invalid output category: %s",
                                outputCategoryObject
                            )
                        )
                    evalException.initCause(e)
                    throw evalException
                }
            } else {
                throw net.starlark.java.eval.EvalException(
                    java.lang.String.format(
                        "Output category has invalid type. Expected string, got: %s",
                        outputCategoryObject
                    )
                )
            }
        }
        val owner: ActionOwner? =
            CppCompileActionBuilder.Companion.getActionOwner(starlarkRuleContext.getRuleContext(), toolchainType)
        val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>? =
            TargetUtils.getExecutionInfo(
                starlarkRuleContext.getRuleContext().getRule(),
                starlarkRuleContext.getRuleContext().isAllowTagsPropagation()
            )
        val builder: CppCompileActionBuilder =
            createCppCompileActionBuilder(
                owner,
                CcCompilationContext.Companion.of(ccCompilationContext),
                ccToolchain,
                configuration,
                coptsFilter,
                featureConfigurationForStarlark,
                source,
                additionalCompilationInputs,
                additionalIncludeScanningRoots,
                outputFiles,
                dotdTreeArtifact,
                diagnosticsTreeArtifact,  /* gcnoFile= */
                null,  /* dwoFile= */
                null,  /* ltoIndexingFile= */
                null,
                usePic,
                needsIncludeValidation,
                executionInfo
            )
        val ruleContext: RuleContext = starlarkRuleContext.getRuleContext()
        val sourceArtifact: SpecialArtifact? = source as SpecialArtifact?
        builder.setVariables(compileBuildVariables)

        try {
            val actionTemplate: CppCompileActionTemplate =
                CppCompileActionTemplate(
                    sourceArtifact,
                    outputFiles,
                    CcModule.Companion.nullIfNone<SpecialArtifact?>(dotdTreeArtifact, SpecialArtifact::class.java),
                    CcModule.Companion.nullIfNone<SpecialArtifact?>(
                        diagnosticsTreeArtifact,
                        SpecialArtifact::class.java
                    ),
                    CcModule.Companion.nullIfNone<SpecialArtifact?>(
                        ltoIndexingTreeArtifact,
                        SpecialArtifact::class.java
                    ),
                    builder,
                    CcToolchainProvider.Companion.create(ccToolchain),
                    outputCategories.build()
                )
            ruleContext.registerAction(actionTemplate)
        } catch (e: net.starlark.java.eval.EvalException) {
            throw RuleErrorException(e.getMessage())
        }
    }

    // TODO(b/420530680): remove after removing uses of depsets of LibraryToLink-s, LinkerInputs
    @net.starlark.java.annot.StarlarkMethod(
        name = "freeze",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "value")]
    )
    @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
    fun freeze(value: net.starlark.java.eval.StarlarkValue): Any? {
        return when (value) {
            -> net.starlark.java.eval.Dict.immutableCopyOf(dict)
            -> net.starlark.java.eval.StarlarkList.immutableCopyOf(iterable)
            ->
                val
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "check_toplevel",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "fn")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun checkToplevel(fn: net.starlark.java.eval.StarlarkFunction) {
        if (fn.getModule().getGlobal(fn.getName()) !== fn) {
            throw net.starlark.java.eval.Starlark.errorf("Passed function must be top-level functions.")
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "per_file_copts",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "cpp_configuration"), net.starlark.java.annot.Param(name = "source_file"), net.starlark.java.annot.Param(
            name = "label"
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun perFileCopts(
        cppConfiguration: CppConfiguration, sourceFile: Artifact?, sourceLabel: Label?
    ): com.google.common.collect.ImmutableList<String?> {
        return cppConfiguration.getPerFileCopts().stream()
            .filter(
                java.util.function.Predicate { perLabelOptions: PerLabelOptions? ->
                    (sourceLabel != null && perLabelOptions.isIncluded(sourceLabel))
                            || perLabelOptions.isIncluded(sourceFile)
                })
            .map<Any?>(PerLabelOptions::getOptions)
            .flatMap<Any?>(java.util.function.Function { options: Any? -> options.stream() })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "declare_compile_output_file",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "ctx",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "label",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "output_name",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(name = "configuration", positional = false, named = true)]
    )
    fun declareCompileOutputFile(
        starlarkRuleContext: StarlarkRuleContext,
        label: Label?,
        outputName: String?,
        configuration: BuildConfigurationValue
    ): Artifact? {
        val ruleContext: RuleContext = starlarkRuleContext.getRuleContext()
        return CppHelper.getCompileOutputArtifact(ruleContext, label, outputName, configuration)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "declare_other_output_file",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "ctx",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "output_name",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(name = "object_file", positional = false, named = true)]
    )
    fun declareOtherOutputFile(
        starlarkRuleContext: StarlarkRuleContext, outputName: String?, objectFile: Artifact
    ): Artifact {
        val ruleContext: RuleContext = starlarkRuleContext.getRuleContext()
        return ruleContext.getDerivedArtifact(
            objectFile.getRootRelativePath().getParentDirectory().getRelative(outputName),
            ruleContext.getConfiguration().getBinDirectory(ruleContext.getLabel().getRepository())
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "create_lto_backend_action",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "actions",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "feature_configuration",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "build_variables",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "use_pic",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "inputs",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "all_bitcode_files",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Depset::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )]
        ), net.starlark.java.annot.Param(
            name = "imports",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Artifact::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )]
        ), net.starlark.java.annot.Param(
            name = "outputs",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(name = "env", positional = false, named = true)]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun createLtoBackendAction(
        actions: StarlarkActionFactory,
        featureConfigurationForStarlark: FeatureConfigurationForStarlark,
        buildVariables: CcToolchainVariables?,
        usePic: Boolean,
        inputs: Depset?,
        allBitcodeFiles: Any?,
        imports: Any?,
        outputs: net.starlark.java.eval.Sequence<*>?,
        env: net.starlark.java.eval.Dict<*, *>?
    ) {
        val featureConfiguration: FeatureConfiguration? =
            featureConfigurationForStarlark.getFeatureConfiguration()
        val bitcodeFiles: BitcodeFiles? =
            if (allBitcodeFiles === net.starlark.java.eval.Starlark.NONE)
                null
            else
                BitcodeFiles(Depset.cast(allBitcodeFiles, Artifact::class.java, "bitcode_files"))
        val action: LtoBackendAction =
            LtoBackendArtifacts.createLtoBackendActionForStarlark(
                actions.getRuleContext().getActionOwner(),
                actions.getRuleContext().getConfiguration(),
                featureConfiguration,
                buildVariables,
                usePic,
                Depset.cast(inputs, Artifact::class.java, "inputs"),
                bitcodeFiles,
                if (imports is Artifact) imports else null,
                com.google.common.collect.ImmutableSet.copyOf<Artifact?>(
                    net.starlark.java.eval.Sequence.cast<Artifact?>(
                        outputs,
                        Artifact::class.java,
                        "outputs"
                    )
                ),
                ActionEnvironment.create(
                    com.google.common.collect.ImmutableMap.< K,
                    V > copyOf<K?, V?>(
                        net.starlark.java.eval.Dict.cast<String?, String?>(
                            env,
                            String::class.java,
                            String::class.java,
                            "env"
                        )
                    )
                )
            )
        actions.getRuleContext().registerAction(action)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "create_lto_backend_action_template",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "actions",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "feature_configuration",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "build_variables",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "use_pic",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "all_bitcode_files",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = Depset::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )]
        ), net.starlark.java.annot.Param(
            name = "additional_inputs",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "index",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = SpecialArtifact::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )]
        ), net.starlark.java.annot.Param(
            name = "bitcode_file",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = SpecialArtifact::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )]
        ), net.starlark.java.annot.Param(
            name = "object_file",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = SpecialArtifact::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )]
        ), net.starlark.java.annot.Param(
            name = "dwo_file",
            positional = false,
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = SpecialArtifact::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )]
        ), net.starlark.java.annot.Param(name = "env", positional = false, named = true)]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun createLtoBackendActionTemplate(
        actions: StarlarkActionFactory,
        featureConfigurationForStarlark: FeatureConfigurationForStarlark,
        buildVariables: CcToolchainVariables?,
        usePic: Boolean,
        allBitcodeFiles: Any?,
        additionalInputs: Depset?,
        indexObj: Any?,
        bitcodeFileObj: Any?,
        objectFileObj: Any?,
        dwoFileObj: Any?,
        env: net.starlark.java.eval.Dict<*, *>?
    ) {
        val featureConfiguration: FeatureConfiguration? =
            featureConfigurationForStarlark.getFeatureConfiguration()
        val bitcodeFiles: BitcodeFiles? =
            if (allBitcodeFiles === net.starlark.java.eval.Starlark.NONE)
                null
            else
                BitcodeFiles(Depset.cast(allBitcodeFiles, Artifact::class.java, "bitcode_files"))
        val actionTemplate: LtoBackendActionTemplate =
            LtoBackendActionTemplate(
                if (indexObj is SpecialArtifact) indexObj else null,
                if (bitcodeFileObj is SpecialArtifact) bitcodeFileObj else null,
                if (objectFileObj is SpecialArtifact) objectFileObj else null,
                if (dwoFileObj is SpecialArtifact) dwoFileObj else null,
                featureConfiguration,
                Depset.cast(additionalInputs, Artifact::class.java, "additional_inputs"),
                ActionEnvironment.create(
                    com.google.common.collect.ImmutableMap.< K,
                    V > copyOf<K?, V?>(
                        net.starlark.java.eval.Dict.cast<String?, String?>(
                            env,
                            String::class.java,
                            String::class.java,
                            "env"
                        )
                    )
                ),
                buildVariables,
                usePic,
                bitcodeFiles,
                actions.getRuleContext().getActionOwner()
            )
        actions.getRuleContext().registerAction(actionTemplate)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "create_header_info",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "header_module",
            positional = false,
            named = true,
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "pic_header_module",
            positional = false,
            named = true,
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "modular_public_headers",
            positional = false,
            named = true,
            defaultValue = "[]"
        ), net.starlark.java.annot.Param(
            name = "modular_private_headers",
            positional = false,
            named = true,
            defaultValue = "[]"
        ), net.starlark.java.annot.Param(
            name = "textual_headers",
            positional = false,
            named = true,
            defaultValue = "[]"
        ), net.starlark.java.annot.Param(
            name = "separate_module_headers",
            positional = false,
            named = true,
            defaultValue = "[]"
        ), net.starlark.java.annot.Param(
            name = "separate_module",
            positional = false,
            named = true,
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "separate_pic_module",
            positional = false,
            named = true,
            defaultValue = "None"
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun createHeaderInfo(
        headerModule: Any?,
        picHeaderModule: Any?,
        modularPublicHeaders: net.starlark.java.eval.Sequence<*>?,
        modularPrivateHeaders: net.starlark.java.eval.Sequence<*>?,
        textualHeaders: net.starlark.java.eval.Sequence<*>?,
        separateModuleHeaders: net.starlark.java.eval.Sequence<*>?,
        separateModule: Any?,
        separatePicModule: Any?,
        thread: net.starlark.java.eval.StarlarkThread
    ): HeaderInfo {
        return HeaderInfo.Companion.create(
            thread.getNextIdentityToken(),
            CcModule.Companion.nullIfNone<Artifact.DerivedArtifact?>(
                headerModule,
                Artifact.DerivedArtifact::class.java
            ),
            CcModule.Companion.nullIfNone<Artifact.DerivedArtifact?>(
                picHeaderModule,
                Artifact.DerivedArtifact::class.java
            ),
            net.starlark.java.eval.Sequence.cast<Artifact?>(
                modularPublicHeaders,
                Artifact::class.java,
                "modular_public_headers"
            )
                .getImmutableList(),
            net.starlark.java.eval.Sequence.cast<Artifact?>(
                modularPrivateHeaders,
                Artifact::class.java,
                "modular_private_headers"
            )
                .getImmutableList(),
            net.starlark.java.eval.Sequence.cast<Artifact?>(textualHeaders, Artifact::class.java, "textual_headers")
                .getImmutableList(),
            net.starlark.java.eval.Sequence.cast<Artifact?>(
                separateModuleHeaders,
                Artifact::class.java,
                "separate_module_headers"
            )
                .getImmutableList(),
            CcModule.Companion.nullIfNone<Artifact.DerivedArtifact?>(
                separateModule,
                Artifact.DerivedArtifact::class.java
            ),
            CcModule.Companion.nullIfNone<Artifact.DerivedArtifact?>(
                separatePicModule,
                Artifact.DerivedArtifact::class.java
            ),
            com.google.common.collect.ImmutableList.of<HeaderInfo?>(),
            com.google.common.collect.ImmutableList.of<HeaderInfo?>()
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "create_header_info_with_deps",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "header_info",
            positional = false,
            named = true,
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "deps",
            positional = false,
            named = true,
            defaultValue = "[]"
        ), net.starlark.java.annot.Param(name = "merged_deps", positional = false, named = true, defaultValue = "[]")],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun createHeaderInfoWithDeps(
        headerInfo: HeaderInfo,
        deps: net.starlark.java.eval.Sequence<*>?,
        mergedDeps: net.starlark.java.eval.Sequence<*>?,
        thread: net.starlark.java.eval.StarlarkThread
    ): HeaderInfo {
        return HeaderInfo.Companion.create(
            thread.getNextIdentityToken(),
            headerInfo.headerModule,
            headerInfo.picHeaderModule,
            headerInfo.modularPublicHeaders,
            headerInfo.modularPrivateHeaders,
            headerInfo.textualHeaders,
            headerInfo.separateModuleHeaders,
            headerInfo.separateModule,
            headerInfo.separatePicModule,
            net.starlark.java.eval.Sequence.cast<HeaderInfo?>(deps, HeaderInfo::class.java, "deps").getImmutableList(),
            net.starlark.java.eval.Sequence.cast<HeaderInfo?>(mergedDeps, HeaderInfo::class.java, "merged_deps")
                .getImmutableList()
        )
    }

    /**
     * Run variable expansion and shell tokenization on a sequence of flags.
     * 
     * 
     * When expanding path variables (e.g. $(execpath ...)), the label can refer to any of which in
     * the `srcs`, `non_arc_srcs`, `hdrs` or `data` attributes or an output of
     * the target.
     * 
     * @param starlarkRuleContext The rule context of the expansion.
     * @param attributeName The attribute of the rule tied to the expansion. Used for error reporting
     * only.
     * @param flags The sequence of flags to expand.
     */
    @net.starlark.java.annot.StarlarkMethod(
        name = "expand_and_tokenize",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "ctx",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "attr",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(name = "flags", positional = false, defaultValue = "[]", named = true)]
    )
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun expandAndTokenize(
        starlarkRuleContext: StarlarkRuleContext, attributeName: String?, flags: net.starlark.java.eval.Sequence<*>
    ): net.starlark.java.eval.Sequence<String?>? {
        if (flags.isEmpty()) {
            return net.starlark.java.eval.Sequence.cast<String?>(flags, String::class.java, attributeName)
        }
        val expander: Expander =
            starlarkRuleContext
                .getRuleContext()
                .getExpander(
                    StarlarkRuleContext.makeLabelMap(
                        com.google.common.collect.ImmutableSet.copyOf<E?>(
                            com.google.common.collect.Iterables.concat<T?>(
                                starlarkRuleContext.getRuleContext().getPrerequisites("srcs"),
                                starlarkRuleContext.getRuleContext().getPrerequisites("non_arc_srcs"),
                                starlarkRuleContext.getRuleContext().getPrerequisites("hdrs"),
                                starlarkRuleContext.getRuleContext().getPrerequisites("data"),
                                starlarkRuleContext
                                    .getRuleContext()
                                    .getPrerequisites("additional_linker_inputs")
                            )
                        ),
                        starlarkRuleContext
                            .getStarlarkSemantics()
                            .getBool(BuildLanguageOptions.INCOMPATIBLE_LOCATIONS_PREFERS_EXECUTABLE)
                    )
                )
                .withDataExecLocations()
        val expandedFlags: com.google.common.collect.ImmutableList<String?>? =
            expander.tokenized(
                attributeName,
                net.starlark.java.eval.Sequence.cast<T?>(flags, String::class.java, attributeName)
            )
        return net.starlark.java.eval.StarlarkList.immutableCopyOf<String?>(expandedFlags)
    }

    companion object {
        const val NAME: String = "cc_internal"

        private const val ALLOWLIST_CACHE_MAX_SIZE = 32
        private val ALLOWLIST_CACHE_LOCK = Any()

        /**
         * Caches the conversion from a Starlark [Sequence] to a [ ].
         * 
         * 
         * [.checkPrivateApi] is called on every private API usage, making it a hot path.
         * However, it is typically called with only a handful of unique [Sequence] instances
         * defined in Starlark files.
         * 
         * 
         * To maximize performance, we use a copy-on-write [IdentityHashMap]. This avoids the
         * cost of hashing sequences and keeps reads (the common case) lock-free. Updates are rare and are
         * synchronized. If the cache exceeds [.ALLOWLIST_CACHE_MAX_SIZE], we start fresh with an
         * empty cache. This approach performs better than a Caffeine cache with maximum size and/or weak
         * keys, which both have per-read maintenance operations.
         */
        @kotlin.concurrent.Volatile
        private var allowlistCache: IdentityHashMap<net.starlark.java.eval.Sequence<net.starlark.java.eval.Tuple?>?, BuiltinRestriction.Allowlist?> =
            IdentityHashMap<net.starlark.java.eval.Sequence<net.starlark.java.eval.Tuple?>?, BuiltinRestriction.Allowlist?>()

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun allowlistFromStarlark(allowlistObject: Any?): BuiltinRestriction.Allowlist? {
            // Fast path: lock-free read.
            var allowlist: BuiltinRestriction.Allowlist? = allowlistCache.get(allowlistObject)
            if (allowlist != null) {
                return allowlist
            }

            val seq: net.starlark.java.eval.Sequence<net.starlark.java.eval.Tuple?> =
                net.starlark.java.eval.Sequence.cast<net.starlark.java.eval.Tuple?>(
                    allowlistObject,
                    net.starlark.java.eval.Tuple::class.java,
                    "allowlist"
                )
            if (!seq.isImmutable()) {
                return createAllowlist(seq)
            }

            // Cache miss: check again under lock.
            synchronized(ALLOWLIST_CACHE_LOCK) {
                allowlist = allowlistCache.get(seq)
                if (allowlist == null) {
                    // Copy on write.
                    val newCache: IdentityHashMap<net.starlark.java.eval.Sequence<net.starlark.java.eval.Tuple?>?, BuiltinRestriction.Allowlist?> =
                        if (allowlistCache.size() < ALLOWLIST_CACHE_MAX_SIZE)
                            IdentityHashMap<net.starlark.java.eval.Sequence<net.starlark.java.eval.Tuple?>?, BuiltinRestriction.Allowlist?>(
                                allowlistCache
                            )
                        else
                            IdentityHashMap<net.starlark.java.eval.Sequence<net.starlark.java.eval.Tuple?>?, BuiltinRestriction.Allowlist?>()
                    allowlist = createAllowlist(seq)
                    newCache.put(seq, allowlist)
                    allowlistCache = newCache
                }
            }
            return allowlist
        }

        private fun createAllowlist(seq: net.starlark.java.eval.Sequence<net.starlark.java.eval.Tuple?>): BuiltinRestriction.Allowlist {
            return BuiltinRestriction.Allowlist.create(
                seq.stream() // TODO(bazel-team): Avoid unchecked indexing and casts on values obtained from
                    // Starlark, even though it is allowlisted.
                    .map<Any?>(
                        java.util.function.Function { p: net.starlark.java.eval.Tuple? ->
                            val repo = p.get(0) as String
                            val packagePrefix = p.get(1) as String?
                            if (repo.isEmpty())
                                BuiltinRestriction.mainRepoAllowlistEntry(packagePrefix)
                            else
                                BuiltinRestriction.externalRepoAllowlistEntry(repo, packagePrefix)
                        })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            )
        }

        private val interner: com.google.common.collect.Interner<Any?> = BlazeInterners.newWeakInterner<Any?>()

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun createCppCompileActionBuilder(
            owner: ActionOwner?,
            ccCompilationContext: CcCompilationContext?,
            ccToolchain: StarlarkInfo?,
            configuration: BuildConfigurationValue,
            coptsFilter: CoptsFilter?,
            featureConfigurationForStarlark: FeatureConfigurationForStarlark,
            sourceArtifact: Artifact?,
            additionalCompilationInputs: net.starlark.java.eval.Sequence<*>,
            additionalIncludeScanningRoots: net.starlark.java.eval.Sequence<*>,
            outputFile: Any?,
            dotdFile: Any?,
            diagnosticsFile: Any?,
            gcnoFile: Any?,
            dwoFile: Any?,
            ltoIndexingFile: Any?,
            usePic: Boolean,
            needsIncludeValidation: Boolean,
            executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?
        ): CppCompileActionBuilder {
            val builder: CppCompileActionBuilder =
                CppCompileActionBuilder(owner, CcToolchainProvider.Companion.create(ccToolchain), configuration)
                    .setSourceFile(sourceArtifact)
                    .setCcCompilationContext(ccCompilationContext)
                    .setCoptsFilter(coptsFilter)
                    .setFeatureConfiguration(featureConfigurationForStarlark.getFeatureConfiguration())
                    .addExecutionInfo(executionInfo)
            if (additionalCompilationInputs.size() > 0) {
                builder.addMandatoryInputs(
                    net.starlark.java.eval.Sequence.cast<Artifact?>(
                        additionalCompilationInputs, Artifact::class.java, "additional_compilation_inputs"
                    )
                )
            }
            if (additionalIncludeScanningRoots.size() > 0) {
                builder.addAdditionalIncludeScanningRoots(
                    net.starlark.java.eval.Sequence.cast<Artifact?>(
                        additionalIncludeScanningRoots, Artifact::class.java, "additional_include_scanning_roots"
                    )
                )
            }
            builder.setGcnoFile(CcModule.Companion.nullIfNone<Artifact?>(gcnoFile, Artifact::class.java))
            builder.setDwoFile(CcModule.Companion.nullIfNone<Artifact?>(dwoFile, Artifact::class.java))
            builder.setLtoIndexingFile(CcModule.Companion.nullIfNone<Artifact?>(ltoIndexingFile, Artifact::class.java))
            builder.setOutputs(
                CcModule.Companion.nullIfNone<Artifact?>(outputFile, Artifact::class.java),
                CcModule.Companion.nullIfNone<Artifact?>(dotdFile, Artifact::class.java),
                CcModule.Companion.nullIfNone<Artifact?>(diagnosticsFile, Artifact::class.java)
            )
            builder.setPicMode(usePic)
            builder.setNeedsIncludeValidation(needsIncludeValidation)
            return builder
        }
    }
}
