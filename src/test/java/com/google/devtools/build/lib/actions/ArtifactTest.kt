// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.Artifact.ArchivedTreeArtifact

/** Tests for [Artifact].  */
@RunWith(TestParameterInjector::class)
class ArtifactTest {
    private val scratch: Scratch = Scratch()
    private var execDir: Path? = null
    private var rootDir: ArtifactRoot? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setRootDir() {
        execDir = scratch.dir("/base/exec")
        rootDir = ArtifactRoot.asDerivedRoot(execDir, RootType.OUTPUT, "root")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testConstruction_badRootDir() {
        val f1: Path = scratch.file("/exec/dir/file.ext")
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                ActionsTestUtil.Companion.createArtifactWithExecPath(
                    ArtifactRoot.asDerivedRoot(execDir, RootType.OUTPUT, "bogus"),
                    f1.relativeTo(execDir)
                )
                    .getRootRelativePath()
            })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testMemoryUsage() {
        val root: ArtifactRoot = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/foo")))
        val aPath: PathFragment? = PathFragment.create("src/a")
        val arrSize = 1 shl 20
        val arr = arrayOfNulls<Any>(arrSize)
        val usedMemory = getUsedMemory()
        for (i in 0..<arrSize) {
            arr[i] = ActionsTestUtil.Companion.createArtifactWithExecPath(root, aPath)
        }
        Truth.assertThat((getUsedMemory() - usedMemory) / arrSize).isAtMost(34L)
    }

    @org.junit.Test
    fun testEquivalenceRelation() {
        val aPath: PathFragment? = PathFragment.create("src/a")
        val bPath: PathFragment? = PathFragment.create("src/b")
        assertThat(ActionsTestUtil.Companion.createArtifactWithRootRelativePath(rootDir, aPath))
            .isEqualTo(ActionsTestUtil.Companion.createArtifactWithRootRelativePath(rootDir, aPath))
        assertThat(ActionsTestUtil.Companion.createArtifactWithRootRelativePath(rootDir, bPath))
            .isEqualTo(ActionsTestUtil.Companion.createArtifactWithRootRelativePath(rootDir, bPath))
        assertThat(
            ActionsTestUtil.Companion.createArtifactWithRootRelativePath(rootDir, aPath)
                .equals(ActionsTestUtil.Companion.createArtifactWithRootRelativePath(rootDir, bPath))
        )
            .isFalse()
    }

    @org.junit.Test
    fun testComparison() {
        val aPath: PathFragment? = PathFragment.create("src/a")
        val bPath: PathFragment? = PathFragment.create("src/b")
        val aArtifact: Artifact? = ActionsTestUtil.Companion.createArtifactWithRootRelativePath(rootDir, aPath)
        val bArtifact: Artifact? = ActionsTestUtil.Companion.createArtifactWithRootRelativePath(rootDir, bPath)
        assertThat(Artifact.EXEC_PATH_COMPARATOR.compare(aArtifact, bArtifact)).isEqualTo(-1)
        assertThat(Artifact.EXEC_PATH_COMPARATOR.compare(aArtifact, aArtifact)).isEqualTo(0)
        assertThat(Artifact.EXEC_PATH_COMPARATOR.compare(bArtifact, bArtifact)).isEqualTo(0)
        assertThat(Artifact.EXEC_PATH_COMPARATOR.compare(bArtifact, aArtifact)).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetFilename() {
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/foo")))
        val javaFile: Artifact = ActionsTestUtil.Companion.createArtifact(root, scratch.file("/foo/Bar.java"))
        val generatedHeader: Artifact =
            ActionsTestUtil.Companion.createArtifact(root, scratch.file("/foo/bar.proto.h"))
        val generatedCc: Artifact = ActionsTestUtil.Companion.createArtifact(root, scratch.file("/foo/bar.proto.cc"))
        val aCPlusPlusFile: Artifact = ActionsTestUtil.Companion.createArtifact(root, scratch.file("/foo/bar.cc"))
        assertThat(JavaSemantics.JAVA_SOURCE.matches(javaFile.getFilename())).isTrue()
        assertThat(CppFileTypes.CPP_HEADER.matches(generatedHeader.getFilename())).isTrue()
        assertThat(CppFileTypes.CPP_SOURCE.matches(generatedCc.getFilename())).isTrue()
        assertThat(CppFileTypes.CPP_SOURCE.matches(aCPlusPlusFile.getFilename())).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetExtension() {
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/foo")))
        val javaFile: Artifact = ActionsTestUtil.Companion.createArtifact(root, scratch.file("/foo/Bar.java"))
        assertThat(javaFile.getExtension()).isEqualTo("java")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsFileType() {
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/foo")))
        val javaFile: Artifact = ActionsTestUtil.Companion.createArtifact(root, scratch.file("/foo/Bar.java"))
        assertThat(javaFile.isFileType(FileType.of("java"))).isTrue()
        assertThat(javaFile.isFileType(FileType.of("cc"))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsFileTypeSet() {
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/foo")))
        val javaFile: Artifact = ActionsTestUtil.Companion.createArtifact(root, scratch.file("/foo/Bar.java"))
        assertThat(javaFile.isFileType(FileTypeSet.of(FileType.of("cc"), FileType.of("java"))))
            .isTrue()
        assertThat(javaFile.isFileType(FileTypeSet.of(FileType.of("py"), FileType.of("js")))).isFalse()
        assertThat(javaFile.isFileType(FileTypeSet.of())).isFalse()
    }

    @org.junit.Test
    fun testMangledPath() {
        val path = "dir/sub_dir/name:end"
        assertThat(Actions.escapedPath(path)).isEqualTo("dir_Ssub_Udir_Sname_Cend")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRootRelativePathIsSameAsExecPath() {
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/foo")))
        val a: Artifact = ActionsTestUtil.Companion.createArtifact(root, scratch.file("/foo/bar1.h"))
        assertThat(a.getRootRelativePath()).isSameInstanceAs(a.getExecPath())
    }

    @org.junit.Test
    fun testToDetailString() {
        val execRoot: Path? = scratch.getFileSystem().getPath("/execroot/workspace")
        val a: Artifact =
            createArtifact(
                ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "b"), "c"
            )
        assertThat(a.toDetailString()).isEqualTo("[[<execution_root>]b]c")
    }

    @org.junit.Test
    fun testWeirdArtifact() {
        val execRoot: Path? = scratch.getFileSystem().getPath("/")
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                ActionsTestUtil.Companion.createArtifactWithExecPath(
                    ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "a"),
                    PathFragment.create("c")
                )
                    .getRootRelativePath()
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun derivedArtifactCodecs(
        @TestParameter includeGeneratingActionKey: Boolean, @TestParameter useSharedValues: Boolean
    ) {
        val artifactContext: ArtifactSerializationContext =
            object : ArtifactSerializationContext() {
                public override fun getSourceArtifact(
                    execPath: PathFragment?, root: ArtifactRoot?, owner: ArtifactOwner?
                ): SourceArtifact? {
                    throw java.lang.UnsupportedOperationException()
                }

                public override fun includeGeneratingActionKey(
                    artifact: DerivedArtifact, context: SerializationDependencyProvider?
                ): Boolean {
                    return includeGeneratingActionKey
                            || !artifact
                        .getGeneratingActionKey()
                        .equals(ActionsTestUtil.Companion.NULL_ACTION_LOOKUP_DATA)
                }

                public override fun getOmittedGeneratingActionKey(
                    context: SerializationDependencyProvider?
                ): ActionLookupData? {
                    Truth.assertThat(includeGeneratingActionKey).isFalse()
                    return ActionsTestUtil.Companion.NULL_ACTION_LOOKUP_DATA
                }
            }

        val artifact: DerivedArtifact =
            ActionsTestUtil.Companion.createArtifact(rootDir, "dir/out.txt") as DerivedArtifact
        artifact.setGeneratingActionKey(ActionsTestUtil.Companion.NULL_ACTION_LOOKUP_DATA)

        val anotherRoot: ArtifactRoot =
            ArtifactRoot.asDerivedRoot(scratch.getFileSystem().getPath("/"), RootType.OUTPUT, "other")
        val anotherArtifact: DerivedArtifact =
            DerivedArtifact.create(
                anotherRoot,
                anotherRoot.getExecPath().getRelative("dir/out.txt"),
                ActionsTestUtil.Companion.NULL_ARTIFACT_OWNER
            )
        anotherArtifact.setGeneratingActionKey(ActionsTestUtil.Companion.NULL_ACTION_LOOKUP_DATA)

        val tree: SpecialArtifact? =
            createTreeArtifactWithGeneratingAction(
                rootDir, rootDir.getExecPath().getRelative("tree")
            )
        val treeChild: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(tree, "child")
        val archivedTree: ArchivedTreeArtifact? = ArchivedTreeArtifact.createForTree(tree)
        val customArchivedTree: ArchivedTreeArtifact? =
            ArchivedTreeArtifact.createWithCustomDerivedTreeRoot(
                tree, PathFragment.create("custom"), PathFragment.create("archived.zip")
            )

        val templateExpansionTree: SpecialArtifact? =
            createTreeArtifactWithGeneratingAction(
                rootDir, rootDir.getExecPath().getRelative("template")
            )
        val expansionOutput: TreeFileArtifact =
            TreeFileArtifact.createTemplateExpansionOutput(
                templateExpansionTree,
                "output",
                ActionsTestUtil.Companion.NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER
            )
        expansionOutput.setGeneratingActionKey(
            ActionLookupData.create(ActionsTestUtil.Companion.NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER, 0)
        )
        val expansionSubdir: SpecialArtifact =
            SpecialArtifact.createSubTreeArtifact(
                templateExpansionTree,
                PathFragment.create("subdir"),
                ActionsTestUtil.Companion.NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER
            )
        expansionSubdir.setGeneratingActionKey(
            ActionLookupData.create(ActionsTestUtil.Companion.NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER, 1)
        )

        val tester: SerializationTester =
            SerializationTester(
                artifact,
                anotherArtifact,
                tree,
                treeChild,
                archivedTree,
                customArchivedTree,
                expansionOutput,
                expansionSubdir
            )
                .addDependency(FileSystem::class.java, scratch.getFileSystem())
                .addDependency(
                    RootCodecDependencies::class.java, RootCodecDependencies(anotherRoot.getRoot())
                )
                .addDependency(ArtifactSerializationContext::class.java, artifactContext)

        if (useSharedValues) {
            for (codec in ArtifactCodecs.VALUE_SHARING_CODECS) {
                tester.addCodec(codec)
            }
            tester.makeMemoizingAndAllowFutureBlocking( /* allowFutureBlocking= */true)
        }

        tester.runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sourceArtifactCodecRecyclesSourceArtifactInstances(
        @TestParameter useSharedValues: Boolean
    ) {
        val root: Root? = Root.fromPath(scratch.dir("/"))
        val artifactRoot: ArtifactRoot = ArtifactRoot.asSourceRoot(root)
        val artifactFactory: ArtifactFactory =
            ArtifactFactory(execDir.getParentDirectory(), "blaze-out")

        var objectCodecs: ObjectCodecs =
            ObjectCodecs(
                AutoRegistry.get()
                    .getBuilder()
                    .addReferenceConstant(scratch.getFileSystem())
                    .setAllowDefaultCodec(true)
                    .build(),
                com.google.common.collect.ImmutableClassToInstanceMap.builder<Any?>()
                    .put<FileSystem?>(FileSystem::class.java, scratch.getFileSystem())
                    .put<ArtifactSerializationContext?>(
                        ArtifactSerializationContext::class.java,
                        artifactFactory::getSourceArtifact
                    )
                    .put<RootCodecDependencies?>(
                        RootCodecDependencies::class.java,
                        RootCodecDependencies(artifactRoot.getRoot())
                    )
                    .build()
            )

        var service: FingerprintValueService? = null
        if (useSharedValues) {
            service = FingerprintValueService.createForTesting(FingerprintValueStore.inMemoryStore())
            for (codec in ArtifactCodecs.VALUE_SHARING_CODECS) {
                objectCodecs =
                    objectCodecs.withCodecOverridesForTesting(com.google.common.collect.ImmutableList.of<E?>(codec))
            }
        }

        val pathFragment: PathFragment? = PathFragment.create("src/foo.cc")
        val owner: ArtifactOwner = LabelArtifactOwner(Label.parseCanonicalUnchecked("//foo:bar"))
        val sourceArtifact: SourceArtifact = SourceArtifact(artifactRoot, pathFragment, owner)

        val deserialized1: SourceArtifact?
        val deserialized2: SourceArtifact?
        if (useSharedValues) {
            deserialized1 =
                objectCodecs.deserializeMemoizedAndBlocking(
                    service,
                    objectCodecs.serializeMemoizedAndBlocking(service, sourceArtifact).getObject()
                ) as SourceArtifact?
            deserialized2 =
                objectCodecs.deserializeMemoizedAndBlocking(
                    service,
                    objectCodecs.serializeMemoizedAndBlocking(service, sourceArtifact).getObject()
                ) as SourceArtifact?
        } else {
            deserialized1 =
                objectCodecs.deserialize(objectCodecs.serialize(sourceArtifact)) as SourceArtifact?
            deserialized2 =
                objectCodecs.deserialize(objectCodecs.serialize(sourceArtifact)) as SourceArtifact?
        }
        assertThat(deserialized1).isSameInstanceAs(deserialized2)

        val sourceArtifactFromFactory: Artifact? =
            artifactFactory.getSourceArtifact(pathFragment, root, owner)
        val deserialized: Artifact?
        if (useSharedValues) {
            deserialized =
                objectCodecs.deserializeMemoizedAndBlocking(
                    service,
                    objectCodecs
                        .serializeMemoizedAndBlocking(service, sourceArtifactFromFactory)
                        .getObject()
                ) as Artifact?
        } else {
            deserialized =
                objectCodecs.deserialize(objectCodecs.serialize(sourceArtifactFromFactory)) as Artifact?
        }
        assertThat(sourceArtifactFromFactory).isSameInstanceAs(deserialized)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLongDirname() {
        val dirName: String? = createDirNameArtifact().getDirname()

        Truth.assertThat(dirName).isEqualTo("aaa/bbb/ccc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirnameInExecutionDir() {
        val artifact: Artifact =
            createArtifact(
                ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/foo"))),
                scratch.file("/foo/bar.txt")
            )

        assertThat(artifact.getDirname()).isEqualTo(".")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCanConstructPathFromDirAndFilename() {
        val artifact: Artifact = createDirNameArtifact()
        val constructed: String? = java.lang.String.format("%s/%s", artifact.getDirname(), artifact.getFilename())

        Truth.assertThat(constructed).isEqualTo("aaa/bbb/ccc/ddd")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsSourceArtifact() {
        assertThat(
            SourceArtifact(
                ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/"))),
                PathFragment.create("src/foo.cc"),
                ArtifactOwner.NULL_OWNER
            )
                .isSourceArtifact()
        )
            .isTrue()
        assertThat(
            createArtifact(
                ArtifactRoot.asDerivedRoot(scratch.dir("/genfiles"), RootType.OUTPUT, "aaa"),
                scratch.file("/genfiles/aaa/bar.out")
            )
                .isSourceArtifact()
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetRoot() {
        val execRoot: Path? = scratch.getFileSystem().getPath("/")
        val root: ArtifactRoot? = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "newRoot")
        assertThat(ActionsTestUtil.Companion.createArtifact(root, scratch.file("/newRoot/foo")).getRoot())
            .isEqualTo(root)
    }

    @org.junit.Test
    fun hashCodeAndEquals() {
        val execRoot: Path? = scratch.getFileSystem().getPath("/")
        val root: ArtifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "newRoot")
        val firstOwner: ActionLookupKey =
            object : ActionLookupKey() {
                public override fun getLabel(): Label? {
                    return null
                }

                public override fun getConfigurationKey(): BuildConfigurationKey? {
                    return null
                }

                public override fun functionName(): SkyFunctionName? {
                    return null
                }
            }
        val secondOwner: ActionLookupKey =
            object : ActionLookupKey() {
                public override fun getLabel(): Label? {
                    return null
                }

                public override fun getConfigurationKey(): BuildConfigurationKey? {
                    return null
                }

                public override fun functionName(): SkyFunctionName? {
                    return null
                }
            }
        val derived1: DerivedArtifact =
            DerivedArtifact.create(root, PathFragment.create("newRoot/shared"), firstOwner)
        derived1.setGeneratingActionKey(ActionLookupData.create(firstOwner, 0))
        val derived2: DerivedArtifact =
            DerivedArtifact.create(root, PathFragment.create("newRoot/shared"), secondOwner)
        derived2.setGeneratingActionKey(ActionLookupData.create(secondOwner, 0))
        val sourceRoot: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(root.getRoot().asPath()))
        val source1: Artifact = SourceArtifact(sourceRoot, PathFragment.create("shared"), firstOwner)
        val source2: Artifact = SourceArtifact(sourceRoot, PathFragment.create("shared"), secondOwner)
        EqualsTester()
            .addEqualityGroup(derived1)
            .addEqualityGroup(derived2)
            .addEqualityGroup(source1, source2)
            .testEquals()
        assertThat(derived1.hashCode()).isNotEqualTo(derived2.hashCode())
        assertThat(derived1.hashCode()).isNotEqualTo(source1.hashCode())
        assertThat(source1.hashCode()).isEqualTo(source2.hashCode())
        val wrapper1: Artifact.OwnerlessArtifactWrapper = OwnerlessArtifactWrapper(derived1)
        val wrapper2: Artifact.OwnerlessArtifactWrapper = OwnerlessArtifactWrapper(derived2)
        val wrapper3: Artifact.OwnerlessArtifactWrapper = OwnerlessArtifactWrapper(source1)
        val wrapper4: Artifact.OwnerlessArtifactWrapper = OwnerlessArtifactWrapper(source2)
        EqualsTester()
            .addEqualityGroup(wrapper1, wrapper2)
            .addEqualityGroup(wrapper3, wrapper4)
            .testEquals()
        val path1: Path? = derived1.getPath()
        val path2: Path? = derived2.getPath()
        val path3: Path? = source1.getPath()
        val path4: Path? = source2.getPath()
        EqualsTester().addEqualityGroup(path1, path2, path3, path4).testEquals()
    }

    @Throws(java.lang.Exception::class)
    private fun createDirNameArtifact(): Artifact {
        return createArtifact(
            ArtifactRoot.asSourceRoot(Root.fromPath(scratch.dir("/"))),
            scratch.file("/aaa/bbb/ccc/ddd")
        )
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testGetRepositoryRelativePathExternalSourceArtifacts() {
        val externalRoot: ArtifactRoot? =
            ArtifactRoot.asExternalSourceRoot(
                Root.fromPath(
                    scratch
                        .dir("/output_base")
                        .getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION)
                        .getRelative("foo")
                )
            )

        // --experimental_sibling_repository_layout not set
        assertThat(
            SourceArtifact(
                externalRoot,
                LabelConstants.EXTERNAL_PATH_PREFIX.getRelative("foo/bar/baz.cc"),
                ArtifactOwner.NULL_OWNER
            )
                .getRepositoryRelativePath()
        )
            .isEqualTo(PathFragment.create("bar/baz.cc"))

        // --experimental_sibling_repository_layout set
        assertThat(
            SourceArtifact(
                externalRoot,
                LabelConstants.EXPERIMENTAL_EXTERNAL_PATH_PREFIX.getRelative("foo/bar/baz.cc"),
                ArtifactOwner.NULL_OWNER
            )
                .getRepositoryRelativePath()
        )
            .isEqualTo(PathFragment.create("bar/baz.cc"))
    }

    @org.junit.Test
    fun archivedTreeArtifact_create_returnsArtifactInArchivedRoot() {
        val root: ArtifactRoot =
            ArtifactRoot.asDerivedRoot(execDir, RootType.OUTPUT, "blaze-out", "fastbuild")
        val tree: SpecialArtifact = createTreeArtifact(root, "tree")

        val archivedTreeArtifact: ArchivedTreeArtifact = ArchivedTreeArtifact.createForTree(tree)

        assertThat(archivedTreeArtifact.getParent()).isSameInstanceAs(tree)
        assertThat(archivedTreeArtifact.getArtifactOwner())
            .isSameInstanceAs(ActionsTestUtil.Companion.NULL_ARTIFACT_OWNER)
        assertThat(archivedTreeArtifact.getExecPathString())
            .isEqualTo("blaze-out/:archived_tree_artifacts/fastbuild/tree.zip")
        assertThat(archivedTreeArtifact.getRoot().getExecPathString())
            .isEqualTo("blaze-out/:archived_tree_artifacts/fastbuild")
    }

    @org.junit.Test
    fun archivedTreeArtifact_create_returnsArtifactWithGeneratingActionFromParent() {
        val actionLookupKey: ActionLookupKey? = Mockito.mock<ActionLookupKey?>(ActionLookupKey::class.java)
        val actionLookupData: ActionLookupData = ActionLookupData.create(actionLookupKey, 0)
        val tree: SpecialArtifact = createTreeArtifact(rootDir, "tree", actionLookupData)

        val archivedTreeArtifact: ArchivedTreeArtifact = ArchivedTreeArtifact.createForTree(tree)

        assertThat(archivedTreeArtifact.getExecPathString())
            .isEqualTo("root/:archived_tree_artifacts/tree.zip")
        assertThat(archivedTreeArtifact.getArtifactOwner()).isSameInstanceAs(actionLookupKey)
        assertThat(archivedTreeArtifact.getGeneratingActionKey()).isSameInstanceAs(actionLookupData)
    }

    @org.junit.Test
    fun archivedTreeArtifact_createWithCustomDerivedTreeRoot_returnsArtifactWithCustomRoot() {
        val root: ArtifactRoot =
            ArtifactRoot.asDerivedRoot(execDir, RootType.OUTPUT, "blaze-out", "fastbuild")
        val tree: SpecialArtifact = createTreeArtifact(root, "dir/tree")

        val archivedTreeArtifact: ArchivedTreeArtifact =
            ArchivedTreeArtifact.createWithCustomDerivedTreeRoot(
                tree, PathFragment.create("custom/custom2"), PathFragment.create("treePath/file.xyz")
            )

        assertThat(archivedTreeArtifact.getParent()).isSameInstanceAs(tree)
        assertThat(archivedTreeArtifact.getExecPathString())
            .isEqualTo("blaze-out/custom/custom2/fastbuild/treePath/file.xyz")
        assertThat(archivedTreeArtifact.getRoot().getExecPathString())
            .isEqualTo("blaze-out/custom/custom2/fastbuild")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun archivedTreeArtifact_codec_roundTripsArchivedArtifact(
        @TestParameter useSharedValues: Boolean
    ) {
        val artifact1: ArchivedTreeArtifact = createArchivedTreeArtifact(rootDir, "tree1")
        val anotherRoot: ArtifactRoot =
            ArtifactRoot.asDerivedRoot(scratch.getFileSystem().getPath("/"), RootType.OUTPUT, "src")
        val artifact2: ArchivedTreeArtifact = createArchivedTreeArtifact(anotherRoot, "tree2")
        val tester: SerializationTester =
            SerializationTester(artifact1, artifact2)
                .addDependency(FileSystem::class.java, scratch.getFileSystem())
                .addDependency(
                    RootCodecDependencies::class.java, RootCodecDependencies(anotherRoot.getRoot())
                )
                .addDependencies(SerializationDepsUtils.SERIALIZATION_DEPS_FOR_TEST)
                .< ArchivedTreeArtifact > setVerificationFunction < ArchivedTreeArtifact ? > (
                    { original, deserialized ->
                        assertThat(original).isEqualTo(deserialized)
                        assertThat(original.getGeneratingActionKey())
                            .isEqualTo(deserialized.getGeneratingActionKey())
                    })
        if (useSharedValues) {
            for (codec in ArtifactCodecs.VALUE_SHARING_CODECS) {
                tester.addCodec(codec)
            }
            tester.makeMemoizingAndAllowFutureBlocking( /* allowFutureBlocking= */true)
        }
        tester.runTests()
    }

    @org.junit.Test
    fun archivedTreeArtifact_getExecPathWithinArchivedArtifactsTree_returnsCorrectPath() {
        assertThat(
            ArchivedTreeArtifact.getExecPathWithinArchivedArtifactsTree(
                PathFragment.create("bazel-out/k8-fastbuild/bin/dir/subdir")
            )
        )
            .isEqualTo(
                PathFragment.create("bazel-out/:archived_tree_artifacts/k8-fastbuild/bin/dir/subdir")
            )
    }

    @org.junit.Test
    fun mappedArtifact() {
        val semantics: StarlarkSemantics? = PATH_MAPPER.storeIn(StarlarkSemantics.DEFAULT)

        val sourceRoot: Root? = Root.fromPath(scratch.getFileSystem().getPath("/some/path"))
        val sourceArtifactRoot: ArtifactRoot = ArtifactRoot.asSourceRoot(sourceRoot)
        val sourceArtifact1: Artifact =
            ActionsTestUtil.Companion.createArtifactWithExecPath(
                sourceArtifactRoot, PathFragment.create("path/to/pkg/file1")
            )
        val sourceArtifact2: Artifact =
            ActionsTestUtil.Companion.createArtifactWithExecPath(
                sourceArtifactRoot, PathFragment.create("path/to/pkg/file2")
            )

        val execRoot: Path? = scratch.getFileSystem().getPath("/some/path")
        val outputArtifactRoot: ArtifactRoot =
            ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "output", "k8-opt", "bin")
        val outputArtifact1: Artifact =
            ActionsTestUtil.Companion.createArtifactWithExecPath(
                outputArtifactRoot, PathFragment.create("output/k8-opt/bin/path/to/pkg/file1")
            )
        val outputArtifact2: Artifact =
            ActionsTestUtil.Companion.createArtifactWithExecPath(
                outputArtifactRoot, PathFragment.create("output/k8-opt/bin/path/to/pkg/file2")
            )

        assertThat(sourceArtifact1.getExecPathStringForStarlark(semantics))
            .isEqualTo("path/to/pkg/file1")
        assertThat(sourceArtifact1.getDirnameForStarlark(semantics)).isEqualTo("path/to/pkg")

        val mappedSourceRoot1: FileRootApi = sourceArtifact1.getRootForStarlark(semantics)
        assertThat(mappedSourceRoot1.execPathString).isEqualTo("")

        assertThat(sourceArtifact2.getExecPathStringForStarlark(semantics))
            .isEqualTo("path/to/pkg/file2")
        assertThat(sourceArtifact2.getDirnameForStarlark(semantics)).isEqualTo("path/to/pkg")

        val mappedSourceRoot2: FileRootApi = sourceArtifact1.getRootForStarlark(semantics)
        assertThat(mappedSourceRoot2.execPathString).isEqualTo("")

        assertThat(outputArtifact1.getExecPathStringForStarlark(semantics))
            .isEqualTo("output/3540078408/path/to/pkg/file1")
        assertThat(outputArtifact1.getDirnameForStarlark(semantics))
            .isEqualTo("output/3540078408/path/to/pkg")

        val mappedOutputRoot1: FileRootApi = outputArtifact1.getRootForStarlark(semantics)
        assertThat(mappedOutputRoot1.execPathString).isEqualTo("output/3540078408")

        assertThat(outputArtifact2.getExecPathStringForStarlark(semantics))
            .isEqualTo("output/3540078409/path/to/pkg/file2")
        assertThat(outputArtifact2.getDirnameForStarlark(semantics))
            .isEqualTo("output/3540078409/path/to/pkg")

        val mappedOutputRoot2: FileRootApi = outputArtifact2.getRootForStarlark(semantics)
        assertThat(mappedOutputRoot2.execPathString).isEqualTo("output/3540078409")

        // Starlark equality uses Object#equals.
        // Mapped roots are always distinct from non-mapped roots, even if their paths are equal.
        EqualsTester()
            .addEqualityGroup(mappedSourceRoot1, mappedSourceRoot2)
            .addEqualityGroup(mappedOutputRoot1)
            .addEqualityGroup(mappedOutputRoot2)
            .addEqualityGroup(sourceRoot)
            .addEqualityGroup(outputArtifactRoot)
            .testEquals()

        val starlarkCompare: com.google.common.base.Equivalence<FileRootApi?>? =
            object : com.google.common.base.Equivalence<FileRootApi?>() {
                override fun doEquivalent(a: FileRootApi?, b: FileRootApi?): Boolean {
                    // Compare a and b in both directions as the implementations of compareTo may be
                    // different.
                    return Starlark.ORDERING.compare(a, b) == 0 && Starlark.ORDERING.compare(b, a) == 0
                }

                override fun doHash(comparable: FileRootApi?): Int {
                    return 0
                }
            }

        val e: java.lang.ClassCastException? =
            org.junit.Assert.assertThrows<java.lang.ClassCastException?>(
                java.lang.ClassCastException::class.java,
                org.junit.function.ThrowingRunnable {
                    Starlark.ORDERING.compare(
                        mappedOutputRoot1,
                        outputArtifactRoot
                    )
                })
        Truth.assertThat(e).hasMessageThat().isEqualTo("unsupported comparison: mapped_root <=> root")

        EquivalenceTester.of<FileRootApi?>(starlarkCompare)
            .addEquivalenceGroup(mappedSourceRoot1, mappedSourceRoot2)
            .addEquivalenceGroup(mappedOutputRoot1)
            .addEquivalenceGroup(mappedOutputRoot2)
            .test()
    }

    companion object {
        private fun getUsedMemory(): Long {
            GcFinalization.awaitFullGc()
            return java.lang.Runtime.getRuntime().totalMemory() - java.lang.Runtime.getRuntime().freeMemory()
        }

        private val PATH_MAPPER: PathMapper = PathMapper { execPath ->
            if (execPath.startsWith(PathFragment.create("output"))) {
                // output/k8-opt/bin/path/to/pkg/file --> output/<hash>/path/to/pkg/file
                return@PathMapper execPath
                    .subFragment(0, 1)
                    .getRelative(java.lang.Integer.toUnsignedString(execPath.subFragment(3).hashCode()))
                    .getRelative(execPath.subFragment(3))
            } else {
                return@PathMapper execPath
            }
        }

        private fun createTreeArtifact(root: ArtifactRoot, relativePath: String?): SpecialArtifact {
            return createTreeArtifact(root, relativePath, ActionsTestUtil.Companion.NULL_ACTION_LOOKUP_DATA)
        }

        private fun createTreeArtifact(
            root: ArtifactRoot, relativePath: String?, actionLookupData: ActionLookupData
        ): SpecialArtifact {
            val treeArtifact: SpecialArtifact =
                SpecialArtifact.create(
                    root,
                    root.getExecPath().getRelative(relativePath),
                    actionLookupData.getActionLookupKey(),
                    SpecialArtifactType.TREE
                )
            treeArtifact.setGeneratingActionKey(actionLookupData)
            return treeArtifact
        }

        private fun createArchivedTreeArtifact(
            root: ArtifactRoot, treeRelativePath: String?
        ): ArchivedTreeArtifact {
            return ArchivedTreeArtifact.createForTree(createTreeArtifact(root, treeRelativePath))
        }
    }
}
