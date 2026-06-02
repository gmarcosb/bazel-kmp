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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.ActionExecutionContext

/** Tests [SymlinkAction].  */
@RunWith(TestParameterInjector::class)
class SymlinkActionTest : BuildViewTestCase() {
    private var executor: Executor? = null
    private var fs: SpiedFileSystem? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        executor = TestExecutorBuilder(fileSystem, directories).build()
    }

    public override fun createFileSystem(): FileSystem? {
        fs = SpiedFileSystem.createInMemorySpy()
        return fs
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkToSourceFile(@TestParameter useExecRootForSource: Boolean) {
        val inputArtifact: Artifact = getSourceArtifact("input")
        val outputArtifact: Artifact = getBinArtifactWithNoOwner("output")

        val inputPath: Path = directories.getExecRoot(TestConstants.WORKSPACE_NAME).getRelative("input")
        inputPath.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(inputPath)
        inputArtifact.getPath().createSymbolicLink(inputPath)
        outputArtifact.getPath().getParentDirectory().createDirectoryAndParents()

        runSymlinkAction(inputArtifact, outputArtifact, useExecRootForSource)

        val expectedTarget: PathFragment? =
            if (useExecRootForSource)
                getExecRoot().getRelative(inputArtifact.getExecPath()).asFragment()
            else
                inputArtifact.getPath().asFragment()

        assertThat(outputArtifact.getPath().isSymbolicLink()).isTrue()
        assertThat(outputArtifact.getPath().readSymbolicLink()).isEqualTo(expectedTarget)

        Mockito.verify<SpiedFileSystem?>(fs)
            .createSymbolicLink(
                outputArtifact.getPath().asFragment(), expectedTarget, SymlinkTargetType.FILE
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkToSourceDirectory(@TestParameter useExecRootForSource: Boolean) {
        val inputArtifact: Artifact = getSourceArtifact("input")
        val outputArtifact: Artifact = getBinArtifactWithNoOwner("output")

        val inputPath: Path = directories.getExecRoot(TestConstants.WORKSPACE_NAME).getRelative("input")
        inputPath.createDirectoryAndParents()
        inputArtifact.getPath().createSymbolicLink(inputPath)
        outputArtifact.getPath().getParentDirectory().createDirectoryAndParents()

        runSymlinkAction(inputArtifact, outputArtifact, useExecRootForSource)

        val expectedTarget: PathFragment? =
            if (useExecRootForSource)
                getExecRoot().getRelative(inputArtifact.getExecPath()).asFragment()
            else
                inputArtifact.getPath().asFragment()

        assertThat(outputArtifact.getPath().isSymbolicLink()).isTrue()
        assertThat(outputArtifact.getPath().readSymbolicLink()).isEqualTo(expectedTarget)

        Mockito.verify<SpiedFileSystem?>(fs)
            .createSymbolicLink(
                outputArtifact.getPath().asFragment(), expectedTarget, SymlinkTargetType.DIRECTORY
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkToOutputFile() {
        val inputArtifact: Artifact = getBinArtifactWithNoOwner("input")
        val outputArtifact: Artifact = getBinArtifactWithNoOwner("output")

        inputArtifact.getPath().getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContent(inputArtifact.getPath(), java.nio.charset.StandardCharsets.UTF_8, "hello world")
        outputArtifact.getPath().getParentDirectory().createDirectoryAndParents()

        runSymlinkAction(inputArtifact, outputArtifact)

        assertThat(outputArtifact.getPath().isSymbolicLink()).isTrue()
        assertThat(outputArtifact.getPath().readSymbolicLink())
            .isEqualTo(inputArtifact.getPath().asFragment())

        Mockito.verify<SpiedFileSystem?>(fs)
            .createSymbolicLink(
                outputArtifact.getPath().asFragment(),
                inputArtifact.getPath().asFragment(),
                SymlinkTargetType.FILE
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkToOutputTree() {
        val inputArtifact: Artifact =
            createTreeArtifactWithGeneratingAction(
                getTargetConfiguration().getBinDir(), "input"
            )
        val outputArtifact: Artifact =
            createTreeArtifactWithGeneratingAction(
                getTargetConfiguration().getBinDir(), "output"
            )

        inputArtifact.getPath().createDirectoryAndParents()
        outputArtifact.getPath().createDirectoryAndParents()

        runSymlinkAction(inputArtifact, outputArtifact)

        assertThat(outputArtifact.getPath().isSymbolicLink()).isTrue()
        assertThat(outputArtifact.getPath().readSymbolicLink())
            .isEqualTo(inputArtifact.getPath().asFragment())

        Mockito.verify<SpiedFileSystem?>(fs)
            .createSymbolicLink(
                outputArtifact.getPath().asFragment(),
                inputArtifact.getPath().asFragment(),
                SymlinkTargetType.DIRECTORY
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkToAbsolutePath() {
        val outputArtifact: Artifact = getBinArtifactWithNoOwner("output")

        outputArtifact.getPath().getParentDirectory().createDirectoryAndParents()

        runSymlinkAction(PathFragment.create("/some/path"), outputArtifact)

        assertThat(outputArtifact.getPath().isSymbolicLink()).isTrue()
        assertThat(outputArtifact.getPath().readSymbolicLink())
            .isEqualTo(PathFragment.create("/some/path"))

        Mockito.verify<SpiedFileSystem?>(fs)
            .createSymbolicLink(
                outputArtifact.getPath().asFragment(),
                PathFragment.create("/some/path"),
                SymlinkTargetType.UNSPECIFIED
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec(@TestParameter useExecRootForSource: Boolean) {
        val inputArtifact: Artifact = getSourceArtifact("input")
        val outputArtifact: Artifact.DerivedArtifact = getBinArtifactWithNoOwner("output")
        outputArtifact.setGeneratingActionKey(ActionsTestUtil.Companion.NULL_ACTION_LOOKUP_DATA)

        val action: SymlinkAction? =
            SymlinkAction.toArtifact(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                inputArtifact,
                outputArtifact,
                "Test symlink action",
                useExecRootForSource
            )

        SerializationTester(action)
            .addDependency(FileSystem::class.java, scratch.getFileSystem())
            .addDependency(Root.RootCodecDependencies::class.java, RootCodecDependencies(root))
            .addDependencies(SerializationDepsUtils.SERIALIZATION_DEPS_FOR_TEST)
            .setVerificationFunction(
                { `in`, out ->
                    val inAction: SymlinkAction = `in` as SymlinkAction
                    val outAction: SymlinkAction = out as SymlinkAction
                    assertThat(inAction.getPrimaryInput().getFilename())
                        .isEqualTo(outAction.getPrimaryInput().getFilename())
                    assertThat(inAction.getPrimaryOutput().getFilename())
                        .isEqualTo(outAction.getPrimaryOutput().getFilename())
                    assertThat(inAction.getOwner()).isEqualTo(outAction.getOwner())
                    assertThat(inAction.getProgressMessage()).isEqualTo(outAction.getProgressMessage())
                })
            .runTests()
    }

    @Throws(java.lang.Exception::class)
    private fun runSymlinkAction(
        inputArtifact: Artifact?, outputArtifact: Artifact?, useExecRootForSource: Boolean
    ) {
        val action: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            SymlinkAction.toArtifact(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                inputArtifact,
                outputArtifact,
                "Test symlink action",
                useExecRootForSource
            )

        assertThat(action.getInputs().toList()).containsExactly(inputArtifact)
        assertThat(action.getOutputs()).containsExactly(outputArtifact)
        assertThat(action.getProgressMessage()).isEqualTo("Test symlink action")

        execute(action)
    }

    @Throws(java.lang.Exception::class)
    private fun runSymlinkAction(inputArtifact: Artifact?, outputArtifact: Artifact?) {
        val action: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            SymlinkAction.toArtifact(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                inputArtifact,
                outputArtifact,
                "Test symlink action",  /* useExecRootForSource= */
                false
            )

        assertThat(action.getInputs().toList()).containsExactly(inputArtifact)
        assertThat(action.getOutputs()).containsExactly(outputArtifact)
        assertThat(action.getProgressMessage()).isEqualTo("Test symlink action")

        execute(action)
    }

    @Throws(java.lang.Exception::class)
    private fun runSymlinkAction(absolutePath: PathFragment?, outputArtifact: Artifact?) {
        val action: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            SymlinkAction.toAbsolutePath(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER, absolutePath, outputArtifact, "Test symlink action"
            )

        assertThat(action.getInputs().toList()).isEmpty()
        assertThat(action.getOutputs()).containsExactly(outputArtifact)
        assertThat(action.getProgressMessage()).isEqualTo("Test symlink action")

        execute(action)
    }

    @Throws(java.lang.Exception::class)
    private fun execute(action: SymlinkAction) {
        val actionResult: ActionResult =
            action.execute(
                ActionExecutionContext(
                    executor,
                    createInputMetadataProvider(action.getInputs().toList()),
                    ActionInputPrefetcher.NONE,
                    actionKeyContext,
                    < T > mock < T ? > (OutputMetadataStore::class.java),  /* rewindingEnabled= */
                false,
                LostInputsCheck.NONE,  /* fileOutErr= */
                null,
                StoredEventHandler(),  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* actionFileSystem= */
                null,
                DiscoveredModulesPruner.DEFAULT,
                SyscallCache.NO_CACHE,
                ThreadStateReceiver.NULL_INSTANCE
            ))

        assertThat(actionResult.spawnResults()).isEmpty()
    }

    companion object {
        @Throws(IOException::class)
        private fun createInputMetadataProvider(inputs: Iterable<Artifact>): InputMetadataProvider {
            val inputMap: ActionInputMap = ActionInputMap(1)
            for (input in inputs) {
                if (input.isTreeArtifact()) {
                    inputMap.putTreeArtifact(input, TreeArtifactValue.empty())
                } else {
                    inputMap.put(input, FileArtifactValue.createForTesting(input))
                }
            }
            return inputMap
        }
    }
}
