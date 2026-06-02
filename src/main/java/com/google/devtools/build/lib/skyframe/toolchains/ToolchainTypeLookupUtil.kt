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

import com.google.devtools.build.lib.actions.ActionConflictException

/** Helper class that looks up [ToolchainTypeInfo] data.  */
object ToolchainTypeLookupUtil {
    @Throws(java.lang.InterruptedException::class, InvalidToolchainTypeException::class)
    fun resolveToolchainTypes(
        env: SkyFunction.Environment,
        toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>,
        configuration: BuildConfigurationValue?
    ): com.google.common.collect.ImmutableMap<Label?, ToolchainTypeInfo?>? {
        val toolchainTypesByKey: com.google.common.collect.ImmutableMap<ConfiguredTargetKey?, ToolchainTypeRequirement?> =
            toolchainTypes.stream()
                .collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                        java.util.function.Function { toolchainTypeRequirement: Any? ->
                            ConfiguredTargetKey.builder()
                                .setLabel(toolchainTypeRequirement.toolchainType())
                                .setConfiguration(configuration)
                                .build()
                        },
                        java.util.function.Function { toolchainTypeRequirement: Any? -> toolchainTypeRequirement })
                )

        val values: SkyframeLookupResult = env.getValuesAndExceptions(toolchainTypesByKey.keySet())
        var valuesMissing: Boolean = env.valuesMissing()
        val results: SequencedMap<Label?, ToolchainTypeInfo?>? =
            if (valuesMissing) null else LinkedHashMap<Label?, ToolchainTypeInfo?>()
        for (entry in toolchainTypesByKey.entrySet()) {
            val key: ConfiguredTargetKey = entry.getKey()
            val toolchainTypeRequirement: ToolchainTypeRequirement = entry.getValue()

            val originalLabel: Label? = key.getLabel()
            val toolchainTypeInfo: java.util.Optional<ToolchainTypeInfo?>? =
                findToolchainTypeInfo(toolchainTypeRequirement, key, values)
            if (toolchainTypeInfo == null) {
                // Continue processing to find errors, but note that we didn't succeed.
                valuesMissing = true
                continue
            }
            if (!valuesMissing) {
                toolchainTypeInfo.ifPresent(
                    java.util.function.Consumer { info: ToolchainTypeInfo? ->
                        // These are only different if the toolchain type was aliased.
                        results.put(originalLabel, info)
                        results.put(info.typeLabel(), info)
                    })
            }
        }
        if (valuesMissing) {
            return null
        }

        return com.google.common.collect.ImmutableMap.copyOf<Label?, ToolchainTypeInfo?>(results)
    }

    /**
     * Returns `null` to signal a Skyframe restart, an `Optional.empty` if the toolchain
     * type is invalid but ignored, and a populated [Optional] with the toolchain type info
     * otherwise.
     */
    @Throws(InvalidToolchainTypeException::class)
    private fun findToolchainTypeInfo(
        toolchainTypeRequirement: ToolchainTypeRequirement,
        key: ConfiguredTargetKey,
        values: SkyframeLookupResult
    ): java.util.Optional<ToolchainTypeInfo?>? {
        try {
            val ctv: ConfiguredTargetValue? =
                values.getOrThrow<E1?, E2?, E3?>(
                    key,
                    ConfiguredValueCreationException::class.java,
                    NoSuchThingException::class.java,
                    ActionConflictException::class.java
                ) as ConfiguredTargetValue?
            if (ctv == null) {
                return null
            }

            val configuredTarget: ConfiguredTarget = ctv.getConfiguredTarget()
            val toolchainTypeInfo: ToolchainTypeInfo? = PlatformProviderUtils.toolchainType(configuredTarget)
            if (toolchainTypeInfo == null && !toolchainTypeRequirement.ignoreIfInvalid()) {
                if (PlatformProviderUtils.declaredToolchainInfo(configuredTarget) != null) {
                    throw InvalidToolchainTypeException(
                        configuredTarget.getLabel(),
                        ("is a toolchain instance. Is the rule definition for the target you're building "
                                + "setting \"toolchains =\" to a toolchain() instead of the expected "
                                + "toolchain_type()?")
                    )
                }
                throw InvalidToolchainTypeException(configuredTarget.getLabel())
            }

            if (toolchainTypeInfo == null) {
                return java.util.Optional.empty<ToolchainTypeInfo?>()
            }
            return java.util.Optional.of<ToolchainTypeInfo?>(toolchainTypeInfo)
        } catch (e: ConfiguredValueCreationException) {
            throw InvalidToolchainTypeException(e)
        } catch (e: NoSuchThingException) {
            throw InvalidToolchainTypeException(e)
        } catch (e: ActionConflictException) {
            throw InvalidToolchainTypeException(key.getLabel(), e)
        }
    }

    /** Exception used when a toolchain type label is not a valid toolchain type.  */
    class InvalidToolchainTypeException : ToolchainException {
        internal constructor(label: Label?) : super(formatError(label, DEFAULT_ERROR))

        internal constructor(e: ConfiguredValueCreationException?) : super(e)

        constructor(e: NoSuchThingException?) : super(e)

        constructor(label: Label?, e: ActionConflictException?) : super(formatError(label, DEFAULT_ERROR), e)

        internal constructor(label: Label?, error: String?) : super(formatError(label, error))

        val detailedCode: Code
            get() = Code.INVALID_TOOLCHAIN_TYPE

        companion object {
            private const val DEFAULT_ERROR = "does not provide ToolchainTypeInfo"

            private fun formatError(label: Label?, error: String?): String? {
                return java.lang.String.format("Target %s was referenced as a toolchain type, but %s", label, error)
            }
        }
    }
}
