// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.config

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider

/** Tests for [FlagSetFunction].  */
@RunWith(TestParameterInjector::class)
class FlagSetFunctionTest : BuildViewTestCase() {
    // TODO: b/409377907 - Most of this enforcement has been moved to ProjectFunction. Move the
    // corresponding tests to ProjectFunctionTest.
    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        writeProjectSclDefinition("test/project_proto.scl")
    }

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        builder.addConfigurationFragment(DummyTestFragment::class.java)
        return builder.build()
    }

    /**
     * Asserts a [FlagSetValue] contains a given kind of event with a given message that
     * occurred a given number of times.
     * 
     * 
     * Only applies to messages that are expected to persistently display, even on Skyframe cache
     * hits: see [FlagSetValue.getPersistentMessages].
     */
    private fun assertContainsPersistentMessage(
        value: FlagSetValue, kind: com.google.devtools.build.lib.events.EventKind?, frequency: Int, message: String?
    ) {
        var count = 0
        for (event in value.getPersistentMessages()) {
            if (event.getKind() != kind) {
                continue
            }
            count++
            Truth.assertThat(event.getMessage()).contains(message)
        }
        Truth.assertThat(count).isEqualTo(frequency)
    }

    /**
     * Given "//foo:myflag" and "default_value", creates the BUILD and .bzl files to realize a
     * string_flag with that label and default value.
     */
    @Throws(java.lang.Exception::class)
    private fun createStringFlag(labelName: String, defaultValue: String?) {
        val flagDir: String = labelName.substring(2, labelName.indexOf(":"))
        val flagName: String = labelName.substring(labelName.indexOf(":") + 1)
        scratch.file(
            flagDir + "/build_settings.bzl",
            """
        string_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.string(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
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

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagSetsFunction_returns_modified_buildOptions() {
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--platforms=//buildenv/platforms/android:x86"]
              ),
          ],
        )
        
        """.trimIndent()
        )
        scratch.file("test/BUILD")
        // given original BuildOptions and a valid key
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )
        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>("platforms"),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )
        val flagSetsValue: FlagSetValue = executeFunction(key)
        // expects the modified BuildOptions
        assertThat(flagSetsValue.getOptionsFromFlagset())
            .containsExactly("--platforms=//buildenv/platforms/android:x86")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noEnforceCanonical_unknown_sclConfig_returns_original_buildOptions() {
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--platforms=//buildenv/platforms/android:x86"]
              ),
          ],
        )
        
        """.trimIndent()
        )
        scratch.file("test/BUILD")
        // given valid project file but a nonexistent scl config
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )
        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "unknown_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                false
            )
        val flagSetsValue: FlagSetValue = executeFunction(key)

        // expects the original BuildOptions
        assertThat(flagSetsValue.getOptionsFromFlagset()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidEnforcementPolicy_fails() {
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          enforcement_policy = "invalid",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--platforms=//buildenv/platforms/android:x86"]
              ),
          ],
        )
        
        """.trimIndent()
        )
        scratch.file("test/BUILD")
        // given original BuildOptions and a valid key
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )
        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )
        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown)
            .hasMessageThat()
            .contains("invalid enforcement_policy 'invalid' in //test:PROJECT.scl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noEnforceCanonicalConfigs_noConfigsIsNoop() {
        scratch.file("test/PROJECT.scl", "")
        scratch.file("test/BUILD")
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )
        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "random_config_name",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                false
            )
        val flagSetsValue: FlagSetValue = executeFunction(key)

        // Without enforced configs, unknown configs are a no-op.
        assertThat(flagSetsValue.getOptionsFromFlagset()).isEmpty()
        assertContainsEvent("Ignoring --scl_config=random_config_name")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noEnforceCanonicalConfigs_sclConfigWarns() {
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--define=bar=bar"]
              ),
          ],
        )
        
        """.trimIndent()
        )
        scratch.file("test/BUILD")
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )
        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>("define"),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                false
            )

        val flagSetsValue: FlagSetValue = executeFunction(key)

        assertThat(flagSetsValue.getOptionsFromFlagset()).isEmpty()
        assertContainsEvent(
            "Ignoring --scl_config=test_config because --enforce_project_configs is not set"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enforceCanonicalConfigs_noConfigsIsNoop() {
        scratch.file("test/PROJECT.scl", "")
        scratch.file("test/BUILD")
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )
        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "fake_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                false
            )
        val flagSetsValue: FlagSetValue = executeFunction(key)

        assertThat(flagSetsValue.getOptionsFromFlagset()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enforceCanonicalConfigsValidConfig() {
        createStringFlag("//test:myflag",  /* defaultValue= */"default")
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--//test:myflag=test_config_value"],
                  is_default = True,
              ),
              buildable_unit_pb2.BuildableUnit.create(
                  name = "other_config",
                  flags = ["--//test:myflag=other_config_value"],
                  description = "user-friendly config description",
              ),
          ],
        )
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "other_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )
        val flagSetsValue: FlagSetValue = executeFunction(key)

        com.google.common.truth.Subject.contains("--//test:myflag=other_config_value")
        assertContainsPersistentMessage(
            flagSetsValue,
            com.google.devtools.build.lib.events.EventKind.INFO,  /* frequency= */
            1,
            "Applying flags from the config 'other_config'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun canonicalConfigs_acceptSpaceDelimitedFlags() {
        scratch.file("test/BUILD")
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--define foo=bar"],
                  is_default = True,
              ),
          ],
        )
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>("define"),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )
        val flagSetsValue: FlagSetValue = executeFunction(key)

        com.google.common.truth.Subject.contains("--define=foo=bar") // space is replaced with =
        assertContainsPersistentMessage(
            flagSetsValue,
            com.google.devtools.build.lib.events.EventKind.INFO,  /* frequency= */
            1,
            "Applying flags from the config 'test_config'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enforceCanonicalConfigsExtraNativeFlag_withSclConfig_fails() {
        scratch.file(
            "test/build_settings.bzl",
            """
string_flag = rule(implementation = lambda ctx: [], build_setting = config.string(flag = True))

""".trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          enforcement_policy = "strict",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--//test:myflag=test_config_value"]
              ),
              buildable_unit_pb2.BuildableUnit.create(
                  name = "other_config",
                  flags = ["--//test:myflag=other_config_value"]
              ),
          ],
        )
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions = createBuildOptions("--define=foo=bar")

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>("--define=foo=bar", ""),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown).hasMessageThat().contains("Found ['--define=foo=bar']")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enforceCanonicalConfigsFlag_warnPolicy_passes() {
        scratch.file(
            "test/build_settings.bzl",
            """
        string_flag = rule(implementation = lambda ctx: [], build_setting = config.string(flag = True))
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          enforcement_policy = "warn",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--//test:myflag=test_config_value"]
              ),
              buildable_unit_pb2.BuildableUnit.create(
                  name = "other_config",
                  flags = ["--//test:myflag=other_config_value"]
              ),
          ],
        )
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions = createBuildOptions("--define=foo=bar")

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>("--define=foo=bar", ""),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        assertContainsPersistentMessage(
            executeFunction(key),
            com.google.devtools.build.lib.events.EventKind.WARNING,  /* frequency= */
            1,
            "also sets output-affecting flags in the command line or user bazelrc:"
                    + " ['--define=foo=bar']"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enforceCanonicalConfigsFlag_compatiblePolicy_unrelatedFlag_warns() {
        scratch.file(
            "test/build_settings.bzl",
            """
        string_flag = rule(implementation = lambda ctx: [], build_setting = config.string(flag = True))
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          enforcement_policy = "compatible",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--//test:myflag=test_config_value"]
              ),
              buildable_unit_pb2.BuildableUnit.create(
                  name = "other_config",
                  flags = ["--//test:myflag=other_config_value"]
              ),
          ],
        )
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions = createBuildOptions("--define=foo=bar")

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>("--define=foo=bar", ""),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        assertContainsPersistentMessage(
            executeFunction(key),
            com.google.devtools.build.lib.events.EventKind.WARNING,  /* frequency= */
            1,
            "also sets output-affecting flags in the command line or user bazelrc:"
                    + " ['--define=foo=bar']"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enforceCanonicalConfigs_compatiblePolicy_onlyDifferentValue_fails() {
        scratch.file(
            "test/build_settings.bzl",
            """
        string_flag = rule(implementation = lambda ctx: [], build_setting = config.string(flag = True))
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        string_flag(
            name = "other_flag",
            build_setting_default = "default",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          enforcement_policy = "compatible",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--//test:myflag=test_config_value", "--//test:other_flag=test_config_value"]
              ),
          ],
        )
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions =
            createBuildOptions("--//test:myflag=other_value", "--//test:other_flag=test_config_value")

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "--//test:myflag=other_value", "", "--//test:other_flag=test_config_value", ""
                ),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown).hasMessageThat().contains("Found ['--//test:myflag=other_value']")
        Truth.assertThat(thrown).hasMessageThat().doesNotContain("['--//test:other_flag=test_config_value']")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun oldSchema_enforceCanonicalConfigs_wrongConfigsType() {
        scratch.file("test/BUILD")
        scratch.file(
            "test/PROJECT.scl",
            """
        project = {
          "configs": 1,
        }
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions = createBuildOptions("--define=foo=bar")

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown)
            .hasMessageThat()
            .contains("configs variable must be a map of strings to lists of strings")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun oldSchema_enforceCanonicalConfigs_wrongConfigsKeyType() {
        scratch.file("test/BUILD")
        scratch.file(
            "test/PROJECT.scl",
            """
        project = {
          "configs": {
            123: ["--compilation_mode=opt"],
          },
        }
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions = createBuildOptions("--define=foo=bar")

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown)
            .hasMessageThat()
            .contains("configs variable must be a map of strings to lists of strings")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun oldSchema_enforceCanonicalConfigs_wrongConfigsValueType() {
        scratch.file("test/BUILD")
        scratch.file(
            "test/PROJECT.scl",
            """
        project = {
          "configs": {
            "test_config": 123,
          },
        }
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions = createBuildOptions("--define=foo=bar")

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown)
            .hasMessageThat()
            .contains("configs variable must be a map of strings to lists of strings")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enforceCanonicalConfigsExtraFakeExpandedFlag_withSclConfig_fails() {
        scratch.file(
            "test/build_settings.bzl",
            """
        string_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.string(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          enforcement_policy = "strict",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--//test:myflag=test_config_value"]
              ),
              buildable_unit_pb2.BuildableUnit.create(
                  name = "other_config",
                  flags = ["--//test:myflag=other_config_value"]
              ),
          ],
        )
        
        """.trimIndent()
        )

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                createBuildOptions(),  // this is a fake flag so don't add it here.
                /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "--bar",
                    "--config=foo"
                ),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown).hasMessageThat().contains("Found ['--config=foo']")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enforceCanonicalConfigs_extraFlagThatIsAlsoInConfig_differentValue_fails() {
        scratch.file(
            "test/build_settings.bzl",
            """
        string_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.string(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          enforcement_policy = "strict",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--//test:myflag=test_config_value"]
              ),
          ],
        )
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions = createBuildOptions("--//test:myflag=other_value")

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "--//test:myflag=other_value",
                    ""
                ),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown).hasMessageThat().contains("Found ['--//test:myflag=other_value']")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enforceCanonicalConfigs_passedFlagThatIsInConfig_passes() {
        scratch.file(
            "test/build_settings.bzl",
            """
        string_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.string(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--//test:myflag=test_config_value"]
              ),
          ],
        )
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions = createBuildOptions("--//test:myflag=test_config_value")

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "--//test:myflag=test_config_value",
                    ""
                ),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val unused: FlagSetValue = executeFunction(key)
        assertDoesNotContainEvent("--scl_config must be the only configuration-affecting flag")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enforceCanonicalConfigsExtraStarlarkFlag_fails() {
        scratch.file(
            "test/build_settings.bzl",
            """
        string_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.string(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        string_flag(
            name = "starlark_flags_always_affect_configuration",
            build_setting_default = "default",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          enforcement_policy = "strict",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--//test:myflag=test_config_value"]
              ),
              buildable_unit_pb2.BuildableUnit.create(
                  name = "other_config",
                  flags = ["--//test:myflag=other_config_value"]
              ),
          ],
        )
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions =
            createBuildOptions("--//test:starlark_flags_always_affect_configuration=yes_they_do")

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "--//test:starlark_flags_always_affect_configuration=yes_they_do", ""
                ),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown)
            .hasMessageThat()
            .contains("Found ['--//test:starlark_flags_always_affect_configuration=yes_they_do']")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enforceCanonicalConfigsExtraTestFlag_passes() {
        scratch.file(
            "test/build_settings.bzl",
            """
        string_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.string(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        string_flag(
            name = "starlark_flags_always_affect_configuration",
            build_setting_default = "default",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          enforcement_policy = "strict",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--//test:myflag=test_config_value"]
              ),
              buildable_unit_pb2.BuildableUnit.create(
                  name = "other_config",
                  flags = ["--//test:myflag=other_config_value"]
              ),
          ],
        )
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions =
            createBuildOptions("--test_filter=foo", "--cache_test_results=true", "--test_arg=blah")

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "--test_filter=foo", "", "--cache_test_results=true", "", "--test_arg=blah", ""
                ),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val unused: FlagSetValue = executeFunction(key)
        assertDoesNotContainEvent("--scl_config must be the only configuration-affecting flag")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noEnforceCanonicalConfigs_noSclConfig_extraFlag_passes() {
        scratch.file(
            "test/build_settings.bzl",
            """
string_flag = rule(implementation = lambda ctx: [], build_setting = config.string(flag = True))

""".trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")
        string_flag(
            name = "myflag",
            build_setting_default = "default",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--//test:myflag=test_config_value"]
              ),
              buildable_unit_pb2.BuildableUnit.create(
                  name = "other_config",
                  flags = ["--//test:myflag=other_config_value"]
              ),
          ],
        )
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions = createBuildOptions("--define=foo=bar")

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "test_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>("--define=foo=bar", ""),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                false
            )

        val unused: FlagSetValue = executeFunction(key)
        assertDoesNotContainEvent("--scl_config must be the only configuration-affecting flag")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enforceCanonicalConfigsNonExistentConfig_fails() {
        createStringFlag("//test:myflag",  /* defaultValue= */"default")
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          enforcement_policy = "strict",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--//test:myflag=test_config_value"]
              ),
          ],
        )
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),
                "non_existent_config",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown)
            .hasMessageThat()
            .contains("--scl_config=non_existent_config is not a valid configuration for this project")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enforceCanonicalConfigsNoSclConfigFlagNoDefaultConfig() {
        createStringFlag("//test:myflag",  /* defaultValue= */"default")
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--//test:myflag=test_config_value"]
              ),
              buildable_unit_pb2.BuildableUnit.create(
                  name = "other_config",
                  flags = ["--//test:myflag=other_config_value"]
              ),
          ],
        )
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),  /* sclConfig= */
                "",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown)
            .hasMessageThat()
            .contains(
                "This project's builds must set --scl_config because no default config is defined"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun oldSchema_enforceCanonicalConfigsNonexistentDefaultConfig() {
        createStringFlag("//test:myflag",  /* defaultValue= */"default")
        scratch.file(
            "test/PROJECT.scl",
            """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
            "other_config": ['--//test:myflag=other_config_value'],
          },
            "default_config": "nonexistent_config",
        }
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),  /* sclConfig= */
                "",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown)
            .hasMessageThat()
            .contains("default_config must be a string matching a configs variable definition")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun oldSchema_enforceCanonicalConfigs_wrongDefaultConfigType() {
        createStringFlag("//test:myflag",  /* defaultValue= */"default")
        scratch.file(
            "test/PROJECT.scl",
            """
        project = {
          "configs": {
            "test_config": ['--//test:myflag=test_config_value'],
            "other_config": ['--//test:myflag=other_config_value'],
          },
          "default_config": ["test_config"],
        }
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),  /* sclConfig= */
                "",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown)
            .hasMessageThat()
            .contains("default_config must be a string matching a configs variable definition")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enforceCanonicalConfigsNoSclConfigFlagValidDefaultConfig() {
        createStringFlag("//test:myflag",  /* defaultValue= */"default")
        scratch.file(
            "test/PROJECT.scl",
            """
load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
project = project_pb2.Project.create(
  buildable_units = [
      buildable_unit_pb2.BuildableUnit.create(
          name = "test_config",
          flags = ["--//test:myflag=test_config_value"],
          is_default = True,
          description = "user-friendly config description",
      ),
  ],
)

""".trimIndent()
        )
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),  /* sclConfig= */
                "",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )
        val flagSetsValue: FlagSetValue = executeFunction(key)

        assertThat(flagSetsValue.getOptionsFromFlagset())
            .containsExactly("--//test:myflag=test_config_value")
        assertContainsPersistentMessage(
            flagSetsValue,
            com.google.devtools.build.lib.events.EventKind.INFO,  /* frequency= */
            1,
            "Applying flags from the config 'test_config'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun directlySet_nonBuildOptions_fails() {
        createStringFlag("//test:myflag",  /* defaultValue= */"default")
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--bazelrc=foo"],
                  is_default = True,
                  description = "user-friendly config description",
           ),
          ],
        )
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),  /* sclConfig= */
                "",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>("bazelrc"),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown)
            .hasMessageThat()
            .contains("project flags don't support non-output affecting option: --bazelrc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun directlySet_unrecognizedFlag_fails() {
        createStringFlag("//test:myflag",  /* defaultValue= */"default")
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--not_a_flag=1"],
                  is_default = True,
              ),
          ],
        )
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),  /* sclConfig= */
                "",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown).hasMessageThat().contains("unrecognized option: --not_a_flag")
    }

    @org.junit.Test
    @TestParameters(
        "{label: '//test:myflag', expectSuccess: true}",
        "{label: '//test:not_a_target', expectSuccess: false}",
        "{label: '//not_a_package:myflag', expectSuccess: false}",
        "{label: '//other:not_a_flag', expectSuccess: false}"
    )
    @Throws(java.lang.Exception::class)
    fun directlySet_starlarkFlag(label: String?, expectSuccess: Boolean) {
        createStringFlag("//test:myflag",  /* defaultValue= */"default")
        scratch.file(
            "other/BUILD",
            """
        genrule(name = "not_a_flag", outs = ["out"], cmd = "echo foo > ${'$'}@")
        
        """.trimIndent()
        )
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--%s=1"],
                  is_default = True,
              ),
          ],
        )
        
        """
                .trimIndent()
                .formatted(label)
        )
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),  /* sclConfig= */
                "",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        if (expectSuccess) {
            val flagSetsValue: FlagSetValue = executeFunction(key)
            assertThat(flagSetsValue.getOptionsFromFlagset())
                .containsExactly(String.format("--%s=1", label))
            assertContainsPersistentMessage(
                flagSetsValue,
                com.google.devtools.build.lib.events.EventKind.INFO,  /* frequency= */
                1,
                "Applying flags from the config 'test_config'"
            )
        } else {
            val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
                java.lang.Exception::class.java,
                org.junit.function.ThrowingRunnable { executeFunction(key) })
            Truth.assertThat(thrown).hasMessageThat().contains("unrecognized Starlark flag: --" + label)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicFlagsetFunctionalityWithTopLevelProjectSchema() {
        createStringFlag("//test:myflag",  /* defaultValue= */"default")
        scratch.file(
            "test/PROJECT.scl",
            """
        load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
        project = project_pb2.Project.create(
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "test_config",
                  flags = ["--//test:myflag=test_config_value"],
                  is_default = True,
                  description = "user-friendly config description",
              ),
          ],
        )
        
        """.trimIndent()
        )
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),  /* sclConfig= */
                "",
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )
        val flagSetsValue: FlagSetValue = executeFunction(key)

        assertThat(flagSetsValue.getOptionsFromFlagset())
            .containsExactly("--//test:myflag=test_config_value")
        assertContainsPersistentMessage(
            flagSetsValue,
            com.google.devtools.build.lib.events.EventKind.INFO,  /* frequency= */
            1,
            "Applying flags from the config 'test_config'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun clearUserDocumentationOfValidConfigs() {
        createStringFlag("//test:myflag",  /* defaultValue= */"default")
        scratch.file(
            "test/PROJECT.scl",
            """
load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
project = project_pb2.Project.create(
  enforcement_policy = "strict",
  buildable_units = [
      buildable_unit_pb2.BuildableUnit.create(
          name = "debug",
          flags = ["--//test:myflag=debug_value"],
      ),
      buildable_unit_pb2.BuildableUnit.create(
          name = "release",
          flags = ["--//test:myflag=release_value"],
      ),
  ],
)

""".trimIndent()
        )
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//test:test_target")),
                Label.parseCanonical("//test:PROJECT.scl"),  /* sclConfig= */
                null,
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown)
            .hasMessageThat()
            .contains(
                """
            This project's builds must set --scl_config because no default config is defined.

            This project supports:
              --scl_config=debug   -> [--//test:myflag=debug_value]
              --scl_config=release -> [--//test:myflag=release_value]

            This policy is defined in test/PROJECT.scl.
            
            """.trimIndent()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildingMultipleTargets_withSameConfig_isAllowed() {
        createStringFlag("//test:myflag",  /* defaultValue= */"default")
        scratch.file(
            "test/PROJECT.scl",
            """
load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
project = project_pb2.Project.create(
  enforcement_policy = "warn",
  buildable_units = [
      buildable_unit_pb2.BuildableUnit.create(
          name = "default",
          flags = [],
          is_default = True,
      ),
      buildable_unit_pb2.BuildableUnit.create(
          name = "debug",
          flags = ["--//test:myflag=debug_value"],
      ),
      buildable_unit_pb2.BuildableUnit.create(
          name = "release",
          flags = ["--//test:myflag=release_value"],
      ),
  ],
)

""".trimIndent()
        )
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(
                    Label.parseCanonical("//test:test_target"),
                    Label.parseCanonical("//test:test_target2")
                ),
                Label.parseCanonical("//test:PROJECT.scl"),  /* sclConfig= */
                null,
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val unused: FlagSetValue = executeFunction(key)
        assertDoesNotContainEvent(
            "Cannot parse options: Building target(s) with different configurations are not supported"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleTargetsWithMismatchingDefaultBuildableUnitsFails() {
        createStringFlag("//test:myflag",  /* defaultValue= */"default")
        scratch.file(
            "test/PROJECT.scl",
            """
load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
project = project_pb2.Project.create(
  buildable_units = [
      buildable_unit_pb2.BuildableUnit.create(
          name = "foo_default",
          target_patterns = ["//test:test_target"],
          flags = ["--//test:myflag=foo_default_value"],
          is_default = True,
      ),
      buildable_unit_pb2.BuildableUnit.create(
          name = "bar_default",
          target_patterns = ["//test:test_target2"],
          flags = ["--//test:myflag=bar_default_value"],
          is_default = True,
      ),
  ],
)

""".trimIndent()
        )
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(
                    Label.parseCanonical("//test:test_target"),
                    Label.parseCanonical("//test:test_target2")
                ),
                Label.parseCanonical("//test:PROJECT.scl"),  /* sclConfig= */
                null,
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { executeFunction(key) })
        Truth.assertThat(thrown)
            .hasMessageThat()
            .contains("Building target(s) with different configurations are not supported")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleTargetsWithDifferentDefaultsSucceedsIfSameFlags() {
        createStringFlag("//test:myflag",  /* defaultValue= */"default")
        scratch.file(
            "test/PROJECT.scl",
            """
load("//test:project_proto.scl", "buildable_unit_pb2", "project_pb2")
project = project_pb2.Project.create(
  buildable_units = [
      buildable_unit_pb2.BuildableUnit.create(
          name = "foo_default",
          target_patterns = ["//test:test_target"],
          flags = ["--//test:myflag=common_default_value"],
          is_default = True,
      ),
      buildable_unit_pb2.BuildableUnit.create(
          name = "bar_default",
          target_patterns = ["//test:test_target2"],
          flags = ["--//test:myflag=common_default_value"],
          is_default = True,
      ),
  ],
)

""".trimIndent()
        )
        val buildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(
                ruleClassProvider.getFragmentRegistry().getOptionsClasses()
            )

        val key: FlagSetValue.Key? =
            FlagSetValue.Key.create(
                com.google.common.collect.ImmutableSet.of<E?>(
                    Label.parseCanonical("//test:test_target"),
                    Label.parseCanonical("//test:test_target2")
                ),
                Label.parseCanonical("//test:PROJECT.scl"),  /* sclConfig= */
                null,
                buildOptions,  /* allOptionNames= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* userOptions= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* configFlagDefinitions= */
                ConfigFlagDefinitions.NONE,  /* enforceCanonical= */
                true
            )

        val unused: FlagSetValue = executeFunction(key)
        assertNoEvents()
    }

    @Throws(java.lang.Exception::class)
    private fun executeFunction(key: FlagSetValue.Key?): FlagSetValue {
        val skyframeExecutor: SkyframeExecutor = getSkyframeExecutor()
        val result: EvaluationResult<FlagSetValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key,  /* keepGoing= */false, reporter)
        if (result.hasError()) {
            throw result.getError(key).getException()
        }
        return result.get(key)
    }

    @org.junit.Test
    @TestParameters(valuesProvider = TargetPatternProvider::class)
    fun doesBuildableUnitMatchTarget(
        included: Boolean, buildableUnit: BuildableUnit?, label: Label?
    ) {
        assertThat(FlagSetFunction.doesBuildableUnitMatchTarget(buildableUnit, label))
            .isEqualTo(included)
    }

    internal class TargetPatternProvider :
        com.google.testing.junit.testparameterinjector.TestParametersValuesProvider() {
        @Throws(java.lang.Exception::class)
        override fun provideValues(context: com.google.testing.junit.testparameterinjector.TestParametersValuesProvider.Context?): com.google.common.collect.ImmutableList<TestParametersValues?> {
            return com.google.common.collect.ImmutableList.of<TestParametersValues?>( // Single pattern
                Companion.create(true, "//foo:foo", "//foo:foo"),
                Companion.create(false, "//foo:foo", "//foo:bar"),
                Companion.create(true, "//foo/...", "//foo:foo"),
                Companion.create(true, "//foo/...", "//foo/bar:bar"),
                Companion.create(false, "//foo/...", "//bar:bar"),
                Companion.create(false, "//foo/bar/...", "//foo:foo"),  // Multiple patterns

                Companion.create(
                    true,
                    com.google.common.collect.ImmutableList.of<String?>("//foo:foo", "//bar:bar"),
                    "//foo:foo"
                ),
                Companion.create(
                    true,
                    com.google.common.collect.ImmutableList.of<String?>("//foo:foo", "//bar:bar"),
                    "//bar:bar"
                ),
                Companion.create(
                    false,
                    com.google.common.collect.ImmutableList.of<String?>("//foo:foo", "//bar:bar"),
                    "//quux:quux"
                ),  // Negative patterns

                Companion.create(false, "-//foo:foo", "//foo:foo"),
                Companion.create(false, "-//foo/...", "//foo:foo"),
                Companion.create(
                    false,
                    com.google.common.collect.ImmutableList.of<String?>("//foo/...", "-//foo/bar/..."),
                    "//foo/bar:bar"
                ),
                Companion.create(
                    true,
                    com.google.common.collect.ImmutableList.of<String?>("//foo/...", "-//foo/bar/..."),
                    "//foo:foo"
                ),
                Companion.create(
                    true,
                    com.google.common.collect.ImmutableList.of<String?>(
                        "//foo/...",
                        "-//foo/bar/...",
                        "//foo/bar/baz/..."
                    ),
                    "//foo/bar/baz"
                ),
                Companion.create(
                    true,
                    com.google.common.collect.ImmutableList.of<String?>(
                        "//foo/...",
                        "-//foo/bar/...",
                        "//foo/bar/baz/..."
                    ),
                    "//foo:foo"
                ),
                Companion.create(
                    false,
                    com.google.common.collect.ImmutableList.of<String?>(
                        "//foo/...",
                        "-//foo/bar/...",
                        "//foo/bar/baz/..."
                    ),
                    "//foo/bar/quux"
                )
            )
        }

        companion object {
            @Throws(java.lang.Exception::class)
            private fun create(included: Boolean, pattern: String, label: String?): TestParametersValues {
                return Companion.create(included, com.google.common.collect.ImmutableList.of<String?>(pattern), label)
            }

            @Throws(java.lang.Exception::class)
            private fun create(
                included: Boolean, patterns: com.google.common.collect.ImmutableList<String?>?, label: String?
            ): TestParametersValues {
                val name: String = String.format("%s-%s-%s", if (included) "included" else "excluded", patterns, label)
                val buildableUnit: BuildableUnit? =
                    BuildableUnit.create(
                        "test",
                        patterns,
                        "Test Unit",
                        com.google.common.collect.ImmutableList.of<E?>("--flag"),  /* isDefault= */
                        true
                    )
                return TestParametersValues.builder()
                    .name(name)
                    .addParameter("included", included)
                    .addParameter("buildableUnit", buildableUnit)
                    .addParameter("label", Label.parseCanonicalUnchecked(label))
                    .build()
            }
        }
    }
}
