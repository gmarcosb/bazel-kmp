// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.AbstractAction

/** An [ActionTemplate] generated from Starlark's `ctx.actions.map_directory()` API.  */
class StarlarkMapActionTemplate(
    actionOwner: ActionOwner?,
    inputDirectories: Dict<String?, SpecialArtifact?>,
    additionalInputs: Dict<String?, Any?>,
    outputDirectories: Dict<String?, SpecialArtifact?>,
    tools: Dict<String?, Any?>,
    additionalParams: Dict<String?, Any?>?,
    spawnActionBuilder: com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder?,
    executionRequirements: com.google.common.collect.ImmutableMap<String?, String?>?,
    outputPathsMode: OutputPathsMode?,
    env: ActionEnvironment,
    repoMappingSupplier: InterruptibleSupplier<RepositoryMapping?>,
    expandedActionsMnemonic: String?,
    implementation: StarlarkFunction,
    semantics: StarlarkSemantics?,
    symbolGenerator: SymbolGenerator<*>?
) : ActionKeyComputer(), ActionTemplate<AbstractAction?> {
    private val actionOwner: ActionOwner?
    private val inputDirectories: Dict<String?, SpecialArtifact?>

    // Values in `additionalInputs` are either Artifact(s), FilesToRunProvider(s),
    // or NestedSet<Artifact>.
    private val additionalInputs: Dict<String?, Any?>
    private val allInputs: NestedSet<Artifact?>?
    private val outputDirectories: Dict<String?, SpecialArtifact?>
    private val tools: Dict<String?, Any?>
    private val toolsNs: NestedSet<Artifact?>?
    private val additionalParams: Dict<String?, Any?>

    // Comprises of the inputs that get passed along to each SpawnAction.
    private val spawnActionBuilder: com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder?
    private val executionRequirements: com.google.common.collect.ImmutableMap<String?, String?>?
    private val outputPathsMode: OutputPathsMode?
    private val env: ActionEnvironment
    private val repoMapping: RepositoryMapping?
    private val expandedActionsMnemonic: String?
    private val implementation: StarlarkFunction
    private val semantics: StarlarkSemantics?
    private val symbolGenerator: SymbolGenerator<*>?

    init {
        val allInputsNsBuilder: NestedSetBuilder<Artifact?> =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
        val toolsNsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
        this.actionOwner = actionOwner
        this.inputDirectories =
            validateDictValues<SpecialArtifact?>(inputDirectories, INPUT_DIRECTORIES_KEY, DIRECTORY_CLASSES)
        addDictValuesToNestedSets(inputDirectories, INPUT_DIRECTORIES_KEY, allInputsNsBuilder)
        this.additionalInputs =
            validateDictValues<Any?>(additionalInputs, ADDITIONAL_INPUTS_KEY, ADDITIONAL_INPUTS_CLASSES)
        addDictValuesToNestedSets(additionalInputs, ADDITIONAL_INPUTS_KEY, allInputsNsBuilder)
        this.outputDirectories =
            validateDictValues<SpecialArtifact?>(outputDirectories, OUTPUT_DIRECTORIES_KEY, DIRECTORY_CLASSES)
        this.tools = validateDictValues<Any?>(tools, TOOLS_KEY, ADDITIONAL_INPUTS_CLASSES)
        addDictValuesToNestedSets(tools, TOOLS_KEY, allInputsNsBuilder, toolsNsBuilder)
        this.allInputs = allInputsNsBuilder.build()
        this.toolsNs = toolsNsBuilder.build()
        this.additionalParams = Dict.immutableCopyOf<String?, Any?>(additionalParams)
        this.spawnActionBuilder = spawnActionBuilder
        this.executionRequirements = executionRequirements
        this.outputPathsMode = outputPathsMode
        this.env = env
        this.repoMapping = repoMappingSupplier.get()
        this.expandedActionsMnemonic = expandedActionsMnemonic
        this.implementation = implementation
        this.semantics = semantics
        this.symbolGenerator = symbolGenerator
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun <V> validateDictValues(
        dict: Dict<String?, V?>,
        what: String?,
        allowedClasses: com.google.common.collect.ImmutableSet<java.lang.Class<*>>
    ): Dict<String?, V?> {
        for (entry in dict.entrySet()) {
            val keyedDebugString: String? = java.lang.String.format("%s['%s']", what, entry.getKey())
            var assignable = false
            for (allowedClass in allowedClasses) {
                if (allowedClass.isAssignableFrom(entry.getValue().getClass())) {
                    assignable = true
                    break
                }
            }
            if (!assignable) {
                throw Starlark.errorf(
                    "Expected one of %s; but got %s in %s.",
                    allowedClasses.stream()
                        .map<String?>(java.util.function.Function { c: java.lang.Class<*>? -> Starlark.classType(c) })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>()),
                    Starlark.type(entry.getValue()),
                    keyedDebugString
                )
            }
        }
        return Dict.immutableCopyOf<String?, V?>(dict)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun addDictValuesToNestedSets(
        dict: Dict<String?, *>, what: String?, vararg builders: NestedSetBuilder<Artifact?>
    ) {
        for (entry in dict.entrySet()) {
            val keyedDebugString: String? = java.lang.String.format("%s['%s']", what, entry.getKey())
            when (entry.getValue()) {
                -> {
                    for (builder in builders) {
                        builder.add(artifact)
                    }
                }

                -> {
                    for (builder in builders) {
                        builder.addTransitive(filesToRunProvider.getFilesToRun())
                    }
                }

                -> {
                    for (builder in builders) {
                        builder.addTransitive(Depset.cast(depset, Artifact::class.java, keyedDebugString))
                    }
                }

                else -> {
                    throw java.lang.IllegalStateException(
                        java.lang.String.format("Unexpected value %s in %s", entry.getValue(), what)
                    )
                }
            }
        }
    }

    @Throws(ActionConflictException::class, ActionExecutionException::class, java.lang.InterruptedException::class)
    public override fun generateActionsForInputArtifacts(
        inputTreeFileArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact?>?,
        artifactOwner: ActionLookupKey?,
        eventHandler: com.google.devtools.build.lib.events.EventHandler?
    ): com.google.common.collect.ImmutableList<AbstractAction> {
        val inputTreeArtifactsToChildren: com.google.common.collect.ImmutableListMultimap<SpecialArtifact?, TreeFileArtifact?> =
            ActionTemplate.getInputTreeArtifactsToChildren(inputTreeFileArtifacts)

        val context: StarlarkTemplateContext =
            StarlarkTemplateContext(
                semantics,
                actionOwner,
                artifactOwner,
                spawnActionBuilder,
                InterruptibleSupplier { repoMapping },
                com.google.common.collect.ImmutableSet.copyOf<SpecialArtifact?>(outputDirectories.values()),
                getExecutionInfo()
            )

        val expandedDirectories: com.google.common.collect.ImmutableMap.Builder<String?, ExpandedDirectory?> =
            com.google.common.collect.ImmutableMap.builder<String?, ExpandedDirectory?>()
        for (entry in inputDirectories.entrySet()) {
            val inputDirectory: SpecialArtifact? = entry.getValue()
            val children: com.google.common.collect.ImmutableList<TreeFileArtifact?> =
                inputTreeArtifactsToChildren.get(inputDirectory)
            expandedDirectories.put(entry.getKey(), ExpandedDirectory(inputDirectory, children))
        }

        try {
            Mutability.create("action template").use { mu ->
                val thread: StarlarkThread =
                    StarlarkThread.create(mu, semantics, "map_directory implementation", symbolGenerator)
                thread.setPrintHandler(com.google.devtools.build.lib.events.Event.makeDebugPrintHandler(eventHandler))
                val argumentProcessor: net.starlark.java.eval.StarlarkCallable.ArgumentProcessor =
                    Starlark.requestArgumentProcessor(thread, implementation)
                argumentProcessor.addPositionalArg(context)
                argumentProcessor.addNamedArg(
                    INPUT_DIRECTORIES_KEY,
                    Dict.immutableCopyOf<String?, ExpandedDirectory?>(expandedDirectories.buildOrThrow())
                )
                argumentProcessor.addNamedArg(OUTPUT_DIRECTORIES_KEY, outputDirectories)
                argumentProcessor.addNamedArg(ADDITIONAL_INPUTS_KEY, additionalInputs)
                argumentProcessor.addNamedArg(TOOLS_KEY, tools)
                argumentProcessor.addNamedArg(ADDITIONAL_PARAMS_KEY, additionalParams)

                val returnValue: Any? =
                    Starlark.callViaArgumentProcessor(thread, implementation, argumentProcessor)

                if (returnValue !== Starlark.NONE) {
                    throw Starlark.errorf(
                        "actions.map_directory() implementation %s at %s may not return a non-None value (got"
                                + " %s)",
                        implementation.getName(),
                        implementation.getLocation(),
                        Starlark.repr(returnValue, semantics)
                    )
                }

                val actions: com.google.common.collect.ImmutableList<AbstractAction> = context.getActions()
                checkActionOutputsArtifactOwner(actions, artifactOwner)
                return actions
            }
        } catch (e: net.starlark.java.eval.EvalException) {
            throw ActionExecutionException(
                e, this,  /* catastrophe= */true, makeDetailedExitCode(e.getMessage())
            )
        } finally {
            context.close()
        }
    }

    public override fun getExecProperties(): com.google.common.collect.ImmutableMap<String?, String?>? {
        return this.executionRequirements
    }

    @Throws(ActionConflictException::class)
    private fun checkActionOutputsArtifactOwner(
        actions: com.google.common.collect.ImmutableList<AbstractAction>, artifactOwner: ActionLookupKey?
    ) {
        // This partially checks for action conflicts whereby files declared outside of this
        // Starlark implementation call are set as outputs of actions created within the implementation.
        // Files declared within this implementation should have the `artifactOwner` as their artifact
        // owner, and if the artifact owner any output artifact is different, it means that is is
        // is already output by some other action outside of this implementation, and hence is an action
        // conflict. The other typical checks for action conflicts are handled in the
        // ActionTemplateExpansionFunction.
        for (action in actions) {
            for (output in action.getOutputs()) {
                if (!output.getArtifactOwner().equals(artifactOwner)) {
                    throw ActionConflictException.create(
                        output,
                        action,
                        java.lang.String.format(
                            ("%s has conflicting output '%s' that is an output of another action, thus causing"
                                    + " an action conflict. `template_ctx.run()` should only use outputs declared"
                                    + " by `template_ctx.declare_file()` within the same Starlark implementation"
                                    + " function."),
                            action.prettyPrint(), output.getExecPath()
                        ),  /* isPrefixConflict= */
                        false
                    )
                }
            }
        }
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    protected override fun computeKey(
        actionKeyContext: ActionKeyContext,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        // Already contains input_directories, additional_inputs and tools.
        actionKeyContext.addNestedSetToFingerprint(fp, allInputs)
        addMapToFingerprint(actionKeyContext, fp, outputDirectories)
        addMapToFingerprint(actionKeyContext, fp, additionalParams)
        fp.addStringMap(executionRequirements)
        fp.addString(getMnemonic())
        fp.addString(expandedActionsMnemonic)
        PathMappers.addToFingerprint(
            getMnemonic(),
            getExecutionInfo(),
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            actionKeyContext,
            outputPathsMode,
            fp
        )
        env.addTo(fp)
        fp.addString(implementation.getName())
        fp.addBytes(BazelModuleContext.of(implementation.getModule()).bzlTransitiveDigest())
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    private fun addMapToFingerprint(
        actionKeyContext: ActionKeyContext, fp: Fingerprint, dict: Dict<String?, *>
    ) {
        try {
            for (entry in dict.entrySet()) {
                fp.addString(entry.getKey())
                when (entry.getValue()) {
                    -> fp.addPath(artifact.getExecPath())
                    -> actionKeyContext.addNestedSetToFingerprint(
                        fp, Depset.cast(depset, Artifact::class.java, "unused")
                    )

                    -> fp.addBoolean(bool)
                    -> fp.addInt(starlarkInt.toIntUnchecked())
                    -> fp.addString(string)
                    else -> {
                        throw java.lang.IllegalStateException(
                            java.lang.String.format(
                                "Expected Artifact or Depset; but got %s in %s.",
                                Starlark.type(entry.getValue()), entry.getKey()
                            )
                        )
                    }
                }
            }
        } catch (e: net.starlark.java.eval.EvalException) {
            // This should never happen, and should be validated / thrown in StarlarkActionFactory.
            throw java.lang.IllegalStateException(e)
        }
    }

    public override fun prettyPrint(): String? {
        return java.lang.String.format(
            "StarlarkMapActionTemplate with output TreeArtifacts: %s", outputDirectories.values()
        )
    }

    public override fun getMnemonic(): String {
        return INTERNAL_MAP_ACTION_TEMPLATE_MNEMONIC
    }

    public override fun isShareable(): Boolean {
        return true
    }

    public override fun getTools(): NestedSet<Artifact?>? {
        return toolsNs
    }

    public override fun getSchedulingDependencies(): NestedSet<Artifact?> {
        return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    }

    public override fun getOwner(): ActionOwner? {
        return actionOwner
    }

    public override fun getOutputs(): com.google.common.collect.ImmutableSet<Artifact?> {
        return com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (outputDirectories.values())
    }

    public override fun getOriginalInputs(): NestedSet<Artifact?>? {
        return getInputs()
    }

    public override fun getMandatoryOutputs(): com.google.common.collect.ImmutableSet<Artifact?> {
        return com.google.common.collect.ImmutableSet.of<Artifact?>()
    }

    public override fun getMandatoryInputs(): NestedSet<Artifact?>? {
        return getInputs()
    }

    public override fun getInputs(): NestedSet<Artifact?>? {
        return allInputs
    }

    public override fun getInputTreeArtifacts(): com.google.common.collect.ImmutableList<SpecialArtifact?> {
        return com.google.common.collect.ImmutableList.copyOf<SpecialArtifact?>(inputDirectories.values())
    }

    public override fun getInputFilesForExtraAction(
        actionExecutionContext: ActionExecutionContext?
    ): NestedSet<Artifact?> {
        return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    }

    public override fun getClientEnvironmentVariables(): com.google.common.collect.ImmutableList<String?> {
        return com.google.common.collect.ImmutableList.of<String?>()
    }

    public override fun describe(): String? {
        return java.lang.String.format("Expanding %s into actions", getInputTreeArtifacts())
    }

    /**
     * Represents a directory that has been expanded at execution time.
     * 
     * 
     * This is used to access the files within this directory that were previously generated by
     * other actions.
     * 
     * 
     * Implements [FileApi] and delegates all calls to the underlying [ ].
     */
    class ExpandedDirectory(
        directory: SpecialArtifact,
        children: com.google.common.collect.ImmutableList<TreeFileArtifact?>?
    ) : ExpandedDirectoryApi {
        private val directory: SpecialArtifact
        private val children: StarlarkList<FileApi?>

        init {
            com.google.common.base.Preconditions.checkArgument(directory.isTreeArtifact())
            this.directory = directory
            this.children = StarlarkList.immutableCopyOf<T?>(children)
        }

        public override fun children(): StarlarkList<FileApi?> {
            return children
        }

        public override fun getDirectory(): SpecialArtifact {
            return directory
        }

        public override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
            printer.append("ExpandedDirectory(directory = ")
            directory.repr(printer, semantics)
            printer.append(", children = ")
            children.repr(printer, semantics)
            printer.append(")")
        }
    }

    companion object {
        private const val INTERNAL_MAP_ACTION_TEMPLATE_MNEMONIC = "StarlarkMapActionTemplate"

        const val INPUT_DIRECTORIES_KEY: String = "input_directories"
        const val ADDITIONAL_INPUTS_KEY: String = "additional_inputs"
        const val OUTPUT_DIRECTORIES_KEY: String = "output_directories"
        const val TOOLS_KEY: String = "tools"
        const val ADDITIONAL_PARAMS_KEY: String = "additional_params"

        // The allowed classes for values for the different keys.
        private val ADDITIONAL_INPUTS_CLASSES: com.google.common.collect.ImmutableSet<java.lang.Class<*>> =
            com.google.common.collect.ImmutableSet.of<java.lang.Class<*>?>(
                Artifact::class.java,
                FilesToRunProvider::class.java,
                Depset::class.java
            )
        private val DIRECTORY_CLASSES: com.google.common.collect.ImmutableSet<java.lang.Class<*>> =
            com.google.common.collect.ImmutableSet.of<java.lang.Class<*>?>(SpecialArtifact::class.java)

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
