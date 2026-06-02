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

/** Test cases for [FileWriteAction].  */
abstract class FileWriteActionTestCase : BuildViewTestCase() {
    private var action: Action? = null
    private var outputArtifact: Artifact? = null
    private var output: Path? = null
    private var executor: Executor? = null
    protected var context: ActionExecutionContext? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createAction() {
        outputArtifact = getBinArtifactWithNoOwner("destination.txt")
        output = outputArtifact.getPath()
        output.getParentDirectory().createDirectoryAndParents()
        action = createAction(ActionsTestUtil.Companion.NULL_ACTION_OWNER, outputArtifact, "Hello World", false)
    }

    protected abstract fun createAction(
        actionOwner: ActionOwner?, outputArtifact: Artifact?, data: String?, makeExecutable: Boolean
    ): Action

    @Before
    @Throws(java.lang.Exception::class)
    fun createExecutorAndContext() {
        executor = TestExecutorBuilder(fileSystem, directories).build()
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

    protected fun checkNoInputsByDefault() {
        assertThat(action.getInputs().toList()).isEmpty()
        assertThat(action.getPrimaryInput()).isNull()
    }

    protected fun checkDestinationArtifactIsOutput() {
        val outputs: MutableCollection<Artifact?> = action.getOutputs()
        Truth.assertThat(HashSet<Artifact?>(outputs))
            .isEqualTo(com.google.common.collect.Sets.newHashSet(outputArtifact))
        assertThat(action.getPrimaryOutput()).isEqualTo(outputArtifact)
    }

    @Throws(java.lang.Exception::class)
    protected fun checkCanWriteNonExecutableFile() {
        val actionResult: ActionResult = action.execute(context)
        assertThat(actionResult.spawnResults()).isEmpty()
        val content = String(FileSystemUtils.readContentAsLatin1(output))
        Truth.assertThat(content).isEqualTo("Hello World")
        assertThat(output.isExecutable()).isFalse()
    }

    @Throws(java.lang.Exception::class)
    protected fun checkCanWriteExecutableFile() {
        val outputArtifact: Artifact = getBinArtifactWithNoOwner("hello")
        val output: Path = outputArtifact.getPath()
        val action: Action =
            createAction(ActionsTestUtil.Companion.NULL_ACTION_OWNER, outputArtifact, "echo 'Hello World'", true)
        val actionResult: ActionResult = action.execute(context)
        assertThat(actionResult.spawnResults()).isEmpty()
        val content = String(FileSystemUtils.readContentAsLatin1(output))
        Truth.assertThat(content).isEqualTo("echo 'Hello World'")
        assertThat(output.isExecutable()).isTrue()
    }

    private enum class KeyAttributes {
        DATA,
        MAKE_EXECUTABLE
    }

    @Throws(java.lang.Exception::class)
    protected fun checkComputesConsistentKeys() {
        ActionTester.runTest<KeyAttributes?>(
            com.google.devtools.build.lib.analysis.actions.FileWriteActionTestCase.KeyAttributes::class.java,
            object : ActionCombinationFactory<KeyAttributes?> {
                override fun generate(attributesToFlip: com.google.common.collect.ImmutableSet<KeyAttributes?>): Action {
                    return createAction(
                        ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                        outputArtifact,
                        if (attributesToFlip.contains(com.google.devtools.build.lib.analysis.actions.FileWriteActionTestCase.KeyAttributes.DATA)) "0" else "1",
                        attributesToFlip.contains(com.google.devtools.build.lib.analysis.actions.FileWriteActionTestCase.KeyAttributes.MAKE_EXECUTABLE)
                    )
                }
            },
            actionKeyContext
        )
    }
}
