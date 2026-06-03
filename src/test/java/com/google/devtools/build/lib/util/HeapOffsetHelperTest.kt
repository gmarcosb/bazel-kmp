// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.runtime.BlazeRuntime

/** Tests HeapOffsetHelper and verify we are properly pulling heap data.  */
@RunWith(JUnit4::class)
class HeapOffsetHelperTest : BuildIntegrationTestCase() {
    private val memoryPressureModule: MemoryPressureModule = MemoryPressureModule()

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.runtimeBuilder.addBlazeModule(memoryPressureModule)

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

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadPattern() {
        val bugReporter: RecordingBugReporter = recordBugReportsAndReinitialize()

        // short-circuit this test when our version isn't JDK 21 or in Bazel
        // environments where JDK 21 doesn't have this.
        if (!HeapOffsetHelper.isWorkaroundNeeded() || AnalysisMock.get().isThisBazel()) {
            return
        }

        buildTarget("//foo:foo")

        val badPattern: java.util.regex.Pattern = java.util.regex.Pattern.compile("horse")

        val offset: Long = HeapOffsetHelper.getSizeOfFillerArrayOnHeap(badPattern, bugReporter)
        Truth.assertThat(offset).isEqualTo(0)

        Truth.assertThat(bugReporter.getExceptions()).hasSize(1)
        val reported: Throwable? =
            com.google.common.collect.Iterables.getOnlyElement<Throwable?>(bugReporter.getExceptions())

        Truth.assertThat(reported).isInstanceOf(java.lang.IllegalStateException::class.java)
        Truth.assertThat(reported).hasMessageThat().contains("JDK 21")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun matchesOpenJdk21Filler() {
        // short-circuit this test when our version isn't JDK 21 or in Bazel
        // environments where JDK 21 doesn't have this.
        if (!HeapOffsetHelper.isWorkaroundNeeded() || AnalysisMock.get().isThisBazel()) {
            return
        }

        // NOTE: If this test fails, it means that the JDK has changed and we need to update the
        // pattern in MemoryPressureOptions.  The flag can also be set in rc files to override the
        // default before a release.
        val bugReporter: RecordingBugReporter = recordBugReportsAndReinitialize()
        buildTarget("//foo:foo")

        val defaultOptionPattern: java.util.regex.Pattern? =
            runtimeWrapper
                .commandEnvironment
                .getOptions()
                .getOptions(MemoryPressureOptions::class.java)
                .getJvmHeapHistogramInternalObjectPattern()
                .regexPattern()

        val offset: Long = HeapOffsetHelper.getSizeOfFillerArrayOnHeap(defaultOptionPattern, bugReporter)
        bugReporter.assertNoExceptions()

        Truth.assertThat(offset).isGreaterThan(0)
    }
}
