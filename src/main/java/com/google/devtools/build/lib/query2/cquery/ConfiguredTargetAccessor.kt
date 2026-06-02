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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.analysis.ConfiguredAspect

/**
 * A [TargetAccessor] for [ConfiguredTarget] objects.
 * 
 * 
 * Incomplete; we'll implement getVisibility when needed.
 */
class ConfiguredTargetAccessor(
    walkableGraph: WalkableGraph,
    queryEnvironment: ConfiguredTargetQueryEnvironment,
    topLevelAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>
) : TargetAccessor<CqueryNode?> {
    private val walkableGraph: WalkableGraph
    private val queryEnvironment: ConfiguredTargetQueryEnvironment
    private val lookupEnvironment: LookupEnvironment
    private val topLevelAspectsByTarget: java.util.function.Supplier<com.google.common.collect.ImmutableListMultimap<ConfiguredTargetKey?, ConfiguredAspect?>?>


    init {
        this.walkableGraph = walkableGraph
        this.queryEnvironment = queryEnvironment
        this.lookupEnvironment =
            EnvironmentForUtilities(
                ResultProvider { key: SkyKey? ->
                    try {
                        val value: SkyValue? = walkableGraph.getValue(key)
                        if (value != null) {
                            return@ResultProvider value
                        }
                        return@ResultProvider walkableGraph.getException(key)
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.IllegalStateException(
                            "Thread interrupted in the middle of looking up: " + key, e
                        )
                    }
                })
        this.topLevelAspectsByTarget =
            if (queryEnvironment.isSettingEnabled(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.INCLUDE_ASPECTS))
                com.google.common.base.Suppliers.memoize<com.google.common.collect.ImmutableListMultimap<ConfiguredTargetKey?, ConfiguredAspect?>?>(
                    com.google.common.base.Supplier {
                        topLevelAspects.entries.stream()
                            .collect(
                                TODO("Cannot convert element")
                            ) < java.util.Map.Entry < AspectKeyCreator.AspectKey
                    },
                    ConfiguredAspect >,
                    ConfiguredTargetKey,
                    ConfiguredAspect > com.google.common.collect.ImmutableListMultimap.toImmutableListMultimap<Any?, Any?, Any?>(
                        java.util.function.Function { entry: Any? -> entry.getKey().getBaseConfiguredTargetKey() },
                        java.util.function.Function { java.util.Map.Entry.value })
                )
        com.google.common.base.Suppliers.ofInstance<com.google.common.collect.ImmutableListMultimap<ConfiguredTargetKey?, ConfiguredAspect?>?>(
            com.google.common.collect.ImmutableListMultimap.of<ConfiguredTargetKey?, ConfiguredAspect?>()
        )
    }

    override fun getTargetKind(target: CqueryNode): String {
        val actualTarget = getTarget(target)
        return actualTarget.getTargetKind()
    }

    override fun getLabel(target: CqueryNode): String {
        return target.getOriginalLabel().toString()
    }

    override fun getPackage(target: CqueryNode): String {
        return target.getOriginalLabel().getPackageIdentifier().getPackageFragment().toString()
    }

    override fun isRule(target: CqueryNode): Boolean {
        val actualTarget = getTarget(target)
        return actualTarget is Rule
    }

    override fun isExecutableNonTestRule(target: CqueryNode): Boolean {
        val actualTarget = getTarget(target)
        return TargetUtils.isExecutableNonTestRule(actualTarget)
    }

    override fun isTestRule(target: CqueryNode): Boolean {
        val actualTarget = getTarget(target)
        return TargetUtils.isTestRule(actualTarget)
    }

    override fun isTestSuite(target: CqueryNode): Boolean {
        val actualTarget = getTarget(target)
        return TargetUtils.isTestSuiteRule(actualTarget)
    }

    /**
     * Returns all of `keyedConfiguredTarget`'s prerequisites.
     * 
     * 
     * Does not resolve aliases. So for aliases, this returns their `actual` attribute deps
     * (plus any implicit deps).
     * 
     * 
     * Use sparingly: this doesn't distinguish where those prerequisites come from. For example if
     * `keyedConfiguredTarget` depends on aspect A which depends on `//foo`, whether
     * `//foo` is returned here depends on the values of [ ][QueryEnvironment.Setting.INCLUDE_ASPECTS] or [QueryEnvironment.Setting.EXPLICIT_ASPECTS]
     * 
     * 
     * So this method returns the canonical direct dependencies as determined by cquery. But it
     * doesn't expose the logic cquery uses to determine that, nor the command-line flags that toggle
     * cquery's choices.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getPrerequisites(keyedConfiguredTarget: CqueryNode): MutableSet<CqueryNode?>? {
        return queryEnvironment.getFwdDeps(com.google.common.collect.ImmutableList.of<CqueryNode?>(keyedConfiguredTarget))
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    override fun getPrerequisites(
        caller: QueryExpression?,
        keyedConfiguredTarget: CqueryNode,
        attrName: String?,
        errorMsgPrefix: String?
    ): MutableList<CqueryNode?> {
        // Process aliases.
        val actual: CqueryNode = keyedConfiguredTarget.getActual()

        com.google.common.base.Preconditions.checkArgument(
            isRule(actual), "%s %s is not a rule configured target", errorMsgPrefix, getLabel(actual)
        )

        val depsByLabel: com.google.common.collect.ImmutableListMultimap<Label?, CqueryNode?> =
            com.google.common.collect.Multimaps.index<Label?, CqueryNode?>(
                queryEnvironment.getFwdDeps(com.google.common.collect.ImmutableList.of<CqueryNode?>(actual)),
                com.google.common.base.Function { obj: CqueryNode? -> obj.getOriginalLabel() })

        val rule: Rule = getTarget(actual) as Rule
        val configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>? =
            actual.getConfigConditions()
        val attributeMapper: ConfiguredAttributeMapper =
            ConfiguredAttributeMapper.of(
                rule,
                configConditions,
                keyedConfiguredTarget.getConfigurationChecksum(),  /* alwaysSucceed= */
                false
            )
        if (!attributeMapper.has(attrName)) {
            throw com.google.devtools.build.lib.query2.engine.QueryException(
                caller,
                java.lang.String.format(
                    "%sconfigured target of type %s does not have attribute '%s'",
                    errorMsgPrefix, rule.getRuleClass(), attrName
                ),
                ConfigurableQuery.Code.ATTRIBUTE_MISSING
            )
        }
        val toReturn: com.google.common.collect.ImmutableList.Builder<CqueryNode?> =
            com.google.common.collect.ImmutableList.builder<CqueryNode?>()
        attributeMapper.visitLabels(attrName, { label -> toReturn.addAll(depsByLabel.get(label)) })
        return toReturn.build()
    }

    override fun getStringListAttr(target: CqueryNode, attrName: String?): MutableList<String?> {
        val attributeMapper: ConfiguredAttributeMapper = getAttributes(target)
        return attributeMapper.get(attrName, Types.STRING_LIST)
    }

    override fun getStringAttr(target: CqueryNode, attrName: String?): String {
        val attributeMapper: ConfiguredAttributeMapper = getAttributes(target)
        return attributeMapper.get(attrName, Type.STRING)
    }

    override fun getAttrAsString(target: CqueryNode, attrName: String?): Iterable<String?> {
        val attributeMapper: ConfiguredAttributeMapper = getAttributes(target)
        val attribute: Attribute? = attributeMapper.getAttributeDefinition(attrName)
        if (attribute == null) {
            // Ignore unknown attributes.
            return com.google.common.collect.ImmutableList.of<String?>()
        }
        val attributeType: Type<*> = attribute.getType()

        var value: Any? = attributeMapper.get(attrName, attributeType)
        if (value == null) {
            return com.google.common.collect.ImmutableList.of<String?>()
        }

        if (attrName == "visibility"
            && attributeType.equals(BuildType.NODEP_LABEL_LIST)
        ) {
            // This special case for the visibility attribute is needed because its value is replaced
            // with an empty list during package loading if it is public or private in order not to visit
            // the package called 'visibility'.
            val actualTarget = getTarget(target)
            com.google.common.base.Preconditions.checkArgument(actualTarget is Rule)
            val rule: Rule = actualTarget as Rule
            value = attributeType.cast(rule.getVisibilityDeclaredLabels())
        }

        // Return a single-valued list, because a configured target only has one value for the
        // attribute. Flatten to a string regardless of the actual type so that regex-based matches can
        // be performed.
        return com.google.common.collect.ImmutableList.of<E?>(TargetUtils.convertAttributeValue(attributeType, value))
    }

    private fun getAttributes(target: CqueryNode): ConfiguredAttributeMapper {
        val actualTarget = getTarget(target)
        com.google.common.base.Preconditions.checkArgument(actualTarget is Rule)
        val rule: Rule? = actualTarget as Rule?
        val configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>? =
            target.getConfigConditions()
        return ConfiguredAttributeMapper.of(
            rule, configConditions, target.getConfigurationChecksum(),  /* alwaysSucceed= */false
        )
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
    override fun getVisibility(
        caller: QueryExpression?, from: CqueryNode?
    ): com.google.common.collect.ImmutableSet<QueryVisibility<CqueryNode?>?>? {
        // TODO(bazel-team): implement this if needed.
        throw com.google.devtools.build.lib.query2.engine.QueryException(
            "visible() is not supported on configured targets",
            ConfigurableQuery.Code.VISIBLE_FUNCTION_NOT_SUPPORTED
        )
    }

    fun getTarget(configuredTarget: CqueryNode): Target {
        // Dereference any aliases that might be present.
        val label: Label = configuredTarget.getOriginalLabel()
        try {
            return queryEnvironment.getTarget(label)
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.IllegalStateException("Thread interrupted in the middle of getting a Target.", e)
        } catch (e: TargetNotFoundException) {
            throw java.lang.IllegalStateException("Unable to get target from package in accessor.", e)
        }
    }

    fun getLookupEnvironment(): LookupEnvironment {
        return lookupEnvironment
    }

    /** Returns the rule that generates the given output file.  */
    @Throws(java.lang.InterruptedException::class)
    fun getGeneratingConfiguredTarget(kct: CqueryNode): RuleConfiguredTarget? {
        com.google.common.base.Preconditions.checkArgument(kct is OutputFileConfiguredTarget)
        return (walkableGraph.getValue(
            ConfiguredTargetKey.builder()
                .setLabel((kct as OutputFileConfiguredTarget).getGeneratingRule().getLabel())
                .setConfigurationKey(kct.getConfigurationKey())
                .build()
        ) as ConfiguredTargetValue)
            .getConfiguredTarget() as RuleConfiguredTarget?
    }

    /** Returns the top-level aspects applied to the given [CqueryNode].  */
    fun getTopLevelAspects(cn: CqueryNode): com.google.common.collect.ImmutableList<ConfiguredAspect?> {
        if (cn.getLookupKey() !is ConfiguredTargetKey) {
            return com.google.common.collect.ImmutableList.of<ConfiguredAspect?>()
        }
        return topLevelAspectsByTarget.get().get(key)
    }
}
