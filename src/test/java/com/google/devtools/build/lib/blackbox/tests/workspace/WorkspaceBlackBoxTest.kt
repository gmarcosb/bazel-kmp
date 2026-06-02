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
import com.google.devtools.build.lib.blackbox.tests.workspace.RepoWithRuleWritingTextGenerator
import com.google.devtools.build.lib.blackbox.tests.workspace.WorkspaceTestUtils
import com.google.devtools.build.lib.vfs.Path
import java.nio.file.Path
import java.nio.file.Paths

/** End to end test of workspace-related functionality.  */
class WorkspaceBlackBoxTest : AbstractBlackBoxTest() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNotInMsys() {
        context()
            .write(
                "repo_rule.bzl",
                """
            def _impl(rctx):
                result = rctx.execute(["bash", "-c", "which bash > out.txt"])
                if result.return_code != 0:
                    fail("Execute bash failed: " + result.stderr)
                rctx.file("BUILD", 'exports_files(["out.txt"])')

            check_bash = repository_rule(implementation = _impl)
            
            """.trimIndent()
            )

        context()
            .write(
                AbstractBlackBoxTest.Companion.MODULE_DOT_BAZEL,
                "check_bash = use_repo_rule('//:repo_rule.bzl', 'check_bash')",
                "check_bash(name = 'check_bash_target')"
            )

        // To make repository rule target be computed, depend on it in debug_rule
        context()
            .write(
                "BUILD",
                "load(':rule.bzl', 'debug_rule')",
                "debug_rule(name = 'check', dep = '@check_bash_target//:out.txt')"
            )

        context()
            .write(
                "rule.bzl",
                """
            def _impl(ctx):
                out = ctx.actions.declare_file("does_not_matter")
                ctx.actions.do_nothing(mnemonic = "UseInput", inputs = ctx.attr.dep.files)
                ctx.actions.write(out, "Hi")
                return [DefaultInfo(files = depset([out]))]

            debug_rule = rule(
                implementation = _impl,
                attrs = {
                    "dep": attr.label(allow_single_file = True),
                },
            )
            
            """.trimIndent()
            )

        val bazel: BuilderRunner = WorkspaceTestUtils.bazel(context())
        // The build using "bash" should fail on Windows, and pass on Linux and Mac OS
        if (AbstractBlackBoxTest.Companion.isWindows()) {
            bazel.shouldFail()
        }
        bazel.build("check")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecuteInWorkingDirectory() {
        val pwd = if (AbstractBlackBoxTest.Companion.isWindows()) "['cmd', '/c', 'echo %cd%']" else "['pwd']"
        val buildFileText =
            ("\"\"\""
                    + java.lang.String.join(
                "\n",
                RepoWithRuleWritingTextGenerator.Companion.loadRule("@main"),
                RepoWithRuleWritingTextGenerator.Companion.callRule("debug_me", "out", "%s")
            )
                    + "\"\"\" % stdout")
        context()
            .write(
                "repo_rule.bzl",
                "def _impl(rctx):",
                String.format(
                    "  result = rctx.execute(%s, working_directory=rctx.attr.working_directory)", pwd
                ),
                "  if result.return_code != 0:",
                "    fail('Execute failed: ' + result.stderr)",  // we want to compare the real paths,
                // otherwise it is not clear how to verify the relative path variant
                "  wd = str(rctx.path(rctx.attr.working_directory))",  // pwd returns the path with '\n' in the end of the line; cut it
                "  stdout = result.stdout.strip(' \\n\\r').replace('\\\\', '/')",
                "  if wd != stdout:",
                "    fail('Wrong current directory: **%s**, expecting **%s**' % (stdout, wd))",  // create BUILD file with a target so we can call it;
                // rule of a target is defined in the main repository
                "  rctx.file('BUILD', " + buildFileText + ")",
                "check_wd = repository_rule(implementation = _impl,",
                "  attrs = { 'working_directory': attr.string() }",
                ")"
            )

        context()
            .write(
                RepoWithRuleWritingTextGenerator.Companion.HELPER_FILE,
                RepoWithRuleWritingTextGenerator.Companion.WRITE_TEXT_TO_FILE
            )
        context().write("BUILD")

        val tempDirectory: Path = java.nio.file.Files.createTempDirectory("temp-execute")
        context()
            .write(
                AbstractBlackBoxTest.Companion.MODULE_DOT_BAZEL,
                "module(name = 'main')",
                "check_wd = use_repo_rule('//:repo_rule.bzl', 'check_wd')",
                "check_wd(name = 'relative', working_directory = 'relative')",
                "check_wd(name = 'relative2', working_directory = '../relative2')",
                String.format(
                    "check_wd(name = 'absolute', working_directory = '%s')",
                    com.google.devtools.build.lib.blackbox.framework.PathUtils.pathForStarlarkFile(tempDirectory)
                ),
                String.format(
                    "check_wd(name = 'absolute2', working_directory = '%s')",
                    com.google.devtools.build.lib.blackbox.framework.PathUtils.pathForStarlarkFile(
                        tempDirectory.resolve(
                            "non_existent_child"
                        )
                    )
                )
            )

        val bazel: BuilderRunner = WorkspaceTestUtils.bazel(context())
        bazel.build("@relative//:debug_me")
        val outFile: Path = context().resolveBinPath(bazel, "external/+check_wd+relative/out")
        Truth.assertThat(outFile.toFile().exists()).isTrue()
        val lines: MutableList<String?> = com.google.devtools.build.lib.blackbox.framework.PathUtils.readFile(outFile)
        Truth.assertThat(lines.size).isEqualTo(1)
        Truth.assertThat(Paths.get(lines.get(0)).endsWith(Paths.get("external/+check_wd+relative/relative")))
            .isTrue()

        bazel.build("@relative2//:debug_me")
        bazel.build("@absolute//:debug_me")

        bazel.build("@absolute2//:debug_me")
        val outFile2: Path = context().resolveBinPath(bazel, "external/+check_wd+absolute2/out")
        Truth.assertThat(outFile2.toFile().exists()).isTrue()
        val lines2: MutableList<String?> = com.google.devtools.build.lib.blackbox.framework.PathUtils.readFile(outFile2)
        Truth.assertThat(lines2.size).isEqualTo(1)
        Truth.assertThat(Paths.get(lines2.get(0)) == tempDirectory.resolve("non_existent_child"))
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWorkspaceChanges() {
        val repoA: Path? = context().getTmpDir().resolve("a")
        RepoWithRuleWritingTextGenerator(repoA).withOutputText("hi").setupRepository()

        val repoB: Path? = context().getTmpDir().resolve("b")
        RepoWithRuleWritingTextGenerator(repoB).withOutputText("bye").setupRepository()

        context()
            .write(
                AbstractBlackBoxTest.Companion.MODULE_DOT_BAZEL,
                "local_repository = use_repo_rule('@bazel_tools//tools/build_defs/repo:local.bzl',"
                        + " 'local_repository')",
                String.format(
                    "local_repository(name = 'x', path = '%s',)",
                    com.google.devtools.build.lib.blackbox.framework.PathUtils.pathForStarlarkFile(repoA)
                )
            )
        val bazel: BuilderRunner = WorkspaceTestUtils.bazel(context())
        bazel.build("@x//:" + RepoWithRuleWritingTextGenerator.Companion.TARGET)

        val xPath: Path = context().resolveBinPath(bazel, "external/+local_repository+x/out")
        WorkspaceTestUtils.assertLinesExactly(xPath, "hi")

        context()
            .write(
                AbstractBlackBoxTest.Companion.MODULE_DOT_BAZEL,
                "local_repository = use_repo_rule('@bazel_tools//tools/build_defs/repo:local.bzl',"
                        + " 'local_repository')",
                String.format(
                    "local_repository(name = 'x', path = '%s',)",
                    com.google.devtools.build.lib.blackbox.framework.PathUtils.pathForStarlarkFile(repoB)
                )
            )
        bazel.build("@x//:" + RepoWithRuleWritingTextGenerator.Companion.TARGET)

        WorkspaceTestUtils.assertLinesExactly(xPath, "bye")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoPackageLoadingOnBenignWorkspaceChanges() {
        val repo: Path? = context().getTmpDir().resolve(testName.getMethodName())
        RepoWithRuleWritingTextGenerator(repo).withOutputText("hi").setupRepository()

        context()
            .write(
                AbstractBlackBoxTest.Companion.MODULE_DOT_BAZEL,
                "local_repository = use_repo_rule('@bazel_tools//tools/build_defs/repo:local.bzl',"
                        + " 'local_repository')",
                String.format(
                    "local_repository(name = 'ext', path = '%s',)",
                    com.google.devtools.build.lib.blackbox.framework.PathUtils.pathForStarlarkFile(repo)
                )
            )

        val bazel: BuilderRunner =
            WorkspaceTestUtils.bazel(context()) // This combination of flags ensures all progress events get into stdout
                // and Bazel recognizes that there is a terminal, so progress events will be displayed
                .withFlags("--experimental_ui_debug_all_events", "--curses=yes")

        val progressMessage =
            "PROGRESS <no location>: Loading package: @@+local_repository+ext//"

        var result: ProcessResult = bazel.query("@ext//:all")
        Truth.assertThat(result.outString()).contains(progressMessage)

        result = bazel.query("@ext//:all")
        Truth.assertThat(result.outString()).doesNotContain(progressMessage)

        // TODO(bzlmod): Fix this for MODULE.bazel
        // Path moduleDotBazel = context().getWorkDir().resolve(MODULE_DOT_BAZEL);
        // PathUtils.append(moduleDotBazel, "# comment");

        // result = bazel.query("@ext//:all");
        // assertThat(result.outString()).doesNotContain(progressMessage);
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPathWithSpace() {
        context().write("a b/MODULE.bazel")
        val bazel: BuilderRunner = WorkspaceTestUtils.bazel(context())
        bazel.info()
        bazel.help()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadRepoName() {
        context()
            .write(
                AbstractBlackBoxTest.Companion.MODULE_DOT_BAZEL,
                "local_repository = use_repo_rule('@bazel_tools//tools/build_defs/repo:local.bzl',"
                        + " 'local_repository')",
                "local_repository(name = '@a', path = 'abc')"
            )
        context().write("BUILD")
        val result: ProcessResult = context().bazel().shouldFail().build("//...")
        Truth.assertThat(result.errString()).contains("invalid user-provided repo name '@a'")
    }
}
