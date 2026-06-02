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
package net.starlark.java.syntax

import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/**
 * The apparent name and contents of a source file, for consumption by the parser. The file name
 * appears in the location information in the syntax tree, and in error messages, but the Starlark
 * interpreter will not attempt to open the file. However, the default behavior of [ ][EvalException.getMessageWithStack] attempts to read the specified file when formatting a stack
 * trace.
 * 
 * 
 * The parser consumes a stream of chars (UTF-16 codes), and the syntax positions reported by
 * [Node.getStartOffset] and [Location.column] are effectively indices into a char
 * array.
 */
class ParserInput private constructor(content: CharArray?, file: String?) {
    @kotlin.jvm.JvmField
    private val file: String
    @kotlin.jvm.JvmField
    private val content: CharArray?

    init {
        this.content = content
        this.file = Preconditions.checkNotNull<String>(file)
    }

    /** Returns the content of the input source. Callers must not modify the result.  */
    fun getContent(): CharArray? {
        return content
    }

    /** Returns the apparent file name of the input source.  */
    fun getFile(): String {
        return file
    }

    companion object {
        /**
         * Returns an input source that uses the name and content of the specified UTF-8-encoded text
         * file.
         */
        @kotlin.jvm.JvmStatic
        @Throws(IOException::class)
        fun readFile(file: String?): ParserInput {
            val utf8 = Files.readAllBytes(Paths.get(file))
            return fromUTF8(utf8, file)
        }

        /** Returns an unnamed input source that reads from a list of strings, joined by newlines.  */
        @kotlin.jvm.JvmStatic
        fun fromLines(vararg lines: String?): ParserInput {
            return fromString(Joiner.on("\n").join(lines), "")
        }

        /**
         * Returns an input source that reads from a UTF-8-encoded byte array. The caller is free to
         * subsequently mutate the array.
         */
        fun fromUTF8(bytes: ByteArray, file: String?): ParserInput {
            val cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes))
            val utf16 = CharArray(cb.length)
            cb.get(utf16)
            return fromCharArray(utf16, file)
        }

        /**
         * Returns an input source that reads from a Latin1-encoded byte array. The caller is free to
         * subsequently mutate the array.
         * 
         */
        @kotlin.jvm.JvmStatic
        @Deprecated(
            """This function exists to support legacy uses of Latin1 in Bazel. Do not use Latin1
        in new applications."""
        )
        fun fromLatin1(bytes: ByteArray, file: String?): ParserInput {
            val chars = CharArray(bytes.size)
            for (i in bytes.indices) {
                chars[i] = (0xff and bytes[i].toInt()).toChar()
            }
            return ParserInput(chars, file)
        }

        /** Returns an input source that reads from the given string.  */
        @kotlin.jvm.JvmStatic
        fun fromString(content: String, file: String?): ParserInput {
            return fromCharArray(content.toCharArray(), file)
        }

        /**
         * Returns an input source that reads from the given char array. The caller must not subsequently
         * modify the array.
         */
        @kotlin.jvm.JvmStatic
        fun fromCharArray(content: CharArray?, file: String?): ParserInput {
            return ParserInput(content, file)
        }
    }
}
