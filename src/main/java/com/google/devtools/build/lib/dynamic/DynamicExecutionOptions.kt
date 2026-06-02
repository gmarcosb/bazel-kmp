// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.dynamic

import com.google.common.base.Splitter
import com.google.common.collect.ImmutableSet
import com.google.common.primitives.Ints
import com.google.devtools.common.options.*

/** Options related to dynamic spawn execution.  */
@OptionsClass
abstract class DynamicExecutionOptions : OptionsBase() {
    @get:Deprecated("")
    @get:Option(
        name = "experimental_spawn_scheduler",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        metadataTags = [OptionMetadataTag.DEPRECATED],
        defaultValue = "null",
        help = ("Enable dynamic execution by running actions locally and remotely in parallel. Bazel "
                + "spawns each action locally and remotely and picks the one that completes first. "
                + "If an action supports workers, the local action will be run in the persistent "
                + "worker mode. To enable dynamic execution for an individual action mnemonic, use "
                + "the `--internal_spawn_scheduler` and `--strategy=<mnemonic>=dynamic` flags "
                + "instead."),
        expansion = ["--internal_spawn_scheduler", "--spawn_strategy=dynamic"
        ],
        deprecationWarning = ("--experimental_spawn_scheduler is deprecated. Using dynamic execution for everything is"
                + " rarely a good idea (see https://bazel.build/remote/dynamic). If you really want"
                + " to enable dynamic execution globally, pass `--internal_spawn_scheduler "
                + "--spawn_strategy=dynamic`.")
    )
    abstract var experimentalSpawnScheduler: Void?

    @kotlin.jvm.JvmField
    @get:Option(
        name = "internal_spawn_scheduler",
        documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        defaultValue = "true",
        help = ("Placeholder option so that we can tell in Blaze whether the spawn scheduler was "
                + "enabled.")
    )
    abstract var internalSpawnScheduler: Boolean

    @get:Option(
        name = "dynamic_local_strategy",
        converter = Converters.StringToStringListConverter::class,
        documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        defaultValue = "null",
        allowMultiple = true,
        help = ("The local strategies, in order, to use for the given mnemonic - the first applicable "
                + "strategy is used. For example, `worker,sandboxed` runs actions that support "
                + "persistent workers using the worker strategy, and all others using the sandboxed "
                + "strategy. If no mnemonic is given, the list of strategies is used as the "
                + "fallback for all mnemonics. The default fallback list is `worker,sandboxed`, or"
                + "`worker,sandboxed,standalone` if `experimental_local_lockfree_output` is set. "
                + "Takes [mnemonic=]local_strategy[,local_strategy,...]")
    )
    abstract var dynamicLocalStrategy: MutableList<MutableMap.MutableEntry<String?, MutableList<String?>?>?>?

    @get:Option(
        name = "dynamic_remote_strategy",
        converter = Converters.StringToStringListConverter::class,
        documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        defaultValue = "null",
        allowMultiple = true,
        help = ("The remote strategies, in order, to use for the given mnemonic - the first applicable "
                + "strategy is used. If no mnemonic is given, the list of strategies is used as the "
                + "fallback for all mnemonics. The default fallback list is `remote`, so this flag "
                + "usually does not need to be set explicitly. "
                + "Takes [mnemonic=]remote_strategy[,remote_strategy,...]")
    )
    abstract var dynamicRemoteStrategy: MutableList<MutableMap.MutableEntry<String?, MutableList<String?>?>?>?

    @kotlin.jvm.JvmField
    @get:Option(
        name = "dynamic_local_execution_delay",
        oldName = "experimental_local_execution_delay",
        oldNameWarning = false,
        documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        defaultValue = "1000",
        help = ("How many milliseconds should local execution be delayed, if remote execution was faster"
                + " during a build at least once?")
    )
    abstract var localExecutionDelay: Int

    @get:Option(
        name = "debug_spawn_scheduler",
        oldName = "experimental_debug_spawn_scheduler",
        oldNameWarning = false,
        documentationCategory = OptionDocumentationCategory.LOGGING,
        effectTags = [OptionEffectTag.UNKNOWN],
        defaultValue = "false"
    )
    abstract var debugSpawnScheduler: Boolean

    @get:Option(
        name = "experimental_dynamic_slow_remote_time",
        documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        defaultValue = "0",
        help = ("If >0, the time a dynamically run action must run remote-only before we"
                + " prioritize its local execution to avoid remote timeouts."
                + " This may hide some problems on the remote execution system. Do not turn this on"
                + " without monitoring of remote execution issues.")
    )
    abstract var slowRemoteTime: Duration?

    @get:Option(
        name = "experimental_dynamic_local_load_factor",
        documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        defaultValue = "0",
        help = ("Controls how much load from dynamic execution to put on the local machine."
                + " This flag adjusts how many actions in dynamic execution we will schedule"
                + " concurrently. It is based on the number of CPUs Blaze thinks is available,"
                + " which can be controlled with the --local_resources=cpu= flag."
                + "\nIf this flag is 0, all actions are scheduled locally immediately. If > 0,"
                + " the amount of actions scheduled locally is limited by the number of CPUs"
                + " available. If < 1, the load factor is used to reduce the number of locally"
                + " scheduled actions when the number of actions waiting to schedule is high."
                + " This lessens the load on the local machine in the clean build case, where"
                + " the local machine does not contribute much.")
    )
    abstract var localLoadFactor: Double

    @kotlin.jvm.JvmField
    @get:Option(
        name = "experimental_dynamic_exclude_tools",
        documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        defaultValue = "true",
        help = ("When set, targets that are build \"for tool\" are not subject to dynamic execution. Such"
                + " targets are extremely unlikely to be built incrementally and thus not worth"
                + " spending local cycles on.")
    )
    abstract var excludeTools: Boolean

    @get:Option(
        name = "experimental_dynamic_ignore_local_signals",
        documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        converter = SignalListConverter::class,
        effectTags = [OptionEffectTag.EXECUTION],
        defaultValue = "null",
        help = ("Takes a list of OS signal numbers. If a local branch of dynamic execution"
                + " gets killed with any of these signals, the remote branch will be allowed to"
                + " finish instead. For persistent workers, this only affects signals that kill"
                + " the worker process.")
    )
    abstract var ignoreLocalSignals: MutableSet<Int?>?

    /** Converts comma-separated lists of signal numbers into a set of signal numbers.  */
    class SignalListConverter : Converter<MutableSet<Int?>?> {
        @Throws(OptionsParsingException::class)
        override fun convert(input: String?, conversionContext: Any?): ImmutableSet<Int?> {
            if (input == null || "null" == input) {
                return ImmutableSet.of<Int?>()
            }
            val parts = Splitter.on(",").split(input)
            if (!parts.iterator().hasNext()) {
                throw OptionsParsingException("Requires at least one signal number")
            }
            val signals = ImmutableSet.Builder<Int?>()
            for (p in parts) {
                val trimmed: String = p.trim()
                val signalNum = Ints.tryParse(trimmed)
                if (signalNum != null && signalNum > 0) {
                    signals.add(signalNum)
                } else {
                    throw OptionsParsingException(java.lang.String.format("No such signal %s", trimmed))
                }
            }
            return signals.build()
        }

        override fun getTypeDescription(): String {
            return "a comma-separated list of signal numbers"
        }
    }
}
