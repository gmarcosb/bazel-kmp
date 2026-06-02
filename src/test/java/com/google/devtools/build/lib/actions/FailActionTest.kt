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

import com.google.devtools.build.lib.actions.ArtifactRoot.RootType

@RunWith(JUnit4::class)
class FailActionTest {
    private val scratch: Scratch = Scratch()

    private var errorMessage: String? = null
    private var anOutput: Artifact? = null
    private var outputs: MutableCollection<Artifact?>? = null
    private var failAction: FailAction? = null
    private val actionKeyContext: ActionKeyContext = ActionKeyContext()

    protected var actionGraph: MutableActionGraph = MapBasedActionGraph(actionKeyContext)

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        errorMessage = "An error just happened."
        anOutput =
            createArtifact(
                ArtifactRoot.asDerivedRoot(scratch.dir("/"), RootType.OUTPUT, "out"),
                scratch.file("/out/foo")
            )
        outputs = com.google.common.collect.ImmutableList.of<Artifact?>(anOutput)
        failAction =
            FailAction(ActionsTestUtil.Companion.NULL_ACTION_OWNER, outputs, errorMessage, Code.FAIL_ACTION_UNKNOWN)
        actionGraph.registerAction(failAction)
        assertThat(actionGraph.getGeneratingAction(anOutput)).isSameInstanceAs(failAction)
    }

    @org.junit.Test
    fun testExecutingItYieldsExceptionWithErrorMessage() {
        val e: ActionExecutionException? =
            org.junit.Assert.assertThrows<T?>(
                ActionExecutionException::class.java,
                org.junit.function.ThrowingRunnable { failAction.execute(null) })
        assertThat(e).hasMessageThat().contains(errorMessage)
    }

    @org.junit.Test
    fun testInputsAreEmptySet() {
        assertThat(failAction.getInputs().toList()).isEmpty()
    }

    @org.junit.Test
    fun testRetainsItsOutputs() {
        assertThat(failAction.getOutputs()).containsExactlyElementsIn(outputs)
    }

    @org.junit.Test
    fun testPrimaryOutput() {
        assertThat(failAction.getPrimaryOutput()).isSameInstanceAs(anOutput)
    }
}
