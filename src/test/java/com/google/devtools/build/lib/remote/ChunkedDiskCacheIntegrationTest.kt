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

import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialModule

/**
 * Integration tests for chunked remote cache with a combined disk + remote cache.
 * 
 * 
 * Verifies that chunks downloaded from the remote cache are properly captured to disk cache, and
 * that subsequent builds can serve chunks from disk cache without hitting the remote.
 */
@RunWith(JUnit4::class)
class ChunkedDiskCacheIntegrationTest : BuildIntegrationTestCase() {
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
            "--disk_cache=" + diskCacheDir,
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
    fun tearDown() {
        runtimeWrapper.newCommand()
        getWorkspace().getFileSystem().getPath(diskCacheDir).deleteTree()
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

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun largeBlob_uploadedAndDownloaded_throughDiskAndRemoteCache() {
        write(
            "BUILD",
            """
        genrule(
            name = "large_file",
            srcs = [],
            outs = ["large.bin"],
            cmd = "dd if=/dev/zero bs=1M count=3 2>/dev/null | tr '\\0' 'D' > ${'$'}@",
        )
        
        """.trimIndent()
        )

        // First build: generates the file, uploads chunks to remote + disk cache.
        buildTarget("//:large_file")

        val output: Path = getOutputPath("large.bin")
        assertThat(output.exists()).isTrue()
        val originalContent = readFileBytes(output)
        Truth.assertThat(originalContent.size).isEqualTo(3 * 1024 * 1024)

        // Second build: clean outputs + action cache, rebuild.
        // Chunks should be served from disk cache (populated during first build's download capture).
        output.delete()
        cleanAndRestartServer()

        buildTarget("//:large_file")

        assertThat(output.exists()).isTrue()
        Truth.assertThat(readFileBytes(output)).isEqualTo(originalContent)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun largeBlob_diskCasDeleted_rebuildFromRemote() {
        write(
            "BUILD",
            """
        genrule(
            name = "large_file",
            srcs = [],
            outs = ["large.bin"],
            cmd = "dd if=/dev/zero bs=1M count=3 2>/dev/null | tr '\\0' 'E' > ${'$'}@",
        )
        
        """.trimIndent()
        )

        // First build: populates both caches.
        buildTarget("//:large_file")

        val output: Path = getOutputPath("large.bin")
        val originalContent = readFileBytes(output)

        // Delete disk cache CAS entries (simulate cache eviction).
        val diskCasCas: Path = getWorkspace().getFileSystem().getPath(diskCacheDir.getRelative("cas"))
        if (diskCasCas.exists()) {
            diskCasCas.deleteTree()
        }

        // Clean outputs + action cache, rebuild.
        // Should fall back to remote cache since disk CAS is gone.
        output.delete()
        cleanAndRestartServer()

        buildTarget("//:large_file")

        assertThat(output.exists()).isTrue()
        Truth.assertThat(readFileBytes(output)).isEqualTo(originalContent)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleTargets_withDiskCache_allSucceed() {
        write(
            "BUILD",
            """
        genrule(
            name = "data_a",
            srcs = [],
            outs = ["a.bin"],
            cmd = "dd if=/dev/zero bs=1M count=3 2>/dev/null | tr '\\0' 'F' > ${'$'}@",
        )
        genrule(
            name = "data_b",
            srcs = [],
            outs = ["b.bin"],
            cmd = "dd if=/dev/zero bs=1M count=4 2>/dev/null | tr '\\0' 'G' > ${'$'}@",
        )
        genrule(
            name = "combined",
            srcs = [":a.bin", ":b.bin"],
            outs = ["combined.bin"],
            cmd = "cat ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )

        buildTarget("//:data_a", "//:data_b", "//:combined")

        val outputA: Path = getOutputPath("a.bin")
        val outputB: Path = getOutputPath("b.bin")
        val outputCombined: Path = getOutputPath("combined.bin")
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

        buildTarget("//:data_a", "//:data_b", "//:combined")

        Truth.assertThat(readFileBytes(outputA)).isEqualTo(contentA)
        Truth.assertThat(readFileBytes(outputB)).isEqualTo(contentB)
        Truth.assertThat(readFileBytes(outputCombined)).isEqualTo(contentCombined)
    }

    companion object {
        @ClassRule
        @org.junit.Rule
        val worker: WorkerInstance = createWorker()

        private val diskCacheDir: PathFragment
            get() = PathFragment.create(com.google.devtools.build.lib.testutil.TestUtils.tmpDirFile().getAbsolutePath())
                .getRelative("chunked_disk_cache")
    }
}
