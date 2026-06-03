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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata.mergeMaps

/** An Action representing an arbitrary subprocess to be forked and exec'd.  */
open class SpawnAction : AbstractAction, CommandAction {
    private val tools: NestedSet<Artifact?>?
    private val commandLines: CommandLines
    private val env: ActionEnvironment

    private val progressMessage: CharSequence?
    private val mnemonic: String?

    private val resourceSetOrBuilder: ResourceSetOrBuilder?

    @VisibleForSerialization // protected access required due to b/32473060
    protected val sortedExecutionInfo: com.google.common.collect.ImmutableSortedMap<String?, String?>
    private val outputPathsMode: OutputPathsMode?

    /**
     * Constructs a SpawnAction using direct initialization arguments.
     * 
     * 
     * All collections provided must not be subsequently modified.
     * 
     * @param owner the owner of the Action
     * @param tools the set of files comprising the tool that does the work (e.g. compiler). This is a
     * subset of "inputs" and is only used by the WorkerSpawnStrategy
     * @param inputs the set of all files potentially read by this action; must not be subsequently
     * modified
     * @param outputs the set of all files written by this action; must not be subsequently modified.
     * @param resourceSetOrBuilder the resources consumed by executing this Action.
     * @param env the action's environment
     * @param executionInfo out-of-band information for scheduling the spawn
     * @param commandLines the command lines to execute. This includes the main argv vector and any
     * param file-backed command lines.
     * @param progressMessage the message printed during the progression of the build
     * @param mnemonic the mnemonic that is reported in the master log
     */
    constructor(
        owner: ActionOwner?,
        tools: NestedSet<Artifact?>?,
        inputs: NestedSet<Artifact?>?,
        outputs: Iterable<out Artifact?>?,
        resourceSetOrBuilder: ResourceSetOrBuilder?,
        commandLines: CommandLines,
        env: ActionEnvironment,
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>,
        progressMessage: CharSequence?,
        mnemonic: String?,
        outputPathsMode: OutputPathsMode?
    ) : super(owner, inputs,  /* outputs= */outputs) {
        this.tools = tools
        this.resourceSetOrBuilder = resourceSetOrBuilder
        this.sortedExecutionInfo =
            if (executionInfo.isEmpty())
                com.google.common.collect.ImmutableSortedMap.of<String?, String?>()
            else
                executionInfoInterner.intern(
                    com.google.common.collect.ImmutableSortedMap.copyOf<String?, String?>(
                        executionInfo
                    )
                )
        this.commandLines = commandLines
        this.env = env
        this.progressMessage = progressMessage
        this.mnemonic = mnemonic
        this.outputPathsMode = outputPathsMode
    }

    /** Constructor for serialization.  */
    // Follows the production constructor.
    constructor(
        owner: ActionOwner?,
        tools: NestedSet<Artifact?>?,
        inputs: NestedSet<Artifact?>?,
        rawOutputs: Any?,
        resourceSetOrBuilder: ResourceSetOrBuilder?,
        commandLines: CommandLines,
        env: ActionEnvironment,
        sortedExecutionInfo: com.google.common.collect.ImmutableSortedMap<String?, String?>,
        progressMessage: CharSequence?,
        mnemonic: String?,
        outputPathsMode: OutputPathsMode?
    ) : super(owner, inputs,  /* rawOutputs= */rawOutputs) {
        this.tools = tools
        this.resourceSetOrBuilder = resourceSetOrBuilder
        this.sortedExecutionInfo =
            if (sortedExecutionInfo.isEmpty())
                com.google.common.collect.ImmutableSortedMap.of<String?, String?>()
            else
                executionInfoInterner.intern(sortedExecutionInfo)
        this.commandLines = commandLines
        this.env = env
        this.progressMessage = progressMessage
        this.mnemonic = mnemonic
        this.outputPathsMode = outputPathsMode
    }

    public override fun getTools(): NestedSet<Artifact?>? {
        return tools
    }

    public override fun getEnvironment(): ActionEnvironment {
        return env
    }

    @com.google.common.annotations.VisibleForTesting
    fun getCommandLines(): CommandLines {
        return commandLines
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    public override fun getArguments(): MutableList<String?> {
        return commandLines.allArguments(
            PathMappers.create(this, outputPathsMode, this is StarlarkAction)
        )
    }

    public override fun getStarlarkArgs(): net.starlark.java.eval.Sequence<CommandLineArgsApi?>? {
        val result: com.google.common.collect.ImmutableList.Builder<CommandLineArgsApi?> =
            com.google.common.collect.ImmutableList.builder<CommandLineArgsApi?>()
        val directoryInputs: com.google.common.collect.ImmutableSet<Artifact?>? =
            getInputs().toList().stream().filter(Artifact::isDirectory)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())

        for (commandLine in commandLines.unpack()) {
            result.add(Args.forRegisteredAction(commandLine, directoryInputs))
        }
        return StarlarkList.immutableCopyOf<CommandLineArgsApi?>(result.build())
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    public override fun getStarlarkArgv(): net.starlark.java.eval.Sequence<String?>? {
        try {
            return StarlarkList.immutableCopyOf<String?>(getArguments())
        } catch (ex: CommandLineExpansionException) {
            throw net.starlark.java.eval.EvalException(ex)
        }
    }

    @com.google.common.annotations.VisibleForTesting
    public override fun getPossibleInputsForTesting(): NestedSet<Artifact?> {
        return getInputs()
    }

    /** Returns command argument, argv[0].  */
    @com.google.common.annotations.VisibleForTesting
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    fun getCommandFilename(): String? {
        return com.google.common.collect.Iterables.getFirst<String?>(getArguments(), null)
    }

    /** Returns the (immutable) list of arguments, excluding the command name, argv[0].  */
    @com.google.common.annotations.VisibleForTesting
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    fun getRemainingArguments(): MutableList<String?> {
        return com.google.common.collect.ImmutableList.copyOf<String?>(
            com.google.common.collect.Iterables.skip<String?>(
                getArguments(),
                1
            )
        )
    }

    public override fun isVolatile(): Boolean {
        return executeUnconditionally()
    }

    /** Hook for subclasses to perform work before the spawn is executed.  */
    @Throws(ExecException::class)
    protected fun beforeExecute(actionExecutionContext: ActionExecutionContext?) {
    }

    /**
     * Hook for subclasses to perform work after the spawn is executed. This method is only executed
     * if the subprocess execution returns normally, not in case of errors (non-zero exit,
     * setup/network failures, etc.).
     */
    @com.google.errorprone.annotations.ForOverride
    @Throws(ExecException::class, java.lang.InterruptedException::class)
    protected open fun afterExecute(
        actionExecutionContext: ActionExecutionContext?,
        spawnResults: MutableList<SpawnResult?>?,
        pathMapper: PathMapper?
    ) {
    }

    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    public override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
        try {
            beforeExecute(actionExecutionContext)
            val spawn: Spawn = getSpawn(actionExecutionContext)
            val result: com.google.common.collect.ImmutableList<SpawnResult?>? =
                actionExecutionContext
                    .getContext(SpawnStrategyResolver::class.java)
                    .exec(spawn, actionExecutionContext)
            afterExecute(actionExecutionContext, result, spawn.getPathMapper())
            return ActionResult.create(result)
        } catch (e: CommandLineExpansionException) {
            throw createCommandLineException(e)
        } catch (e: ExecException) {
            if (e is SpawnExecException) {
                throw e.toActionExecutionException(this)
            }
            throw ActionExecutionException.fromExecException(e, this)
        }
    }

    private fun createCommandLineException(e: CommandLineExpansionException): ActionExecutionException {
        val detailedExitCode: DetailedExitCode? =
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(com.google.common.base.Strings.nullToEmpty(e.getMessage()))
                    .setSpawn(
                        FailureDetails.Spawn.newBuilder().setCode(Code.COMMAND_LINE_EXPANSION_FAILURE)
                    )
                    .build()
            )
        return ActionExecutionException(e, this,  /*catastrophe=*/false, detailedExitCode)
    }

    @com.google.common.annotations.VisibleForTesting
    fun getResourceSetOrBuilder(): ResourceSetOrBuilder? {
        return resourceSetOrBuilder
    }

    /**
     * Returns a Spawn that is representative of the command that this Action will execute. This
     * function must not modify any state.
     * 
     * 
     * This method is final, as it is merely a shorthand use of the generic way to obtain a spawn,
     * which also depends on the client environment. Subclasses that wish to override the way to get a
     * spawn should override getSpawn() instead.
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    fun getSpawnForTesting(): Spawn {
        return getSpawnForExtraActionSpawnInfo(getInputs())
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    open fun getSpawnForExtraActionSpawnInfo(): Spawn {
        return getSpawnForExtraActionSpawnInfo(getInputs())
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    fun getSpawnForExtraActionSpawnInfo(inputs: NestedSet<Artifact?>?): Spawn {
        return ActionSpawn(
            commandLines.allArguments(),
            this,  /* env= */
            com.google.common.collect.ImmutableMap.of<String?, String?>(),  /* envResolved= */
            false,
            inputs,  // SpawnInfo doesn't report the runfiles trees of the Spawn, so it's fine to just pass in
            // an empty list here.
            /* additionalInputs= */
            com.google.common.collect.ImmutableList.of<ActionInput?>(),  /* reportOutputs= */
            true,
            PathMapper.NOOP
        )
    }

    /**
     * Returns a spawn that is representative of the command that this Action will execute in the
     * given client environment.
     */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    open fun getSpawn(actionExecutionContext: ActionExecutionContext): Spawn {
        return getSpawn(
            actionExecutionContext,
            actionExecutionContext.getClientEnv(),  /* envResolved= */
            false,  /* reportOutputs= */
            true
        )
    }

    /**
     * Return a spawn that is representative of the command that this Action will execute in the given
     * environment.
     * 
     * @param envResolved If set to true, the passed environment variables will be used as the Spawn
     * effective environment. Otherwise they will be used as client environment to resolve the
     * action env.
     */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    protected fun getSpawn(
        actionExecutionContext: ActionExecutionContext,
        env: MutableMap<String?, String?>,
        envResolved: Boolean,
        reportOutputs: Boolean
    ): Spawn {
        val pathMapper: PathMapper? =
            PathMappers.create(this, outputPathsMode, this is StarlarkAction)
        val expandedCommandLines: ExpandedCommandLines =
            commandLines.expand(
                actionExecutionContext.getInputMetadataProvider(),
                getPrimaryOutput().getExecPath(),
                pathMapper,
                getCommandLineLimits()
            )

        return ActionSpawn(
            expandedCommandLines.arguments(),
            this,
            env,
            envResolved,
            getInputs(),
            expandedCommandLines.paramFiles,
            reportOutputs,
            pathMapper
        )
    }

    @com.google.errorprone.annotations.ForOverride
    protected open fun getCommandLineLimits(): CommandLineLimits {
        return getOwner().getBuildConfigurationInfo().getCommandLineLimits()
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    public override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addString(GUID)
        commandLines.addToFingerprint(
            actionKeyContext,
            inputMetadataProvider,
            PathMappers.getEffectiveOutputPathsMode(outputPathsMode, getMnemonic(), getExecutionInfo()),
            fp
        )
        fp.addString(mnemonic)
        env.addTo(fp)
        fp.addStringMap(getExecutionInfo())
        PathMappers.addToFingerprint(
            getMnemonic(),
            getExecutionInfo(),
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            actionKeyContext,
            outputPathsMode,
            fp
        )
    }

    public override fun describeKey(): String {
        val message: java.lang.StringBuilder = java.lang.StringBuilder()
        message.append(getProgressMessage())
        message.append('\n')
        for (entry in env.getFixedEnv().entrySet()) {
            message.append("  Environment variable: ")
            message.append(ShellEscaper.escapeString(entry.getKey()))
            message.append('=')
            message.append(ShellEscaper.escapeString(entry.getValue()))
            message.append('\n')
        }
        for (`var` in getClientEnvironmentVariables()) {
            message.append("  Environment variables taken from the client environment: ")
            message.append(ShellEscaper.escapeString(`var`))
            message.append('\n')
        }
        for (entry in getExecutionInfo().entrySet()) {
            message.append("  Execution info: ")
            message.append(entry.getKey()).append('=').append(entry.getValue())
            message.append('\n')
        }
        try {
            for (argument in ShellEscaper.escapeAll(getArguments())) {
                message.append("  Argument: ")
                message.append(argument)
                message.append('\n')
            }
        } catch (ex: java.lang.InterruptedException) {
            java.lang.Thread.currentThread().interrupt()
            message.append("Interrupted while expanding command line\n")
        } catch (e: CommandLineExpansionException) {
            message.append("Could not expand command line: ")
            message.append(e)
            message.append('\n')
        }
        message.append("  Output paths mode: ")
        message.append(outputPathsMode)
        message.append('\n')
        return message.toString()
    }

    public override fun getMnemonic(): String? {
        return mnemonic
    }

    protected override fun getRawProgressMessage(): String? {
        if (progressMessage != null) {
            return progressMessage.toString()
        }
        return super.getRawProgressMessage()
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    public override fun getExtraActionInfo(actionKeyContext: ActionKeyContext?): ExtraActionInfo.Builder {
        return super.getExtraActionInfo(actionKeyContext)
            .setExtension(SpawnInfo.spawnInfo, getExtraActionSpawnInfo())
    }

    /**
     * Returns information about this spawn action for use by the extra action mechanism.
     * 
     * 
     * Subclasses of SpawnAction may override this in order to provide action-specific behaviour.
     * This can be necessary, for example, when the action discovers inputs.
     */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    private fun getExtraActionSpawnInfo(): SpawnInfo {
        val info: SpawnInfo.Builder = SpawnInfo.newBuilder()
        val spawn: Spawn = getSpawnForExtraActionSpawnInfo()
        info.addAllArgument(spawn.getArguments())
        for (variable in spawn.getEnvironment().entrySet()) {
            info.addVariable(
                EnvironmentVariable.newBuilder()
                    .setName(variable.getKey())
                    .setValue(variable.getValue())
                    .build()
            )
        }
        for (input in spawn.getInputFiles().toList()) {
            // Explicitly ignore runfiles tree artifacts here.
            if (input !is Artifact || !(input as Artifact).isRunfilesTree()) {
                info.addInputFile(input.getExecPathString())
            }
        }
        info.addAllOutputFile(ActionInputHelper.toExecPaths(spawn.getOutputFiles()))
        return info.build()
    }

    @com.google.common.annotations.VisibleForTesting
    public override fun getIncompleteEnvironmentForTesting(): com.google.common.collect.ImmutableMap<String?, String?> {
        // TODO(ulfjack): AbstractAction should declare getEnvironment with a return value of type
        // ActionEnvironment to avoid developers misunderstanding the purpose of this method. That
        // requires first updating all subclasses and callers to actually handle environments correctly,
        // so it's not a small change.
        return getEnvironment().getFixedEnv()
    }

    /** Returns the out-of-band execution data for this action.  */
    public override fun getExecutionInfo(): com.google.common.collect.ImmutableMap<String?, String?> {
        return mergeMaps(super.getExecutionInfo(), sortedExecutionInfo)
    }

    /** A spawn instance that is tied to a specific SpawnAction.  */
    private class ActionSpawn(
        arguments: com.google.common.collect.ImmutableList<String?>?,
        parent: SpawnAction,
        env: MutableMap<String?, String?>,
        envResolved: Boolean,
        inputs: NestedSet<Artifact?>?,
        additionalInputs: Iterable<out ActionInput?>?,
        reportOutputs: Boolean,
        pathMapper: PathMapper?
    ) : BaseSpawn(
        arguments,
        com.google.common.collect.ImmutableMap.of<K?, V?>(),
        parent.getExecutionInfo(),
        parent,
        parent.resourceSetOrBuilder
    ) {
        private val inputs: NestedSet<ActionInput?>?
        private val effectiveEnvironment: com.google.common.collect.ImmutableMap<String?, String?>?
        private val reportOutputs: Boolean
        private val pathMapper: PathMapper?

        /**
         * Creates an ActionSpawn with the given environment variables.
         * 
         * 
         * Subclasses of ActionSpawn may subclass in order to provide action-specific values for
         * environment variables or action inputs.
         */
        init {
            this.inputs =
                NestedSetBuilder.< ActionInput > stableOrder < ActionInput ? > ()
                    .addTransitive(inputs)
                    .addAll(additionalInputs)
                    .build()
            this.pathMapper = pathMapper

            // If the action environment is already resolved using the client environment, the given
            // environment variables are used as they are. Otherwise, they are used as clientEnv to
            // resolve the action environment variables.
            if (envResolved) {
                effectiveEnvironment = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(env)
            } else {
                effectiveEnvironment = parent.getEffectiveEnvironment(env)
            }
            this.reportOutputs = reportOutputs
        }

        public override fun getPathMapper(): PathMapper? {
            return pathMapper
        }

        public override fun getEnvironment(): com.google.common.collect.ImmutableMap<String?, String?>? {
            return effectiveEnvironment
        }

        public override fun getInputFiles(): NestedSet<out ActionInput?>? {
            return inputs
        }

        public override fun getOutputFiles(): MutableCollection<out ActionInput?>? {
            return if (reportOutputs) super.getOutputFiles() else com.google.common.collect.ImmutableSet.of<ActionInput?>()
        }
    }

    /**
     * Builder class to construct [SpawnAction] instances.
     */
    open class Builder {
        private val toolsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        private val inputsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        private val outputs: MutableList<Artifact?> = java.util.ArrayList<Artifact?>()
        private var resourceSetOrBuilder: ResourceSetOrBuilder? = AbstractAction.DEFAULT_RESOURCE_SET
        private var environment: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>()
        private var actionEnvironment: ActionEnvironment? = null
        private var outputPathsMode: OutputPathsMode? = null
        private var executionInfo: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>()
        private var useDefaultShellEnvironment = false
        protected var executeUnconditionally: Boolean = false
        private var executableArg: Any? = null
        private var executableArgs: com.google.devtools.build.lib.analysis.actions.CustomCommandLine.Builder? = null
        private var commandLines: MutableList<CommandLineAndParamFileInfo> =
            java.util.ArrayList<CommandLineAndParamFileInfo>()

        private var progressMessage: CharSequence? = null
        private var mnemonic: String? = "Unknown"
        private var execGroup: String? = DEFAULT_EXEC_GROUP_NAME

        /** Creates a SpawnAction builder.  */
        constructor()

        /** Creates a builder that is a copy of another builder.  */
        constructor(other: Builder) {
            this.toolsBuilder.addTransitive(other.toolsBuilder.build())
            this.inputsBuilder.addTransitive(other.inputsBuilder.build())
            this.outputs.addAll(other.outputs)
            this.resourceSetOrBuilder = other.resourceSetOrBuilder
            this.environment = other.environment
            this.actionEnvironment = other.actionEnvironment
            this.outputPathsMode = other.outputPathsMode
            this.executionInfo = other.executionInfo
            this.useDefaultShellEnvironment = other.useDefaultShellEnvironment
            this.executableArg = other.executableArg
            this.executableArgs = other.executableArgs
            this.commandLines = java.util.ArrayList<CommandLineAndParamFileInfo>(other.commandLines)
            this.progressMessage = other.progressMessage
            this.mnemonic = other.mnemonic
        }

        /**
         * Builds the SpawnAction using the passed-in action configuration.
         * 
         * 
         * This method makes a copy of all the collections, so it is safe to reuse the builder after
         * this method returns.
         * 
         * 
         * This is annotated with @CheckReturnValue, which causes a compiler error when you call this
         * method and ignore its return value. This is because some time ago, calling .build() had the
         * side-effect of registering it with the RuleContext that was passed in to the constructor.
         * This logic was removed, but if people don't notice and still rely on the side-effect, things
         * may break.
         * 
         * @return the SpawnAction.
         */
        @com.google.errorprone.annotations.CheckReturnValue
        fun build(context: ActionConstructionContext): SpawnAction? {
            return build(context.getActionOwner(execGroup), context.getConfiguration())
        }

        @com.google.common.annotations.VisibleForTesting
        @com.google.errorprone.annotations.CheckReturnValue
        fun build(owner: ActionOwner?, configuration: BuildConfigurationValue): SpawnAction? {
            val result: CommandLines.Builder = CommandLines.builder()
            if (executableArg != null) {
                result.addSingleArgument(executableArg)
            } else if (executableArgs != null) {
                result.addCommandLine(executableArgs.build())
            }
            for (pair in this.commandLines) {
                result.addCommandLine(pair)
            }
            val commandLines: CommandLines = result.build()
            val env: ActionEnvironment =
                createActionEnvironment(configuration, useDefaultShellEnvironment, environment)
            return buildSpawnAction(owner, commandLines, configuration, env)
        }

        @com.google.errorprone.annotations.CheckReturnValue
        fun buildForActionTemplate(owner: ActionOwner?): SpawnAction? {
            return buildSpawnAction(
                owner, buildCommandLines(), null, ActionEnvironment.create(environment)
            )
        }

        @com.google.errorprone.annotations.CheckReturnValue
        fun buildForStarlarkActionTemplate(owner: ActionOwner?): SpawnAction {
            val tools: NestedSet<Artifact?>? = toolsBuilder.build()
            val inputs: NestedSet<Artifact?>? = inputsBuilder.addTransitive(tools).build()
            return SpawnAction(
                owner,
                tools,
                inputs,
                outputs,
                resourceSetOrBuilder,
                buildCommandLines(),
                com.google.common.base.Preconditions.checkNotNull<ActionEnvironment?>(actionEnvironment),
                executionInfo,
                progressMessage,
                mnemonic,
                com.google.common.base.Preconditions.checkNotNull<OutputPathsMode?>(outputPathsMode)
            )
        }

        private fun buildCommandLines(): CommandLines {
            val result: CommandLines.Builder = CommandLines.builder()
            if (executableArg != null) {
                result.addSingleArgument(executableArg)
            } else {
                result.addCommandLine(executableArgs.build())
            }
            for (pair in commandLines) {
                result.addCommandLine(pair.commandLine)
            }
            return result.build()
        }

        /**
         * Builds the SpawnAction using the passed-in action configuration.
         * 
         * 
         * This method makes a copy of all the collections, so it is safe to reuse the builder after
         * this method returns.
         */
        private fun buildSpawnAction(
            owner: ActionOwner?,
            commandLines: CommandLines,
            configuration: BuildConfigurationValue?,
            env: ActionEnvironment
        ): SpawnAction? {
            val tools: NestedSet<Artifact?>? = toolsBuilder.build()

            // Build inputsAndTools while reusing the built set of tools.
            val inputsAndTools: NestedSet<Artifact?>? =
                NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                    .addTransitive(inputsBuilder.build())
                    .addTransitive(tools)
                    .build()

            return createSpawnAction(
                owner,
                tools,
                inputsAndTools,
                com.google.common.collect.ImmutableSet.copyOf<Artifact?>(outputs),
                resourceSetOrBuilder,
                commandLines,
                env,
                configuration,
                if (configuration == null)
                    executionInfo
                else
                    configuration.modifiedExecutionInfo(executionInfo, mnemonic),
                progressMessage,
                mnemonic
            )
        }

        /** Creates a SpawnAction.  */
        protected open fun createSpawnAction(
            owner: ActionOwner?,
            tools: NestedSet<Artifact?>?,
            inputsAndTools: NestedSet<Artifact?>?,
            outputs: com.google.common.collect.ImmutableSet<Artifact?>?,
            resourceSetOrBuilder: ResourceSetOrBuilder?,
            commandLines: CommandLines,
            env: ActionEnvironment,
            configuration: BuildConfigurationValue?,
            executionInfo: com.google.common.collect.ImmutableMap<String?, String?>,
            progressMessage: CharSequence?,
            mnemonic: String?
        ): SpawnAction? {
            return SpawnAction(
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
                PathMappers.getOutputPathsMode(configuration)
            )
        }

        /**
         * Adds an artifact that is necessary for executing the spawn itself (e.g. a compiler), in
         * contrast to an artifact that is necessary for the spawn to do its work (e.g. source code).
         * 
         * 
         * The artifact is implicitly added to the inputs of the action as well.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTool(tool: Artifact?): Builder {
            toolsBuilder.add(tool)
            return this
        }

        /**
         * Adds an executable and its runfiles, which is necessary for executing the spawn itself (e.g.
         * a compiler), in contrast to artifacts that are necessary for the spawn to do its work (e.g.
         * source code).
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTool(tool: FilesToRunProvider): Builder {
            addTransitiveTools(tool.getFilesToRun())
            return this
        }

        /** Adds an input to this action.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addInput(artifact: Artifact?): Builder {
            inputsBuilder.add(artifact)
            return this
        }

        /** Adds tools to this action.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTools(artifacts: Iterable<Artifact?>?): Builder {
            toolsBuilder.addAll(artifacts)
            return this
        }

        /** Adds tools to this action.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTransitiveTools(artifacts: NestedSet<Artifact?>?): Builder {
            toolsBuilder.addTransitive(artifacts)
            return this
        }

        /** Adds inputs to this action.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addInputs(artifacts: Iterable<Artifact?>?): Builder {
            inputsBuilder.addAll(artifacts)
            return this
        }


        /** Adds transitive inputs to this action.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTransitiveInputs(artifacts: NestedSet<Artifact?>?): Builder {
            inputsBuilder.addTransitive(artifacts)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addOutput(artifact: Artifact?): Builder {
            outputs.add(artifact)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addOutputs(artifacts: Iterable<Artifact?>): Builder {
            com.google.common.collect.Iterables.addAll<Artifact?>(outputs, artifacts)
            return this
        }

        /**
         * Sets RecourceSet for builder. If ResourceSetBuilder set, then ResourceSetBuilder will
         * override setResources.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setResources(resourceSetOrBuilder: ResourceSetOrBuilder?): Builder {
            this.resourceSetOrBuilder = resourceSetOrBuilder
            return this
        }

        /**
         * Sets the map of environment variables. Do not use! This makes the builder ignore the 'default
         * shell environment', which is computed from the --action_env command line option.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setEnvironment(environment: MutableMap<String?, String?>): Builder {
            this.environment =
                com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder.Companion.envInterner.intern(
                    com.google.common.collect.ImmutableMap.copyOf<String?, String?>(environment)
                )
            this.useDefaultShellEnvironment = false
            return this
        }

        /**
         * Sets the action environment, for when we want to already have created an ActionEnvironment
         * before .build() is called. This is used for Starlark action templates
         * .buildForStarlarkActionTemplate(), where we do not have access to the configuration upon
         * action creation time which is required to create the ActionEnvironment.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setActionEnvironment(actionEnvironment: ActionEnvironment?): Builder {
            this.actionEnvironment = actionEnvironment
            return this
        }

        /**
         * Sets the output paths mode, for when we want to already have it before .build() is called.
         * This is used for Starlark action templates .buildForStarlarkActionTemplate(), where we do not
         * have access to the configuration upon action creation time which is required to create the
         * ActionEnvironment.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setOutputPathsMode(outputPathsMode: OutputPathsMode?): Builder {
            this.outputPathsMode = outputPathsMode
            return this
        }

        /** Sets the map of execution info.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutionInfo(info: MutableMap<String?, String?>): Builder {
            this.executionInfo = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(info)
            return this
        }

        /**
         * Sets the environment to the configuration's default shell environment.
         * 
         * 
         * **All actions should set this if possible and avoid using [.setEnvironment].**
         * 
         * 
         * When this property is set, the action will use a minimal, standardized environment map,
         * overridden with the specified environment variables (if any).
         * 
         * 
         * The list of envvars available to the action (the keys in this map) comes from two places:
         * from the rule class provider and from the command line or rc-files via `--action_env`
         * flags.
         * 
         * 
         * The values for these variables may come from one of three places: from the configuration
         * fragment, or from the `--action_env` flag (when the flag specifies a name-value pair,
         * e.g. `--action_env=FOO=bar`), or from the client environment (when the flag only
         * specifies a name, e.g. `--action_env=HOME`).
         * 
         * 
         * The client environment is specified by the `--client_env` flags. The Bazel client
         * passes these flags to the Bazel server upon each build (e.g. `--client_env=HOME=/home/johndoe`), so the server can keep track of environmental changes
         * between builds, and always use the up-to-date environment (as opposed to calling `System.getenv`, which it should never do, though as of 2017-08-02 it still does in a few
         * places).
         * 
         * 
         * The `--action_env` has priority over configuration-fragment-dictated envvar values,
         * i.e. if the configuration fragment tries to add FOO=bar to the environment, and there's also
         * `--action_env=FOO=baz` or `--action_env=FOO`, then FOO will be available to the
         * action and its value will be "baz", or whatever the corresponding `--client_env` flag
         * specified, respectively.
         * 
         * @see BuildConfigurationValue.getLocalShellEnvironment
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun useDefaultShellEnvironment(environment: MutableMap<String?, String?>): Builder {
            this.environment = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(environment)
            this.useDefaultShellEnvironment = true
            return this
        }

        /**
         * Sets the executable path; the path is interpreted relative to the execution root, unless it's
         * a bare file name.
         * 
         * 
         * **Caution**: if the executable is a bare file name ("foo"), it will be interpreted
         * relative to PATH. See https://github.com/bazelbuild/bazel/issues/13189 for details. To avoid
         * that, use [.setExecutable] instead.
         * 
         * 
         * Calling this method overrides any previous values set via calls to [.setExecutable]
         * or [.setShellCommand].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutable(executable: PathFragment?): Builder {
            this.executableArg = executable
            this.executableArgs = null
            return this
        }

        /**
         * Sets the executable as an artifact.
         * 
         * 
         * Calling this method overrides any previous values set via calls to [.setExecutable]
         * or [.setShellCommand].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutable(executable: Artifact): Builder {
            addTool(executable)
            this.executableArg = ensureCallable(executable)
            this.executableArgs = null
            return this
        }

        /**
         * Sets the executable as a configured target. Automatically adds the files to run to the tools
         * and inputs and uses the executable of the target as the executable.
         * 
         * 
         * Calling this method overrides any previous values set via calls to [.setExecutable]
         * or [.setShellCommand].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutable(executable: TransitiveInfoCollection): Builder {
            val provider: FilesToRunProvider =
                com.google.common.base.Preconditions.checkNotNull<T>(executable.getProvider(FilesToRunProvider::class.java))
            return setExecutable(provider)
        }

        /**
         * Sets the executable as a configured target. Automatically adds the files to run to the tools
         * and inputs and uses the executable of the target as the executable.
         * 
         * 
         * Calling this method overrides any previous values set via calls to [.setExecutable]
         * or [.setShellCommand].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutable(executableProvider: FilesToRunProvider): Builder {
            val executable: Artifact =
                com.google.common.base.Preconditions.checkNotNull<Artifact>(
                    executableProvider.getExecutable(), "The target does not have an executable"
                )
            this.executableArg = ensureCallable(executable)
            this.executableArgs = null
            return addTool(executableProvider)
        }

        /**
         * Sets the executable as a String.
         * 
         * 
         * **Caution**: this is an optimisation intended to be used only by [ ]. It prevents reference
         * duplication when passing [PathFragment] to Starlark as a String and then executing with
         * it.
         * 
         * 
         * Calling this method overrides any previous values set via calls to [.setExecutable]
         * or [.setShellCommand].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutableAsString(executable: String?): Builder {
            this.executableArg = executable
            this.executableArgs = null
            return this
        }

        /**
         * Sets the executable to be a jar executed from the given deploy jar. The deploy jar is
         * automatically added to the action inputs.
         * 
         * 
         * Assumes that the Jar artifact declares a main class.
         * 
         * 
         * Calling this method overrides any previous values set via calls to [.setExecutable]
         * or [.setShellCommand].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setJarExecutable(
            javaExecutable: PathFragment?, deployJar: Artifact?, jvmArgs: NestedSet<String?>?
        ): Builder {
            this.executableArgs =
                CustomCommandLine.Companion.builder()
                    .addPath(javaExecutable)
                    .addAll(jvmArgs)
                    .add("-jar")
                    .addExecPath(deployJar)
            this.executableArg = null
            toolsBuilder.add(deployJar)
            return this
        }

        /**
         * Sets the executable to be the shell and adds the given command as the command to be executed.
         * 
         * 
         * Note that this will not clear the arguments, so any arguments will be passed in addition
         * to the command given here.
         * 
         * 
         * Calling this method overrides any previous values set via calls to [.setExecutable]
         * or [.setShellCommand].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setShellCommand(shExecutable: PathFragment?, command: String?, pad: Boolean): Builder {
            this.executableArg = ShellCommand(shExecutable, command, pad)
            this.executableArgs = null
            return this
        }

        /**
         * Sets the executable to be the shell and adds the given interned commands as the commands to
         * be executed.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setShellCommand(command: Iterable<String?>, pad: Boolean): Builder {
            this.executableArgs = CustomCommandLine.Companion.builder()
                .addAll(com.google.common.collect.ImmutableList.copyOf<String?>(command))
            if (pad) {
                executableArgs.add("")
            }
            this.executableArg = null
            return this
        }

        /** Appends the arguments to the list of executable arguments.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecutableArguments(vararg arguments: String?): Builder {
            if (executableArg != null) {
                executableArgs = CustomCommandLine.Companion.builder().addObject(executableArg)
                executableArg = null
            }
            executableArgs.addAll(com.google.common.collect.ImmutableList.copyOf<String?>(arguments))
            return this
        }

        /**
         * Adds a delegate to compute the command line at a later time.
         * 
         * 
         * The arguments are added after the executable arguments. If you add multiple command lines,
         * they are expanded in the corresponding order.
         * 
         * 
         * The main intention of this method is to save memory by allowing client-controlled sharing
         * between actions and configured targets. Objects passed to this method MUST be immutable.
         * 
         * 
         * See also [CustomCommandLine].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addCommandLine(commandLine: CommandLine?): Builder {
            this.commandLines.add(CommandLineAndParamFileInfo(commandLine, null))
            return this
        }

        /**
         * Adds a delegate to compute the command line at a later time, optionally spilled to a params
         * file.
         * 
         * 
         * The arguments are added after the executable arguments. If you add multiple command lines,
         * they are expanded in the corresponding order. If the command line is spilled to a params
         * file, it is replaced with an argument pointing to the param file.
         * 
         * 
         * The main intention of this method is to save memory by allowing client-controlled sharing
         * between actions and configured targets. Objects passed to this method MUST be immutable.
         * 
         * 
         * See also [CustomCommandLine].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addCommandLine(commandLine: CommandLine?, paramFileInfo: ParamFileInfo?): Builder {
            this.commandLines.add(CommandLineAndParamFileInfo(commandLine, paramFileInfo))
            return this
        }

        /**
         * Sets the progress message.
         * 
         * 
         * The message may contain `%{label}`, `%{input}` or `%{output}
        ` *  patterns, which are substituted with label string, first input or output's path,
         * respectively.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setProgressMessage(@com.google.errorprone.annotations.CompileTimeConstant progressMessage: String?): Builder {
            this.progressMessage = progressMessage
            return this
        }

        /**
         * Sets the progress message. The string is lazily evaluated.
         * 
         * @param progressMessage The message to display
         * @param subject Passed to [String.format]
         */
        @com.google.errorprone.annotations.FormatMethod
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Deprecated("Use {@link #setProgressMessage(String)} with provided patterns.")
        fun setProgressMessage(
            @com.google.errorprone.annotations.FormatString progressMessage: String,
            subject: Any?
        ): Builder? {
            return setProgressMessage(
                object : OnDemandString() {
                    public override fun toString(): String? {
                        return java.lang.String.format(progressMessage, subject)
                    }
                })
        }

        /**
         * Sets the progress message. The string is lazily evaluated.
         * 
         * @param progressMessage The message to display
         * @param subject0 Passed to [String.format]
         * @param subject1 Passed to [String.format]
         */
        @com.google.errorprone.annotations.FormatMethod
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Deprecated("Use {@link #setProgressMessage(String)} with provided patterns.")
        fun setProgressMessage(
            @com.google.errorprone.annotations.FormatString progressMessage: String, subject0: Any?, subject1: Any?
        ): Builder? {
            return setProgressMessage(
                object : OnDemandString() {
                    public override fun toString(): String? {
                        return java.lang.String.format(progressMessage, subject0, subject1)
                    }
                })
        }

        /**
         * Sets the progress message. The string is lazily evaluated.
         * 
         * @param progressMessage The message to display
         * @param subject0 Passed to [String.format]
         * @param subject1 Passed to [String.format]
         * @param subject2 Passed to [String.format]
         */
        @com.google.errorprone.annotations.FormatMethod
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Deprecated("Use {@link #setProgressMessage(String)} with provided patterns.")
        fun setProgressMessage(
            @com.google.errorprone.annotations.FormatString progressMessage: String,
            subject0: Any?,
            subject1: Any?,
            subject2: Any?
        ): Builder? {
            return setProgressMessage(
                object : OnDemandString() {
                    public override fun toString(): String? {
                        return java.lang.String.format(progressMessage, subject0, subject1, subject2)
                    }
                })
        }

        /**
         * Sets a lazily computed progress message.
         * 
         * 
         * When possible, prefer use of one of the overloads that use [String.format]. If you
         * do use this overload, take care not to capture anything expensive.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        private fun setProgressMessage(progressMessage: OnDemandString?): Builder {
            this.progressMessage = progressMessage
            return this
        }

        /**
         * Sets the progress message.
         * 
         * 
         * Same as [.setProgressMessage], except that it may be used with non compile
         * time constants (needed for Starlark literals).
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setProgressMessageFromStarlark(progressMessage: String?): Builder {
            this.progressMessage = progressMessage
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setMnemonic(mnemonic: String): Builder {
            com.google.common.base.Preconditions.checkArgument(
                !mnemonic.isEmpty() && com.google.common.base.CharMatcher.javaLetterOrDigit().matchesAllOf(mnemonic),
                "mnemonic must only contain letters and/or digits, and have non-zero length, was: \"%s\"",
                mnemonic
            )
            this.mnemonic = mnemonic
            return this
        }

        /**
         * Sets the exec group for this action by name. This does not check that `execGroup` is
         * being set to a valid exec group (i.e. one that actually exists). This method expects callers
         * to do that work.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecGroup(execGroup: String?): Builder {
            this.execGroup = execGroup
            return this
        }

        companion object {
            private val envInterner: com.google.common.collect.Interner<com.google.common.collect.ImmutableMap<String?, String?>> =
                BlazeInterners.newWeakInterner<com.google.common.collect.ImmutableMap<String?, String?>?>()
        }
    }

    companion object {
        private const val GUID = "ebd6fce3-093e-45ee-adb6-bf513b602f0d"

        private val executionInfoInterner: com.google.common.collect.Interner<com.google.common.collect.ImmutableSortedMap<String?, String?>?> =
            BlazeInterners.newWeakInterner<com.google.common.collect.ImmutableSortedMap<String?, String?>?>()

        fun createActionEnvironment(
            configuration: BuildConfigurationValue,
            useDefaultShellEnvironment: Boolean,
            environment: com.google.common.collect.ImmutableMap<String?, String?>
        ): ActionEnvironment {
            var env: ActionEnvironment
            if (useDefaultShellEnvironment && !environment.isEmpty()) {
                // Inherited variables override fixed variables in ActionEnvironment. Since we want the
                // fixed part of the action-provided environment to override the inherited part of the
                // user-provided environment, we have to explicitly filter the inherited part.
                val userFilteredInheritedEnv: com.google.common.collect.ImmutableSet<E?> =
                    com.google.common.collect.Sets.difference<E?>(
                        configuration.getActionEnvironment().getInheritedEnv(), environment.keySet()
                    )
                        .immutableCopy()
                // Do not create a new ActionEnvironment in the common case where no vars have been filtered
                // out.
                if (userFilteredInheritedEnv.size()
                    == configuration.getActionEnvironment().getInheritedEnv().size()
                ) {
                    env = configuration.getActionEnvironment()
                } else {
                    env =
                        ActionEnvironment.create(
                            configuration.getActionEnvironment().getFixedEnv(), userFilteredInheritedEnv
                        )
                }
                env = env.withAdditionalFixedVariables(environment)
            } else if (useDefaultShellEnvironment) {
                // This produces the same result as the previous case, but without the overhead.
                env = configuration.getActionEnvironment()
            } else {
                env = ActionEnvironment.create(environment)
            }
            return env
        }

        /**
         * Returns a [CommandLineItem] for the given executable.
         * 
         * 
         * In the common case that the executable's exec path is already [ ][PathFragment.getCallablePathString] (contains [PathFragment.SEPARATOR_CHAR]),
         * returns the executable as-is to avoid creating a new object.
         * 
         * 
         * The only time this method can't return `executable` as-is is for source artifacts in
         * the root package, since their exec path contains no path separator. Note that derived artifacts
         * are necessarily callable since they are always under an output directory.
         */
        private fun ensureCallable(executable: Artifact): CommandLineItem {
            val execPath: PathFragment = executable.getExecPath()
            return if (execPath.getCallablePathString().equals(executable.expandToCommandLine()))
                executable
            else
                execPath::getCallablePathString
        }
    }
}
