// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.bazel.BazelServices.BAZEL_SERVICES

/** Tests of CommandEnvironment's command-interrupting exit functionality.  */
@RunWith(JUnit4::class)
class CommandInterruptionTest {
    /** Options class to pass configuration to our dummy wait command.  */
    @OptionsClass
    abstract class WaitOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "expect_interruption",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val expectInterruption: Boolean
    }

    /**
     * Command which retrieves an exit code off the queue and returns it, or INTERRUPTED if
     * interrupted more than --expect_interruptions times while waiting.
     */
    @Command(name = "snooze", shortDescription = "", help = "", options = [WaitOptions::class])
    private class WaitForCompletionCommand(isTestShuttingDown: AtomicBoolean) : BlazeCommand {
        private val isTestShuttingDown: AtomicBoolean
        private val commandStateHandoff: AtomicReference<com.google.common.util.concurrent.SettableFuture<CommandState?>?>

        init {
            this.isTestShuttingDown = isTestShuttingDown
            this.commandStateHandoff =
                AtomicReference<com.google.common.util.concurrent.SettableFuture<CommandState?>?>()
        }

        public override fun exec(env: CommandEnvironment, options: OptionsParsingResult): BlazeCommandResult {
            val commandState =
                CommandState(
                    env,
                    options.getOptions<WaitOptions?>(WaitOptions::class.java).getExpectInterruption(),
                    isTestShuttingDown
                )
            commandStateHandoff.getAndSet(null).set(commandState)
            return BlazeCommandResult.detailedExitCode(commandState.waitForDetailedCodeFromTest())
        }

        /**
         * Runs an instance of this command on the given executor, waits for it to start and returns a
         * CommandState which can be used to control and assert on the status of that command.
         */
        @Throws(java.lang.InterruptedException::class, ExecutionException::class)
        fun runIn(
            executor: ExecutorService, dispatcher: BlazeCommandDispatcher, expectInterruption: Boolean
        ): CommandState {
            val newHandoff: com.google.common.util.concurrent.SettableFuture<CommandState> =
                com.google.common.util.concurrent.SettableFuture.create<CommandState?>()
            if (!commandStateHandoff.compareAndSet(null, newHandoff)) {
                throw java.lang.AssertionError("Another command is already starting at this time?!")
            }
            @Suppress("unused") val ignoredCommandResult:  // static analysis wants us to check future return values
                    java.util.concurrent.Future<*>? =
                executor.submit<DetailedExitCode?>(
                    RunCommandThroughDispatcher(dispatcher, newHandoff, expectInterruption)
                )
            return newHandoff.get()
        }
    }

    /** Callable to run the above command on a different thread.  */
    private class RunCommandThroughDispatcher(
        dispatcher: BlazeCommandDispatcher,
        commandStateHandoff: com.google.common.util.concurrent.SettableFuture<CommandState>,
        expectInterruption: Boolean
    ) : java.util.concurrent.Callable<DetailedExitCode?> {
        private val dispatcher: BlazeCommandDispatcher
        private val commandStateHandoff: com.google.common.util.concurrent.SettableFuture<CommandState>
        private val expectInterruption: Boolean

        init {
            this.dispatcher = dispatcher
            this.commandStateHandoff = commandStateHandoff
            this.expectInterruption = expectInterruption
        }

        @Throws(java.lang.Exception::class)
        override fun call(): DetailedExitCode? {
            val result: DetailedExitCode?
            try {
                result =
                    dispatcher
                        .exec(
                            com.google.common.collect.ImmutableList.of<E?>(
                                "snooze",
                                if (expectInterruption) "--expect_interruption" else "--noexpect_interruption"
                            ),
                            "CommandInterruptionTest",
                            OutErr.SYSTEM_OUT_ERR
                        )
                        .getDetailedExitCode()
            } catch (throwable: java.lang.Exception) {
                if (commandStateHandoff.isDone()) {
                    commandStateHandoff.get().completeWithFailure(throwable)
                } else {
                    commandStateHandoff.setException(
                        java.lang.IllegalStateException(
                            "The command failed with an exception before WaitForCompletionCommand started.",
                            throwable
                        )
                    )
                }
                throw throwable
            }

            if (commandStateHandoff.isDone()) {
                commandStateHandoff.get().completeWithDetailedCode(result)
            } else {
                commandStateHandoff.setException(
                    java.lang.IllegalStateException(
                        ("The command failed with exit code "
                                + result
                                + " before WaitForCompletionCommand started.")
                    )
                )
            }
            return result
        }
    }

    /** A remote control allowing the test to control and assert on the WaitForCompletionCommand.  */
    private class CommandState(
        commandEnvironment: CommandEnvironment,
        expectInterruption: Boolean,
        isTestShuttingDown: AtomicBoolean
    ) {
        private val result: com.google.common.util.concurrent.SettableFuture<DetailedExitCode?>
        private val commandEnvironment: CommandEnvironment
        private val thread: java.lang.Thread
        private val detailedCodeQueue: BlockingQueue<DetailedExitCode?>
        private val isTestShuttingDown: AtomicBoolean
        private var expectInterruption: Boolean
        private val barrier: CyclicBarrier

        init {
            this.result = com.google.common.util.concurrent.SettableFuture.create<DetailedExitCode?>()
            this.commandEnvironment = commandEnvironment
            this.thread = java.lang.Thread.currentThread()
            this.detailedCodeQueue = ArrayBlockingQueue<DetailedExitCode?>(1)
            this.isTestShuttingDown = isTestShuttingDown
            this.expectInterruption = expectInterruption
            this.barrier = CyclicBarrier(2)
        }

        // command side
        /**
         * Marks the Future associated with this CommandState completed with the given exit code, then
         * waits at the barrier for the test thread to catch up.
         */
        fun completeWithDetailedCode(detailedExitCode: DetailedExitCode?) {
            result.set(detailedExitCode)
            if (!isTestShuttingDown.get()) {
                // Wait at the barrier for the test to assert on status, unless the test is shutting down.
                try {
                    barrier.await()
                } catch (ex: java.lang.InterruptedException) {
                    // this is fine, we're only doing this for the test thread's benefit anyway
                } catch (ex: BrokenBarrierException) {
                }
            }
        }

        /**
         * Marks the Future associated with this CommandState as having failed with the given exit code,
         * then waits at the barrier for the test thread to catch up.
         */
        fun completeWithFailure(throwable: Throwable) {
            result.setException(throwable)
            if (!isTestShuttingDown.get()) {
                // Wait at the barrier for the test to assert on status, unless the test is shutting down.
                try {
                    barrier.await()
                } catch (ex: java.lang.InterruptedException) {
                    // this is fine, we're only doing this for the test thread's benefit anyway
                } catch (ex: BrokenBarrierException) {
                }
            }
        }

        /**
         * Waits for an exit code to come from the test, either INTERRUPTED via thread interruption, or
         * a test-specified exit code via requestExitWith(). If expectInterruption was set, a single
         * interruption will be ignored.
         */
        fun waitForDetailedCodeFromTest(): DetailedExitCode? {
            while (true) {
                var detailedCode: DetailedExitCode? = null
                try {
                    detailedCode = detailedCodeQueue.take()
                    if (java.lang.Thread.interrupted()) {
                        // the interruption and the exit code delivery may have come in simultaneously, which
                        // may result in a successful return from the queue with interrupted() set.
                        throw java.lang.InterruptedException()
                    }
                } catch (ex: java.lang.InterruptedException) {
                    if (!expectInterruption || isTestShuttingDown.get()) {
                        // This is not an expected interruption (possibly because the test is shutting down and
                        // it's the executor's please stop interruption) so give up.
                        return UNEXPECTED_INTERRUPTION
                    }
                    // Otherwise, that was an expected interruption, so return to looking for exit codes.
                    // But we only expect one, so the next one will be fatal.
                    expectInterruption = false
                    // We fall through the catch here in case we received an interruption and an exit code at
                    // the same time.
                }

                if (SENTINEL.equals(detailedCode)) {
                    // The test just wants us to go wait at the barrier for an assertion.
                    try {
                        barrier.await()
                    } catch (impossible: java.lang.InterruptedException) {
                        // This should not happen in normal use, but if it does, exit gracefully so
                        // BlazeCommandDispatcher has a chance to clean up. Use the SENTINEL value to avoid
                        // accidentally passing any tests that might have been looking for INTERRUPTED.
                        return SENTINEL
                    } catch (impossible: BrokenBarrierException) {
                        return SENTINEL
                    }
                } else if (detailedCode != null) {
                    return detailedCode
                }
            }
        }

        // test side
        val moduleEnvironment: BlazeModule.ModuleEnvironment
            /** Gets the ModuleEnvironment modules will see when executing this command.  */
            get() = commandEnvironment.getBlazeModuleEnvironment()

        /** Sends an exit code to the command, which will then return with it if it is still running.  */
        fun requestExitWith(detailedExitCode: DetailedExitCode?) {
            detailedCodeQueue.offer(detailedExitCode)
        }

        /** Sends an interrupt directly to the command's thread.  */
        fun interrupt() {
            thread.interrupt()
        }

        /** Waits for the command to reach a stopping point to check if it has finished or not.  */
        @Throws(java.lang.InterruptedException::class, BrokenBarrierException::class)
        fun synchronizeWithCommand() {
            // If the future is already done, no need to wait at the barrier - we already know the state.
            if (result.isDone()) {
                // But if the command thread is waiting on the barrier, tell it to stop doing so.
                barrier.reset()
                return
            }
            // Offer the sentinel to the queue - if the command is still waiting and it sees the sentinel,
            // it will go to the barrier.
            detailedCodeQueue.offer(SENTINEL)
            // Then wait for the command to finish processing.
            barrier.await()
        }

        /** Asserts that the command finished and returned the given ExitCode.  */
        @Throws(java.lang.InterruptedException::class, ExecutionException::class, BrokenBarrierException::class)
        fun assertFinishedWith(detailedExitCode: DetailedExitCode?) {
            synchronizeWithCommand()
            Truth.assertWithMessage("The command should have been finished, but it was not.")
                .that(result.isDone())
                .isTrue()
            assertThat(com.google.common.util.concurrent.Futures.getDone<DetailedExitCode?>(result)).isEqualTo(
                detailedExitCode
            )
        }

        /** Asserts that the command has not finished yet.  */
        @Throws(java.lang.InterruptedException::class, BrokenBarrierException::class)
        fun assertNotFinishedYet() {
            synchronizeWithCommand()
            if (result.isDone()) {
                try {
                    throw java.lang.AssertionError(
                        "The command should not have been finished, but it finished with exit code "
                                + result.get()
                    )
                } catch (ex: Throwable) {
                    throw java.lang.AssertionError("The command should not have been finished, but it threw", ex)
                }
            }
        }

        /** Asserts that both commands were executed on the same thread.  */
        fun assertOnSameThreadAs(other: CommandState) {
            Truth.assertThat(thread).isSameInstanceAs(other.thread)
        }

        companion object {
            private val SENTINEL: DetailedExitCode = DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage("GO TO THE BARRIER")
                    .setCrash(Crash.newBuilder().setCode(Crash.Code.CRASH_UNKNOWN))
                    .build()
            )
        }
    }

    private var executor: ExecutorService? = null
    private var isTestShuttingDown: AtomicBoolean? = null
    private var dispatcher: BlazeCommandDispatcher? = null
    private var snooze: WaitForCompletionCommand? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        executor = Executors.newSingleThreadExecutor()
        val startupOptionsProvider: OptionsParsingResult? =
            OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
        for (service in BAZEL_SERVICES) {
            service.globalInit(startupOptionsProvider, BAZEL_SERVICES)
        }
        val scratch: Scratch = Scratch()
        isTestShuttingDown = AtomicBoolean(false)
        val productName: String = TestConstants.PRODUCT_NAME
        val serverDirectories: ServerDirectories =
            ServerDirectories(
                scratch.dir("install"), scratch.dir("output"), scratch.dir("user_root")
            )
        val runtime: BlazeRuntime =
            Builder()
                .setFileSystem(scratch.getFileSystem())
                .setProductName(productName)
                .setServerDirectories(serverDirectories)
                .setStartupOptionsProvider(startupOptionsProvider)
                .addBlazeModule(
                    object : BlazeModule() {
                        public override fun initializeRuleClasses(builder: ConfiguredRuleClassProvider.Builder) {
                            // Can't create a Starlark environment without a tools repository!
                            builder.setToolsRepository(TestConstants.TOOLS_REPOSITORY)
                            // Can't create a defaults package without the base options in there!
                            builder.addConfigurationOptions(CoreOptions::class.java)
                            builder.addConfigurationOptions(TestConfiguration.TestOptions::class.java)
                        }
                    })
                .build()
        snooze = WaitForCompletionCommand(isTestShuttingDown)
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(snooze))
        dispatcher = BlazeCommandDispatcher(runtime)
        val blazeDirectories: BlazeDirectories =
            BlazeDirectories(
                serverDirectories,
                scratch.dir("workspace"),
                productName
            )
        runtime.initWorkspace(blazeDirectories,  /* binTools= */null)
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun tearDown() {
        isTestShuttingDown.set(true)
        executor.shutdownNow()
        executor.awaitTermination(
            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS,
            TimeUnit.MILLISECONDS
        )
    }

    // These tests are basically testing the functionality of the dummy command.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sendingExitCodeToTestCommandResultsInExitWithThatStatus() {
        val command = snooze!!.runIn(executor, dispatcher,  /* expectInterruption= */false)
        command.requestExitWith(DetailedExitCode.success())
        command.assertFinishedWith(DetailedExitCode.success())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun interruptingTestCommandMakesItExitWithInterruptedStatus() {
        val command = snooze!!.runIn(executor, dispatcher,  /* expectInterruption= */false)
        command.interrupt()
        command.assertFinishedWith(UNEXPECTED_INTERRUPTION)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun commandIgnoresFirstInterruptionWhenExpectingInterruption() {
        val command = snooze!!.runIn(executor, dispatcher,  /* expectInterruption= */true)
        command.interrupt()
        command.assertNotFinishedYet()
        command.requestExitWith(DetailedExitCode.success())
        command.assertFinishedWith(DetailedExitCode.success())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun commandExitsWithInterruptedAfterInterruptionCountExceeded() {
        val command = snooze!!.runIn(executor, dispatcher,  /* expectInterruption= */true)
        command.interrupt()
        command.assertNotFinishedYet()
        command.interrupt()
        command.assertFinishedWith(UNEXPECTED_INTERRUPTION)
    }

    // These tests get into the meat of actual abrupt exits.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exitForbidsNullException() {
        val command = snooze!!.runIn(executor, dispatcher,  /* expectInterruption= */false)
        try {
            command.moduleEnvironment.exit(null)
            throw java.lang.AssertionError("It shouldn't be allowed to pass null to exit()!")
        } catch (expected: java.lang.NullPointerException) {
            // Good!
        }
        command.assertNotFinishedYet()
        command.requestExitWith(DetailedExitCode.success())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun callingExitOnceInterruptsAndOverridesExitCode() {
        val command = snooze!!.runIn(executor, dispatcher,  /* expectInterruption= */false)
        command.moduleEnvironment.exit(AbruptExitException(NO_TEST_TARGETS_CODE))
        command.assertFinishedWith(NO_TEST_TARGETS_CODE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun callingExitSecondTimeNeitherInterruptsNorReOverridesExitCode() {
        val command = snooze!!.runIn(executor, dispatcher,  /* expectInterruption= */true)
        command.moduleEnvironment.exit(AbruptExitException(NO_TEST_TARGETS_CODE))
        command.assertNotFinishedYet()
        command.moduleEnvironment.exit(AbruptExitException(OPTIONS_FAILURE))
        command.assertNotFinishedYet()
        command.requestExitWith(DetailedExitCode.success())
        command.assertFinishedWith(NO_TEST_TARGETS_CODE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun abruptExitCodesDontOverrideInfrastructureFailures() {
        val command = snooze!!.runIn(executor, dispatcher,  /* expectInterruption= */true)
        command.moduleEnvironment.exit(AbruptExitException(NO_TEST_TARGETS_CODE))
        command.assertNotFinishedYet()
        command.requestExitWith(CRASH)
        command.assertFinishedWith(CRASH)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun callingExitAfterCommandCompletesDoesNothing() {
        val firstCommand = snooze!!.runIn(executor, dispatcher,  /* expectInterruption= */false)
        firstCommand.requestExitWith(DetailedExitCode.success())
        firstCommand.assertFinishedWith(DetailedExitCode.success())
        val newCommandOnSameThread =
            snooze!!.runIn(executor, dispatcher,  /* expectInterruption= */false)
        firstCommand.assertOnSameThreadAs(newCommandOnSameThread)
        firstCommand.moduleEnvironment.exit(AbruptExitException(OPTIONS_FAILURE))
        newCommandOnSameThread.assertNotFinishedYet()
        newCommandOnSameThread.requestExitWith(DetailedExitCode.success())
    }

    companion object {
        private val NO_TEST_TARGETS_CODE: DetailedExitCode? = DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setTestCommand(TestCommand.newBuilder().setCode(Code.NO_TEST_TARGETS))
                .build()
        )
        private val UNEXPECTED_INTERRUPTION: DetailedExitCode? = DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage("unexpected interruption")
                .setInterrupted(
                    Interrupted.newBuilder().setCode(Interrupted.Code.INTERRUPTED_UNKNOWN)
                )
                .build()
        )
        private val CRASH: DetailedExitCode? = DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage("crash")
                .setCrash(Crash.newBuilder().setCode(Crash.Code.CRASH_UNKNOWN))
                .build()
        )
        private val OPTIONS_FAILURE: DetailedExitCode? = DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setCommand(
                    FailureDetails.Command.newBuilder()
                        .setCode(FailureDetails.Command.Code.OPTIONS_PARSE_FAILURE)
                )
                .build()
        )
    }
}
