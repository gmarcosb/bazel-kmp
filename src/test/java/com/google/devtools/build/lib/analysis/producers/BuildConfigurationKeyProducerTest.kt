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
package com.google.devtools.build.lib.analysis.producers

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSortedSet
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider
import com.google.devtools.common.options.Option
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable

/** Tests of [BuildConfigurationKeyProducer].  */
@RunWith(JUnit4::class)
class BuildConfigurationKeyProducerTest : ProducerTestCase() {
    @Before
    @Throws(Exception::class)
    fun initializeSkyframExecutor() {
        val analysisMock: AnalysisMock = AnalysisMock.Companion.get()

        val ruleClassProvider: ConfiguredRuleClassProvider = analysisMock.createRuleClassProvider()
        val buildOptionClasses: ImmutableSortedSet<Class<out FragmentOptions?>?>? =
            ruleClassProvider.getFragmentRegistry().getOptionsClasses()

        val skyframeExecutor: SequencedSkyframeExecutor = getSkyframeExecutor()
        val defaultBuildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(buildOptionClasses).clone()
        skyframeExecutor.injectExtraPrecomputedValues(
            ImmutableList.Builder<PrecomputedValue.Injected?>()
                .add(
                    PrecomputedValue.injected(
                        BaselineOptionsFunction.BASELINE_CONFIGURATION, defaultBuildOptions
                    )
                )
                .addAll(analysisMock.getPrecomputedValues())
                .build()
        )
    }

    @Before
    @Throws(Exception::class)
    fun iniitalizeProjectScl() {
        setBuildLanguageOptions("--experimental_enable_scl_dialect=true")
        writeProjectSclDefinition("test/project_proto.scl")
        scratch.file("test/BUILD")
    }

    /** Extra options for this test.  */
    @OptionsClass
    abstract class DummyTestOptions : FragmentOptions() {
        @get:Option(
            name = "option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "from_default"
        )
        abstract val option: String?

        @get:Option(
            name = "internal_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "from_default",
            metadataTags = [OptionMetadataTag.INTERNAL]
        )
        abstract val internalOption: String?

        @get:Option(
            name = "accumulating",
            allowMultiple = true,
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null"
        )
        abstract val accumulating: MutableList<String?>?
    }

    /** Test fragment.  */
    @RequiresOptions(options = [DummyTestOptions::class])
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
        TestRuleClassProvider.addStandardRules(builder)
        builder.addConfigurationFragment(DummyTestOptionsFragment::class.java)
        return builder.build()
    }

    @Before
    @Throws(Exception::class)
    fun writePlatforms() {
        scratch.file(
            "platforms/BUILD",
            """
        platform(name = "sample")
        
        """.trimIndent()
        )
    }

    @Throws(Exception::class)
    private fun createStarlarkFlagRule() {
        scratch.file(
            "flag/def.bzl",
            """
        def _impl(ctx):
            return []

        basic_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True),
            attrs = {
              "scope": attr.string(
                  doc = "The scope",
                  default = "universal",
                  values = ["universal", "project"],
              ),
            },
        )
        
        """.trimIndent()
        )
    }

    @Throws(Exception::class)
    private fun createStarlarkFlag() {
        createStarlarkFlagRule()
        scratch.file(
            "flag/BUILD",
            """
        load(":def.bzl", "basic_flag")

        basic_flag(
            name = "flag",
            build_setting_default = "from_default",
        )
        
        """.trimIndent()
        )
    }

    @Test
    @Throws(Exception::class)
    fun createKey() {
        val baseOptions: BuildOptions? =
            createBuildOptions("--platforms=//platforms:sample", "--internal_option=from_cmd")
        val result: BuildConfigurationKey = fetch(baseOptions, null)

        assertThat(result).isNotNull()
        assertThat(result.getOptions().get(DummyTestOptions::class.java).getInternalOption())
            .isEqualTo("from_cmd")
    }

    @Test
    @Throws(Exception::class)
    fun createKey_emptyConfig() {
        val baseOptions: BuildOptions? = CommonOptions.EMPTY_OPTIONS
        val result: BuildConfigurationKey = fetch(baseOptions, null)

        assertThat(result).isNotNull()
        assertThat(result.getOptionsChecksum()).isEqualTo(CommonOptions.EMPTY_OPTIONS.checksum())
    }

    @Test
    @Throws(Exception::class)
    fun createKey_platformMapping() {
        scratch.file(
            "/workspace/platform_mappings",
            """
        platforms:
          //platforms:sample
            --internal_option=from_mapping_changed
        
        """.trimIndent()
        )
        invalidatePackages(false)

        val baseOptions: BuildOptions? =
            createBuildOptions("--platforms=//platforms:sample", "--internal_option=from_cmd")
        val result: BuildConfigurationKey = fetch(baseOptions, null)

        assertThat(result).isNotNull()
        assertThat(result.getOptions().get(DummyTestOptions::class.java).getInternalOption())
            .isEqualTo("from_mapping_changed")
    }

    @Test
    @Throws(Exception::class)
    fun createKey_platformMapping_invalidFile() {
        scratch.file(
            "/workspace/platform_mappings",
            """
        not a mapping file
        
        """.trimIndent()
        )
        invalidatePackages(false)

        val baseOptions: BuildOptions? = createBuildOptions("--platforms=//platforms:sample")
        // Fails because the mapping file is poorly formed and cannot be parsed.
        Assert.assertThrows<T?>(PlatformMappingException::class.java, ThrowingRunnable { fetch(baseOptions, null) })
    }

    @Test
    @Throws(Exception::class)
    fun createKey_platformMapping_invalidOption() {
        scratch.file(
            "/workspace/platform_mappings",
            """
        platforms:
          //platforms:sample
            --fake_option
        
        """.trimIndent()
        )
        invalidatePackages(false)

        val baseOptions: BuildOptions? = createBuildOptions("--platforms=//platforms:sample")
        // Fails because the changed platform has an invalid mapping.
        val e: T? =
            Assert.assertThrows<T?>(PlatformMappingException::class.java, ThrowingRunnable { fetch(baseOptions, null) })
        assertThat(e).hasMessageThat().contains("Unrecognized option: --fake_option")
    }

    @Test
    @Throws(Exception::class)
    fun createKey_platformFlags_native() {
        scratch.overwriteFile(
            "platforms/BUILD",
            """
        platform(
            name = "sample",
            flags = [
                "--internal_option=from_platform",
            ],
        )
        
        """.trimIndent()
        )
        invalidatePackages(false)

        val baseOptions: BuildOptions? = createBuildOptions("--platforms=//platforms:sample")
        val result: BuildConfigurationKey = fetch(baseOptions, null)

        assertThat(result).isNotNull()
        assertThat(result.getOptions().get(DummyTestOptions::class.java).getInternalOption())
            .isEqualTo("from_platform")
    }

    @Test
    @Throws(Exception::class)
    fun createKey_platformFlags_starlark() {
        createStarlarkFlag()
        scratch.overwriteFile(
            "platforms/BUILD",
            """
        platform(
            name = "sample",
            flags = [
                "--//flag=from_platform",
            ],
        )
        
        """.trimIndent()
        )
        invalidatePackages(false)

        val baseOptions: BuildOptions? = createBuildOptions("--platforms=//platforms:sample")
        val result: BuildConfigurationKey = fetch(baseOptions, null)

        assertThat(result).isNotNull()
        assertThat(result.getOptions().getStarlarkOptions())
            .containsAtLeast(Label.parseCanonicalUnchecked("//flag"), "from_platform")
    }

    @Test
    @Throws(Exception::class)
    fun createKey_platformFlags_override_native() {
        scratch.overwriteFile(
            "platforms/BUILD",
            """
        platform(
            name = "sample",
            flags = [
                "--option=from_platform",
            ],
        )
        
        """.trimIndent()
        )
        invalidatePackages(false)

        val baseOptions: BuildOptions? =
            createBuildOptions("--platforms=//platforms:sample", "--option=from_cli")
        val result: BuildConfigurationKey = fetch(baseOptions, null)

        assertThat(result).isNotNull()
        assertThat(result.getOptions().get(DummyTestOptions::class.java).getOption())
            .isEqualTo("from_platform")
    }

    @Test
    @Throws(Exception::class)
    fun createKey_platformFlags_override_starlark() {
        createStarlarkFlag()
        scratch.overwriteFile(
            "platforms/BUILD",
            """
        platform(
            name = "sample",
            flags = [
                "--//flag=from_platform",
            ],
        )
        
        """.trimIndent()
        )
        invalidatePackages(false)

        val baseOptions: BuildOptions? =
            createBuildOptions("--platforms=//platforms:sample", "--//flag=from_cli")
        val result: BuildConfigurationKey = fetch(baseOptions, null)

        assertThat(result).isNotNull()
        assertThat(result.getOptions().getStarlarkOptions())
            .containsAtLeast(Label.parseCanonicalUnchecked("//flag"), "from_platform")
    }

    @Test
    @Throws(Exception::class)
    fun createKey_platformFlags_resetToDefault_native() {
        scratch.overwriteFile(
            "platforms/BUILD",
            """
        platform(
            name = "sample",
            flags = [
                "--option=from_default",
            ],
        )
        
        """.trimIndent()
        )
        invalidatePackages(false)

        val baseOptions: BuildOptions? =
            createBuildOptions("--platforms=//platforms:sample", "--option=from_cli")
        val result: BuildConfigurationKey = fetch(baseOptions, null)

        assertThat(result).isNotNull()
        assertThat(result.getOptions().get(DummyTestOptions::class.java).getOption())
            .isEqualTo("from_default")
    }

    // Regression test for https://github.com/bazelbuild/bazel/issues/23147
    @Test
    @Throws(Exception::class)
    fun createKey_platformFlags_resetToDefault_starlark() {
        createStarlarkFlag()
        scratch.overwriteFile(
            "platforms/BUILD",
            """
        platform(
            name = "sample",
            flags = [
                "--//flag=from_default",
            ],
        )
        
        """.trimIndent()
        )
        invalidatePackages(false)

        val baseOptions: BuildOptions? =
            createBuildOptions("--platforms=//platforms:sample", "--//flag=from_cli")
        val result: BuildConfigurationKey = fetch(baseOptions, null)

        assertThat(result).isNotNull()
        // Default key values should not be present in starlark options.
        assertThat(result.getOptions().getStarlarkOptions())
            .doesNotContainKey(Label.parseCanonicalUnchecked("//flag"))
    }

    @Test // Re-enable this once merging repeatable flags works properly. Also add a corresponding Starlark
    // flag to test.
    @Ignore("https://github.com/bazelbuild/bazel/issues/22453")
    @Throws(Exception::class)
    fun createKey_platformFlags_accumulate() {
        scratch.overwriteFile(
            "platforms/BUILD",
            """
        platform(
            name = "sample",
            flags = [
                "--accumulating=from_platform",
            ],
        )
        
        """.trimIndent()
        )
        invalidatePackages(false)

        val baseOptions: BuildOptions? =
            createBuildOptions("--platforms=//platforms:sample", "--accumulating=from_cli")
        val result: BuildConfigurationKey = fetch(baseOptions, null)

        assertThat(result).isNotNull()
        assertThat(result.getOptions().get(DummyTestOptions::class.java).getAccumulating())
            .containsExactly("from_cli", "from_platform")
            .inOrder()
    }

    @Test
    @Throws(Exception::class)
    fun createKey_platformFlags_invalidPlatform() {
        scratch.overwriteFile(
            "platforms/BUILD",
            """
        filegroup(name = "sample")
        
        """.trimIndent()
        )
        invalidatePackages(false)

        val baseOptions: BuildOptions? = createBuildOptions("--platforms=//platforms:sample")
        Assert.assertThrows<T?>(InvalidPlatformException::class.java, ThrowingRunnable { fetch(baseOptions, null) })
    }

    @Test
    @Throws(Exception::class)
    fun createKey_platformFlags_invalidOption() {
        scratch.overwriteFile(
            "platforms/BUILD",
            """
        platform(
            name = "sample",
            flags = [
                "--fake_option_doesnt_exist=from_platform",
            ],
        )
        
        """.trimIndent()
        )
        invalidatePackages(false)

        val baseOptions: BuildOptions? = createBuildOptions("--platforms=//platforms:sample")
        Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            ThrowingRunnable { fetch(baseOptions, null) })
    }

    @Test
    @Throws(Exception::class)
    fun createKey_platformFlags_overridesMapping() {
        scratch.file(
            "/workspace/platform_mappings",
            """
        platforms:
          //platforms:sample
            --internal_option=from_mapping
        
        """.trimIndent()
        )
        scratch.overwriteFile(
            "platforms/BUILD",
            """
        platform(
            name = "sample",
            flags = [
                "--internal_option=from_platform",
            ],
        )
        
        """.trimIndent()
        )
        invalidatePackages(false)

        val baseOptions: BuildOptions? = createBuildOptions("--platforms=//platforms:sample")
        val result: BuildConfigurationKey = fetch(baseOptions, null)

        assertThat(result).isNotNull()
        assertThat(result.getOptions().get(DummyTestOptions::class.java).getInternalOption())
            .isEqualTo("from_platform")
    }

    @Test
    @Throws(Exception::class)
    fun createKey_withScopedBuildOptions_outOfScopeFlag_flagNotSetInTheBaseline() {
        createStarlarkFlagRule()
        scratch.file(
            "flag/BUILD",
            """
        load(":def.bzl", "basic_flag")
        basic_flag(
            name = "foo",
            scope = "project",
            build_setting_default = "default",
        )
        basic_flag(
            name = "bar",
            scope = "universal",
            build_setting_default = "default",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "flag/PROJECT.scl",
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create(
            project_directories = ["//my_project"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "out_of_scope_flag/BUILD",
            """
        load("//flag:def.bzl", "basic_flag")
        basic_flag(
            name = "baz",
            scope = "project",
            build_setting_default = "default",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "out_of_scope_flag/PROJECT.scl",
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create(
            project_directories = ["//out_side_of_my_project"],
        )
        
        """.trimIndent()
        )

        invalidatePackages(false)

        val baseOptions: BuildOptions? =
            createBuildOptions("--//flag:foo=foo", "--//flag:bar=bar", "--//out_of_scope_flag:baz=baz")
        val result: BuildConfigurationKey =
            fetch(baseOptions, Label.parseCanonicalUnchecked("//my_project:my_target"))
        assertThat(result).isNotNull()
        assertThat(
            result
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:foo"))
        )
            .isEqualTo("foo")
        assertThat(
            result
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:bar"))
        )
            .isEqualTo("bar")
        assertThat(
            result
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//out_of_scope_flag:baz"))
        )
            .isNull()

        // Since the effective BuildOptions does not have //out_of_scope_flag:baz, its scope type should
        // not exist in the scope type map.
        val expectedScopeTypeMap: ImmutableMap<Label?, Scope.ScopeType?> =
            ImmutableMap.of<K?, V?>(
                Label.parseCanonicalUnchecked("//flag:foo"),
                ScopeType(Scope.ScopeType.PROJECT),
                Label.parseCanonicalUnchecked("//flag:bar"),
                ScopeType(Scope.ScopeType.UNIVERSAL)
            )
        assertThat(result.getOptions().getScopeTypeMap())
            .containsExactlyEntriesIn(expectedScopeTypeMap)
    }

    @Test
    @Throws(Exception::class)
    fun createKey_withScopedBuildOptions_outOfScopeFlag_flagSetInTheBaseline() {
        val analysisMock: AnalysisMock = AnalysisMock.Companion.get()

        val ruleClassProvider: ConfiguredRuleClassProvider = analysisMock.createRuleClassProvider()
        val buildOptionClasses: ImmutableSortedSet<Class<out FragmentOptions?>?>? =
            ruleClassProvider.getFragmentRegistry().getOptionsClasses()

        val skyframeExecutor: SequencedSkyframeExecutor = getSkyframeExecutor()
        val defaultBuildOptionsBuilder: BuildOptions.Builder =
            BuildOptions.getDefaultBuildOptionsForFragments(buildOptionClasses).clone().toBuilder()

        // set the out of scope flag in the baseline
        val starlarkOptions: MutableMap<Label?, Any?> = HashMap<Label?, Any?>()
        starlarkOptions.put(Label.parseCanonicalUnchecked("//out_of_scope_flag:baz"), "baselineValue")
        defaultBuildOptionsBuilder.addStarlarkOptions(starlarkOptions)

        skyframeExecutor.injectExtraPrecomputedValues(
            ImmutableList.Builder<PrecomputedValue.Injected?>()
                .add(
                    PrecomputedValue.injected(
                        BaselineOptionsFunction.BASELINE_CONFIGURATION,
                        defaultBuildOptionsBuilder.build()
                    )
                )
                .addAll(analysisMock.getPrecomputedValues())
                .build()
        )

        createStarlarkFlagRule()
        scratch.file(
            "flag/BUILD",
            """
        load(":def.bzl", "basic_flag")
        basic_flag(
            name = "foo",
            scope = "project",
            build_setting_default = "default",
        )
        basic_flag(
            name = "bar",
            scope = "universal",
            build_setting_default = "default",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "flag/PROJECT.scl",
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create(
            project_directories = ["//my_project"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "out_of_scope_flag/BUILD",
            """
        load("//flag:def.bzl", "basic_flag")
        basic_flag(
            name = "baz",
            scope = "project",
            build_setting_default = "default",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "out_of_scope_flag/PROJECT.scl",
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create(
            project_directories = ["//out_side_of_my_project"],
        )
        
        """.trimIndent()
        )

        invalidatePackages(false)

        val baseOptions: BuildOptions? =
            createBuildOptions("--//flag:foo=foo", "--//flag:bar=bar", "--//out_of_scope_flag:baz=baz")
        val result: BuildConfigurationKey =
            fetch(baseOptions, Label.parseCanonicalUnchecked("//my_project:my_target"))
        assertThat(result).isNotNull()
        assertThat(
            result
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:foo"))
        )
            .isEqualTo("foo")
        assertThat(
            result
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:bar"))
        )
            .isEqualTo("bar")
        assertThat(
            result
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//out_of_scope_flag:baz"))
        )
            .isEqualTo("baselineValue")

        // Since the effective BuildOptions has //out_of_scope_flag:baz, its scope type should
        // exist in the scope type map.
        val expectedScopeTypeMap: ImmutableMap<Label?, Scope.ScopeType?> =
            ImmutableMap.of<K?, V?>(
                Label.parseCanonicalUnchecked("//flag:foo"),
                ScopeType(Scope.ScopeType.PROJECT),
                Label.parseCanonicalUnchecked("//flag:bar"),
                ScopeType(Scope.ScopeType.UNIVERSAL),
                Label.parseCanonicalUnchecked("//out_of_scope_flag:baz"),
                ScopeType(Scope.ScopeType.PROJECT)
            )
        assertThat(result.getOptions().getScopeTypeMap())
            .containsExactlyEntriesIn(expectedScopeTypeMap)
    }

    @Test
    @Throws(Exception::class)
    fun checkFinalizeBuildOptions_haveCorrectScopeTypeMap_noScopingApplied() {
        createStarlarkFlagRule()
        scratch.file(
            "flag/BUILD",
            """
        load(":def.bzl", "basic_flag")
        basic_flag(
            name = "foo",
            scope = "universal",
            build_setting_default = "default",
        )
        basic_flag(
            name = "bar",
            scope = "universal",
            build_setting_default = "default",
        )
        
        """.trimIndent()
        )
        invalidatePackages(false)

        val baseOptions: BuildOptions? = createBuildOptions("--//flag:foo=foo", "--//flag:bar=bar")
        val result: BuildConfigurationKey =
            fetch(baseOptions, Label.parseCanonicalUnchecked("//my_project:my_target"))

        // All flags should be universal
        val expectedScopeTypeMap: ImmutableMap<Label?, Scope.ScopeType?> =
            ImmutableMap.of<K?, V?>(
                Label.parseCanonicalUnchecked("//flag:foo"),
                ScopeType(Scope.ScopeType.UNIVERSAL),
                Label.parseCanonicalUnchecked("//flag:bar"),
                ScopeType(Scope.ScopeType.UNIVERSAL)
            )
        assertThat(result.getOptions().getScopeTypeMap())
            .containsExactlyEntriesIn(expectedScopeTypeMap)
    }

    @Test
    @Throws(Exception::class)
    fun errorThrown_disallowedScopeType() {
        createStarlarkFlagRule()
        scratch.file(
            "flag/BUILD",
            """
        load(":def.bzl", "basic_flag")
        basic_flag(
            name = "foo",
            scope = "Project",
            build_setting_default = "default",
        )
        
        """.trimIndent()
        )
        invalidatePackages(false)

        val e = Assert.assertThrows<AssertionError?>(
            AssertionError::class.java,
            ThrowingRunnable { createBuildOptions("--//flag:foo=foo") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "//flag:foo: invalid value in 'scope' attribute: has to be one of 'universal' or"
                        + " 'project' instead of 'Project"
            )
    }

    @Throws(
        InterruptedException::class,
        OptionsParsingException::class,
        PlatformMappingException::class,
        InvalidPlatformException::class,
        BuildOptionsScopeFunctionException::class
    )
    private fun fetch(options: BuildOptions?, label: Label?): BuildConfigurationKey {
        val sink = Sink()
        val producer: BuildConfigurationKeyProducer<String?> =
            BuildConfigurationKeyProducer(sink, StateMachine.DONE, CONTEXT, options, label)
        // Ignore the return value: sink will either return a result or re-throw whatever exception it
        // received from the producer.
        val unused = executeProducer(producer)
        return sink.options(CONTEXT)
    }

    /** Receiver for platform info from [PlatformProducer].  */
    private class Sink : BuildConfigurationKeyProducer.ResultSink<String?> {
        private var optionsParsingException: OptionsParsingException? = null
        private var platformMappingException: PlatformMappingException? = null
        private var invalidPlatformException: InvalidPlatformException? = null
        private var buildOptionsScopeFunctionException: BuildOptionsScopeFunctionException? = null
        private var context: String? = null
        private var key: BuildConfigurationKey? = null

        public override fun acceptOptionsParsingError(e: OptionsParsingException?) {
            this.optionsParsingException = e
        }

        public override fun acceptPlatformMappingError(e: PlatformMappingException?) {
            this.platformMappingException = e
        }

        public override fun acceptPlatformFlagsError(e: InvalidPlatformException?) {
            this.invalidPlatformException = e
        }

        public override fun acceptTransitionedConfiguration(context: String?, key: BuildConfigurationKey?) {
            this.context = context
            this.key = key
        }

        public override fun acceptBuildOptionsScopeFunctionError(e: BuildOptionsScopeFunctionException?) {
            this.buildOptionsScopeFunctionException = e
        }

        @Throws(
            OptionsParsingException::class,
            PlatformMappingException::class,
            InvalidPlatformException::class,
            BuildOptionsScopeFunctionException::class
        )
        fun options(expectedContext: String?): BuildConfigurationKey {
            if (this.optionsParsingException != null) {
                throw this.optionsParsingException
            }
            if (this.platformMappingException != null) {
                throw this.platformMappingException
            }
            if (this.invalidPlatformException != null) {
                throw this.invalidPlatformException
            }
            if (this.buildOptionsScopeFunctionException != null) {
                throw this.buildOptionsScopeFunctionException
            }
            if (this.key != null) {
                Truth.assertThat(this.context).isEqualTo(expectedContext)
                return this.key
            }
            throw IllegalStateException("Value and exception not set")
        }
    }

    companion object {
        private const val CONTEXT = "context"
    }
}
