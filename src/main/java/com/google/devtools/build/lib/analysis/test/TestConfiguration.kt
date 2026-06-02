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
// limitations under the License.
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.analysis.OptionsDiffPredicate

/** Test-related options.  */
@RequiresOptions(options = [com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions::class])
class TestConfiguration(buildOptions: BuildOptions) : Fragment() {
    /** Command-line options.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class TestOptions : FragmentOptions() {
        @get:com.google.devtools.common.options.Option(
            name = "test_env",
            converter = com.google.devtools.build.lib.util.EnvVar.Converter::class,
            allowMultiple = true,
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TESTING,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.TEST_RUNNER],
            help = """
            Specifies additional environment variables to be injected into the test runner
            environment. Variables can be either specified by `name`, in which
            case its value will be read from the Bazel client environment, or by the
            `name=value` pair.
            Previously set variables can be unset via `=name`.
            This option can be used multiple times to specify several variables.
            Used only by the 'bazel test' command.
            
            """.trimIndent()
        )
        abstract val testEnvironment: MutableList<com.google.devtools.build.lib.util.EnvVar>?

        abstract fun setTestEnvironment(value: MutableList<com.google.devtools.build.lib.util.EnvVar?>?)

        @com.google.devtools.common.options.Option(
            name = "test_timeout",
            defaultValue = "-1",
            converter = TestTimeoutConverter::class,
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TESTING,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = """
            Override the default test timeout values for test timeouts (in secs). If a single
            positive integer value is specified it will override all categories.  If 4
            comma-separated integers are specified, they will override the timeouts for
            `short`, `moderate`, `long` and `eternal` (in that order). In either form, a value of
            -1 tells blaze to use its default timeouts for that category.
            
            """.trimIndent()
        )
        abstract fun getTestTimeout(): MutableMap<TestTimeout?, java.time.Duration?>?

        @com.google.devtools.common.options.Option(
            name = "default_test_resources",
            defaultValue = "null",
            converter = TestResourcesConverter::class,
            allowMultiple = true,
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TESTING,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = """
            Override the default resources amount for tests. The expected format is
            `{resource}={value}`. If a single positive number is specified as `{value}`
            it will override the default resources for all test sizes. If 4
            comma-separated numbers are specified, they will override the resource
            amount for respectively the `small`, `medium`, `large`, `enormous` test sizes.
            Values can also be `HOST_RAM`/`HOST_CPU`, optionally followed
            by `[-|*]{float}` (eg. `memory=HOST_RAM*.1,HOST_RAM*.2,HOST_RAM*.3,HOST_RAM*.4`).
            The default test resources specified by this flag are overridden by explicit
            resources specified in tags.
            
            """.trimIndent()
        ) // We need to store these as Pair(s) instead of Map.Entry(s) so that they are serializable.
        abstract fun getTestResources(): MutableList<com.google.devtools.build.lib.util.Pair<String?, MutableMap<TestSize?, Double?>?>>?

        @kotlin.jvm.JvmField
        @get:com.google.devtools.common.options.Option(
            name = "test_filter",
            allowMultiple = false,
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = ("Specifies a filter to forward to the test framework.  Used to limit "
                    + "the tests run. Note that this does not affect which targets are built.")
        )
        abstract val testFilter: String?

        @get:com.google.devtools.common.options.Option(
            name = "test_runner_fail_fast",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = ("Forwards fail fast option to the test runner. The test runner should stop execution"
                    + " upon first failure.")
        )
        abstract val testRunnerFailFast: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "cache_test_results",
            defaultValue = "auto",
            abbrev = 't',
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = """
            If set to `auto`, Bazel reruns a test if and only if:
            1. Bazel detects changes in the test or its dependencies,
            2. The test is marked as `external`,
            3. Multiple test runs were requested with `--runs_per_test`, or
            4. The test previously failed.
            If set to `yes`, Bazel caches all test results except for tests marked as
            `external`. If set to `no`, Bazel does not cache any test results.
            
            """.trimIndent()
        )
        abstract val cacheTestResults: com.google.devtools.common.options.TriState?

        @get:com.google.devtools.common.options.Option(
            name = "test_result_expiration",
            defaultValue = "-1",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
            help = "This option is deprecated and has no effect."
        )
        @get:Deprecated("")
        abstract val testResultExpiration: Int

        @get:com.google.devtools.common.options.Option(
            name = "trim_test_configuration",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE
            ],
            help = ("When enabled, test-related options will be cleared below the top level of the build."
                    + " When this flag is active, tests cannot be built as dependencies of non-test"
                    + " rules, but changes to test-related options will not cause non-test rules to be"
                    + " re-analyzed.")
        )
        abstract val trimTestConfiguration: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_retain_test_configuration_across_testonly",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE
            ],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
            help = """
            When enabled, `--trim_test_configuration` will not trim the test configuration for rules
            marked testonly=1. This is meant to reduce action conflict issues when non-test
            rules depend on `cc_test` rules. No effect if `--trim_test_configuration` is
            false.
            
            """.trimIndent()
        )
        abstract val experimentalRetainTestConfigurationAcrossTestonly: Boolean

        @kotlin.jvm.JvmField
        @get:com.google.devtools.common.options.Option(
            name = "test_arg",
            allowMultiple = true,
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = """
            Specifies additional options and arguments that should be passed to the test
            executable. Can be used multiple times to specify several arguments.
            If multiple tests are executed, each of them will receive identical arguments.
            Used only by the `bazel test` command.
            
            """.trimIndent()
        )
        abstract val testArguments: MutableList<String?>?

        @get:com.google.devtools.common.options.Option(
            name = "test_sharding_strategy",
            defaultValue = "explicit",
            converter = ShardingStrategyConverter::class,
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = """
            Specify strategy for test sharding:
            - `explicit` to only use sharding if the `shard_count` `BUILD` attribute is
              present.
            - `disabled` to never use test sharding.
            - `forced=k` to enforce `k` shards for testing regardless of the `shard_count` `BUILD`
              attribute.
            
            """.trimIndent()
        )
        abstract val testShardingStrategy: TestShardingStrategy?

        @get:com.google.devtools.common.options.Option(
            name = "runs_per_test",
            allowMultiple = true,
            defaultValue = "1",
            converter = RunsPerTestConverter::class,
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TESTING,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = """
            Specifies number of times to run each test. If any of those attempts fail for any
            reason, the whole test is considered failed. Normally the value specified is
            just an integer.

            Example: `--runs_per_test=3` will run all tests 3 times.

            Alternate syntax: `regex_filter@runs_per_test`. Where `runs_per_test` stands for
            an integer value and `regex_filter` stands for a list of include and exclude
            regular expression patterns (Also see --instrumentation_filter).

            Example: `--runs_per_test=//foo/.*,-//foo/bar/.*@3` runs all tests in `//foo/` except
            those under `//foo/bar` three times. This option can be passed multiple times. The most
            recently passed argument that matches takes precedence. If nothing matches,
            the test is only run once.
            
            """.trimIndent()
        )
        abstract val runsPerTest: MutableList<PerLabelOptions>?

        @get:com.google.devtools.common.options.Option(
            name = "runs_per_test_detects_flakes",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = ("If true, any shard in which at least one run/attempt passes and at least one "
                    + "run/attempt fails gets a FLAKY status.")
        )
        abstract val runsPerTestDetectsFlakes: Boolean

        /** When to cancel concurrently running tests.  */
        enum class CancelConcurrentTests {
            NEVER,
            ON_FAILED,
            ON_PASSED;

            /** Converts to [CancelConcurrentTests].  */
            internal class Converter : com.google.devtools.common.options.BoolOrEnumConverter<CancelConcurrentTests?>(
                CancelConcurrentTests::class.java,
                "when to cancel concurrent tests",
                CancelConcurrentTests.ON_PASSED,
                CancelConcurrentTests.NEVER
            )
        }

        @get:com.google.devtools.common.options.Option(
            name = "experimental_cancel_concurrent_tests",
            defaultValue = "never",
            converter = com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions.CancelConcurrentTests.Converter::class,
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
            help = """
            If `on_failed` or `on_passed`, then Blaze will cancel concurrently running tests on the first
            successful run with that result. This is only useful in combination with
            `--runs_per_test_detects_flakes`.
            
            """.trimIndent()
        )
        abstract val cancelConcurrentTests: CancelConcurrentTests?

        @get:com.google.devtools.common.options.Option(
            name = "coverage_support",
            converter = LabelConverter::class,
            defaultValue = "@bazel_tools//tools/test:coverage_support",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS
            ],
            help = """
            Location of support files that are required on the inputs of every test action
            that collects code coverage. Defaults to `//tools/test:coverage_support`.
            
            """.trimIndent()
        )
        abstract val coverageSupport: com.google.devtools.build.lib.cmdline.Label?

        @get:com.google.devtools.common.options.Option(
            name = "experimental_fetch_all_coverage_outputs",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
            help = ("If true, then Bazel fetches the entire coverage data directory for each test during a "
                    + "coverage run.")
        )
        abstract val fetchAllCoverageOutputs: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "incompatible_exclusive_test_sandboxed",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
            help = """
            If true, exclusive tests will run with sandboxed strategy. Add `local` tag to force
            an exclusive test run locally
            
            """.trimIndent()
        )
        abstract val incompatibleExclusiveTestSandboxed: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_split_coverage_postprocessing",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
            help = "If true, then Bazel will run coverage postprocessing for test in a new spawn."
        )
        abstract val splitCoveragePostProcessing: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "zip_undeclared_test_outputs",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TESTING,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.TEST_RUNNER],
            help = "If true, undeclared test outputs will be archived in a zip file."
        )
        abstract val zipUndeclaredTestOutputs: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "allow_local_tests",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
            help = "If true, Bazel will allow local tests to run."
        )
        abstract val allowLocalTests: Boolean

        val normalized: TestOptions
            get() {
                val result =
                    clone() as TestOptions
                result.setTestEnvironment(normalizeEnvVars(this.testEnvironment))
                return result
            }

        companion object {
            private val ALWAYS_INVALIDATE_WHEN_CHANGED: com.google.common.collect.ImmutableSet<com.google.devtools.common.options.OptionDefinition?> =
                com.google.common.collect.ImmutableSet.of<com.google.devtools.common.options.OptionDefinition?>(
                    com.google.devtools.common.options.OptionsParser.getOptionDefinitionByName(
                        com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions::class.java,
                        "trim_test_configuration"
                    ),
                    com.google.devtools.common.options.OptionsParser.getOptionDefinitionByName(
                        com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions::class.java,
                        "experimental_retain_test_configuration_across_testonly"
                    )
                )
        }
    }

    private val options: TestOptions?
    private val testTimeout: com.google.common.collect.ImmutableMap<TestTimeout?, java.time.Duration?>?
    private val shouldInclude: Boolean
    private val testResources: com.google.common.collect.ImmutableMap<TestSize?, com.google.common.collect.ImmutableMap<String?, Double?>?>

    init {
        this.options =
            buildOptions.get(com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions::class.java)
        if (options != null) {
            this.shouldInclude = true
            this.testTimeout =
                com.google.common.collect.ImmutableMap.copyOf<TestTimeout?, java.time.Duration?>(options.getTestTimeout())
            val testResources: com.google.common.collect.ImmutableMap.Builder<TestSize?, com.google.common.collect.ImmutableMap<String?, Double?>?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<TestSize?, com.google.common.collect.ImmutableMap<String?, Double?>?>(
                    TestSize.entries.size
                )
            for (size in TestSize.entries) {
                val resources: com.google.common.collect.ImmutableMap.Builder<String?, Double?> =
                    com.google.common.collect.ImmutableMap.builder<String?, Double?>()
                for (resource in options.getTestResources()!!) {
                    resources.put(resource.getFirst(), resource.getSecond().get(size))
                }
                testResources.put(size, resources.buildKeepingLast())
            }
            this.testResources = testResources.buildOrThrow()
        } else {
            this.shouldInclude = false
            this.testTimeout = null
            this.testResources =
                com.google.common.collect.ImmutableMap.of<TestSize?, com.google.common.collect.ImmutableMap<String?, Double?>?>()
        }
    }

    public override fun shouldInclude(): Boolean {
        return shouldInclude
    }

    /** Returns test timeout mapping as set by --test_timeout options.  */
    fun getTestTimeout(): com.google.common.collect.ImmutableMap<TestTimeout?, java.time.Duration?>? {
        return testTimeout
    }

    /** Returns test resource mapping as set by --default_test_resources options.  */
    fun getTestResources(size: TestSize?): com.google.common.collect.ImmutableMap<String?, Double?>? {
        return testResources.getOrDefault(size, com.google.common.collect.ImmutableMap.of<String?, Double?>())
    }

    val testFilter: String?
        get() = options!!.testFilter

    val testRunnerFailFast: Boolean
        get() = options!!.testRunnerFailFast

    fun cacheTestResults(): com.google.devtools.common.options.TriState? {
        return options!!.cacheTestResults
    }

    val testArguments: MutableList<String?>?
        get() = options!!.testArguments

    fun testShardingStrategy(): TestShardingStrategy? {
        return options!!.testShardingStrategy
    }

    val coverageSupport: com.google.devtools.build.lib.cmdline.Label?
        get() = options!!.coverageSupport

    /**
     * @return number of times the given test should run. If the test doesn't match any of the
     * filters, runs it once.
     */
    fun getRunsPerTestForLabel(label: com.google.devtools.build.lib.cmdline.Label?): Int {
        for (perLabelRuns in com.google.common.collect.Lists.reverse<PerLabelOptions>(options!!.runsPerTest)) {
            if (perLabelRuns.isIncluded(label)) {
                return com.google.common.collect.Iterables.getOnlyElement<String?>(perLabelRuns.options).toInt()
            }
        }
        return 1
    }

    fun runsPerTestDetectsFlakes(): Boolean {
        return options!!.runsPerTestDetectsFlakes
    }

    fun cancelConcurrentTests(): CancelConcurrentTests? {
        return options!!.cancelConcurrentTests
    }

    fun fetchAllCoverageOutputs(): Boolean {
        return options!!.fetchAllCoverageOutputs
    }

    fun incompatibleExclusiveTestSandboxed(): Boolean {
        return options!!.incompatibleExclusiveTestSandboxed
    }

    fun splitCoveragePostProcessing(): Boolean {
        return options!!.splitCoveragePostProcessing
    }

    val zipUndeclaredTestOutputs: Boolean
        get() = options!!.zipUndeclaredTestOutputs

    fun allowLocalTests(): Boolean {
        return options!!.allowLocalTests
    }

    /**
     * Option converter that han handle two styles of value for "--runs_per_test":
     * 
     * 
     *  * --runs_per_test=NUMBER: Run each test NUMBER times.
     *  * --runs_per_test=test_regex@NUMBER: Run each test that matches test_regex NUMBER times.
     * This form can be repeated with multiple regexes.
     * 
     */
    class RunsPerTestConverter : PerLabelOptions.PerLabelOptionsConverter() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        public override fun convert(input: String): PerLabelOptions {
            try {
                return parseAsInteger(input)
            } catch (ignored: java.lang.NumberFormatException) {
                return parseAsRegex(input)
            }
        }

        @Throws(
            java.lang.NumberFormatException::class,
            com.google.devtools.common.options.OptionsParsingException::class
        )
        private fun parseAsInteger(input: String): PerLabelOptions {
            val numericValue: Int = input.toInt()
            if (numericValue <= 0) {
                throw com.google.devtools.common.options.OptionsParsingException("'" + input + "' should be >= 1")
            } else {
                val catchAll: com.google.devtools.build.lib.util.RegexFilter =
                    com.google.devtools.build.lib.util.RegexFilter(
                        mutableListOf<String?>(".*"),
                        mutableListOf<String?>()
                    )
                return PerLabelOptions(catchAll, mutableListOf<T?>(input))
            }
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        private fun parseAsRegex(input: String?): PerLabelOptions {
            val testRegexps: PerLabelOptions = super.convert(input)
            if (testRegexps.options.size() !== 1) {
                throw com.google.devtools.common.options.OptionsParsingException("'" + input + "' has multiple runs for a single pattern")
            }
            val runsPerTest: String? = com.google.common.collect.Iterables.getOnlyElement<String?>(testRegexps.options)
            try {
                val numericRunsPerTest: Int = runsPerTest.toInt()
                if (numericRunsPerTest <= 0) {
                    throw com.google.devtools.common.options.OptionsParsingException("'" + input + "' has a value < 1")
                }
            } catch (e: java.lang.NumberFormatException) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    "'" + input + "' has a non-numeric value",
                    e
                )
            }
            return testRegexps
        }

        val typeDescription: String
            get() = "a positive integer or test_regex@runs. This flag may be passed more than once"
    }

    companion object {
        @kotlin.jvm.JvmField
        val SHOULD_INVALIDATE_FOR_OPTION_DIFF: OptionsDiffPredicate =
            OptionsDiffPredicate { options, changedOption, oldValue, newValue ->
                if (com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions.Companion.ALWAYS_INVALIDATE_WHEN_CHANGED.contains(
                        changedOption
                    )
                ) {
                    // changes in --trim_test_configuration itself or related flags always prompt invalidation
                    return@OptionsDiffPredicate true
                }
                // LINT.IfChange
                val affectedOptionsClass: java.lang.Class<out FragmentOptions?> =
                    changedOption.getDeclaringClass(FragmentOptions::class.java)
                if (affectedOptionsClass != com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions::class.java && affectedOptionsClass != CoverageOptions::class.java) {
                    // options outside of TestOptions always prompt invalidation, except for --run_under.
                    if (affectedOptionsClass == CoreOptions::class.java
                        && changedOption.getOptionName().equals("run_under")
                    ) {
                        return@OptionsDiffPredicate RunUnder.trimForNonTestConfiguration(oldValue as RunUnder?) != RunUnder.trimForNonTestConfiguration(
                            newValue as RunUnder?
                        )
                    }
                    return@OptionsDiffPredicate true
                }
                !options.get(com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions::class.java)
                    .getTrimTestConfiguration()
            }
    }
}
