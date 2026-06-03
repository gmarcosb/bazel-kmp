// Copyright 2020 The Bazel Authors. All rights reserved.
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

/**
 * Tests for [ActionExecutionValue.transformForSharedAction] for values including tree
 * artifacts.
 */
@RunWith(TestParameterInjector::class)
class ActionExecutionValueTransformSharedTreeArtifactsTest {
    @TestParameter
    private val includeArchivedTreeArtifacts = false

    private val scratch: Scratch = Scratch()
    private var derivedRoot: ArtifactRoot? = null

    @Before
    @Throws(IOException::class)
    fun createDerivedRoot() {
        derivedRoot =
            ArtifactRoot.asDerivedRoot(scratch.dir("/execroot"), RootType.OUTPUT, DERIVED_PATH_PREFIX)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transformForSharedAction_createsCopyOfEmptyTreeArtifact() {
        val tree: SpecialArtifact = createTreeArtifact("dir", KEY_1)
        val value: TreeArtifactValue = createTreeArtifactValue(tree)
        val actionExecutionValue: ActionExecutionValue =
            createActionExecutionValue(
                com.google.common.collect.ImmutableMap.of<Artifact?, TreeArtifactValue?>(
                    tree,
                    value
                )
            )

        val tree2: SpecialArtifact = createTreeArtifact("dir", KEY_2)
        val transformedValue: ActionExecutionValue =
            actionExecutionValue.transformForSharedAction(NullAction(tree2))

        assertThat(transformedValue.allFileValues).isEmpty()
        assertThat(transformedValue.getAllTreeArtifactValues().keySet()).containsExactly(tree2)
        assertEqualsWithNewParent(value, tree2, transformedValue.getTreeArtifactValue(tree2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transformForSharedAction_createsCopyOfTreeArtifact() {
        val tree: SpecialArtifact = createTreeArtifact("dir", KEY_1)
        val value: TreeArtifactValue = createTreeArtifactValue(tree, "file1", "file2")
        val actionExecutionValue: ActionExecutionValue =
            createActionExecutionValue(
                com.google.common.collect.ImmutableMap.of<Artifact?, TreeArtifactValue?>(
                    tree,
                    value
                )
            )

        val tree2: SpecialArtifact = createTreeArtifact("dir", KEY_2)
        val transformedValue: ActionExecutionValue =
            actionExecutionValue.transformForSharedAction(NullAction(tree2))

        assertThat(transformedValue.allFileValues).isEmpty()
        assertThat(transformedValue.getAllTreeArtifactValues().keySet()).containsExactly(tree2)
        assertEqualsWithNewParent(value, tree2, transformedValue.getTreeArtifactValue(tree2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transformForSharedAction_createsCopyOfMultipleTreeArtifacts() {
        val tree1: SpecialArtifact = createTreeArtifact("dir1", KEY_1)
        val tree2: SpecialArtifact = createTreeArtifact("dir2", KEY_1)
        val value1: TreeArtifactValue = createTreeArtifactValue(tree1, "file1")
        val value2: TreeArtifactValue = createTreeArtifactValue(tree2, "file2", "file3")
        val actionExecutionValue: ActionExecutionValue =
            createActionExecutionValue(
                com.google.common.collect.ImmutableMap.of<Artifact?, TreeArtifactValue?>(
                    tree1,
                    value1,
                    tree2,
                    value2
                )
            )

        val sharedTree1: SpecialArtifact = createTreeArtifact("dir1", KEY_2)
        val sharedTree2: SpecialArtifact = createTreeArtifact("dir2", KEY_2)
        val transformedValue: ActionExecutionValue =
            actionExecutionValue.transformForSharedAction(NullAction(sharedTree1, sharedTree2))

        assertThat(transformedValue.allFileValues).isEmpty()
        assertThat(transformedValue.getAllTreeArtifactValues().keySet())
            .containsExactly(sharedTree1, sharedTree2)
        assertEqualsWithNewParent(
            value1, sharedTree1, transformedValue.getTreeArtifactValue(sharedTree1)
        )
        assertEqualsWithNewParent(
            value2, sharedTree2, transformedValue.getTreeArtifactValue(sharedTree2)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transformForSharedAction_createsCopyForFileAndTreeArtifacts() {
        val file: DerivedArtifact = createFileArtifact("file", KEY_1)
        createFile(file.getPath())
        val fileValue: FileArtifactValue = FileArtifactValue.createForTesting(file)
        val tree: SpecialArtifact = createTreeArtifact("dir", KEY_1)
        val treeValue: TreeArtifactValue = createTreeArtifactValue(tree, "file1", "file2")
        val actionExecutionValue: ActionExecutionValue =
            createActionExecutionValue(
                com.google.common.collect.ImmutableMap.of<Artifact?, FileArtifactValue?>(file, fileValue),
                com.google.common.collect.ImmutableMap.of<Artifact?, TreeArtifactValue?>(tree, treeValue)
            )

        val sharedTree: SpecialArtifact = createTreeArtifact("dir", KEY_2)
        val sharedFile: DerivedArtifact = createFileArtifact("file", KEY_2)
        val transformedValue: ActionExecutionValue =
            actionExecutionValue.transformForSharedAction(NullAction(sharedFile, sharedTree))

        assertThat(transformedValue.allFileValues.keySet()).containsExactly(sharedFile)
        assertThat(transformedValue.allFileValues.get(sharedFile)).isSameInstanceAs(fileValue)
        assertThat(transformedValue.getAllTreeArtifactValues().keySet()).containsExactly(sharedTree)
        assertEqualsWithNewParent(
            treeValue, sharedTree, transformedValue.getTreeArtifactValue(sharedTree)
        )
    }

    /**
     * Checks that provided [TreeArtifactValue] has equal metadata to the original one, expected
     * parent for all of the included artifacts and otherwise the same artifacts as the original one.
     */
    private fun assertEqualsWithNewParent(
        originalValue: TreeArtifactValue,
        expectedTree: SpecialArtifact,
        actualValue: TreeArtifactValue
    ) {
        assertThat(actualValue.getDigest()).isEqualTo(originalValue.getDigest())
        assertThat(actualValue.getMetadata()).isEqualTo(originalValue.getMetadata())
        assertThat(actualValue.getChildPaths()).isEqualTo(originalValue.getChildPaths())

        assertThat(actualValue.getArchivedRepresentation().isPresent())
            .isEqualTo(includeArchivedTreeArtifacts)

        actualValue
            .getArchivedRepresentation()
            .ifPresent(
                { archivedRepresentation ->
                    val originalRepresentation: ArchivedRepresentation? =
                        originalValue.getArchivedRepresentation().get()
                    assertEqualsWithNewParent(
                        originalRepresentation, expectedTree, archivedRepresentation
                    )
                })

        actualValue
            .getChildValues()
            .forEach(
                { artifact, metadata ->
                    val originalArtifact: TreeFileArtifact =
                        originalValue.getChildren().stream()
                            .filter(
                                { original ->
                                    original
                                        .getParentRelativePath()
                                        .equals(artifact.getParentRelativePath())
                                })
                            .findAny()
                            .get()
                    assertThat(artifact.getParent()).isEqualTo(expectedTree)
                    assertThat(artifact.getGeneratingActionKey())
                        .isEqualTo(expectedTree.getGeneratingActionKey())
                    assertOwnerlessEquals(originalArtifact, artifact)
                    assertThat(artifact.isChildOfDeclaredDirectory())
                        .isEqualTo(originalArtifact.isChildOfDeclaredDirectory())
                    assertThat(metadata)
                        .isSameInstanceAs(originalValue.getChildValues().get(originalArtifact))
                })
    }

    @Throws(IOException::class)
    private fun createTreeArtifactValue(
        treeArtifact: SpecialArtifact?, vararg parentRelativePaths: String?
    ): TreeArtifactValue {
        val builder: TreeArtifactValue.Builder = TreeArtifactValue.newBuilder(treeArtifact)

        for (parentRelativePath in parentRelativePaths) {
            val childArtifact: TreeFileArtifact =
                TreeFileArtifact.createTreeOutput(treeArtifact, parentRelativePath)
            createFile(childArtifact.getPath())
            builder.putChild(childArtifact, FileArtifactValue.createForTesting(childArtifact))
        }

        if (includeArchivedTreeArtifacts) {
            val archivedArtifact: ArchivedTreeArtifact = ArchivedTreeArtifact.createForTree(treeArtifact)
            createFile(archivedArtifact.getPath())
            builder.setArchivedRepresentation(
                archivedArtifact, FileArtifactValue.createForTesting(archivedArtifact)
            )
        }

        return builder.build()
    }

    private fun createFileArtifact(relativePath: String?, owner: ActionLookupKey?): DerivedArtifact {
        return DerivedArtifact.create(
            derivedRoot, DERIVED_PATH_PREFIX.getRelative(relativePath), owner
        )
    }

    private fun createTreeArtifact(relativePath: String?, owner: ActionLookupKey?): SpecialArtifact {
        val treeArtifact: SpecialArtifact =
            SpecialArtifact.create(
                derivedRoot,
                DERIVED_PATH_PREFIX.getRelative(relativePath),
                owner,
                SpecialArtifactType.TREE
            )
        treeArtifact.setGeneratingActionKey(ActionLookupData.create(owner, 0))
        return treeArtifact
    }

    companion object {
        private val DERIVED_PATH_PREFIX: PathFragment = PathFragment.create("bazel-out")

        private val KEY_1: ActionLookupKey? = Mockito.mock<ActionLookupKey?>(ActionLookupKey::class.java)
        private val KEY_2: ActionLookupKey? = Mockito.mock<ActionLookupKey?>(ActionLookupKey::class.java)

        private fun assertEqualsWithNewParent(
            expectedRepresentation: ArchivedRepresentation,
            expectedTree: SpecialArtifact,
            actualRepresentation: ArchivedRepresentation
        ) {
            assertThat(actualRepresentation.archivedTreeFileArtifact().getParent()).isEqualTo(expectedTree)
            assertThat(actualRepresentation.archivedTreeFileArtifact().getGeneratingActionKey())
                .isEqualTo(expectedTree.getGeneratingActionKey())
            assertOwnerlessEquals(
                expectedRepresentation.archivedTreeFileArtifact(),
                actualRepresentation.archivedTreeFileArtifact()
            )
            assertThat(actualRepresentation.archivedFileValue())
                .isSameInstanceAs(expectedRepresentation.archivedFileValue())
        }

        private fun assertOwnerlessEquals(expectedArtifact: Artifact?, actualArtifact: Artifact?) {
            assertThat(OwnerlessArtifactWrapper(actualArtifact))
                .isEqualTo(OwnerlessArtifactWrapper(expectedArtifact))
        }

        private fun createActionExecutionValue(
            treeArtifacts: com.google.common.collect.ImmutableMap<Artifact?, TreeArtifactValue?>?
        ): ActionExecutionValue {
            return createActionExecutionValue( /*fileArtifacts=*/com.google.common.collect.ImmutableMap.of<Artifact?, FileArtifactValue?>(),
                treeArtifacts
            )
        }

        private fun createActionExecutionValue(
            fileArtifacts: com.google.common.collect.ImmutableMap<Artifact?, FileArtifactValue?>?,
            treeArtifacts: com.google.common.collect.ImmutableMap<Artifact?, TreeArtifactValue?>?
        ): ActionExecutionValue {
            return ActionExecutionValue.create(
                fileArtifacts,
                treeArtifacts,  /* richArtifactData= */
                null,  /* discoveredModules= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )
        }

        @Throws(IOException::class)
        private fun createFile(file: Path?) {
            FileSystemUtils.writeIsoLatin1(file)
        }
    }
}
