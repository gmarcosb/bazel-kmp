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
package com.google.devtools.build.lib.metrics

import com.google.devtools.build.lib.actions.BuildFailedException

/** Tests metric collection.  */
@RunWith(JUnit4::class)
class MetricsCollectorTest : BuildIntegrationTestCase() {
    internal class BuildMetricsEventListener : BlazeModule() {
        private var event: BuildMetricsEvent? = null

        public override fun beforeCommand(env: CommandEnvironment) {
            env.getEventBus().register(this)
        }

        @com.google.common.eventbus.Subscribe
        fun onBuildMetrics(event: BuildMetricsEvent) {
            this.event = event
        }
    }

    private val buildMetricsEventListener = BuildMetricsEventListener()

    // needed for HeapOffset options.
    private val memoryPressureModule: MemoryPressureModule = MemoryPressureModule()

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.getRuntimeBuilder()
            .addBlazeModule(buildMetricsEventListener)
            .addBlazeModule(memoryPressureModule)

    @Before
    @Throws(java.lang.Exception::class)
    fun writeTrivialFooTarget() {
        write(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            outs = ["out"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
    }

    @Before
    fun setUpWorkerProcessMetricsCollector() {
        WorkerProcessMetricsCollector.instance().setClock(com.google.devtools.build.lib.clock.JavaClock())
    }

    @org.junit.After
    fun resetProfilers() {
        MemoryProfiler.instance().stop()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionsCreated() {
        buildTarget("//foo:foo")
        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        assertThat(buildMetrics.getActionSummary().getActionsCreated()).isGreaterThan(0L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionsCreatedIsZeroOnSecondBuild() {
        buildTarget("//foo:foo")
        buildTarget("//foo:foo")
        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        assertThat(buildMetrics.getActionSummary().getActionsCreated()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionsExecuted() {
        buildTarget("//foo:foo")
        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        assertThat(buildMetrics.getActionSummary().getActionsExecuted()).isGreaterThan(0L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionsExecutedIsZeroOnSecondBuild() {
        buildTarget("//foo:foo")
        buildTarget("//foo:foo")
        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        assertThat(buildMetrics.getActionSummary().getActionsExecuted()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionsCreatedForIndividualMnemonics() {
        write(
            "bar/BUILD",
            """
        load(":bar.bzl", "bar")
        bar(name = "bar")
        
        """.trimIndent()
        )
        write(
            "bar/bar.bzl",
            """
        def _impl(ctx):
            output1 = ctx.actions.declare_file(ctx.attr.name + ".out1")
            output2 = ctx.actions.declare_file(ctx.attr.name + ".out2")
            ctx.actions.write(output1, "foo")
            ctx.actions.write(output2, "bar")
            # Note that we created 2 actions, but pass along only one of their outputs.
            return [DefaultInfo(files = depset([output1]))]

        bar = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        buildTarget("//bar:bar")

        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        val actionData: MutableList<ActionData?> = buildMetrics.getActionSummary().getActionDataList()
        val fileWriteActions: com.google.common.collect.ImmutableList<ActionData> =
            actionData.stream()
                .filter { a: ActionData? -> a.getMnemonic().equals("FileWrite") }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<ActionData>())
        Truth.assertThat(fileWriteActions).hasSize(1)
        val fileWriteAction: ActionData = fileWriteActions.get(0)
        assertThat(fileWriteAction.getActionsCreated()).isEqualTo(2)
        assertThat(fileWriteAction.getActionsExecuted()).isEqualTo(1)

        val totalActionsCreated: Long = actionData.stream().mapToLong(ActionData::getActionsCreated).sum()
        val totalActionsExecuted: Long = actionData.stream().mapToLong(ActionData::getActionsExecuted).sum()
        Truth.assertThat(totalActionsCreated).isEqualTo(3)
        Truth.assertThat(totalActionsExecuted).isEqualTo(2)
        Truth.assertThat(totalActionsCreated).isEqualTo(buildMetrics.getActionSummary().getActionsCreated())
        Truth.assertThat(totalActionsExecuted)
            .isEqualTo(buildMetrics.getActionSummary().getActionsExecuted())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectActionsCreatedNotOverCountedForAlias() {
        write(
            "pkg/BUILD",
            """
        load(":defs.bzl", "my_rule")
        my_rule(name = "foo", dep = "dep_alias_alias")
        alias(name = "dep_alias_alias", actual = ":dep_alias")
        alias(name = "dep_alias", actual = ":dep")
        my_rule(name = "dep")
        
        """.trimIndent()
        )
        write(
            "pkg/defs.bzl",
            """
        def _aspect_impl(target, ctx):
            f = ctx.actions.declare_file(target.label.name + ".out")
            ctx.actions.run_shell(
                outputs = [f],
                command = "touch ${'$'}@",
                mnemonic = "MyAspectAction",
            )
            return [OutputGroupInfo(my_out = depset([f]))]

        my_aspect = aspect(implementation = _aspect_impl)

        def _impl(ctx):
            pass

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(aspects = [my_aspect]),
            },
        )
        
        """.trimIndent()
        )
        buildTarget("//pkg:foo")

        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        val actionData: MutableList<ActionData?> = buildMetrics.getActionSummary().getActionDataList()
        val aspectActions: com.google.common.collect.ImmutableList<ActionData> =
            actionData.stream()
                .filter { a: ActionData? -> a.getMnemonic().equals("MyAspectAction") }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<ActionData>())
        Truth.assertThat(aspectActions).hasSize(1)
        val aspectAction: ActionData = aspectActions.get(0)
        assertThat(aspectAction.getActionsCreated()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildGraphAndArtifactMetrics() {
        write(
            "a/BUILD",
            "genrule(name = 'a', srcs = ['//b:b', '//b:c'], outs = ['a.out'], cmd = 'cat $(SRCS) >"
                    + " $@')"
        )
        write(
            "b/BUILD",
            "genrule(name = 'b', srcs = ['b.in', 'c.in'], outs = ['b.out'], cmd = 'cat $(SRCS) > $@')",
            "genrule(name = 'c', srcs = ['c.in', 'c2.in'], outs = ['c.out'], cmd = 'cat $(SRCS) >"
                    + " $@')"
        )
        write("b/b.in", "12345")
        write("b/c.in", "12")
        createSymlink("c.in", "b/c2.in")
        write(
            "e/BUILD",
            """
        alias(
            name = "facade",
            actual = ":e.out",
        )

        genrule(
            name = "e",
            srcs = ["e.in"],
            outs = ["e.out"],
            cmd = "cat ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        write("e/e.in", "abc")

        // Do one build of a target in a standalone package. Gets us a baseline for analysis/execution.
        buildTarget("//e:facade")
        val skymeldWasInvolvedForBaselineBuild: Boolean =
            getCommandEnvironment().withMergedAnalysisAndExecutionSourceOfTruth()
        var buildGraphMetrics: BuildGraphMetrics =
            buildMetricsEventListener.event.getBuildMetrics().getBuildGraphMetrics()
        var actionLookupValueCount: Int = buildGraphMetrics.getActionLookupValueCount()
        // All these numbers should be big, but just want a basic check.
        Truth.assertThat(actionLookupValueCount).isGreaterThan(0)
        assertThat(buildGraphMetrics.getActionLookupValueCountNotIncludingAspects())
            .isEqualTo(actionLookupValueCount)
        val actionCount: Int = buildGraphMetrics.getActionCount()
        Truth.assertThat(actionCount).isGreaterThan(0)
        assertThat(buildGraphMetrics.getActionCountNotIncludingAspects()).isEqualTo(actionCount)
        assertThat(buildGraphMetrics)
            .comparingExpectedFieldsOnly()
            .isEqualTo(
                BuildGraphMetrics.newBuilder()
                    .setOutputFileConfiguredTargetCount(1)
                    .setOtherConfiguredTargetCount(1)
                    .build()
            )
        val outputArtifactCount: Int = buildGraphMetrics.getOutputArtifactCount()
        Truth.assertThat(outputArtifactCount).isGreaterThan(0)
        val graphSize: Int = buildGraphMetrics.getPostInvocationSkyframeNodeCount()
        Truth.assertThat(graphSize).isGreaterThan(0)
        val artifactMetrics: ArtifactMetrics =
            buildMetricsEventListener.event.getBuildMetrics().getArtifactMetrics()
        assertThat(artifactMetrics.getSourceArtifactsRead().getSizeInBytes()).isGreaterThan(0L)
        assertThat(artifactMetrics.getOutputArtifactsSeen())
            .isEqualTo(ArtifactMetrics.FilesMetric.newBuilder().setSizeInBytes(4L).setCount(3).build())
        assertThat(artifactMetrics.getOutputArtifactsFromActionCache().getCount()).isEqualTo(0)
        assertThat(artifactMetrics.getTopLevelArtifacts())
            .isEqualTo(ArtifactMetrics.FilesMetric.newBuilder().setSizeInBytes(4L).setCount(1).build())
        // Adjust for the "alias", "input" and "output" configured targets, which won't be in play
        // later.
        actionLookupValueCount -= 3

        // Now do a build of a target with non-trivial transitive deps, and verify the metrics. Blaze
        // won't redo analysis of dependencies or re-read their sources.
        buildTarget("//a")

        buildGraphMetrics = buildMetricsEventListener.event.getBuildMetrics().getBuildGraphMetrics()
        assertThat(buildGraphMetrics)
            .comparingExpectedFieldsOnly()
            .isEqualTo(
                BuildGraphMetrics.newBuilder() // Two dependencies and three source files for action lookup values.
                    .setActionLookupValueCount(5 + actionLookupValueCount)
                    .setActionCount(2 + actionCount)
                    .setInputFileConfiguredTargetCount(4)
                    .setOutputArtifactCount(2 + outputArtifactCount)
                    .build()
            )

        var newGraphSize: Int = buildGraphMetrics.getPostInvocationSkyframeNodeCount()
        Truth.assertThat(newGraphSize).isGreaterThan(graphSize)

        assertThat(buildMetricsEventListener.event.getBuildMetrics().getArtifactMetrics())
            .comparingExpectedFieldsOnly()
            .isEqualTo(
                ArtifactMetrics.newBuilder() // 2 distinct artifacts of 6 and 3 bytes, with a symlink to the 3-byte one.
                    .setSourceArtifactsRead(
                        ArtifactMetrics.FilesMetric.newBuilder().setSizeInBytes(12).setCount(3)
                    ) // b outputs 9 bytes, c outputs 6, a outputs 15, 30 total.
                    .setOutputArtifactsSeen(
                        ArtifactMetrics.FilesMetric.newBuilder().setSizeInBytes(30).setCount(3).build()
                    )
                    .setTopLevelArtifacts(
                        ArtifactMetrics.FilesMetric.newBuilder().setSizeInBytes(15).setCount(1).build()
                    )
                    .build()
            )

        // Do a null build. No useful analysis stats.
        buildTarget("//a")
        if (skymeldWasInvolvedForBaselineBuild) {
            // The BuildDriverKey of //e:facade is gone.
            newGraphSize -= 1
        }

        // For null build, we don't do any conflict checking. As the metrics are collected during the
        // traversal that's part of conflict checking, these analysis-related numbers are 0.
        assertThat(buildMetricsEventListener.event.getBuildMetrics().getBuildGraphMetrics())
            .comparingExpectedFieldsOnly()
            .isEqualTo(
                BuildGraphMetrics.newBuilder()
                    .setActionLookupValueCount(0)
                    .setActionLookupValueCountNotIncludingAspects(0)
                    .setActionCount(0)
                    .setActionCountNotIncludingAspects(0)
                    .setInputFileConfiguredTargetCount(0)
                    .setOutputArtifactCount(0)
                    .setPostInvocationSkyframeNodeCount(newGraphSize)
                    .build()
            )
        assertThat(buildMetricsEventListener.event.getBuildMetrics().getArtifactMetrics())
            .ignoringFieldAbsence()
            .isEqualTo(ArtifactMetrics.getDefaultInstance())

        // Change a BUILD file and rebuild: no source artifacts read, but analysis stats present.
        write(
            "a/BUILD",
            "genrule(name = 'a', srcs = ['//b:c', '//b:b'], outs = ['a.out'], cmd = 'cat $(SRCS) >"
                    + " $@')"
        )
        buildTarget("//a")

        assertThat(buildMetricsEventListener.event.getBuildMetrics().getBuildGraphMetrics())
            .comparingExpectedFieldsOnly()
            .isEqualTo(
                BuildGraphMetrics.newBuilder()
                    .setActionLookupValueCount(5 + actionLookupValueCount)
                    .setActionLookupValueCountNotIncludingAspects(5 + actionLookupValueCount)
                    .setActionCount(2 + actionCount)
                    .setActionCountNotIncludingAspects(2 + actionCount)
                    .setInputFileConfiguredTargetCount(4)
                    .setOutputArtifactCount(2 + outputArtifactCount)
                    .setOtherConfiguredTargetCount(0) // ArtifactNestedSet node for stale nested set is still in graph, since it is
                    // technically still valid (even though nobody wants that nested set anymore).
                    .setPostInvocationSkyframeNodeCount(newGraphSize + 1)
                    .build()
            )
        val singleFileMetric: ArtifactMetrics.FilesMetric? =
            ArtifactMetrics.FilesMetric.newBuilder().setSizeInBytes(15L).setCount(1).build()
        assertThat(buildMetricsEventListener.event.getBuildMetrics().getArtifactMetrics())
            .ignoringFieldAbsence()
            .isEqualTo(
                ArtifactMetrics.newBuilder()
                    .setOutputArtifactsSeen(singleFileMetric)
                    .setTopLevelArtifacts(singleFileMetric)
                    .build()
            )

        // Change BUILD file back, but don't do a full build.
        write(
            "a/BUILD",
            "genrule(name = 'a', srcs = ['//b:c', '//b:b'], outs = ['a.out'], cmd = 'cat $(SRCS) >"
                    + " $@')"
        )
        addOptions("--nobuild") // this disables skymeld, because there is no execution phase
        buildTarget("//a")
        assertThat(buildMetricsEventListener.event.getBuildMetrics().getBuildGraphMetrics())
            .comparingExpectedFieldsOnly()
            .isEqualTo(
                BuildGraphMetrics.newBuilder()
                    .setActionLookupValueCount(5 + actionLookupValueCount)
                    .setActionLookupValueCountNotIncludingAspects(5 + actionLookupValueCount)
                    .setActionCount(2 + actionCount)
                    .setActionCountNotIncludingAspects(2 + actionCount)
                    .setInputFileConfiguredTargetCount(4)
                    .setOutputArtifactCount(2 + outputArtifactCount)
                    .setOtherConfiguredTargetCount(0)
                    .setPostInvocationSkyframeNodeCount(newGraphSize + 1)
                    .build()
            )
        assertThat(buildMetricsEventListener.event.getBuildMetrics().getArtifactMetrics())
            .ignoringFieldAbsence()
            .isEqualTo(ArtifactMetrics.getDefaultInstance())

        // Null --nobuild.
        buildTarget("//a")
        if (skymeldWasInvolvedForBaselineBuild) {
            // When doing --nobuild, which doesn't trigger skymeld, no new BuildDriverKey entry is put in
            // the graph while the old one is deleted.
            newGraphSize -= 1
        }

        // Stale action execution have been GC'ed.
        assertThat(
            buildMetricsEventListener
                .event
                .getBuildMetrics()
                .getBuildGraphMetrics()
                .getPostInvocationSkyframeNodeCount()
        )
            .isEqualTo(newGraphSize - 1)

        // Do a null full build. Back to baseline.
        addOptions("--build")
        buildTarget("//a")
        if (skymeldWasInvolvedForBaselineBuild) {
            // Extra BuildDriverKey
            newGraphSize += 1
        }

        // We now have three copies of the ArtifactNestedSetKey, since the re-analysis didn't re-use the
        // old nested set.
        assertThat(
            buildMetricsEventListener
                .event
                .getBuildMetrics()
                .getBuildGraphMetrics()
                .getPostInvocationSkyframeNodeCount()
        )
            .isEqualTo(newGraphSize + 2)
        assertThat(buildMetricsEventListener.event.getBuildMetrics().getArtifactMetrics())
            .ignoringFieldAbsence()
            .comparingExpectedFieldsOnly()
            .isEqualTo(
                ArtifactMetrics.newBuilder()
                    .setOutputArtifactsSeen(singleFileMetric)
                    .setOutputArtifactsFromActionCache(singleFileMetric)
                    .setTopLevelArtifacts(singleFileMetric)
                    .build()
            )

        // Change a source file. It and its symlink are both re-read.
        write("b/c.in", "1234")
        buildTarget("//a")
        assertThat(buildMetricsEventListener.event.getBuildMetrics().getBuildGraphMetrics())
            .comparingExpectedFieldsOnly()
            .comparingExpectedFieldsOnly()
            .isEqualTo(
                BuildGraphMetrics.newBuilder() // Analysis not re-triggered, even of the input file that was changed.
                    .setInputFileConfiguredTargetCount(0)
                    .setPostInvocationSkyframeNodeCount(newGraphSize + 2)
                    .build()
            )
        assertThat(buildMetricsEventListener.event.getBuildMetrics().getArtifactMetrics())
            .ignoringFieldAbsence()
            .comparingExpectedFieldsOnly()
            .isEqualTo(
                ArtifactMetrics.newBuilder()
                    .setSourceArtifactsRead(
                        ArtifactMetrics.FilesMetric.newBuilder().setSizeInBytes(10).setCount(2)
                    )
                    .setOutputArtifactsSeen(
                        ArtifactMetrics.FilesMetric.newBuilder()
                            .setSizeInBytes(42L)
                            .setCount(3)
                            .build()
                    )
                    .setTopLevelArtifacts(
                        ArtifactMetrics.FilesMetric.newBuilder()
                            .setSizeInBytes(21L)
                            .setCount(1)
                            .build()
                    )
                    .build()
            )

        // Validate RuleClass Aspect and SkyFunction data is not reported by default
        var bgm: BuildGraphMetrics =
            buildMetricsEventListener.event.getBuildMetrics().getBuildGraphMetrics()
        assertThat(bgm.getRuleClassList()).isEmpty()
        assertThat(bgm.getAspectList()).isEmpty()

        // Enable skyframe metrics via flag and verify they're reported.
        addOptions("--experimental_record_skyframe_metrics=1")
        buildTarget("//a")
        bgm = buildMetricsEventListener.event.getBuildMetrics().getBuildGraphMetrics()

        val ruleClasses: MutableList<RuleClassCount?> = bgm.getRuleClassList()
        val aspectCount: MutableList<AspectCount>? = bgm.getAspectList()

        Truth.assertThat(ruleClasses.stream().map<Any?>(RuleClassCount::getKey))
            .containsExactly("genrule", "constraint_setting", "constraint_value", "platform")
        Truth.assertThat(aspectCount).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun treeArtifactAndTopLevelMetrics() {
        write(
            "foo/tree_artifact_rule.bzl",
            """
        def _tree_artifact_files_impl(ctx):
            directory = ctx.actions.declare_directory(ctx.attr.name + "_artifact")
            ctx.actions.run_shell(
                outputs = [directory],
                command = "cd %s && echo a > file1 && echo bcde > file2}" % (directory.path),
            )
            return [DefaultInfo(files = depset([directory]))]

        def _several_outputs_impl(ctx):
            file = ctx.actions.declare_file(ctx.attr.name + "_file")
            ctx.actions.write(output = file, content = "abc")
            return [
                DefaultInfo(files = depset([file])),
                OutputGroupInfo(dep_files = ctx.attr.dep[DefaultInfo].files),
            ]

        my_tree = rule(implementation = _tree_artifact_files_impl)
        my_rule = rule(
            implementation = _several_outputs_impl,
            attrs = {"dep": attr.label()},
        )
        
        """.trimIndent()
        )
        write(
            "foo/BUILD",
            """
        load("//foo:tree_artifact_rule.bzl", "my_rule", "my_tree")

        my_tree(name = "tree")

        my_rule(
            name = "top",
            dep = ":tree",
        )
        
        """.trimIndent()
        )
        // Null build to populate silly things like fake build-info artifact.
        buildTarget()
        addOptions("--output_groups=+dep_files")
        buildTarget("//foo:top")
        assertThat(buildMetricsEventListener.event.getBuildMetrics().getArtifactMetrics())
            .ignoringFieldAbsence()
            .isEqualTo(
                ArtifactMetrics.newBuilder()
                    .setOutputArtifactsSeen(
                        ArtifactMetrics.FilesMetric.newBuilder()
                            .setSizeInBytes(10L)
                            .setCount(3)
                            .build()
                    )
                    .setTopLevelArtifacts(
                        ArtifactMetrics.FilesMetric.newBuilder()
                            .setSizeInBytes(10L)
                            .setCount(3)
                            .build()
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetCounts() {
        buildTarget("//foo:foo")
        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        assertThat(buildMetrics.getTargetMetrics().getTargetsConfigured()).isGreaterThan(0L)
        assertThat(buildMetrics.getTargetMetrics().getTargetsConfiguredNotIncludingAspects())
            .isGreaterThan(0L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetsCountsAreZeroOnSecondBuild() {
        buildTarget("//foo:foo")
        buildTarget("//foo:foo")
        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        assertThat(buildMetrics.getTargetMetrics().getTargetsLoaded()).isEqualTo(0)
        assertThat(buildMetrics.getTargetMetrics().getTargetsConfigured()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectLoadedMetric() {
        write(
            "foo/foo.bzl",
            """
        def _aspect_impl(target, ctx):
            outfile = ctx.actions.declare_file(ctx.rule.attr.name + "aspect.out")
            ctx.actions.run_shell(
                outputs = [outfile],
                command = 'echo "1" > ' + outfile.path,
            )
            return [OutputGroupInfo(files = [outfile])]

        def _impl(ctx):
            return []

        rule_aspect = aspect(implementation = _aspect_impl, attr_aspects = ["deps"])

        aspected = rule(
            implementation = _impl,
            attrs = {"deps": attr.label_list(aspects = [rule_aspect])},
        )
        
        """.trimIndent()
        )
        write(
            "foo/BUILD",
            """
        load("//foo:foo.bzl", "aspected")

        aspected(
            name = "top",
            deps = [":dep"],
        )

        aspected(name = "dep")
        
        """.trimIndent()
        )
        buildTarget("//foo:top")
        var buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        // 2 additional: aspect and workspace status action.
        assertThat(buildMetrics.getTargetMetrics().getTargetsConfigured())
            .isEqualTo(2L + buildMetrics.getTargetMetrics().getTargetsConfiguredNotIncludingAspects())
        assertThat(buildMetrics.getActionSummary().getActionsCreated())
            .isEqualTo(2L + buildMetrics.getActionSummary().getActionsCreatedNotIncludingAspects())
        // Traversing the Skyframe graph doesn't hit the workspace status action.
        assertThat(buildMetrics.getBuildGraphMetrics().getActionLookupValueCount())
            .isEqualTo(
                1L
                        + buildMetrics
                    .getBuildGraphMetrics()
                    .getActionLookupValueCountNotIncludingAspects()
            )
        assertThat(buildMetrics.getBuildGraphMetrics().getActionCount())
            .isEqualTo(1L + buildMetrics.getBuildGraphMetrics().getActionCountNotIncludingAspects())

        // Analyzing a new target makes the aspect drop out of the target metric, but the build graph
        // metric still knows about it.
        write("bar/BUILD", "genrule(name = 'bar', outs = ['out'], cmd = 'touch $@')")
        buildTarget("//foo:top", "//bar:bar")
        buildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        assertThat(buildMetrics.getTargetMetrics().getTargetsConfigured())
            .isEqualTo(buildMetrics.getTargetMetrics().getTargetsConfiguredNotIncludingAspects())
        assertThat(buildMetrics.getActionSummary().getActionsCreated())
            .isEqualTo(buildMetrics.getActionSummary().getActionsCreatedNotIncludingAspects())
        assertThat(buildMetrics.getBuildGraphMetrics().getActionLookupValueCount())
            .isEqualTo(
                1L
                        + buildMetrics
                    .getBuildGraphMetrics()
                    .getActionLookupValueCountNotIncludingAspects()
            )
        assertThat(buildMetrics.getBuildGraphMetrics().getActionCount())
            .isEqualTo(1L + buildMetrics.getBuildGraphMetrics().getActionCountNotIncludingAspects())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackagesLoaded() {
        buildTarget("//foo:foo")
        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        assertThat(buildMetrics.getPackageMetrics().getPackagesLoaded()).isGreaterThan(0L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackagesLoadedIsZeroOnSecondBuild() {
        buildTarget("//foo:foo")
        buildTarget("//foo:foo")
        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        assertThat(buildMetrics.getPackageMetrics().getPackagesLoaded()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisTimeInMs() {
        buildTarget("//foo:foo")
        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        assertThat(buildMetrics.getTimingMetrics().getAnalysisPhaseTimeInMs()).isGreaterThan(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionTimeInMs() {
        buildTarget("//foo:foo")
        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        assertThat(buildMetrics.getTimingMetrics().getExecutionPhaseTimeInMs()).isGreaterThan(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionsExecutionStartTimeInMs() {
        buildTarget("//foo:foo")
        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        val actionsExecutionStartInMs: Long = buildMetrics.getTimingMetrics().getActionsExecutionStartInMs()
        Truth.assertThat(actionsExecutionStartInMs).isGreaterThan(0)
        Truth.assertThat(actionsExecutionStartInMs)
            .isLessThan(buildMetrics.getTimingMetrics().getWallTimeInMs())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUsedHeapSizePostBuild() {
        // TODO(bazel-team): Fix recording used heap size on Windows.
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)
        addOptions("--memory_profile=/dev/null")

        // The options from above do not get added to the initial command environment,
        // so it has to be recreated here.
        buildTarget("//foo:foo")
        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        assertThat(buildMetrics.getMemoryMetrics().getUsedHeapSizePostBuild()).isGreaterThan(0L)
        // Note that we cannot test peak heap size here since the tiny builds that we do here don't
        // trigger a full GC.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUsedHeapSizePostBuildCollectionOffByDefault() {
        buildTarget("//foo:foo")
        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        assertThat(buildMetrics.getMemoryMetrics().getUsedHeapSizePostBuild()).isEqualTo(0)
        assertThat(buildMetrics.getMemoryMetrics().getPeakPostGcHeapSize()).isEqualTo(0)
        assertThat(buildMetrics.getMemoryMetrics().getPeakPostGcTenuredSpaceHeapSize()).isEqualTo(0)
        assertThat(buildMetrics.getMemoryMetrics().getPeakPostGcHeapSizeDuringExecution()).isEqualTo(0)
        assertThat(buildMetrics.getMemoryMetrics().getPeakPostGcTenuredSpaceHeapSizeDuringExecution())
            .isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWallTimePostBuild() {
        buildTarget("//foo:foo")
        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        assertThat(buildMetrics.getTimingMetrics().getWallTimeInMs()).isGreaterThan(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cumulativeMetrics() {
        buildTarget("//foo:foo")
        assertThat(buildMetricsEventListener.event.getBuildMetrics().getCumulativeMetrics())
            .isEqualTo(CumulativeMetrics.newBuilder().setNumAnalyses(1).setNumBuilds(1).build())

        addOptions("--nobuild")
        buildTarget("//foo:foo")
        assertThat(buildMetricsEventListener.event.getBuildMetrics().getCumulativeMetrics())
            .isEqualTo(CumulativeMetrics.newBuilder().setNumAnalyses(2).setNumBuilds(1).build())

        addOptions("--build", "--noanalyze")
        buildTarget("//foo:foo")
        assertThat(buildMetricsEventListener.event.getBuildMetrics().getCumulativeMetrics())
            .isEqualTo(CumulativeMetrics.newBuilder().setNumAnalyses(2).setNumBuilds(1).build())

        write(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            srcs = ["//noexist"],
            outs = ["out"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )

        addOptions("--analyze")
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:foo") })
        assertThat(buildMetricsEventListener.event.getBuildMetrics().getCumulativeMetrics())
            .isEqualTo(CumulativeMetrics.newBuilder().setNumAnalyses(3).setNumBuilds(1).build())

        write(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            outs = ["out"],
            cmd = "/bin/false",
        )
        
        """.trimIndent()
        )

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:foo") })
        assertThat(buildMetricsEventListener.event.getBuildMetrics().getCumulativeMetrics())
            .isEqualTo(CumulativeMetrics.newBuilder().setNumAnalyses(4).setNumBuilds(2).build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionData() {
        write(
            "bar/BUILD",
            """
        genrule(
            name = "bar",
            srcs = [
                ":dep1",
                ":dep2",
            ],
            outs = ["out"],
            cmd = "touch ${'$'}@",
        )

        genrule(
            name = "dep1",
            outs = ["out1"],
            cmd = "touch ${'$'}@",
        )

        genrule(
            name = "dep2",
            outs = ["out2"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
        buildTarget("//bar")
        val buildMetrics: BuildMetrics = buildMetricsEventListener.event.getBuildMetrics()
        val actionDataList: MutableList<ActionData>? = buildMetrics.getActionSummary().getActionDataList()
        Truth.assertThat(actionDataList).hasSize(2)
        assertThat(actionDataList!!.get(0).getMnemonic()).isEqualTo("Genrule")
        assertThat(actionDataList.get(0).getActionsExecuted()).isEqualTo(3)

        assertThat(actionDataList.get(1).getMnemonic()).isEqualTo("DummyBuildInfoAction")
        assertThat(actionDataList.get(1).getActionsExecuted()).isEqualTo(1)

        for (actionData in actionDataList) {
            assertThat(actionData.getFirstStartedMs()).isAtMost(actionData.getLastEndedMs())
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun skymeldNullIncrementalBuild_buildGraphMetricsNotCollected() {
        write(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            outs = ["out"],
            cmd = "touch ${'$'}@",
        )

        genrule(
            name = "bar",
            outs = ["out2"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
        addOptions("--experimental_merged_skyframe_analysis_execution")
        val expected: BuildGraphMetrics? =
            BuildGraphMetrics.newBuilder()
                .setActionLookupValueCount(8)
                .setActionLookupValueCountNotIncludingAspects(8)
                .setActionCount(2)
                .setActionCountNotIncludingAspects(2)
                .setInputFileConfiguredTargetCount(1)
                .setOutputArtifactCount(2)
                .build()
        buildTarget("//foo:foo", "//foo:bar")

        assertThat(buildMetricsEventListener.event.getBuildMetrics().getBuildGraphMetrics())
            .comparingExpectedFieldsOnly()
            .isEqualTo(expected)

        // Null build.
        buildTarget("//foo:foo", "//foo:bar")

        val expectedNullBuild: BuildGraphMetrics? =
            BuildGraphMetrics.newBuilder()
                .setActionLookupValueCount(0)
                .setActionLookupValueCountNotIncludingAspects(0)
                .setActionCount(0)
                .setActionCountNotIncludingAspects(0)
                .setInputFileConfiguredTargetCount(0)
                .setOutputArtifactCount(0)
                .build()
        assertThat(buildMetricsEventListener.event.getBuildMetrics().getBuildGraphMetrics())
            .comparingExpectedFieldsOnly()
            .isEqualTo(expectedNullBuild)
    }

    @org.junit.Test
    fun testCreateWorkerPoolMetrics() {
        // Given a list of WorkerProcessMetric(s), it should correctly aggregate the counts and create
        // the WorkerPoolMetrics proto.
        val allProcessMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics?> =
            com.google.common.collect.ImmutableList.of<WorkerProcessMetrics?>( // Hash 100: alive_count = 3
                //           created_count = 1
                createWorkerProcessMetrics( /* workerIds= */
                    com.google.common.collect.ImmutableList.of<Int?>(1),  /* hash= */
                    100,
                    Status.ALIVE,  /* newlyCreated= */
                    true
                ),
                createWorkerProcessMetrics( /* workerIds= */
                    com.google.common.collect.ImmutableList.of<Int?>(2, 3),  /* hash= */
                    100,
                    Status.ALIVE,  /* newlyCreated= */
                    false
                ),  // Hash 200: evicted_count = 3
                //           io_exception_destroyed_count = 2
                //           unknown_destroyed_count = 1
                //           destroyed_count = 6
                //           created_count = 3  (should still be counted even if they are killed).
                createWorkerProcessMetrics( /* workerIds= */
                    com.google.common.collect.ImmutableList.of<Int?>(1, 2, 3),  /* hash= */
                    200,
                    Status.KILLED_DUE_TO_MEMORY_PRESSURE,  /* newlyCreated= */
                    false
                ),
                createWorkerProcessMetrics( /* workerIds= */
                    com.google.common.collect.ImmutableList.of<Int?>(1, 2),  /* hash= */
                    200,
                    Status.KILLED_DUE_TO_IO_EXCEPTION,  /* newlyCreated= */
                    true
                ),
                createWorkerProcessMetrics( /* workerIds= */
                    com.google.common.collect.ImmutableList.of<Int?>(1),  /* hash= */
                    200,
                    Status.KILLED_UNKNOWN,  /* newlyCreated= */
                    true
                )
            )
        assertThat(MetricsCollector.createWorkerPoolMetrics(allProcessMetrics).getWorkerPoolStatsList())
            .containsExactly(
                WorkerPoolStats.newBuilder()
                    .setMnemonic(DUMMY_MNEMONIC)
                    .setHash(100)
                    .setCreatedCount(1)
                    .setAliveCount(3)
                    .build(),
                WorkerPoolStats.newBuilder()
                    .setMnemonic(DUMMY_MNEMONIC)
                    .setHash(200)
                    .setEvictedCount(3)
                    .setIoExceptionDestroyedCount(2)
                    .setUnknownDestroyedCount(1)
                    .setDestroyedCount(6)
                    .setCreatedCount(3)
                    .build()
            )
    }

    private fun createWorkerProcessMetrics(
        workerIds: com.google.common.collect.ImmutableList<Int?>, hash: Int, status: Status?, newlyCreated: Boolean
    ): WorkerProcessMetrics {
        val processStatus: WorkerProcessStatus = WorkerProcessStatus()
        processStatus.maybeUpdateStatus(status)
        val workerProcessMetrics: WorkerProcessMetrics =
            WorkerProcessMetrics(
                workerIds,  /* processId= */
                0,
                processStatus,
                "DUMMY_MNEMONIC",  /* isMultiplex= */
                workerIds.size > 1,  /* isSandbox= */
                true,  /* workerKeyHash= */
                hash
            )
        if (!newlyCreated) {
            // Simulate that this process has been alive since before the command.
            workerProcessMetrics.onBeforeCommand()
        }
        return workerProcessMetrics
    }

    companion object {
        private const val DUMMY_MNEMONIC = "DUMMY_MNEMONIC"
    }
}
