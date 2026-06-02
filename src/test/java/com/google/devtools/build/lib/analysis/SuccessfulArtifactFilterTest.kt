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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

/** Unit test for [SuccessfulArtifactFilter].  */
@RunWith(JUnit4::class)
class SuccessfulArtifactFilterTest {
    private val scratch: Scratch = Scratch()

    private var root: ArtifactRoot? = null
    private var ctx: TopLevelArtifactContext? = null
    private var groupProvider: OutputGroupInfo? = null

    @Before
    @Throws(IOException::class)
    fun setUp() {
        val sourceDir: Path = scratch.dir("/source")
        root = ArtifactRoot.asSourceRoot(Root.fromPath(sourceDir))
    }

    @java.lang.SafeVarargs
    private fun initializeOutputGroupInfo(vararg groups: Pair<String?, NestedSet<Artifact?>?>) {
        val outputGroups: TreeMap<String?, NestedSetBuilder<Artifact?>?> =
            TreeMap<String?, NestedSetBuilder<Artifact?>?>()
        for (pair in groups) {
            outputGroups.put(pair.first, NestedSetBuilder.fromNestedSet(pair.second))
        }
        groupProvider = OutputGroupInfo.fromBuilders(outputGroups)
        ctx = TopLevelArtifactContext(false, false, com.google.common.collect.ImmutableSortedSet.copyOf(groupProvider))
    }

    @org.junit.Test
    fun allOutputGroupsFiltered() {
        val group1FailedArtifact: SourceArtifact = newArtifact("g1_failed_output")
        val group2FailedArtifact: SourceArtifact = newArtifact("g2_failed_output")
        val group3FailedArtifact: SourceArtifact = newArtifact("g3_failed_output")
        val group1BuiltArtifact: SourceArtifact = newArtifact("g1_output")
        val group2BuiltArtifact: SourceArtifact = newArtifact("g2_output")
        val group3BuiltArtifact1: SourceArtifact = newArtifact("g3_output1")
        val group3BuiltArtifact2: SourceArtifact = newArtifact("g3_output2")
        val successfulArtifacts: com.google.common.collect.ImmutableSet<Artifact?> =
            com.google.common.collect.ImmutableSet.of<Artifact?>(
                group1BuiltArtifact, group2BuiltArtifact, group3BuiltArtifact1, group3BuiltArtifact2
            )
        // Arrange each output group with a different nested set structure.
        val group1Artifacts: NestedSet<Artifact?>? =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .addTransitive(NestedSetBuilder.create(Order.STABLE_ORDER, group1BuiltArtifact))
                .addTransitive(NestedSetBuilder.create(Order.STABLE_ORDER, group1FailedArtifact))
                .build()
        val group2Artifacts: NestedSet<Artifact?>? =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .add(group2BuiltArtifact)
                .addTransitive(NestedSetBuilder.create(Order.STABLE_ORDER, group2FailedArtifact))
                .build()
        val group3Artifacts1: NestedSet<Artifact?>? =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .add(group3BuiltArtifact1)
                .addTransitive(NestedSetBuilder.create(Order.STABLE_ORDER, group3FailedArtifact))
                .build()
        val group3Artifacts2: NestedSet<Artifact?>? =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .add(group3FailedArtifact)
                .addTransitive(NestedSetBuilder.create(Order.STABLE_ORDER, group3BuiltArtifact2))
                .build()
        val group3Artifacts: NestedSet<Artifact?>? =
            NestedSetBuilder.fromNestedSets(
                com.google.common.collect.ImmutableList.of<E?>(
                    group3Artifacts1,
                    group3Artifacts2
                )
            )
                .build()

        initializeOutputGroupInfo(
            Pair.of("g1", group1Artifacts),
            Pair.of("g2", group2Artifacts),
            Pair.of("g3", group3Artifacts)
        )

        val allArtifactsToBuild: ArtifactsToBuild =
            TopLevelArtifactHelper.getAllArtifactsToBuild(groupProvider, null, ctx)
        val outputGroups: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?>? =
            allArtifactsToBuild.getAllArtifactsByOutputGroup()

        val filter: SuccessfulArtifactFilter = SuccessfulArtifactFilter(successfulArtifacts)
        val filteredOutputGroups: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?> =
            filter.filterArtifactsInOutputGroup(outputGroups)
        assertThat(filteredOutputGroups.get("g1").isIncomplete()).isTrue()
        assertThat(filteredOutputGroups.get("g2").isIncomplete()).isTrue()
        assertThat(filteredOutputGroups.get("g3").isIncomplete()).isTrue()
        val groupArtifacts: MutableMap<String?, com.google.common.collect.ImmutableSet<Artifact?>?> =
            extractArtifactsByOutputGroup(filteredOutputGroups)
        Truth.assertThat(groupArtifacts.get("g1")).containsExactly(group1BuiltArtifact)
        Truth.assertThat(groupArtifacts.get("g2")).containsExactly(group2BuiltArtifact)
        Truth.assertThat(groupArtifacts.get("g3"))
            .containsExactly(group3BuiltArtifact1, group3BuiltArtifact2)
    }

    @org.junit.Test
    fun emptyOutputGroupsNotReturned() {
        val group1FailedArtifact: SourceArtifact = newArtifact("g1_failed_output")
        val group2FailedArtifact: SourceArtifact = newArtifact("g2_failed_output")
        val group1BuiltArtifact: SourceArtifact = newArtifact("g1_output")
        val successfulArtifacts: com.google.common.collect.ImmutableSet<Artifact?> =
            com.google.common.collect.ImmutableSet.of<Artifact?>(group1BuiltArtifact)
        val group1Artifacts: NestedSet<Artifact?>? =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .addTransitive(NestedSetBuilder.create(Order.STABLE_ORDER, group1BuiltArtifact))
                .addTransitive(NestedSetBuilder.create(Order.STABLE_ORDER, group1FailedArtifact))
                .build()
        val group2Artifacts: NestedSet<Artifact?>? =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ().add(group2FailedArtifact).build()

        initializeOutputGroupInfo(Pair.of("g1", group1Artifacts), Pair.of("g2", group2Artifacts))

        val allArtifactsToBuild: ArtifactsToBuild =
            TopLevelArtifactHelper.getAllArtifactsToBuild(groupProvider, null, ctx)
        val outputGroups: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?>? =
            allArtifactsToBuild.getAllArtifactsByOutputGroup()

        val filter: SuccessfulArtifactFilter = SuccessfulArtifactFilter(successfulArtifacts)
        val filteredOutputGroups: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?> =
            filter.filterArtifactsInOutputGroup(outputGroups)
        assertThat(filteredOutputGroups.get("g1").isIncomplete()).isTrue()
        Truth.assertThat(filteredOutputGroups).containsKey("g1")
        Truth.assertThat(filteredOutputGroups).doesNotContainKey("g2")
    }

    @org.junit.Test
    fun unfilteredNestedSetsReused() {
        val group1BuiltArtifact: SourceArtifact = newArtifact("output1")
        val group1BuiltArtifact2: SourceArtifact = newArtifact("output2")
        val group1BuiltArtifact3: SourceArtifact = newArtifact("output3")
        val successfulArtifacts: com.google.common.collect.ImmutableSet<Artifact?> =
            com.google.common.collect.ImmutableSet.of<Artifact?>(
                group1BuiltArtifact,
                group1BuiltArtifact2,
                group1BuiltArtifact3
            )
        val successfulArtifactSet: NestedSet<Artifact?>? =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .add(group1BuiltArtifact)
                .addTransitive(
                    NestedSetBuilder.create(
                        Order.STABLE_ORDER, group1BuiltArtifact2, group1BuiltArtifact3
                    )
                )
                .build()
        val setContainingSuccessfulSet1: NestedSet<Artifact?>? =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .add(group1BuiltArtifact)
                .addTransitive(successfulArtifactSet)
                .build()
        val setContainingSuccessfulSet2: NestedSet<Artifact?>? =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .add(group1BuiltArtifact)
                .addTransitive(successfulArtifactSet)
                .build()
        val outputGroup: NestedSet<Artifact?>? =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .addTransitive(setContainingSuccessfulSet1)
                .addTransitive(setContainingSuccessfulSet2)
                .build()

        initializeOutputGroupInfo(Pair.of("out", outputGroup))

        val allArtifactsToBuild: ArtifactsToBuild =
            TopLevelArtifactHelper.getAllArtifactsToBuild(groupProvider, null, ctx)
        val outputGroups: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?> =
            allArtifactsToBuild.getAllArtifactsByOutputGroup()

        val filter: SuccessfulArtifactFilter = SuccessfulArtifactFilter(successfulArtifacts)
        val unfilteredArtifactsInOutputGroup: ArtifactsInOutputGroup? = outputGroups.get("out")
        val filteredArtifactsInOutputGroup: ArtifactsInOutputGroup =
            filter.filterArtifactsInOutputGroup(outputGroups).get("out")
        assertThat(filteredArtifactsInOutputGroup.isIncomplete()).isFalse()
        assertThat(filteredArtifactsInOutputGroup).isSameInstanceAs(unfilteredArtifactsInOutputGroup)
    }

    @org.junit.Test(timeout = 10000)
    fun deeplyNestedSetFilteredQuickly() {
        val failedArtifact: SourceArtifact = newArtifact("failed_output")
        val builtArtifact: SourceArtifact = newArtifact("output")
        val successfulArtifacts: com.google.common.collect.ImmutableSet<Artifact?> =
            com.google.common.collect.ImmutableSet.of<Artifact?>(builtArtifact)
        // Arrange each output group with a different nested set structure.
        val baseSet: NestedSet<Artifact?>? =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ().add(builtArtifact).add(failedArtifact).build()
        val sets: MutableList<NestedSet<Artifact?>?> = java.util.ArrayList<NestedSet<Artifact?>?>()
        sets.add(baseSet)
        // Create a NestedSet DAG with ((500 * 499) / 2) nodes, but with only 500 unique nodes. It
        // should be feasible to filter this NestedSet using memoization in a small test and we should
        // timeout if we aren't using memoization.
        for (i in 0..499) {
            val builder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
            builder.add(builtArtifact).add(failedArtifact)
            for (set in sets) {
                builder.addTransitive(set)
            }
            sets.add(builder.build())
        }
        val maxSet: NestedSet<Artifact?>? = com.google.common.collect.Iterables.getLast<NestedSet<Artifact?>?>(sets)

        initializeOutputGroupInfo(Pair.of("group", maxSet))

        val allArtifactsToBuild: ArtifactsToBuild =
            TopLevelArtifactHelper.getAllArtifactsToBuild(groupProvider, null, ctx)
        val outputGroups: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?>? =
            allArtifactsToBuild.getAllArtifactsByOutputGroup()

        val filter: SuccessfulArtifactFilter = SuccessfulArtifactFilter(successfulArtifacts)
        val groupArtifacts: MutableMap<String?, com.google.common.collect.ImmutableSet<Artifact?>?> =
            extractArtifactsByOutputGroup(filter.filterArtifactsInOutputGroup(outputGroups))
        Truth.assertThat(groupArtifacts.get("group")).containsExactlyElementsIn(successfulArtifacts)
    }

    private fun newArtifact(name: String?): SourceArtifact {
        return SourceArtifact(root, PathFragment.create(name), LabelArtifactOwner.NULL_OWNER)
    }

    companion object {
        private fun extractArtifactsByOutputGroup(
            outputGroups: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?>
        ): MutableMap<String?, com.google.common.collect.ImmutableSet<Artifact?>?> {
            val groupToDeclaredArtifacts: MutableMap<String?, com.google.common.collect.ImmutableSet<Artifact?>?> =
                HashMap<String?, com.google.common.collect.ImmutableSet<Artifact?>?>()
            for (entry in outputGroups.entries) {
                groupToDeclaredArtifacts.put(entry.key, entry.value.getArtifacts().toSet())
            }
            return groupToDeclaredArtifacts
        }
    }
}
