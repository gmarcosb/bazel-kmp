// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.testutil

import com.google.common.base.Joiner
import com.google.common.collect.*
import com.google.devtools.build.lib.graph.Node
import com.google.devtools.build.lib.packages.Attribute.attr
import com.google.devtools.build.lib.query2.engine.QueryEnvironment
import com.google.devtools.build.lib.query2.engine.QueryException
import com.google.devtools.build.lib.query2.testutil.AbstractQueryTest.Companion.assertPackageLoadingCode
import com.google.devtools.build.lib.testutil.TestUtils
import org.junit.After
import org.junit.Assert
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Tests for the query engine, generic over the result type. This allows us to share the tests
 * between the different implementations, and also parameterize it over the set of options, such as
 * `--keep_going`.
 * 
 * @param <T> the actual target type
</T> */
abstract class AbstractQueryTest<T> {
    protected var mockToolsConfig: MockToolsConfig? = null
    protected var helper: QueryHelper<T?>? = null
    protected var analysisMock: AnalysisMock? = null

    protected fun setRuleClassProviders(vararg mockRules: MockRule?): ConfiguredRuleClassProvider.Builder {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        for (rule in mockRules) {
            builder.addRuleDefinition(rule)
        }
        builder.addConfigurationFragment(DummyTestFragment::class.java)
        return builder
    }

    @Before
    @Throws(Exception::class)
    fun initializeQueryHelper() {
        helper = createQueryHelper()
        helper!!.setUp()
        mockToolsConfig = MockToolsConfig(helper!!.rootDirectory)
        analysisMock = AnalysisMock.get()
        helper!!.setUniverseScope(this.defaultUniverseScope)
        helper!!.useRuleClassProvider(setRuleClassProviders().build())

        analysisMock.setupMockTestingRules(mockToolsConfig)
    }

    @After
    fun cleanUpQueryHelper() {
        helper!!.cleanUp()
    }

    protected abstract fun createQueryHelper(): QueryHelper<T?>

    /**
     * Used to disable configurable attribute queries on DepServerQueryEnvironment, which doesn't
     * support them.
     */
    protected open fun testConfigurableAttributes(): Boolean {
        return true
    }

    protected open val dependencyCorrection: String?
        /** Partial query to filter out implicit dependencies.  */
        get() = ""

    protected val dependencyCorrectionWithGen: String
        /** Partial query to filter out implicit dependencies of genrules.  */
        get() = this.dependencyCorrection + " - deps(" + TestConstants.GENRULE_SETUP + ")"

    @Throws(IOException::class)
    protected fun writeFile(pathName: String?, vararg lines: String?) {
        helper!!.writeFile(pathName, *lines)
    }

    @Throws(IOException::class)
    protected fun overwriteFile(pathName: String?, vararg lines: String?) {
        helper!!.overwriteFile(pathName, *lines)
    }

    @Throws(IOException::class)
    protected fun overwriteFile(pathName: String?, lines: ImmutableList<String?>) {
        helper!!.overwriteFile(pathName, *lines.toTypedArray<String?>())
    }

    protected fun assertContainsEvent(expectedMessage: String?) {
        helper!!.assertContainsEvent(expectedMessage)
    }

    protected fun assertDoesNotContainEvent(notExpectedMessage: String?) {
        helper!!.assertDoesNotContainEvent(notExpectedMessage)
    }

    @Throws(IOException::class)
    protected fun ensureSymbolicLink(link: String?, target: String?) {
        helper!!.ensureSymbolicLink(link, target)
    }

    protected fun assertStartsWith(expected: String?, actual: String) {
        if (!actual.startsWith(expected)) {
            // Call into ChattyAssertsTestCase to get the nice formatting.
            Truth.assertThat(actual).isEqualTo(expected)
        }
    }

    // Evaluate the query, assert that it is successful, and return its results.
    @Throws(Exception::class)
    protected open fun eval(query: String): MutableSet<T?> {
        val result: ResultAndTargets<T?> = helper!!.evaluateQuery(query)
        Truth.assertWithMessage(
            "evaluateQuery failed: %s\n%s", query, Iterables.toString(
                helper!!.events
            )
        )
            .that(result.getQueryEvalResult().success)
            .isTrue()
        return result.getResultSet()
    }

    // Like eval(), but asserts that evaluation completes abruptly with a QueryException, whose
    // message and FailureDetail is returned.
    @Throws(Exception::class)
    protected open fun evalThrows(query: String?, unconditionallyThrows: Boolean): EvalThrowsResult {
        try {
            helper!!.evaluateQuery(query)
            Assert.fail("evaluateQuery completed normally: " + query)
            throw IllegalStateException()
        } catch (e: QueryException) {
            val message = if (e.cause != null) e.cause!!.message else e.message
            return EvalThrowsResult(message, e.getFailureDetail())
        }
    }

    /**
     * Error message and [FailureDetail] from the failing query evaluation performed by [ ][.evalThrows].
     */
    protected class EvalThrowsResult(val message: String?, failureDetail: FailureDetail?) {
        private val failureDetail: FailureDetail?

        init {
            this.failureDetail = failureDetail
        }

        fun getFailureDetail(): FailureDetail? {
            return failureDetail
        }
    }

    // Returns the set as a space-separated list of labels in lex order.
    @Throws(Exception::class)
    protected fun evalToString(query: String): String {
        return Joiner.on(' ').join(evalToListOfStrings(query))
    }

    @Throws(Exception::class)
    protected fun evalToListOfStrings(query: String): ImmutableList<String?> {
        return resultSetToListOfStrings(eval(query))
    }

    protected fun resultSetToListOfStrings(results: MutableSet<T?>): ImmutableList<String?> {
        return results.stream()
            .map<String?> { node: T? -> helper!!.getLabel(node) }
            .sorted(Ordering.natural<String?>())
            .collect(ImmutableList.toImmutableList<String?>())
    }

    @Throws(Exception::class)
    protected fun assertContains(x: MutableSet<T?>, y: MutableSet<T?>?) {
        if (!x.containsAll(y!!)) {
            Assert.fail("x is not a superset of y:\nx = " + x + "\ny = " + y)
        }
    }

    @Throws(Exception::class)
    protected fun assertNotContains(x: MutableSet<T?>, y: MutableSet<T?>?) {
        Truth.assertThat(x.containsAll(y!!)).isFalse()
    }

    @Test
    @Throws(Exception::class)
    open fun testTargetLiteralWithMissingTargets() {
        writeFile("a/BUILD")
        val evalThrowsResult = evalThrows("//a:b", false)
        Truth.assertThat(evalThrowsResult.message)
            .matches(
                TestUtils.createMissingTargetAssertionString(
                    "b", "a", helper!!.rootDirectory.getPathString(), ""
                )
            )
        assertThat(evalThrowsResult.getFailureDetail().getPackageLoading().getCode())
            .isEqualTo(FailureDetails.PackageLoading.Code.TARGET_MISSING)
    }

    @Throws(Exception::class)
    protected fun writeBuildFiles1() {
        // Note, these BUILD files contain no rules, only files, so we use the
        // "a/...:*" wildcard to match them.
        writeFile("a/BUILD", "exports_files(['x', 'y', 'z'])")
        writeFile("a/b/BUILD", "exports_files(['p', 'q'])")
    }

    @Test
    @Throws(Exception::class)
    fun testTargetLiterals() {
        writeBuildFiles1()
        Truth.assertThat(evalToString("a/b:*")).isEqualTo(AB_FILES)
        Truth.assertThat(evalToString("a/...:*")).isEqualTo(A_AB_FILES)
        Truth.assertThat(evalToString("a:*")).isEqualTo(A_FILES)
        Truth.assertThat(evalToString("//a:x")).isEqualTo("//a:x")
    }

    @Test
    @Throws(Exception::class)
    open fun testBadTargetLiterals() {
        val result = evalThrows("bad:*:*", false)
        checkResultofBadTargetLiterals(result.message, result.getFailureDetail())
    }

    protected fun checkResultofBadTargetLiterals(message: String?, failureDetail: FailureDetail) {
        assertThat(failureDetail.getTargetPatterns().getCode())
            .isEqualTo(TargetPatterns.Code.LABEL_SYNTAX_ERROR)
        Truth.assertThat(message).isEqualTo("invalid target name '*:*': target names may not contain ':'")
    }

    @Test
    @Throws(Exception::class)
    fun testAlgebraicSetOperations() {
        writeBuildFiles1()
        Truth.assertThat(evalToString("a/...:* intersect a/b/...:*")).isEqualTo(AB_FILES)
        Truth.assertThat(evalToString("a/b/...:* intersect a/...:*")).isEqualTo(AB_FILES)
        Truth.assertThat(evalToString("//a:x union a/b/...:*")).isEqualTo(AB_FILES + " //a:x")
        Truth.assertThat(evalToString("a/b/...:* union //a:x")).isEqualTo(AB_FILES + " //a:x")
        Truth.assertThat(evalToString("a/...:* except a/b/...:*")).isEqualTo(A_FILES)
        Truth.assertThat(evalToString("a/b/...:* except a/...:*")).isEmpty()

        Truth.assertThat(evalToString("(a/...:* union a/b/...:*) except //a/b:p"))
            .isEqualTo("//a/b:BUILD //a/b:q " + A_FILES)
        Truth.assertThat(evalToString("a/...:* union (a/b/...:* except //a/b:p)")).isEqualTo(A_AB_FILES)

        // Test - + ^ variants:
        Truth.assertThat(evalToString("a/...:* + (a/b/...:* - //a/b:p)")).isEqualTo(A_AB_FILES)
        Truth.assertThat(evalToString("a/...:* ^ a/b/...:*")).isEqualTo(AB_FILES)
    }

    @Test
    @Throws(Exception::class)
    fun testAlgebraicSetOperations_manyOperands() {
        writeBuildFiles1()
        Truth.assertThat(evalToString("//a:BUILD + //a:x + //a:y + //a:z + //a/b:BUILD + //a/b:p + //a/b:q"))
            .isEqualTo(A_AB_FILES)
        Truth.assertThat(
            evalToString(
                "a/...:* - //a:BUILD - //a:x - //a:y - //a:z - //a/b:BUILD - //a/b:p - //a/b:q"
            )
        )
            .isEmpty()
        Truth.assertThat(
            evalToString(
                "(//a:x + //a:y) ^ (//a:x + //a:z) ^ (//a:x + //a/b:p) ^ (//a:x + //a/b:q)"
            )
        )
            .isEqualTo("//a:x")
    }

    @Throws(Exception::class)
    private fun writeBuildFiles2() {
        writeFile(
            "c/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_test.bzl", "cc_test")

        genrule(name='c', srcs=['p', 'q'], outs=['r', 's'], cmd=':')
        cc_binary(name='d', srcs=['e.cc'], data=['r'])
        cc_test(name='f', srcs=['g.cc'])
        
        """.trimIndent()
        )
    }

    @Test
    @Throws(Exception::class)
    fun testKindOperator() {
        writeBuildFiles2()
        Truth.assertThat(evalToString("c:*"))
            .isEqualTo(
                "//c:BUILD //c:c //c:d //c:d.dwp //c:d.stripped //c:e.cc //c:f //c:f.dwp //c:f.stripped"
                        + " //c:g.cc //c:p //c:q //c:r //c:s"
            )
        Truth.assertThat(evalToString("kind(rule, c:*)")).isEqualTo("//c:c //c:d //c:f")
        Truth.assertThat(evalToString("kind(genrule, c:*)")).isEqualTo("//c:c")
        Truth.assertThat(evalToString("kind(cc.*, c:*)")).isEqualTo("//c:d //c:f")
        Truth.assertThat(evalToString("kind(file, c:*)"))
            .isEqualTo(
                "//c:BUILD //c:d.dwp //c:d.stripped //c:e.cc //c:f.dwp //c:f.stripped //c:g.cc //c:p"
                        + " //c:q //c:r //c:s"
            )
        Truth.assertThat(evalToString("kind(gener.*, c:*)"))
            .isEqualTo("//c:d.dwp //c:d.stripped //c:f.dwp //c:f.stripped //c:r //c:s")
        Truth.assertThat(evalToString("kind(gen.*, c:*)"))
            .isEqualTo("//c:c //c:d.dwp //c:d.stripped //c:f.dwp //c:f.stripped //c:r //c:s")
        Truth.assertThat(evalToString("kind(source, c:*)"))
            .isEqualTo("//c:BUILD //c:e.cc //c:g.cc //c:p //c:q")
        Truth.assertThat(evalToString("kind('source file', c:*)"))
            .isEqualTo("//c:BUILD //c:e.cc //c:g.cc //c:p //c:q")
    }

    @Test
    @Throws(Exception::class)
    fun testFilterOperator() {
        writeBuildFiles2()
        Truth.assertThat(evalToString("c:*"))
            .isEqualTo(
                "//c:BUILD //c:c //c:d //c:d.dwp //c:d.stripped //c:e.cc //c:f //c:f.dwp //c:f.stripped"
                        + " //c:g.cc //c:p //c:q //c:r //c:s"
            )
        Truth.assertThat(evalToString("filter(BUILD, c:*)")).isEqualTo("//c:BUILD")
        Truth.assertThat(evalToString("filter('\\.cc$', c:*)")).isEqualTo("//c:e.cc //c:g.cc")
        Truth.assertThat(evalToString("filter(//c.*cc$, c:*)")).isEqualTo("//c:e.cc //c:g.cc")
        Truth.assertThat(evalToString("filter(:.$, c:*)"))
            .isEqualTo("//c:c //c:d //c:f //c:p //c:q //c:r //c:s")
    }

    @Test
    @Throws(Exception::class)
    fun testAttrOperatorOnName() {
        writeBuildFiles2()
        writeBuildFilesWithConfigurableAttributes()

        Truth.assertThat(evalToString("attr(name, '.*', '//c:*')")).isEqualTo("//c:c //c:d //c:f")
        Truth.assertThat(evalToString("attr(name, '.+', '//c:*')")).isEqualTo("//c:c //c:d //c:f")
        Truth.assertThat(evalToString("attr(name, '.*d.*', '//c:*')")).isEqualTo("//c:d")

        Truth.assertThat(evalToString("attr(name, '.*e.*', '//c:*')")).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun testAttrOperator() {
        writeBuildFiles2()
        writeBuildFilesWithConfigurableAttributes()

        Truth.assertThat(evalToString("c:*"))
            .isEqualTo(
                "//c:BUILD //c:c //c:d //c:d.dwp //c:d.stripped //c:e.cc //c:f //c:f.dwp //c:f.stripped"
                        + " //c:g.cc //c:p //c:q //c:r //c:s"
            )
        Truth.assertThat(evalToString("attr(cmd,':', c:*)")).isEqualTo("//c:c")
        // Using "empty" pattern will just check existence of the attribute.
        Truth.assertThat(evalToString("attr(cmd,'', c:*)")).isEqualTo("//c:c")
        Truth.assertThat(evalToString("attr(linkshared, 0, c:*)")).isEqualTo("//c:d //c:f")
        Truth.assertThat(evalToString("attr('data', 'r', c:*)")).isEqualTo("//c:d")
        // Empty list attribute value always resolves to '[]'. If list attribute has
        // more than one value, the will be delimited with ','.
        Truth.assertThat(evalToString("attr('deps', '\\[\\]', c:*)")).isEqualTo("//c:d //c:f")
        Truth.assertThat(evalToString("attr('deps', '^..$', c:*)")).isEqualTo("//c:d //c:f")
        Truth.assertThat(evalToString("attr('srcs', '\\[[^,]+\\]', c:*)")).isEqualTo("//c:d //c:f")

        // Configurable attributes:
        if (testConfigurableAttributes()) {
            Truth.assertThat(evalToString("attr('deps', 'bdep', //configurable/...)"))
                .isEqualTo("//configurable:main")
            Truth.assertThat(evalToString("attr('deps', 'nomatch', //configurable/...)")).isEmpty()
        }
    }

    /** Regression test for b/16835016: don't crash when evaluating null-valued attributes.  */
    @Test
    @Throws(Exception::class)
    fun testNullAttrOperator() {
        writeBuildFiles2()
        Truth.assertThat(evalToString("attr(deprecation, ' ', c:*)")).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun testAttrOperatorOnBooleans() {
        writeFile(
            "t/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(name='t', srcs=['t.cc'], data=['r'], testonly=0)
        cc_library(name='t_test', srcs=['t.cc'], data=['r'], testonly=1)
        
        """.trimIndent()
        )

        // Assure that integers query correctly for BOOLEAN values.
        Truth.assertThat(evalToString("attr(testonly, 0, t:*)")).isEqualTo("//t:t")
        Truth.assertThat(evalToString("attr(testonly, 1, t:*)")).isEqualTo("//t:t_test")
    }

    @Throws(Exception::class)
    protected fun runGenqueryScopeTest(isPostAnalysisQuery: Boolean) {
        // Tests the relationship between deps(genquery_rule) and that of its scope.
        // For query, deps(genquery_rule) should include transitive deps of its scope
        // For cquery and aquery, deps(genquery_rule) should include its scope, but not its transitive
        // deps.

        writeFile(
            "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name='a')"
        )
        writeFile(
            "b/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name='b', deps=['//a:a'])"
        )
        writeFile("q/BUILD", "genquery(name='q', scope=['//b'], expression='deps(//b)')")

        // Assure that deps of a genquery rule includes the transitive closure of its scope.
        // This is required for correctness of incremental "blaze build genqueryrule"
        val evalResult = evalToListOfStrings("deps(//q:q)")
        if (isPostAnalysisQuery) {
            // Not checking for equality, since when run as a cquery test, there will be other
            // dependencies.
            Truth.assertThat(evalResult).contains("//q:q")
            // assert that transitive closure of scope is NOT present.
            Truth.assertThat(evalResult).containsNoneOf("//a:a", "//b:b")
        } else {
            Truth.assertThat(evalResult).containsExactly("//q:q", "//a:a", "//b:b")
        }
    }

    @Test
    @Throws(Exception::class)
    open fun testGenqueryScope() {
        runGenqueryScopeTest(false)
    }

    @Test
    @Throws(Exception::class)
    fun testAttrOnPackageDefaultVisibility() {
        writeFile(
            "t/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        package(default_visibility=['//visibility:public'])
        cc_library(name='t', srcs=['t.cc'])
        
        """.trimIndent()
        )

        Truth.assertThat(evalToString("attr(visibility, public, t:*)")).isEqualTo("//t:t")
    }

    @Test
    @Throws(Exception::class)
    fun testSomeOperator_noCountParameter() {
        writeBuildFiles2()
        Truth.assertThat(eval("some(c:*)")).hasSize(1)
        assertContains(eval("c:*"), eval("some(c:*)"))
        Truth.assertThat(evalToString("some(//c:q)")).isEqualTo("//c:q")

        val result = evalThrows("some(//c:q intersect //c:p)", true)
        Truth.assertThat(result.message).isEqualTo("argument set is empty")
        assertQueryCode(result.getFailureDetail(), Query.Code.ARGUMENTS_MISSING)
    }

    @Test
    @Throws(Exception::class)
    fun testSomeOperator_countParameterNotEqualActualCount() {
        writeBuildFiles2()
        Truth.assertThat(eval("some(//c:p + //c:q, 5)")).hasSize(2)
        Truth.assertThat(evalToString("some(//c:p + //c:q, 5)")).isEqualTo("//c:p //c:q")

        Truth.assertThat(eval("some(//c:c + //c:d + //c:p + //c:q + //c:r + //c:s, 3)")).hasSize(3)
        // No need to check `evalToString`, the output strings may differ based test suite setup.
    }

    @Test
    @Throws(Exception::class)
    fun testSomeOperator_nestedSomeTest() {
        writeBuildFiles2()
        Truth.assertThat(eval("some(some(//c:p + //c:q, 2) + some(//c:p + //c:s + //c:q, 3), 5)")).hasSize(3)
        Truth.assertThat(evalToString("some(some(//c:p + //c:q, 2) + some(//c:p + //c:s + //c:q, 3), 5)"))
            .isEqualTo("//c:p //c:q //c:s")
    }

    @Throws(Exception::class)
    protected open fun writeBuildFiles3() {
        writeFile(
            "a/BUILD",
            """
        genrule(name='a', srcs=['//b', '//c'], outs=['out'], cmd=':')
        exports_files(['a2'])
        
        """.trimIndent()
        )
        writeFile("b/BUILD", "genrule(name='b', srcs=['//d'], outs=['out'], cmd=':')")
        writeFile("c/BUILD", "genrule(name='c', srcs=['//d'], outs=['out'], cmd=':')")
        writeFile("d/BUILD", "exports_files(['d'])")
    }

    /**
     * Setup a BUILD file that loads two .scl files, one directly and the other through a .bzl file.
     */
    @Throws(Exception::class)
    protected fun writeBzlAndSclFiles() {
        writeFile(
            "foo/BUILD",
            """
        load('//bar:direct.scl', 'x')
        load('//bar:intermediate.bzl', 'y')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = 'foo',
            tags = [x, y],
        )
        
        """.trimIndent()
        )
        writeFile("bar/BUILD")
        writeFile(
            "bar/direct.scl",  //
            "x = 'X'"
        )
        writeFile(
            "bar/intermediate.bzl",
            """
        load(':indirect.scl', _y='y')
        y = _y
        
        """.trimIndent()
        )
        writeFile(
            "bar/indirect.scl",  //
            "y = 'Y'"
        )
    }

    @Throws(Exception::class)
    protected fun writeBuildFilesWithConfigurableAttributesUnconditionally() {
        writeFile(
            "conditions/BUILD",
            """
        config_setting(
            name = 'a',
            values = {'foo': 'a'})
        config_setting(
            name = 'b',
            values = {'foo': 'b'})
        
        """.trimIndent()
        )
        writeFile(
            "configurable/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "",
            "cc_binary(",
            "    name = 'main',",
            "    srcs = ['main.cc'],",
            "    deps = select({",
            "        '//conditions:a': [':adep'],",
            "        '//conditions:b': [':bdep'],",
            "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "': [':defaultdep'],",
            "    }))",
            "cc_library(",
            "    name = 'adep',",
            "    srcs = ['adep.cc'])",
            "cc_library(",
            "    name = 'bdep',",
            "    srcs = ['bdep.cc'])",
            "cc_library(",
            "    name = 'defaultdep',",
            "    srcs = ['defaultdep.cc'])"
        )
    }

    @Throws(Exception::class)
    private fun writeBuildFilesWithConfigurableAttributes() {
        if (testConfigurableAttributes()) {
            writeBuildFilesWithConfigurableAttributesUnconditionally()
        }
    }

    @Test
    @Throws(Exception::class)
    fun testSomePathOperator() {
        writeBuildFiles3()
        writeBuildFilesWithConfigurableAttributes()

        Truth.assertThat(eval("somepath(//a, //a:a2)")).isEqualTo(EMPTY) // no path
        Truth.assertThat(eval("somepath(//d, //a)")).isEqualTo(EMPTY) // no path

        val somepathAToD = eval("somepath(//a, //d)")
        Truth.assertThat(somepathAToD).containsAtLeastElementsIn(eval("//a"))
        val aAndB = eval("//a + //b")
        // Contains one of {//b, //c}:
        Truth.assertThat(somepathAToD).containsAnyIn(aAndB)
        assertContains(somepathAToD, eval("//d"))

        // Configurable attributes:
        if (testConfigurableAttributes()) {
            Truth.assertThat(eval("somepath(//configurable:main, //configurable:bdep.cc)"))
                .isEqualTo(eval("//configurable:main + //configurable:bdep + //configurable:bdep.cc"))
        }
    }

    @Test
    @Throws(Exception::class)
    fun testSomePathOperatorOrdering() {
        writeFile(
            "a/BUILD",
            """
        genrule(name='a1', srcs=['//b', '//c'], outs=['out1'], cmd=':')
        genrule(name='a0', srcs=[':a1'], outs=['out0'], cmd=':')
        
        """.trimIndent()
        )
        writeFile("b/BUILD", "genrule(name='b', srcs=['//d'], outs=['out'], cmd=':')")
        writeFile("c/BUILD", "genrule(name='c', srcs=['//d'], outs=['out'], cmd=':')")
        writeFile("d/BUILD", "exports_files(['d'])")

        val pathList1 = ImmutableList.of<String?>("//a:a0", "//a:a1", "//b:b", "//d:d")
        val pathList2 = ImmutableList.of<String?>("//a:a0", "//a:a1", "//c:c", "//d:d")

        val somepathAToD = evalToListOfStrings("somepath(//a:a0, //d)")
        if (somepathAToD.contains("//b:b")) {
            Truth.assertThat(pathList1).isEqualTo(somepathAToD)
        } else {
            Truth.assertThat(somepathAToD).isEqualTo(pathList2)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testAllPathsOperator() {
        writeBuildFiles3()
        writeBuildFilesWithConfigurableAttributes()

        Truth.assertThat(eval("somepath(//a, //a:a2)")).isEqualTo(EMPTY) // no path
        Truth.assertThat(eval("somepath(//d, //a)")).isEqualTo(EMPTY) // no path

        val allpathsAtoD = eval("allpaths(//a, //d)")
        Truth.assertThat(allpathsAtoD).containsAtLeastElementsIn(eval("//a + //b + //c + //d"))

        // Configurable attributes:
        if (testConfigurableAttributes()) {
            Truth.assertThat(eval("allpaths(//configurable:main, //configurable:bdep.cc)"))
                .isEqualTo(eval("//configurable:main + //configurable:bdep + //configurable:bdep.cc"))
        }
    }

    @Test
    @Throws(Exception::class)
    fun testPathOperatorsWithOutputFile() {
        writeFile("a/BUILD", "genrule(name='a', outs=['out'], cmd=':')")

        Truth.assertThat(eval("somepath(//a, //a:out)")).isEqualTo(EMPTY) // no path
        Truth.assertThat(eval("allpaths(//a, //a:out)")).isEqualTo(EMPTY) // no path

        Truth.assertThat(eval("somepath(//a:out, //a)")).isEqualTo(eval("//a + //a:out"))
        Truth.assertThat(eval("allpaths(//a:out, //a)")).isEqualTo(eval("//a + //a:out"))
    }

    @Test
    @Throws(Exception::class)
    fun testDeps() {
        writeBuildFiles3()
        writeBuildFilesWithConfigurableAttributes()

        Truth.assertThat(eval("deps(//d)")).isEqualTo(eval("//d"))
        Truth.assertThat(eval("deps(//c)" + this.dependencyCorrectionWithGen))
            .isEqualTo(eval("//c union //d"))
        Truth.assertThat(eval("deps(//b)" + this.dependencyCorrectionWithGen))
            .isEqualTo(eval("//b union //d"))
        Truth.assertThat(eval("deps(//a)" + this.dependencyCorrectionWithGen))
            .isEqualTo(eval("//a union //b union //c union //d"))

        Truth.assertThat(eval("deps(//c:out)")).isEqualTo(eval("deps(//c) union //c:out"))
        Truth.assertThat(eval("deps(//b:out)")).isEqualTo(eval("deps(//b) union //b:out"))
        Truth.assertThat(eval("deps(//a:out)")).isEqualTo(eval("deps(//a) union //a:out"))

        // Test depth-bounded variant:
        Truth.assertThat(eval("deps(//a, 0)" + this.dependencyCorrectionWithGen)).isEqualTo(eval("//a"))
        Truth.assertThat(eval("deps(//a, 1)" + this.dependencyCorrectionWithGen))
            .isEqualTo(eval("//a union //b union //c"))
        Truth.assertThat(eval("deps(//a, 2)" + this.dependencyCorrectionWithGen))
            .isEqualTo(eval("//a + //b + //c + //d"))

        // Regression coverage for bug #1561800:
        // "blaze query 'deps(<output file>, 1)' returns the output file,
        // not its generating rule"
        Truth.assertThat(eval("deps(//a:out, 0)")).isEqualTo(eval("//a:out"))
        Truth.assertThat(eval("deps(//a:out, 1)" + this.dependencyCorrectionWithGen))
            .isEqualTo(eval("//a:out + //a"))
        Truth.assertThat(eval("deps(//a:out, 2)" + this.dependencyCorrectionWithGen))
            .isEqualTo(eval("//a:out + //a + //b + //c"))

        // Configurable attributes:
        if (testConfigurableAttributes()) {
            var implicitDeps = ""
            if (analysisMock.isThisBazel) {
                implicitDeps = " + " + helper!!.toolsRepository + "//tools/def_parser:def_parser"
            }
            var expectedDependencies =
                (helper!!.toolsRepository
                    .toString() + "//tools/cpp:link_extra_lib + "
                        + helper!!.toolsRepository
                        + "//tools/cpp:malloc + //configurable:main + "
                        + "//configurable:main.cc + //configurable:adep + //configurable:bdep + "
                        + "//configurable:defaultdep + //conditions:a + //conditions:b "
                        + implicitDeps)
            if (includeCppToolchainDependencies()) {
                expectedDependencies += " + //tools/cpp:toolchain_type + //tools/cpp:current_cc_toolchain"
            }
            Truth.assertThat(eval("deps(//configurable:main, 1)" + TestConstants.CC_DEPENDENCY_CORRECTION))
                .containsExactlyElementsIn(eval(expectedDependencies))
        }
    }

    protected open fun includeCppToolchainDependencies(): Boolean {
        return true
    }

    @Test
    @Throws(Exception::class)
    fun testDepsDoesNotIncludeBuildFiles() {
        writeFile("deps/BUILD", "exports_files(['build_def', 'starlark.bzl'])")
        writeFile(
            "deps/starlark.bzl",
            """
        def macro():
          native.genrule(name = 'dep2', outs = ['dep2.txt'], cmd = 'echo Hi >${'$'}@')
        
        """.trimIndent()
        )

        writeFile(
            "s/BUILD",
            """
        load('//deps:starlark.bzl', 'macro')
        macro()
        genrule(name = 'my_rule',
                outs = ['my.txt'],
                srcs = [':dep1.txt', ':dep2.txt'],
                cmd = 'echo ${'$'}(SRCS) >${'$'}@')
        
        """.trimIndent()
        )

        val result = evalToListOfStrings("deps(//s:my_rule)")
        Truth.assertThat(result).containsAtLeast("//s:dep2", "//s:dep1.txt", "//s:dep2.txt", "//s:my_rule")
        Truth.assertThat(result)
            .containsNoneOf("//deps:BUILD", "//deps:build_def", "//deps:starlark.bzl", "//s:BUILD")
    }

    @Throws(Exception::class)
    protected fun writeAspectDefinition(aspectAttrs: String?) {
        helper!!.setQuerySettings(QueryEnvironment.Setting.INCLUDE_ASPECTS)
        writeFile(
            "test/aspect.bzl",
            "def _aspect_impl(target, ctx):",
            "   return []",
            "def _rule_impl(ctx):",
            "   return []",
            "",
            "MyAspect = aspect(",
            "   implementation=_aspect_impl,",
            "   attr_aspects=['deps'],",
            "   attrs = ",
            aspectAttrs,
            ")",
            "aspect_rule = rule(",
            "   implementation=_rule_impl,",
            "   attrs = { 'attr' : ",
            "             attr.label_list(mandatory=True, allow_files=True, aspects = [MyAspect]),",
            "             'param' : attr.string(),",
            "           },",
            ")",
            "plain_rule = rule(",
            "   implementation=_rule_impl,",
            "   attrs = { 'attr' : ",
            "             attr.label_list(mandatory=False, allow_files=True) ",
            "           },",
            ")"
        )
        writeFile(
            "prod/BUILD",
            """
        load('//test:aspect.bzl', 'plain_rule')
        plain_rule(
             name = 'zzz'
        )
        
        """.trimIndent()
        )
    }

    @Test
    @Throws(Exception::class)
    fun testAspectOnRuleWithoutDeclaredProviders() {
        helper!!.setQuerySettings(QueryEnvironment.Setting.INCLUDE_ASPECTS)
        writeAspectDefinition("{'_extra_deps' : attr.label(default = Label('//test:z'))}")
        writeFile(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'aspect_rule', 'plain_rule')
        aspect_rule(name='a', attr=[':b'])
        plain_rule(name='b')
        plain_rule(name='z')
        
        """.trimIndent()
        )

        Truth.assertThat(eval("deps(//test:a)")).containsAtLeastElementsIn(eval("//test:b + //test:z"))
    }

    @Test
    @Throws(Exception::class)
    fun testQueryStarlarkAspects() {
        helper!!.setQuerySettings(QueryEnvironment.Setting.INCLUDE_ASPECTS)
        writeAspectDefinition("{'_extra_deps' : attr.label(default = Label('//prod:zzz'))}")
        writeFile(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'aspect_rule', 'plain_rule')
        plain_rule(
             name = 'yyy',
        )
        aspect_rule(
             name = 'xxx',
             attr = [':yyy'],
        )
        aspect_rule(
             name = 'qqq',
             attr = ['//test:yyy'],
        )
        
        """.trimIndent()
        )

        Truth.assertThat(eval("deps(//test:xxx)")).containsAtLeastElementsIn(eval("//prod:zzz + //test:yyy"))
        Truth.assertThat(eval("deps(//test:qqq)")).containsAtLeastElementsIn(eval("//prod:zzz + //test:yyy"))
    }

    @Test
    @Throws(Exception::class)
    fun testQueryStarlarkAspectWithParameters() {
        helper!!.setQuerySettings(QueryEnvironment.Setting.INCLUDE_ASPECTS)
        writeAspectDefinition(
            "{'_extra_deps' : attr.label(default = Label('//prod:zzz')),"
                    + "'param' : attr.string(values=['a', 'b']) }"
        )
        writeFile(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'aspect_rule', 'plain_rule')
        plain_rule(
             name = 'yyy',
        )
        aspect_rule(
             name = 'xxx',
             attr = [':yyy'],
             param = 'a',
        )
        aspect_rule(
             name = 'qqq',
             attr = ['//test:yyy'],
             param = 'b',
        )
        
        """.trimIndent()
        )

        Truth.assertThat(eval("deps(//test:xxx)")).containsAtLeastElementsIn(eval("//prod:zzz + //test:yyy"))
        Truth.assertThat(eval("deps(//test:qqq)")).containsAtLeastElementsIn(eval("//prod:zzz + //test:yyy"))
    }

    @Test
    @Throws(Exception::class)
    fun testQueryStarlarkAspectsNoImplicitDeps() {
        helper!!.setQuerySettings(QueryEnvironment.Setting.INCLUDE_ASPECTS)
        writeAspectDefinition("{'_extra_deps':attr.label(default = Label('//prod:zzz'))}")
        writeFile(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'aspect_rule', 'plain_rule')
        plain_rule(
             name = 'yyy',
        )
        aspect_rule(
             name = 'xxx',
             attr = [':yyy'],
        )
        
        """.trimIndent()
        )
        helper!!.setQuerySettings(QueryEnvironment.Setting.NO_IMPLICIT_DEPS)

        Truth.assertThat(eval("deps(//test:xxx)")).containsNoneIn(eval("//prod:zzz"))
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkDiamondEquality() {
        writeFile(
            "foo/BUILD",
            """
        load('//foo:a.bzl', 'A')
        load('//foo:b.bzl', 'B')
        load('//foo:checker.bzl', 'check')
        load('//test_defs:foo_library.bzl', 'foo_library')
        check(A.c, B.c)
        check(B.a, A)
        foo_library(name = 'foo')
        
        """.trimIndent()
        )
        writeFile(
            "foo/a.bzl",
            """
        load('//foo:c.bzl', 'C')
        A = struct(c = C)
        # comment to make sure this formats properly
        
        """.trimIndent()
        )
        writeFile(
            "foo/b.bzl",
            """
        load('//foo:a.bzl', 'A')
        load('//foo:c.bzl', 'C')
        B = struct(a = A, c = C)
        
        """.trimIndent()
        )
        writeFile("foo/c.bzl", "C = struct()")
        writeFile(
            "foo/checker.bzl",
            """
        def check(arg1, arg2):
          if arg1 != arg2:
            fail('Long error message just saying that the two args passed in were not equal')
        
        """.trimIndent()
        )
        // Check no errors.
        Truth.assertThat(evalToString("//foo:foo")).isEqualTo("//foo:foo")
    }

    @Test
    @Throws(Exception::class)
    fun testRdeps() {
        writeBuildFiles3()
        writeBuildFilesWithConfigurableAttributes()

        Truth.assertThat(eval("rdeps(//a, //d)" + this.dependencyCorrection))
            .isEqualTo(eval("//a union //b union //c union //d"))
        Truth.assertThat(eval("rdeps(//b, //d)" + this.dependencyCorrection))
            .isEqualTo(eval("//b union //d"))
        Truth.assertThat(eval("rdeps(//b union //c, //d)" + this.dependencyCorrection))
            .isEqualTo(eval("//b union //c union //d"))
        Truth.assertThat(eval("rdeps(//a union //c, //b)" + this.dependencyCorrection))
            .isEqualTo(eval("//a union //b"))
        Truth.assertThat(eval("rdeps(//a:out union //c:out, //b)" + this.dependencyCorrection))
            .isEqualTo(eval("//a union //a:out union //b"))
        Truth.assertThat(eval("rdeps(//d, //a)" + this.dependencyCorrection)).isEqualTo(EMPTY)

        // Test depth-bounded variant:
        Truth.assertThat(eval("rdeps(//a, //d, 1)" + this.dependencyCorrection))
            .isEqualTo(eval("//b union //c union //d"))
        Truth.assertThat(eval("rdeps(//a, //d, 0)" + this.dependencyCorrection)).isEqualTo(eval("//d"))

        // Configurable attributes:
        if (testConfigurableAttributes()) {
            Truth.assertThat(eval("rdeps(//configurable:all, //configurable:adep.cc)"))
                .isEqualTo(eval("//configurable:main + //configurable:adep + //configurable:adep.cc"))
            Truth.assertThat(eval("rdeps(//configurable:all, //configurable:bdep.cc)"))
                .isEqualTo(eval("//configurable:main + //configurable:bdep + //configurable:bdep.cc"))
            Truth.assertThat(eval("rdeps(//configurable:all, //configurable:defaultdep.cc)"))
                .isEqualTo(
                    eval(
                        "//configurable:main + //configurable:defaultdep + "
                                + "//configurable:defaultdep.cc"
                    )
                )
        }
    }

    @Test
    @Throws(Exception::class)
    open fun testLet() {
        writeBuildFiles3()

        helper!!.setQuerySettings(QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        assertContains(
            eval("//b + //c + //d"),
            eval("let x = //a in deps(\$x) except \$x" + this.dependencyCorrectionWithGen)
        )
        val result = evalThrows("\$undefined", true)
        Truth.assertThat(result.message).isEqualTo("undefined variable 'undefined'")
        assertQueryCode(result.getFailureDetail(), Query.Code.VARIABLE_UNDEFINED)
    }

    @Test
    @Throws(Exception::class)
    fun testScopeOfLetExpressions() {
        val numTargets = 1000

        val filesBuilder = StringBuilder("'0'")
        for (i in 1..<numTargets) {
            filesBuilder.append(String.format(", '%d'", i))
        }
        val files = filesBuilder.toString()
        writeFile("a/BUILD", "exports_files([" + files + "])")

        val letQueryBuilder = StringBuilder("(let x = //a:0 in \$x)")
        for (i in 1..<numTargets) {
            letQueryBuilder.append(String.format(" + (let x = //a:%d in \$x)", i))
        }
        val letQuery = letQueryBuilder.toString()

        Truth.assertThat(eval(letQuery)).containsExactlyElementsIn(eval("//a:* - //a:BUILD"))
    }

    @Test
    @Throws(Exception::class)
    fun testSubdirSymlinkCycle() {
        writeBuildFiles1()
        helper!!.ensureSymbolicLink("a/s", "s")
        Truth.assertThat(evalToString("a/...:*")).isEqualTo(A_AB_FILES)
    }

    @Test
    @Throws(Exception::class)
    open fun testCycleInSubpackage() {
        writeFile(
            "a/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', deps = [':dep'])
        foo_library(name = 'dep')
        
        """.trimIndent()
        )
        writeFile(
            "a/subdir/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'cycletarget', deps = ['cycletarget'])"
        )
        Truth.assertThat(evalToListOfStrings("deps(//a:a)")).containsExactly("//a:a", "//a:dep")
    }

    @Throws(Exception::class)
    protected fun setupCycleInStarlarkParentDir() {
        writeFile(
            "a/BUILD",
            """
        load('//a:cycle1.bzl', 'C1')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a')
        
        """.trimIndent()
        )
        writeFile(
            "a/cycle1.bzl",
            """
        load('//a:cycle2.bzl', 'C2')
        C1 = struct()
        
        """.trimIndent()
        )
        writeFile(
            "a/cycle2.bzl",
            """
        load('//a:cycle1.bzl', 'C1')
        C2 = struct()
        
        """.trimIndent()
        )
        writeFile(
            "a/subdir/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'subdir')"
        )
    }

    @Test
    @Throws(Exception::class)
    open fun testCycleInStarlarkParentDir() {
        setupCycleInStarlarkParentDir()
        Truth.assertThat(evalToListOfStrings("//a/subdir:all")).containsExactly("//a/subdir:subdir")
    }

    @Test
    @Throws(Exception::class)
    fun testNestedLetExpressions() {
        writeFile("a/BUILD", "exports_files(['f1', 'f2'])")
        writeFile("b/BUILD", "exports_files(['f1', 'f2'])")
        val letQuery =
            ("let x1 = //a:f1 in "
                    + "let x2 = //a:f2 in "
                    + "let x1 = //b:f1 in "
                    + "let x2 = //b:f2 in "
                    + "\$x1 + \$x2")
        Truth.assertThat(eval(letQuery)).containsExactlyElementsIn(eval("//b:f1 + //b:f2"))
    }

    @Test
    @Throws(Exception::class)
    open fun testBuildFiles() {
        writeBuildFiles3()
        Truth.assertThat(eval("//a ^ //b")).isEqualTo(EMPTY)
        Truth.assertThat(eval("buildfiles(//a ^ //b)")).isEqualTo(EMPTY)
        Truth.assertThat(eval("buildfiles(//a)")).isEqualTo(eval("//a:BUILD"))
        Truth.assertThat(eval("buildfiles(//b)")).isEqualTo(eval("//b:BUILD"))
        Truth.assertThat(eval("buildfiles(//a + //b)")).isEqualTo(eval("//a:BUILD + //b:BUILD"))
    }

    @Test
    @Throws(Exception::class)
    open fun testBuildFilesDoesNotReturnVisibilityOfRule() {
        writeFile("fruit/BUILD", "filegroup(name='fruit', visibility=['//fruit/lemon:lemon'])")
        writeFile("fruit/lemon/BUILD", "package_group(name='lemon', packages=['//fruit/...'])")
        Truth.assertThat(eval("buildfiles(//fruit:all)")).isEqualTo(eval("//fruit:BUILD"))
    }

    @Test
    @Throws(Exception::class)
    open fun testBuildFilesDoesNotReturnVisibilityOfBUILD() {
        writeFile(
            "fruit/BUILD",
            """
        filegroup(name='fruit', srcs=['fruit.sh'])
        exports_files(['BUILD'], visibility=['//fruit/lemon:lemon'])
        
        """.trimIndent()
        )
        writeFile("fruit/lemon/BUILD", "package_group(name='lemon', packages=['//fruit/...'])")

        Truth.assertThat(eval("buildfiles(//fruit:all)")).isEqualTo(eval("//fruit:BUILD"))
    }

    @Test
    @Throws(Exception::class)
    open fun testNoImplicitDeps() {
        writeFile(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name='x', srcs=['x.cc'])"
        )

        // Implicit dependencies:
        var hostDepsExpr = helper!!.toolsRepository.toString() + "//tools/cpp:malloc"
        hostDepsExpr +=
            (" + "
                    + helper!!.toolsRepository
                    + "//tools/cpp:link_extra_lib"
                    + " + "
                    + helper!!.toolsRepository
                    + "//tools/cpp:linkextra.cc")
        if (!analysisMock.isThisBazel) {
            hostDepsExpr += " + //tools/cpp:malloc.cc"
        }
        var implicitDepsExpr = ""
        if (analysisMock.isThisBazel) {
            implicitDepsExpr +=
                (" + "
                        + helper!!.toolsRepository
                        + "//tools/def_parser:def_parser"
                        + " + "
                        + helper!!.toolsRepository
                        + "//tools/def_parser:def_parser.exe")
        }

        val targetDepsExpr = "//x:x + //x:x.cc"
        val toolchainDepsExpr = "//tools/cpp:toolchain_type + //tools/cpp:current_cc_toolchain"

        // Test all combinations of --[no]host_deps and --[no]implicit_deps on //x:x
        var expected = targetDepsExpr + " + " + hostDepsExpr + implicitDepsExpr
        if (includeCppToolchainDependencies()) {
            expected += " + " + toolchainDepsExpr
        }
        assertEqualsFiltered(expected, "deps(//x)" + TestConstants.CC_DEPENDENCY_CORRECTION)
        assertEqualsFiltered(
            targetDepsExpr + " + " + hostDepsExpr,
            "deps(//x)" + TestConstants.CC_DEPENDENCY_CORRECTION,
            QueryEnvironment.Setting.ONLY_TARGET_DEPS
        )
        assertEqualsFiltered(targetDepsExpr, "deps(//x)", QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        assertEqualsFiltered(
            targetDepsExpr,
            "deps(//x)",
            QueryEnvironment.Setting.ONLY_TARGET_DEPS,
            QueryEnvironment.Setting.NO_IMPLICIT_DEPS
        )
    }

    @Throws(Exception::class)
    protected fun assertEqualsFiltered(expected: String, actual: String, vararg settings: QueryEnvironment.Setting?) {
        helper!!.setQuerySettings(*settings)
        Truth.assertThat(eval(actual)).containsExactlyElementsIn(eval(expected))
    }

    @Throws(Exception::class)
    private fun runNodepDepsTest(expectVisibilityDep: Boolean, vararg settings: QueryEnvironment.Setting?) {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 't1', deps = [':t2'], visibility = [':pg'])
        foo_library(name = 't2')
        package_group(name = 'pg')
        
        """.trimIndent()
        )

        helper!!.setQuerySettings(*settings)

        if (expectVisibilityDep) {
            Truth.assertThat(eval("deps(//foo:t1)")).contains(Iterables.getOnlyElement<T?>(eval("//foo:pg")))
        } else {
            Truth.assertThat(eval("deps(//foo:t1)")).doesNotContain(Iterables.getOnlyElement<T?>(eval("//foo:pg")))
        }
    }

    @Test
    @Throws(Exception::class)
    fun testNodepDeps_defaultIsTrue() {
        runNodepDepsTest( /* expectVisibilityDep= */true)
    }

    @Test
    @Throws(Exception::class)
    open fun testNodepDeps_false() {
        runNodepDepsTest( /* expectVisibilityDep= */false, QueryEnvironment.Setting.NO_NODEP_DEPS)
    }

    @Test
    @Throws(Exception::class)
    open fun testCycleInStarlark() {
        runCycleInStarlarkTest( /* checkFailureDetail= */true)
    }

    @Throws(Exception::class)
    protected fun runCycleInStarlarkTest(checkFailureDetail: Boolean) {
        writeFile(
            "a/BUILD",
            """
        load('//a:cycle1.bzl', 'C1')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a')
        
        """.trimIndent()
        )
        writeFile(
            "a/cycle1.bzl",
            """
        load('//a:cycle2.bzl', 'C2')
        C1 = struct()
        
        """.trimIndent()
        )
        writeFile(
            "a/cycle2.bzl",
            """
        load('//a:cycle1.bzl', 'C1')
        C2 = struct()
        
        """.trimIndent()
        )
        val result = evalThrows("//a:all", false)
        // TODO(mschaller): evalThrows's message can be non-deterministic if events are too. It probably
        //  needs to be refactored to deal with underlying event non-determinism, because fixing query
        //  engines' event non-determinism is probably hard.
        if (checkFailureDetail) {
            assertThat(result.getFailureDetail().getTargetPatterns().getCode())
                .isEqualTo(TargetPatterns.Code.CYCLE)
        }
    }

    @Test
    @Throws(Exception::class)
    open fun testLabelsOperator() {
        writeBuildFiles3()
        writeBuildFilesWithConfigurableAttributes()
        writeBuildFilesWithImplicitAttribute()

        // srcs:
        Truth.assertThat(eval("labels(srcs, //a)")).isEqualTo(eval("//b + //c"))
        Truth.assertThat(eval("labels(srcs, //b)")).isEqualTo(eval("//d"))
        Truth.assertThat(eval("labels(srcs, //b + //a)")).isEqualTo(eval("//b + //c + //d"))

        // outs:
        Truth.assertThat(eval("labels(outs, //a)")).isEqualTo(eval("//a:out"))
        Truth.assertThat(eval("labels(outs, //b)")).isEqualTo(eval("//b:out"))
        Truth.assertThat(eval("labels(outs, //d)")).isEqualTo(EMPTY) // d is a file

        // empty:
        Truth.assertThat(eval("labels(data, //b + //a)")).isEqualTo(EMPTY)

        // no such attribute
        Truth.assertThat(eval("labels(no_such_attr, //b)")).isEqualTo(EMPTY)

        // singleton LABEL:
        Truth.assertThat(eval("labels(srcs, //k)")).isEqualTo(eval("//k:k.txt"))

        // Works for implicit edges too.  This is for consistency with --output
        // xml, which exposes them too. Note that, for whatever reason, the
        // implicit attribute must be referenced using "$" instead of "_".
        Truth.assertThat(eval("labels('\$implicit', //k)")).isEqualTo(eval("//k:implicit"))

        // Configurable deps:
        if (testConfigurableAttributes()) {
            Truth.assertThat(eval("labels(\"deps\", //configurable:main)"))
                .isEqualTo(eval("//configurable:adep + //configurable:bdep + //configurable:defaultdep"))
        }
    }

    @Throws(Exception::class)
    private fun writeBuildFilesWithImplicitAttribute() {
        writeFile(
            "k/defs.bzl",
            """
        def impl(ctx):
          return [DefaultInfo()]
        has_implicit_attr = rule(
            implementation=impl,
            attrs = {
                'srcs': attr.label_list(),
                '_implicit': attr.label(default='//k:implicit')
            },
        )
        
        """.trimIndent()
        )
        writeFile(
            "k/BUILD",
            """
        load(':defs.bzl', 'has_implicit_attr')
        has_implicit_attr(name='k', srcs=['k.txt'])
        filegroup(name='implicit')
        
        """.trimIndent()
        )
    }

    /* tests(x) operator */
    @Test
    @Throws(Exception::class)
    open fun testTestsOperatorExpandsTestsAndExcludesNonTests() {
        writeFile(
            "a/BUILD",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "test_suite(name='a')",
            "foo_test(name='foo_test', srcs=['foo_test.sh'])",
            "foo_binary(name='cc_binary')"
        )
        Truth.assertThat(eval("tests(//a)")).isEqualTo(eval("//a:foo_test"))
    }

    @Test
    @Throws(Exception::class)
    open fun testTestsOperatorFiltersByTagSizeAndEnv() {
        writeFile(
            "b/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "test_suite(name='large_tests', tags=['large'])",
            "test_suite(name='prod_tests', tags=['prod'])",
            "test_suite(name='foo_tests', tags=['foo'])",
            "foo_test(name='large_test', size='large', srcs=['foo_test.sh'])",
            "foo_test(name='prod_test', tags=['prod'], srcs=['py_test.py'])",
            "foo_test(name='foo_test', tags=['foo'])"
        )

        Truth.assertThat(eval("tests(//b:large_tests)")).isEqualTo(eval("//b:large_test"))
        Truth.assertThat(eval("tests(//b:prod_tests)")).isEqualTo(eval("//b:prod_test"))
        Truth.assertThat(eval("tests(//b:foo_tests)")).isEqualTo(eval("//b:foo_test"))
    }

    @Test
    @Throws(Exception::class)
    open fun testTestsOperatorFiltersByNegativeTag() {
        writeFile(
            "b/BUILD",
            PythonTestUtils.getPyLoad("py_test"),
            "load('@rules_cc//cc:cc_test.bzl', 'cc_test')",
            "test_suite(name='foo_tests', tags=['foo'])",
            "test_suite(name='bar_tests', tags=['bar'])",
            "test_suite(name='foo_notbar_tests', tags=['foo', '-bar'])",
            "py_test(name='py_test', tags=['blah', 'prod'], srcs=['py_test.py'])",
            "cc_test(name='cc_test', tags=['foo'])",
            "cc_test(name='cc_test2', tags=['bar'])",
            "cc_test(name='cc_test3', tags=['foo', 'bar'])"
        )

        Truth.assertThat(eval("tests(//b:foo_notbar_tests)")).isEqualTo(eval("//b:cc_test"))
        Truth.assertThat(eval("tests(//b:foo_tests)")).isEqualTo(eval("//b:cc_test + //b:cc_test3"))
        Truth.assertThat(eval("tests(//b:bar_tests)")).isEqualTo(eval("//b:cc_test2 + //b:cc_test3"))
    }

    @Test
    @Throws(Exception::class)
    open fun testTestsOperatorCrossesPackages() {
        writeFile("c/BUILD", "test_suite(name='c', tests=['//d:suite'])")
        writeFile(
            "d/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        test_suite(name='suite')
        foo_test(name='foo_test', srcs=['foo_test.sh'])
        
        """.trimIndent()
        )

        Truth.assertThat(eval("tests(//c)")).isEqualTo(eval("//d:foo_test"))
    }

    @Test
    @Throws(Exception::class)
    open fun testTestsOperatorHandlesCyclesGracefully() {
        writeFile("c/BUILD", "test_suite(name='c', tests=['//d'])")
        writeFile("d/BUILD", "test_suite(name='d', tests=['//c'])")

        Truth.assertThat(eval("tests(//c)")).isEqualTo(EMPTY) // Doesn't crash or get stuck.
    }

    @Test
    @Throws(Exception::class)
    open fun testTestSuiteInTestsAttributeAndViceVersa() {
        writeFile(
            "cherry/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        test_suite(name='cherry', tests=[':suite', ':direct'])
        test_suite(name='suite', tests=[':indirect'])
        foo_test(name='direct', srcs=['direct.sh'])
        foo_test(name='indirect', srcs=['indirect.sh'])
        
        """.trimIndent()
        )

        Truth.assertThat(eval("tests(//cherry:cherry)"))
            .isEqualTo(eval("//cherry:direct + //cherry:indirect"))
    }

    @Test
    @Throws(Exception::class)
    open fun testTestsOperatorReportsMissingTargets() {
        writeFile("c/BUILD", "test_suite(name='c', tests=['//d'])")
        writeFile("d/BUILD")

        val result = evalThrows("tests(//c)", false)
        assertStartsWith(
            "couldn't expand 'tests' attribute of test_suite //c:c: " + "no such target '//d:d'",
            result.message!!
        )
        assertPackageLoadingCode(result.getFailureDetail(), Code.TARGET_MISSING)
    }

    @Test
    @Throws(Exception::class)
    open fun testDotDotDotWithUnrelatedCycle() {
        writeFile(
            "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name = 'a')"
        )
        writeFile(
            "cycle/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'cycle1', deps = ['cycle2'])
        foo_library(name = 'cycle2', deps = ['cycle1'])
        
        """.trimIndent()
        )
        Truth.assertThat(eval("//a:a")).isEqualTo(eval("//a/..."))
    }

    @Test
    @Throws(Exception::class)
    open fun testDotDotDotWithCycle() {
        writeFile(
            "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name = 'a')"
        )
        writeFile(
            "a/b/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'cycle1', deps = ['cycle2'])
        foo_library(name = 'cycle2', deps = ['cycle1'])
        
        """.trimIndent()
        )
        Truth.assertThat(eval("//a:a + //a/b:cycle1 + //a/b:cycle2")).isEqualTo(eval("//a/..."))
    }

    /* executables(x) operator */
    @Test
    @Throws(Exception::class)
    fun testExecutablesQuery() {
        writeFile(
            "donut/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc:cc_test.bzl", "cc_test")

        foo_binary(
            name = "bin",
            srcs = ["thief.sh"],
        )

        cc_test(
            name = "test",
            srcs = ["shop.cc"],
        )

        cc_library(
            name = "lib",
            srcs = ["shop.cc"],
        )
        
        """.trimIndent()
        )

        Truth.assertThat(eval("executables(//donut:all)")).isEqualTo(eval("//donut:bin"))
    }

    /* set(x) operator */
    @Test
    @Throws(Exception::class)
    open fun testSet() {
        writeBuildFiles3()

        Truth.assertThat(eval("set()")).isEqualTo(EMPTY)
        Truth.assertThat(eval("set(\t//a\n//b )")).isEqualTo(eval("//a + //b"))
        Truth.assertThat(eval("set(//a //b //c //d)")).isEqualTo(eval("//a + //b + //c + //d"))
    }

    /* Regression tests */ // Regression test for bug #1153968, "CRASH in query: getTransitiveClosure
    // called without prior call to buildTransitiveClosure".
    @Test
    @Throws(Exception::class)
    fun testRuleOutputAmbiguityIsntFatal() {
        writeFile("x/BUILD", "genrule(name='x', outs=['x'], cmd='')")
        val result = eval("allpaths(x:*, //x)") // doesn't crash
        // result = { genrule(//x) }
        Truth.assertThat(result).hasSize(1)
        val r = result.iterator().next()
        Truth.assertThat(helper!!.getLabel(r)).isEqualTo("//x:x")
    }

    // Regression test for bug #2340261:
    // "blaze query doesn't show deps that come from the default_visibility..."
    @Test
    @Throws(Exception::class)
    fun testDefaultVisibilityReturnedInDeps() {
        writeFile(
            "kiwi/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        package(default_visibility=['//mango:mango'])
        foo_library(name='kiwi')
        
        """.trimIndent()
        )
        writeFile("mango/BUILD", "package_group(name='mango', packages=[])")

        val result = eval("deps(//kiwi:kiwi)" + this.dependencyCorrection)
        Truth.assertThat(result).isEqualTo(eval("//mango:mango + //kiwi:kiwi"))
    }

    @Test
    @Throws(Exception::class)
    open fun testDefaultVisibilityReturnedInDeps_nonEmptyDependencyFilter() {
        writeFile(
            "kiwi/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        package(default_visibility=['//mango:mango'])
        foo_library(name='kiwi')
        
        """.trimIndent()
        )
        writeFile("mango/BUILD", "package_group(name='mango', packages=[])")

        helper!!.setQuerySettings(QueryEnvironment.Setting.ONLY_TARGET_DEPS)

        val result = eval("deps(//kiwi:kiwi)" + this.dependencyCorrection)
        Truth.assertThat(result).isEqualTo(eval("//mango:mango + //kiwi:kiwi"))
    }

    @Test
    @Throws(Exception::class)
    fun testDefaultVisibilityReturnedInDepsForInputFiles() {
        writeFile(
            "kiwi/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        package(default_visibility=['//mango:mango'])
        foo_library(name='kiwi', srcs=['kiwi.sh'])
        
        """.trimIndent()
        )
        writeFile("mango/BUILD", "package_group(name='mango', packages=[])")

        val result = eval("deps(//kiwi:kiwi.sh)")
        // Updated the test with an isThisBazel() check because incompatible_no_implicit_file_export
        // is flipped to true in Bazel but remains false in Blaze. This discrepancy caused 'unexpected
        // target' failures internally by still including the //mango:mango package group. The
        // conditional logic ensures the test correctly validates both environments, and TAP presubmits
        // are now passing.
        if (analysisMock.isThisBazel) {
            Truth.assertThat(result).isEqualTo(eval("//kiwi:kiwi.sh"))
        } else {
            Truth.assertThat(result).isEqualTo(eval("//mango:mango + //kiwi:kiwi.sh"))
        }
    }

    // Regression test for bug #2827101:
    // "Package group dependencies are not taken into account by gcheckout"
    @Test
    @Throws(Exception::class)
    fun testIncludesReturnedInDeps() {
        writeFile(
            "peach/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        package_group(name='peach',
                      includes=[':seed'])
        package_group(name='seed',
                      includes=[':cyanide'])
        package_group(name='cyanide',
                      packages=['//hydrogen', '//nitrogen', '//carbon'])
        foo_library(name='dessert',
                   visibility=[':peach'])
        
        """.trimIndent()
        )

        val result = eval("deps(//peach:dessert)" + this.dependencyCorrection)
        Truth.assertThat(result)
            .isEqualTo(eval("//peach:peach + //peach:seed + //peach:cyanide + //peach:dessert"))
    }

    // Regression test for #1267510, modification of result of subexpression
    // evaluation.
    @Test
    @Throws(Exception::class)
    fun testRegression1267510() {
        writeFile("x/BUILD")
        writeFile("y/BUILD")

        // somepath(x:BUILD, y:BUILD) returns a constant empty set.  "+" should not
        // attempt to modify its LHS operand.
        Truth.assertThat(eval("somepath(x:BUILD, y:BUILD) + x:BUILD")).isEqualTo(eval("x:BUILD"))
    }

    // Regression test for #1309697, NPE crash during Blaze query.
    @Test
    @Throws(Exception::class)
    open fun testRegression1309697() {
        writeFile(
            "x/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='x', srcs=['a.cc', 'a.cc'])"
        )
        val expectedError = "Label '//x:a.cc' is duplicated in the 'srcs' attribute of rule 'x'"
        if (helper!!.isKeepGoing) {
            Truth.assertThat(evalThrows("//x:all", false).message).contains(expectedError)
        } else {
            evalThrows("//x:all", false)
            assertContainsEvent(expectedError)
        }
    }

    // Private helper of testGraphOrderOfWildcards.
    @Throws(Exception::class)
    private fun one(label: String): T? {
        return eval(label).iterator().next()
    }

    @Test
    @Throws(Exception::class)
    open fun testGraphOrderOfWildcards() {
        // TODO(blaze-team): (2009) we could use some helpers for graph order tests.
        writeFile(
            "x/BUILD",
            """
        genrule(name='x', srcs=['y'], outs=['x.out'], cmd=':')
        genrule(name='y', outs=['y.out'], cmd=':')
        
        """.trimIndent()
        )
        helper!!.setOrderedResults(true) // This query needs a graph.

        val resultAndTargets: ResultAndTargets<T?> = helper!!.evaluateQuery("//x:*")
        val digraphResult: DigraphQueryEvalResult<T?> =
            resultAndTargets.getQueryEvalResult() as DigraphQueryEvalResult<T?>
        val results: MutableSet<T?> = resultAndTargets.getResultSet()
        val subgraph: Digraph<T?> = digraphResult.graph.extractSubgraph(results)

        val xBuild = one("//x:BUILD")
        val xx = one("//x:x")
        val xxout = one("//x:x.out")
        val xy = one("//x:y")
        val xyout = one("//x:y.out")

        Truth.assertThat(results).isEqualTo(ImmutableSet.of<T?>(xBuild, xx, xxout, xy, xyout))

        val expected: Digraph<T?> = Digraph<T?>()
        expected.addEdge(xyout, xy)
        expected.addEdge(xx, xy)
        expected.addEdge(xxout, xx)
        expected.createNode(xBuild)
        if (!expected.equals(subgraph)) {
            // TODO(blaze-team): (2009) make this a utility method of Digraph.
            System.err.println("Expected:")
            expected.visitNodesBeforeEdges(
                createVisitor<T?>(
                    PrintWriter(BufferedWriter(OutputStreamWriter(System.err, StandardCharsets.UTF_8)))
                ),
                null
            )
            System.err.println("Was:")
            subgraph.visitNodesBeforeEdges(
                createVisitor<T?>(
                    PrintWriter(BufferedWriter(OutputStreamWriter(System.err, StandardCharsets.UTF_8)))
                ),
                null
            )
            Assert.fail()
        }
    }

    // Regression test for bug #1345896, "Blaze query p:* loads more packages
    // than just p".
    @Test
    @Throws(Exception::class)
    open fun testWildcardsDontLoadUnnecessaryPackages() {
        writeFile(
            "x/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='x', deps=['//y'])"
        )
        writeFile("y/BUILD")

        eval("//x:*")
        helper!!.assertPackageNotLoaded("y")
    }

    // #1352570, "NPE crash in deps(x, n)".
    @Test
    @Throws(Exception::class)
    fun testRegression1352570() {
        writeFile(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(name='x', deps=['z'])
        cc_library(name='y', deps=['z'])
        cc_library(name='z')
        
        """.trimIndent()
        )
        val result = eval("deps(//x:x + //x:y, 2) intersect //x:*") // no crash
        Truth.assertThat(result).isEqualTo(eval("//x:x + //x:y + //x:z"))
    }

    @Test
    @Throws(Exception::class)
    open fun testTestSuiteWithFile() {
        // Note that test_suite does not restrict the set of targets that can appear here.
        writeFile("x/BUILD", "test_suite(name='a', tests=['a.txt'])")
        Truth.assertThat(eval("tests(//x:a)")).isEmpty()
        Truth.assertThat(eval("deps(//x:a)")).isEqualTo(eval("//x:a + //x:a.txt"))
    }

    @Test
    @Throws(Exception::class)
    open fun testStrictTestSuiteWithFile() {
        helper!!.setQuerySettings(QueryEnvironment.Setting.TESTS_EXPRESSION_STRICT)
        writeFile("x/BUILD", "test_suite(name='a', tests=['a.txt'])")
        val result = evalThrows("tests(//x:a)", false)
        Truth.assertThat(result.message)
            .isEqualTo(
                "The label '//x:a.txt' in the test_suite '//x:a' does not refer to a test or "
                        + "test_suite rule!"
            )
        assertQueryCode(result.getFailureDetail(), Query.Code.INVALID_LABEL_IN_TEST_SUITE)
    }

    @Test
    @Throws(Exception::class)
    open fun testAmbiguousAllResolvesToTestSuiteNamedAll() {
        helper!!.setQuerySettings(QueryEnvironment.Setting.TESTS_EXPRESSION_STRICT)
        writeFile(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_test.bzl", "cc_test")
        cc_test(name='one')
        cc_test(name='two')
        test_suite(name='all', tests=[':one'])
        
        """.trimIndent()
        )
        Truth.assertThat(eval("tests(//x:all)")).isEqualTo(eval("//x:one"))
        // Expect an ambiguity warning in the event handler.
        assertContainsEvent(
            "The target pattern '//x:all' is ambiguous: ':all' is both a wildcard, and "
                    + "the name of an existing test_suite rule; using the latter interpretation"
        )
    }

    // Test that long expressions can be parsed and evaluated (without stackoverflow)
    @Test
    @Throws(Exception::class)
    fun testBigExpression() {
        writeBuildFiles3()

        val query = StringBuilder()
        query.append("//a")
        for (i in 1..9999) {
            query.append("+ //b")
        }
        Truth.assertThat(eval(query.toString())).isEqualTo(eval("//a + //b"))
    }

    @Test
    @Throws(Exception::class)
    open fun testSlashSlashDotDotDot() {
        useReducedSetOfRules()
        writeFile("MODULE.bazel")
        writeFile("a/BUILD", "filegroup(name = 'a', srcs = ['a.sh'])")
        Truth.assertThat(eval("//...")).isEqualTo(eval("//a"))
    }

    @Test
    @Throws(Exception::class)
    fun testQueryTimeLoadingOfTargetPatternHappyPath() {
        // Given a workspace containing two packages, "//a" and "//a/b",
        writeFile(
            "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name = 'a')"
        )
        writeFile(
            "a/b/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'b')"
        )

        // When the query environment is queried for "//a/b:b" which hasn't been loaded,
        val queryTimeLoadedPattern = eval("//a/b:b")

        // Then the query evaluates to that target.
        Truth.assertThat(queryTimeLoadedPattern).hasSize(1)
    }

    @Test
    @Throws(Exception::class)
    open fun testQueryTimeLoadingOfTargetsBelowPackageHappyPath() {
        // Given a workspace containing three packages, "//a", "//a/b", and "//a/b/c",
        writeFile(
            "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name = 'a')"
        )
        writeFile(
            "a/b/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'b')"
        )
        writeFile(
            "a/b/c/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'c')"
        )

        // When the query environment is queried for "//a/b/..." which hasn't been loaded,
        val queryTimeLoadedPattern = eval("//a/b/...")

        // Then the query evaluates to the two targets "//a/b:b" and "//a/b/c:c".
        Truth.assertThat(queryTimeLoadedPattern).hasSize(2)
    }

    @Test
    @Throws(Exception::class)
    open fun testQueryTimeLoadingTargetsBelowMissingPackage() {
        // Given a workspace containing one package, "//a",
        writeFile(
            "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name = 'a')"
        )

        // When the query environment is queried for targets belonging to packages beneath the
        // package "a/b", which doesn't exist,
        val missingPackage = "a/b"
        val result = evalThrows("//" + missingPackage + "/...", false)
        val s = result.message

        // Then an exception is thrown that says that the pattern matched nothing.
        Truth.assertThat(s).containsMatch("no targets found beneath '" + missingPackage + "'")
        assertThat(result.getFailureDetail().getTargetPatterns().getCode())
            .isEqualTo(TargetPatterns.Code.TARGETS_MISSING)
    }

    @Test
    @Throws(Exception::class)
    open fun testQueryTimeLoadingTargetsBelowNonPackageDirectory() {
        // Given a workspace containing two packages, "//a/b/c", and "//a/b/c/d",
        writeFile(
            "a/b/c/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'c')"
        )
        writeFile(
            "a/b/c/d/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'd')"
        )

        // When the query environment is queried for "//a/b/..." which hasn't been loaded,
        val queryTimeLoadedPattern = eval("//a/b/...")

        // Then the query evaluates to the two targets "//a/b/c:c" and "//a/b/c/d:d".
        Truth.assertThat(queryTimeLoadedPattern).hasSize(2)
    }

    @Throws(Exception::class)
    private fun useExtendedSetOfRules() {
        helper!!.useRuleClassProvider(
            setRuleClassProviders(
                TestAspects.BASE_RULE,
                TestAspects.ASPECT_REQUIRING_RULE,
                TestAspects.EXTRA_ATTRIBUTE_ASPECT_REQUIRING_PROVIDER_RULE,
                TestAspects.HONEST_RULE,
                TestAspects.SIMPLE_RULE
            )
                .build()
        )
    }

    @Throws(Exception::class)
    protected fun useReducedSetOfRules() {
        helper!!.clearAllFiles()
        helper!!.useRuleClassProvider(analysisMock.createRuleClassProvider())
        helper!!.writeFile("embedded_tools/BUILD")
        helper!!.writeFile("embedded_tools/MODULE.bazel", "module(name='bazel_tools')")
        helper!!.writeFile("embedded_tools/tools/build_defs/repo/BUILD")
        helper!!.writeFile(
            "embedded_tools/tools/build_defs/repo/local.bzl",
            """
        def _local_repository_impl(rctx):
          path = rctx.workspace_root.get_child(rctx.attr.path)
          rctx.symlink(path, ".")
        local_repository = repository_rule(
          implementation = _local_repository_impl,
          attrs = {"path": attr.string()},
        )
        
        """.trimIndent()
        )
        helper!!.writeFile("platforms_workspace/BUILD")
        helper!!.writeFile("platforms_workspace/MODULE.bazel", "module(name='platforms')")
        helper!!.writeFile("local_config_xcode_workspace/BUILD")
        helper!!.writeFile(
            "local_config_xcode_workspace/MODULE.bazel", "module(name='local_config_xcode')"
        )
        helper!!.writeFile("rules_java_workspace/BUILD")
        helper!!.writeFile("rules_java_workspace/MODULE.bazel", "module(name='rules_java')")
        helper!!.writeFile("rules_python_workspace/BUILD")
        helper!!.writeFile("rules_python_workspace/MODULE.bazel", "module(name='rules_python')")
        helper!!.writeFile("rules_python_internal_workspace/BUILD")
        helper!!.writeFile(
            "rules_python_internal_workspace/MODULE.bazel", "module(name='rules_python_internal')"
        )
        helper!!.writeFile("bazel_skylib_workspace/BUILD")
        helper!!.writeFile("bazel_skylib_workspace/MODULE.bazel", "module(name='bazel_skylib')")
        helper!!.writeFile("third_party/protobuf/BUILD")
        helper!!.writeFile("third_party/protobuf/MODULE.bazel", "module(name='com_google_protobuf')")
        helper!!.writeFile("proto_bazel_features_workspace/BUILD")
        helper!!.writeFile(
            "proto_bazel_features_workspace/MODULE.bazel", "module(name='proto_bazel_features')"
        )
        helper!!.writeFile("bazel_features_workspace/BUILD")
        helper!!.writeFile("bazel_features_workspace/MODULE.bazel", "module(name='bazel_features')")
        helper!!.writeFile("build_bazel_apple_support/BUILD")
        helper!!.writeFile(
            "build_bazel_apple_support/MODULE.bazel", "module(name='build_bazel_apple_support')"
        )
        helper!!.writeFile("third_party/bazel_rules/rules_cc/BUILD")
        helper!!.writeFile("third_party/bazel_rules/rules_cc/MODULE.bazel", "module(name='rules_cc')")
        helper!!.writeFile("third_party/bazel_rules/rules_shell/BUILD")
        helper!!.writeFile(
            "third_party/bazel_rules/rules_shell/MODULE.bazel", "module(name='rules_shell')"
        )
    }

    @Test
    @Throws(Exception::class)
    open fun testHaveDepsOnAspectsAttributes() {
        useExtendedSetOfRules()
        writeFile(
            "a/BUILD",
            """
        extra_attribute_aspect_requiring_provider(name='a', foo=[':b'])
        honest(name='b', foo=[])
        
        """.trimIndent()
        )
        writeFile("extra/BUILD", "honest(name='extra', foo=[])")

        Truth.assertThat(evalToString("deps(//a:a)")).contains("//extra:extra")
    }

    @Test
    @Throws(Exception::class)
    open fun testNoDepsOnAspectAttributeWhenAspectMissing() {
        useExtendedSetOfRules()
        writeFile(
            "a/BUILD",
            """
        aspect(name='a', foo=[':b'])
        honest(name='b', foo=[])
        extra_attribute_aspect_requiring_provider(name='c', foo=[':d'])
        simple(name='d', foo=[])
        
        """.trimIndent()
        )
        writeFile("extra/BUILD", "honest(name='extra', foo=[])")

        Truth.assertThat(evalToString("deps(//a:a)")).doesNotContain("//extra:extra")
        Truth.assertThat(evalToString("deps(//a:c)")).doesNotContain("//extra:extra")
    }

    @Test
    @Throws(Exception::class)
    open fun testNoDepsOnAspectAttributeWithNoImpicitDeps() {
        useExtendedSetOfRules()
        helper!!.setQuerySettings(QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        writeFile(
            "a/BUILD",
            """
        extra_attribute_aspect_requiring_provider(name='a', foo=[':b'])
        honest(name='b', foo=[])
        
        """.trimIndent()
        )
        writeFile("extra/BUILD", "honest(name='extra', foo=[])")

        Truth.assertThat(evalToString("deps(//a:a)")).doesNotContain("//extra:extra")
    }

    @Throws(Exception::class)
    fun simpleVisibilityTest(visibility: String?, expectVisible: Boolean) {
        useReducedSetOfRules()
        writeFile("MODULE.bazel")
        writeFile("a/BUILD", "filegroup(name = 'a', srcs = ['//b:b'])")
        writeFile(
            "b/BUILD", "filegroup(name = 'b', srcs = ['b.txt'], visibility = ['" + visibility + "'])"
        )
        val actual = evalToString("visible(//a, somepath(//a, //b))")
        if (expectVisible) {
            Truth.assertThat(actual).isEqualTo("//a:a //b:b")
        } else {
            Truth.assertThat(actual).isEqualTo("//a:a")
        }
    }

    @Test
    @Throws(Exception::class)
    open fun testVisible_simple_public() {
        simpleVisibilityTest("//visibility:public", true)
    }

    @Test
    @Throws(Exception::class)
    open fun testVisible_simple_private() {
        simpleVisibilityTest("//visibility:private", false)
    }

    @Test
    @Throws(Exception::class)
    open fun testVisible_simple_package() {
        simpleVisibilityTest("//a:__pkg__", true)
    }

    @Test
    @Throws(Exception::class)
    open fun testVisible_simple_subpackages() {
        simpleVisibilityTest("//a:__subpackages__", true)
    }

    @Test
    @Throws(Exception::class)
    open fun testVisible_simple_different_subpackages() {
        simpleVisibilityTest("//c:__subpackages__", false)
    }

    @Test
    @Throws(Exception::class)
    open fun testVisible_private_same_package() {
        useReducedSetOfRules()
        writeFile("MODULE.bazel")
        writeFile(
            "a/BUILD",
            """
        filegroup(name = 'a', srcs = [':b'], visibility = ['//visibility:private'])
        filegroup(name = 'b', srcs = ['b.txt'], visibility = ['//visibility:private'])
        
        """.trimIndent()
        )
        Truth.assertThat(evalToString("visible(//a:a, somepath(//a:a, //a:b))")).isEqualTo("//a:a //a:b")
    }

    @Test
    @Throws(Exception::class)
    open fun testVisible_package_group() {
        useReducedSetOfRules()
        writeFile("MODULE.bazel")
        writeFile("a/BUILD", "filegroup(name = 'a', srcs = ['//b:b'])")
        writeFile(
            "b/BUILD",
            """
        package_group(name = 'friends', packages = ['//a', '//b'])
        filegroup(name = 'b', srcs = ['b.txt'], visibility = [':friends'])
        
        """.trimIndent()
        )
        Truth.assertThat(evalToString("visible(//a, somepath(//a, //b))")).isEqualTo("//a:a //b:b")
    }

    @Test
    @Throws(Exception::class)
    open fun testVisible_package_group_invisible() {
        useReducedSetOfRules()
        writeFile("MODULE.bazel")
        writeFile("a/BUILD", "filegroup(name = 'a', srcs = ['//b:b'])")
        writeFile(
            "b/BUILD",
            """
        package_group(name = 'friends', packages = ['//c'])
        filegroup(name = 'b', srcs = ['b.txt'], visibility = [':friends'])
        
        """.trimIndent()
        )
        writeFile("c/BUILD")
        Truth.assertThat(evalToString("visible(//a, somepath(//a, //b))")).isEqualTo("//a:a")
    }

    @Test
    @Throws(Exception::class)
    open fun testVisible_package_group_include() {
        useReducedSetOfRules()
        writeFile("MODULE.bazel")
        writeFile("a/BUILD", "filegroup(name = 'a', srcs = ['//b:b'])")
        writeFile(
            "b/BUILD",
            """
        package_group(name = 'friends', packages = ['//c'], includes = [':friends_of_friends'])
        package_group(name = 'friends_of_friends', packages = ['//a'])
        filegroup(name = 'b', srcs = ['b.txt'], visibility = [':friends'])
        
        """.trimIndent()
        )
        writeFile("c/BUILD")
        Truth.assertThat(evalToString("visible(//a, somepath(//a, //b))")).isEqualTo("//a:a //b:b")
    }

    @Test
    @Throws(Exception::class)
    open fun testVisible_java_javatests() {
        useReducedSetOfRules()
        writeFile("MODULE.bazel")
        writeFile(
            "java/com/google/a/BUILD",
            "filegroup(name = 'a', srcs = ['a.txt'], visibility = ['//visibility:private'])"
        )
        writeFile(
            "javatests/com/google/a/BUILD",
            "filegroup(name = 'a', srcs = ['//java/com/google/a:a'],"
                    + " visibility = ['//visibility:private'])"
        )
        Truth.assertThat(
            evalToString(
                "visible(//javatests/com/google/a,"
                        + " somepath(//javatests/com/google/a, //java/com/google/a))"
            )
        )
            .isEqualTo("//java/com/google/a:a //javatests/com/google/a:a")
    }

    @Test
    @Throws(Exception::class)
    open fun testVisible_java_javatests_different_package() {
        useReducedSetOfRules()
        writeFile("MODULE.bazel")
        writeFile(
            "java/com/google/a/BUILD",
            "filegroup(name = 'a', srcs = ['a.txt'], visibility = ['//visibility:private'])"
        )
        writeFile(
            "javatests/com/google/b/BUILD",
            "filegroup(name = 'b', srcs = ['//java/com/google/a:a'],"
                    + " visibility = ['//visibility:private'])"
        )
        Truth.assertThat(
            evalToString(
                "visible(//javatests/com/google/b,"
                        + " somepath(//javatests/com/google/b, //java/com/google/a))"
            )
        )
            .isEqualTo("//javatests/com/google/b:b")
    }

    // java cannot see javatests
    @Test
    @Throws(Exception::class)
    open fun testVisible_javatests_java() {
        useReducedSetOfRules()
        writeFile("MODULE.bazel")
        writeFile(
            "javatests/com/google/a/BUILD",
            "filegroup(name = 'a', srcs = ['a.txt'], visibility = ['//visibility:private'])"
        )
        writeFile(
            "java/com/google/a/BUILD",
            "filegroup(name = 'a', srcs = ['//javatests/com/google/a:a'],"
                    + " visibility = ['//visibility:private'])"
        )
        Truth.assertThat(
            evalToString(
                "visible(//java/com/google/a,"
                        + " somepath(//java/com/google/a, //javatests/com/google/a))"
            )
        )
            .isEqualTo("//java/com/google/a:a")
    }

    @Test
    @Throws(Exception::class)
    open fun testVisible_default_private() {
        useReducedSetOfRules()
        writeFile("MODULE.bazel")
        writeFile("a/BUILD", "filegroup(name = 'a', srcs = ['//b'])")
        writeFile(
            "b/BUILD",
            """
        package(default_visibility = ['//visibility:private'])
        filegroup(name = 'b', srcs = ['b.txt'])
        
        """.trimIndent()
        )
        Truth.assertThat(evalToString("visible(//a, somepath(//a, //b))")).isEqualTo("//a:a")
    }

    @Test
    @Throws(Exception::class)
    open fun testVisible_default_public() {
        useReducedSetOfRules()
        writeFile("MODULE.bazel")
        writeFile("a/BUILD", "filegroup(name = 'a', srcs = ['//b'])")
        writeFile(
            "b/BUILD",
            """
        package(default_visibility = ['//visibility:public'])
        filegroup(name = 'b', srcs = ['b.txt'])
        
        """.trimIndent()
        )
        Truth.assertThat(evalToString("visible(//a, somepath(//a, //b))")).isEqualTo("//a:a //b:b")
    }

    @Test
    @Throws(Exception::class)
    open fun testPackageGroupAllBeneath() {
        useReducedSetOfRules()
        writeFile("MODULE.bazel")
        writeFile("a/BUILD", "filegroup(name = 'a', srcs = ['//b:b'])")
        writeFile(
            "b/BUILD",
            """
        package_group(name = 'friends', packages = ['//a/...'])
        filegroup(name = 'b', srcs = ['b.txt'], visibility = [':friends'])
        
        """.trimIndent()
        )
        Truth.assertThat(evalToString("visible(//a, somepath(//a, //b))")).isEqualTo("//a:a //b:b")
    }

    @Test
    @Throws(Exception::class)
    open fun testBuildfilesWithDuplicates() {
        writeFile(
            "foo/BUILD",
            """
        load('//baz:baz.bzl', 'x')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'foo', deps = ['//baz'])
        
        """.trimIndent()
        )
        writeFile(
            "bar/BUILD",
            """
        load('//baz:baz.bzl', 'x')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'bar', deps = ['//baz'])
        
        """.trimIndent()
        )
        writeFile(
            "baz/BUILD",
            """
        load('//baz:baz.bzl', 'x')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'baz')
        
        """.trimIndent()
        )
        writeFile("baz/baz.bzl", "x = 2")
        Truth.assertThat(evalToString("buildfiles(deps(//foo)) + buildfiles(deps(//bar))"))
            .isEqualTo(
                "//bar:BUILD //baz:BUILD //baz:baz.bzl //foo:BUILD //test_defs:BUILD"
                        + " //test_defs:foo_library.bzl"
            )
    }

    @Test
    @Throws(Exception::class)
    open fun bzlPackageBadDueToBrokenLoad() {
        writeFile("foo/BUILD", "load('//bar:bar.bzl', 'sym')")
        writeFile("bar/BUILD", "load('//noexist:noexist.bzl', 'bad')")
        writeFile("bar/bar.bzl", "sym = 0")
        Truth.assertThat(evalToListOfStrings("buildfiles(//foo:BUILD)"))
            .containsExactly("//foo:BUILD", "//bar:bar.bzl", "//bar:BUILD")
    }

    @Test
    @Throws(Exception::class)
    open fun bzlPackageBadDueToBrokenSyntax() {
        writeFile("foo/BUILD", "load('//bar:bar.bzl', 'sym')")
        writeFile("bar/BUILD", "malformed syntax")
        writeFile("bar/bar.bzl", "sym = 0")
        Truth.assertThat(evalToListOfStrings("buildfiles(//foo:BUILD)"))
            .containsExactly("//foo:BUILD", "//bar:bar.bzl", "//bar:BUILD")
    }

    @Test
    @Throws(Exception::class)
    open fun testBuildfilesContainingScl() {
        writeBzlAndSclFiles()

        Truth.assertThat(evalToString("buildfiles(deps(//foo))"))
            .isEqualTo(
                "//bar:BUILD //bar:direct.scl //bar:indirect.scl //bar:intermediate.bzl //foo:BUILD"
                        + " //test_defs:BUILD //test_defs:foo_library.bzl"
            )
    }

    @Test
    @Throws(Exception::class)
    open fun badRuleInDeps() {
        runBadRuleInDeps(Code.STARLARK_EVAL_ERROR)
    }

    @Throws(Exception::class)
    protected fun runBadRuleInDeps(code: Any?) {
        writeFile(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'foo', deps = ['//bar:bar'])"
        )
        writeFile(
            "bar/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar', srcs = 'bad_single_file')"
        )
        val evalThrowsResult =
            evalThrows("deps(//foo:foo)",  /* unconditionallyThrows= */false)
        val failureDetailBuilder: FailureDetail.Builder = FailureDetail.newBuilder()
        if (code is FailureDetails.PackageLoading.Code) {
            failureDetailBuilder.setPackageLoading(
                FailureDetails.PackageLoading.newBuilder().setCode(code as Code?)
            )
        } else if (code is Query.Code) {
            failureDetailBuilder.setQuery(FailureDetails.Query.newBuilder().setCode(code as Query.Code?))
        }
        assertThat(evalThrowsResult.getFailureDetail())
            .comparingExpectedFieldsOnly()
            .isEqualTo(failureDetailBuilder.build())
    }

    @Test
    @Throws(Exception::class)
    open fun buildfilesBazel() {
        writeFile("bar/BUILD.bazel")
        writeFile("bar/bar.bzl", "sym = 0")
        writeFile("foo/BUILD.bazel", "load('//bar:bar.bzl', 'sym')")
        Truth.assertThat(evalToListOfStrings("buildfiles(foo:*)"))
            .containsExactly("//foo:BUILD.bazel", "//bar:bar.bzl", "//bar:BUILD.bazel")
    }

    @Test
    @Throws(Exception::class)
    open fun testTargetsFromBuildfilesAndRealTargets() {
        writeFile(
            "foo/BUILD",
            """
        load('//baz:baz.bzl', 'x')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'foo', deps = ['//baz'])
        
        """.trimIndent()
        )
        writeFile(
            "baz/BUILD",
            """
        load('//baz:baz.bzl', 'x')
        load('//test_defs:foo_library.bzl', 'foo_library')
        exports_files(['baz.bzl'])
        foo_library(name = 'baz')
        
        """.trimIndent()
        )
        writeFile("baz/baz.bzl", "x = 2")
        Truth.assertThat(evalToString("buildfiles(deps(//foo)) + //baz:BUILD + //baz:baz.bzl"))
            .isEqualTo(
                "//baz:BUILD //baz:baz.bzl //foo:BUILD //test_defs:BUILD //test_defs:foo_library.bzl"
            )
        Truth.assertThat(evalToString("buildfiles(deps(//foo)) ^ //baz:BUILD")).isEqualTo("//baz:BUILD")
        Truth.assertThat(evalToString("buildfiles(deps(//foo)) ^ //baz:baz.bzl")).isEqualTo("//baz:baz.bzl")
    }

    @Test
    @Throws(Exception::class)
    open fun testBuildfilesOfBuildfiles() {
        writeFile(
            "foo/BUILD",
            """
        load('//baz:baz.bzl', 'x')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'foo')
        
        """.trimIndent()
        )
        writeFile("baz/BUILD", "load('//bar:bar.bzl', 'x')")
        writeFile("baz/baz.bzl", "x = 1")
        writeFile("bar/BUILD")
        writeFile("bar/bar.bzl", "x = 2")
        Truth.assertThat(evalToString("buildfiles(//foo)"))
            .isEqualTo(
                "//baz:BUILD //baz:baz.bzl //foo:BUILD //test_defs:BUILD //test_defs:foo_library.bzl"
            )
        Truth.assertThat(evalToString("buildfiles(buildfiles(//foo))"))
            .isEqualTo(
                "//baz:BUILD //baz:baz.bzl //foo:BUILD //test_defs:BUILD //test_defs:foo_library.bzl"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testBoundedDepsStreaming() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', deps = [':b'])
        foo_library(name = 'b', deps = [':c'])
        foo_library(name = 'c', deps = [':d'])
        foo_library(name = 'd')
        
        """.trimIndent()
        )
        Truth.assertThat(evalToString("deps(//foo:a + //foo:b, 1)" + this.dependencyCorrection))
            .isEqualTo("//foo:a //foo:b //foo:c")
    }

    @Test
    @Throws(Exception::class)
    fun testBoundedRdepsStreaming() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', deps = [':b'])
        foo_library(name = 'b', deps = [':c'])
        foo_library(name = 'c', deps = [':d'])
        foo_library(name = 'd')
        
        """.trimIndent()
        )
        Truth.assertThat(evalToString("rdeps(//foo:a, //foo:d + //foo:c, 1)" + this.dependencyCorrection))
            .isEqualTo("//foo:b //foo:c //foo:d")
    }

    @Test
    @Throws(Exception::class)
    open fun boundedDepsWithError() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'foo', deps = [':dep'])
        foo_library(name = 'dep', deps = ['//bar:missing'])
        
        """.trimIndent()
        )
        Truth.assertThat(evalToListOfStrings("deps(//foo:foo, 1)")).containsExactly("//foo:foo", "//foo:dep")
    }

    // Ideally we wouldn't fail on an irrelevant error (since //bar:missing is a dep of //foo:dep,
    // not an rdep). This test documents the current non-ideal behavior.
    @Test
    @Throws(Exception::class)
    open fun boundedRdepsWithError() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'foo', deps = [':dep'])
        foo_library(name = 'dep', deps = ['//bar:missing'])
        
        """.trimIndent()
        )
        Truth.assertThat(
            evalThrows("rdeps(//foo:foo, //foo:dep, 1)",  /* unconditionallyThrows= */false).message
        )
            .contains("preloading transitive closure failed: no such package 'bar':")
    }

    @Test
    @Throws(Exception::class)
    open fun testEqualityOfOrderedThreadSafeImmutableSet() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a')
        foo_library(name = 'b')
        
        """.trimIndent()
        )

        val targets = eval("//foo:a + //foo:b")
        val env: QueryEnvironment<T?> = helper!!.queryEnvironment
        val mutableSet: ThreadSafeMutableSet<T?>? = env.createThreadSafeMutableSet()
        mutableSet.addAll(targets)
        Truth.assertThat(targets).isEqualTo(mutableSet)
    }

    @Test
    @Throws(Exception::class)
    open fun testSiblings_simple() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a')
        foo_library(name = 'b')
        foo_library(name = 'c')
        foo_library(name = 'd')
        
        """.trimIndent()
        )
        Truth.assertThat(evalToString("siblings(//foo:a)"))
            .isEqualTo("//foo:BUILD //foo:a //foo:b //foo:c //foo:d")
    }

    @Test
    @Throws(Exception::class)
    open fun testSiblings_duplicatePackages() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a')
        foo_library(name = 'b')
        foo_library(name = 'c')
        foo_library(name = 'd')
        
        """.trimIndent()
        )
        Truth.assertThat(evalToString("siblings(//foo:a + //foo:b + //foo:c + //foo:d)"))
            .isEqualTo("//foo:BUILD //foo:a //foo:b //foo:c //foo:d")
    }

    @Test
    @Throws(Exception::class)
    open fun testSiblings_samePackageRdeps() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', deps = [':b'])
        foo_library(name = 'b', deps = [':c', ':d'])
        foo_library(name = 'c', deps = [':d'])
        foo_library(name = 'd')
        
        """.trimIndent()
        )
        writeFile(
            "bar/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'e', deps = ['//foo:d'])
        foo_library(name = 'f', deps = ['//foo:d'])
        
        """.trimIndent()
        )
        Truth.assertThat(evalToString("rdeps(//foo:* + //bar:*, //foo:d, 1)"))
            .isEqualTo("//bar:e //bar:f //foo:b //foo:c //foo:d")
        Truth.assertThat(evalToString("rdeps(siblings(//foo:d), //foo:d, 1)"))
            .isEqualTo("//foo:b //foo:c //foo:d")
        // 'same_pkg_direct_rdeps(//foo:d)' is supposed to have the same semantics as
        // 'rdeps(siblings(//foo:d), //foo:d, 1) - //foo:d'
        Truth.assertThat(evalToString("same_pkg_direct_rdeps(//foo:d)")).isEqualTo("//foo:b //foo:c")
    }

    @Test
    @Throws(Exception::class)
    open fun testSiblings_matchesTargetNamedAll() {
        writeFile(
            "foo/BUILD",
            """
        # NOTE: target named 'all' collides with, takes precedence over the ':all' wildcard
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'all')
        foo_library(name = 'ball')
        foo_library(name = 'call')
        foo_library(name = 'doll')
        
        """.trimIndent()
        )
        Truth.assertThat(evalToString("//foo:all")).isEqualTo("//foo:all")
        Truth.assertThat(evalToString("kind(' rule', siblings(//foo:BUILD))"))
            .isEqualTo("//foo:all //foo:ball //foo:call //foo:doll")
    }

    // Explicit test for the interaction of 'siblings' on operands coming from 'buildfiles' or
    // 'loadfiles'. The behavior here of treating a load'd .bzl file as coming from the package
    // loading it, rather than the package to which it belongs, is unfortunate, but it's the only
    // thing blaze can do with the unfortunate implementation details of 'buildfiles' and 'loadfiles'
    // (see FakeLoadTarget and other tests dealing with these functions).
    @Test
    @Throws(Exception::class)
    open fun testSiblings_withBuildfiles() {
        writeFile(
            "foo/BUILD",
            """
        load('//bar:bar.bzl', 'x')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'foo')
        
        """.trimIndent()
        )
        writeFile(
            "bar/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar')"
        )
        writeFile("bar/bar.bzl", "x = 42")
        Truth.assertThat(evalToString("siblings(buildfiles(//foo:foo))")).isEqualTo("//foo:BUILD //foo:foo")
    }

    @Test
    @Throws(Exception::class)
    open fun testSamePackageRdeps_simple() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', srcs = ['A.java'])
        foo_library(name = 'b', srcs = ['B.java'], deps = [':a'])
        foo_library(name = 'c', srcs = ['C.java'], deps = [':b'])
        
        """.trimIndent()
        )
        Truth.assertThat(evalToString("same_pkg_direct_rdeps(//foo:A.java)")).isEqualTo("//foo:a")
    }

    @Test
    @Throws(Exception::class)
    open fun testSamePackageRdeps_duplicate() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', srcs = ['A.java'])
        foo_library(name = 'b', srcs = ['B.java'], deps = [':a'])
        foo_library(name = 'c', srcs = ['C.java'], deps = [':b'])
        
        """.trimIndent()
        )
        Truth.assertThat(evalToString("same_pkg_direct_rdeps(//foo:A.java + //foo:A.java)"))
            .isEqualTo("//foo:a")
    }

    @Test
    @Throws(Exception::class)
    open fun testSamePackageRdeps_two() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', deps = [':b'])
        foo_library(name = 'b', deps = [':c', ':d'])
        foo_library(name = 'c', deps = [':d'])
        foo_library(name = 'd')
        
        """.trimIndent()
        )
        writeFile(
            "bar/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'e', deps = ['//foo:d'])
        foo_library(name = 'f', deps = ['//foo:d'])
        
        """.trimIndent()
        )
        Truth.assertThat(evalToString("kind(rule, same_pkg_direct_rdeps(//foo:d))"))
            .isEqualTo("//foo:b //foo:c")
    }

    @Test
    @Throws(Exception::class)
    open fun testSamePackageRdeps_twoPackages() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', srcs = ['A.java'])
        foo_library(name = 'b', srcs = ['B.java'], deps = [':a'])
        foo_library(name = 'c', srcs = ['C.java'], deps = [':b'])
        
        """.trimIndent()
        )
        // //bar:d directly depends on //foo:a but is in the wrong package
        writeFile(
            "bar/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'd', srcs = ['D.java'], deps = ['//foo:a'])"
        )
        Truth.assertThat(evalToString("kind(rule, same_pkg_direct_rdeps(//foo:a))")).isEqualTo("//foo:b")
    }

    @Test
    @Throws(Exception::class)
    open fun testSamePackageRdeps_crissCross() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', srcs = ['A.java'])
        foo_library(name = 'b', srcs = ['B.java'], deps = ['//bar:a'])
        
        """.trimIndent()
        )
        writeFile(
            "bar/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', srcs = ['A.java'])
        foo_library(name = 'b', srcs = ['B.java'], deps = ['//foo:a'])
        
        """.trimIndent()
        )
        Truth.assertThat(evalToString("kind(rule, same_pkg_direct_rdeps(//foo:a + //bar:a))")).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    open fun testVisibleWithNonPackageGroupVisibility() {
        writeFile(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'foo', visibility = ['//bar:bar'])"
        )
        writeFile(
            "bar/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar')"
        )
        Truth.assertThat(evalToString("visible(//bar:bar, //foo:foo)")).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    open fun testVisibleWithPackageGroupWithNonPackageGroupIncludes() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'foo', visibility = [':pg'])
        package_group(name = 'pg', includes = ['//bar:bar'])
        
        """.trimIndent()
        )
        writeFile(
            "bar/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar')"
        )
        Truth.assertThat(evalToString("visible(//bar:bar, //foo:foo)")).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun testDeepNestedLet() {
        writeFile(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'foo')"
        )

        // We used to get a StackOverflowError at this depth. We're still vulnerable to stack overflows
        // at higher depths, due to how the query engine works.
        val nestingDepth = 500
        val queryString: String =
            Joiner.on(" + ").join(Collections.nCopies<String?>(nestingDepth, "let x = //foo:foo in \$x"))

        Truth.assertThat(evalToString(queryString)).isEqualTo("//foo:foo")
    }

    @Test
    @Throws(Exception::class)
    fun testUnsuccessfulInnerFutureInNestedLetTransformAsyncFastPath() {
        // Not actually needed for the behavior being tested, but needed for the cquery and aquery test
        // subclasses that infer and load a universe.
        writeFile(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'foo')"
        )
        val result =
            evalThrows("let x = let y = //foo in \$nope in \$x",  /* unconditionallyThrows= */true)
        Truth.assertThat(result.message).contains("undefined variable 'nope'")
        Truth.assertThat(result.message).doesNotContain("java.lang.IllegalStateException")
        assertQueryCode(result.getFailureDetail(), Query.Code.VARIABLE_UNDEFINED)
    }

    @Test
    @Throws(Exception::class)
    fun testUnconditionalQueryException() {
        // The query expression being evaluated needs to be of the form "e1 + e2", where evaluation of
        // "e1" throws a QueryException even in keepGoing mode. See cl/141772584.
        writeFile(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'foo')"
        )
        val result =
            evalThrows("some(//foo - //foo) + //foo",  /* unconditionallyThrows= */true)
        Truth.assertThat(result.message).isEqualTo("argument set is empty")
        assertQueryCode(result.getFailureDetail(), Query.Code.ARGUMENTS_MISSING)
    }

    @Test
    @Throws(Exception::class)
    open fun testNoImplicitDeps_computedDefault() {
        // This rule cannot be defined in Starlark because the latter requires attributes with a
        // computed default to be private.
        val computedDefaultRule: MockRule =
            MockRule {
                MockRule.define(
                    "computed_default_rule",
                    attr("use_default", Type.BOOLEAN),
                    attr("dep", BuildType.LABEL)
                        .allowedFileTypes(FileTypeSet.ANY_FILE)
                        .value(
                            object : ComputedDefault("use_default") {
                                public override fun getDefault(rule: AttributeMap): Any? {
                                    return@MockRule if (rule.get("use_default", Type.BOOLEAN))
                                        Label.parseCanonicalUnchecked("//x:default")
                                    else
                                        null
                                }
                            })
                )
            }

        helper!!.useRuleClassProvider(setRuleClassProviders(computedDefaultRule).build())

        writeFile(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        computed_default_rule(name='x1')
        computed_default_rule(name='x2', use_default = True)
        computed_default_rule(name='x3', dep = ':custom')
        computed_default_rule(name='x4', dep = ':custom', use_default = True)
        computed_default_rule(name='x5', dep = '//x:default')
        computed_default_rule(name='x6', dep = '//x:default', use_default = True)
        cc_binary(name='default')
        cc_binary(name='custom')
        
        """.trimIndent()
        )

        assertDependsNotFiltered("//x:x1", "//x:default")
        assertDependsFiltered("//x:x2", "//x:default")
        assertDependsFiltered("//x:x3", "//x:custom")
        assertDependsFiltered("//x:x4", "//x:custom")
        assertDependsFiltered("//x:x5", "//x:default")
        assertDependsFiltered("//x:x6", "//x:default")

        assertDependsNotFiltered("//x:x1", "//x:default", QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        assertDependsNotFiltered("//x:x2", "//x:default", QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        assertDependsFiltered("//x:x3", "//x:custom", QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        assertDependsFiltered("//x:x4", "//x:custom", QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        assertDependsFiltered("//x:x5", "//x:default", QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        assertDependsFiltered("//x:x6", "//x:default", QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
    }

    @Throws(Exception::class)
    private fun assertDependsNotFiltered(from: String, to: String?, vararg settings: QueryEnvironment.Setting?) {
        val fromDeps = "deps(" + from + ")"
        assertEqualsFiltered(fromDeps, fromDeps + '-' + to, *settings)
    }

    @Throws(Exception::class)
    private fun assertDependsFiltered(from: String, to: String, vararg settings: QueryEnvironment.Setting?) {
        val fromDeps = "deps(" + from + ")"
        assertEqualsFiltered(to, fromDeps + '^' + to, *settings)
    }

    @Throws(Exception::class)
    protected fun writeBzlmodBuildFiles() {
        helper!!.overwriteFile(
            "MODULE.bazel", "bazel_dep(name= 'repo', version='1.0', repo_name='my_repo')"
        )
        helper!!.overwriteFile(
            "BUILD",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "foo_binary(",
            "name='rinne',",
            "srcs=['rinne.sh'],",
            "deps=['@my_repo//a:x','@my_repo//a/b:p']",
            ")"
        )
        helper!!.addModule(
            ModuleKey("repo", Version.parse("1.0")), "module(name = 'repo', version = '1.0')"
        )
        writeFile(helper!!.moduleRoot.getRelative("repo+1.0/REPO.bazel").getPathString(), "")
        writeFile(
            helper!!.moduleRoot.getRelative("repo+1.0/a/BUILD").getPathString(),
            "exports_files(['x', 'y', 'z'])",
            "filegroup(name = 'a_shar')"
        )
        writeFile(
            helper!!.moduleRoot.getRelative("repo+1.0/a/b/BUILD").getPathString(),
            "exports_files(['p', 'q'])",
            "filegroup(name = 'a_b_shar')"
        )
        val mapping: RepositoryMapping? =
            RepositoryMapping.create(
                ImmutableMap.of<K?, V?>("my_repo", RepositoryName.create("repo+")), RepositoryName.MAIN
            )
        helper!!.setMainRepoTargetParser(mapping)
    }

    @Test
    @Throws(Exception::class)
    fun testExternalRepo_allTargetsInPackage() {
        writeBzlmodBuildFiles()
        Truth.assertThat(evalToString("@my_repo//a/b:*")).isEqualTo(REPO_AB_ALL)
        Truth.assertThat(evalToString("@my_repo//a:*")).isEqualTo(REPO_A_ALL)
    }

    @Test
    @Throws(Exception::class)
    fun testExternalRepo_allTargetsBelow() {
        writeBzlmodBuildFiles()
        Truth.assertThat(evalToString("@my_repo//...:*")).isEqualTo(REPO_A_AB_ALL)
        Truth.assertThat(evalToString("@my_repo//a/...")).isEqualTo(REPO_A_AB_RULES)
        Truth.assertThat(evalToString("@my_repo//a/b/...")).isEqualTo(REPO_AB_RULES)
    }

    @Test
    @Throws(Exception::class)
    fun testLabelFlagDefaultAppearsInDepsQuery() {
        writeFile(
            "donut/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'thief', srcs = ['thief.sh'])
        label_flag(name = 'myflag', build_setting_default = ':thief')
        
        """.trimIndent()
        )

        Truth.assertThat(evalToString("deps(//donut:myflag, 1)" + this.dependencyCorrectionWithGen))
            .isEqualTo("//donut:myflag //donut:thief")
    }

    @Test
    @Throws(Exception::class)
    fun testLabelSettingDefaultAppearsInDepsQuery() {
        writeFile(
            "donut/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'thief', srcs = ['thief.sh'])
        label_setting(name = 'mysetting', build_setting_default = ':thief')
        
        """.trimIndent()
        )

        Truth.assertThat(evalToString("deps(//donut:mysetting, 1)" + this.dependencyCorrectionWithGen))
            .isEqualTo("//donut:mysetting //donut:thief")
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkRuleToolchainDeps() {
        overwriteFile("MODULE.bazel", "register_toolchains('//bar:all')")
        writeFile(
            "foo/BUILD",
            """
        load(":foo.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )
        writeFile(
            "foo/foo.bzl",
            """
        def noop(ctx):
          pass

        my_rule = rule(
          implementation = noop,
          toolchains = ['//bar:bar_type'],
        )
        
        """.trimIndent()
        )
        writeFile(
            "bar/BUILD",
            """
        load(":bar.bzl", "test_toolchain")

        toolchain_type(name = "bar_type")
        toolchain_type(name = "other_type")
        test_toolchain(name = "bar_impl")
        test_toolchain(name = "other_impl")

        toolchain(
            name = "bar_toolchain",
            toolchain = ":bar_impl",
            toolchain_type = ":bar_type",
        )

        toolchain(
            name = "other_toolchain",
            toolchain = ":other_impl",
            toolchain_type = ":other_type",
        )
        
        """.trimIndent()
        )
        writeFile(
            "bar/bar.bzl",
            """
        def _impl(ctx):
            toolchain = platform_common.ToolchainInfo()
            return [toolchain]

        test_toolchain = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )

        // Use contains (instead of matching full string) because post-analysis query implementation
        // will contain resolved toolchain, whereas pre-analysis query will not.
        Truth.assertThat(evalToString("deps(//foo, 1)")).contains("//bar:bar_type")
        // Test unbounded deps, too.
        Truth.assertThat(evalToString("deps(//foo)")).contains("//bar:bar_type")

        helper!!.setQuerySettings(QueryEnvironment.Setting.NO_IMPLICIT_DEPS)

        Truth.assertThat(evalToString("deps(//foo, 1)")).doesNotContain("//bar:bar_type")
        Truth.assertThat(evalToString("deps(//foo)")).doesNotContain("//bar:bar_type")
    }

    @Test
    @Throws(Exception::class)
    fun testNativeRuleToolchainDeps() {
        writeFile(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(name = "cclib")
        
        """.trimIndent()
        )

        Truth.assertThat(evalToString("deps(//foo:cclib)")).contains("//tools/cpp:toolchain_type")

        helper!!.setQuerySettings(QueryEnvironment.Setting.NO_IMPLICIT_DEPS)

        Truth.assertThat(evalToString("deps(//foo:cclib)")).doesNotContain("//tools/cpp:toolchain_type")
    }

    /**
     * A helper interface that allows creating a bunch of BUILD files and running queries against
     * them. We use this rather than the existing FoundationTestCase / BuildTestCase infrastructure to
     * allow running the same test against multiple query implementations (like the deps server).
     */
    interface QueryHelper<T> {
        /** Basic set-up; this is called once at the beginning of a test, before anything else.  */
        @Throws(Exception::class)
        fun setUp()

        fun cleanUp()

        var isKeepGoing: Boolean

        fun setOrderedResults(orderedResults: Boolean)

        fun setUniverseScope(universeScope: String?)

        fun reportsUniverseEvaluationErrors(): Boolean {
            return true
        }

        /** Re-initializes the query environment with the given settings.  */
        fun setQuerySettings(vararg settings: QueryEnvironment.Setting?)

        val rootDirectory: Path?

        val ignoredSubdirectoriesFile: PathFragment?

        /** Removes all files below the package root.  */
        @Throws(IOException::class)
        fun clearAllFiles()

        /** Changes the rule class provider to be used for the query evaluation.  */
        @Throws(Exception::class)
        fun useRuleClassProvider(ruleClassProvider: ConfiguredRuleClassProvider?)

        /**
         * Create a scratch file in the given filesystem, with the given pathName, consisting of a set
         * of lines. The method returns a Path instance for the scratch file.
         */
        @Throws(IOException::class)
        fun writeFile(fileName: String?, vararg lines: String?)

        /** Like `writeFile`, but the file is written unconditionally.  */
        @Throws(IOException::class)
        fun overwriteFile(fileName: String?, vararg lines: String?)

        /**
         * Create a symbolic link in the given filesystem from `link` that points to `target`.
         */
        @Throws(IOException::class)
        fun ensureSymbolicLink(link: String?, target: String?)

        /** Return an instance of [QueryEnvironment] according to set-up rules.  */
        val queryEnvironment: QueryEnvironment<T?>

        /** Evaluates the given query and returns the result. Query is expected to have valid syntax.  */
        @Throws(Exception::class)
        fun evaluateQuery(query: String?): ResultAndTargets<T?>

        @Throws(Exception::class)
        fun evaluateQueryRaw(query: String?): MutableSet<T?> {
            return evaluateQuery(query).resultSet
        }

        val toolsRepository: RepositoryName
            get() = RepositoryName.MAIN

        /**
         * Contains both the results of the query (Like if there were errors, empty result, etc.) and
         * the actual targets returned by the query.
         */
        class ResultAndTargets<T>(queryEvalResult: QueryEvalResult?, results: MutableSet<T?>) {
            private val queryEvalResult: QueryEvalResult?
            val resultSet: MutableSet<T?>

            init {
                this.queryEvalResult = queryEvalResult
                this.resultSet = results
            }

            fun getQueryEvalResult(): QueryEvalResult? {
                return queryEvalResult
            }
        }

        /**
         * Clears the event storage that is used for [.assertContainsEvent] and [ ][.getFirstEvent].
         */
        fun clearEvents()

        /** Asserts that the event storage contains an event with the given message text.  */
        fun assertContainsEvent(expectedMessage: String?)

        /** Asserts that the event storage does not contain an event with the given message text.  */
        fun assertDoesNotContainEvent(notExpectedMessage: String?)

        /** Returns the message text for the first event in the event storage.  */
        val firstEvent: String?

        val events: Iterable<Event?>?

        /**
         * If this implementation is backed by a package cache, this asserts that the given package is
         * not present in the cache.
         */
        @Throws(Exception::class)
        fun assertPackageNotLoaded(packageName: String?)

        fun getLabel(target: T?): String?

        fun addModule(key: ModuleKey?, vararg moduleFileLines: String?)

        val moduleRoot: Path?

        fun setMainRepoTargetParser(mapping: RepositoryMapping?)

        @Throws(AbruptExitException::class, InterruptedException::class)
        fun maybeHandleDiffs()
    }

    companion object {
        protected val EMPTY: ImmutableSet<*> = ImmutableSet.of<Any?>()

        protected open val defaultUniverseScope: String = "//...:*"
            /**
             * By default, we load the universe (of both rules and files) for our tests. If a specific test or
             * subclass requires that only a subset of the universe is loaded, it may override this default
             * and/or specify a per-test method universe scope.
             */
            get() = Companion.field

        protected fun assertPackageLoadingCode(result: ResultAndTargets<Target?>, code: Code?) {
            val failureDetail: FailureDetail =
                result.getQueryEvalResult().detailedExitCode.getFailureDetail()
            assertThat(failureDetail).isNotNull()
            Companion.assertPackageLoadingCode(failureDetail, code)
        }

        protected fun assertPackageLoadingCode(failureDetail: FailureDetail, code: Code?) {
            assertThat(failureDetail.getPackageLoading().getCode()).isEqualTo(code)
        }

        protected fun assertQueryCode(failureDetail: FailureDetail, code: Query.Code?) {
            assertThat(failureDetail.getQuery().getCode()).isEqualTo(code)
        }

        protected const val AB_FILES: String = "//a/b:BUILD //a/b:p //a/b:q"
        protected const val A_FILES: String = "//a:BUILD //a:x //a:y //a:z"
        protected val A_AB_FILES: String = AB_FILES + " " + A_FILES

        private fun <T> createVisitor(writer: PrintWriter): DotOutputVisitor<T?> {
            return DotOutputVisitor<T?>(writer, "\n", LabelSerializer { node: Node<T?>? -> node!!.label.toString() })
        }

        protected const val REPO_A_RULES: String = "@@repo+//a:a_shar"
        protected const val REPO_AB_RULES: String = "@@repo+//a/b:a_b_shar"
        protected const val REPO_AB_ALL: String =
            "@@repo+//a/b:BUILD @@repo+//a/b:a_b_shar @@repo+//a/b:p @@repo+//a/b:q"
        protected const val REPO_A_ALL: String =
            "@@repo+//a:BUILD @@repo+//a:a_shar @@repo+//a:x @@repo+//a:y @@repo+//a:z"
        protected val REPO_A_AB_RULES: String = REPO_AB_RULES + " " + REPO_A_RULES
        protected val REPO_A_AB_ALL: String = REPO_AB_ALL + " " + REPO_A_ALL
    }
}
