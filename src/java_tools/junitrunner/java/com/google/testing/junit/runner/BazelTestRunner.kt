// Copyright 2015 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner

import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.devtools.build.lib.clock.Clock.nanoTime
import com.google.testing.junit.runner.internal.SignalHandlers
import com.google.testing.junit.runner.internal.StackTraces
import com.google.testing.junit.runner.junit4.JUnit4Bazel
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.suiteClass
import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import com.google.testing.junit.runner.junit4.JUnit4Runner
import com.google.testing.junit.runner.junit4.JUnit4Runner.run
import net.starlark.java.syntax.Identifier.getName
import java.io.PrintStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

/**
 * A class to run JUnit tests in a controlled environment.
 * 
 * 
 * Currently sets up a security manager to catch undesirable behaviour; System.exit. Also has
 * nice command line options - run with "-help" for details.
 * 
 * 
 * This class traps writes to `System.err.println()` and `System.out.println()
` *  including the output of failed tests in the error report.
 * 
 * 
 * It also traps SIGTERM signals to make sure that the test report is written when the signal is
 * closed by the unit test framework for running over time.
 */
// for signal handling, see JDK-8349056
object BazelTestRunner {
    /**
     * If no arguments are passed on the command line, use this System property to determine which
     * test suite to run.
     */
    const val TEST_SUITE_PROPERTY_NAME: String = "bazel.test_suite"

    const val AWAIT_NON_DAEMON_THREADS_PROPERTY_NAME: String = "bazel.test_runner.await_non_daemon_threads"

    private const val EXIT_CODE_SUCCESS = 0
    private const val EXIT_CODE_TEST_FAILURE_OTHER = 1
    private const val EXIT_CODE_TEST_RUNNER_FAILURE = 2
    private const val EXIT_CODE_TEST_FAILURE_OOM = 137

    /**
     * Takes as arguments the classes or packages to test.
     * 
     * 
     * To help just run one test or method in a suite, the test suite may be passed in via system
     * properties (-Dbazel.test_suite). An empty args parameter means to run all tests in the suite. A
     * non-empty args parameter means to run only the specified tests/methods.
     * 
     * 
     * Return codes:
     * 
     * 
     *  * Test runner failure, bad arguments, etc.: exit code of 2
     *  * Test failure that included an OutOfMemoryException: exit code of 137
     *  * Normal test failure: exit code of 1
     *  * All tests pass: exit code of 0
     * 
     */
    @kotlin.jvm.JvmStatic
    fun main(args: Array<String>) {
        val stderr: PrintStream = java.lang.System.err

        // Install signal handlers early to ensure stack traces are printed even if the test
        // is interrupted during suite creation.
        installSignalHandlers(stderr)

        val suiteClassName: String? = java.lang.System.getProperty(TEST_SUITE_PROPERTY_NAME)
        if (!checkTestSuiteProperty(suiteClassName)) {
            java.lang.System.exit(EXIT_CODE_TEST_RUNNER_FAILURE)
        }

        var exitCode: Int
        try {
            exitCode = BazelTestRunner.runTestsInSuite(suiteClassName, args)
        } catch (e: Throwable) {
            // An exception was thrown by the runner. Print the error to the output stream so it will be
            // logged
            // by the executing strategy, and return a failure, so this process can gracefully shut down.
            e.printStackTrace()
            exitCode =
                if (e is java.lang.OutOfMemoryError) EXIT_CODE_TEST_FAILURE_OOM else EXIT_CODE_TEST_FAILURE_OTHER
        }

        java.lang.System.err.printf("%nBazelTestRunner exiting with a return value of %d%n", exitCode)
        java.lang.System.err.println("JVM shutdown hooks (if any) will run now.")
        java.lang.System.err.println("The JVM will exit once they complete.")
        java.lang.System.err.println()

        printStackTracesIfJvmExitHangs(stderr)
        awaitAllNonDaemonThreadsToFinish()

        val format: DateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val shutdownTime: java.util.Date = java.util.Date()
        val formattedShutdownTime: String = format.format(shutdownTime)
        java.lang.System.err.printf("-- JVM shutdown starting at %s --%n%n", formattedShutdownTime)
        java.lang.System.exit(exitCode)
    }

    /**
     * Ensures that the bazel.test_suite in argument is not `null` or print error and
     * explanation.
     * 
     * @param testSuiteProperty system property to check
     */
    private fun checkTestSuiteProperty(testSuiteProperty: String?): Boolean {
        if (testSuiteProperty == null) {
            java.lang.System.err.printf(
                "Error: The test suite Java system property %s is required but missing.%n",
                TEST_SUITE_PROPERTY_NAME
            )
            java.lang.System.err.println()
            java.lang.System.err.println("This property is set automatically when running with Bazel like such:")
            java.lang.System.err.printf(
                "  java -D%s=[test-suite-class] %s%n",
                TEST_SUITE_PROPERTY_NAME, BazelTestRunner::class.java.getName()
            )
            java.lang.System.err.printf(
                "  java -D%s=[test-suite-class] -jar [deploy-jar]%n", TEST_SUITE_PROPERTY_NAME
            )
            java.lang.System.err.println("E.g.:")
            java.lang.System.err.printf(
                "  java -D%s=org.example.testing.junit.runner.SmallTests %s%n",
                TEST_SUITE_PROPERTY_NAME, BazelTestRunner::class.java.getName()
            )
            java.lang.System.err.printf(
                "  java -D%s=org.example.testing.junit.runner.SmallTests "
                        + "-jar SmallTests_deploy.jar%n",
                TEST_SUITE_PROPERTY_NAME
            )
            return false
        }
        return true
    }

    /**
     * Runs the tests in the specified suite. Looks for the suite class in the given classLoader, or
     * in the system classloader if none is specified.
     */
    private fun runTestsInSuite(suiteClassName: String?, args: Array<String?>): Int {
        val suite: java.lang.Class<*>? = getTestClass(suiteClassName)

        if (suite == null) {
            // No class found corresponding to the system property passed in from Bazel
            if (args.size == 0 && suiteClassName != null) {
                java.lang.System.err.printf("Class not found: [%s]%n", suiteClassName)
                return EXIT_CODE_TEST_RUNNER_FAILURE
            }
        }

        // TODO(kush): Use a new classloader for the following instantiation.
        val runner: JUnit4Runner =
            JUnit4Bazel.builder().suiteClass(suite)
                .config(com.google.testing.junit.runner.junit4.JUnit4InstanceModules.Config(*args)).build().runner()
        val result: org.junit.runner.Result? = runner.run()
        if (result.wasSuccessful()) {
            return EXIT_CODE_SUCCESS
        }
        return if (result.getFailures().stream()
                .anyMatch { failure: org.junit.runner.notification.Failure? -> failure.getException() is java.lang.OutOfMemoryError }
        )
            EXIT_CODE_TEST_FAILURE_OOM
        else
            EXIT_CODE_TEST_FAILURE_OTHER
    }

    private fun getTestClass(name: String?): java.lang.Class<*>? {
        if (name == null) {
            return null
        }

        try {
            return java.lang.Class.forName(name)
        } catch (e: java.lang.ClassNotFoundException) {
            return null
        }
    }

    /**
     * If the system property `bazel.test_runner.await_non_daemon_threads` is set to true, adds
     * a shutdown hook that waits for all non-daemon threads to finish before allowing the JVM to
     * exit. This is useful for tests that spawn non-daemon threads that may still be running when the
     * test finishes, but should be allowed to finish before the JVM to validate all code paths have
     * proper cleanup logic.
     */
    private fun awaitAllNonDaemonThreadsToFinish() {
        if (!java.lang.Boolean.getBoolean(AWAIT_NON_DAEMON_THREADS_PROPERTY_NAME)) {
            return
        }
        val mainThread: java.lang.Thread = java.lang.Thread.currentThread()
        java.lang.Runtime.getRuntime()
            .addShutdownHook(
                java.lang.Thread(
                    java.lang.Runnable {
                        val currentThread: java.lang.Thread = java.lang.Thread.currentThread()
                        while (true) {
                            val nonDaemonAliveThreads: MutableList<java.lang.Thread?> =
                                java.lang.Thread.getAllStackTraces().keys.stream()
                                    .filter { obj: java.lang.Thread? -> obj.isAlive() }
                                    .filter { thread: java.lang.Thread? -> !thread.isDaemon() }
                                    .filter { t: java.lang.Thread? -> t.getId() != currentThread.getId() }
                                    .filter { t: java.lang.Thread? -> t.getId() != mainThread.getId() }
                                    .collect(Collectors.toList())

                            if (nonDaemonAliveThreads.isEmpty()) {
                                return@Runnable
                            }
                            java.lang.Thread.yield()
                        }
                    })
            )
    }

    /**
     * Prints out stack traces if the JVM does not exit quickly. This can help detect shutdown hooks
     * that are preventing the JVM from exiting quickly.
     * 
     * @param out Print stream to use
     */
    private fun printStackTracesIfJvmExitHangs(out: PrintStream) {
        val thread: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    sleepUninterruptibly(5)
                    out.println("JVM still up after five seconds. Dumping stack traces for all threads.")
                    StackTraces.printAll(out,  /* emitJsonThreadDump= */true)
                },
                "BazelTestRunner: Print stack traces if JVM exit hangs"
            )

        thread.setDaemon(true)
        thread.start()
    }

    /** Invokes SECONDS.[sleep(sleepForSeconds)][TimeUnit.sleep] uninterruptibly.  */
    private fun sleepUninterruptibly(sleepForSeconds: Long) {
        var interrupted = false
        try {
            val end: Long = java.lang.System.nanoTime() + TimeUnit.SECONDS.toNanos(sleepForSeconds)
            while (true) {
                try {
                    // TimeUnit.sleep() treats negative timeouts just like zero.
                    TimeUnit.NANOSECONDS.sleep(end - java.lang.System.nanoTime())
                    return
                } catch (e: java.lang.InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) {
                java.lang.Thread.currentThread().interrupt()
            }
        }
    }

    /** Installs a SIGTERM handler that prints stack traces for all threads.  */
    private fun installSignalHandlers(errPrintStream: PrintStream) {
        val signalHandlers: SignalHandlers = SignalHandlers(SignalHandlers.Companion.createRealHandlerInstaller())
        signalHandlers.installHandler(
            sun.misc.Signal("TERM"),
            sun.misc.SignalHandler { `__`: sun.misc.Signal? ->
                errPrintStream.println("Received SIGTERM, dumping stack traces for all threads\n")
                StackTraces.printAll(errPrintStream,  /* emitJsonThreadDump= */true)
            })
    }
}
