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

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** Tests for [StarlarkAttributeTransitionProvider].  */
@RunWith(JUnit4::class)
class StarlarkAttrTransitionProviderTest : BuildViewTestCase() {
    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        builder.addConfigurationFragment(DummyTestFragment::class.java)
        return builder.build()
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setupMyInfo() {
        scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()")

        scratch.file("myinfo/BUILD")
    }

    @Throws(java.lang.Exception::class)
    private fun writeBasicTestFiles() {
        getAnalysisMock().ccSupport().setupCcToolchainConfigForCpu(mockToolsConfig, "armeabi-v7a")
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def transition_func(settings, attr):
            return [
                {"//command_line_option:cpu": "k8"},
                {"//command_line_option:cpu": "armeabi-v7a"},
            ]

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = ["//command_line_option:cpu"],
        )

        def impl(ctx):
            return MyInfo(
                attr_deps = ctx.split_attr.deps,
                attr_dep = ctx.split_attr.dep,
            )

        my_rule = rule(
            implementation = impl,
            attrs = {
                "deps": attr.label_list(cfg = my_transition),
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
            deps = [
                ":main1",
                ":main2",
            ],
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )

        cc_binary(
            name = "main2",
            srcs = ["main2.c"],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkSplitTransitionSplitAttr() {
        scratch.file(
            "test/starlark/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def transition_func(settings, attr):
            return {
                "amsterdam": {"//command_line_option:foo": "stroopwafel"},
                "paris": {"//command_line_option:foo": "crepe"},
            }

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )

        def _impl(ctx):
            return MyInfo(split_attr_dep = ctx.split_attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )

        def _s_impl_e(ctx):
            return []

        simple_rule = rule(_s_impl_e)
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:rules.bzl", "my_rule", "simple_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple_rule(name = "dep")
        
        """.trimIndent()
        )

        val splitAttr: MutableMap<Any?, ConfiguredTarget?> =
            getMyInfoFromTarget(getConfiguredTarget("//test/starlark:test"))
                .getValue("split_attr_dep") as MutableMap<Any?, ConfiguredTarget?>
        Truth.assertThat(splitAttr.keys).containsExactly("amsterdam", "paris")
        assertThat(
            getConfiguration(splitAttr.get("amsterdam"))
                .getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getFoo()
        )
            .isEqualTo("stroopwafel")
        assertThat(
            getConfiguration(splitAttr.get("paris"))
                .getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getFoo()
        )
            .isEqualTo("crepe")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkListSplitTransitionSplitAttr() {
        scratch.file(
            "test/starlark/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def transition_func(settings, attr):
            return [
                {"//command_line_option:foo": "stroopwafel"},
                {"//command_line_option:foo": "crepe"},
            ]

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )

        def _impl(ctx):
            return MyInfo(split_attr_dep = ctx.split_attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )

        def _s_impl_e(ctx):
            return []

        simple_rule = rule(_s_impl_e)
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:rules.bzl", "my_rule", "simple_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple_rule(name = "dep")
        
        """.trimIndent()
        )

        val splitAttr: MutableMap<Any?, ConfiguredTarget?> =
            getMyInfoFromTarget(getConfiguredTarget("//test/starlark:test"))
                .getValue("split_attr_dep") as MutableMap<Any?, ConfiguredTarget?>
        Truth.assertThat(splitAttr.keys).containsExactly("0", "1")
        assertThat(
            getConfiguration(splitAttr.get("0")).getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getFoo()
        )
            .isEqualTo("stroopwafel")
        assertThat(
            getConfiguration(splitAttr.get("1")).getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getFoo()
        )
            .isEqualTo("crepe")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkPatchTransitionSplitAttr() {
        scratch.file(
            "test/starlark/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def transition_func(settings, attr):
            return {"//command_line_option:foo": "stroopwafel"}

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )

        def _impl(ctx):
            return MyInfo(split_attr_dep = ctx.split_attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )

        def _s_impl_e(ctx):
            return []

        simple_rule = rule(_s_impl_e)
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:rules.bzl", "my_rule", "simple_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple_rule(name = "dep")
        
        """.trimIndent()
        )

        val splitAttr: MutableMap<Any?, ConfiguredTarget?> =
            getMyInfoFromTarget(getConfiguredTarget("//test/starlark:test"))
                .getValue("split_attr_dep") as MutableMap<Any?, ConfiguredTarget?>
        Truth.assertThat(splitAttr.keys).containsExactly(Starlark.NONE)
        assertThat(
            getConfiguration(splitAttr.get(Starlark.NONE))
                .getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getFoo()
        )
            .isEqualTo("stroopwafel")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkConfigSplitAttr() {
        // This is a customized test case for b/152078818, where a starlark transition that takes a
        // starlark config as input caused a failure when no custom values were provided for the config.
        scratch.file(
            "test/starlark/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _build_setting_impl(ctx):
            return []

        string_flag = rule(
            implementation = _build_setting_impl,
            build_setting = config.string(flag = True),
        )

        def transition_func(settings, attr):
            return {"amsterdam": {"//command_line_option:foo": "stroopwafel"}}

        my_transition = transition(
            implementation = transition_func,
            inputs = ["//test/starlark:custom_arg"],
            outputs = ["//command_line_option:foo"],
        )

        def _impl(ctx):
            return MyInfo(split_attr_dep = ctx.split_attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )

        def _s_impl_e(ctx):
            return []

        simple_rule = rule(_s_impl_e)
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:rules.bzl", "my_rule", "simple_rule", "string_flag")

        string_flag(
            name = "custom_arg",
            build_setting_default = "ski",
        )

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple_rule(name = "dep")
        
        """.trimIndent()
        )

        // Run the analysis phase with the default options, i.e. no custom flags first.
        getConfiguredTarget("//test/starlark:test")

        // b/152078818 was unique in that an error was hidden until the next run due to how event replay
        // was done. Test it by supplying a value to the starlark config, which should trigger the
        // analysis phase again.
        useConfiguration("--//test/starlark:custom_arg=snowboard")
        getConfiguredTarget("//test/starlark:test")
    }

    /**
     * Tests that split transition key is preserved even when there's a single split with no change.
     * 
     * 
     * Starlark implementation may depend on the value of the key.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkSplitTransitionSplitAttrSingleUnchanged() {
        useConfiguration("--foo=stroopwafel")
        scratch.file(
            "test/starlark/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def transition_func(settings, attr):
            return {
                "amsterdam": {"//command_line_option:foo": "stroopwafel"},
            }

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )

        def _impl(ctx):
            return MyInfo(split_attr_dep = ctx.split_attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )

        def _s_impl_e(ctx):
            return []

        simple_rule = rule(_s_impl_e)
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:rules.bzl", "my_rule", "simple_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple_rule(name = "dep")
        
        """.trimIndent()
        )

        val splitAttr: MutableMap<Any?, ConfiguredTarget?> =
            getMyInfoFromTarget(getConfiguredTarget("//test/starlark:test"))
                .getValue("split_attr_dep") as MutableMap<Any?, ConfiguredTarget?>
        Truth.assertThat(splitAttr.keys).containsExactly("amsterdam")
        assertThat(
            getConfiguration(splitAttr.get("amsterdam"))
                .getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getFoo()
        )
            .isEqualTo("stroopwafel")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionSplitTransitionCheckAttrDeps() {
        writeBasicTestFiles()
        testSplitTransitionCheckAttrDeps(getConfiguredTarget("//test/starlark:test"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionSplitTransitionCheckAttrDep() {
        writeBasicTestFiles()
        testSplitTransitionCheckAttrDep(getConfiguredTarget("//test/starlark:test"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNullTransitionAttributeWithMissingSkyframeDep() {
        scratch.file(
            "test/starlark/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def transition_func(settings, attr):
            return {"//test/starlark/nested:flag": True}

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = ["//test/starlark/nested:flag"],
        )

        def _impl(ctx):
            return MyInfo(data = ctx.attr.data)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "data": attr.label_list(cfg = my_transition, allow_files = True, default = []),
            },
        )

        def _basic_impl(ctx):
            return []

        bool_flag = rule(
            implementation = _basic_impl,
            build_setting = config.bool(flag = True),
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/nested/BUILD",
            """
        load("//test/starlark:rules.bzl", "bool_flag")

        bool_flag(
            name = "flag",
            build_setting_default = False,
        )
        
        """.trimIndent()
        )
        scratch.file("test/starlark/some_file.txt", "Random content")
        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:rules.bzl", "my_rule")

        my_rule(
            name = "test",
            data = ["some_file.txt"],
        )
        
        """.trimIndent()
        )

        assertThat(getConfiguredTarget("//test/starlark:test")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetAndRuleNotInAllowlist() {
        scratch.overwriteFile(
            TestConstants.TOOLS_REPOSITORY_SCRATCH
                    + "tools/allowlists/function_transition_allowlist/BUILD",
            "package_group(",
            "    name = 'function_transition_allowlist',",
            "    packages = [",
            "        '//test/...',",
            "    ],",
            ")"
        )
        getAnalysisMock().ccSupport().setupCcToolchainConfigForCpu(mockToolsConfig, "armeabi-v7a")
        scratch.file(
            "not_allowlisted/my_rule.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def transition_func(settings, attr):
            return [
                {"//command_line_option:cpu": "k8"},
                {"//command_line_option:cpu": "armeabi-v7a"},
            ]

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = ["//command_line_option:cpu"],
        )

        def impl(ctx):
            return MyInfo(
                attr_deps = ctx.attr.deps,
                attr_dep = ctx.attr.dep,
            )

        my_rule = rule(
            implementation = impl,
            attrs = {
                "deps": attr.label_list(cfg = my_transition),
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "not_allowlisted/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//not_allowlisted:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main",
        )

        cc_binary(
            name = "main",
            srcs = ["main.c"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//not_allowlisted:test")
        assertContainsEvent("Non-allowlisted use of Starlark transition")
    }

    @Throws(java.lang.Exception::class)
    private fun testSplitTransitionCheckAttrDeps(target: ConfiguredTarget) {
        val attrDeps: Dict<String?, MutableList<ConfiguredTarget?>?> =
            getMyInfoFromTarget(target).getValue("attr_deps") as Dict<String?, MutableList<ConfiguredTarget?>?>
        Truth.assertThat(attrDeps.size).isEqualTo(2)
        val attrDepsMap: com.google.common.collect.ListMultimap<String?, Any?>? =
            attrDeps.values.stream()
                .flatMap<ConfiguredTarget?> { obj: MutableList<ConfiguredTarget?>? -> obj.stream() }
                .map<Any?> { ct: ConfiguredTarget? -> getConfiguration(ct).getCpu() }
        TODO(
            """
            |Cannot convert element
            |With text:
            |collect(<@org.jspecify.annotations.Nullable Object, Object, @org.jspecify.annotations.Nullable Object, ArrayListMultimap<Object, @org.jspecify.annotations.Nullable Object>>toMultimap(cpu -> cpu, (ignored) -> target, ArrayListMultimap::create)
            """.trimMargin()
        )

        Truth.assertThat(attrDepsMap).valuesForKey("k8").hasSize(2)
        Truth.assertThat(attrDepsMap).valuesForKey("armeabi-v7a").hasSize(2)
    }

    @Throws(java.lang.Exception::class)
    private fun testSplitTransitionCheckAttrDep(target: ConfiguredTarget) {
        // Check that even though my_rule.dep is defined as a single label, ctx.attr.dep is still a list
        // with multiple ConfiguredTarget objects because of the two different CPUs.
        val attrDep: Dict<String?, ConfiguredTarget?> =
            getMyInfoFromTarget(target).getValue("attr_dep") as Dict<String?, ConfiguredTarget?>
        Truth.assertThat(attrDep.size).isEqualTo(2)
        val attrDepMap: com.google.common.collect.ListMultimap<String?, Any?>? =
            attrDep.values.stream()
                .map<Any?> { ct: ConfiguredTarget? -> getConfiguration(ct).getCpu() }
        TODO(
            """
            |Cannot convert element
            |With text:
            |collect(<@org.jspecify.annotations.Nullable Object, Object, @org.jspecify.annotations.Nullable Object, ArrayListMultimap<Object, @org.jspecify.annotations.Nullable Object>>toMultimap(cpu -> cpu, (ignored) -> target, ArrayListMultimap::create)
            """.trimMargin()
        )

        Truth.assertThat(attrDepMap).valuesForKey("k8").hasSize(1)
        Truth.assertThat(attrDepMap).valuesForKey("armeabi-v7a").hasSize(1)
    }

    @Throws(java.lang.Exception::class)
    private fun writeReadSettingsTestFiles() {
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _flag_impl(ctx):
            pass

        string_flag = rule(
            implementation = _flag_impl,
            build_setting = config.string(flag = True),
        )

        string_list_flag = rule(
            implementation = _flag_impl,
            build_setting = config.string_list(flag = True),
        )

        def transition_func(settings, attr):
            transitions = []
            for val in settings["//test/starlark:source"]:
                transitions.append({"//test/starlark:dest": val})
            return transitions

        my_transition = transition(
            implementation = transition_func,
            inputs = ["//test/starlark:source"],
            outputs = ["//test/starlark:dest"],
        )

        def impl(ctx):
            return MyInfo(attr_dep = ctx.split_attr.dep)

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//test/starlark:my_rule.bzl", "my_rule", "string_list_flag", "string_flag")

        string_list_flag(
            name = "source",
            build_setting_default = [],
        )

        string_flag(
            name = "dest",
            build_setting_default = "",
        )

        my_rule(
            name = "test",
            dep = ":main",
        )

        cc_binary(
            name = "main",
            srcs = ["main.c"],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReadSettingsSplitDepAttrDep() {
        getAnalysisMock().ccSupport().setupCcToolchainConfigForCpu(mockToolsConfig, "armeabi-v7a")
        writeReadSettingsTestFiles()

        useConfiguration("--//test/starlark:source=first,second")
        val target: ConfiguredTarget? = getConfiguredTarget("//test/starlark:test")

        val splitDep: Dict<String?, ConfiguredTarget?> =
            getMyInfoFromTarget(target).getValue("attr_dep") as Dict<String?, ConfiguredTarget?>
        Truth.assertThat(splitDep.size).isEqualTo(2)
        val values: com.google.common.collect.ImmutableSet<String?> =
            splitDep.values.stream()
                .map<BuildConfigurationValue?> { ct: ConfiguredTarget? -> this.getConfiguration(ct) }
                .map<Any?>(BuildConfigurationValue::getOptions)
                .map<String?> { options: Any? ->
                    options
                        .getStarlarkOptions()
                        .get(Label.parseCanonicalUnchecked("//test/starlark:dest")) as String?
                }
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())
        Truth.assertThat(values).containsExactly("first", "second")
    }

    @Throws(java.lang.Exception::class)
    private fun writeOptionConversionTestFiles() {
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def transition_func(settings, attr):
            return {
                "//command_line_option:cpu": "armeabi-v7a",
                "//command_line_option:dynamic_mode": "off",
            }

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = [
                "//command_line_option:cpu",
                "//command_line_option:dynamic_mode",
            ],
        )

        def impl(ctx):
            return MyInfo(attr_dep = ctx.attr.dep)

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:my_rule.bzl", "my_rule")
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")

        my_rule(
            name = "test",
            dep = ":main",
        )

        cc_binary(
            name = "main",
            srcs = ["main.c"],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOptionConversionCpu() {
        writeOptionConversionTestFiles()
        BazelMockAndroidSupport.setupNdk(mockToolsConfig) // cc_binary needs this

        val target: ConfiguredTarget? = getConfiguredTarget("//test/starlark:test")

        val dep: MutableList<ConfiguredTarget?>? =
            getMyInfoFromTarget(target).getValue("attr_dep") as MutableList<ConfiguredTarget?>?
        Truth.assertThat(dep).hasSize(1)
        assertThat(getConfiguration(com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(dep)).getCpu()).isEqualTo(
            "armeabi-v7a"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun writeReadAndPassthroughOptionsTestFiles() {
        scratch.file(
            "test/skylark/my_rule.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        settings_under_test = {
            "//command_line_option:cpu": "armeabi-v7a",
            "//command_line_option:compilation_mode": "dbg",
            "//command_line_option:platform_suffix": "my-platform-suffix",
        }

        def set_options_transition_func(settings, attr):
            return settings_under_test

        def passthrough_transition_func(settings, attr):
            # All values in this test should be possible to copy within Starlark.
            ret = dict(settings)

            # All values in this test should be possible to read within Starlark.
            # This does not mean that it is possible to set a string value for all settings,
            # e.g. //command_line_option:bazes should be set to a list of strings.
            for key, expected_value in settings_under_test.items():
                if str(ret[key]) != expected_value:
                    fail("%s did not pass through, got %r expected %r" %
                         (key, str(ret[key]), expected_value))
            ret["//command_line_option:bazes"] = ["ok"]
            return ret

        my_set_options_transition = transition(
            implementation = set_options_transition_func,
            inputs = [],
            outputs = settings_under_test.keys(),
        )
        my_passthrough_transition = transition(
            implementation = passthrough_transition_func,
            inputs = settings_under_test.keys(),
            outputs = ["//command_line_option:bazes"] + settings_under_test.keys(),
        )

        def impl(ctx):
            return MyInfo(attr_dep = ctx.attr.dep)

        my_set_options_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_set_options_transition),
            },
        )
        my_passthrough_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_passthrough_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/skylark/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//test/skylark:my_rule.bzl", "my_passthrough_rule", "my_set_options_rule")

        my_set_options_rule(
            name = "top",
            dep = ":test",
        )

        my_passthrough_rule(
            name = "test",
            dep = ":main",
        )

        cc_binary(
            name = "main",
            srcs = ["main.c"],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompilationModeReadableInStarlarkTransitions() {
        writeReadAndPassthroughOptionsTestFiles()
        BazelMockAndroidSupport.setupNdk(mockToolsConfig) // cc_binary needs this

        val topTarget: ConfiguredTarget? = getConfiguredTarget("//test/skylark:top")

        val topDep: MutableList<ConfiguredTarget?>? =
            getMyInfoFromTarget(topTarget).getValue("attr_dep") as MutableList<ConfiguredTarget?>?
        Truth.assertThat(topDep).hasSize(1)
        val testTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(topDep)
        val testDep: MutableList<ConfiguredTarget?>? =
            getMyInfoFromTarget(testTarget).getValue("attr_dep") as MutableList<ConfiguredTarget?>?
        Truth.assertThat(testDep).hasSize(1)
        val mainTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(testDep)
        assertThat(
            getConfiguration(mainTarget).getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getBazes()
        )
            .containsExactly("ok")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUndeclaredOptionKey() {
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        def transition_func(settings, attr):
            return {"//command_line_option:cpu": "k8"}

        my_transition = transition(implementation = transition_func, inputs = [], outputs = [])

        def impl(ctx):
            return []

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test/starlark:test")
        assertContainsEvent(
            "transition function returned undeclared output '//command_line_option:cpu'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclaredOutputNotReturned() {
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        def transition_func(settings, attr):
            return {"//command_line_option:cpu": "k8"}

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = [
                "//command_line_option:cpu",
                "//command_line_option:host_cpu",
            ],
        )

        def impl(ctx):
            return []

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test/starlark:test")
        assertContainsEvent(
            "transition outputs [//command_line_option:host_cpu] were not "
                    + "defined by transition function"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSettingsContainOnlyInputs() {
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        def transition_func(settings, attr):
            if (len(settings) != 2 or
                (not settings["//command_line_option:host_cpu"]) or
                (not settings["//command_line_option:cpu"])):
                fail()
            return {"//command_line_option:cpu": "k8"}

        my_transition = transition(
            implementation = transition_func,
            inputs = [
                "//command_line_option:host_cpu",
                "//command_line_option:cpu",
            ],
            outputs = ["//command_line_option:cpu"],
        )

        def impl(ctx):
            return []

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )
        
        """.trimIndent()
        )

        assertThat(getConfiguredTarget("//test/starlark:test")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidInputKey() {
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        def transition_func(settings, attr):
            return {"//command_line_option:cpu": "k8"}

        my_transition = transition(
            implementation = transition_func,
            inputs = ["cpu"],
            outputs = ["//command_line_option:cpu"],
        )

        def impl(ctx):
            return []

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test/starlark:test")
        assertContainsEvent(
            "invalid transition input 'cpu'. If this is intended as a native option, "
                    + "it must begin with //command_line_option:"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidNativeOptionInput() {
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        def transition_func(settings, attr):
            return {"//command_line_option:cpu": "k8"}

        my_transition = transition(
            implementation = transition_func,
            inputs = ["//command_line_option:foop", "//command_line_option:barp"],
            outputs = ["//command_line_option:cpu"],
        )

        def impl(ctx):
            return []

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test/starlark:test")
        assertContainsEvent(
            "transition inputs [//command_line_option:barp, //command_line_option:foop] "
                    + "do not correspond to valid settings"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidNativeOptionOutput() {
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        def transition_func(settings, attr):
            return {"//command_line_option:foobarbaz": "k8"}

        my_transition = transition(
            implementation = transition_func,
            inputs = ["//command_line_option:cpu"],
            outputs = ["//command_line_option:foobarbaz"],
        )

        def impl(ctx):
            return []

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test/starlark:test")
        assertContainsEvent(
            "transition outputs [//command_line_option:foobarbaz] "
                    + "do not correspond to valid settings"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBannedNativeOptionOutput() {
        // Just picked an arbitrary incompatible_ flag; however, could be any flag
        // besides incompatible_enable_cc_toolchain_resolution (and might not even need to be real).
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        def transition_func(settings, attr):
            return {"//command_line_option:incompatible_merge_genfiles_directory": True}

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = ["//command_line_option:incompatible_merge_genfiles_directory"],
        )

        def impl(ctx):
            return []

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test/starlark:test")
        assertContainsEvent(
            "Invalid transition output '//command_line_option:incompatible_merge_genfiles_directory'. "
                    + "Cannot transition on --experimental_* or --incompatible_* options"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidOutputKey() {
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        def transition_func(settings, attr):
            return {"cpu": "k8"}

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = ["cpu"],
        )

        def impl(ctx):
            return []

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test/starlark:test")
        assertContainsEvent(
            "invalid transition output 'cpu'. If this is intended as a native option, "
                    + "it must begin with //command_line_option:"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidOptionValue() {
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        def transition_func(settings, attr):
            return {"//command_line_option:cpu": 1}

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = ["//command_line_option:cpu"],
        )

        def impl(ctx):
            return []

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test/starlark:test")
        assertContainsEvent("Invalid value type for option 'cpu'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicateOutputs() {
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        def transition_func(settings, attr):
            return {"//command_line_option:cpu": 1}

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = [
                "//command_line_option:cpu",
                "//command_line_option:foo",
                "//command_line_option:cpu",
            ],
        )

        def impl(ctx):
            return []

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test/starlark:test")
        assertContainsEvent("duplicate transition output '//command_line_option:cpu'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidNativeOptionOutput_analysisTest() {
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        my_transition = analysis_test_transition(
            settings = {"//command_line_option:foobarbaz": "k8"},
        )

        def impl(ctx):
            return []

        my_rule_test = rule(
            implementation = impl,
            analysis_test = True,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:my_rule.bzl", "my_rule_test")

        my_rule_test(
            name = "test",
            dep = ":main1",
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test/starlark:test")
        assertContainsEvent(
            "transition outputs [//command_line_option:foobarbaz] "
                    + "do not correspond to valid settings"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidOutputKey_analysisTest() {
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        my_transition = analysis_test_transition(
            settings = {"cpu": "k8"},
        )

        def impl(ctx):
            return []

        my_rule_test = rule(
            implementation = impl,
            analysis_test = True,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:my_rule.bzl", "my_rule_test")

        my_rule_test(
            name = "test",
            dep = ":main1",
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test/starlark:test")
        assertContainsEvent(
            "invalid transition output 'cpu'. If this is intended as a native option, "
                    + "it must begin with //command_line_option:"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun writeBuildSettingsBzl() {
        scratch.file(
            "test/starlark/build_settings.bzl",
            """
        BuildSettingInfo = provider(fields = ["value"])

        def _impl(ctx):
            return [BuildSettingInfo(value = ctx.build_setting_value)]

        int_flag = rule(implementation = _impl, build_setting = config.int(flag = True))
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun writeRulesWithAttrTransitionBzl() {
        scratch.file(
            "test/starlark/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test/starlark:build_settings.bzl", "BuildSettingInfo")

        def _transition_impl(settings, attr):
            return {"//test/starlark:the-answer": 42}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test/starlark:the-answer"],
        )

        def _rule_impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _rule_impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )

        def _dep_rule_impl(ctx):
            return [BuildSettingInfo(value = ctx.attr.fact[BuildSettingInfo].value)]

        dep_rule_impl = rule(
            implementation = _dep_rule_impl,
            attrs = {
                "fact": attr.label(default = "//test/starlark:the-answer"),
            },
        )
        
        """.trimIndent()
        )
    }

    private fun getCoreOptions(target: ConfiguredTarget): CoreOptions {
        return getConfiguration(target).getOptions().get(CoreOptions::class.java)
    }

    private fun getStarlarkOptions(target: ConfiguredTarget): com.google.common.collect.ImmutableMap<Label?, Any?> {
        return getConfiguration(target).getOptions().getStarlarkOptions()
    }

    private fun getStarlarkOption(target: ConfiguredTarget, absName: String?): Any? {
        return getStarlarkOptions(target).get(Label.parseCanonicalUnchecked(absName))
    }

    private fun getMnemonic(target: ConfiguredTarget): String {
        return getConfiguration(target).getMnemonic()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnBuildSetting_fromDefault() {
        writeBuildSettingsBzl()
        writeRulesWithAttrTransitionBzl()
        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:build_settings.bzl", "int_flag")
        load("//test/starlark:rules.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        my_rule(name = "dep")

        int_flag(
            name = "the-answer",
            build_setting_default = 0,
        )
        
        """.trimIndent()
        )

        val dep: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(getConfiguredTarget("//test/starlark:test")).getValue("dep") as MutableList<ConfiguredTarget?>?
            )
        assertThat(
            getConfiguration(dep)
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//test/starlark:the-answer"))
        )
            .isEqualTo(StarlarkInt.of(42))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnBuildSetting_fromCommandLine() {
        writeBuildSettingsBzl()
        writeRulesWithAttrTransitionBzl()
        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:build_settings.bzl", "int_flag")
        load("//test/starlark:rules.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        my_rule(name = "dep")

        int_flag(
            name = "the-answer",
            build_setting_default = 0,
        )
        
        """.trimIndent()
        )

        useConfiguration("--//test/starlark:the-answer=7")
        val test: ConfiguredTarget? = getConfiguredTarget("//test/starlark:test")
        assertThat(
            getConfiguration(test)
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//test/starlark:the-answer"))
        )
            .isEqualTo(StarlarkInt.of(7))

        val dep: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(test).getValue("dep") as MutableList<ConfiguredTarget?>?
            )
        assertThat(
            getConfiguration(dep)
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//test/starlark:the-answer"))
        )
            .isEqualTo(StarlarkInt.of(42))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionBackToStarlarkDefaultOK() {
        writeBuildSettingsBzl()
        scratch.file(
            "test/starlark/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _transition_impl(settings, attr):
            return {
                "//test/starlark:the-answer": attr.answer_for_dep,
                "//test/starlark:did-transition": 1,
            }

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test/starlark:the-answer", "//test/starlark:did-transition"],
        )

        def _rule_impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _rule_impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
                "answer_for_dep": attr.int(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:build_settings.bzl", "int_flag")
        load("//test/starlark:rules.bzl", "my_rule")

        my_rule(
            name = "test",
            answer_for_dep = 0,
            dep = ":dep1",
        )

        my_rule(
            name = "dep1",
            answer_for_dep = 42,
            dep = ":dep2",
        )

        my_rule(
            name = "dep2",
            answer_for_dep = 0,
            dep = ":dep3",
        )

        my_rule(name = "dep3")

        int_flag(
            name = "the-answer",
            build_setting_default = 0,
        )

        int_flag(
            name = "did-transition",
            build_setting_default = 0,
        )
        
        """.trimIndent()
        )
        useConfiguration("--cpu=FOO")

        val test: ConfiguredTarget? = getConfiguredTarget("//test/starlark:test")

        // '//test/starlark:did-transition ensures ST-hash is 'turned on' since :test has no ST-hash
        //   and thus will trivially have a unique getMnemonic
        val dep1: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(test).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        val dep2: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(dep1).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        val dep3: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(dep2).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        // These must be true
        Truth.assertThat(getMnemonic(dep1)).isNotEqualTo(getMnemonic(dep2))

        Truth.assertThat(getMnemonic(dep2)).isNotEqualTo(getMnemonic(dep3))

        Truth.assertThat(getMnemonic(dep1)).isEqualTo(getMnemonic(dep3))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnBuildSetting_onlyTransitionsAffectsDirectory() {
        writeBuildSettingsBzl()
        writeRulesWithAttrTransitionBzl()
        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:build_settings.bzl", "int_flag")
        load("//test/starlark:rules.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        my_rule(name = "dep")

        int_flag(
            name = "the-answer",
            build_setting_default = 0,
        )

        int_flag(
            name = "cmd-line-option",
            build_setting_default = 0,
        )
        
        """.trimIndent()
        )

        useConfiguration("--//test/starlark:cmd-line-option=100", "--compilation_mode=opt")

        val test: ConfiguredTarget? = getConfiguredTarget("//test/starlark:test")

        val dep: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(test).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        // Assert starlark option set via transition.
        Truth.assertThat(getStarlarkOption(dep, "//test/starlark:the-answer")).isEqualTo(StarlarkInt.of(42))

        // Assert starlark option set via command line.
        Truth.assertThat(getStarlarkOption(dep, "//test/starlark:cmd-line-option"))
            .isEqualTo(StarlarkInt.of(100))

        // Assert native option set via command line.
        assertThat(getCoreOptions(dep).getCompilationMode().toString()).isEqualTo("opt")

        // Assert that transitionDirectoryNameFragment is only affected by options
        // set via transitions. Not by native or starlark options set via command line,
        // never touched by any transition.
        Truth.assertThat(getMnemonic(dep))
            .endsWith(
                OutputPathMnemonicComputer.transitionDirectoryNameFragment(
                    com.google.common.collect.ImmutableList.of<E?>("//test/starlark:the-answer=42")
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnCompilationMode_hasNoHash() {
        writeBuildSettingsBzl()
        scratch.file(
            "test/starlark/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _transition_impl(settings, attr):
            return {
                "//command_line_option:compilation_mode": attr.cmode_for_dep,
            }

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//command_line_option:compilation_mode"],
        )

        def _rule_impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _rule_impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
                "cmode_for_dep": attr.string(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:build_settings.bzl", "int_flag")
        load("//test/starlark:rules.bzl", "my_rule")

        my_rule(
            name = "test",
            cmode_for_dep = "opt",
            dep = ":dep1",
        )

        my_rule(
            name = "dep1",
            cmode_for_dep = "fastbuild",
            dep = ":dep2",
        )

        my_rule(name = "dep2")
        
        """.trimIndent()
        )
        useConfiguration("--compilation_mode=fastbuild")

        val test: ConfiguredTarget? = getConfiguredTarget("//test/starlark:test")

        val dep1: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(test).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        val dep2: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(dep1).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        // Assert transitionDirectoryNameFragment is empty for all configurations
        Truth.assertThat(getMnemonic(test)).doesNotContain("-ST-")
        Truth.assertThat(getMnemonic(dep1)).doesNotContain("-ST-")
        Truth.assertThat(getMnemonic(dep2)).doesNotContain("-ST-")

        // test and dep1 should have different configurations b/c compilation_mode changed
        assertThat(getConfiguration(test)).isNotEqualTo(getConfiguration(dep1))

        // test and dep2 should have identical configurations
        assertThat(getConfiguration(test)).isEqualTo(getConfiguration(dep2))
    }

    @Throws(java.lang.Exception::class)
    private fun writeFilesWithMultipleNativeOptionTransitions() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _foo_impl(settings, attr):
            return {"//command_line_option:foo": "foosball"}

        foo_transition = transition(
            implementation = _foo_impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )

        def _bar_impl(settings, attr):
            return {"//command_line_option:bar": "barsball"}

        bar_transition = transition(
            implementation = _bar_impl,
            inputs = [],
            outputs = ["//command_line_option:bar"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "bar_transition", "foo_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            cfg = foo_transition,
            attrs = {
                "dep": attr.label(cfg = bar_transition),
            },
        )

        def _basic_impl(ctx):
            return []

        simple = rule(_basic_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule", "simple")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple(name = "dep")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputDirHash_multipleNativeOptionTransitions() {
        writeFilesWithMultipleNativeOptionTransitions()
        val test: ConfiguredTarget? = getConfiguredTarget("//test")

        val dep: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(test).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        Truth.assertThat(getMnemonic(test))
            .endsWith(
                OutputPathMnemonicComputer.transitionDirectoryNameFragment(
                    com.google.common.collect.ImmutableList.of<E?>("//command_line_option:foo=foosball")
                )
            )

        Truth.assertThat(getMnemonic(dep))
            .endsWith(
                OutputPathMnemonicComputer.transitionDirectoryNameFragment(
                    com.google.common.collect.ImmutableList.of<E?>(
                        "//command_line_option:bar=barsball", "//command_line_option:foo=foosball"
                    )
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputDirHash_onlyExec() {
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = "exec"),
            },
        )

        def _basic_impl(ctx):
            return []

        simple = rule(_basic_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule", "simple")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple(name = "dep")
        
        """.trimIndent()
        )

        val test: ConfiguredTarget? = getConfiguredTarget("//test")
        val dep: ConfiguredTarget = getMyInfoFromTarget(test).getValue("dep") as ConfiguredTarget

        Truth.assertThat(getMnemonic(test)).doesNotContain("-ST-")

        // Until platforms is EXPLICIT_IN_OUTPUT_PATH, it will change here as well.
        // But, nothing else should be different.
        Truth.assertThat(getMnemonic(dep)).endsWith("-exec")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputDirHash_starlarkRevertedByExec() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _some_impl(settings, attr):
            return {"//command_line_option:copt": ["set_by_test_target"]}

        some_transition = transition(
            implementation = _some_impl,
            inputs = [],
            outputs = ["//command_line_option:copt"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "some_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            cfg = some_transition,
            attrs = {
                "dep": attr.label(cfg = "exec"),
            },
        )

        def _basic_impl(ctx):
            return []

        simple = rule(_basic_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule", "simple")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple(name = "dep")
        
        """.trimIndent()
        )

        useConfiguration("--copt=toplevel_copt")
        val test: ConfiguredTarget? = getConfiguredTarget("//test")

        val dep: ConfiguredTarget = getMyInfoFromTarget(test).getValue("dep") as ConfiguredTarget

        Truth.assertThat(getMnemonic(test))
            .endsWith(
                OutputPathMnemonicComputer.transitionDirectoryNameFragment(
                    com.google.common.collect.ImmutableList.of<E?>("//command_line_option:copt=[set_by_test_target]")
                )
            )
        // Sanity check: the exec-configured value is indeed unique vs. both the target-transitioned
        // value and the top-level config.
        assertThat(test.getConfigurationKey().getOptions().get(CppOptions::class.java).getCoptList())
            .isNotEqualTo(dep.getConfigurationKey().getOptions().get(CppOptions::class.java).getCoptList())
        assertThat(getTargetConfiguration().getOptions().get(CppOptions::class.java).getCoptList())
            .isNotEqualTo(dep.getConfigurationKey().getOptions().get(CppOptions::class.java).getCoptList())
        Truth.assertThat(getMnemonic(dep)).endsWith("-exec")
    }

    // Test that a no-op starlark transition to an already starlark transitioned configuration
    // results in the same configuration.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputDirHash_noop_changeToSameState() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _bar_impl(settings, attr):
            return {"//test:bar": "barsball"}

        bar_transition = transition(
            implementation = _bar_impl,
            inputs = [],
            outputs = ["//test:bar"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "bar_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            cfg = bar_transition,
            attrs = {
                "dep": attr.label(cfg = bar_transition),
            },
        )

        def _basic_impl(ctx):
            return []

        string_flag = rule(
            implementation = _basic_impl,
            build_setting = config.string(flag = True),
        )
        simple = rule(_basic_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule", "simple", "string_flag")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple(name = "dep")

        string_flag(
            name = "bar",
            build_setting_default = "",
        )
        
        """.trimIndent()
        )

        val test: ConfiguredTarget? = getConfiguredTarget("//test")

        val dep: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(test).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        Truth.assertThat(getMnemonic(test)).isEqualTo(getMnemonic(dep))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputDirHash_noop_emptyReturns() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _bar_impl(settings, attr):
            return {}

        bar_transition = transition(
            implementation = _bar_impl,
            inputs = [],
            outputs = [],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "bar_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            cfg = bar_transition,
            attrs = {
                "dep": attr.label(cfg = bar_transition),
            },
        )

        def _basic_impl(ctx):
            return []

        simple = rule(_basic_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule", "simple")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple(name = "dep")
        
        """.trimIndent()
        )

        val test: ConfiguredTarget? = getConfiguredTarget("//test")

        val dep: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(test).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        Truth.assertThat(getMnemonic(test)).isEqualTo(getMnemonic(dep))
    }

    // Test that setting all starlark options back to default != null hash of top level.
    // We could set some starlark options on the command line but we don't count this as a starlark
    // transition to the command line configuration will always have a null values for
    // {@code transitionDirectoryNameFragment}.
    //
    // e.g. for a build setting //foo whose default value is "foop" the following sequence
    //
    // (CommandLine) //foo=blah -> (StarlarkTransition) //foo=foop
    //
    // must create a non-null hash for after the StarlarkTransition even though later on we empty
    // the default out of the starlark map (In StarlarkTransition#validate)
    // TODO(bazel-team): This can be optimized. Make these the same configuration.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputDirHash_multipleStarlarkOptionTransitions_backToDefaultCommandLine() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _foo_two_impl(settings, attr):
            return {"//test:foo": "foosballerina"}

        foo_two_transition = transition(
            implementation = _foo_two_impl,
            inputs = [],
            outputs = ["//test:foo"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "foo_two_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = foo_two_transition),
            },
        )

        def _basic_impl(ctx):
            return []

        string_flag = rule(
            implementation = _basic_impl,
            build_setting = config.string(flag = True),
        )
        simple = rule(_basic_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule", "simple", "string_flag")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple(name = "dep")

        string_flag(
            name = "foo",
            build_setting_default = "foosballerina",
        )
        
        """.trimIndent()
        )

        useConfiguration("--//test:foo=foosball")

        val dep: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(getConfiguredTarget("//test")).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        Truth.assertThat(getMnemonic(dep)).contains("-ST-")
    }

    /** See comment above [FunctionTransitionUtil.updateOutputDirectoryNameFragment]  */ // TODO(bazel-team): This can be optimized. Make these the same configuration.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputDirHash_starlarkOption_differentBoolRepresentationsNotEquals() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _foo_impl(settings, attr):
            return {"//test:foo": 1}

        foo_transition = transition(
            implementation = _foo_impl,
            inputs = [],
            outputs = ["//test:foo"],
        )

        def _foo_two_impl(settings, attr):
            return {"//test:foo": True}

        foo_two_transition = transition(
            implementation = _foo_two_impl,
            inputs = [],
            outputs = ["//test:foo"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "foo_transition", "foo_two_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            cfg = foo_transition,
            attrs = {
                "dep": attr.label(cfg = foo_two_transition),
            },
        )

        def _basic_impl(ctx):
            return []

        bool_flag = rule(
            implementation = _basic_impl,
            build_setting = config.bool(flag = True),
        )
        simple = rule(_basic_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "bool_flag", "my_rule", "simple")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple(name = "dep")

        bool_flag(
            name = "foo",
            build_setting_default = False,
        )
        
        """.trimIndent()
        )

        val test: ConfiguredTarget? = getConfiguredTarget("//test")

        val dep: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(test).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        Truth.assertThat(getMnemonic(test))
            .endsWith(
                OutputPathMnemonicComputer.transitionDirectoryNameFragment(
                    com.google.common.collect.ImmutableList.of<E?>("//test:foo=1")
                )
            )
        Truth.assertThat(getMnemonic(dep))
            .endsWith(
                OutputPathMnemonicComputer.transitionDirectoryNameFragment(
                    com.google.common.collect.ImmutableList.of<E?>("//test:foo=true")
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputDirHash_nativeOption_differentBoolRepresentationsEquals() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _bool_impl(settings, attr):
            return {"//command_line_option:bool": "1"}

        bool_transition = transition(
            implementation = _bool_impl,
            inputs = [],
            outputs = ["//command_line_option:bool"],
        )

        def _bool_two_impl(settings, attr):
            return {"//command_line_option:bool": "true"}

        bool_two_transition = transition(
            implementation = _bool_two_impl,
            inputs = [],
            outputs = ["//command_line_option:bool"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "bool_transition", "bool_two_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            cfg = bool_transition,
            attrs = {
                "dep": attr.label(cfg = bool_two_transition),
            },
        )

        def _basic_impl(ctx):
            return []

        simple = rule(_basic_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule", "simple")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple(name = "dep")
        
        """.trimIndent()
        )

        val test: ConfiguredTarget? = getConfiguredTarget("//test")

        val dep: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(test).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        Truth.assertThat(getMnemonic(test)).isEqualTo(getMnemonic(dep))
    }

    @Throws(java.lang.Exception::class)
    private fun writeFilesWithMultipleStarlarkTransitions() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _foo_impl(settings, attr):
            return {"//test:foo": "foosball"}

        foo_transition = transition(
            implementation = _foo_impl,
            inputs = [],
            outputs = ["//test:foo"],
        )

        def _bar_impl(settings, attr):
            return {"//test:bar": "barsball"}

        bar_transition = transition(
            implementation = _bar_impl,
            inputs = [],
            outputs = ["//test:bar"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "bar_transition", "foo_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            cfg = foo_transition,
            attrs = {
                "dep": attr.label(cfg = bar_transition),
            },
        )

        def _basic_impl(ctx):
            return []

        string_flag = rule(
            implementation = _basic_impl,
            build_setting = config.string(flag = True),
        )
        simple = rule(_basic_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule", "simple", "string_flag")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple(name = "dep")

        string_flag(
            name = "foo",
            build_setting_default = "",
        )

        string_flag(
            name = "bar",
            build_setting_default = "",
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputDirHash_multipleStarlarkTransitions() {
        writeFilesWithMultipleStarlarkTransitions()
        val test: ConfiguredTarget? = getConfiguredTarget("//test")

        val dep: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(test).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        Truth.assertThat(getMnemonic(test))
            .endsWith(
                OutputPathMnemonicComputer.transitionDirectoryNameFragment(
                    com.google.common.collect.ImmutableList.of<E?>("//test:foo=foosball")
                )
            )
        Truth.assertThat(getMnemonic(dep))
            .endsWith(
                OutputPathMnemonicComputer.transitionDirectoryNameFragment(
                    com.google.common.collect.ImmutableList.of<E?>("//test:bar=barsball", "//test:foo=foosball")
                )
            )
    }

    @Throws(java.lang.Exception::class)
    private fun writeFilesWithMultipleMixedTransitions() {
        scratch.file(
            "test/transitions.bzl",
            """
        def _foo_impl(settings, attr):
            return {"//command_line_option:foo": "foosball"}

        foo_transition = transition(
            implementation = _foo_impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )

        def _bar_impl(settings, attr):
            return {"//command_line_option:bar": "barsball"}

        bar_transition = transition(
            implementation = _bar_impl,
            inputs = [],
            outputs = ["//command_line_option:bar"],
        )

        def _zee_impl(settings, attr):
            return {"//test:zee": "zeesball"}

        zee_transition = transition(
            implementation = _zee_impl,
            inputs = [],
            outputs = ["//test:zee"],
        )

        def _xan_impl(settings, attr):
            return {"//test:xan": "xansball"}

        xan_transition = transition(
            implementation = _xan_impl,
            inputs = [],
            outputs = ["//test:xan"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load(
            "//test:transitions.bzl",
            "bar_transition",
            "foo_transition",
            "xan_transition",
            "zee_transition",
        )

        def _impl_a(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule_a = rule(
            implementation = _impl_a,
            cfg = foo_transition,  # transition #1
            attrs = {
                # transition #2
                "dep": attr.label(cfg = zee_transition),
            },
        )

        def _impl_b(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule_b = rule(
            implementation = _impl_b,
            cfg = bar_transition,  # transition #3
            attrs = {
                # transition #4
                "dep": attr.label(cfg = xan_transition),
            },
        )

        def _basic_impl(ctx):
            return []

        string_flag = rule(
            implementation = _basic_impl,
            build_setting = config.string(flag = True),
        )
        simple = rule(_basic_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule_a", "my_rule_b", "simple", "string_flag")

        my_rule_a(
            name = "top",
            dep = ":middle",
        )

        my_rule_b(
            name = "middle",
            dep = "bottom",
        )

        simple(name = "bottom")

        string_flag(
            name = "zee",
            build_setting_default = "",
        )

        string_flag(
            name = "xan",
            build_setting_default = "",
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputDirHash_multipleMixedTransitions() {
        writeFilesWithMultipleMixedTransitions()

        // test:top (foo_transition)
        val top: ConfiguredTarget? = getConfiguredTarget("//test:top")

        assertThat(getConfiguration(top).getOptions().getStarlarkOptions()).isEmpty()
        Truth.assertThat(getMnemonic(top))
            .endsWith(
                OutputPathMnemonicComputer.transitionDirectoryNameFragment(
                    com.google.common.collect.ImmutableList.of<E?>("//command_line_option:foo=foosball")
                )
            )

        // test:middle (foo_transition, zee_transition, bar_transition)
        val middle: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(getMyInfoFromTarget(top).getValue("dep") as MutableList<ConfiguredTarget?>?)

        assertThat(getConfiguration(middle).getOptions().getStarlarkOptions().entrySet())
            .containsExactly(
                com.google.common.collect.Maps.immutableEntry<K?, V?>(
                    Label.parseCanonicalUnchecked("//test:zee"),
                    "zeesball"
                )
            )

        Truth.assertThat(getMnemonic(middle))
            .endsWith(
                OutputPathMnemonicComputer.transitionDirectoryNameFragment(
                    com.google.common.collect.ImmutableList.of<E?>(
                        "//command_line_option:bar=barsball",
                        "//command_line_option:foo=foosball",
                        "//test:zee=zeesball"
                    )
                )
            )

        // test:bottom (foo_transition, zee_transition, bar_transition, xan_transition)
        val bottom: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(middle).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        assertThat(getConfiguration(bottom).getOptions().getStarlarkOptions().entrySet())
            .containsExactly(
                com.google.common.collect.Maps.immutableEntry<K?, V?>(
                    Label.parseCanonicalUnchecked("//test:zee"),
                    "zeesball"
                ),
                com.google.common.collect.Maps.immutableEntry<K?, V?>(
                    Label.parseCanonicalUnchecked("//test:xan"),
                    "xansball"
                )
            )
        Truth.assertThat(getMnemonic(bottom))
            .endsWith(
                OutputPathMnemonicComputer.transitionDirectoryNameFragment(
                    com.google.common.collect.ImmutableList.of<E?>(
                        "//command_line_option:bar=barsball", "//command_line_option:foo=foosball",
                        "//test:xan=xansball", "//test:zee=zeesball"
                    )
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnBuildSetting_badValue() {
        writeBuildSettingsBzl()
        scratch.file(
            "test/starlark/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test/starlark:build_settings.bzl", "BuildSettingInfo")

        def _transition_impl(settings, attr):
            return {"//test/starlark:the-answer": "What do you get if you multiply six by nine?"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test/starlark:the-answer"],
        )

        def _rule_impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _rule_impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )

        def _dep_rule_impl(ctx):
            return [BuildSettingInfo(value = ctx.attr.fact[BuildSettingInfo].value)]

        dep_rule_impl = rule(
            implementation = _dep_rule_impl,
            attrs = {
                "fact": attr.label(default = "//test/starlark:the-answer"),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:build_settings.bzl", "int_flag")
        load("//test/starlark:rules.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        my_rule(name = "dep")

        int_flag(
            name = "the-answer",
            build_setting_default = 0,
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test/starlark:test")
        assertContainsEvent(
            "expected value of type 'int' for //test/starlark:the-answer, "
                    + "but got \"What do you get if you multiply six by nine?\" (string)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnBuildSetting_noSuchTarget() {
        writeRulesWithAttrTransitionBzl()
        // Still need to write this file in order not to rewrite rules.bzl file (has loads from this
        // file)
        writeBuildSettingsBzl()
        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:rules.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        my_rule(name = "dep")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test/starlark:test")
        assertContainsEvent(
            "no such target '//test/starlark:the-answer': target "
                    + "'the-answer' not declared in package"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionOnBuildSetting_notABuildSetting() {
        writeRulesWithAttrTransitionBzl()
        scratch.file(
            "test/starlark/build_settings.bzl",
            """
        BuildSettingInfo = provider(fields = ["value"])

        def _impl(ctx):
            return [BuildSettingInfo(value = ctx.build_setting_value)]

        int_flag = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/starlark/BUILD",
            """
        load("//test/starlark:build_settings.bzl", "int_flag")
        load("//test/starlark:rules.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        my_rule(name = "dep")

        int_flag(name = "the-answer")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test/starlark:test")
        assertContainsEvent(
            "attempting to transition on '//test/starlark:the-answer' which "
                    + "is not a build setting"
        )
    }

    /**
     * Regression test for b/147245129.
     * 
     * 
     * This tests that when exec transitions are applied from target configurations that are
     * identical except for different Starlark flags, outputs do not conflict.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildSettingTransitionsWorkWithExecTransitions() {
        // This setup creates an int_flag_reading_rule whose output is the value of an int_flag (which
        // guarantees actions in configurations with different Starlark flag values are different). It
        // then makes this a genrule exec tool (so it applies after an exec transition). And finally
        // creates a build_setting_changing_rule that changes the int_flag's value and depends on the
        // genrule. So building the genrule at both the top-level and under the
        // build_setting_changing_rule triggers the test scenario.
        scratch.file(
            "test/starlark/rules.bzl",
            """
        BuildSettingInfo = provider(fields = ["value"])

        def _impl(ctx):
            return [BuildSettingInfo(value = ctx.build_setting_value)]

        int_flag = rule(implementation = _impl, build_setting = config.int())

        def _transition_impl(settings, attr):
            return {"//test/starlark:the-answer": 42}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test/starlark:the-answer"],
        )

        def _int_impl(ctx):
            value = ctx.attr._int_dep[BuildSettingInfo].value
            ctx.actions.write(ctx.outputs.out, str(value))

        int_flag_reading_rule = rule(
            implementation = _int_impl,
            attrs = {
                "_int_dep": attr.label(default = "//test/starlark:the-answer"),
                "out": attr.output(),
            },
        )

        def _rule_impl(ctx):
            pass

        build_setting_changing_rule = rule(
            implementation = _rule_impl,
            attrs = {
                "dep": attr.label(cfg = my_transition, allow_single_file = True),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/starlark/BUILD",
            """
        load(
            "//test/starlark:rules.bzl",
            "build_setting_changing_rule",
            "int_flag",
            "int_flag_reading_rule",
        )

        int_flag(
            name = "the-answer",
            build_setting_default = 0,
        )

        genrule(
            name = "with_exec_tool",
            srcs = [],
            outs = ["with_exec_tool.out"],
            cmd = "echo hi > ${'$'}@",
            tools = [":int_reader"],
        )

        int_flag_reading_rule(
            name = "int_reader",
            out = "int_reader.out",
        )

        build_setting_changing_rule(
            name = "transitioner",
            dep = ":with_exec_tool",
        )
        
        """.trimIndent()
        )
        // Note: calling getConfiguredTarget for each target doesn't activate conflict detection.
        update(
            com.google.common.collect.ImmutableList.of<String?>(
                "//test/starlark:transitioner",
                "//test/starlark:with_exec_tool.out"
            ),  /*keepGoing=*/
            false,
            BuildViewTestCase.Companion.LOADING_PHASE_THREADS,  /*doAnalysis=*/
            true,
            com.google.common.eventbus.EventBus()
        )
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkSplitTransitionRequiredFragments() {
        // All Starlark rule transitions are patch transitions, while all Starlark attribute transitions
        // are split transitions.
        scratch.file(
            "test/my_rule.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def transition_func(settings, attr):
            return [
                # --copt is a C++ option.
                {"//command_line_option:copt": []},
            ]

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = ["//command_line_option:copt"],
        )

        def impl(ctx):
            return []

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("//test:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        cc_library(
            name = "dep",
            srcs = ["dep.c"],
        )
        
        """.trimIndent()
        )

        val ct: ConfiguredTargetAndData = getConfiguredTargetAndData("//test")
        assertNoEvents()
        val attributes: ConfiguredAttributeMapper = ct.getAttributeMapperForTesting()
        val attrTransition: ConfigurationTransition =
            attributes
                .getAttributeDefinition("dep")
                .getTransitionFactory()
                .create(AttributeTransitionData.builder().attributes(attributes).build())
        val requiredFragments: RequiredConfigFragmentsProvider.Builder =
            RequiredConfigFragmentsProvider.builder()
        attrTransition.addRequiredFragments(
            requiredFragments, ct.getConfiguration().getBuildOptionDetails()
        )
        assertThat(requiredFragments.build().optionsClasses()).containsExactly(CppOptions::class.java)
    }

    /**
     * @param directRead if set to true, reads the output value directly from the input dict, else
     * just passes in the same value as a string
     */
    @Throws(java.lang.Exception::class)
    private fun testNoOpTransitionLeavesSameConfigNative(directRead: Boolean) {
        val outputValue = if (directRead) "settings['//command_line_option:foo']" else "'frisbee'"
        val inputs = if (directRead) "['//command_line_option:foo']" else "[]"

        scratch.file(
            "test/defs.bzl",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _transition_impl(settings, attr):",
            "  return {'//command_line_option:foo': " + outputValue + "}",
            "my_transition = transition(",
            "  implementation = _transition_impl,",
            "  inputs = " + inputs + ",",
            "  outputs = ['//command_line_option:foo'],",
            ")",
            "def _impl(ctx):",
            "  return MyInfo(dep = ctx.attr.dep)",
            "my_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    'dep': attr.label(cfg = my_transition),",
            "  })"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        my_rule(name = "dep")
        
        """.trimIndent()
        )

        useConfiguration("--foo=frisbee")
        val test: ConfiguredTarget? = getConfiguredTarget("//test")

        val dep: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(test).getValue("dep") as MutableList<ConfiguredTarget?>?
            )
        Truth.assertThat(getMnemonic(test)).isEqualTo(getMnemonic(dep))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoOpTransitionLeavesSameConfig_native_directRead() {
        testNoOpTransitionLeavesSameConfigNative(true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoOpTransitionLeavesSameConfig_native_setToSame() {
        testNoOpTransitionLeavesSameConfigNative(false)
    }

    /**
     * @param directRead if set to true, reads the output value directly from the input dict, else
     * just passes in the same value as a string
     * @param setToDefault if set to true, value getting passed through the transition is the default
     * value of the build settings. Internally we don't keep default values in the build settings
     * map inside [BuildOptions] so it's nice to test this separately.
     */
    @Throws(java.lang.Exception::class)
    private fun testNoOpTransitionLeavesSameConfig_starlark(directRead: Boolean, setToDefault: Boolean) {
        val outputValue = if (directRead) "settings['//test:flag']" else "'frisbee'"
        val inputs = if (directRead) "['//test:flag']" else "[]"
        val buildSettingsDefault = if (setToDefault) "frisbee" else "waterpolo"

        scratch.file(
            "test/defs.bzl",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _flag_impl(ctx):",
            "  return []",
            "my_flag = rule(",
            "  implementation = _flag_impl,",
            "  build_setting = config.string(flag = True)",
            ")",
            "def _transition_impl(settings, attr):",
            "  return {'//test:flag': " + outputValue + "}",
            "my_transition = transition(",
            "  implementation = _transition_impl,",
            "  inputs = " + inputs + ",",
            "  outputs = ['//test:flag'],",
            ")",
            "def _impl(ctx):",
            "  return MyInfo(dep = ctx.attr.dep)",
            "my_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    'dep': attr.label(cfg = my_transition),",
            "  })"
        )
        scratch.file(
            "test/BUILD",
            "load('//test:defs.bzl', 'my_rule', 'my_flag')",
            "my_rule(name = 'test', dep = ':dep')",
            "my_rule(name = 'dep')",
            "my_flag(name = 'flag', build_setting_default = '" + buildSettingsDefault + "')"
        )

        // TODO(blaze-configurability-team): There is a bug in BuildViewTestCase that it does not audit
        //   these Starlark options at all (i.e. check they are the right type or that values at default
        //   are unset/set to null rather than explicitly set to the default.
        if (buildSettingsDefault != "frisbee") {
            useConfiguration("--//test:flag=frisbee")
        }
        val test: ConfiguredTarget? = getConfiguredTarget("//test")

        val dep: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(test).getValue("dep") as MutableList<ConfiguredTarget?>?
            )
        Truth.assertThat(getMnemonic(test)).isEqualTo(getMnemonic(dep))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoOpTransitionLeavesSameConfig_starlark_directRead() {
        testNoOpTransitionLeavesSameConfig_starlark(true, false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoOpTransitionLeavesSameConfig_starlark_setToSame() {
        testNoOpTransitionLeavesSameConfig_starlark(false, false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoOpTransitionLeavesSameConfig_starlark_setToDefault() {
        testNoOpTransitionLeavesSameConfig_starlark(false, true)
    }

    @org.junit.Test
    fun testOptionConversionDynamicMode() {
        // TODO(waltl): check that dynamic_mode is parsed properly.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOptionConversionCrosstoolTop() {
        // TODO(waltl): check that crosstool_top is parsed properly.
    }

    /**
     * Changing --cpu implicitly changes the target platform. Test that the old value of --platforms
     * gets cleared out (platform mappings can then kick in to set --platforms correctly).
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplicitPlatformsChange() {
        getAnalysisMock().ccSupport().setupCcToolchainConfigForCpu(mockToolsConfig, "armeabi-v7a")
        scratch.file("platforms/BUILD", "platform(name = 'my_platform', constraint_values = [])")
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        def transition_func(settings, attr):
            return {"//command_line_option:cpu": "armeabi-v7a"}

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = ["//command_line_option:cpu"],
        )

        def impl(ctx):
            return []

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/starlark/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:my_platform")
        val dep: ConfiguredTarget =
            getDirectPrerequisite(getConfiguredTarget("//test/starlark:test"), "//test/starlark:main1")
        // When --platforms is empty and no platform mapping triggers, PlatformMappingValue sets
        // --platforms to PlatformOptions.computeTargetPlatform(), which defaults to the host.
        assertThat(getConfiguration(dep).getOptions().get(PlatformOptions::class.java).getPlatforms())
            .containsExactly(Label.parseCanonicalUnchecked(TestConstants.PLATFORM_LABEL))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExplicitPlatformsChange() {
        getAnalysisMock().ccSupport().setupCcToolchainConfigForCpu(mockToolsConfig, "armeabi-v7a")
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
            "test/starlark/my_rule.bzl",
            """
        def transition_func(settings, attr):
            return {
                "//command_line_option:cpu": "armeabi-v7a",
                "//command_line_option:platforms": ["//platforms:my_other_platform"],
            }

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = [
                "//command_line_option:cpu",
                "//command_line_option:platforms",
            ],
        )

        def impl(ctx):
            return []

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/starlark/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:my_platform")
        val dep: ConfiguredTarget =
            getDirectPrerequisite(getConfiguredTarget("//test/starlark:test"), "//test/starlark:main1")
        assertThat(getConfiguration(dep).getOptions().get(PlatformOptions::class.java).getPlatforms())
            .containsExactly(Label.parseCanonicalUnchecked("//platforms:my_other_platform"))
    }

    /* If the transition doesn't change --cpu, it doesn't constitute a platform change. */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoPlatformChange() {
        scratch.file(
            "platforms/BUILD",
            "platform(name = 'my_platform',",
            "    parents = ['" + TestConstants.PLATFORM_LABEL + "'],",
            "    constraint_values = [],",
            ")"
        )
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        def transition_func(settings, attr):
            return {
                "//command_line_option:foo": "blah",
            }

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = [
                "//command_line_option:foo",
            ],
        )

        def impl(ctx):
            return []

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/starlark/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:my_platform")
        val dep: ConfiguredTarget =
            getDirectPrerequisite(getConfiguredTarget("//test/starlark:test"), "//test/starlark:main1")
        assertThat(getConfiguration(dep).getOptions().get(PlatformOptions::class.java).getPlatforms())
            .containsExactly(Label.parseCanonicalUnchecked("//platforms:my_platform"))
    }

    /*
   * If the transition claims to change --cpu but doesn't, it doesn't constitute a platform change
   * and also doesn't affect any other options.
   */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCpuNoOpChangeIsFullyNoOp() {
        scratch.file(
            "platforms/BUILD",
            "platform(name = 'my_platform',",
            "    parents = ['" + TestConstants.PLATFORM_LABEL + "'],",
            "    constraint_values = [],",
            ")"
        )
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        def transition_func(settings, attr):
            # Leave --cpu unchanged, but still trigger the full transition logic that would be
            # bypassed by returning {}.
            return settings

        my_transition = transition(
            implementation = transition_func,
            inputs = [
                "//command_line_option:cpu",
            ],
            outputs = [
                "//command_line_option:cpu",
            ],
        )

        def impl(ctx):
            return []

        my_rule = rule(
            implementation = impl,
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/starlark/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )
        
        """.trimIndent()
        )

        val topLevel: ConfiguredTarget? = getConfiguredTarget("//test/starlark:test")
        val dep: ConfiguredTarget =
            getDirectPrerequisite(getConfiguredTarget("//test/starlark:test"), "//test/starlark:main1")
        assertThat(getConfiguration(dep).getOptions())
            .isEqualTo(getConfiguration(topLevel).getOptions())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEffectiveNoopTransitionTrimsInputBuildSettings() {
        scratch.file(
            "test/starlark/rules.bzl",
            """
        def _string_impl(ctx):
            return []

        string_flag = rule(
            implementation = _string_impl,
            build_setting = config.string(flag = True),
        )

        def _no_op_transition_impl(settings, attr):
            return {
                "//test/starlark:input_and_output": settings["//test/starlark:input_and_output"],
                "//test/starlark:output_only": "output_only_default",
            }

        _no_op_transition = transition(
            implementation = _no_op_transition_impl,
            inputs = [
                "//test/starlark:input_only",
                "//test/starlark:input_and_output",
            ],
            outputs = [
                "//test/starlark:input_and_output",
                "//test/starlark:output_only",
            ],
        )

        def _apply_transition_impl(ctx):
            ctx.actions.symlink(
                output = ctx.outputs.out,
                target_file = ctx.file.target,
            )
            return [DefaultInfo(executable = ctx.outputs.out)]

        apply_transition = rule(
            implementation = _apply_transition_impl,
            attrs = {
                "target": attr.label(
                    cfg = _no_op_transition,
                    allow_single_file = True,
                ),
                "out": attr.output(),
            },
            executable = False,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/starlark/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//test/starlark:rules.bzl", "apply_transition", "string_flag")

        string_flag(
            name = "input_only",
            build_setting_default = "input_only_default",
        )

        string_flag(
            name = "input_and_output",
            build_setting_default = "input_and_output_default",
        )

        string_flag(
            name = "output_only",
            build_setting_default = "output_only_default",
        )

        cc_binary(
            name = "main",
            srcs = ["main.cc"],
        )

        apply_transition(
            name = "transitioned_main",
            out = "main_out",
            target = ":main",
        )
        
        """.trimIndent()
        )

        update(
            com.google.common.collect.ImmutableList.of<String?>(
                "//test/starlark:main",
                "//test/starlark:transitioned_main"
            ),  /*keepGoing=*/
            false,
            BuildViewTestCase.Companion.LOADING_PHASE_THREADS,  /*doAnalysis=*/
            true,
            com.google.common.eventbus.EventBus()
        )
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExplicitNoopTransitionTrimsInputBuildSettings() {
        scratch.file(
            "test/starlark/rules.bzl",
            """
        def _string_impl(ctx):
            return []

        string_flag = rule(
            implementation = _string_impl,
            build_setting = config.string(flag = True),
        )

        def _no_op_transition_impl(settings, attr):
            return {}

        _no_op_transition = transition(
            implementation = _no_op_transition_impl,
            inputs = [
                "//test/starlark:input_only",
                "//test/starlark:input_and_output",
            ],
            outputs = [
                "//test/starlark:input_and_output",
                "//test/starlark:output_only",
            ],
        )

        def _apply_transition_impl(ctx):
            ctx.actions.symlink(
                output = ctx.outputs.out,
                target_file = ctx.file.target,
            )
            return [DefaultInfo(executable = ctx.outputs.out)]

        apply_transition = rule(
            implementation = _apply_transition_impl,
            attrs = {
                "target": attr.label(
                    cfg = _no_op_transition,
                    allow_single_file = True,
                ),
                "out": attr.output(),
            },
            executable = False,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/starlark/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//test/starlark:rules.bzl", "apply_transition", "string_flag")

        string_flag(
            name = "input_only",
            build_setting_default = "input_only_default",
        )

        string_flag(
            name = "input_and_output",
            build_setting_default = "input_and_output_default",
        )

        string_flag(
            name = "output_only",
            build_setting_default = "output_only_default",
        )

        cc_binary(
            name = "main",
            srcs = ["main.cc"],
        )

        apply_transition(
            name = "transitioned_main",
            out = "main_out",
            target = ":main",
        )
        
        """.trimIndent()
        )

        update(
            com.google.common.collect.ImmutableList.of<String?>(
                "//test/starlark:main",
                "//test/starlark:transitioned_main"
            ),  /*keepGoing=*/
            false,
            BuildViewTestCase.Companion.LOADING_PHASE_THREADS,  /*doAnalysis=*/
            true,
            com.google.common.eventbus.EventBus()
        )
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionPreservesAllowMultipleDefault() {
        scratch.file(
            "test/starlark/rules.bzl",
            """
        P = provider(fields = ["value"])

        def _s_impl(ctx):
            return [P(value = ctx.build_setting_value)]

        def _t_impl(settings, attr):
            if "foo" in settings["//test/starlark:a"]:
                return {"//test/starlark:b": ["bar"]}
            else:
                return {"//test/starlark:b": ["baz"]}

        def _r_impl(ctx):
            pass

        s = rule(
            implementation = _s_impl,
            build_setting = config.string(allow_multiple = True, flag = True),
        )
        t = transition(
            implementation = _t_impl,
            inputs = ["//test/starlark:a"],
            outputs = ["//test/starlark:b"],
        )
        r = rule(
            implementation = _r_impl,
            attrs = {
                "setting": attr.label(cfg = t),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/starlark/BUILD",
            """
        load(":rules.bzl", "r", "s")

        s(
            name = "a",
            build_setting_default = "",
        )

        s(
            name = "b",
            build_setting_default = "",
        )

        r(
            name = "c",
            setting = ":b",
        )
        
        """.trimIndent()
        )
        update(
            com.google.common.collect.ImmutableList.of<String?>("//test/starlark:c"),  /* keepGoing= */
            false,
            BuildViewTestCase.Companion.LOADING_PHASE_THREADS,  /* doAnalysis= */
            true,
            com.google.common.eventbus.EventBus()
        )
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allowMultipleNativeOptionWithEnvVarConverter() {
        // Added to support --action_env and --host_action_env.
        scratch.file(
            "test/rules.bzl",
            "def _t_impl(settings, attr):",
            "    return {",
            "        '//command_line_option:allow_multiple_with_env_var_converter':",
            "        ['a=1', 'b=2', 'c'] }",
            "t = transition(",
            "    implementation = _t_impl,",
            "    inputs = [],",
            "    outputs =" + " ['//command_line_option:allow_multiple_with_env_var_converter'],",
            ")",
            "r = rule(",
            "    implementation = lambda ctx: [],",
            "    attrs = {",
            "        'dep': attr.label(cfg = t),",
            "    },",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load(":rules.bzl", "r")

        r(name = "dep")

        r(
            name = "c",
            dep = ":dep",
        )
        
        """.trimIndent()
        )

        // See CoreOptions.actionEnvironment for this option's parsing semantics.
        assertThat(
            getDirectPrerequisite(getConfiguredTarget("//test:c"), "//test:dep")
                .getConfigurationKey()
                .getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getAllowMultipleWithEnvVarConverter()
        )
            .containsExactly(
                Set("a", "1"), Set("b", "2"), Inherit("c")
            )
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allowMultipleNativeOptionWithEnvVarPassTopLevel() {
        // Check that Starlark transitions faithfully propagate inputs from the top-level command line.
        // In other words String -> Java type -> Starlark type -> Java type stays consistent.
        //
        // Added to support --action_env and --host_action_env.
        scratch.file(
            "test/rules.bzl",
            "def _t_impl(settings, attr):",
            "    return {",
            "        '//command_line_option:allow_multiple_with_env_var_converter':",
            "       " + " settings['//command_line_option:allow_multiple_with_env_var_converter']",
            "    }",
            "t = transition(",
            "    implementation = _t_impl,",
            "    inputs = ['//command_line_option:allow_multiple_with_env_var_converter'],",
            "    outputs =" + " ['//command_line_option:allow_multiple_with_env_var_converter'],",
            ")",
            "r = rule(",
            "    implementation = lambda ctx: [],",
            "    attrs = {",
            "        'dep': attr.label(cfg = t),",
            "    },",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load(":rules.bzl", "r")

        r(name = "dep")

        r(
            name = "c",
            dep = ":dep",
        )
        
        """.trimIndent()
        )

        useConfiguration(
            "--allow_multiple_with_env_var_converter=a=1",
            "--allow_multiple_with_env_var_converter=b=2",
            "--allow_multiple_with_env_var_converter=a=2",
            "--allow_multiple_with_env_var_converter=c"
        )
        val parentCt: ConfiguredTarget? = getConfiguredTarget("//test:c")
        val depCt: ConfiguredTarget = getDirectPrerequisite(parentCt, "//test:dep")

        assertThat(
            parentCt
                .getConfigurationKey()
                .getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getAllowMultipleWithEnvVarConverter()
        )
            .isEqualTo(
                depCt
                    .getConfigurationKey()
                    .getOptions()
                    .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                    .getAllowMultipleWithEnvVarConverter()
            )
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allowMultipleNativeOptionWithListConverter() {
        // See allowMultiple definition in Option.java for a list-returning converter means.
        scratch.file(
            "test/rules.bzl",
            "def _t_impl(settings, attr):",
            "    return { '//command_line_option:allow_multiple_with_list_converter': ['foo,bar',"
                    + " 'baz'] }",
            "t = transition(",
            "    implementation = _t_impl,",
            "    inputs = [],",
            "    outputs = ['//command_line_option:allow_multiple_with_list_converter'],",
            ")",
            "r = rule(",
            "    implementation = lambda ctx: [],",
            "    attrs = {",
            "        'dep': attr.label(cfg = t),",
            "    },",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load(":rules.bzl", "r")

        r(name = "dep")

        r(
            name = "c",
            dep = ":dep",
        )
        
        """.trimIndent()
        )

        assertThat(
            getDirectPrerequisite(getConfiguredTarget("//test:c"), "//test:dep")
                .getConfigurationKey()
                .getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
                .getAllowMultipleWithListConverter()
        )
            .containsExactly("foo", "bar", "baz")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitionPreservesNonDefaultInputOnlySetting() {
        scratch.file(
            "test/starlark/rules.bzl",
            """
        def _string_impl(ctx):
            return []

        string_flag = rule(
            implementation = _string_impl,
            build_setting = config.string(flag = True),
        )

        def _transition_impl(settings, attr):
            return {
                "//test/starlark:output_only": settings["//test/starlark:input_only"],
            }

        _transition = transition(
            implementation = _transition_impl,
            inputs = [
                "//test/starlark:input_only",
            ],
            outputs = [
                "//test/starlark:output_only",
            ],
        )

        def _apply_transition_impl(ctx):
            ctx.actions.symlink(
                output = ctx.outputs.out,
                target_file = ctx.file.target,
            )
            return [DefaultInfo(executable = ctx.outputs.out)]

        apply_transition = rule(
            implementation = _apply_transition_impl,
            attrs = {
                "target": attr.label(
                    cfg = _transition,
                    allow_single_file = True,
                ),
                "out": attr.output(),
            },
            executable = False,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/starlark/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//test/starlark:rules.bzl", "apply_transition", "string_flag")

        string_flag(
            name = "input_only",
            build_setting_default = "input_only_default",
        )

        string_flag(
            name = "output_only",
            build_setting_default = "output_only_default",
        )

        cc_binary(
            name = "main",
            srcs = ["main.cc"],
        )

        apply_transition(
            name = "transitioned_main",
            out = "main_out",
            target = ":main",
        )
        
        """.trimIndent()
        )

        useConfiguration("--//test/starlark:input_only=not_the_default")
        val inputOnlySetting: Label? = Label.parseCanonicalUnchecked("//test/starlark:input_only")
        val transitionedDep: ConfiguredTarget =
            getDirectPrerequisite(
                getConfiguredTarget("//test/starlark:transitioned_main"), "//test/starlark:main"
            )
        assertThat(
            getConfiguration(transitionedDep)
                .getOptions()
                .getStarlarkOptions()
                .get(inputOnlySetting)
        )
            .isEqualTo("not_the_default")
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun getMyInfoFromTarget(configuredTarget: ConfiguredTarget): StructImpl? {
            val key: Provider.Key =
                Key(
                    keyForBuild(Label.parseCanonical("//myinfo:myinfo.bzl")), "MyInfo"
                )
            return configuredTarget.get(key) as StructImpl?
        }
    }
}
