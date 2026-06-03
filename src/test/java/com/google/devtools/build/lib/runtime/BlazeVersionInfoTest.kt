// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.BlazeVersionInfo
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.Collections

/**
 * Tests [BlazeVersionInfo].
 */
@RunWith(JUnit4::class)
class BlazeVersionInfoTest {
    @org.junit.Test
    fun testEmptyVersionInfoMeansNotAvailable() {
        val info: BlazeVersionInfo = BlazeVersionInfo(mutableMapOf<String?, String?>())
        Truth.assertThat(info.isAvailable()).isFalse()
        Truth.assertThat(info.getSummary()).isNull()
        Truth.assertThat(info.getReleaseName()).isEqualTo("development version")
    }

    @org.junit.Test
    fun testReleaseNameIsDevelopmentIfBuildLabelIsNull() {
        val data: MutableMap<String?, String?> = Collections.singletonMap<String?, String?>("Build label", "")
        val info: BlazeVersionInfo = BlazeVersionInfo(data)
        Truth.assertThat(info.getReleaseName()).isEqualTo("development version")
    }

    @org.junit.Test
    fun testReleaseNameIfBuildLabelIsPresent() {
        val data: MutableMap<String?, String?> =
            Collections.singletonMap<String?, String?>("Build label", "3/4/2009 (gold)")
        val info: BlazeVersionInfo = BlazeVersionInfo(data)
        Truth.assertThat(info.getReleaseName()).isEqualTo("release 3/4/2009 (gold)")
    }

    @org.junit.Test
    fun testGetSummaryReturnsOrderedTablifiedData() {
        val data: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("key3", "foo", "key2", "bar", "key1", "baz")

        val info: BlazeVersionInfo = BlazeVersionInfo(data)
        Truth.assertThat(info.getSummary()).isEqualTo("key1: baz\nkey2: bar\nkey3: foo")
    }

    @org.junit.Test
    fun testVersionIsHeadIfBuildLabelIsNull() {
        val info: BlazeVersionInfo = BlazeVersionInfo(com.google.common.collect.ImmutableMap.of<String?, String?>())
        Truth.assertThat(info.getVersion()).isEmpty()
    }

    @org.junit.Test
    fun testVersionsIIfBuildLabelIsPresent() {
        val data: MutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("Build label", "123.4")
        val info: BlazeVersionInfo = BlazeVersionInfo(data)
        Truth.assertThat(info.getVersion()).isEqualTo("123.4")
    }
}
