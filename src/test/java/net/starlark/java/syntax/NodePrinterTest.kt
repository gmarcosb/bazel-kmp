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
package net.starlark.java.syntax

import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import net.starlark.java.syntax.SyntaxError.Exception.errors
import net.starlark.java.syntax.TypeTable.errors
import net.starlark.java.syntax.TypeTable.ok
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests [Node.toString] and `NodePrinter`.  */
@RunWith(JUnit4::class)
class NodePrinterTest {
    private var fileOptions: net.starlark.java.syntax.FileOptions? = net.starlark.java.syntax.FileOptions.DEFAULT

    private fun setFileOptions(fileOptions: net.starlark.java.syntax.FileOptions?) {
        this.fileOptions = fileOptions
    }

    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun parseFile(vararg lines: String?): net.starlark.java.syntax.StarlarkFile {
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(*lines)
        val file: net.starlark.java.syntax.StarlarkFile =
            net.starlark.java.syntax.StarlarkFile.parse(input, fileOptions)
        if (!file.ok()) {
            throw net.starlark.java.syntax.SyntaxError.Exception(file.errors())
        }
        return file
    }

    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun parseStatement(vararg lines: String?): net.starlark.java.syntax.Statement {
        return parseFile(*lines).getStatements().get(0)
    }

    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun parseExpression(vararg lines: String?): net.starlark.java.syntax.Expression {
        return net.starlark.java.syntax.Expression.parse(
            net.starlark.java.syntax.ParserInput.fromLines(*lines),
            fileOptions
        )
    }

    /**
     * Parses the given string as an expression, and asserts that its pretty print matches the given
     * string.
     */
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun assertExprPrettyMatches(source: String?, expected: String?) {
        val node: net.starlark.java.syntax.Expression = parseExpression(source)
        assertPrettyMatches(node, expected)
    }

    /**
     * Parses the given string as an expression, and asserts that its `toString` matches the
     * given string.
     */
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun assertExprTostringMatches(source: String?, expected: String?) {
        val node: net.starlark.java.syntax.Expression = parseExpression(source)
        Truth.assertThat(node.toString()).isEqualTo(expected)
    }

    /**
     * Parses the given string as an expression, and asserts that both its pretty print and `toString` return the original string.
     */
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun assertExprBothRoundTrip(source: String?) {
        assertExprPrettyMatches(source, source)
        assertExprTostringMatches(source, source)
    }

    /**
     * Parses the given string as a statement, and asserts that its pretty print with one indent
     * matches the given string.
     */
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun assertStmtIndentedPrettyMatches(source: String?, expected: String?) {
        val node: net.starlark.java.syntax.Statement = parseStatement(source)
        assertIndentedPrettyMatches(node, expected)
    }

    /**
     * Parses the given string as an statement, and asserts that its `toString` matches the
     * given string.
     */
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun assertStmtTostringMatches(source: String?, expected: String?) {
        val node: net.starlark.java.syntax.Statement = parseStatement(source)
        Truth.assertThat(node.toString()).isEqualTo(expected)
    }

    // Expressions.
    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun abstractComprehension() {
        // Covers DictComprehension and ListComprehension.
        assertExprBothRoundTrip("[z for y in x if True for z in y]")
        assertExprBothRoundTrip("{z: x for y in x if True for z in y}")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun binaryOperatorExpression() {
        assertExprPrettyMatches("1 + 2", "(1 + 2)")
        assertExprTostringMatches("1 + 2", "1 + 2")

        assertExprPrettyMatches("1 + (2 * 3)", "(1 + (2 * 3))")
        assertExprTostringMatches("1 + (2 * 3)", "1 + 2 * 3")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun conditionalExpression() {
        assertExprBothRoundTrip("1 if True else 2")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun dictExpression() {
        assertExprBothRoundTrip("{1: \"a\", 2: \"b\"}")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun dotExpression() {
        assertExprBothRoundTrip("o.f")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun funcallExpression() {
        assertExprBothRoundTrip("f()")
        assertExprBothRoundTrip("f(a)")
        assertExprBothRoundTrip("f(a, b = B, c = C, *d, **e)")
        assertExprBothRoundTrip("o.f()")
        assertExprBothRoundTrip("f(1 + 1)")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun identifier() {
        assertExprBothRoundTrip("foo")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun indexExpression() {
        assertExprBothRoundTrip("a[i]")
        assertExprBothRoundTrip("a[(1,)]")
        assertExprPrettyMatches("a[1,2]", "a[(1, 2)]")
        assertExprTostringMatches("a[1,2]", "a[(1, 2)]")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun integerLiteral() {
        assertExprBothRoundTrip("5")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun listLiteralShort() {
        assertExprBothRoundTrip("[]")
        assertExprBothRoundTrip("[5]")
        assertExprBothRoundTrip("[5, 6]")
        assertExprBothRoundTrip("()")
        assertExprBothRoundTrip("(5,)")
        assertExprBothRoundTrip("(5, 6)")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun listLiteralLong() {
        // List literals with enough elements to trigger the abbreviated toString() format.
        assertExprPrettyMatches("[1, 2, 3, 4, 5, 6]", "[1, 2, 3, 4, 5, 6]")
        assertExprTostringMatches("[1, 2, 3, 4, 5, 6]", "[1, 2, 3, 4, +2 more]")

        assertExprPrettyMatches("(1, 2, 3, 4, 5, 6)", "(1, 2, 3, 4, 5, 6)")
        assertExprTostringMatches("(1, 2, 3, 4, 5, 6)", "(1, 2, 3, 4, +2 more)")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun listLiteralNested() {
        // Make sure that the inner list doesn't get abbreviated when the outer list is printed using
        // prettyPrint().
        assertExprPrettyMatches(
            "[1, 2, 3, [10, 20, 30, 40, 50, 60], 4, 5, 6]",
            "[1, 2, 3, [10, 20, 30, 40, 50, 60], 4, 5, 6]"
        )
        // It doesn't matter as much what toString does.
        assertExprTostringMatches("[1, 2, 3, [10, 20, 30, 40, 50, 60], 4, 5, 6]", "[1, 2, 3, +4 more]")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun sliceExpression() {
        assertExprBothRoundTrip("a[b:c:d]")
        assertExprBothRoundTrip("a[b:c]")
        assertExprBothRoundTrip("a[b:]")
        assertExprBothRoundTrip("a[:c:d]")
        assertExprBothRoundTrip("a[:c]")
        assertExprBothRoundTrip("a[::d]")
        assertExprBothRoundTrip("a[:]")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun stringLiteral() {
        assertExprBothRoundTrip("\"foo\"")
        assertExprBothRoundTrip("\"quo\\\"ted\"")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun unaryOperatorExpression() {
        assertExprPrettyMatches("not True", "not (True)")
        assertExprTostringMatches("not True", "not True")
        assertExprPrettyMatches("-(5 + 3)", "-((5 + 3))")
        assertExprTostringMatches("-5", "-5")
    }

    // Statements.
    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun assignmentStatement() {
        assertStmtIndentedPrettyMatches("x = y", "  x = y\n")
        assertStmtTostringMatches("x = y", "x = y\n")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun augmentedAssignmentStatement() {
        assertStmtIndentedPrettyMatches("x += y", "  x += y\n")
        assertStmtTostringMatches("x += y", "x += y\n")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun assignmentStatementWithTypeAnnotation() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        assertStmtIndentedPrettyMatches("x : T = y", "  x : T = y\n")
        assertStmtTostringMatches("x : T = y", "x : T = y\n")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun expressionStatement() {
        assertStmtIndentedPrettyMatches("5", "  5\n")
        assertStmtTostringMatches("5", "5\n")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun defStatement() {
        assertStmtIndentedPrettyMatches(
            """
        def f(x):
          print(x)
          """.trimIndent(),
            """
          def f(x):
            print(x)
        
        """.trimIndent()
        )
        assertStmtTostringMatches(
            """
        def f(x):
          print(x)
          """.trimIndent(),
            "def f(x): ...\n"
        )

        assertStmtIndentedPrettyMatches(
            """
        def f(a, b=B, *c, d=D, **e):
          print(x)
          """.trimIndent(),
            """
          def f(a, b=B, *c, d=D, **e):
            print(x)
        
        """.trimIndent()
        )
        assertStmtTostringMatches(
            """
        def f(a, b=B, *c, d=D, **e):
          print(x)
          """.trimIndent(),
            "def f(a, b=B, *c, d=D, **e): ...\n"
        )

        assertStmtIndentedPrettyMatches(
            """
        def f():
          pass
          """.trimIndent(),
            """
          def f():
            pass
        
        """.trimIndent()
        )
        assertStmtTostringMatches(
            """
        def f():
          pass
          """.trimIndent(),
            "def f(): ...\n"
        )
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun defStatementWithTypeAnnotations() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        assertStmtIndentedPrettyMatches(
            """
        def f(x:int):
          print(x)
          """.trimIndent(),
            """
          def f(x: int):
            print(x)
        
        """.trimIndent()
        )
        assertStmtTostringMatches(
            """
        def f(x:bool):
          print(x)
          """.trimIndent(),
            "def f(x: bool): ...\n"
        )

        assertStmtIndentedPrettyMatches(
            """
        def f()->int:
          print(x)
          """.trimIndent(),
            """
          def f() -> int:
            print(x)
        
        """.trimIndent()
        )
        assertStmtTostringMatches(
            """
        def f() -> bool:
          print(x)
        
        """.trimIndent(),
            "def f() -> bool: ...\n"
        )
        assertStmtIndentedPrettyMatches(
            """
        def f[T,U,](x:dict[T,U])->list[U]:
          print(x)
          """.trimIndent(),
            """
          def f[T, U](x: dict[T, U]) -> list[U]:
            print(x)
        
        """.trimIndent()
        )
        assertStmtTostringMatches(
            """
        def f[T,U,](x:dict[T,U]|set[U]) -> bool:
          print(x)
        
        """.trimIndent(),
            "def f[T, U](x: dict[T, U] | set[U]) -> bool: ...\n"
        )
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun typeAnnotations() {
        // TODO(ilist@): replace with parsing type annotations directly (remove `def` from this test)
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        assertStmtTostringMatches("def f(x:bool): pass", "def f(x: bool): ...\n")
        assertStmtTostringMatches("def f(x:None | bool): pass", "def f(x: None | bool): ...\n")
        assertStmtTostringMatches("def f(x:list[str]): pass", "def f(x: list[str]): ...\n")
        assertStmtTostringMatches("def f(x:dict[str,int]): pass", "def f(x: dict[str, int]): ...\n")
        assertStmtTostringMatches(
            "def f(x:Callable[[str],int]): pass", "def f(x: Callable[[str], int]): ...\n"
        )
        assertStmtTostringMatches(
            "def f(x:Callable[[str|int],int]): pass", "def f(x: Callable[[str | int], int]): ...\n"
        )
        assertStmtTostringMatches(
            "def f(x:TypedDict[{'field1': int}]): pass",
            "def f(x: TypedDict[{\"field1\": int}]): ...\n"
        )
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun typeAliasStatement() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        assertStmtTostringMatches("type my_int=int", "type my_int = ...\n")
        assertStmtIndentedPrettyMatches("type my_int=int", "  type my_int = int\n")
        assertStmtTostringMatches("type X[T,U]=dict[T,U]|list[U]", "type X[T, U] = ...\n")
        assertStmtIndentedPrettyMatches(
            "type X[T,U]=dict[T,U]|list[U]", "  type X[T, U] = dict[T, U] | list[U]\n"
        )
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun ellipsisExpression() {
        setFileOptions(
            net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).tolerateInvalidTypeExpressions(true)
                .build()
        )
        // Use `def` rather than `type` to wrap the type expression, because `type`'s toString()
        // introduces its own metasyntactic "..." placeholder.
        assertStmtTostringMatches(
            "def f(x:Callable[...,int]): pass", "def f(x: Callable[(..., int)]): ...\n"
        )
        assertStmtIndentedPrettyMatches("type x=...", "  type x = ...\n")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun castExpression() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        assertExprPrettyMatches("cast(list[int]|str,x+y)", "cast(list[int] | str, x + y)")
        assertExprTostringMatches("cast(set|None,bar(),)", "cast(set | None, bar())")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun isinstanceExpression() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        assertExprPrettyMatches("isinstance(x+y, list|tuple)", "isinstance(x + y, list | tuple)")
        assertExprTostringMatches("isinstance(foo(), T[U],)", "isinstance(foo(), T[U])")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun flowStatement() {
        assertStmtIndentedPrettyMatches(
            """
        def f():
             pass
             continue
             break
             """.trimIndent(),
            """
          def f():
            pass
            continue
            break
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun forStatement() {
        assertStmtIndentedPrettyMatches(
            """
        for x in y:
          print(x)
          """.trimIndent(),
            """
          for x in y:
            print(x)
        
        """.trimIndent()
        )
        assertStmtTostringMatches(
            """
        for x in y:
          print(x)
          """.trimIndent(),
            "for x in y: ...\n"
        )

        assertStmtIndentedPrettyMatches(
            """
        for x in y:
          pass
          """.trimIndent(),
            """
          for x in y:
            pass
        
        """.trimIndent()
        )
        assertStmtTostringMatches(
            """
        for x in y:
          pass
          """.trimIndent(),
            "for x in y: ...\n"
        )
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun ifStatement() {
        assertStmtIndentedPrettyMatches(
            """
        if True:
          print(x)
          """.trimIndent(),
            """
          if True:
            print(x)
        
        """.trimIndent()
        )
        assertStmtTostringMatches(
            """
        if True:
          print(x)
          """.trimIndent(),
            "if True: ...\n"
        )

        assertStmtIndentedPrettyMatches(
            """
        if True:
          print(x)
        elif False:
          print(y)
        else:
          print(z)
          """.trimIndent(),
            """
          if True:
            print(x)
          elif False:
            print(y)
          else:
            print(z)
        
        """.trimIndent()
        )
        assertStmtTostringMatches(
            """
        if True:
          print(x)
        elif False:
          print(y)
        else:
          print(z)
          """.trimIndent(),
            "if True: ...\n"
        )
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun loadStatement() {
        assertStmtIndentedPrettyMatches(
            "load(\"foo.bzl\", a=\"A\", \"B\")", "  load(\"foo.bzl\", a=\"A\", \"B\")\n"
        )
        assertStmtTostringMatches(
            "load(\"foo.bzl\", a=\"A\", \"B\")\n", "load(\"foo.bzl\", a=\"A\", \"B\")\n"
        )
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun varStatement() {
        setFileOptions(net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build())
        assertStmtIndentedPrettyMatches("x : T", "  x : T\n")
        assertStmtTostringMatches("x : T\n", "x : T\n")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun returnStatement() {
        assertStmtIndentedPrettyMatches("return \"foo\"", "  return \"foo\"\n")
        assertStmtTostringMatches("return \"foo\"", "return \"foo\"\n")

        assertStmtIndentedPrettyMatches("return None", "  return None\n")
        assertStmtTostringMatches("return None", "return None\n")

        assertStmtIndentedPrettyMatches("return", "  return\n")
        assertStmtTostringMatches("return", "return\n")
    }

    // Miscellaneous.
    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun file() {
        val node: net.starlark.java.syntax.Node = parseFile("print(x)\nprint(y)")
        assertIndentedPrettyMatches(
            node,
            """
          print(x)
          print(y)
        
        """.trimIndent()
        )
        assertTostringMatches(node, "<StarlarkFile with 2 statements>")
    }

    @org.junit.Test
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun comment() {
        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines(
                "# foo",  //
                "expr # bar"
            )
        val r: net.starlark.java.syntax.Parser.ParseResult =
            net.starlark.java.syntax.Parser.parseFile(input, net.starlark.java.syntax.FileOptions.DEFAULT)
        val c0: net.starlark.java.syntax.Comment = r.comments.get(0)
        assertIndentedPrettyMatches(c0, "  # foo")
        assertTostringMatches(c0, "# foo")
        val c1: net.starlark.java.syntax.Comment = r.comments.get(1)
        assertIndentedPrettyMatches(c1, "  # bar")
        assertTostringMatches(c1, "# bar")
    } /* Not tested explicitly because they're covered implicitly by tests for other nodes:
   * - DictExpression.Entry
   * - Argument / Parameter
   * - IfStatements
   */

    companion object {
        /**
         * Asserts that the given node's pretty print at a given indent level matches the given string.
         */
        private fun assertPrettyMatches(node: net.starlark.java.syntax.Node?, indentLevel: Int, expected: String?) {
            val buf: java.lang.StringBuilder = java.lang.StringBuilder()
            net.starlark.java.syntax.NodePrinter(buf, indentLevel).printNode(node)
            Truth.assertThat(buf.toString()).isEqualTo(expected)
        }

        /** Asserts that the given node's pretty print with no indent matches the given string.  */
        private fun assertPrettyMatches(node: net.starlark.java.syntax.Node?, expected: String?) {
            assertPrettyMatches(node, 0, expected)
        }

        /** Asserts that the given node's pretty print with one indent matches the given string.  */
        private fun assertIndentedPrettyMatches(node: net.starlark.java.syntax.Node?, expected: String?) {
            assertPrettyMatches(node, 1, expected)
        }

        /** Asserts that the given node's `toString` matches the given string.  */
        private fun assertTostringMatches(node: net.starlark.java.syntax.Node, expected: String?) {
            Truth.assertThat(node.toString()).isEqualTo(expected)
        }
    }
}
