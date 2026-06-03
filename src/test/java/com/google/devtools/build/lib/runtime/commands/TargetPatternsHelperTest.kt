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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.analysis.ServerDirectories

/** Tests [TargetPatternsHelper].  */
@RunWith(JUnit4::class)
class TargetPatternsHelperTest {
    private var env: CommandEnvironment? = null
    private var scratch: Scratch? = null
    private var options: OptionsParser? = null
    private var mockEventBus: MockEventBus? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        options = OptionsParser.builder().optionsClasses(BuildRequestOptions::class.java).build()
        scratch = Scratch()
        val runtime: BlazeRuntime? =
            Builder()
                .setFileSystem(scratch.getFileSystem())
                .setProductName(TestConstants.PRODUCT_NAME)
                .setServerDirectories(
                    ServerDirectories(
                        scratch.resolve("/install"),
                        scratch.resolve("/base"),
                        scratch.resolve("/userRoot")
                    )
                )
                .setStartupOptionsProvider(
                    OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
                )
                .build()
        mockEventBus = MockEventBus()
        env = Mockito.mock<CommandEnvironment>(CommandEnvironment::class.java)
        Mockito.`when`<T?>(env.getWorkingDirectory()).thenReturn(scratch.resolve("wd"))
        Mockito.`when`<T?>(env.getRuntime()).thenReturn(runtime)
        Mockito.`when`<T?>(env.getEventBus()).thenReturn(mockEventBus)
    }

    @org.junit.Test
    @Throws(TargetPatternsHelperException::class)
    fun testEmpty() {
        // tests when no residue and no --target_pattern_file are set
        assertThat(TargetPatternsHelper.readFrom(env, options)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetPatternFile() {
        val targetPatternFilePath: Path = scratch.file("/wd/patterns.txt", "//some/...\n//patterns")
        options.parse("--target_pattern_file=patterns.txt")

        assertThat(TargetPatternsHelper.readFrom(env, options))
            .isEqualTo(com.google.common.collect.ImmutableList.of<String?>("//some/...", "//patterns"))
        Truth.assertThat(mockEventBus!!.inputFileEvents)
            .containsExactly(
                InputFileEvent.create( /* type= */
                    "target_pattern_file", targetPatternFilePath.getFileSize()
                )
            )
    }

    @org.junit.Test
    @Throws(TargetPatternsHelperException::class)
    fun testNoTargetPatternFile() {
        val patterns: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("//some/...", "//patterns")
        options.setResidue(patterns, com.google.common.collect.ImmutableList.of<String?>())

        assertThat(TargetPatternsHelper.readFrom(env, options)).isEqualTo(patterns)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testSpecifyPatternAndFileThrows() {
        options.parse("--target_pattern_file=patterns.txt")
        options.setResidue(
            com.google.common.collect.ImmutableList.of<String?>("//some:pattern"),
            com.google.common.collect.ImmutableList.of<String?>()
        )

        val expected: TargetPatternsHelperException =
            org.junit.Assert.assertThrows<T>(
                TargetPatternsHelperException::class.java,
                org.junit.function.ThrowingRunnable { TargetPatternsHelper.readFrom(env, options) })

        val message =
            "Command-line target pattern and --target_pattern_file cannot both be specified"
        assertThat(expected).hasMessageThat().isEqualTo(message)
        assertThat(expected.getFailureDetail())
            .isEqualTo(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setTargetPatterns(
                        TargetPatterns.newBuilder()
                            .setCode(Code.TARGET_PATTERN_FILE_WITH_COMMAND_LINE_PATTERN)
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testSpecifyNonExistingFileThrows() {
        options.parse("--target_pattern_file=patterns.txt")

        val expected: TargetPatternsHelperException =
            org.junit.Assert.assertThrows<T>(
                TargetPatternsHelperException::class.java,
                org.junit.function.ThrowingRunnable { TargetPatternsHelper.readFrom(env, options) })

        val regex = "I/O error reading from .*patterns.txt.*\\(No such file or directory\\)"
        assertThat(expected).hasMessageThat().matches(regex)
        assertThat(expected.getFailureDetail().getMessage()).matches(regex)
        assertThat(expected.getFailureDetail().hasTargetPatterns()).isTrue()
        assertThat(expected.getFailureDetail().getTargetPatterns().getCode())
            .isEqualTo(Code.TARGET_PATTERN_FILE_READ_FAILURE)
    }

    private class MockEventBus : com.google.common.eventbus.EventBus() {
        val inputFileEvents: MutableSet<InputFileEvent?> =
            com.google.common.collect.Sets.newConcurrentHashSet<InputFileEvent?>()

        override fun post(event: Any) {
            inputFileEvents.add(event as InputFileEvent)
        }
    }
}
