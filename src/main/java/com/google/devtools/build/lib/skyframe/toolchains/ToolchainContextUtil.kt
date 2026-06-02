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

import com.google.devtools.build.lib.analysis.ExecGroupCollection

/** Utility methods for toolchain context creation.  */
object ToolchainContextUtil {
    @Throws(ExecGroupCollection.InvalidExecGroupException::class)
    fun getUnloadedToolchainContextsInputs(
        target: Target,
        coreOptions: CoreOptions,
        platformConfig: PlatformConfiguration?,
        parentExecutionPlatformLabel: Label?,
        toolchainConfigurationKey: BuildConfigurationKey?
    ): UnloadedToolchainContextsInputs {
        val rule: Rule? = target.getAssociatedRule()
        if (rule == null) {
            return UnloadedToolchainContextsInputs.empty()
        }

        val ruleClass: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            rule.getRuleClassObject()
        val useAutoExecGroups: Boolean =
            ruleClass.autoExecGroupsMode
                .isEnabled(RawAttributeMapper.of(rule), coreOptions.getUseAutoExecGroups())

        var toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?> =
            ruleClass.getToolchainTypes()
        if (!ruleClass.isStarlark && ruleClass.getName().equals("genrule")) {
            // Override the toolchain types based on the target-level "toolchains" attribute.
            toolchainTypes = updateToolchainTypesFromAttribute(rule, toolchainTypes)
        }

        val defaultExecConstraintLabels: com.google.common.collect.ImmutableSet<Label?> =
            getExecutionPlatformConstraints(rule, platformConfig)
        val perExecGroupExecConstraintLabels: com.google.common.collect.ImmutableMultimap<String?, Label?> =
            getPerExecGroupExecutionPlatformConstraints(
                rule, platformConfig, toolchainTypes, useAutoExecGroups
            )

        val processedExecGroups: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            DeclaredExecGroup.process(
                ruleClass.getDeclaredExecGroups(),
                defaultExecConstraintLabels,
                perExecGroupExecConstraintLabels,
                toolchainTypes,
                useAutoExecGroups
            )

        if (platformConfig == null || !rule.useToolchainResolution()) {
            return UnloadedToolchainContextsInputs.create(
                processedExecGroups,  /* targetToolchainContextKey= */null
            )
        }

        return UnloadedToolchainContextsInputs.create(
            processedExecGroups,
            createDefaultToolchainContextKey(
                toolchainConfigurationKey,
                defaultExecConstraintLabels,  /* debugTarget= */
                platformConfig.debugToolchainResolution(rule.getLabel()),  /* useAutoExecGroups= */
                useAutoExecGroups,
                toolchainTypes,
                parentExecutionPlatformLabel
            )
        )
    }

    private fun updateToolchainTypesFromAttribute(
        rule: Rule?, toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>
    ): com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?> {
        val attributes: NonconfigurableAttributeMapper = NonconfigurableAttributeMapper.of(rule)
        val targetToolchainTypes: MutableList<Label?> = attributes.get("toolchains", BuildType.LABEL_LIST)
        if (targetToolchainTypes.isEmpty()) {
            // No need to update.
            return toolchainTypes
        }

        val updatedToolchainTypes: com.google.common.collect.ImmutableSet.Builder<ToolchainTypeRequirement?> =
            com.google.common.collect.ImmutableSet.Builder<ToolchainTypeRequirement?>()
        updatedToolchainTypes.addAll(toolchainTypes)
        targetToolchainTypes.stream()
            .map<Any?>(
                java.util.function.Function { toolchainTypeLabel: Label? ->
                    ToolchainTypeRequirement.builder(toolchainTypeLabel)
                        .mandatory(false) // Some of these may be template variables, not toolchain types, and so should
                        // be silently ignored.
                        .ignoreIfInvalid(true)
                        .build()
                })
            .forEach(updatedToolchainTypes::add)
        return updatedToolchainTypes.build()
    }

    fun createDefaultToolchainContextKey(
        configurationKey: BuildConfigurationKey?,
        defaultExecConstraintLabels: com.google.common.collect.ImmutableSet<Label?>?,
        debugTarget: Boolean,
        useAutoExecGroups: Boolean,
        toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>?,
        parentExecutionPlatformLabel: Label?
    ): ToolchainContextKey? {
        val toolchainContextKeyBuilder: com.google.devtools.build.lib.skyframe.toolchains.ToolchainContextKey.Builder =
            ToolchainContextKey.Companion.key()
                .configurationKey(configurationKey)
                .execConstraintLabels(defaultExecConstraintLabels)
                .debugTarget(debugTarget)

        // Add toolchain types only if automatic exec groups are not created for this target.
        if (!useAutoExecGroups) {
            toolchainContextKeyBuilder.toolchainTypes(toolchainTypes)
        }

        if (parentExecutionPlatformLabel != null) {
            // Find out what execution platform the parent used, and force that.
            // This should only be set for direct toolchain dependencies.
            toolchainContextKeyBuilder.forceExecutionPlatform(parentExecutionPlatformLabel)
        }
        return toolchainContextKeyBuilder.build()
    }

    /**
     * Returns the target-specific execution platform constraints, based on the rule definition and
     * any constraints added by the target, including those added for the target on the command line.
     */
    private fun getExecutionPlatformConstraints(
        rule: Rule, platformConfiguration: PlatformConfiguration?
    ): com.google.common.collect.ImmutableSet<Label?> {
        if (platformConfiguration == null) {
            return com.google.common.collect.ImmutableSet.of<Label?>() // See NoConfigTransition.
        }
        val mapper: NonconfigurableAttributeMapper = NonconfigurableAttributeMapper.of(rule)
        val execConstraintLabels: com.google.common.collect.ImmutableSet.Builder<Label?> =
            com.google.common.collect.ImmutableSet.Builder<Label?>()

        execConstraintLabels.addAll(rule.getRuleClassObject().getExecutionPlatformConstraints())
        if (rule.getRuleClassObject()
                .getAttributeProvider()
                .hasAttr(RuleClass.EXEC_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST)
        ) {
            execConstraintLabels.addAll(
                mapper.get(RuleClass.EXEC_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST)
            )
        }

        return execConstraintLabels.build()
    }

    @Throws(ExecGroupCollection.InvalidExecGroupException::class)
    private fun getPerExecGroupExecutionPlatformConstraints(
        rule: Rule,
        platformConfiguration: PlatformConfiguration?,
        toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>,
        useAutoExecGroups: Boolean
    ): com.google.common.collect.ImmutableMultimap<String?, Label?> {
        if (platformConfiguration == null) {
            return com.google.common.collect.ImmutableMultimap.of<String?, Label?>() // See NoConfigTransition.
        }
        if (!rule.getRuleClassObject()
                .getAttributeProvider()
                .hasAttr(RuleClass.EXEC_GROUP_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST_DICT)
        ) {
            return com.google.common.collect.ImmutableMultimap.of<String?, Label?>()
        }
        val mapper: NonconfigurableAttributeMapper = NonconfigurableAttributeMapper.of(rule)
        val execGroupConstraints: com.google.common.collect.ImmutableMultimap.Builder<String?, Label?> =
            com.google.common.collect.ImmutableMultimap.builder<String?, Label?>()

        val packageContext: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Label.PackageContext.of(
                rule.getPackageMetadata().packageIdentifier(),
                rule.getPackageMetadata().repositoryMapping()
            )
        for (entry in mapper
            .get(RuleClass.EXEC_GROUP_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST_DICT)
            .entrySet()) {
            val canonicalKey: String
            if (StarlarkExecGroupCollection.isValidGroupName(entry.getKey())) {
                canonicalKey = entry.getKey()
                if (!rule.getRuleClassObject().getDeclaredExecGroups().containsKey(canonicalKey)) {
                    throw InvalidExecGroupException(
                        "execution constraints",
                        rule.getDisplayFormLabel(),
                        com.google.common.collect.ImmutableSet.of<E?>(canonicalKey),
                        rule.getRuleClassObject().getDeclaredExecGroups().keySet()
                    )
                }
            } else if (useAutoExecGroups) {
                val label: Label
                try {
                    label = Label.parseWithPackageContext(entry.getKey(), packageContext)
                } catch (e: LabelSyntaxException) {
                    throw InvalidExecGroupException(
                        "execution constraints",
                        rule.getDisplayFormLabel(),
                        com.google.common.collect.ImmutableSet.of<E?>(entry.getKey()),
                        com.google.common.collect.ImmutableSet.of<E?>()
                    )
                }
                if (toolchainTypes.stream()
                        .map<Any?>(ToolchainTypeRequirement::toolchainType)
                        .noneMatch(label::equals)
                ) {
                    var suggestedLabels: com.google.common.collect.ImmutableSet<String?> =
                        com.google.common.collect.ImmutableSet.of<String?>()
                    // TODO: Generalize Label#getDisplayForm to accept non-main repo mappings.
                    if (rule.getLabel().getRepository().isMain()) {
                        suggestedLabels =
                            toolchainTypes.stream()
                                .map<Any?>(ToolchainTypeRequirement::toolchainType)
                                .map<Any?>(java.util.function.Function { type: Any? ->
                                    type.getDisplayForm(
                                        rule.getPackageMetadata().repositoryMapping()
                                    )
                                })
                                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
                    }
                    throw InvalidExecGroupException(
                        "execution constraints",
                        rule.getDisplayFormLabel(),
                        com.google.common.collect.ImmutableSet.of<E?>(entry.getKey()),
                        suggestedLabels
                    )
                }
                canonicalKey = label.getUnambiguousCanonicalForm()
            } else {
                throw InvalidExecGroupException(
                    "execution constraints",
                    rule.getDisplayFormLabel(),
                    com.google.common.collect.ImmutableSet.of<E?>(entry.getKey()),
                    com.google.common.collect.ImmutableSet.of<E?>()
                )
            }
            execGroupConstraints.putAll(canonicalKey, entry.getValue())
        }

        return execGroupConstraints.build()
    }
}
