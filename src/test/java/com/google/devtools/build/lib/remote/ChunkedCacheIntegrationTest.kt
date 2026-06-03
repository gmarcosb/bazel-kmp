// Copyright 2026 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.vfs.FileSystemUtils.readContent

/** Integration tests for chunked remote cache using SplitBlob/SpliceBlob APIs.  */
@RunWith(JUnit4::class)
class ChunkedCacheIntegrationTest : BuildIntegrationTestCase() {
    val startupOptionClasses: com.google.common.collect.ImmutableList<java.lang.Class<out OptionsBase?>?>?
        get() = com.google.common.collect.ImmutableList.builder<java.lang.Class<out OptionsBase?>?>()
            .addAll(super.startupOptionClasses)
            .add(RemoteStartupOptions::class.java)
            .build()

    @Throws(java.lang.Exception::class)
    override fun setupOptions() {
        super.setupOptions()
        addOptions(
            "--remote_cache=grpc://localhost:" + worker.getPort(),
            "--experimental_remote_cache_chunking"
        )
    }

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.runtimeBuilder
            .addBlazeModule(RemoteModule())
            .addBlazeModule(BuildSummaryStatsModule())
            .addBlazeModule(BlockWaitingModule())

    val spawnModules: com.google.common.collect.ImmutableList<BlazeModule?>?
        get() = com.google.common.collect.ImmutableList.builder<BlazeModule?>()
            .addAll(super.spawnModules)
            .add(StandaloneModule())
            .add(CredentialModule())
            .build()

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun waitDownloads() {
        runtimeWrapper.newCommand()
    }

    private fun getOutputPath(binRelativePath: String?): Path {
        return targetConfiguration.getBinDir().getRoot().getRelative(binRelativePath)
    }

    @Throws(java.lang.Exception::class)
    private fun cleanAndRestartServer() {
        getOutputBase().getRelative("action_cache").deleteTreesBelow()
        createRuntimeWrapper()
    }

    @Throws(IOException::class)
    private fun readFileBytes(path: Path): ByteArray {
        path.getInputStream().use { `in` ->
            return com.google.common.io.ByteStreams.toByteArray(`in`)
        }
    }

    private fun computeDigest(data: ByteArray): Digest {
        val hash: com.google.common.hash.HashCode = com.google.common.hash.Hashing.sha256().hashBytes(data)
        return Digest.newBuilder().setHash(hash.toString()).setSizeBytes(data.size).build()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uploadAndDownloadLargeBlob_withChunking_succeeds() {
        write(
            "BUILD",
            """
        genrule(
            name = "large_file",
            srcs = [],
            outs = ["large.txt"],
            cmd = "dd if=/dev/zero bs=1M count=3 2>/dev/null | tr '\\0' 'a' > ${'$'}@",
        )
        
        """.trimIndent()
        )

        buildTarget("//:large_file")

        val output: Path = getOutputPath("large.txt")
        assertThat(output.exists()).isTrue()
        val originalContent = readFileBytes(output)
        Truth.assertThat(originalContent.size).isAtLeast(2 * 1024 * 1024)

        val blobDigest: Digest = computeDigest(originalContent)

        // Verify SplitBlob returns multiple chunks and each chunk is individually downloadable.
        val metadata: RequestMetadata? =
            RequestMetadata.newBuilder()
                .setCorrelatedInvocationsId("test-build-id")
                .setToolInvocationId("test-command-id")
                .setActionId("test-action-id")
                .setToolDetails(ToolDetails.newBuilder().setToolName("bazel").setToolVersion("test"))
                .build()
        val interceptor: ClientInterceptor? = TracingMetadataUtils.attachMetadataInterceptor(metadata)

        val channel: ManagedChannel =
            ManagedChannelBuilder.forAddress("localhost", worker.getPort())
                .usePlaintext()
                .intercept(interceptor)
                .build()
        try {
            val casStub: ContentAddressableStorageGrpc.ContentAddressableStorageBlockingStub =
                ContentAddressableStorageGrpc.newBlockingStub(channel)

            val splitResponse: SplitBlobResponse =
                casStub.splitBlob(SplitBlobRequest.newBuilder().setBlobDigest(blobDigest).build())
            val chunkDigests: MutableList<Digest> = splitResponse.getChunkDigestsList()

            Truth.assertThat(chunkDigests.size).isGreaterThan(1)
            val totalChunkSize: Long = chunkDigests.stream().mapToLong(Digest::getSizeBytes).sum()
            Truth.assertThat(totalChunkSize).isEqualTo(originalContent.size)

            // Download each chunk individually and reassemble to verify integrity.
            val bsStub: ByteStreamGrpc.ByteStreamBlockingStub = ByteStreamGrpc.newBlockingStub(channel)
            val reassembled: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
            for (chunkDigest in chunkDigests) {
                val resourceName = "blobs/" + chunkDigest.getHash() + "/" + chunkDigest.getSizeBytes()
                val readIter: MutableIterator<ReadResponse?> =
                    bsStub.read(ReadRequest.newBuilder().setResourceName(resourceName).build())
                var chunkBytesRead = 0
                while (readIter.hasNext()) {
                    val data: ByteArray = readIter.next().getData().toByteArray()
                    reassembled.write(data)
                    chunkBytesRead += data.size
                }
                Truth.assertThat(chunkBytesRead).isEqualTo(chunkDigest.getSizeBytes() as Int)
            }
            Truth.assertThat(reassembled.toByteArray()).isEqualTo(originalContent)
        } finally {
            channel.shutdownNow()
        }

        // Delete output and action cache, then rebuild to exercise chunked download.
        output.delete()
        assertThat(output.exists()).isFalse()
        cleanAndRestartServer()

        buildTarget("//:large_file")

        assertThat(output.exists()).isTrue()
        Truth.assertThat(readFileBytes(output)).isEqualTo(originalContent)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleTargets_withChunking_allSucceed() {
        // Multiple large files built in parallel, with a downstream target that depends on them.
        // Use deterministic content (filled with distinct byte patterns) so we can verify integrity.
        write(
            "BUILD",
            """
        genrule(
            name = "data_a",
            srcs = [],
            outs = ["a.bin"],
            cmd = "dd if=/dev/zero bs=1M count=3 2>/dev/null | tr '\\0' 'A' > ${'$'}@",
        )
        genrule(
            name = "data_b",
            srcs = [],
            outs = ["b.bin"],
            cmd = "dd if=/dev/zero bs=1M count=4 2>/dev/null | tr '\\0' 'B' > ${'$'}@",
        )
        genrule(
            name = "combined",
            srcs = [":a.bin", ":b.bin"],
            outs = ["combined.bin"],
            cmd = "cat ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )

        buildTarget("//:combined")

        val outputA: Path = getOutputPath("a.bin")
        val outputB: Path = getOutputPath("b.bin")
        val outputCombined: Path = getOutputPath("combined.bin")
        assertThat(outputA.exists()).isTrue()
        assertThat(outputB.exists()).isTrue()
        assertThat(outputCombined.exists()).isTrue()

        val contentA = readFileBytes(outputA)
        val contentB = readFileBytes(outputB)
        val contentCombined = readFileBytes(outputCombined)
        Truth.assertThat(contentA.size).isEqualTo(3 * 1024 * 1024)
        Truth.assertThat(contentB.size).isEqualTo(4 * 1024 * 1024)
        Truth.assertThat(contentCombined.size).isEqualTo(7 * 1024 * 1024)

        // Clean and rebuild from cache.
        outputA.delete()
        outputB.delete()
        outputCombined.delete()
        cleanAndRestartServer()

        buildTarget("//:combined")

        Truth.assertThat(readFileBytes(outputA)).isEqualTo(contentA)
        Truth.assertThat(readFileBytes(outputB)).isEqualTo(contentB)
        Truth.assertThat(readFileBytes(outputCombined)).isEqualTo(contentCombined)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildWithChunking_smallFile_succeeds() {
        write(
            "BUILD",
            """
        genrule(
            name = "small_file",
            srcs = [],
            outs = ["small.txt"],
            cmd = "echo 'hello world' > ${'$'}@",
        )
        
        """.trimIndent()
        )

        buildTarget("//:small_file")

        val output: Path = getOutputPath("small.txt")
        assertThat(output.exists()).isTrue()
        assertThat(readContent(output, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("hello world\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mixedSizes_largeAndSmallOutputs_allSucceed() {
        write(
            "BUILD",
            """
        genrule(
            name = "large",
            srcs = [],
            outs = ["large.bin"],
            cmd = "dd if=/dev/zero bs=1M count=3 2>/dev/null | tr '\\0' 'X' > ${'$'}@",
        )
        genrule(
            name = "small",
            srcs = [],
            outs = ["small.txt"],
            cmd = "echo 'small output' > ${'$'}@",
        )
        
        """.trimIndent()
        )

        buildTarget("//:large", "//:small")

        val largePath: Path = getOutputPath("large.bin")
        val smallPath: Path = getOutputPath("small.txt")
        val largeContent = readFileBytes(largePath)
        Truth.assertThat(largeContent.size).isEqualTo(3 * 1024 * 1024)
        assertThat(readContent(smallPath, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("small output\n")

        // Clean and rebuild.
        largePath.delete()
        smallPath.delete()
        cleanAndRestartServer()

        buildTarget("//:large", "//:small")

        Truth.assertThat(readFileBytes(largePath)).isEqualTo(largeContent)
        assertThat(readContent(smallPath, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("small output\n")
    }

    companion object {
        @ClassRule
        @org.junit.Rule
        val worker: WorkerInstance = createWorker()
    }
}
