// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionKeyContext

/**
 * Tests [SpawnActionTemplate].
 */
@RunWith(JUnit4::class)
class SpawnActionTemplateTest {
    private var root: ArtifactRoot? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setRootDir() {
        val scratch: Scratch = Scratch()
        val execRoot: Path? = scratch.getFileSystem().getPath("/")
        root = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "root")
    }

    @org.junit.Test
    fun testInputAndOutputTreeArtifacts() {
        val actionTemplate: SpawnActionTemplate = createSimpleSpawnActionTemplate()
        assertThat(actionTemplate.getInputs().toList()).containsExactly(createInputTreeArtifact())
        assertThat(actionTemplate.getOutputs()).containsExactly(createOutputTreeArtifact())
    }

    @org.junit.Test
    fun testCommonToolsAndInputs() {
        val inputTreeArtifact: SpecialArtifact = createInputTreeArtifact()
        val outputTreeArtifact: SpecialArtifact = createOutputTreeArtifact()
        val commonInput: Artifact = createDerivedArtifact("common/input")
        val commonTool: Artifact = createDerivedArtifact("common/tool")
        val executable: Artifact = createDerivedArtifact("bin/cp")


        val actionTemplate: SpawnActionTemplate = builder(inputTreeArtifact, outputTreeArtifact)
            .setExecutionInfo(com.google.common.collect.ImmutableMap.of<String?, String?>("local", ""))
            .setExecutable(executable)
            .setCommandLineTemplate(
                createSimpleCommandLineTemplate(inputTreeArtifact, outputTreeArtifact)
            )
            .setOutputPathMapper(IDENTITY_MAPPER)
            .setMnemonics("ActionTemplate", "ExpandedAction")
            .addCommonTools(com.google.common.collect.ImmutableList.of<E?>(commonTool))
            .addCommonInputs(com.google.common.collect.ImmutableList.of<E?>(commonInput))
            .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER)

        assertThat(actionTemplate.getTools().toList()).containsAtLeast(commonTool, executable)
        assertThat(actionTemplate.getInputs().toList())
            .containsAtLeast(commonInput, commonTool, executable)
    }

    @org.junit.Test
    fun testBuilder_outputPathMapperRequired() {
        val inputTreeArtifact: SpecialArtifact = createInputTreeArtifact()
        val outputTreeArtifact: SpecialArtifact = createOutputTreeArtifact()
        val builder: SpawnActionTemplate.Builder = builder(inputTreeArtifact, outputTreeArtifact)
            .setExecutionInfo(com.google.common.collect.ImmutableMap.of<String?, String?>("local", ""))
            .setExecutable(PathFragment.create("/bin/cp"))
            .setCommandLineTemplate(
                createSimpleCommandLineTemplate(inputTreeArtifact, outputTreeArtifact)
            )
            .setMnemonics("ActionTemplate", "ExpandedAction")

        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { builder.build(ActionsTestUtil.Companion.NULL_ACTION_OWNER) })
    }

    @org.junit.Test
    fun testBuilder_executableRequired() {
        val inputTreeArtifact: SpecialArtifact = createInputTreeArtifact()
        val outputTreeArtifact: SpecialArtifact = createOutputTreeArtifact()
        val builder: SpawnActionTemplate.Builder = builder(inputTreeArtifact, outputTreeArtifact)
            .setExecutionInfo(com.google.common.collect.ImmutableMap.of<String?, String?>("local", ""))
            .setOutputPathMapper(IDENTITY_MAPPER)
            .setCommandLineTemplate(
                createSimpleCommandLineTemplate(inputTreeArtifact, outputTreeArtifact)
            )
            .setMnemonics("ActionTemplate", "ExpandedAction")

        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { builder.build(ActionsTestUtil.Companion.NULL_ACTION_OWNER) })
    }

    @org.junit.Test
    fun testBuilder_commandlineTemplateRequired() {
        val inputTreeArtifact: SpecialArtifact = createInputTreeArtifact()
        val outputTreeArtifact: SpecialArtifact = createOutputTreeArtifact()
        val builder: SpawnActionTemplate.Builder = builder(inputTreeArtifact, outputTreeArtifact)
            .setExecutionInfo(com.google.common.collect.ImmutableMap.of<String?, String?>("local", ""))
            .setOutputPathMapper(IDENTITY_MAPPER)
            .setExecutable(PathFragment.create("/bin/cp"))
            .setMnemonics("ActionTemplate", "ExpandedAction")

        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { builder.build(ActionsTestUtil.Companion.NULL_ACTION_OWNER) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getKey_same() {
        val keyContext: ActionKeyContext = ActionKeyContext()
        val inputTreeArtifact: SpecialArtifact = createInputTreeArtifact()
        val outputTreeArtifact: SpecialArtifact = createOutputTreeArtifact()
        val executable: Artifact = createDerivedArtifact("bin/cp")

        // Use two different builders because the same builder would share the underlying
        // SpawnActionBuilder.
        val actionTemplate: SpawnActionTemplate =
            builder(inputTreeArtifact, outputTreeArtifact)
                .setExecutable(executable)
                .setCommandLineTemplate(
                    createSimpleCommandLineTemplate(inputTreeArtifact, outputTreeArtifact)
                )
                .setOutputPathMapper(IDENTITY_MAPPER)
                .setMnemonics("ActionTemplate", "ExpandedAction")
                .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER)
        val actionTemplate2: SpawnActionTemplate =
            builder(inputTreeArtifact, outputTreeArtifact)
                .setExecutable(executable)
                .setCommandLineTemplate(
                    createSimpleCommandLineTemplate(inputTreeArtifact, outputTreeArtifact)
                )
                .setOutputPathMapper(IDENTITY_MAPPER)
                .setMnemonics("ActionTemplate", "ExpandedAction")
                .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER)
        assertThat(actionTemplate2.getKey(keyContext,  /* inputMetadataProvider= */null))
            .isEqualTo(actionTemplate.getKey(keyContext,  /* inputMetadataProvider= */null))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getKey_differs() {
        val keyContext: ActionKeyContext = ActionKeyContext()
        val inputTreeArtifact: SpecialArtifact = createInputTreeArtifact()
        val outputTreeArtifact: SpecialArtifact = createOutputTreeArtifact()
        val executable: Artifact = createDerivedArtifact("bin/cp")

        // Use two different builders because the same builder would share the underlying
        // SpawnActionBuilder.
        val actionTemplate: SpawnActionTemplate =
            builder(inputTreeArtifact, outputTreeArtifact)
                .setExecutable(executable)
                .setCommandLineTemplate(
                    createSimpleCommandLineTemplate(inputTreeArtifact, outputTreeArtifact)
                )
                .setOutputPathMapper(IDENTITY_MAPPER)
                .setMnemonics("ActionTemplate", "ExpandedAction")
                .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER)
        val actionTemplate2: SpawnActionTemplate =
            builder(inputTreeArtifact, outputTreeArtifact)
                .setExecutable(executable)
                .setCommandLineTemplate(
                    createSimpleCommandLineTemplate(inputTreeArtifact, outputTreeArtifact)
                )
                .setOutputPathMapper(IDENTITY_MAPPER)
                .setMnemonics("ActionTemplate", "ExpandedAction2")
                .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER)
        assertThat(actionTemplate2.getKey(keyContext,  /* inputMetadataProvider= */null))
            .isNotEqualTo(actionTemplate.getKey(keyContext,  /* inputMetadataProvider= */null))
    }

    @org.junit.Test
    fun testExpandedAction_inputAndOutputTreeFileArtifacts() {
        val actionTemplate: SpawnActionTemplate = createSimpleSpawnActionTemplate()
        val inputTreeArtifact: SpecialArtifact = createInputTreeArtifact()
        val outputTreeArtifact: SpecialArtifact = createOutputTreeArtifact()

        val inputTreeFileArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact?> =
            createInputTreeFileArtifacts(inputTreeArtifact)

        val expandedActions: MutableList<SpawnAction?>? =
            actionTemplate.generateActionsForInputArtifacts(
                inputTreeFileArtifacts,
                ActionsTestUtil.Companion.NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER,
                NullEventHandler.INSTANCE
            )

        Truth.assertThat(expandedActions).hasSize(3)

        for (i in expandedActions.indices) {
            val baseName = "child" + i
            assertThat(expandedActions!!.get(i).getInputs().toList())
                .containsExactly(
                    TreeFileArtifact.createTreeOutput(inputTreeArtifact, "children/" + baseName)
                )
            assertThat(expandedActions.get(i).getOutputs())
                .containsExactly(
                    TreeFileArtifact.createTemplateExpansionOutput(
                        outputTreeArtifact,
                        "children/" + baseName,
                        ActionsTestUtil.Companion.NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER
                    )
                )
        }
    }

    @org.junit.Test
    fun testExpandedAction_commonToolsAndInputs() {
        val inputTreeArtifact: SpecialArtifact = createInputTreeArtifact()
        val outputTreeArtifact: SpecialArtifact = createOutputTreeArtifact()
        val commonInput: Artifact = createDerivedArtifact("common/input")
        val commonTool: Artifact = createDerivedArtifact("common/tool")
        val executable: Artifact = createDerivedArtifact("bin/cp")

        val actionTemplate: SpawnActionTemplate =
            builder(inputTreeArtifact, outputTreeArtifact)
                .setExecutionInfo(com.google.common.collect.ImmutableMap.of<K?, V?>("local", ""))
                .setExecutable(executable)
                .setCommandLineTemplate(
                    createSimpleCommandLineTemplate(inputTreeArtifact, outputTreeArtifact)
                )
                .setOutputPathMapper(IDENTITY_MAPPER)
                .setMnemonics("ActionTemplate", "ExpandedAction")
                .addCommonTools(com.google.common.collect.ImmutableList.of<E?>(commonTool))
                .addCommonInputs(com.google.common.collect.ImmutableList.of<E?>(commonInput))
                .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER)

        val inputTreeFileArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact?> =
            createInputTreeFileArtifacts(inputTreeArtifact)
        val expandedActions: MutableList<SpawnAction?> =
            actionTemplate.generateActionsForInputArtifacts(
                inputTreeFileArtifacts,
                ActionsTestUtil.Companion.NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER,
                NullEventHandler.INSTANCE
            )

        for (i in expandedActions.indices) {
            assertThat(expandedActions.get(i).getInputs().toList())
                .containsAtLeast(commonInput, commonTool, executable)
            assertThat(expandedActions.get(i).getTools().toList())
                .containsAtLeast(commonTool, executable)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedAction_arguments() {
        val actionTemplate: SpawnActionTemplate = createSimpleSpawnActionTemplate()
        val inputTreeArtifact: SpecialArtifact = createInputTreeArtifact()
        val outputTreeArtifact: SpecialArtifact = createOutputTreeArtifact()

        val inputTreeFileArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact?> =
            createInputTreeFileArtifacts(inputTreeArtifact)

        val expandedActions: MutableList<SpawnAction?>? =
            actionTemplate.generateActionsForInputArtifacts(
                inputTreeFileArtifacts,
                ActionsTestUtil.Companion.NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER,
                NullEventHandler.INSTANCE
            )

        Truth.assertThat(expandedActions).hasSize(3)

        for (i in expandedActions.indices) {
            val baseName: String? = java.lang.String.format("child%d", i)
            assertThat(expandedActions!!.get(i).getArguments())
                .containsExactly(
                    "/bin/cp",
                    inputTreeArtifact.getExecPathString() + "/children/" + baseName,
                    outputTreeArtifact.getExecPathString() + "/children/" + baseName
                )
                .inOrder()
        }
    }

    @org.junit.Test
    fun testExpandedAction_executionInfoAndEnvironment() {
        val actionTemplate: SpawnActionTemplate = createSimpleSpawnActionTemplate()
        val inputTreeArtifact: SpecialArtifact = createInputTreeArtifact()
        val inputTreeFileArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact?> =
            createInputTreeFileArtifacts(inputTreeArtifact)

        val expandedActions: MutableList<SpawnAction?>? =
            actionTemplate.generateActionsForInputArtifacts(
                inputTreeFileArtifacts,
                ActionsTestUtil.Companion.NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER,
                NullEventHandler.INSTANCE
            )

        Truth.assertThat(expandedActions).hasSize(3)

        for (i in expandedActions.indices) {
            assertThat(expandedActions!!.get(i).getIncompleteEnvironmentForTesting())
                .containsExactly("env", "value")
            assertThat(expandedActions.get(i).getExecutionInfo()).containsExactly("local", "")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedAction_illegalOutputPath() {
        val inputTreeArtifact: SpecialArtifact = createInputTreeArtifact()
        val outputTreeArtifact: SpecialArtifact = createOutputTreeArtifact()
        val inputTreeFileArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact?> =
            createInputTreeFileArtifacts(inputTreeArtifact)

        val builder: SpawnActionTemplate.Builder = builder(inputTreeArtifact, outputTreeArtifact)
            .setExecutable(PathFragment.create("/bin/cp"))
            .setCommandLineTemplate(
                createSimpleCommandLineTemplate(inputTreeArtifact, outputTreeArtifact)
            )

        var mapper: OutputPathMapper = object : OutputPathMapper() {
            public override fun parentRelativeOutputPath(inputTreeFileArtifact: TreeFileArtifact): PathFragment {
                return PathFragment.create("//absolute/" + inputTreeFileArtifact.getParentRelativePath())
            }
        }

        val actionTemplate: SpawnActionTemplate =
            builder.setOutputPathMapper(mapper).build(ActionsTestUtil.Companion.NULL_ACTION_OWNER)

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            "Absolute output paths not allowed, expected IllegalArgumentException",
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                actionTemplate.generateActionsForInputArtifacts(
                    inputTreeFileArtifacts,
                    ActionsTestUtil.Companion.NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER,
                    NullEventHandler.INSTANCE
                )
            })

        mapper = object : OutputPathMapper() {
            public override fun parentRelativeOutputPath(inputTreeFileArtifact: TreeFileArtifact): PathFragment {
                return PathFragment.create("../" + inputTreeFileArtifact.getParentRelativePath())
            }
        }

        val actionTemplate2: SpawnActionTemplate =
            builder.setOutputPathMapper(mapper).build(ActionsTestUtil.Companion.NULL_ACTION_OWNER)

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            "Output paths containing '..' not allowed, expected IllegalArgumentException",
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                actionTemplate2.generateActionsForInputArtifacts(
                    inputTreeFileArtifacts,
                    ActionsTestUtil.Companion.NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER,
                    NullEventHandler.INSTANCE
                )
            })
    }

    private fun builder(
        inputTreeArtifact: SpecialArtifact?, outputTreeArtifact: SpecialArtifact?
    ): SpawnActionTemplate.Builder {
        return Builder(inputTreeArtifact, outputTreeArtifact)
    }

    private fun createSimpleSpawnActionTemplate(): SpawnActionTemplate {
        val inputTreeArtifact: SpecialArtifact = createInputTreeArtifact()
        val outputTreeArtifact: SpecialArtifact = createOutputTreeArtifact()

        return builder(inputTreeArtifact, outputTreeArtifact)
            .setExecutionInfo(com.google.common.collect.ImmutableMap.of<K?, V?>("local", ""))
            .setEnvironment(com.google.common.collect.ImmutableMap.of<K?, V?>("env", "value"))
            .setExecutable(PathFragment.create("/bin/cp"))
            .setCommandLineTemplate(
                createSimpleCommandLineTemplate(inputTreeArtifact, outputTreeArtifact)
            )
            .setOutputPathMapper(IDENTITY_MAPPER)
            .setMnemonics("ActionTemplate", "ExpandedAction")
            .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER)
    }

    private fun createInputTreeArtifact(): SpecialArtifact {
        return createTreeArtifact("my/inputTree")
    }

    private fun createOutputTreeArtifact(): SpecialArtifact {
        return createTreeArtifact("my/outputTree")
    }

    private fun createTreeArtifact(rootRelativePath: String?): SpecialArtifact {
        val relpath: PathFragment? = PathFragment.create(rootRelativePath)
        val result: SpecialArtifact =
            SpecialArtifact.create(
                root,
                root.getExecPath().getRelative(relpath),
                ActionsTestUtil.Companion.NULL_ARTIFACT_OWNER,
                SpecialArtifactType.TREE
            )
        result.setGeneratingActionKey(ActionsTestUtil.Companion.NULL_ACTION_LOOKUP_DATA)
        return result
    }

    private fun createDerivedArtifact(rootRelativePath: String?): Artifact {
        return ActionsTestUtil.Companion.createArtifact(root, rootRelativePath)
    }

    private fun createSimpleCommandLineTemplate(
        inputTreeArtifact: Artifact?, outputTreeArtifact: Artifact?
    ): CustomCommandLine {
        return CustomCommandLine.builder()
            .addPlaceholderTreeArtifactExecPath(inputTreeArtifact)
            .addPlaceholderTreeArtifactExecPath(outputTreeArtifact)
            .build()
    }

    companion object {
        private val IDENTITY_MAPPER: OutputPathMapper = object : OutputPathMapper() {
            public override fun parentRelativeOutputPath(inputTreeFileArtifact: TreeFileArtifact): PathFragment {
                return inputTreeFileArtifact.getParentRelativePath()
            }
        }

        private fun createInputTreeFileArtifacts(
            inputTreeArtifact: SpecialArtifact?
        ): com.google.common.collect.ImmutableList<TreeFileArtifact?> {
            return com.google.common.collect.ImmutableList.of<E?>(
                TreeFileArtifact.createTreeOutput(inputTreeArtifact, "children/child0"),
                TreeFileArtifact.createTreeOutput(inputTreeArtifact, "children/child1"),
                TreeFileArtifact.createTreeOutput(inputTreeArtifact, "children/child2")
            )
        }
    }
}
