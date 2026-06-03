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
package com.google.devtools.build.lib.analysis.config


import com.google.devtools.build.lib.actions.ActionEnvironment

/**
 * Represents a collection of context information which may affect a build (for example: the target
 * platform for compilation, or whether or not debug tables are required). In fact, all
 * "environmental" information (e.g. from the tool's command-line, as opposed to the BUILD file)
 * that can affect the output of any build tool should be explicitly represented in the `BuildConfigurationValue` instance.
 * 
 * 
 * A single build may require building tools to run on a variety of platforms: when compiling a
 * server application for production, we must build the build tools (like compilers) to run on the
 * execution platform, but cross-compile the application for the production environment.
 * 
 * 
 * There is always at least one `BuildConfigurationValue` instance in any build: the one
 * representing the target platform. Additional instances may be created, in a cross-compilation
 * build, for example.
 * 
 * 
 * Instances of `BuildConfigurationValue` are canonical:
 * 
 * <pre>`c1.equals(c2) <=> c1==c2.`</pre>
 */
@AutoCodec
class BuildConfigurationValue
internal constructor(
    buildOptions: BuildOptions,
    mnemonic: String,
    siblingRepositoryLayout: Boolean,
    platformCpu: String?,  // Arguments below this are either server-global and constant or completely dependent values.
    workspaceName: String,
    directories: BlazeDirectories?,
    fragments: com.google.common.collect.ImmutableMap<java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?, com.google.devtools.build.lib.analysis.config.Fragment?>?,
    reservedActionMnemonics: com.google.common.collect.ImmutableSet<String?>?,
    actionEnvironment: ActionEnvironment
) : BuildConfigurationApi, SkyValue, BuildConfigurationInfo {
    /** Global state necessary to build a BuildConfiguration.  */
    interface GlobalStateProvider {
        /** Computes the default shell environment for actions from the command line options.  */
        fun getActionEnvironment(options: BuildOptions?): ActionEnvironment?

        fun getFragmentRegistry(): FragmentRegistry?

        fun getReservedActionMnemonics(): com.google.common.collect.ImmutableSet<String?>?

        fun getRunfilesPrefix(): String?
    }

    private val outputDirectories: OutputDirectories

    private val fragments: com.google.common.collect.ImmutableSortedMap<java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?, com.google.devtools.build.lib.analysis.config.Fragment?>

    private val starlarkVisibleFragments: com.google.common.collect.ImmutableMap<String?, java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?>
    private val workspaceName: String
    private val reservedActionMnemonics: com.google.common.collect.ImmutableSet<String?>?
    private val commandLineLimits: CommandLineLimits

    /**
     * The global "make variables" such as "$(TARGET_CPU)"; these get applied to all rules analyzed in
     * this configuration.
     */
    private val globalMakeEnv: com.google.common.collect.ImmutableMap<String?, String?>

    private val actionEnv: ActionEnvironment
    private val testEnv: ActionEnvironment

    private val buildOptions: BuildOptions
    private val options: CoreOptions?

    /** The cpu value based on the platform the configuration is built for.  */
    private val platformCpu: String?

    /**
     * If non-empty, this is appended to output directories as ST-[transitionDirectoryNameFragment].
     * The value is a hash of BuildOptions that have been affected by a Starlark transition.
     * 
     * 
     * See b/203470434 or #14023 for more information and planned behavior changes.
     */
    private val mnemonic: String

    private val commandLineBuildVariables: com.google.common.collect.ImmutableMap<String?, String?>

    /** Data for introspecting the options used by this configuration.  */
    private val buildOptionDetails: BuildOptionDetails

    private val siblingRepositoryLayout: Boolean

    private val defaultFeatures: FeatureSet

    @kotlin.concurrent.Volatile
    @Transient
    // lazily initialized
    private var buildEvent: BuildConfigurationEvent? = null

    /**
     * Validates the options for this BuildConfigurationValue. Issues warnings for the use of
     * deprecated options, and warnings or errors for any option settings that conflict.
     */
    fun reportInvalidOptions(reporter: EventHandler) {
        // Validate that --cpu has an allowed value. Since there is no CoreConfiguration, handle this
        // directly instead of using reportInvalidOptions.
        // TODO: blaze-configurability-team - Remove this when --cpu is fully deprecated.
        val coreOptions: CoreOptions? = getOptions().get<T?>(CoreOptions::class.java)
        if (!coreOptions.getAllowedCpuValues().isEmpty()) {
            if (!coreOptions.getAllowedCpuValues().contains(coreOptions.getCpu())) {
                reporter.handle(
                    Event.error(
                        java.lang.String.format(
                            "Invalid --cpu value \"%s\": allowed values are %s.",
                            coreOptions.getCpu(),
                            com.google.common.base.Joiner.on(", ").join(coreOptions.getAllowedCpuValues())
                        )
                    )
                )
            }
        }

        for (fragment in fragments.values()) {
            fragment.reportInvalidOptions(reporter, this.buildOptions)
        }
    }

    /**
     * Compute the test environment, which, at configuration level, is a pair consisting of the
     * statically set environment variables with their values and the set of environment variables to
     * be inherited from the client environment.
     */
    private fun setupTestEnvironment(): ActionEnvironment {
        if (!buildOptions.contains(TestOptions::class.java)) {
            // TestOptions have been trimmed.
            return ActionEnvironment.EMPTY
        }
        // Order doesn't matter here as ActionEnvironment sorts by key.
        val testEnv: MutableMap<String?, String?> = HashMap<String?, String?>()
        for (envVar in buildOptions.get<T?>(TestOptions::class.java).getTestEnvironment()) {
            when (envVar) {
                -> testEnv.put(name, value)
                -> testEnv.put(name, null)
                -> testEnv.remove(name)
            }
        }
        return ActionEnvironment.split(testEnv)
    }

    // Package-visible for serialization purposes.
    init {
        this.fragments =
            fragmentsInterner.intern(
                com.google.common.collect.ImmutableSortedMap.< Class <? extends Fragment >, Fragment>copyOf<java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?, com.google.devtools.build.lib.analysis.config.Fragment?>(fragments, FragmentClassSet.LEXICAL_FRAGMENT_SORTER))
        this.starlarkVisibleFragments = buildIndexOfStarlarkVisibleFragments()
        this.buildOptions = buildOptions
        this.mnemonic = mnemonic
        this.options = buildOptions.get<T?>(CoreOptions::class.java)
        this.outputDirectories =
            OutputDirectories(
                directories,
                options,
                buildOptions.get<T?>(PlatformOptions::class.java),
                mnemonic,
                workspaceName,
                siblingRepositoryLayout
            )
        this.workspaceName = workspaceName
        this.siblingRepositoryLayout = siblingRepositoryLayout

        // We can't use an ImmutableMap.Builder here; we need the ability to add entries with keys that
        // are already in the map so that the same define can be specified on the command line twice,
        // and ImmutableMap.Builder does not support that.
        commandLineBuildVariables =
            com.google.common.collect.ImmutableMap.copyOf<String?, String?>(options.getNormalizedCommandLineBuildVariables())

        this.actionEnv = actionEnvironment
        this.testEnv = setupTestEnvironment()
        this.buildOptionDetails =
            BuildOptionDetails.Companion.forOptions(
                buildOptions.getNativeOptions(), buildOptions.getStarlarkOptions()
            )

        this.platformCpu = platformCpu

        // These should be documented in the build encyclopedia.
        // TODO(configurability-team): Deprecate TARGET_CPU in favor of platforms.
        globalMakeEnv =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "TARGET_CPU",
                if (options.getIncompatibleTargetCpuFromPlatform()) platformCpu else options.getCpu(),
                "COMPILATION_MODE",
                options.getCompilationMode().toString(),
                "BINDIR",
                getBinDirectory(RepositoryName.MAIN).getExecPathString(),
                "GENDIR",
                getGenfilesDirectory(RepositoryName.MAIN).getExecPathString()
            )

        this.reservedActionMnemonics = reservedActionMnemonics
        this.commandLineLimits = CommandLineLimits(options.getMinParamFileSize())
        this.defaultFeatures = FeatureSet.Companion.parse(options.getDefaultFeatures())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is BuildConfigurationValue) {
            return false
        }
        // Only considering arguments that are non-dependent and non-server-global.
        return this.buildOptions == other.buildOptions
                && this.workspaceName == other.workspaceName
                && this.siblingRepositoryLayout == other.siblingRepositoryLayout && this.mnemonic == other.mnemonic
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(buildOptions, workspaceName, siblingRepositoryLayout, mnemonic)
    }

    private fun buildIndexOfStarlarkVisibleFragments(): com.google.common.collect.ImmutableMap<String?, java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?> {
        val builder: com.google.common.collect.ImmutableMap.Builder<String?, java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?> =
            com.google.common.collect.ImmutableMap.builder<String?, java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?>()

        for (fragmentClass in fragments.keySet()) {
            val module: StarlarkBuiltin? = StarlarkAnnotations.getStarlarkBuiltin(fragmentClass)
            if (module != null) {
                builder.put(module.name(), fragmentClass)
            }
        }
        return builder.buildOrThrow()
    }

    /**
     * Returns the [BuildConfigurationKey] for this configuration.
     * 
     * 
     * Note that this method does not apply a platform mapping. It is assumed that this
     * configuration was created with a platform mapping and thus its key does not need to be mapped
     * again.
     */
    fun getKey(): BuildConfigurationKey {
        return BuildConfigurationKey.create(buildOptions)
    }

    /** Retrieves the [BuildOptionDetails] containing data on this configuration's options.  */
    fun getBuildOptionDetails(): BuildOptionDetails {
        return buildOptionDetails
    }

    /** Returns the output directory for this build configuration.  */
    fun getOutputDirectory(repositoryName: RepositoryName?): ArtifactRoot? {
        return outputDirectories.getOutputDirectory(repositoryName)
    }

    @Deprecated("Use {@link #getBinDirectory} instead.")
    public override fun getBinDir(): ArtifactRoot? {
        return outputDirectories.getBinDirectory(RepositoryName.MAIN)
    }

    /**
     * Returns the bin directory for this build configuration.
     * 
     * 
     * TODO(kchodorow): This (and the other get*Directory functions) won't work with external
     * repositories without changes to how ArtifactFactory resolves derived roots. This is not an
     * issue right now because it only effects Blaze's include scanning (internal) and Bazel's
     * repositories (external) but will need to be fixed.
     * 
     */
    @Deprecated("Use {@code RuleContext#getBinDirectory} instead whenever possible.")
    fun getBinDirectory(repositoryName: RepositoryName?): ArtifactRoot? {
        return outputDirectories.getBinDirectory(repositoryName)
    }

    /**
     * Returns a relative path to the bin directory at execution time.
     * 
     */
    @Deprecated("Use {@code RuleContext#getBinFragment} instead whenever possible.")
    fun getBinFragment(repositoryName: RepositoryName?): PathFragment {
        return outputDirectories.getBinDirectory(repositoryName).getExecPath()
    }

    @Deprecated("Use {@link #getGenfilesDirectory} instead.")
    public override fun getGenfilesDir(): ArtifactRoot? {
        return outputDirectories.getGenfilesDirectory(RepositoryName.MAIN)
    }

    /**
     * Returns the genfiles directory for this build configuration.
     * 
     */
    @Deprecated("Use {@code RuleContext#getGenfilesDirectory} instead whenever possible.")
    fun getGenfilesDirectory(repositoryName: RepositoryName?): ArtifactRoot? {
        return outputDirectories.getGenfilesDirectory(repositoryName)
    }

    fun hasSeparateGenfilesDirectory(): Boolean {
        return !outputDirectories.mergeGenfilesDirectory()
    }

    @Throws(EvalException::class)
    public override fun hasSeparateGenfilesDirectoryForStarlark(thread: StarlarkThread?): Boolean {
        BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        return hasSeparateGenfilesDirectory()
    }

    /**
     * Returns the testlogs directory for this build configuration.
     * 
     * 
     * Use `RuleContext#getTestLogsDirectory` instead whenever possible.
     */
    fun getTestLogsDirectory(repositoryName: RepositoryName?): ArtifactRoot? {
        return outputDirectories.getTestLogsDirectory(repositoryName)
    }

    /**
     * Returns a relative path to the genfiles directory at execution time.
     * 
     */
    @Deprecated("Use {@code RuleContext#getGenfilesFragment} instead whenever possible.")
    fun getGenfilesFragment(repositoryName: RepositoryName?): PathFragment? {
        return outputDirectories.getGenfilesFragment(repositoryName)
    }

    /**
     * Returns the path separator for the host platform. This is basically the same as [ ][java.io.File.pathSeparator], except that that returns the value for this JVM, which may or may
     * not match the host platform. You should only use this when invoking tools that are known to use
     * the native path separator, i.e., the path separator for the machine that they run on.
     */
    public override fun getHostPathSeparator(): String? {
        return outputDirectories.getHostPathSeparator()
    }

    fun getWorkspaceName(): String {
        return workspaceName
    }

    override fun getMnemonic(): String? {
        return outputDirectories.getMnemonic()
    }

    /** Returns whether to use automatic exec groups.  */
    fun useAutoExecGroups(): Boolean {
        return options.getUseAutoExecGroups()
    }

    /**
     * Returns the name of the base output directory under which actions in this configuration write
     * their outputs.
     * 
     * 
     * This is the same as [.getMnemonic].
     */
    fun getOutputDirectoryName(): String? {
        return outputDirectories.getOutputDirName()
    }

    override fun toString(): String {
        return checksum()!!
    }

    public override fun debugPrint(out: PrintStream) {
        out.printf("BuildConfigurationValue: %s\n", this.checksum())
        out.printf("  %s\n", this.options)
    }

    fun getActionEnvironment(): ActionEnvironment {
        return actionEnv
    }

    fun isSiblingRepositoryLayout(): Boolean {
        return siblingRepositoryLayout
    }

    @Throws(EvalException::class)
    public override fun isSiblingRepositoryLayoutForStarlark(thread: StarlarkThread?): Boolean {
        BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        return isSiblingRepositoryLayout()
    }

    /**
     * Return the "fixed" part of the actions' environment variables.
     * 
     * 
     * An action's full set of environment variables consist of a "fixed" part and of a "variable"
     * part. The "fixed" variables are independent of the Bazel client's own environment, and are
     * returned by this function. The "variable" ones are inherited from the Bazel client's own
     * environment, and are returned by [.getVariableShellEnvironment].
     * 
     * 
     * Since values of the "fixed" variables are already known at analysis phase, it is returned
     * here as a map.
     */
    public override fun getLocalShellEnvironment(): com.google.common.collect.ImmutableMap<String?, String?> {
        return actionEnv.getFixedEnv()
    }

    /**
     * Return the "variable" part of the actions' environment variables.
     * 
     * 
     * An action's full set of environment variables consist of a "fixed" part and of a "variable"
     * part. The "fixed" variables are independent of the Bazel client's own environment, and are
     * returned by [.getLocalShellEnvironment]. The "variable" ones are inherited from the Bazel
     * client's own environment, and are returned by this function.
     * 
     * 
     * The values of the "variable" variables are tracked in Skyframe via the [ ][com.google.devtools.build.lib.skyframe.SkyFunctions.CLIENT_ENVIRONMENT_VARIABLE] skyfunction.
     * This method only returns the names of those variables to be inherited, if set in the client's
     * environment. (Variables where the name is not returned in this set should not be taken from the
     * client environment.)
     */
    @Deprecated("") // Use getActionEnvironment instead.
    fun getVariableShellEnvironment(): Iterable<String?> {
        return actionEnv.getInheritedEnv()
    }

    /**
     * Returns a regex-based instrumentation filter instance that used to match label names to
     * identify targets to be instrumented in the coverage mode.
     */
    fun getInstrumentationFilter(): RegexFilter? {
        return options.getInstrumentationFilter()
    }

    /**
     * Returns a boolean of whether to include targets created by *_test rules in the set of targets
     * matched by --instrumentation_filter. If this is false, all test targets are excluded from
     * instrumentation.
     */
    fun shouldInstrumentTestTargets(): Boolean {
        return options.getInstrumentTestTargets()
    }

    /** Returns a boolean of whether to collect code coverage for generated files or not.  */
    fun shouldCollectCodeCoverageForGeneratedFiles(): Boolean {
        return options.getCollectCodeCoverageForGeneratedFiles()
    }

    /**
     * Returns a new, unordered mapping of names to values of "Make" variables defined by this
     * configuration.
     * 
     * 
     * This does *not* include package-defined overrides (e.g. vardef) and so should not be used by
     * the build logic. This is used only for the 'info' command.
     * 
     * 
     * Command-line definitions of make environments override variables defined by `Fragment.addGlobalMakeVariables()`.
     */
    fun getMakeEnvironment(): com.google.common.collect.ImmutableMap<String?, String?> {
        val makeEnvironment: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
            com.google.common.collect.ImmutableMap.builder<String?, String?>()
        makeEnvironment.putAll(globalMakeEnv)
        makeEnvironment.putAll(commandLineBuildVariables)
        return makeEnvironment.buildKeepingLast()
    }

    /**
     * Returns a new, unordered mapping of names that are set through the command lines. (Fragments,
     * in particular the Google C++ support, can set variables through the command line.)
     */
    fun getCommandLineBuildVariables(): com.google.common.collect.ImmutableMap<String?, String?> {
        return commandLineBuildVariables
    }

    /** Returns the global defaults for this configuration for the Make environment.  */
    fun getGlobalMakeEnvironment(): com.google.common.collect.ImmutableMap<String?, String?> {
        return globalMakeEnv
    }

    /**
     * Returns the default value for the specified "Make" variable for this configuration. Returns
     * null if no value was found.
     */
    fun getMakeVariableDefault(`var`: String?): String? {
        return globalMakeEnv.get(`var`)
    }

    /** Returns a configuration fragment instances of the given class.  */
    fun <T : com.google.devtools.build.lib.analysis.config.Fragment?> getFragment(clazz: java.lang.Class<T?>): T? {
        return clazz.cast(fragments.get(clazz))
    }

    /** Return all the configuration fragments.  */
    fun getFragments(): com.google.common.collect.ImmutableSortedMap<java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?, com.google.devtools.build.lib.analysis.config.Fragment?> {
        return fragments
    }

    /** Returns true if the requested configuration fragment is present.  */
    fun <T : com.google.devtools.build.lib.analysis.config.Fragment?> hasFragment(clazz: java.lang.Class<T?>): Boolean {
        return getFragment<T?>(clazz) != null
    }

    /** Returns true if all requested configuration fragment are present (this may be slow).  */
    fun hasAllFragments(fragmentClasses: MutableSet<java.lang.Class<*>>): Boolean {
        for (fragmentClass in fragmentClasses) {
            if (!hasFragment<com.google.devtools.build.lib.analysis.config.Fragment?>(
                    fragmentClass.asSubclass<com.google.devtools.build.lib.analysis.config.Fragment?>(
                        com.google.devtools.build.lib.analysis.config.Fragment::class.java
                    )
                )
            ) {
                return false
            }
        }
        return true
    }

    fun getDirectories(): BlazeDirectories? {
        return outputDirectories.getDirectories()
    }

    fun platformCpu(): String? {
        return platformCpu
    }

    /** Returns true if non-functional build stamps are enabled.  */
    fun stampBinaries(): Boolean {
        return options.getStampBinaries()
    }

    @Throws(EvalException::class)
    public override fun stampBinariesForStarlark(thread: StarlarkThread?): Boolean {
        BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        return stampBinaries()
    }

    /** Returns true if extended sanity checks should be enabled.  */
    fun extendedSanityChecks(): Boolean {
        return options.getExtendedSanityChecks()
    }

    /** Returns true if we are building runfiles manifests for this configuration.  */
    fun buildRunfileManifests(): Boolean {
        return options.getBuildRunfileManifests()
    }

    /** Returns true if we are building runfile links for this configuration.  */
    fun buildRunfileLinks(): Boolean {
        return options.getBuildRunfileManifests() && options.getBuildRunfileLinks()
    }

    /**
     * Returns true if Runfiles should merge in FilesToBuild from deps when collecting data runfiles.
     */
    fun alwaysIncludeFilesToBuildInData(): Boolean {
        return options.getAlwaysIncludeFilesToBuildInData()
    }

    /**
     * Returns user-specified test environment variables and their values, as set by the --test_env
     * options.
     */
    public override fun getTestEnv(): com.google.common.collect.ImmutableMap<String?, String?> {
        return testEnv.getFixedEnv()
    }

    /**
     * Returns user-specified test environment variables and their values, as set by the `--test_env` options. It is incomplete in that it is not a superset of the [ ][.getActionEnvironment], but both have to be applied, with this one being applied after the
     * other, such that `--test_env` settings can override `--action_env` settings.
     */
    // TODO(ulfjack): Just return the merged action and test action environment here?
    fun getTestActionEnvironment(): ActionEnvironment {
        return testEnv
    }

    override fun getCommandLineLimits(): CommandLineLimits {
        return commandLineLimits
    }

    public override fun isCodeCoverageEnabled(): Boolean {
        return options.getCollectCodeCoverage()
    }

    public override fun getShortId(): String {
        return buildOptions.shortId()
    }

    fun getRunUnder(): RunUnder? {
        return options.getRunUnder()
    }

    /** Should the `--run_under` be configured in the exec configuration?  */
    fun runUnderExecConfigForTests(): Boolean {
        return options.getBazelTestExecRunUnder()
    }

    /** Returns true if this is an execution configuration.  */
    fun isExecConfiguration(): Boolean {
        return options.getIsExec()
    }

    override fun isToolConfiguration(): Boolean {
        return isExecConfiguration()
    }

    fun checkVisibility(): Boolean {
        return options.getCheckVisibility()
    }

    fun enforceTransitiveVisibility(): Boolean {
        return options.getEnforceTransitiveVisibility()
    }

    fun verboseVisibilityErrors(): Boolean {
        return options.getVerboseVisibilityErrors()
    }

    fun checkTestonlyForOutputFiles(): Boolean {
        return options.getCheckTestonlyForOutputFiles()
    }

    fun checkLicenses(): Boolean {
        return options.getCheckLicenses()
    }

    fun enforceConstraints(): Boolean {
        return options.getEnforceConstraints()
    }

    fun allowAnalysisFailures(): Boolean {
        return options.getAllowAnalysisFailures()
    }

    fun evaluatingForAnalysisTest(): Boolean {
        return options.getEvaluatingForAnalysisTest()
    }

    fun analysisTestingDepsLimit(): Int {
        return options.getAnalysisTestingDepsLimit()
    }

    fun getActionListeners(): MutableList<Label?>? {
        return options.getActionListeners()
    }

    fun allowUnresolvedSymlinks(): Boolean {
        return options.getAllowUnresolvedSymlinks()
    }

    fun allowMapDirectory(): Boolean {
        return options.getAllowMapDirectory()
    }

    /** Returns compilation mode.  */
    fun getCompilationMode(): CompilationMode? {
        return options.getCompilationMode()
    }

    override fun checksum(): String? {
        return buildOptions.checksum()
    }

    /**
     * Returns a user-friendly short configuration identifier.
     * 
     * 
     * See [BuildOptions.shortId] for details.
     */
    fun shortId(): String {
        return buildOptions.shortId()
    }

    /** Returns a copy of the build configuration options for this configuration.  */
    fun cloneOptions(): BuildOptions {
        return buildOptions.clone()
    }

    /**
     * Returns the actual options reference used by this configuration.
     * 
     * 
     * **Be very careful using this method.** Options classes are mutable - no caller should
     * ever call this method if there's any change the reference might be written to. This method only
     * exists because [.cloneOptions] can be expensive when applied to every edge in a
     * dependency graph.
     * 
     * 
     * Do not use this method without careful review with other Bazel developers.
     */
    fun getOptions(): BuildOptions {
        return buildOptions
    }

    fun getCpu(): String? {
        return options.getCpu()
    }

    @com.google.common.annotations.VisibleForTesting
    fun getHostCpu(): String? {
        return options.getHostCpu()
    }

    /**
     * Describes whether to create runfile symlink trees.
     * 
     * 
     * May be overridden if an [com.google.devtools.build.lib.vfs.OutputService] capable of
     * creating symlink trees is available.
     */
    enum class RunfileSymlinksMode {
        SKIP,
        CREATE
    }

    fun getRunfileSymlinksMode(): RunfileSymlinksMode {
        return getRunfileSymlinksMode(options)
    }

    @Throws(EvalException::class)
    public override fun runfilesEnabledForStarlark(thread: StarlarkThread?): Boolean {
        BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        return runfilesEnabled()
    }

    fun remotableSourceManifestActions(): Boolean {
        return options.getRemotableSourceManifestActions()
    }

    /**
     * Returns a modified copy of `executionInfo` if any `executionInfoModifiers` apply to
     * the given `mnemonic`. Otherwise returns `executionInfo` unchanged.
     */
    fun modifiedExecutionInfo(
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>, mnemonic: String?
    ): com.google.common.collect.ImmutableMap<String?, String?>? {
        if (!ExecutionInfoModifier.Companion.matches(
                options.getExecutionInfoModifier(), options.getAdditiveModifyExecutionInfo(), mnemonic
            )
        ) {
            return executionInfo
        }
        val mutableCopy: MutableMap<String?, String?> = HashMap<String?, String?>(executionInfo)
        modifyExecutionInfo(mutableCopy, mnemonic)
        return com.google.common.collect.ImmutableSortedMap.copyOf<String?, String?>(mutableCopy)
    }

    /** Applies `executionInfoModifiers` to the given `executionInfo`.  */
    fun modifyExecutionInfo(executionInfo: MutableMap<String?, String?>?, mnemonic: String?) {
        ExecutionInfoModifier.Companion.apply(
            options.getExecutionInfoModifier(),
            options.getAdditiveModifyExecutionInfo(),
            mnemonic,
            executionInfo
        )
    }

    /** Returns the list of default features used for all packages.  */
    fun getDefaultFeatures(): FeatureSet {
        return defaultFeatures
    }

    @Throws(EvalException::class)
    public override fun getDisabledFeatures(thread: StarlarkThread?): StarlarkSet<String?> {
        BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        return StarlarkSet.immutableCopyOf(getDefaultFeatures().off)
    }

    /**
     * Returns the "top-level" environment space, i.e. the set of environments all top-level targets
     * must be compatible with. An empty value implies no restrictions.
     */
    fun getTargetEnvironments(): MutableList<Label?>? {
        return options.getTargetEnvironments()
    }

    fun getStarlarkFragmentByName(name: String?): java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>? {
        return starlarkVisibleFragments.get(name)
    }

    fun getStarlarkFragmentNames(): com.google.common.collect.ImmutableCollection<String?> {
        return starlarkVisibleFragments.keySet()
    }

    fun getEventId(): BuildEventId {
        return BuildEventIdUtil.configurationId(checksum())
    }

    override fun toBuildEvent(): BuildConfigurationEvent? {
        if (buildEvent == null) {
            synchronized(this) {
                if (buildEvent == null) {
                    buildEvent = createBuildEvent()
                }
            }
        }
        return buildEvent
    }

    private fun createBuildEvent(): BuildConfigurationEvent {
        var cpu = getCpu()
        if (options.getIncompatibleBepCpuFromPlatform()) {
            cpu = platformCpu
        }
        val eventId: BuildEventId = getEventId()
        val builder: BuildEventStreamProtos.BuildEvent.Builder =
            BuildEventStreamProtos.BuildEvent.newBuilder()
        builder
            .setId(eventId)
            .setConfiguration(
                BuildEventStreamProtos.Configuration.newBuilder()
                    .setMnemonic(getMnemonic())
                    .setPlatformName(cpu)
                    .putAllMakeVariable(getMakeEnvironment())
                    .setCpu(cpu)
                    .setIsTool(isToolConfiguration())
                    .build()
            )
        return BuildConfigurationEvent(eventId, builder.build())
    }

    fun getReservedActionMnemonics(): com.google.common.collect.ImmutableSet<String?>? {
        return reservedActionMnemonics
    }

    companion object {
        private val fragmentsInterner: com.google.common.collect.Interner<com.google.common.collect.ImmutableSortedMap<java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?, com.google.devtools.build.lib.analysis.config.Fragment?>> =
            BlazeInterners.newWeakInterner()

        // Only BuildConfigurationFunction should instantiate this.
        @Throws(InvalidConfigurationException::class)
        fun create(
            buildOptions: BuildOptions,
            baselineOptions: BuildOptions?,
            siblingRepositoryLayout: Boolean,
            platformCpu: String?,  // Arguments below this are server-global.
            directories: BlazeDirectories?,
            globalProvider: GlobalStateProvider,
            fragmentFactory: FragmentFactory
        ): BuildConfigurationValue {
            val fragmentClasses: FragmentClassSet =
                if (buildOptions.hasNoConfig())
                    FragmentClassSet.of(com.google.common.collect.ImmutableSet.of<E?>())
                else
                    globalProvider.getFragmentRegistry().getAllFragments()
            val fragments: com.google.common.collect.ImmutableSortedMap<java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?, com.google.devtools.build.lib.analysis.config.Fragment?> =
                getConfigurationFragments(buildOptions, fragmentClasses, fragmentFactory)

            val mnemonic: String =
                OutputPathMnemonicComputer.computeMnemonic(buildOptions, baselineOptions, fragments)

            return BuildConfigurationValue(
                buildOptions,
                mnemonic,
                siblingRepositoryLayout,
                platformCpu,
                globalProvider.getRunfilesPrefix()!!,
                directories,
                fragments,
                globalProvider.getReservedActionMnemonics(),
                globalProvider.getActionEnvironment(buildOptions)
            )
        }

        // TODO(blaze-configurability-team): Ideally tests use the above create; however,
        //   ConfigurationTestCase most just checks equality constraints and this wants to directly
        //   fiddle with the mnemonic (and supplying a baselineOptions would be somewhat heavy).
        @com.google.common.annotations.VisibleForTesting
        @Throws(InvalidConfigurationException::class)
        fun createForTesting(
            buildOptions: BuildOptions,
            mnemonic: String,
            siblingRepositoryLayout: Boolean,  // Arguments below this are server-global.
            directories: BlazeDirectories?,
            globalProvider: GlobalStateProvider,
            fragmentFactory: FragmentFactory
        ): BuildConfigurationValue {
            val fragmentClasses: FragmentClassSet =
                if (buildOptions.hasNoConfig())
                    FragmentClassSet.of(com.google.common.collect.ImmutableSet.of<E?>())
                else
                    globalProvider.getFragmentRegistry().getAllFragments()
            val fragments: com.google.common.collect.ImmutableSortedMap<java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?, com.google.devtools.build.lib.analysis.config.Fragment?> =
                getConfigurationFragments(buildOptions, fragmentClasses, fragmentFactory)

            return BuildConfigurationValue(
                buildOptions,
                mnemonic,
                siblingRepositoryLayout,
                "",
                globalProvider.getRunfilesPrefix()!!,
                directories,
                fragments,
                globalProvider.getReservedActionMnemonics(),
                globalProvider.getActionEnvironment(buildOptions)
            )
        }

        @Throws(InvalidConfigurationException::class)
        private fun getConfigurationFragments(
            buildOptions: BuildOptions?, fragmentClasses: FragmentClassSet, fragmentFactory: FragmentFactory
        ): com.google.common.collect.ImmutableSortedMap<java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?, com.google.devtools.build.lib.analysis.config.Fragment?> {
            val fragments: com.google.common.collect.ImmutableSortedMap.Builder<java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?, com.google.devtools.build.lib.analysis.config.Fragment?> =
                com.google.common.collect.ImmutableSortedMap.orderedBy<java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?, com.google.devtools.build.lib.analysis.config.Fragment?>(
                    FragmentClassSet.LEXICAL_FRAGMENT_SORTER
                )
            for (fragmentClass in fragmentClasses) {
                val fragment: com.google.devtools.build.lib.analysis.config.Fragment? =
                    fragmentFactory.createFragment(buildOptions, fragmentClass)
                if (fragment != null) {
                    fragments.put(fragmentClass, fragment)
                }
            }
            return fragments.buildOrThrow()
        }

        @com.google.common.annotations.VisibleForTesting
        fun getRunfileSymlinksMode(options: CoreOptions): RunfileSymlinksMode {
            // TODO(buchgr): Revisit naming and functionality of this flag. See #9248 for details.
            if (options.getEnableRunfiles() === TriState.YES
                || (options.getEnableRunfiles() === TriState.AUTO && OS.getCurrent() !== OS.WINDOWS)
            ) {
                return RunfileSymlinksMode.CREATE
            }
            return RunfileSymlinksMode.SKIP
        }

        @kotlin.jvm.JvmOverloads
        fun runfilesEnabled(options: CoreOptions = this.options): Boolean {
            return getRunfileSymlinksMode(options) == RunfileSymlinksMode.CREATE
        }

        fun configurationIdMessage(
            configuration: BuildConfigurationValue?
        ): BuildEventId.ConfigurationId {
            if (configuration == null) {
                return BuildEventIdUtil.nullConfigurationIdMessage()
            }
            return BuildEventIdUtil.configurationIdMessage(configuration.checksum())
        }

        fun configurationId(configuration: BuildConfigurationValue?): BuildEventId {
            if (configuration == null) {
                return BuildEventIdUtil.nullConfigurationId()
            }
            return configuration.getEventId()
        }

        fun buildEvent(configuration: BuildConfigurationValue?): BuildEvent? {
            return if (configuration == null) NullConfiguration.INSTANCE else configuration.toBuildEvent()
        }
    }
}
