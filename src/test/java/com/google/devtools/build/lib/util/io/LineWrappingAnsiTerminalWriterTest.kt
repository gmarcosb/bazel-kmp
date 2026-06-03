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
package com.google.devtools.build.lib.util.io

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException

/**
 * Tests [LineWrappingAnsiTerminalWriter].
 */
@RunWith(JUnit4::class)
class LineWrappingAnsiTerminalWriterTest {
    @Test
    @Throws(IOException::class)
    fun testSimpleLineWrapping() {
        val terminal: LoggingTerminalWriter = LoggingTerminalWriter()
        LineWrappingAnsiTerminalWriter(terminal, 5, '+').append("abcdefghij")
        assertThat(terminal.getTranscript()).isEqualTo("abcd+" + NL + "efgh+" + NL + "ij")
    }

    @Test
    @Throws(IOException::class)
    fun testAlwaysWrap() {
        val terminal: LoggingTerminalWriter = LoggingTerminalWriter()
        LineWrappingAnsiTerminalWriter(terminal, 5, '+').append("12345").newline()
        assertThat(terminal.getTranscript()).isEqualTo("1234+" + NL + "5" + NL)
    }

    @Test
    @Throws(IOException::class)
    fun testWrapLate() {
        val terminal: LoggingTerminalWriter = LoggingTerminalWriter()
        LineWrappingAnsiTerminalWriter(terminal, 5, '+').append("1234")
        // Lines are only wrapped, once a character is written that cannot fit in the current line, and
        // not already once the last usable character of a line is used. Hence, in this example, we do
        // not want to see the continuation character.
        assertThat(terminal.getTranscript()).isEqualTo("1234")
    }

    @Test
    @Throws(IOException::class)
    fun testNewlineTranslated() {
        val terminal: LoggingTerminalWriter = LoggingTerminalWriter()
        LineWrappingAnsiTerminalWriter(terminal, 80, '+').append("foo\nbar\n")
        assertThat(terminal.getTranscript()).isEqualTo("foo" + NL + "bar" + NL)
    }

    @Test
    @Throws(IOException::class)
    fun testNewlineResetsCount() {
        val terminal: LoggingTerminalWriter = LoggingTerminalWriter()
        LineWrappingAnsiTerminalWriter(terminal, 5, '+')
            .append("123")
            .newline()
            .append("abc")
            .newline()
            .append("ABC\nABC")
            .newline()
        assertThat(terminal.getTranscript())
            .isEqualTo("123" + NL + "abc" + NL + "ABC" + NL + "ABC" + NL)
    }

    @Test
    @Throws(IOException::class)
    fun testEventsPassedThrough() {
        val terminal: LoggingTerminalWriter = LoggingTerminalWriter()
        LineWrappingAnsiTerminalWriter(terminal, 80, '+')
            .okStatus()
            .append("ok")
            .failStatus()
            .append("fail")
            .normal()
            .append("normal")
        assertThat(terminal.getTranscript()).isEqualTo(OK + "ok" + FAIL + "fail" + NORMAL + "normal")
    }

    companion object {
        val NL: String? = LoggingTerminalWriter.NEWLINE
        val OK: String? = LoggingTerminalWriter.OK
        val FAIL: String? = LoggingTerminalWriter.FAIL
        val NORMAL: String? = LoggingTerminalWriter.NORMAL
    }
}
