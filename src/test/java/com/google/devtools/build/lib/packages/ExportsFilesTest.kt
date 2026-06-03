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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.getTarget
import com.google.devtools.build.lib.packages.util.PackageLoadingTestCase
import com.google.devtools.build.lib.testutil.FoundationTestCase
import net.starlark.java.syntax.Location.file
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.Collections

/** A test for the `exports_files` function.  */
@RunWith(JUnit4::class)
class ExportsFilesTest : PackageLoadingTestCase() {
    @Throws(java.lang.Exception::class)
    private fun pkg(): java.lang.Package {
        scratch.file("pkg/BUILD", "exports_files(['foo.txt', 'bar.txt'])")
        return getPackage("pkg")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExportsFilesRegistersFilesWithPackage() {
        val names = getFileNamesOf(pkg())
        val expected = "//pkg:BUILD //pkg:bar.txt //pkg:foo.txt"
        Truth.assertThat(com.google.common.base.Joiner.on(' ').join(names)).isEqualTo(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileThatsNotRegisteredYieldsUnknownTargetException() {
        val e: NoSuchTargetException? =
            org.junit.Assert.assertThrows<T?>(
                NoSuchTargetException::class.java,
                org.junit.function.ThrowingRunnable { pkg().getTarget("baz.txt") })
        assertThat(e)
            .hasMessageThat()
            .contains("no such target '//pkg:baz.txt': target 'baz.txt' not declared in package 'pkg'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredFilesAreRetrievable() {
        val pkg: java.lang.Package = pkg()
        assertThat(pkg.getTarget("foo.txt").getName()).isEqualTo("foo.txt")
        assertThat(pkg.getTarget("bar.txt").getName()).isEqualTo("bar.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExportsFilesAndRuleNameConflict() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "pkg2/BUILD",
            """
        exports_files(["foo"])

        genrule(
            name = "foo",
            srcs = ["bar"],
            outs = [],
            cmd = "/bin/true",
        )
        
        """.trimIndent()
        )
        assertThat(getTarget("//pkg2:foo")).isInstanceOf(InputFile::class.java)
        assertContainsEvent("rule 'foo' conflicts with existing source file")
    }

    companion object {
        /**
         * Returns the names of the input files that are known to pkg.
         */
        private fun getFileNamesOf(pkg: java.lang.Package): MutableList<String?> {
            val names: MutableList<String?> = java.util.ArrayList<String?>()
            for (target in pkg.getTargets(FileTarget::class.java)) {
                names.add(target.getLabel().toString())
            }
            Collections.sort<String?>(names)
            return names
        }
    }
}
