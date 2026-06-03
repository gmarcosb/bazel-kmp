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

/** Tests [UnixGlob]  */
@RunWith(JUnit4::class)
class GlobTest {
    private var tmpPath: Path? = null
    private var fs: FileSystem? = null
    private var throwOnReaddir: Path? = null
    private var throwOnStat: Path? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun initializeFileSystem() {
        fs =
            object : InMemoryFileSystem(DigestHashFunction.SHA256) {
                @Throws(IOException::class)
                public override fun readdir(path: PathFragment, followSymlinks: Boolean): MutableCollection<Dirent?> {
                    if (throwOnReaddir != null && throwOnReaddir.asFragment().equals(path)) {
                        throw FileNotFoundException(path.getPathString())
                    }
                    return super.readdir(path, followSymlinks)
                }

                @Throws(IOException::class)
                public override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus {
                    if (throwOnStat != null && throwOnStat.asFragment().equals(path)) {
                        throw FileNotFoundException(path.getPathString())
                    }
                    return super.statIfFound(path, followSymlinks)
                }
            }
        tmpPath = fs.getPath("/globtmp")

        val directories: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "foo/bar/wiz", "foo/barnacle/wiz", "food/barnacle/wiz", "fool/barnacle/wiz"
            )

        for (dir in directories) {
            tmpPath.getRelative(dir).createDirectoryAndParents()
        }
        FileSystemUtils.createEmptyFile(tmpPath.getRelative("foo/bar/wiz/file"))
    }

    @org.junit.After
    fun resetInteruppt() {
        java.lang.Thread.interrupted()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testQuestionMarkMatch() {
        assertGlobMatches("foo?",  /* => */"food", "fool")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testQuestionMarkNoMatch() {
        assertGlobMatches("food/bar?" /* => nothing */)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStartsWithStar() {
        assertGlobMatches("*oo",  /* => */"foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStartsWithStarWithMiddleStar() {
        assertGlobMatches("*f*o",  /* => */"foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEndsWithStar() {
        assertGlobMatches("foo*",  /* => */"foo", "food", "fool")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEndsWithStarWithMiddleStar() {
        assertGlobMatches("f*oo*",  /* => */"foo", "food", "fool")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMiddleStar() {
        assertGlobMatches("f*o",  /* => */"foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTwoMiddleStars() {
        assertGlobMatches("f*o*o",  /* => */"foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleStarPatternWithNamedChild() {
        assertGlobMatches("*/bar",  /* => */"foo/bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleStarPatternWithChildGlob() {
        assertGlobMatches(
            "*/bar*",  /* => */"foo/bar", "foo/barnacle", "food/barnacle", "fool/barnacle"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleStarAsChildGlob() {
        assertGlobMatches("foo/*/wiz",  /* => */"foo/bar/wiz", "foo/barnacle/wiz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoAsteriskAndFilesDontExist() {
        // Note un-UNIX like semantics:
        assertGlobMatches("ceci/n'est/pas/une/globbe" /* => nothing */)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleAsteriskUnderNonexistentDirectory() {
        // Note un-UNIX like semantics:
        assertGlobMatches("not-there/*" /* => nothing */)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilteredResults_noDirs() {
        assertThat(
            Builder(tmpPath, FilesystemOps.DIRECT)
                .addPatterns("**")
                .setPathDiscriminator(
                    TestUnixGlobPathDiscriminator(
                        java.util.function.Predicate { p: Path? ->  /* traversalPredicate= */true },  /* resultPredicate= */
                        java.util.function.BiPredicate { p: Path?, isDir: Boolean? -> !isDir!! })
                )
                .globInterruptible()
        )
            .containsExactlyElementsIn(resolvePaths("foo/bar/wiz/file"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilteredResults_noFiles() {
        assertThat(
            Builder(tmpPath, FilesystemOps.DIRECT)
                .addPatterns("**")
                .setPathDiscriminator(
                    TestUnixGlobPathDiscriminator( /* traversalPredicate= */
                        java.util.function.Predicate { p: Path? -> true },  /* resultPredicate= */
                        java.util.function.BiPredicate { p: Path?, isDir: Boolean? -> isDir })
                )
                .globInterruptible()
        )
            .containsExactlyElementsIn(
                resolvePaths(
                    "",
                    "foo",
                    "foo/bar",
                    "foo/bar/wiz",
                    "foo/barnacle",
                    "foo/barnacle/wiz",
                    "food",
                    "food/barnacle",
                    "food/barnacle/wiz",
                    "fool",
                    "fool/barnacle",
                    "fool/barnacle/wiz"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilteredResults_pathMatch() {
        val wanted: Path? = tmpPath.getRelative("food/barnacle/wiz")

        assertThat(
            Builder(tmpPath, FilesystemOps.DIRECT)
                .addPatterns("**")
                .setPathDiscriminator(
                    TestUnixGlobPathDiscriminator( /* traversalPredicate= */
                        java.util.function.Predicate { p: Path? -> true },  /* resultPredicate= */
                        java.util.function.BiPredicate { path: Path?, isDir: Boolean? -> path.equals(wanted) })
                )
                .globInterruptible()
        )
            .containsExactly(wanted)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTraversal_onlyFoo() {
        // Use a directory traversal filter to only walk the root dir and "foo", but not "fool or "food"
        // So we'll end up the directories, "fool" and "food", but not sub-dirs.
        assertThat(
            Builder(tmpPath, FilesystemOps.DIRECT)
                .addPatterns("**")
                .setPathDiscriminator(
                    TestUnixGlobPathDiscriminator( /* traversalPredicate= */
                        java.util.function.Predicate { path: Path? ->
                            path.equals(tmpPath)
                                    || path.getPathString().contains("foo/")
                                    || path.getPathString().endsWith("foo")
                        },  /* resultPredicate= */
                        java.util.function.BiPredicate { x: Path?, isDir: Boolean? -> true })
                )
                .globInterruptible()
        )
            .containsExactlyElementsIn(
                resolvePaths(
                    "",
                    "foo",
                    "foo/bar",
                    "foo/bar/wiz",
                    "foo/bar/wiz/file",
                    "foo/barnacle",
                    "foo/barnacle/wiz",
                    "fool",
                    "food"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobWithNonExistentBase() {
        val globResult: MutableCollection<Path?>? =
            Builder(fs.getPath("/does/not/exist"), FilesystemOps.DIRECT)
                .addPattern("*.txt")
                .globInterruptible()
        Truth.assertThat(globResult).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobUnderFile() {
        assertGlobMatches("foo/bar/wiz/file/*" /* => nothing */)
    }

    @Throws(java.lang.Exception::class)
    private fun assertGlobMatches(pattern: String?, vararg expecteds: String) {
        assertGlobMatches(mutableSetOf<String?>(pattern), *expecteds)
    }

    @Throws(java.lang.Exception::class)
    private fun assertGlobMatches(pattern: MutableCollection<String?>?, vararg expecteds: String) {
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
            val file: Path? = if (expected == ".") tmpPath else tmpPath.getRelative(expected)
            expectedFiles.add(file)
        }
        return expectedFiles
    }

    @org.junit.Test
    fun testIOFailureOnStat() {
        val syscallCache: SyscallCache =
            object : SyscallCache() {
                @Throws(IOException::class)
                public override fun statIfFound(path: Path?, symlinks: Symlinks?): FileStatus? {
                    throw IOException("EIO")
                }

                public override fun readdir(path: Path?): MutableCollection<Dirent?>? {
                    throw java.lang.IllegalStateException()
                }

                public override fun getType(path: Path?, symlinks: Symlinks?): DirentTypeWithSkip? {
                    throw java.lang.IllegalStateException()
                }

                public override fun clear() {
                    throw java.lang.IllegalStateException()
                }
            }

        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    Builder(tmpPath, syscallCache).addPattern("foo/bar/wiz/file").glob()
                })
        Truth.assertThat(e).hasMessageThat().isEqualTo("EIO")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobWithoutWildcardsDoesNotCallReaddir() {
        val filesystemOps: FilesystemOps =
            object : FilesystemOps() {
                @Throws(IOException::class)
                public override fun statIfFound(path: Path?): FileStatus? {
                    return FilesystemOps.DIRECT.statIfFound(path)
                }

                public override fun readdir(path: Path?): MutableCollection<Dirent?>? {
                    throw java.lang.IllegalStateException()
                }
            }

        assertThat(Builder(tmpPath, filesystemOps).addPattern("foo/bar/wiz/file").glob())
            .containsExactly(tmpPath.getRelative("foo/bar/wiz/file"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIllegalPatterns() {
        assertIllegalPattern("foo**bar")
        assertIllegalPattern("")
        assertIllegalPattern(".")
        assertIllegalPattern("/foo")
        assertIllegalPattern("./foo")
        assertIllegalPattern("foo/")
        assertIllegalPattern("foo/./bar")
        assertIllegalPattern("../foo/bar")
        assertIllegalPattern("foo//bar")
    }

    /** Tests that globs can contain Java regular expression special characters  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSpecialRegexCharacter() {
        val tmpPath2: Path = fs.getPath("/globtmp2")
        tmpPath2.createDirectoryAndParents()
        val aDotB: Path? = tmpPath2.getChild("a.b")
        FileSystemUtils.createEmptyFile(aDotB)
        val aPlusB: Path? = tmpPath2.getChild("a+b")
        FileSystemUtils.createEmptyFile(aPlusB)
        val aWordCharacterB: Path? = tmpPath2.getChild("a\\wb")
        FileSystemUtils.createEmptyFile(aWordCharacterB)
        val disjunctionsAndBrackets: Path? = tmpPath2.getChild("aab|a{1,2}[ab]")
        FileSystemUtils.createEmptyFile(disjunctionsAndBrackets)
        val lineNoise: Path? = tmpPath2.getChild("\\|}[{[].+")
        FileSystemUtils.createEmptyFile(lineNoise)
        FileSystemUtils.createEmptyFile(tmpPath2.getChild("aab"))
        // Note: these contain two asterisks because otherwise a RE is not built,
        // as an optimization.
        assertThat(
            Builder(tmpPath2, FilesystemOps.DIRECT)
                .addPattern("*a.b*")
                .globInterruptible()
        )
            .containsExactly(aDotB)
        assertThat(
            Builder(tmpPath2, FilesystemOps.DIRECT)
                .addPattern("*a+b*")
                .globInterruptible()
        )
            .containsExactly(aPlusB)
        assertThat(
            Builder(tmpPath2, FilesystemOps.DIRECT)
                .addPattern("*a\\wb*")
                .globInterruptible()
        )
            .containsExactly(aWordCharacterB)
        assertThat(
            Builder(tmpPath2, FilesystemOps.DIRECT)
                .addPattern("*aab|a{1,2}[ab]*")
                .globInterruptible()
        )
            .containsExactly(disjunctionsAndBrackets)
        assertThat(
            Builder(tmpPath2, FilesystemOps.DIRECT)
                .addPattern("*\\|}[{[].+*")
                .globInterruptible()
        )
            .containsExactly(lineNoise)
    }

    /**
     * Test that '(' and ')' in glob patterns are ignored if the glob is compiled to regexp.
     * 
     * 
     * TODO(b/154003471) Change the behavior and start treating '(' and ')' as literal characters
     * in glob patterns. This will require an incompatible flag.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParenthesesInRegex() {
        val tmpPath3: Path = fs.getPath("/globtmp3")
        tmpPath3.createDirectoryAndParents()
        val fooBar: Path? = tmpPath3.getChild("foo bar")
        FileSystemUtils.createEmptyFile(fooBar)
        val fooBarInParentheses: Path? = tmpPath3.getChild("foo (bar)")
        FileSystemUtils.createEmptyFile(fooBarInParentheses)
        // Note: these contain two asterisks because otherwise a RE is not built,
        // as an optimization.
        assertThat(
            Builder(tmpPath3, FilesystemOps.DIRECT)
                .addPattern("*foo (bar)*")
                .globInterruptible()
        )
            .containsExactly(fooBar)
        assertThat(
            Builder(tmpPath3, FilesystemOps.DIRECT)
                .addPattern("(*foo bar*)")
                .globInterruptible()
        )
            .containsExactly(fooBar)
        assertThat(
            Builder(tmpPath3, FilesystemOps.DIRECT)
                .addPattern("*)((foo ))bar(*")
                .globInterruptible()
        )
            .containsExactly(fooBar)
        assertThat(
            Builder(tmpPath3, FilesystemOps.DIRECT)
                .addPattern("*foo (bar*")
                .globInterruptible()
        )
            .containsExactly(fooBar)
        assertThat(
            Builder(tmpPath3, FilesystemOps.DIRECT)
                .addPattern("*foo bar*)")
                .globInterruptible()
        )
            .containsExactly(fooBar)
        // Note: the following glob pattern doesn't contain asterisks, and a RE wouldn't be expected to
        // be built.
        assertThat(
            Builder(tmpPath3, FilesystemOps.DIRECT)
                .addPattern("foo (bar)")
                .globInterruptible()
        )
            .containsExactly(fooBarInParentheses)
    }

    @org.junit.Test
    fun testMatchesCallWithNoCache() {
        assertThat(UnixGlob.matches("*a*b", "CaCb", null)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultiplePatterns() {
        assertGlobMatches(com.google.common.collect.Lists.newArrayList<String?>("foo", "fool"), "foo", "fool")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMatcherMethodRecursiveBelowDir() {
        FileSystemUtils.createEmptyFile(tmpPath.getRelative("foo/file"))
        val pattern = "foo/**/*"
        assertThat(UnixGlob.matches(pattern, "foo/bar")).isTrue()
        assertThat(UnixGlob.matches(pattern, "foo/bar/baz")).isTrue()
        assertThat(UnixGlob.matches(pattern, "foo")).isFalse()
        assertThat(UnixGlob.matches(pattern, "foob")).isFalse()
        assertThat(UnixGlob.matches("**/foo", "foo")).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultiplePatternsWithOverlap() {
        assertGlobMatchesAnyOrder(com.google.common.collect.Lists.newArrayList<String?>("food", "foo?"), "food", "fool")
        assertGlobMatchesAnyOrder(com.google.common.collect.Lists.newArrayList<String?>("food", "?ood", "f??d"), "food")
        Truth.assertThat(resolvePaths("food", "fool", "foo"))
            .containsExactlyElementsIn(
                Builder(tmpPath, FilesystemOps.DIRECT)
                    .addPatterns("food", "xxx", "*")
                    .glob()
            )
    }

    @Throws(java.lang.Exception::class)
    private fun assertGlobMatchesAnyOrder(patterns: java.util.ArrayList<String?>?, vararg paths: String) {
        Truth.assertThat(resolvePaths(*paths))
            .containsExactlyElementsIn(
                Builder(tmpPath, FilesystemOps.DIRECT)
                    .addPatterns(patterns)
                    .globInterruptible()
            )
    }

    @Throws(java.lang.Exception::class)
    private fun assertIllegalPattern(pattern: String?) {
        val e: UnixGlob.BadPattern? =
            org.junit.Assert.assertThrows<T?>(
                UnixGlob.BadPattern::class.java,
                org.junit.function.ThrowingRunnable {
                    Builder(tmpPath, FilesystemOps.DIRECT)
                        .addPattern(pattern)
                        .globInterruptible()
                })
        assertThat(e).hasMessageThat().containsMatch("in glob pattern")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHiddenFiles() {
        for (dir in com.google.common.collect.ImmutableList.of<String?>(".hidden", "..also.hidden", "not.hidden")) {
            tmpPath.getRelative(dir).createDirectoryAndParents()
        }

        // Note that these are not in the result: ".", ".."
        assertGlobMatches("*", "not.hidden", "foo", "fool", "food", ".hidden", "..also.hidden")

        assertGlobMatches("*.hidden", "not.hidden")

        assertGlobMatches(".*also*", "..also.hidden")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIOException() {
        throwOnReaddir = fs.getPath("/throw_on_readdir")
        throwOnReaddir.createDirectory()
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable {
                Builder(throwOnReaddir, FilesystemOps.DIRECT).addPattern("**").glob()
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFastFailureithInterrupt() {
        java.lang.Thread.currentThread().interrupt()
        throwOnStat = tmpPath
        val e: FileNotFoundException? =
            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { Builder(tmpPath, FilesystemOps.DIRECT).glob() })
        Truth.assertThat(e).hasMessageThat().contains("globtmp")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCheckCanBeInterrupted() {
        val mainThread: java.lang.Thread = java.lang.Thread.currentThread()
        val executor: ThreadPoolExecutor = Executors.newFixedThreadPool(10) as ThreadPoolExecutor

        val interruptExactlyOnce: AtomicBoolean = AtomicBoolean(false)
        // Ensures the cancellation occurs while the glob is running.
        val waitInPredicate: CountDownLatch = CountDownLatch(1)

        val interrupterPredicate: java.util.function.Predicate<Path?> =
            object : java.util.function.Predicate<Path?> {
                override fun test(input: Path?): Boolean {
                    if (interruptExactlyOnce.compareAndSet(false, true)) {
                        mainThread.interrupt()
                    } else {
                        try {
                            Truth.assertThat(
                                waitInPredicate.await(
                                    com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                                    TimeUnit.SECONDS
                                )
                            ).isTrue()
                        } catch (e: java.lang.InterruptedException) {
                            throw java.lang.AssertionError(e)
                        }
                    }
                    return true
                }
            }

        val interrupterDiscriminator: UnixGlobPathDiscriminator =
            TestUnixGlobPathDiscriminator( /*traversalPredicate=*/
                interrupterPredicate,  /*resultPredicate=*/
                java.util.function.BiPredicate { x: Path?, isDir: Boolean? -> true })

        val globResult: java.util.concurrent.Future<*> =
            Builder(tmpPath, FilesystemOps.DIRECT)
                .addPattern("**")
                .setPathDiscriminator(interrupterDiscriminator)
                .setExecutor(executor)
                .globAsync()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { globResult.get() })

        globResult.cancel(true)
        waitInPredicate.countDown()

        org.junit.Assert.assertThrows<CancellationException?>(
            CancellationException::class.java,
            org.junit.function.ThrowingRunnable {
                com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly(globResult)
            })

        java.lang.Thread.interrupted()
        Truth.assertThat(executor.isShutdown()).isFalse()
        executor.shutdown()
        Truth.assertThat(
            executor.awaitTermination(
                com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
        ).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCheckCannotBeInterrupted() {
        val mainThread: java.lang.Thread = java.lang.Thread.currentThread()
        val executor: ThreadPoolExecutor = Executors.newFixedThreadPool(10) as ThreadPoolExecutor
        val sentInterrupt: AtomicBoolean = AtomicBoolean(false)

        val interrupterPredicate: java.util.function.Predicate<Path?> =
            object : java.util.function.Predicate<Path?> {
                override fun test(input: Path?): Boolean {
                    if (!sentInterrupt.getAndSet(true)) {
                        mainThread.interrupt()
                    }
                    return true
                }
            }

        val interrupterDiscriminator: UnixGlobPathDiscriminator =
            TestUnixGlobPathDiscriminator( /*traversalPredicate=*/
                interrupterPredicate,  /*resultPredicate=*/
                java.util.function.BiPredicate { x: Path?, isDir: Boolean? -> true })

        val result: MutableList<Path?>? =
            Builder(tmpPath, FilesystemOps.DIRECT)
                .addPatterns("**", "*")
                .setPathDiscriminator(interrupterDiscriminator)
                .setExecutor(executor)
                .glob()

        // In the non-interruptible case, the interrupt bit should be set, but the
        // glob should return the correct set of full results.
        Truth.assertThat(java.lang.Thread.interrupted()).isTrue()
        Truth.assertThat(result)
            .containsExactlyElementsIn(
                resolvePaths(
                    ".",
                    "foo",
                    "foo/bar",
                    "foo/bar/wiz",
                    "foo/bar/wiz/file",
                    "foo/barnacle",
                    "foo/barnacle/wiz",
                    "food",
                    "food/barnacle",
                    "food/barnacle/wiz",
                    "fool",
                    "fool/barnacle",
                    "fool/barnacle/wiz"
                )
            )

        Truth.assertThat(executor.isShutdown()).isFalse()
        executor.shutdown()
        Truth.assertThat(
            executor.awaitTermination(
                com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
        ).isTrue()
    }

    @org.junit.Test
    @Throws(UnixGlob.BadPattern::class)
    fun testExcludeFiltering() {
        var paths: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("a/A.java", "a/B.java", "a/b/C.java", "c.cc")
        Truth.assertThat(removeExcludes(paths, "**/*.java")).containsExactly("c.cc")
        Truth.assertThat(removeExcludes(paths, "a/**/*.java")).containsExactly("c.cc")
        Truth.assertThat(removeExcludes(paths, "**/nomatch.*")).containsAtLeastElementsIn(paths)
        Truth.assertThat(removeExcludes(paths, "a/A.java")).containsExactly("a/B.java", "a/b/C.java", "c.cc")
        Truth.assertThat(removeExcludes(paths, "a/?.java")).containsExactly("a/b/C.java", "c.cc")
        Truth.assertThat(removeExcludes(paths, "a/*/C.java")).containsExactly("a/A.java", "a/B.java", "c.cc")
        Truth.assertThat(removeExcludes(paths, "**")).isEmpty()
        Truth.assertThat(removeExcludes(paths, "**/**")).isEmpty()

        // Test filenames that look like code patterns.
        paths = com.google.common.collect.ImmutableList.of<String?>(
            "a/A.java",
            "a/B.java",
            "a/b/*.java",
            "a/b/C.java",
            "c.cc"
        )
        Truth.assertThat(removeExcludes(paths, "**/*.java")).containsExactly("c.cc")
        Truth.assertThat(removeExcludes(paths, "**/A.java", "**/B.java", "**/C.java"))
            .containsExactly("a/b/*.java", "c.cc")
    }

    companion object {
        @Throws(UnixGlob.BadPattern::class)
        private fun removeExcludes(
            paths: com.google.common.collect.ImmutableList<String?>,
            vararg excludes: String?
        ): MutableCollection<String?> {
            val pathSet: HashSet<String?> = HashSet<String?>(paths)
            UnixGlob.removeExcludes(pathSet, com.google.common.collect.ImmutableList.< E > copyOf < E ? > (excludes))
            return pathSet
        }
    }
}
