// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.constraints

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** Implementation of [ConstraintSemantics] using [RuleContext] to check constraints.  */
class RuleContextConstraintSemantics : ConstraintSemantics<RuleContext?> {
    /**
     * Logs an error message that the current rule violates constraints.
     */
    fun ruleError(ruleContext: RuleContext, message: String?) {
        ruleContext.ruleError(message)
    }

    /**
     * Logs an error message that an attribute on the current rule doesn't properly declare
     * constraints.
     */
    fun attributeError(ruleContext: RuleContext, attribute: String?, message: String?) {
        ruleContext.attributeError(attribute, message)
    }

    /**
     * Provides a set of default environments for a given environment group.
     */
    private interface DefaultsProvider {
        fun getDefaults(group: EnvironmentLabels?): MutableCollection<Label?>?
    }

    /**
     * Provides a group's defaults as specified in the environment group's BUILD declaration.
     */
    private class GroupDefaultsProvider : DefaultsProvider {
        override fun getDefaults(group: EnvironmentLabels): MutableCollection<Label?> {
            return group.getDefaults()
        }
    }

    /**
     * Provides a group's defaults, factoring in rule class defaults as specified by
     * [com.google.devtools.build.lib.packages.RuleClass.Builder.compatibleWith]
     * and [com.google.devtools.build.lib.packages.RuleClass.Builder.restrictedTo].
     */
    private class RuleClassDefaultsProvider(ruleClassDefaults: EnvironmentCollection) : DefaultsProvider {
        private val ruleClassDefaults: EnvironmentCollection
        private val groupDefaults: GroupDefaultsProvider

        init {
            this.ruleClassDefaults = ruleClassDefaults
            this.groupDefaults = GroupDefaultsProvider()
        }

        override fun getDefaults(group: EnvironmentLabels): MutableCollection<Label?> {
            if (ruleClassDefaults.getGroups().contains(group)) {
                return ruleClassDefaults.getEnvironments(group)
            } else {
                // If there are no rule class defaults for this group, just inherit global defaults.
                return groupDefaults.getDefaults(group)
            }
        }
    }

    /**
     * Collects the set of supported environments for a given rule by merging its
     * restriction-style and compatibility-style environment declarations as specified by
     * the given attributes. Only includes environments from "known" groups, i.e. the groups
     * owning the environments explicitly referenced from these attributes.
     */
    private inner class EnvironmentCollector(
        ruleContext: RuleContext, restrictionAttr: String?, compatibilityAttr: String?,
        defaultsProvider: DefaultsProvider
    ) {
        private val ruleContext: RuleContext
        private val restrictionAttr: String?
        private val compatibilityAttr: String?
        private val defaultsProvider: DefaultsProvider

        private val restrictionEnvironments: EnvironmentCollection
        private val compatibilityEnvironments: EnvironmentCollection
        private val supportedEnvironments: EnvironmentCollection

        /**
         * Constructs a new collector on the given attributes.
         * 
         * @param ruleContext analysis context for the rule
         * @param restrictionAttr the name of the attribute that declares "restricted to"-style
         * environments. If the rule doesn't have this attribute, this is considered an
         * empty declaration.
         * @param compatibilityAttr the name of the attribute that declares "compatible with"-style
         * environments. If the rule doesn't have this attribute, this is considered an
         * empty declaration.
         * @param defaultsProvider provider for the default environments within a group if not
         * otherwise overridden by the above attributes
         */
        init {
            this.ruleContext = ruleContext
            this.restrictionAttr = restrictionAttr
            this.compatibilityAttr = compatibilityAttr
            this.defaultsProvider = defaultsProvider

            val environmentsBuilder: EnvironmentCollection.Builder = Builder()
            restrictionEnvironments = collectRestrictionEnvironments(environmentsBuilder)
            compatibilityEnvironments = collectCompatibilityEnvironments(environmentsBuilder)
            supportedEnvironments = environmentsBuilder.build()
        }

        /**
         * Returns the set of environments supported by this rule, as determined by the
         * restriction-style attribute, compatibility-style attribute, and group defaults
         * provider instantiated with this class.
         */
        fun getEnvironments(): EnvironmentCollection {
            return supportedEnvironments
        }

        /**
         * Validity-checks that no group has its environment referenced in both the "compatible with"
         * and restricted to" attributes. Returns true if all is good, returns false and reports
         * appropriate errors if there are any problems.
         */
        fun validateEnvironmentSpecifications(): Boolean {
            val restrictionGroups: com.google.common.collect.ImmutableCollection<EnvironmentLabels?> =
                restrictionEnvironments.getGroups()
            var hasErrors = false

            for (group in compatibilityEnvironments.getGroups()) {
                if (restrictionGroups.contains(group)) {
                    // To avoid error-spamming the user, when we find a conflict we only report one example
                    // environment from each attribute for that group.
                    val compatibilityEnv: Label =
                        compatibilityEnvironments.getEnvironments(group).iterator().next()
                    val restrictionEnv: Label? = restrictionEnvironments.getEnvironments(group).iterator().next()

                    if (compatibilityEnv.equals(restrictionEnv)) {
                        attributeError(
                            ruleContext, compatibilityAttr, (compatibilityEnv
                                .toString() + " cannot appear both here and in " + restrictionAttr)
                        )
                    } else {
                        attributeError(
                            ruleContext, compatibilityAttr, (compatibilityEnv.toString() + " and "
                                    + restrictionEnv + " belong to the same environment group. They should be declared "
                                    + "together either here or in " + restrictionAttr)
                        )
                    }
                    hasErrors = true
                }
            }

            return !hasErrors
        }

        /**
         * Adds environments specified in the "restricted to" attribute to the set of supported
         * environments and returns the environments added.
         */
        fun collectRestrictionEnvironments(
            supportedEnvironments: EnvironmentCollection.Builder
        ): EnvironmentCollection {
            return collectEnvironments(restrictionAttr, supportedEnvironments)
        }

        /**
         * Adds environments specified in the "compatible with" attribute to the set of supported
         * environments, along with all defaults from the groups they belong to. Returns these
         * environments, not including the defaults.
         */
        fun collectCompatibilityEnvironments(
            supportedEnvironments: EnvironmentCollection.Builder
        ): EnvironmentCollection {
            val compatibilityEnvironments: EnvironmentCollection =
                collectEnvironments(compatibilityAttr, supportedEnvironments)
            for (group in compatibilityEnvironments.getGroups()) {
                supportedEnvironments.putAll(group, defaultsProvider.getDefaults(group))
            }
            return compatibilityEnvironments
        }

        /**
         * Adds environments specified by the given attribute to the set of supported environments
         * and returns the environments added.
         * 
         * 
         * If this rule doesn't have the given attributes, returns an empty set.
         */
        fun collectEnvironments(
            attrName: String?,
            supportedEnvironments: EnvironmentCollection.Builder
        ): EnvironmentCollection {
            if (!ruleContext.getRule().isAttrDefined(attrName, BuildType.LABEL_LIST)) {
                return EnvironmentCollection.EMPTY
            }
            val environments: EnvironmentCollection.Builder = Builder()
            for (envTarget in ruleContext.getPrerequisites(attrName)) {
                val envInfo: EnvironmentWithGroup = resolveEnvironment(envTarget)
                environments.put(envInfo.group(), envInfo.environment())
                supportedEnvironments.put(envInfo.group(), envInfo.environment())
            }
            return environments.build()
        }

        /**
         * Returns the environment and its group. An [Environment] rule only "supports" one
         * environment: itself. Extract that from its more generic provider interface and check that
         * it's in fact what we see.
         */
        fun resolveEnvironment(envRule: TransitiveInfoCollection): EnvironmentWithGroup {
            val prereq: SupportedEnvironmentsProvider =
                com.google.common.base.Preconditions.checkNotNull<T>(envRule.getProvider(SupportedEnvironmentsProvider::class.java))
            return com.google.common.collect.Iterables.getOnlyElement<T>(
                prereq.getStaticEnvironments().getGroupedEnvironments()
            )
        }
    }

    /**
     * Returns the set of environments this rule supports, applying the logic described in [ ].
     * 
     * 
     * Note this set is **not complete** - it doesn't include environments from groups we don't
     * "know about". Environments and groups can be declared in any package. If the rule includes no
     * references to that package, then it simply doesn't know anything about them. But the constraint
     * semantics say the rule should support the defaults for that group. We encode this implicitly:
     * given the returned set, for any group that's not in the set the rule is also considered to
     * support that group's defaults.
     * 
     * @param ruleContext analysis context for the rule. A rule error is triggered here if invalid
     * constraint settings are discovered.
     * @return the environments this rule supports, not counting defaults "unknown" to this rule as
     * described above. Returns null if any errors are encountered.
     */
    public override fun getSupportedEnvironments(ruleContext: RuleContext): EnvironmentCollection? {
        if (!validateAttributes(ruleContext)) {
            return null
        }

        // This rule's rule class defaults (or null if the rule class has no defaults).
        val ruleClassCollector = maybeGetRuleClassDefaults(ruleContext)
        // Default environments for this rule. If the rule has rule class defaults, this is
        // those defaults. Otherwise it's the global defaults specified by environment_group
        // declarations.
        val ruleDefaults: DefaultsProvider?

        if (ruleClassCollector != null) {
            if (!ruleClassCollector.validateEnvironmentSpecifications()) {
                return null
            }
            ruleDefaults = RuleClassDefaultsProvider(ruleClassCollector.getEnvironments())
        } else {
            ruleDefaults = GroupDefaultsProvider()
        }

        val ruleCollector = EnvironmentCollector(
            ruleContext,
            RuleClass.RESTRICTED_ENVIRONMENT_ATTR, RuleClass.COMPATIBLE_ENVIRONMENT_ATTR, ruleDefaults
        )
        if (!ruleCollector.validateEnvironmentSpecifications()) {
            return null
        }

        var supportedEnvironments: EnvironmentCollection = ruleCollector.getEnvironments()
        if (ruleClassCollector != null) {
            // If we have rule class defaults from groups that aren't referenced from the rule itself,
            // we need to add them in too to override the global defaults.
            supportedEnvironments =
                addUnknownGroupsToCollection(supportedEnvironments, ruleClassCollector.getEnvironments())
        }
        return supportedEnvironments
    }

    /**
     * Returns the rule class defaults specified for this rule, or null if there are
     * no such defaults.
     */
    private fun maybeGetRuleClassDefaults(ruleContext: RuleContext): EnvironmentCollector? {
        val rule: Rule = ruleContext.getRule()
        val restrictionAttr: String? = RuleClass.DEFAULT_RESTRICTED_ENVIRONMENT_ATTR
        val compatibilityAttr: String? = RuleClass.DEFAULT_COMPATIBLE_ENVIRONMENT_ATTR

        if (rule.isAttrDefined(restrictionAttr, BuildType.LABEL_LIST)
            || rule.isAttrDefined(compatibilityAttr, BuildType.LABEL_LIST)
        ) {
            return EnvironmentCollector(
                ruleContext, restrictionAttr, compatibilityAttr,
                GroupDefaultsProvider()
            )
        } else {
            return null
        }
    }

    /**
     * Validity-checks this rule's constraint-related attributes. Returns true if all is good,
     * returns false and reports appropriate errors if there are any problems.
     */
    private fun validateAttributes(ruleContext: RuleContext): Boolean {
        val attributes: AttributeMap = ruleContext.attributes()

        // Report an error if "restricted to" is explicitly set to nothing. Even if this made
        // conceptual sense, we don't know which groups we should apply that to.
        val restrictionAttr: String? = RuleClass.RESTRICTED_ENVIRONMENT_ATTR
        val restrictionEnvironments: MutableList<out TransitiveInfoCollection> =
            ruleContext.getPrerequisites(restrictionAttr)
        if (restrictionEnvironments.isEmpty()
            && attributes.isAttributeValueExplicitlySpecified(restrictionAttr)
        ) {
            attributeError(ruleContext, restrictionAttr, "attribute cannot be empty")
            return false
        }

        return true
    }

    /**
     * Helper container for checkConstraints: stores both a set of deps that need to be
     * constraint-checked and the subset of those deps that only appear inside selects.
     */
    private class DepsToCheck(
        depsToCheck: MutableSet<TransitiveInfoCollection>?,
        selectOnlyDeps: MutableSet<TransitiveInfoCollection?>
    ) {
        private val allDeps: MutableSet<TransitiveInfoCollection>?
        private val selectOnlyDeps: MutableSet<TransitiveInfoCollection?>

        init {
            this.allDeps = depsToCheck
            this.selectOnlyDeps = selectOnlyDeps
        }

        fun allDeps(): MutableSet<TransitiveInfoCollection>? {
            return allDeps
        }

        fun isSelectOnly(dep: TransitiveInfoCollection?): Boolean {
            return selectOnlyDeps.contains(dep)
        }
    }

    /**
     * Performs constraint checking on the given rule's dependencies and reports any errors. This
     * includes:
     * 
     * 
     *  * Static environment checking: if this rule supports environment E, all deps outside
     * selects must also support E
     *  * Refined environment computation: this rule's refined environments are its static
     * environments intersected with the refined environments of all dependencies (including
     * chosen deps in selects)
     *  * Refined environment checking: no environment groups can be "emptied" due to refinement
     * 
     * 
     * @param ruleContext the rule to analyze
     * @param staticEnvironments the rule's supported environments, as defined by the return value of
     * [.getSupportedEnvironments]. In particular, for any environment group that's not in
     * this collection, the rule is assumed to support the defaults for that group.
     * @param refinedEnvironments a builder for populating this rule's refined environments
     * @param removedEnvironmentCulprits a builder for populating the core dependencies that trigger
     * pruning away environments through refinement. If multiple dependencies qualify (e.g. two
     * direct deps under the current rule), one is arbitrarily chosen.
     */
    public override fun checkConstraints(
        ruleContext: RuleContext,
        staticEnvironments: EnvironmentCollection,
        refinedEnvironments: EnvironmentCollection.Builder,
        removedEnvironmentCulprits: MutableMap<Label?, RemovedEnvironmentCulprit?>
    ) {
        // Start with the full set of static environments:
        val refinedEnvironmentsSoFar: MutableSet<EnvironmentWithGroup> =
            LinkedHashSet<Any?>(staticEnvironments.getGroupedEnvironments())
        val groupsWithEnvironmentsRemoved: MutableSet<EnvironmentLabels?> = LinkedHashSet<EnvironmentLabels?>()
        // Maps the label results of getUnsupportedEnvironments() to EnvironmentWithGroups. We can't
        // have that method just return EnvironmentWithGroups because it also collects group defaults,
        // which we only have labels for.
        val labelsToEnvironments: MutableMap<Label?, EnvironmentWithGroup?> = HashMap<Label?, EnvironmentWithGroup?>()
        for (envWithGroup in staticEnvironments.getGroupedEnvironments()) {
            labelsToEnvironments.put(envWithGroup.environment(), envWithGroup)
        }

        val depsToCheck = getConstraintCheckedDependencies(ruleContext)

        for (dep in depsToCheck.allDeps()!!) {
            if (!depsToCheck.isSelectOnly(dep)) {
                // TODO(bazel-team): support static constraint checking for selects. A selectable constraint
                // is valid if the union of all deps in the select includes all of this rule's static
                // environments. Determining that requires following the select paths that don't get chosen,
                // which means we won't have ConfiguredTargets for those deps and need to find another
                // way to get their environments.
                checkStaticConstraints(ruleContext, staticEnvironments, dep)
            }
            refineEnvironmentsForDep(
                ruleContext, staticEnvironments, dep, labelsToEnvironments,
                refinedEnvironmentsSoFar, groupsWithEnvironmentsRemoved, removedEnvironmentCulprits
            )
        }

        checkRefinedConstraints(
            ruleContext, groupsWithEnvironmentsRemoved,
            refinedEnvironmentsSoFar, refinedEnvironments, removedEnvironmentCulprits
        )
    }

    /**
     * Performs static constraint checking against the given dep.
     * 
     * @param ruleContext the rule being analyzed
     * @param staticEnvironments the static environments of the rule being analyzed
     * @param dep the dep to check
     */
    private fun checkStaticConstraints(
        ruleContext: RuleContext,
        staticEnvironments: EnvironmentCollection, dep: TransitiveInfoCollection
    ) {
        val depEnvironments: SupportedEnvironmentsProvider =
            dep.getProvider(SupportedEnvironmentsProvider::class.java)
        val unsupportedEnvironments: MutableCollection<Label?> =
            getUnsupportedEnvironments(depEnvironments.getStaticEnvironments(), staticEnvironments)
        if (!unsupportedEnvironments.isEmpty()) {
            ruleError(
                ruleContext,
                ("dependency " + dep.getLabel() + " doesn't support expected environment"
                        + (if (unsupportedEnvironments.size() == 1) "" else "s")
                        + ": " + com.google.common.base.Joiner.on(", ").join(unsupportedEnvironments))
            )
        }
    }

    /**
     * Helper method for [.checkConstraints]: performs refined environment constraint checking.
     * 
     * 
     * Refined environment expectations: no environment group should be emptied out due to
     * refining. This reflects the idea that some of the static declared environments get pruned out
     * by the build configuration, but *all* environments shouldn't be pruned out.
     * 
     * 
     * Violations of this expectation trigger rule analysis errors.
     */
    private fun checkRefinedConstraints(
        ruleContext: RuleContext,
        groupsWithEnvironmentsRemoved: MutableSet<EnvironmentLabels?>,
        refinedEnvironmentsSoFar: MutableSet<EnvironmentWithGroup>,
        refinedEnvironments: EnvironmentCollection.Builder,
        removedEnvironmentCulprits: MutableMap<Label?, RemovedEnvironmentCulprit?>
    ) {
        val refinedGroups: MutableSet<EnvironmentLabels?> = LinkedHashSet<EnvironmentLabels?>()
        for (envWithGroup in refinedEnvironmentsSoFar) {
            refinedEnvironments.put(envWithGroup.group(), envWithGroup.environment())
            refinedGroups.add(envWithGroup.group())
        }
        val newlyEmptyGroups: MutableSet<EnvironmentLabels> =
            if (groupsWithEnvironmentsRemoved.isEmpty())
                com.google.common.collect.ImmutableSet.of<EnvironmentLabels?>()
            else
                com.google.common.collect.Sets.difference<EnvironmentLabels?>(
                    groupsWithEnvironmentsRemoved,
                    refinedGroups
                )
        if (!newlyEmptyGroups.isEmpty()) {
            ruleError(
                ruleContext,
                getOverRefinementError(
                    ruleContext.getLabel(), newlyEmptyGroups, removedEnvironmentCulprits
                )
            )
        }
    }

    /**
     * Constructs an error message for when all environments have been pruned out of one or more
     * environment groups due to refining.
     */
    private fun getOverRefinementError(
        currentTarget: Label,
        newlyEmptyGroups: MutableSet<EnvironmentLabels>,
        removedEnvironmentCulprits: MutableMap<Label?, RemovedEnvironmentCulprit?>
    ): String? {
        val message: StringJoiner = StringJoiner("\n")
            .add(
                "the current command line flags disqualify all supported environments because of "
                        + "incompatible select() paths:"
            )
        for (group in newlyEmptyGroups) {
            if (newlyEmptyGroups.size() > 1) {
                message
                    .add(" ")
                    .add("environment group: " + group.getLabel() + ":")
            }
            for (prunedEnvironment in group.getEnvironments()) {
                val culprit: RemovedEnvironmentCulprit? = removedEnvironmentCulprits.get(prunedEnvironment)
                // Only environments this rule statically declared support for have culprits.
                if (culprit != null) {
                    message
                        .add(" ")
                        .add(getMissingEnvironmentCulpritMessage(currentTarget, prunedEnvironment, culprit))
                }
            }
        }
        return message.toString()
    }

    fun getMissingEnvironmentCulpritMessage(
        currentTarget: Label, environment: Label?, reason: RemovedEnvironmentCulprit
    ): String? {
        val culprit: LabelAndLocation = reason.culprit()
        val targetToExplore: Label? =
            if (currentTarget.equals(culprit.label())) reason.selectedDepForCulprit() else culprit.label()

        return StringJoiner("\n")
            .add("  environment: " + environment)
            .add("    removed by: " + culprit.label() + " (" + culprit.location() + ")")
            .add("    because of a select() that chooses dep: " + reason.selectedDepForCulprit())
            .add("    which lacks: " + environment)
            .add("")
            .add(
                java.lang.String.format(
                    "To see why, run: blaze build --target_environment=%s %s",
                    environment, targetToExplore
                )
            )
            .toString()
    }

    /**
     * Provides information about a target's incompatibility.
     * 
     * 
     * After calling `checkForIncompatibility()`, the `isIncompatible` getter tells you
     * whether the target is incompatible. If the target is incompatible, then `underlyingTarget` tells you which underlying target provided the incompatibility. For the vast
     * majority of targets this is the same one passed to `checkForIncompatibility()`. In some
     * instances like [OutputFileConfiguredTarget], however, the `underlyingTarget` is the
     * rule that generated the file.
     */
    class IncompatibleCheckResult(isIncompatible: Boolean, underlyingTarget: ConfiguredTarget?) {
        val isIncompatible: Boolean
        val underlyingTarget: ConfiguredTarget?

        init {
            this.underlyingTarget = underlyingTarget
            this.isIncompatible = isIncompatible
            java.util.Objects.requireNonNull<Any?>(underlyingTarget, "underlyingTarget")
        }

        companion object {
            private fun create(
                isIncompatible: Boolean, underlyingTarget: ConfiguredTarget?
            ): IncompatibleCheckResult {
                return IncompatibleCheckResult(isIncompatible, underlyingTarget)
            }
        }
    }

    companion object {
        /**
         * Adds environments to an [EnvironmentCollection] from groups that aren't already
         * a part of that collection.
         * 
         * @param environments the collection to add to
         * @param toAdd the collection to add. All environments in this collection in groups
         * that aren't represented in `environments` are added to `environments`.
         * @return the expanded collection.
         */
        private fun addUnknownGroupsToCollection(
            environments: EnvironmentCollection, toAdd: EnvironmentCollection
        ): EnvironmentCollection {
            val builder: EnvironmentCollection.Builder = Builder()
            builder.putAll(environments)
            for (candidateGroup in toAdd.getGroups()) {
                if (!environments.getGroups().contains(candidateGroup)) {
                    builder.putAll(candidateGroup, toAdd.getEnvironments(candidateGroup))
                }
            }
            return builder.build()
        }

        /**
         * Helper method for [.checkConstraints]: refines a rule's environments with the given dep.
         * 
         * 
         * A rule's **complete** refined set applies this process to every dep.
         */
        private fun refineEnvironmentsForDep(
            ruleContext: RuleContext,
            staticEnvironments: EnvironmentCollection,
            dep: TransitiveInfoCollection,
            labelsToEnvironments: MutableMap<Label?, EnvironmentWithGroup?>,
            refinedEnvironmentsSoFar: MutableSet<EnvironmentWithGroup>,
            groupsWithEnvironmentsRemoved: MutableSet<EnvironmentLabels?>,
            removedEnvironmentCulprits: MutableMap<Label?, RemovedEnvironmentCulprit?>
        ) {
            val depEnvironments: SupportedEnvironmentsProvider =
                dep.getProvider(SupportedEnvironmentsProvider::class.java)

            // Stores the environments that are pruned from the refined set because of this dep. Even
            // though they're removed, some subset of the environments they fulfill may belong in the
            // refined set. For example, if environment "both" fulfills "a" and "b" and "lib" statically
            // sets restricted_to = ["both"] and "dep" sets restricted_to = ["a"], then lib's refined set
            // excludes "both". But rather than be emptied out it can be reduced to "a".
            val prunedEnvironmentsFromThisDep: MutableSet<Label?> = LinkedHashSet<Label?>()

            // Refine this rule's environments by intersecting with the dep's refined environments:
            for (refinedEnvironmentToPrune in getUnsupportedEnvironments(
                depEnvironments.getRefinedEnvironments(), staticEnvironments
            )) {
                var envToPrune: EnvironmentWithGroup? = labelsToEnvironments.get(refinedEnvironmentToPrune)
                if (envToPrune == null) {
                    // If we have no record of this environment, that means the current rule implicitly uses
                    // the defaults for this group. So explicitly opt that group's defaults into the refined
                    // set before trying to remove specific items.
                    for (defaultEnv in getDefaults(
                        refinedEnvironmentToPrune,
                        depEnvironments.getRefinedEnvironments()
                    )) {
                        refinedEnvironmentsSoFar.add(defaultEnv)
                        labelsToEnvironments.put(defaultEnv.environment(), defaultEnv)
                    }
                    envToPrune = com.google.common.base.Verify.verifyNotNull<EnvironmentWithGroup?>(
                        labelsToEnvironments.get(refinedEnvironmentToPrune)
                    )
                }
                refinedEnvironmentsSoFar.remove(envToPrune)
                groupsWithEnvironmentsRemoved.add(envToPrune.group())
                removedEnvironmentCulprits.put(
                    envToPrune.environment(),
                    findOriginalRefiner(ruleContext, dep.getLabel(), depEnvironments, envToPrune)
                )
                prunedEnvironmentsFromThisDep.add(envToPrune.environment())
            }

            // Add in any dep environment that one of the environments we removed fulfills. In other
            // words, the removed environment is no good, but some subset of it may be.
            for (depEnv in depEnvironments.getRefinedEnvironments().getGroupedEnvironments()) {
                for (fulfiller in depEnv.group().getFulfillers(depEnv.environment())) {
                    if (prunedEnvironmentsFromThisDep.contains(fulfiller)) {
                        refinedEnvironmentsSoFar.add(depEnv)
                    }
                }
            }
        }

        /**
         * Given an environment that should be refined out of the current rule because of the given dep,
         * returns the original dep that caused the removal.
         * 
         * 
         * For example, say we have R -> D1 -> D2 and all rules support environment E. If the
         * refinement happens because D2 has
         * <pre>
         * deps = select({":foo": ["restricted_to_E"], ":bar": ["restricted_to_F"]}}  # Choose F.
        </pre> * 
         * 
         * 
         * then D2 is the original refiner (even though D1 and R inherit the same pruning).
         */
        private fun findOriginalRefiner(
            ruleContext: RuleContext, dep: Label?,
            depEnvironments: SupportedEnvironmentsProvider, envToPrune: EnvironmentWithGroup
        ): RemovedEnvironmentCulprit? {
            val depCulprit: RemovedEnvironmentCulprit? =
                depEnvironments.getRemovedEnvironmentCulprit(envToPrune.environment())
            if (depCulprit != null) {
                return depCulprit
            }
            // If the dep has no record of this environment being refined, that means the current rule
            // is the culprit.
            return RemovedEnvironmentCulprit.create(
                LabelAndLocation.of(ruleContext.getTarget()),  // While it'd be nice to know the dep's location too, it isn't strictly necessary.
                // Especially since we already have the parent's location. So it's easy enough to find the
                // dep. And we want to respect the efficiency concerns described in LabelAndLocation.
                //
                // Alternatively, we could prepare error strings directly in SupportedEnvironmentsProvider,
                // which should remove the need for LabelAndLocation for any target.
                dep
            )
        }

        /**
         * Finds the given environment in the given set and returns the default environments for its
         * group.
         */
        private fun getDefaults(
            env: Label?,
            allEnvironments: EnvironmentCollection
        ): MutableCollection<EnvironmentWithGroup> {
            var group: EnvironmentLabels? = null
            for (candidateGroup in allEnvironments.getGroups()) {
                if (candidateGroup.getDefaults().contains(env)) {
                    group = candidateGroup
                    break
                }
            }
            com.google.common.base.Verify.verifyNotNull<Any?>(group)
            val builder: com.google.common.collect.ImmutableSet.Builder<EnvironmentWithGroup?> =
                com.google.common.collect.ImmutableSet.builder<EnvironmentWithGroup?>()
            for (defaultEnv in group.getDefaults()) {
                builder.add(EnvironmentWithGroup.create(defaultEnv, group))
            }
            return builder.build()
        }

        /**
         * Given a collection of environments and a collection of expected environments, returns the
         * missing environments that would cause constraint expectations to be violated. Includes the
         * effects of environment group defaults.
         */
        fun getUnsupportedEnvironments(
            actualEnvironments: EnvironmentCollection, expectedEnvironments: EnvironmentCollection
        ): MutableCollection<Label?> {
            val missingEnvironments: MutableSet<Label?> = LinkedHashSet<Label?>()
            val actualEnvironmentLabels: MutableCollection<Label?> = actualEnvironments.getEnvironments()

            // Check if each explicitly expected environment is satisfied.
            for (expectedEnv in expectedEnvironments.getGroupedEnvironments()) {
                val group: EnvironmentLabels = expectedEnv.group()
                val environment: Label? = expectedEnv.environment()
                var isSatisfied = false
                if (actualEnvironments.getGroups().contains(group)) {
                    // If the actual environments include members from the expected environment's group, we
                    // need to either find the environment itself or another one that transitively fulfills it.
                    if (actualEnvironmentLabels.contains(environment)
                        || intersect(actualEnvironmentLabels, group.getFulfillers(environment))
                    ) {
                        isSatisfied = true
                    }
                } else {
                    // If the actual environments don't reference the expected environment's group at all,
                    // the group's defaults are implicitly included. So we need to check those defaults for
                    // either the expected environment or another environment that transitively fulfills it.
                    if (group.isDefault(environment)
                        || intersect(group.getFulfillers(environment), group.getDefaults())
                    ) {
                        isSatisfied = true
                    }
                }
                if (!isSatisfied) {
                    missingEnvironments.add(environment)
                }
            }

            // For any environment group not referenced by the expected environments, its defaults are
            // implicitly expected. We can ignore this if the actual environments also don't reference the
            // group (since in that case the same defaults apply), otherwise we have to check.
            for (group in actualEnvironments.getGroups()) {
                if (!expectedEnvironments.getGroups().contains(group)) {
                    for (expectedDefault in group.getDefaults()) {
                        if (!actualEnvironmentLabels.contains(expectedDefault)
                            && !intersect(actualEnvironmentLabels, group.getFulfillers(expectedDefault))
                        ) {
                            missingEnvironments.add(expectedDefault)
                        }
                    }
                }
            }

            return missingEnvironments
        }

        private fun intersect(labels1: Iterable<Label?>, labels2: Iterable<Label?>): Boolean {
            return !com.google.common.collect.Sets.intersection<Label?>(
                com.google.common.collect.Sets.newHashSet<Label?>(
                    labels1
                ), com.google.common.collect.Sets.newHashSet<Label?>(labels2)
            ).isEmpty()
        }

        /**
         * Returns all dependencies that should be constraint-checked against the current rule, including
         * both "unconditional" deps (outside selects) and deps that only appear in selects.
         */
        private fun getConstraintCheckedDependencies(ruleContext: RuleContext): DepsToCheck {
            val depsToCheck: MutableSet<TransitiveInfoCollection> = LinkedHashSet<TransitiveInfoCollection>()
            val selectOnlyDeps: MutableSet<TransitiveInfoCollection?> = LinkedHashSet<TransitiveInfoCollection?>()
            val depsOutsideSelects: MutableSet<TransitiveInfoCollection?> = LinkedHashSet<TransitiveInfoCollection?>()

            val attributes: AttributeMap = ruleContext.attributes()
            for (attr in attributes.getAttributeNames()) {
                val attrDef: Attribute = attributes.getAttributeDefinition(attr)
                if (attrDef.getType().getLabelClass() !== LabelClass.DEPENDENCY
                    || attrDef.skipConstraintsOverride()
                ) {
                    continue
                }
                if (!attrDef.checkConstraintsOverride()) {
                    // Use the same implicit deps check that query uses. This facilitates running queries to
                    // determine exactly which rules need to be constraint-annotated for depot migrations.
                    if (!DependencyFilter.NO_IMPLICIT_DEPS.test(ruleContext.getRule(), attrDef)
                        || attrDef.isToolDependency()
                    ) {
                        continue
                    }
                }

                val selectOnlyDepsForThisAttribute: MutableSet<Label?> =
                    Companion.getDepsOnlyInSelects<T?>(ruleContext, attr, attributes.getAttributeType(attr))
                for (dep in ruleContext.getPrerequisites(attr)) {
                    // For normal configured targets the target's label is the same label appearing in the
                    // select(). But for AliasConfiguredTargets the label in the select() refers to the alias,
                    // while dep.getLabel() refers to the target the alias points to. So add this quick check
                    // to make sure we're comparing the same labels.
                    var dep: TransitiveInfoCollection = dep
                    val depLabelInSelect: Label? =
                        if (dep is ConfiguredTarget)
                            dep.getOriginalLabel()
                        else
                            dep.getLabel()
                    // Output files inherit the environment spec of their generating rule.
                    if (dep is OutputFileConfiguredTarget) {
                        // Note this reassignment means constraint violation errors reference the generating
                        // rule, not the file. This makes the source of the environmental mismatch more clear.
                        dep = (dep as OutputFileConfiguredTarget).getGeneratingRule()
                    }
                    // Input files don't support environments. We may subsequently opt them into constraint
                    // checking, but for now just pass them by.
                    if (dep.getProvider(SupportedEnvironmentsProvider::class.java) != null) {
                        depsToCheck.add(dep)
                        if (!selectOnlyDepsForThisAttribute.contains(depLabelInSelect)) {
                            depsOutsideSelects.add(dep)
                        }
                    }
                }
            }

            for (dep in depsToCheck) {
                if (!depsOutsideSelects.contains(dep)) {
                    selectOnlyDeps.add(dep)
                }
            }

            return DepsToCheck(depsToCheck, selectOnlyDeps)
        }

        /**
         * Returns the deps for this attribute that only appear in selects.
         * 
         * 
         * For example:
         * 
         * <pre>
         * deps = [":a"] + select({"//foo:cond": [":b"]}) + select({"//conditions:default": [":c"]})
        </pre> * 
         * 
         * returns `[":b"]`. Even though `[":c"]` also appears in a select, that's a
         * degenerate case with only one always-chosen condition. So that's considered the same as an
         * unconditional dep.
         * 
         * 
         * Note that just because a dep only appears in selects for this attribute doesn't mean it
         * won't appear unconditionally in another attribute.
         */
        private fun <T> getDepsOnlyInSelects(
            ruleContext: RuleContext, attr: String?, attrType: Type<T?>?
        ): MutableSet<Label?> {
            val rule: Rule = ruleContext.getRule()
            if (!rule.isConfigurableAttribute(attr) || !BuildType.isLabelType(attrType)) {
                return com.google.common.collect.ImmutableSet.of<Label?>()
            }
            val unconditionalDeps: MutableSet<Label?> = LinkedHashSet<Label?>()
            val selectableDeps: MutableSet<Label?> = LinkedHashSet<Label?>()
            val selectList: BuildType.SelectorList<T?> =
                RawAttributeMapper.of(rule).getSelectorList(attr, attrType)
            for (select in selectList.getSelectors()) {
                addSelectValuesToSet<Any?>(select, if (select.isUnconditional()) unconditionalDeps else selectableDeps)
            }
            return com.google.common.collect.Sets.difference<Label?>(selectableDeps, unconditionalDeps)
        }

        /**
         * Adds all label values from the given select to the given set. Automatically handles different
         * value types (e.g. labels vs. label lists).
         */
        private fun <T> addSelectValuesToSet(select: BuildType.Selector<T?>, set: MutableSet<Label?>) {
            val type: Type<T?> = select.getOriginalType()
            val visitor: LabelVisitor = LabelVisitor { label, dummy -> set.add(label) }
            select.forEach({ label, value -> type.visitLabels(visitor, value,  /* context= */null) })
        }

        /**
         * Checks whether the target is incompatible.
         * 
         * 
         * See the documentation for [RuleContextConstraintSemantics.IncompatibleCheckResult] for
         * more information.
         */
        fun checkForIncompatibility(target: ConfiguredTarget): IncompatibleCheckResult {
            var target: ConfiguredTarget = target
            if (target is OutputFileConfiguredTarget) {
                // For generated files, we want to query the generating rule for providers. genrule() for
                // example doesn't attach providers like IncompatiblePlatformProvider to its outputs.
                target = (target as OutputFileConfiguredTarget).getGeneratingRule()
            }
            return IncompatibleCheckResult.Companion.create(
                target.get(IncompatiblePlatformProvider.PROVIDER) != null, target
            )
        }
    }
}
