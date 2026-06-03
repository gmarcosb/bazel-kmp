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
package net.starlark.java.eval

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import net.starlark.java.eval.EvaluationTestCase
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkSemantics
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.syntax.Location.column
import net.starlark.java.syntax.Location.line
import net.starlark.java.syntax.SyntaxError.Exception.errors
import net.starlark.java.syntax.SyntaxError.location
import net.starlark.java.syntax.TypeTable.errors
import java.util.LinkedList

/** Helper class for tests that evaluate Starlark code.  */ // TODO(adonovan): simplify this class out of existence.
// Most of its callers should be using the script-based test harness in net.starlark.java.eval.
// TODO(adonovan): extended only by StarlarkFlagGuardingTest; specialize that one test instead.
internal open class EvaluationTestCase {
    private var semantics: StarlarkSemantics? = StarlarkSemantics.DEFAULT
    private var thread: StarlarkThread? = null // created lazily by getStarlarkThread
    private var module: java.lang.Module? = null // created lazily by getModule

    private var fileOptions: net.starlark.java.syntax.FileOptions? = net.starlark.java.syntax.FileOptions.DEFAULT

    /**
     * Updates the semantics used to filter predeclared bindings, and carried by subsequently created
     * threads. Causes a new StarlarkThread and Module to be created when next needed.
     */
    fun setSemantics(semantics: StarlarkSemantics?) {
        this.semantics = semantics

        // Re-initialize the thread and module with the new semantics when needed.
        this.thread = null
        this.module = null
    }

    fun getFileOptions(): net.starlark.java.syntax.FileOptions? {
        return fileOptions
    }

    fun setFileOptions(fileOptions: net.starlark.java.syntax.FileOptions?) {
        this.fileOptions = fileOptions
    }

    // TODO(adonovan): don't let subclasses inherit vaguely specified "helpers".
    // Separate all the tests clearly into tests of the scanner, parser, resolver,
    // and evaluation.
    /** Updates a global binding in the module.  */ // TODO(adonovan): rename setGlobal.
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.Exception::class)
    fun update(varname: String?, value: Any?): EvaluationTestCase {
        getModule().setGlobal(varname, value)
        return this
    }

    /** Returns the value of a global binding in the module.  */ // TODO(adonovan): rename getGlobal.
    @Throws(java.lang.Exception::class)
    fun lookup(varname: String?): Any {
        return getModule().getGlobal(varname)
    }

    /** Joins the lines, parses them as an expression, and evaluates it.  */
    @Throws(java.lang.Exception::class)
    fun eval(vararg lines: String?): Any {
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(lines)
        return Starlark.eval(input, getFileOptions(), getModule(), this.starlarkThread)
    }

    /** Joins the lines, parses them as a file, and executes it.  */
    @Throws(
        net.starlark.java.syntax.SyntaxError.Exception::class,
        EvalException::class,
        java.lang.InterruptedException::class
    )
    fun exec(vararg lines: String?) {
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(lines)
        Starlark.execFile(input, getFileOptions(), getModule(), this.starlarkThread)
    }

    // A hook for subclasses to alter the created module.
    // Implementations may add to the predeclared environment.
    // TODO(adonovan): only used in StarlarkFlagGuardingTest; move there.
    protected open fun newModuleHook(predeclared: com.google.common.collect.ImmutableMap.Builder<String?, Any?>?) {}

    val starlarkThread: StarlarkThread?
        get() {
            if (this.thread == null) {
                val mu: Mutability? = Mutability.create("test")
                this.thread =
                    StarlarkThread.create(
                        mu, semantics,  /* contextDescription= */"", SymbolGenerator.create("test")
                    )
                // Sets a post-assign hook to enable global export of StarlarkFunction Symbols.
                this.thread.setPostAssignHook({ unusedName, unusedLocation, unusedValue -> })
            }
            return this.thread
        }

    private fun getModule(): java.lang.Module? {
        if (this.module == null) {
            val predeclared: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.builder<String?, Any?>()
            newModuleHook(predeclared)
            this.module = java.lang.Module.withPredeclared(semantics, predeclared.buildOrThrow())
        }
        return this.module
    }

    @Throws(java.lang.Exception::class)
    fun checkEvalError(msg: String?, vararg input: String?) {
        try {
            exec(*input)
            org.junit.Assert.fail("Expected error '" + msg + "' but got no error")
        } catch (e: net.starlark.java.syntax.SyntaxError.Exception) {
            Truth.assertThat(e).hasMessageThat().isEqualTo(msg)
        } catch (e: EvalException) {
            Truth.assertThat(e).hasMessageThat().isEqualTo(msg)
        }
    }

    /**
     * Verifies that a piece of Starlark code fails at the specified location with either a [ ] or an [EvalException] having the specified error message.
     * 
     * 
     * For a [SyntaxError], the location checked is the first reported error's location. For
     * an [EvalException], the location checked is the location of the innermost stack frame.
     * 
     * @param failingLine 1-based line where the error is expected
     * @param failingColumn 1-based column where the error is expected.
     */
    @Throws(java.lang.Exception::class)
    fun checkEvalErrorAtLocation(
        msg: String?, failingLine: Int, failingColumn: Int, vararg input: String?
    ) {
        try {
            exec(*input)
            org.junit.Assert.fail("Expected error '" + msg + "' but got no error")
        } catch (e: net.starlark.java.syntax.SyntaxError.Exception) {
            Truth.assertThat(e).hasMessageThat().isEqualTo(msg)
            val location: net.starlark.java.syntax.Location = e.errors().get(0).location()
            Truth.assertThat(location.line()).isEqualTo(failingLine)
            Truth.assertThat(location.column()).isEqualTo(failingColumn)
        } catch (e: EvalException) {
            assertThat(e).hasMessageThat().isEqualTo(msg)
            assertThat(e.getCallStack()).isNotEmpty()
            val location: net.starlark.java.syntax.Location =
                com.google.common.collect.Iterables.getLast<T?>(e.getCallStack()).location
            Truth.assertThat(location.line()).isEqualTo(failingLine)
            Truth.assertThat(location.column()).isEqualTo(failingColumn)
        }
    }

    @Throws(java.lang.Exception::class)
    fun checkEvalErrorContains(msg: String?, vararg input: String?) {
        try {
            exec(*input)
            org.junit.Assert.fail("Expected error containing '" + msg + "' but got no error")
        } catch (e: net.starlark.java.syntax.SyntaxError.Exception) {
            Truth.assertThat(e).hasMessageThat().contains(msg)
        } catch (e: EvalException) {
            Truth.assertThat(e).hasMessageThat().contains(msg)
        }
    }

    @Throws(java.lang.Exception::class)
    fun checkEvalErrorDoesNotContain(msg: String?, vararg input: String?) {
        try {
            exec(*input)
        } catch (e: net.starlark.java.syntax.SyntaxError.Exception) {
            Truth.assertThat(e).hasMessageThat().doesNotContain(msg)
        } catch (e: EvalException) {
            Truth.assertThat(e).hasMessageThat().doesNotContain(msg)
        }
    }

    /** Encapsulates a separate test which can be executed by a Scenario.  */
    private interface Testable {
        @Throws(java.lang.Exception::class)
        fun run()
    }

    /**
     * A test scenario (a script of steps). Beware: Scenario is an inner class that mutates its
     * enclosing EvaluationTestCase as it executes the script.
     */
    internal inner class Scenario @kotlin.jvm.JvmOverloads constructor(semantics: StarlarkSemantics? = StarlarkSemantics.DEFAULT) {
        private val setup: SetupActions = net.starlark.java.eval.EvaluationTestCase.SetupActions()
        private val semantics: StarlarkSemantics?

        init {
            this.semantics = semantics
        }

        @Throws(java.lang.Exception::class)
        private fun run(testable: Testable) {
            this@EvaluationTestCase.setSemantics(semantics)
            testable.run()
        }

        /** Allows the execution of several statements before each following test.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setUp(vararg lines: String?): Scenario {
            setup.registerExec(*lines)
            return this
        }

        /**
         * Allows the update of the specified variable before each following test
         * 
         * @param name The name of the variable that should be updated
         * @param value The new value of the variable
         * @return This `Scenario`
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun update(name: String?, value: Any?): Scenario {
            setup.registerUpdate(name, value)
            return this
        }

        /**
         * Evaluates two expressions and asserts that their results are equal.
         * 
         * @param src The source expression to be evaluated
         * @param expectedEvalString The expression of the expected result
         * @return This `Scenario`
         * @throws Exception
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(java.lang.Exception::class)
        fun testEval(src: String?, expectedEvalString: String?): Scenario {
            runTest(createComparisonTestable(src, expectedEvalString, true))
            return this
        }

        /** Evaluates an expression and compares its result to the expected object.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(java.lang.Exception::class)
        fun testExpression(src: String?, expected: Any?): Scenario {
            runTest(createComparisonTestable(src, expected, false))
            return this
        }

        /** Evaluates an expression and compares its result to the ordered list of expected objects.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(java.lang.Exception::class)
        fun testExactOrder(src: String?, vararg items: Any?): Scenario {
            runTest(collectionTestable(src, *items))
            return this
        }

        /** Evaluates an expression and checks whether it fails with the expected error.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(java.lang.Exception::class)
        fun testIfExactError(expectedError: String?, vararg lines: String?): Scenario {
            runTest(errorTestable(true, expectedError, *lines))
            return this
        }

        /**
         * Evaluates an expression and checks whether it fails with the expected error at the expected
         * location.
         * 
         * 
         * See [.checkEvalErrorAtLocation] for how an error's location is determined.
         * 
         * @param failingLine 1-based line where the error is expected.
         * @param failingColumn 1-based column where the error is expected.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(java.lang.Exception::class)
        fun testIfExactErrorAtLocation(
            expectedError: String?, failingLine: Int, failingColumn: Int, vararg lines: String?
        ): Scenario {
            runTest(errorTestableAtLocation(expectedError, failingLine, failingColumn, *lines))
            return this
        }

        /** Evaluates the expresson and checks whether it fails with the expected error.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(java.lang.Exception::class)
        fun testIfErrorContains(expectedError: String?, vararg lines: String?): Scenario {
            runTest(errorTestable(false, expectedError, *lines))
            return this
        }

        /** Looks up the value of the specified variable and compares it to the expected value.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(java.lang.Exception::class)
        fun testLookup(name: String?, expected: Any?): Scenario {
            runTest(createLookUpTestable(name, expected))
            return this
        }

        /**
         * Creates a Testable that checks whether the evaluation of the given expression fails with the
         * expected error.
         * 
         * @param exactMatch whether the error message must be identical to the expected error.
         */
        private fun errorTestable(
            exactMatch: Boolean, error: String?, vararg lines: String?
        ): Testable {
            return object : Testable {
                @Throws(java.lang.Exception::class)
                override fun run() {
                    if (exactMatch) {
                        checkEvalError(error, *lines)
                    } else {
                        checkEvalErrorContains(error, *lines)
                    }
                }
            }
        }

        /**
         * Creates a Testable that checks whether the evaluation of the given expression fails with the
         * expected evaluation error in the expected location.
         * 
         * 
         * See [.checkEvalErrorAtLocation] for how an error's location is determined.
         * 
         * @param failingLine 1-based line where the error is expected.
         * @param failingColumn 1-based column where the error is expected.
         */
        private fun errorTestableAtLocation(
            error: String?, failingLine: Int, failingColumn: Int, vararg lines: String?
        ): Testable {
            return object : Testable {
                @Throws(java.lang.Exception::class)
                override fun run() {
                    checkEvalErrorAtLocation(error, failingLine, failingColumn, *lines)
                }
            }
        }

        /**
         * Creates a Testable that checks whether the value of the expression is a sequence containing
         * the expected elements.
         */
        private fun collectionTestable(src: String?, vararg expected: Any?): Testable {
            return object : Testable {
                @Throws(java.lang.Exception::class)
                override fun run() {
                    Truth.assertThat(eval(src) as Iterable<*>?).containsExactly(*expected).inOrder()
                }
            }
        }

        /**
         * Creates a testable that compares the value of the expression to a specified result.
         * 
         * @param src The expression to be evaluated
         * @param expected Either the expected object or an expression whose evaluation leads to the
         * expected object
         * @param expectedIsExpression Signals whether `expected` is an object or an expression
         * @return An instance of Testable that runs the comparison
         */
        private fun createComparisonTestable(
            src: String?, expected: Any?, expectedIsExpression: Boolean
        ): Testable {
            return object : Testable {
                @Throws(java.lang.Exception::class)
                override fun run() {
                    val actual = eval(src)
                    var realExpected = expected

                    // We could also print the actual object and compare the string to the expected
                    // expression, but then the order of elements would matter.
                    if (expectedIsExpression) {
                        realExpected = eval(expected as String?)
                    }

                    Truth.assertThat(actual).isEqualTo(realExpected)
                }
            }
        }

        /**
         * Creates a Testable that looks up the given variable and compares its value to the expected
         * value
         * 
         * @param name
         * @param expected
         * @return An instance of Testable that does both lookup and comparison
         */
        private fun createLookUpTestable(name: String?, expected: Any?): Testable {
            return object : Testable {
                @Throws(java.lang.Exception::class)
                override fun run() {
                    Truth.assertThat(lookup(name)).isEqualTo(expected)
                }
            }
        }

        /**
         * Executes the given Testable
         * @param testable
         * @throws Exception
         */
        @Throws(java.lang.Exception::class)
        protected fun runTest(testable: Testable) {
            run(net.starlark.java.eval.EvaluationTestCase.TestableDecorator(setup, testable))
        }
    }

    /**
     * A simple decorator that allows the execution of setup actions before running a `Testable`
     */
    internal class TestableDecorator(private val setup: SetupActions, private val decorated: Testable) : Testable {
        /**
         * Executes all stored actions and updates plus the actual `Testable`
         */
        @Throws(java.lang.Exception::class)
        override fun run() {
            setup.executeAll()
            decorated.run()
        }
    }

    /** A container for collection actions that should be executed before a test  */
    private inner class SetupActions {
        private val setup: MutableList<Testable>

        init {
            setup = LinkedList<Testable>()
        }

        /**
         * Registers an update to a module variable to be bound before a test
         * 
         * @param name
         * @param value
         */
        fun registerUpdate(name: String?, value: Any?) {
            setup.add(
                object : Testable {
                    @Throws(java.lang.Exception::class)
                    override fun run() {
                        this@EvaluationTestCase.update(name, value)
                    }
                })
        }

        /** Registers a sequence of statements for execution prior to a test.  */
        fun registerExec(vararg lines: String?) {
            setup.add(
                object : Testable {
                    @Throws(java.lang.Exception::class)
                    override fun run() {
                        this@EvaluationTestCase.exec(*lines)
                    }
                })
        }

        /**
         * Executes all stored actions and updates
         * 
         * @throws Exception
         */
        @Throws(java.lang.Exception::class)
        fun executeAll() {
            for (testable in setup) {
                testable.run()
            }
        }
    }
}
