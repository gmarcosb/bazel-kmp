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
package net.starlark.java.eval

import net.starlark.java.annot.Param

/** Tests of Starlark evaluation.  */ // There is no clear distinction between this and EvaluationTest.
// TODO(adonovan): reorganize.
@RunWith(JUnit4::class)
class StarlarkEvaluationTest {
    private val ev: EvaluationTestCase = EvaluationTestCase()

    internal class Bad

    @StarlarkMethod(name = "foobar", documented = false)
    fun foobar(): String {
        return "foobar"
    }

    @com.google.errorprone.annotations.DoNotCall("Always throws java.lang.InterruptedException")
    @StarlarkMethod(name = "interrupted_function", documented = false)
    @Throws(java.lang.InterruptedException::class)
    fun interruptedFunction(): NoneType? {
        throw java.lang.InterruptedException()
    }

    @StarlarkMethod(name = "stackoverflow", documented = false)
    fun stackoverflow(): Int {
        return if (true) stackoverflow() else 0 // (defeat static recursion checker)
    }

    @StarlarkMethod(name = "throwoom", documented = false)
    fun throwoom() {
        throw java.lang.OutOfMemoryError("Java heap space")
    }

    @StarlarkMethod(name = "thrownpe", documented = false)
    fun thrownpe() {
        throw java.lang.NullPointerException("oops")
    }

    // A trivial struct-like class with Starlark fields defined by a map.
    private open class SimpleStruct(fields: com.google.common.collect.ImmutableMap<String?, Any?>) : StarlarkValue,
        Structure {
        val fields: com.google.common.collect.ImmutableMap<String?, Any?>

        init {
            this.fields = fields
        }

        val fieldNames: com.google.common.collect.ImmutableCollection<String?>
            get() = fields.keys

        public override fun getValue(name: String?): Any? {
            return fields.get(name)
        }

        public override fun getErrorMessageForUnknownField(name: String?): String? {
            return null
        }

        public override fun repr(p: Printer, semantics: StarlarkSemantics?) {
            // This repr function prints only the fields.
            // Any methods are still accessible through dir/getattr/hasattr.
            p.append("simplestruct(")
            var sep = ""
            for (e in fields.entries) {
                p.append(sep).append(e.key).append(" = ").repr(e.value, semantics)
                sep = ", "
            }
            p.append(")")
        }
    }

    @StarlarkBuiltin(name = "Mock", doc = "")
    internal open inner class Mock : StarlarkValue {
        @StarlarkMethod(
            name = "MockFn",
            selfCall = true,
            documented = false,
            parameters = [Param(name = "pos", positional = true)]
        )
        fun selfCall(myName: String): String {
            return "I'm a mock named " + myName
        }

        @StarlarkMethod(name = "value_of", parameters = [Param(name = "str")], documented = false)
        fun valueOf(str: String): Int? {
            return str.toInt()
        }

        @StarlarkMethod(name = "is_empty", parameters = [Param(name = "str")], documented = false)
        open fun isEmpty(str: String): Boolean {
            return str.isEmpty()
        }

        fun value() {}

        @StarlarkMethod(name = "return_bad", documented = false)
        fun returnBad(): Bad {
            return Bad() // not a legal Starlark value
        }

        @StarlarkMethod(name = "struct_field", documented = false, structField = true)
        fun structField(): String {
            return "a"
        }

        @StarlarkMethod(
            name = "struct_field_with_extra",
            documented = false,
            structField = true,
            useStarlarkSemantics = true
        )
        fun structFieldWithExtra(sem: StarlarkSemantics?): String {
            return ("struct_field_with_extra("
                    + (sem != null)
                    + ")")
        }

        @StarlarkMethod(name = "struct_field_callable", documented = false, structField = true)
        fun structFieldCallable(): Any {
            return getattr(this@StarlarkEvaluationTest, "foobar")
        }

        @StarlarkMethod(name = "interrupted_struct_field", documented = false, structField = true)
        @Throws(java.lang.InterruptedException::class)
        fun structFieldInterruptedCallable(): Any? {
            throw java.lang.InterruptedException()
        }

        @StarlarkMethod(name = "function", documented = false, structField = false)
        fun function(): String {
            return "a"
        }

        @Suppress("unused")
        @StarlarkMethod(
            name = "nullfunc_failing",
            parameters = [Param(name = "p1"), Param(name = "p2")],
            documented = false,
            allowReturnNones = false
        )
        fun nullfuncFailing(p1: String?, p2: StarlarkInt?): StarlarkValue? {
            return null
        }

        @StarlarkMethod(name = "nullfunc_working", documented = false, allowReturnNones = true)
        fun nullfuncWorking(): StarlarkValue? {
            return null
        }

        @StarlarkMethod(name = "voidfunc", documented = false)
        fun voidfunc() {
        }

        @StarlarkMethod(name = "string_list", documented = false)
        fun stringList(): com.google.common.collect.ImmutableList<String?> {
            return com.google.common.collect.ImmutableList.of<String?>("a", "b")
        }

        @StarlarkMethod(name = "string", documented = false)
        fun string(): String {
            return "a"
        }

        @StarlarkMethod(name = "string_list_dict", documented = false)
        fun stringListDict(): MutableMap<String?, MutableList<String?>?> {
            return com.google.common.collect.ImmutableMap.of<K?, V?>("a", StarlarkList.immutableOf("b", "c"))
        }

        @StarlarkMethod(
            name = "with_params",
            documented = false,
            parameters = [Param(name = "pos1"), Param(name = "pos2", defaultValue = "False"), Param(
                name = "posOrNamed",
                defaultValue = "False",
                positional = true,
                named = true
            ), Param(name = "named", positional = false, named = true), Param(
                name = "optionalNamed",
                defaultValue = "False",
                positional = false,
                named = true
            ), Param(
                name = "acceptsAny",
                defaultValue = "'a'",
                positional = false,
                named = true
            ), Param(
                name = "noneable",
                allowedTypes = [ParamType(type = StarlarkInt::class), ParamType(type = NoneType::class)],
                defaultValue = "None",
                positional = false,
                named = true
            ), Param(
                name = "multi",
                allowedTypes = [ParamType(type = String::class), ParamType(type = StarlarkInt::class), ParamType(
                    type = Sequence::class,
                    generic1 = StarlarkInt::class
                ), ParamType(type = NoneType::class)],
                defaultValue = "None",
                positional = false,
                named = true
            )]
        )
        fun withParams(
            pos1: StarlarkInt?,
            pos2: Boolean,
            posOrNamed: Boolean,
            named: Boolean,
            optionalNamed: Boolean,
            acceptsAny: Any?,
            noneable: Any?,
            multi: Any?
        ): String {
            return ("with_params("
                    + pos1
                    + ", "
                    + pos2
                    + ", "
                    + posOrNamed
                    + ", "
                    + named
                    + ", "
                    + optionalNamed
                    + ", "
                    + acceptsAny
                    + (if (noneable !== Starlark.NONE) ", " + noneable else "")
                    + (if (multi !== Starlark.NONE) ", " + multi else "")
                    + ")")
        }

        @StarlarkMethod(name = "with_extra", documented = false, useStarlarkThread = true)
        fun withExtraInterpreterParams(thread: StarlarkThread): String {
            return "with_extra(" + thread.getCallerLocation().line() + ")"
        }

        @StarlarkMethod(
            name = "with_params_and_extra",
            documented = false,
            parameters = [Param(name = "pos1"), Param(name = "pos2", defaultValue = "False"), Param(
                name = "posOrNamed",
                defaultValue = "False",
                positional = true,
                named = true
            ), Param(name = "named", positional = false, named = true), Param(
                name = "optionalNamed",
                defaultValue = "False",
                positional = false,
                named = true
            ), Param(
                name = "acceptsAny",
                defaultValue = "'a'",
                positional = false,
                named = true
            ), Param(
                name = "noneable",
                allowedTypes = [ParamType(type = StarlarkInt::class), ParamType(type = NoneType::class)],
                defaultValue = "None",
                positional = false,
                named = true
            ), Param(
                name = "multi",
                allowedTypes = [ParamType(type = String::class), ParamType(type = StarlarkInt::class), ParamType(
                    type = Sequence::class,
                    generic1 = StarlarkInt::class
                ), ParamType(type = NoneType::class)],
                defaultValue = "None",
                positional = false,
                named = true
            )],
            useStarlarkThread = true
        )
        fun withParamsAndExtraInterpreterParams(
            pos1: StarlarkInt?,
            pos2: Boolean,
            posOrNamed: Boolean,
            named: Boolean,
            optionalNamed: Boolean,
            acceptsAny: Any?,
            noneable: Any?,
            multi: Any?,
            thread: StarlarkThread
        ): String {
            return ("with_params_and_extra("
                    + pos1
                    + ", "
                    + pos2
                    + ", "
                    + posOrNamed
                    + ", "
                    + named
                    + ", "
                    + optionalNamed
                    + ", "
                    + acceptsAny
                    + (if (noneable !== Starlark.NONE) ", " + noneable else "")
                    + (if (multi !== Starlark.NONE) ", " + multi else "")
                    + ", "
                    + thread.getCallerLocation().line()
                    + ")")
        }

        @StarlarkMethod(
            name = "proxy_methods_object",
            doc = "Returns a struct containing all callable method objects of this mock",
            useStarlarkThread = true
        )
        @Throws(EvalException::class, java.lang.InterruptedException::class)
        fun proxyMethodsObject(thread: StarlarkThread): Structure? {
            val builder: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.builder<String?, Any?>()
            for (name in Starlark.dir(thread.mutability(), thread.getSemantics(), this)) {
                if (name == "interrupted_struct_field") {
                    continue  // skip, because getattr would be interrupted
                }
                val v: Any? = Starlark.getattr(thread.mutability(), thread.getSemantics(), this, name, null)
                builder.put(name, v)
            }
            return SimpleStruct(builder.buildOrThrow())
        }

        @StarlarkMethod(
            name = "with_args_and_thread",
            documented = false,
            parameters = [Param(name = "pos1"), Param(name = "pos2", defaultValue = "False"), Param(
                name = "named",
                positional = false,
                named = true
            )],
            extraPositionals = Param(name = "args"),
            useStarlarkThread = true
        )
        fun withArgsAndThread(
            pos1: StarlarkInt?, pos2: Boolean, named: Boolean, args: Sequence<*>, thread: StarlarkThread?
        ): String {
            val argsString = debugPrintArgs(args, thread)
            return ("with_args_and_thread("
                    + pos1
                    + ", "
                    + pos2
                    + ", "
                    + named
                    + ", "
                    + argsString
                    + ")")
        }

        @StarlarkMethod(
            name = "with_kwargs",
            documented = false,
            parameters = [Param(name = "pos", defaultValue = "False"), Param(
                name = "named",
                positional = false,
                named = true
            )],
            extraKeywords = Param(name = "kwargs")
        )
        fun withKwargs(pos: Boolean, named: Boolean, kwargs: Dict<String?, Any?>): String {
            val kwargsString =
                ("kwargs("
                        + kwargs
                    .entrySet()
                    .stream()
                    .map({ entry -> entry.getKey() + "=" + entry.getValue() })
                    .collect(Collectors.joining(", "))
                        + ")")
            return "with_kwargs(" + pos + ", " + named + ", " + kwargsString + ")"
        }

        @StarlarkMethod(
            name = "with_args_and_kwargs",
            documented = false,
            parameters = [Param(name = "foo", named = true, positional = true)],
            extraPositionals = Param(name = "args"),
            extraKeywords = Param(name = "kwargs"),
            useStarlarkThread = true
        )
        fun withArgsAndKwargs(
            foo: String, args: Tuple, kwargs: Dict<String?, Any?>, thread: StarlarkThread?
        ): String {
            val argsString = debugPrintArgs(args, thread)
            val kwargsString =
                ("kwargs("
                        + kwargs
                    .entrySet()
                    .stream()
                    .map({ entry -> entry.getKey() + "=" + entry.getValue() })
                    .collect(Collectors.joining(", "))
                        + ")")
            return "with_args_and_kwargs(" + foo + ", " + argsString + ", " + kwargsString + ")"
        }

        override fun toString(): String {
            return "<mock>"
        }
    }

    @StarlarkBuiltin(name = "MockInterface", doc = "")
    internal interface MockInterface : StarlarkValue {
        @StarlarkMethod(name = "is_empty_interface", parameters = [Param(name = "str")], documented = false)
        fun isEmptyInterface(str: String?): Boolean?
    }

    @StarlarkBuiltin(name = "MockSubClass", doc = "")
    internal inner class MockSubClass : Mock(), MockInterface {
        override fun isEmpty(str: String): Boolean {
            return str.isEmpty()
        }

        override fun isEmptyInterface(str: String): Boolean {
            return str.isEmpty()
        }
    }

    @StarlarkBuiltin(name = "ParamterizedMock", doc = "")
    internal interface ParameterizedApi<ObjectT> : StarlarkValue {
        @StarlarkMethod(
            name = "method",
            documented = false,
            parameters = [Param(name = "foo", named = true, positional = true)]
        )
        fun method(o: ObjectT?): ObjectT?
    }

    internal class ParameterizedMock : ParameterizedApi<String?> {
        override fun method(o: String?): String? {
            return o
        }
    }

    // Verifies that a method implementation overriding a parameterized annotated interface method
    // is still treated as Starlark-callable. Concretely, method() below should be treated as
    // callable even though its method signature isn't an *exact* match of the annotated method
    // declaration, due to the interface's method declaration being generic.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParameterizedMock() {
        ev.Scenario()
            .update("mock", ParameterizedMock())
            .setUp("result = mock.method('bar')")
            .testLookup("result", "bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleIf() {
        ev.Scenario()
            .setUp("def foo():", "  a = 0", "  x = 0", "  if x: a = 5", "  return a", "a = foo()")
            .testLookup("a", StarlarkInt.of(0))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIfPass() {
        ev.Scenario()
            .setUp("def foo():", "  a = 1", "  x = True", "  if x: pass", "  return a", "a = foo()")
            .testLookup("a", StarlarkInt.of(1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNestedIf() {
        executeNestedIf(0, 0, 0)
        executeNestedIf(1, 0, 3)
        executeNestedIf(1, 1, 5)
    }

    @Throws(java.lang.Exception::class)
    private fun executeNestedIf(x: Int, y: Int, expected: Int) {
        val `fun`: String? = String.format("foo%s%s", x, y)
        ev.Scenario()
            .setUp(
                "def " + `fun` + "():",
                "  x = " + x,
                "  y = " + y,
                "  a = 0",
                "  b = 0",
                "  if x:",
                "    if y:",
                "      a = 2",
                "    b = 3",
                "  return a + b",
                "x = " + `fun` + "()"
            )
            .testLookup("x", StarlarkInt.of(expected))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIfElse() {
        executeIfElse("foo", "something", 2)
        executeIfElse("bar", "", 3)
    }

    @Throws(java.lang.Exception::class)
    private fun executeIfElse(`fun`: String?, y: String?, expected: Int) {
        ev.Scenario()
            .setUp(
                "def " + `fun` + "():",
                "  y = '" + y + "'",
                "  x = 5",
                "  if x:",
                "    if y: a = 2",
                "    else: a = 3",
                "  return a",
                "z = " + `fun` + "()"
            )
            .testLookup("z", StarlarkInt.of(expected))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIfElifElse_ifExecutes() {
        execIfElifElse(1, 0, 1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIfElifElse_elifExecutes() {
        execIfElifElse(0, 1, 2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIfElifElse_elseExecutes() {
        execIfElifElse(0, 0, 3)
    }

    @Throws(java.lang.Exception::class)
    private fun execIfElifElse(x: Int, y: Int, v: Int) {
        ev.Scenario()
            .setUp(
                "def foo():",
                "  x = " + x + "",
                "  y = " + y + "",
                "  if x:",
                "    return 1",
                "  elif y:",
                "    return 2",
                "  else:",
                "    return 3",
                "v = foo()"
            )
            .testLookup("v", StarlarkInt.of(v))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForOnList() {
        ev.Scenario()
            .setUp(
                "def foo():",
                "  s = ''",
                "  for i in ['hello', ' ', 'world']:",
                "    s = s + i",
                "  return s",
                "s = foo()"
            )
            .testLookup("s", "hello world")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForAssignmentList() {
        ev.Scenario()
            .setUp(
                "def foo():",
                "  d = ['a', 'b', 'c']",
                "  s = ''",
                "  for i in d:",
                "    s = s + i",
                "    d = ['d', 'e', 'f']",  // check that we use the old list
                "  return s",
                "s = foo()"
            )
            .testLookup("s", "abc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForAssignmentDict() {
        ev.Scenario()
            .setUp(
                "def func():",
                "  d = {'a' : 1, 'b' : 2, 'c' : 3}",
                "  s = ''",
                "  for i in d:",
                "    s = s + i",
                "    d = {'d' : 1, 'e' : 2, 'f' : 3}",
                "  return s",
                "s = func()"
            )
            .testLookup("s", "abc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForUpdateList() {
        ev.Scenario()
            .setUp(
                "def foo():",
                "  xs = [1, 2, 3]",
                "  for x in xs:",
                "    if x == 1:",
                "      xs.append(10)"
            )
            .testIfErrorContains(
                "list value is temporarily immutable due to active for-loop iteration", "foo()"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForUpdateDict() {
        ev.Scenario()
            .setUp("def foo():", "  d = {'a': 1, 'b': 2, 'c': 3}", "  for k in d:", "    d[k] *= 2")
            .testIfErrorContains(
                "dict value is temporarily immutable due to active for-loop iteration", "foo()"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForUnlockedAfterBreak() {
        ev.Scenario()
            .setUp(
                "def foo():",
                "  xs = [1, 2]",
                "  for x in xs:",
                "    break",
                "  xs.append(3)",
                "  return xs"
            )
            .testEval("foo()", "[1, 2, 3]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForNestedOnSameListStillLocked() {
        ev.Scenario()
            .setUp(
                "def foo():",
                "  xs = [1, 2]",
                "  ys = []",
                "  for x1 in xs:",
                "    for x2 in xs:",
                "      ys.append(x1 * x2)",
                "    xs.append(4)",
                "  return ys"
            )
            .testIfErrorContains(
                "list value is temporarily immutable due to active for-loop iteration", "foo()"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForNestedOnSameListUnlockedAtEnd() {
        ev.Scenario()
            .setUp(
                "def foo():",
                "  xs = [1, 2]",
                "  ys = []",
                "  for x1 in xs:",
                "    for x2 in xs:",
                "      ys.append(x1 * x2)",
                "  xs.append(4)",
                "  return ys"
            )
            .testEval("foo()", "[1, 2, 2, 4]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForNestedWithListCompGood() {
        ev.Scenario()
            .setUp(
                "def foo():",
                "  xs = [1, 2]",
                "  ys = []",
                "  for x in xs:",
                "    zs = [None for x in xs for y in (ys.append(x) or ys)]",
                "  return ys"
            )
            .testEval("foo()", "[1, 2, 1, 2]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForNestedWithListCompBad() {
        ev.Scenario()
            .setUp(
                "def foo():",
                "  xs = [1, 2, 3]",
                "  ys = []",
                "  for x in xs:",
                "    zs = [None for x in xs for y in (xs.append(x) or ys)]",
                "  return ys"
            )
            .testIfErrorContains(
                "list value is temporarily immutable due to active for-loop iteration", "foo()"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForDeepUpdate() {
        // Check that indirectly reachable values can still be manipulated as normal.
        ev.Scenario()
            .setUp(
                "def foo():",
                "  xs = [['a'], ['b'], ['c']]",
                "  ys = []",
                "  for x in xs:",
                "    for y in x:",
                "      ys.append(y)",
                "    xs[2].append(x[0])",
                "  return ys",
                "ys = foo()"
            )
            .testLookup("ys", StarlarkList.of(null, "a", "b", "c", "a", "b"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForNotIterable() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .testIfErrorContains(
                "type 'int' is not iterable",
                "def func():",
                "  for i in mock.value_of('1'): a = i",
                "func()\n"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForStringNotIterable() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .testIfErrorContains(
                "type 'string' is not iterable", "def func():", "  for i in 'abc': a = i", "func()\n"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForOnDictionary() {
        ev.Scenario()
            .setUp(
                "def foo():",
                "  d = {1: 'a', 2: 'b', 3: 'c'}",
                "  s = ''",
                "  for i in d: s = s + d[i]",
                "  return s",
                "s = foo()"
            )
            .testLookup("s", "abc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadDictKey() {
        ev.Scenario().testIfErrorContains("unhashable type: 'list'", "{ [1, 2]: [3, 4] }")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForLoopReuseVariable() {
        ev.Scenario()
            .setUp(
                "def foo():",
                "  s = ''",
                "  for i in ['a', 'b']:",
                "    for i in ['c', 'd']: s = s + i",
                "  return s",
                "s = foo()"
            )
            .testLookup("s", "cdcd")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForLoopMultipleVariables() {
        ev.Scenario()
            .setUp(
                "def foo():",
                "  s = ''",
                "  for [i, j] in [[1, 2], [3, 4]]:",
                "    s = s + str(i) + str(j) + '.'",
                "  return s",
                "s = foo()"
            )
            .testLookup("s", "12.34.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForLoopBreak() {
        simpleFlowTest("break", 1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForLoopContinue() {
        simpleFlowTest("continue", 10)
    }

    @Throws(java.lang.Exception::class)
    private fun simpleFlowTest(statement: String?, expected: Int) {
        ev.exec(
            "def foo():",
            "  s = 0",
            "  hit = 0",
            "  for i in range(0, 10):",
            "    s = s + 1",
            "    " + statement + "",
            "    hit = 1",
            "  return [s, hit]",
            "x = foo()"
        )
        Truth.assertThat(ev.lookup("x") as Iterable<Any?>?)
            .containsExactly(StarlarkInt.of(expected), StarlarkInt.of(0))
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForLoopBreakFromDeeperBlock() {
        flowFromDeeperBlock("break", 1)
        flowFromNestedBlocks("break", 29)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForLoopContinueFromDeeperBlock() {
        flowFromDeeperBlock("continue", 5)
        flowFromNestedBlocks("continue", 39)
    }

    @Throws(java.lang.Exception::class)
    private fun flowFromDeeperBlock(statement: String?, expected: Int) {
        ev.exec(
            "def foo():",
            "   s = 0",
            "   for i in range(0, 10):",
            "       if i % 2 != 0:",
            "           " + statement + "",
            "       s = s + 1",
            "   return s",
            "x = foo()"
        )
        Truth.assertThat(ev.lookup("x")).isEqualTo(StarlarkInt.of(expected))
    }

    @Throws(java.lang.Exception::class)
    private fun flowFromNestedBlocks(statement: String?, expected: Int) {
        ev.exec(
            "def foo2():",
            "   s = 0",
            "   for i in range(1, 41):",
            "       if i % 2 == 0:",
            "           if i % 3 == 0:",
            "               if i % 5 == 0:",
            "                   " + statement + "",
            "       s = s + 1",
            "   return s",
            "y = foo2()"
        )
        Truth.assertThat(ev.lookup("y")).isEqualTo(StarlarkInt.of(expected))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNestedForLoopsMultipleBreaks() {
        nestedLoopsTest("break", 2, 6, 6)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNestedForLoopsMultipleContinues() {
        nestedLoopsTest("continue", 4, 20, 20)
    }

    @Throws(java.lang.Exception::class)
    private fun nestedLoopsTest(
        statement: String?, outerExpected: Int, firstExpected: Int, secondExpected: Int
    ) {
        ev.exec(
            "def foo():",
            "   outer = 0",
            "   first = 0",
            "   second = 0",
            "   for i in range(0, 5):",
            "       for j in range(0, 5):",
            "           if j == 2:",
            "               " + statement + "",
            "           first = first + 1",
            "       for k in range(0, 5):",
            "           if k == 2:",
            "               " + statement + "",
            "           second = second + 1",
            "       if i == 2:",
            "           " + statement + "",
            "       outer = outer + 1",
            "   return [outer, first, second]",
            "x = foo()"
        )
        Truth.assertThat(ev.lookup("x") as Iterable<Any?>?)
            .containsExactly(
                StarlarkInt.of(outerExpected),
                StarlarkInt.of(firstExpected),
                StarlarkInt.of(secondExpected)
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForLoopBreakError() {
        flowStatementInsideFunction("break")
        flowStatementAfterLoop("break")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForLoopContinueError() {
        flowStatementInsideFunction("continue")
        flowStatementAfterLoop("continue")
    }

    // TODO(adonovan): move this and all tests that use it to ResolverTest.
    @Throws(java.lang.Exception::class)
    private fun assertResolutionError(expectedError: String?, vararg lines: String?) {
        val error: net.starlark.java.syntax.SyntaxError.Exception? =
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception?>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable { ev.exec(*lines) })
        Truth.assertThat(error).hasMessageThat().contains(expectedError)
    }

    @Throws(java.lang.Exception::class)
    private fun flowStatementInsideFunction(statement: String?) {
        assertResolutionError(
            statement + " statement must be inside a for loop",  //
            "def foo():",
            "  " + statement,
            "x = foo()"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun flowStatementAfterLoop(statement: String?) {
        assertResolutionError(
            statement + " statement must be inside a for loop",  //
            "def foo2():",
            "   for i in range(0, 3):",
            "      pass",
            "   " + statement,
            "y = foo2()"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoneAssignment() {
        ev.Scenario()
            .setUp("def foo(x=None):", "  x = 1", "  x = None", "  return 2", "s = foo()")
            .testLookup("s", StarlarkInt.of(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReassignment() {
        ev.exec(
            "def foo(x=None):",  //
            "  x = 1",
            "  x = [1, 2]",
            "  x = 'str'",
            "  return x",
            "s = foo()"
        )
        Truth.assertThat(ev.lookup("s")).isEqualTo("str")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaCalls() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("b = mock.is_empty('a')")
            .testLookup("b", java.lang.Boolean.FALSE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaCallsOnSubClass() {
        ev.Scenario()
            .update("mock", MockSubClass())
            .setUp("b = mock.is_empty('a')")
            .testLookup("b", java.lang.Boolean.FALSE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaCallsOnInterface() {
        ev.Scenario()
            .update("mock", MockSubClass())
            .setUp("b = mock.is_empty_interface('a')")
            .testLookup("b", java.lang.Boolean.FALSE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaCallsNotStarlarkMethod() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .testIfExactError("'Mock' value has no field or method 'value'", "mock.value()")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoOperatorIndex() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .testIfExactError("type 'Mock' has no operator [](int)", "mock[2]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaCallsNoMethod() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .testIfExactError("'Mock' value has no field or method 'bad'", "mock.bad()")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaCallsNoMethodErrorMsg() {
        ev.Scenario()
            .testIfExactError("'int' value has no field or method 'bad'", "s = (3).bad('a', 'b', 'c')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaCallWithKwargs() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .testIfExactError(
                "'Mock' value has no field or method 'isEmpty' (did you mean 'is_empty'?)",
                "mock.isEmpty(str='abc')"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringListDictValues() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp(
                "def func(mock):",
                "  for i, v in mock.string_list_dict().items():",
                "    modified_list = v + ['extra_string']",
                "  return modified_list",
                "m = func(mock)"
            )
            .testLookup("m", StarlarkList.of(null, "b", "c", "extra_string"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProxyMethodsObject() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("m = mock.proxy_methods_object()", "b = m.with_params(1, True, named=True)")
            .testLookup("b", "with_params(1, true, false, true, false, a)")
    }

    /**
     * This test verifies an error is raised when a method parameter is set both positionally and
     * by name.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgSpecifiedBothByNameAndPosition() {
        // in with_params, 'posOrNamed' is positional parameter index 2. So by specifying both
        // posOrNamed by name and three positional parameters, there is a conflict.
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .testIfErrorContains(
                "with_params() got multiple values for argument 'posOrNamed'",
                "mock.with_params(1, True, True, posOrNamed=True, named=True)"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTooManyPositionalArgs() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .testIfErrorContains(
                "with_params() accepts no more than 3 positional arguments but got 4",
                "mock.with_params(1, True, True, 'toomany', named=True)"
            )

        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .testIfErrorContains(
                "with_params() accepts no more than 3 positional arguments but got 5",
                "mock.with_params(1, True, True, 'toomany', 'alsotoomany', named=True)"
            )

        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .testIfErrorContains(
                "is_empty() accepts no more than 1 positional argument but got 2",
                "mock.is_empty('a', 'b')"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaCallWithPositionalAndKwargs() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("b = mock.with_params(1, True, named=True)")
            .testLookup("b", "with_params(1, true, false, true, false, a)")
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("b = mock.with_params(1, True, named=True, multi=1)")
            .testLookup("b", "with_params(1, true, false, true, false, a, 1)")
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("b = mock.with_params(1, True, named=True, multi='abc')")
            .testLookup("b", "with_params(1, true, false, true, false, a, abc)")

        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("b = mock.with_params(1, True, named=True, multi=[1,2,3])")
            .testLookup("b", "with_params(1, true, false, true, false, a, [1, 2, 3])")

        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("")
            .testIfExactError(
                "with_params() missing 1 required named argument: named", "mock.with_params(1, True)"
            )
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("")
            .testIfExactError(
                "with_params() missing 1 required named argument: named",
                "mock.with_params(1, True, True)"
            )
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("b = mock.with_params(1, True, True, named=True)")
            .testLookup("b", "with_params(1, true, true, true, false, a)")
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("b = mock.with_params(1, True, named=True, posOrNamed=True)")
            .testLookup("b", "with_params(1, true, true, true, false, a)")
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("b = mock.with_params(1, True, named=True, posOrNamed=True, optionalNamed=True)")
            .testLookup("b", "with_params(1, true, true, true, true, a)")
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("")
            .testIfExactError(
                "with_params() got unexpected keyword argument 'posornamed' (did you mean"
                        + " 'posOrNamed'?)",
                "mock.with_params(1, True, named=True, posornamed=True)"
            )
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("")
            .testIfExactError(
                "with_params() got unexpected keyword argument 'n'",
                "mock.with_params(1, True, named=True, posOrNamed=True, n=2)"
            )
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("")
            .testExpression(
                "mock.with_params(1, True, True, named=True, optionalNamed=False, acceptsAny=None)",
                "with_params(1, true, true, true, false, None)"
            )
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("")
            .testExpression(
                "mock.with_params(1, True, True, named=True, optionalNamed=False, acceptsAny=123)",
                "with_params(1, true, true, true, false, 123)"
            )

        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("")
            .testIfExactError(
                "in call to with_params(), parameter 'multi' got value of type 'bool', "
                        + "want 'string, int, sequence, or NoneType'",
                "mock.with_params(1, True, named=True, multi=False)"
            )

        // We do not enforce list item parameter type constraints.
        // Test for this behavior.
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("b = mock.with_params(1, True, named=True, multi=['a', 'b'])")
            .testLookup("b", "with_params(1, true, false, true, false, a, [\"a\", \"b\"])")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoJavaCallsWithoutStarlark() {
        ev.Scenario()
            .testIfExactError("'int' value has no field or method 'to_string'", "s = (3).to_string()")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructAccess() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("v = mock.struct_field")
            .testLookup("v", "a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructAccessAsFuncallNonCallable() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .testIfExactError("'string' object is not callable", "v = mock.struct_field()")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelfCall() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("v = mock('bestmock')")
            .testLookup("v", "I'm a mock named bestmock")

        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("mockfunction = mock", "v = mockfunction('bestmock')")
            .testLookup("v", "I'm a mock named bestmock")

        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .testIfErrorContains(
                "in call to MockFn(), parameter 'pos' got value of type 'int', want 'string'",
                "v = mock(1)"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructAccessAsFuncall() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("v = mock.struct_field_callable()")
            .testLookup("v", "foobar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCallingInterruptedStructField() {
        ev.update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { ev.eval("mock.interrupted_struct_field()") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCallingInterruptedFunction() {
        ev.update("interrupted_function", getattr(this, "interrupted_function"))
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { ev.eval("interrupted_function()") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaFunctionWithExtraInterpreterParams() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("v = mock.with_extra()")
            .testLookup("v", "with_extra(1)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructFieldWithExtraInterpreterParams() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("v = mock.struct_field_with_extra")
            .testLookup("v", "struct_field_with_extra(true)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaFunctionWithParamsAndExtraInterpreterParams() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("b = mock.with_params_and_extra(1, True, named=True)")
            .testLookup("b", "with_params_and_extra(1, true, false, true, false, a, 1)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaFunctionWithExtraArgsAndThread() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("b = mock.with_args_and_thread(1, True, 'extraArg1', 'extraArg2', named=True)")
            .testLookup("b", "with_args_and_thread(1, true, true, args(extraArg1, extraArg2))")

        // Use an args list.
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp(
                "myargs = ['extraArg2']",
                "b = mock.with_args_and_thread(1, True, 'extraArg1', named=True, *myargs)"
            )
            .testLookup("b", "with_args_and_thread(1, true, true, args(extraArg1, extraArg2))")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaFunctionWithExtraKwargs() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("b = mock.with_kwargs(True, extraKey1=True, named=True, extraKey2='x')")
            .testLookup("b", "with_kwargs(true, true, kwargs(extraKey1=true, extraKey2=x))")

        // Use a kwargs dict.
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp(
                "mykwargs = {'extraKey2':'x', 'named':True}",
                "b = mock.with_kwargs(True, extraKey1=True, **mykwargs)"
            )
            .testLookup("b", "with_kwargs(true, true, kwargs(extraKey1=true, extraKey2=x))")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaFunctionWithArgsAndKwargs() {
        // Foo is used positionally
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("b = mock.with_args_and_kwargs('foo', 'bar', 'baz', extraKey1=True, extraKey2='x')")
            .testLookup(
                "b", "with_args_and_kwargs(foo, args(bar, baz), kwargs(extraKey1=true, extraKey2=x))"
            )

        // Use an args list and a kwargs dict
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp(
                "mykwargs = {'extraKey1':True}",
                "myargs = ['baz']",
                "b = mock.with_args_and_kwargs('foo', 'bar', extraKey2='x', *myargs, **mykwargs)"
            )
            .testLookup(
                "b", "with_args_and_kwargs(foo, args(bar, baz), kwargs(extraKey2=x, extraKey1=true))"
            )

        // Foo is used by name
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("b = mock.with_args_and_kwargs(foo='foo', extraKey1=True)")
            .testLookup("b", "with_args_and_kwargs(foo, args(), kwargs(extraKey1=true))")

        // Empty args and kwargs.
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("b = mock.with_args_and_kwargs('foo')")
            .testLookup("b", "with_args_and_kwargs(foo, args(), kwargs())")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProxyMethodsObjectWithArgsAndKwargs() {
        // Foo is used positionally
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp(
                "m = mock.proxy_methods_object()",
                "b = m.with_args_and_kwargs('foo', 'bar', 'baz', extraKey1=True, extraKey2='x')"
            )
            .testLookup(
                "b", "with_args_and_kwargs(foo, args(bar, baz), kwargs(extraKey1=true, extraKey2=x))"
            )

        // Use an args list and a kwargs dict
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp(
                "mykwargs = {'extraKey1':True}",
                "myargs = ['baz']",
                "m = mock.proxy_methods_object()",
                "b = m.with_args_and_kwargs('foo', 'bar', extraKey2='x', *myargs, **mykwargs)"
            )
            .testLookup(
                "b", "with_args_and_kwargs(foo, args(bar, baz), kwargs(extraKey2=x, extraKey1=true))"
            )

        // Foo is used by name
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp(
                "m = mock.proxy_methods_object()",
                "b = m.with_args_and_kwargs(foo='foo', extraKey1=True)"
            )
            .testLookup("b", "with_args_and_kwargs(foo, args(), kwargs(extraKey1=true))")

        // Empty args and kwargs.
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("m = mock.proxy_methods_object()", "b = m.with_args_and_kwargs('foo')")
            .testLookup("b", "with_args_and_kwargs(foo, args(), kwargs())")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructAccessOfMethod() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .testExpression("type(mock.function)", "builtin_function_or_method")
        ev.Scenario().update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .testExpression("mock.function()", "a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructAccessTypo() {
        ev.Scenario()
            .update("mock", SimpleStruct(com.google.common.collect.ImmutableMap.of<String?, Any?>("field", 123)))
            .testIfExactError(
                "'SimpleStruct' value has no field or method 'fild' (did you mean 'field'?)",
                "mock.fild"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructAccessType_nonClassObject() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .testIfExactError(
                "'Mock' value has no field or method 'sturct_field' (did you mean 'struct_field'?)",
                "v = mock.sturct_field"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaFunctionReturnsIllegalValue() {
        ev.update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
        val e: UncheckedEvalException? =
            org.junit.Assert.assertThrows<T?>(
                UncheckedEvalException::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("mock.return_bad()") })
        assertThat(e)
            .hasCauseThat()
            .hasMessageThat()
            .contains(
                "invalid Starlark value: class net.starlark.java.eval.StarlarkEvaluationTest\$Bad"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaFunctionReturnsNullFails() {
        ev.update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
        val e: java.lang.RuntimeException? =
            org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
                java.lang.RuntimeException::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("mock.nullfunc_failing('abc', 1)") })
        Truth.assertThat(e).hasCauseThat().hasMessageThat().contains("method invocation returned null")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaFunctionOverflowsStack() {
        ev.update("stackoverflow", getattr(this, "stackoverflow"))
        val e: UncheckedEvalError =
            org.junit.Assert.assertThrows<T>(
                UncheckedEvalError::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("stackoverflow()") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("StackOverflowError thrown during Starlark evaluation")
        assertThat(stream(e.getStackTrace()).map({ obj: java.lang.StackTraceElement? -> obj.getMethodName() }))
            .containsExactly("stackoverflow", "<expr>")
            .inOrder()
        // The underlying exception is preserved as cause.
        assertThat(e).hasCauseThat().isInstanceOf(java.lang.StackOverflowError::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaFunctionThrowsOom() {
        ev.update("throwoom", getattr(this, "throwoom"))
        val e: UncheckedEvalError =
            org.junit.Assert.assertThrows<T>(
                UncheckedEvalError::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("throwoom()") })
        assertThat(e).hasMessageThat().isEqualTo("OutOfMemoryError thrown during Starlark evaluation")
        assertThat(stream(e.getStackTrace()).map({ obj: java.lang.StackTraceElement? -> obj.getMethodName() }))
            .containsExactly("throwoom", "<expr>")
            .inOrder()
        // The underlying exception is preserved as cause.
        assertThat(e).hasCauseThat().isInstanceOf(java.lang.OutOfMemoryError::class.java)
        assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("Java heap space")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaFunctionThrowsNpe() {
        ev.update("thrownpe", getattr(this, "thrownpe"))
        val e: UncheckedEvalException =
            org.junit.Assert.assertThrows<T>(
                UncheckedEvalException::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("thrownpe()") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("NullPointerException thrown during Starlark evaluation")
        assertThat(stream(e.getStackTrace()).map({ obj: java.lang.StackTraceElement? -> obj.getMethodName() }))
            .containsExactly("thrownpe", "<expr>")
            .inOrder()
        // The underlying exception is preserved as cause.
        assertThat(e).hasCauseThat().isInstanceOf(java.lang.NullPointerException::class.java)
        assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("oops")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uncheckedExceptionContextAppendedToMessage() {
        ev.update("thrownpe", getattr(this, "thrownpe"))
            .getStarlarkThread()
            .setUncheckedExceptionContext({ "some extra context" })
        val e: UncheckedEvalException? =
            org.junit.Assert.assertThrows<T?>(
                UncheckedEvalException::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("thrownpe()") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("NullPointerException thrown during Starlark evaluation (some extra context)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClassObjectAccess() {
        ev.Scenario()
            .update("mock", SimpleStruct(com.google.common.collect.ImmutableMap.of<String?, Any?>("field", "a")))
            .setUp("v = mock.field")
            .testLookup("v", "a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFieldReturnsNonStarlarkValue() {
        ev.update(
            "s",
            SimpleStruct(com.google.common.collect.ImmutableMap.of<String?, Any?>("bad", java.lang.StringBuilder()))
        )
        val e: java.lang.RuntimeException? = org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
            java.lang.RuntimeException::class.java,
            org.junit.function.ThrowingRunnable { ev.eval("s.bad") })
        Truth.assertThat(e)
            .hasCauseThat()
            .hasMessageThat()
            .contains("invalid Starlark value: class java.lang.StringBuilder")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaFunctionReturnsNone() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("v = mock.nullfunc_working()")
            .testLookup("v", Starlark.NONE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVoidJavaFunctionReturnsNone() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp("v = mock.voidfunc()")
            .testLookup("v", Starlark.NONE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAugmentedAssignment() {
        ev.Scenario()
            .setUp("def f1(x):", "  x += 1", "  return x", "", "foo = f1(41)")
            .testLookup("foo", StarlarkInt.of(42))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAugmentedAssignmentHasNoSideEffects() {
        // Check object position.
        ev.Scenario()
            .setUp(
                "counter = [0]",
                "value = [1, 2]",
                "",
                "def f():",
                "  counter[0] = counter[0] + 1",
                "  return value",
                "",
                "f()[1] += 1"
            ) // `f()` should be called only once here
            .testLookup("counter", StarlarkList.of(null, StarlarkInt.of(1)))

        // Check key position.
        ev.Scenario()
            .setUp(
                "counter = [0]",
                "value = [1, 2]",
                "",
                "def f():",
                "  counter[0] = counter[0] + 1",
                "  return 1",
                "",
                "value[f()] += 1"
            ) // `f()` should be called only once here
            .testLookup("counter", StarlarkList.of(null, StarlarkInt.of(1)))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidAugmentedAssignment_listExpression() {
        assertResolutionError(
            "cannot perform augmented assignment on a list or tuple expression",  //
            "def f(a, b):",
            "  [a, b] += []",
            "f(1, 2)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidAugmentedAssignment_notAnLValue() {
        assertResolutionError(
            "cannot assign to 'x + 1'",  //
            "x + 1 += 2"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAssignmentEvaluationOrder() {
        ev.Scenario()
            .setUp(
                "ordinary = []",
                "augmented = []",
                "value = [1, 2]",
                "",
                "def f(record):",
                "  record.append('f')",
                "  return value",
                "",
                "def g(record):",
                "  record.append('g')",
                "  return value",
                "",
                "f(ordinary)[0] = g(ordinary)[1]",
                "f(augmented)[0] += g(augmented)[1]"
            )
            .testLookup("ordinary", StarlarkList.of(null, "g", "f")) // This order is consistent
            .testLookup("augmented", StarlarkList.of(null, "f", "g")) // with Python
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictComprehensions_iterationOrder() {
        ev.Scenario()
            .setUp(
                "def foo():",
                "  d = {x : x for x in ['c', 'a', 'b']}",
                "  s = ''",
                "  for a in d:",
                "    s += a",
                "  return s",
                "s = foo()"
            )
            .testLookup("s", "cab")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDotExpressionOnNonStructObject() {
        ev.Scenario()
            .testIfExactError(
                "'string' value has no field or method 'field' (did you mean 'find'?)",
                "x = 'a'.field"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlusEqualsOnListMutating() {
        ev.Scenario()
            .setUp(
                "def func():",
                "  l1 = [1, 2]",
                "  l2 = l1",
                "  l2 += [3, 4]",
                "  return l1, l2",
                "lists = str(func())"
            )
            .testLookup("lists", "([1, 2, 3, 4], [1, 2, 3, 4])")

        // The same but with += after an IndexExpression
        ev.Scenario()
            .setUp(
                "def func():",
                "  l = [1, 2]",
                "  d = {0: l}",
                "  d[0] += [3, 4]",
                "  return l, d[0]",
                "lists = str(func())"
            )
            .testLookup("lists", "([1, 2, 3, 4], [1, 2, 3, 4])")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlusEqualsOnTuple() {
        ev.Scenario()
            .setUp(
                "def func():",
                "  t1 = (1, 2)",
                "  t2 = t1",
                "  t2 += (3, 4)",
                "  return t1, t2",
                "tuples = func()"
            )
            .testLookup(
                "tuples",
                Tuple.of(
                    Tuple.of(StarlarkInt.of(1), StarlarkInt.of(2)),
                    Tuple.of(
                        StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3), StarlarkInt.of(4)
                    )
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlusOnDictDeprecated() {
        ev.Scenario()
            .testIfErrorContains("unsupported binary operation: dict + dict", "{1: 2} + {3: 4}")
        ev.Scenario()
            .testIfErrorContains(
                "unsupported binary operation: dict + dict",
                "def func():",
                "  d = {1: 2}",
                "  d += {3: 4}",
                "func()"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictAssignmentAsLValue() {
        ev.Scenario()
            .setUp("def func():", "  d = {'a' : 1}", "  d['b'] = 2", "  return d", "d = func()")
            .testLookup(
                "d",
                com.google.common.collect.ImmutableMap.of<K?, V?>("a", StarlarkInt.of(1), "b", StarlarkInt.of(2))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNestedDictAssignmentAsLValue() {
        ev.Scenario()
            .setUp(
                "def func():",
                "  d = {'a' : 1}",
                "  e = {'d': d}",
                "  e['d']['b'] = 2",
                "  return e",
                "e = func()"
            )
            .testLookup(
                "e",
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "d",
                    com.google.common.collect.ImmutableMap.of<K?, V?>("a", StarlarkInt.of(1), "b", StarlarkInt.of(2))
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListAssignmentAsLValue() {
        ev.Scenario()
            .setUp(
                "def func():",
                "  a = [1, 2]",
                "  a[1] = 3",
                "  a[-2] = 4",
                "  return a",
                "a = str(func())"
            )
            .testLookup("a", "[4, 3]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNestedListAssignmentAsLValue() {
        ev.Scenario()
            .setUp(
                "def func():",
                "  d = [1, 2]",
                "  e = [3, d]",
                "  e[1][1] = 4",
                "  return e",
                "e = str(func())"
            )
            .testLookup("e", "[3, [1, 4]]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictTupleAssignmentAsLValue() {
        ev.Scenario()
            .setUp(
                "def func():", "  d = {'a' : 1}", "  d['b'], d['c'] = 2, 3", "  return d", "d = func()"
            )
            .testLookup(
                "d",
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "a", StarlarkInt.of(1), "b", StarlarkInt.of(2), "c", StarlarkInt.of(3)
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictItemPlusEqual() {
        ev.Scenario()
            .setUp("def func():", "  d = {'a' : 2}", "  d['a'] += 3", "  return d", "d = func()")
            .testLookup("d", com.google.common.collect.ImmutableMap.of<K?, V?>("a", StarlarkInt.of(5)))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDictAssignmentAsLValueSideEffects() {
        ev.Scenario()
            .setUp("def func(d):", "  d['b'] = 2", "d = {'a' : 1}", "func(d)")
            .testLookup(
                "d",
                Dict.builder()
                    .put("a", StarlarkInt.of(1))
                    .put("b", StarlarkInt.of(2))
                    .buildImmutable()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAssignmentToListInDictSideEffect() {
        ev.Scenario()
            .setUp("l = [1, 2]", "d = {0: l}", "d[0].append(3)")
            .testLookup(
                "l", StarlarkList.of(null, StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUserFunctionKeywordArgs() {
        ev.Scenario()
            .setUp("def foo(a, b, c):", "  return a + b + c", "s = foo(1, c=2, b=3)")
            .testLookup("s", StarlarkInt.of(6))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionCallOrdering() {
        ev.Scenario()
            .setUp("def func(): return foo() * 2", "def foo(): return 2", "x = func()")
            .testLookup("x", StarlarkInt.of(4))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionCallBadOrdering() {
        ev.Scenario()
            .testIfErrorContains(
                "global variable 'foo' is referenced before assignment.",
                "def func(): return foo() * 2",
                "x = func()",
                "def foo(): return 2"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLocalVariableDefinedBelow() {
        ev.Scenario()
            .setUp(
                "def beforeEven(li):",  // returns the value before the first even number
                "    for i in li:",
                "        if i % 2 == 0:",
                "            return a",
                "        else:",
                "            a = i",
                "res = beforeEven([1, 3, 4, 5])"
            )
            .testLookup("res", StarlarkInt.of(3))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShadowisNotInitialized() {
        ev.Scenario()
            .testIfErrorContains( /* error message */
                "local variable 'gl' is referenced before assignment",
                "gl = 5",
                "def foo():",
                "    if False: gl = 2",
                "    return gl",
                "res = foo()"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShadowBuiltin() {
        ev.Scenario()
            .testIfErrorContains(
                "global variable 'len' is referenced before assignment",
                "x = len('abc')",
                "len = 2",
                "y = x + len"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRecursionDisallowedByDefault() {
        ev.Scenario()
            .testIfErrorContains(
                "function 'f' called recursively",
                "def main():",
                "  f(5)",
                "def f(n):",
                "  if n > 0: g(n - 1)",
                "def g(n):",
                "  if n > 0: f(n - 1)",
                "main()"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRecursionAllowedWithOption() {
        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines(
                "def fac(n): return 1 if n < 2 else n * fac(n - 1)",  //
                "x = fac(5)"
            )
        val module: java.lang.Module = java.lang.Module.create()
        Mutability.create("test").use { mu ->
            val semantics: StarlarkSemantics? =
                StarlarkSemantics.builder().setBool(StarlarkSemantics.ALLOW_RECURSION, true).build()
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, semantics)
            Starlark.execFile(input, net.starlark.java.syntax.FileOptions.DEFAULT, module, thread)
        }
        assertThat(module.getGlobal("x")).isEqualTo(StarlarkInt.of(120))
    }

    @org.junit.Test // TODO(adonovan): move to ResolverTest.
    @Throws(java.lang.Exception::class)
    fun testTypo() {
        assertResolutionError(
            "name 'my_variable' is not defined (did you mean 'myVariable'?)",  //
            "myVariable = 2",
            "x = my_variable + 1"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoneTrueFalseInStarlark() {
        ev.Scenario()
            .setUp("a = None", "b = True", "c = False")
            .testLookup("a", Starlark.NONE)
            .testLookup("b", java.lang.Boolean.TRUE)
            .testLookup("c", java.lang.Boolean.FALSE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHasattrMethods() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp(
                "a = hasattr(mock, 'struct_field')",
                "b = hasattr(mock, 'function')",
                "c = hasattr(mock, 'is_empty')",
                "d = hasattr('str', 'replace')",
                "e = hasattr(mock, 'other')\n"
            )
            .testLookup("a", java.lang.Boolean.TRUE)
            .testLookup("b", java.lang.Boolean.TRUE)
            .testLookup("c", java.lang.Boolean.TRUE)
            .testLookup("d", java.lang.Boolean.TRUE)
            .testLookup("e", java.lang.Boolean.FALSE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetattrMethods() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .setUp(
                "a = str(getattr(mock, 'struct_field', 'no'))",
                "b = str(getattr(mock, 'function', 'no'))",
                "c = str(getattr(mock, 'is_empty', 'no'))",
                "d = str(getattr('str', 'replace', 'no'))",
                "e = str(getattr(mock, 'other', 'no'))\n"
            )
            .testLookup("a", "a")
            .testLookup("b", "<built-in method function of Mock value>")
            .testLookup("c", "<built-in method is_empty of Mock value>")
            .testLookup("d", "<built-in method replace of string value>")
            .testLookup("e", "no")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListAnTupleConcatenationDoesNotWorkInStarlark() {
        ev.Scenario()
            .testIfExactError("unsupported binary operation: list + tuple", "[1, 2] + (3, 4)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotCreateMixedListInStarlark() {
        ev.Scenario()
            .testExactOrder("['a', 'b', 1, 2]", "a", "b", StarlarkInt.of(1), StarlarkInt.of(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotConcatListInStarlarkWithDifferentGenericTypes() {
        ev.Scenario()
            .testExactOrder("[1, 2] + ['a', 'b']", StarlarkInt.of(1), StarlarkInt.of(2), "a", "b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConcatEmptyListWithNonEmptyWorks() {
        ev.Scenario().testExactOrder("[] + ['a', 'b']", "a", "b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFormatStringWithTuple() {
        ev.Scenario().setUp("v = '%s%s' % ('a', 1)").testLookup("v", "a1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingletonTuple() {
        ev.Scenario().testExactOrder("(1,)", StarlarkInt.of(1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirFindsClassObjectFields() {
        ev.Scenario()
            .update(
                "s",
                SimpleStruct(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "a",
                        StarlarkInt.of(1),
                        "b",
                        StarlarkInt.of(2)
                    )
                )
            )
            .testExactOrder("dir(s)", "a", "b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirFindsJavaObjectStructFieldsAndMethods() {
        ev.Scenario()
            .update("mock", net.starlark.java.eval.StarlarkEvaluationTest.Mock())
            .testExactOrder(
                "dir(mock)",
                "function",
                "interrupted_struct_field",
                "is_empty",
                "nullfunc_failing",
                "nullfunc_working",
                "proxy_methods_object",
                "return_bad",
                "string",
                "string_list",
                "string_list_dict",
                "struct_field",
                "struct_field_callable",
                "struct_field_with_extra",
                "value_of",
                "voidfunc",
                "with_args_and_kwargs",
                "with_args_and_thread",
                "with_extra",
                "with_kwargs",
                "with_params",
                "with_params_and_extra"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrint() {
        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines(
                "print('hello')",  //
                "print('a', 'b')",
                "print('a', 'b', sep='x')"
            )
        val prints: MutableList<String?> = java.util.ArrayList<String?>()
        Mutability.create("test").use { mu ->
            val thread: StarlarkThread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            thread.printHandler = { unused, msg -> prints.add(msg) }
            Starlark.execFile(input, net.starlark.java.syntax.FileOptions.DEFAULT, java.lang.Module.create(), thread)
        }
        Truth.assertThat(prints).containsExactly("hello", "a b", "axb").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrintBadKwargs() {
        ev.Scenario()
            .testIfErrorContains(
                "print() got unexpected keyword argument 'end'", "print(end='x', other='y')"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConditionalExpressionAtToplevel() {
        ev.Scenario().setUp("x = 1 if 2 else 3").testLookup("x", StarlarkInt.of(1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConditionalExpressionInFunction() {
        ev.Scenario()
            .setUp("def foo(a, b, c): return a+b if c else a-b\n")
            .testExpression("foo(23, 5, 0)", StarlarkInt.of(18))
    }

    // SimpleStructWithMethods augments SimpleStruct's fields with annotated Java methods.
    private class SimpleStructWithMethods : SimpleStruct(
        com.google.common.collect.ImmutableMap.of<String?, Any?>(
            "values_only_field",
            "fromValues",
            "values_only_method",
            returnFromValues,
            "collision_field",
            "fromValues",
            "collision_method",
            returnFromValues
        )
    ) {
        @get:StarlarkMethod(name = "callable_only_field", documented = false, structField = true)
        val callableOnlyField: String
            get() = "fromStarlarkMethod"

        @get:StarlarkMethod(name = "callable_only_method", documented = false, structField = false)
        val callableOnlyMethod: String
            get() = "fromStarlarkMethod"

        @get:StarlarkMethod(name = "collision_field", documented = false, structField = true)
        val collisionField: String
            get() = "fromStarlarkMethod"

        @get:StarlarkMethod(name = "collision_method", documented = false, structField = false)
        val collisionMethod: String
            get() = "fromStarlarkMethod"

        companion object {
            // A function that returns "fromValues".
            private val returnFromValues: Any = object : StarlarkCallable() {
                val name: String
                    get() = "returnFromValues"

                public override fun call(thread: StarlarkThread?, args: Tuple?, kwargs: Dict<String?, Any?>?): Any {
                    return "fromValues"
                }
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructFieldDefinedOnlyInValues() {
        ev.Scenario()
            .update("val", SimpleStructWithMethods())
            .setUp("v = val.values_only_field")
            .testLookup("v", "fromValues")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructMethodDefinedOnlyInValues() {
        ev.Scenario()
            .update("val", SimpleStructWithMethods())
            .setUp("v = val.values_only_method()")
            .testLookup("v", "fromValues")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructFieldDefinedOnlyInStarlarkMethod() {
        ev.Scenario()
            .update("val", SimpleStructWithMethods())
            .setUp("v = val.callable_only_field")
            .testLookup("v", "fromStarlarkMethod")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructMethodDefinedOnlyInStarlarkMethod() {
        ev.Scenario()
            .update("val", SimpleStructWithMethods())
            .setUp("v = val.callable_only_method()")
            .testLookup("v", "fromStarlarkMethod")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructMethodDefinedInValuesAndStarlarkMethod() {
        // This test exercises the resolution of ambiguity between @StarlarkMethod-annotated
        // fields and those reported by Structure.getValue.
        ev.Scenario()
            .update("val", SimpleStructWithMethods())
            .setUp("v = val.collision_method()")
            .testLookup("v", "fromStarlarkMethod")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrNotDefined() {
        ev.Scenario()
            .update("s", SimpleStructWithMethods()) // dir shows all fields and methods
            .testEval(
                "dir(s)",
                "['callable_only_field', 'callable_only_method', 'collision_field',"
                        + " 'collision_method', 'values_only_field', 'values_only_method']"
            ) // field-like non-existent access
            .testIfExactError(
                "'SimpleStructWithMethods' value has no field or method 'nonesuch'", "s.nonesuch"
            ) // method-like non-existent access (same result)
            .testIfExactError(
                "'SimpleStructWithMethods' value has no field or method 'nonesuch'", "s.nonesuch()"
            ) // spelling hint
            .testIfExactError(
                "'SimpleStructWithMethods' value has no field or method 'collision_metod' (did you"
                        + " mean 'collision_method'?)",
                "s.collision_metod"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListComprehensionsShadowGlobalVariable() {
        ev.exec(
            "a = 18",  //
            "def foo():",
            "  b = [a for a in range(3)]",
            "  return a",
            "x = foo()"
        )
        Truth.assertThat(ev.lookup("x")).isEqualTo(StarlarkInt.of(18))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testComprehensionsAreLocal() {
        // Regression test for https://github.com/bazelbuild/starlark/issues/92.
        ev.exec(
            "x = 1",  // this global binding is not affected (even temporarily) by the comprehension
            "def f():",
            "  return x",
            "y = [f() for x in [2]][0]"
        )
        Truth.assertThat(ev.lookup("y")).isEqualTo(StarlarkInt.of(1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionEvaluatedBeforeArguments() {
        // ''.nonesuch must be evaluated (and fail) before f().
        ev.Scenario()
            .testIfErrorContains(
                "'string' value has no field or method 'nonesuch'",
                "def f(): x = 1//0",
                "''.nonesuch(f())"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAddMethodsRejectsFields() {
        val ex: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable {
                    Starlark.addMethods(
                        com.google.common.collect.ImmutableMap.builder<K?, V?>(),
                        net.starlark.java.eval.StarlarkEvaluationTest.Mock()
                    )
                })
        Truth.assertThat(ex).hasMessageThat().contains("method struct_field has structField=true")
    }

    companion object {
        private fun getattr(x: Any?, name: String?): Any {
            try {
                return Starlark.getattr(
                    Mutability.IMMUTABLE, StarlarkSemantics.DEFAULT, x, name, Starlark.NONE
                )
            } catch (ex: EvalException) {
                throw java.lang.IllegalStateException(ex)
            } catch (ex: java.lang.InterruptedException) {
                throw java.lang.IllegalStateException(ex)
            }
        }

        private fun debugPrintArgs(args: Iterable<*>, thread: StarlarkThread?): String {
            val p: Printer = Printer()
            p.append("args(")
            var sep = ""
            for (arg in args) {
                p.append(sep).debugPrint(arg, thread)
                sep = ", "
            }
            return p.append(")").toString()
        }
    }
}
