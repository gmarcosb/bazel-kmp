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

import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo

/** A helper interface for printing debug messages from toolchain resolution.  */
interface ToolchainResolutionDebugPrinter {
    fun debugEnabled(): Boolean

    /** Report on which toolchains were selected.  */
    fun reportSelectedToolchains(
        targetPlatform: Label?,
        executionPlatform: Label?,
        toolchainTypeToResolved: com.google.common.collect.ImmutableSetMultimap<ToolchainTypeInfo?, Label?>?
    )

    /** Report on an execution platform that was skipped due to constraint mismatches.  */
    fun reportRemovedExecutionPlatform(
        label: Label?, missingConstraints: com.google.common.collect.ImmutableList<ConstraintValueInfo?>?
    )

    fun reportRejectedExecutionPlatforms(rejectedExecutionPlatforms: com.google.common.collect.ImmutableMap<Label?, String?>?)

    /** A do-nothing implementation for when debug messages are suppressed.  */
    class NoopPrinter private constructor() : ToolchainResolutionDebugPrinter {
        override fun debugEnabled(): Boolean {
            return false
        }

        override fun reportSelectedToolchains(
            targetPlatform: Label?,
            executionPlatform: Label?,
            toolchainTypeToResolved: com.google.common.collect.ImmutableSetMultimap<ToolchainTypeInfo?, Label?>?
        ) {
        }

        override fun reportRemovedExecutionPlatform(
            label: Label?, missingConstraints: com.google.common.collect.ImmutableList<ConstraintValueInfo?>?
        ) {
        }

        override fun reportRejectedExecutionPlatforms(
            rejectedExecutionPlatforms: com.google.common.collect.ImmutableMap<Label?, String?>?
        ) {
        }
    }

    /** Implement debug printing using the [ExtendedEventHandler].  */
    class EventHandlerImpl private constructor(eventHandler: ExtendedEventHandler) : ToolchainResolutionDebugPrinter {
        override fun debugEnabled(): Boolean {
            return true
        }

        private val eventHandler: ExtendedEventHandler

        init {
            this.eventHandler = eventHandler
        }

        @com.google.errorprone.annotations.FormatMethod
        private fun debugMessage(@com.google.errorprone.annotations.FormatString template: String, vararg args: Any?) {
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.info(
                    java.lang.String.format(
                        template,
                        *args
                    )
                )
            )
        }

        override fun reportSelectedToolchains(
            targetPlatform: Label?,
            executionPlatform: Label?,
            toolchainTypeToResolved: com.google.common.collect.ImmutableSetMultimap<ToolchainTypeInfo?, Label?>
        ) {
            val selectedToolchains: String? =
                toolchainTypeToResolved.entries().stream()
                    .map<Any?>(
                        java.util.function.Function { e: MutableMap.MutableEntry<ToolchainTypeInfo?, Label?>? ->
                            java.lang.String.format(
                                "type %s -> toolchain %s", e.getKey().typeLabel(), e.getValue()
                            )
                        })
                    .collect(Collectors.joining(", "))
            debugMessage(
                "ToolchainResolution: Target platform %s: Selected execution platform %s," + " %s",
                targetPlatform, executionPlatform, selectedToolchains
            )
        }

        override fun reportRemovedExecutionPlatform(
            label: Label?, missingConstraints: com.google.common.collect.ImmutableList<ConstraintValueInfo>
        ) {
            // TODO: jcater - Make this one line listing all constraints.
            for (constraint in missingConstraints) {
                // The value for this setting is not present in the platform, or doesn't match the
                // expected value.
                debugMessage(
                    "ToolchainResolution: Removed execution platform %s from"
                            + " available execution platforms, it is missing constraint %s",
                    label, constraint.label()
                )
            }
        }

        override fun reportRejectedExecutionPlatforms(
            rejectedExecutionPlatforms: com.google.common.collect.ImmutableMap<Label?, String?>
        ) {
            if (!rejectedExecutionPlatforms.isEmpty()) {
                for (entry in rejectedExecutionPlatforms.entrySet()) {
                    val toolchainLabel: Label? = entry.getKey()
                    val message: String? = entry.getValue()
                    debugMessage(
                        "ToolchainResolution: Rejected execution platform %s; %s", toolchainLabel, message
                    )
                }
            }
        }
    }

    companion object {
        fun create(debug: Boolean, eventHandler: ExtendedEventHandler): ToolchainResolutionDebugPrinter {
            if (debug) {
                return com.google.devtools.build.lib.skyframe.toolchains.ToolchainResolutionDebugPrinter.EventHandlerImpl(
                    eventHandler
                )
            }
            return com.google.devtools.build.lib.skyframe.toolchains.ToolchainResolutionDebugPrinter.NoopPrinter()
        }
    }
}
