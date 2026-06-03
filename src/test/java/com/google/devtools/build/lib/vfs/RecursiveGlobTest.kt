// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.vfs.UnixGlob.FilesystemOps

/** Tests [UnixGlob] recursive globs.  */
@RunWith(JUnit4::class)
class RecursiveGlobTest {
    private var tmpPath: Path? = null
    private var fileSystem: FileSystem? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun initializeFileSystem() {
        fileSystem =
            InMemoryFileSystem(com.google.devtools.build.lib.clock.BlazeClock.instance(), DigestHashFunction.SHA256)
        tmpPath = fileSystem.getPath("/rglobtmp")
        for (dir in com.google.common.collect.ImmutableList.of<String?>(
            "foo/bar/wiz",
            "foo/baz/wiz",
            "foo/baz/quip/wiz",
            "food/baz/wiz",
            "fool/baz/wiz"
        )) {
            tmpPath.getRelative(dir).createDirectoryAndParents()
        }
        FileSystemUtils.createEmptyFile(tmpPath.getRelative("foo/bar/wiz/file"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleStar() {
        assertGlobMatches(
            "**", ".", "foo", "foo/bar", "foo/bar/wiz", "foo/baz", "foo/baz/quip",
            "foo/baz/quip/wiz", "foo/baz/wiz", "foo/bar/wiz/file", "food", "food/baz",
            "food/baz/wiz", "fool", "fool/baz", "fool/baz/wiz"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleDoubleStar() {
        assertGlobMatches(
            "**/**", ".", "foo", "foo/bar", "foo/bar/wiz", "foo/baz", "foo/baz/quip",
            "foo/baz/quip/wiz", "foo/baz/wiz", "foo/bar/wiz/file", "food", "food/baz",
            "food/baz/wiz", "fool", "fool/baz", "fool/baz/wiz"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectoryWithDoubleStar() {
        assertGlobMatches(
            "foo/**", "foo", "foo/bar", "foo/bar/wiz", "foo/baz", "foo/baz/quip",
            "foo/baz/quip/wiz", "foo/baz/wiz", "foo/bar/wiz/file"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIllegalPatterns() {
        for (prefix in com.google.common.collect.Lists.newArrayList<String>("", "*/", "**/", "ba/")) {
            val suffix: String = ("/" + prefix).substring(0, prefix.length)
            for (pattern in com.google.common.collect.Lists.newArrayList<String?>(
                "**fo",
                "fo**",
                "**fo**",
                "fo**fo",
                "fo**fo**fo"
            )) {
                assertIllegalWildcard(prefix + pattern)
                assertIllegalWildcard(pattern + suffix)
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleStarPatternWithNamedChild() {
        assertGlobMatches("**/bar", "foo/bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleStarPatternWithChildGlob() {
        assertGlobMatches(
            "**/ba*",
            "foo/bar", "foo/baz", "food/baz", "fool/baz"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleStarAsChildGlob() {
        assertGlobMatches("foo/**/wiz", "foo/bar/wiz", "foo/baz/quip/wiz", "foo/baz/wiz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleStarUnderNonexistentDirectory() {
        assertGlobMatches("not-there/**" /* => nothing */)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleStarGlobWithNonExistentBase() {
        val globResult: MutableCollection<Path?>? =
            Builder(fileSystem.getPath("/does/not/exist"), FilesystemOps.DIRECT)
                .addPattern("**")
                .globInterruptible()
        Truth.assertThat(globResult).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleStarUnderFile() {
        assertGlobMatches("foo/bar/wiz/file/**" /* => nothing */)
    }

    @Throws(java.lang.Exception::class)
    private fun assertGlobMatches(pattern: String?, vararg expecteds: String) {
        assertThat(
            Builder(tmpPath, FilesystemOps.DIRECT)
                .addPatterns(pattern)
                .globInterruptible()
        )
            .containsExactlyElementsIn(resolvePaths(*expecteds))
    }

    private fun resolvePaths(vararg relativePaths: String): MutableSet<Path?> {
        val expectedFiles: MutableSet<Path?> = HashSet<Path?>()
        for (expected in relativePaths) {
            val file: Path? = if (expected == ".")
                tmpPath
            else
                tmpPath.getRelative(expected)
            expectedFiles.add(file)
        }
        return expectedFiles
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRecursiveGlobsAreOptimized() {
        val numGlobTasks: Long =
            Builder(tmpPath, FilesystemOps.DIRECT)
                .addPattern("**")
                .setPathDiscriminator(
                    TestUnixGlobPathDiscriminator(
                        java.util.function.Predicate { p: Path? -> true },
                        java.util.function.BiPredicate { p: Path?, isDir: Boolean? -> !isDir!! })
                )
                .globInterruptibleAndReturnNumGlobTasksForTesting()

        // The old glob implementation used to use 41 total glob tasks.
        // Yes, checking for an exact value here is super brittle, but it lets us catch performance
        // regressions. In other words, if you're a developer reading this comment because this test
        // case is failing, you should be very sure you know what you're doing before you change the
        // expectation of the test.
        Truth.assertThat(numGlobTasks).isEqualTo(28)
    }

    @Throws(java.lang.Exception::class)
    private fun assertIllegalWildcard(pattern: String?) {
        val e: UnixGlob.BadPattern? =
            org.junit.Assert.assertThrows<T?>(
                UnixGlob.BadPattern::class.java,
                org.junit.function.ThrowingRunnable {
                    Builder(tmpPath, FilesystemOps.DIRECT)
                        .addPattern(pattern)
                        .globInterruptible()
                })
        assertThat(e).hasMessageThat().containsMatch("recursive wildcard must be its own segment")
    }
}
