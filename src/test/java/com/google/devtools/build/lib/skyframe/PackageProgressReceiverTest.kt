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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * Tests [PackageProgressReceiver].
 */
@RunWith(JUnit4::class)
class PackageProgressReceiverTest {
    @org.junit.Test
    fun testPackageVisible() {
        // If there is only a single package being loaded, it is visible in
        // the activity part of the progress state.
        val id: PackageIdentifier = PackageIdentifier.createInMainRepo("foo/bar/baz")
        val progress: PackageProgressReceiver = PackageProgressReceiver()
        progress.startReadPackage(id)
        val activity: String = progress.progressState().getSecond()

        Truth.assertWithMessage("Unfinished package '%s' should be visible in activity: %s", id, activity)
            .that(activity.contains(id.toString()))
            .isTrue()
    }

    @org.junit.Test
    fun testPackageCounted() {
        // If the loading of a package is completed, it is no longer visible as activity,
        // but counted as one package fully loaded.
        val id: PackageIdentifier = PackageIdentifier.createInMainRepo("foo/bar/baz")
        val progress: PackageProgressReceiver = PackageProgressReceiver()
        progress.startReadPackage(id)
        progress.doneReadPackage(id)
        val state: String = progress.progressState().getFirst()
        val activity: String = progress.progressState().getSecond()

        Truth.assertWithMessage("Finished package '%s' should not be visible in activity: %s", id, activity)
            .that(activity.contains(id.toString()))
            .isFalse()
        Truth.assertWithMessage("Number of completed packages should be visible in state")
            .that(state.contains("1 package"))
            .isTrue()
    }

    @org.junit.Test
    fun testReset() {
        // After resetting, messages should be as immediately after creation.
        val progress: PackageProgressReceiver = PackageProgressReceiver()
        val defaultState: String? = progress.progressState().getFirst()
        val defaultActivity: String? = progress.progressState().getSecond()
        val id: PackageIdentifier? = PackageIdentifier.createInMainRepo("foo/bar/baz")
        progress.startReadPackage(id)
        progress.doneReadPackage(id)
        progress.reset()
        assertThat(progress.progressState().getFirst()).isEqualTo(defaultState)
        assertThat(progress.progressState().getSecond()).isEqualTo(defaultActivity)
    }

    @org.junit.Test
    fun testLargeNumbersFormattedWithCommas() {
        // Verify that large package counts (>= 10,000) are formatted with comma separators.
        val progress: PackageProgressReceiver = PackageProgressReceiver()

        for (i in 0..11233) {
            val id: PackageIdentifier? = PackageIdentifier.createInMainRepo("pkg" + i)
            progress.startReadPackage(id)
            progress.doneReadPackage(id)
        }

        val state: String? = progress.progressState().getFirst()
        Truth.assertThat(state).contains("11,234 packages loaded")
    }

    @org.junit.Test
    fun testLargePendingSetFormattedWithCommas() {
        // Verify that large pending package counts (>= 10,000) are formatted with comma separators.
        val progress: PackageProgressReceiver = PackageProgressReceiver()

        for (i in 0..11499) {
            val id: PackageIdentifier? = PackageIdentifier.createInMainRepo("pending/pkg" + i)
            progress.startReadPackage(id)
        }

        val activity: String? = progress.progressState().getSecond()
        Truth.assertThat(activity).contains("(11,500 packages)")
    }

    @org.junit.Test
    fun testSmallNumbersNotFormattedWithCommas() {
        // Verify that counts below 10,000 (IEEE style threshold) are NOT formatted with commas.
        val progress: PackageProgressReceiver = PackageProgressReceiver()

        for (i in 0..1233) {
            val id: PackageIdentifier? = PackageIdentifier.createInMainRepo("pkg" + i)
            progress.startReadPackage(id)
            progress.doneReadPackage(id)
        }

        val state: String? = progress.progressState().getFirst()
        Truth.assertThat(state).contains("1234 packages loaded")
        Truth.assertThat(state).doesNotContain("1,234")
    }
}
