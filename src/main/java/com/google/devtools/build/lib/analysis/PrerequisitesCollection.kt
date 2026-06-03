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

import com.google.devtools.build.lib.actions.Artifact

/** Collection of the attributes dependencies available in a [RuleContext].  */
class PrerequisitesCollection private constructor(
    attributeToPrerequisitesMap: ImmutableSortedKeyListMultimap<String?, ConfiguredTargetAndData?>,
    attributesMapper: AttributesMapper,
    ruleErrorConsumer: RuleErrorConsumer,
    rule: Rule,
    ruleClassNameForLogging: String?
) {
    private interface AttributesMapper {
        fun getAttribute(attributeName: String?): Attribute
    }

    private val attributeToPrerequisitesMap: ImmutableSortedKeyListMultimap<String?, ConfiguredTargetAndData?>

    private val attributesMapper: AttributesMapper

    private val ruleErrorConsumer: RuleErrorConsumer
    private val rule: Rule
    private val ruleClassNameForLogging: String?

    internal constructor(
        attributeToPrerequisitesMap: ImmutableSortedKeyListMultimap<String?, ConfiguredTargetAndData?>,
        attributes: com.google.common.collect.ImmutableMap<String?, Attribute?>,
        ruleErrorConsumer: RuleErrorConsumer,
        rule: Rule,
        ruleClassNameForLogging: String?
    ) : this(
        attributeToPrerequisitesMap,  /* attributesMapper= */
        AttributesMapper { key: String? -> attributes.get(key) },
        ruleErrorConsumer,
        rule,
        ruleClassNameForLogging
    )

    internal constructor(
        attributeToPrerequisitesMap: ImmutableSortedKeyListMultimap<String?, ConfiguredTargetAndData?>,
        attributes: AttributeMap,
        ruleErrorConsumer: RuleErrorConsumer,
        rule: Rule,
        ruleClassNameForLogging: String?
    ) : this(
        attributeToPrerequisitesMap,  /* attributesMapper= */
        attributes::getAttributeDefinition,
        ruleErrorConsumer,
        rule,
        ruleClassNameForLogging
    )

    init {
        this.attributeToPrerequisitesMap = attributeToPrerequisitesMap
        this.attributesMapper = attributesMapper
        this.ruleErrorConsumer = ruleErrorConsumer
        this.rule = rule
        this.ruleClassNameForLogging = ruleClassNameForLogging
    }

    fun has(attributeName: String?): Boolean {
        return attributesMapper.getAttribute(attributeName) != null
    }

    /** Returns a list of all prerequisites as `ConfiguredTarget` objects.  */
    fun getAllPrerequisites(): com.google.common.collect.ImmutableList<out TransitiveInfoCollection?> {
        return attributeToPrerequisitesMap.values().stream()
            .map(ConfiguredTargetAndData::getConfiguredTarget)
            .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
    }

    /** Returns the [ConfiguredTargetAndData] the given attribute.  */
    fun getPrerequisiteConfiguredTargets(attributeName: String?): MutableList<ConfiguredTargetAndData> {
        return attributeToPrerequisitesMap.get(attributeName)
    }

    /**
     * Returns the list of transitive info collections that feed into this target through the
     * specified attribute.
     */
    fun getPrerequisites(attributeName: String): MutableList<out TransitiveInfoCollection?> {
        val attribute: Attribute? = attributesMapper.getAttribute(attributeName)
        if (attribute == null) {
            return com.google.common.collect.ImmutableList.of<TransitiveInfoCollection?>()
        }

        val prerequisiteConfiguredTargets: MutableList<ConfiguredTargetAndData>
        // android_binary, android_test, and android_binary_internal override deps to use a split
        // transition.
        if ((rule.getRuleClass().equals("android_binary")
                    || rule.getRuleClass().equals("android_test")
                    || rule.getRuleClass().equals("android_binary_internal"))
            && attributeName == "deps"
            && attribute.getTransitionFactory().isSplit()
        ) {
            // TODO(b/168038145): Restore legacy behavior of returning the prerequisites from the first
            // portion of the split transition.
            // Callers should be identified, cleaned up, and this check removed.
            val map: MutableMap<com.google.common.base.Optional<String?>?, MutableList<ConfiguredTargetAndData?>?> =
                getSplitPrerequisites(attributeName)
            prerequisiteConfiguredTargets =
                if (map.isEmpty()) com.google.common.collect.ImmutableList.of<ConfiguredTargetAndData?>() else map.entrySet()
                    .iterator().next().getValue()
        } else {
            prerequisiteConfiguredTargets = getPrerequisiteConfiguredTargets(attributeName)
        }

        return com.google.common.collect.Lists.transform<ConfiguredTargetAndData?, TransitiveInfoCollection?>(
            prerequisiteConfiguredTargets, ConfiguredTargetAndData::getConfiguredTarget
        )
    }

    /**
     * Returns all the providers of the specified type that are listed under the specified attribute
     * of this target in the BUILD file.
     */
    fun <C : TransitiveInfoProvider?> getPrerequisites(
        attributeName: String, classType: java.lang.Class<C?>
    ): MutableList<C?> {
        AnalysisUtils.Companion.checkProvider<C?>(classType)
        return AnalysisUtils.Companion.getProviders<C?>(getPrerequisites(attributeName), classType)
    }

    /**
     * Returns all the declared Starlark wrapped providers for the specified constructor under the
     * specified attribute of this target in the BUILD file.
     */
    @Throws(RuleErrorException::class)
    fun <T> getPrerequisites(
        attributeName: String, starlarkKey: StarlarkProviderWrapper<T?>?
    ): com.google.common.collect.ImmutableList<T?>? {
        return getProviders(getPrerequisites(attributeName), starlarkKey)
    }

    /**
     * Returns all the declared providers (native and Starlark) for the specified constructor under
     * the specified attribute of this target in the BUILD file.
     */
    fun <T : Info?> getPrerequisites(
        attributeName: String, builtinProvider: BuiltinProvider<T?>?
    ): MutableList<T?>? {
        return getProviders(getPrerequisites(attributeName), builtinProvider)
    }

    /**
     * Returns the prerequisites keyed by their transition keys. If the split transition is not active
     * (e.g. split() returned an empty list), the key is an empty Optional.
     */
    fun getSplitPrerequisites(
        attributeName: String?
    ): MutableMap<com.google.common.base.Optional<String?>?, MutableList<ConfiguredTargetAndData?>?> {
        checkAttributeIsDependency(attributeName)
        // Use an ImmutableListMultimap.Builder here to preserve ordering.
        val result: com.google.common.collect.ImmutableListMultimap.Builder<com.google.common.base.Optional<String?>?, ConfiguredTargetAndData?> =
            com.google.common.collect.ImmutableListMultimap.builder<com.google.common.base.Optional<String?>?, ConfiguredTargetAndData?>()
        val deps: MutableList<ConfiguredTargetAndData> = getPrerequisiteConfiguredTargets(attributeName)
        for (t in deps) {
            val transitionKeys: com.google.common.collect.ImmutableList<String> = t.getTransitionKeys()
            if (transitionKeys.isEmpty()) {
                // The split transition is not active, i.e. does not change build configurations.
                // TODO(jungjw): Investigate if we need to do a check here.
                return com.google.common.collect.ImmutableMap.of<com.google.common.base.Optional<String?>?, MutableList<ConfiguredTargetAndData?>?>(
                    com.google.common.base.Optional.absent<String?>(),
                    deps
                )
            }
            for (key in transitionKeys) {
                result.put(com.google.common.base.Optional.of<String?>(key), t)
            }
        }
        return com.google.common.collect.Multimaps.asMap<com.google.common.base.Optional<String?>?, ConfiguredTargetAndData?>(
            result.build()
        )
    }

    /**
     * Returns the transitive info collection that feeds into this target through the specified
     * attribute. Returns null if the attribute is empty.
     */
    fun getPrerequisite(attributeName: String?): TransitiveInfoCollection? {
        checkAttributeIsDependency(attributeName)
        val elements: MutableList<ConfiguredTargetAndData> = getPrerequisiteConfiguredTargets(attributeName)
        com.google.common.base.Preconditions.checkState(
            elements.size() <= 1,
            "%s attribute %s produces more than one prerequisite",
            ruleClassNameForLogging,
            attributeName
        )
        return if (elements.isEmpty()) null else elements.get(0).getConfiguredTarget()
    }

    /**
     * Returns the declared provider (native and Starlark) for the specified constructor under the
     * specified attribute of this target in the BUILD file. May return null if there is no
     * TransitiveInfoCollection under the specified attribute.
     */
    fun <T : Info?> getPrerequisite(
        attributeName: String?, builtinProvider: BuiltinProvider<T?>?
    ): T? {
        val prerequisite: TransitiveInfoCollection? = getPrerequisite(attributeName)
        return if (prerequisite == null) null else prerequisite.get(builtinProvider)
    }

    /**
     * Returns the specified provider of the prerequisite referenced by the attribute in the argument.
     * If the attribute is empty or it does not support the specified provider, returns null.
     */
    fun <C : TransitiveInfoProvider?> getPrerequisite(
        attributeName: String?, provider: java.lang.Class<C?>?
    ): C? {
        val prerequisite: TransitiveInfoCollection? = getPrerequisite(attributeName)
        return if (prerequisite == null) null else prerequisite.getProvider(provider)
    }

    @Throws(RuleErrorException::class)
    fun <T> getPrerequisite(attributeName: String?, key: StarlarkProviderWrapper<T?>?): T? {
        val prerequisite: TransitiveInfoCollection? = getPrerequisite(attributeName)
        return if (prerequisite == null) null else prerequisite.get(key)
    }

    /**
     * For the specified attribute "attributeName" (which must be of type label), resolves the
     * ConfiguredTarget and returns its single build artifact.
     * 
     * 
     * If the attribute is optional, has no default and was not specified, then null will be
     * returned. Note also that null is returned (and an attribute error is raised) if there wasn't
     * exactly one build artifact for the target.
     */
    fun getPrerequisiteArtifact(attributeName: String?): Artifact? {
        val target: TransitiveInfoCollection? = getPrerequisite(attributeName)
        return transitiveInfoCollectionToArtifact(attributeName, target)
    }

    private fun transitiveInfoCollectionToArtifact(
        attributeName: String?, target: TransitiveInfoCollection?
    ): Artifact? {
        if (target != null) {
            val artifacts: NestedSet<Artifact?> = target.getProvider(FileProvider::class.java).getFilesToBuild()
            if (artifacts.isSingleton()) {
                return artifacts.getSingleton()
            } else {
                ruleErrorConsumer.attributeError(
                    attributeName, target.getLabel() + " expected a single artifact"
                )
            }
        }
        return null
    }

    /**
     * Returns the prerequisite referred to by the specified attribute. Also checks whether the
     * attribute is marked as executable and that the target referred to can actually be executed.
     * 
     * @param attributeName the name of the attribute
     * @return the [FilesToRunProvider] interface of the prerequisite.
     */
    fun getExecutablePrerequisite(attributeName: String?): FilesToRunProvider? {
        val ruleDefinition: Attribute = attributesMapper.getAttribute(attributeName)

        com.google.common.base.Preconditions.checkNotNull<Any?>(
            ruleDefinition, "%s attribute %s is not defined", ruleClassNameForLogging, attributeName
        )
        com.google.common.base.Preconditions.checkState(
            ruleDefinition.isExecutable(),
            "%s attribute %s is not configured to be executable",
            ruleClassNameForLogging,
            attributeName
        )

        val prerequisite: TransitiveInfoCollection? = getPrerequisite(attributeName)
        if (prerequisite == null) {
            return null
        }

        val result: FilesToRunProvider? = prerequisite.getProvider(FilesToRunProvider::class.java)
        if (result == null || result.getExecutable() == null) {
            ruleErrorConsumer.attributeError(
                attributeName, prerequisite.getLabel() + " does not refer to a valid executable target"
            )
        }
        return result
    }

    /**
     * Returns all the providers of the specified type that are listed under the specified attribute
     * of this target in the BUILD file, and that contain the specified provider.
     */
    fun <C : TransitiveInfoProvider?>
            getPrerequisitesIf(
        attributeName: String, classType: java.lang.Class<C?>
    ): Iterable<out TransitiveInfoCollection?> {
        AnalysisUtils.Companion.checkProvider<C?>(classType)
        return AnalysisUtils.Companion.filterByProvider(getPrerequisites(attributeName), classType)
    }

    private fun checkAttributeIsDependency(attributeName: String?) {
        val attributeDefinition: Attribute = attributesMapper.getAttribute(attributeName)
        com.google.common.base.Preconditions.checkNotNull(
            attributeDefinition,
            "%s: %s attribute %s is not defined",
            rule.getLocation(),
            ruleClassNameForLogging,
            attributeName
        )
        com.google.common.base.Preconditions.checkState(
            attributeDefinition.getType().getLabelClass() === LabelClass.DEPENDENCY,
            "%s attribute %s is not a label type attribute",
            ruleClassNameForLogging,
            attributeName
        )
    }
}
