// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** [com.google.devtools.build.lib.analysis.test.TestActionBuilder] tests.  */
@RunWith(TestParameterInjector::class)
class TestActionBuilderTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun createBuildFile() {
        analysisMock.pySupport().setup(mockToolsConfig)

        scratch.file(
            "tests/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_test(name = 'small_test_1',",
            "        srcs = ['small_test_1.py'],",
            "        data = [':xUnit'],",
            "        size = 'small',",
            "        tags = ['tag1'])",
            "",
            "foo_test(name = 'small_test_2',",
            "        srcs = ['small_test_2.sh'],",
            "        size = 'small',",
            "        tags = ['tag2'])",
            "",
            "foo_test(name = 'large_test_1',",
            "        srcs = ['large_test_1.sh'],",
            "        data = [':xUnit'],",
            "        size = 'large',",
            "        tags = ['tag1'])",
            "",
            "foo_binary(name = 'notest',",
            "        srcs = ['notest.py'])",
            "foo_library(name = 'xUnit')",
            "",
            "test_suite(name = 'smallTests', tags=['small'])"
        )
    }

    private fun assertSharded(testRule: ConfiguredTarget?, expectSharding: Int) {
        val testStatusList: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact> =
            getTestStatusArtifacts(testRule)
        if (expectSharding == 0) {
            val testResult: Artifact? =
                com.google.common.collect.Iterables.getOnlyElement<Artifact.DerivedArtifact?>(testStatusList)
            val action: TestRunnerAction? = getGeneratingAction(testResult) as TestRunnerAction?
            assertThat(action.isSharded()).isFalse()
            assertThat(action.getExecutionSettings().getTotalShards()).isSameInstanceAs(0)
            assertThat(action.getShardNum()).isSameInstanceAs(0)
            return
        }

        val totalShards: Int = testStatusList.size
        val shardNumbers: MutableSet<Int> = HashSet<Int>()
        for (testResult in testStatusList) {
            val action: TestRunnerAction? = getGeneratingAction(testResult) as TestRunnerAction?
            assertThat(action.isSharded()).isTrue()
            assertThat(action.getExecutionSettings().getTotalShards()).isSameInstanceAs(totalShards)
            assertThat(action.getTestLog().getExecPath().getPathString())
                .endsWith(
                    java.lang.String.format("shard_%d_of_%d/test.log", action.getShardNum() + 1, totalShards)
                )
            shardNumbers.add(action.getShardNum())
        }
        Truth.assertThat(shardNumbers).isEqualTo(sequenceSet(0, totalShards))
        Truth.assertThat(shardNumbers).hasSize(expectSharding)
    }

    @Throws(IOException::class)
    private fun writeJavaTests() {
        scratch.file(
            "javatests/jt/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "RGT",
            srcs = ["RGT.java"],
        )

        java_test(
            name = "RGT_none",
            srcs = ["RGT.java"],
            shard_count = 0,
        )

        java_test(
            name = "RGT_many",
            srcs = ["RGT.java"],
            shard_count = 33,
        )

        java_test(
            name = "RGT_small",
            size = "small",
            srcs = ["RGT.java"],
        )

        java_test(
            name = "NoRunner",
            srcs = ["NoTestRunnerTest.java"],
            main_class = "NoTestRunnerTest.java",
            use_testrunner = 0,
        )
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun getShardRunfilesMappings(label: String?): com.google.common.collect.ImmutableList<MutableMap<PathFragment?, Artifact?>> {
        return getTestStatusArtifacts(label).stream()
            .map<Any?>(this::getGeneratingAction)
            .map<Any?> { a: Any? -> (a as TestRunnerAction).getRunfilesTree() }
            .map<Any?>(this::getGeneratingAction)
            .map<Any?> { a: Any? -> (a as RunfilesTreeAction).getRunfilesTree() }
            .map<Any?>(RunfilesTree::getMapping)
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesMappingCached() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "sh",
            srcs = ["a.sh"],
            shard_count = 2,
        )

        java_test(
            name = "java",
            srcs = ["Java.java"],
            shard_count = 2,
        )
        
        """.trimIndent()
        )

        val shMappings: com.google.common.collect.ImmutableList<MutableMap<PathFragment?, Artifact?>> =
            getShardRunfilesMappings("//a:sh")
        Truth.assertThat(shMappings).hasSize(2)
        Truth.assertThat(shMappings.get(0)).isSameInstanceAs(shMappings.get(1))

        val javaMappings: com.google.common.collect.ImmutableList<MutableMap<PathFragment?, Artifact?>> =
            getShardRunfilesMappings("//a:java")
        Truth.assertThat(javaMappings).hasSize(2)
        Truth.assertThat(javaMappings.get(0)).isSameInstanceAs(javaMappings.get(1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSharding() {
        useConfiguration("--test_sharding_strategy=explicit")

        assertSharded(getConfiguredTarget("//tests:small_test_1"), 0)
        assertSharded(getConfiguredTarget("//tests:large_test_1"), 0)

        writeJavaTests()
        assertSharded(getConfiguredTarget("//javatests/jt:NoRunner"), 0)
        assertSharded(getConfiguredTarget("//javatests/jt:RGT"), 0)
        assertSharded(getConfiguredTarget("//javatests/jt:RGT_small"), 0)
        assertSharded(getConfiguredTarget("//javatests/jt:RGT_none"), 0)

        // Has an explicit "shard_count" attribute.
        assertSharded(getConfiguredTarget("//javatests/jt:RGT_many"), 33)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShardingDisabled() {
        useConfiguration("--test_sharding_strategy=disabled")

        assertSharded(getConfiguredTarget("//tests:small_test_1"), 0)
        assertSharded(getConfiguredTarget("//tests:large_test_1"), 0)

        writeJavaTests()
        assertSharded(getConfiguredTarget("//javatests/jt:NoRunner"), 0)
        assertSharded(getConfiguredTarget("//javatests/jt:RGT"), 0)
        assertSharded(getConfiguredTarget("//javatests/jt:RGT_small"), 0)
        assertSharded(getConfiguredTarget("//javatests/jt:RGT_none"), 0)

        // Has an explicit "shard_count" attribute.
        assertSharded(getConfiguredTarget("//javatests/jt:RGT_many"), 0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShardingForced() {
        useConfiguration("--test_sharding_strategy=forced=5")

        assertSharded(getConfiguredTarget("//tests:small_test_1"), 5)
        assertSharded(getConfiguredTarget("//tests:large_test_1"), 5)

        writeJavaTests()
        assertSharded(getConfiguredTarget("//javatests/jt:NoRunner"), 5)
        assertSharded(getConfiguredTarget("//javatests/jt:RGT"), 5)
        assertSharded(getConfiguredTarget("//javatests/jt:RGT_small"), 5)
        assertSharded(getConfiguredTarget("//javatests/jt:RGT_none"), 5)
        assertSharded(getConfiguredTarget("//javatests/jt:RGT_many"), 5)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShardingForced_equalValue_equalChecksum() {
        useConfiguration("--test_sharding_strategy=forced=5")
        val config1: BuildConfigurationValue? = getTargetConfiguration()

        initializeSkyframeExecutor()

        useConfiguration("--test_sharding_strategy=forced=5")
        val config2: BuildConfigurationValue? = getTargetConfiguration()

        assertThat(config2).isEqualTo(config1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShardingForced_differentValue_differentChecksum() {
        useConfiguration("--test_sharding_strategy=forced=5")
        val config1: BuildConfigurationValue? = getTargetConfiguration()

        initializeSkyframeExecutor()

        useConfiguration("--test_sharding_strategy=forced=6")
        val config2: BuildConfigurationValue? = getTargetConfiguration()

        assertThat(config2).isNotEqualTo(config1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlakyAttributeValidation() {
        scratch.file(
            "flaky/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "good_test",
            srcs = ["a.sh"],
        )

        foo_test(
            name = "flaky_test",
            srcs = ["a.sh"],
            flaky = 1,
        )
        
        """.trimIndent()
        )
        var testStatus: Artifact? =
            com.google.common.collect.Iterables.getOnlyElement<Artifact.DerivedArtifact?>(getTestStatusArtifacts("//flaky:good_test"))
        assertThat(testStatus).isNotNull()
        var action: TestRunnerAction? = getGeneratingAction(testStatus) as TestRunnerAction?
        assertThat(action.getTestProperties().isFlaky()).isFalse()

        testStatus =
            com.google.common.collect.Iterables.getOnlyElement<Artifact.DerivedArtifact?>(getTestStatusArtifacts("//flaky:flaky_test"))
        assertThat(testStatus).isNotNull()
        action = getGeneratingAction(testStatus) as TestRunnerAction?
        assertThat(action.getTestProperties().isFlaky()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIllegalBooleanFlakySetting() {
        checkError(
            "flaky",
            "bad_test",
            "expected one of [False, True, 0, 1]",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name = 'bad_test',",
            "        srcs = ['a.sh'],",
            "        flaky = 2)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunsPerTest() {
        useConfiguration("--runs_per_test=2")
        val testStatusList: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact> =
            getTestStatusArtifacts("//tests:small_test_1")
        Truth.assertThat(testStatusList).hasSize(2)
        val testStatus1: Artifact = testStatusList.get(0)
        val testStatus2: Artifact = testStatusList.get(1)
        assertThat(testStatus1).isNotNull()
        assertThat(testStatus2).isNotNull()
        assertThat(testStatus2).isNotSameInstanceAs(testStatus1)
        assertThat(getGeneratingAction(testStatus2))
            .isNotSameInstanceAs(getGeneratingAction(testStatus1))
        com.google.common.truth.Subject.contains("tests/small_test_1/run_1_of_2/test")
        com.google.common.truth.Subject.contains("tests/small_test_1/run_2_of_2/test")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunsPerTestCanBeOverridden() {
        useConfiguration("--runs_per_test=1", "--runs_per_test=2")
        val testStatusList: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact> =
            getTestStatusArtifacts("//tests:small_test_1")
        Truth.assertThat(testStatusList).hasSize(2)
        val testStatus1: Artifact = testStatusList.get(0)
        val testStatus2: Artifact = testStatusList.get(1)
        assertThat(testStatus1).isNotNull()
        assertThat(testStatus2).isNotNull()
        assertThat(testStatus2).isNotSameInstanceAs(testStatus1)
        assertThat(getGeneratingAction(testStatus2))
            .isNotSameInstanceAs(getGeneratingAction(testStatus1))
        com.google.common.truth.Subject.contains("tests/small_test_1/run_1_of_2/test")
        com.google.common.truth.Subject.contains("tests/small_test_1/run_2_of_2/test")
    }

    /**
     * Test that test rules always construct with a standard timeout, either inferred from size or
     * explicitly set by attribute.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestTimeoutFlagOverridesTimeoutDefaultsValues() {
        scratch.file(
            "javatests/timeouts/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "small_no_timeout",
            size = "small",
            srcs = [],
        )

        java_test(
            name = "small_with_timeout",
            size = "small",
            timeout = "long",
            srcs = [],
        )
        
        """.trimIndent()
        )
        var testStatusList: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact> =
            getTestStatusArtifacts("//javatests/timeouts:small_no_timeout")
        var testAction: TestRunnerAction? =
            getGeneratingAction(
                com.google.common.collect.Iterables.get<Artifact.DerivedArtifact?>(
                    testStatusList,
                    0
                )
            ) as TestRunnerAction?
        var timeout: Int? = testAction.getTestProperties().getTimeout().getTimeoutSeconds()
        Truth.assertThat(timeout).isEqualTo(TestTimeout.SHORT.timeoutSeconds)

        testStatusList = getTestStatusArtifacts("//javatests/timeouts:small_with_timeout")
        testAction = getGeneratingAction(
            com.google.common.collect.Iterables.get<Artifact.DerivedArtifact?>(
                testStatusList,
                0
            )
        ) as TestRunnerAction?
        timeout = testAction.getTestProperties().getTimeout().getTimeoutSeconds()
        Truth.assertThat(timeout).isEqualTo(TestTimeout.LONG.timeoutSeconds)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunsPerTestWithSharding() {
        useConfiguration("--runs_per_test=2")
        scratch.file(
            "javatests/jt/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "RGT",
            srcs = ["RGT.java"],
            shard_count = 10,
        )
        
        """.trimIndent()
        )
        val testStatusList: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact> =
            getTestStatusArtifacts("//javatests/jt:RGT")
        Truth.assertThat(testStatusList).hasSize(20)
        val testStatus1: Artifact = testStatusList.get(0)
        val testStatus10: Artifact = testStatusList.get(9)
        val testStatus11: Artifact = testStatusList.get(10)
        assertThat(testStatus1).isNotNull()
        assertThat(testStatus10).isNotNull()
        assertThat(testStatus11).isNotNull()
        com.google.common.truth.Subject.contains("javatests/jt/RGT/shard_1_of_10_run_1_of_2/test")
        com.google.common.truth.Subject.contains("javatests/jt/RGT/shard_5_of_10_run_2_of_2/test")
        com.google.common.truth.Subject.contains("javatests/jt/RGT/shard_6_of_10_run_1_of_2/test")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectOverNonExpandingTestSuitesVisitsImplicitTests() {
        scratch.file(
            "BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name = 'test_a',",
            "        srcs = [':a.sh'])",
            "",
            "foo_test(name = 'test_b',",
            "        srcs = [':b.sh'])",
            "",
            "test_suite(name = 'suite'",
            ")"
        )
        writeLabelCollectionAspect()

        useLoadingOptions("--noexpand_test_suites")
        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//:suite"),
                com.google.common.collect.ImmutableList.of<String?>("//:aspect.bzl%a"),  /* keepGoing= */
                false,  /* loadingPhaseThreads= */
                1,  /* doAnalysis= */
                true,
                com.google.common.eventbus.EventBus()
            )
        val aspectValue: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
        val key: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonicalUnchecked("//:aspect.bzl")), "StructImpl"
            )
        val info: StructImpl = aspectValue.get(key) as StructImpl
        assertThat((info.getValue("labels") as Depset).getSet(String::class.java).toList())
            .containsExactly("@@//:suite", "@@//:test_a", "@@//:test_b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectOverNonExpandingTestSuitesVisitsExplicitTests() {
        scratch.file(
            "BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name = 'test_a',",
            "        srcs = [':a.sh'])",
            "",
            "foo_test(name = 'test_b',",
            "        srcs = [':b.sh'])",
            "",
            "test_suite(name = 'suite',",
            "           tests = [':test_b']",
            ")"
        )
        writeLabelCollectionAspect()

        useLoadingOptions("--noexpand_test_suites")
        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//:suite"),
                com.google.common.collect.ImmutableList.of<String?>("//:aspect.bzl%a"),  /* keepGoing= */
                false,  /* loadingPhaseThreads= */
                1,  /* doAnalysis= */
                true,
                com.google.common.eventbus.EventBus()
            )
        val aspectValue: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
        val key: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonicalUnchecked("//:aspect.bzl")), "StructImpl"
            )
        val info: StructImpl = aspectValue.get(key) as StructImpl
        assertThat((info.getValue("labels") as Depset).getSet(String::class.java).toList())
            .containsExactly("@@//:suite", "@@//:test_b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectOverExpandingTestSuitesDoesNotVisitSuite() {
        scratch.file(
            "BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name = 'test_a',",
            "        srcs = [':a.sh'])",
            "",
            "foo_test(name = 'test_b',",
            "        srcs = [':b.sh'])",
            "",
            "test_suite(name = 'suite',",
            ")"
        )
        writeLabelCollectionAspect()

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//:suite"),
                com.google.common.collect.ImmutableList.of<String?>("//:aspect.bzl%a"),  /* keepGoing= */
                false,  /* loadingPhaseThreads= */
                1,  /* doAnalysis= */
                true,
                com.google.common.eventbus.EventBus()
            )
        val key: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonicalUnchecked("//:aspect.bzl")), "StructImpl"
            )

        val labels: MutableList<String> = java.util.ArrayList<String>()
        for (a in analysisResult.getAspectsMap().values()) {
            val info: StructImpl = a.get(key) as StructImpl
            labels.addAll((info.getValue("labels") as Depset).getSet(String::class.java).toList())
        }
        Truth.assertThat(labels).containsExactly("@@//:test_a", "@@//:test_b")
    }

    @Throws(IOException::class)
    private fun writeLabelCollectionAspect() {
        scratch.file(
            "aspect.bzl",
            """
        StructImpl = provider(fields = ["labels"])

        def _impl(target, ctx):
            print(target.label)
            transitive = []
            if hasattr(ctx.rule.attr, "tests"):
                transitive += [dep[StructImpl].labels for dep in ctx.rule.attr.tests]
            if hasattr(ctx.rule.attr, "_implicit_tests"):
                transitive += [dep[StructImpl].labels for dep in ctx.rule.attr._implicit_tests]
            return [StructImpl(labels = depset([str(target.label)], transitive = transitive))]

        a = aspect(_impl, attr_aspects = ["tests", "_implicit_tests"])
        
        """.trimIndent()
        )
    }

    /** Regression test for bug []//b/2644860"">&quot;http://b/2644860&quot;.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIllegalTestSizeAttributeDoesNotCrashTestSuite() {
        checkError(
            "bad_size",
            "illegal_size_test",
            "In rule 'illegal_size_test', size 'bad' is not a valid size",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name = 'illegal_size_test',",
            "        srcs = ['illegal.sh'],",
            "        size = 'bad')",
            "test_suite(name = 'everything')"
        )
    }

    /** Regression test for bug []//b/2644860"">&quot;http://b/2644860&quot; but with an illegal Timeout.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIllegalTestTimeoutAttributeDoesNotCrashTestSuite() {
        checkError(
            "bad_timeout",
            "illegal_timeout_test",
            "In rule 'illegal_timeout_test', timeout 'unreasonable' is not a valid timeout",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name = 'illegal_timeout_test',",
            "        srcs = ['illegal.sh'],",
            "        timeout = 'unreasonable')",
            "test_suite(name = 'everything')"
        )
    }

    /**
     * With the legacy test toolchain, a test action will run on the first execution platform,
     * regardless of its constraints.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFirstExecPlatformWithLegacyTestToolchain(
        @TestParameter("linux", "macos") targetOs: String?,
        @TestParameter("x86_64", "aarch64") targetCpu: String?
    ) {
        scratch.file(
            "some_test.bzl",
            """
        def _some_test_impl(ctx):
            script = ctx.actions.declare_file(ctx.attr.name + ".sh")
            ctx.actions.write(script, "shell script goes here", is_executable = True)
            return [
                DefaultInfo(executable = script),
            ]

        some_test = rule(
            implementation = _some_test_impl,
            test = True,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "BUILD",
            "load(':some_test.bzl', 'some_test')",
            """
        constraint_setting(name = "exec")
        constraint_value(
            name = "is_exec",
            constraint_setting = ":exec",
        )

        [
            platform(
                name = "{}_{}_target".format(os, cpu),
                constraint_values = [
                    "%1${'$'}sos:" + os,
                    "%1${'$'}scpu:" + cpu,
                ],
            )
            for os in ["linux", "macos"]
            for cpu in ["x86_64", "aarch64"]
        ]

        [
            platform(
                name = "{}_{}_exec".format(os, cpu),
                constraint_values = [
                    "%1${'$'}sos:" + os,
                    "%1${'$'}scpu:" + cpu,
                    ":is_exec",
                ],
                exec_properties = {
                    "os": os,
                    "cpu": cpu,
                },
            )
            for os in ["linux", "macos"]
            for cpu in ["x86_64", "aarch64"]
        ]

        some_test(name = "some_test")
        
        """
                .trimIndent()
                .formatted(TestConstants.CONSTRAINTS_PACKAGE_ROOT)
        )
        useConfiguration(
            java.lang.String.format(
                "--no%s//tools/test:incompatible_use_default_test_toolchain",
                TestConstants.TOOLS_REPOSITORY.getCanonicalForm()
            ),
            "--platforms=//:%s_%s_target".formatted(targetOs, targetCpu),
            "--extra_execution_platforms=//:linux_x86_64_exec,//:linux_aarch64_exec,//:macos_x86_64_exec,//:macos_aarch64_exec"
        )
        val testStatusList: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact> =
            getTestStatusArtifacts("//:some_test")
        val testAction: TestRunnerAction? = getGeneratingAction(testStatusList.get(0)) as TestRunnerAction?
        assertThat(testAction.getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonicalUnchecked("//:linux_x86_64_exec"))
        assertThat(testAction.getExecProperties()).containsExactly("os", "linux", "cpu", "x86_64")
    }

    /**
     * With the default test toolchain, a test action should run on a platform that matches all
     * constraints of the target platform.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecPlatformMatchesTargetConstraintsWithDefaultTestToolchain(
        @TestParameter("linux", "macos") targetOs: String?,
        @TestParameter("x86_64", "aarch64") targetCpu: String?
    ) {
        scratch.file(
            "some_test.bzl",
            """
        def _some_test_impl(ctx):
            script = ctx.actions.declare_file(ctx.attr.name + ".sh")
            ctx.actions.write(script, "shell script goes here", is_executable = True)
            return [
                DefaultInfo(executable = script),
            ]

        some_test = rule(
            implementation = _some_test_impl,
            test = True,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "BUILD",
            "load(':some_test.bzl', 'some_test')",
            """
        constraint_setting(name = "exec")
        constraint_value(
            name = "is_exec",
            constraint_setting = ":exec",
        )

        [
            platform(
                name = "{}_{}_target".format(os, cpu),
                constraint_values = [
                    "%1${'$'}sos:" + os,
                    "%1${'$'}scpu:" + cpu,
                ],
            )
            for os in ["linux", "macos"]
            for cpu in ["x86_64", "aarch64"]
        ]

        [
            platform(
                name = "{}_{}_exec".format(os, cpu),
                constraint_values = [
                    "%1${'$'}sos:" + os,
                    "%1${'$'}scpu:" + cpu,
                    ":is_exec",
                ],
                exec_properties = {
                    "os": os,
                    "cpu": cpu,
                },
            )
            for os in ["linux", "macos"]
            for cpu in ["x86_64", "aarch64"]
        ]

        some_test(name = "some_test")
        
        """
                .trimIndent()
                .formatted(TestConstants.CONSTRAINTS_PACKAGE_ROOT)
        )
        useConfiguration(
            java.lang.String.format(
                "--%s//tools/test:incompatible_use_default_test_toolchain",
                TestConstants.TOOLS_REPOSITORY.getCanonicalForm()
            ),
            "--platforms=//:%s_%s_target".formatted(targetOs, targetCpu),
            "--extra_execution_platforms=//:linux_x86_64_exec,//:linux_aarch64_exec,//:macos_x86_64_exec,//:macos_aarch64_exec"
        )
        val testStatusList: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact> =
            getTestStatusArtifacts("//:some_test")
        val testAction: TestRunnerAction? = getGeneratingAction(testStatusList.get(0)) as TestRunnerAction?
        assertThat(testAction.getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonicalUnchecked("//:%s_%s_exec".formatted(targetOs, targetCpu)))
        assertThat(testAction.getExecProperties()).containsExactly("os", targetOs, "cpu", targetCpu)
    }

    /**
     * With the default test toolchain, a failure to find a suitable execution platform will result in
     * a toolchain resolution error.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoMatchingExecPlatformWithDefaultTestToolchain() {
        scratch.file(
            "some_test.bzl",
            """
        def _some_test_impl(ctx):
            script = ctx.actions.declare_file(ctx.attr.name + ".sh")
            ctx.actions.write(script, "shell script goes here", is_executable = True)
            return [
                DefaultInfo(executable = script),
            ]

        some_test = rule(
            implementation = _some_test_impl,
            test = True,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "BUILD",
            "load(':some_test.bzl', 'some_test')",
            """
        constraint_setting(name = "exec")
        constraint_value(
            name = "is_exec",
            constraint_setting = ":exec",
        )

        platform(
            name = "linux_x86_64_target",
            constraint_values = [
                "%1${'$'}sos:linux",
                "%1${'$'}scpu:x86_64",
            ],
        )

        platform(
            name = "macos_aarch64_exec",
            constraint_values = [
                "%1${'$'}sos:macos",
                "%1${'$'}scpu:aarch64",
                ":is_exec",
            ],
        )

        some_test(name = "some_test")
        
        """
                .trimIndent()
                .formatted(TestConstants.CONSTRAINTS_PACKAGE_ROOT)
        )
        useConfiguration(
            java.lang.String.format(
                "--%s//tools/test:incompatible_use_default_test_toolchain",
                TestConstants.TOOLS_REPOSITORY.getCanonicalForm()
            ),
            "--platforms=//:linux_x86_64_target",
            "--host_platform=//:macos_aarch64_exec",
            "--extra_execution_platforms=//:macos_aarch64_exec"
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//:some_test")).isNull()
        assertContainsEvent(
            java.util.regex.Pattern.compile(
                "While resolving toolchains for target //:some_test: No matching toolchains found for"
                        + " types:.*?//tools/test:default_test_toolchain_type"
            )
        )
    }

    /**
     * Overriding the exec group from within the test affects the way exec properties are selected.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOverrideExecGroup() {
        scratch.file(
            "some_test.bzl",
            """
        def _some_test_impl(ctx):
            script = ctx.actions.declare_file(ctx.attr.name + ".sh")
            ctx.actions.write(script, "shell script goes here", is_executable = True)
            return [
                DefaultInfo(executable = script),
                testing.ExecutionInfo({}, exec_group = "custom_group"),
            ]

        some_test = rule(
            implementation = _some_test_impl,
            exec_groups = {"custom_group": exec_group()},
            test = True,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "BUILD",
            "load(':some_test.bzl', 'some_test')",
            "some_test(",
            "    name = 'custom_exec_group_test',",
            "    exec_properties = {'test.key': 'bad', 'custom_group.key': 'good'},",
            ")"
        )
        val testStatusList: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact> =
            getTestStatusArtifacts("//:custom_exec_group_test")
        val testAction: TestRunnerAction? = getGeneratingAction(testStatusList.get(0)) as TestRunnerAction?
        val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>? = testAction.getExecutionInfo()
        Truth.assertThat(executionInfo).containsExactly("key", "good")
    }

    /**
     * Overriding the exec group from within the test with --use_target_platform_for_tests.
     * 
     * 
     * This is the same test as testOverrideExecGroup with --use_target_platform_for_tests and a
     * target platform.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOverrideTestExecGroup() {
        scratch.file(
            "some_test.bzl",
            """
        def _some_test_impl(ctx):
            script = ctx.actions.declare_file(ctx.attr.name + ".sh")
            ctx.actions.write(script, "shell script goes here", is_executable = True)
            return [
                DefaultInfo(executable = script),
                testing.ExecutionInfo({}, exec_group = "custom_group"),
            ]

        some_test = rule(
            implementation = _some_test_impl,
            exec_groups = {"custom_group": exec_group()},
            test = True,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "BUILD",
            "load(':some_test.bzl', 'some_test')",
            "platform(",
            "    name = 'linux_aarch64',",
            "    constraint_values = [",
            "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux',",
            "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:aarch64',",
            "    ],",
            ")",
            "some_test(",
            "    name = 'custom_exec_group_test',",
            "    exec_properties = {'test.key': 'bad', 'custom_group.key': 'good'},",
            ")"
        )
        useConfiguration("--use_target_platform_for_tests=true", "--platforms=//:linux_aarch64")
        val testStatusList: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact> =
            getTestStatusArtifacts("//:custom_exec_group_test")
        val testAction: TestRunnerAction? = getGeneratingAction(testStatusList.get(0)) as TestRunnerAction?
        val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>? = testAction.getExecutionInfo()
        Truth.assertThat(executionInfo).containsExactly("key", "good")
    }

    /** Adding exec_properties from the platform with --use_target_platform_for_tests.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetTestExecGroup() {
        scratch.file(
            "some_test.bzl",
            """
        def _some_test_impl(ctx):
            script = ctx.actions.declare_file(ctx.attr.name + ".sh")
            ctx.actions.write(script, "shell script goes here", is_executable = True)
            return [
                DefaultInfo(executable = script),
            ]

        some_test = rule(
            implementation = _some_test_impl,
            test = True,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "BUILD",
            "load(':some_test.bzl', 'some_test')",
            "platform(",
            "    name = 'linux_x86',",
            "    constraint_values = [",
            "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux',",
            "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64',",
            "    ],",
            "    exec_properties = {'keyhost': 'bad'},",
            ")",
            "platform(",
            "    name = 'linux_aarch64',",
            "    constraint_values = [",
            "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux',",
            "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:aarch64',",
            "    ],",
            "    exec_properties = {'key2': 'good'},",
            ")",
            "some_test(",
            "    name = 'exec_group_test',",
            "    exec_properties = {'key': 'bad'},",
            ")"
        )
        useConfiguration(
            "--use_target_platform_for_tests=true",
            "--platforms=//:linux_aarch64",
            "--host_platform=//:linux_x86"
        )
        val testStatusList: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact> =
            getTestStatusArtifacts("//:exec_group_test")
        val testAction: TestRunnerAction? = getGeneratingAction(testStatusList.get(0)) as TestRunnerAction?
        assertThat(testAction.getExecutionPlatform().label().getName()).isEqualTo("linux_aarch64")

        val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>? = testAction.getExecutionInfo()
        Truth.assertThat(executionInfo).containsExactly("key2", "good")
    }

    /** Adding test specific exec_properties with --use_target_platform_for_tests.  */
    @org.junit.Test
    @Ignore("https://github.com/bazelbuild/bazel/issues/17466")
    @Throws(java.lang.Exception::class)
    fun testTargetTestExecGroupInheritance() {
        useConfiguration(
            "--use_target_platform_for_tests=true",
            "--platforms=//:linux_aarch64",
            "--host_platform=//:linux_x86"
        )
        scratch.file(
            "some_test.bzl",
            """
        def _some_test_impl(ctx):
            script = ctx.actions.declare_file(ctx.attr.name + ".sh")
            ctx.actions.write(script, "shell script goes here", is_executable = True)
            return [
                DefaultInfo(executable = script),
            ]

        some_test = rule(
            implementation = _some_test_impl,
            test = True,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "BUILD",
            "load(':some_test.bzl', 'some_test')",
            "platform(",
            "    name = 'linux_x86',",
            "    constraint_values = [",
            "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux',",
            "       '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64',",
            "    ],",
            "    exec_properties = {'keyhost': 'bad'},",
            ")",
            "platform(",
            "    name = 'linux_aarch64',",
            "    constraint_values = [",
            "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux',",
            "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:aarch64',",
            "    ],",
            "    exec_properties = {'key2': 'good'},",
            ")",
            "some_test(",
            "    name = 'exec_group_test',",
            "    exec_properties = {'test.key': 'good', 'key': 'bad'},",
            ")"
        )
        val testStatusList: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact> =
            getTestStatusArtifacts("//:exec_group_test")
        val testAction: TestRunnerAction? = getGeneratingAction(testStatusList.get(0)) as TestRunnerAction?
        assertThat(testAction.getExecutionPlatform().label().getName()).isEqualTo("linux_aarch64")

        val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>? = testAction.getExecutionInfo()
        Truth.assertThat(executionInfo).containsExactly("key2", "good", "key", "good")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonExecutableCoverageReportGenerator() {
        useConfiguration(
            "--coverage_report_generator=//bad_gen:bad_cov_gen", "--collect_code_coverage"
        )
        checkError(
            "bad_gen",
            "some_test",
            "--coverage_report_generator does not refer to an executable target",
            "load('@rules_cc//cc:cc_test.bzl', 'cc_test')",
            "filegroup(name = 'bad_cov_gen')",
            "cc_test(name = 'some_test')"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun getTestStatusArtifacts(label: String?): com.google.common.collect.ImmutableList<Artifact.DerivedArtifact> {
        val target: ConfiguredTarget? = getConfiguredTarget(label)
        return target.getProvider(TestProvider::class.java).getTestParams().getTestStatusArtifacts()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunUnderConfiguredForTestExecPlatform() {
        scratch.file(
            "some_test.bzl",
            """
        def _some_test_impl(ctx):
            script = ctx.actions.declare_file(ctx.attr.name + ".sh")
            ctx.actions.run_shell(
                outputs = [script],
                inputs = [],
                command = "echo 'shell script goes here' > ${'$'}@",
            )
            return [
                DefaultInfo(executable = script),
                testing.ExecutionInfo(exec_group = "alternative_test"),
            ]

        some_test = rule(
            implementation = _some_test_impl,
            test = True,
            exec_groups = {
                "test": exec_group(
                    exec_compatible_with = [
                        "%1${'$'}sos:linux",
                    ],
                ),
                "alternative_test": exec_group(
                    exec_compatible_with = [
                        "%1${'$'}sos:android",
                    ],
                ),
            },
        )
        
        """
                .trimIndent()
                .formatted(TestConstants.CONSTRAINTS_PACKAGE_ROOT)
        )
        scratch.file(
            "BUILD",
            """
        load(':some_test.bzl', 'some_test')
        platform(
            name = "linux",
            constraint_values = [
                "%1${'$'}sos:linux",
            ],
        )
        platform(
            name = "windows",
            constraint_values = [
                "%1${'$'}sos:windows",
            ],
        )
        platform(
            name = "macos",
            constraint_values = [
                "%1${'$'}sos:macos",
            ],
        )
        platform(
            name = "android",
            constraint_values = [
                "%1${'$'}sos:android",
            ],
        )
        genrule(
            name = "run_under_tool",
            outs = ["run_under_tool.sh"],
            cmd = "echo 'runUnderTool' > ${'$'}@",
            executable = True,
        )
        some_test(
            name = "some_test",
            exec_compatible_with = ["%1${'$'}sos:macos"],
        )
        
        """
                .trimIndent()
                .formatted(TestConstants.CONSTRAINTS_PACKAGE_ROOT)
        )
        useConfiguration(
            "--run_under=//:run_under_tool",
            "--incompatible_bazel_test_exec_run_under",
            "--platforms=//:windows",
            "--host_platform=//:windows",
            "--extra_execution_platforms=//:windows,//:android,//:linux,//:macos"
        )

        val generateAction: Action? = getGeneratingAction(getExecutable("//:some_test"))
        assertThat(generateAction.getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonicalUnchecked("//:macos"))

        val testAction: Action? = getGeneratingAction(getTestStatusArtifacts("//:some_test").get(0))
        assertThat(testAction.getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonicalUnchecked("//:android"))

        val runUnderTool: Artifact? =
            testAction.getInputs().toList().stream()
                .filter({ artifact -> artifact.getExecPath().getBaseName().equals("run_under_tool.sh") })
                .findFirst()
                .orElseThrow()
        // TODO: The run_under_tool should be built for the exec platform of the test action, which
        //  differs from the exec platform of the "test" exec group due to testing.ExecutionInfo.
        //  Building for the "test" exec group is still preferred over building for the target platform
        //  or the default exec platform of the test rule.
        assertThat(
            (getGeneratingAction(runUnderTool).getOwner().getBuildConfigurationInfo() as BuildConfigurationValue)
                .getOptions()
                .get(PlatformOptions::class.java)
                .getPlatforms()
        )
            .containsExactly(Label.parseCanonicalUnchecked("//:linux"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineBuiltForTestExecutionOS() {
        scratch.file(
            "some_test.bzl",
            """
        def _some_test_impl(ctx):
            script = ctx.actions.declare_file(ctx.attr.name + ".sh")
            ctx.actions.run_shell(
                outputs = [script],
                inputs = [],
                command = "echo 'shell script goes here' > ${'$'}@",
            )
            return [
                DefaultInfo(executable = script),
            ]

        some_test = rule(
            implementation = _some_test_impl,
            test = True,
            exec_groups = {
                "test": exec_group(
                    exec_compatible_with = [
                        "%1${'$'}sos:macos",
                    ],
                ),
            },
        )
        
        """
                .trimIndent()
                .formatted(TestConstants.CONSTRAINTS_PACKAGE_ROOT)
        )
        scratch.file(
            "BUILD",
            """
        load(':some_test.bzl', 'some_test')
        platform(
            name = "linux",
            constraint_values = [
                "%1${'$'}sos:linux",
            ],
        )
        platform(
            name = "windows",
            constraint_values = [
                "%1${'$'}sos:windows",
            ],
        )
        platform(
            name = "macos",
            constraint_values = [
                "%1${'$'}sos:macos",
            ],
        )
        some_test(
            name = "some_test",
            exec_compatible_with = ["%1${'$'}sos:windows"],
        )
        
        """
                .trimIndent()
                .formatted(TestConstants.CONSTRAINTS_PACKAGE_ROOT)
        )
        useConfiguration(
            "--platforms=//:windows",
            "--host_platform=//:windows",
            "--extra_execution_platforms=//:windows,//:linux,//:macos"
        )

        val testAction: Action? = getGeneratingAction(getTestStatusArtifacts("//:some_test").get(0))
        assertThat((testAction as TestRunnerAction).getExecutionSettings().getExecutionOs())
            .isEqualTo(com.google.devtools.build.lib.util.OS.DARWIN)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineBuiltForTestExecutionOS_withExecutionInfo() {
        scratch.file(
            "some_test.bzl",
            """
        def _some_test_impl(ctx):
            script = ctx.actions.declare_file(ctx.attr.name + ".sh")
            ctx.actions.run_shell(
                outputs = [script],
                inputs = [],
                command = "echo 'shell script goes here' > ${'$'}@",
            )
            return [
                DefaultInfo(executable = script),
                testing.ExecutionInfo(exec_group = "alternative_test"),
            ]

        some_test = rule(
            implementation = _some_test_impl,
            test = True,
            exec_groups = {
                "test": exec_group(
                    exec_compatible_with = [
                        "%1${'$'}sos:macos",
                    ],
                ),
                "alternative_test": exec_group(
                    exec_compatible_with = [
                        "%1${'$'}sos:linux",
                    ],
                ),
            },
        )
        
        """
                .trimIndent()
                .formatted(TestConstants.CONSTRAINTS_PACKAGE_ROOT)
        )
        scratch.file(
            "BUILD",
            """
        load(':some_test.bzl', 'some_test')
        platform(
            name = "linux",
            constraint_values = [
                "%1${'$'}sos:linux",
            ],
        )
        platform(
            name = "windows",
            constraint_values = [
                "%1${'$'}sos:windows",
            ],
        )
        platform(
            name = "macos",
            constraint_values = [
                "%1${'$'}sos:macos",
            ],
        )
        some_test(
            name = "some_test",
            exec_compatible_with = ["%1${'$'}sos:windows"],
        )
        
        """
                .trimIndent()
                .formatted(TestConstants.CONSTRAINTS_PACKAGE_ROOT)
        )
        useConfiguration(
            "--platforms=//:windows",
            "--host_platform=//:windows",
            "--extra_execution_platforms=//:windows,//:linux,//:macos"
        )

        val testAction: Action? = getGeneratingAction(getTestStatusArtifacts("//:some_test").get(0))
        assertThat((testAction as TestRunnerAction).getExecutionSettings().getExecutionOs())
            .isEqualTo(com.google.devtools.build.lib.util.OS.LINUX)
    }

    private fun getTestStatusArtifacts(
        target: TransitiveInfoCollection
    ): com.google.common.collect.ImmutableList<Artifact.DerivedArtifact?> {
        return target.getProvider(TestProvider::class.java).getTestParams().getTestStatusArtifacts()
    }

    companion object {
        private fun sequenceSet(start: Int, end: Int): MutableSet<Int?> {
            com.google.common.base.Preconditions.checkArgument(end > start)
            val seqSet: MutableSet<Int?> = HashSet<Int?>()
            for (i in start..<end) {
                seqSet.add(i)
            }
            return seqSet
        }
    }
}
