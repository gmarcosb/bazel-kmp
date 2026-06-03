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

import com.google.devtools.build.lib.actions.ThreadStateReceiver

/**
 * Tests for [GlobCache]
 */
@RunWith(JUnit4::class)
class GlobCacheTest {
    private val scratch: Scratch = Scratch("/workspace")

    private var packageDirectory: Path? = null
    private var buildFile: Path? = null
    private var cacheThreadPool: ExecutorService? = null
    private var cache: GlobCache? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createFiles() {
        buildFile = scratch.file(
            "isolated/BUILD",
            "# contents don't matter in this test"
        )
        scratch.file(
            "isolated/sub/BUILD",
            "# contents don't matter in this test"
        )

        packageDirectory = buildFile.getParentDirectory()

        scratch.file(
            "isolated/first.txt",
            "# this is first.txt"
        )

        scratch.file(
            "isolated/second.txt",
            "# this is second.txt"
        )

        scratch.file(
            "isolated/first.js",
            "# this is first.js"
        )

        scratch.file(
            "isolated/second.js",
            "# this is second.js"
        )

        // Files in subdirectories
        scratch.file(
            "isolated/foo/first.js",
            "# this is foo/first.js"
        )

        scratch.file(
            "isolated/foo/second.js",
            "# this is foo/second.js"
        )

        scratch.file(
            "isolated/bar/first.js",
            "# this is bar/first.js"
        )

        scratch.file(
            "isolated/bar/second.js",
            "# this is bar/second.js"
        )

        scratch.file(
            "isolated/sub/sub.js",
            "# this is sub/sub.js"
        )

        createCache()
    }

    @org.junit.After
    fun shutDownThreadPoolIfExists() {
        if (cacheThreadPool != null) {
            cacheThreadPool.shutdownNow()
        }
    }

    private fun createCache(vararg ignoredDirectories: PathFragment?) {
        shutDownThreadPoolIfExists()
        cacheThreadPool = Executors.newFixedThreadPool(10)
        cache =
            GlobCache(
                packageDirectory,
                PackageIdentifier.createInMainRepo("isolated"),
                IgnoredSubdirectories.of(com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (ignoredDirectories)),
                object : CachingPackageLocator() {
                    public override fun getBuildFileForPackage(packageId: PackageIdentifier): Path? {
                        val packageName: String = packageId.getPackageFragment().getPathString()
                        if (packageName == "isolated") {
                            return scratch.resolve("isolated/BUILD")
                        } else if (packageName == "isolated/sub") {
                            return scratch.resolve("isolated/sub/BUILD")
                        } else {
                            return null
                        }
                    }

                    public override fun getBaseNameForLoadedPackage(packageName: PackageIdentifier): String? {
                        val buildFileForPackage: Path? = getBuildFileForPackage(packageName)
                        return if (buildFileForPackage == null) null else buildFileForPackage.getBaseName()
                    }
                },
                SyscallCache.NO_CACHE,
                cacheThreadPool,
                -1,
                ThreadStateReceiver.NULL_INSTANCE
            )
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun deleteFiles() {
        scratch.getFileSystem().getPath("/").deleteTreesBelow()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIgnoredDirectory() {
        createCache(PathFragment.create("isolated/foo"))
        val paths: MutableList<Path> = cache.safeGlobUnsorted("**/*.js", Globber.Operation.FILES).get()
        assertPathsAre(
            paths,
            "/workspace/isolated/first.js",
            "/workspace/isolated/second.js",
            "/workspace/isolated/bar/first.js",
            "/workspace/isolated/bar/second.js"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSafeGlob() {
        val paths: MutableList<Path> = cache.safeGlobUnsorted("*.js", Globber.Operation.FILES_AND_DIRS).get()
        assertPathsAre(
            paths,
            "/workspace/isolated/first.js", "/workspace/isolated/second.js"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSafeGlobInvalidPattern() {
        val invalidPattern = "Foo?.txt"
        org.junit.Assert.assertThrows<T?>(
            BadGlobException::class.java,
            org.junit.function.ThrowingRunnable {
                cache.safeGlobUnsorted(
                    invalidPattern,
                    Globber.Operation.FILES_AND_DIRS
                ).get()
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetGlob() {
        val glob: MutableList<String?>? = cache.getGlobUnsorted("*.js")
        Truth.assertThat(glob).containsExactly("first.js", "second.js")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetGlob_subdirectory() {
        val glob: MutableList<String?>? = cache.getGlobUnsorted("foo/*.js")
        Truth.assertThat(glob).containsExactly("foo/first.js", "foo/second.js")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetKeySet() {
        assertThat(cache.getKeySet()).isEmpty()

        cache.getGlobUnsorted("*.java")
        assertThat(cache.getKeySet())
            .containsExactly(Pair.of("*.java", Globber.Operation.FILES_AND_DIRS))

        cache.getGlobUnsorted("*.java")
        assertThat(cache.getKeySet())
            .containsExactly(Pair.of("*.java", Globber.Operation.FILES_AND_DIRS))

        cache.getGlobUnsorted("*.js")
        assertThat(cache.getKeySet())
            .containsExactly(
                Pair.of("*.java", Globber.Operation.FILES_AND_DIRS),
                Pair.of("*.js", Globber.Operation.FILES_AND_DIRS)
            )

        cache.getGlobUnsorted("*.java", Globber.Operation.FILES)
        assertThat(cache.getKeySet())
            .containsExactly(
                Pair.of("*.java", Globber.Operation.FILES_AND_DIRS),
                Pair.of("*.js", Globber.Operation.FILES_AND_DIRS),
                Pair.of("*.java", Globber.Operation.FILES)
            )

        org.junit.Assert.assertThrows<T?>(
            BadGlobException::class.java,
            org.junit.function.ThrowingRunnable { cache.getGlobUnsorted("invalid?") })
        assertThat(cache.getKeySet())
            .containsExactly(
                Pair.of("*.java", Globber.Operation.FILES_AND_DIRS),
                Pair.of("*.js", Globber.Operation.FILES_AND_DIRS),
                Pair.of("*.java", Globber.Operation.FILES)
            )

        cache.getGlobUnsorted("foo/first.*")
        assertThat(cache.getKeySet())
            .containsExactly(
                Pair.of("*.java", Globber.Operation.FILES_AND_DIRS),
                Pair.of("*.java", Globber.Operation.FILES),
                Pair.of("*.js", Globber.Operation.FILES_AND_DIRS),
                Pair.of("foo/first.*", Globber.Operation.FILES_AND_DIRS)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlob() {
        assertEmpty(cache.globUnsorted(list("*.java"), NONE, Globber.Operation.FILES, true))

        assertThat(cache.globUnsorted(list("*.*"), NONE, Globber.Operation.FILES, true))
            .containsExactly("first.js", "first.txt", "second.js", "second.txt")

        assertThat(cache.globUnsorted(list("*.*"), list("first.js"), Globber.Operation.FILES, true))
            .containsExactly("first.txt", "second.js", "second.txt")

        assertThat(cache.globUnsorted(list("*.txt", "first.*"), NONE, Globber.Operation.FILES, true))
            .containsExactly("first.txt", "second.txt", "first.js")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRecursiveGlobDoesNotMatchSubpackage() {
        val glob: MutableList<String?>? = cache.getGlobUnsorted("**/*.js")
        Truth.assertThat(glob).containsExactly(
            "first.js", "second.js", "foo/first.js", "bar/first.js",
            "foo/second.js", "bar/second.js"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleFileExclude_star() {
        assertThat(
            cache.globUnsorted(
                list("*"), list("first.txt"), Globber.Operation.FILES_AND_DIRS, true
            )
        )
            .containsExactly("BUILD", "bar", "first.js", "foo", "second.js", "second.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleFileExclude_starStar() {
        assertThat(
            cache.globUnsorted(
                list("**"), list("first.txt"), Globber.Operation.FILES_AND_DIRS, true
            )
        )
            .containsExactly(
                "BUILD",
                "bar",
                "bar/first.js",
                "bar/second.js",
                "first.js",
                "foo",
                "foo/first.js",
                "foo/second.js",
                "second.js",
                "second.txt"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExcludeAll_star() {
        assertThat(cache.globUnsorted(list("*"), list("*"), Globber.Operation.FILES_AND_DIRS, true))
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExcludeAll_star_noMatchesAnyway() {
        assertThat(cache.globUnsorted(list("nope"), list("*"), Globber.Operation.FILES_AND_DIRS, true))
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExcludeAll_starStar() {
        assertThat(cache.globUnsorted(list("**"), list("**"), Globber.Operation.FILES_AND_DIRS, true))
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExcludeAll_manual() {
        assertThat(
            cache.globUnsorted(
                list("**"), list("*", "*/*", "*/*/*"), Globber.Operation.FILES_AND_DIRS, true
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleFileExcludeDoesntMatch() {
        assertThat(
            cache.globUnsorted(
                list("first.txt"), list("nope.txt"), Globber.Operation.FILES_AND_DIRS, true
            )
        )
            .containsExactly("first.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExcludeDirectory() {
        assertThat(cache.globUnsorted(list("foo/*"), NONE, Globber.Operation.FILES, true))
            .containsExactly("foo/first.js", "foo/second.js")
        assertThat(
            cache.globUnsorted(list("foo/*"), list("foo"), Globber.Operation.FILES_AND_DIRS, true)
        )
            .containsExactly("foo/first.js", "foo/second.js")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testChildGlobWithChildExclude() {
        assertThat(
            cache.globUnsorted(
                list("foo/*"), list("foo/*"), Globber.Operation.FILES_AND_DIRS, true
            )
        )
            .isEmpty()
        assertThat(
            cache.globUnsorted(
                list("foo/first.js", "foo/second.js"),
                list("foo/*"),
                Globber.Operation.FILES_AND_DIRS,
                true
            )
        )
            .isEmpty()
        assertThat(
            cache.globUnsorted(
                list("foo/first.js"), list("foo/first.js"), Globber.Operation.FILES_AND_DIRS, true
            )
        )
            .isEmpty()
        assertThat(
            cache.globUnsorted(
                list("foo/first.js"), list("*/first.js"), Globber.Operation.FILES_AND_DIRS, true
            )
        )
            .isEmpty()
        assertThat(
            cache.globUnsorted(
                list("foo/first.js"), list("*/*"), Globber.Operation.FILES_AND_DIRS, true
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubpackages_noWildcard() {
        assertThat(cache.globUnsorted(list("sub/sub.js"), list(), Globber.Operation.SUBPACKAGES, true))
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubpackages_simpleDoubleStar() {
        assertThat(cache.globUnsorted(list("**"), list(), Globber.Operation.SUBPACKAGES, true))
            .containsExactly("sub")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubpackages_onlySub() {
        assertThat(cache.globUnsorted(list("sub"), list(), Globber.Operation.SUBPACKAGES, true))
            .containsExactly("sub")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubpackages_singleStarsAfterSub() {
        assertThat(cache.globUnsorted(list("sub/*"), list(), Globber.Operation.SUBPACKAGES, true))
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubpackages_doubleStarsAfterSub() {
        assertThat(cache.globUnsorted(list("sub/**"), list(), Globber.Operation.SUBPACKAGES, true))
            .containsExactly("sub")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubpackages_twoDoubleStarsAfterSub() {
        // Both `**`s are considered to match no path fragments.
        assertThat(cache.globUnsorted(list("sub/**/**"), list(), Globber.Operation.SUBPACKAGES, true))
            .containsExactly("sub")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubpackages_doubleStarsAndOtherPathAfterSub() {
        assertThat(cache.globUnsorted(list("sub/**/foo"), list(), Globber.Operation.SUBPACKAGES, true))
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubpackages_doubleStarWithTrailingPattern() {
        assertThat(cache.globUnsorted(list("**/bar"), list(), Globber.Operation.SUBPACKAGES, true))
            .isEmpty()
    }

    private fun assertEmpty(glob: MutableCollection<*>?) {
        Truth.assertThat(glob).isEmpty()
    }

    private fun assertPathsAre(paths: MutableList<Path>, vararg strings: String?) {
        val pathStrings: MutableList<String?> = java.util.ArrayList<String?>()
        for (path in paths) {
            pathStrings.add(path.getPathString())
        }
        Truth.assertThat(pathStrings).containsExactlyElementsIn(java.util.Arrays.asList<String?>(*strings))
    }

    /* syntactic shorthand for Lists.newArrayList(strings) */
    private fun list(vararg strings: String?): MutableList<String?> {
        return com.google.common.collect.Lists.newArrayList<String?>(*strings)
    }

    companion object {
        private val NONE: MutableList<String?> = mutableListOf<String?>()
    }
}
