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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

/**
 * Output formatter that prints [ConfigurationTransition] information for rule configured
 * targets in the results of a cquery call.
 */
internal class TransitionsOutputFormatterCallback(
    eventHandler: ExtendedEventHandler?,
    options: CqueryOptions?,
    out: java.io.OutputStream?,
    skyframeExecutor: SkyframeExecutor,
    accessor: TargetAccessor<CqueryNode?>?,
    ruleClassProvider: RuleClassProvider?,
    labelPrinter: LabelPrinter
) : CqueryThreadsafeCallback(eventHandler, options, out, skyframeExecutor, accessor,  /* uniquifyResults= */false) {
    private val partialResultMap: HashMap<Label?, Target>
    private val ruleClassProvider: RuleClassProvider?
    private val labelPrinter: LabelPrinter
    private val transitionCache: StarlarkTransitionCache

    val name: String
        get() = "transitions"

    /**
     * @param accessor provider of query result configured targets.
     */
    init {
        this.ruleClassProvider = ruleClassProvider
        this.partialResultMap = HashMap<Label?, Target>()
        this.labelPrinter = labelPrinter
        this.transitionCache = skyframeExecutor.getSkyframeBuildView().getStarlarkTransitionCache()
    }

    @Throws(java.lang.InterruptedException::class)
    override fun processOutput(partialResult: Iterable<CqueryNode>) {
        val verbosity: Transitions = options.getTransitions()
        if (verbosity == Transitions.NONE) {
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.error(
                    "Instead of using --output=transitions, set the --transitions"
                            + " flag explicitly to 'lite' or 'full'"
                )
            )
            return
        }
        partialResult.forEach(
            java.util.function.Consumer { kct: CqueryNode? ->
                partialResultMap.put(
                    kct.getOriginalLabel(),
                    accessor.getTarget(kct)
                )
            })
        for (keyedConfiguredTarget in partialResult) {
            val target: Target = partialResultMap.get(keyedConfiguredTarget.getOriginalLabel())
            val config: BuildConfigurationValue? =
                getConfiguration(keyedConfiguredTarget.getConfigurationKey())
            addResult(
                getRuleClassTransition(keyedConfiguredTarget, target)
                        + java.lang.String.format(
                    "%s (%s)",
                    labelPrinter.toString(keyedConfiguredTarget.getOriginalLabel()),
                    CqueryThreadsafeCallback.Companion.shortId(config)
                )
            )
            val dependencies: com.google.common.collect.ImmutableSet<ResolvedTransition>
            try {
                // We don't actually use fromOptions in our implementation of
                // DependencyResolver but passing to avoid passing a null and since we have the information
                // anyway.
                dependencies =
                    CqueryTransitionResolver(
                        eventHandler, accessor, this, ruleClassProvider, transitionCache
                    )
                        .dependencies(keyedConfiguredTarget)
            } catch (e: EvaluateException) {
                eventHandler.handle(
                    com.google.devtools.build.lib.events.Event.error(
                        java.lang.String.format(
                            "Failed to evaluate %s: %s", keyedConfiguredTarget.getOriginalLabel(), e
                        )
                    )
                )
                return
            } catch (e: IncompatibleTargetChecker.IncompatibleTargetException) {
                eventHandler.handle(
                    com.google.devtools.build.lib.events.Event.warn(
                        java.lang.String.format(
                            "Skipping dependencies of incompatible target %s",
                            keyedConfiguredTarget.getOriginalLabel()
                        )
                    )
                )
                return
            }
            for (dep in dependencies) {
                addResult(
                    "  "
                        .concat(dep.attributeName())
                        .concat("#")
                        .concat(labelPrinter.toString(dep.label()))
                        .concat("#")
                        .concat(dep.transitionName())
                        .concat(" -> ")
                        .concat(
                            dep.options().stream().map<Any?>(BuildOptions::shortId).collect(Collectors.joining(", "))
                        )
                )
                if (verbosity == Transitions.LITE) {
                    continue
                }
                var diff: OptionsDiff = OptionsDiff()
                for (options in dep.options()) {
                    diff = OptionsDiff.diff(diff, config.getOptions(), options)
                }
                diff.getPrettyPrintList().forEach({ singleDiff -> addResult("    " + singleDiff) })
            }
        }
    }

    companion object {
        private fun getRuleClassTransition(ct: CqueryNode, target: Target): String? {
            val rule: Rule? = target.getAssociatedRule()
            if (rule == null) {
                return ""
            }

            val factory: TransitionFactory<RuleTransitionData?> =
                rule.getRuleClassObject().getTransitionFactory()
            return factory
                .create(
                    RuleTransitionData.create(
                        target.getAssociatedRule(),  /* configConditions= */
                        null,
                        ct.getConfigurationKey().getOptionsChecksum()
                    )
                )
                .getName()
                .concat(" -> ")
        }
    }
}
