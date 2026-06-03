// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Tests [BlazeCommandDispatcher].  */
@RunWith(JUnit4::class)
class BlazeCommandDispatcherTest {
    private val scratch: Scratch = Scratch()
    private var runtime: BlazeRuntime? = null
    private val outErr: RecordingOutErr = RecordingOutErr()
    private val foo: FooCommand = com.google.devtools.build.lib.runtime.BlazeCommandDispatcherTest.FooCommand()
    private val bar: BarCommand = com.google.devtools.build.lib.runtime.BlazeCommandDispatcherTest.BarCommand()
    private var clientEnv: MutableMap<String?, String?>? = null
    private var errorOnAfterCommand: AbruptExitException? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun initializeRuntime() {
        initializeRuntimeInternal()
    }

    @org.junit.After
    fun cleanUp() {
        BugReport.maybePropagateLastCrashIfInTest()
    }

    @Throws(java.lang.Exception::class)
    private fun initializeRuntimeInternal(vararg additionalModules: BlazeModule?) {
        val productName: String = TestConstants.PRODUCT_NAME
        val serverDirectories: ServerDirectories =
            ServerDirectories(
                scratch.dir("install_base"), scratch.dir("output_base"), scratch.dir("user_root")
            )
        // no ConfiguredTargetFactory is needed for testing command dispatch
        val builder: BlazeRuntime.Builder =
            Builder()
                .setFileSystem(scratch.getFileSystem())
                .setServerDirectories(serverDirectories)
                .setProductName(productName)
                .setStartupOptionsProvider(
                    OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
                )
                .addBlazeModule(
                    object : BlazeModule() {
                        public override fun beforeCommand(env: CommandEnvironment) {
                            clientEnv = env.getClientEnv()
                        }

                        @Throws(AbruptExitException::class)
                        public override fun afterCommand() {
                            if (errorOnAfterCommand != null) {
                                throw errorOnAfterCommand
                            }
                        }
                    })
        for (module in additionalModules) {
            builder.addBlazeModule(module)
        }
        this.runtime = builder.build()

        val directories: BlazeDirectories =
            BlazeDirectories(
                serverDirectories,
                scratch.dir("scratch"),
                productName
            )
        runtime.initWorkspace(directories,  /*binTools=*/null)
        errorOnAfterCommand = null
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun stopProfilers() {
        // Needs to be done because we are simulating crashes but keeping the jvm alive.
        com.google.devtools.build.lib.profiler.Profiler.instance().stop()
        MemoryProfiler.instance().stop()
    }

    /** Options for [FooCommand].  */
    @OptionsClass
    abstract class FooOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "success",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "true"
        )
        abstract val exitStatus: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "stdout",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = ""
        )
        abstract val stdout: String?

        @get:com.google.devtools.common.options.Option(
            name = "stderr",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = ""
        )
        abstract val stderr: String?
    }

    @Command(
        name = "foo",
        options = [com.google.devtools.build.lib.runtime.BlazeCommandDispatcherTest.FooOptions::class],
        shortDescription = "",
        help = ""
    )
    private open class FooCommand : BlazeCommand {
        public override fun exec(env: CommandEnvironment, options: OptionsParsingResult): BlazeCommandResult {
            val fooOptions: FooOptions? =
                options.getOptions<FooOptions?>(com.google.devtools.build.lib.runtime.BlazeCommandDispatcherTest.FooOptions::class.java)
            env.getReporter().getOutErr().printOut(fooOptions!!.stdout)
            env.getReporter().getOutErr().printErr(fooOptions.stderr)
            if (fooOptions.exitStatus) {
                return BlazeCommandResult.success()
            } else {
                return BlazeCommandResult.failureDetail(
                    FailureDetail.newBuilder()
                        .setSpawn(Spawn.newBuilder().setCode(Spawn.Code.NON_ZERO_EXIT))
                        .build()
                )
            }
        }
    }

    @Command(name = "bar", shortDescription = "", help = "")
    private class BarCommand : BlazeCommand {
        public override fun exec(env: CommandEnvironment, options: OptionsParsingResult?): BlazeCommandResult {
            env.getReporter().getOutErr().printOut("Hello, bar.\n")
            return BlazeCommandResult.success()
        }
    }

    private abstract class AnsiTestingCommand : BlazeCommand {
        public override fun exec(env: CommandEnvironment, options: OptionsParsingResult?): BlazeCommandResult {
            val outErr: OutErr = env.getReporter().getOutErr()
            try {
                env.getReporter().switchToAnsiAllowingHandler()
                val ansiBytes: ByteArray? = ANSI_CODE.toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
                env.getReporter().handle(
                    com.google.devtools.build.lib.events.Event.of(
                        com.google.devtools.build.lib.events.EventKind.STDOUT,
                        null,
                        ansiBytes
                    )
                )
                outErr.getOutputStream().flush()
                outErr.getErrorStream().flush()
            } catch (e: IOException) {
                return BlazeCommandResult.failureDetail(
                    FailureDetail.newBuilder().setCrash(Crash.getDefaultInstance()).build()
                )
            }

            return BlazeCommandResult.success()
        }

        companion object {
            const val ANSI_CODE: String = "\u001B[34mFoo"
        }
    }

    @Command(name = "binary", binaryStdOut = true, shortDescription = "", help = "")
    private class BinaryCommand : AnsiTestingCommand()

    @Command(name = "ascii", shortDescription = "", help = "")
    private class AsciiCommand : AnsiTestingCommand()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutErrorAndExitStatus() {
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(foo))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
        val args = arrayOf<String?>(
            "foo", "--stdout=Hello, out.",
            "--stderr=Hello, err.", "--success=false"
        )
        val result: BlazeCommandResult = dispatch.exec(java.util.Arrays.< T > asList < T ? > (args), "test", outErr)
        assertThat(outErr.outAsLatin1()).endsWith("Hello, out.")
        assertThat(outErr.errAsLatin1()).endsWith("Hello, err.")
        assertThat(result.getExitCode()).isEqualTo(ExitCode.BUILD_FAILURE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecReportsHardCrashStatus() {
        val crashCommand =
            CommandCompleteRecordingCommand(
                java.util.function.Supplier {
                    throw java.lang.OutOfMemoryError("oom message")
                })
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(crashCommand))
        val dispatch: BlazeCommandDispatcher =
            BlazeCommandDispatcher(runtime, BugReporter.defaultInstance())

        val directResult: BlazeCommandResult =
            dispatch.exec(com.google.common.collect.ImmutableList.of<E?>("testcommand"), "clientdesc", outErr)
        // Crashes from main thread don't interrupt main thread.
        Truth.assertThat(java.lang.Thread.currentThread().isInterrupted()).isFalse()

        val commandCompleteEvent: CommandCompleteEvent? =
            crashCommand.commandCompleteEvent.get(
                com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
        val exitCode: DetailedExitCode = commandCompleteEvent.getExitCode()
        assertThat(exitCode.getExitCode()).isEqualTo(ExitCode.OOM_ERROR)
        assertThat(exitCode.getFailureDetail()).isNotNull()
        val crash: Crash = exitCode.getFailureDetail().getCrash()
        assertThat(crash.getCode()).isEqualTo(FailureDetails.Crash.Code.CRASH_OOM)
        assertThat(crash.getCausesCount()).isEqualTo(1)
        assertThat(crash.getCauses(0).getMessage()).isEqualTo("oom message")
        com.google.common.truth.Subject.contains("BlazeCommandDispatcherTest.java")
        assertThat(directResult.getExitCode()).isEqualTo(ExitCode.OOM_ERROR)
        assertThat(directResult.shutdown()).isTrue()
        Truth.assertThat(BugReport.andResetLastCrashingThrowableIfInTest)
            .isInstanceOf(java.lang.OutOfMemoryError::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun crashPreventsNewCommand() {
        val commandStarted: CountDownLatch = CountDownLatch(1)
        val hangingCommand: BlazeCommand =
            CommandCompleteRecordingCommand(
                java.util.function.Supplier {
                    commandStarted.countDown()
                    try {
                        java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                    } catch (e: java.lang.InterruptedException) {
                        return@Supplier BlazeCommandResult.detailedExitCode(
                            DetailedExitCode.of(
                                FailureDetail.newBuilder()
                                    .setInterrupted(
                                        FailureDetails.Interrupted.newBuilder()
                                            .setCode(FailureDetails.Interrupted.Code.INTERRUPTED)
                                    )
                                    .build()
                            )
                        )
                    }
                    throw java.lang.IllegalStateException("Should have been interrupted")
                })

        val crashStarted: CountDownLatch = CountDownLatch(1)
        val waitToFinishOnCrash: CountDownLatch = CountDownLatch(1)
        initializeRuntimeInternal(
            object : BlazeModule() {
                public override fun blazeShutdownOnCrash(exitCode: DetailedExitCode?) {
                    crashStarted.countDown()
                    com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly(waitToFinishOnCrash)
                }
            })
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(hangingCommand))
        val dispatch: BlazeCommandDispatcher =
            BlazeCommandDispatcher(runtime, BugReporter.defaultInstance())

        val directResult: AtomicReference<BlazeCommandResult?> = AtomicReference<BlazeCommandResult?>()
        val commandThread: TestThread =
            TestThread(
                TestRunnable {
                    directResult.set(
                        dispatch.exec(
                            com.google.common.collect.ImmutableList.of<E?>("testcommand"),
                            "clientdesc",
                            outErr
                        )
                    )
                })
        commandThread.start()

        Truth.assertThat(
            commandStarted.await(
                com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
        ).isTrue()

        val crashExitCode: DetailedExitCode? =
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage("crash oom message")
                    .setCrash(Crash.newBuilder().setCode(Crash.Code.CRASH_OOM))
                    .build()
            )
        val crashThread: TestThread = TestThread(TestRunnable { runtime.cleanUpForCrash(crashExitCode) })
        crashThread.start()

        Truth.assertThat(
            crashStarted.await(
                com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
        ).isTrue()
        commandThread.joinAndAssertState(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
        assertThat(directResult.get().getDetailedExitCode()).isSameInstanceAs(crashExitCode)

        val recordingOutErr: RecordingOutErr = RecordingOutErr()
        val errorMessage = TestConstants.PRODUCT_NAME + " is crashing: crash oom message"

        assertThat(
            dispatch
                .exec(com.google.common.collect.ImmutableList.of<E?>("testcommand"), "clientdesc", recordingOutErr)
                .getDetailedExitCode()
                .getFailureDetail()
        )
            .isEqualTo(
                FailureDetails.FailureDetail.newBuilder()
                    .setCommand(
                        FailureDetails.Command.newBuilder()
                            .setCode(FailureDetails.Command.Code.PREVIOUSLY_SHUTDOWN)
                    )
                    .setMessage(errorMessage)
                    .build()
            )
        com.google.common.truth.Subject.contains(errorMessage)

        Truth.assertThat(crashThread.isAlive()).isTrue()

        waitToFinishOnCrash.countDown()

        crashThread.joinAndAssertState(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecReportsStatus() {
        val failureDetail: FailureDetail? =
            FailureDetail.newBuilder()
                .setSpawn(Spawn.newBuilder().setCode(Spawn.Code.NON_ZERO_EXIT))
                .build()
        val crashCommand =
            CommandCompleteRecordingCommand(java.util.function.Supplier { BlazeCommandResult.failureDetail(failureDetail) })
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(crashCommand))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)

        val directResult: BlazeCommandResult =
            dispatch.exec(com.google.common.collect.ImmutableList.of<E?>("testcommand"), "clientdesc", outErr)

        val commandCompleteEvent: CommandCompleteEvent? =
            crashCommand.commandCompleteEvent.get(
                com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
        assertThat(commandCompleteEvent.getExitCode()).isEqualTo(DetailedExitCode.of(failureDetail))
        assertThat(directResult.shutdown()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClientEnv() {
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(foo))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
        val args = arrayOf<String?>("foo", "--client_env=V1=val1", "--client_env=V2=", "--client_env=V3=val3")
        dispatch.exec(java.util.Arrays.< T > asList < T ? > (args), "test", outErr)
        Truth.assertThat(clientEnv).containsExactly("V1", "val1", "V2", "", "V3", "val3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClientEnvEmpty() {
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(foo))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
        val args = arrayOf<String?>("foo")
        dispatch.exec(java.util.Arrays.< T > asList < T ? > (args), "test", outErr)
        Truth.assertThat(clientEnv).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAfterCommandCanModifyExitStatus() {
        val detailedExitCode: DetailedExitCode? =
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage("afterCommandError")
                    .setBuildProgress(
                        BuildProgress.newBuilder().setCode(Code.BES_UPLOAD_LOCAL_FILE_ERROR)
                    )
                    .build()
            )
        errorOnAfterCommand = AbruptExitException(detailedExitCode)
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(foo))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
        val result: BlazeCommandResult =
            dispatch.exec(mutableListOf<T?>("foo", "--success=true"), "test", outErr)
        assertThat(result.getExitCode()).isEqualTo(ExitCode.TRANSIENT_BUILD_EVENT_SERVICE_UPLOAD_ERROR)
        assertThat(result.getDetailedExitCode()).isEqualTo(detailedExitCode)
        com.google.common.truth.Subject.contains("afterCommandError")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleCommands() {
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(foo, bar))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)

        dispatch.exec(mutableListOf<T?>("foo", "--stdout=Hello, foo."), "test", outErr)
        assertThat(outErr.outAsLatin1()).isEqualTo("Hello, foo.")
        outErr.reset()
        dispatch.exec(< T > asList < T ? > ("bar"), "test", outErr)
        assertThat(outErr.outAsLatin1()).isEqualTo("Hello, bar.\n")
    }

    @Command(name = "block", help = "", shortDescription = "")
    private class BlockCommand : BlazeCommand {
        private val waitLatch: CountDownLatch = CountDownLatch(1)
        private val started: CountDownLatch = CountDownLatch(1)

        fun unblock() {
            waitLatch.countDown()
        }

        @Throws(java.lang.InterruptedException::class)
        fun awaitRunning() {
            started.await()
        }

        public override fun exec(env: CommandEnvironment?, options: OptionsParsingResult?): BlazeCommandResult {
            started.countDown()
            try {
                waitLatch.await()
            } catch (e: java.lang.InterruptedException) {
                java.lang.Thread.currentThread().interrupt()
                throw java.lang.IllegalStateException("Should not have been interrupted")
            }
            return BlazeCommandResult.success()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConcurrentCommandsWaitForLock() {
        val blockCommand = BlockCommand()
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(bar, blockCommand))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime,  /*serverPid=*/42)

        val blockCommandThread: java.lang.Thread =
            TestThread(
                TestRunnable {
                    dispatch.exec(
                        com.google.common.collect.ImmutableList.of<E?>("block"),
                        "blocking client",
                        RecordingOutErr()
                    )
                })
        val blockedCommandThread: TestThread =
            TestThread(
                TestRunnable {
                    dispatch.exec(
                        InvocationPolicy.getDefaultInstance(),
                        com.google.common.collect.ImmutableList.of<E?>("bar"),
                        outErr,
                        LockingMode.WAIT,
                        UiVerbosity.NORMAL,
                        "test client",
                        runtime.getClock().currentTimeMillis(),  /* startupOptionsTaggedWithBazelRc= */
                        java.util.Optional.empty<T?>(),  /* idleTaskResultsSupplier= */
                        { com.google.common.collect.ImmutableList.of<E?>() },  /* commandExtensions= */
                        com.google.common.collect.ImmutableList.of<E?>(),  /* commandExtensionReporter= */
                        { ext -> })
                })

        try {
            blockCommandThread.start()
            blockCommand.awaitRunning()
            blockedCommandThread.start()

            while (!outErr.errAsLatin1().contains("Another command")) {
                java.lang.Thread.sleep(100)
            }
            com.google.common.truth.Subject.contains(
                "Another command (blocking client) is running. Waiting for it to complete on the"
                        + " server (server_pid=42)..."
            )
        } finally {
            blockCommand.unblock()
            // We don't care what happened on the threads, don't assert state to make sure we join both.
            blockCommandThread.join()
            blockedCommandThread.join()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDetectsInvalidCommandLineOptions() {
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(foo))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
        val result: BlazeCommandResult = dispatch.exec(mutableListOf<T?>("foo", "--invalid"), "test", outErr)
        assertThat(result.getExitCode()).isEqualTo(ExitCode.COMMAND_LINE_ERROR)
        com.google.common.truth.Subject.contains("Unrecognized option: --invalid\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReportsCommandNotFound() {
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(foo))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
        val result: BlazeCommandResult = dispatch.exec(< T > asList < T ? > ("baz"), "test", outErr)
        assertThat(result.getExitCode()).isEqualTo(ExitCode.COMMAND_LINE_ERROR)
        assertThat(outErr.errAsLatin1())
            .matches("Command 'baz' not found. Try '(blaze|bazel) help'.\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProvidesHelpWhenNoCommandSpecified() {
        @Command(name = "help", shortDescription = "", help = "")
        class HelpCommand : BlazeCommand {
            public override fun exec(env: CommandEnvironment, options: OptionsParsingResult?): BlazeCommandResult {
                env.getReporter().getOutErr().printOutLn("This is the help message.")
                return BlazeCommandResult.success()
            }
        }
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(HelpCommand()))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
        val result: BlazeCommandResult = dispatch.exec(com.google.common.collect.ImmutableList.of<E?>(), "test", outErr)
        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS)
        assertThat(outErr.outAsLatin1()).isEqualTo("This is the help message.\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOptionsDefaults() {
        val blazercOpts: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "--rc_source=/home/jrluser/.blazerc",
                "--default_override=0:foo=--stdout",
                "--default_override=0:foo=stdout",
                "--default_override=0:foo=--stderr",
                "--default_override=0:foo=stderr",
                "--announce_rc"
            )

        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(foo))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)

        val cmdLine: MutableList<String?> = com.google.common.collect.Lists.newArrayList<String?>("foo")
        cmdLine.addAll(blazercOpts)
        var result: BlazeCommandResult = dispatch.exec(cmdLine, "test", outErr)
        assertThat(outErr.outAsLatin1()).isEqualTo("stdout")
        // TODO(bazel-team): Fix inconsistent line breaks that make the regex match necessary.
        assertThat(outErr.errAsLatin1())
            .containsMatch(
                ("INFO: Reading rc options for 'foo' from /home/jrluser/.blazerc:\\s+"
                        + "  'foo' options: --stdout stdout --stderr stderr\\s+"
                        + "stderr")
            )
        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS)

        // Explicit options override those from config file:
        result = dispatch.exec(mutableListOf<T?>("foo", "--success=false"), "test", outErr)
        assertThat(result.getExitCode()).isEqualTo(ExitCode.BUILD_FAILURE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIllegalOptions() {
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(foo))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
        val result: BlazeCommandResult = dispatch.exec(
            mutableListOf<T?>("foo", "--not_a_valid_option"), "test", outErr
        )
        assertThat(result.getExitCode()).isEqualTo(ExitCode.COMMAND_LINE_ERROR)
    }

    @Command(
        name = "wiz",
        inheritsOptionsFrom = [com.google.devtools.build.lib.runtime.BlazeCommandDispatcherTest.FooCommand::class],
        shortDescription = "",
        help = ""
    )
    private class WizCommand : FooCommand()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInheritanceOfOptionDefaults() {
        // "foo" options in ~/.blazerc should apply to "wiz" too...
        val blazercOpts: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "--rc_source=/home/jrluser/.blazerc",
                "--default_override=0:foo=--stdout",
                "--default_override=0:foo=stdout",
                "--default_override=0:foo=--stderr",
                "--default_override=0:foo=stderr",
                "--announce_rc"
            )
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(foo, WizCommand()))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)

        val cmdLine: MutableList<String?> = com.google.common.collect.Lists.newArrayList<String?>("wiz")
        cmdLine.addAll(blazercOpts)
        dispatch.exec(cmdLine, "test", outErr)
        assertThat(outErr.outAsLatin1()).isEqualTo("stdout")
        // TODO(bazel-team): Fix inconsistent line breaks that make the regex match necessary.
        assertThat(outErr.errAsLatin1())
            .containsMatch(
                ("INFO: Reading rc options for 'wiz' from /home/jrluser/.blazerc:\\s+"
                        + "  Inherited 'foo' options: --stdout stdout --stderr stderr\\s+"
                        + "stderr")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBinaryCommandOutput() {
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(BinaryCommand()))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)

        val ansiEscapedString = AnsiTestingCommand.Companion.ANSI_CODE

        // Binary commands do not remove ANSI control codes.
        val result: BlazeCommandResult = dispatch.exec(
            mutableListOf<T?>("binary", "--color=no"), "test", outErr
        )
        val out: String? = outErr.outAsLatin1()
        val err: String? = outErr.errAsLatin1()

        MoreAsserts.assertExitCode(
            ExitCode.SUCCESS.numericExitCode,
            result.getExitCode().getNumericExitCode(), out, err
        )
        Truth.assertThat(out).contains(ansiEscapedString)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAsciiCommandOutput() {
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(AsciiCommand()))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)

        val ansiEscapedString = AnsiTestingCommand.Companion.ANSI_CODE

        // ASCII commands remove ANSI control codes.
        val result: BlazeCommandResult = dispatch.exec(
            mutableListOf<T?>("ascii", "--color=no"),
            "test", outErr
        )
        val out: String? = outErr.outAsLatin1()
        val err: String? = outErr.errAsLatin1()

        MoreAsserts.assertExitCode(
            ExitCode.SUCCESS.numericExitCode,
            result.getExitCode().getNumericExitCode(), out, err
        )
        Truth.assertThat(out).doesNotContain(ansiEscapedString)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWaitingForTimestampGranularityMonitor() {
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(foo))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)

        for (i in 0..2) {
            val result: BlazeCommandResult =
                dispatch.exec(java.util.Arrays.< T > asList < T ? > ("foo"), "test", outErr)
            assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS)
        }

        assertThat(outErr.outAsLatin1()).isEmpty()
        for (line in outErr.errAsLatin1().split("\n")) {
            Truth.assertThat(line)
                .containsMatch(
                    "^|Blaze waited .* to avoid potential file system timestamp granularity issues"
                )
        }
    }

    /**
     * Regression test for b/136003907.
     * 
     * 
     * Tests that even if [System.out] or [System.err] are read and retained during the
     * lifetime of a command (which we cannot prevent, since they are public), there is no memory leak
     * of [CommandEnvironment.getReporter].
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noMemoryLeakOfReporterThroughSystemOutErr() {
        @Command(name = "retain_out_err", shortDescription = "", help = "")
        class SystemOutErrRetainingCommand : BlazeCommand {
            private val defaultStdout: PrintStream? = java.lang.System.out
            private val defaultStderr: PrintStream? = java.lang.System.err
            private var reporterRef: java.lang.ref.WeakReference<com.google.devtools.build.lib.events.Reporter?>? = null

            public override fun exec(env: CommandEnvironment, options: OptionsParsingResult?): BlazeCommandResult {
                val overriddenStdout: PrintStream? = java.lang.System.out
                Truth.assertThat(overriddenStdout).isNotNull()
                Truth.assertThat(overriddenStdout).isNotEqualTo(defaultStdout)

                val overriddenStderr: PrintStream? = java.lang.System.err
                Truth.assertThat(overriddenStderr).isNotNull()
                Truth.assertThat(overriddenStderr).isNotEqualTo(defaultStderr)

                val reporter: com.google.devtools.build.lib.events.Reporter? = env.getReporter()
                Truth.assertThat(reporter).isNotNull()
                reporterRef = java.lang.ref.WeakReference<T?>(env.getReporter())

                return BlazeCommandResult.success()
            }
        }

        val cmd = SystemOutErrRetainingCommand()
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(cmd))
        val dispatcher: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)

        dispatcher.exec(com.google.common.collect.ImmutableList.of<E?>("retain_out_err"), "test", outErr)

        GcFinalization.awaitClear(cmd.reporterRef)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun useHighestPriorityExitCode() {
        val arbitraryError1: DetailedExitCode? =
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage("This error message should be overwritten.")
                    .setCommand(
                        FailureDetails.Command.newBuilder()
                            .setCode(FailureDetails.Command.Code.COMMAND_NOT_FOUND)
                    )
                    .build()
            )
        val infrastructureFailure: DetailedExitCode? =
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage("This is an infrastructure failure so this error should take priority.")
                    .setCommand(
                        FailureDetails.Command.newBuilder()
                            .setCode(
                                FailureDetails.Command.Code
                                    .STARLARK_CPU_PROFILE_FILE_INITIALIZATION_FAILURE
                            )
                    )
                    .build()
            )
        val arbitraryError2: DetailedExitCode? =
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage("This error message should be overrwritten.")
                    .setCommand(
                        FailureDetails.Command.newBuilder()
                            .setCode(FailureDetails.Command.Code.INVOCATION_POLICY_INVALID)
                    )
                    .build()
            )
        initializeRuntimeInternal(
            object : BlazeModule() {
                @Throws(AbruptExitException::class)
                public override fun beforeCommand(env: CommandEnvironment?) {
                    throw AbruptExitException(arbitraryError1)
                }
            },
            object : BlazeModule() {
                @Throws(AbruptExitException::class)
                public override fun beforeCommand(env: CommandEnvironment?) {
                    throw AbruptExitException(infrastructureFailure)
                }
            },
            object : BlazeModule() {
                @Throws(AbruptExitException::class)
                public override fun beforeCommand(env: CommandEnvironment?) {
                    throw AbruptExitException(arbitraryError2)
                }
            })
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(foo))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
        val result: BlazeCommandResult =
            dispatch.exec(mutableListOf<T?>("foo", "--config=UNDEFINED_CONFIG_VALUE"), "test", outErr)
        assertThat(result.getExitCode()).isEqualTo(ExitCode.LOCAL_ENVIRONMENTAL_ERROR)
        assertThat(result.getExitCode().isInfrastructureFailure()).isTrue()
        assertThat(result.getDetailedExitCode()).isEqualTo(infrastructureFailure)
        com.google.common.truth.Subject.contains("This is an infrastructure failure so this error should take priority.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun useFirstExitCodeIfAllHaveEquivalentPriority() {
        // The options parsing failure should be encountered first.
        val optionsParseFailure: DetailedExitCode? =
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(
                        "Error parsing options: Config value \'UNDEFINED_CONFIG_VALUE\' is not defined"
                                + " in any .rc file"
                    )
                    .setCommand(
                        FailureDetails.Command.newBuilder()
                            .setCode(FailureDetails.Command.Code.OPTIONS_PARSE_FAILURE)
                    )
                    .build()
            )
        val arbitraryError1: DetailedExitCode? =
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage("This error message should be overrwritten.")
                    .setCommand(
                        FailureDetails.Command.newBuilder() // Arbitrarily chosen error code.
                            .setCode(FailureDetails.Command.Code.INVOCATION_POLICY_INVALID)
                    )
                    .build()
            )
        val arbitraryError2: DetailedExitCode? =
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage("This error message should be overwritten.")
                    .setCommand(
                        FailureDetails.Command.newBuilder() // Arbitrarily chosen error code.
                            .setCode(FailureDetails.Command.Code.COMMAND_NOT_FOUND)
                    )
                    .build()
            )
        initializeRuntimeInternal(
            object : BlazeModule() {
                @Throws(AbruptExitException::class)
                public override fun beforeCommand(env: CommandEnvironment?) {
                    throw AbruptExitException(arbitraryError1)
                }
            },
            object : BlazeModule() {
                @Throws(AbruptExitException::class)
                public override fun beforeCommand(env: CommandEnvironment?) {
                    throw AbruptExitException(arbitraryError2)
                }
            })
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(foo))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
        val result: BlazeCommandResult =
            dispatch.exec(mutableListOf<T?>("foo", "--config=UNDEFINED_CONFIG_VALUE"), "test", outErr)
        assertThat(result.getExitCode()).isEqualTo(ExitCode.COMMAND_LINE_ERROR)
        assertThat(result.getDetailedExitCode()).isEqualTo(optionsParseFailure)
        com.google.common.truth.Subject.contains("Config value 'UNDEFINED_CONFIG_VALUE' is not defined in any .rc file")
    }

    @Command(name = "testcommand", shortDescription = "", help = "")
    private class CommandCompleteRecordingCommand(resultSupplier: java.util.function.Supplier<BlazeCommandResult?>) :
        BlazeCommand {
        private val commandCompleteEvent: com.google.common.util.concurrent.SettableFuture<CommandCompleteEvent?> =
            com.google.common.util.concurrent.SettableFuture.create<CommandCompleteEvent?>()
        private val resultSupplier: java.util.function.Supplier<BlazeCommandResult?>

        init {
            this.resultSupplier = resultSupplier
        }

        @com.google.common.eventbus.Subscribe
        fun onCommandComplete(commandComplete: CommandCompleteEvent?) {
            commandCompleteEvent.set(commandComplete)
        }

        public override fun exec(env: CommandEnvironment, options: OptionsParsingResult?): BlazeCommandResult? {
            env.getEventBus().register(this)
            return resultSupplier.get()
        }
    }
}
