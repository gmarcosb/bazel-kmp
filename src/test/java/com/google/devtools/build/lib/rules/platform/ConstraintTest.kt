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
package com.google.devtools.build.lib.rules.platform

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** Tests of [ConstraintSetting] and [ConstraintValue].  */
@RunWith(JUnit4::class)
class ConstraintTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun createConstraints() {
        scratch.file(
            "constraint/BUILD",
            """
        constraint_setting(name = "basic")

        constraint_value(
            name = "foo",
            constraint_setting = ":basic",
        )

        constraint_value(
            name = "bar",
            constraint_setting = ":basic",
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConstraint() {
        val setting: ConfiguredTarget = getConfiguredTarget("//constraint:basic")
        assertThat(setting).isNotNull()

        val constraintSettingInfo: ConstraintSettingInfo = PlatformProviderUtils.constraintSetting(setting)
        assertThat(constraintSettingInfo).isNotNull()
        assertThat(constraintSettingInfo).isNotNull()
        assertThat(constraintSettingInfo.label()).isEqualTo(Label.parseCanonical("//constraint:basic"))
        assertThat(constraintSettingInfo.hasDefaultConstraintValue()).isFalse()
        assertThat(constraintSettingInfo.defaultConstraintValue()).isNull()

        val fooValue: ConfiguredTarget = getConfiguredTarget("//constraint:foo")
        assertThat(fooValue).isNotNull()

        val fooConstraintValueInfo: ConstraintValueInfo = PlatformProviderUtils.constraintValue(fooValue)
        assertThat(fooConstraintValueInfo).isNotNull()
        assertThat(fooConstraintValueInfo.constraint().label())
            .isEqualTo(Label.parseCanonical("//constraint:basic"))
        assertThat(fooConstraintValueInfo.label()).isEqualTo(Label.parseCanonical("//constraint:foo"))

        val barValue: ConfiguredTarget = getConfiguredTarget("//constraint:bar")
        assertThat(barValue).isNotNull()

        val barConstraintValueInfo: ConstraintValueInfo = PlatformProviderUtils.constraintValue(barValue)
        assertThat(barConstraintValueInfo.constraint().label())
            .isEqualTo(Label.parseCanonical("//constraint:basic"))
        assertThat(barConstraintValueInfo.label()).isEqualTo(Label.parseCanonical("//constraint:bar"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConstraint_defaultValue() {
        scratch.file(
            "constraint_default/BUILD",
            """
        constraint_setting(
            name = "basic",
            default_constraint_value = ":foo",
        )

        constraint_value(
            name = "foo",
            constraint_setting = ":basic",
        )

        constraint_value(
            name = "bar",
            constraint_setting = ":basic",
        )
        
        """.trimIndent()
        )

        val setting: ConfiguredTarget = getConfiguredTarget("//constraint_default:basic")
        assertThat(setting).isNotNull()
        val constraintSettingInfo: ConstraintSettingInfo = PlatformProviderUtils.constraintSetting(setting)
        assertThat(constraintSettingInfo).isNotNull()
        assertThat(constraintSettingInfo.hasDefaultConstraintValue()).isTrue()

        val fooValue: ConfiguredTarget = getConfiguredTarget("//constraint_default:foo")
        assertThat(fooValue).isNotNull()
        val fooConstraintValueInfo: ConstraintValueInfo? = PlatformProviderUtils.constraintValue(fooValue)
        assertThat(fooConstraintValueInfo).isNotNull()

        assertThat(constraintSettingInfo.defaultConstraintValue()).isEqualTo(fooConstraintValueInfo)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConstraint_defaultValue_differentPackageFails() {
        scratch.file(
            "other/BUILD",
            """
        constraint_value(
            name = "other",
            constraint_setting = "//constraint_default:basic",
        )
        
        """.trimIndent()
        )
        checkError(
            "constraint_default",
            "basic",
            "same package",
            "constraint_setting(name = 'basic',",
            "    default_constraint_value = '//other:other',",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConstraint_defaultValue_nonExistentTargetFails() {
        checkError(
            "constraint_default",
            "basic",
            "default constraint value '//constraint_default:food' does not exist",
            "constraint_setting(name = 'basic',",
            "    default_constraint_value = ':food',",
            "    )",
            "constraint_value(name = 'foo',",
            "    constraint_setting = ':basic',",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConstraint_defaultValue_starlark() {
        setBuildLanguageOptions("--experimental_platforms_api=true")
        scratch.file(
            "constraint_default/BUILD",
            """
        constraint_setting(
            name = "basic",
            default_constraint_value = ":foo",
        )

        constraint_value(
            name = "foo",
            constraint_setting = ":basic",
        )
        
        """.trimIndent()
        )

        scratch.file(
            "verify/verify.bzl",
            """
        result = provider()

        def _impl(ctx):
            constraint_setting = ctx.attr.constraint_setting[platform_common.ConstraintSettingInfo]
            default_value = constraint_setting.default_constraint_value
            has_default_value = constraint_setting.has_default_constraint_value
            return [result(
                default_value = default_value,
                has_default_value = has_default_value,
            )]

        verify = rule(
            implementation = _impl,
            attrs = {
                "constraint_setting": attr.label(
                    providers = [platform_common.ConstraintSettingInfo],
                ),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "verify/BUILD",
            """
        load(":verify.bzl", "verify")

        verify(
            name = "verify",
            constraint_setting = "//constraint_default:basic",
        )
        
        """.trimIndent()
        )

        val myRuleTarget: ConfiguredTarget = getConfiguredTarget("//verify:verify")
        val info: StructImpl =
            myRuleTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//verify:verify.bzl")), "result"
                )
            ) as StructImpl

        val defaultConstraintValue: ConstraintValueInfo =
            info.getValue("default_value") as ConstraintValueInfo
        assertThat(defaultConstraintValue).isNotNull()
        assertThat(defaultConstraintValue.label())
            .isEqualTo(Label.parseCanonicalUnchecked("//constraint_default:foo"))
        assertThat(defaultConstraintValue.constraint().label())
            .isEqualTo(Label.parseCanonicalUnchecked("//constraint_default:basic"))

        val hasConstraintValue = info.getValue("has_default_value") as Boolean
        Truth.assertThat(hasConstraintValue).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConstraint_defaultValue_notSet_starlark() {
        setBuildLanguageOptions("--experimental_platforms_api=true")
        scratch.file("constraint_default/BUILD", "constraint_setting(name = 'basic')")

        scratch.file(
            "verify/verify.bzl",
            """
        result = provider()

        def _impl(ctx):
            constraint_setting = ctx.attr.constraint_setting[platform_common.ConstraintSettingInfo]
            default_value = constraint_setting.default_constraint_value
            has_default_value = constraint_setting.has_default_constraint_value
            return [result(
                default_value = default_value,
                has_default_value = has_default_value,
            )]

        verify = rule(
            implementation = _impl,
            attrs = {
                "constraint_setting": attr.label(
                    providers = [platform_common.ConstraintSettingInfo],
                ),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "verify/BUILD",
            """
        load(":verify.bzl", "verify")

        verify(
            name = "verify",
            constraint_setting = "//constraint_default:basic",
        )
        
        """.trimIndent()
        )

        val myRuleTarget: ConfiguredTarget = getConfiguredTarget("//verify:verify")
        val info: StructImpl =
            myRuleTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//verify:verify.bzl")), "result"
                )
            ) as StructImpl

        assertThat(info.getValue("default_value")).isEqualTo(Starlark.NONE)

        val hasConstraintValue = info.getValue("has_default_value") as Boolean
        Truth.assertThat(hasConstraintValue).isFalse()
    }
}
