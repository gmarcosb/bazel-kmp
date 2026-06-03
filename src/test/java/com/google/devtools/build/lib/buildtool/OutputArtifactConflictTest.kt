// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionConflictException

/** Tests for action conflicts.  */
@RunWith(TestParameterInjector::class)
class OutputArtifactConflictTest : BuildIntegrationTestCase() {
    @TestParameter
    var skymeld: Boolean = false

    @TestParameter
    var minimizeMemory: Boolean = false

    internal class AnalysisFailureEventListener : BlazeModule() {
        private val eventIds: MutableList<TargetCompletedId?> = java.util.ArrayList<TargetCompletedId?>()
        private val analysisFailures: MutableList<String?> = java.util.ArrayList<String?>()

        public override fun beforeCommand(env: CommandEnvironment) {
            env.getEventBus().register(this)
        }

        @com.google.common.eventbus.Subscribe
        fun onAnalysisFailure(event: AnalysisFailureEvent) {
            eventIds.add(event.getEventId().getTargetCompleted())
            analysisFailures.add(event.getFailedTarget().getLabel().toString())
        }
    }

    private val eventListener = AnalysisFailureEventListener()

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.getRuntimeBuilder().addBlazeModule(eventListener)

    @Before
    fun setup() {
        addOptions("--experimental_merged_skyframe_analysis_execution=" + skymeld)
        if (minimizeMemory) {
            addOptions(
                "--notrack_incremental_state",
                "--discard_analysis_cache",
                "--nokeep_state_after_build",
                "--heuristically_drop_nodes",
                "--nouse_action_cache"
            )
        }
    }

    @Throws(IOException::class)
    private fun writeConflictBzl() {
        write(
            "foo/conflict.bzl",
            """
        def _conflict_impl(ctx):
            inputs = depset(
                ctx.files.srcs,
                transitive = [dep[DefaultInfo].files for dep in ctx.attr.deps],
            )
            conflict_output = ctx.actions.declare_file("conflict_output")
            other = ctx.actions.declare_file("other" + ctx.attr.name)
            ctx.actions.run_shell(
                inputs = inputs,
                outputs = [conflict_output, other],
                command = "touch %s %s" % (conflict_output.path, other.path),
            )
            return [DefaultInfo(files = depset([conflict_output, other]))]

        my_rule = rule(
            implementation = _conflict_impl,
            attrs = {
                "srcs": attr.label_list(allow_files = True),
                "deps": attr.label_list(providers = [DefaultInfo]),
            },
        )
        
        """.trimIndent()
        )
    }

    /**
     * Builds the provided targets and asserts expected exceptions.
     * 
     * @return the exit code extracted from the failure detail.
     */
    private fun assertThrowsExceptionWhenBuildingTargets(keepGoing: Boolean, vararg targets: String?): Code {
        val failureDetail: FailureDetail? =
            if (keepGoing)
                org.junit.Assert.assertThrows<T?>(
                    BuildFailedException::class.java,
                    org.junit.function.ThrowingRunnable { buildTarget(*targets) })
                    .getDetailedExitCode()
                    .getFailureDetail()
            else
                org.junit.Assert.assertThrows<T?>(
                    ViewCreationFailedException::class.java,
                    org.junit.function.ThrowingRunnable { buildTarget(*targets) })
                    .getFailureDetail()
        return com.google.common.base.Preconditions.checkNotNull<Any?>(failureDetail).getAnalysis().getCode()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArtifactPrefix(
        @TestParameter keepGoing: Boolean, @TestParameter modifyBuildFile: Boolean
    ) {
        write("x/y/BUILD", "genrule(name = 'y', outs = ['whatever'], cmd = 'touch $@')")
        if (modifyBuildFile) {
            write("x/BUILD", "genrule(name = 'y', outs = ['not_y'], cmd = 'touch $@')")
            buildTarget("//x:y", "//x/y:y")
            write("x/BUILD", "genrule(name = 'y', outs = ['y'], cmd = 'touch $@')")
        } else {
            write("x/BUILD", "genrule(name = 'y', outs = ['y'], cmd = 'touch $@')")
            buildTarget("//x/y:y")
        }

        MoreAsserts.assertNoEvents(events.errors())
        Truth.assertThat(eventListener.analysisFailures).isEmpty()

        addOptions("--keep_going=" + keepGoing)
        val errorCode: Code = assertThrowsExceptionWhenBuildingTargets(keepGoing, "//x/y:y", "//x:y")
        assertThat(errorCode)
            .isEqualTo(if (keepGoing) Code.NOT_ALL_TARGETS_ANALYZED else Code.ARTIFACT_PREFIX_CONFLICT)

        if (keepGoing) {
            Truth.assertThat(eventListener.analysisFailures).containsExactly("//x:y", "//x/y:y")
        } else {
            Truth.assertThat(eventListener.analysisFailures).containsAnyOf("//x:y", "//x/y:y")
        }

        events.assertContainsError("One of the output paths '" + TestConstants.PRODUCT_NAME + "-out/")
        events.assertContainsError("/bin/x/y/whatever' (belonging to //x/y:y)")
        events.assertContainsError("/bin/x/y' (belonging to //x:y)")
        events.assertContainsError("is a prefix of the other")
        Truth.assertThat(events.errors()).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectArtifactSharesPrefixWithTargetArtifact(
        @TestParameter keepGoing: Boolean, @TestParameter modifyBuildFile: Boolean
    ) {
        if (modifyBuildFile) {
            write("x/BUILD", "genrule(name = 'y', outs = ['y.out'], cmd = 'touch $@')")
        } else {
            write("x/BUILD", "genrule(name = 'y', outs = ['y.bad'], cmd = 'touch $@')")
        }
        write("x/y/BUILD", "genrule(name = 'y', outs = ['whatever'], cmd = 'touch $@')")
        write(
            "x/aspect.bzl",
            """
        def _aspect_impl(target, ctx):
            if not getattr(ctx.rule.attr, "outs", None):
                return  [OutputGroupInfo()]
            conflict_outputs = list()
            for out in ctx.rule.attr.outs:
                if out.name[1:] == ".bad":
                    aspect_out = ctx.actions.declare_file(out.name[:1])
                    conflict_outputs.append(aspect_out)
                    cmd = "echo %s > %s" % (out.name, aspect_out.path)
                    ctx.actions.run_shell(
                        outputs = [aspect_out],
                        command = cmd,
                    )
            return [OutputGroupInfo(
                files = depset(conflict_outputs),
            )]

        my_aspect = aspect(implementation = _aspect_impl)
        
        """.trimIndent()
        )

        if (modifyBuildFile) {
            buildTarget("//x/y", "//x:y")
            write("x/BUILD", "genrule(name = 'y', outs = ['y.bad'], cmd = 'touch $@')")
        } else {
            buildTarget("//x/y")
        }
        MoreAsserts.assertNoEvents(events.errors())
        Truth.assertThat(eventListener.analysisFailures).isEmpty()

        addOptions("--aspects=//x:aspect.bzl%my_aspect", "--output_groups=files")
        addOptions("--keep_going=" + keepGoing)
        val errorCode: Code = assertThrowsExceptionWhenBuildingTargets(keepGoing, "//x/y", "//x:y")
        assertThat(errorCode)
            .isEqualTo(if (keepGoing) Code.NOT_ALL_TARGETS_ANALYZED else Code.ARTIFACT_PREFIX_CONFLICT)
        events.assertContainsError("One of the output paths '" + TestConstants.PRODUCT_NAME + "-out/")
        events.assertContainsError("/bin/x/y/whatever' (belonging to //x/y:y)")
        events.assertContainsError("/bin/x/y' (belonging to //x:y)")
        events.assertContainsError("is a prefix of the other")
        Truth.assertThat(events.errors()).hasSize(1)
        Truth.assertThat(eventListener.analysisFailures).containsAnyOf("//x:y", "//x/y:y")

        // We can't be sure if aspect(//x:y) or //x/y:y would trigger the conflict.
        skipTheRestIfSkymeldAndMinimizeMemory()
        // As we have --output_groups=file, the CTs won't actually be built. Only the
        // AnalysisFailureEvent from Aspect(//x:y) is expected even though there are 2 conflicting
        // actions.
        assertThat(eventListener.eventIds.get(0).getAspect()).isEqualTo("//x:aspect.bzl%my_aspect")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectArtifactPrefix(
        @TestParameter keepGoing: Boolean, @TestParameter modifyBuildFile: Boolean
    ) {
        if (modifyBuildFile) {
            write(
                "x/BUILD",
                """
          genrule(
              name = "y",
              outs = ["y.out"],
              cmd = "touch ${'$'}@",
          )

          genrule(
              name = "ydir",
              outs = ["y.dir"],
              cmd = "touch ${'$'}@",
          )
          
          """.trimIndent()
            )
        } else {
            write(
                "x/BUILD",
                """
          genrule(
              name = "y",
              outs = ["y.bad"],
              cmd = "touch ${'$'}@",
          )

          genrule(
              name = "ydir",
              outs = ["y.dir"],
              cmd = "touch ${'$'}@",
          )
          
          """.trimIndent()
            )
        }
        write(
            "x/aspect.bzl",
            """
        def _aspect_impl(target, ctx):
            if not getattr(ctx.rule.attr, "outs", None):
                return [OutputGroupInfo()]
            conflict_outputs = list()
            for out in ctx.rule.attr.outs:
                if out.name[1:] == ".bad":
                    aspect_out = ctx.actions.declare_file(out.name[:1])
                    conflict_outputs.append(aspect_out)
                    cmd = "echo %s > %s" % (out.name, aspect_out.path)
                    ctx.actions.run_shell(
                        outputs = [aspect_out],
                        command = cmd,
                    )
                elif out.name[1:] == ".dir":
                    aspect_out = ctx.actions.declare_file(out.name[:1] + "/" + out.name)
                    conflict_outputs.append(aspect_out)
                    out_dir = aspect_out.path[:len(aspect_out.path) - len(out.name) + 1]
                    cmd = "mkdir %s && echo %s > %s" % (out_dir, out.name, aspect_out.path)
                    ctx.actions.run_shell(
                        outputs = [aspect_out],
                        command = cmd,
                    )
            return [OutputGroupInfo(
                files = depset(conflict_outputs),
            )]

        my_aspect = aspect(implementation = _aspect_impl)
        
        """.trimIndent()
        )

        if (modifyBuildFile) {
            buildTarget("//x:y", "//x:ydir")
            write(
                "x/BUILD",
                """
          genrule(
              name = "y",
              outs = ["y.bad"],
              cmd = "touch ${'$'}@",
          )

          genrule(
              name = "ydir",
              outs = ["y.dir"],
              cmd = "touch ${'$'}@",
          )
          
          """.trimIndent()
            )
        } else {
            buildTarget("//x:y")
        }
        MoreAsserts.assertNoEvents(events.errors())
        Truth.assertThat(eventListener.analysisFailures).isEmpty()

        addOptions("--aspects=//x:aspect.bzl%my_aspect", "--output_groups=files")
        addOptions("--keep_going=" + keepGoing)
        val errorCode: Code = assertThrowsExceptionWhenBuildingTargets(keepGoing, "//x:ydir", "//x:y")
        assertThat(errorCode)
            .isEqualTo(if (keepGoing) Code.NOT_ALL_TARGETS_ANALYZED else Code.ARTIFACT_PREFIX_CONFLICT)
        events.assertContainsError("One of the output paths '" + TestConstants.PRODUCT_NAME + "-out/")
        events.assertContainsError("bin/x/y' (belonging to //x:y)")
        events.assertContainsError("bin/x/y/y.dir' (belonging to //x:ydir)")
        events.assertContainsError("is a prefix of the other")
        Truth.assertThat(events.errors()).hasSize(1)
        assertThat(eventListener.eventIds.get(0).getAspect()).isEqualTo("//x:aspect.bzl%my_aspect")
        if (keepGoing) {
            Truth.assertThat(eventListener.analysisFailures).containsExactly("//x:y", "//x:ydir")
        } else {
            Truth.assertThat(eventListener.analysisFailures).containsAnyOf("//x:y", "//x:ydir")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidatedConflict() {
        writeConflictBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:conflict.bzl", "my_rule")

        my_rule(name = "first")

        my_rule(name = "second")
        
        """.trimIndent()
        )

        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:first", "//foo:second") })
        Truth.assertThat(eventListener.analysisFailures).containsAnyOf("//foo:first", "//foo:second")

        write(
            "foo/BUILD",
            """
        load("//foo:conflict.bzl", "my_rule")

        my_rule(name = "first")
        
        """.trimIndent()
        )
        buildTarget("//foo:first")

        events.assertNoWarningsOrErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNewTargetConflict(@TestParameter keepGoing: Boolean) {
        addOptions("--keep_going=" + keepGoing)
        writeConflictBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:conflict.bzl", "my_rule")

        my_rule(name = "first")

        my_rule(name = "second")
        
        """.trimIndent()
        )
        buildTarget("//foo:first")
        events.assertNoWarningsOrErrors()

        val errorCode: Code =
            assertThrowsExceptionWhenBuildingTargets(keepGoing, "//foo:first", "//foo:second")
        assertThat(errorCode)
            .isEqualTo(if (keepGoing) Code.NOT_ALL_TARGETS_ANALYZED else Code.ACTION_CONFLICT)
        events.assertContainsError(
            "file 'foo/conflict_output' is generated by these conflicting actions:"
        )
        Truth.assertThat(eventListener.analysisFailures).hasSize(1)
        Truth.assertThat(eventListener.analysisFailures).containsAnyOf("//foo:first", "//foo:second")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTwoOverlappingBuildsHasNoConflict(@TestParameter keepGoing: Boolean) {
        addOptions("--keep_going=" + keepGoing)
        writeConflictBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:conflict.bzl", "my_rule")

        my_rule(name = "first")

        my_rule(name = "second")
        
        """.trimIndent()
        )

        // Verify that together they fail, even though no new targets have been analyzed
        val errorCode: Code =
            assertThrowsExceptionWhenBuildingTargets(keepGoing, "//foo:first", "//foo:second")
        assertThat(errorCode)
            .isEqualTo(if (keepGoing) Code.NOT_ALL_TARGETS_ANALYZED else Code.ACTION_CONFLICT)

        // Verify that they still don't fail individually, so no state remains
        buildTarget("//foo:first")
        events.assertNoWarningsOrErrors()
        buildTarget("//foo:second")
        events.assertNoWarningsOrErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFailingTargetsDoNotCauseActionConflicts() {
        addOptions("--keep_going")
        write(
            "x/bad_rule.bzl",
            """
        def _impl(ctx):
            return list().this_method_does_not_exist()

        bad_rule = rule(_impl, attrs = {"deps": attr.label_list()})
        
        """.trimIndent()
        )
        write(
            "x/BUILD",
            """
        load("//x:bad_rule.bzl", "bad_rule")

        cc_binary(
            name = "y",
            srcs = ["y.cc"],
            malloc = "//base:system_malloc",
        )

        bad_rule(
            name = "bad",
            deps = [":y"],
        )
        
        """.trimIndent()
        )
        write("x/y/y.cc", "")
        write("x/y/BUILD", "cc_library(name = 'y', srcs=['y.cc'])")
        write("x/y.cc", "int main() { return 0; }")

        try {
            buildTarget("//x:y", "//x/y")
            org.junit.Assert.fail()
        } catch (e: ViewCreationFailedException) {
            org.junit.Assert.fail("Unexpected artifact prefix conflict: " + e)
        } catch (e: BuildFailedException) {
            // Expected.
        }
    }

    // Regression test for b/184944522.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConflictErrorAndAnalysisError() {
        addOptions("--keep_going")
        writeConflictBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:conflict.bzl", "my_rule")

        my_rule(name = "first")

        my_rule(name = "second")
        
        """.trimIndent()
        )
        write(
            "x/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'x', deps = ['//y:y'])"
        )
        write(
            "y/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'y', visibility = ['//visibility:private'])"
        )

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//x:x", "//foo:first", "//foo:second") })
        events.assertContainsError(
            "file 'foo/conflict_output' is generated by these conflicting actions:"
        )
        // When targets have conflicting artifacts, one of them "wins" and is successfully built. All
        // of the other targets with conflicting artifacts fail.
        Truth.assertThat(eventListener.analysisFailures).contains("//x:x")
        Truth.assertThat(eventListener.analysisFailures).hasSize(2)
        Truth.assertThat(eventListener.analysisFailures).containsAnyOf("//foo:first", "//foo:second")
    }

    // Verify that an aspect whose analysis is unfinished doesn't fail the conflict reporting process.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConflictErrorAndUnfinishedAspectAnalysis_mergedAnalysisExecution(
        @TestParameter keepGoing: Boolean
    ) {
        addOptions("--keep_going=" + keepGoing)
        write(
            "x/aspect.bzl",
            """
        def _aspect_impl(target, ctx):
            if not getattr(ctx.rule.attr, "outs", None):
                return [OutputGroupInfo()]
            conflict_outputs = list()
            for out in ctx.rule.attr.outs:
                if out.name[1:] == ".bad":
                    aspect_out = ctx.actions.declare_file(out.name[:1])
                    conflict_outputs.append(aspect_out)
                    cmd = "echo %s > %s" % (out.name, aspect_out.path)
                    ctx.actions.run_shell(
                        outputs = [aspect_out],
                        command = cmd,
                    )
            return [OutputGroupInfo(
                files = depset(conflict_outputs),
            )]

        my_aspect = aspect(implementation = _aspect_impl)
        
        """.trimIndent()
        )

        write(
            "x/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        genrule(
            name = "y",
            outs = ["y.bad"],
            cmd = "touch ${'$'}@",
        )

        foo_library(
            name = "fail_analysis",
            deps = ["//private:y"],
        )
        
        """.trimIndent()
        )
        write("x/y/BUILD", "genrule(name = 'y', outs = ['whatever'], cmd = 'touch $@')")
        write(
            "private/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'y', visibility = ['//visibility:private'])"
        )
        addOptions("--aspects=//x:aspect.bzl%my_aspect", "--output_groups=files")

        val errorCode: Code =
            assertThrowsExceptionWhenBuildingTargets(
                keepGoing, "//x/y:y", "//x:y", "//x:fail_analysis"
            )
        if (keepGoing) {
            assertThat(errorCode).isEqualTo(Code.NOT_ALL_TARGETS_ANALYZED)
            events.assertContainsError(
                "One of the output paths '" + TestConstants.PRODUCT_NAME + "-out/"
            )
            events.assertContainsError("/bin/x/y/whatever' (belonging to //x/y:y)")
            events.assertContainsError("/bin/x/y' (belonging to //x:y)")
            events.assertContainsError("is a prefix of the other")
            events.assertContainsError("Analysis of target '//x:fail_analysis' (config: ")
            events.assertContainsError(") failed")

            Truth.assertThat(eventListener.analysisFailures).containsAtLeast("//x:y", "//x:fail_analysis")
        } else if (minimizeMemory) {
            assertThat(errorCode)
                .isAnyOf(Code.ARTIFACT_PREFIX_CONFLICT, Code.CONFIGURED_VALUE_CREATION_FAILED)
            // When in minimize-memory mode, the action conflicts are counted as failed analysis, since
            // conflict checking happens during the analysis of each target.
            Truth.assertThat(eventListener.analysisFailures)
                .containsAnyOf("//x:y", "//x/y:y", "//x:fail_analysis")
        } else {
            assertThat(errorCode)
                .isAnyOf(Code.ARTIFACT_PREFIX_CONFLICT, Code.CONFIGURED_VALUE_CREATION_FAILED)
        }
    }

    // This test is documenting current behavior more than enforcing a contract: it might be ok for
    // Bazel to suppress the error message about an action conflict, since the relevant actions are
    // not run in this build. However, that might cause problems for users who aren't immediately
    // alerted when they introduce an action conflict. We already skip exhaustive checks for action
    // conflicts in the name of performance and that has prompted complaints, so suppressing actual
    // conflicts seems like a bad idea.
    //
    // While this test is written with aspects, any actions that generate conflicting outputs but
    // aren't run would exhibit this behavior.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unusedActionsStillConflict() {
        // TODO(b/245923465) Limitation with Skymeld.
        TruthJUnit.assume().that(skymeld).isFalse()
        write(
            "foo/aspect.bzl",
            "def _aspect1_impl(target, ctx):",
            "  outfile = ctx.actions.declare_file('aspect.out')",
            "  ctx.actions.run_shell(",
            "    outputs = [outfile],",
            "    progress_message = 'Action for aspect 1',",
            "    command = 'echo \"1\" > ' + outfile.path,",
            "  )",
            "  return [OutputGroupInfo(files1 = [outfile])]",
            "",
            "def _aspect2_impl(target, ctx):",
            "  outfile = ctx.actions.declare_file('aspect.out')",
            "  ctx.actions.run_shell(",
            "    outputs = [outfile],",
            "    progress_message = 'Action for aspect 2',",
            "    command = 'echo \"2\" > ' + outfile.path,",
            "  )",
            "  return [OutputGroupInfo(files2 = [outfile])]",
            "",
            "def _rule_impl(ctx):",
            "  outfile = ctx.actions.declare_file('file.out')",
            "  ctx.actions.run_shell(",
            "    outputs = [outfile],",
            "    progress_message = 'Action for target',",
            "    command = 'touch ' + outfile.path,",
            "  )",
            "  return [DefaultInfo(files = depset([outfile]))]",
            "aspect1 = aspect(implementation = _aspect1_impl)",
            "aspect2 = aspect(implementation = _aspect2_impl)",
            "",
            "bad_rule = rule(implementation = _rule_impl, attrs = {'deps' : attr.label_list(aspects ="
                    + " [aspect1, aspect2])})"
        )
        write(
            "foo/BUILD",
            """
        load("//foo:aspect.bzl", "bad_rule")
        load('//test_defs:foo_library.bzl', 'foo_library')

        foo_library(
            name = "dep",
            srcs = ["dep.sh"],
        )

        bad_rule(
            name = "foo",
            deps = [":dep"],
        )
        
        """.trimIndent()
        )
        addOptions("--keep_going")
        // If Bazel decides to permit this scenario, the build should succeed instead of throwing here.
        val buildFailedException: BuildFailedException =
            org.junit.Assert.assertThrows<T>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:foo") })
        assertThat(buildFailedException)
            .hasMessageThat()
            .contains("command succeeded, but not all targets were analyzed")
        // We successfully built the output file despite the supposed failure.
        val artifacts: Iterable<Artifact?>? = getArtifacts("//foo:foo")
        Truth.assertThat(artifacts).hasSize(1)
        assertThat(com.google.common.collect.Iterables.getOnlyElement<Artifact?>(artifacts).getPath().exists()).isTrue()
        assertThat(
            buildFailedException.getDetailedExitCode().getFailureDetail().getAnalysis().getCode()
        )
            .isEqualTo(FailureDetails.Analysis.Code.NOT_ALL_TARGETS_ANALYZED)
        events.assertContainsError("file 'foo/aspect.out' is generated by these conflicting actions:")
        events.assertContainsError(
            java.util.regex.Pattern.compile(
                "Aspects: \\[//foo:aspect.bzl%aspect[12]], \\[//foo:aspect.bzl%aspect[12]]"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleConflictErrors() {
        writeConflictBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:conflict.bzl", "my_rule")

        my_rule(name = "first")

        my_rule(name = "second")
        
        """.trimIndent()
        )
        write("x/BUILD", "genrule(name = 'y', outs = ['y'], cmd = 'touch $@')")
        write("x/y/BUILD", "genrule(name = 'y', outs = ['whatever'], cmd = 'touch $@')")

        addOptions("--keep_going")

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//x/y", "//x:y", "//foo:first", "//foo:second") })
        events.assertContainsError(
            "file 'foo/conflict_output' is generated by these conflicting actions:"
        )
        events.assertContainsError("One of the output paths '" + TestConstants.PRODUCT_NAME + "-out/")
        events.assertContainsError("bin/x/y' (belonging to //x:y)")
        events.assertContainsError("is a prefix of the other")
        // When targets have conflicting artifacts, one of them "wins" and is successfully built. All
        // of the other targets with conflicting artifacts fail.
        Truth.assertThat(eventListener.analysisFailures).containsAtLeast("//x:y", "//x/y:y")
        Truth.assertThat(eventListener.analysisFailures).hasSize(3)
        Truth.assertThat(eventListener.analysisFailures).containsAnyOf("//foo:first", "//foo:second")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun repeatedConflictBuild() {
        writeConflictBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:conflict.bzl", "my_rule")

        my_rule(name = "first")

        my_rule(name = "second")
        
        """.trimIndent()
        )
        var e: ViewCreationFailedException? =
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:first", "//foo:second") })
        assertThat(e).hasCauseThat().isInstanceOf(ActionConflictException::class.java)
        Truth.assertThat(eventListener.analysisFailures).containsAnyOf("//foo:first", "//foo:second")
        eventListener.analysisFailures.clear()

        e =
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:first", "//foo:second") })
        assertThat(e).hasCauseThat().isInstanceOf(ActionConflictException::class.java)
        Truth.assertThat(eventListener.analysisFailures).containsAnyOf("//foo:first", "//foo:second")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConflictAfterNullBuild(@TestParameter keepGoing: Boolean) {
        addOptions("--aspects=//x:aspect.bzl%my_aspect", "--output_groups=files")
        addOptions("--keep_going=" + keepGoing)
        write("x/BUILD", "genrule(name = 'y', outs = ['y.out'], cmd = 'touch $@')")
        write("x/y/BUILD", "genrule(name = 'y', outs = ['whatever'], cmd = 'touch $@')")
        write(
            "x/aspect.bzl",
            """
        def _aspect_impl(target, ctx):
            if not getattr(ctx.rule.attr, "outs", None):
                return [OutputGroupInfo()]
            conflict_outputs = list()
            for out in ctx.rule.attr.outs:
                if out.name[1:] == ".bad":
                    aspect_out = ctx.actions.declare_file(out.name[:1])
                    conflict_outputs.append(aspect_out)
                    cmd = "echo %s > %s" % (out.name, aspect_out.path)
                    ctx.actions.run_shell(
                        outputs = [aspect_out],
                        command = cmd,
                    )
            return [OutputGroupInfo(
                files = depset(conflict_outputs),
            )]

        my_aspect = aspect(implementation = _aspect_impl)
        
        """.trimIndent()
        )
        // First build: no conflict expected.
        buildTarget("//x/y", "//x:y")
        // Null build
        buildTarget("//x/y", "//x:y")
        MoreAsserts.assertNoEvents(events.errors())
        Truth.assertThat(eventListener.analysisFailures).isEmpty()

        // Modify BUILD file to introduce a conflict.
        write("x/BUILD", "genrule(name = 'y', outs = ['y.bad'], cmd = 'touch $@')")

        val errorCode: Code = assertThrowsExceptionWhenBuildingTargets(keepGoing, "//x/y", "//x:y")
        assertThat(errorCode)
            .isEqualTo(if (keepGoing) Code.NOT_ALL_TARGETS_ANALYZED else Code.ARTIFACT_PREFIX_CONFLICT)
        events.assertContainsError("One of the output paths '" + TestConstants.PRODUCT_NAME + "-out/")
        events.assertContainsError("/bin/x/y/whatever' (belonging to //x/y:y)")
        events.assertContainsError("/bin/x/y' (belonging to //x:y)")
        events.assertContainsError("is a prefix of the other")
        Truth.assertThat(events.errors()).hasSize(1)
        Truth.assertThat(eventListener.analysisFailures).containsAnyOf("//x:y", "//x/y:y")
        // We don't know if the conflict is triggered by the aspect(//x:y) or //x/y:y
        skipTheRestIfSkymeldAndMinimizeMemory()
        assertThat(eventListener.eventIds.get(0).getAspect()).isEqualTo("//x:aspect.bzl%my_aspect")
    }

    // There exists a discrepancy between skymeld and noskymeld modes in case of --keep_going.
    // noskymeld: bazel would stop at the end of the analysis phase and build nothing.
    // skymeld: we either finish building one of the 2 conflicting artifacts, or none at all.
    //
    // The overall build would still fail in both cases.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTwoConflictingTargets_keepGoing_behaviorDifferences() {
        addOptions("--keep_going")
        write("x/BUILD", "genrule(name = 'y', outs = ['y'], cmd = 'touch $@')")
        write("x/y/BUILD", "genrule(name = 'y', outs = ['whatever'], cmd = 'touch $@')")

        val errorCode: Code =
            assertThrowsExceptionWhenBuildingTargets( /*keepGoing=*/true, "//x:y", "//x/y:y")

        assertThat(errorCode).isEqualTo(Code.NOT_ALL_TARGETS_ANALYZED)

        if (minimizeMemory) {
            // The states might have been dropped, so we can't check further here.
            return
        }

        val outputXY: Path =
            com.google.common.collect.Iterables.getOnlyElement<Artifact?>(getArtifacts("//x:y")).getPath()
        val outputXYY: Path =
            com.google.common.collect.Iterables.getOnlyElement<Artifact?>(getArtifacts("//x/y:y")).getPath()

        if (skymeld) {
            // Verify that these 2 conflicting artifacts can't both exist.
            Truth.assertThat(outputXYY.isFile() && outputXY.isFile()).isFalse()
        } else {
            // Verify that none of the output artifacts were built.
            assertThat(outputXY.exists()).isFalse()
            assertThat(outputXYY.exists()).isFalse()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dependencyHasConflict_keepGoing_bothTopLevelTargetsFail() {
        addOptions("--keep_going")
        writeConflictBzl()
        write(
            "foo/dummy.bzl",
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
            return [DefaultInfo(files = depset([output]))]

        dummy = rule(
            implementation = _impl,
            attrs = {
                "srcs": attr.label_list(allow_files = True),
                "deps": attr.label_list(providers = [DefaultInfo]),
            },
        )
        
        """.trimIndent()
        )
        write(
            "foo/BUILD",
            """
        load("//foo:conflict.bzl", "my_rule")
        load("//foo:dummy.bzl", "dummy")

        my_rule(name = "conflict_first")

        my_rule(
            name = "conflict_second",
            deps = [":conflict_first"],
        )

        dummy(
            name = "top_level_a",
            deps = [":conflict_second"],
        )

        dummy(
            name = "top_level_b",
            deps = [":conflict_second"],
        )
        
        """.trimIndent()
        )
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:top_level_a", "//foo:top_level_b") })
        events.assertContainsError(
            "file 'foo/conflict_output' is generated by these conflicting actions:"
        )
        Truth.assertThat(eventListener.analysisFailures)
            .containsExactly("//foo:top_level_a", "//foo:top_level_b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun conflict_noTrackIncrementalState_detected() {
        TruthJUnit.assume().that(minimizeMemory).isTrue()
        writeConflictBzl()
        write(
            "foo/BUILD",
            """
        load("//foo:conflict.bzl", "my_rule")

        my_rule(name = "first")

        my_rule(name = "second")
        
        """.trimIndent()
        )

        buildTarget("//foo:first")
        val outputDirName: String? = getTargetConfigurationFromLastBuildResult().getOutputDirectoryName()

        val e: ViewCreationFailedException =
            org.junit.Assert.assertThrows<T>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:first", "//foo:second") })
        assertThat(e).hasCauseThat().isInstanceOf(ActionConflictException::class.java)
        val msg: String = e.getCause().getMessage()

        // Assert that the two action hashes are different.
        val m: java.util.regex.Matcher =
            java.util.regex.Pattern.compile("Action key: ([0-9a-f]{64}), ([0-9a-f]{64})").matcher(msg)
        Truth.assertThat(m.find()).isTrue()
        Truth.assertThat(m.group(1)).isNotEqualTo(m.group(2))

        // Infer the evaluation order by checking which target label appears first in the message.
        val firstIdx: Int = msg.indexOf("//foo:first")
        val secondIdx: Int = msg.indexOf("//foo:second")
        Truth.assertThat(firstIdx).isNotEqualTo(-1)
        Truth.assertThat(secondIdx).isNotEqualTo(-1)

        val attemptedAction: String?
        val previousAction: String?
        if (firstIdx < secondIdx) {
            attemptedAction = "first"
            previousAction = "second"
        } else {
            attemptedAction = "second"
            previousAction = "first"
        }

        // Ensure that we are printing the differences in the describeKey()
        val expected: String? =
            """
        Action describeKey: are different:
          Action A:   Argument: 'touch %1${'$'}s-out/%2${'$'}s/bin/foo/conflict_output %1${'$'}s-out/%2${'$'}s/bin/foo/other%3${'$'}s'
          Action B:   Argument: 'touch %1${'$'}s-out/%2${'$'}s/bin/foo/conflict_output %1${'$'}s-out/%2${'$'}s/bin/foo/other%4${'$'}s'
        
        """
                .trimIndent()
                .formatted(TestConstants.PRODUCT_NAME, outputDirName, attemptedAction, previousAction)

        Truth.assertThat(msg).contains(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun directoryWithNestedFile() {
        write(
            "foo/conflict.bzl",
            """
        def _impl(ctx):
            dir = ctx.actions.declare_directory(ctx.label.name + ".dir")
            file = ctx.actions.declare_file(ctx.label.name + ".dir/file.txt")
            ctx.actions.run_shell(
                outputs = [dir, file],
                command = "mkdir -p ${'$'}1 && touch ${'$'}2",
                arguments = [dir.path, file.path],
            )
            return [DefaultInfo(files = depset([dir, file]))]

        my_rule = rule(implementation = _impl)
        
        """.trimIndent()
        )
        write(
            "foo/BUILD",
            """
        load(":conflict.bzl", "my_rule")

        my_rule(name = "bar")
        
        """.trimIndent()
        )

        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:bar") })
        events.assertContainsError("One of the output paths")
        events.assertContainsError("is a prefix of the other")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun directoryWithNestedDirectory() {
        write(
            "foo/conflict.bzl",
            """
        def _impl(ctx):
            dir = ctx.actions.declare_directory(ctx.label.name + ".dir")
            subdir = ctx.actions.declare_directory(ctx.label.name + ".dir/subdir")
            ctx.actions.run_shell(
                outputs = [dir, subdir],
                command = "mkdir -p ${'$'}1 && mkdir -p ${'$'}2",
                arguments = [dir.path, subdir.path],
            )
            return [DefaultInfo(files = depset([dir, subdir]))]

        my_rule = rule(implementation = _impl)
        
        """.trimIndent()
        )
        write(
            "foo/BUILD",
            """
        load(":conflict.bzl", "my_rule")

        my_rule(name = "bar")
        
        """.trimIndent()
        )

        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:bar") })
        events.assertContainsError("One of the output paths")
        events.assertContainsError("is a prefix of the other")
    }

    private fun skipTheRestIfSkymeldAndMinimizeMemory() {
        TruthJUnit.assume().that(skymeld && minimizeMemory).isFalse()
    }
}
