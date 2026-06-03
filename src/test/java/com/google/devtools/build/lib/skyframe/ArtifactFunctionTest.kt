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

/** Tests for [ArtifactFunction].  */ // Doesn't actually need any particular Skyframe, but is only relevant to Skyframe full mode.
@RunWith(JUnit4::class)
class ArtifactFunctionTest : ArtifactFunctionTestCase() {
    @Before
    fun setUp() {
        delegateActionExecutionFunction =
            com.google.devtools.build.lib.skyframe.ArtifactFunctionTest.SimpleActionExecutionFunction()
    }

    @Throws(java.lang.Exception::class)
    private fun assertFileArtifactValueMatches() {
        val output: Artifact = createDerivedArtifact("output")
        val path: Path = output.getPath()
        file(path, "contents")
        assertValueMatches(path.stat(), path.getDigest(), evaluateFileArtifactValue(output))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBasicArtifact() {
        fastDigest = false
        assertFileArtifactValueMatches()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBasicArtifactWithXattr() {
        fastDigest = true
        assertFileArtifactValueMatches()
    }

    @org.junit.Test
    @Throws(Throwable::class)
    fun testMissingNonMandatoryArtifact() {
        val input: Artifact = createSourceArtifact("input1")
        assertThat(evaluateArtifactValue(input)).isNotNull()
    }

    @org.junit.Test
    @Throws(Throwable::class)
    fun testUnreadableInputWithFsWithAvailableDigest() {
        val expectedDigest = byteArrayOf(1, 2, 3, 4)
        setupRoot(
            object : com.google.devtools.build.lib.skyframe.ArtifactFunctionTestCase.CustomInMemoryFs() {
                @Throws(IOException::class)
                public override fun getDigest(path: PathFragment): ByteArray? {
                    return if (path.getBaseName().equals("unreadable")) expectedDigest else super.getDigest(path)
                }
            })

        val input: Artifact = createSourceArtifact("unreadable")
        val inputPath: Path = input.getPath()
        file(inputPath, "dummynotused")
        inputPath.chmod(0)

        val value: FileArtifactValue = evaluateArtifactValue(input) as FileArtifactValue

        val stat: FileStatus = inputPath.stat()
        assertThat(value.getSize()).isEqualTo(stat.size)
        assertThat(value.getDigest()).isEqualTo(expectedDigest)
    }

    /**
     * Tests that ArtifactFunction rethrows a transitive [IOException] as an [ ].
     */
    @org.junit.Test
    @Throws(Throwable::class)
    fun testIOException_endToEnd() {
        val exception: IOException = IOException("beep")
        setupRoot(
            object : com.google.devtools.build.lib.skyframe.ArtifactFunctionTestCase.CustomInMemoryFs() {
                @Throws(IOException::class)
                public override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus {
                    if (path.getBaseName().equals("bad")) {
                        throw exception
                    }
                    return super.statIfFound(path, followSymlinks)
                }
            })
        val sourceArtifact: Artifact = createSourceArtifact("bad")
        val e: SourceArtifactException? =
            org.junit.Assert.assertThrows<T?>(
                SourceArtifactException::class.java,
                org.junit.function.ThrowingRunnable { evaluateArtifactValue(sourceArtifact) })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("error reading file '" + sourceArtifact.getExecPathString() + "': beep")
    }

    @org.junit.Test
    @Throws(Throwable::class)
    fun testActionTreeArtifactOutput() {
        val artifact: SpecialArtifact = createDerivedTreeArtifactWithAction("treeArtifact")
        val treeFileArtifact1: TreeFileArtifact = createFakeTreeFileArtifact(artifact, "child1", "hello1")
        val treeFileArtifact2: TreeFileArtifact = createFakeTreeFileArtifact(artifact, "child2", "hello2")

        val value: TreeArtifactValue = evaluateArtifactValue(artifact) as TreeArtifactValue
        assertThat(value.getChildValues()).containsKey(treeFileArtifact1)
        assertThat(value.getChildValues()).containsKey(treeFileArtifact2)
        assertThat(value.getChildValues().get(treeFileArtifact1).getDigest()).isNotNull()
        assertThat(value.getChildValues().get(treeFileArtifact2).getDigest()).isNotNull()
    }

    @org.junit.Test
    @Throws(Throwable::class)
    fun testSpawnActionTemplate() {
        // artifact1 is a tree artifact generated by normal action.
        val artifact1: SpecialArtifact = createDerivedTreeArtifactWithAction("treeArtifact1")
        createFakeTreeFileArtifact(artifact1, "child1", "hello1")
        createFakeTreeFileArtifact(artifact1, "child2", "hello2")

        // artifact2 is a tree artifact generated by action template.
        val artifact2: SpecialArtifact = createDerivedTreeArtifactOnly("treeArtifact2")
        val actionTemplate: SpawnActionTemplate =
            ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2)
        actions.add(actionTemplate)
        val treeFileArtifact1: TreeFileArtifact =
            createFakeExpansionTreeFileArtifact(actionTemplate, artifact2, "child1", "hello1")
        val treeFileArtifact2: TreeFileArtifact =
            createFakeExpansionTreeFileArtifact(actionTemplate, artifact2, "child2", "hello2")

        val value: TreeArtifactValue = evaluateArtifactValue(artifact2) as TreeArtifactValue
        assertThat(value.getChildValues()).containsKey(treeFileArtifact1)
        assertThat(value.getChildValues()).containsKey(treeFileArtifact2)
        assertThat(value.getChildValues().get(treeFileArtifact1).getDigest()).isNotNull()
        assertThat(value.getChildValues().get(treeFileArtifact2).getDigest()).isNotNull()
    }

    @org.junit.Test
    @Throws(Throwable::class)
    fun testConsecutiveSpawnActionTemplates() {
        // artifact1 is a tree artifact generated by normal action.
        val artifact1: SpecialArtifact = createDerivedTreeArtifactWithAction("treeArtifact1")
        createFakeTreeFileArtifact(artifact1, "child1", "hello1")
        createFakeTreeFileArtifact(artifact1, "child2", "hello2")

        // artifact2 is a tree artifact generated by action template.
        val artifact2: SpecialArtifact = createDerivedTreeArtifactOnly("treeArtifact2")
        val template2: SpawnActionTemplate =
            ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2)
        actions.add(template2)
        createFakeExpansionTreeFileArtifact(template2, artifact2, "child1", "hello1")
        createFakeExpansionTreeFileArtifact(template2, artifact2, "child2", "hello2")

        // artifact3 is a tree artifact generated by action template.
        val artifact3: SpecialArtifact = createDerivedTreeArtifactOnly("treeArtifact3")
        val template3: SpawnActionTemplate =
            ActionsTestUtil.createDummySpawnActionTemplate(artifact2, artifact3)
        actions.add(template3)
        val treeFileArtifact1: TreeFileArtifact =
            createFakeExpansionTreeFileArtifact(template3, artifact3, "child1", "hello1")
        val treeFileArtifact2: TreeFileArtifact =
            createFakeExpansionTreeFileArtifact(template3, artifact3, "child2", "hello2")

        val value: TreeArtifactValue = evaluateArtifactValue(artifact3) as TreeArtifactValue
        assertThat(value.getChildValues()).containsKey(treeFileArtifact1)
        assertThat(value.getChildValues()).containsKey(treeFileArtifact2)
        assertThat(value.getChildValues().get(treeFileArtifact1).getDigest()).isNotNull()
        assertThat(value.getChildValues().get(treeFileArtifact2).getDigest()).isNotNull()
    }

    @org.junit.Test
    @Throws(Throwable::class)
    fun testActionTemplateGeneratesMultipleOutputTreesFromDifferentActions() {
        // `inputTree` is a tree artifact generated by normal action.
        val inputTree: SpecialArtifact = createDerivedTreeArtifactWithAction("treeArtifact1")
        createFakeTreeFileArtifact(inputTree, "child1", "hello1")
        createFakeTreeFileArtifact(inputTree, "child2", "hello2")
        val outputTree1: SpecialArtifact = createDerivedTreeArtifactOnly("treeArtifact2")
        val outputTree2: SpecialArtifact = createDerivedTreeArtifactOnly("treeArtifact3")
        val template: ActionTemplate<DummyAction?> =
            object : TestActionTemplate(
                com.google.common.collect.ImmutableList.of<SpecialArtifact?>(inputTree),
                com.google.common.collect.ImmutableSet.of<SpecialArtifact?>(outputTree1, outputTree2)
            ) {
                public override fun generateActionsForInputArtifacts(
                    inputTreeFileArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact?>?,
                    artifactOwner: ActionLookupKey?,
                    eventHandler: com.google.devtools.build.lib.events.EventHandler?
                ): com.google.common.collect.ImmutableList<DummyAction?> {
                    val actions: com.google.common.collect.ImmutableList.Builder<DummyAction?> =
                        com.google.common.collect.ImmutableList.builder<DummyAction?>()
                    for (outputTree in com.google.common.collect.ImmutableSet.of<Any?>(outputTree1, outputTree2)) {
                        val output: TreeFileArtifact? =
                            TreeFileArtifact.createTemplateExpansionOutput(
                                outputTree, "child", artifactOwner
                            )
                        actions.add(DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output))
                    }
                    return actions.build()
                }
            }
        actions.add(template)
        val treeFileArtifact1: TreeFileArtifact =
            createFakeExpansionTreeFileArtifact(template, outputTree1, "child", "hello")
        val treeFileArtifact2: TreeFileArtifact =
            createFakeExpansionTreeFileArtifact(template, outputTree2, "child", "hello")
        val value: TreeArtifactValue = evaluateArtifactValue(outputTree1) as TreeArtifactValue
        val value2: TreeArtifactValue = evaluateArtifactValue(outputTree2) as TreeArtifactValue

        assertThat(value.getChildValues()).containsKey(treeFileArtifact1)
        assertThat(value2.getChildValues()).containsKey(treeFileArtifact2)
        // The TreeArtifactValue for outputTree1 should not contain the child from outputTree2 and vice
        // versa.
        assertThat(value.getChildValues()).doesNotContainKey(treeFileArtifact2)
        assertThat(value2.getChildValues()).doesNotContainKey(treeFileArtifact1)
        assertThat(value.getChildValues().get(treeFileArtifact1).getDigest()).isNotNull()
        assertThat(value2.getChildValues().get(treeFileArtifact2).getDigest()).isNotNull()
    }

    @org.junit.Test
    @Throws(Throwable::class)
    fun testSubdirectoryArtifactsGetFlattenedIntoTopLevelTreeArtifact() {
        val inputTree: SpecialArtifact = createDerivedTreeArtifactWithAction("inputTree")
        createFakeTreeFileArtifact(inputTree, "child1", "hello1")
        createFakeTreeFileArtifact(inputTree, "child2", "hello2")
        val topLevelTree: SpecialArtifact = createDerivedTreeArtifactOnly("topLevelTree")
        // topLevelTree is a tree artifact generated by action template.
        val template: ActionTemplate<DummyAction?> =
            object : TestActionTemplate(
                com.google.common.collect.ImmutableList.of<SpecialArtifact?>(inputTree),
                com.google.common.collect.ImmutableSet.of<SpecialArtifact?>(topLevelTree)
            ) {
                public override fun generateActionsForInputArtifacts(
                    inputTreeFileArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact?>?,
                    artifactOwner: ActionLookupKey?,
                    eventHandler: com.google.devtools.build.lib.events.EventHandler?
                ): com.google.common.collect.ImmutableList<DummyAction?> {
                    val actions: com.google.common.collect.ImmutableList.Builder<DummyAction?> =
                        com.google.common.collect.ImmutableList.builder<DummyAction?>()
                    actions.add(
                        DummyAction(
                            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                            TreeFileArtifact.createTemplateExpansionOutput(
                                topLevelTree, "file1.txt", artifactOwner
                            )
                        )
                    )
                    actions.add(
                        DummyAction(
                            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                            SpecialArtifact.createSubTreeArtifact(
                                topLevelTree, PathFragment.create("subdir1"), artifactOwner
                            )
                        )
                    )
                    actions.add(
                        DummyAction(
                            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                            SpecialArtifact.createSubTreeArtifact(
                                topLevelTree, PathFragment.create("subdir2"), artifactOwner
                            )
                        )
                    )
                    return actions.build()
                }
            }
        actions.add(template)
        topLevelTree.setGeneratingActionKey(
            ActionLookupData.create(
                ArtifactFunctionTestCase.Companion.ALL_OWNER,
                actions.size - 1
            )
        )

        val file1: TreeFileArtifact =
            createFakeExpansionTreeFileArtifact(template, topLevelTree, "file1.txt", "hello")
        val subTree1: SpecialArtifact = createSubTreeArtifact("subdir1", topLevelTree, 1)
        val file2: TreeFileArtifact = createFakeTreeFileArtifact(subTree1, "child1", "hello1")
        val file3: TreeFileArtifact = createFakeTreeFileArtifact(subTree1, "child2", "hello2")
        val subTree2: SpecialArtifact = createSubTreeArtifact("subdir2", topLevelTree, 2)
        val file4: TreeFileArtifact = createFakeTreeFileArtifact(subTree2, "child1", "hello1")
        val file5: TreeFileArtifact = createFakeTreeFileArtifact(subTree2, "child2", "hello2")

        val topTreeValue: TreeArtifactValue = evaluateArtifactValue(topLevelTree) as TreeArtifactValue
        val subTree1Value: TreeArtifactValue = evaluateArtifactValue(subTree1) as TreeArtifactValue
        val subTree2Value: TreeArtifactValue = evaluateArtifactValue(subTree2) as TreeArtifactValue
        // The top level tree artifact value should contain a flattened view of all the files under it
        // (including the files from its subdirectories).
        assertThat(topTreeValue.getChildren()).containsExactly(file1, file2, file3, file4, file5)
        // Whilst the subtree tree artifact values only contain the files directly under them.
        assertThat(subTree1Value.getChildren()).containsExactly(file2, file3)
        assertThat(subTree2Value.getChildren()).containsExactly(file4, file5)
    }

    private fun createSourceArtifact(path: String?): Artifact {
        return ActionsTestUtil.createArtifactWithExecPath(
            ArtifactRoot.asSourceRoot(Root.fromPath(root)), PathFragment.create(path)
        )
    }

    private fun createDerivedArtifact(path: String?): DerivedArtifact {
        val execPath: PathFragment? = PathFragment.create("out").getRelative(path)
        val output: DerivedArtifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath,
                ArtifactFunctionTestCase.Companion.ALL_OWNER
            )
        actions.add(DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output))
        output.setGeneratingActionKey(
            ActionLookupData.create(
                ArtifactFunctionTestCase.Companion.ALL_OWNER,
                actions.size - 1
            )
        )
        return output
    }

    private fun createDerivedTreeArtifactWithAction(path: String?): SpecialArtifact {
        val treeArtifact: SpecialArtifact = createDerivedTreeArtifactOnly(path)
        actions.add(DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), treeArtifact))
        treeArtifact.setGeneratingActionKey(
            ActionLookupData.create(
                ArtifactFunctionTestCase.Companion.ALL_OWNER,
                actions.size - 1
            )
        )
        return treeArtifact
    }

    private fun createDerivedTreeArtifactOnly(path: String?): SpecialArtifact {
        val execPath: PathFragment? = PathFragment.create("out").getRelative(path)
        return SpecialArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
            execPath,
            ArtifactFunctionTestCase.Companion.ALL_OWNER,
            SpecialArtifactType.TREE
        )
    }

    private fun createSubTreeArtifact(
        path: String?, parent: SpecialArtifact, templateActionIndex: Int
    ): SpecialArtifact {
        val key: ActionTemplateExpansionKey? =
            ActionTemplateExpansionValue.key(
                parent.getArtifactOwner(), parent.getGeneratingActionKey().getActionIndex()
            )
        return SpecialArtifact.createSubTreeArtifact(
            parent, PathFragment.create(path), ActionLookupData.create(key, templateActionIndex)
        )
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.Exception::class)
    private fun createFakeExpansionTreeFileArtifact(
        actionTemplate: ActionTemplate<*>,
        outputTreeArtifact: SpecialArtifact?,
        parentRelativePath: String?,
        content: String?
    ): TreeFileArtifact {
        val actionIndex: Int =
            com.google.common.collect.Iterables.indexOf<ActionAnalysisMetadata?>(actions, actionTemplate::equals)
        com.google.common.base.Preconditions.checkState(actionIndex >= 0, "%s not registered", actionTemplate)
        val treeFileArtifact: TreeFileArtifact =
            TreeFileArtifact.createTemplateExpansionOutput(
                outputTreeArtifact,
                parentRelativePath,
                ActionTemplateExpansionValue.key(ArtifactFunctionTestCase.Companion.ALL_OWNER, actionIndex)
            )
        val path: Path = treeFileArtifact.getPath()
        path.getParentDirectory().createDirectoryAndParents()
        ArtifactFunctionTestCase.Companion.writeFile(path, content)
        return treeFileArtifact
    }

    @Throws(java.lang.Exception::class)
    private fun evaluateFileArtifactValue(artifact: Artifact?): FileArtifactValue? {
        val value: SkyValue? = evaluateArtifactValue(artifact)
        assertThat(value).isInstanceOf(FileArtifactValue::class.java)
        return value as FileArtifactValue?
    }

    @Throws(java.lang.Exception::class)
    private fun evaluateArtifactValue(artifact: Artifact?): SkyValue? {
        val key: SkyKey = Artifact.key(artifact)
        val result: EvaluationResult<SkyValue?> =
            evaluate<SkyValue?>(*com.google.common.collect.ImmutableList.of<Any?>(key).toTypedArray<SkyKey?>())
        if (result.hasError()) {
            throw result.getError().getException()
        }
        val value: SkyValue? = result.get(key)
        if (value is ActionExecutionValue) {
            return value.getExistingFileArtifactValue(artifact)
        }
        return value
    }

    @Throws(
        java.lang.InterruptedException::class,
        ActionConflictException::class,
        Actions.ArtifactGeneratedByOtherRuleException::class
    )
    private fun setGeneratingActions() {
        if (evaluator.getExistingValue(ArtifactFunctionTestCase.Companion.ALL_OWNER) == null) {
            val generatingActions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> =
                com.google.common.collect.ImmutableList.copyOf<ActionAnalysisMetadata?>(actions)
            Actions.assignOwnersAndThrowIfConflictToleratingSharedActions(
                actionKeyContext, generatingActions, ArtifactFunctionTestCase.Companion.ALL_OWNER
            )
            differencer.inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    ArtifactFunctionTestCase.Companion.ALL_OWNER,
                    Delta.justNew(BasicActionLookupValue(generatingActions))
                )
            )
        }
    }

    @Throws(
        java.lang.InterruptedException::class,
        ActionConflictException::class,
        Actions.ArtifactGeneratedByOtherRuleException::class
    )
    private fun <E : SkyValue?> evaluate(vararg keys: SkyKey?): EvaluationResult<E?> {
        setGeneratingActions()
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        return evaluator.evaluate(java.util.Arrays.< T > asList < T ? > (keys), evaluationContext)
    }

    /**
     * Value builder for actions that just stats and stores the output file (which must either be
     * orphaned or exist).
     */
    private class SimpleActionExecutionFunction : SkyFunction {
        @Throws(java.lang.InterruptedException::class)
        public override fun compute(skyKey: SkyKey, env: Environment): SkyValue {
            val artifactData: MutableMap<Artifact?, FileArtifactValue?> = HashMap<Artifact?, FileArtifactValue?>()
            val treeArtifactData: MutableMap<Artifact?, TreeArtifactValue?> = HashMap<Artifact?, TreeArtifactValue?>()
            val actionLookupData: ActionLookupData = skyKey.argument() as ActionLookupData
            val actionLookupValue: ActionLookupValue =
                env.getValue(actionLookupData.getActionLookupKey()) as ActionLookupValue
            val action: Action = actionLookupValue.getAction(actionLookupData.getActionIndex())
            val output: Artifact? = com.google.common.collect.Iterables.getOnlyElement<T?>(action.getOutputs())

            try {
                if (output.isTreeArtifact()) {
                    val parent: SpecialArtifact = output as SpecialArtifact
                    val treeFileArtifact1: TreeFileArtifact? =
                        TreeFileArtifact.createTreeOutput(output as SpecialArtifact, "child1")
                    val treeFileArtifact2: TreeFileArtifact? =
                        TreeFileArtifact.createTreeOutput(output as SpecialArtifact, "child2")
                    val tree: TreeArtifactValue? =
                        TreeArtifactValue.newBuilder(parent)
                            .putChild(
                                treeFileArtifact1, FileArtifactValue.createForTesting(treeFileArtifact1)
                            )
                            .putChild(
                                treeFileArtifact2, FileArtifactValue.createForTesting(treeFileArtifact2)
                            )
                            .build()
                    treeArtifactData.put(output, tree)
                } else if (output.isRunfilesTree()) {
                    artifactData.put(output, FileArtifactValue.RUNFILES_TREE_MARKER)
                } else {
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
                }
            } catch (e: IOException) {
                throw java.lang.IllegalStateException(e)
            }
            return ActionsTestUtil.createActionExecutionValue(
                com.google.common.collect.ImmutableMap.< K,
                V > copyOf<K?, V?>(artifactData),
                com.google.common.collect.ImmutableMap.< K,
                V > copyOf<K?, V?>(treeArtifactData)
            )
        }
    }

    private abstract class TestActionTemplate(
        inputTreeArtifacts: com.google.common.collect.ImmutableList<SpecialArtifact>,
        outputTreeArtifacts: com.google.common.collect.ImmutableSet<SpecialArtifact>
    ) : ActionTemplate<DummyAction?> {
        private val inputTreeArtifacts: com.google.common.collect.ImmutableList<SpecialArtifact>
        private val outputTreeArtifacts: com.google.common.collect.ImmutableSet<SpecialArtifact>

        init {
            for (inputTreeArtifact in inputTreeArtifacts) {
                com.google.common.base.Preconditions.checkArgument(
                    inputTreeArtifact.isTreeArtifact(),
                    inputTreeArtifact
                )
            }
            for (outputTreeArtifact in outputTreeArtifacts) {
                com.google.common.base.Preconditions.checkArgument(
                    outputTreeArtifact.isTreeArtifact(),
                    outputTreeArtifact
                )
            }
            this.inputTreeArtifacts = inputTreeArtifacts
            this.outputTreeArtifacts = outputTreeArtifacts
        }

        public override fun getInputTreeArtifacts(): com.google.common.collect.ImmutableList<SpecialArtifact> {
            return inputTreeArtifacts
        }

        val outputs: com.google.common.collect.ImmutableSet<Artifact?>
            get() = com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (outputTreeArtifacts)

        val owner: ActionOwner?
            get() = ActionsTestUtil.NULL_ACTION_OWNER

        val isShareable: Boolean
            get() = false

        val mnemonic: String
            get() = "TestActionTemplate"

        public override fun getKey(
            actionKeyContext: ActionKeyContext?, inputMetadataProvider: InputMetadataProvider?
        ): String {
            val fp: Fingerprint = Fingerprint()
            for (inputTreeArtifact in inputTreeArtifacts) {
                fp.addPath(inputTreeArtifact.getPath())
            }
            for (outputTreeArtifact in outputTreeArtifacts) {
                fp.addPath(outputTreeArtifact.getPath())
            }
            return fp.hexDigestAndReset()
        }

        public override fun prettyPrint(): String {
            return "TestActionTemplate for " + outputTreeArtifacts
        }

        public override fun describe(): String {
            return prettyPrint()
        }

        val tools: NestedSet<Artifact?>
            get() = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

        val inputs: NestedSet<Artifact?>
            get() = NestedSetBuilder.wrap(Order.STABLE_ORDER, inputTreeArtifacts)

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
            get() = NestedSetBuilder.wrap(Order.STABLE_ORDER, inputTreeArtifacts)

        override fun toString(): String {
            return prettyPrint()
        }
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun file(path: Path, contents: String?) {
            path.getParentDirectory().createDirectoryAndParents()
            ArtifactFunctionTestCase.Companion.writeFile(path, contents)
        }

        @Throws(java.lang.Exception::class)
        private fun createFakeTreeFileArtifact(
            treeArtifact: SpecialArtifact?, parentRelativePath: String?, content: String?
        ): TreeFileArtifact {
            val treeFileArtifact: TreeFileArtifact =
                TreeFileArtifact.createTreeOutput(treeArtifact, parentRelativePath)
            val path: Path = treeFileArtifact.getPath()
            path.getParentDirectory().createDirectoryAndParents()
            ArtifactFunctionTestCase.Companion.writeFile(path, content)
            return treeFileArtifact
        }

        @Throws(IOException::class)
        private fun assertValueMatches(file: FileStatus, digest: ByteArray?, value: FileArtifactValue) {
            assertThat(value.getSize()).isEqualTo(file.size)
            if (digest == null) {
                assertThat(value.getDigest()).isNull()
                assertThat(value.getModifiedTime()).isEqualTo(file.lastModifiedTime)
            } else {
                assertThat(value.getDigest()).isEqualTo(digest)
            }
        }
    }
}
