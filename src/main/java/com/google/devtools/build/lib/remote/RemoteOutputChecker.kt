// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.packages.TargetUtils.isTestRuleName

/**
 * An [OutputChecker] that decides which outputs to download taking into account the output
 * mode and the TTL of remote metadata.
 */
class RemoteOutputChecker @kotlin.jvm.JvmOverloads constructor(
    commandName: String,
    outputsMode: RemoteOutputsMode?,
    patternsToDownload: com.google.common.collect.ImmutableList<java.util.function.Predicate<String?>>,
    lastRemoteOutputChecker: RemoteOutputChecker? = null
) : OutputChecker {
    private enum class CommandMode {
        UNKNOWN,
        BUILD,
        TEST,
        RUN,
        COVERAGE
    }

    private val commandMode: CommandMode
    private val outputsMode: RemoteOutputsMode?
    private val lastRemoteOutputChecker: RemoteOutputChecker?

    private var clock: com.google.devtools.build.lib.clock.Clock? = null

    private val patternsToDownload: com.google.common.collect.ImmutableList<java.util.function.Predicate<String?>>
    private val pathsToDownload: ConcurrentArtifactPathTrie = ConcurrentArtifactPathTrie()
    private val pathsToSkip: MutableSet<PathFragment?> = ConcurrentHashMap.newKeySet<PathFragment?>()

    init {
        this.commandMode =
            when (commandName) {
                "build" -> CommandMode.BUILD
                "test" -> CommandMode.TEST
                "run" -> CommandMode.RUN
                "coverage" -> CommandMode.COVERAGE
                else -> CommandMode.UNKNOWN
            }
        this.outputsMode = outputsMode
        this.patternsToDownload = patternsToDownload
        this.lastRemoteOutputChecker = lastRemoteOutputChecker
    }

    /** Sets this checker to check the TTL of remote metadata when deciding whether to trust it.  */
    fun setCheckMetadataTtl(clock: com.google.devtools.build.lib.clock.Clock?) {
        this.clock = clock
    }

    // Skymeld-only.
    fun afterTopLevelTargetAnalysis(
        configuredTarget: ConfiguredTarget,
        topLevelArtifactContextSupplier: java.util.function.Supplier<TopLevelArtifactContext>
    ) {
        if (outputsMode == RemoteOutputsMode.ALL) {
            // For ALL, there's no need to keep track of toplevel targets - we download everything.
            return
        }
        addTopLevelTarget(configuredTarget, configuredTarget, topLevelArtifactContextSupplier)
    }

    // Skymeld-only.
    fun afterTestAnalyzedEvent(configuredTarget: ConfiguredTarget) {
        if (outputsMode == RemoteOutputsMode.ALL) {
            // For ALL, there's no need to keep track of toplevel targets - we download everything.
            return
        }
        addTargetUnderTest(configuredTarget)
    }

    // Skymeld-only.
    fun afterAspectAnalysis(
        configuredAspect: ConfiguredAspect,
        topLevelArtifactContextSupplier: java.util.function.Supplier<TopLevelArtifactContext>
    ) {
        if (outputsMode == RemoteOutputsMode.ALL) {
            // For ALL, there's no need to keep track of toplevel targets - we download everything.
            return
        }
        addTopLevelTarget(
            configuredAspect,  /* configuredTarget= */null, topLevelArtifactContextSupplier
        )
    }

    // Skymeld-only.
    fun coverageArtifactsKnown(coverageArtifacts: com.google.common.collect.ImmutableSet<Artifact>) {
        if (outputsMode == RemoteOutputsMode.ALL) {
            // For ALL, there's no need to keep track of toplevel targets - we download everything.
            return
        }
        maybeAddCoverageArtifacts(coverageArtifacts)
    }

    // Non-Skymeld only.
    fun afterAnalysis(analysisResult: AnalysisResult) {
        if (outputsMode == RemoteOutputsMode.ALL) {
            // For ALL, there's no need to keep track of toplevel targets - we download everything.
            return
        }
        for (target in analysisResult.getTargetsToBuild()) {
            addTopLevelTarget(target, target, analysisResult::getTopLevelContext)
        }
        for (aspect in analysisResult.getAspectsMap().values()) {
            addTopLevelTarget(aspect,  /* configuredTarget= */null, analysisResult::getTopLevelContext)
        }
        val targetsToTest: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            analysisResult.getTargetsToTest()
        if (targetsToTest != null) {
            for (target in targetsToTest) {
                addTargetUnderTest(target)
            }
            maybeAddCoverageArtifacts(analysisResult.getArtifactsToBuild())
        }
    }

    private fun addTopLevelTarget(
        target: ProviderCollection,
        configuredTarget: ConfiguredTarget?,
        topLevelArtifactContextSupplier: java.util.function.Supplier<TopLevelArtifactContext>
    ) {
        if (shouldAddTopLevelTarget(configuredTarget)) {
            val topLevelArtifactContext: TopLevelArtifactContext = topLevelArtifactContextSupplier.get()
            val artifactsToBuild: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                TopLevelArtifactHelper.getAllArtifactsToBuild(target, topLevelArtifactContext)
                    .getImportantArtifacts()
            addOutputsToDownload(artifactsToBuild.toList())
            // RunfileTrees are requested with this special output group. We lack access to an
            // InputMetadataProvider that can expand arbitrary RunfileTrees, so we have to mirror that
            // logic here.
            if (topLevelArtifactContext.outputGroups().contains(OutputGroupInfo.HIDDEN_TOP_LEVEL)) {
                addRunfiles(target)
            }
            addExtraActionArtifacts(target)
        }
    }

    private fun addRunfiles(buildTarget: ProviderCollection) {
        val runfilesProvider: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            buildTarget.getProvider(FilesToRunProvider::class.java)
        if (runfilesProvider == null) {
            return
        }
        val runfilesSupport: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            runfilesProvider.getRunfilesSupport()
        if (runfilesSupport == null) {
            return
        }
        val runfiles: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            runfilesSupport.getRunfiles()
        for (runfile in runfiles.getArtifacts().toList()) {
            if (mayBeRemote(runfile)) {
                addOutputToDownload(runfile)
            }
        }
        for (symlink in runfiles.getSymlinks().toList()) {
            val artifact: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                symlink.getArtifact()
            if (mayBeRemote(artifact)) {
                addOutputToDownload(artifact)
            }
        }
        for (symlink in runfiles.getRootSymlinks().toList()) {
            val artifact: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                symlink.getArtifact()
            if (mayBeRemote(artifact)) {
                addOutputToDownload(artifact)
            }
        }
    }

    private fun addExtraActionArtifacts(target: ProviderCollection) {
        val extraActionArtifactsProvider: ExtraActionArtifactsProvider? =
            target.getProvider(ExtraActionArtifactsProvider::class.java)
        if (extraActionArtifactsProvider != null) {
            addOutputsToDownload(extraActionArtifactsProvider.getExtraActionArtifacts().toList())
        }
    }

    private fun addTargetUnderTest(target: ProviderCollection) {
        val testProvider: TestProvider =
            com.google.common.base.Preconditions.checkNotNull<T>(target.getProvider(TestProvider::class.java))
        if (outputsMode != RemoteOutputsMode.MINIMAL
            && (commandMode == CommandMode.TEST || commandMode == CommandMode.COVERAGE)
        ) {
            // In test or coverage mode, download the outputs of the test runner action.
            addOutputsToDownload(testProvider.getTestParams().getOutputs())
        }
        if (commandMode == CommandMode.COVERAGE) {
            // In coverage mode, download the per-test and aggregated coverage files.
            // Do this even for MINIMAL, since coverage (unlike test) doesn't produce any observable
            // results other than outputs.
            addOutputsToDownload(testProvider.getTestParams().getCoverageArtifacts())
        }
    }

    private fun maybeAddCoverageArtifacts(artifactsToBuild: com.google.common.collect.ImmutableSet<Artifact>) {
        if (commandMode != CommandMode.COVERAGE) {
            return
        }
        for (artifactToBuild in artifactsToBuild) {
            if (artifactToBuild.getArtifactOwner().equals(CoverageReportValue.COVERAGE_REPORT_KEY)) {
                addOutputToDownload(artifactToBuild)
            }
        }
    }

    private fun addOutputsToDownload(files: Iterable<out ActionInput?>) {
        for (file in files) {
            addOutputToDownload(file)
        }
    }

    /** Marks a file for download.  */
    fun addOutputToDownload(file: ActionInput?) {
        pathsToDownload.add(file)
    }

    /**
     * Marks a file as not for download, regardless of the output mode.
     * 
     * 
     * This is used by [RemoteExecutionService] to skip downloading in-memory outputs.
     * 
     * @param execPath the exec path of the file that is not to be downloaded.
     */
    fun skipDownload(execPath: PathFragment?) {
        pathsToSkip.add(execPath)
    }

    private fun shouldAddTopLevelTarget(configuredTarget: ConfiguredTarget?): Boolean {
        return when (commandMode) {
            CommandMode.RUN -> true
            CommandMode.COVERAGE, CommandMode.TEST -> {
                // Do not download test binary in test/coverage mode.
                if (configuredTarget is RuleConfiguredTarget
                    && isTestRuleName(configuredTarget.getRuleClassString())
                ) {
                    false
                }
                outputsMode != RemoteOutputsMode.MINIMAL
            }

            else -> outputsMode != RemoteOutputsMode.MINIMAL
        }
    }

    private fun matchesPattern(execPath: PathFragment): Boolean {
        for (pattern in patternsToDownload) {
            if (pattern.test(execPath.toString())) {
                return true
            }
        }
        return false
    }

    /** Returns whether this [ActionInput] should be downloaded.  */
    public override fun shouldDownloadOutput(output: ActionInput, metadata: FileArtifactValue): Boolean {
        com.google.common.base.Preconditions.checkState(
            !(output is Artifact && output.isTreeArtifact()),
            "shouldDownloadOutput should not be called on a tree artifact"
        )
        return metadata.isRemote()
                && shouldDownloadOutput(
            output.getExecPath(),
            if (output is TreeFileArtifact)
                output.getParent().getExecPath()
            else
                null
        )
    }

    /**
     * Returns whether a remote [ActionInput] with the given path should be downloaded.
     * 
     * @param treeRootExecPath the path of the tree artifact if the given [ActionInput] is
     * contained in one
     */
    fun shouldDownloadOutput(
        execPath: PathFragment, treeRootExecPath: PathFragment?
    ): Boolean {
        if (pathsToSkip.contains(execPath)) {
            return false
        }
        return outputsMode == RemoteOutputsMode.ALL || pathsToDownload.contains(execPath)
                || matchesPattern(execPath)
                || (treeRootExecPath != null && matchesPattern(treeRootExecPath))
    }

    public override fun shouldTrustMetadata(file: ActionInput, metadata: FileArtifactValue): Boolean {
        // Local metadata is always trusted.
        if (!metadata.isRemote()) {
            return true
        }

        // If Bazel should download this file, but it does not exist locally, returns false to rerun
        // the generating action to trigger the download (just like in the normal build, when local
        // outputs are missing).
        if (lastRemoteOutputChecker != null) {
            // This is an incremental build. If the file was downloaded by previous build and is now
            // missing, invalidate the action.
            if (lastRemoteOutputChecker.shouldDownloadOutput(file, metadata)) {
                return false
            }
        }

        if (shouldDownloadOutput(file, metadata)) {
            return false
        }

        if (clock != null) {
            return isAlive(metadata)
        }

        // The remote metadata may have passed its TTL, but we are requested to optimistically assume
        // that it's still available remotely. If it isn't, build or action rewinding will take care
        // of rerunning the actions needed to produce the file and also evict the stale metadata. This
        // incurs roughly the same performance hit, but only when actually needed.
        return true
    }

    private fun isAlive(metadata: FileArtifactValue): Boolean {
        val expirationTime: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            metadata.getExpirationTime()
        return expirationTime == null || expirationTime.isAfter(clock.now())
    }

    fun maybeInvalidateSkyframeValues(memoizingEvaluator: MemoizingEvaluator) {
        if (lastRemoteOutputChecker == null) {
            return
        }

        // If the outputsMode or commandMode is changed, we invalidate completion functions. Otherwise,
        // some requested outputs might not be correctly downloaded.
        if (lastRemoteOutputChecker.outputsMode != outputsMode
            || lastRemoteOutputChecker.commandMode != commandMode
        ) {
            memoizingEvaluator.delete(
                java.util.function.Predicate { k: SkyKey? ->
                    val functionName: SkyFunctionName = k.functionName()
                    functionName == SkyFunctions.TARGET_COMPLETION
                            || functionName == SkyFunctions.ASPECT_COMPLETION
                })
        }
    }

    companion object {
        /**
         * Returns whether this [ActionInput] could conceivably be only available remotely.
         * 
         * 
         * Use this as a quick check to avoid unnecessary extra work for artifacts that are definitely
         * local.
         */
        fun mayBeRemote(actionInput: ActionInput?): Boolean {
            return !(actionInput is Artifact
                    && actionInput.isSourceArtifact() // Source artifacts in the main repo don't need to be fetched.
                    && (actionInput.getOwner() == null || actionInput.getOwner().getRepository().isMain()))
        }
    }
}
