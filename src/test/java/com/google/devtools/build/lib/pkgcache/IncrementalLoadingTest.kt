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

import com.google.devtools.build.lib.actions.ActionKeyContext

/**
 * Tests for incremental loading; these cover both normal operation and diff awareness, for which a
 * list of modified / added / removed files is available.
 */
@RunWith(JUnit4::class)
class IncrementalLoadingTest {
    protected var tester: PackageLoadingTester? = null

    private var throwOnReaddir: Path? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createTester() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val fs: FileSystem =
            object : InMemoryFileSystem(clock, DigestHashFunction.SHA256) {
                @Throws(IOException::class)
                public override fun readdir(path: PathFragment, followSymlinks: Boolean): MutableCollection<Dirent?> {
                    if (throwOnReaddir != null && throwOnReaddir.asFragment().equals(path)) {
                        throw FileNotFoundException(path.getPathString())
                    }
                    return super.readdir(path, followSymlinks)
                }
            }
        tester = createTester(fs, clock)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoChange() {
        tester!!.addFile(
            "base/BUILD",
            "filegroup(name = 'hello', srcs = ['foo.txt'])"
        )
        tester!!.sync()
        val oldTarget = tester!!.getTarget("//base:hello")
        assertThat(oldTarget).isNotNull()

        tester!!.sync()
        val newTarget = tester!!.getTarget("//base:hello")
        assertThat(newTarget).isSameInstanceAs(oldTarget)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testModifyBuildFile() {
        tester!!.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])")
        tester!!.sync()
        val oldTarget = tester!!.getTarget("//base:hello")

        tester!!.modifyFile("base/BUILD", "filegroup(name = 'hello', srcs = ['bar.txt'])")
        tester!!.sync()
        val newTarget = tester!!.getTarget("//base:hello")
        assertThat(newTarget).isNotSameInstanceAs(oldTarget)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testModifyNonBuildFile() {
        tester!!.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])")
        tester!!.addFile("base/foo.txt", "nothing")
        tester!!.sync()
        val oldTarget = tester!!.getTarget("//base:hello")

        tester!!.modifyFile("base/foo.txt", "other")
        tester!!.sync()
        val newTarget = tester!!.getTarget("//base:hello")
        assertThat(newTarget).isSameInstanceAs(oldTarget)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoveNonBuildFile() {
        tester!!.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])")
        tester!!.addFile("base/foo.txt", "nothing")
        tester!!.sync()
        val oldTarget = tester!!.getTarget("//base:hello")

        tester!!.removeFile("base/foo.txt")
        tester!!.sync()
        val newTarget = tester!!.getTarget("//base:hello")
        assertThat(newTarget).isSameInstanceAs(oldTarget)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testModifySymlinkedFileSamePackage() {
        tester!!.addSymlink("base/BUILD", "mybuild")
        tester!!.addFile("base/mybuild", "filegroup(name = 'hello', srcs = ['foo.txt'])")
        tester!!.sync()
        val oldTarget = tester!!.getTarget("//base:hello")
        tester!!.modifyFile("base/mybuild", "filegroup(name = 'hello', srcs = ['bar.txt'])")
        tester!!.sync()
        val newTarget = tester!!.getTarget("//base:hello")
        assertThat(newTarget).isNotSameInstanceAs(oldTarget)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testModifySymlinkedFileDifferentPackage() {
        tester!!.addSymlink("base/BUILD", "../other/BUILD")
        tester!!.addFile("other/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])")
        tester!!.sync()
        val oldTarget = tester!!.getTarget("//base:hello")

        tester!!.modifyFile("other/BUILD", "filegroup(name = 'hello', srcs = ['bar.txt'])")
        tester!!.sync()
        val newTarget = tester!!.getTarget("//base:hello")
        assertThat(newTarget).isNotSameInstanceAs(oldTarget)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBUILDSymlinkModifiedThenChanges() {
        // We need to ensure that the timestamps of "one" and "two" are different, because Blaze
        // currently does not recognize changes to symlinks if the timestamps of the old and the new
        // file pointed to by the symlink are the same.
        tester!!.addFile("one", "filegroup(name='a', srcs=['1'])")
        tester!!.sync()

        tester!!.addFile("two", "filegroup(name='a', srcs=['2'])")
        tester!!.addSymlink("oldlink", "one")
        tester!!.addSymlink("newlink", "one")
        tester!!.addSymlink("a/BUILD", "../oldlink")
        tester!!.sync()
        val a1 = tester!!.getTarget("//a:a")

        tester!!.modifySymlink("a/BUILD", "../newlink")
        tester!!.sync()

        tester!!.getTarget("//a:a")

        tester!!.modifySymlink("newlink", "two")
        tester!!.sync()

        val a3 = tester!!.getTarget("//a:a")
        assertThat(a3).isNotSameInstanceAs(a1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBUILDFileIsExternalSymlinkAndChanges() {
        tester!!.addFile("/nonroot/file", "filegroup(name='a', srcs=['file'])")
        tester!!.addSymlink("a/BUILD", "/nonroot/file")
        tester!!.sync()

        val a1 = tester!!.getTarget("//a:a")
        tester!!.modifyFile("/nonroot/file", "filegroup(name='a', srcs=['file2'])")
        tester!!.sync()

        val a2 = tester!!.getTarget("//a:a")
        tester!!.sync()

        assertThat(a2).isNotSameInstanceAs(a1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelWithTwoSegmentsAndTotalInvalidation() {
        tester!!.addFile("a/BUILD", "filegroup(name='fg', srcs=['b/c'])")
        tester!!.addFile("a/b/BUILD")
        tester!!.sync()

        val fg1 = tester!!.getTarget("//a:fg")
        tester!!.everythingModified()
        tester!!.sync()

        val fg2 = tester!!.getTarget("//a:fg")
        assertThat(fg2).isSameInstanceAs(fg1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAddGlobFile() {
        tester!!.addFile("base/BUILD", "filegroup(name = 'hello', srcs = glob(['*.txt']))")
        tester!!.addFile("base/foo.txt", "nothing")
        tester!!.sync()
        val oldTarget = tester!!.getTarget("//base:hello")

        tester!!.addFile("base/bar.txt", "also nothing")
        tester!!.sync()
        val newTarget = tester!!.getTarget("//base:hello")
        assertThat(newTarget).isNotSameInstanceAs(oldTarget)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoveGlobFile() {
        tester!!.addFile("base/BUILD", "filegroup(name = 'hello', srcs = glob(['*.txt']))")
        tester!!.addFile("base/foo.txt", "nothing")
        tester!!.addFile("base/bar.txt", "also nothing")
        tester!!.sync()
        val oldTarget = tester!!.getTarget("//base:hello")

        tester!!.removeFile("base/bar.txt")
        tester!!.sync()
        val newTarget = tester!!.getTarget("//base:hello")
        assertThat(newTarget).isNotSameInstanceAs(oldTarget)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageNotInLastBuildReplaced() {
        tester!!.addFile("a/BUILD", "filegroup(name='a', srcs=['bad.sh'])")
        tester!!.sync()
        val a1 = tester!!.getTarget("//a:a")

        tester!!.addFile("b/BUILD", "filegroup(name='b', srcs=['b.sh'])")
        tester!!.modifyFile("a/BUILD", "filegroup(name='a', srcs=['good.sh'])")
        tester!!.sync()
        tester!!.getTarget("//b:b")

        tester!!.sync()
        val a2 = tester!!.getTarget("//a:a")
        assertThat(a2).isNotSameInstanceAs(a1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBrokenSymlinkAddedThenFixed() {
        tester!!.addFile("a/BUILD", "filegroup(name='a', srcs=glob(['**']))")
        tester!!.sync()
        val a1 = tester!!.getTarget("//a:a")

        tester!!.addSymlink("a/b", "../c")
        tester!!.sync()
        tester!!.getTarget("//a:a")

        tester!!.addFile("c")
        tester!!.sync()
        val a3 = tester!!.getTarget("//a:a")
        assertThat(a3).isNotSameInstanceAs(a1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildFileWithSyntaxError() {
        tester!!.addFile(
            "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(xyz='a')"
        )
        tester!!.sync()
        org.junit.Assert.assertThrows<T?>(
            NoSuchThingException::class.java,
            org.junit.function.ThrowingRunnable { tester!!.getTarget("//a:a") })

        tester!!.modifyFile(
            "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name='a')"
        )
        tester!!.sync()
        tester!!.getTarget("//a:a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkedBuildFileWithSyntaxError() {
        tester!!.addFile(
            "a/BUILD.real",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(xyz='a')"
        )
        tester!!.addSymlink("a/BUILD", "BUILD.real")
        tester!!.sync()
        org.junit.Assert.assertThrows<T?>(
            NoSuchThingException::class.java,
            org.junit.function.ThrowingRunnable { tester!!.getTarget("//a:a") })
        tester!!.modifyFile(
            "a/BUILD.real",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name='a')"
        )
        tester!!.sync()
        tester!!.getTarget("//a:a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransientErrorsInGlobbing() {
        val buildFile: Path =
            tester!!.addFile(
                "e/BUILD",
                "load('//test_defs:foo_library.bzl', 'foo_library')",
                "foo_library(name = 'e', srcs = glob(['*.txt']))"
            )
        val parentDir: Path? = buildFile.getParentDirectory()
        tester!!.addFile("e/data.txt")
        throwOnReaddir = parentDir
        tester!!.sync()
        org.junit.Assert.assertThrows<T?>(
            NoSuchPackageException::class.java,
            org.junit.function.ThrowingRunnable { tester!!.getTarget("//e:e") })
        throwOnReaddir = null
        tester!!.sync()
        val target = tester!!.getTarget("//e:e")
        assertThat((target as Rule).containsErrors()).isFalse()
        val globList = (target as Rule).getAttr("srcs") as MutableList<*>?
        Truth.assertThat(globList).containsExactly(Label.parseCanonical("//e:data.txt"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIrrelevantFileInSubdirDoesntReloadPackage() {
        tester!!.addFile(
            "pkg/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'pkg', srcs = glob(['**/*.sh']))"
        )
        tester!!.addFile("pkg/pkg.sh", "#!/bin/bash")
        tester!!.addFile("pkg/bar/bar.sh", "#!/bin/bash")
        val packageoid: Packageoid? = tester!!.getTarget("//pkg:pkg").getPackageoid()
        val pkg: Package? = tester!!.getPackage("pkg")

        // Write file in directory to force reload of top-level glob.
        tester!!.addFile("pkg/irrelevant_file")
        tester!!.addFile("pkg/bar/irrelevant_file") // Subglob is also reloaded.
        assertThat(tester!!.getTarget("//pkg:pkg").getPackageoid()).isSameInstanceAs(packageoid)
        assertThat(tester!!.getPackage("pkg")).isSameInstanceAs(pkg)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMissingPackages() {
        tester!!.sync()

        org.junit.Assert.assertThrows<T?>(
            NoSuchThingException::class.java,
            org.junit.function.ThrowingRunnable { tester!!.getTarget("//a:a") })

        tester!!.addFile(
            "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name='a')"
        )
        tester!!.sync()
        tester!!.getTarget("//a:a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testChangedExternalFile() {
        tester!!.addFile(
            "a/BUILD",
            """
        load("//a:b.bzl", "b")

        b()
        
        """.trimIndent()
        )

        tester!!.addFile(
            "/b.bzl",
            """
        def b():
            pass
        
        """.trimIndent()
        )
        tester!!.addSymlink("a/b.bzl", "/b.bzl")
        tester!!.sync()
        tester!!.getTarget("//a:BUILD")
        val packageOptions: PackageOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(PackageOptions::class.java)
        packageOptions.setCheckExternalOtherFiles(false)
        tester!!.modifyFile("/b.bzl", "ERROR ERROR")
        tester!!.syncWithOptions(packageOptions)
        tester!!.getTarget("//a:BUILD")
        packageOptions.setCheckExternalOtherFiles(true)
        tester!!.syncWithOptions(packageOptions)

        org.junit.Assert.assertThrows<T?>(
            NoSuchThingException::class.java,
            org.junit.function.ThrowingRunnable { tester!!.getTarget("//a:BUILD") })
    }

    internal class PackageLoadingTester(fs: FileSystem, clock: com.google.devtools.build.lib.testutil.ManualClock) {
        private inner class ManualDiffAwareness : DiffAwareness {
            private var lastView: View? = null
            private var currentView: View? = null

            public override fun getCurrentView(options: OptionsProvider?): View? {
                lastView = currentView
                currentView = object : View() {}
                return currentView
            }

            public override fun getDiff(oldView: View?, newView: View?): ModifiedFileSet? {
                if (oldView === lastView && newView === currentView) {
                    return com.google.common.base.Preconditions.checkNotNull<ModifiedFileSet?>(modifiedFileSet)
                } else {
                    return ModifiedFileSet.EVERYTHING_MODIFIED
                }
            }

            public override fun name(): String {
                return "PackageLoadingTester.DiffAwareness"
            }

            public override fun close() {
            }
        }

        private inner class ManualDiffAwarenessFactory : DiffAwareness.Factory {
            public override fun maybeCreate(
                pathEntry: Root, ignoredPaths: IgnoredSubdirectories?, optionsProvider: OptionsProvider?
            ): DiffAwareness? {
                return if (pathEntry.asPath().equals(workspace)) ManualDiffAwareness() else null
            }
        }

        private val clock: com.google.devtools.build.lib.testutil.ManualClock
        private val workspace: Path
        private val outputBase: Path
        private val reporter: com.google.devtools.build.lib.events.Reporter =
            com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
        private val skyframeExecutor: SkyframeExecutor
        private val changes: MutableList<Path> = java.util.ArrayList<Path>()
        private var everythingModified = false
        private var modifiedFileSet: ModifiedFileSet? = null

        init {
            this.clock = clock
            workspace = fs.getPath("/workspace")
            workspace.createDirectory()
            addFile("test_defs/BUILD")
            addFile(
                "test_defs/foo_library.bzl",
                """
          def _impl(ctx):
            pass
          foo_library = rule(
            implementation = _impl,
            attrs = {
              "srcs": attr.label_list(allow_files=True),
              "deps": attr.label_list(),
            },
          )
          
          """.trimIndent()
            )
            outputBase = fs.getPath("/output_base")
            outputBase.createDirectory()
            addFile("WORKSPACE")

            val loadingMock: LoadingMock = LoadingMock.Companion.get()
            val directories: BlazeDirectories =
                BlazeDirectories(
                    ServerDirectories(
                        fs.getPath("/install"), fs.getPath("/output"), fs.getPath("/userRoot")
                    ),
                    workspace,
                    loadingMock.getProductName()
                )
            val ruleClassProvider: ConfiguredRuleClassProvider = loadingMock.createRuleClassProvider()
            val pkgFactory: PackageFactory? =
                loadingMock
                    .getPackageFactoryBuilderForTesting(directories)
                    .setExtraSkyFunctions(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            SkyFunctions.MODULE_FILE,
                            ModuleFileFunction(
                                ruleClassProvider.getBazelStarlarkEnvironment(),
                                directories.getWorkspace(),
                                com.google.common.collect.ImmutableMap.of<K?, V?>()
                            )
                        )
                    )
                    .build(ruleClassProvider, fs)
            skyframeExecutor =
                BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
                    .setPkgFactory(pkgFactory)
                    .setFileSystem(fs)
                    .setDirectories(directories)
                    .setActionKeyContext(ActionKeyContext())
                    .setDiffAwarenessFactories(com.google.common.collect.ImmutableList.of<E?>(ManualDiffAwarenessFactory()))
                    .setSyscallCache(SyscallCache.NO_CACHE)
                    .build()
            SkyframeExecutorTestHelper.process(skyframeExecutor)
            val packageOptions: PackageOptions =
                com.google.devtools.common.options.Options.getDefaults<O>(PackageOptions::class.java)
            packageOptions.setDefaultVisibility(RuleVisibility.PUBLIC)
            packageOptions.setShowLoadingProgress(true)
            packageOptions.setGlobbingThreads(7)
            skyframeExecutor.injectExtraPrecomputedValues(
                com.google.common.collect.ImmutableList.of<E?>(
                    PrecomputedValue.injected(
                        RepositoryDirectoryValue.VENDOR_DIRECTORY, java.util.Optional.empty<T?>()
                    ),
                    PrecomputedValue.injected(
                        RepoDefinitionFunction.REPOSITORY_OVERRIDES, com.google.common.collect.ImmutableMap.of<K?, V?>()
                    )
                )
            )
            val buildLanguageOptions: BuildLanguageOptions? =
                com.google.devtools.common.options.Options.getDefaults<O?>(BuildLanguageOptions::class.java)
            skyframeExecutor.preparePackageLoading(
                PathPackageLocator(
                    outputBase,
                    com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(workspace)),
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                ),
                packageOptions,
                buildLanguageOptions,
                UUID.randomUUID(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                QuiescingExecutorsImpl.forTesting(),
                TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())
            )
            skyframeExecutor.setActionEnv(com.google.common.collect.ImmutableMap.of<K?, V?>())
        }

        @Throws(IOException::class)
        fun addFile(fileName: String?, vararg content: String?): Path {
            val buildFile: Path = workspace.getRelative(fileName)
            com.google.common.base.Preconditions.checkState(!buildFile.exists())
            var currentPath: Path = buildFile

            // Add the new file and all the directories that will be created by
            // createDirectoryAndParents()
            while (!currentPath.exists()) {
                changes.add(currentPath)
                currentPath = currentPath.getParentDirectory()
            }

            buildFile.getParentDirectory().createDirectoryAndParents()
            FileSystemUtils.writeContentAsLatin1(buildFile, com.google.common.base.Joiner.on('\n').join(content))
            return buildFile
        }

        @Throws(IOException::class)
        fun addSymlink(fileName: String?, target: String?) {
            val path: Path = workspace.getRelative(fileName)
            com.google.common.base.Preconditions.checkState(!path.exists())
            path.getParentDirectory().createDirectoryAndParents()
            path.createSymbolicLink(PathFragment.create(target))
            changes.add(path)
        }

        @Throws(IOException::class)
        fun removeFile(fileName: String?) {
            val path: Path = workspace.getRelative(fileName)
            com.google.common.base.Preconditions.checkState(path.delete())
            changes.add(path)
        }

        @Throws(IOException::class)
        fun modifyFile(fileName: String?, vararg content: String?) {
            val path: Path = workspace.getRelative(fileName)
            com.google.common.base.Preconditions.checkState(path.exists())
            com.google.common.base.Preconditions.checkState(path.delete())
            FileSystemUtils.writeContentAsLatin1(path, com.google.common.base.Joiner.on('\n').join(content))
            changes.add(path)
        }

        @Throws(IOException::class)
        fun modifySymlink(fileName: String?, newTarget: String?) {
            val symlink: Path = workspace.getRelative(fileName)
            com.google.common.base.Preconditions.checkState(symlink.exists())
            symlink.delete()
            symlink.createSymbolicLink(PathFragment.create(newTarget))
            changes.add(symlink)
        }

        fun everythingModified() {
            everythingModified = true
        }

        private fun getModifiedFileSet(): ModifiedFileSet {
            if (everythingModified) {
                everythingModified = false
                return ModifiedFileSet.EVERYTHING_MODIFIED
            }

            val builder: ModifiedFileSet.Builder = ModifiedFileSet.builder()
            for (path in changes) {
                if (!path.startsWith(workspace)) {
                    continue
                }

                val workspacePath: PathFragment? = path.relativeTo(workspace)
                builder.modify(workspacePath)
            }
            return builder.build()
        }

        @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
        fun sync() {
            syncWithOptions(com.google.devtools.common.options.Options.getDefaults<O?>(PackageOptions::class.java))
        }

        @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
        fun syncWithOptions(packageOptions: PackageOptions) {
            clock.advanceMillis(1)

            modifiedFileSet = getModifiedFileSet()
            packageOptions.setDefaultVisibility(RuleVisibility.PUBLIC)
            packageOptions.setShowLoadingProgress(true)
            packageOptions.setGlobbingThreads(7)
            val buildLanguageOptions: BuildLanguageOptions? =
                com.google.devtools.common.options.Options.getDefaults<O?>(BuildLanguageOptions::class.java)
            skyframeExecutor.preparePackageLoading(
                PathPackageLocator(
                    outputBase,
                    com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(workspace)),
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                ),
                packageOptions,
                buildLanguageOptions,
                UUID.randomUUID(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                QuiescingExecutorsImpl.forTesting(),
                TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())
            )
            skyframeExecutor.setActionEnv(com.google.common.collect.ImmutableMap.of<K?, V?>())
            skyframeExecutor.invalidateFilesUnderPathForTesting(
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus()),
                modifiedFileSet,
                Root.fromPath(workspace)
            )
            (skyframeExecutor as SequencedSkyframeExecutor)
                .handleDiffsForTesting(
                    com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus()),
                    packageOptions
                )

            changes.clear()
        }

        @Throws(NoSuchPackageException::class, NoSuchTargetException::class, java.lang.InterruptedException::class)
        fun getTarget(targetName: String?): Target {
            val label: Label? = Label.parseCanonicalUnchecked(targetName)
            return skyframeExecutor.getPackageManager().getTarget(reporter, label)
        }

        @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
        fun getPackage(pkgId: PackageIdentifier?): Package {
            return skyframeExecutor.getPackageManager().getPackage(reporter, pkgId)
        }

        @Throws(LabelSyntaxException::class, NoSuchPackageException::class, java.lang.InterruptedException::class)
        fun getPackage(packageName: String?): Package? {
            return getPackage(PackageIdentifier.parse(packageName))
        }
    }

    companion object {
        @Throws(java.lang.Exception::class)
        protected fun createTester(
            fs: FileSystem,
            clock: com.google.devtools.build.lib.testutil.ManualClock
        ): PackageLoadingTester {
            return PackageLoadingTester(fs, clock)
        }
    }
}
