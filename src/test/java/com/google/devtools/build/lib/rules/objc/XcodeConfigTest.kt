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
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.rules.apple.DottedVersion
import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable

/** Unit tests for the `xcode_config` rule.  */
@RunWith(JUnit4::class)
class XcodeConfigTest : BuildViewTestCase() {
    private val ev: BazelEvaluationTestCase = BazelEvaluationTestCase()

    @Test
    @Throws(Exception::class)
    fun testEmptyConfig_noVersionFlag() {
        scratch.file(
            "xcode/BUILD",
            "load('@build_bazel_apple_support//xcode:xcode_config.bzl', 'xcode_config')",
            "xcode_config(name = 'foo',)"
        )
        useConfiguration("--xcode_version_config=//xcode:foo")

        assertIosSdkVersion(AppleCommandLineOptions.DEFAULT_IOS_SDK_VERSION)
    }

    @Test
    @Throws(Exception::class)
    fun testDefaultVersion() {
        val fileBuilder = BuildFileBuilder()
        fileBuilder
            .addExplicitVersion("version512", "5.1.2", true)
            .addExplicitVersion("version84", "8.4", false)
            .write(scratch, "xcode/BUILD")

        useConfiguration("--xcode_version_config=//xcode:foo")

        assertXcodeVersion("5.1.2")
        assertAvailability("unknown")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun testMutualAndExplicitXcodesThrows() {
        scratch.file(
            "xcode/BUILD",
            """
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")
        load("@build_bazel_apple_support//xcode:available_xcodes.bzl", "available_xcodes")
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")

        xcode_config(
            name = "foo",
            default = ":version512",
            local_versions = ":local",
            remote_versions = ":remote",
            versions = [
                ":version512",
                ":version84",
            ],
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            version = "5.1.2",
        )

        xcode_version(
            name = "version84",
            version = "8.4",
        )

        available_xcodes(
            name = "remote",
            default = ":version512",
            versions = [":version512"],
        )

        available_xcodes(
            name = "local",
            default = ":version84",
            versions = [":version84"],
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//xcode:foo")
        assertContainsEvent("'versions' may not be set if '[local,remote]_versions' is set")
    }

    @Test
    @Throws(Exception::class)
    fun testMutualAndDefaultThrows() {
        scratch.file(
            "xcode/BUILD",
            """
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")
        load("@build_bazel_apple_support//xcode:available_xcodes.bzl", "available_xcodes")
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")

        xcode_config(
            name = "foo",
            default = ":version512",
            local_versions = ":local",
            remote_versions = ":remote",
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            version = "5.1.2",
        )

        xcode_version(
            name = "version84",
            version = "8.4",
        )

        available_xcodes(
            name = "remote",
            default = ":version512",
            versions = [":version512"],
        )

        available_xcodes(
            name = "local",
            default = ":version84",
            versions = [":version84"],
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//xcode:foo")
        assertContainsEvent("'default' may not be set if '[local,remote]_versions' is set.")
    }

    @Test
    @Throws(Exception::class)
    fun testNoLocalXcodesThrows() {
        scratch.file(
            "xcode/BUILD",
            """
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")
        load("@build_bazel_apple_support//xcode:available_xcodes.bzl", "available_xcodes")
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")

        xcode_config(
            name = "foo",
            remote_versions = ":remote",
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            version = "5.1.2",
        )

        available_xcodes(
            name = "remote",
            default = ":version512",
            versions = [":version512"],
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//xcode:foo")
        assertContainsEvent("if 'remote_versions' are set, you must also set 'local_versions'")
    }

    @Test
    @Throws(Exception::class)
    fun testAcceptFlagForMutuallyAvailable() {
        BuildFileBuilder()
            .addRemoteVersion("version512", "5.1.2", true)
            .addRemoteVersion("version84", "8.4", false)
            .addLocalVersion("version84", "8.4", true)
            .write(scratch, "xcode/BUILD")

        useConfiguration("--xcode_version=8.4", "--xcode_version_config=//xcode:foo")
        assertXcodeVersion("8.4")
        assertAvailability("both")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET
            )
        )

        assertNoEvents()
    }

    @Test
    @Throws(Exception::class)
    fun testPreferFlagOverMutuallyAvailable() {
        BuildFileBuilder()
            .addRemoteVersion("version512", "5.1.2", true)
            .addRemoteVersion("version84", "8.4", false)
            .addLocalVersion("version84", "8.4", true)
            .write(scratch, "xcode/BUILD")

        useConfiguration("--xcode_version=5.1.2", "--xcode_version_config=//xcode:foo")
        assertXcodeVersion("5.1.2")
        assertAvailability("remote")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN,
                ExecutionRequirements.NO_LOCAL,
                ExecutionRequirements.REQUIREMENTS_SET
            )
        )

        assertContainsEvent(
            "--xcode_version=5.1.2 specified, but it is not available locally. Your build"
                    + " will fail if any actions require a local Xcode."
        )
    }

    @Test
    @Throws(Exception::class)
    fun testPreferMutual_choosesLocalDefaultOverNewest() {
        BuildFileBuilder()
            .addRemoteVersion("version512", "5.1.2", true)
            .addRemoteVersion("version84", "8.4", false)
            .addLocalVersion("version512", "5.1.2", true)
            .addLocalVersion("version84", "8.4", false)
            .write(scratch, "xcode/BUILD")

        useConfiguration(
            "--experimental_prefer_mutual_xcode=true", "--xcode_version_config=//xcode:foo"
        )
        assertXcodeVersion("5.1.2")
        assertAvailability("both")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun testWarnWithExplicitLocalOnlyVersion() {
        BuildFileBuilder()
            .addRemoteVersion("version512", "5.1.2", true)
            .addLocalVersion("version84", "8.4", true)
            .write(scratch, "xcode/BUILD")

        useConfiguration("--xcode_version=8.4", "--xcode_version_config=//xcode:foo")
        assertXcodeVersion("8.4")
        assertAvailability("local")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN,
                ExecutionRequirements.NO_REMOTE,
                ExecutionRequirements.REQUIREMENTS_SET
            )
        )

        assertContainsEvent(
            ("--xcode_version=8.4 specified, but it is not available remotely. Actions"
                    + " requiring Xcode will be run locally, which could make your build"
                    + " slower.")
        )
    }

    @Test
    @Throws(Exception::class)
    fun testPreferLocalDefaultIfNoMutualNoFlagDifferentMainVersion() {
        BuildFileBuilder()
            .addRemoteVersion("version512", "5.1.2", true)
            .addLocalVersion("version84", "8.4", true)
            .write(scratch, "xcode/BUILD")

        useConfiguration("--xcode_version_config=//xcode:foo")
        assertXcodeVersion("8.4")
        assertAvailability("local")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN,
                ExecutionRequirements.NO_REMOTE,
                ExecutionRequirements.REQUIREMENTS_SET
            )
        )

        assertContainsEvent(
            ("Using a local Xcode version, '8.4', since there are no"
                    + " remotely available Xcodes on this machine. Consider downloading one of the"
                    + " remotely available Xcode versions (5.1.2)")
        )
    }

    @Test
    @Throws(Exception::class)
    fun testPreferLocalDefaultIfNoMutualNoFlagDifferentBuildAlias() {
        // Version 10.0 of different builds are not matched
        BuildFileBuilder()
            .addRemoteVersion("version10", "10.0", true, "10.0.0.101ff", "10.0")
            .addLocalVersion("version10.0.0.10C504", "10.0.0.10C504", true, "10.0.0.10C504", "10.0")
            .write(scratch, "xcode/BUILD")

        useConfiguration("--xcode_version_config=//xcode:foo")
        assertXcodeVersion("10.0.0.10C504")
        assertAvailability("local")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN,
                ExecutionRequirements.NO_REMOTE,
                ExecutionRequirements.REQUIREMENTS_SET
            )
        )

        assertContainsEvent(
            ("Using a local Xcode version, '10.0.0.10C504', since there are no"
                    + " remotely available Xcodes on this machine. Consider downloading one of the"
                    + " remotely available Xcode versions (10.0)")
        )
    }

    @Test
    @Throws(Exception::class)
    fun testPreferLocalDefaultIfNoMutualNoFlagDifferentFullVersion() {
        // Version 10.0 of different builds are not matched
        BuildFileBuilder()
            .addRemoteVersion("version10", "10.0.0.101ff", true, "10.0", "10.0.0.101ff")
            .addLocalVersion("version10.0.0.10C504", "10.0.0.10C504", true, "10.0.0.10C504", "10.0")
            .write(scratch, "xcode/BUILD")

        useConfiguration("--xcode_version_config=//xcode:foo")
        assertXcodeVersion("10.0.0.10C504")
        assertAvailability("local")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN,
                ExecutionRequirements.NO_REMOTE,
                ExecutionRequirements.REQUIREMENTS_SET
            )
        )

        assertContainsEvent(
            ("Using a local Xcode version, '10.0.0.10C504', since there are no"
                    + " remotely available Xcodes on this machine. Consider downloading one of the"
                    + " remotely available Xcode versions (10.0.0.101ff)")
        )
    }

    @Test
    @Throws(Exception::class)
    fun testChooseNewestMutualXcode() {
        BuildFileBuilder()
            .addRemoteVersion("version92", "9.2", true)
            .addRemoteVersion("version10", "10", false, "10.0.0.10C504")
            .addRemoteVersion("version84", "8.4", false)
            .addLocalVersion("version9", "9", true)
            .addLocalVersion("version84", "8.4", false)
            .addLocalVersion("version10.0.0.10C504", "10.0.0.10C504", false, "10.0")
            .write(scratch, "xcode/BUILD")

        useConfiguration("--xcode_version_config=//xcode:foo")
        assertXcodeVersion("10")
        assertAvailability("both")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET
            )
        )

        assertNoEvents()
    }

    @Test
    @Throws(Exception::class)
    fun testPreferMutualXcodeFalseOverridesMutual() {
        BuildFileBuilder()
            .addRemoteVersion("version10", "10", true, "10.0.0.10C504")
            .addLocalVersion("version84", "8.4", true)
            .addLocalVersion("version10.0.0.10C504", "10.0.0.10C504", false, "10.0")
            .write(scratch, "xcode/BUILD")

        useConfiguration(
            "--xcode_version_config=//xcode:foo", "--experimental_prefer_mutual_xcode=false"
        )
        assertXcodeVersion("8.4")
        assertAvailability("local")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun testLocalDefaultCanBeMutuallyAvailable() {
        // Passing "--experimental_prefer_mutual_xcode=false" allows toggling between Xcode versions
        // using xcode-select. This test ensures that if the version from xcode-select is available
        // remotely, both local and remote execution are enabled.
        BuildFileBuilder()
            .addRemoteVersion("version10", "10", true, "10.0.0.10C504")
            .addLocalVersion("version10.0.0.10C504", "10.0.0.10C504", true, "10.0")
            .write(scratch, "xcode/BUILD")

        useConfiguration(
            "--xcode_version_config=//xcode:foo", "--experimental_prefer_mutual_xcode=false"
        )
        assertXcodeVersion("10")
        assertAvailability("both")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET
            )
        )

        assertNoEvents()
    }

    @Test
    @Throws(Exception::class)
    fun testPreferLocalDefaultOverDifferentBuild() {
        BuildFileBuilder()
            .addRemoteVersion("version10", "10", true, "10.0.0.10C1ff")
            .addLocalVersion("version10.0.0.10C504", "10.0.0.10C504", true, "10")
            .write(scratch, "xcode/BUILD")

        useConfiguration(
            "--xcode_version_config=//xcode:foo", "--experimental_prefer_mutual_xcode=false"
        )
        assertXcodeVersion("10.0.0.10C504")
        assertAvailability("local")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET
            )
        )

        assertContainsEvent(
            ("Using a local Xcode version, '10.0.0.10C504', since there are no"
                    + " remotely available Xcodes on this machine. Consider downloading one of the"
                    + " remotely available Xcode versions (10)")
        )
    }

    @Test
    @Throws(Exception::class)
    fun testInvalidXcodeFromMutualThrows() {
        BuildFileBuilder()
            .addRemoteVersion("version512", "5.1.2", true)
            .addRemoteVersion("version84", "8.4", false)
            .addLocalVersion("version84", "8.4", true)
            .write(scratch, "xcode/BUILD")

        useConfiguration("--xcode_version=6")
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//xcode:foo")
        assertContainsEvent(
            ("--xcode_version=6 specified, but '6' is not an available Xcode version."
                    + " Locally available versions: [8.4]. Remotely available versions:"
                    + " [5.1.2, 8.4].")
        )
    }

    @Test
    @Throws(Exception::class)
    fun xcodeVersionConfigConstructor() {
        scratch.file(
            "test_starlark/extension.bzl",
            """
        result = provider()

        def _impl(ctx):
            return [result(xcode_version = apple_common.XcodeVersionConfig(
                ios_sdk_version = "1.1",
                ios_minimum_os_version = "1.2",
                watchos_sdk_version = "1.3",
                watchos_minimum_os_version = "1.4",
                tvos_sdk_version = "1.5",
                tvos_minimum_os_version = "1.6",
                macos_sdk_version = "1.7",
                macos_minimum_os_version = "1.8",
                visionos_sdk_version = "1.9",
                visionos_minimum_os_version = "1.10",
                xcode_version = "1.11",
                availability = "UNKNOWN",
                xcode_version_flag = "0.0",
                include_xcode_execution_info = False,
            ))]

        my_rule = rule(_impl, attrs = {"dep": attr.label()})
        
        """.trimIndent()
        )
        scratch.file(
            "test_starlark/BUILD",
            """
        load(":extension.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )
        assertNoEvents()
        val myRuleTarget: ConfiguredTarget = getConfiguredTarget("//test_starlark:test")
        val info: StructImpl =
            myRuleTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//test_starlark:extension.bzl")), "result"
                )
            ) as StructImpl
        val actual: StructImpl = info.getValue("xcode_version", StructImpl::class.java)
        Truth.assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.IOS_DEVICE)
                .toString()
        )
            .isEqualTo("1.1")
        Truth.assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.IOS_SIMULATOR)
                .toString()
        )
            .isEqualTo("1.1")
        Truth.assertThat(
            callProviderMethod(
                actual, "minimum_os_for_platform_type", ApplePlatform.PlatformType.IOS
            )
                .toString()
        )
            .isEqualTo("1.2")
        Truth.assertThat(
            callProviderMethod(
                actual, "minimum_os_for_platform_type", ApplePlatform.PlatformType.CATALYST
            )
                .toString()
        )
            .isEqualTo("1.2")
        Truth.assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.WATCHOS_DEVICE)
                .toString()
        )
            .isEqualTo("1.3")
        Truth.assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.WATCHOS_SIMULATOR)
                .toString()
        )
            .isEqualTo("1.3")
        Truth.assertThat(
            callProviderMethod(
                actual, "minimum_os_for_platform_type", ApplePlatform.PlatformType.WATCHOS
            )
                .toString()
        )
            .isEqualTo("1.4")
        Truth.assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.TVOS_DEVICE)
                .toString()
        )
            .isEqualTo("1.5")
        Truth.assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.TVOS_SIMULATOR)
                .toString()
        )
            .isEqualTo("1.5")
        Truth.assertThat(
            callProviderMethod(
                actual, "minimum_os_for_platform_type", ApplePlatform.PlatformType.TVOS
            )
                .toString()
        )
            .isEqualTo("1.6")
        Truth.assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.MACOS).toString()
        )
            .isEqualTo("1.7")
        Truth.assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.CATALYST)
                .toString()
        )
            .isEqualTo("1.7")
        Truth.assertThat(
            callProviderMethod(
                actual, "minimum_os_for_platform_type", ApplePlatform.PlatformType.MACOS
            )
                .toString()
        )
            .isEqualTo("1.8")
        Truth.assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.VISIONOS_DEVICE)
                .toString()
        )
            .isEqualTo("1.9")
        Truth.assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.VISIONOS_SIMULATOR)
                .toString()
        )
            .isEqualTo("1.9")
        Truth.assertThat(
            callProviderMethod(
                actual, "minimum_os_for_platform_type", ApplePlatform.PlatformType.VISIONOS
            )
                .toString()
        )
            .isEqualTo("1.10")
        Truth.assertThat(callProviderMethod(actual, "xcode_version").toString()).isEqualTo("1.11")
        Truth.assertThat(callProviderMethod(actual, "availability")).isEqualTo("unknown")
        Truth.assertThat(callProviderMethod(actual, "execution_info"))
            .isEqualTo(ImmutableMap.of<String?, String?>("requires-darwin", "", "supports-xcode-requirements-set", ""))
    }

    @Test
    @Throws(Exception::class)
    fun xcodeVersionConfig_throwsOnBadInput() {
        scratch.file(
            "test_starlark/extension.bzl",
            """
        result = provider()

        def _impl(ctx):
            return [result(xcode_version = apple_common.XcodeVersionConfig(
                ios_sdk_version = "not a valid dotted version",
                ios_minimum_os_version = "1.2",
                watchos_sdk_version = "1.3",
                watchos_minimum_os_version = "1.4",
                tvos_sdk_version = "1.5",
                tvos_minimum_os_version = "1.6",
                macos_sdk_version = "1.7",
                macos_minimum_os_version = "1.8",
                visionos_sdk_version = "1.9",
                visionos_minimum_os_version = "1.10",
                xcode_version = "1.11",
                availability = "UNKNOWN",
                xcode_version_flag = "0.0",
                include_xcode_execution_info = False,
            ))]

        my_rule = rule(_impl, attrs = {"dep": attr.label()})
        
        """.trimIndent()
        )
        scratch.file(
            "test_starlark/BUILD",
            """
        load(":extension.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )
        assertNoEvents()
        Assert.assertThrows<AssertionError?>(
            AssertionError::class.java,
            ThrowingRunnable { getConfiguredTarget("//test_starlark:test") })
        assertContainsEvent("Dotted version components must all start with the form")
        assertContainsEvent("got 'not a valid dotted version'")
    }

    @Test
    @Throws(Exception::class)
    fun xcodeVersionConfig_exposesExpectedAttributes() {
        scratch.file(
            "test_starlark/extension.bzl",
            """
        result = provider()

        def _impl(ctx):
            xcode_version = apple_common.XcodeVersionConfig(
                ios_sdk_version = "1.1",
                ios_minimum_os_version = "1.2",
                watchos_sdk_version = "1.3",
                watchos_minimum_os_version = "2.4",
                tvos_sdk_version = "1.5",
                tvos_minimum_os_version = "1.6",
                macos_sdk_version = "1.7",
                macos_minimum_os_version = "1.8",
                visionos_sdk_version = "1.9",
                visionos_minimum_os_version = "1.10",
                xcode_version = "1.11",
                availability = "UNKNOWN",
                xcode_version_flag = "0.0",
                include_xcode_execution_info = False,
            )
            return [result(
                xcode_version = xcode_version.xcode_version(),
                min_os = xcode_version.minimum_os_for_platform_type(
                    ctx.fragments.apple.single_arch_platform.platform_type,
                ),
            )]

        my_rule = rule(
            _impl,
            attrs = {"dep": attr.label()},
            fragments = ["apple"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test_starlark/BUILD",
            """
        load(":extension.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )
        assertNoEvents()
        val myRuleTarget: ConfiguredTarget = getConfiguredTarget("//test_starlark:test")
        val info: StructImpl =
            myRuleTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//test_starlark:extension.bzl")), "result"
                )
            ) as StructImpl
        assertThat(info.getValue("xcode_version").toString()).isEqualTo("1.11")
        assertThat(info.getValue("min_os").toString()).isEqualTo("1.8")
    }

    @Test
    @Throws(Exception::class)
    fun testValidVersion() {
        BuildFileBuilder()
            .addExplicitVersion("version512", "5.1.2", true)
            .write(scratch, "xcode/BUILD")

        useConfiguration("--xcode_version=5.1.2", "--xcode_version_config=//xcode:foo")

        assertXcodeVersion("5.1.2")
        assertAvailability("unknown")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun testValidAlias_dottedVersion() {
        BuildFileBuilder()
            .addExplicitVersion("version512", "5.1.2", true, "5")
            .write(scratch, "xcode/BUILD")

        useConfiguration("--xcode_version=5", "--xcode_version_config=//xcode:foo")

        assertXcodeVersion("5.1.2")
        assertAvailability("unknown")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun testValidAlias_nonNumerical() {
        BuildFileBuilder()
            .addExplicitVersion("version512", "5.1.2", true, "valid_version")
            .write(scratch, "xcode/BUILD")

        useConfiguration("--xcode_version=valid_version", "--xcode_version_config=//xcode:foo")

        assertXcodeVersion("5.1.2")
        assertAvailability("unknown")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun testInvalidXcodeSpecified() {
        BuildFileBuilder()
            .addExplicitVersion("version512", "5.1.2", true)
            .addExplicitVersion("version84", "8.4", false)
            .write(scratch, "xcode/BUILD")

        useConfiguration("--xcode_version=6")
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//xcode:foo")
        assertContainsEvent(
            "--xcode_version=6 specified, but '6' is not an available Xcode version. "
                    + "If you believe you have '6' installed"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testRequiresDefault() {
        scratch.file(
            "xcode/BUILD",
            """
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")

        xcode_config(
            name = "foo",
            versions = [":version512"],
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            version = "5.1.2",
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//xcode:foo")
        assertContainsEvent("default version must be specified")
    }

    @Test
    @Throws(Exception::class)
    fun testDuplicateAliases_definedVersion() {
        BuildFileBuilder()
            .addExplicitVersion("version512", "5.1.2", true, "5")
            .addExplicitVersion("version5", "5.0", false, "5")
            .write(scratch, "xcode/BUILD")

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//xcode:foo")
        assertContainsEvent(
            "'5' is registered to multiple labels (@@//xcode:version512, @@//xcode:version5)"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testDuplicateAliases_withinAvailableXcodes() {
        BuildFileBuilder()
            .addRemoteVersion("version512", "5.1.2", true, "5")
            .addRemoteVersion("version5", "5.0", false, "5")
            .addLocalVersion("version5", "5.0", true, "5")
            .write(scratch, "xcode/BUILD")

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//xcode:foo")
        assertContainsEvent(
            "'5' is registered to multiple labels (@@//xcode:version512, @@//xcode:version5)"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testVersionAliasedToItself() {
        BuildFileBuilder()
            .addExplicitVersion("version512", "5.1.2", true, "5.1.2")
            .write(scratch, "xcode/BUILD")

        useConfiguration("--xcode_version_config=//xcode:foo")

        assertXcodeVersion("5.1.2")
        assertAvailability("unknown")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun testDuplicateVersionNumbers() {
        BuildFileBuilder()
            .addExplicitVersion("version512", "5.1.2", true)
            .addExplicitVersion("version5", "5.1.2", false, "5")
            .write(scratch, "xcode/BUILD")

        useConfiguration("--xcode_version=5")
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//xcode:foo")
        assertContainsEvent(
            "'5.1.2' is registered to multiple labels (@@//xcode:version512, @@//xcode:version5)"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testVersionConflictsWithAlias() {
        BuildFileBuilder()
            .addExplicitVersion("version512", "5.1.2", true)
            .addExplicitVersion("version5", "5.0", false, "5.1.2")
            .write(scratch, "xcode/BUILD")

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//xcode:foo")
        assertContainsEvent(
            "'5.1.2' is registered to multiple labels (@@//xcode:version512, @@//xcode:version5)"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testDefaultIosSdkVersion() {
        scratch.file(
            "xcode/BUILD",
            """
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")

        xcode_config(
            name = "foo",
            default = ":version512",
            versions = [
                ":version512",
                ":version64",
            ],
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            default_ios_sdk_version = "7.1",
            version = "5.1.2",
        )

        xcode_version(
            name = "version64",
            aliases = [
                "6.0",
                "foo",
                "6",
            ],
            default_ios_sdk_version = "43.0",
            version = "6.4",
        )
        
        """.trimIndent()
        )
        useConfiguration("--xcode_version_config=//xcode:foo")

        assertXcodeVersion("5.1.2")
        assertIosSdkVersion("7.1")
        assertAvailability("unknown")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun testDefaultSdkVersions() {
        scratch.file(
            "xcode/BUILD",
            """
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")

        xcode_config(
            name = "foo",
            default = ":version512",
            versions = [
                ":version512",
                ":version64",
            ],
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            default_ios_sdk_version = "101",
            default_macos_sdk_version = "104",
            default_tvos_sdk_version = "103",
            default_watchos_sdk_version = "102",
            version = "5.1.2",
        )

        xcode_version(
            name = "version64",
            aliases = [
                "6.0",
                "foo",
                "6",
            ],
            default_ios_sdk_version = "43.0",
            version = "6.4",
        )
        
        """.trimIndent()
        )
        useConfiguration("--xcode_version_config=//xcode:foo")

        assertXcodeVersion("5.1.2")
        assertAvailability("unknown")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET
            )
        )

        val platformToVersion: ImmutableMap<ApplePlatform?, String?> =
            ImmutableMap.builder<ApplePlatform?, String?>()
                .put(ApplePlatform.IOS_SIMULATOR, "101")
                .put(ApplePlatform.WATCHOS_SIMULATOR, "102")
                .put(ApplePlatform.TVOS_SIMULATOR, "103")
                .put(ApplePlatform.MACOS, "104")
                .build()
        for (platform in platformToVersion.keys) {
            val version: DottedVersion? = DottedVersion.fromString(platformToVersion.get(platform))
            Truth.assertThat<DottedVersion?>(getSdkVersionForPlatform(platform)).isEqualTo(version)
            Truth.assertThat<DottedVersion?>(getMinimumOsVersionForPlatform(platform)).isEqualTo(version)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testDefaultSdkVersions_selectedXcode() {
        scratch.file(
            "xcode/BUILD",
            """
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")

        xcode_config(
            name = "foo",
            default = ":version512",
            versions = [
                ":version512",
                ":version64",
            ],
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            default_ios_sdk_version = "7.1",
            version = "5.1.2",
        )

        xcode_version(
            name = "version64",
            aliases = [
                "6.0",
                "foo",
                "6",
            ],
            default_ios_sdk_version = "43",
            default_macos_sdk_version = "46",
            default_tvos_sdk_version = "45",
            default_watchos_sdk_version = "44",
            version = "6.4",
        )
        
        """.trimIndent()
        )
        useConfiguration("--xcode_version=6", "--xcode_version_config=//xcode:foo")

        assertXcodeVersion("6.4")
        assertAvailability("unknown")
        assertHasRequirements(
            ImmutableList.of<String?>(
                ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET
            )
        )

        val platformToVersion: ImmutableMap<ApplePlatform?, String?> =
            ImmutableMap.builder<ApplePlatform?, String?>()
                .put(ApplePlatform.IOS_SIMULATOR, "43")
                .put(ApplePlatform.WATCHOS_SIMULATOR, "44")
                .put(ApplePlatform.TVOS_SIMULATOR, "45")
                .put(ApplePlatform.MACOS, "46")
                .build()
        for (platform in platformToVersion.keys) {
            val version: DottedVersion? = DottedVersion.fromString(platformToVersion.get(platform))
            Truth.assertThat<DottedVersion?>(getSdkVersionForPlatform(platform)).isEqualTo(version)
            Truth.assertThat<DottedVersion?>(getMinimumOsVersionForPlatform(platform)).isEqualTo(version)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testOverrideDefaultSdkVersions() {
        scratch.file(
            "xcode/BUILD",
            """
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")

        xcode_config(
            name = "foo",
            default = ":version512",
            versions = [
                ":version512",
                ":version64",
            ],
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            default_ios_sdk_version = "7.1",
            version = "5.1.2",
        )

        xcode_version(
            name = "version64",
            aliases = [
                "6.0",
                "foo",
                "6",
            ],
            default_ios_sdk_version = "101",
            default_macos_sdk_version = "104",
            default_tvos_sdk_version = "103",
            default_watchos_sdk_version = "102",
            version = "6.4",
        )
        
        """.trimIndent()
        )
        useConfiguration(
            "--xcode_version=6",
            "--xcode_version_config=//xcode:foo",
            "--ios_sdk_version=15.3",
            "--watchos_sdk_version=15.4",
            "--tvos_sdk_version=15.5",
            "--macos_sdk_version=15.6"
        )

        assertXcodeVersion("6.4")
        assertAvailability("unknown")
        val platformToVersion: ImmutableMap<ApplePlatform?, String?> =
            ImmutableMap.builder<ApplePlatform?, String?>()
                .put(ApplePlatform.IOS_SIMULATOR, "15.3")
                .put(ApplePlatform.WATCHOS_SIMULATOR, "15.4")
                .put(ApplePlatform.TVOS_SIMULATOR, "15.5")
                .put(ApplePlatform.MACOS, "15.6")
                .build()
        for (platform in platformToVersion.keys) {
            val version: DottedVersion? = DottedVersion.fromString(platformToVersion.get(platform))
            Truth.assertThat<DottedVersion?>(getSdkVersionForPlatform(platform)).isEqualTo(version)
            Truth.assertThat<DottedVersion?>(getMinimumOsVersionForPlatform(platform)).isEqualTo(version)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testXcodeVersionFromStarlarkByAlias() {
        scratch.file(
            "test_starlark/BUILD",
            """
        load("//test_starlark:r.bzl", "r")
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")
        load("@build_bazel_apple_support//xcode:xcode_config_alias.bzl", "xcode_config_alias")

        xcode_config_alias(name = "a")

        xcode_config(
            name = "c",
            default = ":v",
            versions = [":v"],
        )

        xcode_version(
            name = "v",
            default_ios_sdk_version = "1.0",
            default_macos_sdk_version = "3.0",
            default_tvos_sdk_version = "2.0",
            default_watchos_sdk_version = "4.0",
            version = "0.0",
        )

        r(name = "r")
        
        """.trimIndent()
        )
        scratch.file(
            "test_starlark/r.bzl",
            """
        MyInfo = provider()

        def _impl(ctx):
            conf = ctx.attr._xcode[apple_common.XcodeVersionConfig]
            ios = apple_common.platform.ios_simulator
            tvos = apple_common.platform.tvos_simulator
            return MyInfo(
                xcode = conf.xcode_version(),
                ios_sdk = conf.sdk_version_for_platform(ios),
                tvos_sdk = conf.sdk_version_for_platform(tvos),
                macos_min = conf.minimum_os_for_platform_type(
                    apple_common.platform_type.macos,
                ),
                watchos_min = conf.minimum_os_for_platform_type(
                    apple_common.platform_type.watchos,
                ),
                availability = conf.availability(),
                execution_info = conf.execution_info(),
            )

        r = rule(
            implementation = _impl,
            attrs = {"_xcode": attr.label(default = Label("//test_starlark:a"))},
            fragments = ["apple"],
        )
        
        """.trimIndent()
        )

        useConfiguration(
            "--xcode_version_config=//test_starlark:c",
            "--tvos_sdk_version=2.5",
            "--watchos_minimum_os=4.5"
        )
        val r: ConfiguredTarget = getConfiguredTarget("//test_starlark:r")
        val key: Provider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test_starlark:r.bzl")), "MyInfo"
            )
        val info: StructImpl = r.get(key) as StructImpl

        assertThat(info.getValue("xcode").toString()).isEqualTo("0.0")
        assertThat(info.getValue("ios_sdk").toString()).isEqualTo("1.0")
        assertThat(info.getValue("tvos_sdk").toString()).isEqualTo("2.5")
        assertThat(info.getValue("macos_min").toString()).isEqualTo("3.0")
        assertThat(info.getValue("watchos_min").toString()).isEqualTo("4.5")
        assertThat(info.getValue("availability").toString()).isEqualTo("unknown")
        Truth.assertThat(info.getValue("execution_info") as MutableMap<*, *>?)
            .containsKey(ExecutionRequirements.REQUIRES_DARWIN)
        Truth.assertThat(info.getValue("execution_info") as MutableMap<*, *>?)
            .containsKey(ExecutionRequirements.REQUIREMENTS_SET)
    }

    @Test
    @Throws(Exception::class)
    fun testMutualXcodeFromStarlarkByAlias() {
        scratch.file(
            "test_starlark/BUILD",
            """
        load("//test_starlark:r.bzl", "r")
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")
        load("@build_bazel_apple_support//xcode:available_xcodes.bzl", "available_xcodes")
        load("@build_bazel_apple_support//xcode:xcode_config_alias.bzl", "xcode_config_alias")

        xcode_config_alias(name = "a")

        xcode_config(
            name = "c",
            local_versions = ":local",
            remote_versions = ":remote",
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            version = "5.1.2",
        )

        xcode_version(
            name = "version84",
            version = "8.4",
        )

        available_xcodes(
            name = "remote",
            default = ":version512",
            versions = [
                ":version512",
                ":version84",
            ],
        )

        available_xcodes(
            name = "local",
            default = ":version84",
            versions = [":version84"],
        )

        r(name = "r")
        
        """.trimIndent()
        )
        scratch.file(
            "test_starlark/r.bzl",
            """
        MyInfo = provider()

        def _impl(ctx):
            conf = ctx.attr._xcode[apple_common.XcodeVersionConfig]
            ios = apple_common.platform.ios_simulator
            tvos = apple_common.platform.tvos_simulator
            return MyInfo(
                xcode = conf.xcode_version(),
                ios_sdk = conf.sdk_version_for_platform(ios),
                tvos_sdk = conf.sdk_version_for_platform(tvos),
                macos_min = conf.minimum_os_for_platform_type(
                    apple_common.platform_type.macos,
                ),
                watchos_min = conf.minimum_os_for_platform_type(
                    apple_common.platform_type.watchos,
                ),
                availability = conf.availability(),
                execution_info = conf.execution_info(),
            )

        r = rule(
            implementation = _impl,
            attrs = {"_xcode": attr.label(default = Label("//test_starlark:a"))},
            fragments = ["apple"],
        )
        
        """.trimIndent()
        )

        useConfiguration("--xcode_version_config=//test_starlark:c")
        val r: ConfiguredTarget = getConfiguredTarget("//test_starlark:r")
        val key: Provider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test_starlark:r.bzl")), "MyInfo"
            )
        val info: StructImpl = r.get(key) as StructImpl
        Truth.assertThat(info.getValue("execution_info") as MutableMap<*, *>?)
            .containsKey(ExecutionRequirements.REQUIRES_DARWIN)
        Truth.assertThat(info.getValue("execution_info") as MutableMap<*, *>?)
            .containsKey(ExecutionRequirements.REQUIREMENTS_SET)
    }

    @Test
    @Throws(Exception::class)
    fun testLocalXcodeFromStarlarkByAlias() {
        scratch.file(
            "test_starlark/BUILD",
            """
        load("//test_starlark:r.bzl", "r")
        load("@build_bazel_apple_support//xcode:available_xcodes.bzl", "available_xcodes")
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")
        load("@build_bazel_apple_support//xcode:xcode_config_alias.bzl", "xcode_config_alias")
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")

        xcode_config_alias(name = "a")

        xcode_config(
            name = "c",
            local_versions = ":local",
            remote_versions = ":remote",
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            version = "5.1.2",
        )

        xcode_version(
            name = "version84",
            version = "8.4",
        )

        available_xcodes(
            name = "remote",
            default = ":version512",
            versions = [":version512"],
        )

        available_xcodes(
            name = "local",
            default = ":version84",
            versions = [":version84"],
        )

        r(name = "r")
        
        """.trimIndent()
        )
        scratch.file(
            "test_starlark/r.bzl",
            """
        MyInfo = provider()

        def _impl(ctx):
            conf = ctx.attr._xcode[apple_common.XcodeVersionConfig]
            ios = apple_common.platform.ios_simulator
            tvos = apple_common.platform.tvos_simulator
            return MyInfo(
                xcode = conf.xcode_version(),
                ios_sdk = conf.sdk_version_for_platform(ios),
                tvos_sdk = conf.sdk_version_for_platform(tvos),
                macos_min = conf.minimum_os_for_platform_type(
                    apple_common.platform_type.macos,
                ),
                watchos_min = conf.minimum_os_for_platform_type(
                    apple_common.platform_type.watchos,
                ),
                availability = conf.availability(),
            )

        r = rule(
            implementation = _impl,
            attrs = {"_xcode": attr.label(default = Label("//test_starlark:a"))},
            fragments = ["apple"],
        )
        
        """.trimIndent()
        )

        useConfiguration("--xcode_version_config=//test_starlark:c")
        val r: ConfiguredTarget = getConfiguredTarget("//test_starlark:r")
        val key: Provider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test_starlark:r.bzl")), "MyInfo"
            )
        val info: StructImpl = r.get(key) as StructImpl

        assertThat(info.getValue("xcode").toString()).isEqualTo("8.4")
        assertThat(info.getValue("availability").toString()).isEqualTo("local")
    }

    @Test
    @Throws(Exception::class)
    fun testDefaultWithoutVersion() {
        scratch.file(
            "xcode/BUILD",
            """
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")

        xcode_config(
            name = "foo",
            default = ":version512",
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
                "5.1.2",
            ],
            version = "5.1.2",
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//xcode:foo")
        assertContainsEvent(
            "default label '@@//xcode:version512' must be contained in versions attribute"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testVersionDoesNotContainDefault() {
        scratch.file(
            "xcode/BUILD",
            """
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")

        xcode_config(
            name = "foo",
            default = ":version512",
            versions = [":version6"],
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
                "5.1.2",
            ],
            version = "5.1.2",
        )

        xcode_version(
            name = "version6",
            version = "6.0",
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//xcode:foo")
        assertContainsEvent("must be contained in versions attribute")
    }

    // Verifies that the --xcode_version_config configuration value can be accessed via the
    // configuration_field() Starlark method and used in a Starlark rule.
    @Test
    @Throws(Exception::class)
    fun testConfigurationFieldForRule() {
        scratch.file(
            "test_starlark/provider_grabber.bzl",
            """
        def _impl(ctx):
            conf = ctx.attr._xcode_dep[apple_common.XcodeVersionConfig]
            return [conf]

        provider_grabber = rule(
            implementation = _impl,
            attrs = {
                "_xcode_dep": attr.label(
                    default = configuration_field(
                        fragment = "apple",
                        name = "xcode_config_label",
                    ),
                ),
            },
            fragments = ["apple"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test_starlark/BUILD",
            """
        load("//test_starlark:provider_grabber.bzl", "provider_grabber")
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")

        xcode_config(
            name = "config1",
            default = ":version1",
            versions = [":version1"],
        )

        xcode_config(
            name = "config2",
            default = ":version2",
            versions = [":version2"],
        )

        xcode_version(
            name = "version1",
            version = "1.0",
        )

        xcode_version(
            name = "version2",
            version = "2.0",
        )

        provider_grabber(name = "provider_grabber")
        
        """.trimIndent()
        )

        useConfiguration("--xcode_version_config=//test_starlark:config1")
        assertXcodeVersion("1.0", "//test_starlark:provider_grabber")

        useConfiguration("--xcode_version_config=//test_starlark:config2")
        assertXcodeVersion("2.0", "//test_starlark:provider_grabber")
    }

    // Verifies that the --xcode_version_config configuration value can be accessed via the
    // configuration_field() Starlark method and used in a Starlark aspect.
    @Test
    @Throws(Exception::class)
    fun testConfigurationFieldForAspect() {
        scratch.file(
            "test_starlark/provider_grabber.bzl",
            """
        def _aspect_impl(target, ctx):
            conf = ctx.attr._xcode_dep[apple_common.XcodeVersionConfig]
            return [conf]

        MyAspect = aspect(
            implementation = _aspect_impl,
            attrs = {
                "_xcode_dep": attr.label(
                    default = configuration_field(
                        fragment = "apple",
                        name = "xcode_config_label",
                    ),
                ),
            },
            fragments = ["apple"],
        )

        def _rule_impl(ctx):
            conf = ctx.attr.dep[0][apple_common.XcodeVersionConfig]
            return [conf]

        provider_grabber = rule(
            implementation = _rule_impl,
            attrs = {"dep": attr.label_list(
                mandatory = True,
                allow_files = True,
                aspects = [MyAspect],
            )},
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test_starlark/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        load("//test_starlark:provider_grabber.bzl", "provider_grabber")
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")

        xcode_config(
            name = "config1",
            default = ":version1",
            versions = [":version1"],
        )

        xcode_config(
            name = "config2",
            default = ":version2",
            versions = [":version2"],
        )

        xcode_version(
            name = "version1",
            version = "1.0",
        )

        xcode_version(
            name = "version2",
            version = "2.0",
        )

        java_library(
            name = "fake_lib",
        )

        provider_grabber(
            name = "provider_grabber",
            dep = [":fake_lib"],
        )
        
        """.trimIndent()
        )

        useConfiguration("--xcode_version_config=//test_starlark:config1")
        assertXcodeVersion("1.0", "//test_starlark:provider_grabber")

        useConfiguration("--xcode_version_config=//test_starlark:config2")
        assertXcodeVersion("2.0", "//test_starlark:provider_grabber")
    }

    @Test
    @Throws(Exception::class)
    fun testExplicitXcodesModeNoFlag() {
        BuildFileBuilder()
            .addExplicitVersion("version512", "5.1.2", true, "5", "5.1")
            .addExplicitVersion("version64", "6.4", false, "6.0", "foo", "6")
            .write(scratch, "xcode/BUILD")
        getConfiguredTarget("//xcode:foo")
        assertXcodeVersion("5.1.2")
    }

    @Test
    @Throws(Exception::class)
    fun testExplicitXcodesModeWithFlag() {
        BuildFileBuilder()
            .addExplicitVersion("version512", "5.1.2", true, "5", "5.1")
            .addExplicitVersion("version64", "6.4", false, "6.0", "foo", "6")
            .write(scratch, "xcode/BUILD")
        useConfiguration("--xcode_version=6.4")
        getConfiguredTarget("//xcode:foo")
        assertXcodeVersion("6.4")
    }

    @Test
    @Throws(Exception::class)
    fun testAvailableXcodesModeNoFlag() {
        BuildFileBuilder()
            .addRemoteVersion("version512", "5.1.2", true, "5", "5.1")
            .addRemoteVersion("version84", "8.4", false)
            .addLocalVersion("version84", "8.4", true)
            .write(scratch, "xcode/BUILD")

        useConfiguration("--xcode_version_config=//xcode:foo")
        getConfiguredTarget("//xcode:foo")
        assertXcodeVersion("8.4")
    }

    @Test
    @Throws(Exception::class)
    fun testAvailableXcodeModesDifferentAlias() {
        BuildFileBuilder()
            .addRemoteVersion("version5", "5.1", true, "5")
            .addLocalVersion("version5.1.2", "5.1.2", true, "5")
            .write(scratch, "xcode/BUILD")
        useConfiguration("--xcode_version=5")
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//xcode:foo")
        assertContainsEvent("Xcode version 5 was selected")
        assertContainsEvent("This corresponds to local Xcode version 5.1.2")
    }

    @Test
    @Throws(Exception::class)
    fun testAvailableXcodeModesDifferentAliasFullySpecified() {
        BuildFileBuilder()
            .addRemoteVersion("version5", "5.1", true, "5")
            .addLocalVersion("version5.1.2", "5.1.2", true, "5")
            .write(scratch, "xcode/BUILD")
        useConfiguration("--xcode_version=5.1.2")
        getConfiguredTarget("//xcode:foo")
        assertXcodeVersion("5.1.2")
        assertAvailability("local")
    }

    @Test
    @Throws(Exception::class)
    fun testAvailableXcodesModeWithFlag() {
        BuildFileBuilder()
            .addRemoteVersion("version512", "5.1.2", true, "5", "5.1")
            .addRemoteVersion("version84", "8.4", false)
            .addLocalVersion("version84", "8.4", true)
            .write(scratch, "xcode/BUILD")
        useConfiguration("--xcode_version=5.1.2")
        getConfiguredTarget("//xcode:foo")
        assertXcodeVersion("5.1.2")
    }

    @Test
    @Throws(Exception::class)
    fun testXcodeWithExtensionMatchingRemote() {
        BuildFileBuilder()
            .addRemoteVersion("version0", "0.0", true, "0.0-unstable")
            .addLocalVersion("version84", "8.4", true)
            .write(scratch, "xcode/BUILD")
        useConfiguration(
            "--xcode_version=0.0-unstable", "--experimental_include_xcode_execution_requirements=true"
        )
        getConfiguredTarget("//xcode:foo")

        assertAvailability("remote")
        assertHasRequirementsWithValues(
            ImmutableMap.of<String?, String?>(
                ExecutionRequirements.REQUIRES_XCODE + ":0.0", "",
                ExecutionRequirements.REQUIRES_XCODE_LABEL + ":unstable", ""
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun testXcodeVersionWithExtensionMatchingRemoteAndLocal() {
        BuildFileBuilder()
            .addRemoteVersion("version0.x", "0.0", true, "0.0-unstable")
            .addLocalVersion("version0", "0.0", true, "0.0", "0.0.1")
            .write(scratch, "xcode/BUILD")
        useConfiguration(
            "--xcode_version=0.0-unstable", "--experimental_include_xcode_execution_requirements=true"
        )
        getConfiguredTarget("//xcode:foo")

        assertAvailability("remote")
        assertHasRequirementsWithValues(
            ImmutableMap.of<String?, String?>(
                ExecutionRequirements.REQUIRES_XCODE + ":0.0", "",
                ExecutionRequirements.REQUIRES_XCODE_LABEL + ":unstable", ""
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun testXcodeVersionWithNoExtension() {
        BuildFileBuilder()
            .addRemoteVersion("version00-remote", "0.0", true, "0.0", "0.0-beta")
            .addLocalVersion("version00", "0.0", true, "0.0")
            .write(scratch, "xcode/BUILD")
        useConfiguration(
            "--xcode_version=0.0", "--experimental_include_xcode_execution_requirements=true"
        )
        getConfiguredTarget("//xcode:foo")

        assertAvailability("both")
        assertHasRequirementsWithValues(
            ImmutableMap.of<String?, String?>(ExecutionRequirements.REQUIRES_XCODE + ":0.0", "")
        )
        assertDoesNotHaveRequirements(
            ImmutableList.of<String?>(ExecutionRequirements.REQUIRES_XCODE_LABEL + ":")
        )
    }

    @Throws(Exception::class)
    private fun getSdkVersionForPlatform(platform: ApplePlatform): DottedVersion? {
        val xcodeConfig: ConfiguredTarget = getConfiguredTarget("//xcode:foo")
        val provider: StructImpl = xcodeConfig.get(XCODE_VERSION_INFO_PROVIDER_KEY) as StructImpl
        return callProviderMethod(provider, "sdk_version_for_platform", platform) as DottedVersion?
    }

    @Throws(Exception::class)
    private fun getMinimumOsVersionForPlatform(platform: ApplePlatform): DottedVersion? {
        val xcodeConfig: ConfiguredTarget = getConfiguredTarget("//xcode:foo")
        val provider: StructImpl = xcodeConfig.get(XCODE_VERSION_INFO_PROVIDER_KEY) as StructImpl
        return callProviderMethod(provider, "minimum_os_for_platform_type", platform.type) as DottedVersion?
    }

    @Throws(Exception::class)
    private fun assertXcodeVersion(version: String?, providerTargetLabel: String? = "//xcode:foo") {
        val xcodeConfig: ConfiguredTarget = getConfiguredTarget(providerTargetLabel)
        val provider: StructImpl = xcodeConfig.get(XCODE_VERSION_INFO_PROVIDER_KEY) as StructImpl
        Truth.assertThat(callProviderMethod(provider, "xcode_version"))
            .isEqualTo(DottedVersion.fromString(version))
    }

    @Throws(Exception::class)
    private fun assertAvailability(availability: String?, providerTargetLabel: String? = "//xcode:foo") {
        val xcodeConfig: ConfiguredTarget = getConfiguredTarget(providerTargetLabel)
        val provider: StructImpl = xcodeConfig.get(XCODE_VERSION_INFO_PROVIDER_KEY) as StructImpl
        Truth.assertThat(callProviderMethod(provider, "availability")).isEqualTo(availability)
    }

    @Throws(Exception::class)
    private fun assertHasRequirements(
        executionRequirements: MutableList<String?>,
        providerTargetLabel: String? = "//xcode:foo"
    ) {
        val xcodeConfig: ConfiguredTarget = getConfiguredTarget(providerTargetLabel)
        val provider: StructImpl = xcodeConfig.get(XCODE_VERSION_INFO_PROVIDER_KEY) as StructImpl
        for (requirement in executionRequirements) {
            Truth.assertThat(requirement).isIn(getExecutionInfo(provider).keys)
        }
    }

    @Throws(Exception::class)
    private fun assertDoesNotHaveRequirements(
        executionRequirements: MutableList<String?>, providerTargetLabel: String? = "//xcode:foo"
    ) {
        val xcodeConfig: ConfiguredTarget = getConfiguredTarget(providerTargetLabel)
        val provider: StructImpl = xcodeConfig.get(XCODE_VERSION_INFO_PROVIDER_KEY) as StructImpl
        for (requirement in executionRequirements) {
            Truth.assertThat(requirement).isNotIn(getExecutionInfo(provider))
        }
    }

    @Throws(Exception::class)
    private fun assertHasRequirementsWithValues(
        executionRequirements: MutableMap<String?, String?>, providerTargetLabel: String? = "//xcode:foo"
    ) {
        val xcodeConfig: ConfiguredTarget = getConfiguredTarget(providerTargetLabel)
        val provider: StructImpl = xcodeConfig.get(XCODE_VERSION_INFO_PROVIDER_KEY) as StructImpl
        for (requirement in executionRequirements.entries) {
            val actual: Dict<String?, Any?> = getExecutionInfo(provider)
            Truth.assertThat(requirement.key).isIn(actual.keys)
            Truth.assertThat(actual.getOrDefault(requirement.key, "")).isEqualTo(requirement.value)
        }
    }

    @Throws(Exception::class)
    private fun assertIosSdkVersion(version: String?) {
        Truth.assertThat<DottedVersion?>(getSdkVersionForPlatform(ApplePlatform.IOS_SIMULATOR))
            .isEqualTo(DottedVersion.fromString(version))
    }

    @Throws(Exception::class)
    private fun callProviderMethod(provider: StructImpl, methodName: String?, vararg positional: Any?): Any? {
        return Starlark.call(
            ev.getStarlarkThread(),
            provider.getValue(methodName),
            ImmutableList.copyOf<Any?>(positional),
            ImmutableMap.of<String?, Any?>()
        )
    }

    @Throws(Exception::class)
    private fun getExecutionInfo(provider: StructImpl): Dict<String?, Any?> {
        return Dict.cast<String?, Any?>(
            callProviderMethod(provider, "execution_info"),
            String::class.java,
            Any::class.java,
            "execution_info"
        )
    }

    companion object {
        private val XCODE_VERSION_INFO_PROVIDER_KEY: Provider.Key = Key(
            keyForBuiltins(Label.parseCanonicalUnchecked("@_builtins//:common/xcode/providers.bzl")),
            "XcodeVersionInfo"
        )
    }
}
