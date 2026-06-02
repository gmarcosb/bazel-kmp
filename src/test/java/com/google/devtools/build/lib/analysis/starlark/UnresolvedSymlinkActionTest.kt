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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.actions.ActionExecutionContext

/** Tests [UnresolvedSymlinkAction].  */
@RunWith(JUnit4::class)
class UnresolvedSymlinkActionTest : BuildViewTestCase() {
    private var output: Path? = null
    private var outputArtifact: Artifact.DerivedArtifact? = null
    private var action: UnresolvedSymlinkAction? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        val binDir: ArtifactRoot = targetConfig.getBinDirectory(RepositoryName.MAIN)
        outputArtifact =
            SpecialArtifact.create(
                binDir,
                binDir.getExecPath().getRelative("symlink"),
                ActionsTestUtil.NULL_ARTIFACT_OWNER,
                SpecialArtifactType.UNRESOLVED_SYMLINK
            )
        outputArtifact.setGeneratingActionKey(ActionsTestUtil.NULL_ACTION_LOOKUP_DATA)
        output = outputArtifact.getPath()
        output.getParentDirectory().createDirectoryAndParents()
        action =
            UnresolvedSymlinkAction.create(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                outputArtifact,
                "../some/relative/path",
                SymlinkTargetType.UNSPECIFIED,
                "Creating unresolved symlink"
            )
    }

    @org.junit.Test
    fun testInputsAreEmpty() {
        assertThat(action.getInputs().toList()).isEmpty()
    }

    @org.junit.Test
    fun testOutputArtifactIsOutput() {
        assertThat(action.getOutputs()).containsExactly(outputArtifact)
    }

    @org.junit.Test
    fun testTargetAffectsKey() {
        val action1: UnresolvedSymlinkAction =
            UnresolvedSymlinkAction.create(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                outputArtifact,
                "some/path",
                SymlinkTargetType.UNSPECIFIED,
                "Creating unresolved symlink"
            )
        val action2: UnresolvedSymlinkAction =
            UnresolvedSymlinkAction.create(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                outputArtifact,
                "some/other/path",
                SymlinkTargetType.UNSPECIFIED,
                "Creating unresolved symlink"
            )

        Truth.assertThat(computeKey(action1)).isNotEqualTo(computeKey(action2))
    }

    @org.junit.Test
    fun testTargetTypeAffectsKey() {
        val action1: UnresolvedSymlinkAction =
            UnresolvedSymlinkAction.create(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                outputArtifact,
                "some/path",
                SymlinkTargetType.UNSPECIFIED,
                "Creating unresolved symlink"
            )
        val action2: UnresolvedSymlinkAction =
            UnresolvedSymlinkAction.create(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                outputArtifact,
                "some/path",
                SymlinkTargetType.FILE,
                "Creating unresolved symlink"
            )
        val action3: UnresolvedSymlinkAction =
            UnresolvedSymlinkAction.create(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                outputArtifact,
                "some/path",
                SymlinkTargetType.DIRECTORY,
                "Creating unresolved symlink"
            )

        Truth.assertThat(computeKey(action1)).isNotEqualTo(computeKey(action2))
        Truth.assertThat(computeKey(action2)).isNotEqualTo(computeKey(action3))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlink() {
        val executor: Executor? = TestExecutorBuilder(fileSystem, directories).build()
        val actionResult: ActionResult =
            action.execute(
                ActionExecutionContext(
                    executor,  /* inputMetadataProvider= */
                    null,
                    ActionInputPrefetcher.NONE,
                    actionKeyContext,  /* outputMetadataStore= */
                    null,  /* rewindingEnabled= */
                    false,
                    LostInputsCheck.NONE,  /* fileOutErr= */
                    null,
                    StoredEventHandler(),  /* clientEnv= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* actionFileSystem= */
                    null,
                    DiscoveredModulesPruner.DEFAULT,
                    SyscallCache.NO_CACHE,
                    ThreadStateReceiver.NULL_INSTANCE
                )
            )
        assertThat(actionResult.spawnResults()).isEmpty()
        assertThat(output.isSymbolicLink()).isTrue()
        assertThat(output.readSymbolicLink()).isEqualTo(PathFragment.create("../some/relative/path"))
        assertThat(action.getPrimaryInput()).isNull()
        assertThat(action.getPrimaryOutput()).isEqualTo(outputArtifact)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        SerializationTester(action)
            .addDependency(FileSystem::class.java, scratch.getFileSystem())
            .addDependency(Root.RootCodecDependencies::class.java, RootCodecDependencies(root))
            .addDependencies(SerializationDepsUtils.SERIALIZATION_DEPS_FOR_TEST)
            .setVerificationFunction(
                { `in`, out ->
                    val inAction: UnresolvedSymlinkAction = `in` as UnresolvedSymlinkAction
                    val outAction: UnresolvedSymlinkAction = out as UnresolvedSymlinkAction
                    assertThat(inAction.getPrimaryInput()).isEqualTo(outAction.getPrimaryInput())
                    assertThat(inAction.getPrimaryOutput().getFilename())
                        .isEqualTo(outAction.getPrimaryOutput().getFilename())
                    assertThat(inAction.getOwner()).isEqualTo(outAction.getOwner())
                    assertThat(inAction.getProgressMessage()).isEqualTo(outAction.getProgressMessage())
                })
            .runTests()
    }

    private fun computeKey(action: UnresolvedSymlinkAction): String {
        val fp: Fingerprint = Fingerprint()
        action.computeKey(actionKeyContext,  /* inputMetadataProvider= */null, fp)
        return fp.hexDigestAndReset()
    }
}
