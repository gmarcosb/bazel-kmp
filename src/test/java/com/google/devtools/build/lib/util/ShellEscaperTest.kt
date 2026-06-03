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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.util.ShellEscaper.escapeString

/**
 * Tests for [ShellEscaper].
 * 
 * 
 * Based on `com.google.io.base.shell.ShellUtilsTest`.
 */
@RunWith(JUnit4::class)
class ShellEscaperTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shellEscape() {
        assertThat(escapeString("")).isEqualTo("''")
        assertThat(escapeString("foo")).isEqualTo("foo")
        assertThat(escapeString("foo bar")).isEqualTo("'foo bar'")
        assertThat(escapeString("'foo'")).isEqualTo("''\\''foo'\\'''")
        assertThat(escapeString("\\'foo\\'")).isEqualTo("'\\'\\''foo\\'\\'''")
        assertThat(escapeString("\${filename%.c}.o")).isEqualTo("'\${filename%.c}.o'")
        assertThat(escapeString("<html!>")).isEqualTo("'<html!>'")
        assertThat(escapeString("~not_home")).isEqualTo("'~not_home'")
        assertThat(escapeString("external/protobuf+3.19.6/src/goo~gle"))
            .isEqualTo("external/protobuf+3.19.6/src/goo~gle")
        assertThat(escapeString("external/+install_dev_dependencies+foo/pkg"))
            .isEqualTo("external/+install_dev_dependencies+foo/pkg")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun escapeAll() {
        val escaped: MutableSet<String?> = com.google.common.collect.ImmutableSet.copyOf(
            ShellEscaper.escapeAll(mutableListOf<T?>("foo", "@bar", "baz'qux"))
        )
        Truth.assertThat(escaped).containsExactly("foo", "@bar", "'baz'\\''qux'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun escapeJoinAllIntoAppendable() {
        val appendable: java.lang.Appendable = ShellEscaper.escapeJoinAll(
            java.lang.StringBuilder("initial"), mutableListOf<T?>("foo", "\$BAR")
        )
        Truth.assertThat(appendable.toString()).isEqualTo("initialfoo '\$BAR'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun escapeJoinAllIntoAppendableWithCustomJoiner() {
        val appendable: java.lang.Appendable = ShellEscaper.escapeJoinAll(
            java.lang.StringBuilder("initial"), mutableListOf<T?>("foo", "\$BAR"), com.google.common.base.Joiner.on('|')
        )
        Truth.assertThat(appendable.toString()).isEqualTo("initialfoo|'\$BAR'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun escapeJoinAll() {
        val actual: String? = ShellEscaper.escapeJoinAll(
            mutableListOf<T?>("foo", "@echo:-", "100", "\$US", "a b", "\"qu'ot'es\"", "\"quot\"", "\\")
        )
        Truth.assertThat(actual)
            .isEqualTo("foo @echo:- 100 '\$US' 'a b' '\"qu'\\''ot'\\''es\"' '\"quot\"' '\\'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun escapeJoinAllWithCustomJoiner() {
        val actual: String? = ShellEscaper.escapeJoinAll(
            mutableListOf<T?>("foo", "@echo:-", "100", "\$US", "a b", "\"qu'ot'es\"", "\"quot\"", "\\"),
            com.google.common.base.Joiner.on('|')
        )
        Truth.assertThat(actual)
            .isEqualTo("foo|@echo:-|100|'\$US'|'a b'|'\"qu'\\''ot'\\''es\"'|'\"quot\"'|'\\'")
    }
}
