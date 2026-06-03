// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.Digest

/** Tests for [RemoteActionInputFetcher].  */
@RunWith(JUnit4::class)
class RemoteActionInputFetcherTest : ActionInputPrefetcherTestBase() {
    private var digestUtil: DigestUtil? = null

    @Throws(IOException::class)
    override fun setUp() {
        super.setUp()
        val dev: Path = fs.getPath("/dev")
        dev.createDirectory()
        dev.setWritable(false)
        digestUtil = DigestUtil(SyscallCache.NO_CACHE, ActionInputPrefetcherTestBase.Companion.HASH_FUNCTION)
    }

    override fun createPrefetcher(cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?>): AbstractActionInputPrefetcher {
        val combinedCache: CombinedCache = newCombinedCache(digestUtil, cas)
        return RemoteActionInputFetcher(
            com.google.devtools.build.lib.events.Reporter(EventBusEventHandler(eventBus)),
            "none",
            "none",
            combinedCache,
            execRoot,
            tempPathGenerator,
            DUMMY_REMOTE_OUTPUT_CHECKER,
            ActionOutputDirectoryHelper.createForTesting(),
            OutputPermissions.READONLY
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStagingVirtualActionInput() {
        // arrange
        val combinedCache: CombinedCache =
            newCombinedCache(digestUtil, HashMap<com.google.common.hash.HashCode?, ByteArray?>())
        val actionInputFetcher: RemoteActionInputFetcher =
            RemoteActionInputFetcher(
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus()),
                "none",
                "none",
                combinedCache,
                execRoot,
                tempPathGenerator,
                DUMMY_REMOTE_OUTPUT_CHECKER,
                ActionOutputDirectoryHelper.createForTesting(),
                OutputPermissions.READONLY
            )
        val a: VirtualActionInput = ActionsTestUtil.createVirtualActionInput("file1", "hello world")

        // act
        wait(
            actionInputFetcher.prefetchFilesInterruptibly(
                action,
                com.google.common.collect.ImmutableList.of<E?>(a),
                { unused: ActionInput? -> null },
                Priority.MEDIUM,
                Reason.INPUTS
            )
        )

        // assert
        val p: Path = execRoot.getRelative(a.getExecPath())
        assertThat(FileSystemUtils.readContent(p, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("hello world")
        assertThat(p.isExecutable()).isTrue()
        assertThat(actionInputFetcher.downloadedFiles()).isEmpty()
        assertThat(actionInputFetcher.downloadsInProgress()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStagingEmptyVirtualActionInput() {
        // arrange
        val combinedCache: CombinedCache =
            newCombinedCache(digestUtil, HashMap<com.google.common.hash.HashCode?, ByteArray?>())
        val actionInputFetcher: RemoteActionInputFetcher =
            RemoteActionInputFetcher(
                com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus()),
                "none",
                "none",
                combinedCache,
                execRoot,
                tempPathGenerator,
                DUMMY_REMOTE_OUTPUT_CHECKER,
                ActionOutputDirectoryHelper.createForTesting(),
                OutputPermissions.READONLY
            )

        // act
        wait(
            actionInputFetcher.prefetchFilesInterruptibly(
                action,
                com.google.common.collect.ImmutableList.of<E?>(VirtualActionInput.EMPTY_MARKER),
                { unused: ActionInput? -> null },
                Priority.MEDIUM,
                Reason.INPUTS
            )
        )

        // assert that nothing happened
        assertThat(actionInputFetcher.downloadedFiles()).isEmpty()
        assertThat(actionInputFetcher.downloadsInProgress()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prefetchFiles_missingFiles_failsWithSpecificMessage() {
        val metadata: MutableMap<ActionInput?, FileArtifactValue> = HashMap<ActionInput?, FileArtifactValue>()
        val a: Artifact = createRemoteArtifact(
            "file1",
            "hello world",
            metadata,  /* cas= */
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        )
        val prefetcher: AbstractActionInputPrefetcher =
            createPrefetcher(HashMap<com.google.common.hash.HashCode?, ByteArray?>())

        val error: T? =
            org.junit.Assert.assertThrows<T?>(
                BulkTransferException::class.java,
                org.junit.function.ThrowingRunnable {
                    wait(
                        prefetcher.prefetchFilesInterruptibly(
                            action,
                            com.google.common.collect.ImmutableList.of<E?>(a),
                            { key: Any? -> metadata.get(key) },
                            Priority.MEDIUM,
                            Reason.INPUTS
                        )
                    )
                })

        assertThat(prefetcher.downloadedFiles()).isEmpty()
        assertThat(prefetcher.downloadsInProgress()).isEmpty()
        val m: FileArtifactValue = metadata.get(a)
        val digest: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            DigestUtil.buildDigest(m.getDigest(), m.getSize())
        assertThat(error)
            .hasMessageThat()
            .contains(java.lang.String.format("%s/%s", digest.getHash(), digest.getSizeBytes()))
    }

    private fun newCombinedCache(
        digestUtil: DigestUtil?,
        cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?>
    ): CombinedCache {
        val cacheEntries: MutableMap<Digest?, ByteArray?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<Digest?, ByteArray?>(cas.size)
        for (entry in cas.entries) {
            cacheEntries.put(
                DigestUtil.buildDigest(entry.key.asBytes(), entry.value!!.size),
                entry.value
            )
        }
        return CombinedCache(
            InMemoryCacheClient(cacheEntries),  /* diskCacheClient= */
            null,  /* symlinkTemplate= */
            null,
            digestUtil,  /* chunkingEnabled= */
            false
        )
    }

    companion object {
        private val DUMMY_REMOTE_OUTPUT_CHECKER: RemoteOutputChecker =
            RemoteOutputChecker("build", RemoteOutputsMode.MINIMAL, com.google.common.collect.ImmutableList.of<E?>())
    }
}
