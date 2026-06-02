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

import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Lists
import com.google.common.flogger.GoogleLogger
import com.google.common.flogger.LazyArg
import com.google.common.flogger.LazyArgs
import com.google.common.io.LineReader
import com.google.devtools.build.lib.bazel.repository.downloader.HttpStream.Factory.create
import com.google.devtools.build.lib.bazel.repository.downloader.ProgressInputStream.Factory.create
import com.google.devtools.build.lib.util.StringUtilities
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.stream.Collectors
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/**
 * Helper class for running Bazel process as external process from JUnit tests. Can be used to run
 * arbitrary external process and explore the results.
 */
internal class ProcessRunner
/**
 * Creates ProcessRunner
 * 
 * @param parameters process parameters like executable name, arguments, timeout etc
 * @param executorService to use for process output/error streams reading; intentionally passed as
 * a parameter so we can use the thread pool to speed up. Should be multi-threaded, as two
 * separate tasks are submitted, to read from output and error streams.
 * 
 * SuppressWarnings: WeakerAccess - suppress the warning about constructor being public:
 * the class is intended to be used outside the package. (IDE currently marks the possibility
 * for the constructor to be package-private because the current usages are only inside the
 * package, but it is going to change)
 */(private val parameters: ProcessParameters, private val executorService: ExecutorService) {
    @Throws(Exception::class)
    fun runSynchronously(): ProcessResult {
        val args = parameters.arguments()
        val commandParts: MutableList<String?> = ArrayList<String?>(args.size + 1)
        commandParts.add(parameters.name())
        commandParts.addAll(args)

        logger.atInfo().log(
            "Running: %s", LazyArgs.lazy<String?>(LazyArg { commandParts.stream().collect(Collectors.joining(" ")) })
        )

        val processBuilder = ProcessBuilder(commandParts)
        processBuilder.directory(parameters.workingDirectory())
        parameters.environment()
            .ifPresent(Consumer { map: ImmutableMap<String?, String?>? -> processBuilder.environment().putAll(map!!) })
        // Always clear the variables used for runfiles discovery so that the process doesn't inherit
        // them from the Bazel test environment.
        processBuilder
            .environment()
            .keys
            .removeAll(
                ImmutableSet.of<String?>(
                    "RUNFILES_DIR",
                    "RUNFILES_MANIFEST_FILE",
                    "RUNFILES_MANIFEST_ONLY",
                    "JAVA_RUNFILES",
                    "PYTHON_RUNFILES"
                )
            )

        parameters.redirectOutput()
            .ifPresent(Consumer { path: Path? -> processBuilder.redirectOutput(path!!.toFile()) })
        parameters.redirectError().ifPresent(Consumer { path: Path? -> processBuilder.redirectError(path!!.toFile()) })

        val process = processBuilder.start()

        try {
            if (parameters.redirectOutput().isPresent())
                null
            else
                createReader(process.getInputStream(), ">> ").use { outReader ->
                    if (parameters.redirectError().isPresent())
                        null
                    else
                        createReader(process.getErrorStream(), "ERROR: ").use { errReader ->
                            val timeoutMillis = parameters.timeoutMillis()
                            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                                throw TimeoutException(
                                    String.format(
                                        "%s timed out after %d seconds (%d millis)",
                                        parameters.name(), timeoutMillis / 1000, timeoutMillis
                                    )
                                )
                            }

                            val err =
                                if (errReader != null)
                                    errReader.get()
                                else
                                    Files.readAllLines(parameters.redirectError().get())
                            val out =
                                if (outReader != null)
                                    outReader.get()
                                else
                                    Files.readAllLines(parameters.redirectOutput().get())

                            val exitValue = process.exitValue()
                            val expectedToFail = parameters.expectedToFail() || parameters.expectedExitCode() != 0
                            if ((exitValue == 0) == expectedToFail) {
                                throw ProcessRunnerException(
                                    String.format(
                                        "Expected to %s, but %s.\nError: %s\nOutput: %s",
                                        if (expectedToFail) "fail" else "succeed",
                                        if (exitValue == 0) "succeeded" else "failed",
                                        StringUtilities.joinLines(err),
                                        StringUtilities.joinLines(out)
                                    )
                                )
                            }
                            // We want to check the exact exit code if it was explicitly set to something;
                            // we already checked the variant when it is equal to zero above.
                            if (parameters.expectedExitCode() != 0 && parameters.expectedExitCode() != exitValue) {
                                throw ProcessRunnerException(
                                    String.format(
                                        "Expected exit code %d, but found %d.\nError: %s\nOutput: %s",
                                        parameters.expectedExitCode(),
                                        exitValue,
                                        StringUtilities.joinLines(err),
                                        StringUtilities.joinLines(out)
                                    )
                                )
                            }

                            if (parameters.expectedEmptyError()) {
                                if (!err.isEmpty()) {
                                    throw ProcessRunnerException(
                                        "Expected empty error stream, but found: " + StringUtilities.joinLines(err)
                                    )
                                }
                            }
                            return ProcessResult.Companion.create(exitValue, out, err)
                        }
                }
        } finally {
            process.destroy()
        }
    }

    private fun createReader(stream: InputStream, prefix: String?): ProcessStreamReader {
        return ProcessStreamReader(
            executorService, stream, Consumer? { s: String? -> logger.atFine().log("%s%s", prefix, s) })
    }

    /** Specific runtime exception for external process errors  */
    class ProcessRunnerException internal constructor(message: String?) : RuntimeException(message)

    private class ProcessStreamReader(
        executorService: ExecutorService,
        private val stream: InputStream,
        logConsumer: Consumer<String?>?
    ) : AutoCloseable {
        private val future: Future<MutableList<String?>>
        private val exception = AtomicReference<IOException?>()

        init {
            future =
                executorService.submit<MutableList<String?>?>(
                    Callable {
                        val lines: MutableList<String?> = Lists.newArrayList<String?>()
                        try {
                            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                                val lineReader = LineReader(reader)
                                var line: String?
                                while ((lineReader.readLine().also { line = it }) != null) {
                                    if (logConsumer != null) {
                                        logConsumer.accept(line)
                                    }
                                    lines.add(line)
                                }
                            }
                        } catch (e: IOException) {
                            exception.set(e)
                        }
                        lines
                    })
        }

        @Throws(InterruptedException::class, ExecutionException::class, TimeoutException::class, IOException::class)
        fun get(): MutableList<String?> {
            try {
                val lines = future.get(15, TimeUnit.SECONDS)
                if (exception.get() != null) {
                    throw exception.get()
                }
                return lines
            } finally {
                // if future is timed out
                stream.close()
            }
        }

        @Throws(Exception::class)
        override fun close() {
            stream.close()
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
