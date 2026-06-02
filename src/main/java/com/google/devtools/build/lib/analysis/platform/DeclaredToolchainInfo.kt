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
package com.google.devtools.build.lib.analysis.platform

import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider

/**
 * Provider for a toolchain declaration, which associates a toolchain type, the execution and target
 * constraints, and the actual toolchain label. The toolchain is then available for use but will be
 * lazily resolved only when it is actually needed for toolchain-aware rules. Toolchain definitions
 * are exposed to Starlark and Bazel via [ToolchainInfo] providers.
 * 
 * @param toolchainType The type of the toolchain being declared. This will be a label of a
 * toolchain_type() target.
 * @param execConstraints The constraints describing the execution environment.
 * @param targetConstraints The constraints describing the target environment.
 * @param targetSettings The setting, that target build configuration needs to satisfy.
 * @param targetLabel The label of the `toolchain` target itself.
 * @param resolvedToolchainLabel The label of the toolchain to resolve for use in toolchain-aware
 * rules.
 */
@AutoCodec
class DeclaredToolchainInfo(
    toolchainType: ToolchainTypeInfo?,
    execConstraints: ConstraintCollection?,
    targetConstraints: ConstraintCollection?,
    targetSettings: com.google.common.collect.ImmutableList<ConfigMatchingProvider?>?,
    targetLabel: com.google.devtools.build.lib.cmdline.Label?,
    resolvedToolchainLabel: com.google.devtools.build.lib.cmdline.Label?
) : com.google.devtools.build.lib.analysis.TransitiveInfoProvider {
    fun hasTargetToExecConstraints(): Boolean {
        // This needs to check identity as the special ConstraintCollection is otherwise equal to the
        // empty one. This avoids adding a new field or making ConstraintCollection more complex.
        return execConstraints === USE_TARGET_PLATFORM_CONSTRAINTS
                && targetConstraints === USE_TARGET_PLATFORM_CONSTRAINTS
    }

    /** Builder class to assist in creating [DeclaredToolchainInfo] instances.  */
    class Builder {
        private var toolchainType: ToolchainTypeInfo? = null
        private val execConstraints: com.google.devtools.build.lib.analysis.platform.ConstraintCollection.Builder =
            ConstraintCollection.Companion.builder()
        private val targetConstraints: com.google.devtools.build.lib.analysis.platform.ConstraintCollection.Builder =
            ConstraintCollection.Companion.builder()
        private val targetSettings: com.google.common.collect.ImmutableList.Builder<ConfigMatchingProvider?> =
            com.google.common.collect.ImmutableList.Builder<ConfigMatchingProvider?>()
        private var targetLabel: com.google.devtools.build.lib.cmdline.Label? = null
        private var resolvedToolchainLabel: com.google.devtools.build.lib.cmdline.Label? = null

        /** Sets the type of the toolchain being declared.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun toolchainType(toolchainType: ToolchainTypeInfo?): Builder {
            this.toolchainType = toolchainType
            return this
        }

        /** Adds constraints describing the execution environment.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecConstraints(constraints: Iterable<ConstraintValueInfo?>?): Builder {
            this.execConstraints.addConstraints(constraints)
            return this
        }

        /** Adds constraints describing the execution environment.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecConstraints(vararg constraints: ConstraintValueInfo?): Builder {
            return addExecConstraints(com.google.common.collect.ImmutableList.copyOf<ConstraintValueInfo?>(constraints))
        }

        /** Adds constraints describing the target environment.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTargetConstraints(constraints: Iterable<ConstraintValueInfo?>?): Builder {
            this.targetConstraints.addConstraints(constraints)
            return this
        }

        /** Adds constraints describing the target environment.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTargetConstraints(vararg constraints: ConstraintValueInfo?): Builder {
            return addTargetConstraints(com.google.common.collect.ImmutableList.copyOf<ConstraintValueInfo?>(constraints))
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTargetSettings(targetSettings: Iterable<ConfigMatchingProvider?>): Builder {
            this.targetSettings.addAll(targetSettings)
            return this
        }

        /** Sets the label of the `toolchain` target itself.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun targetLabel(targetLabel: com.google.devtools.build.lib.cmdline.Label?): Builder {
            this.targetLabel = targetLabel
            return this
        }

        /** Sets the label of the toolchain to resolve for use in toolchain-aware rules.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun resolvedToolchainLabel(resolvedToolchainLabel: com.google.devtools.build.lib.cmdline.Label?): Builder {
            this.resolvedToolchainLabel = resolvedToolchainLabel
            return this
        }

        /** Returns the newly created [DeclaredToolchainInfo] instance.  */
        @Throws(com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo.DuplicateConstraintException::class)
        fun build(): DeclaredToolchainInfo {
            // Handle constraint duplication in attributes separately, so they can be reported correctly.
            var execConstraintsException: com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException? =
                null
            var execConstraints: ConstraintCollection?
            try {
                execConstraints = this.execConstraints.build()
            } catch (e: com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException) {
                execConstraints = null
                execConstraintsException = e
            }
            var targetConstraintsException: com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException? =
                null
            var targetConstraints: ConstraintCollection?
            try {
                targetConstraints = this.targetConstraints.build()
            } catch (e: com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException) {
                targetConstraints = null
                targetConstraintsException = e
            }
            if (execConstraintsException != null || targetConstraintsException != null) {
                throw com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo.DuplicateConstraintException(
                    execConstraintsException, targetConstraintsException
                )
            }
            return DeclaredToolchainInfo(
                toolchainType,
                execConstraints,
                targetConstraints,
                targetSettings.build(),
                targetLabel,
                resolvedToolchainLabel
            )
        }

        fun buildWithTargetToExecConstraints(): DeclaredToolchainInfo {
            return DeclaredToolchainInfo(
                toolchainType,
                USE_TARGET_PLATFORM_CONSTRAINTS,
                USE_TARGET_PLATFORM_CONSTRAINTS,
                targetSettings.build(),
                targetLabel,
                resolvedToolchainLabel
            )
        }
    }

    /**
     * Exception for reporting duplicated constraints from declared toolchains.
     * 
     * 
     * Contains distinct fields for errors from the execution constraints or target constraints, so
     * that these can be reported separately.
     */
    class DuplicateConstraintException private constructor(
        execConstraintsException: com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException?,
        targetConstraintsException: com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException?
    ) : java.lang.Exception(
        com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo.DuplicateConstraintException.Companion.formatError(
            execConstraintsException,
            targetConstraintsException
        )
    ) {
        private val execConstraintsException: com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException?

        private val targetConstraintsException: com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException?

        init {
            // At least one should be non-null.
            this.execConstraintsException = execConstraintsException
            this.targetConstraintsException = targetConstraintsException
        }

        fun execConstraintsException(): com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException? {
            return execConstraintsException
        }

        fun targetConstraintsException(): com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException? {
            return targetConstraintsException
        }

        companion object {
            fun formatError(
                execConstraintsException: com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException?,
                targetConstraintsException: com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException?
            ): String {
                val message: java.lang.StringBuilder = java.lang.StringBuilder()
                message.append("Duplicate constraints detected[")
                if (execConstraintsException != null) {
                    message.append("in execution constraints: ").append(execConstraintsException.message)
                }
                if (targetConstraintsException != null) {
                    if (execConstraintsException != null) {
                        message.append(", ")
                    }
                    message.append("in target constraints: ").append(targetConstraintsException.message)
                }
                message.append("]")
                return message.toString()
            }
        }
    }

    val toolchainType: ToolchainTypeInfo?
    val execConstraints: ConstraintCollection?
    val targetConstraints: ConstraintCollection?
    val targetSettings: com.google.common.collect.ImmutableList<ConfigMatchingProvider?>?
    val targetLabel: com.google.devtools.build.lib.cmdline.Label?
    val resolvedToolchainLabel: com.google.devtools.build.lib.cmdline.Label?

    init {
        this.resolvedToolchainLabel = resolvedToolchainLabel
        this.targetLabel = targetLabel
        this.targetSettings = targetSettings
        this.targetConstraints = targetConstraints
        this.execConstraints = execConstraints
        this.toolchainType = toolchainType
        java.util.Objects.requireNonNull<ToolchainTypeInfo?>(toolchainType, "toolchainType")
        java.util.Objects.requireNonNull<ConstraintCollection?>(execConstraints, "execConstraints")
        java.util.Objects.requireNonNull<ConstraintCollection?>(targetConstraints, "targetConstraints")
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<ConfigMatchingProvider?>?>(
            targetSettings,
            "targetSettings"
        )
        java.util.Objects.requireNonNull<com.google.devtools.build.lib.cmdline.Label?>(targetLabel, "targetLabel")
        java.util.Objects.requireNonNull<com.google.devtools.build.lib.cmdline.Label?>(
            resolvedToolchainLabel,
            "resolvedToolchainLabel"
        )
    }

    companion object {
        private val USE_TARGET_PLATFORM_CONSTRAINTS: ConstraintCollection

        init {
            try {
                USE_TARGET_PLATFORM_CONSTRAINTS = ConstraintCollection.Companion.builder().build()
            } catch (e: com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException) {
                throw java.lang.IllegalStateException(e)
            }
        }

        /** Returns a new [Builder] for creating [DeclaredToolchainInfo] instances.  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo.Builder()
        }
    }
}
