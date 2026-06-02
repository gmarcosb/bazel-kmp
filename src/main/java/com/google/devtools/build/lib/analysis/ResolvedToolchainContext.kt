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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement

/**
 * Represents the data needed for a specific target's use of toolchains and platforms, including
 * specific [ToolchainInfo] providers for each required toolchain type.
 */
@AutoValue
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
abstract class ResolvedToolchainContext

    : ResolvedToolchainsDataInterface<ToolchainInfo?> {
    abstract fun toolchains(): com.google.common.collect.ImmutableMap<ToolchainTypeInfo?, ToolchainInfo?>?

    /** Returns the template variables that these toolchains provide.  */
    abstract fun templateVariableProviders(): com.google.common.collect.ImmutableList<TemplateVariableInfo?>?

    /** Returns the actual prerequisites for this context, for use in validation.  */
    abstract fun prerequisiteTargets(): com.google.common.collect.ImmutableSet<ConfiguredTargetAndData?>?

    /**
     * Returns the toolchain for the given type, or `null` if the toolchain type was not
     * required in this context. Be careful if `ResolvedToolchainContext` is from the
     * default-exec-group (usually `RuleContext.getToolchainContext()`) because it will not have
     * toolchains after Automatic Exec Groups are enabled. In that case please use `RuleContext.getToolchainInfo(toolchainTypeLabel)`.
     */
    override fun forToolchainType(toolchainTypeLabel: com.google.devtools.build.lib.cmdline.Label?): ToolchainInfo? {
        val toolchainTypeInfo: ToolchainTypeInfo? = requestedToolchainTypeLabels().get(toolchainTypeLabel)
        if (toolchainTypeInfo == null) {
            return null
        }
        return toolchains().get(toolchainTypeInfo)
    }

    fun forToolchainType(toolchainType: ToolchainTypeInfo?): ToolchainInfo? {
        return toolchains().get(toolchainType)
    }

    /**
     * Exception used when a toolchain type is requested but the resolved target does not have
     * ToolchainInfo.
     */
    internal class TargetNotToolchainException(
        toolchainType: ToolchainTypeInfo,
        resolvedTargetLabel: com.google.devtools.build.lib.cmdline.Label?
    ) : ToolchainException(
        String.format(
            "toolchain type %s resolved to target %s, but that target does not provide "
                    + ToolchainInfo.Companion.STARLARK_NAME,
            toolchainType.typeLabel(),
            resolvedTargetLabel
        )
    ) {
        val detailedCode: Code
            get() = Code.MISSING_PROVIDER
    }

    /** Exception used when a toolchain type is required but noimplementation was found.  */
    private class MissingToolchainTypeRequirementException(toolchainTypeRequirement: ToolchainTypeRequirement) :
        ToolchainException(
            java.lang.String.format(
                "toolchain type %s was mandatory but is not present",
                toolchainTypeRequirement.toolchainType()
            )
        ) {
        val detailedCode: Code
            get() = Code.NO_MATCHING_TOOLCHAIN
    }

    companion object {
        /**
         * Finishes preparing the [ResolvedToolchainContext] by finding the specific toolchain
         * providers to be used for each toolchain type.
         */
        @Throws(ToolchainException::class)
        fun load(
            unloadedToolchainContext: UnloadedToolchainContext,
            targetDescription: String?,
            toolchainTargets: com.google.common.collect.ImmutableSet<ConfiguredTargetAndData>
        ): ResolvedToolchainContext {
            val toolchainsBuilder: com.google.common.collect.ImmutableMap.Builder<ToolchainTypeInfo?, ToolchainInfo?> =
                com.google.common.collect.ImmutableMap.Builder<ToolchainTypeInfo?, ToolchainInfo?>()
            val templateVariableProviders: com.google.common.collect.ImmutableList.Builder<TemplateVariableInfo?> =
                com.google.common.collect.ImmutableList.Builder<TemplateVariableInfo?>()

            for (target in toolchainTargets) {
                // Aliases are in toolchainTypeToResolved by the original alias label, not via the final
                // target's label.
                val discoveredLabel: com.google.devtools.build.lib.cmdline.Label? =
                    target.getConfiguredTarget().getOriginalLabel()
                val toolchainInfo: ToolchainInfo? = PlatformProviderUtils.toolchain(target.getConfiguredTarget())

                for (toolchainType in unloadedToolchainContext.toolchainTypeToResolved().inverse()
                    .get(discoveredLabel)) {
                    // If the toolchainType hadn't been resolved to an actual target, resolution would have
                    // failed with an error much earlier. However, the target might still not be an actual
                    // toolchain.

                    if (toolchainType != null) {
                        if (toolchainInfo != null) {
                            toolchainsBuilder.put(toolchainType, toolchainInfo)
                        } else {
                            throw TargetNotToolchainException(toolchainType, discoveredLabel)
                        }
                    }

                    // Find any template variables present for this toolchain.
                    val templateVariableInfo: TemplateVariableInfo? =
                        target.getConfiguredTarget().get(TemplateVariableInfo.PROVIDER)
                    if (templateVariableInfo != null) {
                        templateVariableProviders.add(templateVariableInfo)
                    }
                }
            }

            val toolchains: com.google.common.collect.ImmutableMap<ToolchainTypeInfo?, ToolchainInfo?> =
                toolchainsBuilder.buildOrThrow()

            // Verify that all mandatory toolchain type requirements are present.
            for (toolchainTypeRequirement in unloadedToolchainContext.toolchainTypes()) {
                if (toolchainTypeRequirement.mandatory()) {
                    val toolchainTypeLabel: com.google.devtools.build.lib.cmdline.Label? =
                        toolchainTypeRequirement.toolchainType()
                    val toolchainTypeInfo: ToolchainTypeInfo? =
                        unloadedToolchainContext.requestedLabelToToolchainType().get(toolchainTypeLabel)
                    if (!toolchains.containsKey(toolchainTypeInfo)) {
                        throw MissingToolchainTypeRequirementException(toolchainTypeRequirement)
                    }
                }
            }

            return AutoValue_ResolvedToolchainContext( // super:
                unloadedToolchainContext.key(),
                unloadedToolchainContext.executionPlatform(),
                unloadedToolchainContext.targetPlatform(),
                unloadedToolchainContext.toolchainTypes(),
                unloadedToolchainContext.resolvedToolchainLabels(),  // this:
                targetDescription,
                unloadedToolchainContext.requestedLabelToToolchainType(),
                toolchains,
                templateVariableProviders.build(),
                com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (toolchainTargets)
            )
        }
    }
}
