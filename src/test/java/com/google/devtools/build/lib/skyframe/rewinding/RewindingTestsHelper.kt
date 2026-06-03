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
package com.google.devtools.build.lib.skyframe.rewinding

import com.google.devtools.build.lib.vfs.FileSystemUtils.readContentAsLatin1

/**
 * Implements rewinding-specific infrastructure and test logic used for rewinding tests. Search for
 * the callers of these methods to find the [BuildIntegrationTestCase] classes where the
 * build-specific infrastructure and the actual tests are implemented.
 * 
 * 
 * In this class, methods whose names begin with `run` implement test logic. Generally,
 * these tests have the following structure:
 * 
 * 
 *  1. `BUILD`, `bzl`, and other source file setup.
 *  1. Injection of one or more [spawn shims][SpawnShim], giving the test control of
 * whether execution succeeds or fails with either an appropriately structured [       ] or an [IOException]. These shims also occasionally capture
 * input file contents to be checked after the build is done.
 *  1. Injection of a [       ] so that
 * the invalidated Skyframe nodes can be tracked, along with the order they're invalidated in.
 *  1. The build itself.
 *  1. Assertions of (some subset of):
 * 
 *  * the build's output's contents
 *  * what spawn actions were run (and, when possible, in what order)
 *  * what events were emitted
 *  * what Skyframe nodes were invalidated and in what order
 * 
 * 
 * 
 * 
 * Tests are largely of two structurally similar but distinguishable categories:
 * 
 * 
 *  1. Tests that check the rewinding strategy's behavior and how it interacts with build logic
 * under varying circumstances, like [.runActionFromPreviousBuildReevaluated], [       ][.runIneffectiveRewindingResultsInLostInputTooManyTimes], [       ][.runInterruptedDuringRewindStopsNormally].
 *  1. Tests that check the behavior of the execution strategy and Skyframe action execution
 * machinery to make sure they collaborate to give the action rewinding strategy the
 * information it needs to figure out what Skyframe nodes need to be rewound. Examples of
 * these include [.runDependentActionsReevaluated], [.runTreeFileArtifactRewound],
 * and others, which test different combinations of types of action inputs which can get lost.
 * 
 */
class RewindingTestsHelper(testCase: BuildIntegrationTestCase?, recorder: ActionEventRecorder?) {
    val recorder: ActionEventRecorder
    val testCase: BuildIntegrationTestCase
    private val spawnController: SpawnController = SpawnController()
    val lostOutputsModule: LostImportantOutputHandlerModule

    fun getLostOutputsModule(): LostImportantOutputHandlerModule {
        return lostOutputsModule
    }

    /**
     * Converts a file digest to a hex string compatible with the test's active [ ].
     */
    @com.google.errorprone.annotations.ForOverride
    fun toHex(digest: ByteArray, size: Long): String {
        val hex: java.lang.StringBuilder = java.lang.StringBuilder()
        for (b in digest) {
            hex.append(String.format("%02x", b))
        }
        hex.append('/')
        hex.append(size)
        return hex.toString()
    }

    @com.google.errorprone.annotations.ForOverride
    fun createLostOutputsModule(): LostImportantOutputHandlerModule {
        return LostImportantOutputHandlerModule({ digest: ByteArray, size: Long -> this.toHex(digest, size) })
    }

    fun makeControllableActionStrategyModule(
        vararg identifiers: String?
    ): ControllableActionStrategyModule {
        return ControllableActionStrategyModule(spawnController, *identifiers)
    }

    val executedSpawnDescriptions: com.google.common.collect.ImmutableList<String?>
        get() = spawnController.getExecutedSpawnDescriptions()

    fun clearExecutedSpawnDescriptions() {
        spawnController.clearExecutedSpawnDescriptions()
    }

    fun addSpawnShim(spawnDescription: String?, spawnShim: SpawnShim?) {
        spawnController.addSpawnShim(spawnDescription, spawnShim)
    }

    fun verifyAllSpawnShimsConsumed() {
        spawnController.verifyAllShimsConsumed()
    }

    @Throws(IOException::class)
    fun createLostInputsExecException(
        spawn: Spawn, context: ActionExecutionContext?, vararg lostInputNames: String?
    ): ExecResult? {
        return createLostInputsExecException(
            context,
            java.util.Arrays.stream<String?>(lostInputNames)
                .map<Any?> { name: String? -> SpawnInputUtils.getInputWithName(spawn, name) }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>()))
    }

    @Throws(IOException::class)
    fun createLostInputsExecException(
        context: ActionExecutionContext, vararg lostInputs: ActionInput?
    ): ExecResult {
        return createLostInputsExecException(
            context,
            com.google.common.collect.ImmutableList.copyOf<ActionInput?>(lostInputs)
        )
    }

    @Throws(IOException::class)
    fun createLostInputsExecException(
        context: ActionExecutionContext, lostInputs: com.google.common.collect.ImmutableList<ActionInput>
    ): ExecResult {
        val builder: com.google.common.collect.ImmutableSetMultimap.Builder<String?, ActionInput?> =
            com.google.common.collect.ImmutableSetMultimap.builder<String?, ActionInput?>()
        for (lostInput in lostInputs) {
            builder.put(getHexDigest(lostInput, context), lostInput)
        }
        return ExecResult.ofException(LostInputsExecException(builder.build()))
    }

    @Throws(IOException::class)
    private fun getHexDigest(input: ActionInput?, context: ActionExecutionContext): String {
        val metadata: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            context.getInputMetadataProvider().getInputMetadata(input)
        return toHex(metadata.getDigest(), metadata.getSize())
    }

    /**
     * Injects a [NotifyingHelper.Listener] that collects keys rewound by rewinding into the
     * returned list, starting with the next build.
     * 
     * 
     * To avoid brittle assertions on the number of keys rewound, [ArtifactNestedSetKey] is
     * not collected, though it may be rewound. Its [ ] may contain multiple paths (of
     * varying length) to a lost artifact, any of which would be a correct chain for rewinding.
     */
    fun collectOrderedRewoundKeys(): MutableList<SkyKey?> {
        val rewoundKeys: MutableList<SkyKey?> = Collections.synchronizedList<SkyKey?>(java.util.ArrayList<SkyKey?>())
        testCase.injectListenerAtStartOfNextBuild(
            NotifyingHelper.Listener { key, type, order, context ->
                if (type.equals(NotifyingHelper.EventType.MARK_DIRTY) && order.equals(NotifyingHelper.Order.AFTER)) {
                    val markDirtyAfterContext: MarkDirtyAfterContext =
                        context as MarkDirtyAfterContext
                    if (markDirtyAfterContext.dirtyType === DirtyType.REWIND && markDirtyAfterContext.actuallyDirtied // Ignore ArtifactNestedSetKey. See method javadoc.
                        && (key !is ArtifactNestedSetKey)
                    ) {
                        rewoundKeys.add(key)
                    }
                }
            })
        return rewoundKeys
    }

    /**
     * Builds the genrule "//`pkg`:consume_output", which must specify "//`pkg`:output.inlined" as a "srcs" dep. Returns the contents of `output.inlined` as a
     * latin1 [String].
     * 
     * 
     * This is useful for builds that do not write output files to disk, and so those files'
     * contents can't be verified via regular filesystem operations. This method extracts `output.inlined`'s contents during evaluation.
     */
    @Throws(java.lang.Exception::class)
    fun buildAndGetOutput(pkg: String?, testCase: BuildIntegrationTestCase): String? {
        val invocationOutput: AtomicReference<String?> = AtomicReference<String?>(null)
        addSpawnShim(
            String.format("Executing genrule //%s:consume_output", pkg),
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                val actionInput: ActionInput = SpawnInputUtils.getInputWithName(spawn, "output.inlined")
                invocationOutput.set(latin1StringFromActionInput(context, actionInput))
                ExecResult.delegate()
            })
        testCase.buildTarget(String.format("//%s:consume_output", pkg))
        return invocationOutput.get()
    }

    @Throws(java.lang.Exception::class)
    fun runNoLossSmokeTest() {
        testCase.write(
            "test/BUILD",
            """
        genrule(
            name = "rule1",
            srcs = ["source.txt"],
            outs = ["intermediate.txt"],
            cmd = "(cat ${'$'}< && echo from rule1) > ${'$'}@",
        )

        genrule(
            name = "rule2",
            srcs = ["intermediate.txt"],
            outs = ["output.inlined"],
            cmd = "(cat ${'$'}< && echo from rule2) > ${'$'}@",
        )

        genrule(
            name = "consume_output",
            srcs = [":output.inlined"],
            outs = ["dummy.out"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
        testCase.write("test/source.txt", "source")

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        val outputFileContent = buildAndGetOutput("test", testCase)

        Truth.assertThat(outputFileContent).isEqualTo("source\nfrom rule1\nfrom rule2\n")
        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactly(
                "Executing genrule //test:rule1",
                "Executing genrule //test:rule2",
                "Executing genrule //test:consume_output"
            )
            .inOrder()

        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>(
                "Executing genrule //test:rule1", "Executing genrule //test:rule2"
            ),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>(),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>(),  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(0)
        )

        Truth.assertThat(rewoundKeys).isEmpty()
    }

    @Throws(java.lang.Exception::class)
    fun runLostInputWithRewindingDisabled() {
        testCase.write(
            "foo/BUILD",
            """
        genrule(name = 'top', outs = ['top.out'], srcs = [':dep'], cmd = 'cp ${'$'}< ${'$'}@')
        genrule(name = 'dep', outs = ['dep.out'], cmd = 'touch ${'$'}@')
        
        """.trimIndent()
        )
        testCase.addOptions("--norewind_lost_inputs")
        addSpawnShim(
            "Executing genrule //foo:top",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                createLostInputsExecException(
                    spawn,
                    context,
                    "dep.out"
                )
            })

        val e: T = org.junit.Assert.assertThrows<T>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { testCase.buildTarget("//foo:top") })
        assertThat(e.getDetailedExitCode().getFailureDetail().getActionRewinding().getCode())
            .isEqualTo(ActionRewinding.Code.LOST_INPUT_REWINDING_DISABLED)
        testCase.assertContainsError(
            "Executing genrule //foo:top failed: Unexpected lost inputs (pass"
                    + " --rewind_lost_inputs to enable recovery): foo/dep.out"
        )
    }

    /**
     * Tests that [Inconsistency.BUILDING_PARENT_FOUND_UNDONE_CHILD] is not tolerated if there
     * has not been any rewinding.
     */
    @Throws(java.lang.Exception::class)
    fun runBuildingParentFoundUndoneChildNotToleratedWithoutRewinding() {
        val bugReporter: BugReporter = Mockito.mock<BugReporter>(BugReporter::class.java)
        testCase.setCustomBugReporterAndReinitialize(bugReporter)
        testCase.write(
            "foo/BUILD",
            """
        genrule(
            name = "top",
            srcs = [":dep"],
            outs = ["top.out"],
            cmd = "cp ${'$'}< ${'$'}@",
        )

        genrule(
            name = "dep",
            outs = ["dep.out"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
        testCase.injectListenerAtStartOfNextBuild(
            NotifyingHelper.Listener { key, type, order, context ->
                if (type === NotifyingHelper.EventType.GET_BATCH && order === NotifyingHelper.Order.BEFORE && context === Reason.PREFETCH && isActionExecutionKey(
                        key,
                        Label.parseCanonicalUnchecked("//foo:dep")
                    )
                ) {
                    try {
                        testCase
                            .skyframeExecutor
                            .getEvaluator()
                            .getExistingEntryAtCurrentlyEvaluatingVersion(key)
                            .markDirty(DirtyType.REWIND)
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.IllegalStateException(e)
                    }
                }
            })

        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable { testCase.buildTarget("//foo:top") })
        Truth.assertThat(e).hasMessageThat().contains("Unexpected undone children")
        Mockito.verify<BugReporter?>(bugReporter)
            .handleCrash(ArgumentMatchers.any<Crash?>(), ArgumentMatchers.any<CrashContext?>())
    }

    @Throws(java.lang.Exception::class)
    fun runDependentActionsReevaluated_spawnFailed() {
        // The first time rule2 is executed, the execution strategy fails, saying that rule2's two input
        // files are missing.
        runDependentActionsReevaluated(
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                createLostInputsExecException(
                    context,
                    getIntermediate1And2LostInputs(spawn)
                )
            })
    }

    @Throws(java.lang.Exception::class)
    fun runDependentActionsReevaluated(shim: SpawnShim?) {
        // This test sets up a genrule, rule2, that consumes the outputs of two other genrules.
        testCase.write(
            "test/BUILD",
            """
        genrule(
            name = "rule1_1",
            srcs = ["source_1.txt"],
            outs = ["intermediate_1.txt"],
            cmd = "(cat ${'$'}< && echo from rule1_1) > ${'$'}@",
        )

        genrule(
            name = "rule1_2",
            srcs = ["source_2.txt"],
            outs = ["intermediate_2.txt"],
            cmd = "(cat ${'$'}< && echo from rule1_2) > ${'$'}@",
        )

        genrule(
            name = "rule2",
            srcs = [
                "intermediate_1.txt",
                "intermediate_2.txt",
                "source_3.txt",
            ],
            outs = ["output.inlined"],
            cmd = "(cat ${'$'}(SRCS) && echo from rule2) > ${'$'}@",
        )

        genrule(
            name = "consume_output",
            srcs = [":output.inlined"],
            outs = ["dummy.out"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )

        testCase.write("test/source_1.txt", "source_1")
        testCase.write("test/source_2.txt", "source_2")
        testCase.write("test/source_3.txt", "source_3")

        addSpawnShim("Executing genrule //test:rule2", shim)

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        val outputFileContents = buildAndGetOutput("test", testCase)

        // The evaluation succeeds, producing the expected output, after re-executing rule1_1's and
        // rule1_2's actions.
        Truth.assertThat(outputFileContents)
            .isEqualTo("source_1\nfrom rule1_1\nsource_2\nfrom rule1_2\nsource_3\nfrom rule2\n")

        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactly(
                "Executing genrule //test:rule1_1",
                "Executing genrule //test:rule1_2",
                "Executing genrule //test:rule2",
                "Executing genrule //test:rule1_1",
                "Executing genrule //test:rule1_2",
                "Executing genrule //test:rule2",
                "Executing genrule //test:consume_output"
            )

        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>(),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>(
                "Executing genrule //test:rule1_1", "Executing genrule //test:rule1_2"
            ),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //test:rule2"),  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(2)
        )

        assertOnlyActionsRewound(rewoundKeys)
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys))
            .containsExactly("//test:rule1_1", "//test:rule1_2")
    }

    @Throws(java.lang.Exception::class)
    fun runActionFromPreviousBuildReevaluated() {
        // This test sets up a genrule, rule2, that consumes the outputs of rule1. rule1 is requested on
        // the first build, so that on the second build, when rule2 discovers its missing input, rule1
        // is cached.
        writeTwoGenrulePackage(testCase)

        testCase.buildTarget("//test:rule1")
        Truth.assertThat(this.executedSpawnDescriptions).containsExactly("Executing genrule //test:rule1")

        clearExecutedSpawnDescriptions()
        // The first time rule2 is executed, the execution strategy fails, saying that rule2's input
        // file is missing.
        addSpawnShim(
            "Executing genrule //test:rule2",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                val lostInputs: com.google.common.collect.ImmutableList<ActionInput> =
                    com.google.common.collect.ImmutableList.of<ActionInput>(
                        SpawnInputUtils.getInputWithName(
                            spawn,
                            "intermediate.txt"
                        )
                    )
                createLostInputsExecException(context, lostInputs)
            })

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        val outputFileContents = buildAndGetOutput("test", testCase)

        // The evaluation succeeds, producing the expected output, after re-executing rule1's action.
        Truth.assertThat(outputFileContents).isEqualTo("source_1\nfrom rule1\nsource_2\nfrom rule2\n")

        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactly(
                "Executing genrule //test:rule2",
                "Executing genrule //test:rule1",
                "Executing genrule //test:rule2",
                "Executing genrule //test:consume_output"
            )

        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>(),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //test:rule1"),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //test:rule2"),  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(0, 1)
        )

        assertOnlyActionsRewound(rewoundKeys)
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//test:rule1")
    }

    @Throws(java.lang.Exception::class)
    fun runIneffectiveRewindingResultsInLostInputTooManyTimes() {
        // This test sets up two genrules, and makes the several execution attempts of rule2 fail,
        // saying that the file produced by rule1 is missing. The last time rule2 fails because of the
        // same lost input, rewinding is not attempted, and the build fails with a
        // LOST_INPUT_TOO_MANY_TIMES detailed exit code.
        writeTwoGenrulePackage(testCase)

        // Store a reference to the input so that we can match the exception message. The output
        // directory name (and hence the string representation) varies by platform.
        val intermediate: AtomicReference<ActionInput?> = AtomicReference<ActionInput?>()
        for (i in 0..ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS) {
            addSpawnShim(
                "Executing genrule //test:rule2",
                SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                    intermediate.set(SpawnInputUtils.getInputWithName(spawn, "intermediate.txt"))
                    ExecResult.ofException(
                        LostInputsExecException(
                            com.google.common.collect.ImmutableSetMultimap.< K,
                            V > of<K?, V?>("fakedigest/10", intermediate.get())
                        )
                    )
                })
        }

        val bugReporter: RecordingBugReporter = testCase.recordBugReportsAndReinitialize()
        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        val e: BuildFailedException =
            org.junit.Assert.assertThrows<T>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { testCase.buildTarget("//test:rule2") })
        assertThat(e.getDetailedExitCode().getFailureDetail().getActionRewinding().getCode())
            .isEqualTo(ActionRewinding.Code.LOST_INPUT_TOO_MANY_TIMES)

        val errorDetail: String? =
            java.lang.String.format(
                ("lost input too many times (#%s) for the same action. lostInput: %s, "
                        + "lostInput digest: fakedigest/10, "
                        + "failedAction: action 'Executing genrule //test:rule2'"),
                ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS + 1, intermediate.get()
            )
        com.google.common.truth.Subject.contains(errorDetail)
        Truth.assertThat(com.google.common.collect.Iterables.getOnlyElement<Throwable?>(bugReporter.getExceptions()))
            .hasMessageThat()
            .contains(errorDetail)

        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactlyElementsIn(
                com.google.common.collect.Iterables.concat<String?>(
                    Collections.nCopies<com.google.common.collect.ImmutableList<String?>?>(
                        ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS + 1,
                        com.google.common.collect.ImmutableList.of<String?>(
                            "Executing genrule //test:rule1", "Executing genrule //test:rule2"
                        )
                    )
                )
            )
            .inOrder()

        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>(),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //test:rule1"),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>(),  /* expectResultReceivedForFailedRewound= */
            false,  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(
                ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS + 1
            )
        )

        assertOnlyActionsRewound(rewoundKeys)
        Truth.assertThat(
            com.google.common.collect.Iterables.frequency(
                rewoundArtifactOwnerLabels(rewoundKeys),
                "//test:rule1"
            )
        )
            .isEqualTo(ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS)
    }

    /**
     * Create N genrules that are dependent on a static source file. And then create another N
     * genrules that will consume the previous genrules equal to its index from 1 to N. For example,
     * if N = 3, the first consume genrule will contain 'rule1', the second consume genrule will
     * contain 'rule1' and 'rule2', and the third consume genrule will contain 'rule1', 'rule2', and
     * 'rule3'. Lastly there is a genrule that will have an output: 'output.inlined' that contains all
     * N consume genrules as sources; this is used to assert that no output file remains after the
     * test case.
     */
    @Throws(IOException::class)
    private fun writeNGenrulePackages(n: Int) {
        val lines: MutableList<String?> = java.util.ArrayList<String?>()
        for (i in 1..n) {
            testCase.write("test/source_" + i + ".txt", "source_" + i)
            lines.add("genrule(")
            lines.add("    name = 'rule" + i + "',")
            lines.add("    srcs = ['source_" + i + ".txt'],")
            lines.add("    outs = ['out_" + i + ".txt'],")
            lines.add("    cmd = '(cat $(SRCS) && echo from rule" + i + ") > $@') ")
            lines.add("")
        }
        val outs: java.lang.StringBuilder = java.lang.StringBuilder()
        for (i in 1..n) {
            val out = " 'consume_" + i + ".out', "
            outs.append(out)
            val entries: java.lang.StringBuilder = java.lang.StringBuilder()
            for (e in 1..i) {
                entries.append("':out_").append(e).append(".txt', ")
            }
            lines.add("genrule(")
            lines.add("    name = 'consume_" + i + "',")
            lines.add("    srcs = [" + entries + "],")
            lines.add("    outs = [" + out + "],")
            lines.add("    cmd = '(cat $(SRCS) && echo from consume_" + i + ") > $@') ")
            lines.add("")
        }
        lines.add("genrule(")
        lines.add("    name = 'consume_output',")
        lines.add("    srcs = [" + outs + "],")
        lines.add("    outs = ['output.inlined'],")
        lines.add("    cmd = 'touch $@')")
        val writeLines = arrayOfNulls<String>(lines.size)
        for (i in lines.indices) {
            writeLines[i] = lines.get(i)
        }
        testCase.write("test/BUILD", *writeLines)
    }

    /**
     * This test sets up [ActionRewindStrategy.MAX_ACTION_REWIND_EVENTS] + 1 (N) genrules that
     * consume 1 ... N inputs respectively and will build each of the genrules. All N inputs will be
     * lost and throw a [LostInputsExecException] such that all of the genrule actions will
     * rewind. The [PostableActionRewindingStats] event will contain the top [ ][ActionRewindStrategy.MAX_ACTION_REWIND_EVENTS] action rewind events based on the maximum number
     * of nodes invalidated for each rewind action plan. The expected action rewind events logged will
     * not contain the genrule action with one input.
     */
    @Throws(java.lang.Exception::class)
    fun runMultipleLostInputsForRewindPlan() {
        writeNGenrulePackages(ActionRewindStrategy.MAX_ACTION_REWIND_EVENTS + 1)
        for (i in 1..ActionRewindStrategy.MAX_ACTION_REWIND_EVENTS + 1) {
            val target = i
            addSpawnShim(
                "Executing genrule //test:consume_" + target,
                SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                    val inputMap: com.google.common.collect.ImmutableSetMultimap.Builder<String?, ActionInput?> =
                        com.google.common.collect.ImmutableSetMultimap.builder<String?, ActionInput?>()
                    for (e in 1..target) {
                        val input: ActionInput = SpawnInputUtils.getInputWithName(spawn, "out_" + e + ".txt")
                        inputMap.put("fake_digest_" + target + "_" + e, input)
                    }
                    ExecResult.ofException(LostInputsExecException(inputMap.build()))
                })
        }
        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        testCase.buildTarget(
            *IntStream.rangeClosed(1, ActionRewindStrategy.MAX_ACTION_REWIND_EVENTS + 1)
                .mapToObj<String?>(java.util.function.IntFunction { i: Int -> "//test:consume_" + i })
                .toArray<String?> { _Dummy_.__Array__() })
        assertOnlyActionsRewound(rewoundKeys)
        verifyAllSpawnShimsConsumed()
        recorder.assertTotalLostInputCountsFromStats(
            com.google.common.collect.ImmutableList.of<Int?>(
                (ActionRewindStrategy.MAX_ACTION_REWIND_EVENTS + 1)
                        * (ActionRewindStrategy.MAX_ACTION_REWIND_EVENTS + 2)
                        / 2
            )
        )
    }

    @Throws(java.lang.Exception::class)
    fun runInterruptedDuringRewindStopsNormally() {
        // This test sets up two genrules, and makes the first execution of rule2 fail, saying that the
        // file produced by rule1 is missing. Before rule1 is re-executed, the test interrupts the
        // build. The build should stop with an interrupt normally (and not crash).
        writeTwoGenrulePackage(testCase)

        addSpawnShim(
            "Executing genrule //test:rule2",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                addSpawnShim(
                    "Executing genrule //test:rule1",
                    SpawnShim { ignoredSpawn: Spawn?, ignoredContext: ActionExecutionContext? ->
                        java.lang.Thread.currentThread().interrupt()
                        ExecResult.delegate()
                    })
                val lostInputs: com.google.common.collect.ImmutableList<ActionInput> =
                    com.google.common.collect.ImmutableList.of<ActionInput>(
                        SpawnInputUtils.getInputWithName(
                            spawn,
                            "intermediate.txt"
                        )
                    )
                createLostInputsExecException(context, lostInputs)
            })

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { testCase.buildTarget("//test:rule2") })

        assertOutputForStopBeforeRewoundReexecution()

        assertOnlyActionsRewound(rewoundKeys)
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//test:rule1")
    }

    private fun assertOutputForStopBeforeRewoundReexecution() {
        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactly(
                "Executing genrule //test:rule1",
                "Executing genrule //test:rule2",
                "Executing genrule //test:rule1"
            )
            .inOrder()

        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //test:rule1"),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>(),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>(),  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(1)
        )
        Truth.assertThat(
            recorder.getActionStartedEvents().stream()
                .map<String?> { e: ActionStartedEvent? -> ActionEventRecorder.progressMessageOrPrettyPrint(e.getAction()) }
                .filter { anObject: String? -> "Executing genrule //test:rule2".equals(anObject) }
                .count())
            .isEqualTo(1)
        Truth.assertThat(
            recorder.getActionCompletionEvents().stream()
                .map<String?> { e: ActionCompletionEvent? -> ActionEventRecorder.progressMessageOrPrettyPrint(e.getAction()) }
                .filter { anObject: String? -> "Executing genrule //test:rule2".equals(anObject) }
                .count())
            .isEqualTo(0)
        Truth.assertThat(
            recorder.getActionExecutedEvents().stream()
                .map<String?> { e: ActionExecutedEvent? -> ActionEventRecorder.progressMessageOrPrettyPrint(e.getAction()) }
                .filter { anObject: String? -> "Executing genrule //test:rule2".equals(anObject) }
                .count())
            .isEqualTo(0)
        Truth.assertThat(
            recorder.getActionResultReceivedEvents().stream()
                .map<String?> { e: ActionResultReceivedEvent? -> ActionEventRecorder.progressMessageOrPrettyPrint(e.getAction()) }
                .filter { anObject: String? -> "Executing genrule //test:rule2".equals(anObject) }
                .count())
            .isEqualTo(0)
        Truth.assertThat(
            recorder.getActionRewoundEvents().stream()
                .map<String?> { e: ActionRewoundEvent? ->
                    ActionEventRecorder.progressMessageOrPrettyPrint(
                        e.getFailedRewoundAction()
                    )
                }
                .filter { anObject: String? -> "Executing genrule //test:rule2".equals(anObject) }
                .count())
            .isEqualTo(1)
    }

    init {
        this.testCase = com.google.common.base.Preconditions.checkNotNull<BuildIntegrationTestCase>(testCase)
        this.recorder = com.google.common.base.Preconditions.checkNotNull<ActionEventRecorder>(recorder)
        this.lostOutputsModule = createLostOutputsModule()
    }

    @Throws(java.lang.Exception::class)
    fun runFailureDuringRewindStopsNormally() {
        // This test sets up two genrules, and makes the first execution of rule2 fail, saying that the
        // file produced by rule1 is missing. The execution of rule1 fails. The build should stop with
        // that failure (and not crash).
        writeTwoGenrulePackage(testCase)

        addSpawnShim(
            "Executing genrule //test:rule2",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                addSpawnShim(
                    "Executing genrule //test:rule1",
                    SpawnShim { ignoredSpawn: Spawn?, ignoredContext: ActionExecutionContext? ->
                        ExecResult.ofException(
                            SpawnExecException(
                                "kaboom",
                                FAILED_RESULT,  /* forciblyRunRemotely= */
                                false,  /* catastrophe= */
                                false
                            )
                        )
                    })
                val lostInputs: com.google.common.collect.ImmutableList<ActionInput> =
                    com.google.common.collect.ImmutableList.of<ActionInput>(
                        SpawnInputUtils.getInputWithName(
                            spawn,
                            "intermediate.txt"
                        )
                    )
                createLostInputsExecException(context, lostInputs)
            })

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        val buildFailedException: BuildFailedException? =
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { testCase.buildTarget("//test:rule2") })

        val errorDetail = "Executing genrule //test:rule1 failed: (Exit 1)"
        if (keepGoing()) {
            assertThat(buildFailedException).hasMessageThat().isNull()
        } else {
            assertThat(buildFailedException).hasMessageThat().contains(errorDetail)
        }
        testCase.assertContainsError(errorDetail)
        assertOutputForStopBeforeRewoundReexecution()
        assertOnlyActionsRewound(rewoundKeys)
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//test:rule1")
    }

    @Throws(java.lang.Exception::class)
    fun runIntermediateActionRewound() {
        // This test sets up three genrules, and makes the first execution of rule3 fail, saying that
        // the file produced by rule2 is missing but the file produced by rule1 is not.
        // Rule2 executes twice, consuming the file output from rule1 both times. Rule1 is not executed
        // a second time.
        testCase.write(
            "test/BUILD",
            """
        genrule(
            name = "rule1",
            srcs = ["source_1.txt"],
            outs = ["intermediate_1.txt"],
            cmd = "(cat ${'$'}< && echo from rule1) > ${'$'}@",
        )

        genrule(
            name = "rule2",
            srcs = [
                "intermediate_1.txt",
                "source_2.txt",
            ],
            outs = ["intermediate_2.txt"],
            cmd = "(cat ${'$'}(SRCS) && echo from rule2) > ${'$'}@",
        )

        genrule(
            name = "rule3",
            srcs = [
                "intermediate_1.txt",
                "intermediate_2.txt",
                "source_3.txt",
            ],
            outs = ["output.inlined"],
            cmd = "(cat ${'$'}(SRCS) && echo from rule3) > ${'$'}@",
        )

        genrule(
            name = "consume_output",
            srcs = [":output.inlined"],
            outs = ["dummy.out"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )

        testCase.write("test/source_1.txt", "source_1")
        testCase.write("test/source_2.txt", "source_2")
        testCase.write("test/source_3.txt", "source_3")

        addSpawnShim(
            "Executing genrule //test:rule3",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                createLostInputsExecException(
                    spawn,
                    context,
                    "intermediate_2.txt"
                )
            })

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        val outputFileContents = buildAndGetOutput("test", testCase)

        Truth.assertThat(outputFileContents)
            .isEqualTo(
                """
            source_1
            from rule1
            source_1
            from rule1
            source_2
            from rule2
            source_3
            from rule3
            
            """.trimIndent()
            )

        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactly(
                "Executing genrule //test:rule1",
                "Executing genrule //test:rule2",
                "Executing genrule //test:rule3",
                "Executing genrule //test:rule2",
                "Executing genrule //test:rule3",
                "Executing genrule //test:consume_output"
            )
            .inOrder()

        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //test:rule1"),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //test:rule2"),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //test:rule3"),  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(1)
        )

        assertOnlyActionsRewound(rewoundKeys)
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//test:rule2")
    }

    @Throws(java.lang.Exception::class)
    fun runChainOfActionsRewound() {
        // This test exercises recursive rewinding. It sets up three genrules that depend on each other
        // in a chain. Rule1 and rule2 execute successfully their first time. When rule3 executes, it
        // fails, saying that the file produced by rule2 is missing. When rule2 is executed for the
        // second time, it fails, saying that the file produced by rule1 is missing. Thereafter, all
        // executions succeed.
        testCase.write(
            "test/BUILD",
            """
        genrule(
            name = "rule1",
            srcs = ["source_1.txt"],
            outs = ["intermediate_1.txt"],
            cmd = "(cat ${'$'}< && echo from rule1) > ${'$'}@",
        )

        genrule(
            name = "rule2",
            srcs = [
                "intermediate_1.txt",
                "source_2.txt",
            ],
            outs = ["intermediate_2.txt"],
            cmd = "(cat ${'$'}(SRCS) && echo from rule2) > ${'$'}@",
        )

        genrule(
            name = "rule3",
            srcs = [
                "intermediate_2.txt",
                "source_3.txt",
            ],
            outs = ["output.inlined"],
            cmd = "(cat ${'$'}(SRCS) && echo from rule3) > ${'$'}@",
        )

        genrule(
            name = "consume_output",
            srcs = [":output.inlined"],
            outs = ["dummy.out"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )

        testCase.write("test/source_1.txt", "source_1")
        testCase.write("test/source_2.txt", "source_2")
        testCase.write("test/source_3.txt", "source_3")

        addSpawnShim(
            "Executing genrule //test:rule3",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                addSpawnShim(
                    "Executing genrule //test:rule2",
                    SpawnShim { otherSpawn: Spawn?, otherContext: ActionExecutionContext? ->
                        createLostInputsExecException(
                            otherSpawn,
                            otherContext,
                            "intermediate_1.txt"
                        )
                    })
                createLostInputsExecException(spawn, context, "intermediate_2.txt")
            })

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        val outputFileContents = buildAndGetOutput("test", testCase)

        Truth.assertThat(outputFileContents)
            .isEqualTo("source_1\nfrom rule1\nsource_2\nfrom rule2\nsource_3\nfrom rule3\n")

        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactly(
                "Executing genrule //test:rule1",
                "Executing genrule //test:rule2",
                "Executing genrule //test:rule3",
                "Executing genrule //test:rule2",
                "Executing genrule //test:rule1",
                "Executing genrule //test:rule2",
                "Executing genrule //test:rule3",
                "Executing genrule //test:consume_output"
            )
            .inOrder()

        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>(),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>(
                "Executing genrule //test:rule1", "Executing genrule //test:rule2"
            ),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //test:rule3"),  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(2)
        )

        assertOnlyActionsRewound(rewoundKeys)
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys))
            .containsExactly("//test:rule2", "//test:rule1")
    }

    @Throws(java.lang.Exception::class)
    fun runNondeterministicActionRewound() {
        // This test demonstrates that rewinding works when rewound actions are nondeterministic.
        //
        // A nondeterministic genrule, rule1, produces output which is lost. The genrule rule2 uses this
        // output and fails. The rewound nondeterministic action generates a new output. This test
        // asserts that rule2 uses the new output on its second try, by checking rule2's output when
        // it's used by rule3.

        testCase.write(
            "test/BUILD",
            """
        genrule(
            name = "rule1",
            srcs = ["source_1.txt"],
            outs = ["intermediate_1.inlined"],
            cmd = "(cat ${'$'}(location source_1.txt) && echo ${'$'}${'$'}RANDOM) > ${'$'}@",
            tags = ["no-cache"],
        )

        genrule(
            name = "rule2",
            srcs = [
                "source_2.txt",
                "intermediate_1.inlined",
            ],
            outs = ["intermediate_2.inlined"],
            cmd = "(cat ${'$'}(SRCS) && echo from rule2) > ${'$'}@",
        )

        genrule(
            name = "rule3",
            srcs = ["intermediate_2.inlined"],
            outs = ["output.txt"],
            cmd = "(cat ${'$'}< && echo from rule3) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        testCase.write("test/source_1.txt", "source_1")
        testCase.write("test/source_2.txt", "source_2")

        val intermediate1FirstContent: AtomicReference<String?> = AtomicReference<String?>(null)
        addSpawnShim(
            "Executing genrule //test:rule2",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                val intermediate1: ActionInput =
                    SpawnInputUtils.getInputWithName(spawn, "intermediate_1.inlined")
                intermediate1FirstContent.set(latin1StringFromActionInput(context, intermediate1))
                createLostInputsExecException(context, intermediate1)
            })

        val intermediate1SecondContent: AtomicReference<String?> = AtomicReference<String?>(null)
        addSpawnShim(
            "Executing genrule //test:rule2",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                val intermediate1: ActionInput =
                    SpawnInputUtils.getInputWithName(spawn, "intermediate_1.inlined")
                intermediate1SecondContent.set(latin1StringFromActionInput(context, intermediate1))
                ExecResult.delegate()
            })

        val intermediate2Content: AtomicReference<String?> = AtomicReference<String?>(null)
        addSpawnShim(
            "Executing genrule //test:rule3",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                val intermediate2: ActionInput =
                    SpawnInputUtils.getInputWithName(spawn, "intermediate_2.inlined")
                intermediate2Content.set(latin1StringFromActionInput(context, intermediate2))
                ExecResult.delegate()
            })

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        testCase.buildTarget("//test:rule3")

        Truth.assertThat(intermediate1SecondContent.get()).isNotEqualTo(intermediate1FirstContent.get())
        Truth.assertThat(intermediate2Content.get())
            .isEqualTo(String.format("source_2\n%sfrom rule2\n", intermediate1SecondContent.get()))
        verifyAllSpawnShimsConsumed()
        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactly(
                "Executing genrule //test:rule1",
                "Executing genrule //test:rule2",
                "Executing genrule //test:rule1",
                "Executing genrule //test:rule2",
                "Executing genrule //test:rule3"
            )
            .inOrder()

        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //test:rule3"),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //test:rule1"),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //test:rule2"),  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(1)
        )

        assertOnlyActionsRewound(rewoundKeys)
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//test:rule1")
    }

    @Throws(IOException::class)
    private fun setUpParallelTrackSharedActionPackage() {
        testCase.write(
            "shared/shared.bzl",
            "def _shared_impl(ctx):",
            "    in_file = ctx.file.src",
            "    a_shared_out = ctx.actions.declare_file('A-shared.out')",
            "    ctx.actions.run_shell(",
            "        inputs = [in_file],",
            "        outputs = [a_shared_out],",
            "        progress_message = 'Copying %s input %s to A-shared.out' % (ctx.attr.name,"
                    + " in_file.short_path),",
            "        command = 'cp %s %s' % (in_file.path, a_shared_out.path),",
            "    )",
            "    b_shared_out = ctx.actions.declare_file('B-shared.out')",
            "    ctx.actions.run_shell(",
            "        inputs = [a_shared_out],",
            "        outputs = [b_shared_out],",
            "        progress_message = 'Copying A-shared.out to B-shared.out on behalf of %s' % "
                    + "(ctx.attr.name),",
            "        command = 'cp %s %s' % (a_shared_out.path, b_shared_out.path),",
            "    )",
            "    out = ctx.outputs.out",
            "    ctx.actions.run_shell(",
            "        inputs = [b_shared_out],",
            "        outputs = [out],",
            "        progress_message = 'Copying B-shared.out to %s output %s' % (ctx.attr.name,"
                    + " out.short_path),",
            "        command = 'cp %s %s' % (b_shared_out.path, out.path),",
            "    )",
            "    return [DefaultInfo(files = depset([out]))]",
            "",
            "shared = rule(",
            "    implementation = _shared_impl,",
            "    attrs = {",
            "        'src': attr.label(",
            "            mandatory = True,",
            "            allow_single_file = True,",
            "        ),",
            "        'out': attr.output(",
            "            mandatory = True",
            "        ),",
            "    }",
            ")"
        )
        testCase.write(
            "shared/BUILD",
            """
        load("//shared:shared.bzl", "shared")

        genrule(
            name = "shared_input",
            srcs = [],
            outs = ["shared_input.txt"],
            cmd = 'echo "hi i am a shared input" > ${'$'}@',
        )

        shared(
            name = "shared_1",
            src = "shared_input.txt",
            out = "shared_1.out",
        )

        shared(
            name = "shared_2",
            src = "shared_input.txt",
            out = "shared_2.out",
        )

        genrule(
            name = "merge_shared_rules",
            srcs = [
                "shared_1.out",
                "shared_2.out",
            ],
            outs = ["output.inlined"],
            cmd = "(cat ${'$'}(location shared_1.out) && cat ${'$'}(location shared_2.out)) > ${'$'}@",
        )

        genrule(
            name = "consume_output",
            srcs = [":output.inlined"],
            outs = ["dummy.out"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    fun runParallelTrackSharedActionsRewound() {
        // This test demonstrates that, given a pair of parallel sequences of shared actions like so:
        //
        //   1B   2B  (higher actions depend on lower actions)
        //   |    |
        //   1A   2A
        //
        // in which 1A and 2A are shared, 1B and 2B are shared, and xB depends on an output of xA,
        // when 1B rewinds because the 1A output it depends on is lost, and 2B ran simultaneously with
        // the first, failed, evaluation of 1B and registers itself as depending on 1B's completion
        // future, then 2B gets reset when 1B clears its ActionExecutionState. Re-evaluations of dep
        // actions may proceed non-deterministically, but this test makes 2A win the "rewound A" race,
        // and then 1B win the "rewound B" race.
        ensureMultipleJobs()
        setUpParallelTrackSharedActionPackage()

        addSpawnShim(
            "Copying A-shared.out to B-shared.out on behalf of shared_1",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                createLostInputsExecException(
                    spawn,
                    context,
                    "A-shared.out"
                )
            })
        addSpawnShim(
            "Copying A-shared.out to B-shared.out on behalf of shared_2",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                createLostInputsExecException(
                    spawn,
                    context,
                    "A-shared.out"
                )
            })

        // This code controls the evaluation of the shared actions belonging to shared_1 and shared_2
        // so that the following events occur in the specified order. Each non-final step is associated
        // with a latch which prevents the subsequent step from happening before the preceding step
        // happens.
        //
        // 1. shared_1's B-shared.out generating action (hereafter referred to as "shared_1B", and
        //    likewise for other actions) emits an ActionStartedEvent, discovers its input is lost,
        //    emits an ActionRewoundEvent, but does not yet clear its ActionExecutionState from
        //    SkyframeActionExecutor.
        val shared1BEmittedRewoundEvent: CountDownLatch = CountDownLatch(1)

        // 2. shared_2A coalesces with shared_1A's done ActionExecutionState. shared_2B coalesces with
        //    action_1B's not-done ActionExecutionState. It declares a Future dependency, and waits.
        val shared2BDeclaresFutureDep: CountDownLatch = CountDownLatch(1)

        // 3. shared_1B clears its ActionExecutionState from SkyframeActionExecutor, triggering
        //    shared_2B's re-evaluation. shared_1B also clears shared_1A's ActionExecutionState.
        //    shared_2B does not find a matching ActionExecutionState, proceeds with its own evaluation,
        //    and discovers its input is lost also (which would be realistic, given that neither
        //    shared_1A nor shared_2A have re-evaluated).
        //
        //    shared_2B clears its ActionExecutionState, attempts to clear any ActionExecutionState
        //    associated with shared_2A (but there is none), requests shared_2A's re-evaluation,
        //    shared_2A re-evaluates, and shared_2B is ready to evaluate for its fifth(*) time.
        //
        // (*) Count:
        //    1. before shared_2A is first evaluated
        //    2. after shared_2A is first evaluated
        //    3. reset by shared_1B's state clearing
        //    4. reset by its own rewinding, before shared_2A is again evaluated
        //    5. after shared_2A is again evaluated
        val shared2BReadyForFifthTime: CountDownLatch = CountDownLatch(1)

        // 4. shared_1A coalesces with the done ActionExecutionState from shared_2A's second evaluation,
        //    and shared_1B successfully re-evaluates.
        val shared1BDone: CountDownLatch = CountDownLatch(1)

        // 5. shared_2B coalesces with shared_1B's done ActionExecutionState, and the build successfully
        //    completes.
        val shared1ARewound: AtomicInteger = AtomicInteger(0)
        val shared2ARewound: AtomicInteger = AtomicInteger(0)
        val shared2AReady: AtomicInteger = AtomicInteger(0)
        val shared2BReady: AtomicInteger = AtomicInteger(0)
        testCase.injectListenerAtStartOfNextBuild(
            NotifyingHelper.Listener { key, type, order, context ->
                // Count the times shared_1{A,B} are rewound.
                if (type.equals(NotifyingHelper.EventType.MARK_DIRTY) && order.equals(NotifyingHelper.Order.AFTER)) {
                    val markDirtyAfterContext: MarkDirtyAfterContext =
                        context as MarkDirtyAfterContext
                    checkState(
                        markDirtyAfterContext.dirtyType.equals(DirtyType.REWIND),
                        "Unexpected DirtyType %s for key %s",
                        context,
                        key
                    )
                    checkState(key is ActionLookupData, "rewound key not an action: %s", key)
                    if (actionHasLabelAndIndex(key as ActionLookupData?, "shared_1", 0)) {
                        com.google.common.base.Preconditions.checkState(
                            shared1ARewound.incrementAndGet() == 1,
                            "shared_1A rewound twice"
                        )
                    } else if (actionHasLabelAndIndex(key as ActionLookupData, "shared_2", 0)) {
                        com.google.common.base.Preconditions.checkState(
                            shared2ARewound.incrementAndGet() == 1,
                            "shared_2A rewound twice"
                        )
                    } else {
                        throw java.lang.IllegalStateException(
                            java.lang.String.format("rewound key has unexpected address: %s", key)
                        )
                    }
                }

                if (type.equals(NotifyingHelper.EventType.IS_READY)
                    && key is ActionLookupData
                    && actionHasLabelAndIndex(key, "shared_2", 0)
                ) {
                    val shared2AReadiedCount: Int = shared2AReady.incrementAndGet()
                    if (shared2AReadiedCount == 1) {
                        shared1BEmittedRewoundEvent.await()
                    }
                }

                if (type.equals(NotifyingHelper.EventType.IS_READY)
                    && key is ActionLookupData
                    && actionHasLabelAndIndex(key, "shared_2", 1)
                ) {
                    val shared2BReadiedCount: Int = shared2BReady.incrementAndGet()
                    if (shared2BReadiedCount == 5) {
                        // Wait to attempt final evaluation of shared_2B until after shared_1B is done.
                        shared2BReadyForFifthTime.countDown()
                        shared1BDone.await()
                    }
                }

                // When shared_2B declares a future dep, allow shared_1B's Skyframe execution attempt to
                // clear its ActionExecutionState and reset its node.
                if (type.equals(NotifyingHelper.EventType.ADD_EXTERNAL_DEP)
                    && key is ActionLookupData
                    && actionHasLabelAndIndex(key, "shared_2", 1)
                ) {
                    shared2BDeclaresFutureDep.countDown()
                }

                // Wait to attempt the rewound evaluation of shared_1A until after shared_2A finishes its
                // rewound evaluation and shared_2B is ready again.
                if (type.equals(NotifyingHelper.EventType.IS_READY)
                    && key is ActionLookupData
                    && actionHasLabelAndIndex(key, "shared_1", 0)
                ) {
                    if (shared1ARewound.get() == 1) {
                        shared2BReadyForFifthTime.await()
                    }
                }
                if (type.equals(NotifyingHelper.EventType.SET_VALUE)
                    && key is ActionLookupData
                    && actionHasLabelAndIndex(key, "shared_1", 1)
                ) {
                    shared1BDone.countDown()
                }
            })

        recorder.setActionRewoundEventSubscriber(
            java.util.function.Consumer { rewoundEvent: ActionRewoundEvent? ->
                val progressMessage: String = rewoundEvent.getFailedRewoundAction().getProgressMessage()
                if (progressMessage == "Copying A-shared.out to B-shared.out on behalf of shared_1") {
                    shared1BEmittedRewoundEvent.countDown()
                    try {
                        shared2BDeclaresFutureDep.await()
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.IllegalStateException(e)
                    }
                }
            })

        val output = buildAndGetOutput("shared", testCase)

        Truth.assertThat(output).isEqualTo("hi i am a shared input\nhi i am a shared input\n")

        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactly(
                "Executing genrule //shared:shared_input",
                "Copying shared_1 input shared/shared_input.txt to A-shared.out",
                "Copying A-shared.out to B-shared.out on behalf of shared_1",
                "Copying A-shared.out to B-shared.out on behalf of shared_2",
                "Copying shared_2 input shared/shared_input.txt to A-shared.out",
                "Copying A-shared.out to B-shared.out on behalf of shared_1",
                "Copying B-shared.out to shared_1 output shared/shared_1.out",
                "Copying B-shared.out to shared_2 output shared/shared_2.out",
                "Executing genrule //shared:merge_shared_rules",
                "Executing genrule //shared:consume_output"
            )
        Truth.assertThat(shared1ARewound.get()).isEqualTo(1)
        Truth.assertThat(shared2ARewound.get()).isEqualTo(1)
    }

    @Throws(java.lang.Exception::class)
    fun runTreeFileArtifactRewound_spawnFailed() {
        runTreeFileArtifactRewound(
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                val lostInputs: com.google.common.collect.ImmutableList<ActionInput> =
                    getTreeFileArtifactRewoundLostInputs(spawn)
                createLostInputsExecException(context, lostInputs)
            })
    }

    @Throws(java.lang.Exception::class)
    fun runTreeFileArtifactRewound(shim: SpawnShim?) {
        // This test demonstrates that rewinding works when an action fails due to a lost input which is
        // a generated TreeFileArtifact that is directly depended on. To emphasize: the failed action
        // directly depends on a file *contained in the tree*, and does *not* directly depend on the
        // tree itself.
        //
        // The compilation action "Compiling tree/make_cc_dir.cc/file1.cc" fails, saying that
        // "make_cc_dir.cc/file1.cc", one of the output files in the tree outputted by the "make_cc"
        // rule, is lost. The action that generated that tree, "Action tree/make_cc_dir.cc", is rewound
        // along with the failed compilation action.
        //
        // This test also confirms that rewinding is compatible with critical-path tracking when a
        // non-shared action (like this test's compiling actions) fails and is run a second time.

        setUpTreeArtifactPackage(testCase)

        addSpawnShim("Compiling tree/make_cc_dir.cc/file1.cc", shim)

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        testCase.buildTarget("//tree:consumes_tree")

        verifyAllSpawnShimsConsumed()
        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactly(
                "Action tree/make_cc_dir.cc",
                "Compiling tree/make_cc_dir.cc/file1.cc",
                "Action tree/make_cc_dir.cc",
                "Compiling tree/make_cc_dir.cc/file1.cc",
                "Compiling tree/make_cc_dir.cc/file2.cc",
                "Compiling tree/source_2.cc",
                "Linking tree/libconsumes_tree.so",
                "Linking tree/libconsumes_tree.a"
            )

        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>(
                "Compiling tree/make_cc_dir.cc/file2.cc",
                "Linking tree/libconsumes_tree.so",
                "Linking tree/libconsumes_tree.a"
            ),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Action tree/make_cc_dir.cc"),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Compiling tree/make_cc_dir.cc/file1.cc"),  /* actionRewindingPostLostInputCounts= */

            com.google.common.collect.ImmutableList.of<Int?>(1)
        )

        Truth.assertThat(rewoundKeys).hasSize(1)
        assertActionKey(rewoundKeys.get(0), "//tree:make_cc",  /* index= */0)
    }

    @Throws(java.lang.Exception::class)
    fun runTreeArtifactRewound_allFilesLost_spawnFailed() {
        // This test demonstrates that rewinding works when an action fails due to a lost input which is
        // a generated TreeFileArtifact that is *indirectly* depended on. In contrast to what
        // testTreeFileArtifactRewound tests, in this test the failed action directly depends on the
        // tree, not the file contained in the tree.
        //
        // The linking action "Linking tree/libconsumes_tree.so" fails, saying that the "*.pic.o" files
        // produced by the compilation actions are lost. The linking action which failed is reset along
        // with those compilation actions.
        //
        // This test also confirms that rewinding is compatible with critical-path tracking when a
        // previously completed non-shared action (like this test's compiling actions) is rerun.

        val lostTreeFileArtifactNames: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("make_cc_dir/file1.pic.o", "make_cc_dir/file2.pic.o")

        val shim: SpawnShim =
            getTreeArtifactRewoundWhenTreeFilesLostSpawnFailedShim(lostTreeFileArtifactNames)

        runTreeArtifactRewoundWhenTreeFilesLost(lostTreeFileArtifactNames, shim)
    }

    @Throws(java.lang.Exception::class)
    fun runTreeArtifactRewound_oneFileLost_spawnFailed() {
        // This test is like runTreeArtifactRewound_allFilesLost_spawnFailed, except it loses only one
        // of the files in the tree that "Linking tree/libconsumes_tree.so" depends on. By doing so it
        // exercises the case when only a subset of a tree's files are lost.
        //
        // The linking action which failed is reset, and *all* the compilation actions whose outputs
        // are included by the tree are rewound.
        //
        // It would be better if only the compilation action responsible for the lost file was rewound,
        // but rewinding is expected to be uncommon, so the overkill effort shouldn't be a problem in
        // practice.

        val lostTreeFileArtifactNames: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("make_cc_dir/file1.pic.o")

        val shim: SpawnShim =
            getTreeArtifactRewoundWhenTreeFilesLostSpawnFailedShim(lostTreeFileArtifactNames)

        runTreeArtifactRewoundWhenTreeFilesLost(lostTreeFileArtifactNames, shim)
    }

    private fun getTreeArtifactRewoundWhenTreeFilesLostSpawnFailedShim(
        lostTreeFileArtifactNames: com.google.common.collect.ImmutableList<String?>
    ): SpawnShim {
        return SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
            val treeArtifact: Artifact? = getTreeArtifactRewoundWhenTreeFilesLostTree(spawn)
            val lostTreeFileArtifacts: com.google.common.collect.ImmutableList<ActionInput> =
                getTreeArtifactRewoundWhenTreeFilesLostInputs(
                    lostTreeFileArtifactNames, spawn, context, treeArtifact
                )
            createLostInputsExecException(context, lostTreeFileArtifacts)
        }
    }

    @Throws(java.lang.Exception::class)
    fun runTreeArtifactRewoundWhenTreeFilesLost(
        lostTreeFileArtifactNames: com.google.common.collect.ImmutableList<String?>, shim: SpawnShim?
    ) {
        setUpTreeArtifactPackage(testCase)

        addSpawnShim("Linking tree/libconsumes_tree.so", shim)

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        testCase.buildTarget("//tree:consumes_tree")
        verifyAllSpawnShimsConsumed()
        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactly(
                "Action tree/make_cc_dir.cc",
                "Compiling tree/make_cc_dir.cc/file1.cc",
                "Compiling tree/make_cc_dir.cc/file2.cc",
                "Compiling tree/source_2.cc",
                "Linking tree/libconsumes_tree.so",
                "Compiling tree/make_cc_dir.cc/file1.cc",
                "Compiling tree/make_cc_dir.cc/file2.cc",
                "Linking tree/libconsumes_tree.so",
                "Linking tree/libconsumes_tree.a"
            )

        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>(
                "Action tree/make_cc_dir.cc", "Linking tree/libconsumes_tree.a"
            ),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>(
                "Compiling tree/make_cc_dir.cc/file1.cc", "Compiling tree/make_cc_dir.cc/file2.cc"
            ),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Linking tree/libconsumes_tree.so"),  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(
                lostTreeFileArtifactNames.size
            )
        )

        Truth.assertThat(rewoundKeys).hasSize(3)
        val treeActionIndices: HashSet<Int?> = HashSet<Int?>(com.google.common.collect.ImmutableList.of<Int?>(0, 1))
        for (i in 0..1) {
            assertThat(rewoundKeys.get(i)).isInstanceOf(ActionLookupData::class.java)
            assertThat((rewoundKeys.get(i) as ActionLookupData).getLabel().getCanonicalForm())
                .isEqualTo("//tree:consumes_tree")
            Truth.assertThat(treeActionIndices.remove((rewoundKeys.get(i) as ActionLookupData).getActionIndex()))
                .isTrue()
        }
        assertArtifactKey(rewoundKeys.get(2), "tree/_pic_objs/consumes_tree/make_cc_dir")
    }

    @Throws(java.lang.Exception::class)
    fun runGeneratedRunfilesRewound_allFilesLost_spawnFailed() {
        // This test demonstrates that rewinding works when an action fails due to lost inputs which are
        // generated files in the action's runfiles. Rewinding must propagate across the runfiles tree
        // artifacts and actions associated with the runfiles.

        val lostRunfiles: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("gen1.dat", "gen2.dat")

        val shim: SpawnShim = getGeneratedRunfilesRewoundSpawnFailedShim(lostRunfiles)

        runGeneratedRunfilesRewound(lostRunfiles, shim)
    }

    @Throws(java.lang.Exception::class)
    fun runGeneratedRunfilesRewound_oneFileLost_spawnFailed() {
        // This test is like runGeneratedRunfilesRewound_allFilesLost_spawnFailed, except it loses only
        // one of the two generated runfiles that "Executing genrule //middle:tool_user" depends on.
        //
        // Like with runTreeArtifactRewound_oneFileLost_spawnFailed, it would be better if only the one
        // action responsible for the lost input was rewound, but rewinding is expected to be uncommon,
        // so the overkill effort isn't expected to be a problem in practice.

        val lostRunfiles: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("gen1.dat")

        val shim: SpawnShim = getGeneratedRunfilesRewoundSpawnFailedShim(lostRunfiles)

        runGeneratedRunfilesRewound(lostRunfiles, shim)
    }

    private fun getGeneratedRunfilesRewoundSpawnFailedShim(lostRunfiles: com.google.common.collect.ImmutableList<String?>): SpawnShim {
        return SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
            val lostRunfileArtifacts: com.google.common.collect.ImmutableList<ActionInput> =
                getGeneratedRunfilesRewoundLostRunfiles(lostRunfiles, spawn, context)
            createLostInputsExecException(context, lostRunfileArtifacts)
        }
    }

    @Throws(java.lang.Exception::class)
    protected fun mockFooBinary(relativePath: String?) {
        testCase.write(
            relativePath,
            """
        def _impl(ctx):
          symlink = ctx.actions.declare_file(ctx.label.name)
          ctx.actions.symlink(output = symlink, target_file = ctx.files.srcs[0],
            is_executable = True)
          files = depset(ctx.files.srcs)
          return [DefaultInfo(files = files, executable = symlink,
             runfiles = ctx.runfiles(transitive_files = files, collect_default = True))]
        foo_binary = rule(
          implementation = _impl,
          executable = True,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
            "deps": attr.label_list(),
            "data": attr.label_list(allow_files=True),
          },
        )
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    fun runGeneratedRunfilesRewound(lostRunfiles: com.google.common.collect.ImmutableList<String?>, shim: SpawnShim?) {
        mockFooBinary("middle/foo_binary.bzl")
        testCase.write(
            "middle/BUILD",
            """
        load(":foo_binary.bzl", "foo_binary")
        genrule(
            name = "gen1",
            srcs = [],
            outs = ["gen1.dat"],
            cmd = 'echo "made by gen1" > ${'$'}@',
        )

        genrule(
            name = "gen2",
            srcs = [],
            outs = ["gen2.dat"],
            cmd = 'echo "made by gen2" > ${'$'}@',
        )

        foo_binary(
            name = "tool",
            srcs = ["tool.sh"],
            data = [
                "gen1.dat",
                "gen2.dat",
                "source_1.txt",
            ],
        )

        genrule(
            name = "tool_user",
            srcs = [],
            outs = ["tool_user.out"],
            cmd = "touch ${'$'}(OUTS)",
            tools = ["tool"],
        )
        
        """.trimIndent()
        )
        testCase.write("middle/tool.sh", "#!/bin/bash").setExecutable(true)
        testCase.write("middle/source_1.txt", "source_1")

        addSpawnShim("Executing genrule //middle:tool_user", shim)

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        testCase.buildTarget("//middle:tool_user")
        verifyAllSpawnShimsConsumed()
        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactly(
                "Executing genrule //middle:gen1 [for tool]",
                "Executing genrule //middle:gen2 [for tool]",
                "Executing genrule //middle:tool_user",
                "Executing genrule //middle:gen1 [for tool]",
                "Executing genrule //middle:gen2 [for tool]",
                "Executing genrule //middle:tool_user"
            )

        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>(),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>(
                "Executing genrule //middle:gen1 [for tool]",
                "Executing genrule //middle:gen2 [for tool]"
            ),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //middle:tool_user"),  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(lostRunfiles.size)
        )

        if (buildRunfileManifests()) {
            Truth.assertThat(rewoundKeys).hasSize(6)
            val expectedRewoundGenrules: HashSet<String?> =
                HashSet<String?>(com.google.common.collect.ImmutableList.of<String?>("//middle:gen1", "//middle:gen2"))
            var i = 0
            var sourceManifestActionSeen = false
            while (i < 5) {
                assertThat(rewoundKeys.get(i)).isInstanceOf(ActionLookupData::class.java)
                val actionKey: ActionLookupData = rewoundKeys.get(i) as ActionLookupData
                val actionLabel: String = actionKey.getLabel().getCanonicalForm()
                i++
                if (actionLabel == "//middle:tool") {
                    when (actionKey.getActionIndex()) {
                        0 -> {}
                        1 -> sourceManifestActionSeen = true
                        2 -> Truth.assertThat(sourceManifestActionSeen).isTrue()
                        else -> org.junit.Assert.fail(
                            String.format(
                                "Unexpected action index. actionKey: %s, rewoundKeys: %s",
                                actionKey, rewoundKeys
                            )
                        )
                    }
                } else {
                    Truth.assertThat(expectedRewoundGenrules.remove(actionLabel)).isTrue()
                }
            }

            assertActionKey(rewoundKeys.get(i++), "//middle:tool",  /* index= */3)
        } else {
            Truth.assertThat(rewoundKeys).hasSize(4)
            val expectedRewoundGenrules: HashSet<String?> =
                HashSet<String?>(com.google.common.collect.ImmutableList.of<String?>("//middle:gen1", "//middle:gen2"))
            var i = 0
            while (i < 3) {
                assertThat(rewoundKeys.get(i)).isInstanceOf(ActionLookupData::class.java)
                val actionKey: ActionLookupData = rewoundKeys.get(i) as ActionLookupData
                val actionLabel: String = actionKey.getLabel().getCanonicalForm()
                i++
                if (actionLabel == "//middle:tool") {
                    assertThat(actionKey.getActionIndex()).isEqualTo(0)
                } else {
                    Truth.assertThat(expectedRewoundGenrules.remove(actionLabel)).isTrue()
                }
            }

            assertActionKey(rewoundKeys.get(i++), "//middle:tool",  /* index= */1)
        }
    }

    @Throws(java.lang.Exception::class)
    fun runDupeDirectAndRunfilesDependencyRewound_spawnFailed() {
        val intermediate1FirstContent: AtomicReference<String?> = AtomicReference<String?>(null)
        val shim: SpawnShim =
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                val lostInput: ActionInput =
                    getDupeDirectAndRunfilesDependencyRewoundLostInput(spawn, context)
                intermediate1FirstContent.set(latin1StringFromActionInput(context, lostInput))
                createLostInputsExecException(context, lostInput)
            }
        runDupeDirectAndRunfilesDependencyRewound(intermediate1FirstContent, shim)
    }

    /**
     * Runs a test which demonstrates that rewinding works when a lost input is both directly depended
     * on and, via runfiles, indirectly depended on by an action. Rewinding must invalidate both paths
     * from the failed action to the rewound generating action.
     * 
     * 
     * This checks that the correct nodes were invalidated in the correct order. In particular, the
     * runfiles action and output artifact must have been invalidated after the artifact corresponding
     * to the lost input. Otherwise, their evaluation could race with the invalidation of the
     * generating action and its output artifact. If the runfiles nodes won, they could propagate
     * stale values for the lost input.
     */
    @Throws(java.lang.Exception::class)
    fun runDupeDirectAndRunfilesDependencyRewound(
        intermediate1FirstContent: AtomicReference<String?>, shim: SpawnShim?
    ) {
        mockFooBinary("test/foo_binary.bzl")
        testCase.write(
            "test/BUILD",
            """
        load(":foo_binary.bzl", "foo_binary")
        genrule(
            name = "rule1",
            srcs = [],
            outs = ["intermediate_1.inlined"],
            cmd = "echo ${'$'}${'$'}RANDOM > ${'$'}@",
            tags = ["no-cache"],
        )

        foo_binary(
            name = "tool",
            srcs = ["tool.sh"],
            data = ["intermediate_1.inlined"],
        )

        genrule(
            name = "rule2",
            srcs = [],
            outs = ["intermediate_2.inlined"],
            cmd = "(${'$'}(location tool) && cat ${'$'}(location intermediate_1.inlined) && " +
                  "echo from rule2) > ${'$'}@",
            tools = [
                "intermediate_1.inlined",
                "tool",
            ],
        )

        genrule(
            name = "rule3",
            srcs = ["intermediate_2.inlined"],
            outs = ["output.txt"],
            cmd = "(cat ${'$'}< && echo from rule3) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        testCase
            .write(
                "test/tool.sh",
                "#!/bin/bash",
                String.format(
                    "cat \${0}.runfiles/%s/test/intermediate_1.inlined", TestConstants.WORKSPACE_NAME
                ),
                "echo 'from tool'"
            )
            .setExecutable(true)

        addSpawnShim("Executing genrule //test:rule2", shim)

        val intermediate1SecondContent: AtomicReference<String?> = AtomicReference<String?>(null)
        addSpawnShim(
            "Executing genrule //test:rule2",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                val intermediate1: Artifact =
                    SpawnInputUtils.getRunfilesArtifactWithName(spawn, context, "intermediate_1.inlined")
                intermediate1SecondContent.set(latin1StringFromActionInput(context, intermediate1))
                ExecResult.delegate()
            })

        val intermediate2Content: AtomicReference<String?> = AtomicReference<String?>(null)
        addSpawnShim(
            "Executing genrule //test:rule3",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                val intermediate2: ActionInput =
                    SpawnInputUtils.getInputWithName(spawn, "intermediate_2.inlined")
                intermediate2Content.set(latin1StringFromActionInput(context, intermediate2))
                ExecResult.delegate()
            })

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        testCase.buildTarget("//test:rule3")

        Truth.assertThat(intermediate1SecondContent.get()).isNotEqualTo(intermediate1FirstContent.get())
        Truth.assertThat(intermediate2Content.get())
            .isEqualTo(
                String.format(
                    "%sfrom tool\n%sfrom rule2\n",
                    intermediate1SecondContent.get(), intermediate1SecondContent.get()
                )
            )
        verifyAllSpawnShimsConsumed()
        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactly(
                "Executing genrule //test:rule1 [for tool]",
                "Executing genrule //test:rule2",
                "Executing genrule //test:rule1 [for tool]",
                "Executing genrule //test:rule2",
                "Executing genrule //test:rule3"
            )
            .inOrder()

        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //test:rule3"),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //test:rule1 [for tool]"),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //test:rule2"),  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(1)
        )

        if (buildRunfileManifests()) {
            Truth.assertThat(rewoundKeys).hasSize(5)
            var sourceManifestActionSeen = false
            for (i in 0..3) {
                assertThat(rewoundKeys.get(i)).isInstanceOf(ActionLookupData::class.java)
                val actionKey: ActionLookupData = rewoundKeys.get(i) as ActionLookupData
                val actionLabel: String = actionKey.getLabel().getCanonicalForm()
                if (actionLabel == "//test:tool") {
                    when (actionKey.getActionIndex()) {
                        0 -> {}
                        1 -> sourceManifestActionSeen = true
                        2 -> Truth.assertThat(sourceManifestActionSeen).isTrue()
                        else -> org.junit.Assert.fail(
                            String.format(
                                "Unexpected action index. actionKey: %s, rewoundKeys: %s",
                                actionKey, rewoundKeys
                            )
                        )
                    }
                } else {
                    Truth.assertThat(actionLabel).isEqualTo("//test:rule1")
                }
            }

            assertActionKey(rewoundKeys.get(4), "//test:tool",  /* index= */3)
        } else {
            Truth.assertThat(rewoundKeys).hasSize(3)
            var i = 0
            while (i < 2) {
                assertThat(rewoundKeys.get(i)).isInstanceOf(ActionLookupData::class.java)
                val actionKey: ActionLookupData = rewoundKeys.get(i) as ActionLookupData
                val actionLabel: String = actionKey.getLabel().getCanonicalForm()
                i++
                if (actionLabel == "//test:tool") {
                    assertThat(actionKey.getActionIndex()).isEqualTo(0)
                } else {
                    Truth.assertThat(actionLabel).isEqualTo("//test:rule1")
                }
            }

            assertActionKey(rewoundKeys.get(i++), "//test:tool",  /* index= */1)
        }
    }

    @Throws(java.lang.Exception::class)
    fun runTreeInRunfilesRewound_spawnFailed() {
        val shim: SpawnShim =
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                val treeArtifact: Artifact = getTreeInRunfilesRewoundTree(spawn, context)
                val lostInputs: com.google.common.collect.ImmutableList<ActionInput> =
                    getTreeInRunfilesRewoundLostInputs(spawn, context, treeArtifact)
                createLostInputsExecException(context, lostInputs)
            }

        runTreeInRunfilesRewound(shim)
    }

    @Throws(java.lang.Exception::class)
    fun runTreeInRunfilesRewound(shim: SpawnShim?) {
        testCase.write(
            "middle/tree.bzl",
            """
        def _tree_impl(ctx):
            tree_artifact = ctx.actions.declare_directory(ctx.attr.name + "_dir")
            ctx.actions.run_shell(
                inputs = ctx.files.srcs,
                outputs = [tree_artifact],
                command = '(echo "tree1" > ${'$'}1/gen1.out) && (echo "tree2" > ${'$'}1/gen2.out)',
                arguments = [tree_artifact.path],
            )
            return DefaultInfo(
                files = depset(direct = [tree_artifact]),
                runfiles = ctx.runfiles(files = [tree_artifact]),
            )

        tree = rule(
            implementation = _tree_impl,
            attrs = {"srcs": attr.label_list(allow_files = True)},
        )
        
        """.trimIndent()
        )
        mockFooBinary("middle/foo_binary.bzl")
        testCase.write(
            "middle/BUILD",
            """
        load(":tree.bzl", "tree")
        load(":foo_binary.bzl", "foo_binary")

        tree(
            name = "gen_tree",
            srcs = ["source_1.txt"],
        )

        foo_binary(
            name = "tool",
            srcs = ["tool.sh"],
            data = [
                "source_2.txt",
                ":gen_tree",
            ],
        )

        genrule(
            name = "tool_user",
            srcs = [],
            outs = ["tool_user.out"],
            cmd = "touch ${'$'}(OUTS)",
            tools = ["tool"],
        )
        
        """.trimIndent()
        )
        testCase.write("middle/tool.sh", "#!/bin/bash").setExecutable(true)
        testCase.write("middle/source_1.txt", "source_1")
        testCase.write("middle/source_2.txt", "source_2")

        addSpawnShim("Executing genrule //middle:tool_user", shim)

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        testCase.buildTarget("//middle:tool_user")
        verifyAllSpawnShimsConsumed()
        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactly(
                "Action middle/gen_tree_dir [for tool]",
                "Executing genrule //middle:tool_user",
                "Action middle/gen_tree_dir [for tool]",
                "Executing genrule //middle:tool_user"
            )
            .inOrder()

        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>(),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Action middle/gen_tree_dir [for tool]"),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //middle:tool_user"),  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(2)
        )

        if (buildRunfileManifests()) {
            Truth.assertThat(rewoundKeys).hasSize(6)
            var i = 0
            var sourceManifestActionSeen = false
            while (i < 5) {
                assertThat(rewoundKeys.get(i)).isInstanceOf(ActionLookupData::class.java)
                val actionKey: ActionLookupData = rewoundKeys.get(i) as ActionLookupData
                val actionLabel: String = actionKey.getLabel().getCanonicalForm()
                i++
                if (actionLabel == "//middle:tool") {
                    when (actionKey.getActionIndex()) {
                        0 -> {}
                        1 -> sourceManifestActionSeen = true
                        2 -> Truth.assertThat(sourceManifestActionSeen).isTrue()
                        else -> org.junit.Assert.fail(
                            String.format(
                                "Unexpected action index. actionKey: %s, rewoundKeys: %s",
                                actionKey, rewoundKeys
                            )
                        )
                    }
                } else {
                    Truth.assertThat(actionLabel).isEqualTo("//middle:gen_tree")
                    assertArtifactKey(rewoundKeys.get(i), "middle/gen_tree_dir")
                    i++
                }
            }

            assertActionKey(rewoundKeys.get(i++), "//middle:tool",  /* index= */3)
        } else {
            Truth.assertThat(rewoundKeys).hasSize(4)
            var i = 0
            while (i < 3) {
                assertThat(rewoundKeys.get(i)).isInstanceOf(ActionLookupData::class.java)
                val actionKey: ActionLookupData = rewoundKeys.get(i) as ActionLookupData
                val actionLabel: String = actionKey.getLabel().getCanonicalForm()
                i++
                if (actionLabel == "//middle:tool") {
                    assertThat(actionKey.getActionIndex()).isEqualTo(0)
                } else {
                    Truth.assertThat(actionLabel).isEqualTo("//middle:gen_tree")
                    assertArtifactKey(rewoundKeys.get(i), "middle/gen_tree_dir")
                    i++
                }
            }

            assertActionKey(rewoundKeys.get(i++), "//middle:tool",  /* index= */1)
        }
    }

    /**
     * Regression test for b/181884247.
     * 
     * 
     * The action for `//test:consumer` has three inputs all generated by `//test:gen`.
     * However, the action's `depset` of inputs is arranged such that the three artifacts are
     * split among its children. This tests that rewinding properly handles the case of a requested
     * [ArtifactNestedSetKey] containing only some of the inputs for a particular generating
     * action.
     */
    @Throws(java.lang.Exception::class)
    fun runInputsFromSameGeneratingActionSplitAmongNestedSetChildren() {
        testCase.write(
            "test/defs.bzl",
            """
        def _consumer_impl(ctx):
            in1, in2, in3 = ctx.attr.three_output_genrule.files.to_list()
            out = ctx.actions.declare_file("consumer.out")
            ctx.actions.run_shell(
                outputs = [out],
                # Arrange the inputs such that they are split among the depset's children.
                inputs = depset([in1], transitive = [depset([in2, in3])]),
                command = "touch %s" % out.path,
                progress_message = "Running consumer",
            )
            return DefaultInfo(files = depset([out]))

        consumer = rule(
            implementation = _consumer_impl,
            attrs = {"three_output_genrule": attr.label(mandatory = True)},
        )
        
        """.trimIndent()
        )
        testCase.write(
            "test/BUILD",
            """
        load(":defs.bzl", "consumer")

        genrule(
            name = "gen",
            outs = [
                "gen.out1",
                "gen.out2",
                "gen.out3",
            ],
            cmd = "touch ${'$'}(OUTS)",
        )

        consumer(
            name = "consumer",
            three_output_genrule = ":gen",
        )
        
        """.trimIndent()
        )

        addSpawnShim(
            "Running consumer",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                createLostInputsExecException(
                    spawn,
                    context,
                    "gen.out1"
                )
            })
        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()

        testCase.buildTarget("//test:consumer")

        Truth.assertThat(rewoundKeys).hasSize(1)
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//test:gen")
        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactly(
                "Executing genrule //test:gen",
                "Running consumer",
                "Executing genrule //test:gen",
                "Running consumer"
            )
            .inOrder()
        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>(),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //test:gen"),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Running consumer"),  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(1)
        )
    }

    @Throws(java.lang.Exception::class)
    fun runGeneratedHeaderRewound_lostInInputDiscovery_spawnFailed() {
        val shim: SpawnShim =
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                val header: ActionInput = getGeneratedHeaderRewoundLostInput(spawn)
                createLostInputsExecException(context, header)
            }

        runGeneratedHeaderRewound_lostInInputDiscovery(shim)
    }

    @Throws(java.lang.Exception::class)
    fun runGeneratedHeaderRewound_lostInInputDiscovery(shim: SpawnShim?) {
        // This test checks that rewinding works when the lost input is a generated header, and the loss
        // is found by remote include scanning, which happens in input discovery.
        writeGeneratedHeaderDirectDepPackage(testCase)

        addSpawnShim("Extracting include lines from blaze-out/k8-fastbuild/bin/genheader/gen.h", shim)

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        testCase.buildTarget("//genheader:consumes_header")
        verifyAllSpawnShimsConsumed()

        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactlyElementsIn(
                filterExecutedSpawnDescriptions(
                    "Executing genrule //genheader:gen_header",
                    "Extracting include lines from genheader/consumes.cc",
                    "Extracting include lines from tools/cpp/malloc.cc",
                    "Compiling tools/cpp/malloc.cc",
                    "Extracting include lines from tools/cpp/linkextra.cc",
                    "Compiling tools/cpp/linkextra.cc",
                    "Extracting include lines from blaze-out/k8-fastbuild/bin/genheader/gen.h",
                    "Executing genrule //genheader:gen_header",
                    "Extracting include lines from blaze-out/k8-fastbuild/bin/genheader/gen.h",
                    "Compiling genheader/consumes.cc",
                    "Linking genheader/consumes_header"
                )
            )

        // Input discovery actions do not result in action lifecycle events. E.g., the "Extracting
        // [...]" action is run, but results in no ActionStartedEvent/ActionCompletionEvent/etc.
        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>(
                "Linking genheader/consumes_header", "Compiling genheader/consumes.cc"
            ),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //genheader:gen_header"),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>(),  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(1)
        )

        assertOnlyActionsRewound(rewoundKeys)
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//genheader:gen_header")
    }

    @Throws(java.lang.Exception::class)
    fun runGeneratedHeaderRewound_lostInActionExecution_spawnFailed() {
        val shim: SpawnShim =
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                val header: ActionInput = getGeneratedHeaderRewoundLostInput(spawn)
                createLostInputsExecException(context, header)
            }

        runGeneratedHeaderRewound_lostInActionExecution(shim)
    }

    @Throws(java.lang.Exception::class)
    fun runGeneratedHeaderRewound_lostInActionExecution(shim: SpawnShim?) {
        // This test checks that rewinding works when the lost input is a generated header, and the loss
        // is found during action execution (after input discovery)
        //
        // This test also confirms that rewinding is compatible with critical-path tracking when a
        // non-shared action (like this test's compiling action) fails and is run a second time.
        writeGeneratedHeaderDirectDepPackage(testCase)

        addSpawnShim("Compiling genheader/consumes.cc", shim)

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        testCase.buildTarget("//genheader:consumes_header")
        verifyAllSpawnShimsConsumed()
        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactlyElementsIn(
                filterExecutedSpawnDescriptions(
                    "Executing genrule //genheader:gen_header",
                    "Extracting include lines from genheader/consumes.cc",
                    "Extracting include lines from tools/cpp/malloc.cc",
                    "Compiling tools/cpp/malloc.cc",
                    "Extracting include lines from tools/cpp/linkextra.cc",
                    "Compiling tools/cpp/linkextra.cc",
                    "Extracting include lines from blaze-out/k8-fastbuild/bin/genheader/gen.h",
                    "Compiling genheader/consumes.cc",
                    "Executing genrule //genheader:gen_header",
                    "Compiling genheader/consumes.cc",
                    "Linking genheader/consumes_header"
                )
            )

        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>("Linking genheader/consumes_header"),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //genheader:gen_header"),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Compiling genheader/consumes.cc"),  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(1)
        )

        assertOnlyActionsRewound(rewoundKeys)
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//genheader:gen_header")
    }

    @Throws(java.lang.Exception::class)
    fun runGeneratedTransitiveHeaderRewound_lostInInputDiscovery_spawnFailed() {
        val shim: SpawnShim =
            SpawnShim { discoverySpawn: Spawn?, discoveryContext: ActionExecutionContext? ->
                val header: ActionInput = getGeneratedHeaderRewoundLostInput(discoverySpawn)
                createLostInputsExecException(discoveryContext, header)
            }

        runGeneratedTransitiveHeaderRewound_lostInInputDiscovery(shim)
    }

    @Throws(java.lang.Exception::class)
    fun runGeneratedTransitiveHeaderRewound_lostInInputDiscovery(shim: SpawnShim?) {
        // Like runGeneratedHeaderRewound_lostInInputDiscovery, this test checks that rewinding works
        // when the lost input is a generated header, except in this test, the header is indirectly
        // depended on.
        //
        // Note that only the target-graph dependency is indirect (i.e. the dependency between
        // ":consumes_header" and ":gen.h"). The Skyframe node corresponding to the compiling action of
        // ":consumes_header" directly depends on the "gen.h" artifact, though that dependency is
        // discovered during execution.
        writeGeneratedHeaderIndirectDepPackage(testCase)

        addSpawnShim("Extracting include lines from blaze-out/k8-fastbuild/bin/genheader/gen.h", shim)

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        testCase.buildTarget("//genheader:consumes_header")
        verifyAllSpawnShimsConsumed()

        // Note that because intermediate.cc does not have an include directive for gen.h, the
        // "Extracting [...]/gen.h" action is first attempted just prior to the first attempt of
        // "Compiling genheader/consumes.cc".
        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactly(
                "Executing genrule //genheader:gen_header",
                "Extracting include lines from genheader/intermediate.cc",
                "Extracting include lines from genheader/consumes.cc",
                "Extracting include lines from tools/cpp/malloc.cc",
                "Compiling tools/cpp/malloc.cc",
                "Extracting include lines from tools/cpp/linkextra.cc",
                "Compiling tools/cpp/linkextra.cc",
                "Compiling genheader/intermediate.cc",
                "Extracting include lines from blaze-out/k8-fastbuild/bin/genheader/gen.h",
                "Executing genrule //genheader:gen_header",
                "Extracting include lines from blaze-out/k8-fastbuild/bin/genheader/gen.h",
                "Compiling genheader/consumes.cc",
                "Linking genheader/consumes_header"
            )

        // Input discovery actions do not result in action lifecycle events. E.g., the "Extracting
        // [...]" action is run, but results in no ActionStartedEvent/ActionCompletionEvent/etc.
        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>(
                "Compiling genheader/intermediate.cc",
                "Compiling genheader/consumes.cc",
                "Linking genheader/consumes_header"
            ),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //genheader:gen_header"),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>(),  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(1)
        )

        assertOnlyActionsRewound(rewoundKeys)
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//genheader:gen_header")
    }

    @Throws(java.lang.Exception::class)
    fun runGeneratedTransitiveHeaderRewound_lostInActionExecution_spawnFailed() {
        val shim: SpawnShim =
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                val header: ActionInput = getGeneratedHeaderRewoundLostInput(spawn)
                createLostInputsExecException(context, header)
            }

        runGeneratedTransitiveHeaderRewound_lostInActionExecution(shim)
    }

    @Throws(java.lang.Exception::class)
    fun runGeneratedTransitiveHeaderRewound_lostInActionExecution(shim: SpawnShim?) {
        // Like runGeneratedHeaderRewound_lostInActionExecution, this test checks that rewinding works
        // when the lost input is a generated header, except in this test, the header is indirectly
        // depended on.
        //
        // Note that only the target-graph dependency is indirect (i.e. the dependency between
        // ":consumes_header" and ":gen.h"). The Skyframe node corresponding to the compiling action of
        // ":consumes_header" directly depends on the "gen.h" artifact, though that dependency is
        // discovered during execution.
        writeGeneratedHeaderIndirectDepPackage(testCase)

        addSpawnShim("Compiling genheader/consumes.cc", shim)

        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        testCase.buildTarget("//genheader:consumes_header")
        verifyAllSpawnShimsConsumed()
        Truth.assertThat(this.executedSpawnDescriptions)
            .containsExactlyElementsIn(
                filterExecutedSpawnDescriptions(
                    "Executing genrule //genheader:gen_header",
                    "Extracting include lines from genheader/intermediate.cc",
                    "Extracting include lines from tools/cpp/malloc.cc",
                    "Compiling tools/cpp/malloc.cc",
                    "Extracting include lines from tools/cpp/linkextra.cc",
                    "Compiling tools/cpp/linkextra.cc",
                    "Extracting include lines from genheader/consumes.cc",
                    "Compiling genheader/intermediate.cc",
                    "Extracting include lines from blaze-out/k8-fastbuild/bin/genheader/gen.h",
                    "Compiling genheader/consumes.cc",
                    "Executing genrule //genheader:gen_header",
                    "Compiling genheader/consumes.cc",
                    "Linking genheader/consumes_header"
                )
            )

        recorder.assertEvents( /* runOnce= */
            com.google.common.collect.ImmutableList.of<String?>(
                "Linking genheader/consumes_header", "Compiling genheader/intermediate.cc"
            ),  /* completedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Executing genrule //genheader:gen_header"),  /* failedRewound= */
            com.google.common.collect.ImmutableList.of<String?>("Compiling genheader/consumes.cc"),  /* actionRewindingPostLostInputCounts= */
            com.google.common.collect.ImmutableList.of<Int?>(1)
        )

        assertOnlyActionsRewound(rewoundKeys)
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//genheader:gen_header")
    }

    /**
     * Regression test for b/242179728.
     * 
     * 
     * Exercises a scenario where a failing action depends on another action which is rewound
     * between the time that the action fails and the dep is looked up for signaling. The order of
     * events in this scenario (synchronized so that they execute sequentially) is:
     * 
     * 
     *  1. `//foo:dep` executes successfully and produces two outputs, `dep.out1` and
     * `dep.out2`.
     *  1. `//foo:fail`, which depends on `dep.out1`, executes and fails due to a
     * regular action execution failure (not a lost input).
     *  1. `//foo:other`, which depends on `dep.out2`, executes and observes a lost
     * input. `//foo:dep` is rewound.
     *  1. `//foo:fail` looks up `//foo:dep` for [Reason.RDEP_ADDITION] and
     * observes it to be dirty.
     * 
     */
    @Throws(java.lang.Exception::class)
    fun runDoneToDirtyDepForNodeInError() {
        ensureMultipleJobs()
        testCase.write(
            "foo/BUILD",
            """
        genrule(
            name = "other",
            srcs = [":dep.out2"],
            outs = ["other.out"],
            cmd = "cp ${'$'}< ${'$'}@",
        )

        genrule(
            name = "fail",
            srcs = [":dep.out1"],
            outs = ["fail.out"],
            cmd = "false",
        )

        genrule(
            name = "dep",
            outs = [
                "dep.out1",
                "dep.out2",
            ],
            cmd = "touch ${'$'}(OUTS)",
        )
        
        """.trimIndent()
        )
        val depDone: CountDownLatch = CountDownLatch(1)
        val failExecuting: CountDownLatch = CountDownLatch(1)
        val depRewound: CountDownLatch = CountDownLatch(1)
        val fail: Label = Label.parseCanonicalUnchecked("//foo:fail")
        val dep: Label = Label.parseCanonicalUnchecked("//foo:dep")
        addSpawnShim(
            "Executing genrule //foo:fail",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                failExecuting.countDown()
                ExecResult.delegate()
            })
        addSpawnShim(
            "Executing genrule //foo:other",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                createLostInputsExecException(
                    spawn,
                    context,
                    "dep.out2"
                )
            })
        testCase.injectListenerAtStartOfNextBuild(
            NotifyingHelper.Listener { key, type, order, context ->
                if (isActionExecutionKey(key, fail) && type === NotifyingHelper.EventType.CREATE_IF_ABSENT) {
                    depDone.await()
                } else if (isActionExecutionKey(key, dep)
                    && type === NotifyingHelper.EventType.SET_VALUE && order === NotifyingHelper.Order.AFTER
                ) {
                    depDone.countDown()
                } else if (isActionExecutionKey(key, dep)
                    && type === NotifyingHelper.EventType.ADD_REVERSE_DEP && order === NotifyingHelper.Order.BEFORE && isActionExecutionKey(
                        context,
                        fail
                    )
                ) {
                    depRewound.await()
                } else if (isActionExecutionKey(key, dep)
                    && type === NotifyingHelper.EventType.MARK_DIRTY && order === NotifyingHelper.Order.AFTER
                ) {
                    depRewound.countDown()
                }
            })

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { testCase.buildTarget("//foo:all") })
        testCase.assertContainsError("Executing genrule //foo:fail failed")
    }

    /**
     * Tests handling of an action that is rewound and completes with an error in between the time
     * that a second action declares a dependency on it and consumes it during input checking, where
     * the second action depends on the lost input indirectly (via an [ArtifactNestedSetKey]).
     * 
     * 
     * Targets in this test:
     * 
     * 
     *  * `:flaky_lost`: initially executes successfully, but then gets rewound and completes
     * with an error.
     *  * `:top1`: initiates rewinding on `:flaky_lost`.
     *  * `:top2`: depends indirectly on `:flaky_lost` and observes it as an undone
     * input.
     * 
     * 
     * 
     * Order of events in this test:
     * 
     * 
     *  1. `:top2` requests its inputs from Skyframe, including an [       ] containing `flaky_lost.out`. It is not done, so `:top2`
     * needs a Skyframe restart.
     *  1. The [ArtifactNestedSetKey] containing `flaky_lost.out` completes
     * successfully.
     *  1. `:top2` resumes after the Skyframe restart.
     *  1. `:top1` observes `flaky_lost.out` to be a lost input and rewinds `:flaky_lost`.
     *  1. `:flaky_lost` executes a second time, and this time the action fails.
     *  1. `:top2` has no missing direct deps, but cannot look up `flaky_lost.out`
     * because its generating action failed. In order to propagate a valid root cause, it
     * initiates rewinding of the [ArtifactNestedSetKey].
     * 
     */
    @Throws(java.lang.Exception::class)
    fun runFlakyActionFailsAfterRewind_raceWithIndirectConsumer_undoneDuringInputChecking() {
        ensureMultipleJobs()
        testCase.write(
            "foo/defs.bzl",
            """
        def _action_with_indirect_input(ctx):
            other1 = ctx.actions.declare_file("other1")
            ctx.actions.write(other1, "")
            other2 = ctx.actions.declare_file("other2")
            ctx.actions.write(other2, "")

            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            indirect_input = ctx.file.indirect_input
            ctx.actions.run_shell(
                inputs = depset([other1], transitive = [depset([other2, indirect_input])]),
                outputs = [out],
                command = "cat ${'$'}1 ${'$'}2 ${'$'}3 > ${'$'}4",
                arguments = [other1.path, other2.path, indirect_input.path, out.path],
            )
            return DefaultInfo(files = depset([out]))

        action_with_indirect_input = rule(
            implementation = _action_with_indirect_input,
            attrs = {"indirect_input": attr.label(allow_single_file = True)},
        )
        
        """.trimIndent()
        )
        testCase.write(
            "foo/BUILD",
            """
        load(":defs.bzl", "action_with_indirect_input")

        action_with_indirect_input(
            name = "top2",
            indirect_input = ":flaky_lost",
        )

        genrule(
            name = "top1",
            srcs = [":flaky_lost"],
            outs = ["top1.out"],
            cmd = "cp ${'$'}< ${'$'}@",
        )

        genrule(
            name = "flaky_lost",
            outs = ["flaky_lost.out"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
        val top2RestartedWithDoneNestedSet: CountDownLatch = CountDownLatch(1)
        val errorSet: CountDownLatch = CountDownLatch(1)
        addSpawnShim(
            "Executing genrule //foo:top1",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                top2RestartedWithDoneNestedSet.await()
                addSpawnShim(
                    "Executing genrule //foo:flaky_lost",
                    SpawnShim { spawn2: Spawn?, context2: ActionExecutionContext? ->
                        ExecResult.ofException(
                            SpawnExecException(
                                "Flaky action failure",
                                FAILED_RESULT,  /* forciblyRunRemotely= */
                                false,  /* catastrophe= */
                                false
                            )
                        )
                    })
                createLostInputsExecException(spawn, context, "flaky_lost.out")
            })

        testCase.injectListenerAtStartOfNextBuild(
            NotifyingHelper.Listener { key, type, order, context ->
                if (key is ArtifactNestedSetKey
                    && type === NotifyingHelper.EventType.GET_BATCH && order === NotifyingHelper.Order.BEFORE && context === Reason.PREFETCH
                ) {
                    top2RestartedWithDoneNestedSet.countDown()
                    // This needs to be uninterruptible to exercise the desired scenario in the
                    // --nokeep_going case.
                    com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly(errorSet)
                } else if (isActionExecutionKey(key, Label.parseCanonicalUnchecked("//foo:flaky_lost"))
                    && type === NotifyingHelper.EventType.SET_VALUE && order === NotifyingHelper.Order.AFTER && ValueWithMetadata.getMaybeErrorInfo(
                        context as SkyValue?
                    ) != null
                ) {
                    errorSet.countDown()
                }
            })

        val top2: Label? = Label.parseCanonical("//foo:top2")
        val top1: Label? = Label.parseCanonical("//foo:top1")
        val flakyLost: Label? = Label.parseCanonical("//foo:flaky_lost")

        val targetCompleteEvents: MutableMap<Label?, TargetCompleteEvent?> = recordTargetCompleteEvents()
        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { testCase.buildTarget("//foo:top1", "//foo:top2") })
        verifyAllSpawnShimsConsumed()
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//foo:flaky_lost")

        // Check that TargetCompleteEvents were posted with the correct root cause.
        if (keepGoing()) {
            Truth.assertThat(targetCompleteEvents.keys).containsExactly(top1, top2)
        } else {
            Truth.assertThat(targetCompleteEvents).hasSize(1)
            Truth.assertThat(targetCompleteEvents.keys).containsAnyOf(top1, top2)
        }
        targetCompleteEvents.forEach { (target: Label?, event: TargetCompleteEvent?) ->
            Truth.assertWithMessage("%s", target)
                .that(event.getRootCauses().getSingleton().getLabel())
                .isEqualTo(flakyLost)
        }

        // Trying again irons out the flaky failure with no rewinding.
        rewoundKeys.clear()
        targetCompleteEvents.clear()
        testCase.buildTarget("//foo:top1", "//foo:top2")
        Truth.assertThat(rewoundKeys).isEmpty()
    }

    @Throws(java.lang.Exception::class)
    fun runDiscoveredCppModuleLost() {
        testCase.write(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        package(features = [
            "header_modules",
            "use_header_modules",
        ])

        cc_library(
            name = "top",
            srcs = ["top.cc"],
            deps = [":dep"],
        )

        cc_library(
            name = "dep",
            hdrs = ["dep.h"],
        )
        
        """.trimIndent()
        )
        testCase.write("foo/top.cc", "#include \"foo/dep.h\"")
        testCase.write("foo/dep.h")

        val depPcm: AtomicReference<Artifact?> = AtomicReference<Artifact?>()
        addSpawnShim(
            "Compiling foo/top.cc",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                val lostInput: ActionInput = SpawnInputUtils.getInputWithName(spawn, "dep.pic.pcm")
                depPcm.set(lostInput as Artifact?)
                createLostInputsExecException(context, lostInput)
            })
        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()

        testCase.buildTarget("//foo:top")

        verifyAllSpawnShimsConsumed()
        Truth.assertThat(rewoundKeys).containsExactly(Artifact.key(depPcm.get()))
        Truth.assertThat(com.google.common.collect.ImmutableMultiset.copyOf<String?>(this.executedSpawnDescriptions))
            .hasCount("Compiling foo/dep.cppmap", 2)
    }

    @Throws(java.lang.Exception::class)
    fun runMultipleLostInputsWithSameDigest_rewoundTogether() {
        testCase.write(
            "foo/BUILD",
            """
        genrule(name = "top", srcs = [":dep1", ":dep2"], outs = ["top.out"], cmd = "echo top >${'$'}@")
        genrule(name = "dep1", outs = ["dep1.out"], cmd = "echo dep > ${'$'}@")
        genrule(name = "dep2", outs = ["dep2.out"], cmd = "echo dep > ${'$'}@")
        
        """.trimIndent()
        )
        addSpawnShim(
            "Executing genrule //foo:top",
            SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                createLostInputsExecException(
                    spawn,
                    context,
                    "dep1.out",
                    "dep2.out"
                )
            })
        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()

        testCase.buildTarget("//foo:top")

        verifyAllSpawnShimsConsumed()
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//foo:dep1", "//foo:dep2")
        Truth.assertThat(recorder.getActionRewoundEvents()).hasSize(1)
    }

    @Throws(java.lang.Exception::class)
    fun runLostTopLevelOutputWithRewindingDisabled() {
        testCase.write(
            "foo/BUILD", "genrule(name = 'gen', outs = ['gen.out'], cmd = 'echo lost > $@')"
        )
        testCase.addOptions("--norewind_lost_inputs")
        lostOutputsModule.addLostOutput(getExecPath("bin/foo/gen.out"))

        val e: T = org.junit.Assert.assertThrows<T>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { testCase.buildTarget("//foo:gen") })
        lostOutputsModule.verifyAllLostOutputsConsumed()
        assertThat(e.getDetailedExitCode().getFailureDetail().getActionRewinding().getCode())
            .isEqualTo(ActionRewinding.Code.LOST_OUTPUT_REWINDING_DISABLED)
        testCase.assertContainsError(
            "//foo:gen: Unexpected lost outputs (pass --rewind_lost_inputs to enable recovery):"
                    + " foo/gen.out"
        )
    }

    @Throws(java.lang.Exception::class)
    fun runTopLevelOutputRewound_regularFile() {
        testCase.write(
            "foo/defs.bzl",
            """
        def _lost_and_found_impl(ctx):
            lost = ctx.actions.declare_file("lost.out")
            found = ctx.actions.declare_file("found.out")
            ctx.actions.run_shell(outputs = [lost], command = "echo lost > %s" % lost.path)
            ctx.actions.run_shell(outputs = [found], command = "echo found > %s" % found.path)
            return DefaultInfo(files = depset([lost, found]))

        lost_and_found = rule(implementation = _lost_and_found_impl)
        
        """.trimIndent()
        )
        testCase.write(
            "foo/BUILD",
            """
        load(":defs.bzl", "lost_and_found")

        lost_and_found(name = "lost_and_found")
        
        """.trimIndent()
        )
        lostOutputsModule.addLostOutput(getExecPath("bin/foo/lost.out"))
        val fooLostAndFound: Label = Label.parseCanonical("//foo:lost_and_found")
        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        val targetCompleteEvents: MutableMap<Label?, TargetCompleteEvent?> = recordTargetCompleteEvents()
        listenForNoCompletionEventsBeforeRewinding(fooLostAndFound, targetCompleteEvents)

        testCase.buildTarget("//foo:lost_and_found")

        lostOutputsModule.verifyAllLostOutputsConsumed()
        Truth.assertThat(rewoundKeys).hasSize(1)
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//foo:lost_and_found")
        Truth.assertThat(com.google.common.collect.ImmutableMultiset.copyOf<String?>(this.executedSpawnDescriptions))
            .hasCount("Action foo/lost.out", 2)
        Truth.assertThat(com.google.common.collect.ImmutableMultiset.copyOf<String?>(this.executedSpawnDescriptions))
            .hasCount("Action foo/found.out", 1)
        Truth.assertThat(targetCompleteEvents.keys).containsExactly(fooLostAndFound)
        assertOutputsReported(
            targetCompleteEvents.get(fooLostAndFound), "bin/foo/lost.out", "bin/foo/found.out"
        )
        recorder.assertTotalLostOutputCountsFromStats(com.google.common.collect.ImmutableList.of<Int?>(1))
    }

    @Throws(java.lang.Exception::class)
    fun runTopLevelOutputRewound_aspectOwned() {
        testCase.write(
            "foo/defs.bzl",
            """
        def _lost_and_found_aspect_impl(target, ctx):
            lost = ctx.actions.declare_file("lost.out")
            found = ctx.actions.declare_file("found.out")
            ctx.actions.run_shell(outputs = [lost], command = "echo lost > %s" % lost.path)
            ctx.actions.run_shell(outputs = [found], command = "echo found > %s" % found.path)
            return [OutputGroupInfo(default = depset([lost, found]))]

        lost_and_found_aspect = aspect(implementation = _lost_and_found_aspect_impl)
        
        """.trimIndent()
        )
        testCase.write("foo/BUILD", "filegroup(name = 'lib')")
        lostOutputsModule.addLostOutput(getExecPath("bin/foo/lost.out"))
        val fooLib: Label = Label.parseCanonical("//foo:lib")
        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        val aspectCompleteEvents: MutableMap<Label?, AspectCompleteEvent?> = recordAspectCompleteEvents()
        listenForNoCompletionEventsBeforeRewinding(fooLib, aspectCompleteEvents)

        testCase.addOptions("--aspects=foo/defs.bzl%lost_and_found_aspect")
        testCase.buildTarget("//foo:lib")

        lostOutputsModule.verifyAllLostOutputsConsumed()
        Truth.assertThat(rewoundKeys).hasSize(1)
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//foo:lib")
        assertThat((rewoundKeys.get(0) as ActionLookupData).getActionLookupKey())
            .isInstanceOf(AspectKey::class.java)
        Truth.assertThat(com.google.common.collect.ImmutableMultiset.copyOf<String?>(this.executedSpawnDescriptions))
            .hasCount("Action foo/lost.out", 2)
        Truth.assertThat(com.google.common.collect.ImmutableMultiset.copyOf<String?>(this.executedSpawnDescriptions))
            .hasCount("Action foo/found.out", 1)
        Truth.assertThat(aspectCompleteEvents.keys).containsExactly(fooLib)
        assertOutputsReported(
            aspectCompleteEvents.get(fooLib), "bin/foo/lost.out", "bin/foo/found.out"
        )
        recorder.assertTotalLostOutputCountsFromStats(com.google.common.collect.ImmutableList.of<Int?>(1))
    }

    @Throws(java.lang.Exception::class)
    fun runTopLevelOutputRewound_fileInTreeArtifact() {
        testCase.write(
            "foo/defs.bzl",
            """
        def _lost_and_found_trees_impl(ctx):
            lost_tree = ctx.actions.declare_directory("lost_tree")
            found_tree = ctx.actions.declare_directory("found_tree")
            ctx.actions.run_shell(
                outputs = [lost_tree],
                command = "echo lost > %s/lost_file" % lost_tree.path,
            )
            ctx.actions.run_shell(
                outputs = [found_tree],
                command = "echo found > %s/found_file" % found_tree.path,
            )
            return DefaultInfo(files = depset([lost_tree, found_tree]))

        lost_and_found_trees = rule(implementation = _lost_and_found_trees_impl)
        
        """.trimIndent()
        )
        testCase.write(
            "foo/BUILD",
            """
        load(":defs.bzl", "lost_and_found_trees")

        lost_and_found_trees(name = "lost_and_found_trees")
        
        """.trimIndent()
        )
        lostOutputsModule.addLostOutput(getExecPath("bin/foo/lost_tree/lost_file"))
        val fooLostAndFoundTrees: Label = Label.parseCanonical("//foo:lost_and_found_trees")
        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        val targetCompleteEvents: MutableMap<Label?, TargetCompleteEvent?> = recordTargetCompleteEvents()
        listenForNoCompletionEventsBeforeRewinding(fooLostAndFoundTrees, targetCompleteEvents)

        testCase.buildTarget("//foo:lost_and_found_trees")

        lostOutputsModule.verifyAllLostOutputsConsumed()
        assertTreeArtifactRewound(rewoundKeys, "foo/lost_tree")
        Truth.assertThat(com.google.common.collect.ImmutableMultiset.copyOf<String?>(this.executedSpawnDescriptions))
            .hasCount("Action foo/lost_tree", 2)
        Truth.assertThat(com.google.common.collect.ImmutableMultiset.copyOf<String?>(this.executedSpawnDescriptions))
            .hasCount("Action foo/found_tree", 1)
        Truth.assertThat(targetCompleteEvents.keys).containsExactly(fooLostAndFoundTrees)
        assertOutputsReported(
            targetCompleteEvents.get(fooLostAndFoundTrees),
            "bin/foo/lost_tree/lost_file",
            "bin/foo/found_tree/found_file"
        )
        recorder.assertTotalLostOutputCountsFromStats(com.google.common.collect.ImmutableList.of<Int?>(1))
    }

    @Throws(java.lang.Exception::class)
    fun runTopLevelOutputRewound_partiallyBuiltTarget_regularFile() {
        testCase.write(
            "foo/defs.bzl",
            """
        def _lost_found_and_failed_impl(ctx):
            lost = ctx.actions.declare_file("lost.out")
            found = ctx.actions.declare_file("found.out")
            failed = ctx.actions.declare_file("failed.out")
            ctx.actions.run_shell(
                outputs = [lost, found],
                command = "echo lost > %s && echo found > %s" % (lost.path, found.path),
            )
            ctx.actions.run_shell(outputs = [failed], inputs = [found], command = "false")
            return DefaultInfo(files = depset([lost, found, failed]))

        lost_found_and_failed = rule(implementation = _lost_found_and_failed_impl)
        
        """.trimIndent()
        )
        testCase.write(
            "foo/BUILD",
            """
        load(":defs.bzl", "lost_found_and_failed")

        lost_found_and_failed(name = "lost_found_and_failed")
        
        """.trimIndent()
        )
        lostOutputsModule.addLostOutput(getExecPath("bin/foo/lost.out"))
        val fooLostFoundAndFailed: Label = Label.parseCanonical("//foo:lost_found_and_failed")
        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        val targetCompleteEvents: MutableMap<Label?, TargetCompleteEvent?> = recordTargetCompleteEvents()
        listenForNoCompletionEventsBeforeRewinding(fooLostFoundAndFailed, targetCompleteEvents)

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { testCase.buildTarget("//foo:lost_found_and_failed") })

        lostOutputsModule.verifyAllLostOutputsConsumed()

        Truth.assertThat(targetCompleteEvents.keys).containsExactly(fooLostFoundAndFailed)
        val event: TargetCompleteEvent? = targetCompleteEvents.get(fooLostFoundAndFailed)
        assertThat(event.failed()).isTrue()

        if (keepGoing()) {
            Truth.assertThat(rewoundKeys).hasSize(1)
            Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys))
                .containsExactly("//foo:lost_found_and_failed")
            Truth.assertThat(com.google.common.collect.ImmutableMultiset.copyOf<String?>(this.executedSpawnDescriptions))
                .hasCount("Action foo/lost.out", 2)
            Truth.assertThat(com.google.common.collect.ImmutableMultiset.copyOf<String?>(this.executedSpawnDescriptions))
                .hasCount("Action foo/failed.out", 1)
            // The event is failed but still reports the built artifacts, including the one that was lost.
            assertOutputsReported(event, "bin/foo/lost.out", "bin/foo/found.out")
        } else {
            Truth.assertThat(rewoundKeys).isEmpty()
            Truth.assertThat(this.executedSpawnDescriptions).containsNoDuplicates()
            // The event does not report the lost artifact because with --nokeep_going, we have no
            // opportunity to rewind after an error is observed.
            assertOutputsReported(event, "bin/foo/found.out")
        }
        recorder.assertTotalLostOutputCountsFromStats(com.google.common.collect.ImmutableList.of<Int?>(1))
    }

    @Throws(java.lang.Exception::class)
    fun runTopLevelOutputRewound_partiallyBuiltTarget_fileInTreeArtifact() {
        ensureMultipleJobs()
        testCase.write(
            "foo/defs.bzl",
            """
        def _lost_tree_found_and_failed_impl(ctx):
            lost_tree = ctx.actions.declare_directory("lost_tree")
            found = ctx.actions.declare_file("found.out")
            failed = ctx.actions.declare_file("failed.out")
            ctx.actions.run_shell(
                outputs = [lost_tree, found],
                command = "echo lost > ${'$'}1/lost_file && echo found > ${'$'}2",
                arguments = [lost_tree.path, found.path],
            )
            ctx.actions.run_shell(outputs = [failed], inputs = [found], command = "false")
            return DefaultInfo(files = depset([lost_tree, found, failed]))

        lost_tree_found_and_failed = rule(implementation = _lost_tree_found_and_failed_impl)
        
        """.trimIndent()
        )
        testCase.write(
            "foo/BUILD",
            """
        load(":defs.bzl", "lost_tree_found_and_failed")

        lost_tree_found_and_failed(name = "lost_tree_found_and_failed")
        
        """.trimIndent()
        )
        lostOutputsModule.addLostOutput(getExecPath("bin/foo/lost_tree/lost_file"))
        val fooLostTreeFoundAndFailed: Label = Label.parseCanonical("//foo:lost_tree_found_and_failed")
        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        val targetCompleteEvents: MutableMap<Label?, TargetCompleteEvent?> = recordTargetCompleteEvents()
        listenForNoCompletionEventsBeforeRewinding(fooLostTreeFoundAndFailed, targetCompleteEvents)

        if (!keepGoing()) {
            // Block the failing action on the completion of the TreeArtifactValue (produced by
            // ArtifactFunction). Otherwise, the build may be aborted without considering it as built,
            // meaning it won't be observed to be lost.
            val treeArtifactDone: CountDownLatch = CountDownLatch(1)
            testCase.injectListenerAtStartOfNextBuild(
                NotifyingHelper.Listener { key, type, order, context ->
                    if (key is Artifact
                        && key.isTreeArtifact()
                        && type === NotifyingHelper.EventType.SET_VALUE && order === NotifyingHelper.Order.AFTER
                    ) {
                        treeArtifactDone.countDown()
                    }
                })
            addSpawnShim(
                "Action foo/failed.out",
                SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                    treeArtifactDone.await()
                    ExecResult.delegate()
                })
        }

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { testCase.buildTarget("//foo:lost_tree_found_and_failed") })

        lostOutputsModule.verifyAllLostOutputsConsumed()

        Truth.assertThat(targetCompleteEvents.keys).containsExactly(fooLostTreeFoundAndFailed)
        val event: TargetCompleteEvent? = targetCompleteEvents.get(fooLostTreeFoundAndFailed)
        assertThat(event.failed()).isTrue()

        if (keepGoing()) {
            assertTreeArtifactRewound(rewoundKeys, "foo/lost_tree")
            Truth.assertThat(com.google.common.collect.ImmutableMultiset.copyOf<String?>(this.executedSpawnDescriptions))
                .hasCount("Action foo/lost_tree", 2)
            Truth.assertThat(com.google.common.collect.ImmutableMultiset.copyOf<String?>(this.executedSpawnDescriptions))
                .hasCount("Action foo/failed.out", 1)
            // The event is failed but still reports the built artifacts, including the one that was lost.
            assertOutputsReported(event, "bin/foo/lost_tree/lost_file", "bin/foo/found.out")
        } else {
            Truth.assertThat(rewoundKeys).isEmpty()
            Truth.assertThat(this.executedSpawnDescriptions).containsNoDuplicates()
            // The event does not report the lost artifact because with --nokeep_going, we have no
            // opportunity to rewind after an error is observed.
            assertOutputsReported(event, "bin/foo/found.out")
        }
        recorder.assertTotalLostOutputCountsFromStats(com.google.common.collect.ImmutableList.of<Int?>(1))
    }

    @Throws(java.lang.Exception::class)
    fun runTopLevelOutputRewound_ineffectiveRewinding() {
        testCase.write(
            "foo/defs.bzl",
            """
        def _lost_and_found_impl(ctx):
            lost = ctx.actions.declare_file("lost.out")
            found = ctx.actions.declare_file("found.out")
            ctx.actions.run_shell(outputs = [lost], command = "echo lost > %s" % lost.path)
            ctx.actions.run_shell(outputs = [found], command = "echo found > %s" % found.path)
            return DefaultInfo(files = depset([lost, found]))

        lost_and_found = rule(implementation = _lost_and_found_impl)
        
        """.trimIndent()
        )
        testCase.write(
            "foo/BUILD",
            """
        load(":defs.bzl", "lost_and_found")

        lost_and_found(name = "lost_and_found")
        
        """.trimIndent()
        )
        val fooLostAndFound: Label = Label.parseCanonical("//foo:lost_and_found")
        val outputExecPath = getExecPath("bin/foo/lost.out")
        val bugReporter: RecordingBugReporter = testCase.recordBugReportsAndReinitialize()
        val rewoundKeys: MutableList<SkyKey?> = collectOrderedRewoundKeys()
        val targetCompleteEvents: MutableMap<Label?, TargetCompleteEvent?> = recordTargetCompleteEvents()
        listenForNoCompletionEventsBeforeRewinding(fooLostAndFound, targetCompleteEvents)

        for (i in 0..ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS) {
            addSpawnShim(
                "Action foo/lost.out",
                SpawnShim { spawn: Spawn?, context: ActionExecutionContext? ->
                    lostOutputsModule.addLostOutput(outputExecPath)
                    ExecResult.delegate()
                })
        }

        val e: BuildFailedException =
            org.junit.Assert.assertThrows<T>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { testCase.buildTarget("//foo:lost_and_found") })
        assertThat(e.getDetailedExitCode().getFailureDetail().getActionRewinding().getCode())
            .isEqualTo(ActionRewinding.Code.LOST_OUTPUT_TOO_MANY_TIMES)

        assertOnlyActionsRewound(rewoundKeys)
        Truth.assertThat(rewoundArtifactOwnerLabels(rewoundKeys))
            .containsExactlyElementsIn(
                Collections.nCopies<String?>(
                    ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS, "//foo:lost_and_found"
                )
            )
        Truth.assertThat(com.google.common.collect.ImmutableMultiset.copyOf<String?>(this.executedSpawnDescriptions))
            .hasCount("Action foo/lost.out", ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS + 1)

        val actionExecutionValue: ActionExecutionValue =
            testCase.skyframeExecutor.getEvaluator().getExistingValue(rewoundKeys.get(0)) as ActionExecutionValue
        val lostInput: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            actionExecutionValue.allFileValues.entrySet().stream()
                .filter({ entry -> entry.getKey().getRootRelativePathString().equals("foo/lost.out") })
                .map({ java.util.Map.Entry.value })
                .collect(com.google.common.collect.MoreCollectors.onlyElement<T?>())
        val expectedError: String? =
            java.lang.String.format(
                "Lost output foo/lost.out (digest %s), and rewinding was ineffective after %d"
                        + " attempts.",
                toHex(lostInput.getDigest(), lostInput.getSize()),
                ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS
            )
        testCase.assertContainsError(expectedError)
        com.google.common.truth.Subject.contains(expectedError)
        Truth.assertThat(com.google.common.collect.Iterables.getOnlyElement<Throwable?>(bugReporter.getExceptions()))
            .hasMessageThat()
            .contains(expectedError)

        // TargetCompleteEvent is failed and reports only the found output and not the lost output.
        Truth.assertThat(targetCompleteEvents.keys).containsExactly(fooLostAndFound)
        val event: TargetCompleteEvent? = targetCompleteEvents.get(fooLostAndFound)
        assertThat(event.failed()).isTrue()
        assertOutputsReported(event, "bin/foo/found.out")

        recorder.assertTotalLostOutputCountsFromStats(
            com.google.common.collect.ImmutableList.of<Int?>(ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS + 1)
        )
    }

    fun listenForNoCompletionEventsBeforeRewinding(
        lostLabel: Label, events: MutableMap<Label?, out EventReportingArtifacts?>?
    ) {
        testCase.injectListenerAtStartOfNextBuild(
            NotifyingHelper.Listener { key, type, order, context ->
                if (type === NotifyingHelper.EventType.MARK_DIRTY
                    || (isActionExecutionKey(key, lostLabel) && type === NotifyingHelper.EventType.SET_VALUE)
                ) {
                    // Completion events for lost outputs should not be emitted until after rewinding
                    // completes. Otherwise, we may publish stale artifact URIs to the BEP.
                    Truth.assertThat(events).isEmpty()
                }
            })
    }

    @Throws(java.lang.Exception::class)
    fun assertOutputsReported(
        event: EventReportingArtifacts, vararg expectedRootRelativePaths: String?
    ) {
        val reported: ReportedArtifacts = event.reportedArtifacts(OutputGroupFileModes.DEFAULT)
        val expectedExecPaths: MutableList<PathFragment?> = java.util.ArrayList<PathFragment?>()
        for (path in expectedRootRelativePaths) {
            expectedExecPaths.add(PathFragment.create(getExecPath(path)))
        }
        val execPaths: MutableList<PathFragment?> = java.util.ArrayList<PathFragment?>()
        for (set in reported.artifacts) {
            reported.completionContext.visitArtifacts(
                set.toList(),
                object : ArtifactReceiver() {
                    public override fun accept(artifact: Artifact, metadata: FileArtifactValue?) {
                        execPaths.add(artifact.getExecPath())
                    }

                    public override fun acceptFilesetMapping(fileset: Artifact?, link: FilesetOutputSymlink) {
                        execPaths.add(link.target().getExecPath())
                    }
                })
        }
        Truth.assertThat(execPaths).containsExactlyElementsIn(expectedExecPaths)
    }

    /**
     * Ensures that the value of the `--jobs` flag is at least 2.
     * 
     * 
     * Several tests use artificial synchronization to exercise certain race conditions and require
     * a multiple execution phase threads to guarantee progress.
     * 
     * 
     * Note that the default value for `--jobs` is automatically calculated based on host
     * CPU.
     */
    @Throws(java.lang.Exception::class)
    private fun ensureMultipleJobs() {
        val autoJobs: Int = JobsConverter().convert("auto")
        if (autoJobs == 1) {
            logger.atInfo().log("Setting --jobs=2 (was 1)")
            testCase.addOptions("--jobs=2")
        } else {
            logger.atInfo().log("Keeping default value of --jobs=%s", autoJobs)
        }
    }

    private fun keepGoing(): Boolean {
        return testCase.runtimeWrapper.getOptions<KeepGoingOption?>(KeepGoingOption::class.java).getKeepGoing()
    }

    fun buildRunfileManifests(): Boolean {
        return testCase.runtimeWrapper.getOptions<CoreOptions?>(CoreOptions::class.java).getBuildRunfileManifests()
    }

    fun recordTargetCompleteEvents(): MutableMap<Label?, TargetCompleteEvent?> {
        val targetCompleteEvents: MutableMap<Label?, TargetCompleteEvent?> = HashMap<Label?, TargetCompleteEvent?>()
        testCase
            .runtimeWrapper
            .registerSubscriber(
                object : Any() {
                    @com.google.common.eventbus.Subscribe
                    @Suppress("unused")
                    fun accept(event: TargetCompleteEvent) {
                        val prev: TargetCompleteEvent? = targetCompleteEvents.put(event.getLabel(), event)
                        checkState(prev == null, "Duplicate TargetCompleteEvent for %s", event.getLabel())
                    }
                })
        return targetCompleteEvents
    }

    private fun recordAspectCompleteEvents(): MutableMap<Label?, AspectCompleteEvent?> {
        val aspectCompleteEvents: MutableMap<Label?, AspectCompleteEvent?> = HashMap<Label?, AspectCompleteEvent?>()
        testCase
            .runtimeWrapper
            .registerSubscriber(
                object : Any() {
                    @com.google.common.eventbus.Subscribe
                    @Suppress("unused")
                    fun accept(event: AspectCompleteEvent) {
                        // If we need to track targets with multiple aspects, we could change the key type.
                        val prev: AspectCompleteEvent? = aspectCompleteEvents.put(event.getLabel(), event)
                        checkState(prev == null, "Duplicate AspectCompleteEvent for %s", event.getLabel())
                    }
                })
        return aspectCompleteEvents
    }

    /**
     * Converts a root-relative output path to an exec path, accounting for the top-level
     * configuration's mnemonic and [TestConstants.PRODUCT_NAME].
     * 
     * 
     * Example: bin/pkg/file.out -> bazel-out/k8-fastbuild/bin/pkg/file.out
     */
    @Throws(java.lang.Exception::class)
    private fun getExecPath(rootRelativePath: String?): String {
        if (testCase.targetConfigurationFromLastBuildResult == null) {
            // Need at least one build to get the configuration, so run a null build.
            testCase.buildTarget()
            recorder.clear() // Don't record stats for the null build.
        }
        return testCase
            .targetConfigurationFromLastBuildResult
            .getOutputDirectory(RepositoryName.MAIN)
            .getExecPath()
            .getRelative(rootRelativePath)
            .getPathString()
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * Filters out spawn descriptions that only appear in Bazel or Blaze and aren't relevant to the
         * test.
         */
        private fun filterExecutedSpawnDescriptions(vararg expectedDescriptions: String?): Array<Any?>? {
            if (AnalysisMock.get().isThisBazel()) {
                return java.util.Arrays.stream<String?>(expectedDescriptions) // Bazel doesn't support spawn-based include scanning without additional
                    // toolchain tools.
                    .filter { s: String? -> !s.startsWith("Extracting include lines ") }
                    .toArray<String?> { _Dummy_.__Array__() }
            } else {
                return expectedDescriptions
            }
        }

        fun assertOnlyActionsRewound(rewoundKeys: MutableList<SkyKey?>) {
            for (key in rewoundKeys) {
                if (key !is ArtifactNestedSetKey) {
                    assertThat(key).isInstanceOf(ActionLookupData::class.java)
                }
            }
        }

        fun rewoundArtifactOwnerLabels(rewoundKeys: MutableList<SkyKey?>): com.google.common.collect.ImmutableList<String?> {
            return rewoundKeys.stream()
                .filter { k: SkyKey? -> k is ActionLookupData }
                .map<Any?> { k: SkyKey? -> (k as ActionLookupData).getActionLookupKey().getLabel().getCanonicalForm() }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        }

        fun assertArtifactKey(skyKey: SkyKey, path: String?) {
            assertThat(skyKey).isInstanceOf(Artifact::class.java)
            assertThat((skyKey as Artifact).getRootRelativePathString()).isEqualTo(path)
        }

        fun assertActionKey(skyKey: SkyKey, label: String?, index: Int) {
            assertThat(skyKey).isInstanceOf(ActionLookupData::class.java)
            assertThat((skyKey as ActionLookupData).getLabel().getCanonicalForm()).isEqualTo(label)
            assertThat((skyKey as ActionLookupData).getActionIndex()).isEqualTo(index)
        }

        fun assertTreeArtifactRewound(rewoundKeys: MutableList<SkyKey?>?, lostTree: String?) {
            Truth.assertThat(rewoundKeys).hasSize(2)
            assertThat(rewoundKeys!!.get(1)).isInstanceOf(SpecialArtifact::class.java)
            val treeArtifact: SpecialArtifact = rewoundKeys.get(1) as SpecialArtifact
            assertThat(treeArtifact.isTreeArtifact()).isTrue()
            assertThat(treeArtifact.getRootRelativePathString()).isEqualTo(lostTree)
            assertThat(rewoundKeys.get(0)).isEqualTo(treeArtifact.getGeneratingActionKey())
        }

        @Throws(IOException::class)
        fun latin1StringFromActionInput(context: ActionExecutionContext, input: ActionInput): String {
            // Test logic implemented here requires that files whose contents will be read locally have the
            // suffix ".inlined". Tests using remote execution should be configured to eagerly fetch these
            // artifacts.
            checkArgument(
                input.getExecPathString().endsWith(".inlined"),
                "Only inputs ending in .inlined are guaranteed readable. Tried to read: %s",
                input
            )
            return String(readContentAsLatin1(context.getInputPath(input)))
        }

        fun getIntermediate1And2LostInputs(spawn: Spawn): com.google.common.collect.ImmutableList<ActionInput> {
            return com.google.common.collect.ImmutableList.of<ActionInput?>(
                SpawnInputUtils.getInputWithName(spawn, "intermediate_1.txt"),
                SpawnInputUtils.getInputWithName(spawn, "intermediate_2.txt")
            )
        }

        @Throws(IOException::class)
        private fun writeTwoGenrulePackage(testCase: BuildIntegrationTestCase) {
            testCase.write(
                "test/BUILD",
                """
        genrule(
            name = "rule1",
            srcs = ["source_1.txt"],
            outs = ["intermediate.txt"],
            cmd = "(cat ${'$'}< && echo from rule1) > ${'$'}@",
        )

        genrule(
            name = "rule2",
            srcs = [
                "intermediate.txt",
                "source_2.txt",
            ],
            outs = ["output.inlined"],
            cmd = "(cat ${'$'}(SRCS) && echo from rule2) > ${'$'}@",
        )

        genrule(
            name = "consume_output",
            srcs = [":output.inlined"],
            outs = ["dummy.out"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
            )

            testCase.write("test/source_1.txt", "source_1")
            testCase.write("test/source_2.txt", "source_2")
        }

        private val FAILED_RESULT: SpawnResult? = Builder()
            .setStatus(SpawnResult.Status.NON_ZERO_EXIT)
            .setExitCode(1)
            .setFailureDetail(
                FailureDetail.newBuilder()
                    .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.NON_ZERO_EXIT))
                    .build()
            )
            .setRunnerName("remote")
            .build()

        private fun actionHasLabelAndIndex(
            actionLookupData: ActionLookupData, labelName: String?, index: Int
        ): Boolean {
            val label: Label? = actionLookupData.getLabel()
            return label != null && label.name.equals(labelName)
                    && actionLookupData.getActionIndex() === index
        }

        /**
         * This method defines a package with a Starlark rule "make_cc" and a cc_library rule
         * "consumes_tree" which depends on "make_cc". The Starlark rule generates a tree artifact. The
         * cc_library rule class knows how to consume tree artifacts: it uses a separate compilation
         * action for each file in the tree, and then one linking action for the tree.
         */
        @Throws(java.lang.Exception::class)
        private fun setUpTreeArtifactPackage(testCase: BuildIntegrationTestCase) {
            testCase.write(
                "tree/tree.bzl",
                """
        def _tree_impl(ctx):
            tree_artifact = ctx.actions.declare_directory(ctx.attr.name + "_dir.cc")
            ctx.actions.run_shell(
                inputs = ctx.files.srcs,
                outputs = [tree_artifact],
                command = "touch ${'$'}1/file1.cc && touch ${'$'}1/file2.cc",
                arguments = [tree_artifact.path],
            )
            return DefaultInfo(files = depset(direct = [tree_artifact]))

        tree = rule(
            implementation = _tree_impl,
            attrs = {"srcs": attr.label_list(allow_files = True)},
        )
        
        """.trimIndent()
            )

            testCase.write(
                "tree/BUILD",
                """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load(":tree.bzl", "tree")

        tree(
            name = "make_cc",
            srcs = ["source_1.txt"],
        )

        cc_library(
            name = "consumes_tree",
            srcs = [
                "source_2.cc",
                ":make_cc",
            ],
        )
        
        """.trimIndent()
            )

            testCase.write("tree/source_1.txt", "source_1")
            testCase.write("tree/source_2.cc", "#define FOO")
            // Don't want to have to track inclusion extraction for tree file artifacts.
            testCase.addOptions("--features=-cc_include_scanning")
        }

        fun getTreeFileArtifactRewoundLostInputs(spawn: Spawn): com.google.common.collect.ImmutableList<ActionInput> {
            return com.google.common.collect.ImmutableList.of<ActionInput?>(
                SpawnInputUtils.getInputWithName(
                    spawn,
                    "make_cc_dir.cc/file1.cc"
                )
            )
        }

        fun getTreeArtifactRewoundWhenTreeFilesLostTree(spawn: Spawn?): Artifact? {
            return SpawnInputUtils.getTreeArtifactWithName(spawn, "make_cc_dir")
        }

        fun getTreeArtifactRewoundWhenTreeFilesLostInputs(
            lostTreeFileArtifactNames: com.google.common.collect.ImmutableList<String?>,
            spawn: Spawn?,
            context: ActionExecutionContext,
            treeArtifact: Artifact?
        ): com.google.common.collect.ImmutableList<ActionInput> {
            return lostTreeFileArtifactNames.stream()
                .map<Any?> { n: String? -> SpawnInputUtils.getExpandedToArtifact(n, treeArtifact, spawn, context) }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        }

        fun getGeneratedRunfilesRewoundLostRunfiles(
            lostRunfiles: com.google.common.collect.ImmutableList<String?>,
            spawn: Spawn,
            context: ActionExecutionContext?
        ): com.google.common.collect.ImmutableList<ActionInput> {
            return lostRunfiles.stream()
                .map<Any?> { n: String? -> SpawnInputUtils.getRunfilesArtifactWithName(spawn, context, n) }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        }

        fun getDupeDirectAndRunfilesDependencyRewoundLostInput(
            spawn: Spawn, context: ActionExecutionContext?
        ): ActionInput {
            return SpawnInputUtils.getRunfilesArtifactWithName(spawn, context, "intermediate_1.inlined")
        }

        fun getTreeInRunfilesRewoundTree(spawn: Spawn, context: ActionExecutionContext?): Artifact {
            return SpawnInputUtils.getRunfilesArtifactWithName(spawn, context, "gen_tree")
        }

        fun getTreeInRunfilesRewoundLostInputs(
            spawn: Spawn?, context: ActionExecutionContext, treeArtifact: Artifact?
        ): com.google.common.collect.ImmutableList<ActionInput> {
            return com.google.common.collect.ImmutableList.of<ActionInput?>(
                SpawnInputUtils.getExpandedToArtifact("gen1.out", treeArtifact, spawn, context),
                SpawnInputUtils.getExpandedToArtifact("gen2.out", treeArtifact, spawn, context)
            )
        }

        @Throws(IOException::class)
        private fun writeGeneratedHeaderDirectDepPackage(testCase: BuildIntegrationTestCase) {
            testCase.write(
                "genheader/BUILD",
                """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        genrule(
            name = "gen_header",
            srcs = [],
            outs = ["gen.h"],
            cmd = "touch ${'$'}@",
        )

        cc_binary(
            name = "consumes_header",
            srcs = [
                "consumes.cc",
                "gen.h",
            ],
        )
        
        """.trimIndent()
            )
            testCase.write(
                "genheader/consumes.cc",
                "#include \"genheader/gen.h\"",
                "int main() {",
                "  return 0;",
                "}"
            )
        }

        fun getGeneratedHeaderRewoundLostInput(spawn: Spawn): ActionInput {
            return SpawnInputUtils.getInputWithName(spawn, "genheader/gen.h")
        }

        @Throws(IOException::class)
        private fun writeGeneratedHeaderIndirectDepPackage(testCase: BuildIntegrationTestCase) {
            testCase.write(
                "genheader/BUILD",
                """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        genrule(
            name = "gen_header",
            srcs = [],
            outs = ["gen.h"],
            cmd = 'echo "int f(int x);" > ${'$'}@',
        )

        cc_library(
            name = "intermediate",
            srcs = ["intermediate.cc"],
            hdrs = ["gen.h"],
        )

        cc_binary(
            name = "consumes_header",
            srcs = ["consumes.cc"],
            deps = ["intermediate"],
        )
        
        """.trimIndent()
            )
            testCase.write("genheader/intermediate.cc", "int f(int x) { return x + 1; }")
            testCase.write(
                "genheader/consumes.cc",
                "#include \"genheader/gen.h\"",
                "int main() {",
                "  return f(1);",
                "}"
            )
        }

        fun isActionExecutionKey(key: Any, label: Label): Boolean {
            return key is ActionLookupData && label.equals((key as ActionLookupData).getLabel())
        }
    }
}
