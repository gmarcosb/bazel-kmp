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

/** Tests Starlark API for [ConstraintCollection] providers.  */
@RunWith(JUnit4::class)
class ConstraintCollectionApiTest : PlatformTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConstraintSettings() {
        constraintBuilder("//foo:s1").addConstraintValue("value1").write()
        constraintBuilder("//foo:s2").addConstraintValue("value2").write()
        platformBuilder("//foo:my_platform").addConstraint("value1").addConstraint("value2").write()

        val constraintCollection: ConstraintCollection? = fetchConstraintCollection("//foo:my_platform")
        assertThat(constraintCollection).isNotNull()

        Truth.assertThat(collectLabels(constraintCollection.constraintSettings()))
            .containsExactly(
                Label.parseCanonicalUnchecked("//foo:s1"), Label.parseCanonicalUnchecked("//foo:s2")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConstraintValue() {
        constraintBuilder("//foo:s1").addConstraintValue("value1").write()
        constraintBuilder("//foo:s2").addConstraintValue("value2").write()
        constraintBuilder("//foo:unused").write()
        platformBuilder("//foo:my_platform").addConstraint("value1").addConstraint("value2").write()

        val constraintCollection: ConstraintCollection? = fetchConstraintCollection("//foo:my_platform")
        assertThat(constraintCollection).isNotNull()

        val setting: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//foo:s1"))
        assertThat(constraintCollection.has(setting)).isTrue()
        val value: ConstraintValueInfo = constraintCollection.get(setting)
        assertThat(value).isNotNull()
        assertThat(value.label()).isEqualTo(Label.parseCanonicalUnchecked("//foo:value1"))
        assertThat(
            constraintCollection.has(
                ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//foo:unused"))
            )
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testContraintValue_parent() {
        constraintBuilder("//foo:s1").addConstraintValue("value1").write()
        constraintBuilder("//foo:s2").addConstraintValue("value2").write()
        constraintBuilder("//foo:s3").addConstraintValue("value3").addConstraintValue("value4").write()
        platformBuilder("//foo:p1").addConstraint("value1").addConstraint("value4").write()
        platformBuilder("//foo:p2").setParent("//foo:p1").addConstraint("value2").write()
        platformBuilder("//foo:p3").setParent("//foo:p2").addConstraint("value3").write()

        val constraintCollection: ConstraintCollection? = fetchConstraintCollection("//foo:p3")
        assertThat(constraintCollection).isNotNull()

        var setting: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//foo:s1"))
        assertThat(constraintCollection.has(setting)).isTrue()
        var value: ConstraintValueInfo = constraintCollection.get(setting)
        assertThat(value).isNotNull()
        assertThat(value.label()).isEqualTo(Label.parseCanonicalUnchecked("//foo:value1"))

        setting = ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//foo:s2"))
        assertThat(constraintCollection.has(setting)).isTrue()
        value = constraintCollection.get(setting)
        assertThat(value).isNotNull()
        assertThat(value.label()).isEqualTo(Label.parseCanonicalUnchecked("//foo:value2"))

        setting = ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//foo:s3"))
        assertThat(constraintCollection.has(setting)).isTrue()
        value = constraintCollection.get(setting)
        assertThat(value).isNotNull()
        assertThat(value.label()).isEqualTo(Label.parseCanonicalUnchecked("//foo:value3"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConstraintValue_starlark() {
        setBuildLanguageOptions("--experimental_platforms_api=true")
        constraintBuilder("//foo:s1").addConstraintValue("value1").write()
        constraintBuilder("//foo:s2").addConstraintValue("value2").write()
        platformBuilder("//foo:my_platform").addConstraint("value1").addConstraint("value2").write()

        scratch.file(
            "verify/verify.bzl",
            """
        result = provider()

        def _impl(ctx):
            platform = ctx.attr.platform[platform_common.PlatformInfo]
            constraint_setting = ctx.attr.constraint_setting[platform_common.ConstraintSettingInfo]
            constraint_collection = platform.constraints
            value_from_index = constraint_collection[constraint_setting]
            value_from_get = constraint_collection.get(constraint_setting)
            used_constraints = constraint_collection.constraint_settings
            has_constraint = constraint_collection.has(constraint_setting)
            has_constraint_value = constraint_collection.has_constraint_value(value_from_get)
            return [result(
                value_from_index = value_from_index,
                value_from_get = value_from_get,
                used_constraints = used_constraints,
                has_constraint = has_constraint,
                has_constraint_value = has_constraint_value,
            )]

        verify = rule(
            implementation = _impl,
            attrs = {
                "platform": attr.label(providers = [platform_common.PlatformInfo]),
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
            constraint_setting = "//foo:s1",
            platform = "//foo:my_platform",
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

        val constraintValueFromIndex: ConstraintValueInfo =
            info.getValue("value_from_index") as ConstraintValueInfo
        assertThat(constraintValueFromIndex).isNotNull()
        assertThat(constraintValueFromIndex.label())
            .isEqualTo(Label.parseCanonicalUnchecked("//foo:value1"))

        val constraintValueFromGet: ConstraintValueInfo =
            info.getValue("value_from_get") as ConstraintValueInfo
        assertThat(constraintValueFromGet).isNotNull()
        assertThat(constraintValueFromGet.label())
            .isEqualTo(Label.parseCanonicalUnchecked("//foo:value1"))

        val usedConstraints: net.starlark.java.eval.Sequence<ConstraintSettingInfo?>? =
            info.getValue("used_constraints") as net.starlark.java.eval.Sequence<ConstraintSettingInfo?>?
        Truth.assertThat(usedConstraints).isNotNull()
        Truth.assertThat(usedConstraints)
            .containsExactly(
                ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//foo:s1")),
                ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//foo:s2"))
            )

        val hasConstraint = info.getValue("has_constraint") as Boolean
        Truth.assertThat(hasConstraint).isTrue()

        val hasConstraintValue = info.getValue("has_constraint_value") as Boolean
        Truth.assertThat(hasConstraintValue).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGet_defaultConstraintValues() {
        constraintBuilder("//constraint/default:basic")
            .defaultConstraintValue("foo")
            .addConstraintValue("bar")
            .write()
        constraintBuilder("//constraint/default:other").write()

        platformBuilder("//constraint/default:plat_with_default").write()
        platformBuilder("//constraint/default:plat_without_default").addConstraint("bar").write()

        val basicConstraintSetting: ConstraintSettingInfo? =
            fetchConstraintSettingInfo("//constraint/default:basic")
        val otherConstraintSetting: ConstraintSettingInfo? =
            fetchConstraintSettingInfo("//constraint/default:other")

        val constraintCollectionWithDefault: ConstraintCollection? =
            fetchConstraintCollection("//constraint/default:plat_with_default")
        assertThat(constraintCollectionWithDefault).isNotNull()
        assertThat(constraintCollectionWithDefault.has(basicConstraintSetting)).isTrue()
        assertThat(constraintCollectionWithDefault.get(basicConstraintSetting)).isNotNull()
        assertThat(constraintCollectionWithDefault.get(basicConstraintSetting).label())
            .isEqualTo(Label.parseCanonicalUnchecked("//constraint/default:foo"))
        assertThat(constraintCollectionWithDefault.has(otherConstraintSetting)).isFalse()
        assertThat(constraintCollectionWithDefault.get(otherConstraintSetting)).isNull()

        val constraintCollectionWithoutDefault: ConstraintCollection? =
            fetchConstraintCollection("//constraint/default:plat_without_default")
        assertThat(constraintCollectionWithoutDefault).isNotNull()
        assertThat(constraintCollectionWithDefault.has(basicConstraintSetting)).isTrue()
        assertThat(constraintCollectionWithoutDefault.get(basicConstraintSetting)).isNotNull()
        assertThat(constraintCollectionWithoutDefault.get(basicConstraintSetting).label())
            .isEqualTo(Label.parseCanonicalUnchecked("//constraint/default:bar"))
    }

    private fun collectLabels(settings: MutableCollection<out ConstraintSettingInfo?>): MutableSet<Label?> {
        return settings.stream().map<Any?>(ConstraintSettingInfo::label).collect(Collectors.toSet())
    }

    @Throws(java.lang.Exception::class)
    private fun fetchConstraintCollection(platformLabel: String?): ConstraintCollection? {
        val platformInfo: PlatformInfo? = fetchPlatformInfo(platformLabel)
        if (platformInfo == null) {
            return null
        }
        return platformInfo.constraints()
    }
}
