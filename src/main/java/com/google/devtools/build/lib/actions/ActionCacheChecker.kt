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

import com.google.devtools.build.lib.actions.cache.Protos.ActionCacheStatistics.MissReason

/**
 * Checks whether an [Action] needs to be executed, or whether it has not changed since it was
 * last stored in the action cache. Must be informed of the new Action data after execution as well.
 * 
 * 
 * The fingerprint, input files names, and metadata (either mtimes or MD5sums) of each action are
 * cached in the action cache to avoid unnecessary rebuilds.
 * 
 * 
 * While instances of this class hold references to action and metadata cache instances, they are
 * otherwise lightweight, and should be constructed anew and discarded for each build request.
 */
class ActionCacheChecker(
    actionCache: ActionCache?,
    artifactResolver: ArtifactResolver,
    actionKeyContext: ActionKeyContext?,
    executionFilter: com.google.common.base.Predicate<in com.google.devtools.build.lib.actions.Action?>,
    proxyMetadataFactory: ProxyMetadataFactory,
    cacheConfig: CacheConfig?
) {
    private val actionKeyContext: ActionKeyContext?
    private val executionFilter: com.google.common.base.Predicate<in com.google.devtools.build.lib.actions.Action?>
    private val artifactResolver: ArtifactResolver
    private val proxyMetadataFactory: ProxyMetadataFactory
    private val cacheConfig: CacheConfig
    private val totalCacheCheckSemaphoreWaitMillis: AtomicLong = AtomicLong(0)

    private val actionCache: ActionCache? // Null when not enabled.


    /** Cache config parameters for ActionCacheChecker.  */
    @AutoValue
    abstract class CacheConfig {
        abstract fun enabled(): Boolean

        abstract fun storeOutputMetadata(): Boolean

        /** Builder for ActionCacheChecker.CacheConfig.  */
        @AutoValue.Builder
        abstract class Builder {
            abstract fun setEnabled(value: Boolean): Builder?

            abstract fun setStoreOutputMetadata(value: Boolean): Builder?

            abstract fun build(): CacheConfig?
        }

        companion object {
            fun builder(): Builder {
                return Builder()
            }
        }
    }

    init {
        this.executionFilter = executionFilter
        this.actionKeyContext = actionKeyContext
        this.artifactResolver = artifactResolver
        this.proxyMetadataFactory = proxyMetadataFactory
        this.cacheConfig =
            (if (cacheConfig != null)
                cacheConfig
            else
                CacheConfig.Companion.builder().setEnabled(true).setStoreOutputMetadata(false).build())!!
        if (this.cacheConfig.enabled()) {
            this.actionCache = com.google.common.base.Preconditions.checkNotNull<ActionCache?>(actionCache)
        } else {
            this.actionCache = null
        }
    }

    fun isActionExecutionProhibited(action: com.google.devtools.build.lib.actions.Action?): Boolean {
        return !executionFilter.apply(action)
    }

    /** Whether the action cache is enabled.  */
    fun enabled(): Boolean {
        return cacheConfig.enabled()
    }

    /**
     * Checks whether one of existing output paths is already used as a key. If yes, returns it -
     * otherwise uses first output file as a key
     */
    private fun getCacheEntry(action: com.google.devtools.build.lib.actions.Action): com.google.devtools.build.lib.actions.cache.ActionCache.Entry? {
        if (!cacheConfig.enabled()) {
            return null // ignore existing cache when disabled.
        }
        return ActionCacheUtils.getCacheEntry(actionCache, action)
    }

    fun removeCacheEntry(action: com.google.devtools.build.lib.actions.Action) {
        com.google.common.base.Preconditions.checkState(enabled(), "Action cache disabled")
        ActionCacheUtils.removeCacheEntry(actionCache, action)
    }

    private fun unconditionalExecution(action: com.google.devtools.build.lib.actions.Action): Boolean {
        return !isActionExecutionProhibited(action) && action.executeUnconditionally()
    }

    /**
     * The currently cached outputs when output metadata is stored (i.e., `CacheConfig#shouldStoreOutputMetadata`).
     * 
     * 
     * Metadata retrieved from the filesystem overrides the cached metadata. This way, an action
     * will not be rerun if the cached metadata is still valid, unless the filesystem state needs to
     * be updated.
     */
    private class CachedOutputMetadata(
        fileMetadata: com.google.common.collect.ImmutableMap<Artifact?, FileArtifactValue?>?,
        treeMetadata: com.google.common.collect.ImmutableMap<SpecialArtifact?, TreeArtifactValue?>?
    ) {
        val fileMetadata: com.google.common.collect.ImmutableMap<Artifact?, FileArtifactValue?>?
        val treeMetadata: com.google.common.collect.ImmutableMap<SpecialArtifact?, TreeArtifactValue?>?

        init {
            this.fileMetadata = fileMetadata
            this.treeMetadata = treeMetadata
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun loadCachedOutputMetadata(
        action: com.google.devtools.build.lib.actions.Action,
        entry: com.google.devtools.build.lib.actions.cache.ActionCache.Entry,
        outputMetadataStore: OutputMetadataStore
    ): CachedOutputMetadata {
        val mergedFileMetadata: com.google.common.collect.ImmutableMap.Builder<Artifact?, FileArtifactValue?> =
            com.google.common.collect.ImmutableMap.builder<Artifact?, FileArtifactValue?>()
        val mergedTreeMetadata: com.google.common.collect.ImmutableMap.Builder<SpecialArtifact?, TreeArtifactValue?> =
            com.google.common.collect.ImmutableMap.builder<SpecialArtifact?, TreeArtifactValue?>()
        val proxyOutputs: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.copyOf<String?>(entry.getProxyOutputs())

        for (artifact in action.getOutputs()) {
            if (artifact.isTreeArtifact()) {
                val parent: SpecialArtifact = artifact as SpecialArtifact

                if (proxyOutputs.contains(parent.getExecPathString())) {
                    try {
                        val metadata: TreeArtifactValue = constructProxyTreeMetadata(parent)
                        mergedTreeMetadata.put(parent, metadata)
                    } catch (e: IOException) {
                        // Ignore - we'll get an action cache miss.
                    }
                    continue
                }

                val cachedTreeMetadata: SerializableTreeArtifactValue? = entry.getOutputTree(parent)
                if (cachedTreeMetadata == null) {
                    continue
                }

                val childValues: MutableMap<TreeFileArtifact?, FileArtifactValue?> =
                    HashMap<TreeFileArtifact?, FileArtifactValue?>()
                // Load remote child file metadata from cache.
                cachedTreeMetadata
                    .childValues
                    .forEach { (key: String?, value: FileArtifactValue?) ->
                        childValues.put(
                            TreeFileArtifact.Companion.createTreeOutput(
                                parent,
                                key
                            ), value
                        )
                    }

                var archivedRepresentation: java.util.Optional<ArchivedRepresentation?> =
                    cachedTreeMetadata
                        .archivedFileValue
                        .map<ArchivedRepresentation?>(
                            java.util.function.Function { fileArtifactValue: FileArtifactValue? ->
                                ArchivedRepresentation.create(
                                    ArchivedTreeArtifact.Companion.createForTree(parent), fileArtifactValue
                                )
                            })

                var filesystemTreeMetadata: TreeArtifactValue?
                try {
                    filesystemTreeMetadata = outputMetadataStore.getTreeArtifactValue(parent)
                } catch (ignored: FileNotFoundException) {
                    filesystemTreeMetadata = null
                } catch (e: IOException) {
                    // Ignore the cached metadata if we encountered an error when loading its counterpart from
                    // the filesystem.
                    logger.atWarning().withCause(e).log("Failed to load metadata for %s", parent)
                    continue
                }

                if (filesystemTreeMetadata != null) {
                    // Filesystem metadata overrides the cached metadata.
                    childValues.putAll(filesystemTreeMetadata.getChildValues())
                    if (filesystemTreeMetadata.getArchivedRepresentation().isPresent()) {
                        archivedRepresentation = filesystemTreeMetadata.getArchivedRepresentation()
                    }
                }

                val merged: TreeArtifactValue.Builder = TreeArtifactValue.newBuilder(parent)
                childValues.forEach(merged::putChild)
                archivedRepresentation.ifPresent(merged::setArchivedRepresentation)

                mergedTreeMetadata.put(parent, merged.build())
            } else {
                var cachedMetadata: FileArtifactValue?
                if (proxyOutputs.contains(artifact.getExecPathString())) {
                    try {
                        cachedMetadata = proxyMetadataFactory.createProxyMetadata(artifact)
                    } catch (e: IOException) {
                        cachedMetadata = null
                    }
                } else {
                    cachedMetadata = entry.getOutputFile(artifact)
                }

                if (cachedMetadata == null) {
                    continue
                }

                var filesystemMetadata: FileArtifactValue?
                try {
                    filesystemMetadata = getOutputMetadataOrConstant(outputMetadataStore, artifact)
                } catch (ignored: FileNotFoundException) {
                    filesystemMetadata = null
                } catch (e: IOException) {
                    // Ignore the cached metadata if we encountered an error when loading its counterpart from
                    // the filesystem.
                    logger.atWarning().withCause(e).log("Failed to load metadata for %s", artifact)
                    continue
                }

                // Filesystem metadata overrides the cached metadata.
                mergedFileMetadata.put(
                    artifact, if (filesystemMetadata != null) filesystemMetadata else cachedMetadata
                )
            }
        }

        return CachedOutputMetadata(
            mergedFileMetadata.buildOrThrow(), mergedTreeMetadata.buildOrThrow()
        )
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun constructProxyTreeMetadata(parent: SpecialArtifact): TreeArtifactValue {
        val tree: TreeArtifactValue.Builder = TreeArtifactValue.newBuilder(parent)
        TreeArtifactValue.visitTree(
            parent.getPath(),
            { parentRelativePath, type, traversedSymlink ->
                if (type !== Dirent.Type.DIRECTORY) {
                    val child: TreeFileArtifact? = createTreeOutput(parent, parentRelativePath)
                    val metadata: FileArtifactValue? = proxyMetadataFactory.createProxyMetadata(child)
                    // visitTree() uses multiple threads and putChild() is not thread-safe
                    synchronized(tree) {
                        tree.putChild(child, metadata)
                    }
                }
            })
        return tree.build()
    }

    /**
     * Checks whether `action` needs to be executed and returns a non-null [Token] if so.
     * 
     * 
     * The method checks if any of the action's inputs or outputs have changed. Returns a non-null
     * [Token] if the action needs to be executed, and null otherwise.
     * 
     * 
     * If this method returns non-null, indicating that the action will be executed, the `outputMetadataStore` must have any cached metadata cleared so that it does not serve stale
     * metadata for the action's outputs after the action is executed.
     */
    // Note: the handler should only be used for DEPCHECKER events; there's no
    // guarantee it will be available for other events.
    @Throws(java.lang.InterruptedException::class)
    fun getTokenIfNeedToExecute(
        action: com.google.devtools.build.lib.actions.Action,
        resolvedCacheArtifacts: MutableList<Artifact?>?,
        clientEnv: MutableMap<String?, String?>,
        outputPermissions: OutputPermissions?,
        handler: EventHandler?,
        inputMetadataProvider: InputMetadataProvider,
        outputMetadataStore: OutputMetadataStore,
        actionExecutionSalt: String?,
        outputChecker: OutputChecker?,
        useArchivedTreeArtifacts: Boolean
    ): Token? {
        // TODO(bazel-team): (2010) For RunfilesAction/SymlinkAction and similar actions that
        // produce only symlinks we should not check whether inputs are valid at all - all that matters
        // that inputs and outputs are still exist (and new inputs have not appeared). All other checks
        // are unnecessary. In other words, the only metadata we should check for them is file existence
        // itself.

        if (!cacheConfig.enabled()) {
            return com.google.devtools.build.lib.actions.ActionCacheChecker.Token(action)
        }

        val entry: com.google.devtools.build.lib.actions.cache.ActionCache.Entry? = getCacheEntry(action)
        var actionInputs: NestedSet<Artifact?> = action.getInputs()
        // Resolve action inputs from cache, if necessary.
        val inputsKnown: Boolean = action.inputsKnown()
        if (!inputsKnown && resolvedCacheArtifacts != null) {
            // The action doesn't know its inputs, but the caller has a good idea of what they are.
            com.google.common.base.Preconditions.checkState(
                action.discoversInputs(),
                "Actions that don't know their inputs must discover them: %s",
                action
            )
            // When inputs are pruned, the action cache stores all used inputs. Otherwise, it only stores
            // discovered inputs.
            if (entry != null && !entry.isCorrupted() && entry.prunedInputs()) {
                actionInputs = NestedSetBuilder.wrap(Order.STABLE_ORDER, resolvedCacheArtifacts)
            } else {
                actionInputs =
                    NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                        .addTransitive(action.getMandatoryInputs())
                        .addAll(resolvedCacheArtifacts)
                        .build()
            }
        }

        var cachedOutputMetadata: CachedOutputMetadata? = null
        if (entry != null && !entry.isCorrupted() && cacheConfig.storeOutputMetadata()) {
            cachedOutputMetadata = loadCachedOutputMetadata(action, entry, outputMetadataStore)
        }

        val token: Token = com.google.devtools.build.lib.actions.ActionCacheChecker.Token(action)
        if (mustExecute(
                action,
                entry,
                token,
                handler,
                inputMetadataProvider,
                outputMetadataStore,
                actionInputs,
                clientEnv,
                outputPermissions,
                actionExecutionSalt,
                cachedOutputMetadata,
                outputChecker,
                useArchivedTreeArtifacts
            )
        ) {
            if (entry != null) {
                removeCacheEntry(action)
            }
            return token
        }

        // Don't store pruned inputs in the action - it costs too much memory.
        if (!inputsKnown && !entry.prunedInputs()) {
            action.updateInputs(actionInputs)
        }

        // Inject cached output metadata if we have an action cache hit.
        if (cachedOutputMetadata != null) {
            cachedOutputMetadata.fileMetadata.forEach { (output: Artifact?, metadata: FileArtifactValue?) ->
                outputMetadataStore.injectFile(
                    output,
                    metadata
                )
            }
            cachedOutputMetadata.treeMetadata.forEach { (output: SpecialArtifact?, tree: TreeArtifactValue?) ->
                outputMetadataStore.injectTree(
                    output,
                    tree
                )
            }
        }

        return null
    }

    @Throws(java.lang.InterruptedException::class)
    private fun mustExecute(
        action: com.google.devtools.build.lib.actions.Action,
        entry: com.google.devtools.build.lib.actions.cache.ActionCache.Entry?,
        token: Token,
        handler: EventHandler?,
        inputMetadataProvider: InputMetadataProvider,
        outputMetadataStore: OutputMetadataStore,
        actionInputs: NestedSet<Artifact?>,
        clientEnv: MutableMap<String?, String?>,
        outputPermissions: OutputPermissions?,
        actionExecutionSalt: String?,
        cachedOutputMetadata: CachedOutputMetadata?,
        outputChecker: OutputChecker?,
        useArchivedTreeArtifacts: Boolean
    ): Boolean {
        // Unconditional execution can be applied only for actions that are allowed to be executed.
        if (unconditionalExecution(action)) {
            com.google.common.base.Preconditions.checkState(action.isVolatile())
            reportUnconditionalExecution(handler, action)
            actionCache.accountMiss(MissReason.UNCONDITIONAL_EXECUTION)
            return true
        }

        if (entry == null) {
            reportNewAction(handler, action)
            actionCache.accountMiss(MissReason.NOT_CACHED)
            return true
        }

        if (entry.isCorrupted()) {
            reportCorruptedCacheEntry(handler, action)
            actionCache.accountMiss(MissReason.CORRUPTED_CACHE_ENTRY)
            return true
        }

        val actionKey: String? = action.getKey(actionKeyContext, inputMetadataProvider)
        token.actionKey = actionKey // Save the action key for reuse in updateActionCache().

        val effectiveEnvironment: com.google.common.collect.ImmutableMap<String?, String?> =
            computeEffectiveEnvironment(action, clientEnv)

        if (!isUpToDate(
                entry,
                action,
                actionKey,
                actionInputs,
                inputMetadataProvider,
                outputMetadataStore,
                cachedOutputMetadata,
                outputChecker,
                effectiveEnvironment,
                actionExecutionSalt,
                outputPermissions,
                useArchivedTreeArtifacts
            )
        ) {
            reportDigestMismatch(handler, action)
            actionCache.accountMiss(MissReason.DIGEST_MISMATCH)
            return true
        }

        actionCache.accountHit()
        return false
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun updateActionCache(
        action: com.google.devtools.build.lib.actions.Action,
        token: Token,
        inputMetadataProvider: InputMetadataProvider,
        outputMetadataStore: OutputMetadataStore,
        clientEnv: MutableMap<String?, String?>,
        outputPermissions: OutputPermissions?,
        actionExecutionSalt: String?,
        useArchivedTreeArtifacts: Boolean
    ) {
        com.google.common.base.Preconditions.checkState(
            cacheConfig.enabled(),
            "cache unexpectedly disabled, action: %s",
            action
        )
        com.google.common.base.Preconditions.checkArgument(token != null, "token unexpectedly null, action: %s", action)
        val key = token.cacheKey
        if (actionCache.get(key) != null) {
            // This cache entry has already been updated by a shared action. We don't need to do it again.
            return
        }
        val effectiveEnvironment: com.google.common.collect.ImmutableMap<String?, String?> =
            computeEffectiveEnvironment(action, clientEnv)

        // We may already have the action key stored in the token if there was a previous (but out of
        // date) cache entry for this action. If not, there's no need to store the action key in the
        // token since we won't need it again.
        var actionKey = token.actionKey
        if (actionKey == null) {
            actionKey = action.getKey(actionKeyContext, inputMetadataProvider)
        }

        val builder: com.google.devtools.build.lib.actions.cache.ActionCache.Entry.Builder =
            com.google.devtools.build.lib.actions.cache.ActionCache.Entry.Builder(
                actionKey,
                action.discoversInputs(),
                effectiveEnvironment,
                actionExecutionSalt,
                outputPermissions,
                useArchivedTreeArtifacts
            )
                .setPrunedInputs(action.prunedInputs())

        for (output in action.getOutputs()) {
            // Remove old records from the cache if they used different key.
            val execPath: String = output.getExecPathString()
            if (key != execPath) {
                actionCache.remove(execPath)
            }
            if (!outputMetadataStore.artifactOmitted(output)) {
                if (output.isTreeArtifact()) {
                    val parent: SpecialArtifact = output as SpecialArtifact
                    val metadata: TreeArtifactValue? = outputMetadataStore.getTreeArtifactValue(parent)
                    builder.addOutputTree(parent, metadata, cacheConfig.storeOutputMetadata())
                } else {
                    // Output files *must* exist and be accessible after successful action execution. We use
                    // the 'constant' metadata for the volatile workspace status output. The volatile output
                    // contains information such as timestamps, and even when --stamp is enabled, we don't
                    // want to rebuild everything if only that file changes.
                    val metadata: FileArtifactValue? = getOutputMetadataOrConstant(outputMetadataStore, output)
                    com.google.common.base.Preconditions.checkState(metadata != null)
                    builder.addOutputFile(output, metadata, cacheConfig.storeOutputMetadata())
                }
            }
        }

        val excludePathsFromActionCache: com.google.common.collect.ImmutableSet<Artifact?> =
            if (action.discoversInputs() && !action.prunedInputs())
                action.getMandatoryInputs().toSet()
            else
                com.google.common.collect.ImmutableSet.of<Artifact?>()

        for (input in action.getInputs().toList()) {
            builder.addInputFile(
                input,
                getInputMetadataMaybe(inputMetadataProvider, input),  /* saveExecPath= */
                !excludePathsFromActionCache.contains(input)
            )
        }

        actionCache.put(key, builder.build())
    }

    @Throws(PackageRootException::class, java.lang.InterruptedException::class)
    fun getCachedInputs(
        action: com.google.devtools.build.lib.actions.Action,
        resolver: PackageRootResolver?
    ): MutableList<Artifact?>? {
        val entry: com.google.devtools.build.lib.actions.cache.ActionCache.Entry? = getCacheEntry(action)
        if (entry == null || entry.isCorrupted()) {
            return com.google.common.collect.ImmutableList.of<Artifact?>()
        }

        val outputs: MutableList<PathFragment?> = java.util.ArrayList<PathFragment?>()
        for (output in action.getOutputs()) {
            outputs.add(output.getExecPath())
        }
        val inputExecPaths: MutableList<PathFragment?> = java.util.ArrayList<PathFragment?>()
        if (entry.discoversInputs()) {
            for (path in entry.getDiscoveredInputPaths()) {
                val execPath: PathFragment? = PathFragment.create(path)
                // Code assumes that action has only 1-2 outputs and ArrayList.contains() will be most
                // efficient.
                if (!outputs.contains(execPath)) {
                    inputExecPaths.add(execPath)
                }
            }
        }

        // Note that this method may trigger a violation of the desirable invariant that getInputs()
        // is a superset of getMandatoryInputs(). See bug about an "action not in canonical form"
        // error message and the integration test test_crosstool_change_and_failure().
        val allowedDerivedInputsMap: MutableMap<PathFragment?, Artifact?> = HashMap<PathFragment?, Artifact?>()
        for (derivedInput in action.getAllowedDerivedInputs().toList()) {
            if (!derivedInput.isSourceArtifact()) {
                allowedDerivedInputsMap.put(derivedInput.getExecPath(), derivedInput)
            }
        }

        val inputArtifactsBuilder: com.google.common.collect.ImmutableList.Builder<Artifact?> =
            com.google.common.collect.ImmutableList.builder<Artifact?>()
        val unresolvedPaths: MutableList<PathFragment?> = java.util.ArrayList<PathFragment?>()
        for (execPath in inputExecPaths) {
            val artifact: Artifact? = allowedDerivedInputsMap.get(execPath)
            if (artifact != null) {
                inputArtifactsBuilder.add(artifact)
            } else {
                // Remember this execPath, we will try to resolve it as a source artifact.
                unresolvedPaths.add(execPath)
            }
        }

        val resolvedArtifacts: MutableMap<PathFragment?, SourceArtifact?>? =
            artifactResolver.resolveSourceArtifacts(unresolvedPaths, resolver)
        if (resolvedArtifacts == null) {
            // We are missing some dependencies. We need to rerun this update later.
            return null
        }

        for (execPath in unresolvedPaths) {
            val artifact: Artifact? = resolvedArtifacts.get(execPath)
            // If PathFragment cannot be resolved into the artifact, ignore it. This could happen if the
            // rule has changed and the action no longer depends on, e.g., an additional source file in a
            // separate package and that package is no longer referenced anywhere else. It is safe to
            // ignore such paths because dependency checker would identify changes in inputs (ignored path
            // was used before) and will force action execution.
            if (artifact != null) {
                inputArtifactsBuilder.add(artifact)
            }
        }
        return inputArtifactsBuilder.build()
    }

    /**
     * Only call if action requires execution because there was a failure to record action cache hit
     */
    @Throws(java.lang.InterruptedException::class)
    fun getTokenUnconditionallyAfterFailureToRecordActionCacheHit(
        action: com.google.devtools.build.lib.actions.Action?,
        resolvedCacheArtifacts: MutableList<Artifact?>?,
        clientEnv: MutableMap<String?, String?>,
        outputPermissions: OutputPermissions?,
        handler: EventHandler?,
        inputMetadataProvider: InputMetadataProvider,
        outputMetadataStore: OutputMetadataStore,
        actionExecutionSalt: String?,
        outputChecker: OutputChecker?,
        useArchivedTreeArtifacts: Boolean
    ): Token? {
        if (action != null) {
            removeCacheEntry(action)
        }
        return getTokenIfNeedToExecute(
            action,
            resolvedCacheArtifacts,
            clientEnv,
            outputPermissions,
            handler,
            inputMetadataProvider,
            outputMetadataStore,
            actionExecutionSalt,
            outputChecker,
            useArchivedTreeArtifacts
        )
    }

    fun addCacheCheckSemaphoreWaitTime(waitTimeMs: Long) {
        totalCacheCheckSemaphoreWaitMillis.addAndGet(waitTimeMs)
    }

    fun getTotalCacheCheckSemaphoreWaitMillis(): Long {
        return totalCacheCheckSemaphoreWaitMillis.get()
    }

    /** Wrapper for all context needed by the ActionCacheChecker to handle a single action.  */
    class Token private constructor(action: com.google.devtools.build.lib.actions.Action) {
        /** The primary output's path, used as the key for [ActionCache] .  */
        private val cacheKey: String

        /** The result of calling [Action.getKey], or `null` if it was not called.  */
        private var actionKey: String? = null

        init {
            this.cacheKey = action.getPrimaryOutput().getExecPathString()
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private fun getCachedMetadata(
            cachedOutputMetadata: CachedOutputMetadata?, artifact: Artifact
        ): FileArtifactValue? {
            com.google.common.base.Preconditions.checkArgument(!artifact.isTreeArtifact())

            if (cachedOutputMetadata == null) {
                return null
            }

            return cachedOutputMetadata.fileMetadata.get(artifact)
        }

        private fun getCachedTreeMetadata(
            cachedOutputMetadata: CachedOutputMetadata?, artifact: Artifact
        ): TreeArtifactValue? {
            com.google.common.base.Preconditions.checkArgument(artifact.isTreeArtifact())

            if (cachedOutputMetadata == null) {
                return null
            }

            return cachedOutputMetadata.treeMetadata.get(artifact as SpecialArtifact)
        }

        /**
         * Returns whether an action cache entry is up to date.
         * 
         * @param entry action cache entry
         * @param action action to be validated.
         * @param actionKey the action key previously obtained from action.getKey()
         * @param actionInputs the action inputs; usually action.getInputs(), but might be a previously
         * cached set of discovered inputs for actions that discover them.
         * @param outputMetadataStore metadata provider for action outputs.
         * @param cachedOutputMetadata cached metadata that should be used instead of `outputMetadataStore`.
         * @param outputChecker used to check whether remote metadata should be trusted.
         * @param effectiveEnvironment the effective client environment for the action.
         * @param actionExecutionSalt the action execution salt
         * @param outputPermissions the requested output permissions
         * @param useArchivedTreeArtifacts whether archived tree artifacts are enabled.
         * @return whether the action cache entry is valid.
         */
        @Throws(java.lang.InterruptedException::class)
        private fun isUpToDate(
            entry: com.google.devtools.build.lib.actions.cache.ActionCache.Entry,
            action: com.google.devtools.build.lib.actions.Action,
            actionKey: String?,
            actionInputs: NestedSet<Artifact?>,
            inputMetadataProvider: InputMetadataProvider,
            outputMetadataStore: OutputMetadataStore,
            cachedOutputMetadata: CachedOutputMetadata?,
            outputChecker: OutputChecker?,
            effectiveEnvironment: com.google.common.collect.ImmutableMap<String?, String?>?,
            actionExecutionSalt: String?,
            outputPermissions: OutputPermissions?,
            useArchivedTreeArtifacts: Boolean
        ): Boolean {
            val builder: com.google.devtools.build.lib.actions.cache.ActionCache.Entry.Builder =
                com.google.devtools.build.lib.actions.cache.ActionCache.Entry.Builder(
                    actionKey,
                    action.discoversInputs(),
                    effectiveEnvironment,
                    actionExecutionSalt,
                    outputPermissions,
                    useArchivedTreeArtifacts
                )

            for (artifact in action.getOutputs()) {
                if (artifact.isTreeArtifact()) {
                    var treeMetadata: TreeArtifactValue? = getCachedTreeMetadata(cachedOutputMetadata, artifact)
                    if (treeMetadata == null) {
                        treeMetadata = getOutputTreeMetadataMaybe(outputMetadataStore, artifact)
                    }
                    if (treeMetadata != null
                        && shouldTrustTreeMetadata(artifact, treeMetadata, outputChecker)
                    ) {
                        builder.addOutputTree(artifact as SpecialArtifact, treeMetadata)
                    } else {
                        return false
                    }
                } else {
                    var metadata: FileArtifactValue? = getCachedMetadata(cachedOutputMetadata, artifact)
                    if (metadata == null) {
                        metadata = getOutputMetadataMaybe(outputMetadataStore, artifact)
                    }
                    if (metadata != null && shouldTrustMetadata(artifact, metadata, outputChecker)) {
                        builder.addOutputFile(artifact, metadata)
                    } else {
                        return false
                    }
                }
            }
            for (artifact in actionInputs.toList()) {
                val inputMetadata: FileArtifactValue? = getInputMetadataMaybe(inputMetadataProvider, artifact)
                builder.addInputFile(artifact, inputMetadata)
            }
            return entry.getDigest().contentEquals(builder.build().getDigest())
        }

        private fun shouldTrustMetadata(
            artifact: Artifact, metadata: FileArtifactValue?, outputChecker: OutputChecker?
        ): Boolean {
            com.google.common.base.Preconditions.checkArgument(!artifact.isTreeArtifact())
            if (outputChecker == null) {
                return true
            }
            return outputChecker.shouldTrustMetadata(artifact, metadata)
        }

        private fun shouldTrustTreeMetadata(
            artifact: Artifact, treeMetadata: TreeArtifactValue, outputChecker: OutputChecker?
        ): Boolean {
            com.google.common.base.Preconditions.checkArgument(artifact.isTreeArtifact())
            if (outputChecker == null) {
                return true
            }
            if (treeMetadata.getArchivedRepresentation().isPresent()) {
                val archivedArtifact: ArchivedTreeArtifact? =
                    treeMetadata
                        .getArchivedRepresentation()
                        .map(ArchivedRepresentation::archivedTreeFileArtifact)
                        .orElseThrow()
                val archivedMetadata: FileArtifactValue? =
                    treeMetadata
                        .getArchivedRepresentation()
                        .map(ArchivedRepresentation::archivedFileValue)
                        .orElseThrow()
                if (!outputChecker.shouldTrustMetadata(archivedArtifact, archivedMetadata)) {
                    return false
                }
            }
            for (entry in treeMetadata.getChildValues().entrySet()) {
                val child: TreeFileArtifact? = entry.key
                val childMetadata: FileArtifactValue? = entry.value
                if (!outputChecker.shouldTrustMetadata(child, childMetadata)) {
                    return false
                }
            }
            return true
        }

        private fun computeEffectiveEnvironment(
            action: com.google.devtools.build.lib.actions.Action, clientEnv: MutableMap<String?, String?>
        ): com.google.common.collect.ImmutableMap<String?, String?> {
            val builder: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
                com.google.common.collect.ImmutableMap.builder<String?, String?>()
            for (`var` in action.getClientEnvironmentVariables()) {
                val value = clientEnv.get(`var`)
                if (value != null) {
                    builder.put(`var`, value)
                }
            }
            return builder.buildKeepingLast()
        }

        @Throws(IOException::class)
        private fun getInputMetadataOrConstant(
            inputMetadataProvider: InputMetadataProvider, artifact: Artifact
        ): FileArtifactValue? {
            val metadata: FileArtifactValue? = inputMetadataProvider.getInputMetadata(artifact)
            return if (metadata != null && artifact.isConstantMetadata())
                FileArtifactValue.ConstantMetadataValue.INSTANCE
            else
                metadata
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        private fun getOutputMetadataOrConstant(
            outputMetadataStore: OutputMetadataStore, artifact: Artifact
        ): FileArtifactValue? {
            val metadata: FileArtifactValue? = outputMetadataStore.getOutputMetadata(artifact)
            return if (metadata != null && artifact.isConstantMetadata())
                FileArtifactValue.ConstantMetadataValue.INSTANCE
            else
                metadata
        }

        // TODO(ulfjack): It's unclear to me why we're ignoring all IOExceptions. In some cases, we want
        // to trigger a re-execution, so we should catch the IOException explicitly there. In others, we
        // should propagate the exception, because it is unexpected (e.g., bad file system state).
        private fun getInputMetadataMaybe(
            inputMetadataProvider: InputMetadataProvider, artifact: Artifact
        ): FileArtifactValue? {
            try {
                return getInputMetadataOrConstant(inputMetadataProvider, artifact)
            } catch (e: IOException) {
                return null
            }
        }

        // TODO(ulfjack): It's unclear to me why we're ignoring all IOExceptions. In some cases, we want
        // to trigger a re-execution, so we should catch the IOException explicitly there. In others, we
        // should propagate the exception, because it is unexpected (e.g., bad file system state).
        @Throws(java.lang.InterruptedException::class)
        private fun getOutputMetadataMaybe(
            outputMetadataStore: OutputMetadataStore, artifact: Artifact
        ): FileArtifactValue? {
            com.google.common.base.Preconditions.checkArgument(!artifact.isTreeArtifact())
            try {
                return getOutputMetadataOrConstant(outputMetadataStore, artifact)
            } catch (e: IOException) {
                return null
            }
        }

        @Throws(java.lang.InterruptedException::class)
        private fun getOutputTreeMetadataMaybe(
            outputMetadataStore: OutputMetadataStore, artifact: Artifact
        ): TreeArtifactValue? {
            com.google.common.base.Preconditions.checkArgument(artifact.isTreeArtifact())
            try {
                return outputMetadataStore.getTreeArtifactValue(artifact as SpecialArtifact)
            } catch (e: IOException) {
                return null
            }
        }

        /**
         * In most cases, this method should not be called directly - reportXXX() methods should be used
         * instead. This is done to avoid cost associated with building the message.
         */
        private fun reportRebuild(
            handler: EventHandler?,
            action: com.google.devtools.build.lib.actions.Action,
            message: String?
        ) {
            // For RunfilesTreeAction, do not report rebuild.
            if (handler != null) {
                handler.handle(
                    Event.of(
                        EventKind.DEPCHECKER,
                        null,
                        "Executing " + action.prettyPrint() + ": " + message + "."
                    )
                )
            }
        }

        private fun reportUnconditionalExecution(
            handler: EventHandler?,
            action: com.google.devtools.build.lib.actions.Action
        ) {
            reportRebuild(handler, action, "unconditional execution is requested")
        }

        private fun reportDigestMismatch(handler: EventHandler?, action: com.google.devtools.build.lib.actions.Action) {
            reportRebuild(handler, action, "action changed since cached execution")
        }

        private fun reportNewAction(handler: EventHandler?, action: com.google.devtools.build.lib.actions.Action) {
            reportRebuild(handler, action, "no entry in the cache (action is new)")
        }

        private fun reportCorruptedCacheEntry(
            handler: EventHandler?,
            action: com.google.devtools.build.lib.actions.Action
        ) {
            reportRebuild(handler, action, "cache entry is corrupted")
        }
    }
}
