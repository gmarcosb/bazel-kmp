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

import com.google.devtools.build.lib.analysis.platform.ConstraintSettingInfo

/** Tests Starlark API for [PlatformInfo] providers.  */
@RunWith(JUnit4::class)
class PlatformInfoApiTest : PlatformTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun constructor() {
        constraintBuilder("//foo:basic").addConstraintValue("value1").write()
        platformBuilder("//foo:my_platform").addConstraint("value1").write()
        assertNoEvents()

        val platformInfo: PlatformInfo? = fetchPlatformInfo("//foo:my_platform")
        assertThat(platformInfo).isNotNull()
        val constraintSetting: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//foo:basic"))
        val constraintValue: ConstraintValueInfo? =
            ConstraintValueInfo.create(
                constraintSetting, Label.parseCanonicalUnchecked("//foo:value1")
            )
        assertThat(platformInfo.constraints().get(constraintSetting)).isEqualTo(constraintValue)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tooManyParentsError() {
        val lines: MutableList<String?> =
            com.google.common.collect.ImmutableList.Builder<String?>()
                .addAll(platformBuilder("//foo:parent_platform1").lines())
                .addAll(platformBuilder("//foo:parent_platform2").lines())
                .addAll(
                    com.google.common.collect.ImmutableList.of<String?>(
                        "platform(name = 'my_platform',\n",
                        "  parents = [\n",
                        "    ':parent_platform1',\n",
                        "    ':parent_platform2',\n",
                        "  ])"
                    )
                )
                .build()

        checkError(
            "foo",
            "my_platform",
            "in parents attribute of platform rule //foo:my_platform: "
                    + "parents attribute must have a single value",
            *lines.toArray<String?>(arrayOf<String?>())
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun constraints_overlappingError() {
        val lines: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.Builder<String?>()
                .addAll(
                    constraintBuilder("//foo:basic")
                        .addConstraintValue("value1")
                        .addConstraintValue("value2")
                        .lines()
                )
                .addAll(
                    platformBuilder("//foo:my_platform")
                        .addConstraint("value1")
                        .addConstraint("value2")
                        .lines()
                )
                .build()

        checkError(
            "foo",
            "my_platform",
            "Duplicate constraint values detected: "
                    + "constraint_setting //foo:basic has [//foo:value1, //foo:value2]",
            *lines.toArray<String?>(arrayOf<String?>())
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun constraints_invalidTarget_error() {
        checkError(
            "foo",
            "my_platform",  // TODO: https://github.com/bazelbuild/bazel/issues/23126 - Have a better error message.
            // Something like "Invalid dependency :lib does not provide ConstraintValueInfo"
            "errors encountered while analyzing target",
            """
        filegroup(name = "lib")

        platform(
            name = "my_platform",
            constraint_values = [
                ":lib",
            ],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun constraints_parent() {
        constraintBuilder("//foo:setting1").addConstraintValue("value1").write()
        constraintBuilder("//foo:setting2").addConstraintValue("value2").write()
        platformBuilder("//foo:parent_platform").addConstraint("value1").write()
        platformBuilder("//foo:my_platform")
            .setParent("//foo:parent_platform")
            .addConstraint("value2")
            .write()
        assertNoEvents()

        val platformInfo: PlatformInfo? = fetchPlatformInfo("//foo:my_platform")
        assertThat(platformInfo).isNotNull()
        val constraintSetting1: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//foo:setting1"))
        val constraintValue1: ConstraintValueInfo? =
            ConstraintValueInfo.create(
                constraintSetting1, Label.parseCanonicalUnchecked("//foo:value1")
            )
        assertThat(platformInfo.constraints().get(constraintSetting1)).isEqualTo(constraintValue1)
        val constraintSetting2: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//foo:setting2"))
        val constraintValue2: ConstraintValueInfo? =
            ConstraintValueInfo.create(
                constraintSetting2, Label.parseCanonicalUnchecked("//foo:value2")
            )
        assertThat(platformInfo.constraints().get(constraintSetting2)).isEqualTo(constraintValue2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun constraints_parent_override() {
        constraintBuilder("//foo:setting1")
            .addConstraintValue("value1a")
            .addConstraintValue("value1b")
            .write()
        platformBuilder("//foo:parent_platform").addConstraint("value1a").write()
        platformBuilder("//foo:my_platform").addConstraint("value1b").write()
        assertNoEvents()

        val platformInfo: PlatformInfo? = fetchPlatformInfo("//foo:my_platform")
        assertThat(platformInfo).isNotNull()
        val constraintSetting1: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//foo:setting1"))
        val constraintValue1: ConstraintValueInfo? =
            ConstraintValueInfo.create(
                constraintSetting1, Label.parseCanonicalUnchecked("//foo:value1b")
            )
        assertThat(platformInfo.constraints().get(constraintSetting1)).isEqualTo(constraintValue1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun execProperties() {
        val props: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("k1", "v1", "k2", "v2")
        platformBuilder("//foo:my_platform").setExecProperties(props).write()
        assertNoEvents()

        val platformInfo: PlatformInfo? = fetchPlatformInfo("//foo:my_platform")
        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.execProperties()).isEqualTo(props)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun execProperties_parent() {
        val props: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("k1", "v1", "k2", "v2")
        platformBuilder("//foo:parent_platform").setExecProperties(props).write()
        platformBuilder("//foo:my_platform").setParent("//foo:parent_platform").write()
        assertNoEvents()

        val platformInfo: PlatformInfo? = fetchPlatformInfo("//foo:my_platform")
        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.execProperties()).isEqualTo(props)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun execProperties_parent_merged() {
        val propsParent: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("k1", "v1", "k2", "v2")
        val propsChild: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("k2", "child_v2", "k3", "child_v3")
        platformBuilder("//foo:parent_platform").setExecProperties(propsParent).write()
        platformBuilder("//foo:my_platform")
            .setParent("//foo:parent_platform")
            .setExecProperties(propsChild)
            .write()
        assertNoEvents()

        val platformInfo: PlatformInfo? = fetchPlatformInfo("//foo:my_platform")
        assertThat(platformInfo).isNotNull()
        val expected: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("k1", "v1", "k2", "child_v2", "k3", "child_v3")
        assertThat(platformInfo.execProperties()).isEqualTo(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flags() {
        platformBuilder("//foo:basic").addFlags("--cpu=k8", "--//starlark:flag=other").write()

        val platformInfo: PlatformInfo? = fetchPlatformInfo("//foo:basic")
        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.flags()).containsExactly("--cpu=k8", "--//starlark:flag=other")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flags_parent() {
        platformBuilder("//foo:parent").addFlags("--cpu=k8").write()
        platformBuilder("//foo:basic").setParent("//foo:parent").write()

        val platformInfo: PlatformInfo? = fetchPlatformInfo("//foo:basic")
        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.flags()).containsExactly("--cpu=k8")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flags_parent_merged() {
        platformBuilder("//foo:parent").addFlags("--cpu=k8").write()
        platformBuilder("//foo:basic")
            .setParent("//foo:parent")
            .addFlags("--//starlark:flag=other")
            .write()

        val platformInfo: PlatformInfo? = fetchPlatformInfo("//foo:basic")
        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.flags()).containsExactly("--cpu=k8", "--//starlark:flag=other")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flags_parent_override() {
        platformBuilder("//foo:parent").addFlags("--cpu=arm").write()
        platformBuilder("//foo:basic").setParent("//foo:parent").addFlags("--cpu=k8").write()

        val platformInfo: PlatformInfo? = fetchPlatformInfo("//foo:basic")
        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.flags()).containsExactly("--cpu=arm", "--cpu=k8").inOrder()
    }
}
