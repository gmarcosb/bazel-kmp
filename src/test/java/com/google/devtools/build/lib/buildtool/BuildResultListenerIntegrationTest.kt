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

/** Integration test for [com.google.devtools.build.lib.skyframe.BuildResultListener].  */
@RunWith(TestParameterInjector::class)
class BuildResultListenerIntegrationTest : BuildIntegrationTestCase() {
    @TestParameter
    var mergedAnalysisExecution: Boolean = false

    @Before
    fun setUp() {
        addOptions("--experimental_merged_skyframe_analysis_execution=" + mergedAnalysisExecution)
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

    @Throws(IOException::class)
    private fun writeSuccessfulAspectBzl() {
        write(
            "foo/aspect.bzl",
            """
        def _aspect_impl(target, ctx):
            print("hello")
            return []

        successful_aspect = aspect(implementation = _aspect_impl)
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multiTargetBuild_success() {
        writeMyRuleBzl()
        writeSuccessfulAspectBzl()
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
        addOptions("--aspects=//foo:aspect.bzl%successful_aspect")

        val result: BuildResult = buildTarget("//foo:foo", "//foo:bar")

        assertThat(result.getSuccess()).isTrue()
        Truth.assertThat(getLabelsOfAnalyzedTargets()).containsExactly("//foo:foo", "//foo:bar")
        Truth.assertThat(getLabelsOfBuiltTargets()).containsExactly("//foo:foo", "//foo:bar")
        Truth.assertThat(getLabelsOfAnalyzedAspects()).containsExactly("//foo:foo", "//foo:bar")
        Truth.assertThat(getLabelsOfBuiltAspects()).containsExactly("//foo:foo", "//foo:bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectAnalysisFailure_consistentWithNonSkymeld() {
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

        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:foo") })

        Truth.assertThat(getLabelsOfAnalyzedAspects()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectExecutionFailure_consistentWithNonSkymeld(@TestParameter keepGoing: Boolean) {
        addOptions("--keep_going=" + keepGoing)
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

        Truth.assertThat(getLabelsOfAnalyzedAspects()).contains("//foo:foo")
        Truth.assertThat(getLabelsOfBuiltAspects()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetExecutionFailure_consistentWithNonSkymeld(@TestParameter keepGoing: Boolean) {
        addOptions("--keep_going=" + keepGoing)
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

        Truth.assertThat(getLabelsOfAnalyzedTargets()).contains("//foo:execution_failure")
        if (keepGoing) {
            Truth.assertThat(getLabelsOfAnalyzedTargets())
                .containsExactly("//foo:foo", "//foo:execution_failure")
            Truth.assertThat(getLabelsOfBuiltTargets()).containsExactly("//foo:foo")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetAnalysisFailure_consistentWithNonSkymeld(@TestParameter keepGoing: Boolean) {
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
            Truth.assertThat(getLabelsOfAnalyzedTargets()).contains("//foo:foo")
            Truth.assertThat(getLabelsOfBuiltTargets()).containsExactly("//foo:foo")
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:foo", "//foo:analysis_failure") })
            assertThat(getBuildResultListener().getBuiltTargets()).isEmpty()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nullIncrementalBuild_correctAnalyzedAndBuiltTargets() {
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

        var result: BuildResult = buildTarget("//foo:foo")

        assertThat(result.getSuccess()).isTrue()
        Truth.assertThat(getLabelsOfAnalyzedTargets()).containsExactly("//foo:foo")
        Truth.assertThat(getLabelsOfBuiltTargets()).containsExactly("//foo:foo")

        result = buildTarget("//foo:foo")

        assertThat(result.getSuccess()).isTrue()
        Truth.assertThat(getLabelsOfAnalyzedTargets()).containsExactly("//foo:foo")
        Truth.assertThat(getLabelsOfBuiltTargets()).containsExactly("//foo:foo")
    }
}
