// Copyright 2022 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.skyframe.ActionExecutionValueTest.Companion.createWithDiscoveredModules

@RunWith(JUnit4::class)
class ActionExecutionValueTest {
    @org.junit.Test
    fun equality() {
        val tree1: SpecialArtifact = tree("tree1")
        val tree1Value1: TreeArtifactValue =
            TreeArtifactValue.newBuilder(tree1)
                .putChild(TreeFileArtifact.createTreeOutput(tree1, "file1"), VALUE_1_REMOTE)
                .build()
        val tree1Value2: TreeArtifactValue =
            TreeArtifactValue.newBuilder(tree1)
                .putChild(TreeFileArtifact.createTreeOutput(tree1, "file1"), VALUE_2_REMOTE)
                .build()
        val symlink1: FilesetOutputSymlink =
            FilesetOutputSymlink(
                PathFragment.create("name1"),
                ActionsTestUtil.createArtifact(OUTPUT_ROOT, "target1"),
                VALUE_1_REMOTE
            )
        val symlink2: FilesetOutputSymlink =
            FilesetOutputSymlink(
                PathFragment.create("name2"),
                ActionsTestUtil.createArtifact(OUTPUT_ROOT, "target2"),
                VALUE_2_REMOTE
            )

        EqualsTester()
            .addEqualityGroup(
                createWithArtifactData(
                    com.google.common.collect.ImmutableMap.of<Artifact?, FileArtifactValue?>(
                        output("file1"),
                        VALUE_1_REMOTE
                    )
                )
            )
            .addEqualityGroup(
                createWithArtifactData(
                    com.google.common.collect.ImmutableMap.of<Artifact?, FileArtifactValue?>(
                        output("file1"),
                        VALUE_2_REMOTE
                    )
                )
            )
            .addEqualityGroup(
                createWithArtifactData(
                    com.google.common.collect.ImmutableMap.of<Artifact?, FileArtifactValue?>(
                        output(
                            "file1",
                            ACTION_LOOKUP_DATA_2
                        ), VALUE_1_REMOTE
                    )
                )
            )
            .addEqualityGroup(
                createWithArtifactData(
                    com.google.common.collect.ImmutableMap.of<Artifact?, FileArtifactValue?>(
                        output("file2"),
                        VALUE_1_REMOTE
                    )
                )
            )
            .addEqualityGroup(
                createWithArtifactData(
                    com.google.common.collect.ImmutableMap.of<Artifact?, FileArtifactValue?>(
                        output("file1"),
                        VALUE_1_REMOTE,
                        output("file2"),
                        VALUE_2_REMOTE
                    )
                )
            ) // treeArtifactData
            .addEqualityGroup(
                createWithTreeArtifactData(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        tree1,
                        TreeArtifactValue.empty()
                    )
                )
            )
            .addEqualityGroup(
                createWithTreeArtifactData(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        tree("tree2"),
                        TreeArtifactValue.empty()
                    )
                )
            )
            .addEqualityGroup(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    tree1, TreeArtifactValue.empty(), tree("tree2"), TreeArtifactValue.empty()
                )
            )
            .addEqualityGroup(com.google.common.collect.ImmutableMap.of<Any?, Any?>(tree1, tree1Value1))
            .addEqualityGroup(
                com.google.common.collect.ImmutableMap.of<Any?, Any?>(
                    tree1,
                    tree1Value2
                )
            ) // outputSymlinks
            .addEqualityGroup(
                createWithFilesetOutput(
                    FilesetOutputTree.create(
                        com.google.common.collect.ImmutableList.of<E?>(symlink1),  /* treeArtifacts= */
                        com.google.common.collect.ImmutableMap.of<K?, V?>()
                    )
                )
            )
            .addEqualityGroup(
                createWithFilesetOutput(
                    FilesetOutputTree.create(
                        com.google.common.collect.ImmutableList.of<E?>(symlink2),  /* treeArtifacts= */
                        com.google.common.collect.ImmutableMap.of<K?, V?>()
                    )
                )
            )
            .addEqualityGroup(
                createWithFilesetOutput(
                    FilesetOutputTree.create(
                        com.google.common.collect.ImmutableList.of<E?>(symlink1, symlink2),  /* treeArtifacts= */
                        com.google.common.collect.ImmutableMap.of<K?, V?>()
                    )
                )
            ) // discoveredModules
            .addEqualityGroup(
                createWithDiscoveredModules(
                    NestedSetBuilder.create(Order.STABLE_ORDER, output("file1"))
                ),
                createWithDiscoveredModules(
                    NestedSetBuilder.create(Order.STABLE_ORDER, output("file1"))
                )
            )
            .addEqualityGroup(
                createWithDiscoveredModules(
                    NestedSetBuilder.create(Order.STABLE_ORDER, output("file1", ACTION_LOOKUP_DATA_2))
                )
            )
            .addEqualityGroup(
                createWithDiscoveredModules(
                    NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ().add(output("file2"))
                )
            )
            .addEqualityGroup(
                createWithDiscoveredModules(
                    NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ().add(output("file1"))
                        .add(output("file2"))
                )
            ) // Does not detect equality for identical sets with different shape.
            .addEqualityGroup(
                createWithDiscoveredModules(
                    NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                        .add(output("file1"))
                        .addTransitive(NestedSetBuilder.create(Order.STABLE_ORDER, output("file2")))
                )
            )
            .testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serialization() {
        SerializationTester( // Single output file
            createWithArtifactData(
                com.google.common.collect.ImmutableMap.of<Artifact?, FileArtifactValue?>(
                    output("output1"),
                    VALUE_1_REMOTE
                )
            ),  // Fileset
            createWithFilesetOutput(
                FilesetOutputTree.create(
                    com.google.common.collect.ImmutableList.of<E?>(
                        FilesetOutputSymlink(
                            PathFragment.create("name"), output("target"), VALUE_1_REMOTE
                        )
                    ),  /* treeArtifacts= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>()
                )
            ),  // Module discovering
            createWithDiscoveredModules(
                NestedSetBuilder.create(Order.STABLE_ORDER, output("module"))
            ),  // Multiple output files
            createWithArtifactData(
                com.google.common.collect.ImmutableMap.of<Artifact?, FileArtifactValue?>(
                    output("output1"), VALUE_1_REMOTE, output("output2"), VALUE_2_REMOTE
                )
            ),  // Single tree
            createWithTreeArtifactData(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    tree("tree"),
                    TreeArtifactValue.empty()
                )
            ),  // Multiple trees
            createWithTreeArtifactData(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    tree("tree1"),
                    TreeArtifactValue.empty(),
                    tree("tree2"),
                    TreeArtifactValue.empty()
                )
            ),  // Mixed file and tree
            ActionExecutionValue.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(output("file"), VALUE_1_REMOTE),
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    tree("tree"),
                    TreeArtifactValue.empty()
                ),  /* richArtifactData= */
                null,  /* discoveredModules= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )
        )
            .addDependency(FileSystem::class.java, OUTPUT_ROOT.getRoot().getFileSystem())
            .addDependency(
                RootCodecDependencies::class.java, RootCodecDependencies(OUTPUT_ROOT.getRoot())
            )
            .addDependencies(SerializationDepsUtils.SERIALIZATION_DEPS_FOR_TEST)
            .runTests()
    }

    companion object {
        private val VALUE_1_REMOTE: FileArtifactValue = FileArtifactValue.createForRemoteFile( /* digest= */
            ByteArray(0),  /* size= */0,  /* locationIndex= */1
        )
        private val VALUE_2_REMOTE: FileArtifactValue = FileArtifactValue.createForRemoteFile( /* digest= */
            ByteArray(0),  /* size= */0,  /* locationIndex= */2
        )

        private val KEY: ActionLookupKey = ActionsTestUtil.NULL_ARTIFACT_OWNER
        private val ACTION_LOOKUP_DATA_1: ActionLookupData = ActionLookupData.create(KEY, 1)
        private val ACTION_LOOKUP_DATA_2: ActionLookupData = ActionLookupData.create(KEY, 2)

        private val OUTPUT_ROOT: ArtifactRoot =
            ArtifactRoot.asDerivedRoot(Scratch().resolve("/execroot"), RootType.OUTPUT, "out")

        private fun createWithArtifactData(
            artifactData: com.google.common.collect.ImmutableMap<Artifact?, FileArtifactValue?>?
        ): ActionExecutionValue {
            return ActionExecutionValue.create( /* artifactData= */
                artifactData,  /* treeArtifactData= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* richArtifactData= */
                null,  /* discoveredModules= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )
        }

        private fun createWithTreeArtifactData(
            treeArtifactData: com.google.common.collect.ImmutableMap<Artifact?, TreeArtifactValue?>?
        ): ActionExecutionValue {
            return ActionExecutionValue.create( /* artifactData= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                treeArtifactData,  /* richArtifactData= */
                null,  /* discoveredModules= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )
        }

        private fun createWithFilesetOutput(filesetOutput: FilesetOutputTree?): ActionExecutionValue {
            return ActionExecutionValue.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    output("fileset.manifest"),
                    VALUE_1_REMOTE
                ),  /* treeArtifactData= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                filesetOutput,  /* discoveredModules= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )
        }

        private fun createWithDiscoveredModules(
            discoveredModules: NestedSetBuilder<Artifact?>
        ): ActionExecutionValue? {
            return createWithDiscoveredModules(discoveredModules.build())
        }

        private fun createWithDiscoveredModules(
            discoveredModules: NestedSet<Artifact?>?
        ): ActionExecutionValue {
            return ActionExecutionValue.create( /* artifactData= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    output("modules.pcm"),
                    VALUE_1_REMOTE
                ),  /* treeArtifactData= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* richArtifactData= */
                null,
                discoveredModules
            )
        }

        private fun output(rootRelativePath: String?): DerivedArtifact {
            return output(rootRelativePath, ACTION_LOOKUP_DATA_1)
        }

        private fun output(
            rootRelativePath: String?, generatingAction: ActionLookupData
        ): DerivedArtifact {
            val result: DerivedArtifact =
                DerivedArtifact.create(
                    OUTPUT_ROOT,
                    OUTPUT_ROOT.getExecPath().getRelative(rootRelativePath),
                    generatingAction.getActionLookupKey()
                )
            result.setGeneratingActionKey(generatingAction)
            return result
        }

        private fun tree(rootRelativePath: String?): SpecialArtifact {
            val result: SpecialArtifact =
                SpecialArtifact.create(
                    OUTPUT_ROOT,
                    OUTPUT_ROOT.getExecPath().getRelative(rootRelativePath),
                    KEY,
                    SpecialArtifactType.TREE
                )
            result.setGeneratingActionKey(ACTION_LOOKUP_DATA_1)
            return result
        }
    }
}
