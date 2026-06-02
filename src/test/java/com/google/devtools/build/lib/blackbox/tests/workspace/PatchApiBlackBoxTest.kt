// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.blackbox.tests.workspace

import com.google.common.truth.Truth
import com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorDescriptor.Builder.build
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkBaseExternalContext.readFile
import com.google.devtools.build.lib.blackbox.framework.BuilderRunner
import com.google.devtools.build.lib.blackbox.framework.ProcessResult
import com.google.devtools.build.lib.blackbox.junit.AbstractBlackBoxTest
import com.google.devtools.build.lib.blackbox.tests.workspace.WorkspaceTestUtils
import com.google.devtools.build.lib.vfs.Path
import java.io.IOException
import java.nio.file.Path

/**
 * End to end test of patch API we exposed in @bazel_tools//tools/build_defs/repo:utils.bzl. The
 * patch API is used in http_repository and git_repository.
 * 
 * 
 * The idea is to use a custom repository rules that use the API to patch existing files.
 */
class PatchApiBlackBoxTest : AbstractBlackBoxTest() {
    @Throws(IOException::class)
    private fun setUpPatchTestRepo(
        patchArgs: com.google.common.collect.ImmutableList<String?>?, patchTool: String?, hasPatchCmdsWin: Boolean
    ) {
        var patchArgsStr: java.lang.StringBuilder = java.lang.StringBuilder("\"-p1\"")
        if (patchArgs != null) {
            patchArgsStr = java.lang.StringBuilder()
            for (arg in patchArgs) {
                patchArgsStr.append("\"").append(arg).append("\", ")
            }
        }
        context()
            .write(
                "patched_repo.bzl",
                """
            load(
                "@bazel_tools//tools/build_defs/repo:utils.bzl",
                "patch",
                "workspace_and_buildfile",
            )

            _common_attrs = {
                "files": attr.string_dict(default = {}),
                "patches": attr.label_list(default = []),
                "patch_tool": attr.string(default = ""),
                "patch_args": attr.string_list(default = []),
                "patch_cmds": attr.string_list(default = []),
                "patch_cmds_win": attr.string_list(default = []),
                "build_file": attr.label(allow_single_file = True),
                "build_file_content": attr.string(),
            }

            def _patched_repo_implementation(ctx):
                for file_name, label in ctx.attr.files.items():
                    ctx.template(file_name, ctx.path(Label(label)))
                workspace_and_buildfile(ctx)
                patch(ctx)

            patched_repo = repository_rule(
                implementation = _patched_repo_implementation,
                attrs = _common_attrs,
            )
            
            """.trimIndent()
            )
        context()
            .write(
                AbstractBlackBoxTest.Companion.MODULE_DOT_BAZEL,
                "patched_repo = use_repo_rule(\"//:patched_repo.bzl\", \"patched_repo\")",
                "",
                "patched_repo(",
                "    name = \"test\",",
                "    files = {\"foo.sh\" : \"//:foo.sh\"},",
                "    patches = [\"//:remove-dragons.patch\"],",
                String.format("    patch_args = [%s],", patchArgsStr.toString()),
                (if (patchTool == null) "" else String.format("    patch_tool = \"%s\",", patchTool)),
                "    patch_cmds = [",
                "      \"find . -name '*.sh' -exec sed -i.bak '1s|/usr/bin/env sh|/bin/sh|' {} +\",",
                "      \"chmod u+x ./foo.sh\",",
                "    ],",
                (if (hasPatchCmdsWin)
                    ("    patch_cmds_win = [\"(Get-Content -path foo.sh) -replace '/usr/bin/env"
                            + " sh','/bin/sh' | Set-Content -Path foo.sh\"],")
                else
                    ""),
                "    build_file_content =",
                "    \"\"\"",
                "filegroup(",
                "    name = \"foo\",",
                "    srcs = [\"foo.sh\"],",
                ")",
                "    \"\"\"",
                ")"
            )
        context().write("BUILD")
        context().write("foo.sh", "#!/usr/bin/env sh", "", "echo Here be dragons...", "")
        context()
            .write(
                "remove-dragons.patch",
                "#!/usr/bin/env sh",
                "diff --git a/foo.sh b/foo.sh",
                "index 1f4c41e..9d548ff 100644",
                "--- a/foo.sh",
                "+++ b/foo.sh",
                "@@ -1,4 +1,4 @@",
                " #!/usr/bin/env sh",
                "",
                "-echo Here be dragons...",
                "+echo New version of foo.sh, no more dangerous animals...",
                ""
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPatchApiUsingNativePatch() {
        setUpPatchTestRepo(null, null, true)
        val bazel: BuilderRunner = WorkspaceTestUtils.bazel(context())
        bazel.build("@test//:foo")
        assertFooIsPatched(bazel)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPatchApiUsingNativePatchFailed() {
        // Using -p2 should cause an error
        setUpPatchTestRepo(com.google.common.collect.ImmutableList.of<String?>("-p2"), null, true)
        val bazel: BuilderRunner = WorkspaceTestUtils.bazel(context()).shouldFail()
        val result: ProcessResult = bazel.build("@test//:foo")
        Truth.assertThat(result.errString())
            .contains("Cannot determine file name with strip = 2 at line 4:\n--- a/foo.sh")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFallBackToPatchToolDueToPatchArgs() {
        // Native patch doesn't support -b argument, should fallback to patch command line tool.
        setUpPatchTestRepo(com.google.common.collect.ImmutableList.of<String?>("-p1", "-b"), null, true)
        val bazel: BuilderRunner = WorkspaceTestUtils.bazel(context())
        if (AbstractBlackBoxTest.Companion.isWindows()) {
            // On Windows, we expect no patch tool in PATH after removing MSYS paths from PATH env var.
            bazel.shouldFail()
        }
        val result: ProcessResult = bazel.build("@test//:foo")
        if (AbstractBlackBoxTest.Companion.isWindows()) {
            Truth.assertThat(result.errString())
                .contains("CreateProcessW(\"C:\\foo\\bar\\usr\\bin\\bash.exe\" -c \"patch '-p1' '-b'")
            Truth.assertThat(result.errString()).contains("The system cannot find the file specified.")
        } else {
            assertFooIsPatched(bazel)
            // foo.sh.orig should be generated due to "-b" argument.
            val fooOrig: Path =
                context().resolveExecRootPath(bazel, "external/+patched_repo+test/foo.sh.orig")
            Truth.assertThat(fooOrig.toFile().exists()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFallBackToPatchToolWhenItIsSpecified() {
        // Should fallback to the specified patch tool.
        setUpPatchTestRepo(null, "patch", true)
        val bazel: BuilderRunner = WorkspaceTestUtils.bazel(context())
        if (AbstractBlackBoxTest.Companion.isWindows()) {
            // On Windows, we expect no patch tool in PATH after removing MSYS paths from PATH env var.
            bazel.shouldFail()
        }
        val result: ProcessResult = bazel.build("@test//:foo")
        if (AbstractBlackBoxTest.Companion.isWindows()) {
            Truth.assertThat(result.errString())
                .contains("CreateProcessW(\"C:\\foo\\bar\\usr\\bin\\bash.exe\" -c \"patch '-p1'")
            Truth.assertThat(result.errString()).contains("The system cannot find the file specified.")
        } else {
            assertFooIsPatched(bazel)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFallBackToPatchCmdsWhenPatchCmdsWinNotSpecified() {
        setUpPatchTestRepo(null, null, false)
        val bazel: BuilderRunner = WorkspaceTestUtils.bazel(context())
        if (AbstractBlackBoxTest.Companion.isWindows()) {
            // On Windows, we expect no bash tool in PATH after removing MSYS paths from PATH env var.
            bazel.shouldFail()
        }
        val result: ProcessResult = bazel.build("@test//:foo")
        if (AbstractBlackBoxTest.Companion.isWindows()) {
            Truth.assertThat(result.errString())
                .contains("CreateProcessW(\"C:\\foo\\bar\\usr\\bin\\bash.exe\" -c")
            Truth.assertThat(result.errString()).contains("The system cannot find the file specified.")
        } else {
            assertFooIsPatched(bazel)
        }
    }

    @Throws(java.lang.Exception::class)
    private fun assertFooIsPatched(bazel: BuilderRunner?) {
        val foo: Path = context().resolveExecRootPath(bazel, "external/+patched_repo+test/foo.sh")
        Truth.assertThat(foo.toFile().exists()).isTrue()
        val patchedFoo: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "#!/bin/sh", "", "echo New version of foo.sh, no more dangerous animals...", ""
            )
        Truth.assertThat(com.google.devtools.build.lib.blackbox.framework.PathUtils.readFile(foo))
            .containsExactlyElementsIn(patchedFoo)
    }
}
