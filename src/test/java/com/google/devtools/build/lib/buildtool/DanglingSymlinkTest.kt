// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.actions.BuildFailedException

/** Tests for dangling symlinks.  */
@RunWith(JUnit4::class)
class DanglingSymlinkTest : BuildIntegrationTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun addNoJobsOption() {
        addOptions("--jobs", "1")
    }

    /**
     * Regression test for bug 823903 about symlink to non-existent target
     * breaking DependencyChecker.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDanglingSymlinks() {
        write(
            "test/BUILD",
            "genrule(name='test_ln', srcs=[], outs=['test.out']," +
                    " cmd='/bin/ln -sf wrong.out $(@D)/test.out')\n"
        )

        addOptions("--keep_going")
        val e: BuildFailedException? =
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//test:test_ln") })
        assertThat(e).hasMessageThat().isNull()

        events.assertContainsError("output 'test/test.out' is a dangling symbolic link")
        events.assertContainsError(
            "Executing genrule //test:test_ln failed: not all outputs were created"
        )
    }

    /** Tests that bad symlinks for inputs are properly handled.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCircularSymlinkMidLevel() {
        val fooBuildFile: Path =
            write(
                "foo/BUILD",
                """
            filegroup(
                name = "foo",
                srcs = ["foo.sh"],
            )

            genrule(
                name = "top",
                srcs = [":foo"],
                outs = ["out"],
                cmd = "touch ${'$'}@",
            )
            
            """.trimIndent()
            )
        val fooShFile: Path = fooBuildFile.getParentDirectory().getRelative("foo.sh")
        fooShFile.createSymbolicLink(PathFragment.create("foo.sh"))

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:top") })
        events.assertContainsError(
            "Executing genrule //foo:top failed: error reading file '//foo:foo.sh': Symlink cycle"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDanglingSymlinkMidLevel() {
        val fooBuildFile: Path =
            write(
                "foo/BUILD",
                """
            load('//test_defs:foo_binary.bzl', 'foo_binary')
            foo_binary(
                name = "foo",
                srcs = ["foo.sh"],
            )

            genrule(
                name = "top",
                srcs = [":foo"],
                outs = ["out"],
                cmd = "touch ${'$'}@",
            )
            
            """.trimIndent()
            )
        val fooShFile: Path = fooBuildFile.getParentDirectory().getRelative("foo.sh")
        fooShFile.createSymbolicLink(PathFragment.create("doesnotexist"))

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:top") })
        events.assertContainsError("missing input file '//foo:foo.sh'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun globDanglingSymlink() {
        val packageDirPath: Path = write("foo/BUILD", "exports_files(glob(['*.txt']))").getParentDirectory()
        write("foo/existing.txt")
        val badSymlink: Path? = packageDirPath.getChild("bad.txt")
        FileSystemUtils.ensureSymbolicLink(badSymlink, "nope")
        // Successful build: dangling symlinks in glob are ignored.
        buildTarget("//foo:all")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun globSymlinkCycle() {
        val fooBuildFile: Path = write("foo/BUILD", "filegroup(name = 'foo', srcs = glob(['*.sh']))")
        fooBuildFile
            .getParentDirectory()
            .getChild("foo.sh")
            .createSymbolicLink(PathFragment.create("foo.sh"))
        val e: TargetParsingException =
            org.junit.Assert.assertThrows<T>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:foo") })
        assertThat(e.getDetailedExitCode().getFailureDetail().getPackageLoading().getCode())
            .isEqualTo(FailureDetails.PackageLoading.Code.EVAL_GLOBS_SYMLINK_ERROR)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun globMissingFile() {
    }
}
