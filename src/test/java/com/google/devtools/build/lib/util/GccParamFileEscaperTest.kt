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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.util.GccParamFileEscaper.escapeString

/** Tests for [GccParamFileEscaper].  */
@RunWith(JUnit4::class)
class GccParamFileEscaperTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEscapeString() {
        assertThat(escapeString("")).isEqualTo("''")
        assertThat(escapeString("foo")).isEqualTo("foo")
        assertThat(escapeString("'foo'")).isEqualTo("\\'foo\\'")
        assertThat(escapeString("\"foo\"")).isEqualTo("\\\"foo\\\"")
        assertThat(escapeString("\\foo")).isEqualTo("\\\\foo")
        assertThat(escapeString("foo bar")).isEqualTo("foo\\ bar")
        assertThat(escapeString("foo\tbar")).isEqualTo("foo\\\tbar")
        assertThat(escapeString("foo\rbar")).isEqualTo("foo\\\rbar")
        assertThat(escapeString("foo\n'foo'\n")).isEqualTo("foo\\\n\\'foo\\'\\\n")
        assertThat(escapeString("foo\u000cbar")).isEqualTo("foo\\\u000cbar")
        assertThat(escapeString("foo\u000Bbar")).isEqualTo("foo\\\u000Bbar")
        assertThat(escapeString("\${filename%.c}.o")).isEqualTo("\${filename%.c}.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEscapeAll() {
        val escaped: MutableSet<String?> =
            com.google.common.collect.ImmutableSet.copyOf(
                GccParamFileEscaper.escapeAll(
                    mutableListOf<T?>(
                        "foo",
                        "'foo'",
                        "foo\n"
                    )
                )
            )
        Truth.assertThat(escaped).containsExactly("foo", "\\'foo\\'", "foo\\\n")
    }
}
