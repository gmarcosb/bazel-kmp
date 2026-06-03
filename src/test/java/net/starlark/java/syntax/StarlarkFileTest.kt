// Copyright 2006 The Bazel Authors. All rights reserved.
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

import com.google.common.truth.Truth
import net.starlark.java.syntax.SyntaxError.Exception.errors
import net.starlark.java.syntax.SyntaxError.location
import net.starlark.java.syntax.TypeTable.errors
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests of StarlarkFile parsing.  */ // TODO(adonovan): move tests of parsing into ParserTest.
@RunWith(JUnit4::class)
class StarlarkFileTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParsesFineWithNewlines() {
        val file: net.starlark.java.syntax.StarlarkFile = parseFile("foo()", "bar()", "something = baz()", "bar()")
        Truth.assertThat(file.getStatements()).hasSize(4)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFailsIfNewlinesAreMissing() {
        val file: net.starlark.java.syntax.StarlarkFile = parseFile("foo() bar() something = baz() bar()")

        val error: net.starlark.java.syntax.SyntaxError =
            net.starlark.java.syntax.TestUtils.assertContainsError(
                file.errors(),
                "syntax error at \'bar\': expected newline"
            )
        Truth.assertThat(error.location().toString()).isEqualTo("foo.star:1:7")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplicitStringConcatenationFails() {
        // TODO(adonovan): move to ParserTest.
        val file: net.starlark.java.syntax.StarlarkFile = parseFile("a = 'foo' 'bar'")
        val error: net.starlark.java.syntax.SyntaxError =
            net.starlark.java.syntax.TestUtils.assertContainsError(
                file.errors(), "Implicit string concatenation is forbidden, use the + operator"
            )
        Truth.assertThat(error.location().toString()).isEqualTo("foo.star:1:11") // start of 'bar'
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplicitStringConcatenationAcrossLinesIsIllegal() {
        val file: net.starlark.java.syntax.StarlarkFile = parseFile("a = 'foo'\n  'bar'")

        val error: net.starlark.java.syntax.SyntaxError =
            net.starlark.java.syntax.TestUtils.assertContainsError(file.errors(), "indentation error")
        Truth.assertThat(error.location().toString()).isEqualTo("foo.star:2:2")
    }

    companion object {
        /**
         * Parses the contents of the specified string (using 'foo.star' as the apparent filename) and
         * returns the AST. Resets the error handler beforehand.
         */
        private fun parseFile(vararg lines: String?): net.starlark.java.syntax.StarlarkFile {
            val src: String = com.google.common.base.Joiner.on("\n").join(lines)
            val input: net.starlark.java.syntax.ParserInput? =
                net.starlark.java.syntax.ParserInput.fromString(src, "foo.star")
            return net.starlark.java.syntax.StarlarkFile.parse(input)
        }
    }
}
