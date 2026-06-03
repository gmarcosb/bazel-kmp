// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild
import net.starlark.java.eval.Sequence
import org.junit.Test

/** Tests for the action properties on rule configured targets of Apple related rules.  */
@RunWith(JUnit4::class)
class AppleRulesTest : AnalysisTestCase() {
    @Before
    @Throws(Exception::class)
    fun setup() {
        MockObjcSupport.setup(mockToolsConfig)
        scratch.file(
            "test/aspect.bzl",
            """
        foo = provider()

        def _impl(target, ctx):
            return [foo(actions = target.actions)]

        MyAspect = aspect(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "xcode/BUILD",
            """
        load("@build_bazel_apple_support//xcode:available_xcodes.bzl", "available_xcodes")
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")

        xcode_version(
            name = "version10_1_0",
            aliases = [
                "10.1",
                "10.1.0",
            ],
            default_ios_sdk_version = "12.1",
            default_macos_sdk_version = "10.14",
            default_tvos_sdk_version = "12.1",
            default_watchos_sdk_version = "5.1",
            version = "10.1.0",
        )

        xcode_version(
            name = "version10_2_1",
            aliases = [
                "10.2.1",
                "10.2",
            ],
            default_ios_sdk_version = "12.2",
            default_macos_sdk_version = "10.14",
            default_tvos_sdk_version = "12.2",
            default_watchos_sdk_version = "5.2",
            version = "10.2.1",
        )

        available_xcodes(
            name = "xcodes_a",
            default = ":version10_1_0",
            versions = [":version10_1_0"],
        )

        available_xcodes(
            name = "xcodes_b",
            default = ":version10_2_1",
            versions = [":version10_2_1"],
        )

        xcode_config(
            name = "local",
            local_versions = ":xcodes_a",
            remote_versions = ":xcodes_b",
        )

        xcode_config(
            name = "mutual",
            local_versions = ":xcodes_b",
            remote_versions = ":xcodes_b",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "xxx",
            srcs = ["dep1.cc"],
            hdrs = ["dep1.h"],
            defines = ["DEP1"],
            includes = ["dep1/baz"],
        )
        
        """.trimIndent()
        )
    }

    @Test
    @Throws(Exception::class)
    fun executionRequirementsSetCcLibrary() {
        val flags: ImmutableList<String?> =
            ImmutableList.builder<String?>()
                .addAll(MockObjcSupport.requiredObjcCrosstoolFlagsNoXcodeConfig())
                .add("--xcode_version_config=//xcode:local")
                .build()
        useConfiguration(*flags.toArray<String?>(arrayOfNulls<String>(1)))
        val analysisResult: AnalysisResult =
            update(ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")

        val configuredAspect: ConfiguredAspect? =
            Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())

        val fooKey: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "foo")

        val fooProvider: StructImpl = configuredAspect.get(fooKey) as StructImpl
        assertThat(fooProvider.getValue("actions")).isNotNull()
        val actions: Sequence<ActionAnalysisMetadata>? =
            fooProvider.getValue("actions") as Sequence<ActionAnalysisMetadata>?
        Truth.assertThat(actions).isNotEmpty()

        for (action in actions!!) {
            assertThat(action).isInstanceOf(AbstractAction::class.java)
            if (action.getExecutionInfo().containsKey("requires-darwin")) {
                assertThat(action.getExecutionInfo()).containsKey("supports-xcode-requirements-set")
                assertThat(action.getExecutionInfo()).containsKey("no-remote")
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun dottedVersionOptionIsReadableFromStarlarkTransition() {
        // Test that DottedVersion.Option is readable from a Starlark transition, since it is a distinct
        // type from DottedVersion (see the documentation comment on DottedVersion.Option for the
        // rationale).
        scratch.overwriteFile(
            "tools/allowlists/function_transition_allowlist/BUILD",
            """
        package_group(
            name = "function_transition_allowlist",
            packages = ["//..."],
        )

        filegroup(
            name = "srcs",
            srcs = glob(["**"]),
            visibility = ["//tools/allowlists:__pkg__"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "transition/transition.bzl",
            """
        def _silly_transition_impl(settings, attr):
            version = str(settings["//command_line_option:ios_minimum_os"])
            next = version if version.endswith(".1") else version + ".1"
            return {"//command_line_option:ios_minimum_os": next}

        silly_transition = transition(
            implementation = _silly_transition_impl,
            inputs = ["//command_line_option:ios_minimum_os"],
            outputs = ["//command_line_option:ios_minimum_os"],
        )

        def _my_rule_impl(ctx):
            return []

        my_rule = rule(
            cfg = silly_transition,
            implementation = _my_rule_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "transition/BUILD",
            """
        load("//transition:transition.bzl", "my_rule")

        my_rule(name = "xxx")
        
        """.trimIndent()
        )

        useConfiguration("--ios_minimum_os=10.0")
        val result: AnalysisResult = update("//transition:xxx")
        val configuration: BuildConfigurationValue =
            Iterables.getOnlyElement<T?>(result.getTopLevelTargetsWithConfigs()).getConfiguration()
        val appleOptions: AppleCommandLineOptions =
            configuration.getOptions().get(AppleCommandLineOptions::class.java)
        assertThat(appleOptions.iosMinimumOs).isNotNull()
        val version: DottedVersion = DottedVersion.maybeUnwrap(appleOptions.iosMinimumOs)!!
        Truth.assertThat(version.toString()).isEqualTo("10.0.1")
    }
}
