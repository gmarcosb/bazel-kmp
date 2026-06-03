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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

/** This event is fired as soon as a target is either built or fails.  */
class TargetCompleteEvent
private constructor(
    targetAndData: ConfiguredTargetAndData,
    rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?,
    completionContext: CompletionContext,
    outputs: com.google.common.collect.ImmutableMap<String, ArtifactsInOutputGroup>,
    isTest: Boolean,
    announceTargetSummary: Boolean
) : SkyValue, BuildEventWithOrderConstraint, EventReportingArtifacts, BuildEventWithConfiguration {
    /** Lightweight data needed about the configured target in this event.  */
    class ExecutableTargetData private constructor(targetAndData: ConfiguredTargetAndData) {
        private val runfilesSupport: RunfilesSupport?
        private val executable: Artifact?

        init {
            val provider: FilesToRunProvider? =
                targetAndData.getConfiguredTarget().getProvider(FilesToRunProvider::class.java)
            if (provider != null) {
                this.executable = provider.getExecutable()
                this.runfilesSupport = provider.getRunfilesSupport()
            } else {
                this.executable = null
                this.runfilesSupport = null
            }
        }

        fun getRunfilesDirectory(): Path? {
            if (runfilesSupport != null) {
                return runfilesSupport.getRunfilesDirectory()
            }
            return null
        }

        fun getExecutable(): Artifact? {
            return executable
        }
    }

    private val label: Label?
    private val configuredTargetKey: ConfiguredTargetKey?
    private val rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>
    private val postedAfter: com.google.common.collect.ImmutableList<BuildEventId?>
    private val completionContext: CompletionContext
    private val outputs: com.google.common.collect.ImmutableMap<String, ArtifactsInOutputGroup>

    // The label as appeared in the BUILD file.
    private val originalLabel: Label?
    private val isTest: Boolean
    private val announceTargetSummary: Boolean
    private val testTimeoutSeconds: Long?
    private val testParams: TestParams?
    private val configurationEvent: BuildEvent?
    private val configEventId: BuildEventId?
    private val tags: Iterable<String?>?
    private val executableTargetData: ExecutableTargetData
    private val detailedExitCode: DetailedExitCode?

    init {
        this.rootCauses =
            if (rootCauses == null) NestedSetBuilder.emptySet(Order.STABLE_ORDER) else rootCauses
        this.executableTargetData = ExecutableTargetData(targetAndData)
        val postedAfterBuilder: com.google.common.collect.ImmutableList.Builder<BuildEventId?> =
            com.google.common.collect.ImmutableList.builder<BuildEventId?>()
        this.label = targetAndData.getConfiguredTarget().getLabel()
        this.originalLabel = targetAndData.getConfiguredTarget().getOriginalLabel()
        this.configuredTargetKey =
            ConfiguredTargetKey.fromConfiguredTarget(targetAndData.getConfiguredTarget())
        postedAfterBuilder.add(BuildEventIdUtil.targetConfigured(originalLabel))
        var mostImportantDetailedExitCode: DetailedExitCode? = null
        for (cause in this.rootCauses.toList()) {
            mostImportantDetailedExitCode =
                DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                    mostImportantDetailedExitCode, cause.detailedExitCode
                )
            postedAfterBuilder.add(cause.idProto)
        }
        detailedExitCode = mostImportantDetailedExitCode
        this.completionContext = completionContext
        this.outputs = outputs
        this.isTest = isTest
        this.announceTargetSummary = announceTargetSummary
        this.testTimeoutSeconds = if (isTest) getTestTimeoutSeconds(targetAndData) else null
        val configuration: BuildConfigurationValue? = targetAndData.getConfiguration()
        this.configEventId = BuildConfigurationValue.Companion.configurationId(configuration)
        this.configurationEvent = if (configuration != null) configuration.toBuildEvent() else null
        this.testParams =
            if (isTest)
                targetAndData.getConfiguredTarget().getProvider(TestProvider::class.java).getTestParams()
            else
                null
        this.postedAfter = postedAfterBuilder.build()
        this.tags = targetAndData.getRuleTags()
    }

    /** Returns the label of the target associated with the event.  */
    fun getLabel(): Label? {
        return label
    }

    /**
     * Returns the original label of the target.
     * 
     * 
     * See [ConfiguredTarget.getOriginalLabel].
     */
    fun getOriginalLabel(): Label? {
        return originalLabel
    }

    fun getConfiguredTargetKey(): ConfiguredTargetKey? {
        return configuredTargetKey
    }

    fun getExecutableTargetData(): ExecutableTargetData {
        return executableTargetData
    }

    /** Determines whether the target has failed or succeeded.  */
    fun failed(): Boolean {
        return !rootCauses.isEmpty()
    }

    /** Get the root causes of the target. May be empty.  */
    fun getRootCauses(): NestedSet<com.google.devtools.build.lib.causes.Cause?> {
        return rootCauses
    }

    fun getLegacyFilteredImportantArtifacts(): Iterable<Artifact> {
        // TODO(ulfjack): This duplicates code in ArtifactsToBuild.
        val builder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        for (artifactsInOutputGroup in outputs.values()) {
            if (artifactsInOutputGroup.areImportant()) {
                builder.addTransitive(artifactsInOutputGroup.getArtifacts())
            }
        }
        return com.google.common.collect.Iterables.filter<T?>(
            builder.build().toList(),
            com.google.common.base.Predicate { artifact: T? -> !artifact.isSourceArtifact() && !artifact.isRunfilesTree() })
    }

    public override fun getEventId(): BuildEventId {
        return BuildEventIdUtil.targetCompleted(originalLabel, configEventId)
    }

    public override fun getChildrenEvents(): com.google.common.collect.ImmutableList<BuildEventId?> {
        val childrenBuilder: com.google.common.collect.ImmutableList.Builder<BuildEventId?> =
            com.google.common.collect.ImmutableList.builder<BuildEventId?>()
        for (cause in rootCauses.toList()) {
            childrenBuilder.add(cause.idProto)
        }
        if (isTest) {
            // For tests, announce all the test actions that will minimally happen (except for
            // interruption). If after the result of a test action another attempt is necessary,
            // it will be announced with the action that made the new attempt necessary.
            for (run in 0..<java.lang.Math.max(testParams.getRuns(), 1)) {
                for (shard in 0..<java.lang.Math.max(testParams.getShards(), 1)) {
                    childrenBuilder.add(BuildEventIdUtil.testResult(label, run, shard, configEventId))
                }
            }
            childrenBuilder.add(BuildEventIdUtil.testSummary(label, configEventId))
        }
        if (announceTargetSummary) {
            childrenBuilder.add(BuildEventIdUtil.targetSummary(originalLabel, configEventId))
        }
        return childrenBuilder.build()
    }

    fun getCompletionContext(): CompletionContext {
        return completionContext
    }

    fun getOutputGroup(outputGroup: String?): ArtifactsInOutputGroup? {
        return outputs.get(outputGroup)
    }

    public override fun referencedLocalFiles(): com.google.common.collect.ImmutableList<LocalFile?> {
        val builder: com.google.common.collect.ImmutableList.Builder<LocalFile?> =
            com.google.common.collect.ImmutableList.builder<LocalFile?>()
        for (group in outputs.values()) {
            if (group.areImportant()) {
                completionContext.visitArtifacts(
                    filterFilesets(group.getArtifacts().toList()),
                    object : ArtifactReceiver() {
                        public override fun accept(artifact: Artifact?, metadata: FileArtifactValue?) {
                            builder.add(
                                LocalFile(
                                    completionContext.pathResolver().toPath(artifact),
                                    LocalFileType.forArtifact(artifact, metadata),
                                    metadata
                                )
                            )
                        }

                        public override fun acceptFilesetMapping(fileset: Artifact?, link: FilesetOutputSymlink?) {
                            throw java.lang.IllegalStateException(fileset.toString() + " should have been filtered out")
                        }
                    })
            }
        }
        return builder.build()
    }

    public override fun asStreamProto(converters: BuildEventContext): BuildEventStreamProtos.BuildEvent {
        val builder: BuildEventStreamProtos.TargetComplete.Builder =
            BuildEventStreamProtos.TargetComplete.newBuilder()

        val failed = failed()
        builder.setSuccess(!failed)
        if (detailedExitCode != null) {
            if (!failed) {
                BugReport.sendBugReport(
                    java.lang.IllegalStateException("Detailed exit code with success? " + detailedExitCode)
                )
            }
            val failureDetail: FailureDetails.FailureDetail? = detailedExitCode.getFailureDetail()
            if (failureDetail != null) {
                builder.setFailureDetail(failureDetail)
            }
        }
        builder.addAllTag(tags).addAllOutputGroup(getOutputFilesByGroup(converters))

        if (isTest) {
            builder.setTestTimeout(Durations.fromSeconds(testTimeoutSeconds))
            builder.setTestTimeoutSeconds(testTimeoutSeconds)
        }

        val filteredImportantArtifacts: Iterable<Artifact> = getLegacyFilteredImportantArtifacts()
        for (artifact in filteredImportantArtifacts) {
            if (artifact.isDirectory()) {
                val metadata: FileArtifactValue =
                    checkNotNull(
                        completionContext.getFileArtifactValue(artifact),
                        "missing metadata for artifact: %s",
                        artifact
                    )
                builder.addDirectoryOutput(newFile(artifact, metadata))
            }
        }
        // TODO(aehlig): remove direct reporting of artifacts as soon as clients no longer need it.
        if (converters.getOptions().getLegacyImportantOutputs()) {
            Companion.addFilesDirectlyToProtoField(
                completionContext, builder, converters, filteredImportantArtifacts
            )
        }

        val complete: BuildEventStreamProtos.TargetComplete? = builder.build()
        return GenericBuildEvent.protoChaining(this).setCompleted(complete).build()
    }

    public override fun postedAfter(): com.google.common.collect.ImmutableList<BuildEventId?> {
        return postedAfter
    }

    public override fun reportedArtifacts(outputGroupFileModes: OutputGroupFileModes): ReportedArtifacts {
        return toReportedArtifacts(outputs, completionContext, outputGroupFileModes)
    }

    public override fun storeForReplay(): Boolean {
        return true
    }

    public override fun getConfigurations(): MutableCollection<BuildEvent?> {
        return if (configurationEvent != null) com.google.common.collect.ImmutableList.of<BuildEvent?>(
            configurationEvent
        ) else com.google.common.collect.ImmutableList.of<BuildEvent?>()
    }

    private fun getOutputFilesByGroup(converters: BuildEventContext): com.google.common.collect.ImmutableList<OutputGroup?> {
        return toOutputGroupProtos(outputs, completionContext, converters)
    }

    companion object {
        private val LOWERCASE_HEX_ENCODING: com.google.common.io.BaseEncoding =
            com.google.common.io.BaseEncoding.base16().lowerCase()

        /** Construct a successful target completion event.  */
        fun successfulBuild(
            ct: ConfiguredTargetAndData,
            completionContext: CompletionContext,
            outputs: com.google.common.collect.ImmutableMap<String, ArtifactsInOutputGroup>,
            announceTargetSummary: Boolean
        ): TargetCompleteEvent? {
            return TargetCompleteEvent(
                ct, null, completionContext, outputs, false, announceTargetSummary
            )
        }

        /** Construct a successful target completion event for a target that will be tested.  */
        fun successfulBuildSchedulingTest(
            ct: ConfiguredTargetAndData,
            completionContext: CompletionContext,
            outputs: com.google.common.collect.ImmutableMap<String, ArtifactsInOutputGroup>,
            announceTargetSummary: Boolean
        ): TargetCompleteEvent {
            return TargetCompleteEvent(
                ct, null, completionContext, outputs, true, announceTargetSummary
            )
        }

        /**
         * Construct a target completion event for a failed target, with the given non-empty root causes.
         */
        fun createFailed(
            ct: ConfiguredTargetAndData,
            completionContext: CompletionContext,
            rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>,
            outputs: com.google.common.collect.ImmutableMap<String, ArtifactsInOutputGroup>,
            announceTargetSummary: Boolean
        ): TargetCompleteEvent {
            com.google.common.base.Preconditions.checkArgument(!rootCauses.isEmpty())
            return TargetCompleteEvent(
                ct, rootCauses, completionContext, outputs, false, announceTargetSummary
            )
        }

        // TODO(aehlig): remove as soon as we managed to get rid of the deprecated "important_output"
        // field.
        private fun addFilesDirectlyToProtoField(
            completionContext: CompletionContext,
            builder: TargetComplete.Builder,
            converters: BuildEventContext,
            artifacts: Iterable<Artifact>
        ) {
            Companion.addFilesDirectlyToProtoField(
                completionContext, builder::addImportantOutput, converters, artifacts
            )
        }

        private fun addFilesDirectlyToProtoField(
            completionContext: CompletionContext,
            addFile: java.util.function.Consumer<BuildEventStreamProtos.File?>,
            converters: BuildEventContext,
            artifacts: Iterable<Artifact>
        ) {
            completionContext.visitArtifacts(
                filterFilesets(artifacts),
                object : ArtifactReceiver() {
                    public override fun accept(artifact: Artifact, metadata: FileArtifactValue) {
                        val uri: String? =
                            converters.pathConverter().apply(completionContext.pathResolver().toPath(artifact))
                        val file: BuildEventStreamProtos.File = newFile(artifact, metadata, uri)
                        // Omit files with unknown contents (e.g. if uploading failed).
                        if (file.getFileCase() !== BuildEventStreamProtos.File.FileCase.FILE_NOT_SET) {
                            addFile.accept(file)
                        }
                    }

                    public override fun acceptFilesetMapping(fileset: Artifact?, link: FilesetOutputSymlink?) {
                        throw java.lang.IllegalStateException(fileset.toString() + " should have been filtered out")
                    }
                })
        }

        private fun filterFilesets(artifacts: Iterable<Artifact>): Iterable<Artifact?> {
            return com.google.common.collect.Iterables.filter<Artifact?>(
                artifacts,
                com.google.common.base.Predicate { artifact: Artifact? -> !artifact.isFileset() })
        }

        /**
         * Creates a [BuildEventStreamProtos.File] proto for an artifact.
         * 
         * @param artifact the artifact
         * @param metadata the artifact's metadata
         * @param uri the artifact's URI, or null if the artifact was not uploaded
         */
        fun newFile(
            artifact: Artifact, metadata: FileArtifactValue, uri: String?
        ): BuildEventStreamProtos.File {
            return newFile(artifact.getRoot(), artifact.getRootRelativePath(), metadata, uri)
        }

        /**
         * Creates a [BuildEventStreamProtos.File] proto for an artifact.
         * 
         * 
         * Prefer calling [.newFile] if a URI is available
         * for this artifact.
         * 
         * @param artifact the artifact
         * @param metadata the artifact's metadata
         */
        fun newFile(artifact: Artifact, metadata: FileArtifactValue): BuildEventStreamProtos.File {
            return newFile(artifact, metadata,  /* uri= */null)
        }

        /**
         * Creates a [BuildEventStreamProtos.File] proto for a path.
         * 
         * 
         * Prefer calling [.newFile] if an [Artifact]
         * is available for this path.
         * 
         * @param root the root the path resides under
         * @param rootRelativePath the path relative to the root
         * @param metadata the path's metadata
         * @param uri the path's URI, or null if the artifact was not uploaded
         */
        fun newFile(
            root: ArtifactRoot,
            rootRelativePath: PathFragment,
            metadata: FileArtifactValue,
            uri: String?
        ): BuildEventStreamProtos.File {
            val file: File.Builder =
                File.newBuilder()
                    .setName(StringEncoding.internalToUnicode(rootRelativePath.getPathString()))
                    .addAllPathPrefix(
                        com.google.common.collect.Iterables.transform<F?, T?>(
                            root.getExecPath().segments(), StringEncoding::internalToUnicode
                        )
                    )
            if (metadata.getType().isSymlink()) {
                file.setSymlinkTargetPath(
                    StringEncoding.internalToUnicode(metadata.getUnresolvedSymlinkTarget())
                )
            } else if (metadata.getType().exists()) {
                val digest: ByteArray? = metadata.getDigest()
                if (digest != null) {
                    file.setDigest(LOWERCASE_HEX_ENCODING.encode(digest))
                }
                file.setLength(metadata.getSize())
            }
            if (uri != null) {
                file.setUri(StringEncoding.internalToUnicode(uri))
            }
            return file.build()
        }

        fun toReportedArtifacts(
            outputs: com.google.common.collect.ImmutableMap<String, ArtifactsInOutputGroup>,
            completionContext: CompletionContext?,
            outputGroupFileModes: OutputGroupFileModes
        ): ReportedArtifacts {
            val builder: com.google.common.collect.ImmutableSet.Builder<NestedSet<Artifact?>?> =
                com.google.common.collect.ImmutableSet.builder<NestedSet<Artifact?>?>()
            for (entry in outputs.entrySet()) {
                val groupName: String = entry.getKey()
                val mode: OutputGroupFileMode? = outputGroupFileModes.getMode(groupName)
                val artifactsInGroup: ArtifactsInOutputGroup = entry.getValue()
                if (artifactsInGroup.areImportant()) {
                    if (mode === OutputGroupFileMode.NAMED_SET_OF_FILES_ONLY
                        || mode === OutputGroupFileMode.BOTH
                    ) {
                        builder.add(artifactsInGroup.getArtifacts())
                    }
                }
            }
            return ReportedArtifacts(builder.build(), completionContext)
        }

        /** Returns [OutputGroup] protos for given output groups and optional coverage artifacts.  */
        fun toOutputGroupProtos(
            outputs: com.google.common.collect.ImmutableMap<String, ArtifactsInOutputGroup>,
            completionContext: CompletionContext,
            converters: BuildEventContext
        ): com.google.common.collect.ImmutableList<OutputGroup?> {
            val groups: com.google.common.collect.ImmutableList.Builder<OutputGroup?> =
                com.google.common.collect.ImmutableList.builder<OutputGroup?>()
            outputs.forEach(
                java.util.function.BiConsumer { outputGroup: String?, artifactsInOutputGroup: ArtifactsInOutputGroup? ->
                    if (!artifactsInOutputGroup.areImportant()) {
                        return@forEach
                    }
                    val artifacts: NestedSet<Artifact?> = artifactsInOutputGroup.getArtifacts()
                    groups.add(
                        makeOutputGroupProto(
                            completionContext,
                            converters,
                            outputGroup,
                            artifactsInOutputGroup.isIncomplete(),
                            java.util.function.Supplier { artifacts },
                            artifacts::toList
                        )
                    )
                })
            return groups.build()
        }

        /**
         * Constructs an [OutputGroup] message based on how the group has been configured to report
         * its artifacts on the command-line.
         */
        private fun makeOutputGroupProto(
            completionContext: CompletionContext,
            converters: BuildEventContext,
            outputGroup: String?,
            outputGroupIncomplete: Boolean,
            artifactsToReport: java.util.function.Supplier<NestedSet<Artifact?>?>,
            artifactListSupplier: java.util.function.Supplier<MutableList<Artifact?>?>
        ): OutputGroup {
            val builder: OutputGroup.Builder =
                OutputGroup.newBuilder().setName(outputGroup).setIncomplete(outputGroupIncomplete)
            val fileMode: OutputGroupFileMode? = converters.getFileModeForOutputGroup(outputGroup)
            if (fileMode === OutputGroupFileMode.NAMED_SET_OF_FILES_ONLY
                || fileMode === OutputGroupFileMode.BOTH
            ) {
                val namer: ArtifactGroupNamer = converters.artifactGroupNamer()
                builder.addFileSets(namer.apply(artifactsToReport.get().toNode()))
            }
            if (fileMode === OutputGroupFileMode.INLINE_ONLY || fileMode === OutputGroupFileMode.BOTH) {
                Companion.addFilesDirectlyToProtoField(
                    completionContext, builder::addInlineFiles, converters, artifactListSupplier.get()
                )
            }
            return builder.build()
        }

        /**
         * Returns timeout value in seconds that should be used for all test actions under this configured
         * target. We always use the "categorical timeouts" which are based on the --test_timeout flag. A
         * rule picks its timeout but ends up with the same effective value as all other rules in that
         * category and configuration.
         */
        private fun getTestTimeoutSeconds(targetAndData: ConfiguredTargetAndData): Long {
            val configuration: BuildConfigurationValue = targetAndData.getConfiguration()
            val categoricalTimeout: TestTimeout? = targetAndData.getTestTimeout()
            return configuration
                .getFragment<T?>(TestConfiguration::class.java)
                .getTestTimeout()
                .get(categoricalTimeout)
                .toSeconds()
        }
    }
}
