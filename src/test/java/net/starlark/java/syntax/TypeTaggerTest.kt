// Copyright 2025 The Bazel Authors. All Rights Reserved.
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

import com.google.common.truth.BooleanSubject
import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.add
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.allowToplevelRebinding
import net.starlark.java.syntax.FileOptions.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.loadBindsGlobally
import net.starlark.java.syntax.Program.getResolvedFunction
import net.starlark.java.syntax.SyntaxError.Exception.errors
import net.starlark.java.syntax.TypeTable.errors
import net.starlark.java.syntax.TypeTable.getType
import net.starlark.java.syntax.TypeTable.ok
import net.starlark.java.syntax.TypeTaggerTest
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [TypeTagger].  */
@RunWith(JUnit4::class)
class TypeTaggerTest {
    private var options: net.starlark.java.syntax.FileOptions.Builder =
        net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).resolveTypeSyntax(true)

    private var module: net.starlark.java.syntax.Resolver.Module =
        net.starlark.java.syntax.TestUtils.Module.Companion.withUniversalTypesAnd(
            "struct",
            net.starlark.java.syntax.Types.STRUCT_CONSTRUCTOR
        )

    private var loader: net.starlark.java.syntax.TypeTagger.Loader? = null

    /** Extracts an expression string to a type in an empty environment.  */
    @Throws(java.lang.Exception::class)
    private fun extractType(type: String?): net.starlark.java.syntax.StarlarkType? {
        val expr: net.starlark.java.syntax.Expression? = net.starlark.java.syntax.Expression.parseTypeExpression(
            net.starlark.java.syntax.ParserInput.fromLines(type),
            options.build()
        )
        val function: net.starlark.java.syntax.Resolver.Function? =
            net.starlark.java.syntax.Resolver.resolveExpr(expr, module, options.build())
        return net.starlark.java.syntax.TypeTagger.extractType(expr, function, module)
    }

    /**
     * Asserts that attempting to extract an expression string to a type fails, with a syntax
     * exception whose message exactly matches the expected string.
     */
    @Throws(java.lang.Exception::class)
    private fun assertExtractTypeFails(type: String?, expectedMessage: String?) {
        val e: net.starlark.java.syntax.SyntaxError.Exception? =
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception?>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable { extractType(type) })
        Truth.assertThat(e).hasMessageThat().isEqualTo(expectedMessage)
    }

    private class Result(file: net.starlark.java.syntax.StarlarkFile?, typeTable: net.starlark.java.syntax.TypeTable) {
        /** Returns the type of an identifier.  */
        fun getType(id: net.starlark.java.syntax.Identifier): net.starlark.java.syntax.StarlarkType? {
            Truth.assertThat(id.getBinding()).isNotNull()
            return this.typeTable.getType(id.getBinding())
        }

        fun getType(function: net.starlark.java.syntax.Resolver.Function?): net.starlark.java.syntax.Types.CallableType? {
            return this.typeTable.getType(function)
        }

        /** Returns the type of a `def`'s resolved function.  */
        fun getType(def: net.starlark.java.syntax.DefStatement): net.starlark.java.syntax.Types.CallableType? {
            Truth.assertThat(def.getResolvedFunction()).isNotNull()
            return getType(def.getResolvedFunction())
        }

        /** Returns the type of a `lambda`'s resolved function.  */
        fun getType(lambda: net.starlark.java.syntax.LambdaExpression): net.starlark.java.syntax.Types.CallableType? {
            Truth.assertThat(lambda.getResolvedFunction()).isNotNull()
            return getType(lambda.getResolvedFunction())
        }

        val file: net.starlark.java.syntax.StarlarkFile?
        val typeTable: net.starlark.java.syntax.TypeTable

        init {
            this.file = file
            this.typeTable = typeTable
        }
    }

    /**
     * Parses a series of strings as a file, then resolves and type-tags it.
     * 
     * 
     * Asserts that parsing and symbol resolution succeeded, but type-tagging may fail.
     */
    @Throws(java.lang.Exception::class)
    private fun tagFilePossiblyFailing(vararg lines: String?): Result {
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(*lines)
        val file: net.starlark.java.syntax.StarlarkFile =
            net.starlark.java.syntax.StarlarkFile.parse(input, options.build())
        Truth.assertThat(file.errors()).isEmpty()
        net.starlark.java.syntax.Resolver.resolveFile(file, module)
        Truth.assertThat(file.errors()).isEmpty()
        val typeTable: net.starlark.java.syntax.TypeTable =
            net.starlark.java.syntax.TypeTagger.tagFile(file, module, loader)
        return net.starlark.java.syntax.TypeTaggerTest.Result(file, typeTable)
    }

    /** As in [.tagFilePossiblyFailing] but asserts that even type tagging succeeded.  */
    @Throws(java.lang.Exception::class)
    private fun tagFile(vararg lines: String?): Result {
        val result = tagFilePossiblyFailing(*lines)
        Truth.assertThat(result.typeTable.errors()).isEmpty()
        return result
    }

    /** Asserts that type tagging fails with at least the specified error.  */
    @Throws(java.lang.Exception::class)
    private fun assertInvalid(expectedError: String?, vararg lines: String?) {
        val result = tagFilePossiblyFailing(*lines)
        Truth.assertWithMessage("type tagging succeeded unexpectedly")
            .that(result.typeTable.ok())
            .isFalse()
        net.starlark.java.syntax.TestUtils.assertContainsError(result.typeTable.errors(), expectedError)
    }

    /** Returns the first statement of a parsed file.  */
    private fun <T : net.starlark.java.syntax.Statement?> getFirstStatement(
        clazz: java.lang.Class<T?>,
        file: net.starlark.java.syntax.StarlarkFile
    ): T? {
        Truth.assertThat(file.getStatements()).isNotEmpty()
        val stmt: net.starlark.java.syntax.Statement = file.getStatements().get(0)
        Truth.assertThat(stmt).isInstanceOf(clazz)
        return clazz.cast(stmt)
    }

    /** Returns the first statement of a function body.  */
    private fun <T : net.starlark.java.syntax.Statement?> getFirstStatement(
        clazz: java.lang.Class<T?>,
        def: net.starlark.java.syntax.DefStatement
    ): T? {
        Truth.assertThat(def.getBody()).isNotEmpty()
        val stmt: net.starlark.java.syntax.Statement = def.getBody().get(0)
        Truth.assertThat(stmt).isInstanceOf(clazz)
        return clazz.cast(stmt)
    }

    /** Returns the resolved function of the first def statement with the given name.  */
    private fun getDefFunction(
        file: net.starlark.java.syntax.StarlarkFile?,
        name: String?
    ): net.starlark.java.syntax.Resolver.Function? {
        val functions: java.util.ArrayList<net.starlark.java.syntax.Resolver.Function> =
            java.util.ArrayList<net.starlark.java.syntax.Resolver.Function>()
        object : net.starlark.java.syntax.NodeVisitor() {
            override fun visit(def: net.starlark.java.syntax.DefStatement) {
                if (def.identifier.name.equals(name)) {
                    functions.add(def.getResolvedFunction())
                }
                super.visit(def)
            }
        }.visit(file)
        Truth.assertThat(functions).isNotEmpty()
        return functions.get(0)
    }

    @Throws(java.lang.Exception::class)
    private fun assertTopLevelUsesTypeSyntax(vararg lines: String?): BooleanSubject {
        val result = tagFile(*lines)
        return Truth.assertThat(result.typeTable.usesTypeSyntax(result.file.getResolvedFunction()))
    }

    @Throws(java.lang.Exception::class)
    private fun assertDefFunctionUsesTypeSyntax(name: String?, vararg lines: String?): BooleanSubject {
        val result = tagFile(*lines)
        return Truth.assertThat(result.typeTable.usesTypeSyntax(getDefFunction(result.file, name)))
    }

    @org.junit.Test
    fun staticTypeCheckingFlagRequirements() {
        options = net.starlark.java.syntax.FileOptions.builder().resolveTypeSyntax(false)
            .tolerateInvalidTypeExpressions(false)
        Truth.assertThat(
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { tagFilePossiblyFailing("0") })
        )
            .hasMessageThat()
            .contains("type tagging requires that resolveTypeSyntax is set")

        options =
            net.starlark.java.syntax.FileOptions.builder().resolveTypeSyntax(true).tolerateInvalidTypeExpressions(true)
        Truth.assertThat(
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { tagFilePossiblyFailing("0") })
        )
            .hasMessageThat()
            .contains("type tagging requires that tolerateInvalidTypeExpressions is not set")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extractType_primitives() {
        Truth.assertThat(extractType("None")).isEqualTo(net.starlark.java.syntax.Types.NONE)
        Truth.assertThat(extractType("bool")).isEqualTo(net.starlark.java.syntax.Types.BOOL)
        Truth.assertThat(extractType("int")).isEqualTo(net.starlark.java.syntax.Types.INT)
        Truth.assertThat(extractType("float")).isEqualTo(net.starlark.java.syntax.Types.FLOAT)
        Truth.assertThat(extractType("str")).isEqualTo(net.starlark.java.syntax.Types.STR)

        assertExtractTypeFails("None[bool]", "'None' does not accept arguments")
        assertExtractTypeFails("bool[bool]", "'bool' does not accept arguments")
        assertExtractTypeFails("int[bool]", "'int' does not accept arguments")
        assertExtractTypeFails("float[bool]", "'float' does not accept arguments")
        assertExtractTypeFails("str[bool]", "'str' does not accept arguments")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extractType_union() {
        Truth.assertThat(extractType("int|bool")).isEqualTo(
            net.starlark.java.syntax.Types.union(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.BOOL
            )
        )
    }

    // These are also tests of the list, dict, and tuple type constructors, not just the TypeTagger.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extractType_list() {
        Truth.assertThat(extractType("list[int]"))
            .isEqualTo(net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.INT))
        Truth.assertThat(extractType("list[list[int]]"))
            .isEqualTo(net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.INT)))
        Truth.assertThat(extractType("list"))
            .isEqualTo(net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.ANY))

        assertExtractTypeFails("list[int, bool]", "list[] accepts exactly 1 argument but got 2")
        assertExtractTypeFails("list[[int]]", "unexpected expression '[int]'")
        assertExtractTypeFails("list[int, ...]", "in application to list, got '...', expected a type")
        assertExtractTypeFails("list[()]", "in application to list, got '()', expected a type")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extractType_dict() {
        Truth.assertThat(extractType("dict[int, str]")).isEqualTo(
            net.starlark.java.syntax.Types.dict(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR
            )
        )
        Truth.assertThat(extractType("dict[int, list[str]]"))
            .isEqualTo(
                net.starlark.java.syntax.Types.dict(
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.STR)
                )
            )
        Truth.assertThat(extractType("dict")).isEqualTo(
            net.starlark.java.syntax.Types.dict(
                net.starlark.java.syntax.Types.ANY,
                net.starlark.java.syntax.Types.ANY
            )
        )

        assertExtractTypeFails("dict[int]", "dict[] accepts exactly 2 arguments but got 1")
        assertExtractTypeFails("dict[int, str, bool]", "dict[] accepts exactly 2 arguments but got 3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extractType_tuple() {
        Truth.assertThat(extractType("tuple[()]")).isEqualTo(net.starlark.java.syntax.Types.EMPTY_TUPLE)
        Truth.assertThat(extractType("tuple[int]"))
            .isEqualTo(net.starlark.java.syntax.Types.tuple(net.starlark.java.syntax.Types.INT))
        Truth.assertThat(extractType("tuple[int, str, bool]"))
            .isEqualTo(
                net.starlark.java.syntax.Types.tuple(
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.STR,
                    net.starlark.java.syntax.Types.BOOL
                )
            )
        Truth.assertThat(extractType("tuple[tuple[int, str], bool]"))
            .isEqualTo(
                net.starlark.java.syntax.Types.tuple(
                    net.starlark.java.syntax.Types.tuple(
                        net.starlark.java.syntax.Types.INT,
                        net.starlark.java.syntax.Types.STR
                    ), net.starlark.java.syntax.Types.BOOL
                )
            )
        Truth.assertThat(extractType("tuple[int, ...]"))
            .isEqualTo(net.starlark.java.syntax.Types.homogeneousTuple(net.starlark.java.syntax.Types.INT))
        Truth.assertThat(extractType("tuple"))
            .isEqualTo(net.starlark.java.syntax.Types.homogeneousTuple(net.starlark.java.syntax.Types.ANY))

        assertExtractTypeFails(
            "tuple[...]",
            "in application to tuple, '...' can only appear as the second of exactly 2 arguments, where"
                    + " the first argument is a type"
        )
        assertExtractTypeFails(
            "tuple[int, str, ...]",
            "in application to tuple, '...' can only appear as the second of exactly 2 arguments, where"
                    + " the first argument is a type"
        )
        assertExtractTypeFails(
            "tuple[(), int]",
            "in application to tuple, '()' can only appear if it is the only argument"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extractType_struct() {
        Truth.assertThat(extractType("struct[{}]"))
            .isEqualTo(net.starlark.java.syntax.Types.struct(com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>()))
        Truth.assertThat(extractType("struct[{'foo': int, 'bar': list[str]}]"))
            .isEqualTo(
                net.starlark.java.syntax.Types.struct(
                    com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                        "foo",
                        net.starlark.java.syntax.Types.INT,
                        "bar",
                        net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.STR)
                    )
                )
            )

        Truth.assertThat(extractType("struct")).isEqualTo(net.starlark.java.syntax.Types.STRUCT_OF_ANY)
        Truth.assertThat(extractType("struct[{'foo': int}, ...]"))
            .isEqualTo(
                net.starlark.java.syntax.Types.partialStruct(
                    com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                        "foo",
                        net.starlark.java.syntax.Types.INT
                    )
                )
            )

        assertExtractTypeFails("struct[...]", "in application to struct, got '...', expected a dict")
        assertExtractTypeFails(
            "struct[{'a': int}, int]",
            "in application to struct, got 'int' for optional argument #2, expected '...'"
        )
        assertExtractTypeFails(
            "struct[{'a': int}, ..., ...]", "struct[] accepts at most 2 arguments but got 3"
        )
        // Just like for eval-time dict literals, keys must be unique.
        assertExtractTypeFails(
            "struct[{'foo': int, 'foo': bool}]", "dictionary expression has duplicate key: \"foo\""
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extractType_unknownIdentifier() {
        assertExtractTypeFails("Foo", "name 'Foo' is not defined")
        assertExtractTypeFails("Foo[int]", "name 'Foo' is not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun localCannotShadowPredeclaredType() {
        assertInvalid(
            "local symbol 'int' cannot be used as a type",
            """
        def f():
            int = 123
            x : int
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonTypeCannotBeUsedAsType() {
        module = net.starlark.java.syntax.TestUtils.Module.Companion.withTypes("Foo", null)
        assertInvalid(
            "predeclared symbol 'Foo' cannot be used as a type",
            """
        x : Foo
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun annotationMustBeAtFirstOccurrence_assignment() {
        assertInvalid(
            "type annotation on 'x' may only appear at its declaration",
            """
        def f():
            x = 123
            x : int = 123
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun annotationMustBeAtFirstOccurrence_varStatementAfterAssignment() {
        assertInvalid(
            "type annotation on 'y' may only appear at its declaration",
            """
        def f():
            x, y, z = 123
            y : int
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun annotationMustBeAtFirstOccurrence_parameters() {
        assertInvalid(
            "type annotation on 'x' may only appear at its declaration",
            """
        def f(x):
            # Invalid even though x has no type annotation above.
            x : int
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun annotationMustBeAtFirstOccurence_localVar() {
        // Also avoid assertInvalid() in this test case so we have some coverage of the declaration
        // location reporting, which is spread over two events.
        val typeTable: net.starlark.java.syntax.TypeTable =
            tagFilePossiblyFailing(
                """
                def f():
                    x : int
                    x : str
                
                """.trimIndent()
            )
                .typeTable
        Truth.assertThat(typeTable.ok()).isFalse()
        net.starlark.java.syntax.TestUtils.assertContainsError(
            typeTable.errors(), "3:5: type annotation on 'x' may only appear at its declaration"
        )
        net.starlark.java.syntax.TestUtils.assertContainsError(typeTable.errors(), "2:5: 'x' previously declared here")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun annotationMustBeAtFirstOccurrence_innerFunction() {
        // Every function definition implicitly annotates its identifier as at least a Callable, even
        // if the definition has no parameter or return type annotations.
        assertInvalid(
            "function 'g' was previously declared",
            """
        def f():
            def g():
                pass
            def g():
                pass
        
        """.trimIndent()
        )

        assertInvalid(
            "function 'g' was previously declared",
            """
        def f():
            g = 1
            def g():
                pass
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun annotationMustBeAtFirstOccurrence_loadedGlobal() {
        // These options are needed to exercise attempting to annotate a loaded symbol. Otherwise we
        // would be annotating a distinct global symbol whose name happens to clash with the loaded one.
        // That's also an error, but not the one we want to test.
        options.loadBindsGlobally(true).allowToplevelRebinding(true)

        assertInvalid(
            "type annotation on 'x' may only appear at its declaration",
            """
        load("...", "x")
        x : int = 1
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tagFile_setsFunctionType_basic() {
        val result =
            tagFile(
                """
            def f(a : int, b = 1, *c : bool, d : str = "abc", e, **f : int) -> bool:
                pass
            
            """.trimIndent()
            )
        val type: net.starlark.java.syntax.Types.CallableType? = result.getType(
            getFirstStatement<net.starlark.java.syntax.DefStatement?>(
                net.starlark.java.syntax.DefStatement::class.java,
                result.file
            )
        )

        Truth.assertThat(type).isNotNull()
        Truth.assertThat(type.getParameterNames()).containsExactly("a", "b", "d", "e").inOrder()
        Truth.assertThat(type.getParameterTypes())
            .containsExactly(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.ANY,
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.ANY
            )
            .inOrder()
        Truth.assertThat(type.getNumPositionalOnlyParameters()).isEqualTo(0)
        Truth.assertThat(type.getNumPositionalParameters()).isEqualTo(2)
        Truth.assertThat(type.getMandatoryParameters()).containsExactly("a", "e").inOrder()
        Truth.assertThat(type.getVarargsType()).isEqualTo(net.starlark.java.syntax.Types.BOOL)
        Truth.assertThat(type.getKwargsType()).isEqualTo(net.starlark.java.syntax.Types.INT)
        Truth.assertThat(type.getReturnType()).isEqualTo(net.starlark.java.syntax.Types.BOOL)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tagFile_setsFunctionType_omittedDetailsHandledCorrectly() {
        var result =
            tagFile(
                """
            def f(*a, **b):
                pass
            
            """.trimIndent()
            )
        var type: net.starlark.java.syntax.Types.CallableType? = result.getType(
            getFirstStatement<net.starlark.java.syntax.DefStatement?>(
                net.starlark.java.syntax.DefStatement::class.java,
                result.file
            )
        )

        Truth.assertThat(type).isNotNull()
        Truth.assertThat(type.getParameterNames()).isEmpty()
        Truth.assertThat(type.getParameterTypes()).isEmpty()
        Truth.assertThat(type.getVarargsType()).isEqualTo(net.starlark.java.syntax.Types.ANY)
        Truth.assertThat(type.getKwargsType()).isEqualTo(net.starlark.java.syntax.Types.ANY)
        Truth.assertThat(type.getReturnType()).isEqualTo(net.starlark.java.syntax.Types.ANY)

        result =
            tagFile(
                """
            def f():
                pass
            
            """.trimIndent()
            )
        type = result.getType(
            getFirstStatement<net.starlark.java.syntax.DefStatement?>(
                net.starlark.java.syntax.DefStatement::class.java,
                result.file
            )
        )

        Truth.assertThat(type).isNotNull()
        Truth.assertThat(type.getVarargsType()).isNull()
        Truth.assertThat(type.getKwargsType()).isNull()
        Truth.assertThat(type.getReturnType()).isEqualTo(net.starlark.java.syntax.Types.ANY)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tagFile_reachesInnerFunctions() {
        val result =
            tagFile(
                """
            def f():
                def g(a : int):
                    pass
            
            """.trimIndent()
            )
        val outer: net.starlark.java.syntax.DefStatement = getFirstStatement<net.starlark.java.syntax.DefStatement>(
            net.starlark.java.syntax.DefStatement::class.java,
            result.file
        )
        val inner: net.starlark.java.syntax.DefStatement = getFirstStatement<net.starlark.java.syntax.DefStatement>(
            net.starlark.java.syntax.DefStatement::class.java,
            outer
        )
        val type: net.starlark.java.syntax.Types.CallableType? = result.getType(inner)

        Truth.assertThat(type).isNotNull()
        Truth.assertThat(type.getParameterNames()).containsExactly("a")
        Truth.assertThat(type.getParameterTypes()).containsExactly(net.starlark.java.syntax.Types.INT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tagFile_setsFunctionType_onLambdas() {
        var result =
            tagFile(
                """
            lambda x: 123
            
            """.trimIndent()
            )
        var stmt: net.starlark.java.syntax.ExpressionStatement =
            getFirstStatement<net.starlark.java.syntax.ExpressionStatement>(
                net.starlark.java.syntax.ExpressionStatement::class.java,
                result.file
            )
        var type: net.starlark.java.syntax.Types.CallableType? =
            result.getType(stmt.getExpression() as net.starlark.java.syntax.LambdaExpression?)

        Truth.assertThat(type).isNotNull()
        Truth.assertThat(type.getParameterNames()).containsExactly("x")
        Truth.assertThat(type.getParameterTypes()).containsExactly(net.starlark.java.syntax.Types.ANY)
        Truth.assertThat(type.getReturnType()).isEqualTo(net.starlark.java.syntax.Types.ANY)

        result =
            tagFile(
                """
            lambda x: lambda y: 123
            
            """.trimIndent()
            )
        stmt = getFirstStatement<net.starlark.java.syntax.ExpressionStatement>(
            net.starlark.java.syntax.ExpressionStatement::class.java,
            result.file
        )
        type =
            result.getType((stmt.expression as net.starlark.java.syntax.LambdaExpression).getBody() as net.starlark.java.syntax.LambdaExpression?)

        Truth.assertThat(type).isNotNull()
    }

    // No type is set for the callable created for evaluating a file.
    // (There's no equivalent test for evaluating an expression, since that callable is created
    // on-the-fly by Starlark#eval.)
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tagFile_doesNotSetTypeOnStarlarkFileFunction() {
        val result = tagFile("pass")
        val type: net.starlark.java.syntax.Types.CallableType? = result.getType(result.file.getResolvedFunction())

        Truth.assertThat(type).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tagFile_setsBindingType_nullByDefault() {
        val result =
            tagFile(
                """
            x = 1
            
            """.trimIndent()
            )
        val stmt: net.starlark.java.syntax.AssignmentStatement =
            getFirstStatement<net.starlark.java.syntax.AssignmentStatement>(
                net.starlark.java.syntax.AssignmentStatement::class.java,
                result.file
            )
        val type: net.starlark.java.syntax.StarlarkType? =
            result.getType(stmt.getLHS() as net.starlark.java.syntax.Identifier?)

        Truth.assertThat(type).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tagFile_setsBindingType_var() {
        val result =
            tagFile(
                """
            x : int
            
            """.trimIndent()
            )
        val stmt: net.starlark.java.syntax.VarStatement = getFirstStatement<net.starlark.java.syntax.VarStatement>(
            net.starlark.java.syntax.VarStatement::class.java,
            result.file
        )
        val type: net.starlark.java.syntax.StarlarkType? = result.getType(stmt.getIdentifier())

        Truth.assertThat(type).isEqualTo(net.starlark.java.syntax.Types.INT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tagFile_setsBindingType_assignment() {
        options.allowToplevelRebinding(true)

        val result =
            tagFile(
                """
            x : int = 5
            x = 6  # not clobbered by annotation-less reassignment
            
            """.trimIndent()
            )
        val stmt: net.starlark.java.syntax.AssignmentStatement =
            getFirstStatement<net.starlark.java.syntax.AssignmentStatement>(
                net.starlark.java.syntax.AssignmentStatement::class.java,
                result.file
            )
        val type: net.starlark.java.syntax.StarlarkType? =
            result.getType((stmt.getLHS() as net.starlark.java.syntax.Identifier?))

        Truth.assertThat(type).isEqualTo(net.starlark.java.syntax.Types.INT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tagFile_setsBindingType_functionIdentifier() {
        val result =
            tagFile(
                """
            def f(x : int):
                pass
            
            """.trimIndent()
            )
        val stmt: net.starlark.java.syntax.DefStatement = getFirstStatement<net.starlark.java.syntax.DefStatement>(
            net.starlark.java.syntax.DefStatement::class.java,
            result.file
        )
        val type: net.starlark.java.syntax.StarlarkType? = result.getType(stmt.getIdentifier())

        Truth.assertThat(type).isInstanceOf(net.starlark.java.syntax.Types.CallableType::class.java)
        Truth.assertThat((type as net.starlark.java.syntax.Types.CallableType).getParameterTypeByPos(0))
            .isEqualTo(net.starlark.java.syntax.Types.INT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tagFile_setsBindingType_functionParams() {
        val result =
            tagFile(
                """
            def f(a : int, b = 1, *c : bool, d : str = "abc", e, **f : int) -> bool:
                pass
            
            """.trimIndent()
            )
        val stmt: net.starlark.java.syntax.DefStatement = getFirstStatement<net.starlark.java.syntax.DefStatement>(
            net.starlark.java.syntax.DefStatement::class.java,
            result.file
        )
        val bindingTypes: java.util.ArrayList<net.starlark.java.syntax.StarlarkType> =
            java.util.ArrayList<net.starlark.java.syntax.StarlarkType>()
        for (param in stmt.getParameters()) {
            bindingTypes.add(result.getType(param.identifier))
        }

        Truth.assertThat(bindingTypes)
            .containsExactly(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.ANY,
                net.starlark.java.syntax.Types.BOOL,
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.ANY,
                net.starlark.java.syntax.Types.INT
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tagFile_setsBindingType_lambdaParams() {
        val result =
            tagFile(
                """
            lambda x, y: 123
            
            """.trimIndent()
            )
        val stmt: net.starlark.java.syntax.ExpressionStatement =
            getFirstStatement<net.starlark.java.syntax.ExpressionStatement>(
                net.starlark.java.syntax.ExpressionStatement::class.java,
                result.file
            )
        val lambda: net.starlark.java.syntax.LambdaExpression =
            stmt.getExpression() as net.starlark.java.syntax.LambdaExpression
        val bindingTypes: java.util.ArrayList<net.starlark.java.syntax.StarlarkType> =
            java.util.ArrayList<net.starlark.java.syntax.StarlarkType>()
        for (param in lambda.getParameters()) {
            bindingTypes.add(result.getType(param.identifier))
        }

        Truth.assertThat(bindingTypes)
            .containsExactly(net.starlark.java.syntax.Types.ANY, net.starlark.java.syntax.Types.ANY).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tagFile_setsBindingType_insideFunctions() {
        val result =
            tagFile(
                """
            def f():
                x : int
            
            """.trimIndent()
            )
        val stmt: net.starlark.java.syntax.DefStatement = getFirstStatement<net.starlark.java.syntax.DefStatement>(
            net.starlark.java.syntax.DefStatement::class.java,
            result.file
        )
        val type: net.starlark.java.syntax.StarlarkType? = result.getType(
            getFirstStatement<net.starlark.java.syntax.VarStatement?>(
                net.starlark.java.syntax.VarStatement::class.java,
                stmt
            ).getIdentifier()
        )

        Truth.assertThat(type).isEqualTo(net.starlark.java.syntax.Types.INT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tagFile_toleratesBareStarParam() {
        tagFile(
            """
        def f(*, x):
            pass
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tagFile_toplevelUsesTypeSyntax() {
        // <toplevel> is considered to use static type syntax if any part of the file uses static type
        // syntax.
        assertTopLevelUsesTypeSyntax(
            """
            # No type syntax anywhere.
            z = 1
            def f(x):
                return lambda y: x + 2
            f(z)
            
            """.trimIndent()
        )
            .isFalse()

        assertTopLevelUsesTypeSyntax("type X = int").isTrue()
        assertTopLevelUsesTypeSyntax("x: int").isTrue()
        assertTopLevelUsesTypeSyntax("x: int = 1").isTrue()
        assertTopLevelUsesTypeSyntax("x = cast(int, 1)").isTrue()
        // nested lambda and def statements
        assertTopLevelUsesTypeSyntax("lambda x: cast(int, x)").isTrue()
        assertTopLevelUsesTypeSyntax(
            """
            def f(x: int):
                pass
            
            """.trimIndent()
        )
            .isTrue()
        assertTopLevelUsesTypeSyntax(
            """
            def f(x) -> int:
                pass
            
            """.trimIndent()
        )
            .isTrue()
        assertTopLevelUsesTypeSyntax(
            """
            def f(x):
                def g(y):
                    z: int = 42
                    return z + y
            
            """.trimIndent()
        )
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tagFile_defStatementUsesTypeSyntax() {
        // A def statement uses static type syntax if it has type annotations in its declarations on in
        // its body (including nested lambdas but not nested def statements).
        assertDefFunctionUsesTypeSyntax("f", "def f(x): return x").isFalse()
        assertDefFunctionUsesTypeSyntax("f", "def f(x) -> int: return 42").isTrue()
        assertDefFunctionUsesTypeSyntax("f", "def f(x: int): return x").isTrue()
        assertDefFunctionUsesTypeSyntax("f", "def f(x): return cast(int, x)").isTrue()

        // Nesting
        assertDefFunctionUsesTypeSyntax(
            "untyped_in_typed_toplevel",
            """
            X: int = 42
            def untyped_in_typed_toplevel(x):
                return X
            
            """.trimIndent()
        )
            .isFalse()
        val typedInUntypedDef: String =
            """
        def untyped_with_nested_typed(x):
            def typed_nested_in_untyped(y: int) -> int:
                return cast(int, x) + y
            return typed_nested_in_untyped(x)
        
        """.trimIndent()
        assertDefFunctionUsesTypeSyntax("untyped_with_nested_typed", typedInUntypedDef).isFalse()
        assertDefFunctionUsesTypeSyntax("typed_nested_in_untyped", typedInUntypedDef).isTrue()
        assertDefFunctionUsesTypeSyntax(
            "untyped_with_nested_typed_lambda",
            """
            def untyped_with_nested_typed_lambda(x):
                return (lambda y: cast(int, y) + 42)(x)
            
            """.trimIndent()
        )
            .isTrue()
        assertDefFunctionUsesTypeSyntax(
            "untyped_with_nested_untyped_def_with_nested_typed_lambda",
            """
            def untyped_with_nested_untyped_def_with_nested_typed_lambda(x):
                def nested(y):
                    return (lambda z: cast(int, z) + 42)(y)
                return (lambda w: w)(nested(x))
            
            """.trimIndent()
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun loadStatement() {
        loader = net.starlark.java.syntax.TypeTagger.Loader? { importName: String? ->
            net.starlark.java.syntax.TestUtils.LoadableModule.Companion.of(
                "typed",
                net.starlark.java.syntax.Types.INT,
                "untyped",
                net.starlark.java.syntax.Types.ANY
            )
        }
        val result = tagFile("load('//x:x.bzl', local_t = 'typed', local_u = 'untyped')")
        val loadStmt: net.starlark.java.syntax.LoadStatement =
            getFirstStatement<net.starlark.java.syntax.LoadStatement>(
                net.starlark.java.syntax.LoadStatement::class.java,
                result.file
            )

        assertThat(loadStmt.bindings.stream().map({ b -> result.getType(b.localName) }))
            .containsExactly(net.starlark.java.syntax.Types.INT, net.starlark.java.syntax.Types.ANY)
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun loadStatement_requiresWorkingLoader() {
        loader = null
        assertInvalid(
            "load statements are not supported because no module loader has been defined",
            "load('//x:x.bzl', 'x')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun loadStatement_requiresLoadableModule() {
        loader = net.starlark.java.syntax.TypeTagger.Loader? { importName: String? -> null }
        assertInvalid("module '//x:x.bzl' not found", "load('//x:x.bzl', 'x')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun loadStatement_requiresExportedGlobal() {
        loader =
            net.starlark.java.syntax.TypeTagger.Loader? { importName: String? -> net.starlark.java.syntax.TestUtils.LoadableModule.Companion.of() }
        assertInvalid("module '//x:x.bzl' does not contain symbol 'x'", "load('//x:x.bzl', 'x')")
    }
}
