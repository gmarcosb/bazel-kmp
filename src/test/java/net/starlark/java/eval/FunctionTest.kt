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
package net.starlark.java.eval

import SymbolGenerator.GlobalSymbol
import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import net.starlark.java.eval.Dict
import net.starlark.java.eval.EvaluationTestCase
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkCallable
import net.starlark.java.eval.StarlarkFunction
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkSemantics
import net.starlark.java.eval.StarlarkThread
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** A test class for functions and scoping.  */
@RunWith(JUnit4::class)
class FunctionTest {
    private val ev: EvaluationTestCase = EvaluationTestCase()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDef() {
        ev.exec("def f(a, b=1, *args, c, d=2, e=3, **kwargs): pass")
        val f: StarlarkFunction = ev.lookup("f") as StarlarkFunction
        assertThat(f).isNotNull()
        assertThat(f.getName()).isEqualTo("f")
        assertThat(f.getParameterNames())
            .containsExactly("a", "b", "c", "d", "e", "args", "kwargs")
            .inOrder()
        assertThat(f.getNumOrdinaryParameters()).isEqualTo(2) // a, b
        assertThat(f.getNumKeywordOnlyParameters()).isEqualTo(3) // c, d, e
        assertThat(f.hasVarargs()).isTrue()
        assertThat(f.hasKwargs()).isTrue()
        Truth.assertThat(getDefaults(f))
            .containsExactly(
                null, StarlarkInt.of(1), null, StarlarkInt.of(2), StarlarkInt.of(3), null, null
            )
            .inOrder()

        // same, sans varargs
        ev.exec("def g(a, b=1, *, c, d=2, e=3, **kwargs): pass")
        val g: StarlarkFunction = ev.lookup("g") as StarlarkFunction
        assertThat(g.getParameterNames()).containsExactly("a", "b", "c", "d", "e", "kwargs").inOrder()
        assertThat(g.getNumOrdinaryParameters()).isEqualTo(2) // a, b
        assertThat(g.getNumKeywordOnlyParameters()).isEqualTo(3) // c, d, e
        assertThat(g.hasVarargs()).isFalse()
        assertThat(g.hasKwargs()).isTrue()
        Truth.assertThat(getDefaults(g))
            .containsExactly(null, StarlarkInt.of(1), null, StarlarkInt.of(2), StarlarkInt.of(3), null)
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionDefCallOuterFunc() {
        val params: MutableList<Any?> = java.util.ArrayList<Any?>()
        createOuterFunction(params)
        ev.exec(
            "def func(a):",  //
            "  outer_func(a)",
            "func(1)",
            "func(2)"
        )
        Truth.assertThat(params).containsExactly(StarlarkInt.of(1), StarlarkInt.of(2)).inOrder()
    }

    @Throws(java.lang.Exception::class)
    private fun createOuterFunction(params: MutableList<Any?>) {
        val outerFunc: StarlarkCallable =
            object : StarlarkCallable() {
                val name: String
                    get() = "outer_func"

                @Throws(EvalException::class)
                public override fun call(
                    thread: StarlarkThread?,
                    args: Tuple?,
                    kwargs: Dict<String?, Any?>?
                ): NoneType {
                    params.addAll(args)
                    return Starlark.NONE
                }
            }
        ev.update("outer_func", outerFunc)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionDefNoEffectOutsideScope() {
        ev.update("a", StarlarkInt.of(1))
        ev.exec(
            "def func():",  //
            "  a = 2",
            "func()\n"
        )
        Truth.assertThat(ev.lookup("a")).isEqualTo(StarlarkInt.of(1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionDefGlobalVaribleReadInFunction() {
        ev.exec(
            "a = 1",  //
            "def func():",
            "  b = a",
            "  return b",
            "c = func()\n"
        )
        Truth.assertThat(ev.lookup("c")).isEqualTo(StarlarkInt.of(1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionDefLocalGlobalScope() {
        ev.exec(
            "a = 1",  //
            "def func():",
            "  a = 2",
            "  b = a",
            "  return b",
            "c = func()\n"
        )
        Truth.assertThat(ev.lookup("c")).isEqualTo(StarlarkInt.of(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionDefLocalVariableReferencedBeforeAssignment() {
        ev.checkEvalErrorContains(
            "local variable 'a' is referenced before assignment.",
            "a = 1",
            "def func():",
            "  b = a",
            "  a = 2",
            "  return b",
            "c = func()\n"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionDefLocalVariableReferencedInCallBeforeAssignment() {
        ev.checkEvalErrorContains(
            "local variable 'a' is referenced before assignment.",
            "def dummy(x):",
            "  pass",
            "a = 1",
            "def func():",
            "  dummy(a)",
            "  a = 2",
            "func()\n"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionDefLocalVariableReferencedAfterAssignment() {
        ev.exec(
            "a = 1",  //
            "def func():",
            "  a = 2",
            "  b = a",
            "  a = 3",
            "  return b",
            "c = func()\n"
        )
        Truth.assertThat(ev.lookup("c")).isEqualTo(StarlarkInt.of(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkGlobalComprehensionIsAllowed() {
        ev.exec("a = [i for i in [1, 2, 3]]\n")
        Truth.assertThat(ev.lookup("a") as Iterable<Any?>?)
            .containsExactly(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionReturn() {
        ev.exec(
            "def func():",  //
            "  return 2",
            "b = func()\n"
        )
        Truth.assertThat(ev.lookup("b")).isEqualTo(StarlarkInt.of(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionReturnFromALoop() {
        ev.exec(
            "def func():",  //
            "  for i in [1, 2, 3, 4, 5]:",
            "    return i",
            "b = func()\n"
        )
        Truth.assertThat(ev.lookup("b")).isEqualTo(StarlarkInt.of(1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionExecutesProperly() {
        ev.exec(
            "def func(a):",
            "  b = 1",
            "  if a:",
            "    b = 2",
            "  return b",
            "c = func(0)",
            "d = func(1)\n"
        )
        Truth.assertThat(ev.lookup("c")).isEqualTo(StarlarkInt.of(1))
        Truth.assertThat(ev.lookup("d")).isEqualTo(StarlarkInt.of(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionCallFromFunction() {
        val params: MutableList<Any?> = java.util.ArrayList<Any?>()
        createOuterFunction(params)
        ev.exec(
            "def func2(a):",
            "  outer_func(a)",
            "def func1(b):",
            "  func2(b)",
            "func1(1)",
            "func1(2)\n"
        )
        Truth.assertThat(params).containsExactly(StarlarkInt.of(1), StarlarkInt.of(2)).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionCallFromFunctionReadGlobalVar() {
        ev.exec(
            "a = 1",  //
            "def func2():",
            "  return a",
            "def func1():",
            "  return func2()",
            "b = func1()\n"
        )
        Truth.assertThat(ev.lookup("b")).isEqualTo(StarlarkInt.of(1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionParamCanShadowGlobalVarAfterGlobalVarIsRead() {
        ev.exec(
            "a = 1",
            "def func2(a):",
            "  return 0",
            "def func1():",
            "  dummy = a",
            "  return func2(2)",
            "b = func1()\n"
        )
        Truth.assertThat(ev.lookup("b")).isEqualTo(StarlarkInt.of(0))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleLineFunction() {
        ev.exec(
            "def func(): return 'a'",  //
            "s = func()\n"
        )
        Truth.assertThat(ev.lookup("s")).isEqualTo("a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionReturnsDictionary() {
        ev.exec(
            "def func(): return {'a' : 1}",  //
            "d = func()",
            "a = d['a']\n"
        )
        Truth.assertThat(ev.lookup("a")).isEqualTo(StarlarkInt.of(1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionReturnsList() {
        ev.exec(
            "def func(): return [1, 2, 3]",  //
            "d = func()",
            "a = d[1]\n"
        )
        Truth.assertThat(ev.lookup("a")).isEqualTo(StarlarkInt.of(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionNameAliasing() {
        ev.exec(
            "def func(a):",  //
            "  return a + 1",
            "alias = func",
            "r = alias(1)"
        )
        Truth.assertThat(ev.lookup("r")).isEqualTo(StarlarkInt.of(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCallingFunctionsWithMixedModeArgs() {
        ev.exec(
            "def func(a, b, c):",  //
            "  return a + b + c",
            "v = func(1, c = 2, b = 3)"
        )
        Truth.assertThat(ev.lookup("v")).isEqualTo(StarlarkInt.of(6))
    }

    private fun functionWithOptionalArgs(): String {
        return ("def func(a, b = None, c = None):\n"
                + "  r = a + 'a'\n"
                + "  if b:\n"
                + "    r += 'b'\n"
                + "  if c:\n"
                + "    r += 'c'\n"
                + "  return r\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWhichOptionalArgsAreDefinedForFunctions() {
        ev.exec(
            functionWithOptionalArgs(),
            "v1 = func('1', 1, 1)",
            "v2 = func(b = 2, a = '2', c = 2)",
            "v3 = func('3')",
            "v4 = func('4', c = 1)\n"
        )
        Truth.assertThat(ev.lookup("v1")).isEqualTo("1abc")
        Truth.assertThat(ev.lookup("v2")).isEqualTo("2abc")
        Truth.assertThat(ev.lookup("v3")).isEqualTo("3a")
        Truth.assertThat(ev.lookup("v4")).isEqualTo("4ac")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultArguments() {
        ev.exec(
            "def func(a, b = 'b', c = 'c'):",
            "  return a + b + c",
            "v1 = func('a', 'x', 'y')",
            "v2 = func(b = 'x', a = 'a', c = 'y')",
            "v3 = func('a')",
            "v4 = func('a', c = 'y')\n"
        )
        Truth.assertThat(ev.lookup("v1")).isEqualTo("axy")
        Truth.assertThat(ev.lookup("v2")).isEqualTo("axy")
        Truth.assertThat(ev.lookup("v3")).isEqualTo("abc")
        Truth.assertThat(ev.lookup("v4")).isEqualTo("aby")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultArgumentsInsufficientArgNum() {
        ev.checkEvalError(
            "func() missing 1 required positional argument: a",
            "def func(a, b = 'b', c = 'c'):",
            "  return a + b + c",
            "func()"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgsIsNotIterable() {
        ev.checkEvalError(
            "argument after * must be an iterable, not int",
            "def func1(a, b): return a + b",
            "func1('a', *42)"
        )

        ev.checkEvalError(
            "argument after * must be an iterable, not string",
            "def func2(a, b): return a + b",
            "func2('a', *'str')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeywordOnly() {
        ev.checkEvalError(
            "func() missing 1 required keyword-only argument: b",  //
            "def func(a, *, b): pass",
            "func(5)"
        )

        ev.checkEvalError(
            "func() accepts no more than 1 positional argument but got 2",
            "def func(a, *, b): pass",
            "func(5, 6)"
        )

        ev.exec("def func(a, *, b, c = 'c'): return a + b + c")
        Truth.assertThat(ev.eval("func('a', b = 'b')")).isEqualTo("abc")
        Truth.assertThat(ev.eval("func('a', b = 'b', c = 'd')")).isEqualTo("abd")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarArgsAndKeywordOnly() {
        ev.checkEvalError(
            "func() missing 1 required keyword-only argument: b",
            "def func(a, *args, b): pass",
            "func(5)"
        )

        ev.checkEvalError(
            "func() missing 1 required keyword-only argument: b",
            "def func(a, *args, b): pass",
            "func(5, 6)"
        )

        ev.exec("def func(a, *args, b, c = 'c'): return a + str(args) + b + c")
        Truth.assertThat(ev.eval("func('a', b = 'b')")).isEqualTo("a()bc")
        Truth.assertThat(ev.eval("func('a', b = 'b', c = 'd')")).isEqualTo("a()bd")
        Truth.assertThat(ev.eval("func('a', 1, 2, b = 'b')")).isEqualTo("a(1, 2)bc")
        Truth.assertThat(ev.eval("func('a', 1, 2, b = 'b', c = 'd')")).isEqualTo("a(1, 2)bd")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotPassResidualsByName() {
        ev.checkEvalError(
            "f() got unexpected keyword argument: args", "def f(*args): pass", "f(args=[])"
        )

        ev.exec("def f(**kwargs): return kwargs")
        assertThat(Starlark.repr(ev.eval("f(kwargs=1)"), StarlarkSemantics.DEFAULT))
            .isEqualTo("{\"kwargs\": 1}")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeywordOnlyAfterStarArg() {
        ev.checkEvalError(
            "func() missing 1 required keyword-only argument: c",
            "def func(a, *b, c): pass",
            "func(5)"
        )

        ev.checkEvalError(
            "func() missing 1 required keyword-only argument: c",
            "def func(a, *b, c): pass",
            "func(5, 6, 7)"
        )

        ev.exec("def func(a, *b, c): return a + str(b) + c")
        Truth.assertThat(ev.eval("func('a', c = 'c')")).isEqualTo("a()c")
        Truth.assertThat(ev.eval("func('a', 1, c = 'c')")).isEqualTo("a(1,)c")
        Truth.assertThat(ev.eval("func('a', 1, 2, c = 'c')")).isEqualTo("a(1, 2)c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKwargsBadKey() {
        ev.checkEvalError(
            "keywords must be strings, not int",  //
            "def func(a, b): return a + b",
            "func('a', **{3: 1})"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKwargsIsNotDict() {
        ev.checkEvalError(
            "argument after ** must be a dict, not int",
            "def func(a, b): return a + b",
            "func('a', **42)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKwargsCollision() {
        ev.checkEvalError(
            "func() got multiple values for parameter 'b'",
            "def func(a, b): return a + b",
            "func('a', 'b', **{'b': 'foo'})"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKwargsCollisionWithNamed() {
        ev.checkEvalError(
            "func() got multiple values for parameter 'b'",
            "def func(a, b): return a + b",
            "func('a', b = 'b', **{'b': 'foo'})"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultArguments2() {
        ev.exec(
            "a = 2",
            "def foo(x=a): return x",
            "def bar():",
            "  a = 3",
            "  return foo()",
            "v = bar()\n"
        )
        Truth.assertThat(ev.lookup("v")).isEqualTo(StarlarkInt.of(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMixingPositionalOptional() {
        ev.exec(
            "def f(name, value = '', optional = ''):",  //
            "  return value",
            "v = f('name', 'value')"
        )
        Truth.assertThat(ev.lookup("v")).isEqualTo("value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarArg() {
        ev.exec(
            "def f(name, value = '1', optional = '2'): return name + value + optional",
            "v1 = f(*['name', 'value'])",
            "v2 = f('0', *['name', 'value'])",
            "v3 = f('0', optional = '3', *['b'])",
            "v4 = f(name='a', *[])\n"
        )
        Truth.assertThat(ev.lookup("v1")).isEqualTo("namevalue2")
        Truth.assertThat(ev.lookup("v2")).isEqualTo("0namevalue")
        Truth.assertThat(ev.lookup("v3")).isEqualTo("0b3")
        Truth.assertThat(ev.lookup("v4")).isEqualTo("a12")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarParam() {
        ev.exec(
            "def f(name, value = '1', optional = '2', *rest):",
            "  r = name + value + optional + '|'",
            "  for x in rest: r += x",
            "  return r",
            "v1 = f('a', 'b', 'c', 'd', 'e')",
            "v2 = f('a', optional='b', value='c')",
            "v3 = f('a')"
        )
        Truth.assertThat(ev.lookup("v1")).isEqualTo("abc|de")
        Truth.assertThat(ev.lookup("v2")).isEqualTo("acb|")
        Truth.assertThat(ev.lookup("v3")).isEqualTo("a12|")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKwParam() {
        ev.exec(
            ("def foo(a, b, c=3, d=4, g=7, h=8, *args, **kwargs):\n"
                    + "  return (a, b, c, d, g, h, args, kwargs)\n"
                    + "v1 = foo(1, 2)\n"
                    + "v2 = foo(1, h=9, i=0, *['x', 'y', 'z', 't'])\n"
                    + "v3 = foo(1, i=0, *[2, 3, 4, 5, 6, 7, 8])\n"
                    + "def bar(**kwargs):\n"
                    + "  return kwargs\n"
                    + "b1 = bar(name='foo', type='jpg', version=42).items()\n"
                    + "b2 = bar()\n")
        )

        assertThat(Starlark.repr(ev.lookup("v1"), StarlarkSemantics.DEFAULT))
            .isEqualTo("(1, 2, 3, 4, 7, 8, (), {})")
        assertThat(Starlark.repr(ev.lookup("v2"), StarlarkSemantics.DEFAULT))
            .isEqualTo("(1, \"x\", \"y\", \"z\", \"t\", 9, (), {\"i\": 0})")
        assertThat(Starlark.repr(ev.lookup("v3"), StarlarkSemantics.DEFAULT))
            .isEqualTo("(1, 2, 3, 4, 5, 6, (7, 8), {\"i\": 0})")
        assertThat(Starlark.repr(ev.lookup("b1"), StarlarkSemantics.DEFAULT))
            .isEqualTo("[(\"name\", \"foo\"), (\"type\", \"jpg\"), (\"version\", 42)]")
        assertThat(Starlark.repr(ev.lookup("b2"), StarlarkSemantics.DEFAULT)).isEqualTo("{}")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTrailingCommas() {
        // Test that trailing commas are allowed in function definitions and calls
        // even after last *args or **kwargs expressions, like python3
        ev.exec(
            ("def f(*args, **kwargs): pass\n"
                    + "v1 = f(1,)\n"
                    + "v2 = f(*(1,2),)\n"
                    + "v3 = f(a=1,)\n"
                    + "v4 = f(**{\"a\": 1},)\n")
        )

        assertThat(Starlark.repr(ev.lookup("v1"), StarlarkSemantics.DEFAULT)).isEqualTo("None")
        assertThat(Starlark.repr(ev.lookup("v2"), StarlarkSemantics.DEFAULT)).isEqualTo("None")
        assertThat(Starlark.repr(ev.lookup("v3"), StarlarkSemantics.DEFAULT)).isEqualTo("None")
        assertThat(Starlark.repr(ev.lookup("v4"), StarlarkSemantics.DEFAULT)).isEqualTo("None")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCalls() {
        ev.exec("def f(a, b = None): return a, b")

        assertThat(Starlark.repr(ev.eval("f(1)"), StarlarkSemantics.DEFAULT)).isEqualTo("(1, None)")
        assertThat(Starlark.repr(ev.eval("f(1, 2)"), StarlarkSemantics.DEFAULT)).isEqualTo("(1, 2)")
        assertThat(Starlark.repr(ev.eval("f(a=1)"), StarlarkSemantics.DEFAULT)).isEqualTo("(1, None)")
        assertThat(Starlark.repr(ev.eval("f(a=1, b=2)"), StarlarkSemantics.DEFAULT))
            .isEqualTo("(1, 2)")
        assertThat(Starlark.repr(ev.eval("f(b=2, a=1)"), StarlarkSemantics.DEFAULT))
            .isEqualTo("(1, 2)")

        ev.checkEvalError(
            "f() missing 1 required positional argument: a",  //
            "f()"
        )
        ev.checkEvalError(
            "f() accepts no more than 2 positional arguments but got 3",  //
            "f(1, 2, 3)"
        )
        ev.checkEvalError(
            "f() got unexpected keyword arguments: c, d",  //
            "f(1, 2, c=3, d=4)"
        )
        ev.checkEvalError(
            "f() missing 1 required positional argument: a",  //
            "f(b=2)"
        )
        ev.checkEvalError(
            "f() missing 1 required positional argument: a",  //
            "f(b=2)"
        )
        ev.checkEvalError(
            "f() got multiple values for parameter 'a'",  //
            "f(2, a=1)"
        )
        ev.checkEvalError(
            "f() got unexpected keyword argument: c",  //
            "f(b=2, a=1, c=3)"
        )

        ev.exec("def g(*, one, two, three): pass")
        ev.checkEvalError(
            "g() got unexpected keyword argument: tree (did you mean 'three'?)",  //
            "g(tree=3)"
        )
        ev.checkEvalError(
            "g() does not accept positional arguments, but got 3",  //
            "g(1, 2 ,3)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasing_keepsOriginalName() {
        ev.exec(
            """
        _y = 10

        def _f(x):
          return x * _y

        g = _f
        
        """.trimIndent()
        )
        val f: StarlarkFunction = ev.lookup("_f") as StarlarkFunction
        Truth.assertThat(ev.lookup("g")).isSameInstanceAs(f) // "g" is an alias for "_f"

        val id: SymbolGenerator.Symbol<*> = f.getToken()
        assertThat(id.isGlobal()).isTrue()

        val globalId: GlobalSymbol<*> = id as GlobalSymbol<*>
        assertThat(globalId.getName()).isEqualTo("_f")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exportedLambdas_haveGlobalIds() {
        ev.exec(
            """
        x = lambda v: "--" + v
        y = x
        
        """.trimIndent()
        )
        val x: StarlarkFunction = ev.lookup("x") as StarlarkFunction
        Truth.assertThat(ev.lookup("y")).isSameInstanceAs(x) // "y" is an alias for "x"

        val id: SymbolGenerator.Symbol<*> = x.getToken()
        assertThat(id.isGlobal()).isTrue()
        val globalId: GlobalSymbol<*> = id as GlobalSymbol<*>
        assertThat(globalId.getName()).isEqualTo("x")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun localLambdas_haveLocalIds() {
        ev.exec(
            """
        x = (lambda v: v + 1,)
        
        """.trimIndent()
        )
        val x: Tuple = ev.lookup("x") as Tuple
        val lambda: StarlarkFunction = x.get(0) as StarlarkFunction

        val id: SymbolGenerator.Symbol<*> = lambda.getToken()
        assertThat(id.isGlobal()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun innerLambdas_canBePublished() {
        ev.exec(
            """
        x = (lambda v: v + 1,)
        y = x[0]
        
        """.trimIndent()
        )
        val x: Tuple = ev.lookup("x") as Tuple
        val y: StarlarkFunction = ev.lookup("y") as StarlarkFunction
        assertThat(x.get(0)).isSameInstanceAs(y)

        val id: SymbolGenerator.Symbol<*> = y.getToken()
        assertThat(id.isGlobal()).isTrue()
        val globalId: GlobalSymbol<*> = id as GlobalSymbol<*>
        assertThat(globalId.getName()).isEqualTo("y")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun globalFunctionReassignment_fails() {
        val thrown: net.starlark.java.syntax.SyntaxError.Exception? =
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception?>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable {
                    ev.exec(
                        """
                    x = lambda v: v + 1
                    x = lambda v: v + 2
                    
                    """.trimIndent()
                    )
                })
        Truth.assertThat(thrown).hasMessageThat().contains("'x' redeclared at top level")
    }

    // Regression test for b/385394075
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun positionalOnlyCall_setsKeywordArgsVarargsAndKwargs() {
        ev.exec(
            """
        def f(a, b, *args, k = 42, **kwargs):
            kwargs["mutable"] = True  # verify that **kwargs is a mutable dict
            return "k=%s args=%s kwargs=%s" % (repr(k), repr(args), repr(kwargs))
        
        """.trimIndent()
        )
        val f: StarlarkFunction? = ev.lookup("f") as StarlarkFunction?
        Mutability.create("test").use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            Truth.assertThat(Starlark.positionalOnlyCall(thread, f, "a", "b", "c", "d") as String?)
                .isEqualTo("k=42 args=(\"c\", \"d\") kwargs={\"mutable\": True}")
        }
    }

    companion object {
        private fun getDefaults(fn: StarlarkFunction): MutableList<Any?> {
            val defaults: MutableList<Any?> = java.util.ArrayList<Any?>()
            for (i in 0..<fn.getParameterNames().size()) {
                defaults.add(fn.getDefaultValue(i))
            }
            return defaults
        }
    }
}
