// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** Tests for [BuildConfigurationFunction]'s special behaviors.  */
@RunWith(TestParameterInjector::class)
class BuildConfigurationFunctionTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setupMyInfo() {
        scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()")

        scratch.file("myinfo/BUILD")
    }

    @Throws(java.lang.Exception::class)
    private fun writeAllowlistFile() {
        scratch.overwriteFile(
            "tools/allowlists/function_transition_allowlist/BUILD",
            """
        package_group(
            name = "function_transition_allowlist",
            packages = [
                "//test/...",
            ],
        )
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun writeBuildSettingsBzl() {
        scratch.file(
            "test/build_settings.bzl",
            """
        BuildSettingInfo = provider(fields = ["value"])

        def _impl(ctx):
            return [BuildSettingInfo(value = ctx.build_setting_value)]

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        
        """.trimIndent()
        )
    }

    private fun getMnemonic(target: ConfiguredTarget): String {
        return getConfiguration(target).getMnemonic()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHasHash() {
        writeAllowlistFile()
        writeBuildSettingsBzl()
        scratch.file(
            "test/transitions.bzl",
            """
        def _foo_impl(settings, attr):
            return {"//test:foo": "transitioned"}

        foo_transition = transition(
            implementation = _foo_impl,
            inputs = [],
            outputs = ["//test:foo"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "foo_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = foo_transition),
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
        load("//test:build_settings.bzl", "string_flag")
        load("//test:rules.bzl", "my_rule", "simple")

        string_flag(
            name = "foo",
            build_setting_default = "default",
        )

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple(name = "dep")
        
        """.trimIndent()
        )

        val test: ConfiguredTarget? = getConfiguredTarget("//test")

        Truth.assertThat(getMnemonic(test)).doesNotContain("-ST-")

        val dep: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(test).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        Truth.assertThat(getMnemonic(dep))
            .endsWith(
                OutputPathMnemonicComputer.transitionDirectoryNameFragment(
                    com.google.common.collect.ImmutableList.of<E?>("//test:foo=transitioned")
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun avoidHashForInExplicitOutputPath() {
        writeAllowlistFile()
        scratch.file(
            "test/transitions.bzl",
            """
        def _opt_impl(settings, attr):
            return {"//command_line_option:compilation_mode": "opt"}

        opt_transition = transition(
            implementation = _opt_impl,
            inputs = [],
            outputs = ["//command_line_option:compilation_mode"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "opt_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = opt_transition),
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

        useConfiguration("--compilation_mode=fastbuild")
        val test: ConfiguredTarget? = getConfiguredTarget("//test")

        com.google.common.truth.Subject.contains("fastbuild")
        Truth.assertThat(getMnemonic(test)).doesNotContain("-ST-")

        val dep: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(test).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        com.google.common.truth.Subject.contains("opt")
        Truth.assertThat(getMnemonic(dep)).doesNotContain("-ST-")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun abaAvoidsHash() {
        writeAllowlistFile()
        writeBuildSettingsBzl()
        scratch.file(
            "test/transitions.bzl",
            """
        def _toggle_impl(settings, attr):
            if (settings["//test:foo"] != "default"):
                return {"//test:foo": "default"}
            else:
                return {"//test:foo": "transitioned"}

        toggle_foo_transition = transition(
            implementation = _toggle_impl,
            inputs = ["//test:foo"],
            outputs = ["//test:foo"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "toggle_foo_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = toggle_foo_transition),
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
        load("//test:build_settings.bzl", "string_flag")
        load("//test:rules.bzl", "my_rule", "simple")

        string_flag(
            name = "foo",
            build_setting_default = "default",
        )

        my_rule(
            name = "test",
            dep = ":middle",
        )

        my_rule(
            name = "middle",
            dep = ":root",
        )

        simple(name = "root")
        
        """.trimIndent()
        )

        val test: ConfiguredTarget? = getConfiguredTarget("//test")

        Truth.assertThat(getMnemonic(test)).doesNotContain("-ST-")

        val middle: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(test).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        Truth.assertThat(getMnemonic(middle))
            .endsWith(
                OutputPathMnemonicComputer.transitionDirectoryNameFragment(
                    com.google.common.collect.ImmutableList.of<E?>("//test:foo=transitioned")
                )
            )

        val root: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
                getMyInfoFromTarget(middle).getValue("dep") as MutableList<ConfiguredTarget?>?
            )

        Truth.assertThat(getMnemonic(test)).doesNotContain("-ST-")

        assertThat(getConfiguration(test)).isEqualTo(getConfiguration(root))
        assertThat(getConfiguration(test)).isNotEqualTo(getConfiguration(middle))

        // This should be implied by everything else but as a final check....
        assertThat(getConfiguration(test).getMnemonic())
            .isEqualTo(getConfiguration(root).getMnemonic())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatformExplicitInOutputDir_withPlatformMappings() {
        writeAllowlistFile()
        scratch.file(
            "test/transitions.bzl",
            """
        def _platform_impl(settings, attr):
            return {"//command_line_option:platforms": [attr.platform]}

        platform_transition = transition(
            implementation = _platform_impl,
            inputs = [],
            outputs = ["//command_line_option:platforms"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "platform_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(),
            },
        )

        def _basic_impl(ctx):
            return []

        as_platform = rule(
            implementation = _basic_impl,
            cfg = platform_transition,
            attrs = {
                "platform": attr.label(default = "//platforms:alpha"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "as_platform", "my_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        as_platform(
            name = "dep",
            platform = "//platforms:beta",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "platforms/BUILD",
            """
        platform(name = "alpha")

        platform(name = "beta")
        
        """.trimIndent()
        )
        scratch.file(
            "tools/platform_mappings",
            "platforms:",
            "  //platforms:alpha",
            "    --cpu=alpha",
            "  //platforms:beta",
            "    --cpu=beta",
            "flags:",
            "  --cpu=alpha",
            "    //platforms:alpha",
            "  --cpu=beta",
            "    //platforms:beta"
        )

        useConfiguration(
            "--compilation_mode=fastbuild",
            "--platforms=//platforms:alpha",
            "--platform_mappings=tools/platform_mappings",
            "--experimental_platform_in_output_dir",
            "--noexperimental_use_platforms_in_output_dir_legacy_heuristic",
            "--experimental_override_name_platform_in_output_dir=//platforms:alpha=alpha",
            "--experimental_override_name_platform_in_output_dir=//platforms:beta=beta"
        )
        val test: ConfiguredTarget? = getConfiguredTarget("//test")

        Truth.assertThat(getMnemonic(test)).contains("alpha-fastbuild")
        Truth.assertThat(getMnemonic(test)).doesNotContain("-ST-")

        val dep: ConfiguredTarget = getMyInfoFromTarget(test).getValue("dep") as ConfiguredTarget

        Truth.assertThat(getMnemonic(dep)).contains("beta-fastbuild")
        Truth.assertThat(getMnemonic(dep)).doesNotContain("-ST-")

        // Verify platform_mappings applied properly
        assertThat(getConfiguration(test).getCpu()).isEqualTo("alpha")
        assertThat(getConfiguration(dep).getCpu()).isEqualTo("beta")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatformExplicitInOutputDir_withMorePlatformMappings() {
        writeAllowlistFile()
        scratch.file(
            "test/transitions.bzl",
            """
        def _platform_impl(settings, attr):
            return {"//command_line_option:platforms": [attr.platform]}

        platform_transition = transition(
            implementation = _platform_impl,
            inputs = [],
            outputs = ["//command_line_option:platforms"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "platform_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(),
            },
        )

        def _basic_impl(ctx):
            return []

        as_platform = rule(
            implementation = _basic_impl,
            cfg = platform_transition,
            attrs = {
                "platform": attr.label(default = "//platforms:alpha"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "as_platform", "my_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        as_platform(
            name = "dep",
            platform = "//platforms:beta",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "platforms/BUILD",
            """
        platform(name = "alpha")

        platform(name = "beta")
        
        """.trimIndent()
        )

        // Test just wants to transition some options not usually explicitly in the output path
        // so if those options are changed/removed, just replace them here.
        scratch.file(
            "tools/platform_mappings",
            "platforms:",
            "  //platforms:alpha",
            "    --cpu=alpha",
            "    --use_ijars=false",
            "    --dynamic_mode=default",
            "  //platforms:beta",
            "    --cpu=beta",
            "    --use_ijars=true",
            "    --dynamic_mode=off"
        )

        useConfiguration(
            "--compilation_mode=fastbuild",
            "--platforms=//platforms:alpha",
            "--platform_mappings=tools/platform_mappings",
            "--experimental_platform_in_output_dir",
            "--noexperimental_use_platforms_in_output_dir_legacy_heuristic",
            "--experimental_override_name_platform_in_output_dir=//platforms:alpha=alpha",
            "--experimental_override_name_platform_in_output_dir=//platforms:beta=beta"
        )
        val test: ConfiguredTarget? = getConfiguredTarget("//test")

        Truth.assertThat(getMnemonic(test)).contains("alpha-fastbuild")
        Truth.assertThat(getMnemonic(test)).doesNotContain("-ST-")

        val dep: ConfiguredTarget = getMyInfoFromTarget(test).getValue("dep") as ConfiguredTarget

        Truth.assertThat(getMnemonic(dep)).contains("beta-fastbuild")
        Truth.assertThat(getMnemonic(dep)).doesNotContain("-ST-")

        // Verify platform_mappings applied properly
        assertThat(getConfiguration(test).getCpu()).isEqualTo("alpha")
        assertThat(getConfiguration(test).getFragment(CppConfiguration::class.java).getDynamicModeFlag())
            .isEqualTo(CppConfiguration.DynamicMode.DEFAULT)
        assertThat(getConfiguration(test).getFragment(JavaConfiguration::class.java).getUseIjars()).isFalse()
        assertThat(getConfiguration(dep).getCpu()).isEqualTo("beta")
        assertThat(getConfiguration(dep).getFragment(CppConfiguration::class.java).getDynamicModeFlag())
            .isEqualTo(CppConfiguration.DynamicMode.OFF)
        assertThat(getConfiguration(dep).getFragment(JavaConfiguration::class.java).getUseIjars()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatformExplicitInOutputDir_withExecConfigDep() {
        writeAllowlistFile()
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = 'exec'),
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
            dep = ":dep",
        )

        my_rule(
            name = "dep",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "platforms/BUILD",
            """
        platform(name = "alpha")
        
        """.trimIndent()
        )

        useConfiguration(
            "--compilation_mode=fastbuild",
            "--platforms=//platforms:alpha",
            "--host_platform=//platforms:alpha",
            "--experimental_platform_in_output_dir",
            "--noexperimental_use_platforms_in_output_dir_legacy_heuristic",
            "--experimental_override_name_platform_in_output_dir=//platforms:alpha=alpha-override"
        )
        val test: ConfiguredTarget? = getConfiguredTarget("//test")

        Truth.assertThat(getMnemonic(test)).contains("alpha-override-fastbuild")
        Truth.assertThat(getMnemonic(test)).doesNotContain("-ST-")

        val dep: ConfiguredTarget = getMyInfoFromTarget(test).getValue("dep") as ConfiguredTarget

        // The platform name override is used in dep with exec config
        Truth.assertThat(getMnemonic(dep)).contains("alpha-override-opt-exec")
        Truth.assertThat(getMnemonic(dep)).doesNotContain("-ST-")
    }

    @org.junit.Test
    @TestParameters(
        ("{platformInOutputDir: True, nonExecMnemonic:"
                + " alpha-override-fastbuild, execMnemonic: alpha-override-opt-exec}"),
        ("{platformInOutputDir: False, nonExecMnemonic:"
                + " alpha-fastbuild, execMnemonic: alpha-opt-exec}"),
        ("{platformInOutputDir: Auto, nonExecMnemonic:"
                + " alpha-fastbuild, execMnemonic: alpha-override-opt-exec}")
    )
    @Throws(java.lang.Exception::class)
    fun testDifferentStatesOfPlatformInOutputDir(
        platformInOutputDir: String?, nonExecMnemonic: String?, execMnemonic: String?
    ) {
        writeAllowlistFile()
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _impl(ctx):
            return MyInfo(exec_dep = ctx.attr.exec_dep, non_exec_dep = ctx.attr.non_exec_dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "exec_dep": attr.label(cfg = 'exec'),
                "non_exec_dep": attr.label(),
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
            exec_dep = ":exec_dep",
            non_exec_dep = ":non_exec_dep",
        )

        my_rule(
            name = "exec_dep",
        )

        my_rule(
            name = "non_exec_dep",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "platforms/BUILD",
            """
        platform(name = "alpha_platform")
        
        """.trimIndent()
        )

        useConfiguration(
            "--compilation_mode=fastbuild",
            "--platforms=//platforms:alpha_platform",
            "--cpu=alpha",
            "--host_platform=//platforms:alpha_platform",
            "--host_cpu=alpha",
            "--experimental_platform_in_output_dir=" + platformInOutputDir,
            "--experimental_override_name_platform_in_output_dir=//platforms:alpha_platform=alpha-override"
        )
        val test: ConfiguredTarget? = getConfiguredTarget("//test")

        Truth.assertThat(getMnemonic(test)).contains(nonExecMnemonic)
        Truth.assertThat(getMnemonic(test)).doesNotContain("-ST-")

        val dep: ConfiguredTarget = getMyInfoFromTarget(test).getValue("exec_dep") as ConfiguredTarget
        // The platform name override is used in dep with exec config
        Truth.assertThat(getMnemonic(dep)).contains(execMnemonic)
        Truth.assertThat(getMnemonic(dep)).doesNotContain("-ST-")

        val nonExecDep: ConfiguredTarget =
            getMyInfoFromTarget(test).getValue("non_exec_dep") as ConfiguredTarget
        // The platform name override is used in dep with non-exec config
        Truth.assertThat(getMnemonic(nonExecDep)).contains(nonExecMnemonic)
        Truth.assertThat(getMnemonic(nonExecDep)).doesNotContain("-ST-")
    }

    @org.junit.Test
    @TestParameters(
        ("{limitOutputDirToPlatforms: [],"
                + "t1Path: p1-fastbuild,"
                + "d1Path: p1-fastbuild,"
                + "d2Path: p2-opt-exec,"
                + "d3Path: p3-fastbuild}"), ("{limitOutputDirToPlatforms: [//platforms:p1],"
                + "t1Path: p1-fastbuild,"
                + "d1Path: p1-fastbuild,"
                + "d2Path: p2-opt-exec,"
                + "d3Path: p3_cpu-fastbuild}"), ("{limitOutputDirToPlatforms: [//platforms:p1, //platforms:p3],"
                + "t1Path: p1-fastbuild,"
                + "d1Path: p1-fastbuild,"
                + "d2Path: p2-opt-exec,"
                + "d3Path: p3-fastbuild}")
    )
    @Throws(java.lang.Exception::class)
    fun testLimitOutputDirToPlatforms(
        limitOutputDirToPlatforms: MutableList<String?>,
        t1Path: String?,
        d1Path: String?,
        d2Path: String?,
        d3Path: String?
    ) {
        writeAllowlistFile()
        scratch.file(
            "test/rules.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _p3_transition_impl(settings, attr):
            return {
                "//command_line_option:platforms": ["//platforms:p3"]
            }

        p3_transition = transition(
            implementation = _p3_transition_impl,
            inputs = [],
            outputs = ["//command_line_option:platforms"],
        )

        def _impl(ctx):
            d3 = ctx.attr.d3[0] if ctx.attr.d3 else None
            return MyInfo(d1 = ctx.attr.d1, d2 = ctx.attr.d2, d3 = d3)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "d1": attr.label(),
                "d2": attr.label(cfg = 'exec'),
                "d3": attr.label(cfg = p3_transition),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(
            name = "t1",
            d1 = ":d1",
            d2 = ":d2",
            d3 = ":d3",
        )

        my_rule(name = "d1")
        my_rule(name = "d2")
        my_rule(name = "d3")
        
        """.trimIndent()
        )
        scratch.file(
            "platforms/BUILD",
            """
        platform(name = "p1")
        platform(name = "p2")
        platform(name = "p3", flags = ["--cpu=p3_cpu"])
        
        """.trimIndent()
        )
        useConfiguration(
            "--compilation_mode=fastbuild",
            "--platforms=//platforms:p1",
            "--cpu=p1_cpu",
            "--host_platform=//platforms:p2",
            "--host_cpu=p2_cpu",
            "--experimental_platform_in_output_dir",
            "--incompatible_limit_platforms_in_output_dir_to="
                    + java.lang.String.join(",", limitOutputDirToPlatforms)
        )

        val t1: ConfiguredTarget? = getConfiguredTarget("//test:t1")
        Truth.assertThat(getMnemonic(t1)).isEqualTo(t1Path)

        val d1: ConfiguredTarget = getMyInfoFromTarget(t1).getValue("d1") as ConfiguredTarget
        Truth.assertThat(getMnemonic(d1)).isEqualTo(d1Path)

        val d2: ConfiguredTarget = getMyInfoFromTarget(t1).getValue("d2") as ConfiguredTarget
        Truth.assertThat(getMnemonic(d2)).isEqualTo(d2Path)

        val d3: ConfiguredTarget = getMyInfoFromTarget(t1).getValue("d3") as ConfiguredTarget
        Truth.assertThat(getMnemonic(d3)).contains(d3Path)
        if (limitOutputDirToPlatforms.isEmpty()
            || limitOutputDirToPlatforms.contains("//platforms:p3")
        ) {
            Truth.assertThat(getMnemonic(d3)).doesNotContain("-ST-")
        } else {
            Truth.assertThat(getMnemonic(d3)).contains("-ST-")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatformWithNoCPUConstraint_emptyTargetCpu() {
        scratch.file(
            "platforms/BUILD",
            """
        platform(
            name = "no_cpu_platform",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/lib.bzl",
            """
        my_rule = rule(
            implementation = lambda ctx: [],
            attrs = {
                "exec_deps": attr.label_list(cfg = "exec"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "my_rule")
        my_rule(
            name = "parent",
            exec_deps = [":child"]
        )
        my_rule(name = "child")
        
        """.trimIndent()
        )

        useConfiguration(
            "--incompatible_target_cpu_from_platform",
            "--platforms=//platforms:no_cpu_platform",
            "--extra_execution_platforms=//platforms:no_cpu_platform"
        )

        val config: BuildConfigurationValue = getConfiguration(getConfiguredTarget("//test:parent"))
        assertThat(config.isExecConfiguration()).isFalse()
        assertThat(config.getMakeEnvironment()).containsEntry("TARGET_CPU", "")

        val execConfig: BuildConfigurationValue =
            getConfiguration(
                getDirectPrerequisite(getConfiguredTarget("//test:parent"), "//test:child")
            )

        assertThat(execConfig.isExecConfiguration()).isTrue()
        assertThat(execConfig.getMakeEnvironment()).containsEntry("TARGET_CPU", "")
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
