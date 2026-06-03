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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.cmdline.Label

/**
 * Tests for recovering from IOExceptions thrown by the filesystem when reading BUILD files. Needs
 * its own test class because it uses a custom filesystem.
 */
@RunWith(JUnit4::class)
class IOExceptionsTest : PackageLoadingTestCase() {
    private var crashMessage: java.util.function.Function<PathFragment?, String?> =
        java.util.function.Function { p: PathFragment? -> nullFunction(p) }

    @Before
    fun initializeVisitor() {
        setUpSkyframe(RuleVisibility.PRIVATE)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun visitTransitively(label: Label?): Boolean {
        val key: SkyKey = TransitiveTargetKey.of(label)
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder().setParallelism(5).setEventHandler(reporter).build()
        val result: EvaluationResult<SkyValue?> =
            skyframeExecutor.prepareAndGet(com.google.common.collect.ImmutableSet.of<E?>(key), evaluationContext)
        val value: TransitiveTargetValue? = result.get(key) as TransitiveTargetValue?
        val hasTransitiveError = (value == null) || value.encounteredLoadingError()
        return !result.hasError() && !hasTransitiveError
    }

    @Throws(java.lang.Exception::class)
    protected fun syncPackages() {
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory)
        )
    }

    override fun createFileSystem(): FileSystem? {
        return object : InMemoryFileSystem(DigestHashFunction.SHA256) {
            @Throws(IOException::class)
            public override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus? {
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
    fun testBasicFailure() {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        val buildPath: Path =
            scratch.file(
                "pkg/BUILD",
                "load('//test_defs:foo_library.bzl', 'foo_library')",
                "foo_library(name = 'x')"
            )
        crashMessage =
            java.util.function.Function { path: PathFragment? ->
                if (buildPath.asFragment().equals(path)) "custom crash: " + buildPath else null
            }
        Truth.assertThat(visitTransitively(Label.parseCanonical("//pkg:x"))).isFalse()
        scratch.overwriteFile(
            "pkg/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        # another comment to force reload
        foo_library(name = "x")
        
        """.trimIndent()
        )
        crashMessage = java.util.function.Function { p: PathFragment? -> nullFunction(p) }
        syncPackages()
        eventCollector.clear()
        reporter.addHandler(FoundationTestCase.failFastHandler)
        Truth.assertThat(visitTransitively(Label.parseCanonical("//pkg:x"))).isTrue()
        assertNoEvents()
    }


    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNestedFailure() {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        scratch.file(
            "top/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'top', deps = ['//pkg:x'])"
        )
        val buildPath: Path =
            scratch.file(
                "pkg/BUILD",
                "load('//test_defs:foo_library.bzl', 'foo_library')",
                "foo_library(name = 'x')"
            )
        crashMessage =
            java.util.function.Function { path: PathFragment? ->
                if (buildPath.asFragment().equals(path)) "custom crash: " + buildPath else null
            }
        Truth.assertThat(visitTransitively(Label.parseCanonical("//top:top"))).isFalse()
        assertContainsEvent("no such package 'pkg'")
        assertContainsEvent("custom crash")
        Truth.assertThat(eventCollector).hasSize(1)
        scratch.overwriteFile(
            "pkg/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        # another comment to force reload
        foo_library(name = "x")
        
        """.trimIndent()
        )
        crashMessage = java.util.function.Function { p: PathFragment? -> nullFunction(p) }
        syncPackages()
        eventCollector.clear()
        reporter.addHandler(FoundationTestCase.failFastHandler)
        Truth.assertThat(visitTransitively(Label.parseCanonical("//top:top"))).isTrue()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOneLevelUpFailure() {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        val buildPath: Path =
            scratch.file(
                "top/BUILD",
                "load('//test_defs:foo_library.bzl', 'foo_library')",
                "foo_library(name = 'x')"
            )
        buildPath.getParentDirectory().getRelative("pkg").createDirectory()
        crashMessage =
            java.util.function.Function { path: PathFragment? ->
                if (buildPath.asFragment().equals(path)) "custom crash: " + buildPath else null
            }
        Truth.assertThat(visitTransitively(Label.parseCanonical("//top/pkg:x"))).isFalse()
    }

    companion object {
        private fun nullFunction(p: PathFragment?): String? {
            return null
        }
    }
}
