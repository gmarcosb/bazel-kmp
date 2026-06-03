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
import com.google.devtools.coverageoutputgenerator.BranchCoverageItem
import com.google.devtools.coverageoutputgenerator.LcovParser
import com.google.devtools.coverageoutputgenerator.SourceFileCoverage
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.stream.Collectors

/** Unit tests for [LcovParser].  */
@RunWith(JUnit4::class)
class LcovParserTest {
    @org.junit.Test
    @Throws(IOException::class)
    fun testParseInvalidTracefile() {
        val sourceFiles: MutableList<SourceFileCoverage> =
            LcovParser.Companion.parse(ByteArrayInputStream("Invalid lcov tracefile".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        Truth.assertThat(sourceFiles).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testParseTracefile() {
        val lcovLines: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
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

        val sourceFiles: MutableList<SourceFileCoverage> =
            LcovParser.Companion.parse(
                ByteArrayInputStream(
                    com.google.common.base.Joiner.on("\n").join(lcovLines)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
            )

        Truth.assertThat(sourceFiles).hasSize(2)
        Truth.assertThat(sourceFiles.get(0).sourceFileName()).isEqualTo("src1.foo")
        Truth.assertThat(sourceFiles.get(1).sourceFileName()).isEqualTo("src2.foo")
        Truth.assertThat(sourceFiles.get(0).getLines())
            .containsExactly(
                2, 3L,
                4, 0L
            )
        Truth.assertThat(sourceFiles.get(1).getLines()).containsExactly(3, 1L, 4, 1L)
        Truth.assertThat(sourceFiles.get(0).getFunctionLineNumbers()).containsExactly("bar", 4, "foo", 2)
        Truth.assertThat(sourceFiles.get(1).getFunctionLineNumbers()).containsExactly("foo", 3)
        Truth.assertThat(sourceFiles.get(0).getFunctionsExecution()).containsExactly("bar", 0L, "foo", 3L)
        Truth.assertThat(sourceFiles.get(1).getFunctionsExecution()).containsExactly("foo", 1L)
        Truth.assertThat(sourceFiles.get(0).getAllBranches()).isEmpty()
        Truth.assertThat(sourceFiles.get(1).getAllBranches()).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testParseTracefileWithLargeCounts() {
        val tracefile: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "SF:SOURCE_FILENAME",
                "FN:4,file1-func1",
                "FNDA:1000000000000,file1-func1",
                "FNF:1",
                "FNH:1",
                "DA:4,1000000000000",
                "DA:5,1000000000000",
                "LH:2",
                "LF:2",
                "end_of_record"
            )

        val sourceFiles: MutableList<SourceFileCoverage> =
            LcovParser.Companion.parse(
                ByteArrayInputStream(
                    com.google.common.base.Joiner.on("\n").join(tracefile)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
            )
        val sourceFile: SourceFileCoverage = sourceFiles.get(0)

        val functions: MutableMap<String?, Long?>? = sourceFile.getFunctionsExecution()
        Truth.assertThat(functions).containsEntry("file1-func1", 1000000000000L)

        Truth.assertThat(sourceFile.getLines()).containsExactly(4, 1000000000000L, 5, 1000000000000L)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testParseBrdaBranches() {
        val traceFile: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "SF:SOURCE_FILE",
                "FN:2,func",
                "FNDA:1,func",
                "DA:2,1",
                "DA:3,1",
                "DA:4,1",
                "DA:5,1",
                "DA:6,1",
                "BRDA:6,0,0,1",
                "BRDA:6,0,1,0",
                "DA:7,13",
                "BRDA:7,0,0,12",
                "BRDA:7,0,1,1",
                "DA:8,12",
                "DA:10,1",
                "DA:12,0",
                "BRDA:12,0,0,-",
                "BRDA:12,0,1,-",
                "DA:13,0",
                "DA:14.0",
                "DA:16,0",
                "end_of_record"
            )
        val sourceFiles: MutableList<SourceFileCoverage> =
            LcovParser.Companion.parse(
                ByteArrayInputStream(
                    com.google.common.base.Joiner.on("\n").join(traceFile)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
            )
        val sourceFile: SourceFileCoverage = sourceFiles.get(0)

        val branches: MutableList<BranchCoverageItem> =
            sourceFile.getAllBranches().stream().collect(Collectors.toList())
        Truth.assertThat(branches)
            .containsExactly(
                BranchCoverageItem.Companion.create(6, "0", "0", true, 1),
                BranchCoverageItem.Companion.create(6, "0", "1", true, 0),
                BranchCoverageItem.Companion.create(7, "0", "0", true, 12),
                BranchCoverageItem.Companion.create(7, "0", "1", true, 1),
                BranchCoverageItem.Companion.create(12, "0", "0", false, 0),
                BranchCoverageItem.Companion.create(12, "0", "1", false, 0)
            )
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testParseBaBranches() {
        val traceFile: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "SF:SOURCE_FILE",
                "FN:2,func",
                "FNDA:1,func",
                "DA:1,5",
                "BA:2,1",
                "BA:2,2",
                "DA:3,0",
                "BA:4,0",
                "BA:4,0",
                "DA:5,0",
                "DA:6,5",
                "BA:7,2",
                "BA:7,1",
                "BA:7,2",
                "DA:8,1",
                "DA:9,0",
                "DA:10,4",
                "end_of_record"
            )
        val sourceFiles: MutableList<SourceFileCoverage> =
            LcovParser.Companion.parse(
                ByteArrayInputStream(
                    com.google.common.base.Joiner.on("\n").join(traceFile)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
            )
        val sourceFile: SourceFileCoverage = sourceFiles.get(0)

        val branches: MutableList<BranchCoverageItem> =
            sourceFile.getAllBranches().stream().collect(Collectors.toList())
        Truth.assertThat(branches)
            .containsExactly(
                BranchCoverageItem.Companion.create(2, "0", "0", true, 0),
                BranchCoverageItem.Companion.create(2, "0", "1", true, 1),
                BranchCoverageItem.Companion.create(4, "0", "0", false, 0),
                BranchCoverageItem.Companion.create(4, "0", "1", false, 0),
                BranchCoverageItem.Companion.create(7, "0", "0", true, 1),
                BranchCoverageItem.Companion.create(7, "0", "1", true, 0),
                BranchCoverageItem.Companion.create(7, "0", "2", true, 1)
            )
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testParseFnWithEnd() {
        val traceFile: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("SF:SOURCE_FILE", "FN:2,3,func", "end_of_record")
        val sourceFiles: MutableList<SourceFileCoverage> =
            LcovParser.Companion.parse(
                ByteArrayInputStream(
                    com.google.common.base.Joiner.on("\n").join(traceFile)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
            )
        val sourceFile: SourceFileCoverage = sourceFiles.get(0)

        Truth.assertThat(sourceFile.getAllFunctionLineNumbers())
            .containsExactly(java.util.Map.entry<String?, Int?>("func", 2))
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testParseLineWithHash() {
        val traceFile: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("SF:src.foo", "DA:1,1,hash", "end_of_record")

        val sourceFiles: MutableList<SourceFileCoverage> =
            LcovParser.Companion.parse(
                ByteArrayInputStream(
                    com.google.common.base.Joiner.on("\n").join(traceFile)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
            )

        Truth.assertThat(sourceFiles.get(0).getLines()).containsExactly(1, 1L)
    }
}
