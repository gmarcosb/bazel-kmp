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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.vfs.DigestHashFunction

/**
 * Tests verifying appropriate propagation of [InterruptedException] during filesystem
 * operations.
 */
@RunWith(JUnit4::class)
class InterruptedExceptionTest : AnalysisTestCase() {
    private val mainThread: java.lang.Thread = java.lang.Thread.currentThread()

    override fun createFileSystem(): FileSystem? {
        return object : InMemoryFileSystem(DigestHashFunction.SHA256) {
            @Throws(IOException::class)
            public override fun readdir(path: PathFragment, followSymlinks: Boolean): MutableCollection<Dirent?> {
                if (path.toString().contains("causes_interrupt")) {
                    mainThread.interrupt()
                }
                return super.readdir(path, followSymlinks)
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobInterruptedException() {
        scratch.file(
            "a/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'a', srcs = glob(['**/*']))"
        )
        scratch.file("a/b/foo.sh", "testfile")
        scratch.file("a/causes_interrupt/bar.sh", "testfile")
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { update("//a:a") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkGlobInterruptedException() {
        scratch.file(
            "a/gen.bzl",
            """
        def gen():
            native.filegroup(name = "a", srcs = native.glob(["**/*"]))
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load("//a:gen.bzl", "gen")

        gen()
        
        """.trimIndent()
        )

        scratch.file("a/b/foo.sh", "testfile")
        scratch.file("a/causes_interrupt/bar.sh", "testfile")
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { update("//a:a") })
    }
}
