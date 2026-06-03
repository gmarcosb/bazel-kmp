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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.actions.Artifact

/** Tests for [UiEventHandler].  */
@RunWith(Enclosed::class)
open class UiEventHandlerTest {
    @TestParameter
    private val skymeldMode = false

    val uiOptions: UiOptions = createUiOptions()
    val output: FlushCollectingOutputStream = FlushCollectingOutputStream()
    val clock: com.google.devtools.build.lib.testutil.ManualClock = com.google.devtools.build.lib.testutil.ManualClock()

    var uiEventHandler: UiEventHandler? = null

    fun createUiOptions(): UiOptions {
        val options: UiOptions = com.google.devtools.common.options.Options.getDefaults<O>(UiOptions::class.java)
        options.setShowProgress(false)
        return options
    }

    fun createUiEventHandler(outputKind: com.google.devtools.build.lib.events.EventKind) {
        uiOptions.setEventKindFilters(com.google.common.collect.ImmutableList.of<E?>())
        output.flush()
        output.flushed.clear()

        val outErr: OutErr? =
            when (outputKind) {
                com.google.devtools.build.lib.events.EventKind.STDOUT -> OutErr.create( /* out= */output,  /* err= */< T > mock < T ? > (java.io.OutputStream::class.java)
                    )
                    com.google.devtools.build.lib.events.EventKind.STDERR

                -> OutErr.create( /* out= */< T > mock < T ? > (java.io.OutputStream::class.java)
                    ,  /* err= */output)
                else -> throw java.lang.AssertionError(outputKind)
            }

        uiEventHandler =
            UiEventHandler(
                outErr,
                uiOptions,  /* quiet= */
                false,
                clock,
                com.google.common.eventbus.EventBus(),  /* workspacePathFragment= */
                null,
                skymeldMode,  /* newStatsSummary= */
                false
            )
        uiEventHandler.mainRepoMappingComputationStarted(MainRepoMappingComputationStartingEvent())
        uiEventHandler.buildStarted(
            BuildStartingEvent.create(
                "outputFileSystemType",  /* usesInMemoryFileSystem= */
                false,
                < T > mock < T ? > (BuildRequest::class.java),  /* workspace= */
            null,
            "/pwd"
        ))
    }

    /** Test cases that exercise both stdout and stderr.  */
    @RunWith(TestParameterInjector::class)
    class StdoutAndStderrTest : UiEventHandlerTest() {
        @TestParameter("STDOUT", "STDERR")
        private val outputKind: com.google.devtools.build.lib.events.EventKind? = null

        @Before
        fun createUiEventHandler() {
            createUiEventHandler(outputKind)
        }

        @org.junit.Test
        fun buildComplete_outputsBuildFailedOnStderr() {
            uiEventHandler.buildComplete(BUILD_COMPLETE_EVENT)

            if (outputKind == com.google.devtools.build.lib.events.EventKind.STDOUT) {
                output.assertFlushed()
            } else {
                output.assertFlushed(BUILD_DID_NOT_COMPLETE_MESSAGE)
            }
        }

        @org.junit.Test
        fun buildComplete_flushesBufferedMessage() {
            uiEventHandler.handle(output("hello"))
            uiEventHandler.buildComplete(BUILD_COMPLETE_EVENT)

            if (outputKind == com.google.devtools.build.lib.events.EventKind.STDOUT) {
                output.assertFlushed("hello")
            } else {
                output.assertFlushed("hello", java.lang.System.lineSeparator() + BUILD_DID_NOT_COMPLETE_MESSAGE)
            }
        }

        @org.junit.Test
        fun buildComplete_successfulBuild() {
            uiEventHandler.handle(output(""))
            val buildSuccessResult: BuildResult = BuildResult( /* startTimeMillis= */0)
            buildSuccessResult.setDetailedExitCode(DetailedExitCode.success())
            uiEventHandler.buildComplete(BuildCompleteEvent(buildSuccessResult))

            if (outputKind == com.google.devtools.build.lib.events.EventKind.STDOUT) {
                output.assertFlushed()
            } else {
                output.assertFlushed(
                    "\u001b[32mINFO: \u001b[0mBuild completed successfully, 0 total actions"
                            + java.lang.System.lineSeparator()
                )
            }
        }

        @org.junit.Test
        fun buildComplete_emptyBuffer_outputsBuildFailedOnStderr() {
            uiEventHandler.handle(output(""))
            uiEventHandler.buildComplete(BUILD_COMPLETE_EVENT)

            if (outputKind == com.google.devtools.build.lib.events.EventKind.STDOUT) {
                output.assertFlushed()
            } else {
                output.assertFlushed(BUILD_DID_NOT_COMPLETE_MESSAGE)
            }
        }

        @org.junit.Test
        fun handleOutputEvent_buffersWithoutNewline() {
            uiEventHandler.handle(output("hello"))
            output.assertFlushed()
        }

        @org.junit.Test
        fun handleOutputEvent_concatenatesInBuffer() {
            uiEventHandler.handle(output("hello "))
            uiEventHandler.handle(output("there"))
            uiEventHandler.buildComplete(BUILD_COMPLETE_EVENT)

            if (outputKind == com.google.devtools.build.lib.events.EventKind.STDOUT) {
                output.assertFlushed("hello there")
            } else {
                output.assertFlushed(
                    "hello there", java.lang.System.lineSeparator() + BUILD_DID_NOT_COMPLETE_MESSAGE
                )
            }
        }

        @org.junit.Test
        fun handleOutputEvent_flushesOnNewline() {
            uiEventHandler.handle(output("hello\n"))
            output.assertFlushed("hello\n")
        }

        @org.junit.Test
        fun handleOutputEvent_flushesOnlyUntilNewline() {
            uiEventHandler.handle(output("hello\nworld"))
            output.assertFlushed("hello\n")
        }

        @org.junit.Test
        fun handleOutputEvent_flushesUntilLastNewline() {
            uiEventHandler.handle(output("hello\nto\neveryone"))
            output.assertFlushed("hello\nto\n")
        }

        @org.junit.Test
        fun handleOutputEvent_flushesMultiLineMessageAtOnce() {
            uiEventHandler.handle(output("hello\neveryone\n"))
            output.assertFlushed("hello\neveryone\n")
        }

        @org.junit.Test
        fun handleOutputEvent_concatenatesBufferBeforeFlushingOnNewline() {
            uiEventHandler.handle(output("hello"))
            uiEventHandler.handle(output(" there!\nmore text"))

            output.assertFlushed("hello there!\n")
        }

        // This test only exercises progress bar code when testing stderr output, since we don't make
        // any assertions on stderr (where the progress bar is written) when testing stdout.
        @org.junit.Test
        fun noChangeOnUnflushedWrite() {
            uiOptions.setShowProgress(true)
            uiOptions.setUseCursesEnum(UseCurses.YES)
            createUiEventHandler()
            if (outputKind == com.google.devtools.build.lib.events.EventKind.STDERR) {
                Truth.assertThat(output.flushed).hasSize(2)
                output.flushed.clear()
            }
            // Unterminated strings are saved in memory and not pushed out at all.
            Truth.assertThat(output.flushed).isEmpty()
            Truth.assertThat(output.writtenSinceFlush).isEmpty()
        }

        private fun output(message: String?): com.google.devtools.build.lib.events.Event? {
            return com.google.devtools.build.lib.events.Event.of(outputKind, message)
        }
    }

    /** Test cases that only exercise stdout.  */
    @RunWith(JUnit4::class)
    class StdoutOnlyTest : UiEventHandlerTest() {
        @Before
        fun createUiEventHandler() {
            createUiEventHandler(com.google.devtools.build.lib.events.EventKind.STDOUT)
        }

        @org.junit.Test
        fun handleOutputEvent_flushesRemainingLines() {
            uiEventHandler.handle(
                com.google.devtools.build.lib.events.Event.of(
                    com.google.devtools.build.lib.events.EventKind.STDOUT,
                    "hello\nto\neveryone"
                )
            )
            output.assertFlushed("hello\nto\n")
            uiEventHandler.afterCommand(AfterCommandEvent())
            output.assertFlushed("hello\nto\n", "everyone")
        }
    }

    /** Test cases that only exercise stderr.  */
    @RunWith(JUnit4::class)
    class StderrOnlyTest : UiEventHandlerTest() {
        @Before
        fun createUiEventHandler() {
            createUiEventHandler(com.google.devtools.build.lib.events.EventKind.STDERR)
        }

        @org.junit.Test
        fun buildCompleteMessageDoesntOverrideError() {
            uiOptions.setShowProgress(true)
            uiOptions.setUseCursesEnum(UseCurses.YES)
            createUiEventHandler()

            uiEventHandler.buildComplete(BUILD_COMPLETE_EVENT)
            uiEventHandler.handle(com.google.devtools.build.lib.events.Event.error("Show me this!"))
            uiEventHandler.afterCommand(AfterCommandEvent())

            Truth.assertThat(output.flushed).hasSize(5)
            Truth.assertThat(output.flushed.get(3)).contains("Show me this!")
            Truth.assertThat(output.flushed.get(4)).doesNotContain(CLEAR_PROGRESS_BAR)
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun temporarilyDisableProgress() {
            uiOptions.setShowProgress(true)
            uiOptions.setUseCursesEnum(UseCurses.YES)
            uiOptions.setShowProgressRateLimit(1)
            uiOptions.setUiActionsShown(2)
            createUiEventHandler()
            val action1: NullAction = actionWithProgressMessage("Executing action 1", "action1.out")
            val action2: NullAction = actionWithProgressMessage("Executing action 2", "action2.out")
            uiEventHandler.loadingComplete(
                LoadingPhaseCompleteEvent(
                    com.google.common.collect.ImmutableSet.of<E?>(),
                    com.google.common.collect.ImmutableSet.of<E?>(),
                    RepositoryMapping.EMPTY
                )
            )
            uiEventHandler.analysisComplete(< T > mock < T ? > (AnalysisPhaseCompleteEvent::class.java))
            output.flushed.clear()

            // Showing progress, running actions shown.
            clock.advanceMillis(2000)
            uiEventHandler.runningAction(RunningActionEvent(action1, "local"))
            Truth.assertThat(output.flushed).hasSize(1)
            Truth.assertThat(output.flushed.getFirst()).contains("Executing action 1;")

            // Disable progress, progress bar cleared.
            assertThat(uiEventHandler.disableProgress()).isTrue()
            Truth.assertThat(output.flushed).hasSize(2)
            Truth.assertThat(output.flushed.getLast()).endsWith(CLEAR_PROGRESS_BAR)

            // Another action starts running, still no progress updates.
            clock.advanceMillis(2000)
            uiEventHandler.runningAction(RunningActionEvent(action2, "local"))
            Truth.assertThat(output.flushed).hasSize(2)

            // Enable progress again, progress bar written with both running actions.
            uiEventHandler.enableProgress()
            Truth.assertThat(output.flushed).hasSize(3)
            Truth.assertThat(output.flushed.getLast()).contains("2 actions running")
            Truth.assertThat(output.flushed.getLast()).contains("Executing action 1;")
            Truth.assertThat(output.flushed.getLast()).contains("Executing action 2;")
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun progressOff_disableProgressReturnsFalse() {
            uiOptions.setShowProgress(false)
            createUiEventHandler()
            assertThat(uiEventHandler.disableProgress()).isFalse()
        }

        @org.junit.Test
        @Throws(java.lang.Exception::class)
        fun progressAlreadyDisabled_disableProgressReturnsFalse() {
            uiOptions.setShowProgress(true)
            createUiEventHandler()
            assertThat(uiEventHandler.disableProgress()).isTrue()
            assertThat(uiEventHandler.disableProgress()).isFalse()
        }

        companion object {
            private fun actionWithProgressMessage(progressMessage: String, outputPath: String?): NullAction {
                val output: Artifact = ActionsTestUtil.createArtifact(OUTPUT_ROOT, outputPath)
                return object : NullAction(output) {
                    protected val rawProgressMessage: String
                        get() = progressMessage
                }
            }
        }
    }

    private class FlushCollectingOutputStream : java.io.OutputStream() {
        private val flushed: MutableList<String?> = java.util.ArrayList<String?>()
        private var writtenSinceFlush = ""

        @Throws(IOException::class)
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()))
        }

        override fun write(bytes: ByteArray, offset: Int, len: Int) {
            writtenSinceFlush += String(
                java.util.Arrays.copyOfRange(bytes, offset, offset + len),
                java.nio.charset.StandardCharsets.UTF_8
            )
        }

        override fun flush() {
            // Ignore inconsequential extra flushes.
            if (!writtenSinceFlush.isEmpty()) {
                flushed.add(writtenSinceFlush)
            }
            writtenSinceFlush = ""
        }

        fun assertFlushed(vararg messages: String?) {
            Truth.assertThat(writtenSinceFlush).isEmpty()
            Truth.assertThat(flushed).containsExactlyElementsIn(messages)
        }
    }

    companion object {
        private val BUILD_COMPLETE_EVENT: BuildCompleteEvent = BuildCompleteEvent(BuildResult( /* startTimeMillis= */0))
        private val BUILD_DID_NOT_COMPLETE_MESSAGE =
            "\u001b[31m\u001b[1mERROR: \u001b[0mBuild did NOT complete successfully" + java.lang.System.lineSeparator()

        /** The escape sequence that clears the progress bar when curses is enabled.  */
        private const val CLEAR_PROGRESS_BAR = "\u001b[1A\u001b[K"

        private val OUTPUT_ROOT: ArtifactRoot? = ArtifactRoot.asDerivedRoot(
            InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/base/exec"),
            RootType.OUTPUT,
            "out"
        )
    }
}
