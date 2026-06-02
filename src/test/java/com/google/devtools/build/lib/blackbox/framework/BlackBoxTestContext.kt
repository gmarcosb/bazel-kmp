// Copyright 2019 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.blackbox.framework

import com.google.common.collect.Maps
import com.google.common.truth.Truth
import com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorDescriptor.Builder.build
import com.google.devtools.build.lib.util.OS
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Holds JUnit integration tests context, passed to tests from base class.
 * 
 * 
 * Provides access to source and work directories, generated and binary files, allows to run
 * built binary, creates [BuilderRunner] for running Bazel commands
 */
class BlackBoxTestContext(
    testName: String?,
    productName: String?,
    binaryPath: Path,
    commonEnv: MutableMap<String?, String?>,
    executorService: ExecutorService?
) {
    /** Returns the working directory of the test  */
    val workDir: Path

    /** Returns the source directory (TEST_SRCDIR) of the test.  */
    val srcDir: Path

    /** Returns the temp directory (TEST_TMPDIR) of the test  */
    val tmpDir: Path
    private val commonEnv: MutableMap<String?, String?>
    private val executorService: ExecutorService?

    /** Returns the product name: bazel  */
    val productName: String?
    private val binaryPath: Path
    private var genFilesPath: Path? = null
    private val binFilesPaths: Path? = null

    init {
        Truth.assertThat(Files.exists(binaryPath)).isTrue()

        this.commonEnv = Maps.newHashMap<String?, String?>(commonEnv)
        this.productName = productName
        this.binaryPath = binaryPath
        srcDir = getPathFromEnv("TEST_SRCDIR")
        tmpDir = getPathFromEnv("TEST_TMPDIR")

        workDir = tmpDir.resolve(testName)
        Files.createDirectories(workDir)

        this.executorService = executorService
    }

    /**
     * Writes `lines` using ISO_8859_1 into the file, specified by the `subPath`
     * relative to the working directory. Overrides the file if it exists, creates the file if it does
     * not exist.
     * 
     * @param subPath path to file relative to working directory
     * @param lines lines of text to write. Newlines are added by the method.
     * @return Path to the file
     * @throws IOException in case if the file can not be created/overridden, or can not be open for
     * writing
     */
    @Throws(IOException::class)
    fun write(subPath: String?, vararg lines: String?): Path? {
        return PathUtils.writeFileInDir(workDir, subPath, *lines)
    }

    /**
     * Writes `lines` using ISO_8859_1 into the file, specified by the `subPath`
     * relative to the working directory. Overrides the file if it exists, creates the file if it does
     * not exist.
     * 
     * @param subPath path to file relative to working directory
     * @param lines lines of text to write. Newlines are added by the method.
     * @return Path to the file
     * @throws IOException in case if the file can not be created/overridden, or can not be open for
     * writing
     */
    @Throws(IOException::class)
    fun write(subPath: String?, lines: MutableList<String?>?): Path? {
        return PathUtils.writeFileInDir(workDir, subPath, lines)
    }

    /**
     * Writes `lines` using ISO_8859_1 into the BUILD file in the directory, specified by
     * the `subPath` relative to the working directory. Overrides the file if it exists,
     * creates the file if it does not exist.
     * 
     * @param subPathToDir path to directory relative to working directory, where BUILD file should be
     * written
     * @param lines lines of text to write. Newlines are added by the method.
     * @return Path to the file
     * @throws IOException in case if the file can not be created/overridden, or can not be open for
     * writing
     */
    @Throws(IOException::class)
    fun writeBuild(subPathToDir: String, vararg lines: String?): Path? {
        val separator = (if (subPathToDir.endsWith(File.separator)) "" else File.separator)
        return write(subPathToDir + separator + "BUILD", *lines)
    }

    /**
     * Reads the lines of the file, specified by the `subPath` relative to the working
     * directory, in ISO_8859_1.
     * 
     * @param subPath path to the file relative to the working directory
     * @return list of file lines (without the newline characters)
     * @throws IOException if the file does not exist or can not be read
     */
    @Throws(IOException::class)
    fun read(subPath: String?): MutableList<String?> {
        return PathUtils.readFile(workDir, subPath)
    }

    /**
     * Resolve a path relative to "bazel-genfiles".
     * 
     * 
     * Calls `bazel info bazel-genfiles`, caches the result.
     * 
     * @param bazel the instance of BuilderRunner to run info with
     * @param subPathUnderGen path to the file under bazel-gen directory
     * @return full path to the resolved file
     * @throws Exception if `bazel info` command fails
     */
    @Throws(Exception::class)
    fun resolveGenPath(bazel: BuilderRunner, subPathUnderGen: String): Path {
        if (genFilesPath == null) {
            genFilesPath = PathUtils.resolve(workDir, getInfoValue(bazel, productName + "-genfiles"))
        }
        return PathUtils.resolve(genFilesPath, subPathUnderGen)
    }

    /**
     * Resolve a path relative to "bazel-bin".
     * 
     * 
     * Calls `bazel info bazel-bin`
     * 
     * @param bazel the instance of BuilderRunner to run info with
     * @param subPathUnderBin path to the file under bazel-bin directory
     * @return full path to the resolved file
     * @throws Exception if `bazel info` command fails
     */
    @Throws(Exception::class)
    fun resolveBinPath(bazel: BuilderRunner, subPathUnderBin: String): Path {
        val binPath = PathUtils.resolve(workDir, getInfoValue(bazel, productName + "-bin"))
        return PathUtils.resolve(binPath, subPathUnderBin)
    }

    /**
     * Resolve a path relative to "execution_root". Useful for checking the contents of the generated
     * external repositories.
     * 
     * 
     * Calls `bazel info execution_root`
     * 
     * @param bazel the instance of BuilderRunner to run info with
     * @param subPathUnderBin path to the file under execution_root directory
     * @return full path to the resolved file
     * @throws Exception if `bazel info` command fails
     */
    @Throws(Exception::class)
    fun resolveExecRootPath(bazel: BuilderRunner, subPathUnderBin: String): Path {
        val binPath = PathUtils.resolve(workDir, getInfoValue(bazel, "execution_root"))
        return PathUtils.resolve(binPath, subPathUnderBin)
    }

    @Throws(Exception::class)
    private fun getInfoValue(bazel: BuilderRunner, key: String?): String? {
        val parts: Array<String?> =
            bazel.info(key).outString().trim { it <= ' ' }.split(" ".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()
        return parts[parts.size - 1]
    }

    /**
     * Runs the built executable. Calls `bazel info` to get the information about bazel-bin
     * directory location.
     * 
     * @param bazel the instance of BuilderRunner to run info with
     * @param subPathUnderBin path to the executable relative to bazel-bin directory
     * @param timeoutMillis timeout on the process execution
     * @return ProcessResult result of the execution with the process exit code and strings with
     * stdout and stderr contents.
     * @throws Exception if `bazel info` command fails or executable invocation fails.
     */
    @Throws(Exception::class)
    fun runBuiltBinary(
        bazel: BuilderRunner, subPathUnderBin: String, timeoutMillis: Long
    ): ProcessResult {
        var subPathUnderBin = subPathUnderBin
        if (OS.WINDOWS == OS.getCurrent() && !subPathUnderBin.endsWith(".exe")) {
            subPathUnderBin += ".exe"
        }
        val executable = resolveBinPath(bazel, subPathUnderBin)
        Truth.assertThat(Files.exists(executable)).isTrue()
        Truth.assertThat(Files.isRegularFile(executable)).isTrue()
        Truth.assertThat(Files.isExecutable(executable)).isTrue()

        val parameters: ProcessParameters? =
            ProcessParameters.Companion.builder()
                .setWorkingDirectory(workDir.toFile())
                .setName(executable.toString())
                .setTimeoutMillis(getProcessTimeoutMillis(timeoutMillis))
                .build()
        return ProcessRunner(parameters, executorService).runSynchronously()
    }

    /**
     * Creates the instance of BuilderRunner for running Bazel commands in the working directory. see
     * [BuilderRunner]
     * 
     * @return BuilderRunner interface for running Bazel commands.
     */
    fun bazel(): BuilderRunner {
        return BuilderRunner(
            workDir, binaryPath, getProcessTimeoutMillis(-1), commonEnv, executorService
        )
    }

    /**
     * Runs external binary in the specified working directory. See [BuilderRunner]
     * 
     * @param workingDirectory working directory for running the binary
     * @param processToRun path to the binary to run
     * @param expectEmptyError if `true`, no text is expected in the error stream,
     * otherwise, ProcessRunnerException is thrown.
     * @param arguments arguments to pass to the binary
     * @return ProcessResult execution result
     */
    @Throws(Exception::class)
    fun runBinary(
        workingDirectory: Path, processToRun: String?, expectEmptyError: Boolean, vararg arguments: String?
    ): ProcessResult {
        val parameters: ProcessParameters? =
            ProcessParameters.Companion.builder()
                .setWorkingDirectory(workingDirectory.toFile())
                .setName(processToRun)
                .setTimeoutMillis(getProcessTimeoutMillis(-1))
                .setArguments(*arguments)
                .setExpectedEmptyError(expectEmptyError)
                .build()
        return ProcessRunner(parameters, executorService).runSynchronously()
    }

    companion object {
        /**
         * Take the value from environment variable and assert that it is a path, and the file or
         * directory, specified by this path, exists.
         * 
         * @param name name of the environment variable
         * @return Path to the file where the value of environment variable points
         */
        private fun getPathFromEnv(name: String?): Path {
            val pathStr = System.getenv(name)
            Truth.assertThat(pathStr).isNotNull()
            val path = Paths.get(pathStr)
            Truth.assertThat(Files.exists(path)).isTrue()
            return path.toAbsolutePath()
        }

        /**
         * Define the value of the timeout for the Bazel process invoked for the test. Use the value,
         * specified by the user, or default test timeout value.
         * 
         * @param timeoutMillis value for the timeout, specified by the user. If the user has not
         * specified the value, -1 is passed.
         * @return timeout value in milliseconds
         */
        private fun getProcessTimeoutMillis(timeoutMillis: Long): Long {
            if (timeoutMillis > 0) {
                return timeoutMillis
            }
            return testTimeoutMillis
        }

        private val testTimeoutMillis: Long
            /**
             * Determine the timeout of the blackbox test, use information from the environment variable.
             * 
             * @return timeout value in milliseconds
             */
            get() {
                val timeout = System.getenv("TEST_TIMEOUT")
                if (timeout != null) {
                    try {
                        return TimeUnit.SECONDS.toMillis(timeout.toInt())
                    } catch (e: NumberFormatException) {
                        println("Invalid test timeout value, using default.")
                    }
                }
                return TimeUnit.SECONDS.toMillis(900)
            }
    }
}
