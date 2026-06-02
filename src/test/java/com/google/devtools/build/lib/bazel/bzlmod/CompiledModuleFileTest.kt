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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.CompiledModuleFile.IncludeStatement

@RunWith(JUnit4::class)
class CompiledModuleFileTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkSyntax_good() {
        val program: String =
            """
        abc()
        include("hullo")
        foo = bar
        
        """.trimIndent()
        Truth.assertThat(checkSyntax(program))
            .containsExactly(
                IncludeStatement("hullo", net.starlark.java.syntax.Location.fromFileLineColumn("test file", 2, 1))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkSyntax_good_multiple() {
        val program: String =
            """
        abc()
        include("hullo")
        foo = bar
        include('world')
        
        """.trimIndent()
        Truth.assertThat(checkSyntax(program))
            .containsExactly(
                IncludeStatement("hullo", net.starlark.java.syntax.Location.fromFileLineColumn("test file", 2, 1)),
                IncludeStatement("world", net.starlark.java.syntax.Location.fromFileLineColumn("test file", 4, 1))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkSyntax_good_multilineLiteral() {
        val program: String =
            """
        abc()
        # Ludicrous as this may be, it's still valid syntax. Your funeral, etc...
        include(${'"'}""hullo
        world${'"'}"")
        
        """.trimIndent()
        Truth.assertThat(checkSyntax(program))
            .containsExactly(
                IncludeStatement(
                    "hullo\nworld",
                    net.starlark.java.syntax.Location.fromFileLineColumn("test file", 3, 1)
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkSyntax_good_benignUsageOfInclude() {
        val program: String =
            """
        myext = use_extension('whatever')
        myext.include(include="hullo")
        
        """.trimIndent()
        Truth.assertThat(checkSyntax(program)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkSyntax_good_includeIdentifierReassigned() {
        val program: String =
            """
        include('world')
        include = print
        # from this point on, we no longer check anything about `include` usage.
        include('hello')
        str(include)
        exclude = include
        
        """.trimIndent()
        Truth.assertThat(checkSyntax(program))
            .containsExactly(
                IncludeStatement("world", net.starlark.java.syntax.Location.fromFileLineColumn("test file", 1, 1))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkSyntax_bad_if() {
        val program: String =
            """
        abc()
        if d > 3:
          pass
        
        """.trimIndent()
        val ex: net.starlark.java.syntax.SyntaxError.Exception? =
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception?>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable { checkSyntax(program) })
        Truth.assertThat(ex)
            .hasMessageThat()
            .contains("`if` statements are not allowed in MODULE.bazel files")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkSyntax_bad_assignIncludeResult() {
        val program: String =
            """
        foo = include('hello')
        
        """.trimIndent()
        val ex: net.starlark.java.syntax.SyntaxError.Exception? =
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception?>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable { checkSyntax(program) })
        Truth.assertThat(ex)
            .hasMessageThat()
            .contains("the `include` directive MUST be called directly at the top-level")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkSyntax_bad_assignIncludeIdentifier() {
        val program: String =
            """
        foo = include
        foo('hello')
        
        """.trimIndent()
        val ex: net.starlark.java.syntax.SyntaxError.Exception? =
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception?>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable { checkSyntax(program) })
        Truth.assertThat(ex)
            .hasMessageThat()
            .contains("the `include` directive MUST be called directly at the top-level")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkSyntax_bad_multipleArgumentsToInclude() {
        val program: String =
            """
        include('hello', 'world')
        
        """.trimIndent()
        val ex: net.starlark.java.syntax.SyntaxError.Exception? =
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception?>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable { checkSyntax(program) })
        Truth.assertThat(ex)
            .hasMessageThat()
            .contains("the `include` directive MUST be called with exactly one positional")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkSyntax_bad_keywordArgumentToInclude() {
        val program: String =
            """
        include(label='hello')
        
        """.trimIndent()
        val ex: net.starlark.java.syntax.SyntaxError.Exception? =
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception?>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable { checkSyntax(program) })
        Truth.assertThat(ex)
            .hasMessageThat()
            .contains("the `include` directive MUST be called with exactly one positional")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkSyntax_bad_nonLiteralArgumentToInclude() {
        val program: String =
            """
        foo = 'hello'
        include(foo)
        
        """.trimIndent()
        val ex: net.starlark.java.syntax.SyntaxError.Exception? =
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception?>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable { checkSyntax(program) })
        Truth.assertThat(ex)
            .hasMessageThat()
            .contains("the `include` directive MUST be called with exactly one positional")
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun checkSyntax(str: String?): com.google.common.collect.ImmutableList<IncludeStatement?> {
            return CompiledModuleFile.checkModuleFileSyntax(
                net.starlark.java.syntax.StarlarkFile.parse(
                    net.starlark.java.syntax.ParserInput.fromString(
                        str,
                        "test file"
                    )
                )
            )
        }
    }
}
