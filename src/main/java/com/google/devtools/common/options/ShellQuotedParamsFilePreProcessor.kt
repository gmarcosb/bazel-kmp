// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options

import java.io.IOException
import java.io.PushbackReader

/**
 * A [ParamsFilePreProcessor] that processes a parameter file using the `com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType.SHELL_QUOTED` format. This
 * format assumes each parameter is separated by whitespace and is quoted using singe quotes
 * (`'`) if it contains any special characters or is an empty string.
 */
class ShellQuotedParamsFilePreProcessor(fs: java.nio.file.FileSystem?) :
    com.google.devtools.common.options.ParamsFilePreProcessor(fs) {
    @Throws(IOException::class)
    override fun parse(paramsFile: java.nio.file.Path): MutableList<String?> {
        val args: MutableList<String?> = java.util.ArrayList<String?>()
        com.google.devtools.common.options.ShellQuotedParamsFilePreProcessor.ShellQuotedReader(
            java.nio.file.Files.newBufferedReader(
                paramsFile,
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).use { reader ->
            var arg: String?
            while ((reader.readArg().also { arg = it }) != null) {
                args.add(arg)
            }
        }
        return args
    }

    private class ShellQuotedReader(reader: java.io.Reader) : java.lang.AutoCloseable {
        private val reader: PushbackReader
        private var position = -1

        init {
            this.reader = PushbackReader(reader, 10)
        }

        @Throws(IOException::class)
        fun read(): Char {
            val value: Int = reader.read()
            position++
            return value.toChar()
        }

        @Throws(IOException::class)
        fun unread(value: Char) {
            reader.unread(value.code)
            position--
        }

        @Throws(IOException::class)
        fun hasNext(): Boolean {
            val value = read()
            val hasNext = value != -1.toChar()
            unread(value)
            return hasNext
        }

        @Throws(IOException::class)
        override fun close() {
            reader.close()
        }

        @Throws(IOException::class)
        fun readArg(): String? {
            if (!hasNext()) {
                return null
            }

            val arg: java.lang.StringBuilder = java.lang.StringBuilder()

            var quoteStart = -1
            var quoted = false
            var current: Char

            while ((read().also { current = it }) != -1.toChar()) {
                if (quoted) {
                    if (current == '\'') {
                        val escapedQuoteRemainder: java.lang.StringBuilder =
                            java.lang.StringBuilder().append(read()).append(read()).append(read())
                        if (escapedQuoteRemainder.toString() == "\\''") {
                            arg.append("'")
                        } else {
                            for (c in escapedQuoteRemainder.reverse().toString().toCharArray()) {
                                unread(c)
                            }
                            quoted = false
                            quoteStart = -1
                        }
                    } else {
                        arg.append(current)
                    }
                } else {
                    if (current == '\'') {
                        quoted = true
                        quoteStart = position
                    } else if (current == '\r') {
                        val next = read()
                        if (next == '\n') {
                            return arg.toString()
                        } else {
                            unread(next)
                            return arg.toString()
                        }
                    } else if (java.lang.Character.isWhitespace(current)) {
                        return arg.toString()
                    } else {
                        arg.append(current)
                    }
                }
            }
            if (quoted) {
                throw IOException(
                    java.lang.String.format(
                        com.google.devtools.common.options.ParamsFilePreProcessor.Companion.UNFINISHED_QUOTE_MESSAGE_FORMAT,
                        "'",
                        quoteStart
                    )
                )
            }
            return arg.toString()
        }
    }
}
