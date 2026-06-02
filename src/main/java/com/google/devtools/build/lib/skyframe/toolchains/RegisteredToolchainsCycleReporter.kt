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
package com.google.devtools.build.lib.skyframe.toolchains

import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement

/**
 * [CyclesReporter.SingleCycleReporter] implementation that can handle cycles involving
 * registered toolchains.
 */
class RegisteredToolchainsCycleReporter : SingleCycleReporter {
    override fun maybeReportCycle(
        topLevelKey: SkyKey?,
        cycleInfo: CycleInfo,
        alreadyReported: Boolean,
        eventHandler: ExtendedEventHandler
    ): Boolean {
        val cycle: com.google.common.collect.ImmutableList<SkyKey> = cycleInfo.getCycle()
        if (alreadyReported) {
            return true
        } else if (!com.google.common.collect.Iterables.any<SkyKey?>(cycle, IS_TOOLCHAIN_RELATED)) {
            return false
        }

        // Find the ConfiguredTargetKey, this should tell the problem.
        val configuredTargetKey: java.util.Optional<ConfiguredTargetKey?> = findRootConfiguredTarget(cycle)
        if (!configuredTargetKey.isPresent()) {
            return false
        }

        val printer: com.google.common.base.Function<Any?, String?> =
            com.google.common.base.Function { input: Any? ->
                if (input is ConfiguredTargetKey) {
                    val label: Label = input.getLabel()
                    return@Function label.toString()
                }
                if (input is com.google.devtools.build.lib.skyframe.toolchains.RegisteredToolchainsValue.Key) {
                    return@Function "RegisteredToolchains"
                }
                if (input is SingleToolchainResolutionKey) {
                    val toolchainType: Label? =
                        (input as SingleToolchainResolutionKey).toolchainType().toolchainType()
                    return@Function java.lang.String.format("toolchain type %s", toolchainType)
                }
                if (input is ToolchainContextKey) {
                    val toolchainTypes: String? =
                        input.toolchainTypes().stream()
                            .map<Any?>(ToolchainTypeRequirement::toolchainType)
                            .map<Any?>(Label::toString)
                            .collect(Collectors.joining(", "))
                    return@Function java.lang.String.format("toolchain types %s", toolchainTypes)
                }
                throw java.lang.UnsupportedOperationException(input.toString())
            }

        val cycleMessage: java.lang.StringBuilder =
            java.lang.StringBuilder()
                .append("Misconfigured toolchains: ")
                .append(printer.apply(configuredTargetKey.get()))
                .append(" is declared as a toolchain but has inappropriate dependencies.")
                .append(" Declared toolchains should be created with the 'toolchain' rule")
                .append(" and should not have dependencies that themselves require toolchains.")

        AbstractLabelCycleReporter.printCycle(cycleInfo.getCycle(), cycleMessage, printer)
        eventHandler.handle(Event.error(null, cycleMessage.toString()))
        return true
    }

    /**
     * Returns the first [SkyKey] that is an instance of [ConfiguredTargetKey] and follows
     * [RegisteredToolchainsValue.Key]. This will loop over the cycle in case the [ ] is not first in the list.
     */
    private fun findRootConfiguredTarget(cycle: com.google.common.collect.ImmutableList<SkyKey>): java.util.Optional<ConfiguredTargetKey?> {
        // Loop over the cycle, possibly twice, first looking for RegisteredToolchainsValue,
        // then finding the first ConfiguredTargetKey.
        var rtvFound = false
        for (i in 0..<cycle.size() * 2) {
            val skyKey: SkyKey = cycle.get(i % cycle.size())
            if (!rtvFound && IS_REGISTERED_TOOLCHAINS_SKY_KEY.apply(skyKey)) {
                rtvFound = true
            }
            if (rtvFound && IS_CONFIGURED_TARGET_SKY_KEY.apply(skyKey)) {
                return java.util.Optional.of<ConfiguredTargetKey?>(skyKey as ConfiguredTargetKey)
            }
        }

        return java.util.Optional.empty<ConfiguredTargetKey?>()
    }

    companion object {
        private val IS_REGISTERED_TOOLCHAINS_SKY_KEY: com.google.common.base.Predicate<SkyKey?> =
            SkyFunctions.isSkyFunction(SkyFunctions.REGISTERED_TOOLCHAINS)

        private val IS_CONFIGURED_TARGET_SKY_KEY: com.google.common.base.Predicate<SkyKey?> =
            SkyFunctions.isSkyFunction(SkyFunctions.CONFIGURED_TARGET)

        private val IS_SINGLE_TOOLCHAIN_RESOLUTION_SKY_KEY: com.google.common.base.Predicate<SkyKey?>? =
            SkyFunctions.isSkyFunction(SkyFunctions.SINGLE_TOOLCHAIN_RESOLUTION)

        private val IS_TOOLCHAIN_RESOLUTION_SKY_KEY: com.google.common.base.Predicate<SkyKey?>? =
            SkyFunctions.isSkyFunction(SkyFunctions.TOOLCHAIN_RESOLUTION)

        private val IS_TOOLCHAIN_RELATED: com.google.common.base.Predicate<SkyKey?> =
            com.google.common.base.Predicates.or<SkyKey?>(
                IS_REGISTERED_TOOLCHAINS_SKY_KEY,
                IS_SINGLE_TOOLCHAIN_RESOLUTION_SKY_KEY,
                IS_TOOLCHAIN_RESOLUTION_SKY_KEY
            )
    }
}
