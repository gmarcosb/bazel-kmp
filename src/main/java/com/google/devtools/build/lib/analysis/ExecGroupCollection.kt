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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.server.FailureDetails.Analysis

/**
 * A container class for groups of [DeclaredExecGroup] instances. This correctly handles exec
 * group inheritance between rules and targets. See https://bazel.build/reference/exec-groups for
 * further details.
 */
@AutoValue
abstract class ExecGroupCollection {
    /** Builder class for correctly constructing ExecGroupCollection instances.  */ // Note that this is _not_ an actual @AutoValue.Builder: it provides more logic and has different
    // fields.
    abstract class Builder {
        abstract fun execGroups(): com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>?

        @Throws(InvalidExecGroupException::class)
        fun build(
            toolchainContexts: ToolchainCollection<ResolvedToolchainContext?>?,
            rawExecProperties: com.google.common.collect.ImmutableMap<String?, String?>,
            targetLabel: String?
        ): ExecGroupCollection {
            // For each exec group, compute the combined execution properties.

            val combinedExecProperties: com.google.common.collect.ImmutableTable<String?, String?, String?> =
                computeCombinedExecProperties(toolchainContexts, rawExecProperties, targetLabel)

            return AutoValue_ExecGroupCollection(execGroups(), combinedExecProperties)
        }
    }

    abstract fun execGroups(): com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>?

    protected abstract fun execProperties(): com.google.common.collect.ImmutableTable<String?, String?, String?>?

    fun getExecGroup(execGroupName: String?): DeclaredExecGroup? {
        return execGroups().get(execGroupName)
    }

    fun getExecProperties(execGroupName: String?): com.google.common.collect.ImmutableMap<String?, String?> {
        return execProperties().row(execGroupName)
    }

    /** An error for when the user tries to access a non-existent exec group.  */
    class InvalidExecGroupException(
        what: String?,
        targetLabel: String?,
        invalidNames: MutableCollection<String?>,
        validNames: Iterable<String?>?
    ) : AbstractSaneAnalysisException(
        String.format(
            "Tried to set %s for non-existent exec groups on %s: %s%s",
            what,
            targetLabel,
            java.lang.String.join(",", invalidNames),
            invalidNames.stream()
                .map<String?> { invalidName: String? ->
                    net.starlark.java.spelling.SpellChecker.didYouMean(
                        invalidName,
                        validNames
                    )
                }
                .filter { s: String? -> !s.isEmpty() }
                .findFirst()
                .orElse(""))) {
        override fun getDetailedExitCode(): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setAnalysis(Analysis.newBuilder().setCode(Code.EXEC_GROUP_MISSING))
                    .build()
            )
        }
    }

    companion object {
        /**
         * Gets the combined exec properties of the platform and the target's exec properties. If a
         * property is set in both, the target properties take precedence.
         */
        @Throws(InvalidExecGroupException::class)
        private fun computeCombinedExecProperties(
            toolchainContexts: ToolchainCollection<ResolvedToolchainContext?>?,
            rawExecProperties: com.google.common.collect.ImmutableMap<String?, String?>,
            targetLabel: String?
        ): com.google.common.collect.ImmutableTable<String?, String?, String?> {
            val execGroupNames: com.google.common.collect.ImmutableSet<String?>?
            if (toolchainContexts == null) {
                execGroupNames =
                    com.google.common.collect.ImmutableSet.of<String?>(DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME)
            } else {
                execGroupNames = toolchainContexts.getExecGroupNames()
            }

            // Parse the target-level exec properties.
            val parsedTargetProperties: com.google.common.collect.ImmutableTable<String?, String?, String?> =
                parseExecProperties(rawExecProperties)
            // Validate the exec group names in the properties.
            if (toolchainContexts != null) {
                val unknownTargetExecGroupNames: com.google.common.collect.ImmutableSet<String?> =
                    parsedTargetProperties.rowKeySet().stream()
                        .filter { name: String? -> name != DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME }
                        .filter { name: String? -> !execGroupNames.contains(name) }
                        .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())
                if (!unknownTargetExecGroupNames.isEmpty()) {
                    throw InvalidExecGroupException(
                        "exec_properties",
                        targetLabel,
                        unknownTargetExecGroupNames,
                        com.google.common.collect.Iterables.concat<String?>(
                            execGroupNames,
                            com.google.common.collect.ImmutableSet.of<String?>(DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME)
                        )
                    )
                }
            }

            // Parse each execution platform's exec properties.
            val executionPlatforms: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.analysis.platform.PlatformInfo>?
            if (toolchainContexts == null) {
                executionPlatforms =
                    com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.analysis.platform.PlatformInfo?>()
            } else {
                executionPlatforms =
                    execGroupNames.stream()
                        .map<com.google.devtools.build.lib.analysis.platform.PlatformInfo?> { name: String? ->
                            toolchainContexts.getToolchainContext(
                                name
                            ).executionPlatform()
                        }
                        .distinct()
                        .collect(com.google.common.collect.ImmutableSet.toImmutableSet<com.google.devtools.build.lib.analysis.platform.PlatformInfo?>())
            }
            val parsedPlatformProperties: MutableMap<com.google.devtools.build.lib.analysis.platform.PlatformInfo?, com.google.common.collect.ImmutableTable<String?, String?, String?>?> =
                LinkedHashMap<com.google.devtools.build.lib.analysis.platform.PlatformInfo?, com.google.common.collect.ImmutableTable<String?, String?, String?>?>()
            for (executionPlatform in executionPlatforms) {
                val parsed: com.google.common.collect.ImmutableTable<String?, String?, String?> =
                    parseExecProperties(executionPlatform.execProperties())
                parsedPlatformProperties.put(executionPlatform, parsed)
            }

            // First, get the defaults.
            val defaultExecProperties: com.google.common.collect.ImmutableMap<String?, String?> =
                parsedTargetProperties.row(DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME)
            val result: com.google.common.collect.Table<String?, String?, String?> =
                com.google.common.collect.HashBasedTable.create<String?, String?, String?>()
            Companion.putAll<String?, String?, String?>(
                result,
                DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME,
                defaultExecProperties
            )

            for (execGroupName in execGroupNames) {
                val combined: com.google.common.collect.ImmutableMap<String?, String?> =
                    computeProperties(
                        execGroupName,
                        defaultExecProperties,
                        toolchainContexts,
                        parsedPlatformProperties,
                        parsedTargetProperties
                    )
                Companion.putAll<String?, String?, String?>(result, execGroupName, combined)
            }

            return com.google.common.collect.ImmutableTable.copyOf<String?, String?, String?>(result)
        }

        private fun <R, C, V> putAll(
            builder: com.google.common.collect.Table<R?, C?, V?>,
            row: R?,
            values: MutableMap<C?, V?>
        ) {
            for (entry in values.entries) {
                builder.put(row, entry.key, entry.value)
            }
        }

        private fun computeProperties(
            execGroupName: String?,
            defaultExecProperties: com.google.common.collect.ImmutableMap<String?, String?>,
            toolchainContexts: ToolchainCollection<ResolvedToolchainContext?>?,
            parsedPlatformProperties: MutableMap<com.google.devtools.build.lib.analysis.platform.PlatformInfo?, com.google.common.collect.ImmutableTable<String?, String?, String?>?>,
            parsedTargetProperties: com.google.common.collect.ImmutableTable<String?, String?, String?>
        ): com.google.common.collect.ImmutableMap<String?, String?> {
            val defaultExecGroupPlatformProperties: com.google.common.collect.ImmutableMap<String?, String?>?
            val platformProperties: com.google.common.collect.ImmutableMap<String?, String?>?
            if (toolchainContexts == null) {
                defaultExecGroupPlatformProperties = com.google.common.collect.ImmutableMap.of<String?, String?>()
                platformProperties = com.google.common.collect.ImmutableMap.of<String?, String?>()
            } else {
                val executionPlatform: com.google.devtools.build.lib.analysis.platform.PlatformInfo? =
                    toolchainContexts.getToolchainContext(execGroupName).executionPlatform()
                defaultExecGroupPlatformProperties =
                    parsedPlatformProperties.get(executionPlatform).row(DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME)
                platformProperties = parsedPlatformProperties.get(executionPlatform).row(execGroupName)
            }
            val targetProperties: MutableMap<String?, String?> =
                LinkedHashMap<String?, String?>(parsedTargetProperties.row(execGroupName))
            for (propertyName in defaultExecProperties.keys) {
                // If the property exists in the default and not in the target, copy it.
                targetProperties.computeIfAbsent(propertyName) { key: String? -> defaultExecProperties.get(key) }
            }

            // Combine the target and exec platform properties. Target properties take precedence.
            // Use a HashMap instead of an ImmutableMap.Builder because we expect duplicate keys.
            val combined: MutableMap<String?, String?> = LinkedHashMap<String?, String?>()
            combined.putAll(defaultExecGroupPlatformProperties)
            combined.putAll(defaultExecProperties)
            combined.putAll(platformProperties)
            combined.putAll(targetProperties)
            return com.google.common.collect.ImmutableMap.copyOf<String?, String?>(combined)
        }

        /**
         * Parse raw exec properties attribute value into a map of exec group names to their properties.
         * The raw map can have keys of two forms: (1) 'property' and (2) 'exec_group_name.property'. The
         * former get parsed into the default exec group, the latter get parsed into their relevant exec
         * groups.
         */
        private fun parseExecProperties(
            rawExecProperties: MutableMap<String?, String?>
        ): com.google.common.collect.ImmutableTable<String?, String?, String?> {
            val execProperties: com.google.common.collect.ImmutableTable.Builder<String?, String?, String?> =
                com.google.common.collect.ImmutableTable.builder<String?, String?, String?>()
            for (execProperty in rawExecProperties.entries) {
                val rawProperty: String = execProperty.key!!
                val delimiterIndex: Int = rawProperty.indexOf('.')
                if (delimiterIndex == -1) {
                    execProperties.put(DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME, rawProperty, execProperty.value)
                } else {
                    val execGroup: String = rawProperty.substring(0, delimiterIndex)
                    val property: String = rawProperty.substring(delimiterIndex + 1)
                    execProperties.put(execGroup, property, execProperty.value)
                }
            }
            return execProperties.buildOrThrow()
        }
    }
}
