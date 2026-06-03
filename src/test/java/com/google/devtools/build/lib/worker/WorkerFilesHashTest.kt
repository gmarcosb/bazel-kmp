// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.actions.ActionInput

/** Tests for [WorkerFilesHash].  */
@RunWith(JUnit4::class)
class WorkerFilesHashTest {
    private val outputRoot: ArtifactRoot =
        ArtifactRoot.asDerivedRoot(Scratch().resolve("/execroot"), RootType.OUTPUT, "bazel-out")

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val workerFilesWithDigests_returnsToolsWithCorrectDigests: Unit
        get() {
            val tool1Digest: ByteArray? = "text1".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            val tool2Digest: ByteArray? = "text2".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            val inputMetadataProvider: InputMetadataProvider =
                createMetadataProvider(
                    com.google.common.collect.ImmutableMap.of<String?, Any?>(
                        "tool1",
                        fileArtifactValue(tool1Digest),
                        "tool2",
                        fileArtifactValue(tool2Digest)
                    )
                )
            val spawn: Spawn =
                SpawnBuilder()
                    .withTool(ActionInputHelper.fromPath("tool1"))
                    .withTool(ActionInputHelper.fromPath("tool2"))
                    .build()

            val filesWithDigests: SortedMap<PathFragment?, ByteArray?>? =
                WorkerFilesHash.getWorkerFilesWithDigests(spawn, inputMetadataProvider)

            Truth.assertThat(filesWithDigests)
                .containsExactly(
                    PathFragment.create("tool1"), tool1Digest, PathFragment.create("tool2"), tool2Digest
                )
                .inOrder()
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val workerFilesWithDigests_treeArtifactTool_returnsExpanded: Unit
        get() {
            val tree: SpecialArtifact = createTreeArtifact("tree")
            val child1: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "child1")
            val child2: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, "child2")
            val child1Digest: ByteArray? = "text1".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            val child2Digest: ByteArray? = "text2".toByteArray(java.nio.charset.StandardCharsets.UTF_8)

            val spawn: Spawn = SpawnBuilder().withTool(tree).build()

            val treeArtifactValue: TreeArtifactValue =
                TreeArtifactValue.newBuilder(tree)
                    .putChild(
                        child1,
                        FileArtifactValue.createForNormalFile(
                            child1Digest,  /* proxy= */null,  /* size= */123
                        )
                    )
                    .putChild(
                        child2,
                        FileArtifactValue.createForNormalFile(
                            child2Digest,  /* proxy= */null,  /* size= */456
                        )
                    )
                    .build()

            val fakeActionInputFileCache: FakeActionInputFileCache = FakeActionInputFileCache()
            fakeActionInputFileCache.putTreeArtifact(tree, treeArtifactValue)
            fakeActionInputFileCache.put(
                child1,
                FileArtifactValue.createForNormalFile(child1Digest,  /* proxy= */null,  /* size= */123)
            )
            fakeActionInputFileCache.put(
                child2,
                FileArtifactValue.createForNormalFile(child2Digest,  /* proxy= */null,  /* size= */456)
            )
            val filesWithDigests: SortedMap<PathFragment?, ByteArray?>? =
                WorkerFilesHash.getWorkerFilesWithDigests(spawn, fakeActionInputFileCache)

            Truth.assertThat(filesWithDigests)
                .containsExactly(child1.getExecPath(), child1Digest, child2.getExecPath(), child2Digest)
                .inOrder()
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val workerFilesWithDigests_spawnWithInputsButNoTools_returnsEmpty: Unit
        get() {
            val inputMetadataProvider: InputMetadataProvider =
                createMetadataProvider(com.google.common.collect.ImmutableMap.of<String?, Any?>())
            val spawn: Spawn = SpawnBuilder().withInputs("file1", "file2").build()

            val filesWithDigests: SortedMap<PathFragment?, ByteArray?>? =
                WorkerFilesHash.getWorkerFilesWithDigests(spawn, inputMetadataProvider)

            Truth.assertThat(filesWithDigests).isEmpty()
        }

    @get:org.junit.Test
    val workerFilesWithDigests_missingDigestForTool_fails: Unit
        get() {
            val inputMetadataProvider: InputMetadataProvider =
                createMetadataProvider(com.google.common.collect.ImmutableMap.of<String?, Any?>())
            val spawn: Spawn = SpawnBuilder().withTool(ActionInputHelper.fromPath("tool")).build()

            org.junit.Assert.assertThrows<T?>(
                MissingInputException::class.java,
                org.junit.function.ThrowingRunnable {
                    WorkerFilesHash.getWorkerFilesWithDigests(
                        spawn,
                        inputMetadataProvider
                    )
                })
        }

    @get:org.junit.Test
    val workerFilesWithDigests_ioExceptionForToolMetadata_fails: Unit
        get() {
            val injected: IOException = IOException("oh no")
            val inputMetadataProvider: InputMetadataProvider =
                createMetadataProvider(
                    com.google.common.collect.ImmutableMap.of<String?, Any?>(
                        "tool",
                        injected
                    )
                )
            val spawn: Spawn = SpawnBuilder().withTool(ActionInputHelper.fromPath("tool")).build()

            val thrown: IOException? =
                org.junit.Assert.assertThrows<IOException?>(
                    IOException::class.java,
                    org.junit.function.ThrowingRunnable {
                        WorkerFilesHash.getWorkerFilesWithDigests(
                            spawn,
                            inputMetadataProvider
                        )
                    })

            Truth.assertThat(thrown).isSameInstanceAs(injected)
        }

    private fun createTreeArtifact(rootRelativePath: String?): SpecialArtifact {
        return ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            outputRoot, outputRoot.getExecPath().getRelative(rootRelativePath)
        )
    }

    companion object {
        private fun createMetadataProvider(
            inputMetadataOrExceptions: com.google.common.collect.ImmutableMap<String?, Any?>
        ): InputMetadataProvider {
            return object : InputMetadataProvider() {
                @Throws(IOException::class)
                public override fun getInputMetadataChecked(input: ActionInput): FileArtifactValue? {
                    val metadataOrException: Any? = inputMetadataOrExceptions.get(input.getExecPathString())
                    if (metadataOrException == null) {
                        return null
                    }
                    if (metadataOrException is IOException) {
                        throw metadataOrException
                    }
                    if (metadataOrException is FileArtifactValue) {
                        return metadataOrException
                    }
                    throw java.lang.AssertionError("Unexpected value: " + metadataOrException)
                }

                public override fun getTreeMetadata(actionInput: ActionInput?): TreeArtifactValue? {
                    return null
                }

                public override fun getEnclosingTreeMetadata(execPath: PathFragment?): TreeArtifactValue? {
                    return null
                }

                public override fun getFileset(input: ActionInput?): FilesetOutputTree? {
                    throw java.lang.UnsupportedOperationException()
                }

                val filesets: MutableMap<Artifact, FilesetOutputTree>?
                    get() {
                        throw java.lang.UnsupportedOperationException()
                    }

                public override fun getRunfilesMetadata(input: ActionInput?): RunfilesArtifactValue? {
                    throw java.lang.UnsupportedOperationException()
                }

                val runfilesTrees: com.google.common.collect.ImmutableList<RunfilesTree?>?
                    get() {
                        throw java.lang.UnsupportedOperationException()
                    }

                public override fun getInput(execPath: PathFragment?): ActionInput? {
                    throw java.lang.UnsupportedOperationException()
                }
            }
        }

        private fun fileArtifactValue(digest: ByteArray?): FileArtifactValue {
            val value: FileArtifactValue = Mockito.mock<FileArtifactValue>(FileArtifactValue::class.java)
            Mockito.`when`<T?>(value.getDigest()).thenReturn(digest)
            return value
        }
    }
}
