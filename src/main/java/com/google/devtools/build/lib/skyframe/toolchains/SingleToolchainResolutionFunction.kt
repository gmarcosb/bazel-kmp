// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.PlatformConfiguration

/** [SkyFunction] which performs toolchain resolution for a single toolchain type.  */
class SingleToolchainResolutionFunction : SkyFunction {
    @Throws(
        com.google.devtools.build.lib.skyframe.toolchains.SingleToolchainResolutionFunction.ToolchainResolutionFunctionException::class,
        java.lang.InterruptedException::class
    )
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val key: SingleToolchainResolutionKey = skyKey.argument() as SingleToolchainResolutionKey

        // This call could be combined with other Skyframe calls, but this SkyFunction is evaluated so
        // rarely it's not worth optimizing.
        val configuration: BuildConfigurationValue? =
            env.getValue(key.configurationKey()) as BuildConfigurationValue?
        if (env.valuesMissing()) {
            return null
        }

        // Check if we are debugging the target or the toolchain type.
        val debug =
            key.debugTarget()
                    || configuration
                .getFragment(PlatformConfiguration::class.java)
                .debugToolchainResolution(key.toolchainType().toolchainType())

        // Get all toolchains.
        val toolchains: RegisteredToolchainsValue?
        try {
            toolchains =
                env.getValueOrThrow<InvalidToolchainLabelException?>(
                    RegisteredToolchainsValue.Companion.key(key.configurationKey(), debug),
                    InvalidToolchainLabelException::class.java
                ) as RegisteredToolchainsValue?
            if (toolchains == null) {
                return null
            }
        } catch (e: InvalidToolchainLabelException) {
            throw com.google.devtools.build.lib.skyframe.toolchains.SingleToolchainResolutionFunction.ToolchainResolutionFunctionException(
                e
            )
        }

        val platforms: MutableMap<ConfiguredTargetKey?, PlatformInfo>? =
            getPlatforms(env, key.targetPlatformKey(), key.availableExecutionPlatformKeys())
        if (platforms == null) {
            return null
        }

        val debugPrinter: SingleToolchainResolutionDebugPrinter =
            SingleToolchainResolutionDebugPrinter.Companion.create(debug, env.getListener())
        val targetPlatform: PlatformInfo = platforms.get(key.targetPlatformKey())
        debugPrinter.startToolchainResolution(
            key.toolchainType().toolchainType(), targetPlatform.label()
        )

        // Describe rejected toolchains if any are present.
        java.util.Optional.ofNullable<com.google.common.collect.ImmutableTable<Label?, Label?, String?>?>(toolchains.rejectedToolchains)
            .map<com.google.common.collect.ImmutableMap<Label?, String?>?>(java.util.function.Function { rejectedToolchains: com.google.common.collect.ImmutableTable<Label?, Label?, kotlin.String?>? ->
                rejectedToolchains.row(
                    key.toolchainType().toolchainType()
                )
            })
            .ifPresent(java.util.function.Consumer { rejectedToolchains: com.google.common.collect.ImmutableMap<Label?, kotlin.String?>? ->
                debugPrinter.describeRejectedToolchains(
                    rejectedToolchains
                )
            })

        // Find the right one.
        val toolchainResolution: SingleToolchainResolutionValue? =
            resolveConstraints(
                debugPrinter,
                key.toolchainType(),
                key.toolchainTypeInfo(),
                key.availableExecutionPlatformKeys(),
                platforms,
                targetPlatform,
                toolchains.registeredToolchains
            )

        debugPrinter.finishDebugging()

        return toolchainResolution
    }

    /**
     * Loads [PlatformInfo] from Skyframe for checking against toolchain constraints. Returns
     * null if Skyframe value isn't available yet.
     */
    @Throws(
        com.google.devtools.build.lib.skyframe.toolchains.SingleToolchainResolutionFunction.ToolchainResolutionFunctionException::class,
        java.lang.InterruptedException::class
    )
    private fun getPlatforms(
        env: SkyFunction.Environment?,
        targetPlatformKey: ConfiguredTargetKey,
        availableExecutionPlatformKeys: MutableList<ConfiguredTargetKey>
    ): MutableMap<ConfiguredTargetKey?, PlatformInfo>? {
        try {
            return PlatformLookupUtil.getPlatformInfo(
                com.google.common.collect.ImmutableList.builderWithExpectedSize<ConfiguredTargetKey?>(
                    availableExecutionPlatformKeys.size() + 1
                )
                    .add(targetPlatformKey)
                    .addAll(availableExecutionPlatformKeys)
                    .build(),
                env
            )
        } catch (e: InvalidPlatformException) {
            throw com.google.devtools.build.lib.skyframe.toolchains.SingleToolchainResolutionFunction.ToolchainResolutionFunctionException(
                e
            )
        }
    }

    /**
     * Exception used when there was some issue with the toolchain making it impossible to resolve
     * constraints.
     */
    internal class InvalidConfigurationDuringToolchainResolutionException
        (e: InvalidConfigurationException?) : ToolchainException(e) {
        val detailedCode: Code
            get() = Code.INVALID_CONSTRAINT_VALUE
    }

    /**
     * Used to indicate errors during the computation of an [SingleToolchainResolutionValue].
     */
    private class ToolchainResolutionFunctionException : SkyFunctionException {
        internal constructor(e: InvalidToolchainLabelException?) : super(e, Transience.PERSISTENT)

        internal constructor(e: InvalidPlatformException?) : super(e, Transience.PERSISTENT)
    }

    companion object {
        /**
         * Given the available execution platforms and toolchains, find the set of platform, toolchain
         * pairs that are compatible a) with each other, and b) with the toolchain type and target
         * platform.
         */
        @Throws(
            com.google.devtools.build.lib.skyframe.toolchains.SingleToolchainResolutionFunction.ToolchainResolutionFunctionException::class,
            java.lang.InterruptedException::class
        )
        private fun resolveConstraints(
            debugPrinter: SingleToolchainResolutionDebugPrinter,
            toolchainType: ToolchainTypeRequirement,
            toolchainTypeInfo: ToolchainTypeInfo?,
            availableExecutionPlatformKeys: MutableList<ConfiguredTargetKey>,
            platforms: MutableMap<ConfiguredTargetKey?, PlatformInfo>,
            targetPlatform: PlatformInfo,
            toolchains: com.google.common.collect.ImmutableList<DeclaredToolchainInfo?>
        ): SingleToolchainResolutionValue? {
            // Platforms may exist multiple times in availableExecutionPlatformKeys. The Set lets this code
            // check whether a platform has already been seen during processing.
            val platformKeysSeen: MutableSet<ConfiguredTargetKey?> = HashSet<ConfiguredTargetKey?>()
            val builder: com.google.common.collect.ImmutableMap.Builder<ConfiguredTargetKey?, Label?> =
                com.google.common.collect.ImmutableMap.builder<ConfiguredTargetKey?, Label?>()

            // Pre-filter for the correct toolchain type. This simplifies the loop and makes debugging
            // toolchain resolution much, much easier.
            val filteredToolchains: com.google.common.collect.ImmutableList<DeclaredToolchainInfo> =
                toolchains.stream()
                    .filter(
                        java.util.function.Predicate { toolchain: DeclaredToolchainInfo? ->
                            toolchain.toolchainType().typeLabel().equals(toolchainType.toolchainType())
                        })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<DeclaredToolchainInfo>())

            for (toolchain in filteredToolchains) {
                // Make sure the target platform matches. A toolchain with use_target_platform_constraints
                // matches
                // any target platform.
                if (!toolchain.hasTargetToExecConstraints()
                    && !checkConstraints(
                        debugPrinter,
                        toolchain.targetConstraints(),  /* isTargetPlatform= */
                        true,
                        targetPlatform,
                        toolchain.targetLabel(),
                        toolchain.resolvedToolchainLabel()
                    )
                ) {
                    continue
                }

                debugPrinter.reportCompatibleTargetPlatform(
                    toolchain.targetLabel(), toolchain.resolvedToolchainLabel()
                )

                var done = true

                // Find the matching execution platforms.
                for (executionPlatformKey in availableExecutionPlatformKeys) {
                    // Only check the toolchains if this is a new platform.
                    if (platformKeysSeen.contains(executionPlatformKey)) {
                        debugPrinter.reportSkippedExecutionPlatformSeen(executionPlatformKey.getLabel())
                        continue
                    }

                    val executionPlatform: PlatformInfo = platforms.get(executionPlatformKey)

                    // Check if the platform allows this toolchain type.
                    if (executionPlatform.checkToolchainTypes()
                        && !executionPlatform.allowedToolchainTypes().contains(toolchainType.toolchainType())
                    ) {
                        debugPrinter.reportSkippedExecutionPlatformDisallowed(
                            executionPlatformKey.getLabel(), toolchainType.toolchainType()
                        )

                        // Keep looking for a valid toolchain for this exec platform
                        done = false
                        continue
                    }

                    // Check if the execution constraints match.
                    if (!checkConstraints(
                            debugPrinter,
                            if (toolchain.hasTargetToExecConstraints())
                                targetPlatform.constraints()
                            else
                                toolchain.execConstraints(),  /* isTargetPlatform= */
                            false,
                            executionPlatform,
                            toolchain.targetLabel(),
                            toolchain.resolvedToolchainLabel()
                        )
                    ) {
                        // Keep looking for a valid toolchain for this exec platform
                        done = false
                        continue
                    }

                    debugPrinter.reportCompatibleExecutionPlatform(executionPlatformKey.getLabel())
                    builder.put(executionPlatformKey, toolchain.resolvedToolchainLabel())
                    platformKeysSeen.add(executionPlatformKey)
                }

                if (done) {
                    debugPrinter.reportDone(toolchainType.toolchainType())
                    break
                }
            }

            val resolvedToolchainLabels: com.google.common.collect.ImmutableMap<ConfiguredTargetKey?, Label?> =
                builder.buildOrThrow()
            debugPrinter.reportResolvedToolchains(
                resolvedToolchainLabels, targetPlatform.label(), toolchainType.toolchainType()
            )

            return SingleToolchainResolutionValue.Companion.create(toolchainTypeInfo, resolvedToolchainLabels)
        }

        /**
         * Returns `true` iff all constraints set by the toolchain and in the [PlatformInfo]
         * match.
         */
        private fun checkConstraints(
            debugPrinter: SingleToolchainResolutionDebugPrinter,
            toolchainConstraints: ConstraintCollection,
            isTargetPlatform: Boolean,
            platform: PlatformInfo,
            targetLabel: Label?,
            resolvedToolchainLabel: Label?
        ): Boolean {
            // Check every constraint_setting in either the toolchain or the platform.

            val mismatchSettings: com.google.common.collect.ImmutableSet<ConstraintSettingInfo?> =
                toolchainConstraints.diff(platform.constraints())

            // If a constraint_setting has a default_constraint_value, and the platform
            // sets a non-default constraint value for the same constraint_setting, then
            // even toolchains with no reference to that constraint_setting will detect
            // a mismatch here. This manifests as a toolchain resolution failure (#8778).
            //
            // To allow combining rulesets with their own toolchains in a single top-level
            // workspace, toolchains that do not reference a constraint_setting should not
            // be forced to match with it.
            val mismatchSettingsWithDefault: com.google.common.collect.ImmutableSet<ConstraintSettingInfo?> =
                mismatchSettings.stream()
                    .filter(toolchainConstraints::hasWithoutDefault)
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<ConstraintSettingInfo?>())

            debugPrinter.reportMismatchedSettings(
                toolchainConstraints,
                isTargetPlatform,
                platform,
                targetLabel,
                resolvedToolchainLabel,
                mismatchSettingsWithDefault
            )

            return mismatchSettingsWithDefault.isEmpty()
        }
    }
}
