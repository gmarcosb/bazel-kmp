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

/** Tests [PositionAwareAnsiTerminalWriter].  */
@RunWith(JUnit4::class)
class PositionAwareAnsiTerminalWriterTest {
    @Test
    @Throws(IOException::class)
    fun positionSimple() {
        val sample = "lorem ipsum..."
        val loggingTerminalWriter: LoggingTerminalWriter = LoggingTerminalWriter()
        val terminalWriter: PositionAwareAnsiTerminalWriter =
            PositionAwareAnsiTerminalWriter(loggingTerminalWriter)

        terminalWriter.append(sample)

        assertThat(terminalWriter.getPosition()).isEqualTo(sample.length)
        assertThat(loggingTerminalWriter.getTranscript()).isEqualTo(sample)
    }

    @Test
    @Throws(IOException::class)
    fun positionTwoLines() {
        val firstLine = "lorem ipsum..."
        val secondLine = "foo bar baz"

        val loggingTerminalWriter: LoggingTerminalWriter = LoggingTerminalWriter()
        val terminalWriter: PositionAwareAnsiTerminalWriter =
            PositionAwareAnsiTerminalWriter(loggingTerminalWriter)

        terminalWriter.append(firstLine)
        assertThat(terminalWriter.getPosition()).isEqualTo(firstLine.length)
        terminalWriter.newline()
        assertThat(terminalWriter.getPosition()).isEqualTo(0)
        terminalWriter.append(secondLine)
        assertThat(terminalWriter.getPosition()).isEqualTo(secondLine.length)
        terminalWriter.newline()
        assertThat(terminalWriter.getPosition()).isEqualTo(0)
        assertThat(loggingTerminalWriter.getTranscript()).isEqualTo(firstLine + NL + secondLine + NL)
    }

    @Test
    @Throws(IOException::class)
    fun positionNewlineTranslated() {
        val firstLine = "lorem ipsum..."
        val secondLine = "foo bar baz"

        val loggingTerminalWriter: LoggingTerminalWriter = LoggingTerminalWriter()
        val terminalWriter: PositionAwareAnsiTerminalWriter =
            PositionAwareAnsiTerminalWriter(loggingTerminalWriter)

        terminalWriter.append(firstLine + "\n" + secondLine)
        assertThat(terminalWriter.getPosition()).isEqualTo(secondLine.length)
        terminalWriter.append("\n")
        assertThat(terminalWriter.getPosition()).isEqualTo(0)
        assertThat(loggingTerminalWriter.getTranscript()).isEqualTo(firstLine + NL + secondLine + NL)
    }

    @Test
    @Throws(IOException::class)
    fun passThrough() {
        val loggingTerminalWriter: LoggingTerminalWriter = LoggingTerminalWriter()
        val terminalWriter: PositionAwareAnsiTerminalWriter =
            PositionAwareAnsiTerminalWriter(loggingTerminalWriter)

        terminalWriter
            .append("abc")
            .okStatus()
            .append("ok")
            .failStatus()
            .append("fail")
            .normal()
            .append("normal")
        assertThat(loggingTerminalWriter.getTranscript())
            .isEqualTo("abc" + OK + "ok" + FAIL + "fail" + NORMAL + "normal")
    }

    @Test
    @Throws(IOException::class)
    fun highlightNospace() {
        val sample = "lorem ipsum..."

        val loggingTerminalWriter: LoggingTerminalWriter = LoggingTerminalWriter()
        val terminalWriter: PositionAwareAnsiTerminalWriter =
            PositionAwareAnsiTerminalWriter(loggingTerminalWriter)

        terminalWriter.failStatus()
        assertThat(terminalWriter.getPosition()).isEqualTo(0)
        terminalWriter.append(sample)
        assertThat(terminalWriter.getPosition()).isEqualTo(sample.length)
        assertThat(loggingTerminalWriter.getTranscript()).isEqualTo(FAIL + sample)
    }

    companion object {
        val NL: String? = LoggingTerminalWriter.NEWLINE
        val OK: String? = LoggingTerminalWriter.OK
        val FAIL: String? = LoggingTerminalWriter.FAIL
        val NORMAL: String? = LoggingTerminalWriter.NORMAL
    }
}
