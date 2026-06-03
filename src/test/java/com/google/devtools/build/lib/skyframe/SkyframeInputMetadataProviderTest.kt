// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionLookupData

@RunWith(JUnit4::class)
class SkyframeInputMetadataProviderTest : FoundationTestCase() {
    // The behavior this test verifies (that SkyValues are memoized over multiple restarts) is not
    // actually necessary for the SkyframeInputMetadataProvider, but only due to a pretty brittle
    // combination of happenstances: action rewinding may remove a value from the graph at any time
    // and then MemoizingEvaluator.getExistingValue() will return null. However, Skyframe stores
    // previously requested direct dependencies in SkyFunction.Environment, so when that happens,
    // the requested metadata is still returned. But this relies on the particular implementation of
    // Skyframe *and* SkyframeInputMetadataProvider and memoizing over restarts isn't that costly so
    // it's useful to signal to someone who would remove this memoization, either accidentally or
    // intentionally.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun skyframeLookupsMemoizedOverMultipleRestarts() {
        val perBuild: StaticInputMetadataProvider =
            StaticInputMetadataProvider(com.google.common.collect.ImmutableMap.of<K?, V?>())

        val owner: ActionLookupKey? = ActionsTestUtil.createActionLookupKey("owner")
        val actionKey: ActionLookupData? = ActionLookupData.create(owner, 0)
        val artifact: DerivedArtifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root.asPath(), RootType.OUTPUT, "out"),
                PathFragment.create("out/foo"),
                owner
            )
        artifact.getPath().getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(artifact.getPath(), "test")
        artifact.setGeneratingActionKey(actionKey)

        val evaluator: MemoizingEvaluator = Mockito.mock<MemoizingEvaluator>(MemoizingEvaluator::class.java)
        val simp: SkyframeInputMetadataProvider =
            SkyframeInputMetadataProvider(evaluator, perBuild, "out")

        // On the first iteration, the dependency is not available yet. getInputMetadataChecked()
        // should accordingly throw.
        val env1: SkyFunction.Environment = Mockito.mock<SkyFunction.Environment>(SkyFunction.Environment::class.java)
        Mockito.`when`<T?>(evaluator.getExistingValue(actionKey)).thenReturn(null)
        Mockito.`when`<T?>(env1.getValue(actionKey)).thenReturn(null)
        simp.withSkyframeAllowed(env1).use { unused ->
            org.junit.Assert.assertThrows<T?>(
                MissingDepExecException::class.java,
                org.junit.function.ThrowingRunnable { simp.getInputMetadataChecked(artifact) })
        }
        val metadata: FileArtifactValue = FileArtifactValue.createForTesting(artifact)
        val aev: ActionExecutionValue? =
            ActionExecutionValue.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(artifact, metadata),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                null,
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )

        // Now the artifact in in Skyframe. Its metadata should be returned.
        val env2: SkyFunction.Environment = Mockito.mock<SkyFunction.Environment>(SkyFunction.Environment::class.java)
        Mockito.`when`<T?>(evaluator.getExistingValue(actionKey)).thenReturn(aev)
        Mockito.`when`<T?>(env2.getValue(actionKey)).thenReturn(aev)
        simp.withSkyframeAllowed(env2).use { unused ->
            assertThat(simp.getInputMetadataChecked(artifact)).isEqualTo(metadata)
        }
        // No further methods on env3 or the evaluator should be called and the metadata should still be
        // returned as normal.
        Mockito.`when`<T?>(evaluator.getExistingValue(actionKey)).thenThrow(java.lang.IllegalStateException::class.java)
        val env3: SkyFunction.Environment? = Mockito.mock<SkyFunction.Environment?>(SkyFunction.Environment::class.java)
        simp.withSkyframeAllowed(env3).use { unused ->
            assertThat(simp.getInputMetadataChecked(artifact)).isEqualTo(metadata)
        }
        Mockito.verify<Any?>(env3, Mockito.never()).getValue(actionKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun skyframeLookupsMemoizedWithinASingleRestart() {
        val perBuild: StaticInputMetadataProvider =
            StaticInputMetadataProvider(com.google.common.collect.ImmutableMap.of<K?, V?>())

        val owner: ActionLookupKey? = ActionsTestUtil.createActionLookupKey("owner")
        val actionKey: ActionLookupData? = ActionLookupData.create(owner, 0)
        val artifact: DerivedArtifact =
            DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(root.asPath(), RootType.OUTPUT, "out"),
                PathFragment.create("out/foo"),
                owner
            )
        artifact.getPath().getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(artifact.getPath(), "test")
        artifact.setGeneratingActionKey(actionKey)
        val metadata: FileArtifactValue = FileArtifactValue.createForTesting(artifact)
        val aev: ActionExecutionValue? =
            ActionExecutionValue.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(artifact, metadata),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                null,
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )

        val evaluator: MemoizingEvaluator = Mockito.mock<MemoizingEvaluator>(MemoizingEvaluator::class.java)
        val simp: SkyframeInputMetadataProvider =
            SkyframeInputMetadataProvider(evaluator, perBuild, "out")

        val env: SkyFunction.Environment? = Mockito.mock<SkyFunction.Environment?>(SkyFunction.Environment::class.java)
        Mockito.`when`<T?>(evaluator.getExistingValue(actionKey))
            .thenReturn(aev) // first call
            .thenThrow(java.lang.IllegalStateException::class.java) // Subsequent calls

        simp.withSkyframeAllowed(env).use { unused ->
            assertThat(simp.getInputMetadataChecked(artifact)).isEqualTo(metadata)
            assertThat(simp.getInputMetadataChecked(artifact)).isEqualTo(metadata)
        }
        Mockito.verify<Any?>(env, Mockito.never()).getValue(ArgumentMatchers.any<T?>())
    }
}
