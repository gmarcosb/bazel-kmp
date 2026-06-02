// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionExecutionContext.ShowSubcommands

/**
 * Options affecting the execution phase of a build.
 * 
 * 
 * These options are interpreted by the BuildTool to choose an Executor to be used for the build.
 * 
 * 
 * Note: from the user's point of view, the characteristic function of this set of options is
 * indistinguishable from that of the BuildRequestOptions: they are all per-request. The difference
 * is only apparent in the implementation: these options are used only by the lib.exec machinery,
 * which affects how C++ and Java compilation occur. (The BuildRequestOptions contain a mixture of
 * "semantic" options affecting the choice of targets to build, and "non-semantic" options affecting
 * the lib.actions machinery.) Ideally, the user would be unaware of the difference. For now, the
 * usage strings are identical modulo "part 1", "part 2".
 */
@com.google.devtools.common.options.OptionsClass
abstract class ExecutionOptions : com.google.devtools.common.options.OptionsBase() {
    @get:com.google.devtools.common.options.Option(
        name = "spawn_strategy",
        defaultValue = "",
        converter = com.google.devtools.common.options.Converters.CommaSeparatedNonEmptyOptionListConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("Specify how spawn actions are executed by default. Accepts a comma-separated list of"
                + " strategies from highest to lowest priority. For each action Bazel picks the"
                + " strategy with the highest priority that can execute the action. The default"
                + " value is \"remote,worker,sandboxed,local\". See"
                + " https://blog.bazel.build/2019/06/19/list-strategy.html for details.")
    )
    abstract val spawnStrategy: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "genrule_strategy",
        defaultValue = "",
        converter = com.google.devtools.common.options.Converters.CommaSeparatedNonEmptyOptionListConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("Specify how to execute genrules. This flag will be phased out. Instead, use "
                + "--spawn_strategy=<value> to control all actions or --strategy=Genrule=<value> "
                + "to control genrules only.")
    )
    abstract val genruleStrategy: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "strategy",
        allowMultiple = true,
        converter = com.google.devtools.common.options.Converters.StringToStringListConverter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("Specify how to distribute compilation of other spawn actions. Accepts a comma-separated"
                + " list of strategies from highest to lowest priority. For each action Bazel picks"
                + " the strategy with the highest priority that can execute the action. The default"
                + " value is \"remote,worker,sandboxed,local\". This flag overrides the values set"
                + " by --spawn_strategy (and --genrule_strategy if used with mnemonic Genrule). See"
                + " https://blog.bazel.build/2019/06/19/list-strategy.html for details.")
    )
    abstract val strategy: MutableList<MutableMap.MutableEntry<String?, MutableList<String?>?>?>?

    @get:com.google.devtools.common.options.Option(
        name = "strategy_regexp",
        allowMultiple = true,
        converter = RegexFilterAssignmentConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        defaultValue = "null",
        help = ("Override which spawn strategy should be used to execute spawn actions that have "
                + "descriptions matching a certain regex_filter. See --per_file_copt for details on "
                + "regex_filter matching. "
                + "The last regex_filter that matches the description is used. "
                + "This option overrides other flags for specifying strategy. "
                + "Example: --strategy_regexp=//foo.*\\.cc,-//foo/bar=local means to run actions "
                + "using local strategy if their descriptions match //foo.*.cc but not //foo/bar. "
                + "Example: --strategy_regexp='Compiling.*/bar=local "
                + " --strategy_regexp=Compiling=sandboxed will run 'Compiling //foo/bar/baz' with "
                + "the 'local' strategy, but reversing the order would run it with 'sandboxed'. ")
    )
    abstract val strategyByRegexp: MutableList<MutableMap.MutableEntry<com.google.devtools.build.lib.util.RegexFilter, MutableList<String?>?>?>?

    @get:com.google.devtools.common.options.Option(
        name = "allowed_strategies_by_exec_platform",
        allowMultiple = true,
        converter = LabelToStringListConverter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = """
          Filters spawn strategies by the execution platform without affecting order.
          For example:
          ```
          common --spawn_strategy=remote,sandboxed,worker,local
          common --strategy=Genrule=local
          common --allowed_strategies_by_exec_platform=@platforms//host:host=local,sandboxed,worker
          common --allowed_strategies_by_exec_platform=//:linux_amd64=remote
          ```
          With the above options;
          - Actions configured for the host platform will be given `remote,sandboxed,worker`.
          - Actions configured for the `//:linux_amd64` platform will be given `remote`.
          - Actions configured for the `//:linux_amd64` platform with mnemonic `Genrule` will be
            given no strategies and fail to spawn.
          
          """.trimIndent()
    )
    abstract val allowedStrategiesByExecPlatform: MutableList<MutableMap.MutableEntry<Label, MutableList<String?>?>?>?

    @get:com.google.devtools.common.options.Option(
        name = "materialize_param_files",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("Writes intermediate parameter files to output tree even when using remote action "
                + "execution or caching. Useful when debugging actions. This is implied by "
                + "--subcommands and --verbose_failures.")
    )
    abstract val materializeParamFiles: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_materialize_param_files_directly",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = "If materializing param files, do so with direct writes to disk."
    )
    abstract val materializeParamFilesDirectly: Boolean

    fun shouldMaterializeParamFiles(): Boolean {
        // Implied by --subcommands and --verbose_failures
        return this.materializeParamFiles
                || this.showSubcommands !== ShowSubcommands.FALSE || this.verboseFailures
    }

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "verbose_failures",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = "If a command fails, print out the full command line."
    )
    abstract val verboseFailures: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "subcommands",
        abbrev = 's',
        defaultValue = "false",
        converter = ShowSubcommandsConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("Display the subcommands executed during a build. Related flags:"
                + " --execution_log_json_file, --execution_log_binary_file (for logging subcommands"
                + " to a file in a tool-friendly format).")
    )
    abstract val showSubcommands: ShowSubcommands?

    @get:com.google.devtools.common.options.Option(
        name = "check_up_to_date",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("Don't perform the build, just check if it is up-to-date.  If all targets are "
                + "up-to-date, the build completes successfully.  If any step needs to be executed "
                + "an error is reported and the build fails.")
    )
    abstract val checkUpToDate: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "check_tests_up_to_date",
        defaultValue = "false",
        implicitRequirements = ["--check_up_to_date"],
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TESTING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("Don't run tests, just check if they are up-to-date.  If all tests results are "
                + "up-to-date, the testing completes successfully.  If any test needs to be built or "
                + "executed, an error is reported and the testing fails.  This option implies "
                + "--check_up_to_date behavior.")
    )
    abstract val testCheckUpToDate: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "test_strategy",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TESTING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = "Specifies which strategy to use when running tests."
    )
    abstract val testStrategy: String?

    @get:com.google.devtools.common.options.Option(
        name = "test_keep_going",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TESTING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("When disabled, any non-passing test will cause the entire build to stop. By default "
                + "all tests are run, even if some do not pass.")
    )
    abstract val testKeepGoing: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "flaky_test_attempts",
        allowMultiple = true,
        defaultValue = "default",
        converter = TestAttemptsConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TESTING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("Each test will be retried up to the specified number of times in case of any test"
                + " failure. Tests that required more than one attempt to pass are marked as 'FLAKY'"
                + " in the test summary. Normally the value specified is just an integer or the"
                + " string 'default'. If an integer, then all tests will be run up to N times. If"
                + " 'default', then only a single test attempt will be made for regular tests and"
                + " three for tests marked explicitly as flaky by their rule (flaky=1 attribute)."
                + " Alternate syntax: regex_filter@flaky_test_attempts. Where flaky_test_attempts is"
                + " as above and regex_filter stands for a list of include and exclude regular"
                + " expression patterns (Also see --runs_per_test). Example:"
                + " --flaky_test_attempts=//foo/.*,-//foo/bar/.*@3 deflakes all tests in //foo/"
                + " except those under foo/bar three times. This option can be passed multiple"
                + " times. The most recently passed argument that matches takes precedence. If"
                + " nothing matches, behavior is as if 'default' above.")
    )
    abstract val testAttempts: MutableList<PerLabelOptions>?

    @get:com.google.devtools.common.options.Option(
        name = "test_tmpdir",
        defaultValue = "null",
        converter = OptionsUtils.PathFragmentConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TESTING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = "Specifies the base temporary directory for 'bazel test' to use."
    )
    abstract val testTmpDir: PathFragment?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "test_output",
        defaultValue = "summary",
        converter = com.google.devtools.build.lib.exec.ExecutionOptions.TestOutputFormat.Converter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TEST_RUNNER, com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT, com.google.devtools.common.options.OptionEffectTag.EXECUTION
        ],
        help = """
          Specifies desired output mode. Not to be confused with `--test_summary` which controls
          the test summary printed on command completion.

          Valid values are;
          - `summary` (default) to print summaries for failed tests,
          - `errors` to also print test logs for failed tests,
          - `all` to print summaries and logs for all tests and
          - `streamed` to output logs for all tests in real time (this will force tests to be
            executed locally one at a time regardless of `--test_strategy` value).
          
          """.trimIndent()
    )
    abstract var testOutput: TestOutputFormat?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "max_test_output_bytes",
        defaultValue = "-1",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TEST_RUNNER, com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT, com.google.devtools.common.options.OptionEffectTag.EXECUTION
        ],
        help = ("Specifies maximum per-test-log size that can be emitted when --test_output is 'errors' "
                + "or 'all'. Useful for avoiding overwhelming the output with excessively noisy test "
                + "output. The test header is included in the log size. Negative values imply no "
                + "limit. Output is all or nothing.")
    )
    abstract val maxTestOutputBytes: Int

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "test_summary",
        defaultValue = "short",
        converter = com.google.devtools.build.lib.exec.ExecutionOptions.TestSummaryFormat.Converter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = """
          Specifies the desired format of the test summary. Valid values are;
          - `short` to list all tests that ran to completion.
          - `short_uncached` to list tests that ran to completion, omitting cached tests.
          - `terse` to list only failed and flaky tests.
          - `detailed` to list tests that ran to completion and their test cases.
          - `detailed_uncached` to list tests that ran to completion and their test cases,
            omitting cached tests.
          - `testcase` to print summary in test case resolution without detailed information about
            failed test cases.
          - `none` to omit the summary.
          
          """.trimIndent()
    )
    abstract var testSummary: TestSummaryFormat?

    @get:com.google.devtools.common.options.Option(
        name = "local_resources",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        allowMultiple = true,
        help = ("Set the number of resources available to Bazel. "
                + "Takes in an assignment to a float or "
                + ResourceConverter.HOST_RAM_KEYWORD
                + "/"
                + ResourceConverter.HOST_CPUS_KEYWORD
                + ", optionally "
                + "followed by [-|*]<float> (eg. memory="
                + ResourceConverter.HOST_RAM_KEYWORD
                + "*.5 to use half the available RAM). "
                + "Can be used multiple times to specify multiple "
                + "types of resources. Bazel will limit concurrently running actions "
                + "based on the available resources and the resources required. "
                + "Tests can declare the amount of resources they need "
                + "by using a tag of the \"resources:<resource name>:<amount>\" format. "),
        converter = ResourceConverter.AssignmentConverter::class
    )
    abstract val localResourcesFields: MutableList<MutableMap.MutableEntry<String?, Double?>?>?

    val localResources: com.google.common.collect.ImmutableMap<String?, Double?>
        get() {
            val resources: com.google.common.collect.ImmutableMap.Builder<String?, Double?> =
                com.google.common.collect.ImmutableMap.builder<String?, Double?>()
            return resources
                .put(ResourceSet.CPU, LocalHostCapacity.getLocalHostCapacity().getCpuUsage())
                .put(ResourceSet.MEMORY, .67 * LocalHostCapacity.getLocalHostCapacity().getMemoryMb())
                .putAll(this.localResourcesFields)
                .buildKeepingLast()
        }

    @get:com.google.devtools.common.options.Option(
        name = "experimental_cpu_load_scheduling",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("Enables the experimental local execution scheduling based on CPU load, not estimation of"
                + " actions one by one.  Experimental scheduling have showed the large benefit on a"
                + " large local builds on a powerful machines with the large number of cores."
                + " Recommended to use with --local_resources=cpu=HOST_CPUS")
    )
    abstract val experimentalCpuLoadScheduling: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_cpu_load_scheduling_window_size",
        defaultValue = "5000ms",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("The size of window during experimental scheduling of action based on CPU load. Make"
                + " sense to define only when flag --experimental_cpu_load_scheduling is enabled.")
    )
    abstract val experimentalCpuLoadSchedulingWindowSize: java.time.Duration?

    @get:com.google.devtools.common.options.Option(
        name = "local_test_jobs",
        defaultValue = "auto",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TESTING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("The max number of local test jobs to run concurrently. "
                + "Takes "
                + ResourceConverter.FLAG_SYNTAX
                + ". 0 means local resources will limit the number of local test jobs to run "
                + "concurrently instead. Setting this greater than the value for --jobs "
                + "is ineffectual."),
        converter = LocalTestJobsConverter::class
    )
    abstract val localTestJobs: Int

    fun usingLocalTestJobs(): Boolean {
        return this.localTestJobs != 0
    }

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "cache_computed_file_digests",
        defaultValue = "50000",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("If greater than 0, configures Bazel to cache file digests in memory based on their "
                + "metadata instead of recomputing the digests from disk every time they are needed. "
                + "Setting this to 0 ensures correctness because not all file changes can be noted "
                + "from file metadata. When not 0, the number indicates the size of the cache as the "
                + "number of file digests to be cached.")
    )
    abstract val cacheSizeForComputedFileDigests: Long

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_enable_critical_path_profiling",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("If set (the default), critical path profiling is enabled for the execution phase. This"
                + " has a slight overhead in RAM and CPU, and may prevent Bazel from making certain"
                + " aggressive RAM optimizations in some cases.")
    )
    abstract val enableCriticalPathProfiling: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_stats_summary",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        defaultValue = "false",
        help = "Enable a modernized summary of the build stats."
    )
    abstract val statsSummary: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "execution_log_binary_file",
        defaultValue = "null",
        category = "verbosity",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        converter = ExecutionLogFileConverter::class,
        help = ("Log the executed spawns into this file as length-delimited SpawnExec protos, according"
                + " to src/main/protobuf/spawn.proto. Prefer --execution_log_compact_file, which is"
                + " significantly smaller and cheaper to produce. The flag accepts boolean and string"
                + " values. If string, it represents a local path. If true, then"
                + " --experimental_stream_log_file_uploads must be set, whereby it will stream the"
                + " execution log to remote storage. If false, then logging to the execution log is"
                + " disabled. Related flags: --execution_log_compact_file (compact format; mutually"
                + " exclusive), --execution_log_json_file (text JSON format; mutually exclusive),"
                + " --execution_log_sort (whether to sort the execution log), --subcommands (for"
                + " displaying subcommands in terminal output).")
    )
    abstract val executionLogBinaryFile: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "execution_log_json_file",
        defaultValue = "null",
        category = "verbosity",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        converter = ExecutionLogFileConverter::class,
        help = ("Log the executed spawns into this file as newline-delimited JSON representations of"
                + " SpawnExec protos, according to src/main/protobuf/spawn.proto. Prefer"
                + " --execution_log_compact_file, which is significantly smaller and cheaper to"
                + " produce. The flag accepts boolean and string values. If string, it represents a"
                + " local path. If true, then --experimental_stream_log_file_uploads must be set,"
                + " whereby it will stream the execution log to remote storage. If false, then"
                + " logging to the execution log is disabled. Related flags:"
                + " --execution_log_compact_file (compact format; mutually exclusive),"
                + " --execution_log_binary_file (binary protobuf format; mutually exclusive),"
                + " --execution_log_sort (whether to sort the execution log),"
                + " --subcommands (for displaying subcommands in terminal output).")
    )
    abstract val executionLogJsonFile: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "execution_log_compact_file",
        oldName = "experimental_execution_log_compact_file",
        defaultValue = "null",
        category = "verbosity",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        converter = ExecutionLogFileConverter::class,
        help = ("Log the executed spawns into this file as length-delimited ExecLogEntry protos,"
                + " according to src/main/protobuf/spawn.proto. The entire file is zstd compressed."
                + " The flag accepts boolean and string values. If string, it represents a local"
                + " path. If true, then --experimental_stream_log_file_uploads must be set, whereby"
                + " it will stream the execution log to remote storage. If false, then logging to the"
                + " execution log is disabled. Related flags: --execution_log_binary_file (binary"
                + " protobuf format; mutually exclusive), --execution_log_json_file (text JSON"
                + " format; mutually exclusive), --subcommands (for displaying subcommands in"
                + " terminal output).")
    )
    abstract val executionLogCompactFile: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "execution_log_sort",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("Whether to sort the execution log, making it easier to compare logs across invocations."
                + " Set to false to avoid potentially significant CPU and memory usage at the end of"
                + " the invocation, at the cost of producing the log in nondeterministic execution"
                + " order. Only applies to the binary and JSON formats; the compact format is never"
                + " sorted.")
    )
    abstract val executionLogSort: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "execution_log_mnemonic_filter",
        defaultValue = ".*",
        converter = RegexFilterConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("Filter the execution log by mnemonic. Only spawns with a matching mnemonic will be"
                + " logged. Supports a comma-separated list of regexes, with optional '-' prefix"
                + " for exclusions. The default is to log every spawn.")
    )
    abstract val executionLogMnemonicFilter: com.google.devtools.build.lib.util.RegexFilter?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_remote_cache_eviction_retries",
        defaultValue = "5",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.REMOTE,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("The maximum number of attempts to retry if the build encountered a transient remote"
                + " cache error that would otherwise fail the build. Applies for example when"
                + " artifacts are evicted from the remote cache, or in certain cache failure"
                + " conditions. A new invocation id will be generated for each attempt.")
    )
    abstract val remoteRetryOnTransientCacheError: Int

    @get:com.google.devtools.common.options.Option(
        name = "allow_one_action_on_resource_unavailable",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("If set, allow at least one action to run even if the resource is not enough or"
                + " unavailable.")
    )
    abstract val allowOneActionOnResourceUnavailable: Boolean

    /**
     * Accepts a filesystem path, or boolean-like values selecting a default location or disabling the
     * log.
     */
    class ExecutionLogFileConverter : com.google.devtools.common.options.Converter.Contextless<PathFragment?>(),
        com.google.devtools.common.options.BooleanStyleOption {
        override fun convert(input: String): PathFragment? {
            if (input.isEmpty()) {
                return PathFragment.EMPTY_FRAGMENT
            }
            try {
                return if (BOOLEAN_CONVERTER.convert(input)) PathFragment.EMPTY_FRAGMENT else null
            } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                return OptionsUtils.PathFragmentConverter().convert(input)
            }
        }

        val typeDescription: String
            get() = "a path, or a boolean to use the default execution log location"

        companion object {
            private val BOOLEAN_CONVERTER: com.google.devtools.common.options.Converters.BooleanConverter =
                com.google.devtools.common.options.Converters.BooleanConverter()
        }
    }

    /** An enum for specifying different formats of test output.  */
    enum class TestOutputFormat {
        /**
         * Provide summary output only. NOTE: Functionally this is `NONE`, as `--test_summary` controls
         * the summary output.
         */
        SUMMARY,

        /** Print output from failed tests to the stderr after the test failure.  */
        ERRORS,

        /** Print output from all tests to the stderr after the test completion.  */
        ALL,

        /**
         * Stream output from tests as they run. Forces tests to be executed sequentially and locally.
         */
        STREAMED;

        /** Converts to [TestOutputFormat].  */
        class Converter : com.google.devtools.common.options.EnumConverter<TestOutputFormat?>(
            TestOutputFormat::class.java,
            "test output"
        )
    }

    /** An enum for specifying different formatting styles of test summaries.  */
    enum class TestSummaryFormat {
        /** Show all tests that can to completion, but not individual test cases.  */
        SHORT,

        /** Like "SHORT", but do not show tests that were cached.  */
        SHORT_UNCACHED,

        /** Like "SHORT", but even shorter: Only failed and flaky tests.  */
        TERSE,

        /**
         * Show all tests (including tests that failed to build), their test cases, and a summary of all
         * test cases (passed, skipped, failing).
         */
        DETAILED,

        /** Like "DETAILED", but only for tests that were not cached.  */
        DETAILED_UNCACHED,

        /** Do not print summary.  */
        NONE,

        /** Summarize all test cases (passed, skipped, failing).  */
        TESTCASE;

        /** Converts to [TestSummaryFormat].  */
        class Converter : com.google.devtools.common.options.EnumConverter<TestSummaryFormat?>(
            TestSummaryFormat::class.java,
            "test summary"
        )
    }

    /** Converter for the --flaky_test_attempts option.  */
    class TestAttemptsConverter : PerLabelOptions.PerLabelOptionsConverter() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        private fun validateInput(input: String) {
            if (input != "default") {
                val value: Int = java.lang.Integer.parseInt(input)
                if (value < MIN_VALUE) {
                    throw com.google.devtools.common.options.OptionsParsingException("'" + input + "' should be >= " + MIN_VALUE)
                } else if (value > MAX_VALUE) {
                    throw com.google.devtools.common.options.OptionsParsingException("'" + input + "' should be <= " + MAX_VALUE)
                }
            }
        }

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
            validateInput(input)
            val catchAll: com.google.devtools.build.lib.util.RegexFilter =
                com.google.devtools.build.lib.util.RegexFilter(
                    Collections.singletonList<String?>(".*"),
                    Collections.emptyList<String?>()
                )
            return PerLabelOptions(catchAll, Collections.singletonList<T?>(input))
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        private fun parseAsRegex(input: String?): PerLabelOptions {
            val testRegexps: PerLabelOptions = super.convert(input)
            if (testRegexps.options.size() !== 1) {
                throw com.google.devtools.common.options.OptionsParsingException("'" + input + "' has multiple runs for a single pattern")
            }
            val runsPerTest: String? = com.google.common.collect.Iterables.getOnlyElement<String?>(testRegexps.options)
            try {
                // Run this in order to catch errors.
                validateInput(runsPerTest!!)
            } catch (e: java.lang.NumberFormatException) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    "'" + input + "' has a non-numeric value",
                    e
                )
            }
            return testRegexps
        }

        val typeDescription: String
            get() = ("a positive integer, the string \"default\", or test_regex@attempts. "
                    + "This flag may be passed more than once")

        companion object {
            private const val MIN_VALUE = 1
            private const val MAX_VALUE = 10
        }
    }

    /** Converter for --local_test_jobs, which takes {@value FLAG_SYNTAX}  */
    class LocalTestJobsConverter :
        ResourceConverter.IntegerConverter( /* auto= */java.util.function.Supplier { 0 },  /* minValue= */
            0,  /* maxValue= */
            java.lang.Integer.MAX_VALUE
        )

    /** Converter for --subcommands  */
    class ShowSubcommandsConverter : com.google.devtools.common.options.BoolOrEnumConverter<ShowSubcommands?>(
        ShowSubcommands::class.java, "subcommand option", ShowSubcommands.TRUE, ShowSubcommands.FALSE
    )

    /** Converter for options that take a label-to-string-list assignment.  */
    protected class LabelToStringListConverter
    internal constructor() :
        com.google.devtools.common.options.Converters.AssignmentToListOfValuesConverter<Label?, String?>(
            LabelConverter(),
            com.google.devtools.common.options.Converters.StringConverter(),
            com.google.devtools.common.options.Converters.AssignmentToListOfValuesConverter.AllowEmptyKeys.NO
        ) {
        val typeDescription: String
            get() = "a '<Label>=value[,value]' assignment"
    }
}
