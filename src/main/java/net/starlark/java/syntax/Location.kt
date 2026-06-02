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

import com.google.common.base.Preconditions
import java.lang.Long
import javax.annotation.concurrent.Immutable
import kotlin.Any
import kotlin.Boolean
import kotlin.Comparable
import kotlin.Int
import kotlin.String

/**
 * A Location denotes a position within a Starlark file.
 * 
 * 
 * A location is a triple `(file, line, column)`, where `file` is the apparent name
 * of the file, `line` is the optional 1-based line number, and `column` is the optional
 * 1-based column number measured in UTF-16 code units. If the column is zero it is not displayed.
 * If the line number is also zero, it too is not displayed; in this case, the location denotes the
 * file as a whole.
 */
@Immutable
class Location(file: String?, line: Int, column: Int) : Comparable<Location?> {
    private val file: String
    private val line: Int
    private val column: Int

    /** Returns the name of the file containing this location.  */
    fun file(): String {
        return file
    }

    /** Returns the line number of this location.  */
    fun line(): Int {
        return line
    }

    /** Returns the column number of this location.  */
    fun column(): Int {
        return column
    }

    /**
     * Formats the location as `"file:line:col"`. If the column is zero, it is omitted. If the
     * line is also zero, it too is omitted.
     */
    override fun toString(): String {
        val buf = StringBuilder()
        buf.append(file)
        if (line != 0) {
            buf.append(':').append(line)
            if (column != 0) {
                buf.append(':').append(column)
            }
        }
        return buf.toString()
    }

    /** Returns a three-valued lexicographical comparison of two Locations.  */
    override fun compareTo(that: Location): Int {
        val cmp = this.file().compareTo(that.file())
        if (cmp != 0) {
            return cmp
        }
        return Long.compare(
            (this.line.toLong() shl 32) or this.column.toLong(), (that.line.toLong() shl 32) or that.column.toLong()
        )
    }

    override fun hashCode(): Int {
        return 97 * file.hashCode() + 37 * line + column
    }

    override fun equals(that: Any?): Boolean {
        return this === that
                || (that is Location
                && this.file == that.file
                && this.line == that.line && this.column == that.column)
    }

    init {
        this.file = Preconditions.checkNotNull<String>(file)
        this.line = line
        this.column = column
    }

    companion object {
        /**
         * Returns a Location for the given file, line and column. If `column` is non-zero, `line` too must be non-zero.
         */
        @kotlin.jvm.JvmStatic
        fun fromFileLineColumn(file: String?, line: Int, column: Int): Location {
            Preconditions.checkArgument(line != 0 || column == 0, "non-zero column but no line number")
            return Location(file, line, column)
        }

        /** Returns a Location for the file as a whole.  */
        @kotlin.jvm.JvmStatic
        fun fromFile(file: String?): Location {
            return Location(file, 0, 0)
        }

        /** A location for built-in functions.  */
        @kotlin.jvm.JvmField
        val BUILTIN: Location = fromFile("<builtin>")
    }
}
