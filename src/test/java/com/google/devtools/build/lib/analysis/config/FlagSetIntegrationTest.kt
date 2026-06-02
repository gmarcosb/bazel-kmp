// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

/**
 * Integration tests for building with `--scl_config` (flag sets). See [Project] and
 * [CoreOptions.sclConfig] for details.
 * 
 * 
 * Use this for tests that cover building targets with `--scl-config`.
 * 
 * 
 * If you just want to test [com.google.devtools.build.lib.skyframe.config.FlagSetFunction]
 * (i.e. parsing --scl_config independent of how builds use it), use [ ].
 * 
 * 
 * If you need full end-to-end testing, use `flagset_tests.sh`.
 */
@RunWith(TestParameterInjector::class)
class FlagSetIntegrationTest : BuildIntegrationTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setup() {
        writeProjectSclDefinition("test/project_proto.scl",  /* alsoWriteBuildFile= */true)
    }

    /**
     * Given "//foo:myflag" and "default_value", creates the BUILD and .bzl files to realize a
     * string_flag with that label and default value.
     */
    @Throws(java.lang.Exception::class)
    private fun createStringFlag(labelName: String, defaultValue: String?) {
        val flagDir: String = labelName.substring(2, labelName.indexOf(":"))
        val flagName: String = labelName.substring(labelName.indexOf(":") + 1)
        write(
            flagDir + "/build_settings.bzl",
            """
string_flag = rule(implementation = lambda ctx: [], build_setting = config.string(flag = True))

""".trimIndent()
        )
        write(
            flagDir + "/BUILD",
            """
        load(":build_settings.bzl", "string_flag")
        string_flag(
            name = "%s",
            build_setting_default = "%s",
        )
        
        """
                .trimIndent()
                .formatted(flagName, defaultValue)
        )
    }

    /** Given ""//test:s", creates the BUILD and .bzl files for a trivial target with that label.  */
    @Throws(java.lang.Exception::class)
    private fun createSimpleTarget(labelName: String) {
        val targetDir: String = labelName.substring(2, labelName.indexOf(":"))
        val targetName: String = labelName.substring(labelName.indexOf(":") + 1)
        write(
            targetDir + "/defs.bzl",
            """
simple_rule = rule(
 implementation = lambda ctx: [],
 attrs = {}
 )

""".trimIndent()
        )
        write(
            targetDir + "/BUILD",
            """
load(":defs.bzl", "simple_rule")
simple_rule(name = "%s")

"""
                .trimIndent()
                .formatted(targetName)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noSclConfigSetAndNoDefaultConfig(@TestParameter enforceProjectConfigs: Boolean) {
        createSimpleTarget("//test:s")
        write(
            "test/PROJECT.scl",
            """
load(
  "//test:project_proto.scl",
  "buildable_unit_pb2",
  "project_pb2",
)
project = project_pb2.Project.create(
    enforcement_policy = "warn",
    buildable_units = [
      buildable_unit_pb2.BuildableUnit.create(
          name = "test_config",
          flags = ["--//test:myflag=test_config_value"],
          is_default = False,
      )
  ],
)

""".trimIndent()
        )

        addOptions("--enforce_project_configs=" + (if (enforceProjectConfigs) "1" else "0"))
        if (!enforceProjectConfigs) {
            // There's no default project config but that doesn't matter when project enforcement is
            // disabled: the entire PROJECT.scl is ignored.
            assertThat(buildTarget("//test:s")).isNotNull()
        } else {
            // With project enforcement enabled, no default config means user must set --scl_config.
            val expectedError: InvalidConfigurationException? =
                org.junit.Assert.assertThrows<T?>(
                    InvalidConfigurationException::class.java,
                    org.junit.function.ThrowingRunnable { buildTarget("//test:s") })
            assertThat(expectedError)
                .hasMessageThat()
                .contains("This project's builds must set --scl_config")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun warnModeAddsBothUserAndProjectStarlarkFlags() {
        createStringFlag("//test1:project_flag",  /* defaultValue= */"default")
        createStringFlag("//test2:user_flag",  /* defaultValue= */"default")
        createSimpleTarget("//test:s")
        write(
            "test/PROJECT.scl",
            """
load(
  "//test:project_proto.scl",
  "buildable_unit_pb2",
  "project_pb2",
)
project = project_pb2.Project.create(
    enforcement_policy = "warn",
    buildable_units = [
      buildable_unit_pb2.BuildableUnit.create(
          name = "test_config",
          flags = ["--//test1:project_flag=set_by_project"],
          is_default = True,
      )
  ],
)

""".trimIndent()
        )

        addOptions("--enforce_project_configs=1", "--//test2:user_flag=set_by_user")
        val result: BuildResult = buildTarget("//test:s")

        assertThat(result).isNotNull()
        assertThat(result.getBuildConfiguration().getOptions().getStarlarkOptions())
            .containsExactly(
                Label.parseCanonicalUnchecked("//test1:project_flag"),
                "set_by_project",
                Label.parseCanonicalUnchecked("//test2:user_flag"),
                "set_by_user"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun warnMode_userFlagTakesPrecedenceOverProjectFlag() {
        createStringFlag("//test1:flag",  /* defaultValue= */"default")
        createSimpleTarget("//test:s")
        write(
            "test/PROJECT.scl",
            """
load(
  "//test:project_proto.scl",
  "buildable_unit_pb2",
  "project_pb2",
)
project = project_pb2.Project.create(
    enforcement_policy = "warn",
    buildable_units = [
      buildable_unit_pb2.BuildableUnit.create(
          name = "test_config",
          flags = ["--//test1:flag=set_by_project"],
          is_default = True,
      )
  ],
)

""".trimIndent()
        )

        addOptions("--enforce_project_configs=1", "--//test1:flag=set_by_user")
        val result: BuildResult = buildTarget("//test:s")

        assertThat(result).isNotNull()
        assertThat(result.getBuildConfiguration().getOptions().getStarlarkOptions())
            .containsExactly(Label.parseCanonicalUnchecked("//test1:flag"), "set_by_user")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun warnMode_allowMultipleFlagShowsUserSettingsLast() {
        write(
            "test1//build_settings.bzl",
            """
repeatable_string_flag = rule(
    implementation = lambda ctx: [],
    build_setting = config.string_list(flag = True, repeatable = True),
)

""".trimIndent()
        )
        write(
            "test1/BUILD",
            """
        load(":build_settings.bzl", "repeatable_string_flag")
        repeatable_string_flag(
            name = "flag",
            build_setting_default = [],
        )
        
        """.trimIndent()
        )
        createSimpleTarget("//test:s")
        write(
            "test/PROJECT.scl",
            """
load(
  "//test:project_proto.scl",
  "buildable_unit_pb2",
  "project_pb2",
)
project = project_pb2.Project.create(
    enforcement_policy = "warn",
    buildable_units = [
      buildable_unit_pb2.BuildableUnit.create(
          name = "test_config",
          flags = ["--//test1:flag=set_by_project"],
          is_default = True,
      )
  ],
)

""".trimIndent()
        )

        addOptions("--enforce_project_configs=1", "--//test1:flag=set_by_user")
        val result: BuildResult = buildTarget("//test:s")

        assertThat(result).isNotNull()
        assertThat(result.getBuildConfiguration().getOptions().getStarlarkOptions())
            .containsExactly(
                Label.parseCanonicalUnchecked("//test1:flag"),
                com.google.common.collect.ImmutableList.of<E?>("set_by_project", "set_by_user")
            )
    }
}
