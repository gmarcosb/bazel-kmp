// Copyright 2006 The Bazel Authors. All Rights Reserved.
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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import net.starlark.java.eval.EvaluationTestCase
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkSemantics
import net.starlark.java.eval.StarlarkThread
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests of StarlarkThread.  */
@RunWith(JUnit4::class)
class StarlarkThreadTest {
    private val ev: EvaluationTestCase = EvaluationTestCase()

    // Test the API directly
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLookupAndUpdate() {
        Truth.assertThat(ev.lookup("foo")).isNull()
        ev.update("foo", "bar")
        Truth.assertThat(ev.lookup("foo")).isEqualTo("bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleUpdateSucceeds() {
        Truth.assertThat(ev.lookup("VERSION")).isNull()
        ev.update("VERSION", StarlarkInt.of(42))
        Truth.assertThat(ev.lookup("VERSION")).isEqualTo(StarlarkInt.of(42))
        ev.update("VERSION", StarlarkInt.of(43))
        Truth.assertThat(ev.lookup("VERSION")).isEqualTo(StarlarkInt.of(43))
    }

    // Test assign through interpreter, ev.lookup through API:
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAssign() {
        Truth.assertThat(ev.lookup("foo")).isNull()
        ev.exec("foo = 'bar'")
        Truth.assertThat(ev.lookup("foo")).isEqualTo("bar")
    }

    // Test update through API, reference through interpreter:
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReference() {
        val e: net.starlark.java.syntax.SyntaxError.Exception? =
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception?>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("foo") })
        Truth.assertThat(e).hasMessageThat().isEqualTo("name 'foo' is not defined")
        ev.update("foo", "bar")
        Truth.assertThat(ev.eval("foo")).isEqualTo("bar")
    }

    // Test assign and reference through interpreter:
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAssignAndReference() {
        val e: net.starlark.java.syntax.SyntaxError.Exception? =
            org.junit.Assert.assertThrows<net.starlark.java.syntax.SyntaxError.Exception?>(
                net.starlark.java.syntax.SyntaxError.Exception::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("foo") })
        Truth.assertThat(e).hasMessageThat().isEqualTo("name 'foo' is not defined")
        ev.exec("foo = 'bar'")
        Truth.assertThat(ev.eval("foo")).isEqualTo("bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBindToNullThrowsException() {
        val e: java.lang.NullPointerException? =
            org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
                java.lang.NullPointerException::class.java,
                org.junit.function.ThrowingRunnable { ev.update("some_name", null) })
        Truth.assertThat(e).hasMessageThat().isEqualTo("Module.setGlobal(some_name, null)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUniverseCanBeShadowed() {
        val module: java.lang.Module = java.lang.Module.create()
        Mutability.create("test").use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            Starlark.execFile(
                net.starlark.java.syntax.ParserInput.fromLines("True = 123"),
                net.starlark.java.syntax.FileOptions.DEFAULT,
                module,
                thread
            )
        }
        assertThat(module.getGlobal("True")).isEqualTo(StarlarkInt.of(123))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVariableIsReferencedBeforeAssignment() {
        ev.Scenario()
            .testIfErrorContains(
                "local variable 'y' is referenced before assignment",
                "y = 1",  // bind => y is global
                "def foo(x):",
                "  x += y",  // fwd ref to local y
                "  y = 2",  // binding => y is local
                "  return x",
                "foo(1)"
            )
        ev.Scenario()
            .testIfErrorContains(
                "global variable 'len' is referenced before assignment",
                "print(len)",  // fwd ref to global len
                "len = 1"
            ) // binding => len is local
    }
}
