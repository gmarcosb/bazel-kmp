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
package com.google.devtools.build.lib.exec.local

import com.google.devtools.build.lib.testing.common.DirectoryListingHelper.file

/** Unit tests for [LocalSpawnRunner].  */
@RunWith(JUnit4::class)
class LocalSpawnRunnerTest {
    private class TestedLocalSpawnRunner(
        execRoot: Path?,
        localExecutionOptions: LocalExecutionOptions?,
        resourceManager: ResourceManager?,
        processWrapper: ProcessWrapper?,
        localEnvProvider: LocalEnvProvider?
    ) : LocalSpawnRunner(
        execRoot,
        localExecutionOptions,
        resourceManager,
        localEnvProvider,  /* binTools= */
        null,
        processWrapper,
        Mockito.< T > mock < T ? > (RunfilesTreeUpdater::class.java)
    ) {
        private var tmpDirPath: Path? = null

        // Rigged to act on supplied filesystem (e.g. InMemoryFileSystem) for testing purposes
        // TODO(b/70572634): Update FileSystem abstraction to support createTempDirectory() from
        // the java.nio.file.Files package.
        @Throws(IOException::class)
        protected override fun createActionTemp(execRoot: Path): Path {
            var tempDirPath: Path
            do {
                val idStr =
                    (java.lang.Long.toHexString(java.lang.Thread.currentThread().getId())
                            + "_"
                            + java.lang.Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong()))
                tempDirPath = execRoot.getRelative("tmp" + idStr)
            } while (tempDirPath.exists())
            if (!tempDirPath.createDirectory()) {
                throw IOException(String.format("Could not create temp directory '%s'", tempDirPath))
            }
            this.tmpDirPath = tempDirPath
            return tempDirPath
        }

        val actionTemp: Path?
            get() = tmpDirPath
    }

    private open class FinishedSubprocess(private val exitCode: Int) : Subprocess {
        public override fun destroy(): Boolean {
            return false
        }

        public override fun exitValue(): Int {
            return exitCode
        }

        public override fun finished(): Boolean {
            return true
        }

        val isAlive: Boolean
            get() = false

        public override fun timedout(): Boolean {
            return false
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun waitFor() {
            // Do nothing.
        }

        val outputStream: java.io.OutputStream
            get() = com.google.common.io.ByteStreams.nullOutputStream()

        val inputStream: java.io.InputStream
            get() = ByteArrayInputStream(ByteArray(0))

        val errorStream: java.io.InputStream
            get() = ByteArrayInputStream(ByteArray(0))

        public override fun close() {
            // Do nothing.
        }

        val processId: Long
            get() = 0
    }

    private class SubprocessInterceptor : SubprocessFactory {
        public override fun create(params: SubprocessBuilder?): Subprocess? {
            throw java.lang.UnsupportedOperationException()
        }
    }

    private val resourceManager: ResourceManager = ResourceManager()

    @Before
    fun suppressLogging() {
        java.util.logging.Logger.getLogger(TestedLocalSpawnRunner::class.java.getName())
            .setFilter(java.util.logging.Filter { record: LogRecord? -> false })
    }

    private fun setupEnvironmentForFakeExecution(): FileSystem {
        // Prevent any subprocess execution at all.
        SubprocessBuilder.setDefaultSubprocessFactory(SubprocessInterceptor())
        resourceManager.setAvailableResources(
            ResourceSet.create( /* memoryMb= */1,  /* cpu= */1,  /* localTestCount= */1)
        )
        return InMemoryFileSystem(DigestHashFunction.SHA256)
    }

    /**
     * Enables real execution by default.
     * 
     * 
     * Tests should call setupEnvironmentForFakeExecution() if they do not want real execution.
     */
    @Before
    fun setupEnvironmentForRealExecution() {
        SubprocessBuilder.setDefaultSubprocessFactory(JavaSubprocessFactory.INSTANCE)
        resourceManager.setAvailableResources(LocalHostCapacity.getLocalHostCapacity())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun vanillaZeroExit() {
        // TODO(#3536): Make this test work on Windows.
        // The Command API implicitly absolutizes the path, and we get weird paths on Windows:
        // T:\execroot\execroot\_bin\process-wrapper
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)

        val fs: FileSystem = setupEnvironmentForFakeExecution()

        val factory: SubprocessFactory = Mockito.mock<SubprocessFactory>(SubprocessFactory::class.java)
        val captor: ArgumentCaptor<SubprocessBuilder?> =
            ArgumentCaptor.forClass<SubprocessBuilder?, SubprocessBuilder?>(SubprocessBuilder::class.java)
        Mockito.`when`<T?>(factory.create(captor.capture())).thenReturn(FinishedSubprocess(0))
        SubprocessBuilder.setDefaultSubprocessFactory(factory)

        val options: LocalExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(LocalExecutionOptions::class.java)
        options.localSigkillGraceSeconds = 456
        val testedRunner =
            TestedLocalSpawnRunner(
                fs.getPath("/execroot"),
                options,
                resourceManager,
                makeProcessWrapper(options),
                LocalEnvProvider { env: MutableMap<String?, String?>, binTools: BinTools?, fallbackTmpDir: String? ->
                    keepLocalEnvUnchanged(
                        env,
                        binTools,
                        fallbackTmpDir
                    )
                })
        val runner: LocalSpawnRunner = testedRunner

        val fileOutErr: FileOutErr = FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"))
        val context: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(SIMPLE_SPAWN, fileOutErr, java.time.Duration.ofSeconds(123))
        assertThat(fs.getPath("/execroot").createDirectory()).isTrue()
        val result: SpawnResult = runner.exec(SIMPLE_SPAWN, context)
        Mockito.verify<Any?>(factory).create(ArgumentMatchers.any<T?>(SubprocessBuilder::class.java))
        assertThat(result.status()).isEqualTo(SpawnResult.Status.SUCCESS)
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(result.setupSuccess()).isTrue()
        assertThat(result.getExecutorHostName()).isEqualTo(NetUtil.getCachedShortHostName())

        assertThat(captor.getValue().getArgv())
            .containsExactlyElementsIn(
                com.google.common.collect.ImmutableList.of<E?>(
                    "/process-wrapper",
                    "--timeout=123",
                    "--kill_delay=456",
                    "--stats=" + testedRunner.actionTemp.getRelative("stats.out"),
                    "/bin/echo",
                    "Hi!"
                )
            )
        assertThat(captor.getValue().getEnv()).containsExactly("VARIABLE", "value")
        assertThat(captor.getValue().getTimeoutMillis()).isEqualTo(0)
        assertThat(captor.getValue().getStdout()).isEqualTo(StreamAction.REDIRECT)
        assertThat(captor.getValue().getStdoutFile()).isEqualTo(java.io.File("/out/stdout"))
        assertThat(captor.getValue().getStderr()).isEqualTo(StreamAction.REDIRECT)
        assertThat(captor.getValue().getStderrFile()).isEqualTo(java.io.File("/out/stderr"))

        Truth.assertThat(context.lockOutputFilesCalled).isTrue()
        Truth.assertThat(context.reportedStatus)
            .containsExactly(SpawnSchedulingEvent.create("local"), SpawnExecutingEvent.create("local"))
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParamFiles() {
        // TODO(#3536): Make this test work on Windows.
        // The Command API implicitly absolutizes the path, and we get weird paths on Windows:
        // T:\execroot\execroot\_bin\process-wrapper
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)

        val fs: FileSystem = setupEnvironmentForFakeExecution()

        val factory: SubprocessFactory = Mockito.mock<SubprocessFactory>(SubprocessFactory::class.java)
        Mockito.`when`<T?>(factory.create(ArgumentMatchers.any<T?>())).thenReturn(FinishedSubprocess(0))
        SubprocessBuilder.setDefaultSubprocessFactory(factory)

        val options: LocalExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(LocalExecutionOptions::class.java)
        options.localSigkillGraceSeconds = 456
        val execRoot: Path = fs.getPath("/execroot")
        val runner: LocalSpawnRunner =
            TestedLocalSpawnRunner(
                execRoot,
                options,
                resourceManager,
                makeProcessWrapper(options),
                LocalEnvProvider { env: MutableMap<String?, String?>, binTools: BinTools?, fallbackTmpDir: String? ->
                    keepLocalEnvUnchanged(
                        env,
                        binTools,
                        fallbackTmpDir
                    )
                })
        val paramFileActionInput: ParamFileActionInput =
            ParamFileActionInput(
                PathFragment.create("some/dir/params"),
                com.google.common.collect.ImmutableList.of<E?>("--foo", "--bar"),
                ParameterFileType.UNQUOTED
            )
        val spawn: Spawn =
            SpawnBuilder("/bin/echo", "Hi!")
                .withInput(paramFileActionInput)
                .withEnvironment("VARIABLE", "value")
                .build()
        val fileOutErr: FileOutErr = FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"))
        val context: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(spawn, fileOutErr, java.time.Duration.ofSeconds(123))
        assertThat(fs.getPath("/execroot").createDirectory()).isTrue()
        val result: SpawnResult = runner.exec(spawn, context)
        assertThat(result.status()).isEqualTo(SpawnResult.Status.SUCCESS)
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(result.setupSuccess()).isTrue()
        assertThat(result.getExecutorHostName()).isEqualTo(NetUtil.getCachedShortHostName())
        val paramFile: Path = execRoot.getRelative("some/dir/params")
        assertThat(paramFile.exists()).isTrue()
        paramFile.getInputStream().use { inputStream ->
            Truth.assertThat<String?>(
                String(
                    com.google.common.io.ByteStreams.toByteArray(inputStream),
                    java.nio.charset.StandardCharsets.UTF_8
                ).split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            )
                .asList()
                .containsExactly("--foo", "--bar")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_materializesVirtualInputAsExecutable() {
        val fs: FileSystem = setupEnvironmentForFakeExecution()
        val factory: SubprocessFactory = Mockito.mock<SubprocessFactory>(SubprocessFactory::class.java)
        Mockito.`when`<T?>(factory.create(ArgumentMatchers.any<T?>())).thenReturn(FinishedSubprocess(0))
        SubprocessBuilder.setDefaultSubprocessFactory(factory)
        val execRoot: Path = fs.getPath("/execroot")
        val options: LocalExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(LocalExecutionOptions::class.java)
        val runner: LocalSpawnRunner =
            TestedLocalSpawnRunner(
                execRoot,
                options,
                resourceManager,
                makeProcessWrapper(options),
                LocalEnvProvider { env: MutableMap<String?, String?>, binTools: BinTools?, fallbackTmpDir: String? ->
                    keepLocalEnvUnchanged(
                        env,
                        binTools,
                        fallbackTmpDir
                    )
                })
        val virtualInput: VirtualActionInput = ActionsTestUtil.createVirtualActionInput("input1", "hello")
        val spawn: Spawn = SpawnBuilder("/bin/true").withInput(virtualInput).build()
        val fileOutErr: FileOutErr = FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"))
        val context: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(spawn, fileOutErr, java.time.Duration.ZERO)

        val result: SpawnResult = runner.exec(spawn, context)

        assertThat(result.status()).isEqualTo(Status.SUCCESS)
        assertThat(DirectoryListingHelper.leafDirectoryEntries(execRoot))
            .containsExactly(file("input1"))
        val inputPath: Path = execRoot.getRelative(virtualInput.getExecPath())
        assertThat(inputPath.isExecutable()).isTrue()
        assertThat(FileSystemUtils.readLinesAsLatin1(inputPath)).containsExactly("hello")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noProcessWrapper() {
        // TODO(#3536): Make this test work on Windows.
        // The Command API implicitly absolutizes the path, and we get weird paths on Windows:
        // T:\execroot\bin\echo
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)

        val fs: FileSystem = setupEnvironmentForFakeExecution()

        val factory: SubprocessFactory = Mockito.mock<SubprocessFactory>(SubprocessFactory::class.java)
        val captor: ArgumentCaptor<SubprocessBuilder?> =
            ArgumentCaptor.forClass<SubprocessBuilder?, SubprocessBuilder?>(SubprocessBuilder::class.java)
        Mockito.`when`<T?>(factory.create(captor.capture())).thenReturn(FinishedSubprocess(0))
        SubprocessBuilder.setDefaultSubprocessFactory(factory)

        val options: LocalExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(LocalExecutionOptions::class.java)
        options.localSigkillGraceSeconds = 456
        val runner: LocalSpawnRunner =
            TestedLocalSpawnRunner(
                fs.getPath("/execroot"),
                options,
                resourceManager,  /* processWrapper= */
                null,
                LocalEnvProvider { env: MutableMap<String?, String?>, binTools: BinTools?, fallbackTmpDir: String? ->
                    keepLocalEnvUnchanged(
                        env,
                        binTools,
                        fallbackTmpDir
                    )
                })

        val fileOutErr: FileOutErr = FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"))
        val context: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(SIMPLE_SPAWN, fileOutErr, java.time.Duration.ofSeconds(123))
        assertThat(fs.getPath("/execroot").createDirectory()).isTrue()
        val result: SpawnResult = runner.exec(SIMPLE_SPAWN, context)
        Mockito.verify<Any?>(factory).create(ArgumentMatchers.any<T?>())
        assertThat(result.status()).isEqualTo(SpawnResult.Status.SUCCESS)
        assertThat(result.exitCode()).isEqualTo(0)
        assertThat(result.setupSuccess()).isTrue()
        assertThat(result.getExecutorHostName()).isEqualTo(NetUtil.getCachedShortHostName())

        assertThat(captor.getValue().getArgv())
            .containsExactlyElementsIn(com.google.common.collect.ImmutableList.of<E?>("/bin/echo", "Hi!"))
        assertThat(captor.getValue().getEnv()).containsExactly("VARIABLE", "value")
        // Without the process wrapper, we use the Command API to enforce the timeout.
        assertThat(captor.getValue().getTimeoutMillis()).isEqualTo(123 * 1000L)

        Truth.assertThat(context.lockOutputFilesCalled).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonZeroExit() {
        // TODO(#3536): Make this test work on Windows.
        // The Command API implicitly absolutizes the path, and we get weird paths on Windows:
        // T:\execroot\execroot\_bin\process-wrapper
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)

        val fs: FileSystem = setupEnvironmentForFakeExecution()

        val factory: SubprocessFactory = Mockito.mock<SubprocessFactory>(SubprocessFactory::class.java)
        val captor: ArgumentCaptor<SubprocessBuilder?> =
            ArgumentCaptor.forClass<SubprocessBuilder?, SubprocessBuilder?>(SubprocessBuilder::class.java)
        Mockito.`when`<T?>(factory.create(captor.capture())).thenReturn(FinishedSubprocess(3))
        SubprocessBuilder.setDefaultSubprocessFactory(factory)

        val options: LocalExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(LocalExecutionOptions::class.java)
        val testedRunner =
            TestedLocalSpawnRunner(
                fs.getPath("/execroot"),
                options,
                resourceManager,
                makeProcessWrapper(options),
                LocalEnvProvider { env: MutableMap<String?, String?>, binTools: BinTools?, fallbackTmpDir: String? ->
                    keepLocalEnvUnchanged(
                        env,
                        binTools,
                        fallbackTmpDir
                    )
                })
        val runner: LocalSpawnRunner = testedRunner

        assertThat(fs.getPath("/execroot").createDirectory()).isTrue()
        val fileOutErr: FileOutErr = FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"))
        val context: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(SIMPLE_SPAWN, fileOutErr, java.time.Duration.ZERO)
        val result: SpawnResult = runner.exec(SIMPLE_SPAWN, context)
        Mockito.verify<Any?>(factory).create(ArgumentMatchers.any<T?>(SubprocessBuilder::class.java))
        assertThat(result.status()).isEqualTo(SpawnResult.Status.NON_ZERO_EXIT)
        assertThat(result.exitCode()).isEqualTo(3)
        assertThat(result.setupSuccess()).isTrue()
        assertThat(result.getExecutorHostName()).isEqualTo(NetUtil.getCachedShortHostName())

        assertThat(captor.getValue().getArgv())
            .containsExactlyElementsIn(
                com.google.common.collect.ImmutableList.of<E?>(
                    "/process-wrapper",
                    "--timeout=0",
                    "--kill_delay=15",
                    "--stats=" + testedRunner.actionTemp.getRelative("stats.out"),
                    "/bin/echo",
                    "Hi!"
                )
            )
        assertThat(captor.getValue().getEnv()).containsExactly("VARIABLE", "value")
        assertThat(captor.getValue().getStdout()).isEqualTo(StreamAction.REDIRECT)
        assertThat(captor.getValue().getStdoutFile()).isEqualTo(java.io.File("/out/stdout"))
        assertThat(captor.getValue().getStderr()).isEqualTo(StreamAction.REDIRECT)
        assertThat(captor.getValue().getStderrFile()).isEqualTo(java.io.File("/out/stderr"))

        Truth.assertThat(context.lockOutputFilesCalled).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun processStartupThrows() {
        val fs: FileSystem = setupEnvironmentForFakeExecution()

        val factory: SubprocessFactory = Mockito.mock<SubprocessFactory>(SubprocessFactory::class.java)
        val captor: ArgumentCaptor<SubprocessBuilder?> =
            ArgumentCaptor.forClass<SubprocessBuilder?, SubprocessBuilder?>(SubprocessBuilder::class.java)
        Mockito.`when`<T?>(factory.create(captor.capture())).thenThrow(IOException("I'm sorry, Dave"))
        SubprocessBuilder.setDefaultSubprocessFactory(factory)

        val options: LocalExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(LocalExecutionOptions::class.java)
        val runner: LocalSpawnRunner =
            TestedLocalSpawnRunner(
                fs.getPath("/execroot"),
                options,
                resourceManager,
                makeProcessWrapper(options),
                LocalEnvProvider { env: MutableMap<String?, String?>, binTools: BinTools?, fallbackTmpDir: String? ->
                    keepLocalEnvUnchanged(
                        env,
                        binTools,
                        fallbackTmpDir
                    )
                })

        assertThat(fs.getPath("/out").createDirectory()).isTrue()
        assertThat(fs.getPath("/execroot").createDirectory()).isTrue()
        val fileOutErr: FileOutErr = FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"))
        val context: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(SIMPLE_SPAWN, fileOutErr, java.time.Duration.ZERO)
        val result: SpawnResult = runner.exec(SIMPLE_SPAWN, context)
        Mockito.verify<Any?>(factory).create(ArgumentMatchers.any<T?>(SubprocessBuilder::class.java))
        assertThat(result.status()).isEqualTo(SpawnResult.Status.EXECUTION_FAILED)
        assertThat(result.exitCode()).isEqualTo(-1)
        assertThat(result.setupSuccess()).isFalse()
        assertThat(result.getWallTimeInMs()).isEqualTo(0)
        assertThat(result.getUserTimeInMs()).isEqualTo(0)
        assertThat(result.getSystemTimeInMs()).isEqualTo(0)
        assertThat(result.getExecutorHostName()).isEqualTo(NetUtil.getCachedShortHostName())

        assertThat(FileSystemUtils.readContent(fs.getPath("/out/stderr"), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("Action failed to execute: java.io.IOException: I'm sorry, Dave\n")

        Truth.assertThat(context.lockOutputFilesCalled).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun disallowLocalExecution() {
        val fs: FileSystem = setupEnvironmentForFakeExecution()

        val options: LocalExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(LocalExecutionOptions::class.java)
        options.setAllowedLocalAction(RegexPatternConverter().convert("none"))
        val runner: LocalSpawnRunner =
            TestedLocalSpawnRunner(
                fs.getPath("/execroot"),
                options,
                resourceManager,
                makeProcessWrapper(options),
                LocalEnvProvider { env: MutableMap<String?, String?>, binTools: BinTools?, fallbackTmpDir: String? ->
                    keepLocalEnvUnchanged(
                        env,
                        binTools,
                        fallbackTmpDir
                    )
                })

        assertThat(fs.getPath("/execroot").createDirectory()).isTrue()
        val fileOutErr: FileOutErr = FileOutErr()
        val context: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(SIMPLE_SPAWN, fileOutErr, java.time.Duration.ZERO)
        val reply: SpawnResult = runner.exec(SIMPLE_SPAWN, context)
        assertThat(reply.status()).isEqualTo(SpawnResult.Status.EXECUTION_DENIED)
        assertThat(reply.exitCode()).isEqualTo(-1)
        assertThat(reply.setupSuccess()).isFalse()
        assertThat(reply.getWallTimeInMs()).isEqualTo(0)
        assertThat(reply.getUserTimeInMs()).isEqualTo(0)
        assertThat(reply.getSystemTimeInMs()).isEqualTo(0)
        assertThat(reply.getExecutorHostName()).isEqualTo(NetUtil.getCachedShortHostName())

        // TODO(ulfjack): Maybe we should only lock after checking?
        Truth.assertThat(context.lockOutputFilesCalled).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun interruptedException() {
        val fs: FileSystem = setupEnvironmentForFakeExecution()

        val factory: SubprocessFactory = Mockito.mock<SubprocessFactory>(SubprocessFactory::class.java)
        val captor: ArgumentCaptor<SubprocessBuilder?> =
            ArgumentCaptor.forClass<SubprocessBuilder?, SubprocessBuilder?>(SubprocessBuilder::class.java)
        Mockito.`when`<T?>(factory.create(captor.capture()))
            .thenReturn(
                object : FinishedSubprocess(3) {
                    private var destroyed = false

                    override fun destroy(): Boolean {
                        destroyed = true
                        return true
                    }

                    @Throws(java.lang.InterruptedException::class)
                    override fun waitFor() {
                        if (!destroyed) {
                            throw java.lang.InterruptedException()
                        }
                    }
                })
        SubprocessBuilder.setDefaultSubprocessFactory(factory)

        val options: LocalExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(LocalExecutionOptions::class.java)
        val runner: LocalSpawnRunner =
            TestedLocalSpawnRunner(
                fs.getPath("/execroot"),
                options,
                resourceManager,
                makeProcessWrapper(options),
                LocalEnvProvider { env: MutableMap<String?, String?>, binTools: BinTools?, fallbackTmpDir: String? ->
                    keepLocalEnvUnchanged(
                        env,
                        binTools,
                        fallbackTmpDir
                    )
                })

        val fileOutErr: FileOutErr = FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"))
        val context: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(SIMPLE_SPAWN, fileOutErr, java.time.Duration.ZERO)
        assertThat(fs.getPath("/execroot").createDirectory()).isTrue()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable {
                runner.exec(
                    SIMPLE_SPAWN, context
                )
            })
        java.lang.Thread.interrupted()
        Truth.assertThat(context.lockOutputFilesCalled).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun interruptWaitsForProcessExit() {
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)

        val tempDir: Path =
            com.google.devtools.build.lib.testutil.TestUtils.createUniqueTmpDir(JavaIoFileSystem(DigestHashFunction.SHA256))

        val runner: LocalSpawnRunner =
            LocalSpawnRunner(
                tempDir,
                com.google.devtools.common.options.Options.getDefaults<O?>(LocalExecutionOptions::class.java),
                resourceManager,
                LocalEnvProvider.forCurrentOs(com.google.common.collect.ImmutableMap.of<K?, V?>()),  /* binTools= */
                null,  /* processWrapper= */
                null,
                Mockito.< T > mock < T ? > (RunfilesTreeUpdater::class.java)
            )
        val fileOutErr: FileOutErr =
            FileOutErr(tempDir.getRelative("stdout"), tempDir.getRelative("stderr"))

        // This test to exercise a race condition by attempting an operation multiple times. We can get
        // false positives (the test passing without us catching a problem), so try a few times. When
        // implementing this fix on 2019-09-11, this specific configuration was sufficient to catch the
        // previously-existent bug.
        val tries = 10
        val delaySeconds = 1

        val content: Path = tempDir.getChild("content")
        val started: Path = tempDir.getChild("started")
        // Start a subprocess that blocks until it is killed, and when it is, writes some output to
        // a temporary file after some delay.
        val script =
            ("trap 'sleep "
                    + delaySeconds
                    + "; echo foo >"
                    + content.getPathString()
                    + "; exit 1' TERM; "
                    + "touch "
                    + started.getPathString()
                    + "; "
                    + "while :; do "
                    + "  echo 'waiting to be killed'; "
                    + "  sleep 1; "
                    + "done")
        val spawn: Spawn = SpawnBuilder("/bin/sh", "-c", script).build()

        val context: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(spawn, fileOutErr, java.time.Duration.ZERO)

        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        try {
            for (i in 0..<tries) {
                content.delete()
                started.delete()
                val interruptCaught: Semaphore = Semaphore(0)
                val future: java.util.concurrent.Future<*> =
                    executor.submit(
                        java.lang.Runnable {
                            try {
                                runner.exec(spawn, context)
                            } catch (e: java.lang.InterruptedException) {
                                interruptCaught.release()
                            } catch (t: Throwable) {
                                throw java.lang.IllegalStateException(t)
                            }
                        })
                // Wait until we know the subprocess has started so that delivering a termination signal
                // to it triggers the delayed write to the file.
                while (!started.exists()) {
                    java.lang.Thread.sleep(1)
                }
                future.cancel(true)
                interruptCaught.acquireUninterruptibly()
                // At this point, the subprocess must have fully stopped so write some content to the file
                // and expect that these contents remain unmodified.
                FileSystemUtils.writeContent(content, java.nio.charset.StandardCharsets.UTF_8, "bar")
                // Wait for longer than the spawn takes to exit before we check the file contents to ensure
                // that we properly awaited for termination of the subprocess.
                java.lang.Thread.sleep((delaySeconds * 2 * 1000).toLong())
                assertThat(
                    FileSystemUtils.readContent(
                        content,
                        java.nio.charset.StandardCharsets.UTF_8
                    )
                ).isEqualTo("bar")
            }
        } finally {
            executor.shutdown()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkPrefetchCalled() {
        val fs: FileSystem = setupEnvironmentForFakeExecution()

        val factory: SubprocessFactory = Mockito.mock<SubprocessFactory>(SubprocessFactory::class.java)
        Mockito.`when`<T?>(factory.create(ArgumentMatchers.any<T?>())).thenReturn(FinishedSubprocess(0))
        SubprocessBuilder.setDefaultSubprocessFactory(factory)

        val options: LocalExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(LocalExecutionOptions::class.java)
        val runner: LocalSpawnRunner =
            TestedLocalSpawnRunner(
                fs.getPath("/execroot"),
                options,
                resourceManager,
                makeProcessWrapper(options),
                LocalEnvProvider { env: MutableMap<String?, String?>, binTools: BinTools?, fallbackTmpDir: String? ->
                    keepLocalEnvUnchanged(
                        env,
                        binTools,
                        fallbackTmpDir
                    )
                })

        val fileOutErr: FileOutErr = FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"))
        val context: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(SIMPLE_SPAWN, fileOutErr, java.time.Duration.ofSeconds(123))
        assertThat(fs.getPath("/execroot").createDirectory()).isTrue()
        runner.exec(SIMPLE_SPAWN, context)
        Truth.assertThat(context.prefetchCalled).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkNoPrefetchCalled() {
        val fs: FileSystem = setupEnvironmentForFakeExecution()

        val factory: SubprocessFactory = Mockito.mock<SubprocessFactory>(SubprocessFactory::class.java)
        Mockito.`when`<T?>(factory.create(ArgumentMatchers.any<T?>())).thenReturn(FinishedSubprocess(0))
        SubprocessBuilder.setDefaultSubprocessFactory(factory)

        val options: LocalExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(LocalExecutionOptions::class.java)
        val runner: LocalSpawnRunner =
            TestedLocalSpawnRunner(
                fs.getPath("/execroot"),
                options,
                resourceManager,
                makeProcessWrapper(options),
                LocalEnvProvider { env: MutableMap<String?, String?>, binTools: BinTools?, fallbackTmpDir: String? ->
                    keepLocalEnvUnchanged(
                        env,
                        binTools,
                        fallbackTmpDir
                    )
                })

        val fileOutErr: FileOutErr = FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"))

        val spawn: Spawn =
            SpawnBuilder("/bin/echo", "Hi!")
                .withExecutionInfo(ExecutionRequirements.DISABLE_LOCAL_PREFETCH, "")
                .build()

        val context: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(spawn, fileOutErr, java.time.Duration.ofSeconds(123))

        assertThat(fs.getPath("/execroot").createDirectory()).isTrue()
        runner.exec(spawn, context)
        Truth.assertThat(context.prefetchCalled).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkLocalEnvProviderCalled() {
        val fs: FileSystem = setupEnvironmentForFakeExecution()

        val factory: SubprocessFactory = Mockito.mock<SubprocessFactory>(SubprocessFactory::class.java)
        Mockito.`when`<T?>(factory.create(ArgumentMatchers.any<T?>())).thenReturn(FinishedSubprocess(0))
        SubprocessBuilder.setDefaultSubprocessFactory(factory)
        val localEnvProvider: LocalEnvProvider? = Mockito.mock<LocalEnvProvider?>(LocalEnvProvider::class.java)

        val options: LocalExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(LocalExecutionOptions::class.java)
        val runner: LocalSpawnRunner =
            TestedLocalSpawnRunner(
                fs.getPath("/execroot"),
                options,
                resourceManager,
                makeProcessWrapper(options),
                localEnvProvider
            )

        val fileOutErr: FileOutErr = FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"))
        val context: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(SIMPLE_SPAWN, fileOutErr, java.time.Duration.ofSeconds(123))
        assertThat(fs.getPath("/execroot").createDirectory()).isTrue()

        runner.exec(SIMPLE_SPAWN, context)
        Mockito.verify<Any?>(localEnvProvider)
            .rewriteLocalEnv(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.matches("^/execroot/tmp[0-9a-fA-F]+_[0-9a-fA-F]+/work$")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hasExecutionStatistics() {
        // TODO(b/62588075) Currently no process-wrapper or execution statistics support in Windows.
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)

        val fs: FileSystem =
            UnixFileSystem(
                DigestHashFunction.SHA256,  /* hashAttributeName= */
                "",
                NativePosixFilesServiceImpl()
            )

        val options: LocalExecutionOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(LocalExecutionOptions::class.java)

        val minimumWallTimeToSpendInMs = 10 * 1000

        val minimumUserTimeToSpendInMs = minimumWallTimeToSpendInMs
        // Under normal loads we should be able to use a much lower bound for maxUserTime, but be
        // generous here in case of hardware issues.
        val maximumUserTimeToSpendInMs = minimumUserTimeToSpendInMs + 20 * 1000

        val minimumSystemTimeToSpendInMs = 0
        // Under normal loads we should be able to use a much lower bound for maxSysTime, but be
        // generous here in case of hardware issues.
        val maximumSystemTimeToSpendInMs = minimumSystemTimeToSpendInMs + 20 * 1000

        val execRoot: Path = getTemporaryExecRoot(fs)
        val embeddedBinaries: Path = getTemporaryEmbeddedBin(fs)
        val binTools: BinTools =
            BinTools.forEmbeddedBin(embeddedBinaries, com.google.common.collect.ImmutableList.of<E?>("process-wrapper"))
        val processWrapperPath: Path = binTools.getEmbeddedPath("process-wrapper")
        copyProcessWrapperIntoExecRoot(processWrapperPath)
        val cpuTimeSpenderPath: Path = copyCpuTimeSpenderIntoExecRoot(execRoot)

        val runner: LocalSpawnRunner =
            LocalSpawnRunner(
                execRoot,
                options,
                resourceManager,
                { env: MutableMap<String?, String?>, binTools: BinTools?, fallbackTmpDir: String? ->
                    keepLocalEnvUnchanged(
                        env,
                        binTools,
                        fallbackTmpDir
                    )
                },
                binTools,
                ProcessWrapper(
                    processWrapperPath.asFragment(),
                    ActionInputHelper.fromPath(processWrapperPath.asFragment()),  /* killDelay= */
                    java.time.Duration.ZERO,  /* gracefulSigterm= */
                    false
                ),
                Mockito.< T > mock < T ? > (RunfilesTreeUpdater::class.java)
            )

        val spawn: Spawn =
            SpawnBuilder(
                cpuTimeSpenderPath.getPathString(),
                (minimumUserTimeToSpendInMs / 1000L).toString(),
                (minimumSystemTimeToSpendInMs / 1000L).toString()
            )
                .build()

        val fileOutErr: FileOutErr = FileOutErr(fs.getPath("/dev/null"), fs.getPath("/dev/null"))
        val context: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(spawn, fileOutErr, java.time.Duration.ZERO)

        val spawnResult: SpawnResult = runner.exec(spawn, context)

        assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.SUCCESS)
        assertThat(spawnResult.exitCode()).isEqualTo(0)
        assertThat(spawnResult.setupSuccess()).isTrue()
        assertThat(spawnResult.getExecutorHostName()).isEqualTo(NetUtil.getCachedShortHostName())

        assertThat(spawnResult.getWallTimeInMs()).isAtLeast(minimumWallTimeToSpendInMs)
        // Under heavy starvation, max wall time could be anything, so don't check it here.
        assertThat(spawnResult.getUserTimeInMs()).isAtLeast(minimumUserTimeToSpendInMs)
        assertThat(spawnResult.getUserTimeInMs()).isAtMost(maximumUserTimeToSpendInMs)
        assertThat(spawnResult.getSystemTimeInMs()).isAtLeast(minimumSystemTimeToSpendInMs)
        assertThat(spawnResult.getSystemTimeInMs()).isAtMost(maximumSystemTimeToSpendInMs)
        assertThat(spawnResult.getNumBlockOutputOperations()).isAtLeast(0L)
        assertThat(spawnResult.getNumBlockInputOperations()).isAtLeast(0L)
        assertThat(spawnResult.getNumInvoluntaryContextSwitches()).isAtLeast(0L)
    }

    // Check that relative paths in the Spawn are absolutized relative to the execroot passed to the
    // LocalSpawnRunner.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun relativePath() {
        // TODO(#3536): Make this test work on Windows.
        // The Command API implicitly absolutizes the path, and we get weird paths on Windows:
        // T:\execroot\execroot\_bin\process-wrapper
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)

        val fs: FileSystem = setupEnvironmentForFakeExecution()

        val factory: SubprocessFactory = Mockito.mock<SubprocessFactory>(SubprocessFactory::class.java)
        val captor: ArgumentCaptor<SubprocessBuilder?> =
            ArgumentCaptor.forClass<SubprocessBuilder?, SubprocessBuilder?>(SubprocessBuilder::class.java)
        Mockito.`when`<T?>(factory.create(captor.capture())).thenReturn(FinishedSubprocess(0))
        SubprocessBuilder.setDefaultSubprocessFactory(factory)

        val runner: LocalSpawnRunner =
            TestedLocalSpawnRunner(
                fs.getPath("/execroot"),
                com.google.devtools.common.options.Options.getDefaults<O?>(LocalExecutionOptions::class.java),
                resourceManager,  /* processWrapper= */
                null,
                LocalEnvProvider { env: MutableMap<String?, String?>, binTools: BinTools?, fallbackTmpDir: String? ->
                    keepLocalEnvUnchanged(
                        env,
                        binTools,
                        fallbackTmpDir
                    )
                })

        val fileOutErr: FileOutErr = FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"))
        val spawn: Spawn = SpawnBuilder("foo/bar", "Hi!").build()
        val context: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(spawn, fileOutErr, java.time.Duration.ofSeconds(123))
        assertThat(fs.getPath("/execroot").createDirectory()).isTrue()
        runner.exec(spawn, context)
        Mockito.verify<Any?>(factory).create(ArgumentMatchers.any<T?>(SubprocessBuilder::class.java))

        assertThat(captor.getValue().getArgv()).containsExactly("/execroot/foo/bar", "Hi!")
    }

    companion object {
        private val SIMPLE_SPAWN: Spawn = SpawnBuilder("/bin/echo", "Hi!").withEnvironment("VARIABLE", "value").build()

        private fun keepLocalEnvUnchanged(
            env: MutableMap<String?, String?>, binTools: BinTools?, fallbackTmpDir: String?
        ): com.google.common.collect.ImmutableMap<String?, String?> {
            return com.google.common.collect.ImmutableMap.copyOf<String?, String?>(env)
        }

        private fun makeProcessWrapper(options: LocalExecutionOptions): ProcessWrapper {
            return ProcessWrapper(
                PathFragment.create("/process-wrapper"),
                ActionInputHelper.fromPath("/process-wrapper"),
                options.getLocalSigkillGraceSecondsDuration(),  /* gracefulSigterm= */
                false
            )
        }

        /**
         * Copies the `process-wrapper` tool into the path under the temporary execRoot where the
         * [LocalSpawnRunner] expects to find it.
         */
        @Throws(IOException::class)
        private fun copyProcessWrapperIntoExecRoot(wrapperPath: Path) {
            val realProcessWrapperFile: java.io.File =
                java.io.File(
                    PathFragment.create(BlazeTestUtils.runfilesDir())
                        .getRelative(TestConstants.PROCESS_WRAPPER_PATH)
                        .getPathString()
                )
            Truth.assertThat(realProcessWrapperFile.exists()).isTrue()

            wrapperPath.createDirectoryAndParents()
            val wrapperFile: java.io.File = wrapperPath.getPathFile()

            wrapperPath.delete()
            com.google.common.io.Files.copy(realProcessWrapperFile, wrapperFile)
            assertThat(wrapperPath.exists()).isTrue()

            wrapperPath.setExecutable(true)
        }

        /**
         * Copies the `spend_cpu_time` test util into the temporary execRoot so that the [ ] can execute it.
         */
        @Throws(IOException::class)
        private fun copyCpuTimeSpenderIntoExecRoot(execRoot: Path): Path {
            val realCpuTimeSpenderFile: java.io.File =
                java.io.File(
                    PathFragment.create(BlazeTestUtils.runfilesDir())
                        .getRelative(TestConstants.CPU_TIME_SPENDER_PATH)
                        .getPathString()
                )
            Truth.assertThat(realCpuTimeSpenderFile.exists()).isTrue()

            val execRootCpuTimeSpenderPath: Path = execRoot.getRelative("spend-cpu-time")
            val execRootCpuTimeSpenderFile: java.io.File = execRootCpuTimeSpenderPath.getPathFile()

            assertThat(execRootCpuTimeSpenderPath.exists()).isFalse()
            com.google.common.io.Files.copy(realCpuTimeSpenderFile, execRootCpuTimeSpenderFile)
            assertThat(execRootCpuTimeSpenderPath.exists()).isTrue()

            execRootCpuTimeSpenderPath.setExecutable(true)

            return execRootCpuTimeSpenderPath
        }

        @Throws(IOException::class)
        private fun getTemporaryRoot(fs: FileSystem?, name: String?): Path {
            val tempDirPath: Path = com.google.devtools.build.lib.testutil.TestUtils.createUniqueTmpDir(fs)
            assertThat(tempDirPath.exists()).isTrue()

            val root: Path = tempDirPath.getRelative(name)
            assertThat(root.createDirectory()).isTrue()
            assertThat(root.exists()).isTrue()

            return root
        }

        /**
         * Returns an execRoot [Path] inside a new temporary directory.
         * 
         * 
         * The temporary directory will be automatically deleted on exit.
         */
        @Throws(IOException::class)
        private fun getTemporaryExecRoot(fs: FileSystem?): Path {
            return getTemporaryRoot(fs, "execRoot")
        }

        @Throws(IOException::class)
        private fun getTemporaryEmbeddedBin(fs: FileSystem?): Path {
            return getTemporaryRoot(fs, "embedded_bin")
        }
    }
}
