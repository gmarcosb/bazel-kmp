// Copyright 2006 The Bazel Authors.  All Rights Reserved.
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

import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import net.starlark.java.syntax.Location.column
import net.starlark.java.syntax.Location.line
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [FileLocations].  */ // TODO(adonovan): express this test in terms of the public API.
@RunWith(JUnit4::class)
class FileLocationsTest {
    @org.junit.Test
    fun testEmpty() {
        val table: net.starlark.java.syntax.FileLocations = create("")
        checkOffset(table, 0, "1:1")
    }

    @org.junit.Test
    fun testNewline() {
        val table: net.starlark.java.syntax.FileLocations = create("\n")
        checkOffset(table, 0, "1:1")
        checkOffset(table, 1, "2:1") // EOF
    }

    @org.junit.Test
    fun testOneLiner() {
        val table: net.starlark.java.syntax.FileLocations = create("foo")
        checkOffset(table, 0, "1:1")
        checkOffset(table, 1, "1:2")
        checkOffset(table, 2, "1:3")
        checkOffset(table, 3, "1:4") // EOF
    }

    @org.junit.Test
    fun testMultiLiner() {
        val table: net.starlark.java.syntax.FileLocations = create("\ntwo\nthree\n\nfive\n")

        // \n
        checkOffset(table, 0, "1:1")

        // two\n
        checkOffset(table, 1, "2:1")
        checkOffset(table, 2, "2:2")
        checkOffset(table, 3, "2:3")
        checkOffset(table, 4, "2:4")

        // three\n
        checkOffset(table, 5, "3:1")
        checkOffset(table, 10, "3:6")

        // \n
        checkOffset(table, 11, "4:1")

        // five\n
        checkOffset(table, 12, "5:1")
        checkOffset(table, 16, "5:5")

        // start of final empty line
        checkOffset(table, 17, "6:1") // EOF
    }

    companion object {
        private fun create(buffer: String): net.starlark.java.syntax.FileLocations {
            return net.starlark.java.syntax.FileLocations.create(buffer.toCharArray(), "/fake/file")
        }

        // Asserts that the specified offset results in a line/column pair of the form "1:2".
        private fun checkOffset(table: net.starlark.java.syntax.FileLocations, offset: Int, wantLineCol: String?) {
            val loc: net.starlark.java.syntax.Location = table.getLocation(offset)
            val got: String = String.format("%d:%d", loc.line(), loc.column())
            if (got != wantLineCol) {
                throw java.lang.AssertionError(
                    String.format("location(%d) = %s, want %s", offset, got, wantLineCol)
                )
            }
        }
    }
}
