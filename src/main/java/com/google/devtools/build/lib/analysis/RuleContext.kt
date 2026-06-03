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

import com.google.devtools.build.lib.analysis.constraints.ConstraintConstants.getOsFromConstraintsOrHost

/**
 * The totality of data available during the analysis of a rule.
 * 
 * 
 * These objects should not outlast the analysis phase. Do not pass them to [ ] objects or other persistent objects. There are
 * internal tests to ensure that RuleContext objects are not persisted that check the name of this
 * class, so update those tests if you change this class's name.
 * 
 * 
 * @see com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory
 * 
 * 
 * The class is intended to be sub-classed by [AspectContext], in order to share the code.
 * However, it's not intended for sub-classing beyond that, and the constructor is intentionally
 * package private to enforce that.
 */
open class RuleContext internal constructor(
    builder: Builder,
    attributes: AttributeMap?,
    prerequisitesCollection: PrerequisitesCollection,
    execGroupCollection: ExecGroupCollection
) : TargetContext(
    builder.env,
    builder.target.getAssociatedRule(),
    builder.configuration,
    getDirectPrerequisites(builder.prerequisiteMap),
    builder.visibility,
    builder.transitiveVisibilityImposedByThisPackage
), ActionConstructionContext, ActionRegistry, RuleErrorConsumer, java.lang.AutoCloseable {
    /** Custom dependency validation logic.  */
    interface PrerequisiteValidator {
        /**
         * Checks whether the rule in `contextBuilder` is allowed to depend on `prerequisite` through the attribute `attribute`.
         * 
         * 
         * Can be used for enforcing any organization-specific policies about the layout of the
         * workspace.
         */
        fun validate(
            contextBuilder: Builder?, prerequisite: ConfiguredTargetAndData?, attribute: Attribute?
        )

        /**
         * Returns whether a package is considered experimental. Packages outside of experimental may
         * not depend on packages that are experimental.
         */
        fun packageUnderExperimental(packageIdentifier: PackageIdentifier?): Boolean

        /**
         * Returns whether a package is considered to be in the prototypes directory. Packages outside
         * of prototypes may not depend on packages that are in prototypes.
         */
        fun packageUnderPrototypes(packageIdentifier: PackageIdentifier?): Boolean

        /**
         * Returns whether the given package is allowed to depend on prototype packages. (If the given
         * package is itself an experimental or prototype package, this method's result is ignored.)
         */
        fun mayDependOnPrototypes(packageIdentifier: PackageIdentifier?): Boolean {
            return false
        }
    }

    private val rule: Rule

    /**
     * If this `RuleContext` is for rule evaluation, this holds the attribute-based
     * prerequisites of the rule and if it is for aspect evaluation, it will contain the merged
     * prerequisites of the rule and the base aspects (rule attributes take precedence).
     */
    private val prerequisitesCollection: PrerequisitesCollection

    private val configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>?
    private val attributes: AttributeMap?
    private val features: FeatureSet
    private val ruleClassNameForLogging: String?
    private val configurationFragmentPolicy: ConfigurationFragmentPolicy
    private val ruleClassProvider: ConfiguredRuleClassProvider
    private val reporter: RuleErrorConsumer
    private val toolchainContexts: ToolchainCollection<ResolvedToolchainContext?>?
    private val execGroupCollection: ExecGroupCollection
    private val requiredConfigFragments: RequiredConfigFragmentsProvider?

    private val transitivePackagesForRunfileRepoMappingManifest: NestedSet<Package.Metadata?>?

    private val makeVariableExpanders: MutableList<com.google.devtools.build.lib.analysis.Expander> =
        java.util.ArrayList<com.google.devtools.build.lib.analysis.Expander>()

    /** Map of exec group names to ActionOwners.  */
    private val actionOwners: MutableMap<String?, ActionOwner?> = HashMap<String?, ActionOwner?>()

    private val actionOwnerSymbolGenerator: SymbolGenerator<ActionLookupKey?>?

    /* lazily computed cache for Make variables, computed from the above. See get... method */
    @Transient
    private var configurationMakeVariableContext: ConfigurationMakeVariableContext? = null

    /**
     * Thread used for any Starlark evaluation during analysis, e.g. rule implementation function for
     * a Starlark-defined rule, or Starlarkified helper logic for native rules that have been
     * partially migrated to `@_builtins`.
     */
    private val starlarkThread: StarlarkThread

    /**
     * The `ctx` object passed to a Starlark-defined rule's or aspect's implementation function.
     * This object may outlive the analysis phase, e.g. if it is returned in a provider.
     * 
     * 
     * Initialized explicitly by calling [.initStarlarkRuleContext]. Native rules that do not
     * pass this object to `@_builtins` might avoid the cost of initializing this object, but
     * for everyone else it's mandatory.
     */
    private var starlarkRuleContext: StarlarkRuleContext? = null

    private val conflictFinder: com.google.common.base.Supplier<IncrementalArtifactConflictFinder?>

    private fun computeFeatures(): FeatureSet {
        val pkg: FeatureSet = rule.getPackageDeclarations().getPackageArgs().features()
        val rule: FeatureSet =
            if (attributes().has("features", Types.STRING_LIST))
                FeatureSet.Companion.parse(attributes().get("features", Types.STRING_LIST))
            else
                FeatureSet.Companion.EMPTY
        return FeatureSet.Companion.mergeWithGlobalFeatures(
            FeatureSet.Companion.merge(pkg, rule), getConfiguration().getDefaultFeatures()
        )
    }

    fun isAllowTagsPropagation(): Boolean {
        return getAnalysisEnvironment()
            .getStarlarkSemantics()
            .getBool(BuildLanguageOptions.INCOMPATIBLE_ALLOW_TAGS_PROPAGATION)
    }

    /**
     * If this `RuleContext` is for rule evaluation, returns the attribute-based prerequisites
     * of the rule and if it is for aspect evaluation, it returns the merged prerequisites of the rule
     * and the base aspects (rule attributes take precedence).
     */
    fun getRulePrerequisitesCollection(): PrerequisitesCollection {
        return prerequisitesCollection
    }

    /**
     * Prerequisites lookup methods in `RuleContext` such as [ ][RuleContext.getExecutablePrerequisite] use this method to find the `PrerquisitesCollection` owning an attribute with the given name.
     * 
     * 
     * For aspect evaluation, [AspectContext] overrides this to select the correct owning
     * `PrerequisitesCollection` for the given `attributeName` whether it is owned by the
     * main aspect or the underlying rule and base aspects.
     */
    open fun getOwningPrerequisitesCollection(attributeName: String?): PrerequisitesCollection {
        return prerequisitesCollection
    }

    fun getRepository(): RepositoryName {
        return rule.getRepository()
    }

    override fun getBinDirectory(): ArtifactRoot? {
        return getConfiguration().getBinDirectory(getLabel().getRepository())
    }

    fun getGenfilesDirectory(): ArtifactRoot? {
        return getConfiguration().getGenfilesDirectory(getLabel().getRepository())
    }

    fun getTestLogsDirectory(): ArtifactRoot? {
        return getConfiguration().getTestLogsDirectory(getLabel().getRepository())
    }

    fun getBinFragment(): PathFragment? {
        return getConfiguration().getBinFragment(getLabel().getRepository())
    }

    fun getGenfilesFragment(): PathFragment? {
        return getConfiguration().getGenfilesFragment(getLabel().getRepository())
    }

    fun getRule(): Rule {
        return rule
    }

    open fun getAspects(): com.google.common.collect.ImmutableList<Aspect?>? {
        return com.google.common.collect.ImmutableList.of<Aspect?>()
    }

    /**
     * If this target's configuration suppresses analysis failures, this returns a list of strings,
     * where each string corresponds to a description of an error that occurred during processing this
     * target.
     * 
     * @throws IllegalStateException if this target's configuration does not suppress analysis
     * failures (if `getConfiguration().allowAnalysisFailures()` is false)
     */
    fun getSuppressedErrorMessages(): MutableList<String?> {
        com.google.common.base.Preconditions.checkState(
            getConfiguration().allowAnalysisFailures(),
            "Error messages can only be retrieved via RuleContext if allow_analysis_failures is true"
        )
        com.google.common.base.Preconditions.checkState(
            reporter is SuppressingErrorReporter, "Unexpected error reporter"
        )
        return (reporter as SuppressingErrorReporter).getErrorMessages()
    }

    /**
     * If this `RuleContext` is for an aspect implementation, returns that aspect. (it is
     * the last aspect in the list of aspects applied to a target; all other aspects are the ones main
     * aspect sees as specified by its "required_aspect_providers") Otherwise returns `null
    ` * .
     */
    open fun getMainAspect(): Aspect? {
        return null
    }

    /**
     * Returns a rule class name suitable for log messages, including an aspect name if applicable.
     */
    fun getRuleClassNameForLogging(): String? {
        return ruleClassNameForLogging
    }

    /** Returns the workspace name for the rule.  */
    fun getWorkspaceName(): String {
        return rule.getPackageMetadata().workspaceName()
    }

    /** The configuration conditions that trigger this rule's configurable attributes.  */
    fun getConfigConditions(): com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>? {
        return configConditions
    }

    /** All aspects applied to the rule.  */
    open fun getAspectDescriptors(): com.google.common.collect.ImmutableList<AspectDescriptor?>? {
        return com.google.common.collect.ImmutableList.of<AspectDescriptor?>()
    }

    /**
     * Accessor for the attributes of the rule and its aspects.
     * 
     * 
     * The rule's native attributes can be queried both on their structure / existence and values
     * Aspect attributes can only be queried on their structure.
     * 
     * 
     * This should be the sole interface for reading rule/aspect attributes in [RuleContext].
     * Don't expose other access points through new public methods.
     */
    fun attributes(): AttributeMap? {
        return attributes
    }

    public override fun hasErrors(): Boolean {
        return reporter.hasErrors()
    }

    /** Returns a list of all prerequisites as `ConfiguredTarget` objects.  */
    open fun getAllPrerequisites(): com.google.common.collect.ImmutableList<out TransitiveInfoCollection?>? {
        return prerequisitesCollection.getAllPrerequisites()
    }

    /** Returns the [ConfiguredTargetAndData] the given attribute.  */
    fun getPrerequisiteConfiguredTargets(attributeName: String?): MutableList<ConfiguredTargetAndData?>? {
        return getOwningPrerequisitesCollection(attributeName)
            .getPrerequisiteConfiguredTargets(attributeName)
    }

    /**
     * Returns a special action owner for test actions. Test actions should run on the target platform
     * rather than the host platform. Note that the value is not cached (on the assumption that this
     * method is only called once).
     */
    fun getTestActionOwner(): ActionOwner {
        val testExecutionPlatform: PlatformInfo?
        val testExecProperties: com.google.common.collect.ImmutableMap<String?, String?>?

        // If we have a toolchain, pull the target platform out of it.
        if (toolchainContexts != null) {
            // TODO(https://github.com/bazelbuild/bazel/issues/17466): This doesn't respect execution
            // properties coming from the target's `exec_properties` attribute.
            // src/test/java/com/google/devtools/build/lib/analysis/test/TestActionBuilderTest.java has a
            // test to test for it when it gets figured out.
            testExecutionPlatform = toolchainContexts.getTargetPlatform()
            testExecProperties = testExecutionPlatform.execProperties()
        } else {
            testExecutionPlatform = null
            testExecProperties = getExecGroups().getExecProperties(DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME)
        }

        return createActionOwner(
            rule,
            getAspectDescriptors(),
            getConfiguration(),
            testExecProperties,
            testExecutionPlatform
        )
    }

    override fun getActionOwner(): ActionOwner? {
        return getActionOwner(DEFAULT_EXEC_GROUP_NAME)
    }

    override fun getActionOwner(execGroup: String?): ActionOwner? {
        if (actionOwners.containsKey(execGroup)) {
            return actionOwners.get(execGroup)
        }
        if (toolchainContexts != null && !toolchainContexts.hasToolchainContext(execGroup)) {
            return null
        }
        val actionOwner: ActionOwner =
            createActionOwner(
                rule,
                getAspectDescriptors(),
                getConfiguration(),
                execGroupCollection.getExecProperties(execGroup),
                getExecutionPlatform(execGroup)
            )
        actionOwners.put(execGroup, actionOwner)
        return actionOwner
    }

    /**
     * An opaque symbol generator to be used when identifying objects by their action owner/index of
     * creation. Only needed if an object needs to know whether it was created by the same action
     * owner in the same order as another object. Each symbol must call [ ][SymbolGenerator.generate] separately to obtain a unique object.
     */
    fun getSymbolGenerator(): SymbolGenerator<*>? {
        return actionOwnerSymbolGenerator
    }

    /** Returns a configuration fragment for this this target.  */
    fun <T : com.google.devtools.build.lib.analysis.config.Fragment?> getFragment(fragment: java.lang.Class<T?>): T? {
        return getFragment<T?>(fragment, fragment.getSimpleName(), "")
    }

    private fun <T : com.google.devtools.build.lib.analysis.config.Fragment?> getFragment(
        fragment: java.lang.Class<T?>, name: String?, additionalErrorMessage: String?
    ): T? {
        // TODO(bazel-team): The fragments can also be accessed directly through
        // BuildConfigurationValue. Can we lock that down somehow?
        com.google.common.base.Preconditions.checkArgument(
            isLegalFragment<T?>(fragment),
            "%s has to declare '%s' as a required fragment in order to access it.%s",
            ruleClassNameForLogging,
            name,
            additionalErrorMessage
        )
        return getConfiguration().getFragment<T?>(fragment)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    fun getStarlarkFragment(name: String?): com.google.devtools.build.lib.analysis.config.Fragment? {
        val fragmentClass: java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>? =
            getConfiguration().getStarlarkFragmentByName(name)
        if (fragmentClass == null) {
            return null
        }
        try {
            com.google.common.base.Preconditions.checkArgument(
                isLegalFragment(fragmentClass),
                ("%s has to declare '%s' as a required fragment in order to access it."
                        + " Please update the 'fragments' argument of the rule definition "
                        + "(for example: fragments = [\"%s\"])"),
                ruleClassNameForLogging,
                name,
                name
            )
            return getConfiguration().getFragment(fragmentClass)
        } catch (ex: java.lang.IllegalArgumentException) { // fishy
            throw net.starlark.java.eval.EvalException(ex.getMessage())
        }
    }

    fun getStarlarkFragmentNames(): com.google.common.collect.ImmutableCollection<String?>? {
        return getConfiguration().getStarlarkFragmentNames()
    }

    fun <T : com.google.devtools.build.lib.analysis.config.Fragment?> isLegalFragment(fragment: java.lang.Class<T?>?): Boolean {
        return ruleClassProvider.getFragmentRegistry().getUniversalFragments().contains(fragment)
                || configurationFragmentPolicy.isLegalConfigurationFragment(fragment)
    }

    public override fun getOwner(): ActionLookupKey {
        return getAnalysisEnvironment().getOwner()
    }

    public override fun registerAction(action: ActionAnalysisMetadata?) {
        getAnalysisEnvironment().registerAction(action)
    }

    /**
     * Convenience function for subclasses to report non-attribute-specific errors in the current
     * rule.
     */
    public override fun ruleError(message: String?) {
        reporter.ruleError(message)
    }

    /**
     * Convenience function for subclasses to report non-attribute-specific warnings in the current
     * rule.
     */
    public override fun ruleWarning(message: String?) {
        reporter.ruleWarning(message)
    }

    /**
     * Convenience function for subclasses to report attribute-specific errors in the current rule.
     * 
     * 
     * If the name of the attribute starts with `$` it is replaced with a string `
     * (an implicit dependency)`.
     */
    public override fun attributeError(attrName: String?, message: String?) {
        reporter.attributeError(attrName, message)
    }

    /**
     * Like attributeError, but does not mark the configured target as errored.
     * 
     * 
     * If the name of the attribute starts with `$` it is replaced with a string `
     * (an implicit dependency)`.
     */
    public override fun attributeWarning(attrName: String?, message: String?) {
        reporter.attributeWarning(attrName, message)
    }

    /**
     * Returns an artifact beneath the root of either the "bin" or "genfiles" tree, whose path is
     * based on the name of this target and the current configuration. The choice of which tree to use
     * is based on the rule with which this target (which must be an OutputFile or a Rule) is
     * associated.
     */
    fun createOutputArtifact(): Artifact? {
        val target: Target = getTarget()
        val rootRelativePath: PathFragment =
            getPackageDirectory().getRelative(PathFragment.create(target.getName()))

        return internalCreateOutputArtifact(rootRelativePath, target, OutputFile.Kind.FILE)
    }

    /**
     * Returns the output artifact of an [OutputFile] of this target.
     * 
     * @see .createOutputArtifact
     */
    fun createOutputArtifact(out: OutputFile): Artifact? {
        val packageRelativePath: PathFragment =
            getPackageDirectory().getRelative(PathFragment.create(out.getName()))
        return internalCreateOutputArtifact(packageRelativePath, out, out.getKind())
    }

    /**
     * Returns an artifact beneath the root of either the "bin" or "genfiles" tree, whose path is
     * based on the name of this target and the current configuration, with a script suffix
     * appropriate for the execution platform assigned to the default exec group (`.cmd` for
     * Windows, otherwise `.sh`). The choice of which tree to use is based on the rule with
     * which this target (which must be an OutputFile or a Rule) is associated.
     */
    fun createOutputArtifactScriptForAnalysisTest(): Artifact? {
        val target: Target = getTarget()

        val fileExtension = if (isDefaultExecGroupExecutingOnWindows()) ".cmd" else ".sh"

        val rootRelativePath: PathFragment =
            getPackageDirectory().getRelative(PathFragment.create(target.getName() + fileExtension))

        return internalCreateOutputArtifact(rootRelativePath, target, OutputFile.Kind.FILE)
    }

    /**
     * Implementation for [.createOutputArtifact] and [ ][.createOutputArtifact]. This is private so that [ ][.createOutputArtifact] can have a more specific signature.
     */
    private fun internalCreateOutputArtifact(
        rootRelativePath: PathFragment, target: Target, outputFileKind: OutputFile.Kind
    ): Artifact? {
        com.google.common.base.Preconditions.checkState(
            target.getLabel().getPackageIdentifier().equals(getLabel().getPackageIdentifier()),
            "Creating output artifact for target '%s' in different package than the rule '%s' "
                    + "being analyzed",
            target.getLabel(),
            getLabel()
        )
        val root: ArtifactRoot? = getBinOrGenfilesDirectory()

        return when (outputFileKind) {
            FILE -> getDerivedArtifact(rootRelativePath, root)
            FILESET -> getAnalysisEnvironment().getFilesetArtifact(rootRelativePath, root)
        }
    }

    /**
     * Returns the root of either the "bin" or "genfiles" tree, based on this target and the current
     * configuration. The choice of which tree to use is based on the rule with which this target
     * (which must be an OutputFile or a Rule) is associated.
     */
    override fun getBinOrGenfilesDirectory(): ArtifactRoot? {
        return if (rule.outputsToBindir())
            getConfiguration().getBinDirectory(getLabel().getRepository())
        else
            getConfiguration().getGenfilesDirectory(getLabel().getRepository())
    }

    /**
     * Creates an artifact in a directory that is unique to the package that contains the rule, thus
     * guaranteeing that it never clashes with artifacts created by rules in other packages.
     */
    fun getBinArtifact(relative: String?): Artifact? {
        return getBinArtifact(PathFragment.create(relative))
    }

    fun getBinArtifact(relative: PathFragment?): Artifact? {
        return getPackageRelativeArtifact(
            relative, getConfiguration().getBinDirectory(getLabel().getRepository())
        )
    }

    /**
     * Creates an artifact in a directory that is unique to the package that contains the rule, thus
     * guaranteeing that it never clashes with artifacts created by rules in other packages.
     */
    fun getGenfilesArtifact(relative: String?): Artifact? {
        return getGenfilesArtifact(PathFragment.create(relative))
    }

    fun getGenfilesArtifact(relative: PathFragment?): Artifact? {
        return getPackageRelativeArtifact(
            relative, getConfiguration().getGenfilesDirectory(getLabel().getRepository())
        )
    }

    override fun getShareableArtifact(rootRelativePath: PathFragment?, root: ArtifactRoot?): Artifact? {
        return getAnalysisEnvironment().getDerivedArtifact(rootRelativePath, root)
    }

    override fun getPackageRelativeArtifact(
        relative: PathFragment?, root: ArtifactRoot?
    ): Artifact.DerivedArtifact? {
        return getDerivedArtifact(getPackageDirectory().getRelative(relative), root)
    }

    /**
     * Creates an artifact in a directory that is unique to the package that contains the rule, thus
     * guaranteeing that it never clashes with artifacts created by rules in other packages.
     */
    fun getPackageRelativeArtifact(relative: String?, root: ArtifactRoot?): Artifact? {
        return getPackageRelativeArtifact(PathFragment.create(relative), root)
    }

    override fun getPackageDirectory(): PathFragment {
        return getLabel()
            .getPackageIdentifier()
            .getPackagePath(getConfiguration().isSiblingRepositoryLayout())
    }

    /**
     * Creates an artifact under a given root with the given root-relative path.
     * 
     * 
     * Verifies that it is in the root-relative directory corresponding to the package of the rule,
     * thus ensuring that it doesn't clash with other artifacts generated by other rules using this
     * method.
     */
    override fun getDerivedArtifact(
        rootRelativePath: PathFragment, root: ArtifactRoot?
    ): Artifact.DerivedArtifact? {
        com.google.common.base.Preconditions.checkState(
            rootRelativePath.startsWith(getPackageDirectory()),
            "Output artifact '%s' not under package directory '%s' for target '%s'",
            rootRelativePath,
            getPackageDirectory(),
            getLabel()
        )
        return getAnalysisEnvironment().getDerivedArtifact(rootRelativePath, root)
    }

    override fun getTreeArtifact(rootRelativePath: PathFragment, root: ArtifactRoot?): SpecialArtifact? {
        com.google.common.base.Preconditions.checkState(
            rootRelativePath.startsWith(getPackageDirectory()),
            "Output artifact '%s' not under package directory '%s' for target '%s'",
            rootRelativePath,
            getPackageDirectory(),
            getLabel()
        )
        return getAnalysisEnvironment().getTreeArtifact(rootRelativePath, root)
    }

    /**
     * Creates a tree artifact in a directory that is unique to the package that contains the rule,
     * thus guaranteeing that it never clashes with artifacts created by rules in other packages.
     */
    fun getPackageRelativeTreeArtifact(relative: PathFragment?, root: ArtifactRoot?): Artifact? {
        return getTreeArtifact(getPackageDirectory().getRelative(relative), root)
    }

    fun getPackageRelativeTreeArtifact(relative: String?, root: ArtifactRoot?): Artifact? {
        return getPackageRelativeTreeArtifact(PathFragment.create(relative), root)
    }

    /**
     * Creates an artifact in a directory that is unique to the rule, thus guaranteeing that it never
     * clashes with artifacts created by other rules.
     */
    fun getUniqueDirectoryArtifact(
        uniqueDirectory: String?, relative: String?, root: ArtifactRoot?
    ): Artifact? {
        return getUniqueDirectoryArtifact(uniqueDirectory, PathFragment.create(relative), root)
    }

    override fun getUniqueDirectoryArtifact(uniqueDirectorySuffix: String?, relative: String?): Artifact? {
        return getUniqueDirectoryArtifact(uniqueDirectorySuffix, relative, getBinOrGenfilesDirectory())
    }

    override fun getUniqueDirectoryArtifact(uniqueDirectorySuffix: String?, relative: PathFragment?): Artifact? {
        return getUniqueDirectoryArtifact(uniqueDirectorySuffix, relative, getBinOrGenfilesDirectory())
    }

    override fun getUniqueDirectoryArtifact(
        uniqueDirectory: String?, relative: PathFragment?, root: ArtifactRoot?
    ): Artifact? {
        return getDerivedArtifact(getUniqueDirectory(uniqueDirectory).getRelative(relative), root)
    }

    /**
     * Returns true iff the rule, or any attached aspect, has an attribute with the given name and
     * type.
     */
    fun isAttrDefined(attrName: String?, type: Type<*>?): Boolean {
        return attributes().has(attrName, type)
    }

    /**
     * Returns the specified provider of the prerequisite referenced by the attribute in the argument.
     * If the attribute is empty or it does not support the specified provider, returns null.
     */
    fun <C : TransitiveInfoProvider?> getPrerequisite(
        attributeName: String?, provider: java.lang.Class<C?>?
    ): C? {
        return getOwningPrerequisitesCollection(attributeName).getPrerequisite<C?>(attributeName, provider)
    }

    /**
     * Returns the transitive info collection that feeds into this target through the specified
     * attribute. Returns null if the attribute is empty.
     */
    fun getPrerequisite(attributeName: String?): TransitiveInfoCollection? {
        return getOwningPrerequisitesCollection(attributeName).getPrerequisite(attributeName)
    }

    /**
     * Returns the declared provider (native and Starlark) for the specified constructor under the
     * specified attribute of this target in the BUILD file. May return null if there is no
     * TransitiveInfoCollection under the specified attribute.
     */
    fun <T : Info?> getPrerequisite(
        attributeName: String?, builtinProvider: BuiltinProvider<T?>?
    ): T? {
        return getOwningPrerequisitesCollection(attributeName)
            .getPrerequisite(attributeName, builtinProvider)
    }

    @Throws(RuleErrorException::class)
    fun <T> getPrerequisite(attributeName: String?, key: StarlarkProviderWrapper<T?>?): T? {
        return getOwningPrerequisitesCollection(attributeName).getPrerequisite(attributeName, key)
    }

    /**
     * Returns the `--run_under` prerequisite based on the value of `--incompatible_bazel_test_exec_run_under`.
     */
    fun getRunUnderPrerequisite(): TransitiveInfoCollection? {
        return getPrerequisite(
            if (getConfiguration().runUnderExecConfigForTests())
                ":run_under_exec_config"
            else
                ":run_under_target_config"
        )
    }

    /**
     * Returns the list of transitive info collections that feed into this target through the
     * specified attribute.
     */
    fun getPrerequisites(attributeName: String?): MutableList<out TransitiveInfoCollection?>? {
        return getOwningPrerequisitesCollection(attributeName).getPrerequisites(attributeName)
    }

    /**
     * Returns all the providers of the specified type that are listed under the specified attribute
     * of this target in the BUILD file.
     */
    fun <C : TransitiveInfoProvider?> getPrerequisites(
        attributeName: String?, classType: java.lang.Class<C?>?
    ): MutableList<C?> {
        return getOwningPrerequisitesCollection(attributeName)
            .getPrerequisites<C?>(attributeName, classType)
    }

    /**
     * Returns all the declared providers (native and Starlark) for the specified constructor under
     * the specified attribute of this target in the BUILD file.
     */
    fun <T : Info?> getPrerequisites(
        attributeName: String?, starlarkKey: BuiltinProvider<T?>?
    ): MutableList<T?>? {
        return getOwningPrerequisitesCollection(attributeName)
            .getPrerequisites(attributeName, starlarkKey)
    }

    /**
     * Returns the prerequisite referred to by the specified attribute. Also checks whether the
     * attribute is marked as executable and that the target referred to can actually be executed.
     * 
     * @param attributeName the name of the attribute
     * @return the [FilesToRunProvider] interface of the prerequisite.
     */
    fun getExecutablePrerequisite(attributeName: String?): FilesToRunProvider? {
        return getOwningPrerequisitesCollection(attributeName).getExecutablePrerequisite(attributeName)
    }

    fun fromAttributes(attributeNames: Iterable<String?>): com.google.common.collect.ImmutableList<TemplateVariableInfo?> {
        // Get template variable providers from the attributes.
        val fromAttributes: com.google.common.collect.ImmutableList<TemplateVariableInfo?> =
            com.google.common.collect.Streams.stream<String?>(attributeNames) // Only process this attribute it if is present in the rule.
                .filter(java.util.function.Predicate { attrName: String? ->
                    this.attributes().has(attrName)
                }) // Get the TemplateVariableInfo providers from this attribute.
                .flatMap(
                    java.util.function.Function { attrName: String? ->
                        this.getPrerequisites(
                            attrName,
                            TemplateVariableInfo.PROVIDER
                        ).stream()
                    })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())

        return fromAttributes
    }

    fun fromToolchains(): com.google.common.collect.ImmutableList<TemplateVariableInfo?>? {
        if (this.getToolchainContexts() == null) {
            return com.google.common.collect.ImmutableList.of<TemplateVariableInfo?>()
        }

        val toolchainProviders: com.google.common.collect.ImmutableList<TemplateVariableInfo?>? =
            this.getToolchainContexts().contextMap().values().stream()
                .flatMap({ context -> context.templateVariableProviders().stream() })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        return toolchainProviders
    }

    /** The constructor is intentionally package private to be only used by [AspectContext].  */
    init {
        this.rule = builder.target.getAssociatedRule()
        this.configurationFragmentPolicy = builder.configurationFragmentPolicy
        this.ruleClassProvider = builder.ruleClassProvider
        this.configConditions = builder.configConditions.asProviders
        this.attributes = attributes
        this.features = computeFeatures()
        this.ruleClassNameForLogging = builder.getRuleClassNameForLogging()
        this.actionOwnerSymbolGenerator = SymbolGenerator.create<ActionLookupKey?>(builder.actionOwnerSymbol)
        this.reporter = builder.reporter
        this.toolchainContexts = builder.toolchainContexts
        this.execGroupCollection = execGroupCollection
        this.requiredConfigFragments = builder.requiredConfigFragments
        this.transitivePackagesForRunfileRepoMappingManifest =
            builder.transitivePackagesForRunfileRepoMappingManifest
        this.starlarkThread = createStarlarkThread(builder.mutability) // uses above state
        this.prerequisitesCollection = prerequisitesCollection
        this.conflictFinder = builder.conflictFinder
    }

    open fun getDefaultTemplateVariableProviders(): com.google.common.collect.ImmutableList<TemplateVariableInfo?> {
        val ruleTemplateVariableInfo: com.google.common.collect.ImmutableList<TemplateVariableInfo?> =
            com.google.common.collect.ImmutableList.Builder<TemplateVariableInfo?>()
                .addAll(fromAttributes(DEFAULT_MAKE_VARIABLE_ATTRIBUTES))
                .addAll(fromToolchains())
                .build()

        return ruleTemplateVariableInfo
    }

    fun getExpander(templateContext: com.google.devtools.build.lib.analysis.stringtemplate.TemplateContext?): com.google.devtools.build.lib.analysis.Expander {
        val expander: com.google.devtools.build.lib.analysis.Expander =
            com.google.devtools.build.lib.analysis.Expander(this, templateContext)
        makeVariableExpanders.add(expander)
        return expander
    }

    fun getExpander(): com.google.devtools.build.lib.analysis.Expander {
        val expander: com.google.devtools.build.lib.analysis.Expander =
            com.google.devtools.build.lib.analysis.Expander(this, getConfigurationMakeVariableContext())
        makeVariableExpanders.add(expander)
        return expander
    }

    fun getExpander(labelMap: com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?>?): com.google.devtools.build.lib.analysis.Expander {
        val expander: com.google.devtools.build.lib.analysis.Expander =
            com.google.devtools.build.lib.analysis.Expander(this, getConfigurationMakeVariableContext(), labelMap)
        makeVariableExpanders.add(expander)
        return expander
    }

    /**
     * Returns a cached context that maps Make variable names (string) to values (string) without any
     * extra [MakeVariableSupplier].
     * 
     * 
     * CAUTION: If there's no context, this will initialize the context.
     */
    open fun getConfigurationMakeVariableContext(): ConfigurationMakeVariableContext? {
        if (configurationMakeVariableContext == null) {
            configurationMakeVariableContext =
                ConfigurationMakeVariableContext(
                    rule.getPackageDeclarations(),
                    getConfiguration(),
                    getDefaultTemplateVariableProviders()
                )
        }
        return configurationMakeVariableContext
    }

    private fun createStarlarkThread(mutability: Mutability?): StarlarkThread {
        val env: AnalysisEnvironment = getAnalysisEnvironment()
        val thread: StarlarkThread =
            StarlarkThread.create(
                mutability, env.getStarlarkSemantics(), getLabel().toString(), getSymbolGenerator()
            )
        thread.setPrintHandler(com.google.devtools.build.lib.events.Event.makeDebugPrintHandler(env.getEventHandler()))
        BazelRuleAnalysisThreadContext(this).storeInThread(thread)
        return thread
    }

    fun getStarlarkThread(): StarlarkThread {
        return starlarkThread
    }

    /**
     * Initializes the StarlarkRuleContext for use and returns it. No-op if already initialized.
     * 
     * 
     * Throws RuleErrorException on failure.
     */
    @Throws(RuleErrorException::class)
    fun initStarlarkRuleContext(): StarlarkRuleContext {
        if (starlarkRuleContext == null) {
            val aspectDescriptor: AspectDescriptor? =
                if (getMainAspect() == null) null else getMainAspect().getDescriptor()
            this.starlarkRuleContext = StarlarkRuleContext(this, aspectDescriptor)
        }
        return starlarkRuleContext
    }

    fun getStarlarkRuleContext(): StarlarkRuleContext {
        com.google.common.base.Preconditions.checkNotNull<StarlarkRuleContext?>(
            starlarkRuleContext,
            "Must call initStarlarkRuleContext() first"
        )
        return starlarkRuleContext
    }

    /**
     * Retrieves the `@_builtins`-defined Starlark object registered in the `exported_to_java` mapping under the given name.
     * 
     * 
     * Reports and raises a rule error if no symbol by that name is defined.
     */
    @Throws(RuleErrorException::class, java.lang.InterruptedException::class)
    fun getStarlarkDefinedBuiltin(name: String?): Any? {
        val result: Any? = getAnalysisEnvironment().getStarlarkDefinedBuiltins().get(name)
        if (result == null) {
            throwWithRuleError(
                java.lang.String.format(
                    "(Internal error) No symbol named '%s' defined in the @_builtins exported_to_java"
                            + " dict",
                    name
                )
            )
        }
        return result
    }

    /**
     * Calls a Starlark function in this rule's Starlark thread with the given positional and keyword
     * arguments. On failure, calls [.throwWithRuleError] with the Starlark stack trace.
     * 
     * 
     * This convenience method avoids the need to catch EvalException when the failure would just
     * immediately terminate rule analysis anyway.
     */
    @Throws(RuleErrorException::class, java.lang.InterruptedException::class)
    fun callStarlarkOrThrowRuleError(
        func: Any?, args: MutableList<Any?>?, kwargs: MutableMap<String?, Any?>?
    ): Any? {
        try {
            return Starlark.call(starlarkThread, func, args, kwargs)
        } catch (e: net.starlark.java.eval.EvalException) {
            throw throwWithRuleError(e.getMessageWithStack())
        }
    }

    /**
     * Returns the conflict finder if [ ][com.google.devtools.build.lib.skyframe.ConflictCheckingMode.UPON_CONFIGURED_OBJECT_CREATION]
     * and null otherwise.
     */
    fun getConflictFinder(): IncrementalArtifactConflictFinder? {
        return conflictFinder.get()
    }

    /**
     * Prepares Starlark objects created during this target's analysis for use by others. Freezes
     * mutability, clears expensive references.
     */
    override fun close() {
        starlarkThread.mutability().freeze()
        if (starlarkRuleContext != null) {
            starlarkRuleContext.close()
            starlarkRuleContext = null
        }
    }

    open fun useAutoExecGroups(): Boolean {
        return getRule()
            .getRuleClassObject()
            .getAutoExecGroupsMode()
            .isEnabled(attributes(), getConfiguration().useAutoExecGroups())
    }

    /**
     * Returns the toolchain context from the default exec group. Important note: In case automatic
     * exec groups are enabled, use `getToolchainInfo(Label toolchainType)` function.
     */
    fun getToolchainContext(): ResolvedToolchainContext? {
        return if (toolchainContexts == null) null else toolchainContexts.getDefaultToolchainContext()
    }

    private fun getToolchainContext(execGroup: String?): ResolvedToolchainContext? {
        return if (toolchainContexts == null) null else toolchainContexts.getToolchainContext(execGroup)
    }

    private fun getToolchainContextForToolchainType(toolchainType: Label): ResolvedToolchainContext? {
        val toolchainContext: ResolvedToolchainContext? =
            toolchainContexts.getToolchainContext(toolchainType.toString())
        if (toolchainContext != null && toolchainContext.forToolchainType(toolchainType) != null) {
            // Return early if name of the Automatic Exec Group (AEG) and toolchain type matches.
            return toolchainContext
        }

        // Alias can be used for toolchains, in which case name of AEG will not match with the toolchain
        // type in its ResolvedToolchainContext (AEGs are created before toolchain context is resolved).
        val aliasName: String? =
            toolchainContexts.getExecGroupNames().stream()
                .filter(DeclaredExecGroup::isAutomatic)
                .filter(
                    { name ->
                        val context: ResolvedToolchainContext? = toolchainContexts.getToolchainContext(name)
                        (context != null
                                && context.requestedToolchainTypeLabels().containsKey(toolchainType))
                    })
                .findFirst()
                .orElse(null)
        return if (aliasName == null) null else toolchainContexts.getToolchainContext(aliasName)
    }

    /**
     * Returns the toolchain info from the default exec group in case automatic exec groups are not
     * enabled. If they are enabled, retrieves toolchain info from the corresponding automatic exec
     * group.
     */
    fun getToolchainInfo(toolchainType: Label): ToolchainInfo? {
        val toolchainContext: ResolvedToolchainContext?
        if (useAutoExecGroups()) {
            toolchainContext = getToolchainContextForToolchainType(toolchainType)
        } else {
            toolchainContext = getToolchainContext()
        }
        return if (toolchainContext == null) null else toolchainContext.forToolchainType(toolchainType)
    }

    fun hasToolchainContext(execGroup: String?): Boolean {
        return toolchainContexts != null && toolchainContexts.hasToolchainContext(execGroup)
    }

    fun getToolchainContexts(): ToolchainCollection<ResolvedToolchainContext?>? {
        return toolchainContexts
    }

    fun getExecGroups(): ExecGroupCollection {
        return execGroupCollection
    }

    fun targetPlatformHasConstraint(constraintValue: ConstraintValueInfo?): Boolean {
        if (toolchainContexts == null || toolchainContexts.getTargetPlatform() == null) {
            return false
        }
        // All toolchain contexts should have the same target platform so we access via the default.
        return toolchainContexts.getTargetPlatform().constraints().hasConstraintValue(constraintValue)
    }

    fun getRuleClassProvider(): ConfiguredRuleClassProvider {
        return ruleClassProvider
    }

    /**
     * Returns the configuration fragments this rule uses if it should be included for this rule.
     * Otherwise it returns null.
     */
    fun getRequiredConfigFragments(): RequiredConfigFragmentsProvider? {
        if (requiredConfigFragments == null) {
            return null
        }

        var merged: RequiredConfigFragmentsProvider.Builder? = null

        // Add variables accessed through ctx.var, if this is a Starlark rule.
        if (starlarkRuleContext != null) {
            for (makeVariable in starlarkRuleContext.lookedUpVariables()) {
                if (isUserDefinedMakeVariable(makeVariable)) {
                    if (merged == null) {
                        merged = RequiredConfigFragmentsProvider.builder().merge(requiredConfigFragments)
                    }
                    merged.addDefine(makeVariable)
                }
            }
        }

        // Add variables accessed through Make variable substitution.
        for (makeVariableExpander in makeVariableExpanders) {
            for (makeVariable in makeVariableExpander.lookedUpVariables()) {
                if (isUserDefinedMakeVariable(makeVariable)) {
                    if (merged == null) {
                        merged = RequiredConfigFragmentsProvider.builder().merge(requiredConfigFragments)
                    }
                    merged.addDefine(makeVariable)
                }
            }
        }

        return if (merged == null) requiredConfigFragments else merged.build()
    }

    /**
     * Returns the set of transitive package metadata. This is only intended to be used to create the
     * repo mapping manifest for the runfiles tree. Can be null if transitive packages are not tracked
     * (see [ ][com.google.devtools.build.lib.skyframe.SkyframeExecutor.shouldStoreTransitivePackagesInLoadingAndAnalysis]).
     */
    fun getTransitivePackagesForRunfileRepoMappingManifest(): NestedSet<Package.Metadata?>? {
        return transitivePackagesForRunfileRepoMappingManifest
    }

    private fun isUserDefinedMakeVariable(makeVariable: String?): Boolean {
        // User-defined make values may be set either in "--define foo=bar" or in a vardef in the rule's
        // package. Both are equivalent for these purposes, since in both cases setting
        // "--define foo=bar" impacts the rule's output.
        return rule.getPackageDeclarations().getMakeEnvironment().containsKey(makeVariable)
                || getConfiguration().getCommandLineBuildVariables().containsKey(makeVariable)
    }

    override fun getExecutionPlatform(): PlatformInfo? {
        if (getToolchainContext() == null) {
            return null
        }
        return getToolchainContext().executionPlatform()
    }

    override fun getExecutionPlatform(execGroup: String?): PlatformInfo? {
        if (toolchainContexts == null) {
            return null
        }
        val toolchainContext: ResolvedToolchainContext? = getToolchainContext(execGroup)
        return if (toolchainContext == null) null else toolchainContext.executionPlatform()
    }

    fun getExecutionPlatformForToolchainType(toolchainType: Label): PlatformInfo? {
        if (toolchainContexts == null) {
            return null
        }
        val toolchainContext: ResolvedToolchainContext? = getToolchainContextForToolchainType(toolchainType)
        return if (toolchainContext == null) null else toolchainContext.executionPlatform()
    }

    /**
     * For the specified attribute "attributeName" (which must be of type list(label)), resolve all
     * the labels into ConfiguredTargets (for the configuration appropriate to the attribute) and
     * return their build artifacts as a [PrerequisiteArtifacts] instance.
     * 
     * @param attributeName the name of the attribute to traverse
     */
    fun getPrerequisiteArtifacts(attributeName: String?): PrerequisiteArtifacts {
        return PrerequisiteArtifacts.Companion.get(this, attributeName)
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
        return prerequisitesCollection.getPrerequisiteArtifact(attributeName)
    }

    /**
     * Returns a path fragment qualified by the rule name and unique fragment to disambiguate
     * artifacts produced from the source file appearing in multiple rules.
     * 
     * 
     * For example "pkg/dir/name" -> "pkg/&lt;fragment>/rule/dir/name.
     */
    fun getUniqueDirectory(fragment: String?): PathFragment? {
        return getUniqueDirectory(PathFragment.create(fragment))
    }

    /**
     * Returns a path fragment qualified by the rule name and unique fragment to disambiguate
     * artifacts produced from the source file appearing in multiple rules.
     * 
     * 
     * For example "pkg/dir/name" -> "pkg/&lt;fragment>/rule/dir/name.
     */
    override fun getUniqueDirectory(fragment: PathFragment?): PathFragment? {
        return AnalysisUtils.Companion.getUniqueDirectory(
            getLabel(), fragment, getConfiguration().isSiblingRepositoryLayout()
        )
    }

    /**
     * Check that all targets that were specified as sources are from the same package as this rule.
     * Output a warning or an error for every target that is imported from a different package.
     */
    fun checkSrcsSamePackage(onlyWarn: Boolean) {
        val packageName: PathFragment? = getLabel().getPackageFragment()
        for (srcItem in PrerequisiteArtifacts.Companion.get(this, "srcs").list()) {
            if (!srcItem.isSourceArtifact()) {
                // In theory, we should not do this check. However, in practice, we
                // have a couple of rules that do not obey the "srcs must contain
                // files and only files" rule. Thus, we are stuck with this hack here :(
                continue
            }
            val associatedLabel: Label = srcItem.getOwner()
            val itemPackageName: PathFragment = associatedLabel.getPackageFragment()
            if (!itemPackageName.equals(packageName)) {
                val message =
                    ("please do not import '"
                            + associatedLabel
                            + "' directly. "
                            + "You should either move the file to this package or depend on "
                            + "an appropriate rule there")
                if (onlyWarn) {
                    attributeWarning("srcs", message)
                } else {
                    attributeError("srcs", message)
                }
            }
        }
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getImplicitOutputArtifact(function: ImplicitOutputsFunction): Artifact? {
        val result: Iterable<String?>
        try {
            result =
                function.getImplicitOutputs(
                    getAnalysisEnvironment().getEventHandler(), RawAttributeMapper.of(rule)
                )
        } catch (e: net.starlark.java.eval.EvalException) {
            // It's ok as long as we don't use this method from Starlark.
            throw java.lang.IllegalStateException(e)
        }
        return getImplicitOutputArtifact(com.google.common.collect.Iterables.getOnlyElement<String?>(result))
    }

    /** Only use from Starlark. Returns the implicit output artifact for a given output path.  */
    fun getImplicitOutputArtifact(path: String?): Artifact? {
        return getPackageRelativeArtifact(path, getBinOrGenfilesDirectory())
    }

    /**
     * Returns the (unmodifiable, ordered) list of artifacts which are the outputs of this target.
     * 
     * 
     * Each element in this list is associated with a single output, either declared implicitly
     * (via setImplicitOutputsFunction()) or explicitly (listed in the 'outs' attribute of our rule).
     */
    fun getOutputArtifacts(): com.google.common.collect.ImmutableList<Artifact?> {
        val artifacts: com.google.common.collect.ImmutableList.Builder<Artifact?> =
            com.google.common.collect.ImmutableList.builder<Artifact?>()
        for (out in rule.getOutputFiles()) {
            artifacts.add(createOutputArtifact(out))
        }
        return artifacts.build()
    }

    /**
     * Like [.getOutputArtifacts] but for a singular output item. Reports an error if the
     * "out" attribute is not a singleton.
     * 
     * @return null if the output list is empty, the artifact for the first item of the output list
     * otherwise
     */
    fun getOutputArtifact(): Artifact? {
        val outs: MutableList<Artifact?> = getOutputArtifacts()
        if (outs.size() != 1) {
            attributeError("out", "exactly one output file required")
            if (outs.isEmpty()) {
                return null
            }
        }
        return outs.get(0)
    }

    override fun getRelatedArtifact(pathFragment: PathFragment?, extension: String?): Artifact.DerivedArtifact? {
        val file: PathFragment = FileSystemUtils.replaceExtension(pathFragment, extension)
        return getDerivedArtifact(file, getConfiguration().getBinDirectory(getLabel().getRepository()))
    }

    /** Returns true if the target for this context is a test target.  */
    fun isTestTarget(): Boolean {
        return TargetUtils.isTestRule(getTarget())
    }

    /** Returns true if the testonly attribute is set on this context.  */
    fun isTestOnlyTarget(): Boolean {
        return attributes().has("testonly", Type.BOOLEAN) && attributes().get("testonly", Type.BOOLEAN)
    }

    /** Returns true if the execution platform of the default exec group is Windows.  */
    fun isDefaultExecGroupExecutingOnWindows(): Boolean {
        return getOsFromConstraintsOrHost(getExecutionPlatform()) === OS.WINDOWS
    }

    /**
     * @return the set of features applicable for the current rule.
     */
    fun getFeatures(): com.google.common.collect.ImmutableSet<String?>? {
        return features.on
    }

    /**
     * @return the set of features that are disabled for the current rule.
     */
    fun getDisabledFeatures(): com.google.common.collect.ImmutableSet<String?>? {
        return features.off
    }

    override fun getRuleErrorConsumer(): RuleErrorConsumer? {
        return this
    }

    /**
     * Returns `true` if a [RequiredConfigFragmentsProvider] should be included for this
     * rule.
     */
    fun shouldIncludeRequiredConfigFragmentsProvider(): Boolean {
        return requiredConfigFragments != null
    }

    override fun toString(): String {
        return "RuleContext(" + getLabel() + ", " + getConfiguration() + ")"
    }

    /** Builder class for a RuleContext.  */
    class Builder @com.google.common.annotations.VisibleForTesting constructor(
        env: AnalysisEnvironment?,
        target: Target?,
        aspects: com.google.common.collect.ImmutableList<Aspect?>?,
        configuration: BuildConfigurationValue?
    ) : RuleErrorConsumer {
        private val env: AnalysisEnvironment
        private val target: Target
        private val aspects: com.google.common.collect.ImmutableList<Aspect?>
        private val configuration: BuildConfigurationValue
        private val reporter: RuleErrorConsumer
        private var ruleClassProvider: ConfiguredRuleClassProvider? = null
        private var configurationFragmentPolicy: ConfigurationFragmentPolicy? = null
        private var actionOwnerSymbol: ActionLookupKey? = null
        private var prerequisiteMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>? = null
        private var allowMaterializerRuleRealDeps = false

        private var materializerTargets: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>? = null

        private var configConditions: ConfigConditions? = null
        private var mutability: Mutability? = null
        private var visibility: NestedSet<PackageGroupContents?>? = null
        private var transitiveVisibilityImposedByThisPackage: PackageSpecificationProvider? = null
        private var toolchainContexts: ToolchainCollection<ResolvedToolchainContext?>? = null
        private var baseTargetToolchainContexts: ToolchainCollection<AspectBaseTargetResolvedToolchainContext?>? = null
        private var execGroupCollectionBuilder: ExecGroupCollection.Builder? = null
        private var rawExecProperties: com.google.common.collect.ImmutableMap<String?, String?>? = null

        private var conflictFinder: com.google.common.base.Supplier<IncrementalArtifactConflictFinder?> =
            com.google.common.base.Supplier { null }
        private var requiredConfigFragments: RequiredConfigFragmentsProvider? = null

        private var transitivePackagesForRunfileRepoMappingManifest: NestedSet<Package.Metadata?>? = null

        init {
            this.env = com.google.common.base.Preconditions.checkNotNull<AnalysisEnvironment>(env)
            this.target = com.google.common.base.Preconditions.checkNotNull<Target>(target)
            this.aspects =
                com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<Aspect?>>(
                    aspects
                )
            this.configuration =
                com.google.common.base.Preconditions.checkNotNull<BuildConfigurationValue>(configuration)
            if (configuration.allowAnalysisFailures()) {
                reporter = SuppressingErrorReporter()
            } else {
                reporter =
                    com.google.devtools.build.lib.analysis.RuleContext.ErrorReporter(
                        env, target.getAssociatedRule(), configuration, getRuleClassNameForLogging()
                    )
            }
        }

        /**
         * Same as [.build], except without some attribute checks.
         * 
         * 
         * Don't use this function outside of testing. The use should be limited to cases where
         * specifying ConfigConditions.EMPTY, which can cause a noMatchError when accessing attributes
         * within attribute checking.
         */
        @com.google.common.annotations.VisibleForTesting
        @Throws(InvalidExecGroupException::class)
        fun unsafeBuild(): RuleContext {
            return build(false)
        }

        @com.google.common.annotations.VisibleForTesting
        @Throws(InvalidExecGroupException::class)
        fun build(): RuleContext {
            return build(true)
        }

        @Throws(InvalidExecGroupException::class)
        private fun build(attributeChecks: Boolean): RuleContext {
            com.google.common.base.Preconditions.checkNotNull<ConfiguredRuleClassProvider?>(ruleClassProvider)
            com.google.common.base.Preconditions.checkNotNull<Any?>(configurationFragmentPolicy)
            com.google.common.base.Preconditions.checkNotNull<Any?>(actionOwnerSymbol)
            com.google.common.base.Preconditions.checkNotNull<Any?>(prerequisiteMap)
            com.google.common.base.Preconditions.checkNotNull<ConfigConditions?>(configConditions)
            com.google.common.base.Preconditions.checkNotNull<Mutability?>(mutability)
            com.google.common.base.Preconditions.checkNotNull<Any?>(visibility)
            val ruleAttributes: ConfiguredAttributeMapper =
                ConfiguredAttributeMapper.of(
                    target.getAssociatedRule(), configConditions.asProviders, configuration
                )
            val targetMap: com.google.common.collect.ImmutableListMultimap<DependencyKind?, ConfiguredTargetAndData?> =
                createTargetMap()
            validateExtraPrerequisites(attributeChecks, ruleAttributes)

            val execGroupCollection: ExecGroupCollection =
                createExecGroupCollection(execGroupCollectionBuilder, ruleAttributes)
            if (aspects.isEmpty()) {
                return create(this, ruleAttributes, targetMap, execGroupCollection)
            } else {
                return AspectContext.Companion.create(
                    this, ruleAttributes, targetMap, execGroupCollection, baseTargetToolchainContexts
                )
            }
        }

        @Throws(InvalidExecGroupException::class)
        private fun createExecGroupCollection(
            execGroupCollectionBuilder: ExecGroupCollection.Builder, attributes: AttributeMap
        ): ExecGroupCollection {
            if (rawExecProperties == null) {
                if (!attributes.has(RuleClass.EXEC_PROPERTIES_ATTR, Types.STRING_DICT)) {
                    rawExecProperties = com.google.common.collect.ImmutableMap.of<String?, String?>()
                } else {
                    rawExecProperties =
                        com.google.common.collect.ImmutableMap.copyOf(
                            attributes.get(RuleClass.EXEC_PROPERTIES_ATTR, Types.STRING_DICT)
                        )
                }
            }

            return execGroupCollectionBuilder.build(
                toolchainContexts, rawExecProperties, getRule().getDisplayFormLabel()
            )
        }

        private fun checkAttributesNonEmpty(attributes: AttributeMap) {
            for (attributeName in attributes.getAttributeNames()) {
                val attr: Attribute = attributes.getAttributeDefinition(attributeName)
                if (!attr.isNonEmpty()) {
                    continue
                }
                val attributeValue: Any? = attributes.get(attributeName, attr.getType())

                // TODO(adonovan): define in terms of Starlark.len?
                var isEmpty = false
                if (attributeValue is MutableList<*>) {
                    isEmpty = attributeValue.isEmpty()
                } else if (attributeValue is MutableMap<*, *>) {
                    isEmpty = attributeValue.isEmpty()
                }
                if (isEmpty) {
                    reporter.attributeError(attr.getName(), "attribute must be non empty")
                }
            }
        }

        private fun checkAttributesForDuplicateLabels(attributes: ConfiguredAttributeMapper) {
            for (attributeName in attributes.getAttributeNames()) {
                val attr: Attribute = attributes.getAttributeDefinition(attributeName)
                if (attr.getType() !== BuildType.LABEL_LIST) {
                    // It is not obvious but correct to skip LABEL_LIST_DICT here: since concatenating selects
                    // of dicts of lists does not concatenate the lists but picks the last one for each key,
                    // all possible duplicates have already been ruled out by AggregatingAttributeMapper.
                    continue
                }

                val duplicates: MutableSet<Label?> = attributes.checkForDuplicateLabels(attr)
                for (label in duplicates) {
                    reporter.attributeError(attr.getName(), java.lang.String.format("Label '%s' is duplicated", label))
                }
            }
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRuleClassProvider(ruleClassProvider: ConfiguredRuleClassProvider): Builder {
            this.ruleClassProvider = ruleClassProvider
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setAllowMaterializerRuleRealDeps(allowMaterializerRuleRealDeps: Boolean): Builder {
            this.allowMaterializerRuleRealDeps = allowMaterializerRuleRealDeps
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setConfigurationFragmentPolicy(policy: ConfigurationFragmentPolicy): Builder {
            this.configurationFragmentPolicy = policy
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setActionOwnerSymbol(actionOwnerSymbol: ActionLookupKey?): Builder {
            this.actionOwnerSymbol = actionOwnerSymbol
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setMutability(mutability: Mutability?): Builder {
            this.mutability = mutability
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setVisibility(visibility: NestedSet<PackageGroupContents?>?): Builder {
            this.visibility = visibility
            return this
        }

        /**
         * Sets the prerequisites and checks their visibility. It also generates appropriate error or
         * warning messages and sets the error flag as appropriate.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setPrerequisites(
            prerequisiteMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?
        ): Builder {
            this.prerequisiteMap =
                com.google.common.base.Preconditions.checkNotNull<OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>>(
                    prerequisiteMap
                )
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setMaterializerTargets(
            materializerTargets: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?
        ): Builder {
            this.materializerTargets = materializerTargets
            return this
        }

        /**
         * Sets the configuration conditions needed to determine which paths to follow for this rule's
         * configurable attributes.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setConfigConditions(configConditions: ConfigConditions?): Builder {
            this.configConditions =
                com.google.common.base.Preconditions.checkNotNull<ConfigConditions>(configConditions)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setTransitiveVisibilityImposedByThisPackage(
            transitiveVisibility: PackageSpecificationProvider?
        ): Builder {
            this.transitiveVisibilityImposedByThisPackage = transitiveVisibility
            return this
        }

        /** Sets the collection of [ResolvedToolchainContext]s available to this rule.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @com.google.common.annotations.VisibleForTesting
        fun setToolchainContexts(
            toolchainContexts: ToolchainCollection<ResolvedToolchainContext?>?
        ): Builder {
            com.google.common.base.Preconditions.checkState(
                this.toolchainContexts == null,
                "toolchainContexts has already been set for this Builder"
            )
            this.toolchainContexts = toolchainContexts
            return this
        }

        /**
         * Sets the collection of [AspectBaseTargetResolvedToolchainContext]s available to this
         * aspect from its base target.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setBaseTargetToolchainContexts(
            baseTargetToolchainContexts: ToolchainCollection<AspectBaseTargetResolvedToolchainContext?>?
        ): Builder {
            com.google.common.base.Preconditions.checkState(
                this.baseTargetToolchainContexts == null,
                "baseTargetToolchainContexts has already been set for this Builder"
            )
            this.baseTargetToolchainContexts = baseTargetToolchainContexts
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecGroupCollectionBuilder(
            execGroupCollectionBuilder: ExecGroupCollection.Builder
        ): Builder {
            this.execGroupCollectionBuilder = execGroupCollectionBuilder
            return this
        }

        /**
         * Warning: if you set the exec properties using this method any exec_properties attribute value
         * will be ignored in favor of this value.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecProperties(execProperties: com.google.common.collect.ImmutableMap<String?, String?>?): Builder {
            this.rawExecProperties = execProperties
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRequiredConfigFragments(
            requiredConfigFragments: RequiredConfigFragmentsProvider?
        ): Builder {
            this.requiredConfigFragments = requiredConfigFragments
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setTransitivePackagesForRunfileRepoMappingManifest(
            packages: NestedSet<Package.Metadata?>?
        ): Builder {
            this.transitivePackagesForRunfileRepoMappingManifest = packages
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setConflictFinder(conflictFinder: com.google.common.base.Supplier<IncrementalArtifactConflictFinder?>): Builder {
            this.conflictFinder = conflictFinder
            return this
        }

        /**
         * Filter only attribute-based prerequisites, validate them and return them in a map from [ ] to list of configured targets.
         */
        private fun createTargetMap(): com.google.common.collect.ImmutableListMultimap<DependencyKind?, ConfiguredTargetAndData?> {
            val mapBuilder: com.google.common.collect.ImmutableListMultimap.Builder<DependencyKind?, ConfiguredTargetAndData?> =
                com.google.common.collect.ImmutableListMultimap.builder<DependencyKind?, ConfiguredTargetAndData?>()

            for (entry in prerequisiteMap.asMap().entrySet()) {
                val attribute: Attribute? = entry.getKey().getAttribute()
                if (attribute == null) {
                    continue
                }

                if (attribute.isSingleArtifact() && entry.getValue().size() > 1) {
                    attributeError(attribute.getName(), "must contain a single dependency")
                    continue
                }

                val filter: com.google.common.base.Predicate<String?> =
                    if (attribute.isSilentRuleClassFilter())
                        attribute.getAllowedRuleClassPredicate()
                    else
                        com.google.common.base.Predicates.alwaysTrue<String?>()

                for (configuredTarget in entry.getValue()) {
                    if (filter.apply(configuredTarget.getRuleClass())) {
                        if (aspects.isEmpty()
                            || getMainAspect().getAspectClass().equals(entry.getKey().getOwningAspect())
                        ) {
                            // During aspects evaluation, only validate the dependencies of the main aspect.
                            // Dependencies of base aspects as well as the rule itself are checked when they
                            // are evaluated.
                            validateDirectPrerequisite(attribute, configuredTarget)
                        }
                        mapBuilder.put(entry.getKey(), configuredTarget)
                    }

                    if (attribute.isForDependencyResolution()) {
                        if (!configuredTarget.isForDependencyResolution()
                            && configuredTarget.getConfiguredTarget()
                                    !is PackageGroupConfiguredTarget
                        ) {
                            attributeError(
                                attribute.getName(),
                                java.lang.String.format(
                                    "attribute marked as available in materializers but prerequisite %s isn't",
                                    AliasProvider.Companion.describeTargetWithAliases(
                                        configuredTarget, TargetMode.WITH_KIND
                                    )
                                )
                            )
                        }
                    }

                    // Run materializer attribute validation only when constructing a context for rules and
                    // not for aspects because aspects aren't materializer rules. In particular doing this
                    // would break any aspect that has dependencies when it encounters a materializer rule.
                    if (!forAspect() && !allowMaterializerRuleRealDeps) {
                        // Materializer rules can depend only on dependency resolution rules via "normal" /
                        // non-dormant attributes, and everything else only via dormant attributes, except
                        // for PackageGroupConfiguredTargets and build settings.
                        if (target.getAssociatedRule().getRuleClassObject().isMaterializerRule()
                            && !(attribute.getType() === BuildType.DORMANT_LABEL
                                    || attribute.getType() === BuildType.DORMANT_LABEL_LIST) && !configuredTarget.isForDependencyResolution() && (configuredTarget.getConfiguredTarget()
                                    !is PackageGroupConfiguredTarget)
                        ) {
                            attributeError(
                                attribute.getName(),
                                java.lang.String.format(
                                    ("materializer rules can depend on only dependency resolution rules via"
                                            + " non-dormant attributes; %s is a non-dormant attribute, and %s is not"
                                            + " a dependency resolution rule"),
                                    attribute.getName(), configuredTarget.getTargetLabel()
                                )
                            )
                        }
                    }
                }
            }

            if (materializerTargets != null) {
                for (entry in materializerTargets.entries()) {
                    val attribute: Attribute? = entry.getKey().getAttribute()
                    if (attribute == null) {
                        continue
                    }
                    val materializerTarget: ConfiguredTargetAndData = entry.getValue()
                    validateDirectPrerequisite(attribute, materializerTarget)
                }
            }

            return mapBuilder.build()
        }

        public override fun ruleError(message: String?) {
            reporter.ruleError(message)
        }

        public override fun attributeError(attrName: String?, message: String?) {
            reporter.attributeError(attrName, message)
        }

        public override fun ruleWarning(message: String?) {
            reporter.ruleWarning(message)
        }

        public override fun attributeWarning(attrName: String?, message: String?) {
            reporter.attributeWarning(attrName, message)
        }

        public override fun hasErrors(): Boolean {
            return reporter.hasErrors()
        }

        private fun reportBadPrerequisite(
            attribute: Attribute,
            prerequisite: ConfiguredTargetAndData,
            reason: String?,
            isWarning: Boolean
        ) {
            val message: String? =
                com.google.devtools.build.lib.analysis.RuleContext.Builder.Companion.badPrerequisiteMessage(
                    prerequisite,
                    reason,
                    isWarning
                )
            if (isWarning) {
                attributeWarning(attribute.getName(), message)
            } else {
                attributeError(attribute.getName(), message)
            }
        }

        private fun validateDirectPrerequisiteType(
            prerequisite: ConfiguredTargetAndData, attribute: Attribute
        ) {
            if (prerequisite.isMaterializerRule()) {
                // Materializer rules pass along other targets, so don't check their providers.
                return
            }

            val ruleClass: String = prerequisite.getRuleClass()
            if (!ruleClass.isEmpty()) {
                validateRuleDependency(prerequisite, attribute)
                return
            }

            if (!(prerequisite.isTargetFile() && attribute.isStrictLabelCheckingEnabled())) {
                return
            }

            val prerequisiteTargetLabel: Label = prerequisite.getTargetLabel()
            if (attribute.getAllowedFileTypesPredicate().apply(prerequisiteTargetLabel.getName())) {
                return
            }

            if (prerequisite.isTargetInputFile() && !prerequisite.getInputPath().exists()) {
                // Misplaced labels, no corresponding target exists
                if (attribute.getAllowedFileTypesPredicate().isNone()
                    && !prerequisiteTargetLabel.getName().contains(".")
                ) {
                    // There are no allowed files in the attribute but it's not a valid rule,
                    // and the filename doesn't contain a dot --> probably a misspelled rule
                    attributeError(
                        attribute.getName(), "rule '" + prerequisiteTargetLabel + "' does not exist"
                    )
                } else {
                    attributeError(
                        attribute.getName(), "target '" + prerequisiteTargetLabel + "' does not exist"
                    )
                }
                return
            }
            // The file exists but has a bad extension
            reportBadPrerequisite(
                attribute, prerequisite, "expected " + attribute.getAllowedFileTypesPredicate(), false
            )
        }

        /** Returns whether the context being constructed is for the evaluation of an aspect.  */
        fun forAspect(): Boolean {
            return !aspects.isEmpty()
        }

        fun getRule(): Rule {
            return target.getAssociatedRule()
        }

        /**
         * Returns the [StarlarkSemantics] governs the building of this rule (and the rest of the
         * build).
         */
        fun getStarlarkSemantics(): StarlarkSemantics? {
            return env.getStarlarkSemantics()
        }

        /**
         * Returns a rule class name suitable for log messages, including an aspect name if applicable.
         */
        fun getRuleClassNameForLogging(): String? {
            if (aspects.isEmpty()) {
                return target.getAssociatedRule().getRuleClass()
            }

            return (com.google.common.base.Joiner.on(",")
                .join(aspects.stream().map<Any?>(Aspect::getDescriptor).collect(Collectors.toList()))
                    + " aspect on "
                    + target.getAssociatedRule().getRuleClass())
        }

        fun getErrorConsumer(): RuleErrorConsumer {
            return reporter
        }

        fun getConfiguration(): BuildConfigurationValue {
            return configuration
        }

        fun getMainAspect(): Aspect? {
            return com.google.common.collect.Streams.findLast<Aspect?>(aspects.stream()).orElse(null)
        }

        fun getAspects(): com.google.common.collect.ImmutableList<Aspect?> {
            return aspects
        }

        fun isStarlarkRuleOrAspect(): Boolean {
            val mainAspect: Aspect? = getMainAspect()
            if (mainAspect != null) {
                return mainAspect.getAspectClass() is StarlarkAspectClass
            } else {
                return getRule().getRuleClassObject().getRuleDefinitionEnvironmentLabel() != null
            }
        }

        private fun validateDirectPrerequisiteFileTypes(
            prerequisite: ConfiguredTargetAndData, attribute: Attribute
        ) {
            if (attribute.isSkipAnalysisTimeFileTypeCheck()) {
                return
            }
            val allowedFileTypes: FileTypeSet? = attribute.getAllowedFileTypesPredicate()
            if (allowedFileTypes == null) {
                // It's not a label or label_list attribute.
                return
            }
            if (allowedFileTypes === FileTypeSet.ANY_FILE && !attribute.isNonEmpty() && !attribute.isSingleArtifact()) {
                return
            }

            // If we allow any file we still need to check if there are actually files generated
            // Note that this check only runs for ANY_FILE predicates if the attribute is NON_EMPTY
            // or SINGLE_ARTIFACT
            // If we performed this check when allowedFileTypes == NO_FILE this would
            // always throw an error in those cases
            if (allowedFileTypes !== FileTypeSet.NO_FILE) {
                val artifacts: NestedSet<Artifact?> =
                    prerequisite.getConfiguredTarget().getProvider(FileProvider::class.java).getFilesToBuild()
                if (attribute.isSingleArtifact() && !artifacts.isSingleton()) {
                    attributeError(
                        attribute.getName(),
                        "'" + prerequisite.getTargetLabel() + "' must produce a single file"
                    )
                    return
                }
                for (sourceArtifact in artifacts.toList()) {
                    if (allowedFileTypes.apply(sourceArtifact.getFilename())) {
                        return
                    }
                    if (sourceArtifact.isTreeArtifact()) {
                        return
                    }
                }
                attributeError(
                    attribute.getName(),
                    ("'"
                            + prerequisite.getTargetLabel()
                            + "' does not produce any "
                            + getRuleClassNameForLogging()
                            + " "
                            + attribute.getName()
                            + " files (expected "
                            + allowedFileTypes
                            + ")")
                )
            }
        }

        /**
         * Because some rules still have to use allowedRuleClasses to do rule dependency validation. A
         * dependency is valid if it is from a rule in allowedRuledClasses, OR if all of the providers
         * in requiredProviders are provided by the target.
         */
        private fun validateRuleDependency(prerequisite: ConfiguredTargetAndData, attribute: Attribute) {
            val unfulfilledRequirements: MutableSet<String?> = LinkedHashSet<String?>()
            if (com.google.devtools.build.lib.analysis.RuleContext.Builder.Companion.checkRuleDependencyClass(
                    prerequisite,
                    attribute,
                    unfulfilledRequirements
                )
            ) {
                return
            }

            if (checkRuleDependencyClassWarnings(prerequisite, attribute)) {
                return
            }

            if (com.google.devtools.build.lib.analysis.RuleContext.Builder.Companion.checkRuleDependencyMandatoryProviders(
                    prerequisite,
                    attribute,
                    unfulfilledRequirements
                )
            ) {
                return
            }

            // not allowed rule class and some mandatory providers missing => reject.
            if (!unfulfilledRequirements.isEmpty()) {
                attributeError(
                    attribute.getName(), StringUtil.joinEnglishList(unfulfilledRequirements, "and")
                )
            }
        }

        /**
         * Check if prerequisite should be allowed with warning based on its rule class.
         * 
         * 
         * If yes, also issues said warning.
         */
        private fun checkRuleDependencyClassWarnings(
            prerequisite: ConfiguredTargetAndData, attribute: Attribute
        ): Boolean {
            if (!attribute.getAllowedRuleClassWarningPredicate().apply(prerequisite.getRuleClass())) {
                return false
            }

            val allowedRuleClasses: com.google.common.base.Predicate<String?>? =
                attribute.getAllowedRuleClassPredicate()
            reportBadPrerequisite(
                attribute,
                prerequisite,
                if (allowedRuleClasses === com.google.common.base.Predicates.alwaysTrue<String?>())
                    null
                else
                    "expected " + allowedRuleClasses,
                true
            )
            // prerequisite has a rule class allowed with a warning => accept, emitting a warning.
            return true
        }

        /**
         * Perform extra validation of prerequisites. Standard attribute-based dependencies are already
         * validated as part of [.createTargetMap].
         */
        private fun validateExtraPrerequisites(
            attributeChecks: Boolean, attributes: ConfiguredAttributeMapper
        ) {
            // These checks can fail when ConfigConditions.EMPTY are empty, resulting in noMatchError
            // accessing attributes without a default condition.
            // ConfigConditions.EMPTY is always true for non-rules:
            // https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/skyframe/ConfiguredTargetFunction.java;l=943;drc=720dc5fd640de692db129777c7c7c32924627c43
            // This can happen in BuildViewForTesting.getRuleContextForTesting as it specifies
            // ConfigConditions.EMPTY.
            if (attributeChecks && target is Rule) {
                checkAttributesNonEmpty(attributes)
                checkAttributesForDuplicateLabels(attributes)
            }

            // This conditionally checks visibility on config_setting rules based on
            // --config_setting_visibility_policy. This should be removed as soon as it's deemed safe
            // to unconditionally check visibility. See
            // https://github.com/bazelbuild/bazel/issues/12669.
            val configSettingVisibilityPolicy: ConfigSettingVisibilityPolicy? =
                target.getPackageMetadata().configSettingVisibilityPolicy()
            if (configSettingVisibilityPolicy !== ConfigSettingVisibilityPolicy.LEGACY_OFF) {
                // Validate config conditions.

                val configSettingAttr: Attribute = attributes.getAttributeDefinition("\$config_dependencies")
                for (condition in configConditions.asConfiguredTargets.values()) {
                    validateDirectPrerequisite(
                        configSettingAttr,  // Another nuance: when both --incompatible_enforce_config_setting_visibility and
                        // --incompatible_config_setting_private_default_visibility are disabled, both of
                        // these are ignored:
                        //
                        //  - visibility settings on a select() -> config_setting dep
                        //  - visibility settings on a select() -> alias -> config_setting dep chain
                        //
                        // In that scenario, both are ignored because the logic here that checks the
                        // select() -> ??? edge is completely skipped.
                        //
                        // When just --incompatible_enforce_config_setting_visibility is on, that means
                        // "enforce config_setting visibility with public default". That's a temporary state
                        // to support depot migration. In that case, we continue to ignore the alias'
                        // visibility in preference for the config_setting. So skip select() -> alias as
                        // before, but now enforce select() -> config_setting_the_alias_refers_to.
                        //
                        // When we also turn on --incompatible_config_setting_private_default_visibility, we
                        // expect full standard visibility compliance. In that case we directly evaluate the
                        // alias visibility, as is usual semantics. So two the following two edges are
                        // checked: 1: select() -> alias and 2: alias -> config_setting.
                        if (configSettingVisibilityPolicy === ConfigSettingVisibilityPolicy.DEFAULT_PUBLIC)
                            condition.fromConfiguredTargetNoCheck(
                                condition.getConfiguredTarget().getActual()
                            )
                        else
                            condition
                    )
                }
            }

            // Validate toolchains.
            if (toolchainContexts != null) {
                for (toolchainContext in toolchainContexts.contextMap().values()) {
                    for (prerequisite in toolchainContext.prerequisiteTargets()) {
                        validateDirectPrerequisite(TOOLCHAIN_ATTRIBUTE, prerequisite)
                    }
                }
            }
        }

        private fun validateDirectPrerequisite(
            attribute: Attribute, prerequisite: ConfiguredTargetAndData
        ) {
            validateDirectPrerequisiteType(prerequisite, attribute)
            validateDirectPrerequisiteFileTypes(prerequisite, attribute)
            if (attribute.performPrereqValidatorCheck()) {
                ruleClassProvider.getPrerequisiteValidator().validate(this, prerequisite, attribute)
            }
        }

        companion object {
            private fun badPrerequisiteMessage(
                prerequisite: ConfiguredTargetAndData, reason: String?, isWarning: Boolean
            ): String? {
                val msgReason = if (reason != null) " (" + reason + ")" else ""
                if (isWarning) {
                    return java.lang.String.format(
                        "%s is unexpected here%s; continuing anyway",
                        AliasProvider.Companion.describeTargetWithAliases(prerequisite, TargetMode.WITH_KIND), msgReason
                    )
                }
                return java.lang.String.format(
                    "%s is misplaced here%s",
                    AliasProvider.Companion.describeTargetWithAliases(prerequisite, TargetMode.WITH_KIND), msgReason
                )
            }

            /** Check if prerequisite should be allowed based on its rule class.  */
            private fun checkRuleDependencyClass(
                prerequisite: ConfiguredTargetAndData,
                attribute: Attribute,
                unfulfilledRequirements: MutableSet<String?>
            ): Boolean {
                val predicate: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    attribute.getAllowedRuleClassPredicate()
                if (predicate == com.google.common.base.Predicates.alwaysTrue<String?>()) {
                    // alwaysTrue is a special sentinel value. See
                    // RuleClass.Builder.RuleClassNamePredicate.unspecified.
                    return false
                }

                if (predicate.apply(prerequisite.getRuleClass())) {
                    // prerequisite has an allowed rule class => accept.
                    return true
                }
                // remember that the rule class that was not allowed;
                // but maybe prerequisite provides required providers? do not reject yet.
                unfulfilledRequirements.add(
                    com.google.devtools.build.lib.analysis.RuleContext.Builder.Companion.badPrerequisiteMessage(
                        prerequisite,
                        "expected " + predicate,
                        false
                    )
                )
                return false
            }

            /** Check if prerequisite should be allowed based on required providers on the attribute.  */
            private fun checkRuleDependencyMandatoryProviders(
                prerequisite: ConfiguredTargetAndData,
                attribute: Attribute,
                unfulfilledRequirements: MutableSet<String?>
            ): Boolean {
                val requiredProviders: RequiredProviders = attribute.getRequiredProviders()

                if (requiredProviders.acceptsAny()) {
                    // If no required providers specified, we do not know if we should accept.
                    return false
                }

                if (prerequisite.getConfiguredTarget().satisfies(requiredProviders)) {
                    return true
                }

                unfulfilledRequirements.add(
                    java.lang.String.format(
                        "'%s' does not have mandatory providers: %s",
                        prerequisite.getTargetLabel(),
                        prerequisite
                            .getConfiguredTarget()
                            .missingProviders(requiredProviders)
                            .getDescription()
                    )
                )

                return false
            }
        }
    }

    /** Helper class for reporting errors and warnings.  */
    private class ErrorReporter(
        env: AnalysisEnvironment?,
        rule: Rule,
        configuration: BuildConfigurationValue?,
        ruleClassNameForLogging: String?
    ) : EventHandlingErrorReporter(ruleClassNameForLogging, env), RuleErrorConsumer {
        private val rule: Rule
        private val configuration: BuildConfigurationValue?

        init {
            this.rule = rule
            this.configuration = configuration
        }

        override fun getMacroMessageAppendix(unusedAttrName: String?): String? {
            // TODO(b/141234726):  Historically this reported the location
            // of the rule attribute in the macro call (assuming no **kwargs),
            // but we no longer locations for individual attributes.
            // We should record the instantiation call stack in each rule
            // and report the position of its topmost frame here.
            return if (rule.isRuleCreatedInMacro())
                java.lang.String.format(
                    ". Since this rule was created by the macro '%s', the error might have been "
                            + "caused by the macro implementation",
                    getGeneratorFunction()
                )
            else
                ""
        }

        fun getGeneratorFunction(): String? {
            return rule.getAttr("generator_function") as String?
        }

        override fun getLabel(): Label {
            return rule.getLabel()
        }

        override fun getConfiguration(): BuildConfigurationValue? {
            return configuration
        }

        override fun getRuleLocation(): net.starlark.java.syntax.Location {
            return rule.getLocation()
        }
    }

    /**
     * Implementation of an error consumer which does not post any events, saves rule and attribute
     * errors for future consumption, and drops warnings.
     */
    class SuppressingErrorReporter : RuleErrorConsumer {
        private val errorMessages: MutableList<String?> = com.google.common.collect.Lists.newArrayList<String?>()

        public override fun ruleWarning(message: String?) {}

        public override fun ruleError(message: String?) {
            errorMessages.add(message)
        }

        public override fun attributeWarning(attrName: String?, message: String?) {}

        public override fun attributeError(attrName: String?, message: String?) {
            errorMessages.add(message)
        }

        public override fun hasErrors(): Boolean {
            return !errorMessages.isEmpty()
        }

        /** Returns the error message strings reported to this error consumer.  */
        fun getErrorMessages(): MutableList<String?> {
            return errorMessages
        }
    }

    companion object {
        const val TOOLCHAIN_ATTR_NAME: String = "\$toolchain"

        /** A fake attribute to use for toolchain-related validation errors.  */
        private val TOOLCHAIN_ATTRIBUTE: Attribute = Builder(TOOLCHAIN_ATTR_NAME, BuildType.LABEL_LIST).build()

        fun create(
            builder: Builder,
            ruleAttributes: AttributeMap?,
            targetsMap: com.google.common.collect.ImmutableListMultimap<DependencyKind?, ConfiguredTargetAndData?>,
            execGroupCollection: ExecGroupCollection
        ): RuleContext {
            val attrNameToTargets: ImmutableSortedKeyListMultimap.Builder<String?, ConfiguredTargetAndData?> =
                ImmutableSortedKeyListMultimap.builder()
            for (entry in targetsMap.asMap().entrySet()) {
                attrNameToTargets.putAll(entry.getKey().getAttribute().getName(), entry.getValue())
            }

            return RuleContext(
                builder,
                ruleAttributes,
                PrerequisitesCollection(
                    attrNameToTargets.build(),
                    ruleAttributes,
                    builder.getErrorConsumer(),
                    builder.getRule(),
                    builder.getRuleClassNameForLogging()
                ),
                execGroupCollection
            )
        }

        private fun getDirectPrerequisites(
            prerequisiteMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>
        ): com.google.common.collect.ImmutableSet<ConfiguredTargetAndData?> {
            return prerequisiteMap.entries().stream()
                .filter({ e -> e.getKey().getAttribute() == null })
                .map({ e -> e.getValue() })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
        }

        @com.google.common.annotations.VisibleForTesting
        fun createActionOwner(
            rule: Rule,
            aspectDescriptors: com.google.common.collect.ImmutableList<AspectDescriptor?>?,
            buildConfigurationValue: BuildConfigurationValue?,
            execProperties: com.google.common.collect.ImmutableMap<String?, String?>?,
            executionPlatform: PlatformInfo?
        ): ActionOwner {
            return ActionOwner.create(
                rule.getLabel(),
                rule.getLocation(),
                rule.getTargetKind(),
                buildConfigurationValue,
                executionPlatform,
                aspectDescriptors,
                execProperties
            )
        }

        // TODO(b/37567440): Remove when Starlark callers can be updated to get this from
        // CcToolchainProvider. We should use CcCommon.CC_TOOLCHAIN_ATTRIBUTE_NAME, but we didn't want to
        // pollute core with C++ specific constant.
        protected val DEFAULT_MAKE_VARIABLE_ATTRIBUTES: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "toolchains",
                ":cc_toolchain",
                "\$toolchains",
                "\$cc_toolchain"
            )
    }
}
