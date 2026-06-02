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

import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction.name
import com.google.devtools.build.lib.runtime.GcThrashingDetector
import java.util.OptionalInt

/** Options for responding to memory pressure.  */
@com.google.devtools.common.options.OptionsClass
abstract class MemoryPressureOptions : com.google.devtools.common.options.OptionsBase() {
    @get:com.google.devtools.common.options.Option(
        name = "skyframe_high_water_mark_threshold",
        defaultValue = "85",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        help = ("Flag for advanced configuration of Bazel's internal Skyframe engine. If Bazel detects"
                + " its retained heap percentage usage is at least this threshold, it will drop"
                + " unnecessary temporary Skyframe state. Tweaking this may let you mitigate wall"
                + " time impact of GC thrashing, when the GC thrashing is (i) caused by the memory"
                + " usage of this temporary state and (ii) more costly than reconstituting the state"
                + " when it is needed.")
    )
    abstract var skyframeHighWaterMarkMemoryThreshold: Int

    @get:com.google.devtools.common.options.Option(
        name = "skyframe_high_water_mark_minor_gc_drops_per_invocation",
        defaultValue = "10",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        converter = NonNegativeIntegerConverter::class,
        help = ("Flag for advanced configuration of Bazel's internal Skyframe engine. If Bazel detects"
                + " its retained heap percentage usage exceeds the threshold set by"
                + " --skyframe_high_water_mark_threshold, when a minor GC event occurs, it will drop"
                + " unnecessary temporary Skyframe state, up to this many times per invocation."
                + " Defaults to 10. Zero means that minor GC events will never trigger drops. If the"
                + " limit is reached, Skyframe state will no longer be dropped when a minor GC event"
                + " occurs and that retained heap percentage threshold is exceeded.")
    )
    abstract var skyframeHighWaterMarkMinorGcDropsPerInvocation: Int

    @get:com.google.devtools.common.options.Option(
        name = "skyframe_high_water_mark_full_gc_drops_per_invocation",
        defaultValue = "10",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        converter = NonNegativeIntegerConverter::class,
        help = ("Flag for advanced configuration of Bazel's internal Skyframe engine. If Bazel detects"
                + " its retained heap percentage usage exceeds the threshold set by"
                + " --skyframe_high_water_mark_threshold, when a full GC event occurs, it will drop"
                + " unnecessary temporary Skyframe state, up to this many times per invocation."
                + " Defaults to 10. Zero means that full GC events will never trigger drops. If the"
                + " limit is reached, Skyframe state will no longer be dropped when a full GC event"
                + " occurs and that retained heap percentage threshold is exceeded.")
    )
    abstract var skyframeHighWaterMarkFullGcDropsPerInvocation: Int

    @get:com.google.devtools.common.options.Option(
        name = "gc_thrashing_limits",
        defaultValue = "1s:2,20s:3,1m:5",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        converter = GcThrashingLimitsConverter::class,
        help = ("Limits which, if reached, cause GcThrashingDetector to crash Bazel with an OOM. Each"
                + " limit is specified as <period>:<count> where period is a duration and count is a"
                + " positive integer. If more than --gc_thrashing_threshold percent of tenured space"
                + " (old gen heap) remains occupied after <count> consecutive full GCs within"
                + " <period>, an OOM is triggered. Multiple limits can be specified separated by"
                + " commas.")
    )
    abstract val gcThrashingLimits: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.runtime.GcThrashingDetector.Limit?>?

    @get:com.google.devtools.common.options.Option(
        name = "gc_thrashing_threshold",
        oldName = "experimental_oom_more_eagerly_threshold",
        oldNameWarning = false,
        defaultValue = "100",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        converter = com.google.devtools.common.options.Converters.PercentageConverter::class,
        help = ("The percent of tenured space occupied (0-100) above which GcThrashingDetector considers"
                + " memory pressure events against its limits (--gc_thrashing_limits). If set to 100,"
                + " GcThrashingDetector is disabled.")
    )
    abstract val gcThrashingThreshold: Int

    @get:com.google.devtools.common.options.Option(
        name = "gc_churning_threshold",
        defaultValue = "100",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        converter = com.google.devtools.common.options.Converters.PercentageConverter::class,
        help = ("At any point after an invocation has been running for at least one minute, if Blaze has"
                + " spent at least this percentage of the invocation's wall time doing full GCs,"
                + " Blaze will give up and fail with an OOM. A value of 100 effectively means to"
                + " never give up for this reason.")
    )
    abstract val gcChurningThreshold: Int

    @get:com.google.devtools.common.options.Option(
        name = "gc_churning_threshold_if_multiple_top_level_targets",
        defaultValue = com.google.devtools.common.options.Converters.OptionalPercentageConverter.UNSET,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        converter = com.google.devtools.common.options.Converters.OptionalPercentageConverter::class,
        help = ("If set to a value in [0, 100] and this is a command that takes top-level targets (e.g."
                + " build but not query) and there are multiple such top-level targets, overrides"
                + " --gc_churning_threshold. Useful to configure more aggressive OOMing behavior"
                + " (i.e. a lower value than --gc_churning_threshold) when they are multiple"
                + " top-level targets so that the invoker of Bazel can split and retry while still"
                + " having less aggressive behavior when there is a single top-level target.")
    )
    abstract val gcChurningThresholdIfMultipleTopLevelTargets: OptionalInt?

    @get:com.google.devtools.common.options.Option(
        name = "jvm_heap_histogram_internal_object_pattern",
        converter = com.google.devtools.common.options.Converters.RegexPatternConverter::class,
        defaultValue = "jdk\\.internal\\.vm\\.Filler.+",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("Regex for overriding the matching logic for JDK21+ JVM heap memory"
                + " collection. We are relying on volatile internal G1 GC implemenation"
                + " details to get a clean memory metric, this option allows us to adapt"
                + " to changes in that internal implementation without having to wait"
                + " for a binary release.  Passed to JDK Matcher.find()")
    )
    abstract val jvmHeapHistogramInternalObjectPattern: com.google.devtools.common.options.RegexPatternOption?

    internal class NonNegativeIntegerConverter :
        com.google.devtools.common.options.Converters.RangeConverter(0, java.lang.Integer.MAX_VALUE)

    internal class GcThrashingLimitsConverter

        :
        com.google.devtools.common.options.Converter.Contextless<com.google.common.collect.ImmutableList<com.google.devtools.build.lib.runtime.GcThrashingDetector.Limit?>?>() {
        private val commaListConverter: com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter =
            com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter()
        private val durationConverter: com.google.devtools.common.options.Converters.DurationConverter =
            com.google.devtools.common.options.Converters.DurationConverter()
        private val positiveIntConverter: com.google.devtools.common.options.Converters.RangeConverter =
            com.google.devtools.common.options.Converters.RangeConverter(1, java.lang.Integer.MAX_VALUE)

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.runtime.GcThrashingDetector.Limit?> {
            val result: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.runtime.GcThrashingDetector.Limit?> =
                com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.runtime.GcThrashingDetector.Limit?>()
            for (part in commaListConverter.convert(input)) {
                val colonIndex: Int = part.indexOf(':'.code)
                if (colonIndex == -1) {
                    throw com.google.devtools.common.options.OptionsParsingException("Expected <period>:<count>, got " + part)
                }
                val period: java.time.Duration? = durationConverter.convert(part.substring(0, colonIndex))
                val count: Int = positiveIntConverter.convert(part.substring(colonIndex + 1))
                result.add(com.google.devtools.build.lib.runtime.GcThrashingDetector.Limit.Companion.of(period, count))
            }
            return result.build()
        }

        val typeDescription: String
            get() = "comma separated pairs of <period>:<count>"
    }
}
