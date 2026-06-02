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
package com.google.devtools.build.lib.bazel.repository.downloader

import com.google.common.base.Ascii
import com.google.common.collect.ImmutableList
import java.io.IOException
import java.io.InputStream
import kotlin.collections.HashMap
import kotlin.collections.MutableList
import kotlin.collections.MutableMap

/** Utility class for parsing HTTP messages.  */
internal object HttpParser {
    /**
     * Parses request line and headers of HTTP request.
     * 
     * 
     * This parser is correct and extremely lax. This implementation is Θ(n) and the stream should
     * be buffered. All decoding is ISO-8859-1. A 1mB upper bound on memory is enforced.
     * 
     * @throws IOException if reading failed or premature end of stream encountered
     * @throws HttpParserError if 400 error should be sent to client and connection must be closed
     */
    /** Exhausts request line and headers of HTTP request.  */
    @kotlin.jvm.JvmOverloads
    @Throws(IOException::class)
    fun readHttpRequest(
        stream: InputStream,
        output: MutableMap<String?, MutableList<String?>?> = HashMap<String?, MutableList<String?>?>()
    ) {
        val builder = StringBuilder(256)
        var state = State.METHOD
        var key = ""
        var toto = 0
        while (true) {
            val c = stream.read()
            if (c == -1) {
                throw IOException() // RFC7230 § 3.4
            }
            if (++toto == 1024 * 1024) {
                throw HttpParserError() // RFC7230 § 3.2.5
            }
            when (state) {
                State.METHOD -> if (c == ' '.code) {
                    if (builder.length == 0) {
                        throw HttpParserError()
                    }
                    output.put("x-method", ImmutableList.of<String?>(builder.toString()))
                    builder.setLength(0)
                    state = State.URI
                } else if (c == '\r'.code || c == '\n'.code) {
                    break // RFC7230 § 3.5
                } else {
                    builder.append(Ascii.toUpperCase(c.toChar()))
                }

                State.URI -> if (c == ' '.code) {
                    if (builder.length == 0) {
                        throw HttpParserError()
                    }
                    output.put("x-request-uri", ImmutableList.of<String?>(builder.toString()))
                    builder.setLength(0)
                    state = State.VERSION
                } else {
                    builder.append(c.toChar())
                }

                State.VERSION -> if (c == '\r'.code || c == '\n'.code) {
                    output.put("x-version", ImmutableList.of<String?>(builder.toString()))
                    builder.setLength(0)
                    state = if (c == '\r'.code) State.CR1 else State.LF1
                } else {
                    builder.append(Ascii.toUpperCase(c.toChar()))
                }

                State.CR1 -> {
                    if (c == '\n'.code) {
                        state = State.LF1
                        break
                    }
                    throw HttpParserError()
                }

                State.LF1 -> {
                    if (c == '\r'.code) {
                        state = State.LF2
                        break
                    } else if (c == '\n'.code) {
                        return
                    } else if (c == ' '.code || c == '\t'.code) {
                        throw HttpParserError("Line folding unacceptable") // RFC7230 § 3.2.4
                    }
                    state = State.HKEY
                    if (c == ':'.code) {
                        key = builder.toString()
                        builder.setLength(0)
                        state = State.HSEP
                    } else {
                        builder.append(Ascii.toLowerCase(c.toChar()))
                    }
                }

                State.HKEY -> if (c == ':'.code) {
                    key = builder.toString()
                    builder.setLength(0)
                    state = State.HSEP
                } else {
                    builder.append(Ascii.toLowerCase(c.toChar()))
                }

                State.HSEP -> {
                    if (c == ' '.code || c == '\t'.code) {
                        break
                    }
                    state = State.HVAL
                    if (c == '\r'.code || c == '\n'.code) {
                        output.put(key, ImmutableList.of<String?>(builder.toString()))
                        builder.setLength(0)
                        state = if (c == '\r'.code) State.CR1 else State.LF1
                    } else {
                        builder.append(c.toChar())
                    }
                }

                State.HVAL -> if (c == '\r'.code || c == '\n'.code) {
                    output.put(key, ImmutableList.of<String?>(builder.toString()))
                    builder.setLength(0)
                    state = if (c == '\r'.code) State.CR1 else State.LF1
                } else {
                    builder.append(c.toChar())
                }

                State.LF2 -> {
                    if (c == '\n'.code) {
                        return
                    }
                    throw HttpParserError()
                }

                else -> throw AssertionError()
            }
        }
    }

    internal class HttpParserError @kotlin.jvm.JvmOverloads constructor(messageForClient: String? = "Malformed Request") :
        IOException(messageForClient)

    private enum class State {
        METHOD, URI, VERSION, HKEY, HSEP, HVAL, CR1, LF1, LF2
    }
}
