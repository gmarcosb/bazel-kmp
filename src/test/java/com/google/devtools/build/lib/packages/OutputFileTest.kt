// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.common.testing.EqualsTester
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.getTarget
import com.google.devtools.build.lib.packages.util.PackageLoadingTestCase
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction.name
import com.google.devtools.build.lib.testutil.FoundationTestCase
import net.starlark.java.syntax.Location.file
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class OutputFileTest : PackageLoadingTestCase() {
    private var pkg: java.lang.Package? = null
    private var rule: Rule? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createRule() {
        scratch.file("pkg/BUILD", "genrule(name='foo', srcs=[], cmd='', outs=['x', 'subdir/y'])")
        this.rule = getTarget("//pkg:foo") as Rule
        this.pkg = getPackage(this.rule.getLabel().getPackageIdentifier())
        assertThat(this.pkg.getTarget(this.rule.getLabel().name)).isSameInstanceAs(this.rule)
        assertNoEvents()
    }

    @Throws(java.lang.Exception::class)
    private fun checkTargetRetainsGeneratingRule(output: OutputFile) {
        assertThat(output.getGeneratingRule()).isSameInstanceAs(rule)
    }

    @Throws(java.lang.Exception::class)
    private fun checkName(output: OutputFile, expectedName: String?) {
        assertThat(output.getName()).isEqualTo(expectedName)
    }

    @Throws(java.lang.Exception::class)
    private fun checkLabel(output: OutputFile, expectedLabelString: String?) {
        assertThat(output.getLabel().toString()).isEqualTo(expectedLabelString)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetAssociatedRule() {
        assertThat(pkg.getTarget("x").getAssociatedRule()).isSameInstanceAs(rule)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputFileInPackageDir() {
        val outputFileX: OutputFile = pkg.getTarget("x") as OutputFile
        checkTargetRetainsGeneratingRule(outputFileX)
        checkName(outputFileX, "x")
        checkLabel(outputFileX, "//pkg:x")
        assertThat(outputFileX.getTargetKind()).isEqualTo("generated file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputFileInSubdirectory() {
        val outputFileY: OutputFile = pkg.getTarget("subdir/y") as OutputFile
        checkTargetRetainsGeneratingRule(outputFileY)
        checkName(outputFileY, "subdir/y")
        checkLabel(outputFileY, "//pkg:subdir/y")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEquivalenceRelation() {
        val outputFileX1: OutputFile? = pkg.getTarget("x") as OutputFile?
        val outputFileX2: OutputFile? = pkg.getTarget("x") as OutputFile?
        val outputFileY1: OutputFile? = pkg.getTarget("subdir/y") as OutputFile?
        val outputFileY2: OutputFile? = pkg.getTarget("subdir/y") as OutputFile?
        assertThat(outputFileX2).isSameInstanceAs(outputFileX1)
        assertThat(outputFileY2).isSameInstanceAs(outputFileY1)
        EqualsTester()
            .addEqualityGroup(outputFileX1, outputFileX2)
            .addEqualityGroup(outputFileY1, outputFileY2)
            .testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicateOutputFilesInDifferentRules() {
        scratch.file(
            "two_outs/BUILD",
            """
        genrule(
            name = "a",
            outs = ["out"],
            cmd = "ls >${'$'}(location out)",
        )

        genrule(
            name = "b",
            outs = ["out"],
            cmd = "ls >${'$'}(location out)",
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getTarget("//two_outs:BUILD")
        assertContainsEvent(
            "generated file 'out' in rule 'b' conflicts with "
                    + "existing generated file from rule 'a'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputFileNameConflictsWithExistingRule() {
        scratch.file(
            "out_is_rule/BUILD",
            """
        genrule(
            name = "a",
            outs = ["out"],
            cmd = "ls >${'$'}(location out)",
        )

        genrule(
            name = "b",
            outs = ["a"],
            cmd = "ls >${'$'}(location out)",
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getTarget("//out_is_rule:BUILD")
        assertContainsEvent("generated file 'a' in rule 'b' conflicts with existing genrule rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicateOutputFilesInSameRule() {
        scratch.file(
            "two_outs/BUILD",
            """
        genrule(
            name = "a",
            outs = [
                "out",
                "out",
            ],
            cmd = "ls >${'$'}(location out)",
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getTarget("//two_outs:BUILD")
        assertContainsEvent("rule 'a' has more than one generated file named 'out'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputFileWithIllegalName() {
        scratch.file("bad_out_name/BUILD", "genrule(name='a', cmd='ls', outs=['!@#:'])")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getTarget("//bad_out_name:BUILD")
        assertContainsEvent("invalid label '!@#:'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputFileWithCrossPackageLabel() {
        scratch.file("cross_package_out/BUILD", "genrule(name='a', cmd='ls', outs=['//foo:bar'])")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getTarget("//cross_package_out:BUILD")
        assertContainsEvent("label '//foo:bar' is not in the current package")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputFileNamedBUILD() {
        scratch.file("output_called_build/BUILD", "genrule(name='a', cmd='ls', outs=['BUILD'])")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getTarget("//output_called_build:BUILD")
        assertContainsEvent("generated file 'BUILD' in rule 'a' conflicts with existing source file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReduceForSerialization() {
        val outputFileX: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            pkg.getTarget("x")
        assertThat(outputFileX).hasSamePropertiesAs(outputFileX.reduceForSerialization())
        val outputFileY: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            pkg.getTarget("subdir/y")
        assertThat(outputFileY).hasSamePropertiesAs(outputFileY.reduceForSerialization())
    }
}
