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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.vfs.bazel.Blake3Hasher.hash
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.allowToplevelRebinding
import net.starlark.java.syntax.FileOptions.Builder.build
import net.starlark.java.syntax.SyntaxError.Exception.errors
import net.starlark.java.syntax.TypeCheckerTest
import net.starlark.java.syntax.TypeCheckerTest.FooType
import net.starlark.java.syntax.TypeTable.errors
import net.starlark.java.syntax.TypeTable.ok
import net.starlark.java.syntax.Types.callable
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TypeCheckerTest {
    private val options: net.starlark.java.syntax.FileOptions.Builder = net.starlark.java.syntax.FileOptions.builder()
        .allowTypeSyntax(true)
        .resolveTypeSyntax(true) // This lets us construct simpler test cases without wrapper `def` statements.
        .allowToplevelRebinding(true)

    private var module: net.starlark.java.syntax.Resolver.Module =
        net.starlark.java.syntax.TestUtils.Module.Companion.withUniversalTypes()

    private var loader: net.starlark.java.syntax.TypeTagger.Loader? = null

    /**
     * Throws [AssertionError] if a file has errors, with an exception message that includes
     * `what` and the errors.
     */
    private fun assertNoErrors(what: String?, file: net.starlark.java.syntax.StarlarkFile) {
        if (!file.ok()) {
            throw java.lang.AssertionError(
                String.format(
                    "Unexpected errors: %s:\n%s",
                    what,
                    com.google.common.base.Joiner.on("\n").join(file.errors())
                )
            )
        }
    }

    /**
     * Throws [AssertionError] if a type table has errors, with an exception message that
     * includes `what` and the errors.
     */
    private fun assertNoErrors(what: String?, typeTable: net.starlark.java.syntax.TypeTable) {
        if (!typeTable.ok()) {
            throw java.lang.AssertionError(
                String.format(
                    "Unexpected errors: %s:\n%s", what, com.google.common.base.Joiner.on("\n").join(typeTable.errors())
                )
            )
        }
    }

    private class PreparedFile(
        file: net.starlark.java.syntax.StarlarkFile,
        typeTable: net.starlark.java.syntax.TypeTable?
    ) {
        val file: net.starlark.java.syntax.StarlarkFile
        val typeTable: net.starlark.java.syntax.TypeTable?

        init {
            this.file = file
            this.typeTable = typeTable
        }
    }

    /**
     * Parses, resolves, and type-tags a file, without typechecking it.
     * 
     * 
     * Returns a file without errors or else asserts failure.
     */
    @Throws(java.lang.Exception::class)
    private fun prepareFile(vararg lines: String?): PreparedFile {
        com.google.common.base.Preconditions.checkArgument(lines.size > 0)
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(*lines)
        val file: net.starlark.java.syntax.StarlarkFile =
            net.starlark.java.syntax.StarlarkFile.parse(input, options.build())
        assertNoErrors("parsing", file)
        net.starlark.java.syntax.Resolver.resolveFile(file, module)
        assertNoErrors("resolving", file)
        val typeTable: net.starlark.java.syntax.TypeTable =
            net.starlark.java.syntax.TypeTagger.tagFile(file, module, loader)
        assertNoErrors("type-tagging", typeTable)
        return PreparedFile(file, typeTable)
    }

    /**
     * Statically typechecks a program.
     * 
     * 
     * Asserts that steps before typechecking succeeded, but the typechecking itself may fail. The
     * resulting errors are available in the returned `StarlarkFile`.
     */
    @Throws(java.lang.Exception::class)
    private fun typecheckFilePossiblyFailing(vararg lines: String?): PreparedFile {
        val preparedFile = prepareFile(*lines)
        net.starlark.java.syntax.TypeChecker.checkFile(preparedFile.file, preparedFile.typeTable, module)
        return preparedFile
    }

    /** As in [.typecheckFilePossiblyFailing] but asserts that even type checking succeeded.  */
    @Throws(java.lang.Exception::class)
    private fun assertValid(vararg lines: String?): net.starlark.java.syntax.StarlarkFile {
        val preparedFile = typecheckFilePossiblyFailing(*lines)
        Truth.assertThat(preparedFile.file.errors()).isEmpty()
        Truth.assertThat(preparedFile.typeTable.errors()).isEmpty()
        return preparedFile.file
    }

    /** Asserts that type checking fails with at least the specified error.  */
    @Throws(java.lang.Exception::class)
    private fun assertInvalid(expectedError: String?, vararg lines: String?) {
        val preparedFile = typecheckFilePossiblyFailing(*lines)
        Truth.assertThat(preparedFile.file.errors()).isEmpty()
        Truth.assertWithMessage("type checking suceeded unexpectedly")
            .that(preparedFile.typeTable.ok())
            .isFalse()
        net.starlark.java.syntax.TestUtils.assertContainsError(preparedFile.typeTable.errors(), expectedError)
    }

    /**
     * Returns the inferred type of an expression, given zero or more `var` declarations for
     * identifiers appearing within the expression.
     */
    @Throws(java.lang.Exception::class)
    private fun inferTypeGivenDecls(expr: String?, vararg decls: String?): net.starlark.java.syntax.StarlarkType? {
        val preparedFile = prepareFile(*com.google.common.collect.ObjectArrays.concat<String?>(decls, expr))
        val resolvedExpr: net.starlark.java.syntax.Expression? =
            (preparedFile.file.statements.getLast() as net.starlark.java.syntax.ExpressionStatement).getExpression()
        return net.starlark.java.syntax.TypeChecker.inferTypeOf(resolvedExpr, preparedFile.typeTable, module)
    }

    /**
     * Asserts that the inferred type of an expression is equal to the expected type, given zero or
     * more `var` declarations for identifiers appearing within the expression.
     */
    @Throws(java.lang.Exception::class)
    private fun assertTypeGivenDecls(
        expr: String,
        expected: net.starlark.java.syntax.StarlarkType?,
        vararg decls: String?
    ) {
        val actual: net.starlark.java.syntax.StarlarkType? = inferTypeGivenDecls(expr, *decls)
        Truth.assertWithMessage("type of %s", expr).that(actual).isEqualTo(expected)
    }

    /**
     * Like [.assertTypeGivenDecls], but runs the typechecker on the whole file (and verifies
     * that it succeeds) as well. Useful for tests of the computed type of an unannotated variable,
     * which is set during a full typechecker pass but not when inferring the type of an expression.
     */
    @Throws(java.lang.Exception::class)
    private fun assertTypeAfterTypecheck(
        expr: String,
        expected: net.starlark.java.syntax.StarlarkType?,
        vararg decls: String?
    ) {
        val preparedFile = prepareFile(*com.google.common.collect.ObjectArrays.concat<String?>(decls, expr))
        net.starlark.java.syntax.TypeChecker.checkFile(preparedFile.file, preparedFile.typeTable, module)
        Truth.assertThat(preparedFile.file.errors()).isEmpty()
        val resolvedExpr: net.starlark.java.syntax.Expression? =
            (preparedFile.file.statements.getLast() as net.starlark.java.syntax.ExpressionStatement).getExpression()
        Truth.assertWithMessage("type of %s", expr)
            .that(net.starlark.java.syntax.TypeChecker.inferTypeOf(resolvedExpr, preparedFile.typeTable, module))
            .isEqualTo(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_identifier() {
        assertTypeGivenDecls("x", net.starlark.java.syntax.Types.INT, "x: int")
    }

    // TODO: #27370 - The real behavior we want is that an unannotated variable has an inferred type
    // if it is a non-parameter local variable in typed code, and Any type otherwise.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unannotatedVarIsAnyType() {
        assertTypeGivenDecls("x", net.starlark.java.syntax.Types.ANY, "x = 'ignored'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_literals() {
        assertTypeGivenDecls("'abc'", net.starlark.java.syntax.Types.STR)
        assertTypeGivenDecls("123", net.starlark.java.syntax.Types.INT)
        assertTypeGivenDecls("1.0", net.starlark.java.syntax.Types.FLOAT)
    }

    // TODO: #27728 - We should add a test that the types of universals, and in particular the
    // keyword-like symbols `None`, `True`, and `False`, are appropriately inferred to have types
    // None, bool, and bool respectively. This test would have to live in the eval package, since the
    // universal environment is not available to the syntax/ package.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assignment_simple() {
        assertValid(
            """
        n: int = 123
        
        """.trimIndent()
        )

        assertInvalid(
            ":1:1: cannot assign type 'str' to 'n' of type 'int'",
            """
        n: int = "abc"
        
        """.trimIndent()
        )

        assertInvalid(
            ":2:1: cannot assign type 'str' to 'n' of type 'int'",
            """
        n: int
        n = "abc"
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_assignment() {
        assertValid(
            """
        n = 123
        n + 456
        x: bool  # ensure toplevel code is type-checked
        
        """.trimIndent()
        )

        assertValid(
            """
        n = 123
        n + "456"  # not a static type error in untyped code
        
        """.trimIndent()
        )

        assertInvalid(
            "operator '+' cannot be applied to types 'int' and 'str'",
            """
        n = 123
        n + "456"
        x: bool  # ensure toplevel code is type-checked
        
        """.trimIndent()
        )

        assertInvalid(
            "cannot assign type 'str' to 'n' of type 'int'",
            """
        n = 123
        n = "456"  # subsequent assignments do not change the type
        x: bool    # ensure toplevel code is type-checked
        
        """.trimIndent()
        )

        // TODO: #28037 - in mypy, this is an error (attempt to use a variable of unknown type, since
        // the assignment is lexically below first use). We should treat it the same.
        assertValid(
            """
        def f() -> None:  # ensure function is type-checked
            for i in [0, 1]:
                if i == 1:
                    n + "456"
                else:
                    n = 123
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assignment_to_immutable_supertype() {
        assertValid(
            """
        list_lvalue: list[int]
        dict_lvalue: dict[str, int]

        a: object = list_lvalue
        b: Sequence[int] = list_lvalue
        c: Collection[int|float|str] = list_lvalue  # immutable collections covariant
        d: Mapping[str, int|float] = dict_lvalue  # Mapping (not dict!) covariant in value
        e: Collection[str|int] = dict_lvalue  # as keys
        f: tuple[int, ...] = ()
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assignment_rvalue_inference() {
        // Empty list literals can be assigned to a target of any collection type, and empty dict
        // literals can be assigned to any mapping type (recursively).
        assertValid(
            """
        a: list[int] = []
        b: Sequence[str] = []
        c: Collection[bool] = []
        d: dict[str, int] = {}
        e: Mapping[str, int] = {}
        f: Collection[str] = {}  # as collection of keys
        
        """.trimIndent()
        )

        // Non-empty list/dict rvalues can be assigned to covariant mutable list/dict types
        // (recursively)
        assertValid(
            """
        g: list[int|float] = [1, 2, 3] + [4, 5, 6]
        h: list[list[int]|dict[str, str]] = [[]] if 1 == 0 else [{}]
        
        """.trimIndent()
        )

        // ... but not to incompatible ones.
        assertInvalid(
            ":1:1: cannot assign type 'list[int]' to 'x' of type 'list[float]'",
            """
        x: list[float] = [1, 2, 3]
        y: dict[str, int] = {'a': 1.0}
        
        """.trimIndent()
        )

        // If the LHS is untyped, it's inferred to be the recursively lvalue version of the RHS type.
        assertTypeAfterTypecheck(
            "x",
            net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.INT),  // not Types.listRvalue
            """
        x = [1, 2, 3]
        _: Any  # ensure toplevel code is type-checked
        
        """.trimIndent()
        )
        assertTypeAfterTypecheck(
            "x",
            net.starlark.java.syntax.Types.dict(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.INT
            ),  // not Types.listRvalue
            """
        x = {'a': 1}
        _: Any  # ensure toplevel code is type-checked
        
        """.trimIndent()
        )
        assertTypeAfterTypecheck(
            "x",  // Not Types.listRvalue or Types.dictRvalue
            net.starlark.java.syntax.Types.union(
                net.starlark.java.syntax.Types.list(
                    net.starlark.java.syntax.Types.dict(
                        net.starlark.java.syntax.Types.STR,
                        net.starlark.java.syntax.Types.INT
                    )
                ),
                net.starlark.java.syntax.Types.dict(
                    net.starlark.java.syntax.Types.STR,
                    net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.INT)
                )
            ),
            """
        x = [{'a': 1}] if 1 == 0 else {'b': [2, 3]}
        _: Any  # ensure toplevel code is type-checked
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sequence_assignment() {
        assertValid(
            """
        x: int|str
        x, y = 1, "2"
        x, y = ["3", "4"]
        x, y = {"a": 3.14, "b": 2.71}  # a dict is treated as its sequence of keys
        s: set[str]
        x, y = s
        
        """.trimIndent()
        )
        // Multi-level lhs sequences
        assertValid("(x, (y, z)) = [1, [2, 3]]")
        assertValid(
            """
        y: list[int]
        x, (y[0],) = 1, [2]
        
        """.trimIndent()
        )
        assertValid(
            """
        a: list[Any]
        (x, (y, [z])) = a
        
        """.trimIndent()
        )

        assertInvalid(
            ":2:1: cannot assign non-iterable type 'str' to '(x, y)'",
            """
        x: str
        x, y = "ab"  # type error: strings are not iterable in Starlark
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:1: cannot assign type 'tuple[str]' to '(x, y)'; want 2-element sequence",
            """
        x: str
        x, y = ("ab",)
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:1: cannot assign type 'tuple[int, int, int]' to '(x, y)'; want 2-element sequence",
            """
        x: int
        x, y = 1, 2, 3
        
        """.trimIndent()
        )

        assertInvalid(
            ":3:3: operator '+' cannot be applied to types 'int' and 'str'",
            """
        x: list[int] = [0]
        x[0], y, z = 1, 2, "3"
        y + z
        
        """.trimIndent()
        )

        assertInvalid(
            ":3:3: operator '+' cannot be applied to types 'int|bool' and 'int|bool'",
            """
        z: list[int|bool]
        x, y = z
        x + y
        
        """.trimIndent()
        )

        // Any and unions
        assertTypeAfterTypecheck(
            "x, y",
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.ANY,
                net.starlark.java.syntax.Types.ANY
            ),
            "z: Any; x, y = z"
        )
        assertTypeAfterTypecheck(
            "x, y",
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.STR
                )
            ),
            "z: list[int] | tuple[int, str]; x, y = z"
        )
        assertTypeAfterTypecheck(
            "x, y",
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.ANY
                ),
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.ANY
                )
            ),
            "z: list[int] | Any; x, y = z"
        )
        // lhs is type-checked even if rhs is Any.
        assertInvalid(
            ":3:2: cannot index 'x' of type 'int'",
            """
        x: int
        z: Any
        x[0], y = z
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sequence_assignment_order_of_operations() {
        assertTypeAfterTypecheck(
            "x",
            net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.INT),
            """
        _: Any  # ensure toplevel code is type-checked
        x, x[0] = ([1], 2)
        
        """.trimIndent()
        )

        assertInvalid(
            ":2:4: x of type 'tuple[int]' does not support item assignment",
            """
        _: Any  # ensure toplevel code is type-checked
        x, x[0] = ((1,), 2)
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sequence_assignment_rvalue_inference() {
        assertValid(
            """
        x: list[int|str]
        y: tuple[list[int|str], dict[int|str, int|str]]
        x, y = [], ([], {})
        x, y = ["a", "b"], ([1, 2], {"a": "b"})
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun canTolerateIrrelevantStatementTypes() {
        assertValid(
            """
        type A = int
        int # expression statement
        def f() -> None:
            for i in [0, 1]:
                if i == 1:
                    break
                else:
                    continue
        
        """.trimIndent()
        )
    }

    /** A dummy type having a single field 'f' of a given type.  */
    private open class FooType(fieldType: net.starlark.java.syntax.StarlarkType) :
        net.starlark.java.syntax.StarlarkType() {
        protected val fieldType: net.starlark.java.syntax.StarlarkType
        private val supertypes: com.google.common.collect.ImmutableList<net.starlark.java.syntax.StarlarkType?>

        init {
            this.fieldType = fieldType
            this.supertypes = com.google.common.collect.ImmutableList.of<net.starlark.java.syntax.StarlarkType?>(
                net.starlark.java.syntax.Types.struct(
                    com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                        "f",
                        fieldType
                    )
                )
            )
        }

        override fun getField(
            name: String,
            context: net.starlark.java.syntax.TypeContext?
        ): net.starlark.java.syntax.StarlarkType? {
            return if (name == "f") fieldType else null
        }

        override fun getSupertypes(): com.google.common.collect.ImmutableList<net.starlark.java.syntax.StarlarkType?> {
            return supertypes
        }

        override fun equals(obj: Any?): Boolean {
            return obj != null && obj.javaClass == this.javaClass
                    && fieldType == (obj as FooType).fieldType
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(this.javaClass.hashCode(), fieldType)
        }

        override fun toString(): String {
            return String.format("Foo[%s]", fieldType)
        }

        /** Like FooType, but mutable.  */
        private class Mutable(fieldType: net.starlark.java.syntax.StarlarkType) : FooType(fieldType) {
            override fun toString(): String {
                return String.format("MutableFoo[%s]", fieldType)
            }

            override fun hasSetField(): Boolean {
                return true
            }
        }
    }

    private val fooModule: net.starlark.java.syntax.Resolver.Module =
        net.starlark.java.syntax.TestUtils.Module.Companion.withUniversalTypesAnd(
            "struct",
            net.starlark.java.syntax.Types.STRUCT_CONSTRUCTOR,
            "Foo",
            net.starlark.java.syntax.Types.wrapTypeConstructor(
                "Foo",
                java.util.function.Function { t: net.starlark.java.syntax.StarlarkType? -> FooType(t) }),
            "MutableFoo",
            net.starlark.java.syntax.Types.wrapTypeConstructor(
                "MutableFoo",
                java.util.function.Function { t: net.starlark.java.syntax.StarlarkType? ->
                    net.starlark.java.syntax.TypeCheckerTest.FooType.Mutable(t)
                })
        )

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_dot() {
        module = fooModule

        assertTypeGivenDecls("o.f", net.starlark.java.syntax.Types.INT, "o: Foo[int]")
        assertTypeGivenDecls(
            "o.f",
            net.starlark.java.syntax.Types.union(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.BOOL
            ),
            "o: Foo[str] | MutableFoo[int|bool]"
        )
        assertTypeGivenDecls("o.f", net.starlark.java.syntax.Types.ANY, "o: Any")
        assertTypeGivenDecls("o.f", net.starlark.java.syntax.Types.INT, "o: struct[{'f': int}]")
        assertTypeGivenDecls("o.g", net.starlark.java.syntax.Types.ANY, "o: struct[{'f': int}, ...]")
        assertTypeGivenDecls("o.f + o.g", net.starlark.java.syntax.Types.FLOAT, "o: struct[{'f': int, 'g': float}]")

        assertInvalid(
            ":2:2: 'n' of type 'int' does not have field 'f'",
            """
        n: int
        n.f
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:2: 's' of type 'struct[{\"f\": int}]' does not have field 'g'",
            """
        s: struct[{'f': int}]
        s.g
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:2: 'o' of type 'Foo[int]' does not have field 'g'",
            """
        o: Foo[int]
        o.g
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assignment_dot() {
        module = fooModule

        assertValid(
            """
        o1: MutableFoo[int]
        o1.f = 123

        o2: Any
        o2.f = 123
        
        """.trimIndent()
        )

        assertInvalid(
            ":2:2: 's' of type 'str' does not have field 'f'",
            """
        s: str
        s.f = 123
        
        """.trimIndent()
        )

        assertInvalid(
            ":2:1: o of type 'Foo[int]' does not support field assignment",
            """
        o: Foo[int]  # immutable
        o.f = 123
        
        """.trimIndent()
        )

        assertInvalid(
            ":2:1: cannot assign type 'str' to 'o.f' of type 'int'",
            """
        o: MutableFoo[int]
        o.f = 'abc'
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:2: 'o' of type 'MutableFoo[int]' does not have field 'g'",
            """
        o: MutableFoo[int]
        o.g = 123
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:1: cannot assign type 'int' to 'o.f' which expects a value satisfying all of the 2"
                    + " types ['int', 'bool']",
            """
        o: MutableFoo[int] | MutableFoo[bool]
        o.f = 123
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assignment_to_struct() {
        module = fooModule

        assertValid(
            """
        rhs: Foo[int]
        compatible_total_struct: struct[{"f": int | str}] = rhs
        struct_of_no_fields: struct[{}] = rhs
        
        """.trimIndent()
        )

        assertInvalid(
            ":2:1: cannot assign type 'Foo[int]' to 'incompatible_total_struct' of type 'struct[{\"f\":"
                    + " int, \"g\": str}]'",
            """
        rhs: Foo[int]
        incompatible_total_struct: struct[{"f": int, "g": str}] = rhs
        
        """.trimIndent()
        )

        // Cannot assign a subtype of a total struct to any partial struct
        assertInvalid(
            ":2:1: cannot assign type 'Foo[int]' to 'partial_struct' of type 'struct[{\"f\": int|str},"
                    + " ...]'",
            """
        rhs: Foo[int]
        partial_struct: struct[{"f": int | str}, ...] = rhs
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:1: cannot assign type 'Foo[int]' to 'struct_of_all_fields' of type 'struct'",
            """
        rhs: Foo[int]
        struct_of_all_fields: struct = rhs
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_index_nonIndexable() {
        assertInvalid(
            ":2:2: cannot index 'n' of type 'int'",
            """
        n: int
        n["abc"]
        
        """.trimIndent()
        )

        // Any doesn't save us from doing a bad operation on a non-Any type.
        assertInvalid(
            ":3:2: cannot index 'n' of type 'int'",
            """
        n: int
        a: Any
        n[a]
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assignment_index_nonIndexable() {
        assertInvalid(
            ":2:2: cannot index 'n' of type 'int'",
            """
        n: int
        n["abc"] = 123
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_index_any() {
        assertTypeGivenDecls("a[123]", net.starlark.java.syntax.Types.ANY, "a: Any")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assign_index_any() {
        assertValid(
            """
        a: Any
        a["abc"] = 123
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_index_dict() {
        // Exact key type match.
        assertTypeGivenDecls("d['abc']", net.starlark.java.syntax.Types.INT, "d: dict[str, int]")
        // Match based on subtyping.
        assertTypeGivenDecls("d[s]", net.starlark.java.syntax.Types.INT, "d: dict[object, int]; s: str")
        // Bypass key type constraint using Any.
        assertTypeGivenDecls("d[a]", net.starlark.java.syntax.Types.INT, "d: dict[str, int]; a: Any")

        assertInvalid(
            ":2:2: 'd' of type 'dict[str, int]' requires key type 'str', but got 'int'",
            """
        d: dict[str, int]
        d[123]
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assignment_index_dict() {
        assertValid(
            """
        # Exact match.
        d1: dict[str, int]
        d1["abc"] = 123

        # Subtyping match.
        d2: dict[object, int]
        d2["abc"] = 123

        # Any match.
        a: Any
        d1["abc"] = a
        
        """.trimIndent()
        )

        assertInvalid(
            """
        :2:1: cannot assign type 'str' to 'd["abc"]' of type 'int'
        """.trimIndent(),
            """
        d: dict[str, int]
        d["abc"] = "abc"
        
        """.trimIndent()
        )

        // This failure is through the infer() code path, also exercised in the test case above.
        assertInvalid(
            """
        :2:2: 'd' of type 'dict[str, int]' requires key type 'str', but got 'int'
        """.trimIndent(),
            """
        d: dict[str, int]
        d[123] = 123
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_index_list() {
        assertTypeGivenDecls("arr[123]", net.starlark.java.syntax.Types.STR, "arr: list[str]")

        assertTypeGivenDecls("arr[a]", net.starlark.java.syntax.Types.STR, "arr: list[str]; a: Any")

        assertInvalid(
            ":2:4: 'arr' of type 'list[str]' must be indexed by an integer, but got 'str'",
            """
        arr: list[str]
        arr["abc"]
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assignment_index_list() {
        assertValid(
            """
        # Normal case.
        arr: list[str]
        arr[123] = "abc"

        # Any as index.
        a: Any
        arr[a] = "abc"

        # Any as value.
        arr[123] = a
        
        """.trimIndent()
        )

        assertInvalid(
            """
        :2:1: cannot assign type 'int' to 'arr[123]' of type 'str'
        """.trimIndent(),
            """
        arr: list[str]
        arr[123] = 456
        
        """.trimIndent()
        )

        // This failure is through the infer() code path, also exercised in the test case above.
        assertInvalid(
            """
        :2:4: 'arr' of type 'list[str]' must be indexed by an integer, but got 'str'
        """.trimIndent(),
            """
        arr: list[str]
        arr["abc"] = "xyz"
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_index_str() {
        assertTypeGivenDecls("s[123]", net.starlark.java.syntax.Types.STR, "s: str")

        assertTypeGivenDecls("s[a]", net.starlark.java.syntax.Types.STR, "s: str; a: Any")

        assertInvalid(
            ":2:2: 's' of type 'str' must be indexed by an integer, but got 'str'",
            """
        s: str
        s["abc"]
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assignment_index_str() {
        assertInvalid(
            ":3:1: s of type 'str' does not support item assignment",
            """
        # Normal case.
        s: str
        s[123] = "abc"
        
        """.trimIndent()
        )
        assertInvalid(
            ":4:1: s of type 'str' does not support item assignment",
            """
        # Any as index.
        s: str
        a: Any
        s[a] = "abc"
        
        """.trimIndent()
        )
        assertInvalid(
            ":4:1: s of type 'str' does not support item assignment",
            """
        # Any as value.
        s: str
        a: Any
        s[123] = a
        
        """.trimIndent()
        )

        assertInvalid(
            """
        :2:1: cannot assign type 'int' to 's[123]' of type 'str'
        """.trimIndent(),
            """
        s: str
        s[123] = 456
        
        """.trimIndent()
        )

        // This failure is through the infer() code path, also exercised in the test case above.
        assertInvalid(
            """
        :2:2: 's' of type 'str' must be indexed by an integer, but got 'str'
        """.trimIndent(),
            """
        s: str
        s["abc"] = "xyz"
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_index_tuple() {
        // Statically knowable index in-range.
        assertTypeGivenDecls("t[1]", net.starlark.java.syntax.Types.STR, "t: tuple[int, str, bool]")
        assertTypeGivenDecls("t[-1]", net.starlark.java.syntax.Types.BOOL, "t: tuple[int, str, bool]")
        // Index into unknown-length homogeneous tuples.
        assertTypeGivenDecls("t[1]", net.starlark.java.syntax.Types.INT, "t: tuple[int, ...]")
        assertTypeGivenDecls("t[n]", net.starlark.java.syntax.Types.INT, "t: tuple[int, ...]; n: Any")

        // Index can't be statically determined.
        val unionType: net.starlark.java.syntax.StarlarkType? = net.starlark.java.syntax.Types.union(
            net.starlark.java.syntax.Types.INT,
            net.starlark.java.syntax.Types.STR,
            net.starlark.java.syntax.Types.BOOL
        )
        assertTypeGivenDecls("t[n]", unionType, "t: tuple[int, str, bool]; n: int")
        assertTypeGivenDecls("t[a]", unionType, "t: tuple[int, str, bool]; a: Any")

        // Bad index type.
        assertInvalid(
            ":2:2: 't' of type 'tuple[int, str, bool]' must be indexed by an integer, but got 'str'",
            """
        t: tuple[int, str, bool]
        t["abc"]
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:2: 't' of type 'tuple[str, ...]' must be indexed by an integer, but got 'str'",
            """
        t: tuple[str, ...]
        t["abc"]
        
        """.trimIndent()
        )

        // Statically knowable index out-of-range.
        assertInvalid(
            ":2:2: 't' of type 'tuple[int, str, bool]' is indexed by integer 3, which is out-of-range",
            """
        t: tuple[int, str, bool]
        t[3]
        
        """.trimIndent()
        )
        // Statically knowable index out-of-range.
        assertInvalid(
            ":2:2: 't' of type 'tuple[int, str, bool]' is indexed by integer -4, which is out-of-range",
            """
        t: tuple[int, str, bool]
        t[-4]
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assignment_index_tuple() {
        // Cannot assign a value to a tuple index
        assertInvalid(
            ":2:1: t of type 'tuple[int, str, bool]' does not support item assignment",
            """
        t: tuple[int, str, bool]
        t[1] = "abc"
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:1: t of type 'tuple[str, ...]' does not support item assignment",
            """
        t: tuple[str, ...]
        t[1] = "abc"
        
        """.trimIndent()
        )
        // ... even when the value is Any
        assertInvalid(
            ":3:1: t of type 'tuple[int, str, bool]' does not support item assignment",
            """
        val: Any
        t: tuple[int, str, bool]
        t[1] = val
        
        """.trimIndent()
        )
        assertInvalid(
            ":3:1: t of type 'tuple[str, ...]' does not support item assignment",
            """
        val: Any
        t: tuple[str, ...]
        t[1] = val
        
        """.trimIndent()
        )
        // ... or the index is unknown
        assertInvalid(
            ":3:1: t of type 'tuple[int, str, bool]' does not support item assignment",
            """
        i: Any
        t: tuple[int, str, bool]
        t[i] = "abc"
        
        """.trimIndent()
        )
        assertInvalid(
            ":3:1: t of type 'tuple[str, ...]' does not support item assignment",
            """
        i: Any
        t: tuple[str, ...]
        t[i] = "abc"
        
        """.trimIndent()
        )
        // If the value is of the wrong type, we still report an error about tuple index assignment.
        assertInvalid(
            ":2:1: t of type 'tuple[int, str, bool]' does not support item assignment",
            """
        t: tuple[int, str, bool]
        t[1] = {"foo": "bar"}
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:1: t of type 'tuple[str, ...]' does not support item assignment",
            """
        t: tuple[str, ...]
        t[1] = {"foo": "bar"}
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assignment_index_nested() {
        assertValid(
            """
        x: list[list[int]]
        x[0][0] = 1
        
        """.trimIndent()
        )

        assertInvalid(
            ":2:1: x[0] of type 'tuple[int]' does not support item assignment",
            """
        x: list[tuple[int]]
        x[0][0] = 1
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_index_union() {
        assertTypeGivenDecls(
            "u[1]",
            net.starlark.java.syntax.Types.union(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.FLOAT,
                net.starlark.java.syntax.Types.BOOL
            ),
            "u: str | list[int] | tuple[float, ...] | tuple[Any, bool]"
        )
        assertTypeGivenDecls("u['abc']", net.starlark.java.syntax.Types.NUMERIC, "u: dict[str, int] | dict[Any, float]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assignment_index_union() {
        assertValid(
            """
        u: list[int] | dict[Any, int]
        u[1] = 123
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:1: cannot assign type 'str' to 'u[1]' which expects a value satisfying all of the 2"
                    + " types ['str', 'int']",
            """
        u: list[str] | list[int]
        u[1] = "abc"
        
        """.trimIndent()
        )
        assertValid(
            """
        u: list[str|int]
        u[1] = "abc"
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun augmented_assignment() {
        module = fooModule

        assertValid(
            """
        x: list[int]
        x += [1, 2]
        x[0] += 3

        y: dict[str, int]
        y |= {"answer": 42}
        y["key"] //= 2

        z: set[int|str]
        z_rhs: set[str]
        z ^= z_rhs

        w: MutableFoo[int]
        w.f *= 2
        
        """.trimIndent()
        )
        // Augmented assignment to an immutable value is legal as long as the types match.
        assertValid(
            """
        x: int | float
        x += 1.5

        y: str
        y *= 2

        z: tuple[int, ...]
        z += (1, 2)
        
        """.trimIndent()
        )

        // Binary operator cannot be applied to LHS and RHS types.
        assertInvalid(
            ":2:3: operator '+=' cannot be applied to types 'int' and 'str'",
            """
        x: int
        x += "abc"
        
        """.trimIndent()
        )
        // TODO(b/141263526): we may want to support list += sequence.
        assertInvalid(
            ":2:3: operator '+=' cannot be applied to types 'list[int]' and 'tuple[int, int]'",
            """
        x: list[int]
        x += (1, 2)
        
        """.trimIndent()
        )

        // Binary operator can be applied to LHS and RHS types, but the result is not assignable to LHS
        assertInvalid(
            ":2:3: operator '+=' cannot be applied to types 'int' and 'float': cannot update 'x' of"
                    + " type 'int' with a result value of type 'float'",
            """
        x: int
        x += 1.5
        
        """.trimIndent()
        )
        assertInvalid(
            (":2:3: operator '|=' cannot be applied to types 'dict[str, int]' and 'dict[int, float]':"
                    + " cannot update 'x' of type 'dict[str, int]' with a result value of type"
                    + " 'dict[str|int, int|float]'"),
            """
        x: dict[str, int]
        x |= {1: 2.3}
        
        """.trimIndent()
        )
        assertInvalid(
            (":2:3: operator '+=' cannot be applied to types 'tuple[int, str]' and 'tuple[str]': cannot"
                    + " update 'x' of type 'tuple[int, str]' with a result value of type 'tuple[int, str,"
                    + " str]'"),
            """
        x: tuple[int, str]
        x += ("hello", )
        
        """.trimIndent()
        )

        // Invalid index/field assignments.
        assertInvalid(
            ":2:1: x of type 'str' does not support item assignment",
            """
        x: str
        x[1] += "a"
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:1: x of type 'tuple[int, ...]|list[Any]' does not support item assignment",
            """
        x: tuple[int, ...] | list
        x[0] += 42
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:1: x of type 'Foo[int]' does not support field assignment",
            """
        x: Foo[int] # immutable
        x.f *= 2
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:1: x of type 'MutableFoo[int]|Foo[int]' does not support field assignment",
            """
        x: MutableFoo[int] | Foo[int]  # potentially immutable
        x.f *= 2
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_slice() {
        assertTypeGivenDecls("x[1:2]", net.starlark.java.syntax.Types.STR, "x: str")
        assertTypeGivenDecls(
            "x[1:]",
            net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.INT),
            "x: list[int]"
        )
        assertTypeGivenDecls(
            "x[y:z:w]",
            net.starlark.java.syntax.Types.union(
                net.starlark.java.syntax.Types.sequence(net.starlark.java.syntax.Types.STR),
                net.starlark.java.syntax.Types.ANY
            ),
            "x: Sequence[str] | Any; y: Any; z: Any; w: Any"
        )

        // Invalid operand type
        assertInvalid(
            "invalid slice operand 'x' of type 'int', expected Sequence or str", "x: int; x[:2:-1]"
        )

        // Invalid index types
        assertInvalid("got 'str' for start index, want int", "x: str; [][x:]")
        assertInvalid("got 'Any|bool' for stop index, want int", "y: Any | bool; [][:y:]")
        assertInvalid("got 'float' for slice step, want int", "z: float; [][::z]")

        // Invalid step
        assertInvalid("slice step cannot be zero", "x: list; x[::0]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_slice_tuple_indices() {
        assertTypeGivenDecls(
            "x[0:4:1]",
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.BOOL
            ),
            "x: tuple[int, str, bool]"
        )
        assertTypeGivenDecls(
            "x[:]",
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.BOOL
            ),
            "x: tuple[int, str, bool]"
        )
        assertTypeGivenDecls(
            "x[1:3]",
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.BOOL
            ),
            "x: tuple[int, str, bool]"
        )
        assertTypeGivenDecls(
            "x[-9999:2]",
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR
            ),
            "x: tuple[int, str, bool]"
        )
        assertTypeGivenDecls(
            "x[1:9999]",
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.BOOL
            ),
            "x: tuple[int, str, bool]"
        )
        assertTypeGivenDecls(
            "x[-3::2]",
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.BOOL
            ),
            "x: tuple[int, str, bool]"
        )
        assertTypeGivenDecls(
            "x[::-1]",
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.BOOL,
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.INT
            ),
            "x: tuple[int, str, bool]"
        )
        assertTypeGivenDecls(
            "x[-1:-4:-2]",
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.BOOL,
                net.starlark.java.syntax.Types.INT
            ),
            "x: tuple[int, str, bool]"
        )

        assertTypeGivenDecls(
            "x[0:99:9]",
            net.starlark.java.syntax.Types.homogeneousTuple(
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.STR
                )
            ),
            "x: tuple[int | str, ...]"
        )

        assertTypeGivenDecls(
            "x[y:]",
            net.starlark.java.syntax.Types.homogeneousTuple(
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.STR,
                    net.starlark.java.syntax.Types.BOOL
                )
            ),
            "x: tuple[int, str, bool]; y: int"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_dict() {
        // Empty case.
        assertTypeGivenDecls(
            "{}",
            net.starlark.java.syntax.Types.dictRvalue(
                net.starlark.java.syntax.Types.NEVER,
                net.starlark.java.syntax.Types.NEVER
            )
        )

        // Homogeneous case.
        assertTypeGivenDecls(
            "{'a': 1, 'b': 2}",
            net.starlark.java.syntax.Types.dictRvalue(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.INT
            )
        )

        // Heterogeneous case.
        val unionType: net.starlark.java.syntax.StarlarkType? =
            net.starlark.java.syntax.Types.union(net.starlark.java.syntax.Types.STR, net.starlark.java.syntax.Types.INT)
        assertTypeGivenDecls("{'a': 'abc', 1: 123}", net.starlark.java.syntax.Types.dictRvalue(unionType, unionType))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_list() {
        // Empty case.
        assertTypeGivenDecls("[]", net.starlark.java.syntax.Types.listRvalue(net.starlark.java.syntax.Types.NEVER))

        // Homogeneous case.
        assertTypeGivenDecls("[1, 2, 3]", net.starlark.java.syntax.Types.listRvalue(net.starlark.java.syntax.Types.INT))

        // Heterogeneous case.
        val unionType: net.starlark.java.syntax.StarlarkType? =
            net.starlark.java.syntax.Types.union(net.starlark.java.syntax.Types.INT, net.starlark.java.syntax.Types.STR)
        assertTypeGivenDecls("[1, 'a']", net.starlark.java.syntax.Types.listRvalue(unionType))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_tuple() {
        // Empty case.
        assertTypeGivenDecls("()", net.starlark.java.syntax.Types.EMPTY_TUPLE)

        // Fixed-length with homogeneous elements.
        assertTypeGivenDecls(
            "(1, 2, 3)",
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.INT
            )
        )

        // Fixed-length with heterogeneous elements.
        assertTypeGivenDecls(
            "(1, 'a')",
            net.starlark.java.syntax.Types.tuple(net.starlark.java.syntax.Types.INT, net.starlark.java.syntax.Types.STR)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_unary_operator() {
        // NOT is always boolean.
        assertTypeGivenDecls("not x", net.starlark.java.syntax.Types.BOOL, "x: bool")
        assertTypeGivenDecls("not x", net.starlark.java.syntax.Types.BOOL, "x: Any")
        assertTypeGivenDecls("not x", net.starlark.java.syntax.Types.BOOL, "x: list[int] | str")

        // The remaining unary operators preserve the type of their operand.
        assertTypeGivenDecls("-i", net.starlark.java.syntax.Types.INT, "i: int")
        assertTypeGivenDecls("-42", net.starlark.java.syntax.Types.INT)
        assertTypeGivenDecls("-x", net.starlark.java.syntax.Types.FLOAT, "x: float")
        assertTypeGivenDecls("-99.9", net.starlark.java.syntax.Types.FLOAT)
        assertTypeGivenDecls("-x", net.starlark.java.syntax.Types.INT, "x: int")
        assertTypeGivenDecls("-x", net.starlark.java.syntax.Types.ANY, "x: Any")
        assertTypeGivenDecls("-x", net.starlark.java.syntax.Types.NUMERIC, "x: int | float")

        assertTypeGivenDecls("+i", net.starlark.java.syntax.Types.INT, "i: int")
        assertTypeGivenDecls("+42", net.starlark.java.syntax.Types.INT)
        assertTypeGivenDecls("+x", net.starlark.java.syntax.Types.FLOAT, "x: float")
        assertTypeGivenDecls("+99.9", net.starlark.java.syntax.Types.FLOAT)
        assertTypeGivenDecls("+x", net.starlark.java.syntax.Types.ANY, "x: Any")
        assertTypeGivenDecls("+x", net.starlark.java.syntax.Types.NUMERIC, "x: int | float")

        assertTypeGivenDecls("~i", net.starlark.java.syntax.Types.INT, "i: int")
        assertTypeGivenDecls("~1", net.starlark.java.syntax.Types.INT)
        assertTypeGivenDecls("~x", net.starlark.java.syntax.Types.ANY, "x: Any")

        // Unsupported operations.
        assertInvalid(":2:1: operator '-' cannot be applied to type 'str'", "x: str", "-x")
        assertInvalid(":2:1: operator '+' cannot be applied to type 'str'", "x: str", "+x")
        assertInvalid(":2:1: operator '~' cannot be applied to type 'str'", "x: str", "~x")
        assertInvalid(":2:1: operator '-' cannot be applied to type 'str|int'", "x: str | int", "-x")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_and_or() {
        assertTypeGivenDecls("x and y", net.starlark.java.syntax.Types.BOOL, "x: int; y: str")
        assertTypeGivenDecls("x or y", net.starlark.java.syntax.Types.BOOL, "x: int; y: str")
        assertTypeGivenDecls("x and y", net.starlark.java.syntax.Types.BOOL, "x: int | float; y: str | bool")
        assertTypeGivenDecls("x or y", net.starlark.java.syntax.Types.BOOL, "x: list[int]; y: list[str]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_equality() {
        assertTypeGivenDecls("x == y", net.starlark.java.syntax.Types.BOOL, "x: int; y: str")
        assertTypeGivenDecls("x != y", net.starlark.java.syntax.Types.BOOL, "x: int; y: str")
        assertTypeGivenDecls("x == y", net.starlark.java.syntax.Types.BOOL, "x: int | float; y: str | bool")
        assertTypeGivenDecls("x != y", net.starlark.java.syntax.Types.BOOL, "x: int | float; y: str | bool")
        assertTypeGivenDecls("x == y", net.starlark.java.syntax.Types.BOOL, "x: int; y: Any")
        assertTypeGivenDecls("x != y", net.starlark.java.syntax.Types.BOOL, "x: Any; y: str")
        assertTypeGivenDecls("x == y", net.starlark.java.syntax.Types.BOOL, "x: Any; y: Any")
        assertTypeGivenDecls("x != y", net.starlark.java.syntax.Types.BOOL, "x: Any; y: Any")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_comparison() {
        assertTypeGivenDecls("x < y", net.starlark.java.syntax.Types.BOOL, "x: int; y: float")
        assertTypeGivenDecls("x >= y", net.starlark.java.syntax.Types.BOOL, "x: bool; y: bool")
        assertTypeGivenDecls("x <= y", net.starlark.java.syntax.Types.BOOL, "x: str; y: str")

        // Any inference
        assertTypeGivenDecls("x < y", net.starlark.java.syntax.Types.BOOL, "x: Any; y: Any")
        assertTypeGivenDecls("x >= y", net.starlark.java.syntax.Types.BOOL, "x: Any; y: int")
        assertTypeGivenDecls("x <= y", net.starlark.java.syntax.Types.BOOL, "x: str; y: Any")

        // Unions
        assertTypeGivenDecls("x < y", net.starlark.java.syntax.Types.BOOL, "x: int | float; y: float")
        assertTypeGivenDecls("x >= y", net.starlark.java.syntax.Types.BOOL, "x: int; y: int | float")
        assertTypeGivenDecls("x >= y", net.starlark.java.syntax.Types.BOOL, "x: Any; y: int | list[str]")
        assertTypeGivenDecls("x < y", net.starlark.java.syntax.Types.BOOL, "x: int | str; y: Any")

        // Compound types
        assertTypeGivenDecls("(1, 2) >= (3, 4)", net.starlark.java.syntax.Types.BOOL)
        assertTypeGivenDecls(
            "t1 <= t2",
            net.starlark.java.syntax.Types.BOOL,
            "t1: tuple[int, float]; t2: tuple[int, ...]"
        )
        assertTypeGivenDecls("x < y", net.starlark.java.syntax.Types.BOOL, "x: list[int]; y: list[int|float]")
        assertTypeGivenDecls("x <= y", net.starlark.java.syntax.Types.BOOL, "x: list[int|float]; y: list[float|int]")
        assertTypeGivenDecls("x > y", net.starlark.java.syntax.Types.BOOL, "x: tuple[str, int]; y: tuple[str]")
        assertTypeGivenDecls(
            "x <= y", net.starlark.java.syntax.Types.BOOL, "x: list[tuple[str, int]]; y: list[tuple[Any, float]]"
        )
        // Lists of Never are always comparable to other lists
        assertTypeGivenDecls("[] < [1]", net.starlark.java.syntax.Types.BOOL)
        assertTypeGivenDecls("['a'] >= []", net.starlark.java.syntax.Types.BOOL)
        assertTypeGivenDecls("[] > []", net.starlark.java.syntax.Types.BOOL)

        // unsupported operations
        assertInvalid(
            "operator '<' cannot be applied to types 'str' and 'int'", "x: str; y: int; x < y"
        )
        assertInvalid("operator '>' cannot be applied to types 'float' and 'bool'", "x: bool; 0.0 > x")
        assertInvalid(
            "operator '>=' cannot be applied to types 'dict[str, int]' and 'dict[str, int]'",
            "x: str; y: str; {x: 1} >= {y: 2}"
        )
        assertInvalid(
            "operator '<' cannot be applied to types 'dict[str, int]' and 'Any'",
            "x: dict[str, int]; y: Any; x < y"
        )
        assertInvalid(
            "operator '>=' cannot be applied to types 'Any' and 'dict[str, int]'",
            "x: Any; y: dict[str, int]; x >= y"
        )
        // because lhs str is incomparable to rhs int (and vice versa)
        assertInvalid(
            "operator '<' cannot be applied to types 'int|str' and 'int|str'",
            "x: int | str; y: int | str; x < y"
        )
        // Incomparable compound types
        assertInvalid(
            "operator '<' cannot be applied to types 'list[int|str]' and 'list[str]'",
            "x: list[int|str]; y: list[str]; x < y"
        )
        assertInvalid(
            "operator '>=' cannot be applied to types 'tuple[int, str]' and 'tuple[str, int]'",
            "x: tuple[int, str]; y: tuple[str, int]; x >= y"
        )
        assertInvalid(
            "operator '>=' cannot be applied to types 'tuple[int, str]' and 'tuple[int|str, ...]'",
            "x: tuple[int, str]; y: tuple[int|str, ...]; x >= y"
        )
        assertInvalid(
            "operator '>=' cannot be applied to types 'list[tuple[str, int]]' and 'list[tuple[bool,"
                    + " Any]]'",
            "x: list[tuple[str, int]]; y: list[tuple[bool, Any]]; x >= y"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_plus_binary_operator() {
        // numeric addition
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.INT, "x: int; y: int")
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.FLOAT, "x: int; y: float")
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.FLOAT, "x: float; y: int | float")

        // concatenation
        assertTypeGivenDecls("'hello' + 'world'", net.starlark.java.syntax.Types.STR)
        assertTypeGivenDecls("[] + []", net.starlark.java.syntax.Types.listRvalue(net.starlark.java.syntax.Types.NEVER))
        assertTypeGivenDecls("[] + [1]", net.starlark.java.syntax.Types.listRvalue(net.starlark.java.syntax.Types.INT))
        assertTypeGivenDecls(
            "['hello'] + []",
            net.starlark.java.syntax.Types.listRvalue(net.starlark.java.syntax.Types.STR)
        )
        assertTypeGivenDecls(
            "[1, 2.0] + [3, 'four']",
            net.starlark.java.syntax.Types.listRvalue(
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.FLOAT,
                    net.starlark.java.syntax.Types.STR
                )
            )
        )
        assertTypeGivenDecls(
            "x + y",
            net.starlark.java.syntax.Types.listRvalue(net.starlark.java.syntax.Types.INT),
            "x: list[int]; y: list[int]"
        )
        assertTypeGivenDecls(
            "x + y",
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.FLOAT,
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR
            ),
            "x: tuple[int, float]; y: tuple[int, str]"
        )
        assertTypeGivenDecls(
            "x + y",
            net.starlark.java.syntax.Types.homogeneousTuple(
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.FLOAT,
                    net.starlark.java.syntax.Types.BOOL
                )
            ),
            "x: tuple[int, float]; y: tuple[bool, ...]"
        )
        assertTypeGivenDecls(
            "x + y",
            net.starlark.java.syntax.Types.homogeneousTuple(net.starlark.java.syntax.Types.BOOL),
            "x: tuple[()]; y: tuple[bool, ...]"
        )
        assertTypeGivenDecls(
            "x + y",
            net.starlark.java.syntax.Types.homogeneousTuple(
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.BOOL
                )
            ),
            "x: tuple[int, ...]; y: tuple[bool, ...]"
        )
        assertTypeGivenDecls(
            "x + y",
            net.starlark.java.syntax.Types.homogeneousTuple(net.starlark.java.syntax.Types.INT),
            "x: tuple[int, ...]; y: tuple[()]"
        )

        // Any inference
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.ANY, "x: Any; y: Any")
        // TODO: #28037 - the following cases can be tightened to int | float
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.ANY, "x: Any; y: int")
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.ANY, "x: int; y: Any")
        // TODO: #28037 - the following cases can be tightened to float
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.ANY, "x: Any; y: float")
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.ANY, "x: float; y: Any")
        // TODO: #28037 - the following cases can be tightened to str
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.ANY, "x: Any; y: str")
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.ANY, "x: str; y: Any")
        // TODO: #28037 - the following cases can be tightened to list[str]
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.ANY, "x: Any; y: list[str]")
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.ANY, "x: list[int]; y: Any")
        // TODO: #28037 - the following cases can be tightened to "tuple of indeterminable shape".
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.ANY, "x: Any; y: tuple[str]")
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.ANY, "x: tuple[int, int]; y: Any")
        // TODO: #28037 - the following can be tightened to int | float | str.
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.ANY, "x: Any; y: int | str")
        // TODO: #28037 - the following can be tightened to list[Any] | float
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.ANY, "x: list[int] | float; y: Any")
        // TODO: #28037 - the following cases should fail
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.ANY, "x: Any; y: bool")
        assertTypeGivenDecls("x + y", net.starlark.java.syntax.Types.ANY, "x: bool; y: Any")

        // unsupported operations
        assertInvalid("operator '+' cannot be applied to types 'str' and 'int'", "x: str; x + 1")
        assertInvalid(
            "operator '+' cannot be applied to types 'int|str' and 'str'", "x: int|str; y: str; x + y"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_pipe_binary_operator() {
        assertTypeGivenDecls("x | y", net.starlark.java.syntax.Types.INT, "x: int; y: int")
        assertTypeGivenDecls(
            "x | y",
            net.starlark.java.syntax.Types.dictRvalue(
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.STR,
                    net.starlark.java.syntax.Types.INT
                ),
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.BOOL,
                    net.starlark.java.syntax.Types.FLOAT
                )
            ),
            "x: dict[str, bool]; y: dict[int, float]"
        )
        assertTypeGivenDecls(
            "x | y",
            net.starlark.java.syntax.Types.set(
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.STR
                )
            ),
            "x: set[int]; y: set[str]"
        )

        // TODO: #28037 - add a test for a union with a set[Never] once we can construct empty sets in
        // test machinery.

        // Any inference
        assertTypeGivenDecls("x | y", net.starlark.java.syntax.Types.ANY, "x: Any; y: Any")
        // TODO: #28037 - the following cases can be tightened to int
        assertTypeGivenDecls("x | y", net.starlark.java.syntax.Types.ANY, "x: Any; y: int")
        assertTypeGivenDecls("x | y", net.starlark.java.syntax.Types.ANY, "x: int; y: Any")
        // TODO: #28037 - the following cases can be tightened to dict[Any, Any]
        assertTypeGivenDecls("x | y", net.starlark.java.syntax.Types.ANY, "x: Any; y: dict[str, int]")
        assertTypeGivenDecls("x | y", net.starlark.java.syntax.Types.ANY, "x: dict[str, int]; y: Any")
        // TODO: #28037 - the following cases can be tightened to set[Any]
        assertTypeGivenDecls("x | y", net.starlark.java.syntax.Types.ANY, "x: Any; y: set[int]")
        assertTypeGivenDecls("x | y", net.starlark.java.syntax.Types.ANY, "x: set[str]; y: Any")
        // TODO: #28037 - the following can be tightened to int | set[Any]
        assertTypeGivenDecls("x | y", net.starlark.java.syntax.Types.ANY, "x: Any; y: int | set[str]")
        // TODO: #28037 - the following can be tightened to dict[Any, Any] | set[Any]
        assertTypeGivenDecls("x | y", net.starlark.java.syntax.Types.ANY, "x: Any; y: dict[str, str] | set[str]")
        // TODO: #28037 - the following cases should fail
        assertTypeGivenDecls("x | y", net.starlark.java.syntax.Types.ANY, "x: Any; y: int | bool")
        assertTypeGivenDecls("x | y", net.starlark.java.syntax.Types.ANY, "x: int | bool; y: Any")

        // unsupported operations
        assertInvalid("operator '|' cannot be applied to types 'int' and 'float'", "x: int; x | 2.0")
        assertInvalid(
            "operator '|' cannot be applied to types 'int|set[int]' and 'int|set[int]'",
            "x: int|set[int]; y: int|set[int]; x | y"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_ampersand_binary_operator() {
        assertTypeGivenDecls("x & y", net.starlark.java.syntax.Types.INT, "x: int; y: int")
        // TODO: #28037 - tighter inference for set intersections.
        assertTypeGivenDecls(
            "x & y",
            net.starlark.java.syntax.Types.set(
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.STR,
                    net.starlark.java.syntax.Types.INT
                )
            ),
            "x: set[str|int]; y: set[str|bool]"
        )

        // Any inference
        assertTypeGivenDecls("x & y", net.starlark.java.syntax.Types.ANY, "x: Any; y: Any")
        // TODO: #28037 - the following cases can be tightened to int
        assertTypeGivenDecls("x & y", net.starlark.java.syntax.Types.ANY, "x: Any; y: int")
        assertTypeGivenDecls("x & y", net.starlark.java.syntax.Types.ANY, "x: int; y: Any")
        // TODO: #28037 - the following cases can be tightened to set[Any]
        assertTypeGivenDecls("x & y", net.starlark.java.syntax.Types.ANY, "x: Any; y: set[int]")
        assertTypeGivenDecls("x & y", net.starlark.java.syntax.Types.ANY, "x: set[str]; y: Any")

        // unsupported operations
        assertInvalid("operator '&' cannot be applied to types 'int' and 'float'", "x: int; x & 2.0")
        assertInvalid(
            "operator '&' cannot be applied to types 'int' and 'set[int]'",
            "x: int; y: set[int]; x & y"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_caret_binary_operator() {
        assertTypeGivenDecls("x ^ y", net.starlark.java.syntax.Types.INT, "x: int; y: int")
        assertTypeGivenDecls(
            "x ^ y",
            net.starlark.java.syntax.Types.set(
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.STR,
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.BOOL
                )
            ),
            "x: set[str|int]; y: set[str|bool]"
        )

        // Any inference
        assertTypeGivenDecls("x ^ y", net.starlark.java.syntax.Types.ANY, "x: Any; y: Any")
        // TODO: #28037 - the following can be tightened to int
        assertTypeGivenDecls("x ^ y", net.starlark.java.syntax.Types.ANY, "x: Any; y: int")
        assertTypeGivenDecls("x ^ y", net.starlark.java.syntax.Types.ANY, "x: int; y: Any")
        // TODO: #28037 - the following can be tightened to set[Any]
        assertTypeGivenDecls("x ^ y", net.starlark.java.syntax.Types.ANY, "x: Any; y: set[int]")
        assertTypeGivenDecls("x ^ y", net.starlark.java.syntax.Types.ANY, "x: set[str]; y: Any")

        // unsupported operations
        assertInvalid(
            "operator '^' cannot be applied to types 'int' and 'float'", "x: int; y: float; x ^ y"
        )
        assertInvalid(
            "operator '^' cannot be applied to types 'int' and 'set[int]'",
            "x: int; y: set[int]; x ^ y"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_bitshift_binary_operators() {
        assertTypeGivenDecls("x << y", net.starlark.java.syntax.Types.INT, "x: int; y: int")
        assertTypeGivenDecls("x >> y", net.starlark.java.syntax.Types.INT, "x: int; y: int")

        // Any inference
        assertTypeGivenDecls("x << y", net.starlark.java.syntax.Types.ANY, "x: Any; y: Any")
        // TODO: #28037 - can be tightened to int
        assertTypeGivenDecls("x >> y", net.starlark.java.syntax.Types.ANY, "x: Any; y: int")
        assertTypeGivenDecls("x << y", net.starlark.java.syntax.Types.ANY, "x: int; y: Any")
        // TODO: #28037 - the following should fail
        assertTypeGivenDecls("x << y", net.starlark.java.syntax.Types.ANY, "x: Any; y: bool")
        assertTypeGivenDecls("x >> y", net.starlark.java.syntax.Types.ANY, "x: bool; y: Any")

        // unsupported operations
        assertInvalid("operator '<<' cannot be applied to types 'int' and 'float'", "x: int; x << 2.0")
        assertInvalid(
            "operator '>>' cannot be applied to types 'bool' and 'int'", "x: bool; y: int; x >> y"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_minus_binary_operator() {
        assertTypeGivenDecls("x - y", net.starlark.java.syntax.Types.INT, "x: int; y: int")
        assertTypeGivenDecls("x - y", net.starlark.java.syntax.Types.FLOAT, "x: int; y: float")
        assertTypeGivenDecls("x - y", net.starlark.java.syntax.Types.FLOAT, "x: float; y: int | float")
        assertTypeGivenDecls(
            "x - y",
            net.starlark.java.syntax.Types.set(
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.STR,
                    net.starlark.java.syntax.Types.INT
                )
            ),
            "x: set[str|int]; y: set[str|bool]"
        )

        // Any inference
        assertTypeGivenDecls("x - y", net.starlark.java.syntax.Types.ANY, "x: Any; y: Any")
        // TODO: #28037 - the following cases can be tightened to int | float
        assertTypeGivenDecls("x - y", net.starlark.java.syntax.Types.ANY, "x: Any; y: int")
        assertTypeGivenDecls("x - y", net.starlark.java.syntax.Types.ANY, "x: int; y: Any")
        // TODO: #28037 - the following cases can be tightened to float
        assertTypeGivenDecls("x - y", net.starlark.java.syntax.Types.ANY, "x: Any; y: float")
        assertTypeGivenDecls("x - y", net.starlark.java.syntax.Types.ANY, "x: float; y: Any")
        // TODO: #28037 - the following cases can be tightened to set[Any]
        assertTypeGivenDecls("x - y", net.starlark.java.syntax.Types.ANY, "x: Any; y: set[int]")
        assertTypeGivenDecls("x - y", net.starlark.java.syntax.Types.ANY, "x: set[str]; y: Any")

        // unsupported operations
        assertInvalid("operator '-' cannot be applied to types 'str' and 'int'", "x: str; x - 1")
        assertInvalid(
            "operator '-' cannot be applied to types 'int' and 'set[int]'",
            "x: int; y: set[int]; x - y"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_star_binary_operator() {
        // numeric multiplication
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.INT, "x: int; y: int")
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.FLOAT, "x: int; y: float")
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.FLOAT, "x: float; y: int | float")

        // string repetition
        assertTypeGivenDecls("'hello' * 2", net.starlark.java.syntax.Types.STR)
        assertTypeGivenDecls("2 * 'bye'", net.starlark.java.syntax.Types.STR)

        // list repetition
        assertTypeGivenDecls(
            "[1, 2.0] * 2",
            net.starlark.java.syntax.Types.listRvalue(
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.FLOAT
                )
            )
        )
        assertTypeGivenDecls(
            "2 * [1, 2.0]",
            net.starlark.java.syntax.Types.listRvalue(
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.FLOAT
                )
            )
        )
        // preserve list type even when the returned list is size 0
        assertTypeGivenDecls(
            "[1, 2.0] * 0",
            net.starlark.java.syntax.Types.listRvalue(
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.FLOAT
                )
            )
        )
        assertTypeGivenDecls(
            "0 * [1, 2.0]",
            net.starlark.java.syntax.Types.listRvalue(
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.FLOAT
                )
            )
        )
        assertTypeGivenDecls(
            "x * y",
            net.starlark.java.syntax.Types.listRvalue(net.starlark.java.syntax.Types.INT),
            "x: int; y: list[int]"
        )

        // tuple repetition
        assertTypeGivenDecls(
            "x * 2",
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.FLOAT,
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.FLOAT
            ),
            "x: tuple[int, float]"
        )
        assertTypeGivenDecls(
            "2 * x",
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.FLOAT,
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.FLOAT
            ),
            "x: tuple[int, float]"
        )
        assertTypeGivenDecls(
            "x * 2",
            net.starlark.java.syntax.Types.homogeneousTuple(net.starlark.java.syntax.Types.INT),
            "x: tuple[int, ...]"
        )
        assertTypeGivenDecls(
            "2 * x",
            net.starlark.java.syntax.Types.homogeneousTuple(net.starlark.java.syntax.Types.INT),
            "x: tuple[int, ...]"
        )
        assertTypeGivenDecls("x * 0", net.starlark.java.syntax.Types.EMPTY_TUPLE, "x: tuple[int, float]")
        assertTypeGivenDecls("0 * x", net.starlark.java.syntax.Types.EMPTY_TUPLE, "x: tuple[int, float]")
        assertTypeGivenDecls("x * 0", net.starlark.java.syntax.Types.EMPTY_TUPLE, "x: tuple[int, ...]")
        assertTypeGivenDecls("0 * x", net.starlark.java.syntax.Types.EMPTY_TUPLE, "x: tuple[int, ...]")
        assertTypeGivenDecls("x * -1", net.starlark.java.syntax.Types.EMPTY_TUPLE, "x: tuple[str]")
        assertTypeGivenDecls("-1 * x", net.starlark.java.syntax.Types.EMPTY_TUPLE, "x: tuple[str]")
        assertTypeGivenDecls("x * -1", net.starlark.java.syntax.Types.EMPTY_TUPLE, "x: tuple[str, ...]")
        assertTypeGivenDecls("-1 * x", net.starlark.java.syntax.Types.EMPTY_TUPLE, "x: tuple[str, ...]")
        assertTypeGivenDecls(
            "x * y",
            net.starlark.java.syntax.Types.homogeneousTuple(net.starlark.java.syntax.Types.INT),
            "x: int; y: tuple[int]"
        )
        assertTypeGivenDecls(
            "x * y",
            net.starlark.java.syntax.Types.homogeneousTuple(net.starlark.java.syntax.Types.INT),
            "x: int; y: tuple[int, ...]"
        )

        // Any inference
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.ANY, "x: Any; y: Any")
        // The next 2 cases are tricky - that `Any` could be numeric, str, list, or tuple!
        // TODO: #28037 - can be tightened to "int | float | str | list | tuple of any shape"
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.ANY, "x: Any; y: int")
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.ANY, "x: int; y: Any")
        // TODO: #28037 - the following cases can be tightened to float
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.ANY, "x: Any; y: float")
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.ANY, "x: float; y: Any")
        // TODO: #28037 - the following cases can be tightened to str
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.ANY, "x: Any; y: str")
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.ANY, "x: str; y: Any")
        // TODO: #28037 - can be tightened to list[str]
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.ANY, "x: Any; y: list[str]")
        // TODO: #28037 - can be tightened to list[int]
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.ANY, "x: list[int]; y: Any")
        // TODO: #28037 - can be tightened to tuple[str, ...]
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.ANY, "x: Any; y: tuple[str]")
        // TODO: #28037 - can be tightened to tuple[int, ...]
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.ANY, "x: tuple[int, int]; y: Any")
        // TODO: #28037 - can be tightened to float | str
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.ANY, "x: Any; y: float|str")
        // TODO: #28037 - can be tightened to list[str] | str
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.ANY, "x: list[str]|str; y: Any")
        // TODO: #28037 - the following cases should fail
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.ANY, "x: Any; y: bool")
        assertTypeGivenDecls("x * y", net.starlark.java.syntax.Types.ANY, "x: bool; y: Any")

        // unsupported operations
        assertInvalid("operator '*' cannot be applied to types 'str' and 'float'", "x: str; x * 1.0")
        assertInvalid(
            "operator '*' cannot be applied to types 'bool' and 'int'", "x: bool; y: int; x * y"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_slash_binary_operator() {
        assertTypeGivenDecls("x / y", net.starlark.java.syntax.Types.FLOAT, "x: int; y: int")
        assertTypeGivenDecls("x // y", net.starlark.java.syntax.Types.INT, "x: int; y: int")
        assertTypeGivenDecls("x / y", net.starlark.java.syntax.Types.FLOAT, "x: int; y: float")
        assertTypeGivenDecls("x // y", net.starlark.java.syntax.Types.FLOAT, "x: int; y: float")
        assertTypeGivenDecls("x / y", net.starlark.java.syntax.Types.FLOAT, "x: float; y: int | float")
        assertTypeGivenDecls("x // y", net.starlark.java.syntax.Types.FLOAT, "x: float; y: int | float")

        // Any inference
        assertTypeGivenDecls("x / y", net.starlark.java.syntax.Types.ANY, "x: Any; y: Any")
        assertTypeGivenDecls("x // y", net.starlark.java.syntax.Types.ANY, "x: Any; y: Any")
        // TODO: #28037 - can be tightened to float
        assertTypeGivenDecls("x / y", net.starlark.java.syntax.Types.ANY, "x: Any; y: int")
        // TODO: #28037 - can be tightened to int | float
        assertTypeGivenDecls("x // y", net.starlark.java.syntax.Types.ANY, "x: Any; y: int")
        // TODO: #28037 - can be tightened to float
        assertTypeGivenDecls("x / y", net.starlark.java.syntax.Types.ANY, "x: int; y: Any")
        // TODO: #28037 - can be tightened to int | float
        assertTypeGivenDecls("x // y", net.starlark.java.syntax.Types.ANY, "x: int; y: Any")
        // TODO: #28037 - the following can be tightened to float
        assertTypeGivenDecls("x / y", net.starlark.java.syntax.Types.ANY, "x: Any; y: float")
        assertTypeGivenDecls("x // y", net.starlark.java.syntax.Types.ANY, "x: Any; y: float")
        assertTypeGivenDecls("x / y", net.starlark.java.syntax.Types.ANY, "x: float; y: Any")
        assertTypeGivenDecls("x // y", net.starlark.java.syntax.Types.ANY, "x: float; y: Any")

        // unsupported operations
        assertInvalid(
            "operator '/' cannot be applied to types 'int' and 'str'", "x: int; y: str; x / y"
        )
        assertInvalid(
            "operator '//' cannot be applied to types 'str' and 'float'", "x: str; y: float; x // y"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_percent_binary_operator() {
        // numeric modulo
        assertTypeGivenDecls("x % y", net.starlark.java.syntax.Types.INT, "x: int; y: int")
        assertTypeGivenDecls("x % y", net.starlark.java.syntax.Types.FLOAT, "x: int; y: float")
        assertTypeGivenDecls("x % y", net.starlark.java.syntax.Types.FLOAT, "x: float; y: int | float")
        // string substitution
        assertTypeGivenDecls("'hello %s' % 'world'", net.starlark.java.syntax.Types.STR)
        assertTypeGivenDecls("'hello %s %s' % (' ', 'world')", net.starlark.java.syntax.Types.STR)
        assertTypeGivenDecls("'the answer is %s' % x", net.starlark.java.syntax.Types.STR, "x: int")

        // Any inference
        // TODO: #28037 - can be tightened to int | float | str
        assertTypeGivenDecls("x % y", net.starlark.java.syntax.Types.ANY, "x: Any; y: Any")
        assertTypeGivenDecls("x % y", net.starlark.java.syntax.Types.ANY, "x: Any; y: int")
        // TODO: #28037 - can be tightened to int | float
        assertTypeGivenDecls("x % y", net.starlark.java.syntax.Types.ANY, "x: int; y: Any")
        // TODO: #28037 - can be tightened to float | str
        assertTypeGivenDecls("x % y", net.starlark.java.syntax.Types.ANY, "x: Any; y: float")
        // TODO: #28037 - can be tightened to float
        assertTypeGivenDecls("x % y", net.starlark.java.syntax.Types.ANY, "x: float; y: Any")
        // TODO: #28037 - can be tightened to str
        assertTypeGivenDecls("x % y", net.starlark.java.syntax.Types.ANY, "x: Any; y: str")
        assertTypeGivenDecls("x % y", net.starlark.java.syntax.Types.STR, "x: str; y: Any")

        // unsupported operations
        assertInvalid(
            "operator '%' cannot be applied to types 'float' and 'str'", "x: float; x % 'hello'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_in_binary_operator() {
        // in Any
        assertTypeGivenDecls("x in y", net.starlark.java.syntax.Types.BOOL, "x: Any; y: Any")
        assertTypeGivenDecls("x not in y", net.starlark.java.syntax.Types.BOOL, "x: Any; y: Any")
        assertTypeGivenDecls("x in y", net.starlark.java.syntax.Types.BOOL, "x: bool; y: Any")
        assertTypeGivenDecls("x not in y", net.starlark.java.syntax.Types.BOOL, "x: list[int]; y: Any")

        // in str
        assertTypeGivenDecls("x in y", net.starlark.java.syntax.Types.BOOL, "x: str; y: str")
        assertTypeGivenDecls("x not in y", net.starlark.java.syntax.Types.BOOL, "x: str; y: str")
        assertTypeGivenDecls("x in y", net.starlark.java.syntax.Types.BOOL, "x: Any; y: str")
        assertTypeGivenDecls("x not in y", net.starlark.java.syntax.Types.BOOL, "x: Any; y: str")

        // in collections (type of lhs doesn't need to match collection's type)
        assertTypeGivenDecls("x in y", net.starlark.java.syntax.Types.BOOL, "x: str; y: list[bool]")
        assertTypeGivenDecls("x not in y", net.starlark.java.syntax.Types.BOOL, "x: str; y: tuple[str]")
        assertTypeGivenDecls("x in y", net.starlark.java.syntax.Types.BOOL, "x: str; y: dict[str, int]")
        assertTypeGivenDecls("x not in y", net.starlark.java.syntax.Types.BOOL, "x: str; y: set[int]")
        assertTypeGivenDecls("x in y", net.starlark.java.syntax.Types.BOOL, "x: bool; y: Any")
        assertTypeGivenDecls("x not in y", net.starlark.java.syntax.Types.BOOL, "x: list[int]; y: Any")
        assertTypeGivenDecls("x in y", net.starlark.java.syntax.Types.BOOL, "x: Any; y: list[int|float]")
        assertTypeGivenDecls("x not in y", net.starlark.java.syntax.Types.BOOL, "x: Any; y: set[str]")

        // unsupported operations
        assertInvalid("operator 'in' cannot be applied to types 'Any' and 'int'", "x: Any; x in 42")
        assertInvalid(
            "operator 'not in' cannot be applied to types 'list[str]' and 'str'",
            "x: str; ['e'] not in x"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_conditional() {
        assertTypeGivenDecls("x if cond else y", net.starlark.java.syntax.Types.INT, "cond: bool; x: int; y: int")
        assertTypeGivenDecls("x if cond else y", net.starlark.java.syntax.Types.NUMERIC, "cond: bool; x: int; y: float")

        // Any handling; the following test cases assume no Any-simplification in unions.
        assertTypeGivenDecls(
            "x if cond else y",
            net.starlark.java.syntax.Types.union(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.ANY
            ),
            "cond: bool; x: int; y: Any"
        )
        assertTypeGivenDecls(
            "x if cond else y",
            net.starlark.java.syntax.Types.union(
                net.starlark.java.syntax.Types.ANY,
                net.starlark.java.syntax.Types.FLOAT
            ),
            "cond: bool; x: Any; y: float"
        )
        assertTypeGivenDecls("x if cond else y", net.starlark.java.syntax.Types.ANY, "cond: bool; x: Any; y: Any")

        // Condition's type does not matter.
        assertTypeGivenDecls("x if cond else y", net.starlark.java.syntax.Types.INT, "cond: float; x: int; y: int")
        assertTypeGivenDecls("x if cond else y", net.starlark.java.syntax.Types.INT, "cond: Any; x: int; y: int")
        assertTypeGivenDecls(
            "x if cond else y", net.starlark.java.syntax.Types.INT, "def cond() -> int: return 42", "x: int; y: int"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_call() {
        assertTypeGivenDecls(
            "f(1, y = 'y')",
            net.starlark.java.syntax.Types.INT,
            """
        def f(x: int, y: str, z: int = 42) -> int:
            return 0
        
        """.trimIndent()
        )

        // Function type unions and Any handling
        assertTypeGivenDecls("f(42)", net.starlark.java.syntax.Types.ANY, "f: Any")
        assertTypeGivenDecls(
            "(f if 1 else g)(42)",
            net.starlark.java.syntax.Types.union(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR
            ),
            """
        def f(x: int) -> int:
            return x
        def g(y: int) -> str:
            return "hello"
        
        """.trimIndent()
        )
        assertTypeGivenDecls(
            "(f if 1 else g)(42)",
            net.starlark.java.syntax.Types.union(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.ANY
            ),
            """
        def f(x: int) -> int:
            return x
        g: Any
        
        """.trimIndent()
        )

        // Omitted return type is Any
        assertTypeGivenDecls(
            "f(42)",
            net.starlark.java.syntax.Types.ANY,
            """
        def f(x: int):
            return x
        
        """.trimIndent()
        )

        // Parameter type unions and Any handling
        assertTypeGivenDecls(
            "f(1, y = 'y')",
            net.starlark.java.syntax.Types.INT,
            """
        def f(x: int | str, y) -> int:
            return 0
        
        """.trimIndent()
        )

        // Argument type unions and Any handling
        assertTypeGivenDecls(
            "f(X, y = Y)",
            net.starlark.java.syntax.Types.INT,
            """
        def f(x: int | float | str, y: str, z: int = 42) -> int:
            return 0
        X: int | str
        Y: Any
        
        """.trimIndent()
        )

        // Infer types of list/dict literals in argument values (same mechanism as rvalue inference
        // for assignments)
        assertValid(
            """
        def f(x: list[int|str], y: dict[str|int, int|float]) -> None:
            pass
        f([], {})
        f([1, 2, 3], {"a": 1, "b": 2})
        
        """.trimIndent()
        )

        // Cannot call a non-callable
        assertInvalid(
            ":2:1: 'f' is not callable; got type 'int'",
            """
        f: int
        f(42)
        
        """.trimIndent()
        )
        assertInvalid(
            "'f if 1 else g' is not callable; got type 'Callable[[int], int]|int'",
            """
        def f(x: int) -> int:
            return x
        g: int
        (f if 1 else g)(42)
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_call_bad_arguments() {
        // Wrong argument types
        assertInvalid(
            "in call to 'f()', parameter 'y' got value of type 'str', want 'int'",
            """
        def f(x: Any, y: int) -> int:
            return 0
        f(123, "hello")
        
        """.trimIndent()
        )
        // Too many positionals
        assertInvalid(
            "'f()' accepts no more than 2 positional arguments but got 3",
            """
        def f(x: int, y: int) -> int:
            return 0
        f(1, 2, 3)
        
        """.trimIndent()
        )
        // Unexpected arguments
        assertInvalid(
            "'f()' got unexpected keyword argument: mispelled (did you mean 'misspelled'?)",
            """
        def f(x: int, misspelled: int) -> int:
            return 0
        f(x = 1, mispelled = 2)
        
        """.trimIndent()
        )
        // Missing required arguments
        assertInvalid(
            "'f()' missing 1 required argument: y",
            """
        def f(x: int, y: int) -> int:
            return 0
        f(42)
        
        """.trimIndent()
        )
        assertInvalid(
            "'f()' missing 2 required arguments: y, z",
            """
        def f(x: int, y: int, z) -> int:
            return 0
        f(42)
        
        """.trimIndent()
        )
        assertInvalid(
            "'f()' missing 2 required arguments: y, z",
            """
        def f(x: int, y: int, *, z) -> int:
            return 0
        f(42)
        
        """.trimIndent()
        )
        assertInvalid(
            "'f()' missing 1 required argument: y",
            """
        def f(x: int, y: int, z: str = "has_default") -> int:
            return 0
        f(42)
        
        """.trimIndent()
        )
        assertInvalid(
            "'f()' missing 1 required argument: y",
            """
        def f(x: int, y: int, *, z: str = "has_default") -> int:
            return 0
        f(42)
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_call_varargs() {
        assertTypeGivenDecls(
            "f(1, *args)",
            net.starlark.java.syntax.Types.INT,
            "args: list[str]",
            """
        def f(x: int, y: str, *args) -> int:
            return 0
        
        """.trimIndent()
        )
        // Complex types
        assertTypeGivenDecls(
            "f(1, *args)",
            net.starlark.java.syntax.Types.INT,
            """
        def f(x: int, *args: str|float) -> int:
            return 0
        args: list[str] | tuple[float]
        
        """.trimIndent()
        )
        // Caller varargs satisfy missing positional arguments
        assertTypeGivenDecls(
            "f(1, *args)",
            net.starlark.java.syntax.Types.INT,
            """
        def f(x: int, y: str, z: str) -> int:
            return 0
        args: list[str]
        
        """.trimIndent()
        )
        // Callable varargs absorb residual positional arguments
        assertTypeGivenDecls(
            "f(1, 'two', 3.0)",
            net.starlark.java.syntax.Types.INT,
            """
        def f(x: int, *args: str|float) -> int:
            return 0
        
        """.trimIndent()
        )
        // Wrong shape
        assertInvalid(
            "argument after * must be a sequence, not 'str'",
            """
        def f(*args) -> int:
            return 0
        args: str
        f(*args)
        
        """.trimIndent()
        )
        assertInvalid(
            "argument after * must be a sequence, not 'str|list[str]'",
            """
        def f(*args) -> int:
            return 0
        args: str | list[str]
        f(*args)
        
        """.trimIndent()
        )
        // Wrong element type
        assertInvalid(
            "in call to 'f()', elements of argument after * must be 'float', not 'str|float'",
            """
        def f(*args: float) -> int:
            return 0
        args: list[str] | list[float]
        f(*args)
        
        """.trimIndent()
        )
        // Wrong type of residual positional arguments
        assertInvalid(
            "in call to 'f()', residual positional arguments must be 'str|float', not 'int'",
            """
        def f(x: int, *args: str|float) -> int:
            return 0
        f(1, 2, 3)
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_call_kwargs() {
        assertTypeGivenDecls(
            "f(1, **kwargs)",
            net.starlark.java.syntax.Types.INT,
            """
        def f(x: int, y: float, **kwargs) -> int:
            return 0
        kwargs: dict[str, float]
        
        """.trimIndent()
        )
        // Complex types
        assertTypeGivenDecls(
            "f(1, **kwargs)",
            net.starlark.java.syntax.Types.INT,
            """
        def f(x: int, **kwargs: str|float) -> int:
            return 0
        kwargs: dict[str, str] | dict[str, float]
        
        """.trimIndent()
        )
        // Caller kwargs satisfy missing keyword arguments
        assertTypeGivenDecls(
            "f(1, **kwargs)",
            net.starlark.java.syntax.Types.INT,
            """
        def f(x: int, y: str, *, z: str) -> int:
            return 0
        kwargs: dict[str, str]
        
        """.trimIndent()
        )
        // Callable kwargs absorb residual keyword arguments
        assertTypeGivenDecls(
            "f(1, y='two', z=3.0)",
            net.starlark.java.syntax.Types.INT,
            """
        def f(x: int, **kwargs: str|float) -> int:
            return 0
        
        """.trimIndent()
        )
        // Wrong shape
        assertInvalid(
            "argument after ** must be a dict with string keys, not 'list[Any]'",
            """
        def f(**kwargs) -> int:
            return 0
        kwargs: list
        f(**kwargs)
        
        """.trimIndent()
        )
        assertInvalid(
            "argument after ** must be a dict with string keys, not 'dict[Any, Any]|list[Any]'",
            """
        def f(**kwargs) -> int:
            return 0
        kwargs: dict | list
        f(**kwargs)
        
        """.trimIndent()
        )
        // Wrong element type
        assertInvalid(
            "in call to 'f()', values of argument after ** must be 'float', not 'str|float'",
            """
        def f(**kwargs: float) -> int:
            return 0
        kwargs: dict[str, str] | dict[str, float]
        f(**kwargs)
        
        """.trimIndent()
        )
        // Wrong type of residual keyword arguments
        assertInvalid(
            "in call to 'f()', residual keyword arguments must be 'str|float', not 'int'",
            """
        def f(x: int, **kwargs: str|float) -> int:
            return 0
        f(x=1, y=2, z=3)
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_list_comprehension() {
        assertTypeGivenDecls(
            "[x for x in lst]",
            net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.INT),
            "lst: list[int]"
        )
        assertTypeGivenDecls(
            "[x * 3.14 for x in lst]",
            net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.FLOAT),
            "lst: list[int]"
        )
        assertTypeGivenDecls( // a is tuple[str], d is int, so a * d is an indeterminate-length str tuple
            "[a * d for a in b if c for d in e]",
            net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.homogeneousTuple(net.starlark.java.syntax.Types.STR)),
            "b: list[tuple[str]]",
            "c: bool",
            "e: Sequence[int]"
        )

        // For clauses must be iterable
        assertInvalid(
            ":3:28: comprehension 'for' clause operand must be an iterable, got 'str'",
            """
        b: list[int]
        d: str
        [a + c for a in b for c in d]
        
        """.trimIndent()
        )
        assertInvalid(
            ":3:17: comprehension 'for' clause operand must be an iterable, got 'int'",
            """
        b: int
        d: list[int]
        [a + c for a in b for c in d]
        
        """.trimIndent()
        )
        // If clauses must type-check
        assertInvalid(
            ":3:25: in call to 'cond()', parameter 'x' got value of type 'str', want 'int'",
            """
        lst: list[str]
        def cond(x: int) -> int: return x
        [x for x in lst if cond(x)]
        
        """.trimIndent()
        )
        // Body must type-check
        assertInvalid(
            ":2:4: operator '+' cannot be applied to types 'str' and 'int'",
            """
        lst: list[str]
        [x + 1 for x in lst]
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:4: operator '+' cannot be applied to types 'str' and 'float'",
            """
        lst: list[str]
        {x + 3.14 : x for x in lst}
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:8: operator '+' cannot be applied to types 'str' and 'list[str]'",
            """
        lst: list[str]
        {x : x + [x] for x in lst}
        
        """.trimIndent()
        )

        // Any and union handling
        assertTypeGivenDecls(
            "[x * 2 for x in lst]",
            net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.ANY),
            "lst: Any"
        )
        assertTypeGivenDecls(
            "[x * 2 for x in lst]",
            net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.NUMERIC),
            "lst: list[int] | Collection[float]"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_dict_comprehension() {
        assertTypeGivenDecls(
            "{'%s' % x : x for x in lst}",
            net.starlark.java.syntax.Types.dict(net.starlark.java.syntax.Types.STR, net.starlark.java.syntax.Types.INT),
            "lst: list[int]"
        )
        assertTypeGivenDecls(
            "{x : x for x in lst}",
            net.starlark.java.syntax.Types.dict(net.starlark.java.syntax.Types.ANY, net.starlark.java.syntax.Types.ANY),
            "lst: Any"
        )
        assertTypeGivenDecls(
            "{'%s' % x : x * 2 for x in lst}",
            net.starlark.java.syntax.Types.dict(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.NUMERIC
            ), "lst: list[int] | Collection[float]"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun def_argument_defaults() {
        assertValid("def f(x: int = 42, y: str= '', z = {}): pass")
        // Allow list/dict literal defaults (same mechanism as rvalue inference for assignments)
        assertValid(
            """
        def f(x: list[int] = [], y: dict[str, float] = {}): pass

        def g(x: list[int|float] = [1, 2, 3], y: dict[str|int, int|float] = {"pi": 3.14}): pass
        
        """.trimIndent()
        )
        // ... but the default's type does not cause the argument's type to be inferred
        assertTypeAfterTypecheck(
            "f",
            net.starlark.java.syntax.Types.callable(
                com.google.common.collect.ImmutableList.of<String?>("x", "y"),
                com.google.common.collect.ImmutableList.of<net.starlark.java.syntax.StarlarkType?>(
                    net.starlark.java.syntax.Types.ANY,
                    net.starlark.java.syntax.Types.ANY
                ),  // not list[int] or dict[str, float]
                0,
                2,
                com.google.common.collect.ImmutableSet.of<String?>(),
                null,
                null,
                net.starlark.java.syntax.Types.NONE
            ),
            "def f(x = [1, 2, 3], y = {'pi': 3.14}) -> None: pass"
        )
        val invalid = "def f(x: int = 42.0, y: str = 43, z = []): pass"
        assertInvalid("f(): parameter 'x' has default value of type 'float', declares 'int'", invalid)
        assertInvalid("f(): parameter 'y' has default value of type 'int', declares 'str'", invalid)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun def_return_type() {
        assertValid("def f(): pass")
        assertValid("def f(): return 42")
        assertValid("def f() -> int: return 42")
        assertValid("def f() -> None: pass")
        assertValid("def f() -> None: return")
        assertValid(
            """
        def f() -> int|None:
            if 2 + 2 == 4:
                return 42
        
        """.trimIndent()
        )
        assertValid(
            """
        def f() -> int|float|str:
            if 2 + 2 == 4:
                return 42
            elif 2.0 + 2.0 == 4.0:
                return 42.0
            else:
                return 'abc'
        
        """.trimIndent()
        )
        // Infer list/dict literal returns (same mechanism as rvalue inference for assignments)
        assertValid(
            """
        def f() -> list[int]:
            return []

        def g() -> list[int|float]:
            return [1, 2, 3]

        def h() -> dict[str, int|float]:
            return {}

        def i() -> dict[str|int, int|float]:
            return {"pi": 3.14}
        
        """.trimIndent()
        )

        assertInvalid(
            ":2:5: f() declares return type 'int' but may exit without an explicit 'return'",
            """
        def f() -> int:
            if 2 + 2 == 4:
                return 42
        
        """.trimIndent()
        )
        assertInvalid(
            ":3:16: f() declares return type 'None' but may return 'int'",
            """
        def f() -> None:
            if 2 + 2 == 4:
                return 42
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun def_body_checked_iff_function_uses_type_syntax() {
        assertInvalid(
            "operator '+' cannot be applied to types 'int' and 'str'",
            """
        X: int = 42
        def typed() -> int:
            return X + "abc"
        
        """.trimIndent()
        )
        assertValid(
            """
        X: int = 42
        def untyped():
            # error ignored by static type checker because function is untyped
            return X + "abc"
        
        """.trimIndent()
        )
        assertInvalid(
            "operator '+' cannot be applied to types 'int' and 'str'",
            """
        def typed(x):
            # type syntax in nested lambdas causes outer function to be type-checked.
            return (lambda x: cast(int, x))(x) + "abc"
        
        """.trimIndent()
        )
        assertValid(
            """
        X: int = 42
        def untyped():
            # type syntax in nested defs does not affect outer function
            def get_int() -> int:
                return X
            # error ignored by static type checker because outer function is untyped
            return get_int() + "abc"
        
        """.trimIndent()
        )

        assertInvalid(
            ":5:18: operator '%' cannot be applied to types 'int' and 'str'",
            """
        X: int = 42
        def untyped():
            def get_int() -> int:
                # type syntax in nested typed defs is checked even if the outer def is untyped
                return X % "abc"
            return get_int() + "def"
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_cast() {
        assertTypeGivenDecls("cast(int, x)", net.starlark.java.syntax.Types.INT, "x: Any")
        // cast expression allows casting to the wrong type
        assertTypeGivenDecls(
            "cast(list[int] | bool, 42)",
            net.starlark.java.syntax.Types.union(
                net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.INT),
                net.starlark.java.syntax.Types.BOOL
            )
        )
        // cast expression always checks that its second argument is well-typed
        assertInvalid(
            "operator '+' cannot be applied to types 'int' and 'str'", "cast(int, 1 + 'two')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infer_lambda() {
        // no inference on the type of a lamda's argument
        assertTypeGivenDecls("(lambda x: x + y)(42)", net.starlark.java.syntax.Types.ANY, "y: int")
        // ... but a cast in the body allows inferring the return type
        assertTypeGivenDecls("(lambda x: cast(int, x) + 1)(42)", net.starlark.java.syntax.Types.INT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun if_statement() {
        // condition
        assertInvalid(
            "operator '+' cannot be applied to types 'float' and 'str'",
            """
        def _wrapper() -> None:
            if 12.3 + '45.6' > 0:
                pass
        
        """.trimIndent()
        )
        // then body
        assertInvalid(
            "operator '+' cannot be applied to types 'int' and 'str'",
            """
        def _wrapper() -> None:
            if 1 == 2:
                123 + '456'
        
        """.trimIndent()
        )
        // else body
        assertInvalid(
            "operator '+' cannot be applied to types 'str' and 'int'",
            """
        def _wrapper() -> None:
            if 1 == 2:
                pass
            else:
                '123' + 456
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun if_statement_in_untyped_code() {
        // In untyped code, don't type-check the condition or non-def statements in then/else blocks ...
        assertValid(
            """
        def _untyped_wrapper():
            if 1 + "two":   # type error ignored in untyped code
                3 + "four"  # type error ignored in untyped code
            else:
                5 + "six"   # type error ignored in untyped code
        
        """.trimIndent()
        )
        // ... but do recurse into inner typed defs in then/else blocks
        assertInvalid(
            ":4:20: typed() declares return type 'int' but may return 'str'",
            """
        def _untyped_wrapper():
            if 1 + "two":         # type error ignored in untyped code
                def typed() -> int:
                    return "abc"  # type error checked in typed innner def
        
        """.trimIndent()
        )
        assertInvalid(
            ":6:20: typed() declares return type 'int' but may return 'float'",
            """
        def _untyped_wrapper():
            if 1 + "two":        # type error ignored in untyped code
                pass
            else:
                def typed() -> int:
                    return 3.14  # type error checked in typed innner def
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun for_statement_operand() {
        assertValid(
            """
        def _wrapper() -> None:
            for x in [1, 2, 3]:
                pass
        
        """.trimIndent()
        )
        assertValid(
            """
        def _wrapper() -> None:
            for x in (1, 2, 3):
                pass
        
        """.trimIndent()
        )
        assertValid(
            """
        def _wrapper() -> None:
            for x in {'a': 'b', 'c': 'd'}:
                pass
        
        """.trimIndent()
        )
        assertValid(
            """
        y: Any
        def _wrapper() -> None:
            for x in y:
                pass
        
        """.trimIndent()
        )
        assertValid(
            """
        y: Any | list[int]
        def f(x: int) -> None: pass  # to verify type of x
        def _wrapper() -> None:
            for x in y:
                f(x)
        
        """.trimIndent()
        )

        // Sequence assignment
        assertValid(
            """
        def _wrapper() -> None:
            for x, y in [(1, 2)]:
                pass
        
        """.trimIndent()
        )

        assertInvalid(
            "'for' loop operand must be an iterable, got 'int'",
            """
        def _wrapper() -> None:
            for x in 42:
                pass
        
        """.trimIndent()
        )
        assertInvalid(
            "cannot assign type 'tuple[int]' to '(x, y)'; want 2-element sequence",
            """
        def _wrapper() -> None:
            for x, y in [(42,)]:
                pass
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun for_statement_operand_with_previously_typed_vars() {
        assertValid(
            """
        def _wrapper() -> None:
            x: int
            for x in [1, 2, 3]:
                pass

            for x in (1, 2, 3):
                pass

            y: str
            for y in {'a': 'b', 'c': 'd'}:
                pass

            z: Any
            for z in [1, "two", 3.14, None]:
                pass
        
        """.trimIndent()
        )
        assertInvalid(
            ":3:9: cannot assign type 'int|str' to 'x' of type 'int'",
            """
        def _wrapper() -> None:
            x: int
            for x in [1, "two"]:
                pass
        
        """.trimIndent()
        )

        // Sequence assignment
        assertValid(
            """
        def _wrapper() -> None:
            x: int
            y: str
            for x, y in [(1, "two")]:
                pass
        
        """.trimIndent()
        )
        assertInvalid(
            ":3:9: cannot assign type 'str' to 'x' of type 'int'",
            """
        def _wrapper() -> None:
            x: int
            for x, y in [("three", 4)]:
                pass
        
        """.trimIndent()
        )
        assertInvalid(
            ":4:12: cannot assign type 'int' to 'y' of type 'str'",
            """
        def _wrapper() -> None:
            x: Any
            y: str
            for x, y in [("three", 4)]:
                pass
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun for_statement_body() {
        assertValid(
            """
        def _wrapper() -> None:
            for x in [1, 2, 3]:
                x + 1
        
        """.trimIndent()
        )

        assertInvalid(
            "operator '+' cannot be applied to types 'str' and 'int'",
            """
        def _wrapper() -> None:
            for x in ['a', 'b', 'c']:
                x + 1
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun for_statement_in_untyped_code() {
        // In untyped code, don't type-check the operand or non-def statements in body ...
        assertValid(
            """
        def _untyped_wrapper():
            for x in (1, "two", 3.14):  # type error ignored in untyped code
                x / "bad"               # type error ignored in untyped code
        
        """.trimIndent()
        )
        // ... but do recurse into inner typed defs in body
        assertInvalid(
            ":4:20: typed() declares return type 'int' but may return 'str'",
            """
        def _untyped_wrapper():
            for x in (1, "two", 3.14):  # type error ignored in untyped code
                def typed() -> int:
                    return "abc"        # type error checked in typed innner def
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun load_statement() {
        loader = net.starlark.java.syntax.TypeTagger.Loader? { importName: String? ->
            net.starlark.java.syntax.TestUtils.LoadableModule.Companion.of(
                "x",
                net.starlark.java.syntax.Types.union(
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.STR
                )
            )
        }
        assertInvalid(
            ":3:1: cannot assign type 'int|str' to 'y[0]' of type 'int'",
            """
        load("//x:x.bzl", "x")
        y : list[int] = [0]
        y[0] = x
        
        """.trimIndent()
        )
    }
}
