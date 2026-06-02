// Copyright 2019 The Bazel Authors. All rights reserved.
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

/**
 * Sky function which performs toolchain resolution for multiple toolchain types, including
 * selecting the execution platform.
 */
class ToolchainResolutionFunction : SkyFunction {
    @Throws(
        com.google.devtools.build.lib.skyframe.toolchains.ToolchainResolutionFunction.ToolchainResolutionFunctionException::class,
        java.lang.InterruptedException::class
    )
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): UnloadedToolchainContext? {
        val key: ToolchainContextKey = skyKey.argument() as ToolchainContextKey

        try {
            val builder: com.google.devtools.build.lib.skyframe.toolchains.UnloadedToolchainContextImpl.Builder =
                UnloadedToolchainContextImpl.Companion.builder(key)

            // Determine the configuration being used.
            val configuration: BuildConfigurationValue? =
                env.getValue(key.configurationKey()) as BuildConfigurationValue?
            if (configuration == null) {
                throw ValueMissingException()
            }
            val platformConfiguration: PlatformConfiguration =
                com.google.common.base.Preconditions.checkNotNull<T>(configuration.getFragment(PlatformConfiguration::class.java))

            // Check if debug output should be generated.
            val debug =
                key.debugTarget()
                        || configuration
                    .getFragment(PlatformConfiguration::class.java)
                    .debugToolchainResolution(
                        key.toolchainTypes().stream()
                            .map<Any?>(ToolchainTypeRequirement::toolchainType)
                            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
                    )

            val debugPrinter: ToolchainResolutionDebugPrinter =
                ToolchainResolutionDebugPrinter.Companion.create(debug, env.getListener())

            // Create keys for all platforms that will be used, and validate them early.
            // Do this early, to catch platform errors early.
            val platformKeys: PlatformKeys =
                PlatformKeys.Companion.load(
                    env,
                    debugPrinter,
                    configuration.getKey(),
                    platformConfiguration,
                    key.execConstraintLabels()
                )
            if (env.valuesMissing()) {
                return null
            }

            // Load the configured target for the toolchain types to ensure that they are valid and
            // resolve aliases.
            val resolvedToolchainTypeInfos: com.google.common.collect.ImmutableMap<Label?, ToolchainTypeInfo?>? =
                loadToolchainTypeInfos(env, configuration, key.toolchainTypes())
            builder.setRequestedLabelToToolchainType(resolvedToolchainTypeInfos)
            val resolvedToolchainTypes: com.google.common.collect.ImmutableSet<ToolchainType> =
                loadToolchainTypes(resolvedToolchainTypeInfos, key.toolchainTypes())

            // Determine the actual toolchain implementations to use.
            determineToolchainImplementations(
                env,
                key.configurationKey(),
                resolvedToolchainTypes,
                key.forceExecutionPlatform()
                    .map<ConfiguredTargetKey?>(java.util.function.Function { platformLabel: Label? ->
                        platformKeys.find(platformLabel)
                    }),
                builder,
                platformKeys,
                key.debugTarget()
            )

            val unloadedToolchainContext: UnloadedToolchainContext = builder.build()
            debugPrinter.reportSelectedToolchains(
                unloadedToolchainContext.targetPlatform().label(),
                unloadedToolchainContext.executionPlatform().label(),
                unloadedToolchainContext.toolchainTypeToResolved()
            )
            return unloadedToolchainContext
        } catch (e: ToolchainException) {
            throw com.google.devtools.build.lib.skyframe.toolchains.ToolchainResolutionFunction.ToolchainResolutionFunctionException(
                e
            )
        } catch (e: ValueMissingException) {
            return null
        }
    }

    internal class ToolchainType(
        toolchainTypeRequirement: ToolchainTypeRequirement?,
        toolchainTypeInfo: ToolchainTypeInfo?
    ) {
        fun mandatory(): Boolean {
            return toolchainTypeRequirement.mandatory()
        }

        val toolchainTypeRequirement: ToolchainTypeRequirement?
        val toolchainTypeInfo: ToolchainTypeInfo?

        init {
            this.toolchainTypeInfo = toolchainTypeInfo
            this.toolchainTypeRequirement = toolchainTypeRequirement
            java.util.Objects.requireNonNull<Any?>(toolchainTypeRequirement, "toolchainTypeRequirement")
            java.util.Objects.requireNonNull<Any?>(toolchainTypeInfo, "toolchainTypeInfo")
        }
    }

    /**
     * Returns a map from the actual post-alias Label to the ToolchainTypeRequirement for that type.
     */
    private fun loadToolchainTypes(
        resolvedToolchainTypeInfos: com.google.common.collect.ImmutableMap<Label?, ToolchainTypeInfo?>,
        toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement>
    ): com.google.common.collect.ImmutableSet<ToolchainType> {
        val resolved: com.google.common.collect.ImmutableSet.Builder<ToolchainType?> =
            com.google.common.collect.ImmutableSet.Builder<ToolchainType?>()

        for (toolchainTypeRequirement in toolchainTypes) {
            // Find the actual Label.
            var toolchainTypeRequirement: ToolchainTypeRequirement = toolchainTypeRequirement
            var toolchainTypeLabel: Label = toolchainTypeRequirement.toolchainType()
            val toolchainTypeInfo: ToolchainTypeInfo? = resolvedToolchainTypeInfos.get(toolchainTypeLabel)
            if (toolchainTypeInfo == null) {
                continue
            }

            // If the labels don't match, re-build the TTR.
            toolchainTypeLabel = toolchainTypeInfo.typeLabel()
            if (!toolchainTypeLabel.equals(toolchainTypeRequirement.toolchainType())) {
                toolchainTypeRequirement =
                    toolchainTypeRequirement.toBuilder().toolchainType(toolchainTypeLabel).build()
            }

            resolved.add(ToolchainType(toolchainTypeRequirement, toolchainTypeInfo))
        }
        return resolved.build()
    }

    internal class ValueMissingException : java.lang.Exception()

    /** Exception used when a toolchain type is required but no matching toolchain is found.  */
    internal class UnresolvedToolchainsException(
        targetPlatformInfo: PlatformInfo,
        missingToolchainTypes: SequencedSet<ToolchainTypeInfo?>
    ) : ToolchainException(
        getMessage(targetPlatformInfo, missingToolchainTypes)
    ) {
        val detailedCode: Code
            get() = Code.NO_MATCHING_TOOLCHAIN

        companion object {
            private fun getMessage(
                targetPlatformInfo: PlatformInfo, missingToolchainTypes: SequencedSet<ToolchainTypeInfo?>
            ): String? {
                // All characters with special meaning anywhere in a regex (':' for example is only special
                // within brackets).
                val regexSpecialChars: MutableList<Char?> =
                    "+.|([{^$?\\*".codePoints().mapToObj<Char?>(java.util.function.IntFunction { c: Int -> c.toChar() })
                        .toList()
                val labelStrings: com.google.common.collect.ImmutableList<String?> =
                    missingToolchainTypes.stream()
                        .map<Any?>(ToolchainTypeInfo::typeLabel)
                        .map<Any?>(Label::toString)
                        .map<Any?>(
                            java.util.function.Function { label: Any? ->
                                // Regex-quote if label contains special characters.
                                for (c in regexSpecialChars) {
                                    if (label.indexOf(c) >= 0) {
                                        return@map java.util.regex.Pattern.quote(label)
                                    }
                                }
                                label
                            })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
                val missingToolchainRows: com.google.common.collect.ImmutableList<String?> =
                    missingToolchainTypes.stream()
                        .map<Any?>(
                            java.util.function.Function { type: ToolchainTypeInfo? ->
                                java.lang.String.format(
                                    "  %s%s",
                                    type.typeLabel(),
                                    if (type.noneFoundError() != null) ": " + type.noneFoundError() else ""
                                )
                            })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
                var platformSpecificMessage: String? = ""
                if (targetPlatformInfo.missingToolchainErrorMessage != null) {
                    platformSpecificMessage = targetPlatformInfo.missingToolchainErrorMessage
                }
                return java.lang.String.format(
                    """
          No matching toolchains found for types:
          %s
          To debug, rerun with --toolchain_resolution_debug='%s'
          %s
          """.trimIndent(),
                    java.lang.String.join("\n", missingToolchainRows),
                    java.lang.String.join("|", labelStrings),
                    platformSpecificMessage
                )
            }
        }
    }

    /** Used to indicate errors during the computation of an [UnloadedToolchainContextImpl].  */
    private class ToolchainResolutionFunctionException(e: ToolchainException?) :
        SkyFunctionException(e, Transience.PERSISTENT)

    companion object {
        /**
         * Returns a map from the requested toolchain type Label (after any alias chains) to the [ ] provider.
         */
        @Throws(
            InvalidToolchainTypeException::class,
            java.lang.InterruptedException::class,
            ValueMissingException::class
        )
        private fun loadToolchainTypeInfos(
            environment: SkyFunction.Environment,
            configuration: BuildConfigurationValue?,
            toolchainTypeRequirements: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement>
        ): com.google.common.collect.ImmutableMap<Label?, ToolchainTypeInfo?>? {
            val resolvedToolchainTypes: com.google.common.collect.ImmutableMap<Label?, ToolchainTypeInfo?>? =
                ToolchainTypeLookupUtil.resolveToolchainTypes(
                    environment, toolchainTypeRequirements, configuration
                )
            if (environment.valuesMissing()) {
                throw ValueMissingException()
            }
            return resolvedToolchainTypes
        }

        @Throws(
            java.lang.InterruptedException::class,
            ValueMissingException::class,
            InvalidPlatformException::class,
            UnresolvedToolchainsException::class,
            InvalidToolchainLabelException::class,
            InvalidConfigurationDuringToolchainResolutionException::class
        )
        private fun determineToolchainImplementations(
            environment: SkyFunction.Environment,
            configurationKey: BuildConfigurationKey?,
            toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainType>,
            forcedExecutionPlatform: java.util.Optional<ConfiguredTargetKey?>,
            builder: com.google.devtools.build.lib.skyframe.toolchains.UnloadedToolchainContextImpl.Builder,
            platformKeys: PlatformKeys,
            debugTarget: Boolean
        ) {
            // Find the toolchains for the requested toolchain types.

            val registeredToolchainKeys: MutableList<SingleToolchainResolutionKey> =
                java.util.ArrayList<SingleToolchainResolutionKey>()
            for (toolchainType in toolchainTypes) {
                registeredToolchainKeys.add(
                    SingleToolchainResolutionValue.Companion.key(
                        configurationKey,
                        toolchainType.toolchainTypeRequirement,
                        toolchainType.toolchainTypeInfo,
                        platformKeys.targetPlatformKey,
                        platformKeys.executionPlatformKeys,
                        debugTarget
                    )
                )
            }

            val results: SkyframeLookupResult = environment.getValuesAndExceptions(registeredToolchainKeys)
            var valuesMissing = false

            // Determine the potential set of toolchains.
            val resolvedToolchains: com.google.common.collect.Table<ConfiguredTargetKey?, ToolchainTypeInfo?, Label?> =
                com.google.common.collect.HashBasedTable.create<ConfiguredTargetKey?, ToolchainTypeInfo?, Label?>()
            val missingMandatoryToolchains: SequencedSet<ToolchainTypeInfo?> = LinkedHashSet<ToolchainTypeInfo?>()
            for (key in registeredToolchainKeys) {
                val singleToolchainResolutionValue: SingleToolchainResolutionValue? =
                    results.getOrThrow<InvalidToolchainLabelException?, InvalidConfigurationDuringToolchainResolutionException?>(
                        key,
                        InvalidToolchainLabelException::class.java,
                        InvalidConfigurationDuringToolchainResolutionException::class.java
                    ) as SingleToolchainResolutionValue?
                if (singleToolchainResolutionValue == null) {
                    valuesMissing = true
                    continue
                }

                if (!singleToolchainResolutionValue.availableToolchainLabels.isEmpty()) {
                    val requiredToolchainType: ToolchainTypeInfo? = singleToolchainResolutionValue.toolchainType
                    resolvedToolchains.putAll(
                        findPlatformsAndLabels(requiredToolchainType, singleToolchainResolutionValue)
                    )
                } else if (key.toolchainType().mandatory()) {
                    // Save the missing type and continue looping to check for more.
                    missingMandatoryToolchains.add(key.toolchainTypeInfo())
                }
                // TODO(katre): track missing optional toolchains?
            }

            // Verify that all mandatory toolchain types have a toolchain.
            if (!missingMandatoryToolchains.isEmpty()) {
                throw UnresolvedToolchainsException(
                    platformKeys.targetPlatformInfo(), missingMandatoryToolchains
                )
            }

            if (valuesMissing) {
                throw ValueMissingException()
            }

            // Find and return the first execution platform which has all mandatory toolchains.
            val selectedExecutionPlatformKey: java.util.Optional<ConfiguredTargetKey?> =
                findExecutionPlatformForToolchains(
                    toolchainTypes, forcedExecutionPlatform, platformKeys, resolvedToolchains
                )

            val toolchainTypeRequirements: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?> =
                toolchainTypes.stream()
                    .map<ToolchainTypeRequirement?>(ToolchainType::toolchainTypeRequirement)
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<ToolchainTypeRequirement?>())
            if (selectedExecutionPlatformKey.isEmpty()) {
                builder.setToolchainTypes(toolchainTypeRequirements)
                builder.setExecutionPlatform(PlatformInfo.EMPTY_PLATFORM_INFO)
                builder.setTargetPlatform(PlatformInfo.EMPTY_PLATFORM_INFO)
                builder.setErrorData(
                    NoMatchingPlatformData.Companion.builder()
                        .setToolchainTypes(toolchainTypeRequirements)
                        .setAvailableExecutionPlatformKeys(platformKeys.executionPlatformKeys)
                        .setTargetPlatformKey(platformKeys.targetPlatformKey)
                        .build()
                )
                return
            }

            val platforms: MutableMap<ConfiguredTargetKey?, PlatformInfo?>? =
                PlatformLookupUtil.getPlatformInfo(
                    com.google.common.collect.ImmutableList.of<ConfiguredTargetKey?>(
                        selectedExecutionPlatformKey.get(),
                        platformKeys.targetPlatformKey
                    ),
                    environment
                )
            if (platforms == null) {
                throw ValueMissingException()
            }

            builder.setToolchainTypes(toolchainTypeRequirements)
            builder.setExecutionPlatform(platforms.get(selectedExecutionPlatformKey.get()))
            builder.setTargetPlatform(platforms.get(platformKeys.targetPlatformKey))

            val toolchains: MutableMap<ToolchainTypeInfo?, Label?> =
                resolvedToolchains.row(selectedExecutionPlatformKey.get())
            builder.setToolchainTypeToResolved(
                com.google.common.collect.ImmutableSetMultimap.copyOf<ToolchainTypeInfo?, Label?>(
                    toolchains.entrySet()
                )
            )
        }

        /**
         * Adds all of toolchain labels from `toolchainResolutionValue` to `resolvedToolchains`.
         */
        private fun findPlatformsAndLabels(
            requiredToolchainType: ToolchainTypeInfo?,
            singleToolchainResolutionValue: SingleToolchainResolutionValue
        ): com.google.common.collect.Table<ConfiguredTargetKey?, ToolchainTypeInfo?, Label?> {
            val resolvedToolchains: com.google.common.collect.Table<ConfiguredTargetKey?, ToolchainTypeInfo?, Label?> =
                com.google.common.collect.HashBasedTable.create<ConfiguredTargetKey?, ToolchainTypeInfo?, Label?>()
            for (entry in singleToolchainResolutionValue.availableToolchainLabels.entrySet()) {
                resolvedToolchains.put(entry.getKey(), requiredToolchainType, entry.getValue())
            }
            return resolvedToolchains
        }

        /**
         * Finds the first platform from `availableExecutionPlatformKeys` that is present in `resolvedToolchains` and has all required toolchain types.
         */
        private fun findExecutionPlatformForToolchains(
            toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainType>,
            forcedExecutionPlatform: java.util.Optional<ConfiguredTargetKey?>,
            platformKeys: PlatformKeys,
            resolvedToolchains: com.google.common.collect.Table<ConfiguredTargetKey?, ToolchainTypeInfo?, Label?>
        ): java.util.Optional<ConfiguredTargetKey?> {
            if (forcedExecutionPlatform.isPresent()) {
                // Is the forced platform suitable?
                if (platformKeys.isPlatformSuitable(
                        forcedExecutionPlatform.get(),
                        toolchainTypes,
                        resolvedToolchains,  // For the forced execution platform, ignore allowed toolchain types.
                        /* checkAllowedToolchainTypes= */
                        false
                    )
                ) {
                    return forcedExecutionPlatform
                }
            }

            val candidatePlatforms: java.util.stream.Stream<ConfiguredTargetKey?>? =
                platformKeys.executionPlatformKeys.stream()
                    .filter(
                        java.util.function.Predicate { epk: ConfiguredTargetKey? ->
                            platformKeys.isPlatformSuitable(
                                epk,
                                toolchainTypes,
                                resolvedToolchains,  /* checkAllowedToolchainTypes= */
                                true
                            )
                        })

            val toolchainTypeInfos: com.google.common.collect.ImmutableSet<ToolchainTypeInfo?> =
                toolchainTypes.stream().map<ToolchainTypeInfo?>(ToolchainType::toolchainTypeInfo)
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<ToolchainTypeInfo?>())

            // Sort by the number of toolchains (the sort is stable)
            return candidatePlatforms.max(
                java.util.Comparator.comparingLong<ConfiguredTargetKey?>(
                    java.util.function.ToLongFunction { epk: ConfiguredTargetKey? ->
                        countToolchainsOnPlatform(
                            epk,
                            toolchainTypeInfos,
                            resolvedToolchains
                        )
                    })
            )
        }

        private fun countToolchainsOnPlatform(
            executionPlatformKey: ConfiguredTargetKey?,
            toolchainTypeInfos: com.google.common.collect.ImmutableSet<ToolchainTypeInfo?>,
            resolvedToolchains: com.google.common.collect.Table<ConfiguredTargetKey?, ToolchainTypeInfo?, Label?>
        ): Long {
            if (toolchainTypeInfos.isEmpty()) {
                return 0
            }

            // Determine the number of optional toolchains.
            val platformToolchains: MutableSet<ToolchainTypeInfo?> =
                resolvedToolchains.row(executionPlatformKey).keySet()
            return toolchainTypeInfos.stream()
                .filter(java.util.function.Predicate { o: ToolchainTypeInfo? -> platformToolchains.contains(o) })
                .count()
        }
    }
}
