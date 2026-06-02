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

import java.util.*
import javax.annotation.concurrent.Immutable

/**
 * FileLocations maps each source offset within a file to a Location. An offset is a (UTF-16) char
 * index such that `0 <= offset <= size`. A Location is a (file, line, column) triple.
 */
@Immutable
internal class FileLocations private constructor(linestart: IntArray, file: String, size: Int) {
    private val linestart: IntArray // maps line number (line >= 1) to char offset
    private val file: String
    private val size: Int // size of file in chars

    init {
        this.linestart = linestart
        this.file = file
        this.size = size
    }

    fun file(): String {
        return file
    }

    private fun getLineAt(offset: Int): Int {
        check(!(offset < 0 || offset > size)) { "Illegal position: " + offset }
        var lowBoundary = 1
        var highBoundary = linestart.size - 1
        while (true) {
            if ((highBoundary - lowBoundary) <= 1) {
                if (linestart[highBoundary] > offset) {
                    return lowBoundary
                } else {
                    return highBoundary
                }
            }
            val medium = lowBoundary + ((highBoundary - lowBoundary) shr 1)
            if (linestart[medium] > offset) {
                highBoundary = medium
            } else {
                lowBoundary = medium
            }
        }
    }

    fun getLocation(offset: Int): Location {
        val line = getLineAt(offset)
        val column = offset - linestart[line] + 1
        return Location(file, line, column)
    }

    fun size(): Int {
        return size
    }

    override fun hashCode(): Int {
        return Objects.hash(linestart.contentHashCode(), file, size)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is FileLocations) {
            return false
        }
        val that = other
        return this.size == that.size && this.linestart.contentEquals(that.linestart) && this.file == that.file
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun create(buffer: CharArray, file: String): FileLocations {
            return FileLocations(computeLinestart(buffer), file, buffer.size)
        }

        private fun computeLinestart(buffer: CharArray): IntArray {
            // Compute the size.
            var size = 2
            for (i in buffer.indices) {
                if (buffer[i] == '\n') {
                    size++
                }
            }
            val linestart = IntArray(size)

            var index = 0
            linestart[index++] = 0 // The 0th line does not exist - so we fill something in
            // to make sure the start pos for the 1st line ends up at
            // linestart[1]. Using 0 is useful for tables that are
            // completely empty.
            linestart[index++] = 0 // The first line ("line 1") starts at offset 0.

            // Scan the buffer and record the offset of each line start. Doing this
            // once upfront is faster than checking each char as it is pulled from
            // the buffer.
            for (i in buffer.indices) {
                if (buffer[i] == '\n') {
                    linestart[index++] = i + 1
                }
            }
            return linestart
        }
    }
}
