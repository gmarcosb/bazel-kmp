// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.extra

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache
import com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper.dumpStructureWithEquivalenceReduction
import net.starlark.java.syntax.Location
import org.junit.Test

/**
 * Unit tests for ExtraAction class.
 */
@RunWith(JUnit4::class)
class ExtraActionTest : FoundationTestCase() {
    private val actionKeyContext: ActionKeyContext = ActionKeyContext()

    private class SpecifiedInfoAction(info: ExtraActionInfo) : NullAction() {
        private val info: ExtraActionInfo

        init {
            this.info = info
        }

        public override fun getExtraActionInfo(actionKeyContext: ActionKeyContext?): ExtraActionInfo.Builder {
            return info.toBuilder()
        }
    }

    @Test
    @Throws(Exception::class)
    fun testExtraActionInfoAffectsMnemonic() {
        val infoOne: ExtraActionInfo = ExtraActionInfo.newBuilder()
            .setExtension(
                JavaCompileInfo.javaCompileInfo,
                JavaCompileInfo.newBuilder().addSourceFile("one").build()
            )
            .build()

        val infoTwo: ExtraActionInfo = ExtraActionInfo.newBuilder()
            .setExtension(
                JavaCompileInfo.javaCompileInfo,
                JavaCompileInfo.newBuilder().addSourceFile("two").build()
            )
            .build()

        val execRoot: Path? = scratch.getFileSystem().getPath("/")
        val root: ArtifactRoot? = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out")
        val output: Artifact? = ActionsTestUtil.Companion.createArtifact(root, scratch.file("/out/test.out"))
        val actionOne: Action = ExtraActionInfoFileWriteAction(
            ActionsTestUtil.Companion.NULL_ACTION_OWNER, output,
            SpecifiedInfoAction(infoOne)
        )
        val actionTwo: Action = ExtraActionInfoFileWriteAction(
            ActionsTestUtil.Companion.NULL_ACTION_OWNER, output,
            SpecifiedInfoAction(infoTwo)
        )

        assertThat(actionOne.getKey(actionKeyContext,  /* inputMetadataProvider= */null))
            .isNotEqualTo(actionTwo.getKey(actionKeyContext,  /* inputMetadataProvider= */null))
    }

    /**
     * Regression test. The Spawn created for extra actions needs to pass the environment of the extra
     * action by getting the result of SpawnAction.getEnvironment() method instead of relying on the
     * default field value of BaseSpawn.environment.
     */
    @Test
    @Throws(Exception::class)
    fun testEnvironmentPassedOnOverwrite() {
        val execRoot: Path? = scratch.getFileSystem().getPath("/")
        val out: ArtifactRoot? = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out")
        val extraAction: ExtraAction =
            ExtraAction(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                ImmutableSet.of<E?>(
                    ActionsTestUtil.Companion.createArtifact(
                        out,
                        scratch.file("/out/test.out")
                    ) as Artifact.DerivedArtifact?
                ),
                NullAction(),
                false,
                CommandLine.of(ImmutableList.of<E?>("one", "two", "thee")),
                ActionEnvironment.create(ImmutableMap.of<K?, V?>("TEST", "TEST_VALUE")),
                ImmutableMap.of<K?, V?>(),
                "Executing extra action bla bla",
                "bla bla"
            )

        val spawnEnvironment: MutableMap<String?, String?> = HashMap<String?, String?>()
        val fakeSpawnStrategy: SpawnStrategy =
            object : SpawnStrategy() {
                public override fun exec(
                    spawn: Spawn, actionExecutionContext: ActionExecutionContext?
                ): ImmutableList<SpawnResult?> {
                    spawnEnvironment.putAll(spawn.getEnvironment())
                    return ImmutableList.of<SpawnResult?>()
                }

                public override fun canExec(
                    spawn: Spawn?, actionContextRegistry: ActionContext.ActionContextRegistry?
                ): Boolean {
                    return true
                }
            }

        val testExecutor: BlazeExecutor? =
            TestExecutorBuilder(fileSystem, execRoot)
                .addStrategy(fakeSpawnStrategy, "fake")
                .setDefaultStrategies("fake")
                .build()

        val actionResult: ActionResult =
            extraAction.execute(
                ActionExecutionContext(
                    testExecutor,
                    FakeActionInputFileCache(),
                    ActionInputPrefetcher.NONE,
                    actionKeyContext,  /* outputMetadataStore= */
                    null,  /* rewindingEnabled= */
                    false,
                    LostInputsCheck.NONE,  /* fileOutErr= */
                    null,  /* eventHandler= */
                    null,  /* clientEnv= */
                    ImmutableMap.of<K?, V?>(),  /* actionFileSystem= */
                    null,
                    DiscoveredModulesPruner.DEFAULT,
                    SyscallCache.NO_CACHE,
                    ThreadStateReceiver.NULL_INSTANCE
                )
            )
        assertThat(actionResult.spawnResults()).isEmpty()
        Truth.assertThat(spawnEnvironment.get("TEST")).isNotNull()
        Truth.assertThat(spawnEnvironment).containsEntry("TEST", "TEST_VALUE")
    }

    @Test
    @Throws(Exception::class)
    fun testUpdateInputsNotPassedToShadowedAction() {
        val execRoot: Path? = scratch.getFileSystem().getPath("/")
        val out: ArtifactRoot? = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out")
        val src: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/src")))
        val extraIn: Artifact? = ActionsTestUtil.Companion.createArtifact(src, scratch.file("/src/extra.in"))
        val discoveredIn: Artifact? = ActionsTestUtil.Companion.createArtifact(src, scratch.file("/src/discovered.in"))
        val shadowedAction: Action = Mockito.mock<Action>(Action::class.java)
        Mockito.`when`<T?>(shadowedAction.discoversInputs()).thenReturn(true)
        Mockito.`when`<T?>(shadowedAction.getInputs()).thenReturn(NestedSetBuilder.emptySet(Order.STABLE_ORDER))
        Mockito.`when`<T?>(shadowedAction.inputsKnown()).thenReturn(true)
        val extraAction: ExtraAction =
            ExtraAction(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                NestedSetBuilder.create(Order.STABLE_ORDER, extraIn),
                ImmutableSet.of<E?>(
                    ActionsTestUtil.Companion.createArtifact(
                        out,
                        scratch.file("/out/test.out")
                    ) as Artifact.DerivedArtifact?
                ),
                shadowedAction,
                false,
                CommandLine.of(ImmutableList.of<E?>()),
                ActionEnvironment.EMPTY,
                ImmutableMap.of<K?, V?>(),
                "Executing extra action bla bla",
                "bla bla"
            )
        extraAction.updateInputs(NestedSetBuilder.create(Order.STABLE_ORDER, extraIn, discoveredIn))
        Mockito.verify<Any?>(shadowedAction, Mockito.never()).updateInputs(ArgumentMatchers.any<T?>())
    }

    @Test
    @Throws(Exception::class)
    fun testSerializationRoundTrip_resetsInputs() {
        val execRoot: Path? = scratch.getFileSystem().getPath("/")
        val out: ArtifactRoot? = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out")
        val src: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/src")))
        val extraInput: Artifact? = ActionsTestUtil.Companion.createArtifact(src, scratch.file("/src/extra.in"))
        val discoveredInput: Artifact? =
            ActionsTestUtil.Companion.createArtifact(src, scratch.file("/src/discovered.in"))
        val output: Artifact.DerivedArtifact =
            ActionsTestUtil.Companion.createArtifact(out, scratch.file("/out/test.out")) as Artifact.DerivedArtifact
        output.setGeneratingActionKey(ActionsTestUtil.Companion.NULL_ACTION_LOOKUP_DATA)
        // Note that this differs from NULL_ACTION_OWNER in that it has non-empty execProperties, which
        // are important for testing.
        val dummyActionOwner: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionOwner.createDummy(
                ActionsTestUtil.Companion.NULL_LABEL,
                Location("dummy-file", 0, 0),  /* targetKind= */
                "dummy-kind",  /* buildConfigurationMnemonic= */
                "dummy-configuration-mnemonic",  /* configurationChecksum= */
                "dummy-configuration",
                BuildConfigurationEvent(
                    BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
                    BuildEventStreamProtos.BuildEvent.getDefaultInstance()
                ),  /* isToolConfiguration= */
                false,  /* executionPlatform= */
                PlatformInfo.EMPTY_PLATFORM_INFO,  /* aspectDescriptors= */
                ImmutableList.of<E?>(),  /* execProperties= */
                ImmutableMap.of<K?, V?>("property1", "value1", "property2", "value2")
            )
        val extraAction: ExtraAction =
            ExtraAction(
                dummyActionOwner,
                NestedSetBuilder.create(Order.STABLE_ORDER, extraInput),
                ImmutableSet.of<E?>(output),  /* shadowedAction= */
                InputDiscoveringNullAction(),  /* createDummyOutput= */
                false,
                CommandLine.of(ImmutableList.of<E?>()),
                ActionEnvironment.EMPTY,  /* executionInfo= */
                ImmutableMap.of<K?, V?>("xyz", "2", "abc", "1"),
                "Executing extra action bla bla",
                "bla bla"
            )
        ActionsTestUtil.Companion.ensureMemoizedIsInitializedIsSet(extraAction)
        val originalStructure: String? = dumpStructureWithEquivalenceReduction(extraAction)

        extraAction.updateInputs(
            NestedSetBuilder.create(Order.STABLE_ORDER, extraInput, discoveredInput)
        )

        SerializationTester(extraAction)
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
