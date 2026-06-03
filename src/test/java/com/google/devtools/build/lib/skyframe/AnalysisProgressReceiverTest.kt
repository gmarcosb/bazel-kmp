// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.common.truth.Truth
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests [AnalysisProgressReceiver].  */
@RunWith(JUnit4::class)
class AnalysisProgressReceiverTest {
    @org.junit.Test
    fun testTargetCounted() {
        // If the configuration of a target is completed it is counted as fully configured target.
        val progress: AnalysisProgressReceiver = AnalysisProgressReceiver()
        progress.doneConfigureTarget()
        val progressString1: String = progress.getProgressString()

        Truth.assertWithMessage("One configured target should be visible in progress.")
            .that(progressString1.contains("1 target configured"))
            .isTrue()

        progress.doneConfigureTarget()
        val progressString2: String = progress.getProgressString()

        Truth.assertWithMessage("Two configured targets should be visible in progress.")
            .that(progressString2.contains("2 targets configured"))
            .isTrue()
    }

    @org.junit.Test
    fun testDownloadedTargetCounted() {
        val progress: AnalysisProgressReceiver = AnalysisProgressReceiver()
        progress.doneDownloadedConfiguredTarget()
        val progressString1: String? = progress.getProgressString()

        Truth.assertThat(progressString1).contains("1 target configured (1 remote cache hits)")

        progress.doneConfigureTarget()
        val progressString2: String? = progress.getProgressString()

        Truth.assertThat(progressString2).contains("2 targets configured (1 remote cache hits)")
    }

    @org.junit.Test
    fun testAspectCounted() {
        val progress: AnalysisProgressReceiver = AnalysisProgressReceiver()
        progress.doneConfigureAspect()
        val progressString1: String = progress.getProgressString()

        Truth.assertWithMessage("One configured aspect should be visible in progress.")
            .that(progressString1.contains("0 targets configured, 1 aspect application"))
            .isTrue()

        progress.doneConfigureAspect()
        val progressString2: String = progress.getProgressString()

        Truth.assertWithMessage("Two configured aspects should be visible in progress.")
            .that(progressString2.contains("0 targets configured, 2 aspect applications"))
            .isTrue()
    }

    @org.junit.Test
    fun testDownloadedAspectCounted() {
        val progress: AnalysisProgressReceiver = AnalysisProgressReceiver()
        progress.doneDownloadedConfiguredAspect()
        val progressString1: String? = progress.getProgressString()

        Truth.assertThat(progressString1)
            .contains("0 targets configured, 1 aspect application (1 remote cache hits)")

        progress.doneConfigureAspect()
        val progressString2: String? = progress.getProgressString()

        Truth.assertThat(progressString2)
            .contains("0 targets configured, 2 aspect applications (1 remote cache hits)")
    }

    @org.junit.Test
    fun testTargetAndAspectCounted() {
        val progress: AnalysisProgressReceiver = AnalysisProgressReceiver()
        val progressString1: String? = progress.getProgressString()
        Truth.assertThat(progressString1).contains("0 targets configured")

        progress.doneConfigureTarget()
        val progressString2: String? = progress.getProgressString()

        Truth.assertThat(progressString2).contains("1 target configured")

        progress.doneConfigureAspect()
        val progressString3: String? = progress.getProgressString()

        Truth.assertThat(progressString3).contains("1 target configured, 1 aspect application")
    }

    @org.junit.Test
    fun testReset() {
        // After resetting, messages should be as immediately after creation.
        val progress: AnalysisProgressReceiver = AnalysisProgressReceiver()
        val defaultProgress: String? = progress.getProgressString()
        progress.doneConfigureTarget()
        assertThat(progress.getProgressString()).isNotEqualTo(defaultProgress)
        progress.reset()
        assertThat(progress.getProgressString()).isEqualTo(defaultProgress)
    }

    @org.junit.Test
    fun testLargeTargetCountFormattedWithCommas() {
        // Verify that large target counts are formatted with comma separators for readability.
        val progress: AnalysisProgressReceiver = AnalysisProgressReceiver()

        for (i in 0..12344) {
            progress.doneConfigureTarget()
        }

        val progressString: String? = progress.getProgressString()
        Truth.assertThat(progressString).contains("12,345 targets configured")
    }

    @org.junit.Test
    fun testLargeDownloadedTargetCountFormattedWithCommas() {
        // Verify that large downloaded target counts (>= 10,000) are formatted with comma separators.
        val progress: AnalysisProgressReceiver = AnalysisProgressReceiver()

        for (i in 0..15677) {
            progress.doneDownloadedConfiguredTarget()
        }

        val progressString: String? = progress.getProgressString()
        Truth.assertThat(progressString).contains("15,678 targets configured")
        Truth.assertThat(progressString).contains("(15,678 remote cache hits)")
    }

    @org.junit.Test
    fun testLargeAspectCountFormattedWithCommas() {
        // Verify that large aspect counts (>= 10,000) are formatted with comma separators.
        val progress: AnalysisProgressReceiver = AnalysisProgressReceiver()

        for (i in 0..12499) {
            progress.doneConfigureAspect()
        }

        val progressString: String? = progress.getProgressString()
        Truth.assertThat(progressString).contains("12,500 aspect applications")
    }

    @org.junit.Test
    fun testLargeDownloadedAspectCountFormattedWithCommas() {
        // Verify that large downloaded aspect counts (>= 10,000) are formatted with comma separators.
        val progress: AnalysisProgressReceiver = AnalysisProgressReceiver()

        for (i in 0..11233) {
            progress.doneDownloadedConfiguredAspect()
        }

        val progressString: String? = progress.getProgressString()
        Truth.assertThat(progressString).contains("11,234 aspect applications")
        Truth.assertThat(progressString).contains("(11,234 remote cache hits)")
    }

    @org.junit.Test
    fun testSmallCountsNotFormattedWithCommas() {
        // Verify that counts below 10,000 (IEEE style threshold) are NOT formatted with commas.
        val progress: AnalysisProgressReceiver = AnalysisProgressReceiver()

        for (i in 0..5677) {
            progress.doneConfigureTarget()
        }

        val progressString: String? = progress.getProgressString()
        Truth.assertThat(progressString).contains("5678 targets configured")
        Truth.assertThat(progressString).doesNotContain("5,678")
    }
}
