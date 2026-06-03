// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

/** Extends [RuleContext] to provide all data available during the analysis of an aspect.  */
class AspectContext internal constructor(
    builder: com.google.devtools.build.lib.analysis.RuleContext.Builder,
    aspectAwareAttributeMapper: AspectAwareAttributeMapper?,
    ruleAndBaseAspectsPrerequisites: PrerequisitesCollection?,
    mainAspectPrerequisites: PrerequisitesCollection,
    execGroupCollection: ExecGroupCollection?,
    baseTargetToolchainContexts: ToolchainCollection<AspectBaseTargetResolvedToolchainContext?>?,
    targetUsesAutoExecGroups: Boolean
) : RuleContext(
    builder, aspectAwareAttributeMapper, ruleAndBaseAspectsPrerequisites, execGroupCollection
) {
    /**
     * A list of all aspects applied to the target.
     * 
     * 
     * The last aspect in the list is the main aspect that this context is for.
     */
    private val aspects: com.google.common.collect.ImmutableList<Aspect>

    private val aspectDescriptors: com.google.common.collect.ImmutableList<AspectDescriptor?>

    private val mainAspectPrerequisites: PrerequisitesCollection

    /**
     * The toolchain contexts for the base target.
     * 
     * 
     * It only contains the providers created by the aspects that propagate to the toolchains.
     */
    private val baseTargetToolchainContexts: ToolchainCollection<AspectBaseTargetResolvedToolchainContext?>?


    /** Whether the target uses auto exec groups.  */
    private val targetUsesAutoExecGroups: Boolean

    /** The make variables for the base target.  */
    private var baseTargetConfigurationMakeVariableContext: ConfigurationMakeVariableContext? = null

    init {
        this.aspects = builder.getAspects()
        this.aspectDescriptors = aspects.stream().map<Any?>(Aspect::getDescriptor)
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        this.mainAspectPrerequisites = mainAspectPrerequisites
        this.baseTargetToolchainContexts = baseTargetToolchainContexts
        this.targetUsesAutoExecGroups = targetUsesAutoExecGroups
    }

    /**
     * Returns the toolchain contexts for the base target. Can be null if no aspect in the `aspects` path propagate to the toolchains.
     */
    fun getBaseTargetToolchainContexts(): ToolchainCollection<AspectBaseTargetResolvedToolchainContext?>? {
        return baseTargetToolchainContexts
    }

    /** Returns the labels of default the toolchain types that aspects have propagated.  */
    fun getRequestedToolchainTypesLabels(): com.google.common.collect.ImmutableSet<Label?> {
        if (targetUsesAutoExecGroups) {
            return baseTargetToolchainContexts.contextMap().entrySet().stream()
                .filter({ e -> DeclaredExecGroup.isAutomatic(e.getKey()) })
                .flatMap({ e -> e.getValue().requestedToolchainTypeLabels().keySet().stream() })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
        } else {
            return baseTargetToolchainContexts
                .getDefaultToolchainContext()
                .requestedToolchainTypeLabels()
                .keySet()
        }
    }

    /**
     * Returns the toolchain data for the given type, or `null` if the toolchain type was not
     * required in this context.
     */
    fun getToolchainTarget(
        toolchainType: Label?
    ): ToolchainAspectsProviders? {
        var execGroupContext: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            baseTargetToolchainContexts.getDefaultToolchainContext()
        if (targetUsesAutoExecGroups) {
            execGroupContext =
                baseTargetToolchainContexts.contextMap().entrySet().stream()
                    .filter(
                        { e ->
                            DeclaredExecGroup.isAutomatic(e.getKey())
                                    && e.getValue().requestedToolchainTypeLabels().containsKey(toolchainType)
                        })
                    .findFirst()
                    .map({ e -> e.getValue() })
                    .orElse(null)
            if (execGroupContext == null) {
                return null
            }
        }
        return execGroupContext
            .toolchains()
            .get(execGroupContext.requestedToolchainTypeLabels().get(toolchainType))
    }

    override fun getOwningPrerequisitesCollection(attributeName: String?): PrerequisitesCollection? {
        if (mainAspectPrerequisites.has(attributeName)) {
            return mainAspectPrerequisites
        }
        return getRulePrerequisitesCollection()
    }

    fun getMainAspectPrerequisitesCollection(): PrerequisitesCollection {
        return mainAspectPrerequisites
    }

    override fun getAspects(): com.google.common.collect.ImmutableList<Aspect> {
        return aspects
    }

    /**
     * Return the main aspect of this context.
     * 
     * 
     * It is the last aspect in the list of aspects applied to a target; all other aspects are the
     * ones main aspect sees as specified by its "required_aspect_providers").
     */
    override fun getMainAspect(): Aspect? {
        return com.google.common.collect.Iterables.getLast<Aspect?>(aspects)
    }

    /** All aspects applied to the rule.  */
    override fun getAspectDescriptors(): com.google.common.collect.ImmutableList<AspectDescriptor?> {
        return aspectDescriptors
    }

    override fun useAutoExecGroups(): Boolean {
        // TODO: b/370558813 - Use AutoExecGroupsMode for aspects, as well.
        val aspectAttributes: com.google.common.collect.ImmutableMap<String?, Attribute?> =
            getMainAspect().getDefinition().getAttributes()
        if (aspectAttributes.containsKey("\$use_auto_exec_groups")) {
            return aspectAttributes.get("\$use_auto_exec_groups").getDefaultValueUnchecked() as Boolean
        } else {
            return getConfiguration().useAutoExecGroups()
        }
    }

    override fun getAllPrerequisites(): com.google.common.collect.ImmutableList<out TransitiveInfoCollection?> {
        return com.google.common.collect.Streams.concat<Any?>(
            mainAspectPrerequisites.getAllPrerequisites().stream(),
            getRulePrerequisitesCollection().getAllPrerequisites().stream()
        )
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
    }

    fun getTemplateVariablesFromAspectAttributes(
        attributeNames: Iterable<String?>
    ): com.google.common.collect.ImmutableList<TemplateVariableInfo?> {
        // Get template variable providers from the attributes.
        return com.google.common.collect.Streams.stream<String?>(attributeNames) // Only process this attribute it if is present in the aspect directly.
            .filter { attrName: String? ->
                this.getMainAspectPrerequisitesCollection().has(attrName)
            }  // Get the TemplateVariableInfo providers from this attribute.
            .flatMap { attrName: String? ->
                this.getMainAspectPrerequisitesCollection()
                    .getPrerequisites(attrName, TemplateVariableInfo.PROVIDER)
                    .stream()
            }
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
    }

    fun getTemplateVariablesFromBaseRuleAttributes(
        attributeNames: Iterable<String?>
    ): com.google.common.collect.ImmutableList<TemplateVariableInfo?> {
        // Get template variable providers from the attributes.
        return com.google.common.collect.Streams.stream<String?>(attributeNames) // Only process this attribute it if is present in the target directly.
            .filter { attrName: String? ->
                this.getRulePrerequisitesCollection().has(attrName)
            }  // Get the TemplateVariableInfo providers from this attribute.
            .flatMap { attrName: String? ->
                this.getRulePrerequisitesCollection()
                    .getPrerequisites(attrName, TemplateVariableInfo.PROVIDER)
                    .stream()
            }
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
    }

    private fun getTemplateVariablesFromBaseRuleToolchains(): com.google.common.collect.ImmutableList<TemplateVariableInfo?>? {
        if (this.getBaseTargetToolchainContexts() == null) {
            return com.google.common.collect.ImmutableList.of<TemplateVariableInfo?>()
        }

        return this.getBaseTargetToolchainContexts().contextMap().values().stream()
            .flatMap({ context -> context.templateVariableProviders().stream() })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
    }

    override fun getDefaultTemplateVariableProviders(): com.google.common.collect.ImmutableList<TemplateVariableInfo?> {
        return com.google.common.collect.ImmutableList.Builder<TemplateVariableInfo?>()
            .addAll(getTemplateVariablesFromAspectAttributes(RuleContext.Companion.DEFAULT_MAKE_VARIABLE_ATTRIBUTES))
            .addAll(fromToolchains())
            .build()
    }

    /**
     * Returns the [ConfigurationMakeVariableContext] for the aspect itself, including
     * toolchains but not including the underlying target.
     */
    override fun getConfigurationMakeVariableContext(): ConfigurationMakeVariableContext? {
        return super.getConfigurationMakeVariableContext()
    }

    /**
     * Returns the [ConfigurationMakeVariableContext] for the base rule, but not including the
     * aspect and its toolchains.
     */
    fun getBaseTargetConfigurationMakeVariableContext(): ConfigurationMakeVariableContext {
        if (baseTargetConfigurationMakeVariableContext == null) {
            val templateVariableProviders: com.google.common.collect.ImmutableList<TemplateVariableInfo?> =
                com.google.common.collect.ImmutableList.Builder<TemplateVariableInfo?>()
                    .addAll(getTemplateVariablesFromBaseRuleAttributes(RuleContext.Companion.DEFAULT_MAKE_VARIABLE_ATTRIBUTES))
                    .addAll(getTemplateVariablesFromBaseRuleToolchains())
                    .build()

            baseTargetConfigurationMakeVariableContext =
                ConfigurationMakeVariableContext(
                    this.getRule().getPackageDeclarations(),
                    getConfiguration(),
                    templateVariableProviders
                )
        }
        return baseTargetConfigurationMakeVariableContext
    }

    companion object {
        /**
         * Merge the attributes of the aspects in the aspects path.
         * 
         * 
         * For attributes with the same name, the one that is first encountered takes precedence.
         */
        private fun mergeAspectsAttributes(
            aspects: com.google.common.collect.ImmutableList<Aspect>
        ): com.google.common.collect.ImmutableMap<String?, Attribute?>? {
            if (aspects.isEmpty()) {
                return com.google.common.collect.ImmutableMap.of<String?, Attribute?>()
            } else if (aspects.size == 1) {
                return aspects.get(0).getDefinition().getAttributes()
            } else {
                val aspectAttributes: LinkedHashMap<String?, Attribute?> = LinkedHashMap<String?, Attribute?>()
                for (aspect in aspects) {
                    val currentAttributes: com.google.common.collect.ImmutableMap<String?, Attribute?> =
                        aspect.getDefinition().getAttributes()
                    for (kv in currentAttributes.entries) {
                        aspectAttributes.putIfAbsent(kv.key, kv.value)
                    }
                }
                return com.google.common.collect.ImmutableMap.copyOf<String?, Attribute?>(aspectAttributes)
            }
        }

        fun create(
            builder: com.google.devtools.build.lib.analysis.RuleContext.Builder,
            ruleAttributes: AttributeMap?,
            targetsMap: com.google.common.collect.ImmutableListMultimap<DependencyKind?, ConfiguredTargetAndData?>,
            execGroupCollection: ExecGroupCollection?,
            baseTargetToolchainContexts: ToolchainCollection<AspectBaseTargetResolvedToolchainContext?>?
        ): AspectContext {
            return createAspectContextWithSeparatedPrerequisites(
                builder, ruleAttributes, targetsMap, execGroupCollection, baseTargetToolchainContexts
            )
        }

        /**
         * Create prerequisites collection for aspect evaluation separating the main aspect prerequisites
         * from the underlying rule and base aspects prerequisites.
         */
        private fun createAspectContextWithSeparatedPrerequisites(
            ruleContextBuilder: com.google.devtools.build.lib.analysis.RuleContext.Builder,
            ruleAttributes: AttributeMap?,
            prerequisitesMap: com.google.common.collect.ImmutableListMultimap<DependencyKind?, ConfiguredTargetAndData?>,
            execGroupCollection: ExecGroupCollection?,
            baseTargetToolchainContexts: ToolchainCollection<AspectBaseTargetResolvedToolchainContext?>?
        ): AspectContext {
            val mainAspectPrerequisites: ImmutableSortedKeyListMultimap.Builder<String?, ConfiguredTargetAndData?> =
                ImmutableSortedKeyListMultimap.builder()
            val ruleAndBaseAspectsPrerequisites: ImmutableSortedKeyListMultimap.Builder<String?, ConfiguredTargetAndData?> =
                ImmutableSortedKeyListMultimap.builder()

            val mainAspect: Aspect? =
                com.google.common.collect.Iterables.getLast<Aspect?>(ruleContextBuilder.getAspects())

            for (entry in prerequisitesMap.asMap().entries) {
                val attributeName: String? = entry.key.getAttribute().getName()

                if (mainAspect.getAspectClass().equals(entry.key.getOwningAspect())) {
                    mainAspectPrerequisites.putAll(attributeName, entry.value)
                } else {
                    ruleAndBaseAspectsPrerequisites.putAll(attributeName, entry.value)
                }
            }

            val targetUsesAutoExecGroups: Boolean =
                ruleContextBuilder
                    .getRule()
                    .getRuleClassObject()
                    .getAutoExecGroupsMode()
                    .isEnabled(ruleAttributes, ruleContextBuilder.getConfiguration().useAutoExecGroups())

            return AspectContext(
                ruleContextBuilder,
                AspectAwareAttributeMapper(
                    ruleAttributes, mergeAspectsAttributes(ruleContextBuilder.getAspects())
                ),
                PrerequisitesCollection(
                    ruleAndBaseAspectsPrerequisites.build(),
                    mergeRuleAndBaseAspectsAttributes(ruleAttributes, ruleContextBuilder.getAspects()),
                    ruleContextBuilder.getErrorConsumer(),
                    ruleContextBuilder.getRule(),
                    ruleContextBuilder.getRuleClassNameForLogging()
                ),
                PrerequisitesCollection(
                    mainAspectPrerequisites.build(),
                    mainAspect.getDefinition().getAttributes(),
                    ruleContextBuilder.getErrorConsumer(),
                    ruleContextBuilder.getRule(),
                    ruleContextBuilder.getRuleClassNameForLogging()
                ),
                execGroupCollection,
                baseTargetToolchainContexts,
                targetUsesAutoExecGroups
            )
        }

        private fun mergeRuleAndBaseAspectsAttributes(
            ruleAttributes: AttributeMap?, aspects: com.google.common.collect.ImmutableList<Aspect>
        ): AspectAwareAttributeMapper {
            val mergedBaseAspectsAttributes: LinkedHashMap<String?, Attribute?> = LinkedHashMap<String?, Attribute?>()
            for (i in 0..<aspects.size - 1) {
                for (attribute in aspects.get(i).getDefinition().getAttributes().values()) {
                    mergedBaseAspectsAttributes.putIfAbsent(attribute.getName(), attribute)
                }
            }
            return AspectAwareAttributeMapper(
                ruleAttributes,
                com.google.common.collect.ImmutableMap.copyOf<String?, Attribute?>(mergedBaseAspectsAttributes)
            )
        }
    }
}
