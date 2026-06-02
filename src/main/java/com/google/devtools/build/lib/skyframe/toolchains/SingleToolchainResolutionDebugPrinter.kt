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

import com.google.devtools.build.lib.analysis.platform.ConstraintCollection

/** A helper interface for printing debug messages from single toolchain resolution.  */
interface SingleToolchainResolutionDebugPrinter {
    fun finishDebugging()

    fun describeRejectedToolchains(rejectedToolchains: com.google.common.collect.ImmutableMap<Label?, String?>?)

    fun startToolchainResolution(toolchainType: Label?, targetPlatform: Label?)

    fun reportCompatibleTargetPlatform(targetLabel: Label?, resolvedToolchainLabel: Label?)

    fun reportSkippedExecutionPlatformSeen(executionPlatform: Label?)

    fun reportSkippedExecutionPlatformDisallowed(executionPlatform: Label?, toolchainType: Label?)

    fun reportCompatibleExecutionPlatform(executionPlatform: Label?)

    fun reportResolvedToolchains(
        resolvedToolchains: com.google.common.collect.ImmutableMap<ConfiguredTargetKey?, Label?>?,
        targetPlatform: Label?,
        toolchainType: Label?
    )

    fun reportMismatchedSettings(
        toolchainConstraints: ConstraintCollection?,
        isTargetPlatform: Boolean,
        platform: PlatformInfo?,
        targetLabel: Label?,
        resolvedToolchainLabel: Label?,
        mismatchSettingsWithDefault: com.google.common.collect.ImmutableSet<ConstraintSettingInfo?>?
    )

    fun reportDone(toolchainType: Label?)

    /** A do-nothing implementation for when debug messages are suppressed.  */
    class NoopPrinter private constructor() : SingleToolchainResolutionDebugPrinter {
        override fun finishDebugging() {}

        override fun describeRejectedToolchains(rejectedToolchains: com.google.common.collect.ImmutableMap<Label?, String?>?) {}

        override fun startToolchainResolution(toolchainType: Label?, targetPlatform: Label?) {}

        override fun reportCompatibleTargetPlatform(targetLabel: Label?, resolvedToolchainLabel: Label?) {}

        override fun reportSkippedExecutionPlatformSeen(executionPlatform: Label?) {}

        override fun reportSkippedExecutionPlatformDisallowed(
            executionPlatform: Label?, toolchainType: Label?
        ) {
        }

        override fun reportCompatibleExecutionPlatform(executionPlatform: Label?) {}

        override fun reportResolvedToolchains(
            resolvedToolchains: com.google.common.collect.ImmutableMap<ConfiguredTargetKey?, Label?>?,
            targetPlatform: Label?,
            toolchainType: Label?
        ) {
        }

        override fun reportMismatchedSettings(
            toolchainConstraints: ConstraintCollection?,
            isTargetPlatform: Boolean,
            platform: PlatformInfo?,
            targetLabel: Label?,
            resolvedToolchainLabel: Label?,
            mismatchSettingsWithDefault: com.google.common.collect.ImmutableSet<ConstraintSettingInfo?>?
        ) {
        }

        override fun reportDone(toolchainType: Label?) {}
    }

    /** Implement debug printing using the [ExtendedEventHandler].  */
    class EventHandlerImpl private constructor(eventHandler: ExtendedEventHandler) :
        SingleToolchainResolutionDebugPrinter {
        /** Helper enum to define the three indentation levels used in `debugMessage`.  */
        private enum class IndentLevel(value: String) {
            TARGET_PLATFORM_LEVEL(""),
            TOOLCHAIN_LEVEL("  "),
            EXECUTION_PLATFORM_LEVEL("    ");

            val value: String?

            init {
                this.value = value
            }

            fun indent(): String? {
                return value
            }
        }

        private val eventHandler: ExtendedEventHandler
        private val resolutionTrace: MutableList<String?> = java.util.ArrayList<String?>()

        init {
            this.eventHandler = eventHandler
        }

        @com.google.errorprone.annotations.FormatMethod
        private fun debugMessage(
            indent: IndentLevel,
            @com.google.errorprone.annotations.FormatString template: String,
            vararg args: Any?
        ) {
            val padding = if (resolutionTrace.isEmpty()) "" else " ".repeat("INFO: ".length())
            resolutionTrace.add(
                padding + "ToolchainResolution: " + indent.indent() + java.lang.String.format(template, *args)
            )
        }

        override fun finishDebugging() {
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.info(
                    java.lang.String.join(
                        "\n",
                        resolutionTrace
                    )
                )
            )
        }

        override fun describeRejectedToolchains(rejectedToolchains: com.google.common.collect.ImmutableMap<Label?, String?>) {
            if (!rejectedToolchains.isEmpty()) {
                for (entry in rejectedToolchains.entrySet()) {
                    val toolchainLabel: Label? = entry.getKey()
                    val message: String? = entry.getValue()
                    debugMessage(
                        IndentLevel.TOOLCHAIN_LEVEL, "Rejected toolchain %s; %s", toolchainLabel, message
                    )
                }
            }
        }

        override fun startToolchainResolution(toolchainType: Label?, targetPlatform: Label?) {
            debugMessage(
                IndentLevel.TARGET_PLATFORM_LEVEL,
                "Performing resolution of %s for target platform %s",
                toolchainType,
                targetPlatform
            )
        }

        override fun reportCompatibleTargetPlatform(targetLabel: Label?, resolvedToolchainLabel: Label?) {
            debugMessage(
                IndentLevel.TOOLCHAIN_LEVEL,
                "Toolchain %s (resolves to %s) is compatible with target platform, searching for"
                        + " execution platforms:",
                targetLabel,
                resolvedToolchainLabel
            )
        }

        override fun reportSkippedExecutionPlatformSeen(executionPlatform: Label?) {
            debugMessage(
                IndentLevel.EXECUTION_PLATFORM_LEVEL,
                "Skipping execution platform %s; it has already selected a toolchain",
                executionPlatform
            )
        }

        override fun reportSkippedExecutionPlatformDisallowed(
            executionPlatform: Label?, toolchainType: Label?
        ) {
            debugMessage(
                IndentLevel.EXECUTION_PLATFORM_LEVEL,
                "Skipping execution platform %s; its allowed toolchain types does not contain the"
                        + " current toolchain type %s",
                executionPlatform,
                toolchainType
            )
        }

        override fun reportCompatibleExecutionPlatform(executionPlatform: Label?) {
            debugMessage(
                IndentLevel.EXECUTION_PLATFORM_LEVEL,
                "Compatible execution platform %s",
                executionPlatform
            )
        }

        override fun reportResolvedToolchains(
            resolvedToolchains: com.google.common.collect.ImmutableMap<ConfiguredTargetKey?, Label?>,
            targetPlatform: Label?,
            toolchainType: Label?
        ) {
            if (resolvedToolchains.isEmpty()) {
                debugMessage(
                    IndentLevel.TARGET_PLATFORM_LEVEL,
                    "No %s toolchain found for target platform %s.",
                    toolchainType,
                    targetPlatform
                )
            } else {
                debugMessage(
                    IndentLevel.TARGET_PLATFORM_LEVEL,
                    "Recap of selected %s toolchains for target platform %s:",
                    toolchainType,
                    targetPlatform
                )
                resolvedToolchains.forEach(
                    java.util.function.BiConsumer { executionPlatformKey: ConfiguredTargetKey?, resolvedToolchainLabel: Label? ->
                        debugMessage(
                            IndentLevel.TOOLCHAIN_LEVEL,
                            "Selected %s to run on execution platform %s",
                            resolvedToolchainLabel,
                            executionPlatformKey.getLabel()
                        )
                    })
            }
        }

        override fun reportMismatchedSettings(
            toolchainConstraints: ConstraintCollection,
            isTargetPlatform: Boolean,
            platform: PlatformInfo,
            targetLabel: Label?,
            resolvedToolchainLabel: Label?,
            mismatchSettingsWithDefault: com.google.common.collect.ImmutableSet<ConstraintSettingInfo?>
        ) {
            if (!mismatchSettingsWithDefault.isEmpty()) {
                var mismatchValues: String =
                    mismatchSettingsWithDefault.stream()
                        .filter(toolchainConstraints::has)
                        .map<Any?>(java.util.function.Function { s: ConstraintSettingInfo? ->
                            toolchainConstraints.get(s).label().getName()
                        })
                        .collect(Collectors.joining(", "))
                if (!mismatchValues.isEmpty()) {
                    mismatchValues = "; mismatching values: " + mismatchValues
                }

                var missingSettings: String =
                    mismatchSettingsWithDefault.stream()
                        .filter(java.util.function.Predicate { s: ConstraintSettingInfo? -> !toolchainConstraints.has(s) })
                        .map<Any?>(java.util.function.Function { s: ConstraintSettingInfo? -> s.label().getName() })
                        .collect(Collectors.joining(", "))
                if (!missingSettings.isEmpty()) {
                    missingSettings = "; missing: " + missingSettings
                }
                if (isTargetPlatform) {
                    debugMessage(
                        IndentLevel.TOOLCHAIN_LEVEL,
                        "Rejected toolchain %s (resolves to %s) %s",
                        targetLabel,
                        resolvedToolchainLabel,
                        mismatchValues + missingSettings
                    )
                } else {
                    debugMessage(
                        IndentLevel.EXECUTION_PLATFORM_LEVEL,
                        "Incompatible execution platform %s%s",
                        platform.label(),
                        mismatchValues + missingSettings
                    )
                }
            }
        }

        override fun reportDone(toolchainType: Label?) {
            debugMessage(
                IndentLevel.TOOLCHAIN_LEVEL,
                "All execution platforms have been assigned a %s toolchain, stopping",
                toolchainType
            )
        }
    }

    companion object {
        fun create(
            debug: Boolean, eventHandler: ExtendedEventHandler
        ): SingleToolchainResolutionDebugPrinter {
            if (debug) {
                return com.google.devtools.build.lib.skyframe.toolchains.SingleToolchainResolutionDebugPrinter.EventHandlerImpl(
                    eventHandler
                )
            }
            return com.google.devtools.build.lib.skyframe.toolchains.SingleToolchainResolutionDebugPrinter.NoopPrinter()
        }
    }
}
