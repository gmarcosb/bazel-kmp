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

import com.google.devtools.build.lib.actions.AbstractAction

/**
 * An Action representing a test with the associated environment (runfiles, environment variables,
 * test result, etc). It consumes test executable and runfiles artifacts and produces test result
 * and test status artifacts.
 */
// Not final so that we can mock it in tests.
class TestRunnerAction internal constructor(
    owner: ActionOwner?,
    inputs: NestedSet<Artifact?>?,
    runfilesTree: Artifact?,
    testSetupScript: Artifact?,  // Must be in inputs
    testXmlGeneratorScript: Artifact?,  // Must be in inputs
    collectCoverageScript: FilesToRunProvider?,  // filesToRun must be in input, if not null
    testLog: Artifact,
    testXml: ActionInput,
    cacheStatus: Artifact,
    coverageArtifact: Artifact?,
    coverageDirectory: Artifact?,
    undeclaredOutputsDir: Artifact,
    testProperties: TestTargetProperties?,
    coverageEnv: com.google.common.collect.ImmutableMap<String?, String?>,
    extraTestEnv: ActionEnvironment,
    executionSettings: TestTargetExecutionSettings?,
    shardNum: Int,
    runNumber: Int,
    configuration: BuildConfigurationValue?,
    workspaceName: String?,
    shExecutable: PathFragment?,
    cancelConcurrentTests: CancelConcurrentTests?,
    splitCoveragePostProcessing: Boolean,
    lcovMergerFilesToRun: NestedSet<Artifact?>?
) : AbstractAction(
    owner,
    inputs,
    nonNullAsSet(
        testLog,  // See TestActionBuilder.TEST_XML_IS_ACTION_OUTPUT for details.
        if (testXml is Artifact) testXml else null,
        cacheStatus,
        coverageArtifact,
        coverageDirectory,
        undeclaredOutputsDir
    )
), NotifyOnActionCacheHit, CommandAction {
    private val runfilesTree: Artifact?
    private val testSetupScript: Artifact?
    private val testXmlGeneratorScript: Artifact?
    private val collectCoverageScript: FilesToRunProvider?
    private val configuration: BuildConfigurationValue
    private val testConfiguration: TestConfiguration
    private val testLog: Artifact?
    private val testXml: ActionInput
    private val cacheStatus: Artifact
    private val testWarningsPath: PathFragment
    private val unusedRunfilesLogPath: PathFragment?
    private val shExecutable: PathFragment?
    private val splitLogsPath: PathFragment?
    private val splitLogsDir: PathFragment
    private val undeclaredOutputsAnnotationsDir: PathFragment
    private val undeclaredOutputsManifestPath: PathFragment?
    private val undeclaredOutputsAnnotationsPath: PathFragment?
    private val undeclaredOutputsAnnotationsPbPath: PathFragment?
    private val testShard: PathFragment?
    private val testExitSafe: PathFragment?
    private val testStderr: PathFragment?
    private val testInfrastructureFailure: PathFragment?
    private val baseDir: PathFragment

    private val filesToDeleteBeforeExecution: com.google.common.collect.ImmutableSet<PathFragment?>
    private val directoriesToDeleteBeforeExecution: com.google.common.collect.ImmutableSet<PathFragment?>

    private val coverageData: Artifact?
    private val coverageDirectory: Artifact?
    private val undeclaredOutputsDir: Artifact
    private val testProperties: TestTargetProperties
    private val executionSettings: TestTargetExecutionSettings
    private val shardNum: Int
    private val runNumber: Int
    private val workspaceName: String?

    /**
     * Cached test result status used to minimize disk accesses. This field is set when test status is
     * retrieved from disk or saved to disk. This field is null if it has not been set yet. This field
     * is an empty optional when the file was not present on disk or there was a failure to read it.
     */
    private var cachedTestResultData: java.util.Optional<TestResultData?>? = null

    /** Environment variables specific to running code coverage  */
    private val coverageEnv: com.google.common.collect.ImmutableMap<String?, String?>

    /** Any extra environment variables (and values) added by the rule that created this action.  */
    private val extraTestEnv: ActionEnvironment

    /**
     * The set of environment variables that are inherited from the client environment. These are
     * handled explicitly by the ActionCacheChecker and so don't have to be included in the cache key.
     */
    private val requiredClientEnvVariables: MutableCollection<String?>

    private val cancelConcurrentTests: CancelConcurrentTests?

    private val splitCoveragePostProcessing: Boolean
    private val lcovMergerFilesToRun: NestedSet<Artifact?>?


    /**
     * Create new TestRunnerAction instance. Should not be called directly. Use [ ] instead.
     * 
     * @param shardNum The shard number. Must be 0 if totalShards == 0 (no sharding). Otherwise, must
     * be >= 0 and < totalShards.
     * @param runNumber test run number
     */
    init {
        com.google.common.base.Preconditions.checkState((collectCoverageScript == null) == (coverageArtifact == null))
        this.runfilesTree = runfilesTree
        this.testSetupScript = testSetupScript
        this.testXmlGeneratorScript = testXmlGeneratorScript
        this.collectCoverageScript = collectCoverageScript
        this.configuration = com.google.common.base.Preconditions.checkNotNull<BuildConfigurationValue>(configuration)
        this.testConfiguration =
            com.google.common.base.Preconditions.checkNotNull<T>(configuration.getFragment<T?>(TestConfiguration::class.java))
        this.testLog = testLog
        this.testXml = testXml
        this.cacheStatus = cacheStatus
        this.coverageData = coverageArtifact
        this.coverageDirectory = coverageDirectory
        this.undeclaredOutputsDir = undeclaredOutputsDir
        this.shardNum = shardNum
        this.runNumber = runNumber
        this.testProperties = com.google.common.base.Preconditions.checkNotNull<TestTargetProperties>(testProperties)
        this.executionSettings =
            com.google.common.base.Preconditions.checkNotNull<TestTargetExecutionSettings>(executionSettings)

        this.baseDir = cacheStatus.getExecPath().getParentDirectory()

        val totalShards: Int = executionSettings.getTotalShards()
        com.google.common.base.Preconditions.checkState(
            (totalShards == 0 && shardNum == 0)
                    || (totalShards > 0 && 0 <= shardNum && shardNum < totalShards)
        )
        this.testExitSafe = baseDir.getChild("test.exited_prematurely")
        // testShard Path should be set only if sharding is enabled.
        this.testShard = if (totalShards > 1) baseDir.getChild("test.shard") else null
        this.testWarningsPath = baseDir.getChild("test.warnings")
        this.unusedRunfilesLogPath = baseDir.getChild("test.unused_runfiles_log")
        this.testStderr = baseDir.getChild("test.err")
        this.shExecutable = shExecutable
        this.splitLogsDir = baseDir.getChild("test.raw_splitlogs")
        // See note in {@link #getSplitLogsPath} on the choice of file name.
        this.splitLogsPath = splitLogsDir.getChild("test.splitlogs")
        this.undeclaredOutputsAnnotationsDir = baseDir.getChild("test.outputs_manifest")
        this.undeclaredOutputsManifestPath = undeclaredOutputsAnnotationsDir.getChild("MANIFEST")
        this.undeclaredOutputsAnnotationsPath = undeclaredOutputsAnnotationsDir.getChild("ANNOTATIONS")
        this.undeclaredOutputsAnnotationsPbPath =
            undeclaredOutputsAnnotationsDir.getChild("ANNOTATIONS.pb")
        this.testInfrastructureFailure = baseDir.getChild("test.infrastructure_failure")
        this.workspaceName = workspaceName

        this.coverageEnv = coverageEnv
        this.extraTestEnv = extraTestEnv
        this.requiredClientEnvVariables =
            LazySetConcatenation.Companion.from(
                configuration.getActionEnvironment().getInheritedEnv(),
                configuration.getTestActionEnvironment().getInheritedEnv(),
                this.extraTestEnv.getInheritedEnv()
            )
        this.cancelConcurrentTests = cancelConcurrentTests
        this.splitCoveragePostProcessing = splitCoveragePostProcessing
        this.lcovMergerFilesToRun = lcovMergerFilesToRun


        // Mark all possible test outputs for deletion before test execution.
        // TestRunnerAction potentially can create many more non-declared outputs - xml output, coverage
        // data file and logs for failed attempts. All those outputs are uniquely identified by the test
        // log base name with arbitrary prefix and extension.

        // We need to remove *.(xml|data|shard|warnings|zip) files if they are present.
        val filesToDeleteBuilder: com.google.common.collect.ImmutableSet.Builder<PathFragment?> =
            com.google.common.collect.ImmutableSet.builder<PathFragment?>()
                .add(
                    testWarningsPath,
                    unusedRunfilesLogPath,
                    testStderr,
                    testExitSafe,
                    testInfrastructureFailure,  // We cannot use coverageData artifact since it may be null. Generate coverage name
                    // instead.
                    baseDir.getChild("coverage.dat"),
                    baseDir.getChild("test.zip")
                ) // Delete files fetched from remote execution.
        if (testXml !is Artifact) {
            filesToDeleteBuilder.add(testXml.getExecPath())
        }
        if (testShard != null) {
            filesToDeleteBuilder.add(testShard)
        }
        this.filesToDeleteBeforeExecution = filesToDeleteBuilder.build()
        this.directoriesToDeleteBeforeExecution =
            com.google.common.collect.ImmutableSet.of<E?>( // Note that splitLogsPath points to a file inside the splitLogsDir so it's not
                // necessary to delete it explicitly.
                splitLogsDir,
                undeclaredOutputsDir.getExecPath(),
                undeclaredOutputsAnnotationsDir,
                baseDir.getRelative("test_attempts")
            )
    }

    fun allowLocalTests(): Boolean {
        return testConfiguration.allowLocalTests()
    }

    public override fun mayModifySpawnOutputsAfterExecution(): Boolean {
        // Test actions modify test spawn outputs after execution:
        // - if there are multiple attempts (unavoidable);
        // - in all cases due to appending any stray stderr output to the test log in
        //   StandaloneTestStrategy.
        // TODO: Get rid of the second case and only return true if there are multiple attempts.
        return true
    }

    fun getRunfilesTree(): Artifact? {
        return runfilesTree
    }

    public override fun getEnvironment(): ActionEnvironment? {
        return configuration.getActionEnvironment()
    }

    fun getConfiguration(): BuildConfigurationValue {
        return configuration
    }

    fun getBaseDir(): PathFragment {
        return baseDir
    }

    fun getSplitCoveragePostProcessing(): Boolean {
        return splitCoveragePostProcessing
    }

    fun getLcovMergerFilesToRun(): NestedSet<Artifact?>? {
        return lcovMergerFilesToRun
    }

    fun getCoverageDirectoryTreeArtifact(): Artifact? {
        return coverageDirectory
    }

    public override fun showsOutputUnconditionally(): Boolean {
        return true
    }

    fun getSpawnOutputs(): MutableList<ActionInput?> {
        val outputs: MutableList<ActionInput?> = java.util.ArrayList<ActionInput?>()
        outputs.add(testXml)
        outputs.add(ActionInputHelper.fromPath(getExitSafeFile()))
        if (isSharded()) {
            outputs.add(ActionInputHelper.fromPath(getTestShard()))
        }
        outputs.add(ActionInputHelper.fromPath(getTestWarningsPath()))
        outputs.add(ActionInputHelper.fromPath(getSplitLogsPath()))
        outputs.add(ActionInputHelper.fromPath(getUnusedRunfilesLogPath()))
        outputs.add(ActionInputHelper.fromPath(getInfrastructureFailureFile()))
        if (testConfiguration.getZipUndeclaredTestOutputs()) {
            outputs.add(ActionInputHelper.fromPath(getUndeclaredOutputsZipPath()))
        } else {
            outputs.add(undeclaredOutputsDir)
        }
        outputs.add(ActionInputHelper.fromPath(getUndeclaredOutputsManifestPath()))
        outputs.add(ActionInputHelper.fromPath(getUndeclaredOutputsAnnotationsPath()))
        outputs.add(ActionInputHelper.fromPath(getUndeclaredOutputsAnnotationsPbPath()))
        if (isCoverageMode()) {
            if (!splitCoveragePostProcessing) {
                outputs.add(coverageData)
            }
            if (coverageDirectory != null) {
                outputs.add(coverageDirectory)
            }
        }
        return outputs
    }

    /**
     * Returns the list of mappings from file name constants to output files. This method checks the
     * file system for existence of these output files, so it must only be used after test execution.
     */
    // TODO(ulfjack): Instead of going to local disk here, use SpawnResult (add list of files there).
    @Throws(IOException::class)
    fun getTestOutputsMapping(
        resolver: ArtifactPathResolver, execRoot: Path?
    ): com.google.common.collect.ImmutableMultimap<String?, Path?> {
        // TODO(tjgq): The existence checks below will incorrectly return false if the test action was
        // reconstructed from the action cache, as we don't populate the output filesystem on an action
        // cache hit. This is difficult to fix because some of the files below are produced by test
        // spawns, but not declared as action outputs, and only the latter are stored in the action
        // cache.
        val builder: com.google.common.collect.ImmutableMultimap.Builder<String?, Path?> =
            com.google.common.collect.ImmutableMultimap.builder<String?, Path?>()
        if (resolver.toPath(getTestLog()).exists()) {
            builder.put(TestFileNameConstants.TEST_LOG, resolver.toPath(getTestLog()))
        }
        if (getCoverageData() != null && resolver.toPath(getCoverageData()).exists()) {
            builder.put(TestFileNameConstants.TEST_COVERAGE, resolver.toPath(getCoverageData()))
        }
        if (execRoot != null) {
            val resolvedPaths = resolve(execRoot)
            if (resolvedPaths.getTestStderr().exists()) {
                builder.put(TestFileNameConstants.TEST_STDERR, resolvedPaths.getTestStderr())
            }
            if (resolvedPaths.getXmlOutputPath().exists()) {
                builder.put(TestFileNameConstants.TEST_XML, resolvedPaths.getXmlOutputPath())
            }
            if (resolvedPaths.getSplitLogsPath().exists()) {
                builder.put(TestFileNameConstants.SPLIT_LOGS, resolvedPaths.getSplitLogsPath())
            }
            if (resolvedPaths.getTestWarningsPath().exists()) {
                builder.put(TestFileNameConstants.TEST_WARNINGS, resolvedPaths.getTestWarningsPath())
            }
            if (testConfiguration.getZipUndeclaredTestOutputs()
                && resolvedPaths.getUndeclaredOutputsZipPath().exists()
            ) {
                builder.put(
                    TestFileNameConstants.UNDECLARED_OUTPUTS_ZIP,
                    resolvedPaths.getUndeclaredOutputsZipPath()
                )
            }
            if (!testConfiguration.getZipUndeclaredTestOutputs()
                && resolvedPaths.getUndeclaredOutputsDir().exists()
            ) {
                addAllFilesInUndeclaredOutputsDirectory(builder, resolvedPaths.getUndeclaredOutputsDir())
            }
            if (resolvedPaths.getUndeclaredOutputsManifestPath().exists()) {
                builder.put(
                    TestFileNameConstants.UNDECLARED_OUTPUTS_MANIFEST,
                    resolvedPaths.getUndeclaredOutputsManifestPath()
                )
            }
            if (resolvedPaths.getUndeclaredOutputsAnnotationsPath().exists()) {
                builder.put(
                    TestFileNameConstants.UNDECLARED_OUTPUTS_ANNOTATIONS,
                    resolvedPaths.getUndeclaredOutputsAnnotationsPath()
                )
            }
            if (resolvedPaths.getUndeclaredOutputsAnnotationsPbPath().exists()) {
                builder.put(
                    TestFileNameConstants.UNDECLARED_OUTPUTS_ANNOTATIONS_PB,
                    resolvedPaths.getUndeclaredOutputsAnnotationsPbPath()
                )
            }
            if (resolvedPaths.getUnusedRunfilesLogPath().exists()) {
                builder.put(
                    TestFileNameConstants.UNUSED_RUNFILES_LOG, resolvedPaths.getUnusedRunfilesLogPath()
                )
            }
            if (resolvedPaths.getInfrastructureFailureFile().exists()) {
                builder.put(
                    TestFileNameConstants.TEST_INFRASTRUCTURE_FAILURE,
                    resolvedPaths.getInfrastructureFailureFile()
                )
            }
        }
        return builder.build()
    }

    // Test actions are always distinguished by their target name, which must be unique.
    public override fun isShareable(): Boolean {
        return false
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        // TODO(b/150305897): use addUUID?
        fp.addString(GUID)
        fp.addIterableStrings(executionSettings.getArgs().arguments())
        fp.addString(com.google.common.base.Strings.nullToEmpty(executionSettings.getTestFilter()))
        fp.addBoolean(executionSettings.getTestRunnerFailFast())
        val runUnder: RunUnder? = executionSettings.getRunUnder()
        fp.addString(if (runUnder == null) "" else runUnder.value())
        fp.addStringMap(coverageEnv)
        extraTestEnv.addTo(fp)
        // TODO(ulfjack): It might be better for performance to hash the action and test envs in config,
        // and only add a hash here.
        configuration.getActionEnvironment().addTo(fp)
        configuration.getTestActionEnvironment().addTo(fp)
        // The 'requiredClientEnvVariables' are handled by Skyframe and don't need to be added here.
        fp.addString(testProperties.getSize().toString())
        fp.addString(testProperties.getTimeout().toString())
        fp.addStrings(testProperties.getTags())
        fp.addBoolean(testProperties.isRemotable())
        fp.addInt(shardNum)
        fp.addInt(executionSettings.getTotalShards())
        fp.addInt(runNumber)
        fp.addInt(executionSettings.getTotalRuns())
        fp.addBoolean(configuration.isCodeCoverageEnabled())
        fp.addBoolean(testConfiguration.getZipUndeclaredTestOutputs())
        fp.addStringMap(getExecutionInfo())
    }

    /**
     * Returns whether the test should be executed unconditionally based on the test configuration,
     * the test properties, and the previous test result when known.
     */
    public override fun executeUnconditionally(): Boolean {
        // Note: isVolatile must return true if executeUnconditionally can ever return true
        // for this instance.
        return executeUnconditionally(
            testConfiguration.cacheTestResults(),
            java.util.function.Supplier { this.maybeReadCacheStatus() },
            testProperties.isExternal(),
            executionSettings.getTotalRuns()
        )
    }

    public override fun isVolatile(): Boolean {
        return true
    }

    /** Saves cache status to disk.  */
    @Throws(IOException::class)
    fun saveCacheStatus(actionExecutionContext: ActionExecutionContext, data: TestResultData) {
        try {
            actionExecutionContext.getInputPath(cacheStatus).getOutputStream().use { out ->
                data.writeTo(out)
                // set unconditionally at the end of test action execution
                cachedTestResultData = java.util.Optional.of<TestResultData?>(data)
            }
        } catch (e: IOException) {
            cachedTestResultData = java.util.Optional.empty<TestResultData?>()
            throw e
        }
    }

    /**
     * Sets cachedTestResultData, if not already set, to the cached status from disk or empty optional
     * if the file doesn't exist or if there is an error. Then returns cachedTestResultData.
     */
    @com.google.common.annotations.VisibleForTesting
    fun maybeReadCacheStatus(): java.util.Optional<TestResultData?> {
        try {
            if (cachedTestResultData == null) {
                val testing: TestResultData = readCacheStatus()
                cachedTestResultData = java.util.Optional.of<TestResultData?>(testing)
            }
        } catch (e: FileNotFoundException) {
            cachedTestResultData = java.util.Optional.empty<TestResultData?>()
        } catch (e: IOException) {
            logger.atInfo().log("Unexpected IOException thrown while reading cached status.")
            cachedTestResultData = java.util.Optional.empty<TestResultData?>()
        }
        return com.google.common.base.Preconditions.checkNotNull<java.util.Optional<TestResultData?>>(
            cachedTestResultData
        )
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class)
    fun readCacheStatus(): TestResultData {
        cacheStatus.getPath().getInputStream().use { `in` ->
            return TestResultData.parseFrom(`in`, ExtensionRegistry.getEmptyRegistry())
        }
    }

    /**
     * Returns whether a cached result should be accepted from a disk/remote cache, depending on the
     * test configuration and test properties.
     * 
     * 
     * This should *not* be used to determine whether to accept a cached result from the action
     * cache. Call [.executeUnconditionally] instead.
     * 
     * 
     * Unlike [.executeUnconditionally], this decision does not depend on the previous test
     * result, as otherwise we wouldn't attempt to hit the disk/remote cache when the test has changed
     * from a failing to a passing state since the last execution without causing the action to be
     * reanalyzed (for example, by editing a source file into a passing state that has been previously
     * seen).
     * 
     * 
     * We're not concerned about a flaky failure becoming sticky in the disk/remote cache, because
     * it's impossible to solve this problem generally. In any case, this can only occur with a remote
     * execution implementation that caches failures, as we never upload them to a disk/remote cache
     * ourselves.
     */
    fun shouldAcceptCachedResult(): Boolean {
        return shouldAcceptCachedResult(
            testConfiguration.cacheTestResults(),
            testProperties.isExternal(),
            executionSettings.getTotalRuns()
        )
    }

    public override fun actionCacheHit(executor: ActionCachedContext): Boolean {
        maybeReadCacheStatus()
        if (cachedTestResultData.isEmpty()) {
            executor.getEventHandler()
                .handle(com.google.devtools.build.lib.events.Event.warn(getErrorMessageOnCachedTestResultError()))
            return false
        }
        try {
            val testOutputs: com.google.common.collect.ImmutableMultimap<String?, Path?> =
                getTestOutputsMapping(executor.getPathResolver(), executor.getExecRoot())
            executor
                .getEventHandler()
                .post(
                    executor
                        .getContext(TestActionContext::class.java)
                        .newCachedTestResult(
                            executor.getExecRoot(), this, cachedTestResultData.get(), testOutputs
                        )
                )
        } catch (e: IOException) {
            logger.atInfo().log("%s", getErrorMessageOnNewCachedTestResultError(e.getMessage()))
            executor
                .getEventHandler()
                .handle(com.google.devtools.build.lib.events.Event.warn(getErrorMessageOnNewCachedTestResultError(e.getMessage())))
            return false
        }
        return true
    }

    @com.google.common.annotations.VisibleForTesting
    fun getErrorMessageOnNewCachedTestResultError(exceptionMsg: String?): String {
        return getErrorMessageOnCachedTestResultError() + ": " + exceptionMsg
    }

    @com.google.common.annotations.VisibleForTesting
    fun getErrorMessageOnCachedTestResultError(): String {
        return ("Cached test status was unexpectedly unavailable on disk: could be result of"
                + " expired authentication, bad disk, or modifications in the output tree."
                + " From "
                + describe())
    }

    protected override fun getRawProgressMessage(): String {
        return "Testing " + getTestName()
    }

    protected override fun getAdditionalPathOutputsToDelete(): Iterable<PathFragment?> {
        return filesToDeleteBeforeExecution
    }

    protected override fun getDirectoryOutputsToDelete(): Iterable<PathFragment?> {
        return directoriesToDeleteBeforeExecution
    }

    @Throws(IOException::class)
    fun createEmptyOutputs(context: ActionExecutionContext) {
        for (output in this@TestRunnerAction.getOutputs()) {
            FileSystemUtils.touchFile(context.getInputPath(output))
        }
    }

    fun setupEnvVariables(env: MutableMap<String?, String?>) {
        // Allow --test_env and rules to overwite these values
        coverageEnv.forEach(java.util.function.BiConsumer { key: String?, value: String? ->
            env.putIfAbsent(
                key,
                value
            )
        })

        env.put("TEST_TARGET", Label.print(getOwner().getLabel()))
        env.put("TEST_SIZE", getTestProperties().getSize().toString())
        env.put("TEST_TIMEOUT", java.lang.Long.toString(getTimeout().toSeconds()))
        env.put("TEST_WORKSPACE", getRunfilesPrefix())
        env.put(
            "TEST_BINARY",
            getExecutionSettings()
                .getExecutable()
                .getRunfilesPath()
                .getCallablePathStringForOs(executionSettings.getExecutionOs())
        )

        // When we run test multiple times, set different TEST_RANDOM_SEED values for each run.
        // Don't override any previous setting.
        if (executionSettings.getTotalRuns() > 1 && !env.containsKey("TEST_RANDOM_SEED")) {
            env.put("TEST_RANDOM_SEED", java.lang.Integer.toString(getRunNumber() + 1))
        }
        // TODO(b/184206260): Actually set TEST_RANDOM_SEED with random seed.
        // The above TEST_RANDOM_SEED has historically been set with the run number, but we should
        // explicitly set TEST_RUN_NUMBER to indicate the run number and actually set TEST_RANDOM_SEED
        // with a random seed. However, much code has come to depend on it being set to the run number
        // and this is an externally documented behavior. Modifying TEST_RANDOM_SEED should be done
        // carefully.
        if (executionSettings.getTotalRuns() > 1 && !env.containsKey("TEST_RUN_NUMBER")) {
            env.put("TEST_RUN_NUMBER", java.lang.Integer.toString(getRunNumber() + 1))
        }

        val testFilter: String? = getExecutionSettings().getTestFilter()
        if (testFilter != null) {
            env.put(TEST_BRIDGE_TEST_FILTER_ENV, testFilter)
        }
        if (testConfiguration.getTestRunnerFailFast()) {
            env.put("TESTBRIDGE_TEST_RUNNER_FAIL_FAST", "1")
        }

        env.put("TEST_WARNINGS_OUTPUT_FILE", getTestWarningsPath().getPathString())
        env.put("TEST_UNUSED_RUNFILES_LOG_FILE", getUnusedRunfilesLogPath().getPathString())

        env.put("TEST_LOGSPLITTER_OUTPUT_FILE", getSplitLogsPath().getPathString())

        if (testConfiguration.getZipUndeclaredTestOutputs()) {
            env.put("TEST_UNDECLARED_OUTPUTS_ZIP", getUndeclaredOutputsZipPath().getPathString())
        }

        env.put("TEST_UNDECLARED_OUTPUTS_DIR", undeclaredOutputsDir.getExecPathString())
        env.put("TEST_UNDECLARED_OUTPUTS_MANIFEST", getUndeclaredOutputsManifestPath().getPathString())
        env.put(
            "TEST_UNDECLARED_OUTPUTS_ANNOTATIONS",
            getUndeclaredOutputsAnnotationsPath().getPathString()
        )
        env.put(
            "TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR",
            getUndeclaredOutputsAnnotationsDir().getPathString()
        )

        env.put("TEST_PREMATURE_EXIT_FILE", getExitSafeFile().getPathString())
        env.put("TEST_INFRASTRUCTURE_FAILURE_FILE", getInfrastructureFailureFile().getPathString())

        if (isSharded()) {
            env.put("TEST_SHARD_INDEX", java.lang.Integer.toString(getShardNum()))
            env.put("TEST_TOTAL_SHARDS", java.lang.Integer.toString(getExecutionSettings().getTotalShards()))
            env.put("TEST_SHARD_STATUS_FILE", getTestShard().getPathString())
        }
        env.put("XML_OUTPUT_FILE", testXml.getExecPathString())

        if (!configuration.runfilesEnabled()) {
            // If runfiles are disabled, tell remote-runtest.sh/local-runtest.sh about that.
            env.put("RUNFILES_MANIFEST_ONLY", "1")
        }

        if (isCoverageMode()) {
            // Instruct remote-runtest.sh/local-runtest.sh not to cd into the runfiles directory.
            // TODO(ulfjack): Find a way to avoid setting this variable.
            env.put("RUNTEST_PRESERVE_CWD", "1")

            env.put("COVERAGE_MANIFEST", getCoverageManifest().getExecPathString())
            env.put("COVERAGE_DIR", getCoverageDirectory().getPathString())
            env.put("COVERAGE_OUTPUT_FILE", getCoverageData().getExecPathString())
            env.put("SPLIT_COVERAGE_POST_PROCESSING", if (splitCoveragePostProcessing) "1" else "0")
            env.put("IS_COVERAGE_SPAWN", "0")
        }
    }

    /**
     * Gets the test name in a user-friendly format. Will generally include the target name and
     * run/shard numbers, if applicable.
     */
    fun getTestName(): String {
        val suffix = getTestSuffix()
        val label: String = Label.print(getOwner().getLabel())
        return if (suffix.isEmpty()) label else label + " " + suffix
    }

    /**
     * Gets the test suffix in a user-friendly format, eg "(shard 1 of 7)". Will include the target
     * name and run/shard numbers, if applicable.
     */
    fun getTestSuffix(): String {
        val totalShards: Int = executionSettings.getTotalShards()
        // Use a 1-based index for user friendliness.
        val runsPerTest: Int = executionSettings.getTotalRuns()
        if (totalShards > 1 && runsPerTest > 1) {
            return java.lang.String.format(
                "(shard %d of %d, run %d of %d)", shardNum + 1, totalShards, runNumber + 1, runsPerTest
            )
        } else if (totalShards > 1) {
            return java.lang.String.format("(shard %d of %d)", shardNum + 1, totalShards)
        } else if (runsPerTest > 1) {
            return java.lang.String.format("(run %d of %d)", runNumber + 1, runsPerTest)
        } else {
            return ""
        }
    }

    /** Returns the timeout for this test action, respecting the value of `--test_timeout`.  */
    fun getTimeout(): java.time.Duration {
        return testConfiguration.getTestTimeout().get(testProperties.getTimeout())
    }

    fun getTestLog(): Artifact? {
        return testLog
    }

    fun getTestXml(): ActionInput {
        return testXml
    }

    /** Returns all environment variables which must be set in order to run this test.  */
    fun getExtraTestEnv(): ActionEnvironment {
        return extraTestEnv
    }

    public override fun getClientEnvironmentVariables(): MutableCollection<String?> {
        return requiredClientEnvVariables
    }

    fun resolve(execRoot: Path?): ResolvedPaths {
        return ResolvedPaths(execRoot)
    }

    fun getCacheStatusArtifact(): Artifact {
        return cacheStatus
    }

    fun getTestStderrPath(): PathFragment? {
        return testStderr
    }

    fun getTestWarningsPath(): PathFragment {
        return testWarningsPath
    }

    fun getUnusedRunfilesLogPath(): PathFragment? {
        return unusedRunfilesLogPath
    }

    fun getSplitLogsPath(): PathFragment? {
        return splitLogsPath
    }

    fun getUndeclaredOutputsDir(): Artifact {
        return undeclaredOutputsDir
    }

    /** Returns path to the optional zip file of undeclared test outputs.  */
    fun getUndeclaredOutputsZipPath(): PathFragment {
        return undeclaredOutputsDir.getExecPath().getChild(UNDECLARED_OUTPUTS_ZIP_NAME)
    }

    /** Returns path to the undeclared output manifest file.  */
    fun getUndeclaredOutputsManifestPath(): PathFragment? {
        return undeclaredOutputsManifestPath
    }

    fun getUndeclaredOutputsAnnotationsDir(): PathFragment {
        return undeclaredOutputsAnnotationsDir
    }

    /** Returns path to the undeclared output annotations file.  */
    fun getUndeclaredOutputsAnnotationsPath(): PathFragment? {
        return undeclaredOutputsAnnotationsPath
    }

    /** Returns path to the undeclared output annotations file.  */
    fun getUndeclaredOutputsAnnotationsPbPath(): PathFragment? {
        return undeclaredOutputsAnnotationsPbPath
    }

    fun getTestShard(): PathFragment? {
        return testShard
    }

    fun getExitSafeFile(): PathFragment? {
        return testExitSafe
    }

    fun getInfrastructureFailureFile(): PathFragment? {
        return testInfrastructureFailure
    }

    /** Returns coverage data artifact or null if code coverage was not requested.  */
    fun getCoverageData(): Artifact? {
        return coverageData
    }

    fun getCoverageManifest(): Artifact? {
        return getExecutionSettings().getInstrumentedFileManifest()
    }

    /** Returns true if coverage data should be gathered.  */
    fun isCoverageMode(): Boolean {
        return coverageData != null
    }

    /**
     * Returns a directory to temporarily store coverage results for the given action relative to the
     * execution root. This directory is used to store all coverage results related to the test
     * execution with exception of the locally generated *.gcda files. Those are stored separately
     * using relative path within coverage directory.
     * 
     * 
     * If the coverageDirectory field is set, then its exec path is returned. This is a tree
     * artifact, meaning that all files in the corresponding directories are returned from sandboxed
     * or remote execution.
     * 
     * 
     * Otherwise, the directory name for the given test runner action is constructed as: `[blaze-out/.../testlogs/]_coverage/target_path/test_log_name` where `test_log_name` is
     * usually a target name but potentially can include extra suffix, such as a shard number (if test
     * execution was sharded).
     */
    fun getCoverageDirectory(): PathFragment {
        if (coverageDirectory != null) {
            return coverageDirectory.getExecPath()
        }
        val coverageRoot: PathFragment = getTestLog().getRoot().getExecPath().getRelative(COVERAGE_TMP_ROOT)
        return coverageRoot.getRelative(
            FileSystemUtils.removeExtension(getTestLog().getRootRelativePath())
        )
    }

    fun getTestProperties(): TestTargetProperties {
        return testProperties
    }

    public override fun getExecutionInfo(): com.google.common.collect.ImmutableMap<String?, String?>? {
        return testProperties.getExecutionInfo()
    }

    fun getExecutionSettings(): TestTargetExecutionSettings {
        return executionSettings
    }

    fun isSharded(): Boolean {
        return testShard != null
    }

    /**
     * Returns the shard number for this action. If getTotalShards() > 0, must be >= 0 and <
     * getTotalShards(). Otherwise, must be 0.
     */
    fun getShardNum(): Int {
        return shardNum
    }

    /** Returns run number.  */
    fun getRunNumber(): Int {
        return runNumber
    }

    /** Returns the workspace name.  */
    fun getRunfilesPrefix(): String? {
        return workspaceName
    }


    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    public override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
        val context: TestActionContext = actionExecutionContext.getContext(TestActionContext::class.java)
        return execute(actionExecutionContext, context)
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    fun execute(
        actionExecutionContext: ActionExecutionContext?, testActionContext: TestActionContext
    ): ActionResult {
        val spawnResults: MutableList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
        val failedAttempts: MutableList<ProcessedAttemptResult?> = java.util.ArrayList<ProcessedAttemptResult?>()
        var testRunnerSpawn: TestRunnerSpawn? = null
        var attemptGroup: AttemptGroup? = null

        try {
            try {
                testRunnerSpawn = testActionContext.createTestRunnerSpawn(this, actionExecutionContext)
                attemptGroup =
                    if (cancelConcurrentTests !== CancelConcurrentTests.NEVER)
                        testActionContext.getAttemptGroup(getOwner(), shardNum)
                    else
                        AttemptGroup.Companion.NOOP
                val cancelOnResult: com.google.devtools.build.lib.analysis.test.TestActionContext.TestAttemptResult.Result? =
                    when (cancelConcurrentTests) {
                        NEVER -> null
                        ON_FAILED -> com.google.devtools.build.lib.analysis.test.TestActionContext.TestAttemptResult.Result.FAILED_CAN_RETRY
                        ON_PASSED -> com.google.devtools.build.lib.analysis.test.TestActionContext.TestAttemptResult.Result.PASSED
                    }
                try {
                    attemptGroup.register()
                    val result: ActionResult =
                        executeAllAttempts(
                            testRunnerSpawn,
                            testActionContext.isTestKeepGoing(),
                            cancelOnResult,
                            attemptGroup,
                            spawnResults,
                            failedAttempts
                        )

                    // If the current test attempt is requested to be cancelled after it has finished, we need
                    // to handle the interruption here and clear the interrupted status. Otherwise, the
                    // interrupted status will be propagated to skyframe and the whole invocation will be
                    // cancelled.
                    if (java.lang.Thread.interrupted()) {
                        throw java.lang.InterruptedException()
                    }

                    return result
                } finally {
                    attemptGroup.unregister()
                }
            } catch (e: java.lang.InterruptedException) {
                if (!attemptGroup.cancelled()) {
                    throw e
                }

                testRunnerSpawn.finalizeCancelledTest(failedAttempts)
                createEmptyOutputs(testRunnerSpawn.getActionExecutionContext())
                return ActionResult.create(spawnResults)
            }
        } catch (e: ExecException) {
            throw ActionExecutionException.fromExecException(e, this)
        } catch (e: IOException) {
            throw ActionExecutionException.fromExecException(
                EnvironmentalExecException(e, Code.TEST_RUNNER_IO_EXCEPTION), this
            )
        }
    }

    public override fun getMnemonic(): String {
        return MNEMONIC
    }

    public override fun getMandatoryOutputs(): com.google.common.collect.ImmutableSet<Artifact?> {
        return com.google.common.collect.ImmutableSet.copyOf(getOutputs())
    }

    fun getTestSetupScript(): Artifact? {
        return testSetupScript
    }

    fun getTestXmlGeneratorScript(): Artifact? {
        return testXmlGeneratorScript
    }

    fun getCollectCoverageScript(): FilesToRunProvider? {
        return collectCoverageScript
    }

    fun getShExecutableMaybe(): PathFragment? {
        return shExecutable
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    public override fun getArguments(): MutableList<String?> {
        return TestStrategy.Companion.expandedArgsFromAction(this)
    }

    @Throws(ActionExecutionException::class)
    public override fun getIncompleteEnvironmentForTesting(): com.google.common.collect.ImmutableMap<String?, String?> {
        return getEnvironment().getFixedEnv()
    }

    public override fun getPossibleInputsForTesting(): NestedSet<Artifact?> {
        return getInputs()
    }

    /** The same set of paths as the parent test action, resolved against a given exec root.  */
    inner class ResolvedPaths internal constructor(execRoot: Path?) {
        private val execRoot: Path

        init {
            this.execRoot = com.google.common.base.Preconditions.checkNotNull<Path>(execRoot)
        }

        private fun getPath(relativePath: PathFragment?): Path {
            return execRoot.getRelative(relativePath)
        }

        fun getBaseDir(): Path {
            return getPath(baseDir)
        }

        /**
         * In rare cases, error messages will be printed to stderr instead of stdout. The test action is
         * responsible for appending anything in the stderr file to the real test.log.
         */
        fun getTestStderr(): Path {
            return getPath(testStderr)
        }

        fun getTestWarningsPath(): Path {
            return getPath(testWarningsPath)
        }

        fun getSplitLogsPath(): Path {
            return getPath(splitLogsPath)
        }

        fun getUnusedRunfilesLogPath(): Path {
            return getPath(unusedRunfilesLogPath)
        }

        /** Returns path to the directory containing the split logs (raw and proto file).  */
        fun getSplitLogsDir(): Path {
            return getPath(splitLogsDir)
        }

        /** Returns path to the optional zip file of undeclared test outputs.  */
        fun getUndeclaredOutputsZipPath(): Path {
            return getUndeclaredOutputsDir().getChild(UNDECLARED_OUTPUTS_ZIP_NAME)
        }

        /** Returns path to the directory to hold undeclared test outputs.  */
        fun getUndeclaredOutputsDir(): Path {
            return getPath(undeclaredOutputsDir.getExecPath())
        }

        /** Returns path to the directory to hold undeclared output annotations parts.  */
        fun getUndeclaredOutputsAnnotationsDir(): Path {
            return getPath(undeclaredOutputsAnnotationsDir)
        }

        /** Returns path to the undeclared output manifest file.  */
        fun getUndeclaredOutputsManifestPath(): Path {
            return getPath(undeclaredOutputsManifestPath)
        }

        /** Returns path to the undeclared output annotations file.  */
        fun getUndeclaredOutputsAnnotationsPath(): Path {
            return getPath(undeclaredOutputsAnnotationsPath)
        }

        /** Returns path to the undeclared output annotations pb file.  */
        fun getUndeclaredOutputsAnnotationsPbPath(): Path {
            return getPath(undeclaredOutputsAnnotationsPbPath)
        }

        fun getTestShard(): Path? {
            return if (testShard == null) null else getPath(testShard)
        }

        fun getExitSafeFile(): Path {
            return getPath(testExitSafe)
        }

        fun getInfrastructureFailureFile(): Path {
            return getPath(testInfrastructureFailure)
        }

        /** Returns path to the optionally created XML output file created by the test.  */
        fun getXmlOutputPath(): Path {
            return getPath(testXml.getExecPath())
        }

        fun getCoverageDirectory(): Path {
            return getPath(this@TestRunnerAction.getCoverageDirectory())
        }

        fun getCoverageDataPath(): Path {
            return getPath(getCoverageData().getExecPath())
        }
    }

    @Throws(ExecException::class, IOException::class, java.lang.InterruptedException::class)
    fun executeAllAttempts(
        testRunnerSpawn: TestRunnerSpawn,
        keepGoing: Boolean,
        cancelOnResult: com.google.devtools.build.lib.analysis.test.TestActionContext.TestAttemptResult.Result?,
        attemptGroup: AttemptGroup,
        spawnResults: MutableList<SpawnResult?>,
        failedAttempts: MutableList<ProcessedAttemptResult?>
    ): ActionResult {
        var testRunnerSpawn: TestRunnerSpawn = testRunnerSpawn
        var maxAttempts = 0

        while (true) {
            val result: TestAttemptResult = testRunnerSpawn.execute()
            val actualMaxAttempts =
                if (failedAttempts.isEmpty()) testRunnerSpawn.getMaxAttempts(result) else maxAttempts
            com.google.common.base.Preconditions.checkState(actualMaxAttempts != 0)

            spawnResults.addAll(result.spawnResults())
            val testResult: com.google.devtools.build.lib.analysis.test.TestActionContext.TestAttemptResult.Result =
                result.result()
            if (testResult == cancelOnResult) {
                attemptGroup.cancelOthers()
            }
            if (testResult != com.google.devtools.build.lib.analysis.test.TestActionContext.TestAttemptResult.Result.PASSED) {
                val nextRunnerAndAttempts =
                    Companion.computeNextRunnerAndMaxAttempts(
                        testResult,
                        testRunnerSpawn,
                        failedAttempts.size() + 1,
                        actualMaxAttempts,
                        spawnResults
                    )
                if (nextRunnerAndAttempts != null) {
                    failedAttempts.add(
                        testRunnerSpawn.finalizeFailedTestAttempt(result, failedAttempts.size() + 1)
                    )

                    // Change the phase here because we are executing a rerun of the failed attempt.
                    testRunnerSpawn
                        .getActionExecutionContext()
                        .getEventHandler()
                        .post(ChangePhase(this))

                    testRunnerSpawn = nextRunnerAndAttempts.spawn
                    maxAttempts = nextRunnerAndAttempts.maxAttempts
                    continue
                }
            }
            testRunnerSpawn.finalizeTest(result, failedAttempts)

            if (!keepGoing && testResult != com.google.devtools.build.lib.analysis.test.TestActionContext.TestAttemptResult.Result.PASSED) {
                val systemFailure: DetailedExitCode? = result.primarySystemFailure()
                if (systemFailure != null) {
                    throw TestExecException(
                        "Test failed (system error), aborting: "
                                + systemFailure.getFailureDetail().getMessage(),
                        systemFailure.getFailureDetail()
                    )
                }
                val errorMessage = "Test failed, aborting"
                throw TestExecException(
                    errorMessage,
                    FailureDetail.newBuilder()
                        .setTestAction(
                            TestAction.newBuilder().setCode(TestAction.Code.NO_KEEP_GOING_TEST_FAILURE)
                        )
                        .setMessage(errorMessage)
                        .build()
                )
            }
            return ActionResult.create(spawnResults)
        }
    }

    /** Value type used to store computed next runner and max attempts.  */
    @com.google.common.annotations.VisibleForTesting
    internal class TestRunnerSpawnAndMaxAttempts(spawn: TestRunnerSpawn?, val maxAttempts: Int) {
        val spawn: TestRunnerSpawn?

        init {
            this.spawn = spawn
            java.util.Objects.requireNonNull<TestRunnerSpawn?>(spawn, "spawn")
        }

        companion object {
            fun create(spawn: TestRunnerSpawn?, maxAttempts: Int): TestRunnerSpawnAndMaxAttempts {
                return TestRunnerSpawnAndMaxAttempts(spawn, maxAttempts)
            }
        }
    }

    private class LazySetConcatenation(
        first: com.google.common.collect.ImmutableSet<String?>,
        second: com.google.common.collect.ImmutableSet<String?>,
        third: com.google.common.collect.ImmutableSet<String?>
    ) : AbstractCollection<String?>() {
        private val first: com.google.common.collect.ImmutableSet<String?>
        private val second: com.google.common.collect.ImmutableSet<String?>
        private val third: com.google.common.collect.ImmutableSet<String?>

        init {
            this.first = first
            this.second = second
            this.third = third
        }

        override fun iterator(): MutableIterator<String?> {
            return com.google.common.collect.Iterators.concat<String?>(
                first.iterator(),
                second.iterator(),
                third.iterator()
            )
        }

        override fun size(): Int {
            return first.size() + second.size() + third.size()
        }

        override fun isEmpty(): Boolean {
            return false
        }

        companion object {
            fun from(
                first: com.google.common.collect.ImmutableSet<String?>,
                second: com.google.common.collect.ImmutableSet<String?>,
                third: com.google.common.collect.ImmutableSet<String?>
            ): MutableCollection<String?> {
                val firstEmpty: Boolean = first.isEmpty()
                val secondEmpty: Boolean = second.isEmpty()
                val thirdEmpty: Boolean = third.isEmpty()
                if (firstEmpty && secondEmpty) {
                    return third
                }
                if (firstEmpty && thirdEmpty) {
                    return second
                }
                if (secondEmpty && thirdEmpty) {
                    return first
                }

                return LazySetConcatenation(first, second, third)
            }
        }
    }

    companion object {
        val COVERAGE_TMP_ROOT: PathFragment? = PathFragment.create("_coverage")

        private const val UNDECLARED_OUTPUTS_ZIP_NAME = "outputs.zip"

        // Used for selecting subset of testcase / testmethods.
        private const val TEST_BRIDGE_TEST_FILTER_ENV = "TESTBRIDGE_TEST_ONLY"

        private const val GUID = "cc41f9d0-47a6-11e7-8726-eb6ce83a8cc8"
        const val MNEMONIC: String = "TestRunner"

        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private fun nonNullAsSet(vararg artifacts: Artifact?): com.google.common.collect.ImmutableSet<Artifact?> {
            val builder: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
                com.google.common.collect.ImmutableSet.builder<Artifact?>()
            for (artifact in artifacts) {
                if (artifact != null) {
                    builder.add(artifact)
                }
            }
            return builder.build()
        }

        @Throws(IOException::class)
        private fun addAllFilesInUndeclaredOutputsDirectory(
            builder: com.google.common.collect.ImmutableMultimap.Builder<String?, Path?>, undeclaredOutputsDir: Path?
        ) {
            val dirsToVisit: ArrayDeque<Path> = ArrayDeque<Path>()
            dirsToVisit.add(undeclaredOutputsDir)
            while (!dirsToVisit.isEmpty()) {
                val dir: Path = dirsToVisit.pop()
                val sortedEntries: MutableList<Dirent> = java.util.ArrayList<Any?>(dir.readdir(Symlinks.FOLLOW))
                sortedEntries.sort(java.util.Comparator.comparing<Dirent?, Any?>(Dirent::getName))
                for (dirent in sortedEntries) {
                    val child: Path = dir.getChild(dirent.getName())
                    if (dirent.getType().equals(Dirent.Type.DIRECTORY)) {
                        dirsToVisit.add(child)
                    } else if (dirent.getType().equals(Dirent.Type.FILE)) {
                        val name =
                            (TestFileNameConstants.UNDECLARED_OUTPUTS_DIR
                                    + "/"
                                    + child.relativeTo(undeclaredOutputsDir))
                        builder.put(name, child)
                    }
                }
            }
        }

        @com.google.common.annotations.VisibleForTesting
        fun executeUnconditionally(
            cacheTestResults: com.google.devtools.common.options.TriState?,
            prevStatus: java.util.function.Supplier<java.util.Optional<TestResultData?>>,  // lazy to avoid I/O if possible
            isExternal: Boolean,
            runsPerTest: Int
        ): Boolean {
            if (!shouldAcceptCachedResult(cacheTestResults, isExternal, runsPerTest)) {
                return true
            }
            val status: java.util.Optional<TestResultData?> = prevStatus.get()
            if (status.isEmpty()) {
                // Execute unconditionally if a previous test result is not available.
                return true
            }
            if (!status.get().getCachable()) {
                // Execute unconditionally if the previous test result was marked non-cacheable.
                // It seems that this can only happen with --experimental_cancel_concurrent_tests.
                return true
            }
            if (cacheTestResults == com.google.devtools.common.options.TriState.AUTO && !status.get().getTestPassed()) {
                // Execute unconditionally if the previous test result was a failure, as otherwise we can
                // get stuck forever in the event of a flaky failure.
                return true
            }
            return false
        }

        @com.google.common.annotations.VisibleForTesting
        fun shouldAcceptCachedResult(
            cacheTestResults: com.google.devtools.common.options.TriState?, isExternal: Boolean, runsPerTest: Int
        ): Boolean {
            if (isExternal || cacheTestResults == com.google.devtools.common.options.TriState.NO) {
                return false
            }
            if (cacheTestResults == com.google.devtools.common.options.TriState.AUTO && runsPerTest > 1) {
                return false
            }
            return true
        }

        /**
         * Method used to compute next runner and max attempts. Returns null if there if there is no
         * remaining attempts (including fallback runner).
         */
        @com.google.common.annotations.VisibleForTesting
        @Throws(ExecException::class, java.lang.InterruptedException::class)
        fun computeNextRunnerAndMaxAttempts(
            result: com.google.devtools.build.lib.analysis.test.TestActionContext.TestAttemptResult.Result,
            testRunnerSpawn: TestRunnerSpawn,
            numAttempts: Int,
            maxAttempts: Int,
            results: MutableList<SpawnResult?>?
        ): TestRunnerSpawnAndMaxAttempts? {
            com.google.common.base.Preconditions.checkState(
                result != com.google.devtools.build.lib.analysis.test.TestActionContext.TestAttemptResult.Result.PASSED,
                "Should not compute retry runner if last result passed"
            )
            if (result.canRetry() && numAttempts < maxAttempts) {
                val nextRunner: TestRunnerSpawn? = testRunnerSpawn.getFlakyRetryRunner(results)
                if (nextRunner != null) {
                    return TestRunnerSpawnAndMaxAttempts.Companion.create(nextRunner, maxAttempts)
                }
            } else {
                val nextRunner: TestRunnerSpawn? = testRunnerSpawn.getFallbackRunner()
                if (nextRunner != null) {
                    // We only support one level of fallback, in which case maxAttempts gets *added* once. We
                    // don't support a different number of max attempts for the fallback strategy.
                    return TestRunnerSpawnAndMaxAttempts.Companion.create(nextRunner, numAttempts + maxAttempts)
                }
            }
            return null
        }
    }
}
