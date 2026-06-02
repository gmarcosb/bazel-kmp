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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.AbstractCommandLine

/** Remaining code that is needed because [LtoBackendActionTemplate] is still native.  */
object LtoBackendArtifacts {
    // LINT.IfChange(lto_backends)
    private fun addPathsToBuildVariablesBuilder(
        buildVariablesBuilder: com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Builder,
        indexPath: String?,
        objectFilePath: String?,
        dwoFilePath: String?,
        bitcodeFilePath: String?
    ) {
        // Ideally, those strings would come directly from the execPath of the Artifacts of
        // the LtoBackendAction.Builder; however, in order to support tree artifacts, we need
        // the bitcodeFilePath to be different from the bitcodeTreeArtifact execPath.
        // The former is a file path and the latter is the directory path.
        // Therefore we accept strings as inputs rather than artifacts.
        if (indexPath != null) {
            buildVariablesBuilder.addVariable("thinlto_index", indexPath)
        } else {
            // An empty input indicates not to perform cross-module optimization.
            buildVariablesBuilder.addVariable("thinlto_index", "/dev/null")
        }
        // The output from the LTO backend step is a native object file.
        buildVariablesBuilder.addVariable("thinlto_output_object_file", objectFilePath)
        // The input to the LTO backend step is the bitcode file.
        buildVariablesBuilder.addVariable("thinlto_input_bitcode_file", bitcodeFilePath)

        // Add the context sensitive instrument path to the backend.
        if (dwoFilePath != null) {
            buildVariablesBuilder.addVariable(
                CompileBuildVariables.PER_OBJECT_DEBUG_INFO_FILE.getVariableName(), dwoFilePath
            )
            buildVariablesBuilder.addVariable(
                CompileBuildVariables.IS_USING_FISSION.getVariableName(), ""
            )
        }
    }

    private fun getLtoBackendActionInputs(
        index: Artifact?,
        imports: Artifact?,
        bitcodeFile: Artifact?,
        additionalInputs: NestedSet<Artifact?>?
    ): NestedSet<Artifact?> {
        val inputsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        inputsBuilder.addTransitive(additionalInputs)
        inputsBuilder.add(bitcodeFile)
        if (imports != null) {
            // Although the imports file is not used by the LTOBackendAction while the action is
            // executing, it is needed during the input discovery phase, and we must list it as an input
            // to the action in order for it to be preserved under --discard_orphaned_artifacts.
            inputsBuilder.add(imports)
        }
        if (index != null) {
            inputsBuilder.add(index)
        }
        return inputsBuilder.build()
    }

    private fun getLtoBackendActionOutputs(
        objectFile: Artifact?, dwoFile: Artifact?
    ): com.google.common.collect.ImmutableSet<Artifact?> {
        val builder: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
            com.google.common.collect.ImmutableSet.builder<Artifact?>()
        builder.add(objectFile)
        // Add the context sensitive instrument path to the backend.
        if (dwoFile != null) {
            builder.add(dwoFile)
        }
        return builder.build()
    }

    private fun getLtoBackendCommandLine(
        featureConfiguration: FeatureConfiguration,
        buildVariables: CcToolchainVariables?,
        usePic: Boolean
    ): CommandLines {
        val ltoCommandLine: CommandLine =
            object : AbstractCommandLine() {
                @Throws(CommandLineExpansionException::class)
                public override fun arguments(): Iterable<String?> {
                    return arguments( /* inputMetadataProvider= */null, PathMapper.NOOP)
                }

                @Throws(CommandLineExpansionException::class)
                public override fun arguments(
                    inputMetadataProvider: InputMetadataProvider?, pathMapper: PathMapper?
                ): com.google.common.collect.ImmutableList<String?> {
                    val args: com.google.common.collect.ImmutableList.Builder<String?> =
                        com.google.common.collect.ImmutableList.builder<String?>()
                    try {
                        args.addAll(
                            featureConfiguration.getCommandLine(
                                CppActionNames.LTO_BACKEND,
                                buildVariables,
                                inputMetadataProvider,
                                pathMapper
                            )
                        )
                    } catch (e: com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException) {
                        throw CommandLineExpansionException(e.getMessage())
                    }
                    // If this is a PIC compile (set based on the CppConfiguration), the PIC
                    // option should be added after the rest of the command line so that it
                    // cannot be overridden. This is consistent with the ordering in the
                    // CppCompileAction's compiler options.
                    if (usePic) {
                        args.add("-fPIC")
                    }
                    return args.build()
                }
            }
        val compiler: PathFragment? =
            PathFragment.create(featureConfiguration.getToolPathForAction(CppActionNames.LTO_BACKEND))
        return CommandLines.builder()
            .addSingleArgument(compiler)
            .addCommandLine(ltoCommandLine)
            .build()
    }

    fun createLtoBackendActionForStarlark(
        owner: ActionOwner?,
        configuration: BuildConfigurationValue?,
        featureConfiguration: FeatureConfiguration,
        buildVariables: CcToolchainVariables?,
        usePic: Boolean,
        inputs: NestedSet<Artifact?>?,
        allBitcodeFiles: BitcodeFiles?,
        imports: Artifact?,
        outputs: com.google.common.collect.ImmutableSet<Artifact?>?,
        env: ActionEnvironment?
    ): LtoBackendAction {
        val commandLines: CommandLines =
            getLtoBackendCommandLine(featureConfiguration, buildVariables, usePic)
        return LtoBackendAction.Companion.create(
            owner, configuration, inputs, allBitcodeFiles, imports, outputs, commandLines, env
        )
    }

    /**
     * Adds artifact to builder. The resulting builder can be built into a valid ltoBackendAction.
     * 
     * 
     * Assumes that buildVariables have been initialized previously. If this is not true, the
     * action will be wrong.
     * 
     * @param buildVariables preinitialized CcToolchainVariables.
     * @param featureConfiguration the feature configuration to get the command line for the builder.
     * @param index the index artifact to add. Can be a TreeFileArtifact but cannot be a Tree
     * Artifact.
     * @param imports the imports artifact to add. Can be a TreeFileArtifact but cannot be a Tree
     * Artifact.
     * @param bitcodeArtifact the bitcode artifact to add. If it is a Tree Artifact, bitcodeFilePath
     * must be set.
     * @param objectFile the object file to add. Can be a TreeFileArtifact but cannot be a Tree
     * Artifact.
     * @param bitcodeFiles the bitcode files to add.
     * @param dwoFile the dwo file to add.
     * @param usePic whether to add the PIC option to the command line.
     * @param bitcodeFilePath the path of the bitcode object we are compiling. Only used if
     * bitcodeArtifact is a tree artifact.
     * @param isDummyAction if true then ignores the preconditions, because it is generating a dummy
     * action, not a valid action.
     */
    fun createLtoBackendActionForTemplate(
        actionOwner: ActionOwner?,
        additionalInputs: NestedSet<Artifact?>?,
        env: ActionEnvironment?,
        buildVariables: CcToolchainVariables?,
        featureConfiguration: FeatureConfiguration,
        index: Artifact?,
        imports: Artifact?,
        bitcodeArtifact: Artifact,
        objectFile: Artifact,
        bitcodeFiles: BitcodeFiles?,
        dwoFile: Artifact?,
        usePic: Boolean,
        bitcodeFilePath: String?,
        isDummyAction: Boolean
    ): LtoBackendAction {
        com.google.common.base.Preconditions.checkState(
            isDummyAction
                    || ((index == null || !index.isTreeArtifact())
                    && (imports == null || !imports.isTreeArtifact())
                    && (dwoFile == null || !dwoFile.isTreeArtifact())
                    && !objectFile.isTreeArtifact()),
            "index, imports, object and dwo files cannot be TreeArtifacts. We need to know their exact"
                    + " path not just directory path."
        )
        com.google.common.base.Preconditions.checkState(
            isDummyAction || (bitcodeArtifact.isTreeArtifact() xor (bitcodeFilePath == null)),
            ("If bitcode file is a tree artifact, the bitcode file path must contain the path. If it is"
                    + " not a tree artifact, then bitcode file path should be null to not override the"
                    + " path.")
        )
        val buildVariablesBuilder: com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Builder =
            CcToolchainVariables.Companion.builder(buildVariables)
        val inputs: NestedSet<Artifact?> =
            getLtoBackendActionInputs(index, imports, bitcodeArtifact, additionalInputs)
        val outputs: com.google.common.collect.ImmutableSet<Artifact?> = getLtoBackendActionOutputs(objectFile, dwoFile)

        val indexPath: String? = if (index == null) null else index.getExecPathString()
        val dwoFilePath: String? = if (dwoFile == null) null else dwoFile.getExecPathString()
        addPathsToBuildVariablesBuilder(
            buildVariablesBuilder,
            indexPath,
            objectFile.getExecPathString(),
            dwoFilePath,
            if (bitcodeFilePath != null) bitcodeFilePath else bitcodeArtifact.getExecPathString()
        )
        val buildVariablesWithFiles: CcToolchainVariables? = buildVariablesBuilder.build()

        return createLtoBackendActionForStarlark(
            actionOwner,  /* configuration= */
            null,
            featureConfiguration,
            buildVariablesWithFiles,
            usePic,
            inputs,
            bitcodeFiles,
            imports,
            outputs,
            env
        )
    } // LINT.ThenChange(@rules_cc//cc/private/link/lto_backends.bzl:lto_backends)
}
