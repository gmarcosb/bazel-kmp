// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionInput

/** Tests for [SpawnInputExpander].  */
@RunWith(JUnit4::class)
class SpawnInputExpanderTest {
    private val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    private val execRoot: Path = fs.getPath("/root")
    private val rootDir: ArtifactRoot? = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out")

    private var expander: SpawnInputExpander = SpawnInputExpander()
    private val inputMap: MutableMap<PathFragment?, ActionInput?> = HashMap<PathFragment?, ActionInput?>()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesSingleFile() {
        val artifact: Artifact? =
            ActionsTestUtil.createArtifact(
                ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
                fs.getPath("/root/dir/file")
            )
        val runfiles: Runfiles = Builder("workspace").addArtifact(artifact).build()
        val runfilesTree: RunfilesTree =
            AnalysisTestUtil.createRunfilesTree(PathFragment.create("runfiles"), runfiles)

        expander.addSingleRunfilesTreeToInputs(
            runfilesTree,
            inputMap,
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache(),
            PathMapper.NOOP,
            PathFragment.EMPTY_FRAGMENT
        )

        Truth.assertThat(inputMap)
            .containsExactly(PathFragment.create("runfiles/workspace/dir/file"), artifact)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesWithFileset() {
        val fileset: Artifact = createFilesetArtifact("foo/biz/fs_out")
        val runfiles: Runfiles = Builder("workspace").addArtifact(fileset).build()
        val runfilesTree: RunfilesTree =
            AnalysisTestUtil.createRunfilesTree(PathFragment.create("runfiles"), runfiles)
        val link: FilesetOutputSymlink = filesetSymlink("zizz", "xyz/zizz")
        val filesetOutputTree: FilesetOutputTree? =
            FilesetOutputTree.create(
                com.google.common.collect.ImmutableList.of<E?>(link),  /* treeArtifacts= */
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )

        val fakeActionInputFileCache: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        fakeActionInputFileCache.putFileset(fileset, filesetOutputTree)
        expander.addSingleRunfilesTreeToInputs(
            runfilesTree,
            inputMap,
            fakeActionInputFileCache,
            PathMapper.NOOP,
            PathFragment.EMPTY_FRAGMENT
        )

        Truth.assertThat(inputMap)
            .containsExactly(
                PathFragment.create("runfiles/workspace/foo/biz/fs_out/zizz"), link.target()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesDirectoryNonStrict() {
        val artifact: Artifact? =
            ActionsTestUtil.createArtifact(
                ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
                fs.getPath("/root/dir/file")
            )
        val runfiles: Runfiles = Builder("workspace").addArtifact(artifact).build()
        val runfilesTree: RunfilesTree =
            AnalysisTestUtil.createRunfilesTree(PathFragment.create("runfiles"), runfiles)

        expander.addSingleRunfilesTreeToInputs(
            runfilesTree,
            inputMap,
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache(),
            PathMapper.NOOP,
            PathFragment.EMPTY_FRAGMENT
        )
        Truth.assertThat(inputMap)
            .containsExactly(PathFragment.create("runfiles/workspace/dir/file"), artifact)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesTwoFiles() {
        val artifact1: Artifact? =
            ActionsTestUtil.createArtifact(
                ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
                fs.getPath("/root/dir/file")
            )
        val artifact2: Artifact? =
            ActionsTestUtil.createArtifact(
                ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
                fs.getPath("/root/dir/baz")
            )
        val runfiles: Runfiles =
            Builder("workspace").addArtifact(artifact1).addArtifact(artifact2).build()
        val runfilesTree: RunfilesTree =
            AnalysisTestUtil.createRunfilesTree(PathFragment.create("runfiles"), runfiles)

        expander.addSingleRunfilesTreeToInputs(
            runfilesTree,
            inputMap,
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache(),
            PathMapper.NOOP,
            PathFragment.EMPTY_FRAGMENT
        )
        Truth.assertThat(inputMap)
            .containsExactly(
                PathFragment.create("runfiles/workspace/dir/file"), artifact1,
                PathFragment.create("runfiles/workspace/dir/baz"), artifact2
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesTwoFiles_pathMapped() {
        val artifact1: Artifact? =
            ActionsTestUtil.createArtifact(
                ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
                fs.getPath("/root/dir/file")
            )
        val artifact2: Artifact? =
            ActionsTestUtil.createArtifact(
                ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
                fs.getPath("/root/dir/baz")
            )
        val runfiles: Runfiles =
            Builder("workspace").addArtifact(artifact1).addArtifact(artifact2).build()
        val runfilesTree: RunfilesTree =
            AnalysisTestUtil.createRunfilesTree(
                PathFragment.create("bazel-out/k8-opt/bin/foo.runfiles"), runfiles
            )

        expander.addSingleRunfilesTreeToInputs(
            runfilesTree,
            inputMap,
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache(),
            { execPath -> PathFragment.create(execPath.getPathString().replace("k8-opt/", "")) },
            PathFragment.EMPTY_FRAGMENT
        )

        Truth.assertThat(inputMap)
            .containsExactly(
                PathFragment.create("bazel-out/bin/foo.runfiles/workspace/dir/file"),
                artifact1,
                PathFragment.create("bazel-out/bin/foo.runfiles/workspace/dir/baz"),
                artifact2
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesSymlink() {
        val artifact: Artifact? =
            ActionsTestUtil.createArtifact(
                ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
                fs.getPath("/root/dir/file")
            )
        val runfiles: Runfiles =
            Builder("workspace")
                .addSymlink(PathFragment.create("symlink"), artifact)
                .build()
        val runfilesTree: RunfilesTree =
            AnalysisTestUtil.createRunfilesTree(PathFragment.create("runfiles"), runfiles)

        expander.addSingleRunfilesTreeToInputs(
            runfilesTree,
            inputMap,
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache(),
            PathMapper.NOOP,
            PathFragment.EMPTY_FRAGMENT
        )

        Truth.assertThat(inputMap)
            .containsExactly(PathFragment.create("runfiles/workspace/symlink"), artifact)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesRootSymlink() {
        val artifact: Artifact? =
            ActionsTestUtil.createArtifact(
                ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
                fs.getPath("/root/dir/file")
            )
        val runfiles: Runfiles =
            Builder("workspace")
                .addRootSymlink(PathFragment.create("symlink"), artifact)
                .build()
        val runfilesTree: RunfilesTree =
            AnalysisTestUtil.createRunfilesTree(PathFragment.create("runfiles"), runfiles)

        expander.addSingleRunfilesTreeToInputs(
            runfilesTree,
            inputMap,
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache(),
            PathMapper.NOOP,
            PathFragment.EMPTY_FRAGMENT
        )

        Truth.assertThat(inputMap)
            .containsExactly(
                PathFragment.create("runfiles/symlink"),
                artifact,  // If there's no other entry, Runfiles adds an empty file in the workspace to make sure
                // the directory gets created.
                PathFragment.create("runfiles/workspace/.runfile"),
                VirtualActionInput.EMPTY_MARKER
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesWithTreeArtifacts() {
        val treeArtifact: SpecialArtifact = createTreeArtifact("treeArtifact")
        val file1: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "file1")
        val file2: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "file2")
        FileSystemUtils.writeContentAsLatin1(file1.getPath(), "foo")
        FileSystemUtils.writeContentAsLatin1(file2.getPath(), "bar")

        val treeArtifactValue: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(treeArtifact)
                .putChild(file1, FileArtifactValue.createForTesting(file1.getPath()))
                .putChild(file2, FileArtifactValue.createForTesting(file2.getPath()))
                .build()

        val fakeActionInputFileCache: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        fakeActionInputFileCache.putTreeArtifact(treeArtifact, treeArtifactValue)

        val runfiles: Runfiles = Builder("workspace").addArtifact(treeArtifact).build()
        val runfilesTree: RunfilesTree =
            AnalysisTestUtil.createRunfilesTree(PathFragment.create("runfiles"), runfiles)

        expander.addSingleRunfilesTreeToInputs(
            runfilesTree,
            inputMap,
            fakeActionInputFileCache,
            PathMapper.NOOP,
            PathFragment.EMPTY_FRAGMENT
        )

        Truth.assertThat(inputMap)
            .containsExactly(
                PathFragment.create("runfiles/workspace/treeArtifact/file1"), file1,
                PathFragment.create("runfiles/workspace/treeArtifact/file2"), file2
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesWithTreeArtifacts_pathMapped() {
        val treeArtifact: SpecialArtifact = createTreeArtifact("treeArtifact")
        val file1: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "file1")
        val file2: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "file2")
        FileSystemUtils.writeContentAsLatin1(file1.getPath(), "foo")
        FileSystemUtils.writeContentAsLatin1(file2.getPath(), "bar")

        val treeArtifactValue: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(treeArtifact)
                .putChild(file1, FileArtifactValue.createForTesting(file1.getPath()))
                .putChild(file2, FileArtifactValue.createForTesting(file2.getPath()))
                .build()

        val fakeActionInputFileCache: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        fakeActionInputFileCache.putTreeArtifact(treeArtifact, treeArtifactValue)

        val runfiles: Runfiles = Builder("workspace").addArtifact(treeArtifact).build()
        val runfilesTree: RunfilesTree =
            AnalysisTestUtil.createRunfilesTree(
                PathFragment.create("bazel-out/k8-opt/bin/foo.runfiles"), runfiles
            )

        val pathMapper: PathMapper =
            PathMapper { execPath ->
                // Replace the config segment "k8-opt" in "bazel-bin/k8-opt/bin" with a hash of the full
                // path to verify that the new paths are constructed by appending the child paths to the
                // mapped parent path, not by mapping the child paths directly.
                val runfilesPath: PathFragment = execPath.subFragment(3)
                val runfilesPathHash =
                    DigestHashFunction.SHA256
                        .getHashFunction()
                        .hashString(runfilesPath.getPathString(), java.nio.charset.StandardCharsets.UTF_8)
                        .toString()
                execPath
                    .subFragment(0, 1)
                    .getRelative(runfilesPathHash.substring(0, 8))
                    .getRelative(execPath.subFragment(2))
            }

        expander.addSingleRunfilesTreeToInputs(
            runfilesTree, inputMap, fakeActionInputFileCache, pathMapper, PathFragment.EMPTY_FRAGMENT
        )

        Truth.assertThat(inputMap)
            .containsExactly(
                PathFragment.create("bazel-out/2c26b46b/bin/foo.runfiles/workspace/treeArtifact/file1"),
                file1,
                PathFragment.create("bazel-out/2c26b46b/bin/foo.runfiles/workspace/treeArtifact/file2"),
                file2
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesWithArchivedTreeArtifacts() {
        val treeArtifact: SpecialArtifact = createTreeArtifact("treeArtifact")
        val archivedTreeArtifact: ArchivedTreeArtifact? = ArchivedTreeArtifact.createForTree(treeArtifact)
        val treeArtifactValue: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(treeArtifact)
                .setArchivedRepresentation(archivedTreeArtifact, FileArtifactValue.MISSING_FILE_MARKER)
                .build()

        val fakeActionInputFileCache: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        fakeActionInputFileCache.putTreeArtifact(treeArtifact, treeArtifactValue)

        val runfiles: Runfiles = Builder("workspace").addArtifact(treeArtifact).build()
        val runfilesTree: RunfilesTree =
            AnalysisTestUtil.createRunfilesTree(PathFragment.create("runfiles"), runfiles)

        expander = SpawnInputExpander( /* expandArchivedTreeArtifacts= */false)
        expander.addSingleRunfilesTreeToInputs(
            runfilesTree,
            inputMap,
            fakeActionInputFileCache,
            PathMapper.NOOP,
            PathFragment.EMPTY_FRAGMENT
        )

        Truth.assertThat(inputMap)
            .containsExactly(
                PathFragment.create("runfiles/workspace/treeArtifact"), archivedTreeArtifact
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesWithTreeArtifactsInSymlinks() {
        val treeArtifact: SpecialArtifact = createTreeArtifact("treeArtifact")
        val file1: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "file1")
        val file2: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "file2")
        FileSystemUtils.writeContentAsLatin1(file1.getPath(), "foo")
        FileSystemUtils.writeContentAsLatin1(file2.getPath(), "bar")
        val treeArtifactValue: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(treeArtifact)
                .putChild(file1, FileArtifactValue.createForTesting(file1.getPath()))
                .putChild(file2, FileArtifactValue.createForTesting(file2.getPath()))
                .build()

        val fakeActionInputFileCache: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        fakeActionInputFileCache.putTreeArtifact(treeArtifact, treeArtifactValue)

        val runfiles: Runfiles =
            Builder("workspace")
                .addSymlink(PathFragment.create("symlink"), treeArtifact)
                .build()
        val runfilesTree: RunfilesTree =
            AnalysisTestUtil.createRunfilesTree(PathFragment.create("runfiles"), runfiles)

        expander.addSingleRunfilesTreeToInputs(
            runfilesTree,
            inputMap,
            fakeActionInputFileCache,
            PathMapper.NOOP,
            PathFragment.EMPTY_FRAGMENT
        )

        Truth.assertThat(inputMap)
            .containsExactly(
                PathFragment.create("runfiles/workspace/symlink/file1"), file1,
                PathFragment.create("runfiles/workspace/symlink/file2"), file2
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTreeArtifactsInInputs() {
        val treeArtifact: SpecialArtifact = createTreeArtifact("treeArtifact")
        val file1: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "file1")
        val file2: TreeFileArtifact = TreeFileArtifact.createTreeOutput(treeArtifact, "file2")
        FileSystemUtils.writeContentAsLatin1(file1.getPath(), "foo")
        FileSystemUtils.writeContentAsLatin1(file2.getPath(), "bar")

        val treeArtifactValue: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(treeArtifact)
                .putChild(file1, FileArtifactValue.createForTesting(file1.getPath()))
                .putChild(file2, FileArtifactValue.createForTesting(file2.getPath()))
                .build()

        val fakeActionInputFileCache: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        fakeActionInputFileCache.putTreeArtifact(treeArtifact, treeArtifactValue)

        val spawn: Spawn = SpawnBuilder("/bin/echo", "Hello World").withInput(treeArtifact).build()
        val inputMappings: MutableMap<PathFragment?, ActionInput?>? =
            expander.getInputMapping(spawn, fakeActionInputFileCache, PathFragment.EMPTY_FRAGMENT)

        Truth.assertThat(inputMappings).hasSize(2)
        Truth.assertThat(inputMappings).containsEntry(PathFragment.create("out/treeArtifact/file1"), file1)
        Truth.assertThat(inputMappings).containsEntry(PathFragment.create("out/treeArtifact/file2"), file2)
    }

    @Throws(IOException::class)
    private fun createTreeArtifact(relPath: String?): SpecialArtifact {
        val treeArtifact: SpecialArtifact = createSpecialArtifact(relPath, SpecialArtifactType.TREE)
        treeArtifact.setGeneratingActionKey(ActionsTestUtil.NULL_ACTION_LOOKUP_DATA)
        return treeArtifact
    }

    @Throws(IOException::class)
    private fun createFilesetArtifact(relPath: String?): SpecialArtifact {
        return createSpecialArtifact(relPath, SpecialArtifactType.FILESET)
    }

    @Throws(IOException::class)
    private fun createSpecialArtifact(relPath: String?, type: SpecialArtifactType?): SpecialArtifact {
        val outputSegment = "out"
        val outputDir: Path = execRoot.getRelative(outputSegment)
        val outputPath: Path = outputDir.getRelative(relPath)
        outputPath.createDirectoryAndParents()
        val derivedRoot: ArtifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, outputSegment)
        return SpecialArtifact.create(
            derivedRoot,
            derivedRoot.getExecPath().getRelative(derivedRoot.getRoot().relativize(outputPath)),
            ActionsTestUtil.NULL_ARTIFACT_OWNER,
            type
        )
    }

    @org.junit.Test
    fun testEmptyManifest() {
        val filesetMappings: com.google.common.collect.ImmutableMap<Artifact?, FilesetOutputTree?> =
            com.google.common.collect.ImmutableMap.of<Artifact?, FilesetOutputTree?>(
                createFileset("out"),
                FilesetOutputTree.EMPTY
            )

        SpawnInputExpander.addFilesetManifests(filesetMappings, inputMap, PathFragment.EMPTY_FRAGMENT)

        Truth.assertThat(inputMap).isEmpty()
    }

    @org.junit.Test
    fun fileset() {
        val link1: FilesetOutputSymlink = filesetSymlink("foo/bar", "dir/file1")
        val link2: FilesetOutputSymlink = filesetSymlink("foo/baz", "dir/file2")
        val fileset: Artifact = createFileset("out")
        val filesetMappings: com.google.common.collect.ImmutableMap<Artifact?, FilesetOutputTree?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                fileset,
                FilesetOutputTree.create(
                    com.google.common.collect.ImmutableList.of<E?>(link1, link2),  /* treeArtifacts= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>()
                )
            )

        SpawnInputExpander.addFilesetManifests(filesetMappings, inputMap, PathFragment.EMPTY_FRAGMENT)

        Truth.assertThat(inputMap)
            .containsExactly(
                PathFragment.create("out/foo/bar"), link1.target(),
                PathFragment.create("out/foo/baz"), link2.target()
            )
    }

    private fun filesetSymlink(from: String?, to: String?): FilesetOutputSymlink {
        return FilesetOutputSymlink(
            PathFragment.create(from),
            ActionsTestUtil.createArtifact(rootDir, to),
            FileArtifactValue.createForNormalFile(byteArrayOf(1), null, 1)
        )
    }

    private fun createFileset(execPath: String?): SpecialArtifact {
        return SpecialArtifact.create(
            rootDir,
            PathFragment.create(execPath),
            ActionsTestUtil.NULL_ARTIFACT_OWNER,
            SpecialArtifactType.FILESET
        )
    }
}
