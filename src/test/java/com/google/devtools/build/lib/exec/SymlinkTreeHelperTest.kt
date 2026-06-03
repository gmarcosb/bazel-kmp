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

import com.google.devtools.build.lib.actions.Artifact

/** Unit tests for [SymlinkTreeHelper].  */
@RunWith(TestParameterInjector::class)
class SymlinkTreeHelperTest {
    private enum class TreeType {
        RUNFILES,
        FILESET
    }

    private val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    private val execRoot: Path = fs.getPath("/execroot")
    private val outputRoot: ArtifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out")

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        outputRoot.getRoot().asPath().createDirectoryAndParents()
    }

    @org.junit.Test
    fun processFilesetLinks() {
        val target1: Artifact = ActionsTestUtil.createArtifact(outputRoot, "target1")
        val target2: Artifact = ActionsTestUtil.createArtifact(outputRoot, "target2")
        val metadata: FileArtifactValue? =
            FileArtifactValue.createForNormalFile(byteArrayOf(1, 2, 3, 4), null, 10)
        val link1: FilesetOutputSymlink =
            FilesetOutputSymlink(PathFragment.create("from1"), target1, metadata)
        val link2: FilesetOutputSymlink =
            FilesetOutputSymlink(PathFragment.create("from2"), target2, metadata)

        val symlinks: MutableMap<PathFragment?, PathFragment?>? =
            SymlinkTreeHelper.processFilesetLinks(
                com.google.common.collect.ImmutableList.of<E?>(link1, link2),
                "workspace"
            )
        Truth.assertThat(symlinks)
            .containsExactly(
                PathFragment.create("workspace/from1"),
                target1.getPath().asFragment(),
                PathFragment.create("workspace/from2"),
                target2.getPath().asFragment()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createSymlinks(@TestParameter treeType: TreeType, @TestParameter replace: Boolean) {
        val treeRoot: Path = execRoot.getRelative("foo.runfiles")
        val inputManifestPath: Path? = execRoot.getRelative("foo.runfiles_manifest")
        val outputManifestPath: Path? = execRoot.getRelative("foo.runfiles/MANIFEST")
        val helper: SymlinkTreeHelper =
            SymlinkTreeHelper(inputManifestPath, outputManifestPath, treeRoot, TestConstants.WORKSPACE_NAME)

        val file: Artifact = ActionsTestUtil.createArtifact(outputRoot, "file")
        val symlink: Artifact = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputRoot, "symlink")

        FileSystemUtils.writeContent(file.getPath(), java.nio.charset.StandardCharsets.UTF_8, "content")
        FileSystemUtils.ensureSymbolicLink(symlink.getPath(), "/path/to/target")

        val treeWorkspace: Path = treeRoot.getRelative(TestConstants.WORKSPACE_NAME)
        val treeEmpty: Path = treeWorkspace.getRelative("empty")
        val treeFile: Path = treeWorkspace.getRelative("file")
        val treeSymlink: Path = treeWorkspace.getRelative("symlink")
        val treeMissing: Path = treeWorkspace.getRelative("missing")

        if (replace) {
            treeEmpty.createDirectoryAndParents()
            treeFile.createDirectoryAndParents()
            treeSymlink.createDirectoryAndParents()
            treeMissing.createDirectoryAndParents()
            treeWorkspace.chmod(0)
        }

        when (treeType) {
            TreeType.RUNFILES -> {
                val symlinkMap: HashMap<PathFragment?, Artifact?> = HashMap<PathFragment?, Artifact?>()
                symlinkMap.put(PathFragment.create(TestConstants.WORKSPACE_NAME + "/empty"), null)
                symlinkMap.put(PathFragment.create(TestConstants.WORKSPACE_NAME + "/file"), file)
                symlinkMap.put(PathFragment.create(TestConstants.WORKSPACE_NAME + "/symlink"), symlink)

                helper.createRunfilesSymlinks(symlinkMap)
            }

            TreeType.FILESET -> {
                val symlinkMap: HashMap<PathFragment?, PathFragment?> = HashMap<PathFragment?, PathFragment?>()
                symlinkMap.put(PathFragment.create(TestConstants.WORKSPACE_NAME + "/empty"), null)
                symlinkMap.put(PathFragment.create(TestConstants.WORKSPACE_NAME + "/file"), file.getPath().asFragment())
                symlinkMap.put(
                    PathFragment.create(TestConstants.WORKSPACE_NAME + "/symlink"),
                    PathFragment.create("/path/to/target")
                )

                helper.createFilesetSymlinks(symlinkMap)
            }
        }

        assertThat(treeRoot.isDirectory()).isTrue()
        assertThat(treeWorkspace.isDirectory()).isTrue()
        assertThat(treeEmpty.isFile()).isTrue()
        assertThat(FileSystemUtils.readContent(treeEmpty)).isEmpty()
        assertThat(treeFile.isSymbolicLink()).isTrue()
        assertThat(treeFile.readSymbolicLink()).isEqualTo(file.getPath().asFragment())
        assertThat(treeSymlink.isSymbolicLink()).isTrue()
        assertThat(treeSymlink.readSymbolicLink()).isEqualTo(PathFragment.create("/path/to/target"))
        assertThat(treeMissing.exists()).isFalse()
    }
}
