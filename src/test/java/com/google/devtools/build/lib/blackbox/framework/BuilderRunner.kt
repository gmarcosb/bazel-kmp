// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.blackbox.framework

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableMap
import com.google.common.collect.Lists
import com.google.common.collect.Maps
import com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorDescriptor.Builder.build
import com.google.devtools.build.lib.blackbox.framework.ProcessRunner.ProcessRunnerException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Class for running Bazel process in the working directory of the blackbox test.
 * 
 * 
 * Provides customization methods for timeout, environment variables, etc., which modify the
 * current instance of `BuilderRunner`, and also return it for chain calls.
 * 
 * 
 * The instance keeps only parameters for the Bazel invocations, like timeout or environment
 * variables values, but not the data related to the actual Bazel invocations. That is why the same
 * instance can be used to invoke several commands.
 */
class BuilderRunner internal constructor(
    workDir: Path,
    binaryPath: Path,
    defaultTimeoutMillis: Long,
    env: MutableMap<String?, String?>?,
    executorService: ExecutorService?
) {
    private val workDir: Path
    private val binaryPath: Path
    private val env: MutableMap<String?, String?>
    private val executorService: ExecutorService?
    private var timeoutMillis: Long
    private var useDefaultRc = true
    private var errorCode = 0
    private val flags: MutableList<String?>?
    private var shouldFail = false
    private var enableDebug = false

    /**
     * Creates the BuilderRunner
     * 
     * @param workDir working directory of the test
     * @param binaryPath [Path] to the Bazel executable
     * @param defaultTimeoutMillis default timeout in milliseconds to use if the user has not
     * specified timeout for Bazel command invocation
     * @param env environment variables to be passed to Bazel process (part, common for all Bazel
     * invocations)
     * @param executorService [ExecutorService] to be used by the [ProcessRunner], which
     * actually invokes the Bzel command
     */
    init {
        Preconditions.checkNotNull<Path?>(workDir)
        Preconditions.checkNotNull<Path?>(binaryPath)
        Preconditions.checkNotNull<MutableMap<String?, String?>?>(env)
        Preconditions.checkState(defaultTimeoutMillis > 0, "Expected default timeout to be positive")

        this.workDir = workDir
        this.binaryPath = binaryPath
        this.env = Maps.newHashMap<String?, String?>(env)
        this.executorService = executorService
        this.timeoutMillis = defaultTimeoutMillis
        this.flags = Lists.newArrayList<String?>()
    }

    /**
     * Sets environment variable for the Bazel invocation.
     * 
     * @param name name of the variable
     * @param value value of variable
     * @return this BuildRunner instance
     */
    fun withEnv(name: String?, value: String?): BuilderRunner {
        env.put(name, value)
        return this
    }

    /**
     * Sets the expected error code.
     * 
     * @param errorCode the value of the error code
     * @return this BuildRunner instance
     */
    fun withErrorCode(errorCode: Int): BuilderRunner {
        this.errorCode = errorCode
        return this
    }

    /**
     * Expect Bazel to fail. This method is needed when the exact error code can not be specified.
     * 
     * @return this BuildRunner instance
     */
    fun shouldFail(): BuilderRunner {
        this.shouldFail = true
        return this
    }

    /**
     * Sets timeout value for the Bazel process invocation. If not called, default value is used,
     * which is calculated from the test parameters. See [ ][BlackBoxTestContext.getTestTimeoutMillis]. If the invocation time exceeds timeout, [ ] is thrown.
     * 
     * @param timeoutMillis timeout value in milliseconds
     * @return this BuilderRunner instance
     */
    fun withTimeout(timeoutMillis: Long): BuilderRunner {
        Preconditions.checkState(this.timeoutMillis > 0)
        this.timeoutMillis = timeoutMillis
        return this
    }

    /**
     * Should be used ONLY FOR TESTS DEBUG. Adds "--host_jvm_debug" to the Bazel startup options, so
     * that the JVM waits for the debugger process to connect before executing any code.
     * 
     * @return this BuilderRunner instance
     */
    fun enableDebug(): BuilderRunner {
        this.enableDebug = true
        return this
    }

    /**
     * Specifies that Bazel should not pass the default .bazelrc (in the test working directory) as
     * parameter
     * 
     * @return this BuilderRunner instance
     */
    fun withoutDefaultRc(): BuilderRunner {
        useDefaultRc = false
        return this
    }

    /**
     * Specifies the flags to pass to Bazel.
     * 
     * 
     * We need it as a builder method, so that several consequent Bazel calls with the same set of
     * flags could be performed: bazel build --flag1 --flag2 //... bazel info --flag1 --flag2
     * bazel-bin
     * 
     * @return this BuilderRunner instance
     */
    fun withFlags(vararg flags: String?): BuilderRunner {
        Collections.addAll<String?>(this.flags, *flags)
        return this
    }

    /**
     * Runs `bazel info <parameter>` and returns the result. Asserts the process exit
     * code. Does not assert that the error stream is empty.
     * 
     * @param parameters - info command parameter (can be omitted) and the Bazel flags. If Bazel was
     * invoked with some flags, the same set of flags should be used with info.
     * @return ProcessResult with process exit code, strings with stdout and error streams contents
     * @throws TimeoutException in case of timeout
     * @throws IOException in case of the process startup/interaction problems
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws ProcessRunnerException if the process return code is not zero or error stream is not
     * empty when it was expected
     */
    @Throws(Exception::class)
    fun info(vararg parameters: String?): ProcessResult {
        // additional expectations for the info time to be under the default timeout
        withTimeout(min(DEFAULT_TIMEOUT_MILLIS, timeoutMillis))
        return runBinary("info", *parameters)
    }

    /**
     * Runs `bazel help` and returns the result. Asserts the process exit code. Does not
     * assert that the error stream is empty.
     * 
     * @return ProcessResult with process exit code, strings with stdout and error streams contents
     * @throws TimeoutException in case of timeout
     * @throws IOException in case of the process startup/interaction problems
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws ProcessRunnerException if the process return code is not zero or error stream is not
     * empty when it was expected
     */
    @Throws(Exception::class)
    fun help(): ProcessResult {
        // additional expectations for the info time to be under the default timeout
        withTimeout(min(DEFAULT_TIMEOUT_MILLIS, timeoutMillis))
        return runBinary("help")
    }

    /**
     * Runs `bazel test <args>` and returns the result. Asserts that the process exit
     * code is zero. Does not assert that the error stream is empty.
     * 
     * @param args arguments to pass to test command
     * @return ProcessResult with process exit code, strings with stdout and error streams contents
     * @throws TimeoutException in case of timeout
     * @throws IOException in case of the process startup/interaction problems
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws ProcessRunnerException if the process return code is not zero or error stream is not
     * empty when it was expected
     */
    @Throws(Exception::class)
    fun test(vararg args: String?): ProcessResult {
        return runBinary("test", *args)
    }

    /**
     * Runs `bazel build <args>` and returns the result. Asserts that the process
     * exit code is zero. Does not assert that the error stream is empty.
     * 
     * @param args arguments to pass to build command
     * @return ProcessResult with process exit code, strings with stdout and error streams contents
     * @throws TimeoutException in case of timeout
     * @throws IOException in case of the process startup/interaction problems
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws ProcessRunnerException if the process return code is not zero or error stream is not
     * empty when it was expected
     */
    @Throws(Exception::class)
    fun build(vararg args: String?): ProcessResult {
        return runBinary("build", *args)
    }

    /**
     * Runs `bazel query <args>` and returns the result. Asserts that the process
     * exit code is zero. Does not assert that the error stream is empty.
     * 
     * @param args arguments to pass to query command
     * @return ProcessResult with process exit code, strings with stdout and error streams contents
     * @throws TimeoutException in case of timeout
     * @throws IOException in case of the process startup/interaction problems
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws ProcessRunnerException if the process return code is not zero or error stream is not
     * empty when it was expected
     */
    @Throws(Exception::class)
    fun query(vararg args: String?): ProcessResult {
        return runBinary("query", *args)
    }

    /**
     * Runs `bazel run <args>` and returns the result. Asserts that the process exit
     * code is zero. Does not assert that the error stream is empty.
     * 
     * @param args arguments to pass to run command
     * @return ProcessResult with process exit code, strings with stdout and error streams contents
     * @throws TimeoutException in case of timeout
     * @throws IOException in case of the process startup/interaction problems
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws ProcessRunnerException if the process return code is not zero or error stream is not
     * empty when it was expected
     */
    @Throws(Exception::class)
    fun run(vararg args: String?): ProcessResult {
        return runBinary("run", *args)
    }

    /**
     * Runs `bazel shutdown` and returns the result. Asserts that the process exit code is
     * zero. Does not assert that the error stream is empty.
     * 
     * @return ProcessResult with process exit code, strings with stdout and error streams contents
     * @throws TimeoutException in case of timeout
     * @throws IOException in case of the process startup/interaction problems
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws ProcessRunnerException if the process return code is not zero or error stream is not
     * empty when it was expected
     */
    @Throws(Exception::class)
    fun shutdown(): ProcessResult {
        // additional expectations for the shutdown time to be under the default timeout
        withTimeout(min(DEFAULT_TIMEOUT_MILLIS, timeoutMillis))
        return runBinary("shutdown")
    }

    @Throws(Exception::class)
    private fun runBinary(command: String?, vararg args: String?): ProcessResult {
        val list: MutableList<String?> = Lists.newArrayList<String?>()

        if (useDefaultRc) {
            val bazelRc = workDir.resolve(".bazelrc")
            if (Files.exists(bazelRc)) {
                list.add("--bazelrc")
                list.add(bazelRc.toAbsolutePath().toString())
            }
        }
        if (enableDebug) {
            list.add("--host_jvm_debug")
            // 10 min for debug
            list.add("--max_idle_secs=600")
        }
        list.add(command)
        list.addAll(this.flags!!)
        Collections.addAll<String?>(list, *args)

        val parameters: ProcessParameters? =
            ProcessParameters.Companion.builder()
                .setWorkingDirectory(workDir.toFile())
                .setName(binaryPath.toString())
                .setTimeoutMillis(timeoutMillis)
                .setArguments(list)
                .setEnvironment(ImmutableMap.copyOf<String?, String?>(env)) // bazel writes info messages to error stream, so
                // we need to allow the error output stream be not empty
                .setExpectedEmptyError(false)
                .setExpectedExitCode(errorCode)
                .setExpectedToFail(shouldFail)
                .build()
        return ProcessRunner(parameters, executorService).runSynchronously()
    }

    companion object {
        private val DEFAULT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30)
    }
}
