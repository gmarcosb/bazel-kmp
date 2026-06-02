// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact

/** Unit test for [ActionInputMap].  */
@RunWith(TestParameterInjector::class)
class ActionInputMapTest {
    // small hint to stress the map
    private val map: ActionInputMap = ActionInputMap(1)
    private val artifactRoot: ArtifactRoot = ArtifactRoot.asDerivedRoot(
        InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/execroot"),
        RootType.OUTPUT,
        "bazel-out"
    )

    @org.junit.Test
    fun basicPutAndLookup() {
        put("/abc/def", 5)
        assertThat(map.sizeForDebugging()).isEqualTo(1)
        assertContains("/abc/def", 5)
        assertThat(map.getMetadata(PathFragment.create("blah"))).isNull()
        assertThat(map.getInput(PathFragment.create("blah"))).isNull()
    }

    @org.junit.Test
    fun put_ignoresSubsequentPuts() {
        put("/abc/def", 5)
        assertThat(map.sizeForDebugging()).isEqualTo(1)
        put("/abc/def", 6)
        assertThat(map.sizeForDebugging()).isEqualTo(1)
        put("/ghi/jkl", 7)
        assertThat(map.sizeForDebugging()).isEqualTo(2)
        put("/ghi/jkl", 8)
        assertThat(map.sizeForDebugging()).isEqualTo(2)
        assertContains("/abc/def", 5)
        assertContains("/ghi/jkl", 7)
    }

    @org.junit.Test
    fun clear_removesAllElements() {
        val input1: ActionInput = TestInput("/abc/def")
        val input2: ActionInput = TestInput("/ghi/jkl")
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val treeChild: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "child")
        map.put(input1, TestMetadata.Companion.create(1))
        map.put(input2, TestMetadata.Companion.create(2))
        map.putTreeArtifact(
            tree,
            TreeArtifactValue.newBuilder(tree).putChild(treeChild, TestMetadata.Companion.create(3)).build()
        )
        // Sanity check
        assertThat(map.sizeForDebugging()).isEqualTo(3)

        map.clear()

        assertThat(map.sizeForDebugging()).isEqualTo(0)
        assertDoesNotContain(input1)
        assertDoesNotContain(input2)
        assertDoesNotContain(tree)
        assertDoesNotContain(treeChild)
    }

    @org.junit.Test
    fun putTreeArtifact_addsEmptyTreeArtifact() {
        val tree: SpecialArtifact = createTreeArtifact("tree")

        map.putTreeArtifact(tree, TreeArtifactValue.empty())

        assertThat(map.sizeForDebugging()).isEqualTo(1)
        assertContainsTree(tree, TreeArtifactValue.empty())
    }

    @org.junit.Test
    fun putTreeArtifact_addsTreeArtifactAndAllChildren() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val child1: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "child1")
        val child1Metadata: FileArtifactValue = TestMetadata.Companion.create(1)
        val child2: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "child2")
        val child2Metadata: FileArtifactValue = TestMetadata.Companion.create(2)
        val treeValue: TreeArtifactValue =
            TreeArtifactValue.newBuilder(tree)
                .putChild(child1, child1Metadata)
                .putChild(child2, child2Metadata)
                .build()

        map.putTreeArtifact(tree, treeValue)

        assertThat(map.sizeForDebugging()).isEqualTo(1)
        assertContainsTree(tree, treeValue)
        assertContainsFile(child1, child1Metadata)
        assertContainsFile(child2, child2Metadata)
    }

    @org.junit.Test
    fun putTreeArtifact_mixedTreeAndFiles_addsTreeAndChildren() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val child: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "child")
        val childMetadata: FileArtifactValue = TestMetadata.Companion.create(1)
        val file: ActionInput = ActionInputHelper.fromPath("file")
        val fileMetadata: FileArtifactValue = TestMetadata.Companion.create(2)
        map.put(file, fileMetadata)
        val treeValue: TreeArtifactValue =
            TreeArtifactValue.newBuilder(tree).putChild(child, childMetadata).build()

        map.putTreeArtifact(tree, treeValue)

        assertContainsTree(tree, treeValue)
        assertContainsFile(child, childMetadata)
        assertContainsFile(file, fileMetadata)
    }

    @org.junit.Test
    fun putTreeArtifact_multipleTrees_addsAllTreesAndChildren() {
        val tree1: SpecialArtifact = createTreeArtifact("tree1")
        val tree1Child: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree1, "child")
        val tree1ChildMetadata: FileArtifactValue = TestMetadata.Companion.create(1)
        val tree2: SpecialArtifact = createTreeArtifact("tree2")
        val tree2Child: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree2, "child")
        val tree2ChildMetadata: FileArtifactValue = TestMetadata.Companion.create(2)
        val tree1Value: TreeArtifactValue =
            TreeArtifactValue.newBuilder(tree1).putChild(tree1Child, tree1ChildMetadata).build()
        val tree2Value: TreeArtifactValue =
            TreeArtifactValue.newBuilder(tree2).putChild(tree2Child, tree2ChildMetadata).build()

        map.putTreeArtifact(tree1, tree1Value)
        map.putTreeArtifact(tree2, tree2Value)

        assertContainsTree(tree1, tree1Value)
        assertContainsFile(tree1Child, tree1ChildMetadata)
        assertContainsTree(tree2, tree2Value)
        assertContainsFile(tree2Child, tree2ChildMetadata)
    }

    @org.junit.Test
    fun putTreeArtifact_multipleTreesUnderSameDirectory_addsAllTrees() {
        val tree1: SpecialArtifact = createTreeArtifact("dir/tree1")
        val tree2: SpecialArtifact = createTreeArtifact("dir/tree2")
        val tree3: SpecialArtifact = createTreeArtifact("dir/tree3")

        map.putTreeArtifact(tree1, TreeArtifactValue.empty())
        map.putTreeArtifact(tree2, TreeArtifactValue.empty())
        map.putTreeArtifact(tree3, TreeArtifactValue.empty())

        assertContainsTree(tree1, TreeArtifactValue.empty())
        assertContainsTree(tree2, TreeArtifactValue.empty())
        assertContainsTree(tree3, TreeArtifactValue.empty())
    }

    @org.junit.Test
    fun putTreeArtifact_afterPutTreeArtifactWithSameExecPath_doesNothing() {
        val tree1: SpecialArtifact = createTreeArtifact("tree")
        val tree2: SpecialArtifact = createTreeArtifact("tree")
        val tree2File: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree2, "file")
        val tree1Value: TreeArtifactValue = TreeArtifactValue.empty()
        val tree2Value: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(tree2).putChild(tree2File, TestMetadata.Companion.create(1)).build()
        map.putTreeArtifact(tree1, tree1Value)

        map.putTreeArtifact(tree2, tree2Value)

        assertContainsTree(tree1, tree1Value)
        // Cannot assertContainsTree since the execpath will point to tree1 instead.
        assertThat(map.getInputMetadata(tree2)).isEqualTo(tree1Value.getMetadata())
        assertThat(map.getTreeMetadata(tree2.getExecPath())).isSameInstanceAs(tree1Value)
        assertThat(map.getInput(tree2.getExecPath())).isSameInstanceAs(tree1)
        assertDoesNotContain(tree2File)
    }

    @org.junit.Test
    fun putTreeArtifact_sameExecPathAsARegularFile_fails() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val file: ActionInput? = ActionInputHelper.fromPath(tree.getExecPath())
        map.put(file, TestMetadata.Companion.create(1))

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { map.putTreeArtifact(tree, TreeArtifactValue.empty()) })
    }

    private enum class PutOrder {
        DECLARED,
        REVERSE {
            override fun runPuts(put1: java.lang.Runnable, put2: java.lang.Runnable) {
                super.runPuts(put2, put1)
            }
        };

        open fun runPuts(put1: java.lang.Runnable, put2: java.lang.Runnable) {
            put1.run()
            put2.run()
        }
    }

    @org.junit.Test
    fun putTreeArtifact_nestedFile_returnsNestedFileFromExecPath(
        @TestParameter putOrder: PutOrder
    ) {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val treeFile: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "file")
        val treeFileMetadata: FileArtifactValue = TestMetadata.Companion.create(1)
        val file: ActionInput? = ActionInputHelper.fromPath(treeFile.getExecPath())
        val fileMetadata: FileArtifactValue = TestMetadata.Companion.create(1) // identical to `tree/file` file.
        val treeValue: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(tree).putChild(treeFile, treeFileMetadata).build()

        putOrder.runPuts(
            java.lang.Runnable { map.put(file, fileMetadata) },
            java.lang.Runnable { map.putTreeArtifact(tree, treeValue) })

        assertThat(map.getInputMetadata(file)).isSameInstanceAs(fileMetadata)
        assertThat(map.getInputMetadata(treeFile)).isSameInstanceAs(treeFileMetadata)
        assertThat(map.getMetadata(treeFile.getExecPath())).isSameInstanceAs(fileMetadata)
        assertThat(map.getInput(treeFile.getExecPath())).isSameInstanceAs(file)
    }

    @org.junit.Test
    fun put_treeFileArtifact_addsEntry() {
        val treeFile: TreeFileArtifact =
            TreeFileArtifact.createTreeOutput(createTreeArtifact("tree"), "file")
        val metadata: FileArtifactValue = TestMetadata.Companion.create(1)

        map.put(treeFile, metadata)

        assertContainsFile(treeFile, metadata)
    }

    @org.junit.Test
    fun put_sameExecPathAsATree_fails() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val file: ActionInput? = ActionInputHelper.fromPath(tree.getExecPath())
        val fileMetadata: FileArtifactValue = TestMetadata.Companion.create(1)
        map.putTreeArtifact(tree, TreeArtifactValue.empty())

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { map.put(file, fileMetadata) })
    }

    @org.junit.Test
    fun put_treeArtifact_fails() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val metadata: FileArtifactValue = TestMetadata.Companion.create(1)

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { map.put(tree, metadata) })
    }

    @org.junit.Test
    fun getMetadata_actionInputWithTreeExecPath_returnsTreeArtifactEntries() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        map.putTreeArtifact(tree, TreeArtifactValue.empty())
        val input: ActionInput? = ActionInputHelper.fromPath(tree.getExecPath())

        assertThat(map.getInputMetadata(input)).isEqualTo(TreeArtifactValue.empty().getMetadata())
    }

    @org.junit.Test
    fun getMetadata_actionInputWithTreeFileExecPath_returnsTreeArtifactEntries() {
        val inputMap: ActionInputMap = ActionInputMap( /* sizeHint= */1)
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val treeFile: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "file")
        val treeFileMetadata: FileArtifactValue = TestMetadata.Companion.create(1)
        val treeValue: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(tree).putChild(treeFile, treeFileMetadata).build()
        inputMap.putTreeArtifact(tree, treeValue)
        val input: ActionInput? = ActionInputHelper.fromPath(treeFile.getExecPath())

        val metadata: FileArtifactValue? = inputMap.getInputMetadata(input)

        assertThat(metadata).isSameInstanceAs(treeFileMetadata)
    }

    @org.junit.Test
    fun getMetadata_artifactWithTreeFileExecPath_returnsNull() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val treeFile: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "file")
        val treeValue: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(tree).putChild(treeFile, TestMetadata.Companion.create(1)).build()
        map.putTreeArtifact(tree, treeValue)
        val artifact: Artifact? =
            ActionsTestUtil.Companion.createArtifactWithExecPath(artifactRoot, treeFile.getExecPath())

        // Even though we could match the artifact by exec path, it was not registered as a nested
        // artifact -- only the tree file was.
        assertThat(map.getInputMetadata(artifact)).isNull()
    }

    @org.junit.Test
    fun getMetadata_missingFileWithinTree_returnsNull() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        map.putTreeArtifact(
            tree,
            TreeArtifactValue.newBuilder(tree)
                .putChild(TreeFileArtifact.createTreeOutput(tree, "file"), TestMetadata.Companion.create(1))
                .build()
        )
        val nonexistentTreeFile: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "nonexistent")

        assertDoesNotContain(nonexistentTreeFile)
    }

    @org.junit.Test
    fun getInputMetadata_treeFileUnderFile_fails() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val child: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(tree, "file")
        val file: ActionInput? = ActionInputHelper.fromPath(tree.getExecPath())
        map.put(file, TestMetadata.Companion.create(1))

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { map.getInputMetadata(child) })
    }

    @org.junit.Test
    fun getInputMetadata_subtreeFile() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val subtree: SpecialArtifact = createSubTreeArtifact(tree, "subdir")
        val child: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(subtree, "file")
        val treeFileMetadata: FileArtifactValue = TestMetadata.Companion.create(1)
        map.putTreeArtifact(
            subtree, TreeArtifactValue.newBuilder(subtree).putChild(child, treeFileMetadata).build()
        )

        assertThat(map.getInputMetadata(child)).isEqualTo(treeFileMetadata)
    }

    @org.junit.Test
    fun getInputMetadata_subtreeFileUnderTopLevelTree() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val subtree: SpecialArtifact = createSubTreeArtifact(tree, "subdir")
        val child: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(subtree, "file")
        val treeFileMetadata: FileArtifactValue = TestMetadata.Companion.create(1)
        // When the top-level tree is added (instead of the subtree)...
        map.putTreeArtifact(
            tree, TreeArtifactValue.newBuilder(tree).putChild(child, treeFileMetadata).build()
        )
        // getInputMetadata should successfully lookup the metadata under the top-level tree.
        assertThat(map.getInputMetadata(child)).isEqualTo(treeFileMetadata)
    }

    @org.junit.Test
    fun getTreeMetadataForPrefix_nonTree() {
        val file: ActionInput = ActionInputHelper.fromPath("some/file")
        map.put(file, TestMetadata.Companion.create(1))

        assertThat(map.getEnclosingTreeMetadata(file.getExecPath())).isNull()
        assertThat(map.getEnclosingTreeMetadata(file.getExecPath().getParentDirectory())).isNull()
        assertThat(map.getEnclosingTreeMetadata(file.getExecPath().getChild("under"))).isNull()
    }

    @org.junit.Test
    fun getTreeMetadataForPrefix_emptyTree() {
        val tree: SpecialArtifact = createTreeArtifact("a/tree")
        val treeValue: TreeArtifactValue? = TreeArtifactValue.newBuilder(tree).build()
        map.putTreeArtifact(tree, treeValue)

        assertThat(map.getEnclosingTreeMetadata(tree.getExecPath().getParentDirectory())).isNull()
        assertThat(map.getEnclosingTreeMetadata(tree.getExecPath())).isEqualTo(treeValue)
        assertThat(map.getEnclosingTreeMetadata(tree.getExecPath().getChild("under")))
            .isEqualTo(treeValue)
    }

    @org.junit.Test
    fun getTreeMetadataForPrefix_nonEmptyTree() {
        val tree: SpecialArtifact = createTreeArtifact("a/tree")
        val child: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "some/child")
        val treeValue: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(tree).putChild(child, TestMetadata.Companion.create(1)).build()
        map.putTreeArtifact(tree, treeValue)

        assertThat(map.getEnclosingTreeMetadata(tree.getExecPath().getParentDirectory())).isNull()
        assertThat(map.getEnclosingTreeMetadata(tree.getExecPath())).isEqualTo(treeValue)
        assertThat(map.getEnclosingTreeMetadata(child.getExecPath())).isEqualTo(treeValue)
        assertThat(map.getEnclosingTreeMetadata(child.getExecPath().getParentDirectory()))
            .isEqualTo(treeValue)
        assertThat(map.getEnclosingTreeMetadata(child.getExecPath().getChild("under")))
            .isEqualTo(treeValue)
    }

    @org.junit.Test
    fun getters_missingTree_returnNull() {
        map.putTreeArtifact(createTreeArtifact("tree"), TreeArtifactValue.empty())
        val otherTree: SpecialArtifact = createTreeArtifact("other")

        assertDoesNotContain(otherTree)
        assertDoesNotContain(TreeFileArtifact.createTreeOutput(otherTree, "child"))
    }

    @org.junit.Test
    fun stress() {
        val data: java.util.ArrayList<TestEntry> = java.util.ArrayList<TestEntry>()
        run {
            val rng: Random = Random()
            val deduper: HashSet<TestInput?> = HashSet<TestInput?>()
            for (i in 0..99999) {
                val bytes = ByteArray(80)
                rng.nextBytes(bytes)
                for (j in bytes.indices) {
                    bytes[j] = bytes[j].toInt() and (0x7f.toByte()).toInt()
                }
                val nextInput = TestInput(String(bytes, java.nio.charset.StandardCharsets.US_ASCII))
                if (deduper.add(nextInput)) {
                    data.add(TestEntry(nextInput, TestMetadata.Companion.create(i)))
                }
            }
        }
        for (iteration in 0..19) {
            map.clear()
            Collections.shuffle(data)
            for (i in data.indices) {
                val entry: TestEntry = data.get(i)
                map.put(entry.input, entry.metadata)
            }
            assertThat(map.sizeForDebugging()).isEqualTo(data.size())
            for (i in data.indices) {
                val entry: TestEntry = data.get(i)
                assertThat(map.getInputMetadata(entry.input)).isEqualTo(entry.metadata)
            }
        }
    }

    private fun put(execPath: String?, value: Int) {
        map.put(TestInput(execPath), TestMetadata.Companion.create(value))
    }

    private fun assertContains(execPath: String?, value: Int) {
        assertThat(map.getInputMetadata(TestInput(execPath))).isEqualTo(TestMetadata.Companion.create(value))
        assertThat(map.getMetadata(PathFragment.create(execPath)))
            .isEqualTo(TestMetadata.Companion.create(value))
        assertThat(map.getInput(PathFragment.create(execPath))).isEqualTo(TestInput(execPath))
    }

    private fun assertDoesNotContain(input: ActionInput) {
        assertThat(map.getInputMetadata(input)).isNull()
        assertThat(map.getMetadata(input.getExecPath())).isNull()
        assertThat(map.getTreeMetadata(input.getExecPath())).isNull()
        assertThat(map.getInput(input.getExecPath())).isNull()
    }

    private fun assertContainsFile(input: ActionInput, fileValue: FileArtifactValue?) {
        com.google.common.base.Preconditions.checkArgument(
            input !is SpecialArtifact,
            "use assertContainsTree for tree artifacts"
        )
        assertThat(map.getInputMetadata(input)).isSameInstanceAs(fileValue)
        assertThat(map.getMetadata(input.getExecPath())).isSameInstanceAs(fileValue)
        assertThat(map.getTreeMetadata(input.getExecPath())).isNull()
        assertThat(map.getInput(input.getExecPath())).isSameInstanceAs(input)
    }

    private fun assertContainsTree(input: SpecialArtifact, treeValue: TreeArtifactValue) {
        // TreeArtifactValue#getMetadata returns a freshly allocated instance.
        assertThat(map.getInputMetadata(input)).isEqualTo(treeValue.getMetadata())
        assertThat(map.getMetadata(input.getExecPath())).isEqualTo(treeValue.getMetadata())
        assertThat(map.getTreeMetadata(input.getExecPath())).isSameInstanceAs(treeValue)
        assertThat(map.getInput(input.getExecPath())).isSameInstanceAs(input)

        val trees: MutableMap<PathFragment?, TreeArtifactValue?> = HashMap<PathFragment?, TreeArtifactValue?>()
        map.forEachTreeArtifact({ key: K?, value: V? -> trees.put(key, value) })
        Truth.assertThat(trees).containsAtLeast(input.getExecPath(), treeValue)
    }

    private class TestEntry(input: TestInput?, metadata: TestMetadata?) {
        val input: TestInput?
        val metadata: TestMetadata?

        init {
            this.input = input
            this.metadata = metadata
        }
    }

    private class TestInput(fragment: String?) : ActionInput {
        private val fragment: PathFragment

        init {
            this.fragment = PathFragment.create(fragment)
        }

        public override fun isDirectory(): Boolean {
            return false
        }

        public override fun isSymlink(): Boolean {
            return false
        }

        public override fun getExecPath(): PathFragment {
            return fragment
        }

        public override fun getExecPathString(): String {
            return fragment.toString()
        }

        override fun equals(other: Any?): Boolean {
            if (other !is TestInput) {
                return false
            }
            if (this === other) {
                return true
            }
            return fragment.equals(other.fragment)
        }

        override fun hashCode(): Int {
            return fragment.hashCode()
        }
    }

    private fun createTreeArtifact(relativeExecPath: String?): SpecialArtifact {
        return createTreeArtifactWithGeneratingAction(
            artifactRoot, artifactRoot.getExecPath().getRelative(relativeExecPath)
        )
    }

    private fun createSubTreeArtifact(parent: SpecialArtifact?, parentRelativePath: String?): SpecialArtifact {
        val subtree: SpecialArtifact =
            SpecialArtifact.createSubTreeArtifact(
                parent, PathFragment.create(parentRelativePath), ActionsTestUtil.Companion.NULL_ARTIFACT_OWNER
            )
        subtree.setGeneratingActionKey(ActionsTestUtil.Companion.NULL_ACTION_LOOKUP_DATA)
        return subtree
    }

    @AutoValue
    internal abstract class TestMetadata : FileArtifactValue() {
        abstract fun id(): Int

        public override fun getType(): FileStateType {
            return FileStateType.REGULAR_FILE
        }

        public override fun getDigest(): ByteArray {
            return DigestHashFunction.SHA256.getHashFunction().hashInt(id()).asBytes()
        }

        public override fun getSize(): Long {
            return id().toLong()
        }

        public override fun getModifiedTime(): Long {
            throw java.lang.UnsupportedOperationException()
        }

        public override fun wasModifiedSinceDigest(path: Path?): Boolean {
            throw java.lang.UnsupportedOperationException()
        }

        public override fun getContentsProxy(): FileContentsProxy? {
            throw java.lang.UnsupportedOperationException()
        }

        companion object {
            fun create(id: Int): TestMetadata {
                return AutoValue_ActionInputMapTest_TestMetadata(id)
            }
        }
    }
}
