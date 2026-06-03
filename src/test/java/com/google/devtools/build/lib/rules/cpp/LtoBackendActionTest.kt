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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper.dumpStructureWithEquivalenceReduction

/** Tests [com.google.devtools.build.lib.rules.cpp.LtoBackendAction].  */
@RunWith(JUnit4::class)
class LtoBackendActionTest : BuildViewTestCase() {
    private var bitcode1Artifact: Artifact? = null
    private var bitcode2Artifact: Artifact? = null
    private var index1Artifact: Artifact? = null
    private var index2Artifact: Artifact? = null
    private var imports1Artifact: Artifact? = null
    private var imports2Artifact: Artifact? = null
    private var destinationArtifact: Artifact.DerivedArtifact? = null
    private var allBitcodeFiles: BitcodeFiles? = null
    private var collectingAnalysisEnvironment: CollectingAnalysisEnvironment? = null
    private var context: ActionExecutionContext? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createArtifacts() {
        collectingAnalysisEnvironment =
            CollectingAnalysisEnvironment(testAnalysisEnvironment)
        bitcode1Artifact = getSourceArtifact("bitcode1.o")
        bitcode2Artifact = getSourceArtifact("bitcode2.o")
        index1Artifact = getSourceArtifact("bitcode1.thinlto.bc")
        index2Artifact = getSourceArtifact("bitcode2.thinlto.bc")
        scratch.file("bitcode1.imports")
        scratch.file("bitcode2.imports", "bitcode1.o")
        imports1Artifact = getSourceArtifact("bitcode1.imports")
        imports2Artifact = getSourceArtifact("bitcode2.imports")
        destinationArtifact = getBinArtifactWithNoOwner("output")
        allBitcodeFiles =
            BitcodeFiles(
                NestedSetBuilder.create(Order.STABLE_ORDER, bitcode1Artifact, bitcode2Artifact)
            )
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun createExecutorAndContext() {
        val executor: Executor = TestExecutorBuilder(fileSystem, directories).build()
        context =
            ActionExecutionContext(
                executor,  /* inputMetadataProvider= */
                null,
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

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyImports() {
        val action: LtoBackendAction =
            LtoBackendAction.create(
                ActionsTestUtil.NULL_ACTION_OWNER,
                targetConfig,
                NestedSetBuilder.create(Order.STABLE_ORDER, bitcode1Artifact, index1Artifact),
                allBitcodeFiles,
                imports1Artifact,
                com.google.common.collect.ImmutableSet.of<E?>(destinationArtifact),
                CommandLines.builder()
                    .addSingleArgument(scratch.file("/bin/clang").asFragment())
                    .build(),
                ActionEnvironment.create(com.google.common.collect.ImmutableMap.of<K?, V?>())
            )

        collectingAnalysisEnvironment.registerAction(action)
        assertThat(action.getOwner().getLabel())
            .isEqualTo(ActionsTestUtil.NULL_ACTION_OWNER.getLabel())
        assertThat(action.getInputs().toList()).containsExactly(bitcode1Artifact, index1Artifact)
        assertThat(action.getOutputs()).containsExactly(destinationArtifact)
        assertThat(action.getSpawnForTesting().getLocalResources())
            .isEqualTo(AbstractAction.DEFAULT_RESOURCE_SET)
        assertThat(action.getArguments()).containsExactly("/bin/clang")
        assertThat(action.getProgressMessage()).isEqualTo("LTO Backend Compile output")
        assertThat(action.inputsKnown()).isFalse()

        // Discover inputs, which should not add any inputs since bitcode1.imports is empty.
        action.discoverInputs(context)
        assertThat(action.inputsKnown()).isTrue()
        assertThat(action.getInputs().toList()).containsExactly(bitcode1Artifact, index1Artifact)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonEmptyImports() {
        val action: LtoBackendAction =
            LtoBackendAction.create(
                ActionsTestUtil.NULL_ACTION_OWNER,
                targetConfig,
                NestedSetBuilder.create(Order.STABLE_ORDER, bitcode2Artifact, index2Artifact),
                allBitcodeFiles,
                imports2Artifact,
                com.google.common.collect.ImmutableSet.of<E?>(destinationArtifact),
                CommandLines.builder()
                    .addSingleArgument(scratch.file("/bin/clang").asFragment())
                    .build(),
                ActionEnvironment.create(com.google.common.collect.ImmutableMap.of<K?, V?>())
            )
        collectingAnalysisEnvironment.registerAction(action)
        assertThat(action.getOwner().getLabel())
            .isEqualTo(ActionsTestUtil.NULL_ACTION_OWNER.getLabel())
        assertThat(action.getInputs().toList()).containsExactly(bitcode2Artifact, index2Artifact)
        assertThat(action.getOutputs()).containsExactly(destinationArtifact)
        assertThat(action.getSpawnForTesting().getLocalResources())
            .isEqualTo(AbstractAction.DEFAULT_RESOURCE_SET)
        assertThat(action.getArguments()).containsExactly("/bin/clang")
        assertThat(action.getProgressMessage()).isEqualTo("LTO Backend Compile output")
        assertThat(action.inputsKnown()).isFalse()

        // Discover inputs, which should add bitcode1.o which is listed in bitcode2.imports.
        action.discoverInputs(context)
        assertThat(action.inputsKnown()).isTrue()
        assertThat(action.getInputs().toList())
            .containsExactly(bitcode1Artifact, bitcode2Artifact, index2Artifact)
    }

    private enum class KeyAttributes {
        EXECUTABLE,
        IMPORTS_INFO,
        INPUT,
        FIXED_ENVIRONMENT
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testComputeKey() {
        val artifactA: Artifact = getSourceArtifact("a")
        val artifactB: Artifact = getSourceArtifact("b")
        val artifactAimports: Artifact = getSourceArtifact("a.imports")
        val artifactBimports: Artifact = getSourceArtifact("b.imports")

        ActionTester.runTest(
            KeyAttributes::class.java,
            object : ActionCombinationFactory<KeyAttributes?>() {
                override fun generate(attributesToFlip: com.google.common.collect.ImmutableSet<KeyAttributes?>): Action {
                    val executable: PathFragment? =
                        if (attributesToFlip.contains(KeyAttributes.EXECUTABLE))
                            artifactA.getExecPath()
                        else
                            artifactB.getExecPath()

                    val imports: Artifact?
                    if (attributesToFlip.contains(KeyAttributes.IMPORTS_INFO)) {
                        imports = artifactAimports
                    } else {
                        imports = artifactBimports
                    }

                    val input: Artifact?
                    if (attributesToFlip.contains(KeyAttributes.INPUT)) {
                        input = artifactA
                    } else {
                        input = artifactB
                    }

                    val env: MutableMap<String?, String?> = HashMap<String?, String?>()
                    if (attributesToFlip.contains(KeyAttributes.FIXED_ENVIRONMENT)) {
                        env.put("foo", "bar")
                    }

                    val action: SpawnAction =
                        LtoBackendAction.create(
                            ActionsTestUtil.NULL_ACTION_OWNER,
                            targetConfig,
                            NestedSetBuilder.create(Order.STABLE_ORDER, imports, input),
                            BitcodeFiles(NestedSetBuilder.create(Order.STABLE_ORDER)),
                            imports,
                            com.google.common.collect.ImmutableSet.of<E?>(destinationArtifact),
                            CommandLines.builder().addSingleArgument(executable).build(),
                            ActionEnvironment.create(
                                com.google.common.collect.ImmutableMap.< K,
                                V > copyOf<K?, V?>(env)
                            )
                        )
                    collectingAnalysisEnvironment.registerAction(action)
                    return action
                }
            },
            actionKeyContext
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun discoverInputs_missingInputErrorMessage() {
        FileSystemUtils.writeIsoLatin1(imports1Artifact.getPath(), "file1.o", "file2.o", "file3.o")

        val index1Artifact: Artifact = getSourceArtifact("file2.o")
        val action: LtoBackendAction =
            LtoBackendAction.create(
                ActionsTestUtil.NULL_ACTION_OWNER,
                targetConfig,
                NestedSetBuilder.create(Order.STABLE_ORDER, imports1Artifact, index1Artifact),
                BitcodeFiles(NestedSetBuilder.create(Order.STABLE_ORDER, index1Artifact)),
                imports1Artifact,
                com.google.common.collect.ImmutableSet.of<E?>(destinationArtifact),
                CommandLines.builder()
                    .addSingleArgument(scratch.file("/bin/clang").asFragment())
                    .build(),
                ActionEnvironment.create(com.google.common.collect.ImmutableMap.of<K?, V?>())
            )

        val e: ActionExecutionException? =
            org.junit.Assert.assertThrows<T?>(
                ActionExecutionException::class.java,
                org.junit.function.ThrowingRunnable { action.discoverInputs(context) })

        assertThat(e).hasMessageThat().endsWith("(first 10): file1.o, file3.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serializationRoundTrip_resetsInputs() {
        val action: LtoBackendAction =
            LtoBackendAction.create(
                ActionsTestUtil.NULL_ACTION_OWNER,
                targetConfig,
                NestedSetBuilder.create(
                    Order.STABLE_ORDER, bitcode2Artifact, index2Artifact, imports2Artifact
                ),
                allBitcodeFiles,
                imports2Artifact,
                com.google.common.collect.ImmutableSet.of<E?>(destinationArtifact),
                CommandLines.builder()
                    .addSingleArgument(scratch.file("/bin/clang").asFragment())
                    .build(),
                ActionEnvironment.create(com.google.common.collect.ImmutableMap.of<K?, V?>())
            )

        destinationArtifact.setGeneratingActionKey(ActionsTestUtil.NULL_ACTION_LOOKUP_DATA)
        ensureMemoizedIsInitializedIsSet(action)

        val originalStructure: String? = dumpStructureWithEquivalenceReduction(action)

        val originalInputs: com.google.common.collect.ImmutableList<Artifact?>? = action.getInputs().toList()
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            action.discoverInputs(context)
        assertThat(action.getInputs().toList()).isNotEqualTo(originalInputs)

        SerializationTester(action)
            .makeMemoizingAndAllowFutureBlocking( /* allowFutureBlocking= */true)
            .addCodec(ArrayCodec.forComponentType(Artifact::class.java))
            .setVerificationFunction(
                { unusedInput, deserialized ->
                    assertThat(dumpStructureWithEquivalenceReduction(deserialized))
                        .isEqualTo(originalStructure)
                })
            .addDependencies(getCommonSerializationDependencies())
            .addDependencies(SerializationDepsUtils.SERIALIZATION_DEPS_FOR_TEST)
            .runTests()
    }
}
