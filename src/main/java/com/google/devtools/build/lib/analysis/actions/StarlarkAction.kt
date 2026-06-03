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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.Action

/** A Starlark specific SpawnAction.  */
open class StarlarkAction : SpawnAction {
    private constructor(
        owner: ActionOwner?,
        tools: NestedSet<Artifact?>?,
        inputs: NestedSet<Artifact?>?,
        outputs: Iterable<Artifact?>?,
        resourceSetOrBuilder: ResourceSetOrBuilder?,
        commandLines: CommandLines?,
        env: ActionEnvironment?,
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>,
        progressMessage: CharSequence?,
        mnemonic: String?,
        outputPathsMode: OutputPathsMode?
    ) : super(
        owner,
        tools,
        inputs,
        outputs,
        resourceSetOrBuilder,
        commandLines,
        env,
        executionInfo,
        progressMessage,
        mnemonic,
        outputPathsMode
    )

    /** Constructor for serialization.  */
    private constructor(
        owner: ActionOwner?,
        tools: NestedSet<Artifact?>?,
        inputs: NestedSet<Artifact?>?,
        rawOutputs: Any?,
        resourceSetOrBuilder: ResourceSetOrBuilder?,
        commandLines: CommandLines?,
        env: ActionEnvironment?,
        sortedExecutionInfo: com.google.common.collect.ImmutableSortedMap<String?, String?>,
        progressMessage: CharSequence?,
        mnemonic: String?,
        outputPathsMode: OutputPathsMode?
    ) : super(
        owner,
        tools,
        inputs,
        rawOutputs,
        resourceSetOrBuilder,
        commandLines,
        env,
        sortedExecutionInfo,
        progressMessage,
        mnemonic,
        outputPathsMode
    )

    @com.google.common.annotations.VisibleForTesting
    open fun getUnusedInputsList(): java.util.Optional<Artifact?> {
        return java.util.Optional.empty<Artifact?>()
    }

    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    public override fun getInputFilesForExtraAction(
        actionExecutionContext: ActionExecutionContext?
    ): NestedSet<Artifact?>? {
        return getInputs()
    }

    /** Builder class to construct [StarlarkAction] instances.  */
    class Builder : com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder() {
        private var unusedInputsList: java.util.Optional<Artifact> = java.util.Optional.empty<Artifact>()
        private var shadowedAction: java.util.Optional<Action> = java.util.Optional.empty<Action>()

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setUnusedInputsList(unusedInputsList: java.util.Optional<Artifact>): Builder {
            this.unusedInputsList = unusedInputsList
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setShadowedAction(shadowedAction: java.util.Optional<Action>): Builder {
            this.shadowedAction = shadowedAction
            return this
        }

        /** Creates a SpawnAction.  */
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
            var executionInfo: com.google.common.collect.ImmutableMap<String?, String?> = executionInfo
            if (unusedInputsList.isPresent()) {
                // Always download unused_inputs_list file from remote cache.
                executionInfo =
                    com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, String?>(executionInfo.size() + 1)
                        .putAll(executionInfo)
                        .put(
                            ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS,
                            unusedInputsList.get().getExecPathString()
                        )
                        .buildOrThrow()
            }
            val outputPathsMode: OutputPathsMode? = PathMappers.getOutputPathsMode(configuration)
            return if (unusedInputsList.isPresent() || shadowedAction.isPresent())
                EnhancedStarlarkAction(
                    owner,
                    tools,
                    inputsAndTools,
                    outputs,
                    resourceSetOrBuilder,
                    commandLines,
                    env,
                    executionInfo,
                    progressMessage,
                    mnemonic,
                    outputPathsMode,
                    unusedInputsList,
                    shadowedAction
                )
            else
                StarlarkAction(
                    owner,
                    tools,
                    inputsAndTools,
                    outputs,
                    resourceSetOrBuilder,
                    commandLines,
                    env,
                    executionInfo,
                    progressMessage,
                    mnemonic,
                    outputPathsMode
                )
        }
    }

    /** A [StarlarkAction] with `unused_inputs_list` and/or a shadowed action present.  */
    @AutoCodec
    @VisibleForSerialization
    internal class EnhancedStarlarkAction : StarlarkAction {
        // All the inputs of the Starlark action including those listed in the unused inputs and
        // excluding the shadowed action inputs.
        private val allStarlarkActionInputs: NestedSet<Artifact?>?

        // allStarlarkActionInputs plus shadowed action inputs, if present.
        private val originalInputs: NestedSet<Artifact?>?

        // Null when there is no shadowed action.
        private val mandatoryInputs: NestedSet<Artifact?>?

        private val unusedInputsList: java.util.Optional<Artifact>
        private val shadowedAction: java.util.Optional<Action>
        private var inputsDiscovered = false
        private var prunedInputs = false

        constructor(
            owner: ActionOwner?,
            tools: NestedSet<Artifact?>?,
            inputs: NestedSet<Artifact?>?,
            outputs: Iterable<Artifact?>?,
            resourceSetOrBuilder: ResourceSetOrBuilder?,
            commandLines: CommandLines?,
            env: ActionEnvironment?,
            executionInfo: com.google.common.collect.ImmutableMap<String?, String?>,
            progressMessage: CharSequence?,
            mnemonic: String?,
            outputPathsMode: OutputPathsMode?,
            unusedInputsList: java.util.Optional<Artifact>,
            shadowedAction: java.util.Optional<Action>
        ) : super(
            owner,
            tools,
            if (shadowedAction.isPresent())
                createInputs(shadowedAction.get().getInputs(), inputs)
            else
                inputs,
            outputs,
            resourceSetOrBuilder,
            commandLines,
            env,
            executionInfo,
            progressMessage,
            mnemonic,
            outputPathsMode
        ) {
            this.allStarlarkActionInputs = inputs
            this.originalInputs = getInputs()
            this.mandatoryInputs =
                if (shadowedAction.isPresent())
                    createInputs(shadowedAction.get().getMandatoryInputs(), inputs)
                else
                    null
            this.unusedInputsList = unusedInputsList
            this.shadowedAction = shadowedAction
        }

        @AutoCodec.Instantiator
        @VisibleForSerialization
        constructor(
            owner: ActionOwner?,
            tools: NestedSet<Artifact?>?,
            allStarlarkActionInputs: NestedSet<Artifact?>?,
            rawOutputs: Any?,
            resourceSetOrBuilder: ResourceSetOrBuilder?,
            commandLines: CommandLines?,
            environment: ActionEnvironment?,
            sortedExecutionInfo: com.google.common.collect.ImmutableSortedMap<String?, String?>,
            progressMessage: CharSequence?,
            mnemonic: String?,
            outputPathsMode: OutputPathsMode?,
            unusedInputsList: java.util.Optional<Artifact>,
            shadowedAction: java.util.Optional<Action>
        ) : super(
            owner,
            tools,
            if (shadowedAction.isPresent())
                createInputs(shadowedAction.get().getInputs(), allStarlarkActionInputs)
            else
                allStarlarkActionInputs,
            rawOutputs,
            resourceSetOrBuilder,
            commandLines,
            environment,
            sortedExecutionInfo,
            progressMessage,
            mnemonic,
            outputPathsMode
        ) {
            this.allStarlarkActionInputs = allStarlarkActionInputs
            this.originalInputs = getInputs()
            this.mandatoryInputs =
                if (shadowedAction.isPresent())
                    createInputs(shadowedAction.get().getMandatoryInputs(), allStarlarkActionInputs)
                else
                    null
            this.unusedInputsList = unusedInputsList
            this.shadowedAction = shadowedAction
        }

        public override fun getSchedulingDependencies(): NestedSet<Artifact?> {
            return if (shadowedAction.isPresent())
                shadowedAction.get().getSchedulingDependencies()
            else
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        }

        override fun getUnusedInputsList(): java.util.Optional<Artifact> {
            return unusedInputsList
        }

        public override fun isShareable(): Boolean {
            return unusedInputsList.isEmpty()
        }

        public override fun discoversInputs(): Boolean {
            return unusedInputsList.isPresent()
                    || (shadowedAction.isPresent() && shadowedAction.get().discoversInputs())
        }

        public override fun prunedInputs(): Boolean {
            return prunedInputs
        }

        public override fun getOriginalInputs(): NestedSet<Artifact?>? {
            return originalInputs
        }

        protected override fun inputsDiscovered(): Boolean {
            return inputsDiscovered
        }

        protected override fun setInputsDiscovered(inputsDiscovered: Boolean) {
            this.inputsDiscovered = inputsDiscovered
        }

        public override fun getMandatoryInputs(): NestedSet<Artifact?>? {
            return if (mandatoryInputs != null) mandatoryInputs else getInputs()
        }

        public override fun getAllowedDerivedInputs(): NestedSet<Artifact?> {
            if (shadowedAction.isPresent()) {
                return createInputs(shadowedAction.get().getAllowedDerivedInputs(), getInputs())
            }
            return getInputs()
        }

        @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
        public override fun discoverInputs(actionExecutionContext: ActionExecutionContext?): NestedSet<Artifact?>? {
            // If the Starlark action shadows another action and the shadowed action discovers its inputs,
            // we get the shadowed action's discovered inputs and append it to the Starlark action inputs.
            if (shadowedAction.isPresent() && shadowedAction.get().discoversInputs()) {
                val shadowedActionObj: Action = shadowedAction.get()

                val oldInputs: NestedSet<Artifact?> = getInputs()
                val inputFilesForExtraAction: NestedSet<Artifact?>? =
                    shadowedActionObj.getInputFilesForExtraAction(actionExecutionContext)
                if (inputFilesForExtraAction == null) {
                    return null
                }
                updateInputs(
                    createInputs(
                        shadowedActionObj.getInputs(), inputFilesForExtraAction, allStarlarkActionInputs
                    )
                )
                return NestedSetBuilder.wrap(
                    Order.STABLE_ORDER,
                    com.google.common.collect.Sets.difference<E?>(getInputs().toSet(), oldInputs.toSet())
                )
            }
            // Otherwise, we need to "re-discover" all the original inputs: the unused ones that were
            // removed might now be needed.
            updateInputs(allStarlarkActionInputs)
            return allStarlarkActionInputs
        }

        @Throws(IOException::class, ExecException::class)
        private fun getUnusedInputListInputStream(
            actionExecutionContext: ActionExecutionContext, spawnResults: MutableList<SpawnResult?>
        ): java.io.InputStream? {
            // Check if the file is in-memory.
            // Note: SpawnActionContext guarantees that the first list entry exists and corresponds to the
            // executed spawn.

            val unusedInputsListArtifact: Artifact = unusedInputsList.get()
            val content: ByteString? = spawnResults.get(0).getInMemoryOutput(unusedInputsListArtifact)
            if (content != null) {
                return content.newInput()
            }
            // Fallback to reading from disk.
            try {
                return actionExecutionContext
                    .getPathResolver()
                    .toPath(unusedInputsListArtifact)
                    .getInputStream()
            } catch (e: FileNotFoundException) {
                val message =
                    ("Action did not create expected output file listing unused inputs: "
                            + unusedInputsListArtifact.getExecPathString())
                throw UserExecException(
                    e, createFailureDetail(message, Code.UNUSED_INPUT_LIST_FILE_NOT_FOUND)
                )
            }
        }

        @Throws(ExecException::class)
        override fun afterExecute(
            actionExecutionContext: ActionExecutionContext,
            spawnResults: MutableList<SpawnResult?>,
            pathMapper: PathMapper
        ) {
            if (unusedInputsList.isEmpty()) {
                return
            }

            // Initialized lazily in case there are no unused inputs.
            var usedInputsByMappedPath: MutableMap<String?, Artifact?>? = null

            var sawUnusedInput = false

            // Bazel encodes file system paths as raw bytes stored in a Latin-1 encoded string, so we need
            // to make sure to also decode the unused input list as Latin-1.
            try {
                BufferedReader(
                    java.io.InputStreamReader(
                        getUnusedInputListInputStream(actionExecutionContext, spawnResults),
                        java.nio.charset.StandardCharsets.ISO_8859_1
                    )
                ).use { br ->
                    var line: String?
                    while ((br.readLine().also { line = it }) != null) {
                        line = line.trim()
                        if (line.isEmpty()) {
                            continue
                        }
                        if (usedInputsByMappedPath == null) {
                            // Get all the action's inputs after execution which will include the shadowed action
                            // discovered inputs.
                            val allInputs: com.google.common.collect.ImmutableList<Artifact?> = getInputs().toList()
                            usedInputsByMappedPath =
                                com.google.common.collect.Maps.newHashMapWithExpectedSize<String?, Artifact?>(allInputs.size())
                            for (input in allInputs) {
                                usedInputsByMappedPath!!.put(pathMapper.getMappedExecPathString(input), input)
                            }
                        }
                        if (usedInputsByMappedPath!!.remove(line) != null) {
                            sawUnusedInput = true
                        }
                    }
                }
            } catch (e: IOException) {
                throw EnvironmentalExecException(
                    e,
                    createFailureDetail("Unused inputs read failure", Code.UNUSED_INPUT_LIST_READ_FAILURE)
                )
            }

            prunedInputs = sawUnusedInput
            if (sawUnusedInput) {
                updateInputs(NestedSetBuilder.wrap(Order.STABLE_ORDER, usedInputsByMappedPath.values()))
            }
        }

        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        override fun getSpawnForExtraActionSpawnInfo(): Spawn {
            if (shadowedAction.isPresent()) {
                return this.getSpawnForExtraActionSpawnInfo(
                    createInputs(shadowedAction.get().getInputs(), allStarlarkActionInputs)
                )
            }
            return this.getSpawnForExtraActionSpawnInfo(allStarlarkActionInputs)
        }

        @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
        override fun getInputFilesForExtraAction(
            actionExecutionContext: ActionExecutionContext?
        ): NestedSet<Artifact?>? {
            if (shadowedAction.isEmpty()) {
                return allStarlarkActionInputs
            }
            val inputFilesForExtraAction: NestedSet<Artifact?>? =
                shadowedAction.get().getInputFilesForExtraAction(actionExecutionContext)
            if (inputFilesForExtraAction == null) {
                return null
            }
            return createInputs(inputFilesForExtraAction, allStarlarkActionInputs)
        }

        /**
         * {@inheritDoc}
         * 
         * 
         * Adds the environment of the shadowed action, if any, to the execution spawn.
         */
        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        override fun getSpawn(actionExecutionContext: ActionExecutionContext): Spawn? {
            return getSpawn(
                actionExecutionContext,
                getEffectiveEnvironment(actionExecutionContext.getClientEnv()),  /* envResolved= */
                true,  /* reportOutputs= */
                true
            )
        }

        @Throws(CommandLineExpansionException::class)
        public override fun getEffectiveEnvironment(clientEnv: MutableMap<String?, String?>?): com.google.common.collect.ImmutableMap<String?, String?> {
            val env: ActionEnvironment = getEnvironment()
            val environment: MutableMap<String?, String?> =
                com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<K?, V?>(env.estimatedSize())

            if (shadowedAction.isPresent()) {
                // Put all the variables of the shadowed action's environment
                environment.putAll(shadowedAction.get().getEffectiveEnvironment(clientEnv))
            }

            // This order guarantees that the Starlark action can overwrite any variable in its shadowed
            // action environment with a new value.
            env.resolve(environment, clientEnv)
            return com.google.common.collect.ImmutableMap.copyOf<String?, String?>(environment)
        }
    }

    companion object {
        private fun createFailureDetail(message: String?, detailedCode: Code?): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage(message)
                .setStarlarkAction(FailureDetails.StarlarkAction.newBuilder().setCode(detailedCode))
                .build()
        }

        @java.lang.SafeVarargs
        private fun createInputs(vararg inputsLists: NestedSet<Artifact?>?): NestedSet<Artifact?> {
            val nestedSetBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.newBuilder(Order.STABLE_ORDER)
            for (inputs in inputsLists) {
                nestedSetBuilder.addTransitive(inputs)
            }
            return nestedSetBuilder.build()
        }
    }
}
