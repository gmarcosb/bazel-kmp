// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionExecutionContext

/** Runs TestRunnerAction actions.  */ // TODO(bazel-team): add tests for this strategy.
open class StandaloneTestStrategy(
    executionOptions: ExecutionOptions?,
    testSummaryOptions: TestSummaryOptions?,
    tmpDirRoot: com.google.devtools.build.lib.vfs.Path
) : TestStrategy(executionOptions, testSummaryOptions) {
    private val tmpDirRoot: com.google.devtools.build.lib.vfs.Path

    init {
        this.tmpDirRoot = tmpDirRoot
    }

    @Throws(ExecException::class, java.lang.InterruptedException::class)
    public override fun createTestRunnerSpawn(
        action: TestRunnerAction, actionExecutionContext: ActionExecutionContext
    ): TestRunnerSpawn? {
        val testEnvironment =
            createEnvironment(actionExecutionContext, action, tmpDirRoot)

        if (testEnvironment.containsKey(TEST_NAME_ENV)) {
            throw createTestExecException(
                TestAction.Code.LOCAL_TEST_PREREQ_UNMET,
                java.lang.String.format(
                    "cannot set env variable TEST_NAME=%s because TEST_NAME is reserved",
                    testEnvironment.get(TEST_NAME_ENV)
                )
            )
        }

        val executionInfo: MutableMap<String?, String?> = TreeMap<Any?, Any?>(action.getExecutionInfo())
        if (!action.shouldAcceptCachedResult()) {
            // TODO(tjgq): We want to reject a previously cached result, but not prevent the result of the
            // current execution from being uploaded. We should introduce a separate execution requirement
            // for this.
            executionInfo.put(ExecutionRequirements.NO_CACHE, "")
        }
        executionInfo.put(
            ExecutionRequirements.TIMEOUT, java.lang.Long.toString(action.getTimeout().toSeconds())
        )

        val localResourcesSupplier: SimpleSpawn.LocalResourcesSupplier =
            SimpleSpawn.LocalResourcesSupplier {
                action
                    .getTestProperties()
                    .getLocalResourceUsage(
                        action.getOwner().getLabel(), executionOptions.usingLocalTestJobs()
                    )
            }

        val spawn: Spawn =
            SimpleSpawn(
                action,
                getArgs(action),
                com.google.common.collect.ImmutableMap.< K, V > copyOf<K?, V?>(testEnvironment),
                com.google.common.collect.ImmutableMap.< K, V > copyOf<K?, V?>(executionInfo),  /* inputs= */
                action.getInputs(),
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                com.google.common.collect.ImmutableSet.copyOf(action.getSpawnOutputs()),  /* mandatoryOutputs= */
                com.google.common.collect.ImmutableSet.of<E?>(),
                localResourcesSupplier
            )
        val execRoot: com.google.devtools.build.lib.vfs.Path? = actionExecutionContext.getExecRoot()
        val pathResolver: ArtifactPathResolver = actionExecutionContext.getPathResolver()
        val tmpDir: com.google.devtools.build.lib.vfs.Path? =
            pathResolver.convertPath(tmpDirRoot.getChild(TestStrategy.getTmpDirName(action)))
        return StandaloneTestRunnerSpawn(action, actionExecutionContext, spawn, tmpDir, execRoot)
    }

    @Throws(IOException::class)
    private fun processFailedTestAttempt(
        attemptId: Int,
        actionExecutionContext: ActionExecutionContext,
        action: TestRunnerAction,
        result: StandaloneTestResult
    ): StandaloneProcessedAttemptResult {
        return processTestAttempt(
            attemptId,  /* isLastAttempt= */false, actionExecutionContext, action, result
        )
    }

    @Throws(IOException::class)
    private fun finalizeTest(
        action: TestRunnerAction,
        actionExecutionContext: ActionExecutionContext,
        standaloneTestResult: StandaloneTestResult,
        failedAttempts: MutableList<ProcessedAttemptResult>
    ) {
        val lastAttempt =
            processTestAttempt(
                failedAttempts.size() + 1,  /* isLastAttempt= */
                true,
                actionExecutionContext,
                action,
                standaloneTestResult
            )

        val dataBuilder: TestResultData.Builder = standaloneTestResult.testResultDataBuilder
        for (failedAttempt in failedAttempts) {
            val failedAttemptData: TestResultData =
                (failedAttempt as StandaloneProcessedAttemptResult).testResultData
            dataBuilder.addAllFailedLogs(failedAttemptData.getFailedLogsList())
            dataBuilder.addTestTimes(failedAttemptData.getTestTimes(0))
            dataBuilder.addAllTestProcessTimes(failedAttemptData.getTestProcessTimesList())
        }
        if (dataBuilder.getStatus() === BlazeTestStatus.PASSED && !failedAttempts.isEmpty()) {
            dataBuilder.setStatus(BlazeTestStatus.FLAKY)
        }
        val data: TestResultData? = dataBuilder.build()
        val result: TestResult =
            TestResult(
                action,
                data,
                lastAttempt.testOutputs,
                false,
                standaloneTestResult.primarySystemFailure()
            )
        postTestResult(actionExecutionContext, result)
    }

    @Throws(IOException::class)
    private fun processTestAttempt(
        attemptId: Int,
        isLastAttempt: Boolean,
        actionExecutionContext: ActionExecutionContext,
        action: TestRunnerAction,
        result: StandaloneTestResult
    ): StandaloneProcessedAttemptResult {
        var testOutputs: com.google.common.collect.ImmutableMultimap<String?, com.google.devtools.build.lib.vfs.Path?> =
            action.getTestOutputsMapping(
                actionExecutionContext.getPathResolver(), actionExecutionContext.getExecRoot()
            )
        if (!isLastAttempt) {
            testOutputs = renameOutputs(actionExecutionContext, action, testOutputs, attemptId)
        }

        // Recover the test log path, which may have been renamed, and add it to the data builder.
        var renamedTestLog: com.google.devtools.build.lib.vfs.Path? = null
        for (pair in testOutputs.entries()) {
            if (TestFileNameConstants.TEST_LOG.equals(pair.getKey())) {
                com.google.common.base.Preconditions.checkState(renamedTestLog == null, "multiple test_log matches")
                renamedTestLog = pair.getValue()
            }
        }

        val dataBuilder: TestResultData.Builder = result.testResultDataBuilder
        // If the test log path does not exist, mark the test as incomplete
        if (renamedTestLog == null) {
            dataBuilder.setStatus(BlazeTestStatus.INCOMPLETE)
        }

        if (dataBuilder.getStatus() === BlazeTestStatus.PASSED) {
            dataBuilder.setPassedLog(renamedTestLog.toString())
        } else if (dataBuilder.getStatus() !== BlazeTestStatus.INCOMPLETE) {
            dataBuilder.addFailedLogs(renamedTestLog.toString())
        }

        if (!result.spawnResults.isEmpty()) {
            dataBuilder.setExitCode(result.spawnResults.get(0).exitCode())
        }

        // Add the test log to the output
        val data: TestResultData = dataBuilder.build()
        actionExecutionContext
            .getEventHandler()
            .post(
                TestAttempt.forExecutedTestResult(
                    action, data, attemptId, testOutputs, result.executionInfo, isLastAttempt
                )
            )
        processTestOutput(actionExecutionContext, data, action.getTestName(), renamedTestLog)
        return StandaloneProcessedAttemptResult(data, testOutputs)
    }

    @Throws(ExecException::class, IOException::class, java.lang.InterruptedException::class)
    private fun beginTestAttempt(
        testAction: TestRunnerAction,
        spawn: Spawn,
        actionExecutionContext: ActionExecutionContext,
        execRoot: com.google.devtools.build.lib.vfs.Path?
    ): TestAttemptResult? {
        val resolvedPaths: ResolvedPaths = testAction.resolve(execRoot)
        val out: com.google.devtools.build.lib.vfs.Path? = actionExecutionContext.getInputPath(testAction.getTestLog())
        val err: com.google.devtools.build.lib.vfs.Path? = resolvedPaths.getTestStderr()
        val testOutErr: FileOutErr = FileOutErr(out, err)
        var streamed: java.io.Closeable? = null
        if (executionOptions.getTestOutput().equals(TestOutputFormat.STREAMED)) {
            streamed =
                createStreamedTestOutput(
                    com.google.devtools.build.lib.events.Reporter.Companion.outErrForReporter(actionExecutionContext.getEventHandler()),
                    out
                )
        }

        val startTimeMillis: Long = actionExecutionContext.getClock().currentTimeMillis()
        val resolver: SpawnStrategyResolver = actionExecutionContext.getContext(SpawnStrategyResolver::class.java)

        return runTestAttempt(
            testAction,
            actionExecutionContext,
            spawn,
            resolver,
            resolvedPaths,
            testOutErr,
            streamed,
            startTimeMillis
        )
    }

    public override fun newCachedTestResult(
        execRoot: com.google.devtools.build.lib.vfs.Path?,
        action: TestRunnerAction?,
        cachedResult: TestResultData?,
        testOutputs: com.google.common.collect.ImmutableMultimap<String?, com.google.devtools.build.lib.vfs.Path?>?
    ): TestResult? {
        return TestResult(
            action, cachedResult, testOutputs,  /* cached= */true, execRoot,  /* systemFailure= */null
        )
    }

    @com.google.common.annotations.VisibleForTesting
    internal class StandaloneProcessedAttemptResult(
        testResultData: TestResultData,
        testOutputs: com.google.common.collect.ImmutableMultimap<String?, com.google.devtools.build.lib.vfs.Path?>?
    ) : ProcessedAttemptResult {
        val testResultData: TestResultData
        val testOutputs: com.google.common.collect.ImmutableMultimap<String?, com.google.devtools.build.lib.vfs.Path?>?

        init {
            this.testResultData = testResultData
            this.testOutputs = testOutputs
        }
    }

    private inner class StandaloneTestRunnerSpawn(
        testAction: TestRunnerAction,
        actionExecutionContext: ActionExecutionContext,
        spawn: Spawn,
        tmpDir: com.google.devtools.build.lib.vfs.Path?,
        execRoot: com.google.devtools.build.lib.vfs.Path?
    ) : TestRunnerSpawn {
        private val testAction: TestRunnerAction
        private val actionExecutionContext: ActionExecutionContext
        private val spawn: Spawn
        private val tmpDir: com.google.devtools.build.lib.vfs.Path?
        private val execRoot: com.google.devtools.build.lib.vfs.Path?

        init {
            this.testAction = testAction
            this.actionExecutionContext = actionExecutionContext
            this.spawn = spawn
            this.tmpDir = tmpDir
            this.execRoot = execRoot
        }

        public override fun getActionExecutionContext(): ActionExecutionContext {
            return actionExecutionContext
        }

        @Throws(java.lang.InterruptedException::class, IOException::class, ExecException::class)
        public override fun execute(): TestAttemptResult? {
            prepareFileSystem(testAction, execRoot, tmpDir, actionExecutionContext)
            return beginTestAttempt(testAction, spawn, actionExecutionContext, execRoot)
        }

        public override fun getMaxAttempts(firstTestAttemptResult: TestAttemptResult?): Int {
            return getTestAttempts(testAction)
        }

        @Throws(IOException::class)
        public override fun finalizeFailedTestAttempt(
            testAttemptResult: TestAttemptResult?, attempt: Int
        ): StandaloneProcessedAttemptResult {
            return processFailedTestAttempt(
                attempt, actionExecutionContext, testAction, testAttemptResult as StandaloneTestResult?
            )
        }

        @Throws(IOException::class)
        public override fun finalizeTest(
            finalResult: TestAttemptResult?, failedAttempts: MutableList<ProcessedAttemptResult>
        ) {
            this@StandaloneTestStrategy.finalizeTest(
                testAction, actionExecutionContext, finalResult as StandaloneTestResult?, failedAttempts
            )
        }

        @Throws(IOException::class)
        public override fun finalizeCancelledTest(failedAttempts: MutableList<ProcessedAttemptResult>) {
            val builder: TestResultData.Builder? =
                TestResultData.newBuilder()
                    .setCachable(false)
                    .setTestPassed(false)
                    .setStatus(BlazeTestStatus.INCOMPLETE)
            val standaloneTestResult: StandaloneTestResult? =
                StandaloneTestResult.Companion.builder()
                    .setSpawnResults(com.google.common.collect.ImmutableList.of<SpawnResult?>())
                    .setTestResultDataBuilder(builder)
                    .setExecutionInfo(ExecutionInfo.getDefaultInstance())
                    .build()
            finalizeTest(standaloneTestResult, failedAttempts)
        }

        @Throws(ExecException::class, java.lang.InterruptedException::class)
        public override fun getFlakyRetryRunner(previousAttemptResults: MutableList<SpawnResult?>?): TestRunnerSpawn? {
            return createTestRunnerSpawn(testAction, actionExecutionContext)
        }
    }

    @Throws(java.lang.InterruptedException::class, ExecException::class, IOException::class)
    private fun runTestAttempt(
        testAction: TestRunnerAction,
        actionExecutionContext: ActionExecutionContext,
        spawn: Spawn,
        resolver: SpawnStrategyResolver,
        resolvedPaths: ResolvedPaths,
        fileOutErr: FileOutErr,
        streamed: java.io.Closeable?,
        startTimeMillis: Long
    ): TestAttemptResult? {
        var spawnResults: com.google.common.collect.ImmutableList<SpawnResult>

        // We have two protos to represent test attempts:
        // 1. com.google.devtools.build.lib.view.test.TestStatus.TestResultData represents both
        //    failed attempts and finished tests. Bazel stores this to disk to persist cached test
        //    result information across server restarts.
        // 2. com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestResult
        //    represents only individual attempts (failed or not). Bazel reports this as an event to
        //    the Build Event Protocol, but never saves it to disk.
        //
        // The TestResult proto is always constructed from a TestResultData instance, either one
        // that is created right here, or one that is read back from disk.
        var testResultDataBuilder: TestResultData.Builder
        try {
            spawnResults = resolver.exec(spawn, actionExecutionContext.withFileOutErr(fileOutErr))
            testResultDataBuilder = TestResultData.newBuilder()
            testResultDataBuilder.setCachable(true).setTestPassed(true).setStatus(BlazeTestStatus.PASSED)
        } catch (e: SpawnExecException) {
            if (e.isCatastrophic()) {
                closeSuppressed(e, streamed)
                closeSuppressed(e, fileOutErr)
                throw e
            }
            if (!e.getSpawnResult().setupSuccess()) {
                closeSuppressed(e, streamed)
                closeSuppressed(e, fileOutErr)
                // Rethrow as the test could not be run and thus there's no point in retrying.
                throw e
            }
            spawnResults = com.google.common.collect.ImmutableList.of<SpawnResult>(e.getSpawnResult())
            testResultDataBuilder = TestResultData.newBuilder()
            testResultDataBuilder
                .setCachable(e.getSpawnResult().status().isConsideredUserError())
                .setTestPassed(false)
                .setStatus(if (e.hasTimedOut()) BlazeTestStatus.TIMEOUT else BlazeTestStatus.FAILED)
        } catch (e: java.lang.InterruptedException) {
            closeSuppressed(e, streamed)
            closeSuppressed(e, fileOutErr)
            throw e
        }
        val endTimeMillis: Long = actionExecutionContext.getClock().currentTimeMillis()

        // Check TEST_PREMATURE_EXIT_FILE file (and always delete it)
        if (actionExecutionContext
                .getPathResolver()
                .convertPath(resolvedPaths.getExitSafeFile())
                .delete()
            && testResultDataBuilder.getTestPassed()
        ) {
            testResultDataBuilder
                .setCachable(false)
                .setTestPassed(false)
                .setStatus(BlazeTestStatus.FAILED)
            fileOutErr
                .getErrorStream()
                .write(
                    "-- Test exited prematurely (TEST_PREMATURE_EXIT_FILE exists) --\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
        }

        // Do not override a more informative test failure with a generic failure due to the missing
        // shard file, which may have been caused by the test failing before the runner had a chance to
        // touch the file
        if (testResultDataBuilder.getTestPassed()
            && testAction.isSharded()
            && !actionExecutionContext
                .getPathResolver()
                .convertPath(resolvedPaths.getTestShard())
                .exists()
        ) {
            val e: TestExecException =
                createTestExecException(
                    TestAction.Code.LOCAL_TEST_PREREQ_UNMET,
                    """
              Sharding requested, but the test runner did not advertise support for it by touching TEST_SHARD_STATUS_FILE. Either remove the 'shard_count' attribute or use a test runner that supports sharding.
              """.trimIndent()
                )
            closeSuppressed(e, streamed)
            closeSuppressed(e, fileOutErr)
            throw e
        }

        // SpawnActionContext guarantees the first entry to correspond to the spawn passed in (there
        // may be additional entries due to tree artifact handling).
        val primaryResult: SpawnResult = spawnResults.get(0)

        // The SpawnResult of a remotely cached or remotely executed action may not have walltime
        // set. We fall back to the time measured here for backwards compatibility.
        var durationMillis = endTimeMillis - startTimeMillis
        durationMillis =
            (if (primaryResult.getWallTimeInMs() !== 0) primaryResult.getWallTimeInMs() else durationMillis)

        testResultDataBuilder
            .setStartTimeMillisEpoch(startTimeMillis)
            .addTestTimes(durationMillis)
            .addTestProcessTimes(durationMillis)
            .setRunDurationMillis(durationMillis)
            .setHasCoverage(testAction.isCoverageMode())

        if (testAction.isCoverageMode() && testAction.getSplitCoveragePostProcessing()) {
            if (testResultDataBuilder.getTestPassed()) {
                if (testAction.getCoverageDirectoryTreeArtifact() == null) {
                    // Otherwise we'll get a NPE https://github.com/bazelbuild/bazel/issues/13185
                    val e: TestExecException =
                        createTestExecException(
                            TestAction.Code.LOCAL_TEST_PREREQ_UNMET,
                            ("coverageDirectoryTreeArtifact is null:"
                                    + " --experimental_split_coverage_postprocessing depends on"
                                    + " --experimental_fetch_all_coverage_outputs being enabled")
                        )
                    closeSuppressed(e, streamed)
                    closeSuppressed(e, fileOutErr)
                    throw e
                }
                val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    actionExecutionContext
                        .getOutputMetadataStore()
                        .getOutputMetadata(testAction.getCoverageDirectoryTreeArtifact())

                val expandedCoverageDir: com.google.common.collect.ImmutableSortedSet<TreeFileArtifact?>? =
                    actionExecutionContext
                        .getOutputMetadataStore()
                        .getTreeArtifactValue(
                            testAction.getCoverageDirectoryTreeArtifact() as SpecialArtifact?
                        )
                        .getChildren()
                val coverageSpawnMetadata: com.google.common.collect.ImmutableSet<Artifact?> =
                    com.google.common.collect.ImmutableSet.builder<Artifact?>()
                        .addAll(expandedCoverageDir)
                        .add(testAction.getCoverageDirectoryTreeArtifact())
                        .build()

                val coveragePostProcessingSpawn: Spawn =
                    Companion.createCoveragePostProcessingSpawn(
                        actionExecutionContext,
                        testAction,
                        com.google.common.collect.ImmutableList.< E > copyOf < E ? > (expandedCoverageDir),
                        tmpDirRoot
                    )
                val spawnStrategyResolver: SpawnStrategyResolver =
                    actionExecutionContext.getContext(SpawnStrategyResolver::class.java)

                val testRoot: com.google.devtools.build.lib.vfs.Path =
                    actionExecutionContext.getInputPath(testAction.getTestLog()).getParentDirectory()

                val out: com.google.devtools.build.lib.vfs.Path = testRoot.getChild("coverage.log")
                val err: com.google.devtools.build.lib.vfs.Path = testRoot.getChild("coverage.err")
                val coverageOutErr: FileOutErr = FileOutErr(out, err)
                val coverageActionExecutionContext: ActionExecutionContext? =
                    actionExecutionContext
                        .withFileOutErr(coverageOutErr)
                        .withOutputsAsInputs(coverageSpawnMetadata)

                try {
                    spawnStrategyResolver.exec(coveragePostProcessingSpawn, coverageActionExecutionContext)
                } catch (e: SpawnExecException) {
                    if (e.isCatastrophic()) {
                        closeSuppressed(e, streamed)
                        closeSuppressed(e, fileOutErr)
                        throw e
                    }
                    if (!e.getSpawnResult().setupSuccess()) {
                        closeSuppressed(e, streamed)
                        closeSuppressed(e, fileOutErr)
                        // Rethrow as the test could not be run and thus there's no point in retrying.
                        throw e
                    }
                    testResultDataBuilder
                        .setCachable(e.getSpawnResult().status().isConsideredUserError())
                        .setTestPassed(false)
                        .setStatus(if (e.hasTimedOut()) BlazeTestStatus.TIMEOUT else BlazeTestStatus.FAILED)
                } catch (e: ExecException) {
                    closeSuppressed(e, streamed)
                    closeSuppressed(e, fileOutErr)
                    throw e
                } catch (e: java.lang.InterruptedException) {
                    closeSuppressed(e, streamed)
                    closeSuppressed(e, fileOutErr)
                    throw e
                }

                // Append all output from the coverage spawn to the test log.
                appendCoverageLog(coverageOutErr, fileOutErr)
            } else {
                val coverageData: Artifact? = testAction.getCoverageData()
                if (coverageData != null) {
                    com.google.devtools.build.lib.vfs.FileSystemUtils.touchFile(
                        actionExecutionContext.getPathResolver().convertPath(coverageData.getPath())
                    )
                }
            }
        }

        com.google.common.base.Verify.verify(
            !(testAction.isCoverageMode() && testAction.getSplitCoveragePostProcessing())
                    || actionExecutionContext
                .getPathResolver()
                .convertPath(testAction.getCoverageData().getPath())
                .exists()
        )
        com.google.common.base.Verify.verifyNotNull<com.google.common.collect.ImmutableList<SpawnResult?>?>(spawnResults)
        com.google.common.base.Verify.verifyNotNull<Any?>(testResultDataBuilder)

        try {
            if (!fileOutErr.hasRecordedOutput()) {
                // Make sure that the test.log exists.Spaw
                com.google.devtools.build.lib.vfs.FileSystemUtils.touchFile(fileOutErr.getOutputPath())
            }
            fileOutErr.close()
            // Append any error output to the test.log. This is very rare.
            //
            // Only write after the error output stream has been closed. Otherwise, Bazel cannot delete
            // test.err file on Windows. See https://github.com/bazelbuild/bazel/issues/20741.
            writeOutFile(fileOutErr.getErrorPath(), fileOutErr.getOutputPath())
            if (streamed != null) {
                streamed.close()
            }
        } catch (e: IOException) {
            throw EnvironmentalExecException(e, Code.TEST_OUT_ERR_IO_EXCEPTION)
        }

        val xmlOutputPath: com.google.devtools.build.lib.vfs.Path = resolvedPaths.getXmlOutputPath()

        // If the test did not create a test.xml, then we run a separate action to create a test.xml
        // from test.log. We do this as a spawn rather than doing it locally in-process, as the test.log
        // file may only exist remotely (when remote execution is enabled), and we do not want to have
        // to download it.
        if (fileOutErr.getOutputPath().exists() && !xmlOutputPath.exists()) {
            val xmlGeneratingSpawn: Spawn =
                createXmlGeneratingSpawn(testAction, spawn.getEnvironment(), spawnResults.get(0))
            val spawnStrategyResolver: SpawnStrategyResolver =
                actionExecutionContext.getContext(SpawnStrategyResolver::class.java)
            // We treat all failures to generate the test.xml here as catastrophic, and won't rerun
            // the test if this fails. We redirect the output to a temporary file.
            val xmlSpawnOutErr: FileOutErr? = actionExecutionContext.getFileOutErr().childOutErr()

            val xmlActionExecutionContext: ActionExecutionContext? =
                actionExecutionContext
                    .withFileOutErr(xmlSpawnOutErr)
                    .withOutputsAsInputs(com.google.common.collect.ImmutableList.of<E?>(testAction.getTestLog()))
            try {
                val xmlSpawnResults: com.google.common.collect.ImmutableList<SpawnResult> =
                    spawnStrategyResolver.exec(xmlGeneratingSpawn, xmlActionExecutionContext)
                spawnResults =
                    com.google.common.collect.ImmutableList.builder<SpawnResult>()
                        .addAll(spawnResults)
                        .addAll(xmlSpawnResults)
                        .build()
            } catch (e: java.lang.InterruptedException) {
                closeSuppressed(e, xmlSpawnOutErr)
                throw e
            } catch (e: ExecException) {
                closeSuppressed(e, xmlSpawnOutErr)
                throw e
            }
        }

        val details: TestCase? = parseTestResult(xmlOutputPath)
        if (details != null) {
            testResultDataBuilder.setTestCase(details)
        }

        val executionInfo: BuildEventStreamProtos.TestResult.ExecutionInfo =
            extractExecutionInfo(spawnResults.get(0), testResultDataBuilder)
        return StandaloneTestResult.Companion.builder()
            .setSpawnResults(spawnResults) // We return the TestResultData.Builder rather than the finished TestResultData
            // instance, as we may have to rename the output files in case the test needs to be
            // rerun (if it failed here _and_ is marked flaky _and_ the number of flaky attempts
            // is larger than 1).
            .setTestResultDataBuilder(testResultDataBuilder)
            .setExecutionInfo(executionInfo)
            .build()
    }

    companion object {
        private const val TEST_NAME_ENV = "TEST_NAME"
        private val ENV_VARS: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.builder<String?, String?>()
                .put("TZ", "UTC")
                .put(
                    "TEST_SRCDIR",
                    TestPolicy.Companion.RUNFILES_DIR
                ) // TODO(lberki): Remove JAVA_RUNFILES and PYTHON_RUNFILES.
                .put("JAVA_RUNFILES", TestPolicy.Companion.RUNFILES_DIR)
                .put("PYTHON_RUNFILES", TestPolicy.Companion.RUNFILES_DIR)
                .put("RUNFILES_DIR", TestPolicy.Companion.RUNFILES_DIR)
                .put("TEST_TMPDIR", TestPolicy.Companion.TEST_TMP_DIR)
                .put("RUN_UNDER_RUNFILES", "1")
                .buildOrThrow()

        val DEFAULT_LOCAL_POLICY: TestPolicy = TestPolicy(ENV_VARS)

        @Throws(IOException::class)
        private fun renameOutputs(
            actionExecutionContext: ActionExecutionContext,
            action: TestRunnerAction,
            testOutputs: com.google.common.collect.ImmutableMultimap<String?, com.google.devtools.build.lib.vfs.Path?>,
            attemptId: Int
        ): com.google.common.collect.ImmutableMultimap<String?, com.google.devtools.build.lib.vfs.Path?> {
            // Rename outputs
            val namePrefix: String? =
                com.google.devtools.build.lib.vfs.FileSystemUtils.removeExtension(
                    action.getTestLog().getExecPath().getBaseName()
                )
            val testRoot: com.google.devtools.build.lib.vfs.Path =
                actionExecutionContext.getInputPath(action.getTestLog()).getParentDirectory()
            val attemptsDir: com.google.devtools.build.lib.vfs.Path = testRoot.getChild(namePrefix + "_attempts")
            attemptsDir.createDirectory()
            val attemptPrefix = "attempt_" + attemptId
            val testLog: com.google.devtools.build.lib.vfs.Path = attemptsDir.getChild(attemptPrefix + ".log")

            // Get the normal test output paths, and then update them to use "attempt_N" names, and
            // attemptDir, before adding them to the outputs.
            val testOutputsBuilder: com.google.common.collect.ImmutableMultimap.Builder<String?, com.google.devtools.build.lib.vfs.Path?> =
                com.google.common.collect.ImmutableMultimap.builder<String?, com.google.devtools.build.lib.vfs.Path?>()
            for (testOutput in testOutputs.entries()) {
                // e.g. /testRoot/test.dir/file, an example we follow throughout this loop's comments.
                val testOutputPath: com.google.devtools.build.lib.vfs.Path = testOutput.getValue()
                val destinationPath: com.google.devtools.build.lib.vfs.Path
                if (testOutput.getKey() == TestFileNameConstants.TEST_LOG) {
                    // The rename rules for the test log are different than for all the other files.
                    destinationPath = testLog
                } else {
                    // e.g. test.dir/file
                    val relativeToTestDirectory: PathFragment = testOutputPath.relativeTo(testRoot)

                    // e.g. attempt_1.dir/file
                    val destinationPathFragmentStr: String? =
                        relativeToTestDirectory.getSafePathString().replaceFirst("test", attemptPrefix)
                    val destinationPathFragment: PathFragment? = PathFragment.create(destinationPathFragmentStr)

                    // e.g. /attemptsDir/attempt_1.dir/file
                    destinationPath = attemptsDir.getRelative(destinationPathFragment)
                    destinationPath.getParentDirectory().createDirectoryAndParents()
                }

                // Move to the destination.
                testOutputPath.renameTo(destinationPath)

                testOutputsBuilder.put(testOutput.getKey(), destinationPath)
            }
            return testOutputsBuilder.build()
        }

        private fun setupEnvironment(
            action: TestRunnerAction?,
            clientEnv: MutableMap<String?, String?>?,
            execRoot: com.google.devtools.build.lib.vfs.Path,
            runfilesDir: com.google.devtools.build.lib.vfs.Path,
            tmpDir: com.google.devtools.build.lib.vfs.Path
        ): MutableMap<String?, String?> {
            val relativeTmpDir: PathFragment
            if (tmpDir.startsWith(execRoot)) {
                relativeTmpDir = tmpDir.relativeTo(execRoot)
            } else {
                relativeTmpDir = tmpDir.asFragment()
            }
            return DEFAULT_LOCAL_POLICY.computeTestEnvironment(
                action, clientEnv, runfilesDir.relativeTo(execRoot), relativeTmpDir
            )
        }

        @Throws(IOException::class)
        private fun appendCoverageLog(coverageOutErr: FileOutErr, outErr: FileOutErr) {
            writeOutFile(coverageOutErr.getErrorPath(), outErr.getOutputPath())
            writeOutFile(coverageOutErr.getOutputPath(), outErr.getOutputPath())
        }

        @Throws(IOException::class)
        private fun writeOutFile(
            inFilePath: com.google.devtools.build.lib.vfs.Path,
            outFilePath: com.google.devtools.build.lib.vfs.Path
        ) {
            val stat: FileStatus? = inFilePath.statNullable()
            if (stat != null) {
                try {
                    if (stat.getSize() > 0) {
                        if (outFilePath.exists()) {
                            outFilePath.setWritable(true)
                        }
                        outFilePath.getOutputStream(true).use { out ->
                            inFilePath.getInputStream().use { `in` ->
                                com.google.common.io.ByteStreams.copy(`in`, out)
                            }
                        }
                    }
                } finally {
                    inFilePath.delete()
                }
            }
        }

        private fun extractExecutionInfo(
            spawnResult: SpawnResult, result: TestResultData.Builder
        ): BuildEventStreamProtos.TestResult.ExecutionInfo {
            val executionInfo: BuildEventStreamProtos.TestResult.ExecutionInfo.Builder =
                BuildEventStreamProtos.TestResult.ExecutionInfo.newBuilder()

            // The return of `SpawnResult#exitCode()` is noted to only be meaningful if the subprocess
            // actually executed. In this position, `spawnResult.exitCode()` is always meaningful,
            // because the code only runs if `spawnResult.setupSuccess()` is previously verified to
            // be `true`.
            executionInfo.setExitCode(spawnResult.exitCode())

            if (spawnResult.isCacheHit()) {
                result.setRemotelyCached(true)
                executionInfo.setCachedRemotely(true)
            }

            val strategy: String? = spawnResult.getRunnerName()
            if (strategy != null) {
                executionInfo.setStrategy(strategy)
                result.setIsRemoteStrategy(strategy == "remote")
            }

            if (spawnResult.getExecutorHostName() != null) {
                executionInfo.setHostname(spawnResult.getExecutorHostName())
            }

            val sm: SpawnMetrics = spawnResult.getMetrics()
            executionInfo.setTimingBreakdown(
                BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                    .setName("totalTime")
                    .setTime(toProtoDuration(sm.totalTimeInMs()))
                    .addChild(
                        BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                            .setName("parseTime")
                            .setTime(toProtoDuration(sm.parseTimeInMs()))
                            .build()
                    )
                    .addChild(
                        BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                            .setName("fetchTime")
                            .setTime(toProtoDuration(sm.fetchTimeInMs()))
                            .build()
                    )
                    .addChild(
                        BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                            .setName("queueTime")
                            .setTime(toProtoDuration(sm.queueTimeInMs()))
                            .build()
                    )
                    .addChild(
                        BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                            .setName("uploadTime")
                            .setTime(toProtoDuration(sm.uploadTimeInMs()))
                            .build()
                    )
                    .addChild(
                        BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                            .setName("setupTime")
                            .setTime(toProtoDuration(sm.setupTimeInMs()))
                            .build()
                    )
                    .addChild(
                        BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                            .setName("executionWallTime")
                            .setTime(toProtoDuration(sm.executionWallTimeInMs()))
                            .build()
                    )
                    .addChild(
                        BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                            .setName("processOutputsTime")
                            .setTime(toProtoDuration(sm.processOutputsTimeInMs()))
                            .build()
                    )
                    .addChild(
                        BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                            .setName("networkTime")
                            .setTime(toProtoDuration(sm.networkTimeInMs()))
                            .build()
                    )
                    .build()
            )

            return executionInfo.build()
        }

        private fun toProtoDuration(timeInMs: Int): Duration {
            return Durations.fromMillis(timeInMs)
        }

        /**
         * A spawn to generate a test.xml file from the test log. This is only used if the test does not
         * generate a test.xml file itself.
         */
        private fun createXmlGeneratingSpawn(
            action: TestRunnerAction,
            testEnv: com.google.common.collect.ImmutableMap<String?, String?>,
            result: SpawnResult
        ): Spawn {
            val args: com.google.common.collect.ImmutableList<String?> =
                com.google.common.collect.ImmutableList.of<E?>(
                    action
                        .getTestXmlGeneratorScript()
                        .getExecPath()
                        .getCallablePathStringForOs(action.getExecutionSettings().getExecutionOs()),
                    action.getTestLog().getExecPathString(),
                    action.getTestXml().getExecPathString(),
                    java.lang.Integer.toString(result.getWallTimeInMs() / 1000),
                    java.lang.Integer.toString(result.exitCode())
                )
            val envBuilder: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
                com.google.common.collect.ImmutableMap.builder<String?, String?>()
            // "PATH" and "TEST_BINARY" are also required, they should always be set in testEnv.
            com.google.common.base.Preconditions.checkArgument(testEnv.containsKey("PATH"))
            com.google.common.base.Preconditions.checkArgument(testEnv.containsKey("TEST_BINARY"))
            envBuilder.putAll(testEnv).put(TEST_NAME_ENV, action.getTestName())
            // testEnv only contains TEST_SHARD_INDEX and TEST_TOTAL_SHARDS if the test action is sharded,
            // we need to set the default value when the action isn't sharded.
            if (!action.isSharded()) {
                envBuilder.put("TEST_SHARD_INDEX", "0")
                envBuilder.put("TEST_TOTAL_SHARDS", "0")
            }
            return SimpleSpawn(
                action,
                args,
                envBuilder.buildOrThrow(),  // Pass the execution info of the action which is identical to the supported tags set on the
                // test target. In particular, this does not set the test timeout on the spawn.
                action.getExecutionInfo(),  /* inputs= */
                NestedSetBuilder.create(
                    Order.STABLE_ORDER, action.getTestXmlGeneratorScript(), action.getTestLog()
                ),  /* tools= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(action.getTestXml()),  /* mandatoryOutputs= */
                null,
                SpawnAction.DEFAULT_RESOURCE_SET
            )
        }

        private fun createCoveragePostProcessingSpawn(
            actionExecutionContext: ActionExecutionContext,
            action: TestRunnerAction,
            expandedCoverageDir: MutableList<ActionInput?>?,
            tmpDirRoot: com.google.devtools.build.lib.vfs.Path
        ): Spawn {
            val args: com.google.common.collect.ImmutableList<String?> =
                com.google.common.collect.ImmutableList.of<E?>(
                    action.getCollectCoverageScript().getExecutable().getExecPathString()
                )

            val testEnvironment =
                createEnvironment(actionExecutionContext, action, tmpDirRoot)

            testEnvironment.put("TEST_SHARD_INDEX", java.lang.Integer.toString(action.getShardNum()))
            testEnvironment.put(
                "TEST_TOTAL_SHARDS", java.lang.Integer.toString(action.getExecutionSettings().getTotalShards())
            )
            testEnvironment.put(TEST_NAME_ENV, action.getTestName())
            testEnvironment.put("IS_COVERAGE_SPAWN", "1")
            // Let the coverage script locate its own runfiles tree, which is separate from the test
            // runfiles.
            testEnvironment.remove("RUNFILES_DIR")
            testEnvironment.remove("JAVA_RUNFILES")
            testEnvironment.remove("PYTHON_RUNFILES")

            return SimpleSpawn(
                action,
                args,
                com.google.common.collect.ImmutableMap.< K, V > copyOf<K?, V?>(testEnvironment),
                action.getExecutionInfo(),  /* inputs= */
                NestedSetBuilder.< ActionInput > compileOrder < ActionInput ? > ()
                    .addTransitive(action.getInputs())
                    .addAll(expandedCoverageDir)
                    .add(action.getCoverageManifest())
                    .build(),  /* tools= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(action.getCoverageData()),  /* mandatoryOutputs= */
                null,
                SpawnAction.DEFAULT_RESOURCE_SET
            )
        }

        private fun createEnvironment(
            actionExecutionContext: ActionExecutionContext,
            action: TestRunnerAction,
            tmpDirRoot: com.google.devtools.build.lib.vfs.Path
        ): MutableMap<String?, String?> {
            val execRoot: com.google.devtools.build.lib.vfs.Path = actionExecutionContext.getExecRoot()
            val pathResolver: ArtifactPathResolver = actionExecutionContext.getPathResolver()
            val runfilesDir: com.google.devtools.build.lib.vfs.Path =
                pathResolver.convertPath(action.getExecutionSettings().getRunfilesDir())
            val tmpDir: com.google.devtools.build.lib.vfs.Path =
                pathResolver.convertPath(tmpDirRoot.getChild(TestStrategy.getTmpDirName(action)))
            return setupEnvironment(
                action, actionExecutionContext.getClientEnv(), execRoot, runfilesDir, tmpDir
            )
        }

        private fun createTestExecException(
            errorCode: TestAction.Code?, errorMessage: String?
        ): TestExecException {
            return TestExecException(
                errorMessage,
                FailureDetail.newBuilder()
                    .setTestAction(TestAction.newBuilder().setCode(errorCode))
                    .setMessage(errorMessage)
                    .build()
            )
        }
    }
}
