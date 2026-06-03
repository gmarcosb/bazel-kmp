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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.actions.BuildFailedException

/** Tests for Starlark "unused inputs list" functionality on failures caused by unused inputs.  */
@RunWith(TestParameterInjector::class)
class UnusedInputsFailureIntegrationTest : BuildIntegrationTestCase() {
    @TestParameter
    private val keepGoing = false

    @Before
    fun setOptions() {
        addOptions("--keep_going=" + keepGoing)
    }

    private fun listenForTargetCompleteEvents(): MutableList<TargetCompleteEvent?> {
        val events: MutableList<TargetCompleteEvent?> = java.util.ArrayList<TargetCompleteEvent?>()
        runtimeWrapper.registerSubscriber(
            object : Any() {
                @com.google.common.eventbus.Subscribe
                @com.google.errorprone.annotations.Keep
                fun targetComplete(event: TargetCompleteEvent?) {
                    events.add(event)
                }
            })
        return events
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalFailureOnUnusedInput() {
        val bugReporter: RecordingBugReporter = recordBugReportsAndReinitialize()
        write(
            "foo/pruning.bzl",
            """
        def _impl(ctx):
            inputs = ctx.attr.inputs.files
            output = ctx.actions.declare_file(ctx.label.name + ".out")
            unused_file = ctx.actions.declare_file(ctx.label.name + ".unused")
            ctx.actions.run(
                # Make sure original inputs are one level down,
                # so 'leaf unrolling' doesn't get them
                inputs = depset(transitive = [ctx.attr.filler.files, inputs]),
                outputs = [output, unused_file],
                arguments = [output.path, unused_file.path] + [f.path for f in inputs.to_list()],
                executable = ctx.executable.executable,
                unused_inputs_list = unused_file,
            )
            return DefaultInfo(files = depset([output]))

        build_rule = rule(
            attrs = {
                "inputs": attr.label(allow_files = True),
                "filler": attr.label(allow_files = True),
                "executable": attr.label(executable = True, allow_files = True, cfg = "exec"),
            },
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        write("foo/unused.sh", "touch $1", "shift", "unused=$1", "shift", "echo $@ > \$unused")
            .setExecutable(true)
        write("foo/gen_run.sh", "true").setExecutable(true)
        write("foo/filler")
        write(
            "foo/BUILD",
            """
        load("//foo:pruning.bzl", "build_rule")

        build_rule(
            name = "foo",
            executable = ":unused.sh",
            filler = ":filler",
            inputs = ":in",
        )

        genrule(
            name = "gen",
            outs = ["in"],
            cmd = "${'$'}(location :gen_run.sh) && touch ${'$'}@",
            tools = [":gen_run.sh"],
        )
        
        """.trimIndent()
        )

        buildTarget("//foo:foo")
        bugReporter.assertNoExceptions()

        write("foo/gen_run.sh", "false")

        val targetCompleteEvents: MutableList<TargetCompleteEvent?> = listenForTargetCompleteEvents()
        if (keepGoing) {
            buildTarget("//foo:foo")
            bugReporter.assertNoExceptions()

            val targetCompleteEvent: TargetCompleteEvent? =
                com.google.common.collect.Iterables.getOnlyElement<TargetCompleteEvent?>(targetCompleteEvents)
            Truth.assertThat(getAllReportedArtifacts(targetCompleteEvent)).containsExactly("foo/foo.out")
            Truth.assertThat(getRootCauseLabels(targetCompleteEvent)).isEmpty()
        } else {
            val outErr: RecordingOutErr = RecordingOutErr()
            this.outErr = outErr
            val e: BuildFailedException = org.junit.Assert.assertThrows<T>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo") })
            assertThat(e.getDetailedExitCode().getFailureDetail())
                .comparingExpectedFieldsOnly()
                .isEqualTo(
                    FailureDetails.FailureDetail.newBuilder()
                        .setExecution(
                            FailureDetails.Execution.newBuilder()
                                .setCode(FailureDetails.Execution.Code.UNEXPECTED_EXCEPTION)
                                .build()
                        )
                        .build()
                )
            com.google.common.truth.Subject.contains("Executing genrule //foo:gen failed")
            val cause: Throwable = bugReporter.getFirstCause()
            Truth.assertThat(cause).hasMessageThat().contains("Error evaluating artifact nested set")
            Truth.assertThat(cause).hasMessageThat().contains("foo/gen_run.sh")

            // TODO: b/414856090 - There should be a failed TargetCompleteEvent posted.
            Truth.assertThat(targetCompleteEvents).isEmpty()
        }
    }

    /**
     * Regression test for b/218911068.
     * 
     * 
     * Doesn't reproduce the exact crash since that requires BEP infrastructure to be set up, but
     * asserts that the [TargetCompleteEvent] does not report the fileset artifact in the broken
     * build.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalFailureOnUnusedInput_topLevelFileset() {
        TruthJUnit.assume().that(AnalysisMock.get().isThisBazel()).isFalse() // No Filesets in bazel.
        write(
            "foo/pruning.bzl",
            """
        def _impl(ctx):
            inputs = ctx.attr.inputs.files
            output = ctx.actions.declare_file(ctx.label.name + ".out")
            unused_file = ctx.actions.declare_file(ctx.label.name + ".unused")
            ctx.actions.run(
                # Make sure original inputs are one level down,
                # so 'leaf unrolling' doesn't get them
                inputs = depset(transitive = [ctx.attr.filler.files, inputs]),
                outputs = [output, unused_file],
                arguments = [output.path, unused_file.path] + [f.path for f in inputs.to_list()],
                executable = ctx.executable.executable,
                unused_inputs_list = unused_file,
            )
            return DefaultInfo(files = depset([output]))

        build_rule = rule(
            attrs = {
                "inputs": attr.label(allow_files = True),
                "filler": attr.label(allow_files = True),
                "executable": attr.label(executable = True, allow_files = True, cfg = "exec"),
            },
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        write("foo/unused.sh", "touch $1", "shift", "unused=$1", "shift", "echo $@ > \$unused")
            .setExecutable(true)
        write("foo/gen_run.sh", "true").setExecutable(true)
        write("foo/filler")
        write(
            "foo/BUILD",
            """
        load("//foo:pruning.bzl", "build_rule")

        Fileset(name = "fs", entries = [FilesetEntry(files = [":foo"])])

        build_rule(
            name = "foo",
            executable = ":unused.sh",
            filler = ":filler",
            inputs = ":in",
        )

        genrule(
            name = "gen",
            outs = ["in"],
            cmd = "${'$'}(location :gen_run.sh) && touch ${'$'}@",
            tools = [":gen_run.sh"],
        )
        
        """.trimIndent()
        )

        buildTarget("//foo:fs")

        write("foo/gen_run.sh", "false")

        val targetCompleteEvents: MutableList<TargetCompleteEvent?> = listenForTargetCompleteEvents()
        if (keepGoing) {
            buildTarget("//foo:fs")

            val targetCompleteEvent: TargetCompleteEvent? =
                com.google.common.collect.Iterables.getOnlyElement<TargetCompleteEvent?>(targetCompleteEvents)
            Truth.assertThat(getAllReportedArtifacts(targetCompleteEvent)).containsExactly("foo/fs")
            Truth.assertThat(getRootCauseLabels(targetCompleteEvent)).isEmpty()
        } else {
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:fs") })
            assertContainsError("Executing genrule //foo:gen failed")

            val targetCompleteEvent: TargetCompleteEvent? =
                com.google.common.collect.Iterables.getOnlyElement<TargetCompleteEvent?>(targetCompleteEvents)
            Truth.assertThat(getAllReportedArtifacts(targetCompleteEvent)).isEmpty()
            Truth.assertThat(getRootCauseLabels(targetCompleteEvent)).containsExactly("//foo:gen")
        }
    }

    /**
     * Regression test for b/185998331.
     * 
     * 
     * The action graph is:
     * 
     * <pre>
     * top [consume.out] -> [top.out]
     * |
     * consume [consume.sh, prune.out] -> [consume.out]
     * |
     * prune [prune.sh, [bad.out, good.out]] -> [prune.out, unused_list]
     * /                \
     * bad [bad.sh] -> [bad.out]     good [] -> [good.out]
    </pre> * 
     * 
     * where 'prune' reports 'bad' as an unused input. On the first build, 'consume' fails. On the
     * second build, 'bad' fails. If the error is not handled correctly by 'prune', 'top' won't know
     * that 'consume' is unavailable.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalFailureOnUnusedInput_downstreamInputNotReady() {
        write(
            "foo/defs.bzl",
            """
        def _example_rule_impl(ctx):
            bad = ctx.actions.declare_file("bad.out")
            ctx.actions.run(
                outputs = [bad],
                executable = ctx.executable.bad_sh,
                arguments = [bad.path],
            )

            good = ctx.actions.declare_file("good.out")
            ctx.actions.run_shell(outputs = [good], command = "touch %s" % good.path)

            unused_list = ctx.actions.declare_file("unused_list")
            prune = ctx.actions.declare_file("prune.out")
            ctx.actions.run(
                outputs = [prune, unused_list],
                inputs = [bad, good],
                unused_inputs_list = unused_list,
                executable = ctx.executable.prune_sh,
                arguments = [prune.path, unused_list.path, bad.path],
            )

            consume = ctx.actions.declare_file("consume.out")
            ctx.actions.run(
                outputs = [consume],
                inputs = [prune],
                executable = ctx.executable.consume_sh,
                arguments = [consume.path],
            )

            top = ctx.actions.declare_file("top.out")
            ctx.actions.run_shell(
                outputs = [top],
                inputs = [consume],
                command = "touch %s" % top.path,
            )
            return DefaultInfo(files = depset([top]))

        example_rule = rule(
            implementation = _example_rule_impl,
            attrs = {
                "bad_sh": attr.label(
                    executable = True,
                    allow_single_file = True,
                    cfg = "exec",
                    default = "bad.sh",
                ),
                "prune_sh": attr.label(
                    executable = True,
                    allow_single_file = True,
                    cfg = "exec",
                    default = "prune.sh",
                ),
                "consume_sh": attr.label(
                    executable = True,
                    allow_single_file = True,
                    cfg = "exec",
                    default = "consume.sh",
                ),
            },
        )
        
        """.trimIndent()
        )
        write(
            "foo/BUILD",
            """
        load(":defs.bzl", "example_rule")

        example_rule(name = "example")
        
        """.trimIndent()
        )
        write("foo/bad.sh", "#!/bin/bash", "touch $1").setExecutable(true)
        write("foo/prune.sh", "#!/bin/bash", "touch $1 && echo $3 > $2").setExecutable(true)
        write("foo/consume.sh", "#!/bin/bash", "exit 1").setExecutable(true)

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:example") })
        assertContainsError("Action foo/consume.out failed")

        write("foo/bad.sh", "#!/bin/bash", "exit 1").setExecutable(true)
        write("foo/consume.sh", "#!/bin/bash", "touch $@").setExecutable(true)

        val targetCompleteEvents: MutableList<TargetCompleteEvent?> = listenForTargetCompleteEvents()
        if (keepGoing) {
            buildTarget("//foo:example")

            val targetCompleteEvent: TargetCompleteEvent? =
                com.google.common.collect.Iterables.getOnlyElement<TargetCompleteEvent?>(targetCompleteEvents)
            Truth.assertThat(getAllReportedArtifacts(targetCompleteEvent)).containsExactly("foo/top.out")
            Truth.assertThat(getRootCauseLabels(targetCompleteEvent)).isEmpty()
        } else {
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:example") })
            assertContainsError("Action foo/bad.out failed")

            val targetCompleteEvent: TargetCompleteEvent? =
                com.google.common.collect.Iterables.getOnlyElement<TargetCompleteEvent?>(targetCompleteEvents)
            Truth.assertThat(getAllReportedArtifacts(targetCompleteEvent)).isEmpty()
            Truth.assertThat(getRootCauseLabels(targetCompleteEvent)).containsExactly("//foo:example")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalUnusedSymlinkCycle() {
        val bugReporter: RecordingBugReporter = recordBugReportsAndReinitialize()
        write(
            "foo/pruning.bzl",
            """
        def _impl(ctx):
            inputs = ctx.attr.inputs.files
            output = ctx.actions.declare_file(ctx.label.name + ".out")
            unused_inputs_list = ctx.actions.declare_file(ctx.label.name + ".unused")
            arguments = [output.path, unused_inputs_list.path]
            for input in inputs.to_list():
                arguments += [input.path]
            ctx.actions.run(
                inputs = inputs,
                outputs = [output, unused_inputs_list],
                arguments = arguments,
                executable = ctx.executable.executable,
                unused_inputs_list = unused_inputs_list,
            )
            return DefaultInfo(files = depset([output]))

        build_rule = rule(
            attrs = {
                "inputs": attr.label(allow_files = True),
                "executable": attr.label(executable = True, allow_files = True, cfg = "exec"),
            },
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        val unusedSh: Path =
            write("foo/all_unused.sh", "touch $1", "shift", "unused=$1", "shift", "echo $@ > \$unused")
        unusedSh.setExecutable(true)
        val inPath: Path = write("foo/in")
        write(
            "foo/BUILD",
            """
        load("//foo:pruning.bzl", "build_rule")

        build_rule(
            name = "prune",
            executable = ":all_unused.sh",
            inputs = ":in",
        )
        
        """.trimIndent()
        )

        buildTarget("//foo:prune")
        bugReporter.assertNoExceptions()

        inPath.delete()
        inPath.createSymbolicLink(PathFragment.create("in"))

        val targetCompleteEvents: MutableList<TargetCompleteEvent?> = listenForTargetCompleteEvents()
        if (keepGoing) {
            buildTarget("//foo:prune")
            bugReporter.assertNoExceptions()

            val targetCompleteEvent: TargetCompleteEvent? =
                com.google.common.collect.Iterables.getOnlyElement<TargetCompleteEvent?>(targetCompleteEvents)
            Truth.assertThat(getAllReportedArtifacts(targetCompleteEvent)).containsExactly("foo/prune.out")
            Truth.assertThat(getRootCauseLabels(targetCompleteEvent)).isEmpty()
        } else {
            val outErr: RecordingOutErr = RecordingOutErr()
            this.outErr = outErr
            val e: BuildFailedException =
                org.junit.Assert.assertThrows<T>(
                    BuildFailedException::class.java,
                    org.junit.function.ThrowingRunnable { buildTarget("//foo:prune") })
            assertDetailedExitCodeIsSourceIOFailure(e)
            val cause: Throwable = bugReporter.getFirstCause()
            assertDetailedExitCodeIsSourceIOFailure(cause)
            Truth.assertThat(cause).hasMessageThat().isEqualTo("error reading file '//foo:in': Symlink cycle")
            com.google.common.truth.Subject.contains("error reading file '//foo:in': Symlink cycle")

            val targetCompleteEvent: TargetCompleteEvent? =
                com.google.common.collect.Iterables.getOnlyElement<TargetCompleteEvent?>(targetCompleteEvents)
            Truth.assertThat(getAllReportedArtifacts(targetCompleteEvent)).isEmpty()
            Truth.assertThat(getRootCauseLabels(targetCompleteEvent)).containsExactly("//foo:prune")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incrementalUnusedDanglingSymlink() {
        write(
            "foo/pruning.bzl",
            """
        def _impl(ctx):
            inputs = ctx.attr.inputs.files
            output = ctx.actions.declare_file(ctx.label.name + ".out")
            unused_inputs_list = ctx.actions.declare_file(ctx.label.name + ".unused")
            arguments = [output.path, unused_inputs_list.path]
            for input in inputs.to_list():
                arguments += [input.path]
            ctx.actions.run(
                inputs = inputs,
                outputs = [output, unused_inputs_list],
                arguments = arguments,
                executable = ctx.executable.executable,
                unused_inputs_list = unused_inputs_list,
            )
            return DefaultInfo(files = depset([output]))

        build_rule = rule(
            attrs = {
                "inputs": attr.label(allow_files = True),
                "executable": attr.label(executable = True, allow_files = True, cfg = "exec"),
            },
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        val unusedSh: Path =
            write("foo/all_unused.sh", "touch $1", "shift", "unused=$1", "shift", "echo $@ > \$unused")
        unusedSh.setExecutable(true)
        val inPath: Path = write("foo/in")
        write(
            "foo/BUILD",
            """
        load("//foo:pruning.bzl", "build_rule")

        build_rule(
            name = "prune",
            executable = ":all_unused.sh",
            inputs = ":in",
        )
        
        """.trimIndent()
        )

        buildTarget("//foo:prune")

        inPath.delete()
        inPath.createSymbolicLink(PathFragment.create("nope"))

        val targetCompleteEvents: MutableList<TargetCompleteEvent?> = listenForTargetCompleteEvents()
        buildTarget("//foo:prune")

        val targetCompleteEvent: TargetCompleteEvent? =
            com.google.common.collect.Iterables.getOnlyElement<TargetCompleteEvent?>(targetCompleteEvents)
        Truth.assertThat(getAllReportedArtifacts(targetCompleteEvent)).containsExactly("foo/prune.out")
        Truth.assertThat(getRootCauseLabels(targetCompleteEvent)).isEmpty()
    }

    companion object {
        private val SOURCE_IO_FAILURE: FailureDetails.FailureDetail? = FailureDetails.FailureDetail.newBuilder()
            .setExecution(
                FailureDetails.Execution.newBuilder()
                    .setCode(FailureDetails.Execution.Code.SOURCE_INPUT_IO_EXCEPTION)
            )
            .build()

        private fun assertDetailedExitCodeIsSourceIOFailure(exception: Throwable) {
            Truth.assertThat(exception).isInstanceOf(DetailedException::class.java)
            assertThat((exception as DetailedException).detailedExitCode.getFailureDetail())
                .comparingExpectedFieldsOnly()
                .isEqualTo(SOURCE_IO_FAILURE)
        }

        private fun getAllReportedArtifacts(event: TargetCompleteEvent): com.google.common.collect.ImmutableSet<String?> {
            return event.reportedArtifacts(OutputGroupFileModes.DEFAULT).artifacts.stream()
                .flatMap({ set -> set.toList().stream() })
                .map({ artifact -> artifact.getRootRelativePath().getPathString() })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
        }

        private fun getRootCauseLabels(event: TargetCompleteEvent): com.google.common.collect.ImmutableSet<String?> {
            return event.getRootCauses().toList().stream()
                .map({ cause -> cause.getLabel().toString() })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
        }
    }
}
