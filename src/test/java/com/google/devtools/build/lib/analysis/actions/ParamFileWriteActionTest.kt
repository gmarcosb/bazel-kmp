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

import com.google.devtools.build.lib.actions.Action

/** Tests for ParamFileWriteAction.  */
@RunWith(JUnit4::class)
class ParamFileWriteActionTest : BuildViewTestCase() {
    private var rootDir: ArtifactRoot? = null
    private var outputArtifact: Artifact? = null
    private var treeArtifact: SpecialArtifact? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createArtifacts() {
        val execRoot: Path? = scratch.getFileSystem().getPath("/exec")
        rootDir = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out")
        outputArtifact = getBinArtifactWithNoOwner("destination.txt")
        outputArtifact.getPath().getParentDirectory().createDirectoryAndParents()
        treeArtifact = createTreeArtifact("artifact/myTreeFileArtifact")
    }

    @org.junit.Test
    fun testOutputs() {
        val action: Action =
            createParameterFileWriteAction(
                NestedSetBuilder.emptySet(Order.STABLE_ORDER), createNormalCommandLine(), false
            )
        assertThat(Artifact.toRootRelativePaths(action.getOutputs()))
            .containsExactly("destination.txt")
    }

    @org.junit.Test
    fun testInputs() {
        val action: Action =
            createParameterFileWriteAction(
                NestedSetBuilder.create(Order.STABLE_ORDER, treeArtifact),
                createTreeArtifactExpansionCommandLineDefault(),
                false
            )
        assertThat(Artifact.asExecPaths(action.getInputs()))
            .containsExactly("out/artifact/myTreeFileArtifact")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonExecutableOutput() {
        val action: Action =
            createParameterFileWriteAction(
                NestedSetBuilder.emptySet(Order.STABLE_ORDER), createNormalCommandLine(), false
            )
        val context: ActionExecutionContext = actionExecutionContext()
        action.execute(context)
        assertThat(outputArtifact.getPath().isExecutable()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutableOutput() {
        val action: Action =
            createParameterFileWriteAction(
                NestedSetBuilder.emptySet(Order.STABLE_ORDER), createNormalCommandLine(), true
            )
        val context: ActionExecutionContext = actionExecutionContext()
        action.execute(context)
        assertThat(outputArtifact.getPath().isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWriteCommandLineWithoutTreeArtifactExpansion() {
        val action: Action =
            createParameterFileWriteAction(
                NestedSetBuilder.emptySet(Order.STABLE_ORDER), createNormalCommandLine(), false
            )
        val context: ActionExecutionContext = actionExecutionContext()
        val actionResult: ActionResult = action.execute(context)
        assertThat(actionResult.spawnResults()).isEmpty()
        val content = String(FileSystemUtils.readContentAsLatin1(outputArtifact.getPath()))
        Truth.assertThat(content.trim()).isEqualTo("--flag1\n--flag2\n--flag3\nvalue1\nvalue2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWriteCommandLineWithTreeArtifactExpansionDefault() {
        val action: Action =
            createParameterFileWriteAction(
                NestedSetBuilder.create(Order.STABLE_ORDER, treeArtifact),
                createTreeArtifactExpansionCommandLineDefault(),
                false
            )
        val context: ActionExecutionContext = actionExecutionContext()
        val actionResult: ActionResult = action.execute(context)
        assertThat(actionResult.spawnResults()).isEmpty()
        val content = String(FileSystemUtils.readContentAsLatin1(outputArtifact.getPath()))
        Truth.assertThat(content.trim())
            .isEqualTo(
                """
            --flag1
            out/artifact/myTreeFileArtifact/artifacts/treeFileArtifact1
            out/artifact/myTreeFileArtifact/artifacts/treeFileArtifact2
            """.trimIndent()
            )
    }

    private fun createTreeArtifact(rootRelativePath: String?): SpecialArtifact? {
        return createTreeArtifactWithGeneratingAction(
            rootDir, rootDir.getExecPath().getRelative(rootRelativePath)
        )
    }

    private fun createParameterFileWriteAction(
        inputTreeArtifacts: NestedSet<Artifact?>?, commandLine: CommandLine?, executable: Boolean
    ): ParameterFileWriteAction {
        return ParameterFileWriteAction(
            ActionsTestUtil.Companion.NULL_ACTION_OWNER,
            inputTreeArtifacts,
            outputArtifact,
            commandLine,
            ParameterFileType.UNQUOTED,
            executable,
            AbstractFileWriteAction.MNEMONIC,  /* executionInfo= */
            com.google.common.collect.ImmutableMap.of<K?, V?>(),
            CoreOptions.OutputPathsMode.OFF
        )
    }

    private fun createTreeArtifactExpansionCommandLineDefault(): CommandLine {
        return CustomCommandLine.builder()
            .add("--flag1")
            .addExpandedTreeArtifactExecPaths(treeArtifact)
            .build()
    }

    @Throws(java.lang.Exception::class)
    private fun actionExecutionContext(): ActionExecutionContext {
        val child1: TreeFileArtifact? =
            TreeFileArtifact.createTreeOutput(treeArtifact, "artifacts/treeFileArtifact1")
        val child2: TreeFileArtifact? =
            TreeFileArtifact.createTreeOutput(treeArtifact, "artifacts/treeFileArtifact2")

        // We don't need the metadata to test the expansion of a tree artifact into the files in it, so
        // MISSING_FILE_MARKER will do
        val treeArtifactValue: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(treeArtifact)
                .putChild(child1, FileArtifactValue.MISSING_FILE_MARKER)
                .putChild(child2, FileArtifactValue.MISSING_FILE_MARKER)
                .build()

        val fakeActionInputFileCache: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        fakeActionInputFileCache.putTreeArtifact(treeArtifact, treeArtifactValue)

        val executor: Executor? = TestExecutorBuilder(fileSystem, directories).build()
        return ActionExecutionContext(
            executor,
            fakeActionInputFileCache,
            ActionInputPrefetcher.NONE,
            actionKeyContext,  /* outputMetadataStore= */
            null,  /* rewindingEnabled= */
            false,
            LostInputsCheck.NONE,
            FileOutErr(),
            StoredEventHandler(),  /* clientEnv= */
            com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* actionFileSystem= */
            null,
            DiscoveredModulesPruner.DEFAULT,
            SyscallCache.NO_CACHE,
            ThreadStateReceiver.NULL_INSTANCE
        )
    }

    private enum class KeyAttributes {
        COMMANDLINE,
        FILE_TYPE,
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testComputeKey() {
        val outputArtifact: Artifact? = getSourceArtifact("output")
        ActionTester.runTest<KeyAttributes?>(
            com.google.devtools.build.lib.analysis.actions.ParamFileWriteActionTest.KeyAttributes::class.java,
            ActionCombinationFactory { attributesToFlip: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.analysis.actions.ParamFileWriteActionTest.KeyAttributes?>? ->
                val arg =
                    if (attributesToFlip.contains(com.google.devtools.build.lib.analysis.actions.ParamFileWriteActionTest.KeyAttributes.COMMANDLINE)) "foo" else "bar"
                val commandLine: CommandLine? = CommandLine.of(com.google.common.collect.ImmutableList.of<E?>(arg))
                val parameterFileType: ParameterFileType? =
                    if (attributesToFlip.contains(com.google.devtools.build.lib.analysis.actions.ParamFileWriteActionTest.KeyAttributes.FILE_TYPE))
                        ParameterFileType.SHELL_QUOTED
                    else
                        ParameterFileType.UNQUOTED
                ParameterFileWriteAction(
                    ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                    outputArtifact,
                    commandLine,
                    parameterFileType,
                    false
                )
            },
            actionKeyContext
        )
    }

    companion object {
        private fun createNormalCommandLine(): CommandLine {
            return CustomCommandLine.builder()
                .add("--flag1")
                .add("--flag2")
                .addAll("--flag3", com.google.common.collect.ImmutableList.of<E?>("value1", "value2"))
                .build()
        }
    }
}
