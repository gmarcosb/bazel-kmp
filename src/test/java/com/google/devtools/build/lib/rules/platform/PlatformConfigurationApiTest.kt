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
package com.google.devtools.build.lib.rules.platform

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** Tests Starlark API for Platform configuration fragments.  */
@RunWith(JUnit4::class)
class PlatformConfigurationApiTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHostPlatform() {
        scratch.file("platforms/BUILD", "platform(name = 'test_platform')")

        scratch.file(
            "verify/verify.bzl",
            """
        result = provider()

        def _impl(ctx):
            platformConfig = ctx.fragments.platform
            host_platform = platformConfig.host_platform
            return [result(
                host_platform = host_platform,
            )]

        verify = rule(
            implementation = _impl,
            fragments = ["platform"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "verify/BUILD",
            """
        load(":verify.bzl", "verify")

        verify(name = "verify")
        
        """.trimIndent()
        )

        useConfiguration("--host_platform=//platforms:test_platform")

        val myRuleTarget: ConfiguredTarget = getConfiguredTarget("//verify:verify")
        val info: StructImpl =
            myRuleTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//verify:verify.bzl")), "result"
                )
            ) as StructImpl

        val hostPlatform: Label? = info.getValue("host_platform") as Label?
        assertThat(hostPlatform).isEqualTo(Label.parseCanonicalUnchecked("//platforms:test_platform"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetPlatform_single() {
        scratch.file("platforms/BUILD", "platform(name = 'test_platform')")

        scratch.file(
            "verify/verify.bzl",
            """
        result = provider()

        def _impl(ctx):
            platformConfig = ctx.fragments.platform
            target_platform = platformConfig.platform
            return [result(
                target_platform = target_platform,
            )]

        verify = rule(
            implementation = _impl,
            fragments = ["platform"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "verify/BUILD",
            """
        load(":verify.bzl", "verify")

        verify(name = "verify")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:test_platform")

        val myRuleTarget: ConfiguredTarget = getConfiguredTarget("//verify:verify")
        val info: StructImpl =
            myRuleTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//verify:verify.bzl")), "result"
                )
            ) as StructImpl

        val targetPlatform: Label? = info.getValue("target_platform") as Label?
        assertThat(targetPlatform)
            .isEqualTo(Label.parseCanonicalUnchecked("//platforms:test_platform"))
    }
}
