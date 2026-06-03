// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.rules.python.PythonTestUtils.getPyLoad

/** Tests for [SequencedSkyframeExecutor].  */
@RunWith(TestParameterInjector::class)
class SequencedSkyframeExecutorTest : BuildViewTestCase() {
    private val options: OptionsParser = OptionsParser.builder()
        .optionsClasses(
            AnalysisOptions::class.java,
            BuildLanguageOptions::class.java,
            BuildRequestOptions::class.java,
            CoreOptions::class.java,
            ExecutionOptions::class.java,
            KeepStateAfterBuildOption::class.java,
            KeepGoingOption::class.java,
            PackageOptions::class.java,
            UiOptions::class.java
        )
        .build()
    private val extraSkyFunctions: MutableMap<SkyFunctionName?, SkyFunction?> =
        HashMap<SkyFunctionName?, SkyFunction?>()
    private var visitor: QueryTransitivePackagePreloader? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createVisitorAndParseOptions() {
        visitor = skyframeExecutor.getQueryTransitivePackagePreloader()
        options.parse("--jobs=20")
    }

    @Before
    fun setOutputService() {
        skyframeExecutor.setOutputService(LocalOutputService(directories))
    }

    val analysisMock: AnalysisMock
        get() {
            val delegate: AnalysisMock? = super.getAnalysisMock()
            return object : com.google.devtools.build.lib.analysis.util.AnalysisMock.Delegate(delegate) {
                public override fun getSkyFunctions(
                    directories: BlazeDirectories
                ): com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>? {
                    return com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
                        .putAll(delegate.getSkyFunctions(directories))
                        .putAll(extraSkyFunctions)
                        .buildOrThrow()
                }
            }
        }

    private class TopLevelTargetBuiltEventCollector {
        private val collectedEvents: MutableSet<TopLevelTargetBuiltEvent?> = HashSet<TopLevelTargetBuiltEvent?>()

        @com.google.common.eventbus.Subscribe
        fun collect(e: TopLevelTargetBuiltEvent?) {
            collectedEvents.add(e)
        }

        fun getCollectedEvents(): com.google.common.collect.ImmutableSet<TopLevelTargetBuiltEvent?> {
            return com.google.common.collect.ImmutableSet.copyOf<TopLevelTargetBuiltEvent?>(collectedEvents)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testChangeFile() {
        analysisMock.pySupport().setup(mockToolsConfig)
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory)
        )

        val pathString = rootDirectory + "/python/hello/BUILD"
        scratch.file(
            pathString, getPyLoad("py_binary"), "py_binary(name = 'hello', srcs = ['hello.py'])"
        )

        // A dummy file that is never changed.
        scratch.file(rootDirectory + "/misc/BUILD", "filegroup(name = 'misc', srcs = ['hello.sh'])")

        sync("//python/hello:hello", "//misc:misc")

        // No changes yet.
        Truth.assertThat(dirtyValues()).isEmpty()

        // Make a change.
        scratch.overwriteFile(
            pathString,
            getPyLoad("py_binary"),
            "py_binary(name = 'hello', srcs = ['something_else.py'])"
        )
        Truth.assertThat(dirtyValues())
            .containsExactly(
                FileStateValue.key(
                    RootedPath.toRootedPath(
                        Root.fromPath(rootDirectory), PathFragment.create("python/hello/BUILD")
                    )
                )
            )

        // The method will continue returning the value until we invalidate it and re-evaluate.
        Truth.assertThat(dirtyValues()).hasSize(1)
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("python/hello/BUILD")).build(),
            Root.fromPath(rootDirectory)
        )
        sync("//python/hello:hello")
        Truth.assertThat(dirtyValues()).isEmpty()
    }

    // Regression for b/13328517. clearAnalysisCache() method is call when --discard_analysis_cache
    // is used. This saves about 10% of the memory during execution.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClearAnalysisCache() {
        skyframeExecutor.setEventBus(com.google.common.eventbus.EventBus())
        scratch.file(
            rootDirectory + "/discard/BUILD",
            "genrule(name='x', srcs=['input'], outs=['out'], cmd='false')"
        )
        scratch.file(rootDirectory + "/discard/input", "foo")

        var ct: ConfiguredTarget? =
            skyframeExecutor.getConfiguredTargetForTesting(
                reporter, Label.parseCanonical("@//discard:x"), targetConfiguration
            )
        assertThat(ct).isNotNull()
        val ref: java.lang.ref.WeakReference<ConfiguredTarget?> = java.lang.ref.WeakReference<ConfiguredTarget?>(ct)
        ct = null
        // Allow all values to be cleared by passing in empty set of top-level values, since we're not
        // actually building.
        skyframeExecutor.clearAnalysisCache(
            com.google.common.collect.ImmutableSet.of<E?>(),
            com.google.common.collect.ImmutableSet.of<E?>()
        )
        GcFinalization.awaitClear(ref)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testChangeDirectory() {
        analysisMock.pySupport().setup(mockToolsConfig)
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory)
        )

        scratch.file(
            "python/hello/BUILD",
            getPyLoad("py_binary"),
            "py_binary(name = 'hello', srcs = ['hello.py'], data = glob(['*.txt']))"
        )
        scratch.file("python/hello/foo.txt", "foo")

        // A dummy directory that is not changed.
        scratch.file(
            "misc/BUILD",
            getPyLoad("py_binary"),
            "py_binary(name = 'misc', srcs = ['other.py'], data = glob(['*.txt'], allow_empty ="
                    + " True))"
        )

        sync("//python/hello:hello", "//misc:misc")

        // No changes yet.
        Truth.assertThat(dirtyValues()).isEmpty()

        // Make a change.
        scratch.file("python/hello/bar.txt", "bar")
        Truth.assertThat(dirtyValues())
            .containsExactly(
                DirectoryListingStateValue.key(
                    RootedPath.toRootedPath(
                        Root.fromPath(rootDirectory), PathFragment.create("python/hello")
                    )
                )
            )

        // The method will continue returning the value until we invalidate it and re-evaluate.
        Truth.assertThat(dirtyValues()).hasSize(1)
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("python/hello/bar.txt")).build(),
            Root.fromPath(rootDirectory)
        )
        sync("//python/hello:hello")
        Truth.assertThat(dirtyValues()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sync_onlyExternalFileChanged_reportsAffectedFile() {
        val externalRoot: Root? = Root.fromPath(scratch.dir("/external"))
        val file: RootedPath? = RootedPath.toRootedPath(externalRoot, scratch.file("/external/file"))
        initializeSkyframeExecutor( /* doPackageLoadingChecks= */
            true,
            com.google.common.collect.ImmutableList.of<DiffAwareness.Factory>(nothingChangedDiffAwarenessFactory())
        )
        skyframeExecutor
            .injectable()
            .inject(
                file,
                Delta.justNew(FileStateValue.create(file, SyscallCache.NO_CACHE,  /* tsgm= */null))
            )
        skyframeExecutor.externalFilesHelper.getAndNoteFileType(file)
        // Initial sync to establish the baseline DiffAwareness.View.
        skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE)
        scratch.overwriteFile("/external/file", "new content")

        syncSkyframeExecutor()

        val diff: Diff = this.recordedDiff
        assertThat(diff.changedKeysWithNewValues()).containsKey(file)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sync_nothingChangedWithExternalFile_reportsNoExternalKeysInDiff() {
        val externalRoot: Root? = Root.fromPath(scratch.dir("/external"))
        val file: RootedPath? = RootedPath.toRootedPath(externalRoot, scratch.file("/external/file"))
        initializeSkyframeExecutor( /* doPackageLoadingChecks= */
            true,
            com.google.common.collect.ImmutableList.of<DiffAwareness.Factory>(nothingChangedDiffAwarenessFactory())
        )
        skyframeExecutor
            .injectable()
            .inject(
                file,
                Delta.justNew(FileStateValue.create(file, SyscallCache.NO_CACHE,  /* tsgm= */null))
            )
        skyframeExecutor.externalFilesHelper.getAndNoteFileType(file)
        // Initial sync to establish the baseline DiffAwareness.View.
        skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE)

        syncSkyframeExecutor()

        val diff: Diff = this.recordedDiff
        assertThat(diff.changedKeysWithoutNewValues()).doesNotContain(file)
        assertThat(diff.changedKeysWithNewValues()).doesNotContainKey(file)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sync_onlyExternalListingChanged_reportsAffectedListing() {
        val externalRoot: Root? = Root.fromPath(scratch.dir("/external"))
        val dir: RootedPath = RootedPath.toRootedPath(externalRoot, scratch.dir("/external/foo"))
        val value: DirectoryListingStateValue? =
            DirectoryListingStateValue.create(dir.asPath().readdir(Symlinks.NOFOLLOW))
        val dirListingKey: DirectoryListingStateValue.Key = DirectoryListingStateValue.key(dir)
        initializeSkyframeExecutor( /* doPackageLoadingChecks= */
            true,
            com.google.common.collect.ImmutableList.of<DiffAwareness.Factory>(nothingChangedDiffAwarenessFactory())
        )
        skyframeExecutor
            .injectable()
            .inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    dir,
                    Delta.justNew(FileStateValue.create(dir, SyscallCache.NO_CACHE,  /* tsgm= */null)),
                    dirListingKey,
                    Delta.justNew(value)
                )
            )
        skyframeExecutor.externalFilesHelper.getAndNoteFileType(dir)
        // Initial sync to establish the baseline DiffAwareness.View.
        skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE)
        scratch.file("/external/foo/new_file")

        syncSkyframeExecutor()

        val diff: Diff = this.recordedDiff
        assertThat(diff.changedKeysWithoutNewValues()).containsNoneOf(dir, dirListingKey)
        assertThat(diff.changedKeysWithNewValues()).doesNotContainKey(dir)
        assertThat(diff.changedKeysWithNewValues()).containsKey(dirListingKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sync_nothingChangedWithExternalListing_reportsNoExternalKeysInDiff() {
        val externalRoot: Root? = Root.fromPath(scratch.dir("/external"))
        val dir: RootedPath = RootedPath.toRootedPath(externalRoot, scratch.dir("/external/foo"))
        val value: DirectoryListingStateValue? =
            DirectoryListingStateValue.create(dir.asPath().readdir(Symlinks.NOFOLLOW))
        val dirListingKey: DirectoryListingStateValue.Key = DirectoryListingStateValue.key(dir)
        initializeSkyframeExecutor( /* doPackageLoadingChecks= */
            true,
            com.google.common.collect.ImmutableList.of<DiffAwareness.Factory>(nothingChangedDiffAwarenessFactory())
        )
        skyframeExecutor
            .injectable()
            .inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    dir,
                    Delta.justNew(FileStateValue.create(dir, SyscallCache.NO_CACHE,  /* tsgm= */null)),
                    dirListingKey,
                    Delta.justNew(value)
                )
            )
        skyframeExecutor.externalFilesHelper.getAndNoteFileType(dir)
        // Initial sync to establish the baseline DiffAwareness.View.
        skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE)

        syncSkyframeExecutor()

        val diff: Diff = this.recordedDiff
        assertThat(diff.changedKeysWithoutNewValues()).containsNoneOf(dir, dirListingKey)
        assertThat(diff.changedKeysWithNewValues()).doesNotContainKey(dir)
        assertThat(diff.changedKeysWithNewValues()).doesNotContainKey(dirListingKey)
    }

    private val recordedDiff: Diff
        get() = skyframeExecutor
            .getDifferencerForTesting()
            .getDiff( /* fromGraph= */null, { ignored -> false }, { ignored -> false })

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSetDeletedPackages() {
        val eventHandler: ExtendedEventHandler? = NullEventHandler.INSTANCE
        scratch.file("foo/bar/BUILD", "cc_library(name = 'bar', hdrs = ['bar.h'])")
        scratch.file("foo/baz/BUILD", "cc_library(name = 'baz', hdrs = ['baz.h'])")

        assertThat(
            skyframeExecutor
                .getPackageManager()
                .isPackage(eventHandler, PackageIdentifier.createInMainRepo("foo/bar"))
        )
            .isTrue()
        assertThat(
            skyframeExecutor
                .getPackageManager()
                .getBuildFileForPackage(PackageIdentifier.createInMainRepo("foo/bar"))
        )
            .isNotNull()
        assertThat(
            skyframeExecutor
                .getPackageManager()
                .isPackage(eventHandler, PackageIdentifier.createInMainRepo("foo/baz"))
        )
            .isTrue()
        assertThat(
            skyframeExecutor
                .getPackageManager()
                .getBuildFileForPackage(PackageIdentifier.createInMainRepo("foo/baz"))
        )
            .isNotNull()
        assertThat(
            skyframeExecutor
                .getPackageManager()
                .isPackage(eventHandler, PackageIdentifier.createInMainRepo("not/a/package"))
        )
            .isFalse()
        assertThat(
            skyframeExecutor
                .getPackageManager()
                .getBuildFileForPackage(PackageIdentifier.createInMainRepo("not/a/package"))
        )
            .isNull()

        skyframeExecutor
            .getPackageManager()
            .getPackage(eventHandler, PackageIdentifier.createInMainRepo("foo/bar"))
        skyframeExecutor
            .getPackageManager()
            .getPackage(eventHandler, PackageIdentifier.createInMainRepo("foo/baz"))

        org.junit.Assert.assertThrows<T?>(
            "non-existent package was incorrectly thought to exist",
            NoSuchPackageException::class.java,
            org.junit.function.ThrowingRunnable {
                skyframeExecutor
                    .getPackageManager()
                    .getPackage(eventHandler, PackageIdentifier.createInMainRepo("not/a/package"))
            })

        val deletedPackages: com.google.common.collect.ImmutableSet<PackageIdentifier?> =
            com.google.common.collect.ImmutableSet.of<E?>(PackageIdentifier.createInMainRepo("foo/bar"))
        skyframeExecutor.setDeletedPackages(deletedPackages)

        assertThat(
            skyframeExecutor
                .getPackageManager()
                .isPackage(eventHandler, PackageIdentifier.createInMainRepo("foo/bar"))
        )
            .isFalse()
        assertThat(
            skyframeExecutor
                .getPackageManager()
                .getBuildFileForPackage(PackageIdentifier.createInMainRepo("foo/bar"))
        )
            .isNull()
        org.junit.Assert.assertThrows<T?>(
            "deleted package was incorrectly thought to exist",
            NoSuchPackageException::class.java,
            org.junit.function.ThrowingRunnable {
                skyframeExecutor
                    .getPackageManager()
                    .getPackage(eventHandler, PackageIdentifier.createInMainRepo("foo/bar"))
            })
        assertThat(
            skyframeExecutor
                .getPackageManager()
                .isPackage(eventHandler, PackageIdentifier.createInMainRepo("foo/baz"))
        )
            .isTrue()
    }

    // Directly tests that PackageFunction adds a dependency on the PackageLookupValue for
    // (potential) subpackages. This is tested indirectly in several places (e.g.
    // LabelVisitorTest#testSubpackageBoundaryAdd and
    // PackageDeletionTest#testUnsuccessfulBuildAfterDeletion) but those tests are also indirectly
    // testing the behavior of TargetFunction when the target has a '/'.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDependencyOnPotentialSubpackages() {
        val eventHandler: ExtendedEventHandler? = NullEventHandler.INSTANCE
        scratch.file(
            "x/BUILD",
            """
        filegroup(
            name = "x",
            srcs = ["//x:y/z"],
        )

        filegroup(name = "y/z")
        
        """.trimIndent()
        )

        val pkgBefore: Package =
            skyframeExecutor
                .getPackageManager()
                .getPackage(eventHandler, PackageIdentifier.createInMainRepo("x"))
        assertThat(pkgBefore.containsErrors()).isFalse()

        scratch.file("x/y/BUILD", "filegroup(name = 'z')")
        val modifiedFiles: ModifiedFileSet? =
            ModifiedFileSet.builder()
                .modify(PathFragment.create("x"))
                .modify(PathFragment.create("x/y"))
                .modify(PathFragment.create("x/y/BUILD"))
                .build()
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter, modifiedFiles, Root.fromPath(rootDirectory)
        )

        // The package lookup for "x" should now fail because it's invalid.
        reporter.removeHandler(failFastHandler) // expect errors
        assertThat(
            skyframeExecutor
                .getPackageManager()
                .getPackage(eventHandler, PackageIdentifier.createInMainRepo("x"))
                .containsErrors()
        )
            .isTrue()

        scratch.deleteFile("x/y/BUILD")
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter, modifiedFiles, Root.fromPath(rootDirectory)
        )

        // The package lookup for "x" should now succeed again.
        reporter.addHandler(failFastHandler) // no longer expect errors
        val pkgAfter: Package? =
            skyframeExecutor
                .getPackageManager()
                .getPackage(eventHandler, PackageIdentifier.createInMainRepo("x"))
        assertThat(pkgAfter).isNotSameInstanceAs(pkgBefore)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSkyframePackageManagerGetBuildFileForPackage() {
        val skyframePackageManager: PackageManager = skyframeExecutor.getPackageManager()

        scratch.file("nobuildfile/foo.txt")
        scratch.file("deletedpackage/BUILD")
        skyframeExecutor.setDeletedPackages(
            com.google.common.collect.ImmutableList.of<E?>(PackageIdentifier.createInMainRepo("deletedpackage"))
        )
        scratch.file("invalidpackagename.42/BUILD")
        val everythingGoodBuildFilePath: Path? = scratch.file("everythinggood/BUILD")

        assertThat(
            skyframePackageManager.getBuildFileForPackage(
                PackageIdentifier.createInMainRepo("nobuildfile")
            )
        )
            .isNull()
        assertThat(
            skyframePackageManager.getBuildFileForPackage(
                PackageIdentifier.createInMainRepo("deletedpackage")
            )
        )
            .isNull()
        assertThat(
            skyframePackageManager.getBuildFileForPackage(
                PackageIdentifier.createInMainRepo("everythinggood")
            )
        )
            .isEqualTo(everythingGoodBuildFilePath)
    }

    /**
     * Indirect regression test for b/12543229: "The Skyframe error propagation model is problematic".
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageFunctionHandlesExceptionFromDependencies() {
        reporter.removeHandler(failFastHandler)
        val badDirPath: Path = scratch.dir("bad/dir")
        // This will cause an IOException when trying to compute the glob, which is required to load
        // the package.
        badDirPath.setReadable(false)
        scratch.file("bad/BUILD", "filegroup(name='fg', srcs=glob(['**']))")
        org.junit.Assert.assertThrows<T?>(
            NoSuchPackageException::class.java,
            org.junit.function.ThrowingRunnable {
                skyframeExecutor
                    .getPackageManager()
                    .getPackage(reporter, PackageIdentifier.createInMainRepo("bad"))
            })
    }

    @Throws(java.lang.InterruptedException::class)
    private fun dirtyValues(): com.google.common.collect.ImmutableList<SkyKey?> {
        val diff: Diff =
            FilesystemValueChecker(
                TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance()),
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,  /* numThreads= */
                20
            )
                .getDirtyKeys(
                    skyframeExecutor.getEvaluator().getValues(),
                    DirtinessCheckerUtils.createBasicFilesystemDirtinessChecker()
                )
        return com.google.common.collect.ImmutableList.builder<SkyKey?>()
            .addAll(diff.changedKeysWithoutNewValues())
            .addAll(diff.changedKeysWithNewValues().keySet())
            .build()
    }

    @Throws(java.lang.Exception::class)
    private fun sync(vararg labelStrings: String?) {
        val labels: MutableSet<Label?> = HashSet<Label?>()
        for (labelString in labelStrings) {
            labels.add(Label.parseCanonical(labelString))
        }
        visitor.preloadTransitiveTargets(
            reporter,
            labels,  /* keepGoing= */
            false,  /* parallelThreads= */
            200,  /* callerForError= */
            null
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInterruptLoadedTarget() {
        analysisMock.pySupport().setup(mockToolsConfig)
        scratch.file(
            "python/hello/BUILD",
            getPyLoad("py_binary"),
            "py_binary(name = 'hello', srcs = ['hello.py'], data = glob(['*.txt'], allow_empty ="
                    + " True))"
        )
        java.lang.Thread.currentThread().interrupt()
        val packageProvider: LoadedPackageProvider =
            LoadedPackageProvider(skyframeExecutor.getPackageManager(), reporter)
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { packageProvider.getLoadedTarget(Label.parseCanonicalUnchecked("//python/hello:hello")) })
        val target: Target? =
            packageProvider.getLoadedTarget(Label.parseCanonicalUnchecked("//python/hello:hello"))
        assertThat(target).isNotNull()
    }

    /**
     * Generating the same output from two targets is ok if we build them on successive builds and
     * invalidate the first target before we build the second target. This test is basically copied
     * here from `AnalysisCachingTest` because here we can control the number of Skyframe update
     * calls that we make. This prevents an intermediate update call from clearing the action and
     * hiding the bug.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoActionConflictWithInvalidatedTarget() {
        scratch.file(
            "conflict/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "x",
            srcs = ["foo.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )
        
        """.trimIndent()
        )
        val conflict: ConfiguredTargetAndData =
            skyframeExecutor.getConfiguredTargetAndDataForTesting(
                reporter, Label.parseCanonical("@//conflict:x"), targetConfiguration
            )
        assertThat(conflict).isNotNull()
        val root: ArtifactRoot =
            targetConfiguration
                .getBinDirectory(conflict.getConfiguredTarget().getLabel().getRepository())

        val oldAction: Action =
            getGeneratingAction(
                getDerivedArtifact(
                    PathFragment.create("conflict/_objs/x/foo.o"),
                    root,
                    ConfiguredTargetKey.fromConfiguredTarget(conflict.getConfiguredTarget())
                )
            )
        assertThat(oldAction.getOwner().getLabel().toString()).isEqualTo("//conflict:x")
        skyframeExecutor.handleAnalysisInvalidatingChange()
        val objsConflict: ConfiguredTargetAndData =
            skyframeExecutor.getConfiguredTargetAndDataForTesting(
                reporter, Label.parseCanonical("@//conflict:_objs/x/foo.o"), targetConfiguration
            )
        assertThat(objsConflict).isNotNull()
        val newAction: Action =
            getGeneratingAction(
                getDerivedArtifact(
                    PathFragment.create("conflict/_objs/x/foo.o"),
                    root,
                    ConfiguredTargetKey.fromConfiguredTarget(objsConflict.getConfiguredTarget())
                )
            )
        assertThat(newAction.getOwner().getLabel().toString()).isEqualTo("//conflict:_objs/x/foo.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetPackageUsesListener() {
        scratch.file("pkg/BUILD", "thisisanerror")
        val customEventCollector: EventCollector = EventCollector(com.google.devtools.build.lib.events.EventKind.ERRORS)
        val pkg: Package =
            skyframeExecutor
                .getPackageManager()
                .getPackage(
                    com.google.devtools.build.lib.events.Reporter(
                        EventBusEventHandler.createWithNewEventBus(),
                        customEventCollector
                    ),
                    PackageIdentifier.createInMainRepo("pkg")
                )
        assertThat(pkg.containsErrors()).isTrue()
        MoreAsserts.assertContainsEvent(customEventCollector, "name 'thisisanerror' is not defined")
    }

    /** Dummy action that does not create its lone output file.  */
    private class MissingOutputAction(inputs: NestedSet<Artifact?>, output: Artifact) : DummyAction(inputs, output) {
        @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
        override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
            val actionResult: ActionResult = super.execute(actionExecutionContext)
            try {
                getPrimaryOutput().getPath().deleteTree()
            } catch (e: IOException) {
                throw java.lang.AssertionError(e)
            }
            return actionResult
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun <T : SkyValue?> evaluate(roots: Iterable<out SkyKey?>?): EvaluationResult<T?> {
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(SequencedSkyframeExecutor.DEFAULT_THREAD_COUNT)
                .setEventHandler(reporter)
                .build()
        return evaluateWithEvaluationContext<SkyValue?>(roots, evaluationContext)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    private fun <T : SkyValue?> evaluateWithEvaluationContext(
        roots: Iterable<out SkyKey?>?, context: EvaluationContext?
    ): EvaluationResult<T?> {
        return skyframeExecutor.getEvaluator().evaluate(roots, context)
    }

    /**
     * Make sure that if a shared action fails to create an output file, the other action doesn't
     * complain about it too.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSharedActionsNoOutputs() {
        val root: Path = execRoot
        val execPath: PathFragment? = PathFragment.create("out").getRelative("missing")
        // We create two "configured targets" and two copies of the same artifact, each generated by
        // an action from its respective configured target.
        val lc1: ActionLookupKey = InjectedActionLookupKey("lc1")
        val output1: Artifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"), execPath, lc1
            )
        val action1: Action =
            MissingOutputAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output1)
        val ctValue1: ActionLookupValue = createActionLookupValue(action1, lc1)
        val lc2: ActionLookupKey = InjectedActionLookupKey("lc2")
        val output2: Artifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"), execPath, lc2
            )
        val action2: Action =
            MissingOutputAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output2)
        val ctValue2: ActionLookupValue = createActionLookupValue(action2, lc2)
        configureActionExecutor()
        // Inject the "configured targets" into the graph.
        skyframeExecutor
            .getDifferencerForTesting()
            .inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    lc1,
                    Delta.justNew(ctValue1),
                    lc2,
                    Delta.justNew(ctValue2)
                )
            )
        // Do a null build, so that the skyframe executor initializes the action executor properly.
        skyframeExecutor.setActionOutputRoot(outputPath)
        skyframeExecutor.setActionExecutionProgressReportingObjects(
            EMPTY_PROGRESS_SUPPLIER,
            EMPTY_COMPLETION_RECEIVER,
            ActionExecutionStatusReporter.create(reporter)
        )
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.buildArtifacts(
                reporter,
                ResourceManager(),
                com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                options,
                NULL_CHECKER,
                ActionOutputDirectoryHelper.createForTesting(),
                null,
                null
            )

        reporter.removeHandler(failFastHandler) // Expect errors.
        skyframeExecutor.prepareBuildingForTestingOnly(
            reporter,
            com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting()
        )
        val result: EvaluationResult<FileArtifactValue?> =
            evaluate<SkyValue?>(com.google.common.collect.ImmutableList.of<SkyKey?>(output1, output2))
        assertWithMessage(result.toString()).that(result.keyNames()).isEmpty()
        assertThat(result.hasError()).isTrue()
        MoreAsserts.assertContainsEvent(
            eventCollector, "output '" + output1.prettyPrint() + "' was not created"
        )
        MoreAsserts.assertContainsEvent(eventCollector, "not all outputs were created or valid")
        MoreAsserts.assertEventCount(2, eventCollector)
    }

    /** Shared actions can race and both check the action cache and try to execute.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSharedActionsRacing() {
        val root: Path = execRoot
        val execPath: PathFragment? = PathFragment.create("out").getRelative("file")
        val sourcePath: Path = rootDirectory.getRelative("foo/src")
        sourcePath.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(sourcePath)

        // We create two "configured targets" and two copies of the same artifact, each generated by
        // an action from its respective configured target. Both actions will consume the input file
        // "out/input" so we can synchronize their execution.
        val inputKey: ActionLookupKey = InjectedActionLookupKey("input")
        val input: Artifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                PathFragment.create("out").getRelative("input"),
                inputKey
            )
        val baseAction: Action = DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), input)
        val ctBase: ActionLookupValue = createActionLookupValue(baseAction, inputKey)
        val lc1: ActionLookupKey = InjectedActionLookupKey("lc1")
        val output1: Artifact? =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"), execPath, lc1
            )
        val action1: Action = DummyAction(NestedSetBuilder.create(Order.STABLE_ORDER, input), output1)
        val ctValue1: ActionLookupValue = createActionLookupValue(action1, lc1)
        val lc2: ActionLookupKey = InjectedActionLookupKey("lc2")
        val output2: Artifact? =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"), execPath, lc2
            )
        val action2: Action = DummyAction(NestedSetBuilder.create(Order.STABLE_ORDER, input), output2)
        val ctValue2: ActionLookupValue = createActionLookupValue(action2, lc2)

        // Stall both actions during the "checking inputs" phase so that neither will enter
        // SkyframeActionExecutor before both have asked SkyframeActionExecutor if another shared action
        // is running. This way, both actions will check the action cache beforehand and try to update
        // the action cache post-build.
        val inputsRequested: CountDownLatch = CountDownLatch(2)
        configureActionExecutor()
        skyframeExecutor
            .getEvaluator()
            .injectGraphTransformerForTesting(
                NotifyingHelper.makeNotifyingTransformer(
                    NotifyingHelper.Listener { key: SkyKey?, type: NotifyingHelper.EventType?, order: NotifyingHelper.Order?, context: Any? ->
                        if (type == NotifyingHelper.EventType.GET_VALUE_WITH_METADATA && key.functionName()
                                .equals(Artifact.ARTIFACT)
                            && input.equals(key)
                        ) {
                            inputsRequested.countDown()
                            try {
                                Truth.assertThat(
                                    inputsRequested.await(
                                        com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                                        TimeUnit.SECONDS
                                    )
                                )
                                    .isTrue()
                            } catch (e: java.lang.InterruptedException) {
                                throw java.lang.IllegalStateException(e)
                            }
                        }
                    })
            )

        // Inject the "configured targets" and artifact into the graph.
        skyframeExecutor
            .getDifferencerForTesting()
            .inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    lc1,
                    Delta.justNew(ctValue1),
                    lc2,
                    Delta.justNew(ctValue2),
                    inputKey,
                    Delta.justNew(ctBase)
                )
            )
        // Do a null build, so that the skyframe executor initializes the action executor properly.
        skyframeExecutor.setActionOutputRoot(outputPath)
        skyframeExecutor.setActionExecutionProgressReportingObjects(
            EMPTY_PROGRESS_SUPPLIER,
            EMPTY_COMPLETION_RECEIVER,
            ActionExecutionStatusReporter.create(reporter)
        )
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.buildArtifacts(
                reporter,
                ResourceManager(),
                com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                options,
                NULL_CHECKER,
                ActionOutputDirectoryHelper.createForTesting(),
                null,
                null
            )

        skyframeExecutor.prepareBuildingForTestingOnly(
            reporter,
            com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting()
        )
        val result: EvaluationResult<FileArtifactValue?> =
            evaluate<T?>(Artifact.keys(com.google.common.collect.ImmutableList.of<E?>(output1, output2)))
        assertThat(result.hasError()).isFalse()
        TrackingAwaiter.INSTANCE.assertNoErrors()
    }

    /**
     * Tests a subtle situation when three shared actions race and are interrupted. Action A starts
     * executing. Actions B and C start executing. Action B notices action A is already executing and
     * sets completionFuture. It then exits, returning control to
     * AbstractParallelEvaluator$Evaluate#run code. The build is interrupted. When B's code tries to
     * register the future with AbstractQueueVisitor, the future is canceled (or if the interrupt
     * races with B registering the future, shortly thereafter). Action C then starts running. It too
     * notices Action A is already executing. The future's state should be consistent. A cannot finish
     * until C runs, since otherwise C would see that A was done.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testThreeSharedActionsRacing() {
        val root: Path = execRoot
        val out: PathFragment = PathFragment.create("out")
        val execPath: PathFragment? = out.getRelative("file")
        // We create three "configured targets" and three copies of the same artifact, each generated by
        // an action from its respective configured target. The actions wouldn't actually do the same
        // thing if they executed, but they look the same to our execution engine.
        val lcA: ActionLookupKey = InjectedActionLookupKey("lcA")
        val outputA: Artifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"), execPath, lcA
            )
        val actionAStartedSoOthersCanProceed: CountDownLatch = CountDownLatch(1)
        val actionCFinishedSoACanFinish: CountDownLatch = CountDownLatch(1)
        val actionA: Action =
            TestAction(
                java.util.concurrent.Callable {
                    actionAStartedSoOthersCanProceed.countDown()
                    try {
                        java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                    } catch (e: java.lang.InterruptedException) {
                        TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                            actionCFinishedSoACanFinish, "third didn't finish"
                        )
                        throw e
                    }
                    throw java.lang.IllegalStateException("Should have been interrupted")
                } as java.io.Serializable,
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                com.google.common.collect.ImmutableSet.of<E?>(outputA))
        val ctA: ActionLookupValue = createActionLookupValue(actionA, lcA)

        // Shared actions: they look the same from the point of view of Blaze data.
        val lcB: ActionLookupKey = InjectedActionLookupKey("lcB")
        val outputB: Artifact? =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"), execPath, lcB
            )
        val actionB: Action = DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), outputB)
        val ctB: ActionLookupValue = createActionLookupValue(actionB, lcB)
        val lcC: ActionLookupKey = InjectedActionLookupKey("lcC")
        val outputC: Artifact? =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"), execPath, lcC
            )
        val actionC: Action = DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), outputC)
        val ctC: ActionLookupValue = createActionLookupValue(actionC, lcC)

        // Both shared actions wait for A to start executing. We do that by stalling their dep requests
        // on their configured targets. We then let B proceed. Once B finishes its SkyFunction run, it
        // interrupts the main thread. C just waits until it has been interrupted, and then another
        // little bit, to give B time to attempt to add the future and try to cancel it. It not waiting
        // long enough can lead to a flaky pass.
        val mainThread: java.lang.Thread = java.lang.Thread.currentThread()
        val cStarted: CountDownLatch = CountDownLatch(1)
        configureActionExecutor()
        skyframeExecutor
            .getEvaluator()
            .injectGraphTransformerForTesting(
                NotifyingHelper.makeNotifyingTransformer(
                    NotifyingHelper.Listener { key: SkyKey?, type: NotifyingHelper.EventType?, order: NotifyingHelper.Order?, context: Any? ->
                        if (type == NotifyingHelper.EventType.GET_VALUE_WITH_METADATA
                            && (key.equals(lcB) || key.equals(lcC))
                        ) {
                            // One of the shared actions is requesting its configured target dep.
                            TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                                actionAStartedSoOthersCanProceed, "primary didn't start"
                            )
                            if (key.equals(lcC)) {
                                cStarted.countDown()
                                // Wait until interrupted.
                                try {
                                    java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                                    throw java.lang.IllegalStateException("Should have been interrupted")
                                } catch (e: java.lang.InterruptedException) {
                                    // Because ActionExecutionFunction doesn't check for interrupts, this
                                    // interrupted state will persist until the ADD_REVERSE_DEP code below. If
                                    // it does not, this test will start to fail, which is good, since it would
                                    // be strange to check for interrupts in that stretch of hot code.
                                    java.lang.Thread.currentThread().interrupt()
                                }
                                // Wait for B thread to cancel its future. It's hard to know exactly when that
                                // will be, so give it time. No flakes in 2k runs with this sleep.
                                com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly(
                                    100,
                                    TimeUnit.MILLISECONDS
                                )
                            }
                        } else if (type == NotifyingHelper.EventType.ADD_REVERSE_DEP && key.equals(lcB)
                            && order == NotifyingHelper.Order.BEFORE && context != null
                        ) {
                            TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(cStarted, "c missing")
                            // B thread has finished its run. Interrupt build!
                            mainThread.interrupt()
                        } else if (type == NotifyingHelper.EventType.ADD_REVERSE_DEP && key.equals(lcC)
                            && order == NotifyingHelper.Order.BEFORE && context != null
                        ) {
                            // Test is almost over: let action A finish now that C observed future.
                            actionCFinishedSoACanFinish.countDown()
                        }
                    })
            )

        // Inject the "configured targets" and artifacts into the graph.
        skyframeExecutor
            .getDifferencerForTesting()
            .inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    lcA, Delta.justNew(ctA), lcB, Delta.justNew(ctB), lcC, Delta.justNew(ctC)
                )
            )
        // Do a null build, so that the skyframe executor initializes the action executor properly.
        skyframeExecutor.setActionOutputRoot(outputPath)
        skyframeExecutor.setActionExecutionProgressReportingObjects(
            EMPTY_PROGRESS_SUPPLIER,
            EMPTY_COMPLETION_RECEIVER,
            ActionExecutionStatusReporter.create(reporter)
        )
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.buildArtifacts(
                reporter,
                ResourceManager(),
                com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                options,
                NULL_CHECKER,
                ActionOutputDirectoryHelper.createForTesting(),
                null,
                null
            )

        skyframeExecutor.prepareBuildingForTestingOnly(
            reporter,
            com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting()
        )
        reporter.removeHandler(failFastHandler)
        try {
            evaluate<T?>(Artifact.keys(com.google.common.collect.ImmutableList.of<E?>(outputA, outputB, outputC)))
            org.junit.Assert.fail()
        } catch (e: java.lang.InterruptedException) {
            // Expected.
        }
        TrackingAwaiter.INSTANCE.assertNoErrors()
    }

    /** Dummy codec for serialization. Doesn't actually serialize [CountDownLatch]!  */
    @Suppress("unused")
    private class CountDownLatchCodec : ObjectCodec<CountDownLatch?> {
        val encodedClass: java.lang.Class<out CountDownLatch?>
            get() = CountDownLatch::class.java

        public override fun serialize(
            context: SerializationContext?, obj: CountDownLatch?, codedOut: CodedOutputStream?
        ) {
        }

        public override fun deserialize(context: DeserializationContext?, codedIn: CodedInputStream?): CountDownLatch {
            return RETURNED
        }

        companion object {
            private val RETURNED: CountDownLatch = CountDownLatch(0)
        }
    }

    /** Regression test for ##5396: successfully build shared actions with tree artifacts.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sharedActionsWithTree() {
        val root: Path = execRoot
        val execPath: PathFragment? = PathFragment.create("out").getRelative("trees")
        // We create two "configured targets" and two copies of the same artifact, each generated by
        // an action from its respective configured target.
        val lc1: ActionLookupKey = InjectedActionLookupKey("lc1")
        val output1: SpecialArtifact =
            SpecialArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath,
                lc1,
                Artifact.SpecialArtifactType.TREE
            )
        val children: com.google.common.collect.ImmutableList<PathFragment?> =
            com.google.common.collect.ImmutableList.of<E?>(PathFragment.create("child"))
        val action1: Action =
            TreeArtifactAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output1, children)
        val ctValue1: ActionLookupValue = createActionLookupValue(action1, lc1)
        val lc2: ActionLookupKey = InjectedActionLookupKey("lc2")
        val output2: SpecialArtifact =
            SpecialArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath,
                lc2,
                Artifact.SpecialArtifactType.TREE
            )
        val action2: Action =
            TreeArtifactAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output2, children)
        val ctValue2: ActionLookupValue = createActionLookupValue(action2, lc2)
        configureActionExecutor()
        // Inject the "configured targets" into the graph.
        skyframeExecutor
            .getDifferencerForTesting()
            .inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    lc1,
                    Delta.justNew(ctValue1),
                    lc2,
                    Delta.justNew(ctValue2)
                )
            )
        // Do a null build, so that the skyframe executor initializes the action executor properly.
        skyframeExecutor.setActionOutputRoot(outputPath)
        skyframeExecutor.setActionExecutionProgressReportingObjects(
            EMPTY_PROGRESS_SUPPLIER,
            EMPTY_COMPLETION_RECEIVER,
            ActionExecutionStatusReporter.create(reporter)
        )
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.buildArtifacts(
                reporter,
                ResourceManager(),
                com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                options,
                NULL_CHECKER,
                ActionOutputDirectoryHelper.createForTesting(),
                null,
                null
            )

        skyframeExecutor.prepareBuildingForTestingOnly(
            reporter,
            com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting()
        )

        val result: EvaluationResult<TreeArtifactValue?> =
            evaluate<SkyValue?>(com.google.common.collect.ImmutableList.of<SkyKey?>(output1, output2))

        val tree1Child: TreeFileArtifact? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(result.get(output1).getChildren())
        val tree2Child: TreeFileArtifact? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(result.get(output2).getChildren())
        assertThat(tree1Child).isEqualTo(TreeFileArtifact.createTreeOutput(output1, "child"))
        assertThat(tree2Child).isEqualTo(TreeFileArtifact.createTreeOutput(output2, "child"))
    }

    /** Dummy action that creates a tree output.  */ // AutoCodec because the superclass has a WrappedRunnable inside it.
    @AutoCodec
    @VisibleForSerialization
    internal class TreeArtifactAction(
        inputs: NestedSet<Artifact?>,
        output: SpecialArtifact,
        children: Iterable<PathFragment?>
    ) : TestAction(
        java.lang.Runnable { createDirectoryAndFiles(output, children) },
        inputs,
        com.google.common.collect.ImmutableSet.of<Artifact>(output)
    ) {
        @Suppress("unused") // Only needed for serialization.
        private val output: SpecialArtifact

        @Suppress("unused") // Only needed for serialization.
        private val children: Iterable<PathFragment?>?

        init {
            com.google.common.base.Preconditions.checkState(output.isTreeArtifact(), output)
            this.output = output
            this.children = children
        }

        companion object {
            private fun createDirectoryAndFiles(
                output: SpecialArtifact, children: Iterable<PathFragment?>
            ) {
                val directory: Path = output.getPath()
                try {
                    directory.createDirectoryAndParents()
                    for (child in children) {
                        FileSystemUtils.createEmptyFile(directory.getRelative(child))
                    }
                } catch (e: IOException) {
                    throw java.lang.IllegalStateException(e)
                }
            }
        }
    }

    /** Regression test for ##5396: successfully build shared actions with tree artifacts.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sharedActionTemplate() {
        val root: Path = execRoot
        val execPath: PathFragment? = PathFragment.create("out").getRelative("trees")
        // We create two "configured targets" and two copies of the same artifact, each generated by
        // an action from its respective configured target.
        val baseKey: ActionLookupKey = InjectedActionLookupKey("base")
        val baseOutput: SpecialArtifact =
            SpecialArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath,
                baseKey,
                Artifact.SpecialArtifactType.TREE
            )
        val children: com.google.common.collect.ImmutableList<PathFragment?> =
            com.google.common.collect.ImmutableList.of<E?>(PathFragment.create("child"))
        val action1: Action =
            TreeArtifactAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), baseOutput, children)
        val baseCt: ActionLookupValue = createActionLookupValue(action1, baseKey)
        val shared1: ActionLookupKey = InjectedActionLookupKey("shared1")
        val execPath2: PathFragment? = PathFragment.create("out").getRelative("treesShared")
        val sharedOutput1: SpecialArtifact =
            SpecialArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath2,
                shared1,
                Artifact.SpecialArtifactType.TREE
            )
        val template1: ActionTemplate<DummyAction?> =
            DummyActionTemplate(baseOutput, sharedOutput1, ActionOwner.SYSTEM_ACTION_OWNER)
        val shared1Ct: ActionLookupValue = createActionLookupValue(template1, shared1)
        val shared2: ActionLookupKey = InjectedActionLookupKey("shared2")
        val sharedOutput2: SpecialArtifact =
            SpecialArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath2,
                shared2,
                Artifact.SpecialArtifactType.TREE
            )
        val template2: ActionTemplate<DummyAction?> =
            DummyActionTemplate(baseOutput, sharedOutput2, ActionOwner.SYSTEM_ACTION_OWNER)
        val shared2Ct: ActionLookupValue = createActionLookupValue(template2, shared2)
        configureActionExecutor()
        // Inject the "configured targets" into the graph.
        skyframeExecutor
            .getDifferencerForTesting()
            .inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    baseKey,
                    Delta.justNew(baseCt),
                    shared1,
                    Delta.justNew(shared1Ct),
                    shared2,
                    Delta.justNew(shared2Ct)
                )
            )
        // Do a null build, so that the skyframe executor initializes the action executor properly.
        skyframeExecutor.setActionOutputRoot(outputPath)
        skyframeExecutor.setActionExecutionProgressReportingObjects(
            EMPTY_PROGRESS_SUPPLIER,
            EMPTY_COMPLETION_RECEIVER,
            ActionExecutionStatusReporter.create(reporter)
        )
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.buildArtifacts(
                reporter,
                ResourceManager(),
                com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                options,
                NULL_CHECKER,
                ActionOutputDirectoryHelper.createForTesting(),
                null,
                null
            )

        skyframeExecutor.prepareBuildingForTestingOnly(
            reporter,
            com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting()
        )
        evaluate<SkyValue?>(com.google.common.collect.ImmutableList.of<SkyKey?>(sharedOutput1, sharedOutput2))
    }

    private class DummyActionTemplate(
        inputArtifact: SpecialArtifact,
        outputArtifact: SpecialArtifact,
        actionOwner: ActionOwner?
    ) : ActionTemplate<DummyAction?> {
        private val inputArtifacts: com.google.common.collect.ImmutableList<SpecialArtifact>
        private val outputArtifact: SpecialArtifact
        private val actionOwner: ActionOwner?

        init {
            this.inputArtifacts = com.google.common.collect.ImmutableList.of<SpecialArtifact>(inputArtifact)
            this.outputArtifact = outputArtifact
            this.actionOwner = actionOwner
        }

        val isShareable: Boolean
            get() = true

        public override fun generateActionsForInputArtifacts(
            inputTreeFileArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact?>,
            artifactOwner: ActionLookupKey?,
            eventHandler: com.google.devtools.build.lib.events.EventHandler?
        ): com.google.common.collect.ImmutableList<DummyAction?> {
            return inputTreeFileArtifacts.stream()
                .map<DummyAction?> { input: TreeFileArtifact? ->
                    val output: TreeFileArtifact? =
                        TreeFileArtifact.createTemplateExpansionOutput(
                            outputArtifact, input.getParentRelativePath(), artifactOwner
                        )
                    DummyAction(input, output)
                }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<DummyAction?>())
        }

        public override fun getKey(
            actionKeyContext: ActionKeyContext?, inputMetadataProvider: InputMetadataProvider?
        ): String {
            val fp: Fingerprint = Fingerprint()
            for (inputArtifact in inputArtifacts) {
                fp.addPath(inputArtifact.getPath())
            }
            fp.addPath(outputArtifact.getPath())
            return fp.hexDigestAndReset()
        }

        val inputTreeArtifacts: com.google.common.collect.ImmutableList<SpecialArtifact>
            get() = inputArtifacts

        val outputs: com.google.common.collect.ImmutableSet<Artifact?>
            get() = com.google.common.collect.ImmutableSet.of<Artifact?>(outputArtifact)

        val owner: ActionOwner?
            get() = actionOwner

        val mnemonic: String
            get() = "DummyTemplate"

        public override fun prettyPrint(): String {
            return describe()
        }

        public override fun describe(): String {
            return "DummyTemplate"
        }

        val tools: NestedSet<Artifact?>
            get() = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

        val inputs: NestedSet<Artifact?>
            get() = NestedSetBuilder.wrap(Order.STABLE_ORDER, inputArtifacts)

        val originalInputs: NestedSet<Artifact?>
            get() = this.inputs

        val schedulingDependencies: NestedSet<Artifact?>
            get() = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

        val clientEnvironmentVariables: com.google.common.collect.ImmutableList<String?>
            get() = com.google.common.collect.ImmutableList.of<String?>()

        public override fun getInputFilesForExtraAction(
            actionExecutionContext: ActionExecutionContext?
        ): NestedSet<Artifact?> {
            return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        }

        val mandatoryOutputs: com.google.common.collect.ImmutableSet<Artifact?>
            get() = com.google.common.collect.ImmutableSet.of<Artifact?>()

        val mandatoryInputs: NestedSet<Artifact?>
            get() = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    }

    /**
     * b/150153544: demonstration of how shared actions do not work on incremental builds when action
     * cache is disabled. In practice, this test usually throws an exception and deadlocks, because
     * the "top" action notices that its input is missing even before the callable specified here
     * executes and throws an exception, so shared action2 never gets the signal to finish. However,
     * even if "top" is delayed to wait for the shared action2 to run, the assertion that the artifact
     * exists will fail, since action2's "prepare" step deleted it.
     */
    @Ignore("b/150153544")
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalSharedActions() {
        val root: Path = execRoot
        val relativeOut: PathFragment = PathFragment.create("out")
        val execPath: PathFragment? = relativeOut.getRelative("file")
        val sourcePath: Path = rootDirectory.getRelative("foo/src")
        sourcePath.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(sourcePath)

        // We create two "configured targets" and two copies of the same artifact, each generated by
        // an action from its respective configured target.
        val lc1: ActionLookupKey = InjectedActionLookupKey("lc1")
        val output1: Artifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"), execPath, lc1
            )
        val action1: Action = DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output1)
        val ctValue1: ActionLookupValue = createActionLookupValue(action1, lc1)
        val lc2: ActionLookupKey = InjectedActionLookupKey("lc2")
        val output2: Artifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"), execPath, lc2
            )
        val action2Running: CountDownLatch = CountDownLatch(1)
        val topActionTestedOutput: CountDownLatch = CountDownLatch(1)
        val action2: Action =
            TestAction(
                java.util.concurrent.Callable {
                    action2Running.countDown()
                    TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                        topActionTestedOutput, "top ran"
                    )
                    null
                } as java.util.concurrent.Callable<java.lang.Void?>,
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                com.google.common.collect.ImmutableSet.of<E?>(output2))
        val ctValue2: ActionLookupValue = createActionLookupValue(action2, lc2)

        val topLc: ActionLookupKey = InjectedActionLookupKey("top")
        val top: Artifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                relativeOut.getChild("top"),
                topLc
            )
        val topAction: Action =
            TestAction(
                java.util.concurrent.Callable {
                    TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                        action2Running, "action 2 running"
                    )
                    try {
                        assertThat(output1.getPath().exists()).isTrue()
                    } finally {
                        topActionTestedOutput.countDown()
                    }
                    null
                } as java.util.concurrent.Callable<java.lang.Void?>,
                NestedSetBuilder.create(Order.STABLE_ORDER, output1),
                com.google.common.collect.ImmutableSet.of<E?>(top))
        val ctTop: ActionLookupValue = createActionLookupValue(topAction, topLc)

        // Inject the "configured targets" and artifact into the graph.
        skyframeExecutor
            .getDifferencerForTesting()
            .inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    lc1,
                    Delta.justNew(ctValue1),
                    lc2,
                    Delta.justNew(ctValue2),
                    topLc,
                    Delta.justNew(ctTop)
                )
            )
        // Do a null build, so that the skyframe executor initializes the action executor properly.
        skyframeExecutor.setActionOutputRoot(outputPath)
        skyframeExecutor.setActionExecutionProgressReportingObjects(
            EMPTY_PROGRESS_SUPPLIER,
            EMPTY_COMPLETION_RECEIVER,
            ActionExecutionStatusReporter.create(reporter)
        )
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.buildArtifacts(
                reporter,
                ResourceManager(),
                com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                options,
                NULL_CHECKER,
                ActionOutputDirectoryHelper.createForTesting(),
                null,
                null
            )

        // NULL_CHECKER here means action cache, which would be our savior, is not in play.
        skyframeExecutor.prepareBuildingForTestingOnly(
            reporter,
            com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting()
        )
        val result: EvaluationResult<FileArtifactValue?> =
            evaluate<T?>(Artifact.keys(com.google.common.collect.ImmutableList.of<E?>(output1)))
        assertThat(result.hasError()).isFalse()
        skyframeExecutor.prepareBuildingForTestingOnly(
            reporter,
            com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting()
        )
        val result2: EvaluationResult<FileArtifactValue?> =
            evaluate<T?>(Artifact.keys(com.google.common.collect.ImmutableList.of<E?>(top, output2)))
        assertThat(result2.hasError()).isFalse()
        TrackingAwaiter.INSTANCE.assertNoErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun interruptDoesntSuppressErrorOutput() {
        val root: Path = execRoot
        val execPath: PathFragment = PathFragment.create("out").getRelative("dir")
        val cyclesourceFragment: PathFragment? = PathFragment.create("cyclesource")
        val cycleArtifact: Artifact.SourceArtifact =
            SourceArtifact(
                ArtifactRoot.asSourceRoot(Root.fromPath(rootDirectory)),
                cyclesourceFragment,
                ArtifactOwner.NULL_OWNER
            )
        rootDirectory.getRelative(cyclesourceFragment).createSymbolicLink(cyclesourceFragment)
        val lc1: ActionLookupKey = InjectedActionLookupKey("lc1")
        val output: Artifact? =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("cycleOutput"),
                lc1
            )
        val action1: Action = DummyAction(cycleArtifact, output)
        val ctValue1: SkyValue? =
            ValueWithMetadata.normal(
                createActionLookupValue(action1, lc1),
                null,
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )
        val lc2: ActionLookupKey = InjectedActionLookupKey("lc2")
        val output2: Artifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("bar"),
                lc2
            )
        val startedSleep: CountDownLatch = CountDownLatch(1)
        val slowAction: Action =
            TestAction(
                java.util.concurrent.Callable {
                    startedSleep.countDown()
                    java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                    throw java.lang.IllegalStateException("Should have been interrupted")
                } as java.util.concurrent.Callable<java.lang.Void?>,
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                com.google.common.collect.ImmutableSet.of<E?>(output2))
        val ctValue2: SkyValue? =
            ValueWithMetadata.normal(
                createActionLookupValue(slowAction, lc2),
                null,
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )
        configureActionExecutor()
        skyframeExecutor
            .getEvaluator()
            .injectGraphTransformerForTesting(
                NotifyingHelper.makeNotifyingTransformer(
                    NotifyingHelper.Listener { key: SkyKey?, type: NotifyingHelper.EventType?, order: NotifyingHelper.Order?, context: Any? ->
                        if (NotifyingHelper.EventType.IS_READY == type
                            && key is ActionLookupData
                            && lc1.equals(key.getActionLookupKey())
                        ) {
                            TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(startedSleep, "No sleep")
                        }
                    })
            )
        skyframeExecutor
            .getDifferencerForTesting()
            .inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    lc1,
                    Delta.justNew(ctValue1),
                    lc2,
                    Delta.justNew(ctValue2)
                )
            )
        // Do a null build, so that the skyframe executor initializes the action executor properly.
        skyframeExecutor.setActionOutputRoot(outputPath)
        skyframeExecutor.setActionExecutionProgressReportingObjects(
            EMPTY_PROGRESS_SUPPLIER,
            EMPTY_COMPLETION_RECEIVER,
            ActionExecutionStatusReporter.create(reporter)
        )
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.buildArtifacts(
                reporter,
                ResourceManager(),
                com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                options,
                NULL_CHECKER,
                ActionOutputDirectoryHelper.createForTesting(),
                null,
                null
            )

        skyframeExecutor.prepareBuildingForTestingOnly(
            reporter,
            com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting()
        )
        reporter.removeHandler(failFastHandler) // Expect errors.
        evaluate<T?>(Artifact.keys(com.google.common.collect.ImmutableList.of<E?>(output, output2)))
        assertContainsEvent(
            "Test dir/cycleOutput failed: error reading file 'cyclesource': Symlink cycle"
        )
        assertContainsEvent("Test dir/cycleOutput failed: 1 input file(s) are in error")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noEventStorageForNonIncrementalBuild() {
        val skyKey: SkyKey = GraphTester.skyKey("key")
        extraSkyFunctions.put(
            skyKey.functionName(),
            SkyFunction { key, env ->
                env.getListener().handle(com.google.devtools.build.lib.events.Event.warn("warning"))
                env.getListener()
                    .post(
                        object : Postable {
                            override fun storeForReplay(): Boolean {
                                return@put true
                            }
                        })
                object : SkyValue() {}
            })
        initializeSkyframeExecutor()
        skyframeExecutor.setActive(false)
        skyframeExecutor.decideKeepIncrementalState( /* batch= */
            false,  /* keepStateAfterBuild= */
            true,  /* shouldTrackIncrementalState= */
            false,  /* heuristicallyDropNodes= */
            false,  /* discardAnalysisCache= */
            false,
            reporter
        )
        skyframeExecutor.setActive(true)
        syncSkyframeExecutor()

        val result: EvaluationResult<*> =
            evaluate<SkyValue?>(com.google.common.collect.ImmutableList.of<SkyKey?>(skyKey))
        assertThat(result.hasError()).isFalse()
        assertContainsEvent("warning")

        val valueWithMetadata: SkyValue? =
            skyframeExecutor
                .getEvaluator()
                .getExistingEntryAtCurrentlyEvaluatingVersion(skyKey)
                .getValueMaybeWithMetadata()
        assertThat(ValueWithMetadata.getEvents(valueWithMetadata).toList()).isEmpty()
    }

    /**
     * Tests that events from action lookup keys (i.e., analysis events) are not stored in execution.
     * This test is actually more extreme than Blaze is, since it skips the analysis phase and so
     * *never* emits the analysis events, while in reality Blaze will always emit the analysis
     * events, during the analysis phase.
     * 
     * 
     * Also incidentally tests that events coming from action execution are actually not stored at
     * all.
     * 
     * 
     * The boolean TestParameter skymeld is to ensure that this behavior is consistent even for
     * skymeld mode.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun analysisEventsNotStoredInExecution(@TestParameter skymeld: Boolean) {
        val root: Path = execRoot
        val execPath: PathFragment = PathFragment.create("out").getRelative("dir")
        val lc1: ActionLookupKey = InjectedActionLookupKey("lc1")
        val output: Artifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("foo"),
                lc1
            )
        val action1: Action = WarningAction(com.google.common.collect.ImmutableList.of<Artifact?>(), output, "action 1")
        val ctValue1: SkyValue? =
            ValueWithMetadata.normal(
                createActionLookupValue(action1, lc1),
                null,
                NestedSetBuilder.create(
                    Order.STABLE_ORDER,
                    com.google.devtools.build.lib.events.Event.warn("analysis warning 1")
                )
            )
        val lc2: ActionLookupKey = InjectedActionLookupKey("lc2")
        val output2: Artifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("bar"),
                lc2
            )
        val action2: Action =
            WarningAction(com.google.common.collect.ImmutableList.of<Artifact?>(output), output2, "action 2")
        val ctValue2: SkyValue? =
            ValueWithMetadata.normal(
                createActionLookupValue(action2, lc2),
                null,
                NestedSetBuilder.create(
                    Order.STABLE_ORDER,
                    com.google.devtools.build.lib.events.Event.warn("analysis warning 2")
                )
            )
        configureActionExecutor()
        skyframeExecutor
            .getDifferencerForTesting()
            .inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    lc1,
                    Delta.justNew(ctValue1),
                    lc2,
                    Delta.justNew(ctValue2)
                )
            )
        // Do a null build, so that the skyframe executor initializes the action executor properly.
        skyframeExecutor.setActionOutputRoot(outputPath)
        skyframeExecutor.setActionExecutionProgressReportingObjects(
            EMPTY_PROGRESS_SUPPLIER,
            EMPTY_COMPLETION_RECEIVER,
            ActionExecutionStatusReporter.create(reporter)
        )
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.buildArtifacts(
                reporter,
                ResourceManager(),
                com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                options,
                NULL_CHECKER,
                ActionOutputDirectoryHelper.createForTesting(),
                null,
                null
            )

        skyframeExecutor.prepareBuildingForTestingOnly(
            reporter,
            com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
            options,
            NULL_CHECKER,
            ActionOutputDirectoryHelper.createForTesting()
        )

        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(SequencedSkyframeExecutor.DEFAULT_THREAD_COUNT)
                .setEventHandler(reporter)
                .setMergingSkyframeAnalysisExecutionPhases(skymeld)
                .build()
        evaluateWithEvaluationContext<T?>(
            com.google.common.collect.ImmutableList.of<E?>(Artifact.key(output2)),
            evaluationContext
        )
        assertContainsEvent("action 1")
        assertContainsEvent("action 2")
        assertDoesNotContainEvent("analysis warning 1")
        assertDoesNotContainEvent("analysis warning 2")

        // Action's warnings are not stored, and configured target warnings never seen.
        assertThat(
            ValueWithMetadata.getEvents(
                skyframeExecutor
                    .getEvaluator()
                    .getExistingEntryAtCurrentlyEvaluatingVersion(
                        ActionLookupData.create(lc1, 0)
                    )
                    .getValueMaybeWithMetadata()
            )
                .toList()
        )
            .isEmpty()
        assertThat(
            ValueWithMetadata.getEvents(
                skyframeExecutor
                    .getEvaluator()
                    .getExistingEntryAtCurrentlyEvaluatingVersion(
                        ActionLookupData.create(lc2, 0)
                    )
                    .getValueMaybeWithMetadata()
            )
                .toList()
        )
            .isEmpty()
    }

    private class WarningAction(
        inputs: com.google.common.collect.ImmutableList<Artifact?>?,
        output: Artifact,
        private val warningText: String?
    ) : AbstractAction(
        ActionsTestUtil.Companion.NULL_ACTION_OWNER,
        NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ().addAll(inputs).build(),
        com.google.common.collect.ImmutableSet.of<E?>(output)
    ) {
        val mnemonic: String
            get() = "warning action"

        protected override fun computeKey(
            actionKeyContext: ActionKeyContext?,
            inputMetadataProvider: InputMetadataProvider?,
            fp: Fingerprint
        ) {
            fp.addString(warningText)
            fp.addPath(getPrimaryOutput().getExecPath())
        }

        @Throws(ActionExecutionException::class)
        public override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
            actionExecutionContext.getEventHandler()
                .handle(com.google.devtools.build.lib.events.Event.warn(warningText))
            try {
                FileSystemUtils.createEmptyFile(actionExecutionContext.getInputPath(getPrimaryOutput()))
            } catch (e: IOException) {
                throw ActionExecutionException(
                    e, this, false, CrashFailureDetails.detailedExitCodeForThrowable(e)
                )
            }
            return ActionResult.EMPTY
        }
    }

    /** Dummy action that throws a catastrophic error when it runs.  */
    private open class CatastrophicAction(output: Artifact?) :
        DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output) {
        @Throws(ActionExecutionException::class)
        override fun execute(actionExecutionContext: ActionExecutionContext?): ActionResult {
            throw ActionExecutionException(
                "message",
                java.lang.Exception("just cause"),
                this,  /* catastrophe= */
                true,
                expectedDetailedExitCode
            )
        }

        companion object {
            val expectedDetailedExitCode: DetailedExitCode? = DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setCrash(Crash.newBuilder().setCode(Crash.Code.CRASH_UNKNOWN))
                    .build()
            )
        }
    }

    /** Dummy action that flips a boolean when it runs.  */
    private class MarkerAction(output: Artifact?, executed: AtomicBoolean) :
        DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output) {
        private val executed: AtomicBoolean

        init {
            this.executed = executed
            Truth.assertThat(executed.get()).isFalse()
        }

        @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
        override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
            val actionResult: ActionResult = super.execute(actionExecutionContext)
            Truth.assertThat(executed.getAndSet(true)).isFalse()
            return actionResult
        }
    }

    @Throws(IOException::class)
    private fun setupEmbeddedArtifacts() {
        val embeddedTools: MutableList<String?> = analysisMock.embeddedTools
        directories.getEmbeddedBinariesRoot().createDirectoryAndParents()
        for (embeddedToolName in embeddedTools) {
            val toolPath: Path? = directories.getEmbeddedBinariesRoot().getRelative(embeddedToolName)
            FileSystemUtils.touchFile(toolPath)
        }
    }

    /** Test appropriate behavior when an action halts the build with a catastrophic failure.  */
    @Throws(java.lang.Exception::class)
    private fun runCatastropheHaltsBuild() {
        val root: Path = execRoot
        val execPath: PathFragment = PathFragment.create("out").getRelative("dir")
        val lc1: ActionLookupKey = InjectedActionLookupKey("lc1")
        val output: Artifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("foo"),
                lc1
            )
        val action1: Action = CatastrophicAction(output)
        val ctValue1: ActionLookupValue = createActionLookupValue(action1, lc1)
        val lc2: ActionLookupKey = InjectedActionLookupKey("lc2")
        val output2: Artifact? =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("bar"),
                lc2
            )
        val markerRan: AtomicBoolean = AtomicBoolean(false)
        val action2: Action = MarkerAction(output2, markerRan)
        val ctValue2: ActionLookupValue = createActionLookupValue(action2, lc2)

        // Perform testing-related setup.
        skyframeExecutor
            .getDifferencerForTesting()
            .inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    lc1,
                    Delta.justNew(ctValue1),
                    lc2,
                    Delta.justNew(ctValue2)
                )
            )
        val collector = TopLevelTargetBuiltEventCollector()
        skyframeExecutor.setEventBus(com.google.common.eventbus.EventBus())
        skyframeExecutor.getEventBus().register(collector)
        setupEmbeddedArtifacts()
        skyframeExecutor.setActionOutputRoot(outputPath)
        skyframeExecutor.setActionExecutionProgressReportingObjects(
            EMPTY_PROGRESS_SUPPLIER,
            EMPTY_COMPLETION_RECEIVER,
            ActionExecutionStatusReporter.create(reporter)
        )

        reporter.removeHandler(failFastHandler) // Expect errors.
        val builder: Builder =
            SkyframeBuilder(
                skyframeExecutor,
                ResourceManager(),
                NULL_CHECKER,  /* actionExecutionSalt= */
                "",
                ModifiedFileSet.EVERYTHING_MODIFIED,  /* fileCache= */
                null,
                ActionInputPrefetcher.NONE,
                ActionOutputDirectoryHelper.createForTesting(),
                BugReporter.defaultInstance()
            )
        // Note that since ImmutableSet iterates through its elements in the order they are passed in
        // here, we are guaranteed that output will be built before output2, throwing an exception and
        // shutting down the build before output2 is requested.
        val normalArtifacts: MutableSet<Artifact?> =
            com.google.common.collect.ImmutableSet.of<Artifact?>(output, output2)
        try {
            val e: BuildFailedException =
                org.junit.Assert.assertThrows<T>(
                    BuildFailedException::class.java,
                    org.junit.function.ThrowingRunnable {
                        builder.buildArtifacts(
                            reporter,
                            normalArtifacts,
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
                            options,
                            null,
                            null,
                            OutputChecker.TRUST_LOCAL_ONLY
                        )
                    })
            // The catastrophic exception should be propagated into the BuildFailedException whether or
            // not --keep_going is set.
            assertThat(e.getDetailedExitCode()).isEqualTo(CatastrophicAction.Companion.expectedDetailedExitCode)
            Truth.assertThat(collector.getCollectedEvents()).isEmpty()
            Truth.assertThat(markerRan.get()).isFalse()
        } finally {
            skyframeExecutor.getEventBus().unregister(collector)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCatastropheInNoKeepGoing() {
        options.parse("--nokeep_going", "--jobs=1")
        runCatastropheHaltsBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCatastrophicBuild() {
        options.parse("--keep_going", "--jobs=1")
        runCatastropheHaltsBuild()
    }

    /**
     * Test appropriate behavior when an action halts the build with a transitive catastrophic
     * failure.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveCatastropheHaltsBuild() {
        options.parse("--keep_going", "--jobs=5")

        val root: Path = execRoot
        val execPath: PathFragment = PathFragment.create("out").getRelative("dir")
        val catastropheCTK: ActionLookupKey = InjectedActionLookupKey("catastrophe")
        val catastropheArtifact: Artifact? =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("zcatas"),
                catastropheCTK
            )
        val failureHappened: CountDownLatch = CountDownLatch(1)
        val catastrophicAction: Action =
            object : CatastrophicAction(catastropheArtifact) {
                @Throws(ActionExecutionException::class)
                override fun execute(actionExecutionContext: ActionExecutionContext?): ActionResult {
                    TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                        failureHappened, "didn't count failure"
                    )
                    return super.execute(actionExecutionContext)
                }
            }
        val catastropheALV: ActionLookupValue = createActionLookupValue(catastrophicAction, catastropheCTK)
        val failureCTK: ActionLookupKey = InjectedActionLookupKey("failure")
        val failureArtifact: Artifact? =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("fail"),
                failureCTK
            )
        val failureAction: Action = FailedExecAction(failureArtifact, USER_DETAILED_EXIT_CODE)
        val failureALV: ActionLookupValue = createActionLookupValue(failureAction, failureCTK)
        val topCTK: ActionLookupKey = InjectedActionLookupKey("top")
        val topArtifact: Artifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("top"),
                topCTK
            )
        val topAction: Action =
            DummyAction(
                NestedSetBuilder.create(Order.STABLE_ORDER, failureArtifact, catastropheArtifact),
                topArtifact
            )
        val topALV: ActionLookupValue = createActionLookupValue(topAction, topCTK)
        // Perform testing-related setup.
        skyframeExecutor
            .getDifferencerForTesting()
            .inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    catastropheCTK, Delta.justNew(catastropheALV),
                    failureCTK, Delta.justNew(failureALV),
                    topCTK, Delta.justNew(topALV)
                )
            )
        skyframeExecutor
            .getEvaluator()
            .injectGraphTransformerForTesting(
                DeterministicHelper.makeTransformer(
                    NotifyingHelper.Listener { key: SkyKey?, type: NotifyingHelper.EventType?, order: NotifyingHelper.Order?, context: Any? ->
                        if (key.equals(Artifact.key(failureArtifact)) && type == NotifyingHelper.EventType.SET_VALUE) {
                            failureHappened.countDown()
                        }
                    },  /* deterministic= */
                    true
                )
            )
        val collector = TopLevelTargetBuiltEventCollector()
        skyframeExecutor.setEventBus(com.google.common.eventbus.EventBus())
        skyframeExecutor.getEventBus().register(collector)
        setupEmbeddedArtifacts()
        skyframeExecutor.setActionOutputRoot(outputPath)
        skyframeExecutor.setActionExecutionProgressReportingObjects(
            EMPTY_PROGRESS_SUPPLIER,
            EMPTY_COMPLETION_RECEIVER,
            ActionExecutionStatusReporter.create(reporter)
        )

        reporter.removeHandler(failFastHandler) // Expect errors.
        val builder: Builder =
            SkyframeBuilder(
                skyframeExecutor,
                ResourceManager(),
                NULL_CHECKER,  /* actionExecutionSalt= */
                "",
                ModifiedFileSet.EVERYTHING_MODIFIED,  /* fileCache= */
                null,
                ActionInputPrefetcher.NONE,
                ActionOutputDirectoryHelper.createForTesting(),
                BugReporter.defaultInstance()
            )
        val normalArtifacts: MutableSet<Artifact?> = com.google.common.collect.ImmutableSet.of<Artifact?>(topArtifact)
        try {
            val e: BuildFailedException =
                org.junit.Assert.assertThrows<T>(
                    BuildFailedException::class.java,
                    org.junit.function.ThrowingRunnable {
                        builder.buildArtifacts(
                            reporter,
                            normalArtifacts,
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
                            options,
                            null,
                            null,
                            OutputChecker.TRUST_LOCAL_ONLY
                        )
                    })
            // The catastrophic exception should be propagated into the BuildFailedException whether or
            // not --keep_going is set.
            assertThat(e.getDetailedExitCode()).isEqualTo(CatastrophicAction.Companion.expectedDetailedExitCode)
            Truth.assertThat(collector.getCollectedEvents()).isEmpty()
        } finally {
            skyframeExecutor.getEventBus().unregister(collector)
        }
    }

    /**
     * Test appropriate behavior when an action halts the build with a transitive catastrophic
     * failure.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCatastropheAndNonCatastropheInCompletion() {
        options.parse("--keep_going", "--jobs=5")

        val root: Path = execRoot
        val execPath: PathFragment = PathFragment.create("out").getRelative("dir")
        val configuredTargetKey: ActionLookupKey = InjectedActionLookupKey("key")
        val catastropheArtifact: Artifact? =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("catas"),
                configuredTargetKey
            )
        val failedSize = 100
        val failureHappened: CountDownLatch = CountDownLatch(failedSize)
        val catastrophicAction: Action =
            object : CatastrophicAction(catastropheArtifact) {
                @Throws(ActionExecutionException::class)
                override fun execute(actionExecutionContext: ActionExecutionContext?): ActionResult {
                    TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                        failureHappened, "didn't count failure"
                    )
                    return super.execute(actionExecutionContext)
                }
            }
        // Because of random map ordering when getting values back in CompletionFunction, we just
        // sprinkle our failure nodes randomly about the alphabet, trusting that at least one will come
        // before "catas".
        val failedActions: MutableList<Action> = java.util.ArrayList<Action>(failedSize)
        val failedArtifacts: LinkedHashSet<Artifact?> = LinkedHashSet<Artifact?>()
        for (i in 0..<failedSize) {
            val failString =
                com.google.common.hash.HashCode.fromBytes(("fail" + i).toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                    .toString()
            val failureArtifact: Artifact? =
                DerivedArtifact.create(
                    ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                    execPath.getRelative(failString),
                    configuredTargetKey
                )
            failedArtifacts.add(failureArtifact)
            failedActions.add(FailedExecAction(failureArtifact, USER_DETAILED_EXIT_CODE))
        }
        val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> =
            com.google.common.collect.ImmutableList.builder<ActionAnalysisMetadata?>()
                .add(catastrophicAction)
                .addAll(failedActions)
                .build()
        Actions.assignOwnersAndThrowIfConflictToleratingSharedActions(
            ActionKeyContext(), actions, configuredTargetKey
        )
        val nonRuleActionLookupValue: ActionLookupValue = BasicActionLookupValue(actions)
        val failedActionKeys: HashSet<ActionLookupData?> = HashSet<ActionLookupData?>()
        for (failedAction in failedActions) {
            failedActionKeys.add(
                (failedAction.getPrimaryOutput() as Artifact.DerivedArtifact).getGeneratingActionKey()
            )
        }

        // Perform testing-related setup.
        skyframeExecutor
            .getDifferencerForTesting()
            .inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    configuredTargetKey,
                    Delta.justNew(nonRuleActionLookupValue)
                )
            )
        skyframeExecutor
            .getEvaluator()
            .injectGraphTransformerForTesting(
                DeterministicHelper.makeTransformer(
                    NotifyingHelper.Listener { key: SkyKey?, type: NotifyingHelper.EventType?, order: NotifyingHelper.Order?, context: Any? ->
                        if ((key is ActionLookupData)
                            && failedActionKeys.contains(key)
                            && type == NotifyingHelper.EventType.SET_VALUE
                        ) {
                            failureHappened.countDown()
                        }
                    },  // Determinism actually doesn't help here because the internal maps are still
                    // effectively unordered.
                    /* deterministic= */
                    true
                )
            )
        val collector = TopLevelTargetBuiltEventCollector()
        skyframeExecutor.setEventBus(com.google.common.eventbus.EventBus())
        skyframeExecutor.getEventBus().register(collector)
        setupEmbeddedArtifacts()
        skyframeExecutor.setActionOutputRoot(outputPath)
        skyframeExecutor.setActionExecutionProgressReportingObjects(
            EMPTY_PROGRESS_SUPPLIER,
            EMPTY_COMPLETION_RECEIVER,
            ActionExecutionStatusReporter.create(reporter)
        )

        reporter.removeHandler(failFastHandler) // Expect errors.
        val builder: Builder =
            SkyframeBuilder(
                skyframeExecutor,
                ResourceManager(),
                NULL_CHECKER,  /* actionExecutionSalt= */
                "",
                ModifiedFileSet.EVERYTHING_MODIFIED,  /* fileCache= */
                null,
                ActionInputPrefetcher.NONE,
                ActionOutputDirectoryHelper.createForTesting(),
                BugReporter.defaultInstance()
            )
        try {
            val e: BuildFailedException =
                org.junit.Assert.assertThrows<T>(
                    BuildFailedException::class.java,
                    org.junit.function.ThrowingRunnable {
                        builder.buildArtifacts(
                            reporter,
                            com.google.common.collect.ImmutableSet.builder<Artifact?>()
                                .addAll(failedArtifacts)
                                .add(catastropheArtifact)
                                .build(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
                            options,
                            null,
                            TopLevelArtifactContext( /* runTestsExclusively= */
                                false,
                                false,
                                OutputGroupInfo.determineOutputGroups(
                                    com.google.common.collect.ImmutableList.of<E?>(),
                                    OutputGroupInfo.ValidationMode.OUTPUT_GROUP,  /* shouldRunTests= */
                                    false
                                )
                            ),
                            OutputChecker.TRUST_LOCAL_ONLY
                        )
                    })
            // The catastrophic exception should be propagated into the BuildFailedException whether or
            // not --keep_going is set.
            assertThat(e.getDetailedExitCode()).isEqualTo(CatastrophicAction.Companion.expectedDetailedExitCode)
            Truth.assertThat(collector.getCollectedEvents()).isEmpty()
        } finally {
            skyframeExecutor.getEventBus().unregister(collector)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCatastrophicBuildWithoutEdges() {
        options.parse("--keep_going", "--jobs=1", "--discard_analysis_cache")
        skyframeExecutor.setActive(false)
        skyframeExecutor.decideKeepIncrementalState( /* batch= */
            true,  /* keepStateAfterBuild= */
            true,  /* shouldTrackIncrementalState= */
            true,  /* heuristicallyDropNodes= */
            false,  /* discardAnalysisCache= */
            true,
            reporter
        )
        skyframeExecutor.setActive(true)
        runCatastropheHaltsBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExceptionComparator() {
        options.parse("--keep_going", "--jobs=5")

        val root: Path = execRoot
        val execPath: PathFragment = PathFragment.create("out").getRelative("dir")
        val configuredTargetKey: ActionLookupKey = InjectedActionLookupKey("key")
        val dummyArtifact: Artifact? =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("catas"),
                configuredTargetKey
            )

        val catastropheWithUserExitCode: ActionExecutionException =
            ActionExecutionException(
                "foo",
                java.lang.Exception("bar"),
                DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), dummyArtifact),  /* catastrophe= */
                true,
                USER_DETAILED_EXIT_CODE
            )
        val catastropheWithInfrastructureExitCode: ActionExecutionException =
            ActionExecutionException(
                "foo",
                java.lang.Exception("bar"),
                DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), dummyArtifact),  /* catastrophe= */
                true,
                INFRA_DETAILED_EXIT_CODE
            )
        val nonCatastropheWithUserExitCode: ActionExecutionException =
            ActionExecutionException(
                "foo",
                java.lang.Exception("bar"),
                DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), dummyArtifact),  /* catastrophe= */
                false,
                USER_DETAILED_EXIT_CODE
            )
        val nonCatastropheWithInfrastructureExitCode: ActionExecutionException =
            ActionExecutionException(
                "foo",
                java.lang.Exception("bar"),
                DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), dummyArtifact),  /* catastrophe= */
                false,
                INFRA_DETAILED_EXIT_CODE
            )

        val exceptionsWithIncreasingSeverity: com.google.common.collect.ImmutableList<ActionExecutionException?> =
            com.google.common.collect.ImmutableList.of<ActionExecutionException?>(
                nonCatastropheWithUserExitCode,
                nonCatastropheWithInfrastructureExitCode,
                catastropheWithUserExitCode,
                catastropheWithInfrastructureExitCode
            )
        for (i in 0..<exceptionsWithIncreasingSeverity.size - 1) {
            for (j in i + 1..<exceptionsWithIncreasingSeverity.size) {
                assertThat(
                    CompletionFunction.SEVERITY_ORDERING.max(
                        exceptionsWithIncreasingSeverity.get(i),
                        exceptionsWithIncreasingSeverity.get(j)
                    )
                )
                    .isEqualTo(exceptionsWithIncreasingSeverity.get(j))
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCatastropheReportingWithError() {
        options.parse("--keep_going", "--jobs=1")
        val root: Path = execRoot
        val execPath: PathFragment = PathFragment.create("out").getRelative("dir")
        // When we have an action that throws a (non-catastrophic) exception when it is executed,
        val failedKey: ActionLookupKey = InjectedActionLookupKey("failed")
        val failedOutput: Artifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("failed"),
                failedKey
            )
        val failedActionReference: AtomicReference<Action?> = AtomicReference<Action?>()
        val failedAction: Action =
            TestAction(
                object : java.util.concurrent.Callable<java.lang.Void?> {
                    @Throws(ActionExecutionException::class)
                    override fun call(): java.lang.Void? {
                        throw ActionExecutionException(
                            "typical non-catastrophic user failure",
                            failedActionReference.get(),  /* catastrophe= */
                            false,
                            USER_DETAILED_EXIT_CODE
                        )
                    }
                },
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                com.google.common.collect.ImmutableSet.of<E?>(failedOutput)
            )
        failedActionReference.set(failedAction)
        val failedTarget: ActionLookupValue = createActionLookupValue(failedAction, failedKey)

        // And an action that throws a catastrophic exception when it is executed,
        val catastrophicKey: ActionLookupKey = InjectedActionLookupKey("catastrophic")
        val catastrophicOutput: Artifact? =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("catastrophic"),
                catastrophicKey
            )
        val catastrophicAction: Action = CatastrophicAction(catastrophicOutput)
        val catastrophicTarget: ActionLookupValue =
            createActionLookupValue(catastrophicAction, catastrophicKey)

        // And the relevant configured targets have been injected into the graph,
        skyframeExecutor
            .getDifferencerForTesting()
            .inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    failedKey, Delta.justNew(failedTarget),
                    catastrophicKey, Delta.justNew(catastrophicTarget)
                )
            )
        val collector = TopLevelTargetBuiltEventCollector()
        skyframeExecutor.setEventBus(com.google.common.eventbus.EventBus())
        skyframeExecutor.getEventBus().register(collector)
        setupEmbeddedArtifacts()
        skyframeExecutor.setActionOutputRoot(outputPath)
        skyframeExecutor.setActionExecutionProgressReportingObjects(
            EMPTY_PROGRESS_SUPPLIER,
            EMPTY_COMPLETION_RECEIVER,
            ActionExecutionStatusReporter.create(reporter)
        )

        // And the two artifacts are requested,
        reporter.removeHandler(failFastHandler) // Expect errors.
        val builder: Builder =
            SkyframeBuilder(
                skyframeExecutor,
                ResourceManager(),
                NULL_CHECKER,  /* actionExecutionSalt= */
                "",
                ModifiedFileSet.EVERYTHING_MODIFIED,  /* fileCache= */
                null,
                ActionInputPrefetcher.NONE,
                ActionOutputDirectoryHelper.createForTesting(),
                BugReporter.defaultInstance()
            )
        // Note that since ImmutableSet iterates through its elements in the order they are passed in
        // here, we are guaranteed that failedOutput will be built before catastrophicOutput is
        // requested, putting a top-level failure into the build result.
        val normalArtifacts: MutableSet<Artifact?> =
            com.google.common.collect.ImmutableSet.of<Artifact?>(failedOutput, catastrophicOutput)
        try {
            val e: BuildFailedException =
                org.junit.Assert.assertThrows<T>(
                    BuildFailedException::class.java,
                    org.junit.function.ThrowingRunnable {
                        builder.buildArtifacts(
                            reporter,
                            normalArtifacts,
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
                            options,
                            null,
                            null,
                            OutputChecker.TRUST_LOCAL_ONLY
                        )
                    })
            // The catastrophic exception should be propagated into the BuildFailedException whether or
            // not --keep_going is set.
            assertThat(e.getDetailedExitCode()).isEqualTo(CatastrophicAction.Companion.expectedDetailedExitCode)
            Truth.assertThat(collector.getCollectedEvents()).isEmpty()
        } finally {
            skyframeExecutor.getEventBus().unregister(collector)
        }
    }

    /** Dummy action that throws a ActionExecution error when it runs.  */
    private class FailedExecAction(output: Artifact?, detailedExitCode: DetailedExitCode?) :
        DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output) {
        private val detailedExitCode: DetailedExitCode?

        init {
            this.detailedExitCode = detailedExitCode
        }

        @Throws(ActionExecutionException::class)
        override fun execute(actionExecutionContext: ActionExecutionContext?): ActionResult {
            throw ActionExecutionException(
                "foo", java.lang.Exception("bar"), this,  /* catastrophe= */false, detailedExitCode
            )
        }
    }

    /**
     * Verify SkyframeBuilder returns correct user error code as global error code when:
     * 
     * 
     *  1. keepGoing mode is true.
     *  1. user error code exists.
     *  1. no infrastructure error code exists.
     * 
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingExitCodeWithUserError() {
        options.parse("--keep_going", "--jobs=1")
        val root: Path = execRoot
        val execPath: PathFragment = PathFragment.create("out").getRelative("dir")

        val succeededKey: ActionLookupKey = InjectedActionLookupKey("succeeded")
        val succeededOutput: Artifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("succeeded"),
                succeededKey
            )

        val failedKey: ActionLookupKey = InjectedActionLookupKey("failed")
        val failedOutput: Artifact? =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("failed"),
                failedKey
            )

        // Create 1 succeeded key and 1 failed key with user error
        val succeededAction: Action =
            DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), succeededOutput)
        val succeededTarget: ActionLookupValue = createActionLookupValue(succeededAction, succeededKey)
        val failedAction: Action = FailedExecAction(failedOutput, USER_DETAILED_EXIT_CODE)
        val failedTarget: ActionLookupValue = createActionLookupValue(failedAction, failedKey)

        // Inject the targets into the graph,
        skyframeExecutor
            .getDifferencerForTesting()
            .inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    succeededKey, Delta.justNew(succeededTarget),
                    failedKey, Delta.justNew(failedTarget)
                )
            )
        skyframeExecutor.setEventBus(com.google.common.eventbus.EventBus())
        setupEmbeddedArtifacts()
        skyframeExecutor.setActionOutputRoot(outputPath)
        skyframeExecutor.setActionExecutionProgressReportingObjects(
            EMPTY_PROGRESS_SUPPLIER,
            EMPTY_COMPLETION_RECEIVER,
            ActionExecutionStatusReporter.create(reporter)
        )

        // And the two artifacts are requested,
        reporter.removeHandler(failFastHandler) // Expect errors.
        val builder: Builder =
            SkyframeBuilder(
                skyframeExecutor,
                ResourceManager(),
                NULL_CHECKER,  /* actionExecutionSalt= */
                "",
                ModifiedFileSet.EVERYTHING_MODIFIED,  /* fileCache= */
                null,
                ActionInputPrefetcher.NONE,
                ActionOutputDirectoryHelper.createForTesting(),
                BugReporter.defaultInstance()
            )
        val normalArtifacts: MutableSet<Artifact?> =
            com.google.common.collect.ImmutableSet.of<Artifact?>(succeededOutput, failedOutput)
        val e: BuildFailedException =
            org.junit.Assert.assertThrows<T>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable {
                    builder.buildArtifacts(
                        reporter,
                        normalArtifacts,
                        com.google.common.collect.ImmutableSet.of<E?>(),
                        com.google.common.collect.ImmutableSet.of<E?>(),
                        com.google.common.collect.ImmutableSet.of<E?>(),
                        com.google.common.collect.ImmutableSet.of<E?>(),
                        com.google.common.collect.ImmutableSet.of<E?>(),
                        com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
                        options,
                        null,
                        null,
                        OutputChecker.TRUST_LOCAL_ONLY
                    )
                })
        // The exit code should be propagated into the BuildFailedException whether or not --keep_going
        // is set.
        assertThat(e.getDetailedExitCode()).isEqualTo(USER_DETAILED_EXIT_CODE)
    }

    /**
     * Verify SkyframeBuilder returns correct infrastructure error code as global error code when:
     * 
     * 
     *  1. keepGoing mode is true.
     *  1. infrastructure error code exists.
     * 
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingExitCodeWithUserAndInfrastructureError() {
        options.parse("--keep_going", "--jobs=1")
        val root: Path = execRoot
        val execPath: PathFragment = PathFragment.create("out").getRelative("dir")

        val succeededKey: ActionLookupKey = InjectedActionLookupKey("succeeded")
        val succeededOutput: Artifact? =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("succeeded"),
                succeededKey
            )

        val failedKey1: ActionLookupKey = InjectedActionLookupKey("failed1")
        val failedOutput1: Artifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("failed1"),
                failedKey1
            )

        val failedKey2: ActionLookupKey = InjectedActionLookupKey("failed2")
        val failedOutput2: Artifact? =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("failed2"),
                failedKey2
            )

        // Create 1 succeeded key, 1 failed key with infrastructure error and another failed key with
        // user error.

        // TODO TODO
        val succeededAction: Action =
            DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), succeededOutput)
        val succeededTarget: ActionLookupValue = createActionLookupValue(succeededAction, succeededKey)
        val failedAction1: Action = FailedExecAction(failedOutput1, USER_DETAILED_EXIT_CODE)
        val failedTarget1: ActionLookupValue = createActionLookupValue(failedAction1, failedKey1)
        val failedAction2: Action = FailedExecAction(failedOutput2, INFRA_DETAILED_EXIT_CODE)
        val failedTarget2: ActionLookupValue = createActionLookupValue(failedAction2, failedKey2)

        // Inject the targets into the graph,
        skyframeExecutor
            .getDifferencerForTesting()
            .inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    succeededKey, Delta.justNew(succeededTarget),
                    failedKey1, Delta.justNew(failedTarget1),
                    failedKey2, Delta.justNew(failedTarget2)
                )
            )
        skyframeExecutor.setEventBus(com.google.common.eventbus.EventBus())
        setupEmbeddedArtifacts()
        skyframeExecutor.setActionOutputRoot(outputPath)
        skyframeExecutor.setActionExecutionProgressReportingObjects(
            EMPTY_PROGRESS_SUPPLIER,
            EMPTY_COMPLETION_RECEIVER,
            ActionExecutionStatusReporter.create(reporter)
        )

        // And the two artifacts are requested,
        reporter.removeHandler(failFastHandler) // Expect errors.
        val builder: Builder =
            SkyframeBuilder(
                skyframeExecutor,
                ResourceManager(),
                NULL_CHECKER,  /* actionExecutionSalt= */
                "",
                ModifiedFileSet.EVERYTHING_MODIFIED,  /* fileCache= */
                null,
                ActionInputPrefetcher.NONE,
                ActionOutputDirectoryHelper.createForTesting(),
                BugReporter.defaultInstance()
            )
        val normalArtifacts: MutableSet<Artifact?> =
            com.google.common.collect.ImmutableSet.of<Artifact?>(failedOutput1, failedOutput2)
        val e: BuildFailedException =
            org.junit.Assert.assertThrows<T>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable {
                    builder.buildArtifacts(
                        reporter,
                        normalArtifacts,
                        com.google.common.collect.ImmutableSet.of<E?>(),
                        com.google.common.collect.ImmutableSet.of<E?>(),
                        com.google.common.collect.ImmutableSet.of<E?>(),
                        com.google.common.collect.ImmutableSet.of<E?>(),
                        com.google.common.collect.ImmutableSet.of<E?>(),
                        com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
                        options,
                        null,
                        null,
                        OutputChecker.TRUST_LOCAL_ONLY
                    )
                })
        // The exit code should be propagated into the BuildFailedException whether or not --keep_going
        // is set.
        assertThat(e.getDetailedExitCode()).isEqualTo(INFRA_DETAILED_EXIT_CODE)
    }

    /**
     * Tests that when an input-discovering action terminates input discovery with missing inputs, its
     * progress message goes away. We create an input-discovering action that declares a new input.
     * When that new input is declared, which comes after the scanning is completed, we trigger a
     * progress message, and assert that the message does not contain the "Scanning" message.
     * 
     * 
     * To guard against the output format changing, we also trigger a progress message during the
     * scan, and assert that the message there is as expected.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inputDiscoveryMessageDoesntLinger() {
        val root: Path = execRoot
        val execPath: PathFragment = PathFragment.create("out").getRelative("dir")

        val topKey: ActionLookupKey = InjectedActionLookupKey("top")
        val topOutput: Artifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath.getRelative("top"),
                topKey
            )

        val sourceInput: Artifact =
            SourceArtifact(
                ArtifactRoot.asSourceRoot(Root.fromPath(rootDirectory)),
                PathFragment.create("source.optional"),
                ArtifactOwner.NULL_OWNER
            )
        FileSystemUtils.createEmptyFile(sourceInput.getPath())

        val inputDiscoveringAction: Action =
            object : DummyAction(NestedSetBuilder.create(Order.STABLE_ORDER, sourceInput), topOutput) {
                override fun discoverInputs(actionExecutionContext: ActionExecutionContext): NestedSet<Artifact?> {
                    skyframeExecutor
                        .getActionExecutionStatusReporterForTesting()
                        .showCurrentlyExecutingActions("during scanning ")
                    return super.discoverInputs(actionExecutionContext)
                }
            }

        val topTarget: ActionLookupValue = createActionLookupValue(inputDiscoveringAction, topKey)
        skyframeExecutor
            .getDifferencerForTesting()
            .inject(com.google.common.collect.ImmutableMap.of<K?, V?>(topKey, Delta.justNew(topTarget)))
        // Collect all events.
        eventCollector = EventCollector()
        reporter = com.google.devtools.build.lib.events.Reporter(EventBusEventHandler(eventBus), eventCollector)
        skyframeExecutor.setEventBus(eventBus)
        skyframeExecutor.setActionOutputRoot(outputPath)

        val builder: Builder =
            SkyframeBuilder(
                skyframeExecutor,
                ResourceManager(),
                NULL_CHECKER,  /* actionExecutionSalt= */
                "",
                ModifiedFileSet.EVERYTHING_MODIFIED,  /* fileCache= */
                null,
                ActionInputPrefetcher.NONE,
                ActionOutputDirectoryHelper.createForTesting(),
                BugReporter.defaultInstance()
            )
        builder.buildArtifacts(
            reporter,
            com.google.common.collect.ImmutableSet.of<E?>(topOutput),
            com.google.common.collect.ImmutableSet.of<E?>(),
            com.google.common.collect.ImmutableSet.of<E?>(),
            com.google.common.collect.ImmutableSet.of<E?>(),
            com.google.common.collect.ImmutableSet.of<E?>(),
            com.google.common.collect.ImmutableSet.of<E?>(),
            com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
            options,
            null,
            null,
            OutputChecker.TRUST_LOCAL_ONLY
        )
        MoreAsserts.assertContainsEvent(
            eventCollector, java.util.regex.Pattern.compile(".*during scanning.*\n.*Scanning.*\n.*Test dir/top.*")
        )
        MoreAsserts.assertNotContainsEvent(
            eventCollector, java.util.regex.Pattern.compile(".*after scanning.*\n.*Scanning.*\n.*Test dir/top.*")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun rewindingPrerequisites(@TestParameter trackIncrementalState: Boolean) {
        initializeSkyframeExecutor()
        options.parse("--rewind_lost_inputs")

        skyframeExecutor.setActive(false)
        skyframeExecutor.decideKeepIncrementalState( /* batch= */
            false,  /* keepStateAfterBuild= */
            true,
            trackIncrementalState,  /* heuristicallyDropNodes= */
            false,  /* discardAnalysisCache= */
            false,
            reporter
        )
        skyframeExecutor.setActive(true)

        syncSkyframeExecutor() // Permitted.
    }

    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    private fun syncSkyframeExecutor() {
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.sync(
                reporter,
                createPackageLocator(),
                UUID.randomUUID(),  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                tsgm,
                QuiescingExecutorsImpl.forTesting(),
                options,  /* commandName= */
                "build",  /* commandExecutes= */
                true
            )
    }

    private fun configureActionExecutor() {
        skyframeExecutor.configureActionExecutor( /* fileCache= */
            null,
            ActionInputPrefetcher.NONE,  /* actionExecutionSalt= */
            "",  /* maxStdoutErrBytes= */
            999
        )
    }

    companion object {
        private val USER_DETAILED_EXIT_CODE: DetailedExitCode? = DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setSpawn(Spawn.newBuilder().setCode(Code.NON_ZERO_EXIT))
                .build()
        )
        private val INFRA_DETAILED_EXIT_CODE: DetailedExitCode? = DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setCrash(Crash.newBuilder().setCode(Crash.Code.CRASH_UNKNOWN))
                .build()
        )

        private fun nothingChangedDiffAwarenessFactory(): DiffAwareness.Factory {
            return DiffAwareness.Factory { pathEntry, ignoredPaths, optionsProvider ->
                object : DiffAwareness() {
                    public override fun getCurrentView(options: OptionsProvider?): View? {
                        return@Factory object : View() {}
                    }

                    public override fun getDiff(oldView: View?, newView: View?): ModifiedFileSet {
                        return@Factory ModifiedFileSet.NOTHING_MODIFIED
                    }

                    public override fun name(): String? {
                        return@Factory null
                    }

                    public override fun close() {}
                }
            }
        }

        private val NULL_CHECKER: ActionCacheChecker = ActionCacheChecker(
            AMNESIAC_CACHE,
            FakeArtifactResolverBase(),
            ActionKeyContext(),
            com.google.common.base.Predicates.alwaysTrue<T?>(),
            ProxyMetadataFactory.NO_PROXIES,  /* cacheConfig= */
            null
        )

        private val EMPTY_PROGRESS_SUPPLIER: ProgressSupplier = object : ProgressSupplier() {
            val progressString: String
                get() = ""
        }

        private val EMPTY_COMPLETION_RECEIVER: ActionCompletedReceiver = ActionCompletedReceiver { ald -> }

        @Throws(
            ActionConflictException::class,
            java.lang.InterruptedException::class,
            Actions.ArtifactGeneratedByOtherRuleException::class
        )
        private fun createActionLookupValue(
            generatingAction: ActionAnalysisMetadata, actionLookupKey: ActionLookupKey?
        ): ActionLookupValue {
            val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> =
                com.google.common.collect.ImmutableList.of<ActionAnalysisMetadata?>(generatingAction)
            Actions.assignOwnersAndThrowIfConflict(ActionKeyContext(), actions, actionLookupKey)
            return BasicActionLookupValue(actions)
        }
    }
}
