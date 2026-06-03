// Copyright 2016 The Bazel Authors. All rights reserved.
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
import com.google.devtools.coverageoutputgenerator.BranchCoverageItem
import com.google.devtools.coverageoutputgenerator.Coverage
import com.google.devtools.coverageoutputgenerator.SourceFileCoverage
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.HashSet

/** Test for LcovMerger.  */
@RunWith(JUnit4::class)
class CoverageTest {
    private var coverage: Coverage? = null

    @Before
    fun initializeCoverage() {
        coverage = Coverage()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOneTracefile() {
        val sourceFileCoverage: SourceFileCoverage = SourceFileCoverage("src.foo")
        sourceFileCoverage.addLine(1, 1)
        sourceFileCoverage.addLine(2, 1)

        coverage.add(sourceFileCoverage)

        Truth.assertThat(coverage.getAllSourceFiles()).hasSize(1)
        Truth.assertThat(
            com.google.common.collect.Iterables.get<SourceFileCoverage?>(coverage.getAllSourceFiles(), 0).getLines()
        )
            .containsExactly(1, 1L, 2, 1L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOverlappingTracefilesMerge() {
        val sourceFileCoverage1: SourceFileCoverage = SourceFileCoverage("src.foo")
        val sourceFileCoverage2: SourceFileCoverage = SourceFileCoverage("src.foo")
        sourceFileCoverage1.addLine(1, 2)
        sourceFileCoverage1.addLine(2, 1)
        sourceFileCoverage1.addLine(3, 2)
        sourceFileCoverage1.addBranch(1, "", "0", true, 2)
        sourceFileCoverage1.addBranch(1, "", "1", true, 1)
        sourceFileCoverage2.addLine(1, 3)
        sourceFileCoverage2.addLine(2, 3)
        sourceFileCoverage2.addLine(3, 0)
        sourceFileCoverage2.addBranch(1, "", "0", true, 1)
        sourceFileCoverage2.addBranch(1, "", "1", true, 2)

        coverage.add(sourceFileCoverage1)
        coverage.add(sourceFileCoverage2)

        Truth.assertThat(coverage.getAllSourceFiles()).hasSize(1)
        Truth.assertThat(
            com.google.common.collect.Iterables.get<SourceFileCoverage?>(coverage.getAllSourceFiles(), 0).getLines()
        )
            .containsExactly(1, 5L, 2, 4L, 3, 2L)
        Truth.assertThat(
            com.google.common.collect.Iterables.get<SourceFileCoverage?>(coverage.getAllSourceFiles(), 0)
                .getAllBranches()
        )
            .containsExactly(
                BranchCoverageItem.Companion.create(1, "", "0", true, 3),
                BranchCoverageItem.Companion.create(1, "", "1", true, 3)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDistinctTracefiles() {
        val sourceFileCoverage1: SourceFileCoverage = SourceFileCoverage("src_1.foo")
        val sourceFileCoverage2: SourceFileCoverage = SourceFileCoverage("src_2.foo")
        sourceFileCoverage1.addLine(1, 1L)
        sourceFileCoverage1.addLine(2, 1L)
        sourceFileCoverage2.addLine(1, 3L)
        sourceFileCoverage2.addLine(2, 3L)

        coverage.add(sourceFileCoverage1)
        coverage.add(sourceFileCoverage2)

        Truth.assertThat(coverage.getAllSourceFiles()).hasSize(2)
        Truth.assertThat(
            com.google.common.collect.Iterables.get<SourceFileCoverage?>(coverage.getAllSourceFiles(), 0)
                .sourceFileName()
        )
            .isEqualTo("src_1.foo")
        Truth.assertThat(
            com.google.common.collect.Iterables.get<SourceFileCoverage?>(coverage.getAllSourceFiles(), 1)
                .sourceFileName()
        )
            .isEqualTo("src_2.foo")
        Truth.assertThat(
            com.google.common.collect.Iterables.get<SourceFileCoverage?>(coverage.getAllSourceFiles(), 0).getLines()
        )
            .containsExactly(1, 1L, 2, 1L)
        Truth.assertThat(
            com.google.common.collect.Iterables.get<SourceFileCoverage?>(coverage.getAllSourceFiles(), 1).getLines()
        )
            .containsExactly(1, 3L, 2, 3L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilterSources() {
        val coverage: Coverage = Coverage()

        coverage.add(SourceFileCoverage("/filterOut/package/file1.c"))
        coverage.add(SourceFileCoverage("/filterOut/package/file2.c"))
        val validSource1: SourceFileCoverage = SourceFileCoverage("/valid/package/file3.c")
        coverage.add(validSource1)
        val validSource2: SourceFileCoverage = SourceFileCoverage("/valid/package/file4.c")
        coverage.add(validSource2)
        val filteredSources: MutableCollection<SourceFileCoverage>? =
            Coverage.Companion.filterOutMatchingSources(
                coverage,
                com.google.common.collect.ImmutableList.of<String?>("/filterOut/package/.+")
            )
                .getAllSourceFiles()

        Truth.assertThat(filteredSources).containsExactly(validSource1, validSource2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilterSourcesEmptyResult() {
        val coverage: Coverage = Coverage()

        coverage.add(SourceFileCoverage("/filterOut/package/file1.c"))
        coverage.add(SourceFileCoverage("/filterOut/package/file2.c"))
        val filteredSources: MutableCollection<SourceFileCoverage>? =
            Coverage.Companion.filterOutMatchingSources(
                coverage,
                com.google.common.collect.ImmutableList.of<String?>("/filterOut/package/.+")
            )
                .getAllSourceFiles()

        Truth.assertThat(filteredSources).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilterSourcesNoMatches() {
        val coverage: Coverage = Coverage()

        val validSource1: SourceFileCoverage = SourceFileCoverage("/valid/package/file3.c")
        coverage.add(validSource1)
        val validSource2: SourceFileCoverage = SourceFileCoverage("/valid/package/file4.c")
        coverage.add(validSource2)
        val filteredSources: MutableCollection<SourceFileCoverage>? =
            Coverage.Companion.filterOutMatchingSources(
                coverage,
                com.google.common.collect.ImmutableList.of<String?>("/something/else/.+")
            )
                .getAllSourceFiles()

        Truth.assertThat(filteredSources).containsExactly(validSource1, validSource2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilterSourcesMultipleRegex() {
        val coverage: Coverage = Coverage()

        coverage.add(SourceFileCoverage("/filterOut/package/file1.c"))
        coverage.add(SourceFileCoverage("/filterOut/package/file2.c"))
        coverage.add(SourceFileCoverage("/repo/external/p.c"))
        val validSource1: SourceFileCoverage = SourceFileCoverage("/valid/package/file3.c")
        coverage.add(validSource1)
        val validSource2: SourceFileCoverage = SourceFileCoverage("/valid/package/file4.c")
        coverage.add(validSource2)
        val filteredSources: MutableCollection<SourceFileCoverage>? =
            Coverage.Companion.filterOutMatchingSources(
                coverage, com.google.common.collect.ImmutableList.of<String?>("/filterOut/package/.+", ".+external.+")
            )
                .getAllSourceFiles()

        Truth.assertThat(filteredSources).containsExactly(validSource1, validSource2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilterSourcesNoFilter() {
        val coverage: Coverage = Coverage()

        val validSource1: SourceFileCoverage = SourceFileCoverage("/valid/package/file3.c")
        coverage.add(validSource1)
        val validSource2: SourceFileCoverage = SourceFileCoverage("/valid/package/file4.c")
        coverage.add(validSource2)
        val filteredSources: MutableCollection<SourceFileCoverage>? =
            Coverage.Companion.filterOutMatchingSources(coverage, com.google.common.collect.ImmutableList.of<String?>())
                .getAllSourceFiles()

        Truth.assertThat(filteredSources).containsExactly(validSource1, validSource2)
    }

    @org.junit.Test
    fun testFilterSourcesNullCoverage() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                Coverage.Companion.filterOutMatchingSources(
                    null,
                    com.google.common.collect.ImmutableList.of<String?>()
                )
            })
    }

    @org.junit.Test
    fun testFilterSourcesNullRegex() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { Coverage.Companion.filterOutMatchingSources(Coverage(), null) })
    }

    private fun getSourceFileNames(
        sourceFileCoverageCollection: MutableCollection<SourceFileCoverage>
    ): MutableList<String> {
        val sourceFilenames: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        for (sourceFileCoverage in sourceFileCoverageCollection) {
            sourceFilenames.add(sourceFileCoverage.sourceFileName())
        }
        return sourceFilenames.build()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetOnlyTheseSources() {
        val coverage: Coverage = Coverage()
        coverage.add(SourceFileCoverage("source/common/protobuf/utility.cc"))
        coverage.add(SourceFileCoverage("source/common/grpc/common.cc"))
        coverage.add(SourceFileCoverage("source/server/options.cc"))
        coverage.add(SourceFileCoverage("source/server/manager.cc"))

        val sourcesToKeep: MutableSet<String?> = HashSet<String?>()
        sourcesToKeep.add("source/common/protobuf/utility.cc")
        sourcesToKeep.add("source/common/grpc/common.cc")

        Truth.assertThat(
            getSourceFileNames(
                Coverage.Companion.getOnlyTheseSources(coverage, sourcesToKeep).getAllSourceFiles()
            )
        )
            .containsExactly("source/common/protobuf/utility.cc", "source/common/grpc/common.cc")
    }

    @org.junit.Test
    fun testGetOnlyTheseSourcesNullCoverage() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { Coverage.Companion.getOnlyTheseSources(null, HashSet<String?>()) })
    }

    @org.junit.Test
    fun testGetOnlyTheseSourcesNullSources() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { Coverage.Companion.getOnlyTheseSources(Coverage(), null) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetOnlyTheseSourcesEmptySources() {
        val coverage: Coverage = Coverage()
        coverage.add(SourceFileCoverage("source/common/protobuf/utility.cc"))
        coverage.add(SourceFileCoverage("source/common/grpc/common.cc"))
        coverage.add(SourceFileCoverage("source/server/options.cc"))
        coverage.add(SourceFileCoverage("source/server/manager.cc"))

        Truth.assertThat(Coverage.Companion.getOnlyTheseSources(coverage, HashSet<String?>()).getAllSourceFiles())
            .isEmpty()
    }
}
