// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.merkletree

import build.bazel.remote.execution.v2.Digest
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableClassToInstanceMap
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.devtools.build.lib.clock.JavaClock
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache
import org.junit.Test
import java.time.Duration
import kotlin.collections.ArrayList

@RunWith(JUnit4::class)
class MerkleTreeComputerTest {
    private var execRoot: Path? = null
    private var artifactRoot: ArtifactRoot? = null

    @Before
    @Throws(IOException::class)
    fun setUp() {
        val fs: InMemoryFileSystem = InMemoryFileSystem(JavaClock(), DigestHashFunction.SHA256)
        execRoot = fs.getPath("/execroot/_main")
        execRoot.createDirectoryAndParents()
        artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, ArtifactRoot.RootType.OUTPUT, "outputs")
        Preconditions.checkNotNull<T?>(artifactRoot.getRoot().asPath()).createDirectoryAndParents()
    }

    @Test
    @Throws(Exception::class)
    fun testSubtreeComputationCancelled_subsequentReusingCallNotAffected() {
        val fakeFileCache = FakeActionInputFileCache()
        val treeArtifactInput: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(
                artifactRoot, "dir/subdir/tree_artifact"
            )
        treeArtifactInput.getPath().createDirectoryAndParents()
        val treeArtifactBuilder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            TreeArtifactValue.newBuilder(treeArtifactInput)
        val treeFileArtifact: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Artifact.TreeFileArtifact.createTreeOutput(treeArtifactInput, "file")
        FileSystemUtils.writeContentAsLatin1(treeFileArtifact.getPath(), "file content")
        treeArtifactBuilder.putChild(
            treeFileArtifact, FileArtifactValue.createForTesting(treeFileArtifact)
        )
        fakeFileCache.putTreeArtifact(treeArtifactInput, treeArtifactBuilder.build())
        val spawn: @NotNull Spawn = SpawnBuilder().withInputs(treeArtifactInput).build()
        val merkleTreeComputer = createMerkleTreeComputer( /* uploader= */null)

        val treeFileMetadataAccessed: CountDownLatch = CountDownLatch(1)
        val delayedMetadataProvider: DelegatingPairInputMetadataProvider =
            DelegatingPairInputMetadataProvider(
                object : InputMetadataProvider() {
                    @Throws(InterruptedException::class)
                    public override fun getInputMetadataChecked(input: ActionInput?): FileArtifactValue? {
                        if (input != treeFileArtifact || treeFileMetadataAccessed.getCount() == 0L) {
                            return null
                        }
                        treeFileMetadataAccessed.countDown()
                        Thread.sleep(Long.Companion.MAX_VALUE)
                        throw IllegalStateException("not reached")
                    }

                    public override fun getTreeMetadata(input: ActionInput?): TreeArtifactValue? {
                        return null
                    }

                    public override fun getEnclosingTreeMetadata(execPath: PathFragment?): TreeArtifactValue? {
                        return null
                    }

                    public override fun getFileset(input: ActionInput?): FilesetOutputTree? {
                        return null
                    }

                    val filesets: ImmutableMap<Artifact, FilesetOutputTree>
                        get() = ImmutableMap.of<Artifact?, FilesetOutputTree?>()

                    public override fun getRunfilesMetadata(input: ActionInput?): RunfilesArtifactValue? {
                        return null
                    }

                    val runfilesTrees: ImmutableList<RunfilesTree>?
                        get() = null

                    public override fun getInput(execPath: PathFragment?): ActionInput? {
                        return null
                    }
                },
                fakeFileCache
            )
        val capturedThrowable: AtomicReference<Throwable?> = AtomicReference<Throwable?>()
        val buildThread =
            Thread(
                Runnable {
                    try {
                        val unused =
                            merkleTreeComputer.buildForSpawn(
                                spawn,
                                ImmutableSet.of<PathFragment>(),  /* scrubber= */
                                null,
                                createSpawnExecutionContext(spawn, delayedMetadataProvider),
                                RemotePathResolver.createDefault(execRoot),
                                MerkleTreeComputer.BlobPolicy.KEEP
                            )
                    } catch (t: Throwable) {
                        if (t is InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                        capturedThrowable.set(t)
                    }
                })
        buildThread.start()
        // Wait until the Merkle subtree for the tree artifact started to build, then interrupt it.
        treeFileMetadataAccessed.await()
        buildThread.interrupt()
        buildThread.join()
        Truth.assertThat(capturedThrowable.get()).isInstanceOf(InterruptedException::class.java)

        // Expected to succeed despite the subtree computation having been canceled.
        val unused =
            merkleTreeComputer.buildForSpawn(
                spawn,
                ImmutableSet.of<PathFragment>(),  /* scrubber= */
                null,
                createSpawnExecutionContext(spawn, fakeFileCache),
                RemotePathResolver.createDefault(execRoot),
                MerkleTreeComputer.BlobPolicy.KEEP
            )
    }

    @Test
    @Throws(Throwable::class)
    fun testSubtreeComputationCancelled_concurrentReusingCallNotAffected() {
        val fakeFileCache = FakeActionInputFileCache()
        val treeArtifactInput: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(
                artifactRoot, "dir/subdir/tree_artifact"
            )
        treeArtifactInput.getPath().createDirectoryAndParents()
        val treeArtifactBuilder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            TreeArtifactValue.newBuilder(treeArtifactInput)
        val treeFileArtifact: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Artifact.TreeFileArtifact.createTreeOutput(treeArtifactInput, "file")
        FileSystemUtils.writeContentAsLatin1(treeFileArtifact.getPath(), "file content")
        treeArtifactBuilder.putChild(
            treeFileArtifact, FileArtifactValue.createForTesting(treeFileArtifact)
        )
        fakeFileCache.putTreeArtifact(treeArtifactInput, treeArtifactBuilder.build())
        val spawn: @NotNull Spawn = SpawnBuilder().withInputs(treeArtifactInput).build()
        val ensureInputsPresentCount: AtomicInteger = AtomicInteger()
        val merkleTreeComputer =
            createMerkleTreeComputer(
                object : MerkleTreeUploader() {
                    override fun uploadBlob(
                        context: RemoteActionExecutionContext?, digest: Digest?, data: ByteArray?
                    ): ListenableFuture<Void?>? {
                        return Futures.immediateVoidFuture()
                    }

                    override fun uploadFile(
                        context: RemoteActionExecutionContext?,
                        remotePathResolver: RemotePathResolver?,
                        digest: Digest?,
                        path: Path?,
                        force: Boolean
                    ): ListenableFuture<Void?>? {
                        return Futures.immediateVoidFuture()
                    }

                    override fun uploadVirtualActionInput(
                        context: RemoteActionExecutionContext?,
                        digest: Digest?,
                        virtualActionInput: VirtualActionInput?
                    ): ListenableFuture<Void?>? {
                        return Futures.immediateVoidFuture()
                    }

                    public override fun ensureInputsPresent(
                        context: RemoteActionExecutionContext?,
                        merkleTree: Uploadable?,
                        force: Boolean,
                        remotePathResolver: RemotePathResolver?
                    ) {
                        ensureInputsPresentCount.incrementAndGet()
                    }
                })

        val treeFileMetadataAccessed: CountDownLatch = CountDownLatch(1)
        val treeFileMetadataContinue: CountDownLatch = CountDownLatch(1)
        val delayedMetadataProvider: DelegatingPairInputMetadataProvider =
            DelegatingPairInputMetadataProvider(
                object : InputMetadataProvider() {
                    @Throws(InterruptedException::class)
                    public override fun getInputMetadataChecked(input: ActionInput?): FileArtifactValue? {
                        if (input == treeFileArtifact && treeFileMetadataAccessed.getCount() >= 0) {
                            treeFileMetadataAccessed.countDown()
                            treeFileMetadataContinue.await()
                        }
                        return null
                    }

                    public override fun getTreeMetadata(input: ActionInput?): TreeArtifactValue? {
                        return null
                    }

                    public override fun getEnclosingTreeMetadata(execPath: PathFragment?): TreeArtifactValue? {
                        return null
                    }

                    public override fun getFileset(input: ActionInput?): FilesetOutputTree? {
                        return null
                    }

                    val filesets: ImmutableMap<Artifact, FilesetOutputTree>
                        get() = ImmutableMap.of<Artifact?, FilesetOutputTree?>()

                    public override fun getRunfilesMetadata(input: ActionInput?): RunfilesArtifactValue? {
                        return null
                    }

                    val runfilesTrees: ImmutableList<RunfilesTree>?
                        get() = null

                    public override fun getInput(execPath: PathFragment?): ActionInput? {
                        return null
                    }
                },
                fakeFileCache
            )
        val interruptedThreadThrowable: AtomicReference<Throwable?> = AtomicReference<Throwable?>()
        val interruptedThread =
            Thread(
                Runnable {
                    try {
                        val unused =
                            merkleTreeComputer.buildForSpawn(
                                spawn,
                                ImmutableSet.of<PathFragment>(),  /* scrubber= */
                                null,
                                createSpawnExecutionContext(spawn, delayedMetadataProvider),
                                RemotePathResolver.createDefault(execRoot),
                                MerkleTreeComputer.BlobPolicy.KEEP
                            )
                    } catch (t: Throwable) {
                        if (t is InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                        interruptedThreadThrowable.set(t)
                    }
                })
        interruptedThread.start()
        // Wait until the Merkle subtree for the tree artifact started to build.
        treeFileMetadataAccessed.await()

        val unrelatedThreads = ArrayList<Thread>()
        val unrelatedThreadThrowables: ArrayList<AtomicReference<Throwable?>> = ArrayList<AtomicReference<Throwable?>>()
        for (i in 0..9) {
            val capturedThrowable: AtomicReference<Throwable?> = AtomicReference<Throwable?>()
            unrelatedThreadThrowables.add(capturedThrowable)
            val thread =
                Thread(
                    Runnable {
                        try {
                            val unused =
                                merkleTreeComputer.buildForSpawn(
                                    spawn,
                                    ImmutableSet.of<PathFragment>(),  /* scrubber= */
                                    null,
                                    createSpawnExecutionContext(spawn, fakeFileCache),
                                    RemotePathResolver.createDefault(execRoot),
                                    MerkleTreeComputer.BlobPolicy.KEEP
                                )
                        } catch (t: Throwable) {
                            if (t is InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                            capturedThrowable.set(t)
                        }
                    })
            thread.start()
            unrelatedThreads.add(thread)
            // Wait for the new subtree build to block on the first one.
            while (thread.getState() == Thread.State.RUNNABLE || thread.getState() == Thread.State.NEW) {
                Thread.sleep(Duration.ofMillis(10))
            }
        }

        // Interrupting the first build does not result in its subtree build future being canceled since
        // other threads are also waiting for it.
        interruptedThread.interrupt()
        treeFileMetadataContinue.countDown()
        interruptedThread.join()
        for (thread in unrelatedThreads) {
            thread.join()
        }

        Truth.assertThat(interruptedThreadThrowable.get()).isInstanceOf(InterruptedException::class.java)
        for (capturedThrowable in unrelatedThreadThrowables) {
            if (capturedThrowable.get() != null) {
                throw capturedThrowable.get()
            }
        }

        // All threads share a single upload of the subtree.
        Truth.assertThat(ensureInputsPresentCount.get()).isEqualTo(1)
    }

    private fun createMerkleTreeComputer(uploader: MerkleTreeUploader?): MerkleTreeComputer {
        return MerkleTreeComputer(
            DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256),
            uploader,
            "buildRequestId",
            "commandId",
            "_main"
        )
    }

    private fun createSpawnExecutionContext(
        spawn: Spawn?, inputMetadataProvider: InputMetadataProvider?
    ): FakeSpawnExecutionContext {
        return FakeSpawnExecutionContext(
            spawn,
            inputMetadataProvider,
            execRoot,
            FileOutErr(),
            ImmutableClassToInstanceMap.of<ActionContext?>(),  /* actionFileSystem= */
            null
        )
    }
}
