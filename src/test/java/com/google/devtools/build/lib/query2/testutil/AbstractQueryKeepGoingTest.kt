// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.cmdline.Label
import com.google.devtools.build.lib.query2.engine.QueryEnvironment
import com.google.devtools.build.lib.query2.engine.QueryException
import com.google.devtools.build.lib.query2.testutil.AbstractQueryTest.assertPackageLoadingCode
import org.junit.Test

/**
 * Tests for query evaluation when keep_going is enabled. It covers the QueryEvalTest and adds
 * additional tests that are keep_going-specific.
 */
abstract class AbstractQueryKeepGoingTest : QueryTest() {
    @Before
    @Throws(Exception::class)
    fun setKeepGoing() {
        helper.setKeepGoing(true)
    }

    // Like eval(), but asserts that evaluation completes normally, with an error.
    // Events should be checked with assertContainsEvent().
    @Throws(Exception::class)
    protected fun evalFail(query: String): ResultAndTargets<Target?> {
        val result: ResultAndTargets<Target?> = helper.evaluateQuery(query)
        Truth.assertWithMessage("evaluateQuery succeeded: %s", query)
            .that(result.getQueryEvalResult().success)
            .isFalse()
        return result
    }

    // Like eval(), but makes no assertions about whether evaluation completes with an error.
    // Because the query helper reuses its AbstractBlazeQueryEnvironment, BlazeQueryEnvironment-based
    // implementations will perform graph evaluations using the same memoizing evaluator, which reuses
    // the same EmittedEventState, causing later evaluations that emit the same errors to not count as
    // failures. SkyQueryEnvironment-based implementations do not do this, and may report that later
    // evaluations have failed.
    // In either case, events should be checked with assertContainsEvent().
    // TODO(bazel-team): it is probably unintentional that BlazeQueryEnvironment-based evaluations'
    // error state is sensitive to prior evaluations. Tests that use this method should be fixed when
    // there's a chance to fix the state that's retained across queries because of the query helper
    // and BlazeQueryEnvironment.
    @Throws(Exception::class)
    protected fun evalMaybe(query: String?): MutableSet<Target?>? {
        return helper.evaluateQuery(query).getResultSet()
    }

    @Throws(Exception::class)
    override fun evalThrows(query: String, unconditionallyThrows: Boolean): EvalThrowsResult {
        // This method can be called in both keep_going and nokeep_going modes: expect either an
        // exception or an error message.
        try {
            val result: ResultAndTargets<Target?> = evalFail(query)
            Truth.assertThat(helper.isKeepGoing()).isTrue()
            val msg: String? =
                helper
                    .getFirstEvent()
                    .replace("^Skipping '[^']+': ".toRegex(), "")
                    .replace("Evaluation of query \"[^\"]+\" failed: ".toRegex(), "")
            return EvalThrowsResult(
                msg, result.getQueryEvalResult().detailedExitCode.getFailureDetail()
            )
        } catch (e: QueryException) {
            // TODO(ulfjack): Even in keep_going mode, the query engine sometimes throws a QueryException.
            // Remove the guard and fix the problems.
            if (!unconditionallyThrows) {
                Truth.assertThat(helper.isKeepGoing()).isFalse()
            }
            val msg = if (e.cause != null) e.cause!!.message else e.message
            return EvalThrowsResult(msg, e.getFailureDetail())
        }
    }

    // Regression test for bug #2482284:
    // "blaze query mypackage:* does not report targets that cross package boundaries"
    @Test
    @Throws(Exception::class)
    fun testErrorWhenResultContainsLabelsCrossingSubpackage() {
        writeFile(
            "pear/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "plum/peach",
            srcs = ["peach.sh"],
        )

        foo_library(
            name = "apple",
            srcs = ["apple.sh"],
        )
        
        """.trimIndent()
        )
        writeFile("pear/plum/BUILD")

        val resultAndTargets: ResultAndTargets<Target?> = evalFail("//pear:apple")
        assertContainsEvent("is invalid because 'pear/plum' is a subpackage")
        if (helper.reportsUniverseEvaluationErrors()) {
            assertPackageLoadingCode(resultAndTargets, Code.LABEL_CROSSES_PACKAGE_BOUNDARY)
        } else {
            AbstractQueryTest.Companion.assertQueryCode(
                resultAndTargets.getQueryEvalResult().detailedExitCode.getFailureDetail(),
                Query.Code.BUILD_FILE_ERROR
            )
        }
    }

    @Test
    @Throws(Exception::class)
    fun testErrorWhenWildcardResultContainsLabelsCrossingSubpackage() {
        writeFile(
            "pear/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "plum/peach",
            srcs = ["peach.sh"],
        )

        foo_library(
            name = "apple",
            srcs = ["apple.sh"],
        )
        
        """.trimIndent()
        )
        writeFile("pear/plum/BUILD")

        val resultAndTargets: ResultAndTargets<Target?> = evalFail("//pear:all")
        assertContainsEvent("is invalid because 'pear/plum' is a subpackage")
        if (helper.reportsUniverseEvaluationErrors()) {
            assertPackageLoadingCode(resultAndTargets, Code.LABEL_CROSSES_PACKAGE_BOUNDARY)
        } else {
            AbstractQueryTest.Companion.assertQueryCode(
                resultAndTargets.getQueryEvalResult().detailedExitCode.getFailureDetail(),
                Query.Code.BUILD_FILE_ERROR
            )
        }
    }

    @Throws(Exception::class)
    override fun writeBuildFiles3() {
        writeFile(
            "a/BUILD",
            """
        genrule(
            name = "a",
            srcs = [
                "//b",
                "//c",
            ],
            outs = ["out"],
            cmd = ":",
        )

        exports_files(["a2"])
        
        """.trimIndent()
        )
        writeFile("b/BUILD", "genrule(name='b', srcs=['//d'], outs=['out'], cmd=':')")
        writeFile("c/BUILD", "genrule(name='c', srcs=['//d'], outs=['out'], cmd=':')")
        writeFile("d/BUILD", "exports_files(['d'])")
    }

    @Throws(Exception::class)
    protected fun assertNoFailFast(
        errorMsg: String?, checkFailureDetail: Boolean, keepGoingErrorMsg: String?
    ) {
        writeFile(
            "missingdep/BUILD",
            """
        load('@rules_cc//cc:cc_library.bzl', 'cc_library')
        cc_library(
            name = "missingdep",
            deps = ["//i/do/not/exist"],
        )
        
        """.trimIndent()
        )

        helper.setKeepGoing(false)
        val throwsResult1 = evalThrows("deps(//missingdep)", false)
        Truth.assertThat(throwsResult1.getMessage()).contains(errorMsg)
        if (checkFailureDetail) {
            assertPackageLoadingCode(throwsResult1.getFailureDetail(), Code.BUILD_FILE_MISSING)
        }

        // (1) --keep_going.
        helper.clearEvents()
        helper.setKeepGoing(true)
        // partial results
        val failResult: ResultAndTargets<Target?> =
            evalFail("deps(//missingdep)" + TestConstants.CC_DEPENDENCY_CORRECTION)
        Truth.assertThat(failResult.getResultSet()).isEqualTo(eval("//missingdep"))
        assertContainsEvent("Evaluation of query \"deps(//missingdep)\" failed: " + keepGoingErrorMsg)
        if (checkFailureDetail) {
            assertPackageLoadingCode(failResult, Code.BUILD_FILE_MISSING)
        }

        // (2) --nokeep_going.
        helper.setKeepGoing(false)
        val throwsResult2 = evalThrows("deps(//missingdep)", false)
        Truth.assertThat(throwsResult2.getMessage()).contains(errorMsg) // no results
        if (checkFailureDetail) {
            assertPackageLoadingCode(throwsResult2.getFailureDetail(), Code.BUILD_FILE_MISSING)
        }
    }

    // Regression test for bug #1234015, "blaze query --keep_going doesn't
    // always work".  Previously, any failure in a labels() expression would
    // cause results to be suppressed.  Now, partial results are printed.
    @Test
    @Throws(Exception::class)
    fun testNoFailFastOnLabelsExpression() {
        writeFile(
            "bad/BUILD", "genrule(name='bad', srcs=['x', '//missing', 'y'], outs=['out'], cmd=':')"
        )

        val result: MutableSet<Target?>? = evalFail("labels(srcs, //bad)").getResultSet()
        assertContainsEvent("no such package 'missing': " + "BUILD file not found")
        assertContainsEvent("--keep_going specified, ignoring errors. Results may be inaccurate")
        Truth.assertThat(result).isEqualTo(eval("//bad:x + //bad:y")) // partial results
    }

    // Ensure that --keep_going distinguishes malformed target literals from
    // good ones that happen to refer to bad BUILD files.
    @Test
    @Throws(Exception::class)
    fun testBadBuildFileKeepGoing() {
        writeFile("bad/BUILD", "blah blah blah")
        val result: ResultAndTargets<Target?> = evalFail("bad:*")
        if (helper.reportsUniverseEvaluationErrors()) {
            assertPackageLoadingCode(result, Code.SYNTAX_ERROR)
        } else {
            AbstractQueryTest.Companion.assertQueryCode(
                result.getQueryEvalResult().detailedExitCode.getFailureDetail(),
                Query.Code.BUILD_FILE_ERROR
            )
        }
        assertContainsEvent("syntax error at 'blah'")
        assertContainsEvent("--keep_going specified, ignoring errors. Results may be inaccurate")

        Truth.assertThat(result.getResultSet()).isEqualTo(evalMaybe("//bad:BUILD")) // partial results
    }

    @Test
    @Throws(Exception::class)
    fun testStrictTestSuiteWithFileAndKeepGoing() {
        helper.setQuerySettings(QueryEnvironment.Setting.TESTS_EXPRESSION_STRICT)
        writeFile("x/BUILD", "test_suite(name='a', tests=['a.txt'])")
        Truth.assertThat(evalFail("tests(//x:a)").getResultSet()).isEmpty()
        assertContainsEvent(
            "The label '//x:a.txt' in the test_suite '//x:a' does not refer to a test "
                    + "or test_suite rule!"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testQueryAllForBrokenPackage() {
        writeFile(
            "x/BUILD",
            """
        filegroup(name = "a")

        x = 1 // 0

        filegroup(name = "c")
        
        """.trimIndent() // not executed
        )
        Truth.assertThat(evalFail("//x:all").getResultSet()).hasSize(1)
        assertContainsEvent("division by zero")
        assertContainsEvent("Results may be inaccurate")
    }

    @Test
    @Throws(Exception::class)
    fun testQueryDotDotDotForBrokenPackage() {
        writeFile(
            "x/BUILD",
            """
        filegroup(name = "a")

        x = 1 // 0

        filegroup(name = "c")
        
        """.trimIndent() // not executed
        )
        Truth.assertThat(evalFail("//x/...").getResultSet()).hasSize(1)
        assertContainsEvent("division by zero")
        assertContainsEvent("Results may be inaccurate")
    }

    @Test
    @Throws(Exception::class)
    fun testNonExistentDotDotDot() {
        Truth.assertThat(evalFail("//does_not_exist/...").getResultSet()).isEmpty()
        assertContainsEvent("no targets found beneath 'does_not_exist'")
        assertContainsEvent("Results may be inaccurate")
    }

    @Test
    @Throws(Exception::class)
    fun testErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile_TBD() {
        runTestErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile("//foo/...", 1)
    }

    @Test
    @Throws(Exception::class)
    fun testErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile_TIP() {
        runTestErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile("//foo/foo:all", 0)
    }

    @Test
    @Throws(Exception::class)
    fun testErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile_ST() {
        runTestErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile("//foo/foo:banana", 0)
    }

    @Test
    @Throws(Exception::class)
    fun testErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile_IPAT() {
        runTestErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile("foo/foo/banana", 0)
    }

    @Throws(Exception::class)
    private fun runTestErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile(
        queryExpression: String, numExpectedTargets: Int
    ) {
        if (helper.reportsUniverseEvaluationErrors()) {
            // This family of test cases are interesting only for query environments that don't report
            // universe evaluation errors. This way we can assert that any error message came from query
            // evaluation.
            return
        }

        // Starlark imports must refer to files in packages. When the file being imported exists, but
        // it has no containing package, an error should be reported for queries that involve the
        // package containing that import.

        // The package "//foo" can be loaded and has no errors.
        writeFile(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name='apple', srcs=['apple.sh'])"
        )

        // The package "//foo/foo" has a load statement that fails. Its ":banana" target does not depend
        // on the load, but because the package failed to load, it does not exist.
        writeFile(
            "foo/foo/BUILD",
            """
        load("//bar:lib.bzl", "myfunc")
        load('//test_defs:foo_library.bzl', 'foo_library')

        foo_library(
            name = "banana",
            srcs = ["banana.sh"],
        )
        
        """.trimIndent()
        )

        // This Starlark file is fine, but it has no containing package, so it can't be loaded.
        writeFile("bar/lib.bzl", "custom_rule(name = 'myfunc')")

        Truth.assertThat(evalFail(queryExpression).getResultSet()).hasSize(numExpectedTargets)

        val expectedError =
            "error loading package 'foo/foo': Every .bzl file must have a corresponding package"
        assertContainsEvent(expectedError)
    }

    @Test
    @Throws(Exception::class)
    fun testPluralErrorsReportedWhenStarlarkLoadRefersToMissingPkgExistingFile() {
        // This test does not yet pass for some SkyQueryEnvironment-specific QueryExpression
        // implementations.

        // Like runTestErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile, but with multiple
        // packages in error, testing that each packages' error is reported.

        // The package "//foo" can be loaded and has no errors.

        writeFile(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name='apple', srcs=['apple.sh'])"
        )

        // The packages "//foo/foo" and "//foo/foo2" each have a load statement that fails. The
        // ":banana" targets do not depend on the load, but because the packages failed to load, they do
        // not exist.
        writeFile(
            "foo/foo/BUILD",
            """
        load("//bar:lib.bzl", "myfunc")
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "banana",
            srcs = ["banana.sh"],
        )
        
        """.trimIndent()
        )
        writeFile(
            "foo/foo2/BUILD",
            """
        load("//bar:lib.bzl", "myfunc")
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "banana",
            srcs = ["banana.sh"],
        )
        
        """.trimIndent()
        )

        // This Starlark file is fine, but it has no containing package, so it can't be loaded.
        writeFile("bar/lib.bzl", "custom_rule(name = 'myfunc')")

        Truth.assertThat(evalFail("//foo/foo:*").getResultSet()).isEmpty()
        val expectedError =
            ("error loading package 'foo/foo': Every .bzl file must have a corresponding package, "
                    + "but '//bar:lib.bzl' does not have one")
        assertContainsEvent(expectedError)
        helper.clearEvents()

        Truth.assertThat(evalFail("//foo/foo2:*").getResultSet()).isEmpty()
        val expectedError2 =
            ("error loading package 'foo/foo2': Every .bzl file must have a corresponding package, "
                    + "but '//bar:lib.bzl' does not have one")
        assertContainsEvent(expectedError2)
        helper.clearEvents()

        Truth.assertThat(evalFail("//foo/foo:* + //foo/foo2:*").getResultSet()).isEmpty()
        assertContainsEvent(expectedError)
        assertContainsEvent(expectedError2)
    }

    @Test
    @Throws(Exception::class)
    fun testErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile_TBD() {
        runTestErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile("//foo/...", 1)
    }

    @Test
    @Throws(Exception::class)
    fun testErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile_TIP() {
        runTestErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile("//foo/foo:all", 0)
    }

    @Test
    @Throws(Exception::class)
    fun testErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile_ST() {
        runTestErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile("//foo/foo:banana", 0)
    }

    @Test
    @Throws(Exception::class)
    fun testErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile_IPAT() {
        runTestErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile("foo/foo/banana", 0)
    }

    @Throws(Exception::class)
    private fun runTestErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile(
        queryExpression: String, numExpectedTargets: Int
    ) {
        if (helper.reportsUniverseEvaluationErrors()) {
            // This family of test cases are interesting only for query environments that don't report
            // universe evaluation errors. This way we can assert that any error message came from query
            // evaluation.
            return
        }

        // Starlark imports must refer to files that exist, otherwise they will fail and an error should
        // be reported. How shocking!

        // The package "//foo" can be loaded and has no errors.
        writeFile(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name='apple', srcs=['apple.sh'])"
        )

        // The package "//foo/foo" has a load statement that fails. Its ":banana" target does not depend
        // on the load, but because the package failed to load, it does not exist.
        writeFile(
            "foo/foo/BUILD",
            """
        load("//bar:lib.bzl", "myfunc")
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "banana",
            srcs = ["banana.sh"],
        )
        
        """.trimIndent()
        )

        // The load statement in "//foo/foo" refers to an existing package, but the Starlark file is
        // missing.
        writeFile("bar/BUILD")

        Truth.assertThat(evalFail(queryExpression).getResultSet()).hasSize(numExpectedTargets)

        val expectedError =
            "error loading package 'foo/foo': cannot load '//bar:lib.bzl': no such file"
        assertContainsEvent(expectedError)
    }

    @Test
    @Throws(Exception::class)
    fun testErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle_TBD() {
        runTestErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle("//foo/...", 1)
    }

    @Test
    @Throws(Exception::class)
    fun testErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle_TIP() {
        runTestErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle("//foo/foo:all", 0)
    }

    @Test
    @Throws(Exception::class)
    fun testErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle_ST() {
        runTestErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle("//foo/foo:banana", 0)
    }

    @Test
    @Throws(Exception::class)
    fun testErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle_IPAT() {
        runTestErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle("foo/foo/banana", 0)
    }

    @Throws(Exception::class)
    private fun runTestErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle(
        queryExpression: String, numExpectedTargets: Int
    ) {
        if (helper.reportsUniverseEvaluationErrors()) {
            // This family of test cases are interesting only for query environments that don't report
            // universe evaluation errors. This way we can assert that any error message came from query
            // evaluation.
            return
        }

        // Starlark imports must refer to files that don't point into a symlink cycle, otherwise they
        // will fail and an error should be reported. Quite astonishing!

        // The package "//foo" can be loaded and has no errors.
        writeFile(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name='apple', srcs=['apple.sh'])"
        )

        // The package "//foo/foo" has a load statement that fails. Its ":banana" target does not depend
        // on the load, but because the package failed to load, it does not exist.
        writeFile(
            "foo/foo/BUILD",
            """
        load("//bar:lib.bzl", "myfunc")
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "banana",
            srcs = ["banana.sh"],
        )
        
        """.trimIndent()
        )

        // The load statement in "//foo/foo" refers to an existing package, but the Starlark file the
        // load statement refers to points into a symlink cycle.
        writeFile("bar/BUILD")
        ensureSymbolicLink("bar/lib.bzl", "bar/recursion")
        ensureSymbolicLink("bar/recursion", "bar/recursion")

        Truth.assertThat(evalFail(queryExpression).getResultSet()).hasSize(numExpectedTargets)

        val expectedError =
            ("error loading package 'foo/foo': Encountered error while reading extension file"
                    + " 'bar/lib.bzl': Symlink cycle")
        assertContainsEvent(expectedError)
    }

    @Test
    @Throws(Exception::class)
    fun testNoErrorReportedWhenUniverseIncludesBrokenPkgButQueryDoesNot() {
        if (helper.reportsUniverseEvaluationErrors()) {
            // This test case is interesting only for query environments that don't report universe
            // evaluation errors. This way we can assert that any error message came from query
            // evaluation.
            return
        }

        // The package "//foo" is healthy.
        writeFile(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name='apple', srcs=['apple.sh'])"
        )

        // The package "//baz" is not healthy: it contains a load statement referring to an unpackaged
        // Starlark file.
        writeFile(
            "baz/BUILD",
            """
        load("//bar:lib.bzl", "myfunc")
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "banana",
            srcs = ["banana.sh"],
        )
        
        """.trimIndent()
        )
        writeFile("bar/lib.bzl", "custom_rule(name = 'myfunc')")

        // Nevertheless, a query affecting just the healthy package emits no errors.
        Truth.assertThat(eval("//foo/...")).hasSize(1)
        assertDoesNotContainEvent("error loading package 'baz'")
    }

    @Test
    @Throws(Exception::class)
    override fun boundedRdepsWithError() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "foo",
            deps = [":dep"],
        )

        foo_library(
            name = "dep",
            deps = ["//bar:missing"],
        )
        
        """.trimIndent()
        )
        val targetResultAndTargets: ResultAndTargets<Target?> = evalFail("rdeps(//foo:foo, //foo:dep, 1)")
        Truth.assertThat(
            targetResultAndTargets.getResultSet().stream()
                .map<Any?> { t: Target? -> Label.print(t.getLabel()) }
                .collect(ImmutableSet.toImmutableSet<Any?>()))
            .containsExactly("//foo:dep", "//foo:foo")
        // Ideally we wouldn't print this irrelevant error (since //bar:missing is a dep of //foo:dep,
        // not an rdep), or make it fail the query.
        assertThat(targetResultAndTargets.getQueryEvalResult().detailedExitCode.getExitCode())
            .isEqualTo(ExitCode.BUILD_FAILURE)
        assertContainsEvent("no such package 'bar':")
    }

    @Test
    @Throws(Exception::class)
    fun testIgnoredSubdirectoryIsTBDQuery() {
        overwriteFile(helper.getIgnoredSubdirectoriesFile().getPathString(), "a/b")
        writeFile("a/BUILD", "filegroup(name = 'a')")
        writeFile("a/b/BUILD", "filegroup(name = 'a_b')")
        writeFile("a/b/c/BUILD", "filegroup(name = 'a_b_c')")

        // Ensure that modified files are invalidated in the skyframe. If a file has
        // already been read prior to the test's writes, this forces the query to
        // pick up the modified versions.
        helper.maybeHandleDiffs()

        val resultAndTargets: ResultAndTargets<Target?> = helper.evaluateQuery("//a/b/...")
        assertContainsEvent("Pattern '//a/b/...' was filtered out by ignored directory 'a/b'")
        Truth.assertThat(resultAndTargets.getQueryEvalResult().success).isTrue()
        Truth.assertThat(targetLabels(resultAndTargets.getResultSet())).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun bogusVisibility() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        package(default_visibility = ["//visibility:public"])

        foo_library(
            name = "a",
            visibility = [
                "//bad:visibility",
                "//bar:__pkg__",
            ],
        )

        foo_library(name = "b")

        foo_library(
            name = "c",
            visibility = ["//bad:visibility"],
        )
        
        """.trimIndent()
        )
        writeFile("bar/BUILD")
        val resultAndTargets: ResultAndTargets<Target?> =
            helper.evaluateQuery("visible(//bar:BUILD, //foo:all)")
        Truth.assertThat(resultAndTargets.getQueryEvalResult().success).isFalse()
        Truth.assertThat(targetLabels(resultAndTargets.getResultSet())).containsExactly("//foo:a", "//foo:b")
        assertContainsEvent("Invalid visibility label '//bad:visibility': no such package 'bad'")
        assertContainsEvent("--keep_going specified, ignoring errors. Results may be inaccurate")
    }
}
