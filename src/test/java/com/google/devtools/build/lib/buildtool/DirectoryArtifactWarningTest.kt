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

import com.google.devtools.build.lib.actions.BuildFailedException

/**
 * Integration test for warnings issued when an artifact is a directory.
 */
@RunWith(JUnit4::class)
class DirectoryArtifactWarningTest : BuildIntegrationTestCase() {
    @Throws(java.lang.Exception::class)
    private fun setupGenruleWithOutputArtifactDirectory() {
        write(
            "x/BUILD",
            """
        genrule(
            name = "x",
            srcs = [],
            outs = ["dir"],
            cmd = "mkdir ${'$'}(location dir)",
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputArtifactDirectoryError_forGenrule() {
        setupGenruleWithOutputArtifactDirectory()

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//x") })

        events.assertContainsError(
            "output 'x/dir' of //x:x is a directory but was not declared as such"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun setupStarlarkRuleWithOutputArtifactDirectory() {
        write(
            "x/defs.bzl",
            """
        def _impl(ctx):
            ctx.actions.run_shell(
                outputs = [ctx.outputs.out],
                command = "mkdir %s" % ctx.outputs.out.path,
            )

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "out": attr.output(),
            },
        )
        
        """.trimIndent()
        )
        write(
            "x/BUILD",
            """
        load("defs.bzl", "my_rule")

        my_rule(
            name = "x",
            out = "dir",
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputArtifactDirectoryError_forStarlarkRule() {
        setupStarlarkRuleWithOutputArtifactDirectory()

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//x") })

        events.assertContainsError(
            "output 'x/dir' of //x:x is a directory but was not declared as such"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInputArtifactDirectoryWarning_forGenrule() {
        write(
            "x/BUILD",
            """
        genrule(
            name = "x",
            srcs = ["dir"],
            outs = ["out"],
            cmd = "touch ${'$'}(location out)",
        )
        
        """.trimIndent()
        )
        write("x/dir/empty")

        buildTarget("//x")

        events.assertContainsWarning(
            "input 'x/dir' of //x:x is a directory; "
                    + "dependency checking of directories is unsound"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInputArtifactDirectoryWarning_forStarlarkRule() {
        write(
            "x/defs.bzl",
            """
        def _impl(ctx):
            ctx.actions.run_shell(
                inputs = [ctx.file.src],
                outputs = [ctx.outputs.out],
                command = "touch %s" % ctx.outputs.out.path,
            )

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "src": attr.label(allow_single_file = True),
                "out": attr.output(),
            },
        )
        
        """.trimIndent()
        )
        write(
            "x/BUILD",
            """
        load("defs.bzl", "my_rule")

        my_rule(
            name = "x",
            src = "dir",
            out = "out",
        )
        
        """.trimIndent()
        )
        write("x/dir/empty")

        buildTarget("//x")

        events.assertContainsWarning(
            "input 'x/dir' of //x:x is a directory; "
                    + "dependency checking of directories is unsound"
        )
    }
}
