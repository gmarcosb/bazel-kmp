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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.actions.ActionExecutionContext.LostInputsCheck

/** Test cases for [SymlinkAction] when pointing to executables.  */
@RunWith(JUnit4::class)
class ExecutableSymlinkActionTest {
    private val scratch: Scratch = Scratch()
    private var execRoot: Path? = null
    private var inputRoot: ArtifactRoot? = null
    private var outputRoot: ArtifactRoot? = null
    var outErr: TestFileOutErr? = null
    private var executor: Executor? = null
    private val actionKeyContext: ActionKeyContext = ActionKeyContext()

    @Before
    @Throws(java.lang.Exception::class)
    fun createExecutor() {
        val inputDir: Path = scratch.dir("/in")
        execRoot = scratch.getFileSystem().getPath("/")
        inputRoot =
            ArtifactRoot.asDerivedRoot(
                execRoot, RootType.OUTPUT, inputDir.relativeTo(execRoot).getPathString()
            )
        val outSegment = "out"
        execRoot.getChild(outSegment).createDirectoryAndParents()
        outputRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, outSegment)
        outErr = TestFileOutErr()
        executor = com.google.devtools.build.lib.actions.util.DummyExecutor(scratch.getFileSystem(), inputDir)
    }

    private fun createContext(inputMetadataProvider: InputMetadataProvider?): ActionExecutionContext {
        return ActionExecutionContext(
            executor,
            inputMetadataProvider,
            ActionInputPrefetcher.NONE,
            actionKeyContext,
            < T > mock < T ? > (OutputMetadataStore::class.java),  /* rewindingEnabled= */
        false,
        LostInputsCheck.NONE,
        outErr,  /* eventHandler= */
        null,  /* clientEnv= */
        com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* actionFileSystem= */
        null,
        DiscoveredModulesPruner.DEFAULT,
        SyscallCache.NO_CACHE,
        ThreadStateReceiver.NULL_INSTANCE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimple() {
        val inputFile: Path = inputRoot.getRoot().getRelative("some-file")
        val outputFile: Path = outputRoot.getRoot().getRelative("some-output")
        FileSystemUtils.createEmptyFile(inputFile)
        inputFile.setExecutable( /*executable=*/true)
        val input: Artifact? = ActionsTestUtil.Companion.createArtifact(inputRoot, inputFile)
        val output: Artifact? = ActionsTestUtil.Companion.createArtifact(outputRoot, outputFile)
        val action: SymlinkAction =
            SymlinkAction.toExecutable(ActionsTestUtil.Companion.NULL_ACTION_OWNER, input, output, "progress")

        val inputMetadataProvider: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        inputMetadataProvider.put(input, FileArtifactValue.createForTesting(input))

        val actionResult: ActionResult = action.execute(createContext(inputMetadataProvider))
        assertThat(actionResult.spawnResults()).isEmpty()
        assertThat(outputFile.resolveSymbolicLinks()).isEqualTo(inputFile)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFailIfInputIsNotAFile() {
        val dir: Path = inputRoot.getRoot().getRelative("some-dir")
        dir.createDirectoryAndParents()
        val input: Artifact? = ActionsTestUtil.Companion.createArtifact(inputRoot, dir)
        val output: Artifact? =
            createArtifact(outputRoot, outputRoot.getRoot().getRelative("some-output"))
        val action: SymlinkAction =
            SymlinkAction.toExecutable(ActionsTestUtil.Companion.NULL_ACTION_OWNER, input, output, "progress")
        val inputMetadataProvider: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        inputMetadataProvider.put(input, FileArtifactValue.createForTesting(input))
        val e: ActionExecutionException? =
            org.junit.Assert.assertThrows<T?>(
                ActionExecutionException::class.java,
                org.junit.function.ThrowingRunnable { action.execute(createContext(inputMetadataProvider)) })
        assertThat(e).hasMessageThat().contains("'in/some-dir' is not a file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFailIfInputIsNotExecutable() {
        val file: Path = inputRoot.getRoot().getRelative("some-file")
        FileSystemUtils.createEmptyFile(file)
        file.setExecutable( /*executable=*/false)
        val input: Artifact? = ActionsTestUtil.Companion.createArtifact(inputRoot, file)
        val output: Artifact? =
            createArtifact(outputRoot, outputRoot.getRoot().getRelative("some-output"))
        val action: SymlinkAction =
            SymlinkAction.toExecutable(ActionsTestUtil.Companion.NULL_ACTION_OWNER, input, output, "progress")
        val inputMetadataProvider: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        inputMetadataProvider.put(input, FileArtifactValue.createForTesting(input))
        val e: ActionExecutionException =
            org.junit.Assert.assertThrows<T>(
                ActionExecutionException::class.java,
                org.junit.function.ThrowingRunnable { action.execute(createContext(inputMetadataProvider)) })
        val want = "'in/some-file' is not executable"
        val got: String = e.getMessage()
        Truth.assertWithMessage("got %s, want %s", got, want).that(got.contains(want)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        val file: Path = inputRoot.getRoot().getRelative("some-file")
        FileSystemUtils.createEmptyFile(file)
        file.setExecutable( /*executable=*/false)
        val input: Artifact.DerivedArtifact =
            ActionsTestUtil.Companion.createArtifact(inputRoot, file) as Artifact.DerivedArtifact
        input.setGeneratingActionKey(ActionsTestUtil.Companion.NULL_ACTION_LOOKUP_DATA)
        val output: Artifact.DerivedArtifact =
            createArtifact(
                outputRoot, outputRoot.getRoot().getRelative("some-output")
            ) as Artifact.DerivedArtifact
        output.setGeneratingActionKey(ActionsTestUtil.Companion.NULL_ACTION_LOOKUP_DATA)
        val action: SymlinkAction? =
            SymlinkAction.toExecutable(ActionsTestUtil.Companion.NULL_ACTION_OWNER, input, output, "progress")
        SerializationTester(action)
            .addDependency(FileSystem::class.java, scratch.getFileSystem())
            .addDependency(
                Root.RootCodecDependencies::class.java,
                RootCodecDependencies(Root.absoluteRoot(scratch.getFileSystem()))
            )
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
}
