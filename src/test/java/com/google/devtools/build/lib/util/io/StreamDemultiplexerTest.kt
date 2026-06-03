// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util.io

import com.google.common.truth.Truth
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase.write
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import java.util.*

/**
 * Tests [StreamDemultiplexer].
 */
@RunWith(JUnit4::class)
class StreamDemultiplexerTest {
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val ctl = ByteArrayOutputStream()

    private fun toAnsi(stream: ByteArrayOutputStream): String {
        try {
            return String(stream.toByteArray(), charset("ISO-8859-1"))
        } catch (e: UnsupportedEncodingException) {
            throw AssertionError(e)
        }
    }

    private fun inAnsi(string: String): ByteArray? {
        try {
            return string.toByteArray(charset("ISO-8859-1"))
        } catch (e: UnsupportedEncodingException) {
            throw AssertionError(e)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testHelloWorldOnStandardOut() {
        val multiplexed: ByteArray = chunk(1, "Hello, world.")
        StreamDemultiplexer(1.toByte(), out).use { demux ->
            demux.write(multiplexed)
        }
        Truth.assertThat(out.toString("ISO-8859-1")).isEqualTo("Hello, world.")
    }

    @Test
    @Throws(Exception::class)
    fun testOutErrCtl() {
        val multiplexed: ByteArray = concat(chunk(1, "out"), chunk(2, "err"), chunk(3, "ctl"))
        StreamDemultiplexer(1.toByte(), out, err, ctl).use { demux ->
            demux.write(multiplexed)
        }
        Truth.assertThat(toAnsi(out)).isEqualTo("out")
        Truth.assertThat(toAnsi(err)).isEqualTo("err")
        Truth.assertThat(toAnsi(ctl)).isEqualTo("ctl")
    }

    @Test
    @Throws(Exception::class)
    fun testWithoutLineBreaks() {
        val multiplexed: ByteArray = concat(chunk(1, "just "), chunk(1, "one "), chunk(1, "line"))
        StreamDemultiplexer(1.toByte(), out).use { demux ->
            demux.write(multiplexed)
        }
        Truth.assertThat(out.toString("ISO-8859-1")).isEqualTo("just one line")
    }

    @Test
    @Throws(Exception::class)
    fun testMultiplexAndBackWithHelloWorld() {
        val demux: StreamDemultiplexer = StreamDemultiplexer(1.toByte(), out)
        val mux: StreamMultiplexer = StreamMultiplexer(demux)
        val out: OutputStream = mux.createStdout()
        out.write(inAnsi("Hello, world."))
        out.flush()
        Truth.assertThat(toAnsi(this.out)).isEqualTo("Hello, world.")
    }

    @Test
    @Throws(Exception::class)
    fun testMultiplexDemultiplexBinaryStress() {
        val demux: StreamDemultiplexer = StreamDemultiplexer(1.toByte(), out, err, ctl)
        val mux: StreamMultiplexer = StreamMultiplexer(demux)
        val muxOuts = arrayOf<OutputStream?>(mux.createStdout(), mux.createStderr(), mux.createControl())
        val expectedOuts =
            arrayOf<ByteArrayOutputStream?>(ByteArrayOutputStream(), ByteArrayOutputStream(), ByteArrayOutputStream())

        val random = Random(-0x21524111)
        for (round in 0..99) {
            val buffer = ByteArray(random.nextInt(100))
            random.nextBytes(buffer)
            val streamId = random.nextInt(3)
            expectedOuts[streamId]!!.write(buffer)
            expectedOuts[streamId]!!.flush()
            muxOuts[streamId]!!.write(buffer)
            muxOuts[streamId]!!.flush()
        }
        Truth.assertThat(out.toByteArray()).isEqualTo(expectedOuts[0]!!.toByteArray())
        Truth.assertThat(err.toByteArray()).isEqualTo(expectedOuts[1]!!.toByteArray())
        Truth.assertThat(ctl.toByteArray()).isEqualTo(expectedOuts[2]!!.toByteArray())
    }

    companion object {
        private fun chunk(stream: Int, payload: String): ByteArray {
            val payloadBytes: ByteArray = payload.toByteArray(Charset.defaultCharset())
            val result = ByteArray(payloadBytes.size + 5)

            System.arraycopy(payloadBytes, 0, result, 5, payloadBytes.size)
            result[0] = stream.toByte()
            result[1] = (payloadBytes.size shr 24).toByte()
            result[2] = ((payloadBytes.size shr 16) and 0xff).toByte()
            result[3] = ((payloadBytes.size shr 8) and 0xff).toByte()
            result[4] = (payloadBytes.size and 0xff).toByte()
            return result
        }

        private fun concat(vararg chunks: ByteArray): ByteArray {
            var length = 0
            for (chunk in chunks) {
                length += chunk.size
            }

            val result = ByteArray(length)
            var previousChunks = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, result, previousChunks, chunk.size)
                previousChunks += chunk.size
            }
            return result
        }
    }
}
