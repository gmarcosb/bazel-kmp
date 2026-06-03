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

import com.google.devtools.build.lib.vfs.Path

/** A test for [InputFile].  */
@RunWith(JUnit4::class)
class InputFileTest : PackageLoadingTestCase() {
    private var pathX: Path? = null
    private var pathY: Path? = null
    private var pkg: java.lang.Package? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun writeFiles() {
        scratch.file("pkg/BUILD", "genrule(name='dummy', cmd='', outs=[], srcs=['x', 'subdir/y'])")
        pkg = getPackage("pkg")
        assertNoEvents()

        this.pathX = scratch.file("pkg/x", "blah")
        this.pathY = scratch.file("pkg/subdir/y", "blah blah")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetAssociatedRule() {
        Truth.assertWithMessage(null).that(pkg.getTarget("x").getAssociatedRule()).isNull()
    }

    @org.junit.Test
    @Throws(NoSuchTargetException::class)
    fun testInputFileInPackageDirectory() {
        val inputFileX: InputFile = pkg.getTarget("x") as InputFile
        checkPathMatches(inputFileX, pathX)
        checkName(inputFileX, "x")
        checkLabel(inputFileX, "//pkg:x")
        assertThat(inputFileX.getTargetKind()).isEqualTo("source file")
    }

    @org.junit.Test
    @Throws(NoSuchTargetException::class)
    fun testInputFileInSubdirectory() {
        val inputFileY: InputFile = pkg.getTarget("subdir/y") as InputFile
        checkPathMatches(inputFileY, pathY)
        checkName(inputFileY, "subdir/y")
        checkLabel(inputFileY, "//pkg:subdir/y")
    }

    @org.junit.Test
    @Throws(NoSuchTargetException::class)
    fun testEquivalenceRelation() {
        val inputFileX: InputFile? = pkg.getTarget("x") as InputFile?
        assertThat(inputFileX).isSameInstanceAs(pkg.getTarget("x"))
        val inputFileY: InputFile? = pkg.getTarget("subdir/y") as InputFile?
        assertThat(inputFileY).isSameInstanceAs(pkg.getTarget("subdir/y"))
        EqualsTester()
            .addEqualityGroup(inputFileX)
            .addEqualityGroup(inputFileY)
            .testEquals()
    }

    @org.junit.Test
    @Throws(NoSuchTargetException::class)
    fun testReduceForSerialization() {
        val inputFileX: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            pkg.getTarget("x")
        assertThat(inputFileX).hasSamePropertiesAs(inputFileX.reduceForSerialization())
        val inputFileY: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            pkg.getTarget("subdir/y")
        assertThat(inputFileY).hasSamePropertiesAs(inputFileY.reduceForSerialization())
    }

    companion object {
        private fun checkPathMatches(input: InputFile, expectedPath: Path?) {
            assertThat(input.getPath()).isEqualTo(expectedPath)
        }

        private fun checkName(input: InputFile, expectedName: String?) {
            assertThat(input.getName()).isEqualTo(expectedName)
        }

        private fun checkLabel(input: InputFile, expectedLabelString: String?) {
            assertThat(input.getLabel().toString()).isEqualTo(expectedLabelString)
        }
    }
}
