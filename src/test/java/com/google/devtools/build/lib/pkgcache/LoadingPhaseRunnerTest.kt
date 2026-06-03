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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.packages.Attribute.attr

/** Tests for [SkyframeExecutor.loadTargetPatternsWithFilters].  */
@RunWith(TestParameterInjector::class)
class LoadingPhaseRunnerTest {
    private var tester: LoadingPhaseTester? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createLoadingPhaseTester() {
        tester = LoadingPhaseTester()
    }

    @Throws(java.lang.Exception::class)
    private fun assertCircularSymlinksDuringTargetParsing(targetPattern: String?, errorMessage: String?) {
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { tester!!.load(targetPattern) })
        tester!!.assertContainsError(errorMessage)
        val result: TargetPatternPhaseValue = tester!!.loadKeepGoing(targetPattern)
        assertThat(result.hasError()).isTrue()
    }

    private fun assertNoErrors(loadingResult: TargetPatternPhaseValue): TargetPatternPhaseValue {
        assertThat(loadingResult.hasError()).isFalse()
        assertThat(loadingResult.hasPostExpansionError()).isFalse()
        tester!!.assertNoEvents()
        return loadingResult
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSmoke() {
        tester!!.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])")
        val loadingResult: TargetPatternPhaseValue = assertNoErrors(tester!!.load("//base:hello"))
        assertThat(loadingResult.getTargetLabels())
            .containsExactlyElementsIn(getLabels("//base:hello"))
        assertThat(loadingResult.getTestsToRunLabels()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonExistentPackage() {
        val loadingResult: TargetPatternPhaseValue = tester!!.loadKeepGoing("//base:missing")
        assertThat(loadingResult.hasError()).isTrue()
        assertThat(loadingResult.hasPostExpansionError()).isFalse()
        assertThat(loadingResult.getTargetLabels()).isEmpty()
        assertThat(loadingResult.getTestsToRunLabels()).isNull()
        tester!!.assertContainsError("Skipping '//base:missing': no such package 'base'")
        tester!!.assertContainsWarning("Target pattern parsing failed.")
        val err: PatternExpandingError = tester!!.findPostOnce<T>(PatternExpandingError::class.java)
        assertThat(err.pattern).containsExactly("//base:missing")
    }

    @org.junit.Test
    fun testNonExistentPackageWithoutKeepGoing() {
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { tester!!.load("//does/not/exist") })
        val err: PatternExpandingError = tester!!.findPostOnce<T>(PatternExpandingError::class.java)
        assertThat(err.pattern).containsExactly("//does/not/exist")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonExistentTarget() {
        tester!!.addFile("base/BUILD")
        val loadingResult: TargetPatternPhaseValue = tester!!.loadKeepGoing("//base:missing")
        assertThat(loadingResult.hasError()).isTrue()
        assertThat(loadingResult.hasPostExpansionError()).isFalse()
        assertThat(loadingResult.getTargetLabels()).isEmpty()
        assertThat(loadingResult.getTestsToRunLabels()).isNull()
        tester!!.assertContainsError("Skipping '//base:missing': no such target '//base:missing'")
        tester!!.assertContainsWarning("Target pattern parsing failed.")
        val err: PatternExpandingError = tester!!.findPostOnce<T>(PatternExpandingError::class.java)
        assertThat(err.pattern).containsExactly("//base:missing")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExistingAndNonExistentTargetsWithKeepGoing() {
        tester!!.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])")
        tester!!.loadKeepGoing("//base:hello", "//base:missing")
        val err: PatternExpandingError = tester!!.findPostOnce<T>(PatternExpandingError::class.java)
        assertThat(err.pattern).containsExactly("//base:missing")
        val event: TargetParsingCompleteEvent = tester!!.findPostOnce<T>(TargetParsingCompleteEvent::class.java)
        assertThat(event.getOriginalTargetPattern()).containsExactly("//base:hello", "//base:missing")
        assertThat(event.getFailedTargetPatterns()).containsExactly("//base:missing")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRecursiveAllRules() {
        tester!!.addFile("base/BUILD", "filegroup(name = 'base', srcs = ['base.txt'])")
        tester!!.addFile("base/foo/BUILD", "filegroup(name = 'foo', srcs = ['foo.txt'])")
        tester!!.addFile("base/bar/BUILD", "filegroup(name = 'bar', srcs = ['bar.txt'])")
        var loadingResult: TargetPatternPhaseValue = tester!!.load("//base/...")
        assertThat(loadingResult.getTargetLabels())
            .containsExactlyElementsIn(getLabels("//base", "//base/foo", "//base/bar"))

        loadingResult = tester!!.load("//base/bar/...")
        assertThat(loadingResult.getTargetLabels()).containsExactlyElementsIn(getLabels("//base/bar"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRecursiveAllTargets() {
        tester!!.addFile("base/BUILD", "filegroup(name = 'base', srcs = ['base.txt'])")
        tester!!.addFile("base/foo/BUILD", "filegroup(name = 'foo', srcs = ['foo.txt'])")
        tester!!.addFile("base/bar/BUILD", "filegroup(name = 'bar', srcs = ['bar.txt'])")
        var loadingResult: TargetPatternPhaseValue = tester!!.load("//base/...:*")
        assertThat(loadingResult.getTargetLabels())
            .containsExactlyElementsIn(
                getLabels(
                    "//base:BUILD",
                    "//base:base",
                    "//base:base.txt",
                    "//base/foo:BUILD",
                    "//base/foo:foo",
                    "//base/foo:foo.txt",
                    "//base/bar:BUILD",
                    "//base/bar:bar",
                    "//base/bar:bar.txt"
                )
            )

        loadingResult = tester!!.load("//base/...:all-targets")
        assertThat(loadingResult.getTargetLabels())
            .containsExactlyElementsIn(
                getLabels(
                    "//base:BUILD",
                    "//base:base",
                    "//base:base.txt",
                    "//base/foo:BUILD",
                    "//base/foo:foo",
                    "//base/foo:foo.txt",
                    "//base/bar:BUILD",
                    "//base/bar:bar",
                    "//base/bar:bar.txt"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonExistentRecursive() {
        val loadingResult: TargetPatternPhaseValue = tester!!.loadKeepGoing("//base/...")
        assertThat(loadingResult.hasError()).isTrue()
        assertThat(loadingResult.hasPostExpansionError()).isFalse()
        assertThat(loadingResult.getTargetLabels()).isEmpty()
        assertThat(loadingResult.getTestsToRunLabels()).isNull()
        tester!!.assertContainsError("Skipping '//base/...': no targets found beneath 'base'")
        tester!!.assertContainsWarning("Target pattern parsing failed.")
        val err: PatternExpandingError = tester!!.findPostOnce<T>(PatternExpandingError::class.java)
        assertThat(err.pattern).containsExactly("//base/...")
    }

    @org.junit.Test
    fun testMistypedTarget() {
        val e: TargetParsingException? =
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { tester!!.load("foo//bar:missing") })
        assertThat(e)
            .hasMessageThat()
            .contains(
                "invalid package name 'foo//bar': package names may not contain '//' path separators"
            )
        val err: ParsingFailedEvent = tester!!.findPostOnce<T>(ParsingFailedEvent::class.java)
        assertThat(err.pattern).isEqualTo("foo//bar:missing")
    }

    @org.junit.Test
    fun testEmptyTarget() {
        val e: TargetParsingException? = org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { tester!!.load("") })
        assertThat(e).hasMessageThat().contains("invalid target name '': empty target name")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMistypedTargetKeepGoing() {
        val result: TargetPatternPhaseValue = tester!!.loadKeepGoing("foo//bar:missing")
        assertThat(result.hasError()).isTrue()
        tester!!.assertContainsError(
            "invalid package name 'foo//bar': package names may not contain '//' path separators"
        )
        val err: ParsingFailedEvent = tester!!.findPostOnce<T>(ParsingFailedEvent::class.java)
        assertThat(err.pattern).isEqualTo("foo//bar:missing")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadTargetPatternWithTest() {
        tester!!.addFile("base/BUILD")
        val loadingResult: TargetPatternPhaseValue = tester!!.loadTestsKeepGoing("//base:missing")
        assertThat(loadingResult.hasError()).isTrue()
        assertThat(loadingResult.hasPostExpansionError()).isFalse()
        assertThat(loadingResult.getTargetLabels()).isEmpty()
        assertThat(loadingResult.getTestsToRunLabels()).isEmpty()
        tester!!.assertContainsError("Skipping '//base:missing': no such target '//base:missing'")
        tester!!.assertContainsWarning("Target pattern parsing failed.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testManualTarget() {
        tester!!.addFile(
            "cc/BUILD", "fake_cc_library(name = 'my_lib', srcs = ['lib.cc'], tags = ['manual'])"
        )
        var loadingResult: TargetPatternPhaseValue = assertNoErrors(tester!!.load("//cc:all"))
        assertThat(loadingResult.getTargetLabels()).containsExactlyElementsIn(getLabels())

        // Explicitly specified on the command line.
        loadingResult = assertNoErrors(tester!!.load("//cc:my_lib"))
        assertThat(loadingResult.getTargetLabels()).containsExactlyElementsIn(getLabels("//cc:my_lib"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigSettingTarget() {
        tester!!.addFile(
            "config/BUILD",
            """
        fake_cc_library(
            name = "somelib",
            srcs = ["somelib.cc"],
            hdrs = ["somelib.h"],
        )

        config_setting(
            name = "configa",
            values = {"define": "foo=a"},
        )

        config_setting(
            name = "configb",
            values = {"define": "foo=b"},
        )
        
        """.trimIndent()
        )
        var result: TargetPatternPhaseValue = assertNoErrors(tester!!.load("//config:all"))
        assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//config:somelib"))

        // Explicitly specified on the command line.
        result = assertNoErrors(tester!!.load("//config:configa"))
        assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//config:configa"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNegativeTestDoesNotShowUpAtAll() {
        tester!!.addFile(
            "my_test/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name = 'my_test', srcs = ['test.cc'])"
        )
        assertNoErrors(tester!!.loadTests("-//my_test"))
        Truth.assertThat(tester!!.filteredTargets).isEmpty()
        Truth.assertThat(tester!!.testFilteredTargets).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNegativeTargetDoesNotShowUpAtAll() {
        tester!!.addFile("my_library/BUILD", "fake_cc_library(name = 'my_library', srcs = ['test.cc'])")
        assertNoErrors(tester!!.loadTests("-//my_library"))
        Truth.assertThat(tester!!.filteredTargets).isEmpty()
        Truth.assertThat(tester!!.testFilteredTargets).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestMinusAllTests() {
        tester!!.addFile(
            "test/BUILD",
            """
        fake_cc_library(name = "bar1")

        fake_cc_test(
            name = "test",
            tags = ["manual"],
            deps = [":bar1"],
        )
        
        """.trimIndent()
        )
        val result: TargetPatternPhaseValue = tester!!.loadTests("//test:test", "-//test:all")
        assertThat(result.hasError()).isFalse()
        assertThat(result.hasPostExpansionError()).isFalse()
        tester!!.assertContainsWarning("All specified test targets were excluded by filters")
        Truth.assertThat(tester!!.filteredTargets).containsExactlyElementsIn(getLabels("//test:test"))
        assertThat(result.getTargetLabels()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFindLongestPrefix() {
        tester!!.addFile("base/BUILD", "exports_files(['bar', 'bar/bar', 'bar/baz'])")
        var result: TargetPatternPhaseValue = assertNoErrors(tester!!.load("base/bar/baz"))
        assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//base:bar/baz"))
        result = assertNoErrors(tester!!.load("base/bar"))
        assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//base:bar"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultiSegmentLabel() {
        tester!!.addFile("base/foo/BUILD", "exports_files(['bar/baz'])")
        val value: TargetPatternPhaseValue = assertNoErrors(tester!!.load("base/foo:bar/baz"))
        assertThat(value.getTargetLabels()).containsExactlyElementsIn(getLabels("//base/foo:bar/baz"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultiSegmentLabelRelative() {
        tester!!.addFile("base/foo/BUILD", "exports_files(['bar/baz'])")
        tester!!.setRelativeWorkingDirectory("base")
        val value: TargetPatternPhaseValue = assertNoErrors(tester!!.load("foo:bar/baz"))
        assertThat(value.getTargetLabels()).containsExactlyElementsIn(getLabels("//base/foo:bar/baz"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeletedPackage() {
        tester!!.addFile("base/BUILD", "exports_files(['base'])")
        tester!!.setDeletedPackages(PackageIdentifier.createInMainRepo("base"))
        val result: TargetPatternPhaseValue = tester!!.loadKeepGoing("//base")
        assertThat(result.hasError()).isTrue()
        tester!!.assertContainsError(
            "no such package 'base': Package is considered deleted due to --deleted_packages"
        )
        val err: ParsingFailedEvent = tester!!.findPostOnce<T>(ParsingFailedEvent::class.java)
        assertThat(err.pattern).isEqualTo("//base")
    }

    @Throws(java.lang.Exception::class)
    private fun writeBuildFilesForTestFiltering() {
        tester!!.addFile(
            "tests/BUILD",
            """
        load("//test_defs:foo_test.bzl", "foo_test")
        foo_test(
            name = "t1",
            size = "small",
            srcs = ["pass.sh"],
            local = 1,
        )

        foo_test(
            name = "t2",
            size = "medium",
            srcs = ["pass.sh"],
        )

        foo_test(
            name = "t3",
            srcs = ["pass.sh"],
            tags = [
                "local",
                "manual",
            ],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestFiltering() {
        writeBuildFilesForTestFiltering()
        val loadingResult: TargetPatternPhaseValue = assertNoErrors(tester!!.loadTests("//tests:all"))
        assertThat(loadingResult.getTargetLabels())
            .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t2"))
        assertThat(loadingResult.getTestsToRunLabels())
            .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t2"))
        Truth.assertThat(tester!!.filteredTargets).isEmpty()
        Truth.assertThat(tester!!.testFilteredTargets).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestFilteringIncludingManual() {
        writeBuildFilesForTestFiltering()
        tester!!.useLoadingOptions("--build_manual_tests")
        val loadingResult: TargetPatternPhaseValue = assertNoErrors(tester!!.loadTests("//tests:all"))
        assertThat(loadingResult.getTargetLabels())
            .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t2", "//tests:t3"))
        assertThat(loadingResult.getTestsToRunLabels())
            .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t2"))
        Truth.assertThat(tester!!.filteredTargets).isEmpty()
        Truth.assertThat(tester!!.testFilteredTargets).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestFilteringBuildTestsOnly() {
        writeBuildFilesForTestFiltering()
        tester!!.useLoadingOptions("--build_tests_only")
        val result: TargetPatternPhaseValue = assertNoErrors(tester!!.loadTests("//tests:all"))
        assertThat(result.getTargetLabels())
            .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t2"))
        assertThat(result.getTestsToRunLabels())
            .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t2"))
        Truth.assertThat(tester!!.filteredTargets).isEmpty()
        Truth.assertThat(tester!!.testFilteredTargets).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestFilteringSize() {
        writeBuildFilesForTestFiltering()
        tester!!.useLoadingOptions("--test_size_filters=small")
        val result: TargetPatternPhaseValue = assertNoErrors(tester!!.loadTests("//tests:all"))
        assertThat(result.getTargetLabels())
            .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t2"))
        assertThat(result.getTestsToRunLabels()).containsExactlyElementsIn(getLabels("//tests:t1"))
        Truth.assertThat(tester!!.filteredTargets).isEmpty()
        Truth.assertThat(tester!!.testFilteredTargets).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestFilteringSizeAndBuildTestsOnly() {
        writeBuildFilesForTestFiltering()
        tester!!.useLoadingOptions("--test_size_filters=small", "--build_tests_only")
        val result: TargetPatternPhaseValue = assertNoErrors(tester!!.loadTests("//tests:all"))
        assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//tests:t1"))
        assertThat(result.getTestsToRunLabels()).containsExactlyElementsIn(getLabels("//tests:t1"))
        Truth.assertThat(tester!!.filteredTargets).isEmpty()
        Truth.assertThat(tester!!.testFilteredTargets).containsExactlyElementsIn(getLabels("//tests:t2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestFilteringLocalAndBuildTestsOnly() {
        writeBuildFilesForTestFiltering()
        tester!!.useLoadingOptions("--test_tag_filters=local", "--build_tests_only")
        val result: TargetPatternPhaseValue = assertNoErrors(tester!!.loadTests("//tests:all", "//tests:t3"))
        assertThat(result.getTargetLabels())
            .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t3"))
        assertThat(result.getTestsToRunLabels())
            .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t3"))
        Truth.assertThat(tester!!.filteredTargets).isEmpty()
        Truth.assertThat(tester!!.testFilteredTargets).containsExactlyElementsIn(getLabels("//tests:t2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestSuiteExpansion() {
        tester!!.addFile(
            "cc/BUILD",
            """
        fake_cc_test(
            name = "my_test",
            srcs = ["test.cc"],
        )

        test_suite(
            name = "tests",
            tests = [":my_test"],
        )
        
        """.trimIndent()
        )
        val loadingResult: TargetPatternPhaseValue = assertNoErrors(tester!!.loadTests("//cc:tests"))
        assertThat(loadingResult.getTargetLabels())
            .containsExactlyElementsIn(getLabels("//cc:my_test"))
        assertThat(loadingResult.getTestsToRunLabels())
            .containsExactlyElementsIn(getLabels("//cc:my_test"))
        Truth.assertThat(tester!!.originalTargets)
            .containsExactlyElementsIn(getLabels("//cc:tests", "//cc:my_test"))
        Truth.assertThat(tester!!.testSuiteTargets)
            .containsExactly(Label.parseCanonicalUnchecked("//cc:tests"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestSuiteExpansionFails() {
        tester!!.addFile("ts/BUILD", "test_suite(name = 'tests', tests = ['//nonexistent:my_test'])")
        tester!!.useLoadingOptions("--build_tests_only")
        val loadingResult: TargetPatternPhaseValue = tester!!.loadTestsKeepGoing("//ts:tests")
        assertThat(loadingResult.hasError()).isTrue()
        assertThat(loadingResult.hasPostExpansionError()).isFalse()
        tester!!.assertContainsError("no such package 'nonexistent'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestSuiteExpansionFailsForBuild() {
        tester!!.addFile("ts/BUILD", "test_suite(name = 'tests', tests = [':nonexistent_test'])")
        val loadingResult: TargetPatternPhaseValue = tester!!.loadKeepGoing("//ts:tests")
        assertThat(loadingResult.hasError()).isFalse()
        assertThat(loadingResult.hasPostExpansionError()).isTrue()
        tester!!.assertContainsError(
            "expecting a test or a test_suite rule but '//ts:nonexistent_test' is not one"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failureWhileLoadingTestsForTestSuiteKeepGoing() {
        tester!!.addFile("ts/BUILD", "test_suite(name = 'tests', tests = ['//pkg:tests'])")
        tester!!.addFile(
            "pkg/BUILD",
            """
        test_suite(name = "tests")

        test_suite()
        
        """.trimIndent()
        )
        val loadingResult: TargetPatternPhaseValue = tester!!.loadKeepGoing("//ts:tests")
        assertThat(loadingResult.hasError()).isFalse()
        assertThat(loadingResult.hasPostExpansionError()).isTrue()
        tester!!.assertContainsError("test_suite rule has no 'name' attribute")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failureWhileLoadingTestsForTestSuiteNoKeepGoing() {
        tester!!.addFile("ts/BUILD", "test_suite(name = 'tests', tests = ['//pkg:tests'])")
        tester!!.addFile(
            "pkg/BUILD",
            """
        test_suite(name = "tests")

        test_suite()
        
        """.trimIndent()
        )
        val e: TargetParsingException? =
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { tester!!.load("//ts:tests") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("error loading package 'pkg': Package 'pkg' contains errors")
        tester!!.assertContainsError("test_suite rule has no 'name' attribute")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestSuiteExpansionFailsMissingTarget() {
        tester!!.addFile("other/BUILD", "")
        tester!!.addFile("ts/BUILD", "test_suite(name = 'tests', tests = ['//other:no_such_test'])")
        val result: TargetPatternPhaseValue = tester!!.loadTestsKeepGoing("//ts:tests")
        assertThat(result.hasError()).isTrue()
        assertThat(result.hasPostExpansionError()).isTrue()
        tester!!.assertContainsError("no such target '//other:no_such_test'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestSuiteExpansionFailsMultipleSuites() {
        tester!!.addFile("other/BUILD", "")
        tester!!.addFile(
            "ts/BUILD",
            """
        test_suite(
            name = "a",
            tests = ["//other:no_such_test"],
        )

        test_suite(
            name = "b",
            tests = [],
        )
        
        """.trimIndent()
        )
        val result: TargetPatternPhaseValue = tester!!.loadTestsKeepGoing("//ts:all")
        assertThat(result.hasError()).isTrue()
        assertThat(result.hasPostExpansionError()).isTrue()
        tester!!.assertContainsError("no such target '//other:no_such_test'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestSuiteOverridesManualWithBuildTestsOnly() {
        tester!!.addFile(
            "foo/BUILD",
            """
        load("//test_defs:foo_test.bzl", "foo_test")
        foo_test(
            name = "foo",
            srcs = ["foo.sh"],
            tags = ["manual"],
        )

        foo_test(
            name = "bar",
            srcs = ["bar.sh"],
            tags = ["manual"],
        )

        foo_test(
            name = "baz",
            srcs = ["baz.sh"],
        )

        test_suite(
            name = "foo_suite",
            tests = [
                ":baz",
                ":foo",
            ],
        )
        
        """.trimIndent()
        )
        tester!!.useLoadingOptions("--build_tests_only")
        val result: TargetPatternPhaseValue = assertNoErrors(tester!!.loadTests("//foo:all"))
        assertThat(result.getTargetLabels())
            .containsExactlyElementsIn(getLabels("//foo:foo", "//foo:baz"))
        assertThat(result.getTestsToRunLabels())
            .containsExactlyElementsIn(getLabels("//foo:foo", "//foo:baz"))
        Truth.assertThat(tester!!.filteredTargets).isEmpty()
        Truth.assertThat(tester!!.testFilteredTargets)
            .containsExactlyElementsIn(getLabels("//foo:foo_suite"))
    }

    /** Regression test for bug: "subtracting tests from test doesn't work"  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilterNegativeTestFromTestSuite() {
        tester!!.addFile(
            "cc/BUILD",
            """
        fake_cc_test(
            name = "my_test",
            srcs = ["test.cc"],
        )

        fake_cc_test(
            name = "my_other_test",
            srcs = ["other_test.cc"],
        )

        test_suite(
            name = "tests",
            tests = [
                ":my_other_test",
                ":my_test",
            ],
        )
        
        """.trimIndent()
        )
        val result: TargetPatternPhaseValue =
            assertNoErrors(tester!!.loadTests("//cc:tests", "-//cc:my_test"))
        assertThat(result.getTargetLabels())
            .containsExactlyElementsIn(getLabels("//cc:my_other_test", "//cc:my_test"))
        assertThat(result.getTestsToRunLabels())
            .containsExactlyElementsIn(getLabels("//cc:my_other_test"))
    }

    /** Regression test for bug: "blaze doesn't seem to respect target subtractions"  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNegativeTestSuiteExpanded() {
        tester!!.addFile(
            "cc/BUILD",
            """
        fake_cc_test(
            name = "my_test",
            srcs = ["test.cc"],
        )

        fake_cc_test(
            name = "my_other_test",
            srcs = ["other_test.cc"],
        )

        test_suite(
            name = "tests",
            tests = [":my_test"],
        )

        test_suite(
            name = "all_tests",
            tests = ["my_other_test"],
        )
        
        """.trimIndent()
        )
        val result: TargetPatternPhaseValue =
            assertNoErrors(tester!!.loadTests("//cc:all_tests", "-//cc:tests"))
        assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//cc:my_other_test"))
        assertThat(result.getTestsToRunLabels())
            .containsExactlyElementsIn(getLabels("//cc:my_other_test"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestSuiteIsSubtracted() {
        // Test suites are expanded for each target pattern in sequence, not the whole set of target
        // patterns after all the inclusions and exclusions are processed.
        tester!!.addFile(
            "cc/BUILD",
            """
        fake_cc_test(
            name = "my_test",
            srcs = ["test.cc"],
        )

        fake_cc_test(
            name = "my_other_test",
            srcs = ["other_test.cc"],
        )

        test_suite(
            name = "tests",
            tests = [":my_test"],
        )
        
        """.trimIndent()
        )
        val result: TargetPatternPhaseValue = assertNoErrors(tester!!.loadTests("//cc:all", "-//cc:tests"))
        assertThat(result.getTargetLabels())
            .containsExactlyElementsIn(getLabels("//cc:my_test", "//cc:my_other_test"))
        assertThat(result.getTestsToRunLabels())
            .containsExactlyElementsIn(getLabels("//cc:my_other_test"))
    }

    /** Regression test for bug: "blaze test "no targets found" warning now fatal"  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoTestsInRecursivePattern() {
        tester!!.addFile("foo/BUILD", "fake_cc_library(name = 'foo', srcs = ['foo.cc'])")
        val result: TargetPatternPhaseValue = assertNoErrors(tester!!.loadTests("//foo/..."))
        assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//foo"))
        assertThat(result.getTestsToRunLabels()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testComplexTestSuite() {
        tester!!.addFile(
            "cc/BUILD",
            """
        fake_cc_test(
            name = "test1",
            srcs = ["test.cc"],
        )

        fake_cc_test(
            name = "test2",
            srcs = ["test.cc"],
        )

        test_suite(
            name = "empty",
            tags = ["impossible"],
            tests = [],
        )

        test_suite(
            name = "suite1",
            tests = [
                "empty",
                "test1",
            ],
        )

        test_suite(
            name = "suite2",
            tests = ["test2"],
        )

        test_suite(
            name = "all_tests",
            tests = [
                "suite1",
                "suite2",
            ],
        )
        
        """.trimIndent()
        )
        val result: TargetPatternPhaseValue = assertNoErrors(tester!!.loadTests("//cc:all_tests"))
        assertThat(result.getTargetLabels())
            .containsExactlyElementsIn(getLabels("//cc:test1", "//cc:test2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllExcludesManualTest() {
        tester!!.addFile(
            "cc/BUILD",
            """
        fake_cc_test(
            name = "my_test",
            srcs = ["test.cc"],
        )

        fake_cc_test(
            name = "my_other_test",
            srcs = ["other_test.cc"],
            tags = ["manual"],
        )
        
        """.trimIndent()
        )
        val result: TargetPatternPhaseValue = assertNoErrors(tester!!.loadTests("//cc:all"))
        assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//cc:my_test"))
        assertThat(result.getTestsToRunLabels()).containsExactlyElementsIn(getLabels("//cc:my_test"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildFilterDoesNotApplyToTests() {
        tester!!.addFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "foo",
            srcs = ["foo.sh"],
        )

        foo_library(
            name = "lib",
            srcs = ["lib.sh"],
        )

        foo_library(
            name = "nofoo",
            srcs = ["nofoo.sh"],
            tags = ["nofoo"],
        )
        
        """.trimIndent()
        )
        tester!!.useLoadingOptions("--build_tag_filters=nofoo")
        val result: TargetPatternPhaseValue = assertNoErrors(tester!!.loadTests("//foo:all"))
        assertThat(result.getTargetLabels())
            .containsExactlyElementsIn(getLabels("//foo:foo", "//foo:nofoo"))
        assertThat(result.getTestsToRunLabels()).containsExactlyElementsIn(getLabels("//foo:foo"))
    }

    /**
     * Regression test for bug: "blaze is lying to me about what tests exist (have been specified)"
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTotalNegationEmitsWarning() {
        tester!!.addFile(
            "cc/BUILD",
            """
        fake_cc_test(
            name = "my_test",
            srcs = ["test.cc"],
        )

        test_suite(
            name = "tests",
            tests = [":my_test"],
        )
        
        """.trimIndent()
        )
        val result: TargetPatternPhaseValue = tester!!.loadTests("//cc:tests", "-//cc:my_test")
        tester!!.assertContainsWarning("All specified test targets were excluded by filters")
        assertThat(result.getTestsToRunLabels()).containsExactlyElementsIn(getLabels())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepeatedSameLoad() {
        tester!!.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])")
        val firstResult: TargetPatternPhaseValue = assertNoErrors(tester!!.load("//base:hello"))
        val secondResult: TargetPatternPhaseValue = assertNoErrors(tester!!.load("//base:hello"))
        assertThat(secondResult.getTargetLabels()).isEqualTo(firstResult.getTargetLabels())
        assertThat(secondResult.getTestsToRunLabels()).isEqualTo(firstResult.getTestsToRunLabels())
    }

    /**
     * Tests whether globs can update correctly when a new file is added.
     * 
     * 
     * The usage of [LoadingPhaseTester.sync] triggers this via [ ][SkyframeExecutor.invalidateFilesUnderPathForTesting].
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobPicksUpNewFile() {
        tester!!.addFile("foo/BUILD", "filegroup(name='x', srcs=glob(['*.y']))")
        tester!!.addFile("foo/a.y")
        var label: Label? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(assertNoErrors(tester!!.load("//foo:x")).getTargetLabels())
        var result = tester!!.getTarget(label.toString())
        Truth.assertThat(
            com.google.common.collect.Iterables.transform<F?, T?>(
                result.getAssociatedRule().getLabels(), com.google.common.base.Functions.toStringFunction()
            )
        )
            .containsExactly("//foo:a.y")

        tester!!.addFile("foo/b.y")
        tester!!.sync()
        label =
            com.google.common.collect.Iterables.getOnlyElement<T?>(assertNoErrors(tester!!.load("//foo:x")).getTargetLabels())
        result = tester!!.getTarget(label.toString())
        Truth.assertThat(
            com.google.common.collect.Iterables.transform<F?, T?>(
                result.getAssociatedRule().getLabels(), com.google.common.base.Functions.toStringFunction()
            )
        )
            .containsExactly("//foo:a.y", "//foo:b.y")
    }

    /** Regression test: handle symlink cycles gracefully.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCycleReporting_symlinkCycleDuringTargetParsing() {
        tester!!.addFile("hello/BUILD", "fake_cc_library(name = 'a', srcs = glob(['*.cc']))")
        val buildFilePath: Path = tester!!.getWorkspace().getRelative("hello/BUILD")
        val dirPath: Path = buildFilePath.getParentDirectory()
        val fooFilePath: Path = dirPath.getRelative("foo.cc")
        val barFilePath: Path = dirPath.getRelative("bar.cc")
        val bazFilePath: Path = dirPath.getRelative("baz.cc")
        fooFilePath.createSymbolicLink(barFilePath)
        barFilePath.createSymbolicLink(bazFilePath)
        bazFilePath.createSymbolicLink(fooFilePath)
        assertCircularSymlinksDuringTargetParsing("//hello:a", "Too many levels of symbolic links")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRecursivePatternWithCircularSymlink() {
        tester!!.getWorkspace().getChild("broken").createDirectory()

        // Create a circular symlink.
        tester!!
            .getWorkspace()
            .getRelative(PathFragment.create("broken/BUILD"))
            .createSymbolicLink(PathFragment.create("BUILD"))

        assertCircularSymlinksDuringTargetParsing("//broken/...", "circular symlinks detected")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRecursivePatternWithTwoCircularSymlinks() {
        tester!!.getWorkspace().getChild("broken").createDirectory()

        // Create a circular symlink.
        tester!!
            .getWorkspace()
            .getRelative(PathFragment.create("broken/BUILD"))
            .createSymbolicLink(PathFragment.create("x"))
        tester!!
            .getWorkspace()
            .getRelative(PathFragment.create("broken/x"))
            .createSymbolicLink(PathFragment.create("BUILD"))

        assertCircularSymlinksDuringTargetParsing("//broken/...", "circular symlinks detected")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuiteInSuite() {
        tester!!.addFile(
            "suite/BUILD",
            """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "a",
            tests = [":b"],
        )

        test_suite(
            name = "b",
            tests = [":c"],
        )

        foo_test(
            name = "c",
            srcs = ["test.cc"],
        )
        
        """.trimIndent()
        )
        val result: TargetPatternPhaseValue = assertNoErrors(tester!!.load("//suite:a"))
        assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//suite:c"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelTargetErrorsPrintedExactlyOnce_noKeepGoing() {
        tester!!.addFile(
            "bad/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "bad",
            srcs = ["bad.sh"],
        )

        fail("some error")
        
        """.trimIndent()
        )
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { tester!!.load("//bad") })
        tester!!.assertContainsEventWithFrequency("some error", 1)
        val err: PatternExpandingError = tester!!.findPostOnce<T>(PatternExpandingError::class.java)
        assertThat(err.pattern).containsExactly("//bad")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelTargetErrorsPrintedExactlyOnce_keepGoing() {
        tester!!.addFile(
            "bad/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "bad",
            srcs = ["bad.sh"],
        )

        fail("some error")
        
        """.trimIndent()
        )
        val result: TargetPatternPhaseValue = tester!!.loadKeepGoing("//bad")
        assertThat(result.hasError()).isTrue()
        tester!!.assertContainsEventWithFrequency("some error", 1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileOneDependency() {
        // setting up the cc rules in loading phase tests is non-trivial
        // when we no longer have builtin cc rules, this test case can be deleted, or moved to
        // CompileOneDependencyTransformerTest.java
        if (TestConstants.PRODUCT_NAME == "bazel") {
            return
        }
        tester!!.addFile(
            "base/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'hello', srcs = ['hello.cc'])"
        )
        tester!!.useLoadingOptions("--compile_one_dependency")
        val result: TargetPatternPhaseValue = assertNoErrors(tester!!.load("base/hello.cc"))
        assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//base:hello"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileOneDependencyNonExistentSource() {
        // setting up the cc rules in loading phase tests is non-trivial
        // when we no longer have builtin cc rules, this test case can be deleted, or moved to
        // CompileOneDependencyTransformerTest.java
        if (TestConstants.PRODUCT_NAME == "bazel") {
            return
        }
        tester!!.addFile(
            "base/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'hello', srcs = ['hello.cc', '//bad:bad.cc'])"
        )
        tester!!.useLoadingOptions("--compile_one_dependency")
        try {
            val loadingResult: TargetPatternPhaseValue = tester!!.load("base/hello.cc")
            assertThat(loadingResult.hasPostExpansionError()).isFalse()
        } catch (expected: LoadingFailedException) {
            tester!!.assertContainsError("no such package 'bad'")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileOneDependencyNonExistentSourceKeepGoing() {
        tester!!.addFile(
            "base/BUILD", "fake_cc_library(name = 'hello', srcs = ['hello.cc', '//bad:bad.cc'])"
        )
        tester!!.useLoadingOptions("--compile_one_dependency")
        val loadingResult: TargetPatternPhaseValue = tester!!.loadKeepGoing("base/hello.cc")
        assertThat(loadingResult.hasPostExpansionError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileOneDependencyReferencesFile() {
        tester!!.addFile(
            "base/BUILD", "fake_cc_library(name = 'hello', srcs = ['hello.cc', '//bad:bad.cc'])"
        )
        tester!!.useLoadingOptions("--compile_one_dependency")
        val e: TargetParsingException? =
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { tester!!.load("//base:hello") })
        assertThat(e)
            .hasMessageThat()
            .contains("--compile_one_dependency target '//base:hello' must be a file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParsingFailureReported() {
        val loadingResult: TargetPatternPhaseValue = tester!!.loadKeepGoing("//does_not_exist")
        assertThat(loadingResult.hasError()).isTrue()
        val event: ParsingFailedEvent = tester!!.findPostOnce<T>(ParsingFailedEvent::class.java)
        assertThat(event.pattern).isEqualTo("//does_not_exist")
        com.google.common.truth.Subject.contains("BUILD file not found")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCyclesKeepGoing() {
        tester!!.addFile("test/BUILD", "load(':cycle1.bzl', 'make_cycle')")
        tester!!.addFile("test/cycle1.bzl", "load(':cycle2.bzl', 'make_cycle')")
        tester!!.addFile("test/cycle2.bzl", "load(':cycle1.bzl', 'make_cycle')")
        // The skyframe target pattern evaluator isn't able to provide partial results in the presence
        // of cycles, so it simply raises an exception rather than returning an empty result.
        val e: TargetParsingException? =
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { tester!!.load("//test:cycle1") })
        assertThat(e).hasMessageThat().contains("cycles detected")
        tester!!.assertContainsEventWithFrequency("cycle detected in extension", 1)
        val err: PatternExpandingError = tester!!.findPostOnce<T>(PatternExpandingError::class.java)
        assertThat(err.pattern).containsExactly("//test:cycle1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCyclesNoKeepGoing() {
        tester!!.addFile("test/BUILD", "load(':cycle1.bzl', 'make_cycle')")
        tester!!.addFile("test/cycle1.bzl", "load(':cycle2.bzl', 'make_cycle')")
        tester!!.addFile("test/cycle2.bzl", "load(':cycle1.bzl', 'make_cycle')")
        val e: TargetParsingException? =
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { tester!!.load("//test:cycle1") })
        assertThat(e).hasMessageThat().contains("cycles detected")
        tester!!.assertContainsEventWithFrequency("cycle detected in extension", 1)
        val err: PatternExpandingError = tester!!.findPostOnce<T>(PatternExpandingError::class.java)
        assertThat(err.pattern).containsExactly("//test:cycle1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mapsOriginalPatternsToLabels() {
        tester!!.addFile("test/a/BUILD", "fake_cc_library(name = 'a_lib', srcs = ['a.cc'])")
        tester!!.addFile("test/b/BUILD", "fake_cc_library(name = 'b_lib', srcs = ['b.cc'])")

        tester!!.load("test/a:all", "test/b:all", "test/...")

        Truth.assertThat(tester!!.originalPatternsToLabels)
            .containsExactly(
                "test/a:all", Label.parseCanonicalUnchecked("//test/a:a_lib"),
                "test/b:all", Label.parseCanonicalUnchecked("//test/b:b_lib"),
                "test/...", Label.parseCanonicalUnchecked("//test/a:a_lib"),
                "test/...", Label.parseCanonicalUnchecked("//test/b:b_lib")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mapsOriginalPatternsToLabels_omitsExcludedTargets() {
        tester!!.addFile("test/a/BUILD", "fake_cc_library(name = 'a_lib', srcs = ['a.cc'])")

        tester!!.load("test/...", "-test/a:a_lib")

        Truth.assertThat(tester!!.originalPatternsToLabels).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuiteCycle() {
        tester!!.addFile(
            "BUILD", "test_suite(name = 'a', tests = [':b']); test_suite(name = 'b', tests = [':a'])"
        )
        assertThat(
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { tester!!.loadKeepGoing("//:a", "//:b") })
        )
            .hasMessageThat()
            .contains("cycles detected")
        Truth.assertThat(tester!!.assertContainsError("cycle in dependency graph").toString())
            .containsMatch("in test_suite rule //:.: cycle in dependency graph")
        val err: PatternExpandingError = tester!!.findPostOnce<T>(PatternExpandingError::class.java)
        assertThat(err.pattern).containsExactly("//:a", "//:b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuiteExpansions_emptyIfNoTestSuitesRequested() {
        tester!!.addFile(
            "foo/BUILD",
            """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "s",
            tests = ["a"],
        )

        foo_test(
            name = "t",
            srcs = [],
        )
        
        """.trimIndent()
        )

        tester!!.load("//foo:t")

        Truth.assertThat(tester!!.testSuiteExpansions).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuiteExpansions_includesEmptyTestSuite() {
        tester!!.addFile("foo/BUILD", "test_suite(name = 's', tests = [])")

        tester!!.load("//foo:s")

        Truth.assertThat(tester!!.testSuiteExpansions)
            .containsExactly(TestSuiteExpansion.newBuilder().setSuiteLabel("//foo:s").build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuiteExpansions_singleTestSuite() {
        tester!!.addFile(
            "foo/BUILD",
            """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "s",
            tests = [
                "t1",
                "t2",
            ],
        )

        foo_test(
            name = "t1",
            srcs = [],
        )

        foo_test(
            name = "t2",
            srcs = [],
        )
        
        """.trimIndent()
        )

        tester!!.load("//foo:s")

        Truth.assertThat(tester!!.testSuiteExpansions)
            .ignoringRepeatedFieldOrder()
            .containsExactly(
                TestSuiteExpansion.newBuilder()
                    .setSuiteLabel("//foo:s")
                    .addTestLabels("//foo:t1")
                    .addTestLabels("//foo:t2")
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuiteExpansions_multipleTestSuites() {
        tester!!.addFile(
            "foo/BUILD",
            """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "s1",
            tests = [
                "t1",
                "t2",
            ],
        )

        test_suite(
            name = "s2",
            tests = ["t3"],
        )

        foo_test(
            name = "t1",
            srcs = [],
        )

        foo_test(
            name = "t2",
            srcs = [],
        )

        foo_test(
            name = "t3",
            srcs = [],
        )
        
        """.trimIndent()
        )

        tester!!.load("//foo:s1", "//foo:s2")

        Truth.assertThat(tester!!.testSuiteExpansions)
            .ignoringRepeatedFieldOrder()
            .containsExactly(
                TestSuiteExpansion.newBuilder()
                    .setSuiteLabel("//foo:s1")
                    .addTestLabels("//foo:t1")
                    .addTestLabels("//foo:t2")
                    .build(),
                TestSuiteExpansion.newBuilder()
                    .setSuiteLabel("//foo:s2")
                    .addTestLabels("//foo:t3")
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuiteExpansions_overlappingTestSuites() {
        tester!!.addFile(
            "foo/BUILD",
            """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "s1",
            tests = [
                "t1",
                "t2",
            ],
        )

        test_suite(
            name = "s2",
            tests = [
                "t2",
                "t3",
            ],
        )

        foo_test(
            name = "t1",
            srcs = [],
        )

        foo_test(
            name = "t2",
            srcs = [],
        )

        foo_test(
            name = "t3",
            srcs = [],
        )
        
        """.trimIndent()
        )

        tester!!.load("//foo:s1", "//foo:s2")

        Truth.assertThat(tester!!.testSuiteExpansions)
            .ignoringRepeatedFieldOrder()
            .containsExactly(
                TestSuiteExpansion.newBuilder()
                    .setSuiteLabel("//foo:s1")
                    .addTestLabels("//foo:t1")
                    .addTestLabels("//foo:t2")
                    .build(),
                TestSuiteExpansion.newBuilder()
                    .setSuiteLabel("//foo:s2")
                    .addTestLabels("//foo:t2")
                    .addTestLabels("//foo:t3")
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuiteExpansions_nestedTestSuite() {
        tester!!.addFile(
            "foo/BUILD",
            """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "s1",
            tests = [
                "s2",
                "t1",
            ],
        )

        test_suite(
            name = "s2",
            tests = [
                "t2",
                "t3",
            ],
        )

        foo_test(
            name = "t1",
            srcs = [],
        )

        foo_test(
            name = "t2",
            srcs = [],
        )

        foo_test(
            name = "t3",
            srcs = [],
        )
        
        """.trimIndent()
        )

        tester!!.load("//foo:s1")

        Truth.assertThat(tester!!.testSuiteExpansions)
            .ignoringRepeatedFieldOrder()
            .containsExactly(
                TestSuiteExpansion.newBuilder()
                    .setSuiteLabel("//foo:s1")
                    .addTestLabels("//foo:t1")
                    .addTestLabels("//foo:t2")
                    .addTestLabels("//foo:t3")
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuiteExpansions_includesTestMatchingNegativePattern() {
        tester!!.addFile(
            "foo/BUILD",
            """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "s",
            tests = ["t"],
        )

        foo_test(
            name = "t",
            srcs = [],
        )
        
        """.trimIndent()
        )

        tester!!.load("//foo:s", "-//foo:t")

        Truth.assertThat(tester!!.testSuiteExpansions)
            .containsExactly(
                TestSuiteExpansion.newBuilder()
                    .setSuiteLabel("//foo:s")
                    .addTestLabels("//foo:t")
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuiteExpansions_presentWhenSuiteMatchesWildcard() {
        tester!!.addFile(
            "foo/BUILD",
            """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "s",
            tests = ["t1"],
        )

        foo_test(
            name = "t1",
            srcs = [],
        )

        foo_test(
            name = "t2",
            srcs = [],
        )
        
        """.trimIndent()
        )

        tester!!.load("//foo:all")

        Truth.assertThat(tester!!.testSuiteExpansions)
            .containsExactly(
                TestSuiteExpansion.newBuilder()
                    .setSuiteLabel("//foo:s")
                    .addTestLabels("//foo:t1")
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuiteExpansions_excludesSuiteMatchingNegativePatten() {
        tester!!.addFile(
            "foo/BUILD",
            """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "s",
            tests = ["t1"],
        )

        foo_test(
            name = "t1",
            srcs = [],
        )

        foo_test(
            name = "t2",
            srcs = [],
        )
        
        """.trimIndent()
        )

        tester!!.load("//foo:all", "-//foo:s")

        Truth.assertThat(tester!!.testSuiteExpansions).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWildcard() {
        tester!!.addFile(
            "foo/lib/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'lib2', srcs = ['foo.cc'])"
        )
        var value: TargetPatternPhaseValue = assertNoErrors(tester!!.load("//foo/lib:all-targets"))
        assertThat(value.getTargetLabels())
            .containsExactlyElementsIn(
                getLabels("//foo/lib:BUILD", "//foo/lib:lib2", "//foo/lib:foo.cc")
            )

        value = assertNoErrors(tester!!.load("//foo/lib:*"))
        assertThat(value.getTargetLabels())
            .containsExactlyElementsIn(
                getLabels("//foo/lib:BUILD", "//foo/lib:lib2", "//foo/lib:foo.cc")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWildcardConflict() {
        tester!!.addFile(
            "foo/lib/BUILD",
            """
        fake_cc_library(name = "lib1")

        fake_cc_library(name = "lib2")

        fake_cc_library(name = "all-targets")

        fake_cc_library(name = "all")
        
        """.trimIndent()
        )

        assertWildcardConflict("//foo/lib:all", ":all")
        assertWildcardConflict("//foo/lib:all-targets", ":all-targets")
    }

    @Throws(java.lang.Exception::class)
    private fun assertWildcardConflict(label: String, suffix: String?) {
        val value: TargetPatternPhaseValue = tester!!.load(label)
        assertThat(value.getTargetLabels()).containsExactlyElementsIn(getLabels(label))
        tester!!.assertContainsWarning(
            String.format(
                "The target pattern '%s' is ambiguous: '%s' is both a wildcard, and the name of an"
                        + " existing fake_cc_library rule; using the latter interpretation",
                label, suffix
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsolutePatternEndsWithSlashAll() {
        tester!!.addFile("foo/all/BUILD", "fake_cc_library(name = 'all')")
        val value: TargetPatternPhaseValue = tester!!.load("//foo/all")
        assertThat(value.getTargetLabels()).containsExactlyElementsIn(getLabels("//foo/all:all"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativeLabel() {
        tester!!.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])")
        val value: TargetPatternPhaseValue = assertNoErrors(tester!!.load("base:hello"))
        assertThat(value.getTargetLabels()).containsExactlyElementsIn(getLabels("//base:hello"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsoluteLabelWithOffset() {
        tester!!.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])")
        tester!!.setRelativeWorkingDirectory("base")
        val value: TargetPatternPhaseValue = assertNoErrors(tester!!.load("//base:hello"))
        assertThat(value.getTargetLabels()).containsExactlyElementsIn(getLabels("//base:hello"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativeLabelWithOffset() {
        tester!!.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])")
        tester!!.setRelativeWorkingDirectory("base")
        val value: TargetPatternPhaseValue = assertNoErrors(tester!!.load(":hello"))
        assertThat(value.getTargetLabels()).containsExactlyElementsIn(getLabels("//base:hello"))
    }

    private fun expectError(pattern: String?, message: String?) {
        val e: TargetParsingException? =
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { tester!!.load(pattern) })
        assertThat(e).hasMessageThat().contains(message)
    }

    @org.junit.Test
    fun testPatternWithSingleSlashIsError() {
        expectError(
            "/single/slash",
            "invalid target name '/single/slash': target names may not start with '/'"
        )
    }

    @org.junit.Test
    fun testPatternWithSingleSlashIsErrorAndOffset() {
        tester!!.setRelativeWorkingDirectory("base")
        expectError(
            "/single/slash",
            "invalid target name '/single/slash': target names may not start with '/'"
        )
    }

    @org.junit.Test
    fun testPatternWithTripleSlashIsError() {
        expectError(
            "///triple/slash",
            "invalid package name '/triple/slash': package names may not start with '/'"
        )
    }

    @org.junit.Test
    fun testPatternEndingWithSingleSlashIsError() {
        expectError("foo/", "invalid target name 'foo/': target names may not end with '/'")
    }

    @org.junit.Test
    fun testPatternStartingWithDotDotSlash() {
        expectError(
            "../foo",
            "invalid target name '../foo': target names may not contain up-level references '..'"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun runTestPackageLoadingError(keepGoing: Boolean, vararg patterns: String?) {
        tester!!.addFile("bad/BUILD", "nope")
        if (keepGoing) {
            val value: TargetPatternPhaseValue = tester!!.loadKeepGoing(*patterns)
            assertThat(value.hasError()).isTrue()
            tester!!.assertContainsWarning("Target pattern parsing failed")
        } else {
            val exn: TargetParsingException? =
                org.junit.Assert.assertThrows<T?>(
                    TargetParsingException::class.java,
                    org.junit.function.ThrowingRunnable { tester!!.load(*patterns) })
            assertThat(exn).hasCauseThat().isInstanceOf(BuildFileContainsErrorsException::class.java)
            assertThat(exn).hasCauseThat().hasMessageThat().contains("Package 'bad' contains errors")
        }
        tester!!.assertContainsError("/workspace/bad/BUILD:1:1: name 'nope' is not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageLoadingError_keepGoing_explicitTarget() {
        runTestPackageLoadingError( /*keepGoing=*/true, "//bad:BUILD")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageLoadingError_noKeepGoing_explicitTarget() {
        runTestPackageLoadingError( /*keepGoing=*/false, "//bad:BUILD")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageLoadingError_keepGoing_targetsInPackage() {
        runTestPackageLoadingError( /*keepGoing=*/true, "//bad:all")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageLoadingError_noKeepGoing_targetsInPackage() {
        runTestPackageLoadingError( /*keepGoing=*/false, "//bad:all")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageLoadingError_keepGoing_targetsBeneathDirectory() {
        runTestPackageLoadingError( /*keepGoing=*/true, "//bad/...")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageLoadingError_noKeepGoing_targetsBeneathDirectory() {
        runTestPackageLoadingError( /*keepGoing=*/false, "//bad/...")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageLoadingError_keepGoing_someGoodTargetsBeneathDirectory() {
        tester!!.addFile(
            "good/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 't')\n"
        )
        runTestPackageLoadingError( /* keepGoing= */true, "//...")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageLoadingError_noKeepGoing_someGoodTargetsBeneathDirectory() {
        tester!!.addFile(
            "good/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 't')\n"
        )
        runTestPackageLoadingError( /* keepGoing= */false, "//...")
    }

    @Throws(java.lang.Exception::class)
    private fun runTestPackageFileInconsistencyError(keepGoing: Boolean, vararg patterns: String?) {
        tester!!.addFile(
            "bad/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 't')\n"
        )
        val ioExn: IOException = IOException("nope")
        tester!!.throwExceptionOnGetInputStream(tester!!.getWorkspace().getRelative("bad/BUILD"), ioExn)
        if (keepGoing) {
            val value: TargetPatternPhaseValue = tester!!.loadKeepGoing(*patterns)
            assertThat(value.hasError()).isTrue()
            tester!!.assertContainsWarning("Target pattern parsing failed")
            tester!!.assertContainsError("error loading package 'bad': nope")
        } else {
            val exn: TargetParsingException? =
                org.junit.Assert.assertThrows<T?>(
                    TargetParsingException::class.java,
                    org.junit.function.ThrowingRunnable { tester!!.load(*patterns) })
            assertThat(exn).hasCauseThat().isInstanceOf(BuildFileContainsErrorsException::class.java)
            assertThat(exn).hasCauseThat().hasMessageThat().contains("error loading package 'bad': nope")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageFileInconsistencyError_keepGoing_explicitTarget() {
        runTestPackageFileInconsistencyError(true, "//bad:BUILD")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageFileInconsistencyError_noKeepGoing_explicitTarget() {
        runTestPackageFileInconsistencyError(false, "//bad:BUILD")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageFileInconsistencyError_keepGoing_targetsInPackage() {
        runTestPackageFileInconsistencyError(true, "//bad:all")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageFileInconsistencyError_noKeepGoing_targetsInPackage() {
        runTestPackageFileInconsistencyError(false, "//bad:all")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageFileInconsistencyError_keepGoing_argetsBeneathDirectory() {
        runTestPackageFileInconsistencyError(true, "//bad/...")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageFileInconsistencyError_noKeepGoing_targetsBeneathDirectory() {
        runTestPackageFileInconsistencyError(false, "//bad/...")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageFileInconsistencyError_keepGoing_someGoodTargetsBeneathDirectory() {
        tester!!.addFile(
            "good/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 't')\n"
        )
        runTestPackageFileInconsistencyError(true, "//...")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageFileInconsistencyError_noKeepGoing_someGoodTargetsBeneathDirectory() {
        tester!!.addFile(
            "good/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 't')\n"
        )
        runTestPackageFileInconsistencyError(false, "//...")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extensionLoadingError(
        @TestParameter keepGoing: Boolean,
        @TestParameter("//bad:BUILD", "//bad:all", "//bad/...", "//...") pattern: String?
    ) {
        tester!!.addFile("bad/f1.bzl", "nope")
        tester!!.addFile("bad/BUILD", "load(\":f1.bzl\", \"not_a_symbol\")")
        if (keepGoing) {
            val value: TargetPatternPhaseValue = tester!!.loadKeepGoing(pattern)
            assertThat(value.hasError()).isTrue()
            tester!!.assertContainsWarning("Target pattern parsing failed")
        } else {
            val exn: TargetParsingException =
                org.junit.Assert.assertThrows<T>(
                    TargetParsingException::class.java,
                    org.junit.function.ThrowingRunnable { tester!!.load(pattern) })
            assertThat(exn).hasCauseThat().isInstanceOf(BuildFileContainsErrorsException::class.java)
            assertThat(exn)
                .hasCauseThat()
                .hasMessageThat()
                .contains("compilation of module 'bad/f1.bzl' failed")
            val detailedExitCode: DetailedExitCode = exn.getDetailedExitCode()
            assertThat(detailedExitCode.getExitCode()).isEqualTo(ExitCode.BUILD_FAILURE)
            assertThat(detailedExitCode.getFailureDetail().getPackageLoading().getCode())
                .isEqualTo(PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR)
        }
        tester!!.assertContainsError("/workspace/bad/f1.bzl:1:1: name 'nope' is not defined")
    }

    private class LoadingPhaseTester {
        private val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        private val fs: CustomInMemoryFs =
            com.google.devtools.build.lib.pkgcache.LoadingPhaseRunnerTest.CustomInMemoryFs(clock)
        private val workspace: Path

        private val skyframeExecutor: SkyframeExecutor

        private val changes: MutableList<Path> = java.util.ArrayList<Path>()

        private var options: LoadingOptions?
        private val storedErrors: StoredEventHandler

        private var relativeWorkingDirectory: PathFragment? = PathFragment.EMPTY_FRAGMENT
        private var targetParsingCompleteEvent: TargetParsingCompleteEvent? = null
        private var loadingPhaseCompleteEvent: LoadingPhaseCompleteEvent? = null

        private val mockToolsConfig: MockToolsConfig

        init {
            this.workspace = fs.getPath("/workspace")
            workspace.createDirectory()
            mockToolsConfig = MockToolsConfig(workspace)
            val analysisMock: AnalysisMock = AnalysisMock.getAnalysisMockWithoutBuiltinModules()
            analysisMock.setupMockClient(mockToolsConfig)
            val directories: BlazeDirectories =
                BlazeDirectories(
                    ServerDirectories(
                        fs.getPath("/install"), fs.getPath("/output"), fs.getPath("/userRoot")
                    ),
                    workspace,
                    analysisMock.productName
                )
            workspace.getRelative("base").deleteTree()

            val ruleClassProvider: ConfiguredRuleClassProvider = createRuleClassProvider()
            val pkgFactory: PackageFactory? =
                analysisMock.getPackageFactoryBuilderForTesting(directories).build(ruleClassProvider, fs)
            val options: PackageOptions =
                com.google.devtools.common.options.Options.getDefaults<O>(PackageOptions::class.java)
            storedErrors = StoredEventHandler()
            skyframeExecutor =
                BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
                    .setPkgFactory(pkgFactory)
                    .setFileSystem(fs)
                    .setDirectories(directories)
                    .setActionKeyContext(ActionKeyContext())
                    .setExtraSkyFunctions(analysisMock.getSkyFunctions(directories))
                    .setSyscallCache(SyscallCache.NO_CACHE)
                    .build()
            SkyframeExecutorTestHelper.process(skyframeExecutor)
            val pkgLocator: PathPackageLocator? =
                PathPackageLocator.create( /* outputBase= */
                    null,
                    options.getPackagePath(),
                    storedErrors,
                    workspace.asFragment(),
                    workspace,
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                )
            val packageOptions: PackageOptions =
                com.google.devtools.common.options.Options.getDefaults<O>(PackageOptions::class.java)
            packageOptions.setDefaultVisibility(RuleVisibility.PRIVATE)
            packageOptions.setShowLoadingProgress(true)
            packageOptions.setGlobbingThreads(7)
            skyframeExecutor.injectExtraPrecomputedValues(analysisMock.precomputedValues)
            skyframeExecutor.preparePackageLoading(
                pkgLocator,
                packageOptions,
                defaultBuildLanguageOptions(),
                UUID.randomUUID(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                QuiescingExecutorsImpl.forTesting(),
                TimestampGranularityMonitor(clock)
            )
            skyframeExecutor.setActionEnv(com.google.common.collect.ImmutableMap.of<K?, V?>())
            this.options = com.google.devtools.common.options.Options.getDefaults<O?>(LoadingOptions::class.java)
        }

        @Throws(OptionsParsingException::class)
        fun useLoadingOptions(vararg options: String?) {
            val parser: OptionsParser = OptionsParser.builder().optionsClasses(LoadingOptions::class.java).build()
            parser.parse(com.google.common.collect.ImmutableList.copyOf<String?>(options))
            this.options = parser.getOptions<O?>(LoadingOptions::class.java)
        }

        fun setRelativeWorkingDirectory(relativeWorkingDirectory: String?) {
            this.relativeWorkingDirectory = PathFragment.create(relativeWorkingDirectory)
        }

        fun setDeletedPackages(vararg packages: PackageIdentifier?) {
            skyframeExecutor.setDeletedPackages(com.google.common.collect.ImmutableList.< E > copyOf < E ? > (packages))
        }

        @Throws(java.lang.Exception::class)
        fun load(vararg patterns: String?): TargetPatternPhaseValue {
            return loadWithFlags( /*keepGoing=*/false,  /*determineTests=*/false, *patterns)
        }

        @Throws(java.lang.Exception::class)
        fun loadKeepGoing(vararg patterns: String?): TargetPatternPhaseValue {
            return loadWithFlags( /*keepGoing=*/true,  /*determineTests=*/false, *patterns)
        }

        @Throws(java.lang.Exception::class)
        fun loadTests(vararg patterns: String?): TargetPatternPhaseValue {
            return loadWithFlags( /*keepGoing=*/false,  /*determineTests=*/true, *patterns)
        }

        @Throws(java.lang.Exception::class)
        fun loadTestsKeepGoing(vararg patterns: String?): TargetPatternPhaseValue {
            return loadWithFlags( /*keepGoing=*/true,  /*determineTests=*/true, *patterns)
        }

        @Throws(java.lang.Exception::class)
        fun loadWithFlags(
            keepGoing: Boolean, determineTests: Boolean, vararg patterns: String?
        ): TargetPatternPhaseValue {
            sync()
            storedErrors.clear()
            val result: TargetPatternPhaseValue =
                skyframeExecutor.loadTargetPatternsWithFilters(
                    storedErrors,
                    com.google.common.collect.ImmutableList.< E > copyOf < E ? > (patterns),
                    relativeWorkingDirectory,
                    options,  // We load very few packages, and everything is in memory; two should be plenty.
                    /* threadCount= */
                    2,
                    keepGoing,
                    determineTests
                )
            this.targetParsingCompleteEvent = findPost<T>(TargetParsingCompleteEvent::class.java)
            this.loadingPhaseCompleteEvent = findPost<T>(LoadingPhaseCompleteEvent::class.java)
            if (!keepGoing) {
                Truth.assertThat(storedErrors.hasErrors()).isFalse()
            }
            return result
        }

        fun getWorkspace(): Path {
            return workspace
        }

        @Throws(IOException::class)
        fun addFile(fileName: String?, vararg content: String?) {
            val buildFile: Path = workspace.getRelative(fileName)
            com.google.common.base.Preconditions.checkState(!buildFile.exists())
            var currentPath: Path = buildFile

            // Add the new file and all the directories that will be created by
            // createDirectoryAndParents()
            while (!currentPath.exists()) {
                changes.add(currentPath)
                currentPath = currentPath.getParentDirectory()
            }

            buildFile.getParentDirectory().createDirectoryAndParents()
            FileSystemUtils.writeContentAsLatin1(buildFile, com.google.common.base.Joiner.on('\n').join(content))
        }

        @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
        fun sync() {
            clock.advanceMillis(1)
            val builder: ModifiedFileSet.Builder = ModifiedFileSet.builder()
            for (path in changes) {
                if (!path.startsWith(workspace)) {
                    continue
                }

                val workspacePath: PathFragment? = path.relativeTo(workspace)
                builder.modify(workspacePath)
            }
            val modified: ModifiedFileSet? = builder.build()
            skyframeExecutor.invalidateFilesUnderPathForTesting(
                storedErrors, modified, Root.fromPath(workspace)
            )

            changes.clear()
        }

        @Throws(java.lang.Exception::class)
        fun getTarget(targetName: String?): Target {
            val eventHandler: StoredEventHandler = StoredEventHandler()
            val target: Target =
                this.pkgManager.getTarget(eventHandler, Label.parseCanonicalUnchecked(targetName))
            Truth.assertThat(eventHandler.hasErrors()).isFalse()
            return target
        }

        val pkgManager: PackageManager
            get() = skyframeExecutor.getPackageManager()

        val filteredTargets: com.google.common.collect.ImmutableSet<Label?>
            get() = com.google.common.collect.ImmutableSet.copyOf(targetParsingCompleteEvent.getFilteredLabels())

        val testFilteredTargets: com.google.common.collect.ImmutableSet<Label?>
            get() = com.google.common.collect.ImmutableSet.copyOf(targetParsingCompleteEvent.getTestFilteredLabels())

        val originalTargets: com.google.common.collect.ImmutableSet<Label?>
            get() = com.google.common.collect.ImmutableSet.copyOf(targetParsingCompleteEvent.getLabels())

        val originalPatternsToLabels: com.google.common.collect.ImmutableSetMultimap<String?, Label?>
            get() = targetParsingCompleteEvent.getOriginalPatternsToLabels()

        val testSuiteTargets: com.google.common.collect.ImmutableSet<Label?>
            get() = loadingPhaseCompleteEvent.getFilteredLabels()

        val testSuiteExpansions: MutableList<TestSuiteExpansion>
            get() = targetParsingCompleteEvent
                .asStreamProto(null)
                .getExpanded()
                .getTestSuiteExpansionsList()

        fun throwExceptionOnGetInputStream(path: Path, exn: IOException?) {
            fs.throwExceptionOnGetInputStream(path, exn)
        }

        fun filteredEvents(): Iterable<com.google.devtools.build.lib.events.Event?> {
            return com.google.common.collect.Iterables.filter<com.google.devtools.build.lib.events.Event?>(
                storedErrors.getEvents(),
                com.google.common.base.Predicate { event: com.google.devtools.build.lib.events.Event? -> event.getKind() != com.google.devtools.build.lib.events.EventKind.PROGRESS })
        }

        fun assertNoEvents() {
            MoreAsserts.assertNoEvents(filteredEvents())
        }

        fun assertContainsWarning(expectedMessage: String?): com.google.devtools.build.lib.events.Event? {
            return MoreAsserts.assertContainsEvent(
                filteredEvents(),
                expectedMessage,
                com.google.devtools.build.lib.events.EventKind.WARNING
            )
        }

        fun assertContainsError(expectedMessage: String?): com.google.devtools.build.lib.events.Event? {
            return MoreAsserts.assertContainsEvent(
                filteredEvents(),
                expectedMessage,
                com.google.devtools.build.lib.events.EventKind.ERRORS
            )
        }

        fun assertContainsEventWithFrequency(expectedMessage: String?, expectedFrequency: Int) {
            MoreAsserts.assertContainsEventWithFrequency(
                filteredEvents(), expectedMessage, expectedFrequency
            )
        }

        fun <T : Postable?> findPost(clazz: java.lang.Class<T?>): T? {
            return com.google.common.collect.Iterators.getNext<T?>(
                com.google.common.collect.Iterators.filter<T?>(
                    storedErrors.getPosts().iterator(),
                    clazz
                ), null
            )
        }

        fun <T : Postable?> findPostOnce(clazz: java.lang.Class<T?>): T? {
            return com.google.common.collect.Iterables.getOnlyElement<T?>(
                com.google.common.collect.Iterables.filter<T?>(
                    storedErrors.getPosts(),
                    clazz
                )
            )
        }

        companion object {
            private fun createRuleClassProvider(): ConfiguredRuleClassProvider {
                val builder: ConfiguredRuleClassProvider.Builder = Builder()
                TestRuleClassProvider.addStandardRules(builder)
                builder.addRuleDefinition(
                    MockRule {
                        MockRule.define(
                            "fake_cc_library",
                            { b, env ->
                                b.add(attr("srcs", LABEL_LIST).legacyAllowAnyFileType())
                                    .add(attr("hdrs", LABEL_LIST).legacyAllowAnyFileType())
                            })
                    } as MockRule)
                builder.addRuleDefinition(
                    MockRule {
                        MockRule.ancestor(BaseRuleClasses.NativeBuildRule::class.java)
                            .type(RuleClassType.TEST)
                            .define(
                                "fake_cc_test",
                                { b, env ->
                                    b.add(attr("srcs", LABEL_LIST).legacyAllowAnyFileType())
                                        .add(attr("deps", LABEL_LIST).legacyAllowAnyFileType())
                                        .add(
                                            attr("size", STRING).nonconfigurable("policy").value("small")
                                        )
                                        .add(
                                            attr("timeout", STRING)
                                                .nonconfigurable("policy")
                                                .value("short")
                                        )
                                        .add(attr("flaky", BOOLEAN))
                                        .add(attr("shard_count", INTEGER))
                                        .add(attr("local", BOOLEAN).nonconfigurable("policy"))
                                })
                    } as MockRule)
                return builder.build()
            }

            @Throws(OptionsParsingException::class)
            private fun defaultBuildLanguageOptions(): BuildLanguageOptions? {
                val parser: OptionsParser =
                    OptionsParser.builder().optionsClasses(BuildLanguageOptions::class.java).build()
                parser.parse(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS)
                return parser.getOptions<O?>(BuildLanguageOptions::class.java)
            }
        }
    }

    /**
     * Custom [InMemoryFileSystem] that can be pre-configured per-file to throw a supplied
     * IOException instead of the usual behavior.
     */
    private class CustomInMemoryFs(manualClock: com.google.devtools.build.lib.testutil.ManualClock) :
        InMemoryFileSystem(manualClock, DigestHashFunction.SHA256) {
        private val pathsToErrorOnGetInputStream: MutableMap<PathFragment?, IOException?> =
            HashMap<PathFragment?, IOException?>()

        @kotlin.jvm.Synchronized
        fun throwExceptionOnGetInputStream(path: Path, exn: IOException?) {
            pathsToErrorOnGetInputStream.put(path.asFragment(), exn)
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        public override fun getInputStream(path: PathFragment): java.io.InputStream? {
            val exnToThrow: IOException? = pathsToErrorOnGetInputStream.get(path)
            if (exnToThrow != null) {
                throw exnToThrow
            }
            return super.getInputStream(path)
        }
    }

    companion object {
        @BeforeClass
        fun silenceLogger() {
            java.util.logging.Logger.getLogger(BuildView::class.java.getName()).setLevel(java.util.logging.Level.OFF)
        }

        private fun getLabels(vararg labels: String?): MutableList<Label?> {
            val result: MutableList<Label?> = java.util.ArrayList<Label?>()
            for (label in labels) {
                result.add(Label.parseCanonicalUnchecked(label))
            }
            return result
        }
    }
}
