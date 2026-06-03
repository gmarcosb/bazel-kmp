// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.MissingInputFileException

/**
 * Unit tests for [PlatformMappingFunction].
 * 
 * 
 * Note that all parsing tests are located in [PlatformMappingFunctionParserTest].
 */
@RunWith(JUnit4::class)
class PlatformMappingFunctionTest : BuildViewTestCase() {
    /** Extra options for this test.  */
    @OptionsClass
    abstract class DummyTestOptions : FragmentOptions() {
        @get:com.google.devtools.common.options.Option(
            name = "str_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defVal"
        )
        abstract val strOption: String?

        @get:com.google.devtools.common.options.Option(
            name = "internal_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "super secret",
            metadataTags = [OptionMetadataTag.INTERNAL]
        )
        abstract val internalOption: String?

        @get:com.google.devtools.common.options.Option(
            name = "list",
            converter = CommaSeparatedOptionListConverter::class,
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val list: MutableList<String?>?
    }

    /** Test fragment.  */
    @RequiresOptions(options = [com.google.devtools.build.lib.skyframe.config.PlatformMappingFunctionTest.DummyTestOptions::class])
    class DummyTestOptionsFragment(buildOptions: BuildOptions?) : Fragment() {
        private val buildOptions: BuildOptions?

        init {
            this.buildOptions = buildOptions
        }

        // Getter required to satisfy AutoCodec.
        fun getBuildOptions(): BuildOptions? {
            return buildOptions
        }
    }

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        // Needed to properly initialize skyframe.
        TestRuleClassProvider.addStandardRules(builder)
        builder.addConfigurationFragment(com.google.devtools.build.lib.skyframe.config.PlatformMappingFunctionTest.DummyTestOptionsFragment::class.java)
        return builder.build()
    }

    @org.junit.Test
    fun invalidMappingFile_doesNotExist_customLocation() {
        val exception: PlatformMappingException? =
            org.junit.Assert.assertThrows<T?>(
                PlatformMappingException::class.java,
                org.junit.function.ThrowingRunnable {
                    executeFunction(
                        PlatformMappingKey.createExplicitlySet(
                            PathFragment.create("random_location")
                        )
                    )
                })
        assertThat(exception).hasCauseThat().isInstanceOf(MissingInputFileException::class.java)
        assertThat(exception).hasMessageThat().contains("random_location")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidMappingFile_doesNotExist_defaultLocation() {
        val platformMappingValue: PlatformMappingValue = executeFunction(PlatformMappingKey.DEFAULT)

        val mapped: BuildOptions = platformMappingValue.map(createBuildOptions()).getOptions()

        assertThat(mapped.get(PlatformOptions::class.java).getPlatforms())
            .containsExactly(Label.parseCanonicalUnchecked("@bazel_tools//tools:host_platform"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidMappingFile_isDirectory() {
        scratch.dir("somedir")

        val exception: PlatformMappingException? =
            org.junit.Assert.assertThrows<T?>(
                PlatformMappingException::class.java,
                org.junit.function.ThrowingRunnable {
                    executeFunction(
                        PlatformMappingKey.createExplicitlySet(PathFragment.create("somedir"))
                    )
                })
        assertThat(exception).hasCauseThat().isInstanceOf(MissingInputFileException::class.java)
        assertThat(exception).hasMessageThat().contains("somedir")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mapFromPlatform() {
        scratch.file(
            "my_mapping_file",
            """
        platforms:
          //platforms:one
            --str_option=one
        
        """.trimIndent()
        )

        val platformMappingValue: PlatformMappingValue =
            executeFunction(
                PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file"))
            )

        val modifiedOptions: BuildOptions = createBuildOptions("--platforms=//platforms:one")

        val mapped: BuildOptions = platformMappingValue.map(modifiedOptions).getOptions()

        assertThat(
            mapped.get(com.google.devtools.build.lib.skyframe.config.PlatformMappingFunctionTest.DummyTestOptions::class.java)
                .getStrOption()
        ).isEqualTo("one")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mapFromPlatform_fromAlternatePackagePath() {
        scratch.setWorkingDir("/other/package/path")
        scratch.copyFile(rootDirectory.getRelative("MODULE.bazel").getPathString(), "MODULE.bazel")
        setPackageOptions("--package_path=/other/package/path")
        scratch.file(
            "my_mapping_file",
            """
        platforms:
          //platforms:one
            --str_option=one
        
        """.trimIndent()
        )

        val platformMappingValue: PlatformMappingValue =
            executeFunction(
                PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file"))
            )

        val modifiedOptions: BuildOptions = createBuildOptions("--platforms=//platforms:one")

        val mapped: BuildOptions = platformMappingValue.map(modifiedOptions).getOptions()

        assertThat(
            mapped.get(com.google.devtools.build.lib.skyframe.config.PlatformMappingFunctionTest.DummyTestOptions::class.java)
                .getStrOption()
        ).isEqualTo("one")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mapFromPlatform_noWorkspace() {
        // --package_path is not relevant for Bazel and difficult to get to work correctly with
        // WORKSPACE suffixes in tests.
        if (analysisMock.isThisBazel) {
            return
        }

        scratch.setWorkingDir("/other/package/path")
        scratch.file(
            "my_mapping_file",
            """
        platforms:
          //platforms:one
            --str_option=one
        
        """.trimIndent()
        )
        setPackageOptions("--package_path=/other/package/path")

        val platformMappingValue: PlatformMappingValue =
            executeFunction(
                PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file"))
            )
        val modifiedOptions: BuildOptions = createBuildOptions("--platforms=//platforms:one")

        val mapped: BuildOptions = platformMappingValue.map(modifiedOptions).getOptions()

        assertThat(
            mapped.get(com.google.devtools.build.lib.skyframe.config.PlatformMappingFunctionTest.DummyTestOptions::class.java)
                .getStrOption()
        ).isEqualTo("one")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multiplePackagePaths() {
        scratch.setWorkingDir("/other/package/path")
        scratch.file(
            "my_mapping_file",
            """
        platforms:
          //platforms:one
            --str_option=one
        
        """.trimIndent()
        )
        setPackageOptions("--package_path=%workspace%:/other/package/path")

        val platformMappingValue: PlatformMappingValue =
            executeFunction(
                PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file"))
            )

        val modifiedOptions: BuildOptions = createBuildOptions("--platforms=//platforms:one")

        val mapped: BuildOptions = platformMappingValue.map(modifiedOptions).getOptions()

        assertThat(
            mapped.get(com.google.devtools.build.lib.skyframe.config.PlatformMappingFunctionTest.DummyTestOptions::class.java)
                .getStrOption()
        ).isEqualTo("one")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multiplePackagePathsFirstWins() {
        scratch.file(
            "my_mapping_file",
            """
        platforms:
          //platforms:one
            --str_option=one
        
        """.trimIndent()
        )
        scratch.setWorkingDir("/other/package/path")
        scratch.file(
            "my_mapping_file",
            """
        platforms:
          //platforms:one
            --str_option=two
        
        """.trimIndent()
        )
        setPackageOptions("--package_path=%workspace%:/other/package/path")

        val platformMappingValue: PlatformMappingValue =
            executeFunction(
                PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file"))
            )

        val modifiedOptions: BuildOptions = createBuildOptions("--platforms=//platforms:one")

        val mapped: BuildOptions = platformMappingValue.map(modifiedOptions).getOptions()

        assertThat(
            mapped.get(com.google.devtools.build.lib.skyframe.config.PlatformMappingFunctionTest.DummyTestOptions::class.java)
                .getStrOption()
        ).isEqualTo("one")
    }

    // Internal flags (OptionMetadataTag.INTERNAL) cannot be set from the command-line, but
    // platform mapping needs to access them.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mapFromPlatform_internalOption() {
        scratch.file(
            "my_mapping_file",
            """
        platforms:
          //platforms:one
            --internal_option=something_new
        
        """.trimIndent()
        )

        val platformMappingValue: PlatformMappingValue =
            executeFunction(
                PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file"))
            )

        val modifiedOptions: BuildOptions = createBuildOptions("--platforms=//platforms:one")

        val mapped: BuildOptions = platformMappingValue.map(modifiedOptions).getOptions()

        assertThat(
            mapped.get(com.google.devtools.build.lib.skyframe.config.PlatformMappingFunctionTest.DummyTestOptions::class.java)
                .getInternalOption()
        ).isEqualTo("something_new")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mapFromPlatform_starlarkFlag() {
        writeStarlarkFlag()
        scratch.file(
            "my_mapping_file",
            """
        platforms:
          //platforms:one
            --//flag:my_string_flag=mapped_value
        
        """.trimIndent()
        )

        val platformMappingValue: PlatformMappingValue =
            executeFunction(
                PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file"))
            )

        val modifiedOptions: BuildOptions = createBuildOptions("--platforms=//platforms:one")

        val mapped: BuildOptions = platformMappingValue.map(modifiedOptions).getOptions()

        assertThat(mapped.getStarlarkOptions())
            .containsExactly(Label.parseCanonical("//flag:my_string_flag"), "mapped_value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mapFromPlatform_listFlag_overridesConfig() {
        scratch.file(
            "my_mapping_file",
            """
        platforms:
          //platforms:one
            --list=from_mapping
        
        """.trimIndent()
        )

        val platformMappingValue: PlatformMappingValue =
            executeFunction(
                PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file"))
            )

        val modifiedOptions: BuildOptions =
            createBuildOptions("--platforms=//platforms:one", "--list=from_config")

        val mapped: BuildOptions = platformMappingValue.map(modifiedOptions).getOptions()

        // The mapping should completely replace the list, because it is not accumulating.
        assertThat(
            mapped.get(com.google.devtools.build.lib.skyframe.config.PlatformMappingFunctionTest.DummyTestOptions::class.java)
                .getList()
        ).containsExactly("from_mapping")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mapFromPlatform_badStarlarkFlag() {
        scratch.file("test/BUILD") // Set up a valid package but invalid flag.
        scratch.file(
            "my_mapping_file",
            """
        platforms:
          //platforms:one
            --//test:this_flag_doesnt_exist=mapped_value
        
        """.trimIndent()
        )

        val exception: PlatformMappingException? =
            org.junit.Assert.assertThrows<T?>(
                PlatformMappingException::class.java,
                org.junit.function.ThrowingRunnable {
                    executeFunction(
                        PlatformMappingKey.createExplicitlySet(
                            PathFragment.create("my_mapping_file")
                        )
                    )
                })
        assertThat(exception).hasCauseThat().isInstanceOf(PlatformMappingParsingException::class.java)
        assertThat(exception).hasMessageThat().contains("Failed to load //test:this_flag_doesnt_exist")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun platformTransitionWithStarlarkFlagMapping() {
        writeStarlarkFlag()

        // Define a custom platform and mapping from that platform to the flag:
        scratch.file(
            "test/platforms/BUILD",
            """
        platform(
            name = "my_platform",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "my_mapping_file",
            """
        platforms:
          //test/platforms:my_platform
            --//flag:my_string_flag=platform-mapped value
        
        """.trimIndent()
        )

        // Define a rule that platform-transitions its deps:
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
        scratch.file(
            "test/starlark/rules.bzl",
            """
        def transition_func(settings, attr):
            return {"//command_line_option:platforms": "//test/platforms:my_platform"}

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = ["//command_line_option:platforms"],
        )
        transition_rule = rule(
            implementation = lambda ctx: [],
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )
        scratch.file("test/starlark/BUILD")

        // Define a target to build and its dep:
        scratch.file(
            "test/BUILD",
            """
        load("//test/starlark:rules.bzl", "transition_rule")

        transition_rule(
            name = "main",
            dep = ":dep",
        )

        transition_rule(name = "dep")
        
        """.trimIndent()
        )

        // Set the Starlark flag explicitly. Otherwise it won't show up at all in the top-level config's
        // getOptions().getStarlarkOptions() map.
        useConfiguration(
            "--//flag:my_string_flag=top-level value", "--platform_mappings=my_mapping_file"
        )
        val main: ConfiguredTarget = getConfiguredTarget("//test:main")
        val dep: ConfiguredTarget = getDirectPrerequisite(main, "//test:dep")

        assertThat(getConfiguration(main).getOptions().getStarlarkOptions())
            .containsAtLeast(Label.parseCanonical("//flag:my_string_flag"), "top-level value")
        assertThat(getConfiguration(dep).getOptions().getStarlarkOptions())
            .containsAtLeast(Label.parseCanonical("//flag:my_string_flag"), "platform-mapped value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mapFromFlag() {
        scratch.file(
            "my_mapping_file",
            """
        flags:
          --str_option=one
              //platforms:one
        
        """.trimIndent()
        )

        val platformMappingValue: PlatformMappingValue =
            executeFunction(
                PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file"))
            )

        val modifiedOptions: BuildOptions = createBuildOptions("--str_option=one")

        val mapped: BuildOptions = platformMappingValue.map(modifiedOptions).getOptions()

        assertThat(mapped.get(PlatformOptions::class.java).getPlatforms()).containsExactly(PLATFORM1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mapFromFlag_starlarkFlag() {
        writeStarlarkFlag()
        scratch.file(
            "my_mapping_file",
            """
        flags:
          --//flag:my_string_flag=mapped_value
            //platforms:one
        
        """.trimIndent()
        )

        val platformMappingValue: PlatformMappingValue =
            executeFunction(
                PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file"))
            )

        val modifiedOptions: BuildOptions = createBuildOptions("--//flag:my_string_flag=mapped_value")

        val mapped: BuildOptions = platformMappingValue.map(modifiedOptions).getOptions()

        assertThat(mapped.get(PlatformOptions::class.java).getPlatforms()).containsExactly(PLATFORM1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mapFromFlag_badStarlarkFlag() {
        scratch.file("test/BUILD") // Set up a valid package but invalid flag.
        scratch.file(
            "my_mapping_file",
            """
        flags:
          --//test:this_flag_doesnt_exist=mapped_value
            //platforms:one
        
        """.trimIndent()
        )

        val exception: PlatformMappingException? =
            org.junit.Assert.assertThrows<T?>(
                PlatformMappingException::class.java,
                org.junit.function.ThrowingRunnable {
                    executeFunction(
                        PlatformMappingKey.createExplicitlySet(
                            PathFragment.create("my_mapping_file")
                        )
                    )
                })
        assertThat(exception).hasCauseThat().isInstanceOf(PlatformMappingParsingException::class.java)
        assertThat(exception).hasMessageThat().contains("Failed to load //test:this_flag_doesnt_exist")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mappingSyntaxError() {
        scratch.file("test/BUILD")
        scratch.file(
            "my_mapping_file",
            """
        platforms:
          //platforms:one
            --str_option=k8
          # Duplicate platform label
          //platforms:one
            --str_option=arm
        
        """.trimIndent()
        )

        val exception: PlatformMappingException? =
            org.junit.Assert.assertThrows<T?>(
                PlatformMappingException::class.java,
                org.junit.function.ThrowingRunnable {
                    executeFunction(
                        PlatformMappingKey.createExplicitlySet(
                            PathFragment.create("my_mapping_file")
                        )
                    )
                })
        assertThat(exception).hasCauseThat().isInstanceOf(PlatformMappingParsingException::class.java)
        assertThat(exception).hasMessageThat().contains("Got duplicate platform entries")
    }

    @Throws(java.lang.Exception::class)
    private fun writeStarlarkFlag() {
        scratch.file(
            "flag/build_setting.bzl",
            """
        def _impl(ctx):
            return []

        string_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "flag/BUILD",
            """
        load("//flag:build_setting.bzl", "string_flag")

        string_flag(
            name = "my_string_flag",
            build_setting_default = "default value",
        )
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun executeFunction(key: PlatformMappingKey?): PlatformMappingValue {
        val skyframeExecutor: SkyframeExecutor = getSkyframeExecutor()
        val result: EvaluationResult<PlatformMappingValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key,  /*keepGoing=*/false, reporter)
        if (result.hasError()) {
            throw result.getError(key).getException()
        }
        return result.get(key)
    }

    companion object {
        private val PLATFORM1: Label? = Label.parseCanonicalUnchecked("//platforms:one")
    }
}
