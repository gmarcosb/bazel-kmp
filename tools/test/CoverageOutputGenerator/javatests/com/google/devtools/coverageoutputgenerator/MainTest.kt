// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.coverageoutputgenerator

import com.google.common.truth.Truth
import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.coverageoutputgenerator.Coverage
import com.google.devtools.coverageoutputgenerator.LcovMergerTestUtils
import com.google.devtools.coverageoutputgenerator.LcovParser
import org.junit.Before
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

/** Test for [Main].  */
@RunWith(JUnit4::class)
class MainTest {
    @org.junit.Rule
    var temporaryFolder: TemporaryFolder = TemporaryFolder()

    @org.junit.Rule
    var testName: TestName = TestName()
    private var coverageDir: Path? = null

    @Before
    @Throws(IOException::class)
    fun createCoverageDirectory() {
        coverageDir = temporaryFolder.newFolder("coverage-dir").toPath()
    }

    @org.junit.Test
    fun testMainEmptyCoverageDir() {
        Truth.assertThat(
            com.google.devtools.coverageoutputgenerator.Main.getCoverageFilesInDir(
                coverageDir.toAbsolutePath().toString()
            )
        ).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testMainGetLcovTracefiles() {
        val ccCoverageDir: Path = java.nio.file.Files.createTempDirectory(coverageDir, "cc_coverage")
        val javaCoverageDir: Path = java.nio.file.Files.createTempDirectory(coverageDir, "java_coverage")

        java.nio.file.Files.createTempFile(ccCoverageDir, "tracefile1", ".dat")
        java.nio.file.Files.createTempFile(javaCoverageDir, "tracefile2", ".dat")

        val coverageFiles: MutableList<java.io.File?> =
            com.google.devtools.coverageoutputgenerator.Main.getCoverageFilesInDir(
                coverageDir.toAbsolutePath().toString()
            )
        val tracefiles: MutableList<java.io.File?>? =
            com.google.devtools.coverageoutputgenerator.Main.getFilesWithExtension(
                coverageFiles,
                com.google.devtools.coverageoutputgenerator.Constants.TRACEFILE_EXTENSION
            )
        Truth.assertThat(tracefiles).hasSize(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParallelParse_1KLoC_1KLcovFiles() {
        assertParallelParse(1024, 4, 256)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParallelParse_1MLoC_4LcovFiles() {
        assertParallelParse(4, 1024, 1024)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParallelParse_1MLoC_1LcovFiles() {
        assertParallelParse(1, 1024, 1024)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyInputProducesEmptyOutput() {
        val output: Path =
            Paths.get(
                temporaryFolder.getRoot().getAbsolutePath(),
                testName.getMethodName() + ".coverage.dat"
            )
        val exitCode: Int =
            com.google.devtools.coverageoutputgenerator.Main.runWithArgs(
                "--coverage_dir", coverageDir.toAbsolutePath().toString(),
                "--output_file", output.toAbsolutePath().toString()
            )
        Truth.assertThat(exitCode).isEqualTo(0)
        Truth.assertThat(output.toFile().exists()).isTrue()
        Truth.assertThat(output.toFile().length()).isEqualTo(0L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonEmptyInputProducesNonEmptyOutput() {
        LcovMergerTestUtils.generateLcovFiles("test_data/simple_test", 8, 8, 8, coverageDir)
        val output: Path =
            Paths.get(
                temporaryFolder.getRoot().getAbsolutePath(),
                testName.getMethodName() + ".coverage.dat"
            )
        val exitCode: Int =
            com.google.devtools.coverageoutputgenerator.Main.runWithArgs(
                "--coverage_dir", coverageDir.toAbsolutePath().toString(),
                "--output_file", output.toAbsolutePath().toString()
            )
        Truth.assertThat(exitCode).isEqualTo(0)
        Truth.assertThat(output.toFile().exists()).isTrue()
        Truth.assertThat(output.toFile().length()).isGreaterThan(0L)
    }

    @Throws(java.lang.Exception::class)
    private fun assertParallelParse(numLcovFiles: Int, numSourceFiles: Int, numLinesPerSourceFile: Int) {
        val sequentialOutput: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val parallelOutput: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()

        LcovMergerTestUtils.generateLcovFiles(
            "test_data/simple_test", numLcovFiles, numSourceFiles, numLinesPerSourceFile, coverageDir
        )

        val coverageFiles: MutableList<java.io.File?> =
            com.google.devtools.coverageoutputgenerator.Main.getCoverageFilesInDir(
                coverageDir.toAbsolutePath().toString()
            )

        val sequentialCoverage: Coverage = com.google.devtools.coverageoutputgenerator.Main.parseFilesSequentially(
            coverageFiles,
            com.google.devtools.coverageoutputgenerator.Parser { inputStream: java.io.InputStream? ->
                LcovParser.parse(
                    inputStream
                )
            })
        print(sequentialOutput, sequentialCoverage)

        val parallelCoverage: Coverage? =
            com.google.devtools.coverageoutputgenerator.Main.parseFilesInParallel(
                coverageFiles,
                com.google.devtools.coverageoutputgenerator.Parser { inputStream: java.io.InputStream? ->
                    LcovParser.parse(inputStream)
                },
                TEST_PARSE_PARALLELISM
            )
        print(parallelOutput, parallelCoverage)

        Truth.assertThat(parallelOutput.toString()).isEqualTo(sequentialOutput.toString())
    }

    companion object {
        private const val TEST_PARSE_PARALLELISM = 4
    }
}
