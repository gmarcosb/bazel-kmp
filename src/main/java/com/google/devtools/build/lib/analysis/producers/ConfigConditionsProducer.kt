// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.producers

import com.google.devtools.build.lib.analysis.InconsistentNullConfigException

/** Computes the targets that key the configurable attributes used by this rule.  */
internal class ConfigConditionsProducer
    (
    target: com.google.devtools.build.lib.packages.Target,
    targetLabel: com.google.devtools.build.lib.cmdline.Label?,
    buildConfigurationKey: BuildConfigurationKey?,
    targetPlatformInfo: com.google.devtools.build.lib.analysis.platform.PlatformInfo?,
    transitiveState: TransitiveDependencyState?,
    sink: ResultSink,
    runAfter: StateMachine?
) : StateMachine, com.google.devtools.build.lib.analysis.producers.ConfiguredTargetAndDataProducer.ResultSink {
    internal interface ResultSink {
        fun acceptConfigConditions(configConditions: ConfigConditions?)

        fun acceptConfigConditionsError(error: ConfiguredValueCreationException?)
    }

    // -------------------- Input --------------------
    private val targetLabel: com.google.devtools.build.lib.cmdline.Label?
    private val target: com.google.devtools.build.lib.packages.Target?
    private val buildConfigurationKey: BuildConfigurationKey?
    private val targetPlatformInfo: com.google.devtools.build.lib.analysis.platform.PlatformInfo?
    private val transitiveState: TransitiveDependencyState?

    // -------------------- Output --------------------
    private val sink: ResultSink

    // -------------------- Sequencing --------------------
    private val runAfter: StateMachine?

    // -------------------- Internal State --------------------
    // Null if there are no config labels.
    private val configLabels: MutableList<com.google.devtools.build.lib.cmdline.Label?>?

    // Null if there are no config labels.
    private val prerequisites: Array<ConfiguredTargetAndData?>?

    // Null if there are no dependency errors.
    private var mostImportantExitCode: DetailedExitCode? = null

    init {
        this.targetLabel = targetLabel
        this.target = target
        this.buildConfigurationKey = buildConfigurationKey
        this.targetPlatformInfo = targetPlatformInfo
        this.transitiveState = transitiveState
        this.sink = sink
        this.runAfter = runAfter

        this.configLabels = computeConfigLabels(target)
        this.prerequisites =
            if (configLabels == null) null else arrayOfNulls<ConfiguredTargetAndData>(configLabels.size)
    }

    override fun step(tasks: StateMachine.Tasks): StateMachine? {
        if (configLabels == null) {
            sink.acceptConfigConditions(ConfigConditions.EMPTY)
            return runAfter
        }

        // Collect the actual deps without a configuration transition (since by definition config
        // conditions evaluate over the current target's configuration). If the dependency is
        // (erroneously) something that needs the null configuration, its analysis will be
        // short-circuited. That error will be reported later.
        for (i in configLabels.indices) {
            tasks.enqueue(
                ConfiguredTargetAndDataProducer(
                    ConfiguredTargetKey.builder()
                        .setLabel(configLabels.get(i))
                        .setConfigurationKey(buildConfigurationKey)
                        .build(),  /* transitionKeys= */
                    com.google.common.collect.ImmutableList.of<String?>(),
                    transitiveState,
                    this as com.google.devtools.build.lib.analysis.producers.ConfiguredTargetAndDataProducer.ResultSink,
                    i,  /* baseTargetPrerequisitesSupplier= */
                    null
                )
            )
        }
        return StateMachine { tasks: StateMachine.Tasks? -> this.constructConfigConditions(tasks) }
    }

    override fun acceptConfiguredTargetAndData(value: ConfiguredTargetAndData?, index: Int) {
        prerequisites!![index] = value
    }

    override fun acceptConfiguredTargetAndDataError(error: ConfiguredValueCreationException) {
        emitErrorIfMostImportant(error.getDetailedExitCode())
    }

    override fun acceptConfiguredTargetAndDataError(error: NoSuchThingException) {
        emitErrorIfMostImportant(error.getDetailedExitCode())
    }

    override fun acceptConfiguredTargetAndDataError(error: InconsistentNullConfigException?) {
        // A config label was evaluated with a null configuration. This should never happen as
        // ConfigConditions are only present if the parent is a Rule, then always evaluated with the
        // parent configuration.
        throw java.lang.IllegalArgumentException(
            "ConfigCondition dependency should never be evaluated with a null configuration.", error
        )
    }

    private fun constructConfigConditions(tasks: StateMachine.Tasks?): StateMachine? {
        if (mostImportantExitCode != null) {
            return runAfter // There was a previous error.
        }

        val asConfiguredTargets: com.google.common.collect.ImmutableMap.Builder<com.google.devtools.build.lib.cmdline.Label?, ConfiguredTargetAndData?> =
            com.google.common.collect.ImmutableMap.Builder<com.google.devtools.build.lib.cmdline.Label?, ConfiguredTargetAndData?>()
        val asConfigConditions: com.google.common.collect.ImmutableMap.Builder<com.google.devtools.build.lib.cmdline.Label?, ConfigMatchingProvider?> =
            com.google.common.collect.ImmutableMap.Builder<com.google.devtools.build.lib.cmdline.Label?, ConfigMatchingProvider?>()
        for (i in configLabels.indices) {
            val label: com.google.devtools.build.lib.cmdline.Label? = configLabels!!.get(i)
            val prerequisite: ConfiguredTargetAndData? = prerequisites!![i]
            asConfiguredTargets.put(label, prerequisite)
            try {
                asConfigConditions.put(
                    label, ConfigConditions.fromConfiguredTarget(prerequisite, targetPlatformInfo)
                )
            } catch (e: ConfigConditions.InvalidConditionException) {
                val message =
                    (String.format(
                        "%s is not a valid select() condition for %s.\n",
                        prerequisite.getTargetLabel(), targetLabel
                    ) + String.format(
                        "To inspect the select(), run: bazel query --output=build %s.\n", targetLabel
                    ) + "For more help, see https://bazel.build/reference/be/functions#select.\n\n")
                sink.acceptConfigConditionsError(ConfiguredValueCreationException(target, message))
                return runAfter
            }
        }
        sink.acceptConfigConditions(
            ConfigConditions.create(
                asConfiguredTargets.buildOrThrow(), asConfigConditions.buildOrThrow()
            )
        )
        return runAfter
    }

    private fun emitErrorIfMostImportant(newExitCode: DetailedExitCode?) {
        mostImportantExitCode =
            DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                newExitCode, mostImportantExitCode
            )
        if (newExitCode == mostImportantExitCode) {
            sink.acceptConfigConditionsError( // The precise error is reported by the dependency that failed to load.
                // TODO(gregce): beautify this error: https://github.com/bazelbuild/bazel/issues/11984.
                ConfiguredValueCreationException(
                    target, "errors encountered resolving select() keys for " + targetLabel
                )
            )
        }
    }

    companion object {
        /**
         * Computes the config labels belonging to the given target.
         * 
         * @return null if there were no config labels, implying a [ConfigConditions.EMPTY] result.
         */
        private fun computeConfigLabels(target: com.google.devtools.build.lib.packages.Target): MutableList<com.google.devtools.build.lib.cmdline.Label?>? {
            val rule: com.google.devtools.build.lib.packages.Rule? = target.getAssociatedRule()
            if (rule == null) {
                return null
            }

            val attrs: RawAttributeMapper = RawAttributeMapper.of(rule)
            if (!attrs.has(RuleClass.CONFIG_SETTING_DEPS_ATTRIBUTE)) {
                return null
            }

            // Collects the labels of the configured targets we need to resolve.
            val configLabels: MutableList<com.google.devtools.build.lib.cmdline.Label?>? =
                attrs.get<MutableList<com.google.devtools.build.lib.cmdline.Label?>?>(
                    RuleClass.CONFIG_SETTING_DEPS_ATTRIBUTE,
                    BuildType.LABEL_LIST
                )
            if (configLabels!!.isEmpty()) {
                return null
            }
            return configLabels
        }
    }
}
