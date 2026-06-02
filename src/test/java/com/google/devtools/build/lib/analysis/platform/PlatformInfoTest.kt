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
package com.google.devtools.build.lib.analysis.platform

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableMultimap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable

/** Tests of [PlatformInfo].  */
@RunWith(JUnit4::class)
class PlatformInfoTest : BuildViewTestCase() {
    @Test
    @Throws(Exception::class)
    fun builder() {
        val setting1: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:s1"))
        val setting2: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:s2"))

        val builder: PlatformInfo.Builder = PlatformInfo.builder()
        builder.addConstraint(
            ConstraintValueInfo.create(setting1, Label.parseCanonicalUnchecked("//constraint:v1"))
        )
        builder.addConstraint(
            ConstraintValueInfo.create(setting2, Label.parseCanonicalUnchecked("//constraint:v2"))
        )
        val platformInfo: PlatformInfo = builder.build()

        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.constraints().has(setting1)).isTrue()
        assertThat(platformInfo.constraints().get(setting1).label())
            .isEqualTo(Label.parseCanonicalUnchecked("//constraint:v1"))
        assertThat(platformInfo.constraints().has(setting2)).isTrue()
        assertThat(platformInfo.constraints().get(setting2).label())
            .isEqualTo(Label.parseCanonicalUnchecked("//constraint:v2"))
    }

    @Test
    @Throws(Exception::class)
    fun constraints_parentPlatform_noOverlaps() {
        val setting1: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:s1"))
        val setting2: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:s2"))
        val setting3: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:s3"))

        val parent: PlatformInfo? =
            PlatformInfo.builder()
                .addConstraint(
                    ConstraintValueInfo.create(
                        setting1, Label.parseCanonicalUnchecked("//constraint:v1")
                    )
                )
                .build()

        val builder: PlatformInfo.Builder = PlatformInfo.builder()
        builder.setParent(parent)
        builder.addConstraint(
            ConstraintValueInfo.create(setting2, Label.parseCanonicalUnchecked("//constraint:v2"))
        )
        builder.addConstraint(
            ConstraintValueInfo.create(setting3, Label.parseCanonicalUnchecked("//constraint:v3"))
        )
        val platformInfo: PlatformInfo = builder.build()

        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.constraints().has(setting1)).isTrue()
        assertThat(platformInfo.constraints().get(setting1).label())
            .isEqualTo(Label.parseCanonicalUnchecked("//constraint:v1"))
        assertThat(platformInfo.constraints().has(setting2)).isTrue()
        assertThat(platformInfo.constraints().get(setting2).label())
            .isEqualTo(Label.parseCanonicalUnchecked("//constraint:v2"))
        assertThat(platformInfo.constraints().has(setting3)).isTrue()
        assertThat(platformInfo.constraints().get(setting3).label())
            .isEqualTo(Label.parseCanonicalUnchecked("//constraint:v3"))
    }

    @Test
    @Throws(Exception::class)
    fun constraints_parentPlatform_overlaps() {
        val setting1: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:s1"))
        val setting2: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:s2"))
        val setting3: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:s3"))

        val parent: PlatformInfo? =
            PlatformInfo.builder()
                .addConstraint(
                    ConstraintValueInfo.create(
                        setting1, Label.parseCanonicalUnchecked("//constraint:v1")
                    )
                )
                .build()

        val builder: PlatformInfo.Builder = PlatformInfo.builder()
        builder.setParent(parent)
        builder.addConstraint(
            ConstraintValueInfo.create(setting1, Label.parseCanonicalUnchecked("//constraint:v1a"))
        )
        builder.addConstraint(
            ConstraintValueInfo.create(setting2, Label.parseCanonicalUnchecked("//constraint:v2"))
        )
        builder.addConstraint(
            ConstraintValueInfo.create(setting3, Label.parseCanonicalUnchecked("//constraint:v3"))
        )
        val platformInfo: PlatformInfo = builder.build()

        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.constraints().get(setting1).label())
            .isEqualTo(Label.parseCanonicalUnchecked("//constraint:v1a"))
        assertThat(platformInfo.constraints().get(setting2).label())
            .isEqualTo(Label.parseCanonicalUnchecked("//constraint:v2"))
        assertThat(platformInfo.constraints().get(setting3).label())
            .isEqualTo(Label.parseCanonicalUnchecked("//constraint:v3"))
    }

    @Test
    @Throws(Exception::class)
    fun constraints_overlappingError() {
        val setting1: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:basic"))
        val setting2: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:complex"))
        val setting3: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:single"))

        val builder: PlatformInfo.Builder = PlatformInfo.builder()

        builder.addConstraint(
            ConstraintValueInfo.create(setting1, Label.parseCanonicalUnchecked("//constraint:value1"))
        )
        builder.addConstraint(
            ConstraintValueInfo.create(setting1, Label.parseCanonicalUnchecked("//constraint:value2"))
        )

        builder.addConstraint(
            ConstraintValueInfo.create(setting2, Label.parseCanonicalUnchecked("//constraint:value3"))
        )
        builder.addConstraint(
            ConstraintValueInfo.create(setting2, Label.parseCanonicalUnchecked("//constraint:value4"))
        )
        builder.addConstraint(
            ConstraintValueInfo.create(setting2, Label.parseCanonicalUnchecked("//constraint:value5"))
        )

        builder.addConstraint(
            ConstraintValueInfo.create(setting3, Label.parseCanonicalUnchecked("//constraint:value6"))
        )

        val exception: ConstraintCollection.DuplicateConstraintException? =
            Assert.assertThrows<T?>(
                ConstraintCollection.DuplicateConstraintException::class.java, ThrowingRunnable { builder.build() })
        assertThat(exception)
            .hasMessageThat()
            .contains(
                ("Duplicate constraint values detected: "
                        + "constraint_setting //constraint:basic has "
                        + "[//constraint:value1, //constraint:value2], "
                        + "constraint_setting //constraint:complex has "
                        + "[//constraint:value3, //constraint:value4, //constraint:value5]")
            )
    }

    @Test
    @Throws(Exception::class)
    fun execProperties_empty() {
        val builder: PlatformInfo.Builder = PlatformInfo.builder()
        builder.setExecProperties(ImmutableMap.of<K?, V?>())
        val platformInfo: PlatformInfo = builder.build()

        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.execProperties()).isNotNull()
        assertThat(platformInfo.execProperties()).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun execProperties_one() {
        val builder: PlatformInfo.Builder = PlatformInfo.builder()
        builder.setExecProperties(ImmutableMap.of<K?, V?>("elem1", "value1"))
        val platformInfo: PlatformInfo = builder.build()

        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.execProperties()).isNotNull()
        assertThat(platformInfo.execProperties()).containsExactly("elem1", "value1")
    }

    @Test
    @Throws(Exception::class)
    fun execProperties_parentPlatform_keep() {
        val parent: PlatformInfo? =
            PlatformInfo.builder().setExecProperties(ImmutableMap.of<K?, V?>("parent", "properties")).build()

        val builder: PlatformInfo.Builder = PlatformInfo.builder()
        builder.setParent(parent)
        val platformInfo: PlatformInfo = builder.build()

        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.execProperties()).containsExactly("parent", "properties")
    }

    @Test
    @Throws(Exception::class)
    fun execProperties_parentPlatform_inheritance() {
        val parent: PlatformInfo? =
            PlatformInfo.builder()
                .setExecProperties(
                    ImmutableMap.of<K?, V?>("p1", "keep", "p2", "delete", "p3", "parent", "p4", "del2")
                )
                .build()

        val builder: PlatformInfo.Builder = PlatformInfo.builder()
        builder.setParent(parent)
        val platformInfo: PlatformInfo =
            builder.setExecProperties(ImmutableMap.of<K?, V?>("p2", "", "p3", "child", "p4", "")).build()

        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.execProperties()).containsExactly("p1", "keep", "p3", "child")
    }

    @Test
    @Throws(Exception::class)
    fun flags_empty() {
        val builder: PlatformInfo.Builder = PlatformInfo.builder()
        // Don't add any flags
        val platformInfo: PlatformInfo = builder.build()

        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.flags()).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun flags() {
        val builder: PlatformInfo.Builder = PlatformInfo.builder()
        builder.addFlags(ImmutableList.of<E?>("--cpu=k8", "--//starlark:flag=other"))
        val platformInfo: PlatformInfo = builder.build()

        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.flags()).containsExactly("--cpu=k8", "--//starlark:flag=other")
    }

    @Test
    @Throws(Exception::class)
    fun flags_parentPlatform_keep() {
        val parent: PlatformInfo? = PlatformInfo.builder().addFlags(ImmutableList.of<E?>("--cpu=k8")).build()
        val builder: PlatformInfo.Builder = PlatformInfo.builder()
        builder.setParent(parent)
        builder.addFlags(ImmutableList.of<E?>("--//starlark:flag=other"))
        val platformInfo: PlatformInfo = builder.build()

        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.flags())
            .containsExactly("--cpu=k8", "--//starlark:flag=other")
            .inOrder()
    }

    @Test
    @Throws(Exception::class)
    fun flags_parentPlatform_inheritance() {
        val parent: PlatformInfo? = PlatformInfo.builder().addFlags(ImmutableList.of<E?>("--cpu=arm")).build()
        val builder: PlatformInfo.Builder = PlatformInfo.builder()
        builder.setParent(parent)
        builder.addFlags(ImmutableList.of<E?>("--cpu=k8"))
        val platformInfo: PlatformInfo = builder.build()

        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.flags()).containsExactly("--cpu=arm", "--cpu=k8").inOrder()
    }

    @Test
    @Throws(Exception::class)
    fun requiredSettings_empty() {
        val builder: PlatformInfo.Builder = PlatformInfo.builder()
        // Don't add any settings
        val platformInfo: PlatformInfo = builder.build()

        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.requiredSettings()).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun requiredSettings() {
        val builder: PlatformInfo.Builder = PlatformInfo.builder()
        builder.addRequiredSettings(
            ImmutableList.of<E?>(
                configMatchingProvider("//setting:first", true),
                configMatchingProvider("//setting:second", false)
            )
        )
        val platformInfo: PlatformInfo = builder.build()

        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.requiredSettings()).hasSize(2)
        assertThat(
            platformInfo.requiredSettings().stream()
                .map(ConfigMatchingProvider::label)
                .collect(ImmutableSet.toImmutableSet<E?>())
        )
            .containsExactly(
                Label.parseCanonicalUnchecked("//setting:first"),
                Label.parseCanonicalUnchecked("//setting:second")
            )
    }

    @Test
    @Throws(Exception::class)
    fun requiredSettings_parentPlatform_areIgnored() {
        val parent: PlatformInfo? =
            PlatformInfo.builder()
                .addRequiredSettings(
                    ImmutableList.of<E?>(
                        configMatchingProvider("//setting:first", true),
                        configMatchingProvider("//setting:second", false)
                    )
                )
                .build()
        val builder: PlatformInfo.Builder = PlatformInfo.builder()
        builder.setParent(parent)
        val platformInfo: PlatformInfo = builder.build()

        assertThat(platformInfo).isNotNull()
        assertThat(platformInfo.requiredSettings()).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun equalsTester() {
        val setting1: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:basic"))
        val setting2: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:other"))

        val value1: ConstraintValueInfo? =
            ConstraintValueInfo.create(setting1, Label.parseCanonicalUnchecked("//constraint:value1"))
        val value2: ConstraintValueInfo? =
            ConstraintValueInfo.create(setting2, Label.parseCanonicalUnchecked("//constraint:value2"))
        val value3: ConstraintValueInfo? =
            ConstraintValueInfo.create(setting2, Label.parseCanonicalUnchecked("//constraint:value3"))

        EqualsTester()
            .addEqualityGroup( // Base case.
                PlatformInfo.builder()
                    .setLabel(Label.parseCanonicalUnchecked("//platform/plat1"))
                    .addConstraint(value1)
                    .addConstraint(value2)
                    .build(),
                PlatformInfo.builder()
                    .setLabel(Label.parseCanonicalUnchecked("//platform/plat1"))
                    .addConstraint(value1)
                    .addConstraint(value2)
                    .build()
            )
            .addEqualityGroup( // Different label.
                PlatformInfo.builder()
                    .setLabel(Label.parseCanonicalUnchecked("//platform/plat2"))
                    .addConstraint(value1)
                    .addConstraint(value2)
                    .build()
            )
            .addEqualityGroup( // Extra constraint.
                PlatformInfo.builder()
                    .setLabel(Label.parseCanonicalUnchecked("//platform/plat1"))
                    .addConstraint(value1)
                    .addConstraint(value3)
                    .build()
            )
            .addEqualityGroup( // Missing constraint.
                PlatformInfo.builder()
                    .setLabel(Label.parseCanonicalUnchecked("//platform/plat1"))
                    .addConstraint(value1)
                    .build()
            )
            .addEqualityGroup( // Different exec properties.
                PlatformInfo.builder()
                    .setLabel(Label.parseCanonicalUnchecked("//platform/plat1"))
                    .addConstraint(value1)
                    .addConstraint(value2)
                    .setExecProperties(ImmutableMap.of<K?, V?>("key", "value"))
                    .build()
            )
            .addEqualityGroup( // Different no toolchain error message.
                PlatformInfo.builder()
                    .setLabel(Label.parseCanonicalUnchecked("//platform/plat1"))
                    .addConstraint(value1)
                    .addConstraint(value2)
                    .setMissingToolchainErrorMessage("Check docs for plat1 at http://example.com/plat1")
                    .build()
            )
            .testEquals()
    }

    companion object {
        private fun configMatchingProvider(label: String?, match: Boolean): ConfigMatchingProvider {
            return ConfigMatchingProvider.create(
                Label.parseCanonicalUnchecked(label),
                ImmutableMultimap.of<K?, V?>(),
                ImmutableMap.of<K?, V?>(),
                ImmutableSet.of<E?>(),
                if (match)
                    MatchResult.MATCH
                else
                    NoMatch(
                        ImmutableList.of<E?>(
                            MatchResult.NoMatch.Diff.what(Label.parseCanonicalUnchecked("//fake"))
                                .want("foo")
                                .got("bar")
                                .build()
                        )
                    )
            )
        }
    }
}
