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
package com.google.devtools.build.lib.analysis.constraints

import com.google.devtools.build.lib.analysis.FilesToRunProvider

/**
 * Helpers for creating configured targets that are incompatible.
 * 
 * 
 * A target is considered incompatible if any of the following applies:
 * 
 * 
 *  1. The target's `target_compatible_with` attribute specifies a constraint that is
 * not present in the target platform. The target is said to be "directly incompatible".
 *  1. One or more of the target's dependencies is incompatible. The target is said to be
 * "indirectly incompatible."
 * 
 * 
 * The intent of these helpers is that they get called as early in the analysis phase as possible.
 * That's why there are two helpers instead of just one. The first helper determines direct
 * incompatibility very early in the analysis phase. If a target is not directly incompatible, the
 * dependencies need to be analysed and then we can check for indirect incompatibility. Doing these
 * checks as early as possible allows us to skip analysing unused dependencies and ignore unused
 * toolchains.
 * 
 * 
 * See https://bazel.build/docs/platforms#skipping-incompatible-targets for more information on
 * incompatible target skipping.
 */
object IncompatibleTargetChecker {
    /** Rules where it doesn't make sense to check for platform compatibility.  */
    private val NO_COMPATIBILITY_CHECK_RULES: com.google.common.collect.ImmutableList<String?> =
        com.google.common.collect.ImmutableList.of<String?>("toolchain", "config_setting", "label_flag")

    /**
     * Creates an incompatible target if it is "indirectly incompatible".
     * 
     * 
     * In other words, this function checks if a target is incompatible because of one of its
     * dependencies. If a dependency is incompatible, then this target is also incompatible.
     * 
     * 
     * This function returns an `Optional` of a [RuleConfiguredTargetValue]. This
     * provides two states of return values:
     * 
     * 
     *  * `Optional.empty()`: The target is not indirectly incompatible. Analysis can
     * continue.
     *  * `!Optional.empty()`: The target is indirectly incompatible. Analysis should not
     * continue.
     * 
     */
    fun createIndirectlyIncompatibleTarget(
        targetAndConfiguration: TargetAndConfiguration,
        configuredTargetKey: ConfiguredTargetKey?,
        depValueMap: OrderedSetMultimap<com.google.devtools.build.lib.analysis.DependencyKind?, ConfiguredTargetAndData?>,
        configConditions: ConfigConditions,
        platformInfo: com.google.devtools.build.lib.analysis.platform.PlatformInfo?,
        transitiveState: TransitiveDependencyState
    ): java.util.Optional<RuleConfiguredTargetValue?> {
        val target: com.google.devtools.build.lib.packages.Target = targetAndConfiguration.getTarget()
        val rule: com.google.devtools.build.lib.packages.Rule? = target.getAssociatedRule()

        if (rule == null || NO_COMPATIBILITY_CHECK_RULES.contains(rule.getRuleClass())) {
            return java.util.Optional.empty<RuleConfiguredTargetValue?>()
        }

        // Find all the incompatible dependencies.
        val incompatibleDeps: com.google.common.collect.ImmutableList<ConfiguredTarget?> =
            depValueMap.values().stream()
                .map<ConfiguredTarget?> { obj: ConfiguredTargetAndData? -> obj.getConfiguredTarget() }
                .filter { dep: ConfiguredTarget? ->
                    RuleContextConstraintSemantics.checkForIncompatibility(dep).isIncompatible()
                }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<ConfiguredTarget?>())
        if (incompatibleDeps.isEmpty()) {
            return java.util.Optional.empty<RuleConfiguredTargetValue?>()
        }

        val configuration: BuildConfigurationValue? = targetAndConfiguration.getConfiguration()
        val platformLabel: com.google.devtools.build.lib.cmdline.Label? =
            if (platformInfo != null) platformInfo.label() else null
        return java.util.Optional.of<RuleConfiguredTargetValue?>(
            createIncompatibleRuleConfiguredTarget(
                configuredTargetKey,
                configuration,
                configConditions,
                IncompatiblePlatformProvider.Companion.incompatibleDueToTargets(platformLabel, incompatibleDeps),
                rule.getRuleClassObject().getRuleClassId(),
                transitiveState
            )
        )
    }

    /** Creates an incompatible target.  */
    private fun createIncompatibleRuleConfiguredTarget(
        configuredTargetKey: ConfiguredTargetKey?,
        configuration: BuildConfigurationValue,
        configConditions: ConfigConditions,
        incompatiblePlatformProvider: IncompatiblePlatformProvider?,
        ruleClassId: RuleClassId?,
        transitiveState: TransitiveDependencyState
    ): RuleConfiguredTargetValue {
        // Create dummy instances of the necessary data for a configured target. None of this data will
        // actually be used because actions associated with incompatible targets must not be evaluated.
        val providerBuilder: TransitiveInfoProviderMapBuilder =
            TransitiveInfoProviderMapBuilder()
                .put(incompatiblePlatformProvider)
                .add(RunfilesProvider.simple(Runfiles.EMPTY))
                .add(FileProvider.Companion.EMPTY)
                .add(FilesToRunProvider.EMPTY)
                .add(SupportedEnvironments.Companion.EMPTY)
        if (configuration.hasFragment(TestConfiguration::class.java)) {
            // Create a dummy TestProvider instance so that other parts of the code base stay happy. Even
            // though this test will never execute, some code still expects the provider.
            val testParams: TestProvider.TestParams? = TestActionBuilder.createEmptyTestParams()
            providerBuilder.put(TestProvider::class.java, TestProvider(testParams))
        }

        val configuredTarget: RuleConfiguredTarget =
            RuleConfiguredTarget(
                configuredTargetKey,
                convertVisibility(),  /* isCreatedInSymbolicMacro= */
                false,
                providerBuilder.build(),
                configConditions.asProviders(),
                ruleClassId
            )
        return RuleConfiguredTargetValue(configuredTarget, transitiveState.transitivePackages())
    }

    /**
     * Generates visibility for an incompatible target.
     * 
     * 
     * The intent is for this function is to match ConfiguredTargetFactory.convertVisibility().
     * Since visibility is currently validated after incompatibility is evaluated, however, it doesn't
     * matter what visibility we set here. To keep it simple, we pretend that all incompatible targets
     * are public.
     * 
     * 
     * TODO(#16044): Set up properly validated visibility here.
     */
    private fun convertVisibility(): NestedSet<PackageGroupContents?>? {
        return VisibilityProvider.PUBLIC_VISIBILITY
    }

    /**
     * Creates an incompatible configured target if it is "directly incompatible".
     * 
     * 
     * In other words, this state machine checks if a target is incompatible because of its
     * "target_compatible_with" attribute.
     * 
     * 
     * Outputs an `Optional` [RuleConfiguredTargetValue] as follows.
     * 
     * 
     *  * `Optional.empty()`: The target is not directly incompatible. Analysis can continue.
     *  * `!Optional.empty()`: The target is directly incompatible. Analysis should not
     * continue.
     * 
     */
    class IncompatibleTargetProducer(
        targetAndConfiguration: TargetAndConfiguration,
        configuredTargetKey: ConfiguredTargetKey?,
        configConditions: ConfigConditions,
        platformInfo: com.google.devtools.build.lib.analysis.platform.PlatformInfo?,
        transitiveState: TransitiveDependencyState,
        sink: ResultSink,
        runAfter: StateMachine?
    ) : StateMachine, java.util.function.Consumer<SkyValue?> {
        private val targetAndConfiguration: TargetAndConfiguration
        private val configuredTargetKey: ConfiguredTargetKey?
        private val configConditions: ConfigConditions

        // Non-null when the target has an associated rule and does not opt out of toolchain resolution.
        private val platformInfo: com.google.devtools.build.lib.analysis.platform.PlatformInfo?
        private val transitiveState: TransitiveDependencyState

        private val sink: ResultSink

        private val runAfter: StateMachine?

        private val allConstraintValuesBuilder: com.google.common.collect.ImmutableList.Builder<ConstraintValueInfo?> =
            com.google.common.collect.ImmutableList.Builder<ConstraintValueInfo?>()
        private val invalidConstraintValuesBuilder: com.google.common.collect.ImmutableList.Builder<ConstraintValueInfo?> =
            com.google.common.collect.ImmutableList.Builder<ConstraintValueInfo?>()

        /** Sink for the output of this state machine.  */
        interface ResultSink {
            fun acceptIncompatibleTarget(incompatibleTarget: java.util.Optional<RuleConfiguredTargetValue?>?)

            fun acceptValidationException(e: com.google.devtools.build.lib.packages.ConfiguredAttributeMapper.ValidationException?)
        }

        init {
            this.targetAndConfiguration = targetAndConfiguration
            this.configuredTargetKey = configuredTargetKey
            this.configConditions = configConditions
            this.platformInfo = platformInfo
            this.transitiveState = transitiveState
            this.sink = sink
            this.runAfter = runAfter
        }

        override fun step(tasks: com.google.devtools.build.skyframe.state.StateMachine.Tasks): StateMachine? {
            val rule: com.google.devtools.build.lib.packages.Rule? =
                targetAndConfiguration.getTarget().getAssociatedRule()
            if (rule == null || !rule.useToolchainResolution() || platformInfo == null) {
                sink.acceptIncompatibleTarget(java.util.Optional.empty<RuleConfiguredTargetValue?>())
                return runAfter
            }

            val configuration: BuildConfigurationValue? = targetAndConfiguration.getConfiguration()
            // Retrieves the label list for the target_compatible_with attribute.
            val attrs: ConfiguredAttributeMapper =
                ConfiguredAttributeMapper.of(rule, configConditions.asProviders(), configuration)
            if (!attrs.has<MutableList<com.google.devtools.build.lib.cmdline.Label?>?>(
                    "target_compatible_with",
                    BuildType.LABEL_LIST
                )
            ) {
                sink.acceptIncompatibleTarget(java.util.Optional.empty<RuleConfiguredTargetValue?>())
                return runAfter
            }

            // Resolves the constraint labels, checking for invalid configured attributes.
            val targetCompatibleWith: MutableList<com.google.devtools.build.lib.cmdline.Label?>
            try {
                targetCompatibleWith = attrs.getAndValidate<MutableList<com.google.devtools.build.lib.cmdline.Label?>>(
                    "target_compatible_with",
                    BuildType.LABEL_LIST
                )
            } catch (e: com.google.devtools.build.lib.packages.ConfiguredAttributeMapper.ValidationException) {
                sink.acceptValidationException(e)
                return runAfter
            }
            for (label in targetCompatibleWith) {
                tasks.lookUp(
                    ConfiguredTargetKey.builder().setLabel(label).setConfiguration(configuration).build(),
                    this
                )
            }
            return StateMachine { tasks: com.google.devtools.build.skyframe.state.StateMachine.Tasks? ->
                this.processResult(
                    tasks
                )
            }
        }

        override fun accept(value: SkyValue) {
            val configuredTarget: ConfiguredTarget? = (value as ConfiguredTargetValue).getConfiguredTarget()
            val info: ConstraintValueInfo? = PlatformProviderUtils.constraintValue(configuredTarget)
            if (info == null) {
                return
            }
            allConstraintValuesBuilder.add(info)
            if (!platformInfo.constraints().hasConstraintValue(info)) {
                invalidConstraintValuesBuilder.add(info)
            }
        }

        private fun processResult(tasks: com.google.devtools.build.skyframe.state.StateMachine.Tasks?): StateMachine? {
            val allConstraintValues: com.google.common.collect.ImmutableList<ConstraintValueInfo?> =
                allConstraintValuesBuilder.build()

            // Validate that there are no duplicate constraint values from the same constraint setting
            try {
                ConstraintCollection.validateConstraints(allConstraintValues)
            } catch (e: com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException) {
                sink.acceptValidationException(
                    com.google.devtools.build.lib.packages.ConfiguredAttributeMapper.ValidationException(
                        e.message
                    )
                )
                return runAfter
            }

            val invalidConstraintValues: com.google.common.collect.ImmutableList<ConstraintValueInfo?> =
                invalidConstraintValuesBuilder.build()
            if (!invalidConstraintValues.isEmpty()) {
                sink.acceptIncompatibleTarget(
                    java.util.Optional.of<RuleConfiguredTargetValue?>(
                        createIncompatibleRuleConfiguredTarget(
                            configuredTargetKey,
                            targetAndConfiguration.getConfiguration(),
                            configConditions,
                            IncompatiblePlatformProvider.Companion.incompatibleDueToConstraints(
                                platformInfo.label(), invalidConstraintValues
                            ),
                            targetAndConfiguration
                                .getTarget()
                                .getAssociatedRule()
                                .getRuleClassObject()
                                .getRuleClassId(),
                            transitiveState
                        )
                    )
                )
                return runAfter
            }
            sink.acceptIncompatibleTarget(java.util.Optional.empty<RuleConfiguredTargetValue?>())
            return runAfter
        }
    }

    /** Thrown if this target is platform-incompatible with the current build.  */
    class IncompatibleTargetException(target: RuleConfiguredTargetValue?) : java.lang.Exception() {
        private val target: RuleConfiguredTargetValue?

        init {
            this.target = target
        }

        fun target(): RuleConfiguredTargetValue? {
            return target
        }
    }
}
