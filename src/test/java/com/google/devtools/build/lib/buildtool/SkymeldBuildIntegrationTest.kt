// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.actions.BuildFailedException

/** Integration tests for project Skymeld: interleaving Skyframe's analysis and execution phases.  */
@RunWith(TestParameterInjector::class)
class SkymeldBuildIntegrationTest : BuildIntegrationTestCase() {
    private var eventsSubscriber: EventsSubscriber? = null

    @Before
    fun setUp() {
        addOptions("--experimental_merged_skyframe_analysis_execution")
        this.eventsSubscriber = EventsSubscriber()
        runtimeWrapper.registerSubscriber(eventsSubscriber)
    }

    /** A simple rule that has srcs, deps and writes these attributes to its output.  */
    @Throws(IOException::class)
    private fun writeMyRuleBzl() {
        write(
            "foo/my_rule.bzl",
            """
        def _path(file):
            return file.path

        def _impl(ctx):
            inputs = depset(
                ctx.files.srcs,
                transitive = [dep[DefaultInfo].files for dep in ctx.attr.deps],
            )
            output = ctx.actions.declare_file(ctx.attr.name + ".out")
            command = "echo ${'$'}@ > %s" % (output.path)
            args = ctx.actions.args()
            args.add_all(inputs, map_each = _path)
            ctx.actions.run_shell(
                inputs = inputs,
                outputs = [output],
                command = command,
                arguments = [args],
            )
            return DefaultInfo(files = depset([output]))

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "srcs": attr.label_list(allow_files = True),
                "deps": attr.label_list(),
            },
        )
        
        """.trimIndent()
        )
    }

    @Throws(IOException::class)
    private fun writeAnalysisFailureAspectBzl() {
        write(
            "foo/aspect.bzl",
            """
        def _aspect_impl(target, ctx):
            malformed

        analysis_err_aspect = aspect(implementation = _aspect_impl)
        
        """.trimIndent()
        )
    }

    @Throws(IOException::class)
    private fun writeExecutionFailureAspectBzl() {
        write(
            "foo/aspect.bzl",
            """
        def _aspect_impl(target, ctx):
            output = ctx.actions.declare_file("aspect_output")
            ctx.actions.run_shell(
                outputs = [output],
                command = "false",
            )
            return [OutputGroupInfo(
                files = depset([output]),
            )]

        execution_err_aspect = aspect(implementation = _aspect_impl)
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun writeEnvironmentRules(vararg defaults: String?) {
        val defaultsBuilder: java.lang.StringBuilder = java.lang.StringBuilder()
        for (defaultEnv in defaults) {
            defaultsBuilder.append("'").append(defaultEnv).append("', ")
        }

        write(
            "buildenv/BUILD",
            "environment_group(",
            "    name = 'group',",
            "    environments = [':one', ':two'],",
            "    defaults = [" + defaultsBuilder + "])",
            "environment(name = 'one')",
            "environment(name = 'two')"
        )
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.Exception::class)
    private fun assertSingleOutputBuilt(target: String?): Path {
        val singleOutput: Path =
            com.google.common.collect.Iterables.getOnlyElement<Artifact?>(getArtifacts(target)).getPath()
        assertThat(singleOutput.isFile()).isTrue()

        return singleOutput
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nobuild_warning() {
        writeMyRuleBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:my_rule.bzl", "my_rule")

        my_rule(
            name = "foo",
            srcs = ["foo.in"],
        )
        
        """.trimIndent()
        )
        write("foo/foo.in")
        addOptions("--nobuild")

        val recordedOutput: RecordingOutErr = divertInfoLogToOutErr()
        val result: BuildResult = buildTarget("//foo:foo")

        assertThat(result.getSuccess()).isTrue()
        assertThat(recordedOutput.errAsLatin1())
            .containsMatch(
                "--experimental_merged_skyframe_analysis_execution is incompatible with --nobuild"
                        + " and will be ignored"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multiTargetBuild_success() {
        writeMyRuleBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:my_rule.bzl", "my_rule")

        my_rule(
            name = "bar",
            srcs = ["bar.in"],
        )

        my_rule(
            name = "foo",
            srcs = ["foo.in"],
        )
        
        """.trimIndent()
        )
        write("foo/foo.in")
        write("foo/bar.in")

        val result: BuildResult = buildTarget("//foo:foo", "//foo:bar")

        assertThat(result.getSuccess()).isTrue()
        assertSingleOutputBuilt("//foo:foo")
        assertSingleOutputBuilt("//foo:bar")

        Truth.assertThat(getLabelsOfAnalyzedTargets()).containsExactly("//foo:foo", "//foo:bar")
        Truth.assertThat(getLabelsOfBuiltTargets()).containsExactly("//foo:foo", "//foo:bar")

        Truth.assertThat(eventsSubscriber!!.getTopLevelEntityAnalysisConcludedEvents()).hasSize(2)
        assertSingleAnalysisPhaseCompleteEventWithLabels("//foo:foo", "//foo:bar")

        assertThat(directories.getOutputPath(TestConstants.WORKSPACE_NAME).getRelative("build-info.txt").isFile())
            .isTrue()
        assertThat(
            directories.getOutputPath(TestConstants.WORKSPACE_NAME).getRelative("build-changelist.txt").isFile()
        )
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multiTargetNullIncrementalBuild_success() {
        writeMyRuleBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:my_rule.bzl", "my_rule")

        my_rule(
            name = "bar",
            srcs = ["bar.in"],
        )

        my_rule(
            name = "foo",
            srcs = ["foo.in"],
        )
        
        """.trimIndent()
        )
        write("foo/foo.in")
        write("foo/bar.in")

        // First build, ignored.
        buildTarget("//foo:foo", "//foo:bar")
        val result: BuildResult = buildTarget("//foo:foo", "//foo:bar")

        assertThat(result.getSuccess()).isTrue()
        assertSingleOutputBuilt("//foo:foo")
        assertSingleOutputBuilt("//foo:bar")

        assertThat(directories.getOutputPath(TestConstants.WORKSPACE_NAME).getRelative("build-info.txt").isFile())
            .isTrue()
        assertThat(
            directories.getOutputPath(TestConstants.WORKSPACE_NAME).getRelative("build-changelist.txt").isFile()
        )
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sequentialBuilds_verifyNodesAreDone(@TestParameter mergedAnalysisExecution: Boolean) {
        addOptions("--experimental_merged_skyframe_analysis_execution=" + mergedAnalysisExecution)
        write("hello/x.txt", "x")
        write(
            "hello/BUILD",
            """
        genrule(
            name = "target",
            srcs = ["x.txt"],
            outs = ["out"],
            cmd = "cat ${'$'}< > ${'$'}@",
        )

        genrule(
            name = "target2",
            srcs = ["x.txt"],
            outs = ["out2"],
            cmd = "cat ${'$'}< > ${'$'}@",
        )
        
        """.trimIndent()
        )

        buildTarget("//hello:target")
        assertThat(getSkyframeExecutor().getEvaluator().getDoneValues())
            .comparingValuesUsing(IS_EQUIVALENT_SKY_VALUE)
            .containsExactlyEntriesIn(getSkyframeExecutor().getEvaluator().getValues())

        buildTarget("//hello:target2")

        if (mergedAnalysisExecution) {
            // BuildDriverKey of the previous build with be marked dirty from its child BUILD_ID dep.
            // However, only the new BuildDriverKey will be evaluated and marked done.
            val dirtyKey: SkyKey? =
                com.google.common.collect.Iterables.getOnlyElement<T?>(
                    com.google.common.collect.Sets.difference<E?>(
                        getSkyframeExecutor().getEvaluator().getValues().keySet(),
                        getSkyframeExecutor().getEvaluator().getDoneValues().keySet()
                    )
                )
            com.google.common.truth.Subject.contains(
                "BUILD_DRIVER:BuildDriverKey of ActionLookupKey:"
                        + " ConfiguredTargetKey{label=//hello:target"
            )
            assertThat(
                getSkyframeExecutor()
                    .getEvaluator()
                    .getInMemoryGraph()
                    .getIfPresent(dirtyKey)
                    .getLifecycleState()
            )
                .isEqualTo(LifecycleState.CHECK_DEPENDENCIES)
        } else {
            // This doesn't happen for non-Skymeld builds.
            assertThat(getSkyframeExecutor().getEvaluator().getDoneValues())
                .comparingValuesUsing(IS_EQUIVALENT_SKY_VALUE)
                .containsExactlyEntriesIn(getSkyframeExecutor().getEvaluator().getValues())
        }
    }


    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectAnalysisFailure_consistentWithNonSkymeld(
        @TestParameter keepGoing: Boolean, @TestParameter mergedAnalysisExecution: Boolean
    ) {
        addOptions("--keep_going=" + keepGoing)
        addOptions("--experimental_merged_skyframe_analysis_execution=" + mergedAnalysisExecution)
        writeMyRuleBzl()
        writeAnalysisFailureAspectBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:my_rule.bzl", "my_rule")

        my_rule(
            name = "foo",
            srcs = ["foo.in"],
        )
        
        """.trimIndent()
        )
        write("foo/foo.in")

        addOptions("--aspects=//foo:aspect.bzl%analysis_err_aspect", "--output_groups=files")
        if (keepGoing) {
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:foo") })
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:foo") })
        }
        events.assertContainsError("compilation of module 'foo/aspect.bzl' failed")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectExecutionFailure_consistentWithNonSkymeld(
        @TestParameter keepGoing: Boolean, @TestParameter mergedAnalysisExecution: Boolean
    ) {
        addOptions("--keep_going=" + keepGoing)
        addOptions("--experimental_merged_skyframe_analysis_execution=" + mergedAnalysisExecution)
        writeMyRuleBzl()
        writeExecutionFailureAspectBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:my_rule.bzl", "my_rule")

        my_rule(
            name = "foo",
            srcs = ["foo.in"],
        )
        
        """.trimIndent()
        )
        write("foo/foo.in")

        addOptions("--aspects=//foo:aspect.bzl%execution_err_aspect", "--output_groups=files")
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:foo") })
        events.assertContainsError(
            "Action foo/aspect_output failed: (Exit 1): bash failed: error executing Action command"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetExecutionFailure_consistentWithNonSkymeld(
        @TestParameter keepGoing: Boolean, @TestParameter mergedAnalysisExecution: Boolean
    ) {
        addOptions("--keep_going=" + keepGoing)
        addOptions("--experimental_merged_skyframe_analysis_execution=" + mergedAnalysisExecution)
        writeMyRuleBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:my_rule.bzl", "my_rule")

        my_rule(
            name = "execution_failure",
            srcs = ["missing"],
        )

        my_rule(
            name = "foo",
            srcs = ["foo.in"],
        )
        
        """.trimIndent()
        )
        write("foo/foo.in")

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:foo", "//foo:execution_failure") })
        if (keepGoing) {
            assertSingleOutputBuilt("//foo:foo")
        }
        events.assertContainsError(
            "Action foo/execution_failure.out failed: missing input file '//foo:missing'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetAnalysisFailure_consistentWithNonSkymeld(
        @TestParameter keepGoing: Boolean, @TestParameter mergedAnalysisExecution: Boolean
    ) {
        addOptions("--keep_going=" + keepGoing)
        addOptions("--experimental_merged_skyframe_analysis_execution=" + mergedAnalysisExecution)
        writeMyRuleBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:my_rule.bzl", "my_rule")

        my_rule(
            name = "analysis_failure",
            srcs = ["foo.in"],
            deps = [":missing"],
        )

        my_rule(
            name = "foo",
            srcs = ["foo.in"],
        )
        
        """.trimIndent()
        )
        write("foo/foo.in")

        if (keepGoing) {
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:foo", "//foo:analysis_failure") })
            assertSingleOutputBuilt("//foo:foo")
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:foo", "//foo:analysis_failure") })
        }
        events.assertContainsError("rule '//foo:missing' does not exist")
    }

    // Regression test for https://github.com/bazelbuild/bazel/issues/20443
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingWarningContainsDetails() {
        addOptions("--keep_going")
        write(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        constraint_setting(name = "incompatible_setting")

        constraint_value(
            name = "incompatible",
            constraint_setting = ":incompatible_setting",
            visibility = ["//visibility:public"],
        )

        cc_library(
            name = "foo",
            srcs = ["foo.cc"],
            target_compatible_with = ["//foo:incompatible"],
        )
        
        """.trimIndent()
        )
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:foo") })
        events.assertContainsWarning(
            "errors encountered while analyzing target '//foo:foo', it will not be built."
        )
        // The details.
        events.assertContainsWarning("Dependency chain:")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun analysisAndExecutionFailure_keepGoing_bothReported() {
        addOptions("--keep_going")
        writeMyRuleBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:my_rule.bzl", "my_rule")

        my_rule(
            name = "execution_failure",
            srcs = ["missing"],
        )

        my_rule(
            name = "analysis_failure",
            srcs = ["foo.in"],
            deps = [":missing"],
        )
        
        """.trimIndent()
        )
        write("foo/foo.in")

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:analysis_failure", "//foo:execution_failure") })
        events.assertContainsError(
            "Action foo/execution_failure.out failed: missing input file '//foo:missing'"
        )
        events.assertContainsError("rule '//foo:missing' does not exist")

        Truth.assertThat(getLabelsOfAnalyzedTargets()).contains("//foo:execution_failure")
        Truth.assertThat(getLabelsOfBuiltTargets()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun symlinkPlantedLocalAction_success() {
        addOptions("--spawn_strategy=standalone")
        write(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            srcs = ["foo.in"],
            outs = ["foo.out"],
            cmd = "cp ${'$'}< ${'$'}@",
        )
        
        """.trimIndent()
        )
        write("foo/foo.in")

        val result: BuildResult = buildTarget("//foo:foo")

        assertThat(result.getSuccess()).isTrue()
        assertSingleOutputBuilt("//foo:foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun symlinksPlanted() {
        val execroot: Path = directories.getExecRoot(directories.getWorkspace().getBaseName())
        writeMyRuleBzl()
        val fooDir: Path? =
            write(
                "foo/BUILD",
                """
                load("//foo:my_rule.bzl", "my_rule")

                my_rule(
                    name = "foo",
                    srcs = ["foo.in"],
                )
                
                """.trimIndent()
            )
                .getParentDirectory()
        write("foo/foo.in")
        val unusedDir: Path? = write("unused/dummy").getParentDirectory()

        // Before the build: no symlink.
        assertThat(execroot.getRelative("foo").exists()).isFalse()

        buildTarget("//foo:foo")

        // After the build: symlinks to the source directory, even unused packages.
        assertThat(execroot.getRelative("foo").resolveSymbolicLinks()).isEqualTo(fooDir)
        assertThat(execroot.getRelative("unused").resolveSymbolicLinks()).isEqualTo(unusedDir)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun symlinksPlantedExceptProductNamePrefixAndIgnoredPaths() {
        val productName: String? = getRuntime().productName
        val execroot: Path = directories.getExecRoot(directories.getWorkspace().getBaseName())
        writeMyRuleBzl()
        val fooDir: Path? =
            write(
                "foo/BUILD",
                """
                load("//foo:my_rule.bzl", "my_rule")

                my_rule(
                    name = "foo",
                    srcs = ["foo.in"],
                )
                
                """.trimIndent()
            )
                .getParentDirectory()
        write("foo/foo.in")
        val unusedDir: Path? = write("unused/dummy").getParentDirectory()
        write(".bazelignore", "ignored")
        write("ignored/dummy")
        write(productName + "-dir/dummy")

        // Before the build: no symlink.
        assertThat(execroot.getRelative("foo").exists()).isFalse()

        buildTarget("//foo:foo")

        // After the build: symlinks to the source directory, even unused packages, except for those
        // in the .bazelignore file and those with the bazel- prefix.
        assertThat(execroot.getRelative("foo").resolveSymbolicLinks()).isEqualTo(fooDir)
        assertThat(execroot.getRelative("unused").resolveSymbolicLinks()).isEqualTo(unusedDir)
        assertThat(execroot.getRelative("ignored").exists()).isFalse()
        assertThat(execroot.getRelative(productName + "-dir").exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun symlinksReplantedEachBuild() {
        val execroot: Path = directories.getExecRoot(directories.getWorkspace().getBaseName())
        writeMyRuleBzl()
        val fooDir: Path? =
            write(
                "foo/BUILD",
                """
                load("//foo:my_rule.bzl", "my_rule")

                my_rule(
                    name = "foo",
                    srcs = ["foo.in"],
                )
                
                """.trimIndent()
            )
                .getParentDirectory()
        write("foo/foo.in")
        val unusedDir: Path = write("unused/dummy").getParentDirectory()

        buildTarget("//foo:foo")

        // After the 1st build: symlinks to the source directory, even unused packages.
        assertThat(execroot.getRelative("foo").resolveSymbolicLinks()).isEqualTo(fooDir)
        assertThat(execroot.getRelative("unused").resolveSymbolicLinks()).isEqualTo(unusedDir)

        unusedDir.deleteTree()

        buildTarget("//foo:foo")

        // After the 2nd build: symlink to unusedDir is gone, since the package itself was deleted.
        assertThat(execroot.getRelative("foo").resolveSymbolicLinks()).isEqualTo(fooDir)
        assertThat(execroot.getRelative("unused").exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetAnalysisFailure_skymeld_correctAnalysisEvents(@TestParameter keepGoing: Boolean) {
        addOptions("--keep_going=" + keepGoing)
        writeMyRuleBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:my_rule.bzl", "my_rule")

        my_rule(
            name = "analysis_failure",
            srcs = ["foo.in"],
            deps = [":missing"],
        )

        my_rule(
            name = "foo",
            srcs = ["foo.in"],
        )
        
        """.trimIndent()
        )
        write("foo/foo.in")

        if (keepGoing) {
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:foo", "//foo:analysis_failure") })

            Truth.assertThat(eventsSubscriber!!.getTopLevelEntityAnalysisConcludedEvents()).hasSize(2)
            assertSingleAnalysisPhaseCompleteEventWithLabels("//foo:foo")
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:foo", "//foo:analysis_failure") })
            Truth.assertThat(eventsSubscriber!!.getAnalysisPhaseCompleteEvents()).isEmpty()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectAnalysisFailure_skymeld_correctAnalysisEvents(@TestParameter keepGoing: Boolean) {
        addOptions("--keep_going=" + keepGoing)
        writeMyRuleBzl()
        writeAnalysisFailureAspectBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:my_rule.bzl", "my_rule")

        my_rule(
            name = "foo",
            srcs = ["foo.in"],
        )
        
        """.trimIndent()
        )
        write("foo/foo.in")

        addOptions("--aspects=//foo:aspect.bzl%analysis_err_aspect", "--output_groups=files")
        if (keepGoing) {
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:foo") })
            Truth.assertThat(eventsSubscriber!!.getTopLevelEntityAnalysisConcludedEvents()).hasSize(2)
            assertSingleAnalysisPhaseCompleteEventWithLabels("//foo:foo")
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:foo") })
            Truth.assertThat(eventsSubscriber!!.getAnalysisPhaseCompleteEvents()).isEmpty()
        }
        events.assertContainsError("compilation of module 'foo/aspect.bzl' failed")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetSkipped_skymeld_correctAnalysisEvents(@TestParameter keepGoing: Boolean) {
        writeEnvironmentRules()
        addOptions("--keep_going=" + keepGoing)
        write(
            "foo/BUILD",
            """
        filegroup(
            name = "good_bar",
            srcs = ["bar.sh"],
            compatible_with = ["//buildenv:one"],
        )

        filegroup(
            name = "bad_bar",
            srcs = ["bar.sh"],
            compatible_with = ["//buildenv:two"],
        )
        
        """.trimIndent()
        )
        write("foo/bar.sh")
        addOptions("--target_environment=//buildenv:one")
        if (keepGoing) {
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:good_bar", "//foo:bad_bar") })

            Truth.assertThat(eventsSubscriber!!.getTopLevelEntityAnalysisConcludedEvents()).hasSize(2)
            Truth.assertThat(eventsSubscriber!!.getAnalysisPhaseCompleteEvents()).hasSize(1)
            val analysisPhaseCompleteEvent: AnalysisPhaseCompleteEvent? =
                com.google.common.collect.Iterables.getOnlyElement<AnalysisPhaseCompleteEvent?>(eventsSubscriber!!.getAnalysisPhaseCompleteEvents())
            assertThat(analysisPhaseCompleteEvent.getTimeInMs()).isGreaterThan(0)
            Truth.assertThat(getLabelsOfAnalyzedTargets(analysisPhaseCompleteEvent))
                .containsExactly("//foo:good_bar", "//foo:bad_bar")
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:good_bar", "//foo:bad_bar") })
            Truth.assertThat(eventsSubscriber!!.getAnalysisPhaseCompleteEvents()).isEmpty()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetWithNoConfiguration_success() {
        write("foo/BUILD", "exports_files(['bar.txt'])")
        write("foo/bar.txt", "This is just a test file to pretend to build.")
        val result: BuildResult = buildTarget("//foo:bar.txt")

        assertThat(result.getSuccess()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun explain_ignoreSkymeldWithWarning() {
        addOptions("--explain=/dev/null")
        write("foo/BUILD", "genrule(name = 'foo', outs = ['foo.out'], cmd = 'touch $@')")
        val recordedOutput: RecordingOutErr = divertInfoLogToOutErr()
        val buildResult: BuildResult = buildTarget("//foo")

        assertThat(buildResult.getSuccess()).isTrue()

        assertThat(recordedOutput.errAsLatin1())
            .containsMatch(
                "--experimental_merged_skyframe_analysis_execution is incompatible with --explain"
                        + " and will be ignored."
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multiplePackagePath_ignoreSkymeldWithWarning() {
        write("foo/BUILD", "genrule(name = 'foo', outs = ['foo.out'], cmd = 'touch $@')")
        write("otherroot/bar/BUILD", "genrule(name = 'bar', outs = ['bar.out'], cmd = 'touch $@')")
        addOptions("--package_path=%workspace%:otherroot")

        val recordedOutput: RecordingOutErr = divertInfoLogToOutErr()
        val buildResult: BuildResult = buildTarget("//foo", "//bar")

        assertThat(buildResult.getSuccess()).isTrue()

        assertThat(recordedOutput.errAsLatin1())
            .containsMatch(
                "--experimental_merged_skyframe_analysis_execution is incompatible with multiple"
                        + " --package_path.*and its value will be ignored."
            )
    }

    // Regression test for b/245919888.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputFileRemoved_regeneratedWithIncrementalBuild() {
        writeMyRuleBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:my_rule.bzl", "my_rule")

        my_rule(
            name = "foo",
            srcs = ["foo.in"],
        )
        
        """.trimIndent()
        )
        write("foo/foo.in")

        val result: BuildResult = buildTarget("//foo:foo")

        assertThat(result.getSuccess()).isTrue()
        val fooOut: Path = assertSingleOutputBuilt("//foo:foo")

        fooOut.delete()

        val incrementalBuild: BuildResult = buildTarget("//foo:foo")

        assertThat(incrementalBuild.getSuccess()).isTrue()
        assertSingleOutputBuilt("//foo:foo")
    }

    // Regression test for b/245922900.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executionFailure_discardAnalysisCache_doesNotCrash() {
        addOptions("--experimental_merged_skyframe_analysis_execution", "--discard_analysis_cache")
        writeExecutionFailureAspectBzl()
        write(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "foo",
            srcs = ["foo.cc"],
            deps = [":bar"],
        )

        cc_library(
            name = "bar",
            srcs = ["bar.cc"],
        )
        
        """.trimIndent()
        )
        write("foo/foo.cc")
        write("foo/bar.cc")
        addOptions("--aspects=//foo:aspect.bzl%execution_err_aspect", "--output_groups=files")

        // Verify that the build did not crash.
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:foo") })
        events.assertContainsError(
            "Action foo/aspect_output failed: (Exit 1): bash failed: error executing Action command"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetCycle_doesNotCrash() {
        write(
            "a/BUILD",
            """
        alias(
            name = "a",
            actual = ":b",
        )

        alias(
            name = "b",
            actual = ":c",
        )

        alias(
            name = "c",
            actual = ":a",
        )

        filegroup(
            name = "d",
            srcs = [":c"],
        )
        
        """.trimIndent()
        )
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//a:d") })
        events.assertContainsError("cycle in dependency graph")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun analysisOverlapPercentageSanityCheck_success() {
        writeMyRuleBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:my_rule.bzl", "my_rule")

        my_rule(
            name = "bar",
            srcs = ["bar.in"],
        )

        my_rule(
            name = "foo",
            srcs = ["foo.in"],
        )
        
        """.trimIndent()
        )
        write("foo/foo.in")
        write("foo/bar.in")

        addOptions("--experimental_skymeld_analysis_overlap_percentage=5")
        val result: BuildResult = buildTarget("//foo:foo", "//foo:bar")

        assertThat(result.getSuccess()).isTrue()
        assertSingleOutputBuilt("//foo:foo")
        assertSingleOutputBuilt("//foo:bar")

        Truth.assertThat(getLabelsOfAnalyzedTargets()).containsExactly("//foo:foo", "//foo:bar")
        Truth.assertThat(getLabelsOfBuiltTargets()).containsExactly("//foo:foo", "//foo:bar")

        Truth.assertThat(eventsSubscriber!!.getTopLevelEntityAnalysisConcludedEvents()).hasSize(2)
        assertSingleAnalysisPhaseCompleteEventWithLabels("//foo:foo", "//foo:bar")
    }

    // Regression test for b/277783687.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetAnalysisFailureNullBuild_correctErrorsPropagated(
        @TestParameter keepGoing: Boolean
    ) {
        addOptions("--keep_going=" + keepGoing)
        writeMyRuleBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:my_rule.bzl", "my_rule")

        my_rule(
            name = "analysis_failure",
            srcs = ["foo.in"],
            deps = [":missing"],
        )
        
        """.trimIndent()
        )
        write("foo/foo.in")

        if (keepGoing) {
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:analysis_failure") })
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:analysis_failure") })
        }
        events.assertContainsError(
            "in deps attribute of my_rule rule //foo:analysis_failure: rule '//foo:missing' does not"
                    + " exist"
        )

        // Null build
        if (keepGoing) {
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:analysis_failure") })
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:analysis_failure") })
        }
        events.assertContainsError(
            "in deps attribute of my_rule rule //foo:analysis_failure: rule '//foo:missing' does not"
                    + " exist"
        )
    }

    // Regression test for b/300391729.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executionFailure_keepGoing_doesNotSpamWarnings() {
        addOptions("--keep_going")
        writeExecutionFailureAspectBzl()
        write(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "foo",
            srcs = ["foo.cc"],
            deps = [":bar"],
        )

        cc_library(
            name = "bar",
            srcs = ["bar.cc"],
        )
        
        """.trimIndent()
        )
        write("foo/foo.cc")
        write("foo/bar.cc")
        addOptions("--aspects=//foo:aspect.bzl%execution_err_aspect", "--output_groups=files")

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo/...") })
        // No warnings.
        events.assertNoWarnings()
    }

    // Regression test for b/301289073.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun conflictCheck_doesNotTimeout() {
        addOptions("--keep_going")
        write(
            "foo/BUILD",
            """
        BASE_SIZE = 500

        TOP_SIZE = 100

        genrule(
            name = "base_0",
            outs = ["base_0.txt"],
            cmd = "touch ${'$'}@",
        )

        [genrule(
            name = "base_%s" % x,
            srcs = ["base_%s.txt" % (x - 1)],
            outs = ["base_%s.txt" % x],
            cmd = "touch ${'$'}@",
        ) for x in range(1, BASE_SIZE)]

        [genrule(
            name = "level_%s" % y,
            srcs = ["base_%s.txt" % (
                x,
            ) for x in range(0, BASE_SIZE)],
            outs = ["level_%s.txt" % y],
            cmd = "touch ${'$'}@",
        ) for y in range(0, TOP_SIZE)]

        genrule(
            name = "conflict",
            outs = ["conflict"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
        write(
            "foo/conflict/BUILD",
            """
        genrule(
            name = "conflict",
            outs = ["conflict"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )

        // Building a set of targets with recursive dependencies that would trivially finish in time
        // with memoization and time out without.
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo/...") })
        events.assertContainsError("is a prefix of the other")
    }

    private fun assertSingleAnalysisPhaseCompleteEventWithLabels(vararg labels: String?) {
        Truth.assertThat(eventsSubscriber!!.getAnalysisPhaseCompleteEvents()).hasSize(1)
        val analysisPhaseCompleteEvent: AnalysisPhaseCompleteEvent? =
            com.google.common.collect.Iterables.getOnlyElement<AnalysisPhaseCompleteEvent?>(eventsSubscriber!!.getAnalysisPhaseCompleteEvents())
        assertThat(analysisPhaseCompleteEvent.getTimeInMs()).isGreaterThan(0)
        Truth.assertThat(getLabelsOfAnalyzedTargets(analysisPhaseCompleteEvent))
            .containsExactlyElementsIn(labels)
    }

    private fun divertInfoLogToOutErr(): RecordingOutErr {
        // Divert output into recorder:
        val recordedOutput: RecordingOutErr = RecordingOutErr()
        this.outErr = recordedOutput
        divertLogging(
            java.util.logging.Level.INFO,
            outErr,
            com.google.common.collect.ImmutableList.of<java.util.logging.Logger?>(
                java.util.logging.Logger.getLogger(SkymeldModule::class.java.getName())
            )
        )
        return recordedOutput
    }

    private class EventsSubscriber {
        private val topLevelEntityAnalysisConcludedEvents: MutableList<TopLevelEntityAnalysisConcludedEvent?> =
            Collections.synchronizedList<TopLevelEntityAnalysisConcludedEvent?>(java.util.ArrayList<TopLevelEntityAnalysisConcludedEvent?>())

        private val analysisPhaseCompleteEvents: MutableList<AnalysisPhaseCompleteEvent?> =
            Collections.synchronizedList<AnalysisPhaseCompleteEvent?>(java.util.ArrayList<AnalysisPhaseCompleteEvent?>())

        @com.google.common.eventbus.Subscribe
        fun recordTopLevelEntityAnalysisConcludedEvent(event: TopLevelEntityAnalysisConcludedEvent?) {
            topLevelEntityAnalysisConcludedEvents.add(event)
        }

        @com.google.common.eventbus.Subscribe
        fun recordAnalysisPhaseCompleteEvent(event: AnalysisPhaseCompleteEvent?) {
            analysisPhaseCompleteEvents.add(event)
        }

        fun getTopLevelEntityAnalysisConcludedEvents(): MutableList<TopLevelEntityAnalysisConcludedEvent?> {
            return topLevelEntityAnalysisConcludedEvents
        }

        fun getAnalysisPhaseCompleteEvents(): MutableList<AnalysisPhaseCompleteEvent?> {
            return analysisPhaseCompleteEvents
        }
    }

    companion object {
        /**
         * [Correspondence] for use in assertions about maps containing [SkyValue] values.
         * Because [ErrorTransienceValue] instances don't compare equal to themselves, we have to
         * use this hack so map assertions will work reliably.
         */
        private val IS_EQUIVALENT_SKY_VALUE: Correspondence<SkyValue?, SkyValue?> =
            Correspondence.from<SkyValue?, SkyValue?>(
                BinaryPredicate { actual: SkyValue?, expected: SkyValue? -> actual === expected || actual == expected },
                "is equivalent SkyValue to"
            )

        private fun getLabelsOfAnalyzedTargets(event: AnalysisPhaseCompleteEvent): com.google.common.collect.ImmutableSet<String?> {
            return event.getTopLevelTargets().stream()
                .map({ x -> x.getOriginalLabel().getCanonicalForm() })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
        }
    }
}
