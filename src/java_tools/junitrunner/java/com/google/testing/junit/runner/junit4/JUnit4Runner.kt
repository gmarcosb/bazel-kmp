// Copyright 2010 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.junit4

import com.google.testing.junit.runner.internal.SystemExitDetectingShutdownHook
import org.junit.runner.Description
import org.junit.runner.Request
import org.junit.runner.Result
import org.junit.runner.Runner
import org.junit.runner.manipulation.Filter
import org.junit.runner.notification.Failure
import java.io.File
import java.util.function.Supplier

/**
 * Main entry point for running JUnit4 tests.
 *
 *
 */
class JUnit4Runner internal constructor(
    private val request: Request,
    requestFactory: CancellableRequestFactory,
    modelSupplier: Supplier<TestSuiteModel>,
    testRunnerOut: PrintStream,
    config: JUnit4Config,
    runListeners: MutableSet<RunListener?>,
    initializers: MutableSet<Initializer>
) {
    private val requestFactory: CancellableRequestFactory
    private val modelSupplier: Supplier<TestSuiteModel>
    private val testRunnerOut: PrintStream
    private val config: JUnit4Config
    private val runListeners: MutableSet<RunListener?>
    private val initializers: MutableSet<Initializer>

    /** Creates a runner.  */
    init {
        this.requestFactory = requestFactory
        this.modelSupplier = modelSupplier
        this.config = config
        this.testRunnerOut = testRunnerOut
        this.runListeners = runListeners
        this.initializers = initializers
    }

    /**
     * Runs the JUnit4 test.
     * 
     * @return Result of running the test
     */
    fun run(): Result? {
        testRunnerOut.println("JUnit4 Test Runner")
        checkJUnitRunnerApiVersion()

        for (init in initializers) {
            init.initialize()
        }

        // Sharding
        val model: TestSuiteModel = modelSupplier.get()
        val shardingFilter: Filter? = model.getShardingFilter()

        val filteredRequest: Request = applyFilters(
            request, shardingFilter,
            config.getTestIncludeFilterRegexp(),
            config.getTestExcludeFilterRegexp()
        )

        val core: JUnitCore = JUnitCore()
        for (runListener in runListeners) {
            core.addListener(runListener)
        }
        if (config.getTestRunnerFailFast()) {
            core.addListener(StopOnFailureRunListener(requestFactory))
        }

        val exitFile: File? = exitFile
        exitFileActive(exitFile)
        val shutdownHook: Thread? = SystemExitDetectingShutdownHook.newShutdownHook(testRunnerOut)
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        try {
            val cancellableRequest = requestFactory.createRequest(filteredRequest)
            return core.run(cancellableRequest)
        } finally {
            exitFileInactive(exitFile)
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        }
    }

    private fun exitFileInactive(file: File?) {
        if (file != null) {
            try {
                file.delete()
            } catch (t: Throwable) {
                // Just print the stack trace, to avoid masking a real test failure.
                t.printStackTrace(testRunnerOut)
            }
        }
    }

    val model: TestSuiteModel?
        // VisibleForTesting
        get() = modelSupplier.get()

    private fun checkJUnitRunnerApiVersion() {
        config.getJUnitRunnerApiVersion()
    }

    internal class NoOpRunner : Runner() {
        override fun getDescription(): Description {
            return Description.createTestDescription(javaClass, "nothingToDo")
        }

        override fun run(notifier: RunNotifier?) {
        }
    }

    /**
     * A simple initializer which can be used to provide additional initialization logic in custom
     * runners.
     * 
     * 
     * Initializers will be run in unspecified order. If an exception is thrown it will not be
     * deemed recoverable and will cause the runner to error-out.
     */
    interface Initializer {
        fun initialize()
    }

    /** RunListener that requests test execution to stop upon first failure.  */
    private class StopOnFailureRunListener(cancellableRequestFactory: CancellableRequestFactory) : RunListener() {
        private val cancellableRequestFactory: CancellableRequestFactory

        init {
            this.cancellableRequestFactory = cancellableRequestFactory
        }

        @Throws(Exception::class)
        override fun testFailure(failure: Failure?) {
            cancellableRequestFactory.cancelRunOrderly()
        }
    }

    companion object {
        private val exitFile: File?
            // Support for "premature exit files": Tests may write this to communicate
            get() {
                val exitFile = System.getenv("TEST_PREMATURE_EXIT_FILE")
                return if (exitFile == null) null else File(exitFile)
            }

        private fun exitFileActive(file: File?) {
            if (file != null) {
                try {
                    FileOutputStream(file, false).use { outputStream ->
                        // Overwrite file content.
                        outputStream.write(ByteArray(0))
                        outputStream.close()
                    }
                } catch (e: IOException) {
                    throw RuntimeException("Could not write exit file at " + file, e)
                }
            }
        }

        @Throws(NoTestsRemainException::class)
        private fun applyFilter(request: Request, filter: Filter?): Request {
            val runner = request.getRunner()
            SuiteTrimmingFilter(filter).apply(runner)
            return Request.runner(runner)
        }

        /**
         * Apply command-line and sharding filters, if appropriate.
         *
         *
         * 
         * Note that this is carefully written to avoid running into potential
         * problems with the way runners implement filtering. The JavaDoc for
         * [org.junit.runner.manipulation.Filterable] states that tests that
         * don't match the filter should be removed, which implies if you apply two
         * filters, you will always get an intersection of the two. Unfortunately, the
         * filtering implementation of [org.junit.runners.ParentRunner] does not
         * do this, and instead uses a "last applied filter wins" strategy.
         *
         *
         * 
         * We work around potential problems by ensuring that if we apply a second
         * filter, the filter is more restrictive than the first. We also assume that
         * if filtering fails, the request will have a runner that is a
         * [ErrorReportingRunner]. Luckily, we can cover this with tests to make
         * sure we don't break if JUnit changes in the future.
         * 
         * @param request Request to filter
         * @param shardingFilter Sharding filter to use; [Filter.ALL] to not do sharding
         * @param testIncludeFilterRegexp String denoting a regular expression with which
         * to filter tests.  Only test descriptions that match this regular
         * expression will be run.  If `null`, tests will not be filtered.
         * @param testExcludeFilterRegexp String denoting a regular expression with which
         * to filter tests.  Only test descriptions that do not match this regular
         * expression will be run.  If `null`, tests will not be filtered.
         * @return Filtered request (may be a request that delegates to
         * [ErrorReportingRunner]
         */
        private fun applyFilters(
            request: Request, shardingFilter: Filter?,
            testIncludeFilterRegexp: String?, testExcludeFilterRegexp: String?
        ): Request {
            // Allow the user to specify a filter on the command line
            var request = request
            var allowNoTests = false
            var filter = Filter.ALL
            if (testIncludeFilterRegexp != null) {
                filter = RegExTestCaseFilter.Companion.include(testIncludeFilterRegexp)
            }

            if (testExcludeFilterRegexp != null) {
                val excludeFilter: Filter = RegExTestCaseFilter.Companion.exclude(testExcludeFilterRegexp)
                filter = filter.intersect(excludeFilter)
            }

            if (testIncludeFilterRegexp != null || testExcludeFilterRegexp != null) {
                try {
                    request = applyFilter(request, filter)
                } catch (e: NoTestsRemainException) {
                    return createErrorReportingRequestForFilterError(filter)
                }

                /*
       * If you filter a sharded test to run one test, we don't want all the
       * shards but one to fail.
       */
                allowNoTests = (shardingFilter !== Filter.ALL)
            }

            // Sharding
            if (shardingFilter !== Filter.ALL) {
                filter = filter.intersect(shardingFilter)
            }

            if (filter !== Filter.ALL) {
                try {
                    request = applyFilter(request, filter)
                } catch (e: NoTestsRemainException) {
                    if (allowNoTests) {
                        return Request.runner(NoOpRunner())
                    } else {
                        return createErrorReportingRequestForFilterError(filter)
                    }
                }
            }
            return request
        }

        private fun createErrorReportingRequestForFilterError(filter: Filter): Request {
            val runner: ErrorReportingRunner = ErrorReportingRunner(
                Filter::class.java, Exception(
                    String.format("No tests found matching %s", filter.describe())
                )
            )
            return Request.runner(runner)
        }
    }
}
