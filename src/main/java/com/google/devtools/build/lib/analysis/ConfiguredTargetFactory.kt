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

/**
 * This class creates [ConfiguredTarget] instances using a given [ ].
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
class ConfiguredTargetFactory(
    ruleClassProvider: ConfiguredRuleClassProvider,
    conflictFinder: com.google.common.base.Supplier<IncrementalArtifactConflictFinder?>?
) {
    // This class is not meant to be outside of the analysis phase machinery and is only public
    // in order to be accessible from the .view.skyframe package.
    private val ruleClassProvider: ConfiguredRuleClassProvider
    private val conflictFinder: com.google.common.base.Supplier<IncrementalArtifactConflictFinder?>?

    init {
        this.ruleClassProvider = ruleClassProvider
        this.conflictFinder = conflictFinder
    }

    private fun getTransitiveVisibilityForCurrentPackage(
        prerequisiteMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>,
        reporter: com.google.devtools.build.lib.events.EventHandler,
        target: Target
    ): PackageSpecificationProvider? {
        val tvLabel: Label? = target.getPackageDeclarations().getPackageArgs().transitiveVisibility()
        val prerequisite: TransitiveInfoCollection? =
            findTransitiveVisibilityPrerequisite(prerequisiteMap, tvLabel)
        if (prerequisite == null) {
            return null
        }
        val provider: PackageSpecificationProvider? =
            prerequisite.getProvider(PackageSpecificationProvider::class.java)
        if (provider == null) {
            reporter.handle(
                com.google.devtools.build.lib.events.Event.error(
                    target.getLocation(),
                    java.lang.String.format(
                        "Label '%s' in transitive_visibility does not refer to a package group",
                        tvLabel
                    )
                )
            )
        }
        return provider
    }

    /**
     * Invokes the appropriate constructor to create a [ConfiguredTarget] instance.
     * 
     * 
     * For use in `ConfiguredTargetFunction`.
     * 
     * 
     * Returns null if Skyframe deps are missing or upon certain errors.
     */
    @Throws(
        java.lang.InterruptedException::class,
        ActionConflictException::class,
        InvalidExecGroupException::class,
        AnalysisFailurePropagationException::class
    )
    fun createConfiguredTarget(
        analysisEnvironment: AnalysisEnvironment,
        artifactFactory: ArtifactFactory,
        target: Target,
        config: BuildConfigurationValue?,
        configuredTargetKey: ConfiguredTargetKey?,
        prerequisiteMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>,
        materializerTargets: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?,
        configConditions: ConfigConditions?,
        toolchainContexts: ToolchainCollection<ResolvedToolchainContext?>?,
        transitivePackages: NestedSet<Package.Metadata?>?,
        execGroupCollectionBuilder: ExecGroupCollection.Builder?,
        starlarkExecTransition: StarlarkAttributeTransitionProvider?
    ): ConfiguredTarget? {
        if (target is Rule) {
            try {
                CurrentRuleTracker.beginConfiguredTarget((target as Rule).getRuleClassObject())
                return createRule(
                    analysisEnvironment,
                    target as Rule,
                    config,
                    configuredTargetKey,
                    prerequisiteMap,
                    materializerTargets,
                    configConditions,
                    toolchainContexts,
                    transitivePackages,
                    execGroupCollectionBuilder,
                    starlarkExecTransition
                )
            } finally {
                CurrentRuleTracker.endConfiguredTarget()
            }
        }

        // Enforce that targets whose names are outside their declaring macro's namespace cannot be
        // analyzed. (createRule() already enforces this above for rule targets, with optional error
        // interception through analysis_test.)
        try {
            target.getPackageoid().checkMacroNamespaceCompliance(target)
        } catch (e: MacroNamespaceViolationException) {
            analysisEnvironment
                .getEventHandler()
                .handle(com.google.devtools.build.lib.events.Event.error(target.getLocation(), e.getMessage()))
            return null
        }

        // Visibility, like all package groups, doesn't have a configuration
        val visibility: NestedSet<PackageGroupContents?> =
            convertVisibility(prerequisiteMap, analysisEnvironment.getEventHandler(), target)
        // For InputFiles, we're not gating on --experimental_enforce_transitive_visibility because they
        // have no config, so we can't check whether --experimental_enforce_transitive_visibility is
        // set. Some unnecessary memory cost here, but no enforcement because we'll also check for the
        // flag where the provider is read.
        val transitiveVisibility: PackageSpecificationProvider? =
            if ((config != null && config.enforceTransitiveVisibility()) || target is InputFile)
                getTransitiveVisibilityForCurrentPackage(
                    prerequisiteMap, analysisEnvironment.getEventHandler(), target
                )
            else
                null
        if (target is OutputFile) {
            val targetContext: TargetContext =
                TargetContext(
                    analysisEnvironment,
                    target,
                    config,
                    prerequisiteMap.get(DependencyKind.OUTPUT_FILE_RULE_DEPENDENCY),
                    visibility,
                    transitiveVisibility // We are passing around this object because it looks nice,
                    // but it's
                    // never used. OutputFiles get the transitive visibility from their generating rule.
                )
            if (analysisEnvironment.getSkyframeEnv().valuesMissing()) {
                return null
            }
            val ruleLabel: Label? = target.getGeneratingRule().getLabel()
            val rule: RuleConfiguredTarget? =
                targetContext.findDirectPrerequisite(
                    ruleLabel,  // Don't pass a specific configuration, as we don't care what configuration the
                    // generating rule is in. There can only be one actual dependency here, which is
                    // the target that generated the output file.
                    java.util.Optional.empty<BuildConfigurationValue?>()
                ) as RuleConfiguredTarget?
            com.google.common.base.Verify.verifyNotNull<RuleConfiguredTarget?>(
                rule, "While analyzing %s, missing generating rule %s", target, ruleLabel
            )
            // If analysis failures are allowed and the generating rule has failure info, just propagate
            // it. The output artifact won't exist, so we can't create an OutputFileConfiguredTarget.
            if (config.allowAnalysisFailures()
                && rule.get(AnalysisFailureInfo.STARLARK_CONSTRUCTOR.getKey()) != null
            ) {
                return rule
            }
            val artifact: Artifact = rule.findArtifactByOutputLabel(target.getLabel())
            return OutputFileConfiguredTarget(targetContext, artifact, rule)
        } else if (target is InputFile) {
            val targetContext: TargetContext =
                TargetContext(
                    analysisEnvironment,
                    target,
                    config,
                    prerequisiteMap.get(DependencyKind.OUTPUT_FILE_RULE_DEPENDENCY),
                    visibility,
                    transitiveVisibility
                )
            val artifact: SourceArtifact? =
                artifactFactory.getSourceArtifact(
                    target.getExecPath(
                        analysisEnvironment
                            .getStarlarkSemantics()
                            .getBool(BuildLanguageOptions.EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT)
                    ),
                    target.getPackageMetadata().sourceRoot(),
                    ConfiguredTargetKey.builder()
                        .setLabel(target.getLabel())
                        .setConfiguration(config)
                        .build()
                )
            return InputFileConfiguredTarget(targetContext, artifact)
        } else if (target is PackageGroup) {
            val targetContext: TargetContext =
                TargetContext(
                    analysisEnvironment,
                    target,
                    config,
                    prerequisiteMap.get(DependencyKind.VISIBILITY_DEPENDENCY),
                    visibility,  /* transitiveVisibility= */
                    null
                )
            // No transitive visibility checking on package_groups, in part because transitive visibility
            // groups *are* package_groups, and
            // we want to avoid circular dependencies.
            return PackageGroupConfiguredTarget(configuredTargetKey, targetContext, target)
        } else if (target is EnvironmentGroup) {
            return EnvironmentGroupConfiguredTarget(configuredTargetKey)
        } else {
            throw java.lang.AssertionError("Unexpected target class: " + target.getClass().getName())
        }
    }

    /**
     * Factory method: constructs a RuleConfiguredTarget of the appropriate class, based on the rule
     * class. May return null if an error occurred.
     */
    @Throws(
        java.lang.InterruptedException::class,
        ActionConflictException::class,
        InvalidExecGroupException::class,
        AnalysisFailurePropagationException::class
    )
    private fun createRule(
        env: AnalysisEnvironment,
        rule: Rule,
        configuration: BuildConfigurationValue,
        configuredTargetKey: ConfiguredTargetKey?,
        prerequisiteMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>,
        materializerTargets: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?,
        configConditions: ConfigConditions?,
        toolchainContexts: ToolchainCollection<ResolvedToolchainContext?>?,
        transitivePackages: NestedSet<Package.Metadata?>?,
        execGroupCollectionBuilder: ExecGroupCollection.Builder?,
        starlarkExecTransition: StarlarkAttributeTransitionProvider?
    ): ConfiguredTarget? {
        val ruleClass: RuleClass = rule.getRuleClassObject()
        val configurationFragmentPolicy: ConfigurationFragmentPolicy =
            ruleClass.getConfigurationFragmentPolicy()

        // Visibility computation and checking is done for every rule.
        val ruleContext: RuleContext =
            com.google.devtools.build.lib.analysis.RuleContext.Builder(
                env,
                rule,  /* aspects= */
                com.google.common.collect.ImmutableList.of<Aspect?>(),
                configuration
            )
                .setRuleClassProvider(ruleClassProvider)
                .setConfigurationFragmentPolicy(configurationFragmentPolicy)
                .setActionOwnerSymbol(configuredTargetKey)
                .setMutability(Mutability.create("configured target"))
                .setVisibility(convertVisibility(prerequisiteMap, env.getEventHandler(), rule))
                .setPrerequisites(removeToolchainDeps(prerequisiteMap))
                .setMaterializerTargets(materializerTargets)
                .setConfigConditions(configConditions)
                .setToolchainContexts(toolchainContexts)
                .setTransitiveVisibilityImposedByThisPackage(
                    getTransitiveVisibilityForCurrentPackage(
                        prerequisiteMap, env.getEventHandler(), rule
                    )
                )
                .setExecGroupCollectionBuilder(execGroupCollectionBuilder)
                .setRequiredConfigFragments(
                    RequiredFragmentsUtil.getRuleRequiredFragmentsIfEnabled(
                        rule,
                        configuration,
                        ruleClassProvider.getFragmentRegistry().getUniversalFragments(),
                        configConditions,
                        com.google.common.collect.Iterables.transform<F?, T?>(
                            prerequisiteMap.values(), ConfiguredTargetAndData::getConfiguredTarget
                        ),
                        starlarkExecTransition
                    )
                )
                .setTransitivePackagesForRunfileRepoMappingManifest(transitivePackages)
                .setConflictFinder(conflictFinder)
                .setAllowMaterializerRuleRealDeps(ruleClass.materializerRuleAllowsRealDeps())
                .build()

        val analysisFailures: com.google.common.collect.ImmutableList<NestedSet<AnalysisFailure?>?> =
            depAnalysisFailures(ruleContext, com.google.common.collect.ImmutableList.of<TransitiveInfoCollection?>())
        if (!analysisFailures.isEmpty()) {
            return erroredConfiguredTargetWithFailures(ruleContext, analysisFailures)
        }
        if (ruleContext.hasErrors()) {
            return erroredConfiguredTarget(ruleContext, null)
        }

        try {
            rule.getPackageoid().checkMacroNamespaceCompliance(rule)
        } catch (e: MacroNamespaceViolationException) {
            ruleContext.ruleError(e.getMessage())
            return erroredConfiguredTarget(ruleContext, null)
        }

        try {
            var missingFragmentClass: java.lang.Class<*>? = null
            for (fragmentClass in configurationFragmentPolicy.getRequiredConfigurationFragments()) {
                if (!configuration.hasFragment(fragmentClass)) {
                    val missingFragmentPolicy: MissingFragmentPolicy? =
                        configurationFragmentPolicy.getMissingFragmentPolicy(fragmentClass)
                    if (missingFragmentPolicy !== MissingFragmentPolicy.IGNORE) {
                        if (missingFragmentPolicy === MissingFragmentPolicy.FAIL_ANALYSIS) {
                            ruleContext.ruleError(
                                missingFragmentError(
                                    ruleContext, configurationFragmentPolicy, configuration.checksum()
                                )
                            )
                            return null
                        }
                        // Otherwise missingFragmentPolicy == MissingFragmentPolicy.CREATE_FAIL_ACTIONS:
                        missingFragmentClass = fragmentClass
                    }
                }
            }
            if (missingFragmentClass != null) {
                return createFailConfiguredTargetForMissingFragmentClass(ruleContext, missingFragmentClass)
            }

            val target: ConfiguredTarget?

            if (ruleClass.isStarlark()) {
                val rawProviders: Any?
                val isDefaultExecutableCreated: Boolean
                val requiredConfigFragmentsProvider: RequiredConfigFragmentsProvider?
                try {
                    // must be called before any calls to ruleContext.getStarlarkRuleContext()
                    ruleContext.initStarlarkRuleContext()
                    // TODO(bazel-team): maybe merge with RuleConfiguredTargetBuilder?
                    rawProviders = StarlarkRuleConfiguredTargetUtil.evalRule(ruleContext, ruleClass)
                    // TODO(b/268525292): isDefaultExecutableCreated is set to True when
                    // ctx.outputs.executable
                    // is accessed in the implementation. This fragile mechanism should be revised and removed
                    isDefaultExecutableCreated =
                        ruleContext.getStarlarkRuleContext().isDefaultExecutableCreated()
                    requiredConfigFragmentsProvider = ruleContext.getRequiredConfigFragments()
                } finally {
                    ruleContext.close()
                }
                if (rawProviders == null) {
                    return erroredConfiguredTarget(ruleContext, requiredConfigFragmentsProvider)
                }
                // Because ruleContext was closed, rawProviders are now immutable
                // Postprocess providers to create the finished target.
                target =
                    StarlarkRuleConfiguredTargetUtil.createTarget(
                        ruleContext,
                        rawProviders,
                        ruleClass.getAdvertisedProviders(),
                        isDefaultExecutableCreated,
                        requiredConfigFragmentsProvider
                    ) // may be null
                return if (target != null)
                    target
                else
                    erroredConfiguredTarget(ruleContext, requiredConfigFragmentsProvider)
            } else {
                try {
                    target =
                        com.google.common.base.Preconditions.checkNotNull(
                            ruleClass.getConfiguredTargetFactory(RuleConfiguredTargetFactory::class.java),
                            "No configured target factory for %s",
                            ruleClass
                        )
                            .create(ruleContext)
                    if (target != null) {
                        // If a configured target is created, check that all advertised providers are returned.
                        validateRuleAdvertisedProviders(
                            ruleContext, target, ruleClass.getAdvertisedProviders()
                        )
                    }
                } finally {
                    // close() is required if the native rule created StarlarkRuleContext to perform any
                    // Starlark evaluation, i.e. using the @_builtins mechanism.
                    ruleContext.close()
                }
                // TODO(https://github.com/bazelbuild/bazel/issues/17915): genquery and similar native rules
                // may return null without setting a ruleContext error to signal a skyframe restart.
                return if (target != null) target else erroredConfiguredTarget(ruleContext, null)
            }
        } catch (ruleErrorException: RuleErrorException) {
            return erroredConfiguredTarget(ruleContext, null)
        }
    }

    /**
     * Constructs a [ConfiguredAspect]. Returns null if an error occurs; in that case, `aspectFactory` should call one of the error reporting methods of [RuleContext].
     */
    @Throws(
        java.lang.InterruptedException::class,
        ActionConflictException::class,
        InvalidExecGroupException::class,
        RuleErrorException::class
    )
    fun createAspect(
        env: AnalysisEnvironment,
        associatedTarget: Target,
        configuredTarget: ConfiguredTarget,
        aspectPath: com.google.common.collect.ImmutableList<Aspect?>?,
        aspectFactory: ConfiguredAspectFactory,
        aspect: Aspect,
        prerequisiteMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>,
        configConditions: ConfigConditions?,
        toolchainContexts: ToolchainCollection<ResolvedToolchainContext?>?,
        baseTargetToolchainContexts: ToolchainCollection<AspectBaseTargetResolvedToolchainContext?>?,
        execGroupCollectionBuilder: ExecGroupCollection.Builder?,
        aspectConfiguration: BuildConfigurationValue?,
        transitivePackages: NestedSet<Package.Metadata?>?,
        aspectKey: AspectKeyCreator.AspectKey,
        starlarkExecTransition: StarlarkAttributeTransitionProvider?
    ): ConfiguredAspect? {
        val ruleContext: RuleContext =
            com.google.devtools.build.lib.analysis.RuleContext.Builder(
                env,
                associatedTarget,
                aspectPath,
                aspectConfiguration
            )
                .setRuleClassProvider(ruleClassProvider)
                .setConfigurationFragmentPolicy(aspect.getDefinition().getConfigurationFragmentPolicy())
                .setActionOwnerSymbol(aspectKey)
                .setMutability(Mutability.create("aspect"))
                .setVisibility(
                    convertVisibility(prerequisiteMap, env.getEventHandler(), associatedTarget)
                )
                .setPrerequisites(removeToolchainDeps(prerequisiteMap))
                .setConfigConditions(configConditions)
                .setToolchainContexts(toolchainContexts)
                .setBaseTargetToolchainContexts(baseTargetToolchainContexts)
                .setExecGroupCollectionBuilder(execGroupCollectionBuilder)
                .setExecProperties(com.google.common.collect.ImmutableMap.of<String?, String?>())
                .setRequiredConfigFragments(
                    RequiredFragmentsUtil.getAspectRequiredFragmentsIfEnabled(
                        aspect,
                        aspectFactory,
                        associatedTarget.getAssociatedRule(),
                        aspectConfiguration,
                        ruleClassProvider.getFragmentRegistry().getUniversalFragments(),
                        configConditions,
                        com.google.common.collect.Iterables.concat<T?>(
                            com.google.common.collect.Iterables.transform<F?, T?>(
                                prerequisiteMap.values(), ConfiguredTargetAndData::getConfiguredTarget
                            ),
                            com.google.common.collect.ImmutableList.of<E?>(configuredTarget)
                        ),
                        starlarkExecTransition
                    )
                )
                .setTransitivePackagesForRunfileRepoMappingManifest(transitivePackages)
                .setConflictFinder(conflictFinder)
                .build()

        // If allowing analysis failures, targets should be created as normal as possible, and errors
        // will be propagated via a hook elsewhere as AnalysisFailureInfo.
        val allowAnalysisFailures: Boolean = ruleContext.getConfiguration().allowAnalysisFailures()

        val analysisFailures: com.google.common.collect.ImmutableList<NestedSet<AnalysisFailure?>?> =
            depAnalysisFailures(
                ruleContext,
                com.google.common.collect.ImmutableList.of<TransitiveInfoCollection?>(configuredTarget)
            )
        if (!analysisFailures.isEmpty()) {
            return erroredConfiguredAspectWithFailures(ruleContext, analysisFailures)
        }
        if (ruleContext.hasErrors() && !allowAnalysisFailures) {
            return erroredConfiguredAspect(ruleContext, null)
        }

        val configuredAspect: ConfiguredAspect? =
            aspectFactory.create(
                associatedTarget.getLabel(),
                configuredTarget,
                ruleContext,
                aspect.getParameters(),
                ruleClassProvider.getToolsRepository()
            )

        if (ruleContext.getConflictFinder() != null && configuredAspect != null) {
            for (action in configuredAspect.getActions()) {
                ruleContext.getConflictFinder().conflictCheckPerAction(action)
            }
        }
        if (configuredAspect == null) {
            return erroredConfiguredAspect(ruleContext, null)
        } else if (configuredAspect.get(AnalysisFailureInfo.STARLARK_CONSTRUCTOR) != null) {
            // this was created by #erroredConfiguredAspect, return early to skip validating advertised
            // providers
            return configuredAspect
        }

        validateAdvertisedProviders(
            configuredAspect,
            aspectKey,
            aspect.getDefinition().getAdvertisedProviders(),
            associatedTarget,
            env.getEventHandler()
        )
        return configuredAspect
    }

    companion object {
        /**
         * Returns the visibility of the given target, as represented by [ ][VisibilityProvider.getVisibility].
         * 
         * 
         * This is constructed by starting with the value obtained from either [ ][Target.getVisibility] or [Target.getActualVisibility], and recursively expanding package
         * groups. Errors during package group resolution are reported to the `AnalysisEnvironment`.
         */
        private fun convertVisibility(
            prerequisiteMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>,
            reporter: com.google.devtools.build.lib.events.EventHandler,
            target: Target
        ): NestedSet<PackageGroupContents?> {
            // Optimization: don't use actual visibility if not in a symbolic macro. See javadoc on
            // VisibilityProvider#getVisibility. Since the actual visibility only ever adds a ...:__pkg__
            // item, not a package group, it doesn't need to be handled here.
            val ruleVisibility: RuleVisibility =
                if (target.isCreatedInSymbolicMacro()) target.getActualVisibility() else target.getVisibility()
            if (ruleVisibility.equals(RuleVisibility.PUBLIC)) {
                return VisibilityProvider.PUBLIC_VISIBILITY
            }
            if (ruleVisibility.equals(RuleVisibility.PRIVATE)) {
                return VisibilityProvider.PRIVATE_VISIBILITY
            }
            com.google.common.base.Preconditions.checkState(
                ruleVisibility is PackageGroupsRuleVisibility,
                ruleVisibility
            )
            val packageGroupsVisibility: PackageGroupsRuleVisibility =
                ruleVisibility as PackageGroupsRuleVisibility

            val result: NestedSetBuilder<PackageGroupContents?> = NestedSetBuilder.stableOrder()
            for (groupLabel in packageGroupsVisibility.getPackageGroups()) {
                // PackageGroupsConfiguredTargets are always in the package-group configuration.
                val group: TransitiveInfoCollection? = findVisibilityPrerequisite(prerequisiteMap, groupLabel)
                var provider: PackageSpecificationProvider? = null
                // group == null can only happen if the package group list comes from a default_visibility
                // attribute, because in every other case, this missing link is caught during transitive
                // closure visitation or if the RuleConfiguredTargetGraph threw out a visibility edge because
                // if would have caused a cycle. The filtering should be done in a single place,
                // ConfiguredTargetGraph, but for now, this is the minimally invasive way of providing a sane
                // error message in case a cycle is created by a visibility attribute.
                if (group != null) {
                    provider = group.get(PackageSpecificationProvider.Companion.PROVIDER)
                }
                if (provider != null) {
                    result.addTransitive(provider.getPackageSpecifications())
                } else {
                    reporter.handle(
                        com.google.devtools.build.lib.events.Event.error(
                            target.getLocation(),
                            java.lang.String.format("Label '%s' does not refer to a package group", groupLabel)
                        )
                    )
                }
            }

            result.add(packageGroupsVisibility.getDirectPackages())
            return result.build()
        }

        private fun findVisibilityPrerequisite(
            prerequisiteMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>, label: Label?
        ): TransitiveInfoCollection? {
            for (prerequisite in prerequisiteMap.get(DependencyKind.VISIBILITY_DEPENDENCY)) {
                if (prerequisite.getTargetLabel().equals(label) && prerequisite.getConfiguration() == null) {
                    return prerequisite.getConfiguredTarget()
                }
            }
            return null
        }

        private fun findTransitiveVisibilityPrerequisite(
            prerequisiteMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>, label: Label?
        ): TransitiveInfoCollection? {
            for (prerequisite in prerequisiteMap.get(DependencyKind.TRANSITIVE_VISIBILITY_DEPENDENCY)) {
                // Just return the first one.
                if (prerequisite.getTargetLabel().equals(label) && prerequisite.getConfiguration() == null) {
                    return prerequisite.getConfiguredTarget()
                }
            }
            return null
        }

        /**
         * Checks that all the rule advertised providers are returned in the configured target and add
         * error to `ruleContext` if not.
         */
        private fun validateRuleAdvertisedProviders(
            ruleContext: RuleContext,
            configuredTarget: ConfiguredTarget,
            advertisedProviders: AdvertisedProviderSet
        ) {
            for (providerId in advertisedProviders.getStarlarkProviders()) {
                if (configuredTarget.get(providerId) == null) {
                    ruleContext.ruleError(
                        java.lang.String.format(
                            "rule advertised the '%s' provider, but this provider was not among those"
                                    + " returned",
                            providerId
                        )
                    )
                }
            }

            for (klass in advertisedProviders.getBuiltinProviders()) {
                if (configuredTarget.getProvider(klass.asSubclass<U?>(TransitiveInfoProvider::class.java)) == null) {
                    ruleContext.ruleError(
                        java.lang.String.format(
                            "rule advertised the '%s' provider, but this provider was not among those"
                                    + " returned",
                            klass.getSimpleName()
                        )
                    )
                }
            }
        }

        /**
         * If `--allow_analysis_failures` is true, returns a collection of propagated analysis
         * failures from the target's dependencies and `extraDeps` -- one NestedSet per dep with
         * failures to propagate. Otherwise if `--allow_analysis_failures` is false, returns the
         * empty set.
         */
        private fun depAnalysisFailures(
            ruleContext: RuleContext, extraDeps: Iterable<out TransitiveInfoCollection?>?
        ): com.google.common.collect.ImmutableList<NestedSet<AnalysisFailure?>?> {
            if (ruleContext.getConfiguration().allowAnalysisFailures()) {
                val analysisFailures: com.google.common.collect.ImmutableList.Builder<NestedSet<AnalysisFailure?>?> =
                    com.google.common.collect.ImmutableList.builder<NestedSet<AnalysisFailure?>?>()
                val infoCollections: Iterable<out TransitiveInfoCollection> =
                    com.google.common.collect.Iterables.< T > concat < T >(ruleContext.getAllPrerequisites(), extraDeps)
                for (infoCollection in infoCollections) {
                    val failureInfo: AnalysisFailureInfo? =
                        infoCollection.get(AnalysisFailureInfo.STARLARK_CONSTRUCTOR)
                    if (failureInfo != null) {
                        analysisFailures.add(failureInfo.getCausesNestedSet())
                    }
                }
                return analysisFailures.build()
            }
            // Analysis failures are only created and propagated if --allow_analysis_failures is
            // enabled, otherwise these result in actual rule errors which are not caught.
            return com.google.common.collect.ImmutableList.of<NestedSet<AnalysisFailure?>?>()
        }

        @Throws(
            ActionConflictException::class,
            java.lang.InterruptedException::class,
            AnalysisFailurePropagationException::class
        )
        private fun erroredConfiguredTargetWithFailures(
            ruleContext: RuleContext, analysisFailures: MutableList<NestedSet<AnalysisFailure?>?>?
        ): ConfiguredTarget {
            val builder: RuleConfiguredTargetBuilder = RuleConfiguredTargetBuilder(ruleContext)
            builder.addNativeDeclaredProvider(AnalysisFailureInfo.forAnalysisFailureSets(analysisFailures))
            builder.addProvider<RunfilesProvider?>(
                RunfilesProvider::class.java,
                RunfilesProvider.Companion.simple(com.google.devtools.build.lib.analysis.Runfiles.Companion.EMPTY)
            )
            val configuredTarget: ConfiguredTarget = builder.build()
            if (configuredTarget == null) {
                // A failure here is a failure in analysis failure testing machinery, not a "normal" analysis
                // failure that some outer analysis failure test may want to capture. Instead, this failure
                // means that the outer test would be unusable. So we throw an exception rather than returning
                // null and allowing it to propagate up in the usual way.
                throw AnalysisFailurePropagationException(
                    ruleContext.getLabel(), ruleContext.getSuppressedErrorMessages()
                )
            }
            return configuredTarget
        }

        /**
         * Returns a [ConfiguredTarget] which indicates that an analysis error occurred in
         * processing the target. In most cases, this returns null, which signals to callers that the
         * target failed to build and thus the build should fail. However, if analysis failures are
         * allowed in this build, this returns a stub [ConfiguredTarget] which contains information
         * about the failure.
         */
        // TODO(blaze-team): requiredConfigFragmentsProvider is used for Android feature flags and should
        // be removed together with them.
        @Throws(
            ActionConflictException::class,
            java.lang.InterruptedException::class,
            AnalysisFailurePropagationException::class
        )
        private fun erroredConfiguredTarget(
            ruleContext: RuleContext, requiredConfigFragmentsProvider: RequiredConfigFragmentsProvider?
        ): ConfiguredTarget? {
            if (ruleContext.getConfiguration().allowAnalysisFailures()) {
                val analysisFailures: com.google.common.collect.ImmutableList.Builder<AnalysisFailure?> =
                    com.google.common.collect.ImmutableList.builder<AnalysisFailure?>()

                for (errorMessage in ruleContext.getSuppressedErrorMessages()) {
                    analysisFailures.add(AnalysisFailure.create(ruleContext.getLabel(), errorMessage))
                }
                val builder: RuleConfiguredTargetBuilder = RuleConfiguredTargetBuilder(ruleContext)
                builder.addNativeDeclaredProvider(
                    AnalysisFailureInfo.forAnalysisFailures(analysisFailures.build())
                )
                builder.addProvider<RunfilesProvider?>(
                    RunfilesProvider::class.java,
                    RunfilesProvider.Companion.simple(com.google.devtools.build.lib.analysis.Runfiles.Companion.EMPTY)
                )
                if (requiredConfigFragmentsProvider != null) {
                    builder.addProvider(requiredConfigFragmentsProvider)
                }
                val configuredTarget: ConfiguredTarget = builder.build()
                if (configuredTarget == null) {
                    // See comment in erroredConfiguredTargetWithFailures.
                    throw AnalysisFailurePropagationException(
                        ruleContext.getLabel(), ruleContext.getSuppressedErrorMessages()
                    )
                }
                return configuredTarget
            } else {
                // Returning a null ConfiguredTarget is an indication a rule error occurred. Exceptions are
                // not propagated, as this would show a nasty stack trace to users, and only provide info
                // on one specific failure with poor messaging. By returning null, the caller can
                // inspect ruleContext for multiple errors and output thorough messaging on each.
                return null
            }
        }

        private fun missingFragmentError(
            ruleContext: RuleContext,
            configurationFragmentPolicy: ConfigurationFragmentPolicy,
            configurationId: String?
        ): String {
            val ruleClass: RuleClass = ruleContext.getRule().getRuleClassObject()
            val missingFragments: MutableSet<java.lang.Class<*>?> = LinkedHashSet<java.lang.Class<*>?>()
            for (fragment in configurationFragmentPolicy.getRequiredConfigurationFragments()) {
                if (!ruleContext.getConfiguration().hasFragment(fragment)) {
                    missingFragments.add(fragment)
                }
            }
            com.google.common.base.Preconditions.checkState(!missingFragments.isEmpty())
            return ("all rules of type "
                    + ruleClass.getName()
                    + " require the presence of all of ["
                    + missingFragments.stream()
                .map<String?>(java.util.function.Function { obj: java.lang.Class<*>? -> obj.getSimpleName() })
                .collect(Collectors.joining(","))
                    + "], but these were all disabled in configuration "
                    + configurationId)
        }

        @com.google.common.annotations.VisibleForTesting
        fun removeToolchainDeps(
            map: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>
        ): OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?> {
            val result: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?> =
                OrderedSetMultimap.create()

            for (entry in map.entries()) {
                if (DependencyKind.isToolchain(entry.getKey())) {
                    continue
                }
                result.put(entry.getKey(), entry.getValue())
            }

            return result
        }

        @Throws(ActionConflictException::class, java.lang.InterruptedException::class)
        private fun erroredConfiguredAspectWithFailures(
            ruleContext: RuleContext?, analysisFailures: MutableList<NestedSet<AnalysisFailure?>?>?
        ): ConfiguredAspect? {
            val builder: com.google.devtools.build.lib.analysis.ConfiguredAspect.Builder =
                com.google.devtools.build.lib.analysis.ConfiguredAspect.Builder(ruleContext)
            builder.addNativeDeclaredProvider(AnalysisFailureInfo.forAnalysisFailureSets(analysisFailures))

            // Unlike erroredConfiguredTargetAspectWithFailures, we do not add a RunfilesProvider; that
            // would result in a RunfilesProvider being provided twice in the merged configured target.

            // TODO(b/242887801): builder.build() could potentially return null; in that case, should we
            // throw an exception, as erroredConfiguredTarget does, to avoid propagating the error to an
            // outer analysis failure test?
            return builder.build()
        }

        /**
         * Returns a [ConfiguredAspect] which indicates that an analysis error occurred in
         * processing the aspect. In most cases, this returns null, which signals to callers that the
         * target failed to build and thus the build should fail. However, if analysis failures are
         * allowed in this build, this returns a stub [ConfiguredAspect] which contains information
         * about the failure.
         */
        @Throws(ActionConflictException::class, java.lang.InterruptedException::class)
        fun erroredConfiguredAspect(
            ruleContext: RuleContext,
            requiredConfigFragmentsProvider: RequiredConfigFragmentsProvider?
        ): ConfiguredAspect? {
            if (ruleContext.getConfiguration().allowAnalysisFailures()) {
                val analysisFailures: com.google.common.collect.ImmutableList.Builder<AnalysisFailure?> =
                    com.google.common.collect.ImmutableList.builder<AnalysisFailure?>()

                for (errorMessage in ruleContext.getSuppressedErrorMessages()) {
                    analysisFailures.add(AnalysisFailure.create(ruleContext.getLabel(), errorMessage))
                }
                val builder: com.google.devtools.build.lib.analysis.ConfiguredAspect.Builder =
                    com.google.devtools.build.lib.analysis.ConfiguredAspect.Builder(ruleContext)
                builder.addNativeDeclaredProvider(
                    AnalysisFailureInfo.forAnalysisFailures(analysisFailures.build())
                )

                if (requiredConfigFragmentsProvider != null) {
                    builder.addProvider(requiredConfigFragmentsProvider)
                }

                // Unlike erroredConfiguredTarget, we do not add a RunfilesProvider; that would result in a
                // RunfilesProvider being provided twice in the merged configured target.

                // TODO(b/242887801): builder.build() could potentially return null; in that case, should we
                // throw an exception, as erroredConfiguredTarget does, to avoid propagating the error to an
                // outer analysis failure test?
                return builder.build()
            } else {
                // Returning a null ConfiguredAspect is an indication a rule error occurred. Exceptions are
                // not propagated, as this would show a nasty stack trace to users, and only provide info
                // on one specific failure with poor messaging. By returning null, the caller can
                // inspect ruleContext for multiple errors and output thorough messaging on each.
                return null
            }
        }

        private fun validateAdvertisedProviders(
            configuredAspect: ConfiguredAspect,
            aspectKey: AspectKeyCreator.AspectKey,
            advertisedProviders: AdvertisedProviderSet,
            target: Target,
            eventHandler: com.google.devtools.build.lib.events.EventHandler
        ) {
            if (advertisedProviders.canHaveAnyProvider()) {
                return
            }
            for (aClass in advertisedProviders.getBuiltinProviders()) {
                if (configuredAspect.getProvider<TransitiveInfoProvider?>(
                        aClass.asSubclass<TransitiveInfoProvider?>(
                            TransitiveInfoProvider::class.java
                        )
                    ) == null
                ) {
                    eventHandler.handle(
                        com.google.devtools.build.lib.events.Event.error(
                            target.getLocation(),
                            java.lang.String.format(
                                "Aspect '%s', applied to '%s', does not provide advertised provider '%s'",
                                aspectKey.getAspectClass().getName(),
                                target.getLabel(),
                                aClass.getSimpleName()
                            )
                        )
                    )
                }
            }

            for (providerId in advertisedProviders.getStarlarkProviders()) {
                if (configuredAspect.get(providerId) == null) {
                    eventHandler.handle(
                        com.google.devtools.build.lib.events.Event.error(
                            target.getLocation(),
                            java.lang.String.format(
                                "Aspect '%s', applied to '%s', does not provide advertised provider '%s'",
                                aspectKey.getAspectClass().getName(), target.getLabel(), providerId
                            )
                        )
                    )
                }
            }
        }

        /**
         * A pseudo-implementation for configured targets that creates fail actions for all declared
         * outputs, both implicit and explicit, due to a missing fragment class.
         */
        @Throws(java.lang.InterruptedException::class)
        private fun createFailConfiguredTargetForMissingFragmentClass(
            ruleContext: RuleContext, missingFragmentClass: java.lang.Class<*>
        ): ConfiguredTarget {
            val builder: RuleConfiguredTargetBuilder = RuleConfiguredTargetBuilder(ruleContext)
            if (!ruleContext.getOutputArtifacts().isEmpty()) {
                ruleContext.registerAction(
                    FailAction(
                        ruleContext.getActionOwner(),
                        ruleContext.getOutputArtifacts(),
                        "Missing fragment class: " + missingFragmentClass.getName(),
                        Code.FRAGMENT_CLASS_MISSING
                    )
                )
            }
            builder.addProvider<RunfilesProvider?>(
                RunfilesProvider::class.java,
                RunfilesProvider.Companion.simple(com.google.devtools.build.lib.analysis.Runfiles.Companion.EMPTY)
            )
            try {
                return builder.build()
            } catch (e: ActionConflictException) {
                throw java.lang.IllegalStateException(
                    "Can't have an action conflict with one action: " + ruleContext.getLabel(), e
                )
            }
        }
    }
}
