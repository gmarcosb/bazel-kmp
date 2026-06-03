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

import com.google.devtools.build.lib.actions.Artifact.ArchivedTreeArtifact

@RunWith(JUnit4::class)
class TreeArtifactValueCodecTest : BuildViewTestBase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serializationRoundtrip() {
        val subjects: com.google.common.collect.ImmutableList.Builder<TreeArtifactValue?> =
            com.google.common.collect.ImmutableList.builder<TreeArtifactValue?>()
        subjects.add(TreeArtifactValue.empty())

        val root: ArtifactRoot? =
            ArtifactRoot.asDerivedRoot(
                skyframeExecutor.getBlazeDirectoriesForTesting().getOutputBase(),
                RootType.OUTPUT,
                PathFragment.create("bin")
            )
        val parent: SpecialArtifact? =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(
                root, PathFragment.create("bin/tree")
            )
        val child1: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(parent, "child1")
        val child2: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(parent, "child2")
        val metadata1: FileArtifactValue = metadataWithId(1)
        val metadata2: FileArtifactValue = metadataWithId(2)
        subjects.add(
            TreeArtifactValue.newBuilder(parent)
                .putChild(child1, metadata1)
                .putChild(child2, metadata2)
                .build()
        )

        val archivedTreeArtifact: ArchivedTreeArtifact? = ArchivedTreeArtifact.createForTree(parent)
        val archivedArtifactMetadata: FileArtifactValue = metadataWithId(3)
        subjects.add(
            TreeArtifactValue.newBuilder(parent)
                .putChild(child1, metadata1)
                .putChild(child2, metadata2)
                .setArchivedRepresentation(archivedTreeArtifact, archivedArtifactMetadata)
                .build()
        )

        val targetPath: PathFragment? = PathFragment.create("/some/target/path")
        subjects
            .add(TreeArtifactValue.newBuilder(parent).setResolvedPath(targetPath).build())
            .add(
                TreeArtifactValue.newBuilder(parent)
                    .setArchivedRepresentation(archivedTreeArtifact, archivedArtifactMetadata)
                    .build()
            )

        SerializationTester(subjects.build())
            .addDependency(Root.RootCodecDependencies::class.java, RootCodecDependencies())
            .addDependency(
                FileSystem::class.java,
                skyframeExecutor.getBlazeDirectoriesForTesting().getOutputBase().getFileSystem()
            )
            .addDependency(
                ArtifactSerializationContext::class.java,
                skyframeExecutor.getSkyframeBuildView().getArtifactFactory()::getSourceArtifact
            )
            .setVerificationFunction(
                { `in`, out ->
                    assertThat(`in`).isEqualTo(out)
                    assertThat(out).isInstanceOf(DeserializedSkyValue::class.java)
                })
            .runTests()
    }

    companion object {
        private fun metadataWithId(id: Int): FileArtifactValue {
            return FileArtifactValue.createForRemoteFile(byteArrayOf(id.toByte()), id, id)
        }
    }
}
