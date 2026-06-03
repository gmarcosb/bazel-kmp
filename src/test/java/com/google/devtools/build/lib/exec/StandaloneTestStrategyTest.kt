// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionContext

/** Unit tests for [StandaloneTestStrategy].  */
@RunWith(TestParameterInjector::class)
class StandaloneTestStrategyTest : BuildViewTestCase() {
    private class TestedStandaloneTestStrategy(
        executionOptions: ExecutionOptions?,
        testSummaryOptions: TestSummaryOptions?,
        tmpDirRoot: Path?
    ) : StandaloneTestStrategy(executionOptions, testSummaryOptions, tmpDirRoot) {
        var postedResult: TestResult? = null

        protected override fun postTestResult(
            actionExecutionContext: ActionExecutionContext?, result: TestResult?
        ) {
            postedResult = result
        }
    }

    private inner class FakeActionExecutionContext(
        fileOutErr: FileOutErr?,
        actionContextRegistry: ActionContext.ActionContextRegistry,
        inputMetadataProvider: InputMetadataProvider?,
        outputMetadataStore: OutputMetadataStore?
    ) : ActionExecutionContext( /* executor= */
        null,
        inputMetadataProvider,
        ActionInputPrefetcher.NONE,
        ActionKeyContext(),  /* outputMetadataStore= */
        outputMetadataStore,  /* rewindingEnabled= */
        false,
        LostInputsCheck.NONE,
        fileOutErr,  /* eventHandler= */
        null,  /* clientEnv= */
        com.google.common.collect.ImmutableMap.of<K?, V?>("PATH", "/usr/bin:/bin"),  /* actionFileSystem= */
        null,
        DiscoveredModulesPruner.DEFAULT,
        SyscallCache.NO_CACHE,
        ThreadStateReceiver.NULL_INSTANCE
    ) {
        private val actionContextRegistry: ActionContext.ActionContextRegistry

        internal constructor(
            fileOutErr: FileOutErr?,
            inputMetadataProvider: InputMetadataProvider?,
            spawnStrategy: SpawnStrategy?
        ) : this(
            fileOutErr,
            toContextRegistry(spawnStrategy, fileSystem, directories),
            inputMetadataProvider,
            org.mockito.Mockito.mock<OutputMetadataStore?>(OutputMetadataStore::class.java)
        )

        init {
            this.actionContextRegistry = actionContextRegistry
        }

        val clock: com.google.devtools.build.lib.clock.Clock
            get() = com.google.devtools.build.lib.clock.BlazeClock.instance()

        public override fun <T : ActionContext?> getContext(type: java.lang.Class<T?>?): T? {
            return actionContextRegistry.getContext(type)
        }

        val eventHandler: ExtendedEventHandler
            get() = storedEvents

        val execRoot: Path
            get() = this@StandaloneTestStrategyTest.execRoot

        public override fun withOutputsAsInputs(outputs: Iterable<Artifact?>?): ActionExecutionContext? {
            return this
        }

        public override fun withFileOutErr(fileOutErr: FileOutErr?): ActionExecutionContext {
            return FakeActionExecutionContext(
                fileOutErr, actionContextRegistry, getInputMetadataProvider(), getOutputMetadataStore()
            )
        }
    }

    @org.junit.Rule
    val mocks: MockitoRule = MockitoJUnit.rule()

    @org.mockito.Mock
    private val spawnStrategy: SpawnStrategy? = null

    private val storedEvents: StoredEventHandler = StoredEventHandler()

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        Mockito.`when`<T?>(spawnStrategy.canExec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(true)
    }

    @Throws(java.lang.Exception::class)
    private fun getTestAction(target: String?): TestRunnerAction {
        val configuredTarget: ConfiguredTarget = getConfiguredTarget(target)
        val testStatusArtifacts: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact?> =
            configuredTarget.getProvider(TestProvider::class.java).getTestParams().getTestStatusArtifacts()
        val testStatusArtifact: Artifact? =
            com.google.common.collect.Iterables.getOnlyElement<Artifact.DerivedArtifact?>(testStatusArtifacts)
        val action: TestRunnerAction = getGeneratingAction(testStatusArtifact) as TestRunnerAction
        action.getTestLog().getPath().getParentDirectory().createDirectoryAndParents()
        return action
    }

    @Throws(java.lang.Exception::class)
    private fun getTestActions(target: String?): com.google.common.collect.ImmutableList<TestRunnerAction> {
        val configuredTarget: ConfiguredTarget = getConfiguredTarget(target)
        val testStatusArtifacts: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact?> =
            configuredTarget.getProvider(TestProvider::class.java).getTestParams().getTestStatusArtifacts()
        return testStatusArtifacts.stream()
            .map<Any?> { a: Artifact.DerivedArtifact? ->
                val action: TestRunnerAction = getGeneratingAction(a) as TestRunnerAction
                try {
                    action.getTestLog().getPath().getParentDirectory().createDirectoryAndParents()
                } catch (e: IOException) {
                    throw java.lang.IllegalStateException(e)
                }
                action
            }
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateTmpDirForTest() {
        // setup a test action
        scratch.file("standalone/simple_test.sh", "this does not get executed, it is mocked out")
        scratch.file(
            "standalone/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "simple_test",
            size = "small",
            srcs = ["simple_test.sh"],
        )
        
        """.trimIndent()
        )
        val testRunnerAction: TestRunnerAction = getTestAction("//standalone:simple_test")

        val tmpDirName: String = TestStrategy.getTmpDirName(testRunnerAction)
        // Make sure the length of tmpDirName doesn't change unexpectedy: it cannot be too long
        // because Windows and macOS have limitations on file path length.
        // Note: It's OK to update 32 to a smaller number if tmpDirName gets shorter.
        Truth.assertThat(tmpDirName.length).isEqualTo(32)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunTestOnce() {
        val executionOptions: ExecutionOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java)
        val testSummaryOptions: TestSummaryOptions? = TestSummaryOptions.DEFAULTS
        val tmpDirRoot: Path = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions)
        val standaloneTestStrategy =
            TestedStandaloneTestStrategy(executionOptions, testSummaryOptions, tmpDirRoot)

        // setup a test action
        scratch.file("standalone/simple_test.sh", "this does not get executed, it is mocked out")
        scratch.file(
            "standalone/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "simple_test",
            size = "small",
            srcs = ["simple_test.sh"],
        )
        
        """.trimIndent()
        )
        val testRunnerAction: TestRunnerAction = getTestAction("//standalone:simple_test")

        val expectedSpawnResult: SpawnResult =
            Builder()
                .setStatus(Status.SUCCESS)
                .setWallTimeInMs(10)
                .setRunnerName("test")
                .build()
        Mockito.`when`<T?>(spawnStrategy.exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(expectedSpawnResult))

        val actionExecutionContext: ActionExecutionContext =
            FakeActionExecutionContext(
                createTempOutErr(tmpDirRoot), inputMetadataFor(testRunnerAction), spawnStrategy
            )

        // actual StandaloneTestStrategy execution
        val spawnResults: com.google.common.collect.ImmutableList<SpawnResult> =
            execute(testRunnerAction, actionExecutionContext, standaloneTestStrategy)

        Truth.assertThat(spawnResults).contains(expectedSpawnResult)
        val result: TestResult? = standaloneTestStrategy.postedResult
        assertThat(result).isNotNull()
        assertThat(result.isCached()).isFalse()
        assertThat(result.getTestAction()).isSameInstanceAs(testRunnerAction)
        assertThat(result.getData().getTestPassed()).isTrue()
        assertThat(result.getData().getExitCode()).isEqualTo(0)
        assertThat(result.getData().getRemotelyCached()).isFalse()
        assertThat(result.getData().getIsRemoteStrategy()).isFalse()
        assertThat(result.getData().getRunDurationMillis()).isEqualTo(10)
        assertThat(result.getData().getTestTimesList()).containsExactly(10L)
        val attempt: TestAttempt =
            storedEvents.getPosts().stream()
                .filter { obj: Postable? -> TestAttempt::class.java.isInstance(obj) }
                .map<TestAttempt?> { obj: Postable? -> TestAttempt::class.java.cast(obj) }
                .collect(com.google.common.collect.MoreCollectors.onlyElement<TestAttempt>())
        assertThat(attempt.getExecutionInfo().getStrategy()).isEqualTo("test")
        assertThat(attempt.getExecutionInfo().getHostname()).isEqualTo("")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunFlakyTest() {
        val executionOptions: ExecutionOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java)
        val testSummaryOptions: TestSummaryOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(TestSummaryOptions::class.java)

        val tmpDirRoot: Path = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions)
        val standaloneTestStrategy =
            TestedStandaloneTestStrategy(executionOptions, testSummaryOptions, tmpDirRoot)

        // setup a test action
        scratch.file("standalone/simple_test.sh", "this does not get executed, it is mocked out")
        scratch.file(
            "standalone/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "simple_test",
            size = "small",
            srcs = ["simple_test.sh"],
            flaky = True,
        )
        
        """.trimIndent()
        )
        val testRunnerAction: TestRunnerAction = getTestAction("//standalone:simple_test")

        val failSpawnResult: SpawnResult? =
            Builder()
                .setStatus(Status.NON_ZERO_EXIT)
                .setExitCode(1)
                .setFailureDetail(NON_ZERO_EXIT_DETAILS)
                .setWallTimeInMs(10)
                .setRunnerName("test")
                .build()
        val passSpawnResult: SpawnResult =
            Builder()
                .setStatus(Status.SUCCESS)
                .setWallTimeInMs(15)
                .setRunnerName("test")
                .build()
        Mockito.`when`<T?>(spawnStrategy.exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenThrow(SpawnExecException("test failed", failSpawnResult, false)) // XML generation
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(passSpawnResult))
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(passSpawnResult)) // XML generation
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(passSpawnResult))

        val actionExecutionContext: ActionExecutionContext =
            FakeActionExecutionContext(
                createTempOutErr(tmpDirRoot), inputMetadataFor(testRunnerAction), spawnStrategy
            )

        // actual StandaloneTestStrategy execution
        val spawnResults: com.google.common.collect.ImmutableList<SpawnResult> =
            execute(testRunnerAction, actionExecutionContext, standaloneTestStrategy)

        Truth.assertThat(spawnResults)
            .containsExactly(failSpawnResult, passSpawnResult, passSpawnResult, passSpawnResult)
            .inOrder()

        val result: TestResult? = standaloneTestStrategy.postedResult
        assertThat(result).isNotNull()
        assertThat(result.isCached()).isFalse()
        assertThat(result.getTestAction()).isSameInstanceAs(testRunnerAction)
        assertThat(result.getData().getStatus()).isEqualTo(BlazeTestStatus.FLAKY)
        assertThat(result.getData().getTestPassed()).isTrue()
        assertThat(result.getData().getExitCode()).isEqualTo(0)
        assertThat(result.getData().getRemotelyCached()).isFalse()
        assertThat(result.getData().getIsRemoteStrategy()).isFalse()
        assertThat(result.getData().getRunDurationMillis()).isEqualTo(15L)
        assertThat(result.getData().getTestTimesList()).containsExactly(10L, 15L)
        val attempts: com.google.common.collect.ImmutableList<TestAttempt> =
            storedEvents.getPosts().stream()
                .filter { obj: Postable? -> TestAttempt::class.java.isInstance(obj) }
                .map<TestAttempt?> { obj: Postable? -> TestAttempt::class.java.cast(obj) }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<TestAttempt>())
        Truth.assertThat(attempts).hasSize(2)
        val failedAttempt: TestAttempt = attempts.get(0)
        assertThat(failedAttempt.getExecutionInfo().getStrategy()).isEqualTo("test")
        assertThat(failedAttempt.getExecutionInfo().getHostname()).isEqualTo("")
        assertThat(failedAttempt.getStatus()).isEqualTo(TestStatus.FAILED)
        assertThat(failedAttempt.getExecutionInfo().getExitCode()).isEqualTo(1)
        assertThat(failedAttempt.getExecutionInfo().getCachedRemotely()).isFalse()
        val okAttempt: TestAttempt = attempts.get(1)
        assertThat(okAttempt.getStatus()).isEqualTo(TestStatus.PASSED)
        assertThat(okAttempt.getExecutionInfo().getExitCode()).isEqualTo(0)
        assertThat(okAttempt.getExecutionInfo().getStrategy()).isEqualTo("test")
        assertThat(okAttempt.getExecutionInfo().getHostname()).isEqualTo("")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunTestRemotely() {
        val executionOptions: ExecutionOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java)
        val testSummaryOptions: TestSummaryOptions? = TestSummaryOptions.DEFAULTS
        val tmpDirRoot: Path = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions)
        val standaloneTestStrategy =
            TestedStandaloneTestStrategy(executionOptions, testSummaryOptions, tmpDirRoot)

        // setup a test action
        scratch.file("standalone/simple_test.sh", "this does not get executed, it is mocked out")
        scratch.file(
            "standalone/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "simple_test",
            size = "small",
            srcs = ["simple_test.sh"],
        )
        
        """.trimIndent()
        )
        val testRunnerAction: TestRunnerAction = getTestAction("//standalone:simple_test")

        val expectedSpawnResult: SpawnResult =
            Builder()
                .setStatus(Status.SUCCESS)
                .setWallTimeInMs(10)
                .setRunnerName("remote")
                .setExecutorHostname("a-remote-host")
                .build()
        Mockito.`when`<T?>(spawnStrategy.exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(expectedSpawnResult))

        val actionExecutionContext: ActionExecutionContext =
            FakeActionExecutionContext(
                createTempOutErr(tmpDirRoot), inputMetadataFor(testRunnerAction), spawnStrategy
            )

        // actual StandaloneTestStrategy execution
        val spawnResults: com.google.common.collect.ImmutableList<SpawnResult> =
            execute(testRunnerAction, actionExecutionContext, standaloneTestStrategy)

        Truth.assertThat(spawnResults).contains(expectedSpawnResult)

        val result: TestResult? = standaloneTestStrategy.postedResult
        assertThat(result).isNotNull()
        assertThat(result.isCached()).isFalse()
        assertThat(result.getTestAction()).isSameInstanceAs(testRunnerAction)
        assertThat(result.getData().getTestPassed()).isTrue()
        assertThat(result.getData().getExitCode()).isEqualTo(0)
        assertThat(result.getData().getRemotelyCached()).isFalse()
        assertThat(result.getData().getIsRemoteStrategy()).isTrue()
        assertThat(result.getData().getRunDurationMillis()).isEqualTo(10)
        assertThat(result.getData().getTestTimesList()).containsExactly(10L)
        val attempt: TestAttempt =
            storedEvents.getPosts().stream()
                .filter { obj: Postable? -> TestAttempt::class.java.isInstance(obj) }
                .map<TestAttempt?> { obj: Postable? -> TestAttempt::class.java.cast(obj) }
                .collect(com.google.common.collect.MoreCollectors.onlyElement<TestAttempt>())
        assertThat(attempt.getStatus()).isEqualTo(TestStatus.PASSED)
        assertThat(attempt.getExecutionInfo().getExitCode()).isEqualTo(0)
        assertThat(attempt.getExecutionInfo().getStrategy()).isEqualTo("remote")
        assertThat(attempt.getExecutionInfo().getHostname()).isEqualTo("a-remote-host")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunRemotelyCachedTest() {
        val executionOptions: ExecutionOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java)
        val testSummaryOptions: TestSummaryOptions? = TestSummaryOptions.DEFAULTS
        val tmpDirRoot: Path = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions)
        val standaloneTestStrategy =
            TestedStandaloneTestStrategy(executionOptions, testSummaryOptions, tmpDirRoot)

        // setup a test action
        scratch.file("standalone/simple_test.sh", "this does not get executed, it is mocked out")
        scratch.file(
            "standalone/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "simple_test",
            size = "small",
            srcs = ["simple_test.sh"],
        )
        
        """.trimIndent()
        )
        val testRunnerAction: TestRunnerAction = getTestAction("//standalone:simple_test")

        val expectedSpawnResult: SpawnResult =
            Builder()
                .setStatus(Status.SUCCESS)
                .setCacheHit(true)
                .setWallTimeInMs(10)
                .setRunnerName("remote cache")
                .build()
        Mockito.`when`<T?>(spawnStrategy.exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(expectedSpawnResult))

        val actionExecutionContext: ActionExecutionContext =
            FakeActionExecutionContext(
                createTempOutErr(tmpDirRoot), inputMetadataFor(testRunnerAction), spawnStrategy
            )

        // actual StandaloneTestStrategy execution
        val spawnResults: com.google.common.collect.ImmutableList<SpawnResult> =
            execute(testRunnerAction, actionExecutionContext, standaloneTestStrategy)

        // check that the rigged SpawnResult was returned
        Truth.assertThat(spawnResults).contains(expectedSpawnResult)

        val result: TestResult? = standaloneTestStrategy.postedResult
        assertThat(result).isNotNull()
        assertThat(result.isCached()).isFalse()
        assertThat(result.getTestAction()).isSameInstanceAs(testRunnerAction)
        assertThat(result.getData().getTestPassed()).isTrue()
        assertThat(result.getData().getExitCode()).isEqualTo(0)
        assertThat(result.getData().getRemotelyCached()).isTrue()
        assertThat(result.getData().getIsRemoteStrategy()).isFalse()
        assertThat(result.getData().getRunDurationMillis()).isEqualTo(10)
        assertThat(result.getData().getTestTimesList()).containsExactly(10L)
        val attempt: TestAttempt =
            storedEvents.getPosts().stream()
                .filter { obj: Postable? -> TestAttempt::class.java.isInstance(obj) }
                .map<TestAttempt?> { obj: Postable? -> TestAttempt::class.java.cast(obj) }
                .collect(com.google.common.collect.MoreCollectors.onlyElement<TestAttempt>())
        assertThat(attempt.getExecutionInfo().getStrategy()).isEqualTo("remote cache")
        assertThat(attempt.getExecutionInfo().getHostname()).isEqualTo("")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testThatTestLogAndOutputAreReturned() {
        val executionOptions: ExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(ExecutionOptions::class.java)
        val testSummaryOptions: TestSummaryOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(TestSummaryOptions::class.java)
        executionOptions.testOutput = ExecutionOptions.TestOutputFormat.ERRORS
        val tmpDirRoot: Path = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions)
        val standaloneTestStrategy =
            TestedStandaloneTestStrategy(executionOptions, testSummaryOptions, tmpDirRoot)

        // setup a test action
        scratch.file("standalone/failing_test.sh", "this does not get executed, it is mocked out")
        scratch.file(
            "standalone/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "failing_test",
            size = "small",
            srcs = ["failing_test.sh"],
        )
        
        """.trimIndent()
        )
        val testRunnerAction: TestRunnerAction = getTestAction("//standalone:failing_test")

        val expectedSpawnResult: SpawnResult? = FAILED_TEST_SPAWN
        Mockito.`when`<T?>(spawnStrategy.exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    val spawn: Spawn = invocation.getArgument<Spawn>(0)
                    if (spawn.getOutputFiles().size() !== 1) {
                        val context: ActionExecutionContext = invocation.getArgument<ActionExecutionContext>(1)
                        val outErr: FileOutErr = context.getFileOutErr()
                        outErr.getOutputStream().use { stream ->
                            stream.write("This will not appear in the test output: bla\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                            stream.write((TestLogHelper.HEADER_DELIMITER + "\n").toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                            stream.write("This will appear in the test output: foo\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                        }
                        throw SpawnExecException(
                            "Failure!!",
                            expectedSpawnResult,  /* forciblyRunRemotely= */
                            false,  /* catastrophe= */
                            false
                        )
                    } else {
                        return@thenAnswer com.google.common.collect.ImmutableList.of<Any?>(PASSED_TEST_SPAWN)
                    }
                })

        val outErr: FileOutErr = createTempOutErr(tmpDirRoot)
        val actionExecutionContext: ActionExecutionContext =
            FakeActionExecutionContext(outErr, inputMetadataFor(testRunnerAction), spawnStrategy)

        // actual StandaloneTestStrategy execution
        val spawnResults: com.google.common.collect.ImmutableList<SpawnResult> =
            execute(testRunnerAction, actionExecutionContext, standaloneTestStrategy)

        // check that the rigged SpawnResult was returned
        Truth.assertThat(spawnResults).contains(expectedSpawnResult)
        // check that the test log contains all the output
        val logData: String? = FileSystemUtils.readContent(
            testRunnerAction.getTestLog().getPath(),
            java.nio.charset.StandardCharsets.UTF_8
        )
        Truth.assertThat(logData).contains("bla")
        Truth.assertThat(logData).contains(TestLogHelper.HEADER_DELIMITER)
        Truth.assertThat(logData).contains("foo")
        // check that the test stdout contains all the expected output
        outErr.close() // Create the output files.

        val outData: String? =
            FileSystemUtils.readContent(outErr.getOutputPath(), java.nio.charset.StandardCharsets.UTF_8)
        Truth.assertThat(outData).contains("==================== Test output for //standalone:failing_test:")
        Truth.assertThat(outData).doesNotContain("bla")
        Truth.assertThat(outData).doesNotContain(TestLogHelper.HEADER_DELIMITER)
        Truth.assertThat(outData).contains("foo")
        Truth.assertThat(outData)
            .contains(
                "================================================================================"
            )
        assertThat(outErr.getErrorPath().exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testThatTestLogAndOutputAreReturnedWithSplitXmlGeneration() {
        val executionOptions: ExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(ExecutionOptions::class.java)
        val testSummaryOptions: TestSummaryOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(TestSummaryOptions::class.java)
        executionOptions.testOutput = ExecutionOptions.TestOutputFormat.ERRORS
        val tmpDirRoot: Path = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions)
        val standaloneTestStrategy =
            TestedStandaloneTestStrategy(executionOptions, testSummaryOptions, tmpDirRoot)

        // setup a test action
        scratch.file("standalone/failing_test.sh", "this does not get executed, it is mocked out")
        scratch.file(
            "standalone/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "failing_test",
            size = "small",
            srcs = ["failing_test.sh"],
            tags = ["local"],
        )
        
        """.trimIndent()
        )
        val testRunnerAction: TestRunnerAction = getTestAction("//standalone:failing_test")

        val testSpawnResult: SpawnResult? = FAILED_TEST_SPAWN
        val xmlGeneratorSpawnResult: SpawnResult = PASSED_TEST_SPAWN
        val called: MutableList<FileOutErr> = java.util.ArrayList<FileOutErr>()
        Mockito.`when`<T?>(spawnStrategy.exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    val spawn: Spawn = invocation.getArgument<Spawn>(0)
                    // Test that both spawns have the local tag attached as a execution info
                    assertThat(spawn.getExecutionInfo()).containsKey("local")
                    val context: ActionExecutionContext = invocation.getArgument<ActionExecutionContext>(1)
                    val outErr: FileOutErr = context.getFileOutErr()
                    called.add(outErr)
                    if (spawn.getOutputFiles().size() !== 1) {
                        outErr.getOutputStream().use { stream ->
                            stream.write("This will not appear in the test output: bla\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                            stream.write((TestLogHelper.HEADER_DELIMITER + "\n").toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                            stream.write("This will appear in the test output: foo\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                        }
                        throw SpawnExecException(
                            "Failure!!",
                            testSpawnResult,  /* forciblyRunRemotely= */
                            false,  /* catastrophe= */
                            false
                        )
                    } else {
                        val testName = "standalone/failing_test"
                        assertThat(spawn.getEnvironment()).containsEntry("TEST_BINARY", testName)
                        return@thenAnswer com.google.common.collect.ImmutableList.of<Any?>(xmlGeneratorSpawnResult)
                    }
                })

        val outErr: FileOutErr = createTempOutErr(tmpDirRoot)
        val actionExecutionContext: ActionExecutionContext =
            FakeActionExecutionContext(outErr, inputMetadataFor(testRunnerAction), spawnStrategy)

        // actual StandaloneTestStrategy execution
        val spawnResults: com.google.common.collect.ImmutableList<SpawnResult> =
            execute(testRunnerAction, actionExecutionContext, standaloneTestStrategy)

        // check that the rigged SpawnResult was returned
        Truth.assertThat(spawnResults).containsExactly(testSpawnResult, xmlGeneratorSpawnResult)
        // check that the test log contains all the output
        val logData: String? = FileSystemUtils.readContent(
            testRunnerAction.getTestLog().getPath(),
            java.nio.charset.StandardCharsets.UTF_8
        )
        Truth.assertThat(logData).contains("bla")
        Truth.assertThat(logData).contains(TestLogHelper.HEADER_DELIMITER)
        Truth.assertThat(logData).contains("foo")
        // check that the test stdout contains all the expected output
        outErr.close() // Create the output files.
        val outData: String? =
            FileSystemUtils.readContent(outErr.getOutputPath(), java.nio.charset.StandardCharsets.UTF_8)
        Truth.assertThat(outData).contains("==================== Test output for //standalone:failing_test:")
        Truth.assertThat(outData).doesNotContain("bla")
        Truth.assertThat(outData).doesNotContain(TestLogHelper.HEADER_DELIMITER)
        Truth.assertThat(outData).contains("foo")
        Truth.assertThat(outData)
            .contains(
                "================================================================================"
            )
        assertThat(outErr.getErrorPath().exists()).isFalse()
        Truth.assertThat(called).hasSize(2)
        Truth.assertThat(called).containsNoDuplicates()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyOutputCreatesEmptyLogFile() {
        val executionOptions: ExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(ExecutionOptions::class.java)
        val testSummaryOptions: TestSummaryOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(TestSummaryOptions::class.java)
        executionOptions.testOutput = ExecutionOptions.TestOutputFormat.ALL
        val tmpDirRoot: Path = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions)
        val standaloneTestStrategy =
            TestedStandaloneTestStrategy(executionOptions, testSummaryOptions, tmpDirRoot)

        // setup a test action
        scratch.file("standalone/empty_test.sh", "this does not get executed, it is mocked out")
        scratch.file(
            "standalone/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "empty_test",
            size = "small",
            srcs = ["empty_test.sh"],
        )
        
        """.trimIndent()
        )
        val testRunnerAction: TestRunnerAction = getTestAction("//standalone:empty_test")

        val expectedSpawnResult: SpawnResult = PASSED_TEST_SPAWN
        Mockito.`when`<T?>(spawnStrategy.exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(expectedSpawnResult))

        val outErr: FileOutErr = createTempOutErr(tmpDirRoot)
        val actionExecutionContext: ActionExecutionContext =
            FakeActionExecutionContext(outErr, inputMetadataFor(testRunnerAction), spawnStrategy)

        // actual StandaloneTestStrategy execution
        val spawnResults: com.google.common.collect.ImmutableList<SpawnResult> =
            execute(testRunnerAction, actionExecutionContext, standaloneTestStrategy)

        // check that the rigged SpawnResult was returned
        Truth.assertThat(spawnResults).contains(expectedSpawnResult)
        // check that the test log contains all the output
        val logData: String? = FileSystemUtils.readContent(
            testRunnerAction.getTestLog().getPath(),
            java.nio.charset.StandardCharsets.UTF_8
        )
        Truth.assertThat(logData).isEmpty()
        // check that the test stdout contains all the expected output
        outErr.close() // Create the output files.
        val outData: String? =
            FileSystemUtils.readContent(outErr.getOutputPath(), java.nio.charset.StandardCharsets.UTF_8)
        val emptyOutput =
            ("==================== Test output for"
                    + " //standalone:empty_test:(\\s)*================================================================================(\\s)*")
        Truth.assertThat(outData).matches(emptyOutput)
        assertThat(outErr.getErrorPath().exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAppendStdErrDoesNotBusyLoop() {
        val executionOptions: ExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(ExecutionOptions::class.java)
        val testSummaryOptions: TestSummaryOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(TestSummaryOptions::class.java)
        executionOptions.testOutput = ExecutionOptions.TestOutputFormat.ALL
        val tmpDirRoot: Path = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions)
        val standaloneTestStrategy =
            TestedStandaloneTestStrategy(executionOptions, testSummaryOptions, tmpDirRoot)

        // setup a test action
        scratch.file("standalone/empty_test.sh", "this does not get executed, it is mocked out")
        scratch.file(
            "standalone/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "empty_test",
            size = "small",
            srcs = ["empty_test.sh"],
        )
        
        """.trimIndent()
        )
        val testRunnerAction: TestRunnerAction = getTestAction("//standalone:empty_test")

        Mockito.`when`<T?>(spawnStrategy.exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .then(
                Answer { invocation: InvocationOnMock? ->
                    (invocation.getArgument<Any?>(1) as ActionExecutionContext).getFileOutErr().printErr("Foo")
                    com.google.common.collect.ImmutableList.of<Any?>(PASSED_TEST_SPAWN)
                })

        val outErr: FileOutErr = createTempOutErr(tmpDirRoot)
        val actionExecutionContext: ActionExecutionContext =
            FakeActionExecutionContext(outErr, inputMetadataFor(testRunnerAction), spawnStrategy)

        // actual StandaloneTestStrategy execution
        execute(testRunnerAction, actionExecutionContext, standaloneTestStrategy)

        // check that the test stdout contains all the expected output
        val outData: String? =
            FileSystemUtils.readContent(outErr.getOutputPath(), java.nio.charset.StandardCharsets.UTF_8)
        Truth.assertThat(outData).contains("Foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExperimentalCancelConcurrentTests(
        @TestParameter("ON_PASSED", "ON_FAILED") cancelConcurrentTests: CancelConcurrentTests?
    ) {
        useConfiguration(
            "--runs_per_test=2",
            "--runs_per_test_detects_flakes",
            "--experimental_cancel_concurrent_tests=" + cancelConcurrentTests
        )
        val testOnPassed = cancelConcurrentTests === CancelConcurrentTests.ON_PASSED
        val executionOptions: ExecutionOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java)
        val testSummaryOptions: TestSummaryOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(TestSummaryOptions::class.java)
        val tmpDirRoot: Path = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions)
        val standaloneTestStrategy =
            TestedStandaloneTestStrategy(executionOptions, testSummaryOptions, tmpDirRoot)

        scratch.file("standalone/empty_test.sh", "this does not get executed, it is mocked out")
        scratch.file(
            "standalone/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "empty_test",
            size = "small",
            srcs = ["empty_test.sh"],
        )
        
        """.trimIndent()
        )
        val testRunnerActions: com.google.common.collect.ImmutableList<TestRunnerAction> =
            getTestActions("//standalone:empty_test")
        Truth.assertThat(testRunnerActions).hasSize(2)

        val actionA: TestRunnerAction = testRunnerActions.get(0)
        val actionB: TestRunnerAction = testRunnerActions.get(1)
        val attemptGroup: AttemptGroup =
            standaloneTestStrategy.getAttemptGroup(actionA.getOwner(), actionA.getShardNum())
        assertThat(attemptGroup)
            .isSameInstanceAs(
                standaloneTestStrategy.getAttemptGroup(actionB.getOwner(), actionB.getShardNum())
            )

        Mockito.`when`<T?>(spawnStrategy.exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .then(
                Answer { invocation: InvocationOnMock? ->
                    // Avoid triggering split XML generation by creating an empty XML file.
                    FileSystemUtils.touchFile(actionA.resolve(execRoot).getXmlOutputPath())
                    if (testOnPassed) {
                        return@then com.google.common.collect.ImmutableList.of<Any?>(PASSED_TEST_SPAWN)
                    } else {
                        throw SpawnExecException("", FAILED_TEST_SPAWN, false)
                    }
                })
            .thenThrow(java.lang.AssertionError("failure: this should not have been called"))

        val inputMetadataProvider: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        inputMetadataProvider.putRunfilesTree(actionA.getRunfilesTree(), runfilesTreeFor(actionA))
        inputMetadataProvider.putRunfilesTree(actionB.getRunfilesTree(), runfilesTreeFor(actionB))

        val actionExecutionContext: ActionExecutionContext =
            FakeActionExecutionContext(
                createTempOutErr(tmpDirRoot), inputMetadataProvider, spawnStrategy
            )
        val resultA: com.google.common.collect.ImmutableList<SpawnResult> =
            execute(actionA, actionExecutionContext, standaloneTestStrategy)
        assertThat(attemptGroup.cancelled()).isTrue()
        Mockito.verify<Any?>(spawnStrategy).exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        Truth.assertThat(resultA).hasSize(1)
        assertThat(standaloneTestStrategy.postedResult).isNotNull()
        assertThat(standaloneTestStrategy.postedResult.getData().getStatus())
            .isEqualTo(if (testOnPassed) BlazeTestStatus.PASSED else BlazeTestStatus.FAILED)
        assertThat(standaloneTestStrategy.postedResult.getData().getExitCode())
            .isEqualTo(if (testOnPassed) 0 else 1)
        assertContainsPrefixedEvent(
            storedEvents.getEvents(),
            com.google.devtools.build.lib.events.Event.of(
                if (testOnPassed) com.google.devtools.build.lib.events.EventKind.PASS else com.google.devtools.build.lib.events.EventKind.FAIL,
                null,
                "//standalone:empty_test (run 1 of 2)"
            )
        )
        // Reset postedResult.
        standaloneTestStrategy.postedResult = null

        val resultB: com.google.common.collect.ImmutableList<SpawnResult> =
            execute(actionB, actionExecutionContext, standaloneTestStrategy)
        Truth.assertThat(resultB).isEmpty()
        assertThat(standaloneTestStrategy.postedResult).isNotNull()
        assertThat(standaloneTestStrategy.postedResult.getData().getStatus())
            .isEqualTo(BlazeTestStatus.INCOMPLETE)
        Truth.assertThat(storedEvents.getEvents())
            .contains(
                com.google.devtools.build.lib.events.Event.of(
                    com.google.devtools.build.lib.events.EventKind.CANCELLED,
                    null,
                    "//standalone:empty_test (run 2 of 2)"
                )
            )
        // Check that there are no ERROR events.
        Truth.assertThat(
            storedEvents.getEvents().stream()
                .filter { e: com.google.devtools.build.lib.events.Event? -> e.getKind() == com.google.devtools.build.lib.events.EventKind.ERROR }
                .collect(Collectors.toList()))
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExperimentalCancelConcurrentTestsDoesNotTriggerOnUnexpectedResult(
        @TestParameter("ON_PASSED", "ON_FAILED") cancelConcurrentTests: CancelConcurrentTests?
    ) {
        useConfiguration(
            "--runs_per_test=2",
            "--runs_per_test_detects_flakes",
            "--experimental_cancel_concurrent_tests=" + cancelConcurrentTests
        )
        val testOnPassed = cancelConcurrentTests === CancelConcurrentTests.ON_PASSED
        val executionOptions: ExecutionOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java)
        val testSummaryOptions: TestSummaryOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(TestSummaryOptions::class.java)
        val tmpDirRoot: Path = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions)
        val standaloneTestStrategy =
            TestedStandaloneTestStrategy(executionOptions, testSummaryOptions, tmpDirRoot)

        scratch.file("standalone/empty_test.sh", "this does not get executed, it is mocked out")
        scratch.file(
            "standalone/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "empty_test",
            size = "small",
            srcs = ["empty_test.sh"],
        )
        
        """.trimIndent()
        )
        val testRunnerActions: com.google.common.collect.ImmutableList<TestRunnerAction> =
            getTestActions("//standalone:empty_test")
        Truth.assertThat(testRunnerActions).hasSize(2)

        val actionA: TestRunnerAction = testRunnerActions.get(0)
        val actionB: TestRunnerAction = testRunnerActions.get(1)
        val attemptGroup: AttemptGroup =
            standaloneTestStrategy.getAttemptGroup(actionA.getOwner(), actionA.getShardNum())
        assertThat(attemptGroup)
            .isSameInstanceAs(
                standaloneTestStrategy.getAttemptGroup(actionB.getOwner(), actionB.getShardNum())
            )
        assertThat(attemptGroup.cancelled()).isFalse()

        Mockito.`when`<T?>(spawnStrategy.exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .then(
                Answer { invocation: InvocationOnMock? ->
                    // Avoid triggering split XML generation by creating an empty XML file.
                    FileSystemUtils.touchFile(actionA.resolve(execRoot).getXmlOutputPath())
                    if (testOnPassed) {
                        throw SpawnExecException("", FAILED_TEST_SPAWN, false)
                    } else {
                        return@then com.google.common.collect.ImmutableList.of<Any?>(PASSED_TEST_SPAWN)
                    }
                })
            .then(
                Answer { invocation: InvocationOnMock? ->
                    // Avoid triggering split XML generation by creating an empty XML file.
                    FileSystemUtils.touchFile(actionB.resolve(execRoot).getXmlOutputPath())
                    if (testOnPassed) {
                        return@then com.google.common.collect.ImmutableList.of<Any?>(PASSED_TEST_SPAWN)
                    } else {
                        throw SpawnExecException("", FAILED_TEST_SPAWN, false)
                    }
                })

        val inputMetadataProvider: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        inputMetadataProvider.putRunfilesTree(actionA.getRunfilesTree(), runfilesTreeFor(actionA))
        inputMetadataProvider.putRunfilesTree(actionB.getRunfilesTree(), runfilesTreeFor(actionB))

        val actionExecutionContext: ActionExecutionContext =
            FakeActionExecutionContext(
                createTempOutErr(tmpDirRoot), inputMetadataProvider, spawnStrategy
            )
        val resultA: com.google.common.collect.ImmutableList<SpawnResult> =
            execute(actionA, actionExecutionContext, standaloneTestStrategy)
        assertThat(attemptGroup.cancelled()).isFalse()
        Mockito.verify<Any?>(spawnStrategy).exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        Truth.assertThat(resultA).hasSize(1)
        assertThat(standaloneTestStrategy.postedResult).isNotNull()
        assertThat(standaloneTestStrategy.postedResult.getData().getStatus())
            .isEqualTo(if (testOnPassed) BlazeTestStatus.FAILED else BlazeTestStatus.PASSED)
        assertThat(standaloneTestStrategy.postedResult.getData().getExitCode())
            .isEqualTo(if (testOnPassed) 1 else 0)
        assertContainsPrefixedEvent(
            storedEvents.getEvents(),
            com.google.devtools.build.lib.events.Event.of(
                if (testOnPassed) com.google.devtools.build.lib.events.EventKind.FAIL else com.google.devtools.build.lib.events.EventKind.PASS,
                null,
                "//standalone:empty_test (run 1 of 2)"
            )
        )
        // Reset postedResult.
        standaloneTestStrategy.postedResult = null

        val resultB: com.google.common.collect.ImmutableList<SpawnResult> =
            execute(actionB, actionExecutionContext, standaloneTestStrategy)
        assertThat(attemptGroup.cancelled()).isTrue()
        Truth.assertThat(resultB).hasSize(1)
        assertThat(standaloneTestStrategy.postedResult).isNotNull()
        assertThat(standaloneTestStrategy.postedResult.getData().getStatus())
            .isEqualTo(if (testOnPassed) BlazeTestStatus.PASSED else BlazeTestStatus.FAILED)
        assertThat(standaloneTestStrategy.postedResult.getData().getExitCode())
            .isEqualTo(if (testOnPassed) 0 else 1)
        assertContainsPrefixedEvent(
            storedEvents.getEvents(),
            com.google.devtools.build.lib.events.Event.of(
                if (testOnPassed) com.google.devtools.build.lib.events.EventKind.PASS else com.google.devtools.build.lib.events.EventKind.FAIL,
                null,
                "//standalone:empty_test (run 2 of 2)"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExperimentalCancelConcurrentTestsAllUnexpected(
        @TestParameter("ON_PASSED", "ON_FAILED") cancelConcurrentTests: CancelConcurrentTests?
    ) {
        useConfiguration(
            "--runs_per_test=2",
            "--runs_per_test_detects_flakes",
            "--experimental_cancel_concurrent_tests=" + cancelConcurrentTests
        )
        val testOnPassed = cancelConcurrentTests === CancelConcurrentTests.ON_PASSED
        val executionOptions: ExecutionOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java)
        val testSummaryOptions: TestSummaryOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(TestSummaryOptions::class.java)
        val tmpDirRoot: Path = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions)
        val standaloneTestStrategy =
            TestedStandaloneTestStrategy(executionOptions, testSummaryOptions, tmpDirRoot)

        scratch.file("standalone/empty_test.sh", "this does not get executed, it is mocked out")
        scratch.file(
            "standalone/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "empty_test",
            size = "small",
            srcs = ["empty_test.sh"],
        )
        
        """.trimIndent()
        )
        val testRunnerActions: com.google.common.collect.ImmutableList<TestRunnerAction> =
            getTestActions("//standalone:empty_test")
        Truth.assertThat(testRunnerActions).hasSize(2)

        val actionA: TestRunnerAction = testRunnerActions.get(0)
        val actionB: TestRunnerAction = testRunnerActions.get(1)
        val attemptGroup: AttemptGroup =
            standaloneTestStrategy.getAttemptGroup(actionA.getOwner(), actionA.getShardNum())
        assertThat(attemptGroup)
            .isSameInstanceAs(
                standaloneTestStrategy.getAttemptGroup(actionB.getOwner(), actionB.getShardNum())
            )
        assertThat(attemptGroup.cancelled()).isFalse()

        Mockito.`when`<T?>(spawnStrategy.exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .then(
                Answer { invocation: InvocationOnMock? ->
                    // Avoid triggering split XML generation by creating an empty XML file.
                    FileSystemUtils.touchFile(actionA.resolve(execRoot).getXmlOutputPath())
                    if (testOnPassed) {
                        throw SpawnExecException("", FAILED_TEST_SPAWN, false)
                    } else {
                        return@then com.google.common.collect.ImmutableList.of<Any?>(PASSED_TEST_SPAWN)
                    }
                })
            .then(
                Answer { invocation: InvocationOnMock? ->
                    // Avoid triggering split XML generation by creating an empty XML file.
                    FileSystemUtils.touchFile(actionB.resolve(execRoot).getXmlOutputPath())
                    if (testOnPassed) {
                        throw SpawnExecException("", FAILED_TEST_SPAWN, false)
                    } else {
                        return@then com.google.common.collect.ImmutableList.of<Any?>(PASSED_TEST_SPAWN)
                    }
                })

        val inputMetadataProvider: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        inputMetadataProvider.putRunfilesTree(actionA.getRunfilesTree(), runfilesTreeFor(actionA))
        inputMetadataProvider.putRunfilesTree(actionB.getRunfilesTree(), runfilesTreeFor(actionB))

        val actionExecutionContext: ActionExecutionContext =
            FakeActionExecutionContext(
                createTempOutErr(tmpDirRoot), inputMetadataProvider, spawnStrategy
            )
        val resultA: com.google.common.collect.ImmutableList<SpawnResult> =
            execute(actionA, actionExecutionContext, standaloneTestStrategy)
        assertThat(attemptGroup.cancelled()).isFalse()
        Mockito.verify<Any?>(spawnStrategy).exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        Truth.assertThat(resultA).hasSize(1)
        assertThat(standaloneTestStrategy.postedResult).isNotNull()
        assertThat(standaloneTestStrategy.postedResult.getData().getStatus())
            .isEqualTo(if (testOnPassed) BlazeTestStatus.FAILED else BlazeTestStatus.PASSED)
        assertThat(standaloneTestStrategy.postedResult.getData().getExitCode())
            .isEqualTo(if (testOnPassed) 1 else 0)
        assertContainsPrefixedEvent(
            storedEvents.getEvents(),
            com.google.devtools.build.lib.events.Event.of(
                if (testOnPassed) com.google.devtools.build.lib.events.EventKind.FAIL else com.google.devtools.build.lib.events.EventKind.PASS,
                null,
                "//standalone:empty_test (run 1 of 2)"
            )
        )
        // Reset postedResult.
        standaloneTestStrategy.postedResult = null

        val resultB: com.google.common.collect.ImmutableList<SpawnResult> =
            execute(actionB, actionExecutionContext, standaloneTestStrategy)
        assertThat(attemptGroup.cancelled()).isFalse()
        Truth.assertThat(resultB).hasSize(1)
        assertThat(standaloneTestStrategy.postedResult).isNotNull()
        assertThat(standaloneTestStrategy.postedResult.getData().getStatus())
            .isEqualTo(if (testOnPassed) BlazeTestStatus.FAILED else BlazeTestStatus.PASSED)
        assertContainsPrefixedEvent(
            storedEvents.getEvents(),
            com.google.devtools.build.lib.events.Event.of(
                if (testOnPassed) com.google.devtools.build.lib.events.EventKind.FAIL else com.google.devtools.build.lib.events.EventKind.PASS,
                null,
                "//standalone:empty_test (run 2 of 2)"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun missingTestLogSpawnTestResultIsIncomplete() {
        val executionOptions: ExecutionOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java)
        val testSummaryOptions: TestSummaryOptions? = TestSummaryOptions.DEFAULTS
        val tmpDirRoot: Path = TestStrategy.getTmpRoot(rootDirectory, outputBase, executionOptions)
        val standaloneTestStrategy =
            TestedStandaloneTestStrategy(executionOptions, testSummaryOptions, tmpDirRoot)

        // setup a test action
        scratch.file("standalone/simple_test.sh", "this does not get executed, it is mocked out")
        scratch.file(
            "standalone/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "simple_test",
            size = "small",
            srcs = ["simple_test.sh"],
        )
        
        """.trimIndent()
        )
        val testRunnerAction: TestRunnerAction = getTestAction("//standalone:simple_test")
        val actionExecutionContext: ActionExecutionContext =
            FakeActionExecutionContext(
                createTempOutErr(tmpDirRoot), inputMetadataFor(testRunnerAction), spawnStrategy
            )
        val spawn: TestRunnerSpawn =
            standaloneTestStrategy.createTestRunnerSpawn(testRunnerAction, actionExecutionContext)

        val builder: TestResultData.Builder? =
            TestResultData.newBuilder().setTestPassed(true).setStatus(BlazeTestStatus.PASSED)
        val result: StandaloneTestResult? =
            StandaloneTestResult.builder()
                .setSpawnResults(com.google.common.collect.ImmutableList.of<E?>())
                .setTestResultDataBuilder(builder)
                .setExecutionInfo(ExecutionInfo.getDefaultInstance())
                .build()
        val failedResult: ProcessedAttemptResult = spawn.finalizeFailedTestAttempt(result, 0)

        assertThat(failedResult).isInstanceOf(StandaloneProcessedAttemptResult::class.java)
        val data: TestResultData = (failedResult as StandaloneProcessedAttemptResult).testResultData()
        assertThat(data.getStatus()).isEqualTo(BlazeTestStatus.INCOMPLETE)
        assertThat(data.getExitCode()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMetadataResetOnRetry() {
        scratch.file("standalone/flaky_test.sh", "mocked")
        scratch.file(
            "standalone/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "flaky_test",
            srcs = ["flaky_test.sh"],
            flaky = True,
        )
        
        """.trimIndent()
        )
        val testRunnerAction: TestRunnerAction = getTestAction("//standalone:flaky_test")

        val outputMetadataStore: OutputMetadataStore? =
            org.mockito.Mockito.mock<OutputMetadataStore?>(OutputMetadataStore::class.java)
        val context: ActionExecutionContext =
            FakeActionExecutionContext(
                createTempOutErr(outputBase),
                toContextRegistry(spawnStrategy, fileSystem, directories),
                inputMetadataFor(testRunnerAction),
                outputMetadataStore
            )

        Mockito.`when`<T?>(spawnStrategy.exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenThrow(SpawnExecException("failed", FAILED_TEST_SPAWN, false))
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(PASSED_TEST_SPAWN)) // attempt 2 pass
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(PASSED_TEST_SPAWN)) // XML generation

        execute(
            testRunnerAction,
            context,
            TestedStandaloneTestStrategy(
                com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java),
                com.google.devtools.common.options.Options.getDefaults<O?>(TestSummaryOptions::class.java),
                outputBase
            )
        )

        Mockito.verify<Any?>(outputMetadataStore, Mockito.atLeastOnce()).resetOutputs(ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSkipCoverageOnFailure() {
        useConfiguration("--collect_code_coverage", "--experimental_split_coverage_postprocessing")
        scratch.file("standalone/fail_coverage.sh", "mocked")
        scratch.file(
            "standalone/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "fail_coverage",
            srcs = ["fail_coverage.sh"],
        )
        
        """.trimIndent()
        )
        val testRunnerAction: TestRunnerAction = getTestAction("//standalone:fail_coverage")

        Mockito.`when`<T?>(spawnStrategy.exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenThrow(SpawnExecException("failed", FAILED_TEST_SPAWN, false))
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(PASSED_TEST_SPAWN)) // XML generation

        val context: ActionExecutionContext =
            FakeActionExecutionContext(
                createTempOutErr(outputBase), inputMetadataFor(testRunnerAction), spawnStrategy
            )

        execute(
            testRunnerAction,
            context,
            TestedStandaloneTestStrategy(
                com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java),
                com.google.devtools.common.options.Options.getDefaults<O?>(TestSummaryOptions::class.java),
                outputBase
            )
        )

        Mockito.verify<Any?>(spawnStrategy, Mockito.times(2))
            .exec(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()) // Only test + XML, no coverage merger.
        assertThat(testRunnerAction.getCoverageData().getPath().exists()).isTrue()
    }

    companion object {
        private val NON_ZERO_EXIT_DETAILS: FailureDetail? = FailureDetail.newBuilder()
            .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.NON_ZERO_EXIT))
            .build()
        private val FAILED_TEST_SPAWN: SpawnResult? = Builder()
            .setStatus(Status.NON_ZERO_EXIT)
            .setExitCode(1)
            .setFailureDetail(NON_ZERO_EXIT_DETAILS)
            .setRunnerName("test")
            .build()
        private val PASSED_TEST_SPAWN: SpawnResult = Builder().setStatus(Status.SUCCESS).setRunnerName("test").build()

        private fun toContextRegistry(
            spawnStrategy: SpawnStrategy?, fileSystem: FileSystem?, directories: BlazeDirectories
        ): ActionContext.ActionContextRegistry? {
            try {
                return TestExecutorBuilder(fileSystem, directories)
                    .addStrategy(spawnStrategy, "mock")
                    .setDefaultStrategies("mock")
                    .build()
            } catch (e: AbruptExitException) {
                throw java.lang.AssertionError(e)
            }
        }

        private fun createTempOutErr(tmpDirRoot: Path): FileOutErr {
            val outPath: Path? = tmpDirRoot.getRelative("test-out.txt")
            val errPath: Path? = tmpDirRoot.getRelative("test-err.txt")
            return FileOutErr(outPath, errPath)
        }

        @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
        private fun execute(
            testRunnerAction: TestRunnerAction,
            actionExecutionContext: ActionExecutionContext?,
            testActionContext: TestActionContext?
        ): com.google.common.collect.ImmutableList<SpawnResult> {
            return testRunnerAction.execute(actionExecutionContext, testActionContext).spawnResults()
        }

        private fun assertContainsPrefixedEvent(
            events: Iterable<com.google.devtools.build.lib.events.Event>,
            event: com.google.devtools.build.lib.events.Event
        ) {
            for (e in events) {
                if (e.getKind() == event.getKind() && e.getMessage().startsWith(event.getMessage())) {
                    return
                }
            }
            Truth.assertThat(events).contains(event)
        }
    }
}
