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

import com.google.devtools.build.lib.actions.AbstractAction.Companion.deleteOutput
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo

/**
 * Abstract implementation of Action which implements basic functionality: the inputs, outputs, and
 * toString method. Both input and output sets are immutable. Subclasses must be generally immutable
 * - see the documentation on [Action].
 */
@Immutable
@ThreadSafe
abstract class AbstractAction protected constructor(
    owner: ActionOwner?,
    inputs: NestedSet<Artifact?>?,
    rawOutputs: Any?
) : ActionKeyComputer(), com.google.devtools.build.lib.actions.Action, ActionApi {
    private val owner: ActionOwner

    // The variable inputs is non-final only so that actions that discover their inputs can modify it.
    // Access through getInputs() in case it's overridden.
    @javax.annotation.concurrent.GuardedBy("this")
    private var inputs: NestedSet<Artifact?>?

    /**
     * To save memory, this is either an [Artifact] for actions with a single output, or a
     * duplicate-free `Artifact[]` for actions with multiple outputs.
     */
    // AutoCodec cannot see private fields in superclasses due to b/32473060.
    @VisibleForSerialization
    protected val rawOutputs: Any

    protected constructor(owner: ActionOwner?, inputs: NestedSet<Artifact?>?, outputs: Iterable<out Artifact?>) : this(
        owner,
        inputs,
        com.google.devtools.build.lib.actions.AbstractAction.Companion.singletonOrArray(outputs)
    )

    /** Constructor for serialization.  */
    init {
        this.owner = com.google.common.base.Preconditions.checkNotNull<ActionOwner>(owner)
        this.inputs = com.google.common.base.Preconditions.checkNotNull<NestedSet<Artifact?>?>(inputs)
        this.rawOutputs = com.google.common.base.Preconditions.checkNotNull<Any>(rawOutputs)
    }

    public override fun isImmutable(): Boolean {
        return true // immutable and Starlark-hashable
    }

    override fun getOwner(): ActionOwner {
        return owner
    }

    override fun inputsKnown(): Boolean {
        if (!discoversInputs()) {
            return true
        }
        synchronized(this) {
            return inputsDiscovered()
        }
    }

    /**
     * {@inheritDoc}
     * 
     * 
     * Should be overridden along with [.discoverInputs], [.inputsDiscovered], [ ][.setInputsDiscovered] and [.getOriginalInputs] by actions that do input discovery.
     */
    override fun discoversInputs(): Boolean {
        return false
    }

    override fun prunedInputs(): Boolean {
        return false
    }

    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    override fun discoverInputs(actionExecutionContext: ActionExecutionContext?): NestedSet<Artifact?>? {
        throw java.lang.IllegalStateException("Not an input-discovering action: " + this)
    }

    override fun resetDiscoveredInputs() {
        com.google.common.base.Preconditions.checkState(discoversInputs(), "Not an input-discovering action: %s", this)
        if (!inputsKnown()) {
            return
        }
        synchronized(this) {
            inputs = getOriginalInputs()
            setInputsDiscovered(false)
        }
    }

    /**
     * Returns true if inputs have been discovered.
     * 
     * 
     * The value returned reflects the most recent call to [.setInputsDiscovered]. If [ ][.setInputsDiscovered] has never been called, returns false.
     * 
     * 
     * This method is used instead of a `boolean` field in this class in order to save memory
     * for actions which do not discover inputs.
     */
    @com.google.errorprone.annotations.ForOverride
    @javax.annotation.concurrent.GuardedBy("this")
    protected open fun inputsDiscovered(): Boolean {
        throw java.lang.IllegalStateException("Must be overridden by input-discovering action: " + this)
    }

    /**
     * Informs input-discovering actions about their discovery state so that they can correctly
     * implement [.inputsDiscovered].
     */
    @com.google.errorprone.annotations.ForOverride
    @javax.annotation.concurrent.GuardedBy("this")
    protected open fun setInputsDiscovered(inputsDiscovered: Boolean) {
        throw java.lang.IllegalStateException("Must be overridden by input-discovering action: " + this)
    }

    override fun getOriginalInputs(): NestedSet<Artifact?>? {
        com.google.common.base.Preconditions.checkState(
            !discoversInputs(),
            "Must be overridden by input-discovering action"
        )
        return getInputs()
    }

    override fun getAllowedDerivedInputs(): NestedSet<Artifact?>? {
        throw java.lang.IllegalStateException(
            "Must be overridden for action that may have unknown inputs: " + this
        )
    }

    override fun getSchedulingDependencies(): NestedSet<Artifact?> {
        return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    }

    /**
     * Should be called when the inputs of the action become known, that is, either during [ ][.discoverInputs] or during [.execute].
     * 
     * 
     * When an action discovers inputs, it must have been called by the time `#execute()`
     * returns. It can be called both during `discoverInputs` and during `execute()`.
     * 
     * 
     * In addition to being called from action implementations, it will also be called by Bazel
     * itself when an action is loaded from the on-disk action cache.
     */
    @kotlin.jvm.Synchronized
    override fun updateInputs(inputs: NestedSet<Artifact?>?) {
        com.google.common.base.Preconditions.checkState(discoversInputs(), "Not an input-discovering action: %s", this)
        this.inputs = inputs
        setInputsDiscovered(true)
    }

    override fun getTools(): NestedSet<Artifact?> {
        return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    }

    @kotlin.jvm.Synchronized
    override fun getInputs(): NestedSet<Artifact?>? {
        return inputs
    }

    open fun getEnvironment(): ActionEnvironment {
        return ActionEnvironment.Companion.EMPTY
    }

    @Throws(CommandLineExpansionException::class)
    override fun getEffectiveEnvironment(clientEnv: MutableMap<String?, String?>?): com.google.common.collect.ImmutableMap<String?, String?> {
        val env: ActionEnvironment = getEnvironment()
        val effectiveEnvironment: MutableMap<String?, String?> =
            com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<String?, String?>(env.estimatedSize())
        env.resolve(effectiveEnvironment, clientEnv)
        return com.google.common.collect.ImmutableMap.copyOf<String?, String?>(effectiveEnvironment)
    }

    override fun getClientEnvironmentVariables(): MutableCollection<String?>? {
        return getEnvironment().getInheritedEnv()
    }

    override fun getOutputs(): MutableCollection<Artifact?> {
        return if (rawOutputs is Artifact)
            com.google.common.collect.ImmutableSet.of<Artifact?>(rawOutputs)
        else
            OutputSet(rawOutputs as Array<Artifact?>?)
    }

    /**
     * Simple [Set] wrapper around an array for actions with multiple outputs.
     * 
     * 
     * Implements [Set] so that passing an instance to [ImmutableSet.copyOf] results in
     * precise pre-sizing (since it is known to be duplicate-free). Note that the return type of
     * [ActionAnalysisMetadata.getOutputs] is [Collection], so callers are unlikely to
     * expect a fast [.contains] implementation.
     */
    private class OutputSet(array: Array<Artifact?>) : AbstractSet<Artifact?>() {
        private val array: Array<Artifact?>

        init {
            this.array = array
        }

        override fun iterator(): MutableIterator<Artifact?> {
            return com.google.common.collect.Iterators.forArray<Artifact?>(*array)
        }

        override fun size(): Int {
            return array.size
        }
    }

    override fun getPrimaryInput(): Artifact? {
        // The default behavior is to return the first input artifact.
        // Call through the method, not the field, because it may be overridden.
        return com.google.devtools.build.lib.actions.AbstractAction.Companion.getFirstOrNull(getInputs())
    }

    private fun getOriginalPrimaryInput(): Artifact? {
        // The default behavior is to return the first input artifact of the original input list (before
        // input discovery).
        // Call through the method, not the field, because it may be overridden.
        return com.google.devtools.build.lib.actions.AbstractAction.Companion.getFirstOrNull(getOriginalInputs())
    }

    override fun getPrimaryOutput(): Artifact? {
        return if (rawOutputs is Artifact) rawOutputs else (rawOutputs as Array<Artifact?>?)!![0]
    }

    override fun getMandatoryInputs(): NestedSet<Artifact?>? {
        return getInputs()
    }

    override fun toString(): String {
        return (prettyPrint()
                + " ("
                + getMnemonic()
                + "["
                + getInputs().toList()
                + (if (inputsKnown()) " -> " else ", unknown inputs -> ")
                + getOutputs()
                + "]"
                + ")")
    }

    abstract override fun getMnemonic(): String?

    override fun describeKey(): String? {
        return null
    }

    override fun executeUnconditionally(): Boolean {
        return false
    }

    override fun isVolatile(): Boolean {
        return false
    }

    override fun isShareable(): Boolean {
        return true
    }

    override fun showsOutputUnconditionally(): Boolean {
        return false
    }

    override fun getProgressMessage(): String? {
        return getProgressMessageChecked(null)
    }

    override fun getProgressMessage(mainRepositoryMapping: RepositoryMapping?): String? {
        com.google.common.base.Preconditions.checkNotNull<Any?>(mainRepositoryMapping)
        return getProgressMessageChecked(mainRepositoryMapping)
    }

    private fun getProgressMessageChecked(mainRepositoryMapping: RepositoryMapping?): String? {
        var message = getRawProgressMessage()
        if (message == null) {
            return null
        }
        message = replaceProgressMessagePlaceholders(message, mainRepositoryMapping)
        return if (owner.isBuildConfigurationForTool()) message + " [for tool]" else message
    }

    private fun replaceProgressMessagePlaceholders(
        progressMessage: String, mainRepositoryMapping: RepositoryMapping?
    ): String {
        var progressMessage = progressMessage
        if (progressMessage.contains("%{label}") && owner.getLabel() != null) {
            progressMessage =
                progressMessage.replace(
                    "%{label}", owner.getLabel().getDisplayForm(mainRepositoryMapping)
                )
        }
        if (progressMessage.contains("%{output}") && getPrimaryOutput() != null) {
            progressMessage =
                progressMessage.replace("%{output}", getPrimaryOutput().getRootRelativePathString())
        }
        if (progressMessage.contains("%{input}") && getOriginalPrimaryInput() != null) {
            progressMessage =
                progressMessage.replace(
                    "%{input}", getOriginalPrimaryInput().getRootRelativePathString()
                )
        }
        return progressMessage
    }

    /**
     * Returns a progress message string that is specific for this action. This is then annotated with
     * additional information, currently the string '[for tool]' for actions in the tool
     * configurations.
     * 
     * 
     * A return value of null indicates no message should be reported.
     */
    protected open fun getRawProgressMessage(): String? {
        // A cheesy default implementation.  Subclasses are invited to do something
        // more meaningful.
        return defaultProgressMessage()
    }

    private fun defaultProgressMessage(): String {
        return getMnemonic() + " " + getPrimaryOutput().prettyPrint()
    }

    override fun prettyPrint(): String {
        return "action '" + describe() + "'"
    }

    public override fun repr(printer: Printer, semantics: StarlarkSemantics?) {
        printer.append(prettyPrint()) // TODO(bazel-team): implement a readable representation
    }

    /**
     * Deletes all of the action's output files, if they exist. If any of the Artifacts refers to a
     * directory recursively removes the contents of the directory.
     * 
     * @param execRoot the exec root in which this action is executed
     * @param bulkDeleter a helper to bulk delete outputs to avoid delegating to the filesystem
     * @param cleanupArchivedArtifacts whether to clean up archived tree artifacts
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    protected fun deleteOutputs(
        execRoot: Path,
        pathResolver: ArtifactPathResolver,
        bulkDeleter: BulkDeleter?,
        cleanupArchivedArtifacts: Boolean
    ) {
        val outputs: MutableCollection<Artifact?> = getOutputs()
        val artifactsToDelete: Iterable<Artifact> =
            if (cleanupArchivedArtifacts)
                com.google.common.collect.Iterables.concat<Artifact?>(
                    outputs,
                    com.google.devtools.build.lib.actions.AbstractAction.Companion.archivedTreeArtifactOutputs(outputs)
                )
            else
                outputs
        val additionalPathOutputsToDelete: Iterable<PathFragment?> = getAdditionalPathOutputsToDelete()
        val directoryOutputsToDelete: Iterable<PathFragment?> = getDirectoryOutputsToDelete()
        if (bulkDeleter != null) {
            bulkDeleter.bulkDelete(
                com.google.common.collect.Iterables.< T > concat < T ? > (
                        Artifact.Companion.asPathFragments(artifactsToDelete),
                additionalPathOutputsToDelete,
                directoryOutputsToDelete
            ))
            return
        }

        // TODO(b/185277726): Either we don't need a path resolver for actual deletion of output
        //  artifacts (likely) or we need to transform the fragments below (and then the resolver should
        //  be augmented to deal with exec-path PathFragments).
        for (output in artifactsToDelete) {
            com.google.devtools.build.lib.actions.AbstractAction.Companion.deleteOutput(output, pathResolver)
        }

        for (path in additionalPathOutputsToDelete) {
            deleteOutput(execRoot.getRelative(path),  /*root=*/null)
        }

        for (path in directoryOutputsToDelete) {
            execRoot.getRelative(path).deleteTree()
        }
    }

    @com.google.errorprone.annotations.ForOverride
    protected open fun getAdditionalPathOutputsToDelete(): Iterable<PathFragment?> {
        return com.google.common.collect.ImmutableList.of<PathFragment?>()
    }

    @com.google.errorprone.annotations.ForOverride
    protected open fun getDirectoryOutputsToDelete(): Iterable<PathFragment?> {
        return com.google.common.collect.ImmutableList.of<PathFragment?>()
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun prepare(
        execRoot: Path,
        pathResolver: ArtifactPathResolver,
        bulkDeleter: BulkDeleter?,
        cleanupArchivedArtifacts: Boolean
    ) {
        deleteOutputs(execRoot, pathResolver, bulkDeleter, cleanupArchivedArtifacts)
    }

    override fun describe(): String {
        val progressMessage = getProgressMessage()
        return if (progressMessage != null) progressMessage else defaultProgressMessage()
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    override fun getExtraActionInfo(actionKeyContext: ActionKeyContext?): ExtraActionInfo.Builder {
        val result: ExtraActionInfo.Builder =
            ExtraActionInfo.newBuilder()
                .setOwner(owner.getLabel().toString())
                .setId(getKey(actionKeyContext,  /* inputMetadataProvider= */null))
                .setMnemonic(getMnemonic())
        val aspectDescriptors: com.google.common.collect.ImmutableList<AspectDescriptor?> = owner.getAspectDescriptors()
        val lastAspect: AspectDescriptor? =
            if (aspectDescriptors.isEmpty()) null else com.google.common.collect.Iterables.getLast<AspectDescriptor?>(
                aspectDescriptors
            )
        if (lastAspect != null) {
            result.setAspectName(lastAspect.getAspectClass().getName())

            for (entry in lastAspect.getParameters().getAttributes().asMap().entrySet()) {
                result.putAspectParameters(
                    entry.key,
                    ExtraActionInfo.StringList.newBuilder().addAllValue(entry.value).build()
                )
            }
        }
        return result
    }

    override fun getMandatoryOutputs(): com.google.common.collect.ImmutableSet<Artifact?>? {
        return com.google.common.collect.ImmutableSet.of<Artifact?>()
    }

    /**
     * Returns input files that need to be present to allow extra_action rules to shadow this action
     * correctly when run remotely. This is at least the normal inputs of the action, but may include
     * other files as well. For example C(++) compilation may perform include file header scanning.
     * This needs to be mirrored by the extra_action rule. Called by [ ] at execution time for actions that
     * return true for {link #discoversInputs}.
     * 
     * 
     * Returns null when a required value is missing and a Skyframe restart is required.
     * 
     * @param actionExecutionContext Services in the scope of the action, like the Out/Err streams.
     * @throws ActionExecutionException only when code called from this method throws that exception.
     * @throws InterruptedException if interrupted
     */
    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    override fun getInputFilesForExtraAction(
        actionExecutionContext: ActionExecutionContext?
    ): NestedSet<Artifact?>? {
        return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    }

    public override fun getStarlarkInputs(): Depset {
        return Depset.of(Artifact::class.java, getInputs())
    }

    public override fun getStarlarkOutputs(): Depset {
        return Depset.of(Artifact::class.java, NestedSetBuilder.wrap(Order.STABLE_ORDER, getOutputs()))
    }

    @Throws(EvalException::class, java.lang.InterruptedException::class)
    public override fun getStarlarkArgv(): Sequence<String?>? {
        return null
    }

    public override fun getStarlarkArgs(): Sequence<CommandLineArgsApi?>? {
        // Not all action types support returning Args.
        return null
    }

    @Throws(IOException::class, EvalException::class, java.lang.InterruptedException::class)
    public override fun getStarlarkContent(): String? {
        return null
    }

    @Throws(EvalException::class, java.lang.InterruptedException::class)
    public override fun getStarlarkSubstitutions(): Dict<String?, String?>? {
        return null
    }

    public override fun getExecutionInfoDict(): Dict<String?, String?> {
        val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>? = getExecutionInfo()
        return Dict.immutableCopyOf(executionInfo)
    }

    public override fun getEnv(): Dict<String?, String?> {
        return Dict.immutableCopyOf(getEnvironment().getFixedEnv())
    }

    override fun getExecProperties(): com.google.common.collect.ImmutableMap<String?, String?>? {
        return owner.getExecProperties()
    }

    override fun getExecutionPlatform(): PlatformInfo? {
        return owner.getExecutionPlatform()
    }

    /**
     * Returns artifacts that should be subject to path mapping (see [Spawn.getPathMapper],
     * but aren't inputs of the action.
     */
    fun getAdditionalArtifactsForPathMapping(): NestedSet<Artifact?> {
        return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    }

    companion object {
        /**
         * An arbitrary default resource set. We assume that a typical subprocess is single-threaded
         * (i.e., uses one CPU core) and CPU-bound, and uses a small-ish amount of memory. In the past,
         * we've seen that assuming less than one core can lead to local overload. Unless you have data
         * indicating otherwise (for example, we've observed in the past that C++ linking can use large
         * amounts of memory), we suggest to use this default set.
         */
        // TODO(ulfjack): Collect actual data to confirm that this is an acceptable approximation.
        @kotlin.jvm.JvmField
        val DEFAULT_RESOURCE_SET: ResourceSet = ResourceSet.createWithRamCpu(250.0, 1.0)

        private fun singletonOrArray(outputs: Iterable<out Artifact?>): Any? {
            val set: com.google.common.collect.ImmutableSet<Artifact?> =
                com.google.common.collect.ImmutableSet.copyOf<Artifact?>(outputs)
            com.google.common.base.Preconditions.checkArgument(!set.isEmpty(), "Action outputs may not be empty")
            return if (set.size == 1) com.google.common.collect.Iterables.getOnlyElement<Artifact?>(set) else set.toArray<Artifact?>(
                java.util.function.IntFunction { _Dummy_.__Array__() })
        }

        private fun getFirstOrNull(inputs: NestedSet<Artifact?>): Artifact? {
            if (inputs.isEmpty()) {
                return null
            } else if (inputs.isSingleton()) {
                return inputs.getSingleton()
            } else {
                return inputs.toList().getFirst()
            }
        }

        private fun archivedTreeArtifactOutputs(outputs: MutableCollection<Artifact?>): Iterable<Artifact?> {
            return com.google.common.collect.Iterables.transform<Artifact?, Artifact?>(
                com.google.common.collect.Iterables.filter<Artifact?>(
                    outputs,
                    com.google.common.base.Predicate { obj: Artifact? -> obj.isTreeArtifact() }),
                com.google.common.base.Function { tree: Artifact? -> ArchivedTreeArtifact.Companion.createForTree(tree as SpecialArtifact?) })
        }

        /**
         * Remove an output artifact.
         * 
         * 
         * If the path refers to a directory, recursively removes the contents of the directory.
         * 
         * @param output artifact to remove
         */
        @Throws(IOException::class)
        protected fun deleteOutput(output: Artifact, pathResolver: ArtifactPathResolver) {
            com.google.devtools.build.lib.actions.AbstractAction.Companion.deleteOutput(
                pathResolver.toPath(output), pathResolver.transformRoot(output.getRoot().getRoot())
            )
        }

        /**
         * Helper method to remove an output file.
         * 
         * 
         * If the path refers to a directory, recursively removes the contents of the directory.
         * 
         * @param path the output to remove
         * @param root the root containing the output. This is used to check that we don't delete
         * arbitrary files in the file system.
         */
        @Throws(IOException::class)
        fun deleteOutput(path: Path, root: Root?) {
            try {
                // Optimize for the common case: output artifacts are files.
                path.delete()
            } catch (e: IOException) {
                // Handle a couple of scenarios where the output can still be deleted, but make sure we're not
                // deleting random files on the filesystem.
                if (root == null) {
                    throw IOException("null root", e)
                }
                if (!root.contains(path)) {
                    throw IOException(String.format("%s not under %s", path, root), e)
                }

                val parentDir: Path = path.getParentDirectory()
                if (root.contains(parentDir)) {
                    try {
                        parentDir.setWritable(true)
                    } catch (ignored: IOException) {
                        // Intentionally ignored because we will fail below anyway.
                    }
                }

                // Retry deleting after making the parent writable.
                if (path.isDirectory(Symlinks.NOFOLLOW)) {
                    path.deleteTree()
                } else {
                    path.delete()
                }
            }
        }
    }
}
