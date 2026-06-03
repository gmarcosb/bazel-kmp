// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.ActionEnvironment

/** An [ActionTemplate] that expands into [SpawnAction]s at execution time.  */
class SpawnActionTemplate private constructor(
    actionOwner: ActionOwner?,
    inputTreeArtifact: SpecialArtifact,
    outputTreeArtifact: SpecialArtifact,
    commonInputs: NestedSet<Artifact?>?,
    commonTools: NestedSet<Artifact?>?,
    outputPathMapper: OutputPathMapper,
    commandLineTemplate: CustomCommandLine,
    mnemonic: String,
    spawnActionBuilder: com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder
) : ActionKeyComputer(), ActionTemplate<SpawnAction?> {
    private val inputTreeArtifact: SpecialArtifact
    private val outputTreeArtifact: SpecialArtifact
    private val allInputs: NestedSet<Artifact?>?
    private val commonTools: NestedSet<Artifact?>?
    private val actionOwner: ActionOwner?
    private val mnemonic: String
    private val outputPathMapper: OutputPathMapper
    private val spawnActionBuilder: com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder
    private val commandLineTemplate: CustomCommandLine

    /**
     * Interface providing mapping between expanded input files under the input TreeArtifact and
     * parent-relative paths of their associated output file under the output TreeArtifact.
     * 
     * 
     * Users of SpawnActionTemplate must provide a mapper object implementing this interface.
     * SpawnActionTemplate uses the mapper to query for the path of output artifact associated with
     * each input [TreeFileArtifact] resolved at execution time.
     */
    interface OutputPathMapper {
        /**
         * Given the input [TreeFileArtifact], returns the parent-relative path of the associated
         * output [TreeFileArtifact].
         * 
         * @param input the input [TreeFileArtifact]
         */
        fun parentRelativeOutputPath(input: TreeFileArtifact?): PathFragment?
    }

    init {
        this.inputTreeArtifact = inputTreeArtifact
        this.outputTreeArtifact = outputTreeArtifact
        this.commonTools = commonTools
        this.allInputs =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .add(inputTreeArtifact)
                .addTransitive(commonInputs)
                .addTransitive(commonTools)
                .build()
        this.outputPathMapper = outputPathMapper
        this.actionOwner = actionOwner
        this.mnemonic = mnemonic
        this.spawnActionBuilder = spawnActionBuilder
        this.commandLineTemplate = commandLineTemplate
    }

    public override fun generateActionsForInputArtifacts(
        inputTreeFileArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact?>,
        artifactOwner: ActionLookupKey?,
        eventHandler: com.google.devtools.build.lib.events.EventHandler?
    ): com.google.common.collect.ImmutableList<SpawnAction?> {
        val expandedActions: com.google.common.collect.ImmutableList.Builder<SpawnAction?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<SpawnAction?>(inputTreeFileArtifacts.size)
        for (inputTreeFileArtifact in inputTreeFileArtifacts) {
            val parentRelativeOutputPath: PathFragment? =
                outputPathMapper.parentRelativeOutputPath(inputTreeFileArtifact)

            val outputTreeFileArtifact: TreeFileArtifact? =
                TreeFileArtifact.createTemplateExpansionOutput(
                    outputTreeArtifact, parentRelativeOutputPath, artifactOwner
                )

            expandedActions.add(createAction(inputTreeFileArtifact, outputTreeFileArtifact))
        }

        return expandedActions.build()
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        val inputTreeFileArtifact: TreeFileArtifact? =
            TreeFileArtifact.createTreeOutput(inputTreeArtifact, "dummy_for_key")
        val outputTreeFileArtifact: TreeFileArtifact? =
            TreeFileArtifact.createTemplateExpansionOutput(
                outputTreeArtifact,
                outputPathMapper.parentRelativeOutputPath(inputTreeFileArtifact),
                ActionTemplateExpansionValue.key(
                    outputTreeArtifact.getArtifactOwner(),  /*actionIndex=*/0
                )
            )
        val dummyAction: SpawnAction = createAction(inputTreeFileArtifact, outputTreeFileArtifact)
        dummyAction.computeKey(actionKeyContext, inputMetadataProvider, fp)
    }

    /**
     * Returns a SpawnAction that takes inputTreeFileArtifact as input and generates
     * outputTreeFileArtifact.
     */
    private fun createAction(
        inputTreeFileArtifact: TreeFileArtifact?, outputTreeFileArtifact: TreeFileArtifact?
    ): SpawnAction {
        val actionBuilder: com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder =
            com.google.devtools.build.lib.analysis.actions.SpawnActionTemplate.ExpandedSpawnAction.Builder(
                spawnActionBuilder
            )
        actionBuilder.addInput(inputTreeFileArtifact)
        actionBuilder.addOutput(outputTreeFileArtifact)

        val commandLine: CommandLine? = commandLineTemplate.evaluateTreeFileArtifacts(
            com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(inputTreeFileArtifact, outputTreeFileArtifact)
        )
        actionBuilder.addCommandLine(commandLine)

        // Note that we pass in nulls below because SpawnActionTemplate does not support param file, and
        // it does not use any default value for executable or shell environment. They must be set
        // explicitly via builder method #setExecutable and #setEnvironment.
        return actionBuilder.buildForActionTemplate(actionOwner)
    }

    /**
     * Returns the input TreeArtifact(s).
     * 
     * 
     * This method is called by Skyframe to expand the input TreeArtifact(s) into child
     * TreeFileArtifacts. Skyframe then expands this SpawnActionTemplate with the TreeFileArtifacts
     * through [.generateActionsForInputArtifacts].
     */
    public override fun getInputTreeArtifacts(): com.google.common.collect.ImmutableList<SpecialArtifact?> {
        return com.google.common.collect.ImmutableList.of<SpecialArtifact?>(inputTreeArtifact)
    }

    public override fun getOutputs(): com.google.common.collect.ImmutableSet<Artifact?> {
        return com.google.common.collect.ImmutableSet.of<Artifact?>(outputTreeArtifact)
    }

    public override fun getOwner(): ActionOwner? {
        return actionOwner
    }

    public override fun isShareable(): Boolean {
        return true
    }

    public override fun getMnemonic(): String {
        return mnemonic
    }

    public override fun getTools(): NestedSet<Artifact?>? {
        return commonTools
    }

    public override fun getInputs(): NestedSet<Artifact?>? {
        return allInputs
    }

    public override fun getOriginalInputs(): NestedSet<Artifact?>? {
        return getInputs()
    }

    public override fun getSchedulingDependencies(): NestedSet<Artifact?> {
        return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    }

    public override fun getMandatoryInputs(): NestedSet<Artifact?>? {
        return allInputs
    }

    public override fun getInputFilesForExtraAction(
        actionExecutionContext: ActionExecutionContext?
    ): NestedSet<Artifact?> {
        return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    }

    public override fun getMandatoryOutputs(): com.google.common.collect.ImmutableSet<Artifact?> {
        return com.google.common.collect.ImmutableSet.of<Artifact?>()
    }

    public override fun getClientEnvironmentVariables(): MutableCollection<String?> {
        return spawnActionBuilder.buildForActionTemplate(actionOwner).getClientEnvironmentVariables()
    }

    public override fun prettyPrint(): String {
        return "SpawnActionTemplate with output TreeArtifact " + outputTreeArtifact.prettyPrint()
    }

    public override fun describe(): String {
        return "Executing " + mnemonic + " action on all files in " + inputTreeArtifact.prettyPrint()
    }

    override fun toString(): String {
        return prettyPrint()
    }

    /** Builder class to construct [SpawnActionTemplate] instances.  */
    class Builder(inputTreeArtifact: SpecialArtifact, outputTreeArtifact: SpecialArtifact) {
        private var actionTemplateMnemonic = "Unknown"
        private var outputPathMapper: OutputPathMapper? = null
        private var commandLineTemplate: CustomCommandLine? = null
        private var executable: PathFragment? = null

        private val inputTreeArtifact: SpecialArtifact
        private val outputTreeArtifact: SpecialArtifact
        private val inputsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        private val toolsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        private val spawnActionBuilder: com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder

        /**
         * Creates a [SpawnActionTemplate] builder.
         * 
         * @param inputTreeArtifact the required input TreeArtifact.
         * @param outputTreeArtifact the required output TreeArtifact.
         */
        init {
            com.google.common.base.Preconditions.checkState(
                inputTreeArtifact.isTreeArtifact() && outputTreeArtifact.isTreeArtifact(),
                "Either %s or %s is not a TreeArtifact",
                inputTreeArtifact,
                outputTreeArtifact
            )
            this.inputTreeArtifact = inputTreeArtifact
            this.outputTreeArtifact = outputTreeArtifact
            this.spawnActionBuilder = com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder()
        }

        /**
         * Sets the mnemonics for both the [SpawnActionTemplate] and expanded [SpawnAction].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setMnemonics(actionTemplateMnemonic: String, expandedActionMnemonic: String?): Builder {
            this.actionTemplateMnemonic = actionTemplateMnemonic
            spawnActionBuilder.setMnemonic(expandedActionMnemonic)
            return this
        }

        /**
         * Adds common tool artifacts. All common tool artifacts will be added as tool artifacts for
         * expanded actions.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addCommonTools(artifacts: Iterable<Artifact?>?): Builder {
            toolsBuilder.addAll(artifacts)
            spawnActionBuilder.addTools(artifacts)
            return this
        }

        /**
         * Adds common tool artifacts. All common tool artifacts will be added as input tool artifacts
         * for expanded actions.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addCommonTool(tool: FilesToRunProvider): Builder {
            toolsBuilder.addTransitive(tool.getFilesToRun())
            spawnActionBuilder.addTool(tool)
            return this
        }

        /**
         * Adds common input artifacts. All common input artifacts will be added as input artifacts for
         * expanded actions.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addCommonInputs(artifacts: Iterable<Artifact?>?): Builder {
            inputsBuilder.addAll(artifacts)
            spawnActionBuilder.addInputs(artifacts)
            return this
        }

        /** Sets the map of environment variables for expanded actions.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Deprecated("") // TODO(ulfjack): Add env variables to the common environment, rather than replacing
        // it wholesale, which ignores --action_env (unless the client code explicitly handles it).
        fun setEnvironment(environment: MutableMap<String?, String?>?): Builder {
            spawnActionBuilder.setEnvironment(environment)
            return this
        }

        /** Sets the map of execution info for expanded actions.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutionInfo(executionInfo: MutableMap<String?, String?>?): Builder {
            spawnActionBuilder.setExecutionInfo(executionInfo)
            return this
        }

        /**
         * Sets the executable used by expanded actions as a configured target. Automatically adds the
         * files to run to the tools and uses the executable of the target as the executable.
         * 
         * 
         * Calling this method overrides any previous values set via calls to [ ][.setExecutable] and [.setExecutable].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutable(executableProvider: FilesToRunProvider): Builder {
            com.google.common.base.Preconditions.checkArgument(
                executableProvider.getExecutable() != null, "The target does not have an executable"
            )
            spawnActionBuilder.setExecutable(executableProvider)
            addCommonTool(executableProvider)
            this.executable = executableProvider.getExecutable().getExecPath()
            return this
        }

        /**
         * Sets the executable path used by expanded actions. The path is interpreted relative to the
         * execution root, unless it's a bare file name.
         * 
         * 
         * **Caution**: if the executable is a bare file name ("foo"), it will be interpreted
         * relative to PATH. See https://github.com/bazelbuild/bazel/issues/13189 for details. To avoid
         * that, use [.setExecutable] instead.
         * 
         * 
         * Calling this method overrides any previous values set via calls to [ ][.setExecutable] and [.setExecutable].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutable(executable: PathFragment?): Builder {
            spawnActionBuilder.setExecutable(executable)
            this.executable = executable
            return this
        }

        /**
         * Sets the executable artifact used by expanded actions. The path is interpreted relative to
         * the execution root.
         * 
         * 
         * Calling this method overrides any previous values set via calls to [ ][.setExecutable] and [.setExecutable].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutable(artifact: Artifact): Builder {
            spawnActionBuilder.setExecutable(artifact)
            addCommonTools(com.google.common.collect.ImmutableList.of<Artifact?>(artifact))
            this.executable = artifact.getExecPath()
            return this
        }

        /** Sets the command line template used to expand actions.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setCommandLineTemplate(commandLineTemplate: CustomCommandLine?): Builder {
            this.commandLineTemplate = commandLineTemplate
            return this
        }

        /**
         * Sets the [OutputPathMapper] object used to get the parent-relative paths of output
         * [TreeFileArtifact].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setOutputPathMapper(outputPathMapper: OutputPathMapper?): Builder {
            this.outputPathMapper = outputPathMapper
            return this
        }

        /**
         * Builds and returns the [SpawnActionTemplate] using the accumulated builder information.
         * 
         * @param actionOwner the action owner of the SpawnActionTemplate to be built.
         */
        fun build(actionOwner: ActionOwner?): SpawnActionTemplate {
            com.google.common.base.Preconditions.checkNotNull<Any?>(executable)

            return SpawnActionTemplate(
                com.google.common.base.Preconditions.checkNotNull<ActionOwner?>(actionOwner),
                com.google.common.base.Preconditions.checkNotNull<SpecialArtifact?>(inputTreeArtifact),
                com.google.common.base.Preconditions.checkNotNull<SpecialArtifact?>(outputTreeArtifact),
                inputsBuilder.build(),
                toolsBuilder.build(),
                com.google.common.base.Preconditions.checkNotNull<OutputPathMapper?>(outputPathMapper),
                com.google.common.base.Preconditions.checkNotNull<CustomCommandLine?>(commandLineTemplate),
                actionTemplateMnemonic,
                spawnActionBuilder
            )
        }
    }

    private class ExpandedSpawnAction(
        owner: ActionOwner?,
        tools: NestedSet<Artifact?>?,
        inputs: NestedSet<Artifact?>?,
        outputs: Iterable<out Artifact?>?,
        resourceSetOrBuilder: ResourceSetOrBuilder?,
        commandLines: CommandLines?,
        env: ActionEnvironment?,
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>,
        progressMessage: CharSequence?,
        mnemonic: String?
    ) : SpawnAction(
        owner,
        tools,
        inputs,
        outputs,
        resourceSetOrBuilder,
        commandLines,
        env,
        executionInfo,
        progressMessage,
        mnemonic,  /* outputPathsMode= */
        OutputPathsMode.OFF
    ) {
        override fun getCommandLineLimits(): CommandLineLimits {
            return CommandLineLimits.UNLIMITED
        }

        private class Builder(template: com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder) :
            com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder(template) {
            override fun createSpawnAction(
                owner: ActionOwner?,
                tools: NestedSet<Artifact?>?,
                inputsAndTools: NestedSet<Artifact?>?,
                outputs: com.google.common.collect.ImmutableSet<Artifact?>?,
                resourceSetOrBuilder: ResourceSetOrBuilder?,
                commandLines: CommandLines?,
                env: ActionEnvironment?,
                configuration: BuildConfigurationValue?,
                executionInfo: com.google.common.collect.ImmutableMap<String?, String?>,
                progressMessage: CharSequence?,
                mnemonic: String?
            ): SpawnAction {
                return ExpandedSpawnAction(
                    owner,
                    tools,
                    inputsAndTools,
                    outputs,
                    resourceSetOrBuilder,
                    commandLines,
                    env,
                    executionInfo,
                    progressMessage,
                    mnemonic
                )
            }
        }
    }
}
