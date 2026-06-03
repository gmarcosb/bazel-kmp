// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionInput

/** Tests for [ActionOutputMetadataStore].  */
@RunWith(TestParameterInjector::class)
class ActionOutputMetadataStoreTest {
    private enum class ArtifactType {
        SOURCE,
        OUTPUT
    }

    private enum class ResolvedPathDepth {
        SHALLOW,
        DEEP
    }

    private val chmodCalls: MutableMap<Path?, Int?> = com.google.common.collect.Maps.newConcurrentMap<Path?, Int?>()

    private val scratch: Scratch = Scratch(
        object : InMemoryFileSystem(DigestHashFunction.SHA256) {
            @Throws(IOException::class)
            public override fun chmod(pathFragment: PathFragment, mode: Int) {
                val path: Path? = getPath(pathFragment)
                if (chmodCalls.containsKey(path)) {
                    org.junit.Assert.fail("chmod called on " + path + " twice")
                }
                chmodCalls.put(path, mode)
                super.chmod(pathFragment, mode)
            }
        })

    private val tsgm: TimestampGranularityMonitor =
        TimestampGranularityMonitor(com.google.devtools.build.lib.testutil.ManualClock())

    private val execRoot: Path = scratch.resolve("/workspace")
    private val sourceRoot: ArtifactRoot = ArtifactRoot.asSourceRoot(Root.fromPath(execRoot))
    private val outputRoot: ArtifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out")

    @Before
    @Throws(java.lang.Exception::class)
    fun createRootDirs() {
        sourceRoot.getRoot().asPath().createDirectoryAndParents()
        outputRoot.getRoot().asPath().createDirectoryAndParents()
    }

    private fun createStore(outputs: com.google.common.collect.ImmutableSet<Artifact?>?): ActionOutputMetadataStore {
        return createStore(outputs,  /* actionFs= */null)
    }

    private fun createStore(
        outputs: com.google.common.collect.ImmutableSet<Artifact?>?, actionFs: FileSystem?
    ): ActionOutputMetadataStore {
        return ActionOutputMetadataStore.create( /* archivedTreeArtifactsEnabled= */
            false,
            OutputPermissions.READONLY,
            outputs,
            SyscallCache.NO_CACHE,
            tsgm,
            ArtifactPathResolver.createPathResolver(actionFs, execRoot)
        )
    }

    private fun createRemoteActionFileSystem(): RemoteActionFileSystem {
        return createRemoteActionFileSystem(ActionInputMap(0))
    }

    private fun createRemoteActionFileSystem(inputMap: ActionInputMap?): RemoteActionFileSystem {
        return RemoteActionFileSystem(
            scratch.getFileSystem(),
            execRoot.asFragment(),
            outputRoot.getExecPathString(),
            inputMap,
            < T > mock < T ? > (RemoteActionInputFetcher::class.java))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun withNonArtifactInput() {
        val input: ActionInput? = ActionInputHelper.fromPath("foo/bar")
        val metadata: FileArtifactValue? =
            FileArtifactValue.createForNormalFile(
                byteArrayOf(1, 2, 3),  /* proxy= */null,  /* size= */10L
            )
        val map: ActionInputMap = ActionInputMap(1)
        map.put(input, metadata)
        assertThat(map.getInputMetadata(input)).isEqualTo(metadata)
        val inputMetadataProvider: ActionInputMetadataProvider = ActionInputMetadataProvider(map)
        assertThat(inputMetadataProvider.getInputMetadata(input)).isNull()
        Truth.assertThat(chmodCalls).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun withArtifactInput() {
        val path: PathFragment? = PathFragment.create("src/a")
        val artifact: Artifact? = ActionsTestUtil.createArtifactWithRootRelativePath(sourceRoot, path)
        val metadata: FileArtifactValue? =
            FileArtifactValue.createForNormalFile(
                byteArrayOf(1, 2, 3),  /* proxy= */null,  /* size= */10L
            )
        val map: ActionInputMap = ActionInputMap(1)
        map.put(artifact, metadata)
        val inputMetadataProvider: ActionInputMetadataProvider = ActionInputMetadataProvider(map)
        assertThat(inputMetadataProvider.getInputMetadata(artifact)).isEqualTo(metadata)
        Truth.assertThat(chmodCalls).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unknownSourceArtifactPermittedDuringInputDiscovery() {
        val path: PathFragment? = PathFragment.create("src/a")
        val artifact: Artifact? = ActionsTestUtil.createArtifactWithRootRelativePath(sourceRoot, path)
        val inputMap: ActionInputMap = ActionInputMap(0)
        val inputMetadataProvider: ActionInputMetadataProvider = ActionInputMetadataProvider(inputMap)
        assertThat(inputMetadataProvider.getInputMetadata(artifact)).isNull()
        Truth.assertThat(chmodCalls).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unknownArtifactPermittedDuringInputDiscovery() {
        val path: PathFragment? = PathFragment.create("foo/bar")
        val artifact: Artifact? = ActionsTestUtil.createArtifactWithRootRelativePath(outputRoot, path)
        val inputMap: ActionInputMap = ActionInputMap(0)
        val inputMetadataProvider: ActionInputMetadataProvider = ActionInputMetadataProvider(inputMap)
        assertThat(inputMetadataProvider.getInputMetadata(artifact)).isNull()
        Truth.assertThat(chmodCalls).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun withKnownOutputArtifactStatsFile() {
        val artifact: Artifact = ActionsTestUtil.createArtifact(outputRoot, "foo/bar")
        scratch.file(artifact.getPath().getPathString(), "not empty")
        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(artifact))
        assertThat(store.getOutputMetadata(artifact)).isNotNull()
        Truth.assertThat(chmodCalls).isEmpty()
    }

    @org.junit.Test
    fun withMissingOutputArtifactStatsFileFailsWithException() {
        val artifact: Artifact = ActionsTestUtil.createArtifact(outputRoot, "foo/bar")
        assertThat(artifact.getPath().exists()).isFalse()
        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(artifact))
        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { store.getOutputMetadata(artifact) })
        Truth.assertThat(chmodCalls).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unknownTreeArtifactPermittedDuringInputDiscovery() {
        val treeArtifact: SpecialArtifact? =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "foo/bar")
        val artifact: Artifact? = TreeFileArtifact.createTreeOutput(treeArtifact, "baz")
        val inputMap: ActionInputMap = ActionInputMap(0)
        val inputMetadataProvider: ActionInputMetadataProvider = ActionInputMetadataProvider(inputMap)
        assertThat(inputMetadataProvider.getInputMetadata(artifact)).isNull()
        Truth.assertThat(chmodCalls).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun withUnknownOutputArtifactStatsFileTreeArtifact() {
        val treeArtifact: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "foo/bar")
        val artifact: Artifact = TreeFileArtifact.createTreeOutput(treeArtifact, "baz")
        scratch.file(artifact.getPath().getPathString(), "not empty")
        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(treeArtifact))
        assertThat(store.getOutputMetadata(artifact)).isNotNull()
        Truth.assertThat(chmodCalls).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createsTreeArtifactValueFromFilesystem() {
        val treeArtifact: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "foo/bar")
        val child1: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "child1")
        val child2: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "child2")
        scratch.file(child1.getPath().getPathString(), "child1")
        scratch.file(child2.getPath().getPathString(), "child2")

        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(treeArtifact))

        val treeMetadata: FileArtifactValue? = store.getOutputMetadata(treeArtifact)
        val child1Metadata: FileArtifactValue? = store.getOutputMetadata(child1)
        val child2Metadata: FileArtifactValue? = store.getOutputMetadata(child2)
        val tree: TreeArtifactValue = store.getAllTreeArtifactData().get(treeArtifact)

        assertThat(tree.getMetadata()).isEqualTo(treeMetadata)
        assertThat(tree.getChildValues())
            .containsExactly(child1, child1Metadata, child2, child2Metadata)
        assertThat(store.getTreeArtifactValue(treeArtifact)).isEqualTo(tree)
        assertThat(store.getAllArtifactData()).isEmpty()
        Truth.assertThat(chmodCalls).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun withDanglingSymlinkInTreeArtifactFailsWithException() {
        val treeArtifact: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "foo/bar")
        val child: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "child")
        treeArtifact.getPath().createDirectoryAndParents()
        child.getPath().createSymbolicLink(PathFragment.create("/does_not_exist"))

        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(treeArtifact))

        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { store.getOutputMetadata(treeArtifact) })
        Truth.assertThat(e).hasMessageThat().contains("dangling symbolic link")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resettingOutputs() {
        val path: PathFragment? = PathFragment.create("foo/bar")
        val artifact: Artifact = ActionsTestUtil.createArtifactWithRootRelativePath(outputRoot, path)
        val outputPath: Path? = scratch.file(artifact.getPath().getPathString(), "not empty")
        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(artifact))
        store.prepareForActionExecution()

        // The store doesn't have any info. It'll stat the file and discover that it's 10 bytes long.
        assertThat(store.getOutputMetadata(artifact).getSize()).isEqualTo(10)
        Truth.assertThat(chmodCalls).containsExactly(outputPath, 365)

        // Inject a remote file of size 42.
        store.injectFile(artifact, FileArtifactValue.createForRemoteFile(byteArrayOf(1, 2, 3), 42, 0))
        assertThat(store.getOutputMetadata(artifact).getSize()).isEqualTo(42)

        // Reset this output, which will make the store stat the file again.
        store.resetOutputs(com.google.common.collect.ImmutableList.of<E?>(artifact))
        chmodCalls.clear()
        assertThat(store.getOutputMetadata(artifact).getSize()).isEqualTo(10)
        // The store should not have chmodded the file as it already has the correct permission.
        Truth.assertThat(chmodCalls).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun injectRemoteArtifactMetadata() {
        val path: PathFragment? = PathFragment.create("foo/bar")
        val artifact: Artifact = ActionsTestUtil.createArtifactWithRootRelativePath(outputRoot, path)
        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(artifact))
        store.prepareForActionExecution()

        val digest = byteArrayOf(1, 2, 3)
        val size = 10
        store.injectFile(
            artifact, FileArtifactValue.createForRemoteFile(digest, size,  /* locationIndex= */1)
        )

        val v: FileArtifactValue = store.getOutputMetadata(artifact)
        assertThat(v).isNotNull()
        assertThat(v.getDigest()).isEqualTo(digest)
        assertThat(v.getSize()).isEqualTo(size)
        Truth.assertThat(chmodCalls).isEmpty()
    }

    @org.junit.Test
    fun cannotInjectTreeArtifactChildIndividually() {
        val treeArtifact: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "foo/bar")
        val child: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(treeArtifact, "child")

        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(treeArtifact))
        store.prepareForActionExecution()

        val childValue: FileArtifactValue? =
            FileArtifactValue.createForRemoteFile(byteArrayOf(1, 2, 3), 5, 1)

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { store.injectFile(child, childValue) })
        assertThat(store.getAllArtifactData()).isEmpty()
        assertThat(store.getAllTreeArtifactData()).isEmpty()
        Truth.assertThat(chmodCalls).isEmpty()
    }

    @org.junit.Test
    fun canInjectTemplateExpansionOutput() {
        val treeArtifact: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "foo/bar")
        val output: TreeFileArtifact? =
            TreeFileArtifact.createTemplateExpansionOutput(
                treeArtifact, "output", ActionsTestUtil.NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER
            )

        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(treeArtifact))
        store.prepareForActionExecution()

        val value: FileArtifactValue? = FileArtifactValue.createForRemoteFile(byteArrayOf(1, 2, 3), 5, 1)
        store.injectFile(output, value)

        assertThat(store.getAllArtifactData()).containsExactly(output, value)
        assertThat(store.getAllTreeArtifactData()).isEmpty()
        Truth.assertThat(chmodCalls).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun injectRemoteTreeArtifactMetadata() {
        val treeArtifact: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "dir")
        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(treeArtifact))
        store.prepareForActionExecution()

        val tree: TreeArtifactValue =
            TreeArtifactValue.newBuilder(treeArtifact)
                .putChild(
                    TreeFileArtifact.createTreeOutput(treeArtifact, "foo"),
                    FileArtifactValue.createForRemoteFile(byteArrayOf(1, 2, 3), 5, 1)
                )
                .putChild(
                    TreeFileArtifact.createTreeOutput(treeArtifact, "bar"),
                    FileArtifactValue.createForRemoteFile(byteArrayOf(4, 5, 6), 10, 1)
                )
                .build()

        store.injectTree(treeArtifact, tree)

        val value: FileArtifactValue = store.getOutputMetadata(treeArtifact)
        assertThat(value).isNotNull()
        assertThat(value.getDigest()).isEqualTo(tree.getDigest())
        assertThat(store.getAllTreeArtifactData().get(treeArtifact)).isEqualTo(tree)
        Truth.assertThat(chmodCalls).isEmpty()

        assertThat(store.getTreeArtifactValue(treeArtifact)).isEqualTo(tree)

        // Make sure that all children are transferred properly into the ActionExecutionValue. If any
        // child is missing, getExistingFileArtifactValue will throw.
        val actionExecutionValue: ActionExecutionValue =
            ActionExecutionValue.create(store, null, NullAction())
        tree.getChildren().forEach(actionExecutionValue::getExistingFileArtifactValue)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fileArtifactMaterializedAsSymlinkToNonArtifact() {
        val resolvedPath: Path = outputRoot.getRoot().getRelative("resolved")
        val outputArtifact: Artifact =
            ActionsTestUtil.createArtifactWithRootRelativePath(
                outputRoot, PathFragment.create("output")
            )

        val actionFs: RemoteActionFileSystem = createRemoteActionFileSystem()

        val store: ActionOutputMetadataStore =
            createStore(com.google.common.collect.ImmutableSet.of<Artifact?>(outputArtifact), actionFs)
        store.prepareForActionExecution()

        actionFs
            .getPath(outputArtifact.getPath().asFragment())
            .getParentDirectory()
            .createDirectoryAndParents()
        actionFs
            .getPath(outputArtifact.getPath().asFragment())
            .createSymbolicLink(resolvedPath.asFragment())
        FileSystemUtils.writeContentAsLatin1(resolvedPath, "foo")

        assertThat(store.getOutputMetadata(outputArtifact))
            .isEqualTo(FileArtifactValue.createForTesting(resolvedPath))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fileArtifactMaterializedAsSymlinkToFileArtifact(
        @TestParameter artifactType: ArtifactType, @TestParameter depth: ResolvedPathDepth?
    ) {
        val root: ArtifactRoot? =
            when (artifactType) {
                ArtifactType.SOURCE -> sourceRoot
                ArtifactType.OUTPUT -> outputRoot
            }
        val resolvedArtifact: Artifact =
            ActionsTestUtil.createArtifactWithRootRelativePath(root, PathFragment.create("resolved"))
        val inputArtifact: Artifact =
            when (depth) {
                ResolvedPathDepth.SHALLOW -> resolvedArtifact
                ResolvedPathDepth.DEEP -> ActionsTestUtil.createArtifactWithRootRelativePath(
                    outputRoot, PathFragment.create("input")
                )
            }
        val outputArtifact: Artifact =
            ActionsTestUtil.createArtifactWithRootRelativePath(
                outputRoot, PathFragment.create("output")
            )

        var inputMetadata: FileArtifactValue? =
            FileArtifactValue.createForNormalFile(byteArrayOf(1, 2, 3), null, 123L)
        if (!resolvedArtifact.equals(inputArtifact)) {
            inputMetadata =
                FileArtifactValue.createFromExistingWithResolvedPath(
                    inputMetadata, resolvedArtifact.getPath().asFragment()
                )
        }

        val inputMap: ActionInputMap = ActionInputMap(0)
        inputMap.put(inputArtifact, inputMetadata)

        val actionFs: RemoteActionFileSystem = createRemoteActionFileSystem(inputMap)

        val store: ActionOutputMetadataStore =
            createStore(com.google.common.collect.ImmutableSet.of<Artifact?>(outputArtifact), actionFs)
        store.prepareForActionExecution()

        actionFs
            .getPath(outputArtifact.getPath().asFragment())
            .getParentDirectory()
            .createDirectoryAndParents()
        actionFs
            .getPath(outputArtifact.getPath().asFragment())
            .createSymbolicLink(inputArtifact.getPath().asFragment())

        assertThat(store.getOutputMetadata(outputArtifact))
            .isEqualTo(
                FileArtifactValue.createFromExistingWithResolvedPath(
                    inputMetadata, resolvedArtifact.getPath().asFragment()
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun treeArtifactMaterializedAsSymlinkToNonArtifact() {
        val resolvedPath: Path = outputRoot.getRoot().getRelative("resolved")
        val outputArtifact: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "output")

        val actionFs: RemoteActionFileSystem = createRemoteActionFileSystem()

        val store: ActionOutputMetadataStore =
            createStore(com.google.common.collect.ImmutableSet.of<Artifact?>(outputArtifact), actionFs)
        store.prepareForActionExecution()

        actionFs
            .getPath(outputArtifact.getPath().asFragment())
            .getParentDirectory()
            .createDirectoryAndParents()
        actionFs
            .getPath(outputArtifact.getPath().asFragment())
            .createSymbolicLink(resolvedPath.asFragment())
        actionFs.getPath(resolvedPath.asFragment()).createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(resolvedPath.getRelative("child"), "foo")

        assertThat(store.getTreeArtifactValue(outputArtifact))
            .isEqualTo(
                TreeArtifactValue.newBuilder(outputArtifact)
                    .putChild(
                        TreeFileArtifact.createTreeOutput(outputArtifact, "child"),
                        FileArtifactValue.createForTesting(resolvedPath.getRelative("child"))
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun treeArtifactMaterializedAsSymlinkToAnotherTreeArtifact(
        @TestParameter depth: ResolvedPathDepth
    ) {
        val resolvedArtifact: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "resolved")
        val inputArtifact: SpecialArtifact =
            when (depth) {
                ResolvedPathDepth.SHALLOW -> resolvedArtifact
                ResolvedPathDepth.DEEP -> ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "input")
            }
        val outputArtifact: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "output")

        val builder: TreeArtifactValue.Builder =
            TreeArtifactValue.newBuilder(inputArtifact)
                .putChild(
                    TreeFileArtifact.createTreeOutput(inputArtifact, "child"),
                    FileArtifactValue.createForNormalFile(
                        byteArrayOf(1, 2, 3),  /* proxy= */null, 123L
                    )
                )
        if (depth == ResolvedPathDepth.DEEP) {
            builder.setResolvedPath(resolvedArtifact.getPath().asFragment())
        }
        val inputMetadata: TreeArtifactValue? = builder.build()

        val inputMap: ActionInputMap = ActionInputMap(0)
        inputMap.putTreeArtifact(inputArtifact, inputMetadata)

        val actionFs: RemoteActionFileSystem = createRemoteActionFileSystem(inputMap)

        val store: ActionOutputMetadataStore =
            createStore(com.google.common.collect.ImmutableSet.of<Artifact?>(outputArtifact), actionFs)
        store.prepareForActionExecution()

        actionFs
            .getPath(outputArtifact.getPath().asFragment())
            .getParentDirectory()
            .createDirectoryAndParents()
        actionFs
            .getPath(outputArtifact.getPath().asFragment())
            .createSymbolicLink(inputArtifact.getPath().asFragment())

        assertThat(store.getTreeArtifactValue(outputArtifact))
            .isEqualTo(
                TreeArtifactValue.newBuilder(outputArtifact)
                    .putChild(
                        TreeFileArtifact.createTreeOutput(outputArtifact, "child"),
                        FileArtifactValue.createForNormalFile(
                            byteArrayOf(1, 2, 3),  /* proxy= */null, 123L
                        )
                    )
                    .setResolvedPath(resolvedArtifact.getPath().asFragment())
                    .build()
            )
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val metadataFromFilesetMapping: Unit
        get() {
            val sourceArtifact: Artifact? = ActionsTestUtil.createArtifact(sourceRoot, "src.txt")
            val metadata: FileArtifactValue? =
                FileArtifactValue.createForNormalFile(byteArrayOf(1, 2, 3, 4), null, 10L)
            val symlink: FilesetOutputSymlink =
                FilesetOutputSymlink(PathFragment.create("file"), sourceArtifact, metadata)

            val artifact: Artifact? = ActionsTestUtil.createFilesetArtifact(outputRoot, "foo/bar")
            val actionInputMap: ActionInputMap = ActionInputMap(1)
            actionInputMap.putFileset(
                artifact,
                FilesetOutputTree.create(
                    com.google.common.collect.ImmutableList.of<E?>(symlink),  /* treeArtifacts= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>()
                )
            )
            val inputMetadataProvider: ActionInputMetadataProvider =
                ActionInputMetadataProvider(actionInputMap)

            assertThat(inputMetadataProvider.getInputMetadata(sourceArtifact)).isSameInstanceAs(metadata)
            assertThat(inputMetadataProvider.getInputMetadata(ActionInputHelper.fromPath("does_not_exist")))
                .isNull()
            Truth.assertThat(chmodCalls).isEmpty()
        }

    @org.junit.Test
    fun omitRegularArtifact() {
        val omitted: Artifact =
            ActionsTestUtil.createArtifactWithRootRelativePath(
                outputRoot, PathFragment.create("omitted")
            )
        val consumed: Artifact? =
            ActionsTestUtil.createArtifactWithRootRelativePath(
                outputRoot, PathFragment.create("consumed")
            )
        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(omitted, consumed))

        store.prepareForActionExecution()
        store.markOmitted(omitted)

        assertThat(store.artifactOmitted(omitted)).isTrue()
        assertThat(store.artifactOmitted(consumed)).isFalse()
        assertThat(store.getAllTreeArtifactData()).isEmpty()
        Truth.assertThat(chmodCalls).isEmpty()
    }

    @org.junit.Test
    fun omitTreeArtifact() {
        val omittedTree: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(
                outputRoot, PathFragment.create("omitted")
            )
        val consumedTree: SpecialArtifact? =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(
                outputRoot, PathFragment.create("consumed")
            )
        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(omittedTree, consumedTree))

        store.prepareForActionExecution()
        store.markOmitted(omittedTree)
        store.markOmitted(omittedTree) // Marking a tree artifact as omitted twice is tolerated.

        assertThat(store.artifactOmitted(omittedTree)).isTrue()
        assertThat(store.artifactOmitted(consumedTree)).isFalse()
        assertThat(store.getAllArtifactData()).isEmpty()
        Truth.assertThat(chmodCalls).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputArtifactNotPreviouslyInjectedInExecutionMode() {
        val output: Artifact =
            ActionsTestUtil.createArtifactWithRootRelativePath(
                outputRoot, PathFragment.create("dir/file.out")
            )
        val outputPath: Path = scratch.file(output.getPath().getPathString(), "contents")
        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(output))
        store.prepareForActionExecution()

        val metadata: FileArtifactValue = store.getOutputMetadata(output)

        assertThat(metadata.getDigest()).isEqualTo(outputPath.getDigest())
        assertThat(store.getAllArtifactData()).containsExactly(output, metadata)
        assertThat(store.getAllTreeArtifactData()).isEmpty()
        Truth.assertThat(chmodCalls).containsExactly(outputPath, 365)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputArtifactNotPreviouslyInjectedInExecutionMode_writablePermissions() {
        val output: Artifact =
            ActionsTestUtil.createArtifactWithRootRelativePath(
                outputRoot, PathFragment.create("dir/file.out")
            )
        val outputPath: Path = scratch.file(output.getPath().getPathString(), "contents")
        val store: ActionOutputMetadataStore =
            ActionOutputMetadataStore.create( /* archivedTreeArtifactsEnabled= */
                false,
                OutputPermissions.WRITABLE,  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(output),
                SyscallCache.NO_CACHE,
                tsgm,
                ArtifactPathResolver.IDENTITY
            )
        store.prepareForActionExecution()

        val metadata: FileArtifactValue = store.getOutputMetadata(output)

        assertThat(metadata.getDigest()).isEqualTo(outputPath.getDigest())
        assertThat(store.getAllArtifactData()).containsExactly(output, metadata)
        assertThat(store.getAllTreeArtifactData()).isEmpty()
        // Permissions preserved in store, so chmod calls should be empty.
        Truth.assertThat(chmodCalls).containsExactly(outputPath, 493)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputTreeArtifactNotPreviouslyInjectedInExecutionMode() {
        val treeArtifact: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "foo/bar")
        val child1: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "child1")
        val child2: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "subdir/child2")
        val child1Path: Path = scratch.file(child1.getPath().getPathString(), "contents1")
        val child2Path: Path = scratch.file(child2.getPath().getPathString(), "contents2")
        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(treeArtifact))
        store.prepareForActionExecution()

        val treeMetadata: FileArtifactValue? = store.getOutputMetadata(treeArtifact)
        val child1Metadata: FileArtifactValue? = store.getOutputMetadata(child1)
        val child2Metadata: FileArtifactValue? = store.getOutputMetadata(child2)
        val tree: TreeArtifactValue = store.getAllTreeArtifactData().get(treeArtifact)

        assertThat(tree.getMetadata()).isEqualTo(treeMetadata)
        assertThat(tree.getChildValues())
            .containsExactly(child1, child1Metadata, child2, child2Metadata)
        assertThat(store.getTreeArtifactValue(treeArtifact)).isEqualTo(tree)
        assertThat(store.getAllArtifactData()).isEmpty()
        Truth.assertThat(chmodCalls)
            .containsExactly(
                treeArtifact.getPath(),
                365,
                child1Path,
                365,
                child2Path,
                365,
                child2Path.getParentDirectory(),
                365
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enteringExecutionModeClearsCachedOutputs() {
        val artifact: Artifact =
            ActionsTestUtil.createArtifactWithRootRelativePath(
                outputRoot, PathFragment.create("output")
            )
        val treeArtifact: SpecialArtifact? =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "tree")
        val child: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "child")
        scratch.file(artifact.getPath().getPathString(), "1")
        scratch.file(child.getPath().getPathString(), "1")
        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(artifact, treeArtifact))

        val artifactMetadata1: FileArtifactValue? = store.getOutputMetadata(artifact)
        val treeArtifactMetadata1: FileArtifactValue? = store.getOutputMetadata(treeArtifact)
        assertThat(artifactMetadata1).isNotNull()
        assertThat(treeArtifactMetadata1).isNotNull()
        assertThat(store.getAllArtifactData().keySet()).containsExactly(artifact)
        assertThat(store.getAllTreeArtifactData().keySet()).containsExactly(treeArtifact)

        // Entering execution mode should clear the cached outputs.
        store.prepareForActionExecution()
        assertThat(store.getAllArtifactData()).isEmpty()
        assertThat(store.getAllTreeArtifactData()).isEmpty()

        // Updated metadata should be read from the filesystem.
        scratch.overwriteFile(artifact.getPath().getPathString(), "2")
        scratch.overwriteFile(child.getPath().getPathString(), "2")
        val artifactMetadata2: FileArtifactValue? = store.getOutputMetadata(artifact)
        val treeArtifactMetadata2: FileArtifactValue? = store.getOutputMetadata(treeArtifact)
        assertThat(artifactMetadata2).isNotNull()
        assertThat(treeArtifactMetadata2).isNotNull()
        assertThat(artifactMetadata2).isNotEqualTo(artifactMetadata1)
        assertThat(treeArtifactMetadata2).isNotEqualTo(treeArtifactMetadata1)
    }

    @org.junit.Test
    fun cannotEnterExecutionModeTwice() {
        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>())
        store.prepareForActionExecution()
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            store::prepareForActionExecution
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fileArtifactValueFromArtifactCompatibleWithGetMetadata_changed() {
        val artifact: Artifact =
            ActionsTestUtil.createArtifactWithRootRelativePath(
                outputRoot, PathFragment.create("output")
            )
        scratch.file(artifact.getPath().getPathString(), "1")
        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(artifact))

        val getMetadataResult: FileArtifactValue? = store.getOutputMetadata(artifact)
        assertThat(getMetadataResult).isNotNull()

        scratch.overwriteFile(artifact.getPath().getPathString(), "2")
        val fileArtifactValueFromArtifactResult: FileArtifactValue =
            ActionOutputMetadataStore.fileArtifactValueFromArtifact(
                artifact,  /* statNoFollow= */null, SyscallCache.NO_CACHE,  /* tsgm= */null
            )
        assertThat(fileArtifactValueFromArtifactResult).isNotNull()

        assertThat(fileArtifactValueFromArtifactResult.couldBeModifiedSince(getMetadataResult))
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fileArtifactValueFromArtifactCompatibleWithGetMetadata_notChanged() {
        val artifact: Artifact =
            ActionsTestUtil.createArtifactWithRootRelativePath(
                outputRoot, PathFragment.create("output")
            )
        scratch.file(artifact.getPath().getPathString(), "contents")
        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(artifact))

        val getMetadataResult: FileArtifactValue? = store.getOutputMetadata(artifact)
        assertThat(getMetadataResult).isNotNull()

        val fileArtifactValueFromArtifactResult: FileArtifactValue =
            ActionOutputMetadataStore.fileArtifactValueFromArtifact(
                artifact,  /* statNoFollow= */null, SyscallCache.NO_CACHE,  /* tsgm= */null
            )
        assertThat(fileArtifactValueFromArtifactResult).isNotNull()

        assertThat(fileArtifactValueFromArtifactResult.couldBeModifiedSince(getMetadataResult))
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fileArtifactValueForSymlink_readFromCache() {
        DigestUtils.configureCache(1)
        val target: Artifact =
            ActionsTestUtil.createArtifactWithRootRelativePath(
                outputRoot, PathFragment.create("target")
            )
        scratch.file(target.getPath().getPathString(), "contents")
        val symlink: Artifact =
            ActionsTestUtil.createArtifactWithRootRelativePath(
                outputRoot, PathFragment.create("symlink")
            )
        scratch
            .getFileSystem()
            .getPath(symlink.getPath().getPathString())
            .createSymbolicLink(scratch.getFileSystem().getPath(target.getPath().getPathString()))
        val store: ActionOutputMetadataStore =
            createStore( /* outputs= */com.google.common.collect.ImmutableSet.of<Artifact?>(target, symlink))
        val targetMetadata: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            store.getOutputMetadata(target)
        assertThat(DigestUtils.getCacheStats().hitCount()).isEqualTo(0)

        val symlinkMetadata: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            store.getOutputMetadata(symlink)

        assertThat(symlinkMetadata).isEqualTo(targetMetadata)
        assertThat(DigestUtils.getCacheStats().hitCount()).isEqualTo(1)
    }
}
