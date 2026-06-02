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
package com.google.devtools.build.lib.bugreport

import com.google.common.base.Function
import com.google.common.base.Throwables
import com.google.common.collect.ImmutableList
import com.google.common.collect.Lists
import com.google.common.truth.Subject
import com.google.common.util.concurrent.Futures
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.testutil.TestUtils
import org.junit.After
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.nio.file.Files
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Tests for [BugReport].
 * 
 * 
 * Uses [ExitProhibitingSecurityManager] to exercise attempting to halt the JVM without
 * aborting the whole test.
 */
// TODO(b/222158599): Remove handling for GoogleTestSecurityManager.
@RunWith(TestParameterInjector::class)
class BugReportTest {
    @TestParameter
    private val oomDetectorOverride = false

    @Before
    fun maybeSetOomDetector() {
        if (oomDetectorOverride) {
            CrashFailureDetails.setOomDetector({ true })
        }
    }

    @After
    fun restoreDefaultOomDetector() {
        if (oomDetectorOverride) {
            CrashFailureDetails.setOomDetector({ false })
        }
    }

    private enum class CrashType(expectedExitCode: ExitCode, expectedFailureDetailCode: Code) {
        CRASH(ExitCode.BLAZE_INTERNAL_ERROR, Code.CRASH_UNKNOWN) {
            override fun createThrowable(): Throwable {
                return IllegalStateException("Crashed")
            }
        },
        OOM(ExitCode.OOM_ERROR, Code.CRASH_OOM) {
            override fun createThrowable(): Throwable {
                return OutOfMemoryError("Java heap space")
            }
        };

        private val expectedExitCode: ExitCode
        private val expectedFailureDetailCode: Code?

        init {
            this.expectedExitCode = expectedExitCode
            this.expectedFailureDetailCode = expectedFailureDetailCode
        }

        abstract fun createThrowable(): Throwable
    }

    private enum class ExceptionType(throwable: Throwable, isFatal: Boolean, level: Level, expectedMessage: String) {
        FATAL(
            RuntimeException("fatal exception"),  /* isFatal= */
            true,
            Level.SEVERE,
            "myProductName crashed with args: arg foo"
        ),
        NONFATAL(
            IllegalStateException("bug report"),  /* isFatal= */
            false,
            Level.WARNING,
            "myProductName had a non fatal error with args: arg foo"
        ),
        OOM(
            OutOfMemoryError("Java heap space"),  /* isFatal= */
            true,
            Level.SEVERE,
            "myProductName OOMError: arg foo"
        );

        // I'm pretty sure no one will mutate this Throwable.
        private val throwable: Throwable?

        // Same here.
        private val level: Level?

        private val isFatal: Boolean

        val expectedMessage: String

        init {
            this.throwable = throwable
            this.isFatal = isFatal
            this.level = level
            this.expectedMessage = expectedMessage
        }

        val expectedMessageWhileOoming: String
            get() = "While OOMing, " + expectedMessage
    }

    @Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val mockRuntime: BlazeRuntimeInterface =
        Mockito.mock<BlazeRuntimeInterface>(BlazeRuntimeInterface::class.java)

    private var exitCodeFile: Path? = null
    private var failureDetailFile: Path? = null

    @Before
    @Throws(Exception::class)
    fun setup() {
        Mockito.`when`<Any?>(mockRuntime.productName).thenReturn("myProductName")
        BugReport.setRuntime(mockRuntime)

        exitCodeFile = tmp.newFolder().toPath().resolve("exit_code_to_use_on_abrupt_exit")
        failureDetailFile = tmp.newFolder().toPath().resolve("failure_detail")

        CustomExitCodePublisher.setAbruptExitStatusFileDir(exitCodeFile.getParent().toString())
        CustomFailureDetailPublisher.setFailureDetailFilePath(failureDetailFile.toString())
    }

    @After
    fun resetPublishers() {
        CustomExitCodePublisher.resetAbruptExitStatusFile()
        CustomFailureDetailPublisher.resetFailureDetailFilePath()
    }

    @Test
    fun logException(@TestParameter exceptionType: ExceptionType) {
        val handler: TestLogHandler = TestLogHandler()
        val logger = Logger.getLogger("build.lib.bugreport")
        logger.addHandler(handler)
        LoggingUtil.installRemoteLoggerForTesting(Futures.immediateFuture<V?>(logger))

        BugReport.logException(
            exceptionType.throwable, exceptionType.isFatal, ImmutableList.of<String?>("arg", "foo")
        )
        val got: LogRecord = handler.getStoredLogRecords().get(0)
        if (oomDetectorOverride) {
            Truth.assertThat(got.getMessage()).isEqualTo(exceptionType.expectedMessageWhileOoming)
        } else {
            Truth.assertThat(got.getMessage()).isEqualTo(exceptionType.expectedMessage)
        }
        Truth.assertThat(got.getThrown()).isSameInstanceAs(exceptionType.throwable)
        Truth.assertThat(got.getLevel()).isEqualTo(exceptionType.level)
    }

    @Test
    @Throws(Exception::class)
    fun convenienceMethod(@TestParameter crashType: CrashType) {
        val t = crashType.createThrowable()
        val expectedFailureDetail: FailureDetail =
            createExpectedFailureDetail(t, crashType, oomDetectorOverride)
        val exitException =
            Assert.assertThrows<SecurityException>(
                SecurityException::class.java,
                ThrowingRunnable { BugReport.handleCrash(Crash.from(t), CrashContext.halt()) })
        val code: Int = haltCode(exitException)
        Truth.assertThat(code).isEqualTo(expectedExitCode(crashType).numericExitCode)
        Truth.assertThat(BugReport.andResetLastCrashingThrowableIfInTest).isSameInstanceAs(t)

        Mockito.verify<BlazeRuntimeInterface?>(mockRuntime)
            .cleanUpForCrash(
                DetailedExitCode.of(
                    if (oomDetectorOverride) EXIT_CODE_BLAZE_OOMING else crashType.expectedExitCode,
                    expectedFailureDetail
                )
            )
        verifyExitCodeWritten(
            if (oomDetectorOverride)
                EXIT_CODE_BLAZE_OOMING.numericExitCode
            else
                crashType.expectedExitCode.numericExitCode
        )
        verifyFailureDetailWritten(expectedFailureDetail)
    }

    @Test
    @Throws(Exception::class)
    fun halt(@TestParameter crashType: CrashType) {
        val t = crashType.createThrowable()
        val expectedFailureDetail: FailureDetail =
            createExpectedFailureDetail(t, crashType, oomDetectorOverride)

        val exitException =
            Assert.assertThrows<SecurityException>(
                SecurityException::class.java,
                ThrowingRunnable { BugReport.handleCrash(Crash.from(t), CrashContext.halt()) })
        val code: Int = haltCode(exitException)
        val expectedExitCode: ExitCode = expectedExitCode(crashType)
        Truth.assertThat(code).isEqualTo(expectedExitCode.numericExitCode)
        Truth.assertThat(BugReport.andResetLastCrashingThrowableIfInTest).isSameInstanceAs(t)
        Mockito.verify<BlazeRuntimeInterface?>(mockRuntime)
            .cleanUpForCrash(
                DetailedExitCode.of(
                    if (oomDetectorOverride) EXIT_CODE_BLAZE_OOMING else crashType.expectedExitCode,
                    expectedFailureDetail
                )
            )
        verifyExitCodeWritten(
            if (oomDetectorOverride)
                EXIT_CODE_BLAZE_OOMING.numericExitCode
            else
                crashType.expectedExitCode.numericExitCode
        )
        verifyFailureDetailWritten(expectedFailureDetail)
    }

    @Test
    @Throws(Exception::class)
    fun keepAlive(@TestParameter crashType: CrashType) {
        val t = crashType.createThrowable()
        val expectedFailureDetail: FailureDetail =
            createExpectedFailureDetail(t, crashType, oomDetectorOverride)

        BugReport.handleCrash(Crash.from(t), CrashContext.keepAlive())
        Truth.assertThat(BugReport.andResetLastCrashingThrowableIfInTest).isSameInstanceAs(t)

        Mockito.verify<BlazeRuntimeInterface?>(mockRuntime)
            .cleanUpForCrash(
                DetailedExitCode.of(
                    if (oomDetectorOverride) EXIT_CODE_BLAZE_OOMING else crashType.expectedExitCode,
                    expectedFailureDetail
                )
            )
        verifyNoExitCodeWritten()
        verifyFailureDetailWritten(expectedFailureDetail)
    }

    @Test
    @Throws(Throwable::class)
    fun haltOrReturnIfCrashInProgress_otherCrashInProgress_returnsEagerly(
        @TestParameter crashType: CrashType
    ) {
        // Arrange:
        // A first thread will crash with CrashContext.halt(). We mock out the BlazeRuntimeInterface to
        // force this thread to block while holding the BugReport global lock.
        val cleanupBegunLatch: CountDownLatch = CountDownLatch(1)
        val cleanupMayFinishLatch: CountDownLatch = CountDownLatch(1)
        Mockito.doAnswer(
            Answer { inv: InvocationOnMock? ->
                cleanupBegunLatch.countDown()
                cleanupMayFinishLatch.await()
                null
            })
            .`when`<BlazeRuntimeInterface?>(mockRuntime)
            .cleanUpForCrash(ArgumentMatchers.any<DetailedExitCode?>(DetailedExitCode::class.java))

        val firstThrown: Throwable = IllegalStateException("second crash in background thread")
        val doFirstCrash =
            ThrowingRunnable { BugReport.handleCrash(Crash.from(firstThrown), CrashContext.halt()) }
        val firstCrashThrownRef: AtomicReference<SecurityException> = AtomicReference<SecurityException>(null)
        val firstCrashThread: TestThread =
            TestThread(
                TestRunnable {
                    firstCrashThrownRef.set(
                        Assert.assertThrows<SecurityException?>(
                            SecurityException::class.java,
                            doFirstCrash
                        )
                    )
                })
        firstCrashThread.start()
        cleanupBegunLatch.await()

        // Act:
        // Try to crash on a second thread, with a `haltOrReturnIfCrashInProgress` CrashContext. This
        // should return without throwing because the BugReport global lock is held.
        val secondThrown = crashType.createThrowable()
        val haltOrReturnCtx: CrashContext? = CrashContext.haltOrReturnIfCrashInProgress()
        val doSecondCrash =
            ThrowingRunnable { BugReport.handleCrash(Crash.from(secondThrown), haltOrReturnCtx!!) }
        doSecondCrash.run()

        // Assert:
        // Allow the first crashing thread to finish, then confirm that the
        // `CrashContext.haltOrReturnIfCrashInProgress()` will halt when BugReport's lock is free.
        cleanupMayFinishLatch.countDown()
        firstCrashThread.joinAndAssertState(TestUtils.WAIT_TIMEOUT_MILLISECONDS)

        val firstException: SecurityException = firstCrashThrownRef.get()
        val firstCode: Int = haltCode(firstException)
        val expectedExitCode: ExitCode =
            if (oomDetectorOverride) EXIT_CODE_BLAZE_OOMING else ExitCode.BLAZE_INTERNAL_ERROR
        Truth.assertThat(firstCode).isEqualTo(expectedExitCode.numericExitCode)

        val secondException = Assert.assertThrows<SecurityException>(SecurityException::class.java, doSecondCrash)
        val secondCode: Int = haltCode(secondException)
        Truth.assertThat(secondCode).isEqualTo(expectedExitCode(crashType).numericExitCode)
    }

    @Test
    fun customContext_setUpFront(@TestParameter crashType: CrashType) {
        val t = crashType.createThrowable()
        val handler: EventHandler? = Mockito.mock<EventHandler?>(EventHandler::class.java)
        val event: ArgumentCaptor<Event?> = ArgumentCaptor.forClass<Event?, Event?>(Event::class.java)

        BugReport.handleCrash(
            Crash.from(t),
            CrashContext.keepAlive().withExtraOomInfo("Build fewer targets!").reportingTo(handler)
        )
        Truth.assertThat(BugReport.andResetLastCrashingThrowableIfInTest).isSameInstanceAs(t)

        Mockito.verify<Any?>(handler).handle(event.capture())
        assertThat(event.getValue().getKind()).isEqualTo(EventKind.FATAL)
        Subject.contains(Throwables.getStackTraceAsString(t))
        if (oomDetectorOverride || crashType === CrashType.OOM) {
            Subject.contains("ran out of memory and crashed.")
            Subject.contains("Build fewer targets!")
        } else {
            assertThat(event.getValue().getMessage()).doesNotContain("Build fewer targets!")
        }
    }

    @Test
    fun customContext_filledInByRuntime(@TestParameter crashType: CrashType) {
        val t = crashType.createThrowable()
        val handler: EventHandler = Mockito.mock<EventHandler>(EventHandler::class.java)
        val event: ArgumentCaptor<Event?> = ArgumentCaptor.forClass<Event?, Event?>(Event::class.java)
        Mockito.doAnswer(
            Answer { inv: InvocationOnMock? ->
                inv.getArgument<CrashContext?>(0, CrashContext::class.java)
                    .withExtraOomInfo("Build fewer targets!")
                    .reportingTo(handler)
            })
            .`when`<BlazeRuntimeInterface?>(mockRuntime)
            .fillInCrashContext(ArgumentMatchers.any<CrashContext?>())

        BugReport.handleCrash(Crash.from(t), CrashContext.keepAlive())
        Truth.assertThat(BugReport.andResetLastCrashingThrowableIfInTest).isSameInstanceAs(t)

        Mockito.verify<Any?>(handler).handle(event.capture())
        assertThat(event.getValue().getKind()).isEqualTo(EventKind.FATAL)
        Subject.contains(Throwables.getStackTraceAsString(t))

        if (oomDetectorOverride || crashType === CrashType.OOM) {
            Subject.contains("ran out of memory and crashed.")
            Subject.contains("Build fewer targets!")
        } else {
            assertThat(event.getValue().getMessage()).doesNotContain("Build fewer targets!")
        }
    }

    @Throws(Exception::class)
    private fun verifyExitCodeWritten(exitCode: Int) {
        Truth.assertThat(Files.readAllLines(exitCodeFile)).containsExactly(exitCode.toString())
    }

    private fun verifyNoExitCodeWritten() {
        Truth.assertThat(exitCodeFile.toFile().exists()).isFalse()
    }

    @Throws(Exception::class)
    private fun verifyFailureDetailWritten(expected: FailureDetail?) {
        assertThat(
            FailureDetail.parseFrom(
                Files.readAllBytes(failureDetailFile), ExtensionRegistry.getEmptyRegistry()
            )
        )
            .isEqualTo(expected)
    }

    private fun expectedExitCode(crashType: CrashType): ExitCode {
        return if (oomDetectorOverride) EXIT_CODE_BLAZE_OOMING else crashType.expectedExitCode
    }

    companion object {
        private val EXIT_CODE_BLAZE_OOMING: ExitCode = ExitCode.OOM_ERROR
        private val FAILURE_DETAIL_CODE_BLAZE_OOMING: Code? = Code.CRASH_OOM

        private fun createExpectedFailureDetail(
            t: Throwable, crashType: CrashType, oomDetectorOverride: Boolean
        ): FailureDetail {
            val crash: FailureDetails.Crash.Builder =
                FailureDetails.Crash.newBuilder()
                    .setCode(
                        if (oomDetectorOverride)
                            FAILURE_DETAIL_CODE_BLAZE_OOMING
                        else
                            crashType.expectedFailureDetailCode
                    )
                    .addCauses(
                        FailureDetails.Throwable.newBuilder()
                            .setThrowableClass(t.javaClass.getName())
                            .setMessage(t.message)
                            .addAllStackTrace(
                                Lists.transform<F?, T?>(
                                    Arrays.< T > asList < T ? > (t.getStackTrace()),
                                    Function { obj: F? -> obj.toString() })
                            )
                    )
            if (oomDetectorOverride && crashType === CrashType.CRASH) {
                crash.setOomCauseCategory(OomCauseCategory.OOM_DETECTOR_OVERRIDE)
            } else if (crashType === CrashType.OOM) {
                crash.setOomCauseCategory(OomCauseCategory.ORGANIC)
            }
            return FailureDetail.newBuilder()
                .setMessage(String.format("Crashed: (%s) %s", t.javaClass.getName(), t.message))
                .setCrash(crash)
                .build()
        }

        private val SECURITY_EXCEPTION_MESSAGE_PATTERN: Pattern =
            Pattern.compile("Intercepted call to Runtime\\.halt with status (\\d+)")

        private fun haltCode(exitException: SecurityException): Int {
            val message = exitException.message
            Truth.assertThat(message).matches(SECURITY_EXCEPTION_MESSAGE_PATTERN)
            val matcher: Matcher = SECURITY_EXCEPTION_MESSAGE_PATTERN.matcher(message)
            Truth.assertThat(matcher.matches()).isTrue()
            return matcher.group(1).toInt()
        }
    }
}
