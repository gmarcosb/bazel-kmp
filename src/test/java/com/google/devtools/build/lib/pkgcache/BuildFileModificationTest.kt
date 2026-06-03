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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.actions.ActionKeyContext

/**
 * Tests for package loading.
 */
@RunWith(JUnit4::class)
class BuildFileModificationTest : FoundationTestCase() {
    private val clock: com.google.devtools.build.lib.testutil.ManualClock =
        com.google.devtools.build.lib.testutil.ManualClock()
    private var skyframeExecutor: SkyframeExecutor? = null
    private val actionKeyContext: ActionKeyContext = ActionKeyContext()

    @Before
    fun disableLogging() {
        java.util.logging.Logger.getLogger("com.google.devtools").setLevel(java.util.logging.Level.SEVERE)
    }

    @Before
    @Throws(OptionsParsingException::class)
    fun initializeSkyframeExecutor() {
        val analysisMock: AnalysisMock = AnalysisMock.getAnalysisMockWithoutBuiltinModules()
        val ruleClassProvider: ConfiguredRuleClassProvider? = analysisMock.createRuleClassProvider()
        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(outputBase, outputBase, outputBase),
                rootDirectory,
                analysisMock.productName
            )
        val pkgFactory: PackageFactory? =
            analysisMock
                .getPackageFactoryBuilderForTesting(directories)
                .build(ruleClassProvider, fileSystem)
        skyframeExecutor =
            BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
                .setPkgFactory(pkgFactory)
                .setFileSystem(fileSystem)
                .setDirectories(directories)
                .setActionKeyContext(actionKeyContext)
                .setExtraSkyFunctions(analysisMock.getSkyFunctions(directories))
                .setSyscallCache(SyscallCache.NO_CACHE)
                .build()
        skyframeExecutor.injectExtraPrecomputedValues(analysisMock.precomputedValues)
        SkyframeExecutorTestHelper.process(skyframeExecutor)
        val parser: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(PackageOptions::class.java, BuildLanguageOptions::class.java)
                .build()
        parser.parse(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS)
        setUpSkyframe(
            parser.getOptions<O?>(PackageOptions::class.java), parser.getOptions<O?>(BuildLanguageOptions::class.java)
        )
    }

    private fun setUpSkyframe(
        packageOptions: PackageOptions, buildLanguageOptions: BuildLanguageOptions?
    ) {
        val pkgLocator: PathPackageLocator? =
            PathPackageLocator.create(
                null,
                packageOptions.getPackagePath(),
                reporter,
                rootDirectory.asFragment(),
                rootDirectory,
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        packageOptions.setShowLoadingProgress(true)
        packageOptions.setGlobbingThreads(7)
        skyframeExecutor.preparePackageLoading(
            pkgLocator,
            packageOptions,
            buildLanguageOptions,
            UUID.randomUUID(),
            com.google.common.collect.ImmutableMap.of<K?, V?>(),
            QuiescingExecutorsImpl.forTesting(),
            TimestampGranularityMonitor(clock)
        )
        skyframeExecutor.setActionEnv(com.google.common.collect.ImmutableMap.of<K?, V?>())
        skyframeExecutor.setDeletedPackages(
            com.google.common.collect.ImmutableSet.copyOf(packageOptions.getDeletedPackagesOrEmptySet())
        )
    }

    override fun createFileSystem(): FileSystem? {
        return InMemoryFileSystem(clock, DigestHashFunction.SHA256)
    }

    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    private fun invalidatePackages() {
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory)
        )
    }

    @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
    private fun getPackage(packageName: String?): Package {
        return skyframeExecutor.getPackageManager().getPackage(
            reporter,
            PackageIdentifier.createInMainRepo(packageName)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCTimeChangeDetectedWithError() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val build: Path =
            scratch.file(
                "a/BUILD",
                "filegroup(name='a', feet='stinky')".toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1)
            )
        val a1: Package = getPackage("a")
        assertThat(a1.containsErrors()).isTrue()
        assertContainsEvent("//a:a: no such attribute 'feet'")
        eventCollector.clear()
        // writeContent updates mtime and ctime. Note that we keep the content length exactly the same.
        clock.advanceMillis(1)
        FileSystemUtils.writeContent(
            build, "filegroup(name='a', srcs=['a.cc'])".toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1)
        )

        invalidatePackages()
        val a2: Package = getPackage("a")
        assertThat(a2).isNotSameInstanceAs(a1)
        assertThat(a2.containsErrors()).isFalse()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCTimeChangeDetected() {
        val path: Path =
            scratch.file(
                "pkg/BUILD", "filegroup(name = 'foo')\n".toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1)
            )
        val oldPkg: Package = getPackage("pkg")

        // Note that the content has exactly the same length as before.
        clock.advanceMillis(1)
        FileSystemUtils.writeContent(
            path, "filegroup(name = 'bar')\n".toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1)
        )
        assertThat(getPackage("pkg"))
            .isSameInstanceAs(oldPkg) // Change only becomes visible after invalidatePackages.

        invalidatePackages()

        val newPkg: Package = getPackage("pkg")
        assertThat(newPkg).isNotSameInstanceAs(oldPkg)
        assertThat(newPkg.getTarget("bar")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLengthChangeDetected() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val build: Path =
            scratch.file(
                "a/BUILD",
                "filegroup(name='a', srcs=['a.cc'])".toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1)
            )
        val a1: Package = getPackage("a")
        eventCollector.clear()
        // Note that we didn't advance the clock, so ctime/mtime is the same as before.
        // However, the file contents are one byte longer.
        FileSystemUtils.writeContent(
            build, "filegroup(name='ab', srcs=['a.cc'])".toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1)
        )

        invalidatePackages()
        val a2: Package = getPackage("a")
        assertThat(a2).isNotSameInstanceAs(a1)
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTouchedBuildFileCausesReloadAfterSync() {
        val path: Path = scratch.file("pkg/BUILD", "filegroup(name = 'foo')")

        val oldPkg: Package = getPackage("pkg")
        // Change ctime to 1.
        clock.advanceMillis(1)
        path.setLastModifiedTime(1001)
        assertThat(getPackage("pkg")).isSameInstanceAs(oldPkg) // change not yet visible

        invalidatePackages()

        val newPkg: Package = getPackage("pkg")
        assertThat(newPkg).isNotSameInstanceAs(oldPkg)
    }
}
