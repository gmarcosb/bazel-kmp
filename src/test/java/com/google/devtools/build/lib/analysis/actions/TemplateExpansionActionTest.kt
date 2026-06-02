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

/**
 * Tests [TemplateExpansionAction].
 */
@RunWith(JUnit4::class)
class TemplateExpansionActionTest : FoundationTestCase() {
    private var outputRoot: ArtifactRoot? = null
    private var inputArtifact: Artifact? = null
    private var outputArtifact: Artifact? = null
    private var output: Path? = null
    private var substitutions: MutableList<Substitution?>? = null
    private var directories: BlazeDirectories? = null
    private val actionKeyContext: ActionKeyContext = ActionKeyContext()

    @Before
    @Throws(java.lang.Exception::class)
    fun createDirectoriesAndTools() {
        createArtifacts(TEMPLATE)

        substitutions = java.util.ArrayList<Substitution?>()
        substitutions!!.add(Substitution.of("%key%", "foo"))
        substitutions!!.add(Substitution.of("%value%", "bar"))
        directories =
            BlazeDirectories(
                ServerDirectories(
                    scratch.resolve("/install"),
                    scratch.resolve("/base"),
                    scratch.resolve("/userRoot")
                ),
                scratch.resolve("/workspace"),
                "mock-product-name"
            )
    }

    @Throws(java.lang.Exception::class)
    private fun createArtifacts(template: String?) {
        val workspace: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/workspace")))
        scratch.dir("/workspace/out")
        outputRoot = ArtifactRoot.asDerivedRoot(scratch.dir("/workspace"), RootType.OUTPUT, "out")
        val input: Path =
            scratch.overwriteFile("/workspace/input.txt", java.nio.charset.StandardCharsets.UTF_8, template)
        inputArtifact = ActionsTestUtil.Companion.createArtifact(workspace, input)
        output = scratch.resolve("/workspace/out/destination.txt")
        outputArtifact = ActionsTestUtil.Companion.createArtifact(outputRoot, output)
    }

    private fun create(): TemplateExpansionAction {
        val result: TemplateExpansionAction = TemplateExpansionAction(
            ActionsTestUtil.Companion.NULL_ACTION_OWNER,
            outputArtifact, Template.forString(TEMPLATE), substitutions, false
        )
        return result
    }

    @org.junit.Test
    fun testInputsIsEmpty() {
        assertThat(create().getInputs().toList()).isEmpty()
    }

    @org.junit.Test
    fun testDestinationArtifactIsOutput() {
        assertThat(create().getOutputs()).containsExactly(outputArtifact)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpansion() {
        val executor: Executor? = TestExecutorBuilder(fileSystem, directories).build()
        val unused: ActionResult? = create().execute(createContext(executor))
        val content = String(FileSystemUtils.readContentAsLatin1(output))
        val expected: String = com.google.common.base.Joiner.on('\n').join("key=foo", "value=bar")
        Truth.assertThat(content).isEqualTo(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeySameIfSame() {
        val outputArtifact2: Artifact? =
            ActionsTestUtil.Companion.createArtifact(
                outputRoot, scratch.resolve("/workspace/out/destination.txt")
            )
        val a: TemplateExpansionAction = TemplateExpansionAction(
            ActionsTestUtil.Companion.NULL_ACTION_OWNER,
            outputArtifact, Template.forString(TEMPLATE),
            com.google.common.collect.ImmutableList.of<E?>(Substitution.of("%key%", "foo")), false
        )
        val b: TemplateExpansionAction = TemplateExpansionAction(
            ActionsTestUtil.Companion.NULL_ACTION_OWNER,
            outputArtifact2, Template.forString(TEMPLATE),
            com.google.common.collect.ImmutableList.of<E?>(Substitution.of("%key%", "foo")), false
        )

        Truth.assertThat(computeKey(a)).isEqualTo(computeKey(b))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeyDiffersForSubstitution() {
        val outputArtifact2: Artifact? =
            ActionsTestUtil.Companion.createArtifact(
                outputRoot, scratch.resolve("/workspace/out/destination.txt")
            )
        val a: TemplateExpansionAction = TemplateExpansionAction(
            ActionsTestUtil.Companion.NULL_ACTION_OWNER,
            outputArtifact, Template.forString(TEMPLATE),
            com.google.common.collect.ImmutableList.of<E?>(Substitution.of("%key%", "foo")), false
        )
        val b: TemplateExpansionAction = TemplateExpansionAction(
            ActionsTestUtil.Companion.NULL_ACTION_OWNER,
            outputArtifact2, Template.forString(TEMPLATE),
            com.google.common.collect.ImmutableList.of<E?>(Substitution.of("%key%", "foo2")), false
        )

        Truth.assertThat(computeKey(a)).isNotEqualTo(computeKey(b))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeyDiffersForExecutable() {
        val outputArtifact2: Artifact? =
            ActionsTestUtil.Companion.createArtifact(
                outputRoot, scratch.resolve("/workspace/out/destination.txt")
            )
        val a: TemplateExpansionAction = TemplateExpansionAction(
            ActionsTestUtil.Companion.NULL_ACTION_OWNER,
            outputArtifact, Template.forString(TEMPLATE),
            com.google.common.collect.ImmutableList.of<E?>(Substitution.of("%key%", "foo")), false
        )
        val b: TemplateExpansionAction = TemplateExpansionAction(
            ActionsTestUtil.Companion.NULL_ACTION_OWNER,
            outputArtifact2, Template.forString(TEMPLATE),
            com.google.common.collect.ImmutableList.of<E?>(Substitution.of("%key%", "foo")), true
        )

        Truth.assertThat(computeKey(a)).isNotEqualTo(computeKey(b))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeyDiffersForTemplates() {
        val outputArtifact2: Artifact? =
            ActionsTestUtil.Companion.createArtifact(
                outputRoot, scratch.resolve("/workspace/out/destination.txt")
            )
        val a: TemplateExpansionAction = TemplateExpansionAction(
            ActionsTestUtil.Companion.NULL_ACTION_OWNER,
            outputArtifact, Template.forString(TEMPLATE),
            com.google.common.collect.ImmutableList.of<E?>(Substitution.of("%key%", "foo")), false
        )
        val b: TemplateExpansionAction = TemplateExpansionAction(
            ActionsTestUtil.Companion.NULL_ACTION_OWNER,
            outputArtifact2, Template.forString(TEMPLATE + " "),
            com.google.common.collect.ImmutableList.of<E?>(Substitution.of("%key%", "foo")), false
        )

        Truth.assertThat(computeKey(a)).isNotEqualTo(computeKey(b))
    }

    private fun createWithArtifact(): TemplateExpansionAction {
        return createWithArtifact(substitutions)
    }

    private fun createWithArtifact(substitutions: MutableList<Substitution?>?): TemplateExpansionAction {
        val result: TemplateExpansionAction = TemplateExpansionAction(
            ActionsTestUtil.Companion.NULL_ACTION_OWNER, inputArtifact, outputArtifact, substitutions, false
        )
        return result
    }

    private fun createContext(executor: Executor?): ActionExecutionContext {
        return ActionExecutionContext(
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

    @Throws(java.lang.Exception::class)
    private fun executeTemplateExpansion(
        expected: String?,
        substitutions: MutableList<Substitution?>? = this.substitutions
    ) {
        val executor: Executor? = TestExecutorBuilder(fileSystem, directories).build()
        val unused: ActionResult? = createWithArtifact(substitutions).execute(createContext(executor))
        val actual: String? = FileSystemUtils.readContent(output, java.nio.charset.StandardCharsets.UTF_8)
        Truth.assertThat(actual).isEqualTo(expected)
    }

    @org.junit.Test
    fun testArtifactTemplateHasInput() {
        assertThat(createWithArtifact().getInputs().toList()).containsExactly(inputArtifact)
    }

    @org.junit.Test
    fun testArtifactTemplateHasOutput() {
        assertThat(createWithArtifact().getOutputs()).containsExactly(outputArtifact)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArtifactTemplateExpansion() {
        // The trailing "" is needed because scratch.overwriteFile implicitly appends "\n".
        val expected: String = com.google.common.base.Joiner.on('\n').join("key=foo", "value=bar", "")
        executeTemplateExpansion(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWithSpecialCharacters() {
        // We have to overwrite the artifacts since we need our template in "inputs"
        createArtifacts(SPECIAL_CHARS + "%key%")

        // scratch.overwriteFile appends a newline, so we need an additional \n here
        val expected: String? = java.lang.String.format("%s%s\n", SPECIAL_CHARS, SPECIAL_CHARS)

        executeTemplateExpansion(
            expected,
            com.google.common.collect.ImmutableList.of<E?>(
                Substitution.of("%key%", StringEncoding.unicodeToInternal(SPECIAL_CHARS))
            )
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun computeKey(action: TemplateExpansionAction): String {
        val fp: Fingerprint = Fingerprint()
        action.computeKey(actionKeyContext,  /* inputMetadataProvider= */null, fp)
        return fp.hexDigestAndReset()
    }

    companion object {
        private val TEMPLATE: String = com.google.common.base.Joiner.on('\n').join("key=%key%", "value=%value%")
        private const val SPECIAL_CHARS = "Š©±½_strøget"
    }
}
