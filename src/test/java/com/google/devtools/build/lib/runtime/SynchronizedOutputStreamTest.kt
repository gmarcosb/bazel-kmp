// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.ScratchAttributeWriter.write
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase.write
import org.junit.Ignore
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import java.io.IOException

/**
 * Tests for [SynchronizedOutputStream].
 * 
 * 
 * Note that, at the time of writing, these tests serve to document the actual behavior of the
 * class, not necessarily the desired behavior.
 */
@RunWith(JUnit4::class)
class SynchronizedOutputStreamTest {
    @org.junit.Test
    @Throws(IOException::class)
    fun testReadAndResetReturnsChunkedWritesSinceLastCall() {
        val underTest: SynchronizedOutputStream =
            SynchronizedOutputStream( /* maxBufferedLength= */
                5,  /* maxChunkSize= */5,  /* isStderr= */false
            )

        val mockStreamer: BuildEventStreamer? = Mockito.mock<BuildEventStreamer?>(BuildEventStreamer::class.java)
        Mockito.doAnswer(Answer { inv: InvocationOnMock? -> true }).`when`<Any?>(mockStreamer)
            .canBufferProgressWrite(false)
        underTest.registerStreamer(mockStreamer)

        underTest.write(
            byteArrayOf(
                'a'.code.toByte(),
                'b'.code.toByte(),
                'c'.code.toByte(),
                'd'.code.toByte(),
                'e'.code.toByte(),
                'f'.code.toByte(),
                'g'.code.toByte(),
                'h'.code.toByte()
            )
        )
        assertThat(underTest.readAndReset()).containsExactly("abcde", "fgh").inOrder()

        assertThat(underTest.readAndReset()).isEmpty()

        underTest.write(byteArrayOf('i'.code.toByte(), 'j'.code.toByte(), 'k'.code.toByte()))
        assertThat(underTest.readAndReset()).containsExactly("ijk")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testWriteFlushesStreamerWhenMaxBufferedLengthReached() {
        val underTest: SynchronizedOutputStream =
            SynchronizedOutputStream( /* maxBufferedLength= */
                3,  /* maxChunkSize= */3,  /* isStderr= */false
            )

        val writes: MutableList<MutableList<String?>?> = java.util.ArrayList<MutableList<String?>?>()
        val mockStreamer: BuildEventStreamer? = Mockito.mock<BuildEventStreamer?>(BuildEventStreamer::class.java)
        Mockito.doAnswer(
            Answer { inv: InvocationOnMock? ->
                writes.add(com.google.common.collect.ImmutableList.copyOf(underTest.readAndReset()))
                null
            })
            .`when`<Any?>(mockStreamer)
            .flush()
        Mockito.doAnswer(Answer { inv: InvocationOnMock? -> true }).`when`<Any?>(mockStreamer)
            .canBufferProgressWrite(false)
        underTest.registerStreamer(mockStreamer)

        underTest.write(
            byteArrayOf(
                'a'.code.toByte(),
                'b'.code.toByte(),
                'c'.code.toByte(),
                'd'.code.toByte(),
                'e'.code.toByte(),
                'f'.code.toByte(),
                'g'.code.toByte(),
                'h'.code.toByte()
            )
        )
        underTest.write(byteArrayOf('i'.code.toByte(), 'j'.code.toByte()))
        underTest.write(byteArrayOf('k'.code.toByte(), 'l'.code.toByte()))

        Truth.assertThat(writes)
            .containsExactly(
                com.google.common.collect.ImmutableList.of<String?>(
                    "abc",
                    "def",
                    "gh"
                ),  // The write of {'k', 'j'} would have put the buffer over size, so {'i', 'j'} was
                // flushed.
                com.google.common.collect.ImmutableList.of<String?>("ij")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testUsesMaxOfMaxBufferedSizeAndMaxChunkSizeForChunking() {
        val underTest: SynchronizedOutputStream =
            SynchronizedOutputStream( /* maxBufferedLength= */
                2,  /* maxChunkSize= */1,  /* isStderr= */false
            )

        val mockStreamer: BuildEventStreamer? = Mockito.mock<BuildEventStreamer?>(BuildEventStreamer::class.java)
        Mockito.doAnswer(Answer { inv: InvocationOnMock? -> true }).`when`<Any?>(mockStreamer)
            .canBufferProgressWrite(false)
        underTest.registerStreamer(mockStreamer)

        underTest.write(byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte()))
        assertThat(underTest.readAndReset()).containsExactly("ab", "cd").inOrder()
    }

    // TODO(b/154242266): Make BES handle binary data correctly.
    @Ignore("b/154242266 - BES doesn't handle binary stdout/err correctly")
    @org.junit.Test
    @Throws(IOException::class)
    fun testHandlesArbitraryBinaryDataCorrectly() {
        val underTest: SynchronizedOutputStream =
            SynchronizedOutputStream( /* maxBufferedLength= */
                1,  /* maxChunkSize= */1,  /* isStderr= */false
            )

        val input = byteArrayOf(0xff.toByte())
        underTest.write(input)

        val result: String? = com.google.common.collect.Iterables.getOnlyElement<T?>(underTest.readAndReset())
        // In the real code the result eventually winds up in a protobuf, which treats strings as utf8.
        Truth.assertThat(result.toByteArray(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(input)
    }
}
