// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider

/**
 * TransitionResolver resolves the dependencies of a [ ], reporting which configurations its
 * dependencies are actually needed in according to the transitions applied to them. See [ ].
 */
class CqueryTransitionResolver(
    eventHandler: ExtendedEventHandler?,
    accessor: ConfiguredTargetAccessor,
    cqueryThreadsafeCallback: CqueryThreadsafeCallback,
    ruleClassProvider: RuleClassProvider,
    transitionCache: StarlarkTransitionCache?
) {
    /**
     * ResolvedTransition represents a single edge in the dependency graph, between some target and a
     * target it depends on, reachable via a single attribute.
     */
    @AutoValue
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    abstract class ResolvedTransition {
        /** The label of the target being depended on.  */
        abstract fun label(): Label?

        /**
         * The configuration(s) this edge results in. This is a collection because a split transition
         * may lead to a single attribute requesting a dependency in multiple configurations.
         * 
         * 
         * If a target is depended on via two attributes, separate ResolvedTransitions should be
         * used, rather than combining the two into a single ResolvedTransition with multiple options.
         * 
         * 
         * If no transition was applied to an attribute, this collection will be empty.
         */
        abstract fun options(): com.google.common.collect.ImmutableCollection<BuildOptions?>?

        /** The name of the attribute via which the dependency was requested.  */
        abstract fun attributeName(): String?

        /** The name of the transition applied to the attribute.  */
        abstract fun transitionName(): String?

        companion object {
            fun create(
                label: Label?,
                buildOptions: com.google.common.collect.ImmutableCollection<BuildOptions?>?,
                attributeName: String?,
                transitionName: String?
            ): ResolvedTransition {
                return AutoValue_CqueryTransitionResolver_ResolvedTransition(
                    label, buildOptions, attributeName, transitionName
                )
            }
        }
    }

    private val eventHandler: ExtendedEventHandler?
    private val accessor: ConfiguredTargetAccessor
    private val cqueryThreadsafeCallback: CqueryThreadsafeCallback
    private val ruleClassProvider: RuleClassProvider
    private val transitionCache: StarlarkTransitionCache?

    init {
        this.eventHandler = eventHandler
        this.accessor = accessor
        this.cqueryThreadsafeCallback = cqueryThreadsafeCallback
        this.ruleClassProvider = ruleClassProvider
        this.transitionCache = transitionCache
    }

    /**
     * Return the set of dependencies of a ConfiguredTarget, including information about the
     * configuration transitions applied to the dependencies.
     * 
     * @see ResolvedTransition for more details.
     * 
     * @param configuredTarget the configured target whose dependencies are being looked up.
     */
    @Throws(EvaluateException::class, java.lang.InterruptedException::class, IncompatibleTargetException::class)
    fun dependencies(configuredTarget: CqueryNode): com.google.common.collect.ImmutableSet<ResolvedTransition?> {
        if (configuredTarget !is RuleConfiguredTarget) {
            return com.google.common.collect.ImmutableSet.of<ResolvedTransition?>()
        }

        val target: Target? = accessor.getTarget(configuredTarget)
        val configuration: BuildConfigurationValue? =
            cqueryThreadsafeCallback.getConfiguration(configuredTarget.getConfigurationKey())

        val targetAndConfiguration: TargetAndConfiguration = TargetAndConfiguration(target, configuration)
        val attributeTransitionCollector: com.google.common.collect.HashBasedTable<DependencyKind?, Label?, ConfigurationTransition?> =
            com.google.common.collect.HashBasedTable.create<DependencyKind?, Label?, ConfigurationTransition?>()
        val state: com.google.devtools.build.lib.skyframe.DependencyResolver.State =
            DependencyResolver.State.createForCquery(
                targetAndConfiguration, attributeTransitionCollector::put
            )

        val producer: DependencyResolver = DependencyResolver(targetAndConfiguration)
        try {
            if (!producer.evaluate(
                    state,
                    ConfiguredTargetKey.fromConfiguredTarget(configuredTarget),
                    ruleClassProvider,
                    transitionCache,  /* semaphoreLocker= */
                    SemaphoreAcquirer {},
                    accessor.getLookupEnvironment(),
                    eventHandler
                )
            ) {
                throw EvaluateException("DependencyResolver.evaluate did not complete")
            }
        } catch (e: ReportedException) {
            throw EvaluateException(e.getMessage())
        } catch (e: UnreportedException) {
            throw EvaluateException(e.getMessage())
        }

        if (!state.transitiveRootCauses().isEmpty()) {
            throw EvaluateException(
                "expected empty: " + state.transitiveRootCauses().build().toList()
            )
        }

        val deps: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?> = producer.getDepValueMap()

        val resolved: com.google.common.collect.ImmutableSet.Builder<ResolvedTransition?> =
            com.google.common.collect.ImmutableSet.builder<ResolvedTransition?>()
        for (entry in deps.asMap().entrySet()) {
            val kind: DependencyKind = entry.getKey()
            if (kind is NonAttributeDependencyKind) {
                continue  // No attribute edge to report.
            }

            // There can be multiple labels under a given kind. Groups the targets by label.
            val targetsByLabel: com.google.common.collect.ImmutableListMultimap<Label?, ConfiguredTargetAndData?> =
                com.google.common.collect.Multimaps.index<Label?, ConfiguredTargetAndData?>(
                    entry.getValue(),
                    com.google.common.base.Function { prerequisite: ConfiguredTargetAndData? ->
                        prerequisite.getConfiguredTarget().getOriginalLabel()
                    })
            val dependencyName = getDependencyName(kind)
            val attributeTransitions: MutableMap<Label?, ConfigurationTransition?> =
                attributeTransitionCollector.row(kind)

            for (labelEntry in targetsByLabel.asMap().entrySet()) {
                val label: Label? = labelEntry.getKey()
                val targets: MutableCollection<ConfiguredTargetAndData> = labelEntry.getValue()

                // The most common case, so short-circuit this.
                val transitionName = usesNoTransition(configuration, targets)
                if (transitionName != null) {
                    resolved.add(
                        ResolvedTransition.Companion.create(
                            label,  /* buildOptions= */
                            com.google.common.collect.ImmutableList.of<BuildOptions?>(),
                            dependencyName,
                            transitionName
                        )
                    )
                    continue
                }

                // The rule transition does not vary across a split so using the first target is sufficient.
                val ruleTransition: ConfigurationTransition? =
                    getRuleTransition(targets.iterator().next().getConfiguredTarget())

                val toOptions: com.google.common.collect.ImmutableList<Any?> =
                    targets.stream().map<Any?>(java.util.function.Function { t: ConfiguredTargetAndData? ->
                        t.getConfiguration().getOptions()
                    }).collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())

                resolved.add(
                    ResolvedTransition.Companion.create(
                        label,
                        toOptions,
                        dependencyName,
                        getTransitionName(attributeTransitions.get(label), ruleTransition)
                    )
                )
            }
        }
        return resolved.build()
    }

    internal class EvaluateException private constructor(message: String?) : java.lang.Exception(message)

    // Keep in sync with TargetAndConfigurationProducer.computeTransition.
    private fun getRuleTransition(configuredTarget: CqueryNode): ConfigurationTransition? {
        val rule: Rule? = accessor.getTarget(configuredTarget).getAssociatedRule()
        if (rule == null) {
            return null
        }
        var transitionFactory: TransitionFactory<RuleTransitionData?> =
            rule.getRuleClassObject().getTransitionFactory()
        val trimmingTransitionFactory: TransitionFactory<RuleTransitionData?>? =
            (ruleClassProvider as ConfiguredRuleClassProvider).getTrimmingTransitionFactory()

        val isAlias: Boolean = rule.getAssociatedRule().getName().equals("alias")
        if (trimmingTransitionFactory != null && !isAlias) {
            transitionFactory =
                ComposingTransitionFactory.of(transitionFactory, trimmingTransitionFactory)
        }

        val transitionData: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RuleTransitionData.create(rule,  /* configConditions= */null, "")
        return transitionFactory.create(transitionData)
    }

    companion object {
        private fun usesNoTransition(
            fromConfiguration: BuildConfigurationValue?, targets: MutableCollection<ConfiguredTargetAndData>
        ): String? {
            val first: ConfiguredTargetAndData = targets.iterator().next()
            // Check whether the configuration changed.
            if (targets.size() == 1 && fromConfiguration == first.getConfiguration()) {
                return NoTransition.INSTANCE.getName()
            }
            // If any target has a null configuration, they all do, so it's sufficient to check the first.
            if (first.getConfiguration() == null) {
                return "(null transition)"
            }
            return null
        }

        private fun getDependencyName(kind: DependencyKind): String? {
            if (DependencyKind.isToolchain(kind)) {
                val tdk: ToolchainDependencyKind = kind as ToolchainDependencyKind
                if (tdk.isDefaultExecGroup()) {
                    return "[toolchain dependency]"
                }
                return java.lang.String.format("[toolchain dependency: %s]", tdk.getExecGroupName())
            }
            return kind.getAttribute().getName()
        }

        private fun getTransitionName(
            attributeTransition: ConfigurationTransition?,
            ruleTransition: ConfigurationTransition
        ): String? {
            if (attributeTransition == null || NoTransition.isInstance(attributeTransition)) {
                return ruleTransition.getName()
            } else if (NoTransition.isInstance(ruleTransition)) {
                return attributeTransition.getName()
            } else {
                return "(" + attributeTransition.getName() + " + " + ruleTransition.getName() + ")"
            }
        }
    }
}
