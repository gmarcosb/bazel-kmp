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

import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.ResolvedFileFactory.danglingSymlink

/** Tests for [RecursiveFilesystemTraversalFunction].  */
@RunWith(JUnit4::class)
class RecursiveFilesystemTraversalFunctionTest : FoundationTestCase() {
    private var progressReceiver: RecordingEvaluationProgressReceiver? = null
    private var evaluator: MemoizingEvaluator? = null
    private var differencer: RecordingDifferencer? = null
    private var pkgLocator: AtomicReference<PathPackageLocator?>? = null
    private var artifactFunction: NonHermeticArtifactFakeFunction? = null
    private var artifacts: MutableList<Artifact.DerivedArtifact?>? = null

    private val pathsToPretendDontExist: MutableSet<PathFragment?> =
        com.google.common.collect.Sets.newConcurrentHashSet<PathFragment?>()

    override fun createFileSystem(): FileSystem {
        return object : DelegateFileSystem(super.createFileSystem()) {
            @Throws(IOException::class)
            public override fun statIfFound(path: PathFragment?, followSymlinks: Boolean): FileStatus? {
                if (pathsToPretendDontExist.contains(path)) {
                    return null
                }
                return super.statIfFound(path, followSymlinks)
            }
        }
    }

    @Before
    fun setUp() {
        artifacts = java.util.ArrayList<Artifact.DerivedArtifact?>()
        val analysisMock: AnalysisMock = AnalysisMock.get()
        pkgLocator =
            AtomicReference<PathPackageLocator?>(
                PathPackageLocator(
                    outputBase,
                    com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(rootDirectory)),
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                )
            )
        val deletedPackages: AtomicReference<com.google.common.collect.ImmutableSet<PackageIdentifier?>?> =
            AtomicReference<com.google.common.collect.ImmutableSet<PackageIdentifier?>?>(com.google.common.collect.ImmutableSet.of<PackageIdentifier?>())
        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(rootDirectory, outputBase, rootDirectory),
                rootDirectory,
                analysisMock.productName
            )
        val externalFilesHelper: ExternalFilesHelper? =
            ExternalFilesHelper.createForTesting(
                pkgLocator,
                ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
                directories
            )

        val skyFunctions: MutableMap<SkyFunctionName?, SkyFunction?> = HashMap<SkyFunctionName?, SkyFunction?>()
        skyFunctions.put(
            FileStateKey.FILE_STATE,
            FileStateFunction(
                com.google.common.base.Suppliers.ofInstance<T?>(TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())),
                SyscallCache.NO_CACHE,
                externalFilesHelper
            )
        )
        skyFunctions.put(SkyFunctions.FILE, FileFunction(pkgLocator, directories))
        skyFunctions.put(SkyFunctions.DIRECTORY_LISTING, DirectoryListingFunction())
        skyFunctions.put(
            SkyFunctions.DIRECTORY_LISTING_STATE,
            DirectoryListingStateFunction(externalFilesHelper, SyscallCache.NO_CACHE)
        )
        skyFunctions.put(
            SkyFunctions.RECURSIVE_FILESYSTEM_TRAVERSAL,
            RecursiveFilesystemTraversalFunction(SyscallCache.NO_CACHE)
        )
        skyFunctions.put(
            SkyFunctions.PACKAGE_LOOKUP,
            PackageLookupFunction(
                deletedPackages,
                CrossRepositoryLabelViolationStrategy.ERROR,
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        )
        skyFunctions.put(SkyFunctions.IGNORED_SUBDIRECTORIES, IgnoredSubdirectoriesFunction.NOOP)
        skyFunctions.put(SkyFunctions.PACKAGE, PackageFunction.newBuilder().build())
        skyFunctions.put(SkyFunctions.LOCAL_REPOSITORY_LOOKUP, LocalRepositoryLookupFunction())
        skyFunctions.put(
            FileSymlinkInfiniteExpansionUniquenessFunction.NAME,
            FileSymlinkInfiniteExpansionUniquenessFunction()
        )
        skyFunctions.put(
            FileSymlinkCycleUniquenessFunction.NAME, FileSymlinkCycleUniquenessFunction()
        )
        // We use a non-hermetic key to allow us to invalidate the proper artifacts on rebuilds. We
        // could have the artifact depend on the corresponding FileValue, but that would not cover the
        // case of a generated directory, which we have test coverage for.
        skyFunctions.put(Artifact.ARTIFACT, ArtifactFakeFunction())
        artifactFunction = NonHermeticArtifactFakeFunction()
        skyFunctions.put(SkyFunctions.ACTION_EXECUTION, ActionFakeFunction())
        skyFunctions.put(NONHERMETIC_ARTIFACT, artifactFunction)

        progressReceiver = RecordingEvaluationProgressReceiver()
        differencer = SequencedRecordingDifferencer()
        evaluator = InMemoryMemoizingEvaluator(skyFunctions, differencer, progressReceiver)
        PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID())
        PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get())
        PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT)
    }

    private fun sourceArtifact(path: String?): Artifact {
        return ActionsTestUtil.createArtifact(
            ArtifactRoot.asSourceRoot(Root.fromPath(rootDirectory)), path
        )
    }

    private fun treeArtifact(path: String?): SpecialArtifact {
        return ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            ArtifactRoot.asDerivedRoot(rootDirectory, RootType.OUTPUT, "out"),
            PathFragment.create("out/" + path)
        )
    }

    @Throws(IOException::class)
    private fun addNewTreeFileArtifact(parent: SpecialArtifact?, relatedPath: String?) {
        val treeFileArtifact: TreeFileArtifact = TreeFileArtifact.createTreeOutput(parent, relatedPath)
        artifactFunction!!.addNewTreeFileArtifact(treeFileArtifact)
    }

    private fun derivedArtifact(path: String?): Artifact {
        val execPath: PathFragment? = PathFragment.create("out").getRelative(path)
        val result: Artifact.DerivedArtifact =
            ActionsTestUtil.createArtifactWithExecPath(
                ArtifactRoot.asDerivedRoot(rootDirectory, RootType.OUTPUT, "out"), execPath
            ) as Artifact.DerivedArtifact
        result.setGeneratingActionKey(
            ActionLookupData.create(ActionsTestUtil.NULL_ARTIFACT_OWNER, artifacts!!.size)
        )
        artifacts!!.add(result)
        return result
    }

    private fun rootedPath(path: String?, packagePath: String?): RootedPath {
        return RootedPath.toRootedPath(
            Root.fromPath(rootDirectory.getRelative(packagePath)), PathFragment.create(path)
        )
    }

    @Throws(java.lang.Exception::class)
    private fun createFile(path: Path, vararg contents: String?) {
        if (!path.getParentDirectory().exists()) {
            scratch.dir(path.getParentDirectory().getPathString())
        }
        scratch.file(path.getPathString(), contents)
    }

    @Throws(java.lang.Exception::class)
    private fun createFile(artifact: Artifact, vararg contents: String?) {
        createFile(artifact.getPath(), contents)
    }

    @Throws(java.lang.Exception::class)
    private fun createFile(path: RootedPath, vararg contents: String?): RootedPath {
        scratch.dir(parentOf(path).asPath().getPathString())
        createFile(path.asPath(), contents)
        return path
    }

    @AutoValue
    internal abstract class BasicTraversalRequest : TraversalRequest() {
        protected override fun errorInfo(): String {
            return ""
        }

        protected override fun duplicateWithOverrides(
            root: DirectTraversalRoot?, skipTestingForSubpackage: Boolean
        ): TraversalRequest {
            return AutoValue_RecursiveFilesystemTraversalFunctionTest_BasicTraversalRequest(
                root,
                isRootGenerated,
                strictOutputFiles(),
                skipTestingForSubpackage,
                emitEmptyDirectoryNodes()
            )
        }
    }

    @Throws(java.lang.Exception::class)
    private fun <T : SkyValue?> eval(key: SkyKey): EvaluationResult<T?> {
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        return evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), evaluationContext)
    }

    @Throws(java.lang.Exception::class)
    private fun evalTraversalRequest(params: TraversalRequest): RecursiveFilesystemTraversalValue {
        val result: EvaluationResult<RecursiveFilesystemTraversalValue?> = eval<T?>(params)
        assertThat(result.hasError()).isFalse()
        return result.get(params)
    }

    /**
     * Asserts that the requested SkyValue can be built and results in the expected set of files.
     * 
     * 
     * The metadata of files is ignored in comparing the actual results with the expected ones. The
     * returned object however contains the actual metadata.
     */
    @Throws(java.lang.Exception::class)
    private fun traverseAndAssertFiles(
        params: TraversalRequest, vararg expectedFilesIgnoringMetadata: ResolvedFile
    ): RecursiveFilesystemTraversalValue {
        val result: RecursiveFilesystemTraversalValue = evalTraversalRequest(params)
        val nameToActualResolvedFiles: MutableMap<PathFragment?, ResolvedFile> = HashMap<PathFragment?, ResolvedFile>()
        for (act in result.getTransitiveFiles().toList()) {
            // We can't compare  directly, since metadata would be different, so we compare
            // by comparing the results of public method calls..
            nameToActualResolvedFiles.put(act.nameInSymlinkTree, act)
        }
        assertExpectedResolvedFilesPresent(nameToActualResolvedFiles, *expectedFilesIgnoringMetadata)
        return result
    }

    @Throws(java.lang.Exception::class)
    private fun appendToFile(rootedPath: RootedPath, toInvalidate: SkyKey, content: String) {
        val path: Path = rootedPath.asPath()
        if (path.exists()) {
            path.getOutputStream( /* append= */true).use { os ->
                os.write(content.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            }
            differencer.invalidate(com.google.common.collect.ImmutableList.of<E?>(toInvalidate))
        } else {
            createFile(path, content)
        }
    }

    @Throws(java.lang.Exception::class)
    private fun appendToFile(rootedPath: RootedPath, content: String) {
        appendToFile(rootedPath, FileStateValue.key(rootedPath), content)
    }

    @Throws(java.lang.Exception::class)
    private fun appendToFile(file: Artifact, content: String) {
        val key: SkyKey =
            if (file.isSourceArtifact())
                FileStateValue.key(rootedPath(file))
            else
                NonHermeticArtifactSkyKey(file)
        appendToFile(rootedPath(file), key, content)
    }

    private fun invalidateDirectory(path: RootedPath?) {
        differencer.invalidate(com.google.common.collect.ImmutableList.of<E?>(DirectoryListingStateValue.key(path)))
    }

    private fun invalidateDirectory(directoryArtifact: Artifact) {
        invalidateDirectory(rootedPath(directoryArtifact))
    }

    private fun invalidateOutputArtifact(output: Artifact) {
        assertThat(output.isSourceArtifact()).isFalse()
        differencer.invalidate(com.google.common.collect.ImmutableList.of<E?>(NonHermeticArtifactSkyKey(output)))
    }

    private class RecordingEvaluationProgressReceiver

        : EvaluationProgressReceiver {
        var invalidations: MutableSet<SkyKey?>? = null
        var evaluations: MutableSet<SkyKey?>? = null

        init {
            clear()
        }

        fun clear() {
            invalidations = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
            evaluations = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
        }

        public override fun dirtied(skyKey: SkyKey?, dirtyType: DirtyType?) {
            invalidations!!.add(skyKey)
        }

        public override fun deleted(skyKey: SkyKey?) {
            invalidations!!.add(skyKey)
        }

        public override fun evaluated(
            skyKey: SkyKey?,
            state: EvaluationState,
            newValue: SkyValue?,
            newError: ErrorInfo?,
            directDeps: GroupedDeps?
        ) {
            if (state.succeeded()) {
                evaluations!!.add(skyKey)
            }
        }
    }

    @Throws(java.lang.Exception::class)
    private fun assertTraversalOfFile(rootArtifact: Artifact, strictOutput: Boolean) {
        val traversalRoot: TraversalRequest = fileLikeRoot(rootArtifact, strictOutput)
        val rootedPath: RootedPath = createFile(rootedPath(rootArtifact), "foo")

        // Assert that the SkyValue is built and looks right.
        val expected: ResolvedFile = regularFile(rootedPath, EMPTY_METADATA)
        val v1: RecursiveFilesystemTraversalValue = traverseAndAssertFiles(traversalRoot, expected)
        Truth.assertThat(progressReceiver!!.invalidations).isEmpty()
        Truth.assertThat(progressReceiver!!.evaluations).contains(traversalRoot)
        progressReceiver!!.clear()

        // Edit the file and verify that the value is rebuilt.
        appendToFile(rootArtifact, "bar")
        val v2: RecursiveFilesystemTraversalValue = traverseAndAssertFiles(traversalRoot, expected)
        Truth.assertThat(progressReceiver!!.invalidations).contains(traversalRoot)
        Truth.assertThat(progressReceiver!!.evaluations).contains(traversalRoot)
        assertThat(v2).isNotEqualTo(v1)
        assertTraversalRootHashesAreNotEqual(v1, v2)

        progressReceiver!!.clear()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTraversalOfSourceFile() {
        assertTraversalOfFile(sourceArtifact("foo/bar.txt"), false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTraversalOfGeneratedFile() {
        assertTraversalOfFile(derivedArtifact("foo/bar.txt"), false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTraversalOfGeneratedFileWithStrictOutput() {
        assertTraversalOfFile(derivedArtifact("foo/bar.txt"), true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTraversalOfSymlinkToFile() {
        val linkNameArtifact: Artifact = sourceArtifact("foo/baz/qux.sym")
        val linkTargetArtifact: Artifact = sourceArtifact("foo/bar/baz.txt")
        val linkValue: PathFragment? = PathFragment.create("../bar/baz.txt")
        val traversalRoot: TraversalRequest = fileLikeRoot(linkNameArtifact)
        createFile(linkTargetArtifact)
        scratch.dir(linkNameArtifact.getExecPath().getParentDirectory().getPathString())
        rootDirectory.getRelative(linkNameArtifact.getExecPath()).createSymbolicLink(linkValue)

        // Assert that the SkyValue is built and looks right.
        val symlinkNamePath: RootedPath = rootedPath(linkNameArtifact)
        val symlinkTargetPath: RootedPath = rootedPath(linkTargetArtifact)
        val expected: ResolvedFile =
            symlinkToFile(symlinkTargetPath, symlinkNamePath, linkValue, EMPTY_METADATA)
        val v1: RecursiveFilesystemTraversalValue = traverseAndAssertFiles(traversalRoot, expected)
        Truth.assertThat(progressReceiver!!.invalidations).isEmpty()
        Truth.assertThat(progressReceiver!!.evaluations).contains(traversalRoot)
        progressReceiver!!.clear()

        // Edit the target of the symlink and verify that the value is rebuilt.
        appendToFile(linkTargetArtifact, "bar")
        val v2: RecursiveFilesystemTraversalValue = traverseAndAssertFiles(traversalRoot, expected)
        Truth.assertThat(progressReceiver!!.invalidations).contains(traversalRoot)
        Truth.assertThat(progressReceiver!!.evaluations).contains(traversalRoot)
        assertThat(v2).isNotEqualTo(v1)
        assertTraversalRootHashesAreNotEqual(v1, v2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTraversalOfTransitiveSymlinkToFile() {
        val directLinkArtifact: Artifact = sourceArtifact("direct/file.sym")
        val transitiveLinkArtifact: Artifact = sourceArtifact("transitive/sym.sym")
        val fileA: RootedPath = createFile(rootedPath(sourceArtifact("a/file.a")))
        val directLink: RootedPath = rootedPath(directLinkArtifact)
        val transitiveLink: RootedPath = rootedPath(transitiveLinkArtifact)
        val directLinkPath: PathFragment? = PathFragment.create("../a/file.a")
        val transitiveLinkPath: PathFragment? = PathFragment.create("../direct/file.sym")

        parentOf(directLink).asPath().createDirectory()
        parentOf(transitiveLink).asPath().createDirectory()
        directLink.asPath().createSymbolicLink(directLinkPath)
        transitiveLink.asPath().createSymbolicLink(transitiveLinkPath)

        traverseAndAssertFiles(
            fileLikeRoot(directLinkArtifact),
            symlinkToFile(fileA, directLink, directLinkPath, EMPTY_METADATA)
        )

        traverseAndAssertFiles(
            fileLikeRoot(transitiveLinkArtifact),
            symlinkToFile(fileA, transitiveLink, transitiveLinkPath, EMPTY_METADATA)
        )
    }

    @Throws(java.lang.Exception::class)
    private fun assertTraversalOfDirectory(directoryArtifact: Artifact) {
        // Create files under the directory.
        // Use the root + root-relative path of the rootArtifact to create these files, rather than
        // using the rootDirectory + execpath of the rootArtifact. The resulting paths are the same
        // but the RootedPaths are different:
        // in the 1st case, it is: RootedPath(/root/execroot, relative), in the second it is
        // in the 2nd case, it is: RootedPath(/root, execroot/relative).
        // Creating the files will also create the parent directories.
        val file1: RootedPath = createFile(childOf(directoryArtifact, "bar.txt"))
        val file2: RootedPath?
        if (directoryArtifact.isTreeArtifact()) {
            file2 = createFile(childOf(directoryArtifact, "qux.txt"))
            addNewTreeFileArtifact(directoryArtifact as SpecialArtifact, "bar.txt")
            addNewTreeFileArtifact(directoryArtifact as SpecialArtifact, "qux.txt")
        } else {
            file2 = createFile(childOf(directoryArtifact, "baz/qux.txt"))
        }

        val traversalRoot: TraversalRequest = fileLikeRoot(directoryArtifact)

        // Assert that the SkyValue is built and looks right.
        val expected1: ResolvedFile = regularFile(file1, EMPTY_METADATA)
        val expected2: ResolvedFile? = regularFile(file2, EMPTY_METADATA)
        val v1: RecursiveFilesystemTraversalValue =
            traverseAndAssertFiles(traversalRoot, expected1, expected2)
        Truth.assertThat(progressReceiver!!.invalidations).isEmpty()
        Truth.assertThat(progressReceiver!!.evaluations).contains(traversalRoot)
        progressReceiver!!.clear()

        // Add a new file to the directory and see that the value is rebuilt.
        TimestampGranularityUtils.waitForTimestampGranularity(
            directoryArtifact.getPath().stat().getLastChangeTime(), OutErr.SYSTEM_OUT_ERR
        )
        val file3: RootedPath = createFile(childOf(directoryArtifact, "foo.txt"))
        if (directoryArtifact.isTreeArtifact()) {
            addNewTreeFileArtifact(directoryArtifact as SpecialArtifact, "foo.txt")
        }
        if (directoryArtifact.isSourceArtifact()) {
            invalidateDirectory(directoryArtifact)
        } else {
            invalidateOutputArtifact(directoryArtifact)
        }
        val expected3: ResolvedFile? = regularFile(file3, EMPTY_METADATA)
        val v2: RecursiveFilesystemTraversalValue =
            traverseAndAssertFiles(traversalRoot, expected1, expected2, expected3)
        Truth.assertThat(progressReceiver!!.invalidations).contains(traversalRoot)
        Truth.assertThat(progressReceiver!!.evaluations).contains(traversalRoot)
        // Directories always have the same hash code, but that is fine because their contents are also
        // part of the RecursiveFilesystemTraversalValue, so v1 and v2 are unequal.
        assertThat(v2).isNotEqualTo(v1)
        assertTraversalRootHashesAreEqual(v1, v2)
        progressReceiver!!.clear()

        // Edit a file in the directory and see that the value is rebuilt.
        val v3: RecursiveFilesystemTraversalValue
        if (directoryArtifact.isSourceArtifact()) {
            val toInvalidate: SkyKey = FileStateValue.key(file1)
            appendToFile(file1, toInvalidate, "bar")
            v3 = traverseAndAssertFiles(traversalRoot, expected1, expected2, expected3)
            Truth.assertThat(progressReceiver!!.invalidations).contains(traversalRoot)
            Truth.assertThat(progressReceiver!!.evaluations).contains(traversalRoot)
            assertThat(v3).isNotEqualTo(v2)
            // Directories always have the same hash code, but that is fine because their contents are
            // also part of the RecursiveFilesystemTraversalValue, so v2 and v3 are unequal.
            assertTraversalRootHashesAreEqual(v2, v3)
            progressReceiver!!.clear()
        } else {
            // Dependency checking of output directories is unsound. Specifically, the directory mtime
            // is not changed when a contained file is modified.
            v3 = v2
        }

        // Add a new file *outside* of the directory and see that the value is *not* rebuilt.
        val someFile: Artifact = sourceArtifact("somewhere/else/a.file")
        createFile(someFile, "new file")
        appendToFile(someFile, "not all changes are treated equal")
        val v4: RecursiveFilesystemTraversalValue =
            traverseAndAssertFiles(traversalRoot, expected1, expected2, expected3)
        assertThat(v4).isSameInstanceAs(v3)
        assertTraversalRootHashesAreEqual(v3, v4)
        Truth.assertThat(progressReceiver!!.invalidations).doesNotContain(traversalRoot)

        // Add a new empty subdirectory to the directory and see that the value is rebuilt, but results
        // in the collection of files.
        // TODO(#15901): Empty directories currently aren't representable as tree artifact contents and
        //  thus aren't tested here.
        if (!directoryArtifact.isTreeArtifact()) {
            childOf(directoryArtifact, "empty_dir").asPath().createDirectory()
            if (directoryArtifact.isSourceArtifact()) {
                invalidateDirectory(directoryArtifact)
            } else {
                invalidateOutputArtifact(directoryArtifact)
            }

            val v5: RecursiveFilesystemTraversalValue =
                traverseAndAssertFiles(traversalRoot, expected1, expected2, expected3)
            assertThat(v5.getResolvedRoot()).isEqualTo(v4.getResolvedRoot())
            assertThat(v5.getTransitiveFiles().toList())
                .containsExactlyElementsIn(v4.getTransitiveFiles().toList())
            Truth.assertThat(progressReceiver!!.invalidations).contains(traversalRoot)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTraversalOfSourceDirectory() {
        assertTraversalOfDirectory(sourceArtifact("dir"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTraversalOfSourceTreeArtifact() {
        assertTraversalOfDirectory(treeArtifact("dir"))
    }

    // Note that in actual Bazel derived artifact directories are not checked for modifications on
    // incremental builds by default. See TrackSourceDirectoriesFlag.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTraversalOfGeneratedDirectory() {
        assertTraversalOfDirectory(derivedArtifact("dir"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTraversalOfSourceDirectoryWithEmptyDirectoryNodes() {
        val directoryArtifact: Artifact = sourceArtifact("dir")
        directoryArtifact.getPath().createDirectoryAndParents()

        val traversalRoot: TraversalRequest =
            fileLikeRoot(
                directoryArtifact,  /* strictOutput= */false,  /* emitEmptyDirectoryNodes= */true
            )

        // Assert that the SkyValue is built and looks right.
        val rootNode: ResolvedFile =
            ResolvedFileFactory.directory(
                RootedPath.toRootedPath(
                    directoryArtifact.getRoot().getRoot(), directoryArtifact.getRootRelativePath()
                )
            )
        val v1: RecursiveFilesystemTraversalValue = traverseAndAssertFiles(traversalRoot, rootNode)
        Truth.assertThat(progressReceiver!!.invalidations).isEmpty()
        Truth.assertThat(progressReceiver!!.evaluations).contains(traversalRoot)
        progressReceiver!!.clear()

        // Add a new file to the directory and see that the value is rebuilt.
        val emptyDir: RootedPath = childOf(directoryArtifact, "empty_dir")
        emptyDir.asPath().createDirectory()
        val emptyDirNode: ResolvedFile = ResolvedFileFactory.directory(emptyDir)
        invalidateDirectory(directoryArtifact)

        // The value only contains nodes for empty directories - the root dir is no longer empty at this
        // point and thus not represented as a node.
        val v2: RecursiveFilesystemTraversalValue = traverseAndAssertFiles(traversalRoot, emptyDirNode)
        assertThat(v2).isNotEqualTo(v1)
        assertTraversalRootHashesAreEqual(v1, v2)
        Truth.assertThat(progressReceiver!!.invalidations).contains(traversalRoot)
        Truth.assertThat(progressReceiver!!.evaluations).contains(traversalRoot)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTraversalOfTransitiveSymlinkToDirectory() {
        val directLinkArtifact: Artifact = sourceArtifact("direct/dir.sym")
        val transitiveLinkArtifact: Artifact = sourceArtifact("transitive/sym.sym")
        val fileA: RootedPath = createFile(rootedPath(sourceArtifact("a/file.a")))
        val directLink: RootedPath = rootedPath(directLinkArtifact)
        val transitiveLink: RootedPath = rootedPath(transitiveLinkArtifact)
        val directLinkPath: PathFragment? = PathFragment.create("../a")
        val transitiveLinkPath: PathFragment? = PathFragment.create("../direct/dir.sym")

        parentOf(directLink).asPath().createDirectory()
        parentOf(transitiveLink).asPath().createDirectory()
        directLink.asPath().createSymbolicLink(directLinkPath)
        transitiveLink.asPath().createSymbolicLink(transitiveLinkPath)

        // Expect the file as if was a child of the direct symlink, not of the actual directory.
        traverseAndAssertFiles(
            fileLikeRoot(directLinkArtifact),
            symlinkToDirectory(parentOf(fileA), directLink, directLinkPath, EMPTY_METADATA),
            regularFile(childOf(directLinkArtifact, "file.a"), EMPTY_METADATA)
        )

        // Expect the file as if was a child of the transitive symlink, not of the actual directory.
        traverseAndAssertFiles(
            fileLikeRoot(transitiveLinkArtifact),
            symlinkToDirectory(parentOf(fileA), transitiveLink, transitiveLinkPath, EMPTY_METADATA),
            regularFile(childOf(transitiveLinkArtifact, "file.a"), EMPTY_METADATA)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTraversePackage() {
        val buildFile: Artifact = sourceArtifact("pkg/BUILD")
        val buildFilePath: RootedPath = createFile(rootedPath(buildFile))
        val file1: RootedPath = createFile(siblingOf(buildFile, "subdir/file.a"))

        traverseAndAssertFiles(
            pkgRoot(parentOf(buildFilePath)),
            regularFile(buildFilePath, EMPTY_METADATA),
            regularFile(file1, EMPTY_METADATA)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTraversalOfSymlinkToDirectory() {
        val linkNameArtifact: Artifact = sourceArtifact("link/foo.sym")
        val linkTargetArtifact: Artifact = sourceArtifact("dir")
        val linkName: RootedPath = rootedPath(linkNameArtifact)
        val linkValue: PathFragment? = PathFragment.create("../dir")
        val file1: RootedPath = createFile(childOf(linkTargetArtifact, "file.1"))
        createFile(childOf(linkTargetArtifact, "sub/file.2"))
        scratch.dir(parentOf(linkName).asPath().getPathString())
        linkName.asPath().createSymbolicLink(linkValue)

        // Assert that the SkyValue is built and looks right.
        val traversalRoot: TraversalRequest = fileLikeRoot(linkNameArtifact)
        val expected1: ResolvedFile =
            symlinkToDirectory(rootedPath(linkTargetArtifact), linkName, linkValue, EMPTY_METADATA)
        val expected2: ResolvedFile? = regularFile(childOf(linkNameArtifact, "file.1"), EMPTY_METADATA)
        val expected3: ResolvedFile? = regularFile(childOf(linkNameArtifact, "sub/file.2"), EMPTY_METADATA)
        // We expect to see all the files from the symlink'd directory, under the symlink's path, not
        // under the symlink target's path.
        val v1: RecursiveFilesystemTraversalValue =
            traverseAndAssertFiles(traversalRoot, expected1, expected2, expected3)
        Truth.assertThat(progressReceiver!!.invalidations).isEmpty()
        Truth.assertThat(progressReceiver!!.evaluations).contains(traversalRoot)
        progressReceiver!!.clear()

        // Add a new file to the directory and see that the value is rebuilt.
        createFile(childOf(linkTargetArtifact, "file.3"))
        invalidateDirectory(linkTargetArtifact)
        val expected4: ResolvedFile? = regularFile(childOf(linkNameArtifact, "file.3"), EMPTY_METADATA)
        val v2: RecursiveFilesystemTraversalValue =
            traverseAndAssertFiles(traversalRoot, expected1, expected2, expected3, expected4)
        Truth.assertThat(progressReceiver!!.invalidations).contains(traversalRoot)
        Truth.assertThat(progressReceiver!!.evaluations).contains(traversalRoot)
        assertThat(v2).isNotEqualTo(v1)
        assertTraversalRootHashesAreNotEqual(v1, v2)
        progressReceiver!!.clear()

        // Edit a file in the directory and see that the value is rebuilt.
        appendToFile(file1, "bar")
        val v3: RecursiveFilesystemTraversalValue =
            traverseAndAssertFiles(traversalRoot, expected1, expected2, expected3, expected4)
        Truth.assertThat(progressReceiver!!.invalidations).contains(traversalRoot)
        Truth.assertThat(progressReceiver!!.evaluations).contains(traversalRoot)
        assertThat(v3).isNotEqualTo(v2)
        assertTraversalRootHashesAreNotEqual(v2, v3)
        progressReceiver!!.clear()

        // Add a new file *outside* of the directory and see that the value is *not* rebuilt.
        val someFile: Artifact = sourceArtifact("somewhere/else/a.file")
        createFile(someFile, "new file")
        appendToFile(someFile, "not all changes are treated equal")
        val v4: RecursiveFilesystemTraversalValue =
            traverseAndAssertFiles(traversalRoot, expected1, expected2, expected3, expected4)
        assertThat(v4).isEqualTo(v3)
        assertTraversalRootHashesAreEqual(v3, v4)
        Truth.assertThat(progressReceiver!!.invalidations).doesNotContain(traversalRoot)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTraversalOfDanglingSymlink() {
        val linkArtifact: Artifact = sourceArtifact("a/dangling.sym")
        val link: RootedPath = rootedPath(linkArtifact)
        val linkTarget: PathFragment? = PathFragment.create("non_existent")
        parentOf(link).asPath().createDirectory()
        link.asPath().createSymbolicLink(linkTarget)
        traverseAndAssertFiles(fileLikeRoot(linkArtifact), danglingSymlink(link, linkTarget))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTraversalOfDanglingSymlinkInADirectory() {
        val dirArtifact: Artifact = sourceArtifact("a")
        val file: RootedPath = createFile(childOf(dirArtifact, "file.txt"))
        val link: RootedPath = rootedPath(sourceArtifact("a/dangling.sym"))
        val linkTarget: PathFragment? = PathFragment.create("non_existent")
        parentOf(link).asPath().createDirectory()
        link.asPath().createSymbolicLink(linkTarget)
        traverseAndAssertFiles(
            fileLikeRoot(dirArtifact),
            regularFile(file, EMPTY_METADATA),
            danglingSymlink(link, linkTarget)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReportErrorWhenTraversingSubpackages() {
        val pkgDirArtifact: Artifact = sourceArtifact("pkg1/foo")
        val subpkgDirArtifact: Artifact = sourceArtifact("pkg1/foo/subdir/subpkg")
        val pkgBuildFile: RootedPath = childOf(pkgDirArtifact, "BUILD")
        val subpkgBuildFile: RootedPath = childOf(subpkgDirArtifact, "BUILD")
        scratch.dir(rootedPath(pkgDirArtifact).asPath().getPathString())
        scratch.dir(rootedPath(subpkgDirArtifact).asPath().getPathString())
        createFile(pkgBuildFile)
        createFile(subpkgBuildFile)

        val traversalRoot: TraversalRequest = pkgRoot(parentOf(pkgBuildFile))
        val result: EvaluationResult<SkyValue?> = eval<T?>(traversalRoot)

        assertThat(result.hasError()).isTrue()
        assertThat(result.getError().getException())
            .hasMessageThat()
            .contains("crosses package boundary into package rooted at")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileDigestChangeCausesRebuild() {
        val artifact: Artifact = sourceArtifact("foo/bar.txt")
        val path: RootedPath = rootedPath(artifact)
        createFile(path, "hello")

        // Assert that the SkyValue is built and looks right.
        val params: TraversalRequest = fileLikeRoot(artifact)
        val expected: ResolvedFile = regularFile(path, EMPTY_METADATA)
        val v1: RecursiveFilesystemTraversalValue = traverseAndAssertFiles(params, expected)
        Truth.assertThat(progressReceiver!!.evaluations).contains(params)
        progressReceiver!!.clear()

        // Change the digest of the file. See that the value is rebuilt.
        appendToFile(path, "world")
        val v2: RecursiveFilesystemTraversalValue = traverseAndAssertFiles(params, expected)
        Truth.assertThat(progressReceiver!!.invalidations).contains(params)
        assertThat(v2).isNotEqualTo(v1)
        assertTraversalRootHashesAreNotEqual(v1, v2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileMtimeChangeDoesNotCauseRebuildIfDigestIsUnchanged() {
        val artifact: Artifact = sourceArtifact("foo/bar.txt")
        val path: RootedPath = rootedPath(artifact)
        createFile(path, "hello")

        // Assert that the SkyValue is built and looks right.
        val params: TraversalRequest = fileLikeRoot(artifact)
        val expected: ResolvedFile = regularFile(path, EMPTY_METADATA)
        val v1: RecursiveFilesystemTraversalValue = traverseAndAssertFiles(params, expected)
        Truth.assertThat(progressReceiver!!.evaluations).contains(params)
        progressReceiver!!.clear()

        // Change the mtime of the file but not the digest. See that the value is *not* rebuilt.
        TimestampGranularityUtils.waitForTimestampGranularity(
            path.asPath().stat().lastChangeTime, OutErr.SYSTEM_OUT_ERR
        )
        path.asPath().setLastModifiedTime(java.lang.System.currentTimeMillis())
        val v2: RecursiveFilesystemTraversalValue = traverseAndAssertFiles(params, expected)
        assertThat(v2).isEqualTo(v1)
        assertTraversalRootHashesAreEqual(v1, v2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGeneratedDirectoryConflictsWithPackage() {
        val genDir: Artifact = derivedArtifact("a/b")
        createFile(rootedPath(sourceArtifact("a/b/c/file.real")))
        createFile(rootedPath(derivedArtifact("a/b/c/file.fake")))
        createFile(sourceArtifact("a/b/c/BUILD"))

        val key: SkyKey = fileLikeRoot(genDir)
        val result: EvaluationResult<SkyValue?> = eval<SkyValue?>(key)
        assertThat(result.hasError()).isTrue()
        val error: ErrorInfo = result.getError(key)
        assertThat(error.isTransitivelyTransient).isFalse()
        assertThat(error.getException())
            .hasMessageThat()
            .contains("Generated directory a/b/c conflicts with package under the same path.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unboundedSymlinkExpansionError() {
        val bazLink: Artifact = sourceArtifact("foo/baz.sym")
        val parentDir: Path = scratch.dir("foo")
        bazLink.getPath().createSymbolicLink(parentDir)
        val key: SkyKey = pkgRoot(parentOf(rootedPath(bazLink)))
        val result: EvaluationResult<SkyValue?> = eval<SkyValue?>(key)
        assertThat(result.hasError()).isTrue()
        val error: ErrorInfo = result.getError(key)
        assertThat(error.getException()).isInstanceOf(RecursiveFilesystemTraversalException::class.java)
        assertThat((error.getException() as RecursiveFilesystemTraversalException).type)
            .isEqualTo(RecursiveFilesystemTraversalException.Type.SYMLINK_CYCLE_OR_INFINITE_EXPANSION)
        assertThat(error.getException()).hasMessageThat().contains("Infinite symlink expansion")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun symlinkChainError() {
        scratch.dir("a")
        val fooLink: Artifact = sourceArtifact("a/foo.sym")
        val barLink: Artifact = sourceArtifact("a/bar.sym")
        val bazLink: Artifact = sourceArtifact("a/baz.sym")
        fooLink.getPath().createSymbolicLink(barLink.getPath())
        barLink.getPath().createSymbolicLink(bazLink.getPath())
        bazLink.getPath().createSymbolicLink(fooLink.getPath())

        val key: SkyKey = pkgRoot(parentOf(rootedPath(bazLink)))
        val result: EvaluationResult<SkyValue?> = eval<SkyValue?>(key)
        assertThat(result.hasError()).isTrue()
        val error: ErrorInfo = result.getError(key)
        assertThat(error.getException()).isInstanceOf(RecursiveFilesystemTraversalException::class.java)
        assertThat((error.getException() as RecursiveFilesystemTraversalException).type)
            .isEqualTo(RecursiveFilesystemTraversalException.Type.SYMLINK_CYCLE_OR_INFINITE_EXPANSION)
        assertThat(error.getException()).hasMessageThat().contains("Symlink cycle")
    }

    private class NonHermeticArtifactFakeFunction : SkyFunction {
        private var tree: TreeArtifactValue.Builder? = null

        @Throws(SkyFunctionException::class)
        public override fun compute(skyKey: SkyKey, env: Environment?): SkyValue {
            try {
                if (skyKey.argument() is Artifact
                    && (skyKey.argument() as Artifact).isTreeArtifact()
                ) {
                    return tree.build()
                }
                return FileArtifactValue.createForTesting((skyKey.argument() as Artifact).getPath())
            } catch (e: IOException) {
                throw object : SkyFunctionException(e, Transience.PERSISTENT) {}
            }
        }

        @Throws(IOException::class)
        fun addNewTreeFileArtifact(input: TreeFileArtifact) {
            if (tree == null) {
                tree = TreeArtifactValue.newBuilder(input.getParent())
            }
            tree.putChild(input, FileArtifactValue.createForTesting(input.getPath()))
        }
    }

    private class ArtifactFakeFunction : SkyFunction {
        @Throws(java.lang.InterruptedException::class)
        public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue {
            return env.getValue(NonHermeticArtifactSkyKey(skyKey))
        }
    }

    private inner class ActionFakeFunction : SkyFunction {
        @Throws(java.lang.InterruptedException::class)
        public override fun compute(skyKey: SkyKey, env: Environment): SkyValue? {
            return env.getValue(
                NonHermeticArtifactSkyKey(
                    < SkyKey > checkNotNull < SkyKey ? > (artifacts!!.get((skyKey as ActionLookupData).getActionIndex()),
                skyKey
            )))
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileArtifactValueRetainsData() {
        val artifact: Artifact = derivedArtifact("foo/fooy.txt")
        val strictArtifact: Artifact = derivedArtifact("goo/gooy.txt")
        createFile(rootedPath(artifact), "fooy")
        createFile(rootedPath(strictArtifact), "gooy")
        val request: TraversalRequest = fileLikeRoot(artifact, false)
        val strictRequest: TraversalRequest = fileLikeRoot(strictArtifact, true)

        val result: EvaluationResult<RecursiveFilesystemTraversalValue?> = eval<T?>(request)
        val strictResult: EvaluationResult<RecursiveFilesystemTraversalValue?> = eval<T?>(strictRequest)

        assertThat(result.values()).hasSize(1)
        assertThat(strictResult.values()).hasSize(1)

        val value: RecursiveFilesystemTraversalValue = result.values().iterator().next()
        val strictValue: RecursiveFilesystemTraversalValue = strictResult.values().iterator().next()
        val resolvedFile: ResolvedFile = value.getResolvedRoot().get()
        val strictResolvedFile: ResolvedFile = strictValue.getResolvedRoot().get()

        assertThat(resolvedFile.metadata).isInstanceOf(FileArtifactValue::class.java)
        assertThat(strictResolvedFile.metadata).isInstanceOf(FileArtifactValue::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWithDigestFileArtifactValue() {
        // file artifacts will return the same bytes as it was initialized with
        val expectedBytes = byteArrayOf(1, 2, 3)
        val fav: FileArtifactValue? = FileArtifactValue.createForVirtualActionInput(expectedBytes, 10L)
        val result: HasDigest =
            RecursiveFilesystemTraversalFunction.withDigest(fav, null, SyscallCache.NO_CACHE)
        assertThat(result).isInstanceOf(FileArtifactValue::class.java)
        assertThat(result.getDigest()).isEqualTo(expectedBytes)

        // Directories do not have digest but the result will have a fingerprinted digest
        val directoryFav: FileArtifactValue? = FileArtifactValue.createForDirectoryWithMtime(10L)
        val directoryResult: HasDigest =
            RecursiveFilesystemTraversalFunction.withDigest(directoryFav, null, SyscallCache.NO_CACHE)
        assertThat(directoryResult).isInstanceOf(HasDigest.ByteStringDigest::class.java)
        assertThat(directoryResult.getDigest()).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWithDigestFileStateValue() {
        // RegularFileStateValue with actual digest will be transformed with the same digest
        val expectedBytes = byteArrayOf(1, 2, 3)
        val withDigest: RegularFileStateValueWithDigest =
            RegularFileStateValueWithDigest( /* size= */10L,  /* digest= */expectedBytes)
        val result: HasDigest =
            RecursiveFilesystemTraversalFunction.withDigest(withDigest, null, SyscallCache.NO_CACHE)
        assertThat(result).isInstanceOf(FileArtifactValue::class.java)
        assertThat(result.getDigest()).isEqualTo(expectedBytes)

        // FileStateValue will be transformed with fingerprinted digest
        val rootedPath: RootedPath = rootedPath("bar", "foo")
        val fsv: FileStateValue? = FileStateValue.create(rootedPath, SyscallCache.NO_CACHE,  /* tsgm= */null)
        val fsvResult: HasDigest =
            RecursiveFilesystemTraversalFunction.withDigest(fsv, null, SyscallCache.NO_CACHE)
        assertThat(fsvResult).isInstanceOf(HasDigest.ByteStringDigest::class.java)
        assertThat(fsvResult.getDigest()).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegularFileStateValueWithoutDigest() {
        val artifact: Artifact = derivedArtifact("foo/fooy.txt")
        val rootedPath: RootedPath = rootedPath(artifact)
        createFile(rootedPath, "fooy-content")
        val status: FileStatus = rootedPath.asPath().stat()

        val withoutDigest: RegularFileStateValueWithContentsProxy =
            RegularFileStateValueWithContentsProxy(
                status.size,  /* contentsProxy= */FileContentsProxy.create(status)
            )
        val withoutDigestResult: HasDigest =
            RecursiveFilesystemTraversalFunction.withDigest(
                withoutDigest, rootedPath.asPath(), SyscallCache.NO_CACHE
            )
        // withDigest will construct a FileArtifactValue using the Path
        assertThat(withoutDigestResult).isInstanceOf(FileArtifactValue::class.java)
        assertThat(withoutDigestResult.getDigest()).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWithDigestByteStringDigest() {
        val expectedBytes = byteArrayOf(1, 2, 3)
        val byteStringDigest: HasDigest.ByteStringDigest = ByteStringDigest(expectedBytes)
        val result: HasDigest =
            RecursiveFilesystemTraversalFunction.withDigest(
                byteStringDigest, null, SyscallCache.NO_CACHE
            )
        assertThat(result).isInstanceOf(HasDigest.ByteStringDigest::class.java)
        assertThat(result.getDigest()).isEqualTo(expectedBytes)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGracefullyHandlesInconsistentFilesystem() {
        scratch.dir("parent")
        val childPathFragment: PathFragment? = scratch.file("parent/child").asFragment()
        pathsToPretendDontExist.add(childPathFragment)
        val childArtifact: Artifact = sourceArtifact("parent/child")
        val key: SkyKey = pkgRoot(parentOf(rootedPath(childArtifact)))
        val result: EvaluationResult<SkyValue?> = eval<SkyValue?>(key)
        assertThat(result.hasError()).isTrue()
        val error: ErrorInfo = result.getError(key)
        assertThat(error.getException()).isInstanceOf(RecursiveFilesystemTraversalException::class.java)
        assertThat((error.getException() as RecursiveFilesystemTraversalException).type)
            .isEqualTo(RecursiveFilesystemTraversalException.Type.INCONSISTENT_FILESYSTEM)
        assertThat(error.getException())
            .hasMessageThat()
            .contains("We were previously told [/workspace]/[parent/child] was an existing file but")
    }

    private class NonHermeticArtifactSkyKey(arg: SkyKey?) : AbstractSkyKey<SkyKey?>(arg) {
        public override fun functionName(): SkyFunctionName? {
            return NONHERMETIC_ARTIFACT
        }
    }

    companion object {
        private val EMPTY_METADATA: HasDigest? = HasDigest.EMPTY

        private fun rootedPath(artifact: Artifact): RootedPath {
            return RootedPath.toRootedPath(artifact.getRoot().getRoot(), artifact.getRootRelativePath())
        }

        private fun childOf(artifact: Artifact, relative: String?): RootedPath {
            return RootedPath.toRootedPath(
                artifact.getRoot().getRoot(), artifact.getRootRelativePath().getRelative(relative)
            )
        }

        private fun parentOf(path: RootedPath): RootedPath? {
            return com.google.common.base.Preconditions.checkNotNull<T?>(path.getParentDirectory())
        }

        private fun siblingOf(artifact: Artifact, relative: String?): RootedPath {
            val parent: PathFragment = com.google.common.base.Preconditions.checkNotNull<T>(
                artifact.getRootRelativePath().getParentDirectory()
            )
            return RootedPath.toRootedPath(artifact.getRoot().getRoot(), parent.getRelative(relative))
        }

        private fun fileLikeRoot(
            file: Artifact, strictOutput: Boolean, emitEmptyDirectoryNodes: Boolean
        ): TraversalRequest {
            return AutoValue_RecursiveFilesystemTraversalFunctionTest_BasicTraversalRequest(
                DirectTraversalRoot.forFileOrDirectory(file),  /* isRootGenerated= */
                !file.isSourceArtifact(),
                strictOutput,  /* skipTestingForSubpackage= */
                false,
                emitEmptyDirectoryNodes
            )
        }

        private fun fileLikeRoot(file: Artifact, strictOutput: Boolean): TraversalRequest {
            return fileLikeRoot(file, strictOutput,  /* emitEmptyDirectoryNodes= */false)
        }

        private fun fileLikeRoot(file: Artifact): TraversalRequest {
            return fileLikeRoot(file, false)
        }

        private fun pkgRoot(pkgDirectory: RootedPath?): TraversalRequest {
            return AutoValue_RecursiveFilesystemTraversalFunctionTest_BasicTraversalRequest(
                DirectTraversalRoot.forRootedPath(pkgDirectory),  /* isRootGenerated= */
                false,  /* strictOutputFiles= */
                false,  /* skipTestingForSubpackage= */
                true,  /* emitEmptyDirectoryNodes= */
                false
            )
        }

        @Throws(java.lang.Exception::class)
        private fun assertExpectedResolvedFilesPresent(
            nameToActualResolvedFiles: MutableMap<PathFragment?, ResolvedFile>,
            vararg expectedFilesIgnoringMetadata: ResolvedFile
        ) {
            Truth.assertWithMessage("Expected files %s", expectedFilesIgnoringMetadata.contentToString())
                .that(nameToActualResolvedFiles)
                .hasSize(expectedFilesIgnoringMetadata.size)
            org.junit.Assert.assertEquals(
                "Unequal number of ResolvedFiles in Actual and expected.",
                expectedFilesIgnoringMetadata.size.toLong(),
                nameToActualResolvedFiles.size.toLong()
            )
            for (expected in expectedFilesIgnoringMetadata) {
                val actual: ResolvedFile = nameToActualResolvedFiles.get(expected.nameInSymlinkTree)
                assertEquals(expected.type, actual.type)
                assertEquals(expected.path, actual.path)
            }
        }

        private fun assertTraversalRootHashesAre(
            equal: Boolean, a: RecursiveFilesystemTraversalValue, b: RecursiveFilesystemTraversalValue
        ) {
            if (equal) {
                assertThat(a.getResolvedRoot().get().hashCode())
                    .isEqualTo(b.getResolvedRoot().get().hashCode())
            } else {
                assertThat(a.getResolvedRoot().get().hashCode())
                    .isNotEqualTo(b.getResolvedRoot().get().hashCode())
            }
        }

        private fun assertTraversalRootHashesAreEqual(
            a: RecursiveFilesystemTraversalValue, b: RecursiveFilesystemTraversalValue
        ) {
            assertTraversalRootHashesAre(true, a, b)
        }

        private fun assertTraversalRootHashesAreNotEqual(
            a: RecursiveFilesystemTraversalValue, b: RecursiveFilesystemTraversalValue
        ) {
            assertTraversalRootHashesAre(false, a, b)
        }

        private val NONHERMETIC_ARTIFACT: SkyFunctionName? = SkyFunctionName.createNonHermetic("NONHERMETIC_ARTIFACT")
    }
}
