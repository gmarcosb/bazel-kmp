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
package com.google.devtools.build.lib.runtime.commands

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.BlazeVersionInfo
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests [VersionCommand.getInfo].  */
@RunWith(JUnit4::class)
class VersionCommandTest {
    @org.junit.Test
    fun testNoSummary() {
        assertThat(
            VersionCommand.getInfo(
                "product",
                BlazeVersionInfo(com.google.common.collect.ImmutableMap.of<String?, String?>()),
                LEGACY_FORMAT
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    fun testNoSummaryGnuFormat() {
        assertThat(
            VersionCommand.getInfo(
                "product",
                BlazeVersionInfo(com.google.common.collect.ImmutableMap.of<String?, String?>()),
                GNU_FORMAT
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    fun testNoVersionGnuFormat() {
        val map: MutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>(BlazeVersionInfo.BUILD_LABEL, "")
        val info: java.util.Optional<String?>? =
            VersionCommand.getInfo("product", BlazeVersionInfo(map), GNU_FORMAT)
        Truth.assertThat(info).hasValue("product no_version")
    }

    @org.junit.Test
    fun testVersionGnuFormat() {
        val map: MutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>(BlazeVersionInfo.BUILD_LABEL, "1.2")
        val info: java.util.Optional<String?>? =
            VersionCommand.getInfo("product", BlazeVersionInfo(map), GNU_FORMAT)
        Truth.assertThat(info).hasValue("product 1.2")
    }

    @org.junit.Test
    fun testLegacyFormat() {
        val map: MutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                BlazeVersionInfo.BUILD_LABEL,
                "version",
                "More",
                "foo"
            )
        val info: java.util.Optional<String?>? =
            VersionCommand.getInfo("product", BlazeVersionInfo(map), LEGACY_FORMAT)
        Truth.assertThat(info).hasValue("Build label: version\nMore: foo")
    }

    companion object {
        private const val GNU_FORMAT = true
        private const val LEGACY_FORMAT = false
    }
}
