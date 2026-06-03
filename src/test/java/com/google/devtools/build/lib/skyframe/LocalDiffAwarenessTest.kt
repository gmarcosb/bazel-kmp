// Copyright 2022 The Bazel Authors. All rights reserved.
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

/** Integration tests for LocalDiffAwareness.  */
@RunWith(JUnit4::class)
class LocalDiffAwarenessTest {
    private var watchFsEnabledProvider: OptionsProvider? = null
    private var localDiff: LocalDiffAwareness? = null
    private var oldView: DiffAwareness.View? = null
    private var testCaseRoot: Path? = null
    private var testCaseIgnoredDir: Path? = null

    @org.junit.Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    @Before
    @Throws(java.lang.Exception::class)
    fun initializeSettings() {
        val factory: LocalDiffAwareness.Factory =
            Factory(
                com.google.common.collect.ImmutableList.of<String?>(), FsEventsNativeDepsServiceImpl()
            )
        val fileSystem: FileSystem =
            com.google.devtools.build.lib.vfs.util.FileSystems.getNativeFileSystem(DigestHashFunction.SHA256)
        testCaseRoot = fileSystem.getPath(tmp.getRoot().getAbsolutePath())
        testCaseIgnoredDir = testCaseRoot.getChild("ignored-dir")
        testCaseIgnoredDir.createDirectoryAndParents()

        val localDiffOptions: LocalDiffAwareness.Options =
            com.google.devtools.common.options.Options.getDefaults<O>(LocalDiffAwareness.Options::class.java)
        localDiffOptions.watchFS = true
        watchFsEnabledProvider = FakeOptions.of(localDiffOptions)

        localDiff =
            factory.maybeCreate(
                Root.fromPath(testCaseRoot),
                IgnoredSubdirectories.of(
                    com.google.common.collect.ImmutableSet.of<E?>(testCaseIgnoredDir.asFragment().toRelative())
                ),
                watchFsEnabledProvider
            ) as LocalDiffAwareness

        // Ignore test failures when run on a Mac.
        //
        // On a Mac, LocalDiffAwareness.Factory#maybeCreate will produce a MacOSXFsEventsDiffAwareness.
        // There's a known issue with the underlying implementation
        // (https://github.com/bazelbuild/bazel/issues/10776); basically all the test cases in here
        // consistently fail, presumably due to the same underlying issue with FSEvents. Also,
        // MacOSXFsEventsDiffAwareness is already unit-tested separately anyway (although not very well
        // because of the bug).
        Assume.assumeFalse(OS.DARWIN.equals(OS.getCurrent()))
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun closeLocalDiff() {
        localDiff.close()
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun deleteTestCaseRoot() {
        testCaseRoot.deleteTree()
    }

    @Throws(BrokenDiffAwarenessException::class)
    private fun captureFirstView(options: OptionsProvider?) {
        oldView = localDiff.getCurrentView(options)
        com.google.common.base.Preconditions.checkNotNull<Any?>(localDiff)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun areInSequenceWithEverythingModifiedShouldAlwaysReturnFalse() {
        captureFirstView(watchFsEnabledProvider)
        val old: SequentialView? = oldView as SequentialView?
        val everythingMod: SequentialView? = LocalDiffAwareness.EVERYTHING_MODIFIED as SequentialView?
        assertThat(LocalDiffAwareness.areInSequence(old, everythingMod)).isFalse()
        assertThat(LocalDiffAwareness.areInSequence(everythingMod, old)).isFalse()
        assertThat(LocalDiffAwareness.areInSequence(everythingMod, everythingMod)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileAdded() {
        captureFirstView(watchFsEnabledProvider)
        touch("foo.txt")
        ModifiedFileSetChecker().modify("foo.txt").check()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlink() {
        captureFirstView(watchFsEnabledProvider)
        touch("a")
        symlink("b", "a")
        ModifiedFileSetChecker().modify("a").modify("b").check()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkBroken() {
        captureFirstView(watchFsEnabledProvider)
        symlink("b", "a")
        ModifiedFileSetChecker().modify("b").check()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileModified() {
        captureFirstView(watchFsEnabledProvider)

        touch("foo.txt")
        ModifiedFileSetChecker().modify("foo.txt").check()

        ModifiedFileSetChecker().check()

        touch("foo.txt")
        ModifiedFileSetChecker().modify("foo.txt").check()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIgnoredFileModified() {
        captureFirstView(watchFsEnabledProvider)

        touch(testCaseIgnoredDir.getRelative("foo").getPathString())
        ModifiedFileSetChecker().check()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileRemoved() {
        captureFirstView(watchFsEnabledProvider)

        touch("foo.txt")
        ModifiedFileSetChecker().modify("foo.txt").check()

        rm("foo.txt")
        ModifiedFileSetChecker().modify("foo.txt").check()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileAddAndRemove() {
        captureFirstView(watchFsEnabledProvider)

        touch("foo.txt")
        touch("bar.txt")
        ModifiedFileSetChecker().modify("foo.txt").modify("bar.txt").check()

        rm("foo.txt")
        touch("foo.txt")
        ModifiedFileSetChecker().modify("foo.txt").check()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAddDirectory() {
        captureFirstView(watchFsEnabledProvider)

        mkdir("equestria")
        touch("equestria/foo.txt")
        ModifiedFileSetChecker().modify("equestria").modify("equestria/foo.txt").check()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoveDirectory() {
        captureFirstView(watchFsEnabledProvider)

        mkdir("equestria")
        touch("equestria/foo.txt")
        ModifiedFileSetChecker().modify("equestria").modify("equestria/foo.txt").check()

        rm("equestria")
        ModifiedFileSetChecker().modify("equestria").modify("equestria/foo.txt").check()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMoveDirectory() {
        captureFirstView(watchFsEnabledProvider)

        mkdir("equestria")
        touch("equestria/foo.txt")
        ModifiedFileSetChecker().modify("equestria").modify("equestria/foo.txt").check()

        testCaseRoot.getRelative("equestria").renameTo(testCaseRoot.getRelative("equestria2"))
        // The contents of a moved directory are *not* reported as modified.
        ModifiedFileSetChecker()
            .modify("equestria")
            .modify("equestria2")
            .modify("equestria2/foo.txt")
            .check()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLotsOfChanges() {
        captureFirstView(watchFsEnabledProvider)

        mkdir("pre")
        touch("pre/pre.txt")
        ModifiedFileSetChecker().modify("pre").modify("pre/pre.txt").check()

        mkdir("a")
        touch("a/a1.txt")
        touch("a/a2.txt")
        rm("a")
        mkdir("a")
        touch("a/a1.txt")
        touch("a/a2.txt")
        mkdir("a/b")
        touch("a/b/b1.txt")
        ModifiedFileSetChecker()
            .modify("a")
            .modify("a/a1.txt")
            .modify("a/a2.txt")
            .modify("a/b")
            .modify("a/b/b1.txt")
            .check()

        rm("a/b/b1.txt")
        touch("a/b/b2.txt")
        rm("a/b")
        mkdir("a/b")
        rm("a/b")
        mkdir("a/b")
        touch("a/b/b3.txt")
        ModifiedFileSetChecker()
            .modify("a/b")
            .modify("a/b/b1.txt")
            .modify("a/b/b2.txt")
            .modify("a/b/b3.txt")
            .check()

        rm("a/b/b3.txt")
        ModifiedFileSetChecker().modify("a/b/b3.txt").check()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnableWatchFs() {
        val watchFsDisabledProvider: OptionsProvider = createWatchFsDisabledProvider()
        captureFirstView(watchFsDisabledProvider)

        ModifiedFileSetChecker().checkEverythingModified(watchFsDisabledProvider)

        touch("a.txt")
        ModifiedFileSetChecker().checkEverythingModified(watchFsDisabledProvider)

        touch("b.txt")
        ModifiedFileSetChecker().checkEverythingModified(watchFsEnabledProvider)

        touch("c.txt")
        ModifiedFileSetChecker().modify("c.txt").check()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisableWatchFs() {
        val watchFsDisabledProvider: OptionsProvider = createWatchFsDisabledProvider()
        captureFirstView(watchFsEnabledProvider)

        ModifiedFileSetChecker().check()

        org.junit.Assert.assertThrows<T?>(
            BrokenDiffAwarenessException::class.java,
            org.junit.function.ThrowingRunnable { localDiff.getCurrentView(watchFsDisabledProvider) })
    }

    @org.junit.Test
    fun modifiedPathIsntUnderWatchRoot() {
        val otherRootDirectoryNioPath: Path = Paths.get("/notundertestroot")
        Truth.assertThat(otherRootDirectoryNioPath.startsWith(Paths.get(testCaseRoot.getPathString())))
            .isFalse()

        val oldView: View =
            SequentialView(
                localDiff,  /* position= */
                0,  /* modifiedAbsolutePaths= */
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val newView: View =
            SequentialView(
                localDiff,  /* position= */
                1,  /* modifiedAbsolutePaths= */
                com.google.common.collect.ImmutableSet.of<E?>(
                    otherRootDirectoryNioPath.resolve("foo.txt")
                )
            )
        val throwable: Throwable? =
            org.junit.Assert.assertThrows<T?>(
                BrokenDiffAwarenessException::class.java,
                org.junit.function.ThrowingRunnable { localDiff.getDiff(oldView, newView) })
        Truth.assertThat(throwable)
            .hasMessageThat() // Do a round-trip through PathFragment to deal with Windows path separators.
            .contains(
                PathFragment.create("/notundertestroot/foo.txt is not under ").getPathString()
                        + PathFragment.create(testCaseRoot.getPathString()).getPathString()
            )
    }

    @Throws(IOException::class)
    private fun touch(pathString: String?) {
        val path: Path? = testCaseRoot.getRelative(pathString)
        FileSystemUtils.createEmptyFile(path)
        FileSystemUtils.writeIsoLatin1(path, "Sunshine, sunshine, ladybugs awake!")
    }

    @Throws(IOException::class)
    private fun mkdir(pathString: String?) {
        val path: Path = testCaseRoot.getRelative(pathString)
        path.createDirectoryAndParents()
    }

    @Throws(IOException::class)
    private fun rm(pathString: String?) {
        val path: Path = testCaseRoot.getRelative(pathString)
        path.deleteTree()
    }

    @Throws(IOException::class)
    private fun symlink(from: String?, to: String?) {
        val fromPath: Path? = testCaseRoot.getRelative(from)
        val toPath: Path? = testCaseRoot.getRelative(to)
        FileSystemUtils.ensureSymbolicLink(fromPath, toPath)
    }

    private inner class ModifiedFileSetChecker {
        private val modified: MutableSet<PathFragment?> = com.google.common.collect.Sets.newHashSet<PathFragment?>()

        @Throws(java.lang.Exception::class)
        fun check() {
            // Unfortunately, inotify needs a few milliseconds (more than a few in the worst case)
            // after a change to pick up a list of changed files. Trying a few times to make sure.
            for (i in 0..<MAX_RETRY_COUNT) {
                java.lang.Thread.sleep(150)
                val newView: DiffAwareness.View? = localDiff.getCurrentView(watchFsEnabledProvider)
                val modifiedFileSet: ModifiedFileSet = localDiff.getDiff(oldView, newView)
                oldView = newView
                assertThat(modifiedFileSet.treatEverythingAsModified()).isFalse()
                if (modifiedFileSet.modifiedSourceFiles().isEmpty()) {
                    continue
                }
                assertThat(modifiedFileSet.modifiedSourceFiles()).isEqualTo(modified)
                return
            }
            // If we never received any changes, make sure this is what we actually expect.
            Truth.assertThat(modified).isEmpty()
        }

        @Throws(java.lang.Exception::class)
        fun checkEverythingModified(options: OptionsProvider?) {
            val newView: DiffAwareness.View? = localDiff.getCurrentView(options)
            val modifiedFileSet: ModifiedFileSet = localDiff.getDiff(oldView, newView)
            oldView = newView
            assertThat(modifiedFileSet.treatEverythingAsModified()).isTrue()
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun modify(filename: String?): ModifiedFileSetChecker {
            modified.add(PathFragment.create(filename))
            return this
        }
    }

    companion object {
        /** Try this many times to pick up file changes. Inotify needs some nanoseconds of patience.  */
        private const val MAX_RETRY_COUNT = 20

        private fun createWatchFsDisabledProvider(): OptionsProvider {
            val localDiffOptions: LocalDiffAwareness.Options =
                com.google.devtools.common.options.Options.getDefaults<O>(LocalDiffAwareness.Options::class.java)
            localDiffOptions.watchFS = false
            return FakeOptions.of(localDiffOptions)
        }
    }
}
