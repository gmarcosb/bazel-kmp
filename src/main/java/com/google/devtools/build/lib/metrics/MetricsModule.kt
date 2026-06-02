// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.metrics

import com.google.devtools.build.lib.metrics.MetricsCollector
import com.google.devtools.build.lib.runtime.BlazeModule
import com.google.devtools.build.lib.runtime.CommandEnvironment
import java.util.concurrent.atomic.AtomicInteger

/**
 * A blaze module that installs metrics instrumentations and issues a [BuildMetricsEvent] at
 * the end of the build.
 */
class MetricsModule : BlazeModule() {
    /** Metrics options.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class Options : com.google.devtools.common.options.OptionsBase() {
        @com.google.devtools.common.options.Option(
            name = "experimental_record_metrics_for_all_mnemonics",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = ("Controls the output of BEP ActionSummary and BuildGraphMetrics, limiting the number of"
                    + " mnemonics in ActionData and number of entries reported in"
                    + " BuildGraphMetrics.AspectCount/RuleClassCount. By default the number of types is"
                    + " limited to the top 20, by number of executed actions for ActionData, and"
                    + " instances for RuleClass and Asepcts. Setting this option will write statistics"
                    + " for all mnemonics, rule classes and aspects.")
        )
        abstract fun getRecordMetricsForAllMnemonics(): Boolean

        @com.google.devtools.common.options.Option(
            name = "experimental_record_skyframe_metrics",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = ("Controls the output of BEP BuildGraphMetrics, including expensive "
                    + "to compute skyframe metrics about Skykeys, RuleClasses and Aspects. "
                    + "With this flag set to false BuildGraphMetrics.rule_count and aspect "
                    + "fields will not be populated in the BEP.")
        )
        abstract fun getRecordSkyframeMetrics(): Boolean
    }

    private val numAnalyses: AtomicInteger = AtomicInteger()
    private val numBuilds: AtomicInteger = AtomicInteger()

    override fun getCommonCommandOptions(): Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
        return com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
            com.google.devtools.build.lib.metrics.MetricsModule.Options::class.java
        )
    }

    /**
     * Informs the Blaze runtime that this module will post the BuildMetricsEvent and the runtime does
     * not need to supply its own such module.
     */
    override fun postsBuildMetricsEvent(): Boolean {
        return true
    }

    override fun beforeCommand(env: CommandEnvironment?) {
        MetricsCollector.Companion.installInEnv(env, numAnalyses, numBuilds)
    }
}
