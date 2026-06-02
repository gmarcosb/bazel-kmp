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

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * [SkyFunction] that returns all registered toolchains available for toolchain resolution.
 */
class RegisteredToolchainsFunction : SkyFunction {
    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val key: com.google.devtools.build.lib.skyframe.toolchains.RegisteredToolchainsValue.Key =
            skyKey as com.google.devtools.build.lib.skyframe.toolchains.RegisteredToolchainsValue.Key
        val configuration: BuildConfigurationValue? =
            env.getValue(key.getConfigurationKey()) as BuildConfigurationValue?
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

        // Get the toolchains from the configuration.
        // Reverse the list so the last one defined takes precedences.
        val platformConfiguration: PlatformConfiguration =
            configuration.getFragment(PlatformConfiguration::class.java)
        try {
            targetPatternBuilder.addAll(
                TargetPatternUtil.parseAllSigned(
                    platformConfiguration.getExtraToolchains().reverse(), mainRepoParser
                )
            )
        } catch (e: InvalidTargetPatternException) {
            throw RegisteredToolchainsFunctionException(
                InvalidToolchainLabelException(e), Transience.PERSISTENT
            )
        }

        // Get registered toolchains from the external dep graph.
        val bzlmodToolchains: com.google.common.collect.ImmutableList<TargetPattern?>? = getBzlmodToolchains(env)
        if (bzlmodToolchains == null) {
            return null
        }
        targetPatternBuilder.addAll(TargetPatternUtil.toSigned(bzlmodToolchains))

        // Expand target patterns.
        val toolchainLabels: com.google.common.collect.ImmutableSet<Label?>?
        try {
            toolchainLabels =
                TargetPatternUtil.expandTargetPatterns(
                    env, targetPatternBuilder.build(), FilteringPolicies.ruleTypeExplicit("toolchain")
                )
            if (env.valuesMissing()) {
                return null
            }
        } catch (e: InvalidTargetPatternException) {
            throw RegisteredToolchainsFunctionException(
                InvalidToolchainLabelException(e), Transience.PERSISTENT
            )
        }

        // Load the configured target for each, and get the declared toolchain providers.
        val registeredToolchains: com.google.common.collect.ImmutableList<DeclaredToolchainInfo>? =
            configureRegisteredToolchains(env, configuration, toolchainLabels)
        if (env.valuesMissing()) {
            return null
        }

        // Check which toolchains are valid according to their configuration.
        val validToolchains: com.google.common.collect.ImmutableList.Builder<DeclaredToolchainInfo?> =
            com.google.common.collect.ImmutableList.Builder<DeclaredToolchainInfo?>()
        // Some toolchains end up with repeated reasons, so use a HashBasedTable to handle duplicates.
        val rejectedToolchains: com.google.common.collect.Table<Label?, Label?, String?>? =
            if (key.debug()) com.google.common.collect.HashBasedTable.create<Label?, Label?, String?>() else null
        for (toolchain in registeredToolchains) {
            try {
                val errorHandler: java.util.function.Consumer<String?>? =
                    if (key.debug())
                        java.util.function.Consumer? { message: String? ->
                    rejectedToolchains.put(
                        toolchain.toolchainType().typeLabel(), toolchain.targetLabel(), message
                    )
                }
                else
                null
                if (ConfigMatchingUtil.validate(
                        toolchain.targetLabel(),
                        toolchain.targetSettings(),
                        errorHandler,
                        ToolchainRule.TARGET_SETTING_ATTR
                    )
                ) {
                    validToolchains.add(toolchain)
                }
            } catch (e: InvalidConfigurationException) {
                throw RegisteredToolchainsFunctionException(
                    InvalidToolchainLabelException(toolchain.targetLabel(), e), Transience.PERSISTENT
                )
            }
        }

        return RegisteredToolchainsValue.Companion.create(
            validToolchains.build(),
            if (rejectedToolchains != null) com.google.common.collect.ImmutableTable.copyOf<Label?, Label?, String?>(
                rejectedToolchains
            ) else null
        )
    }

    /**
     * Used to indicate that the given [Label] represents a [ConfiguredTarget] which is
     * not a valid [DeclaredToolchainInfo] provider.
     */
    class InvalidToolchainLabelException : ToolchainException {
        constructor(invalidLabel: Label) : super(
            formatMessage(
                invalidLabel.getCanonicalForm(),
                "target does not provide the DeclaredToolchainInfo provider"
            )
        )

        constructor(e: InvalidTargetPatternException) : this(e.getInvalidPattern(), e.getTpe())

        constructor(invalidPattern: String?, e: TargetParsingException) : super(
            formatMessage(
                invalidPattern,
                e.getMessage()
            ), e
        )

        constructor(
            invalidLabel: Label,
            e: ConfiguredValueCreationException
        ) : super(formatMessage(invalidLabel.getCanonicalForm(), e.getMessage()), e)

        constructor(
            invalidLabel: Label,
            e: InvalidConfigurationException
        ) : super(formatMessage(invalidLabel.getCanonicalForm(), e.getMessage()), e)

        val detailedCode: Code
            get() = Code.INVALID_TOOLCHAIN

        companion object {
            private fun formatMessage(invalidPattern: String?, reason: String?): String? {
                return java.lang.String.format("invalid registered toolchain '%s': %s", invalidPattern, reason)
            }
        }
    }

    /**
     * Used to declare all the exception types that can be wrapped in the exception thrown by [ ][.compute].
     */
    class RegisteredToolchainsFunctionException : SkyFunctionException {
        constructor(cause: InvalidToolchainLabelException?, transience: Transience?) : super(cause, transience)

        constructor(cause: ExternalDepsException?, transience: Transience?) : super(cause, transience)
    }

    companion object {
        @Throws(java.lang.InterruptedException::class, RegisteredToolchainsFunctionException::class)
        private fun getBzlmodToolchains(env: SkyFunction.Environment): com.google.common.collect.ImmutableList<TargetPattern?>? {
            val bazelDepGraphValue: BazelDepGraphValue? =
                env.getValue(BazelDepGraphValue.KEY) as BazelDepGraphValue?
            if (bazelDepGraphValue == null) {
                return null
            }
            val toolchains: com.google.common.collect.ImmutableList.Builder<TargetPattern?> =
                com.google.common.collect.ImmutableList.builder<TargetPattern?>()
            for (module in bazelDepGraphValue.depGraph.values()) {
                val parser: TargetPattern.Parser =
                    Parser(
                        PathFragment.EMPTY_FRAGMENT,
                        bazelDepGraphValue.canonicalRepoNameLookup.inverse().get(module.getKey()),
                        bazelDepGraphValue.getFullRepoMapping(module.getKey())
                    )
                for (pattern in module.getToolchainsToRegister()) {
                    try {
                        toolchains.add(parser.parse(pattern))
                    } catch (e: TargetParsingException) {
                        throw RegisteredToolchainsFunctionException(
                            InvalidToolchainLabelException(pattern, e), Transience.PERSISTENT
                        )
                    }
                }
            }
            return toolchains.build()
        }

        @Throws(java.lang.InterruptedException::class, RegisteredToolchainsFunctionException::class)
        private fun configureRegisteredToolchains(
            env: SkyFunction.Environment, configuration: BuildConfigurationValue?, labels: MutableSet<Label?>
        ): com.google.common.collect.ImmutableList<DeclaredToolchainInfo>? {
            val keys: com.google.common.collect.ImmutableSet<ActionLookupKey> =
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
            val toolchains: com.google.common.collect.ImmutableList.Builder<DeclaredToolchainInfo?> =
                com.google.common.collect.ImmutableList.Builder<DeclaredToolchainInfo?>()
            var valuesMissing = false
            for (key in keys) {
                val toolchainLabel: Label = key.getLabel()
                try {
                    val value: SkyValue? = values.getOrThrow<E?>(key, ConfiguredValueCreationException::class.java)
                    if (value == null) {
                        valuesMissing = true
                        continue
                    }

                    val target: ConfiguredTarget? = (value as ConfiguredTargetValue).getConfiguredTarget()
                    val toolchainInfo: DeclaredToolchainInfo = PlatformProviderUtils.declaredToolchainInfo(target)
                    if (toolchainInfo == null) {
                        throw RegisteredToolchainsFunctionException(
                            InvalidToolchainLabelException(toolchainLabel), Transience.PERSISTENT
                        )
                    }
                    toolchains.add(toolchainInfo)
                } catch (e: ConfiguredValueCreationException) {
                    throw RegisteredToolchainsFunctionException(
                        InvalidToolchainLabelException(toolchainLabel, e), Transience.PERSISTENT
                    )
                }
            }

            if (valuesMissing) {
                return null
            }
            return toolchains.build()
        }
    }
}
