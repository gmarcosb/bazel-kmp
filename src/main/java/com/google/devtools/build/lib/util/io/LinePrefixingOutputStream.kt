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

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.lib.util.io.LineFlushingOutputStream
import java.io.IOException

/**
 * A stream that writes to another one, emittig a prefix before every line
 * it emits. This stream will also add a newline for every flush; so it's not
 * useful for anything other than simple text data (e.g. log files). Here's
 * an example which demonstrates how an explicit flush or a flush caused by
 * a full buffer causes a newline to be added to the output.
 * 
 * `
 * foo bar
 * baz ba[flush]ng
 * boo
` * 
 * 
 * This results in this output being emitted:
 * 
 * `
 * my prefix: foo bar
 * my prefix: ba
 * my prefix: ng
 * my prefix: boo
` * 
 */
class LinePrefixingOutputStream(linePrefix: String, sink: java.io.OutputStream) : LineFlushingOutputStream() {
    private val linePrefix: ByteArray?
    private val sink: java.io.OutputStream

    init {
        this.linePrefix = linePrefix.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        this.sink = sink
    }

    @Throws(IOException::class)
    override fun flushingHook() {
        synchronized(sink) {
            if (len == 0) {
                sink.flush()
                return
            }
            val lastByte: Byte = buffer[len - 1]
            val lineIsIncomplete = lastByte != LineFlushingOutputStream.Companion.NEWLINE
            sink.write(linePrefix)
            sink.write(buffer, 0, len)
            if (lineIsIncomplete) {
                sink.write(LineFlushingOutputStream.Companion.NEWLINE.toInt())
            }
            sink.flush()
            len = 0
        }
    }
}
