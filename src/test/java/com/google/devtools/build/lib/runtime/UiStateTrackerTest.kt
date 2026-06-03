// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import build.bazel.remote.execution.v2.Digest

/** Tests [UiStateTracker].  */
@RunWith(TestParameterInjector::class)
class UiStateTrackerTest : FoundationTestCase() {
    @TestParameter
    var isSkymeld: Boolean = false

    private fun getUiStateTracker(clock: com.google.devtools.build.lib.testutil.ManualClock?): UiStateTracker {
        if (isSkymeld) {
            return SkymeldUiStateTracker(clock)
        } else {
            return UiStateTracker(clock)
        }
    }

    private fun getUiStateTracker(
        clock: com.google.devtools.build.lib.testutil.ManualClock?,
        targetWidth: Int
    ): UiStateTracker {
        if (isSkymeld) {
            return SkymeldUiStateTracker(clock, targetWidth)
        } else {
            return UiStateTracker(clock, targetWidth)
        }
    }

    @org.junit.Test
    fun testStrategyIds_getId_idsAreBitmasks() {
        val strategyIds: StrategyIds = StrategyIds()
        val id1: Int = strategyIds.getId("foo")
        val id2: Int = strategyIds.getId("bar")
        val id3: Int = strategyIds.getId("baz")

        Truth.assertThat(id1).isGreaterThan(0)
        Truth.assertThat(id2).isGreaterThan(0)
        Truth.assertThat(id3).isGreaterThan(0)

        Truth.assertThat(id1 and id2).isEqualTo(0)
        Truth.assertThat(id1 and id3).isEqualTo(0)
        Truth.assertThat(id2 and id3).isEqualTo(0)
    }

    @org.junit.Test
    fun testStrategyIds_getId_idsAreReusedIfAlreadyExist() {
        val strategyIds: StrategyIds = StrategyIds()
        val id1: Int? = strategyIds.getId("foo")
        val id2: Int? = strategyIds.getId("bar")
        val id3: Int? = strategyIds.getId("foo")

        Truth.assertThat(id1).isNotEqualTo(id2)
        Truth.assertThat(id1).isEqualTo(id3)
    }

    @org.junit.Test
    fun testStrategyIds_getId_exhaustIds() {
        val strategyIds: StrategyIds = StrategyIds()
        val ids: MutableSet<Int?> = HashSet<Int?>()
        val name: java.lang.StringBuilder = java.lang.StringBuilder()
        while (true) {
            name.append('a')
            val id: Int = strategyIds.getId(name.toString())
            if (id == strategyIds.fallbackId) {
                break
            }
            ids.add(id)
        }
        Truth.assertThat(ids).hasSize(java.lang.Integer.SIZE - 1) // Minus 1 for FALLBACK_NAME.

        assertThat(strategyIds.getId("some")).isEqualTo(strategyIds.fallbackId)
        assertThat(strategyIds.getId("more")).isEqualTo(strategyIds.fallbackId)
    }

    @org.junit.Test
    fun testStrategyIds_formatNames_fallbackExistsByDefault() {
        val strategyIds: StrategyIds = StrategyIds()
        assertThat(strategyIds.formatNames(strategyIds.fallbackId))
            .isEqualTo(StrategyIds.FALLBACK_NAME)
    }

    @org.junit.Test
    fun testStrategyIds_formatNames_oneHasNoComma() {
        val strategyIds: StrategyIds = StrategyIds()
        val id1: Int? = strategyIds.getId("abc")
        assertThat(strategyIds.formatNames(id1)).isEqualTo("abc")
    }

    @org.junit.Test
    fun testStrategyIds_formatNames() {
        val strategyIds: StrategyIds = StrategyIds()
        val id1: Int = strategyIds.getId("abc")
        val id2: Int = strategyIds.getId("xyz")
        val id3: Int = strategyIds.getId("def")

        // Names are not sorted alphabetically but their order is stable based on prior getId calls.
        assertThat(strategyIds.formatNames(id1 or id2)).isEqualTo("abc, xyz")
        assertThat(strategyIds.formatNames(id1 or id3)).isEqualTo("abc, def")
        assertThat(strategyIds.formatNames(id2 or id3)).isEqualTo("xyz, def")
        assertThat(strategyIds.formatNames(id1 or id2 or id3)).isEqualTo("abc, xyz, def")
    }

    private fun mockAction(progressMessage: String?, primaryOutput: String?): Action {
        val path: Path? = outputBase.getRelative(PathFragment.create(primaryOutput))
        val artifact: Artifact? =
            ActionsTestUtil.createArtifact(ArtifactRoot.asSourceRoot(Root.fromPath(outputBase)), path)

        val action: Action = Mockito.mock<Action>(Action::class.java)
        Mockito.`when`<T?>(action.getProgressMessage(< T > eq < T ? > (RepositoryMapping.EMPTY))).thenReturn(progressMessage)
        Mockito.`when`<T?>(action.getPrimaryOutput()).thenReturn(artifact)

        Mockito.verify<Any?>(action, Mockito.never())
            .getProgressMessage(AdditionalMatchers.< T > not < T ? > (<T> eq < T ? > (RepositoryMapping.EMPTY)))
        Mockito.verify<Any?>(action, Mockito.never()).getProgressMessage()
        return action
    }

    @Throws(LabelSyntaxException::class)
    private fun dummyActionOwner(): ActionOwner {
        return ActionOwner.createDummy(
            Label.parseCanonical("//foo:a"),
            net.starlark.java.syntax.Location("dummy-file", 0, 0),  /* targetKind= */
            "",  /* buildConfigurationMnemonic= */
            "",  /* configurationChecksum= */
            "",
            BuildConfigurationEvent(
                BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
                BuildEventStreamProtos.BuildEvent.getDefaultInstance()
            ),  /* isToolConfiguration= */
            true,  /* executionPlatform= */
            PlatformInfo.EMPTY_PLATFORM_INFO,  /* aspectDescriptors= */
            com.google.common.collect.ImmutableList.of<E?>(),  /* execProperties= */
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
    }

    private fun simulateExecutionPhase(uiStateTracker: UiStateTracker) {
        uiStateTracker.loadingComplete(
            LoadingPhaseCompleteEvent(
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                RepositoryMapping.EMPTY
            )
        )
        if (this.isSkymeld) {
            // SkymeldUiStateTracker needs to be in the configuration phase before the execution phase.
            (uiStateTracker as SkymeldUiStateTracker)
                .setBuildStatusForTestingOnly(BuildStatus.ANALYSIS_COMPLETE)
            uiStateTracker.executionPhaseStarted()
        } else {
            val unused: String? = uiStateTracker.analysisComplete()
        }
        uiStateTracker.progressReceiverAvailable(
            ExecutionProgressReceiverAvailableEvent(dummyExecutionProgressReceiver())
        )
    }

    private fun dummyExecutionProgressReceiver(): ExecutionProgressReceiver {
        return ExecutionProgressReceiver(0, null)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testLoadingActivity() {
        // During loading phase, state and activity, as reported by the PackageProgressReceiver,
        // should be visible in the progress bar.
        val loadingState = "42 packages loaded"
        val loadingActivity = "currently loading //src/foo/bar and 17 more"
        val progress: PackageProgressReceiver =
            Mockito.mock<PackageProgressReceiver>(PackageProgressReceiver::class.java)
        Mockito.`when`<T?>(progress.progressState()).thenReturn(Pair(loadingState, loadingActivity))

        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val stateTracker: UiStateTracker = getUiStateTracker(clock)

        stateTracker.loadingStarted(LoadingPhaseStartedEvent(progress))

        // When it is just loading packages.
        val terminalWriterLoading: LoggingTerminalWriter =
            LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriterLoading)
        val loadingOutput: String? = terminalWriterLoading.getTranscript()

        Truth.assertThat(loadingOutput).contains("Loading")
        Truth.assertThat(loadingOutput).contains(loadingState)
        Truth.assertThat(loadingOutput).contains(loadingActivity)

        // When it is configuring targets.
        stateTracker.loadingComplete(
            LoadingPhaseCompleteEvent(
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                RepositoryMapping.EMPTY
            )
        )
        val additionalMessage = "5 targets"
        stateTracker.additionalMessage = additionalMessage
        val analysisProgressString = "5 targets and 0 aspects configured"
        val analysisProgressReceiver: AnalysisProgressReceiver =
            Mockito.mock<AnalysisProgressReceiver>(AnalysisProgressReceiver::class.java)
        Mockito.`when`<T?>(analysisProgressReceiver.getProgressString()).thenReturn(analysisProgressString)
        stateTracker.configurationStarted(ConfigurationPhaseStartedEvent(analysisProgressReceiver))

        val terminalWriterLoadingConfiguration: LoggingTerminalWriter =
            LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriterLoadingConfiguration)
        val loadingConfigurationOutput: String? = terminalWriterLoadingConfiguration.getTranscript()
        Truth.assertThat(loadingConfigurationOutput).contains("Analyzing")
        Truth.assertThat(loadingConfigurationOutput).contains(additionalMessage)
        Truth.assertThat(loadingConfigurationOutput).contains(loadingState)
        Truth.assertThat(loadingConfigurationOutput).contains(loadingActivity)
        // It should contain the analysis progress string along with the loading information.
        Truth.assertThat(loadingConfigurationOutput).contains(analysisProgressString)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testLargeTargetCountFormattedWithCommas() {
        // Verify that large target counts in "Analyzing: X targets" are formatted with comma
        // separators.
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val stateTracker: UiStateTracker = getUiStateTracker(clock)

        val labelsBuilder: com.google.common.collect.ImmutableSet.Builder<Label?> =
            com.google.common.collect.ImmutableSet.builder<Label?>()
        for (i in 0..12344) {
            labelsBuilder.add(Label.parseCanonicalUnchecked("//pkg:target" + i))
        }
        val labels: com.google.common.collect.ImmutableSet<Label?> = labelsBuilder.build()

        stateTracker.loadingComplete(
            LoadingPhaseCompleteEvent(labels, com.google.common.collect.ImmutableSet.of<E?>(), RepositoryMapping.EMPTY)
        )

        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        val output: String? = terminalWriter.getTranscript()

        Truth.assertThat(output).contains("Analyzing:")
        Truth.assertThat(output).contains("12,345 targets")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testSmallTargetCountNotFormattedWithCommas() {
        // Verify that target counts below 10,000 (IEEE style threshold) are NOT formatted with commas.
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val stateTracker: UiStateTracker = getUiStateTracker(clock)

        val labelsBuilder: com.google.common.collect.ImmutableSet.Builder<Label?> =
            com.google.common.collect.ImmutableSet.builder<Label?>()
        for (i in 0..1233) {
            labelsBuilder.add(Label.parseCanonicalUnchecked("//pkg:target" + i))
        }
        val labels: com.google.common.collect.ImmutableSet<Label?> = labelsBuilder.build()

        stateTracker.loadingComplete(
            LoadingPhaseCompleteEvent(labels, com.google.common.collect.ImmutableSet.of<E?>(), RepositoryMapping.EMPTY)
        )

        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        val output: String? = terminalWriter.getTranscript()

        Truth.assertThat(output).contains("Analyzing:")
        Truth.assertThat(output).contains("1234 targets")
        Truth.assertThat(output).doesNotContain("1,234 targets")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testActionVisible() {
        // If there is only one action running, it should be visible
        // somewhere in the progress bar, and also the short version thereof.

        val message = "Building foo"
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        clock.advanceMillis(120000)

        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        stateTracker.actionStarted(ActionStartedEvent(mockAction(message, "bar/foo"), 123456789))

        var terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        var output: String = terminalWriter.getTranscript()
        Truth.assertWithMessage("Action message '%s' should be present in output: %s", message, output)
            .that(output.contains(message))
            .isTrue()

        terminalWriter = LoggingTerminalWriter()
        stateTracker.writeProgressBar(terminalWriter,  /* shortVersion= */true)
        output = terminalWriter.getTranscript()
        Truth.assertWithMessage("Action message '%s' should be present in short output: %s", message, output)
            .that(output.contains(message))
            .isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCompletedActionNotShown() {
        // Completed actions should not be reported in the progress bar, nor in the
        // short progress bar.

        val messageFast = "Running quick action"
        val messageSlow = "Running slow action"

        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        clock.advanceMillis(120000)
        val fastAction: Action = mockAction(messageFast, "foo/fast")
        val slowAction: Action = mockAction(messageSlow, "bar/slow")
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        stateTracker.actionStarted(ActionStartedEvent(fastAction, 123456789))
        stateTracker.actionStarted(ActionStartedEvent(slowAction, 123456999))

        val actionLookupData: ActionLookupData? =
            ActionLookupData.create(< T > mock < T ? > (ActionLookupKey::class.java), 1)
        stateTracker.actionCompletion(
            ActionCompletionEvent(
                20,
                clock.nanoTime(),
                fastAction,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            actionLookupData
        ))

        var terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        var output: String = terminalWriter.getTranscript()
        Truth.assertWithMessage(
            "Completed action '%s' should not be present in output: %s", messageFast, output
        )
            .that(output.contains(messageFast))
            .isFalse()
        Truth.assertWithMessage(
            "Only running action '%s' should be present in output: %s", messageSlow, output
        )
            .that(output.contains(messageSlow))
            .isTrue()

        terminalWriter = LoggingTerminalWriter()
        stateTracker.writeProgressBar(terminalWriter,  /* shortVersion= */true)
        output = terminalWriter.getTranscript()
        Truth.assertWithMessage(
            "Completed action '%s' should not be present in short output: %s", messageFast, output
        )
            .that(output.contains(messageFast))
            .isFalse()
        Truth.assertWithMessage(
            "Only running action '%s' should be present in short output: %s", messageSlow, output
        )
            .that(output.contains(messageSlow))
            .isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testOldestActionVisible() {
        // The earliest-started action is always visible somehow in the progress bar
        // and its short version.

        val messageOld = "Running the first-started action"

        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        clock.advanceMillis(120000)
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        stateTracker.actionStarted(
            ActionStartedEvent(mockAction(messageOld, "bar/foo"), 123456789)
        )
        for (i in 0..29) {
            stateTracker.actionStarted(
                ActionStartedEvent(
                    mockAction("Other action " + i, "some/other/actions/number" + i), 123456790 + i
                )
            )
        }

        var terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        var output: String = terminalWriter.getTranscript()
        Truth.assertWithMessage(
            "Longest running action '%s' should be visible in output: %s", messageOld, output
        )
            .that(output.contains(messageOld))
            .isTrue()

        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter,  /* shortVersion= */true)
        output = terminalWriter.getTranscript()
        Truth.assertWithMessage(
            "Longest running action '%s' should be visible in short output: %s", messageOld, output
        )
            .that(output.contains(messageOld))
            .isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testSampleSize() {
        // Verify that the number of actions shown in the progress bar can be set as sample size.
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(123))
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(2))

        // Start 10 actions (numbered 0 to 9).
        for (i in 0..9) {
            clock.advanceMillis(TimeUnit.SECONDS.toMillis(1))
            val action: Action = mockAction("Performing action A" + i + ".", "action_A" + i + ".out")
            stateTracker.actionStarted(ActionStartedEvent(action, clock.nanoTime()))
        }

        // For various sample sizes verify the progress bar
        for (i in 1..10) {
            stateTracker.setProgressSampleSize(i)
            val terminalWriter: LoggingTerminalWriter =
                LoggingTerminalWriter( /* discardHighlight= */true)
            stateTracker.writeProgressBar(terminalWriter)
            val output: String = terminalWriter.getTranscript()
            Truth.assertWithMessage("Action %s should still be shown in the output: '%s", (i - 1), output)
                .that(output.contains("A" + (i - 1) + "."))
                .isTrue()
            Truth.assertWithMessage("Action %s should not be shown in the output: %s", i, output)
                .that(output.contains("A" + i + "."))
                .isFalse()
            if (i < 10) {
                Truth.assertWithMessage("Ellipsis symbol should be shown in output: %s", output)
                    .that(output.contains("..."))
                    .isTrue()
            } else {
                Truth.assertWithMessage("Ellipsis symbol should not be shown in output: %s", output)
                    .that(output.contains("..."))
                    .isFalse()
            }
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testTimesShown() {
        // For sufficiently long running actions, the time that has passed since their start is shown.
        // In the short version of the progress bar, this should be true at least for the oldest action.

        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(123))
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(2))

        stateTracker.actionStarted(
            ActionStartedEvent(mockAction("First action", "foo"), clock.nanoTime())
        )
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(7))
        stateTracker.actionStarted(
            ActionStartedEvent(mockAction("Second action", "bar"), clock.nanoTime())
        )
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(20))

        var terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        var output: String = terminalWriter.getTranscript()
        Truth.assertWithMessage("Runtime of first action should be visible in output: %s", output)
            .that(output.contains("27s"))
            .isTrue()
        Truth.assertWithMessage("Runtime of second action should be visible in output: %s", output)
            .that(output.contains("20s"))
            .isTrue()

        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter,  /* shortVersion= */true)
        output = terminalWriter.getTranscript()
        Truth.assertWithMessage("Runtime of first action should be visible in short output: %s", output)
            .that(output.contains("27s"))
            .isTrue()
    }

    @org.junit.Test
    fun initialProgressBarTimeIndependent() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(123))
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        stateTracker.buildStarted()

        Truth.assertWithMessage("Initial progress status should be time independent")
            .that(stateTracker.progressBarTimeDependent())
            .isFalse()
    }

    @org.junit.Test
    fun runningActionTimeIndependent() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(123))
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(1))
        simulateExecutionPhase(stateTracker)
        stateTracker.actionStarted(
            ActionStartedEvent(mockAction("Some action", "foo"), clock.nanoTime())
        )

        Truth.assertWithMessage("Progress bar showing a running action should be time dependent")
            .that(stateTracker.progressBarTimeDependent())
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCountVisible() {
        // The test count should be visible in the status bar, as well as the short status bar
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        val filteringComplete: TestFilteringCompleteEvent =
            Mockito.mock<TestFilteringCompleteEvent>(TestFilteringCompleteEvent::class.java)
        val labelA: Label? = Label.parseCanonical("//foo/bar:baz")
        val targetA: ConfiguredTarget = Mockito.mock<ConfiguredTarget>(ConfiguredTarget::class.java)
        Mockito.`when`<T?>(targetA.getLabel()).thenReturn(labelA)
        val targetB: ConfiguredTarget? = Mockito.mock<ConfiguredTarget?>(ConfiguredTarget::class.java)
        Mockito.`when`<MutableCollection<ConfiguredTarget>?>(filteringComplete.getTestTargets())
            .thenReturn(com.google.common.collect.ImmutableSet.of<ConfiguredTarget>(targetA, targetB))
        val testSummary: TestSummary = Mockito.mock<TestSummary>(TestSummary::class.java)
        Mockito.`when`<T?>(testSummary.getTarget()).thenReturn(targetA)
        Mockito.`when`<T?>(testSummary.getLabel()).thenReturn(labelA)

        stateTracker.testFilteringComplete(filteringComplete)
        stateTracker.testSummary(testSummary)

        var terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        var output: String = terminalWriter.getTranscript()
        Truth.assertWithMessage("Test count should be visible in output: %s", output)
            .that(output.contains(" 1 / 2 tests"))
            .isTrue()

        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter,  /* shortVersion= */true)
        output = terminalWriter.getTranscript()
        Truth.assertWithMessage("Test count should be visible in short output: %s", output)
            .that(output.contains(" 1 / 2 tests"))
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPassedVisible() {
        // The last test should still be visible in the long status bar, and colored as ok if it passed.
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        val filteringComplete: TestFilteringCompleteEvent =
            Mockito.mock<TestFilteringCompleteEvent>(TestFilteringCompleteEvent::class.java)
        val labelA: Label? = Label.parseCanonical("//foo/bar:baz")
        val targetA: ConfiguredTarget = Mockito.mock<ConfiguredTarget>(ConfiguredTarget::class.java)
        Mockito.`when`<T?>(targetA.getLabel()).thenReturn(labelA)
        val targetB: ConfiguredTarget? = Mockito.mock<ConfiguredTarget?>(ConfiguredTarget::class.java)
        Mockito.`when`<MutableCollection<ConfiguredTarget>?>(filteringComplete.getTestTargets())
            .thenReturn(com.google.common.collect.ImmutableSet.of<ConfiguredTarget>(targetA, targetB))
        val testSummary: TestSummary = Mockito.mock<TestSummary>(TestSummary::class.java)
        Mockito.`when`<T?>(testSummary.getStatus()).thenReturn(BlazeTestStatus.PASSED)
        Mockito.`when`<T?>(testSummary.getTarget()).thenReturn(targetA)
        Mockito.`when`<T?>(testSummary.getLabel()).thenReturn(labelA)

        stateTracker.testFilteringComplete(filteringComplete)
        stateTracker.testSummary(testSummary)

        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter()
        stateTracker.writeProgressBar(terminalWriter)
        val output: String = terminalWriter.getTranscript()

        val expected = LoggingTerminalWriter.OK + labelA
        Truth.assertWithMessage(
            "Sequence '%s' should be present in colored progress bar: %s", expected, output
        )
            .that(output.contains(expected))
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFailedVisible() {
        // The last test should still be visible in the long status bar, and colored as fail if it
        // did not pass.
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        val filteringComplete: TestFilteringCompleteEvent =
            Mockito.mock<TestFilteringCompleteEvent>(TestFilteringCompleteEvent::class.java)
        val labelA: Label? = Label.parseCanonical("//foo/bar:baz")
        val targetA: ConfiguredTarget = Mockito.mock<ConfiguredTarget>(ConfiguredTarget::class.java)
        Mockito.`when`<T?>(targetA.getLabel()).thenReturn(labelA)
        val targetB: ConfiguredTarget? = Mockito.mock<ConfiguredTarget?>(ConfiguredTarget::class.java)
        Mockito.`when`<MutableCollection<ConfiguredTarget>?>(filteringComplete.getTestTargets())
            .thenReturn(com.google.common.collect.ImmutableSet.of<ConfiguredTarget>(targetA, targetB))
        val testSummary: TestSummary = Mockito.mock<TestSummary>(TestSummary::class.java)
        Mockito.`when`<T?>(testSummary.getStatus()).thenReturn(BlazeTestStatus.FAILED)
        Mockito.`when`<T?>(testSummary.getTarget()).thenReturn(targetA)
        Mockito.`when`<T?>(testSummary.getLabel()).thenReturn(labelA)

        stateTracker.testFilteringComplete(filteringComplete)
        stateTracker.testSummary(testSummary)

        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter()
        stateTracker.writeProgressBar(terminalWriter)
        val output: String = terminalWriter.getTranscript()

        val expected = LoggingTerminalWriter.FAIL + labelA
        Truth.assertWithMessage(
            "Sequence '%s' should be present in colored progress bar: %s", expected, output
        )
            .that(output.contains(expected))
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSensibleShortening() {
        // Verify that in the typical case, we shorten the progress message by shortening
        // the path implicit in it, that can also be extracted from the label. In particular,
        // the parts
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val stateTracker: UiStateTracker = getUiStateTracker(clock,  /* targetWidth= */70)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        val action: Action =
            mockAction(
                "Building some/very/very/long/path/for/some/library/directory/foo.jar (42 source"
                        + " files)",
                "some/very/very/long/path/for/some/library/directory/foo.jar"
            )
        val label: Label? =
            Label.parseCanonical("//some/very/very/long/path/for/some/library/directory:libfoo")
        val owner: ActionOwner? =
            ActionOwner.createDummy(
                label,
                net.starlark.java.syntax.Location("dummy-file", 0, 0),  /* targetKind= */
                "dummy-target-kind",  /* buildConfigurationMnemonic= */
                "dummy-mnemonic",  /* configurationChecksum= */
                "fedcba",
                BuildConfigurationEvent(
                    BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
                    BuildEventStreamProtos.BuildEvent.getDefaultInstance()
                ),  /* isToolConfiguration= */
                false,  /* executionPlatform= */
                PlatformInfo.EMPTY_PLATFORM_INFO,  /* aspectDescriptors= */
                com.google.common.collect.ImmutableList.of<E?>(),  /* execProperties= */
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        Mockito.`when`<T?>(action.getOwner()).thenReturn(owner)

        clock.advanceMillis(TimeUnit.SECONDS.toMillis(3))
        stateTracker.actionStarted(ActionStartedEvent(action, clock.nanoTime()))
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(5))

        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        val output: String = terminalWriter.getTranscript()

        Truth.assertWithMessage("Progress bar should contain 'Building ', but was:\n%s", output)
            .that(output.contains("Building "))
            .isTrue()
        Truth.assertWithMessage(
            "Progress bar should contain 'foo.jar (42 source files)', but was:\n%s", output
        )
            .that(output.contains("foo.jar (42 source files)"))
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionStrategyVisible() {
        // verify that, if a strategy was reported for a shown action, it is visible
        // in the progress bar.
        val strategy = "verySpecialStrategy"
        val primaryOutput = "some/path/to/a/file"

        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val path: Path? = outputBase.getRelative(PathFragment.create(primaryOutput))
        val artifact: Artifact? =
            ActionsTestUtil.createArtifact(ArtifactRoot.asSourceRoot(Root.fromPath(outputBase)), path)
        val action: Action = mockAction("Some random action", primaryOutput)
        Mockito.`when`<T?>(action.getOwner()).thenReturn(dummyActionOwner())
        Mockito.`when`<T?>(action.getPrimaryOutput()).thenReturn(artifact)

        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        stateTracker.actionStarted(ActionStartedEvent(action, clock.nanoTime()))
        stateTracker.runningAction(RunningActionEvent(action, strategy))

        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        val output: String = terminalWriter.getTranscript()

        Truth.assertWithMessage("Output should mention strategy '%s', but was: %s", strategy, output)
            .that(output.contains(strategy))
            .isTrue()
    }

    @Throws(LabelSyntaxException::class)
    private fun createDummyAction(progressMessage: String?): Action {
        val primaryOutput = "some/path/to/a/file"
        val path: Path? = outputBase.getRelative(PathFragment.create(primaryOutput))
        val artifact: Artifact? =
            ActionsTestUtil.createArtifact(ArtifactRoot.asSourceRoot(Root.fromPath(outputBase)), path)
        val action: Action = mockAction(progressMessage, primaryOutput)
        Mockito.`when`<T?>(action.getOwner()).thenReturn(dummyActionOwner())
        Mockito.`when`<T?>(action.getPrimaryOutput()).thenReturn(artifact)
        return action
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionProgress_visible() {
        // arrange
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val action: Action = createDummyAction("Some random action")
        val stateTracker: UiStateTracker = getUiStateTracker(clock,  /* targetWidth= */70)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        stateTracker.actionStarted(ActionStartedEvent(action, clock.nanoTime()))
        stateTracker.actionProgress(
            ActionProgressEvent.create(action, "action-id", "action progress", false)
        )
        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)

        // act
        stateTracker.writeProgressBar(terminalWriter)

        // assert
        val output: String? = terminalWriter.getTranscript()
        Truth.assertThat(output).contains("action progress")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionProgress_withTooSmallWidth_progressSkipped() {
        // arrange
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val action: Action = createDummyAction("Some random action")
        val stateTracker: UiStateTracker = getUiStateTracker(clock,  /* targetWidth= */30)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        stateTracker.actionStarted(ActionStartedEvent(action, clock.nanoTime()))
        stateTracker.actionProgress(
            ActionProgressEvent.create(action, "action-id", "action progress", false)
        )
        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)

        // act
        stateTracker.writeProgressBar(terminalWriter)

        // assert
        val output: String? = terminalWriter.getTranscript()
        Truth.assertThat(output).doesNotContain("action progress")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionProgress_withSmallWidth_progressShortened() {
        // arrange
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val action: Action = createDummyAction("some action")
        // The targetWidth needs to be small enough to cause shortening to occur.
        val stateTracker: UiStateTracker = getUiStateTracker(clock,  /* targetWidth= */40)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        stateTracker.actionStarted(ActionStartedEvent(action, clock.nanoTime()))
        stateTracker.actionProgress(
            ActionProgressEvent.create(action, "action-id", "action progress", false)
        )
        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)

        // act
        stateTracker.writeProgressBar(terminalWriter)

        // assert
        val output: String? = terminalWriter.getTranscript()
        Truth.assertThat(output).contains("action p...")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionProgress_multipleProgress_displayInOrder() {
        // arrange
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val action: Action = createDummyAction("Some random action")
        val stateTracker: UiStateTracker = getUiStateTracker(clock,  /* targetWidth= */70)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        stateTracker.actionStarted(ActionStartedEvent(action, clock.nanoTime()))
        stateTracker.actionProgress(
            ActionProgressEvent.create(action, "action-id1", "action progress 1", false)
        )
        stateTracker.actionProgress(
            ActionProgressEvent.create(action, "action-id2", "action progress 2", false)
        )
        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)

        // act
        stateTracker.writeProgressBar(terminalWriter)

        // assert
        val output: String? = terminalWriter.getTranscript()
        Truth.assertThat(output).contains("action progress 1")
        Truth.assertThat(output).doesNotContain("action progress 2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleActionStrategiesVisibleForDynamicScheduling() {
        val strategy1 = "strategy1"
        val strategy2 = "stratagy2"
        val primaryOutput = "some/path/to/a/file"

        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val path: Path? = outputBase.getRelative(PathFragment.create(primaryOutput))
        val artifact: Artifact? =
            ActionsTestUtil.createArtifact(ArtifactRoot.asSourceRoot(Root.fromPath(outputBase)), path)
        val action: Action = mockAction("Some random action", primaryOutput)
        Mockito.`when`<T?>(action.getOwner()).thenReturn(dummyActionOwner())
        Mockito.`when`<T?>(action.getPrimaryOutput()).thenReturn(artifact)

        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        stateTracker.actionStarted(ActionStartedEvent(action, clock.nanoTime()))
        stateTracker.runningAction(RunningActionEvent(action, strategy1))
        stateTracker.runningAction(RunningActionEvent(action, strategy2))

        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        val output: String = terminalWriter.getTranscript()

        Truth.assertWithMessage(
            "Output should mention strategies '%s' and '%s', but was: %s",
            strategy1, strategy2, output
        )
            .that(output.contains(strategy1 + ", " + strategy2))
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionCountsWithDynamicScheduling() {
        val primaryOutput1 = "some/path/to/a/file"
        val primaryOutput2 = "some/path/to/b/file"

        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)

        val path1: Path? = outputBase.getRelative(PathFragment.create(primaryOutput1))
        val artifact1: Artifact? =
            ActionsTestUtil.createArtifact(ArtifactRoot.asSourceRoot(Root.fromPath(outputBase)), path1)
        val action1: Action = mockAction("First random action", primaryOutput1)
        Mockito.`when`<T?>(action1.getOwner()).thenReturn(dummyActionOwner())
        Mockito.`when`<T?>(action1.getPrimaryOutput()).thenReturn(artifact1)
        stateTracker.actionStarted(ActionStartedEvent(action1, clock.nanoTime()))

        val path2: Path? = outputBase.getRelative(PathFragment.create(primaryOutput2))
        val artifact2: Artifact? =
            ActionsTestUtil.createArtifact(ArtifactRoot.asSourceRoot(Root.fromPath(outputBase)), path2)
        val action2: Action = mockAction("First random action", primaryOutput1)
        Mockito.`when`<T?>(action2.getOwner()).thenReturn(dummyActionOwner())
        Mockito.`when`<T?>(action2.getPrimaryOutput()).thenReturn(artifact2)
        stateTracker.actionStarted(ActionStartedEvent(action2, clock.nanoTime()))

        stateTracker.runningAction(RunningActionEvent(action1, "strategy1"))
        stateTracker.schedulingAction(SchedulingActionEvent(action2, "strategy1"))
        terminalWriter.reset()
        stateTracker.writeProgressBar(terminalWriter)
        com.google.common.truth.Subject.contains("2 actions, 1 running")

        stateTracker.runningAction(RunningActionEvent(action1, "strategy2"))
        terminalWriter.reset()
        stateTracker.writeProgressBar(terminalWriter)
        com.google.common.truth.Subject.contains("2 actions, 1 running")

        stateTracker.runningAction(RunningActionEvent(action2, "strategy1"))
        terminalWriter.reset()
        stateTracker.writeProgressBar(terminalWriter)
        com.google.common.truth.Subject.contains("2 actions running")

        stateTracker.runningAction(RunningActionEvent(action2, "strategy2"))
        terminalWriter.reset()
        stateTracker.writeProgressBar(terminalWriter)
        com.google.common.truth.Subject.contains("2 actions running")
    }

    @Throws(java.lang.Exception::class)
    private fun doTestOutputLength(withTest: Boolean, actions: Int) {
        // If we target 70 characters, then there should be enough space to both,
        // keep the line limit, and show the local part of the running actions and
        // the passed test.
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val stateTracker: UiStateTracker = getUiStateTracker(clock,  /* targetWidth= */70)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)

        val foobuildAction: Action =
            mockAction(
                "Building"
                        + " //src/some/very/long/path/long/long/long/long/long/long/long/foo/foobuild.jar",
                "src/some/very/long/path/long/long/long/long/long/long/long/foo/foobuild.jar"
            )
        val bazbuildAction: Action =
            mockAction(
                "Building"
                        + " //src/some/very/long/path/long/long/long/long/long/long/long/baz/bazbuild.jar",
                "src/some/very/long/path/long/long/long/long/long/long/long/baz/bazbuild.jar"
            )

        val bartestLabel: Label? =
            Label.parseCanonical(
                "//src/another/very/long/long/path/long/long/long/long/long/long/long/long/bars:bartest"
            )
        val bartestTarget: ConfiguredTarget = Mockito.mock<ConfiguredTarget>(ConfiguredTarget::class.java)
        Mockito.`when`<T?>(bartestTarget.getLabel()).thenReturn(bartestLabel)

        val filteringComplete: TestFilteringCompleteEvent =
            Mockito.mock<TestFilteringCompleteEvent>(TestFilteringCompleteEvent::class.java)
        Mockito.`when`<MutableCollection<ConfiguredTarget>?>(filteringComplete.getTestTargets())
            .thenReturn(com.google.common.collect.ImmutableSet.of<ConfiguredTarget>(bartestTarget))

        val testSummary: TestSummary = Mockito.mock<TestSummary>(TestSummary::class.java)
        Mockito.`when`<T?>(testSummary.getStatus()).thenReturn(BlazeTestStatus.PASSED)
        Mockito.`when`<T?>(testSummary.getTarget()).thenReturn(bartestTarget)
        Mockito.`when`<T?>(testSummary.getLabel()).thenReturn(bartestLabel)

        if (actions >= 1) {
            stateTracker.actionStarted(ActionStartedEvent(foobuildAction, 123456789))
        }
        if (actions >= 2) {
            stateTracker.actionStarted(ActionStartedEvent(bazbuildAction, 123456900))
        }
        if (withTest) {
            stateTracker.testFilteringComplete(filteringComplete)
            stateTracker.testSummary(testSummary)
        }

        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        val output: String = terminalWriter.getTranscript()

        Truth.assertWithMessage(
            "Only lines with at most 70 chars should be present in the output:\n%s", output
        )
            .that(longestLine(output) <= 70)
            .isTrue()
        if (actions >= 1) {
            Truth.assertWithMessage("Running action 'foobuild' should be mentioned in output:\n%s", output)
                .that(output.contains("foobuild"))
                .isTrue()
        }
        if (actions >= 2) {
            Truth.assertWithMessage("Running action 'bazbuild' should be mentioned in output:\n%s", output)
                .that(output.contains("bazbuild"))
                .isTrue()
        }
        if (withTest) {
            Truth.assertWithMessage("Passed test ':bartest' should be mentioned in output:\n%s", output)
                .that(output.contains(":bartest"))
                .isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputLength() {
        for (i in 0..2) {
            doTestOutputLength(true, i)
            doTestOutputLength(false, i)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStatusShown() {
        // Verify that for non-executing actions, at least the first 3 characters of the
        // status are shown.
        // Also verify that the number of running actions is reported correctly, if there is
        // more than one active action and not all are running.
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        clock.advanceMillis(120000)
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        val actionFoo: Action = mockAction("Building foo", "foo/foo")
        val ownerFoo: ActionOwner = dummyActionOwner()
        Mockito.`when`<T?>(actionFoo.getOwner()).thenReturn(ownerFoo)
        val actionBar: Action = mockAction("Building bar", "bar/bar")
        val ownerBar: ActionOwner = dummyActionOwner()
        Mockito.`when`<T?>(actionBar.getOwner()).thenReturn(ownerBar)
        var terminalWriter: LoggingTerminalWriter?
        var output: String

        // Action foo being scanned.
        stateTracker.actionStarted(ActionStartedEvent(actionFoo, 123456700))
        stateTracker.scanningAction(ScanningActionEvent(actionFoo))

        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        output = terminalWriter.getTranscript()
        Truth.assertWithMessage("Action foo being scanned should be visible in output:\n%s", output)
            .that(output.contains("sca") || output.contains("Sca"))
            .isTrue()

        // Then action bar gets scheduled.
        stateTracker.actionStarted(ActionStartedEvent(actionBar, 123456701))
        stateTracker.schedulingAction(SchedulingActionEvent(actionBar, "bar-sandbox"))

        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        output = terminalWriter.getTranscript()
        Truth.assertWithMessage("Action bar being scheduled should be visible in output:\n%s", output)
            .that(output.contains("sch") || output.contains("Sch"))
            .isTrue()
        Truth.assertWithMessage("Action foo being scanned should still be visible in output:\n%s", output)
            .that(output.contains("sca") || output.contains("Sca"))
            .isTrue()
        Truth.assertWithMessage("Indication that no actions are running is missing in output:\n%s", output)
            .that(output.contains("0 running"))
            .isTrue()
        Truth.assertWithMessage("Total number of actions expected  in output:\n%s", output)
            .that(output.contains("2 actions"))
            .isTrue()

        // Then foo starts.
        stateTracker.runningAction(RunningActionEvent(actionFoo, "xyz-sandbox"))
        stateTracker.writeProgressBar(terminalWriter)

        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        output = terminalWriter.getTranscript()
        Truth.assertWithMessage("Action foo's xyz-sandbox strategy should be shown in output:\n%s", output)
            .that(output.contains("xyz-sandbox"))
            .isTrue()
        Truth.assertWithMessage("Action foo should no longer be analyzed in output:\n%s", output)
            .that(output.contains("ana") || output.contains("Ana"))
            .isFalse()
        Truth.assertWithMessage("Action bar being scheduled should still be visible in output:\n%s", output)
            .that(output.contains("sch") || output.contains("Sch"))
            .isTrue()
        Truth.assertWithMessage("Indication that one action is running is missing in output:\n%s", output)
            .that(output.contains("1 running"))
            .isTrue()
        Truth.assertWithMessage("Total number of actions expected  in output:\n%s", output)
            .that(output.contains("2 actions"))
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTimerReset() {
        // Verify that a change in an action state (e.g., from scheduling to executing) resets
        // the time associated with that action.

        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(123))
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(2))
        var terminalWriter: LoggingTerminalWriter?
        var output: String

        val actionFoo: Action = mockAction("Building foo", "foo/foo")
        val ownerFoo: ActionOwner = dummyActionOwner()
        Mockito.`when`<T?>(actionFoo.getOwner()).thenReturn(ownerFoo)
        val actionBar: Action = mockAction("Building bar", "bar/bar")
        val ownerBar: ActionOwner = dummyActionOwner()
        Mockito.`when`<T?>(actionBar.getOwner()).thenReturn(ownerBar)

        stateTracker.actionStarted(ActionStartedEvent(actionFoo, clock.nanoTime()))
        stateTracker.runningAction(RunningActionEvent(actionFoo, "foo-sandbox"))
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(7))
        stateTracker.actionStarted(ActionStartedEvent(actionBar, clock.nanoTime()))
        stateTracker.schedulingAction(SchedulingActionEvent(actionBar, "bar-sandbox"))
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(21))

        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        output = terminalWriter.getTranscript()
        Truth.assertWithMessage("Runtime of first action should be visible in output: %s", output)
            .that(output.contains("28s"))
            .isTrue()
        Truth.assertWithMessage("Scheduling time of second action should be visible in output: %s", output)
            .that(output.contains("21s"))
            .isTrue()

        stateTracker.runningAction(RunningActionEvent(actionBar, "bar-sandbox"))
        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        output = terminalWriter.getTranscript()
        Truth.assertWithMessage("Runtime of first action should still be visible in output: %s", output)
            .that(output.contains("28s"))
            .isTrue()
        Truth.assertWithMessage("Time of second action should no longer be visible in output: %s", output)
            .that(output.contains("21s"))
            .isFalse()

        clock.advanceMillis(TimeUnit.SECONDS.toMillis(30))
        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        output = terminalWriter.getTranscript()
        Truth.assertWithMessage("New runtime of first action should be visible in output: %s", output)
            .that(output.contains("58s"))
            .isTrue()
        Truth.assertWithMessage("Runtime of second action should be visible in output: %s", output)
            .that(output.contains("30s"))
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEarlyStatusHandledGracefully() {
        // On the event bus, events sometimes are sent out of order; verify that we handle an
        // early message that an action is running gracefully.
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        val actionFoo: Action = mockAction("Building foo", "foo/foo")
        val ownerFoo: ActionOwner = dummyActionOwner()
        Mockito.`when`<T?>(actionFoo.getOwner()).thenReturn(ownerFoo)
        var terminalWriter: LoggingTerminalWriter?
        var output: String

        // Early status announcement
        stateTracker.runningAction(RunningActionEvent(actionFoo, "foo-sandbox"))

        // Here we don't expect any particular output, just some description; in particular, we do
        // not expect the state tracker to hit an internal error.
        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        output = terminalWriter.getTranscript()
        Truth.assertWithMessage("Expected at least some status bar").that(output).isNotEmpty()

        // Action actually started
        stateTracker.actionStarted(ActionStartedEvent(actionFoo, clock.nanoTime()))

        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        output = terminalWriter.getTranscript()
        Truth.assertWithMessage("Even a strategy announced early should be shown in output:\n%s", output)
            .that(output.contains("foo-sandbox"))
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutingActionsFirst() {
        // Verify that executing actions, even if started late, are visible.
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        clock.advanceMillis(120000)

        for (i in 0..29) {
            val action: Action = mockAction("Takes long to schedule number " + i, "long/startup" + i)
            val owner: ActionOwner = dummyActionOwner()
            Mockito.`when`<T?>(action.getOwner()).thenReturn(owner)
            stateTracker.actionStarted(ActionStartedEvent(action, 123456789 + i))
            stateTracker.schedulingAction(SchedulingActionEvent(action, "xyz-sandbox"))
        }

        for (i in 0..2) {
            val action: Action = mockAction("quickstart" + i, "pkg/quickstart" + i)
            val owner: ActionOwner = dummyActionOwner()
            Mockito.`when`<T?>(action.getOwner()).thenReturn(owner)
            stateTracker.actionStarted(ActionStartedEvent(action, 123457000 + i))
            stateTracker.runningAction(RunningActionEvent(action, "xyz-sandbox"))

            val terminalWriter: LoggingTerminalWriter =
                LoggingTerminalWriter( /* discardHighlight= */true)
            stateTracker.writeProgressBar(terminalWriter)
            val output: String = terminalWriter.getTranscript()
            Truth.assertWithMessage("Action quickstart%s should be visible in output:\n%s", i, output)
                .that(output.contains("quickstart" + i))
                .isTrue()
            Truth.assertWithMessage("Number of running actions should be indicated in output:\n%s", output)
                .that(output.contains((i + 1).toString() + " running"))
                .isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAggregation() {
        // Assert that actions for the same test are aggregated so that an action afterwards
        // is still shown.
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(1234))
        val stateTracker: UiStateTracker = getUiStateTracker(clock,  /* targetWidth= */80)
        stateTracker.setProgressSampleSize(4)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)

        val labelFooTest: Label? = Label.parseCanonical("//foo/bar:footest")
        val targetFooTest: ConfiguredTarget = Mockito.mock<ConfiguredTarget>(ConfiguredTarget::class.java)
        Mockito.`when`<T?>(targetFooTest.getLabel()).thenReturn(labelFooTest)
        val fooOwner: ActionOwner? =
            ActionOwner.createDummy(
                labelFooTest,
                net.starlark.java.syntax.Location("dummy-file", 0, 0),  /* targetKind= */
                "dummy-target-kind",  /* buildConfigurationMnemonic= */
                "TestRunner",  /* configurationChecksum= */
                "abcdef",
                BuildConfigurationEvent(
                    BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
                    BuildEventStreamProtos.BuildEvent.getDefaultInstance()
                ),  /* isToolConfiguration= */
                false,  /* executionPlatform= */
                PlatformInfo.EMPTY_PLATFORM_INFO,  /* aspectDescriptors= */
                com.google.common.collect.ImmutableList.of<E?>(),  /* execProperties= */
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )

        val labelBarTest: Label? = Label.parseCanonical("//baz:bartest")
        val targetBarTest: ConfiguredTarget = Mockito.mock<ConfiguredTarget>(ConfiguredTarget::class.java)
        Mockito.`when`<T?>(targetBarTest.getLabel()).thenReturn(labelBarTest)
        val barOwner: ActionOwner? =
            ActionOwner.createDummy(
                labelBarTest,
                net.starlark.java.syntax.Location("dummy-file", 0, 0),  /* targetKind= */
                "dummy-target-kind",  /* buildConfigurationMnemonic= */
                "TestRunner",  /* configurationChecksum= */
                "abcdef",
                BuildConfigurationEvent(
                    BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
                    BuildEventStreamProtos.BuildEvent.getDefaultInstance()
                ),  /* isToolConfiguration= */
                false,  /* executionPlatform= */
                PlatformInfo.EMPTY_PLATFORM_INFO,  /* aspectDescriptors= */
                com.google.common.collect.ImmutableList.of<E?>(),  /* execProperties= */
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )

        val labelBazTest: Label? = Label.parseCanonical("//baz:baztest")
        val targetBazTest: ConfiguredTarget = Mockito.mock<ConfiguredTarget>(ConfiguredTarget::class.java)
        Mockito.`when`<T?>(targetBazTest.getLabel()).thenReturn(labelBazTest)
        val bazOwner: ActionOwner? =
            ActionOwner.createDummy(
                labelBazTest,
                net.starlark.java.syntax.Location("dummy-file", 0, 0),  /* targetKind= */
                "dummy-target-kind",  /* buildConfigurationMnemonic= */
                "NonTestAction",  /* configurationChecksum= */
                "fedcba",
                BuildConfigurationEvent(
                    BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
                    BuildEventStreamProtos.BuildEvent.getDefaultInstance()
                ),  /* isToolConfiguration= */
                false,  /* executionPlatform= */
                PlatformInfo.EMPTY_PLATFORM_INFO,  /* aspectDescriptors= */
                com.google.common.collect.ImmutableList.of<E?>(),  /* execProperties= */
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )

        val filteringComplete: TestFilteringCompleteEvent =
            Mockito.mock<TestFilteringCompleteEvent>(TestFilteringCompleteEvent::class.java)
        Mockito.`when`<MutableCollection<ConfiguredTarget>?>(filteringComplete.getTestTargets())
            .thenReturn(
                com.google.common.collect.ImmutableSet.of<ConfiguredTarget>(
                    targetFooTest,
                    targetBarTest,
                    targetBazTest
                )
            )
        stateTracker.testFilteringComplete(filteringComplete)

        // First produce 10 actions for footest...
        for (i in 0..9) {
            clock.advanceMillis(TimeUnit.SECONDS.toMillis(1))
            val action: Action = mockAction("Testing foo, shard " + i, "testlog_foo_" + i)
            Mockito.`when`<T?>(action.getOwner()).thenReturn(fooOwner)
            stateTracker.actionStarted(ActionStartedEvent(action, clock.nanoTime()))
        }
        // ...then produce 10 actions for bartest...
        for (i in 0..9) {
            clock.advanceMillis(TimeUnit.SECONDS.toMillis(1))
            val action: Action = mockAction("Testing bar, shard " + i, "testlog_bar_" + i)
            Mockito.`when`<T?>(action.getOwner()).thenReturn(barOwner)
            stateTracker.actionStarted(ActionStartedEvent(action, clock.nanoTime()))
        }
        // ...run a completely unrelated action..
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(1))
        stateTracker.actionStarted(
            ActionStartedEvent(mockAction("Other action", "other/action"), clock.nanoTime())
        )
        // ...and finally, run actions that are associated with baztest but are not a test.
        for (i in 0..9) {
            clock.advanceMillis(1000)
            val action: Action = mockAction("Doing something " + i, "someartifact_" + i)
            Mockito.`when`<T?>(action.getOwner()).thenReturn(bazOwner)
            stateTracker.actionStarted(ActionStartedEvent(action, clock.nanoTime()))
        }
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(1))

        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        val output: String = terminalWriter.getTranscript()

        Truth.assertWithMessage("Progress bar should contain ':footest', but was:\n%s", output)
            .that(output.contains(":footest"))
            .isTrue()
        Truth.assertWithMessage("Progress bar should contain ':bartest', but was:\n%s", output)
            .that(output.contains(":bartest"))
            .isTrue()
        Truth.assertWithMessage("Progress bar should contain 'Other action', but was:\n%s", output)
            .that(output.contains("Other action"))
            .isTrue()
        Truth.assertThat(output).doesNotContain("Testing //baz:baztest")
        Truth.assertThat(output).contains("Doing something")
    }

    @org.junit.Test
    fun testSuffix() {
        assertThat(UiStateTracker.suffix("foobar", 3)).isEqualTo("bar")
        assertThat(UiStateTracker.suffix("foo", -2)).isEmpty()
        assertThat(UiStateTracker.suffix("foobar", 200)).isEqualTo("foobar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDownloadShown_duringLoading() {
        // Verify that, whenever a single download is running in loading phase, it is shown in the
        // status bar.
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        clock.advance(java.time.Duration.ofSeconds(1234))
        val stateTracker: UiStateTracker = getUiStateTracker(clock,  /* targetWidth= */80)

        val url: java.net.URI = java.net.URI.create("http://example.org/first/dep")

        stateTracker.buildStarted()
        stateTracker.downloadProgress(DownloadProgressEvent(url))
        clock.advance(java.time.Duration.ofSeconds(6))

        var terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        var output: String? = terminalWriter.getTranscript()

        Truth.assertThat(output).contains(url.toString())
        Truth.assertThat(output).contains("6s")

        // Progress on the pending download should be reported appropriately
        clock.advance(java.time.Duration.ofSeconds(1))
        stateTracker.downloadProgress(DownloadProgressEvent(url, 256))

        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        output = terminalWriter.getTranscript()

        Truth.assertThat(output).contains(url.toString())
        Truth.assertThat(output).contains("7s")
        Truth.assertThat(output).contains("256")

        // After finishing the download, it should no longer be reported.
        clock.advance(java.time.Duration.ofSeconds(1))
        stateTracker.downloadProgress(DownloadProgressEvent(url, 256, true))

        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        output = terminalWriter.getTranscript()

        Truth.assertThat(output).doesNotContain("example.org")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDownloadShown_duringMainRepoMappingComputation() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        clock.advance(java.time.Duration.ofSeconds(1234))
        val stateTracker: UiStateTracker = getUiStateTracker(clock,  /* targetWidth= */80)

        val url: java.net.URI = java.net.URI.create("http://example.org/first/dep")

        stateTracker.mainRepoMappingComputationStarted()
        stateTracker.downloadProgress(DownloadProgressEvent(url))
        clock.advance(java.time.Duration.ofSeconds(6))

        var terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        var output: String? = terminalWriter.getTranscript()

        Truth.assertThat(output).contains(url.toString())
        Truth.assertThat(output).contains("6s")

        // Progress on the pending download should be reported appropriately
        clock.advance(java.time.Duration.ofSeconds(1))
        stateTracker.downloadProgress(DownloadProgressEvent(url, 256))

        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        output = terminalWriter.getTranscript()

        Truth.assertThat(output).contains(url.toString())
        Truth.assertThat(output).contains("7s")
        Truth.assertThat(output).contains("256")

        // After finishing the download, it should no longer be reported.
        clock.advance(java.time.Duration.ofSeconds(1))
        stateTracker.downloadProgress(DownloadProgressEvent(url, 256, true))

        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        output = terminalWriter.getTranscript()

        Truth.assertThat(output).doesNotContain("example.org")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDownloadOutputLength() {
        // Verify that URLs are shortened in a reasonable way, if the terminal is not wide enough
        // Also verify that the length is respected, even if only a download sample is shown.
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(1234))
        val stateTracker: UiStateTracker = getUiStateTracker(clock,  /* targetWidth= */60)
        val url: java.net.URI =
            java.net.URI.create("http://example.org/some/really/very/very/long/path/filename.tar.gz")

        stateTracker.buildStarted()
        stateTracker.downloadProgress(DownloadProgressEvent(url))
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(6))
        for (i in 0..9) {
            stateTracker.downloadProgress(
                DownloadProgressEvent(
                    java.net.URI.create(
                        ("http://otherhost.example/another/also/length/path/to/another/download"
                                + i
                                + ".zip")
                    )
                )
            )
            clock.advanceMillis(TimeUnit.SECONDS.toMillis(1))
        }

        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        val output: String = terminalWriter.getTranscript()

        Truth.assertWithMessage(
            "Only lines with at most 60 chars should be present in the output:\n%s", output
        )
            .that(longestLine(output) <= 60)
            .isTrue()
        Truth.assertWithMessage("Output still should contain the filename, but was:\n%s", output)
            .that(output.contains("filename.tar.gz"))
            .isTrue()
        Truth.assertWithMessage("Output still should contain the host name, but was:\n%s", output)
            .that(output.contains("example.org"))
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleBuildEventProtocolTransports() {
        // Verify that all announced transports are present in the progress bar
        // and that as transports are closed they disappear from the progress bar.
        // Verify that the wait duration is displayed.
        // Verify that after all transports have been closed, the build status is displayed.
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val transport1: BuildEventTransport = newBepTransport("BuildEventTransport1")
        val transport2: BuildEventTransport = newBepTransport("BuildEventTransport2")
        val transport3: BuildEventTransport = newBepTransport("BuildEventTransport3")
        val buildResult: BuildResult = BuildResult(clock.currentTimeMillis())
        buildResult.setDetailedExitCode(DetailedExitCode.success())
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(1))
        buildResult.setStopTime(clock.currentTimeMillis())

        val stateTracker: UiStateTracker = getUiStateTracker(clock,  /* targetWidth= */80)
        stateTracker.buildStarted()
        stateTracker.buildEventTransportsAnnounced(
            AnnounceBuildEventTransportsEvent(com.google.common.collect.ImmutableList.of<E?>(transport1, transport2))
        )
        stateTracker.buildEventTransportsAnnounced(
            AnnounceBuildEventTransportsEvent(com.google.common.collect.ImmutableList.of<E?>(transport3))
        )
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            stateTracker.buildComplete(BuildCompleteEvent(buildResult))

        var terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter(true)

        clock.advanceMillis(TimeUnit.SECONDS.toMillis(1))
        stateTracker.writeProgressBar(terminalWriter)
        var output: String = terminalWriter.getTranscript()
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("1s"))
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("BuildEventTransport1"))
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("BuildEventTransport2"))
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("BuildEventTransport3"))

        clock.advanceMillis(TimeUnit.SECONDS.toMillis(1))
        stateTracker.buildEventTransportClosed(BuildEventTransportClosedEvent(transport1))
        terminalWriter = LoggingTerminalWriter(true)
        stateTracker.writeProgressBar(terminalWriter)
        output = terminalWriter.getTranscript()
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("2s"))
        MatcherAssert.assertThat<String?>(
            output,
            CoreMatchers.not<String?>(CoreMatchers.containsString("BuildEventTransport1"))
        )
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("BuildEventTransport2"))
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("BuildEventTransport3"))

        clock.advanceMillis(TimeUnit.SECONDS.toMillis(1))
        stateTracker.buildEventTransportClosed(BuildEventTransportClosedEvent(transport3))
        terminalWriter = LoggingTerminalWriter(true)
        stateTracker.writeProgressBar(terminalWriter)
        output = terminalWriter.getTranscript()
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("3s"))
        MatcherAssert.assertThat<String?>(
            output,
            CoreMatchers.not<String?>(CoreMatchers.containsString("BuildEventTransport1"))
        )
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("BuildEventTransport2"))
        MatcherAssert.assertThat<String?>(
            output,
            CoreMatchers.not<String?>(CoreMatchers.containsString("BuildEventTransport3"))
        )

        clock.advanceMillis(TimeUnit.SECONDS.toMillis(1))
        stateTracker.buildEventTransportClosed(BuildEventTransportClosedEvent(transport2))
        terminalWriter = LoggingTerminalWriter(true)
        stateTracker.writeProgressBar(terminalWriter)
        output = terminalWriter.getTranscript()
        MatcherAssert.assertThat<String?>(output, CoreMatchers.not<String?>(CoreMatchers.containsString("3s")))
        MatcherAssert.assertThat<String?>(
            output,
            CoreMatchers.not<String?>(CoreMatchers.containsString("BuildEventTransport1"))
        )
        MatcherAssert.assertThat<String?>(
            output,
            CoreMatchers.not<String?>(CoreMatchers.containsString("BuildEventTransport2"))
        )
        MatcherAssert.assertThat<String?>(
            output,
            CoreMatchers.not<String?>(CoreMatchers.containsString("BuildEventTransport3"))
        )
        Truth.assertThat<String?>(output.split("\\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray())
            .hasLength(1)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testBuildEventTransportsOnNarrowTerminal() {
        // Verify that the progress bar contains useful information on a 60-character terminal.
        //   - Too long names should be shortened to reasonably long prefixes of the name.
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val transport1: BuildEventTransport = newBepTransport("A".repeat(61))
        val transport2: BuildEventTransport = newBepTransport("BuildEventTransport")
        val buildResult: BuildResult = BuildResult(clock.currentTimeMillis())
        buildResult.setDetailedExitCode(DetailedExitCode.success())
        var terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter(true)
        val stateTracker: UiStateTracker = getUiStateTracker(clock,  /* targetWidth= */60)
        stateTracker.buildStarted()
        stateTracker.buildEventTransportsAnnounced(
            AnnounceBuildEventTransportsEvent(com.google.common.collect.ImmutableList.of<E?>(transport1, transport2))
        )
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            stateTracker.buildComplete(BuildCompleteEvent(buildResult))
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(1))
        stateTracker.writeProgressBar(terminalWriter)
        var output: String = terminalWriter.getTranscript()
        Truth.assertThat(longestLine(output)).isAtMost(60)
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("1s"))
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("A".repeat(30) + "..."))
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("BuildEventTransport"))

        clock.advanceMillis(TimeUnit.SECONDS.toMillis(1))
        stateTracker.buildEventTransportClosed(BuildEventTransportClosedEvent(transport2))
        terminalWriter = LoggingTerminalWriter(true)
        stateTracker.writeProgressBar(terminalWriter)
        output = terminalWriter.getTranscript()
        Truth.assertThat(longestLine(output)).isAtMost(60)
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("2s"))
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("A".repeat(30) + "..."))
        MatcherAssert.assertThat<String?>(
            output,
            CoreMatchers.not<String?>(CoreMatchers.containsString("BuildEventTransport"))
        )
        Truth.assertThat<String?>(output.split("\\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray())
            .hasLength(2)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testTotalFetchesReported() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val stateTracker: UiStateTracker = getUiStateTracker(clock,  /* targetWidth= */80)

        stateTracker.buildStarted()
        for (i in 0..29) {
            stateTracker.downloadProgress(FetchEvent("@repoFoo" + i))
        }
        clock.advanceMillis(TimeUnit.SECONDS.toMillis(7))

        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter(true)
        stateTracker.writeProgressBar(terminalWriter)
        val output: String? = terminalWriter.getTranscript()
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("@repoFoo"))
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("7s"))
        MatcherAssert.assertThat<String?>(output, CoreMatchers.containsString("30 fetches"))
    }

    private class FetchEvent(val resourceIdentifier: String?) : FetchProgress {
        val progress: String
            get() = "working..."

        val isFinished: Boolean
            get() = false
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun waitingRemoteCacheMessage_beforeBuildComplete_invisible() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val action: Action = mockAction("Some action", "foo")
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        stateTracker.actionUploadStarted(
            ActionUploadStartedEvent.create(
                action, Store.AC, Digest.newBuilder().setHash("foo").setSizeBytes(1).build()
            )
        )
        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)

        stateTracker.writeProgressBar(terminalWriter)

        val output: String? = terminalWriter.getTranscript()
        Truth.assertThat(output).doesNotContain("1 upload")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun waitingRemoteCacheMessage_afterBuildComplete_visible() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val action: Action = mockAction("Some action", "foo")
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        stateTracker.actionUploadStarted(
            ActionUploadStartedEvent.create(
                action, Store.AC, Digest.newBuilder().setHash("foo").setSizeBytes(1).build()
            )
        )
        val buildResult: BuildResult = BuildResult(clock.currentTimeMillis())
        buildResult.setDetailedExitCode(DetailedExitCode.success())
        buildResult.setStopTime(clock.currentTimeMillis())
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            stateTracker.buildComplete(BuildCompleteEvent(buildResult))
        clock.advanceMillis(java.time.Duration.ofSeconds(2).toMillis())
        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)

        stateTracker.writeProgressBar(terminalWriter)

        val output: String? = terminalWriter.getTranscript()
        Truth.assertThat(output).contains("1 upload")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun waitingRemoteCacheMessage_multipleUploadEvents_countCorrectly() {
        val a: Digest? = Digest.newBuilder().setHash("a").setSizeBytes(1).build()
        val b: Digest? = Digest.newBuilder().setHash("b").setSizeBytes(2).build()
        val c: Digest? = Digest.newBuilder().setHash("c").setSizeBytes(3).build()
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val action: Action = mockAction("Some action", "foo")
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        stateTracker.actionUploadStarted(ActionUploadStartedEvent.create(action, Store.AC, a))
        val buildResult: BuildResult = BuildResult(clock.currentTimeMillis())
        buildResult.setDetailedExitCode(DetailedExitCode.success())
        buildResult.setStopTime(clock.currentTimeMillis())
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            stateTracker.buildComplete(BuildCompleteEvent(buildResult))
        stateTracker.actionUploadStarted(ActionUploadStartedEvent.create(action, Store.CAS, b))
        stateTracker.actionUploadStarted(ActionUploadStartedEvent.create(action, Store.CAS, c))
        stateTracker.actionUploadFinished(ActionUploadFinishedEvent.create(action, Store.AC, a))
        clock.advanceMillis(java.time.Duration.ofSeconds(2).toMillis())
        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)

        stateTracker.writeProgressBar(terminalWriter)

        val output: String? = terminalWriter.getTranscript()
        Truth.assertThat(output).contains("2 uploads")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestAnalyzedEvent() {
        // The test count should be visible in the status bar, as well as the short status bar
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        val labelA: Label? = Label.parseCanonical("//foo:A")
        val targetA: ConfiguredTarget = Mockito.mock<ConfiguredTarget>(ConfiguredTarget::class.java)
        Mockito.`when`<T?>(targetA.getLabel()).thenReturn(labelA)
        val testAnalyzedEventA: TestAnalyzedEvent? =
            TestAnalyzedEvent.create(
                targetA, < T > mock < T ? > (BuildConfigurationValue::class.java),  /* isSkipped= */false)
        val labelB: Label? = Label.parseCanonical("//foo:B")
        val targetB: ConfiguredTarget = Mockito.mock<ConfiguredTarget>(ConfiguredTarget::class.java)
        Mockito.`when`<T?>(targetB.getLabel()).thenReturn(labelB)
        val testAnalyzedEventB: TestAnalyzedEvent? =
            TestAnalyzedEvent.create(
                targetB, < T > mock < T ? > (BuildConfigurationValue::class.java),  /* isSkipped= */false)
        // Only targetA has finished running.
        val testSummary: TestSummary = Mockito.mock<TestSummary>(TestSummary::class.java)
        Mockito.`when`<T?>(testSummary.getTarget()).thenReturn(targetA)
        Mockito.`when`<T?>(testSummary.getLabel()).thenReturn(labelA)

        stateTracker.singleTestAnalyzed(testAnalyzedEventA)
        stateTracker.singleTestAnalyzed(testAnalyzedEventB)
        stateTracker.testSummary(testSummary)

        var terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        var output: String? = terminalWriter.getTranscript()
        Truth.assertThat(output).contains(" 1 / 2 tests")

        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter,  /* shortVersion= */true)
        output = terminalWriter.getTranscript()
        Truth.assertThat(output).contains(" 1 / 2 tests")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestAnalyzedEvent_repeated_noDuplicatedCount() {
        // The test count should be visible in the status bar, as well as the short status bar
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        // Mimic being at the execution phase.
        simulateExecutionPhase(stateTracker)
        val labelA: Label? = Label.parseCanonical("//foo:A")
        val targetA: ConfiguredTarget = Mockito.mock<ConfiguredTarget>(ConfiguredTarget::class.java)
        Mockito.`when`<T?>(targetA.getLabel()).thenReturn(labelA)
        val testAnalyzedEventA: TestAnalyzedEvent? =
            TestAnalyzedEvent.create(
                targetA, < T > mock < T ? > (BuildConfigurationValue::class.java),  /* isSkipped= */false)
        val testAnalyzedEventARepeated: TestAnalyzedEvent? =
            TestAnalyzedEvent.create(
                targetA, < T > mock < T ? > (BuildConfigurationValue::class.java),  /* isSkipped= */false)
        // Only targetA has finished running.
        val testSummary: TestSummary = Mockito.mock<TestSummary>(TestSummary::class.java)
        Mockito.`when`<T?>(testSummary.getTarget()).thenReturn(targetA)
        Mockito.`when`<T?>(testSummary.getLabel()).thenReturn(labelA)

        stateTracker.singleTestAnalyzed(testAnalyzedEventA)
        stateTracker.singleTestAnalyzed(testAnalyzedEventARepeated)
        stateTracker.testSummary(testSummary)

        var terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter)
        var output: String? = terminalWriter.getTranscript()
        Truth.assertThat(output).contains(" 1 / 1 tests")

        terminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter,  /* shortVersion= */true)
        output = terminalWriter.getTranscript()
        Truth.assertThat(output).contains(" 1 / 1 tests")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetOldestAction_prioritizesRunningOverScheduled() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        val stateTracker: UiStateTracker = getUiStateTracker(clock)
        simulateExecutionPhase(stateTracker)

        val scheduledAction: Action = mockAction("Scheduled action", "scheduled/action")
        Mockito.`when`<T?>(scheduledAction.getOwner()).thenReturn(dummyActionOwner())
        stateTracker.actionStarted(ActionStartedEvent(scheduledAction, clock.nanoTime()))
        stateTracker.schedulingAction(SchedulingActionEvent(scheduledAction, "some-strategy"))

        clock.advanceMillis(1000)
        val runningAction: Action = mockAction("Running action", "running/action")
        Mockito.`when`<T?>(runningAction.getOwner()).thenReturn(dummyActionOwner())
        stateTracker.actionStarted(ActionStartedEvent(runningAction, clock.nanoTime()))
        stateTracker.runningAction(RunningActionEvent(runningAction, "some-strategy"))

        val terminalWriter: LoggingTerminalWriter = LoggingTerminalWriter( /* discardHighlight= */true)
        stateTracker.writeProgressBar(terminalWriter,  /* shortVersion= */true)
        val output: String? = terminalWriter.getTranscript()

        Truth.assertThat(output).contains("Running action")
        Truth.assertThat(output).contains("(2 actions, 1 running)")
        Truth.assertThat(output).doesNotContain("Scheduled action")
    }

    companion object {
        private fun longestLine(output: String): Int {
            var maxLength = 0
            for (line in output.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
                maxLength = max(maxLength, line.length)
            }
            return maxLength
        }

        private fun newBepTransport(name: String?): BuildEventTransport {
            val transport: BuildEventTransport = Mockito.mock<BuildEventTransport>(BuildEventTransport::class.java)
            Mockito.`when`<T?>(transport.name()).thenReturn(name)
            return transport
        }
    }
}
