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

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** [SkyFunction] that returns all registered execution platforms available.  */
class RegisteredExecutionPlatformsFunction : SkyFunction {
    @Throws(RegisteredExecutionPlatformsFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val key: com.google.devtools.build.lib.skyframe.toolchains.RegisteredExecutionPlatformsValue.Key =
            skyKey as com.google.devtools.build.lib.skyframe.toolchains.RegisteredExecutionPlatformsValue.Key
        val configuration: BuildConfigurationValue? =
            env.getValue(key.configurationKey()) as BuildConfigurationValue?
        val mainRepoMapping: RepositoryMappingValue? =
            env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN)) as RepositoryMappingValue?
        if (env.valuesMissing()) {
            return null
        }

        val mainRepoParser: TargetPattern.Parser =
            Parser(
                PathFragment.EMPTY_FRAGMENT, RepositoryName.MAIN, mainRepoMapping.repositoryMapping()
            )
        val targetPatternBuilder: com.google.common.collect.ImmutableList.Builder<SignedTargetPattern?> =
            com.google.common.collect.ImmutableList.Builder<SignedTargetPattern?>()

        // Get the execution platforms from the configuration.
        val platformConfiguration: PlatformConfiguration? =
            configuration.getFragment(PlatformConfiguration::class.java)
        if (platformConfiguration != null) {
            try {
                targetPatternBuilder.addAll(
                    TargetPatternUtil.parseAllSigned(
                        platformConfiguration.getExtraExecutionPlatforms(), mainRepoParser
                    )
                )
            } catch (e: InvalidTargetPatternException) {
                throw RegisteredExecutionPlatformsFunctionException(
                    InvalidExecutionPlatformLabelException(e), Transience.PERSISTENT
                )
            }
        }

        // Get registered execution platforms from the external dep graph.
        val bzlmodExecutionPlatforms: com.google.common.collect.ImmutableList<TargetPattern?>? =
            getBzlmodExecutionPlatforms(env)
        if (bzlmodExecutionPlatforms == null) {
            return null
        }
        targetPatternBuilder.addAll(TargetPatternUtil.toSigned(bzlmodExecutionPlatforms))

        // Expand target patterns.
        val platformLabels: com.google.common.collect.ImmutableSet<Label?>?
        try {
            platformLabels =
                TargetPatternUtil.expandTargetPatterns(
                    env, targetPatternBuilder.build(), HAS_PLATFORM_INFO
                )
            if (env.valuesMissing()) {
                return null
            }
        } catch (e: InvalidTargetPatternException) {
            throw RegisteredExecutionPlatformsFunctionException(
                InvalidExecutionPlatformLabelException(e), Transience.PERSISTENT
            )
        }

        // Load the configured target for each, and get the declared execution platforms providers.
        val registeredExecutionPlatforms: com.google.common.collect.ImmutableMap<ConfiguredTargetKey?, PlatformInfo?>? =
            configureRegisteredExecutionPlatforms(env, configuration, platformLabels)
        if (env.valuesMissing()) {
            return null
        }

        // Check which platforms are valid according to their configuration.
        val platformKeys: com.google.common.collect.ImmutableList.Builder<ConfiguredTargetKey?> =
            com.google.common.collect.ImmutableList.Builder<ConfiguredTargetKey?>()
        val rejectedPlatforms: com.google.common.collect.ImmutableMap.Builder<Label?, String?>? =
            if (key.debug()) com.google.common.collect.ImmutableMap.Builder<Label?, String?>() else null
        for (entry in registeredExecutionPlatforms.entrySet()) {
            val configuredTargetKey: ConfiguredTargetKey = entry.getKey()
            val platformInfo: PlatformInfo = entry.getValue()

            try {
                val errorHandler: java.util.function.Consumer<String?>? =
                    if (key.debug()) java.util.function.Consumer? { message: String? ->
                    rejectedPlatforms.put(
                        platformInfo.label(),
                        message
                    )
                } else null
                if (ConfigMatchingUtil.validate(
                        platformInfo.label(),
                        platformInfo.requiredSettings(),
                        errorHandler,
                        PlatformRule.REQUIRED_SETTINGS_ATTR
                    )
                ) {
                    platformKeys.add(configuredTargetKey)
                }
            } catch (e: InvalidConfigurationException) {
                throw RegisteredExecutionPlatformsFunctionException(
                    InvalidExecutionPlatformLabelException(platformInfo.label(), e),
                    Transience.PERSISTENT
                )
            }
        }

        return RegisteredExecutionPlatformsValue.Companion.create(
            platformKeys.build(),
            if (rejectedPlatforms != null) rejectedPlatforms.buildKeepingLast() else null
        )
    }

    /**
     * Used to indicate that the given [Label] represents a [ConfiguredTarget] which is
     * not a valid [PlatformInfo] provider.
     */
    internal class InvalidExecutionPlatformLabelException : ToolchainException, SaneAnalysisException {
        constructor(e: InvalidTargetPatternException) : this(e.getInvalidPattern(), e.getTpe())

        constructor(invalidPattern: String?, e: TargetParsingException) : super(
            java.lang.String.format(
                "invalid registered execution platform '%s': %s", invalidPattern, e.getMessage()
            ),
            e
        )

        constructor(platform: Label?, e: InvalidConfigurationException) : super(
            java.lang.String.format("invalid registered execution platform '%s': %s", platform, e.getMessage()),
            e
        )

        val detailedCode: Toolchain.Code
            get() = Toolchain.Code.INVALID_PLATFORM_VALUE

        val detailedExitCode: DetailedExitCode
            get() = DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(getMessage())
                    .setAnalysis(Analysis.newBuilder().setCode(Code.INVALID_EXECUTION_PLATFORM))
                    .build()
            )
    }

    /**
     * Used to declare all the exception types that can be wrapped in the exception thrown by [ ][.compute].
     */
    private class RegisteredExecutionPlatformsFunctionException : SkyFunctionException {
        private constructor(cause: InvalidExecutionPlatformLabelException?, transience: Transience?) : super(
            cause,
            transience
        )

        private constructor(cause: InvalidPlatformException?, transience: Transience?) : super(cause, transience)
    }

    companion object {
        @SerializationConstant
        val HAS_PLATFORM_INFO: FilteringPolicy = FilteringPolicy { target: Target?, explicit: Boolean ->
            explicit || PlatformLookupUtil.hasPlatformInfo(target)
        }

        @Throws(java.lang.InterruptedException::class, RegisteredExecutionPlatformsFunctionException::class)
        private fun getBzlmodExecutionPlatforms(env: SkyFunction.Environment): com.google.common.collect.ImmutableList<TargetPattern?>? {
            val bazelDepGraphValue: BazelDepGraphValue? =
                env.getValue(BazelDepGraphValue.KEY) as BazelDepGraphValue?
            if (bazelDepGraphValue == null) {
                return null
            }
            val executionPlatforms: com.google.common.collect.ImmutableList.Builder<TargetPattern?> =
                com.google.common.collect.ImmutableList.builder<TargetPattern?>()
            for (module in bazelDepGraphValue.depGraph.values()) {
                val parser: TargetPattern.Parser =
                    Parser(
                        PathFragment.EMPTY_FRAGMENT,
                        bazelDepGraphValue.canonicalRepoNameLookup.inverse().get(module.getKey()),
                        bazelDepGraphValue.getFullRepoMapping(module.getKey())
                    )
                for (pattern in module.getExecutionPlatformsToRegister()) {
                    try {
                        executionPlatforms.add(parser.parse(pattern))
                    } catch (e: TargetParsingException) {
                        throw RegisteredExecutionPlatformsFunctionException(
                            InvalidExecutionPlatformLabelException(pattern, e), Transience.PERSISTENT
                        )
                    }
                }
            }
            return executionPlatforms.build()
        }

        @Throws(java.lang.InterruptedException::class, RegisteredExecutionPlatformsFunctionException::class)
        private fun configureRegisteredExecutionPlatforms(
            env: SkyFunction.Environment, configuration: BuildConfigurationValue?, labels: MutableSet<Label?>
        ): com.google.common.collect.ImmutableMap<ConfiguredTargetKey?, PlatformInfo?>? {
            val keys: com.google.common.collect.ImmutableSet<ConfiguredTargetKey> =
                labels.stream()
                    .map<Any?>(
                        java.util.function.Function { label: Label? ->
                            ConfiguredTargetKey.builder()
                                .setLabel(label)
                                .setConfiguration(configuration)
                                .build()
                        })
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())

            val values: SkyframeLookupResult = env.getValuesAndExceptions(keys)
            val platforms: com.google.common.collect.ImmutableMap.Builder<ConfiguredTargetKey?, PlatformInfo?> =
                com.google.common.collect.ImmutableMap.Builder<ConfiguredTargetKey?, PlatformInfo?>()
            var valuesMissing = false
            for (platformKey in keys) {
                var platformKey: ConfiguredTargetKey = platformKey
                var platformLabel: Label? = platformKey.getLabel()
                try {
                    val value: SkyValue? =
                        values.getOrThrow<E?>(platformKey, ConfiguredValueCreationException::class.java)
                    if (value == null) {
                        valuesMissing = true
                        continue
                    }
                    val target: ConfiguredTarget = (value as ConfiguredTargetValue).getConfiguredTarget()
                    val platformInfo: PlatformInfo = PlatformProviderUtils.platform(target)
                    if (platformInfo == null) {
                        throw RegisteredExecutionPlatformsFunctionException(
                            InvalidPlatformException(platformLabel), Transience.PERSISTENT
                        )
                    }

                    // Update the key so that any aliases are resolved.
                    platformLabel = target.getLabel()
                    platformKey =
                        ConfiguredTargetKey.builder()
                            .setLabel(platformLabel)
                            .setConfigurationKey(BuildConfigurationKey.create(CommonOptions.EMPTY_OPTIONS))
                            .build()

                    platforms.put(platformKey, platformInfo)
                } catch (e: ConfiguredValueCreationException) {
                    throw RegisteredExecutionPlatformsFunctionException(
                        InvalidPlatformException(platformLabel, e), Transience.PERSISTENT
                    )
                }
            }

            if (valuesMissing) {
                return null
            }
            return platforms.buildOrThrow()
        }
    }
}
