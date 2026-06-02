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

import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSortedMap
import com.google.common.collect.Ordering
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider
import com.google.devtools.common.options.Option
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable

/** Tests of [BuildConfigurationKeyMapProducer].  */
@RunWith(JUnit4::class)
class BuildConfigurationKeyMapProducerTest : ProducerTestCase() {
    /** Extra options for this test.  */
    @OptionsClass
    abstract class DummyTestOptions : FragmentOptions() {
        @get:Option(
            name = "option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "super secret"
        )
        abstract val option: String?

        @get:Option(
            name = "internal_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "super secret",
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

    @Test
    @Throws(Exception::class)
    fun createKey() {
        val baseOptions: BuildOptions =
            createBuildOptions("--platforms=//platforms:sample", "--internal_option=from_cmd")
        val result: BuildConfigurationKey = fetch(baseOptions)

        assertThat(result).isNotNull()
        assertThat(result.getOptions().get(DummyTestOptions::class.java).getInternalOption())
            .isEqualTo("from_cmd")
    }

    @Test
    @Throws(Exception::class)
    fun createKeys_preservesOrder() {
        val baseOptions1: BuildOptions =
            createBuildOptions("--platforms=//platforms:sample", "--internal_option=first")
        val baseOptions2: BuildOptions =
            createBuildOptions("--platforms=//platforms:sample", "--internal_option=second")
        val baseOptions3: BuildOptions =
            createBuildOptions("--platforms=//platforms:sample", "--internal_option=third")

        // Use a sorted map implementation to ensure consistent ordering of the input.
        val input: SortedMap<String?, BuildOptions?> =
            ImmutableSortedMap.Builder<String?, BuildOptions?>(Ordering.natural<String?>())
                .put("first", baseOptions1)
                .put("second", baseOptions2)
                .put("third", baseOptions3)
                .buildOrThrow()
        Truth.assertThat(input.keys).containsExactly("first", "second", "third").inOrder()
        val result: ImmutableMap<String?, BuildConfigurationKey> = fetch(input)

        Truth.assertThat(result).isNotNull()
        Truth.assertThat(result.keys).containsExactly("first", "second", "third").inOrder()
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

        val baseOptions: BuildOptions =
            createBuildOptions("--platforms=//platforms:sample", "--internal_option=from_cmd")
        val result: BuildConfigurationKey = fetch(baseOptions)

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

        val baseOptions: BuildOptions = createBuildOptions("--platforms=//platforms:sample")
        // Fails because the mapping file is poorly formed and cannot be parsed.
        Assert.assertThrows<T?>(PlatformMappingException::class.java, ThrowingRunnable { fetch(baseOptions) })
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

        val baseOptions: BuildOptions = createBuildOptions("--platforms=//platforms:sample")
        // Fails because the changed platform has an invalid mapping.
        val e: T? =
            Assert.assertThrows<T?>(PlatformMappingException::class.java, ThrowingRunnable { fetch(baseOptions) })
        assertThat(e).hasMessageThat().contains("Unrecognized option: --fake_option")
    }

    @Test
    @Throws(Exception::class)
    fun createKey_platformFlags() {
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

        val baseOptions: BuildOptions = createBuildOptions("--platforms=//platforms:sample")
        val result: BuildConfigurationKey = fetch(baseOptions)

        assertThat(result).isNotNull()
        assertThat(result.getOptions().get(DummyTestOptions::class.java).getInternalOption())
            .isEqualTo("from_platform")
    }

    @Test
    @Throws(Exception::class)
    fun createKey_platformFlags_override() {
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

        val baseOptions: BuildOptions =
            createBuildOptions("--platforms=//platforms:sample", "--option=from_cli")
        val result: BuildConfigurationKey = fetch(baseOptions)

        assertThat(result).isNotNull()
        assertThat(result.getOptions().get(DummyTestOptions::class.java).getOption())
            .isEqualTo("from_platform")
    }

    @Test // Re-enable this once merging repeatable flags works properly.
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

        val baseOptions: BuildOptions =
            createBuildOptions("--platforms=//platforms:sample", "--accumulating=from_cli")
        val result: BuildConfigurationKey = fetch(baseOptions)

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

        val baseOptions: BuildOptions = createBuildOptions("--platforms=//platforms:sample")
        Assert.assertThrows<T?>(InvalidPlatformException::class.java, ThrowingRunnable { fetch(baseOptions) })
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

        useConfiguration("--platforms=//platforms:sample")
        val baseOptions: BuildOptions = createBuildOptions("--platforms=//platforms:sample")
        Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            ThrowingRunnable { fetch(baseOptions) })
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

        val baseOptions: BuildOptions = createBuildOptions("--platforms=//platforms:sample")
        val result: BuildConfigurationKey = fetch(baseOptions)

        assertThat(result).isNotNull()
        assertThat(result.getOptions().get(DummyTestOptions::class.java).getInternalOption())
            .isEqualTo("from_platform")
    }

    @Throws(
        InterruptedException::class,
        OptionsParsingException::class,
        PlatformMappingException::class,
        InvalidPlatformException::class,
        BuildOptionsScopeFunctionException::class
    )
    private fun fetch(options: BuildOptions): BuildConfigurationKey {
        val result: ImmutableMap<String?, BuildConfigurationKey> =
            fetch(ImmutableMap.of<String?, BuildOptions?>("only", options))
        return result.get("only")
    }

    @Throws(
        InterruptedException::class,
        OptionsParsingException::class,
        PlatformMappingException::class,
        InvalidPlatformException::class,
        BuildOptionsScopeFunctionException::class
    )
    private fun fetch(options: MutableMap<String?, BuildOptions?>?): ImmutableMap<String?, BuildConfigurationKey> {
        val sink = Sink()
        val producer: BuildConfigurationKeyMapProducer =
            BuildConfigurationKeyMapProducer(sink, StateMachine.DONE, options, null)
        // Ignore the return value: sink will either return a result or re-throw whatever exception it
        // received from the producer.
        val unused = executeProducer(producer)
        return sink.options()
    }

    /** Receiver for platform info from [PlatformProducer].  */
    private class Sink : BuildConfigurationKeyMapProducer.ResultSink {
        private var optionsParsingException: OptionsParsingException? = null
        private var platformMappingException: PlatformMappingException? = null
        private var invalidPlatformException: InvalidPlatformException? = null
        private var buildOptionsScopeFunctionException: BuildOptionsScopeFunctionException? = null
        private var keys: ImmutableMap<String?, BuildConfigurationKey>? = null

        public override fun acceptOptionsParsingError(e: OptionsParsingException?) {
            this.optionsParsingException = e
        }

        public override fun acceptPlatformMappingError(e: PlatformMappingException?) {
            this.platformMappingException = e
        }

        public override fun acceptPlatformFlagsError(e: InvalidPlatformException?) {
            this.invalidPlatformException = e
        }

        public override fun acceptTransitionedConfigurations(keys: ImmutableMap<String?, BuildConfigurationKey>?) {
            this.keys = keys
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
        fun options(): ImmutableMap<String?, BuildConfigurationKey> {
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
            if (this.keys != null) {
                return this.keys
            }
            throw IllegalStateException("Value and exception not set")
        }
    }
}
