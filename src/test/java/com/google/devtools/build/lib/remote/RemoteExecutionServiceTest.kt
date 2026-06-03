// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.actions.ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS

/** Tests for [RemoteExecutionService].  */
@RunWith(TestParameterInjector::class)
class RemoteExecutionServiceTest {
    @org.junit.Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @org.junit.Rule
    val rxNoGlobalErrorsRule: RxNoGlobalErrorsRule = RxNoGlobalErrorsRule()

    @org.mockito.Mock
    private val remoteOutputChecker: RemoteOutputChecker? = null // download nothing by default.

    @org.mockito.Mock
    private val outputService: OutputService? = null

    private val digestUtil: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)
    private val reporter: com.google.devtools.build.lib.events.Reporter =
        com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
    private val eventHandler: StoredEventHandler = StoredEventHandler()

    private val cacheCapabilities: CacheCapabilities? = CacheCapabilities.newBuilder()
        .setActionCacheUpdateCapabilities(
            ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build()
        )
        .setSymlinkAbsolutePathStrategy(SymlinkAbsolutePathStrategy.Value.ALLOWED)
        .build()

    // In the past, Bazel only supports RemoteApi version 2.0.
    // Use this to ensure we are backward compatible with Servers that only support 2.0.
    private val legacyRemoteExecutorCapabilities: ServerCapabilities? = ServerCapabilities.newBuilder()
        .setCacheCapabilities(cacheCapabilities)
        .setLowApiVersion(ApiVersion.twoPointZero.toSemVer())
        .setHighApiVersion(ApiVersion.twoPointZero.toSemVer())
        .setExecutionCapabilities(ExecutionCapabilities.newBuilder().setExecEnabled(true).build())
        .build()

    private val remoteExecutorCapabilities: ServerCapabilities? = ServerCapabilities.newBuilder()
        .setCacheCapabilities(cacheCapabilities)
        .setLowApiVersion(ApiVersion.low.toSemVer())
        .setHighApiVersion(ApiVersion.high.toSemVer())
        .setExecutionCapabilities(ExecutionCapabilities.newBuilder().setExecEnabled(true).build())
        .build()

    var remoteOptions: RemoteOptions? = null
    private var fs: FileSystem? = null
    private var execRoot: Path? = null
    private var sourceRoot: ArtifactRoot? = null
    private var artifactRoot: ArtifactRoot? = null
    private var tempPathGenerator: TempPathGenerator? = null
    private var fakeFileCache: com.google.devtools.build.lib.remote.FakeActionInputFileCache? = null
    private var remotePathResolver: RemotePathResolver? = null
    private var outErr: FileOutErr? = null
    private var cache: InMemoryCombinedCache? = null
    private var executor: RemoteExecutionClient? = null
    private var remoteActionExecutionContext: RemoteActionExecutionContext? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        reporter.addHandler(eventHandler)

        remoteOptions = com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)

        fs = InMemoryFileSystem(com.google.devtools.build.lib.clock.JavaClock(), DigestHashFunction.SHA256)

        execRoot = fs.getPath("/execroot/_main")
        execRoot.createDirectoryAndParents()

        artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "outputs")
        sourceRoot = ArtifactRoot.asSourceRoot(Root.fromPath(execRoot))

        com.google.common.base.Preconditions.checkNotNull<T?>(artifactRoot.getRoot().asPath())
            .createDirectoryAndParents()

        tempPathGenerator = TempPathGenerator(fs.getPath("/execroot/_tmp/actions/remote"))

        fakeFileCache = com.google.devtools.build.lib.remote.FakeActionInputFileCache(execRoot)

        remotePathResolver = DefaultRemotePathResolver(execRoot)

        val stdout: Path = fs.getPath("/tmp/stdout")
        val stderr: Path = fs.getPath("/tmp/stderr")
        com.google.common.base.Preconditions.checkNotNull<T?>(stdout.getParentDirectory()).createDirectoryAndParents()
        com.google.common.base.Preconditions.checkNotNull<T?>(stderr.getParentDirectory()).createDirectoryAndParents()
        outErr = FileOutErr(stdout, stderr)

        cache = Mockito.spy<InMemoryCombinedCache>(
            InMemoryCombinedCache(< T > spy < T ? > (InMemoryCacheClient()),
            digestUtil
        ))
        Mockito.doReturn(remoteExecutorCapabilities).`when`<InMemoryCombinedCache?>(cache).getRemoteServerCapabilities()
        executor = Mockito.mock<RemoteExecutionClient>(RemoteExecutionClient::class.java)
        Mockito.`when`<T?>(executor.getServerCapabilities()).thenReturn(remoteExecutorCapabilities)

        val metadata: RequestMetadata? =
            TracingMetadataUtils.buildMetadata("none", "none", "action-id", null)
        remoteActionExecutionContext = RemoteActionExecutionContext.create(metadata)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildRemoteAction_withRegularFileAsOutput() {
        val execPath: PathFragment = execRoot.getRelative("path/to/tree").asFragment()
        val spawn: Spawn =
            SpawnBuilder("dummy")
                .withOutput(ActionsTestUtil.createArtifactWithExecPath(artifactRoot, execPath))
                .build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()

        val remoteAction: RemoteAction = service.buildRemoteAction(spawn, context)

        assertThat(remoteAction.getCommand().getOutputFilesList()).isEmpty()
        assertThat(remoteAction.getCommand().getOutputDirectoriesList()).isEmpty()
        assertThat(remoteAction.getCommand().getOutputPathsList()).containsExactly(execPath.toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun legacy_buildRemoteAction_withRegularFileAsOutput() {
        Mockito.doReturn(legacyRemoteExecutorCapabilities).`when`<InMemoryCombinedCache?>(cache)
            .getRemoteServerCapabilities()
        Mockito.`when`<T?>(executor.getServerCapabilities()).thenReturn(legacyRemoteExecutorCapabilities)
        val execPath: PathFragment = execRoot.getRelative("path/to/tree").asFragment()
        val spawn: Spawn =
            SpawnBuilder("dummy")
                .withOutput(ActionsTestUtil.createArtifactWithExecPath(artifactRoot, execPath))
                .build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()

        val remoteAction: RemoteAction = service.buildRemoteAction(spawn, context)

        assertThat(remoteAction.getCommand().getOutputFilesList()).containsExactly(execPath.toString())
        assertThat(remoteAction.getCommand().getOutputDirectoriesList()).isEmpty()
        assertThat(remoteAction.getCommand().getOutputPathsList()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildRemoteAction_withTreeArtifactAsOutput() {
        val spawn: Spawn =
            SpawnBuilder("dummy")
                .withOutput(
                    ActionsTestUtil.createTreeArtifactWithGeneratingAction(
                        artifactRoot, PathFragment.create("path/to/dir")
                    )
                )
                .build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()

        val remoteAction: RemoteAction = service.buildRemoteAction(spawn, context)

        assertThat(remoteAction.getCommand().getOutputFilesList()).isEmpty()
        assertThat(remoteAction.getCommand().getOutputDirectoriesList()).isEmpty()
        assertThat(remoteAction.getCommand().getOutputPathsList()).containsExactly("path/to/dir")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun legacy_buildRemoteAction_withTreeArtifactAsOutput() {
        Mockito.doReturn(legacyRemoteExecutorCapabilities).`when`<InMemoryCombinedCache?>(cache)
            .getRemoteServerCapabilities()
        Mockito.`when`<T?>(executor.getServerCapabilities()).thenReturn(legacyRemoteExecutorCapabilities)
        val spawn: Spawn =
            SpawnBuilder("dummy")
                .withOutput(
                    ActionsTestUtil.createTreeArtifactWithGeneratingAction(
                        artifactRoot, PathFragment.create("path/to/dir")
                    )
                )
                .build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()

        val remoteAction: RemoteAction = service.buildRemoteAction(spawn, context)

        assertThat(remoteAction.getCommand().getOutputFilesList()).isEmpty()
        assertThat(remoteAction.getCommand().getOutputDirectoriesList()).containsExactly("path/to/dir")
        assertThat(remoteAction.getCommand().getOutputPathsList()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildRemoteAction_withUnresolvedSymlinkAsOutput() {
        val spawn: Spawn =
            SpawnBuilder("dummy")
                .withOutput(
                    ActionsTestUtil.createUnresolvedSymlinkArtifactWithExecPath(
                        artifactRoot, PathFragment.create("path/to/link")
                    )
                )
                .build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()

        val remoteAction: RemoteAction = service.buildRemoteAction(spawn, context)

        assertThat(remoteAction.getCommand().getOutputFilesList()).isEmpty()
        assertThat(remoteAction.getCommand().getOutputDirectoriesList()).isEmpty()
        assertThat(remoteAction.getCommand().getOutputPathsList()).containsExactly("path/to/link")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun legacy_buildRemoteAction_withUnresolvedSymlinkAsOutput() {
        Mockito.doReturn(legacyRemoteExecutorCapabilities).`when`<InMemoryCombinedCache?>(cache)
            .getRemoteServerCapabilities()
        Mockito.`when`<T?>(executor.getServerCapabilities()).thenReturn(legacyRemoteExecutorCapabilities)
        val spawn: Spawn =
            SpawnBuilder("dummy")
                .withOutput(
                    ActionsTestUtil.createUnresolvedSymlinkArtifactWithExecPath(
                        artifactRoot, PathFragment.create("path/to/link")
                    )
                )
                .build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()

        val remoteAction: RemoteAction = service.buildRemoteAction(spawn, context)

        assertThat(remoteAction.getCommand().getOutputFilesList()).containsExactly("path/to/link")
        assertThat(remoteAction.getCommand().getOutputDirectoriesList()).isEmpty()
        assertThat(remoteAction.getCommand().getOutputPathsList()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildRemoteAction_withActionInputAsOutput() {
        val spawn: Spawn =
            SpawnBuilder("dummy")
                .withOutput(ActionInputHelper.fromPath(PathFragment.create("path/to/file")))
                .build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()

        val remoteAction: RemoteAction = service.buildRemoteAction(spawn, context)

        assertThat(remoteAction.getCommand().getOutputFilesList()).isEmpty()
        assertThat(remoteAction.getCommand().getOutputDirectoriesList()).isEmpty()
        assertThat(remoteAction.getCommand().getOutputPathsList()).containsExactly("path/to/file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun legacy_buildRemoteAction_withActionInputFileAsOutput() {
        Mockito.doReturn(legacyRemoteExecutorCapabilities).`when`<InMemoryCombinedCache?>(cache)
            .getRemoteServerCapabilities()
        Mockito.`when`<T?>(executor.getServerCapabilities()).thenReturn(legacyRemoteExecutorCapabilities)
        val spawn: Spawn =
            SpawnBuilder("dummy")
                .withOutput(ActionInputHelper.fromPath(PathFragment.create("path/to/file")))
                .build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()

        val remoteAction: RemoteAction = service.buildRemoteAction(spawn, context)

        assertThat(remoteAction.getCommand().getOutputFilesList()).containsExactly("path/to/file")
        assertThat(remoteAction.getCommand().getOutputDirectoriesList()).isEmpty()
        assertThat(remoteAction.getCommand().getOutputPathsList()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildRemoteAction_withActionInputDirectoryAsOutput() {
        val spawn: Spawn =
            SpawnBuilder("dummy")
                .withOutput(ActionInputHelper.fromPath(PathFragment.create("path/to/dir")))
                .build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()

        val remoteAction: RemoteAction = service.buildRemoteAction(spawn, context)

        assertThat(remoteAction.getCommand().getOutputFilesList()).isEmpty()
        assertThat(remoteAction.getCommand().getOutputDirectoriesList()).isEmpty()
        assertThat(remoteAction.getCommand().getOutputPathsList()).containsExactly("path/to/dir")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildRemoteAction_generateActionSalt_differentiateWorkspaceCache() {
        val spawn: Spawn =
            SpawnBuilder("dummy")
                .withExecutionInfo(ExecutionRequirements.DIFFERENTIATE_WORKSPACE_CACHE, "aa")
                .build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()

        val remoteAction: RemoteAction = service.buildRemoteAction(spawn, context)

        val expected: CacheSalt =
            CacheSalt.newBuilder().setMayBeExecutedRemotely(true).setWorkspace("aa").build()
        assertThat(remoteAction.getAction().getSalt()).isEqualTo(expected.toByteString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildRemoteAction_generateActionSalt_noRemoteExec() {
        val spawn: Spawn =
            SpawnBuilder("dummy")
                .withExecutionInfo(ExecutionRequirements.NO_REMOTE_EXEC, "")
                .build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()

        val remoteAction: RemoteAction = service.buildRemoteAction(spawn, context)

        val expected: CacheSalt = CacheSalt.newBuilder().setMayBeExecutedRemotely(false).build()
        assertThat(remoteAction.getAction().getSalt()).isEqualTo(expected.toByteString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildRemoteAction_goldenTest(@TestParameter("1", "2", "3") seed: Int) {
        val inputs: java.util.ArrayList<Artifact?> = java.util.ArrayList<Artifact?>()

        val files: com.google.common.collect.ImmutableList<E> =
            com.google.common.collect.ImmutableList.of<E>(
                "root_file1",
                "root_file2",
                "root_file3",
                "dir/subdir/file1",
                "dir/subdir/file2",
                "dir/subdir/file3",
                "dir/subdir/subdir2/file1",
                "dir/subdir/subdir2/file2",
                "dir/subdir/subdir2/file3",
                "dir/file1",
                "dir/file2",
                "dir/file3",  // These paths sort differently depending on whether they are sorted as Java Unicode
                // or Bazel internal strings.
                unicodeToInternal("path/ﾐ"),
                unicodeToInternal("path/🔥"),  // These paths sort differently depending on whether they are sorted as Strings or as
                // PathFragments.
                "srcs/system/foo.txt",
                "srcs/system-root/bar.txt"
            )
        for (file in files) {
            val input: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                ActionsTestUtil.createArtifact(artifactRoot, file)
            fakeFileCache.createScratchInput(input, "content of " + file)
            inputs.add(input)
        }

        val treeArtifactInput: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(
                artifactRoot, "dir/subdir/tree_artifact"
            )
        val treeArtifactBuilder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            TreeArtifactValue.newBuilder(treeArtifactInput)
        for (file in files) {
            val treeFileArtifact: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                TreeFileArtifact.createTreeOutput(treeArtifactInput, file)
            val digest: Digest =
                fakeFileCache.createScratchInput(treeFileArtifact, "content of tree file " + file)
            treeArtifactBuilder.putChild(
                treeFileArtifact,
                FileArtifactValue.createForNormalFile(
                    digest.getHashBytes().toByteArray(), null, digest.getSizeBytes()
                )
            )
        }
        fakeFileCache.addTreeArtifact(treeArtifactInput, treeArtifactBuilder.build())
        inputs.add(treeArtifactInput)

        val emptyDir: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(artifactRoot, "empty_dir")
        fakeFileCache.addTreeArtifact(emptyDir, TreeArtifactValue.newBuilder(emptyDir).build())
        inputs.add(emptyDir)
        // Add an artifact with the same path but different owner to verify that the directory isn't
        // duplicated in the Merkle tree.
        val execPath: PathFragment? = artifactRoot.getExecPath().getRelative("empty_dir")
        val emptyDirWithDifferentOwner: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Artifact.SpecialArtifact.create(
                artifactRoot,
                execPath,
                ActionsTestUtil.YET_ANOTHER_NULL_ARTIFACT_OWNER,
                Artifact.SpecialArtifactType.TREE
            )
        emptyDirWithDifferentOwner.setGeneratingActionKey(
            ActionsTestUtil.YET_ANOTHER_NULL_ACTION_LOOKUP_DATA
        )
        fakeFileCache.addTreeArtifact(
            emptyDirWithDifferentOwner,
            TreeArtifactValue.newBuilder(emptyDirWithDifferentOwner).build()
        )
        inputs.add(emptyDirWithDifferentOwner)

        val unresolvedSymlink: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createUnresolvedSymlinkArtifact(artifactRoot, "dir/some_link")
        fakeFileCache.createScratchInputSymlink(unresolvedSymlink, "some/target")
        inputs.add(unresolvedSymlink)

        val runfilesTreeRoot: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            artifactRoot.getExecPath().getRelative("dir/my_tool.runfiles")
        val runfilesTree: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createRunfilesArtifact(artifactRoot, runfilesTreeRoot.getPathString())
        fakeFileCache.addRunfilesTree(
            runfilesTree,
            createRunfilesTree(
                runfilesTreeRoot.getPathString(),
                com.google.common.collect.ImmutableList.copyOf<Artifact?>(inputs)
            )
        )
        inputs.add(runfilesTree)

        val outputDir: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(artifactRoot, "dir/output_dir")

        val srcDir: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createArtifactWithExecPath(sourceRoot, PathFragment.create("src_dir"))
        val unused: Digest =
            fakeFileCache.createScratchInputDirectory(
                srcDir,
                Tree.newBuilder()
                    .setRoot(
                        dir(
                            com.google.common.collect.ImmutableList.of<Message?>(
                                file("file1", "content of src_dir/file1"),
                                file("file2", "content of src_dir/file2"),
                                file("file3", "content of src_dir/file3")
                            ),
                            com.google.common.collect.ImmutableMap.of<String?, Directory?>()
                        )
                    )
                    .build()
            )
        inputs.add(srcDir)
        val srcFile1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createArtifactWithExecPath(
                sourceRoot, srcDir.getExecPath().getChild("file1")
            )
        fakeFileCache.createScratchInput(srcFile1, "content of src_dir/file1")
        inputs.add(srcFile1)
        val srcFile2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createArtifactWithExecPath(
                sourceRoot, srcDir.getExecPath().getChild("file2")
            )
        fakeFileCache.createScratchInput(srcFile2, "content of src_dir/file2")
        inputs.add(srcFile2)
        val srcFile3: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createArtifactWithExecPath(
                sourceRoot, srcDir.getExecPath().getChild("file3")
            )
        fakeFileCache.createScratchInput(srcFile3, "content of src_dir/file3")

        // Explicitly don't add srcFile3 to inputs so that srcDir overlaps non-trivially with input
        // files.

        // Verify that the order of inputs does not affect the result.
        Collections.shuffle(inputs, java.util.random.RandomGeneratorFactory.getDefault().create(seed.toLong()))
        val spawn: @NotNull Spawn = SpawnBuilder("my", "args").withInputs(inputs).withOutput(outputDir).build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        remoteOptions.remoteDiscardMerkleTrees = false
        val service: RemoteExecutionService = newRemoteExecutionService(remoteOptions)

        val emptyDirectory: Directory = dir(
            com.google.common.collect.ImmutableList.of<Message?>(),
            com.google.common.collect.ImmutableMap.of<String?, Directory?>()
        )
        val treeArtifactDirectory: Directory =
            dir(
                com.google.common.collect.ImmutableList.of<Message?>(
                    file("root_file1", "content of tree file root_file1"),
                    file("root_file2", "content of tree file root_file2"),
                    file("root_file3", "content of tree file root_file3")
                ),
                com.google.common.collect.ImmutableMap.of<String?, Directory?>(
                    "dir",
                    dir(
                        com.google.common.collect.ImmutableList.of<Message?>(
                            file("file1", "content of tree file dir/file1"),
                            file("file2", "content of tree file dir/file2"),
                            file("file3", "content of tree file dir/file3")
                        ),
                        com.google.common.collect.ImmutableMap.of<String?, Directory?>(
                            "subdir",
                            dir(
                                com.google.common.collect.ImmutableList.of<Message?>(
                                    file("file1", "content of tree file dir/subdir/file1"),
                                    file("file2", "content of tree file dir/subdir/file2"),
                                    file("file3", "content of tree file dir/subdir/file3")
                                ),
                                com.google.common.collect.ImmutableMap.of<String?, Directory?>(
                                    "subdir2",
                                    dir(
                                        com.google.common.collect.ImmutableList.of<Message?>(
                                            file(
                                                "file1",
                                                "content of tree file dir/subdir/subdir2/file1"
                                            ),
                                            file(
                                                "file2",
                                                "content of tree file dir/subdir/subdir2/file2"
                                            ),
                                            file(
                                                "file3",
                                                "content of tree file dir/subdir/subdir2/file3"
                                            )
                                        ),
                                        com.google.common.collect.ImmutableMap.of<String?, Directory?>()
                                    )
                                )
                            )
                        )
                    ),
                    "path",
                    dir(
                        com.google.common.collect.ImmutableList.of<Message?>(
                            file("ﾐ", "content of tree file path/ﾐ"),
                            file("🔥", "content of tree file path/🔥")
                        ),
                        com.google.common.collect.ImmutableMap.of<String?, Directory?>()
                    ),
                    "srcs",
                    dir(
                        com.google.common.collect.ImmutableList.of<Message?>(),
                        com.google.common.collect.ImmutableMap.of<String?, Directory?>(
                            "system",
                            dir(
                                com.google.common.collect.ImmutableList.of<Message?>(
                                    file("foo.txt", "content of tree file srcs/system/foo.txt")
                                ),
                                com.google.common.collect.ImmutableMap.of<String?, Directory?>()
                            ),
                            "system-root",
                            dir(
                                com.google.common.collect.ImmutableList.of<Message?>(
                                    file("bar.txt", "content of tree file srcs/system-root/bar.txt")
                                ),
                                com.google.common.collect.ImmutableMap.of<String?, Directory?>()
                            )
                        )
                    )
                )
            )
        val runfilesDirectory: Directory =
            dir(
                com.google.common.collect.ImmutableList.of<Message?>(),
                com.google.common.collect.ImmutableMap.of<String?, Directory?>(
                    TestConstants.WORKSPACE_NAME,
                    dir(
                        com.google.common.collect.ImmutableList.of<Message?>(
                            file("root_file1", "content of root_file1"),
                            file("root_file2", "content of root_file2"),
                            file("root_file3", "content of root_file3")
                        ),
                        com.google.common.collect.ImmutableMap.of<String?, Directory?>(
                            "dir",
                            dir(
                                com.google.common.collect.ImmutableList.of<Message?>(
                                    file("file1", "content of dir/file1"),
                                    file("file2", "content of dir/file2"),
                                    file("file3", "content of dir/file3"),
                                    symlink("some_link", "some/target")
                                ),
                                com.google.common.collect.ImmutableMap.of<String?, Directory?>(
                                    "subdir",
                                    dir(
                                        com.google.common.collect.ImmutableList.of<Message?>(
                                            file("file1", "content of dir/subdir/file1"),
                                            file("file2", "content of dir/subdir/file2"),
                                            file("file3", "content of dir/subdir/file3")
                                        ),
                                        com.google.common.collect.ImmutableMap.of<String?, Directory?>(
                                            "subdir2",
                                            dir(
                                                com.google.common.collect.ImmutableList.of<Message?>(
                                                    file(
                                                        "file1", "content of dir/subdir/subdir2/file1"
                                                    ),
                                                    file(
                                                        "file2", "content of dir/subdir/subdir2/file2"
                                                    ),
                                                    file(
                                                        "file3",
                                                        "content of dir/subdir/subdir2/file3"
                                                    )
                                                ),
                                                com.google.common.collect.ImmutableMap.of<String?, Directory?>()
                                            ),
                                            "tree_artifact",
                                            treeArtifactDirectory
                                        )
                                    )
                                )
                            ),
                            "empty_dir",
                            emptyDirectory,
                            "path",
                            dir(
                                com.google.common.collect.ImmutableList.of<Message?>(
                                    file("ﾐ", "content of path/ﾐ"), file("🔥", "content of path/🔥")
                                ),
                                com.google.common.collect.ImmutableMap.of<String?, Directory?>()
                            ),
                            "srcs",
                            dir(
                                com.google.common.collect.ImmutableList.of<Message?>(),
                                com.google.common.collect.ImmutableMap.of<String?, Directory?>(
                                    "system",
                                    dir(
                                        com.google.common.collect.ImmutableList.of<Message?>(
                                            file("foo.txt", "content of srcs/system/foo.txt")
                                        ),
                                        com.google.common.collect.ImmutableMap.of<String?, Directory?>()
                                    ),
                                    "system-root",
                                    dir(
                                        com.google.common.collect.ImmutableList.of<Message?>(
                                            file("bar.txt", "content of srcs/system-root/bar.txt")
                                        ),
                                        com.google.common.collect.ImmutableMap.of<String?, Directory?>()
                                    )
                                )
                            )
                        )
                    )
                )
            )
        val dirDirectory: Directory =
            dir(
                com.google.common.collect.ImmutableList.of<Message?>(
                    file("file1", "content of dir/file1"),
                    file("file2", "content of dir/file2"),
                    file("file3", "content of dir/file3"),
                    symlink("some_link", "some/target")
                ),
                com.google.common.collect.ImmutableMap.of<String?, Directory?>(
                    "my_tool.runfiles",
                    runfilesDirectory,
                    "output_dir",
                    emptyDirectory,
                    "subdir",
                    dir(
                        com.google.common.collect.ImmutableList.of<Message?>(
                            file("file1", "content of dir/subdir/file1"),
                            file("file2", "content of dir/subdir/file2"),
                            file("file3", "content of dir/subdir/file3")
                        ),
                        com.google.common.collect.ImmutableMap.of<String?, Directory?>(
                            "subdir2",
                            dir(
                                com.google.common.collect.ImmutableList.of<Message?>(
                                    file("file1", "content of dir/subdir/subdir2/file1"),
                                    file("file2", "content of dir/subdir/subdir2/file2"),
                                    file("file3", "content of dir/subdir/subdir2/file3")
                                ),
                                com.google.common.collect.ImmutableMap.of<String?, Directory?>()
                            ),
                            "tree_artifact",
                            treeArtifactDirectory
                        )
                    )
                )
            )
        val rootDirectory: Directory =
            dir(
                com.google.common.collect.ImmutableList.of<Message?>(),
                com.google.common.collect.ImmutableMap.of<String?, Directory?>(
                    "outputs",
                    dir(
                        com.google.common.collect.ImmutableList.of<Message?>(
                            file("root_file1", "content of root_file1"),
                            file("root_file2", "content of root_file2"),
                            file("root_file3", "content of root_file3")
                        ),
                        com.google.common.collect.ImmutableMap.of<String?, Directory?>(
                            "dir",
                            dirDirectory,
                            "empty_dir",
                            emptyDirectory,
                            "path",
                            dir(
                                com.google.common.collect.ImmutableList.of<Message?>(
                                    file("ﾐ", "content of path/ﾐ"), file("🔥", "content of path/🔥")
                                ),
                                com.google.common.collect.ImmutableMap.of<String?, Directory?>()
                            ),
                            "srcs",
                            dir(
                                com.google.common.collect.ImmutableList.of<Message?>(),
                                com.google.common.collect.ImmutableMap.of<String?, Directory?>(
                                    "system",
                                    dir(
                                        com.google.common.collect.ImmutableList.of<Message?>(
                                            file("foo.txt", "content of srcs/system/foo.txt")
                                        ),
                                        com.google.common.collect.ImmutableMap.of<String?, Directory?>()
                                    ),
                                    "system-root",
                                    dir(
                                        com.google.common.collect.ImmutableList.of<Message?>(
                                            file("bar.txt", "content of srcs/system-root/bar.txt")
                                        ),
                                        com.google.common.collect.ImmutableMap.of<String?, Directory?>()
                                    )
                                )
                            )
                        )
                    ),
                    "src_dir",
                    dir(
                        com.google.common.collect.ImmutableList.of<Message?>(
                            file("file1", "content of src_dir/file1"),
                            file("file2", "content of src_dir/file2"),
                            file("file3", "content of src_dir/file3")
                        ),
                        com.google.common.collect.ImmutableMap.of<String?, Directory?>()
                    )
                )
            )

        val expectedDigest: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            DigestUtil.fromString(
                when (TestConstants.PRODUCT_NAME) {
                    "bazel" -> "ff6cfaefd3fe05996e6e06e818ff462b7e5632a730e48111a8643bbf58d6e01f/164"
                    "blaze" -> "53a0d960028fceda7b7f7108721f0d5fae190710c4e78fd7385851648958f220/164"
                    else -> throw java.lang.IllegalArgumentException(
                        "Unknown product name " + TestConstants.PRODUCT_NAME
                    )
                }
            )

        assertThat(digestUtil.compute(rootDirectory)).isEqualTo(expectedDigest)

        // Verify that multiple concurrent Merkle tree builds all produce the same result and don't
        // interfere with each other.
        val exceptions: ConcurrentLinkedDeque<Throwable> = ConcurrentLinkedDeque<Throwable>()
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            for (i in 0..15) {
                executor.execute(
                    java.lang.Runnable {
                        try {
                            assertThat(service.buildRemoteAction(spawn, context).getMerkleTree().digest())
                                .isEqualTo(expectedDigest)
                        } catch (e: Throwable) {
                            exceptions.add(e)
                        }
                    })
            }
        }
        if (!exceptions.isEmpty()) {
            val combinedException: java.lang.AssertionError =
                java.lang.AssertionError("Exceptions in golden test runs:")
            for (e in exceptions) {
                combinedException.addSuppressed(e)
            }
            throw combinedException
        }

        // Use JOL to assert on the retained size of an uploadable Merkle tree. These should use as
        // little memory as possible since they are kept in memory during the whole remote execution.
        // Memory usage isn't expected to differ by seed, so only check for one of them.
        if (seed == 1) {
            // JOL tracks objects by their native address, so run GC to minimize noise from moved objects.
            java.lang.System.gc()
            // Keep building a Merkle tree and compute its retained size relative to all previous trees
            // until the size stabilizes. The goal is to compute the size of the objects that are uniquely
            // retained by the tree, as this is the effective overhead at runtime when building many trees
            // in parallel. This is more delicate than it seems:
            // 1. This has to be done in a loop since objects retain their respective Class and JOL's use
            //    of reflection mutates the various caches in the Class objects in non-deterministic ways.
            // 2. Subtracting previous trees is only correct under the assumption that the objects shared
            //    with such trees would also be retained elsewhere (e.g. Artifact objects). If MerkleTree
            //    ever uses techniques such as interning or weak caches, this strategy would have to be
            //    revisited.
            var merkleTreeUniqueRetention: GraphLayout
            val previousRoots: java.util.ArrayList<Any?> = java.util.ArrayList<Any?>()
            var stableRetainedSize: Long = -1
            while (true) {
                val merkleTree: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    service
                        .buildRemoteAction(spawn, context, MerkleTreeComputer.BlobPolicy.KEEP)
                        .getMerkleTree()
                assertThat(merkleTree).isInstanceOf(Uploadable::class.java)
                merkleTreeUniqueRetention =
                    GraphLayout.parseInstance(merkleTree)
                        .subtract(GraphLayout.parseInstance(previousRoots))
                if (merkleTreeUniqueRetention.totalSize() == stableRetainedSize) {
                    break
                }
                stableRetainedSize = merkleTreeUniqueRetention.totalSize()
                previousRoots.add(merkleTree)
            }
            val footprintOut: Path =
                Paths.get(java.lang.System.getenv("TEST_UNDECLARED_OUTPUTS_DIR"), "merkle_tree_footprint.txt")
            java.nio.file.Files.writeString(footprintOut, merkleTreeUniqueRetention.toFootprint())
            // Detailed footprint:
            //     COUNT       AVG       SUM   DESCRIPTION
            //        18       181      3264   [B
            //         9        32       288   b.b.r.e.v2.Digest
            //         2       112       224   [Ljava.lang.Object;
            //         9        16       144   c.g.p.ByteString$LiteralByteString
            //         1        40        40   c.g.c.c.ImmutableSortedMap
            //         2        16        32   c.g.c.c.RegularImmutableList
            //         1        32        32   c.g.d.b.l.r.m.MerkleTree$RootOnly$BlobsUploaded
            //         1        24        24   c.g.c.c.RegularImmutableSortedSet
            //         1        16        16   c.g.d.b.l.r.m.MerkleTree$Uploadable
            //        44                4064   (total)
            //
            // Ignoring objects with constant count, the footprint is made up of:
            // * the two Object arrays backing the ImmutableSortedMap that tracks a map from digest-like
            //   object to their backing blob. Assuming that most of these objects are naturally retained
            //   elsehwere, as is the case for regular files (which are represented as their
            //   FileArtifactValue mapping to their Artifact), this representation is already optimal at
            //   8 bytes per blob.
            // * the Digest objects for non-regular file blobs, in particular Directory protos. These
            //   could be represented more efficiently by storing their raw hash bytes and the size in a
            //   a flat byte array, but savings aren't expected to be significant.
            // * most importantly, the serialized Directory protos, which contain inlined Digest protos
            //   as well as filenames for all files. This is where the largest gains can be made by
            //   introducing a custom representation that is serialized on demand when actually uploading
            //   to the remote. Such a representation could consist of a flat Object array containing
            //   FileArtifactValues (to replace Digest protos), Artifacts (to retrieve the
            //   basename for file nodes), and Integers (referencing intermediate segments of Artifact
            //   exec paths for most directory nodes).
            // TODO: Get this number down.
            Truth.assertThat(stableRetainedSize).isEqualTo(4064)
        }
    }

    private fun file(name: String?, content: String?): FileNode {
        return FileNode.newBuilder()
            .setName(name)
            .setDigest(digestUtil.computeAsUtf8(content))
            .setIsExecutable(true)
            .build()
    }

    private fun symlink(name: String?, target: String?): SymlinkNode {
        return SymlinkNode.newBuilder().setName(name).setTarget(target).build()
    }

    private fun dir(
        filesAndSymlinks: com.google.common.collect.ImmutableList<Message>,
        dirs: com.google.common.collect.ImmutableMap<String?, Directory?>
    ): Directory {
        val builder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Directory.newBuilder()
        for (entry in filesAndSymlinks) {
            when (entry) {
                -> builder.addFiles(fileNode)
                -> builder.addSymlinks(symlinkNode)
                else -> throw java.lang.IllegalArgumentException("Unsupported entry type: " + entry.getClass())
            }
        }
        dirs.forEach(
            java.util.function.BiConsumer { name: String?, dir: Directory? ->
                builder.addDirectories(
                    DirectoryNode.newBuilder().setName(name).setDigest(digestUtil.compute(dir))
                )
            })
        return builder.build()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_executableBitIgnored() {
        // Test that executable bit of downloaded output files are ignored since it will be chmod 555
        // after action execution.

        // arrange

        val fooDigest: Digest? = cache.addContents(remoteActionExecutionContext, "foo-contents")
        val barDigest: Digest? = cache.addContents(remoteActionExecutionContext, "bar-contents")
        val tree: Tree =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addFiles(
                            FileNode.newBuilder()
                                .setName("bar")
                                .setDigest(barDigest)
                                .setIsExecutable(true)
                        )
                )
                .build()
        val treeDigest: Digest? = cache.addContents(remoteActionExecutionContext, tree.toByteArray())
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputFilesBuilder().setPath("outputs/foo").setDigest(fooDigest)
        builder.addOutputDirectoriesBuilder().setPath("outputs/dir").setTreeDigest(treeDigest)
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        // act
        service.downloadOutputs(action, result)

        // assert
        assertThat(execRoot.getRelative("outputs/foo").isExecutable()).isFalse()
        assertThat(execRoot.getRelative("outputs/dir/bar").isExecutable()).isFalse()
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_siblingLayout() {
        // arrange
        remotePathResolver = SiblingRepositoryLayoutResolver(execRoot)

        val fooDigest: Digest? = cache.addContents(remoteActionExecutionContext, "foo-contents")
        val barDigest: Digest? = cache.addContents(remoteActionExecutionContext, "bar-contents")
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputFilesBuilder().setPath("outputs/foo").setDigest(fooDigest)
        builder.addOutputFilesBuilder().setPath("outputs/bar").setDigest(barDigest)
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        // act
        service.downloadOutputs(action, result)

        // assert
        assertThat(readContent(execRoot.getRelative("outputs/foo"), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(
            "foo-contents"
        )
        assertThat(readContent(execRoot.getRelative("outputs/bar"), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(
            "bar-contents"
        )
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_outputFiles() {
        // arrange
        val d1: Digest? = cache.addContents(remoteActionExecutionContext, "content1")
        val r: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file1").setDigest(d1))
                .build()

        val result: RemoteActionResult = RemoteActionResult.createFromCache(CachedActionResult.remote(r))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        // act
        val inMemoryOutput: InMemoryOutput? = service.downloadOutputs(action, result)

        // assert
        assertThat(inMemoryOutput).isNull()
        val actionFs: RemoteActionFileSystem? = context.getActionFileSystem()
        assertThat(actionFs.getDigest(execRoot.asFragment().getRelative("outputs/file1")))
            .isEqualTo(toBinaryDigest(d1))
        assertThat(
            readContent(
                execRoot.getRelative("outputs/file1"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("content1")
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_outputDirectories() {
        // Test that downloading an output directory works.

        // arrange

        val fooDigest: Digest? = cache.addContents(remoteActionExecutionContext, "foo-contents")
        val barDigest: Digest? = cache.addContents(remoteActionExecutionContext, "bar-contents")
        val tree: Tree =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addFiles(
                            FileNode.newBuilder()
                                .setName("foo")
                                .setDigest(fooDigest)
                                .setIsExecutable(true)
                        )
                        .addFiles(
                            FileNode.newBuilder()
                                .setName("subdir/bar")
                                .setDigest(barDigest)
                                .setIsExecutable(true)
                        )
                )
                .build()
        val treeDigest: Digest? = cache.addContents(remoteActionExecutionContext, tree.toByteArray())
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputDirectoriesBuilder().setPath("outputs/a/dir").setTreeDigest(treeDigest)
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        // act
        service.downloadOutputs(action, result)

        // assert
        val actionFs: RemoteActionFileSystem? = context.getActionFileSystem()
        assertThat(actionFs.getDigest(execRoot.asFragment().getRelative("outputs/a/dir/foo")))
            .isEqualTo(toBinaryDigest(fooDigest))
        assertThat(actionFs.getDigest(execRoot.asFragment().getRelative("outputs/a/dir/subdir/bar")))
            .isEqualTo(toBinaryDigest(barDigest))
        assertThat(readContent(execRoot.getRelative("outputs/a/dir/foo"), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("foo-contents")
        assertThat(
            readContent(
                execRoot.getRelative("outputs/a/dir/subdir/bar"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .isEqualTo("bar-contents")
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_emptyOutputDirectories_works() {
        // Test that downloading an empty output directory works.

        // arrange

        val barTreeMessage: Tree? = Tree.newBuilder().setRoot(Directory.getDefaultInstance()).build()
        // Don't add barTreeMessage to the cache, the Tree proto for an empty output directory is
        // recognized by its digest.
        val barTreeDigest: Digest? = digestUtil.compute(barTreeMessage)
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputDirectoriesBuilder().setPath("outputs/a/bar").setTreeDigest(barTreeDigest)
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        // act
        service.downloadOutputs(action, result)

        // assert
        assertThat(execRoot.getRelative("outputs/a/bar").isDirectory()).isTrue()
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_nestedOutputDirectories_works() {
        // Test that downloading a nested output directory works.

        // arrange

        val fooDigest: Digest? = cache.addContents(remoteActionExecutionContext, "foo-contents")
        val quxDigest: Digest? = cache.addContents(remoteActionExecutionContext, "qux-contents")
        val wobbleDirMessage: Directory =
            Directory.newBuilder()
                .addFiles(
                    FileNode.newBuilder().setName("qux").setDigest(quxDigest).setIsExecutable(true)
                )
                .build()
        val wobbleDirDigest: Digest? =
            cache.addContents(remoteActionExecutionContext, wobbleDirMessage.toByteArray())
        val barTreeMessage: Tree =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addFiles(
                            FileNode.newBuilder()
                                .setName("qux")
                                .setDigest(quxDigest)
                                .setIsExecutable(true)
                        )
                        .addDirectories(
                            DirectoryNode.newBuilder().setName("wobble").setDigest(wobbleDirDigest)
                        )
                )
                .addChildren(wobbleDirMessage)
                .build()
        val barTreeDigest: Digest? =
            cache.addContents(remoteActionExecutionContext, barTreeMessage.toByteArray())
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputFilesBuilder().setPath("outputs/a/foo").setDigest(fooDigest)
        builder.addOutputDirectoriesBuilder().setPath("outputs/a/bar").setTreeDigest(barTreeDigest)
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        // act
        service.downloadOutputs(action, result)

        // assert
        assertThat(
            readContent(
                execRoot.getRelative("outputs/a/foo"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("foo-contents")
        assertThat(
            readContent(
                execRoot.getRelative("outputs/a/bar/wobble/qux"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .isEqualTo("qux-contents")
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_outputDirectoriesWithNestedFile_works() {
        // Test that downloading an output directory containing a named output file works.

        // arrange

        val fooDigest: Digest? = cache.addContents(remoteActionExecutionContext, "foo-contents")
        val barDigest: Digest? = cache.addContents(remoteActionExecutionContext, "bar-contents")
        val subdirTreeMessage: Tree =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addFiles(
                            FileNode.newBuilder()
                                .setName("foo")
                                .setDigest(fooDigest)
                                .setIsExecutable(true)
                        )
                        .addFiles(
                            FileNode.newBuilder()
                                .setName("bar")
                                .setDigest(barDigest)
                                .setIsExecutable(true)
                        )
                )
                .build()
        val subdirTreeDigest: Digest? =
            cache.addContents(remoteActionExecutionContext, subdirTreeMessage.toByteArray())
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputFilesBuilder().setPath("outputs/subdir/foo").setDigest(fooDigest)
        builder.addOutputDirectoriesBuilder().setPath("outputs/subdir").setTreeDigest(subdirTreeDigest)
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        // act
        service.downloadOutputs(action, result)

        // assert
        assertThat(readContent(execRoot.getRelative("outputs/subdir/foo"), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("foo-contents")
        assertThat(readContent(execRoot.getRelative("outputs/subdir/bar"), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("bar-contents")
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_outputDirectoriesWithSameHash_works() {
        // Test that downloading an output directory works when two Directory
        // protos have the same hash i.e. because they have the same name and contents or are empty.

        /*
     * /bar/foo/file
     * /foo/file
     */

        // arrange

        val fileDigest: Digest? = cache.addContents(remoteActionExecutionContext, "file")
        val file: FileNode? =
            FileNode.newBuilder().setName("file").setDigest(fileDigest).setIsExecutable(true).build()
        val fooDir: Directory = Directory.newBuilder().addFiles(file).build()
        val fooDigest: Digest? = cache.addContents(remoteActionExecutionContext, fooDir.toByteArray())
        val fooDirNode: DirectoryNode? =
            DirectoryNode.newBuilder().setName("foo").setDigest(fooDigest).build()
        val barDir: Directory = Directory.newBuilder().addDirectories(fooDirNode).build()
        val barDigest: Digest? = cache.addContents(remoteActionExecutionContext, barDir.toByteArray())
        val barDirNode: DirectoryNode? =
            DirectoryNode.newBuilder().setName("bar").setDigest(barDigest).build()
        val rootDir: Directory? =
            Directory.newBuilder().addDirectories(fooDirNode).addDirectories(barDirNode).build()
        val tree: Tree =
            Tree.newBuilder()
                .setRoot(rootDir)
                .addChildren(barDir)
                .addChildren(fooDir)
                .addChildren(fooDir)
                .build()
        val treeDigest: Digest? = cache.addContents(remoteActionExecutionContext, tree.toByteArray())
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputDirectoriesBuilder().setPath("outputs/a").setTreeDigest(treeDigest)
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        // act
        service.downloadOutputs(action, result)

        // assert
        assertThat(readContent(execRoot.getRelative("outputs/a/bar/foo/file"), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("file")
        assertThat(
            readContent(
                execRoot.getRelative("outputs/a/foo/file"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("file")
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_outputDirectoriesWithUnicodeFilenames_works() {
        // arrange
        val internationalizationDigest: Digest? = cache.addContents(remoteActionExecutionContext, "hello")
        val tree: Tree =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addFiles(
                            FileNode.newBuilder()
                                .setName("Iñtërnâtiônàlizætiøn")
                                .setDigest(internationalizationDigest)
                        )
                        .addSymlinks(SymlinkNode.newBuilder().setName("東京都").setTarget("京都市"))
                )
                .build()
        val treeDigest: Digest? = cache.addContents(remoteActionExecutionContext, tree.toByteArray())
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputDirectoriesBuilder().setPath("outputs/dir").setTreeDigest(treeDigest)
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        // act
        service.downloadOutputs(action, result)

        // assert
        assertThat(
            readContent(
                execRoot.getRelative(unicodeToInternal("outputs/dir/Iñtërnâtiônàlizætiøn")),
                java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .isEqualTo("hello")
        assertThat(execRoot.getRelative(unicodeToInternal("outputs/dir/東京都")).readSymbolicLink())
            .isEqualTo(PathFragment.create(unicodeToInternal("京都市")))
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_relativeFileSymlink_success() {
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputFileSymlinksBuilder().setPath("outputs/a/b/link").setTarget("../../foo")
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)

        // Doesn't check for dangling links, hence download succeeds.
        service.downloadOutputs(action, result)

        val path: Path = execRoot.getRelative("outputs/a/b/link")
        assertThat(path.isSymbolicLink()).isTrue()
        assertThat(path.readSymbolicLink()).isEqualTo(PathFragment.create("../../foo"))
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_relativeDirectorySymlink_success() {
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputDirectorySymlinksBuilder().setPath("outputs/a/b/link").setTarget("foo")
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        // Doesn't check for dangling links, hence download succeeds.
        service.downloadOutputs(action, result)

        val path: Path = execRoot.getRelative("outputs/a/b/link")
        assertThat(path.isSymbolicLink()).isTrue()
        assertThat(path.readSymbolicLink()).isEqualTo(PathFragment.create("foo"))
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_relativeOutputSymlinks_success() {
        // Test that download outputs works when the action result only contains output_symlinks
        // and not output_file_symlinks or output_directory_symlinks, which are deprecated.
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputSymlinksBuilder().setPath("outputs/a/b/link").setTarget("../../foo")
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        // Doesn't check for dangling links, hence download succeeds.
        service.downloadOutputs(action, result)

        val path: Path = execRoot.getRelative("outputs/a/b/link")
        assertThat(path.isSymbolicLink()).isTrue()
        assertThat(path.readSymbolicLink()).isEqualTo(PathFragment.create("../../foo"))
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_outputSymlinksCompatibility_success() {
        // Test that download outputs works when the action result contains both output_symlinks
        // and output_file_symlinks (or output_directory_symlinks).
        //
        // Remote Execution Server may set both fields to ensure backward compatibility with
        // clients that don't support output_symlinks.
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputFileSymlinksBuilder().setPath("outputs/a/b/link").setTarget("foo")
        builder.addOutputSymlinksBuilder().setPath("outputs/a/b/link").setTarget("foo")
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        // Doesn't check for dangling links, hence download succeeds.
        service.downloadOutputs(action, result)

        val path: Path = execRoot.getRelative("outputs/a/b/link")
        assertThat(path.isSymbolicLink()).isTrue()
        assertThat(path.readSymbolicLink()).isEqualTo(PathFragment.create("foo"))
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_symlinksInDirectory_success() {
        val tree: Tree =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addSymlinks(SymlinkNode.newBuilder().setName("rel").setTarget("foo"))
                        .addSymlinks(SymlinkNode.newBuilder().setName("abs").setTarget("/bar"))
                )
                .build()
        val treeDigest: Digest? = cache.addContents(remoteActionExecutionContext, tree.toByteArray())
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputDirectoriesBuilder().setPath("outputs/dir").setTreeDigest(treeDigest)
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService(remoteOptions)
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        // Doesn't check for dangling links, hence download succeeds.
        service.downloadOutputs(action, result)

        val relPath: Path = execRoot.getRelative("outputs/dir/rel")
        assertThat(relPath.isSymbolicLink()).isTrue()
        assertThat(relPath.readSymbolicLink()).isEqualTo(PathFragment.create("foo"))
        val absPath: Path = execRoot.getRelative("outputs/dir/abs")
        assertThat(absPath.isSymbolicLink()).isTrue()
        assertThat(absPath.readSymbolicLink()).isEqualTo(PathFragment.create("/bar"))
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_absoluteFileSymlink_success() {
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputFileSymlinksBuilder().setPath("outputs/foo").setTarget("/abs/link")
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        service.downloadOutputs(action, result)

        val path: Path = execRoot.getRelative("outputs/foo")
        assertThat(path.isSymbolicLink()).isTrue()
        assertThat(path.readSymbolicLink()).isEqualTo(PathFragment.create("/abs/link"))
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_absoluteDirectorySymlink_success() {
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputDirectorySymlinksBuilder().setPath("outputs/foo").setTarget("/abs/link")
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        service.downloadOutputs(action, result)

        val path: Path = execRoot.getRelative("outputs/foo")
        assertThat(path.readSymbolicLink()).isEqualTo(PathFragment.create("/abs/link"))
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_symlinkCollision_error() {
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputDirectorySymlinksBuilder().setPath("outputs/foo").setTarget("foo1")
        builder.addOutputSymlinksBuilder().setPath("outputs/foo").setTarget("foo2")
        val result: RemoteActionResult? =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn =
            SpawnBuilder("dummy")
                .withOutput(
                    ActionsTestUtil.createArtifactWithRootRelativePath(
                        artifactRoot, PathFragment.create("foo")
                    )
                )
                .build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        val expected: IOException =
            org.junit.Assert.assertThrows<IOException>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { service.downloadOutputs(action, result) })

        Truth.assertThat<Throwable?>(expected.getSuppressed()).isEmpty()
        Truth.assertThat(expected).hasMessageThat().contains("Symlink path collision")
        Truth.assertThat(expected).hasMessageThat().contains("outputs/foo")
        Truth.assertThat(expected).hasMessageThat().contains("foo1")
        Truth.assertThat(expected).hasMessageThat().contains("foo2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_onActionFailure_downloadEverything() {
        // Test that all outputs are downloaded for a failed action, even if the outputs mode says
        // otherwise.

        // arrange

        val fooDigest: Digest? = cache.addContents(remoteActionExecutionContext, "foo-contents")
        val barDigest: Digest? = cache.addContents(remoteActionExecutionContext, "bar-contents")
        val tree: Tree =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addFiles(
                            FileNode.newBuilder()
                                .setName("bar")
                                .setDigest(barDigest)
                                .setIsExecutable(true)
                        )
                )
                .build()
        val treeDigest: Digest? = cache.addContents(remoteActionExecutionContext, tree.toByteArray())
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputFilesBuilder().setPath("outputs/foo").setDigest(fooDigest)
        builder.addOutputDirectoriesBuilder().setPath("outputs/dir").setTreeDigest(treeDigest)
        builder.setExitCode(1)
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)

        // act
        service.downloadOutputs(action, result)

        // assert
        assertThat(readContent(execRoot.getRelative("outputs/foo"), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(
            "foo-contents"
        )
        assertThat(readContent(execRoot.getRelative("outputs/dir/bar"), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("bar-contents")
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_onDownloadFailure_maintainDirectories() {
        // Test that output directories created prior to spawn execution are not deleted on failure.
        val treeFileDigest: Digest? =
            cache.addException("outputs/outputdir/outputfile", IOException("download failed"))
        val tree: Tree =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addFiles(
                            FileNode.newBuilder()
                                .setName("outputfile")
                                .setDigest(treeFileDigest)
                                .setIsExecutable(true)
                        )
                )
                .build()
        val treeDigest: Digest? = cache.addContents(remoteActionExecutionContext, tree.toByteArray())
        val otherFileDigest: Digest? =
            cache.addException("outputs/otherdir/otherfile", IOException("download failed"))
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputDirectoriesBuilder().setPath("outputs/outputdir").setTreeDigest(treeDigest)
        builder.addOutputFiles(
            OutputFile.newBuilder().setPath("outputs/otherdir/otherfile").setDigest(otherFileDigest)
        )
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        org.junit.Assert.assertThrows<T?>(
            BulkTransferException::class.java,
            org.junit.function.ThrowingRunnable { service.downloadOutputs(action, result) })

        Truth.assertThat(cache.getNumFailedDownloads()).isEqualTo(2)
        assertThat(execRoot.getRelative("outputs/outputdir").exists()).isTrue()
        assertThat(execRoot.getRelative("outputs/outputdir/outputfile").exists()).isFalse()
        assertThat(execRoot.getRelative("outputs/otherdir").exists()).isTrue()
        assertThat(execRoot.getRelative("outputs/otherdir/otherfile").exists()).isFalse()
        Truth.assertThat(context.isLockOutputFilesCalled()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_onError_waitForRemainingDownloadsToComplete() {
        // If one or more downloads of output files / directories fail then the code should
        // wait for all downloads to have been completed before it tries to clean up partially
        // downloaded files.
        val digest1: Digest? = cache.addContents(remoteActionExecutionContext, "file1")
        val digest2: Digest? = cache.addException("file2", IOException("download failed"))
        val digest3: Digest? = cache.addContents(remoteActionExecutionContext, "file3")
        val actionResult: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file1").setDigest(digest1))
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file2").setDigest(digest2))
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file3").setDigest(digest3))
                .build()
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(actionResult))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        val downloadException: BulkTransferException =
            org.junit.Assert.assertThrows<T>(
                BulkTransferException::class.java,
                org.junit.function.ThrowingRunnable { service.downloadOutputs(action, result) })

        assertThat(downloadException.getSuppressed()).hasLength(1)
        Truth.assertThat(cache.getNumSuccessfulDownloads()).isEqualTo(2)
        Truth.assertThat(cache.getNumFailedDownloads()).isEqualTo(1)
        assertThat(downloadException.getSuppressed()[0]).isInstanceOf(IOException::class.java)
        val e: IOException = downloadException.getSuppressed()[0] as IOException
        Truth.assertThat(com.google.common.base.Throwables.getRootCause(e)).hasMessageThat()
            .isEqualTo("download failed")
        Truth.assertThat(context.isLockOutputFilesCalled()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_withMultipleErrors_addsThemAsSuppressed() {
        val digest1: Digest? = cache.addContents(remoteActionExecutionContext, "file1")
        val digest2: Digest? = cache.addException("file2", IOException("file2 failed"))
        val digest3: Digest? = cache.addException("file3", IOException("file3 failed"))
        val actionResult: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file1").setDigest(digest1))
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file2").setDigest(digest2))
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file3").setDigest(digest3))
                .build()
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(actionResult))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        val e: BulkTransferException =
            org.junit.Assert.assertThrows<T>(
                BulkTransferException::class.java,
                org.junit.function.ThrowingRunnable { service.downloadOutputs(action, result) })

        assertThat(e.getSuppressed()).hasLength(2)
        assertThat(e.getSuppressed()[0]).isInstanceOf(IOException::class.java)
        assertThat(e.getSuppressed()[0]).hasMessageThat().isAnyOf("file2 failed", "file3 failed")
        assertThat(e.getSuppressed()[1]).isInstanceOf(IOException::class.java)
        assertThat(e.getSuppressed()[1]).hasMessageThat().isAnyOf("file2 failed", "file3 failed")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_withDuplicateIOErrors_doesNotSuppress() {
        val digest1: Digest? = cache.addContents(remoteActionExecutionContext, "file1")
        val reusedException: IOException = IOException("reused io exception")
        val digest2: Digest? = cache.addException("file2", reusedException)
        val digest3: Digest? = cache.addException("file3", reusedException)
        val actionResult: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file1").setDigest(digest1))
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file2").setDigest(digest2))
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file3").setDigest(digest3))
                .build()
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(actionResult))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        val downloadException: BulkTransferException =
            org.junit.Assert.assertThrows<T>(
                BulkTransferException::class.java,
                org.junit.function.ThrowingRunnable { service.downloadOutputs(action, result) })

        for (t in downloadException.getSuppressed()) {
            Truth.assertThat(t).isInstanceOf(IOException::class.java)
            val e: IOException = t as IOException
            Truth.assertThat(com.google.common.base.Throwables.getRootCause(e)).hasMessageThat()
                .isEqualTo("reused io exception")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_withDuplicateInterruptions_doesNotSuppress() {
        val digest1: Digest? = cache.addContents(remoteActionExecutionContext, "file1")
        val reusedInterruption: java.lang.InterruptedException = java.lang.InterruptedException("reused interruption")
        val digest2: Digest? = cache.addException("file2", reusedInterruption)
        val digest3: Digest? = cache.addException("file3", reusedInterruption)
        val actionResult: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file1").setDigest(digest1))
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file2").setDigest(digest2))
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file3").setDigest(digest3))
                .build()
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(actionResult))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        val e: java.lang.InterruptedException =
            org.junit.Assert.assertThrows<java.lang.InterruptedException>(
                java.lang.InterruptedException::class.java,
                org.junit.function.ThrowingRunnable { service.downloadOutputs(action, result) })

        Truth.assertThat<Throwable?>(e.getSuppressed()).isEmpty()
        Truth.assertThat(com.google.common.base.Throwables.getRootCause(e)).hasMessageThat()
            .isEqualTo("reused interruption")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_withStdoutStderrOnSuccess_writable() {
        // Tests that fetching stdout/stderr as a digest works and that OutErr is still
        // writable afterwards.
        val childOutErr: FileOutErr? = outErr.childOutErr()
        val spyOutErr: FileOutErr = spy(outErr)
        val spyChildOutErr: FileOutErr? = spy(childOutErr)
        Mockito.`when`<T?>(spyOutErr.childOutErr()).thenReturn(spyChildOutErr)
        val digestStdout: Digest? = cache.addContents(remoteActionExecutionContext, "stdout")
        val digestStderr: Digest? = cache.addContents(remoteActionExecutionContext, "stderr")
        val actionResult: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .setStdoutDigest(digestStdout)
                .setStderrDigest(digestStderr)
                .build()
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(actionResult))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn, spyOutErr)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        service.downloadOutputs(action, result)

        Mockito.verify<Any?>(spyOutErr, Mockito.times(2)).childOutErr()
        Mockito.verify<Any?>(spyChildOutErr).clearOut()
        Mockito.verify<Any?>(spyChildOutErr).clearErr()
        assertThat(outErr.getOutputPath().exists()).isTrue()
        assertThat(outErr.getErrorPath().exists()).isTrue()
        try {
            outErr.getOutputStream().write(0)
            outErr.getErrorStream().write(0)
        } catch (err: IOException) {
            throw java.lang.AssertionError("outErr should still be writable after download finished.", err)
        }
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_withStdoutStderrOnFailure_writableAndEmpty() {
        // Test that when downloading stdout/stderr fails the OutErr is still writable
        // and empty.
        val childOutErr: FileOutErr? = outErr.childOutErr()
        val spyOutErr: FileOutErr = spy(outErr)
        val spyChildOutErr: FileOutErr? = spy(childOutErr)
        Mockito.`when`<T?>(spyOutErr.childOutErr()).thenReturn(spyChildOutErr)
        // Don't add stdout/stderr as a known blob to the remote cache so that downloading it will fail
        val digestStdout: Digest? = digestUtil.computeAsUtf8("stdout")
        val digestStderr: Digest? = digestUtil.computeAsUtf8("stderr")
        val actionResult: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .setStdoutDigest(digestStdout)
                .setStderrDigest(digestStderr)
                .build()
        val result: RemoteActionResult =
            RemoteActionResult.createFromCache(CachedActionResult.remote(actionResult))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn, spyOutErr)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        org.junit.Assert.assertThrows<T?>(
            BulkTransferException::class.java,
            org.junit.function.ThrowingRunnable { service.downloadOutputs(action, result) })

        Mockito.verify<Any?>(spyOutErr, Mockito.times(2)).childOutErr()
        Mockito.verify<Any?>(spyChildOutErr).clearOut()
        Mockito.verify<Any?>(spyChildOutErr).clearErr()
        assertThat(outErr.getOutputPath().exists()).isFalse()
        assertThat(outErr.getErrorPath().exists()).isFalse()
        try {
            outErr.getOutputStream().write(0)
            outErr.getErrorStream().write(0)
        } catch (err: IOException) {
            throw java.lang.AssertionError("outErr should still be writable after download failed.", err)
        }
        Truth.assertThat(context.isLockOutputFilesCalled()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_outputNameClashesWithTempName_success() {
        val d1: Digest? = cache.addContents(remoteActionExecutionContext, "content1")
        val d2: Digest? = cache.addContents(remoteActionExecutionContext, "content2")
        val r: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/foo.tmp").setDigest(d1))
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/foo").setDigest(d2))
                .build()
        val result: RemoteActionResult = RemoteActionResult.createFromCache(CachedActionResult.remote(r))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                ArgumentMatchers.any<PathFragment?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(true)

        service.downloadOutputs(action, result)

        assertThat(
            readContent(
                execRoot.getRelative("outputs/foo.tmp"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("content1")
        assertThat(readContent(execRoot.getRelative("outputs/foo"), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(
            "content2"
        )
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_outputFiles_partialDownload() {
        // arrange
        val d1: Digest? = cache.addContents(remoteActionExecutionContext, "content1")
        val d2: Digest? = cache.addContents(remoteActionExecutionContext, "content2")
        val r: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file1").setDigest(d1))
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file2").setDigest(d2))
                .build()

        val result: RemoteActionResult = RemoteActionResult.createFromCache(CachedActionResult.remote(r))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                PathFragment.create("outputs/file1"),  /* treeRootExecPath= */null
            )
        )
            .thenReturn(true)

        // act
        val inMemoryOutput: InMemoryOutput? = service.downloadOutputs(action, result)

        // assert
        assertThat(inMemoryOutput).isNull()
        val actionFs: RemoteActionFileSystem? = context.getActionFileSystem()
        assertThat(actionFs.getDigest(execRoot.asFragment().getRelative("outputs/file1")))
            .isEqualTo(toBinaryDigest(d1))
        assertThat(actionFs.getDigest(execRoot.asFragment().getRelative("outputs/file2")))
            .isEqualTo(toBinaryDigest(d2))
        assertThat(execRoot.getRelative("outputs/file1").exists()).isTrue()
        assertThat(execRoot.getRelative("outputs/file2").exists()).isFalse()
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_outputFiles_noDownload() {
        // arrange
        val d1: Digest? = cache.addContents(remoteActionExecutionContext, "content1")
        val d2: Digest? = cache.addContents(remoteActionExecutionContext, "content2")
        val r: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file1").setDigest(d1))
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file2").setDigest(d2))
                .build()

        val result: RemoteActionResult = RemoteActionResult.createFromCache(CachedActionResult.remote(r))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)

        // act
        val inMemoryOutput: InMemoryOutput? = service.downloadOutputs(action, result)

        // assert
        assertThat(inMemoryOutput).isNull()
        val actionFs: RemoteActionFileSystem? = context.getActionFileSystem()
        assertThat(actionFs.getDigest(execRoot.asFragment().getRelative("outputs/file1")))
            .isEqualTo(toBinaryDigest(d1))
        assertThat(actionFs.getDigest(execRoot.asFragment().getRelative("outputs/file2")))
            .isEqualTo(toBinaryDigest(d2))
        assertThat(execRoot.getRelative("outputs/file1").exists()).isFalse()
        assertThat(execRoot.getRelative("outputs/file2").exists()).isFalse()
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_outputDirectories_partialDownload() {
        // arrange

        // Output Directory:
        // dir/file1
        // dir/a/file2

        val d1: Digest? = cache.addContents(remoteActionExecutionContext, "content1")
        val d2: Digest? = cache.addContents(remoteActionExecutionContext, "content2")
        val file1: FileNode? =
            FileNode.newBuilder().setName("file1").setDigest(d1).setIsExecutable(true).build()
        val file2: FileNode? =
            FileNode.newBuilder().setName("file2").setDigest(d2).setIsExecutable(true).build()
        val a: Directory? = Directory.newBuilder().addFiles(file2).build()
        val da: Digest? = cache.addContents(remoteActionExecutionContext, a)
        val root: Directory? =
            Directory.newBuilder()
                .addFiles(file1)
                .addDirectories(DirectoryNode.newBuilder().setName("a").setDigest(da))
                .build()
        val t: Tree? = Tree.newBuilder().setRoot(root).addChildren(a).build()
        val dt: Digest? = cache.addContents(remoteActionExecutionContext, t)
        val r: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .addOutputDirectories(
                    OutputDirectory.newBuilder().setPath("outputs/dir").setTreeDigest(dt)
                )
                .build()
        val result: RemoteActionResult = RemoteActionResult.createFromCache(CachedActionResult.remote(r))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                PathFragment.create("outputs/dir/file1"), PathFragment.create("outputs/dir")
            )
        )
            .thenReturn(true)

        // act
        val inMemoryOutput: InMemoryOutput? = service.downloadOutputs(action, result)

        // assert
        assertThat(inMemoryOutput).isNull()
        val actionFs: RemoteActionFileSystem? = context.getActionFileSystem()
        assertThat(actionFs.getDigest(execRoot.asFragment().getRelative("outputs/dir/file1")))
            .isEqualTo(toBinaryDigest(d1))
        assertThat(actionFs.getDigest(execRoot.asFragment().getRelative("outputs/dir/a/file2")))
            .isEqualTo(toBinaryDigest(d2))
        assertThat(execRoot.getRelative("outputs/dir/file1").exists()).isTrue()
        assertThat(execRoot.getRelative("outputs/dir/a").exists()).isFalse()
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_outputDirectories_noDownload() {
        // arrange

        // Output Directory:
        // dir/file1
        // dir/a/file2

        val d1: Digest? = cache.addContents(remoteActionExecutionContext, "content1")
        val d2: Digest? = cache.addContents(remoteActionExecutionContext, "content2")
        val file1: FileNode? =
            FileNode.newBuilder().setName("file1").setDigest(d1).setIsExecutable(true).build()
        val file2: FileNode? =
            FileNode.newBuilder().setName("file2").setDigest(d2).setIsExecutable(true).build()
        val a: Directory? = Directory.newBuilder().addFiles(file2).build()
        val da: Digest? = cache.addContents(remoteActionExecutionContext, a)
        val root: Directory? =
            Directory.newBuilder()
                .addFiles(file1)
                .addDirectories(DirectoryNode.newBuilder().setName("a").setDigest(da))
                .build()
        val t: Tree? = Tree.newBuilder().setRoot(root).addChildren(a).build()
        val dt: Digest? = cache.addContents(remoteActionExecutionContext, t)
        val r: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .addOutputDirectories(
                    OutputDirectory.newBuilder().setPath("outputs/dir").setTreeDigest(dt)
                )
                .build()

        val result: RemoteActionResult = RemoteActionResult.createFromCache(CachedActionResult.remote(r))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)

        // act
        val inMemoryOutput: InMemoryOutput? = service.downloadOutputs(action, result)

        // assert
        assertThat(inMemoryOutput).isNull()
        val actionFs: RemoteActionFileSystem? = context.getActionFileSystem()
        assertThat(actionFs.getDigest(execRoot.asFragment().getRelative("outputs/dir/file1")))
            .isEqualTo(toBinaryDigest(d1))
        assertThat(actionFs.getDigest(execRoot.asFragment().getRelative("outputs/dir/a/file2")))
            .isEqualTo(toBinaryDigest(d2))
        assertThat(execRoot.getRelative("outputs/dir/file1").exists()).isFalse()
        assertThat(execRoot.getRelative("outputs/dir/a").exists()).isFalse()
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_outputDirectories_doNotDownload_failProperly() {
        // Test that we properly fail when downloading the metadata of an output
        // directory fails

        // arrange

        // Output Directory:
        // dir/file1
        // dir/a/file2

        val d1: Digest? = cache.addContents(remoteActionExecutionContext, "content1")
        val d2: Digest? = cache.addContents(remoteActionExecutionContext, "content2")
        val file1: FileNode? =
            FileNode.newBuilder().setName("file1").setDigest(d1).setIsExecutable(true).build()
        val file2: FileNode? =
            FileNode.newBuilder().setName("file2").setDigest(d2).setIsExecutable(true).build()
        val a: Directory? = Directory.newBuilder().addFiles(file2).build()
        val da: Digest? = cache.addContents(remoteActionExecutionContext, a)
        val root: Directory? =
            Directory.newBuilder()
                .addFiles(file1)
                .addDirectories(DirectoryNode.newBuilder().setName("a").setDigest(da))
                .build()
        val t: Tree? = Tree.newBuilder().setRoot(root).addChildren(a).build()
        // Downloading the tree will fail
        val downloadTreeException: IOException = IOException("entry not found")
        val dt: Digest? = cache.addException(t, downloadTreeException)
        val r: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .addOutputDirectories(
                    OutputDirectory.newBuilder().setPath("outputs/dir").setTreeDigest(dt)
                )
                .build()

        val result: RemoteActionResult = RemoteActionResult.createFromCache(CachedActionResult.remote(r))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)

        // act
        val e: BulkTransferException =
            org.junit.Assert.assertThrows<T>(
                BulkTransferException::class.java,
                org.junit.function.ThrowingRunnable { service.downloadOutputs(action, result) })

        // assert
        assertThat(e.getSuppressed()).hasLength(1)
        assertThat(e.getSuppressed()[0]).isEqualTo(downloadTreeException)
        Truth.assertThat(context.isLockOutputFilesCalled()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_nonInlinedStdoutAndStderr_alwaysDownload() {
        // arrange
        val dOut: Digest? = cache.addContents(remoteActionExecutionContext, "stdout")
        val dErr: Digest? = cache.addContents(remoteActionExecutionContext, "stderr")
        val r: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .setStdoutDigest(dOut)
                .setStderrDigest(dErr)
                .build()

        val result: RemoteActionResult = RemoteActionResult.createFromCache(CachedActionResult.remote(r))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)

        // act
        val inMemoryOutput: InMemoryOutput? = service.downloadOutputs(action, result)

        // assert
        assertThat(inMemoryOutput).isNull()
        val actionFs: RemoteActionFileSystem? = context.getActionFileSystem()
        assertThat(actionFs.getDigest(outErr.getOutputPathFragment())).isEqualTo(toBinaryDigest(dOut))
        assertThat(actionFs.getDigest(outErr.getErrorPathFragment())).isEqualTo(toBinaryDigest(dErr))
        assertThat(outErr.outAsLatin1()).isEqualTo("stdout")
        assertThat(outErr.errAsLatin1()).isEqualTo("stderr")
        val outputBase: Path = com.google.common.base.Preconditions.checkNotNull<T>(artifactRoot.getRoot().asPath())
        assertThat(outputBase.readdir(Symlinks.NOFOLLOW)).isEmpty()
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_inlinedStdoutAndStderr_alwaysDownload() {
        // arrange
        val dOut: Digest? = digestUtil.compute("stdout".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val dErr: Digest? = digestUtil.compute("stderr".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val r: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .setStdoutRaw(ByteString.copyFromUtf8("stdout"))
                .setStderrRaw(ByteString.copyFromUtf8("stderr"))
                .build()

        val result: RemoteActionResult = RemoteActionResult.createFromCache(CachedActionResult.remote(r))
        val spawn: Spawn = newSpawnFromResult(result)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)

        // act
        val inMemoryOutput: InMemoryOutput? = service.downloadOutputs(action, result)

        // assert
        assertThat(inMemoryOutput).isNull()
        val actionFs: RemoteActionFileSystem? = context.getActionFileSystem()
        assertThat(actionFs.getDigest(outErr.getOutputPathFragment())).isEqualTo(toBinaryDigest(dOut))
        assertThat(actionFs.getDigest(outErr.getErrorPathFragment())).isEqualTo(toBinaryDigest(dErr))
        assertThat(inMemoryOutput).isNull()
        assertThat(outErr.outAsLatin1()).isEqualTo("stdout")
        assertThat(outErr.errAsLatin1()).isEqualTo("stderr")
        val outputBase: Path = com.google.common.base.Preconditions.checkNotNull<T>(artifactRoot.getRoot().asPath())
        assertThat(outputBase.readdir(Symlinks.NOFOLLOW)).isEmpty()
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_inMemoryOutput_doNotDownload() {
        // Test that downloading an in memory output works

        // arrange

        val d1: Digest? = cache.addContents(remoteActionExecutionContext, "content1")
        val d2: Digest? = cache.addContents(remoteActionExecutionContext, "content2")
        val r: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file1").setDigest(d1))
                .addOutputFiles(OutputFile.newBuilder().setPath("outputs/file2").setDigest(d2))
                .build()

        val result: RemoteActionResult = RemoteActionResult.createFromCache(CachedActionResult.remote(r))
        // a1 should be provided as an InMemoryOutput
        val inMemoryOutputPathFragment: PathFragment = PathFragment.create("outputs/file1")
        val spawn: Spawn = newSpawnFromResultWithInMemoryOutput(result, inMemoryOutputPathFragment)
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)

        // act
        val inMemoryOutput: InMemoryOutput = service.downloadOutputs(action, result)

        // assert
        assertThat(inMemoryOutput).isNotNull()
        val expectedContents: ByteString = ByteString.copyFrom("content1", java.nio.charset.StandardCharsets.UTF_8)
        assertThat(inMemoryOutput.getContents()).isEqualTo(expectedContents)
        assertThat(inMemoryOutput.getOutput())
            .isEqualTo(ActionsTestUtil.createArtifact(artifactRoot, "file1"))
        val actionFs: RemoteActionFileSystem? = context.getActionFileSystem()
        assertThat(actionFs.getDigest(execRoot.asFragment().getRelative("outputs/file1")))
            .isEqualTo(toBinaryDigest(d1))
        assertThat(actionFs.getDigest(execRoot.asFragment().getRelative("outputs/file2")))
            .isEqualTo(toBinaryDigest(d2))
        assertThat(execRoot.getRelative("outputs/file1").exists()).isFalse()
        assertThat(execRoot.getRelative("outputs/file2").exists()).isFalse()
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_missingInMemoryOutput_returnsNull() {
        // Test that downloadOutputs returns null if a declared in-memory output is missing from action
        // result.

        // arrange

        val r: ActionResult? = ActionResult.newBuilder().setExitCode(0).build()
        val result: RemoteActionResult? = RemoteActionResult.createFromCache(CachedActionResult.remote(r))
        val a1: Artifact = ActionsTestUtil.createArtifact(artifactRoot, "file1")
        // set file1 as declared output but not mandatory output
        val spawn: Spawn =
            SimpleSpawn(
                FakeOwner("foo", "bar", "//dummy:label"),  /* arguments= */
                com.google.common.collect.ImmutableList.of<E?>(),  /* environment= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* executionInfo= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    REMOTE_EXECUTION_INLINE_OUTPUTS,
                    "outputs/file1"
                ),  /* inputs= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* tools= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(a1),  /* mandatoryOutputs= */
                com.google.common.collect.ImmutableSet.of<E?>(),
                ResourceSet.ZERO
            )

        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)

        // act
        val inMemoryOutput: InMemoryOutput? = service.downloadOutputs(action, result)

        // assert
        assertThat(inMemoryOutput).isNull()
        // The in memory file metadata also should not have been injected.
        val actionFs: RemoteActionFileSystem? = context.getActionFileSystem()
        assertThat(actionFs.exists(execRoot.asFragment().getRelative(a1.getExecPath()))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_missingMandatoryOutputs_reportError() {
        // Test that an AC which misses mandatory outputs is correctly ignored.
        val fooDigest: Digest? = cache.addContents(remoteActionExecutionContext, "foo-contents")
        val builder: ActionResult.Builder = ActionResult.newBuilder()
        builder.addOutputFilesBuilder().setPath("outputs/foo").setDigest(fooDigest)
        val result: RemoteActionResult? =
            RemoteActionResult.createFromCache(CachedActionResult.remote(builder.build()))
        val outputs: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
            com.google.common.collect.ImmutableSet.builder<Artifact?>()
        val expectedOutputFiles: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("outputs/foo", "outputs/bar")
        for (outputFile in expectedOutputFiles) {
            val path: Path? = remotePathResolver.outputPathToLocalPath(unicodeToInternal(outputFile))
            val output: Artifact? = ActionsTestUtil.createArtifact(artifactRoot, path)
            outputs.add(output)
        }
        val spawn: Spawn = newSpawn(com.google.common.collect.ImmutableMap.of<String?, String?>(), outputs.build())
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        createOutputDirectories(spawn)

        val error: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { service.downloadOutputs(action, result) })

        Truth.assertThat(error).hasMessageThat().containsMatch("mandatory output .+ was not created")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputs_pathUnmapped() {
        // Test that the output of a remote action with path mapping applied is downloaded into the
        // correct unmapped local path.
        val d1: Digest? = cache.addContents(remoteActionExecutionContext, "content1")
        val d2: Digest? = cache.addContents(remoteActionExecutionContext, "content2")
        val output1: Artifact = ActionsTestUtil.createArtifact(artifactRoot, "bin/config/dir/output1")
        val output2: Artifact = ActionsTestUtil.createArtifact(artifactRoot, "bin/other_dir/output2")
        val r: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0) // The action result includes the mapped paths.
                .addOutputFiles(
                    OutputFile.newBuilder().setPath("outputs/bin/dir/output1").setDigest(d1)
                )
                .addOutputFiles(
                    OutputFile.newBuilder().setPath("outputs/bin/other_dir/output2").setDigest(d2)
                )
                .build()
        val pathMapper: PathMapper =
            PathMapper { execPath -> PathFragment.create(execPath.getPathString().replaceAll("config/", "")) }
        val spawn: Spawn =
            SpawnBuilder("unused")
                .withOutput(output1)
                .withOutput(output2)
                .setPathMapper(pathMapper)
                .build()
        val result: RemoteActionResult? = RemoteActionResult.createFromCache(CachedActionResult.remote(r))
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                output1.getExecPath(),  /* treeRootExecPath= */null
            )
        )
            .thenReturn(true)
        Mockito.`when`<T?>(
            remoteOutputChecker.shouldDownloadOutput(
                output2.getExecPath(),  /* treeRootExecPath= */null
            )
        )
            .thenReturn(true)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)

        val inMemoryOutput: InMemoryOutput? = service.downloadOutputs(action, result)

        assertThat(inMemoryOutput).isNull()
        val actionFs: RemoteActionFileSystem? = context.getActionFileSystem()
        assertThat(actionFs.getDigest(output1.getPath().asFragment())).isEqualTo(toBinaryDigest(d1))
        assertThat(readContent(output1.getPath(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("content1")
        assertThat(actionFs.getDigest(output2.getPath().asFragment())).isEqualTo(toBinaryDigest(d2))
        assertThat(readContent(output2.getPath(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("content2")
        Truth.assertThat(context.isLockOutputFilesCalled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadOutputs_uploadDirectory_works() {
        // Test that uploading a directory works.

        // arrange

        val fooDigest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("outputs/a/foo"), "xyz")
        val quxDigest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("outputs/bar/qux"), "abc")
        val barDigest: Digest =
            fakeFileCache.createScratchInputDirectory(
                ActionInputHelper.fromPath("outputs/bar"),
                Tree.newBuilder()
                    .setRoot(
                        Directory.newBuilder()
                            .addFiles(
                                FileNode.newBuilder()
                                    .setName("qux")
                                    .setDigest(quxDigest)
                                    .setIsExecutable(true)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
        val fooFile: Path? = execRoot.getRelative("outputs/a/foo")
        val quxFile: Path = execRoot.getRelative("outputs/bar/qux")
        quxFile.setExecutable(true)
        val barDir: Path = execRoot.getRelative("outputs/bar")
        val outputFile: Artifact = ActionsTestUtil.createArtifact(artifactRoot, fooFile)
        val outputDirectory: Artifact? =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(
                artifactRoot, barDir.relativeTo(execRoot)
            )
        val service: RemoteExecutionService = newRemoteExecutionService()
        val spawn: Spawn = newSpawn(
            com.google.common.collect.ImmutableMap.of<String?, String?>(),
            com.google.common.collect.ImmutableSet.of<Artifact?>(outputFile, outputDirectory)
        )
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        val spawnResult: SpawnResult? =
            Builder()
                .setExitCode(0)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()

        // act
        val manifest: UploadManifest = service.buildUploadManifest(action, spawnResult)
        uploadOutputsAndWait(service, action, spawnResult)

        // assert
        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult
            .addOutputFilesBuilder()
            .setPath("outputs/a/foo")
            .setDigest(fooDigest)
            .setIsExecutable(true)
        expectedResult
            .addOutputDirectoriesBuilder()
            .setPath("outputs/bar")
            .setTreeDigest(barDigest)
            .setIsTopologicallySorted(true)
        assertThat(manifest.getActionResult()).isEqualTo(expectedResult.build())

        val toQuery: com.google.common.collect.ImmutableList<Digest?> =
            com.google.common.collect.ImmutableList.of<Digest?>(fooDigest, quxDigest, barDigest)
        assertThat(getFromFuture(cache.findMissingDigests(remoteActionExecutionContext, toQuery)))
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadOutputs_uploadEmptyDirectory_works() {
        // Test that uploading an empty directory works.

        // arrange

        val barDigest: Digest =
            fakeFileCache.createScratchInputDirectory(
                ActionInputHelper.fromPath("outputs/bar"),
                Tree.newBuilder().setRoot(Directory.getDefaultInstance()).build()
            )
        val barDir: Path = execRoot.getRelative("outputs/bar")
        val outputDirectory: Artifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(
                artifactRoot, barDir.relativeTo(execRoot)
            )
        val service: RemoteExecutionService = newRemoteExecutionService()
        val spawn: Spawn = newSpawn(
            com.google.common.collect.ImmutableMap.of<String?, String?>(),
            com.google.common.collect.ImmutableSet.of<Artifact?>(outputDirectory)
        )
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        val spawnResult: SpawnResult? =
            Builder()
                .setExitCode(0)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()

        // act
        val manifest: UploadManifest = service.buildUploadManifest(action, spawnResult)
        uploadOutputsAndWait(service, action, spawnResult)

        // assert
        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult
            .addOutputDirectoriesBuilder()
            .setPath("outputs/bar")
            .setTreeDigest(barDigest)
            .setIsTopologicallySorted(true)
        assertThat(manifest.getActionResult()).isEqualTo(expectedResult.build())
        assertThat(
            getFromFuture(
                cache.findMissingDigests(
                    remoteActionExecutionContext, com.google.common.collect.ImmutableList.of<E?>(barDigest)
                )
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadOutputs_uploadNestedDirectory_works() {
        // Test that uploading a nested directory works.

        // arrange

        val wobbleDigest: Digest =
            fakeFileCache.createScratchInput(
                ActionInputHelper.fromPath("outputs/bar/test/wobble"), "xyz"
            )
        val quxDigest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("outputs/bar/qux"), "abc")
        val testDirMessage: Directory? =
            Directory.newBuilder()
                .addFiles(
                    FileNode.newBuilder()
                        .setName("wobble")
                        .setDigest(wobbleDigest)
                        .setIsExecutable(true)
                        .build()
                )
                .build()
        val testDigest: Digest? = digestUtil.compute(testDirMessage)
        val barTree: Tree? =
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addFiles(
                            FileNode.newBuilder()
                                .setName("qux")
                                .setDigest(quxDigest)
                                .setIsExecutable(true)
                        )
                        .addDirectories(
                            DirectoryNode.newBuilder().setName("test").setDigest(testDigest)
                        )
                )
                .addChildren(testDirMessage)
                .build()
        val barDigest: Digest =
            fakeFileCache.createScratchInputDirectory(
                ActionInputHelper.fromPath("outputs/bar"), barTree
            )

        val quxFile: Path = execRoot.getRelative("outputs/bar/qux")
        quxFile.setExecutable(true)
        val barDir: Path = execRoot.getRelative("outputs/bar")

        val outputDirectory: Artifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(
                artifactRoot, barDir.relativeTo(execRoot)
            )
        val service: RemoteExecutionService = newRemoteExecutionService()
        val spawn: Spawn = newSpawn(
            com.google.common.collect.ImmutableMap.of<String?, String?>(),
            com.google.common.collect.ImmutableSet.of<Artifact?>(outputDirectory)
        )
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        val spawnResult: SpawnResult? =
            Builder()
                .setExitCode(0)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()

        // act
        val manifest: UploadManifest = service.buildUploadManifest(action, spawnResult)
        uploadOutputsAndWait(service, action, spawnResult)

        // assert
        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult
            .addOutputDirectoriesBuilder()
            .setPath("outputs/bar")
            .setTreeDigest(barDigest)
            .setIsTopologicallySorted(true)
        assertThat(manifest.getActionResult()).isEqualTo(expectedResult.build())

        val toQuery: com.google.common.collect.ImmutableList<Digest?> =
            com.google.common.collect.ImmutableList.of<Digest?>(wobbleDigest, quxDigest, barDigest)
        assertThat(getFromFuture(cache.findMissingDigests(remoteActionExecutionContext, toQuery)))
            .isEmpty()
    }

    @Throws(java.lang.Exception::class)
    private fun doUploadDanglingSymlink(targetPath: PathFragment) {
        // arrange
        val linkPath: Path = execRoot.getRelative("outputs/link")
        linkPath.createSymbolicLink(targetPath)
        val outputSymlink: Artifact =
            ActionsTestUtil.createUnresolvedSymlinkArtifactWithExecPath(
                artifactRoot, linkPath.relativeTo(execRoot)
            )
        val service: RemoteExecutionService = newRemoteExecutionService()
        val spawn: Spawn = newSpawn(
            com.google.common.collect.ImmutableMap.of<String?, String?>(),
            com.google.common.collect.ImmutableSet.of<Artifact?>(outputSymlink)
        )
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        val spawnResult: SpawnResult? =
            Builder()
                .setExitCode(0)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()

        // act
        val manifest: UploadManifest = service.buildUploadManifest(action, spawnResult)
        uploadOutputsAndWait(service, action, spawnResult)

        // assert
        val expectedResult: ActionResult.Builder = ActionResult.newBuilder()
        expectedResult
            .addOutputFileSymlinksBuilder()
            .setPath("outputs/link")
            .setTarget(targetPath.toString())
        expectedResult
            .addOutputSymlinksBuilder()
            .setPath("outputs/link")
            .setTarget(targetPath.toString())
        assertThat(manifest.getActionResult()).isEqualTo(expectedResult.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadOutputs_uploadRelativeDanglingSymlink() {
        doUploadDanglingSymlink(PathFragment.create("some/path"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadOutputs_uploadAbsoluteDanglingSymlink() {
        doUploadDanglingSymlink(PathFragment.create("/some/path"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadOutputs_emptyOutputs_doNotPerformUpload() {
        // Test that uploading an empty output does not try to perform an upload.

        // arrange

        val emptyDigest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("outputs/bar/test/wobble"), "")
        val file: Path? = execRoot.getRelative("outputs/bar/test/wobble")
        val outputFile: Artifact = ActionsTestUtil.createArtifact(artifactRoot, file)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val spawn: Spawn = newSpawn(
            com.google.common.collect.ImmutableMap.of<String?, String?>(),
            com.google.common.collect.ImmutableSet.of<Artifact?>(outputFile)
        )
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        val spawnResult: SpawnResult? =
            Builder()
                .setExitCode(0)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()

        // act
        uploadOutputsAndWait(service, action, spawnResult)

        // assert
        assertThat(
            getFromFuture(
                cache.findMissingDigests(
                    remoteActionExecutionContext, com.google.common.collect.ImmutableSet.of<E?>(emptyDigest)
                )
            )
        )
            .containsExactly(emptyDigest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadOutputs_uploadFails_printWarning() {
        val service: RemoteExecutionService = newRemoteExecutionService()
        val spawn: Spawn = newSpawn(
            com.google.common.collect.ImmutableMap.of<String?, String?>(),
            com.google.common.collect.ImmutableSet.of<Artifact?>()
        )
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        val spawnResult: SpawnResult? =
            Builder()
                .setExitCode(0)
                .setStatus(Status.SUCCESS)
                .setRunnerName("test")
                .build()
        Mockito.doReturn(com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(IOException("cache down")))
            .`when`<InMemoryCombinedCache?>(cache)
            .uploadActionResult(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        uploadOutputsAndWait(service, action, spawnResult)

        Truth.assertThat(eventHandler.getEvents()).hasSize(1)
        val evt: com.google.devtools.build.lib.events.Event = eventHandler.getEvents().get(0)
        Truth.assertThat<com.google.devtools.build.lib.events.EventKind?>(evt.getKind())
            .isEqualTo(com.google.devtools.build.lib.events.EventKind.WARNING)
        Truth.assertThat(evt.getMessage()).contains("cache down")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadOutputs_firesUploadEvents() {
        val digest: Digest =
            fakeFileCache.createScratchInput(ActionInputHelper.fromPath("outputs/file"), "content")
        val file: Path? = execRoot.getRelative("outputs/file")
        val outputFile: Artifact = ActionsTestUtil.createArtifact(artifactRoot, file)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val spawn: Spawn = newSpawn(
            com.google.common.collect.ImmutableMap.of<String?, String?>(),
            com.google.common.collect.ImmutableSet.of<Artifact?>(outputFile)
        )
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val action: RemoteAction = service.buildRemoteAction(spawn, context)
        val spawnResult: SpawnResult? =
            Builder()
                .setExitCode(0)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()

        uploadOutputsAndWait(service, action, spawnResult)

        Truth.assertThat(eventHandler.getPosts())
            .containsAtLeast(
                ActionUploadStartedEvent.create(spawn.getResourceOwner(), Store.CAS, digest),
                ActionUploadFinishedEvent.create(spawn.getResourceOwner(), Store.CAS, digest),
                ActionUploadStartedEvent.create(
                    spawn.getResourceOwner(), Store.AC, action.getActionKey().digest()
                ),
                ActionUploadFinishedEvent.create(
                    spawn.getResourceOwner(), Store.AC, action.getActionKey().digest()
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadOutputs_missingMandatoryOutputs_dontUpload() {
        val file: Path? = execRoot.getRelative("outputs/file")
        val outputFile: Artifact = ActionsTestUtil.createArtifact(artifactRoot, file)
        val service: RemoteExecutionService = newRemoteExecutionService()
        val spawn: Spawn = newSpawn(
            com.google.common.collect.ImmutableMap.of<String?, String?>(),
            com.google.common.collect.ImmutableSet.of<Artifact?>(outputFile)
        )
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        val spawnResult: SpawnResult? =
            Builder()
                .setExitCode(0)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()

        uploadOutputsAndWait(service, action, spawnResult)

        // assert
        Truth.assertThat(cache.getNumFindMissingDigests()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadInputsIfNotPresent_deduplicateFindMissingBlobCalls() {
        val taskCount = 100
        val executorService: ExecutorService = Executors.newFixedThreadPool(taskCount)
        val error: AtomicReference<Throwable?> = AtomicReference<Throwable?>(null)
        val semaphore: Semaphore = Semaphore(0)
        val input: ActionInput = ActionInputHelper.fromPath("inputs/foo")
        val inputDigest: Digest = fakeFileCache.createScratchInput(input, "input-foo")
        val service: RemoteExecutionService = newRemoteExecutionService()
        val spawn: Spawn =
            newSpawn(
                com.google.common.collect.ImmutableMap.of<String?, String?>(),
                com.google.common.collect.ImmutableSet.of<Artifact?>(),
                NestedSetBuilder.create(Order.STABLE_ORDER, input)
            )
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)

        for (i in 0..<taskCount) {
            executorService.execute(
                java.lang.Runnable {
                    try {
                        service.uploadInputsIfNotPresent(action,  /* force= */false)
                    } catch (e: Throwable) {
                        if (e is java.lang.InterruptedException) {
                            java.lang.Thread.currentThread().interrupt()
                        }
                        error.set(e)
                    } finally {
                        semaphore.release()
                    }
                })
        }
        semaphore.acquire(taskCount)

        Truth.assertThat(error.get()).isNull()
        Truth.assertThat(cache.getNumFindMissingDigests()).containsEntry(inputDigest, 1)
        for (num in cache.getNumFindMissingDigests().values()) {
            Truth.assertThat(num).isEqualTo(1)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadInputsIfNotPresent_sameInputs_interruptOne_keepOthers() {
        val taskCount = 100
        val executorService: ExecutorService = Executors.newFixedThreadPool(taskCount)
        val error: AtomicReference<Throwable?> = AtomicReference<Throwable?>(null)
        val semaphore: Semaphore = Semaphore(0)
        val input: ActionInput = ActionInputHelper.fromPath("inputs/foo")
        fakeFileCache.createScratchInput(input, "input-foo")
        val service: RemoteExecutionService = newRemoteExecutionService()
        val spawn: Spawn =
            newSpawn(
                com.google.common.collect.ImmutableMap.of<String?, String?>(),
                com.google.common.collect.ImmutableSet.of<Artifact?>(),
                NestedSetBuilder.create(Order.STABLE_ORDER, input)
            )
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        val random: Random = Random()

        for (i in 0..<taskCount) {
            val shouldInterrupt: Boolean = random.nextBoolean()
            executorService.execute(
                java.lang.Runnable {
                    try {
                        if (shouldInterrupt) {
                            java.lang.Thread.currentThread().interrupt()
                        }
                        service.uploadInputsIfNotPresent(action,  /* force= */false)
                    } catch (e: Throwable) {
                        if (!(shouldInterrupt && e is java.lang.InterruptedException)) {
                            error.set(e)
                        }
                    } finally {
                        semaphore.release()
                    }
                })
        }
        semaphore.acquire(taskCount)

        Truth.assertThat(error.get()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadInputsIfNotPresent_interrupted_requestCancelled() {
        val uploadBlobCalled: CountDownLatch = CountDownLatch(1)
        val interrupted: CountDownLatch = CountDownLatch(1)
        val futureDone: CountDownLatch = CountDownLatch(1)
        val future: com.google.common.util.concurrent.SettableFuture<com.google.common.collect.ImmutableSet<Digest?>?> =
            com.google.common.util.concurrent.SettableFuture.create<com.google.common.collect.ImmutableSet<Digest?>?>()
        future.addListener(
            java.lang.Runnable { futureDone.countDown() },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
        Mockito.doAnswer(
            Answer { invocationOnMock: InvocationOnMock? ->
                uploadBlobCalled.countDown()
                future
            })
            .`when`<Any?>(cache.remoteCacheClient)
            .uploadBlobImpl(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        val input: ActionInput = ActionInputHelper.fromPath("inputs/foo")
        fakeFileCache.createScratchInput(input, "input-foo")
        val service: RemoteExecutionService = newRemoteExecutionService()
        val spawn: Spawn =
            newSpawn(
                com.google.common.collect.ImmutableMap.of<String?, String?>(),
                com.google.common.collect.ImmutableSet.of<Artifact?>(),
                NestedSetBuilder.create(Order.STABLE_ORDER, input)
            )
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val action: RemoteAction? = service.buildRemoteAction(spawn, context)
        val thread: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        service.uploadInputsIfNotPresent(action,  /* force= */false)
                    } catch (ignored: java.lang.InterruptedException) {
                        interrupted.countDown()
                    } catch (ignored: java.lang.Exception) {
                        // intentionally ignored
                    }
                })

        thread.start()
        uploadBlobCalled.await()
        thread.interrupt()
        interrupted.await()
        futureDone.await()

        Truth.assertThat(future.isCancelled()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildRemoteActionForRemotePersistentWorkers(
        @TestParameter enablePathMapping: Boolean, @TestParameter siblingRepositoryLayout: Boolean
    ) {
        val input: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createArtifact(artifactRoot, "input")
        fakeFileCache.createScratchInput(input, "value")
        val toolInput: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createArtifact(artifactRoot, "worker_input")
        fakeFileCache.createScratchInput(toolInput, "worker value")

        val toolDat: Artifact = ActionsTestUtil.createArtifact(artifactRoot, "tool.dat")
        fakeFileCache.createScratchInput(toolDat, "tool.dat")
        val runfilesTree: RunfilesTree =
            createRunfilesTree(
                "outputs/worker_input.runfiles",
                com.google.common.collect.ImmutableList.of<Artifact?>(toolDat)
            )
        val runfilesArtifact: ActionInput? =
            ActionsTestUtil.createRunfilesArtifact(artifactRoot, "outputs/worker_input.runfiles")
        fakeFileCache.addRunfilesTree(runfilesArtifact, runfilesTree)

        val spawn: Spawn =
            SpawnBuilder("@flagfile")
                .withExecutionInfo(ExecutionRequirements.SUPPORTS_WORKERS, "1")
                .withExecutionInfo(ExecutionRequirements.REQUIRES_WORKER_PROTOCOL, "json")
                .withInputs(input, toolInput, runfilesArtifact)
                .withTools(toolInput, runfilesArtifact)
                .setPathMapper(
                    if (enablePathMapping) PathMapper { path -> PathFragment.create("mapped_" + path) } else PathMapper.NOOP)
                .build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        remoteOptions.markToolInputs = true
        remoteOptions.remoteDiscardMerkleTrees = false
        remotePathResolver =
            if (siblingRepositoryLayout)
                SiblingRepositoryLayoutResolver(execRoot)
            else
                DefaultRemotePathResolver(execRoot)
        val service: RemoteExecutionService = newRemoteExecutionService(remoteOptions)

        // Check that worker files are properly marked in the merkle tree.
        val runfilesSubDirectory: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Directory.newBuilder()
                .addFiles(
                    FileNode.newBuilder()
                        .setName("tool.dat")
                        .setDigest(digestUtil.computeAsUtf8("tool.dat"))
                        .setIsExecutable(true)
                        .setNodeProperties(
                            NodeProperties.newBuilder()
                                .addProperties(NodeProperty.newBuilder().setName("bazel_tool_input"))
                        )
                        .build()
                )
                .build()

        val runfilesDirectory: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Directory.newBuilder()
                .addDirectories(
                    DirectoryNode.newBuilder()
                        .setName(TestConstants.WORKSPACE_NAME)
                        .setDigest(digestUtil.compute(runfilesSubDirectory))
                        .build()
                )
                .build()

        val inputFile: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FileNode.newBuilder()
                .setName("input")
                .setDigest(digestUtil.computeAsUtf8("value"))
                .setIsExecutable(true)
                .build()
        val toolFile: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FileNode.newBuilder()
                .setName("worker_input")
                .setDigest(digestUtil.computeAsUtf8("worker value"))
                .setIsExecutable(true)
                .setNodeProperties(
                    NodeProperties.newBuilder()
                        .addProperties(NodeProperty.newBuilder().setName("bazel_tool_input"))
                )
                .build()
        val outputsDirectory: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Directory.newBuilder()
                .addFiles(inputFile)
                .addFiles(toolFile)
                .addDirectories(
                    DirectoryNode.newBuilder()
                        .setName("worker_input.runfiles")
                        .setDigest(digestUtil.compute(runfilesDirectory))
                        .build()
                )
                .build()

        var rootDirectory: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Directory.newBuilder()
                .addDirectories(
                    DirectoryNode.newBuilder()
                        .setName(if (enablePathMapping) "mapped_outputs" else "outputs")
                        .setDigest(digestUtil.compute(outputsDirectory))
                        .build()
                )
                .build()

        if (siblingRepositoryLayout) {
            rootDirectory =
                Directory.newBuilder()
                    .addDirectories(
                        DirectoryNode.newBuilder()
                            .setName("_main")
                            .setDigest(digestUtil.compute(rootDirectory))
                            .build()
                    )
                    .build()
        }

        val remoteAction1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            service.buildRemoteAction(spawn, context)
        val merkleTree: Uploadable = remoteAction1.getMerkleTree() as Uploadable
        assertThat(
            Directory.parseFrom(
                merkleTree.blobs().get(merkleTree.digest()) as ByteArray?,
                ExtensionRegistry.getEmptyRegistry()
            )
        )
            .isEqualTo(rootDirectory)
        assertThat(remoteAction1.getAction().getPlatform().getPropertiesList()).hasSize(2)
        assertThat(remoteAction1.getAction().getPlatform().getProperties(0).getName())
            .isEqualTo("persistentWorkerKey")

        // Ensure the worker protocol is communicated.
        assertThat(remoteAction1.getAction().getPlatform().getProperties(1).getName())
            .isEqualTo("persistentWorkerProtocol")
        assertThat(remoteAction1.getAction().getPlatform().getProperties(1).getValue())
            .isEqualTo("json")

        // Check that if a non-tool input changes, the persistent worker key does not change.
        fakeFileCache.createScratchInput(input, "value2")
        val remoteAction2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            service.buildRemoteAction(spawn, context)
        assertThat(remoteAction2.getAction().getPlatform())
            .isEqualTo(remoteAction1.getAction().getPlatform())

        // Check that if a tool input changes, the persistent worker key changes.
        fakeFileCache.createScratchInput(toolInput, "worker value2")
        val remoteAction3: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            service.buildRemoteAction(spawn, context)
        assertThat(remoteAction3.getAction().getPlatform().getPropertiesList()).hasSize(2)
        assertThat(remoteAction3.getAction().getPlatform().getProperties(0).getName())
            .isEqualTo("persistentWorkerKey")
        assertThat(remoteAction3.getAction().getPlatform().getProperties(0).getValue())
            .isNotEqualTo(remoteAction1.getAction().getPlatform().getProperties(0).getValue())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildRemoteActionWithScrubbing() {
        val keptInput: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createArtifact(artifactRoot, "kept_input")
        fakeFileCache.createScratchInput(keptInput, "kept")
        val scrubbedInput: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createArtifact(artifactRoot, "scrubbed_input")
        fakeFileCache.createScratchInput(scrubbedInput, "scrubbed")

        val spawn: Spawn =
            SpawnBuilder("some/path/cmd")
                .withInputs(keptInput, scrubbedInput)
                .withExecutionInfo(ExecutionRequirements.NO_REMOTE_EXEC, "")
                .build()

        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        remoteOptions.scrubber = Scrubber(
            Config.newBuilder()
                .addRules(
                    Config.Rule.newBuilder()
                        .setTransform(
                            Config.Transform.newBuilder()
                                .setSalt("NaCl")
                                .addOmittedInputs(".*scrubbed.*")
                                .addArgReplacements(
                                    Config.Replacement.newBuilder()
                                        .setSource("some/path")
                                        .setTarget("another/dir")
                                )
                        )
                )
                .build()
        )
        remoteOptions.remoteDiscardMerkleTrees = false
        val service: RemoteExecutionService = newRemoteExecutionService(remoteOptions)

        val remoteAction: RemoteAction = service.buildRemoteAction(spawn, context)

        val merkleTree: Uploadable = remoteAction.getMerkleTree() as Uploadable
        val rootProto: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Directory.parseFrom(
                merkleTree.blobs().get(merkleTree.digest()) as ByteArray?,
                ExtensionRegistry.getEmptyRegistry()
            )
        val actualRootDir: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Directory.parseFrom(
                merkleTree.blobs().get(rootProto.getDirectories(0).getDigest()) as ByteArray?,
                ExtensionRegistry.getEmptyRegistry()
            )

        val expectedRootDir: Directory? =
            Directory.newBuilder()
                .addFiles(
                    FileNode.newBuilder()
                        .setName("kept_input")
                        .setDigest(
                            Digest.newBuilder()
                                .setHash(
                                    "79f076abdd19a752db7267bfff2f9022161d120dea919fdaca2ffdfc24ca8c96"
                                )
                                .setSizeBytes(4)
                        )
                        .setIsExecutable(true)
                )
                .build()

        assertThat(actualRootDir).isEqualTo(expectedRootDir)

        assertThat(remoteAction.getCommand().getArgumentsList()).containsExactly("another/dir/cmd")

        assertThat(remoteAction.getAction().getSalt())
            .isEqualTo(
                CacheSalt.newBuilder()
                    .setScrubSalt(CacheSalt.ScrubSalt.newBuilder().setSalt("NaCl"))
                    .build()
                    .toByteString()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildRemoteActionWithPathMapping() {
        val mappedInput: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createArtifact(artifactRoot, "bin/config/input1")
        fakeFileCache.createScratchInput(mappedInput, "value1")
        val unmappedInput: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createArtifact(artifactRoot, "bin/input2")
        fakeFileCache.createScratchInput(unmappedInput, "value2")
        val outputDir: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(
                artifactRoot, "bin/config/output_dir"
            )
        val pathMapper: PathMapper =
            PathMapper { execPath -> PathFragment.create(execPath.getPathString().replaceAll("config/", "")) }
        val spawn: Spawn =
            SpawnBuilder("unused")
                .withInputs(mappedInput, unmappedInput)
                .withOutputs("outputs/bin/config/dir/output1", "outputs/bin/other_dir/output2")
                .withOutputs(outputDir)
                .setPathMapper(pathMapper)
                .build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        remoteOptions.remoteDiscardMerkleTrees = false
        val service: RemoteExecutionService = newRemoteExecutionService(remoteOptions)

        // Check that inputs and outputs of the remote action are mapped correctly.
        val remoteAction: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            service.buildRemoteAction(spawn, context)
        assertThat(remoteAction.getInputMap(false))
            .containsExactly(
                PathFragment.create("outputs/bin/input1"), mappedInput,
                PathFragment.create("outputs/bin/input2"), unmappedInput
            )
        assertThat(remoteAction.getCommand().getOutputFilesList()).isEmpty()
        assertThat(remoteAction.getCommand().getOutputDirectoriesList()).isEmpty()
        assertThat(remoteAction.getCommand().getOutputPathsList())
            .containsExactly(
                "outputs/bin/dir/output1", "outputs/bin/other_dir/output2", "outputs/bin/output_dir"
            )

        // Check that the Merkle tree nodes are mapped correctly, including the output directory.
        val merkleTree: Uploadable = remoteAction.getMerkleTree() as Uploadable
        val rootProto: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Directory.parseFrom(merkleTree.blobs().get(merkleTree.digest()) as ByteArray?)
        val outputsDirectory: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Directory.parseFrom(
                merkleTree.blobs().get(rootProto.getDirectories(0).getDigest()) as ByteArray?
            )
        assertThat(outputsDirectory.getDirectoriesCount()).isEqualTo(1)
        val binDirectory: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Directory.parseFrom(
                merkleTree.blobs().get(outputsDirectory.getDirectories(0).getDigest()) as ByteArray?
            )
        assertThat(
            binDirectory.getFilesList().stream().map(FileNode::getName)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        )
            .containsExactly("input1", "input2")
        assertThat(
            binDirectory.getDirectoriesList().stream()
                .map(DirectoryNode::getName)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        )
            .containsExactly("output_dir")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildRemoteAction_executablePathConformsToPlatform(@TestParameter executionOs: com.google.devtools.build.lib.util.OS?) {
        val spawn: Spawn =
            SpawnBuilder("path/to/pkg/script.bat", "some/other/arg")
                .withOutputs("out")
                .withPlatform(
                    PlatformInfo.builder()
                        .addConstraint(
                            ConstraintConstants.OS_TO_DEFAULT_CONSTRAINT_VALUE.get(executionOs)
                        )
                        .build()
                )
                .build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService(remoteOptions)

        val remoteAction: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            service.buildRemoteAction(spawn, context)

        val expectedFirstArg =
            if (executionOs == com.google.devtools.build.lib.util.OS.WINDOWS) "path\\to\\pkg\\script.bat" else "path/to/pkg/script.bat"
        assertThat(remoteAction.getCommand().getArgumentsList())
            .containsExactly(expectedFirstArg, "some/other/arg")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildRemoteAction_codePointSorting() {
        val one = "path/ﾐ"
        val oneF: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            execRoot.getRelative(unicodeToInternal(one)).asFragment()
        val two = "path/🔥"
        val twoF: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            execRoot.getRelative(unicodeToInternal(two)).asFragment()
        val three = "sort/system-foo"
        val threeF: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            execRoot.getRelative(unicodeToInternal(three)).asFragment()
        val four = "sort/system/foo"
        val fourF: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            execRoot.getRelative(unicodeToInternal(four)).asFragment()
        val spawn: @NotNull Spawn =
            SpawnBuilder("dummy")
                .withOutput(ActionsTestUtil.createArtifactWithExecPath(artifactRoot, oneF))
                .withOutput(ActionsTestUtil.createArtifactWithExecPath(artifactRoot, twoF))
                .withOutput(ActionsTestUtil.createArtifactWithExecPath(artifactRoot, threeF))
                .withOutput(ActionsTestUtil.createArtifactWithExecPath(artifactRoot, fourF))
                .withEnvironment(unicodeToInternal(one), "value1")
                .withEnvironment(unicodeToInternal(two), "value2")
                .withEnvironment(unicodeToInternal(three), "value3")
                .withEnvironment(unicodeToInternal(four), "value4")
                .build()
        val context: FakeSpawnExecutionContext = newSpawnExecutionContext(spawn)
        val service: RemoteExecutionService = newRemoteExecutionService()

        val remoteAction: RemoteAction = service.buildRemoteAction(spawn, context)

        assertThat(remoteAction.getCommand().getOutputPathsList())
            .containsExactly(
                execRoot.getRelative(one).getPathString(),
                execRoot.getRelative(two).getPathString(),
                execRoot.getRelative(three).getPathString(),
                execRoot.getRelative(four).getPathString()
            )
            .inOrder()
        assertThat(remoteAction.getCommand().getEnvironmentVariablesList())
            .containsExactly(
                Command.EnvironmentVariable.newBuilder().setName(one).setValue("value1").build(),
                Command.EnvironmentVariable.newBuilder().setName(two).setValue("value2").build(),
                Command.EnvironmentVariable.newBuilder().setName(three).setValue("value3").build(),
                Command.EnvironmentVariable.newBuilder().setName(four).setValue("value4").build()
            )
            .inOrder()
    }

    private fun newSpawnFromResult(result: RemoteActionResult): Spawn {
        return newSpawnFromResult(com.google.common.collect.ImmutableMap.of<String?, String?>(), result)
    }

    private fun newSpawnFromResult(
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?, result: RemoteActionResult
    ): Spawn {
        val outputs: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
            com.google.common.collect.ImmutableSet.builder<Artifact?>()
        for (file in result.getOutputFiles()) {
            val path: Path? = remotePathResolver.outputPathToLocalPath(unicodeToInternal(file.getPath()))
            val output: Artifact? = ActionsTestUtil.createArtifact(artifactRoot, path)
            outputs.add(output)
        }

        for (directory in result.getOutputDirectories()) {
            val path: Path = remotePathResolver.outputPathToLocalPath(unicodeToInternal(directory.getPath()))
            val output: Artifact? =
                ActionsTestUtil.createTreeArtifactWithGeneratingAction(
                    artifactRoot, path.relativeTo(execRoot)
                )
            outputs.add(output)
        }

        for (fileSymlink in result.getOutputFileSymlinks()) {
            val path: Path? =
                remotePathResolver.outputPathToLocalPath(unicodeToInternal(fileSymlink.getPath()))
            val output: Artifact? = ActionsTestUtil.createArtifact(artifactRoot, path)
            outputs.add(output)
        }

        for (directorySymlink in result.getOutputDirectorySymlinks()) {
            val path: Path =
                remotePathResolver.outputPathToLocalPath(unicodeToInternal(directorySymlink.getPath()))
            val output: Artifact? =
                ActionsTestUtil.createTreeArtifactWithGeneratingAction(
                    artifactRoot, path.relativeTo(execRoot)
                )
            outputs.add(output)
        }

        for (symlink in result.getOutputSymlinks()) {
            val path: Path? = remotePathResolver.outputPathToLocalPath(unicodeToInternal(symlink.getPath()))
            val output: Artifact? = ActionsTestUtil.createArtifact(artifactRoot, path)
            outputs.add(output)
        }

        return newSpawn(executionInfo, outputs.build())
    }

    private fun newSpawnFromResultWithInMemoryOutput(
        result: RemoteActionResult, inMemoryOutput: PathFragment
    ): Spawn {
        return newSpawnFromResult(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                REMOTE_EXECUTION_INLINE_OUTPUTS,
                inMemoryOutput.getPathString()
            ), result
        )
    }

    private fun newSpawn(
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?,
        outputs: com.google.common.collect.ImmutableSet<Artifact?>?
    ): Spawn {
        return newSpawn(executionInfo, outputs, NestedSetBuilder.emptySet(Order.STABLE_ORDER))
    }

    private fun newSpawn(
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?,
        outputs: com.google.common.collect.ImmutableSet<Artifact?>?,
        inputs: NestedSet<out ActionInput?>?
    ): Spawn {
        return SimpleSpawn(
            FakeOwner("foo", "bar", "//dummy:label"),  /* arguments= */
            com.google.common.collect.ImmutableList.of<E?>(),  /* environment= */
            com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* executionInfo= */
            executionInfo,  /* inputs= */
            inputs,  /* outputs= */
            outputs,
            ResourceSet.ZERO
        )
    }

    private fun newSpawnExecutionContext(spawn: Spawn?): FakeSpawnExecutionContext {
        return newSpawnExecutionContext(spawn, outErr)
    }

    private fun newSpawnExecutionContext(spawn: Spawn?, outErr: FileOutErr?): FakeSpawnExecutionContext {
        val actionInputFetcher: RemoteActionInputFetcher =
            RemoteActionInputFetcher(
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus()),
                "none",
                "none",
                cache,
                execRoot,
                tempPathGenerator,
                remoteOutputChecker,
                ActionOutputDirectoryHelper.createForTesting(),
                OutputPermissions.READONLY
            )

        val actionFileSystem: RemoteActionFileSystem =
            RemoteActionFileSystem(
                fs,
                execRoot.asFragment(),
                artifactRoot.getRoot().asPath().relativeTo(execRoot).getPathString(),
                ActionInputMap(0),
                actionInputFetcher
            )

        return FakeSpawnExecutionContext(
            spawn,
            fakeFileCache,
            execRoot,
            outErr,
            com.google.common.collect.ImmutableClassToInstanceMap.of<ActionContext?>(),
            actionFileSystem
        )
    }

    private fun newRemoteExecutionService(): RemoteExecutionService {
        return newRemoteExecutionService(remoteOptions)
    }

    private fun newRemoteExecutionService(remoteOptions: RemoteOptions?): RemoteExecutionService {
        return RemoteExecutionService(
            reporter,  /* verboseFailures= */
            true,
            execRoot,
            remotePathResolver,
            "none",
            "none",
            TestConstants.WORKSPACE_NAME,
            digestUtil,
            remoteOptions,
            com.google.devtools.common.options.Options.getDefaults<O?>(ExecutionOptions::class.java),
            cache,
            executor,
            tempPathGenerator,
            null,
            remoteOutputChecker,
            outputService,
            com.google.common.collect.Sets.newConcurrentHashSet<E?>()
        )
    }

    private fun createRunfilesTree(root: String?, artifacts: MutableCollection<Artifact?>): RunfilesTree {
        return object : RunfilesTree() {
            val execPath: PathFragment
                get() = PathFragment.create(root)

            val mapping: SortedMap<PathFragment?, Artifact?>
                get() {
                    return artifacts.stream()
                        .collect(
                            TODO("Cannot convert element")
                        ) < Object
                    TODO(
                        """
                    |Cannot convert element
                    |With text:
                    |Object>toImmutableSortedMap(
                    |                    <T>naturalOrder(),
                    |                    artifact ->
                    |                        PathFragment.create(TestConstants.WORKSPACE_NAME)
                    |                            .getRelative(artifact.getRunfilesPath())
                    """.trimMargin()
                    )
                    TODO(
                        """
                    |Cannot convert element
                    |With text:
                    |Object>identity(),
                    |                    (a, b) -> b
                    """.trimMargin()
                    )
                }

            val artifacts: NestedSet<Artifact?>
                get() = NestedSetBuilder.wrap(Order.STABLE_ORDER, artifacts)

            val symlinksMode: RunfileSymlinksMode
                get() = RunfileSymlinksMode.SKIP

            val isBuildRunfileLinks: Boolean
                get() = false

            val workspaceName: String
                get() = TestConstants.WORKSPACE_NAME

            val artifactsAtCanonicalLocationsForLogging: NestedSet<Artifact?>
                get() = NestedSetBuilder.wrap(Order.STABLE_ORDER, artifacts)

            val emptyFilenamesForLogging: com.google.common.collect.ImmutableList<PathFragment?>
                get() = com.google.common.collect.ImmutableList.of<PathFragment?>()

            val symlinksForLogging: NestedSet<SymlinkEntry?>
                get() = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

            val rootSymlinksForLogging: NestedSet<SymlinkEntry?>
                get() = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

            val repoMappingManifestForLogging: Artifact?
                get() = null

            val isMappingCached: Boolean
                get() = false

            public override fun fingerprint(
                actionKeyContext: ActionKeyContext?, fp: Fingerprint?, digestAbsolutePaths: Boolean
            ) {
                throw java.lang.UnsupportedOperationException()
            }
        }
    }

    @Throws(IOException::class)
    private fun createOutputDirectories(spawn: Spawn) {
        for (input in spawn.getOutputFiles()) {
            var dir: Path = execRoot.getRelative(input.getExecPath())
            if (!input.isDirectory()) {
                dir = dir.getParentDirectory()
            }
            dir.createDirectoryAndParents()
        }
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun uploadOutputsAndWait(
            service: RemoteExecutionService, action: RemoteAction?, result: SpawnResult?
        ) {
            val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
                com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
            service.uploadOutputs(action, result, { future.set(null) }, ConcurrentChangesCheckLevel.OFF)
            future.get()
        }
    }
}
