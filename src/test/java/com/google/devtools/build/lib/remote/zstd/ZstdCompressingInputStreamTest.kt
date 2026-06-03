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

/** Tests for [ZstdCompressingInputStream].  */
@RunWith(JUnit4::class)
class ZstdCompressingInputStreamTest {
    @org.junit.Test
    @Throws(IOException::class)
    fun compressionWorks() {
        val rand: Random = Random()
        val data = ByteArray(50)
        rand.nextBytes(data)

        val bais: ByteArrayInputStream = ByteArrayInputStream(data)
        ZstdCompressingInputStream(bais).use { zdis ->
            assertThat(Zstd.decompress(com.google.common.io.ByteStreams.toByteArray(zdis), data.size)).isEqualTo(data)
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun streamCanBeCompressedWithMinimumBufferSize() {
        val rand: Random = Random()
        val data = ByteArray(50)
        rand.nextBytes(data)

        val bais: ByteArrayInputStream = ByteArrayInputStream(data)
        ZstdCompressingInputStream(bais, ZstdCompressingInputStream.MIN_BUFFER_SIZE).use { zdis ->
            assertThat(Zstd.decompress(com.google.common.io.ByteStreams.toByteArray(zdis), data.size)).isEqualTo(data)
        }
    }
}
