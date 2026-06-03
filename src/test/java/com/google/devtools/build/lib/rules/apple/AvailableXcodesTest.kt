// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.apple

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild
import net.starlark.java.eval.Sequence
import org.junit.Test

/** Unit tests for the `available_xcodes` rule.  */
@RunWith(JUnit4::class)
class AvailableXcodesTest : BuildViewTestCase() {
    @Test
    @Throws(Exception::class)
    fun testXcodeVersionCanBeReadFromNative() {
        if (TestConstants.PRODUCT_NAME == "bazel") {
            return  // TODO(bazel-team@): Key for AvailableXcodesInfo is wrong
        }
        scratch.file(
            "examples/apple/BUILD",
            """
        load("@build_bazel_apple_support//xcode:available_xcodes.bzl", "available_xcodes")
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")

        package(default_visibility = ["//visibility:public"])

        available_xcodes(
            name = "my_xcodes",
            default = ":xcode_8",
            versions = [
                ":xcode_8",
                ":xcode_9",
            ],
        )

        xcode_version(
            name = "xcode_8",
            default_ios_sdk_version = "9.0",
            default_macos_sdk_version = "9.3",
            default_tvos_sdk_version = "9.2",
            default_watchos_sdk_version = "9.1",
            version = "8",
        )

        xcode_version(
            name = "xcode_9",
            default_ios_sdk_version = "10.0",
            default_macos_sdk_version = "10.3",
            default_tvos_sdk_version = "10.2",
            default_watchos_sdk_version = "10.1",
            version = "9",
        )
        
        """.trimIndent()
        )

        val nativeTarget: ConfiguredTarget = getConfiguredTarget("//examples/apple:my_xcodes")
        val availableXcodesInfo: StructImpl = nativeTarget.get(AVAILABLE_XCODES_PROVIDER_KEY) as StructImpl
        val version8: ConfiguredTarget = getConfiguredTarget("//examples/apple:xcode_8")
        val version8properties: StructImpl? =
            version8.get(XCODE_VERSION_PROPERTIES_PROVIDER_KEY) as StructImpl?
        val version9: ConfiguredTarget = getConfiguredTarget("//examples/apple:xcode_9")
        val version9properties: StructImpl? =
            version9.get(XCODE_VERSION_PROPERTIES_PROVIDER_KEY) as StructImpl?
        val availableVersions: Sequence<StructImpl?> =
            Sequence.cast<T?>(
                availableXcodesInfo.getValue("available_versions"),
                StructImpl::class.java,
                "available_versions"
            )
        Truth.assertThat(availableVersions).hasSize(2)
        assertThat(availableVersions.get(0).getValue("xcode_version_properties"))
            .isEqualTo(version8properties)
        assertThat(availableVersions.get(1).getValue("xcode_version_properties"))
            .isEqualTo(version9properties)
        val defaultVersion: StructImpl = availableXcodesInfo.getValue("default_version", StructImpl::class.java)
        assertThat(defaultVersion.getValue("xcode_version_properties")).isEqualTo(version8properties)
    }

    @Test
    @Throws(Exception::class)
    fun testXcodeVersionRequiresDefault() {
        scratch.file(
            "examples/apple/BUILD",
            """
        load("@build_bazel_apple_support//xcode:available_xcodes.bzl", "available_xcodes")
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")

        package(default_visibility = ["//visibility:public"])

        available_xcodes(
            name = "my_xcodes",
            versions = [":my_xcode"],
        )

        xcode_version(
            name = "my_xcode",
            default_ios_sdk_version = "9.0",
            default_macos_sdk_version = "9.3",
            default_tvos_sdk_version = "9.2",
            default_watchos_sdk_version = "9.1",
            version = "8",
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//examples/apple:my_xcodes")
        assertContainsEvent(
            "missing value for mandatory attribute 'default' in 'available_xcodes' rule"
        )
    }

    companion object {
        private val AVAILABLE_XCODES_PROVIDER_KEY: Provider.Key = Key(
            keyForBuild(Label.parseCanonicalUnchecked("@build_bazel_apple_support//xcode:available_xcodes.bzl")),
            "AvailableXcodesInfo"
        )

        private val XCODE_VERSION_PROPERTIES_PROVIDER_KEY: Provider.Key = Key(
            keyForBuild(Label.parseCanonicalUnchecked("@build_bazel_apple_support//xcode:xcode_version.bzl")),
            "XcodeVersionPropertiesInfo"
        )
    }
}
