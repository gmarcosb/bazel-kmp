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
package com.google.devtools.build.lib.starlark

import com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper.dumpStructureWithEquivalenceReduction

@RunWith(JUnit4::class)
class StarlarkActionTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serializationRoundTrip_resetsInputs() {
        val executable: PathFragment? = scratch.file("/bin/xxx").asFragment()
        val src: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/src")))
        val discoveredInput: Artifact? =
            ActionsTestUtil.createArtifact(src, scratch.file("/src/discovered.in"))
        val output: Artifact.DerivedArtifact = getBinArtifactWithNoOwner("output")
        output.setGeneratingActionKey(ActionsTestUtil.NULL_ACTION_LOOKUP_DATA)

        val starlarkAction: StarlarkAction =
            Builder()
                .setShadowedAction(java.util.Optional.of<T?>(InputDiscoveringNullAction()))
                .setExecutable(executable)
                .addOutput(output)
                .build(ActionsTestUtil.Companion.NULL_ACTION_OWNER, targetConfig) as StarlarkAction

        ensureMemoizedIsInitializedIsSet(starlarkAction)
        val originalStructure: String? = dumpStructureWithEquivalenceReduction(starlarkAction)

        starlarkAction.updateInputs(NestedSetBuilder.create(Order.STABLE_ORDER, discoveredInput))

        SerializationTester(starlarkAction)
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
