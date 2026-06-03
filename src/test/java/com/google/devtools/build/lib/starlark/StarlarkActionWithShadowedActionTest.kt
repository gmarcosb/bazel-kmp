// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlark

import com.google.devtools.build.lib.actions.Action

/** Tests for [StarlarkAction] using the shadowed action parameter.  */
@RunWith(JUnit4::class)
class StarlarkActionWithShadowedActionTest : BuildViewTestCase() {
    private var executionContext: ActionExecutionContext? = null
    private var collectingAnalysisEnvironment: CollectingAnalysisEnvironment? = null
    private var starlarkActionInputs: NestedSet<Artifact?>? = null
    private var shadowedActionInputs: NestedSet<Artifact?>? = null
    private var discoveredInputs: NestedSet<Artifact?>? = null
    private var starlarkActionEnvironment: com.google.common.collect.ImmutableMap<String?, String?>? = null
    private var shadowedActionEnvironment: com.google.common.collect.ImmutableMap<String?, String?>? = null

    private var output: Artifact? = null
    private var executable: PathFragment? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createArtifacts() {
        collectingAnalysisEnvironment =
            CollectingAnalysisEnvironment(testAnalysisEnvironment)
        starlarkActionInputs =
            NestedSetBuilder.create(
                Order.STABLE_ORDER,
                getSourceArtifact("pkg/shadowed_action_inp1"),
                getSourceArtifact("pkg/discovered_inp2"),
                getSourceArtifact("pkg/starlark_action_inp3")
            )
        shadowedActionInputs =
            NestedSetBuilder.create(
                Order.STABLE_ORDER,
                getSourceArtifact("pkg/shadowed_action_inp1"),
                getSourceArtifact("pkg/shadowed_action_inp2"),
                getSourceArtifact("pkg/shadowed_action_inp3")
            )
        discoveredInputs =
            NestedSetBuilder.create(
                Order.STABLE_ORDER,
                getSourceArtifact("pkg/shadowed_action_inp1"),
                getSourceArtifact("pkg/discovered_inp2"),
                getSourceArtifact("pkg/discovered_inp3")
            )
        output = getBinArtifactWithNoOwner("output")
        executable = scratch.file("/bin/xxx").asFragment()
        starlarkActionEnvironment =
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "repeated_var", "starlark_val",
                "a_var", "a_val",
                "b_var", "b_val"
            )
        shadowedActionEnvironment =
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "repeated_var", "shadowed_val",
                "c_var", "c_val",
                "d_var", "d_val"
            )
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun createExecutorAndContext() {
        val executor: Executor = TestExecutorBuilder(fileSystem, directories).build()
        executionContext =
            ActionExecutionContext(
                executor,  /* inputMetadataProvider= */
                null,
                ActionInputPrefetcher.NONE,
                actionKeyContext,  /* outputMetadataStore= */
                null,  /* rewindingEnabled= */
                false,
                LostInputsCheck.NONE,  /* fileOutErr= */
                null,  /* eventHandler= */
                null,  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* actionFileSystem= */
                null,
                DiscoveredModulesPruner.DEFAULT,
                SyscallCache.NO_CACHE,
                ThreadStateReceiver.NULL_INSTANCE
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUsingOnlyShadowedActionInputs() {
        // If both starlark action and the shadowed action do not have inputs, then getInputs of both of
        // them should return empty set
        var shadowedAction: Action =
            createShadowedAction(
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /*discoversInputs=*/false, null
            )
        var starlarkAction: StarlarkAction =
            Builder()
                .setShadowedAction(java.util.Optional.of<T?>(shadowedAction))
                .setExecutable(executable)
                .addOutput(output)
                .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER, targetConfig) as StarlarkAction
        collectingAnalysisEnvironment.registerAction(starlarkAction)

        assertThat(starlarkAction.getInputs().toList()).isEmpty()
        assertThat(starlarkAction.discoversInputs()).isFalse()
        assertThat(starlarkAction.getUnusedInputsList()).isEmpty()
        assertThat(starlarkAction.getAllowedDerivedInputs().toList()).isEmpty()

        // If the starlark action does not have any inputs, then it will use the shadowed action inputs
        shadowedAction = createShadowedAction(shadowedActionInputs, false, null)
        starlarkAction =
            Builder()
                .setShadowedAction(java.util.Optional.of<T?>(shadowedAction))
                .setExecutable(executable)
                .addOutput(output)
                .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER, targetConfig) as StarlarkAction
        collectingAnalysisEnvironment.registerAction(starlarkAction)

        assertThat(starlarkAction.getInputs().toList())
            .containsExactlyElementsIn(shadowedActionInputs.toList())
        assertThat(starlarkAction.discoversInputs()).isFalse()
        assertThat(starlarkAction.getUnusedInputsList()).isEmpty()
        assertThat(starlarkAction.getAllowedDerivedInputs().toList())
            .containsExactlyElementsIn(shadowedActionInputs.toList())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUsingOnlyShadowedActionWithDiscoveredInputs() {
        // Test that the shadowed action's discovered inputs are passed to the starlark action
        var shadowedAction: Action =
            createShadowedAction(
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /*discoversInputs=*/
                true,
                discoveredInputs
            )
        var starlarkAction: StarlarkAction =
            Builder()
                .setShadowedAction(java.util.Optional.of<T?>(shadowedAction))
                .setExecutable(executable)
                .addOutput(output)
                .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER, targetConfig) as StarlarkAction
        collectingAnalysisEnvironment.registerAction(starlarkAction)

        assertThat(starlarkAction.getInputs().toList()).isEmpty()
        assertThat(starlarkAction.getUnusedInputsList()).isEmpty()
        assertThat(starlarkAction.getAllowedDerivedInputs().toList()).isEmpty()
        assertThat(starlarkAction.discoversInputs()).isTrue()
        assertThat(starlarkAction.discoverInputs(executionContext).toList())
            .containsExactlyElementsIn(discoveredInputs.toList())
        // after discovering inputs, the starlark action inputs should be updated
        assertThat(starlarkAction.inputsKnown()).isTrue()
        assertThat(starlarkAction.getInputs().toList())
            .containsExactlyElementsIn(discoveredInputs.toList())

        // Test that both inputs and discovered inputs of the shadowed action are passed to the starlark
        // action
        shadowedAction = createShadowedAction(shadowedActionInputs, true, discoveredInputs)
        starlarkAction =
            Builder()
                .setShadowedAction(java.util.Optional.of<T?>(shadowedAction))
                .setExecutable(executable)
                .addOutput(output)
                .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER, targetConfig) as StarlarkAction
        collectingAnalysisEnvironment.registerAction(starlarkAction)

        assertThat(starlarkAction.getInputs().toList())
            .containsExactlyElementsIn(shadowedActionInputs.toList())
        assertThat(starlarkAction.getUnusedInputsList()).isEmpty()
        assertThat(starlarkAction.getAllowedDerivedInputs().toList())
            .containsExactlyElementsIn(shadowedActionInputs.toList())
        assertThat(starlarkAction.discoversInputs()).isTrue()
        assertThat(starlarkAction.discoverInputs(executionContext).toList())
            .containsExactlyElementsIn(
                com.google.common.collect.Sets.difference<E?>(discoveredInputs.toSet(), shadowedActionInputs.toSet())
            )
        // after discovering inputs, the starlark action inputs should be updated
        assertThat(starlarkAction.inputsKnown()).isTrue()
        assertThat(starlarkAction.getInputs().toList())
            .containsExactlyElementsIn(
                com.google.common.collect.Sets.union<E?>(shadowedActionInputs.toSet(), discoveredInputs.toSet())
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUsingShadowedActionWithStarlarkActionInputs() {
        // Test using Starlark action's inputs without using a shadowed action
        var starlarkAction: StarlarkAction =
            Builder()
                .setExecutable(executable)
                .addInput(starlarkActionInputs.toList().get(0))
                .addInput(starlarkActionInputs.toList().get(1))
                .addInput(starlarkActionInputs.toList().get(2))
                .addOutput(output)
                .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER, targetConfig) as StarlarkAction
        collectingAnalysisEnvironment.registerAction(starlarkAction)

        assertThat(starlarkAction.getInputs().toList())
            .containsExactlyElementsIn(starlarkActionInputs.toList())
        assertThat(starlarkAction.getUnusedInputsList()).isEmpty()
        assertThat(starlarkAction.discoversInputs()).isFalse()

        // Test using Starlark actions's inputs with shadowed action's inputs
        var shadowedAction: Action =
            createShadowedAction(
                shadowedActionInputs,  /*discoversInputs=*/false,  /*discoveredInputs=*/null
            )
        starlarkAction =
            Builder()
                .setShadowedAction(java.util.Optional.of<T?>(shadowedAction))
                .setExecutable(executable)
                .addInput(starlarkActionInputs.toList().get(0))
                .addInput(starlarkActionInputs.toList().get(1))
                .addInput(starlarkActionInputs.toList().get(2))
                .addOutput(output)
                .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER, targetConfig) as StarlarkAction
        collectingAnalysisEnvironment.registerAction(starlarkAction)

        assertThat(starlarkAction.getInputs().toList())
            .containsExactlyElementsIn(
                com.google.common.collect.Sets.union<E?>(shadowedActionInputs.toSet(), starlarkActionInputs.toSet())
            )
        assertThat(starlarkAction.getUnusedInputsList()).isEmpty()
        assertThat(starlarkAction.getAllowedDerivedInputs().toList())
            .containsExactlyElementsIn(
                com.google.common.collect.Sets.union<E?>(shadowedActionInputs.toSet(), starlarkActionInputs.toSet())
            )
        assertThat(starlarkAction.discoversInputs()).isFalse()

        // Test using Starlark actions's inputs with shadowed action's inputs and discovered inputs
        shadowedAction = createShadowedAction(shadowedActionInputs, true, discoveredInputs)
        starlarkAction =
            Builder()
                .setShadowedAction(java.util.Optional.of<T?>(shadowedAction))
                .setExecutable(executable)
                .addInput(starlarkActionInputs.toList().get(0))
                .addInput(starlarkActionInputs.toList().get(1))
                .addInput(starlarkActionInputs.toList().get(2))
                .addOutput(output)
                .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER, targetConfig) as StarlarkAction
        collectingAnalysisEnvironment.registerAction(starlarkAction)

        assertThat(starlarkAction.getInputs().toList())
            .containsExactlyElementsIn(
                com.google.common.collect.Sets.union<E?>(shadowedActionInputs.toSet(), starlarkActionInputs.toSet())
            )
        assertThat(starlarkAction.getUnusedInputsList()).isEmpty()
        assertThat(starlarkAction.getAllowedDerivedInputs().toList())
            .containsExactlyElementsIn(
                com.google.common.collect.Sets.union<E?>(shadowedActionInputs.toSet(), starlarkActionInputs.toSet())
            )
        assertThat(starlarkAction.discoversInputs()).isTrue()
        assertThat(starlarkAction.discoverInputs(executionContext).toList())
            .containsExactly(discoveredInputs.toList().get(2))
        // after discovering inputs, the starlark action inputs should be updated
        assertThat(starlarkAction.inputsKnown()).isTrue()
        assertThat(starlarkAction.getInputs().toList())
            .containsExactlyElementsIn(
                com.google.common.collect.Sets.union<E?>(
                    NestedSetBuilder.wrap(
                        Order.STABLE_ORDER,
                        com.google.common.collect.Sets.union<E?>(
                            shadowedActionInputs.toSet(),
                            starlarkActionInputs.toSet()
                        )
                    )
                        .toSet(),
                    discoveredInputs.toSet()
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPassingShadowedActionEnvironment() {
        // Test using Starlark action's environment without using a shadowed action
        var starlarkAction: StarlarkAction =
            Builder()
                .setExecutable(executable)
                .addInput(starlarkActionInputs.toList().get(0))
                .addOutput(output)
                .setEnvironment(starlarkActionEnvironment)
                .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER, targetConfig) as StarlarkAction
        collectingAnalysisEnvironment.registerAction(starlarkAction)

        assertThat(starlarkAction.getEffectiveEnvironment(com.google.common.collect.ImmutableMap.of<K?, V?>()))
            .containsExactlyEntriesIn(starlarkActionEnvironment)

        // Test using shadowed action's environment without Starlark actions's environment
        val shadowedAction: Action =
            createShadowedAction(
                shadowedActionInputs,  /*discoversInputs=*/false,  /*discoveredInputs=*/null
            )
        starlarkAction =
            Builder()
                .setShadowedAction(java.util.Optional.of<T?>(shadowedAction))
                .setExecutable(executable)
                .addInput(starlarkActionInputs.toList().get(0))
                .addOutput(output)
                .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER, targetConfig) as StarlarkAction
        collectingAnalysisEnvironment.registerAction(starlarkAction)

        assertThat(starlarkAction.getEffectiveEnvironment(com.google.common.collect.ImmutableMap.of<K?, V?>()))
            .containsExactlyEntriesIn(shadowedActionEnvironment)

        // Test using Starlark actions's environment with shadowed action's environment
        starlarkAction =
            Builder()
                .setShadowedAction(java.util.Optional.of<T?>(shadowedAction))
                .setExecutable(executable)
                .addInput(starlarkActionInputs.toList().get(0))
                .addOutput(output)
                .setEnvironment(starlarkActionEnvironment)
                .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER, targetConfig) as StarlarkAction
        collectingAnalysisEnvironment.registerAction(starlarkAction)

        val expectedEnvironment: LinkedHashMap<String?, String?> = LinkedHashMap<String?, String?>()
        expectedEnvironment.putAll(shadowedActionEnvironment)
        expectedEnvironment.putAll(starlarkActionEnvironment)

        val actualEnvironment: com.google.common.collect.ImmutableMap<String?, String?>? =
            starlarkAction.getEffectiveEnvironment(com.google.common.collect.ImmutableMap.of<K?, V?>())
        Truth.assertThat(actualEnvironment).hasSize(5)
        // Starlark action's env overwrites any repeated variable from the shadowed action env
        Truth.assertThat(actualEnvironment).containsEntry("repeated_var", "starlark_val")
        Truth.assertThat(actualEnvironment).containsExactlyEntriesIn(expectedEnvironment)
    }

    @Throws(java.lang.Exception::class)
    private fun createShadowedAction(
        inputs: NestedSet<Artifact?>?, discoversInputs: Boolean, discoveredInputs: NestedSet<Artifact?>?
    ): Action {
        val shadowedAction: Action = Mockito.mock<Action>(Action::class.java)
        Mockito.`when`<T?>(shadowedAction.discoversInputs()).thenReturn(discoversInputs)
        Mockito.`when`<T?>(shadowedAction.getInputs()).thenReturn(inputs)
        Mockito.`when`<T?>(shadowedAction.getMandatoryInputs()).thenReturn(inputs)
        Mockito.`when`<T?>(shadowedAction.getAllowedDerivedInputs()).thenReturn(inputs)
        Mockito.`when`<T?>(
            shadowedAction.getInputFilesForExtraAction(
                ArgumentMatchers.any<T?>(ActionExecutionContext::class.java)
            )
        )
            .thenReturn(discoveredInputs)
        Mockito.`when`<T?>(shadowedAction.inputsKnown()).thenReturn(true)
        Mockito.`when`<T?>(shadowedAction.getOwner()).thenReturn(ActionsTestUtil.Companion.NULL_ACTION_OWNER)
        Mockito.`when`<T?>(shadowedAction.getEffectiveEnvironment(ArgumentMatchers.anyMap<K?, V?>()))
            .thenReturn(com.google.common.collect.ImmutableMap.< K, V > copyOf<K?, V?>(shadowedActionEnvironment))

        return shadowedAction
    }
}
