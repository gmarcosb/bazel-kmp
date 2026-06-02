// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.OutputGroupInfo.HIDDEN_OUTPUT_GROUP_PREFIX

/** Tests for [TopLevelArtifactHelper].  */
@RunWith(JUnit4::class)
class TopLevelArtifactHelperTest {
    private var ctx: TopLevelArtifactContext? = null
    private var groupProvider: OutputGroupInfo? = null

    private var path: Path? = null
    private var root: ArtifactRoot? = null
    private var artifactIdx = 0

    @Before
    @Throws(java.lang.Exception::class)
    fun setRootDir() {
        val scratch: Scratch = Scratch()
        val execRoot: Path? = scratch.getFileSystem().getPath("/")
        root = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "blaze-out")
        path = scratch.dir("/blaze-out/foo")
    }

    private fun setup(groups: Iterable<Pair<String?, Int?>>) {
        val outputGroups: TreeMap<String?, NestedSetBuilder<Artifact?>?> =
            TreeMap<String?, NestedSetBuilder<Artifact?>?>()
        for (pair in groups) {
            outputGroups.put(pair.first, newArtifacts(pair.second))
        }
        groupProvider = OutputGroupInfo.fromBuilders(outputGroups)
        ctx = TopLevelArtifactContext(false, false, com.google.common.collect.ImmutableSortedSet.copyOf(groupProvider))
    }

    @org.junit.Test
    fun artifactsShouldBeSeparateByGroup() {
        setup(java.util.Arrays.asList<T?>(Pair.of("foo", 3), Pair.of("bar", 2)))

        val allArtifacts: ArtifactsToBuild = getAllArtifactsToBuild(groupProvider, null, ctx)
        assertThat(allArtifacts.getAllArtifacts().toList()).hasSize(5)
        assertThat(allArtifacts.getImportantArtifacts().toList()).hasSize(5)

        val artifactsByGroup: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?> =
            allArtifacts.getAllArtifactsByOutputGroup()
        // Two groups
        Truth.assertThat(artifactsByGroup.keys).containsExactly("foo", "bar")
        assertThat(artifactsByGroup.get("foo").getArtifacts().toList()).hasSize(3)
        assertThat(artifactsByGroup.get("bar").getArtifacts().toList()).hasSize(2)
    }

    @org.junit.Test
    fun emptyGroupsShouldBeIgnored() {
        setup(java.util.Arrays.asList<T?>(Pair.of("foo", 1), Pair.of("bar", 0)))

        val allArtifacts: ArtifactsToBuild = getAllArtifactsToBuild(groupProvider, null, ctx)
        assertThat(allArtifacts.getAllArtifacts().toList()).hasSize(1)
        assertThat(allArtifacts.getImportantArtifacts().toList()).hasSize(1)

        val artifactsByGroup: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?> =
            allArtifacts.getAllArtifactsByOutputGroup()
        // The bar list should not appear here, as it contains no artifacts.
        Truth.assertThat(artifactsByGroup.keys).containsExactly("foo")
    }

    @org.junit.Test
    fun importantArtifacts() {
        setup(
            java.util.Arrays.asList<T?>(
                Pair.of(HIDDEN_OUTPUT_GROUP_PREFIX + "notimportant", 1),
                Pair.of("important", 2)
            )
        )

        val allArtifacts: ArtifactsToBuild = getAllArtifactsToBuild(groupProvider, null, ctx)
        assertThat(allArtifacts.getAllArtifacts().toList()).hasSize(3)
        assertThat(allArtifacts.getImportantArtifacts().toList()).hasSize(2)
    }

    private fun newArtifacts(num: Int): NestedSetBuilder<Artifact?> {
        val builder: NestedSetBuilder<Artifact?> = NestedSetBuilder.newBuilder(Order.STABLE_ORDER)
        for (i in 0..<num) {
            builder.add(newArtifact())
        }
        return builder
    }

    private fun newArtifact(): Artifact {
        return ActionsTestUtil.createArtifact(root, path.getRelative((artifactIdx++).toString()))
    }
}
