// Copyright 2018 The Bazel Authors. All Rights Reserved.
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

import net.starlark.java.eval.Debug.ReadyToPause

/** Tests of debugging features of StarlarkThread.  */
@RunWith(JUnit4::class)
class StarlarkThreadDebuggingTest {
    @org.junit.Test
    fun testListFramesEmptyStack() {
        val thread: StarlarkThread = newThread()
        assertThat(Debug.getCallStack(thread)).isEmpty()
        assertThat(thread.getCallStack()).isEmpty()
    }

    /**
     * A callable which captures the Starlark call stack at the time of the last call to it.
     * 
     * 
     * In Starlark, returns the first positional arg if supplied, or None otherwise.
     */
    private class StackTracer(val name: String?) : StarlarkCallable {
        // Debug.Frame values are mutable (and are expected to mutate during the execution of a thread),
        // so we capture their formatted string form instead. (The string form also makes test failures
        // more informative.)
        private var debugStack: com.google.common.collect.ImmutableList<String?>? = null
        private var liteStack: com.google.common.collect.ImmutableList<CallStackEntry?>? = null

        fun getDebugStack(): com.google.common.collect.ImmutableList<String?>? {
            return debugStack
        }

        val callerDebugFrame: String?
            get() = if (debugStack != null) debugStack.get(debugStack.size - 2) else null

        fun getLiteStack(): com.google.common.collect.ImmutableList<CallStackEntry?>? {
            return liteStack
        }

        public override fun call(thread: StarlarkThread, args: Tuple, kwargs: Dict<String?, Any?>?): Any? {
            debugStack =
                Debug.getCallStack(thread).stream()
                    .map({ fr: Debug.Frame -> this.formatDebugFrame(fr) })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            liteStack = thread.getCallStack()
            return com.google.common.collect.Iterables.getFirst<Any?>(args, Starlark.NONE)
        }

        fun formatDebugFrame(fr: Debug.Frame): String? {
            return java.lang.String.format(
                "%s @ %s local=%s", fr.getFunction().getName(), fr.getLocation(), fr.getLocals()
            )
        }

        val location: net.starlark.java.syntax.Location
            get() = net.starlark.java.syntax.Location.BUILTIN

        override fun toString(): String {
            return "<stack tracer>"
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListFramesFromBuiltin() {
        // f is a built-in that captures the stack using the Debugger API.
        val f = StackTracer("f")

        // Set up global environment.
        val module: java.lang.Module? =
            java.lang.Module.withPredeclared(
                StarlarkSemantics.DEFAULT,
                com.google.common.collect.ImmutableMap.of<K?, V?>("a", 1, "b", 2, "f", f)
            )

        // Execute a small file that calls f.
        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromString(
                """
def g(a, y, z):  # shadows global a
    f()

g(4, 5, 6)

""".trimIndent(),
                "main.star"
            )
        Starlark.execFile(input, net.starlark.java.syntax.FileOptions.DEFAULT, module, newThread())

        Truth.assertThat(f.getDebugStack())
            .containsExactly( // location is paren of g(4, 5, 6) call:
                "<toplevel> @ main.star:4:2 local={}",  // location is paren of "f()" call:
                "g @ main.star:2:6 local={a=4, y=5, z=6}",  // location is "current PC" in f.
                "f @ <builtin> local={}"
            )
            .inOrder()

        // Same, with "lite" stack API.
        Truth.assertThat(f.getLiteStack().toString()) // an ImmutableList<StarlarkThread.CallStackEntry>
            .isEqualTo("[<toplevel>@main.star:4:2, g@main.star:2:6, f@<builtin>]")

        // TODO(adonovan): more tests:
        // - a stack containing functions defined in different modules.
        // - changing environment at various program points within a function.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun comprehensionVariables() {
        // Tracers for capturing the stack using the Debugger API.
        val f = StackTracer("f")
        val g = StackTracer("g")
        val h = StackTracer("h")
        val i = StackTracer("i")
        val j = StackTracer("j")
        val k = StackTracer("k")

        val module: java.lang.Module? =
            java.lang.Module.withPredeclared(
                StarlarkSemantics.DEFAULT,
                com.google.common.collect.ImmutableMap.of<K?, V?>("f", f, "g", g, "h", h, "i", i, "j", j, "k", k)
            )

        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromString(
                """
def foo(x):
    x += [[j(x) for x in i(x)] + h(x) for x in f(x) if g(x)]
    return k(x)

foo([[1]])

""".trimIndent(),
                "main.star"
            )
        Starlark.execFile(input, net.starlark.java.syntax.FileOptions.DEFAULT, module, newThread())
        // f is in the outer comprehension's first for clause, and sees foo's local x
        Truth.assertThat(f.callerDebugFrame).isEqualTo("foo @ main.star:2:49 local={x=[[1]]}")
        // g and h see the outer comprehension's x
        Truth.assertThat(g.callerDebugFrame).isEqualTo("foo @ main.star:2:57 local={x=[1]}")
        Truth.assertThat(h.callerDebugFrame).isEqualTo("foo @ main.star:2:35 local={x=[1]}")
        // i is in the inner comprehension's first for clause, and so sees the outer comprehension's x
        Truth.assertThat(i.callerDebugFrame).isEqualTo("foo @ main.star:2:27 local={x=[1]}")
        // j sees the inner comprehension's x
        Truth.assertThat(j.callerDebugFrame).isEqualTo("foo @ main.star:2:13 local={x=1}")
        // k is outside the comprehensions' scope, and sees the final value of foo's local x
        Truth.assertThat(k.callerDebugFrame).isEqualTo("foo @ main.star:3:13 local={x=[[1], [1, 1]]}")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStepIntoFunction() {
        val thread: StarlarkThread = newThread()

        val predicate: ReadyToPause = Debug.stepControl(thread, Stepping.INTO)
        thread.push(defineFunc())

        assertThat(predicate.test(thread)).isTrue()
    }

    @org.junit.Test
    fun testStepIntoFallsBackToStepOver() {
        // test that when stepping into, we'll fall back to stopping at the next statement in the
        // current frame
        val thread: StarlarkThread = newThread()

        val predicate: ReadyToPause = Debug.stepControl(thread, Stepping.INTO)

        assertThat(predicate.test(thread)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStepIntoFallsBackToStepOut() {
        // test that when stepping into, we'll fall back to stopping when exiting the current frame
        val thread: StarlarkThread = newThread()
        thread.push(defineFunc())

        val predicate: ReadyToPause = Debug.stepControl(thread, Stepping.INTO)
        thread.pop()

        assertThat(predicate.test(thread)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStepOverFunction() {
        val thread: StarlarkThread = newThread()

        val predicate: ReadyToPause = Debug.stepControl(thread, Stepping.OVER)
        thread.push(defineFunc())

        assertThat(predicate.test(thread)).isFalse()
        thread.pop()
        assertThat(predicate.test(thread)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStepOverFallsBackToStepOut() {
        // test that when stepping over, we'll fall back to stopping when exiting the current frame
        val thread: StarlarkThread = newThread()
        thread.push(defineFunc())

        val predicate: ReadyToPause = Debug.stepControl(thread, Stepping.OVER)
        thread.pop()

        assertThat(predicate.test(thread)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStepOutOfInnerFrame() {
        val thread: StarlarkThread = newThread()
        thread.push(defineFunc())

        val predicate: ReadyToPause = Debug.stepControl(thread, Stepping.OUT)

        assertThat(predicate.test(thread)).isFalse()
        thread.pop()
        assertThat(predicate.test(thread)).isTrue()
    }

    @org.junit.Test
    fun testStepOutOfOutermostFrame() {
        val thread: StarlarkThread = newThread()

        assertThat(Debug.stepControl(thread, Stepping.OUT)).isNull()
    }

    @org.junit.Test
    fun testStepControlWithNoSteppingReturnsNull() {
        val thread: StarlarkThread = newThread()

        assertThat(Debug.stepControl(thread, Stepping.NONE)).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEvaluateVariableInScope() {
        val module: java.lang.Module? =
            java.lang.Module.withPredeclared(
                StarlarkSemantics.DEFAULT,
                com.google.common.collect.ImmutableMap.of<K?, V?>("a", StarlarkInt.of(1))
            )

        val thread: StarlarkThread = newThread()
        val a: Any? = Starlark.execFile(
            net.starlark.java.syntax.ParserInput.fromLines("a"),
            net.starlark.java.syntax.FileOptions.DEFAULT,
            module,
            thread
        )
        Truth.assertThat(a).isEqualTo(StarlarkInt.of(1))
    }

    @org.junit.Test
    fun testEvaluateVariableNotInScopeFails() {
        val module: java.lang.Module? = java.lang.Module.create()

        val e: net.starlark.java.syntax.SyntaxError.Exception? =
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception?>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable {
                    Starlark.execFile(
                        net.starlark.java.syntax.ParserInput.fromLines("b"),
                        net.starlark.java.syntax.FileOptions.DEFAULT,
                        module,
                        newThread()
                    )
                })

        Truth.assertThat(e).hasMessageThat().isEqualTo("name 'b' is not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEvaluateExpressionOnVariableInScope() {
        val thread: StarlarkThread = newThread()
        val module: java.lang.Module? =
            java.lang.Module.withPredeclared(
                StarlarkSemantics.DEFAULT,  /*predeclared=*/
                com.google.common.collect.ImmutableMap.of<K?, V?>("a", "string")
            )

        assertThat(
            Starlark.execFile(
                net.starlark.java.syntax.ParserInput.fromLines("a.startswith('str')"),
                net.starlark.java.syntax.FileOptions.DEFAULT,
                module,
                thread
            )
        )
            .isEqualTo(true)
        Starlark.execFile(
            net.starlark.java.syntax.ParserInput.fromLines("a = 1"),
            net.starlark.java.syntax.FileOptions.DEFAULT,
            module,
            thread
        )
        assertThat(
            Starlark.execFile(
                net.starlark.java.syntax.ParserInput.fromLines("a"),
                net.starlark.java.syntax.FileOptions.DEFAULT,
                module,
                thread
            )
        )
            .isEqualTo(StarlarkInt.of(1))
    }

    companion object {
        // TODO(adonovan): rewrite these tests at a higher level.
        private fun newThread(): StarlarkThread {
            return StarlarkThread.createTransient(Mutability.create("test"), StarlarkSemantics.DEFAULT)
        }

        // Executes the definition of a trivial function f and returns the function value.
        @Throws(java.lang.Exception::class)
        private fun defineFunc(): StarlarkFunction? {
            return Starlark.execFile(
                net.starlark.java.syntax.ParserInput.fromLines("def f(): pass\nf"),
                net.starlark.java.syntax.FileOptions.DEFAULT,
                java.lang.Module.create(),
                newThread()
            ) as StarlarkFunction?
        }
    }
}
