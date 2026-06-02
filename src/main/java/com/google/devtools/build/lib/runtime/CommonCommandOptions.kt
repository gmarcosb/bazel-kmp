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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.profiler.MemoryProfiler.MemoryProfileStableHeapParameters

/** Options common to all commands.  */
@com.google.devtools.common.options.OptionsClass
abstract class CommonCommandOptions : com.google.devtools.common.options.OptionsBase() {
    @get:com.google.devtools.common.options.Option(
        name = "enable_platform_specific_config",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("If true, Bazel picks up host-OS-specific config lines from bazelrc files. For example, "
                + "if the host OS is Linux and you run bazel build, Bazel picks up lines starting "
                + "with build:linux. Supported OS identifiers are linux, macos, windows, freebsd, "
                + "and openbsd. Enabling this flag is equivalent to using --config=linux on Linux, "
                + "--config=windows on Windows, etc.")
    )
    abstract val enablePlatformSpecificConfig: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "config",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        allowMultiple = true,
        help = ("Selects additional config sections from the rc files; for every <command>, it "
                + "also pulls in the options from <command>:<config> if such a section exists; "
                + "if this section doesn't exist in any .rc file, Blaze fails with an error. "
                + "The config sections and flag combinations they are equivalent to are "
                + "located in the tools/*.blazerc config files.")
    )
    abstract val configs: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "logging",
        defaultValue = "3",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        converter = com.google.devtools.common.options.Converters.LogLevelConverter::class,
        help = "The logging level."
    )
    abstract val verbosity: java.util.logging.Level?

    @get:com.google.devtools.common.options.Option(
        name = "client_cwd",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS],
        converter = com.google.devtools.build.lib.util.OptionsUtils.PathFragmentConverter::class,
        help = "A system-generated parameter which specifies the client's working directory"
    )
    abstract val clientCwd: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "announce_rc",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Whether to announce rc options."
    )
    abstract val announceRcOptions: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "always_profile_slow_operations",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = "Whether profiling slow operations is always turned on"
    )
    abstract val alwaysProfileSlowOperations: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_install_base_gc_max_age",
        defaultValue = "30d",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        converter = com.google.devtools.common.options.Converters.DurationConverter::class,
        help = ("How long an install base must go unused before it's eligible for garbage collection."
                + " If nonzero, the server will attempt to garbage collect other install bases when"
                + " idle.")
    )
    abstract val installBaseGcMaxAge: java.time.Duration?

    abstract fun setInstallBaseGcMaxAge(value: java.time.Duration?)

    @get:com.google.devtools.common.options.Option(
        name = "experimental_action_cache_gc_idle_delay",
        defaultValue = "5m",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        converter = com.google.devtools.common.options.Converters.DurationConverter::class,
        help = ("How long the server must remain idle before a garbage collection of the action cache is"
                + " attempted. Ineffectual unless --experimental_action_cache_gc_max_age is nonzero.")
    )
    abstract val actionCacheGcIdleDelay: java.time.Duration?

    abstract fun setActionCacheGcIdleDelay(value: java.time.Duration?)

    @get:com.google.devtools.common.options.Option(
        name = "experimental_action_cache_gc_threshold",
        defaultValue = "10",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        converter = com.google.devtools.common.options.Converters.PercentageConverter::class,
        help = ("The percentage of stale action cache entries required for garbage collection to be"
                + " triggered. Ineffectual unless --experimental_action_cache_gc_max_age is nonzero.")
    )
    abstract var actionCacheGcThreshold: Int

    @get:com.google.devtools.common.options.Option(
        name = "experimental_action_cache_gc_max_age",
        defaultValue = "0",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        converter = com.google.devtools.common.options.Converters.DurationConverter::class,
        help = ("If set to a nonzero value, the action cache will be periodically garbage collected to"
                + " remove entries older than this age. Garbage collection occurs in the background"
                + " once the server has become idle, as determined by the"
                + " --experimental_action_cache_gc_idle_delay and"
                + " --experimental_action_cache_gc_threshold flags.")
    )
    abstract val actionCacheGcMaxAge: java.time.Duration?

    abstract fun setActionCacheGcMaxAge(value: java.time.Duration?)

    @get:com.google.devtools.common.options.Option(
        name = "experimental_enable_thread_dump",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = ("Whether to enable thread dumps. If true, Bazel will dump the state of all threads"
                + " (including virtual threads) to a file every --experimental_thread_dump_interval,"
                + " or after action execution being inactive for"
                + " --experimental_thread_dump_action_execution_inactivity_duration. The dumps will"
                + " be written to the <output_base>/server/thread_dumps/ directory.")
    )
    abstract val enableThreadDump: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_thread_dump_interval",
        defaultValue = "0",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        converter = com.google.devtools.common.options.Converters.DurationConverter::class,
        help = ("How often to dump the threads periodically. If zero, no thread dumps are written"
                + " periodically.")
    )
    abstract val threadDumpInterval: java.time.Duration?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_thread_dump_action_execution_inactivity_duration",
        defaultValue = "0",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        converter = com.google.devtools.common.options.Converters.DurationConverter::class,
        help = ("Dump the threads when action execution being inactive for this duration. If zero, no"
                + " thread dumps are written for action execution being inactive.")
    )
    abstract val threadDumpActionExecutionInactivityDuration: java.time.Duration?

    /** Converter for UUID. Accepts values as specified by [UUID.fromString].  */
    class UUIDConverter : com.google.devtools.common.options.Converter.Contextless<UUID?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String?): UUID? {
            if (com.google.common.base.Strings.isNullOrEmpty(input)) {
                return null
            }
            try {
                return UUID.fromString(input)
            } catch (e: java.lang.IllegalArgumentException) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format("Value '%s' is not a value UUID.", input), e
                )
            }
        }

        val typeDescription: String
            get() = "a UUID"
    }

    /**
     * Converter for options (--build_request_id) that accept prefixed UUIDs. Since we do not care
     * about the structure of this value after validation, we store it as a string.
     */
    class PrefixedUUIDConverter : com.google.devtools.common.options.Converter.Contextless<String?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String?): String? {
            if (com.google.common.base.Strings.isNullOrEmpty(input)) {
                return null
            }
            // UUIDs that are accepted by UUID#fromString have 36 characters. Interpret the last 36
            // characters as an UUID and the rest as a prefix. We do not check anything about the contents
            // of the prefix.
            try {
                val uuidStartIndex: Int = input.length() - 36
                UUID.fromString(input.substring(uuidStartIndex))
            } catch (e: java.lang.IllegalArgumentException) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format("Value '%s' does not end in a valid UUID.", input), e
                )
            } catch (e: java.lang.IndexOutOfBoundsException) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format("Value '%s' does not end in a valid UUID.", input), e
                )
            }
            return input
        }

        val typeDescription: String
            get() = "An optionally prefixed UUID. The last 36 characters will be verified as a UUID."
    }

    @get:com.google.devtools.common.options.Option(
        name = "invocation_id",
        defaultValue = "",
        converter = UUIDConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING, com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = ("Unique identifier, in UUID format, for the command being run. If explicitly specified"
                + " uniqueness must be ensured by the caller. The UUID is printed to stderr, the BEP"
                + " and remote execution protocol.")
    )
    abstract val invocationId: UUID?

    @get:com.google.devtools.common.options.Option(
        name = "build_request_id",
        defaultValue = "",
        converter = PrefixedUUIDConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING, com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        help = "Unique string identifier for the build being run."
    )
    abstract val buildRequestId: String?

    @get:com.google.devtools.common.options.Option(
        name = "build_metadata",
        converter = com.google.devtools.common.options.Converters.AssignmentConverter::class,
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = "Custom key-value string pairs to supply in a build event."
    )
    abstract val buildMetadata: MutableList<MutableMap.MutableEntry<String?, String?>?>?

    @get:com.google.devtools.common.options.Option(
        name = "oom_message",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING, com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        help = "Custom message to be emitted on an out of memory failure."
    )
    abstract val oomMessage: String?

    @get:com.google.devtools.common.options.Option(
        name = "generate_json_trace_profile",
        oldName = "experimental_generate_json_trace_profile",
        defaultValue = "auto",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = ("If enabled, Bazel profiles the build and writes a JSON-format profile into a file in"
                + " the output base. View profile by loading into chrome://tracing. By default Bazel"
                + " writes the profile for all build-like commands and query.")
    )
    abstract val enableTracer: com.google.devtools.common.options.TriState?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_profile_additional_tasks",
        converter = ProfilerTaskConverter::class,
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = "Specifies additional profile tasks to be included in the profile."
    )
    abstract val additionalProfileTasks: MutableList<ProfilerTask>?

    @get:com.google.devtools.common.options.Option(
        name = "slim_profile",
        oldName = "experimental_slim_json_profile",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = ("Slims down the size of the JSON profile by merging events if the profile gets "
                + "too large.")
    )
    abstract val slimProfile: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_profile_include_primary_output",
        oldName = "experimental_include_primary_output",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = ("Includes the extra \"out\" attribute in action events that contains the exec path "
                + "to the action's primary output.")
    )
    abstract val includePrimaryOutput: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_profile_include_target_label",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = "Includes target label in action events' JSON profile data."
    )
    abstract val profileIncludeTargetLabel: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_profile_include_target_configuration",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = "Includes target configuration hash in action events' JSON profile data."
    )
    abstract val profileIncludeTargetConfiguration: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "profiles_to_retain",
        defaultValue = "5",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = ("Number of profiles to retain in the output base. If there are more than this number of"
                + " profiles in the output base, the oldest are deleted until the total is under the"
                + " limit.")
    )
    abstract val profilesToRetain: Int

    @get:com.google.devtools.common.options.Option(
        name = "profile",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        converter = com.google.devtools.build.lib.util.OptionsUtils.PathFragmentConverter::class,
        help = ("If set, profile Bazel and write data to the specified file. See"
                + " https://bazel.build/advanced/performance/json-trace-profile for more"
                + " information.")
    )
    abstract val profilePath: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "starlark_cpu_profile",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = "Writes into the specified file a pprof profile of CPU usage by all Starlark threads."
    )
    abstract val starlarkCpuProfile: String?

    @get:com.google.devtools.common.options.Option(
        name = "record_full_profiler_data",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = ("By default, Bazel profiler will record only aggregated data for fast but numerous "
                + "events (such as statting the file). If this option is enabled, profiler will "
                + "record each event - resulting in more precise profiling data but LARGE "
                + "performance hit. Option only has effect if --profile used as well.")
    )
    abstract val recordFullProfilerData: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_collect_worker_data_in_profiler",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = "If enabled, the profiler collects worker's aggregated resource data."
    )
    abstract val collectWorkerDataInProfiler: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_collect_load_average_in_profiler",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = "If enabled, the profiler collects the system's overall load average."
    )
    abstract val collectLoadAverageInProfiler: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_collect_system_network_usage",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = "If enabled, the profiler collects the system's network usage."
    )
    abstract val collectSystemNetworkUsage: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_collect_resource_estimation",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = "If enabled, the profiler collects CPU and memory usage estimation for local actions."
    )
    abstract val collectResourceEstimation: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_collect_pressure_stall_indicators",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = "If enabled, the profiler collects the Linux PSI data."
    )
    abstract val collectPressureStallIndicators: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_collect_skyframe_counts_in_profiler",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = ("If enabled, the profiler collects SkyFunction counts in the Skyframe graph over time for"
                + " key function types, like configured targets and action executions. May have a"
                + " performance hit as this visits the ENTIRE Skyframe graph at every profiling time"
                + " unit. Do not use this flag with performance-critical measurements.")
    )
    abstract val collectSkyframeCounts: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "memory_profile",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        converter = com.google.devtools.build.lib.util.OptionsUtils.PathFragmentConverter::class,
        help = ("If set, write memory usage data to the specified file at phase ends and stable heap to"
                + " master log at end of build.")
    )
    abstract val memoryProfilePath: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "memory_profile_stable_heap_parameters",
        defaultValue = "1,0",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        converter = MemoryProfileStableHeapParameters.Converter::class,
        help = ("Tune memory profile's computation of stable heap at end of build. Should be and even"
                + " number of  integers separated by commas. In each pair the first integer is the"
                + " number of GCs to perform. The second integer in each pair is the number of"
                + " seconds to wait between GCs. Ex: 2,4,4,0 would 2 GCs with a 4sec pause, followed"
                + " by 4 GCs with zero second pause")
    )
    abstract val memoryProfileStableHeapParameters: MemoryProfileStableHeapParameters?

    @get:com.google.devtools.common.options.Option(
        name = "heap_dump_on_oom",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = ("Whether to manually output a heap dump if an OOM is thrown (including manual OOMs due to"
                + " reaching --gc_thrashing_limits). The dump will be written to"
                + " <output_base>/<invocation_id>.heapdump.hprof. This option effectively replaces"
                + " -XX:+HeapDumpOnOutOfMemoryError, which has no effect for manual OOMs.")
    )
    abstract val heapDumpOnOom: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "startup_time",
        defaultValue = "0",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        help = "The time in ms the launcher spends before sending the request to the bazel server."
    )
    abstract val startupTime: Long

    @get:com.google.devtools.common.options.Option(
        name = "extract_data_time",
        defaultValue = "0",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        help = "The time in ms spent on extracting the new bazel version."
    )
    abstract val extractDataTime: Long

    @get:com.google.devtools.common.options.Option(
        name = "command_wait_time",
        defaultValue = "0",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        help = "The time in ms a command had to wait on a busy Bazel server process."
    )
    abstract val waitTime: Long

    @get:com.google.devtools.common.options.Option(
        name = "tool_tag",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = "A tool name to attribute this Bazel invocation to."
    )
    abstract val toolTag: String?

    @get:com.google.devtools.common.options.Option(
        name = "restart_reason",
        defaultValue = "no_restart",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        help = "The reason for the server restart."
    )
    abstract var restartReason: String?

    @get:com.google.devtools.common.options.Option(
        name = "binary_path",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        help = "The absolute path of the bazel binary."
    )
    abstract var binaryPath: String?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_allow_project_files",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL, com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        help = "Enable processing of +<file> parameters."
    )
    abstract val allowProjectFiles: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_tool_command_line",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL, com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        converter = com.google.devtools.build.lib.runtime.CommandLineEvent.ToolCommandLineEvent.Converter::class,
        help = ("An extra command line to report with this invocation's command line. Useful for tools "
                + "that invoke Bazel and want the original information that the tool received to be "
                + "logged with the rest of the Bazel invocation.")
    )
    abstract val toolCommandLine: ToolCommandLineEvent?

    @get:com.google.devtools.common.options.Option(
        name = "unconditional_warning",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        allowMultiple = true,
        help = ("A warning that will unconditionally get printed with build warnings and errors. This is"
                + " useful to deprecate bazelrc files or --config definitions. If the intent is to"
                + " effectively deprecate some flag or combination of flags, this is NOT sufficient."
                + " The flag or flags should use the deprecationWarning field in the option"
                + " definition, or the bad combination should be checked for programmatically.")
    )
    abstract val deprecationWarnings: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "track_incremental_state",
        oldName = "keep_incrementality_data",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("If false, Blaze will not persist data that allows for invalidation and re-evaluation "
                + "on incremental builds in order to save memory on this build. Subsequent builds "
                + "will not have any incrementality with respect to this one. Usually you will want "
                + "to specify --batch when setting this to false.")
    )
    abstract val trackIncrementalState: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "repo_env",
        converter = com.google.devtools.build.lib.util.EnvVar.Converter::class,
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES],
        help = """
          Specifies additional environment variables to be available only for repository rules. Note that repository rules see the full environment anyway, but in this way variables can be set via command-line flags and `.bazelrc` entries. The special syntax `=NAME` can be used to explicitly unset a variable. The string `%bazel_workspace%` in a value will be replaced with the absolute path of the workspace as printed by `bazel info workspace`.
          
          """.trimIndent()
    )
    abstract val repositoryEnvironment: MutableList<EnvVar>?

    @get:com.google.devtools.common.options.Option(
        name = "incompatible_repo_env_ignores_action_env",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = """
          If true, `--action_env=NAME=VALUE` will no longer affect repository rule and module extension environments.
          
          """.trimIndent()
    )
    abstract val repoEnvIgnoresActionEnv: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_strict_repo_env",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = """
          If true, repository rules and module extensions will only inherit `PATH`, `PATHEXT`
          (on Windows), and environment variables explicitly specified by `--repo_env`.

          Note that unless `--incompatible_repo_env_ignores_action_env` is true,
          `--action_env=NAME=VALUE` will also be included.
          
          """.trimIndent()
    )
    abstract val useStrictRepoEnv: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "heuristically_drop_nodes",
        oldName = "experimental_heuristically_drop_nodes",
        oldNameWarning = false,
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("If true, Blaze will remove FileState and DirectoryListingState nodes after related File"
                + " and DirectoryListing node is done to save memory. We expect that it is less"
                + " likely that these nodes will be needed again. If so, the program will re-evaluate"
                + " them.")
    )
    abstract val heuristicallyDropNodes: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "http_timeout_scaling",
        defaultValue = "1.0",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = "Scale all timeouts related to http downloads by the given factor"
    )
    abstract val httpTimeoutScaling: Double

    @get:com.google.devtools.common.options.Option(
        name = "http_connector_attempts",
        defaultValue = "8",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = "The maximum number of attempts for http downloads."
    )
    abstract val httpConnectorAttempts: Int

    @get:com.google.devtools.common.options.Option(
        name = "http_connector_retry_max_timeout",
        defaultValue = "0s",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = ("The maximum timeout for http download retries. With a value of 0, no timeout maximum is"
                + " defined.")
    )
    abstract val httpConnectorRetryMaxTimeout: java.time.Duration?

    @get:com.google.devtools.common.options.Option(
        name = "http_max_parallel_downloads",
        defaultValue = "8",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = "The maximum number parallel http downloads."
    )
    abstract val httpMaxParallelDownloads: Int

    /** The option converter to check that the user can only specify legal profiler tasks.  */
    class ProfilerTaskConverter :
        com.google.devtools.common.options.EnumConverter<ProfilerTask?>(ProfilerTask::class.java, "profiler task")

    @get:com.google.devtools.common.options.Option(
        name = "redirect_local_instrumentation_output_writes",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = ("If true and supported, instrumentation output is redirected to be written locally on a"
                + " different machine than where bazel is running on.")
    )
    abstract val redirectLocalInstrumentationOutputWrites: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "write_command_log",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = "Whether or not to write the command.log file"
    )
    abstract val writeCommandLog: Boolean
}
