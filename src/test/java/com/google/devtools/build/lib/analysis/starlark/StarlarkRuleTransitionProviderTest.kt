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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.analysis.AnalysisRootCauseEvent

/** Tests for StarlarkRuleTransitionProvider.  */
@RunWith(TestParameterInjector::class)
class StarlarkRuleTransitionProviderTest : BuildViewTestCase() {
    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        builder.addConfigurationFragment(DummyTestFragment::class.java)
        return builder.build()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadReturnTypeFromTransition() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            return "cpu=k8"

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        val collector = AnalysisRootCauseCollector()
        eventBus.register(collector)
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent("transition function returned string, want dict or list of dicts")

        // Verifies that the AnalysisRootCauseEvent has a no associated configuration. In this case,
        // the error occurs during a transition, so no configuration has been determined.
        val rootCause: AnalysisRootCauseEvent? =
            com.google.common.collect.Iterables.getOnlyElement<AnalysisRootCauseEvent?>(collector.rootCauses)
        assertThat(rootCause.getConfigurations()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputOnlyTransition() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            return {"//command_line_option:foo": "post-transition"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        useConfiguration("--foo=pre-transition")

        val configuration: BuildConfigurationValue = getConfiguration(getConfiguredTarget("//test"))
        assertThat(
            configuration.getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getFoo()
        )
            .isEqualTo("post-transition")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInputAndOutputTransition() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            foo = settings["//command_line_option:foo"].replace("pre", "post")
            return {
                "//command_line_option:foo": foo,
            }

        my_transition = transition(
            implementation = _impl,
            inputs = ["//command_line_option:foo"],
            outputs = ["//command_line_option:foo"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        useConfiguration("--foo=pre-transition")

        val configuration: BuildConfigurationValue = getConfiguration(getConfiguredTarget("//test"))
        assertThat(
            configuration.getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getFoo()
        )
            .isEqualTo("post-transition")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildSettingCannotTransition() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            return {"//command_line_option:foo": "post-transition"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        def _impl(ctx):
            return []

        my_rule = rule(
            implementation = _impl,
            cfg = my_transition,
            build_setting = config.string(),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent(
            "Build setting rules cannot use the `cfg` param to apply transitions to themselves"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadCfgInput() {
        scratch.file(
            "test/rules.bzl", "my_rule = rule(implementation = lambda ctx: [], cfg = 'my_transition')"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent(
            "`cfg` must be set to a transition object initialized by the transition() function."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleReturnConfigs() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            return [
                {"//command_line_option:foo": "split_one"},
                {"//command_line_option:foo": "split_two"},
            ]

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent(
            "Rule transition only allowed to return a single transitioned configuration."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCanDoBadStuffWithParameterizedTransitionsAndSelects() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            if (attr.my_configurable_attr):
                return {"//command_line_option:foo": "true"}
            else:
                return {"//command_line_option:foo": "false"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        def _impl(ctx):
            return []

        my_rule = rule(
            implementation = _impl,
            cfg = my_transition,
            attrs = {
                "my_configurable_attr": attr.bool(default = False),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(
            name = "test",
            my_configurable_attr = select({
                "//conditions:default": False,
                ":true-config": True,
            }),
        )

        config_setting(
            name = "true-config",
            values = {"foo": "true"},
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent(
            ("No attribute 'my_configurable_attr'. "
                    + "Either this attribute does not exist for this rule or the attribute "
                    + "was not resolved because it is set by a select that reads flags the transition "
                    + "may set.")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionReadsInvalidConfiguredAttribute() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            if (hasattr(attr, "my_configurable_attr")):
                return {"//command_line_option:bool": "true"}
            else:
                return {"//command_line_option:bool": "false"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:bool"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")
        def _impl(ctx):
            return []

        my_rule = rule(
            implementation = _impl,
            cfg = my_transition,
            attrs = {
                "my_configurable_attr": attr.bool(default = False),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        # Both of these can be true at once.
        config_setting(
            name = 'dup1',
            values = {'compilation_mode': 'opt'},
        )
        config_setting(
            name = 'dup2',
            values = {'define': 'foo=bar'},
        )

        my_rule(
            name = "test",
            # This select has an error, so the transition should not see the attribute.
            my_configurable_attr = select({
                ":dup1": True,
                ":dup2": False,
                "//conditions:default": False,
            }),
        )
        
        """.trimIndent()
        )

        useConfiguration("-c", "opt", "--define", "foo=bar")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        // The error is from rule analysis, not from the transition.
        assertContainsEvent("Illegal ambiguous match on configurable attribute")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelTypedAttrReturnsLabelNotDep() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            if attr.dict_attr[Label("//test:key")] == "value":
                return {"//command_line_option:foo": "post-transition"}
            else:
                return {"//command_line_option:foo": "uh-oh"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        def _impl(ctx):
            return []

        my_rule = rule(
            implementation = _impl,
            cfg = my_transition,
            attrs = {
                "dict_attr": attr.label_keyed_string_dict(),
            },
        )
        simple_rule = rule(_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule", "simple_rule")

        my_rule(
            name = "test",
            dict_attr = {":key": "value"},
        )

        simple_rule(name = "key")
        
        """.trimIndent()
        )

        useConfiguration("--foo=pre-transition")

        val configuration: BuildConfigurationValue = getConfiguration(getConfiguredTarget("//test"))
        assertThat(
            configuration.getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getFoo()
        )
            .isEqualTo("post-transition")
    }

    @Throws(java.lang.Exception::class)
    private fun writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests() {
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )

        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            "load('//test:rules.bzl', 'my_rule')",
            "load('//test:build_settings.bzl', 'string_flag')",
            "my_rule(name = 'test')",
            "string_flag(",
            "  name = 'cute-animal-fact',",
            "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnBuildSetting_fromDefault() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {"//test:cute-animal-fact": "puffins mate for life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:cute-animal-fact"],
        )
        
        """.trimIndent()
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()

        val configuration: BuildConfigurationValue = getConfiguration(getConfiguredTarget("//test"))
        assertThat(
            configuration
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//test:cute-animal-fact"))
        )
            .isEqualTo("puffins mate for life")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnBuildSetting_fromCommandLine() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {"//test:cute-animal-fact": "puffins_mate_for_life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:cute-animal-fact"],
        )
        
        """.trimIndent()
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()

        useConfiguration("--//test:cute-animal-fact=cats_cant_taste_sugar")

        val configuration: BuildConfigurationValue = getConfiguration(getConfiguredTarget("//test"))
        assertThat(
            configuration
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//test:cute-animal-fact"))
        )
            .isEqualTo("puffins_mate_for_life")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnBuildSetting_badValue() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {"//test:cute-animal-fact": 24}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:cute-animal-fact"],
        )
        
        """.trimIndent()
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()

        useConfiguration("--//test:cute-animal-fact=cats_cant_taste_sugar")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent(
            "expected value of type 'string' for //test:cute-animal-fact, but got 24 (int)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnBuildSetting_noSuchTarget() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {"//test:i-am-not-real": "imaginary-friend"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:i-am-not-real"],
        )
        
        """.trimIndent()
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent(
            "no such target '//test:i-am-not-real': target "
                    + "'i-am-not-real' not declared in package 'test'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnBuildSetting_noSuchPackage() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {"//i-am-not-real": "imaginary-friend"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//i-am-not-real"],
        )
        
        """.trimIndent()
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent(
            "no such package 'i-am-not-real': BUILD file not found in any of the following"
                    + " directories"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnBuildSetting_notABuildSetting() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {"//test:cute-animal-fact": "puffins mate for life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:cute-animal-fact"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        non_build_setting = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "non_build_setting")
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")

        non_build_setting(name = "cute-animal-fact")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent(
            "attempting to transition on '//test:cute-animal-fact' which is not a build setting"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnBuildSetting_dontStoreDefault() {
        scratch.file(
            "test/transitions.bzl",
            "def _transition_impl(settings, attr):",
            "  return {'//test:cute-animal-fact': '" + CUTE_ANIMAL_DEFAULT + "'}",
            "my_transition = transition(",
            "  implementation = _transition_impl,",
            "  inputs = [],",
            "  outputs = ['//test:cute-animal-fact']",
            ")"
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()

        useConfiguration("--//test:cute-animal-fact=cats_cant_taste_sugar")

        val configuration: BuildConfigurationValue = getConfiguration(getConfiguredTarget("//test"))
        assertThat(configuration.getOptions().getStarlarkOptions())
            .doesNotContainKey(Label.parseCanonicalUnchecked("//test:cute-animal-fact"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnBuildSetting_readingUnreadableBuildSetting() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            old_value = settings["//command_line_option:unreadable_by_starlark"]
            fail("This line should be unreachable.")

        my_transition = transition(
            implementation = _transition_impl,
            inputs = ["//command_line_option:unreadable_by_starlark"],
            outputs = ["//command_line_option:unreadable_by_starlark"],
        )
        
        """.trimIndent()
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent(
            java.util.regex.Pattern.compile(
                ("test/transitions.bzl:1:5: before calling _transition_impl: Input build setting"
                        + " //command_line_option:unreadable_by_starlark is of type class"
                        + " \\S*UnreadableStringBox, which is unreadable in Starlark. Please submit a"
                        + " feature request.")
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnBuildSetting_writingUnreadableBuildSetting() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {
                "//command_line_option:unreadable_by_starlark": "post-transition",
            }

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//command_line_option:unreadable_by_starlark"],
        )
        
        """.trimIndent()
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()

        useConfiguration("--unreadable_by_starlark=pre-transition")

        val configuration: BuildConfigurationValue = getConfiguration(getConfiguredTarget("//test"))
        assertThat(
            configuration.getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getUnreadableByStarlark()
        )
            .isEqualTo(UnreadableStringBox.Companion.create("post-transition"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionReadsBuildSetting_fromDefault() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            new_value = settings["//test:cute-animal-fact"].replace("cows", "platypuses")
            return {"//test:cute-animal-fact": new_value}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = ["//test:cute-animal-fact"],
            outputs = ["//test:cute-animal-fact"],
        )
        
        """.trimIndent()
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()

        val configuration: BuildConfigurationValue = getConfiguration(getConfiguredTarget("//test"))
        assertThat(
            configuration
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//test:cute-animal-fact"))
        )
            .isEqualTo("platypuses produce more milk when they listen to soothing music")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionReadsBuildSetting_fromCommandLine() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            now_true = settings["//test:cute-animal-fact"].replace("FALSE", "TRUE")
            return {"//test:cute-animal-fact": now_true}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = ["//test:cute-animal-fact"],
            outputs = ["//test:cute-animal-fact"],
        )
        
        """.trimIndent()
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()

        useConfiguration("--//test:cute-animal-fact=rats_are_ticklish_FALSE")

        val configuration: BuildConfigurationValue = getConfiguration(getConfiguredTarget("//test"))
        assertThat(
            configuration
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//test:cute-animal-fact"))
        )
            .isEqualTo("rats_are_ticklish_TRUE")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionReadsBuildSetting_notABuildSetting() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {"//test:cute-animal-fact": "puffins mate for life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = ["//test:cute-animal-fact"],
            outputs = ["//test:cute-animal-fact"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        non_build_setting = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "non_build_setting")
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")

        non_build_setting(name = "cute-animal-fact")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent(
            "attempting to transition on '//test:cute-animal-fact' which is not a build setting"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionReadsBuildSetting_noSuchTarget() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {"//test:cute-animal-fact": settings["//test:cute-animal-fact"] + " <- TRUE"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = ["//test:i-am-not-real"],
            outputs = ["//test:cute-animal-fact"],
        )
        
        """.trimIndent()
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent(
            "no such target '//test:i-am-not-real': target "
                    + "'i-am-not-real' not declared in package 'test'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAliasedBuildSetting() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {"//test:fact": "puffins_mate_for_life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:fact"],
        )
        
        """.trimIndent()
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()
        scratch.overwriteFile(
            "test/BUILD",
            "load('//test:rules.bzl', 'my_rule')",
            "load('//test:build_settings.bzl', 'string_flag')",
            "my_rule(name = 'test')",
            "alias(name = 'fact', actual = ':cute-animal-fact')",
            "string_flag(",
            "  name = 'cute-animal-fact',",
            "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
            ")"
        )

        useConfiguration("--//test:cute-animal-fact=rats_are_ticklish")

        val starlarkOptions: com.google.common.collect.ImmutableMap<Label?, Any?> =
            getConfiguration(getConfiguredTarget("//test")).getOptions().getStarlarkOptions()
        Truth.assertThat(starlarkOptions.get(Label.parseCanonicalUnchecked("//test:cute-animal-fact")))
            .isEqualTo("puffins_mate_for_life")
        Truth.assertThat(starlarkOptions).doesNotContainKey(Label.parseCanonicalUnchecked("//test:fact"))
        Truth.assertThat(starlarkOptions.keys)
            .containsExactly(Label.parseCanonicalUnchecked("//test:cute-animal-fact"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAliasedBuildSetting_chainedAliases() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {"//test:fact": "puffins_mate_for_life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:fact"],
        )
        
        """.trimIndent()
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()
        scratch.overwriteFile(
            "test/BUILD",
            "load('//test:rules.bzl', 'my_rule')",
            "load('//test:build_settings.bzl', 'string_flag')",
            "my_rule(name = 'test')",
            "alias(name = 'fact', actual = ':alias2')",
            "alias(name = 'alias2', actual = ':cute-animal-fact')",
            "string_flag(",
            "  name = 'cute-animal-fact',",
            "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
            ")"
        )

        useConfiguration("--//test:cute-animal-fact=rats_are_ticklish")

        val configuration: BuildConfigurationValue = getConfiguration(getConfiguredTarget("//test"))
        assertThat(
            configuration
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//test:cute-animal-fact"))
        )
            .isEqualTo("puffins_mate_for_life")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAliasedBuildSetting_configuredActualValue() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {"//test:fact": "puffins mate for life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:fact"],
        )
        
        """.trimIndent()
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()
        scratch.overwriteFile(
            "test/BUILD",
            "load('//test:rules.bzl', 'my_rule')",
            "load('//test:build_settings.bzl', 'string_flag')",
            "my_rule(name = 'test')",
            "alias(",
            "  name = 'fact',",
            "  actual = select({",
            "    '//conditions:default': ':cute-animal-fact',",
            "    ':true-config': 'other-cute-animal-fact',",
            "  })",
            ")",
            "config_setting(",
            "  name = 'true-config',",
            "  values = {'foo': 'true'},",
            ")",
            "string_flag(",
            "  name = 'cute-animal-fact',",
            "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
            ")",
            "string_flag(",
            "  name = 'other-cute-animal-fact',",
            "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
            ")"
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent(
            "attempting to transition on aliased build setting '//test:fact', the actual value of"
                    + " which uses select()."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAliasedBuildSetting_cyclicalAliases() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {"//test:alias1": "puffins mate for life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:alias1"],
        )
        
        """.trimIndent()
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()
        scratch.overwriteFile(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")

        alias(
            name = "alias1",
            actual = ":alias2",
        )

        alias(
            name = "alias2",
            actual = ":alias1",
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent(
            "Dependency cycle involving '//test:alias1' detected in aliased build settings"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAliasedBuildSetting_setAliasAndActual() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {
                "//test:alias": "puffins mate for life",
                "//test:actual": "cats cannot taste sugar",
            }

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = [
                "//test:alias",
                "//test:actual",
            ],
        )
        
        """.trimIndent()
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()
        scratch.overwriteFile(
            "test/BUILD",
            "load('//test:rules.bzl', 'my_rule')",
            "load('//test:build_settings.bzl', 'string_flag')",
            "my_rule(name = 'test')",
            "alias(name = 'alias', actual = ':actual')",
            "string_flag(",
            "  name = 'actual',",
            "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
            ")"
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent(
            "Dependency cycle involving '//test:actual' detected in aliased build settings"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAliasedBuildSetting_outputReturnMismatch() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {
                "//test:actual": "cats cannot taste sugar",
            }

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = [
                "//test:alias",
            ],
        )
        
        """.trimIndent()
        )
        writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests()
        scratch.overwriteFile(
            "test/BUILD",
            "load('//test:rules.bzl', 'my_rule')",
            "load('//test:build_settings.bzl', 'string_flag')",
            "my_rule(name = 'test')",
            "alias(name = 'alias', actual = ':actual')",
            "string_flag(",
            "  name = 'actual',",
            "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
            ")"
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent("transition function returned undeclared output '//test:actual'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOneParamTransitionFunctionApiFails() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings):
            return {"//command_line_option:foo": "post-transition"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent("_impl() accepts no more than 1 positional argument but got 2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotTransitionOnExperimentalFlag() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            return {"//command_line_option:experimental_something_something": True}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:experimental_something_something"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent("Cannot transition on --experimental_* or --incompatible_* options")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisTestsCanTransitionOnExperimentalFlag() {
        scratch.file(
            "test/analysis_test.bzl",
            """
        def make_test(name, target, settings):
          testing.analysis_test(
            name,
            lambda ctx: None,
            attrs = {
              "target" : attr.label(
                default = target,
                cfg = analysis_test_transition(settings = settings)
              )
            },
          )

        

        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:analysis_test.bzl", "make_test")
        filegroup(name = "foo")
        make_test(name = "test", target = ":foo", settings = {
          "//command_line_option:experimental_something_something": True
        })
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertDoesNotContainEvent("Cannot transition on --experimental_* or --incompatible_* options")
        assertContainsEvent(
            "transition outputs [//command_line_option:experimental_something_something] do not"
                    + " correspond to valid settings"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionIsCheckedAgainstDefaultAllowlist() {
        scratch.overwriteFile(
            TestConstants.TOOLS_REPOSITORY_SCRATCH
                    + "tools/allowlists/function_transition_allowlist/BUILD",
            "package_group(",
            "    name = 'function_transition_allowlist',",
            "    packages = [],",
            ")"
        )
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            return {"//command_line_option:foo": "post-transition"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        def _impl(ctx):
            return []

        my_rule = rule(
            implementation = _impl,
            cfg = my_transition,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        useConfiguration("--foo=pre-transition")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent("Non-allowlisted use of Starlark transition")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoNullOptionValues() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            if settings["//command_line_option:nullable_option"] == None:
                return {"//command_line_option:foo": "post-transition"}
            else:
                return {"//command_line_option:foo": settings["//command_line_option:foo"]}

        my_transition = transition(
            implementation = _impl,
            inputs = [
                "//command_line_option:foo",
                "//command_line_option:nullable_option",
            ],
            outputs = ["//command_line_option:foo"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        useConfiguration("--nullable_option=", "--foo=pre-transition")

        val configuration: BuildConfigurationValue = getConfiguration(getConfiguredTarget("//test"))
        assertThat(
            configuration.getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getFoo()
        )
            .isEqualTo("post-transition")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowlistOnRuleNotTargets() {
        // allowlists //test/...
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            return {"//command_line_option:foo": "post-transition"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "neverland/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )
        scratch.file("test/BUILD")
        useConfiguration("--foo=pre-transition")

        val configuration: BuildConfigurationValue =
            getConfiguration(getConfiguredTarget("//neverland:test"))
        assertThat(
            configuration.getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getFoo()
        )
            .isEqualTo("post-transition")
    }

    // TODO(blaze-configurability): We probably want to eventually turn this off. Flip this test when
    // this isn't allowed anymore.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowlistOnTargetsStillWorks() {
        // allowlists //test/...
        scratch.file(
            "neverland/transitions.bzl",
            """
        def _impl(settings, attr):
            return {"//command_line_option:foo": "post-transition"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "neverland/rules.bzl",
            """
        load("//neverland:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//neverland:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )
        scratch.file("neverland/BUILD")
        useConfiguration("--foo=pre-transition")

        val configuration: BuildConfigurationValue = getConfiguration(getConfiguredTarget("//test"))
        assertThat(
            configuration.getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getFoo()
        )
            .isEqualTo("post-transition")
    }

    @org.junit.Test
    @TestParameters(
        ("{"
                + "returnLine: 'return []',"
                + "returnLine: 'return {}',"
                + "returnLine: 'return None',"
                + "returnLine: 'pass',"
                + "}")
    )
    @Throws(java.lang.Exception::class)
    fun noopReturnValues(returnLine: String?) {
        scratch.file(
            "test/transitions.bzl",
            "def _impl(settings, attr):",
            "  " + returnLine,
            "my_transition = transition(implementation = _impl, inputs = [],",
            "  outputs = ['//command_line_option:foo'])"
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )
        // --trim_test_configuration means only the top-level configuration has TestOptions.
        BuildViewTestCase.Companion.assertConfigurationsEqual(
            getConfiguration(getConfiguredTarget("//test")),
            targetConfig,
            com.google.common.collect.ImmutableSet.of<E?>(TestOptions::class.java)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun composingTransitionReportsAllStarlarkErrors() {
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        
        """.trimIndent()
        )
        scratch.file(
            "test/transitions.bzl",
            """
        def _attr_impl(settings, attr):
            return {"//test:attr_transition_output_flag1": "not default"}

        attr_transition = transition(
            implementation = _attr_impl,
            inputs = [],
            outputs = [
                "//test:attr_transition_output_flag1",
                "//test:attr_transition_output_flag2",
            ],
        )

        def _self_impl(settings, attr):
            return {"//test:self_transition_output_flag1": "not default"}

        self_transition = transition(
            implementation = _self_impl,
            inputs = [],
            outputs = [
                "//test:self_transition_output_flag1",
                "//test:self_transition_output_flag2",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "attr_transition", "self_transition")

        def _impl(ctx):
            return []

        rule_with_attr_transition = rule(
            implementation = _impl,
            attrs = {
                "deps": attr.label_list(cfg = attr_transition),
            },
        )
        rule_with_self_transition = rule(
            implementation = _impl,
            cfg = self_transition,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")
        load("//test:rules.bzl", "rule_with_attr_transition", "rule_with_self_transition")

        string_flag(
            name = "attr_transition_output_flag1",
            build_setting_default = "",
        )

        string_flag(
            name = "attr_transition_output_flag2",
            build_setting_default = "",
        )

        string_flag(
            name = "self_transition_output_flag1",
            build_setting_default = "",
        )

        string_flag(
            name = "self_transition_output_flag2",
            build_setting_default = "",
        )

        rule_with_attr_transition(
            name = "buildme",
            deps = [":adep"],
        )

        rule_with_self_transition(name = "adep")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:buildme")
        assertContainsEvent(
            "transition outputs [//test:attr_transition_output_flag2] were not defined by transition "
                    + "function"
        )
        // While _self_impl is in error as it does not define //test:self_transition_output_flag2,
        // evaluation stops at the faulty _attr_impl definition so it does not cause an error.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnDefine() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            return {"//command_line_option:define": "chonky=true"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:define"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent("Starlark transition on --define not supported - try using build settings")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun successfulTypeConversionOfNativeListOption() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            return {"//command_line_option:platforms": ["//test:my_platform"]}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:platforms"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        platform(name = "my_platform")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        getConfiguredTarget("//test")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun successfulTypeConversionOfNativeListOption_unambiguousLabels() {
        setBuildLanguageOptions("--incompatible_unambiguous_label_stringification")

        scratch.overwriteFile("MODULE.bazel", "bazel_dep(name='rules_x',version='1.0')")
        registry.addModule(BzlmodTestUtil.createModuleKey("rules_x", "1.0"), "module(name='rules_x', version='1.0')")
        scratch.file("modules/rules_x+1.0/REPO.bazel")
        scratch.file("modules/rules_x+1.0/BUILD")
        scratch.file(
            "modules/rules_x+1.0/defs.bzl",
            """
        def _tr_impl(settings, attr):
            return {"//command_line_option:platforms": [Label("@@//test:my_platform")]}

        my_transition = transition(
            implementation = _tr_impl,
            inputs = [],
            outputs = ["//command_line_option:platforms"],
        )
        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load("@rules_x//:defs.bzl", "my_rule")

        platform(name = "my_platform")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        invalidatePackages()

        getConfiguredTarget("//test")
        assertNoEvents()
    }

    // Regression test for b/170729565
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSetBooleanNativeOptionWithStarlarkBoolean() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            return {"//command_line_option:bool": True}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:bool"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )
        useConfiguration("--bool=false")
        val ct: ConfiguredTarget? = getConfiguredTarget("//test")
        assertNoEvents()
        assertThat(
            getConfiguration(ct).getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getBool()
        ).isTrue()
    }

    // Regression test for b/170729565
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSetBooleanNativeOptionWithItself() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            return {"//command_line_option:bool": settings["//command_line_option:bool"]}

        my_transition = transition(
            implementation = _impl,
            inputs = ["//command_line_option:bool"],
            outputs = ["//command_line_option:bool"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )
        useConfiguration("--bool=false")
        val ct: ConfiguredTarget? = getConfiguredTarget("//test")
        assertNoEvents()
        assertThat(
            getConfiguration(ct).getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getBool()
        ).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failedTypeConversionOfNativeListOption() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            return {"//command_line_option:platforms": ["this is not a valid label::"]}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:platforms"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        platform(name = "my_platform")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent("invalid target name ':': target names may not contain ':'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun successfulTypeConversionOfNativeListOptionEmptyList() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            return {"//command_line_option:fission": []}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:fission"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        platform(name = "my_platform")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        val ct: ConfiguredTarget? = getConfiguredTarget("//test")
        assertNoEvents()
        assertThat(getConfiguration(ct).getOptions().get(CppOptions::class.java).getFissionModes()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failedTypeConversionOfNativeListOptionNone() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            return {"//command_line_option:copt": None}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:copt"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent(
            "'None' value not allowed for List-type option 'copt'. Please use '[]' instead if trying"
                    + " to set option to empty value."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkPatchTransitionRequiredFragments() {
        // All Starlark rule transitions are patch transitions, while all Starlark attribute transitions
        // are split transitions.
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            # --copt is a C++ option.
            return {"//command_line_option:copt": []}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:copt"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        platform(name = "my_platform")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        val ct: ConfiguredTargetAndData = getConfiguredTargetAndData("//test")
        assertNoEvents()
        val testTarget: Rule = ct.getTargetForTesting() as Rule
        val ruleTransition: ConfigurationTransition =
            testTarget
                .getRuleClassObject()
                .getTransitionFactory()
                .create(RuleTransitionData.create(testTarget, null, ""))
        val requiredFragments: RequiredConfigFragmentsProvider.Builder =
            RequiredConfigFragmentsProvider.builder()
        ruleTransition.addRequiredFragments(
            requiredFragments, ct.getConfiguration().getBuildOptionDetails()
        )
        assertThat(requiredFragments.build().optionsClasses()).containsExactly(CppOptions::class.java)
    }

    /**
     * Unit test for an invalid output directory from a mnemonic via a dep transition. Integration
     * test for top-level transition in //src/test/shell/integration:starlark_configurations_test#
     * test_invalid_mnemonic_from_transition_top_level. Has to be an integration test because the
     * error is emitted in BuildTool.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidMnemonicFromDepTransition() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _impl(settings, attr):
            return {"//command_line_option:cpu": "//bad:cpu"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:cpu"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "bottom")

        genrule(
            name = "test",
            srcs = [":bottom"],
            outs = ["out"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//test:test")).isNull()
        assertContainsEvent("'//bad:cpu' is invalid as part of a path: must not contain /")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnAllowMultiplesBuildSettingRequiresList() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {"//test:cute-animal-fact": "puffins mate for life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:cute-animal-fact"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True, allow_multiple = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")

        string_flag(
            name = "cute-animal-fact",
            build_setting_default = "cats can't taste sugar",
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test")
        assertContainsEvent(
            "'//test:cute-animal-fact' allows multiple values and must be set in transition using a"
                    + " starlark list instead of single value"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnAllowMultiplesBuildSetting() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {"//test:cute-animal-fact": ["puffins mate for life"]}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:cute-animal-fact"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True, allow_multiple = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")

        string_flag(
            name = "cute-animal-fact",
            build_setting_default = "cats can't taste sugar",
        )
        
        """.trimIndent()
        )

        val starlarkOptions: MutableMap<Label?, Any?> =
            getConfiguration(getConfiguredTarget("//test")).getOptions().getStarlarkOptions()
        assertNoEvents()
        Truth.assertThat(
            starlarkOptions.get(Label.parseCanonicalUnchecked("//test:cute-animal-fact")) as MutableList<*>?
        )
            .containsExactly("puffins mate for life")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnAllowMultiplesBuildSettingAlwaysSeesListValue() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            setting_type = type(settings["//test:multiple_flag"])
            if setting_type != type([]):
                fail("Expected setting to be a list, got %s" % setting_type)
            return {}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = ["//test:multiple_flag"],
            outputs = ["//test:multiple_flag"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True, allow_multiple = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")

        string_flag(
            name = "multiple_flag",
            build_setting_default = "",
        )
        
        """.trimIndent()
        )

        // Starlark option at is default value.
        getConfiguredTarget("//test")

        useConfiguration("--//test:multiple_flag=foo")
        getConfiguredTarget("//test")

        useConfiguration("--//test:multiple_flag=foo,bar")
        getConfiguredTarget("//test")
    }

    /**
     * Changing --cpu implicitly changes the target platform. Test that the old value of --platforms
     * gets cleared out (platform mappings can then kick in to set --platforms correctly).
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplicitPlatformsChange() {
        scratch.file("platforms/BUILD", "platform(name = 'my_platform', constraint_values = [])")
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {"//command_line_option:cpu": "ppc"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//command_line_option:cpu"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:my_platform")
        // When --platforms is empty and no platform mapping triggers, PlatformMappingValue sets
        // --platforms to PlatformOptions.computeTargetPlatform(), which defaults to the host.
        assertThat(
            getConfiguration(getConfiguredTarget("//test:test"))
                .getOptions()
                .get(PlatformOptions::class.java)
                .getPlatforms()
        )
            .containsExactly(Label.parseCanonicalUnchecked(TestConstants.PLATFORM_LABEL))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExplicitPlatformsChange() {
        scratch.file(
            "platforms/BUILD",
            """
        platform(
            name = "my_platform",
            constraint_values = [],
        )

        platform(
            name = "my_other_platform",
            constraint_values = [],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {
                "//command_line_option:cpu": "ppc",
                "//command_line_option:platforms": ["//platforms:my_other_platform"],
            }

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = [
                "//command_line_option:cpu",
                "//command_line_option:platforms",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:my_platform")
        assertThat(
            getConfiguration(getConfiguredTarget("//test:test"))
                .getOptions()
                .get(PlatformOptions::class.java)
                .getPlatforms()
        )
            .containsExactly(Label.parseCanonicalUnchecked("//platforms:my_other_platform"))
    }

    /* If the transition doesn't change --cpu, it doesn't constitute a platform change. */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoPlatformChange() {
        scratch.file("platforms/BUILD", "platform(name = 'my_platform', constraint_values = [])")
        scratch.file(
            "test/transitions.bzl",
            """
        def _transition_impl(settings, attr):
            return {
                "//command_line_option:foo": "blah",
            }

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = [
                "//command_line_option:foo",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:my_platform")
        assertThat(
            getConfiguration(getConfiguredTarget("//test:test"))
                .getOptions()
                .get(PlatformOptions::class.java)
                .getPlatforms()
        )
            .containsExactly(Label.parseCanonicalUnchecked("//platforms:my_platform"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionsStillTriggerWhenOnlyRuleAttributesChange() {
        scratch.file(
            "test/defs.bzl",
            """
        def _transition_impl(settings, attr):
            return {
                "//command_line_option:foo": attr.my_attr,
            }

        _my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = [
                "//command_line_option:foo",
            ],
        )

        def _rule_impl(ctx):
            return []

        my_rule = rule(
            implementation = _rule_impl,
            cfg = _my_transition,
            attrs = {
                "my_attr": attr.string(),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "my_rule")

        my_rule(
            name = "buildme",
            my_attr = "first build",
        )
        
        """.trimIndent()
        )
        assertThat(
            getConfiguration(getConfiguredTarget("//test:buildme"))
                .getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getFoo()
        )
            .isEqualTo("first build")

        scratch.overwriteFile(
            "test/BUILD",
            """
        load("//test:defs.bzl", "my_rule")

        my_rule(
            name = "buildme",
            my_attr = "second build",
        )
        
        """.trimIndent()
        )
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("test/BUILD")).build(),
            Root.fromPath(rootDirectory)
        )

        assertThat(
            getConfiguration(getConfiguredTarget("//test:buildme"))
                .getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getFoo()
        )
            .isEqualTo("second build")
    }

    private class AnalysisRootCauseCollector {
        private val rootCauses: java.util.ArrayList<AnalysisRootCauseEvent?> =
            java.util.ArrayList<AnalysisRootCauseEvent?>()

        @com.google.common.eventbus.Subscribe
        fun rootCause(event: AnalysisRootCauseEvent?) {
            rootCauses.add(event)
        }
    }

    companion object {
        private const val CUTE_ANIMAL_DEFAULT = "cows produce more milk when they listen to soothing music"
    }
}
