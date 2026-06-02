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

import com.google.devtools.build.lib.actions.ActionEnvironment

/**
 * An [ActionTemplate] that expands into [LtoBackendAction]s at execution time. Is is
 * similar to [com.google.devtools.build.lib.analysis.actions.SpawnActionTemplate].
 */
class LtoBackendActionTemplate internal constructor(
    indexAndImportsTreeArtifact: SpecialArtifact?,
    fullBitcodeTreeArtifact: SpecialArtifact,
    objectFileTreeArtifact: SpecialArtifact?,
    dwoFileTreeArtifact: SpecialArtifact?,
    featureConfiguration: FeatureConfiguration?,
    additionalInputs: NestedSet<Artifact?>?,
    env: ActionEnvironment?,
    buildVariables: CcToolchainVariables?,
    usePic: Boolean,
    bitcodeFiles: BitcodeFiles?,
    actionOwner: ActionOwner?
) : ActionKeyComputer(), ActionTemplate<LtoBackendAction?> {
    private val buildVariables: CcToolchainVariables?

    private val additionalInputs: NestedSet<Artifact?>?

    private val env: ActionEnvironment?

    // An input tree artifact containing the full bitcode. It is never null.
    private val fullBitcodeTreeArtifact: SpecialArtifact

    // An input tree artifact containing ".thinlto.bc" and ".imports" files, generated together with
    // It will be null when this is a shared non-lto backend.
    private val indexAndImportsTreeArtifact: SpecialArtifact?

    // An output tree artifact that will contain the native objects. In a sibling directory to
    // indexTreeArtifact. The objects will be generated in the same location as defined in the .param
    // file created during the lto indexing step.
    private val objectFileTreeArtifact: SpecialArtifact?

    // The corresponding dwoFile if fission is used.
    private val dwoFileTreeArtifact: SpecialArtifact?

    private val featureConfiguration: FeatureConfiguration?

    private val usePic: Boolean

    private val bitcodeFiles: BitcodeFiles?

    private val actionOwner: ActionOwner
    private val mandatoryInputs: NestedSet<Artifact?>?
    private val allInputs: NestedSet<Artifact?>?

    /**
     * Creates an LtoBackendActionTemplate.
     * 
     * @param indexAndImportsTreeArtifact the TreeArtifact that contains .thinlto.bc. and .imports
     * files.
     * @param fullBitcodeTreeArtifact the TreeArtifact that contains .pic.o files.
     * @param objectFileTreeArtifact the TreeArtifact that contains .pic.o files.
     * @param dwoFileTreeArtifact the TreeArtifact that contains .dwo files.
     * @param featureConfiguration the feature configuration.
     * @param additionalInputs additional inputs
     * @param env action environment
     * @param buildVariables the building variables.
     * @param usePic whether to use PIC or not.
     * @param actionOwner the owner of this [ActionTemplate].
     */
    init {
        this.additionalInputs = additionalInputs
        this.env = env
        this.buildVariables = buildVariables
        this.indexAndImportsTreeArtifact = indexAndImportsTreeArtifact
        this.fullBitcodeTreeArtifact = fullBitcodeTreeArtifact
        this.objectFileTreeArtifact = objectFileTreeArtifact
        this.dwoFileTreeArtifact = dwoFileTreeArtifact
        this.actionOwner =
            com.google.common.base.Preconditions.checkNotNull<ActionOwner>(actionOwner, objectFileTreeArtifact)
        this.featureConfiguration = featureConfiguration
        this.usePic = usePic
        this.bitcodeFiles = bitcodeFiles

        val mandatoryInputsBuilder: NestedSetBuilder<Artifact?> =
            NestedSetBuilder.< Artifact > compileOrder < Artifact ? > ()
                .add(fullBitcodeTreeArtifact)
                .addTransitive(additionalInputs)
        if (indexAndImportsTreeArtifact != null) {
            mandatoryInputsBuilder.add(indexAndImportsTreeArtifact)
        }
        this.mandatoryInputs = mandatoryInputsBuilder.build()
        this.allInputs = mandatoryInputs
    }

    /** Helper functions for generateActionsForInputArtifacts  */
    private fun pathFragmentToRelativePath(parentPath: PathFragment?, path: PathFragment): String? {
        return path.relativeTo(parentPath).getSafePathString()
    }

    private fun removeImportsExtension(path: String): String {
        return com.google.devtools.build.lib.vfs.FileSystemUtils.removeExtension(path)
    }

    private fun removeThinltoBcExtension(path: String): String {
        return com.google.devtools.build.lib.vfs.FileSystemUtils.removeExtension(
            com.google.devtools.build.lib.vfs.FileSystemUtils.removeExtension(
                path
            )
        )
    }

    private fun generateDwoArtifact(
        fullBitcodeRelativePath: String?, artifactOwner: ActionLookupKey?
    ): TreeFileArtifact? {
        if (dwoFileTreeArtifact != null) {
            return TreeFileArtifact.createTemplateExpansionOutput(
                dwoFileTreeArtifact,
                com.google.devtools.build.lib.vfs.FileSystemUtils.replaceExtension(
                    PathFragment.create(
                        fullBitcodeRelativePath
                    ), ".dwo"
                ),
                artifactOwner
            )
        } else {
            return null
        }
    }

    private fun generateOutputObjArtifact(
        fullBitcodeRelativePath: String?, artifactOwner: ActionLookupKey?
    ): TreeFileArtifact {
        return TreeFileArtifact.createTemplateExpansionOutput(
            objectFileTreeArtifact, fullBitcodeRelativePath, artifactOwner
        )
    }

    /**
     * Generates actions for the input artifacts.
     * 
     * 
     * If indexAndImportsTreeArtifact is null, [getInputTreeArtifact] will return the
     * fullBitcodeTreeArtifact and the input artifacts for this action template will be the
     * fullBitcodeTreeFileArtifacts otherwise it will be the indexAndImportsTreeFileArtifacts.
     * 
     * 
     * We use indexAndImportsTreeFileArtifact when possible instead of making both cases expand the
     * fullBitcodeTreeFileArtifacts so we can split the contents of indexAndImportsTreeFileArtifact
     * into index files and import files, and use it to give better error messages.
     */
    @Throws(ActionExecutionException::class)
    public override fun generateActionsForInputArtifacts(
        inputTreeFileArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact>,
        artifactOwner: ActionLookupKey?,
        eventHandler: com.google.devtools.build.lib.events.EventHandler?
    ): com.google.common.collect.ImmutableList<LtoBackendAction?> {
        if (indexAndImportsTreeArtifact != null) {
            return generateActionsForLtoArtifacts(inputTreeFileArtifacts, artifactOwner)
        } else {
            return generateActionsForNonLtoArtifacts(inputTreeFileArtifacts, artifactOwner)
        }
    }

    /**
     * When generating actions for the shared nonlto backend, we do not use
     * indexAndImportsTreeArtifact, instead we only use the fullBitcodeTreeArtifact files.
     */
    private fun generateActionsForNonLtoArtifacts(
        fullBitcodeTreeFileArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact>,
        artifactOwner: ActionLookupKey?
    ): com.google.common.collect.ImmutableList<LtoBackendAction?> {
        val expandedActions: com.google.common.collect.ImmutableList.Builder<LtoBackendAction?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<LtoBackendAction?>(
                fullBitcodeTreeFileArtifacts.size()
            )
        val fullBitcodeParentPath: PathFragment? = fullBitcodeTreeArtifact.getExecPath()
        for (inputTreeFileArtifact in fullBitcodeTreeFileArtifacts) {
            val path: PathFragment = inputTreeFileArtifact.getExecPath()
            val fullBitcodeRelativePath = pathFragmentToRelativePath(fullBitcodeParentPath, path)
            val objTreeFileArtifact: TreeFileArtifact =
                generateOutputObjArtifact(fullBitcodeRelativePath, artifactOwner)
            val dwoFileArtifact: TreeFileArtifact? =
                generateDwoArtifact(fullBitcodeRelativePath, artifactOwner)

            val action: LtoBackendAction =
                LtoBackendArtifacts.createLtoBackendActionForTemplate(
                    this.owner,
                    additionalInputs,
                    env,
                    buildVariables,
                    featureConfiguration,  /* index= */
                    null,  /* imports= */
                    null,
                    inputTreeFileArtifact,
                    objTreeFileArtifact,
                    bitcodeFiles,
                    dwoFileArtifact,
                    usePic,  /* bitcodeFilePath= */
                    null,  /* isDummyAction= */
                    false
                )
            expandedActions.add(action)
        }

        return expandedActions.build()
    }

    /**
     * Given all the files inside indexAndImportsTreeArtifact, we find the corresponding index and
     * imports files. Then we use their path together with the fullBitcodeTreeArtifact path to derive
     * the path of the original full bitcode file. Then for each imports file, we create an lto
     * backend action that depends on that import file, on the corresponding index file, and on the
     * whole fullBitcodeTreeArtifact, which it uses to find the full bitcode file. TODO(antunesi):
     * make the generated action depend only on the corresponding full bitcode file rather than depend
     * on the whole tree artifact that contains the full bitcode file.
     */
    @Throws(ActionExecutionException::class)
    private fun generateActionsForLtoArtifacts(
        indexAndImportsTreeFileArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact>,
        artifactOwner: ActionLookupKey?
    ): com.google.common.collect.ImmutableList<LtoBackendAction?> {
        val expandedActions: com.google.common.collect.ImmutableList.Builder<LtoBackendAction?> =
            com.google.common.collect.ImmutableList.Builder<LtoBackendAction?>()

        val thinltoBcSourceType: com.google.devtools.build.lib.util.FileType = CppFileTypes.LTO_INDEXING_ANALYSIS_FILE
        val importsType: com.google.devtools.build.lib.util.FileType = CppFileTypes.LTO_IMPORTS_FILE

        val importsBuilder: com.google.common.collect.ImmutableList.Builder<TreeFileArtifact> =
            com.google.common.collect.ImmutableList.builder<TreeFileArtifact?>()
        val nameToThinLtoBuilder: com.google.common.collect.ImmutableMap.Builder<String?, TreeFileArtifact?> =
            com.google.common.collect.ImmutableMap.Builder<String?, TreeFileArtifact?>()

        val indexAndImportParentPath: PathFragment? = indexAndImportsTreeArtifact.getExecPath()

        for (inputTreeFileArtifact in indexAndImportsTreeFileArtifacts) {
            val path: PathFragment = inputTreeFileArtifact.getExecPath()
            val isThinLto: Boolean = thinltoBcSourceType.matches(path)
            val isImport: Boolean = importsType.matches(path)

            if (isThinLto) {
                val thinLtoNoExtension =
                    removeThinltoBcExtension(pathFragmentToRelativePath(indexAndImportParentPath, path)!!)
                nameToThinLtoBuilder.put(thinLtoNoExtension, inputTreeFileArtifact)
            } else if (isImport) {
                importsBuilder.add(inputTreeFileArtifact)
            } else {
                val message: String? =
                    java.lang.String.format(
                        "Artifact '%s' expanded from the directory artifact '%s' is neither imports nor"
                                + " thinlto .",
                        inputTreeFileArtifact.getExecPathString(),
                        fullBitcodeTreeArtifact.getExecPathString()
                    ) // kinda wrong, should be index
                throw ActionExecutionException(
                    message, this,  /* catastrophe= */false, makeDetailedExitCode(message)
                )
            }
        }

        // Maps each imports to a .bc file
        val imports: com.google.common.collect.ImmutableList<TreeFileArtifact> = importsBuilder.build()
        val nameToThinLto: com.google.common.collect.ImmutableMap<String?, TreeFileArtifact?> =
            nameToThinLtoBuilder.buildOrThrow()
        if (imports.size() != nameToThinLto.size()) {
            val message: String? =
                java.lang.String.format(
                    "Either both or neither bitcodeFiles and imports files should be null. %s %s" + ".",
                    indexAndImportsTreeFileArtifacts,
                    fullBitcodeTreeArtifact.getExecPathString()
                ) // kinda wrong, should be index
            throw ActionExecutionException(
                message, this,  /* catastrophe= */false, makeDetailedExitCode(message)
            )
        }

        for (importFile in imports) {
            val path: PathFragment = importFile.getExecPath()
            // The relative path of the fullBitcodeFile with respect to the fullBitcodeTreeArtifact
            val fullBitcodeRelativePath =
                removeImportsExtension(pathFragmentToRelativePath(indexAndImportParentPath, path)!!)
            val thinLtoFile: TreeFileArtifact? = nameToThinLto.get(fullBitcodeRelativePath)
            val fullBitcodePath: PathFragment =
                fullBitcodeTreeArtifact.getExecPath().getRelative(fullBitcodeRelativePath)
            val objTreeFileArtifact: TreeFileArtifact =
                generateOutputObjArtifact(fullBitcodeRelativePath, artifactOwner)
            val dwoFileArtifact: TreeFileArtifact? =
                generateDwoArtifact(fullBitcodeRelativePath, artifactOwner)

            val action: LtoBackendAction =
                LtoBackendArtifacts.createLtoBackendActionForTemplate(
                    this.owner,
                    additionalInputs,
                    env,
                    buildVariables,
                    featureConfiguration,
                    thinLtoFile,
                    importFile,
                    fullBitcodeTreeArtifact,
                    objTreeFileArtifact,
                    bitcodeFiles,
                    dwoFileArtifact,
                    usePic,
                    fullBitcodePath.toString(),  /* isDummyAction= */
                    false
                )
            expandedActions.add(action)
        }

        return expandedActions.build()
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        val dummyAction: LtoBackendAction = this.dummyAction
        dummyAction.computeKey(actionKeyContext, inputMetadataProvider, fp)
    }

    private val dummyAction: LtoBackendAction
        /**
         * This is an action that is not valid, because its input bitcode file is a TreeArtifact rather
         * than a specific file. It is useful for calculating keys and inputs of the Action Template by
         * reusing functionality from LtoBackendAction.
         */
        get() =// This is a dummy action that would not work, because the bitcode file path is a directory
            // rather than a file.
            LtoBackendArtifacts.createLtoBackendActionForTemplate(
                this.owner,
                additionalInputs,
                env,
                buildVariables,
                featureConfiguration,
                indexAndImportsTreeArtifact,
                indexAndImportsTreeArtifact,
                fullBitcodeTreeArtifact,
                objectFileTreeArtifact,
                bitcodeFiles,
                dwoFileTreeArtifact,
                usePic,
                null,  /* isDummyAction= */
                true
            )

    val inputTreeArtifacts: com.google.common.collect.ImmutableList<SpecialArtifact?>
        get() = com.google.common.collect.ImmutableList.of<SpecialArtifact?>(
            com.google.common.base.MoreObjects.firstNonNull<SpecialArtifact?>(
                indexAndImportsTreeArtifact,
                fullBitcodeTreeArtifact
            )
        )

    val owner: ActionOwner
        get() = actionOwner

    val isShareable: Boolean
        get() = false

    val mnemonic: String
        get() = "LtoBackendActionTemplate"

    public override fun getMandatoryInputs(): NestedSet<Artifact?>? {
        return mandatoryInputs
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

    val inputs: NestedSet<Artifact?>?
        get() = allInputs

    val originalInputs: NestedSet<Artifact?>?
        get() = allInputs

    val outputs: com.google.common.collect.ImmutableSet<Artifact?>
        get() {
            val builder: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
                com.google.common.collect.ImmutableSet.builder<Artifact?>()
            builder.add(objectFileTreeArtifact)
            if (dwoFileTreeArtifact != null) {
                builder.add(dwoFileTreeArtifact)
            }
            return builder.build()
        }

    val clientEnvironmentVariables: com.google.common.collect.ImmutableList<String?>
        get() = com.google.common.collect.ImmutableList.of<String?>()

    val schedulingDependencies: NestedSet<Artifact?>
        get() = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

    public override fun prettyPrint(): String {
        return "LtoBackendActionTemplate compiling " + fullBitcodeTreeArtifact.getExecPathString()
    }

    public override fun describe(): String {
        return "Lto backend compiling all C++ files in " + fullBitcodeTreeArtifact.prettyPrint()
    }

    override fun toString(): String {
        return prettyPrint()
    }

    companion object {
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
