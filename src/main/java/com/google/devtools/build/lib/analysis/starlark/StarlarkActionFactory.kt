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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.analysis.constraints.ConstraintConstants.getOsFromConstraintsOrHost

/** Provides a Starlark interface for all action creation needs.  */
class StarlarkActionFactory : StarlarkActionFactoryApi {
    private val context: StarlarkActionContext

    /** Counter for actions.run_shell helper scripts. Every script must have a unique name.  */
    private var runShellOutputCounter = 0

    constructor(context: StarlarkActionContext) {
        this.context = context
    }

    protected constructor(parent: StarlarkActionFactory) {
        this.context = parent.context
    }

    private fun newFileRoot(): ArtifactRoot? {
        return context.newFileRoot()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun declareFile(filename: String?, sibling: Any): Artifact? {
        context.checkMutable("actions.declare_file")
        val ruleContext: RuleContext = getRuleContext()

        val fragment: PathFragment
        if (Starlark.NONE == sibling) {
            fragment = ruleContext.getPackageDirectory().getRelative(PathFragment.create(filename))
        } else {
            val original: PathFragment =
                (sibling as Artifact)
                    .getOutputDirRelativePath(
                        getSemantics().getBool(BuildLanguageOptions.Companion.EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT)
                    )
            fragment = original.replaceName(filename)
        }

        if (!fragment.startsWith(ruleContext.getPackageDirectory())) {
            throw Starlark.errorf(
                "the output artifact '%s' is not under package directory '%s' for target '%s'",
                fragment, ruleContext.getPackageDirectory(), ruleContext.getLabel()
            )
        }
        return ruleContext.getDerivedArtifact(fragment, newFileRoot())
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun declareDirectory(filename: String?, sibling: Any): Artifact {
        context.checkMutable("actions.declare_directory")
        val ruleContext: RuleContext = getRuleContext()
        val fragment: PathFragment

        if (Starlark.NONE == sibling) {
            fragment = ruleContext.getPackageDirectory().getRelative(PathFragment.create(filename))
        } else {
            val original: PathFragment =
                (sibling as Artifact)
                    .getOutputDirRelativePath(
                        getSemantics().getBool(BuildLanguageOptions.Companion.EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT)
                    )
            fragment = original.replaceName(filename)
        }

        if (!fragment.startsWith(ruleContext.getPackageDirectory())) {
            throw Starlark.errorf(
                "the output directory '%s' is not under package directory '%s' for target '%s'",
                fragment, ruleContext.getPackageDirectory(), ruleContext.getLabel()
            )
        }

        val result: Artifact = ruleContext.getTreeArtifact(fragment, newFileRoot())
        if (!result.isTreeArtifact()) {
            throw Starlark.errorf(
                "'%s' has already been declared as a regular file, not directory.", filename
            )
        }
        return result
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun declareSymlink(filename: String?, sibling: Any): Artifact {
        context.checkMutable("actions.declare_symlink")
        val ruleContext: RuleContext = getRuleContext()

        if (!ruleContext.getConfiguration().allowUnresolvedSymlinks()) {
            throw Starlark.errorf(
                "actions.declare_symlink() is not allowed; "
                        + "use the --allow_unresolved_symlinks command line option"
            )
        }

        val result: Artifact
        val rootRelativePath: PathFragment?
        if (Starlark.NONE == sibling) {
            rootRelativePath = ruleContext.getPackageDirectory().getRelative(filename)
        } else {
            val original: PathFragment =
                (sibling as Artifact)
                    .getOutputDirRelativePath(
                        getSemantics().getBool(BuildLanguageOptions.Companion.EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT)
                    )
            rootRelativePath = original.replaceName(filename)
        }

        result =
            ruleContext.getAnalysisEnvironment().getSymlinkArtifact(rootRelativePath, newFileRoot())

        if (!result.isSymlink()) {
            throw Starlark.errorf(
                "'%s' has already been declared as something other than a symlink.", filename
            )
        }

        return result
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun doNothing(mnemonic: String?, inputs: Any?) {
        context.checkMutable("actions.do_nothing")
        val ruleContext: RuleContext = getRuleContext()

        val inputSet: NestedSet<Artifact?>? =
            if (inputs is Depset)
                Depset.cast(inputs, Artifact::class.java, "inputs")
            else
                NestedSetBuilder.< Artifact > compileOrder < Artifact ? > ()
                    .addAll(net.starlark.java.eval.Sequence.cast<T?>(inputs, Artifact::class.java, "inputs"))
                    .build()
        val action: Action =
            PseudoAction<InfoType?>(
                UUID.nameUUIDFromBytes(
                    java.lang.String.format("empty action %s", ruleContext.getLabel())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                ),
                ruleContext.getActionOwner(),
                inputSet,
                com.google.common.collect.ImmutableList.of<Any?>(PseudoAction.Companion.getDummyOutput(ruleContext)),
                mnemonic,
                SPAWN_INFO,
                SpawnInfo.newBuilder().build()
            )
        registerAction(action)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun symlink(
        output: FileApi?,
        /* Artifact or None */targetFile: Any?,
        /* String or None */targetPath: Any?,
        /* String or None */targetType: Any?,
        isExecutable: Boolean,
        /* String or None */progressMessageUnchecked: Any?,
        useExecRootForSourceObject: Any?,
        thread: StarlarkThread?
    ) {
        context.checkMutable("actions.symlink")
        if (useExecRootForSourceObject !== Starlark.UNBOUND) {
            BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        }
        val useExecRootForSource =
            Starlark.UNBOUND != useExecRootForSourceObject && useExecRootForSourceObject as Boolean?

        val ruleContext: RuleContext = getRuleContext()

        if ((targetFile === Starlark.NONE) == (targetPath === Starlark.NONE)) {
            throw Starlark.errorf("Exactly one of \"target_file\" or \"target_path\" is required")
        }

        val outputArtifact: Artifact = output as Artifact
        val progressMessage =
            if (progressMessageUnchecked !== Starlark.NONE)
                progressMessageUnchecked as String?
            else
                "Creating symlink %{output}"

        val action: Action?
        if (targetFile !== Starlark.NONE) {
            if (targetType !== Starlark.NONE) {
                throw Starlark.errorf("\"target_type\" cannot be used with \"target_file\"")
            }

            val inputArtifact: Artifact = targetFile as Artifact
            if (outputArtifact.isSymlink()) {
                throw Starlark.errorf(
                    ("symlink() with \"target_file\" param requires that \"output\" be declared as a "
                            + "file or directory, not a symlink (did you mean to use declare_file() or "
                            + "declare_directory() instead of declare_symlink()?)")
                )
            }

            if (inputArtifact.isDirectory() !== outputArtifact.isDirectory()) {
                val inputType = if (inputArtifact.isDirectory()) "directory" else "file"
                val outputType = if (outputArtifact.isDirectory()) "directory" else "file"
                throw Starlark.errorf(
                    "symlink() with \"target_file\" %s param requires that \"output\" be declared as a %s "
                            + "(did you mean to use declare_%s() instead of declare_%s()?)",
                    inputType, inputType, inputType, outputType
                )
            }

            if (isExecutable) {
                if (outputArtifact.isTreeArtifact()) {
                    throw Starlark.errorf("symlink() with \"output\" directory param cannot be executable")
                }
                action =
                    SymlinkAction.Companion.toExecutable(
                        ruleContext.getActionOwner(), inputArtifact, outputArtifact, progressMessage
                    )
            } else {
                action =
                    SymlinkAction.Companion.toArtifact(
                        ruleContext.getActionOwner(),
                        inputArtifact,
                        outputArtifact,
                        progressMessage,
                        useExecRootForSource
                    )
            }
        } else {
            if (!outputArtifact.isSymlink()) {
                throw Starlark.errorf(
                    ("symlink() with \"target_path\" param requires that \"output\" be declared as a "
                            + "symlink, not a file or directory (did you mean to use declare_symlink() instead "
                            + "of declare_file() or declare_directory()?)")
                )
            }

            if (isExecutable) {
                throw Starlark.errorf("\"is_executable\" cannot be True when using \"target_path\"")
            }

            var symlinkTargetType: SymlinkTargetType? = SymlinkTargetType.UNSPECIFIED
            if (targetType is String) {
                symlinkTargetType =
                    when (targetType) {
                        "file" -> SymlinkTargetType.FILE
                        "directory" -> SymlinkTargetType.DIRECTORY
                        else -> throw Starlark.errorf("\"target_type\" must be one of \"file\" or \"directory\"")
                    }
            }

            action =
                UnresolvedSymlinkAction.Companion.create(
                    ruleContext.getActionOwner(),
                    outputArtifact,
                    targetPath as String?,
                    symlinkTargetType,
                    progressMessage
                )
        }
        registerAction(action)
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    public override fun write(
        output: FileApi?,
        content: Any,
        isExecutable: Boolean,
        mnemonicUnchecked: Any?,
        executionRequirementsUnchecked: Any?
    ) {
        context.checkMutable("actions.write")
        val ruleContext: RuleContext = getRuleContext()

        val mnemonic = getMnemonic(mnemonicUnchecked, AbstractFileWriteAction.Companion.MNEMONIC)

        val action: Action
        if (content is String) {
            action =
                FileWriteAction.Companion.create(
                    ruleContext, output as Artifact?, content, isExecutable, mnemonic
                )
        } else if (content is Args) {
            val unmodifiedExecutionRequirements: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                TargetUtils.getFilteredExecutionInfo(
                    executionRequirementsUnchecked,
                    ruleContext.getRule(),
                    getSemantics().getBool(BuildLanguageOptions.INCOMPATIBLE_ALLOW_TAGS_PROPAGATION)
                )
            action =
                ParameterFileWriteAction(
                    ruleContext.getActionOwner(),
                    NestedSetBuilder.wrap(Order.STABLE_ORDER, content.getDirectoryArtifacts()),
                    output as Artifact?,
                    content.build(getMainRepoMappingSupplier()),
                    content.getParameterFileType(),
                    isExecutable,
                    mnemonic,
                    ruleContext
                        .getConfiguration()
                        .modifiedExecutionInfo(unmodifiedExecutionRequirements, mnemonic),
                    PathMappers.getOutputPathsMode(ruleContext.getConfiguration())
                )
        } else {
            throw java.lang.AssertionError("Unexpected type: " + content.getClass().getSimpleName())
        }
        registerAction(action)
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    public override fun run(
        outputs: net.starlark.java.eval.Sequence<*>?,
        inputs: Any?,
        unusedInputsList: Any?,
        executableUnchecked: Any?,
        toolsUnchecked: Any?,
        arguments: net.starlark.java.eval.Sequence<*>,
        mnemonicUnchecked: Any?,
        progressMessage: Any?,
        useDefaultShellEnv: Boolean?,
        envUnchecked: Any?,
        executionRequirementsUnchecked: Any?,
        inputManifestsUnchecked: Any?,
        execGroupUnchecked: Any?,
        shadowedActionUnchecked: Any?,
        resourceSetUnchecked: Any?,
        toolchainUnchecked: Any?
    ) {
        var execGroupUnchecked = execGroupUnchecked
        var toolchainUnchecked = toolchainUnchecked
        context.checkMutable("actions.run")
        execGroupUnchecked = context.maybeOverrideExecGroup(execGroupUnchecked)
        toolchainUnchecked = context.maybeOverrideToolchain(toolchainUnchecked)

        val ruleContext: RuleContext = getRuleContext()
        val useAutoExecGroups: Boolean = ruleContext.useAutoExecGroups()

        val builder: com.google.devtools.build.lib.analysis.actions.StarlarkAction.Builder =
            com.google.devtools.build.lib.analysis.actions.StarlarkAction.Builder()
        buildCommandLine(builder, arguments, getMainRepoMappingSupplier())
        if (executableUnchecked is Artifact) {
            val provider: FilesToRunProvider? = context.getExecutableRunfiles(executableUnchecked, "executable")
            if (provider == null) {
                if (useAutoExecGroups && execGroupUnchecked === Starlark.NONE) {
                    checkToolchainParameterIsSet(ruleContext, toolchainUnchecked)
                }
                builder.setExecutable(executableUnchecked)
            } else {
                builder.setExecutable(provider)
            }
        } else if (executableUnchecked is String) {
            // Normalise if needed and then pass as a String; this keeps the reference when PathFragment
            // is passed from native to Starlark
            builder.setExecutableAsString(
                PathFragment.create(executableUnchecked).getPathString()
            )
        } else if (executableUnchecked is FilesToRunProvider) {
            if (useAutoExecGroups
                && !context.areRunfilesFromDeps(executableUnchecked as FilesToRunProvider) && execGroupUnchecked === Starlark.NONE
            ) {
                checkToolchainParameterIsSet(ruleContext, toolchainUnchecked)
            }
            builder.setExecutable(executableUnchecked as FilesToRunProvider)
        } else {
            // Should have been verified by Starlark before this function is called
            throw java.lang.IllegalStateException()
        }
        registerStarlarkAction(
            outputs,
            inputs,
            unusedInputsList,
            toolsUnchecked,
            mnemonicUnchecked,
            progressMessage,
            useDefaultShellEnv,
            envUnchecked,
            executionRequirementsUnchecked,
            execGroupUnchecked,
            shadowedActionUnchecked,
            resourceSetUnchecked,
            toolchainUnchecked,
            builder
        )
    }

    @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
    public override fun transformVersionFile(
        transformFuncObject: Any?,
        templateObject: Any?,
        outputFileName: String?,
        thread: StarlarkThread
    ): Artifact? {
        BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        return transformBuildInfoFile(
            transformFuncObject, templateObject, outputFileName, true, thread
        )
    }

    @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
    public override fun transformInfoFile(
        transformFuncObject: Any?,
        templateObject: Any?,
        outputFileName: String?,
        thread: StarlarkThread
    ): Artifact? {
        BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        return transformBuildInfoFile(
            transformFuncObject, templateObject, outputFileName, false, thread
        )
    }

    @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
    private fun transformBuildInfoFile(
        transformFuncObject: Any?,
        templateObject: Any?,
        outputFileName: String?,
        isVolatile: Boolean,
        thread: StarlarkThread
    ): Artifact? {
        val ruleContext: RuleContext = getRuleContext()
        val templateFile: Artifact? = templateObject as Artifact?
        val fragment: PathFragment? =
            ruleContext.getPackageDirectory().getRelative(PathFragment.create(outputFileName))
        val buildInfoFile: Artifact? =
            if (isVolatile)
                ruleContext
                    .getAnalysisEnvironment()
                    .getConstantMetadataArtifact(fragment, newFileRoot())
            else
                ruleContext.getDerivedArtifact(fragment, newFileRoot())
        val translationFunc: StarlarkFunction? = transformFuncObject as StarlarkFunction?
        val action: BuildInfoFileWriteAction =
            BuildInfoFileWriteAction(
                ruleContext.getActionOwner(),
                if (isVolatile)
                    ruleContext.getAnalysisEnvironment().getVolatileWorkspaceStatusArtifact()
                else
                    ruleContext.getAnalysisEnvironment().getStableWorkspaceStatusArtifact(),
                buildInfoFile,
                translationFunc,
                templateFile,
                isVolatile,
                thread.getSemantics()
            )
        registerAction(action)
        return buildInfoFile
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun validateActionCreation() {
        // We check if the rule is a dependency resolution rule but allow aspects attached to them.
        // The idea is that dependency resolution rules should not depend on anything other than
        // dependency resolution rules but since there is no such thing as "dependency resolution
        // aspect", there is no risk of that with aspects.
        if (getRuleContext().getAspectDescriptors().isEmpty()
            && getRuleContext().getRule().getRuleClassObject().isDependencyResolutionRule()
        ) {
            throw Starlark.errorf("rules that can be required for materializers shouldn't have actions")
        }

        if (getRuleContext().getRule().isAnalysisTest()) {
            throw Starlark.errorf(
                ("implementation function of a rule with "
                        + "analysis_test=true may not register actions. Analysis test rules may only return "
                        + "success/failure information via AnalysisTestResultInfo.")
            )
        }
    }

    /**
     * Registers action in the context of this [StarlarkActionFactory].
     * 
     * 
     * Use [.getRuleContext] to obtain the context required to create this action.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun registerAction(action: ActionAnalysisMetadata?) {
        validateActionCreation()
        getRuleContext().registerAction(action)
    }

    fun getRuleContext(): RuleContext {
        return context.getRuleContext()
    }

    private fun getSemantics(): StarlarkSemantics? {
        return context.getStarlarkSemantics()
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    public override fun runShell(
        outputs: net.starlark.java.eval.Sequence<*>?,
        inputs: Any?,
        toolsUnchecked: Any?,
        arguments: net.starlark.java.eval.Sequence<*>,
        mnemonicUnchecked: Any?,
        commandUnchecked: Any?,
        progressMessage: Any?,
        useDefaultShellEnv: Boolean?,
        envUnchecked: Any?,
        executionRequirementsUnchecked: Any?,
        inputManifestsUnchecked: Any?,
        execGroupUnchecked: Any?,
        shadowedActionUnchecked: Any?,
        resourceSetUnchecked: Any?,
        toolchainUnchecked: Any?
    ) {
        var execGroupUnchecked = execGroupUnchecked
        var toolchainUnchecked = toolchainUnchecked
        context.checkMutable("actions.run_shell")
        execGroupUnchecked = context.maybeOverrideExecGroup(execGroupUnchecked)
        toolchainUnchecked = context.maybeOverrideToolchain(toolchainUnchecked)

        val ruleContext: RuleContext = getRuleContext()

        val builder: com.google.devtools.build.lib.analysis.actions.StarlarkAction.Builder =
            com.google.devtools.build.lib.analysis.actions.StarlarkAction.Builder()
        buildCommandLine(builder, arguments, getMainRepoMappingSupplier())

        // When we use a shell command, add an empty argument before other arguments.
        //   e.g.  bash -c "cmd" '' 'arg1' 'arg2'
        // bash will use the empty argument as the value of $0 (which we don't care about).
        // arg1 and arg2 will be $1 and $2, as a user expects.
        val pad: Boolean = !arguments.isEmpty()

        if (commandUnchecked is String) {
            val executionInfo: com.google.common.collect.ImmutableMap<String?, String?> =
                com.google.common.collect.ImmutableMap.copyOf(TargetUtils.getExecutionInfo(ruleContext.getRule()))
            val helperScriptSuffix: String? = java.lang.String.format(".run_shell_%d.sh", runShellOutputCounter++)
            val executionPlatform: PlatformInfo? = getExecutionPlatform(execGroupUnchecked, ruleContext)
            val shExecutable: PathFragment? =
                ShToolchain.getPathForPlatform(
                    ruleContext.getConfiguration(),
                    getExecutionPlatform(execGroupUnchecked, ruleContext)
                )
            val constructor: BashCommandConstructor =
                CommandHelper.Companion.buildBashCommandConstructor(
                    executionInfo, shExecutable, helperScriptSuffix
                )
            val helperScript: Artifact? =
                CommandHelper.Companion.commandHelperScriptMaybe(
                    ruleContext, commandUnchecked, constructor, getOsFromConstraintsOrHost(executionPlatform)
                )
            if (helperScript == null) {
                builder.setShellCommand(shExecutable, commandUnchecked, pad)
            } else {
                builder.setShellCommand(shExecutable, helperScript.getExecPathString(), pad)
                builder.addInput(helperScript)
            }
        } else if (commandUnchecked is net.starlark.java.eval.Sequence<*>) {
            if (getSemantics().getBool(BuildLanguageOptions.INCOMPATIBLE_RUN_SHELL_COMMAND_STRING)) {
                throw Starlark.errorf(
                    ("'command' must be of type string. passing a sequence of strings as 'command'"
                            + " is deprecated. To temporarily disable this check,"
                            + " set --incompatible_run_shell_command_string=false.")
                )
            }
            if (!arguments.isEmpty()) {
                throw Starlark.errorf("'arguments' must be empty if 'command' is a sequence of strings")
            }
            val command: MutableList<String?> =
                net.starlark.java.eval.Sequence.cast<String?>(commandUnchecked, String::class.java, "command")
            builder.setShellCommand(command, pad)
        } else {
            throw Starlark.errorf(
                "expected string or list of strings for command instead of %s",
                Starlark.type(commandUnchecked)
            )
        }
        registerStarlarkAction(
            outputs,
            inputs,  /* unusedInputsList= */
            Starlark.NONE,
            toolsUnchecked,
            mnemonicUnchecked,
            progressMessage,
            useDefaultShellEnv,
            envUnchecked,
            executionRequirementsUnchecked,
            execGroupUnchecked,
            shadowedActionUnchecked,
            resourceSetUnchecked,
            toolchainUnchecked,
            builder
        )
    }

    /**
     * Setup for spawn actions common between [.run] and [.runShell].
     * 
     * 
     * `builder` should have either executable or a command set.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun registerStarlarkAction(
        outputs: net.starlark.java.eval.Sequence<*>?,
        inputs: Any?,
        unusedInputsList: Any?,
        toolsUnchecked: Any?,
        mnemonicUnchecked: Any?,
        progressMessage: Any?,
        useDefaultShellEnv: Boolean?,
        envUnchecked: Any?,
        executionRequirementsUnchecked: Any?,
        execGroupUnchecked: Any?,
        shadowedActionUnchecked: Any?,
        resourceSetUnchecked: Any?,
        toolchainUnchecked: Any?,
        builder: com.google.devtools.build.lib.analysis.actions.StarlarkAction.Builder
    ) {
        if (inputs is net.starlark.java.eval.Sequence) {
            builder.addInputs(net.starlark.java.eval.Sequence.cast<Artifact?>(inputs, Artifact::class.java, "inputs"))
        } else {
            builder.addTransitiveInputs(Depset.cast(inputs, Artifact::class.java, "inputs"))
        }

        val outputArtifacts: MutableList<Artifact?> =
            net.starlark.java.eval.Sequence.cast<Artifact?>(outputs, Artifact::class.java, "outputs")
        if (outputArtifacts.isEmpty()) {
            throw Starlark.errorf("param 'outputs' may not be empty")
        }
        builder.addOutputs(outputArtifacts)

        if (unusedInputsList !== Starlark.NONE) {
            if (unusedInputsList is Artifact) {
                builder.setUnusedInputsList(java.util.Optional.of<Artifact?>(unusedInputsList))
            } else {
                throw Starlark.errorf(
                    "expected value of type 'File' for a member of parameter 'unused_inputs_list' but got"
                            + " %s instead",
                    Starlark.type(unusedInputsList)
                )
            }
        }

        val ruleContext: RuleContext = getRuleContext()
        val useAutoExecGroups: Boolean = ruleContext.useAutoExecGroups()

        if (toolsUnchecked !== Starlark.UNBOUND) {
            val tools: MutableList<*> =
                if (toolsUnchecked is net.starlark.java.eval.Sequence)
                    net.starlark.java.eval.Sequence.cast<Any?>(toolsUnchecked, Any::class.java, "tools")
                else
                    Depset.cast(toolsUnchecked, Any::class.java, "tools").toList()

            for (toolUnchecked in tools) {
                if (toolUnchecked is Artifact) {
                    builder.addTool(toolUnchecked)
                    val provider: FilesToRunProvider? = context.getExecutableRunfiles(toolUnchecked, "executable")
                    if (provider != null) {
                        builder.addTool(provider)
                    } else {
                        if (useAutoExecGroups && execGroupUnchecked === Starlark.NONE) {
                            checkToolchainParameterIsSet(ruleContext, toolchainUnchecked)
                        }
                    }
                } else if (toolUnchecked is FilesToRunProvider) {
                    if (useAutoExecGroups
                        && !context.areRunfilesFromDeps(toolUnchecked as FilesToRunProvider) && execGroupUnchecked === Starlark.NONE
                    ) {
                        checkToolchainParameterIsSet(ruleContext, toolchainUnchecked)
                    }
                    builder.addTool(toolUnchecked as FilesToRunProvider)
                } else if (toolUnchecked is Depset) {
                    try {
                        if (useAutoExecGroups && execGroupUnchecked === Starlark.NONE) {
                            checkToolchainParameterIsSet(ruleContext, toolchainUnchecked)
                        }
                        builder.addTransitiveTools((toolUnchecked as Depset).getSet(Artifact::class.java))
                    } catch (e: TypeException) {
                        throw Starlark.errorf(
                            "expected value of type 'File, FilesToRunProvider or Depset of Files' for a member "
                                    + "of parameter 'tools' but %s",
                            e.getMessage()
                        )
                    }
                } else {
                    throw Starlark.errorf(
                        "expected value of type 'File, FilesToRunProvider or Depset of Files' for a member of"
                                + " parameter 'tools' but got %s instead",
                        Starlark.type(toolUnchecked)
                    )
                }
            }
        }

        if (mnemonicUnchecked === Starlark.NONE
            && getSemantics()
                .getBool(BuildLanguageOptions.INCOMPATIBLE_REQUIRE_MNEMONIC_FOR_RUN_ACTIONS)
        ) {
            throw Starlark.errorf("actions.run and actions.run_shell require an explicit mnemonic.")
        }

        val mnemonic = getMnemonic(mnemonicUnchecked, "Action")

        try {
            builder.setMnemonic(mnemonic)
        } catch (e: java.lang.IllegalArgumentException) {
            throw Starlark.errorf("%s", e.getMessage())
        }
        if (progressMessage !== Starlark.NONE) {
            builder.setProgressMessageFromStarlark(progressMessage as String?)
        }

        var env: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>()
        if (envUnchecked !== Starlark.NONE) {
            env = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(
                Dict.cast<String?, String?>(
                    envUnchecked,
                    String::class.java,
                    String::class.java,
                    "env"
                )
            )
        }
        if (Starlark.truth(useDefaultShellEnv)) {
            builder.useDefaultShellEnvironment(env)
        } else {
            builder.setEnvironment(env)
        }

        val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>? =
            TargetUtils.getFilteredExecutionInfo(
                executionRequirementsUnchecked,
                ruleContext.getRule(),
                getSemantics().getBool(BuildLanguageOptions.INCOMPATIBLE_ALLOW_TAGS_PROPAGATION)
            )
        builder.setExecutionInfo(executionInfo)

        val execGroup = determineExecGroup(ruleContext, execGroupUnchecked, toolchainUnchecked)
        builder.setExecGroup(execGroup)

        if (shadowedActionUnchecked !== Starlark.NONE) {
            builder.setShadowedAction(java.util.Optional.of<Action?>(shadowedActionUnchecked as Action?))
        }

        if (resourceSetUnchecked !== Starlark.NONE) {
            validateResourceSetBuilder(resourceSetUnchecked)
            builder.setResources(
                StarlarkActionResourceSetBuilder.Companion.create(
                    resourceSetUnchecked as StarlarkCallable, mnemonic, getSemantics()
                )
            )
        }

        // Always register the action
        registerAction(builder.build(ruleContext))
    }

    private class StarlarkActionResourceSetBuilder(
        fn: StarlarkCallable?,
        mnemonic: String?,
        semantics: StarlarkSemantics?
    ) : ResourceSetOrBuilder {
        private val fn: StarlarkCallable?
        private val mnemonic: String?
        private val semantics: StarlarkSemantics?

        init {
            this.fn = fn
            this.mnemonic = mnemonic
            this.semantics = semantics
        }

        @Throws(ExecException::class, java.lang.InterruptedException::class)
        override fun buildResourceSet(os: OS, inputsSize: Int): ResourceSet {
            try {
                Mutability.create("resource_set_builder_function").use { mu ->
                    // Only numerical values are retained from the result, so a transient SymbolGenerator
                    // is fine.
                    val thread: StarlarkThread? =
                        StarlarkThread.create(
                            mu, semantics, "resource_set callback", SymbolGenerator.createTransient()
                        )
                    val inputInt: StarlarkInt? = StarlarkInt.of(inputsSize)
                    val response: Any? =
                        Starlark.positionalOnlyCall(thread, this.fn, os.getCanonicalName(), inputInt)
                    val resourceSetMapRaw: MutableMap<String?, Any?> =
                        Dict.cast<String?, Any?>(response, String::class.java, Any::class.java, "resource_set")

                    if (!validResources.containsAll(resourceSetMapRaw.keySet())) {
                        val message: String? =
                            java.lang.String.format(
                                "Illegal resource keys: (%s)",
                                com.google.common.base.Joiner.on(",").join(
                                    com.google.common.collect.Sets.difference<String?>(
                                        resourceSetMapRaw.keySet(),
                                        validResources
                                    )
                                )
                            )
                        throw net.starlark.java.eval.EvalException(message)
                    }
                    return ResourceSet.Companion.create(
                        getNumericOrDefault(
                            resourceSetMapRaw, ResourceSet.Companion.MEMORY, DEFAULT_RESOURCE_SET.getMemoryMb()
                        ),
                        getNumericOrDefault(
                            resourceSetMapRaw, ResourceSet.Companion.CPU, DEFAULT_RESOURCE_SET.getCpuUsage()
                        ),
                        getNumericOrDefault(
                            resourceSetMapRaw, "local_test", DEFAULT_RESOURCE_SET.getLocalTestCount().toDouble()
                        ).toInt()
                    )
                }
            } catch (e: net.starlark.java.eval.EvalException) {
                throw UserExecException(
                    FailureDetail.newBuilder()
                        .setMessage(
                            java.lang.String.format("Could not build resources for %s. %s", mnemonic, e.getMessage())
                        )
                        .setStarlarkAction(
                            FailureDetails.StarlarkAction.newBuilder()
                                .setCode(FailureDetails.StarlarkAction.Code.STARLARK_ACTION_UNKNOWN)
                                .build()
                        )
                        .build()
                )
            }
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is StarlarkActionResourceSetBuilder) {
                return false
            }
            return com.google.common.base.Objects.equal(fn, o.fn)
                    && com.google.common.base.Objects.equal(mnemonic, o.mnemonic)
                    && com.google.common.base.Objects.equal(semantics, o.semantics)
        }

        override fun hashCode(): Int {
            return com.google.common.base.Objects.hashCode(fn, mnemonic, semantics)
        }

        companion object {
            private val resourceSetBuilderInterner: com.google.common.collect.Interner<StarlarkActionResourceSetBuilder> =
                BlazeInterners.newWeakInterner<StarlarkActionResourceSetBuilder?>()

            fun create(
                fn: StarlarkCallable?, mnemonic: String?, semantics: StarlarkSemantics?
            ): StarlarkActionResourceSetBuilder {
                return resourceSetBuilderInterner.intern(
                    StarlarkActionResourceSetBuilder(fn, mnemonic, semantics)
                )
            }

            @Throws(net.starlark.java.eval.EvalException::class)
            private fun getNumericOrDefault(
                resourceSetMap: MutableMap<String?, Any?>, key: String?, defaultValue: Double
            ): Double {
                if (!resourceSetMap.containsKey(key)) {
                    return defaultValue
                }

                val value = resourceSetMap.get(key)
                if (value is StarlarkInt) {
                    return value.toDouble()
                }

                if (value is StarlarkFloat) {
                    return value.toDouble()
                }
                throw net.starlark.java.eval.EvalException(
                    java.lang.String.format(
                        "Illegal resource value type for key %s: got %s, want int or float",
                        key, Starlark.type(value)
                    )
                )
            }
        }
    }

    private fun getMnemonic(mnemonicUnchecked: Any?, defaultMnemonic: String?): String? {
        var mnemonic =
            if (mnemonicUnchecked === Starlark.NONE) defaultMnemonic else mnemonicUnchecked as String?
        if (getRuleContext().getConfiguration().getReservedActionMnemonics().contains(mnemonic)) {
            mnemonic = mangleMnemonic(mnemonic)
        }
        return mnemonic
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    public override fun mapDirectory(
        inputDirectories: Dict<*, *>,
        additionalInputs: Dict<*, *>?,
        outputDirectories: Dict<*, *>,
        tools: Dict<*, *>?,
        additionalParams: Dict<*, *>?,
        executionRequirementsUnchecked: Any?,
        execGroupUnchecked: Any?,
        toolchainUnchecked: Any?,
        useDefaultShellEnv: Boolean,
        envUnchecked: Any?,
        mnemonicUnchecked: Any?,
        implementation: StarlarkFunction,
        thread: StarlarkThread
    ) {
        if (!getRuleContext().getConfiguration().allowMapDirectory()) {
            throw Starlark.errorf(
                "actions.map_directory() is an experimental API and is subjected to change. "
                        + "Please set the flag --experimental_allow_map_directory to enable it."
            )
        }
        context.checkMutable("actions.map_directory")

        val ruleContext: RuleContext = getRuleContext()
        if (inputDirectories.size() < 1) {
            throw Starlark.errorf("actions.map_directory() requires at least one input.")
        }
        if (outputDirectories.size() < 1) {
            throw Starlark.errorf("actions.map_directory() requires at least one output.")
        }

        val mnemonic = getMnemonic(mnemonicUnchecked, "ExpandedTemplateAction")
        val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>? =
            ruleContext
                .getConfiguration()
                .modifiedExecutionInfo(
                    TargetUtils.getFilteredExecutionInfo(
                        executionRequirementsUnchecked,
                        ruleContext.getRule(),
                        getSemantics()
                            .getBool(BuildLanguageOptions.INCOMPATIBLE_ALLOW_TAGS_PROPAGATION)
                    ),
                    mnemonic
                )

        val actionEnv: ActionEnvironment? =
            SpawnAction.Companion.createActionEnvironment(
                ruleContext.getConfiguration(),
                useDefaultShellEnv,
                if (envUnchecked === Starlark.NONE)
                    com.google.common.collect.ImmutableMap.of<String?, String?>()
                else
                    com.google.common.collect.ImmutableMap.copyOf<String?, String?>(
                        Dict.cast<String?, String?>(
                            envUnchecked,
                            String::class.java,
                            String::class.java,
                            "env"
                        )
                    )
            )

        validateIsTopLevelStarlarkFunction(implementation)

        val execGroup = determineExecGroup(ruleContext, execGroupUnchecked, toolchainUnchecked)

        val spawnActionBuilder: com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder? =
            com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder()
                .setMnemonic(mnemonic)
                .setResources(DEFAULT_RESOURCE_SET)
                .setActionEnvironment(actionEnv)
                .setExecutionInfo(executionInfo)
                .setOutputPathsMode(PathMappers.getOutputPathsMode(ruleContext.getConfiguration()))

        val template: StarlarkMapActionTemplate =
            StarlarkMapActionTemplate(
                getRuleContext().getActionOwner(execGroup),
                Dict.cast<String?, SpecialArtifact?>(
                    inputDirectories,
                    String::class.java,
                    SpecialArtifact::class.java,
                    StarlarkMapActionTemplate.Companion.INPUT_DIRECTORIES_KEY
                ),
                Dict.cast<String?, Any?>(
                    additionalInputs,
                    String::class.java,
                    Any::class.java,
                    StarlarkMapActionTemplate.Companion.ADDITIONAL_INPUTS_KEY
                ),
                Dict.cast<String?, SpecialArtifact?>(
                    outputDirectories,
                    String::class.java,
                    SpecialArtifact::class.java,
                    StarlarkMapActionTemplate.Companion.OUTPUT_DIRECTORIES_KEY
                ),
                Dict.cast<String?, Any?>(
                    tools,
                    String::class.java,
                    Any::class.java,
                    StarlarkMapActionTemplate.Companion.TOOLS_KEY
                ),
                Dict.cast<String?, Any?>(
                    additionalParams,
                    String::class.java,
                    Any::class.java,
                    StarlarkMapActionTemplate.Companion.ADDITIONAL_PARAMS_KEY
                ),
                spawnActionBuilder,
                executionInfo,
                PathMappers.getOutputPathsMode(ruleContext.getConfiguration()),
                actionEnv,
                getMainRepoMappingSupplier(),
                mnemonic,
                implementation,
                thread.getSemantics(),
                ruleContext.getSymbolGenerator()
            )
        registerAction(template)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun expandTemplate(
        template: FileApi?,
        output: FileApi?,
        substitutionsUnchecked: Dict<*, *>?,
        executable: Boolean,  /* TemplateDict */
        computedSubstitutions: Any
    ) {
        context.checkMutable("actions.expand_template")
        // We use a map to check for duplicate keys
        val substitutionsBuilder: com.google.common.collect.ImmutableMap.Builder<String?, com.google.devtools.build.lib.analysis.actions.Substitution?> =
            com.google.common.collect.ImmutableMap.builder<String?, com.google.devtools.build.lib.analysis.actions.Substitution?>()
        for (substitution in Dict.cast<String?, String?>(
            substitutionsUnchecked,
            String::class.java,
            String::class.java,
            "substitutions"
        ).entrySet()) {
            substitutionsBuilder.put(
                substitution.getKey(),
                com.google.devtools.build.lib.analysis.actions.Substitution.Companion.of(
                    substitution.getKey(),
                    substitution.getValue()
                )
            )
        }
        if (Starlark.UNBOUND != computedSubstitutions) {
            for (substitution in (computedSubstitutions as TemplateDict).getAll()) {
                substitutionsBuilder.put(substitution.getKey(), substitution)
            }
        }
        val substitutionMap: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.analysis.actions.Substitution?>?
        try {
            substitutionMap = substitutionsBuilder.buildOrThrow()
        } catch (e: java.lang.IllegalArgumentException) {
            // user added duplicate keys, report the error, but the stack trace is not of use
            throw Starlark.errorf("%s", e.getMessage())
        }
        val action: TemplateExpansionAction =
            TemplateExpansionAction(
                getRuleContext().getActionOwner(),
                template as Artifact?,
                output as Artifact?,
                substitutionMap.values().asList(),
                executable
            )
        registerAction(action)
    }

    public override fun args(thread: StarlarkThread): Args {
        return Args.newArgs(thread.mutability(), getSemantics())
    }

    public override fun templateDict(): TemplateDictApi {
        return TemplateDict.newDict()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun createShareableArtifact(path: String?, artifactRoot: Any?, thread: StarlarkThread?): FileApi? {
        BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        val root: ArtifactRoot? =
            if (artifactRoot === Starlark.UNBOUND)
                getRuleContext().getBinDirectory()
            else
                artifactRoot as ArtifactRoot?
        return getRuleContext().getShareableArtifact(PathFragment.create(path), root)
    }

    public override fun isImmutable(): Boolean {
        return context.isImmutable()
    }

    public override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
        printer.append("actions for")
        context.repr(printer, semantics)
    }

    private fun getMainRepoMappingSupplier(): InterruptibleSupplier<RepositoryMapping?> {
        return InterruptibleSupplier { context.getRuleContext().getAnalysisEnvironment().getMainRepoMapping() }
    }

    /** The analysis context for `Starlark` actions  */ // For now, this contains methods necessary for SubruleContext to begin using
    // StarlarkActionFactory without any invasive changes to the latter. It will be improved once the
    // subrule implementation approaches maturity.
    // TODO(hvd): clean up this interface to only contain general-purpose methods
    interface StarlarkActionContext : StarlarkValue {
        fun newFileRoot(): ArtifactRoot?

        @Throws(net.starlark.java.eval.EvalException::class)
        fun checkMutable(attrName: String?)

        @Throws(net.starlark.java.eval.EvalException::class)
        fun getExecutableRunfiles(executable: Artifact?, what: String?): FilesToRunProvider?

        fun areRunfilesFromDeps(executable: FilesToRunProvider?): Boolean

        fun getRuleContext(): RuleContext

        fun getStarlarkSemantics(): StarlarkSemantics?

        @Throws(net.starlark.java.eval.EvalException::class)
        fun maybeOverrideExecGroup(execGroupUnchecked: Any?): Any? {
            return execGroupUnchecked
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun maybeOverrideToolchain(toolchainUnchecked: Any?): Any? {
            return toolchainUnchecked
        }
    }

    companion object {
        private val DEFAULT_RESOURCE_SET: ResourceSet = ResourceSet.Companion.createWithRamCpu(250.0, 1.0)
        private val validResources: MutableSet<String?> = HashSet<String?>(
            java.util.Arrays.asList<String?>(
                ResourceSet.Companion.CPU,
                ResourceSet.Companion.MEMORY,
                "local_test"
            )
        )

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkToolchainParameterIsSet(
            ruleContext: RuleContext, toolchainUnchecked: Any?
        ) {
            if ((ruleContext.getToolchainContexts() == null
                        || ruleContext.getToolchainContexts().contextMap().size() > 1)
                && toolchainUnchecked === Starlark.UNBOUND
            ) {
                throw Starlark.errorf(
                    "Couldn't identify if tools are from implicit dependencies or a toolchain. Please"
                            + " set the toolchain parameter. If you're not using a toolchain, set it to 'None'."
                )
            }
        }

        @SerializationConstant
        @VisibleForSerialization
        val SPAWN_INFO: GeneratedExtension<ExtraActionInfo?, SpawnInfo?>? = SpawnInfo.spawnInfo

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun verifyExecGroupExists(execGroup: String?, ctx: RuleContext) {
            if (!ctx.hasToolchainContext(execGroup)) {
                throw Starlark.errorf("Action declared for non-existent exec group '%s'.", execGroup)
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun verifyAutomaticExecGroupExists(execGroup: String?, ruleContext: RuleContext) {
            if (!ruleContext.hasToolchainContext(execGroup)) {
                throw Starlark.errorf("Action declared for non-existent toolchain '%s'.", execGroup)
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkValidGroupName(execGroup: String?) {
            if (!StarlarkExecGroupCollection.isValidGroupName(execGroup)) {
                throw Starlark.errorf("Invalid name for exec group '%s'.", execGroup)
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun getExecutionPlatform(execGroupUnchecked: Any?, ctx: RuleContext): PlatformInfo? {
            if (execGroupUnchecked === Starlark.NONE) {
                return ctx.getExecutionPlatform(DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME)
            } else {
                val execGroup = execGroupUnchecked as String?
                verifyExecGroupExists(execGroup, ctx)
                checkValidGroupName(execGroup)
                return ctx.getExecutionPlatform(execGroupUnchecked)
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        fun buildCommandLine(
            builder: com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder,
            argumentsList: net.starlark.java.eval.Sequence<*>,
            repoMappingSupplier: InterruptibleSupplier<RepositoryMapping?>?
        ) {
            var stringArgs: com.google.common.collect.ImmutableList.Builder<String?>? = null
            for (value in argumentsList) {
                if (value is String) {
                    if (stringArgs == null) {
                        stringArgs = com.google.common.collect.ImmutableList.builder<String?>()
                    }
                    stringArgs.add(value)
                } else if (value is Args) {
                    if (stringArgs != null) {
                        builder.addCommandLine(CommandLine.of(stringArgs.build()))
                        stringArgs = null
                    }
                    val paramFileInfo: ParamFileInfo? = value.getParamFileInfo()
                    builder.addCommandLine(value.build(repoMappingSupplier), paramFileInfo)
                } else {
                    throw Starlark.errorf(
                        "expected list of strings or ctx.actions.args() for arguments instead of %s",
                        Starlark.type(value)
                    )
                }
            }
            if (stringArgs != null) {
                builder.addCommandLine(CommandLine.of(stringArgs.build()))
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun determineExecGroup(
            ruleContext: RuleContext, execGroupUnchecked: Any?, toolchainUnchecked: Any?
        ): String? {
            var toolchainLabel: Label? = null
            if (toolchainUnchecked is Label) {
                toolchainLabel = toolchainUnchecked
            } else if (toolchainUnchecked is String) {
                try {
                    toolchainLabel =
                        Label.parseWithPackageContext(
                            toolchainUnchecked, ruleContext.getPackageContext()
                        )
                } catch (e: LabelSyntaxException) {
                    throw Starlark.errorf("%s", e.getMessage())
                }
            }

            if (execGroupUnchecked !== Starlark.NONE) {
                val execGroup = execGroupUnchecked as String?
                verifyExecGroupExists(execGroup, ruleContext)
                checkValidGroupName(execGroup)

                // If toolchain and exec_groups are both defined, verify they are compatible.
                if (ruleContext.useAutoExecGroups() && toolchainLabel != null) {
                    if (ruleContext.getExecGroups().getExecGroup(execGroup).toolchainTypes().stream()
                            .map(ToolchainTypeRequirement::toolchainType)
                            .noneMatch(toolchainLabel::equals)
                    ) {
                        throw Starlark.errorf(
                            ("`toolchain` and `exec_group` parameters inside actions.{run, run_shell} are not"
                                    + " compatible; use one of them or define `toolchain` which is compatible with"
                                    + " the exec_group (already exists inside the `exec_group`)")
                        )
                    }
                }

                return execGroup
            } else if (ruleContext.useAutoExecGroups() && toolchainLabel != null) {
                verifyAutomaticExecGroupExists(toolchainLabel.toString(), ruleContext)
                return toolchainLabel.toString()
            }

            return DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun validateResourceSetBuilder(fn: Any?) {
            if (fn !is StarlarkCallable) {
                throw Starlark.errorf(
                    "resource_set should be a Starlark-callable function, but got %s instead",
                    Starlark.type(fn)
                )
            }

            if (fn is StarlarkFunction) {
                validateIsTopLevelStarlarkFunction(fn)
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun validateIsTopLevelStarlarkFunction(fn: StarlarkFunction) {
            // Reject non-global functions, because arbitrary closures may cause large
            // analysis-phase data structures to remain live into the execution phase.
            // We require that the function is "global" as opposed to "not a closure"
            // because a global function may be closure if it refers to load bindings.
            // This unfortunately disallows such trivially safe non-global
            // functions as "lambda x: x".
            // See https://github.com/bazelbuild/bazel/issues/12701.
            if (fn.getModule().getGlobal(fn.getName()) !== fn) {
                throw Starlark.errorf(
                    ("to avoid unintended retention of analysis data structures, "
                            + "the function (declared at %s) must be declared "
                            + "by a top-level def statement"),
                    fn.getLocation()
                )
            }
        }

        private fun mangleMnemonic(mnemonic: String?): String {
            return mnemonic + "FromStarlark"
        }
    }
}
