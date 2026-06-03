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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.actions.ActionExecutionContext

/** A strategy for executing a [TestRunnerAction].  */
abstract class TestStrategy(executionOptions: ExecutionOptions, testSummaryOptions: TestSummaryOptions) :
    TestActionContext {
    private class AttemptGroupImpl : AttemptGroup {
        private var cancelled = false
        private val runningThreads: MutableSet<java.lang.Thread>

        init {
            runningThreads = HashSet<java.lang.Thread>()
        }

        @kotlin.jvm.Synchronized
        @Throws(java.lang.InterruptedException::class)
        override fun register() {
            com.google.common.base.Verify.verify(runningThreads.add(java.lang.Thread.currentThread()))

            if (cancelled) {
                throw java.lang.InterruptedException()
            }
        }

        @kotlin.jvm.Synchronized
        override fun unregister() {
            com.google.common.base.Verify.verify(runningThreads.remove(java.lang.Thread.currentThread()))
        }

        @kotlin.jvm.Synchronized
        override fun cancelled(): Boolean {
            return cancelled
        }

        @kotlin.jvm.Synchronized
        override fun cancelOthers() {
            if (cancelled) {
                return
            }

            cancelled = true

            for (thread in runningThreads) {
                if (thread !== java.lang.Thread.currentThread()) {
                    thread.interrupt()
                }
            }
        }
    }

    private val cancelGroups: ConcurrentHashMap<ShardKey?, AttemptGroupImpl> =
        ConcurrentHashMap<ShardKey?, AttemptGroupImpl>()

    /**
     * Ensures that all directories used to run test are in the correct state and their content will
     * not result in stale files.
     */
    @Throws(IOException::class)
    protected fun prepareFileSystem(
        testAction: TestRunnerAction,
        execRoot: Path?,
        tmpDir: Path?,
        actionExecutionContext: ActionExecutionContext?
    ) {
        if (tmpDir != null) {
            recreateDirectory(tmpDir)
        }

        val resolvedPaths: ResolvedPaths = testAction.resolve(execRoot)
        if (testAction.isCoverageMode()) {
            recreateDirectory(resolvedPaths.getCoverageDirectory())
        }

        resolvedPaths.getBaseDir().createDirectoryAndParents()
        resolvedPaths.getUndeclaredOutputsDir().createDirectoryAndParents()
        resolvedPaths.getUndeclaredOutputsAnnotationsDir().createDirectoryAndParents()
        resolvedPaths.getSplitLogsDir().createDirectoryAndParents()

        if (actionExecutionContext != null && actionExecutionContext.getOutputMetadataStore() != null) {
            // Reset output metadata to avoid stale information from previous attempts.
            actionExecutionContext.getOutputMetadataStore().resetOutputs(testAction.getOutputs())
        }
    }

    // Used for generating unique temporary directory names. Contains the next numeric index for every
    // executable base name.
    private val tmpIndex: MutableMap<String?, Int?> = HashMap<String?, Int?>()
    protected val executionOptions: ExecutionOptions
    protected val testSummaryOptions: TestSummaryOptions

    override fun isTestKeepGoing(): Boolean {
        return executionOptions.getTestKeepGoing()
    }

    override fun getAttemptGroup(owner: ActionOwner?, shardNum: Int): AttemptGroup {
        val key = ShardKey(owner, shardNum)
        return cancelGroups.computeIfAbsent(key, java.util.function.Function { k: ShardKey? -> AttemptGroupImpl() })
    }

    /**
     * Returns the number of attempts specific test action can be retried.
     * 
     * 
     * For rules with "flaky = 1" attribute, this method will return 3 unless --flaky_test_attempts
     * option is given and specifies another value.
     */
    @com.google.common.annotations.VisibleForTesting /* protected */ fun getTestAttempts(action: TestRunnerAction): Int {
        return if (action.getTestProperties().isFlaky())
            getTestAttemptsForFlakyTest(action)
        else
            getTestAttempts(action,  /* defaultTestAttempts= */1)
    }

    private fun getTestAttempts(action: TestRunnerAction, defaultTestAttempts: Int): Int {
        val testLabel: Label? = action.getOwner().getLabel()
        return getTestAttemptsPerLabel(executionOptions, testLabel, defaultTestAttempts)
    }

    fun getTestAttemptsForFlakyTest(action: TestRunnerAction): Int {
        return getTestAttempts(action,  /* defaultTestAttempts= */3)
    }

    /*
   * Finalize test run: persist the result, and post on the event bus.
   */
    @Throws(IOException::class)
    protected fun postTestResult(
        actionExecutionContext: ActionExecutionContext,
        result: com.google.devtools.build.lib.analysis.test.TestResult
    ) {
        result.getTestAction().saveCacheStatus(actionExecutionContext, result.getData())
        actionExecutionContext.getEventHandler().post(result)
    }

    /**
     * Returns a unique name for a temporary directory a test could use.
     * 
     * 
     * Since each test within single Blaze run must have a unique TEST_TMPDIR, we will use rule
     * name and a unique (within single Blaze request) number to generate directory name.
     * 
     * 
     * This does not create the directory.
     */
    protected fun getTmpDirName(execPath: PathFragment): String {
        val basename: String? = execPath.getBaseName()

        synchronized(tmpIndex) {
            val index: Int = (if (tmpIndex.containsKey(basename)) tmpIndex.get(basename) else 1)!!
            tmpIndex.put(basename, index + 1)
            return basename + "_" + index
        }
    }

    init {
        this.executionOptions = executionOptions
        this.testSummaryOptions = testSummaryOptions
    }

    /** Parse a test result XML file into a [TestCase].  */
    protected fun parseTestResult(resultFile: Path): TestCase? {
        /* xml files. We avoid parsing it unnecessarily, since test results can potentially consume
    a large amount of memory. */
        if (!PARSE_TEST_RESULT_FORMATS.contains(executionOptions.getTestSummary())) {
            return null
        }

        try {
            resultFile.getInputStream().use { fileStream ->
                return TestXmlOutputParser().parseXmlIntoTestResult(fileStream)
            }
        } catch (e: IOException) {
            return null
        } catch (e: TestXmlOutputParserException) {
            return null
        }
    }

    /**
     * Outputs test result to the stdout after test has finished (e.g. for --test_output=all or
     * --test_output=errors). Will also try to group output lines together (up to 10000 lines) so
     * parallel test outputs will not get interleaved.
     */
    @Throws(IOException::class)
    protected fun processTestOutput(
        actionExecutionContext: ActionExecutionContext,
        testResultData: TestResultData,
        testName: String?,
        testLog: Path?
    ) {
        val isPassed: Boolean = testResultData.getTestPassed()
        try {
            if (testResultData.getStatus() !== BlazeTestStatus.INCOMPLETE
                && TestLogHelper.shouldOutputTestLog(executionOptions.getTestOutput(), isPassed)
            ) {
                TestLogHelper.writeTestLog(
                    testLog,
                    testName,
                    actionExecutionContext.getFileOutErr().getOutputStream(),
                    executionOptions.getMaxTestOutputBytes()
                )
            }
        } finally {
            if (isPassed) {
                actionExecutionContext.getEventHandler().handle(
                    com.google.devtools.build.lib.events.Event.of(
                        com.google.devtools.build.lib.events.EventKind.PASS,
                        null,
                        testName
                    )
                )
            } else {
                var testLogPathToOutput: PathFragment? = null
                if (testLog != null) {
                    testLogPathToOutput =
                        if (testSummaryOptions.getPrintRelativeTestLogPaths())
                            testLog
                                .asFragment()
                                .relativeTo(actionExecutionContext.getExecRoot().asFragment())
                        else
                            testLog.asFragment()
                }
                if (testResultData.hasStatusDetails()) {
                    actionExecutionContext
                        .getEventHandler()
                        .handle(com.google.devtools.build.lib.events.Event.error(testName + ": " + testResultData.getStatusDetails()))
                }
                if (testResultData.getStatus() === BlazeTestStatus.TIMEOUT) {
                    val message: String? =
                        java.lang.String.format(
                            "%s%s",
                            testName,
                            if (testLogPathToOutput != null) " (see " + testLogPathToOutput + ")" else ""
                        )
                    actionExecutionContext
                        .getEventHandler()
                        .handle(
                            com.google.devtools.build.lib.events.Event.of(
                                com.google.devtools.build.lib.events.EventKind.TIMEOUT,
                                null,
                                message
                            )
                        )
                } else if (testResultData.getStatus() === BlazeTestStatus.INCOMPLETE) {
                    actionExecutionContext
                        .getEventHandler()
                        .handle(
                            com.google.devtools.build.lib.events.Event.of(
                                com.google.devtools.build.lib.events.EventKind.CANCELLED,
                                null,
                                testName
                            )
                        )
                } else {
                    val ts: TerminationStatus =
                        TerminationStatus.builder()
                            .setWaitResponse(testResultData.getExitCode())
                            .setTimedOut(testResultData.getStatus() === BlazeTestStatus.TIMEOUT)
                            .build()
                    val message: String? =
                        java.lang.String.format(
                            "%s (%s)%s",
                            testName,
                            ts.toShortString(),
                            if (testLogPathToOutput != null) " (see " + testLogPathToOutput + ")" else ""
                        )
                    actionExecutionContext.getEventHandler().handle(
                        com.google.devtools.build.lib.events.Event.of(
                            com.google.devtools.build.lib.events.EventKind.FAIL,
                            null,
                            message
                        )
                    )
                }
            }
        }
    }

    @Throws(IOException::class)
    protected fun createStreamedTestOutput(outErr: OutErr?, testLogPath: Path?): java.io.Closeable {
        return StreamedTestOutput(outErr, testLogPath)
    }

    private class ShardKey(owner: ActionOwner?, private val shard: Int) {
        private val owner: ActionOwner

        init {
            this.owner = com.google.common.base.Preconditions.checkNotNull<ActionOwner>(owner)
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(owner, shard)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is ShardKey) {
                return false
            }
            return owner.equals(o.owner) && shard == o.shard
        }
    }

    companion object {
        /** Removes directory if it exists and recreates it.  */
        @Throws(IOException::class)
        private fun recreateDirectory(directory: Path) {
            directory.deleteTree()
            directory.createDirectoryAndParents()
        }

        val TEST_TMP_ROOT: PathFragment? = PathFragment.create("_tmp")

        /**
         * Generates a command line to run for the test action, taking into account coverage and `--run_under` settings.
         * 
         * 
         * Basically [.expandedArgsFromAction], but throws [ExecException] instead. This
         * should be used in action execution.
         * 
         * @param testAction The test action.
         * @return the command line as string list.
         * @throws ExecException if [.expandedArgsFromAction] throws
         */
        @Throws(ExecException::class, java.lang.InterruptedException::class)
        fun getArgs(testAction: TestRunnerAction): com.google.common.collect.ImmutableList<String?> {
            try {
                return expandedArgsFromAction(testAction)
            } catch (e: CommandLineExpansionException) {
                throw UserExecException(
                    e,
                    FailureDetail.newBuilder()
                        .setMessage(com.google.common.base.Strings.nullToEmpty(e.getMessage()))
                        .setTestAction(TestAction.newBuilder().setCode(Code.COMMAND_LINE_EXPANSION_FAILURE))
                        .build()
                )
            }
        }

        /**
         * Generates a command line to run for the test action, taking into account coverage and `--run_under` settings.
         * 
         * @param testAction The test action.
         * @return the command line as string list.
         * @throws CommandLineExpansionException
         */
        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        fun expandedArgsFromAction(testAction: TestRunnerAction): com.google.common.collect.ImmutableList<String?> {
            val args: MutableList<String?> = java.util.ArrayList<String?>()
            val executionOs: OS? = testAction.getExecutionSettings().getExecutionOs()

            val testSetup: Artifact = testAction.getTestSetupScript()
            args.add(testSetup.getExecPath().getCallablePathStringForOs(executionOs))

            if (testAction.isCoverageMode()) {
                args.add(
                    testAction
                        .getCollectCoverageScript()
                        .getExecutable()
                        .getExecPath()
                        .getCallablePathStringForOs(executionOs)
                )
            }

            val execSettings: TestTargetExecutionSettings = testAction.getExecutionSettings()

            // Insert the command prefix specified by the "--run_under=<command-prefix>" option, if any.
            if (execSettings.getRunUnder() != null) {
                addRunUnderArgs(testAction, args)
            }

            // Execute the test using the alias in the runfiles tree, as mandated by the Test Encyclopedia.
            // Do not use getCallablePathStringForOs as tw.exe expects a path with forward slashes.
            args.add(execSettings.getExecutable().getRunfilesPath().getCallablePathString())
            com.google.common.collect.Iterables.addAll<T?>(args, execSettings.getArgs().arguments())
            return com.google.common.collect.ImmutableList.copyOf<String?>(args)
        }

        private fun addRunUnderArgs(testAction: TestRunnerAction, args: MutableList<String?>) {
            val execSettings: TestTargetExecutionSettings = testAction.getExecutionSettings()
            val executionOs: OS? = execSettings.getExecutionOs()
            val runUnder: RunUnder = execSettings.getRunUnder()
            when (runUnder) {
                -> {
                    args.add(
                        execSettings
                            .getRunUnderExecutable()
                            .getRunfilesPath()
                            .getCallablePathStringForOs(executionOs)
                    )
                }

                -> {
                    if (execSettings.needsShell()) {
                        // TestActionBuilder constructs TestRunnerAction with a 'null' shell only when none is
                        // required. Something clearly went wrong.
                        com.google.common.base.Preconditions.checkNotNull<Any?>(
                            testAction.getShExecutableMaybe(),
                            "%s",
                            testAction
                        )
                        val shellExecutable: String? =
                            testAction.getShExecutableMaybe().getCallablePathStringForOs(executionOs)
                        args.add(shellExecutable)
                        args.add("-c")
                        args.add("\"$@\"")
                        args.add(shellExecutable) // Sets $0.
                    }
                    args.add(commandRunUnder.command())
                }
            }
            args.addAll(runUnder.options())
        }

        private fun getTestAttemptsPerLabel(
            options: ExecutionOptions, label: Label?, defaultTestAttempts: Int
        ): Int {
            // Check from the last provided, so that the last option provided takes precedence.
            for (perLabelAttempts in com.google.common.collect.Lists.reverse<T>(options.getTestAttempts())) {
                if (perLabelAttempts.isIncluded(label)) {
                    val attempts: String? =
                        com.google.common.collect.Iterables.getOnlyElement<T?>(perLabelAttempts.getOptions())
                    if ("default" == attempts) {
                        return defaultTestAttempts
                    }
                    return java.lang.Integer.parseInt(attempts)
                }
            }
            return defaultTestAttempts
        }

        fun getTmpDirName(action: TestRunnerAction): String {
            val digest: Fingerprint = Fingerprint()
            digest.addPath(action.getExecutionSettings().getExecutable().getExecPath())
            digest.addInt(action.getShardNum())
            digest.addInt(action.getRunNumber())
            // Truncate the string to 32 character to avoid exceeding path length limit on Windows and macOS
            return digest.hexDigestAndReset().substring(0, 32)
        }

        private val PARSE_TEST_RESULT_FORMATS: com.google.common.collect.ImmutableSet<TestSummaryFormat?> =
            com.google.common.collect.Sets.immutableEnumSet<TestSummaryFormat?>(
                TestSummaryFormat.DETAILED,
                TestSummaryFormat.DETAILED_UNCACHED,
                TestSummaryFormat.TESTCASE
            )

        /**
         * Returns a temporary directory for all tests in a workspace to use. Individual tests should
         * create child directories to actually use.
         * 
         * 
         * This either dynamically generates a directory name or uses the directory specified by
         * --test_tmpdir. This does not create the directory.
         */
        fun getTmpRoot(workspace: Path, execRoot: Path, executionOptions: ExecutionOptions): Path {
            return if (executionOptions.getTestTmpDir() != null)
                workspace.getRelative(executionOptions.getTestTmpDir()).getRelative(TEST_TMP_ROOT)
            else
                execRoot.getRelative(TEST_TMP_ROOT)
        }

        /**
         * Returns a subset containing all variables in the given list if they are defined in the given
         * environment.
         */
        @com.google.common.annotations.VisibleForTesting
        fun getMapping(
            variables: Iterable<String?>, environment: MutableMap<String?, String?>
        ): MutableMap<String?, String?> {
            val result: MutableMap<String?, String?> = HashMap<String?, String?>()
            for (`var` in variables) {
                if (environment.containsKey(`var`)) {
                    result.put(`var`, environment.get(`var`))
                }
            }
            return result
        }

        protected fun closeSuppressed(e: Throwable, c: java.io.Closeable?) {
            if (c == null) {
                return
            }
            try {
                c.close()
            } catch (e2: IOException) {
                e.addSuppressed(e2)
            }
        }
    }
}
