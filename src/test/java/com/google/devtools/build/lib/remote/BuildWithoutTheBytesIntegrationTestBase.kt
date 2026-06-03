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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent

/** Base class for integration tests for BwoB.  */
abstract class BuildWithoutTheBytesIntegrationTestBase : BuildIntegrationTestCase() {
    // Concrete implementations should by default set the necessary flags to download minimal outputs.
    // These methods should override the necessary flags to download top-level outputs or all outputs.
    protected abstract fun setDownloadToplevel()

    protected abstract fun setDownloadAll()

    protected abstract fun enableActionRewinding()

    @Throws(java.lang.Exception::class)
    protected abstract fun assertOutputEquals(path: Path?, expectedContent: String?)

    @Throws(java.lang.Exception::class)
    protected abstract fun assertOutputContains(content: String?, contains: String?)

    @Throws(java.lang.Exception::class)
    protected abstract fun evictAllBlobs()

    protected abstract fun hasAccessToRemoteOutputs(): Boolean

    protected abstract fun injectFile(content: ByteArray?)

    @Throws(java.lang.Exception::class)
    protected fun waitDownloads() {
        // Trigger afterCommand of modules so that downloads are waited.
        runtimeWrapper.newCommand()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputsAreNotDownloaded() {
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = [],",
            "  outs = ['out/foo.txt'],",
            "  cmd = 'echo foo > $@',",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['out/foobar.txt'],",
            "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
            ")"
        )

        buildTarget("//:foobar")
        waitDownloads()

        assertOutputsDoNotExist("//:foo")
        assertOutputsDoNotExist("//:foobar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun disableRunfiles_buildSuccessfully() {
        // Disable on Windows since it fails for unknown reasons.
        // TODO(chiwang): Enable it on windows.
        Assume.assumeFalse(com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS)

        write(
            "BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "genrule(",
            "  name = 'foo',",
            "  cmd = 'echo foo > $@',",
            "  outs = ['foo.data'],",
            ")",
            "foo_test(",
            "  name = 'foobar',",
            "  srcs = ['test.sh'],",
            "  data = [':foo'],",
            ")"
        )
        write("test.sh")
        getWorkspace().getRelative("test.sh").setExecutable(true)
        addOptions("--build_runfile_links", "--enable_runfiles=no")

        buildTarget("//:foobar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputsWithRegex() {
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = [],",
            "  outs = ['out/foo.txt'],",
            "  cmd = 'echo foo > $@',",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['out/foobar.txt'],",
            "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
            ")"
        )
        addOptions("--remote_download_regex=.*foo\\.txt$")

        buildTarget("//:foobar")
        waitDownloads()

        assertValidOutputFile("out/foo.txt", "foo\n")
        assertOutputsDoNotExist("//:foobar")

        // Assert that no actions have been executed for the next incremental build since nothing
        // changed
        val actionEventCollector = ActionEventCollector()
        runtimeWrapper.registerSubscriber(actionEventCollector)
        // Override out/foo.txt with the same content
        run {
            val path: Path = getOutputPath("out/foo.txt")
            val isWritable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                path.isWritable()
            if (!isWritable) {
                path.setWritable(true)
            }
            writeContent(path, java.nio.charset.StandardCharsets.UTF_8, "foo\n")
            if (!isWritable) {
                path.setWritable(false)
            }
        }
        buildTarget("//:foobar")
        Truth.assertThat(actionEventCollector.getActionExecutedEvents()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputsWithRegex_deleteOutput_reDownload() {
        // Arrange: Do a clean build and download out/foo.txt
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = [],",
            "  outs = ['out/foo.txt'],",
            "  cmd = 'echo foo > $@',",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['out/foobar.txt'],",
            "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
            ")"
        )
        addOptions("--remote_download_regex=.*foo\\.txt$")

        buildTarget("//:foobar")
        waitDownloads()

        assertValidOutputFile("out/foo.txt", "foo\n")
        assertOutputsDoNotExist("//:foobar")

        // Arrange: Delete out/foo.txt and do an incremental build
        getOutputPath("out/foo.txt").delete()
        val actionEventCollector = ActionEventCollector()
        runtimeWrapper.registerSubscriber(actionEventCollector)
        buildTarget("//:foobar")
        waitDownloads()

        // Assert: out/foo.txt is re-downloaded
        Truth.assertThat(actionEventCollector.getActionExecutedEvents()).hasSize(1)
        assertValidOutputFile("out/foo.txt", "foo\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputsWithRegex_changeRegex_downloadNewMatches() {
        // Arrange: Do a clean build
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = [],",
            "  outs = ['out/foo.txt'],",
            "  cmd = 'echo foo > $@',",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['out/foobar.txt'],",
            "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
            ")"
        )

        buildTarget("//:foobar")
        // Add the new option here because waitDownloads below will internally create a new command
        // which will parse the new option.
        addOptions("--remote_download_regex=.*foobar\\.txt$")
        waitDownloads()

        assertOutputsDoNotExist("//:foo")
        assertOutputsDoNotExist("//:foobar")

        // Arrange: Change regex
        val actionEventCollector = ActionEventCollector()
        runtimeWrapper.registerSubscriber(actionEventCollector)
        buildTarget("//:foobar")
        waitDownloads()

        // Assert: out/foobar.txt is downloaded
        Truth.assertThat(actionEventCollector.getActionExecutedEvents()).hasSize(1)
        assertValidOutputFile("out/foobar.txt", "foo\nbar\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputsWithRegex_treeOutput_regexMatchesTreeFile() {
        writeOutputDirRule()
        write(
            "BUILD",
            "load(':output_dir.bzl', 'output_dir')",
            "output_dir(",
            "  name = 'foo',",
            "  content_map = {'file-1': '1', 'file-2': '2', 'file-3': '3'},",
            ")"
        )
        addOptions("--remote_download_regex=.*foo/file-2$")

        buildTarget("//:foo")
        waitDownloads()

        assertValidOutputFile("foo/file-2", "2")
        assertOutputDoesNotExist("foo/file-1")
        assertOutputDoesNotExist("foo/file-3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputsWithRegex_treeOutput_regexMatchesTreeRoot() {
        writeOutputDirRule()
        write(
            "BUILD",
            "load(':output_dir.bzl', 'output_dir')",
            "output_dir(",
            "  name = 'foo',",
            "  content_map = {'file-1': '1', 'file-2': '2', 'file-3': '3'},",
            ")"
        )
        addOptions("--remote_download_regex=.*foo$")

        buildTarget("//:foo")
        waitDownloads()

        assertThat(getOutputPath("foo").exists()).isTrue()
        assertOutputEquals(getOutputPath("foo/file-1"), "1")
        assertOutputEquals(getOutputPath("foo/file-2"), "2")
        assertOutputEquals(getOutputPath("foo/file-3"), "3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputsWithRegex_treeOutput_regexMatchesEmptyTreeRoot() {
        writeOutputDirRule()
        write(
            "BUILD",
            "load(':output_dir.bzl', 'output_dir')",
            "output_dir(",
            "  name = 'foo',",
            "  content_map = {},",
            ")"
        )
        addOptions("--remote_download_regex=.*foo$")

        buildTarget("//:foo")
        waitDownloads()

        assertThat(getOutputPath("foo").exists()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadOutputsWithRegex_regexMatchParentPath_filesNotDownloaded() {
        write(
            "BUILD",
            "genrule(",
            "  name = 'file-1',",
            "  srcs = [],",
            "  outs = ['foo/file-1'],",
            "  cmd = 'echo file-1 > $@',",
            ")",
            "genrule(",
            "  name = 'file-2',",
            "  srcs = [],",
            "  outs = ['foo/file-2'],",
            "  cmd = 'echo file-2 > $@',",
            ")",
            "genrule(",
            "  name = 'file-3',",
            "  srcs = [],",
            "  outs = ['foo/file-3'],",
            "  cmd = 'echo file-3 > $@',",
            ")"
        )
        addOptions("--remote_download_regex=.*foo$")

        buildTarget("//:file-1", "//:file-2", "//:file-3")
        waitDownloads()

        assertOutputDoesNotExist("foo/file-1")
        assertOutputDoesNotExist("foo/file-2")
        assertOutputDoesNotExist("foo/file-3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun intermediateOutputsAreInputForLocalActions_prefetchIntermediateOutputs() {
        // Test that a remote-only output that's an input to a local action is downloaded lazily before
        // executing the local action.
        write(
            "a/BUILD",
            """
        genrule(
            name = "remote",
            srcs = [],
            outs = ["remote.txt"],
            cmd = "echo -n remote > ${'$'}@",
        )

        genrule(
            name = "local",
            srcs = [":remote"],
            outs = ["local.txt"],
            cmd = "cat ${'$'}(location :remote) > ${'$'}@ && echo -n local >> ${'$'}@",
            tags = ["no-remote"],
        )
        
        """.trimIndent()
        )

        buildTarget("//a:remote")
        waitDownloads()
        assertOutputsDoNotExist("//a:remote")
        buildTarget("//a:local")
        waitDownloads()

        assertOnlyOutputContent("//a:remote", "remote.txt", "remote")
        assertOnlyOutputContent("//a:local", "local.txt", "remotelocal")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun localAction_inputSymlinkToSourceFile() {
        write(
            "a/defs.bzl",
            """
        def _impl(ctx):
            sym = ctx.actions.declare_file(ctx.label.name + ".sym")
            ctx.actions.symlink(output = sym, target_file = ctx.file.target)

            out = ctx.actions.declare_file(ctx.label.name + ".out")
            ctx.actions.run_shell(
                inputs = [sym],
                outputs = [out],
                command = "[[ hello == ${'$'}(cat ${'$'}1) ]] && touch ${'$'}2",
                arguments = [sym.path, out.path],
                execution_requirements = {"no-remote": ""},
            )

            return DefaultInfo(files = depset([out]))

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "target": attr.label(allow_single_file = True),
            },
        )
        
        """.trimIndent()
        )

        write(
            "a/BUILD",
            """
        load(":defs.bzl", "my_rule")

        my_rule(
            name = "my",
            target = "src.txt",
        )
        
        """.trimIndent()
        )

        write("a/src.txt", "hello")

        buildTarget("//a:my")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun localAction_inputSymlinkToGeneratedFile() {
        injectFile("hello".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        write(
            "a/defs.bzl",
            """
        def _impl(ctx):
            file = ctx.actions.declare_file(ctx.label.name + ".file")

            # Use ctx.actions.run_shell instead of ctx.actions.write, so that it runs remotely.
            ctx.actions.run_shell(
                outputs = [file],
                command = "echo -n hello > ${'$'}1",
                arguments = [file.path],
            )

            sym = ctx.actions.declare_file(ctx.label.name + ".sym")
            ctx.actions.symlink(output = sym, target_file = file)

            out = ctx.actions.declare_file(ctx.label.name + ".out")
            ctx.actions.run_shell(
                inputs = [sym],
                outputs = [out],
                command = "[[ hello == ${'$'}(cat ${'$'}1) ]] && touch ${'$'}2",
                arguments = [sym.path, out.path],
                execution_requirements = {"no-remote": ""},
            )

            return DefaultInfo(files = depset([out]))

        my_rule = rule(_impl)
        
        """.trimIndent()
        )

        write(
            "a/BUILD",
            """
        load(":defs.bzl", "my_rule")

        my_rule(name = "my")
        
        """.trimIndent()
        )

        buildTarget("//a:my")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun localAction_inputSymlinkToDirectory() {
        injectFile("hello".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        write(
            "a/defs.bzl",
            """
        def _impl(ctx):
            dir = ctx.actions.declare_directory(ctx.label.name + ".dir")
            ctx.actions.run_shell(
                outputs = [dir],
                command = "mkdir -p ${'$'}1/some/path && echo -n hello > ${'$'}1/some/path/inside.txt",
                arguments = [dir.path],
            )

            sym = ctx.actions.declare_directory(ctx.label.name + ".sym")
            ctx.actions.symlink(output = sym, target_file = dir)

            out = ctx.actions.declare_file(ctx.label.name + ".out")
            ctx.actions.run_shell(
                inputs = [sym],
                outputs = [out],
                command = "[[ hello == ${'$'}(cat ${'$'}1/some/path/inside.txt) ]] && touch ${'$'}2",
                arguments = [sym.path, out.path],
                execution_requirements = {"no-remote": ""},
            )

            return DefaultInfo(files = depset([out]))

        my_rule = rule(_impl)
        
        """.trimIndent()
        )

        write(
            "a/BUILD",
            """
        load(":defs.bzl", "my_rule")

        my_rule(name = "my")
        
        """.trimIndent()
        )

        buildTarget("//a:my")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun localAction_stdoutIsReported() {
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = [],",
            "  outs = ['out/foo.txt'],",
            "  cmd = 'echo my-output-message > $@',",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['out/foobar.txt'],",
            "  cmd = 'cat $(location :foo) && touch $@',",
            "  tags = ['no-remote'],",
            ")"
        )
        val outErr: RecordingOutErr = RecordingOutErr()
        this.outErr = outErr

        buildTarget("//:foobar")
        waitDownloads()

        assertOutputContains(outErr.outAsLatin1(), "my-output-message")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun localAction_stderrIsReported() {
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = [],",
            "  outs = ['out/foo.txt'],",
            "  cmd = 'echo my-error-message > $@',",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['out/foobar.txt'],",
            "  cmd = 'cat $(location :foo) >&2 && exit 1',",
            "  tags = ['no-remote'],",
            ")"
        )
        val outErr: RecordingOutErr = RecordingOutErr()
        this.outErr = outErr

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//:foobar") })

        assertOutputContains(outErr.errAsLatin1(), "my-error-message")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dynamicExecution_stdoutIsReported() {
        addOptions("--internal_spawn_scheduler")
        addOptions("--strategy=Genrule=dynamic")
        addOptions("--experimental_local_execution_delay=9999999")
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = [],",
            "  outs = ['out/foo.txt'],",
            "  cmd = 'echo my-output-message > $@',",
            "  tags = ['no-local'],",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['out/foobar.txt'],",
            "  cmd = 'cat $(location :foo) && touch $@',",
            ")"
        )
        val outErr: RecordingOutErr = RecordingOutErr()
        this.outErr = outErr

        buildTarget("//:foobar")
        waitDownloads()

        assertOutputContains(outErr.outAsLatin1(), "my-output-message")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dynamicExecution_stderrIsReported() {
        addOptions("--internal_spawn_scheduler")
        addOptions("--strategy=Genrule=dynamic")
        addOptions("--experimental_local_execution_delay=9999999")
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = [],",
            "  outs = ['out/foo.txt'],",
            "  cmd = 'echo my-error-message > $@',",
            "  tags = ['no-local'],",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['out/foobar.txt'],",
            "  cmd = 'cat $(location :foo) >&2 && exit 1',",
            ")"
        )
        val outErr: RecordingOutErr = RecordingOutErr()
        this.outErr = outErr

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//:foobar") })

        assertOutputContains(outErr.errAsLatin1(), "my-error-message")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadToplevel_outputsFromAspect_notAggregated() {
        setDownloadToplevel()
        writeCopyAspectRule( /* aggregate= */false)
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = ['foo.in'],",
            "  outs = ['foo.out'],",
            "  cmd = 'cat $(SRCS) > $@',",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['foobar.out'],",
            "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
            ")"
        )
        write("foo.in", "foo")

        addOptions("--aspects=rules.bzl%copy_aspect", "--output_groups=+copy")
        buildTarget("//:foobar")
        waitDownloads()

        assertValidOutputFile("foobar.out", "foo\nbar\n")
        assertOutputDoesNotExist("foo.in.copy")
        assertValidOutputFile("foo.out.copy", "foo\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadToplevel_outputsFromAspect_aggregated() {
        setDownloadToplevel()
        writeCopyAspectRule( /* aggregate= */true)
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = ['foo.in'],",
            "  outs = ['foo.out'],",
            "  cmd = 'cat $(SRCS) > $@',",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['foobar.out'],",
            "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
            ")"
        )
        write("foo.in", "foo")

        addOptions("--aspects=rules.bzl%copy_aspect", "--output_groups=+copy")
        buildTarget("//:foobar")
        waitDownloads()

        assertValidOutputFile("foobar.out", "foo\nbar\n")
        assertValidOutputFile("foo.in.copy", "foo\n")
        assertValidOutputFile("foo.out.copy", "foo\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadToplevel_outputsFromAspect_notDownloadedIfNoOutputGroups() {
        setDownloadToplevel()
        writeCopyAspectRule( /* aggregate= */true)
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = ['foo.in'],",
            "  outs = ['foo.out'],",
            "  cmd = 'cat $(SRCS) > $@',",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['foobar.out'],",
            "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
            ")"
        )
        write("foo.in", "foo")

        addOptions("--aspects=rules.bzl%copy_aspect")
        buildTarget("//:foobar")
        waitDownloads()

        assertValidOutputFile("foobar.out", "foo\nbar\n")
        assertOutputDoesNotExist("foo.in.copy")
        assertOutputDoesNotExist("foo.out.copy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadToplevel_outputsFromImportantOutputGroupAreDownloaded() {
        setDownloadToplevel()
        write(
            "rules.bzl",
            """
        def _gen_impl(ctx):
            output = ctx.actions.declare_file(ctx.attr.name)
            ctx.actions.run_shell(
                outputs = [output],
                arguments = [ctx.attr.content, output.path],
                command = "echo ${'$'}1 > ${'$'}2",
            )
            extra1 = ctx.actions.declare_file(ctx.attr.name + "1")
            ctx.actions.run_shell(
                outputs = [extra1],
                arguments = [ctx.attr.content, extra1.path],
                command = "echo ${'$'}1 > ${'$'}2",
            )
            extra2 = ctx.actions.declare_file(ctx.attr.name + "2")
            ctx.actions.run_shell(
                outputs = [extra2],
                arguments = [ctx.attr.content, extra2.path],
                command = "echo ${'$'}1 > ${'$'}2",
            )
            return [
                DefaultInfo(files = depset([output])),
                OutputGroupInfo(
                    extra1_files = depset([extra1]),
                    extra2_files = depset([extra2]),
                ),
            ]

        gen = rule(
            implementation = _gen_impl,
            attrs = {
                "content": attr.string(mandatory = True),
            },
        )
        
        """.trimIndent()
        )
        write(
            "BUILD",
            "load(':rules.bzl', 'gen')",
            "gen(",
            "  name = 'foo',",
            "  content = 'foo-content',",
            ")"
        )
        addOptions("--output_groups=+extra1_files")

        buildTarget("//:foo")
        waitDownloads()

        assertValidOutputFile("foo", "foo-content\n")
        assertValidOutputFile("foo1", "foo-content\n")
        assertOutputDoesNotExist("foo2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadToplevel_outputsFromHiddenOutputGroupAreNotDownloaded() {
        setDownloadToplevel()
        write(
            "rules.bzl",
            """
        def _gen_impl(ctx):
            output = ctx.actions.declare_file(ctx.attr.name)
            ctx.actions.run_shell(
                outputs = [output],
                arguments = [ctx.attr.content, output.path],
                command = "echo ${'$'}1 > ${'$'}2",
            )
            validation_file = ctx.actions.declare_file(ctx.attr.name + ".validation")
            ctx.actions.run_shell(
                outputs = [validation_file],
                arguments = [ctx.attr.content, validation_file.path],
                command = "echo ${'$'}1 > ${'$'}2",
            )
            return [
                DefaultInfo(files = depset([output])),
                OutputGroupInfo(
                    _validation = depset([validation_file]),
                ),
            ]

        gen = rule(
            implementation = _gen_impl,
            attrs = {
                "content": attr.string(mandatory = True),
            },
        )
        
        """.trimIndent()
        )
        write(
            "BUILD",
            "load(':rules.bzl', 'gen')",
            "gen(",
            "  name = 'foo',",
            "  content = 'foo-content',",
            ")"
        )
        addOptions("--output_groups=+_validation")

        buildTarget("//:foo")
        waitDownloads()

        assertValidOutputFile("foo", "foo-content\n")
        assertOutputDoesNotExist("foo.validation")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadToplevel_treeArtifacts() {
        setDownloadToplevel()
        writeOutputDirRule()
        write(
            "BUILD",
            "load(':output_dir.bzl', 'output_dir')",
            "output_dir(",
            "  name = 'foo',",
            "  content_map = {'file-1': '1', 'file-2': '2', 'file-3': '3'},",
            ")"
        )

        buildTarget("//:foo")

        assertValidOutputFile("foo/file-1", "1")
        assertValidOutputFile("foo/file-2", "2")
        assertValidOutputFile("foo/file-3", "3")
        // TODO(chiwang): Make metadata for downloaded outputs local.
        // assertThat(getMetadata("//:foo").values().stream().noneMatch(FileArtifactValue::isRemote))
        //     .isTrue();
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadToplevel_multipleToplevelTargets() {
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo1',",
            "  srcs = [],",
            "  outs = ['out/foo1.txt'],",
            "  cmd = 'echo foo1 > $@',",
            ")",
            "genrule(",
            "  name = 'foo2',",
            "  srcs = [],",
            "  outs = ['out/foo2.txt'],",
            "  cmd = 'echo foo2 > $@',",
            ")",
            "genrule(",
            "  name = 'foo3',",
            "  srcs = [],",
            "  outs = ['out/foo3.txt'],",
            "  cmd = 'echo foo3 > $@',",
            ")"
        )
        setDownloadToplevel()

        buildTarget("//:foo1", "//:foo2", "//:foo3")

        assertValidOutputFile("out/foo1.txt", "foo1\n")
        // TODO(chiwang): Make metadata for downloaded outputs local.
        // assertThat(getMetadata("//:foo1").values().stream().noneMatch(FileArtifactValue::isRemote))
        //     .isTrue();
        assertValidOutputFile("out/foo2.txt", "foo2\n")
        // TODO(chiwang): Make metadata for downloaded outputs local.
        // assertThat(getMetadata("//:foo2").values().stream().noneMatch(FileArtifactValue::isRemote))
        //     .isTrue();
        assertValidOutputFile("out/foo3.txt", "foo3\n")
        // TODO(chiwang): Make metadata for downloaded outputs local.
        // assertThat(getMetadata("//:foo3").values().stream().noneMatch(FileArtifactValue::isRemote))
        //     .isTrue();
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadToplevel_incrementalBuild_multipleToplevelTargets() {
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo1',",
            "  srcs = [],",
            "  outs = ['out/foo1.txt'],",
            "  cmd = 'echo foo1 > $@',",
            ")",
            "genrule(",
            "  name = 'foo2',",
            "  srcs = [],",
            "  outs = ['out/foo2.txt'],",
            "  cmd = 'echo foo2 > $@',",
            ")",
            "genrule(",
            "  name = 'foo3',",
            "  srcs = [],",
            "  outs = ['out/foo3.txt'],",
            "  cmd = 'echo foo3 > $@',",
            ")"
        )

        buildTarget("//:foo1", "//:foo2", "//:foo3")

        assertOutputsDoNotExist("//:foo1")
        Truth.assertThat(getMetadata("//:foo1").values.stream().allMatch(FileArtifactValue::isRemote))
            .isTrue()
        assertOutputsDoNotExist("//:foo2")
        Truth.assertThat(getMetadata("//:foo2").values.stream().allMatch(FileArtifactValue::isRemote))
            .isTrue()
        assertOutputsDoNotExist("//:foo3")
        Truth.assertThat(getMetadata("//:foo3").values.stream().allMatch(FileArtifactValue::isRemote))
            .isTrue()

        setDownloadToplevel()
        buildTarget("//:foo1", "//:foo2", "//:foo3")

        assertValidOutputFile("out/foo1.txt", "foo1\n")
        // TODO(chiwang): Make metadata for downloaded outputs local.
        // assertThat(getMetadata("//:foo1").values().stream().noneMatch(FileArtifactValue::isRemote))
        //     .isTrue();
        assertValidOutputFile("out/foo2.txt", "foo2\n")
        // TODO(chiwang): Make metadata for downloaded outputs local.
        // assertThat(getMetadata("//:foo2").values().stream().noneMatch(FileArtifactValue::isRemote))
        //     .isTrue();
        assertValidOutputFile("out/foo3.txt", "foo3\n")
        // TODO(chiwang): Make metadata for downloaded outputs local.
        // assertThat(getMetadata("//:foo3").values().stream().noneMatch(FileArtifactValue::isRemote))
        //     .isTrue();
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadToplevel_incrementalBuild_anotherTarget() {
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo1',",
            "  srcs = [':foo3'],",
            "  outs = ['out/foo1.txt'],",
            "  cmd = 'echo foo1 > $@',",
            ")",
            "genrule(",
            "  name = 'foo2',",
            "  srcs = [],",
            "  outs = ['out/foo2.txt'],",
            "  cmd = 'echo foo2 > $@',",
            ")",
            "genrule(",
            "  name = 'foo3',",
            "  srcs = [],",
            "  outs = ['out/foo3.txt'],",
            "  cmd = 'echo foo3 > $@',",
            ")"
        )
        setDownloadToplevel()
        buildTarget("//:foo1")

        assertValidOutputFile("out/foo1.txt", "foo1\n")
        assertOutputsDoNotExist("//:foo2")
        assertOutputsDoNotExist("//:foo3")

        buildTarget("//:foo3")
        assertOutputsDoNotExist("//:foo2")
        assertValidOutputFile("out/foo3.txt", "foo3\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadToplevel_symlinkToGeneratedFile() {
        setDownloadToplevel()
        writeSymlinkRule()
        write(
            "BUILD",
            "load(':symlink.bzl', 'symlink')",
            "genrule(",
            "  name = 'foo',",
            "  srcs = [],",
            "  outs = ['out/foo.txt'],",
            "  cmd = 'echo foo > $@',",
            ")",
            "symlink(",
            "  name = 'foo-link',",
            "  target_artifact = ':foo',",
            ")"
        )

        buildTarget("//:foo-link")

        assertSymlink("foo-link", getOutputPath("out/foo.txt").asFragment())
        assertValidOutputFile("foo-link", "foo\n")

        // Delete link, re-plant symlink
        getOutputPath("foo-link").delete()
        buildTarget("//:foo-link")

        assertSymlink("foo-link", getOutputPath("out/foo.txt").asFragment())
        assertValidOutputFile("foo-link", "foo\n")

        // Delete target, re-download it
        getOutputPath("out/foo.txt").delete()
        buildTarget("//:foo-link")

        assertSymlink("foo-link", getOutputPath("out/foo.txt").asFragment())
        assertValidOutputFile("foo-link", "foo\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadToplevel_symlinkToSourceFile() {
        setDownloadToplevel()
        writeSymlinkRule()
        write(
            "BUILD",
            "load(':symlink.bzl', 'symlink')",
            "symlink(",
            "  name = 'foo-link',",
            "  target_artifact = ':foo.txt',",
            ")"
        )
        write("foo.txt", "foo")

        buildTarget("//:foo-link")

        assertSymlink("foo-link", getSourcePath("foo.txt").asFragment())
        assertOnlyOutputContent("//:foo-link", "foo-link", "foo\n")

        // Delete link, re-plant symlink
        getOutputPath("foo-link").delete()
        buildTarget("//:foo-link")

        assertOnlyOutputContent("//:foo-link", "foo-link", "foo\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadToplevel_symlinkToDirectory() {
        setDownloadToplevel()
        writeSymlinkRule()
        writeOutputDirRule()
        write(
            "BUILD",
            "load(':output_dir.bzl', 'output_dir')",
            "load(':symlink.bzl', 'symlink')",
            "output_dir(",
            "  name = 'foo',",
            "  content_map = {'file-1': '1', 'file-2': '2', 'file-3': '3'},",
            ")",
            "symlink(",
            "  name = 'foo-link',",
            "  target_artifact = ':foo',",
            ")"
        )

        buildTarget("//:foo-link")

        assertSymlink("foo-link", getOutputPath("foo").asFragment())
        assertValidOutputFile("foo-link/file-1", "1")
        assertValidOutputFile("foo-link/file-2", "2")
        assertValidOutputFile("foo-link/file-3", "3")

        // Delete link, re-plant symlink
        getOutputPath("foo-link").deleteTree()
        buildTarget("//:foo-link")

        assertSymlink("foo-link", getOutputPath("foo").asFragment())
        assertValidOutputFile("foo-link/file-1", "1")
        assertValidOutputFile("foo-link/file-2", "2")
        assertValidOutputFile("foo-link/file-3", "3")

        // Delete target, re-download them
        getOutputPath("foo").deleteTree()

        buildTarget("//:foo-link")

        assertSymlink("foo-link", getOutputPath("foo").asFragment())
        assertValidOutputFile("foo-link/file-1", "1")
        assertValidOutputFile("foo-link/file-2", "2")
        assertValidOutputFile("foo-link/file-3", "3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun downloadToplevel_unresolvedSymlink(@TestParameter targetType: SymlinkTargetType) {
        val targetPath: Path =
            com.google.devtools.build.lib.testutil.TestUtils.createUniqueTmpDir(null).getChild("target")

        val targetPathArg: String? = targetPath.getPathString()
        val targetTypeArg =
            when (targetType) {
                FILE -> "file"
                DIRECTORY -> "directory"
                UNSPECIFIED -> ""
            }

        setDownloadToplevel()
        writeSymlinkRule()
        write(
            "BUILD",
            """
        load(':symlink.bzl', 'symlink')
        symlink(
          name = 'foo-link',
          target_path = '%s',
          target_type = '%s',
        )
        
        """
                .trimIndent()
                .formatted(targetPathArg, targetTypeArg)
        )

        buildTarget("//:foo-link")

        assertSymlink("foo-link", targetPath.asFragment())

        // Delete link, re-plant symlink
        getOutputPath("foo-link").delete()
        buildTarget("//:foo-link")

        assertSymlink("foo-link", targetPath.asFragment())

        // Assert that the symlink works after planting the target.
        if (targetType === SymlinkTargetType.FILE) {
            FileSystemUtils.writeContent(targetPath, java.nio.charset.StandardCharsets.UTF_8, "hello world")
            assertThat(FileSystemUtils.readContent(getOutputPath("foo-link"), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("hello world")
        } else if (targetType === SymlinkTargetType.DIRECTORY) {
            targetPath.createDirectory()
            FileSystemUtils.writeContent(
                targetPath.getChild("file.txt"),
                java.nio.charset.StandardCharsets.UTF_8,
                "hello world"
            )
            assertThat(
                FileSystemUtils.readContent(
                    getOutputPath("foo-link/file.txt"),
                    java.nio.charset.StandardCharsets.UTF_8
                )
            )
                .isEqualTo("hello world")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun treeOutputsFromLocalFileSystem_works(
        @TestParameter("no-remote-exec", "local") executionInfo: String?
    ) {
        // Test that tree artifact generated locally can be consumed by other actions.
        // See https://github.com/bazelbuild/bazel/issues/16789

        // Disable remote execution so tree outputs are generated locally

        addOptions("--modify_execution_info=OutputDir=+" + executionInfo)
        setDownloadToplevel()
        writeOutputDirRule()
        write(
            "BUILD",
            "load(':output_dir.bzl', 'output_dir')",
            "output_dir(",
            "  name = 'foo',",
            "  content_map = {'file-1': '1'},",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['out/foobar.txt'],",
            "  cmd = 'cat $(location :foo)/file-1 > $@ && echo bar >> $@',",
            ")"
        )

        buildTarget("//:foobar")
        waitDownloads()

        assertValidOutputFile("out/foobar.txt", "1bar\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyTreeConsumedByLocalAction() {
        // Disable remote execution so that the empty tree artifact is prefetched.
        addOptions("--modify_execution_info=Genrule=+no-remote-exec")
        addOptions("--verbose_failures")
        setDownloadToplevel()
        writeOutputDirRule()
        write(
            "BUILD",
            "load(':output_dir.bzl', 'output_dir')",
            "output_dir(",
            "  name = 'foo',",
            "  content_map = {},",  // no files
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['foobar.txt'],",
            "  cmd = 'touch $@',",
            ")"
        )

        buildTarget("//:foobar")
        waitDownloads()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multiplePackagePaths_buildsSuccessfully() {
        write(
            "../a/src/BUILD",
            """
        genrule(
            name = "foo",
            srcs = [],
            outs = ["out/foo.txt"],
            cmd = "echo foo > ${'$'}@",
        )
        
        """.trimIndent()
        )
        write(
            "BUILD",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = ['//src:foo'],",
            "  outs = ['out/foobar.txt'],",
            "  cmd = 'cat $(location //src:foo) > $@ && echo bar >> $@',",
            ")"
        )
        addOptions("--package_path=%workspace%:%workspace%/../a")
        setDownloadToplevel()

        buildTarget("//:foobar")
        waitDownloads()

        assertValidOutputFile("out/foobar.txt", "foo\nbar\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalBuild_unwritableParentDirectory_outputExists() {
        write(
            "BUILD",
            "genrule(",
            "  name = 'unwritable',",
            "  srcs = ['file.in'],",
            "  outs = ['unwritable/somefile.out'],",
            "  cmd = 'cat $(SRCS) > $@',",
            "  local = True,",
            ")"
        )
        write("file.in", "content")
        buildTarget("//:unwritable")

        getOutputPath("unwritable").setWritable(false)

        write("file.in", "updated content")
        buildTarget("//:unwritable")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalBuild_unwritableParentDirectory_outputDoesNotExist() {
        write(
            "BUILD",
            "genrule(",
            "  name = 'unwritable',",
            "  srcs = ['file.in'],",
            "  outs = ['unwritable/somefile.out'],",
            "  cmd = 'cat $(SRCS) > $@',",
            "  local = True,",
            ")"
        )
        write("file.in", "content")
        buildTarget("//:unwritable")

        getOutputPath("unwritable/somefile.out").delete()
        getOutputPath("unwritable").setWritable(false)

        write("file.in", "updated content")
        buildTarget("//:unwritable")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalBuild_treeArtifacts_correctlyProducesNewTree() {
        writeOutputDirRule()
        write(
            "BUILD",
            "load(':output_dir.bzl', 'output_dir')",
            "output_dir(",
            "  name = 'foo',",
            "  content_map = {'file-1': '1', 'file-2': '2', 'file-3': '3'},",
            ")"
        )
        setDownloadToplevel()
        buildTarget("//:foo")
        waitDownloads()

        write(
            "BUILD",
            "load(':output_dir.bzl', 'output_dir')",
            "output_dir(",
            "  name = 'foo',",
            "  content_map = {'file-1': '1', 'file-4': '4'},",
            ")"
        )
        restartServer()
        setDownloadToplevel()
        buildTarget("//:foo")
        waitDownloads()

        assertValidOutputFile("foo/file-1", "1")
        assertValidOutputFile("foo/file-4", "4")
        assertOutputDoesNotExist("foo/file-2")
        assertOutputDoesNotExist("foo/file-3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalBuild_restartServer_hitActionCache() {
        // Prepare workspace
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = [],",
            "  outs = ['out/foo.txt'],",
            "  cmd = 'echo foo > $@',",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['out/foobar.txt'],",
            "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
            ")"
        )
        var actionEventCollector = ActionEventCollector()
        runtimeWrapper.registerSubscriber(actionEventCollector)

        // Clean build
        buildTarget("//:foobar")

        // all action should be executed
        Truth.assertThat(actionEventCollector.getActionExecutedEvents()).hasSize(3)
        // no outputs are staged
        assertOutputsDoNotExist("//:foobar")

        restartServer()
        actionEventCollector = ActionEventCollector()
        runtimeWrapper.registerSubscriber(actionEventCollector)

        // Incremental build
        buildTarget("//:foobar")

        // all actions should hit the action cache.
        Truth.assertThat(actionEventCollector.getActionExecutedEvents()).isEmpty()
        // no outputs are staged
        assertOutputsDoNotExist("//:foobar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalBuild_sourceModified_rerunActions() {
        // Arrange: Prepare workspace and run a clean build
        write("foo.in", "foo")
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = ['foo.in'],",
            "  outs = ['out/foo.txt'],",
            "  cmd = 'cat $(SRCS) > $@',",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['out/foobar.txt'],",
            "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
            "  tags = ['no-remote'],",
            ")"
        )

        buildTarget("//:foobar")
        assertValidOutputFile("out/foo.txt", "foo\n")
        assertValidOutputFile("out/foobar.txt", "foo\nbar\n")

        // Act: Modify source file and run an incremental build
        write("foo.in", "modified")

        val actionEventCollector = ActionEventCollector()
        runtimeWrapper.registerSubscriber(actionEventCollector)
        buildTarget("//:foobar")

        // Assert: All actions transitively depend on the source file are re-executed and outputs are
        // correct.
        assertValidOutputFile("out/foo.txt", "modified\n")
        assertValidOutputFile("out/foobar.txt", "modified\nbar\n")
        Truth.assertThat(actionEventCollector.numActionNodesEvaluated).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalBuild_intermediateOutputDeleted_nothingIsReEvaluated() {
        // Arrange: Prepare workspace and run a clean build
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = [],",
            "  outs = ['out/foo.txt'],",
            "  cmd = 'echo foo > $@',",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['out/foobar.txt'],",
            "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
            "  tags = ['no-remote'],",
            ")"
        )

        buildTarget("//:foobar")
        assertValidOutputFile("out/foo.txt", "foo\n")
        assertValidOutputFile("out/foobar.txt", "foo\nbar\n")

        // Act: Delete intermediate output and run an incremental build
        val fooPath: Path = getOutputPath("out/foo.txt")
        fooPath.delete()

        val actionEventCollector = ActionEventCollector()
        runtimeWrapper.registerSubscriber(actionEventCollector)
        buildTarget("//:foobar")

        // Assert: local output is deleted, skyframe should trust remote files so no nodes will be
        // re-evaluated.
        assertOutputDoesNotExist("out/foo.txt")
        assertValidOutputFile("out/foobar.txt", "foo\nbar\n")
        Truth.assertThat(actionEventCollector.numActionNodesEvaluated).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalBuild_fileOutputIsPrefetched_noRuns() {
        // We need to download the intermediate output
        if (!hasAccessToRemoteOutputs()) {
            return
        }

        // Arrange: Prepare workspace and run a clean build
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = [],",
            "  outs = ['out/foo.txt'],",
            "  cmd = 'echo foo > $@',",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['out/foobar.txt'],",
            "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
            "  tags = ['no-remote'],",
            ")"
        )

        buildTarget("//:foobar")
        assertValidOutputFile("out/foo.txt", "foo\n")
        assertValidOutputFile("out/foobar.txt", "foo\nbar\n")
        assertThat(
            com.google.common.collect.Iterables.getOnlyElement<FileArtifactValue?>(getMetadata("//:foo").values)
                .isRemote()
        ).isTrue()

        // Act: Do an incremental build without any modifications
        val actionEventCollector = ActionEventCollector()
        runtimeWrapper.registerSubscriber(actionEventCollector)
        buildTarget("//:foobar")

        // Assert: remote file metadata has contents proxy and action node is not marked as dirty.
        assertValidOutputFile("out/foo.txt", "foo\n")
        assertValidOutputFile("out/foobar.txt", "foo\nbar\n")
        Truth.assertThat(actionEventCollector.getActionExecutedEvents()).isEmpty()
        Truth.assertThat(actionEventCollector.getCachedActionEvents()).isEmpty()
        val metadata: FileArtifactValue? =
            com.google.common.collect.Iterables.getOnlyElement<FileArtifactValue?>(getMetadata("//:foo").values)
        assertThat(metadata.isRemote()).isTrue()
        assertThat(metadata.getContentsProxy()).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalBuild_treeOutputIsPrefetched_noRuns() {
        // We need to download the intermediate output
        if (!hasAccessToRemoteOutputs()) {
            return
        }

        // Arrange: Prepare workspace and run a clean build
        writeOutputDirRule()
        write(
            "BUILD",
            "load(':output_dir.bzl', 'output_dir')",
            "output_dir(",
            "  name = 'foo',",
            "  content_map = {'file-1': '1', 'file-2': '2', 'file-3': '3'},",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['out/foobar.txt'],",
            "  cmd = 'echo bar >> $@',",
            "  tags = ['no-remote'],",
            ")"
        )

        buildTarget("//:foobar")
        assertValidOutputFile("foo/file-1", "1")
        assertValidOutputFile("foo/file-2", "2")
        assertValidOutputFile("foo/file-3", "3")
        assertValidOutputFile("out/foobar.txt", "bar\n")
        assertThat(
            com.google.common.collect.Iterables.getOnlyElement<TreeArtifactValue?>(getTreeMetadata("//:foo").values)
                .isEntirelyRemote()
        ).isTrue()

        // Act: Do an incremental build without any modifications
        val actionEventCollector = ActionEventCollector()
        runtimeWrapper.registerSubscriber(actionEventCollector)
        buildTarget("//:foobar")

        // Assert: action node is not marked as dirty.
        assertValidOutputFile("foo/file-1", "1")
        assertValidOutputFile("foo/file-2", "2")
        assertValidOutputFile("foo/file-3", "3")
        Truth.assertThat(actionEventCollector.getActionExecutedEvents()).isEmpty()
        Truth.assertThat(actionEventCollector.getCachedActionEvents()).isEmpty()
    }

    @Throws(java.lang.Exception::class)
    protected fun getMetadata(target: String?): com.google.common.collect.ImmutableMap<Artifact?, FileArtifactValue?> {
        val result: com.google.common.collect.ImmutableMap.Builder<Artifact?, FileArtifactValue?> =
            com.google.common.collect.ImmutableMap.builder<Artifact?, FileArtifactValue?>()
        val evaluator: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            runtimeWrapper.skyframeExecutor.getEvaluator()
        for (artifact: @NotNull Artifact in getArtifacts(target)) {
            val value: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                evaluator.getExistingValue(Artifact.key(artifact))
            if (value is ActionExecutionValue) {
                result.putAll(value.allFileValues)
            } else if (value is TreeArtifactValue) {
                result.putAll(value.getChildValues())
            }
        }
        return result.buildOrThrow()
    }

    @Throws(java.lang.Exception::class)
    protected fun getTreeMetadata(target: String?): com.google.common.collect.ImmutableMap<Artifact?, TreeArtifactValue?> {
        val result: com.google.common.collect.ImmutableMap.Builder<Artifact?, TreeArtifactValue?> =
            com.google.common.collect.ImmutableMap.builder<Artifact?, TreeArtifactValue?>()
        val evaluator: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            runtimeWrapper.skyframeExecutor.getEvaluator()
        for (artifact: @NotNull Artifact in getArtifacts(target)) {
            val value: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                evaluator.getExistingValue(Artifact.key(artifact))
            if (value is ActionExecutionValue) {
                result.putAll(value.getAllTreeArtifactValues())
            } else if (value is TreeArtifactValue) {
                result.put(artifact, value)
            }
        }
        return result.buildOrThrow()
    }

    @Throws(java.lang.Exception::class)
    protected fun getMetadata(output: Artifact?): FileArtifactValue? {
        val evaluator: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            runtimeWrapper.skyframeExecutor.getEvaluator()
        val value: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            evaluator.getExistingValue(Artifact.key(output))
        if (value is ActionExecutionValue) {
            return value.allFileValues.get(output)
        } else if (value is TreeArtifactValue) {
            return value.getChildValues().get(output)
        }
        return null
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalBuild_intermediateOutputModified_rerunGeneratingActions() {
        // Arrange: Prepare workspace and run a clean build
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = [],",
            "  outs = ['out/foo.txt'],",
            "  cmd = 'echo foo > $@',",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo'],",
            "  outs = ['out/foobar.txt'],",
            "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
            "  tags = ['no-remote'],",
            ")"
        )

        buildTarget("//:foobar")
        assertValidOutputFile("out/foo.txt", "foo\n")
        assertValidOutputFile("out/foobar.txt", "foo\nbar\n")

        // Act: Modify the intermediate output and run a incremental build
        val fooPath: Path = getOutputPath("out/foo.txt")
        fooPath.delete()
        writeAbsolute(fooPath, "modified")

        val actionEventCollector = ActionEventCollector()
        runtimeWrapper.registerSubscriber(actionEventCollector)
        buildTarget("//:foobar")

        // Assert: the stale intermediate file should be deleted by skyframe before executing the
        // generating action. Since download minimal, the output didn't get downloaded. Since the input
        // to action :foobar didn't change, we hit the skyframe cache, so the action node didn't event
        // get evaluated. The input didn't get prefetched neither.
        assertOutputDoesNotExist("out/foo.txt")
        assertValidOutputFile("out/foobar.txt", "foo\nbar\n")
        Truth.assertThat(actionEventCollector.getActionExecutedEvents()).hasSize(1)
        Truth.assertThat(actionEventCollector.getCachedActionEvents()).isEmpty()
        val executedAction: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            actionEventCollector.getActionExecutedEvents().get(0).getAction()
        assertThat(executedAction.getPrimaryOutput().getFilename()).isEqualTo("foo.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remoteCacheEvictBlobs_whenPrefetchingInputFile_incrementalBuildCanContinue() {
        // Arrange: Prepare workspace and populate remote cache
        write(
            "a/BUILD",
            """
        genrule(
            name = "foo",
            srcs = ["foo.in"],
            outs = ["foo.out"],
            cmd = "cat ${'$'}(SRCS) > ${'$'}@",
        )

        genrule(
            name = "bar",
            srcs = [
                "foo.out",
                "bar.in",
            ],
            outs = ["bar.out"],
            cmd = "cat ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        write("a/foo.in", "foo")
        write("a/bar.in", "bar")

        // Populate remote cache
        buildTarget("//a:bar")
        getOutputPath("a/foo.out").delete()
        getOutputPath("a/bar.out").delete()
        getOutputBase().getRelative("action_cache").deleteTreesBelow()
        restartServer()

        // Clean build, foo.out isn't downloaded
        buildTarget("//a:bar")
        assertOutputDoesNotExist("a/foo.out")

        // Evict blobs from remote cache
        evictAllBlobs()

        // trigger build error
        write("a/bar.in", "updated bar")
        addOptions("--strategy_regexp=.*bar=local")
        // Build failed because of remote cache eviction
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//a:bar") })

        // Act: Do an incremental build without "clean" or "shutdown"
        buildTarget("//a:bar")

        // Assert: target was successfully built
        assertValidOutputFile("a/bar.out", "foo\nupdated bar\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remoteCacheEvictBlobs_whenPrefetchingInputTree_incrementalBuildCanContinue() {
        // Arrange: Prepare workspace and populate remote cache
        write("BUILD")
        writeOutputDirRule()
        write(
            "a/BUILD",
            """
        load("//:output_dir.bzl", "output_dir")

        output_dir(
            name = "foo.out",
            content_map = {"file-inside": "hello world"},
        )

        genrule(
            name = "bar",
            srcs = [
                "foo.out",
                "bar.in",
            ],
            outs = ["bar.out"],
            cmd = "( ls ${'$'}(location :foo.out); cat ${'$'}(location :bar.in) ) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        write("a/bar.in", "bar")

        // Populate remote cache
        buildTarget("//a:bar")
        getOutputPath("a/foo.out").deleteTreesBelow()
        getOutputPath("a/bar.out").delete()
        getOutputBase().getRelative("action_cache").deleteTreesBelow()
        restartServer()

        // Clean build, foo.out isn't downloaded
        buildTarget("//a:bar")
        assertOutputDoesNotExist("a/foo.out/file-inside")

        // Evict blobs from remote cache
        evictAllBlobs()

        // trigger build error
        write("a/bar.in", "updated bar")
        addOptions("--strategy_regexp=.*bar=local")
        // Build failed because of remote cache eviction
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//a:bar") })

        // Act: Do an incremental build without "clean" or "shutdown"
        buildTarget("//a:bar")

        // Assert: target was successfully built
        assertValidOutputFile("a/bar.out", "file-inside\nupdated bar\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonDeclaredSymlinksFromLocalActions() {
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = [],",
            "  outs = ['foo.txt'],",
            "  cmd = 'echo foo > $@',",
            ")",
            "genrule(",
            "  name = 'foo-link',",
            "  srcs = [':foo'],",
            "  outs = ['foo.link'],",
            "  cmd = 'ln -s foo.txt $@',",
            "  local = True,",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo-link'],",
            "  outs = ['foobar.txt'],",
            "  cmd = 'cat $(location :foo-link) > $@ && echo bar >> $@',",
            "  local = True,",
            ")"
        )

        buildTarget("//:foobar")

        assertValidOutputFile("foobar.txt", "foo\nbar\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun skymeldPromoIntermediateTargetToToplevel_outputFile_downloadFile() {
        // Regression test for https://github.com/bazelbuild/bazel/issues/20737.

        // Disable on Windows since mkfifo doesn't work there.

        Assume.assumeFalse(com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS)
        write(
            "BUILD",
            """
        filegroup(name = "top", srcs = [":actual", "//slow"])
        genrule(name = "proxy", srcs = [":actual"], outs = ["proxy_file"], cmd = "cp ${'$'}< ${'$'}@")
        genrule(name = "actual", srcs = [], outs = ["actual_file"], cmd = "echo ACTUAL > ${'$'}@")
        
        """.trimIndent()
        )

        getWorkspace().getRelative("slow").createDirectoryAndParents()
        // Only write the content of slow/BUILD after //:proxy is built, so we can artificially delay
        // the analysis of //:top
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CommandBuilder(java.lang.System.getenv())
                .addArgs("mkfifo", "slow/BUILD")
                .setWorkingDir(getWorkspace())
                .build()
                .execute()

        buildTarget("//:proxy")
        restartServer()

        runtimeWrapper
            .registerSubscriber(
                object : Any() {
                    @com.google.common.eventbus.Subscribe
                    fun onTargetCompleted(event: TargetCompleteEvent) {
                        if (event.getLabel().toString().equals("//:proxy")) {
                            try {
                                write(
                                    "slow/BUILD",
                                    "filegroup(name = 'slow', visibility = ['//visibility:public'])"
                                )
                            } catch (e: IOException) {
                                throw java.lang.IllegalStateException(e)
                            }
                        }
                    }
                })
        setDownloadToplevel()
        buildTarget("//:top", "//:proxy")
        waitDownloads()

        assertValidOutputFile("actual_file", "ACTUAL\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun skymeldPromoIntermediateTargetToToplevel_outputDirectory_downloadDirectory() {
        // Regression test for https://github.com/bazelbuild/bazel/issues/20737.

        // Disable on Windows since mkfifo doesn't work there.

        Assume.assumeFalse(com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS)
        writeOutputDirRule()
        write(
            "BUILD",
            """
        load(':output_dir.bzl', 'output_dir')
        filegroup(name = "top", srcs = [":actual", "//slow"])
        genrule(name = "proxy", srcs = [":actual"], outs = ["proxy_file"], cmd = "cp ${'$'}</file-1 ${'$'}@")
        output_dir(
          name = "actual",
          content_map = {'file-1': '1', 'file-2': '2', 'file-3': '3'}
        )
        
        """.trimIndent()
        )

        getWorkspace().getRelative("slow").createDirectoryAndParents()
        // Only write the content of slow/BUILD after //:proxy is built, so we can artificially delay
        // the analysis of //:top
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CommandBuilder(java.lang.System.getenv())
                .addArgs("mkfifo", "slow/BUILD")
                .setWorkingDir(getWorkspace())
                .build()
                .execute()

        buildTarget("//:proxy")
        restartServer()

        runtimeWrapper
            .registerSubscriber(
                object : Any() {
                    @com.google.common.eventbus.Subscribe
                    fun onTargetCompleted(event: TargetCompleteEvent) {
                        if (event.getLabel().toString().equals("//:proxy")) {
                            try {
                                write(
                                    "slow/BUILD",
                                    "filegroup(name = 'slow', visibility = ['//visibility:public'])"
                                )
                            } catch (e: IOException) {
                                throw java.lang.IllegalStateException(e)
                            }
                        }
                    }
                })
        setDownloadToplevel()
        buildTarget("//:top", "//:proxy")
        waitDownloads()

        assertValidOutputFile("actual/file-1", "1")
        assertValidOutputFile("actual/file-2", "2")
        assertValidOutputFile("actual/file-3", "3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShareableActionOutputsAsInputs() {
        write(
            "defs.bzl",
            """
        def _generate_shared_file(ctx):
            output = ctx.actions.declare_file("shared.txt")
            ctx.actions.run_shell(
                outputs = [output],
                command = "echo -n 'shared content' > %s" % output.path,
            )
            return [DefaultInfo(files=depset([output]))]
        generate_shared_file = rule(_generate_shared_file)
        
        """.trimIndent()
        )
        write(
            "BUILD",
            """
        load(":defs.bzl", "generate_shared_file")
        generate_shared_file(name = "gen1")
        generate_shared_file(name = "gen2")
        genrule(
            name = "consume_outputs",
            srcs = [":gen1", ":gen2"],
            outs = ["combined_output.txt"],
            cmd = "cat ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )

        buildTarget("//:consume_outputs")

        assertOnlyOutputRemoteContent(
            "//:consume_outputs", "combined_output.txt", "shared contentshared content"
        )
        assertOnlyOutputRemoteContent("//:gen1", "shared.txt", "shared content")
        assertOnlyOutputRemoteContent("//:gen2", "shared.txt", "shared content")
    }

    @Throws(java.lang.Exception::class)
    protected fun assertOutputsDoNotExist(target: String?) {
        for (output in getArtifacts(target)) {
            Truth.assertWithMessage(
                "output %s for target %s should not exist", output.getExecPathString(), target
            )
                .that(output.getPath().exists())
                .isFalse()
        }
    }

    protected fun getSourcePath(relativePath: String?): Path {
        return getDirectories().getWorkspace().getRelative(relativePath)
    }

    protected fun getOutputPath(binRelativePath: String?): Path {
        return targetConfiguration.getBinDir().getRoot().getRelative(binRelativePath)
    }

    protected fun assertOutputDoesNotExist(binRelativePath: String?) {
        val output: Path = getOutputPath(binRelativePath)
        assertThat(output.exists()).isFalse()
    }

    @Throws(java.lang.Exception::class)
    protected fun assertOnlyOutputContent(target: String?, filename: String?, content: String?) {
        val output: Artifact = com.google.common.collect.Iterables.getOnlyElement<Artifact>(getArtifacts(target))
        assertThat(output.getFilename()).isEqualTo(filename)
        assertThat(output.getPath().exists()).isTrue()
        assertOutputEquals(output.getPath(), content)
    }

    @Throws(java.lang.Exception::class)
    protected fun assertOnlyOutputRemoteContent(target: String?, filename: String?, content: String) {
        val output: Artifact = com.google.common.collect.Iterables.getOnlyElement<Artifact>(getArtifacts(target))
        assertThat(output.getFilename()).isEqualTo(filename)
        assertThat(output.getPath().exists()).isFalse()
        val metadata: FileArtifactValue? =
            com.google.common.collect.Iterables.getOnlyElement<FileArtifactValue?>(getMetadata(target).values)
        assertThat(metadata.isRemote()).isTrue()
        assertThat(metadata.getSize()).isEqualTo(content.length)
        assertThat(metadata.getDigest())
            .isEqualTo(
                digestHashFunction.getHashFunction().hashString(content, java.nio.charset.StandardCharsets.UTF_8)
                    .asBytes()
            )
    }

    @Throws(java.lang.Exception::class)
    protected fun assertValidOutputFile(binRelativePath: String?, content: String?) {
        val output: Path = getOutputPath(binRelativePath)
        assertOutputEquals(getOutputPath(binRelativePath), content)
        assertThat(output.isReadable()).isTrue()
        assertThat(output.isWritable()).isFalse()
        assertThat(output.isExecutable()).isTrue()
    }

    @Throws(java.lang.Exception::class)
    protected fun assertSymlink(binRelativeLinkPath: String?, targetPath: PathFragment) {
        // On Windows, readSymbolicLink() always returns an absolute path.
        var targetPath: PathFragment = targetPath
        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS && !targetPath.isAbsolute()) {
            targetPath =
                getOutputPath(binRelativeLinkPath)
                    .getParentDirectory()
                    .getRelative(targetPath)
                    .asFragment()
        }
        val output: Path = getOutputPath(binRelativeLinkPath)
        assertThat(output.isSymbolicLink()).isTrue()
        assertThat(output.readSymbolicLink()).isEqualTo(targetPath)
    }

    @Throws(IOException::class)
    protected fun writeSymlinkRule() {
        FileSystemUtils.touchFile(getWorkspace().getRelative("BUILD"))
        write(
            "symlink.bzl",
            """
        def _symlink_impl(ctx):
            if ctx.file.target_artifact and not ctx.attr.target_path:
                if ctx.file.target_artifact.is_directory:
                    link = ctx.actions.declare_directory(ctx.attr.name)
                else:
                    link = ctx.actions.declare_file(ctx.attr.name)
                ctx.actions.symlink(output = link, target_file = ctx.file.target_artifact)
            elif ctx.attr.target_path and not ctx.file.target_artifact:
                link = ctx.actions.declare_symlink(ctx.attr.name)
                ctx.actions.symlink(
                    output = link,
                    target_path = ctx.attr.target_path,
                    target_type = ctx.attr.target_type or None,
                )
            else:
                fail("exactly one of target_artifact or target_path must be set")

            return DefaultInfo(files = depset([link]))

        symlink = rule(
            implementation = _symlink_impl,
            attrs = {
                "target_artifact": attr.label(allow_single_file = True),
                "target_path": attr.string(),
                "target_type": attr.string(),
            },
        )
        
        """.trimIndent()
        )
    }

    @Throws(IOException::class)
    protected fun writeOutputDirRule() {
        write(
            "output_dir.bzl",
            """
        def _output_dir_impl(ctx):
            out = ctx.actions.declare_directory(ctx.attr.name)
            args = []
            for name, content in ctx.attr.content_map.items():
                args.append(out.path + "/" + name)
                args.append(content)
            ctx.actions.run_shell(
                mnemonic = "OutputDir",
                outputs = [out],
                arguments = args,
                command = 'while ((${'$'}#)); do echo -n "${'$'}2" > ${'$'}1; shift 2; done',
            )
            return DefaultInfo(files = depset([out]))

        output_dir = rule(
            implementation = _output_dir_impl,
            attrs = {
                "content_map": attr.string_dict(mandatory = True),
            },
        )
        
        """.trimIndent()
        )
    }

    @Throws(IOException::class)
    protected fun writeCopyAspectRule(aggregate: Boolean) {
        val lines: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        lines.add(
            "def _copy_aspect_impl(target, ctx):",
            "  files = []",
            "  for src in ctx.rule.files.srcs:",
            "    dst = ctx.actions.declare_file(src.basename + '.copy')",
            "    ctx.actions.run_shell(",
            "      inputs = [src],",
            "      outputs = [dst],",
            "      command = '''",
            "cp $1 $2",
            "''',",
            "      arguments = [src.path, dst.path],",
            "    )",
            "    files.append(dst)",
            ""
        )
        if (aggregate) {
            lines.add(
                "  files = depset(",
                "    direct = files,",
                "    transitive = [src[OutputGroupInfo].copy for src in ctx.rule.attr.srcs if"
                        + " OutputGroupInfo in src],",
                "  )"
            )
        } else {
            lines.add("  files = depset(files)")
        }
        lines.add(
            "",
            "  return [OutputGroupInfo(copy = files)]",
            "",
            "copy_aspect = aspect(",
            "  implementation = _copy_aspect_impl,",
            "  attr_aspects = ['srcs'],",
            ")"
        )
        write("rules.bzl", *lines.build().toTypedArray<String?>())
    }

    protected class ActionEventCollector {
        private val actionExecutedEvents: MutableList<ActionExecutedEvent?> =
            java.util.ArrayList<ActionExecutedEvent?>()
        private val cachedActionEvents: MutableList<CachedActionEvent?> = java.util.ArrayList<CachedActionEvent?>()

        @com.google.common.eventbus.Subscribe
        fun onActionExecuted(event: ActionExecutedEvent?) {
            actionExecutedEvents.add(event)
        }

        @com.google.common.eventbus.Subscribe
        fun onCachedAction(event: CachedActionEvent?) {
            cachedActionEvents.add(event)
        }

        val numActionNodesEvaluated: Int
            get() = getActionExecutedEvents().size + getCachedActionEvents().size

        fun clear() {
            this.actionExecutedEvents.clear()
            this.cachedActionEvents.clear()
        }

        fun getActionExecutedEvents(): MutableList<ActionExecutedEvent?> {
            return actionExecutedEvents
        }

        fun getCachedActionEvents(): MutableList<CachedActionEvent?> {
            return cachedActionEvents
        }
    }

    @Throws(java.lang.Exception::class)
    protected fun restartServer() {
        // Simulates a server restart
        createRuntimeWrapper()
    }
}
