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

import com.google.devtools.build.lib.packages.RuleClass.Builder.STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME

/**
 * Builder class for analyzed rule instances.
 * 
 * 
 * This is used to tell Bazel which [TransitiveInfoProvider]s are produced by the analysis
 * of a configured target. For more information about analysis, see [ ].
 * 
 * @see RuleConfiguredTargetFactory
 */
class RuleConfiguredTargetBuilder(ruleContext: RuleContext) {
    private val ruleContext: RuleContext
    private val providersBuilder: TransitiveInfoProviderMapBuilder = TransitiveInfoProviderMapBuilder()
    private val outputGroupBuilders: TreeMap<String?, NestedSetBuilder<Artifact?>?> =
        TreeMap<String?, NestedSetBuilder<Artifact?>?>()
    private val additionalTestActionTools: com.google.common.collect.ImmutableList.Builder<Artifact?> =
        com.google.common.collect.ImmutableList.Builder<Artifact?>()

    /** These are supported by all configured targets and need to be specially handled.  */
    private var filesToBuild: NestedSet<Artifact?>? = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

    private var runfilesSupport: RunfilesSupport? = null
    private var executable: Artifact? = null
    private val actionsWithoutExtraAction: com.google.common.collect.ImmutableSet<ActionAnalysisMetadata?> =
        com.google.common.collect.ImmutableSet.of<ActionAnalysisMetadata?>()

    init {
        this.ruleContext = ruleContext
    }

    /**
     * Constructs the RuleConfiguredTarget instance based on the values set for this Builder. Returns
     * null if there were rule errors reported.
     */
    @Throws(ActionConflictException::class, java.lang.InterruptedException::class)
    fun build(): ConfiguredTarget? {
        // If allowing analysis failures, the current target may not propagate all of the
        // expected providers; be lenient on such cases (for example, avoid precondition checks).
        val allowAnalysisFailures: Boolean = ruleContext.getConfiguration().allowAnalysisFailures()

        if (ruleContext.getConfiguration().enforceConstraints()) {
            checkConstraints()
        }

        for (allowlistChecker in ruleContext.getRule().getRuleClassObject().getAllowlistCheckers()) {
            handleAllowlistChecker(allowlistChecker)
        }
        if (ruleContext.getConfiguration().enforceTransitiveVisibility()) {
            // Gather the transitive_visibility from this target's package and deps.
            // If there are any, propagate their union in a TransitiveVisibilityProvider.
            // One packageSpecificationProvider is created for each package_group, corresponding to the
            // restrictions imposed by a single bottom level dependency.
            val tvBuilder: com.google.common.collect.ImmutableSet.Builder<PackageSpecificationProvider?> =
                com.google.common.collect.ImmutableSet.builder<PackageSpecificationProvider?>()
            if (ruleContext.getTransitiveVisibilityImposedByThisPackage() != null) {
                tvBuilder.add(ruleContext.getTransitiveVisibilityImposedByThisPackage())
            }
            for (attributeName in ruleContext.attributes().getAttributeNames()) {
                val attribute: Attribute = ruleContext.attributes().getAttributeDefinition(attributeName)
                if (attribute.getType().getLabelClass() === LabelClass.DEPENDENCY) {
                    for (dep in ruleContext.getPrerequisites(attributeName)) {
                        val provider: TransitiveVisibilityProvider? =
                            dep.getProvider(TransitiveVisibilityProvider::class.java)
                        if (provider != null) {
                            tvBuilder.addAll(provider.getTransitiveVisibility())
                        }
                    }
                }
            }
            val finalTransitiveVisibility: com.google.common.collect.ImmutableSet<PackageSpecificationProvider?> =
                tvBuilder.build()
            if (!finalTransitiveVisibility.isEmpty()) {
                addProvider(TransitiveVisibilityProvider(finalTransitiveVisibility))
            }
        }

        if (ruleContext.hasErrors() && !allowAnalysisFailures) {
            return null
        }

        maybeAddRequiredConfigFragmentsProvider()

        val runfilesTrees: NestedSet<Artifact?>? =
            if (runfilesSupport != null)
                NestedSetBuilder.create(Order.STABLE_ORDER, runfilesSupport.getRunfilesTreeArtifact())
            else
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)

        val filesToRunProvider: FilesToRunProvider =
            FilesToRunProvider.Companion.create(
                buildFilesToRun(runfilesTrees, filesToBuild), runfilesSupport, executable
            )
        addProvider(FileProvider.of(filesToBuild))
        addProvider(filesToRunProvider)

        if (runfilesSupport != null) {
            // If a binary is built, build its runfiles, too
            addOutputGroup(OutputGroupInfo.Companion.HIDDEN_TOP_LEVEL, runfilesTrees)
        } else if (providersBuilder.contains(RunfilesProvider::class.java)) {
            // If we don't have a RunfilesSupport (probably because this is not a binary rule), we still
            // want to build the files this rule contributes to runfiles of dependent rules so that we
            // report an error if one of these is broken.
            //
            // Note that this is a best-effort thing: there is .getDataRunfiles() and all the language-
            // specific *RunfilesProvider classes, which we don't add here for reasons that are lost in
            // the mists of time.
            addOutputGroup(
                OutputGroupInfo.Companion.HIDDEN_TOP_LEVEL,
                providersBuilder
                    .getProvider(RunfilesProvider::class.java)
                    .getDefaultRunfiles()
                    .getAllArtifacts()
            )
        }

        if (propagateValidationActionOutputGroup()) {
            propagateTransitiveValidationOutputGroups()
        }

        // Add a default provider that forwards InstrumentedFilesInfo from dependencies, even if this
        // rule doesn't configure InstrumentedFilesInfo. This needs to be done for non-test rules
        // as well, but should be done before initializeTestProvider, which uses that.
        if (ruleContext.getConfiguration().isCodeCoverageEnabled()
            && !providersBuilder.contains(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR.getKey()) && !providersBuilder.contains(
                ToolchainInfo.PROVIDER.getKey()
            )
        ) {
            addNativeDeclaredProvider(InstrumentedFilesCollector.forwardAll(ruleContext))
        }
        // Create test action and artifacts if target was successfully initialized
        // and is a test. Also, as an extreme hack, only bother doing this if the TestConfiguration
        // is actually present.
        if (TargetUtils.isTestRule(ruleContext.getTarget())) {
            val testTags: com.google.common.collect.ImmutableList<String?> =
                com.google.common.collect.ImmutableList.copyOf(ruleContext.getRule().getRuleTags())
            add<TestTagsProvider?>(TestTagsProvider::class.java, TestTagsProvider(testTags))
            if (ruleContext.getConfiguration().hasFragment<T?>(TestConfiguration::class.java)) {
                if (runfilesSupport != null) {
                    add<TestProvider?>(TestProvider::class.java, initializeTestProvider(filesToRunProvider))
                } else {
                    check(allowAnalysisFailures) { "Test rules must have runfiles" }
                }
            }
        }

        // Only add {@link ExtraActionProvider} if extra action listeners are applied
        if (!ruleContext.getConfiguration().getActionListeners().isEmpty()) {
            val extraActionsProvider: ExtraActionArtifactsProvider? =
                ExtraActionUtils.createExtraActionProvider(actionsWithoutExtraAction, ruleContext)
            add<T?>(ExtraActionArtifactsProvider::class.java, extraActionsProvider)
        }

        if (!outputGroupBuilders.isEmpty()) {
            addNativeDeclaredProvider(OutputGroupInfo.Companion.fromBuilders(outputGroupBuilders))
        }

        if (ruleContext.getConfiguration().evaluatingForAnalysisTest()) {
            if (ruleContext.getRule().isAnalysisTest()) {
                ruleContext.ruleError(
                    java.lang.String.format(
                        "analysis_test rule '%s' cannot be transitively "
                                + "depended on by another analysis test rule",
                        ruleContext.getLabel()
                    )
                )
                return null
            }
            addProvider(TransitiveLabelsInfo(transitiveLabels()))
        }

        if (ruleContext.getRule().hasAnalysisTestTransition()) {
            val labels: NestedSet<Label?> = transitiveLabels()
            val depCount: Int = labels.memoizedFlattenAndGetSize()
            if (depCount > ruleContext.getConfiguration().analysisTestingDepsLimit()) {
                ruleContext.ruleError(
                    java.lang.String.format(
                        ("analysis test rule exceeded maximum dependency edge count. "
                                + "Count: %s. Limit is %s. This limit is imposed on analysis test rules which "
                                + "use analysis_test_transition attribute transitions. Exceeding this limit "
                                + "indicates either the analysis_test has too many dependencies, or the "
                                + "underlying toolchains may be large. Try decreasing the number of test "
                                + "dependencies, (Analysis tests should not be very large!) or, if possible, "
                                + "try not using configuration transitions. If underlying toolchain size is "
                                + "to blame, it might be worth considering increasing "
                                + "--analysis_testing_deps_limit. (Beware, however, that large values of "
                                + "this flag can lead to no safeguards against giant "
                                + "test suites that can lead to Out Of Memory exceptions in the build server.)"),
                        depCount, ruleContext.getConfiguration().analysisTestingDepsLimit()
                    )
                )
                return null
            }
        }

        if (ruleContext.getRule().isBuildSetting()) {
            val buildSetting: BuildSetting = ruleContext.getRule().getRuleClassObject().getBuildSetting()
            val defaultValue: Any? =
                ruleContext
                    .attributes()
                    .get(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, buildSetting.getType())
            addProvider<T?>(
                BuildSettingProvider::class.java,
                BuildSettingProvider(buildSetting, defaultValue, ruleContext.getLabel())
            )
        }
        val providers: TransitiveInfoProviderMap = providersBuilder.build()

        if (ruleContext.getRule().isAnalysisTest()) {
            // If the target is an analysis test that returned AnalysisTestResultInfo, register a
            // test pass/fail action on behalf of the target.
            val testResultInfo: AnalysisTestResultInfo? =
                providers.get(AnalysisTestResultInfo.STARLARK_CONSTRUCTOR)

            if (testResultInfo == null) {
                ruleContext.ruleError(
                    "rules with analysis_test=true must return an instance of AnalysisTestResultInfo"
                )
                return null
            }

            AnalysisTestActionBuilder.writeAnalysisTestAction(ruleContext, testResultInfo)
        }

        val analysisEnvironment: AnalysisEnvironment = ruleContext.getAnalysisEnvironment()
        val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> =
            analysisEnvironment.getRegisteredActions()
        try {
            Actions.assignOwnersAndThrowIfConflictToleratingSharedActions(
                analysisEnvironment.getActionKeyContext(), actions, ruleContext.getOwner()
            )
        } catch (e: Actions.ArtifactGeneratedByOtherRuleException) {
            ruleContext.ruleError(e.getMessage())
            return null
        }

        if (ruleContext.getConflictFinder() != null) {
            for (action in actions) {
                ruleContext.getConflictFinder().conflictCheckPerAction(action)
            }
        }
        return RuleConfiguredTarget(ruleContext, providers, actions)
    }

    private fun propagateValidationActionOutputGroup(): Boolean {
        return !ruleContext.getRule().isAnalysisTest()
    }

    /** Actually process  */
    private fun handleAllowlistChecker(allowlistChecker: AllowlistChecker) {
        if (allowlistChecker.attributeSetTrigger() != null
            && !ruleContext
                .getRule()
                .isAttributeValueExplicitlySpecified(allowlistChecker.attributeSetTrigger())
        ) {
            return
        }
        val passing =
            when (allowlistChecker.locationCheck()) {
                INSTANCE -> Allowlist.isAvailable(ruleContext, allowlistChecker.allowlistAttr())
                DEFINITION -> Allowlist.isAvailableBasedOnRuleLocation(
                    ruleContext, allowlistChecker.allowlistAttr()
                )

                INSTANCE_OR_DEFINITION -> Allowlist.isAvailable(ruleContext, allowlistChecker.allowlistAttr())
                        || Allowlist.isAvailableBasedOnRuleLocation(
                    ruleContext, allowlistChecker.allowlistAttr()
                )
            }
        if (!passing) {
            ruleContext.ruleError(allowlistChecker.errorMessage())
        }
    }

    /**
     * Adds [RequiredConfigFragmentsProvider] if [ ][CoreOptions.includeRequiredConfigFragmentsProvider] isn't [ ][CoreOptions.IncludeConfigFragmentsEnum.OFF] and if the provider is not already present.
     * 
     * 
     * For Stalark rules the provider is already added in [ ].
     * 
     * 
     * See [RequiredFragmentsUtil] for a description of the meaning of this provider's
     * content. That class contains methods that populate the results of [ ][RuleContext.getRequiredConfigFragments].
     */
    // TODO(blaze-team): Simplify the conditional logic and make it easier to understand.
    private fun maybeAddRequiredConfigFragmentsProvider() {
        if (ruleContext.shouldIncludeRequiredConfigFragmentsProvider()
            && !providersBuilder.contains(RequiredConfigFragmentsProvider::class.java)
        ) {
            addProvider(ruleContext.getRequiredConfigFragments())
        }
    }

    private fun transitiveLabels(): NestedSet<Label?> {
        val nestedSetBuilder: NestedSetBuilder<Label?> = NestedSetBuilder.stableOrder()

        for (attributeName in ruleContext.attributes().getAttributeNames()) {
            val attributeType: Type<*> =
                ruleContext.attributes().getAttributeDefinition(attributeName).getType()
            if (attributeType.getLabelClass() === LabelClass.DEPENDENCY) {
                for (labelsInfo in ruleContext.getPrerequisites<TransitiveLabelsInfo>(
                    attributeName,
                    TransitiveLabelsInfo::class.java
                )) {
                    nestedSetBuilder.addTransitive(labelsInfo.getLabels())
                }
            }
        }
        nestedSetBuilder.add(ruleContext.getLabel())
        return nestedSetBuilder.build()
    }

    /**
     * Collects the validation action output groups from every dependency-type attribute of this
     * target and adds them to this target's output groups.
     * 
     * 
     * This is done within [RuleConfiguredTargetBuilder] so that every rule always and
     * automatically propagates the validation action output group.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun propagateTransitiveValidationOutputGroups() {
        if (outputGroupBuilders.containsKey(OutputGroupInfo.Companion.VALIDATION_TRANSITIVE)) {
            val rdeLabel: Label? =
                ruleContext.getRule().getRuleClassObject().getRuleDefinitionEnvironmentLabel()
            // only allow native and builtins to override transitive validation propagation
            if (rdeLabel != null
                && BuiltinRestriction.isNotAllowed(
                    rdeLabel,
                    ruleContext.getAnalysisEnvironment().getMainRepoMapping(),
                    BuiltinRestriction.INTERNAL_STARLARK_API_ALLOWLIST
                )
            ) {
                ruleContext.ruleError(rdeLabel.toString() + " cannot access the _transitive_validation private API")
                return
            }
            addOutputGroup(
                OutputGroupInfo.Companion.VALIDATION,
                outputGroupBuilders.remove(OutputGroupInfo.Companion.VALIDATION_TRANSITIVE).build()
            )
        } else {
            collectTransitiveValidationOutputGroups(
                ruleContext,
                java.util.function.Predicate { unused: String? -> true },
                java.util.function.Consumer { validationArtifacts: NestedSet<Artifact?>? ->
                    addOutputGroup(
                        OutputGroupInfo.Companion.VALIDATION,
                        validationArtifacts
                    )
                })
        }
    }

    /**
     * Compute the artifacts to put into the [FilesToRunProvider] for this target. These are the
     * filesToBuild, the runfiles tree of the rule if it exists, as well as the executable.
     */
    private fun buildFilesToRun(
        runfilesTrees: NestedSet<Artifact?>?, filesToBuild: NestedSet<Artifact?>?
    ): NestedSet<Artifact?> {
        val builder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .addTransitive(filesToBuild)
                .addTransitive(runfilesTrees)
        if (executable != null && ruleContext.getRule().getRuleClassObject().isStarlark()) {
            builder.add(executable)
        }
        return builder.build()
    }

    /**
     * Invokes Blaze's constraint enforcement system: checks that this rule's dependencies support its
     * environments and reports appropriate errors if violations are found. Also publishes this rule's
     * supported environments for the rules that depend on it.
     */
    private fun checkConstraints() {
        if (!ruleContext.getRule().getRuleClassObject().supportsConstraintChecking()) {
            return
        }
        val constraintSemantics: ConstraintSemantics<RuleContext?> =
            ruleContext.getRuleClassProvider().getConstraintSemantics()
        val supportedEnvironments: EnvironmentCollection? =
            constraintSemantics.getSupportedEnvironments(ruleContext)
        if (supportedEnvironments != null) {
            val refinedEnvironments: EnvironmentCollection.Builder = Builder()
            val removedEnvironmentCulprits: MutableMap<Label?, RemovedEnvironmentCulprit?> =
                LinkedHashMap<Label?, RemovedEnvironmentCulprit?>()
            constraintSemantics.checkConstraints(
                ruleContext, supportedEnvironments, refinedEnvironments, removedEnvironmentCulprits
            )
            add<T?>(
                SupportedEnvironmentsProvider::class.java,
                SupportedEnvironments.create(
                    supportedEnvironments, refinedEnvironments.build(), removedEnvironmentCulprits
                )
            )
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun initializeTestProvider(filesToRunProvider: FilesToRunProvider): TestProvider {
        val explicitShardCount: Int =
            ruleContext.attributes().get("shard_count", Type.INTEGER).toIntUnchecked()
        if (explicitShardCount < 0
            && ruleContext.getRule().isAttributeValueExplicitlySpecified("shard_count")
        ) {
            ruleContext.attributeError("shard_count", "Must not be negative.")
        }
        if (explicitShardCount > 50) {
            ruleContext.attributeError(
                "shard_count",
                "Having more than 50 shards is indicative of poor test organization. "
                        + "Please reduce the number of shards."
            )
        }
        val testActionBuilder: TestActionBuilder =
            TestActionBuilder(ruleContext)
                .setInstrumentedFiles(
                    providersBuilder.getProvider(
                        InstrumentedFilesInfo.STARLARK_CONSTRUCTOR.getKey()
                    ) as InstrumentedFilesInfo?
                )

        val testParams: TestParams =
            testActionBuilder
                .setFilesToRunProvider(filesToRunProvider)
                .addTools(additionalTestActionTools.build())
                .setExecutionRequirements(
                    providersBuilder.getProvider(ExecutionInfo.PROVIDER.getKey()) as ExecutionInfo?
                )
                .build()
        return TestProvider(testParams)
    }

    /**
     * Add a specific provider with a given value.
     * 
     */
    @Deprecated("use {@link #addProvider}")
    fun <T : TransitiveInfoProvider?> add(key: java.lang.Class<T?>?, value: T?): RuleConfiguredTargetBuilder {
        return addProvider<T?>(key, value)
    }

    /** Add a specific provider with a given value.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun <T : TransitiveInfoProvider?> addProvider(
        key: java.lang.Class<out T?>?, value: T?
    ): RuleConfiguredTargetBuilder {
        providersBuilder.put(key, value)
        return this
    }

    /** Adds a specific provider.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addProvider(provider: TransitiveInfoProvider?): RuleConfiguredTargetBuilder {
        providersBuilder.add(provider)
        return this
    }

    /** Add a collection of specific providers.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addProviders(providers: TransitiveInfoProviderMap?): RuleConfiguredTargetBuilder {
        providersBuilder.addAll(providers)
        return this
    }

    /**
     * Adds a "declared provider" defined in Starlark to the rule. Use this method for declared
     * providers defined in Starlark. The provider symbol must be exported.
     * 
     * 
     * Has special handling for [OutputGroupInfo]: that provider is not added from Starlark
     * directly, instead its output groups are added.
     * 
     * 
     * Use [.addNativeDeclaredProvider] in definitions of native rules.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addStarlarkDeclaredProvider(provider: Info): RuleConfiguredTargetBuilder {
        val constructor: Provider = provider.getProvider()
        // Starlark providers are already exported (enforced by SRCTU.getProviderKey).
        com.google.common.base.Preconditions.checkArgument(constructor.isExported())
        if (OutputGroupInfo.Companion.STARLARK_CONSTRUCTOR.getKey().equals(constructor.getKey())) {
            val outputGroupInfo: OutputGroupInfo = provider as OutputGroupInfo
            for (outputGroup in outputGroupInfo) {
                addOutputGroup(outputGroup, outputGroupInfo.getOutputGroup(outputGroup))
            }
        } else {
            providersBuilder.put(provider)
        }
        return this
    }

    /**
     * Adds "declared providers" defined in native code to the rule. Use this method for declared
     * providers in definitions of native rules.
     * 
     * 
     * Use [.addStarlarkDeclaredProvider] for Starlark rule implementations.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addNativeDeclaredProviders(providers: Iterable<Info>): RuleConfiguredTargetBuilder {
        for (provider in providers) {
            addNativeDeclaredProvider(provider)
        }
        return this
    }

    /**
     * Adds a "declared provider" defined in native code to the rule. Use this method for declared
     * providers in definitions of native rules.
     * 
     * 
     * Use [.addStarlarkDeclaredProvider] for Starlark rule implementations.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addNativeDeclaredProvider(provider: Info): RuleConfiguredTargetBuilder {
        val constructor: Provider = provider.getProvider()
        com.google.common.base.Preconditions.checkState(constructor.isExported())
        providersBuilder.put(provider)
        return this
    }

    /**
     * Returns true if a provider matching the given provider key has already been added to the
     * configured target builder.
     */
    fun containsProviderKey(providerKey: Provider.Key?): Boolean {
        return providersBuilder.contains(providerKey)
    }

    /**
     * Returns true if a provider matching the given legacy key has already been added to the
     * configured target builder.
     */
    fun containsLegacyKey(legacyId: String?): Boolean {
        return providersBuilder.contains(legacyId)
    }

    /** Add a Starlark transitive info. The provider value must be safe.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addStarlarkTransitiveInfo(name: String?, value: Any?): RuleConfiguredTargetBuilder {
        providersBuilder.put(name, value)
        return this
    }

    /** Set the runfiles support for executable targets.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setRunfilesSupport(
        runfilesSupport: RunfilesSupport?, executable: Artifact?
    ): RuleConfiguredTargetBuilder {
        this.runfilesSupport = runfilesSupport
        this.executable = executable
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addTestActionTools(tools: MutableList<Artifact?>): RuleConfiguredTargetBuilder {
        this.additionalTestActionTools.addAll(tools)
        return this
    }

    /** Set the files to build.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setFilesToBuild(filesToBuild: NestedSet<Artifact?>?): RuleConfiguredTargetBuilder {
        this.filesToBuild = filesToBuild
        return this
    }

    private fun getOutputGroupBuilder(name: String?): NestedSetBuilder<Artifact?>? {
        var result: NestedSetBuilder<Artifact?>? = outputGroupBuilders.get(name)
        if (result != null) {
            return result
        }

        result = NestedSetBuilder.stableOrder()
        outputGroupBuilders.put(name, result)
        return result
    }

    /** Adds a set of files to an output group.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addOutputGroup(name: String?, artifacts: NestedSet<Artifact?>?): RuleConfiguredTargetBuilder {
        getOutputGroupBuilder(name).addTransitive(artifacts)
        return this
    }

    /** Adds a file to an output group.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addOutputGroup(name: String?, artifact: Artifact?): RuleConfiguredTargetBuilder {
        getOutputGroupBuilder(name).add(artifact)
        return this
    }

    /** Adds multiple output groups.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addOutputGroups(groups: MutableMap<String?, NestedSet<Artifact?>?>): RuleConfiguredTargetBuilder {
        for (group in groups.entrySet()) {
            getOutputGroupBuilder(group.getKey()).addTransitive(group.getValue())
        }

        return this
    }

    /**
     * Contains a nested set of transitive dependencies of the target which propagated this object.
     * 
     * 
     * This is automatically provided by all targets which are being evaluated in analysis testing.
     * 
     * 
     * For large builds, this object will become *very large*, but analysis tests are required
     * to be very small. The small-size of analysis tests are enforced by evaluating the size of this
     * object.
     */
    private class TransitiveLabelsInfo(labels: NestedSet<Label?>?) : TransitiveInfoProvider {
        private val labels: NestedSet<Label?>?

        init {
            this.labels = labels
        }

        fun getLabels(): NestedSet<Label?>? {
            return labels
        }
    }

    companion object {
        /**
         * Collects the validation action output groups from every dependency-type attribute of the given
         * target that matches the given predicate and passes them to the given consumer.
         * 
         * 
         * This function can be used to implement custom validation action propagation logic that for
         * example ignores some attributes.
         */
        fun collectTransitiveValidationOutputGroups(
            ruleContext: RuleContext,
            includeAttribute: java.util.function.Predicate<String?>,
            consumer: java.util.function.Consumer<NestedSet<Artifact?>?>
        ) {
            for (attributeName in ruleContext.attributes().getAttributeNames()) {
                if (!includeAttribute.test(attributeName)) {
                    continue
                }

                // Validation actions for tools, or from implicit deps should
                // not fail the overall build, since those dependencies should have their own builds
                // and tests that should surface any failing validations.
                val attribute: Attribute = ruleContext.attributes().getAttributeDefinition(attributeName)
                if (!attribute.skipValidations() && !attribute.isToolDependency() && !attribute.isImplicit() && attribute.getType()
                        .getLabelClass() === LabelClass.DEPENDENCY
                ) {
                    for (outputGroup in ruleContext.getPrerequisites(
                        attributeName,
                        OutputGroupInfo.Companion.STARLARK_CONSTRUCTOR
                    )) {
                        val validationArtifacts: NestedSet<Artifact?> =
                            outputGroup.getOutputGroup(OutputGroupInfo.Companion.VALIDATION)

                        if (!validationArtifacts.isEmpty()) {
                            consumer.accept(validationArtifacts)
                        }
                    }
                }
            }
        }
    }
}
