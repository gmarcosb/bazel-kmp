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
import com.google.devtools.coverageoutputgenerator.Coverage
import com.google.devtools.coverageoutputgenerator.LcovPrinter
import com.google.devtools.coverageoutputgenerator.SourceFileCoverage
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Unit tests for [LcovPrinter].  */
@RunWith(JUnit4::class)
class LcovPrinterTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrintTwoFiles() {
        val coverage: Coverage = Coverage()
        val sourceFileCoverage1: SourceFileCoverage = SourceFileCoverage("src1.foo")
        val sourceFileCoverage2: SourceFileCoverage = SourceFileCoverage("src2.foo")
        sourceFileCoverage1.addFunctionLineNumber("foo", 2)
        sourceFileCoverage1.addFunctionLineNumber("bar", 4)
        sourceFileCoverage1.addFunctionExecution("foo", 3L)
        sourceFileCoverage1.addFunctionExecution("bar", 0L)
        sourceFileCoverage1.addLine(2, 3)
        sourceFileCoverage1.addLine(4, 0)
        sourceFileCoverage2.addFunctionLineNumber("foo", 3)
        sourceFileCoverage2.addFunctionExecution("foo", 1L)
        sourceFileCoverage2.addLine(3, 1)
        sourceFileCoverage2.addLine(4, 1)
        coverage.add(sourceFileCoverage1)
        coverage.add(sourceFileCoverage2)

        val byteOutputStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        print(byteOutputStream, coverage)
        byteOutputStream.close()
        val fileLines: Iterable<String?> = com.google.common.base.Splitter.on('\n')
            .split(byteOutputStream.toString(java.nio.charset.StandardCharsets.UTF_8).strip())

        Truth.assertThat(fileLines)
            .containsExactly(
                "SF:src1.foo",
                "FN:4,bar",
                "FN:2,foo",
                "FNDA:0,bar",
                "FNDA:3,foo",
                "FNF:2",
                "FNH:1",
                "DA:2,3",
                "DA:4,0",
                "LH:1",
                "LF:2",
                "end_of_record",
                "SF:src2.foo",
                "FN:3,foo",
                "FNDA:1,foo",
                "FNF:1",
                "FNH:1",
                "DA:3,1",
                "DA:4,1",
                "LH:2",
                "LF:2",
                "end_of_record"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrintOneFile() {
        val coverage: Coverage = Coverage()
        val sourceFileCoverage1: SourceFileCoverage = SourceFileCoverage("src1.foo")
        sourceFileCoverage1.addFunctionLineNumber("foo", 2)
        sourceFileCoverage1.addFunctionLineNumber("bar", 4)
        sourceFileCoverage1.addFunctionExecution("foo", 3L)
        sourceFileCoverage1.addFunctionExecution("bar", 0L)
        sourceFileCoverage1.addLine(2, 3)
        sourceFileCoverage1.addLine(4, 0)
        coverage.add(sourceFileCoverage1)

        val byteOutputStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        print(byteOutputStream, coverage)
        byteOutputStream.close()
        val fileLines: Iterable<String?> = com.google.common.base.Splitter.on('\n')
            .split(byteOutputStream.toString(java.nio.charset.StandardCharsets.UTF_8).strip())

        Truth.assertThat(fileLines)
            .containsExactly(
                "SF:src1.foo",
                "FN:4,bar",
                "FN:2,foo",
                "FNDA:0,bar",
                "FNDA:3,foo",
                "FNF:2",
                "FNH:1",
                "DA:2,3",
                "DA:4,0",
                "LH:1",
                "LF:2",
                "end_of_record"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrintBrdaLines() {
        val sourceFile: SourceFileCoverage = SourceFileCoverage("foo")
        sourceFile.addBranch(3, "0", "0", true, 1)
        sourceFile.addBranch(3, "0", "1", true, 0)
        sourceFile.addBranch(7, "0", "0", false, 0)
        sourceFile.addBranch(7, "0", "1", false, 0)
        val coverage: Coverage = Coverage()
        coverage.add(sourceFile)

        val byteOutputStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        print(byteOutputStream, coverage)
        val fileLines: Iterable<String?> = com.google.common.base.Splitter.on('\n')
            .split(byteOutputStream.toString(java.nio.charset.StandardCharsets.UTF_8))

        Truth.assertThat(fileLines)
            .containsExactly(
                "SF:foo",
                "FNF:0",
                "FNH:0",
                "BRDA:3,0,0,1",
                "BRDA:3,0,1,0",
                "BRDA:7,0,0,-",
                "BRDA:7,0,1,-",
                "BRF:4",
                "BRH:1",
                "LH:0",
                "LF:0",
                "end_of_record",
                ""
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrintBaLines() {
        val coverage: Coverage = Coverage()
        val sourceFile: SourceFileCoverage = SourceFileCoverage("foo")
        sourceFile.addBranch(3, "0", "0", true, 1)
        sourceFile.addBranch(3, "0", "1", true, 0)
        sourceFile.addBranch(7, "0", "0", false, 0)
        sourceFile.addBranch(7, "0", "1", false, 0)
        coverage.add(sourceFile)

        val byteOutputStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        LcovPrinter.Companion.print(byteOutputStream, coverage, true)
        val fileLines: Iterable<String?> = com.google.common.base.Splitter.on('\n')
            .split(byteOutputStream.toString(java.nio.charset.StandardCharsets.UTF_8))

        Truth.assertThat(fileLines)
            .containsExactly(
                "SF:foo",
                "FNF:0",
                "FNH:0",
                "BA:3,2",
                "BA:3,1",
                "BA:7,0",
                "BA:7,0",
                "BRF:4",
                "BRH:1",
                "LH:0",
                "LF:0",
                "end_of_record",
                ""
            )
    }
}
