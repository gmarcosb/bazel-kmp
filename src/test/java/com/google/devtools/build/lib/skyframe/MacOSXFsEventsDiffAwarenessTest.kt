// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories

/** Tests for [MacOSXFsEventsDiffAwareness]  */
@RunWith(JUnit4::class)
class MacOSXFsEventsDiffAwarenessTest {
    private var underTest: MacOSXFsEventsDiffAwareness? = null
    private var watchedPath: Path? = null
    private var watchFsEnabledProvider: OptionsProvider? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        watchedPath = com.google.common.io.Files.createTempDir().getCanonicalFile().toPath()
        underTest =
            MacOSXFsEventsDiffAwareness(
                watchedPath, IgnoredSubdirectories.EMPTY, FsEventsNativeDepsServiceImpl()
            )
        val localDiffOptions: LocalDiffAwareness.Options =
            com.google.devtools.common.options.Options.getDefaults<O>(LocalDiffAwareness.Options::class.java)
        localDiffOptions.watchFS = true
        watchFsEnabledProvider = FakeOptions.of(localDiffOptions)
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun tearDown() {
        underTest.close()
        rmdirs(watchedPath)
    }

    @Throws(IOException::class)
    private fun scratchDir(path: String?) {
        val p: Path = watchedPath.resolve(path)
        p.toFile().mkdirs()
    }

    @Throws(IOException::class)
    private fun scratchFile(path: String?, contents: String = "") {
        val p: Path = watchedPath.resolve(path)
        com.google.common.io.Files.asCharSink(p.toFile(), java.nio.charset.StandardCharsets.UTF_8).write(contents)
    }

    /**
     * Checks that the union of the diffs between the current view and each member of some consecutive
     * sequence of views is the specific set of given files.
     * 
     * @param view1 the view to compare to
     * @param rawPaths the files to expect in the view
     * @return the new view
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(
        IncompatibleViewException::class,
        BrokenDiffAwarenessException::class,
        java.lang.InterruptedException::class
    )
    private fun assertDiff(view1: View?, rawPaths: Iterable<String?>): View? {
        var view1: View? = view1
        val allPaths: MutableSet<PathFragment?> = HashSet<PathFragment?>()
        for (path in rawPaths) {
            allPaths.add(PathFragment.create(path))
        }
        val pathsYetToBeSeen: MutableSet<PathFragment?> = HashSet<PathFragment?>(allPaths)

        // fsevents may be delayed (especially under machine load), which means that we may not notice
        // all file system changes in one go. Try enough times (multiple seconds) for the events to be
        // delivered. Given that each time we call getCurrentView we may get a subset of the total
        // events we expect, track the events we have already seen by subtracting them from the
        // pathsYetToBeSeen set.
        var attempts = 0
        while (true) {
            val view2: View? = underTest.getCurrentView(watchFsEnabledProvider)

            val diff: ModifiedFileSet = underTest.getDiff(view1, view2)
            // If fsevents lost events (e.g. because we weren't fast enough processing them or because
            // too many happened at the same time), there is nothing we can do. Yes, this means that if
            // our fsevents monitor always returns "everything modified", we aren't really testing
            // anything here... but let's assume we don't have such an obvious bug...
            Assume.assumeFalse("Lost events; diff unknown", diff.equals(ModifiedFileSet.EVERYTHING_MODIFIED))

            val modifiedSourceFiles: com.google.common.collect.ImmutableSet<PathFragment?>? = diff.modifiedSourceFiles()
            allPaths.removeAll(modifiedSourceFiles)
            pathsYetToBeSeen.removeAll(modifiedSourceFiles)
            if (pathsYetToBeSeen.isEmpty()) {
                // Found all paths that we wanted to see as modified so now check that we didn't get any
                // extra paths we did not expect.
                if (!allPaths.isEmpty()) {
                    throw java.lang.AssertionError("Paths " + allPaths + " unexpectedly reported as modified")
                }
                return view2
            }

            if (attempts == 600) {
                throw java.lang.AssertionError("Paths " + pathsYetToBeSeen + " not found as modified")
            }
            logger.atInfo().log("Still have to see %d paths", pathsYetToBeSeen.size)
            java.lang.Thread.sleep(100)
            attempts++
            view1 = view2 // getDiff requires views to be sequential if we want to get meaningful data.
        }
    }

    @org.junit.Test
    @Ignore("Test is flaky; see https://github.com/bazelbuild/bazel/issues/10776")
    @Throws(java.lang.Exception::class)
    fun testSimple() {
        val view1: View? = underTest.getCurrentView(watchFsEnabledProvider)

        scratchDir("a/b")
        scratchFile("a/b/c")
        scratchDir("b/c")
        scratchFile("b/c/d")
        val view2: View? = assertDiff(view1, mutableListOf<String?>("a", "a/b", "a/b/c", "b", "b/c", "b/c/d"))

        rmdirs(watchedPath.resolve("a"))
        rmdirs(watchedPath.resolve("b"))
        assertDiff(view2, mutableListOf<String?>("a", "a/b", "a/b/c", "b", "b/c", "b/c/d"))
    }

    @org.junit.Test
    @Ignore("Test is flaky; see https://github.com/bazelbuild/bazel/issues/10776")
    @Throws(java.lang.Exception::class)
    fun testRenameDirectory() {
        scratchDir("dir1")
        scratchFile("dir1/file.c", "first")
        scratchDir("dir2")
        scratchFile("dir2/file.c", "second")
        val view1: View? = underTest.getCurrentView(watchFsEnabledProvider)

        java.nio.file.Files.move(watchedPath.resolve("dir1"), watchedPath.resolve("dir3"))
        java.nio.file.Files.move(watchedPath.resolve("dir2"), watchedPath.resolve("dir1"))
        assertDiff(
            view1, mutableListOf<String?>("dir1", "dir1/file.c", "dir2", "dir2/file.c", "dir3", "dir3/file.c")
        )
    }

    @org.junit.Test
    @Ignore("Test is flaky; see https://github.com/bazelbuild/bazel/issues/10776")
    @Throws(java.lang.Exception::class)
    fun testStress() {
        val view1: View? = underTest.getCurrentView(watchFsEnabledProvider)

        // Attempt to cause fsevents to drop events by performing a lot of concurrent file accesses
        // which then may result in our own callback in fsevents.cc not being able to keep up.
        // There is no guarantee that we'll trigger this condition, but on 2020-02-28 on a Mac Pro
        // 2013, this happened pretty predictably with the settings below.
        logger.atInfo().log("Starting file creation under %s", watchedPath)
        val executor: ExecutorService = Executors.newCachedThreadPool()
        val nThreads = 100
        val nFilesPerThread = 100
        val dirToFilesToCreate: com.google.common.collect.Multimap<String?, String?> =
            com.google.common.collect.HashMultimap.create<String?, String?>()
        for (i in 0..<nThreads) {
            val dir = "" + i
            for (j in 0..<nFilesPerThread) {
                val file = dir + "/" + j
                dirToFilesToCreate.put(dir, file)
            }
        }
        val latch: CountDownLatch = CountDownLatch(nThreads)
        val firstError: AtomicReference<IOException?> = AtomicReference<IOException?>(null)
        dirToFilesToCreate
            .asMap()
            .forEach { (dir: String?, files: MutableCollection<String?>?) ->
                val unused: java.util.concurrent.Future<*>? =
                    executor.submit(
                        java.lang.Runnable {
                            try {
                                scratchDir(dir)
                                for (file in files!!) {
                                    scratchFile(file)
                                }
                            } catch (e: IOException) {
                                firstError.compareAndSet(null, e)
                            }
                            latch.countDown()
                        })
            }
        latch.await()
        executor.shutdown()
        val e: IOException? = firstError.get()
        if (e != null) {
            throw e
        }

        assertDiff(
            view1,
            com.google.common.collect.Iterables.concat<String?>(
                dirToFilesToCreate.keySet(),
                dirToFilesToCreate.values()
            )
        )
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        @Throws(IOException::class)
        private fun rmdirs(directory: Path?) {
            java.nio.file.Files.walkFileTree(
                directory,
                object : SimpleFileVisitor<Path?>() {
                    @Throws(IOException::class)
                    override fun visitFile(file: Path, attrs: BasicFileAttributes?): FileVisitResult {
                        java.nio.file.Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    @Throws(IOException::class)
                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        java.nio.file.Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
        }
    }
}
