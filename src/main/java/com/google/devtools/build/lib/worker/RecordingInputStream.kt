// Copyright 2017 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.worker

import java.io.FilterInputStream
import java.io.IOException
import java.util.stream.Collectors

/**
 * An input stream filter that records the first X bytes read from its wrapped stream.
 * 
 * 
 * The number bytes to record can be set via [.startRecording]}, which also discards
 * any already recorded data. The recorded data can be retrieved via [ ][.getRecordedDataAsString].
 */
internal class RecordingInputStream(`in`: java.io.InputStream?) : FilterInputStream(`in`) {
    private var recordedData: java.io.ByteArrayOutputStream? = null
    private var maxRecordedSize = 0

    /**
     * Returns the maximum number of bytes that can still be recorded in our buffer (but not more
     * than `size`).
     */
    private fun getRecordableBytes(size: Int): Int {
        if (recordedData == null) {
            return 0
        }
        return java.lang.Math.min(maxRecordedSize - recordedData.size(), size)
    }

    @Throws(IOException::class)
    override fun read(): Int {
        val bytesRead: Int = super.read()
        if (getRecordableBytes(bytesRead) > 0) {
            recordedData.write(bytesRead)
        }
        return bytesRead
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray): Int {
        return this.read(b, 0, b.size)
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray?, off: Int, len: Int): Int {
        val bytesRead: Int = super.read(b, off, len)
        val recordableBytes = getRecordableBytes(bytesRead)
        if (recordableBytes > 0) {
            recordedData.write(b, off, recordableBytes)
        }
        return bytesRead
    }

    fun startRecording(maxSize: Int) {
        recordedData = java.io.ByteArrayOutputStream(maxSize)
        maxRecordedSize = maxSize
    }

    /**
     * Reads whatever remaining data is available on the input stream if we still have space left in
     * the recording buffer, in order to maximize the usefulness of the recorded data for the
     * caller.
     */
    fun readRemaining() {
        try {
            val dummy = ByteArray(getRecordableBytes(available()))
            read(dummy)
        } catch (e: IOException) {
            // Ignore.
        }
    }

    val recordedDataAsString: String?
        /**
         * Returns the recorded data as a string, where non-printable characters are replaced with a '?'
         * symbol. Or, if the data is not UTF-8, or has non-printable chars in the start,returns hex
         * values formatted similarly to `hexdump -C`
         */
        get() {
            val bytes: ByteArray = recordedData.toByteArray()
            val input = String(bytes, java.nio.charset.StandardCharsets.UTF_8)
            // TODO: Why do we get so much noise?
            if (com.google.common.base.Utf8.isWellFormed(bytes)
                && !NON_PRINTABLE_CHARS
                    .matcher(
                        input.substring(
                            0,
                            java.lang.Math.min(
                                input.length(),
                                BYTES_PER_HEX_LINE * MAX_HEX_LINES
                            )
                        )
                    )
                    .find()
            ) {
                return NON_PRINTABLE_CHARS.matcher(input).replaceAll("?")
            } else {
                val chunks: MutableList<ByteArray?> =
                    java.util.ArrayList<ByteArray?>(MAX_HEX_LINES)
                while (chunks.size() * BYTES_PER_HEX_LINE < bytes.size && chunks.size() < MAX_HEX_LINES) {
                    chunks.add(
                        java.util.Arrays.copyOfRange(
                            bytes,
                            chunks.size() * BYTES_PER_HEX_LINE,
                            java.lang.Math.min(
                                (1 + chunks.size()) * BYTES_PER_HEX_LINE,
                                bytes.size
                            )
                        )
                    )
                }
                val isTruncated =
                    bytes.size > BYTES_PER_HEX_LINE * MAX_HEX_LINES
                val lines: MutableList<String?> = chunks.stream()
                    .map<String?>(java.util.function.Function { bytes: ByteArray? -> this.formatHexLine(bytes!!) })
                    .collect(Collectors.toList())
                return java.lang.String.format(
                    "Not UTF-8, printing %sas hex\n%s\n",
                    (if (isTruncated) "first 1024 bytes " else ""), com.google.common.base.Joiner.on('\n').join(lines)
                )
            }
        }

    /** Formats a single array of 16 bytes as a hexdump-style line.  */
    private fun formatHexLine(bytes: ByteArray): String? {
        val rawHex: String = com.google.common.io.BaseEncoding.base16().encode(bytes)
        // Adds spaces between hex representation of each char
        val separatedHex: String =
            com.google.common.base.Joiner.on(' ').join(com.google.common.base.Splitter.fixedLength(2).split(rawHex))
        // Adds extra space between each block of 8 hex bytes (two hex chars and one space each).
        val groupedHex: String =
            com.google.common.base.Joiner.on(' ')
                .join(com.google.common.base.Splitter.fixedLength(3 * BYTES_PER_HEX_BLOCK).split(separatedHex))
        // Adds ASCII-safe display of text on the right
        val textDisplay: String =
            com.google.common.primitives.Bytes.asList(*bytes).stream()
                .map<String?>(java.util.function.Function { b: Byte? ->
                    if (b!! >= 32) java.lang.Character.toString(
                        Char(
                            (b as Byte).toUShort()
                        )
                    ) else "."
                })
                .collect(Collectors.joining())
        // Adds space in text display between blocks of 8 hex bytes.
        val splitText: String =
            com.google.common.base.Joiner.on(' ')
                .join(com.google.common.base.Splitter.fixedLength(BYTES_PER_HEX_BLOCK).split(textDisplay))
        return java.lang.String.format("%-50s|%-17s|", groupedHex, splitText)
    }

    companion object {
        private val NON_PRINTABLE_CHARS: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("[^\\p{Print}\\t\\r\\n]", java.util.regex.Pattern.UNICODE_CHARACTER_CLASS)

        /** In hexdump output, the maximum number of lines to output.  */
        private const val MAX_HEX_LINES = 64

        /** In hexdump output, the number of bytes that fit on one line.  */
        private const val BYTES_PER_HEX_LINE = 16

        /** In hexdump output, the number of bytes that is grouped together in blocks.  */
        private const val BYTES_PER_HEX_BLOCK = 8
    }
}
