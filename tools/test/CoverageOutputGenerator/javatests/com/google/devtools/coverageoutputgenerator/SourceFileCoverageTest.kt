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
import com.google.devtools.coverageoutputgenerator.SourceFileCoverage
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Unit tests for [SourceFileCoverage].  */
@RunWith(JUnit4::class)
class SourceFileCoverageTest {
    @org.junit.Test
    fun testCopyConstructor() {
        val sourceFile: SourceFileCoverage = SourceFileCoverage("src.foo")
        sourceFile.addFunctionLineNumber("foo", 3)
        sourceFile.addLine(3, 2)
        sourceFile.addLine(4, 1)
        sourceFile.addLine(5, 0)
        sourceFile.addBranch(3, "0", "0", true, 2)
        sourceFile.addBranch(3, "0", "1", true, 0)
        sourceFile.addBranch(5, "7", "0", false, 0)
        sourceFile.addBranch(5, "7", "1", false, 0)

        val copy: SourceFileCoverage = SourceFileCoverage(sourceFile)

        Truth.assertThat(copy.getLines()).isEqualTo(sourceFile.getLines())
        Truth.assertThat(copy.getFunctionLineNumbers()).isEqualTo(sourceFile.getFunctionLineNumbers())
        Truth.assertThat(copy.getAllBranches())
            .containsExactly(
                BranchCoverageItem.Companion.create(3, "0", "0", true, 2),
                BranchCoverageItem.Companion.create(3, "0", "1", true, 0),
                BranchCoverageItem.Companion.create(5, "7", "0", false, 0),
                BranchCoverageItem.Companion.create(5, "7", "1", false, 0)
            )
    }

    @org.junit.Test
    fun testMergeFunctionNameToLineNumber() {
        val sourceFile1: SourceFileCoverage = SourceFileCoverage("src.foo")
        val sourceFile2: SourceFileCoverage = SourceFileCoverage("src.foo")
        sourceFile1.addFunctionLineNumber("foo", 3)
        sourceFile1.addFunctionLineNumber("bar", 10)
        sourceFile2.addFunctionLineNumber("foo", 3)
        sourceFile2.addFunctionLineNumber("bar", 10)

        val merged: SourceFileCoverage = SourceFileCoverage.Companion.merge(sourceFile1, sourceFile2)

        Truth.assertThat(merged.getFunctionLineNumbers()).containsExactly("foo", 3, "bar", 10)
    }

    @org.junit.Test
    fun testMergeFunctionNameToExecutionCount() {
        val sourceFile1: SourceFileCoverage = SourceFileCoverage("src.foo")
        val sourceFile2: SourceFileCoverage = SourceFileCoverage("src.foo")
        sourceFile1.addFunctionLineNumber("foo", 3)
        sourceFile1.addFunctionExecution("foo", 5L)
        sourceFile2.addFunctionLineNumber("foo", 3)
        sourceFile2.addFunctionExecution("foo", 7L)

        val merged: SourceFileCoverage = SourceFileCoverage.Companion.merge(sourceFile1, sourceFile2)

        Truth.assertThat(merged.getFunctionsExecution()).containsExactly("foo", 12L)
    }

    @org.junit.Test
    fun testMergeLineNumberToLineExecution() {
        val sourceFile1: SourceFileCoverage = SourceFileCoverage("src.foo")
        val sourceFile2: SourceFileCoverage = SourceFileCoverage("src.foo")
        sourceFile1.addLine(4, 3)
        sourceFile1.addLine(5, 4)
        sourceFile1.addLine(10, 0)
        sourceFile2.addLine(4, 5)
        sourceFile2.addLine(5, 0)
        sourceFile2.addLine(10, 3)

        val merged: SourceFileCoverage = SourceFileCoverage.Companion.merge(sourceFile1, sourceFile2)

        Truth.assertThat(merged.getLines())
            .containsExactly(
                4, 8L,
                5, 4L,
                10, 3L
            )
    }

    @org.junit.Test
    fun testMergeBranches() {
        val sourceFile1: SourceFileCoverage = SourceFileCoverage("src.foo")
        val sourceFile2: SourceFileCoverage = SourceFileCoverage("src.foo")
        sourceFile1.addBranch(1, "0", "0", true, 1)
        sourceFile1.addBranch(1, "0", "1", true, 0)
        sourceFile1.addBranch(1, "0", "2", true, 0)
        sourceFile1.addBranch(1, "0", "0", true, 0)
        sourceFile1.addBranch(1, "1", "0", true, 3)
        sourceFile1.addBranch(1, "1", "1", true, 4)
        sourceFile2.addBranch(1, "0", "1", true, 0)
        sourceFile2.addBranch(1, "0", "2", true, 1)
        sourceFile2.addBranch(1, "1", "0", true, 7)
        sourceFile2.addBranch(1, "1", "1", true, 8)

        val merged: SourceFileCoverage = SourceFileCoverage.Companion.merge(sourceFile1, sourceFile2)

        Truth.assertThat(merged.getAllBranches())
            .containsExactly(
                BranchCoverageItem.Companion.create(1, "0", "0", true, 1),
                BranchCoverageItem.Companion.create(1, "0", "1", true, 0),
                BranchCoverageItem.Companion.create(1, "0", "2", true, 1),
                BranchCoverageItem.Companion.create(1, "1", "0", true, 10),
                BranchCoverageItem.Companion.create(1, "1", "1", true, 12)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMismatchedBranchMerge() {
        val sourceFile1: SourceFileCoverage = SourceFileCoverage("source")
        val sourceFile2: SourceFileCoverage = SourceFileCoverage("source")
        sourceFile1.addBranch(800, "0", "0", true, 1)
        sourceFile1.addBranch(800, "0", "1", true, 0)
        sourceFile1.addBranch(800, "1", "0", true, 1)
        sourceFile1.addBranch(900, "0", "0", true, 1)
        sourceFile1.addBranch(900, "0", "1", true, 0)
        sourceFile2.addBranch(800, "1", "0", true, 3)
        sourceFile2.addBranch(800, "1", "1", true, 4)
        sourceFile2.addBranch(900, "0", "0", false, 0)
        sourceFile2.addBranch(900, "0", "1", false, 0)
        sourceFile2.addBranch(900, "0", "2", false, 0)

        // Check the results are the same no matter the order of the merge.
        val merged1: SourceFileCoverage = SourceFileCoverage.Companion.merge(sourceFile1, sourceFile2)
        val merged2: SourceFileCoverage = SourceFileCoverage.Companion.merge(sourceFile2, sourceFile1)

        Truth.assertThat(merged1.getAllBranches())
            .containsExactly(
                BranchCoverageItem.Companion.create(800, "0", "0", true, 1),
                BranchCoverageItem.Companion.create(800, "0", "1", true, 0),
                BranchCoverageItem.Companion.create(800, "1", "0", true, 4),
                BranchCoverageItem.Companion.create(800, "1", "1", true, 4),
                BranchCoverageItem.Companion.create(900, "0", "0", true, 1),
                BranchCoverageItem.Companion.create(900, "0", "1", true, 0),
                BranchCoverageItem.Companion.create(900, "0", "2", true, 0)
            )
        Truth.assertThat(merged2.getAllBranches())
            .containsExactly(
                BranchCoverageItem.Companion.create(800, "0", "0", true, 1),
                BranchCoverageItem.Companion.create(800, "0", "1", true, 0),
                BranchCoverageItem.Companion.create(800, "1", "0", true, 4),
                BranchCoverageItem.Companion.create(800, "1", "1", true, 4),
                BranchCoverageItem.Companion.create(900, "0", "0", true, 1),
                BranchCoverageItem.Companion.create(900, "0", "1", true, 0),
                BranchCoverageItem.Companion.create(900, "0", "2", true, 0)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDifferentLinesReportedAreMergeable() {
        val sourceFile1: SourceFileCoverage = SourceFileCoverage("source")
        val sourceFile2: SourceFileCoverage = SourceFileCoverage("source")
        sourceFile1.addBranch(1, "0", "0", true, 1)
        sourceFile1.addBranch(1, "0", "1", true, 1)
        sourceFile1.addLine(1, 2)
        sourceFile1.addLine(2, 1)
        sourceFile1.addLine(3, 1)

        sourceFile2.addBranch(30, "0", "0", true, 3)
        sourceFile2.addBranch(30, "0", "1", true, 0)
        sourceFile2.addBranch(30, "0", "2", true, 1)
        sourceFile2.addLine(30, 4)
        sourceFile2.addLine(31, 3)
        sourceFile2.addLine(32, 0)
        sourceFile2.addLine(33, 1)

        val merged: SourceFileCoverage = SourceFileCoverage.Companion.merge(sourceFile1, sourceFile2)
        Truth.assertThat(merged.getAllBranches())
            .containsExactly(
                BranchCoverageItem.Companion.create(1, "0", "0", true, 1),
                BranchCoverageItem.Companion.create(1, "0", "1", true, 1),
                BranchCoverageItem.Companion.create(30, "0", "0", true, 3),
                BranchCoverageItem.Companion.create(30, "0", "1", true, 0),
                BranchCoverageItem.Companion.create(30, "0", "2", true, 1)
            )
        Truth.assertThat(merged.getLines())
            .containsExactly(1, 2L, 2, 1L, 3, 1L, 30, 4L, 31, 3L, 32, 0L, 33, 1L)
    }

    @org.junit.Test
    fun testRepeatedBranchesAreMerged() {
        val sourceFile: SourceFileCoverage = SourceFileCoverage("source")
        sourceFile.addBranch(1, "0", "0", false, 0)
        sourceFile.addBranch(1, "0", "1", false, 0)
        sourceFile.addBranch(1, "0", "0", true, 1)
        sourceFile.addBranch(1, "0", "1", true, 1)
        sourceFile.addBranch(1, "0", "0", true, 2)
        sourceFile.addBranch(1, "0", "1", true, 2)

        Truth.assertThat(sourceFile.getAllBranches())
            .containsExactly(
                BranchCoverageItem.Companion.create(1, "0", "0", true, 3),
                BranchCoverageItem.Companion.create(1, "0", "1", true, 3)
            )
    }
}
