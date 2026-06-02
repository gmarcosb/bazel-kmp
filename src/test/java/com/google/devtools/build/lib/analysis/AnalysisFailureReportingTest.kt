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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.configurationIdMessage

/** Analysis failure reporting tests.  */
@RunWith(JUnit4::class)
class AnalysisFailureReportingTest : AnalysisTestCase() {
    private val collector = AnalysisFailureEventCollector()

    // TODO(mschaller): The below is closer now because of e.g. DetailedExitCode/FailureDetail.
    // original(ulfjack): Don't check for exact error message wording; instead, add machine-readable
    // details to the events, and check for those. Also check if we can remove duplicate test coverage
    // for these errors, i.e., consolidate the failure reporting tests in this class.
    @Before
    fun setup() {
        // We only test failure cases in this class.
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        eventBus.register(collector)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMissingRequiredAttribute() {
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = "foo",  # missing "out" attribute
            cmd = "",
        )
        
        """.trimIndent()
        )
        val result: AnalysisResult = update(eventBus, defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//foo")
        assertThat(result.hasError()).isTrue()
        val topLevel: Label? = Label.parseCanonicalUnchecked("//foo")

        Truth.assertThat(collector.events.keySet()).containsExactly(topLevel)

        val topLevelCauses: MutableCollection<com.google.devtools.build.lib.causes.Cause?> =
            collector.events.get(topLevel)
        Truth.assertThat(topLevelCauses).hasSize(1)

        val cause: com.google.devtools.build.lib.causes.Cause? =
            com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.causes.Cause?>(
                topLevelCauses
            )
        Truth.assertThat(cause).isInstanceOf(LoadingFailedCause::class.java)
        assertThat(cause.label).isEqualTo(topLevel)
        Truth.assertThat((cause as LoadingFailedCause).message)
            .isEqualTo(
                "Target '//foo:foo' contains an error and its package is in error: //foo:foo: missing"
                        + " value for mandatory attribute 'outs' in 'genrule' rule"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMissingDependency() {
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            outs = ["foo.txt"],
            cmd = "command",
            tools = ["//bar"],
        )
        
        """.trimIndent()
        )
        val result: AnalysisResult = update(eventBus, defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//foo")
        assertThat(result.hasError()).isTrue()
        val topLevel: Label? = Label.parseCanonicalUnchecked("//foo")
        val causeLabel: Label = Label.parseCanonicalUnchecked("//bar")
        Truth.assertThat(collector.events.keySet()).containsExactly(topLevel)
        Truth.assertThat(collector.events.get(topLevel))
            .containsExactly(
                AnalysisFailedCause(
                    causeLabel,
                    collector.getOnlyConfigurationId(),
                    createPackageLoadingDetailedExitCode(
                        ("BUILD file not found in any of the following"
                                + " directories. Add a BUILD file to a directory to mark it as a"
                                + " package.\n"
                                + " - bar"),
                        Code.BUILD_FILE_MISSING
                    )
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpanderFailure() {
        scratch.file(
            "test/BUILD",
            """
        genrule(
            name = "bad",
            outs = ["bad.out"],
            cmd = "cp ${'$'}< ${'$'}@",  # Error to use ${'$'}< with no srcs
        )
        
        """.trimIndent()
        )
        val result: AnalysisResult =
            update(eventBus, defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//test:bad")
        assertThat(result.hasError()).isTrue()
        val topLevel: Label = Label.parseCanonicalUnchecked("//test:bad")
        Truth.assertThat(collector.events.keySet()).containsExactly(topLevel)
        Truth.assertThat(collector.events)
            .valuesForKey(topLevel)
            .containsExactly(
                AnalysisFailedCause(
                    topLevel,
                    collector.getOnlyConfigurationId(),
                    createAnalysisDetailedExitCode(
                        "in cmd attribute of genrule rule //test:bad: variable '$<' : no input file"
                    )
                )
            )
    }

    /**
     * This error gets reported twice - once when we try to analyze the //cycles1 target, and the
     * other time when we analyze the //c target (which depends on //cycles1). This test checks that
     * both use the same error message.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkCycleReportedExactlyOnce() {
        scratch.file(
            "gp/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'gp', deps = ['//p'])"
        )
        scratch.file(
            "p/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'p', deps = ['//c'])"
        )
        scratch.file(
            "c/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'c', deps = ['//cycles1'])"
        )
        val cycles1BuildFilePath: Path =
            scratch.file(
                "cycles1/BUILD",
                "load('//test_defs:foo_library.bzl', 'foo_library')",
                "foo_library(name = 'cycles1', srcs = glob(['*.sh']))"
            )
        cycles1BuildFilePath
            .getParentDirectory()
            .getRelative("cycles1.sh")
            .createSymbolicLink(PathFragment.create("cycles1.sh"))

        val result: AnalysisResult = update(eventBus, defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//gp")
        assertThat(result.hasError()).isTrue()

        val topLevel: Label? = Label.parseCanonicalUnchecked("//gp")
        val message =
            "Symlink issue while evaluating globs: Symlink cycle:" + " /workspace/cycles1/cycles1.sh"
        val code: Code? = Code.EVAL_GLOBS_SYMLINK_ERROR
        Truth.assertThat(collector.events.get(topLevel))
            .containsExactly(
                AnalysisFailedCause(
                    Label.parseCanonical("//cycles1"),
                    collector.getOnlyConfigurationId(),
                    createPackageLoadingDetailedExitCode(message, code)
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVisibilityError() {
        scratch.file(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'foo', deps = ['//bar'])"
        )
        scratch.file(
            "bar/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar', visibility = ['//visibility:private'])"
        )

        val result: AnalysisResult = update(eventBus, defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//foo")
        assertThat(result.hasError()).isTrue()

        val topLevel: Label? = Label.parseCanonicalUnchecked("//foo")
        Truth.assertThat(collector.events.get(topLevel))
            .containsExactly(
                AnalysisFailedCause(
                    Label.parseCanonical("//foo"),
                    collector.getOnlyConfigurationId(),
                    createAnalysisDetailedExitCode(
                        "in foo_library rule //foo:foo: "
                                + createVisibilityErrorMessage(
                            "target '//bar:bar'", "target '//foo:foo'"
                        )
                    )
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileVisibilityError() {
        scratch.file("foo/BUILD", "filegroup(name = 'foo', srcs = ['//bar:bar.sh'])")
        scratch.file("bar/BUILD", "exports_files(['bar.sh'], visibility = ['//visibility:private'])")
        scratch.file("bar/bar.sh")

        val result: AnalysisResult = update(eventBus, defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//foo")
        assertThat(result.hasError()).isTrue()

        val topLevel: Label? = Label.parseCanonicalUnchecked("//foo")
        Truth.assertThat(collector.events)
            .valuesForKey(topLevel)
            .containsExactly(
                AnalysisFailedCause(
                    Label.parseCanonical("//foo"),
                    collector.getOnlyConfigurationId(),
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(
                                ("in filegroup rule //foo:foo: "
                                        + createVisibilityErrorMessage(
                                    "target '//bar:bar.sh'", "target '//foo:foo'"
                                )
                                        + ". To set the visibility of that source file target, use the"
                                        + " exports_files() function")
                            )
                            .setAnalysis(
                                Analysis.newBuilder()
                                    .setCode(Analysis.Code.CONFIGURED_VALUE_CREATION_FAILED)
                            )
                            .build()
                    )
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVisibilityErrorNoKeepGoing() {
        scratch.file(
            "foo/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name = 'foo', srcs = ['test.sh'], deps = ['//bar'])"
        )
        scratch.file(
            "bar/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar', visibility = ['//visibility:private'])"
        )

        try {
            update(eventBus, defaultFlags(), "//foo")
        } catch (e: ViewCreationFailedException) {
            // Ignored; we check for the correct eventbus event below.
        }

        val topLevel: Label? = Label.parseCanonicalUnchecked("//foo")
        val expectedConfig: BuildConfigurationValue? =
            skyframeExecutor.getSkyframeBuildView().getBuildConfiguration()
        val message =
            ("in foo_test rule //foo:foo: "
                    + createVisibilityErrorMessage("target '//bar:bar'", "target '//foo:foo'"))
        Truth.assertThat(collector.events.get(topLevel))
            .containsExactly(
                AnalysisFailedCause(
                    Label.parseCanonical("//foo"),
                    configurationIdMessage(expectedConfig),
                    createAnalysisDetailedExitCode(message)
                )
            )
    }

    // TODO(ulfjack): Add more tests for
    // - a target that has multiple analysis errors (in the target itself)
    // - a visibility error in a dependency (not in the target itself)
    // - an error in a config condition
    // - a missing top-level target (does that even get this far?)
    // - a top-level target with an InvalidConfigurationException
    // - a top-level target with a ToolchainContextException
    // - a top-level target with a visibility attribute that points to a non-package_group
    // - a top-level target with a package_group that refers to a non-package_group
    // - aspect errors
    /** Class to collect analysis failures.  */
    class AnalysisFailureEventCollector {
        private val events: com.google.common.collect.Multimap<Label?, com.google.devtools.build.lib.causes.Cause?> =
            com.google.common.collect.HashMultimap.create<Label?, com.google.devtools.build.lib.causes.Cause?>()

        @com.google.common.eventbus.Subscribe
        fun failureEvent(event: AnalysisFailureEvent) {
            val failedTarget: ConfiguredTargetKey = event.getFailedTarget()
            events.putAll(failedTarget.getLabel(), event.getRootCauses().toList())
        }

        private fun getOnlyConfigurationId(): ConfigurationId {
            // Analysis errors after the target's configuration has been determined are reported using a
            // possibly transitioned ID which is hard to retrieve from the graph if analysis of that
            // target fails. This method simply extracts them from the event ID.
            return
            com.google.common.collect.Iterables.getOnlyElement<MutableMap.MutableEntry<Label?, com.google.devtools.build.lib.causes.Cause?>?>(
                events.entries()
            )
                .getValue().idProto
                .getConfiguredLabel()
                .getConfiguration()
        }
    }

    companion object {
        fun createPackageLoadingDetailedExitCode(message: String?, code: Code?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setPackageLoading(PackageLoading.newBuilder().setCode(code))
                    .build()
            )
        }

        fun createAnalysisDetailedExitCode(message: String?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setAnalysis(
                        Analysis.newBuilder().setCode(Analysis.Code.CONFIGURED_VALUE_CREATION_FAILED)
                    )
                    .build()
            )
        }

        private fun createVisibilityErrorMessage(from: String?, to: String?): String {
            return java.lang.String.format(
                ("Visibility error:\n"
                        + "%s is not visible from\n"
                        + "%s\n"
                        + "Recommendation: modify the visibility declaration if you think the dependency"
                        + " is legitimate. For more info see https://bazel.build/concepts/visibility"),
                from, to
            )
        }
    }
}
