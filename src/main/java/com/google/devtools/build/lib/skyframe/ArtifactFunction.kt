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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/**
 * A builder of values for [Artifact] keys when the key is not a simple generated artifact. To
 * save memory, ordinary generated artifacts (non-runfiles, non-tree) have their metadata accessed
 * directly from the corresponding [ActionExecutionValue]. This SkyFunction is therefore only
 * usable for source, runfiles trees and tree artifacts.
 */
class ArtifactFunction(
    mkdirForTreeArtifacts: java.util.function.Supplier<Boolean?>,
    sourceArtifactsSeen: MetadataConsumerForMetrics,
    xattrProvider: XattrProvider?,
    actionExecutor: SkyframeActionExecutor,
    cachingDependenciesSupplier: java.util.function.Supplier<RemoteAnalysisCacheReaderDepsProvider>
) : SkyFunction {
    private val mkdirForTreeArtifacts: java.util.function.Supplier<Boolean?>
    private val sourceArtifactsSeen: MetadataConsumerForMetrics
    private val xattrProvider: XattrProvider?
    private val actionExecutor: SkyframeActionExecutor
    private val cachingDependenciesSupplier: java.util.function.Supplier<RemoteAnalysisCacheReaderDepsProvider>

    /** A [SkyValue] representing a missing input file.  */
    class MissingArtifactValue private constructor(missingArtifact: Artifact) : SkyValue {
        private val detailedExitCode: DetailedExitCode

        init {
            val failureDetail: FailureDetail? =
                FailureDetail.newBuilder()
                    .setMessage(constructErrorMessage(missingArtifact, "missing input file"))
                    .setExecution(Execution.newBuilder().setCode(Code.SOURCE_INPUT_MISSING))
                    .build()
            this.detailedExitCode = DetailedExitCode.of(failureDetail)
        }

        fun getDetailedExitCode(): DetailedExitCode {
            return detailedExitCode
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).add("detailedExitCode", detailedExitCode)
                .toString()
        }
    }

    private class State : SerializableSkyKeyComputeState {
        // initialized lazily
        var retrievalContext: RetrievalContext? = null
            get() {
                if (field == null) {
                    field = RetrievalContext()
                }

                return field
            }
            private set
    }

    init {
        this.mkdirForTreeArtifacts = mkdirForTreeArtifacts
        this.sourceArtifactsSeen = sourceArtifactsSeen
        this.xattrProvider = xattrProvider
        this.actionExecutor = actionExecutor
        this.cachingDependenciesSupplier = cachingDependenciesSupplier
    }

    @Throws(ArtifactFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val artifact: Artifact = skyKey as Artifact

        if (!artifact.hasKnownGeneratingAction()) {
            // If the artifact has no known generating action, it is a source artifact and is never cached
            // remotely.
            return createSourceValue(artifact, env)
        }

        val derivedArtifact: Artifact.DerivedArtifact = artifact as DerivedArtifact

        val remoteCachingDependencies: RemoteAnalysisCacheReaderDepsProvider =
            cachingDependenciesSupplier.get()
        if (remoteCachingDependencies.mode().isRetrievalEnabled()
            && !remoteCachingDependencies.getSkycacheAnalysisOnly() && !actionExecutor.shouldSkipRetrieval(
                derivedArtifact.getGeneratingActionKey()
            )
        ) {
            when (SkyValueRetrieverUtils.retrieveRemoteSkyValue(
                artifact,
                env,
                remoteCachingDependencies,
                java.util.function.Supplier { com.google.devtools.build.lib.skyframe.ArtifactFunction.State() })) {
                -> return null
                -> return v.value
                -> {}
            }
        }

        val artifactDependencies =
            ArtifactDependencies.Companion.discoverDependencies(
                derivedArtifact,
                env,  /* crashIfActionOwnerMissing= */
                !remoteCachingDependencies
                    .mode()
                    .isRetrievalEnabled()
            )
        if (artifactDependencies == null) {
            return null
        }

        // If the action is an ActionTemplate, we need to expand the ActionTemplate into concrete
        // actions, execute those actions in parallel and then aggregate the action execution results.
        val actionTemplate: ActionTemplate<*>? = artifactDependencies.maybeGetTemplateActionForTreeArtifact()
        if (actionTemplate != null) {
            if (mkdirForTreeArtifacts.get()) {
                mkdirForTreeArtifact(artifact, env, actionTemplate)
            }
            return createTreeArtifactValueFromActionKey(artifactDependencies, env)
        }

        val generatingActionKey: ActionLookupData? = derivedArtifact.getGeneratingActionKey()
        val actionValue: ActionExecutionValue? = env.getValue(generatingActionKey) as ActionExecutionValue?
        if (actionValue == null) {
            return null
        }

        com.google.common.base.Preconditions.checkState(
            artifact.isTreeArtifact(),
            "'%s' used as a key for ArtifactFunction is not a tree artifact or a source artifact",
            artifact
        )

        // We got a request for the whole tree artifact. We can just return the associated
        // TreeArtifactValue.
        return com.google.common.base.Preconditions.checkNotNull(actionValue.getTreeArtifactValue(artifact), artifact)
    }

    @Throws(java.lang.InterruptedException::class, ArtifactFunctionException::class)
    private fun createSourceValue(artifact: Artifact, env: SkyFunction.Environment): SkyValue? {
        val path: RootedPath = RootedPath.toRootedPath(artifact.getRoot().getRoot(), artifact.getPath())
        val fileSkyKey: SkyKey? = FileValue.key(path)
        val fileValue: FileValue?
        try {
            fileValue = env.getValueOrThrow<IOException?>(fileSkyKey, IOException::class.java) as FileValue?
        } catch (e: DetailedIOException) {
            throw ArtifactFunctionException(
                SourceArtifactException.Companion.createdDetailed(artifact, e), Transience.PERSISTENT
            )
        } catch (e: IOException) {
            throw ArtifactFunctionException(
                SourceArtifactException.Companion.create(artifact, e), Transience.PERSISTENT
            )
        }
        if (fileValue == null) {
            return null
        }
        if (!fileValue.exists()) {
            return MissingArtifactValue(artifact)
        }
        if (fileValue.realFileStateValue()
                    is RegularFileStateValueWithMetadata
        ) {
            val metadata: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                valueWithMetadata.getMetadata()
            sourceArtifactsSeen.accumulate(metadata)
            return metadata
        }

        if (fileValue.isDirectory()) {
            env.getListener().post(SourceDirectoryEvent.create(artifact.getExecPath()))
        }

        if (!fileValue.isDirectory() || !TrackSourceDirectoriesFlag.trackSourceDirectories()) {
            val metadata: FileArtifactValue?
            try {
                metadata = FileArtifactValue.createForSourceArtifact(artifact, fileValue, xattrProvider)
            } catch (e: IOException) {
                throw ArtifactFunctionException(
                    SourceArtifactException.Companion.create(artifact, e), Transience.TRANSIENT
                )
            }
            sourceArtifactsSeen.accumulate(metadata)
            return metadata
        }
        // For directory artifacts that are not Filesets, we initiate a directory traversal here, and
        // compute a hash from the directory structure.
        // We rely on the guarantees of RecursiveFilesystemTraversalFunction for correctness.
        //
        // This approach may have unexpected interactions with --package_path. In particular, the exec
        // root is set up from the loading / analysis phase, and it is now too late to change it;
        // therefore, this may traverse a different set of files depending on which targets are built
        // at the same time and what the package-path layout is (this may be moot if there is only one
        // entry). Or this may return a set of files that's inconsistent with those actually available
        // to the action (for local execution).
        //
        // In the future, we need to make this result the source of truth for the files available to
        // the action so that we at least have consistency.
        val request: TraversalRequest =
            DirectoryArtifactTraversalRequest.Companion.create(
                DirectTraversalRoot.forRootedPath(path),  /* skipTestingForSubpackage= */
                true,
                artifact
            )
        val value: RecursiveFilesystemTraversalValue?
        try {
            value =
                env.getValueOrThrow<RecursiveFilesystemTraversalException?>(
                    request,
                    RecursiveFilesystemTraversalException::class.java
                ) as RecursiveFilesystemTraversalValue?
        } catch (e: RecursiveFilesystemTraversalException) {
            // Use a switch to guarantee that if a new type is added, this stops compiling.
            when (e.getType()) {
                com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.DANGLING_SYMLINK, com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.FILE_OPERATION_FAILURE, com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.SYMLINK_CYCLE_OR_INFINITE_EXPANSION, com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.CANNOT_TRAVERSE_SOURCE_DIRECTORY, com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.CANNOT_CROSS_PACKAGE_BOUNDARY -> throw ArtifactFunctionException(
                    SourceArtifactException.Companion.create(artifact, e), Transience.PERSISTENT
                )

                com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.INCONSISTENT_FILESYSTEM, com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.DETAILED_IO_EXCEPTION -> throw ArtifactFunctionException(
                    SourceArtifactException.Companion.create(artifact, e), Transience.TRANSIENT
                )

                com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.GENERATED_PATH_CONFLICT -> throw java.lang.IllegalStateException(
                    java.lang.String.format(
                        "Generated conflict in source tree: %s %s %s", artifact, fileValue, request
                    ),
                    e
                )
            }
            throw java.lang.IllegalStateException("Can't get here", e)
        }
        if (value == null) {
            return null
        }
        val fp: Fingerprint = Fingerprint()
        for (file in value.getTransitiveFiles().toList()) {
            fp.addString(file.getNameInSymlinkTree().getPathString())
            fp.addBytes(file.getMetadata().getDigest())
        }
        return FileArtifactValue.createForDirectoryWithHash(fp.digestAndReset())
    }

    override fun extractTag(skyKey: SkyKey): String {
        return Label.print((skyKey as Artifact).getOwner())
    }

    private class ArtifactFunctionException : SkyFunctionException {
        internal constructor(e: ActionExecutionException?) : super(e, Transience.TRANSIENT)

        internal constructor(e: SourceArtifactException?, transience: Transience?) : super(e, transience)
    }

    /** Describes dependencies of derived artifacts.  */
    class ArtifactDependencies private constructor(artifact: DerivedArtifact, actionLookupValue: ActionLookupValue) {
        private val artifact: DerivedArtifact
        private val actionLookupValue: ActionLookupValue

        init {
            this.artifact = artifact
            this.actionLookupValue = actionLookupValue
        }

        val isTemplateActionForTreeArtifact: Boolean
            get() = maybeGetTemplateActionForTreeArtifact() != null

        fun maybeGetTemplateActionForTreeArtifact(): ActionTemplate<*>? {
            if (!artifact.isTreeArtifact()) {
                return null
            }
            val result: ActionAnalysisMetadata? =
                actionLookupValue.getActions().get(artifact.getGeneratingActionKey().getActionIndex())
            return if (result is ActionTemplate) result as ActionTemplate<*>? else null
        }

        /**
         * Returns action template expansion keys or `null` if that information is unavailable.
         * 
         * 
         * Must only be called if [.isTemplateActionForTreeArtifact] returns `true`.
         */
        @Throws(java.lang.InterruptedException::class)
        fun getActionTemplateExpansionKeys(
            env: SkyFunction.Environment
        ): com.google.common.collect.ImmutableList<ActionLookupData?>? {
            com.google.common.base.Preconditions.checkState(
                this.isTemplateActionForTreeArtifact, "Action is unexpectedly non-template: %s", this
            )
            val key: ActionTemplateExpansionKey? =
                ActionTemplateExpansionValue.Companion.key(
                    artifact.getArtifactOwner(), artifact.getGeneratingActionKey().getActionIndex()
                )
            val value: ActionTemplateExpansionValue? = env.getValue(key) as ActionTemplateExpansionValue?
            if (value == null) {
                return null
            }
            val expandedActionExecutionKeys: com.google.common.collect.ImmutableList.Builder<ActionLookupData?> =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<E?>(value.getActions().size())
            for (action in value.getActions()) {
                // ActionTemplates expand into actions that can generate multiple output trees (as a whole),
                // but an expanded action can generate outputs under only a single tree. As such, we only
                // need to evaluate the action if it generates an output under the requested tree artifact.
                for (output in action.getOutputs()) {
                    if (output.hasParent() && output.getParent().equals(artifact)) {
                        expandedActionExecutionKeys.add((output as DerivedArtifact).getGeneratingActionKey())
                        break
                    }
                }
            }
            return expandedActionExecutionKeys.build()
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("artifact", artifact)
                .add("generatingActionKey", artifact.getGeneratingActionKey())
                .add("actionLookupValue", actionLookupValue)
                .toString()
        }

        companion object {
            /**
             * Constructs an [ArtifactDependencies] for the provided `derivedArtifact`. Returns
             * `null` if any dependencies are not yet ready.
             */
            @Throws(java.lang.InterruptedException::class)
            fun discoverDependencies(
                derivedArtifact: Artifact.DerivedArtifact,
                env: SkyFunction.Environment,
                crashIfActionOwnerMissing: Boolean
            ): ArtifactDependencies? {
                val generatingActionKey: ActionLookupData = derivedArtifact.getGeneratingActionKey()
                val actionLookupValue: ActionLookupValue? =
                    getActionLookupValue(
                        generatingActionKey.getActionLookupKey(), env, crashIfActionOwnerMissing
                    )
                if (actionLookupValue == null) {
                    return null
                }

                return ArtifactDependencies(derivedArtifact, actionLookupValue)
            }
        }
    }

    /** An [Exception] thrown representing a source input [IOException].  */
    class SourceArtifactException private constructor(detailedExitCode: DetailedExitCode, e: java.lang.Exception?) :
        java.lang.Exception(detailedExitCode.getFailureDetail().getMessage(), e), DetailedException {
        private val detailedExitCode: DetailedExitCode

        init {
            this.detailedExitCode = detailedExitCode
        }

        override fun getDetailedExitCode(): DetailedExitCode {
            return detailedExitCode
        }

        companion object {
            private fun create(artifact: Artifact, e: IOException): SourceArtifactException {
                val detailedExitCode: DetailedExitCode =
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(
                                constructErrorMessage(artifact, "error reading file") + ": " + e.getMessage()
                            )
                            .setExecution(Execution.newBuilder().setCode(Code.SOURCE_INPUT_IO_EXCEPTION))
                            .build()
                    )
                return SourceArtifactException(detailedExitCode, e)
            }

            private fun createdDetailed(
                artifact: Artifact, e: DetailedIOException
            ): SourceArtifactException {
                val failureDetailWithUpdatedErrorMessage: FailureDetail? =
                    e.getDetailedExitCode().getFailureDetail().toBuilder()
                        .setMessage(
                            constructErrorMessage(
                                artifact,
                                "error reading file: "
                                        + e.getDetailedExitCode().getFailureDetail().getMessage()
                            )
                        )
                        .build()
                return SourceArtifactException(
                    DetailedExitCode.of(failureDetailWithUpdatedErrorMessage), e
                )
            }

            private fun create(
                artifact: Artifact, e: RecursiveFilesystemTraversalException
            ): SourceArtifactException {
                val failureDetail: FailureDetail? =
                    FailureDetail.newBuilder()
                        .setMessage(
                            (constructErrorMessage(artifact, "error traversing directory")
                                    + ": "
                                    + e.getMessage())
                        )
                        .setExecution(Execution.newBuilder().setCode(Code.SOURCE_INPUT_IO_EXCEPTION))
                        .build()
                return SourceArtifactException(DetailedExitCode.of(failureDetail), e)
            }
        }
    }

    /**
     * Key for depending on all files under a source directory. Only requested when [ ].
     */
    class DirectoryArtifactTraversalRequest private constructor(
        root: DirectTraversalRoot,
        skipTestingForSubpackage: Boolean,
        artifact: Artifact
    ) : TraversalRequest() {
        private val root: DirectTraversalRoot
        private val skipTestingForSubpackage: Boolean
        private val artifact: Artifact

        init {
            this.root = root
            this.skipTestingForSubpackage = skipTestingForSubpackage
            this.artifact = artifact
        }

        override fun root(): DirectTraversalRoot {
            return root
        }

        protected val isRootGenerated: Boolean
            get() = false

        protected override fun strictOutputFiles(): Boolean {
            return true
        }

        protected override fun skipTestingForSubpackage(): Boolean {
            return skipTestingForSubpackage
        }

        protected override fun emitEmptyDirectoryNodes(): Boolean {
            return true
        }

        protected override fun errorInfo(): String {
            return "Directory artifact " + artifact.prettyPrint()
        }

        override fun duplicateWithOverrides(
            newRoot: DirectTraversalRoot, newSkipTestingForSubpackage: Boolean
        ): TraversalRequest {
            return create(newRoot, newSkipTestingForSubpackage, artifact)
        }

        val skyKeyInterner: SkyKeyInterner<DirectoryArtifactTraversalRequest?>
            get() = interner

        override fun hashCode(): Int {
            // Artifact is only for error info and not considered in hash code or equality.
            return root.hashCode() * 31 + java.lang.Boolean.hashCode(skipTestingForSubpackage)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is DirectoryArtifactTraversalRequest) {
                return false
            }
            // Artifact is only for error info and not considered in hash code or equality.
            return root == o.root && skipTestingForSubpackage == o.skipTestingForSubpackage
        }

        companion object {
            private val interner: SkyKeyInterner<DirectoryArtifactTraversalRequest?> =
                SkyKey.newInterner<DirectoryArtifactTraversalRequest?>()

            private fun create(
                root: DirectTraversalRoot, skipTestingForSubpackage: Boolean, artifact: Artifact
            ): DirectoryArtifactTraversalRequest {
                return interner.intern(
                    DirectoryArtifactTraversalRequest(root, skipTestingForSubpackage, artifact)
                )
            }
        }
    }

    companion object {
        @Throws(ArtifactFunctionException::class)
        private fun mkdirForTreeArtifact(
            artifact: Artifact, env: SkyFunction.Environment, actionForFailure: ActionTemplate<*>
        ) {
            try {
                artifact.getPath().createDirectoryAndParents()
            } catch (e: IOException) {
                val errorMessage: String? =
                    java.lang.String.format(
                        "Failed to create output directory for TreeArtifact %s: %s",
                        artifact.getExecPath(), e.getMessage()
                    )
                env.getListener()
                    .handle(
                        com.google.devtools.build.lib.events.Event.error(
                            actionForFailure.getOwner().getLocation(),
                            errorMessage
                        )
                    )
                // We could throw this as an IOException and expect our callers to catch and reprocess it,
                // but we know the action at fault, so we should be in charge.
                val code: DetailedExitCode =
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(errorMessage)
                            .setExecution(
                                Execution.newBuilder().setCode(Code.TREE_ARTIFACT_DIRECTORY_CREATION_FAILURE)
                            )
                            .build()
                    )
                throw ArtifactFunctionException(
                    ActionExecutionException(errorMessage, e, actionForFailure, false, code)
                )
            }
        }

        @Throws(java.lang.InterruptedException::class)
        private fun createTreeArtifactValueFromActionKey(
            artifactDependencies: ArtifactDependencies, env: SkyFunction.Environment
        ): TreeArtifactValue? {
            // Request the list of expanded action keys from the ActionTemplate.
            val expandedActionExecutionKeys: com.google.common.collect.ImmutableList<ActionLookupData?>? =
                artifactDependencies.getActionTemplateExpansionKeys(env)
            if (expandedActionExecutionKeys == null) {
                return null // The expanded actions are not yet available.
            }

            val expandedActionValues: SkyframeLookupResult =
                env.getValuesAndExceptions(expandedActionExecutionKeys)
            if (env.valuesMissing()) {
                return null // The execution values of the expanded actions are not yet all available.
            }

            // Aggregate the metadata for individual TreeFileArtifacts into a TreeArtifactValue for the
            // parent TreeArtifact.
            val parent: SpecialArtifact? = artifactDependencies.artifact as SpecialArtifact?
            val treeBuilder: com.google.devtools.build.lib.skyframe.TreeArtifactValue.Builder =
                TreeArtifactValue.newBuilder(parent)

            for (actionKey in expandedActionExecutionKeys) {
                var sawTreeChild = false
                val actionExecutionValue: ActionExecutionValue? =
                    expandedActionValues.get(actionKey) as ActionExecutionValue?
                if (actionExecutionValue == null) {
                    return null
                }

                for (entry in actionExecutionValue.allFileValues.entrySet()) {
                    val artifact: Artifact = entry.getKey()
                    com.google.common.base.Preconditions.checkState(
                        artifact.hasParent(),
                        "Parentless artifact %s found in ActionExecutionValue for %s: %s %s",
                        artifact,
                        actionKey,
                        actionExecutionValue,
                        artifactDependencies
                    )

                    if (artifact.getParent().equals(parent)) {
                        sawTreeChild = true
                        treeBuilder.putChild(artifact as TreeFileArtifact, entry.getValue())
                    }
                }

                for (entry in actionExecutionValue.getAllTreeArtifactValues().entrySet()) {
                    val artifact: Artifact = entry.getKey()
                    com.google.common.base.Preconditions.checkState(
                        artifact.hasParent(),
                        "Parentless artifact %s found in ActionExecutionValue for %s: %s %s",
                        artifact,
                        actionKey,
                        actionExecutionValue,
                        artifactDependencies
                    )
                    com.google.common.base.Preconditions.checkState(
                        artifact.isSubTreeArtifact(), "Artifact %s is not a subdirectory artifact", artifact
                    )

                    if (artifact.getParent().equals(parent)) {
                        sawTreeChild = true
                        // Flatten the TreeArtifactValue from subdirectories.
                        val treeArtifactValue: TreeArtifactValue =
                            com.google.common.base.Preconditions.checkNotNull<T>(
                                actionExecutionValue.getTreeArtifactValue(
                                    artifact
                                )
                            )
                        for (childEntry in treeArtifactValue.getChildValues().entrySet()) {
                            treeBuilder.putChild(childEntry.getKey(), childEntry.getValue())
                        }
                    }
                }

                com.google.common.base.Preconditions.checkState(
                    sawTreeChild,
                    "Action denoted by %s does not output any TreeFileArtifacts from %s",
                    actionKey,
                    artifactDependencies
                )
            }

            val tree: TreeArtifactValue? = treeBuilder.build()
            return tree
        }

        @Throws(java.lang.InterruptedException::class)
        fun getActionLookupValue(
            actionLookupKey: ActionLookupKey,
            env: SkyFunction.Environment,
            crashIfActionOwnerMissing: Boolean
        ): ActionLookupValue? {
            val value: ActionLookupValue? = env.getValue(actionLookupKey) as ActionLookupValue?
            if (value == null) {
                com.google.common.base.Preconditions.checkState(
                    actionLookupKey === CoverageReportValue.Companion.COVERAGE_REPORT_KEY || !crashIfActionOwnerMissing,
                    "Not-yet-present artifact owner: %s",
                    actionLookupKey.getCanonicalName()
                )
                return null
            }
            return value
        }

        private fun constructErrorMessage(artifact: Artifact, error: String?): String? {
            val ownerLabel: Label? = artifact.getOwner()
            if (ownerLabel == null || ownerLabel.name.equals(".")) {
                // Discovered inputs may not have an owner. Directory source artifacts may be owned by a label
                // ':.' which will crash toPathFragment below.
                return java.lang.String.format("%s '%s'", error, artifact.getExecPathString())
            }

            val labelFragment: PathFragment = ownerLabel.toPathFragment()
            if (ownerLabel.getRepository().isMain()) {
                if (labelFragment == artifact.getExecPath()) {
                    // No additional useful information from path.
                    return java.lang.String.format("%s '%s'", error, ownerLabel)
                }
            } else {
                // Not worth threading sibling repository layout config value all the way here: if either
                // match, we know the label isn't useful.
                for (siblingRepositoryLayout in com.google.common.collect.ImmutableList.of<Boolean?>(
                    java.lang.Boolean.FALSE,
                    java.lang.Boolean.TRUE
                )) {
                    if (ownerLabel
                            .getRepository()
                            .getExecPath(siblingRepositoryLayout)
                            .getRelative(labelFragment)
                            .equals(artifact.getExecPath())
                    ) {
                        return java.lang.String.format("%s '%s'", error, ownerLabel)
                    }
                }
            }

            // TODO(bazel-team): when is this hit?
            BugReport.sendBugReport(
                java.lang.IllegalStateException("Unexpected special owner? " + artifact + ", " + ownerLabel)
            )
            return java.lang.String.format("%s '%s', owner: '%s'", error, artifact.getExecPathString(), ownerLabel)
        }
    }
}
