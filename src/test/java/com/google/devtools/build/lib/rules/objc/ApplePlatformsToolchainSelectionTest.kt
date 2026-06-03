// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.objc

import com.google.devtools.build.lib.analysis.ConfiguredTarget
import org.junit.Test

/** Test case for the use of Apple platforms and the macOS crosstool.  */
@RunWith(JUnit4::class)
class ApplePlatformsToolchainSelectionTest : ObjcRuleTestCase() {
    override fun platformBasedToolchains(): Boolean {
        return true
    }

    @Test
    @Throws(Exception::class)
    fun testMacOsToolchainSetup() {
        // Verify the macOS toolchain and its associated cpp toolchain.
        val darwinToolchain: ConfiguredTarget =
            getConfiguredTarget("//tools/build_defs/apple/toolchains:darwin_x86_64_any")
        assertThat(darwinToolchain).isNotNull()
        val darwinToolchainInfo: DeclaredToolchainInfo =
            PlatformProviderUtils.declaredToolchainInfo(darwinToolchain)
        assertThat(darwinToolchainInfo).isNotNull()
        assertThat(darwinToolchainInfo.resolvedToolchainLabel())
            .isEqualTo(
                Label.parseCanonicalUnchecked(
                    "//" + MockObjcSupport.DEFAULT_OSX_CROSSTOOL_DIR + ":cc-compiler-darwin_x86_64"
                )
            )
        assertThat(darwinToolchainInfo.toolchainType()).isEqualTo(CPP_TOOLCHAIN_TYPE)

        // Verify the macOS platform.
        val darwinPlatform: ConfiguredTarget =
            getConfiguredTarget(TestConstants.APPLE_PLATFORM_PACKAGE_ROOT + ":darwin_x86_64")
        val darwinPlatformInfo: PlatformInfo? = PlatformProviderUtils.platform(darwinPlatform)
        assertThat(darwinPlatformInfo).isNotNull()
    }

    @Test
    @Throws(Exception::class)
    fun testIosDeviceToolchainSetup() {
        // Verify the iOS 64 bit device toolchain and its associated cpp toolchain.
        val iosDeviceToolchain: ConfiguredTarget =
            getConfiguredTarget("//tools/build_defs/apple/toolchains:ios_arm64_any")
        assertThat(iosDeviceToolchain).isNotNull()
        val iosDeviceToolchainInfo: DeclaredToolchainInfo =
            PlatformProviderUtils.declaredToolchainInfo(iosDeviceToolchain)
        assertThat(iosDeviceToolchainInfo).isNotNull()
        assertThat(iosDeviceToolchainInfo.resolvedToolchainLabel())
            .isEqualTo(
                Label.parseCanonicalUnchecked(
                    "//" + MockObjcSupport.DEFAULT_OSX_CROSSTOOL_DIR + ":cc-compiler-ios_arm64"
                )
            )
        assertThat(iosDeviceToolchainInfo.toolchainType()).isEqualTo(CPP_TOOLCHAIN_TYPE)

        // Verify the iOS 64 bit device platform.
        val iosDevicePlatform: ConfiguredTarget =
            getConfiguredTarget(TestConstants.APPLE_PLATFORM_PACKAGE_ROOT + ":ios_arm64")
        val iosDevicePlatformInfo: PlatformInfo? = PlatformProviderUtils.platform(iosDevicePlatform)
        assertThat(iosDevicePlatformInfo).isNotNull()
    }

    companion object {
        private val CPP_TOOLCHAIN_TYPE: ToolchainTypeInfo? = ToolchainTypeInfo.create(
            Label.parseCanonicalUnchecked(
                TestConstants.TOOLS_REPOSITORY.toString() + "//tools/cpp:toolchain_type"
            )
        )
    }
}
