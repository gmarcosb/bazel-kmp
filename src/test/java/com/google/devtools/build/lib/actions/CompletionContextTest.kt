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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact

/** Tests for [CompletionContext].  */
@RunWith(JUnit4::class)
class CompletionContextTest {
    private val inputMap: ActionInputMap = ActionInputMap(0)
    private val outputRoot: ArtifactRoot = ArtifactRoot.asDerivedRoot(
        InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/execroot"),
        RootType.OUTPUT,
        "out"
    )

    @org.junit.Test
    fun regularArtifact() {
        val file: Artifact = ActionsTestUtil.Companion.createArtifact(outputRoot, "file")
        inputMap.put(file, DUMMY_METADATA)
        val ctx: CompletionContext = createCompletionContext( /* expandFilesets= */true)

        Truth.assertThat(visit(ctx, file)).containsExactly(file)
    }

    @org.junit.Test
    fun treeArtifact_present() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val treeFile1: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(tree, "file1")
        val treeFile2: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(tree, "file2")
        val treeValue: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(tree)
                .putChild(treeFile1, DUMMY_METADATA)
                .putChild(treeFile2, DUMMY_METADATA)
                .build()
        inputMap.putTreeArtifact(tree, treeValue)
        val ctx: CompletionContext = createCompletionContext( /* expandFilesets= */true)

        Truth.assertThat(visit(ctx, tree)).containsExactly(treeFile1, treeFile2).inOrder()
    }

    @org.junit.Test
    fun fileset_noExpansion() {
        val fileset: SpecialArtifact = createFileset("fs")
        inputMap.putFileset(
            fileset,
            FilesetOutputTree.create(
                com.google.common.collect.ImmutableList.of<E?>(
                    filesetLink("a1", ActionsTestUtil.Companion.createArtifact(outputRoot, "b1")),
                    filesetLink("a2", ActionsTestUtil.Companion.createArtifact(outputRoot, "b2"))
                ),  /* treeArtifacts= */
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        )
        val ctx: CompletionContext = createCompletionContext( /* expandFilesets= */false)

        val receiver: ArtifactReceiver? = Mockito.mock<ArtifactReceiver?>(ArtifactReceiver::class.java)
        ctx.visitArtifacts(com.google.common.collect.ImmutableList.of<E?>(fileset), receiver)
        Mockito.verifyNoInteractions(receiver)

        Truth.assertThat(visit(ctx, fileset)).isEmpty()
    }

    @org.junit.Test
    fun fileset_withExpansion() {
        val fileset: SpecialArtifact = createFileset("fs")
        val b1: Artifact = ActionsTestUtil.Companion.createArtifact(outputRoot, "b1")
        val b2: Artifact = ActionsTestUtil.Companion.createArtifact(outputRoot, "b2")
        val links: com.google.common.collect.ImmutableList<FilesetOutputSymlink?> =
            com.google.common.collect.ImmutableList.of<FilesetOutputSymlink?>(
                filesetLink("a1", b1),
                filesetLink("a2", b2)
            )
        inputMap.putFileset(
            fileset,
            FilesetOutputTree.create(links,  /* treeArtifacts= */com.google.common.collect.ImmutableMap.of<K?, V?>())
        )
        val ctx: CompletionContext = createCompletionContext( /* expandFilesets= */true)

        val receiver: ArtifactReceiver? = Mockito.mock<ArtifactReceiver?>(ArtifactReceiver::class.java)
        ctx.visitArtifacts(com.google.common.collect.ImmutableList.of<E?>(fileset), receiver)
        val inOrder: InOrder = Mockito.inOrder(receiver)
        inOrder
            .verify<Any?>(receiver)
            .acceptFilesetMapping(
                fileset, FilesetOutputSymlink(PathFragment.create("a1"), b1, DUMMY_METADATA)
            )
        inOrder
            .verify<Any?>(receiver)
            .acceptFilesetMapping(
                fileset, FilesetOutputSymlink(PathFragment.create("a2"), b2, DUMMY_METADATA)
            )
    }

    private fun createTreeArtifact(rootRelativePath: String?): SpecialArtifact {
        return createTreeArtifactWithGeneratingAction(
            outputRoot, outputRoot.getExecPath().getRelative(rootRelativePath)
        )
    }

    private fun createFileset(rootRelativePath: String?): SpecialArtifact {
        return SpecialArtifact.create(
            outputRoot,
            outputRoot.getExecPath().getRelative(rootRelativePath),
            ActionsTestUtil.Companion.NULL_ARTIFACT_OWNER,
            SpecialArtifactType.FILESET
        )
    }

    private fun createCompletionContext(expandFilesets: Boolean): CompletionContext {
        return CompletionContext(ArtifactPathResolver.IDENTITY, inputMap, expandFilesets)
    }

    companion object {
        private val DUMMY_METADATA: FileArtifactValue? = FileArtifactValue.createForRemoteFile( /* digest= */
            ByteArray(0),  /* size= */0,  /* locationIndex= */0
        )

        private fun visit(ctx: CompletionContext, artifact: Artifact): MutableList<Artifact?> {
            val visited: MutableList<Artifact?> = java.util.ArrayList<Artifact?>()
            ctx.visitArtifacts(
                com.google.common.collect.ImmutableList.of<E?>(artifact),
                object : ArtifactReceiver() {
                    public override fun accept(artifact: Artifact?, metadata: FileArtifactValue?) {
                        visited.add(artifact)
                    }

                    public override fun acceptFilesetMapping(fileset: Artifact?, link: FilesetOutputSymlink?) {
                        throw java.lang.AssertionError(fileset)
                    }
                })
            return visited
        }

        private fun filesetLink(from: String?, target: Artifact?): FilesetOutputSymlink {
            return FilesetOutputSymlink(PathFragment.create(from), target, DUMMY_METADATA)
        }
    }
}
