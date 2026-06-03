// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.github.luben.zstd.Zstd

/** Tests for [Chunker].  */
@RunWith(JUnit4::class)
class ChunkerTest {
    @org.junit.Test
    @Throws(IOException::class)
    fun chunkingShouldWork() {
        val rand: Random = Random()
        val expectedData = ByteArray(21)
        rand.nextBytes(expectedData)

        val chunker: Chunker = Chunker.builder().setInput(expectedData).setChunkSize(10).build()

        val actualData: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()

        assertThat(chunker.hasNext()).isTrue()
        var next: Chunk = chunker.next()
        assertThat(next.getOffset()).isEqualTo(0)
        assertThat(next.getData()).hasSize(10)
        next.getData().writeTo(actualData)

        assertThat(chunker.hasNext()).isTrue()
        next = chunker.next()
        assertThat(next.getOffset()).isEqualTo(10)
        assertThat(next.getData()).hasSize(10)
        next.getData().writeTo(actualData)

        assertThat(chunker.hasNext()).isTrue()
        next = chunker.next()
        assertThat(next.getOffset()).isEqualTo(20)
        assertThat(next.getData()).hasSize(1)
        next.getData().writeTo(actualData)

        assertThat(chunker.hasNext()).isFalse()

        Truth.assertThat(actualData.toByteArray()).isEqualTo(expectedData)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun nextShouldThrowIfNoMoreData() {
        val data = ByteArray(10)
        val chunker: Chunker = Chunker.builder().setInput(data).setChunkSize(10).build()

        assertThat(chunker.hasNext()).isTrue()
        assertThat(chunker.next()).isNotNull()

        assertThat(chunker.hasNext()).isFalse()

        org.junit.Assert.assertThrows<java.util.NoSuchElementException?>(
            java.util.NoSuchElementException::class.java,
            org.junit.function.ThrowingRunnable { chunker.next() })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyData() {
        val inp: ByteArrayInputStream? =
            object : ByteArrayInputStream(ByteArray(0)) {
                private var closed = false

                @Throws(IOException::class)
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        val chunker: Chunker = Chunker.builder().setInput(0, { inp }).build()

        assertThat(chunker.hasNext()).isTrue()

        val next: Chunk = chunker.next()

        assertThat(next).isNotNull()
        assertThat(next.getData()).isEmpty()
        assertThat(next.getOffset()).isEqualTo(0)

        assertThat(chunker.hasNext()).isFalse()
        Truth.assertThat(inp.closed).isTrue()

        org.junit.Assert.assertThrows<java.util.NoSuchElementException?>(
            java.util.NoSuchElementException::class.java,
            org.junit.function.ThrowingRunnable { chunker.next() })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun reset() {
        val data = byteArrayOf(1, 2, 3)
        val chunker: Chunker = Chunker.builder().setInput(data).setChunkSize(1).build()

        assertNextEquals(chunker, 1.toByte())
        assertNextEquals(chunker, 2.toByte())

        chunker.reset()

        assertNextEquals(chunker, 1.toByte())
        assertNextEquals(chunker, 2.toByte())
        assertNextEquals(chunker, 3.toByte())

        chunker.reset()

        assertNextEquals(chunker, 1.toByte())
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun resourcesShouldBeReleased() {
        // Test that after having consumed all data or after reset() is called (whatever happens first)
        // the underlying InputStream should be closed.

        val data = byteArrayOf(1, 2)
        val `in`: AtomicReference<java.io.InputStream?> = AtomicReference<java.io.InputStream?>()
        val supplier: Blob =
            Blob {
                `in`.set(Mockito.spy<ByteArrayInputStream?>(ByteArrayInputStream(data)))
                `in`.get()
            }

        val chunker: Chunker = Chunker(supplier, data.size, 1, false)
        Truth.assertThat(`in`.get()).isNull()
        assertNextEquals(chunker, 1.toByte())
        Mockito.verify<java.io.InputStream?>(`in`.get(), Mockito.never()).close()
        assertNextEquals(chunker, 2.toByte())
        Mockito.verify<java.io.InputStream?>(`in`.get()).close()

        chunker.reset()
        chunker.next()
        chunker.reset()
        Mockito.verify<java.io.InputStream?>(`in`.get()).close()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun seekAfterReset() {
        // Test that seek() works on an uninitialized chunker

        val data = ByteArray(10)
        val chunker: Chunker = Chunker.builder().setInput(data).setChunkSize(10).build()

        chunker.reset()
        chunker.seek(2)

        val next: Chunk = chunker.next()
        assertThat(next).isNotNull()
        assertThat(next.getOffset()).isEqualTo(2)
        assertThat(next.getData()).hasSize(8)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun seekBackwards() {
        val data = ByteArray(10)
        val chunker: Chunker = Chunker.builder().setInput(data).setChunkSize(10).build()

        chunker.seek(4)
        chunker.seek(2)

        val next: Chunk = chunker.next()
        assertThat(next).isNotNull()
        assertThat(next.getOffset()).isEqualTo(2)
        assertThat(next.getData()).hasSize(8)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun seekForwards() {
        val data = ByteArray(10)
        for (i in data.indices) {
            data[i.toInt()] = i
        }
        val chunker: Chunker = Chunker.builder().setInput(data).setChunkSize(2).build()

        var chunk: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = chunker.next()
        assertThat(chunk.getOffset()).isEqualTo(0)
        assertThat(chunk.getData().toByteArray()).isEqualTo(byteArrayOf(0, 1))
        chunker.seek(8)
        chunk = chunker.next()
        assertThat(chunk.getOffset()).isEqualTo(8)
        assertThat(chunk.getData().toByteArray()).isEqualTo(byteArrayOf(8, 9))
        assertThat(chunker.hasNext()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun seekEmptyData() {
        val chunker: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Chunker.builder().setInput(ByteArray(0)).build()
        for (i in 0..1) {
            chunker.seek(0)
            val next: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = chunker.next()
            assertThat(next).isNotNull()
            assertThat(next.getData()).isEmpty()
            assertThat(next.getOffset()).isEqualTo(0)

            assertThat(chunker.hasNext()).isFalse()
            org.junit.Assert.assertThrows<java.util.NoSuchElementException?>(
                java.util.NoSuchElementException::class.java,
                chunker::next
            )
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testSingleChunkCompressed() {
        val data = byteArrayOf(72, 101, 108, 108, 111, 32, 87, 111, 114, 108, 100, 33)
        val chunker: Chunker =
            Chunker.builder().setInput(data).setChunkSize(data.size * 2).setCompressed(true).build()
        val next: Chunk = chunker.next()
        assertThat(chunker.hasNext()).isFalse()
        assertThat(Zstd.decompress(next.getData().toByteArray(), data.size)).isEqualTo(data)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testMultiChunkCompressed() {
        val data = byteArrayOf(72, 101, 108, 108, 111, 32, 87, 111, 114, 108, 100, 33)
        val chunker: Chunker =
            Chunker.builder().setInput(data).setChunkSize(data.size / 2).setCompressed(true).build()

        val baos: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        chunker.next().getData().writeTo(baos)
        assertThat(chunker.hasNext()).isTrue()
        while (chunker.hasNext()) {
            chunker.next().getData().writeTo(baos)
        }
        baos.close()

        assertThat(Zstd.decompress(baos.toByteArray(), data.size)).isEqualTo(data)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testActualSizeIsCorrectAfterSeek() {
        val data = byteArrayOf(72, 101, 108, 108, 111, 32, 87, 111, 114, 108, 100, 33)
        val expectedSizes = intArrayOf(12, 24)
        for (expected in expectedSizes) {
            val chunker: Chunker =
                Chunker.builder()
                    .setInput(data)
                    .setChunkSize(data.size * 2)
                    .setCompressed(expected != data.size)
                    .build()
            chunker.seek(5)
            chunker.next()
            assertThat(chunker.hasNext()).isFalse()
            assertThat(chunker.getOffset()).isEqualTo(expected)
        }
    }

    @Throws(IOException::class)
    private fun assertNextEquals(chunker: Chunker, vararg data: Byte) {
        assertThat(chunker.hasNext()).isTrue()
        val next: ByteString = chunker.next().getData()
        Truth.assertThat(next.toByteArray()).isEqualTo(data)
    }
}
