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
package com.google.devtools.build.lib.bugreport

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.base.Strings
import com.google.common.base.Throwables
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.analysis.BlazeVersionInfo
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.util.TestType
import com.google.errorprone.annotations.FormatMethod
import com.google.errorprone.annotations.FormatString
import java.lang.management.ManagementFactory
import java.util.*
import java.util.logging.Level
import javax.annotation.concurrent.GuardedBy

/**
 * Utility methods for handling crashes: we log the crash, optionally send a bug report, and then
 * terminate the jvm.
 * 
 * 
 * Note, code in this class must be extremely robust. There's nothing worse than a crash-handler
 * that itself crashes!
 */
object BugReport {
    private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

    val REPORTER_INSTANCE: BugReporter = DefaultBugReporter()

    // TODO(b/232094803): Replace the static state with instance variables and allow custom overrides
    //  for testing.
    private val VERSION_INFO: BlazeVersionInfo = BlazeVersionInfo.instance()

    private var runtime: BlazeRuntimeInterface? = null

    @GuardedBy("lock")
    private var lastCrashingThrowable: Throwable? = null

    /**
     * Global lock held while reporting a crash.
     * 
     * 
     * Holding a global lock isn't ideal, but it ensures that concurrent crashes produce coherent
     * bug reports to the user, logs, and bug-reporting backend.
     */
    private val lock: ReentrantLock = ReentrantLock( /* fair= */true)

    private val SHOULD_NOT_SEND_BUG_REPORT_BECAUSE_IN_TEST =
        TestType.isInTest() && System.getenv("ENABLE_BUG_REPORT_LOGGING_IN_TEST") == null

    @kotlin.jvm.JvmStatic
    fun setRuntime(newRuntime: BlazeRuntimeInterface?) {
        Preconditions.checkNotNull<BlazeRuntimeInterface?>(newRuntime)
        Preconditions.checkState(
            runtime == null || TestType.isInTest(), "runtime already set: %s, %s", runtime, newRuntime
        )
        runtime = newRuntime
    }

    private val productName: String?
        get() = if (runtime != null) runtime!!.productName else "<unknown>"

    @kotlin.jvm.JvmStatic
    val andResetLastCrashingThrowableIfInTest: Throwable?
        /**
         * Returns the last crashing throwable passed to [.handleCrash] and clears the stored value.
         */
        get() {
            if (TestType.isInTest()) {
                // Instead of the jvm having been halted, we might have a saved Throwable.
                lock.lock()
                try {
                    val result = lastCrashingThrowable
                    lastCrashingThrowable = null
                    return result
                } finally {
                    lock.unlock()
                }
            }
            return null
        }

    /**
     * In tests, throws if a there was a [.handleCrash] call since the last time this method or
     * [.getAndResetLastCrashingThrowableIfInTest] was called.
     * 
     * 
     * This method exists because Runtime#halt is disabled. Thus, the main thread should call this
     * method whenever it is about to block on thread completion that might hang because of a failed
     * or ignored crash.
     */
    @kotlin.jvm.JvmStatic
    fun maybePropagateLastCrashIfInTest() {
        if (TestType.isInTest()) {
            // Instead of the jvm having been halted, we might have a saved Throwable.
            lock.lock()
            try {
                val lastUnprocessedThrowableInTest: Throwable? = andResetLastCrashingThrowableIfInTest
                if (lastUnprocessedThrowableInTest != null) {
                    throw IllegalStateException(
                        "Unprocessed throwable detected in test", lastUnprocessedThrowableInTest
                    )
                }
            } finally {
                lock.unlock()
            }
        }
    }

    /**
     * Used when an unexpected state is encountered that is not a problem in itself: the program can
     * continue running with no issues for the user, but some assumption of the programmer was wrong.
     * Use this instead of [.sendBugReport] if the issue will not be a high priority to debug
     * (such as an improperly transformed exception in Skyframe).
     * 
     * 
     * Since this is an unexpected state, it will fail if called during a test: either this state
     * can be reached and the call to this method should be deleted, or this points to a separate bug
     * that should be fixed so that this state isn't reached.
     */
    @kotlin.jvm.JvmStatic
    @FormatMethod
    fun logUnexpected(@FormatString message: String, vararg args: Any?) {
        if (SHOULD_NOT_SEND_BUG_REPORT_BECAUSE_IN_TEST) {
            sendBugReport(message, *args)
        } else {
            logger
                .atWarning()
                .atMostEvery(50, TimeUnit.MILLISECONDS)
                .logVarargs("Unexpected state: " + message, args)
        }
    }

    /** See [.logUnexpected].  */
    @kotlin.jvm.JvmStatic
    @FormatMethod
    fun logUnexpected(e: Exception?, @FormatString message: String, vararg args: Any?) {
        if (SHOULD_NOT_SEND_BUG_REPORT_BECAUSE_IN_TEST) {
            sendBugReport(IllegalStateException(java.lang.String.format(message, *args), e))
        } else {
            logger
                .atWarning()
                .atMostEvery(50, TimeUnit.MILLISECONDS)
                .withCause(e)
                .logVarargs("Unexpected state: " + message, args)
        }
    }

    /**
     * Convenience method for [.sendBugReport], sending a bug report with a default
     * [IllegalStateException].
     */
    @kotlin.jvm.JvmStatic
    @FormatMethod
    fun sendBugReport(@FormatString message: String, vararg args: Any?) {
        sendBugReport(IllegalStateException(java.lang.String.format(message, *args)))
    }

    /**
     * Convenience method for [sending a bug][.sendBugReport] without additional arguments.
     */
    @kotlin.jvm.JvmStatic
    fun sendBugReport(exception: Throwable) {
        sendBugReport(exception,  /*args=*/ImmutableList.of<String?>())
    }

    /**
     * Logs the unhandled exception with a special prefix signifying that this was a crash.
     * 
     * @param exception the unhandled exception to display.
     * @param args additional values to record in the message.
     * @param values Additional string values to clarify the exception.
     */
    fun sendBugReport(exception: Throwable, args: MutableList<String?>?, vararg values: String?) {
        sendBugReportInternal(exception,  /*isFatal=*/true, filterArgs(args), *values)
    }

    /** Logs the bug report, indicating it is not a crash.  */
    @kotlin.jvm.JvmStatic
    fun sendNonFatalBugReport(exception: Throwable) {
        sendBugReportInternal(exception,  /*isFatal=*/false,  /*args=*/ImmutableList.of<String?>())
    }

    private fun sendBugReportInternal(
        exception: Throwable, isFatal: Boolean, args: MutableList<String?>?, vararg values: String?
    ) {
        if (SHOULD_NOT_SEND_BUG_REPORT_BECAUSE_IN_TEST) {
            Throwables.throwIfUnchecked(exception)
            throw IllegalStateException(
                "Bug reports in tests should crash: " + args + ", " + Arrays.toString(values), exception
            )
        }
        if (!VERSION_INFO.isReleasedBlaze()) {
            logger.atInfo().log("(Not a released binary; not logged.)")
            return
        }

        BugReport.logException(exception, isFatal, filterArgs(args)!!, *values)
    }

    /**
     * Convenience method equivalent to calling `BugReport.handleCrash(Crash.from(throwable), CrashContext.halt().withArgs(args)`.
     * 
     * 
     * Halts the JVM and does not return.
     */
    @kotlin.jvm.JvmStatic
    fun handleCrash(throwable: Throwable?, vararg args: String?): RuntimeException? {
        BugReport.handleCrash(Crash.Companion.from(throwable), CrashContext.Companion.halt().withArgs(*args))
        throw IllegalStateException("Should have halted", throwable)
    }

    /**
     * Handles a [Crash] according to the given [CrashContext].
     * 
     * 
     * In the case of [CrashContext.halt], the JVM is [halted][Runtime.halt].
     * Otherwise, for [CrashContext.keepAlive], returns `null`, in which case the caller
     * is responsible for shutting down the server.
     */
    @kotlin.jvm.JvmStatic
    fun handleCrash(crash: Crash, ctx: CrashContext) {
        val numericExitCode = crash.getDetailedExitCode().getExitCode().getNumericExitCode()
        val throwable = crash.getThrowable()
        if (runtime != null) {
            runtime!!.fillInCrashContext(ctx)
        }
        // Multiple concurrent crashes may deadlock if certain background threads crash while another
        // crash is already being reported. In these cases, log loudly and return eagerly.
        if (ctx.returnIfCrashInProgress()) {
            if (!lock.tryLock()) {
                logger.atSevere().withCause(throwable).log(
                    "Crash already in progress, not reporting to avoid deadlock: %s", ctx
                )
                return
            }
        } else {
            lock.lock()
        }
        try {
            try {
                logger.atSevere().withCause(throwable).log("Handling crash with %s", ctx)

                if (TestType.isInTest()) {
                    lastCrashingThrowable = throwable
                }

                var crashMsg: String?
                val heapDumpPath: String?
                // Might be a wrapped OOM - the detailed exit code reflects the root cause.
                val isOom = crash.getDetailedExitCode().getExitCode() == ExitCode.OOM_ERROR
                if (isOom) {
                    crashMsg = constructOomExitMessage(ctx.getExtraOomInfo())
                    heapDumpPath = ctx.getHeapDumpPath()
                    if (heapDumpPath != null) {
                        crashMsg += " An attempt will be made to write a heap dump to " + heapDumpPath + "."
                    }
                } else {
                    crashMsg = productName + " crashed due to an internal error."
                    heapDumpPath = null
                }
                crashMsg += " Printing stack trace:\n" + Throwables.getStackTraceAsString(throwable)
                ctx.getEventHandler().handle(Event.fatal(crashMsg))

                try {
                    // Emit exit data before sending a bug report. Bug reports involve an RPC, and given that
                    // we are crashing, who knows if it will complete. It's more important that we write
                    // exit code and failure detail information so that the crash can be handled correctly.
                    emitExitData(crash, ctx, numericExitCode, heapDumpPath)
                    // Skip sending a bug report if the crash is an OOM - attempting an RPC while out of
                    // memory can cause issues. Also, don't try to send a bug report during a crash in a test,
                    // it will throw itself.
                    if (ctx.shouldSendBugReport() && !isOom && !TestType.isInTest()) {
                        sendBugReport(throwable, ctx.getArgs())
                    }
                } finally {
                    if (ctx.shouldHaltJvm()) {
                        // Avoid shutdown deadlock issues: If an application shutdown hook crashes, it will
                        // trigger our Blaze crash handler (this method). Calling System#exit() here, would
                        // therefore induce a deadlock. This call would block on the shutdown sequence
                        // completing, but the shutdown sequence would in turn be blocked on this thread
                        // finishing. Instead, exit fast via halt().
                        halt(numericExitCode)
                    }
                }
            } finally {
                lock.unlock()
            }
        } catch (t: Throwable) {
            logger.atSevere().withCause(t).log("Threw while crashing")
            System.err.println(
                ("ERROR: A crash occurred while "
                        + productName
                        + " was trying to handle a crash! Please file a bug against "
                        + productName
                        + " and include the information below.")
            )

            System.err.println("Original uncaught exception:")
            throwable.printStackTrace(System.err)

            System.err.println("Exception encountered during BugReport#handleCrash:")
            t.printStackTrace(System.err)
        } finally {
            if (ctx.shouldHaltJvm()) {
                halt(numericExitCode)
            }
        }
        if (!ctx.shouldHaltJvm()) {
            return
        }
        logger.atSevere().log("Failed to crash in handleCrash")
        throw IllegalStateException("Should have halted", throwable)
    }

    private fun halt(numericExitCode: Int) {
        if (TestType.getTestType() == TestType.UNKNOWN_TEST) {
            // Only intercept halt in unit tests. In shell integration tests, we do want to halt.
            throw SecurityException(
                "Intercepted call to Runtime.halt with status " + numericExitCode
            )
        } else {
            Runtime.getRuntime().halt(numericExitCode)
        }
    }

    /**
     * Writes exit status files, dumps heap if requested, and calls [ ][BlazeRuntimeInterface.cleanUpForCrash].
     */
    private fun emitExitData(
        crash: Crash, ctx: CrashContext, numericExitCode: Int, heapDumpPath: String?
    ) {
        // Writing the exit code status file is only necessary if we are halting. Otherwise, the
        // caller is responsible for an orderly shutdown with the proper exit code.
        if (ctx.shouldHaltJvm()) {
            if (CustomExitCodePublisher.maybeWriteExitStatusFile(numericExitCode)) {
                logger.atInfo().log("Wrote exit status file.")
            } else {
                logger.atWarning().log("Did not write exit status file; check stderr for errors.")
            }
        }

        if (CustomFailureDetailPublisher.maybeWriteFailureDetailFile(
                crash.getDetailedExitCode().getFailureDetail()
            )
        ) {
            logger.atInfo().log("Wrote failure detail file.")
        } else {
            logger.atWarning().log("Did not write failure detail file; check stderr for errors.")
        }

        if (heapDumpPath != null) {
            logger.atInfo().log("Attempting to dump heap to %s", heapDumpPath)
            try {
                dumpHeap(heapDumpPath)
                logger.atInfo().log("Heap dump complete")
            } catch (t: Throwable) { // Catch anything so we don't forgo the OOM.
                logger.atWarning().withCause(t).log("Heap dump failed")
            }
        }

        if (runtime != null) {
            runtime!!.cleanUpForCrash(crash.getDetailedExitCode())
            logger.atInfo().log("Cleaned up runtime.")
        } else {
            logger.atInfo().log("No runtime to clean.")
        }
    }

    @kotlin.jvm.JvmStatic
    fun constructOomExitMessage(extraInfo: String?): String {
        val msg = productName + " ran out of memory and crashed."
        return if (Strings.isNullOrEmpty(extraInfo)) msg else msg + " " + extraInfo
    }

    @Throws(IOException::class)
    private fun dumpHeap(path: String?) {
        val mxBean: HotSpotDiagnosticMXBean =
            ManagementFactory.newPlatformMXBeanProxy<HotSpotDiagnosticMXBean>(
                ManagementFactory.getPlatformMBeanServer(),
                "com.sun.management:type=HotSpotDiagnostic",
                HotSpotDiagnosticMXBean::class.java
            )
        mxBean.dumpHeap(path,  /*live=*/true)
    }

    /**
     * Filters `args` by removing superfluous items:
     * 
     * 
     *  * The client's environment variables may contain sensitive data, so we filter it out.
     *  * `--default_override` is spammy.
     * 
     */
    private fun filterArgs(args: Iterable<String?>?): ImmutableList<String?>? {
        if (args == null) {
            return null
        }

        val filteredArgs = ImmutableList.builder<String?>()
        for (arg in args) {
            if (arg != null && !arg.startsWith("--client_env=") && !arg.startsWith("--default_override=")) {
                filteredArgs.add(arg)
            }
        }
        return filteredArgs.build()
    }

    /**
     * Logs the exception. Because this method is only called in a blaze release, this will result in
     * a report being sent to a remote logging service.
     * 
     * 
     * TODO(b/232094803): Make this method private and replace the tests with ones calling public
     * methods like [.sendBugReport] directly.
     */
    @VisibleForTesting
    fun logException(
        exception: Throwable?, isCrash: Boolean, args: MutableList<String?>, vararg values: String?
    ) {
        logger.atSevere().withCause(exception).log("Exception")
        var preamble: String? =
            if (CrashFailureDetails.oomDetected()) "While OOMing, " + productName else productName
        val level = if (isCrash) Level.SEVERE else Level.WARNING
        if (!isCrash) {
            preamble += " had a non fatal error with args: "
        } else if (exception is OutOfMemoryError) {
            preamble += " OOMError: "
        } else {
            preamble += " crashed with args: "
        }

        logger.atInfo().log("Calling logToRemote")
        LoggingUtil.logToRemote(level, preamble + Joiner.on(' ').join(args), exception, *values)
        logger.atInfo().log("Call to logToRemote complete")
    }

    /**
     * This is a narrow interface for [BugReport]'s usage of BlazeRuntime. It lives in this
     * file, for the sake of avoiding a build-time cycle.
     */
    interface BlazeRuntimeInterface {
        @kotlin.jvm.JvmField
        val productName: String?

        fun fillInCrashContext(ctx: CrashContext?)

        /**
         * Perform all possible clean-up before crashing, posting events etc. so long as crashing isn't
         * significantly delayed or another crash isn't triggered.
         */
        fun cleanUpForCrash(exitCode: DetailedExitCode?)
    }

    private class DefaultBugReporter : BugReporter {
        override fun sendBugReport(exception: Throwable, args: MutableList<String?>?, vararg values: String?) {
            BugReport.sendBugReport(exception, args, *values)
        }

        override fun sendNonFatalBugReport(exception: Throwable) {
            BugReport.sendNonFatalBugReport(exception)
        }

        override fun handleCrash(crash: Crash, ctx: CrashContext) {
            BugReport.handleCrash(crash, ctx)
        }
    }
}
