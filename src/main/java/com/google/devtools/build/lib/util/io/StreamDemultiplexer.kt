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

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible

/**
 * The dual of [StreamMultiplexer]: This is an output stream into which
 * you can dump the multiplexed stream, and it delegates the de-multiplexed
 * content back into separate channels (instances of [OutputStream]).
 * 
 * The format of the tagged output stream is as follows:
 * 
 * <pre>
 * combined :: = [ control_line payload ... ]+
 * control_line :: = '@' marker '@'? '\n'
 * payload :: = r'^[^\n]*\n'
</pre> * 
 * 
 * For more details, please see [StreamMultiplexer].
 */
@ThreadCompatible
class StreamDemultiplexer(
    smallestMarkerByte: Byte,
    vararg outputStreams: java.io.OutputStream?
) : java.io.OutputStream() {
    @Throws(IOException::class)
    override fun close() {
        flush()
    }

    @Throws(IOException::class)
    override fun flush() {
        if (selectedStream != null) {
            selectedStream.flush()
        }
    }

    /**
     * The output streams, conveniently in an array indexed by the marker byte.
     * Some of these will be null, most likely.
     */
    private val outputStreams: Array<java.io.OutputStream?> =
        arrayOfNulls<java.io.OutputStream>(Byte.Companion.MAX_VALUE + 1)

    /**
     * Each state in this FSM corresponds to a position in the grammar, which is
     * simple enough that we can just move through it from beginning to end as we
     * parse things.
     */
    private enum class State {
        EXPECT_MARKER_BYTE,
        EXPECT_SIZE,
        EXPECT_PAYLOAD,
    }

    private val sizeBuffer = IntArray(4)
    private var state: State = com.google.devtools.build.lib.util.io.StreamDemultiplexer.State.EXPECT_MARKER_BYTE
    private var selectedStream: java.io.OutputStream? = null
    private var currentSizeByte = 0
    private var payloadBytesLeft = 0

    /**
     * Construct a new demultiplexer. The `smallestMarkerByte` indicates
     * the marker byte we would expect for `outputStreams[0]` to be used.
     * So, if this first stream is your stdout and you're using the
     * [StreamMultiplexer], then you will need to set this to
     * `1`. Because [StreamDemultiplexer] extends
     * [OutputStream], this constructor effectively creates an
     * [OutputStream] instance which demultiplexes the tagged data client
     * code writes to it into `outputStreams`.
     */
    init {
        for (i in outputStreams.indices) {
            this.outputStreams[smallestMarkerByte + i] = outputStreams[i]
        }
    }

    @Throws(IOException::class)
    override fun write(b: Int) {
        // This dispatch traverses the finite state machine / grammar.
        when (state) {
            com.google.devtools.build.lib.util.io.StreamDemultiplexer.State.EXPECT_MARKER_BYTE -> parseMarkerByte(b)
            com.google.devtools.build.lib.util.io.StreamDemultiplexer.State.EXPECT_SIZE -> parseSize(b)
            com.google.devtools.build.lib.util.io.StreamDemultiplexer.State.EXPECT_PAYLOAD -> parsePayload(b)
        }
    }

    private fun parseSize(b: Int) {
        sizeBuffer[currentSizeByte] = b
        currentSizeByte += 1
        if (currentSizeByte == 4) {
            state = com.google.devtools.build.lib.util.io.StreamDemultiplexer.State.EXPECT_PAYLOAD
            payloadBytesLeft = ((sizeBuffer[0] shl 24)
                    + (sizeBuffer[1] shl 16)
                    + (sizeBuffer[2] shl 8)
                    + sizeBuffer[3])
        }
    }

    /**
     * Handles [State.EXPECT_MARKER_BYTE]. The byte determines which stream
     * we will be using, and will set [.selectedStream].
     */
    @Throws(IOException::class)
    private fun parseMarkerByte(markerByte: Int) {
        if (markerByte < 0 || markerByte > Byte.Companion.MAX_VALUE) {
            val msg = "Illegal marker byte (" + markerByte + ")"
            throw java.lang.IllegalArgumentException(msg)
        }
        if (markerByte > outputStreams.size
            || outputStreams[markerByte] == null
        ) {
            throw IOException("stream " + markerByte + " not registered.")
        }
        selectedStream = outputStreams[markerByte]
        state = com.google.devtools.build.lib.util.io.StreamDemultiplexer.State.EXPECT_SIZE
        currentSizeByte = 0
    }

    @Throws(IOException::class)
    private fun parsePayload(b: Int) {
        selectedStream.write(b)
        payloadBytesLeft -= 1
        if (payloadBytesLeft == 0) {
            state = com.google.devtools.build.lib.util.io.StreamDemultiplexer.State.EXPECT_MARKER_BYTE
        }
    }
}
