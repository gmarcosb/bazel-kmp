// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker

import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayInputStream
import java.io.IOException

/** Tests the RecordingInputStream class.  */
@RunWith(JUnit4::class)
class RecordingInputStreamTest {
    @get:Throws(IOException::class)
    @get:org.junit.Test
    val recordedDataAsString_returnsPlainStringsAsStrings: Unit
        get() {
            val s = "A good string\nWith two lines\n"
            val bais: ByteArrayInputStream =
                ByteArrayInputStream(s.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            val `in`: RecordingInputStream = RecordingInputStream(bais)

            `in`.startRecording(1000)
            `in`.readRemaining()

            assertThat(`in`.getRecordedDataAsString()).isEqualTo(s)
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val recordedDataAsString_returnsNonUtf8AsHex: Unit
        get() {
            val bais: ByteArrayInputStream =
                ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0X01))
            val `in`: RecordingInputStream = RecordingInputStream(bais)
            val inBuf = ByteArray(1000)

            `in`.startRecording(1000)
            `in`.read(inBuf)

            assertThat(`in`.getRecordedDataAsString())
                .isEqualTo(
                    "Not UTF-8, printing as hex\n"
                            + "FF FE 01                                          |...              |\n"
                )
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val recordedDataAsString_returnsMixedAsHex: Unit
        get() {
            var s = "One 17-char line!"
            // Doubles the length on each iteration
            for (i in 0..5) {
                s += s
            }
            val bytes: ByteArray = s.toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
            bytes[0] = 0x00
            bytes[1] = 0x01
            val bais: ByteArrayInputStream = ByteArrayInputStream(bytes)
            val `in`: RecordingInputStream = RecordingInputStream(bais)
            val inBuf = ByteArray(1025)

            `in`.startRecording(1025)
            `in`.read(inBuf)

            assertThat(`in`.getRecordedDataAsString())
                .startsWith(
                    "Not UTF-8, printing first 1024 bytes as hex\n"
                            + "00 01 65 20 31 37 2D 63  68 61 72 20 6C 69 6E 65  |..e 17-c har line|"
                )
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val recordedDataAsString_returnsLongNonUtf8AsHexLines: Unit
        get() {
            val buf = ByteArray(25)
            for (i in 0..24) {
                buf[i] = i.toByte()
            }
            buf[0] = 0xFF.toByte()
            buf[1] = 0xFE.toByte()
            val bais: ByteArrayInputStream = ByteArrayInputStream(buf)
            val `in`: RecordingInputStream = RecordingInputStream(bais)
            val inBuf = ByteArray(1000)

            `in`.startRecording(1000)
            `in`.read(inBuf)

            assertThat(`in`.getRecordedDataAsString())
                .startsWith(
                    ("Not UTF-8, printing as hex\n"
                            + "FF FE 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F  |........ ........|\n"
                            + "10 11 12 13 14 15 16 17  18                       |........ .       |\n")
                )
        }
}
