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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.util.RamResourceConverter
import com.google.devtools.build.lib.util.ResourceConverter

/** Options related to worker processes.  */
@com.google.devtools.common.options.OptionsClass
abstract class WorkerOptions : com.google.devtools.common.options.OptionsBase() {
    /**
     * Defines a resource converter for named values in the form [name=]value, where the value is
     * [ResourceConverter.FLAG_SYNTAX]. If no name is provided (used when setting a default),
     * the empty string is used as the key. The default value for unspecified mnemonics is defined in
     * [WorkerPoolImpl.createPool]. "auto" currently returns the default.
     */
    class MultiResourceConverter :
        com.google.devtools.common.options.Converter.Contextless<MutableMap.MutableEntry<String?, Int?>?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String?): MutableMap.MutableEntry<String?, Int?> {
            // TODO(steinman): Make auto value return a reasonable multiplier of host capacity.
            if (input == null || input == "null" || input == "auto") {
                return com.google.common.collect.Maps.immutableEntry<String?, Int?>(null, null)
            }
            val pos: Int = input.indexOf('='.code)
            if (pos < 0) {
                return com.google.common.collect.Maps.immutableEntry<String?, Int?>(
                    "", valueConverter.convert(input,  /* conversionContext= */null)
                )
            }
            val name: String = input.substring(0, pos)
            val value: String = input.substring(pos + 1)
            if (value == "auto") {
                return com.google.common.collect.Maps.immutableEntry<String?, Int?>(name, null)
            }

            return com.google.common.collect.Maps.immutableEntry<String?, Int?>(
                name, valueConverter.convert(value,  /* conversionContext= */null)
            )
        }

        val typeDescription: String
            get() = "[name=]value, where value is " + ResourceConverter.Companion.FLAG_SYNTAX

        companion object {
            val valueConverter: com.google.devtools.build.lib.util.ResourceConverter.IntegerConverter =
                com.google.devtools.build.lib.util.ResourceConverter.IntegerConverter(
                    java.util.function.Supplier { 0 },
                    0,
                    java.lang.Integer.MAX_VALUE
                )
        }
    }

    @get:com.google.devtools.common.options.Option(
        name = "worker_max_instances",
        converter = MultiResourceConverter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        help = ("How many instances of each kind of persistent worker may be "
                + "launched if you use the 'worker' strategy. May be specified as [name=value] to "
                + "give a different value per mnemonic. The limit is based on worker keys, which are "
                + "differentiated based on mnemonic, but also on startup flags and environment, so "
                + "there can in some cases be more workers per mnemonic than this flag specifies. "
                + "Takes "
                + ResourceConverter.Companion.FLAG_SYNTAX
                + ". 'auto' calculates a reasonable default based on machine capacity. "
                + "\"=value\" sets a default for unspecified mnemonics."),
        allowMultiple = true
    )
    abstract var workerMaxInstances: MutableList<MutableMap.MutableEntry<String?, Int?>?>?

    @get:com.google.devtools.common.options.Option(
        name = "worker_max_multiplex_instances",
        oldName = "experimental_worker_max_multiplex_instances",
        converter = MultiResourceConverter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        help = ("How many WorkRequests a multiplex worker process may receive in parallel if you use the"
                + " 'worker' strategy with --worker_multiplex. May be specified as "
                + "[name=value] to give a different value per mnemonic. The limit is based on worker "
                + "keys, which are differentiated based on mnemonic, but also on startup flags and "
                + "environment, so there can in some cases be more workers per mnemonic than this "
                + "flag specifies. Takes "
                + ResourceConverter.Companion.FLAG_SYNTAX
                + ". 'auto' calculates a reasonable default based on machine capacity. "
                + "\"=value\" sets a default for unspecified mnemonics."),
        allowMultiple = true
    )
    abstract var workerMaxMultiplexInstances: MutableList<MutableMap.MutableEntry<String?, Int?>?>?

    @get:com.google.devtools.common.options.Option(
        name = "worker_quit_after_build",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        help = "If enabled, all workers quit after a build is done."
    )
    abstract val workerQuitAfterBuild: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "worker_verbose",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = "If enabled, prints verbose messages when workers are started, shutdown, ..."
    )
    abstract var workerVerbose: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "worker_extra_flag",
        converter = com.google.devtools.common.options.Converters.AssignmentConverter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        help = ("Extra command-flags that will be passed to worker processes in addition to "
                + "--persistent_worker, keyed by mnemonic (e.g. --worker_extra_flag=Javac=--debug."),
        allowMultiple = true
    )
    abstract var workerExtraFlags: MutableList<MutableMap.MutableEntry<String?, String?>?>?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "worker_sandboxing",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("If enabled, singleplex workers will run in a sandboxed environment. Singleplex workers"
                + " are always sandboxed when running under the dynamic execution strategy,"
                + " irrespective of this flag.")
    )
    abstract var workerSandboxing: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "worker_multiplex",
        oldName = "experimental_worker_multiplex",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        help = "If enabled, workers will use multiplexing if they support it. "
    )
    abstract var workerMultiplex: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_worker_cancellation",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = "If enabled, Bazel may send cancellation requests to workers that support them."
    )
    abstract var workerCancellation: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_worker_multiplex_sandboxing",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("If enabled, multiplex workers with a 'supports-multiplex-sandboxing' execution"
                + " requirement will run in a sandboxed environment, using a separate sandbox"
                + " directory per work request. Multiplex workers with the execution requirement are"
                + " always sandboxed when running under the dynamic execution strategy,"
                + " irrespective of this flag.")
    )
    abstract var multiplexSandboxing: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_worker_strict_flagfiles",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("If enabled, actions arguments for workers that do not follow the worker specification"
                + " will cause an error. Worker arguments must have exactly one @flagfile argument"
                + " as the last of its list of arguments.")
    )
    abstract var strictFlagfiles: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_total_worker_memory_limit_mb",
        converter = RamResourceConverter::class,
        defaultValue = "0",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        help = ("If this limit is greater than zero idle workers might be killed if the total memory"
                + " usage of all  workers exceed the limit.")
    )
    abstract var totalWorkerMemoryLimitMb: Int

    @get:com.google.devtools.common.options.Option(
        name = "experimental_worker_use_cgroups_on_linux",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("On linux, run all workers in its own cgroup (without any limits set) and use the"
                + " cgroup's own resource accounting for memory measurements. This is overridden by"
                + " --experimental_worker_sandbox_hardening for sandboxed workers.")
    )
    abstract val useCgroupsOnLinux: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_worker_sandbox_hardening",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("If enabled, workers are run in a hardened sandbox, if the implementation allows it. If"
                + " hardening is enabled then tmp directories are distinct for different workers.")
    )
    abstract val sandboxHardening: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_shrink_worker_pool",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        help = ("If enabled, could shrink worker pool if worker memory pressure is high. This flag works"
                + " only when flag experimental_total_worker_memory_limit_mb is enabled.")
    )
    abstract var shrinkWorkerPool: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_worker_metrics_poll_interval",
        converter = com.google.devtools.common.options.Converters.DurationConverter::class,
        defaultValue = "5s",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        help = ("The interval between collecting worker metrics and possibly attempting evictions. "
                + "Cannot effectively be less than 1s for performance reasons.")
    )
    abstract val workerMetricsPollInterval: java.time.Duration?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_worker_memory_limit_mb",
        converter = RamResourceConverter::class,
        defaultValue = "0",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        help = ("If this limit is greater than zero, workers might be killed if the memory usage of the "
                + "worker exceeds the limit. If not used together with dynamic execution and "
                + "`--experimental_dynamic_ignore_local_signals=9`, this may crash your build.")
    )
    abstract var workerMemoryLimitMb: Int

    @get:com.google.devtools.common.options.Option(
        name = "experimental_worker_sandbox_inmemory_tracking",
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("A worker key mnemonic for which the contents of the sandbox directory are tracked in"
                + " memory. This may improve build performance at the cost of additional memory"
                + " usage. Only affects sandboxed workers. May be specified multiple times for"
                + " different mnemonics.")
    )
    abstract val workerSandboxInMemoryTracking: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_worker_allowlist",
        converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionSetConverter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        help = "If non-empty, only allow using persistent workers with the given worker key mnemonic."
    )
    abstract val allowlist: com.google.common.collect.ImmutableList<String?>?

    abstract fun setAllowlist(value: com.google.common.collect.ImmutableList<String?>?)

    companion object {
        @kotlin.jvm.JvmField
        val DEFAULTS: WorkerOptions? =
            com.google.devtools.common.options.Options.Companion.getDefaults<WorkerOptions?>(WorkerOptions::class.java)
    }
}
