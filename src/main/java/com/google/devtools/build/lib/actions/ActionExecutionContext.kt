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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.analysis.SymlinkEntry

/** A class that groups services in the scope of the action. Like the FileOutErr object.  */
class ActionExecutionContext private constructor(
    executor: com.google.devtools.build.lib.actions.Executor?,
    inputMetadataProvider: InputMetadataProvider,
    actionInputPrefetcher: ActionInputPrefetcher?,
    actionKeyContext: ActionKeyContext?,
    outputMetadataStore: OutputMetadataStore,
    rewindingEnabled: Boolean,
    lostInputsCheck: LostInputsCheck,
    fileOutErr: FileOutErr,
    eventHandler: ExtendedEventHandler?,
    clientEnv: MutableMap<String?, String?>,
    env: Environment?,
    actionFileSystem: FileSystem?,
    discoveredModulesPruner: DiscoveredModulesPruner?,
    syscallCache: SyscallCache?,
    threadStateReceiverForMetrics: ThreadStateReceiver?,
    fileSystemSupportsInputDiscovery: Boolean
) : java.io.Closeable, ActionContextRegistry {
    /**
     * A [RunfilesTree] implementation that wraps another one while overriding the path it
     * should be materialized at.
     */
    private class OverriddenPathRunfilesTree(wrapped: RunfilesTree, execPath: PathFragment?) : RunfilesTree {
        private val execPath: PathFragment?
        private val wrapped: RunfilesTree

        init {
            this.wrapped = wrapped
            this.execPath = execPath
        }

        override fun getExecPath(): PathFragment? {
            return execPath
        }

        override fun getMapping(): SortedMap<PathFragment?, Artifact?>? {
            return wrapped.getMapping()
        }

        override fun getArtifacts(): NestedSet<Artifact?>? {
            return wrapped.getArtifacts()
        }

        override fun getSymlinksMode(): RunfileSymlinksMode? {
            return wrapped.getSymlinksMode()
        }

        override fun isBuildRunfileLinks(): Boolean {
            return wrapped.isBuildRunfileLinks()
        }

        override fun getWorkspaceName(): String? {
            return wrapped.getWorkspaceName()
        }

        override fun getArtifactsAtCanonicalLocationsForLogging(): NestedSet<Artifact?>? {
            return wrapped.getArtifactsAtCanonicalLocationsForLogging()
        }

        override fun getEmptyFilenamesForLogging(): Iterable<PathFragment?>? {
            return wrapped.getEmptyFilenamesForLogging()
        }

        override fun getSymlinksForLogging(): NestedSet<SymlinkEntry?>? {
            return wrapped.getSymlinksForLogging()
        }

        override fun getRootSymlinksForLogging(): NestedSet<SymlinkEntry?>? {
            return wrapped.getRootSymlinksForLogging()
        }

        override fun getRepoMappingManifestForLogging(): Artifact? {
            return wrapped.getRepoMappingManifestForLogging()
        }

        override fun isMappingCached(): Boolean {
            return wrapped.isMappingCached()
        }

        override fun fingerprint(
            actionKeyContext: ActionKeyContext?, fp: Fingerprint?, digestAbsolutePaths: Boolean
        ) {
            wrapped.fingerprint(actionKeyContext, fp, digestAbsolutePaths)
        }
    }

    /**
     * An [InputMetadataProvider] wrapping another while overriding the materialization path of
     * a chosen runfiles tree.
     * 
     * 
     * The choice is made by passing in the runfiles tree artifact which represents the tree whose
     * path is is to be overridden.
     */
    private class OverriddenRunfilesPathInputMetadataProvider
        (wrapped: InputMetadataProvider, wrappedRunfilesArtifact: ActionInput, execPath: PathFragment?) :
        InputMetadataProvider {
        private val wrapped: InputMetadataProvider
        private val wrappedRunfilesArtifact: ActionInput
        private val overriddenTree: OverriddenPathRunfilesTree

        init {
            this.wrapped = wrapped
            this.wrappedRunfilesArtifact = wrappedRunfilesArtifact
            this.overriddenTree =
                OverriddenPathRunfilesTree(
                    wrapped.getRunfilesMetadata(wrappedRunfilesArtifact).getRunfilesTree(), execPath
                )
        }

        @Throws(java.lang.InterruptedException::class, IOException::class, MissingDepExecException::class)
        override fun getInputMetadataChecked(input: ActionInput?): FileArtifactValue? {
            return wrapped.getInputMetadataChecked(input)
        }

        override fun getTreeMetadata(actionInput: ActionInput?): TreeArtifactValue? {
            return wrapped.getTreeMetadata(actionInput)
        }

        override fun getEnclosingTreeMetadata(execPath: PathFragment?): TreeArtifactValue? {
            return wrapped.getEnclosingTreeMetadata(execPath)
        }

        override fun getInput(execPath: PathFragment?): ActionInput? {
            return wrapped.getInput(execPath)
        }

        override fun getFileset(input: ActionInput?): FilesetOutputTree? {
            return wrapped.getFileset(input)
        }

        override fun getFilesets(): MutableMap<Artifact?, FilesetOutputTree?>? {
            return wrapped.getFilesets()
        }

        override fun getRunfilesMetadata(input: ActionInput?): RunfilesArtifactValue? {
            val original: RunfilesArtifactValue? = wrapped.getRunfilesMetadata(input)
            if (wrappedRunfilesArtifact == input) {
                return original.withOverriddenRunfilesTree(overriddenTree)
            } else {
                return original
            }
        }

        override fun getRunfilesTrees(): com.google.common.collect.ImmutableList<RunfilesTree?> {
            return com.google.common.collect.ImmutableList.of<RunfilesTree?>(overriddenTree)
        }
    }

    /** Enum for --subcommands flag  */
    enum class ShowSubcommands(private val shouldShowSubcommands: Boolean, private val prettyPrintArgs: Boolean) {
        TRUE(true, false), PRETTY_PRINT(true, true), FALSE(false, false)
    }

    private val executor: com.google.devtools.build.lib.actions.Executor?
    private val inputMetadataProvider: InputMetadataProvider
    private val actionInputPrefetcher: ActionInputPrefetcher?
    private val actionKeyContext: ActionKeyContext?
    private val outputMetadataStore: OutputMetadataStore
    private val rewindingEnabled: Boolean
    private val lostInputsCheck: LostInputsCheck
    private val fileOutErr: FileOutErr
    private val eventHandler: ExtendedEventHandler?
    private val clientEnv: com.google.common.collect.ImmutableMap<String?, String?>
    private val env: Environment?

    private val actionFileSystem: FileSystem?

    private var richArtifactData: RichArtifactData? = null

    private val pathResolver: ArtifactPathResolver
    private val discoveredModulesPruner: DiscoveredModulesPruner?
    private val syscallCache: SyscallCache?
    private val threadStateReceiverForMetrics: ThreadStateReceiver?
    private val fileSystemSupportsInputDiscovery: Boolean

    init {
        this.inputMetadataProvider = inputMetadataProvider
        this.actionInputPrefetcher = actionInputPrefetcher
        this.actionKeyContext = actionKeyContext
        this.outputMetadataStore = outputMetadataStore
        this.rewindingEnabled = rewindingEnabled
        this.lostInputsCheck = lostInputsCheck
        this.fileOutErr = fileOutErr
        this.eventHandler = eventHandler
        this.clientEnv = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(clientEnv)
        this.executor = executor
        this.env = env
        this.actionFileSystem = actionFileSystem
        this.threadStateReceiverForMetrics = threadStateReceiverForMetrics
        this.pathResolver = ArtifactPathResolver.Companion.createPathResolver(
            actionFileSystem,  // executor is only ever null in testing.
            if (executor == null) null else executor.getExecRoot()
        )
        this.discoveredModulesPruner = discoveredModulesPruner
        this.syscallCache = syscallCache
        this.fileSystemSupportsInputDiscovery = fileSystemSupportsInputDiscovery
    }

    constructor(
        executor: com.google.devtools.build.lib.actions.Executor?,
        inputMetadataProvider: InputMetadataProvider,
        actionInputPrefetcher: ActionInputPrefetcher?,
        actionKeyContext: ActionKeyContext?,
        outputMetadataStore: OutputMetadataStore,
        rewindingEnabled: Boolean,
        lostInputsCheck: LostInputsCheck,
        fileOutErr: FileOutErr,
        eventHandler: ExtendedEventHandler?,
        clientEnv: MutableMap<String?, String?>,
        actionFileSystem: FileSystem?,
        discoveredModulesPruner: DiscoveredModulesPruner?,
        syscallCache: SyscallCache?,
        threadStateReceiverForMetrics: ThreadStateReceiver?
    ) : this(
        executor,
        inputMetadataProvider,
        actionInputPrefetcher,
        actionKeyContext,
        outputMetadataStore,
        rewindingEnabled,
        lostInputsCheck,
        fileOutErr,
        eventHandler,
        clientEnv,  /* env= */
        null,
        actionFileSystem,
        discoveredModulesPruner,
        syscallCache,
        threadStateReceiverForMetrics,  /* fileSystemSupportsInputDiscovery= */
        false
    )

    fun getActionInputPrefetcher(): ActionInputPrefetcher? {
        return actionInputPrefetcher
    }

    fun getInputMetadataProvider(): InputMetadataProvider {
        return inputMetadataProvider
    }

    fun getOutputMetadataStore(): OutputMetadataStore {
        return outputMetadataStore
    }

    fun getFileSystem(): FileSystem? {
        if (actionFileSystem != null) {
            return actionFileSystem
        }
        return executor.getFileSystem()
    }

    fun getExecRoot(): Path? {
        return if (actionFileSystem != null)
            actionFileSystem.getPath(executor.getExecRoot().asFragment())
        else
            executor.getExecRoot()
    }

    fun getActionFileSystem(): FileSystem? {
        return actionFileSystem
    }

    fun fileSystemSupportsInputDiscovery(): Boolean {
        return fileSystemSupportsInputDiscovery
    }

    fun isRewindingEnabled(): Boolean {
        return rewindingEnabled
    }

    @Throws(LostInputsActionExecutionException::class)
    fun checkForLostInputs() {
        lostInputsCheck.checkForLostInputs()
    }

    /**
     * Returns the path for an ActionInput.
     * 
     * 
     * Notably, in the future, we want any action-scoped artifacts to resolve paths using this
     * method instead of [Artifact.getPath] because that does not allow filesystem injection.
     * 
     * 
     * TODO(shahan): cleanup [Action]-scoped references to [Artifact.getPath] and
     * [Artifact.getRoot].
     */
    fun getInputPath(input: ActionInput?): Path? {
        return pathResolver.toPath(input)
    }

    fun getRoot(artifact: Artifact): Root? {
        return pathResolver.transformRoot(artifact.getRoot().getRoot())
    }

    fun getPathResolver(): ArtifactPathResolver {
        return pathResolver
    }

    /**
     * Returns the command line options of the Blaze command being executed.
     */
    fun getOptions(): OptionsProvider? {
        return executor.getOptions()
    }

    fun getClock(): com.google.devtools.build.lib.clock.Clock? {
        return executor.getClock()
    }

    /**
     * Returns [BugReporter] to use when reporting bugs, instead of [ ][com.google.devtools.build.lib.bugreport.BugReport.sendBugReport].
     */
    fun getBugReporter(): BugReporter? {
        return executor.getBugReporter()
    }

    fun getEventHandler(): ExtendedEventHandler? {
        return eventHandler
    }

    fun getRichArtifactData(): RichArtifactData? {
        return richArtifactData
    }

    fun setRichArtifactData(richArtifactData: RichArtifactData?) {
        com.google.common.base.Preconditions.checkState(
            this.richArtifactData == null,
            "rich artifact data was set twice, old=%s, new=%s",
            this.richArtifactData,
            richArtifactData
        )
        this.richArtifactData = richArtifactData
    }

    override fun <T : ActionContext?> getContext(type: java.lang.Class<T?>?): T? {
        return executor.getContext<T?>(type)
    }

    /**
     * Report a subcommand event to this Executor's Reporter and, if action logging is enabled, post
     * it on its EventBus.
     */
    fun maybeReportSubcommand(spawn: Spawn, spawnRunner: String?) {
        val showSubcommands: ShowSubcommands = executor.reportsSubcommands()
        if (!showSubcommands.shouldShowSubcommands) {
            return
        }

        val reason: java.lang.StringBuilder = java.lang.StringBuilder()
        val owner: ActionOwner? = spawn.getResourceOwner().getOwner()
        if (owner == null) {
            reason.append(spawn.getResourceOwner().prettyPrint())
        } else {
            reason.append(owner.getDescription())
            reason.append(" [")
            reason.append(spawn.getResourceOwner().prettyPrint())
            reason.append(", configuration: ")
            reason.append(owner.getConfigurationChecksum())
            if (owner.getExecutionPlatform() != null) {
                reason.append(", execution platform: ")
                reason.append(owner.getExecutionPlatform().label())
            }
            reason.append(", mnemonic: ")
            reason.append(spawn.getMnemonic())
            reason.append("]")
        }

        // We print this command out in such a way that it can safely be
        // copied+pasted as a Bourne shell command.  This is extremely valuable for
        // debugging.
        val message: String? =
            CommandFailureUtils.describeCommand(
                CommandDescriptionForm.COMPLETE,
                showSubcommands.prettyPrintArgs,
                spawn.getArguments(),
                spawn.getEnvironment(),  /* environmentVariablesToClear= */
                null,
                getExecRoot().getPathString(),
                spawn.getConfigurationChecksum(),
                spawn.getExecutionPlatformLabel(),
                spawnRunner
            )
        getEventHandler().handle(Event.of(EventKind.SUBCOMMAND, null, "# " + reason + "\n" + message))
    }

    fun getClientEnv(): com.google.common.collect.ImmutableMap<String?, String?> {
        return clientEnv
    }

    /**
     * Provide that `FileOutErr` that the action should use for redirecting the output and error
     * stream.
     */
    fun getFileOutErr(): FileOutErr {
        return fileOutErr
    }

    /**
     * Provides a mechanism for the action to request values from Skyframe while it discovers inputs.
     */
    fun getEnvironmentForDiscoveringInputs(): Environment? {
        return com.google.common.base.Preconditions.checkNotNull<Environment?>(env)
    }

    fun getActionKeyContext(): ActionKeyContext? {
        return actionKeyContext
    }

    fun getDiscoveredModulesPruner(): DiscoveredModulesPruner? {
        return discoveredModulesPruner
    }

    /** This only exists for loose header checking and as a helper for digest computations.  */
    fun getSyscallCache(): SyscallCache? {
        return syscallCache
    }

    fun getThreadStateReceiverForMetrics(): ThreadStateReceiver? {
        return threadStateReceiverForMetrics
    }

    @Throws(IOException::class)
    override fun close() {
        fileOutErr.close()
    }

    private fun withInputMetadataProvider(
        newInputMetadataProvider: InputMetadataProvider
    ): ActionExecutionContext {
        return ActionExecutionContext(
            executor,
            newInputMetadataProvider,
            actionInputPrefetcher,
            actionKeyContext,
            outputMetadataStore,
            rewindingEnabled,
            lostInputsCheck,
            fileOutErr,
            eventHandler,
            clientEnv,
            env,
            actionFileSystem,
            discoveredModulesPruner,
            syscallCache,
            threadStateReceiverForMetrics,
            fileSystemSupportsInputDiscovery
        )
    }

    /**
     * Creates a new [ActionExecutionContext] whose [InputMetadataProvider] has the given
     * [Artifact]s as inputs.
     * 
     * 
     * Each [Artifact] must be an output of the current [ActionExecutionContext] and it
     * must already have been built.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun withOutputsAsInputs(outputs: Iterable<Artifact?>): ActionExecutionContext {
        val additionalInputMap: com.google.common.collect.ImmutableMap.Builder<ActionInput?, FileArtifactValue?> =
            com.google.common.collect.ImmutableMap.builder<ActionInput?, FileArtifactValue?>()

        for (output in outputs) {
            additionalInputMap.put(output, outputMetadataStore.getOutputMetadata(output))
        }

        val additionalInputMetadata: StaticInputMetadataProvider =
            StaticInputMetadataProvider(additionalInputMap.buildOrThrow())

        return withInputMetadataProvider(
            DelegatingPairInputMetadataProvider(additionalInputMetadata, inputMetadataProvider)
        )
    }

    fun withOverriddenRunfilesPath(
        overriddenRunfilesArtifact: ActionInput, overrideRunfilesPath: PathFragment?
    ): ActionExecutionContext {
        return withInputMetadataProvider(
            OverriddenRunfilesPathInputMetadataProvider(
                inputMetadataProvider, overriddenRunfilesArtifact, overrideRunfilesPath
            )
        )
    }

    /**
     * Allows us to create a new context that overrides the FileOutErr with another one. This is
     * useful for muting the output for example.
     */
    fun withFileOutErr(fileOutErr: FileOutErr): ActionExecutionContext {
        return ActionExecutionContext(
            executor,
            inputMetadataProvider,
            actionInputPrefetcher,
            actionKeyContext,
            outputMetadataStore,
            rewindingEnabled,
            lostInputsCheck,
            fileOutErr,
            eventHandler,
            clientEnv,
            env,
            actionFileSystem,
            discoveredModulesPruner,
            syscallCache,
            threadStateReceiverForMetrics,
            fileSystemSupportsInputDiscovery
        )
    }

    /**
     * A way of checking whether any lost inputs have been detected during the execution of this
     * action.
     */
    interface LostInputsCheck {
        /** Throws if inputs have been lost.  */
        @Throws(LostInputsActionExecutionException::class)
        fun checkForLostInputs()

        companion object {
            val NONE: LostInputsCheck = LostInputsCheck {}
        }
    }

    companion object {
        fun forInputDiscovery(
            executor: com.google.devtools.build.lib.actions.Executor?,
            actionInputFileCache: InputMetadataProvider,
            actionInputPrefetcher: ActionInputPrefetcher?,
            actionKeyContext: ActionKeyContext?,
            rewindingEnabled: Boolean,
            lostInputsCheck: LostInputsCheck,
            fileOutErr: FileOutErr,
            eventHandler: ExtendedEventHandler?,
            clientEnv: MutableMap<String?, String?>,
            env: Environment?,
            actionFileSystem: FileSystem?,
            discoveredModulesPruner: DiscoveredModulesPruner?,
            syscalls: SyscallCache?,
            threadStateReceiverForMetrics: ThreadStateReceiver?,
            fileSystemSupportsInputDiscovery: Boolean
        ): ActionExecutionContext {
            return ActionExecutionContext(
                executor,
                actionInputFileCache,
                actionInputPrefetcher,
                actionKeyContext,
                null,
                rewindingEnabled,
                lostInputsCheck,
                fileOutErr,
                eventHandler,
                clientEnv,
                env,
                actionFileSystem,
                discoveredModulesPruner,
                syscalls,
                threadStateReceiverForMetrics,
                fileSystemSupportsInputDiscovery
            )
        }
    }
}
