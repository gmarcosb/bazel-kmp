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
package com.google.devtools.build.lib.analysis.extra

import com.google.devtools.build.lib.actions.AbstractAction

/**
 * Action used by extra_action rules to create an action that shadows an existing action. Runs a
 * command-line using [com.google.devtools.build.lib.actions.SpawnStrategy] for executions.
 */
@AutoCodec
class ExtraAction : SpawnAction {
    private val shadowedAction: Action
    private val createDummyOutput: Boolean
    private val extraActionInputs: NestedSet<Artifact?>?
    private var inputsDiscovered = false

    internal constructor(
        owner: ActionOwner?,
        extraActionInputs: NestedSet<Artifact?>?,
        outputs: MutableCollection<Artifact.DerivedArtifact?>,
        shadowedAction: Action,
        createDummyOutput: Boolean,
        argv: CommandLine?,
        env: ActionEnvironment?,
        executionInfo: MutableMap<String?, String?>?,
        progressMessage: CharSequence?,
        mnemonic: String?
    ) : super(
        owner,  /* tools= */
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        createInputs(
            shadowedAction.getInputs(),  /* inputFilesForExtraAction= */
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            extraActionInputs
        ),
        outputs,
        AbstractAction.DEFAULT_RESOURCE_SET,
        CommandLines.of(argv),
        env,
        com.google.common.collect.ImmutableMap.< K, V > copyOf<K?, V?>(executionInfo),
        progressMessage,
        mnemonic,
        OutputPathsMode.OFF
    ) {
        this.shadowedAction = shadowedAction
        this.createDummyOutput = createDummyOutput

        this.extraActionInputs = extraActionInputs
        if (createDummyOutput) {
            // Expecting just a single dummy file in the outputs.
            com.google.common.base.Preconditions.checkArgument(outputs.size() == 1, outputs)
        }
    }

    @AutoCodec.Instantiator
    @VisibleForSerialization
    internal constructor(
        owner: ActionOwner?,
        extraActionInputs: NestedSet<Artifact?>?,
        rawOutputs: Any?,
        shadowedAction: Action,
        createDummyOutput: Boolean,
        commandLines: CommandLines?,
        environment: ActionEnvironment?,
        sortedExecutionInfo: com.google.common.collect.ImmutableSortedMap<String?, String?>?,
        progressMessage: CharSequence?,
        mnemonic: String?
    ) : super(
        owner,  /* tools= */
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        createInputs(
            shadowedAction.getInputs(),  /* inputFilesForExtraAction= */
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            extraActionInputs
        ),
        rawOutputs,
        AbstractAction.DEFAULT_RESOURCE_SET,
        commandLines,
        environment,
        sortedExecutionInfo,
        progressMessage,
        mnemonic,
        OutputPathsMode.OFF
    ) {
        this.shadowedAction = shadowedAction
        this.createDummyOutput = createDummyOutput
        this.extraActionInputs = extraActionInputs
    }

    override fun getCommandLineLimits(): CommandLineLimits {
        return CommandLineLimits.UNLIMITED
    }

    public override fun discoversInputs(): Boolean {
        return shadowedAction.discoversInputs()
    }

    protected override fun inputsDiscovered(): Boolean {
        return inputsDiscovered
    }

    protected override fun setInputsDiscovered(inputsDiscovered: Boolean) {
        this.inputsDiscovered = inputsDiscovered
    }

    /**
     * This method returns null when a required SkyValue is missing and a Skyframe restart is
     * required.
     */
    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    public override fun discoverInputs(actionExecutionContext: ActionExecutionContext?): NestedSet<Artifact?>? {
        com.google.common.base.Preconditions.checkState(discoversInputs(), this)

        // We need to update our inputs to take account of any additional
        // inputs the shadowed action may need to do its work.
        val oldInputs: NestedSet<Artifact?> = getInputs()
        val inputFilesForExtraAction: NestedSet<Artifact?>? =
            shadowedAction.getInputFilesForExtraAction(actionExecutionContext)
        if (inputFilesForExtraAction == null) {
            return null
        }
        updateInputs(
            createInputs(shadowedAction.getInputs(), inputFilesForExtraAction, extraActionInputs)
        )
        return NestedSetBuilder.wrap(
            Order.STABLE_ORDER, com.google.common.collect.Sets.difference<E?>(getInputs().toSet(), oldInputs.toSet())
        )
    }

    public override fun getOriginalInputs(): NestedSet<Artifact?> {
        return shadowedAction.getOriginalInputs()
    }

    public override fun getSchedulingDependencies(): NestedSet<Artifact?> {
        return shadowedAction.getSchedulingDependencies()
    }

    public override fun getAllowedDerivedInputs(): NestedSet<Artifact?> {
        return shadowedAction.getAllowedDerivedInputs()
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    override fun getSpawn(actionExecutionContext: ActionExecutionContext): Spawn? {
        if (!createDummyOutput) {
            return super.getSpawn(actionExecutionContext)
        }
        return getSpawn(
            actionExecutionContext,
            actionExecutionContext.getClientEnv(),  /* envResolved= */
            false,  /* reportOutputs= */
            false
        )
    }

    @Throws(ExecException::class)
    override fun afterExecute(
        actionExecutionContext: ActionExecutionContext,
        spawnResults: MutableList<SpawnResult?>?,
        pathMapper: PathMapper?
    ) {
        // PHASE 3: create dummy output.
        // If the user didn't specify output, we need to create dummy output
        // to make blaze schedule this action.
        if (createDummyOutput) {
            for (output in getOutputs()) {
                try {
                    FileSystemUtils.touchFile(actionExecutionContext.getInputPath(output))
                } catch (e: IOException) {
                    throw EnvironmentalExecException(e, Code.EXTRA_ACTION_OUTPUT_CREATION_FAILURE)
                }
            }
        }
    }

    /** Returns the action this extra action is 'shadowing'.  */
    fun getShadowedAction(): Action {
        return shadowedAction
    }

    companion object {
        private fun createInputs(
            shadowedActionInputs: NestedSet<Artifact?>?,
            inputFilesForExtraAction: NestedSet<Artifact?>?,
            extraActionInputs: NestedSet<Artifact?>?
        ): NestedSet<Artifact?> {
            return NestedSet.< Artifact > builder < Artifact ? > (Order.STABLE_ORDER)
                .addTransitive(shadowedActionInputs)
                .addTransitive(inputFilesForExtraAction)
                .addTransitive(extraActionInputs)
                .build()
        }
    }
}
