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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.ActionExecutionContext

/** An [ActionTemplate] that expands into [CppCompileAction]s at execution time.  */
class CppCompileActionTemplate internal constructor(
    sourceTreeArtifact: SpecialArtifact,
    outputTreeArtifact: SpecialArtifact?,
    dotdTreeArtifact: SpecialArtifact?,
    diagnosticsTreeArtifact: SpecialArtifact?,
    ltoIndexTreeArtifact: SpecialArtifact?,
    cppCompileActionBuilder: CppCompileActionBuilder,
    toolchain: CcToolchainProvider,
    categories: com.google.common.collect.ImmutableList<ArtifactCategory?>
) : ActionKeyComputer(), ActionTemplate<CppCompileAction?> {
    private val cppCompileActionBuilder: CppCompileActionBuilder
    private val sourceTreeArtifact: SpecialArtifact
    private val outputTreeArtifact: SpecialArtifact?
    private val dotdTreeArtifact: SpecialArtifact?
    private val diagnosticsTreeArtifact: SpecialArtifact?
    private val ltoIndexTreeArtifact: SpecialArtifact?
    private val toolchain: CcToolchainProvider
    private val categories: com.google.common.collect.ImmutableList<ArtifactCategory?>
    private val actionOwner: ActionOwner
    private val mandatoryInputs: NestedSet<Artifact?>?
    private val allInputs: NestedSet<Artifact?>?

    /**
     * Creates a CppCompileActionTemplate.
     * 
     * @param sourceTreeArtifact the TreeArtifact that contains source files to compile.
     * @param outputTreeArtifact the TreeArtifact that contains compilation outputs.
     * @param dotdTreeArtifact the TreeArtifact that contains dotd files.
     * @param diagnosticsTreeArtifact the TreeArtifact that contains serialized diagnostics files.
     * @param ltoIndexTreeArtifact the TreeArtifact that contains lto index files (minimized bitcode).
     * @param cppCompileActionBuilder An almost completely configured [CppCompileActionBuilder]
     * without the input and output files set. It is used as a template to instantiate expanded
     * {CppCompileAction}s.
     * @param toolchain the CcToolchainProvider representing the c++ toolchain for this action
     * @param categories A list of [ArtifactCategory] used to calculate output file name from a
     * source file name.
     * @param actionOwner the owner of this [ActionTemplate].
     */
    init {
        this.cppCompileActionBuilder = cppCompileActionBuilder
        this.sourceTreeArtifact = sourceTreeArtifact
        this.outputTreeArtifact = outputTreeArtifact
        this.dotdTreeArtifact = dotdTreeArtifact
        this.ltoIndexTreeArtifact = ltoIndexTreeArtifact
        this.diagnosticsTreeArtifact = diagnosticsTreeArtifact
        this.toolchain = toolchain
        this.categories = categories
        this.actionOwner =
            com.google.common.base.Preconditions.checkNotNull<ActionOwner>(cppCompileActionBuilder.getOwner())
        this.mandatoryInputs = cppCompileActionBuilder.buildMandatoryInputs()
        this.allInputs =
            NestedSetBuilder.fromNestedSet(mandatoryInputs)
                .addTransitive(cppCompileActionBuilder.getInputsForInvalidation())
                .build()
    }

    // LINT.ThenChange(@rules_cc//cc/private/compile/compile.bzl:cc_and_objc_file_types)
    @Throws(ActionExecutionException::class)
    public override fun generateActionsForInputArtifacts(
        inputTreeFileArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact>,
        artifactOwner: ActionLookupKey?,
        eventHandler: com.google.devtools.build.lib.events.EventHandler?
    ): com.google.common.collect.ImmutableList<CppCompileAction?> {
        val expandedActions: com.google.common.collect.ImmutableList.Builder<CppCompileAction?> =
            com.google.common.collect.ImmutableList.Builder<CppCompileAction?>()

        val sourcesBuilder: com.google.common.collect.ImmutableList.Builder<TreeFileArtifact> =
            com.google.common.collect.ImmutableList.builder<TreeFileArtifact?>()
        val privateHeadersBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        for (inputTreeFileArtifact in inputTreeFileArtifacts) {
            val isHeader: Boolean = CppFileTypes.CPP_HEADER.matches(inputTreeFileArtifact.getExecPath())
            val isTextualInclude: Boolean =
                CppFileTypes.CPP_TEXTUAL_INCLUDE.matches(inputTreeFileArtifact.getExecPath())
            val isSource =
                CC_AND_OBJC_FILE_TYPES.matches(inputTreeFileArtifact.getExecPathString()) && !isHeader

            if (isHeader) {
                privateHeadersBuilder.add(inputTreeFileArtifact)
            }
            if (isSource || (isHeader && shouldCompileHeaders() && !isTextualInclude)) {
                sourcesBuilder.add(inputTreeFileArtifact)
            } else if (!isHeader) {
                val message: String? =
                    java.lang.String.format(
                        "Artifact '%s' expanded from the directory artifact '%s' is neither header "
                                + "nor source file.",
                        inputTreeFileArtifact.getExecPathString(), sourceTreeArtifact.getExecPathString()
                    )
                throw ActionExecutionException(
                    message, this,  /*catastrophe=*/false, makeDetailedExitCode(message)
                )
            }
        }
        val sources: com.google.common.collect.ImmutableList<TreeFileArtifact> = sourcesBuilder.build()
        val privateHeaders: NestedSet<Artifact?>? = privateHeadersBuilder.build()

        for (inputTreeFileArtifact in sources) {
            try {
                val outputName = outputTreeFileArtifactName(inputTreeFileArtifact)
                val outputTreeFileArtifact: TreeFileArtifact? =
                    TreeFileArtifact.createTemplateExpansionOutput(
                        outputTreeArtifact, outputName, artifactOwner
                    )
                var dotdFileArtifact: TreeFileArtifact? = null
                if (dotdTreeArtifact != null
                    && cppCompileActionBuilder.useDotdFile(inputTreeFileArtifact)
                ) {
                    dotdFileArtifact =
                        TreeFileArtifact.createTemplateExpansionOutput(
                            dotdTreeArtifact, outputName + ".d", artifactOwner
                        )
                }
                var diagnosticsFileArtifact: TreeFileArtifact? = null
                if (diagnosticsTreeArtifact != null) {
                    diagnosticsFileArtifact =
                        TreeFileArtifact.createTemplateExpansionOutput(
                            diagnosticsTreeArtifact, outputName + ".dia", artifactOwner
                        )
                }

                var ltoIndexFileArtifact: TreeFileArtifact? = null
                if (ltoIndexTreeArtifact != null) {
                    val outputFilePathFragment: PathFragment = PathFragment.create(outputName)
                    val thinltofile: PathFragment? =
                        com.google.devtools.build.lib.vfs.FileSystemUtils.replaceExtension(
                            outputFilePathFragment,
                            com.google.common.collect.Iterables.getOnlyElement<String?>(CppFileTypes.LTO_INDEXING_OBJECT_FILE.getExtensions())
                        )
                    ltoIndexFileArtifact =
                        TreeFileArtifact.createTemplateExpansionOutput(
                            ltoIndexTreeArtifact, thinltofile, artifactOwner
                        )
                }
                expandedActions.add(
                    createAction(
                        inputTreeFileArtifact,
                        outputTreeFileArtifact,
                        dotdFileArtifact,
                        diagnosticsFileArtifact,
                        ltoIndexFileArtifact,
                        privateHeaders
                    )
                )
            } catch (e: net.starlark.java.eval.EvalException) {
                throw throwActionExecutionException(e)
            }
        }

        return expandedActions.build()
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    protected override fun computeKey(
        actionKeyContext: ActionKeyContext,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        val commandLine: CompileCommandLine =
            CppCompileAction.Companion.buildCommandLine(
                cppCompileActionBuilder.getCoptsFilter(),
                CppActionNames.CPP_COMPILE,
                cppCompileActionBuilder.getFeatureConfiguration(),
                cppCompileActionBuilder.getVariables()
            )
        try {
            CppCompileAction.Companion.computeKey(
                actionKeyContext,
                fp,
                cppCompileActionBuilder.getActionEnvironment(),
                commandLine.getEnvironment(PathMapper.NOOP),
                cppCompileActionBuilder.getExecutionInfo(),
                CppCompileAction.Companion.computeCommandLineKey(
                    commandLine.getCompilerOptions( /* overwrittenVariables= */null, PathMapper.NOOP)
                ),
                cppCompileActionBuilder.getCcCompilationContext().getDeclaredIncludeSrcs(),
                mandatoryInputs,
                mandatoryInputs,
                cppCompileActionBuilder.getPrunableHeaders(),
                cppCompileActionBuilder.getBuiltinIncludeDirectories(),
                cppCompileActionBuilder.getInputsForInvalidation(),
                this.mnemonic,  // This method is not called during actual execution (action templates are always expanded
                // into individual actions that then have their action key computed), so path mapping is
                // supported and fingerprinted correctly even with this set to OFF.
                OutputPathsMode.OFF
            )
        } catch (e: net.starlark.java.eval.EvalException) {
            throw CommandLineExpansionException(e.getMessage())
        }
    }

    private fun shouldCompileHeaders(): Boolean {
        return cppCompileActionBuilder.shouldCompileHeaders()
    }

    @Throws(ActionExecutionException::class)
    private fun createAction(
        sourceTreeFileArtifact: TreeFileArtifact?,
        outputTreeFileArtifact: TreeFileArtifact?,
        dotdFileArtifact: Artifact?,
        diagnosticsFileArtifact: Artifact?,
        ltoIndexFileArtifact: Artifact?,
        privateHeaders: NestedSet<Artifact?>?
    ): CppCompileAction {
        val builder: CppCompileActionBuilder =
            CppCompileActionBuilder(cppCompileActionBuilder)
                .setAdditionalPrunableHeaders(privateHeaders)
                .setSourceFile(sourceTreeFileArtifact)
                .setOutputs(outputTreeFileArtifact, dotdFileArtifact, diagnosticsFileArtifact)
                .setLtoIndexingFile(ltoIndexFileArtifact)

        val buildVariables: com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Builder =
            CcToolchainVariables.Companion.builder(cppCompileActionBuilder.getVariables())
        buildVariables.overrideVariable(
            CompileBuildVariables.SOURCE_FILE.getVariableName(), sourceTreeFileArtifact
        )
        buildVariables.overrideVariable(
            CompileBuildVariables.OUTPUT_FILE.getVariableName(), outputTreeFileArtifact
        )
        if (dotdFileArtifact != null) {
            buildVariables.overrideVariable(
                CompileBuildVariables.DEPENDENCY_FILE.getVariableName(), dotdFileArtifact
            )
        }
        if (diagnosticsFileArtifact != null) {
            buildVariables.overrideVariable(
                CompileBuildVariables.SERIALIZED_DIAGNOSTICS_FILE.getVariableName(),
                diagnosticsFileArtifact
            )
        }

        if (ltoIndexFileArtifact != null) {
            buildVariables.overrideVariable(
                CompileBuildVariables.LTO_INDEXING_BITCODE_FILE.getVariableName(), ltoIndexFileArtifact
            )
        }

        builder.setVariables(buildVariables.build())

        try {
            return builder.buildAndVerify()
        } catch (e: UnconfiguredActionConfigException) {
            throw throwActionExecutionException(e)
        } catch (e: net.starlark.java.eval.EvalException) {
            throw throwActionExecutionException(e)
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun outputTreeFileArtifactName(inputTreeFileArtifact: TreeFileArtifact): String? {
        var outputName: String? = com.google.devtools.build.lib.vfs.FileSystemUtils.removeExtension(
            inputTreeFileArtifact.getParentRelativePath().getPathString()
        )
        for (category in categories) {
            outputName = toolchain.getFeatures().getArtifactNameForCategory(category, outputName)
        }
        return outputName
    }

    @Throws(ActionExecutionException::class)
    private fun throwActionExecutionException(cause: java.lang.Exception): ActionExecutionException? {
        throw ActionExecutionException(
            cause, this,  /*catastrophe=*/false, makeDetailedExitCode(cause.getMessage())
        )
    }

    val inputTreeArtifacts: com.google.common.collect.ImmutableList<SpecialArtifact?>
        get() = com.google.common.collect.ImmutableList.of<SpecialArtifact?>(sourceTreeArtifact)

    val owner: ActionOwner
        get() = actionOwner

    val isShareable: Boolean
        get() = false

    val mnemonic: String
        get() = "CppCompileActionTemplate"

    public override fun getMandatoryInputs(): NestedSet<Artifact?> {
        return NestedSetBuilder.< Artifact > compileOrder < Artifact ? > ()
            .add(sourceTreeArtifact)
            .addTransitive(mandatoryInputs)
            .build()
    }

    public override fun getInputFilesForExtraAction(
        actionExecutionContext: ActionExecutionContext?
    ): NestedSet<Artifact?> {
        return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    }

    val mandatoryOutputs: com.google.common.collect.ImmutableSet<Artifact?>
        get() = com.google.common.collect.ImmutableSet.of<Artifact?>()

    val tools: NestedSet<Artifact?>
        get() = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

    val inputs: NestedSet<Artifact?>
        get() = NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
            .add(sourceTreeArtifact)
            .addTransitive(allInputs)
            .build()

    val originalInputs: NestedSet<Artifact?>
        get() = this.inputs

    val schedulingDependencies: NestedSet<Artifact?>
        get() = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

    val outputs: com.google.common.collect.ImmutableSet<Artifact?>
        get() {
            val builder: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
                com.google.common.collect.ImmutableSet.builder<Artifact?>()
            builder.add(outputTreeArtifact)
            if (dotdTreeArtifact != null) {
                builder.add(dotdTreeArtifact)
            }
            if (ltoIndexTreeArtifact != null) {
                builder.add(ltoIndexTreeArtifact)
            }
            return builder.build()
        }

    val clientEnvironmentVariables: com.google.common.collect.ImmutableList<String?>
        get() = com.google.common.collect.ImmutableList.of<String?>()

    public override fun prettyPrint(): String {
        return "CppCompileActionTemplate compiling " + sourceTreeArtifact.getExecPathString()
    }

    public override fun describe(): String {
        return "Compiling all C++ files in " + sourceTreeArtifact.prettyPrint()
    }

    override fun toString(): String {
        return prettyPrint()
    }

    companion object {
        // LINT.IfChange(cc_and_objc_file_types)
        private val CC_AND_OBJC_FILE_TYPES: FileTypeSet = FileTypeSet.of(
            CppFileTypes.CPP_SOURCE,
            CppFileTypes.CPP_HEADER,
            CppFileTypes.OBJC_SOURCE,
            CppFileTypes.OBJCPP_SOURCE,
            CppFileTypes.C_SOURCE,
            CppFileTypes.ASSEMBLER,
            CppFileTypes.ASSEMBLER_WITH_C_PREPROCESSOR
        )

        private fun makeDetailedExitCode(message: String?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetails.FailureDetail.newBuilder()
                    .setMessage(message)
                    .setExecution(
                        FailureDetails.Execution.newBuilder()
                            .setCode(
                                FailureDetails.Execution.Code
                                    .PERSISTENT_ACTION_OUTPUT_DIRECTORY_CREATION_FAILURE
                            )
                    )
                    .build()
            )
        }
    }
}
