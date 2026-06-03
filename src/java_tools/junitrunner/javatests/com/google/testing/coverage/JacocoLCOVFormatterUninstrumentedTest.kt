// Copyright 2020 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.coverage

import com.google.common.truth.Truth
import com.google.testing.coverage.BranchCoverageDetail
import com.google.testing.coverage.JacocoLCOVFormatter
import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import net.starlark.java.syntax.Identifier.getName
import org.jacoco.core.analysis.IBundleCoverage
import org.jacoco.core.analysis.IClassCoverage
import org.jacoco.core.analysis.IPackageCoverage
import org.jacoco.report.IReportVisitor
import org.jacoco.report.ISourceFileLocator
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito
import java.io.IOException
import java.io.PrintWriter
import java.util.TreeMap

/** Tests the uninstrumented class processing logic in [JacocoLCOVFormatter].  */
@RunWith(JUnit4::class)
class JacocoLCOVFormatterUninstrumentedTest {
    private var writer: java.io.StringWriter? = null
    private var mockBundle: IBundleCoverage? = null

    private fun createSuiteDescription(name: String): org.junit.runner.Description {
        val suite: org.junit.runner.Description = org.junit.runner.Description.createSuiteDescription(name)
        suite.addChild(org.junit.runner.Description.createTestDescription(Any::class.java, "child"))
        return suite
    }

    @Before
    fun setupTest() {
        // Initialize writer for storing coverage report outputs
        writer = java.io.StringWriter()
        // Initialize mock Jacoco bundle containing the mock coverage
        // Classes
        val mockClassCoverages: MutableList<IClassCoverage?> =
            java.util.Arrays.asList<IClassCoverage?>(mockIClassCoverage("Foo", "com/example", "Foo.java"))
        // Package
        val mockPackageCoverage: IPackageCoverage = Mockito.mock<IPackageCoverage>(IPackageCoverage::class.java)
        Mockito.`when`<MutableCollection<IClassCoverage?>?>(mockPackageCoverage.getClasses())
            .thenReturn(mockClassCoverages)
        // Bundle
        mockBundle = Mockito.mock<IBundleCoverage>(IBundleCoverage::class.java)
        Mockito.`when`<MutableCollection<IPackageCoverage?>?>(mockBundle.getPackages())
            .thenReturn(java.util.Arrays.asList<IPackageCoverage?>(mockPackageCoverage))
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testVisitBundleWithSimpleUnixPath() {
        // Paths
        val execPaths: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>("/parent/dir/com/example/Foo.java")
        val formatter: JacocoLCOVFormatter = JacocoLCOVFormatter(execPaths)
        val visitor: IReportVisitor =
            formatter.createVisitor(
                PrintWriter(writer), TreeMap<String?, BranchCoverageDetail?>()
            )

        visitor.visitBundle(mockBundle, Mockito.mock<ISourceFileLocator?>(ISourceFileLocator::class.java))
        visitor.visitEnd()

        val coverageOutput: String? = writer.toString()
        for (sourcePath in execPaths) {
            Truth.assertThat(coverageOutput).contains(sourcePath)
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testVisitBundleWithSimpleWindowsPath() {
        // Paths
        val execPaths: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>("C:/parent/dir/com/example/Foo.java")
        val formatter: JacocoLCOVFormatter = JacocoLCOVFormatter(execPaths)
        val visitor: IReportVisitor =
            formatter.createVisitor(
                PrintWriter(writer), TreeMap<String?, BranchCoverageDetail?>()
            )

        visitor.visitBundle(mockBundle, Mockito.mock<ISourceFileLocator?>(ISourceFileLocator::class.java))
        visitor.visitEnd()

        val coverageOutput: String? = writer.toString()
        for (sourcePath in execPaths) {
            Truth.assertThat(coverageOutput).contains(sourcePath)
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testVisitBundleWithMappedUnixPath() {
        // Paths
        val srcPath = "/some/other/dir/Foo.java"
        val execPaths: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>(srcPath + "////com/example/Foo.java")
        val formatter: JacocoLCOVFormatter = JacocoLCOVFormatter(execPaths)
        val visitor: IReportVisitor =
            formatter.createVisitor(
                PrintWriter(writer), TreeMap<String?, BranchCoverageDetail?>()
            )

        visitor.visitBundle(mockBundle, Mockito.mock<ISourceFileLocator?>(ISourceFileLocator::class.java))
        visitor.visitEnd()

        val coverageOutput: String? = writer.toString()
        Truth.assertThat(coverageOutput).contains(srcPath)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testVisitBundleWithMappedWindowsPath() {
        // Paths
        val srcPath = "C:/some/other/dir/Foo.java"
        val execPaths: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>(srcPath + "////com/example/Foo.java")
        val formatter: JacocoLCOVFormatter = JacocoLCOVFormatter(execPaths)
        val visitor: IReportVisitor =
            formatter.createVisitor(
                PrintWriter(writer), TreeMap<String?, BranchCoverageDetail?>()
            )

        visitor.visitBundle(mockBundle, Mockito.mock<ISourceFileLocator?>(ISourceFileLocator::class.java))
        visitor.visitEnd()

        val coverageOutput: String? = writer.toString()
        Truth.assertThat(coverageOutput).contains(srcPath)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testVisitBundleWithNoMatchHasEmptyOutput() {
        // Non-matching path
        val execPaths: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>("/path/does/not/match/anything.txt")
        val formatter: JacocoLCOVFormatter = JacocoLCOVFormatter(execPaths)
        val visitor: IReportVisitor =
            formatter.createVisitor(
                PrintWriter(writer), TreeMap<String?, BranchCoverageDetail?>()
            )

        visitor.visitBundle(mockBundle, Mockito.mock<ISourceFileLocator?>(ISourceFileLocator::class.java))
        visitor.visitEnd()

        val coverageOutput: String? = writer.toString()
        Truth.assertThat(coverageOutput).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testVisitBundleWithNoExecPathsHasEmptyOutput() {
        // Empty list of exec paths
        val execPaths: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>()
        val formatter: JacocoLCOVFormatter = JacocoLCOVFormatter(execPaths)
        val visitor: IReportVisitor =
            formatter.createVisitor(
                PrintWriter(writer), TreeMap<String?, BranchCoverageDetail?>()
            )

        visitor.visitBundle(mockBundle, Mockito.mock<ISourceFileLocator?>(ISourceFileLocator::class.java))
        visitor.visitEnd()

        val coverageOutput: String? = writer.toString()
        Truth.assertThat(coverageOutput).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testVisitBundleWithoutExecPathsDoesNotPruneOutput() {
        // No paths, don't attempt to demangle paths and prune the output, just output with
        // class-paths as is.
        val formatter: JacocoLCOVFormatter = JacocoLCOVFormatter()
        val visitor: IReportVisitor =
            formatter.createVisitor(
                PrintWriter(writer), TreeMap<String?, BranchCoverageDetail?>()
            )

        visitor.visitBundle(mockBundle, Mockito.mock<ISourceFileLocator?>(ISourceFileLocator::class.java))
        visitor.visitEnd()

        val coverageOutput: String? = writer.toString()
        Truth.assertThat(coverageOutput).isNotEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testVisitBundleWithExactMatch() {
        // It's possible, albeit unlikely, that the execPath and the package based path match exactly
        val srcPath = "com/example/Foo.java"
        val execPaths: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>(srcPath)
        val formatter: JacocoLCOVFormatter = JacocoLCOVFormatter(execPaths)
        val visitor: IReportVisitor =
            formatter.createVisitor(
                PrintWriter(writer), TreeMap<String?, BranchCoverageDetail?>()
            )

        visitor.visitBundle(mockBundle, Mockito.mock<ISourceFileLocator?>(ISourceFileLocator::class.java))
        visitor.visitEnd()

        val coverageOutput: String? = writer.toString()
        Truth.assertThat(coverageOutput).contains(srcPath)
    }

    companion object {
        private fun mockIClassCoverage(
            className: String?, packageName: String?, sourceFileName: String?
        ): IClassCoverage {
            val mocked: IClassCoverage = Mockito.mock<IClassCoverage>(IClassCoverage::class.java)
            Mockito.`when`<String?>(mocked.getName()).thenReturn(className)
            Mockito.`when`<String?>(mocked.getPackageName()).thenReturn(packageName)
            Mockito.`when`<String?>(mocked.getSourceFileName()).thenReturn(sourceFileName)
            return mocked
        }
    }
}
