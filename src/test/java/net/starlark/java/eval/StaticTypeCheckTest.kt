// Copyright 2026 The Bazel Authors. All rights reserved.
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
package net.starlark.java.eval

import net.starlark.java.annot.StarlarkBuiltin

/**
 * Integrated tests for static type checking of Starlark code.
 * 
 * 
 * The test suite `syntax/TypeCheckerTest.java` checks the behavior of the static type
 * checker and the base type definitions in the syntax package. In contrast, this suite checks the
 * overall process of static type checking on a Starlark program, using the production universal
 * types defined in the eval/ package. This includes for instance the machinery to generate type
 * information for [StarlarkBuiltin]s.
 */
@RunWith(JUnit4::class)
class StaticTypeCheckTest {
    private val options: net.starlark.java.syntax.FileOptions.Builder = net.starlark.java.syntax.FileOptions.builder()
        .allowTypeSyntax(true)
        .resolveTypeSyntax(true) // This lets us construct simpler test cases without wrapper `def` statements.
        .allowToplevelRebinding(true)

    private var module: java.lang.Module? = java.lang.Module.create()

    private val loader: net.starlark.java.syntax.TypeTagger.Loader? = null

    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun compile(vararg lines: String?): net.starlark.java.syntax.Program {
        com.google.common.base.Preconditions.checkArgument(lines.size > 0)
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(lines)
        val file: net.starlark.java.syntax.StarlarkFile? =
            net.starlark.java.syntax.StarlarkFile.parse(input, options.build())
        val prog: net.starlark.java.syntax.Program = net.starlark.java.syntax.Program.compileFile(file, module)
        val typeTable: net.starlark.java.syntax.TypeTable =
            net.starlark.java.syntax.TypeTagger.tagProgram(prog, module, loader)
        if (typeTable.ok()) {
            net.starlark.java.syntax.TypeChecker.checkProgram(prog, typeTable, module)
        }
        if (!typeTable.ok()) {
            throw net.starlark.java.syntax.SyntaxError.Exception(typeTable.errors())
        }
        return prog.withTypeTable(typeTable)
    }

    private fun assertValid(vararg lines: String?) {
        try {
            compile(*lines)
        } catch (ex: net.starlark.java.syntax.SyntaxError.Exception) {
            throw java.lang.AssertionError("Expected success, but got: " + ex.getMessage(), ex)
        }
    }

    private fun assertInvalid(message: String?, vararg lines: String?) {
        val ex: net.starlark.java.syntax.SyntaxError.Exception =
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable { compile(*lines) })
        net.starlark.java.syntax.TestUtils.assertContainsError(ex.errors(), message)
    }

    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun inferType(expr: String?): net.starlark.java.syntax.StarlarkType? {
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(expr)
        val expression: net.starlark.java.syntax.Expression? =
            net.starlark.java.syntax.Expression.parse(input, options.build())
        val program: net.starlark.java.syntax.Program =
            net.starlark.java.syntax.Program.compileExpr(expression, module, options.build())
        return program.getTypeTable().getType(program.getResolvedFunction())
    }

    @org.junit.Test
    fun typecheckSuccess() {
        assertValid("n = 123 + 123")
    }

    @org.junit.Test
    fun typecheckFailure() {
        assertInvalid(
            "operator '+' cannot be applied to types 'int' and 'str'",
            """
        n = 123 + 'abc'
        _unused: bool  # ensure file uses type syntax
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    fun unknownSymbolAsType() {
        assertInvalid(
            "name 'unknown' is not defined",
            """
        x: unknown
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    fun nonTypeSymbolAsType() {
        assertInvalid(
            "universal symbol 'len' cannot be used as a type",
            """
        x: len
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    fun noneAsType() {
        assertValid("x: None = None")

        assertInvalid(
            "cannot assign type 'int' to 'x' of type 'None'",
            """
        x: None = 123
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    fun starlarkBuiltinAsType() {
        assertValid("x: list[int] = [123]")

        assertInvalid(
            "cannot assign type 'list[str]' to 'x' of type 'list[int]'",
            """
        x: list[int] = ["abc"]
        
        """.trimIndent()
        )
    }

    @StarlarkBuiltin(name = "BadBodyTypeBuiltin")
    object BadBodyTypeBuiltin : StarlarkValue {
        val associatedTypeConstructor: net.starlark.java.syntax.TypeConstructor?
            get() {
                throw java.lang.RuntimeException("fail")
            }
    }

    @StarlarkBuiltin(name = "BadSignatureTypeBuiltin")
    class BadSignatureTypeBuiltin : StarlarkValue {
        val associatedTypeConstructor: net.starlark.java.syntax.TypeConstructor?
            get() { // missing `static`
                throw java.lang.RuntimeException("fail")
            }
    }

    @StarlarkBuiltin(name = "MissingStaticMethodTypeBuiltin")
    class MissingStaticMethodTypeBuiltin : StarlarkValue

    class DummyLibrary {
        @StarlarkMethod(name = "BadSignature", documented = false, isTypeConstructor = true)
        fun badSignature(): BadSignatureTypeBuiltin {
            return BadSignatureTypeBuiltin()
        }

        @StarlarkMethod(name = "BadBody", documented = false, isTypeConstructor = true)
        fun badBody(): BadBodyTypeBuiltin {
            return BadBodyTypeBuiltin()
        }

        @StarlarkMethod(name = "MissingStaticMethod", documented = false, isTypeConstructor = true)
        fun missingStaticMethod(): MissingStaticMethodTypeBuiltin {
            return MissingStaticMethodTypeBuiltin()
        }
    }

    @org.junit.Test
    fun starlarkBuiltinWithBadAssociatedTypeConstructor() {
        val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        Starlark.addMethods(env, DummyLibrary())
        module = java.lang.Module.withPredeclared(StarlarkSemantics.DEFAULT, env.buildOrThrow())

        var ex: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { compile("x: BadSignature = None") })
        Truth.assertThat(ex)
            .hasMessageThat()
            .containsMatch(
                ".*BadSignatureTypeBuiltin#getAssociatedTypeConstructor has an invalid signature"
            )

        ex = org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { compile("x: BadBody = None") })
        Truth.assertThat(ex)
            .hasMessageThat()
            .containsMatch("Error invoking .*BadBodyTypeBuiltin#getAssociatedTypeConstructor")

        ex =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { compile("x: MissingStaticMethod = None") })
        Truth.assertThat(ex)
            .hasMessageThat()
            .containsMatch("invalid type constructor proxy: .*MissingStaticMethodTypeBuiltin")
    }

    @org.junit.Test
    fun listMethods() {
        assertValid(
            """
        x: list[int]
        x.pop(0)
        
        """.trimIndent()
        )

        assertInvalid(
            "in call to 'x.pop()', parameter 'i' got value of type 'str', want 'int'",
            """
        x: list[int]
        x.pop("abc")
        
        """.trimIndent()
        )

        assertInvalid(
            "'x' of type 'list[int]' does not have field 'does_not_exist'",
            """
        x: list[int]
        x.does_not_exist
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    fun dictMethods() {
        assertValid(
            """
        d: dict[str, int]
        v = d.get("a", 0)
        d.setdefault("b", 2)
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    fun setMethods() {
        assertValid(
            """
        s: set[int]
        s.add(3)
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    fun strMethods() {
        // Note that StringModule is special-cased to take the receiver string object as a separate
        // parameter to the Java method, yet it doesn't appear in the signature for type-checking
        // purposes.
        assertValid(
            """
        s: str
        s.startswith("abc")
        
        """.trimIndent()
        )

        assertInvalid(
            "'s.startswith()' missing 1 required argument: sub",
            """
        s: str
        s.startswith()
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun universalSymbolTypes() {
        assertValid(
            """
        b: bool = True
        b = False
        n: None = None
        s: str = str(123)
        i: int = int(123)
        f: float = float(123)
        l: list = list()
        d: dict = dict()
        se: set = set()
        
        """.trimIndent()
        )
        assertInvalid("cannot assign type 'bool' to 'x' of type 'str'", "x: str = True")
        assertInvalid("cannot assign type 'bool' to 'x' of type 'str'", "x: str = False")
        assertInvalid("cannot assign type 'None' to 'x' of type 'str'", "x: str = None")
        assertInvalid("cannot assign type 'str' to 'x' of type 'int'", "x: int = str(123)")
        assertInvalid("cannot assign type 'int' to 'x' of type 'str'", "x: str = int(123)")
        assertInvalid("cannot assign type 'float' to 'x' of type 'str'", "x: str = float(123)")
        assertInvalid("cannot assign type 'list[Any]' to 'x' of type 'str'", "x: str = list()")
        assertInvalid("cannot assign type 'dict[Any, Any]' to 'x' of type 'str'", "x: str = dict()")
        assertInvalid("cannot assign type 'set[Any]' to 'x' of type 'str'", "x: str = set()")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun predeclaredSymbolTypes() {
        module =
            java.lang.Module.withPredeclared(
                StarlarkSemantics.DEFAULT,
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "PREDECLARED_INT",
                    StarlarkInt.of(123),
                    "PREDECLARED_STR",
                    "abc"
                )
            )
        assertValid(
            """
        x: int = PREDECLARED_INT
        y: str = PREDECLARED_STR
        
        """.trimIndent()
        )
        assertInvalid("cannot assign type 'int' to 'x' of type 'str'", "x: str = PREDECLARED_INT")
        assertInvalid("cannot assign type 'str' to 'x' of type 'int'", "x: int = PREDECLARED_STR")
    }

    // No StarlarkBuiltin annotation.
    class MyUnannotatedType : StarlarkValue {
        @StarlarkMethod(name = "foo", doc = "...")
        fun foo(): Int {
            return 123
        }
    }

    @StarlarkBuiltin(name = "MyType")
    class MyType : StarlarkValue {
        @StarlarkMethod(name = "foo", doc = "...")
        fun foo(): Int {
            return 123
        }
    }

    @StarlarkBuiltin(name = "MySelfCallType")
    class MySelfCallType : StarlarkValue {
        @StarlarkMethod(name = "MySelfCallType", doc = "...", selfCall = true)
        fun selfCall(): Int {
            return 123
        }

        @StarlarkMethod(name = "bar", doc = "...")
        fun bar(): Int {
            return 123
        }
    }

    @StarlarkBuiltin(name = "MyExplicitlyTypedType")
    class MyExplicitlyTypedType : StarlarkValue {
        // Override causes no 'MyExplicitlyTypedType' type to be auto-generated.
        public override fun getStarlarkType(semantics: StarlarkSemantics?): net.starlark.java.syntax.StarlarkType {
            return net.starlark.java.syntax.Types.STRUCT_OF_ANY
        }
    }

    @StarlarkBuiltin(name = "MyExplicitlyTypedSelfCallType")
    class MyExplicitlyTypedSelfCallType : StarlarkValue {
        @StarlarkMethod(name = "MyExplicitlyTypedSelfCallType", doc = "...", selfCall = true)
        fun selfCall(): Int {
            return 123
        }

        // Override causes no 'MyExplicitlyTypedSelfCallType' type to be auto-generated.
        public override fun getStarlarkType(semantics: StarlarkSemantics?): net.starlark.java.syntax.StarlarkType {
            return object : net.starlark.java.syntax.StarlarkType() {
                override fun toString(): String {
                    return "ExplicitlyTypedSelfCall"
                }

                val supertypes: com.google.common.collect.ImmutableList<net.starlark.java.syntax.StarlarkType?>?
                    get() = com.google.common.collect.ImmutableList.of<net.starlark.java.syntax.StarlarkType?>( // Nullary callable returning int.
                        net.starlark.java.syntax.Types.callable(
                            com.google.common.collect.ImmutableList.of<String?>(),
                            com.google.common.collect.ImmutableList.of<net.starlark.java.syntax.StarlarkType?>(),
                            0,
                            0,
                            com.google.common.collect.ImmutableSet.of<String?>(),
                            null,
                            null,
                            net.starlark.java.syntax.Types.INT
                        )
                    )
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun predeclaredBuiltinTypes() {
        module =
            java.lang.Module.withPredeclared(
                StarlarkSemantics.DEFAULT,
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "my_unannotated_type_value",
                    MyUnannotatedType(),
                    "my_type_value",
                    MyType(),
                    "my_self_call_value",
                    MySelfCallType(),
                    "my_explicitly_typed_value",
                    MyExplicitlyTypedType(),
                    "my_explicitly_typed_self_call_value",
                    MyExplicitlyTypedSelfCallType()
                )
            )
        assertValid(
            """
        a: int = my_unannotated_type_value.foo()
        b: int = my_type_value.foo()
        c: int = my_self_call_value()
        d: int = my_self_call_value.bar()
        e: int = my_explicitly_typed_value.some_field  # typed as struct-of-Any
        f: int = my_explicitly_typed_self_call_value()
        
        """.trimIndent()
        )

        assertInvalid(
            "cannot assign type 'MyUnannotatedType' to 'x' of type 'str'",
            "x: str = my_unannotated_type_value"
        )
        assertInvalid("cannot assign type 'MyType' to 'x' of type 'str'", "x: str = my_type_value")
        assertInvalid(
            "cannot assign type 'MySelfCallType' to 'x' of type 'str'", "x: str = my_self_call_value"
        )
        assertInvalid("cannot assign type 'int' to 'x' of type 'str'", "x: str = my_self_call_value()")
        assertInvalid(
            "cannot assign type 'ExplicitlyTypedSelfCall' to 'x' of type 'str'",
            "x: str = my_explicitly_typed_self_call_value"
        )
        assertInvalid(
            "cannot assign type 'int' to 'x' of type 'float'",
            "x: float = my_explicitly_typed_self_call_value()"
        )
        assertInvalid(
            "cannot assign type 'struct' to 'x' of type 'str'", "x: str = my_explicitly_typed_value"
        )

        assertInvalid(
            "'my_type_value' of type 'MyType' does not have field 'bar'",
            "_: str = my_type_value.bar()"
        )
        assertInvalid("'my_type_value' is not callable; got type 'MyType'", "_: str = my_type_value()")
    }
}
