// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/**
 * Utility class for temporarily tracking `select()` keys' [ConfigMatchingProvider]s and
 * [ConfiguredTarget]s.
 * 
 * 
 * This is a utility class because its only purpose is to maintain [ConfiguredTarget] long
 * enough for [RuleContext.Builder] to do prerequisite validation on it (for example,
 * visibility checks).
 * 
 * 
 * Once [RuleContext] is instantiated, it should only have access to [ ], on the principle that providers are the correct interfaces for storing
 * and sharing target metadata. [ConfiguredTarget] isn't meant to persist that long.
 */
class ConfigConditions(
    asConfiguredTargets: com.google.common.collect.ImmutableMap<Label?, ConfiguredTargetAndData?>?,
    asProviders: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>?
) {
    /** Exception for when a `select()` has an invalid key (for example, wrong target type).  */
    class InvalidConditionException : java.lang.Exception()

    val asConfiguredTargets: com.google.common.collect.ImmutableMap<Label?, ConfiguredTargetAndData?>?
    val asProviders: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>?

    init {
        this.asProviders = asProviders
        this.asConfiguredTargets = asConfiguredTargets
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<Label?, ConfiguredTargetAndData?>?>(
            asConfiguredTargets,
            "asConfiguredTargets"
        )
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>?>(
            asProviders,
            "asProviders"
        )
    }

    companion object {
        fun create(
            asConfiguredTargets: com.google.common.collect.ImmutableMap<Label?, ConfiguredTargetAndData?>?,
            asProviders: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>?
        ): ConfigConditions {
            return ConfigConditions(asConfiguredTargets, asProviders)
        }

        val EMPTY: ConfigConditions = create(
            com.google.common.collect.ImmutableMap.of<Label?, ConfiguredTargetAndData?>(),
            com.google.common.collect.ImmutableMap.of<Label?, ConfigMatchingProvider?>()
        )

        /**
         * Returns a [ConfigMatchingProvider] from the given configured target if appropriate, else
         * triggers a [InvalidConditionException].
         * 
         * 
         * This is the canonical place to extract [ConfigMatchingProvider]s from configured
         * targets. It's not as simple as [ConfiguredTarget.getProvider].
         */
        @Throws(InvalidConditionException::class)
        fun fromConfiguredTarget(
            selectKey: ConfiguredTargetAndData, targetPlatform: PlatformInfo?
        ): ConfigMatchingProvider? {
            val selectable: ConfiguredTarget = selectKey.getConfiguredTarget()
            // The below handles config_setting (which natively provides ConfigMatchingProvider) and
            // constraint_value (which needs a custom-built ConfigMatchingProvider).
            val matchingProvider: ConfigMatchingProvider? = selectable.getProvider(ConfigMatchingProvider::class.java)
            if (matchingProvider != null) {
                return matchingProvider
            }
            val constraintValueInfo: ConstraintValueInfo? = selectable.get(ConstraintValueInfo.PROVIDER)
            if (constraintValueInfo != null && targetPlatform != null) {
                // If platformInfo == null, that means the owning target doesn't invoke toolchain
                // resolution, in which case depending on a constraint_value is nonsensical.
                return constraintValueInfo.configMatchingProvider(targetPlatform)
            }

            // Not a valid provider for configuration conditions.
            throw InvalidConditionException()
        }
    }
}
