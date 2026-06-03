// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.DependencyKind.OUTPUT_FILE_RULE_DEPENDENCY

/**
 * Helpers for resolution for dependencies between configured targets.
 * 
 * 
 * Includes logic to determine all attribute dependencies and their associated labels.
 */
object DependencyResolutionHelpers {
    @Throws(Failure::class, java.lang.InterruptedException::class)
    fun computeDependencyLabels(
        node: TargetAndConfiguration,
        aspects: com.google.common.collect.ImmutableList<Aspect?>,
        configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>?,
        toolchainContexts: ToolchainCollection<ToolchainContext?>?,
        baseTargetUnloadedToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>?
    ): DependencyLabels {
        val target: Target = node.getTarget()
        val config: BuildConfigurationValue? = node.getConfiguration()
        val outgoingLabels: OrderedSetMultimap<DependencyKind?, Label?> = OrderedSetMultimap.create()

        // TODO(bazel-team): Figure out a way to implement the below using LabelVisitationUtils.
        val fromRule: Rule
        var attributeMap: ConfiguredAttributeMapper? = null
        if (target is OutputFile) {
            com.google.common.base.Preconditions.checkNotNull<BuildConfigurationValue?>(config)
            addVisibilityDepLabels(target.getVisibilityDependencyLabels(), outgoingLabels)
            addTransitiveVisibilityDepLabel(
                target.getPackageDeclarations().getPackageArgs().transitiveVisibility(), outgoingLabels
            )
            val rule: Rule = (target as OutputFile).getGeneratingRule()
            outgoingLabels.put(OUTPUT_FILE_RULE_DEPENDENCY, rule.getLabel())
            if (com.google.common.collect.Iterables.any<Aspect?>(
                    aspects,
                    com.google.common.base.Predicate { a: Aspect? -> a.getDefinition().applyToFiles() })
            ) {
                attributeMap = ConfiguredAttributeMapper.of(rule, configConditions, config)
                resolveAttributes(getAspectAttributes(aspects), outgoingLabels, rule, attributeMap, config)
            }
            addToolchainDeps(toolchainContexts, outgoingLabels)
        } else if (target is InputFile) {
            addVisibilityDepLabels(target.getVisibilityDependencyLabels(), outgoingLabels)
            addTransitiveVisibilityDepLabel(
                target.getPackageDeclarations().getPackageArgs().transitiveVisibility(), outgoingLabels
            )
        } else if (target is EnvironmentGroup) {
            addVisibilityDepLabels(target.getVisibilityDependencyLabels(), outgoingLabels)
        } else if (target is Rule) {
            fromRule = target
            attributeMap = ConfiguredAttributeMapper.of(fromRule, configConditions, config)
            addTransitiveVisibilityDepLabel(
                fromRule.getPackageDeclarations().getPackageArgs().transitiveVisibility(),
                outgoingLabels
            )
            visitRule(
                node,
                aspects,
                attributeMap,
                toolchainContexts,
                baseTargetUnloadedToolchainContexts,
                outgoingLabels
            )
        } else if (target is PackageGroup) {
            outgoingLabels.putAll(VISIBILITY_DEPENDENCY, target.getIncludes())
        } else {
            throw java.lang.IllegalStateException(target.getLabel().toString())
        }
        return DependencyLabels(outgoingLabels, attributeMap)
    }

    fun getExecutionPlatformLabel(
        kind: AttributeDependencyKind,
        toolchainContexts: ToolchainCollection<ToolchainContext?>?,
        baseTargetUnloadedToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>?,
        aspectsList: com.google.common.collect.ImmutableList<Aspect?>
    ): ExecutionPlatformResult {
        if (aspectsList.isEmpty() || isMainAspect(aspectsList, kind.getOwningAspect())) {
            return getExecutionPlatformLabel(kind, toolchainContexts)
        } else if (kind.getOwningAspect() == null) {
            // During aspect evaluation, use {@code baseTargetUnloadedToolchainContexts} for the base
            // target's dependencies.
            return getExecutionPlatformLabel(kind, baseTargetUnloadedToolchainContexts)
        } else {
            val executionPlatformResult =
                getExecutionPlatformLabel(kind, toolchainContexts)
            if (executionPlatformResult.kind() == com.google.devtools.build.lib.analysis.DependencyResolutionHelpers.ExecutionPlatformResult.Kind.ERROR) {
                // TODO(b/373963347): Make the toolchain contexts of base aspects available to be used with
                // their corresponding dependencies.
                // Currently dependencies of the base aspects are resolved with the toolchain context of the
                // main aspect, skip errors as actual errors would be reported during the base aspect
                // evaluation.
                return ExecutionPlatformResult.Companion.ofSkip()
            } else {
                return executionPlatformResult
            }
        }
    }

    private fun getExecutionPlatformLabel(
        kind: AttributeDependencyKind,
        toolchainContexts: ToolchainCollection<out ToolchainContext?>?
    ): ExecutionPlatformResult {
        if (toolchainContexts == null) {
            return ExecutionPlatformResult.Companion.ofNullLabel()
        }

        val transitionFactory: TransitionFactory<AttributeTransitionData?> =
            kind.getAttribute().getTransitionFactory()
        if (transitionFactory !is ExecutionTransitionFactory) {
            return ExecutionPlatformResult.Companion.ofLabel(
                toolchainContexts
                    .getToolchainContext(DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME)
                    .executionPlatform()
                    .label()
            )
        }

        val execGroup: String? = (transitionFactory as ExecutionTransitionFactory).getExecGroup()
        if (toolchainContexts.hasToolchainContext(execGroup)) {
            val platform: PlatformInfo? = toolchainContexts.getToolchainContext(execGroup).executionPlatform()
            return if (platform == null)
                ExecutionPlatformResult.Companion.ofNullLabel()
            else
                ExecutionPlatformResult.Companion.ofLabel(platform.label())
        }

        return ExecutionPlatformResult.Companion.ofError(
            java.lang.String.format(
                "Attr '%s' declares a transition for non-existent exec group '%s'",
                kind.getAttribute().getName(), execGroup
            )
        )
    }

    /** True if `owningAspect` is the main aspect, the last one in `aspectsList`.  */
    private fun isMainAspect(
        aspectsList: com.google.common.collect.ImmutableList<Aspect?>, owningAspect: AspectClass?
    ): Boolean {
        return com.google.common.collect.Iterables.getLast<Aspect?>(aspectsList).getAspectClass().equals(owningAspect)
    }

    @Throws(Failure::class, java.lang.InterruptedException::class)
    private fun visitRule(
        node: TargetAndConfiguration,
        aspects: com.google.common.collect.ImmutableList<Aspect?>,
        attributeMap: ConfiguredAttributeMapper,
        toolchainContexts: ToolchainCollection<ToolchainContext?>?,
        baseTargetUnloadedToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>?,
        outgoingLabels: OrderedSetMultimap<DependencyKind?, Label?>
    ) {
        com.google.common.base.Preconditions.checkArgument(node.getTarget() is Rule, node)
        val ruleConfig: BuildConfigurationValue =
            com.google.common.base.Preconditions.checkNotNull(node.getConfiguration(), node)
        val rule: Rule = node.getTarget() as Rule

        try {
            attributeMap.validateAttributes()
        } catch (ex: ConfiguredAttributeMapper.ValidationException) {
            throw DependencyResolutionHelpers.Failure(rule.getLocation(), ex.getMessage())
        }

        val visibilityDepLabels: Iterable<Label?>? = rule.getVisibilityDependencyLabels()
        addVisibilityDepLabels(visibilityDepLabels, outgoingLabels)
        resolveAttributes(getAttributes(rule, aspects), outgoingLabels, rule, attributeMap, ruleConfig)

        // Add the rule's visibility labels (which may come from the rule or from package defaults).
        addExplicitDeps(outgoingLabels, rule, "visibility", visibilityDepLabels)

        // Add package default constraints when the rule doesn't explicitly declare them.
        //
        // Note that this can have subtle implications for constraint semantics. For example: say that
        // package defaults declare compatibility with ':foo' and rule R declares compatibility with
        // ':bar'. Does that mean that R is compatible with [':foo', ':bar'] or just [':bar']? In other
        // words, did R's author intend to add additional compatibility to the package defaults or to
        // override them? More severely, what if package defaults "restrict" support to just [':baz']?
        // Should R's declaration signify [':baz'] + ['bar'], [ORIGINAL_DEFAULTS] + ['bar'], or
        // something else?
        //
        // Rather than try to answer these questions with possibly confusing logic, we take the
        // simple approach of assigning the rule's "restriction" attribute to the rule-declared value if
        // it exists, else the package defaults value (and likewise for "compatibility"). This may not
        // always provide what users want, but it makes it easy for them to understand how rule
        // declarations and package defaults intermix (and how to refactor them to get what they want).
        //
        // An alternative model would be to apply the "rule declaration" / "rule class defaults"
        // relationship, i.e. the rule class' "compatibility" and "restriction" declarations are merged
        // to generate a set of default environments, then the rule's declarations are independently
        // processed on top of that. This protects against obscure coupling behavior between
        // declarations from wildly different places (e.g. it offers clear answers to the examples posed
        // above). But within the scope of a single package it seems better to keep the model simple and
        // make the user responsible for resolving ambiguities.
        if (!rule.isAttributeValueExplicitlySpecified(RuleClass.COMPATIBLE_ENVIRONMENT_ATTR)) {
            addExplicitDeps(
                outgoingLabels,
                rule,
                RuleClass.COMPATIBLE_ENVIRONMENT_ATTR,
                rule.getPackageDeclarations().getPackageArgs().defaultCompatibleWith()
            )
        }
        if (!rule.isAttributeValueExplicitlySpecified(RuleClass.RESTRICTED_ENVIRONMENT_ATTR)) {
            addExplicitDeps(
                outgoingLabels,
                rule,
                RuleClass.RESTRICTED_ENVIRONMENT_ATTR,
                rule.getPackageDeclarations().getPackageArgs().defaultRestrictedTo()
            )
        }

        addToolchainDeps(toolchainContexts, outgoingLabels)
        addBaseTargetToolchainDeps(baseTargetUnloadedToolchainContexts, outgoingLabels)
    }

    private fun addToolchainDeps(
        toolchainContexts: ToolchainCollection<ToolchainContext?>?,
        outgoingLabels: OrderedSetMultimap<DependencyKind?, Label?>
    ) {
        if (toolchainContexts != null) {
            for (entry in toolchainContexts.contextMap().entrySet()) {
                outgoingLabels.putAll(
                    DependencyKind.forExecGroup(entry.getKey()),
                    entry.getValue().resolvedToolchainLabels()
                )
            }
        }
    }

    private fun addBaseTargetToolchainDeps(
        toolchainContexts: ToolchainCollection<UnloadedToolchainContext?>?,
        outgoingLabels: OrderedSetMultimap<DependencyKind?, Label?>
    ) {
        if (toolchainContexts == null) {
            return
        }
        for (execGroup in toolchainContexts.contextMap().entrySet()) {
            for (toolchainTypeToResolved in execGroup.getValue().toolchainTypeToResolved().asMap().entrySet()) {
                // map entries from (exec group, toolchain type) to resolved toolchain labels. We need to
                // distinguish the resolved toolchains per type because aspects propagate on toolchains
                // based on the types specified in `toolchains_aspects`. So even if 2 types resolved to the
                // same toolchain target, their CT will be different if an aspect propagates to one type but
                // not the other.
                outgoingLabels.putAll(
                    DependencyKind.forBaseTargetExecGroup(
                        execGroup.getKey(), toolchainTypeToResolved.getKey().typeLabel()
                    ),
                    toolchainTypeToResolved.getValue()
                )
            }
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun resolveAttributes(
        attributeDependencyKinds: Iterable<AttributeDependencyKind>,
        outgoingLabels: OrderedSetMultimap<DependencyKind?, Label?>,
        rule: Rule?,
        attributeMap: ConfiguredAttributeMapper,
        ruleConfig: BuildConfigurationValue
    ) {
        for (dependencyKind in attributeDependencyKinds) {
            val attribute: Attribute = dependencyKind.getAttribute()
            // Not only is resolving CONFIG_SETTING_DEPS_ATTRIBUTE deps here wasteful, since the only
            // place they're used is in ConfiguredTargetFunction.getConfigConditions, but it actually
            // breaks trimming as shown by
            // FeatureFlagManualTrimmingTest#featureFlagInUnusedSelectBranchButNotInTransitiveConfigs_DoesNotError
            // because it resolves a dep that trimming (correctly) doesn't account for because it's part
            // of an unchosen select() branch.
            if (attribute.getName().equals(RuleClass.CONFIG_SETTING_DEPS_ATTRIBUTE)) {
                continue
            }
            val type: Type<*> = attribute.getType()
            if (type === BuildType.OUTPUT || type === BuildType.OUTPUT_LIST || type === BuildType.NODEP_LABEL || type === BuildType.NODEP_LABEL_LIST || type === BuildType.DORMANT_LABEL || type === BuildType.DORMANT_LABEL_LIST || type === BuildType.GENQUERY_SCOPE_TYPE || type === BuildType.GENQUERY_SCOPE_TYPE_LIST) {
                // These types invoke visitLabels() so that they are reported in "bazel query" but do not
                // create a dependency. Maybe it's better to remove that, but then the labels() query
                // function would need to be rethought.
                continue
            }

            resolveAttribute<Any?>(
                attribute, type, dependencyKind, outgoingLabels, rule, attributeMap, ruleConfig
            )
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun <T> resolveAttribute(
        attribute: Attribute,
        type: Type<T?>,
        dependencyKind: AttributeDependencyKind,
        outgoingLabels: OrderedSetMultimap<DependencyKind?, Label?>,
        rule: Rule?,
        attributeMap: ConfiguredAttributeMapper,
        ruleConfig: BuildConfigurationValue
    ) {
        var attributeValue: T? = null
        if (attribute.isImplicit()) {
            // Since the attributes that come from aspects do not appear in attributeMap, we have to get
            // their values from somewhere else. This incidentally means that aspects attributes are not
            // configurable. It would be nice if that wasn't the case, but we'd have to revamp how
            // attribute mapping works, which is a large chunk of work.
            if (dependencyKind.getOwningAspect() == null) {
                attributeValue = attributeMap.get(attribute.getName(), type)
            } else {
                val defaultValue: Any? = attribute.getDefaultValue(rule)
                attributeValue =
                    type.cast(
                        if (defaultValue is ComputedDefault)
                            defaultValue.getDefault(attributeMap)
                        else
                            defaultValue
                    )
            }
        } else if (attribute.isMaterializing()) {
            // These attributes are resolved by calling the materializer function in
            // DependencyMapProducer. The reason is that they need the analyzed versions some direct
            // dependencies and we can't do that here.
            outgoingLabels.put(dependencyKind, null)
        } else if (attribute.isLateBound()) {
            attributeValue =
                type.cast(
                    DependencyResolutionHelpers.resolveLateBoundDefault<FragmentT?>(
                        rule,
                        attributeMap,
                        attribute,
                        ruleConfig
                    )
                )
        } else if (dependencyKind.getOwningAspect() == null && attributeMap.has(attribute.getName())) {
            // This condition is false for aspect attributes that do not give rise to dependencies because
            // attributes that come from aspects do not appear in attributeMap (see the comment in the
            // case that handles implicit attributes).
            attributeValue = attributeMap.get(attribute.getName(), type)
        }

        if (attributeValue == null) {
            return
        }

        type.visitLabels(
            { depLabel, ctx -> outgoingLabels.put(dependencyKind, depLabel) },
            attributeValue,  /*context=*/
            null
        )
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(java.lang.InterruptedException::class)
    fun <FragmentT> resolveLateBoundDefault(
        rule: Rule?, attributeMap: AttributeMap?, attribute: Attribute, ruleConfig: BuildConfigurationValue
    ): Any? {
        com.google.common.base.Preconditions.checkState(!attribute.getTransitionFactory().isSplit())
        val lateBoundDefault: LateBoundDefault<FragmentT?, *> =
            attribute.getLateBoundDefault() as LateBoundDefault<FragmentT?, *>

        val fragmentClass: java.lang.Class<FragmentT?> = lateBoundDefault.getFragmentClass()
        try {
            // TODO(b/65746853): remove this when nothing uses it anymore
            if (BuildConfigurationValue::class.java == fragmentClass // noconfig targets can't meaningfully parse late-bound defaults. See NoConfigTransition.
                && !ruleConfig.getOptions().hasNoConfig()
            ) {
                return lateBoundDefault.resolve(rule, attributeMap, fragmentClass.cast(ruleConfig))
            }
            if (java.lang.Void::class.java == fragmentClass) {
                return lateBoundDefault.resolve(
                    rule, attributeMap,  /* input= */null /* analysisContext= */ /* eventHandler= */
                )
            }
            val fragment: FragmentT? =
                fragmentClass.cast(ruleConfig.getFragment(fragmentClass as java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>))
            if (fragment == null) {
                return null
            }
            return lateBoundDefault.resolve(
                rule, attributeMap, fragment /* analysisContext= */ /* eventHandler= */
            )
        } catch (e: net.starlark.java.eval.EvalException) {
            // Materializers should not be called here and those are the only kind of late-bound defaults
            // that can throw these exceptions.
            throw java.lang.IllegalStateException(e)
        }
    }

    /**
     * Adds new dependencies to the given rule under the given attribute name
     * 
     * @param attrName the name of the attribute to add dependency labels to
     * @param labels the dependencies to add
     */
    private fun addExplicitDeps(
        outgoingLabels: OrderedSetMultimap<DependencyKind?, Label?>,
        rule: Rule,
        attrName: String?,
        labels: Iterable<Label?>?
    ) {
        if (!rule.isAttrDefined(attrName, BuildType.LABEL_LIST)
            && !rule.isAttrDefined(attrName, BuildType.NODEP_LABEL_LIST)
        ) {
            return
        }
        val attribute: Attribute? =
            rule.getRuleClassObject().getAttributeProvider().getAttributeByName(attrName)
        outgoingLabels.putAll(AttributeDependencyKind.forRule(attribute), labels)
    }

    /** Returns the attributes that should be visited for this rule/aspect combination.  */
    private fun getAttributes(
        rule: Rule, aspects: com.google.common.collect.ImmutableList<Aspect?>
    ): com.google.common.collect.ImmutableList<AttributeDependencyKind> {
        val result: com.google.common.collect.ImmutableList.Builder<AttributeDependencyKind?> =
            com.google.common.collect.ImmutableList.builder<AttributeDependencyKind?>()
        val ruleAndBaseAspectsProcessedAttributes: HashSet<String?> = HashSet<String?>()

        // For aspects evaluation, all attributes of the main aspect (last aspect in {@code aspects}
        // should be added, even if they have the same name as an attribute in the rule or a base aspect
        // because main aspect attributes are separated and retrieved from `ctx.attr`.

        // Attributes of the underlying rule and base aspects are merged and retrieved from
        // `ctx.rule.attr` with rule attributes taking precedence then aspects' attributes based on the
        // aspect order in the aspects path (lowest order to highest).
        val ruleAttributes: MutableList<Attribute> =
            rule.getRuleClassObject().getAttributeProvider().getAttributes()
        for (attribute in ruleAttributes) {
            result.add(AttributeDependencyKind.forRule(attribute))
            ruleAndBaseAspectsProcessedAttributes.add(attribute.getName())
        }

        addAspectAttributes(aspects, ruleAndBaseAspectsProcessedAttributes, result)

        return result.build()
    }

    private fun getAspectAttributes(
        aspects: com.google.common.collect.ImmutableList<Aspect?>
    ): com.google.common.collect.ImmutableList<AttributeDependencyKind> {
        val result: com.google.common.collect.ImmutableList.Builder<AttributeDependencyKind?> =
            com.google.common.collect.ImmutableList.builder<AttributeDependencyKind?>()
        addAspectAttributes(aspects, HashSet<String?>(), result)
        return result.build()
    }

    private fun addAspectAttributes(
        aspects: com.google.common.collect.ImmutableList<Aspect?>,
        processedAttributes: MutableSet<String?>,
        attributes: com.google.common.collect.ImmutableList.Builder<AttributeDependencyKind?>
    ) {
        if (aspects.isEmpty()) {
            return
        }

        // Add all the main aspect's attributes
        val mainAspect: Aspect? = com.google.common.collect.Iterables.getLast<Aspect?>(aspects, null)
        for (attribute in mainAspect.getDefinition().getAttributes().values()) {
            attributes.add(AttributeDependencyKind.forAspect(attribute, mainAspect.getAspectClass()))
        }

        // For base aspects, if multiple attributes have the same name, take the first encountered in
        // the aspects path.
        for (aspect in aspects.subList(0, aspects.size() - 1)) {
            for (attribute in aspect.getDefinition().getAttributes().values()) {
                if (processedAttributes.add(attribute.getName())) {
                    attributes.add(AttributeDependencyKind.forAspect(attribute, aspect.getAspectClass()))
                }
            }
        }
    }

    private fun addVisibilityDepLabels(
        labels: Iterable<Label?>?, outgoingLabels: OrderedSetMultimap<DependencyKind?, Label?>
    ) {
        outgoingLabels.putAll(VISIBILITY_DEPENDENCY, labels)
    }

    private fun addTransitiveVisibilityDepLabel(
        label: Label?, outgoingLabels: OrderedSetMultimap<DependencyKind?, Label?>
    ) {
        if (label != null) {
            outgoingLabels.put(TRANSITIVE_VISIBILITY_DEPENDENCY, label)
        }
    }

    /** The tuple [.computeDependencyLabels] outputs.  */
    class DependencyLabels private constructor(
        labels: OrderedSetMultimap<DependencyKind?, Label?>?,
        attributeMap: ConfiguredAttributeMapper?
    ) {
        private val labels: OrderedSetMultimap<DependencyKind?, Label?>?
        private val attributeMap: ConfiguredAttributeMapper?

        init {
            this.labels = labels
            this.attributeMap = attributeMap
        }

        fun labels(): OrderedSetMultimap<DependencyKind?, Label?>? {
            return labels
        }

        // Non-null for rules and output files when there are aspects that apply to files.
        fun attributeMap(): ConfiguredAttributeMapper? {
            return attributeMap
        }
    }

    /** The results of [.getExecutionPlatformLabel] as a tagged union.  */
    @AutoOneOf(com.google.devtools.build.lib.analysis.DependencyResolutionHelpers.ExecutionPlatformResult.Kind::class)
    abstract class ExecutionPlatformResult {
        /** Tags for the possible results.  */
        enum class Kind {
            /** A label was successfully determined.  */
            LABEL,

            /**
             * A label was successfully determined to be null.
             * 
             * 
             * [AutoOneOf] does not permit `@Nullable` so this is distinct from [ ][.LABEL].
             */
            NULL_LABEL,

            /**
             * The dependency should be skipped.
             * 
             * 
             * See comments in [.getExecutionPlatformLabel] for details.
             */
            SKIP,

            /** An error message.  */
            ERROR
        }

        abstract fun kind(): Kind?

        abstract fun label(): Label?

        abstract fun nullLabel()

        abstract fun skip()

        abstract fun error(): String?

        companion object {
            private fun ofLabel(label: Label?): ExecutionPlatformResult {
                return AutoOneOf_DependencyResolutionHelpers_ExecutionPlatformResult.label(label)
            }

            private fun ofNullLabel(): ExecutionPlatformResult {
                return AutoOneOf_DependencyResolutionHelpers_ExecutionPlatformResult.nullLabel()
            }

            private fun ofSkip(): ExecutionPlatformResult {
                return AutoOneOf_DependencyResolutionHelpers_ExecutionPlatformResult.skip()
            }

            private fun ofError(message: String?): ExecutionPlatformResult {
                return AutoOneOf_DependencyResolutionHelpers_ExecutionPlatformResult.error(message)
            }
        }
    }

    /** Indicates a failure during dependency resolution.  */
    class Failure private constructor(location: net.starlark.java.syntax.Location?, message: String?) :
        java.lang.Exception(message) {
        private val location: net.starlark.java.syntax.Location?

        init {
            this.location = location
        }

        /** Returns the location of the error, if known.  */
        fun getLocation(): net.starlark.java.syntax.Location? {
            return location
        }
    }
}
