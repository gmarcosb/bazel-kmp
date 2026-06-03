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
import com.google.devtools.coverageoutputgenerator.GcovParser
import com.google.devtools.coverageoutputgenerator.SourceFileCoverage
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayInputStream
import java.io.IOException

/** Unit tests for [GcovParser].  */
@RunWith(JUnit4::class)
class GcovParserTest {
    @org.junit.Test
    @Throws(IOException::class)
    fun testParseInvalidFile() {
        Truth.assertThat(GcovParser.Companion.parse(ByteArrayInputStream("Invalid gcov file".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testParseTracefileWithOneSourcefile() {
        val sourceFiles: MutableList<SourceFileCoverage?>? =
            GcovParser.Companion.parse(
                ByteArrayInputStream(
                    com.google.common.base.Joiner.on("\n").join(GCOV_INFO_FILE)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
            )
        Truth.assertThat(sourceFiles).hasSize(1)
        assertGcovInfoFile(sourceFiles!!.get(0))
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testParseTracefilWithDifferentFormat() {
        val sourceFiles: MutableList<SourceFileCoverage?>? =
            GcovParser.Companion.parse(
                ByteArrayInputStream(
                    com.google.common.base.Joiner.on("\n").join(GCOV_INFO_FILE2)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
            )
        Truth.assertThat(sourceFiles).hasSize(1)
        assertGcovInfoFile(sourceFiles!!.get(0))
    }

    private fun assertGcovInfoFile(sourceFileCoverage: SourceFileCoverage) {
        Truth.assertThat(sourceFileCoverage.sourceFileName()).isEqualTo("tmp.cpp")

        Truth.assertThat(sourceFileCoverage.nrFunctionsFound()).isEqualTo(5)
        Truth.assertThat(sourceFileCoverage.nrFunctionsHit()).isEqualTo(3)
        Truth.assertThat(sourceFileCoverage.nrOfInstrumentedLines()).isEqualTo(14)
        Truth.assertThat(sourceFileCoverage.nrOfLinesWithNonZeroExecution()).isEqualTo(13)
        Truth.assertThat(sourceFileCoverage.nrBranchesFound()).isEqualTo(16)
        Truth.assertThat(sourceFileCoverage.nrBranchesHit()).isEqualTo(8)

        Truth.assertThat(sourceFileCoverage.getLines())
            .containsExactly(
                7, 1L, 8, 2L, 18, 1L, 21, 1L, 23, 1L, 24, 1L, 25, 1L, 27, 11L, 28, 10L, 30, 1L, 32, 1L,
                33, 0L, 35, 1L, 36, 1L
            )

        Truth.assertThat(sourceFileCoverage.getAllBranches())
            .containsExactly(
                BranchCoverageItem.Companion.create(21, "0", "0", true, 1),
                BranchCoverageItem.Companion.create(21, "0", "1", true, 0),
                BranchCoverageItem.Companion.create(23, "0", "0", true, 1),
                BranchCoverageItem.Companion.create(23, "0", "1", true, 0),
                BranchCoverageItem.Companion.create(24, "0", "0", true, 1),
                BranchCoverageItem.Companion.create(24, "0", "1", true, 0),
                BranchCoverageItem.Companion.create(27, "0", "0", true, 1),
                BranchCoverageItem.Companion.create(27, "0", "1", true, 1),
                BranchCoverageItem.Companion.create(30, "0", "0", true, 0),
                BranchCoverageItem.Companion.create(30, "0", "1", true, 1),
                BranchCoverageItem.Companion.create(32, "0", "0", true, 0),
                BranchCoverageItem.Companion.create(32, "0", "1", true, 1),
                BranchCoverageItem.Companion.create(33, "0", "0", false, 0),
                BranchCoverageItem.Companion.create(33, "0", "1", false, 0),
                BranchCoverageItem.Companion.create(35, "0", "0", true, 1),
                BranchCoverageItem.Companion.create(35, "0", "1", true, 0)
            )
    }

    companion object {
        private val GCOV_INFO_FILE: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "version: 8.1.0 20180103",
                "cwd:/home/gcc/testcase",
                "file:tmp.cpp",
                "function:7,7,0,_ZN3FooIcEC2Ev",
                "function:7,7,1,_ZN3FooIiEC2Ev",
                "function:8,8,0,_ZN3FooIcE3incEv",
                "function:8,8,2,_ZN3FooIiE3incEv",
                "function:18,37,1,main",
                "lcount:7,0,1",
                "lcount:7,1,0",
                "lcount:8,0,1",
                "lcount:8,2,0",
                "lcount:18,1,0",
                "lcount:21,1,0",
                "branch:21,taken",
                "branch:21,nottaken",
                "lcount:23,1,0",
                "branch:23,taken",
                "branch:23,nottaken",
                "lcount:24,1,0",
                "branch:24,taken",
                "branch:24,nottaken",
                "lcount:25,1,0",
                "lcount:27,11,0",
                "branch:27,taken",
                "branch:27,taken",
                "lcount:28,10,0",
                "lcount:30,1,1",
                "branch:30,nottaken",
                "branch:30,taken",
                "lcount:32,1,0",
                "branch:32,nottaken",
                "branch:32,taken",
                "lcount:33,0,1",
                "branch:33,notexec",
                "branch:33,notexec",
                "lcount:35,1,0",
                "branch:35,taken",
                "branch:35,nottaken",
                "lcount:36,1,0"
            )

        private val GCOV_INFO_FILE2: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "file:tmp.cpp",
                "function:7,0,_ZN3FooIcEC2Ev",
                "function:7,1,_ZN3FooIiEC2Ev",
                "function:8,0,_ZN3FooIcE3incEv",
                "function:8,2,_ZN3FooIiE3incEv",
                "function:18,1,main",
                "lcount:7,0",
                "lcount:7,1",
                "lcount:8,0",
                "lcount:8,2",
                "lcount:18,1",
                "lcount:21,1",
                "branch:21,taken",
                "branch:21,nottaken",
                "lcount:23,1",
                "branch:23,taken",
                "branch:23,nottaken",
                "lcount:24,1",
                "branch:24,taken",
                "branch:24,nottaken",
                "lcount:25,1",
                "lcount:27,11",
                "branch:27,taken",
                "branch:27,taken",
                "lcount:28,10",
                "lcount:30,1",
                "branch:30,nottaken",
                "branch:30,taken",
                "lcount:32,1",
                "branch:32,nottaken",
                "branch:32,taken",
                "lcount:33,0",
                "branch:33,notexec",
                "branch:33,notexec",
                "lcount:35,1",
                "branch:35,taken",
                "branch:35,nottaken",
                "lcount:36,1"
            )
    }
}
