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
package com.google.devtools.build.lib.buildtool

import com.github.benmanes.caffeine.cache.CaffeineSpec
import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.buildtool.BuildRequestOptions
import com.google.devtools.build.lib.util.OptionsUtils
import com.google.devtools.build.lib.util.ResourceConverter
import com.google.devtools.build.lib.vfs.PathFragment

/**
 * Options interface for [BuildRequest]: can be used to parse command-line arguments.
 * 
 * 
 * See also `ExecutionOptions`; from the user's point of view, there's no qualitative
 * difference between these two sets of options.
 */
@com.google.devtools.common.options.OptionsClass
abstract class BuildRequestOptions : com.google.devtools.common.options.OptionsBase() {
    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "jobs",
        abbrev = 'j',
        defaultValue = "auto",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS, com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        converter = com.google.devtools.build.lib.buildtool.BuildRequestOptions.JobsConverter::class,
        help = ("The number of concurrent jobs to run. Takes "
                + ResourceConverter.FLAG_SYNTAX
                + ". Values must be between 1 and "
                + MAX_JOBS
                + ". Values above "
                + JOBS_TOO_HIGH_WARNING
                + " may cause memory issues. \"auto\" calculates a reasonable default based on"
                + " host resources.")
    )
    abstract val jobs: Int

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_async_execution",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS, com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = """
          If set to true, Bazel is allowed to run action in a virtual thread. The number of
          actions in flight is still capped with `--jobs`.
          
          """.trimIndent()
    )
    abstract val useAsyncExecution: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_async_execution_max_concurrent_actions",
        defaultValue = "5000",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS, com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = """
          The number of maximum concurrent actions to run with async execution. If the value is
          less than `--jobs`, it is clamped to `--jobs`.
          
          """.trimIndent()
    )
    abstract val asyncExecutionMaxConcurrentActions: Int

    @get:com.google.devtools.common.options.Option(
        name = "progress_report_interval",
        defaultValue = "0",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        converter = ProgressReportIntervalConverter::class,
        help = """
          The number of seconds to wait between reports on still running jobs. The
          default value 0 means the first report will be printed after 10
          seconds, then 30 seconds and after that progress is reported once every minute.
          When `--curses` is enabled, progress is reported every second.
          
          """.trimIndent()
    )
    abstract val progressReportInterval: Int

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "explain",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        converter = OptionsUtils.PathFragmentConverter::class,
        help = ("Causes the build system to explain each executed step of the "
                + "build. The explanation is written to the specified log file.")
    )
    abstract val explanationPath: PathFragment?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "output_filter",
        converter = com.google.devtools.common.options.Converters.RegexPatternConverter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Only shows warnings and action outputs for rules with a name matching the provided "
                + "regular expression.")
    )
    abstract val outputFilter: com.google.devtools.common.options.RegexPatternOption?

    @get:com.google.devtools.common.options.Option(
        name = "analyze",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          Execute the loading/analysis phase; this is the usual behaviour. Specifying `--noanalyze`
          causes the build to stop before starting the loading/analysis phase, just doing
          target pattern parsing and returning zero if that completed successfully; this
          mode is useful for testing.
          
          """.trimIndent()
    )
    abstract val performAnalysisPhase: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "build",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          Execute the build; this is the usual behaviour.
          Specifying `--nobuild` causes the build to stop before executing the build
          actions, returning zero if the package loading and analysis phases completed
          successfully; this mode is useful for testing those phases.
          
          """.trimIndent()
    )
    abstract val performExecutionPhase: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "output_groups",
        converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter::class,
        allowMultiple = true,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        defaultValue = "null",
        help = """
          A list of comma-separated output group names, each of which optionally prefixed by a `+`
          or a `-`. A group prefixed by `+` is added to the default set of output groups,
          while a group prefixed by `-` is removed from the default set. If at least one
          group is not prefixed, the default set of output groups is omitted. For example,
          `--output_groups=+foo,+bar` builds the union of the default set, foo, and bar,
          while `--output_groups=foo,bar` overrides the default set such that only foo and
          bar are built.
          
          """.trimIndent()
    )
    abstract val outputGroups: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "run_validations",
        oldName = "experimental_run_validations",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          Whether to run validation actions as part of the build. See [Validation Actions].

          [Validation Actions]: https://bazel.build/extending/rules#validation_actions
          
          """.trimIndent()
    )
    abstract val runValidationActions: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_use_validation_aspect",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Whether to run validation actions using aspect (for parallelism with tests)."
    )
    abstract val useValidationAspect: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "show_result",
        defaultValue = "1",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          Show the results of the build. For each target, state whether or not it was brought
          up-to-date, and if so, a list of output files that were built.  The printed files
          are convenient strings for copy+pasting to the shell, to execute them.

          This option requires an integer argument, which is the threshold number of targets
          above which result information is not printed. Thus zero causes suppression of
          the message and `MAX_INT` causes printing of the result to occur always. The
          default is one.

          If nothing was built for a target its results may be omitted to keep the output
          under the threshold.
          
          """.trimIndent()
    )
    abstract val maxResultTargets: Int

    @get:com.google.devtools.common.options.Option(
        name = "hide_aspect_results",
        converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter::class,
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = """
          Comma-separated list of aspect names to not display in results (see `--show_result`).
          Useful for keeping aspects added by wrappers which are typically not interesting
          to end users out of console output.
          
          """.trimIndent()
    )
    abstract val hideAspectResults: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "symlink_prefix",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          The prefix that is prepended to any of the convenience symlinks that are created
          after a build. If omitted, the default value is the name of the build tool
          followed by a hyphen. If `/` is passed, then no symlinks are created and no
          warning is emitted. Warning: the special functionality for `/` will be deprecated
          soon; use `--experimental_convenience_symlinks=ignore` instead.
          
          """.trimIndent()
    )
    abstract val symlinkPrefix: String?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_convenience_symlinks",
        converter = ConvenienceSymlinksConverter::class,
        defaultValue = "normal",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          This flag controls how the convenience symlinks (the symlinks that appear in the
          workspace after the build) will be managed. Possible values:
          - `normal` (default): Each kind of convenience symlink will be created or deleted,
            as determined by the build.
          - `clean`: All symlinks will be unconditionally deleted.
          - `ignore`: Symlinks will not be created or cleaned up.
          - `log_only`: Generate log messages as if `normal` were passed, but don't actually
            perform any filesystem operations (useful for tools).

          Note that only symlinks whose names are generated by the current value of
          `--symlink_prefix` can be affected; if the prefix changes, any pre-existing
          symlinks will be left alone.
          
          """.trimIndent()
    )
    abstract val experimentalConvenienceSymlinks: ConvenienceSymlinksMode?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_convenience_symlinks_bep_event",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          This flag controls whether or not we will post the build event
          `ConvenienceSymlinksIdentified` to the Build Event Protocol. If the value is true,
          the BEP will have an entry for `convenienceSymlinksIdentified`,
          listing all of the convenience symlinks created in your workspace. If false, then
          the `convenienceSymlinksIdentified` entry in the BEP will be empty.
          
          """.trimIndent()
    )
    abstract val experimentalConvenienceSymlinksBepEvent: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "output_tree_tracking",
        oldName = "experimental_output_tree_tracking",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = ("If set, tell the output service (if any) to track when files in the output "
                + "tree have been modified externally (not by the build system). "
                + "This should improve incremental build speed when an appropriate output service "
                + "is enabled.")
    )
    abstract val finalizeActions: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "directory_creation_cache",
        defaultValue = "maximumSize=100000",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        converter = com.google.devtools.common.options.Converters.CaffeineSpecConverter::class,
        help = ("Describes the cache used to store known regular directories as they're created. Parent"
                + " directories of output files are created on-demand during action execution.")
    )
    abstract val directoryCreationCacheSpec: CaffeineSpec?

    @get:com.google.devtools.common.options.Option(
        name = "aspects",
        converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        allowMultiple = true,
        help = """
          Comma-separated list of aspects to be applied to top-level targets. In the list, if
          aspect `some_aspect` specifies required aspect providers via
          `required_aspect_providers`, `some_aspect` will run after
          every aspect that was mentioned before it in the aspects list whose advertised
          providers satisfy `some_aspect` required aspect providers. Moreover,
          `some_aspect` will run after all its required aspects specified by
          `requires` attribute.
          `some_aspect` will then have access to the values of those aspects'
          providers.
          `{bzl-file-label}%{aspect_name}`, for example `//tools:my_def.bzl%my_aspect`, where
          `my_aspect` is a top-level value from a file `tools/my_def.bzl`.
          
          """.trimIndent()
    )
    abstract val aspects: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "aspects_parameters",
        converter = com.google.devtools.common.options.Converters.AssignmentConverter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.GENERIC_INPUTS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        allowMultiple = true,
        help = """
          Specifies the values of the command-line aspects parameters. Each parameter value is
          specified via `<param_name>=<param_value>`, for example `my_param=my_val` where
          `my_param` is a parameter of some aspect in `--aspects` list or required by an
          aspect in the list. This option can be used multiple times. However, it is not
          allowed to assign values to the same parameter more than once.
          
          """.trimIndent()
    )
    abstract val aspectsParameters: MutableList<MutableMap.MutableEntry<String?, String?>?>?

    fun getSymlinkPrefix(productName: String?): String? {
        return if (this.symlinkPrefix == null) productName + "-" else this.symlinkPrefix
    }

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "use_action_cache",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION, com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS
        ],
        help = "Whether to use the action cache"
    )
    abstract val useActionCache: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "rewind_lost_inputs",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.REMOTE,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = "Whether to use action rewinding to recover from lost inputs."
    )
    abstract val rewindLostInputs: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "incompatible_skip_genfiles_symlink",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = """
          If set to true, the genfiles symlink will not be created. For more information, see
          [GH-8651].

          [GH-8651]: https://github.com/bazelbuild/bazel/issues/8651
          
          """.trimIndent()
    )
    abstract val incompatibleSkipGenfilesSymlink: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "target_pattern_file",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.GENERIC_INPUTS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS],
        help = ("If set, build will read patterns from the file named here, rather than on the command "
                + "line. It is an error to specify a file here as well as command-line patterns.")
    )
    abstract val targetPatternFile: String?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_merged_skyframe_analysis_execution",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = "If this flag is set, the analysis and execution phases of Skyframe are merged."
    )
    abstract val mergedSkyframeAnalysisExecutionDoNotUseDirectly: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_skymeld_analysis_overlap_percentage",
        defaultValue = "100",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        converter = com.google.devtools.common.options.Converters.PercentageConverter::class,
        help = ("The value represents the % of the analysis phase which will be overlapped with the"
                + " execution phase. A value of x means Skyframe will queue up execution tasks and"
                + " wait until there's x% of the top level target left to be analyzed before allowing"
                + " them to launch. When the value is 0%, we'd wait for all analysis to finish before"
                + " executing (no overlap). When it's 100%, the phases are free to overlap as much as"
                + " they can.")
    )
    abstract val skymeldAnalysisOverlapPercentage: Int

    /** Converter for filesystem value checker threads.  */
    class ThreadConverter :
        ResourceConverter.IntegerConverter( /* auto= */ResourceConverter.HOST_CPUS_SUPPLIER,  /* minValue= */
            1,  /* maxValue= */
            java.lang.Integer.MAX_VALUE
        )

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_fsvc_threads",
        defaultValue = "200",
        converter = ThreadConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = "The number of threads that are used by the `FileSystemValueChecker`."
    )
    abstract val fsvcThreads: Int

    @get:com.google.devtools.common.options.Option(
        name = "experimental_skyframe_memory_dump",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          Dump the memory use of individual nodes in the Skyframe graph after the build. This
          option takes a number of flags separated by commas:
          - `json` (no-op, that's the only format).
          - `notransient` (don't traverse transient fields).
          - `noconfig` (ignore objects related to configurations).
          - `noprecomputed` (ignore precomputed values).
          - `noworkspacestatus` (ignore objects related to the workspace status machinery).
          
          """.trimIndent()
    )
    abstract val skyframeMemoryDump: String?

    @get:com.google.devtools.common.options.Option(
        name = "enforce_project_configs",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = ("If true, interactive builds may only pass the --scl_config build flag; they may not use"
                + " any other build flags. --scl_config must be set to an officially supported"
                + " project configuration. Supported configurations are defined in the target's"
                + " PROJECT.scl, which can be found by walking up the target's packagge path. See"
                + " b/324126745.")
    )
    abstract val enforceProjectConfigs: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_skyframe_error_handling_refactor",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL, com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
        help = ("Used solely for the safe rollout of simplifying Skyframe error handling. This will be "
                + " removed once the rollout is complete (expected timeframe: 1 release)")
    )
    abstract val skyframeErrorHandlingRefactor: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_aquery_dump_after_build_format",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          Writes the state of Skyframe (which includes previous invocations on this blaze instance
          as well) after a build. Output is streamed remotely unless local output is
          requested with `--experimental_aquery_dump_after_build_output_file`.  Does not
          honor aquery flags for `--include_*`, but uses the same defaults, except for
          `--include_commandline=false`. Possible output formats:
          `proto|streamed_proto|textproto|jsonproto`. Using this will disable Skymeld.
          
          """.trimIndent()
    )
    abstract val aqueryDumpAfterBuildFormat: String?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_aquery_dump_after_build_output_file",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        converter = OptionsUtils.PathFragmentConverter::class,
        help = """
          Specify the output file for the aquery dump after a build. Use in conjunction with
          `--experimental_aquery_dump_after_build_format`. The path provided is relative to
          Bazel's output base, unless it's an absolute path. Using this will disable Skymeld.
          
          """.trimIndent()
    )
    abstract val aqueryDumpAfterBuildOutputFile: PathFragment?

    /**
     * Converter for jobs: Takes keyword ({@value #FLAG_SYNTAX}). Values must be between 1 and
     * MAX_JOBS.
     */
    class JobsConverter :
        ResourceConverter.IntegerConverter( /* auto= */ResourceConverter.HOST_CPUS_SUPPLIER,  /* minValue= */
            1,  /* maxValue= */
            MAX_JOBS
        ) {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun checkAndLimit(value: Int): Int {
            if (value.toDouble() < minValue) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format("Value '(%d)' must be at least %d.", value, minValue)
                )
            }
            if (value.toDouble() > maxValue) {
                logger.atWarning().log(
                    ("Flag remoteWorker \"jobs\" ('%d') was set too high. "
                            + "This is a result of passing large values to --local_resources or --jobs. "
                            + "Using '%d' jobs"),
                    value, maxValue
                )
                return maxValue
            }
            return value
        }
    }

    /** Converter for progress_report_interval: [0, 3600].  */
    class ProgressReportIntervalConverter : com.google.devtools.common.options.Converters.RangeConverter(0, 3600)

    /**
     * The [BoolOrEnumConverter] for the [ConvenienceSymlinksMode] where NORMAL is true
     * and IGNORE is false.
     */
    class ConvenienceSymlinksConverter

        : com.google.devtools.common.options.BoolOrEnumConverter<ConvenienceSymlinksMode?>(
        ConvenienceSymlinksMode::class.java,
        "convenience symlinks mode",
        ConvenienceSymlinksMode.NORMAL,
        ConvenienceSymlinksMode.IGNORE
    )

    /** Determines how the convenience symlinks are presented to the user  */
    internal enum class ConvenienceSymlinksMode {
        /** Will manage symlinks based on the symlink prefix.  */
        NORMAL,

        /** Will clean up any existing symlinks.  */
        CLEAN,

        /** Will not create or clean up any symlinks.  */
        IGNORE,

        /** Will not create or clean up any symlinks, but will record the symlinks.  */
        LOG_ONLY
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
        private const val JOBS_TOO_HIGH_WARNING = 2500

        @com.google.common.annotations.VisibleForTesting
        const val MAX_JOBS: Int = 5000
    }
}
