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
package com.google.devtools.build.lib.query2.aquery

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/** Output callback for aquery, prints a human readable summary.  */
internal class ActionGraphSummaryOutputFormatterCallback(
    eventHandler: ExtendedEventHandler?,
    options: AqueryOptions?,
    out: java.io.OutputStream?,
    accessor: TargetAccessor<ConfiguredTargetValue?>?,
    actionFilters: AqueryActionFilter?
) : AqueryThreadsafeCallback(eventHandler, options, out, accessor) {
    private val actionFilters: AqueryActionFilter?
    private val mnemonicToCount: MutableMap<String?, Int?> = HashMap<String?, Int?>()
    private val configurationToCount: MutableMap<String?, Int?> = HashMap<String?, Int?>()
    private val execPlatformToCount: MutableMap<String?, Int?> = HashMap<String?, Int?>()
    private val aspectToCount: MutableMap<String?, Int?> = HashMap<String?, Int?>()

    init {
        this.actionFilters = actionFilters
    }

    val name: String
        get() = "summary"

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun processOutput(partialResult: Iterable<ConfiguredTargetValue>) {
        // Enabling includeParamFiles should enable includeCommandline by default.
        options.setIncludeCommandline(
            options.getIncludeCommandline() || options.getIncludeParamFiles()
        )

        for (configuredTargetValue in partialResult) {
            if (configuredTargetValue !is RuleConfiguredTargetValue) {
                // We have to include non-rule values in the graph to visit their dependencies, but they
                // don't have any actions to print out.
                continue
            }
            for (action in (configuredTargetValue as RuleConfiguredTargetValue).getActions()) {
                processAction(action)
            }
            if (options.getUseAspects()) {
                for (aspectValue in accessor.getAspectValues(configuredTargetValue)) {
                    for (action in aspectValue.getActions()) {
                        processAction(action)
                    }
                }
            }
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun processAction(action: ActionAnalysisMetadata) {
        if (!AqueryUtils.matchesAqueryFilters(
                action, actionFilters, options.getIncludePrunedInputs()
            )
        ) {
            return
        }

        mnemonicToCount.merge(action.getMnemonic(), 1) { a: Int?, b: Int? -> java.lang.Integer.sum(a, b) }
        val actionOwner: ActionOwner? = action.getOwner()
        if (actionOwner != null) {
            val configuration: BuildEvent = actionOwner.getBuildConfigurationEvent()
            val configProto: BuildEventStreamProtos.Configuration =
                configuration.asStreamProto( /*context=*/null).getConfiguration()
            configurationToCount.merge(configProto.getMnemonic(), 1) { a: Int?, b: Int? -> java.lang.Integer.sum(a, b) }

            if (actionOwner.getExecutionPlatform() != null) {
                execPlatformToCount.merge(
                    actionOwner.getExecutionPlatform().label().toString(), 1
                ) { a: Int?, b: Int? -> java.lang.Integer.sum(a, b) }
            }

            // In the case of aspect-on-aspect, AspectDescriptors are listed in
            // topological order of the dependency graph.
            // e.g. [A -> B] would imply that aspect A is applied on top of aspect B.
            val aspectDescriptors: com.google.common.collect.ImmutableList<AspectDescriptor?> =
                actionOwner.getAspectDescriptors().reverse()
            if (!aspectDescriptors.isEmpty()) {
                aspectDescriptors.forEach(
                    java.util.function.Consumer { aspectDescriptor: AspectDescriptor? ->
                        aspectToCount.merge(
                            aspectDescriptor.getAspectClass().getName(),
                            1
                        ) { a: Int?, b: Int? -> java.lang.Integer.sum(a, b) }
                    })
            }
        }
    }

    @Throws(java.lang.InterruptedException::class, IOException::class)
    override fun close(failFast: Boolean) {
        if (failFast) {
            return
        }

        val totalActions: Int = mnemonicToCount.values.stream().mapToInt { v: Int? -> v }.sum()
        if (totalActions == 0) {
            printStream.println("No actions matched.")
        } else {
            printStream.println(totalActions.toString() + " total action" + (if (totalActions == 1) "" else "s") + ".")
        }

        printSummary(mnemonicToCount, "Mnemonics:")
        printSummary(configurationToCount, "Configurations:")
        printSummary(execPlatformToCount, "Execution Platforms:")
        printSummary(aspectToCount, "Aspects:")
    }

    private fun printSummary(actionsCount: MutableMap<String?, Int?>, s: String?) {
        if (!actionsCount.isEmpty()) {
            printStream.println()
            printStream.println(s)
            actionsCount.entries.stream()
                .sorted(java.util.Comparator.comparingInt<MutableMap.MutableEntry<String?, Int?>?>(ToIntFunction { obj: MutableMap.MutableEntry<String?, Int?>? -> obj!!.value }))
                .forEach { entry: MutableMap.MutableEntry<String?, Int?>? -> printStream.println("  " + entry!!.key + ": " + entry.value) }
        }
    }
}
