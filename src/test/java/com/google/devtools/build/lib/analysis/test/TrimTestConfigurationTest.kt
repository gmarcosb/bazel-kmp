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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.packages.Attribute.attr

/** BUILD-level Tests for test_trim_configuration.  */
@RunWith(JUnit4::class)
class TrimTestConfigurationTest : AnalysisTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        setRulesAvailableInTests(NATIVE_LIB_RULE)
        scratch.file(
            "test/test.bzl",
            """
        def _starlark_test_impl(ctx):
            executable = ctx.actions.declare_file(ctx.label.name)
            ctx.actions.write(executable, "#!/bin/true", is_executable = True)
            return DefaultInfo(
                executable = executable,
            )

        starlark_test = rule(
            implementation = _starlark_test_impl,
            test = True,
            executable = True,
            attrs = {
                "deps": attr.label_list(),
                "exec_deps": attr.label_list(cfg = "exec"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/lib.bzl",
            """
        def _starlark_lib_impl(ctx):
            pass

        starlark_lib = rule(
            implementation = _starlark_lib_impl,
            attrs = {
                "deps": attr.label_list(),
                "exec_deps": attr.label_list(cfg = "exec"),
            },
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOffDifferentTestOptions_ResultsInDifferentCTs() {
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        test_suite(
            name = "suite",
            tests = [
                ":starlark_test",
            ],
        )

        starlark_test(
            name = "starlark_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        native_lib(
            name = "native_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        starlark_lib(
            name = "starlark_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        native_lib(
            name = "native_shared_dep",
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        
        """.trimIndent()
        )
        useConfiguration("--notrim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA")
        update(
            "//test:suite",
            "//test:starlark_test",
            "//test:native_dep",
            "//test:starlark_dep",
            "//test:native_shared_dep",
            "//test:starlark_shared_dep"
        )
        val visitedTargets: LinkedHashSet<ActionLookupKey?> =
            LinkedHashSet<ActionLookupKey?>(getSkyframeEvaluatedTargetKeys())
        // asserting that the top-level targets are the same as the ones in the diamond starting at
        // //test:suite
        assertNumberOfConfigurationsOfTargets(
            visitedTargets,
            com.google.common.collect.ImmutableMap.Builder<String?, Int?>()
                .put("//test:suite", 1)
                .put("//test:starlark_test", 1)
                .put("//test:native_dep", 1)
                .put("//test:starlark_dep", 1)
                .put("//test:native_shared_dep", 1)
                .put("//test:starlark_shared_dep", 1)
                .build()
        )

        useConfiguration("--notrim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeB")
        update(
            "//test:suite",
            "//test:starlark_test",
            "//test:native_dep",
            "//test:starlark_dep",
            "//test:native_shared_dep",
            "//test:starlark_shared_dep"
        )
        visitedTargets.addAll(getSkyframeEvaluatedTargetKeys())
        // asserting that we got no overlap between the two runs, we had to build different versions of
        // all seven targets
        assertNumberOfConfigurationsOfTargets(
            visitedTargets,
            com.google.common.collect.ImmutableMap.Builder<String?, Int?>()
                .put("//test:suite", 2)
                .put("//test:starlark_test", 2)
                .put("//test:native_dep", 2)
                .put("//test:starlark_dep", 2)
                .put("//test:native_shared_dep", 2)
                .put("//test:starlark_shared_dep", 2)
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOffDifferentTestOptions_CacheCleared() {
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        test_suite(
            name = "suite",
            tests = [
                ":starlark_test",
            ],
        )

        starlark_test(
            name = "starlark_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        native_lib(
            name = "native_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        starlark_lib(
            name = "starlark_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        native_lib(
            name = "native_shared_dep",
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        
        """.trimIndent()
        )
        useConfiguration("--notrim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA")
        update("//test:suite")
        useConfiguration("--notrim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeB")
        update("//test:suite")
        useConfiguration("--notrim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA")
        update("//test:suite")
        // asserting that we got no overlap between the first and third runs, we had to reanalyze all
        // seven targets
        assertNumberOfConfigurationsOfTargets(
            getSkyframeEvaluatedTargetKeys(),
            com.google.common.collect.ImmutableMap.Builder<String?, Int?>()
                .put("//test:suite", 1)
                .put("//test:starlark_test", 1)
                .put("//test:native_dep", 1)
                .put("//test:starlark_dep", 1)
                .put("//test:native_shared_dep", 1)
                .put("//test:starlark_shared_dep", 1)
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOnDifferentTestOptions_SharesCTsForNonTestRules() {
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        test_suite(
            name = "suite",
            tests = [
                ":starlark_test",
            ],
        )

        starlark_test(
            name = "starlark_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        native_lib(
            name = "native_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        starlark_lib(
            name = "starlark_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        native_lib(
            name = "native_shared_dep",
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        
        """.trimIndent()
        )
        useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA")
        update(
            "//test:suite",
            "//test:starlark_test",
            "//test:native_dep",
            "//test:starlark_dep",
            "//test:native_shared_dep",
            "//test:starlark_shared_dep"
        )
        val visitedTargetKeys: LinkedHashSet<ActionLookupKey?> =
            LinkedHashSet<ActionLookupKey?>(this.evaluatedTargetValueKeys)
        // asserting that the top-level targets are the same as the ones in the diamond starting at
        // //test:suite
        assertNumberOfConfigurationsOfTargets(
            visitedTargetKeys,
            com.google.common.collect.ImmutableMap.Builder<String?, Int?>()
                .put("//test:suite", 1)
                .put("//test:starlark_test", 1)
                .put("//test:native_dep", 1)
                .put("//test:starlark_dep", 1)
                .put("//test:native_shared_dep", 1)
                .put("//test:starlark_shared_dep", 1)
                .build()
        )

        useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeB")
        update(
            "//test:suite",
            "//test:starlark_test",
            "//test:native_dep",
            "//test:starlark_dep",
            "//test:native_shared_dep",
            "//test:starlark_shared_dep"
        )
        visitedTargetKeys.addAll(this.evaluatedTargetValueKeys)

        // asserting that our non-test rules matched between the two runs, we had to build different
        // versions of the three test targets but not the four non-test targets
        assertNumberOfConfigurationsOfTargets(
            visitedTargetKeys,
            com.google.common.collect.ImmutableMap.Builder<String?, Int?>()
                .put("//test:suite", 2)
                .put("//test:starlark_test", 2)
                .put("//test:native_dep", 1)
                .put("//test:starlark_dep", 1)
                .put("//test:native_shared_dep", 1)
                .put("//test:starlark_shared_dep", 1)
                .build()
        )
    }

    @get:Throws(java.lang.InterruptedException::class)
    private val evaluatedTargetValueKeys: com.google.common.collect.ImmutableSet<ActionLookupKey?>
        get() {
            val evaluator: MemoizingEvaluator = skyframeExecutor.getEvaluator()
            val result: com.google.common.collect.ImmutableSet.Builder<ActionLookupKey?> =
                com.google.common.collect.ImmutableSet.builder<ActionLookupKey?>()
            for (key in getSkyframeEvaluatedTargetKeys()) {
                result.add(
                    (evaluator.getExistingValue(key) as ConfiguredTargetValue)
                        .getConfiguredTarget()
                        .getLookupKey()
                )
            }
            return result.build()
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOnDifferentTestOptions_CacheKeptBetweenRuns() {
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        test_suite(
            name = "suite",
            tests = [
                ":starlark_test",
            ],
        )

        starlark_test(
            name = "starlark_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        native_lib(
            name = "native_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        starlark_lib(
            name = "starlark_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        native_lib(
            name = "native_shared_dep",
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        
        """.trimIndent()
        )
        useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA")
        update("//test:suite")
        useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeB")
        update("//test:suite")
        // asserting that the non-test rules were cached from the last run and did not need to be run
        // again
        assertNumberOfConfigurationsOfTargets(
            getSkyframeEvaluatedTargetKeys(),
            com.google.common.collect.ImmutableMap.Builder<String?, Int?>()
                .put("//test:native_dep", 0)
                .put("//test:starlark_dep", 0)
                .put("//test:native_shared_dep", 0)
                .put("//test:starlark_shared_dep", 0)
                .build()
        )
        useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA")
        update("//test:suite")
        // asserting that the test rules were cached from the first run and did not need to be run again
        assertNumberOfConfigurationsOfTargets(
            getSkyframeEvaluatedTargetKeys(),
            com.google.common.collect.ImmutableMap.Builder<String?, Int?>()
                .put("//test:suite", 0)
                .put("//test:starlark_test", 0)
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOnDifferentNonTestOptions_CacheCleared() {
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        test_suite(
            name = "suite",
            tests = [
                ":starlark_test",
            ],
        )

        starlark_test(
            name = "starlark_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        native_lib(
            name = "native_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        starlark_lib(
            name = "starlark_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        native_lib(
            name = "native_shared_dep",
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        
        """.trimIndent()
        )
        useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--define=Test=TypeA")
        update("//test:suite")
        useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--define=Test=TypeB")
        update("//test:suite")
        useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--define=Test=TypeA")
        update("//test:suite")
        // asserting that we got no overlap between the first and third runs, we had to reanalyze all
        // seven targets
        assertNumberOfConfigurationsOfTargets(
            getSkyframeEvaluatedTargetKeys(),
            com.google.common.collect.ImmutableMap.Builder<String?, Int?>()
                .put("//test:suite", 1)
                .put("//test:starlark_test", 1)
                .put("//test:native_dep", 1)
                .put("//test:starlark_dep", 1)
                .put("//test:native_shared_dep", 1)
                .put("//test:starlark_shared_dep", 1)
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOffToOn_CacheCleared() {
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        test_suite(
            name = "suite",
            tests = [
                ":starlark_test",
            ],
        )

        starlark_test(
            name = "starlark_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        native_lib(
            name = "native_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        starlark_lib(
            name = "starlark_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        native_lib(
            name = "native_shared_dep",
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        
        """.trimIndent()
        )
        useConfiguration("--notrim_test_configuration", "--noexpand_test_suites")
        update("//test:suite")
        useConfiguration("--trim_test_configuration", "--noexpand_test_suites")
        update("//test:suite")
        // asserting that we got no overlap between the first and second runs, we had to reanalyze all
        // seven targets
        assertNumberOfConfigurationsOfTargets(
            getSkyframeEvaluatedTargetKeys(),
            com.google.common.collect.ImmutableMap.Builder<String?, Int?>()
                .put("//test:suite", 1)
                .put("//test:starlark_test", 1)
                .put("//test:native_dep", 1)
                .put("//test:starlark_dep", 1)
                .put("//test:native_shared_dep", 1)
                .put("//test:starlark_shared_dep", 1)
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOnToOff_CacheCleared() {
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        test_suite(
            name = "suite",
            tests = [
                ":starlark_test",
            ],
        )

        starlark_test(
            name = "starlark_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        native_lib(
            name = "native_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        starlark_lib(
            name = "starlark_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        native_lib(
            name = "native_shared_dep",
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        
        """.trimIndent()
        )
        useConfiguration("--trim_test_configuration", "--noexpand_test_suites")
        update("//test:suite")
        useConfiguration("--notrim_test_configuration", "--noexpand_test_suites")
        update("//test:suite")
        // asserting that we got no overlap between the first and second runs, we had to reanalyze all
        // seven targets
        assertNumberOfConfigurationsOfTargets(
            getSkyframeEvaluatedTargetKeys(),
            com.google.common.collect.ImmutableMap.Builder<String?, Int?>()
                .put("//test:suite", 1)
                .put("//test:starlark_test", 1)
                .put("//test:native_dep", 1)
                .put("//test:starlark_dep", 1)
                .put("//test:native_shared_dep", 1)
                .put("//test:starlark_shared_dep", 1)
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOnDynamicConfigsNotrimExecDeps_AreNotAnalyzedAnyExtraTimes() {
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        starlark_test(
            name = "starlark_outer_test",
            exec_deps = [
                ":starlark_test",
            ],
            deps = [
                ":starlark_test",
            ],
        )

        starlark_test(
            name = "starlark_test",
            exec_deps = [
                ":native_dep",
                ":starlark_dep",
            ],
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        native_lib(
            name = "native_dep",
            exec_deps = [
                ":native_shared_dep",
                "starlark_shared_dep",
            ],
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        starlark_lib(
            name = "starlark_dep",
            exec_deps = [
                ":native_shared_dep",
                "starlark_shared_dep",
            ],
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        native_lib(
            name = "native_shared_dep",
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        
        """.trimIndent()
        )
        useConfiguration("--trim_test_configuration")
        update(
            "//test:starlark_outer_test",
            "//test:starlark_test",
            "//test:native_dep",
            "//test:starlark_dep",
            "//test:native_shared_dep",
            "//test:starlark_shared_dep"
        )
        val visitedTargets: LinkedHashSet<ActionLookupKey?> =
            LinkedHashSet<ActionLookupKey?>(getSkyframeEvaluatedTargetKeys())
        assertNumberOfConfigurationsOfTargets(
            visitedTargets,
            com.google.common.collect.ImmutableMap.Builder<String?, Int?>() // Top-level and exec.
                .put("//test:starlark_test", 2) // Target and exec.
                .put("//test:native_dep", 2)
                .put("//test:starlark_dep", 2)
                .put("//test:native_shared_dep", 2)
                .put("//test:starlark_shared_dep", 2)
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOffConfigSetting_CanInspectTestOptions() {
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        config_setting(
            name = "test_mode",
            values = {"test_arg": "TypeA"},
        )

        starlark_test(
            name = "starlark_test",
            deps = select({
                ":test_mode": [":starlark_shared_dep"],
                "//conditions:default": [],
            }),
        )

        starlark_lib(
            name = "starlark_dep",
            deps = select({
                ":test_mode": [":starlark_shared_dep"],
                "//conditions:default": [],
            }),
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        
        """.trimIndent()
        )
        useConfiguration("--notrim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA")
        update("//test:test_mode", "//test:starlark_test", "//test:starlark_dep")
        // All 3 targets (top level, under a test, under a non-test) should successfully analyze.
        assertThat(getAnalysisResult().getTargetsToBuild()).hasSize(3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOnConfigSetting_skipsTryingToInspectTestOptions() {
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        config_setting(
            name = "test_mode",
            values = {"test_arg": "TypeA"},
        )

        starlark_test(
            name = "starlark_test",
            deps = select({
                ":test_mode": [":starlark_shared_dep"],
                "//conditions:default": [],
            }),
        )

        starlark_lib(
            name = "starlark_dep",
            deps = select({
                ":test_mode": [":starlark_shared_dep"],
                "//conditions:default": [],
            }),
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        
        """.trimIndent()
        )
        useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA")
        update("//test:starlark_dep")
        assertThat(getAnalysisResult().getTargetsToBuild()).hasSize(1)

        update("//test:test_mode", "//test:starlark_test")
        // When reached through only test targets (top level, under a test) analysis should succeed
        assertThat(getAnalysisResult().getTargetsToBuild()).hasSize(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOffNonTestTargetWithTestDependencies_IsPermitted() {
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        starlark_lib(
            name = "starlark_dep",
            testonly = 1,
            deps = [":starlark_test"],
        )

        starlark_test(
            name = "starlark_test",
        )
        
        """.trimIndent()
        )
        useConfiguration("--notrim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA")
        update("//test:starlark_dep")
        assertThat(getAnalysisResult().getTargetsToBuild()).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOnNonTestTargetWithTestDependencies_IsPermitted() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        starlark_lib(
            name = "starlark_dep",
            testonly = 1,
            deps = [":starlark_test"],
        )

        starlark_test(
            name = "starlark_test",
        )
        
        """.trimIndent()
        )
        useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA")
        update("//test:starlark_dep")
        assertThat(getAnalysisResult().getTargetsToBuild()).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOnNonTestTargetWithTestSuiteDependencies_IsPermitted() {
        // reporter.removeHandler(failFastHandler);
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        starlark_lib(
            name = "starlark_dep",
            testonly = 1,
            deps = [":a_test_suite"],
        )

        starlark_test(
            name = "starlark_test",
        )

        test_suite(
            name = "a_test_suite",
            tests = [":starlark_test"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA")
        update("//test:starlark_dep")
        assertThat(getAnalysisResult().getTargetsToBuild()).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOnNonTestTargetWithJavaTestDependencies_IsPermitted() {
        // reporter.removeHandler(failFastHandler);
        scratch.file(
            "test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        load(":lib.bzl", "starlark_lib")

        starlark_lib(
            name = "starlark_dep",
            testonly = 1,
            deps = [":JavaTest"],
        )

        java_test(
            name = "JavaTest",
            srcs = ["JavaTest.java"],
            test_class = "test.JavaTest",
        )
        
        """.trimIndent()
        )
        useConfiguration(
            "--trim_test_configuration",
            "--noexpand_test_suites",
            "--test_arg=TypeA",
            "--experimental_google_legacy_api"
        )
        update("//test:starlark_dep")
        assertThat(getAnalysisResult().getTargetsToBuild()).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOnTestSuiteWithTestDependencies_CanBeAnalyzed() {
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        test_suite(
            name = "suite",
            tests = [
                ":starlark_test",
                ":suite_2",
            ],
        )

        test_suite(
            name = "suite_2",
            tests = [
                ":starlark_test_2",
                ":starlark_test_3",
            ],
        )

        starlark_test(
            name = "starlark_test",
        )

        starlark_test(
            name = "starlark_test_2",
        )

        starlark_test(
            name = "starlark_test_3",
        )
        
        """.trimIndent()
        )
        useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA")
        update("//test:suite", "//test:suite_2")
        assertThat(getAnalysisResult().getTargetsToBuild()).hasSize(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOnNonTestTargetWithTestDependencies_isTrimmed() {
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        starlark_lib(
            name = "starlark_dep",
            testonly = 1,
            deps = [":starlark_test"],
        )

        starlark_test(
            name = "starlark_test",
        )
        
        """.trimIndent()
        )
        useConfiguration(
            "--trim_test_configuration", "--noexperimental_retain_test_configuration_across_testonly"
        )
        update("//test:starlark_dep")
        val top: ConfiguredTarget = getConfiguredTarget("//test:starlark_dep")
        assertThat(getConfiguration(top).hasFragment(TestConfiguration::class.java)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOnNonTestTargetWithTestDependencies_isNotTrimmedWithExperimentalFlag() {
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        starlark_lib(
            name = "starlark_dep",
            testonly = 1,
            deps = [":starlark_test"],
        )

        starlark_test(
            name = "starlark_test",
        )
        
        """.trimIndent()
        )
        useConfiguration(
            "--trim_test_configuration", "--experimental_retain_test_configuration_across_testonly"
        )
        update("//test:starlark_dep")
        val top: ConfiguredTarget = getConfiguredTarget("//test:starlark_dep")
        assertThat(getConfiguration(top).hasFragment(TestConfiguration::class.java)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagOnNonTestTargetWithMagicTransitiveConfigs_isNotTrimmed() {
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        starlark_lib(
            name = "starlark_dep",
            testonly = 1,
            transitive_configs = ["//command_line_option/fragment:test"],
            deps = [],
        )
        
        """.trimIndent()
        )
        useConfiguration("--trim_test_configuration")
        update("//test:starlark_dep")
        val top: ConfiguredTarget = getConfiguredTarget("//test:starlark_dep")
        assertThat(getConfiguration(top).hasFragment(TestConfiguration::class.java)).isTrue()
    }

    companion object {
        private val NATIVE_LIB_RULE: RuleDefinition = MockRule {
            ancestor(BaseRuleClasses.NativeBuildRule::class.java)
                .define(
                    "native_lib",
                    attr("deps", LABEL_LIST).allowedFileTypes(),
                    attr("exec_deps", LABEL_LIST)
                        .cfg(ExecutionTransitionFactory.createFactory())
                        .allowedFileTypes()
                )
        } as MockRule

        private fun assertNumberOfConfigurationsOfTargets(
            keys: MutableSet<out ActionLookupKey?>, targetsWithCounts: MutableMap<String?, Int?>
        ) {
            val actualSet: com.google.common.collect.ImmutableMultiset<Label?> =
                keys.stream()
                    .filter { key: ActionLookupKey? -> key is ConfiguredTargetKey }
                    .map<Any?>(ArtifactOwner::getLabel)
                    .collect(com.google.common.collect.ImmutableMultiset.toImmutableMultiset<Any?>())
            val expected: com.google.common.collect.ImmutableMap<Label?, Int?> =
                targetsWithCounts.entries.stream()
                    .collect(
                        com.google.common.collect.ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                            java.util.function.Function { entry: Any? -> Label.parseCanonicalUnchecked(entry.getKey()) },
                            java.util.function.Function { obj: Any? -> obj.value })
                    )
            val actual: com.google.common.collect.ImmutableMap<Label?, Int?> =
                expected.keys.stream().collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<Label?, Label?, Int?>(
                        java.util.function.Function { label: Label? -> label },
                        java.util.function.Function { element: Label? -> actualSet.count(element) })
                )
            Truth.assertThat(actual).containsExactlyEntriesIn(expected)
        }
    }
}
