// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository.downloader

import com.google.common.io.CharStreams
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache.KeyType
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** Unit tests for [HashInputStream].  */
@RunWith(JUnit4::class)
class HashInputStreamTest {
    @Rule
    val thrown: ExpectedException = ExpectedException.none()

    @Test
    @Throws(Exception::class)
    fun validChecksum_readsOk() {
        InputStreamReader(
            HashInputStream(
                ByteArrayInputStream("hello".toByteArray(StandardCharsets.UTF_8)),
                Checksum.fromString(KeyType.SHA1, "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d")
            ),
            StandardCharsets.UTF_8
        ).use { reader ->
            Truth.assertThat(CharStreams.toString(reader)).isEqualTo("hello")
        }
    }

    @Test
    @Throws(Exception::class)
    fun badChecksum_throwsIOException() {
        thrown.expect(IOException::class.java)
        thrown.expectMessage("Checksum")
        InputStreamReader(
            HashInputStream(
                ByteArrayInputStream("hello".toByteArray(StandardCharsets.UTF_8)),
                Checksum.fromString(KeyType.SHA1, "0000000000000000000000000000000000000000")
            ),
            StandardCharsets.UTF_8
        ).use { reader ->
            Truth.assertThat(CharStreams.toString(reader))
                .isNull() // Only here to make @CheckReturnValue happy.
        }
    }
}
