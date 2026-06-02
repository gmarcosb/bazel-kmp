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
package com.google.devtools.build.lib.analysis.util

import com.google.common.base.Preconditions
import com.google.common.collect.*
import com.google.common.eventbus.EventBus
import com.google.devtools.build.lib.clock.BlazeClock
import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild
import com.google.devtools.common.options.Options
import com.google.errorprone.annotations.CanIgnoreReturnValue
import com.google.errorprone.annotations.ForOverride
import org.junit.After
import java.util.Arrays
import java.util.Map
import java.util.function.Function

/**
 * Testing framework for tests of the analysis phase that uses the BuildView and LoadingPhaseRunner
 * APIs correctly (compared to [BuildViewTestCase]).
 * 
 * 
 * The intended usage pattern is to first call [.update] with the set of targets, and then
 * assert properties of the configured targets obtained from [.getConfiguredTarget].
 * 
 * 
 * This class intentionally does not inherit from [BuildViewTestCase]; BuildViewTestCase
 * abuses the BuildView API in ways that are incompatible with the goals of this test, i.e. the
 * convenience methods provided there wouldn't work here.
 */
abstract class AnalysisTestCase : FoundationTestCase() {
    /** All the flags that can be passed to [BuildView.update].  */
    enum class Flag {
        // The --keep_going flag.
        KEEP_GOING,

        // Flags for visibility to default to public.
        PUBLIC_VISIBILITY,

        // Flags for CPU to work (be set to k8) in test mode.
        CPU_K8,

        // Flags from TestConstants.PRODUCT_SPECIFIC_FLAGS.
        PRODUCT_SPECIFIC_FLAGS,
    }

    /** Helper class to make it easy to enable and disable flags.  */
    class FlagBuilder {
        private val flags: MutableSet<Flag?> = EnumSet.noneOf<Flag?>(Flag::class.java)

        @CanIgnoreReturnValue
        fun with(flag: Flag?): FlagBuilder {
            flags.add(flag)
            return this
        }

        fun contains(flag: Flag?): Boolean {
            return flags.contains(flag)
        }
    }

    protected var directories: BlazeDirectories? = null
    protected var mockToolsConfig: MockToolsConfig? = null

    protected var analysisMock: AnalysisMock? = null
    protected var buildOptions: BuildOptions? = null
    private var optionsParser: OptionsParser? = null
    protected var packageManager: PackageManager? = null
    private var buildView: BuildViewForTesting? = null
    protected val actionKeyContext: ActionKeyContext = ActionKeyContext()

    // Note that these configurations are virtual (they use only VFS)
    private var universeConfig: BuildConfigurationValue? = null
    private var execConfig: BuildConfigurationValue? = null

    private var analysisResult: AnalysisResult? = null
    protected var skyframeExecutor: SkyframeExecutor? = null
    protected var ruleClassProvider: ConfiguredRuleClassProvider? = null

    protected var workspaceStatusActionFactory: DummyWorkspaceStatusActionFactory? = null
    private var pkgLocator: PathPackageLocator? = null
    protected val delegatingSyscallCache: DelegatingSyscallCache = DelegatingSyscallCache()

    protected var moduleRoot: Path? = null
    protected var registry: FakeRegistry? = null

    @Before
    @Throws(Exception::class)
    fun createMocks() {
        delegatingSyscallCache.setDelegate(SyscallCache.NO_CACHE)
        analysisMock = getAnalysisMock()
        pkgLocator =
            PathPackageLocator(
                outputBase,
                ImmutableList.of<E?>(Root.fromPath(rootDirectory)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        directories =
            BlazeDirectories(
                ServerDirectories(rootDirectory, outputBase, outputBase),
                rootDirectory,
                analysisMock!!.getProductName()
            )
        workspaceStatusActionFactory = DummyWorkspaceStatusActionFactory()

        moduleRoot = scratch.dir("modules")
        registry = FakeRegistry.Companion.DEFAULT_FACTORY.newFakeRegistry(moduleRoot.getPathString())

        mockToolsConfig = MockToolsConfig(rootDirectory)
        analysisMock!!.setupMockToolsRepository(mockToolsConfig)
        analysisMock!!.setupMockClient(mockToolsConfig)

        useRuleClassProvider(analysisMock!!.createRuleClassProvider())
    }

    @After
    fun cleanupInterningPools() {
        skyframeExecutor.getEvaluator().cleanupInterningPools()
    }

    private fun createSkyframeExecutor(pkgFactory: PackageFactory?): SkyframeExecutor {
        return BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
            .setPkgFactory(pkgFactory)
            .setFileSystem(fileSystem)
            .setDirectories(directories)
            .setActionKeyContext(actionKeyContext)
            .setWorkspaceStatusActionFactory(workspaceStatusActionFactory)
            .setExtraSkyFunctions(analysisMock!!.getSkyFunctions(directories))
            .setSyscallCache(delegatingSyscallCache)
            .allowExternalRepositories(allowExternalRepositories())
            .build()
    }

    @ForOverride
    protected fun allowExternalRepositories(): Boolean {
        return false
    }

    /** Changes the rule class provider to be used for the loading and the analysis phase.  */
    @Throws(Exception::class)
    protected open fun useRuleClassProvider(ruleClassProvider: ConfiguredRuleClassProvider) {
        this.ruleClassProvider = ruleClassProvider
        val pkgFactory: PackageFactory? =
            analysisMock!!
                .getPackageFactoryBuilderForTesting(directories)
                .setExtraPrecomputeValues(
                    ImmutableList.builder<PrecomputedValue.Injected?>()
                        .addAll(analysisMock!!.getPrecomputedValues())
                        .add(
                            PrecomputedValue.injected(
                                ModuleFileFunction.REGISTRIES, ImmutableSet.of<E?>(registry.getUrl())
                            )
                        )
                        .build()
                )
                .build(ruleClassProvider, fileSystem)
        useConfiguration()
        skyframeExecutor = createSkyframeExecutor(pkgFactory)
        skyframeExecutor.setEventBus(EventBus())
        reinitializeSkyframeExecutor()
        packageManager = skyframeExecutor.getPackageManager()
        buildView = BuildViewForTesting(directories, ruleClassProvider, skyframeExecutor, null)
    }

    private fun reinitializeSkyframeExecutor() {
        SkyframeExecutorTestHelper.process(skyframeExecutor)
        val packageOptions: PackageOptions = Options.getDefaults<O>(PackageOptions::class.java)
        packageOptions.setShowLoadingProgress(true)
        packageOptions.setGlobbingThreads(3)
        val buildLanguageOptions: BuildLanguageOptions? = Options.getDefaults<O?>(BuildLanguageOptions::class.java)
        skyframeExecutor.preparePackageLoading(
            pkgLocator,
            packageOptions,
            buildLanguageOptions,
            UUID.randomUUID(),
            ImmutableMap.of<K?, V?>(),
            QuiescingExecutorsImpl.forTesting(),
            TimestampGranularityMonitor(BlazeClock.instance())
        )
        skyframeExecutor.setActionEnv(ImmutableMap.of<K?, V?>())
        skyframeExecutor.injectExtraPrecomputedValues(analysisMock!!.getPrecomputedValues())
        skyframeExecutor.injectExtraPrecomputedValues(
            ImmutableList.of<E?>(
                PrecomputedValue.injected(
                    ModuleFileFunction.REGISTRIES, ImmutableSet.of<E?>(registry.getUrl())
                )
            )
        )
    }

    /** Resets the SkyframeExecutor, as if a clean had been executed.  */
    protected fun cleanSkyframe() {
        skyframeExecutor.resetEvaluator()
        reinitializeSkyframeExecutor()
    }

    protected open fun getAnalysisMock(): AnalysisMock {
        return AnalysisMock.Companion.get()
    }

    /**
     * Sets exec and target configuration using the specified options, falling back to the default
     * options for unspecified ones, and recreates the build view.
     */
    @Throws(Exception::class)
    fun useConfiguration(vararg args: String?) {
        optionsParser =
            OptionsParser.builder()
                .optionsClasses(
                    Iterables.concat(
                        Arrays.asList<T?>(
                            ExecutionOptions::class.java,
                            PackageOptions::class.java,
                            BuildLanguageOptions::class.java,
                            BuildRequestOptions::class.java,
                            AnalysisOptions::class.java,
                            KeepGoingOption::class.java,
                            LoadingPhaseThreadsOption::class.java,
                            LoadingOptions::class.java
                        ),
                        ruleClassProvider.getFragmentRegistry().getOptionsClasses()
                    )
                )
                .skipStarlarkOptionPrefixes()
                .build()
        if (defaultFlags().contains(Flag.PUBLIC_VISIBILITY)) {
            optionsParser.parse("--default_visibility=public")
        }
        if (defaultFlags().contains(Flag.CPU_K8)) {
            optionsParser.parse("--cpu=k8", "--host_cpu=k8")
        }
        if (defaultFlags().contains(Flag.PRODUCT_SPECIFIC_FLAGS)) {
            optionsParser.parse(TestConstants.PRODUCT_SPECIFIC_FLAGS)
        }
        optionsParser.parse(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS)
        optionsParser.parse(*args)

        if (!optionsParser.getSkippedArgs().isEmpty()) {
            val done: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                StarlarkOptionsParser.builder()
                    .buildSettingLoader(
                        SkyframeExecutorTargetLoader(
                            skyframeExecutor, PathFragment.EMPTY_FRAGMENT, reporter
                        )
                    )
                    .nativeOptionsParser(optionsParser)
                    .build()
                    .parse()
            Preconditions.checkState(done)
        }

        buildOptions =
            BuildOptions.of(ruleClassProvider.getFragmentRegistry().getOptionsClasses(), optionsParser)
    }

    protected open fun defaultFlags(): FlagBuilder {
        return FlagBuilder()
            .with(Flag.PUBLIC_VISIBILITY)
            .with(Flag.CPU_K8)
            .with(Flag.PRODUCT_SPECIFIC_FLAGS)
    }

    protected fun getGeneratingAction(artifact: Artifact?): Action? {
        ensureUpdateWasCalled()
        val action: ActionAnalysisMetadata? = analysisResult.getActionGraph().getGeneratingAction(artifact)

        if (action != null) {
            Preconditions.checkState(
                action is Action, "%s is not a proper Action object", action.prettyPrint()
            )
            return action as Action
        } else {
            return null
        }
    }

    @get:Throws(InterruptedException::class)
    protected open val targetConfiguration: BuildConfigurationValue
        /**
         * Returns the target configuration for the most recent build, as created in Blaze's primary
         * configuration creation phase.
         */
        get() = universeConfig

    protected val execConfiguration: BuildConfigurationValue?
        get() = execConfig

    protected fun ensureUpdateWasCalled() {
        Preconditions.checkState(analysisResult != null, "You must run update() first!")
    }

    /** Update the BuildView: syncs the package cache; loads and analyzes the given labels.  */
    @Throws(Exception::class)
    protected fun update(
        eventBus: EventBus?,
        config: FlagBuilder,
        explicitTargetPatterns: ImmutableSet<Label?>?,
        aspects: ImmutableList<String?>?,
        aspectsParameters: ImmutableMap<String?, String?>?,
        vararg labels: String?
    ): AnalysisResult? {
        val flags = config.flags

        val loadingOptions: LoadingOptions? = optionsParser.getOptions<O?>(LoadingOptions::class.java)

        val viewOptions: AnalysisOptions? = optionsParser.getOptions<O?>(AnalysisOptions::class.java)
        // update --keep_going option if test requested it.
        val keepGoing = flags.contains(Flag.KEEP_GOING)
        val discardAnalysisCache: Boolean = viewOptions.getDiscardAnalysisCache()

        val packageOptions: PackageOptions? = optionsParser.getOptions<O?>(PackageOptions::class.java)
        val pathPackageLocator: PathPackageLocator? =
            PathPackageLocator.create(
                outputBase,
                packageOptions.getPackagePath(),
                reporter,
                rootDirectory.asFragment(),
                rootDirectory,
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        packageOptions.setShowLoadingProgress(true)
        packageOptions.setGlobbingThreads(7)

        val buildLanguageOptions: BuildLanguageOptions? =
            optionsParser.getOptions<O?>(BuildLanguageOptions::class.java)

        skyframeExecutor.preparePackageLoading(
            pathPackageLocator,
            packageOptions,
            buildLanguageOptions,
            UUID.randomUUID(),
            ImmutableMap.of<K?, V?>(),
            QuiescingExecutorsImpl.forTesting(),
            TimestampGranularityMonitor(BlazeClock.instance())
        )
        skyframeExecutor.setActionEnv(ImmutableMap.of<K?, V?>())
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory)
        )

        val loadingResult: TargetPatternPhaseValue? =
            skyframeExecutor.loadTargetPatternsWithFilters(
                reporter,
                ImmutableList.< E > copyOf < E ? > (labels),
                PathFragment.EMPTY_FRAGMENT,
                loadingOptions,
                LOADING_PHASE_THREADS,
                keepGoing,  /* determineTests= */
                false
            )

        analysisResult =
            buildView!!.update(
                loadingResult,
                buildOptions,
                explicitTargetPatterns,
                aspects,
                aspectsParameters,
                viewOptions,
                keepGoing,
                LOADING_PHASE_THREADS,
                AnalysisTestUtil.TOP_LEVEL_ARTIFACT_CONTEXT,
                reporter,
                eventBus
            )
        if (discardAnalysisCache) {
            buildView!!.clearAnalysisCache(
                analysisResult.getTargetsToBuild(), analysisResult.getAspectsMap().keySet()
            )
        }

        universeConfig = analysisResult.getConfiguration()
        scratch.overwriteFile("platform/BUILD", "platform(name = 'exec')")
        execConfig =
            skyframeExecutor.getConfiguration(
                reporter,
                AnalysisTestUtil.execOptions(universeConfig.getOptions(), skyframeExecutor, reporter),  /* keepGoing= */
                false
            )

        return analysisResult
    }

    @Throws(Exception::class)
    protected fun update(
        eventBus: EventBus?, config: FlagBuilder, aspects: ImmutableList<String?>?, vararg labels: String?
    ): AnalysisResult? {
        return update(
            eventBus,
            config,  /* explicitTargetPatterns= */
            ImmutableSet.of<Label?>(),
            aspects,  /* aspectsParameters= */
            ImmutableMap.of<String?, String?>(),
            *labels
        )
    }

    @Throws(Exception::class)
    protected fun update(eventBus: EventBus?, config: FlagBuilder, vararg labels: String?): AnalysisResult? {
        return update(eventBus, config,  /* aspects= */ImmutableList.of<String?>(), *labels)
    }

    @Throws(Exception::class)
    protected fun update(config: FlagBuilder, vararg labels: String?): AnalysisResult? {
        return update(EventBus(), config,  /* aspects= */ImmutableList.of<String?>(), *labels)
    }

    /** Update the BuildView: syncs the package cache; loads and analyzes the given labels.  */
    @Throws(Exception::class)
    protected fun update(vararg labels: String?): AnalysisResult? {
        return update(EventBus(), defaultFlags(),  /* aspects= */ImmutableList.of<String?>(), *labels)
    }

    @Throws(Exception::class)
    protected open fun update(aspects: ImmutableList<String?>?, vararg labels: String?): AnalysisResult? {
        return update(EventBus(), defaultFlags(), aspects, *labels)
    }

    @Throws(Exception::class)
    protected fun update(
        aspects: ImmutableList<String?>?,
        aspectsParameters: ImmutableMap<String?, String?>?,
        vararg labels: String?
    ): AnalysisResult? {
        return update(
            EventBus(),
            defaultFlags(),  /* explicitTargetPatterns= */
            ImmutableSet.of<Label?>(),
            aspects,
            aspectsParameters,
            *labels
        )
    }

    @Throws(InterruptedException::class)
    protected fun getConfiguredTargetAndTarget(label: String?): ConfiguredTargetAndData {
        return getConfiguredTargetAndTarget(label, this.targetConfiguration)
    }

    protected fun getConfiguredTargetAndTarget(
        label: String?, config: BuildConfigurationValue?
    ): ConfiguredTargetAndData {
        ensureUpdateWasCalled()
        val parsedLabel: Label?
        try {
            parsedLabel = Label.parseCanonical(label)
        } catch (e: LabelSyntaxException) {
            throw AssertionError(e)
        }
        try {
            return skyframeExecutor.getConfiguredTargetAndDataForTesting(reporter, parsedLabel, config)
        } catch (e: InterruptedException) {
            throw AssertionError(e)
        }
    }

    @Throws(InterruptedException::class)
    protected fun getTarget(label: String?): Target? {
        try {
            return SkyframeExecutorTestUtils.getExistingTarget(
                skyframeExecutor, Label.parseCanonical(label)
            )
        } catch (e: LabelSyntaxException) {
            throw AssertionError(e)
        }
    }

    protected fun getConfiguredTargetAndData(
        label: String?, configuration: BuildConfigurationValue?
    ): ConfiguredTargetAndData {
        ensureUpdateWasCalled()
        return getConfiguredTargetForSkyframe(label, configuration)
    }

    @Throws(InterruptedException::class)
    protected fun getConfiguredTargetAndData(label: String?): ConfiguredTargetAndData {
        return getConfiguredTargetAndData(label, this.targetConfiguration)
    }

    protected fun getConfiguredTarget(
        label: String?, configuration: BuildConfigurationValue?
    ): ConfiguredTarget? {
        val result: ConfiguredTargetAndData? = getConfiguredTargetAndData(label, configuration)
        return if (result == null) null else result.getConfiguredTarget()
    }

    /**
     * Returns the corresponding configured target, if it exists. Note that this will only return
     * anything useful after a call to update() with the same label.
     */
    @Throws(InterruptedException::class)
    protected fun getConfiguredTarget(label: String?): ConfiguredTarget? {
        return getConfiguredTarget(label, this.targetConfiguration)
    }

    private fun getConfiguredTargetForSkyframe(
        label: String?, configuration: BuildConfigurationValue?
    ): ConfiguredTargetAndData {
        val parsedLabel: Label?
        try {
            parsedLabel = Label.parseCanonical(label)
        } catch (e: LabelSyntaxException) {
            throw AssertionError(e)
        }
        try {
            return skyframeExecutor.getConfiguredTargetAndDataForTesting(
                reporter, parsedLabel, configuration
            )
        } catch (e: InterruptedException) {
            throw AssertionError(e)
        }
    }

    protected fun getConfiguration(ct: ConfiguredTarget): BuildConfigurationValue {
        return skyframeExecutor.getConfiguration(reporter, ct.getConfigurationKey())
    }

    /**
     * Returns the corresponding configured target, if it exists. Note that this will only return
     * anything useful after a call to update() with the same label. The label passed in must
     * represent an input file.
     */
    protected fun getInputFileConfiguredTarget(label: String?): InputFileConfiguredTarget? {
        return getConfiguredTarget(label, null) as InputFileConfiguredTarget?
    }

    protected fun hasErrors(configuredTarget: ConfiguredTarget?): Boolean {
        return buildView!!.hasErrors(configuredTarget)
    }

    @Throws(InterruptedException::class)
    protected fun getBinArtifact(packageRelativePath: String?, owner: ConfiguredTarget): Artifact {
        val label: Label = owner.getLabel()
        val actionLookupKey: ActionLookupKey? =
            ConfiguredTargetKey.builder()
                .setLabel(label)
                .setConfigurationKey(owner.getConfigurationKey())
                .build()
        val actionLookupValue: ActionLookupValue
        try {
            actionLookupValue =
                skyframeExecutor.getEvaluator().getExistingValue(actionLookupKey) as ActionLookupValue
        } catch (e: InterruptedException) {
            throw IllegalStateException(e)
        }
        val rootRelativePath: PathFragment? = label.getPackageFragment().getRelative(packageRelativePath)
        for (action in actionLookupValue.getActions()) {
            for (output in action.getOutputs()) {
                if (output.getRootRelativePath().equals(rootRelativePath)) {
                    return output
                }
            }
        }
        // Fall back: some tests don't actually need the right owner.
        return buildView!!
            .getArtifactFactory()
            .getDerivedArtifact(
                label.getPackageFragment().getRelative(packageRelativePath),
                this.targetConfiguration.getBinDirectory(label.getRepository()),
                ConfiguredTargetKey.fromConfiguredTarget(owner)
            )
    }

    protected val skyframeEvaluatedTargetKeys: MutableSet<ActionLookupKey>?
        get() = buildView!!.getSkyframeEvaluatedActionLookupKeyCountForTesting()

    protected fun assertNumberOfAnalyzedConfigurationsOfTargets(
        targetsWithCounts: MutableMap<String?, Int?>
    ) {
        val actualSet: ImmutableMultiset<Label?> =
            this.skyframeEvaluatedTargetKeys!!.stream()
                .filter { key: ActionLookupKey? -> key is ConfiguredTargetKey }
                .map<Any?>(ArtifactOwner::getLabel)
                .collect(ImmutableMultiset.toImmutableMultiset<Any?>())
        val expected: ImmutableMap<Label?, Int?> =
            targetsWithCounts.entries.stream()
                .collect(
                    ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                        Function { entry: Any? -> Label.parseCanonicalUnchecked(entry.getKey()) },
                        Function { Map.Entry.value })
                )
        val actual: ImmutableMap<Label?, Int?> =
            expected.keys.stream().collect(
                ImmutableMap.toImmutableMap<Label?, Label?, Int?>(
                    Function { label: Label? -> label },
                    Function { element: Label? -> actualSet.count(element) })
            )
        Truth.assertThat(actual).containsExactlyEntriesIn(expected)
    }

    protected val view: BuildViewForTesting
        get() = buildView!!

    protected val actionGraph: ActionGraph
        get() = skyframeExecutor.getActionGraph(reporter)

    protected fun getAnalysisResult(): AnalysisResult? {
        return analysisResult
    }

    protected fun clearAnalysisResult() {
        analysisResult = null
    }

    /**
     * Makes `rules` available in tests, in addition to all the rules available to Blaze at
     * running time (e.g., java_library).
     * 
     * 
     * Also see [AnalysisTestCase.setRulesAndAspectsAvailableInTests].
     */
    @Throws(Exception::class)
    protected fun setRulesAvailableInTests(vararg rules: RuleDefinition?) {
        // Not all of these aspects are needed for all tests, but it makes it simple to offer them all.
        setRulesAndAspectsAvailableInTests(
            ImmutableList.of<E?>(
                TestAspects.SIMPLE_ASPECT,
                TestAspects.PARAMETRIZED_DEFINITION_ASPECT,
                TestAspects.ASPECT_REQUIRING_PROVIDER,
                TestAspects.FALSE_ADVERTISEMENT_ASPECT,
                TestAspects.ALL_ATTRIBUTES_ASPECT,
                TestAspects.ALL_ATTRIBUTES_WITH_TOOL_ASPECT,
                TestAspects.BAR_PROVIDER_ASPECT,
                TestAspects.EXTRA_ATTRIBUTE_ASPECT,
                TestAspects.PACKAGE_GROUP_ATTRIBUTE_ASPECT,
                TestAspects.COMPUTED_ATTRIBUTE_ASPECT,
                TestAspects.FILE_PROVIDER_ASPECT,
                TestAspects.FOO_PROVIDER_ASPECT,
                TestAspects.ASPECT_REQUIRING_PROVIDER_SETS,
                TestAspects.WARNING_ASPECT,
                TestAspects.ERROR_ASPECT
            ),
            ImmutableList.copyOf<RuleDefinition?>(rules)
        )
    }

    /**
     * Makes `aspects` and `rules` available in tests, in addition to all the rules
     * available to Blaze at running time (e.g., java_library).
     */
    @Throws(Exception::class)
    protected fun setRulesAndAspectsAvailableInTests(
        aspects: Iterable<NativeAspectClass?>, rules: Iterable<RuleDefinition?>
    ) {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        for (aspect in aspects) {
            builder.addNativeAspectClass(aspect)
        }
        for (rule in rules) {
            builder.addRuleDefinition(rule)
        }

        useRuleClassProvider(builder.build())
        update()
    }

    /**
     * Retrieves Starlark provider from a configured target.
     * 
     * 
     * Assuming that the provider is defined in the same bzl file as the rule.
     */
    @Throws(Exception::class)
    protected fun getStarlarkProvider(target: ConfiguredTarget, providerSymbol: String?): StarlarkInfo? {
        return getStarlarkProvider(
            target,
            getTarget(target.getLabel().toString())
                .getAssociatedRule()
                .getRuleClassObject()
                .getRuleDefinitionEnvironmentLabel()
                .toString(),
            providerSymbol
        )
    }

    /** Retrieves Starlark provider from a configured target or aspect.  */
    @Throws(Exception::class)
    protected fun getStarlarkProvider(
        target: ProviderCollection, label: String?, providerSymbol: String?
    ): StarlarkInfo? {
        val key: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical(label)), providerSymbol)
        return target.get(key) as StarlarkInfo?
    }

    companion object {
        private const val LOADING_PHASE_THREADS = 20

        protected val internalTestExecutionMode: InternalTestExecutionMode
            get() = InternalTestExecutionMode.NORMAL
    }
}
