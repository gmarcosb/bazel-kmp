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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.Action

/** Tests for [FilesystemValueChecker].  */
@RunWith(TestParameterInjector::class)
class FilesystemValueCheckerTest {
    private val fs = MockFileSystem()
    private var differencer: RecordingDifferencer? = null
    private var evaluator: MemoizingEvaluator? = null
    private var pkgRoot: Path? = null

    @TestParameter
    private val batchStat: BatchStatMode? = null

    private val mockModifiedOutputsReceiver: ModifiedOutputsReceiver? =
        Mockito.mock<ModifiedOutputsReceiver?>(ModifiedOutputsReceiver::class.java)
    private val modifiedOutputsCaptor: ArgumentCaptor<Artifact?> =
        ArgumentCaptor.forClass<Artifact?, Artifact?>(Artifact::class.java)

    @Throws(IOException::class)
    private fun createTreeArtifact(relPath: String?): SpecialArtifact {
        val outSegment = "bin"
        val outputDir: Path = fs.getPath("/" + outSegment)
        val outputPath: Path? = outputDir.getRelative(relPath)
        outputDir.createDirectory()
        val derivedRoot: ArtifactRoot =
            ArtifactRoot.asDerivedRoot(fs.getPath("/"), RootType.OUTPUT, outSegment)
        return ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            derivedRoot,
            derivedRoot.getExecPath().getRelative(derivedRoot.getRoot().relativize(outputPath))
        )
    }

    @Throws(IOException::class)
    private fun writeFile(path: Path?, vararg lines: String?) {
        // Make sure we advance the clock to detect modifications which do not change the size, which
        // rely on ctime.
        fs.advanceClockMillis(1)
        FileSystemUtils.writeIsoLatin1(path, lines)
    }

    private class MockFileSystem(clock: com.google.devtools.build.lib.testutil.ManualClock) :
        InMemoryFileSystem(clock, DigestHashFunction.SHA256) {
        var statThrowsRuntimeException: Boolean = false
        var readlinkThrowsIoException: Boolean = false

        internal constructor() : this(com.google.devtools.build.lib.testutil.ManualClock())

        @Throws(IOException::class)
        public override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus {
            if (statThrowsRuntimeException) {
                throw java.lang.RuntimeException("bork")
            }
            return super.statIfFound(path, followSymlinks)
        }

        @Throws(IOException::class)
        public override fun readSymbolicLink(path: PathFragment): PathFragment {
            if (readlinkThrowsIoException) {
                throw IOException("readlink failed")
            }
            return super.readSymbolicLink(path)
        }

        fun advanceClockMillis(millis: Int) {
            (clock as com.google.devtools.build.lib.testutil.ManualClock).advanceMillis(millis.toLong())
        }
    }

    private enum class BatchStatMode {
        DISABLED {
            override fun getBatchStat(fileSystem: FileSystem?): BatchStat? {
                return null
            }
        },
        ENABLED {
            override fun getBatchStat(fileSystem: FileSystem): BatchStat? {
                return BatchStat { paths ->
                    val stats: MutableList<FileStatusWithDigest?> = java.util.ArrayList<FileStatusWithDigest?>()
                    for (pathFrag in paths) {
                        stats.add(
                            FileStatusWithDigestAdapter.maybeAdapt(
                                fileSystem.getPath("/").getRelative(pathFrag).statIfFound(Symlinks.NOFOLLOW)
                            )
                        )
                    }
                    stats
                }
            }
        };

        abstract fun getBatchStat(fileSystem: FileSystem?): BatchStat?
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        val skyFunctions: com.google.common.collect.ImmutableMap.Builder<SkyFunctionName?, SkyFunction?> =
            com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()

        pkgRoot = fs.getPath("/testroot")
        pkgRoot.createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(pkgRoot.getRelative("WORKSPACE"))

        val pkgLocator: AtomicReference<PathPackageLocator?> =
            AtomicReference<PathPackageLocator?>(
                PathPackageLocator(
                    fs.getPath("/output_base"),
                    com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(pkgRoot)),
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                )
            )
        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(pkgRoot, pkgRoot, pkgRoot),
                pkgRoot,
                TestConstants.PRODUCT_NAME
            )
        val externalFilesHelper: ExternalFilesHelper? =
            ExternalFilesHelper.createForTesting(
                pkgLocator,
                ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
                directories
            )
        skyFunctions.put(
            FileStateKey.FILE_STATE,
            FileStateFunction(
                com.google.common.base.Suppliers.ofInstance<T?>(TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())),
                SyscallCache.NO_CACHE,
                externalFilesHelper
            )
        )
        skyFunctions.put(SkyFunctions.FILE, FileFunction(pkgLocator, directories))
        skyFunctions.put(
            FileSymlinkCycleUniquenessFunction.NAME, FileSymlinkCycleUniquenessFunction()
        )
        skyFunctions.put(
            FileSymlinkInfiniteExpansionUniquenessFunction.NAME,
            FileSymlinkInfiniteExpansionUniquenessFunction()
        )
        skyFunctions.put(SkyFunctions.PACKAGE, PackageFunction.newBuilder().build())
        skyFunctions.put(
            SkyFunctions.PACKAGE_LOOKUP,
            PackageLookupFunction(
                AtomicReference<V?>(com.google.common.collect.ImmutableSet.of<Any?>()),
                CrossRepositoryLabelViolationStrategy.ERROR,
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        )

        differencer = SequencedRecordingDifferencer()
        evaluator = InMemoryMemoizingEvaluator(skyFunctions.buildOrThrow(), differencer)
        PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID())
        PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get())
    }

    @Before
    fun setupModifiedOutputReceiverMock() {
        Mockito.doNothing()
            .`when`<Any?>(mockModifiedOutputsReceiver)
            .reportModifiedOutputFile(ArgumentMatchers.anyLong(), modifiedOutputsCaptor.capture())
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val dirtyActionValues_unchangedEmptyTreeArtifactWithArchivedFile_noDirtyKeys: Unit
        get() {
            val treeArtifact: SpecialArtifact = createTreeArtifact("dir")
            treeArtifact.getPath().createDirectoryAndParents()
            val actionExecutionValue: ActionExecutionValue =
                actionValueWithTreeArtifacts(
                    com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(),
                    com.google.common.collect.ImmutableList.of<ArchivedTreeArtifact?>(
                        createArchivedTreeArtifactWithContent(treeArtifact)
                    )
                )

            Truth.assertThat(getDirtyActionValues(actionExecutionValue)).isEmpty()
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val dirtyActionValues_unchangedTreeArtifactWithArchivedFile_noDirtyKeys: Unit
        get() {
            val treeArtifact: SpecialArtifact = createTreeArtifact("dir")
            val actionExecutionValue: ActionExecutionValue =
                actionValueWithTreeArtifacts(
                    com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(
                        createTreeFileArtifactWithContent(treeArtifact, "file1", "content"),
                        createTreeFileArtifactWithContent(treeArtifact, "file2", "content2")
                    ),
                    com.google.common.collect.ImmutableList.of<ArchivedTreeArtifact?>(
                        createArchivedTreeArtifactWithContent(treeArtifact)
                    )
                )

            Truth.assertThat(getDirtyActionValues(actionExecutionValue)).isEmpty()
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val dirtyActionValues_editedArchivedFileForEmptyTreeArtifact_reportsChange: Unit
        get() {
            val treeArtifact: SpecialArtifact = createTreeArtifact("dir")
            treeArtifact.getPath().createDirectoryAndParents()
            val archivedTreeArtifact: ArchivedTreeArtifact =
                createArchivedTreeArtifactWithContent(treeArtifact, "old content")
            val actionExecutionValue: ActionExecutionValue =
                actionValueWithTreeArtifacts(
                    com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(),
                    com.google.common.collect.ImmutableList.of<ArchivedTreeArtifact?>(archivedTreeArtifact)
                )

            writeFile(archivedTreeArtifact.getPath(), "new content")
            Truth.assertThat(getDirtyActionValues(actionExecutionValue))
                .containsExactly(ACTION_LOOKUP_DATA)
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val dirtyActionValues_editedArchivedFileForTreeArtifact_reportsChange: Unit
        get() {
            val treeArtifact: SpecialArtifact = createTreeArtifact("dir")
            val archivedTreeArtifact: ArchivedTreeArtifact =
                createArchivedTreeArtifactWithContent(treeArtifact, "old content")
            val actionExecutionValue: ActionExecutionValue =
                actionValueWithTreeArtifacts(
                    com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(
                        createTreeFileArtifactWithContent(
                            treeArtifact,  /* parentRelativePath= */"file1", "content"
                        ),
                        createTreeFileArtifactWithContent(
                            treeArtifact,  /* parentRelativePath= */"file2", "content2"
                        )
                    ),
                    com.google.common.collect.ImmutableList.of<ArchivedTreeArtifact?>(archivedTreeArtifact)
                )

            writeFile(archivedTreeArtifact.getPath(), "new content")
            Truth.assertThat(getDirtyActionValues(actionExecutionValue))
                .containsExactly(ACTION_LOOKUP_DATA)
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val dirtyActionValues_deletedArchivedFileForTreeArtifact_reportsChange: Unit
        get() {
            val treeArtifact: SpecialArtifact = createTreeArtifact("dir")
            val archivedTreeArtifact: ArchivedTreeArtifact = createArchivedTreeArtifactWithContent(treeArtifact)
            val actionExecutionValue: ActionExecutionValue =
                actionValueWithTreeArtifacts(
                    com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(
                        createTreeFileArtifactWithContent(
                            treeArtifact,  /* parentRelativePath= */"file1", "content"
                        ),
                        createTreeFileArtifactWithContent(
                            treeArtifact,  /* parentRelativePath= */"file2", "content2"
                        )
                    ),
                    com.google.common.collect.ImmutableList.of<ArchivedTreeArtifact?>(archivedTreeArtifact)
                )

            archivedTreeArtifact.getPath().delete()
            Truth.assertThat(getDirtyActionValues(actionExecutionValue))
                .containsExactly(ACTION_LOOKUP_DATA)
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val dirtyActionValues_deletedArchivedFileForEmptyTreeArtifact_reportsChange: Unit
        get() {
            val treeArtifact: SpecialArtifact = createTreeArtifact("dir")
            val archivedTreeArtifact: ArchivedTreeArtifact = createArchivedTreeArtifactWithContent(treeArtifact)
            val actionExecutionValue: ActionExecutionValue =
                actionValueWithTreeArtifacts(
                    com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(),
                    com.google.common.collect.ImmutableList.of<ArchivedTreeArtifact?>(archivedTreeArtifact)
                )

            archivedTreeArtifact.getPath().delete()
            Truth.assertThat(getDirtyActionValues(actionExecutionValue))
                .containsExactly(ACTION_LOOKUP_DATA)
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val dirtyActionValues_editedFileForTreeArtifactWithArchivedFile_reportsChange: Unit
        get() {
            val treeArtifact: SpecialArtifact = createTreeArtifact("dir")
            val child1: TreeFileArtifact =
                createTreeFileArtifactWithContent(
                    treeArtifact,  /* parentRelativePath= */"file1", "old content"
                )
            val actionExecutionValue: ActionExecutionValue =
                actionValueWithTreeArtifacts(
                    com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(
                        child1,
                        createTreeFileArtifactWithContent(
                            treeArtifact,  /* parentRelativePath= */"file2", "content2"
                        )
                    ),
                    com.google.common.collect.ImmutableList.of<ArchivedTreeArtifact?>(
                        createArchivedTreeArtifactWithContent(treeArtifact)
                    )
                )

            writeFile(child1.getPath(), "new content")
            Truth.assertThat(getDirtyActionValues(actionExecutionValue))
                .containsExactly(ACTION_LOOKUP_DATA)
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val dirtyActionValues_treeArtifactWithArchivedArtifact_reportsOnlyChangedKey: Unit
        get() {
            val unchangedTreeArtifact: SpecialArtifact = createTreeArtifact("dir1")
            val unchangedValue: ActionExecutionValue =
                actionValueWithTreeArtifacts(
                    com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(
                        createTreeFileArtifactWithContent(
                            unchangedTreeArtifact,
                            "child"
                        )
                    ),
                    com.google.common.collect.ImmutableList.of<ArchivedTreeArtifact?>(
                        createArchivedTreeArtifactWithContent(unchangedTreeArtifact)
                    )
                )
            val changedTreeArtifact: SpecialArtifact = createTreeArtifact("dir2")
            val changedArchivedTreeArtifact: ArchivedTreeArtifact =
                createArchivedTreeArtifactWithContent(changedTreeArtifact, "old content")
            val changedValue: ActionExecutionValue =
                actionValueWithTreeArtifacts(
                    com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(
                        createTreeFileArtifactWithContent(changedTreeArtifact, "file", "content")
                    ),
                    com.google.common.collect.ImmutableList.of<ArchivedTreeArtifact?>(changedArchivedTreeArtifact)
                )

            writeFile(changedArchivedTreeArtifact.getPath(), "new content")
            Truth.assertThat(
                getDirtyActionValues(
                    com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                        actionLookupData(0),
                        unchangedValue,
                        actionLookupData(1),
                        changedValue
                    )
                )
            )
                .containsExactly(actionLookupData(1))
        }

    @Throws(java.lang.InterruptedException::class)
    private fun getDirtyActionValues(actionExecutionValue: ActionExecutionValue): MutableCollection<SkyKey?> {
        return getDirtyActionValues(
            com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                ACTION_LOOKUP_DATA,
                actionExecutionValue
            )
        )
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getDirtyActionValues(valuesMap: com.google.common.collect.ImmutableMap<SkyKey?, SkyValue?>?): MutableCollection<SkyKey?> {
        return FilesystemValueChecker( /* tsgm= */
            null,
            SyscallCache.NO_CACHE,
            XattrProviderOverrider.NO_OVERRIDE,
            FSVC_THREADS_FOR_TEST
        )
            .getDirtyActionValues(
                valuesMap,
                batchStat!!.getBatchStat(fs),
                ModifiedFileSet.EVERYTHING_MODIFIED,
                OutputChecker.TRUST_LOCAL_ONLY,
                { ignored, ignored2 -> })
    }

    @Throws(IOException::class)
    private fun createTreeFileArtifactWithContent(
        treeArtifact: SpecialArtifact?, parentRelativePath: String?, vararg contentLines: String?
    ): TreeFileArtifact {
        val artifact: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, parentRelativePath)
        writeFile(artifact.getPath(), contentLines)
        return artifact
    }

    @Throws(IOException::class)
    private fun createArchivedTreeArtifactWithContent(
        treeArtifact: SpecialArtifact?, vararg contentLines: String?
    ): ArchivedTreeArtifact {
        val artifact: ArchivedTreeArtifact = ArchivedTreeArtifact.createForTree(treeArtifact)
        writeFile(artifact.getPath(), contentLines)
        return artifact
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmpty() {
        val checker: FilesystemValueChecker =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
        assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimple() {
        val checker: FilesystemValueChecker =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )

        val path: Path? = fs.getPath("/foo")
        FileSystemUtils.createEmptyFile(path)
        assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker))

        val skyKey: SkyKey =
            FileStateValue.key(
                RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/foo"))
            )
        var result: EvaluationResult<SkyValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(skyKey), EVALUATION_OPTIONS)
        assertThat(result.hasError()).isFalse()

        assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker))

        FileSystemUtils.writeContentAsLatin1(path, "hello")
        assertDiffWithNewValues(getDirtyFilesystemKeys(evaluator, checker), skyKey)

        // The dirty bits are not reset until the FileValues are actually revalidated.
        assertDiffWithNewValues(getDirtyFilesystemKeys(evaluator, checker), skyKey)

        differencer.invalidate(com.google.common.collect.ImmutableList.of<E?>(skyKey))
        result = evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(skyKey), EVALUATION_OPTIONS)
        assertThat(result.hasError()).isFalse()
        assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker))
    }

    /**
     * Tests that an already-invalidated value can still be marked changed: symlink points at sym1.
     * Invalidate symlink by changing sym1 from pointing at path to point to sym2. This only dirties
     * (rather than changes) symlink because sym2 still points at path, so all symlink stats remain
     * the same. Then do a null build, change sym1 back to point at path, and change symlink to not be
     * a symlink anymore. The fact that it is not a symlink should be detected.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirtySymlink() {
        val checker: FilesystemValueChecker =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )

        val path: Path? = fs.getPath("/foo")
        FileSystemUtils.writeContentAsLatin1(path, "foo contents")
        // We need the intermediate sym1 and sym2 so that we can dirty a child of symlink without
        // actually changing the FileValue calculated for symlink (if we changed the contents of foo,
        // the FileValue created for symlink would notice, since it stats foo).
        val sym1: Path = fs.getPath("/sym1")
        val sym2: Path? = fs.getPath("/sym2")
        val symlink: Path = fs.getPath("/bar")
        FileSystemUtils.ensureSymbolicLink(symlink, sym1)
        FileSystemUtils.ensureSymbolicLink(sym1, path)
        FileSystemUtils.ensureSymbolicLink(sym2, path)
        val fooKey: SkyKey? =
            FileValue.key(RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/foo")))
        val symlinkRootedPath: RootedPath? =
            RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/bar"))
        val symlinkKey: SkyKey? = FileValue.key(symlinkRootedPath)
        val symlinkFileStateKey: SkyKey = FileStateValue.key(symlinkRootedPath)
        val sym1RootedPath: RootedPath? =
            RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/sym1"))
        val sym1FileStateKey: SkyKey = FileStateValue.key(sym1RootedPath)
        val allKeys: Iterable<SkyKey?> = com.google.common.collect.ImmutableList.of<SkyKey?>(symlinkKey, fooKey)

        // First build -- prime the graph.
        var result: EvaluationResult<FileValue?> = evaluator.evaluate(allKeys, EVALUATION_OPTIONS)
        assertThat(result.hasError()).isFalse()
        var symlinkValue: FileValue = result.get(symlinkKey)
        val fooValue: FileValue = result.get(fooKey)
        assertWithMessage(symlinkValue.toString()).that(symlinkValue.isSymlink()).isTrue()
        // Digest is not always available, so use size as a proxy for contents.
        assertThat(symlinkValue.getSize()).isEqualTo(fooValue.getSize())
        assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker))

        // Before second build, move sym1 to point to sym2.
        assertThat(sym1.delete()).isTrue()
        FileSystemUtils.ensureSymbolicLink(sym1, sym2)
        assertDiffWithNewValues(getDirtyFilesystemKeys(evaluator, checker), sym1FileStateKey)

        differencer.invalidate(com.google.common.collect.ImmutableList.of<E?>(sym1FileStateKey))
        result = evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(), EVALUATION_OPTIONS)
        assertThat(result.hasError()).isFalse()
        assertDiffWithNewValues(getDirtyFilesystemKeys(evaluator, checker), sym1FileStateKey)

        // Before third build, move sym1 back to original (so change pruning will prevent signaling of
        // its parents, but change symlink for real.
        assertThat(sym1.delete()).isTrue()
        FileSystemUtils.ensureSymbolicLink(sym1, path)
        assertThat(symlink.delete()).isTrue()
        FileSystemUtils.writeContentAsLatin1(symlink, "new symlink contents")
        assertDiffWithNewValues(getDirtyFilesystemKeys(evaluator, checker), symlinkFileStateKey)
        differencer.invalidate(com.google.common.collect.ImmutableList.of<E?>(symlinkFileStateKey))
        result = evaluator.evaluate(allKeys, EVALUATION_OPTIONS)
        assertThat(result.hasError()).isFalse()
        symlinkValue = result.get(symlinkKey)
        assertWithMessage(symlinkValue.toString()).that(symlinkValue.isSymlink()).isFalse()
        assertThat(result.get(fooKey)).isEqualTo(fooValue)
        assertThat(symlinkValue.getSize()).isNotEqualTo(fooValue.getSize())
        assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExplicitFiles() {
        val checker: FilesystemValueChecker =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )

        val path1: Path? = fs.getPath("/foo1")
        val path2: Path = fs.getPath("/foo2")
        FileSystemUtils.createEmptyFile(path1)
        FileSystemUtils.createEmptyFile(path2)
        assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker))

        val key1: SkyKey? =
            FileStateValue.key(
                RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/foo1"))
            )
        val key2: SkyKey? =
            FileStateValue.key(
                RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/foo2"))
            )
        val skyKeys: Iterable<SkyKey?> = com.google.common.collect.ImmutableList.of<SkyKey?>(key1, key2)
        var result: EvaluationResult<SkyValue?> = evaluator.evaluate(skyKeys, EVALUATION_OPTIONS)
        assertThat(result.hasError()).isFalse()

        assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker))

        // Wait for the timestamp granularity to elapse, so updating the files will observably advance
        // their ctime.
        TimestampGranularityUtils.waitForTimestampGranularity(
            java.lang.System.currentTimeMillis(), OutErr.SYSTEM_OUT_ERR
        )
        // Update path1's contents. This will update the file's ctime with current time indicated by the
        // clock.
        fs.advanceClockMillis(1)
        FileSystemUtils.writeContentAsLatin1(path1, "hello1")
        // Update path2's mtime but not its contents. We expect that an mtime change suffices to update
        // the ctime.
        path2.setLastModifiedTime(42)
        // Assert that both files changed. The change detection relies, among other things, on ctime
        // change.
        assertDiffWithNewValues(getDirtyFilesystemKeys(evaluator, checker), key1, key2)

        differencer.invalidate(skyKeys)
        result = evaluator.evaluate(skyKeys, EVALUATION_OPTIONS)
        assertThat(result.hasError()).isFalse()
        assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileWithIOExceptionNotConsideredDirty() {
        val path: Path = fs.getPath("/testroot/foo")
        path.getParentDirectory().createDirectory()
        path.createSymbolicLink(PathFragment.create("bar"))

        fs.readlinkThrowsIoException = true
        val fileKey: SkyKey =
            FileStateValue.key(
                RootedPath.toRootedPath(Root.fromPath(pkgRoot), PathFragment.create("foo"))
            )
        val result: EvaluationResult<SkyValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(fileKey), EVALUATION_OPTIONS)
        assertThat(result.hasError()).isTrue()

        fs.readlinkThrowsIoException = false
        val checker: FilesystemValueChecker =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
        val diff: Diff = getDirtyFilesystemKeys(evaluator, checker)
        assertThat(diff.changedKeysWithoutNewValues()).isEmpty()
        assertThat(diff.changedKeysWithNewValues()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesInCycleNotConsideredDirty() {
        val path1: Path? = pkgRoot.getRelative("foo1")
        val path2: Path? = pkgRoot.getRelative("foo2")
        val path3: Path? = pkgRoot.getRelative("foo3")
        FileSystemUtils.ensureSymbolicLink(path1, path2)
        FileSystemUtils.ensureSymbolicLink(path2, path3)
        FileSystemUtils.ensureSymbolicLink(path3, path1)
        val fileKey1: SkyKey = FileValue.key(RootedPath.toRootedPath(Root.fromPath(pkgRoot), path1))

        val result: EvaluationResult<SkyValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(fileKey1), EVALUATION_OPTIONS)
        assertThat(result.hasError()).isTrue()

        val checker: FilesystemValueChecker =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
        val diff: Diff = getDirtyFilesystemKeys(evaluator, checker)
        assertThat(diff.changedKeysWithoutNewValues()).isEmpty()
        assertThat(diff.changedKeysWithNewValues()).isEmpty()
    }

    @Throws(java.lang.Exception::class)
    fun checkDirtyActions(batchStatter: BatchStat?) {
        val out1: Artifact = createDerivedArtifact("fiz")
        val out2: Artifact = createDerivedArtifact("pop")

        FileSystemUtils.writeContentAsLatin1(out1.getPath(), "hello")
        FileSystemUtils.writeContentAsLatin1(out2.getPath(), "fizzlepop")

        val tsgm: TimestampGranularityMonitor =
            TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())
        val actionKey1: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)
        val actionKey2: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 1)

        pretendBuildTwoArtifacts(out1, actionKey1, out2, actionKey2, batchStatter, tsgm)

        // Change the file but not its size
        FileSystemUtils.writeContentAsLatin1(out1.getPath(), "hallo")
        checkActionDirtiedByFile(out1, actionKey1, batchStatter, tsgm)
        pretendBuildTwoArtifacts(out1, actionKey1, out2, actionKey2, batchStatter, tsgm)

        // Now try with a different size
        FileSystemUtils.writeContentAsLatin1(out1.getPath(), "hallo2")
        checkActionDirtiedByFile(out1, actionKey1, batchStatter, tsgm)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun pretendBuildTwoArtifacts(
        out1: Artifact,
        actionKey1: SkyKey,
        out2: Artifact,
        actionKey2: SkyKey,
        batchStatter: BatchStat?,
        tsgm: TimestampGranularityMonitor
    ) {
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(1)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()

        tsgm.setCommandStartTime()
        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                actionKey1,
                Delta.justNew(
                    actionValue(
                        TestAction(
                            com.google.common.util.concurrent.Runnables.doNothing(),
                            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                            com.google.common.collect.ImmutableSet.of<E?>(out1)
                        )
                    )
                ),
                actionKey2,
                Delta.justNew(
                    actionValue(
                        TestAction(
                            com.google.common.util.concurrent.Runnables.doNothing(),
                            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                            com.google.common.collect.ImmutableSet.of<E?>(out2)
                        )
                    )
                )
            )
        )
        assertThat(
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(), evaluationContext).hasError()
        ).isFalse()
        assertThat(
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatter,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    OutputChecker.TRUST_LOCAL_ONLY,
                    { ignored, ignored2 -> })
        )
            .isEmpty()

        tsgm.waitForTimestampGranularity(OutErr.SYSTEM_OUT_ERR)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun checkActionDirtiedByFile(
        file: Artifact, actionKey: SkyKey?, batchStatter: BatchStat?, tsgm: TimestampGranularityMonitor?
    ) {
        assertThat(
            FilesystemValueChecker(
                tsgm,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatter,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    OutputChecker.TRUST_LOCAL_ONLY,
                    { ignored, ignored2 -> })
        )
            .containsExactly(actionKey)
        assertThat(
            FilesystemValueChecker(
                tsgm,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatter,
                    ModifiedFileSet.EVERYTHING_DELETED,
                    OutputChecker.TRUST_LOCAL_ONLY,
                    { ignored, ignored2 -> })
        )
            .containsExactly(actionKey)
        assertThat(
            FilesystemValueChecker(
                tsgm,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatter,
                    Builder().modify(file.getExecPath()).build(),
                    OutputChecker.TRUST_LOCAL_ONLY,
                    { ignored, ignored2 -> })
        )
            .containsExactly(actionKey)
        assertThat(
            FilesystemValueChecker(
                tsgm,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatter,
                    Builder()
                        .modify(file.getExecPath().getParentDirectory())
                        .build(),
                    OutputChecker.TRUST_LOCAL_ONLY,
                    { ignored, ignored2 -> })
        )
            .isEmpty()
        assertThat(
            FilesystemValueChecker(
                tsgm,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStatter,
                    ModifiedFileSet.NOTHING_MODIFIED,
                    OutputChecker.TRUST_LOCAL_ONLY,
                    { ignored, ignored2 -> })
        )
            .isEmpty()
    }

    internal enum class ModifiedSetReporting {
        EVERYTHING_MODIFIED {
            override fun getModifiedFileSet(path: PathFragment?): ModifiedFileSet {
                return ModifiedFileSet.EVERYTHING_MODIFIED
            }
        },
        EVERYTHING_DELETED {
            override fun getModifiedFileSet(path: PathFragment?): ModifiedFileSet {
                return ModifiedFileSet.EVERYTHING_DELETED
            }
        },
        SINGLE_PATH {
            override fun getModifiedFileSet(path: PathFragment?): ModifiedFileSet {
                return ModifiedFileSet.builder().modify(path).build()
            }
        };

        abstract fun getModifiedFileSet(path: PathFragment?): ModifiedFileSet?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getDirtyActionValues_touchedTreeDirectory_returnsEmptyDiff(
        @TestParameter("", "subdir") touchedTreePath: String?,
        @TestParameter modifiedSet: ModifiedSetReporting
    ) {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val treeFile: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "subdir/file")
        FileSystemUtils.writeIsoLatin1(treeFile.getPath(), "text")
        val actionKey: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)
        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                actionKey,
                actionValueWithTreeArtifacts(com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(treeFile))
            )
        )
        evaluate()
        FileSystemUtils.touchFile(tree.getPath().getRelative(touchedTreePath))

        val dirtyActionKeys: MutableCollection<SkyKey?>? =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStat!!.getBatchStat(fs),
                    modifiedSet.getModifiedFileSet(tree.getExecPath()),
                    OutputChecker.TRUST_LOCAL_ONLY,
                    mockModifiedOutputsReceiver
                )

        Truth.assertThat(dirtyActionKeys).isEmpty()
        Truth.assertThat(modifiedOutputsCaptor.getAllValues()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getDirtyActionValues_deleteEmptyTreeDirectory_returnsTreeKey(
        @TestParameter modifiedSet: ModifiedSetReporting
    ) {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        tree.getPath().createDirectoryAndParents()
        val actionKey: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)
        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                actionKey,
                actionValueWithTreeArtifact(tree, TreeArtifactValue.empty())
            )
        )
        evaluate()
        assertThat(tree.getPath().delete()).isTrue()

        val dirtyActionKeys: MutableCollection<SkyKey?>? =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStat!!.getBatchStat(fs),
                    modifiedSet.getModifiedFileSet(tree.getExecPath()),
                    OutputChecker.TRUST_LOCAL_ONLY,
                    mockModifiedOutputsReceiver
                )

        Truth.assertThat(dirtyActionKeys).containsExactly(actionKey)
        Truth.assertThat(modifiedOutputsCaptor.getAllValues()).containsExactly(tree)
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val dirtyActionValues_treeDirectoryReplacedWithSymlink_returnsTreeKey: Unit
        get() {
            val tree: SpecialArtifact = createTreeArtifact("tree")
            tree.getPath().createDirectoryAndParents()
            val actionKey: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)
            differencer.inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    actionKey,
                    actionValueWithTreeArtifact(tree, TreeArtifactValue.empty())
                )
            )
            evaluate()
            val dummyEmptyDir: Path = fs.getPath("/bin").getRelative("dir")
            dummyEmptyDir.createDirectoryAndParents()
            assertThat(tree.getPath().delete()).isTrue()
            tree.getPath().createSymbolicLink(dummyEmptyDir)

            val dirtyActionKeys: MutableCollection<SkyKey?>? =
                FilesystemValueChecker( /* tsgm= */
                    null,
                    SyscallCache.NO_CACHE,
                    XattrProviderOverrider.NO_OVERRIDE,
                    FSVC_THREADS_FOR_TEST
                )
                    .getDirtyActionValues(
                        evaluator.getValues(),
                        batchStat!!.getBatchStat(fs),
                        ModifiedFileSet.EVERYTHING_MODIFIED,
                        OutputChecker.TRUST_LOCAL_ONLY,
                        mockModifiedOutputsReceiver
                    )

            Truth.assertThat(dirtyActionKeys).containsExactly(actionKey)
            Truth.assertThat(modifiedOutputsCaptor.getAllValues()).containsExactly(tree)
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getDirtyActionValues_modifiedTreeFile_returnsTreeKey(
        @TestParameter modifiedSet: ModifiedSetReporting
    ) {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val treeFile: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "file")
        FileSystemUtils.writeIsoLatin1(treeFile.getPath(), "text")
        val actionKey: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)
        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                actionKey,
                actionValueWithTreeArtifacts(com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(treeFile))
            )
        )
        evaluate()
        FileSystemUtils.writeIsoLatin1(treeFile.getPath(), "other text")

        val dirtyActionKeys: MutableCollection<SkyKey?>? =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStat!!.getBatchStat(fs),
                    modifiedSet.getModifiedFileSet(treeFile.getExecPath()),
                    OutputChecker.TRUST_LOCAL_ONLY,
                    mockModifiedOutputsReceiver
                )

        Truth.assertThat(dirtyActionKeys).containsExactly(actionKey)
        Truth.assertThat(modifiedOutputsCaptor.getAllValues()).containsExactly(treeFile)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getDirtyActionValues_addedTreeFile_returnsTreeKey(
        @TestParameter modifiedSet: ModifiedSetReporting
    ) {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val treeFile: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "file1")
        FileSystemUtils.writeIsoLatin1(treeFile.getPath())
        val actionKey: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)
        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                actionKey,
                actionValueWithTreeArtifacts(com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(treeFile))
            )
        )
        evaluate()

        val newFile: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "file2")
        FileSystemUtils.writeIsoLatin1(newFile.getPath())
        val dirtyActionValues: MutableCollection<SkyKey?>? =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStat!!.getBatchStat(fs),
                    modifiedSet.getModifiedFileSet(newFile.getExecPath()),
                    OutputChecker.TRUST_LOCAL_ONLY,
                    mockModifiedOutputsReceiver
                )

        Truth.assertThat(dirtyActionValues).containsExactly(actionKey)
        Truth.assertThat(modifiedOutputsCaptor.getAllValues()).containsExactly(tree)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getDirtyActionValues_addedTreeFileToEmptyTree_returnsTreeKey(
        @TestParameter modifiedSet: ModifiedSetReporting
    ) {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        tree.getPath().createDirectoryAndParents()
        val actionKey: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)
        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                actionKey,
                actionValueWithTreeArtifact(tree, TreeArtifactValue.empty())
            )
        )
        evaluate()
        val newFile: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "file")
        FileSystemUtils.writeIsoLatin1(newFile.getPath())

        val dirtyActionKeys: MutableCollection<SkyKey?>? =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStat!!.getBatchStat(fs),
                    modifiedSet.getModifiedFileSet(newFile.getExecPath()),
                    OutputChecker.TRUST_LOCAL_ONLY,
                    mockModifiedOutputsReceiver
                )

        Truth.assertThat(dirtyActionKeys).containsExactly(actionKey)
        Truth.assertThat(modifiedOutputsCaptor.getAllValues()).containsExactly(tree)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getDirtyActionValues_deletedTreeFile_returnsTreeKey(
        @TestParameter modifiedSet: ModifiedSetReporting
    ) {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val treeFile: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "file")
        FileSystemUtils.writeIsoLatin1(treeFile.getPath())
        val actionKey: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)
        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                actionKey,
                actionValueWithTreeArtifacts(com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(treeFile))
            )
        )
        evaluate()
        assertThat(treeFile.getPath().delete()).isTrue()

        val dirtyActionKeys: MutableCollection<SkyKey?>? =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStat!!.getBatchStat(fs),
                    modifiedSet.getModifiedFileSet(treeFile.getExecPath()),
                    OutputChecker.TRUST_LOCAL_ONLY,
                    mockModifiedOutputsReceiver
                )

        Truth.assertThat(dirtyActionKeys).containsExactly(actionKey)
        Truth.assertThat(modifiedOutputsCaptor.getAllValues()).containsExactly(treeFile, tree)
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val dirtyActionValues_everythingModified_returnsAllKeys: Unit
        get() {
            val tree1: SpecialArtifact = createTreeArtifact("tree1")
            val tree1File: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree1, "file")
            FileSystemUtils.writeIsoLatin1(tree1File.getPath(), "text")
            val tree2: SpecialArtifact = createTreeArtifact("tree2")
            val tree2File: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree2, "file")
            FileSystemUtils.writeIsoLatin1(tree2File.getPath())
            val actionKey1: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)
            val actionKey2: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 1)
            differencer.inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    actionKey1,
                    actionValueWithTreeArtifacts(
                        com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(
                            tree1File
                        )
                    ),
                    actionKey2,
                    actionValueWithTreeArtifacts(
                        com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(
                            tree2File
                        )
                    )
                )
            )
            evaluate()
            FileSystemUtils.writeIsoLatin1(tree1File.getPath(), "new text")
            assertThat(tree2File.getPath().delete()).isTrue()

            val dirtyActionKeys: MutableCollection<SkyKey?>? =
                FilesystemValueChecker( /* tsgm= */
                    null,
                    SyscallCache.NO_CACHE,
                    XattrProviderOverrider.NO_OVERRIDE,
                    FSVC_THREADS_FOR_TEST
                )
                    .getDirtyActionValues(
                        evaluator.getValues(),
                        batchStat!!.getBatchStat(fs),
                        ModifiedFileSet.EVERYTHING_MODIFIED,
                        OutputChecker.TRUST_LOCAL_ONLY,
                        mockModifiedOutputsReceiver
                    )

            Truth.assertThat(dirtyActionKeys).containsExactly(actionKey1, actionKey2)
            Truth.assertThat(modifiedOutputsCaptor.getAllValues()).containsExactly(tree1File, tree2, tree2File)
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getDirtyActionValues_changedFileNotInModifiedSet_returnsKeysFromSetOnly(
        @TestParameter reportFirst: Boolean
    ) {
        val tree1: SpecialArtifact = createTreeArtifact("tree1")
        val tree1File: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree1, "file")
        FileSystemUtils.writeIsoLatin1(tree1File.getPath(), "text")
        val tree2: SpecialArtifact = createTreeArtifact("tree2")
        val tree2File: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree2, "file")
        FileSystemUtils.writeIsoLatin1(tree2File.getPath())
        val actionKey1: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)
        val actionKey2: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 1)
        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                actionKey1,
                actionValueWithTreeArtifacts(com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(tree1File)),
                actionKey2,
                actionValueWithTreeArtifacts(com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(tree2File))
            )
        )
        evaluate()
        FileSystemUtils.writeIsoLatin1(tree1File.getPath(), "new text")
        assertThat(tree2File.getPath().delete()).isTrue()

        val dirtyActionKeys: MutableCollection<SkyKey?>? =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),
                    batchStat!!.getBatchStat(fs),
                    ModifiedFileSet.builder()
                        .modify((if (reportFirst) tree1File else tree2File).getExecPath())
                        .build(),
                    OutputChecker.TRUST_LOCAL_ONLY,
                    mockModifiedOutputsReceiver
                )

        Truth.assertThat(dirtyActionKeys).containsExactly(if (reportFirst) actionKey1 else actionKey2)
        Truth.assertThat(modifiedOutputsCaptor.getAllValues())
            .containsExactlyElementsIn(
                if (reportFirst) com.google.common.collect.ImmutableList.of<Any?>(tree1File) else com.google.common.collect.ImmutableList.of<Any?>(
                    tree2File,
                    tree2
                )
            )
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val dirtyActionValues_middleFileSkippedInModifiedFileSet_returnsKeysFromSetOnly: Unit
        get() {
            val treeA: SpecialArtifact = createTreeArtifact("a_tree")
            val treeAFile: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeA, "file")
            FileSystemUtils.writeIsoLatin1(treeAFile.getPath())
            val treeB: SpecialArtifact = createTreeArtifact("b_tree")
            val treeBFile: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeB, "file")
            FileSystemUtils.writeIsoLatin1(treeBFile.getPath())
            val treeC: SpecialArtifact = createTreeArtifact("c_tree")
            val treeCFile: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeC, "file")
            FileSystemUtils.writeIsoLatin1(treeCFile.getPath())
            val actionKey1: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)
            val actionKey2: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 1)
            val actionKey3: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 2)
            differencer.inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    actionKey1,
                    actionValueWithTreeArtifacts(
                        com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(
                            treeAFile
                        )
                    ),
                    actionKey2,
                    actionValueWithTreeArtifacts(
                        com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(
                            treeBFile
                        )
                    ),
                    actionKey3,
                    actionValueWithTreeArtifacts(
                        com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(
                            treeCFile
                        )
                    )
                )
            )
            evaluate()
            assertThat(treeAFile.getPath().delete()).isTrue()
            assertThat(treeBFile.getPath().delete()).isTrue()
            assertThat(treeCFile.getPath().delete()).isTrue()

            val dirtyActionKeys: MutableCollection<SkyKey?>? =
                FilesystemValueChecker( /* tsgm= */
                    null,
                    SyscallCache.NO_CACHE,
                    XattrProviderOverrider.NO_OVERRIDE,
                    FSVC_THREADS_FOR_TEST
                )
                    .getDirtyActionValues(
                        evaluator.getValues(),
                        batchStat!!.getBatchStat(fs),
                        ModifiedFileSet.builder()
                            .modify(treeAFile.getExecPath())
                            .modify(treeCFile.getExecPath())
                            .build(),
                        OutputChecker.TRUST_LOCAL_ONLY,
                        mockModifiedOutputsReceiver
                    )

            Truth.assertThat(dirtyActionKeys).containsExactly(actionKey1, actionKey3)
            Truth.assertThat(modifiedOutputsCaptor.getAllValues())
                .containsExactly(treeAFile, treeA, treeCFile, treeC)
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val dirtyActionValues_nothingModified_returnsEmptyDiff: Unit
        get() {
            val tree: SpecialArtifact = createTreeArtifact("tree")
            val treeFile: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "file")
            FileSystemUtils.writeIsoLatin1(treeFile.getPath())
            val actionKey: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)
            differencer.inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    actionKey,
                    actionValueWithTreeArtifacts(
                        com.google.common.collect.ImmutableList.of<TreeFileArtifact?>(treeFile)
                    )
                )
            )
            evaluate()
            assertThat(treeFile.getPath().delete()).isTrue()

            val dirtyActionKeys: MutableCollection<SkyKey?>? =
                FilesystemValueChecker( /* tsgm= */
                    null,
                    SyscallCache.NO_CACHE,
                    XattrProviderOverrider.NO_OVERRIDE,
                    FSVC_THREADS_FOR_TEST
                )
                    .getDirtyActionValues(
                        evaluator.getValues(),
                        batchStat!!.getBatchStat(fs),
                        ModifiedFileSet.NOTHING_MODIFIED,
                        OutputChecker.TRUST_LOCAL_ONLY,
                        mockModifiedOutputsReceiver
                    )

            Truth.assertThat(dirtyActionKeys).isEmpty()
            Truth.assertThat(modifiedOutputsCaptor.getAllValues()).isEmpty()
        }

    @Throws(java.lang.InterruptedException::class)
    private fun evaluate() {
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(1)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        assertThat(
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(), evaluationContext).hasError()
        ).isFalse()
    }

    @Throws(IOException::class)
    private fun createDerivedArtifact(relPath: String?): Artifact {
        val outSegment = "bin"
        val outputPath: Path = fs.getPath("/" + outSegment)
        outputPath.createDirectory()
        return ActionsTestUtil.createArtifact(
            ArtifactRoot.asDerivedRoot(fs.getPath("/"), RootType.OUTPUT, outSegment),
            outputPath.getRelative(relPath)
        )
    }

    @org.junit.Test // TODO(b/154337187): Remove the following annotation to re-enable once this test is de-flaked.
    @Ignore("b/154337187")
    @Throws(java.lang.Exception::class)
    fun testDirtyActions() {
        checkDirtyActions(null)
    }

    @org.junit.Test // TODO(b/154337187): Remove the following annotation to re-enable once this test is de-flaked.
    @Ignore("b/154337187")
    @Throws(java.lang.Exception::class)
    fun testDirtyActionsBatchStat() {
        checkDirtyActions(
            object : BatchStat() {
                @Throws(IOException::class)
                public override fun batchStat(paths: Iterable<PathFragment?>): MutableList<FileStatusWithDigest?> {
                    val stats: MutableList<FileStatusWithDigest?> = java.util.ArrayList<FileStatusWithDigest?>()
                    for (pathFrag in paths) {
                        stats.add(
                            FileStatusWithDigestAdapter.maybeAdapt(
                                fs.getPath("/").getRelative(pathFrag).statIfFound(Symlinks.NOFOLLOW)
                            )
                        )
                    }
                    return stats
                }
            })
    }

    @org.junit.Test // TODO(b/154337187): Remove the following annotation to re-enable once this test is de-flaked.
    @Ignore("b/154337187")
    @Throws(java.lang.Exception::class)
    fun testDirtyActionsBatchStatWithDigest() {
        checkDirtyActions(
            object : BatchStat() {
                @Throws(IOException::class)
                public override fun batchStat(paths: Iterable<PathFragment?>): MutableList<FileStatusWithDigest?> {
                    val stats: MutableList<FileStatusWithDigest?> = java.util.ArrayList<FileStatusWithDigest?>()
                    for (pathFrag in paths) {
                        val path: Path = fs.getPath("/").getRelative(pathFrag)
                        stats.add(statWithDigest(path, path.statIfFound(Symlinks.NOFOLLOW)))
                    }
                    return stats
                }
            })
    }

    @org.junit.Test // TODO(b/154337187): Remove the following annotation to re-enable once this test is de-flaked.
    @Ignore("b/154337187")
    @Throws(java.lang.Exception::class)
    fun testDirtyActionsBatchStatFallback() {
        checkDirtyActions(
            object : BatchStat() {
                @Throws(IOException::class)
                public override fun batchStat(paths: Iterable<PathFragment?>?): MutableList<FileStatusWithDigest?>? {
                    throw IOException("try again")
                }
            })
    }

    private fun createRemoteMetadata(contents: String): FileArtifactValue {
        return createRemoteMetadata(contents,  /* expirationTime= */null)
    }

    private fun createRemoteMetadata(contents: String, expirationTime: Instant?): FileArtifactValue {
        val data: ByteArray = contents.toByteArray()
        val hashFn: DigestHashFunction = fs.getDigestFunction()
        val hash: com.google.common.hash.HashCode = hashFn.getHashFunction().hashBytes(data)
        return FileArtifactValue.createForRemoteFileWithMaterializationData(
            hash.asBytes(), data.size, -1, expirationTime
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteAndLocalArtifacts(@TestParameter setContentsProxy: Boolean) {
        // Test that injected remote artifacts are trusted by the FileSystemValueChecker if it is
        // configured to trust remote artifacts, and that local files always take precedence over remote
        // files if they are different.
        val actionKey1: SkyKey? = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)
        val actionKey2: SkyKey? = ActionLookupData.create(ACTION_LOOKUP_KEY, 1)

        val out1: Artifact = createDerivedArtifact("foo")
        val out2: Artifact = createDerivedArtifact("bar")
        val metadataToInject: MutableMap<SkyKey?, Delta?> = HashMap<SkyKey?, Delta?>()
        val out1Metadata: FileArtifactValue = createRemoteMetadata("foo-content")
        metadataToInject.put(actionKey1, actionValueWithMetadata(out1, out1Metadata))
        metadataToInject.put(
            actionKey2, actionValueWithMetadata(out2, createRemoteMetadata("bar-content"))
        )
        differencer.inject(metadataToInject)

        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(1)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        assertThat(
            evaluator
                .evaluate(com.google.common.collect.ImmutableList.of<E?>(actionKey1, actionKey2), evaluationContext)
                .hasError()
        )
            .isFalse()
        assertThat(
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),  /* batchStatter= */
                    null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    OutputChecker.TRUST_ALL,
                    { ignored, ignored2 -> })
        )
            .isEmpty()

        if (setContentsProxy) {
            FileSystemUtils.writeContentAsLatin1(out1.getPath(), "foo-content")
            out1Metadata.setContentsProxy(FileContentsProxy.create(out1.getPath().stat()))
        }

        // Create the "out1" artifact on the filesystem and test that it invalidates the generating
        // action's SkyKey.
        FileSystemUtils.writeContentAsLatin1(out1.getPath(), "new-foo-content")
        assertThat(
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),  /* batchStatter= */
                    null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    OutputChecker.TRUST_ALL,
                    { ignored, ignored2 -> })
        )
            .containsExactly(actionKey1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteAndLocalArtifacts_identicalContent(@TestParameter setContentsProxy: Boolean) {
        // Test that if injected remote artifacts and local files are identical, the generating actions
        // are not marked as dirty if it has contents proxy.
        val actionKey1: SkyKey? = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)
        val actionKey2: SkyKey? = ActionLookupData.create(ACTION_LOOKUP_KEY, 1)

        val out1: Artifact = createDerivedArtifact("foo")
        val out2: Artifact = createDerivedArtifact("bar")
        val metadataToInject: MutableMap<SkyKey?, Delta?> = HashMap<SkyKey?, Delta?>()
        val out1Metadata: FileArtifactValue = createRemoteMetadata("foo-content")
        metadataToInject.put(actionKey1, actionValueWithMetadata(out1, out1Metadata))
        metadataToInject.put(
            actionKey2, actionValueWithMetadata(out2, createRemoteMetadata("bar-content"))
        )
        differencer.inject(metadataToInject)

        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(1)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        assertThat(
            evaluator
                .evaluate(com.google.common.collect.ImmutableList.of<E?>(actionKey1, actionKey2), evaluationContext)
                .hasError()
        )
            .isFalse()
        assertThat(
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),  /* batchStatter= */
                    null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    OutputChecker.TRUST_ALL,
                    { ignored, ignored2 -> })
        )
            .isEmpty()

        // Create identical "out1" artifact on the filesystem and test that it doesn't invalidate the
        // generating action's SkyKey if contents proxy is set.
        FileSystemUtils.writeContentAsLatin1(out1.getPath(), "foo-content")
        if (setContentsProxy) {
            out1Metadata.setContentsProxy(FileContentsProxy.create(out1.getPath().stat()))
        }
        val dirtyActionKeys: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),  /* batchStatter= */
                    null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    OutputChecker.TRUST_ALL,
                    { ignored, ignored2 -> })
        if (setContentsProxy) {
            assertThat(dirtyActionKeys).isEmpty()
        } else {
            assertThat(dirtyActionKeys).containsExactly(actionKey1)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteArtifactsExpired() {
        // Test that if injected remote artifacts expired, they are considered as dirty.
        val actionKey1: SkyKey? = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)
        val actionKey2: SkyKey? = ActionLookupData.create(ACTION_LOOKUP_KEY, 1)

        val out1: Artifact = createDerivedArtifact("foo")
        val out2: Artifact = createDerivedArtifact("bar")
        val metadataToInject: MutableMap<SkyKey?, Delta?> = HashMap<SkyKey?, Delta?>()
        metadataToInject.put(
            actionKey1, actionValueWithMetadata(out1, createRemoteMetadata("foo-content"))
        )
        metadataToInject.put(
            actionKey2,
            actionValueWithMetadata(
                out2, createRemoteMetadata("bar-content", Instant.ofEpochMilli(1))
            )
        )
        differencer.inject(metadataToInject)

        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(1)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        assertThat(
            evaluator
                .evaluate(com.google.common.collect.ImmutableList.of<E?>(actionKey1, actionKey2), evaluationContext)
                .hasError()
        )
            .isFalse()
        assertThat(
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),  /* batchStatter= */
                    null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    CHECK_TTL,
                    { ignored, ignored2 -> })
        )
            .containsExactly(actionKey2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteAndLocalTreeArtifacts() {
        // Test that change to local tree files invalidates generating action.
        val actionKey: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)

        val treeArtifact: SpecialArtifact = createTreeArtifact("dir")
        treeArtifact.getPath().createDirectoryAndParents()
        val tree: TreeArtifactValue =
            TreeArtifactValue.newBuilder(treeArtifact)
                .putChild(
                    TreeFileArtifact.createTreeOutput(treeArtifact, "foo"),
                    createRemoteMetadata("foo-content")
                )
                .putChild(
                    TreeFileArtifact.createTreeOutput(treeArtifact, "bar"),
                    createRemoteMetadata("bar-content")
                )
                .build()

        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                actionKey,
                actionValueWithTreeArtifact(treeArtifact, tree)
            )
        )

        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(1)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        assertThat(
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(actionKey), evaluationContext).hasError()
        )
            .isFalse()
        assertThat(
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),  /* batchStatter= */
                    null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    OutputChecker.TRUST_ALL,
                    { ignored, ignored2 -> })
        )
            .isEmpty()

        // Create dir/foo on the local disk and test that it invalidates the associated sky key.
        val fooArtifact: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "foo")
        FileSystemUtils.writeContentAsLatin1(fooArtifact.getPath(), "new-foo-content")
        assertThat(
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),  /* batchStatter= */
                    null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    OutputChecker.TRUST_LOCAL_ONLY,
                    { ignored, ignored2 -> })
        )
            .containsExactly(actionKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteAndLocalTreeArtifacts_partiallyDownloaded(
        @TestParameter setContentsProxy: Boolean
    ) {
        // Test that if injected remote tree artifacts and local files are identical, but the tree is
        // partially downloaded, the generating action is not marked as dirty.
        val actionKey: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)

        val treeArtifact: SpecialArtifact = createTreeArtifact("dir")
        treeArtifact.getPath().createDirectoryAndParents()
        val fooMetadata: FileArtifactValue = createRemoteMetadata("foo-content")
        val tree: TreeArtifactValue =
            TreeArtifactValue.newBuilder(treeArtifact)
                .putChild(TreeFileArtifact.createTreeOutput(treeArtifact, "foo"), fooMetadata)
                .putChild(
                    TreeFileArtifact.createTreeOutput(treeArtifact, "bar"),
                    createRemoteMetadata("bar-content")
                )
                .build()

        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                actionKey,
                actionValueWithTreeArtifact(treeArtifact, tree)
            )
        )

        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(1)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        assertThat(
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(actionKey), evaluationContext).hasError()
        )
            .isFalse()
        assertThat(
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),  /* batchStatter= */
                    null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    OutputChecker.TRUST_ALL,
                    { ignored, ignored2 -> })
        )
            .isEmpty()

        // Create identical dir/foo on the local disk and test that it doesn't invalidate the associated
        // sky key.
        val fooArtifact: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "foo")
        FileSystemUtils.writeContentAsLatin1(fooArtifact.getPath(), "foo-content")
        if (setContentsProxy) {
            fooMetadata.setContentsProxy(FileContentsProxy.create(fooArtifact.getPath().stat()))
        }
        val dirtyActionKeys: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),  /* batchStatter= */
                    null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    OutputChecker.TRUST_ALL,
                    { ignored, ignored2 -> })
        if (setContentsProxy) {
            assertThat(dirtyActionKeys).isEmpty()
        } else {
            assertThat(dirtyActionKeys).containsExactly(actionKey)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteAndLocalTreeArtifacts_identicalContent(
        @TestParameter setContentsProxy: Boolean
    ) {
        // Test that if injected remote tree artifacts and local files are identical, the generating
        // actions are not marked as dirty if contents proxy is set.
        val actionKey: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)

        val treeArtifact: SpecialArtifact = createTreeArtifact("dir")
        treeArtifact.getPath().createDirectoryAndParents()
        val fooMetadata: FileArtifactValue = createRemoteMetadata("foo-content")
        val barMetadata: FileArtifactValue = createRemoteMetadata("bar-content")
        val tree: TreeArtifactValue =
            TreeArtifactValue.newBuilder(treeArtifact)
                .putChild(TreeFileArtifact.createTreeOutput(treeArtifact, "foo"), fooMetadata)
                .putChild(TreeFileArtifact.createTreeOutput(treeArtifact, "bar"), barMetadata)
                .build()

        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                actionKey,
                actionValueWithTreeArtifact(treeArtifact, tree)
            )
        )

        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(1)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        assertThat(
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(actionKey), evaluationContext).hasError()
        )
            .isFalse()
        assertThat(
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),  /* batchStatter= */
                    null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    OutputChecker.TRUST_ALL,
                    { ignored, ignored2 -> })
        )
            .isEmpty()

        // Create identical dir/foo and dir/bar on the local disk and test that it doesn't invalidate
        // the associated sky key.
        val fooArtifact: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "foo")
        FileSystemUtils.writeContentAsLatin1(fooArtifact.getPath(), "foo-content")
        val barArtifact: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "bar")
        FileSystemUtils.writeContentAsLatin1(barArtifact.getPath(), "bar-content")
        if (setContentsProxy) {
            fooMetadata.setContentsProxy(FileContentsProxy.create(fooArtifact.getPath().stat()))
            barMetadata.setContentsProxy(FileContentsProxy.create(barArtifact.getPath().stat()))
        }
        val dirtyActionKeys: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),  /* batchStatter= */
                    null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    OutputChecker.TRUST_ALL,
                    { ignored, ignored2 -> })
        if (setContentsProxy) {
            assertThat(dirtyActionKeys).isEmpty()
        } else {
            assertThat(dirtyActionKeys).containsExactly(actionKey)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteTreeArtifactsExpired() {
        // Test that if injected remote tree artifacts are expired, they are considered as dirty.
        val actionKey: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)

        val treeArtifact: SpecialArtifact = createTreeArtifact("dir")
        treeArtifact.getPath().createDirectoryAndParents()
        val tree: TreeArtifactValue =
            TreeArtifactValue.newBuilder(treeArtifact)
                .putChild(
                    TreeFileArtifact.createTreeOutput(treeArtifact, "foo"),
                    createRemoteMetadata("foo-content")
                )
                .putChild(
                    TreeFileArtifact.createTreeOutput(treeArtifact, "bar"),
                    createRemoteMetadata("bar-content", Instant.ofEpochMilli(1))
                )
                .build()

        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                actionKey,
                actionValueWithTreeArtifact(treeArtifact, tree)
            )
        )

        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(1)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        assertThat(
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(actionKey), evaluationContext).hasError()
        )
            .isFalse()
        assertThat(
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),  /* batchStatter= */
                    null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    CHECK_TTL,
                    { ignored, ignored2 -> })
        )
            .containsExactly(actionKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteTreeArtifacts_archivedRepresentationExpired() {
        // Test that if archived representation of injected remote tree artifacts are expired, they are
        // considered as dirty.
        val actionKey: SkyKey = ActionLookupData.create(ACTION_LOOKUP_KEY, 0)

        val treeArtifact: SpecialArtifact = createTreeArtifact("dir")
        treeArtifact.getPath().createDirectoryAndParents()
        val tree: TreeArtifactValue =
            TreeArtifactValue.newBuilder(treeArtifact)
                .putChild(
                    TreeFileArtifact.createTreeOutput(treeArtifact, "foo"),
                    createRemoteMetadata("foo-content")
                )
                .putChild(
                    TreeFileArtifact.createTreeOutput(treeArtifact, "bar"),
                    createRemoteMetadata("bar-content")
                )
                .setArchivedRepresentation(
                    createArchivedTreeArtifactWithContent(treeArtifact),
                    createRemoteMetadata("archived", Instant.ofEpochMilli(1))
                )
                .build()

        differencer.inject(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                actionKey,
                actionValueWithTreeArtifact(treeArtifact, tree)
            )
        )

        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(1)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        assertThat(
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(actionKey), evaluationContext).hasError()
        )
            .isFalse()
        assertThat(
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )
                .getDirtyActionValues(
                    evaluator.getValues(),  /* batchStatter= */
                    null,
                    ModifiedFileSet.EVERYTHING_MODIFIED,
                    CHECK_TTL,
                    { ignored, ignored2 -> })
        )
            .containsExactly(actionKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPropagatesRuntimeExceptions() {
        val values: MutableCollection<SkyKey?> =
            com.google.common.collect.ImmutableList.of<E?>(
                FileValue.key(
                    RootedPath.toRootedPath(Root.fromPath(pkgRoot), PathFragment.create("foo"))
                )
            )
        evaluator.evaluate(values, EVALUATION_OPTIONS)
        val uncaughtRef: AtomicReference<Throwable?> = AtomicReference<Throwable?>()
        val throwableCaught: CountDownLatch = CountDownLatch(1)
        val uncaughtExceptionHandler: java.lang.Thread.UncaughtExceptionHandler =
            java.lang.Thread.UncaughtExceptionHandler { t: java.lang.Thread?, e: Throwable? ->
                uncaughtRef.compareAndSet(null, e)
                throwableCaught.countDown()
            }
        java.lang.Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler)
        val checker: FilesystemValueChecker =
            FilesystemValueChecker( /* tsgm= */
                null,
                SyscallCache.NO_CACHE,
                XattrProviderOverrider.NO_OVERRIDE,
                FSVC_THREADS_FOR_TEST
            )

        assertEmptyDiff(getDirtyFilesystemKeys(evaluator, checker))

        fs.statThrowsRuntimeException = true
        getDirtyFilesystemKeys(evaluator, checker)
        // Wait for exception handler to trigger (FVC doesn't clean up crashing threads on its own).
        Truth.assertThat(
            throwableCaught.await(
                com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
        ).isTrue()
        val thrown: Throwable? = uncaughtRef.get()
        Truth.assertThat(thrown).isNotNull()
        Truth.assertThat(thrown).hasMessageThat().isEqualTo("bork")
        Truth.assertThat(thrown).isInstanceOf(java.lang.RuntimeException::class.java)
    }

    companion object {
        private val CHECK_TTL: OutputChecker = OutputChecker { file, metadata ->
            metadata.getExpirationTime() == null
                    || metadata.getExpirationTime().isAfter(Instant.now())
        }
        private const val FSVC_THREADS_FOR_TEST = 200
        private val ACTION_LOOKUP_KEY: ActionLookupKey = object : ActionLookupKey() {
            public override fun functionName(): SkyFunctionName {
                return SkyFunctionName.FOR_TESTING
            }

            val label: Label?
                get() = null

            val configurationKey: BuildConfigurationKey?
                get() = null
        }
        private val ACTION_LOOKUP_DATA: ActionLookupData = actionLookupData(0)
        private val EVALUATION_OPTIONS: EvaluationContext? = EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
            .setEventHandler(NullEventHandler.INSTANCE)
            .build()

        @Throws(IOException::class)
        private fun actionValueWithTreeArtifacts(contents: MutableList<TreeFileArtifact>): Delta {
            return Delta.justNew(
                actionValueWithTreeArtifacts(
                    contents,
                    com.google.common.collect.ImmutableList.of<ArchivedTreeArtifact?>()
                )
            )
        }

        @Throws(IOException::class)
        private fun actionValueWithTreeArtifacts(
            contents: Iterable<TreeFileArtifact>, archivedTreeArtifacts: Iterable<ArchivedTreeArtifact>
        ): ActionExecutionValue {
            val treeArtifacts: TreeArtifactValue.MultiBuilder = TreeArtifactValue.newMultiBuilder()

            for (output in contents) {
                treeArtifacts.putChild(output, createMetadataFromFileSystem(output))
            }

            for (archivedTreeArtifact in archivedTreeArtifacts) {
                treeArtifacts.setArchivedRepresentation(
                    archivedTreeArtifact, createMetadataFromFileSystem(archivedTreeArtifact)
                )
            }

            val treeArtifactData: MutableMap<Artifact?, TreeArtifactValue?> = HashMap<Artifact?, TreeArtifactValue?>()
            treeArtifacts.forEach({ key: K?, value: V? -> treeArtifactData.put(key, value) })

            return ActionsTestUtil.createActionExecutionValue( /* artifactData= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.< K,
                V > copyOf<K?, V?>(treeArtifactData)
            )
        }

        @Throws(IOException::class)
        private fun createMetadataFromFileSystem(artifact: Artifact): FileArtifactValue {
            val path: Path = artifact.getPath()
            val noDigest: FileArtifactValue? =
                ActionOutputMetadataStore.fileArtifactValueFromArtifact(
                    artifact,
                    FileStatusWithDigestAdapter.maybeAdapt(path.statIfFound(Symlinks.NOFOLLOW)),
                    SyscallCache.NO_CACHE,
                    null
                )
            return FileArtifactValue.createFromInjectedDigest(noDigest, path.getDigest())
        }

        fun batchStatModes(): com.google.common.collect.ImmutableList<Array<Any?>?> {
            return java.util.Arrays.stream<BatchStatMode?>(BatchStatMode.entries.toTypedArray())
                .map<Array<BatchStatMode?>?> { mode: BatchStatMode? -> arrayOf<BatchStatMode?>(mode) }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Array<Any?>?>())
        }

        private fun actionLookupData(actionIndex: Int): ActionLookupData {
            return ActionLookupData.create(ACTION_LOOKUP_KEY, actionIndex)
        }

        // TODO(bazel-team): Add some tests for FileSystemValueChecker#changedKeys*() methods.
        // Presently these appear to be untested.
        private fun actionValue(action: Action): ActionExecutionValue {
            val artifactData: MutableMap<Artifact?, FileArtifactValue?> = HashMap<Artifact?, FileArtifactValue?>()
            for (output in action.getOutputs()) {
                try {
                    val path: Path = output.getPath()
                    val noDigest: FileArtifactValue? =
                        ActionOutputMetadataStore.fileArtifactValueFromArtifact(
                            output,
                            FileStatusWithDigestAdapter.maybeAdapt(path.statIfFound(Symlinks.NOFOLLOW)),
                            SyscallCache.NO_CACHE,
                            null
                        )
                    val withDigest: FileArtifactValue? =
                        FileArtifactValue.createFromInjectedDigest(noDigest, path.getDigest())
                    artifactData.put(output, withDigest)
                } catch (e: IOException) {
                    throw java.lang.IllegalStateException(e)
                }
            }
            return ActionsTestUtil.createActionExecutionValue(
                com.google.common.collect.ImmutableMap.< K,
                V > copyOf<K?, V?>(artifactData)
            )
        }

        private fun actionValueWithTreeArtifact(output: SpecialArtifact, tree: TreeArtifactValue): Delta {
            return Delta.justNew(
                ActionsTestUtil.createActionExecutionValue( /* artifactData= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    com.google.common.collect.ImmutableMap.of<K?, V?>(output, tree)
                )
            )
        }

        private fun actionValueWithMetadata(output: Artifact, value: FileArtifactValue): Delta {
            return Delta.justNew(
                ActionsTestUtil.createActionExecutionValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        output,
                        value
                    )
                )
            )
        }

        private fun assertEmptyDiff(diff: Diff) {
            assertDiffWithNewValues(diff)
        }

        private fun assertDiffWithNewValues(diff: Diff, vararg keysWithNewValues: SkyKey?) {
            assertThat(diff.changedKeysWithoutNewValues()).isEmpty()
            assertThat(diff.changedKeysWithNewValues().keySet())
                .containsExactlyElementsIn(java.util.Arrays.< T > asList < T ? > (keysWithNewValues))
        }

        private fun statWithDigest(path: Path, stat: FileStatus): FileStatusWithDigest {
            return object : FileStatusWithDigest() {
                @get:Throws(IOException::class)
                val digest: ByteArray?
                    get() = path.getDigest()

                val isFile: Boolean
                    get() = stat.isFile

                val isSpecialFile: Boolean
                    get() = stat.isSpecialFile

                val isDirectory: Boolean
                    get() = stat.isDirectory

                val isSymbolicLink: Boolean
                    get() = stat.isSymbolicLink

                @get:Throws(IOException::class)
                val size: Long
                    get() = stat.size

                @get:Throws(IOException::class)
                val lastModifiedTime: Long
                    get() = stat.lastModifiedTime

                @get:Throws(IOException::class)
                val lastChangeTime: Long
                    get() = stat.lastChangeTime

                @get:Throws(IOException::class)
                val nodeId: Long
                    get() = stat.nodeId
            }
        }

        @Throws(java.lang.InterruptedException::class)
        private fun getDirtyFilesystemKeys(
            evaluator: MemoizingEvaluator, checker: FilesystemValueChecker
        ): Diff {
            return checker.getDirtyKeys(
                evaluator.getValues(), DirtinessCheckerUtils.createBasicFilesystemDirtinessChecker()
            )
        }
    }
}
