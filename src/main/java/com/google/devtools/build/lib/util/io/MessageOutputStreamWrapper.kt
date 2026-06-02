// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.protobuf.Message

/** Creates a MessageOutputStream from an OutputStream.  */
class MessageOutputStreamWrapper private constructor() {
    /** Writes the messages in length-delimited protobuf wire format.  */
    class BinaryOutputStreamWrapper<T : Message?>
        (stream: java.io.OutputStream?) : MessageOutputStream<T?> {
        private val stream: java.io.OutputStream

        init {
            this.stream = com.google.common.base.Preconditions.checkNotNull<java.io.OutputStream>(stream)
        }

        @Throws(IOException::class)
        override fun write(m: T?) {
            com.google.common.base.Preconditions.checkNotNull<T?>(m)
            m.writeDelimitedTo(stream)
        }

        @Throws(IOException::class)
        override fun close() {
            stream.close()
        }
    }

    /** Writes the messages in concatenated JSON text format.  */
    class JsonOutputStreamWrapper<T : Message?>(stream: java.io.OutputStream?) : MessageOutputStream<T?> {
        private val stream: java.io.OutputStream

        init {
            this.stream = com.google.common.base.Preconditions.checkNotNull<java.io.OutputStream>(stream)
        }

        @Throws(IOException::class)
        override fun write(m: T?) {
            com.google.common.base.Preconditions.checkNotNull<T?>(m)
            stream.write(PRINTER.print(m).getBytes(java.nio.charset.StandardCharsets.UTF_8))
        }

        @Throws(IOException::class)
        override fun close() {
            stream.close()
        }

        companion object {
            private val PRINTER: JsonFormat.Printer = JsonFormat.printer().alwaysPrintFieldsWithNoPresence()
        }
    }
}
