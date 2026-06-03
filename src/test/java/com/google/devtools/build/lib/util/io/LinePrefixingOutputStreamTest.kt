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
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Tests [LinePrefixingOutputStream].
 */
@RunWith(JUnit4::class)
class LinePrefixingOutputStreamTest {
    private fun bytes(string: String): ByteArray? {
        return string.toByteArray(StandardCharsets.UTF_8)
    }

    private fun string(bytes: ByteArray): String {
        return String(bytes, StandardCharsets.UTF_8)
    }

    private val out = ByteArrayOutputStream()
    private val prefixOut: LinePrefixingOutputStream = LinePrefixingOutputStream("Prefix: ", out)

    @Test
    @Throws(IOException::class)
    fun testNoOutputUntilNewline() {
        prefixOut.write(bytes("We won't be seeing any output."))
        Truth.assertThat(string(out.toByteArray())).isEmpty()
    }

    @Test
    @Throws(IOException::class)
    fun testOutputIfFlushed() {
        prefixOut.write(bytes("We'll flush after this line."))
        prefixOut.flush()
        Truth.assertThat(string(out.toByteArray())).isEqualTo("Prefix: We'll flush after this line.\n")
    }

    @Test
    @Throws(IOException::class)
    fun testAutoflushUponNewline() {
        prefixOut.write(bytes("Hello, newline.\n"))
        Truth.assertThat(string(out.toByteArray())).isEqualTo("Prefix: Hello, newline.\n")
    }

    @Test
    @Throws(IOException::class)
    fun testAutoflushUponEmbeddedNewLine() {
        prefixOut.write(bytes("Hello line1.\nHello line2.\nHello line3.\n"))
        Truth.assertThat(string(out.toByteArray()))
            .isEqualTo("Prefix: Hello line1.\nPrefix: Hello line2.\nPrefix: Hello line3.\n")
    }

    @Test
    @Throws(IOException::class)
    fun testBufferMaxLengthFlush() {
        var junk = "lots of characters of non-newline junk. "
        while (junk.length < LineFlushingOutputStream.BUFFER_LENGTH) {
            junk = junk + junk
        }
        junk = junk.substring(0, LineFlushingOutputStream.BUFFER_LENGTH)

        // Also test bug where write on a full buffer blows up
        prefixOut.write(bytes(junk + junk))
        prefixOut.write(bytes(junk + junk))
        prefixOut.write(bytes("x"))
        Truth.assertThat(string(out.toByteArray())).isEqualTo(
            ("Prefix: " + junk + "\nPrefix: " + junk
                    + "\nPrefix: " + junk + "\nPrefix: " + junk + "\n")
        )
    }
}
