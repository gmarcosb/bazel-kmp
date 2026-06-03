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

import com.google.common.base.Predicates
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider
import org.junit.Test

/** Tests for the config_feature_flag rule.  */
@RunWith(JUnit4::class)
class ConfigFeatureFlagTest : BuildViewTestCase() {
    private val ev: BazelEvaluationTestCase = BazelEvaluationTestCase()

    @Throws(Exception::class)
    private fun createRuleContext(label: String?): StarlarkRuleContext {
        return StarlarkRuleContext(getRuleContextForStarlark(getConfiguredTarget(label)), null)
    }

    @Before
    @Throws(Exception::class)
    fun enforceTransitiveConfigs() {
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
    }

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder =
            Builder().addRuleDefinition(FeatureFlagSetterRule())
        TestRuleClassProvider.addStandardRules(builder)
        return builder.build()
    }

    @Test
    @Throws(Exception::class)
    fun configFeatureFlagProvider_fromTargetReturnsNullIfTargetDoesNotExportProvider() {
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "top",
            flag_values = {
            },
        )
        
        """.trimIndent()
        )
        assertThat(ConfigFeatureFlagProvider.fromTarget(getConfiguredTarget("//test:top"))).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun configFeatureFlagProvider_containsValueFromConfiguration() {
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "top",
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
        )
        
        """.trimIndent()
        )
        assertThat(
            ConfigFeatureFlagProvider.fromTarget(getConfiguredTarget("//test:top")).getFlagValue()
        )
            .isEqualTo("configured")
    }

    @Test
    @Throws(Exception::class)
    fun configFeatureFlagProvider_usesConfiguredValueOverDefault() {
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "top",
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
        assertThat(
            ConfigFeatureFlagProvider.fromTarget(getConfiguredTarget("//test:top")).getFlagValue()
        )
            .isEqualTo("configured")
    }

    @Test
    @Throws(Exception::class)
    fun configFeatureFlagProvider_starlarkConstructor() {
        scratch.file(
            "test/wrapper.bzl",
            """
        def _flag_reading_wrapper_impl(ctx):
            pass

        flag_reading_wrapper = rule(
            implementation = _flag_reading_wrapper_impl,
            attrs = {"flag": attr.label()},
        )

        def _flag_propagating_wrapper_impl(ctx):
            return [config_common.FeatureFlagInfo(value = "hello")]

        flag_propagating_wrapper = rule(
            implementation = _flag_propagating_wrapper_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(":wrapper.bzl", "flag_propagating_wrapper")

        flag_propagating_wrapper(
            name = "propagator",
        )

        config_setting(
            name = "hello_setting",
            flag_values = {":propagator": "hello"},
        )

        genrule(
            name = "gen",
            srcs = [],
            outs = ["out"],
            cmd = select({
                ":hello_setting": "hello",
                "//conditions:default": "error",
            }),
        )
        
        """.trimIndent()
        )

        val ctad: ConfiguredTargetAndData = getConfiguredTargetAndData("//test:gen")
        val attributeMapper: ConfiguredAttributeMapper = getMapperFromConfiguredTargetAndTarget(ctad)
        assertThat(attributeMapper.get("cmd", Type.STRING)).isEqualTo("hello")
    }

    @Test
    @Throws(Exception::class)
    fun configFeatureFlagProvider_valueIsAccessibleFromStarlark() {
        scratch.file(
            "test/wrapper.bzl",
            """
        def _flag_reading_wrapper_impl(ctx):
            pass

        flag_reading_wrapper = rule(
            implementation = _flag_reading_wrapper_impl,
            attrs = {"flag": attr.label()},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(":wrapper.bzl", "flag_reading_wrapper")

        feature_flag_setter(
            name = "top",
            flag_values = {
                ":flag": "configured",
            },
            transitive_configs = [":flag"],
            deps = [":wrapper"],
        )

        flag_reading_wrapper(
            name = "wrapper",
            flag = ":flag",
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
        val top: ConfiguredTarget = getConfiguredTarget("//test:top")
        val wrapper: ConfiguredTarget =
            Iterables.getOnlyElement(getPrerequisites(top, "deps")) as ConfiguredTarget
        val ctx: StarlarkRuleContext = StarlarkRuleContext(getRuleContextForStarlark(wrapper), null)
        ev.update("ruleContext", ctx)
        ev.update("config_common", ConfigStarlarkCommon())
        val value = ev.eval("ruleContext.attr.flag[config_common.FeatureFlagInfo].value") as String?
        Truth.assertThat(value).isEqualTo("configured")
    }

    @Test
    @Throws(Exception::class)
    fun configFeatureFlagProvider_validatesValuesUsingAllowedValuesAttribute() {
        scratch.file(
            "test/BUILD",
            """
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
        val provider: ConfigFeatureFlagProvider =
            ConfigFeatureFlagProvider.fromTarget(getConfiguredTarget("//test:flag"))
        assertThat(provider.isValidValue("default")).isTrue()
        assertThat(provider.isValidValue("configured")).isTrue()
        assertThat(provider.isValidValue("other")).isTrue()

        assertThat(provider.isValidValue("absent")).isFalse()
        assertThat(provider.isValidValue("conFigured")).isFalse()
        assertThat(provider.isValidValue("  other")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun configFeatureFlagProvider_valueValidationIsPossibleFromStarlark() {
        scratch.file(
            "test/wrapper.bzl",
            """
        def _flag_reading_wrapper_impl(ctx):
            pass

        flag_reading_wrapper = rule(
            implementation = _flag_reading_wrapper_impl,
            attrs = {"flag": attr.label()},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(":wrapper.bzl", "flag_reading_wrapper")

        flag_reading_wrapper(
            name = "wrapper",
            flag = ":flag",
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
        val ctx: StarlarkRuleContext = createRuleContext("//test:wrapper")
        ev.update("ruleContext", ctx)
        ev.update("config_common", ConfigStarlarkCommon())
        val provider = "ruleContext.attr.flag[config_common.FeatureFlagInfo]"
        val isDefaultValid = ev.eval(provider + ".is_valid_value('default')") as Boolean?
        val isConfiguredValid = ev.eval(provider + ".is_valid_value('configured')") as Boolean?
        val isOtherValid = ev.eval(provider + ".is_valid_value('other')") as Boolean?
        val isAbsentValid = ev.eval(provider + ".is_valid_value('absent')") as Boolean?
        val isIncorrectCapitalizationValid =
            ev.eval(provider + ".is_valid_value('conFigured')") as Boolean?
        val isIncorrectSpacingValid = ev.eval(provider + ".is_valid_value('  other')") as Boolean?

        Truth.assertThat(isDefaultValid).isTrue()
        Truth.assertThat(isConfiguredValid).isTrue()
        Truth.assertThat(isOtherValid).isTrue()

        Truth.assertThat(isAbsentValid).isFalse()
        Truth.assertThat(isIncorrectCapitalizationValid).isFalse()
        Truth.assertThat(isIncorrectSpacingValid).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun configFeatureFlagProvider_usesDefaultValueIfConfigurationDoesntSetValue() {
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "top",
            exports_flag = ":flag",
            flag_values = {
                ":other": "configured",
            },
            transitive_configs = [
                ":flag",
                ":other",
            ],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "other",
                "default",
                "configured",
            ],
            default_value = "default",
        )

        config_feature_flag(
            name = "other",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        
        """.trimIndent()
        )
        assertThat(
            ConfigFeatureFlagProvider.fromTarget(getConfiguredTarget("//test:top"))
                .getFlagValue()
        )
            .isEqualTo("default")
    }

    @Test
    @Throws(Exception::class)
    fun configFeatureFlagProvider_ignoresUnusedFlagWithNeitherDefaultNorConfiguredValueSet() {
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "top",
            exports_flag = ":flag",
            flag_values = {
                ":other": "configured",
            },
            transitive_configs = [
                ":flag",
                ":other",
            ],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "other",
                "configured",
            ],
        )

        config_feature_flag(
            name = "other",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//test:top")).isNotNull()
        assertNoEvents()
    }

    @Test
    @Throws(Exception::class)
    fun configFeatureFlagProvider_throwsErrorIfReadFlagWithNeitherDefaultNorConfiguredValueSet() {
        reporter.removeHandler(failFastHandler) // expecting an error
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "top",
            exports_flag = ":flag",
            flag_values = {
                ":other": "configured",
            },
            transitive_configs = [
                ":flag",
                ":other",
            ],
            deps = [":reader"],
        )

        filegroup(
            name = "reader",
            srcs = select({
                ":flag@configured": ["a.txt"],
                "//conditions:default": ["b.txt"],
            }),
            transitive_configs = [
                ":flag",
                ":other",
            ],
        )

        config_setting(
            name = "flag@configured",
            flag_values = {":flag": "configured"},
            transitive_configs = [":flag"],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "other",
                "configured",
            ],
        )

        config_feature_flag(
            name = "other",
            allowed_values = [
                "default",
                "configured",
                "other",
            ],
            default_value = "default",
        )
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//test:top")).isNull()
        assertContainsEvent(
            "config_setting //test:flag@configured is unresolvable because: Feature flag //test:flag"
                    + " has no default but no value was explicitly specified."
        )
    }

    @Test
    @Throws(Exception::class)
    fun allowedValuesAttribute_cannotBeEmpty() {
        reporter.removeHandler(failFastHandler) // expecting an error
        scratch.file(
            "test/BUILD",
            """
        config_feature_flag(
            name = "flag",
            allowed_values = [],
            default_value = "default",
        )
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//test:flag")).isNull()
        assertContainsEvent(
            "in allowed_values attribute of config_feature_flag rule //test:flag: "
                    + "attribute must be non empty"
        )
    }

    @Test
    @Throws(Exception::class)
    fun allowedValuesAttribute_cannotContainDuplicates() {
        reporter.removeHandler(failFastHandler) // expecting an error
        scratch.file(
            "test/BUILD",
            """
        config_feature_flag(
            name = "flag",
            allowed_values = [
                "double",
                "double",
                "toil",
                "trouble",
            ],
            default_value = "trouble",
        )
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//test:flag")).isNull()
        assertContainsEvent(
            "in allowed_values attribute of config_feature_flag rule //test:flag: "
                    + "cannot contain duplicates, but contained multiple of [\"double\"]"
        )
    }

    @Test
    @Throws(Exception::class)
    fun defaultValueAttribute_mustBeMemberOfAllowedValuesIfPresent() {
        reporter.removeHandler(failFastHandler) // expecting an error
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "top",
            exports_flag = ":flag",
            flag_values = {
                ":flag": "legal",
            },
            transitive_configs = [":flag"],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "legal",
                "eagle",
            ],
            default_value = "beagle",
        )
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//test:top")).isNull()
        assertContainsEvent(
            "in default_value attribute of config_feature_flag rule //test:flag: "
                    + "must be one of [\"eagle\", \"legal\"], but was \"beagle\""
        )
    }

    @Test
    @Throws(Exception::class)
    fun configurationValue_mustBeMemberOfAllowedValuesIfPresent() {
        reporter.removeHandler(failFastHandler) // expecting an error
        scratch.file(
            "test/BUILD",
            """
        feature_flag_setter(
            name = "top",
            exports_flag = ":flag",
            flag_values = {
                ":flag": "invalid",
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
        assertThat(getConfiguredTarget("//test:top")).isNull()
        // TODO(b/140635901): when configurationError is implemented, switch to testing for that
        assertContainsEvent(
            "in config_feature_flag rule //test:flag: "
                    + "value must be one of [\"configured\", \"default\", \"other\"], but was \"invalid\""
        )
    }

    @Test
    @Throws(Exception::class)
    fun policy_mustContainRulesPackage() {
        reporter.removeHandler(failFastHandler) // expecting an error
        scratch.overwriteFile(
            "tools/allowlists/config_feature_flag/BUILD",
            "package_group(name = 'config_feature_flag', packages = ['//some/other'])"
        )
        scratch.file(
            "test/BUILD",
            """
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
        assertThat(getConfiguredTarget("//test:flag")).isNull()
        assertContainsEvent(
            "in config_feature_flag rule //test:flag: the config_feature_flag rule is not available in "
                    + "this package"
        )
    }

    @Test
    @Throws(Exception::class)
    fun policy_doesNotBlockRuleIfInPackageGroup() {
        scratch.overwriteFile(
            "tools/allowlists/config_feature_flag/BUILD",
            "package_group(name = 'config_feature_flag', packages = ['//test'])"
        )
        scratch.file(
            "test/BUILD",
            """
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
        assertThat(getConfiguredTarget("//test:flag")).isNotNull()
        assertNoEvents()
    }

    @Test
    fun equalsTester() {
        EqualsTester()
            .addEqualityGroup( // Basic case.
                ConfigFeatureFlagProvider.create("flag1", null, Predicates.alwaysTrue<String?>())
            )
            .addEqualityGroup( // Will be distinct from the first group because CFFP instances are all distinct.
                ConfigFeatureFlagProvider.create("flag1", null, Predicates.alwaysTrue<String?>())
            )
            .addEqualityGroup( // Set the error, still distinct from the above.
                ConfigFeatureFlagProvider.create(null, "error", Predicates.alwaysTrue<String?>())
            )
            .addEqualityGroup( // Change the value, still distinct from the above.
                ConfigFeatureFlagProvider.create("flag2", null, Predicates.alwaysTrue<String?>())
            )
            .testEquals()
    }
}
