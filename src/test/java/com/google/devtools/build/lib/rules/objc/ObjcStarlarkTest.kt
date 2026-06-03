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
package com.google.devtools.build.lib.rules.objc

import com.google.common.collect.ImmutableList
import com.google.common.collect.ObjectArrays
import com.google.common.truth.Subject
import com.google.devtools.build.lib.rules.apple.DottedVersion
import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable

/** Tests for Starlark interaction with the objc_* rules.  */
@RunWith(JUnit4::class)
class ObjcStarlarkTest : ObjcRuleTestCase() {
    @Before
    @Throws(Exception::class)
    fun setupMyInfo() {
        scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()")

        scratch.file("myinfo/BUILD")
    }

    @Throws(Exception::class)
    private fun getMyInfoFromTarget(configuredTarget: ConfiguredTarget): StructImpl {
        val key: Provider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//myinfo:myinfo.bzl")), "MyInfo"
            )
        return configuredTarget.get(key) as StructImpl
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkRuleCanDependOnNativeAppleRule() {
        scratch.file("test_starlark/rule/BUILD")
        scratch.file(
            "test_starlark/rule/apple_rules.bzl",
            """
        load('@rules_cc//cc/common:cc_info.bzl', 'CcInfo')
        load("//myinfo:myinfo.bzl", "MyInfo")

        def my_rule_impl(ctx):
            dep = ctx.attr.deps[0]
            library_to_link = dep[CcInfo].linking_context.linker_inputs.to_list()[0].libraries[0]
            return MyInfo(
                found_hdrs = dep[CcInfo].compilation_context.headers.to_list(),
                found_libs = [library_to_link.static_library],
            )

        my_rule = rule(
            implementation = my_rule_impl,
            attrs = {
                "deps": attr.label_list(
                    allow_files = False,
                    mandatory = False,
                    providers = [[CcInfo]],
                ),
            },
        )
        
        """.trimIndent()
        )
        scratch.file("test_starlark/apple_starlark/a.m")
        scratch.file(
            "test_starlark/apple_starlark/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("//test_starlark/rule:apple_rules.bzl", "my_rule")

        package(default_visibility = ["//visibility:public"])

        my_rule(
            name = "my_target",
            deps = [":lib"],
        )

        objc_library(
            name = "lib",
            srcs = ["a.m"],
            hdrs = ["b.h"],
        )
        
        """.trimIndent()
        )

        val starlarkTarget: ConfiguredTarget =
            getConfiguredTarget("//test_starlark/apple_starlark:my_target")
        val myInfo: StructImpl = getMyInfoFromTarget(starlarkTarget)
        val starlarkHdrs: MutableList<Artifact?>? = myInfo.getValue("found_hdrs") as MutableList<Artifact?>?
        val starlarkLibraries: MutableList<Artifact?>? = myInfo.getValue("found_libs") as MutableList<Artifact?>?

        Subject.contains("b.h")
        Subject.contains("liblib.a")
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkProviderRetrievalNoneIfNoProvider() {
        scratch.file("test_starlark/rule/BUILD")
        scratch.file(
            "test_starlark/rule/apple_rules.bzl",
            """
        load("@rules_cc//cc/common:objc_info.bzl", "ObjcInfo")
        def my_rule_impl(ctx):
            dep = ctx.attr.deps[0]
            objc_provider = dep[ObjcInfo]  # this is line 4
            return []

        my_rule = rule(
            implementation = my_rule_impl,
            attrs = {
                "deps": attr.label_list(allow_files = False, mandatory = False),
            },
        )
        
        """.trimIndent()
        )
        scratch.file("test_starlark/apple_starlark/a.cc")
        scratch.file(
            "test_starlark/apple_starlark/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("//test_starlark/rule:apple_rules.bzl", "my_rule")
        package(default_visibility = ["//visibility:public"])

        my_rule(
            name = "my_target",
            deps = [":lib"],
        )

        cc_library(
            name = "lib",
            srcs = ["a.cc"],
            hdrs = ["b.h"],
        )
        
        """.trimIndent()
        )
        val e =
            Assert.assertThrows<AssertionError?>(
                AssertionError::class.java,
                ThrowingRunnable { getConfiguredTarget("//test_starlark/apple_starlark:my_target") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "apple_starlark/BUILD:5:8: in my_rule rule //test_starlark/apple_starlark:my_target:"
            )
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "File \"/workspace/test_starlark/rule/apple_rules.bzl\", line 4, column 24, in"
                        + " my_rule_impl"
            )
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "<target //test_starlark/apple_starlark:lib> (rule 'cc_library') "
                        + "doesn't contain declared provider 'ObjcInfo'"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkProviderCanCheckForExistenceOfObjcProvider() {
        scratch.file("test_starlark/rule/BUILD")
        scratch.file(
            "test_starlark/rule/apple_rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("@rules_cc//cc/common:objc_info.bzl", "ObjcInfo")

        def my_rule_impl(ctx):
            cc_has_provider = ObjcInfo in ctx.attr.deps[0]
            objc_has_provider = ObjcInfo in ctx.attr.deps[1]
            return MyInfo(cc_has_provider = cc_has_provider, objc_has_provider = objc_has_provider)

        my_rule = rule(
            implementation = my_rule_impl,
            attrs = {
                "deps": attr.label_list(allow_files = False, mandatory = False),
            },
        )
        
        """.trimIndent()
        )
        scratch.file("test_starlark/apple_starlark/a.cc")
        scratch.file("test_starlark/apple_starlark/a.m")
        scratch.file(
            "test_starlark/apple_starlark/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("//test_starlark/rule:apple_rules.bzl", "my_rule")

        package(default_visibility = ["//visibility:public"])

        my_rule(
            name = "my_target",
            deps = [
                ":cc_lib",
                ":objc_lib",
            ],
        )

        objc_library(
            name = "objc_lib",
            srcs = ["a.m"],
        )

        cc_library(
            name = "cc_lib",
            srcs = ["a.cc"],
        )
        
        """.trimIndent()
        )
        val starlarkTarget: ConfiguredTarget =
            getConfiguredTarget("//test_starlark/apple_starlark:my_target")
        val myInfo: StructImpl = getMyInfoFromTarget(starlarkTarget)
        val ccResult = myInfo.getValue("cc_has_provider") as Boolean
        val objcResult = myInfo.getValue("objc_has_provider") as Boolean
        Truth.assertThat(ccResult).isFalse()
        Truth.assertThat(objcResult).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkExportsObjcProviderToNativeRule() {
        scratch.file("test_starlark/rule/BUILD")
        scratch.file(
            "test_starlark/rule/apple_rules.bzl",
            """
        load("@rules_cc//cc/common:cc_info.bzl", "CcInfo")
        load("@rules_cc//cc/common:objc_info.bzl", "ObjcInfo")

        def my_rule_impl(ctx):
            dep = ctx.attr.deps[0]
            return [dep[ObjcInfo], dep[CcInfo]]

        swift_library = rule(
            implementation = my_rule_impl,
            attrs = {
                "deps": attr.label_list(
                    allow_files = False,
                    mandatory = False,
                    providers = [[ObjcInfo, CcInfo]],
                ),
            },
        )
        
        """.trimIndent()
        )

        scratch.file("test_starlark/apple_starlark/a.m")
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)
        scratch.file(
            "test_starlark/apple_starlark/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("//test_starlark/rule:apple_rules.bzl", "swift_library")
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        package(default_visibility = ["//visibility:public"])

        objc_library(
            name = "lib",
            srcs = ["a.m"],
        )

        swift_library(
            name = "my_target",
            deps = [":lib"],
        )

        apple_binary_starlark(
            name = "bin",
            platform_type = "ios",
            deps = [":my_target"],
        )
        
        """.trimIndent()
        )

        val binaryTarget: ConfiguredTarget = getConfiguredTarget("//test_starlark/apple_starlark:bin")
        val executableProvider: StructImpl =
            binaryTarget.get(APPLE_EXECUTABLE_BINARY_PROVIDER_KEY) as StructImpl
        val ccLinkingContext: CcLinkingContext =
            CcInfo.wrap(executableProvider.getValue("cc_info", StarlarkInfo::class.java))
                .getCcLinkingContext()

        Subject.contains("test_starlark/apple_starlark/liblib.a")
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkLinkBinaryInRootPackage() {
        scratch.file("a.m")
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)
        scratch.file(
            "BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        package(default_visibility = ["//visibility:public"])

        objc_library(
            name = "lib",
            srcs = ["a.m"],
        )

        apple_binary_starlark(
            name = "bin",
            platform_type = "macos",
            deps = [":lib"],
        )
        
        """.trimIndent()
        )

        assertThat(getConfiguredTarget("//:bin")).isNotNull()
    }

    @Test
    @Throws(Exception::class)
    fun testObjcRuleCanDependOnArbitraryStarlarkRuleThatProvidesCcInfo() {
        scratch.file("test_starlark/rule/BUILD")
        scratch.file(
            "test_starlark/rule/apple_rules.bzl",
            """
        load("@rules_cc//cc/common:cc_info.bzl", "CcInfo")
        def my_rule_impl(ctx):
            return [CcInfo()]

        my_rule = rule(
            implementation = my_rule_impl,
            attrs = {},
        )
        
        """.trimIndent()
        )

        scratch.file("test_starlark/apple_starlark/a.m")
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)
        scratch.file(
            "test_starlark/apple_starlark/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("//test_starlark/rule:apple_rules.bzl", "my_rule")
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        package(default_visibility = ["//visibility:public"])

        my_rule(
            name = "my_target",
        )

        objc_library(
            name = "lib",
            srcs = ["a.m"],
            deps = [":my_target"],
        )

        apple_binary_starlark(
            name = "bin",
            platform_type = "ios",
            deps = [":lib"],
        )
        
        """.trimIndent()
        )

        val libTarget: ConfiguredTarget = getConfiguredTarget("//test_starlark/apple_starlark:lib")
        assertThat(CcInfo.get(libTarget)).isNotNull()
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkCanAccessAppleConfiguration() {
        scratch.file("test_starlark/rule/BUILD")
        scratch.file(
            "test_starlark/rule/apple_rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def swift_binary_impl(ctx):
            xcode_config = ctx.attr._xcode_config[apple_common.XcodeVersionConfig]
            cpu = ctx.fragments.apple.single_arch_cpu
            platform = ctx.fragments.apple.single_arch_platform
            xcode_config = ctx.attr._xcode_config[apple_common.XcodeVersionConfig]
            env = apple_common.target_apple_env(xcode_config, platform)
            xcode_version = xcode_config.xcode_version()
            sdk_version = xcode_config.sdk_version_for_platform(platform)
            single_arch_platform = ctx.fragments.apple.single_arch_platform
            single_arch_cpu = ctx.fragments.apple.single_arch_cpu
            platform_type = single_arch_platform.platform_type
            return MyInfo(
                cpu = cpu,
                env = env,
                xcode_version = str(xcode_version),
                sdk_version = str(sdk_version),
                single_arch_platform = str(single_arch_platform),
                single_arch_cpu = str(single_arch_cpu),
                platform_type = str(platform_type),
            )

        swift_binary = rule(
            implementation = swift_binary_impl,
            fragments = ["apple"],
            attrs = {
                "_xcode_config": attr.label(
                    default = configuration_field(
                        fragment = "apple",
                        name = "xcode_config_label",
                    ),
                ),
            },
        )
        
        """.trimIndent()
        )

        scratch.file("test_starlark/apple_starlark/a.m")
        scratch.file(
            "test_starlark/apple_starlark/BUILD",
            """
        load("//test_starlark/rule:apple_rules.bzl", "swift_binary")

        package(default_visibility = ["//visibility:public"])

        swift_binary(
           name="my_target",
        )
        
        """.trimIndent()
        )

        useConfiguration("--apple_platform_type=ios", "--ios_multi_cpus=x86_64", "--xcode_version=7.3")
        val starlarkTarget: ConfiguredTarget =
            getConfiguredTarget("//test_starlark/apple_starlark:my_target")
        val myInfo: StructImpl = getMyInfoFromTarget(starlarkTarget)

        val iosCpu: Any? = myInfo.getValue("cpu")
        val env = myInfo.getValue("env") as MutableMap<String?, String?>?
        val sdkVersion: Any? = myInfo.getValue("sdk_version")

        Truth.assertThat(iosCpu).isEqualTo("x86_64")
        Truth.assertThat(env).containsEntry("APPLE_SDK_PLATFORM", "iPhoneSimulator")
        Truth.assertThat(env).containsEntry("APPLE_SDK_VERSION_OVERRIDE", "8.4")
        Truth.assertThat(sdkVersion).isEqualTo("8.4")
        assertThat(myInfo.getValue("xcode_version")).isEqualTo("7.3")
        assertThat(myInfo.getValue("single_arch_platform")).isEqualTo("ios_simulator")
        assertThat(myInfo.getValue("single_arch_cpu")).isEqualTo("x86_64")
        assertThat(myInfo.getValue("platform_type")).isEqualTo("ios")
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkCanAccessApplePlatformNames() {
        scratch.file("test_starlark/rule/BUILD")
        scratch.file(
            "test_starlark/rule/apple_rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _test_rule_impl(ctx):
            platform = ctx.fragments.apple.single_arch_platform
            return MyInfo(
                name = platform.name_in_plist,
            )

        test_rule = rule(
            implementation = _test_rule_impl,
            fragments = ["apple"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test_starlark/apple_starlark/BUILD",
            """
        load("//test_starlark/rule:apple_rules.bzl", "test_rule")

        package(default_visibility = ["//visibility:public"])

        test_rule(
            name = "my_target",
        )
        
        """.trimIndent()
        )

        useConfiguration("--ios_multi_cpus=x86_64", "--apple_platform_type=ios")
        val starlarkTarget: ConfiguredTarget =
            getConfiguredTarget("//test_starlark/apple_starlark:my_target")

        val name: Any? = getMyInfoFromTarget(starlarkTarget).getValue("name")
        Truth.assertThat(name).isEqualTo("iPhoneSimulator")
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkCanAccessAppleToolchain() {
        scratch.file("test_starlark/rule/BUILD")
        scratch.file(
            "test_starlark/rule/apple_rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def swift_binary_impl(ctx):
            apple_toolchain = apple_common.apple_toolchain()
            sdk_dir = apple_toolchain.sdk_dir()
            platform_developer_framework_dir = \
                apple_toolchain.platform_developer_framework_dir(ctx.fragments.apple)
            return MyInfo(
                platform_developer_framework_dir = platform_developer_framework_dir,
                sdk_dir = sdk_dir,
            )

        swift_binary = rule(
            implementation = swift_binary_impl,
            fragments = ["apple"],
        )
        
        """.trimIndent()
        )

        scratch.file("test_starlark/apple_starlark/a.m")
        scratch.file(
            "test_starlark/apple_starlark/BUILD",
            """
        load("//test_starlark/rule:apple_rules.bzl", "swift_binary")

        package(default_visibility = ["//visibility:public"])

        swift_binary(
            name = "my_target",
        )
        
        """.trimIndent()
        )

        useConfiguration("--apple_platform_type=ios", "--ios_multi_cpus=x86_64")
        val starlarkTarget: ConfiguredTarget =
            getConfiguredTarget("//test_starlark/apple_starlark:my_target")
        val myInfo: StructImpl = getMyInfoFromTarget(starlarkTarget)

        val platformDevFrameworksDir = myInfo.getValue("platform_developer_framework_dir") as String?
        val sdkDir = myInfo.getValue("sdk_dir") as String?

        Truth.assertThat(platformDevFrameworksDir)
            .isEqualTo(
                "__BAZEL_XCODE_DEVELOPER_DIR__"
                        + "/Platforms/iPhoneSimulator.platform/Developer/Library/Frameworks"
            )
        Truth.assertThat(sdkDir).isEqualTo("__BAZEL_XCODE_SDKROOT__")
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkCanAccessSdkAndMinimumOs() {
        scratch.file("test_starlark/rule/BUILD")
        scratch.file(
            "test_starlark/rule/apple_rules.bzl",
            """
load("//myinfo:myinfo.bzl", "MyInfo")

def swift_binary_impl(ctx):
    xcode_config = ctx.attr._xcode_config[apple_common.XcodeVersionConfig]
    ios_sdk_version = xcode_config.sdk_version_for_platform(apple_common.platform.ios_device)
    watchos_sdk_version = xcode_config.sdk_version_for_platform(
        apple_common.platform.watchos_device)
    tvos_sdk_version = xcode_config.sdk_version_for_platform(apple_common.platform.tvos_device)
    macos_sdk_version = xcode_config.sdk_version_for_platform(apple_common.platform.macos)
    ios_minimum_os = xcode_config.minimum_os_for_platform_type(apple_common.platform_type.ios)
    watchos_minimum_os = xcode_config.minimum_os_for_platform_type(
        apple_common.platform_type.watchos)
    tvos_minimum_os = xcode_config.minimum_os_for_platform_type(apple_common.platform_type.tvos)
    visionos_minimum_os = xcode_config.minimum_os_for_platform_type(
        apple_common.platform_type.visionos)
    return MyInfo(
        ios_sdk_version = str(ios_sdk_version),
        watchos_sdk_version = str(watchos_sdk_version),
        tvos_sdk_version = str(tvos_sdk_version),
        macos_sdk_version = str(macos_sdk_version),
        ios_minimum_os = str(ios_minimum_os),
        watchos_minimum_os = str(watchos_minimum_os),
        tvos_minimum_os = str(tvos_minimum_os),
        visionos_minimum_os = str(visionos_minimum_os),
    )

swift_binary = rule(
    implementation = swift_binary_impl,
    fragments = ["apple"],
    attrs = {"_xcode_config": attr.label(default = configuration_field(
        fragment = "apple",
        name = "xcode_config_label",
    ))},
)

""".trimIndent()
        )

        scratch.file("test_starlark/apple_starlark/a.m")
        scratch.file(
            "test_starlark/apple_starlark/BUILD",
            """
        load("//test_starlark/rule:apple_rules.bzl", "swift_binary")

        package(default_visibility = ["//visibility:public"])

        swift_binary(
            name = "my_target",
        )
        
        """.trimIndent()
        )

        useConfiguration(
            "--ios_sdk_version=1.1",
            "--ios_minimum_os=1.0",
            "--watchos_sdk_version=2.1",
            "--watchos_minimum_os=2.0",
            "--tvos_sdk_version=3.1",
            "--tvos_minimum_os=3.0",
            "--macos_sdk_version=4.1",
            "--minimum_os_version=5.1"
        )
        var starlarkTarget: ConfiguredTarget =
            getConfiguredTarget("//test_starlark/apple_starlark:my_target")
        var myInfo: StructImpl = getMyInfoFromTarget(starlarkTarget)

        assertThat(myInfo.getValue("ios_sdk_version")).isEqualTo("1.1")
        assertThat(myInfo.getValue("ios_minimum_os")).isEqualTo("1.0")
        assertThat(myInfo.getValue("watchos_sdk_version")).isEqualTo("2.1")
        assertThat(myInfo.getValue("watchos_minimum_os")).isEqualTo("2.0")
        assertThat(myInfo.getValue("tvos_sdk_version")).isEqualTo("3.1")
        assertThat(myInfo.getValue("tvos_minimum_os")).isEqualTo("3.0")
        assertThat(myInfo.getValue("macos_sdk_version")).isEqualTo("4.1")
        assertThat(myInfo.getValue("visionos_minimum_os")).isEqualTo("5.1")

        useConfiguration(
            "--ios_sdk_version=1.1",
            "--watchos_sdk_version=2.1",
            "--tvos_sdk_version=3.1",
            "--macos_sdk_version=4.1"
        )
        starlarkTarget = getConfiguredTarget("//test_starlark/apple_starlark:my_target")
        myInfo = getMyInfoFromTarget(starlarkTarget)

        assertThat(myInfo.getValue("ios_sdk_version")).isEqualTo("1.1")
        assertThat(myInfo.getValue("ios_minimum_os")).isEqualTo("1.1")
        assertThat(myInfo.getValue("watchos_sdk_version")).isEqualTo("2.1")
        assertThat(myInfo.getValue("watchos_minimum_os")).isEqualTo("2.1")
        assertThat(myInfo.getValue("tvos_sdk_version")).isEqualTo("3.1")
        assertThat(myInfo.getValue("tvos_minimum_os")).isEqualTo("3.1")
        assertThat(myInfo.getValue("macos_sdk_version")).isEqualTo("4.1")
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkCanAccessObjcConfiguration() {
        scratch.file("test_starlark/rule/BUILD")
        scratch.file(
            "test_starlark/rule/objc_rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def swift_binary_impl(ctx):
            compilation_mode_copts = ctx.fragments.objc.copts_for_current_compilation_mode
            return MyInfo(
                compilation_mode_copts = compilation_mode_copts,
            )

        swift_binary = rule(
            implementation = swift_binary_impl,
            fragments = ["objc"],
        )
        
        """.trimIndent()
        )

        scratch.file("test_starlark/objc_starlark/a.m")
        scratch.file(
            "test_starlark/objc_starlark/BUILD",
            """
        load("//test_starlark/rule:objc_rules.bzl", "swift_binary")

        package(default_visibility = ["//visibility:public"])

        swift_binary(
            name = "my_target",
        )
        
        """.trimIndent()
        )

        useConfiguration("--compilation_mode=fastbuild")
        val starlarkTarget: ConfiguredTarget =
            getConfiguredTarget("//test_starlark/objc_starlark:my_target")
        val myInfo: StructImpl = getMyInfoFromTarget(starlarkTarget)

        val compilationModeCopts: Any? = myInfo.getValue("compilation_mode_copts")

        Truth.assertThat(compilationModeCopts as MutableList<*>?).containsExactly("-O0", "-DDEBUG=1")
    }

    @Test
    @Throws(Exception::class)
    fun testUsesDebugEntitlementsIsTrueIfCompilationModeIsNotOpt() {
        scratch.file("test_starlark/rule/BUILD")
        scratch.file(
            "test_starlark/rule/objc_rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def test_rule_impl(ctx):
            uses_device_debug_entitlements = ctx.fragments.objc.uses_device_debug_entitlements
            return MyInfo(
                uses_device_debug_entitlements = uses_device_debug_entitlements,
            )

        test_rule = rule(
            implementation = test_rule_impl,
            fragments = ["objc"],
        )
        
        """.trimIndent()
        )

        scratch.file("test_starlark/objc_starlark/a.m")
        scratch.file(
            "test_starlark/objc_starlark/BUILD",
            """
        load("//test_starlark/rule:objc_rules.bzl", "test_rule")

        package(default_visibility = ["//visibility:public"])

        test_rule(
            name = "my_target",
        )
        
        """.trimIndent()
        )

        useConfiguration("--compilation_mode=dbg")
        val starlarkTarget: ConfiguredTarget =
            getConfiguredTarget("//test_starlark/objc_starlark:my_target")

        val usesDeviceDebugEntitlements =
            getMyInfoFromTarget(starlarkTarget).getValue("uses_device_debug_entitlements") as Boolean
        Truth.assertThat(usesDeviceDebugEntitlements).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun testUsesDebugEntitlementsIsFalseIfFlagIsExplicitlyFalse() {
        scratch.file("test_starlark/rule/BUILD")
        scratch.file(
            "test_starlark/rule/objc_rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def test_rule_impl(ctx):
            uses_device_debug_entitlements = ctx.fragments.objc.uses_device_debug_entitlements
            return MyInfo(
                uses_device_debug_entitlements = uses_device_debug_entitlements,
            )

        test_rule = rule(
            implementation = test_rule_impl,
            fragments = ["objc"],
        )
        
        """.trimIndent()
        )

        scratch.file("test_starlark/objc_starlark/a.m")
        scratch.file(
            "test_starlark/objc_starlark/BUILD",
            """
        load("//test_starlark/rule:objc_rules.bzl", "test_rule")

        package(default_visibility = ["//visibility:public"])

        test_rule(
            name = "my_target",
        )
        
        """.trimIndent()
        )

        useConfiguration("--compilation_mode=dbg", "--nodevice_debug_entitlements")
        val starlarkTarget: ConfiguredTarget =
            getConfiguredTarget("//test_starlark/objc_starlark:my_target")

        val usesDeviceDebugEntitlements =
            getMyInfoFromTarget(starlarkTarget).getValue("uses_device_debug_entitlements") as Boolean
        Truth.assertThat(usesDeviceDebugEntitlements).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun testUsesDebugEntitlementsIsFalseIfCompilationModeIsOpt() {
        scratch.file("test_starlark/rule/BUILD")
        scratch.file(
            "test_starlark/rule/objc_rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def test_rule_impl(ctx):
            uses_device_debug_entitlements = ctx.fragments.objc.uses_device_debug_entitlements
            return MyInfo(
                uses_device_debug_entitlements = uses_device_debug_entitlements,
            )

        test_rule = rule(
            implementation = test_rule_impl,
            fragments = ["objc"],
        )
        
        """.trimIndent()
        )

        scratch.file("test_starlark/objc_starlark/a.m")
        scratch.file(
            "test_starlark/objc_starlark/BUILD",
            """
        load("//test_starlark/rule:objc_rules.bzl", "test_rule")

        package(default_visibility = ["//visibility:public"])

        test_rule(
            name = "my_target",
        )
        
        """.trimIndent()
        )

        useConfiguration("--compilation_mode=opt")
        val starlarkTarget: ConfiguredTarget =
            getConfiguredTarget("//test_starlark/objc_starlark:my_target")

        val usesDeviceDebugEntitlements =
            getMyInfoFromTarget(starlarkTarget).getValue("uses_device_debug_entitlements") as Boolean
        Truth.assertThat(usesDeviceDebugEntitlements).isFalse()
    }

    @Throws(Exception::class)
    private fun createObjcProviderStarlarkTarget(vararg implLines: String?): ConfiguredTarget {
        val impl =
            ObjectArrays.concat<String?>(
                ObjectArrays.concat<String?>(
                    arrayOf<String>(
                        "load('@rules_cc//cc/common:cc_info.bzl', 'CcInfo')",
                        "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
                        "load('@rules_cc//cc/common:objc_info.bzl', 'ObjcInfo')",
                        "def swift_binary_impl(ctx):"
                    ),
                    implLines,
                    String::class.java
                ),
                arrayOf<String>(
                    "swift_binary = rule(",
                    "implementation = swift_binary_impl,",
                    "attrs = {",
                    "   'deps': attr.label_list(",
                    "allow_files = False, mandatory = False, providers = [[ObjcInfo]])",
                    "})"
                ),
                String::class.java
            )

        scratch.file("test_starlark/rule/BUILD")
        scratch.file("test_starlark/rule/objc_rules.bzl", impl)
        scratch.file(
            "test_starlark/objc_starlark/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("//test_starlark/rule:objc_rules.bzl", "swift_binary")

        package(default_visibility = ["//visibility:public"])

        swift_binary(
            name = "my_target",
            deps = [":lib"],
        )

        objc_library(
            name = "lib",
            srcs = ["a.m"],
        )
        
        """.trimIndent()
        )

        return getConfiguredTarget("//test_starlark/objc_starlark:my_target")
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkCanCreateObjcProviderFromScratch() {
        val starlarkTarget: ConfiguredTarget =
            createObjcProviderStarlarkTarget(
                "   file = ctx.actions.declare_file('foo.m')",
                "   ctx.actions.run_shell(outputs=[file], command='echo')",
                "   created_provider = ObjcInfo(source=depset([file]))",
                "   return [created_provider]"
            )

        val dependerProvider: StarlarkInfo = ObjcRuleTestCase.Companion.getObjcInfo(starlarkTarget)
        val sources: ImmutableList<Artifact?>? = ObjcRuleTestCase.Companion.getSource(dependerProvider)
        assertThat(ActionsTestUtil.baseArtifactNames(sources)).containsExactly("foo.m")
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkCanCreateObjcProviderWithStrictDeps() {
        val starlarkTarget: ConfiguredTarget =
            createObjcProviderStarlarkTarget(
                "   strict_includes = depset(['path'])",
                "   created_provider = ObjcInfo(strict_include=strict_includes)",
                "   return [created_provider, CcInfo()]"
            )

        val starlarkProvider: StarlarkInfo = ObjcRuleTestCase.Companion.getObjcInfo(starlarkTarget)
        Truth.assertThat(ObjcRuleTestCase.Companion.getStrictInclude(starlarkProvider)).containsExactly("path")

        scratch.file(
            "test_starlark/objc_starlark2/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "direct_dep",
            deps = ["//test_starlark/objc_starlark:my_target"],
        )
        
        """.trimIndent()
        )

        val starlarkProviderDirectDepender: StarlarkInfo =
            ObjcRuleTestCase.Companion.getObjcInfo(getConfiguredTarget("//test_starlark/objc_starlark2:direct_dep"))
        Truth.assertThat(ObjcRuleTestCase.Companion.getStrictInclude(starlarkProviderDirectDepender)).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkCanCreateObjcProviderFromObjcProvider() {
        scratch.file("test_starlark/rule/BUILD")
        scratch.file(
            "test_starlark/rule/objc_rules.bzl",
            """
        load("@rules_cc//cc/common:objc_info.bzl", "ObjcInfo")

        def library_impl(ctx):
            lib = ctx.label.name + ".a"
            file = ctx.actions.declare_file(lib)
            ctx.actions.run_shell(outputs = [file], command = "echo")
            return [ObjcInfo(j2objc_library = depset([file]))]

        library = rule(implementation = library_impl)

        def binary_impl(ctx):
            dep = ctx.attr.deps[0]
            lib = ctx.label.name + ".a"
            file = ctx.actions.declare_file(lib)
            ctx.actions.run_shell(outputs = [file], command = "echo")
            created_provider = ObjcInfo(
                providers = [dep[ObjcInfo]],
                j2objc_library = depset([file]),
            )
            return [created_provider]

        binary = rule(
            implementation = binary_impl,
            attrs = {
                "deps": attr.label_list(
                    allow_files = False,
                    mandatory = False,
                    providers = [[ObjcInfo]],
                ),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test_starlark/objc_starlark/BUILD",
            """
        load("//test_starlark/rule:objc_rules.bzl", "binary", "library")

        package(default_visibility = ["//visibility:public"])

        binary(
            name = "bin",
            deps = [":lib"],
        )

        library(
            name = "lib",
        )
        
        """.trimIndent()
        )

        val starlarkTarget: ConfiguredTarget = getConfiguredTarget("//test_starlark/objc_starlark:bin")

        val dependerProvider: StarlarkInfo = ObjcRuleTestCase.Companion.getObjcInfo(starlarkTarget)
        val libraries: ImmutableList<Artifact?>? =
            Depset.cast(
                dependerProvider.getValue("j2objc_library"),
                Artifact::class.java,
                "dependerProvider value j2objc_library"
            )
                .toList()

        assertThat(ActionsTestUtil.baseArtifactNames(libraries)).containsExactly("lib.a", "bin.a")
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkErrorOnBadObjcProviderInputKey() {
        val e =
            Assert.assertThrows<AssertionError?>(
                AssertionError::class.java,
                ThrowingRunnable {
                    createObjcProviderStarlarkTarget(
                        "   created_provider = ObjcInfo(foo=depset(['bar']))",
                        "   return created_provider"
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("got unexpected keyword argument: foo")
    }

    @Test
    @Throws(Exception::class)
    fun testEmptyObjcProviderKeysArePresent() {
        scratch.file("test_starlark/rule/BUILD")
        scratch.file(
            "test_starlark/rule/apple_rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("@rules_cc//cc/common:objc_info.bzl", "ObjcInfo")

        def swift_binary_impl(ctx):
            objc_provider = ctx.attr.deps[0][ObjcInfo]
            return MyInfo(
                empty_value = objc_provider.j2objc_library,
            )

        swift_binary = rule(
            implementation = swift_binary_impl,
            fragments = ["apple"],
            attrs = {
                "deps": attr.label_list(
                    allow_files = False,
                    mandatory = False,
                    providers = [[ObjcInfo]],
                ),
            },
        )
        
        """.trimIndent()
        )

        scratch.file("test_starlark/apple_starlark/a.m")
        scratch.file(
            "test_starlark/apple_starlark/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("//test_starlark/rule:apple_rules.bzl", "swift_binary")

        package(default_visibility = ["//visibility:public"])

        swift_binary(
            name = "my_target",
            deps = [":lib"],
        )

        objc_library(
            name = "lib",
            srcs = ["a.m"],
        )
        
        """.trimIndent()
        )
        val starlarkTarget: ConfiguredTarget =
            getConfiguredTarget("//test_starlark/apple_starlark:my_target")
        val emptyValue: NestedSet<Artifact?> =
            Depset.cast(
                getMyInfoFromTarget(starlarkTarget).getValue("empty_value"),
                Artifact::class.java,
                "provider \"empty_value\"'s j2objc_library"
            )
        assertThat(emptyValue.toList()).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun testDottedVersion() {
        scratch.file("test_starlark/rule/BUILD", "exports_files(['test_artifact'])")
        scratch.file(
            "test_starlark/rule/apple_rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _test_rule_impl(ctx):
            version = apple_common.dotted_version("5.4")
            return MyInfo(
                version = version,
            )

        test_rule = rule(implementation = _test_rule_impl)
        
        """.trimIndent()
        )

        scratch.file(
            "test_starlark/apple_starlark/BUILD",
            """
        load("//test_starlark/rule:apple_rules.bzl", "test_rule")

        package(default_visibility = ["//visibility:public"])

        test_rule(
            name = "my_target",
        )
        
        """.trimIndent()
        )

        val starlarkTarget: ConfiguredTarget =
            getConfiguredTarget("//test_starlark/apple_starlark:my_target")

        val version = getMyInfoFromTarget(starlarkTarget).getValue("version") as DottedVersion?
        Truth.assertThat<DottedVersion?>(version).isEqualTo(DottedVersion.fromString("5.4"))
    }

    @Test
    @Throws(Exception::class)
    fun testDottedVersion_invalid() {
        scratch.file("test_starlark/rule/BUILD", "exports_files(['test_artifact'])")
        scratch.file(
            "test_starlark/rule/apple_rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _test_rule_impl(ctx):
            version = apple_common.dotted_version("hello")
            return MyInfo(
                version = version,
            )

        test_rule = rule(implementation = _test_rule_impl)
        
        """.trimIndent()
        )

        scratch.file(
            "test_starlark/apple_starlark/BUILD",
            """
        load("//test_starlark/rule:apple_rules.bzl", "test_rule")

        package(default_visibility = ["//visibility:public"])

        test_rule(
            name = "my_target",
        )
        
        """.trimIndent()
        )

        val e =
            Assert.assertThrows<AssertionError?>(
                AssertionError::class.java,
                ThrowingRunnable { getConfiguredTarget("//test_starlark/apple_starlark:my_target") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Dotted version components must all start with the form")
    }

    /**
     * This test verifies that its possible to use the Starlark constructor of ObjcProvider as a
     * provider key to obtain the provider. This test only needs to exist as long as there are two
     * methods of retrieving ObjcProvider (which is true for legacy reasons). This is the 'new' method
     * of retrieving ObjcProvider.
     */
    @Test
    @Throws(Exception::class)
    fun testObjcProviderStarlarkConstructor() {
        scratch.file("test_starlark/rule/BUILD")
        scratch.file(
            "test_starlark/rule/apple_rules.bzl",
            """
        load("@rules_cc//cc/common:objc_info.bzl", "ObjcInfo")

        def my_rule_impl(ctx):
            dep = ctx.attr.deps[0]
            objc_provider = dep[ObjcInfo]
            return objc_provider

        my_rule = rule(
            implementation = my_rule_impl,
            attrs = {
                "deps": attr.label_list(allow_files = False, mandatory = False),
            },
        )
        
        """.trimIndent()
        )
        scratch.file("test_starlark/apple_starlark/a.cc")
        scratch.file(
            "test_starlark/apple_starlark/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("//test_starlark/rule:apple_rules.bzl", "my_rule")

        package(default_visibility = ["//visibility:public"])

        my_rule(
            name = "my_target",
            deps = [":lib"],
        )

        objc_library(
            name = "lib",
            srcs = ["a.m"],
            hdrs = ["a.h"],
        )
        
        """.trimIndent()
        )

        val starlarkTarget: ConfiguredTarget =
            getConfiguredTarget("//test_starlark/apple_starlark:my_target")
        val dependerProvider: StarlarkInfo = ObjcRuleTestCase.Companion.getObjcInfo(starlarkTarget)
        assertThat(dependerProvider).isNotNull()
    }

    @Test
    @Throws(Exception::class)
    fun testDisallowSDKFrameworkAttribute() {
        useConfiguration("--incompatible_disallow_sdk_frameworks_attributes")

        scratch.file(
            "test_starlark/apple_starlark/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "lib",
            srcs = ["a.m"],
            sdk_frameworks = [
                "Accelerate",
                "GLKit",
            ],
        )
        
        """.trimIndent()
        )
        val e =
            Assert.assertThrows<AssertionError?>(
                AssertionError::class.java,
                ThrowingRunnable { getConfiguredTarget("//test_starlark/apple_starlark:lib") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "ERROR /workspace/test_starlark/apple_starlark/BUILD:2:13: "
                        + "in objc_library rule //test_starlark/apple_starlark:lib:"
            )

        assertContainsEvent(
            "sdk_frameworks attribute is disallowed. Use explicit dependencies instead."
        )
    }

    @Test
    @Throws(Exception::class)
    fun testDisallowWeakSDKFrameworksAttribute() {
        useConfiguration("--incompatible_disallow_sdk_frameworks_attributes")

        scratch.file(
            "test_starlark/apple_starlark/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "lib",
            srcs = ["a.m"],
            weak_sdk_frameworks = ["XCTest"],
        )
        
        """.trimIndent()
        )
        val e =
            Assert.assertThrows<AssertionError?>(
                AssertionError::class.java,
                ThrowingRunnable { getConfiguredTarget("//test_starlark/apple_starlark:lib") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "ERROR /workspace/test_starlark/apple_starlark/BUILD:2:13: "
                        + "in objc_library rule //test_starlark/apple_starlark:lib:"
            )

        assertContainsEvent(
            "weak_sdk_frameworks attribute is disallowed. Use explicit dependencies instead."
        )
    }

    companion object {
        private val APPLE_EXECUTABLE_BINARY_PROVIDER_KEY: Provider.Key = Key(
            keyForBuild(
                Label.parseCanonicalUnchecked(
                    "//third_party/bazel_rules/rules_apple:apple_binary_starlark.bzl"
                )
            ),
            "AppleExecutableBinaryInfo"
        )
    }
}
