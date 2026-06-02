// Copyright 2024 The Bazel Authors. All rights reserved.
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

/** Details of platforms used during toolchain resolution.  */
internal class PlatformKeys(
    targetPlatformKey: ConfiguredTargetKey?,
    executionPlatformKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey>?,
    platformInfos: com.google.common.collect.ImmutableMap<ConfiguredTargetKey?, PlatformInfo?>?
) {
    private class Builder(
        environment: SkyFunction.Environment,
        debugPrinter: ToolchainResolutionDebugPrinter,
        configurationKey: BuildConfigurationKey?,
        platformConfiguration: PlatformConfiguration
    ) {
        // Input data.
        private val environment: SkyFunction.Environment
        private val debugPrinter: ToolchainResolutionDebugPrinter
        private val configurationKey: BuildConfigurationKey?

        // Internal state used during loading.
        private val hostPlatformLabel: Label?
        private val targetPlatformLabel: Label?
        private var hostPlatformKey: ConfiguredTargetKey? = null
        private var executionPlatformKeys: LinkedHashSet<ConfiguredTargetKey?>? = null
        private var platformInfos: MutableMap<ConfiguredTargetKey?, PlatformInfo?>? = null

        init {
            this.environment = environment
            this.debugPrinter = debugPrinter
            this.configurationKey = configurationKey

            this.hostPlatformLabel = platformConfiguration.getHostPlatform()
            this.targetPlatformLabel = platformConfiguration.getTargetPlatform()
        }

        @Throws(
            java.lang.InterruptedException::class,
            ValueMissingException::class,
            InvalidPlatformException::class,
            InvalidExecutionPlatformLabelException::class,
            InvalidConstraintValueException::class
        )
        fun build(execConstraintLabels: com.google.common.collect.ImmutableSet<Label?>): PlatformKeys {
            // Determine the execution platform keys.

            findExecutionPlatformKeys()

            // Load the PlatformInfo for all relevant platforms (target, host, exec).
            loadPlatformInfo()

            // Update platform keys to resolve possible aliases.
            val resolvedTargetPlatformKey: ConfiguredTargetKey? =
                updateConfiguredTargetKey(targetPlatformLabel)
            val resolvedHostPlatformKey: ConfiguredTargetKey? = updateConfiguredTargetKey(hostPlatformLabel)
            if (!hostPlatformKey.equals(resolvedHostPlatformKey)) {
                // Replace the host platform key in the execution platform keys with the updated version.
                this.executionPlatformKeys.remove(this.hostPlatformKey)
                this.executionPlatformKeys.add(resolvedHostPlatformKey)
                this.hostPlatformKey = resolvedHostPlatformKey
            }

            // Ensure all platforms are present in platformInfos by original key and by alias-resolved
            // key, if they are different.
            val updated: MutableMap<ConfiguredTargetKey?, PlatformInfo?> =
                HashMap<ConfiguredTargetKey?, PlatformInfo?>()
            for (entry in this.platformInfos.entrySet()) {
                val originalKey: ConfiguredTargetKey = entry.getKey()
                val resolvedKey: ConfiguredTargetKey? = updateConfiguredTargetKey(originalKey.getLabel())
                if (!originalKey.equals(resolvedKey)) {
                    updated.put(resolvedKey, entry.getValue())
                }
            }
            this.platformInfos!!.putAll(updated)

            // Filter the execution platforms, based on the applied constraints (if any).
            val executionPlatformKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey> =
                filterExecutionPlatforms(execConstraintLabels)

            return PlatformKeys(
                resolvedTargetPlatformKey,
                executionPlatformKeys,
                com.google.common.collect.ImmutableMap.copyOf<ConfiguredTargetKey?, PlatformInfo?>(platformInfos)
            )
        }

        @Throws(
            java.lang.InterruptedException::class,
            ValueMissingException::class,
            InvalidPlatformException::class,
            InvalidExecutionPlatformLabelException::class
        )
        fun findExecutionPlatformKeys() {
            // Find the registered execution platforms.
            val registeredExecutionPlatforms: RegisteredExecutionPlatformsValue? =
                environment.getValueOrThrow<InvalidPlatformException?, InvalidExecutionPlatformLabelException?>(
                    RegisteredExecutionPlatformsValue.Companion.key(
                        configurationKey, debugPrinter.debugEnabled()
                    ),
                    InvalidPlatformException::class.java,
                    InvalidExecutionPlatformLabelException::class.java
                ) as RegisteredExecutionPlatformsValue?
            if (registeredExecutionPlatforms == null) {
                throw ValueMissingException()
            }

            // If debugging, describe rejected execution platforms.
            java.util.Optional.ofNullable<com.google.common.collect.ImmutableMap<Label?, String?>?>(
                registeredExecutionPlatforms.rejectedPlatforms
            )
                .filter(
                    com.google.common.base.Predicates.not<com.google.common.collect.ImmutableMap<Label?, String?>?>(
                        com.google.common.base.Predicate { obj: com.google.common.collect.ImmutableMap<Label?, kotlin.String?>? -> obj.isEmpty() })
                )
                .ifPresent(java.util.function.Consumer { rejectedExecutionPlatforms: com.google.common.collect.ImmutableMap<Label?, kotlin.String?>? ->
                    debugPrinter.reportRejectedExecutionPlatforms(
                        rejectedExecutionPlatforms
                    )
                })

            // A given execution platform may be registered multiple times, but only the earliest
            // registration is meaningful in practice. To avoid unnecessary reprocessing a LinkedHashSet
            // is used to de-duplicate while preserving order.
            this.executionPlatformKeys = LinkedHashSet<ConfiguredTargetKey?>()
            executionPlatformKeys.addAll(registeredExecutionPlatforms.registeredExecutionPlatformKeys)
            this.hostPlatformKey =
                ConfiguredTargetKey.builder()
                    .setLabel(hostPlatformLabel)
                    .setConfigurationKey(BuildConfigurationKey.create(CommonOptions.EMPTY_OPTIONS))
                    .build()
            executionPlatformKeys.add(this.hostPlatformKey)
        }

        @Throws(java.lang.InterruptedException::class, ValueMissingException::class, InvalidPlatformException::class)
        fun loadPlatformInfo() {
            val platformKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey?> =
                com.google.common.collect.ImmutableList.Builder<ConfiguredTargetKey?>()
                    .add(
                        ConfiguredTargetKey.builder()
                            .setLabel(targetPlatformLabel)
                            .setConfigurationKey(
                                BuildConfigurationKey.create(CommonOptions.EMPTY_OPTIONS)
                            )
                            .build()
                    )
                    .addAll(this.executionPlatformKeys)
                    .build()

            this.platformInfos = PlatformLookupUtil.getPlatformInfo(platformKeys, environment)
            if (environment.valuesMissing()) {
                throw ValueMissingException()
            }
        }

        fun updateConfiguredTargetKey(platformLabel: Label?): ConfiguredTargetKey? {
            val platformInfo: java.util.Optional<PlatformInfo?> =
                platformInfos.entrySet().stream()
                    .filter(java.util.function.Predicate { entry: MutableMap.MutableEntry<ConfiguredTargetKey?, PlatformInfo?>? ->
                        entry.getKey().getLabel().equals(platformLabel)
                    })
                    .map<PlatformInfo?>(java.util.function.Function { obj: MutableMap.MutableEntry<ConfiguredTargetKey?, PlatformInfo?>? -> obj.getValue() })
                    .findFirst()
            return platformInfo
                .map<Any?>(
                    java.util.function.Function { info: PlatformInfo? ->
                        ConfiguredTargetKey.builder()
                            .setLabel(info.label())
                            .setConfigurationKey(
                                BuildConfigurationKey.create(CommonOptions.EMPTY_OPTIONS)
                            )
                            .build()
                    })
                .orElse(null)
        }

        @Throws(
            java.lang.InterruptedException::class,
            ValueMissingException::class,
            InvalidConstraintValueException::class
        )
        fun filterExecutionPlatforms(
            execConstraintLabels: com.google.common.collect.ImmutableSet<Label?>
        ): com.google.common.collect.ImmutableList<ConfiguredTargetKey> {
            // Short circuit if not needed.

            if (execConstraintLabels.isEmpty()) {
                return com.google.common.collect.ImmutableList.copyOf<ConfiguredTargetKey?>(executionPlatformKeys)
            }

            // Filter out execution platforms that don't satisfy the extra constraints.
            val execConstraintKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey?> =
                execConstraintLabels.stream()
                    .map<Any?>(
                        java.util.function.Function { label: Label? ->
                            ConfiguredTargetKey.builder()
                                .setLabel(label)
                                .setConfigurationKey(
                                    BuildConfigurationKey.create(CommonOptions.EMPTY_OPTIONS)
                                )
                                .build()
                        })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())

            val constraints: MutableList<ConstraintValueInfo?>? =
                ConstraintValueLookupUtil.getConstraintValueInfo(execConstraintKeys, environment)
            if (constraints == null) {
                throw ValueMissingException()
            }

            return executionPlatformKeys.stream()
                .filter(java.util.function.Predicate { key: ConfiguredTargetKey? ->
                    filterPlatform(
                        platformInfos!!.get(
                            key
                        ), constraints
                    )
                })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<ConfiguredTargetKey?>())
        }

        /** Returns `true` if the given platform has all of the constraints.  */
        fun filterPlatform(
            platformInfo: PlatformInfo, constraints: MutableList<ConstraintValueInfo?>?
        ): Boolean {
            val missingConstraints: com.google.common.collect.ImmutableList<ConstraintValueInfo?> =
                platformInfo.constraints().findMissing(constraints)
            debugPrinter.reportRemovedExecutionPlatform(platformInfo.label(), missingConstraints)

            return missingConstraints.isEmpty()
        }
    }

    fun find(platformLabel: Label): ConfiguredTargetKey? {
        if (platformLabel.equals(targetPlatformKey.getLabel())) {
            return this.targetPlatformKey
        }

        for (configuredTargetKey in executionPlatformKeys) {
            if (platformLabel.equals(configuredTargetKey.getLabel())) {
                return configuredTargetKey
            }
        }

        return null
    }

    fun targetPlatformInfo(): PlatformInfo? {
        return platformInfo(targetPlatformKey)
    }

    fun platformInfo(configuredTargetKey: ConfiguredTargetKey?): PlatformInfo? {
        return platformInfos.get(configuredTargetKey)
    }

    fun isPlatformSuitable(
        executionPlatformKey: ConfiguredTargetKey?,
        toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainType?>,
        resolvedToolchains: com.google.common.collect.Table<ConfiguredTargetKey?, ToolchainTypeInfo?, Label?>,
        checkAllowedToolchainTypes: Boolean
    ): Boolean {
        val executionPlatformInfo: PlatformInfo? = platformInfo(executionPlatformKey)
        if (checkAllowedToolchainTypes
            && executionPlatformInfo.checkToolchainTypes()
            && toolchainTypes.isEmpty()
        ) {
            // This can't be suitable.
            return false
        } else if (toolchainTypes.isEmpty()) {
            // Since there aren't any toolchains, we should be able to use any execution platform that
            // has made it this far.
            return true
        }

        // Determine whether all mandatory toolchains are present.
        return resolvedToolchains
            .row(executionPlatformKey)
            .keySet()
            .containsAll(
                toolchainTypes.stream()
                    .filter(java.util.function.Predicate { mandatory() })
                    .map<ToolchainTypeInfo?>(ToolchainType::toolchainTypeInfo)
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<ToolchainTypeInfo?>())
            )
    }

    val targetPlatformKey: ConfiguredTargetKey?
    val executionPlatformKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey>?
    val platformInfos: com.google.common.collect.ImmutableMap<ConfiguredTargetKey?, PlatformInfo?>?

    init {
        this.targetPlatformKey = targetPlatformKey
        this.executionPlatformKeys = executionPlatformKeys
        this.platformInfos = platformInfos
    }

    companion object {
        @Throws(
            java.lang.InterruptedException::class,
            ValueMissingException::class,
            InvalidConstraintValueException::class,
            InvalidPlatformException::class,
            InvalidExecutionPlatformLabelException::class
        )
        fun load(
            environment: SkyFunction.Environment,
            debugPrinter: ToolchainResolutionDebugPrinter,
            configurationKey: BuildConfigurationKey?,
            platformConfiguration: PlatformConfiguration,
            execConstraintLabels: com.google.common.collect.ImmutableSet<Label?>
        ): PlatformKeys {
            return com.google.devtools.build.lib.skyframe.toolchains.PlatformKeys.Builder(
                environment,
                debugPrinter,
                configurationKey,
                platformConfiguration
            )
                .build(execConstraintLabels)
        }
    }
}
