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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.analysis.constraints.ConstraintConstants.getOsFromConstraintsOrHost

/** Helper class to create test actions.  */
class TestActionBuilder(ruleContext: RuleContext) {
    private val ruleContext: RuleContext
    private val additionalTools: com.google.common.collect.ImmutableList.Builder<Artifact?>
    private var runfilesSupport: RunfilesSupport? = null
    private var executable: Artifact? = null
    private var executionRequirements: ExecutionInfo? = null
    private var instrumentedFiles: InstrumentedFilesInfo? = null

    init {
        this.ruleContext = ruleContext
        this.additionalTools = com.google.common.collect.ImmutableList.Builder<Artifact?>()
    }

    /**
     * Creates the test actions and artifacts using the previously set parameters.
     * 
     * @return ordered list of test status artifacts
     */
    @Throws(java.lang.InterruptedException::class)
    fun build(): TestParams { // due to TestTargetExecutionSettings
        com.google.common.base.Preconditions.checkNotNull<RunfilesSupport?>(runfilesSupport)
        return createTestAction()
    }

    /** Set the runfiles and executable to be run as a test.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setFilesToRunProvider(provider: FilesToRunProvider): TestActionBuilder {
        com.google.common.base.Preconditions.checkNotNull<RunfilesSupport?>(provider.getRunfilesSupport())
        com.google.common.base.Preconditions.checkNotNull<Any?>(provider.getExecutable())
        this.runfilesSupport = provider.getRunfilesSupport()
        this.executable = provider.getExecutable()
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addTools(tools: MutableList<Artifact?>): TestActionBuilder {
        this.additionalTools.addAll(tools)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setInstrumentedFiles(instrumentedFiles: InstrumentedFilesInfo?): TestActionBuilder {
        this.instrumentedFiles = instrumentedFiles
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setExecutionRequirements(executionRequirements: ExecutionInfo?): TestActionBuilder {
        this.executionRequirements = executionRequirements
        return this
    }

    private fun getTestActionOwner(useTargetPlatformForTests: Boolean): ActionOwner {
        if (useTargetPlatformForTests && this.executionRequirements == null) {
            return ruleContext.getTestActionOwner()
        }
        val execGroup: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            if (this.executionRequirements != null)
                this.executionRequirements.getExecGroup()
            else
                DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME
        val owner: ActionOwner? = ruleContext.getActionOwner(execGroup)
        if (owner != null) {
            return owner
        }
        return if (useTargetPlatformForTests)
            ruleContext.getTestActionOwner()
        else
            ruleContext.getActionOwner()
    }

    /**
     * Creates a test action and artifacts for the given rule. The test action will use the specified
     * executable and runfiles.
     * 
     * @return ordered list of test artifacts, one per action. These are used to drive execution in
     * Skyframe, and by AggregatingTestListener and TestResultAnalyzer to keep track of completed
     * and pending test runs.
     */
    private fun createTestAction(): TestParams {
        val targetName: PathFragment = PathFragment.create(ruleContext.getLabel().getName())
        val config: BuildConfigurationValue? = ruleContext.getConfiguration()
        val testConfiguration: TestConfiguration = config.getFragment<T>(TestConfiguration::class.java)
        val env: AnalysisEnvironment = ruleContext.getAnalysisEnvironment()
        val root: ArtifactRoot? = ruleContext.getTestLogsDirectory()
        val actionOwner: ActionOwner =
            getTestActionOwner(
                config.getOptions().get<T?>(CoreOptions::class.java).getUseTargetPlatformForTests()
            )
        val isExecutedOnWindows =
            getOsFromConstraintsOrHost(actionOwner.getExecutionPlatform()) === OS.WINDOWS

        val inputsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        inputsBuilder.addTransitive(
            NestedSetBuilder.create(Order.STABLE_ORDER, runfilesSupport.getRunfilesTreeArtifact())
        )

        if (!isExecutedOnWindows) {
            val testRuntime: NestedSet<Artifact?>? =
                PrerequisiteArtifacts.Companion.nestedSet(
                    ruleContext.getRulePrerequisitesCollection(), "\$test_runtime"
                )
            inputsBuilder.addTransitive(testRuntime)
        }

        val testProperties: TestTargetProperties =
            TestTargetProperties(
                ruleContext, executionRequirements, actionOwner.getExecProperties()
            )

        // If the test rule does not provide InstrumentedFilesProvider, there's not much that we can do.
        val collectCodeCoverage = config.isCodeCoverageEnabled() && instrumentedFiles != null

        val testActionExecutable: Artifact =
            if (isExecutedOnWindows)
                ruleContext.getPrerequisiteArtifact("\$test_wrapper")
            else
                ruleContext.getPrerequisiteArtifact("\$test_setup_script")

        inputsBuilder.add(testActionExecutable)
        val testXmlGeneratorExecutable: Artifact =
            if (isExecutedOnWindows)
                ruleContext.getPrerequisiteArtifact("\$xml_writer")
            else
                ruleContext.getPrerequisiteArtifact("\$xml_generator_script")
        inputsBuilder.add(testXmlGeneratorExecutable)

        var collectCoverageScript: FilesToRunProvider? = null
        val coverageTestEnv: TreeMap<String?, String?> = TreeMap<String?, String?>()

        val runsPerTest = getRunsPerTest(ruleContext)
        val shardCount = getShardCount(ruleContext)

        var lcovMergerFilesToRun: NestedSet<Artifact?>? = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

        val executionSettings: TestTargetExecutionSettings?
        if (collectCodeCoverage) {
            collectCoverageScript =
                ruleContext
                    .getPrerequisite("\$collect_coverage_script")
                    .getProvider(FilesToRunProvider::class.java)
            inputsBuilder.addTransitive(collectCoverageScript.getFilesToRun())
            inputsBuilder.addTransitive(instrumentedFiles.getCoverageSupportFiles())
            // Add instrumented file manifest artifact to the list of inputs. This file will contain
            // exec paths of all source files that should be included into the code coverage output.
            val metadataFiles: NestedSet<Artifact?>? = instrumentedFiles.getInstrumentationMetadataFiles()
            inputsBuilder.addTransitive(metadataFiles)
            inputsBuilder.addTransitive(
                PrerequisiteArtifacts.Companion.nestedSet(
                    ruleContext.getRulePrerequisitesCollection(), ":coverage_support"
                )
            )
            inputsBuilder.addTransitive(
                ruleContext
                    .getPrerequisite<RunfilesProvider?>(":coverage_support", RunfilesProvider::class.java)
                    .getDataRunfiles()
                    .getAllArtifacts()
            )

            if (ruleContext.isAttrDefined("\$collect_cc_coverage", LABEL)) {
                val collectCcCoverage: Artifact = ruleContext.getPrerequisiteArtifact("\$collect_cc_coverage")
                inputsBuilder.add(collectCcCoverage)
                coverageTestEnv.put(CC_CODE_COVERAGE_SCRIPT, collectCcCoverage.getExecPathString())
            }

            if (!instrumentedFiles.getReportedToActualSources().isEmpty()) {
                val reportedToActualSourcesArtifact: Artifact =
                    ruleContext.getUniqueDirectoryArtifact(
                        "_coverage_helpers", "reported_to_actual_sources.txt"
                    )
                ruleContext.registerAction(
                    LazyWriteNestedSetOfTupleAction(
                        ruleContext.getActionOwner(),
                        reportedToActualSourcesArtifact,
                        instrumentedFiles.getReportedToActualSources(),
                        ":"
                    )
                )
                inputsBuilder.add(reportedToActualSourcesArtifact)
                coverageTestEnv.put(
                    COVERAGE_REPORTED_TO_ACTUAL_SOURCES_FILE,
                    reportedToActualSourcesArtifact.getExecPathString()
                )
            }

            // lcov is the default CC coverage tool unless otherwise specified on the command line.
            coverageTestEnv.put(BAZEL_CC_COVERAGE_TOOL, GCOV_TOOL)

            // We don't add this attribute to non-supported test target
            var lcovMergerAttr: String? = null
            if (ruleContext.isAttrDefined(":lcov_merger", LABEL)) {
                lcovMergerAttr = ":lcov_merger"
            } else if (ruleContext.isAttrDefined("\$lcov_merger", LABEL)) {
                lcovMergerAttr = "\$lcov_merger"
            }
            if (lcovMergerAttr != null) {
                val lcovMerger: TransitiveInfoCollection? = ruleContext.getPrerequisite(lcovMergerAttr)
                val lcovFilesToRun: FilesToRunProvider? = lcovMerger.getProvider(FilesToRunProvider::class.java)
                // Both executable targets and single artifacts have a FilesToRunProvider.
                if (lcovFilesToRun == null) {
                    ruleContext.attributeError(
                        lcovMergerAttr,
                        "the LCOV merger should be either an executable or a single artifact"
                    )
                }
                coverageTestEnv.put(LCOV_MERGER, lcovFilesToRun.getExecutable().getExecPathString())
                inputsBuilder.addTransitive(lcovFilesToRun.getFilesToRun())
                lcovMergerFilesToRun = lcovFilesToRun.getFilesToRun()
            }

            val instrumentedFileManifest: Artifact? =
                InstrumentedFileManifestAction.Companion.getInstrumentedFileManifest(
                    ruleContext, instrumentedFiles.getInstrumentedFiles(), metadataFiles
                )
            executionSettings =
                TestTargetExecutionSettings(
                    ruleContext,
                    runfilesSupport,
                    executable,
                    instrumentedFileManifest,
                    shardCount,
                    runsPerTest,
                    actionOwner.getExecutionPlatform()
                )
            inputsBuilder.add(instrumentedFileManifest)
            // TODO(ulfjack): Is this even ever set? If yes, does this cost us a lot of memory?
            coverageTestEnv.putAll(instrumentedFiles.getCoverageEnvironment())
        } else {
            executionSettings =
                TestTargetExecutionSettings(
                    ruleContext,
                    runfilesSupport,
                    executable,
                    null,
                    shardCount,
                    runsPerTest,
                    actionOwner.getExecutionPlatform()
                )
        }

        if (config.getRunUnder() != null) {
            val runUnderExecutable: Artifact? = executionSettings.getRunUnderExecutable()
            if (runUnderExecutable != null) {
                inputsBuilder.add(runUnderExecutable)
            }
        }

        val inputs: NestedSet<Artifact?>? = inputsBuilder.build()
        val shardRuns = (if (shardCount > 0) shardCount else 1)
        val results: MutableList<Artifact.DerivedArtifact?> =
            com.google.common.collect.Lists.newArrayListWithCapacity<Artifact.DerivedArtifact?>(runsPerTest * shardRuns)
        val coverageArtifacts: com.google.common.collect.ImmutableList.Builder<Artifact?> =
            com.google.common.collect.ImmutableList.builder<Artifact?>()
        val testOutputs: com.google.common.collect.ImmutableList.Builder<ActionInput?> =
            com.google.common.collect.ImmutableList.builder<ActionInput?>()

        // Use 1-based indices for user friendliness.
        for (shard in 0..<shardRuns) {
            val shardDir: String? =
                if (shardRuns > 1) java.lang.String.format("shard_%d_of_%d", shard + 1, shardCount) else null
            for (run in 0..<runsPerTest) {
                val dir: PathFragment
                if (runsPerTest > 1) {
                    val runDir: String? = java.lang.String.format("run_%d_of_%d", run + 1, runsPerTest)
                    if (shardDir == null) {
                        dir = targetName.getRelative(runDir)
                    } else {
                        dir = targetName.getRelative(shardDir + "_" + runDir)
                    }
                } else if (shardDir == null) {
                    dir = targetName
                } else {
                    dir = targetName.getRelative(shardDir)
                }

                val testLog: Artifact.DerivedArtifact =
                    ruleContext.getPackageRelativeArtifact(dir.getRelative("test.log"), root)
                val testXml: ActionInput? =
                    if (TEST_XML_IS_ACTION_OUTPUT)
                        ruleContext.getPackageRelativeArtifact(dir.getRelative("test.xml"), root)
                    else
                        ActionInputHelper.fromPath(
                            testLog.getExecPath().getParentDirectory().getRelative("test.xml")
                        )
                val cacheStatus: Artifact.DerivedArtifact? =
                    ruleContext.getPackageRelativeArtifact(dir.getRelative("test.cache_status"), root)

                var coverageArtifact: Artifact.DerivedArtifact? = null
                var coverageDirectory: Artifact? = null
                if (collectCodeCoverage) {
                    coverageArtifact =
                        ruleContext.getPackageRelativeArtifact(dir.getRelative("coverage.dat"), root)
                    coverageArtifacts.add(coverageArtifact)
                    if (testConfiguration.fetchAllCoverageOutputs()) {
                        coverageDirectory =
                            ruleContext.getPackageRelativeTreeArtifact(dir.getRelative("_coverage"), root)
                    }
                }

                val undeclaredOutputsDir: Artifact? =
                    ruleContext.getPackageRelativeTreeArtifact(dir.getRelative("test.outputs"), root)

                val cancelConcurrentTests: CancelConcurrentTests? =
                    if (testConfiguration.runsPerTestDetectsFlakes())
                        testConfiguration.cancelConcurrentTests()
                    else
                        CancelConcurrentTests.NEVER

                val splitCoveragePostProcessing: Boolean = testConfiguration.splitCoveragePostProcessing()
                val testRunnerAction: TestRunnerAction =
                    TestRunnerAction(
                        actionOwner,
                        inputs,
                        runfilesSupport.getRunfilesTreeArtifact(),
                        testActionExecutable,
                        testXmlGeneratorExecutable,
                        collectCoverageScript,
                        testLog,
                        testXml,
                        cacheStatus,
                        coverageArtifact,
                        coverageDirectory,
                        undeclaredOutputsDir,
                        testProperties,
                        com.google.common.collect.ImmutableMap.copyOf<String?, String?>(coverageTestEnv),
                        runfilesSupport.getActionEnvironment(),
                        executionSettings,
                        shard,
                        run,
                        config,
                        ruleContext.getWorkspaceName(),
                        if (!isExecutedOnWindows || executionSettings.needsShell())
                            ShToolchain.getPathForPlatform(
                                ruleContext.getConfiguration(), actionOwner.getExecutionPlatform()
                            )
                        else
                            null,
                        cancelConcurrentTests,
                        splitCoveragePostProcessing,
                        lcovMergerFilesToRun
                    )

                testOutputs.addAll(testRunnerAction.getSpawnOutputs())
                testOutputs.addAll(testRunnerAction.getOutputs())

                env.registerAction(testRunnerAction)

                results.add(cacheStatus)
            }
        }
        var coverageParams: CoverageParams? = null
        if (config.isCodeCoverageEnabled()) {
            // TODO(bazel-team): Passing the reportGenerator to every TestParams is a bit strange.
            // It's not enough to add this if the rule has coverage enabled because the command line may
            // contain rules with baseline coverage but no test rules that have coverage enabled, and in
            // that case, we still need the report generator.
            val reportGeneratorTarget: TransitiveInfoCollection? =
                ruleContext.getPrerequisite(":coverage_report_generator")
            val reportGenerator: FilesToRunProvider =
                reportGeneratorTarget.getProvider(FilesToRunProvider::class.java)
            if (reportGenerator.getExecutable() == null) {
                ruleContext.ruleError("--coverage_report_generator does not refer to an executable target")
            }
            coverageParams = CoverageParams(coverageArtifacts.build(), reportGenerator, actionOwner)
        }

        return TestParams(
            runsPerTest,
            shardCount,
            testConfiguration.runsPerTestDetectsFlakes(),
            TestTimeout.getTestTimeout(ruleContext.getRule()),
            ruleContext.getRule().getRuleClass(),
            com.google.common.collect.ImmutableList.copyOf<Artifact.DerivedArtifact?>(results),
            testOutputs.build(),
            coverageParams
        )
    }

    companion object {
        // Whether the test.xml is a declared output of this action rather than just an output of the test
        // spawn. True for Bazel so that it behaves properly with Build without the Bytes (i.e.,
        // --remote_download_regex), but false for Blaze because not all test rules generate a test.xml.
        // DO NOT inline this constant, as it's rewritten by Copybara on import/export.
        private const val TEST_XML_IS_ACTION_OUTPUT = true

        private const val CC_CODE_COVERAGE_SCRIPT = "CC_CODE_COVERAGE_SCRIPT"
        private const val LCOV_MERGER = "LCOV_MERGER"

        // The coverage tool Bazel uses to generate a code coverage report for C++.
        private const val BAZEL_CC_COVERAGE_TOOL = "BAZEL_CC_COVERAGE_TOOL"
        private const val GCOV_TOOL = "GCOV"

        // A file that contains a mapping between the reported source file path and the actual source
        // file path, relative to the workspace directory, if the two values are different. If the
        // reported source file is the same as the actual source path it will not be included in the file.
        private const val COVERAGE_REPORTED_TO_ACTUAL_SOURCES_FILE = "COVERAGE_REPORTED_TO_ACTUAL_SOURCES_FILE"

        /**
         * Creates test actions for a test that will never be executed.
         * 
         * 
         * This is only really useful for things like creating incompatible test actions.
         */
        fun createEmptyTestParams(): TestParams {
            return TestParams(
                0,
                0,
                false,
                TestTimeout.ETERNAL,
                "invalid",
                com.google.common.collect.ImmutableList.of<Artifact.DerivedArtifact?>(),
                com.google.common.collect.ImmutableList.of<ActionInput?>(),  /* coverageParams= */
                null
            )
        }

        fun getShardCount(ruleContext: RuleContext): Int {
            val explicitShardCount: Int =
                ruleContext.attributes().get("shard_count", Type.INTEGER).toIntUnchecked()
            val testConfiguration: TestConfiguration? =
                ruleContext.getConfiguration().getFragment<T?>(TestConfiguration::class.java)
            if (testConfiguration == null) {
                return explicitShardCount
            }

            val strategy: TestShardingStrategy = testConfiguration.testShardingStrategy()
            val result: Int = strategy.getNumberOfShards(explicitShardCount)
            com.google.common.base.Preconditions.checkState(
                result >= 0,
                "%s returned negative shard count %s",
                strategy,
                result
            )
            return result
        }

        fun getRunsPerTest(ruleContext: RuleContext): Int {
            val testConfiguration: TestConfiguration? =
                ruleContext.getConfiguration().getFragment<T?>(TestConfiguration::class.java)
            if (testConfiguration == null) {
                return 1
            }

            return testConfiguration.getRunsPerTestForLabel(ruleContext.getLabel())
        }
    }
}
