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

import com.google.common.io.ByteStreams
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.UnsupportedEncodingException
import java.util.*

/**
 * Test for [StreamMultiplexer].
 */
@RunWith(JUnit4::class)
class StreamMultiplexerTest {
    private var multiplexed: ByteArrayOutputStream? = null
    private var out: OutputStream? = null
    private var err: OutputStream? = null
    private var ctl: OutputStream? = null

    @Before
    @Throws(Exception::class)
    fun createOutputStreams() {
        multiplexed = ByteArrayOutputStream()
        val multiplexer: StreamMultiplexer = StreamMultiplexer(multiplexed)
        out = multiplexer.createStdout()
        err = multiplexer.createStderr()
        ctl = multiplexer.createControl()
    }

    @Test
    @Throws(IOException::class)
    fun testEmptyWire() {
        out!!.flush()
        err!!.flush()
        ctl!!.flush()
        Truth.assertThat(multiplexed!!.toByteArray()).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun testHelloWorldOnStdOut() {
        out!!.write(getLatin("Hello, world."))
        out!!.flush()
        assertMessage(multiplexed!!.toByteArray(), 0, "Hello, world.")
    }

    @Test
    @Throws(Exception::class)
    fun testInterleavedStdoutStderrControl() {
        var start = 0
        out!!.write(getLatin("Hello, stdout."))
        out!!.flush()
        assertMessage(multiplexed!!.toByteArray(), start, "Hello, stdout.")
        start = multiplexed!!.toByteArray().size

        err!!.write(getLatin("Hello, stderr."))
        err!!.flush()
        assertMessage(multiplexed!!.toByteArray(), start, "Hello, stderr.")
        start = multiplexed!!.toByteArray().size

        ctl!!.write(getLatin("Hello, control."))
        ctl!!.flush()
        assertMessage(multiplexed!!.toByteArray(), start, "Hello, control.")
        start = multiplexed!!.toByteArray().size

        out!!.write(getLatin("... and back!"))
        out!!.flush()
        assertMessage(multiplexed!!.toByteArray(), start, "... and back!")
    }

    @Test
    @Throws(Exception::class)
    fun testWillNotCommitToUnderlyingStreamUnlessFlushOrNewline() {
        out!!.write(
            getLatin(
                "There are no newline characters in here, so it won't" +
                        " get written just yet."
            )
        )
        Truth.assertThat(ByteArray(0)).isEqualTo(multiplexed!!.toByteArray())
    }

    @Test
    @Throws(Exception::class)
    fun testNewlineTriggersFlush() {
        out!!.write(getLatin("No newline just yet, so no flushing. "))
        Truth.assertThat(ByteArray(0)).isEqualTo(multiplexed!!.toByteArray())
        out!!.write(getLatin("OK, here we go:\nAnd more to come."))
        assertMessage(
            multiplexed!!.toByteArray(), 0, "No newline just yet, so no flushing. OK, here we go:\n"
        )
        val firstMessageLength = multiplexed!!.toByteArray().size
        out.write('\n'.code.toByte().toInt())
        assertMessage(multiplexed!!.toByteArray(), firstMessageLength, "And more to come.\n")
    }

    @Test
    @Throws(Exception::class)
    fun testFlush() {
        out!!.write(getLatin("Don't forget to flush!"))
        Truth.assertThat(multiplexed!!.toByteArray()).isEqualTo(ByteArray(0))
        out!!.flush() // now the output will appear in multiplexed.
        assertStartsWith(multiplexed!!.toByteArray(), 1, 0, 0, 0)
        assertMessage(multiplexed!!.toByteArray(), 0, "Don't forget to flush!")
    }

    @Test
    @Throws(IOException::class)
    fun testByteEncoding() {
        val devNull = ByteStreams.nullOutputStream()
        val demux: StreamDemultiplexer = StreamDemultiplexer(1.toByte(), devNull)
        val mux: StreamMultiplexer = StreamMultiplexer(demux)
        val out: OutputStream = mux.createStdout()

        // When we cast 266 to a byte, we get 10. So basically, we ended up
        // comparing 266 with 10 as an integer (because out.write takes an int),
        // and then later cast it to 10. This way we'd end up with a control
        // character \n in the middle of the payload which would then screw things
        // up when the real control character arrived. The fixed version of the
        // StreamMultiplexer avoids this problem by always casting to a byte before
        // carrying out any comparisons.
        out.write(266)
        out.write(10)
    }

    companion object {
        @Throws(UnsupportedEncodingException::class)
        private fun getLatin(string: String): ByteArray? {
            return string.toByteArray(charset("ISO-8859-1"))
        }

        private fun assertStartsWith(actual: ByteArray, vararg expectedPrefix: Int) {
            for (i in expectedPrefix.indices) {
                Truth.assertThat<Byte?>(actual[i]).isEqualTo(expectedPrefix[i])
            }
        }

        @Throws(Exception::class)
        private fun assertMessage(actual: ByteArray, start: Int, expected: String) {
            Truth.assertThat(Arrays.copyOfRange(actual, start + 5, actual.size)).isEqualTo(getLatin(expected))
        }
    }
}
