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
// limitations under the License
package com.google.devtools.build.lib.rules.config

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider
import org.junit.Test

/** Tests for the config_feature_flag rule.  */
@RunWith(JUnit4::class)
class ConfigFeatureFlagNamingTest : BuildViewTestCase() {
    private fun getMnemonic(target: ConfiguredTarget): String {
        return getConfiguration(target).getMnemonic()
    }

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder =
            Builder().addRuleDefinition(FeatureFlagSetterRule())
        TestRuleClassProvider.addStandardRules(builder)
        return builder.build()
    }

    @Test
    @Throws(Exception::class)
    fun featureFlagSetter_sameSettingYieldsSameMnemonic_legacy() {
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "top_a",
            exports_flag = ":flag",
            flag_values = {
                ":flag": "configured",
            },
            transitive_configs = [":flag"],
        )

        feature_flag_setter(
            name = "top_b",
            exports_flag = ":flag",
            flag_values = {
                ":flag": "configured",
            },
            transitive_configs = [":flag"],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        
        """.trimIndent()
        )
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        val aMnemonic = getMnemonic(getConfiguredTarget("//test:top_a"))
        val bMnemonic = getMnemonic(getConfiguredTarget("//test:top_b"))
        Truth.assertThat(aMnemonic).isEqualTo(bMnemonic)
    }

    @Test
    @Throws(Exception::class)
    fun featureFlagSetter_diffSettingYieldsDiffMnemonic_legacy() {
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "top_a",
            exports_flag = ":flag",
            flag_values = {
                ":flag": "configured",
            },
            transitive_configs = [":flag"],
        )

        feature_flag_setter(
            name = "top_b",
            exports_flag = ":flag",
            flag_values = {
                ":flag": "other",
            },
            transitive_configs = [":flag"],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        
        """.trimIndent()
        )
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        val aMnemonic = getMnemonic(getConfiguredTarget("//test:top_a"))
        val bMnemonic = getMnemonic(getConfiguredTarget("//test:top_b"))
        Truth.assertThat(aMnemonic).isNotEqualTo(bMnemonic)
    }

    @Test
    @Throws(Exception::class)
    fun featureFlagSetter_sameSettingYieldsSameMnemonic_diff() {
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "top_a",
            exports_flag = ":flag",
            flag_values = {
                ":flag": "configured",
            },
            transitive_configs = [":flag"],
        )

        feature_flag_setter(
            name = "top_b",
            exports_flag = ":flag",
            flag_values = {
                ":flag": "configured",
            },
            transitive_configs = [":flag"],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        
        """.trimIndent()
        )
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        val aMnemonic = getMnemonic(getConfiguredTarget("//test:top_a"))
        val bMnemonic = getMnemonic(getConfiguredTarget("//test:top_b"))
        Truth.assertThat(aMnemonic).isEqualTo(bMnemonic)
    }

    @Test
    @Throws(Exception::class)
    fun featureFlagSetter_diffSettingYieldsDiffMnemonic_diff() {
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "top_a",
            exports_flag = ":flag",
            flag_values = {
                ":flag": "configured",
            },
            transitive_configs = [":flag"],
        )

        feature_flag_setter(
            name = "top_b",
            exports_flag = ":flag",
            flag_values = {
                ":flag": "other",
            },
            transitive_configs = [":flag"],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        
        """.trimIndent()
        )
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        val aMnemonic = getMnemonic(getConfiguredTarget("//test:top_a"))
        val bMnemonic = getMnemonic(getConfiguredTarget("//test:top_b"))
        Truth.assertThat(aMnemonic).isNotEqualTo(bMnemonic)
    }

    @Test
    @Throws(Exception::class)
    fun untrimmedFlag_doesNothing_legacy() {
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "via_setter",
            exports_flag = ":flag",
            flag_values = {
                ":flag": "configured",
            },
            transitive_configs = [":flag"],
            deps = [":via_consumer"],
        )

        genrule(
            name = "via_consumer",
            outs = ["out"],
            cmd = "touch ${'$'}@",
            transitive_configs = [":flag"],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        
        """.trimIndent()
        )
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        val viaSetter: ConfiguredTarget = getConfiguredTarget("//test:via_setter")
        val viaConsumer: ConfiguredTarget = getDirectPrerequisite(viaSetter, "//test:via_consumer")
        Truth.assertThat(getMnemonic(viaSetter)).isEqualTo(getMnemonic(viaConsumer))
    }

    @Test
    @Throws(Exception::class)
    fun trimmedFlag_causesDiff_legacy() {
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "via_setter",
            exports_flag = ":flag",
            flag_values = {
                ":flag": "configured",
            },
            transitive_configs = [":flag"],
            deps = [":via_consumer"],
        )

        genrule(
            name = "via_consumer",
            outs = ["out"],
            cmd = "touch ${'$'}@",
            transitive_configs = [],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        
        """.trimIndent()
        )
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        val viaSetter: ConfiguredTarget = getConfiguredTarget("//test:via_setter")
        val viaConsumer: ConfiguredTarget = getDirectPrerequisite(viaSetter, "//test:via_consumer")
        Truth.assertThat(getMnemonic(viaSetter)).isNotEqualTo(getMnemonic(viaConsumer))
    }

    @Test
    @Throws(Exception::class)
    fun untrimmedFlag_doesNothing_diff() {
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "via_setter",
            exports_flag = ":flag",
            flag_values = {
                ":flag": "configured",
            },
            transitive_configs = [":flag"],
            deps = [":via_consumer"],
        )

        genrule(
            name = "via_consumer",
            outs = ["out"],
            cmd = "touch ${'$'}@",
            transitive_configs = [":flag"],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        
        """.trimIndent()
        )
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        val viaSetter: ConfiguredTarget = getConfiguredTarget("//test:via_setter")
        val viaConsumer: ConfiguredTarget = getDirectPrerequisite(viaSetter, "//test:via_consumer")
        Truth.assertThat(getMnemonic(viaSetter)).isEqualTo(getMnemonic(viaConsumer))
    }

    @Test
    @Throws(Exception::class)
    fun trimmedFlag_causesDiff_diff() {
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "via_setter",
            exports_flag = ":flag",
            flag_values = {
                ":flag": "configured",
            },
            transitive_configs = [":flag"],
            deps = [":via_consumer"],
        )

        genrule(
            name = "via_consumer",
            outs = ["out"],
            cmd = "touch ${'$'}@",
            transitive_configs = [],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        
        """.trimIndent()
        )
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        val viaSetter: ConfiguredTarget = getConfiguredTarget("//test:via_setter")
        val viaConsumer: ConfiguredTarget = getDirectPrerequisite(viaSetter, "//test:via_consumer")
        Truth.assertThat(getMnemonic(viaSetter)).isNotEqualTo(getMnemonic(viaConsumer))
    }
}
