// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.protobuf.ExtensionRegistry

/** Creates a MessageInputStream from an OutputStream.  */
class MessageInputStreamWrapper private constructor() {
    /** Reads the messages in length-delimited protobuf wire format.  */
    class BinaryInputStreamWrapper<T : Message?>(stream: java.io.InputStream?, defaultInstance: T?) :
        MessageInputStream<T?> {
        private val stream: java.io.InputStream
        private val parser: com.google.protobuf.Parser<T?>

        init {
            this.stream = com.google.common.base.Preconditions.checkNotNull<java.io.InputStream>(stream)
            this.parser = defaultInstance.getParserForType() as com.google.protobuf.Parser<T?>
        }

        @Throws(IOException::class)
        override fun read(): T? {
            return parser.parseDelimitedFrom(stream, ExtensionRegistry.getEmptyRegistry())
        }

        @Throws(IOException::class)
        override fun close() {
            stream.close()
        }
    }

    /** Reads the messages in concatenated JSON text format.  */
    class JsonInputStreamWrapper<T : Message?>(stream: java.io.InputStream?, defaultInstance: T?) :
        MessageInputStream<T?> {
        private val scanner: java.util.Scanner
        private val builderSupplier: java.util.function.Supplier<Message.Builder>

        init {
            this.scanner = java.util.Scanner(
                com.google.common.base.Preconditions.checkNotNull<java.io.InputStream?>(stream),
                java.nio.charset.StandardCharsets.UTF_8
            ).useDelimiter(
                DELIMITER
            )
            this.builderSupplier = defaultInstance::newBuilderForType
        }

        @Throws(IOException::class)
        override fun read(): T? {
            if (!scanner.hasNext()) {
                return null
            }
            val builder: Message.Builder = builderSupplier.get()
            PARSER.merge(scanner.next(), builder)
            return builder.build() as T?
        }

        @Throws(IOException::class)
        override fun close() {
            scanner.close()
        }

        companion object {
            private val PARSER: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()

            // The string `\n}{\n` is a reliable delimiter, but we must use lookbehind/lookahead to avoid
            // consuming the braces when tokenizing.
            private val DELIMITER: java.util.regex.Pattern = java.util.regex.Pattern.compile("(?<=\\n\\})(?=\\{\\n)")
        }
    }
}
