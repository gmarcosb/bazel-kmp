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
package com.google.devtools.build.lib.util

import com.google.common.truth.Truth
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.PrintStream

/**
 * Tests for [AnsiStrippingOutputStream].
 */
@RunWith(JUnit4::class)
class AnsiStrippingOutputStreamTest {
    var output: java.io.ByteArrayOutputStream? = null
    var input: PrintStream? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createStreams() {
        output = java.io.ByteArrayOutputStream()
        val inputStream: java.io.OutputStream = AnsiStrippingOutputStream(output)
        input = PrintStream(inputStream)
    }

    @Throws(java.lang.Exception::class)
    private fun getOutput(vararg fragments: String?): String {
        for (fragment in fragments) {
            input.print(fragment)
        }

        return String(output.toByteArray(), charset("ISO8859-1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun doesNotFailHorribly() {
        Truth.assertThat(getOutput("Love")).isEqualTo("Love")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun canStripAnsiCode() {
        Truth.assertThat(getOutput(ESCAPE + "32mLove" + ESCAPE + "m")).isEqualTo("Love")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun recognizesAnsiCodeWhenBrokenUp() {
        Truth.assertThat(getOutput("\u001b", "[", "mLove")).isEqualTo("Love")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun handlesOnlyEscCorrectly() {
        Truth.assertThat(getOutput("\u001bLove")).isEqualTo("\u001bLove")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun handlesEscInPlaceOfControlCharCorrectly() {
        Truth.assertThat(getOutput(ESCAPE + "31;42" + ESCAPE + "1mLove")).isEqualTo(ESCAPE + "31;42Love")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun handlesTwoEscapeSequencesCorrectly() {
        Truth.assertThat(getOutput(ESCAPE + "32m" + ESCAPE + "1m" + "Love")).isEqualTo("Love")
    }

    companion object {
        private const val ESCAPE = "\u001b["
    }
}
