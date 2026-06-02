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
import com.google.common.base.Preconditions
import java.io.InputStream
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/**
 * FastCDC 2020 implementation for splitting large blobs.
 * 
 * 
 * This module implements the canonical FastCDC algorithm as described in the
 * [paper](https://ieeexplore.ieee.org/document/9055082) by Wen Xia, et al., in 2020.
 */
class FastCdcChunker(minSize: Int, avgSize: Int, maxSize: Int, normalization: Int, seed: Long, digestUtil: DigestUtil) {
    private val minSize: Int
    private val maxSize: Int
    private val avgSize: Int
    private val maskS: Long
    private val maskL: Long
    private val maskSLs: Long
    private val maskLLs: Long
    private val seed: Long
    private val shiftedSeed: Long
    private val digestUtil: DigestUtil

    constructor(digestUtil: DigestUtil) : this(ChunkingConfig.Companion.defaults(), digestUtil)

    constructor(config: ChunkingConfig, digestUtil: DigestUtil) : this(
        config.minChunkSize(),
        config.avgChunkSize,
        config.maxChunkSize(),
        config.normalizationLevel,
        Integer.toUnsignedLong(config.seed),
        digestUtil
    )

    init {
        Preconditions.checkArgument(minSize > 0, "minSize must be positive")
        Preconditions.checkArgument(avgSize >= minSize, "avgSize must be >= minSize")
        Preconditions.checkArgument(maxSize >= avgSize, "maxSize must be >= avgSize")
        Preconditions.checkArgument((avgSize and (avgSize - 1)) == 0, "avgSize must be a power of 2, got %s", avgSize)
        Preconditions.checkArgument(normalization >= 0 && normalization <= 3, "normalization must be 0-3")

        this.minSize = minSize
        this.avgSize = avgSize
        this.maxSize = maxSize
        this.digestUtil = digestUtil

        val bits = 31 - Integer.numberOfLeadingZeros(avgSize)
        val smallBits = bits + normalization
        val largeBits = bits - normalization
        Preconditions.checkArgument(smallBits <= 25 && largeBits >= 5, "normalization level too extreme for avgSize")

        this.maskS = MASKS[smallBits]
        this.maskL = MASKS[largeBits]
        this.maskSLs = this.maskS shl 1
        this.maskLLs = this.maskL shl 1

        this.seed = seed
        this.shiftedSeed = seed shl 1
    }

    /** Finds the next chunk boundary.  */
    private fun cut(buf: ByteArray, off: Int, len: Int): Int {
        if (len <= minSize) {
            return len
        }

        val n = Math.min(len, maxSize)
        val center = Math.min(n, avgSize)

        // Round down to even boundaries for 2-byte processing so we don't need to
        // divide by 2 in the loop.
        val minLimit = minSize and 1.inv()
        val centerLimit = center and 1.inv()
        val remainingLimit = n and 1.inv()

        val s = this.seed
        val sLs = this.shiftedSeed
        var hash: Long = 0

        // Below avgSize: use maskS to discourage early cuts (too small chunks)
        run {
            var a = minLimit
            while (a < centerLimit) {
                hash = (hash shl 2) + (GEAR_LS[buf[off + a].toInt() and 0xFF] xor sLs)
                if ((hash and maskSLs) == 0L) {
                    return a
                }
                hash = hash + (GEAR[buf[off + a + 1].toInt() and 0xFF] xor s)
                if ((hash and maskS) == 0L) {
                    return a + 1
                }
                a += 2
            }
        }

        // Above avgSize: use maskL to encourage cuts (too large chunks)
        var a = centerLimit
        while (a < remainingLimit) {
            hash = (hash shl 2) + (GEAR_LS[buf[off + a].toInt() and 0xFF] xor sLs)
            if ((hash and maskLLs) == 0L) {
                return a
            }
            hash = hash + (GEAR[buf[off + a + 1].toInt() and 0xFF] xor s)
            if ((hash and maskL) == 0L) {
                return a + 1
            }
            a += 2
        }

        return n
    }

    /**
     * Chunks a file and returns chunk digests.
     * 
     * 
     * This method is used for building MerkleTree entries for large files. It returns the content
     * digests in order for each chunk.
     * 
     * 
     * Note: We don't need the raw data here. We can read from the original file (seekable) when
     * uploading, similar to how whole blobs work.
     */
    @Throws(IOException::class)
    fun chunkToDigests(input: InputStream): MutableList<Digest?> {
        val digests: MutableList<Digest?> = ArrayList<Digest?>()

        val buf = ByteArray(maxSize * 2)
        var cursor = 0
        var end = 0
        var eof = false

        while (true) {
            var available = end - cursor
            if (available < maxSize && !eof) {
                if (cursor > 0 && available > 0) {
                    System.arraycopy(buf, cursor, buf, 0, available)
                }
                cursor = 0
                end = available

                while (end < buf.size) {
                    val n = input.read(buf, end, buf.size - end)
                    if (n == -1) {
                        eof = true
                        break
                    }
                    end += n
                }
                available = end - cursor
            }

            if (available == 0) {
                break
            }

            val chunkLen = cut(buf, cursor, available)
            digests.add(digestUtil.compute(buf, cursor, chunkLen))

            cursor += chunkLen
        }

        return digests
    }

    companion object {
        // Masks for each of the desired number of bits, where 0 through 5 are unused.
        // The values for sizes 64 bytes through 128 kilo-bytes come from the C
        // reference implementation (found in the destor repository) while the extra
        // values come from the restic-FastCDC repository. The FastCDC paper claims that
        // the deduplication ratio is slightly improved when the mask bits are spread
        // relatively evenly, hence these seemingly "magic" values.
        // @formatter:off
        private val MASKS = longArrayOf(0,  // 0: padding
        0,  // 1: padding
        0,  // 2: padding
        0,  // 3: padding
        0,  // 4: padding
        0x0000000001804110L,  // 5: unused except for NC 3
        0x0000000001803110L,  // 6: 64B
        0x0000000018035100L,  // 7: 128B
        0x0000001800035300L,  // 8: 256B
        0x0000019000353000L,  // 9: 512B
        0x0000590003530000L,  // 10: 1KB
        0x0000d90003530000L,  // 11: 2KB
        0x0000d90103530000L,  // 12: 4KB
        0x0000d90303530000L,  // 13: 8KB
        0x0000d90313530000L,  // 14: 16KB
        0x0000d90f03530000L,  // 15: 32KB
        0x0000d90303537000L,  // 16: 64KB
        0x0000d90703537000L,  // 17: 128KB
        0x0000d90707537000L,  // 18: 256KB
        0x0000d91707537000L,  // 19: 512KB
        0x0000d91747537000L,  // 20: 1MB
        0x0000d91767537000L,  // 21: 2MB
        0x0000d93767537000L,  // 22: 4MB
        0x0000d93777537000L,  // 23: 8MB
        0x0000d93777577000L,  // 24: 16MB
        0x0000db3777577000L,  // 25: unused except for NC 3
        )
        
         // GEAR contains seemingly random numbers which are created by computing the MD5 digest of values
 // from 0 to 255, using only the high 8 bytes of the 16-byte digest. This is the "gear hash"
 // referred to in the FastCDC paper.
        private val GEAR = longArrayOf(0x3b5d3c7d207e37dcL, 0x784d68ba91123086L, -0x32ad77f077d18d68L, -0x153071b1e6023359L, 
        -0x3ce0c7a2042e9cd5L, 0x1d5f27001e25abe6L, -0x7cecf421c365266fL, -0x3b4dda98916489b7L, 
        -0x55cd64d61f714b67L, -0x4980342de1a882a8L, 0x0027baaada2acf6bL, -0x1c10d2a538c3dddaL, 
        0x0890f24d6ed312b7L, -0x57f61fc97ae28382L, -0xf5901a1ffec27e5L, 0x1d026304452cec14L, 
        0x03864632648e248fL, -0x325530c2326d464cL, -0xa1fed19c3e787aaL, -0x779d062c7de3ff4aL, 
        -0x57d08cc78af09076L, 0x1e583dc6c1cb0b6fL, 0x7a3145b69743a7f1L, -0x544df011bfb7f815L, 
        -0x4eb4c301f847c5a3L, -0x4623d876752465f1L, 0x3703f5e91baa62beL, -0x30f447997ea08268L, 
        0x3d9867c41ea9dcd3L, 0x1be1fa65442bf22cL, 0x14300da4c55631d9L, -0x1967163439aba367L, 
        0x4763107ec64e92a5L, -0x39a7de039a9695dcL, 0x76196c064822f0b7L, 0x485be841f3525e01L, 
        -0x9ad43637a68b00bL, -0x3527cad053161c17L, 0x2a6ed1dceb35e98eL, -0x390b7c4523ee97f1L, 
        0x3cfd8c17e9cf12f1L, -0x7647c3a1d15a9b8fL, -0x5199a302db1c6d57L, -0x13cc3b1afb3476ebL, 
        0x3fb9b15fc9fe7451L, -0x2802e02e6ba0de6bL, 0x31ade0853443efd8L, 0x255efc9863e1e2d2L, 
        0x10eab6008d5642cfL, 0x46f04863257ac804L, -0x5ad23bd58765d82dL, -0x2555206318850a9bL, 
        0x6b479cd53d87febbL, 0x6309e2d3f93db72fL, -0x3a8c700455e0062aL, 0x6bd57f3f25af7968L, 
        0x67605486d90d0a4aL, -0x1eb2f4699c404252L, -0x48442727e914fbecL, -0x21075b0e94ca5eeaL, 
        -0x186cd27a5550012aL, 0x08161cbae90cfd48L, -0x7aaaf8414d6b0f75L, -0x6edcb159002c664eL, 
        -0x528f30b4dbca0cfeL, -0x2d76568a9a43d2d9L, -0x71aa7bc800356622L, -0x692d8fb48eea3fc0L, 
        0x0889bbcdfc660e41L, 0x5e0d4e67dc92128dL, 0x72a9f8917063ed97L, 0x438b69d409e016e3L, 
        -0x20b01275a275bc69L, 0x00f41dcf41d403f7L, 0x4814eb038e52603fL, -0x62504533a71d29afL, 
        -0x1d0ba71b41e8f51L, 0x4457ec414df6a940L, 0x06e62f1451123314L, -0x42efeb2e8c456d34L, 
        -0x210ce71da12a88a0L, -0x6015f21620357adbL, 0x459de1e76c20624bL, -0x5113e769e81d299aL, 
        0x126a2c06ab5a83cbL, -0x4ecdeacdc9f09eceL, 0x65421503dbb40123L, 0x2d67c287ea089ab3L, 
        0x6c93bff5a56bd6b6L, 0x4ffb2036cab6d98dL, -0x318487a4e41852b1L, -0x124bd109e7602e9dL, 
        -0x236fad778fc6770aL, 0x365f9c1d2c691884L, -0x39bfa7c97f266402L, 0x3cd4624c07593ec6L, 
        0x7f1ea8d85d7c5805L, 0x014842d480b57149L, 0x0b649bcb5a828688L, -0x432a8f712864e710L, 
        -0x1678379d042d0d10L, -0x67d8ce98e0f327d4L, -0x450ec174e9273f9dL, -0x715cef63426ae446L, 
        -0x2ebefba404c7a353L, 0x2acbc1a0af1f7d30L, -0x19bbb27620fc4021L, -0x5e73388e47e77007L, 
        -0x67cbbd624fe3c645L, 0x214add07fe086a1fL, -0x70f83e64e094c007L, 0x56a297b1bf4ffe55L, 
        -0x6b2aa71b6c3ab039L, 0x40bfc24c764552cbL, -0x6ce58f90757adf35L, 0x32229d322935bd52L, 
        0x2560d0f5dc4fefafL, -0x62433b7caa69644aL, 0x0fd81c3985c0b56aL, -0x1fc7e81ea9f0d426L, 
        -0x3e44b07e276d4d2bL, -0x4f3b79b0b1d72d29L, 0x3ecc49f9d9d6c263L, 0x51307e99b52ba65eL, 
        -0x750d4977257b58aeL, -0xa28dadc46e4df4aL, 0x6d95ff1ff4634806L, 0x562f21555458339aL, 
        -0x3f31b80776cc9cbaL, 0x487823e5089b40d8L, -0x1b8d838143926a6eL, 0x5a8f7277e94970baL, 
        -0x35d0bf94e3744b0L, 0x5b1f8a95f1791070L, -0x2cfb506036fd79fbL, 0x5440ab7fc930e748L, 
        0x312d25fbca2ab5a1L, 0x10f4a4b234a4d575L, -0x6fcfe2aafb818b8dL, 0x3b6372886c61591eL, 
        0x293402b77c444e06L, 0x451f34a4d3e97dd7L, 0x3158d814d81bc57bL, 0x034942425b9bda69L, 
        -0x1dfcd0061acd2645L, 0x62ae066b8b2179e5L, -0x6aba1ef3d0728e28L, 0x7ff7483eb2d23fc0L, 
        0x00945fcebdc98d86L, -0x789b4441664d935eL, 0x1b1ec62284c0bfc3L, 0x58e0fcc4f0aa362bL, 
        0x5f4abefa878d458dL, -0x28b53d069f83ae7L, -0x5b1c04c820734057L, -0x409681bc353a8b1bL, 
        -0x790eb5c0970b32adL, 0x24a23d076f1ce522L, -0x18da327fb7797338L, -0x40c38d614db9bc9eL, 
        -0x270932a84c33e128L, 0x6329e52425541577L, 0x62aa688ad5ae1ac0L, 0x0a242566269bf845L, 
        0x168b1a4753aca74bL, -0x876501000d181c4L, 0x6c3362093b6fccdbL, 0x4ce8f50bd28c09b2L, 
        0x006a2db95ae8aa93L, -0x68a4f29dc3c2e574L, 0x18605d3935338c5bL, 0x5bb6f6136cad3c71L, 
        0x0f53a20701f8d8a6L, -0x5473a52d1816c399L, 0x40b5ac5127acaa29L, -0x738409c3df8a76a1L, 
        0x78bd9f7e014a805cL, -0x4d36160b06373fceL, -0x1029fb67d8146e0dL, 0x2be459f482c16fbdL, 
        -0x26d31f3a8ba55574L, 0x0aaa8fb298d965b9L, 0x2b37f92c6c803b15L, -0x73ab5a16b1f0f188L, 
        -0x6a064916f3f5cfceL, -0x186c6055bc93878cL, -0x2e9401709575bf37L, 0x44982b86263fd2faL, 
        -0x1d7a04c6067b1a7dL, 0x779a8df72d7619d3L, -0xd286572172a22e2L, -0x2efc8cab29997b1eL, 
        0x004c82a4e668a8e5L, 0x31d40a7668b044e6L, -0x28fa87ac742fd3efL, -0x24babcef873a0b7eL, 
        -0x688ede448095ae53L, 0x73d5ccbd34eff8ddL, -0x1bc85f82ca91e833L, 0x47b2782043c95627L, 
        -0x604daebec1be2b66L, -0x3328f49f9adaec2dL, 0x1c95b31e8a1b49b2L, -0x3518c202e434b3e5L, 
        0x34d98331b1f5b70fL, 0x784e39f22338d92fL, 0x18613d4a064df420L, -0xe27251da0f43142L, 
        0x33f77c15ae855efcL, 0x3c88b3b912eb109cL, -0x6a95d1369450115bL, 0x1aa005b5e0ad0e87L, 
        0x5500d70527c4bb8eL, -0x1c93a8e69bde33bcL, 0x13c4d286cc36ee39L, 0x5654a23d818b2a81L, 
        0x77b1dc13d161abdcL, 0x734f44de5f8d5eb5L, 0x60717e174a6c89a2L, -0x2b8269b6d995dee2L, 
        0x5b13a4322bb69e90L, -0x89969f6074a03c4L, 0x21e6ac55bedcdac9L, -0x64a949d49ee99216L, 
        -0xb709946c6868164L, 0x35f332f9c0e6ae9aL, -0x338cc09565787250L, 0x3da161e41cc108c2L, 
        -0x4828b51aca6eb2afL, 0x4d493b0b11d36469L, -0x31d9b2e204568be6L, -0x562e0d238bc923faL, 
        0x70738016604c2a27L, 0x231d36e96e93f3d5L, 0x7666881197838d19L, 0x4a2a83090aaad40cL, 
        -0xe189ea6e9974ca3L, 0x7363236497f730a7L, 0x301080e37379dd4dL, 0x502dea2971827042L, 
        -0x3d3a147a70cd9da1L, 0x786afb9edfafbdffL, -0x2511f2797b6f4d5cL, 0x617366b3268609f6L, 
        -0x51f1ca5f01b9e8c2L, -0x2e5f8216c17db0efL, 0x079b8b115ea4cca8L, -0x6c566d8baa705145L, 
        -0x4e191dd1f75fc4dL, -0x159ca0245c967230L, -0x30ac9a6cd7afc5a4L, -0x321c4ce1902a2880L, 
        -0x71c1bdde2c9ebbedL, -0x10eb2f27940e5dd4L, -0x1e27cf2c0e93a225L, -0x5542d4d5baeafb1fL, 
        )
        
         // @formatter:on
         private val GEAR_LS: LongArray = computeGearLs()

        private fun computeGearLs(): LongArray {
            val gearLs = LongArray(GEAR.size)
            for (i in GEAR.indices) {
                gearLs[i] = GEAR[i] shl 1
            }
            return gearLs
        }
    }
}
