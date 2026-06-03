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
package com.google.devtools.build.lib.runtime

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.ScratchAttributeWriter.write
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase.write
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException

/**
 * Unit tests for [LineBufferedOutputStream] .
 */
@RunWith(JUnit4::class)
class LineBufferedOutputStreamTest {
    private class MockOutputStream : java.io.OutputStream() {
        private val writes: MutableList<String?> = java.util.ArrayList<String?>()
        private var throwException = false

        @Throws(IOException::class)
        override fun write(byteAsInt: Int) {
            val b = byteAsInt.toByte() // make sure we work with bytes in comparisons
            write(byteArrayOf(b), 0, 1)
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun write(b: ByteArray, off: Int, inlen: Int) {
            writes.add(String(b, off, inlen, java.nio.charset.StandardCharsets.UTF_8))
            if (throwException) {
                throwException = false
                throw IOException("thrown")
            }
        }
    }

    @Throws(java.lang.Exception::class)
    private fun lineBuffer(vararg inputs: String): MutableList<String?> {
        val mockOutputStream = MockOutputStream()
        LineBufferedOutputStream(mockOutputStream, 6).use { cut ->
            for (input in inputs) {
                cut.write(input.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            }
        }
        return mockOutputStream.writes
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLineBuffering() {
        val large: String = "a".repeat(100)

        Truth.assertThat(lineBuffer("foo\nbar")).containsExactly("foo\n", "bar")
        Truth.assertThat(lineBuffer("foobarfoobar")).containsExactly("foobar", "foobar")
        Truth.assertThat(lineBuffer("fivey\none\n")).containsExactly("fivey\n", "one\n")
        Truth.assertThat(lineBuffer("sixish\none\n")).containsExactly("sixish", "\n", "one\n")
        Truth.assertThat(lineBuffer("s")).containsExactly("s")
        Truth.assertThat(lineBuffer("\n\n\n\n")).containsExactly("\n", "\n", "\n", "\n")
        Truth.assertThat(lineBuffer("foo\n\nbar\n")).containsExactly("foo\n", "\n", "bar\n")

        Truth.assertThat(lineBuffer("a", "a", large, large, "a")).containsExactly(
            "aa", large, large, "a"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIOErrorOnWrappedStream() {
        val mos = MockOutputStream()
        LineBufferedOutputStream(mos, 4).use { cut ->
            mos.throwException = true
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable { cut.write("aaaa".toByteArray(java.nio.charset.StandardCharsets.UTF_8)) })
            cut.write("a".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        }
        Truth.assertThat(mos.writes).containsExactly("aaaa", "a")
    }
}
