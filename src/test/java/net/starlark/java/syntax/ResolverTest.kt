// Copyright 2015 The Bazel Authors. All rights reserved.
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
import net.starlark.java.syntax.FileOptions.Builder.allowToplevelRebinding
import net.starlark.java.syntax.FileOptions.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.loadBindsGlobally
import net.starlark.java.syntax.Program.getResolvedFunction
import net.starlark.java.syntax.SyntaxError.Exception.errors
import net.starlark.java.syntax.TypeTable.errors
import net.starlark.java.syntax.TypeTable.ok
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests of the Starlark resolver.  */
@RunWith(JUnit4::class)
class ResolverTest {
    private val options: net.starlark.java.syntax.FileOptions.Builder = net.starlark.java.syntax.FileOptions.builder()

    // Resolves a file using the current options,
    // in an environment with a single predeclared name, pre.
    // Errors are recorded in file.errors().
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun resolveFile(vararg lines: String?): net.starlark.java.syntax.StarlarkFile {
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(*lines)
        val file: net.starlark.java.syntax.StarlarkFile =
            net.starlark.java.syntax.StarlarkFile.parse(input, options.build())
        net.starlark.java.syntax.Resolver.resolveFile(
            file,
            net.starlark.java.syntax.TestUtils.Module.Companion.withPredeclared("pre")
        )
        return file
    }

    // Assertions that parsing and resolution succeeds.
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun assertValid(vararg lines: String?) {
        getValidFile(*lines)
    }

    // Asserts that parsing of the program succeeds but resolution fails
    // with at least the specified error.
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun assertInvalid(expectedError: String?, vararg lines: String?) {
        val errors: MutableList<net.starlark.java.syntax.SyntaxError?> = getResolutionErrors(*lines)
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, expectedError)
    }

    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun getValidFile(vararg lines: String?): net.starlark.java.syntax.StarlarkFile {
        val file: net.starlark.java.syntax.StarlarkFile = resolveFile(*lines)
        if (!file.ok()) {
            throw net.starlark.java.syntax.SyntaxError.Exception(file.errors())
        }
        return file
    }

    // Returns the non-empty list of resolution errors of the program.
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun getResolutionErrors(vararg lines: String?): MutableList<net.starlark.java.syntax.SyntaxError?> {
        val file: net.starlark.java.syntax.StarlarkFile = resolveFile(*lines)
        if (file.ok()) {
            throw java.lang.AssertionError("resolution succeeded unexpectedly")
        }
        return file.errors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAssignmentNotValidLValue() {
        assertInvalid("cannot assign to '\"a\"'", "'a' = 1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAugmentedAssignmentWithMultipleLValues() {
        assertInvalid(
            "cannot perform augmented assignment on a list or tuple expression",  //
            "a, b += 2, 3"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReturnOutsideFunction() {
        assertInvalid(
            "return statements must be inside a function",  //
            "return 2\n"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadAfterStatement() {
        options.requireLoadStatementsFirst(true)
        val errors: MutableList<net.starlark.java.syntax.SyntaxError?> =
            getResolutionErrors("a = 5", "load(':b.bzl', 'c')")
        net.starlark.java.syntax.TestUtils.assertContainsError(
            errors,
            ":2:1: load statements must appear before any other statement"
        )
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:1: \tfirst non-load statement appears here")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowLoadAfterStatement() {
        options.requireLoadStatementsFirst(false)
        assertValid(
            "a = 5",  //
            "load(':b.bzl', 'c')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicateBindingWithinALoadStatement() {
        assertInvalid(
            "load statement defines 'x' more than once",  //
            "load('module', 'x', 'x')"
        )
        assertInvalid(
            "load statement defines 'x' more than once",  //
            "load('module', 'x', x='y')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConflictsAtToplevel_default() {
        var errors: MutableList<net.starlark.java.syntax.SyntaxError?> = getResolutionErrors("x=1; x=2")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:6: 'x' redeclared at top level")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:1: 'x' previously declared here")

        errors = getResolutionErrors("x=1; load('module', 'x')")
        net.starlark.java.syntax.TestUtils.assertContainsError(
            errors,
            ":1:22: conflicting file-local declaration of 'x'"
        )
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:1: 'x' previously declared as global here")

        // Also: "loads must appear first"
        errors = getResolutionErrors("load('module', 'x'); x=1")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:22: conflicting global declaration of 'x'")
        net.starlark.java.syntax.TestUtils.assertContainsError(
            errors,
            ":1:17: 'x' previously declared as file-local here"
        )

        errors = getResolutionErrors("load('module', 'x'); load('module', 'x')")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:38: 'x' redeclared at top level")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:17: 'x' previously declared here")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConflictsAtToplevel_loadBindsGlobally() {
        options.loadBindsGlobally(true)

        var errors: MutableList<net.starlark.java.syntax.SyntaxError?> = getResolutionErrors("x=1; x=2")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:6: 'x' redeclared at top level")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:1: 'x' previously declared here")

        errors = getResolutionErrors("x=1; load('module', 'x')")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:22: 'x' redeclared at top level")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:1: 'x' previously declared here")

        // Also: "loads must appear first"
        errors = getResolutionErrors("load('module', 'x'); x=1")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:22: 'x' redeclared at top level")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:17: 'x' previously declared here")

        errors = getResolutionErrors("load('module', 'x'); load('module', 'x')")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:38: 'x' redeclared at top level")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:17: 'x' previously declared here")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConflictsAtToplevel_allowToplevelRebinding() {
        // This flag allows rebinding of globals, or of file-locals,
        // but a given name cannot be both globally and file-locally bound.
        options.allowToplevelRebinding(true)

        assertValid("x=1; x=2")

        var errors: MutableList<net.starlark.java.syntax.SyntaxError?> = getResolutionErrors("x=1; load('module', 'x')")
        net.starlark.java.syntax.TestUtils.assertContainsError(
            errors,
            ":1:22: conflicting file-local declaration of 'x'"
        )
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:1: 'x' previously declared as global here")

        // Also: "loads must appear first"
        errors = getResolutionErrors("load('module', 'x'); x=1")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:22: conflicting global declaration of 'x'")
        net.starlark.java.syntax.TestUtils.assertContainsError(
            errors,
            ":1:17: 'x' previously declared as file-local here"
        )

        assertValid("load('module', 'x'); load('module', 'x')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConflictsAtToplevel_loadBindsGlobally_allowToplevelRebinding() {
        options.loadBindsGlobally(true)
        options.allowToplevelRebinding(true)
        options.requireLoadStatementsFirst(false)

        assertValid("x=1; x=2")
        assertValid("x=1; load('module', 'x')")
        assertValid("load('module', 'x'); x=1")
        assertValid("load('module', 'x'); load('module', 'x')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForbiddenToplevelIfStatement() {
        assertInvalid(
            "if statements are not allowed at the top level",  //
            "if pre: a = 2"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUndefinedName() {
        assertInvalid("name 'foo' is not defined", "[foo for x in []]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionLocalVariable() {
        assertInvalid(
            "name 'a' is not defined",  //
            "def func2(b):",
            "  c = b",
            "  c = a",
            "def func1():",
            "  a = 1",
            "  func2(2)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionLocalVariableDoesNotEffectGlobalEnv() {
        assertInvalid(
            "name 'a' is not defined",  //
            "def func1():",
            "  a = 1",
            "def func2(b):",
            "  b = a"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionParameterDoesNotEffectGlobalEnv() {
        assertInvalid(
            "name 'a' is not defined",  //
            "def func1(a):",
            "  return a",
            "def func2():",
            "  b = a"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefinitionByItself() {
        // Variables are assumed to be statically visible in the block (even if they might not be
        // initialized).
        assertValid("a = a")
        assertValid("a += a")
        assertValid("[[] for _ in [] for a in a]")
        assertValid("def f():", "  for a in a: pass")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLocalEnvironmentsAreSeparate() {
        assertValid(
            "def func1():",  //
            "  a = 1",
            "def func2():",
            "  a = 'abc'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuiltinsCanBeShadowed() {
        assertValid("pre = 1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobalShadowsPredeclaredForEntireFile() {
        // global 'pre' shadows predeclared of same name.
        val errors: MutableList<net.starlark.java.syntax.SyntaxError?> = getResolutionErrors("pre; pre = 1; pre = 2")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:15: 'pre' redeclared at top level")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:6: 'pre' previously declared here")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTwoFunctionsWithTheSameName() {
        // Def statements act just like an assignment statement.
        val errors: MutableList<net.starlark.java.syntax.SyntaxError?> =
            getResolutionErrors("def foo(): pass", "def foo(): pass")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":2:5: 'foo' redeclared at top level")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":1:5: 'foo' previously declared here")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionDefRecursion() {
        assertValid("def func():", "  func()\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMutualRecursion() {
        assertValid("def foo(i):", "  bar(i)", "def bar(i):", "  foo(i)", "foo(4)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionDefinedBelow() {
        assertValid("def bar(): a = foo() + 'a'", "def foo(): return 1\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobalDefinedBelow() {
        assertValid("def bar(): return x", "x = 5\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLocalVariableDefinedBelow() {
        assertValid(
            "def bar():",
            "    for i in pre(5):",
            "        if i > 2: return x",
            "        x = i" // x is visible in the entire function block
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionDoesNotExist() {
        assertInvalid(
            "name 'foo' is not defined",  //
            "def bar(): a = foo() + 'a'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTupleLiteralWorksForDifferentTypes() {
        assertValid("('a', 1)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictExpressionDifferentValueTypeWorks() {
        assertValid("{'a': 1, 'b': 'c'}")
    }

    // Starlark built-in functions specific tests
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFuncReturningDictAssignmentAsLValue() {
        assertValid(
            "def my_dict():",  //
            "  return {'a': 1}",
            "def func():",
            "  my_dict()['b'] = 2"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyLiteralGenericIsSetInLaterConcatWorks() {
        assertValid(
            "def func():",  //
            "  s = {}",
            "  s['a'] = 'b'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuiltinGlobalFunctionsReadOnlyInFuncDefBody() {
        assertValid("def func():", "  rule = 'abc'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuiltinGlobalFunctionsReadOnlyAsFuncDefArg() {
        assertValid("def func(rule):", "  return rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelForFails() {
        assertInvalid(
            "for loops are not allowed at the top level",  //
            "for i in []: 0\n"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testComprehension() {
        // The operand of the first for clause is resolved outside the comprehension block.
        assertInvalid("name 'x' is not defined", "[() for x in x]")
        assertValid("[() for x in () for x in x]") // forward ref
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicateParameter() {
        assertInvalid(
            "duplicate parameter: a",
            "def func(a, b, a):",  //
            "  a = 1"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParameterOrdering() {
        // ordering
        assertInvalid(
            "required parameter a may not follow **kwargs",  //
            "def func(**kwargs, a): pass"
        )
        assertInvalid(
            "required positional parameter b may not follow an optional parameter",  //
            "def func(a=1, b): pass"
        )
        assertInvalid(
            "optional parameter may not follow **kwargs",  //
            "def func(**kwargs, a=1): pass"
        )
        assertInvalid(
            "* parameter may not follow **kwargs",  //
            "def func(**kwargs, *args): pass"
        )
        assertInvalid(
            "* parameter may not follow **kwargs",  //
            "def func(**kwargs, *): pass"
        )
        assertInvalid(
            "bare * must be followed by keyword-only parameters",  //
            "def func(*): pass"
        )

        // duplicate parameters
        assertInvalid("duplicate parameter: a", "def func(a, a): pass")
        assertInvalid("duplicate parameter: a", "def func(a, a=1): pass")
        assertInvalid("duplicate parameter: a", "def func(a, *a): pass")
        assertInvalid("duplicate parameter: a", "def func(*a, a): pass")
        assertInvalid("duplicate parameter: a", "def func(*a, a=1): pass")
        assertInvalid("duplicate parameter: a", "def func(a, **a): pass")
        assertInvalid("duplicate parameter: a", "def func(*a, **a): pass")

        // multiple *
        assertInvalid("multiple * parameters not allowed", "def func(a, *, b, *): pass")
        assertInvalid("multiple * parameters not allowed", "def func(a, *args, b, *): pass")
        assertInvalid("multiple * parameters not allowed", "def func(a, *, b, *args): pass")
        assertInvalid("multiple * parameters not allowed", "def func(a, *args, b, *args): pass")

        // multiple **kwargs
        assertInvalid("multiple ** parameters not allowed", "def func(**kwargs, **kwargs): pass")

        assertValid("def f(a, b, c=1, d=2, *args, e, f=3, g, **kwargs): pass")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgumentOrdering() {
        // positionals go before keywords
        assertInvalid(
            "positional argument may not follow keyword",  //
            "pre(a=1, 0)"
        )

        // keywords must be unique
        assertInvalid(
            "duplicate keyword argument: a",  //
            "pre(a=1, a=2)"
        )

        // no arguments after **kwargs
        assertInvalid(
            "positional argument may not follow **kwargs",  //
            "pre(**0, 0)"
        )
        assertInvalid(
            "keyword argument a may not follow **kwargs",  //
            "pre(**0, a=1)"
        )
        assertInvalid(
            "*args may not follow **kwargs",  //
            "pre(**0, *0)"
        )
        assertInvalid(
            "multiple **kwargs not allowed",  //
            "pre(**0, **0)"
        )
        assertInvalid(
            "*args may not follow **kwargs",  // also, a parse error
            "pre(**0, *)"
        )

        // bad arguments after *args
        assertInvalid(
            "positional argument may not follow *args",  //
            "pre(*0, 1)"
        )
        assertInvalid(
            "keyword argument a may not follow *args",  //
            "pre(*0, a=1)"
        ) // Python (even v2) allows this
        assertInvalid(
            "multiple *args not allowed",  //
            "pre(*0, *0)"
        )

        assertValid("pre(0, a=0, *0, **0)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUndefError() {
        // Regression test for a poor error message.
        val errors: MutableList<net.starlark.java.syntax.SyntaxError?> = getResolutionErrors("lambda: undef")
        Truth.assertThat(errors.get(0).message()).isEqualTo("name 'undef' is not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBindingScopeAndIndex_basic() {
        checkBindings( // Assign successive indices.
            "xᴳ₀ = 0",  // Visit LHS.
            "yᴳ₁, zᴳ₂ = 1, 2",  // Visit function identifiers and subscripts, don't visit field names, resolve predeclareds.
            "xᴳ₀(yᴳ₁.f  , preᴾ₀[zᴳ₂])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBindingScopeAndIndex_bindingAfterFirstUse() {
        checkBindings( // Use before definition. (Dynamically invalid, but resolves just fine.)
            "xᴳ₀",
            "xᴳ₀ = 0",  // Same in local scope, but permit reassignment.
            "def fᴳ₁():",
            "  yᴸ₀",
            "  yᴸ₀ = 0",
            "  yᴸ₀ = 0",
            "  yᴸ₀"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBindingScopeAndIndex_functionBlock() {
        checkBindings(
            "xᴳ₀ = 0",
            "yᴳ₁ = 1",  // Default expr resolves outside function block, for all params.
            "def fᴳ₂(xᴸ₀ = xᴳ₀, zᴸ₁ = xᴳ₀):",  // Param available within function block, and shadows global.
            "  xᴸ₀",
            "  zᴸ₁ = 1",  // New bindings in body are local to function block.
            "  wᴸ₂ = 2",  // Global is referenced directly without cell/free indirection.
            "  yᴳ₁",  // Can resolve recursive reference to current function.
            "  fᴳ₂"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBindingScopeAndIndex_nestedFunctions() {
        checkBindings(
            "aᴳ₀ = 0",  // a used in nested function but not a cell because it's global
            "bᴳ₁ = 1",  // b not used in nested function
            "def fᴳ₂():",
            "  cᶜ₀ = aᴳ₀",  // c used in nested function, so made a cell; still increments index
            "  dᴸ₁ = 1",  // d not used in nested function, remains local
            "  def gᴸ₂():",
            "    cᶠ₀",  // use of enclosing local becomes free; does not increment index
            "    eᴸ₀ = 1"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBindingScopeAndIndex_comprehensions() {
        checkBindings(
            "xᴳ₀ = 0",
            "yᴳ₁ = 0",  // Comprehensions have their own block.
            // First for-clause resolved outside of this block.
            // Subsequent for-clauses resolved inside this block.
            "[xᴸ₀ for xᴸ₀ in xᴳ₀ for xᴸ₀ in xᴸ₀ if yᴳ₁]"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBindingScopeAndIndex_loads() {
        // Load statements create file-local bindings.
        // Functions that reference load bindings are closures.
        checkBindings(
            """
        load('module', aᶜ₀='a', bᴸ₁='b')
        aᶜ₀, bᴸ₁
        def fᴳ₀():
          aᶠ₀
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBindingScopeAndIndex_functionAnnotations() {
        options.allowTypeSyntax(true)
        options.resolveTypeSyntax(true)

        checkBindings(
            """
        Tᴳ₀ = 1
        def fᴳ₁(xᴸ₀: Tᴳ₀ = preᴾ₀) -> preᴾ₀:
          pass
        
        """.trimIndent()
        )

        // Type annotations are resolved outside of the function's block, just like default expressions.
        checkBindings(
            """
        xᴳ₀ = 1
        def fᴳ₁(xᴸ₀: xᴳ₀) -> xᴳ₀:
          xᴸ₀
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBindingScopeAndIndex_varAnnotations() {
        options.allowTypeSyntax(true)
        options.resolveTypeSyntax(true)

        checkBindings(
            "Tᴳ₀ = 1",  // A var statement creates a binding for its variable (x), and its type annotation (T) has
            // its binding set.
            "xᴳ₁ : Tᴳ₀",  // Var statements can shadow predeclared.
            "preᴳ₂ : Tᴳ₀",
            "def fᴳ₃():",
            "  xᴳ₁",
            "  preᴳ₂"
        )

        // Type annotations in assignments have their bindings set.
        checkBindings("xᴳ₀ : preᴾ₀ = 1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBindingScopeAndIndex_typeAlias() {
        options.allowTypeSyntax(true)
        options.resolveTypeSyntax(true)

        // A type declaration creates a binding for its variable (T) and its definition has its bindings
        // set.
        checkBindings("type Tᴳ₀ = preᴾ₀")

        // A type declaration can shadow a predeclared.
        checkBindings(
            """
        Tᴳ₀ = 1
        type preᴳ₁ = Tᴳ₀
        
        """.trimIndent()
        )

        // This is dumb and illegal, but not for resolver-related reasons.
        checkBindings("type Tᴳ₀ = Tᴳ₀")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBindingScopeAndIndex_cast() {
        options.allowTypeSyntax(true)
        options.resolveTypeSyntax(true)

        checkBindings("cast(preᴾ₀, preᴾ₀)")
    }

    // TODO: #27848 - Add test case for isinstance(), once supported.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBindingScopeAndIndex_typeSyntaxNotResolvedWhenFlagDisabled() {
        options.allowTypeSyntax(true)
        options.resolveTypeSyntax(false)

        checkBindings(
            """
        def fᴳ₀(xᴸ₀: T   = preᴾ₀) -> pre  :
          pass
        xᴳ₁ : pre  #
        yᴳ₂ : pre   = 1
        
        """.trimIndent()
        )

        checkBindings(
            """
        type T   = S  #
        cast(T  , preᴾ₀)
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBindingScopeAndIndex_genericTypeVars_notResolved() {
        // Check that these are not currently processed.
        // TODO: #27370 - Add support to the resolver for these.
        options.allowTypeSyntax(true)
        options.resolveTypeSyntax(true)
        checkBindings(
            "def fᴳ₀[S  , T  ]():",  //
            "  pass",
            "type Fooᴳ₁[X  ] = preᴾ₀"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDocComments() {
        options.allowTypeSyntax(true)
        val file: net.starlark.java.syntax.StarlarkFile =
            getValidFile(
                """
            #: Doc for FOO
            #: multiline
            FOO = 1

            BAR, BAZ = (2, 3)  #: Applies to LHS list

            #: Applies to var annotation without initialier
            QUX : pre
            QUUX : pre #: And the trailing version...
            
            """.trimIndent()
            )

        Truth.assertThat(file.docCommentsMap.keys)
            .containsExactly("FOO", "BAR", "BAZ", "QUX", "QUUX")
            .inOrder()
        Truth.assertThat(
            file.docCommentsMap.values.stream()
                .map<String?> { obj: net.starlark.java.syntax.DocComments? -> obj.getText() })
            .containsExactly(
                "Doc for FOO\nmultiline",
                "Applies to LHS list",
                "Applies to LHS list",
                "Applies to var annotation without initialier",
                "And the trailing version..."
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeAliasStatement_mustBeAtTopLevel() {
        options.allowTypeSyntax(true)
        assertInvalid(
            ":2:3: type alias statement not at top level",
            """
        def f():
          type X = int
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeAliasStatement_redeclarationDisallowed() {
        options.allowTypeSyntax(true)
        options.resolveTypeSyntax(true)
        assertInvalid(
            ":2:6: 'T' redeclared at top level",
            """
        type T = pre
        type T = pre
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:6: 'T' redeclared at top level",
            """
        T = 1
        type T = pre
        
        """.trimIndent()
        )
        assertInvalid(
            ":2:1: 'T' redeclared at top level",
            """
        type T = pre
        T = 1
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeAliasStatement_redeclarationAllowedWithFlag() {
        options.allowTypeSyntax(true)
        options.allowToplevelRebinding(true)
        assertValid(
            """
        type T = pre
        type T = pre
        
        """.trimIndent()
        )
        assertValid(
            """
        T = 1
        type T = pre
        
        """.trimIndent()
        )
        assertValid(
            """
        type T = pre
        T = 1
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleTypeAnnotationsDisallowed_topLevel() {
        options.allowTypeSyntax(true)
        val errors: MutableList<net.starlark.java.syntax.SyntaxError?> =
            getResolutionErrors( // All four permutations of VarStatement vs annotated assignment statement.
                """
            a : int
            a : str

            b : int = 123
            b : str

            c : int
            c : str = "abc"

            d : int = 123
            d : str = "abc"
            
            """.trimIndent()
            )
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":2:1: 'a' redeclared at top level")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":5:1: 'b' redeclared at top level")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":8:1: 'c' redeclared at top level")
        net.starlark.java.syntax.TestUtils.assertContainsError(errors, ":11:1: 'd' redeclared at top level")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleAnnotationWithReassignmentIsAllowed() {
        options.allowTypeSyntax(true)
        assertValid(
            """
        def f():
            a : pre
            a = 123
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnnotationFollowedByAssignmentStillCountsAsRedeclaration() {
        options.allowTypeSyntax(true)
        assertInvalid(
            "'a' redeclared at top level",
            """
        a : int
        a = 123
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCastExpression_cannotBeLhsOfAssignment() {
        options.allowTypeSyntax(true)
        val file: net.starlark.java.syntax.StarlarkFile =
            resolveFile(
                """
            cast(int, x) = 42
            cast(int, y[0]) = 42
            cast(list[int], z) += [42]
            
            """.trimIndent()
            )
        Truth.assertThat(file.ok()).isFalse()
        net.starlark.java.syntax.TestUtils.assertContainsError(file.errors(), "cannot assign to 'cast(int, x)'")
        net.starlark.java.syntax.TestUtils.assertContainsError(file.errors(), "cannot assign to 'cast(int, y[0])'")
        net.starlark.java.syntax.TestUtils.assertContainsError(file.errors(), "cannot assign to 'cast(list[int], z)'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCastExpression_valueAndType_areResolved() {
        options.allowTypeSyntax(true)
        options.resolveTypeSyntax(true)

        val goodFile: net.starlark.java.syntax.StarlarkFile = resolveFile("cast(pre, pre)")
        Truth.assertThat(goodFile.ok()).isTrue()

        val badFile: net.starlark.java.syntax.StarlarkFile = resolveFile("cast(a, b)")
        Truth.assertThat(badFile.ok()).isFalse()
        net.starlark.java.syntax.TestUtils.assertContainsError(badFile.errors(), "name 'a' is not defined")
        net.starlark.java.syntax.TestUtils.assertContainsError(badFile.errors(), "name 'b' is not defined")
    }

    // TODO: #27848 - Resolve types in isinstance().
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsInstanceExpression_notYetSupported() {
        options.allowTypeSyntax(true)
        val badFile: net.starlark.java.syntax.StarlarkFile = resolveFile("isinstance(x, list)")
        Truth.assertThat(badFile.ok()).isFalse()
        net.starlark.java.syntax.TestUtils.assertContainsError(badFile.errors(), "isinstance() is not yet supported")
    }

    // checkBindings verifies the binding (scope and index) of each identifier.
    // Every variable must be followed by a superscript letter (its scope)
    // and a subscript numeral (its index). They are replaced by spaces, the
    // file is resolved, and then the computed information is written over
    // the spaces. The resulting string must match the input.
    @Throws(java.lang.Exception::class)
    private fun checkBindings(vararg lines: String?) {
        val src: String = com.google.common.base.Joiner.on("\n").join(lines)
        val file: net.starlark.java.syntax.StarlarkFile = resolveFile(src.replace("[₀₁₂₃₄₅₆₇₈₉ᴸᴳᶜᶠᴾᵁ]".toRegex(), " "))
        if (!file.ok()) {
            throw java.lang.AssertionError("resolution failed: " + file.errors())
        }
        val out: Array<String?> = arrayOf<String>(src)
        object : net.starlark.java.syntax.NodeVisitor() {
            override fun visit(id: net.starlark.java.syntax.Identifier) {
                // Replace ...x__... with ...xᴸ₀...
                val binding: net.starlark.java.syntax.Resolver.Binding? = id.binding
                var suffix = ""
                if (binding != null) {
                    suffix += "ᴸᴳᶜᶠᴾᵁ".get(binding.scope.ordinal()) // follow order of enum
                    suffix += "₀₁₂₃₄₅₆₇₈₉".get(binding.index) // 10 is plenty
                } else {
                    suffix = "  "
                }
                out[0] =
                    (out[0].substring(0, id.getEndOffset())
                            + suffix
                            + out[0].substring(id.getEndOffset() + 2))
            }
        }.visit(file)
        Truth.assertThat(out[0]).isEqualTo(src)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mutationFreeAtTopLevelHeuristic() {
        // Standard mutation-free file
        Truth.assertThat(
            resolveFile(
                """
                    my_list = [1, 2, 3]
                    other_list = [4, 5]
                    combined = my_list + other_list
                    
                    """.trimIndent()
            )
                .getResolvedFunction()
                .isMutationFreeAtTopLevel()
        )
            .isTrue()

        // Mutating call expression at top level
        Truth.assertThat(
            resolveFile(
                """
                    x = []
                    x.append(1)
                    
                    """.trimIndent()
            )
                .getResolvedFunction()
                .isMutationFreeAtTopLevel()
        )
            .isFalse()

        // Mutating index assignment at top level
        Truth.assertThat(
            resolveFile(
                """
                    x = [1]
                    x[0] = 2
                    
                    """.trimIndent()
            )
                .getResolvedFunction()
                .isMutationFreeAtTopLevel()
        )
            .isFalse()

        // Read-only index expression at top level (should be mutation-free!)
        Truth.assertThat(
            resolveFile(
                """
                    x = [1]
                    y = x[0]
                    
                    """.trimIndent()
            )
                .getResolvedFunction()
                .isMutationFreeAtTopLevel()
        )
            .isTrue()

        // Mutations nested within functions (should remain mutation-free at top level!)
        Truth.assertThat(
            resolveFile(
                """
                    def my_func():
                      local_list = [1, 2, 3]
                      local_list += [4]
                      local_list.append(5)
                      local_list[0] = 6
                    
                    """.trimIndent()
            )
                .getResolvedFunction()
                .isMutationFreeAtTopLevel()
        )
            .isTrue()

        // Top-level augmented assignment (when allowToplevelRebinding is true)
        options.allowToplevelRebinding(true)
        Truth.assertThat(
            resolveFile(
                """
                    my_list = [1, 2, 3]
                    my_list += [4]
                    
                    """.trimIndent()
            )
                .getResolvedFunction()
                .isMutationFreeAtTopLevel()
        )
            .isFalse()
        options.allowToplevelRebinding(false)

        // Pure list/tuple unpacking at top level (should be mutation-free!)
        Truth.assertThat(
            resolveFile(
                """
                    a, b = [1, 2]
                    
                    """.trimIndent()
            )
                .getResolvedFunction()
                .isMutationFreeAtTopLevel()
        )
            .isTrue()

        // Impure list unpacking with index assignment at top level
        Truth.assertThat(
            resolveFile(
                """
                    a = [1]
                    b, a[0] = [2, 3]
                    
                    """.trimIndent()
            )
                .getResolvedFunction()
                .isMutationFreeAtTopLevel()
        )
            .isFalse()

        // Mutate a struct field (rejected at runtime, but technically legal syntactically)
        Truth.assertThat(
            resolveFile(
                """
                    s = struct(a = 1)
                    s.a = 2
                    
                    """.trimIndent()
            )
                .getResolvedFunction()
                .isMutationFreeAtTopLevel()
        )
            .isFalse()
    }
}
