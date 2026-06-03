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
package com.google.devtools.build.lib.remote.chunking

import build.bazel.remote.execution.v2.Digest

/** Tests for [FastCcdChunker].  */
@RunWith(JUnit4::class)
class FastCdcChunkerTest {
    @org.junit.Test
    @Throws(IOException::class)
    fun chunkToDigests_emptyInput_returnsEmptyList() {
        val chunker: FastCdcChunker = FastCdcChunker(DIGEST_UTIL)

        val digests: MutableList<Digest> = chunker.chunkToDigests(ByteArrayInputStream(ByteArray(0)))

        Truth.assertThat(digests).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun chunkToDigests_smallInput_returnsSingleChunk() {
        val config: ChunkingConfig = ChunkingConfig(1024, 2, 0)
        val chunker: FastCdcChunker = FastCdcChunker(config, DIGEST_UTIL)
        val data = ByteArray(100)
        Random(42).nextBytes(data)

        val digests: MutableList<Digest> = chunker.chunkToDigests(ByteArrayInputStream(data))

        Truth.assertThat(digests).hasSize(1)
        assertThat(digests.get(0).getSizeBytes()).isEqualTo(100)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun chunkToDigests_dataAtMinSize_returnsSingleChunk() {
        val config: ChunkingConfig = ChunkingConfig(1024, 2, 0)
        val chunker: FastCdcChunker = FastCdcChunker(config, DIGEST_UTIL)
        val data = ByteArray(config.minChunkSize())
        Random(42).nextBytes(data)

        val digests: MutableList<Digest> = chunker.chunkToDigests(ByteArrayInputStream(data))

        Truth.assertThat(digests).hasSize(1)
        assertThat(digests.get(0).getSizeBytes()).isEqualTo(config.minChunkSize())
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun chunkToDigests_largeInput_producesMultipleChunks() {
        val config: ChunkingConfig = ChunkingConfig(1024, 2, 0)
        val chunker: FastCdcChunker = FastCdcChunker(config, DIGEST_UTIL)
        val data = ByteArray(config.maxChunkSize() * 3)
        Random(42).nextBytes(data)

        val digests: MutableList<Digest> = chunker.chunkToDigests(ByteArrayInputStream(data))

        Truth.assertThat(digests.size).isGreaterThan(1)
        val totalSize: Long = digests.stream().mapToLong(Digest::getSizeBytes).sum()
        Truth.assertThat(totalSize).isEqualTo(data.size)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun chunkToDigests_sameInputProducesSameChunks() {
        val chunker: FastCdcChunker = FastCdcChunker(DIGEST_UTIL)
        val data = ByteArray(2 * 1024 * 1024)
        Random(123).nextBytes(data)

        val digests1: MutableList<Digest> = chunker.chunkToDigests(ByteArrayInputStream(data))
        val digests2: MutableList<Digest> = chunker.chunkToDigests(ByteArrayInputStream(data))

        Truth.assertThat(digests1).isEqualTo(digests2)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun chunkToDigests_chunkSizesWithinBounds() {
        val config: ChunkingConfig = ChunkingConfig(1024, 2, 0)
        val chunker: FastCdcChunker = FastCdcChunker(config, DIGEST_UTIL)
        val data = ByteArray(config.maxChunkSize() * 10)
        Random(42).nextBytes(data)

        val digests: MutableList<Digest> = chunker.chunkToDigests(ByteArrayInputStream(data))

        for (i in 0..<digests.size - 1) {
            val size: Long = digests.get(i).getSizeBytes()
            Truth.assertThat(size).isAtLeast(config.minChunkSize())
            Truth.assertThat(size).isAtMost(config.maxChunkSize())
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun chunkToDigests_lastChunkCanBeSmallerThanMin() {
        val config: ChunkingConfig = ChunkingConfig(1024, 2, 0)
        val chunker: FastCdcChunker = FastCdcChunker(config, DIGEST_UTIL)
        val dataSize: Int = config.maxChunkSize() + config.minChunkSize() / 2
        val data = ByteArray(dataSize)
        Random(42).nextBytes(data)

        val digests: MutableList<Digest> = chunker.chunkToDigests(ByteArrayInputStream(data))

        Truth.assertThat(digests.size).isAtLeast(1)
        val totalSize: Long = digests.stream().mapToLong(Digest::getSizeBytes).sum()
        Truth.assertThat(totalSize).isEqualTo(dataSize)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun chunkToDigests_digestsAreCorrect() {
        val config: ChunkingConfig = ChunkingConfig(1024, 2, 0)
        val chunker: FastCdcChunker = FastCdcChunker(config, DIGEST_UTIL)
        val data = ByteArray(500)
        Random(42).nextBytes(data)

        val digests: MutableList<Digest> = chunker.chunkToDigests(ByteArrayInputStream(data))

        Truth.assertThat(digests).hasSize(1)
        val expected: Digest? = DIGEST_UTIL.compute(data)
        assertThat(digests.get(0)).isEqualTo(expected)
    }

    @org.junit.Test
    fun constructor_invalidMinSize_throws() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { FastCdcChunker(0, 1024, 4096, 2, 0, DIGEST_UTIL) })
    }

    @org.junit.Test
    fun constructor_avgSizeLessThanMinSize_throws() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { FastCdcChunker(1024, 512, 4096, 2, 0, DIGEST_UTIL) })
    }

    @org.junit.Test
    fun constructor_maxSizeLessThanAvgSize_throws() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { FastCdcChunker(256, 1024, 512, 2, 0, DIGEST_UTIL) })
    }

    @org.junit.Test
    fun constructor_avgSizeNotPowerOfTwo_throws() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { FastCdcChunker(256, 1000, 4096, 2, 0, DIGEST_UTIL) })
    }

    @org.junit.Test
    fun constructor_invalidNormalization_throws() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { FastCdcChunker(256, 1024, 4096, 4, 0, DIGEST_UTIL) })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun chunkToDigests_withDefaultConfig() {
        val chunker: FastCdcChunker = FastCdcChunker(DIGEST_UTIL)
        val data = ByteArray(4 * 1024 * 1024)
        Random(42).nextBytes(data)

        val digests: MutableList<Digest> = chunker.chunkToDigests(ByteArrayInputStream(data))

        Truth.assertThat(digests.size).isGreaterThan(1)
        val totalSize: Long = digests.stream().mapToLong(Digest::getSizeBytes).sum()
        Truth.assertThat(totalSize).isEqualTo(data.size)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun chunkToDigests_testVectorsSeed0() {
        verifyTestVectors(
            0,
            arrayOf<IntArray?>(
                intArrayOf(0, 19186),
                intArrayOf(19186, 19279),
                intArrayOf(38465, 17354),
                intArrayOf(55819, 16387),
                intArrayOf(72206, 19940),
                intArrayOf(92146, 17320),
            ),
            arrayOf<String>(
                "0f9efa589121d5d9e9e2c4ace91337d77cae866537143f6f15a0ffd525a77c2d",
                "c7c86a165573c16448cda35c9169742e85645af42be22889f8b96b8ee0ec7cb0",
                "bc88521e28a8b4479cdea5f75aa721a24f3a0a7d0be903aa6d505c574e51e89d",
                "4b8dac2652e4685c629d2bb1ae9d4448e676b86f2e67ca0b2fff3d9580184b79",
                "c0a7062da6f2386c28e086ee0cedd5732252741269838773cff1ddb05b2df6ed",
                "7fa5b12134dc75cd2ac8dc60d3a8f3c8d22f0ee9d4cf74a4aa937e2a0d2d79a5",
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun chunkToDigests_testVectorsSeed666() {
        verifyTestVectors(
            666,
            arrayOf<IntArray?>(
                intArrayOf(0, 17635),
                intArrayOf(17635, 17334),
                intArrayOf(34969, 19136),
                intArrayOf(54105, 17467),
                intArrayOf(71572, 23593),
                intArrayOf(95165, 14301),
            ),
            arrayOf<String>(
                "cb3a9d80a3569772d4ed331ca37ab0c862c759897b890fc1aac90a4f2ea3a407",
                "d758c6b7b0b7eef1e996f8ccd17de6c645360b03a26c35541e7581348ac08944",
                "24846aefd89e510594bae3e9d7d5ea5012067601512610fed126a3c57ba993f5",
                "efa785e1fefb49f190e665f72fd246c1442079874508c312196da1fb3040d00b",
                "a2f557bdd8d40d8faada963ad5f91ec54b10ccee7c5ae72754a65137592dc607",
                "e131100b4a7147ccad19dc63c4a2fac1f5d8b644e1373eeb6803825024234efc",
            )
        )
    }

    // Test vectors from the Remote Execution API specification:
    // https://github.com/bazelbuild/remote-apis/blob/v2.12.0/build/bazel/remote/execution/v2/fastcdc2020_test_vectors.txt
    // Test image: "Akashita" by Toriyama Sekien (1712-1788), public domain.
    // Source: https://commons.wikimedia.org/wiki/File:SekienAkashita.jpg
    @Throws(java.lang.Exception::class)
    private fun verifyTestVectors(seed: Long, expectedChunks: Array<IntArray?>, expectedHashes: Array<String?>) {
        val testVectorPath: Path =
            Path.of(Runfiles.preload().withSourceRepository("").rlocation(TEST_VECTOR_PATH))
        val fileData: ByteArray = java.nio.file.Files.readAllBytes(testVectorPath)

        val chunker: FastCdcChunker = FastCdcChunker(4096, 16384, 65535, 2, seed, DIGEST_UTIL)
        val digests: MutableList<Digest>?
        ByteArrayInputStream(fileData).use { input ->
            digests = chunker.chunkToDigests(input)
        }
        Truth.assertThat(digests).hasSize(expectedChunks.size)

        val actualChunks: MutableList<IntArray?> = java.util.ArrayList<IntArray?>()
        var offset = 0
        for (digest in digests!!) {
            actualChunks.add(intArrayOf(offset, digest.getSizeBytes() as Int))
            offset += digest.getSizeBytes() as Int
        }

        for (i in expectedChunks.indices) {
            Truth.assertThat(actualChunks.get(i)!![0]).isEqualTo(expectedChunks[i]!![0])
            Truth.assertThat(actualChunks.get(i)!![1]).isEqualTo(expectedChunks[i]!![1])

            val chunkData = ByteArray(expectedChunks[i]!![1])
            java.lang.System.arraycopy(fileData, expectedChunks[i]!![0], chunkData, 0, chunkData.size)
            val chunkHash = com.google.common.hash.Hashing.sha256().hashBytes(chunkData).toString()
            Truth.assertThat(chunkHash).isEqualTo(expectedHashes[i])
        }
    }

    companion object {
        private const val TEST_VECTOR_PATH =
            "io_bazel/src/test/java/com/google/devtools/build/lib/remote/chunking/testdata/SekienAkashita.jpg"

        private val DIGEST_UTIL: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)
    }
}
