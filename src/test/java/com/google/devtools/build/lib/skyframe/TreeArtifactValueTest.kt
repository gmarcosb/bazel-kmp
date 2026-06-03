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

import com.google.devtools.build.lib.actions.Artifact.ArchivedTreeArtifact

/** Tests for [TreeArtifactValue].  */
@RunWith(TestParameterInjector::class)
class TreeArtifactValueTest {
    private val scratch: Scratch = Scratch()
    private val root: ArtifactRoot? = ArtifactRoot.asDerivedRoot(
        scratch.resolve("root"), RootType.OUTPUT, PathFragment.create("bin")
    )

    internal class VisitTreeArgs(parentRelativePath: PathFragment?, type: Dirent.Type?, val traversedSymlink: Boolean) {
        val parentRelativePath: PathFragment?
        val type: Dirent.Type?

        init {
            this.type = type
            this.parentRelativePath = parentRelativePath
            java.util.Objects.requireNonNull<Any?>(parentRelativePath, "parentRelativePath")
            java.util.Objects.requireNonNull<Any?>(type, "type")
        }

        companion object {
            fun of(
                parentRelativePath: PathFragment?, type: Dirent.Type?, traversedSymlink: Boolean
            ): VisitTreeArgs {
                return VisitTreeArgs(parentRelativePath, type, traversedSymlink)
            }
        }
    }

    @org.junit.Test
    fun createsCorrectValue() {
        val parent: SpecialArtifact = createTreeArtifact("bin/tree")
        val child1: TreeFileArtifact = TreeFileArtifact.createTreeOutput(parent, "child1")
        val child2: TreeFileArtifact = TreeFileArtifact.createTreeOutput(parent, "child2")
        val metadata1: FileArtifactValue = metadataWithId(1)
        val metadata2: FileArtifactValue = metadataWithId(2)

        val tree: TreeArtifactValue =
            TreeArtifactValue.newBuilder(parent)
                .putChild(child1, metadata1)
                .putChild(child2, metadata2)
                .build()

        assertThat(tree.getChildren()).containsExactly(child1, child2)
        assertThat(tree.getChildValues()).containsExactly(child1, metadata1, child2, metadata2)
        assertThat(tree.getChildPaths())
            .containsExactly(child1.getParentRelativePath(), child2.getParentRelativePath())
        assertThat(tree.getDigest()).isNotNull()
        assertThat(tree.getMetadata().getDigest()).isEqualTo(tree.getDigest())
    }

    @org.junit.Test
    fun createsCorrectValueWithArchivedRepresentation() {
        val parent: SpecialArtifact = createTreeArtifact("bin/tree")
        val child1: TreeFileArtifact = TreeFileArtifact.createTreeOutput(parent, "child1")
        val child2: TreeFileArtifact = TreeFileArtifact.createTreeOutput(parent, "child2")
        val archivedTreeArtifact: ArchivedTreeArtifact = createArchivedTreeArtifact(parent)
        val child1Metadata: FileArtifactValue = metadataWithId(1)
        val child2Metadata: FileArtifactValue = metadataWithId(2)
        val archivedArtifactMetadata: FileArtifactValue = metadataWithId(3)

        val tree: TreeArtifactValue =
            TreeArtifactValue.newBuilder(parent)
                .putChild(child1, child1Metadata)
                .putChild(child2, child2Metadata)
                .setArchivedRepresentation(archivedTreeArtifact, archivedArtifactMetadata)
                .build()

        assertThat(tree.getChildren()).containsExactly(child1, child2)
        assertThat(tree.getChildValues())
            .containsExactly(child1, child1Metadata, child2, child2Metadata)
        assertThat(tree.getChildPaths())
            .containsExactly(child1.getParentRelativePath(), child2.getParentRelativePath())
        assertThat(tree.getDigest()).isNotNull()
        assertThat(tree.getMetadata().getDigest()).isEqualTo(tree.getDigest())
        assertThat(tree.getArchivedRepresentation())
            .hasValue(ArchivedRepresentation.create(archivedTreeArtifact, archivedArtifactMetadata))
    }

    @org.junit.Test
    fun createsCorrectValueWithResolvedPath() {
        val targetPath: PathFragment? = PathFragment.create("/some/target/path")
        val parent: SpecialArtifact = createTreeArtifact("bin/tree")

        val tree: TreeArtifactValue =
            TreeArtifactValue.newBuilder(parent).setResolvedPath(targetPath).build()

        assertThat(tree.getResolvedPath()).hasValue(targetPath)
        assertThat(tree.getMetadata().getResolvedPath()).isEqualTo(targetPath)
    }

    @org.junit.Test
    fun empty() {
        val emptyTree: TreeArtifactValue = TreeArtifactValue.empty()

        assertThat(emptyTree.getChildren()).isEmpty()
        assertThat(emptyTree.getChildValues()).isEmpty()
        assertThat(emptyTree.getChildPaths()).isEmpty()
        assertThat(emptyTree.getDigest()).isNotNull()
        assertThat(emptyTree.getMetadata().getDigest()).isEqualTo(emptyTree.getDigest())
    }

    @org.junit.Test
    fun createsCanonicalEmptyInstance() {
        val parent: SpecialArtifact = createTreeArtifact("bin/tree")

        val emptyTreeFromBuilder: TreeArtifactValue? = TreeArtifactValue.newBuilder(parent).build()

        assertThat(emptyTreeFromBuilder).isSameInstanceAs(TreeArtifactValue.empty())
    }

    @org.junit.Test
    fun createsCorrectEmptyValueWithArchivedRepresentation() {
        val parent: SpecialArtifact = createTreeArtifact("bin/tree")
        val archivedTreeArtifact: ArchivedTreeArtifact = createArchivedTreeArtifact(parent)
        val archivedArtifactMetadata: FileArtifactValue = metadataWithId(1)

        val tree: TreeArtifactValue =
            TreeArtifactValue.newBuilder(parent)
                .setArchivedRepresentation(archivedTreeArtifact, archivedArtifactMetadata)
                .build()

        assertThat(tree.getChildren()).isEmpty()
        assertThat(tree.getChildValues()).isEmpty()
        assertThat(tree.getChildPaths()).isEmpty()
        assertThat(tree.getDigest()).isNotNull()
        assertThat(tree.getMetadata().getDigest()).isEqualTo(tree.getDigest())
        assertThat(tree.getArchivedRepresentation())
            .hasValue(ArchivedRepresentation.create(archivedTreeArtifact, archivedArtifactMetadata))
    }

    @org.junit.Test
    fun cannotCreateBuilderForNonTreeArtifact() {
        val notTreeArtifact: SpecialArtifact? =
            SpecialArtifact.create(
                root,
                PathFragment.create("bin/not_tree"),
                ActionsTestUtil.NULL_ARTIFACT_OWNER,
                SpecialArtifactType.FILESET
            )

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { TreeArtifactValue.newBuilder(notTreeArtifact) })
    }

    @org.junit.Test
    fun cannotMixParentsWithinSingleBuilder() {
        val parent: SpecialArtifact = createTreeArtifact("bin/tree")
        val childOfAnotherParent: TreeFileArtifact? =
            TreeFileArtifact.createTreeOutput(createTreeArtifact("bin/other_tree"), "child")

        val builderForParent: TreeArtifactValue.Builder = TreeArtifactValue.newBuilder(parent)

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { builderForParent.putChild(childOfAnotherParent, metadataWithId(1)) })
    }

    @org.junit.Test
    fun cannotAddArchivedRepresentationWithWrongParent() {
        val parent: SpecialArtifact = createTreeArtifact("bin/tree")
        val archivedDifferentTreeArtifact: ArchivedTreeArtifact =
            createArchivedTreeArtifact(createTreeArtifact("bin/other_tree"))
        val builderForParent: TreeArtifactValue.Builder = TreeArtifactValue.newBuilder(parent)
        val metadata: FileArtifactValue = metadataWithId(1)

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                builderForParent.setArchivedRepresentation(
                    archivedDifferentTreeArtifact,
                    metadata
                )
            })
    }

    @org.junit.Test
    fun orderIndependence() {
        val parent: SpecialArtifact = createTreeArtifact("bin/tree")
        val child1: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(parent, "child1")
        val child2: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(parent, "child2")
        val metadata1: FileArtifactValue = metadataWithId(1)
        val metadata2: FileArtifactValue = metadataWithId(2)

        val tree1: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(parent)
                .putChild(child1, metadata1)
                .putChild(child2, metadata2)
                .build()
        val tree2: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(parent)
                .putChild(child2, metadata2)
                .putChild(child1, metadata1)
                .build()

        assertThat(tree1).isEqualTo(tree2)
    }

    @org.junit.Test
    fun nullDigests_equal() {
        val parent: SpecialArtifact = createTreeArtifact("bin/tree")
        val child: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(parent, "child")
        val metadataNoDigest: FileArtifactValue = metadataWithIdNoDigest(1)

        val tree1: TreeArtifactValue =
            TreeArtifactValue.newBuilder(parent).putChild(child, metadataNoDigest).build()
        val tree2: TreeArtifactValue =
            TreeArtifactValue.newBuilder(parent).putChild(child, metadataNoDigest).build()

        assertThat(metadataNoDigest.getDigest()).isNull()
        assertThat(tree1.getDigest()).isNotNull()
        assertThat(tree2.getDigest()).isNotNull()
        assertThat(tree1).isEqualTo(tree2)
    }

    @org.junit.Test
    fun nullDigestsForArchivedRepresentation_equal() {
        val parent: SpecialArtifact = createTreeArtifact("bin/tree")
        val archivedTreeArtifact: ArchivedTreeArtifact = createArchivedTreeArtifact(parent)
        val metadataNoDigest: FileArtifactValue = metadataWithIdNoDigest(1)

        val tree1: TreeArtifactValue =
            TreeArtifactValue.newBuilder(parent)
                .setArchivedRepresentation(archivedTreeArtifact, metadataNoDigest)
                .build()
        val tree2: TreeArtifactValue =
            TreeArtifactValue.newBuilder(parent)
                .setArchivedRepresentation(archivedTreeArtifact, metadataNoDigest)
                .build()

        assertThat(metadataNoDigest.getDigest()).isNull()
        assertThat(tree1.getDigest()).isNotNull()
        assertThat(tree2.getDigest()).isNotNull()
        assertThat(tree1).isEqualTo(tree2)
    }

    @org.junit.Test
    fun nullDigests_notEqual() {
        val parent: SpecialArtifact = createTreeArtifact("bin/tree")
        val child: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(parent, "child")
        val metadataNoDigest1: FileArtifactValue = metadataWithIdNoDigest(1)
        val metadataNoDigest2: FileArtifactValue = metadataWithIdNoDigest(2)

        val tree1: TreeArtifactValue =
            TreeArtifactValue.newBuilder(parent).putChild(child, metadataNoDigest1).build()
        val tree2: TreeArtifactValue =
            TreeArtifactValue.newBuilder(parent).putChild(child, metadataNoDigest2).build()

        assertThat(metadataNoDigest1.getDigest()).isNull()
        assertThat(metadataNoDigest2.getDigest()).isNull()
        assertThat(tree1.getDigest()).isNotNull()
        assertThat(tree2.getDigest()).isNotNull()
        assertThat(tree1.getDigest()).isNotEqualTo(tree2.getDigest())
    }

    @org.junit.Test
    fun nullDigestsForArchivedRepresentation_notEqual() {
        val parent: SpecialArtifact = createTreeArtifact("bin/tree")
        val archivedTreeArtifact: ArchivedTreeArtifact = createArchivedTreeArtifact(parent)
        val metadataNoDigest1: FileArtifactValue = metadataWithIdNoDigest(1)
        val metadataNoDigest2: FileArtifactValue = metadataWithIdNoDigest(2)

        val tree1: TreeArtifactValue =
            TreeArtifactValue.newBuilder(parent)
                .setArchivedRepresentation(archivedTreeArtifact, metadataNoDigest1)
                .build()
        val tree2: TreeArtifactValue =
            TreeArtifactValue.newBuilder(parent)
                .setArchivedRepresentation(archivedTreeArtifact, metadataNoDigest2)
                .build()

        assertThat(metadataNoDigest1.getDigest()).isNull()
        assertThat(metadataNoDigest2.getDigest()).isNull()
        assertThat(tree1.getDigest()).isNotNull()
        assertThat(tree2.getDigest()).isNotNull()
        assertThat(tree1.getDigest()).isNotEqualTo(tree2.getDigest())
    }

    @org.junit.Test
    fun findChildEntryByExecPath_returnsCorrectEntry() {
        val tree: SpecialArtifact = createTreeArtifact("bin/tree")
        val file1: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(tree, "file1")
        val file1Metadata: FileArtifactValue = metadataWithIdNoDigest(1)
        val treeArtifactValue: TreeArtifactValue =
            TreeArtifactValue.newBuilder(tree)
                .putChild(file1, file1Metadata)
                .putChild(TreeFileArtifact.createTreeOutput(tree, "file2"), metadataWithIdNoDigest(2))
                .build()

        assertThat(treeArtifactValue.findChildEntryByExecPath(PathFragment.create("bin/tree/file1")))
            .isEqualTo(com.google.common.collect.Maps.immutableEntry<Any?, Any?>(file1, file1Metadata))
    }

    @org.junit.Test
    fun findChildEntryByExecPath_nonExistentChild_returnsNull(
        @TestParameter("bin/nonexistent", "a_before_nonexistent", "z_after_nonexistent") nonexistentPath: String?
    ) {
        val tree: SpecialArtifact = createTreeArtifact("bin/tree")
        val treeArtifactValue: TreeArtifactValue =
            TreeArtifactValue.newBuilder(tree)
                .putChild(TreeFileArtifact.createTreeOutput(tree, "file"), metadataWithIdNoDigest(1))
                .build()

        assertThat(treeArtifactValue.findChildEntryByExecPath(PathFragment.create(nonexistentPath)))
            .isNull()
    }

    @org.junit.Test
    fun findChildEntryByExecPath_emptyTreeArtifactValue_returnsNull() {
        val treeArtifactValue: TreeArtifactValue = TreeArtifactValue.empty()
        assertThat(treeArtifactValue.findChildEntryByExecPath(PathFragment.create("file"))).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun visitTree_visitsEachChild() {
        val treeDir: Path = scratch.dir("tree")
        scratch.file("tree/file1")
        scratch.file("tree/a/file2")
        scratch.file("tree/a/b/file3")
        scratch.resolve("tree/file_link").createSymbolicLink(PathFragment.create("file1"))
        scratch.resolve("tree/a/dir_link").createSymbolicLink(PathFragment.create("b"))
        val children: MutableList<VisitTreeArgs?> = java.util.ArrayList<VisitTreeArgs?>()

        TreeArtifactValue.visitTree(
            treeDir,
            { child, type, traversedSymlink ->
                synchronized(children) {
                    children.add(VisitTreeArgs.Companion.of(child, type, traversedSymlink))
                }
            })

        Truth.assertThat(children)
            .containsExactly(
                VisitTreeArgs.Companion.of(PathFragment.create(""), Dirent.Type.DIRECTORY, false),
                VisitTreeArgs.Companion.of(PathFragment.create("a"), Dirent.Type.DIRECTORY, false),
                VisitTreeArgs.Companion.of(PathFragment.create("a/b"), Dirent.Type.DIRECTORY, false),
                VisitTreeArgs.Companion.of(PathFragment.create("file1"), Dirent.Type.FILE, false),
                VisitTreeArgs.Companion.of(PathFragment.create("a/file2"), Dirent.Type.FILE, false),
                VisitTreeArgs.Companion.of(PathFragment.create("a/b/file3"), Dirent.Type.FILE, false),
                VisitTreeArgs.Companion.of(PathFragment.create("file_link"), Dirent.Type.FILE, true),
                VisitTreeArgs.Companion.of(PathFragment.create("a/dir_link"), Dirent.Type.DIRECTORY, true),
                VisitTreeArgs.Companion.of(PathFragment.create("a/dir_link/file3"), Dirent.Type.FILE, true)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun visitTree_throwsOnDanglingSymlink() {
        val treeDir: Path = scratch.dir("tree")
        scratch.resolve("tree/symlink").createSymbolicLink(PathFragment.create("/does_not_exist"))

        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    TreeArtifactValue.visitTree(
                        treeDir,
                        { child, type, traversedSymlink -> })
                })
        Truth.assertThat(e).hasMessageThat().contains("child symlink is a dangling symbolic link")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun visitTree_throwsOnSymlinkLoop() {
        val treeDir: Path = scratch.dir("tree")
        scratch.resolve("tree/symlink").createSymbolicLink(scratch.resolve(treeDir.asFragment()))

        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    TreeArtifactValue.visitTree(
                        treeDir,
                        { child, type, traversedSymlink -> })
                })
        Truth.assertThat(e).hasMessageThat().contains("tree/symlink")
        Truth.assertThat(e).hasMessageThat().contains("Too many levels of symbolic links")
    }

    @org.junit.Test
    fun visitTree_throwsOnUnknownDirentType() {
        val fs: FileSystem =
            object : InMemoryFileSystem(DigestHashFunction.SHA256) {
                @Throws(IOException::class)
                public override fun readdir(path: PathFragment, followSymlinks: Boolean): MutableCollection<Dirent?>? {
                    if (path.equals(PathFragment.create("/tree"))) {
                        return com.google.common.collect.ImmutableList.of<Dirent?>(
                            Dirent(
                                "unknown",
                                Dirent.Type.UNKNOWN
                            )
                        )
                    }
                    return super.readdir(path, followSymlinks)
                }
            }
        val treeDir: Path? = fs.getPath("/tree")

        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    TreeArtifactValue.visitTree(
                        treeDir,
                        { child, type, traversedSymlink -> })
                })
        Truth.assertThat(e).hasMessageThat().contains("child unknown has an unsupported type")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun visitTree_throwsOnSymlinkToSpecialFile() {
        val fs: FileSystem =
            object : InMemoryFileSystem(DigestHashFunction.SHA256) {
                @Throws(IOException::class)
                public override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus? {
                    if (path.equals(PathFragment.create("/tree/sym"))) {
                        return object : FileStatus() {
                            val isFile: Boolean
                                get() = true

                            val isDirectory: Boolean
                                get() = false

                            val isSymbolicLink: Boolean
                                get() = false

                            val isSpecialFile: Boolean
                                get() = true

                            val lastChangeTime: Long
                                get() = 0

                            val lastModifiedTime: Long
                                get() = 0

                            val nodeId: Long
                                get() = 0

                            val size: Long
                                get() = 0
                        }
                    }
                    return super.statIfFound(path, followSymlinks)
                }
            }
        val treeDir: Path = fs.getPath("/tree")
        treeDir.createDirectory()
        treeDir.getChild("sym").createSymbolicLink(PathFragment.create("/special"))

        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    TreeArtifactValue.visitTree(
                        treeDir,
                        { child, type, traversedSymlink -> })
                })
        Truth.assertThat(e).hasMessageThat().contains("child sym has an unsupported type")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun visitTree_propagatesIoExceptionFromVisitor() {
        val treeDir: Path = scratch.dir("tree")
        val e: IOException = IOException("From visitor")

        val thrown: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    TreeArtifactValue.visitTree(
                        treeDir,
                        { child, type, traversedSymlink ->
                            throw e
                        })
                })
        Truth.assertThat(thrown).isSameInstanceAs(e)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun visitTree_permitsUpLevelSymlinkInsideTree() {
        val treeDir: Path = scratch.dir("tree")
        scratch.file("tree/file")
        scratch.dir("tree/a")
        scratch.resolve("tree/a/up_link").createSymbolicLink(PathFragment.create("../file"))
        val children: MutableList<VisitTreeArgs?> = java.util.ArrayList<VisitTreeArgs?>()

        TreeArtifactValue.visitTree(
            treeDir,
            { child, type, traversedSymlink ->
                synchronized(children) {
                    children.add(VisitTreeArgs.Companion.of(child, type, traversedSymlink))
                }
            })

        Truth.assertThat(children)
            .containsExactly(
                VisitTreeArgs.Companion.of(PathFragment.create(""), Dirent.Type.DIRECTORY, false),
                VisitTreeArgs.Companion.of(PathFragment.create("file"), Dirent.Type.FILE, false),
                VisitTreeArgs.Companion.of(PathFragment.create("a"), Dirent.Type.DIRECTORY, false),
                VisitTreeArgs.Companion.of(PathFragment.create("a/up_link"), Dirent.Type.FILE, true)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun visitTree_permitsUpLevelSymlinkOutsideTree() {
        val treeDir: Path = scratch.dir("tree")
        scratch.file("tree/file")
        scratch.dir("tree/a")
        scratch.file("other_tree/file")
        scratch
            .resolve("tree/a/uplink")
            .createSymbolicLink(PathFragment.create("../../other_tree/file"))
        val children: MutableList<VisitTreeArgs?> = java.util.ArrayList<VisitTreeArgs?>()

        TreeArtifactValue.visitTree(
            treeDir,
            { child, type, traversedSymlink ->
                synchronized(children) {
                    children.add(VisitTreeArgs.Companion.of(child, type, traversedSymlink))
                }
            })

        Truth.assertThat(children)
            .containsExactly(
                VisitTreeArgs.Companion.of(PathFragment.create(""), Dirent.Type.DIRECTORY, false),
                VisitTreeArgs.Companion.of(PathFragment.create("file"), Dirent.Type.FILE, false),
                VisitTreeArgs.Companion.of(PathFragment.create("a"), Dirent.Type.DIRECTORY, false),
                VisitTreeArgs.Companion.of(PathFragment.create("a/uplink"), Dirent.Type.FILE, true)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun visitTree_permitsAbsoluteSymlink() {
        val treeDir: Path = scratch.dir("tree")
        val targetFile: Path = scratch.file("target_file")
        val targetDir: Path = scratch.dir("target_dir")
        scratch.resolve("tree/absolute_file_link").createSymbolicLink(targetFile.asFragment())
        scratch.resolve("tree/absolute_dir_link").createSymbolicLink(targetDir.asFragment())
        val children: MutableList<VisitTreeArgs?> = java.util.ArrayList<VisitTreeArgs?>()

        TreeArtifactValue.visitTree(
            treeDir,
            { child, type, traversedSymlink ->
                synchronized(children) {
                    children.add(VisitTreeArgs.Companion.of(child, type, traversedSymlink))
                }
            })

        Truth.assertThat(children)
            .containsExactly(
                VisitTreeArgs.Companion.of(PathFragment.create(""), Dirent.Type.DIRECTORY, false),
                VisitTreeArgs.Companion.of(PathFragment.create("absolute_file_link"), Dirent.Type.FILE, true),
                VisitTreeArgs.Companion.of(
                    PathFragment.create("absolute_dir_link"), Dirent.Type.DIRECTORY, true
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun visitTree_permitsUplevelSymlinkTraversingOutsideThenBackInsideTree() {
        val treeDir: Path = scratch.dir("tree")
        scratch.file("tree/file")
        scratch.resolve("tree/link").createSymbolicLink(PathFragment.create("../tree/file"))

        val children: MutableList<VisitTreeArgs?> = java.util.ArrayList<VisitTreeArgs?>()

        TreeArtifactValue.visitTree(
            treeDir,
            { child, type, traversedSymlink ->
                synchronized(children) {
                    children.add(VisitTreeArgs.Companion.of(child, type, traversedSymlink))
                }
            })

        Truth.assertThat(children)
            .containsExactly(
                VisitTreeArgs.Companion.of(PathFragment.create(""), Dirent.Type.DIRECTORY, false),
                VisitTreeArgs.Companion.of(PathFragment.create("file"), Dirent.Type.FILE, false),
                VisitTreeArgs.Companion.of(PathFragment.create("link"), Dirent.Type.FILE, true)
            )
    }

    @org.junit.Test
    fun multiBuilder_empty_injectsNothing() {
        val results: MutableMap<SpecialArtifact?, TreeArtifactValue?> = HashMap<SpecialArtifact?, TreeArtifactValue?>()

        TreeArtifactValue.newMultiBuilder().forEach({ key: K?, value: V? -> results.put(key, value) })

        Truth.assertThat(results).isEmpty()
    }

    @org.junit.Test
    fun multiBuilder_injectsEmptyTreeArtifact() {
        val treeArtifacts: TreeArtifactValue.MultiBuilder = TreeArtifactValue.newMultiBuilder()
        val parent: SpecialArtifact = createTreeArtifact("bin/tree")
        val results: MutableMap<SpecialArtifact?, TreeArtifactValue?> = HashMap<SpecialArtifact?, TreeArtifactValue?>()

        treeArtifacts.addTree(parent).forEach({ key: K?, value: V? -> results.put(key, value) })

        Truth.assertThat(results).containsExactly(parent, TreeArtifactValue.empty())
    }

    @org.junit.Test
    fun multiBuilder_injectsSingleTreeArtifact() {
        val treeArtifacts: TreeArtifactValue.MultiBuilder = TreeArtifactValue.newMultiBuilder()
        val parent: SpecialArtifact = createTreeArtifact("bin/tree")
        val child1: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(parent, "child1")
        val child2: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(parent, "child2")
        val results: MutableMap<SpecialArtifact?, TreeArtifactValue?> = HashMap<SpecialArtifact?, TreeArtifactValue?>()

        treeArtifacts
            .putChild(child1, metadataWithId(1))
            .putChild(child2, metadataWithId(2))
            .forEach({ key: K?, value: V? -> results.put(key, value) })

        Truth.assertThat(results)
            .containsExactly(
                parent,
                TreeArtifactValue.newBuilder(parent)
                    .putChild(child1, metadataWithId(1))
                    .putChild(child2, metadataWithId(2))
                    .build()
            )
    }

    @org.junit.Test
    fun multiBuilder_injectsMultipleTreeArtifacts() {
        val treeArtifacts: TreeArtifactValue.MultiBuilder = TreeArtifactValue.newMultiBuilder()
        val parent1: SpecialArtifact = createTreeArtifact("bin/tree1")
        val parent1Child1: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(parent1, "child1")
        val parent1Child2: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(parent1, "child2")
        val parent2: SpecialArtifact = createTreeArtifact("bin/tree2")
        val parent2Child: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(parent2, "child")
        val results: MutableMap<SpecialArtifact?, TreeArtifactValue?> = HashMap<SpecialArtifact?, TreeArtifactValue?>()

        treeArtifacts
            .putChild(parent1Child1, metadataWithId(1))
            .putChild(parent2Child, metadataWithId(3))
            .putChild(parent1Child2, metadataWithId(2))
            .forEach({ key: K?, value: V? -> results.put(key, value) })

        Truth.assertThat(results)
            .containsExactly(
                parent1,
                TreeArtifactValue.newBuilder(parent1)
                    .putChild(parent1Child1, metadataWithId(1))
                    .putChild(parent1Child2, metadataWithId(2))
                    .build(),
                parent2,
                TreeArtifactValue.newBuilder(parent2)
                    .putChild(parent2Child, metadataWithId(3))
                    .build()
            )
    }

    @org.junit.Test
    fun multiBuilder_injectsTreeArtifactWithArchivedRepresentation() {
        val builder: TreeArtifactValue.MultiBuilder = TreeArtifactValue.newMultiBuilder()
        val parent: SpecialArtifact = createTreeArtifact("bin/tree")
        val child: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(parent, "child")
        val childMetadata: FileArtifactValue = metadataWithId(1)
        val archivedTreeArtifact: ArchivedTreeArtifact = createArchivedTreeArtifact(parent)
        val archivedTreeArtifactMetadata: FileArtifactValue = metadataWithId(2)
        val results: MutableMap<SpecialArtifact?, TreeArtifactValue?> = HashMap<SpecialArtifact?, TreeArtifactValue?>()

        builder
            .putChild(child, childMetadata)
            .setArchivedRepresentation(archivedTreeArtifact, archivedTreeArtifactMetadata)
            .forEach({ key: K?, value: V? -> results.put(key, value) })

        Truth.assertThat(results)
            .containsExactly(
                parent,
                TreeArtifactValue.newBuilder(parent)
                    .putChild(child, childMetadata)
                    .setArchivedRepresentation(archivedTreeArtifact, archivedTreeArtifactMetadata)
                    .build()
            )
    }

    @org.junit.Test
    fun multiBuilder_injectsEmptyTreeArtifactWithArchivedRepresentation() {
        val builder: TreeArtifactValue.MultiBuilder = TreeArtifactValue.newMultiBuilder()
        val parent: SpecialArtifact = createTreeArtifact("bin/tree")
        val archivedTreeArtifact: ArchivedTreeArtifact = createArchivedTreeArtifact(parent)
        val metadata: FileArtifactValue = metadataWithId(1)
        val results: MutableMap<SpecialArtifact?, TreeArtifactValue?> = HashMap<SpecialArtifact?, TreeArtifactValue?>()

        builder.setArchivedRepresentation(archivedTreeArtifact, metadata)
            .forEach({ key: K?, value: V? -> results.put(key, value) })

        Truth.assertThat(results)
            .containsExactly(
                parent,
                TreeArtifactValue.newBuilder(parent)
                    .setArchivedRepresentation(archivedTreeArtifact, metadata)
                    .build()
            )
    }

    @org.junit.Test
    fun multiBuilder_injectsTreeArtifactsWithAndWithoutArchivedRepresentation() {
        val builder: TreeArtifactValue.MultiBuilder = TreeArtifactValue.newMultiBuilder()
        val parent1: SpecialArtifact = createTreeArtifact("bin/tree1")
        val archivedArtifact1: ArchivedTreeArtifact = createArchivedTreeArtifact(parent1)
        val archivedArtifact1Metadata: FileArtifactValue = metadataWithId(1)
        val parent1Child: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(parent1, "child")
        val parent1ChildMetadata: FileArtifactValue = metadataWithId(2)
        val parent2: SpecialArtifact = createTreeArtifact("bin/tree2")
        val parent2Child: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(parent2, "child")
        val parent2ChildMetadata: FileArtifactValue = metadataWithId(3)
        val results: MutableMap<SpecialArtifact?, TreeArtifactValue?> = HashMap<SpecialArtifact?, TreeArtifactValue?>()

        builder
            .setArchivedRepresentation(archivedArtifact1, archivedArtifact1Metadata)
            .putChild(parent1Child, parent1ChildMetadata)
            .putChild(parent2Child, parent2ChildMetadata)
            .forEach({ key: K?, value: V? -> results.put(key, value) })

        Truth.assertThat(results)
            .containsExactly(
                parent1,
                TreeArtifactValue.newBuilder(parent1)
                    .putChild(parent1Child, parent1ChildMetadata)
                    .setArchivedRepresentation(archivedArtifact1, metadataWithId(1))
                    .build(),
                parent2,
                TreeArtifactValue.newBuilder(parent2)
                    .putChild(parent2Child, parent2ChildMetadata)
                    .build()
            )
    }

    private fun createTreeArtifact(execPath: String?): SpecialArtifact {
        return createTreeArtifact(execPath, root)
    }

    companion object {
        private fun createArchivedTreeArtifact(specialArtifact: SpecialArtifact?): ArchivedTreeArtifact {
            return ArchivedTreeArtifact.createForTree(specialArtifact)
        }

        private fun createTreeArtifact(execPath: String?, root: ArtifactRoot?): SpecialArtifact {
            return ActionsTestUtil.createTreeArtifactWithGeneratingAction(
                root, PathFragment.create(execPath)
            )
        }

        private fun metadataWithId(id: Int): FileArtifactValue {
            return FileArtifactValue.createForRemoteFile(byteArrayOf(id.toByte()), id, id)
        }

        private fun metadataWithIdNoDigest(id: Int): FileArtifactValue {
            val value: FileArtifactValue = Mockito.spy<FileArtifactValue>(FileArtifactValue::class.java)
            Mockito.doReturn(null).`when`<Any?>(value).getDigest()
            Mockito.doReturn(id.toLong()).`when`<Any?>(value).getModifiedTime()
            return value
        }
    }
}
