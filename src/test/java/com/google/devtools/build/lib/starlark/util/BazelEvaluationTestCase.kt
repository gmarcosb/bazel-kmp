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
package com.google.devtools.build.lib.starlark.util

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.analysis.starlark.StarlarkConfig
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.events.EventKind
import com.google.devtools.common.options.Options
import com.google.errorprone.annotations.CanIgnoreReturnValue
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Module
import net.starlark.java.syntax.*
import org.junit.Assert
import java.util.*

/** BazelEvaluationTestCase is a helper class for tests of Bazel loading-phase evaluation.  */ // TODO(adonovan): this helper class might be somewhat handy for testing core Starlark, but its
// widespread use in tests of Bazel features greatly hinders the improvement of Bazel's loading
// phase. The existence of tests based on this class forces Bazel to continue support scenarios in
// which the test creates the environment, the threads, and so on, when these should be
// implementation details of the loading phase. Instead, the lib.packages should present an API in
// which the client provides files, flags, and arguments like a command-line tool, and all our tests
// should be ported to use that API.
class BazelEvaluationTestCase @kotlin.jvm.JvmOverloads constructor(label: String? = DEFAULT_LABEL) {
    private val eventCollectionApparatus: EventCollectionApparatus = EventCollectionApparatus(EventKind.ALL_EVENTS)

    private val label: Label

    private var semantics: StarlarkSemantics? = StarlarkSemantics.DEFAULT
    private var thread: StarlarkThread? = null // created lazily by getStarlarkThread
    private var module: Module? = null // created lazily by getModule

    private var fragmentNameToClass: ImmutableMap<String?, Class<*>?>? = ImmutableMap.of<String?, Class<*>?>()

    private var threadOwner: Any? = "test"

    init {
        this.label = Label.parseCanonicalUnchecked(label)
    }

    /**
     * Parses the semantics flags and updates the semantics used to filter predeclared bindings, and
     * carried by subsequently created threads. Causes a new StarlarkThread and Module to be created
     * when next needed.
     */
    @Throws(OptionsParsingException::class)
    fun setSemantics(vararg options: String?) {
        this.semantics =
            Options.parse(BuildLanguageOptions::class.java, options).options.toStarlarkSemantics()

        // Re-initialize the thread and module with the new semantics when needed.
        this.thread = null
        this.module = null
    }

    val eventHandler: ExtendedEventHandler
        get() = eventCollectionApparatus.reporter()

    /** Updates a global binding in the module.  */ // TODO(adonovan): rename setGlobal.
    @CanIgnoreReturnValue
    @Throws(Exception::class)
    fun update(varname: String?, value: Any?): BazelEvaluationTestCase {
        getModule()!!.setGlobal(varname, value)
        return this
    }

    /** Returns the value of a global binding in the module.  */ // TODO(adonovan): rename getGlobal.
    @Throws(Exception::class)
    fun lookup(varname: String?): Any? {
        return getModule()!!.getGlobal(varname)
    }

    /** Joins the lines, parses them as an expression, and evaluates it.  */
    @Throws(Exception::class)
    fun eval(vararg lines: String?): Any? {
        val input: ParserInput? = ParserInput.fromLines(lines)
        return Starlark.eval(input, FileOptions.DEFAULT, getModule(), this.starlarkThread)
    }

    /** Joins the lines, parses them as a file, and executes it.  */
    @Throws(SyntaxError.Exception::class, EvalException::class, InterruptedException::class)
    fun exec(vararg lines: String?) {
        val input: ParserInput? = ParserInput.fromLines(lines)
        Starlark.execFile(input, FileOptions.DEFAULT, getModule(), this.starlarkThread)
    }

    /**
     * Joins the lines, parses them as a file with the given label, executes it and exports all [ ]s.
     */
    @Throws(Exception::class)
    fun execAndExport(label: Label?, vararg lines: String?) {
        val input: ParserInput? = ParserInput.fromLines(lines)
        val module = getModule()
        val file: StarlarkFile? = StarlarkFile.parse(input)
        val prog: Program? = Program.compileFile(file!!, module)
        BzlLoadFunction.execAndExport(prog, label, this.eventHandler, module, this.starlarkThread)
    }

    /**
     * Joins the lines, parses them as a file, executes it and exports all [ ]s.
     */
    @Throws(Exception::class)
    fun execAndExport(vararg lines: String?) {
        execAndExport(this.label, *lines)
    }

    private fun newThread(thread: StarlarkThread?) {
        // This StarlarkThread has no PackageContext, so attempts to create a rule will fail.
        // Rule creation is tested by StarlarkIntegrationTest.

        // This is a poor approximation to the thread that Blaze would create
        // for testing rule implementation functions. It has phase LOADING, for example.
        // TODO(adonovan): stop creating threads in tests. This is the responsibility of the
        // production code. Tests should provide only files and commands.

        BzlInitThreadContext(
            label,  /* transitiveDigest= */
            ByteArray(0),  // dummy value for tests
            TestConstants.TOOLS_REPOSITORY,  /* networkAllowlistForTests= */
            Optional.empty<T?>(),
            fragmentNameToClass,  /* mainRepoMapping= */
            null
        )
            .storeInThread(thread)
    }

    /**
     * Allows for subclasses to inject custom fragments into the environment.
     * 
     * 
     * Must be called prior to any evaluation calls.
     */
    fun setFragmentNameToClass(fragmentNameToClass: ImmutableMap<String?, Class<*>?>?) {
        Preconditions.checkState(this.thread == null, "Call this method before getStarlarkThread()")
        this.fragmentNameToClass = fragmentNameToClass
    }

    private fun newModule(
        predeclared: ImmutableMap.Builder<String?, Any?>,
        docCommentsMap: ImmutableMap<String?, DocComments?>?,
        unusedDocCommentLines: ImmutableList<Comment?>?
    ): Module? {
        predeclared.putAll(StarlarkGlobalsImpl.INSTANCE.getFixedBzlToplevels())
        predeclared.put("platform_common", PlatformCommon())
        predeclared.put("config_common", ConfigStarlarkCommon())
        predeclared.put("config", StarlarkConfig())
        Starlark.addMethods(predeclared, ConfigGlobalLibrary())

        val clientData: BazelModuleContext? =
            BazelModuleContext.create(
                BazelModuleKey.createFakeModuleKeyForTesting(label),
                RepositoryMapping.EMPTY,
                this.label.toString(),  /* loads= */
                ImmutableList.of<E?>(),  /* bzlTransitiveDigest= */
                ByteArray(0),
                docCommentsMap,
                unusedDocCommentLines
            )
        return Module.withPredeclaredAndData(semantics, predeclared.buildOrThrow(), clientData)
    }

    /** Creates a new Starlark module for testing, and having no doc comments.  */
    fun newModule(): Module? {
        return newModule(
            ImmutableMap.builder<String?, Any?>(),  /* docCommentsMap= */
            ImmutableMap.of<String?, DocComments?>(),  /* unusedDocCommentLines= */
            ImmutableList.of<Comment?>()
        )
    }

    /**
     * Creates a new Starlark module suitable for testing, with doc comments from the given compiled
     * [Program].
     */
    fun newModule(program: Program): Module? {
        return newModule(
            ImmutableMap.builder<String?, Any?>(), program.getDocCommentsMap(), program.getUnusedDocCommentLines()
        )
    }

    /** Sets a thread owner, for cases where the default value of `"test"` doesn't work.  */
    fun setThreadOwner(owner: Any?) {
        this.threadOwner = owner
    }

    val starlarkThread: StarlarkThread
        get() {
            if (this.thread == null) {
                val mu: Mutability? = Mutability.create("test")
                val thread: StarlarkThread =
                    StarlarkThread.create(mu, semantics, "test", SymbolGenerator.create<Any?>(threadOwner))
                thread.printHandler =
                    Event.makeDebugPrintHandler(this.eventHandler)
                newThread(thread)
                this.thread = thread
            }
            return this.thread
        }

    fun getModule(): Module? {
        if (this.module == null) {
            this.module = newModule()
        }
        return this.module
    }

    @Throws(Exception::class)
    fun checkEvalError(msg: String?, vararg input: String?) {
        try {
            exec(*input)
            Assert.fail("Expected error '" + msg + "' but got no error")
        } catch (e: SyntaxError.Exception) {
            Truth.assertThat(e).hasMessageThat().isEqualTo(msg)
        } catch (e: EvalException) {
            Truth.assertThat(e).hasMessageThat().isEqualTo(msg)
        } catch (e: EventCollectionApparatus.FailFastException) {
            Truth.assertThat(e).hasMessageThat().isEqualTo(msg)
        }
    }

    @Throws(Exception::class)
    fun checkEvalErrorContains(msg: String?, vararg input: String?) {
        try {
            exec(*input)
            Assert.fail("Expected error containing '" + msg + "' but got no error")
        } catch (e: SyntaxError.Exception) {
            Truth.assertThat(e).hasMessageThat().contains(msg)
        } catch (e: EvalException) {
            Truth.assertThat(e).hasMessageThat().contains(msg)
        } catch (e: EventCollectionApparatus.FailFastException) {
            Truth.assertThat(e).hasMessageThat().contains(msg)
        }
    }

    // Forward relevant methods to the EventCollectionApparatus
    @CanIgnoreReturnValue
    fun setFailFast(failFast: Boolean): BazelEvaluationTestCase {
        eventCollectionApparatus.setFailFast(failFast)
        return this
    }

    val eventCollector: EventCollector
        get() = eventCollectionApparatus.collector()

    fun assertContainsError(expectedMessage: String?): Event {
        return eventCollectionApparatus.assertContainsError(expectedMessage)
    }

    /** Encapsulates a separate test which can be executed by a Scenario.  */
    protected interface Testable {
        @Throws(Exception::class)
        fun run()
    }

    /**
     * A test scenario (a script of steps). Beware: Scenario is an inner class that mutates its
     * enclosing BazelEvaluationTestCase as it executes the script.
     */
    inner class Scenario(vararg starlarkOptions: String?) {
        private val setup: SetupActions = BazelEvaluationTestCase.SetupActions()
        private val starlarkOptions: Array<String?>

        init {
            this.starlarkOptions = starlarkOptions
        }

        @Throws(Exception::class)
        private fun run(testable: Testable) {
            setSemantics(*starlarkOptions)
            testable.run()
        }

        /** Allows the execution of several statements before each following test.  */
        @CanIgnoreReturnValue
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
        @CanIgnoreReturnValue
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
        @CanIgnoreReturnValue
        @Throws(Exception::class)
        fun testEval(src: String?, expectedEvalString: String?): Scenario {
            runTest(createComparisonTestable(src, expectedEvalString, true))
            return this
        }

        /** Evaluates an expression and compares its result to the expected object.  */
        @CanIgnoreReturnValue
        @Throws(Exception::class)
        fun testExpression(src: String?, expected: Any?): Scenario {
            runTest(createComparisonTestable(src, expected, false))
            return this
        }

        /** Evaluates an expression and checks whether it fails with the expected error.  */
        @CanIgnoreReturnValue
        @Throws(Exception::class)
        fun testIfExactError(expectedError: String?, vararg lines: String?): Scenario {
            runTest(errorTestable(true, expectedError, *lines))
            return this
        }

        /** Evaluates the expression and checks whether it fails with the expected error.  */
        @CanIgnoreReturnValue
        @Throws(Exception::class)
        fun testIfErrorContains(expectedError: String?, vararg lines: String?): Scenario {
            runTest(errorTestable(false, expectedError, *lines))
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
                @Throws(Exception::class)
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
                @Throws(Exception::class)
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

        /** Executes the given Testable  */
        @Throws(Exception::class)
        fun runTest(testable: Testable) {
            run(TestableDecorator(setup, testable))
        }
    }

    /**
     * A simple decorator that allows the execution of setup actions before running a `Testable`
     */
    internal class TestableDecorator(private val setup: SetupActions, private val decorated: Testable) : Testable {
        /** Executes all stored actions and updates plus the actual `Testable`  */
        @Throws(Exception::class)
        override fun run() {
            setup.executeAll()
            decorated.run()
        }
    }

    /** A container for collection actions that should be executed before a test  */
    internal inner class SetupActions {
        private val setup: MutableList<Testable>

        init {
            setup = ArrayList<Testable>()
        }

        /**
         * Registers an update to a module variable to be bound before a test
         * 
         * @param name
         */
        fun registerUpdate(name: String?, value: Any?) {
            setup.add(
                object : Testable {
                    @Throws(Exception::class)
                    override fun run() {
                        this@BazelEvaluationTestCase.update(name, value)
                    }
                })
        }

        /** Registers a sequence of statements for execution prior to a test.  */
        fun registerExec(vararg lines: String?) {
            setup.add(
                object : Testable {
                    @Throws(Exception::class)
                    override fun run() {
                        this@BazelEvaluationTestCase.exec(*lines)
                    }
                })
        }

        /** Executes all stored actions and updates  */
        @Throws(Exception::class)
        fun executeAll() {
            for (testable in setup) {
                testable.run()
            }
        }
    }

    companion object {
        private const val DEFAULT_LABEL = "//test:label"
    }
}
