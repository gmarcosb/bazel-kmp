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
package com.google.devtools.build.lib.remote.zstd

import com.github.luben.zstd.Zstd

/** Tests for [ZstdDecompressingOutputStream].  */
@RunWith(JUnit4::class)
class ZstdDecompressingOutputStreamTest {
    @org.junit.Test
    @Throws(IOException::class)
    fun decompressionWorks() {
        val rand: Random = Random()
        val data = ByteArray(50)
        rand.nextBytes(data)
        val compressed: ByteArray = Zstd.compress(data)

        val baos: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        ZstdDecompressingOutputStream(baos).use { zdos ->
            zdos.write(compressed)
            zdos.flush()
        }
        Truth.assertThat(baos.toByteArray()).isEqualTo(data)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun streamCanBeDecompressedOneByteAtATime() {
        val rand: Random = Random()
        val data = ByteArray(50)
        rand.nextBytes(data)
        val compressed: ByteArray = Zstd.compress(data)

        val baos: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        ZstdDecompressingOutputStream(baos).use { zdos ->
            for (b in compressed) {
                zdos.write(b.toInt())
            }
            zdos.flush()
        }
        Truth.assertThat(baos.toByteArray()).isEqualTo(data)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun bytesWrittenMatchesDecompressedBytes() {
        val data: ByteArray =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".getBytes(java.nio.charset.StandardCharsets.UTF_8)

        val compressed: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        ZstdOutputStream(compressed).use { zos ->
            zos.setCloseFrameOnFlush(true)
            for (i in data.indices) {
                zos.write(data[i])
                if (i % 5 == 0) {
                    // Create multiple frames of 5 bytes each.
                    zos.flush()
                }
            }
        }
        val decompressed: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        ZstdDecompressingOutputStream(decompressed).use { zdos ->
            for (b in compressed.toByteArray()) {
                zdos.write(b.toInt())
                zdos.flush()
            }
        }
        Truth.assertThat(decompressed.toByteArray()).isEqualTo(data)
    }
}
