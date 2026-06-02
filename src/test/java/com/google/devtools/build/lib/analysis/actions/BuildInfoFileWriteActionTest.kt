// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.AbstractAction

/** Tests [BuildInfoFileWriteAction].  */
@RunWith(JUnit4::class)
class BuildInfoFileWriteActionTest : BuildViewTestCase() {
    private var outputFile: Artifact? = null
    private var outputPath: Path? = null
    private var context: ActionExecutionContext? = null
    private var executor: Executor? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createOutputFile() {
        outputFile = getBinArtifactWithNoOwner("output.txt")
        outputPath = outputFile.getPath()
        outputPath.getParentDirectory().createDirectoryAndParents()
    }

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

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun execute_writesToOutputFile() {
        scratch.file(
            "input.txt",  //
            "name test_name",
            "client test_client"
        )
        scratch.file(
            "template.txt",  //
            "#define NAME {NAME}",
            "#define CLIENT {CLIENT}"
        )
        val starlarkFuncObject =
            exec(
                "def t(d):",
                " r = {}",
                " r[\"{NAME}\"] = d[\"name\"] + \"_foo\"",
                " r[\"{CLIENT}\"] = d[\"client\"] + \"_c\"",
                " return r",
                "t"
            )
        val expected = "#define NAME test_name_foo\n" + "#define CLIENT test_client_c\n"

        val action: AbstractAction =
            BuildInfoFileWriteAction(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                getSourceArtifact("input.txt", WorkspaceStatusValue.BUILD_INFO_KEY),
                outputFile,
                starlarkFuncObject as StarlarkFunction?,
                getSourceArtifact("template.txt"),
                false,
                StarlarkSemantics.DEFAULT
            )
        val actionResult: ActionResult = action.execute(context)
        val actual = String(FileSystemUtils.readContentAsLatin1(outputPath))

        assertThat(actionResult.spawnResults()).isEmpty()
        assertThat(action.getOutputs()).containsExactly(outputFile)
        Truth.assertThat(actual).isEqualTo(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun keyMissing_templatePartiallyExpanded() {
        scratch.file(
            "input.txt",  //
            "name test_name",
            "client test_client"
        )
        scratch.file(
            "template.txt",  //
            "#define NAME {NAME}",
            "#define CLIENT {CLIENT_MISSING}"
        )
        val starlarkFuncObject =
            exec(
                "def t(d):",
                " r = {}",
                " r[\"{NAME}\"] = d[\"name\"] + \"_foo\"",
                " r[\"{CLIENT}\"] = d[\"client\"] + \"_c\"",
                " return r",
                "t"
            )
        val expected = "#define NAME test_name_foo\n" + "#define CLIENT {CLIENT_MISSING}\n"

        val action: AbstractAction =
            BuildInfoFileWriteAction(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                getSourceArtifact("input.txt", WorkspaceStatusValue.BUILD_INFO_KEY),
                outputFile,
                starlarkFuncObject as StarlarkFunction?,
                getSourceArtifact("template.txt"),
                false,
                StarlarkSemantics.DEFAULT
            )
        val actionResult: ActionResult = action.execute(context)
        val actual = String(FileSystemUtils.readContentAsLatin1(outputPath))

        assertThat(actionResult.spawnResults()).isEmpty()
        assertThat(action.getOutputs()).containsExactly(outputFile)
        Truth.assertThat(actual).isEqualTo(expected)
    }

    private enum class BuildInfoFileWriteActionAttributes {
        INPUT,
        TEMPLATE,
        IS_VOLATILE,
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionInputsVary_checkComputeKeyResults() {
        scratch.file("input1.txt", "")
        scratch.file("template1.txt", "")
        scratch.file("input2.txt", "")
        scratch.file("template2.txt", "")
        val starlarkFuncObject =
            exec(
                "def t(d):",
                " for i in range(1, 10):",
                "   for j in range (1, 10):",
                "       a = 5",
                " return {}",
                "t"
            )

        ActionTester.runTest<BuildInfoFileWriteActionAttributes?>(
            BuildInfoFileWriteActionAttributes::class.java,
            object : ActionCombinationFactory<BuildInfoFileWriteActionAttributes?> {
                override fun generate(
                    attributesToFlip: com.google.common.collect.ImmutableSet<BuildInfoFileWriteActionAttributes?>
                ): Action? {
                    return BuildInfoFileWriteAction(
                        ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                        if (attributesToFlip.contains(BuildInfoFileWriteActionAttributes.INPUT))
                            getSourceArtifact("input1.txt", WorkspaceStatusValue.BUILD_INFO_KEY)
                        else
                            getSourceArtifact("input2.txt", WorkspaceStatusValue.BUILD_INFO_KEY),
                        outputFile,
                        starlarkFuncObject as StarlarkFunction?,
                        if (attributesToFlip.contains(BuildInfoFileWriteActionAttributes.TEMPLATE))
                            getSourceArtifact("template1.txt")
                        else
                            getSourceArtifact("template2.txt"),
                        attributesToFlip.contains(BuildInfoFileWriteActionAttributes.IS_VOLATILE),
                        StarlarkSemantics.DEFAULT
                    )
                }
            },
            actionKeyContext
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun wrongKeyRead_exceptionThrown() {
        scratch.file(
            "input.txt",  //
            "name test_name",
            "extra_client test_client"
        )
        scratch.file(
            "template.txt",  //
            "#define NAME {NAME}",
            "#define CLIENT {CLIENT}"
        )
        val starlarkFuncObject =
            exec(
                "def t(d):",
                " r = {}",
                " r[\"{NAME}\"] = d[\"name\"] + \"_foo\"",
                " r[\"{CLIENT}\"] = d[\"client\"] + \"_c\"",
                " return r",
                "t"
            )
        val action: AbstractAction =
            BuildInfoFileWriteAction(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                getSourceArtifact("input.txt", WorkspaceStatusValue.BUILD_INFO_KEY),
                outputFile,
                starlarkFuncObject as StarlarkFunction?,
                getSourceArtifact("template.txt"),
                false,
                StarlarkSemantics.DEFAULT
            )

        assertThat(
            org.junit.Assert.assertThrows<T?>(
                ActionExecutionException::class.java,
                org.junit.function.ThrowingRunnable { action.execute(context) })
        )
            .hasMessageThat()
            .contains("key \"client\" not found in dictionary")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun callbackReturnValueInvalidType_exceptionThrown() {
        scratch.file("input.txt", "")
        scratch.file("template.txt", "")
        val starlarkFuncObject = exec("def t(d):", " return [2, 5]", "t")
        val action: AbstractAction =
            BuildInfoFileWriteAction(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                getSourceArtifact("input.txt", WorkspaceStatusValue.BUILD_INFO_KEY),
                outputFile,
                starlarkFuncObject as StarlarkFunction?,
                getSourceArtifact("template.txt"),
                false,
                StarlarkSemantics.DEFAULT
            )

        assertThat(
            org.junit.Assert.assertThrows<T?>(
                ActionExecutionException::class.java,
                org.junit.function.ThrowingRunnable { action.execute(context) })
        )
            .hasMessageThat()
            .contains(
                ("BuildInfo translation callback function is expected to return dict of strings to"
                        + " strings, could not convert return value to Java type: got list for"
                        + " 'substitution_dict', want dict")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun callbackReturnDictContainsInvalidType_exceptionThrown() {
        scratch.file("input.txt", "")
        scratch.file("template.txt", "")
        val starlarkFuncObject = exec("def t(d):", " return {'a': 'b', 'c': 5}", "t")
        val action: AbstractAction =
            BuildInfoFileWriteAction(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                getSourceArtifact("input.txt", WorkspaceStatusValue.BUILD_INFO_KEY),
                outputFile,
                starlarkFuncObject as StarlarkFunction?,
                getSourceArtifact("template.txt"),
                false,
                StarlarkSemantics.DEFAULT
            )

        assertThat(
            org.junit.Assert.assertThrows<T?>(
                ActionExecutionException::class.java,
                org.junit.function.ThrowingRunnable { action.execute(context) })
        )
            .hasMessageThat()
            .contains(
                "could not convert return value to Java type: got dict<string, int> for"
                        + " 'substitution_dict', want dict<string, string>"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun callbackFails_exceptionThrown() {
        scratch.file("input.txt", "")
        scratch.file("template.txt", "")
        val starlarkFuncObject = exec("def t(d):", " fail('starlark error')", "t")
        val action: AbstractAction =
            BuildInfoFileWriteAction(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                getSourceArtifact("input.txt", WorkspaceStatusValue.BUILD_INFO_KEY),
                outputFile,
                starlarkFuncObject as StarlarkFunction?,
                getSourceArtifact("template.txt"),
                false,
                StarlarkSemantics.DEFAULT
            )

        assertThat(
            org.junit.Assert.assertThrows<T?>(
                ActionExecutionException::class.java,
                org.junit.function.ThrowingRunnable { action.execute(context) })
        )
            .hasMessageThat()
            .contains("Error in fail: starlark error")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun wrongArtifactOwnerOnInputSourceFile_exceptionThrown() {
        scratch.file("input.txt", "")
        scratch.file("template.txt", "")
        val starlarkFuncObject = exec("def t(d):", " pass", "t")

        Truth.assertThat(
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable {
                    BuildInfoFileWriteAction(
                        ActionsTestUtil.Companion.NULL_ACTION_OWNER,  // Set no artifact owner.
                        getSourceArtifact("input.txt"),
                        outputFile,
                        starlarkFuncObject as StarlarkFunction?,
                        getSourceArtifact("template.txt"),
                        false,
                        StarlarkSemantics.DEFAULT
                    )
                })
        )
            .hasMessageThat()
            .contains(
                "input artifact of BuildInfoFileWriteAction must be one of workspace status artifacts:"
                        + " ctx.info_file or ctx.version_file"
            )
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun exec(vararg lines: String?): Any? {
            Mutability.create("test").use { mutability ->
                val thread: StarlarkThread? = StarlarkThread.createTransient(mutability, StarlarkSemantics.DEFAULT)
                return Starlark.execFile(
                    net.starlark.java.syntax.ParserInput.Companion.fromLines(*lines),
                    net.starlark.java.syntax.FileOptions.Companion.DEFAULT,
                    net.starlark.java.eval.Module.withPredeclaredAndData(
                        StarlarkSemantics.DEFAULT,
                        com.google.common.collect.ImmutableMap.of<String?, Any?>(),
                        BazelModuleContext.create(
                            BazelModuleKey.createFakeModuleKeyForTesting(
                                Label.parseCanonicalUnchecked("//test:label")
                            ),
                            RepositoryMapping.EMPTY,
                            "test/label.bzl",  /* loads= */
                            com.google.common.collect.ImmutableList.of<E?>(),  /* bzlTransitiveDigest= */
                            ByteArray(0),  /* docCommentsMap= */
                            com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* unusedDocCommentLines= */
                            com.google.common.collect.ImmutableList.of<E?>()
                        )
                    ),
                    thread
                )
            }
        }
    }
}
