// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.cmdline.TargetParsingException

/** [AnalysisTestCase] with custom filesystem that can throw on stat if desired.  */
@RunWith(JUnit4::class)
class AnalysisWithIOExceptionsTest : AnalysisTestCase() {
    private var crashMessage: java.util.function.Function<PathFragment?, String?> =
        java.util.function.Function { path: PathFragment? -> null }

    override fun createFileSystem(): FileSystem? {
        return object : InMemoryFileSystem(DigestHashFunction.SHA256) {
            @Throws(IOException::class)
            public override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus {
                val crash: String? = crashMessage.apply(path)
                if (crash != null) {
                    throw IOException(crash)
                }
                return super.statIfFound(path, followSymlinks)
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobIOException() {
        scratch.file(
            "b/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'b', deps= ['//a:a'])"
        )
        scratch.file(
            "a/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'a', srcs = glob(['a.sh']))"
        )
        crashMessage = java.util.function.Function { path: PathFragment? ->
            if (path.toString().contains("a.sh")) "bork" else null
        }
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//b:b") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncrementalGlobIOException() {
        scratch.file(
            "b/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'b', deps= ['//a:a'])"
        )
        scratch.file(
            "a/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', srcs = glob(['a.sh']))
        foo_library(name = 'expensive', srcs = ['expensive.sh'])
        
        """.trimIndent()
        )
        val aShFile: Path = scratch.file("a/a.sh")
        update("//b:b")
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(aShFile.relativeTo(rootDirectory)).build(),
            Root.fromPath(rootDirectory)
        )
        crashMessage = java.util.function.Function { path: PathFragment? ->
            if (path.toString().contains("a.sh")) "bork" else null
        }
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//b:b") })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testWorkspaceError() {
        scratch.file("a/BUILD")
        crashMessage = java.util.function.Function { path: PathFragment? ->
            if (path.toString().contains("MODULE.bazel")) "bork" else null
        }
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable {
                update(
                    FlagBuilder().with(AnalysisTestCase.Flag.KEEP_GOING),
                    "//a:a"
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobExceptionWithCrossingLabel() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val buildPath: Path =
            scratch.file(
                "foo/BUILD",
                """
            load('//test_defs:foo_library.bzl', 'foo_library')
            foo_library(name = 'foo', srcs = glob(['subdir/*.sh']))
            foo_library(name = 'crosses/directory', srcs = ['foo.sh'])
            
            """.trimIndent()
            )
        scratch.file(
            "top/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'top', deps = ['//foo:foo'], srcs = ['top.sh'])"
        )
        val errorPath: Path = buildPath.getParentDirectory().getChild("subdir")
        crashMessage = java.util.function.Function { path: PathFragment? ->
            if (errorPath.asFragment().equals(path)) "custom crash: bork" else null
        }
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//top:top") })
    }
}
