// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.PlatformConfiguration

/** Tests for [BuildConfigurationValue].  */
@RunWith(TestParameterInjector::class)
class BuildConfigurationValueTest : ConfigurationTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBasics() {
        if (analysisMock.isThisBazel()) {
            return
        }

        val config: BuildConfigurationValue = create("--cpu=piii")
        val outputDirPrefix =
            outputBase.toString() + "/execroot/" + config.getWorkspaceName() + "/blaze-out/.*piii-fastbuild"

        assertThat(config.getOutputDirectory(RepositoryName.MAIN).getRoot().toString())
            .matches(outputDirPrefix)
        assertThat(config.getBinDirectory(RepositoryName.MAIN).getRoot().toString())
            .matches(outputDirPrefix + "/bin")
        assertThat(config.getTestLogsDirectory(RepositoryName.MAIN).getRoot().toString())
            .matches(outputDirPrefix + "/testlogs")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatformSuffix() {
        if (analysisMock.isThisBazel()) {
            return
        }

        val config: BuildConfigurationValue = create("--platform_suffix=test")
        assertThat(config.getOutputDirectory(RepositoryName.MAIN).getRoot().toString())
            .matches(
                (outputBase
                    .toString() + "/execroot/"
                        + config.getWorkspaceName()
                        + "/blaze-out/.*k8-fastbuild-test")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvironment() {
        if (analysisMock.isThisBazel()) {
            return
        }

        val env: com.google.common.collect.ImmutableMap<String?, String?> = create().getLocalShellEnvironment()
        Truth.assertThat(env).containsEntry("LANG", "en_US")
        Truth.assertThat(env).containsKey("PATH")
        Truth.assertThat(env.get("PATH")).contains("/bin:/usr/bin")
    }

    @org.junit.Test
    fun testCaching() {
        val a: CoreOptions = com.google.devtools.common.options.Options.getDefaults<O>(CoreOptions::class.java)
        val b: CoreOptions = com.google.devtools.common.options.Options.getDefaults<O>(CoreOptions::class.java)
        // The String representations of the CoreOptions must be equal even if these are
        // different objects, if they were created with the same options (no options in this case).
        assertThat(b.toString()).isEqualTo(a.toString())
        assertThat(BuildOptions.optionsToCacheKey(b)).isEqualTo(BuildOptions.optionsToCacheKey(a))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetEnvironment() {
        val oneEnvConfig: BuildConfigurationValue = create("--target_environment=//foo")
        assertThat(oneEnvConfig.getTargetEnvironments()).containsExactly(Label.parseCanonical("//foo"))

        val twoEnvsConfig: BuildConfigurationValue =
            create("--target_environment=//foo", "--target_environment=//bar")
        assertThat(twoEnvsConfig.getTargetEnvironments())
            .containsExactly(Label.parseCanonical("//foo"), Label.parseCanonical("//bar"))

        val noEnvsConfig: BuildConfigurationValue = create()
        assertThat(noEnvsConfig.getTargetEnvironments()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobalMakeVariableOverride() {
        assertThat(create().getMakeEnvironment()).containsEntry("COMPILATION_MODE", "fastbuild")
        val config: BuildConfigurationValue = create("--define", "COMPILATION_MODE=fluttershy")
        assertThat(config.getMakeEnvironment()).containsEntry("COMPILATION_MODE", "fluttershy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetCpuFromCpuFlag() {
        val config: BuildConfigurationValue =
            create(
                "--noincompatible_target_cpu_from_platform",
                "--cpu=piii",
                "--platforms=" + TestConstants.PLATFORM_LABEL
            )
        assertThat(config.getMakeEnvironment()).containsEntry("TARGET_CPU", "piii")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetCpuFromPlatform() {
        val config: BuildConfigurationValue =
            create(
                "--cpu=piii",
                "--platforms=" + TestConstants.PLATFORM_LABEL,
                "--incompatible_target_cpu_from_platform"
            )
        assertThat(config.getMakeEnvironment()).containsEntry("TARGET_CPU", "x86_64")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetCpuFromPlatformWithCpuMapping() {
        val config: BuildConfigurationValue =
            create(
                "--cpu=piii",
                "--platforms=" + TestConstants.PLATFORM_LABEL,
                "--incompatible_target_cpu_from_platform",
                ("--experimental_override_platform_cpu_name="
                        + TestConstants.PLATFORM_LABEL
                        + "=new_cpu")
            )
        assertThat(config.getMakeEnvironment()).containsEntry("TARGET_CPU", "new_cpu")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetCpuFromPlatform_multipleCpuMappings_lastOneWins() {
        val config: BuildConfigurationValue =
            create(
                "--cpu=piii",
                "--platforms=" + TestConstants.PLATFORM_LABEL,
                "--incompatible_target_cpu_from_platform",
                ("--experimental_override_platform_cpu_name="
                        + TestConstants.PLATFORM_LABEL
                        + "=new_cpu_1"),
                ("--experimental_override_platform_cpu_name="
                        + TestConstants.PLATFORM_LABEL
                        + "=new_cpu_2")
            )
        assertThat(config.getMakeEnvironment()).containsEntry("TARGET_CPU", "new_cpu_2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecConfigTargetCpuFromPlatform() {
        val config: BuildConfigurationValue =
            createExec(
                "--cpu=x86_64",
                "--host_platform=" + TestConstants.PIII_PLATFORM_LABEL,
                "--incompatible_target_cpu_from_platform"
            )
        assertThat(config.getMakeEnvironment()).containsEntry("TARGET_CPU", "x86_32")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecConfigTargetCpuFromPlatformWithCpuMapping() {
        val config: BuildConfigurationValue =
            createExec(
                "--cpu=x86_64",
                "--host_platform=" + TestConstants.PIII_PLATFORM_LABEL,
                "--incompatible_target_cpu_from_platform",
                ("--experimental_override_platform_cpu_name="
                        + TestConstants.PIII_PLATFORM_LABEL
                        + "=new_cpu")
            )
        assertThat(config.getMakeEnvironment()).containsEntry("TARGET_CPU", "new_cpu")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetBuildOptionDetails() {
        // Directly defined options:
        assertThat(create("-c", "dbg").getBuildOptionDetails().getOptionValue("compilation_mode"))
            .isEqualTo(CompilationMode.DBG)
        assertThat(create("-c", "opt").getBuildOptionDetails().getOptionValue("compilation_mode"))
            .isEqualTo(CompilationMode.OPT)

        // Options defined in a fragment:
        assertThat(create("--force_pic").getBuildOptionDetails().getOptionValue("force_pic"))
            .isEqualTo(java.lang.Boolean.TRUE)
        assertThat(create("--noforce_pic").getBuildOptionDetails().getOptionValue("force_pic"))
            .isEqualTo(java.lang.Boolean.FALSE)

        // Legitimately null option:
        assertThat(create().getBuildOptionDetails().getOptionValue("test_filter")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigFragmentsAreShareableAcrossConfigurations() {
        // Note we can't use any fragments that load files (e.g. CROSSTOOL) because those get
        // Skyframe-invalidated between create() calls.
        val config1: BuildConfigurationValue = create("--javacopt=foo")
        val config2: BuildConfigurationValue = create("--javacopt=bar")
        val config3: BuildConfigurationValue = create("--toolchain_resolution_debug=.*")
        // Shared because all platform options are the same:
        assertThat(config1.getFragment(PlatformConfiguration::class.java))
            .isSameInstanceAs(config2.getFragment(PlatformConfiguration::class.java))
        // Distinct because the platform options differ:
        assertThat(config1.getFragment(PlatformConfiguration::class.java))
            .isNotSameInstanceAs(config3.getFragment(PlatformConfiguration::class.java))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineVariables() {
        val config: BuildConfigurationValue =
            create("--define", "a=b/c:d", "--define", "b=FOO", "--define", "DEFUN=Nope")
        assertThat(config.getCommandLineBuildVariables().get("a")).isEqualTo("b/c:d")
        assertThat(config.getCommandLineBuildVariables().get("b")).isEqualTo("FOO")
        assertThat(config.getCommandLineBuildVariables().get("DEFUN")).isEqualTo("Nope")
    }

    // Regression test for bug #2518997:
    // "--define in blazerc overrides --define from command line"
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineVariablesOverride() {
        val config: BuildConfigurationValue = create("--define", "a=b", "--define", "a=c")
        assertThat(config.getCommandLineBuildVariables().get("a")).isEqualTo("c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNormalization_definesWithDifferentNames() {
        val config: BuildConfigurationValue = create("--define", "a=1", "--define", "b=2")
        val options: CoreOptions = config.getOptions().get(CoreOptions::class.java)
        assertThat(options.getNormalizedCommandLineBuildVariables())
            .containsExactly("a", "1", "b", "2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNormalization_definesWithSameName() {
        val config: BuildConfigurationValue = create("--define", "a=1", "--define", "a=2")
        val options: CoreOptions = config.getOptions().get(CoreOptions::class.java)
        assertThat(options.getNormalizedCommandLineBuildVariables()).containsExactly("a", "2")
        assertThat(config).isEqualTo(create("--define", "a=2"))
    }

    // This is really a test of option parsing, not command-line variable
    // semantics.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineVariablesWithFunnyCharacters() {
        val config: BuildConfigurationValue =
            create(
                "--define", "foo=#foo",
                "--define", "comma=a,b",
                "--define", "space=foo bar",
                "--define", "thing=a \"quoted\" thing",
                "--define", "qspace=a\\ quoted\\ space",
                "--define", "#a=pounda"
            )
        assertThat(config.getCommandLineBuildVariables().get("foo")).isEqualTo("#foo")
        assertThat(config.getCommandLineBuildVariables().get("comma")).isEqualTo("a,b")
        assertThat(config.getCommandLineBuildVariables().get("space")).isEqualTo("foo bar")
        assertThat(config.getCommandLineBuildVariables().get("thing")).isEqualTo("a \"quoted\" thing")
        assertThat(config.getCommandLineBuildVariables().get("qspace")).isEqualTo("a\\ quoted\\ space")
        assertThat(config.getCommandLineBuildVariables().get("#a")).isEqualTo("pounda")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecDefine_isAllowedByDefault() {
        val cfg: BuildConfigurationValue = createExec("--define=foo=bar")
        assertThat(cfg.getCommandLineBuildVariables().get("foo")).isEqualTo("bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecDefine_isIgnoredIfExcludedAndNotAllowed() {
        val cfg: BuildConfigurationValue =
            createExec("--define=foo=bar", "--experimental_exclude_defines_from_exec_config=true")
        assertThat(cfg.getCommandLineBuildVariables()).doesNotContainKey("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecDefine_isPropagatedIfAllowedByFlag() {
        val cfg: BuildConfigurationValue =
            createExec(
                "--define=foo=bar",
                "--experimental_exclude_defines_from_exec_config=true",
                "--experimental_propagate_custom_flag=foo",
                "--define=baz=qux"
            )
        assertThat(cfg.getCommandLineBuildVariables()).containsEntry("foo", "bar")
        assertThat(cfg.getCommandLineBuildVariables()).doesNotContainEntry("baz", "qux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecStarlarkFlag_isDisallowedByDefault() {
        scratch.file(
            "my_starlark_flag/rule_defs.bzl",
            """
        def _impl(ctx):
            return []

        bool_flag = rule(
            implementation = _impl,
            build_setting = config.bool(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "my_starlark_flag/BUILD",
            """
        load("//my_starlark_flag:rule_defs.bzl", "bool_flag")

        bool_flag(
            name = "starlark_flag",
            build_setting_default = False,
        )
        
        """.trimIndent()
        )
        val cfg: BuildConfigurationValue =
            createExec(
                com.google.common.collect.ImmutableMap.of<String?, Any?>(
                    "//my_starlark_flag:starlark_flag",
                    "true"
                )
            )
        assertThat(
            cfg.getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//my_starlark_flag:starlark_flag"))
        )
            .isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecStarlarkFlag_isIgnoredIfExcludedAndNotAllowed() {
        scratch.file(
            "my_starlark_flag/rule_defs.bzl",
            """
        def _impl(ctx):
            return []

        bool_flag = rule(
            implementation = _impl,
            build_setting = config.bool(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "my_starlark_flag/BUILD",
            """
        load("//my_starlark_flag:rule_defs.bzl", "bool_flag")

        bool_flag(
            name = "starlark_flag",
            build_setting_default = False,
        )
        
        """.trimIndent()
        )
        val cfg: BuildConfigurationValue =
            createExec(
                com.google.common.collect.ImmutableMap.of<String?, Any?>("//my_starlark_flag:starlark_flag", "true"),
                "--experimental_exclude_starlark_flags_from_exec_config=true"
            )
        assertThat(
            cfg.getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//my_starlark_flag:starlark_flag"))
        )
            .isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecStarlarkFlag_isPropagatedIfAllowedByFlag() {
        scratch.file(
            "my_starlark_flag/rule_defs.bzl",
            """
        def _impl(ctx):
            return []

        bool_flag = rule(
            implementation = _impl,
            build_setting = config.bool(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "my_starlark_flag/BUILD",
            """
        load("//my_starlark_flag:rule_defs.bzl", "bool_flag")

        bool_flag(
            name = "starlark_flag",
            build_setting_default = False,
        )

        bool_flag(
            name = "other_starlark_flag",
            build_setting_default = False,
        )
        
        """.trimIndent()
        )
        val cfg: BuildConfigurationValue =
            createExec(
                com.google.common.collect.ImmutableMap.of<String?, Any?>(
                    "//my_starlark_flag:starlark_flag",
                    "true",
                    "//my_starlark_flag:other_starlark_flag",
                    "true"
                ),
                "--experimental_exclude_starlark_flags_from_exec_config=true",  // Verify that labels are parsed rather than compared as strings by specifying the
                // label in non-canonical form.
                "--experimental_propagate_custom_flag=@//my_starlark_flag:starlark_flag"
            )
        assertThat(
            cfg.getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//my_starlark_flag:starlark_flag"))
        )
            .isEqualTo("true")
        assertThat(
            cfg.getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//my_starlark_flag:other_starlark_flag"))
        )
            .isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecStarlarkFlag_isPropagatedByTargetPattern() {
        scratch.file("my_starlark_flag/BUILD")
        scratch.file(
            "my_starlark_flag/rule_defs.bzl",
            """
        bool_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.bool(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "flags_to_propagate/BUILD",
            """
        load("//my_starlark_flag:rule_defs.bzl", "bool_flag")
        bool_flag(
            name = "include_me",
            build_setting_default = False,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "flags_to_reset/BUILD",
            """
        load("//my_starlark_flag:rule_defs.bzl", "bool_flag")
        bool_flag(
            name = "exclude_me",
            build_setting_default = False,
        )
        
        """.trimIndent()
        )

        val cfg: BuildConfigurationValue =
            createExec(
                com.google.common.collect.ImmutableMap.of<String?, Any?>(
                    "//flags_to_propagate:include_me", "true", "//flags_to_reset:exclude_me", "true"
                ),
                "--experimental_exclude_starlark_flags_from_exec_config=true",
                "--experimental_propagate_custom_flag=//flags_to_propagate/..."
            )

        assertThat(
            cfg.getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flags_to_propagate:include_me"))
        )
            .isEqualTo("true")
        assertThat(
            cfg.getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flags_to_reset:exclude_me"))
        )
            .isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkFlagExecScopes(@TestParameter propagateByDefault: Boolean) {
        scratch.file("my_starlark_flag/BUILD")
        scratch.file(
            "my_starlark_flag/rule_defs.bzl",
            """
        string_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.string(flag = True),
            attrs = {"scope": attr.string(), "on_leave_scope": attr.string()},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//my_starlark_flag:rule_defs.bzl", "string_flag")
        string_flag(
            name = "default_scope",
            build_setting_default = "default",
        )
        string_flag(
            name = "target_scope",
            build_setting_default = "default",
            scope = "target",
        )
        string_flag(
            name = "universal_scope",
            build_setting_default = "default",
            scope = "universal",
        )
        string_flag(
            name = "flag_in_exec_config_set_to_another_value",
            build_setting_default = "default",
            scope = "target",
            on_leave_scope = "another_value"
        )
        string_flag(
            name = "another_flag",
            build_setting_default = "default",
        )
        string_flag(
            name = "flag_in_exec_config_reference_another_flag_value",
            build_setting_default = "default",
            scope = "exec:--//test:another_flag",
        )
        
        """.trimIndent()
        )

        val execConfig: BuildConfigurationValue =
            createExec(
                com.google.common.collect.ImmutableMap.of<String?, Any?>(
                    "//test:default_scope",
                    "custom",
                    "//test:target_scope",
                    "custom",
                    "//test:universal_scope",
                    "custom",
                    "//test:flag_in_exec_config_set_to_another_value",
                    "target_value",
                    "//test:flag_in_exec_config_reference_another_flag_value",
                    "target_value",
                    "//test:another_flag",
                    "default"
                ),
                "--experimental_exclude_starlark_flags_from_exec_config="
                        + (if (propagateByDefault) "false" else "true")
            )

        if (propagateByDefault) {
            assertThat(execConfig.getOptions().getStarlarkOptions())
                .containsExactly(
                    Label.parseCanonicalUnchecked("//test:universal_scope"),
                    "custom",
                    Label.parseCanonicalUnchecked("//test:default_scope"),
                    "custom",
                    Label.parseCanonicalUnchecked("//test:another_flag"),
                    "default"
                )
        } else {
            assertThat(execConfig.getOptions().getStarlarkOptions())
                .containsExactly(
                    Label.parseCanonicalUnchecked("//test:universal_scope"),
                    "custom",
                    Label.parseCanonicalUnchecked("//test:flag_in_exec_config_set_to_another_value"),
                    "another_value",
                    Label.parseCanonicalUnchecked(
                        "//test:flag_in_exec_config_reference_another_flag_value"
                    ),
                    "default",
                    Label.parseCanonicalUnchecked("//test:another_flag"),
                    "default"
                )
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkFlagExecScopes_take_precedence_over_custom_overrides() {
        scratch.file("my_starlark_flag/BUILD")
        scratch.file(
            "my_starlark_flag/rule_defs.bzl",
            """
        string_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.string(flag = True),
            attrs = {"scope": attr.string(values = ["target", "universal"])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//my_starlark_flag:rule_defs.bzl", "string_flag")
        string_flag(
            name = "default_scope",
            build_setting_default = "default",
        )
        string_flag(
            name = "target_scope",
            build_setting_default = "default",
            scope = "target",
        )
        
        """.trimIndent()
        )

        val execConfig: BuildConfigurationValue =
            createExec(
                com.google.common.collect.ImmutableMap.of<String?, Any?>(
                    "//test:default_scope",
                    "custom",
                    "//test:target_scope",
                    "custom"
                ),
                "--experimental_exclude_starlark_flags_from_exec_config=true",
                "--experimental_propagate_custom_flag=//test:target_scope",
                "--experimental_propagate_custom_flag=//test:default_scope"
            )

        assertThat(execConfig.getOptions().getStarlarkOptions())
            .containsExactly(Label.parseCanonicalUnchecked("//test:default_scope"), "custom")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun labelFlagExecScopes(@TestParameter propagateByDefault: Boolean) {
        scratch.file(
            "test/BUILD",
            """
        label_flag(
            name = "default_scope",
            build_setting_default = "//foo",
        )
        label_flag(
            name = "target_scope",
            build_setting_default = "//foo",
            scope = "target",
        )
        label_flag(
            name = "universal_scope",
            build_setting_default = "//foo",
            scope = "universal",
        )
        
        """.trimIndent()
        )

        val execConfig: BuildConfigurationValue =
            createExec(
                com.google.common.collect.ImmutableMap.of<String?, Any?>(
                    "//test:default_scope",
                    "//custom",
                    "//test:target_scope",
                    "//custom",
                    "//test:universal_scope",
                    "//custom"
                ),
                "--experimental_exclude_starlark_flags_from_exec_config="
                        + (if (propagateByDefault) "false" else "true")
            )

        if (propagateByDefault) {
            assertThat(execConfig.getOptions().getStarlarkOptions())
                .containsExactly(
                    Label.parseCanonicalUnchecked("//test:universal_scope"),
                    "//custom",
                    Label.parseCanonicalUnchecked("//test:default_scope"),
                    "//custom"
                )
        } else {
            assertThat(execConfig.getOptions().getStarlarkOptions())
                .containsExactly(Label.parseCanonicalUnchecked("//test:universal_scope"), "//custom")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHostCompilationModeDefault() {
        val cfg: BuildConfigurationValue = createExec()
        assertThat(cfg.getCompilationMode()).isEqualTo(CompilationMode.OPT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHostCompilationModeNonDefault() {
        val cfg: BuildConfigurationValue = createExec("--host_compilation_mode=dbg")
        assertThat(cfg.getCompilationMode()).isEqualTo(CompilationMode.DBG)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncompatibleMergeGenfilesDirectory() {
        val target: BuildConfigurationValue = create("--incompatible_merge_genfiles_directory")
        val exec: BuildConfigurationValue = createExec("--incompatible_merge_genfiles_directory")
        assertThat(target.getGenfilesDirectory(RepositoryName.MAIN))
            .isEqualTo(target.getBinDirectory(RepositoryName.MAIN))
        assertThat(exec.getGenfilesDirectory(RepositoryName.MAIN))
            .isEqualTo(exec.getBinDirectory(RepositoryName.MAIN))
    }

    @Throws(java.lang.Exception::class)
    private fun getTestConfigurations(): com.google.common.collect.ImmutableList<BuildConfigurationValue?> {
        return com.google.common.collect.ImmutableList.of<BuildConfigurationValue?>(
            create(),
            create("--cpu=piii"),
            create("--javacopt=foo"),
            create("--platform_suffix=-test"),
            create("--target_environment=//foo", "--target_environment=//bar"),
            create("--incompatible_merge_genfiles_directory"),
            create(
                "--define",
                "foo=#foo",
                "--define",
                "comma=a,b",
                "--define",
                "space=foo bar",
                "--define",
                "thing=a \"quoted\" thing",
                "--define",
                "qspace=a\\ quoted\\ space",
                "--define",
                "#a=pounda"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        // Unnecessary ImmutableList.copyOf apparently necessary to choose non-varargs constructor.
        SerializationTester(com.google.common.collect.ImmutableList.< E > copyOf < E ? > (getTestConfigurations()))
            .addDependency(FileSystem::class.java, getScratch().getFileSystem())
            .addDependency(OptionsChecksumCache::class.java, MapBackedChecksumCache())
            .setVerificationFunction({ subject: BuildConfigurationValue, deserialized: BuildConfigurationValue ->
                verifyDeserialized(
                    subject,
                    deserialized
                )
            })
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeyCodec() {
        SerializationTester(
            getTestConfigurations().stream()
                .map<Any?>(BuildConfigurationValue::getKey)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        )
            .addDependency(OptionsChecksumCache::class.java, MapBackedChecksumCache())
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatformInOutputDir_legacy_defaultPlatform() {
        val config: BuildConfigurationValue =
            create(
                "--experimental_platform_in_output_dir",
                "--experimental_use_platforms_in_output_dir_legacy_heuristic",
                "--cpu=k8"
            )

        assertThat(config.getOutputDirectory(RepositoryName.MAIN).getRoot().toString())
            .matches(".*/[^/]+-out/k8-fastbuild")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatformInOutputDir_legacy_withPlatform() {
        scratch.file("platform/BUILD", "platform(name = 'alpha')")
        val config: BuildConfigurationValue =
            create(
                "--experimental_platform_in_output_dir",
                "--experimental_use_platforms_in_output_dir_legacy_heuristic",
                "--platforms=//platform:alpha"
            )

        assertThat(config.getOutputDirectory(RepositoryName.MAIN).getRoot().toString())
            .matches(".*/[^/]+-out/alpha-fastbuild")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatformInOutputDir_defaultPlatform() {
        val config: BuildConfigurationValue =
            create(
                "--experimental_platform_in_output_dir",
                "--noexperimental_use_platforms_in_output_dir_legacy_heuristic",
                "--cpu=k8"
            )
        // See tests of these flags with platform_mappings for more realistic results.
        assertThat(config.getOutputDirectory(RepositoryName.MAIN).getRoot().toString())
            .matches(".*/[^/]+-out/platform-\\w*-fastbuild")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatformInOutputDir_withPlatform() {
        scratch.file("platform/BUILD", "platform(name = 'alpha')")
        val config: BuildConfigurationValue =
            create(
                "--experimental_platform_in_output_dir",
                "--noexperimental_use_platforms_in_output_dir_legacy_heuristic",
                "--platforms=//platform:alpha"
            )

        assertThat(config.getOutputDirectory(RepositoryName.MAIN).getRoot().toString())
            .matches(".*/[^/]+-out/platform-\\w*-fastbuild")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatformInOutputDir_withPlatformAndMatchingOverride() {
        scratch.file("platform/BUILD", "platform(name = 'alpha')")
        val config: BuildConfigurationValue =
            create(
                "--experimental_platform_in_output_dir",
                "--noexperimental_use_platforms_in_output_dir_legacy_heuristic",
                "--experimental_override_name_platform_in_output_dir=//platform:alpha=alpha",
                "--platforms=//platform:alpha"
            )

        assertThat(config.getOutputDirectory(RepositoryName.MAIN).getRoot().toString())
            .matches(".*/[^/]+-out/alpha-fastbuild")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatformInOutputDir_withPlatformAndNonMatchingOverride() {
        scratch.file("platform/BUILD", "platform(name = 'alpha')")
        val config: BuildConfigurationValue =
            create(
                "--experimental_platform_in_output_dir",
                "--noexperimental_use_platforms_in_output_dir_legacy_heuristic",
                "--experimental_override_name_platform_in_output_dir=//platform:beta=beta",
                "--platforms=//platform:alpha"
            )

        assertThat(config.getOutputDirectory(RepositoryName.MAIN).getRoot().toString())
            .matches(".*/[^/]+-out/platform-\\w*-fastbuild")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigurationEquality() {
        // Note that, in practice, test_arg should not be used as a no-op argument; however,
        // these configurations are never trimmed nor even used to build targets so not an issue.
        EqualsTester()
            .addEqualityGroup(
                createRaw(parseBuildOptions("--test_arg=1a"), "k8", false),
                createRaw(parseBuildOptions("--test_arg=1a"), "k8", false)
            ) // Different BuildOptions means non-equal
            .addEqualityGroup(
                createRaw(
                    parseBuildOptions("--test_arg=1b"),
                    "k8",
                    false
                )
            ) // Different --experimental_sibling_repository_layout means non-equal
            .addEqualityGroup(createRaw(parseBuildOptions("--test_arg=2"), "k8", true))
            .addEqualityGroup(
                createRaw(
                    parseBuildOptions("--test_arg=2"),
                    "k8",
                    false
                )
            ) // Different transitionDirectoryNameFragment means non-equal
            .addEqualityGroup(createRaw(parseBuildOptions("--test_arg=3"), "k8", false))
            .addEqualityGroup(createRaw(parseBuildOptions("--test_arg=3"), "arm", false))
            .addEqualityGroup(createRaw(parseBuildOptions("--test_arg=3"), "risc", false))
            .testEquals()
    }

    companion object {
        /**
         * Partial verification of deserialized BuildConfigurationValue.
         * 
         * 
         * Direct comparison of deserialized to subject doesn't work because Fragment classes do not
         * implement equals. This runs the part of BuildConfigurationValue.equals that has equals
         * definitions.
         */
        private fun verifyDeserialized(
            subject: BuildConfigurationValue, deserialized: BuildConfigurationValue
        ) {
            assertThat(deserialized.getOptions()).isEqualTo(subject.getOptions())
        }
    }
}
