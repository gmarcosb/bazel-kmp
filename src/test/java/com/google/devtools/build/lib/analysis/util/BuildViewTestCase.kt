// Copyright 2019 The Bazel Authors. All rights reserved.
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
import com.google.common.base.Splitter
import com.google.common.collect.*
import com.google.common.eventbus.EventBus
import com.google.devtools.build.lib.actions.util.DummyExecutor
import com.google.devtools.build.lib.clock.BlazeClock
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache
import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild
import com.google.devtools.build.lib.util.StringUtil
import com.google.devtools.build.runfiles.Runfiles
import com.google.devtools.common.options.Options
import com.google.errorprone.annotations.CanIgnoreReturnValue
import com.google.errorprone.annotations.ForOverride
import net.starlark.java.eval.EvalException
import org.junit.After
import org.junit.Assert
import org.junit.function.ThrowingRunnable
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.function.Predicate
import java.util.regex.Pattern
import kotlin.Any
import kotlin.Array
import kotlin.AssertionError
import kotlin.Boolean
import kotlin.Exception
import kotlin.IllegalArgumentException
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.NoSuchElementException
import kotlin.RuntimeException
import kotlin.String
import kotlin.UnsupportedOperationException
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableCollection
import kotlin.collections.MutableIterator
import kotlin.collections.MutableList
import kotlin.collections.MutableSet
import kotlin.plus
import kotlin.toString

/** Common test code that creates a BuildView instance.  */
abstract class BuildViewTestCase : FoundationTestCase() {
    protected var analysisMock: AnalysisMock? = null
    protected var ruleClassProvider: ConfiguredRuleClassProvider? = null
    protected var view: BuildViewForTesting? = null

    protected var skyframeExecutor: SequencedSkyframeExecutor? = null

    protected var tsgm: TimestampGranularityMonitor? = null
    protected var directories: BlazeDirectories? = null
    protected var actionKeyContext: ActionKeyContext? = null

    protected var moduleRoot: Path? = null
    protected var registry: FakeRegistry? = null

    // Note that these configurations are virtual (they use only VFS)
    protected var targetConfig: BuildConfigurationValue? = null // "target" or "build" config
    protected var execConfig: BuildConfigurationValue? = null
    private var configurationArgs: ImmutableList<String?>? = null

    private var packageOptions: PackageOptions? = null
    private var buildLanguageOptions: BuildLanguageOptions? = null
    protected var pkgFactory: PackageFactory? = null

    protected var mockToolsConfig: MockToolsConfig? = null

    protected var workspaceStatusActionFactory: WorkspaceStatusAction.Factory? = null

    private var mutableActionGraph: MutableActionGraph? = null

    private var customLoadingOptions: LoadingOptions? = null
    protected var targetConfigKey: BuildConfigurationKey? = null

    private var actionLogBufferPathGenerator: ActionLogBufferPathGenerator? = null

    private var inliningBzlLoadFunction: BzlLoadFunction? = null

    @After
    fun cleanupInterningPools() {
        skyframeExecutor.getEvaluator().cleanupInterningPools()
    }

    @Before
    @Throws(Exception::class)
    open fun initializeSkyframeExecutor() {
        initializeSkyframeExecutor( /* doPackageLoadingChecks= */true)
    }

    @Throws(Exception::class)
    fun initializeSkyframeExecutor(
        doPackageLoadingChecks: Boolean, diffAwarenessFactories: ImmutableList<DiffAwareness.Factory?>?
    ) {
        initializeSkyframeExecutor(
            doPackageLoadingChecks, diffAwarenessFactories,  /* globUnderSingleDep= */true
        )
    }

    /**
     * Only [com.google.devtools.build.lib.skyframe.PackageFunctionTest] still covers testing
     * Skyframe Hybrid globbing by passing in the test parameter globUnderSingleDep.
     * 
     * 
     * All other tests adopt GLOBS strategy by setting `globUnderSingleDep` to `true`.
     */
    @kotlin.jvm.JvmOverloads
    @Throws(Exception::class)
    fun initializeSkyframeExecutor(
        doPackageLoadingChecks: Boolean,
        diffAwarenessFactories: ImmutableList<DiffAwareness.Factory?>? = ImmutableList.of<DiffAwareness.Factory?>(),
        globUnderSingleDep: Boolean = true
    ) {
        analysisMock = getAnalysisMock()
        directories =
            BlazeDirectories(
                ServerDirectories(rootDirectory, outputBase, outputBase),
                rootDirectory,
                analysisMock!!.getProductName()
            )
        moduleRoot = scratch.dir("modules")
        registry = FakeRegistry.Companion.DEFAULT_FACTORY.newFakeRegistry(moduleRoot.getPathString())

        actionKeyContext = ActionKeyContext()
        mockToolsConfig = MockToolsConfig(rootDirectory, false)
        analysisMock!!.setupMockToolsRepository(mockToolsConfig)
        initializeMockClient()

        packageOptions = parsePackageOptions()
        buildLanguageOptions = parseBuildLanguageOptions()
        workspaceStatusActionFactory = DummyWorkspaceStatusActionFactory()
        mutableActionGraph = MapBasedActionGraph(actionKeyContext)
        ruleClassProvider = createRuleClassProvider()
        this.outputPath.createDirectoryAndParents()
        val extraPrecomputedValues: ImmutableList<PrecomputedValue.Injected?> =
            ImmutableList.builder<PrecomputedValue.Injected?>()
                .addAll(analysisMock!!.getPrecomputedValues())
                .add(
                    PrecomputedValue.injected(
                        ModuleFileFunction.REGISTRIES, ImmutableSet.of<E?>(registry.getUrl())
                    )
                )
                .addAll(extraPrecomputedValues())
                .build()
        val pkgFactoryBuilder: PackageFactory.BuilderForTesting =
            analysisMock!!
                .getPackageFactoryBuilderForTesting(directories)
                .setExtraPrecomputeValues(extraPrecomputedValues)
                .setPackageValidator(this.packageValidator)
                .setPackageOverheadEstimator(this.packageOverheadEstimator)
        if (!doPackageLoadingChecks) {
            pkgFactoryBuilder.disableChecks()
        }
        pkgFactory = pkgFactoryBuilder.build(ruleClassProvider, fileSystem)
        tsgm = TimestampGranularityMonitor(BlazeClock.instance())
        if (skyframeExecutor != null) {
            cleanupInterningPools()
        }
        skyframeExecutor =
            BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
                .setPkgFactory(pkgFactory)
                .setFileSystem(fileSystem)
                .setDirectories(directories)
                .setActionKeyContext(actionKeyContext)
                .setWorkspaceStatusActionFactory(workspaceStatusActionFactory)
                .setExtraSkyFunctions(analysisMock!!.getSkyFunctions(directories))
                .setSyscallCache(SyscallCache.NO_CACHE)
                .setDiffAwarenessFactories(diffAwarenessFactories)
                .allowExternalRepositories(allowExternalRepositories())
                .setGlobUnderSingleDep(globUnderSingleDep)
                .build()
        if (usesInliningBzlLoadFunction()) {
            injectInliningBzlLoadFunction(skyframeExecutor, ruleClassProvider, directories)
        } else {
            // As of 05/21/2024, SerializationCheckingGraph does not deserialize analysis phase objects
            // from inline bzl correctly.
            //
            // The SerializationCheckingGraph assumes that objects that are exported from a given .bzl
            // file can be looked up later as a global symbol in the corresponding BzlLoadValue and that
            // the BzlLoadValue is present in Skyframe. This isn't true when .bzl inlining is used.
            SkyframeExecutorTestHelper.process(skyframeExecutor)
        }
        skyframeExecutor.injectExtraPrecomputedValues(extraPrecomputedValues)
        packageOptions.setShowLoadingProgress(true)
        packageOptions.setGlobbingThreads(7)
        skyframeExecutor.preparePackageLoading(
            createPackageLocator(),
            packageOptions,
            buildLanguageOptions,
            UUID.randomUUID(),
            ImmutableMap.of<K?, V?>(),
            QuiescingExecutorsImpl.forTesting(),
            tsgm
        )
        skyframeExecutor.setActionEnv(ImmutableMap.of<K?, V?>())
        useConfiguration()
        setUpSkyframe()
        this.actionLogBufferPathGenerator =
            ActionLogBufferPathGenerator(directories.getActionTempsDirectory(this.execRoot))
    }

    protected fun createPackageLocator(): PathPackageLocator {
        return PathPackageLocator(
            outputBase, ImmutableList.of<E?>(root), BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
        )
    }

    @ForOverride
    protected open fun allowExternalRepositories(): Boolean {
        return false
    }

    private fun injectInliningBzlLoadFunction(
        skyframeExecutor: SkyframeExecutor,
        ruleClassProvider: RuleClassProvider?,
        directories: BlazeDirectories?
    ) {
        val skyFunctions: ImmutableMap<SkyFunctionName?, SkyFunction?> =
            (skyframeExecutor.getEvaluator() as InMemoryMemoizingEvaluator).getSkyFunctionsForTesting()
        inliningBzlLoadFunction =
            BzlLoadFunction.createForInlining(
                ruleClassProvider,
                directories,  // Use a cache size of 2 for testing to balance coverage for where loads are present and
                // aren't present in the cache.
                /* bzlLoadValueCacheSize= */
                2
            )
        // The builtins should be empty since this was just created but reset it anyway to be sure.
        inliningBzlLoadFunction.resetInliningCacheAndBuiltinsForTesting()
        // This doesn't override the BZL_LOAD -> BzlLoadFunction mapping, but nothing besides
        // PackageFunction should be requesting that key while using the inlining code path.
        (skyFunctions.get(SkyFunctions.PACKAGE) as PackageFunction)
            .setBzlLoadFunctionForInliningForTesting(inliningBzlLoadFunction)
    }

    /**
     * Returns whether or not to use the inlined version of BzlLoadFunction in this test.
     * 
     * @see BzlLoadFunction.computeInline
     */
    protected open fun usesInliningBzlLoadFunction(): Boolean {
        return false
    }

    /**
     * Returns extra precomputed values to inject, both into Skyframe and the testing package loaders.
     */
    @Throws(Exception::class)
    protected open fun extraPrecomputedValues(): ImmutableList<PrecomputedValue.Injected?>? {
        return ImmutableList.of<PrecomputedValue.Injected?>()
    }

    @Throws(IOException::class)
    protected open fun initializeMockClient() {
        analysisMock!!.setupMockClient(mockToolsConfig)
        analysisMock!!.setupPrelude(mockToolsConfig)
    }

    protected open fun getAnalysisMock(): AnalysisMock {
        return AnalysisMock.Companion.get()
    }

    /**
     * Called to create the rule class provider used in this test.
     * 
     * 
     * This function is called only once. (Multiple calls could lead to subtle identity bugs
     * between native objects.)
     */
    protected open fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        return getAnalysisMock().createRuleClassProvider()
    }

    protected fun getRuleClassProvider(): ConfiguredRuleClassProvider {
        return ruleClassProvider
    }

    protected val starlarkSemantics: StarlarkSemantics
        get() = buildLanguageOptions.toStarlarkSemantics()

    protected open val packageValidator: PackageValidator
        get() = PackageValidator.NOOP_VALIDATOR

    protected open val packageOverheadEstimator: PackageOverheadEstimator
        get() = PackageOverheadEstimator.NOOP_ESTIMATOR

    @Throws(Exception::class)
    protected fun createConfiguration(vararg args: String?): BuildConfigurationValue {
        val buildOptions: BuildOptions = createBuildOptions(*args)

        // This is being done outside of BuildView, potentially even before the BuildView was
        // constructed and thus cannot rely on BuildView having injected this for us.
        skyframeExecutor.setBaselineConfiguration(buildOptions, reporter)
        return skyframeExecutor.createConfiguration(reporter, buildOptions, false)
    }

    @Throws(OptionsParsingException::class, InvalidConfigurationException::class)
    protected fun createBuildOptions(vararg args: String?): BuildOptions {
        val allArgs = ImmutableList.copyOf<String?>(args)
        return skyframeExecutor.createBuildOptionsForTesting(reporter, allArgs)
    }

    @Throws(
        NoSuchPackageException::class,
        NoSuchTargetException::class,
        LabelSyntaxException::class,
        InterruptedException::class
    )
    protected fun getTarget(label: String?): Target? {
        return getTarget(Label.parseCanonical(label))
    }

    @Throws(NoSuchPackageException::class, NoSuchTargetException::class, InterruptedException::class)
    protected fun getTarget(label: Label?): Target {
        return this.packageManager.getTarget(reporter, label)
    }

    /**
     * Checks that loading the given target fails with the expected error message.
     * 
     * 
     * Fails with an assertion error if this doesn't happen.
     * 
     * 
     * This method is useful for checking loading phase errors. Analysis phase errors can be
     * checked with [.getConfiguredTarget] and related methods.
     */
    @Throws(InterruptedException::class)
    protected fun assertTargetError(label: String?, expectedError: String?) {
        try {
            getTarget(label)
            Assert.fail("Expected loading phase failure for target " + label)
        } catch (e: NoSuchPackageException) {
            // Target loading failed as expected.
        } catch (e: NoSuchTargetException) {
        } catch (e: LabelSyntaxException) {
        }
        assertContainsEvent(expectedError)
    }

    private fun setUpSkyframe() {
        val pkgLocator: PathPackageLocator? =
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
        skyframeExecutor.preparePackageLoading(
            pkgLocator,
            packageOptions,
            buildLanguageOptions,
            UUID.randomUUID(),
            ImmutableMap.of<K?, V?>(),
            QuiescingExecutorsImpl.forTesting(),
            tsgm
        )
        skyframeExecutor.setActionEnv(ImmutableMap.of<K?, V?>())
        skyframeExecutor.setDeletedPackages(packageOptions.getDeletedPackagesOrEmptySet())
        skyframeExecutor.injectExtraPrecomputedValues(
            ImmutableList.of<E?>(
                PrecomputedValue.injected(
                    RepositoryDirectoryValue.VENDOR_DIRECTORY, Optional.empty<T?>()
                )
            )
        )
    }

    @Throws(OptionsParsingException::class, InterruptedException::class, AbruptExitException::class)
    protected fun setPackageOptions(vararg options: String?) {
        packageOptions = parsePackageOptions(*options)
        setUpSkyframe()
        invalidatePackages( /* alsoConfigs= */false)
    }

    @Throws(OptionsParsingException::class, InterruptedException::class, AbruptExitException::class)
    protected open fun setBuildLanguageOptions(vararg options: String?) {
        buildLanguageOptions = parseBuildLanguageOptions(*options)
        setUpSkyframe()
        invalidatePackages( /* alsoConfigs= */false)
    }

    @Throws(InterruptedException::class, AbruptExitException::class)
    protected fun setPackageAndBuildLanguageOptions(
        packageOptions: PackageOptions, buildLanguageOptions: BuildLanguageOptions
    ) {
        this.packageOptions = packageOptions
        this.buildLanguageOptions = buildLanguageOptions
        setUpSkyframe()
        invalidatePackages( /* alsoConfigs= */false)
    }

    protected open val defaultVisibility: String
        /**
         * Override to change the default visibility for a test suite. Visibility can also be controlled
         * with [.setPackageOptions].
         */
        get() = "public"

    @Throws(OptionsParsingException::class)
    private fun parsePackageOptions(vararg options: String?): PackageOptions {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(PackageOptions::class.java).build()
        parser.parse("--default_visibility=" + this.defaultVisibility)
        parser.parse(*options)
        return parser.getOptions<O>(PackageOptions::class.java)
    }

    @Throws(OptionsParsingException::class)
    protected fun parseBuildLanguageOptions(vararg options: String?): BuildLanguageOptions {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(BuildLanguageOptions::class.java).build()
        parser.parse(this.defaultBuildLanguageOptions)
        parser.parse(*options)
        return parser.getOptions<O>(BuildLanguageOptions::class.java)
    }

    protected open val defaultBuildLanguageOptions: MutableList<String?>
        get() {
            val ans =
                ImmutableList.builder<String?>()
            ans.addAll(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS)
            return ans.build()
        }

    /** Used by skyframe-only tests.  */
    protected fun getSkyframeExecutor(): SequencedSkyframeExecutor? {
        return Preconditions.checkNotNull<SequencedSkyframeExecutor?>(skyframeExecutor)
    }

    protected val packageManager: PackageManager
        get() = skyframeExecutor.getPackageManager()

    /**
     * Invalidates all existing packages, clears the cache for inlined bzl loads (including builtins),
     * and invalidates configurations.
     */
    @Throws(InterruptedException::class, AbruptExitException::class)
    protected open fun invalidatePackages() {
        invalidatePackages(true)
    }

    /**
     * Invalidates all existing packages and clears the cache for inlined bzl loads (including
     * builtins). Optionally also invalidates configurations.
     * 
     * 
     * Tests should invalidate both unless they have specific reason not to.
     */
    @Throws(InterruptedException::class, AbruptExitException::class)
    protected fun invalidatePackages(alsoConfigs: Boolean) {
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory)
        )
        if (inliningBzlLoadFunction != null) {
            inliningBzlLoadFunction.resetInliningCacheAndBuiltinsForTesting()
        }
        if (alsoConfigs) {
            try {
                // Also invalidate all configurations. This is important: by invalidating all files we
                // invalidate CROSSTOOL, which invalidates CppConfiguration (and a few other fragments).
                // Otherwise we end up with old CppConfiguration instances. Even though they're logically
                // equal to the new ones, CppConfiguration has no .equals() method and some production code
                // expects equality.
                useConfiguration(*configurationArgs.toTypedArray<String?>())
            } catch (e: Exception) {
                // There are enough dependers on this method that don't handle Exception that just passing
                // through the Exception would result in a huge refactoring. As it stands this shouldn't
                // fail anyway because this method only gets called after a successful useConfiguration()
                // call anyway.
                throw RuntimeException(e)
            }
        }
    }

    protected open val defaultsForConfiguration: Iterable<String?>?
        /**
         * Returns options that will be implicitly prepended to any options passed to [ ][.useConfiguration].
         */
        get() = TestConstants.PRODUCT_SPECIFIC_FLAGS

    /**
     * Sets exec and target configuration using the specified options, falling back to the default
     * options for unspecified ones, and recreates the build view.
     * 
     * 
     * NOTE: Build language options are not support by this method, for example
     * --experimental_google_legacy_api. Use [.setBuildLanguageOptions] instead.
     * 
     * @param args native and Starlark option name/pair descriptions in command line form (e.g.
     * "--cpu=k8")
     */
    @Throws(Exception::class)
    protected open fun useConfiguration(vararg args: String?) {
        val actualArgs =
            ImmutableList.builder<String?>().addAll(this.defaultsForConfiguration).add(*args).build()

        targetConfig = createConfiguration(*actualArgs.toTypedArray<String?>())
        if (!scratch.resolve("platform/BUILD").exists()) {
            scratch.overwriteFile("platform/BUILD", "platform(name = 'exec')")
        }
        execConfig =
            skyframeExecutor.getConfiguration(
                reporter,
                AnalysisTestUtil.execOptions(targetConfig.getOptions(), skyframeExecutor, reporter),  /* keepGoing= */
                false
            )

        targetConfigKey = targetConfig.getKey()
        configurationArgs = actualArgs
        createBuildView()
    }

    /**
     * Creates BuildView using current execConfig/targetConfig values. Ensures that execConfig is
     * either identical to the targetConfig or `isExecConfiguration()` is true.
     */
    @Throws(InvalidConfigurationException::class, InterruptedException::class)
    protected fun createBuildView() {
        Preconditions.checkNotNull<Any?>(targetConfig)
        Preconditions.checkState(
            this.execConfiguration.equals(this.targetConfiguration)
                    || this.execConfiguration.isExecConfiguration(),
            "Exec configuration %s is not an exec configuration' "
                    + "and does not match target configuration %s",
            this.execConfiguration,
            this.targetConfiguration
        )

        skyframeExecutor.handleAnalysisInvalidatingChange()
        skyframeExecutor.setBaselineConfiguration(targetConfig.getOptions(), reporter)

        view = BuildViewForTesting(directories, ruleClassProvider, skyframeExecutor, null)
        view!!.setConfigurationForTesting(targetConfig)

        val root: Root? = Root.fromPath(rootDirectory)
        view!!.getArtifactFactory().setPackageRoots({ pkgId -> root })
    }

    @get:Throws(InterruptedException::class)
    protected val testAnalysisEnvironment: CachingAnalysisEnvironment?
        get() {
            val env: SkyFunction.Environment = SkyFunctionEnvironmentForTesting(reporter, skyframeExecutor)
            val starlarkBuiltinsValue: StarlarkBuiltinsValue =
                Preconditions.checkNotNull<T?>(env.getValue(StarlarkBuiltinsValue.key())) as StarlarkBuiltinsValue
            return CachingAnalysisEnvironment(
                view!!.getArtifactFactory(),
                actionKeyContext,
                object : ActionLookupKey() {
                    val label: Label?
                        get() = null

                    val configurationKey: BuildConfigurationKey?
                        get() = null

                    public override fun functionName(): SkyFunctionName? {
                        return null
                    }
                },  /* extendedSanityChecks= */
                false,  /* allowAnalysisFailures= */
                false,
                reporter,
                env,
                starlarkBuiltinsValue
            )
        }

    /**
     * Returns the sorted list of all rule classes available in builtins, following the logic of
     * `bazel info build-language`.
     * 
     * @param includeMacroWrappedRules if true, include rule classes for rules wrapped in macros.
     */
    @Throws(Exception::class)
    protected fun getBuiltinRuleClasses(includeMacroWrappedRules: Boolean): ImmutableList<RuleClass?> {
        val env: SkyFunction.Environment = SkyFunctionEnvironmentForTesting(reporter, skyframeExecutor)
        val builtins: StarlarkBuiltinsValue =
            Preconditions.checkNotNull<T?>(env.getValue(StarlarkBuiltinsValue.key())) as StarlarkBuiltinsValue
        return RuleClassUtils.getBuiltinRuleClasses(
            builtins, ruleClassProvider, includeMacroWrappedRules
        )
    }

    /**
     * Allows access to the prerequisites of a configured target. This is currently used in some tests
     * to reach into the internals of RuleCT for white box testing. In principle, this should not be
     * used; instead tests should only assert on properties of the exposed provider instances and / or
     * the action graph.
     */
    @Throws(
        InterruptedException::class,
        TransitionException::class,
        InvalidConfigurationException::class,
        InconsistentAspectOrderException::class,
        Failure::class
    )
    protected fun getDirectPrerequisites(target: ConfiguredTarget?): MutableCollection<ConfiguredTarget?>? {
        return view!!.getDirectPrerequisitesForTesting(reporter, target)
    }

    @Throws(Exception::class)
    protected fun getDirectPrerequisite(target: ConfiguredTarget?, label: String?): ConfiguredTarget? {
        val candidateLabel: Label? = Label.parseCanonical(label)
        val prereq: Optional<ConfiguredTarget?> =
            getDirectPrerequisites(target)!!.stream()
                .filter { candidate: ConfiguredTarget? -> candidate.getOriginalLabel().equals(candidateLabel) }
                .findFirst()
        return prereq.orElse(null)
    }

    @Throws(Exception::class)
    protected fun getConfiguredTargetAndDataDirectPrerequisite(
        ctad: ConfiguredTargetAndData, label: String?
    ): ConfiguredTargetAndData? {
        val candidateLabel: Label? = Label.parseCanonical(label)
        for (candidate in view!!.getConfiguredTargetAndDataDirectPrerequisitesForTesting(
            reporter, ctad.getConfiguredTarget()
        )) {
            if (candidate.getConfiguredTarget().getLabel().equals(candidateLabel)) {
                return candidate
            }
        }
        return null
    }

    /**
     * Creates and returns a rule context that is equivalent to the one that was used to create the
     * given configured target.
     */
    @Throws(Exception::class)
    protected fun getRuleContext(target: ConfiguredTarget?): RuleContext? {
        return view!!.getRuleContextForTesting(reporter, target, StubAnalysisEnvironment())
    }

    /**
     * Creates and returns a rule context to use for Starlark tests that is equivalent to the one that
     * was used to create the given configured target.
     */
    @Throws(Exception::class)
    protected fun getRuleContextForStarlark(target: ConfiguredTarget): RuleContext? {
        // TODO(bazel-team): we need this horrible workaround because CachingAnalysisEnvironment
        // only works with StoredErrorEventListener despite the fact it accepts the interface
        // ErrorEventListener, so it's not possible to create it with reporter.
        // See BuildView.getRuleContextForTesting().
        val eventHandler: StoredEventHandler =
            object : StoredEventHandler() {
                @kotlin.jvm.Synchronized
                override fun handle(e: Event?) {
                    super.handle(e)
                    reporter.handle(e)
                }
            }
        return view!!.getRuleContextForTesting(target, eventHandler)
    }

    /**
     * Allows access to the prerequisites of a configured target. This is currently used in some tests
     * to reach into the internals of RuleCT for white box testing. In principle, this should not be
     * used; instead tests should only assert on properties of the exposed provider instances and / or
     * the action graph.
     */
    @Throws(Exception::class)
    protected fun getPrerequisites(
        target: ConfiguredTarget?, attributeName: String?
    ): MutableList<out TransitiveInfoCollection?> {
        return Lists.transform<F?, T?>(
            getRuleContext(target).getPrerequisiteConfiguredTargets(attributeName),
            ConfiguredTargetAndData::getConfiguredTarget
        )
    }

    /**
     * Allows access to the prerequisites of a configured target. This is currently used in some tests
     * to reach into the internals of RuleCT for white box testing. In principle, this should not be
     * used; instead tests should only assert on properties of the exposed provider instances and / or
     * the action graph.
     */
    @Throws(Exception::class)
    protected fun <C : TransitiveInfoProvider?> getPrerequisites(
        target: ConfiguredTarget?, attributeName: String?, classType: Class<C?>?
    ): Iterable<C?> {
        return AnalysisUtils.getProviders(getPrerequisites(target, attributeName), classType)
    }

    /**
     * Allows access to the prerequisites of a configured target. This is currently used in some tests
     * to reach into the internals of RuleCT for white box testing. In principle, this should not be
     * used; instead tests should only assert on properties of the exposed provider instances and / or
     * the action graph.
     */
    @Throws(Exception::class)
    protected fun getPrerequisiteArtifacts(
        target: ConfiguredTarget?, attributeName: String?
    ): ImmutableList<Artifact?> {
        val result: MutableSet<Artifact?> = LinkedHashSet<Artifact?>()
        for (provider in getPrerequisites<C>(target, attributeName, FileProvider::class.java)) {
            result.addAll(provider.getFilesToBuild().toList())
        }
        return ImmutableList.copyOf<Artifact?>(result)
    }

    /**
     * Retrieves Starlark provider from a configured target.
     * 
     * 
     * Assuming that the provider is defined in the same bzl file as the rule.
     */
    @Throws(Exception::class)
    protected fun getStarlarkProvider(target: ConfiguredTarget, providerSymbol: String?): StarlarkInfo? {
        val key: StarlarkProvider.Key =
            Key(
                keyForBuild(
                    getTarget(target.getLabel())
                        .getAssociatedRule()
                        .getRuleClassObject()
                        .getRuleDefinitionEnvironmentLabel()
                ),
                providerSymbol
            )
        return target.get(key) as StarlarkInfo?
    }

    protected val actionGraph: ActionGraph
        get() = skyframeExecutor.getActionGraph(reporter)

    /** Returns all arguments used by the action.  */
    @Throws(Exception::class)
    protected fun allArgsForAction(action: SpawnAction): ImmutableList<String?> {
        val args = ImmutableList.Builder<String?>()
        val commandLines: ImmutableList<CommandLineAndParamFileInfo?> = action.getCommandLines().unpack()
        for (pair in commandLines.subList(1, commandLines.size)) {
            args.addAll(pair.commandLine.arguments())
        }
        return args.build()
    }

    /** Locates the first parameter file used by the action and returns its command line.  */
    protected fun paramFileCommandLineForAction(action: Action): CommandLine? {
        if (action is SpawnAction) {
            val commandLines: CommandLines = action.getCommandLines()
            for (pair in commandLines.unpack()) {
                if (pair.paramFileInfo != null) {
                    return pair.commandLine
                }
            }
        }
        val parameterFileWriteAction: ParameterFileWriteAction? = paramFileWriteActionForAction(action)
        return if (parameterFileWriteAction != null) parameterFileWriteAction.getCommandLine() else null
    }

    /** Locates the first parameter file used by the action and returns its args.  */
    @Throws(CommandLineExpansionException::class, InterruptedException::class)
    protected fun paramFileArgsForAction(action: Action): Iterable<String?>? {
        val commandLine: CommandLine? = paramFileCommandLineForAction(action)
        return if (commandLine != null) commandLine.arguments() else null
    }

    /**
     * Locates the first parameter file used by the action and returns its args.
     * 
     * 
     * If no param file is used, return the action's arguments.
     */
    @Throws(CommandLineExpansionException::class, InterruptedException::class)
    protected fun paramFileArgsOrActionArgs(action: CommandAction): Iterable<String?>? {
        val commandLine: CommandLine? = paramFileCommandLineForAction(action)
        return if (commandLine != null) commandLine.arguments() else action.getArguments()
    }

    /** Locates the first parameter file used by the action and returns its contents.  */
    @Throws(CommandLineExpansionException::class, InterruptedException::class, IOException::class)
    protected fun paramFileStringContentsForAction(action: Action): String? {
        if (action is SpawnAction) {
            val commandLines: CommandLines = action.getCommandLines()
            for (pair in commandLines.unpack()) {
                if (pair.paramFileInfo != null) {
                    val out = ByteArrayOutputStream()
                    ParameterFile.writeParameterFile(
                        out, pair.commandLine.arguments(), pair.paramFileInfo.getFileType()
                    )
                    return out.toString(StandardCharsets.ISO_8859_1)
                }
            }
        }
        val parameterFileWriteAction: ParameterFileWriteAction? = paramFileWriteActionForAction(action)
        return if (parameterFileWriteAction != null) parameterFileWriteAction.getStringContents() else null
    }

    protected fun paramFileWriteActionForAction(action: Action): ParameterFileWriteAction? {
        for (input in action.getInputs().toList()) {
            if (input !is SpecialArtifact) {
                val generatingAction: Action? = getGeneratingAction(input)
                if (generatingAction is ParameterFileWriteAction) {
                    return generatingAction
                }
            }
        }
        return null
    }

    protected fun getGeneratingActionAnalysisMetadata(artifact: Artifact?): ActionAnalysisMetadata? {
        Preconditions.checkNotNull<Any?>(artifact)
        var actionAnalysisMetadata: ActionAnalysisMetadata? =
            mutableActionGraph.getGeneratingAction(artifact)

        if (actionAnalysisMetadata == null) {
            if (artifact.isSourceArtifact() || !(artifact as DerivedArtifact).hasGeneratingActionKey()) {
                return null
            }
            actionAnalysisMetadata = this.actionGraph.getGeneratingAction(artifact)
        }

        return actionAnalysisMetadata
    }

    protected fun getGeneratingAction(target: ConfiguredTarget, outputName: String): Action? {
        val filesToBuild: NestedSet<Artifact?> = getFilesToBuild(target)
        return getGeneratingAction(outputName, filesToBuild, "filesToBuild")
    }

    private fun getGeneratingAction(
        outputName: String, filesToBuild: NestedSet<Artifact?>, providerName: String?
    ): Action? {
        return getGeneratingAction(findArtifactNamed(outputName, filesToBuild, providerName))
    }

    protected fun getGeneratingAction(artifact: Artifact?): Action? {
        val action: ActionAnalysisMetadata? = getGeneratingActionAnalysisMetadata(artifact)

        if (action != null) {
            Preconditions.checkState(
                action is Action, "%s is not a proper Action object", action.prettyPrint()
            )
            return action as Action
        } else {
            return null
        }
    }

    @Throws(Exception::class)
    protected fun runfilesTreeFor(testRunnerAction: TestRunnerAction): RunfilesTree {
        val runfilesTreeArtifact: Artifact? = testRunnerAction.getRunfilesTree()
        val runfilesTreeAction: RunfilesTreeAction? =
            getGeneratingAction(runfilesTreeArtifact) as RunfilesTreeAction?
        return runfilesTreeAction.getRunfilesTree()
    }

    @Throws(Exception::class)
    protected fun inputMetadataFor(testRunnerAction: TestRunnerAction): FakeActionInputFileCache {
        val result = FakeActionInputFileCache()
        result.putRunfilesTree(testRunnerAction.getRunfilesTree(), runfilesTreeFor(testRunnerAction))
        return result
    }

    protected fun getGeneratingActionInOutputGroup(
        target: ConfiguredTarget?, outputName: String, outputGroupName: String?
    ): Action? {
        val outputGroup: NestedSet<Artifact?> = OutputGroupInfo.get(target).getOutputGroup(outputGroupName)
        return getGeneratingAction(outputName, outputGroup, "outputGroup/" + outputGroupName)
    }

    /**
     * Returns the SpawnAction that generates an artifact. Implicitly assumes the action is a
     * SpawnAction.
     */
    protected fun getGeneratingSpawnAction(artifact: Artifact?): SpawnAction {
        return getGeneratingAction(artifact) as SpawnAction
    }

    protected fun getGeneratingSpawnAction(target: ConfiguredTarget, outputName: String): SpawnAction {
        return getGeneratingSpawnAction(
            findArtifactNamed(outputName, getFilesToBuild(target), target.getLabel())
        )
    }

    @Throws(CommandLineExpansionException::class, InterruptedException::class)
    protected fun getGeneratingSpawnActionArgs(artifact: Artifact?): MutableList<String?> {
        val a: SpawnAction = getGeneratingSpawnAction(artifact)
        return a.getArguments()
    }

    protected fun actionsTestUtil(): ActionsTestUtil {
        return ActionsTestUtil(this.actionGraph)
    }

    // Get a MutableActionGraph for testing purposes.
    protected fun getMutableActionGraph(): MutableActionGraph {
        return mutableActionGraph
    }

    /**
     * Returns the ConfiguredTarget for the specified label, configured for the "build" (aka "target")
     * configuration. If the label corresponds to a target with a top-level configuration transition,
     * that transition is applied to the given config in the returned ConfiguredTarget.
     * 
     * 
     * May return null on error; see [.getConfiguredTarget].
     */
    @Throws(LabelSyntaxException::class)
    fun getConfiguredTarget(label: String?): ConfiguredTarget? {
        return getConfiguredTarget(label, targetConfig)
    }

    /**
     * Returns the ConfiguredTarget for the specified label, using the given build configuration. If
     * the label corresponds to a target with a top-level configuration transition, that transition is
     * applied to the given config in the returned ConfiguredTarget.
     * 
     * 
     * May return null on error; see [.getConfiguredTarget].
     */
    @Throws(LabelSyntaxException::class)
    fun getConfiguredTarget(label: String?, config: BuildConfigurationValue?): ConfiguredTarget? {
        return getConfiguredTarget(Label.parseCanonical(label), config)
    }

    /**
     * Returns the ConfiguredTarget for the specified label, using the given build configuration. If
     * the label corresponds to a target with a top-level configuration transition, that transition is
     * applied to the given config in the returned ConfiguredTarget.
     * 
     * 
     * If the evaluation of the SkyKey corresponding to the configured target fails, this method
     * may return null. In that case, use a debugger to inspect the [ErrorInfo] for the
     * evaluation, which is produced by the [MemoizingEvaluator.getExistingValue] call in [ ][SkyframeExecutor.getConfiguredTargetForTesting]. See also b/26382502.
     * 
     * @throws AssertionError if the target cannot be transitioned into with the given configuration
     */
    // TODO(bazel-team): Should we work around b/26382502 by asserting here that the result is not
    // null?
    protected fun getConfiguredTarget(label: Label?, config: BuildConfigurationValue?): ConfiguredTarget? {
        try {
            return view!!.getConfiguredTargetForTesting(reporter, label, config)
        } catch (e: InvalidConfigurationException) {
            throw AssertionError(e)
        } catch (e: InterruptedException) {
            throw AssertionError(e)
        }
    }

    /**
     * Returns a ConfiguredTargetAndData for the specified label, using the given build configuration.
     */
    @Throws(
        StarlarkTransition.TransitionException::class,
        InvalidConfigurationException::class,
        InterruptedException::class
    )
    protected fun getConfiguredTargetAndData(
        label: Label?, config: BuildConfigurationValue?
    ): ConfiguredTargetAndData? {
        return view!!.getConfiguredTargetAndDataForTesting(reporter, label, config)
    }

    /**
     * Returns the ConfiguredTargetAndData for the specified label. If the label corresponds to a
     * target with a top-level configuration transition, that transition is applied to the given
     * config in the ConfiguredTargetAndData's ConfiguredTarget.
     */
    @Throws(
        LabelSyntaxException::class,
        StarlarkTransition.TransitionException::class,
        InvalidConfigurationException::class,
        InterruptedException::class
    )
    fun getConfiguredTargetAndData(label: String?): ConfiguredTargetAndData? {
        return getConfiguredTargetAndData(Label.parseCanonical(label), targetConfig)
    }

    /**
     * Returns the ConfiguredTarget for the specified file label, configured for the "build" (aka
     * "target") configuration.
     */
    @Throws(LabelSyntaxException::class)
    protected fun getFileConfiguredTarget(label: String?): FileConfiguredTarget? {
        return getConfiguredTarget(label, targetConfig) as FileConfiguredTarget?
    }

    /**
     * Returns the Artifact for the specified label, configured for the "build" (aka "target")
     * configuration.
     */
    @Throws(LabelSyntaxException::class)
    protected fun getArtifact(label: String?): Artifact {
        val target: ConfiguredTarget? = getConfiguredTarget(label, targetConfig)
        if (target is FileConfiguredTarget) {
            return target.getArtifact()
        } else {
            return getFilesToBuild(target).getSingleton()
        }
    }

    /**
     * Returns the ConfiguredTarget for the specified label, configured for the "exec" configuration.
     */
    @Throws(LabelSyntaxException::class)
    protected fun getExecConfiguredTarget(label: String?): ConfiguredTarget? {
        return getConfiguredTarget(label, this.execConfiguration)
    }

    /**
     * Returns the ConfiguredTarget for the specified file label, configured for the "exec"
     * configuration.
     */
    @Throws(LabelSyntaxException::class)
    protected fun getExecFileConfiguredTarget(label: String?): FileConfiguredTarget? {
        return getExecConfiguredTarget(label) as FileConfiguredTarget?
    }

    /** Returns the configurations in which the given label has already been configured.  */
    @Throws(Exception::class)
    protected fun getKnownConfigurations(label: String?): MutableSet<BuildConfigurationKey?> {
        val parsed: Label = Label.parseCanonicalUnchecked(label)
        val cts: MutableSet<BuildConfigurationKey?> = HashSet<BuildConfigurationKey?>()
        for (e in skyframeExecutor.getEvaluator().getDoneValues().entrySet()) {
            if (e.key !is ConfiguredTargetKey) {
                continue
            }
            if (parsed.equals(ctKey.getLabel())) {
                cts.add(ctKey.getConfigurationKey())
            }
        }
        return cts
    }

    /**
     * Returns the [ConfiguredAspect] with the given label. For example: `//my:defs.bzl%my_aspect`.
     * 
     * 
     * Assumes only one configured aspect exists for this label. If this isn't true, or you need
     * finer grained selection for different configurations, you'll need to expand this method.
     */
    @Throws(Exception::class)
    protected fun getAspect(label: String?): ConfiguredAspect {
        return skyframeExecutor.getEvaluator().getDoneValues().entrySet().stream()
            .filter(
                { e ->
                    e.getKey() is AspectKey
                            && (e.getKey() as AspectKey).getAspectName().equals(label)
                })
            .map({ e -> e.getValue() as AspectValue? })
            .collect(MoreCollectors.onlyElement<T?>())
    }

    /**
     * Rewrites the MODULE.bazel file
     * 
     * 
     * Triggers Skyframe to reinitialize everything.
     */
    @Throws(Exception::class)
    fun rewriteModuleDotBazel(vararg lines: String?) {
        scratch.overwriteFile("MODULE.bazel", *lines)
        invalidatePackages()
    }

    /**
     * Create and return a configured scratch rule.
     * 
     * @param packageName the package name of the rule.
     * @param ruleName the name of the rule.
     * @param lines the text of the rule.
     * @return the configured target instance for the created rule.
     */
    @Throws(Exception::class)
    protected fun scratchConfiguredTarget(
        packageName: String, ruleName: String?, vararg lines: String?
    ): ConfiguredTarget? {
        return scratchConfiguredTarget(packageName, ruleName, targetConfig, *lines)
    }

    /**
     * Create and return a configured scratch rule.
     * 
     * @param packageName the package name of the rule.
     * @param ruleName the name of the rule.
     * @param config the configuration to use to construct the configured rule.
     * @param lines the text of the rule.
     * @return the configured target instance for the created rule.
     */
    @Throws(Exception::class)
    protected fun scratchConfiguredTarget(
        packageName: String, ruleName: String?, config: BuildConfigurationValue?, vararg lines: String?
    ): ConfiguredTarget? {
        val ctad: ConfiguredTargetAndData? =
            scratchConfiguredTargetAndData(packageName, ruleName, config, *lines)
        return if (ctad == null) null else ctad.getConfiguredTarget()
    }

    /**
     * Creates and returns a configured scratch rule and its data.
     * 
     * @param packageName the package name of the rule.
     * @param rulename the name of the rule.
     * @param lines the text of the rule.
     * @return the configured tatarget and target instance for the created rule.
     */
    @Throws(Exception::class)
    protected fun scratchConfiguredTargetAndData(
        packageName: String, rulename: String?, vararg lines: String?
    ): ConfiguredTargetAndData? {
        return scratchConfiguredTargetAndData(packageName, rulename, targetConfig, *lines)
    }

    /**
     * Creates and returns a configured scratch rule and its data.
     * 
     * @param packageName the package name of the rule.
     * @param ruleName the name of the rule.
     * @param config the configuration to use to construct the configured rule.
     * @param lines the text of the rule.
     * @return the ConfiguredTargetAndData instance for the created rule.
     */
    @Throws(Exception::class)
    protected fun scratchConfiguredTargetAndData(
        packageName: String, ruleName: String?, config: BuildConfigurationValue?, vararg lines: String?
    ): ConfiguredTargetAndData? {
        val rule: Target = scratchRule(packageName, ruleName, *lines)
        return view!!.getConfiguredTargetAndDataForTesting(reporter, rule.getLabel(), config)
    }

    /**
     * Create and return a scratch rule.
     * 
     * @param packageName the package name of the rule.
     * @param ruleName the name of the rule.
     * @param lines the text of the rule.
     * @return the rule instance for the created rule.
     */
    @Throws(Exception::class)
    protected fun scratchRule(packageName: String, ruleName: String?, vararg lines: String?): Rule {
        // Allow to create the BUILD file also in the top package.
        val buildFilePathString = if (packageName.isEmpty()) "BUILD" else packageName + "/BUILD"
        scratch.file(buildFilePathString, *lines)
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter,
            Builder().modify(PathFragment.create(buildFilePathString)).build(),
            Root.fromPath(rootDirectory)
        )
        return getTarget("//" + packageName + ":" + ruleName) as Rule
    }

    /**
     * Check that configuration of the target named 'ruleName' in the specified BUILD file fails with
     * an error message containing 'expectedErrorMessage'.
     * 
     * @param packageName the package name of the generated BUILD file
     * @param ruleName the rule name for the rule in the generated BUILD file
     * @param expectedErrorMessage the expected error message.
     * @param lines the text of the rule.
     * @return the found error.
     */
    @Throws(Exception::class)
    protected fun checkError(
        packageName: String, ruleName: String?, expectedErrorMessage: String?, vararg lines: String?
    ): Event? {
        eventCollector.clear()
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        val target: ConfiguredTarget? = scratchConfiguredTarget(packageName, ruleName, *lines)
        if (target != null) {
            Truth.assertWithMessage("Rule '" + "//%s:%s' did not contain an error", packageName, ruleName)
                .that(view!!.hasErrors(target))
                .isTrue()
        }
        return assertContainsEvent(expectedErrorMessage)
    }

    /**
     * Check that configuration of the target named 'ruleName' in the specified BUILD file fails with
     * an error message matching 'expectedErrorPattern'.
     * 
     * @param packageName the package name of the generated BUILD file
     * @param ruleName the rule name for the rule in the generated BUILD file
     * @param expectedErrorPattern a regex that matches the expected error.
     * @param lines the text of the rule.
     * @return the found error.
     */
    @Throws(Exception::class)
    protected fun checkError(
        packageName: String, ruleName: String?, expectedErrorPattern: Pattern?, vararg lines: String?
    ): Event? {
        eventCollector.clear()
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        val target: ConfiguredTarget? = scratchConfiguredTarget(packageName, ruleName, *lines)
        if (target != null) {
            Truth.assertWithMessage("Rule '" + "//%s:%s' did not contain an error", packageName, ruleName)
                .that(view!!.hasErrors(target))
                .isTrue()
        }
        return assertContainsEvent(expectedErrorPattern)
    }

    /**
     * Check that configuration of the target named 'label' fails with an error message containing
     * 'expectedErrorMessage'.
     * 
     * @param label the target name to test
     * @param expectedErrorMessage the expected error message.
     * @return the found error.
     */
    @Throws(Exception::class)
    protected fun checkError(label: String, expectedErrorMessage: String?): Event? {
        eventCollector.clear()
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        val target: ConfiguredTarget? = getConfiguredTarget(label)
        if (target != null) {
            Truth.assertWithMessage("Rule '%s' did not contain an error", label)
                .that(view!!.hasErrors(target))
                .isTrue()
        }
        return assertContainsEvent(expectedErrorMessage)
    }

    /**
     * Checks whether loading the given target results in the specified error message.
     * 
     * @param target the name of the target.
     * @param expectedErrorMessage the expected error message.
     */
    protected fun checkLoadingPhaseError(target: String?, expectedErrorMessage: String?) {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        // The error happens during the loading of the Starlark file so checkError doesn't work here
        Assert.assertThrows<Exception?>(Exception::class.java, ThrowingRunnable { getTarget(target) })
        assertContainsEvent(expectedErrorMessage)
    }

    /**
     * Check that configuration of the target named 'ruleName' in the specified BUILD file reports a
     * warning message ending in 'expectedWarningMessage', and that no errors were reported.
     * 
     * @param packageName the package name of the generated BUILD file
     * @param ruleName the rule name for the rule in the generated BUILD file
     * @param expectedWarningMessage the expected warning message.
     * @param lines the text of the rule.
     * @return the found error.
     */
    @Throws(Exception::class)
    protected fun checkWarning(
        packageName: String, ruleName: String?, expectedWarningMessage: String?, vararg lines: String?
    ): Event? {
        eventCollector.clear()
        val target: ConfiguredTarget? = scratchConfiguredTarget(packageName, ruleName, *lines)
        Truth.assertWithMessage("Rule '" + "//%s:%s' did contain an error", packageName, ruleName)
            .that(view!!.hasErrors(target))
            .isFalse()
        return assertContainsEvent(expectedWarningMessage)
    }

    /**
     * Given a collection of Artifacts, returns a corresponding set of strings of the form "[root]
     * [relpath]", such as "bin x/libx.a". Such strings make assertions easier to write.
     * 
     * 
     * The returned set preserves the order of the input.
     */
    protected fun artifactsToStrings(artifacts: NestedSet<out Artifact?>): MutableSet<String?>? {
        return artifactsToStrings(artifacts.toList())
    }

    /**
     * Given a collection of Artifacts, returns a corresponding set of strings of the form "[root]
     * [relpath]", such as "bin x/libx.a". Such strings make assertions easier to write.
     * 
     * 
     * The returned set preserves the order of the input.
     */
    protected fun artifactsToStrings(artifacts: Iterable<out Artifact?>): MutableSet<String?> {
        return AnalysisTestUtil.artifactsToStrings(targetConfig, artifacts)
    }

    protected fun getSourceArtifact(rootRelativePath: PathFragment?, root: Root?): Artifact {
        return view!!.getArtifactFactory().getSourceArtifact(rootRelativePath, root)
    }

    protected fun getSourceArtifact(name: String?, owner: ArtifactOwner?): Artifact {
        return view!!.getArtifactFactory()
            .getSourceArtifact(PathFragment.create(name), Root.fromPath(rootDirectory), owner)
    }

    protected fun getSourceArtifact(name: String?): Artifact? {
        return getSourceArtifact(PathFragment.create(name), Root.fromPath(rootDirectory))
    }

    /**
     * Gets a derived artifact, creating it if necessary. `ArtifactOwner` should be a genuine
     * [ConfiguredTargetKey] corresponding to a [ConfiguredTarget]. If called from a test
     * that does not exercise the analysis phase, the convenience methods [ ][.getBinArtifactWithNoOwner] or [.getGenfilesArtifactWithNoOwner] should be used instead.
     */
    protected fun getDerivedArtifact(
        rootRelativePath: PathFragment?, root: ArtifactRoot?, owner: ArtifactOwner?
    ): Artifact.DerivedArtifact? {
        if (owner is ActionLookupKey) {
            val skyValue: SkyValue?
            try {
                skyValue = skyframeExecutor.getEvaluator().getExistingValue((owner as ActionLookupKey?))
            } catch (e: InterruptedException) {
                throw IllegalStateException(e)
            }
            if (skyValue is ActionLookupValue) {
                for (action in skyValue.getActions()) {
                    for (output in action.getOutputs()) {
                        if (output.getRootRelativePath().equals(rootRelativePath)
                            && output.getRoot().equals(root)
                        ) {
                            return output as Artifact.DerivedArtifact
                        }
                    }
                }
            }
        }
        // Fall back: some tests don't actually need an artifact with an owner.
        // TODO(janakr): the tests that are passing in nonsense here should be changed.
        return view!!.getArtifactFactory().getDerivedArtifact(rootRelativePath, root, owner)
    }

    /**
     * Gets a Tree Artifact for testing in the subdirectory of the [ ][BuildConfigurationValue.getBinDirectory] corresponding to the package of `owner`. So to
     * specify a file foo/foo.o owned by target //foo:foo, `packageRelativePath` should just be
     * "foo.o".
     */
    protected fun getTreeArtifact(packageRelativePath: String?, owner: ConfiguredTarget): Artifact? {
        val actionLookupKey: ActionLookupKey? = ConfiguredTargetKey.fromConfiguredTarget(owner)
        return getDerivedArtifact(
            owner.getLabel().getPackageFragment().getRelative(packageRelativePath),
            getConfiguration(owner).getBinDirectory(RepositoryName.MAIN),
            actionLookupKey
        )
    }

    /**
     * Gets a derived Artifact for testing with path of the form
     * root/owner.getPackageFragment()/packageRelativePath.
     * 
     * @see .getDerivedArtifact
     */
    private fun getPackageRelativeDerivedArtifact(
        packageRelativePath: String?, root: ArtifactRoot?, owner: ArtifactOwner
    ): Artifact? {
        return getDerivedArtifact(
            owner.getLabel().getPackageFragment().getRelative(packageRelativePath), root, owner
        )
    }

    /**
     * Gets a derived Artifact for testing in the [BuildConfigurationValue.getBinDirectory].
     * This method should only be used for tests that do no analysis, and so there is no
     * ConfiguredTarget to own this artifact. If the test runs the analysis phase, [ ][.getBinArtifact] or its convenience methods should be used instead.
     */
    protected fun getBinArtifactWithNoOwner(rootRelativePath: String?): Artifact.DerivedArtifact? {
        return getDerivedArtifact(
            PathFragment.create(rootRelativePath),
            targetConfig.getBinDirectory(RepositoryName.MAIN),
            ActionsTestUtil.NULL_ARTIFACT_OWNER
        )
    }

    /**
     * Gets a derived Artifact for testing in the subdirectory of the [ ][BuildConfigurationValue.getBinDirectory] corresponding to the package of `owner`. So to
     * specify a file foo/foo.o owned by target //foo:foo, `packageRelativePath` should just be
     * "foo.o".
     */
    protected fun getBinArtifact(packageRelativePath: String?, owner: ConfiguredTarget?): Artifact? {
        try {
            return getPackageRelativeDerivedArtifact(
                packageRelativePath,
                getRuleContext(owner).getBinDirectory(),
                ConfiguredTargetKey.fromConfiguredTarget(owner)
            )
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    /**
     * Gets a derived Artifact for testing in the subdirectory of the [ ][BuildConfigurationValue.getBinDirectory] corresponding to the package of `owner`, where
     * the given artifact belongs to the given ConfiguredTarget together with the given Aspect. So to
     * specify a file foo/foo.o owned by target //foo:foo with an aspect from FooAspect, `packageRelativePath` should just be "foo.o", and aspectOfOwner should be FooAspect.class. This
     * method is necessary when an Aspect of the target, not the target itself, is creating an
     * Artifact.
     */
    protected fun getBinArtifact(
        packageRelativePath: String?, owner: ConfiguredTarget, creatingAspectFactory: AspectClass?
    ): Artifact? {
        return getBinArtifact(
            packageRelativePath, owner, creatingAspectFactory, AspectParameters.EMPTY
        )
    }

    /**
     * Gets a derived Artifact for testing in the subdirectory of the [ ][BuildConfigurationValue.getBinDirectory] corresponding to the package of `owner`, where
     * the given artifact belongs to the given ConfiguredTarget together with the given Aspect. So to
     * specify a file foo/foo.o owned by target //foo:foo with an aspect from FooAspect, `packageRelativePath` should just be "foo.o", and aspectOfOwner should be FooAspect.class. This
     * method is necessary when an Aspect of the target, not the target itself, is creating an
     * Artifact.
     */
    protected fun getBinArtifact(
        packageRelativePath: String?,
        owner: ConfiguredTarget,
        creatingAspectFactory: AspectClass?,
        parameters: AspectParameters?
    ): Artifact? {
        try {
            return getPackageRelativeDerivedArtifact(
                packageRelativePath,
                getRuleContext(owner).getBinDirectory(),
                AspectKeyCreator.createAspectKey(
                    AspectDescriptor.of(creatingAspectFactory, parameters),
                    ConfiguredTargetKey.builder()
                        .setLabel(owner.getLabel())
                        .setConfiguration(getConfiguration(owner))
                        .build()
                )
            )
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    /**
     * Gets a derived Artifact for testing in the [ ][BuildConfigurationValue.getGenfilesDirectory]. This method should only be used for tests that
     * do no analysis, and so there is no ConfiguredTarget to own this artifact. If the test runs the
     * analysis phase, [.getGenfilesArtifact] or its convenience
     * methods should be used instead.
     */
    protected fun getGenfilesArtifactWithNoOwner(rootRelativePath: String?): Artifact? {
        return getDerivedArtifact(
            PathFragment.create(rootRelativePath),
            targetConfig.getGenfilesDirectory(RepositoryName.MAIN),
            ActionsTestUtil.NULL_ARTIFACT_OWNER
        )
    }

    /**
     * Gets a derived Artifact for testing in the subdirectory of the [ ][BuildConfigurationValue.getGenfilesDirectory] corresponding to the package of `owner`. So
     * to specify a file foo/foo.o owned by target //foo:foo, `packageRelativePath` should just
     * be "foo.o".
     */
    protected fun getGenfilesArtifact(packageRelativePath: String?, owner: String?): Artifact? {
        val config: BuildConfigurationValue = getConfiguration(owner)
        return getGenfilesArtifact(
            packageRelativePath,
            ConfiguredTargetKey.builder()
                .setLabel(Label.parseCanonicalUnchecked(owner))
                .setConfiguration(config)
                .build(),
            config
        )
    }

    /**
     * Gets a derived Artifact for testing in the subdirectory of the [ ][BuildConfigurationValue.getGenfilesDirectory] corresponding to the package of `owner`. So
     * to specify a file foo/foo.o owned by target //foo:foo, `packageRelativePath` should just
     * be "foo.o".
     */
    protected fun getGenfilesArtifact(packageRelativePath: String?, owner: ConfiguredTarget?): Artifact? {
        val configKey: ConfiguredTargetKey = ConfiguredTargetKey.fromConfiguredTarget(owner)
        val configuration: BuildConfigurationValue? =
            skyframeExecutor.getConfiguration(reporter, configKey.getConfigurationKey())
        return getGenfilesArtifact(packageRelativePath, configKey, configuration)
    }

    /**
     * Gets a derived Artifact for testing in the subdirectory of the [ ][BuildConfigurationValue.getGenfilesDirectory] corresponding to the package of `owner`,
     * where the given artifact belongs to the given ConfiguredTarget together with the given Aspect.
     * So to specify a file foo/foo.o owned by target //foo:foo with an apsect from FooAspect, `packageRelativePath` should just be "foo.o", and aspectOfOwner should be FooAspect.class. This
     * method is necessary when an Apsect of the target, not the target itself, is creating an
     * Artifact.
     */
    protected fun getGenfilesArtifact(
        packageRelativePath: String?, owner: ConfiguredTarget, creatingAspectFactory: AspectClass?
    ): Artifact? {
        return getGenfilesArtifact(
            packageRelativePath, owner, creatingAspectFactory, AspectParameters.EMPTY
        )
    }

    protected fun getGenfilesArtifact(
        packageRelativePath: String?,
        owner: ConfiguredTarget,
        creatingAspectFactory: AspectClass?,
        params: AspectParameters?
    ): Artifact? {
        return getPackageRelativeDerivedArtifact(
            packageRelativePath,
            getConfiguration(owner).getGenfilesDirectory(owner.getLabel().getRepository()),
            getOwnerForAspect(owner, creatingAspectFactory, params)
        )
    }

    /**
     * Gets a derived Artifact for testing in the subdirectory of the [ ][BuildConfigurationValue.getGenfilesDirectory] corresponding to the package of `owner`. So
     * to specify a file foo/foo.o owned by target //foo:foo, `packageRelativePath` should just
     * be "foo.o".
     */
    private fun getGenfilesArtifact(
        packageRelativePath: String?, owner: ArtifactOwner, config: BuildConfigurationValue
    ): Artifact? {
        return getPackageRelativeDerivedArtifact(
            packageRelativePath, config.getGenfilesDirectory(RepositoryName.MAIN), owner
        )
    }

    protected fun getOwnerForAspect(
        owner: ConfiguredTarget, creatingAspectFactory: AspectClass?, params: AspectParameters?
    ): AspectKey {
        return AspectKeyCreator.createAspectKey(
            AspectDescriptor.of(creatingAspectFactory, params),
            ConfiguredTargetKey.builder()
                .setLabel(owner.getLabel())
                .setConfiguration(getConfiguration(owner))
                .build()
        )
    }

    /**
     * @return a shared artifact at the binary-root relative path `rootRelativePath` owned by
     * `owner`.
     * @param rootRelativePath the binary-root relative path of the artifact.
     * @param owner the artifact's owner.
     */
    protected fun getSharedArtifact(rootRelativePath: String?, owner: ConfiguredTarget?): Artifact? {
        try {
            return getDerivedArtifact(
                PathFragment.create(rootRelativePath),
                getRuleContext(owner).getBinDirectory(),
                ConfiguredTargetKey.fromConfiguredTarget(owner)
            )
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    @Throws(Exception::class)
    protected fun getGeneratingActionForLabel(label: String?): Action? {
        return getGeneratingAction(getArtifact(label))
    }

    protected val outputPath: Path
        get() = directories.getOutputPath(ruleClassProvider.getRunfilesPrefix())

    protected val relativeOutputPath: String
        get() = directories.getRelativeOutputPath()

    /**
     * Verifies whether the rule checks the 'srcs' attribute validity.
     * 
     * 
     * At the call site it expects the `packageName` to contain:
     * 
     * 
     *  1. `:gvalid` - genrule that outputs a valid file
     *  1. `:ginvalid` - genrule that outputs an invalid file
     *  1. `:gmix` - genrule that outputs a mix of valid and invalid files
     *  1. `:valid` - rule of type `ruleType` that has a valid file, `:gvalid` and
     * `:gmix` in the srcs
     *  1. `:invalid` - rule of type `ruleType` that has an invalid file, `:ginvalid` in the srcs
     *  1. `:mix` - rule of type `ruleType` that has a valid and an invalid file in the
     * srcs
     * 
     * 
     * @param packageName the package where the rules under test are located
     * @param ruleType rules under test types
     * @param expectedTypes expected file types
     */
    @Throws(Exception::class)
    protected fun assertSrcsValidityForRuleType(
        packageName: String?, ruleType: String, expectedTypes: String
    ) {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val descriptionSingle = ruleType + " srcs file (expected " + expectedTypes + ")"
        val descriptionPlural = ruleType + " srcs files (expected " + expectedTypes + ")"
        val descriptionPluralFile = "(expected " + expectedTypes + ")"
        assertSrcsValidity(
            ruleType,
            packageName + ":valid",
            false,
            "need at least one " + descriptionSingle,
            "'" + packageName + ":gvalid' does not produce any " + descriptionPlural,
            "'" + packageName + ":gmix' does not produce any " + descriptionPlural
        )
        assertSrcsValidity(
            ruleType,
            packageName + ":invalid",
            true,
            "source file '" + packageName + ":a.foo' is misplaced here " + descriptionPluralFile,
            "'" + packageName + ":ginvalid' does not produce any " + descriptionPlural
        )
        assertSrcsValidity(
            ruleType,
            packageName + ":mix",
            true,
            "'" + packageName + ":a.foo' does not produce any " + descriptionPlural
        )
    }

    @Throws(Exception::class)
    protected fun assertSrcsValidity(
        ruleType: String, targetName: String?, expectedError: Boolean, vararg expectedMessages: String?
    ) {
        val target: ConfiguredTarget? = getConfiguredTarget(targetName)
        if (expectedError) {
            Truth.assertThat(view!!.hasErrors(target)).isTrue()
            for (expectedMessage in expectedMessages) {
                val message =
                    "in srcs attribute of " + ruleType + " rule " + targetName + ": " + expectedMessage
                assertContainsEvent(message)
            }
        } else {
            Truth.assertThat(view!!.hasErrors(target)).isFalse()
            for (expectedMessage in expectedMessages) {
                val message =
                    ("in srcs attribute of "
                            + ruleType
                            + " rule "
                            + target.getLabel()
                            + ": "
                            + expectedMessage)
                assertDoesNotContainEvent(message)
            }
        }
    }

    /**
     * Utility method for asserting that the contents of one collection are the same as those in a
     * second plus some set of common elements.
     */
    protected fun assertSameContentsWithCommonElements(
        artifacts: Iterable<String?>?, expectedInputs: Array<String?>, common: Iterable<String?>
    ) {
        Truth.assertThat(artifacts)
            .containsExactlyElementsIn(Iterables.concat<String?>(Lists.newArrayList<String?>(*expectedInputs), common))
    }

    protected fun assertContainsSelfEdgeEvent(label: String?) {
        assertContainsEvent(Pattern.compile(label + " \\([a-f0-9]+\\) \\[self-edge]"))
    }

    /** Returns all extra actions for that target (no transitive actions), no duplicate actions.  */
    protected fun getExtraActionActions(target: ConfiguredTarget): ImmutableList<Action?> {
        val result: LinkedHashSet<Action?> = LinkedHashSet<Action?>()
        for (artifact in getExtraActionArtifacts(target).toList()) {
            result.add(getGeneratingAction(artifact))
        }
        return ImmutableList.copyOf<Action?>(result)
    }

    @Throws(Exception::class)
    protected fun getActions(label: String?, actionClass: Class<*>?): ImmutableList<Action?> {
        return (getConfiguredTarget(label) as RuleConfiguredTarget)
            .getActions().stream()
            .map({ obj: Any? -> Action::class.java.cast(obj) })
            .filter({ action -> action.getClass().equals(actionClass) })
            .collect(ImmutableList.toImmutableList<E?>())
    }

    @Throws(Exception::class)
    protected fun getActions(label: String?, mnemonic: String?): ImmutableList<Action?> {
        return (getConfiguredTarget(label) as RuleConfiguredTarget)
            .getActions().stream()
            .map({ obj: Any? -> Action::class.java.cast(obj) })
            .filter({ action -> action.getMnemonic().equals(mnemonic) })
            .collect(ImmutableList.toImmutableList<E?>())
    }

    @Throws(Exception::class)
    protected fun getActions(label: String?): ImmutableList<Action?> {
        return (getConfiguredTarget(label) as RuleConfiguredTarget)
            .getActions().stream().map({ obj: Any? -> Action::class.java.cast(obj) })
            .collect(ImmutableList.toImmutableList<E?>())
    }

    @Throws(Exception::class)
    protected fun getExecutable(label: String?): Artifact {
        return getConfiguredTarget(label).getProvider(FilesToRunProvider::class.java).getExecutable()
    }

    @Throws(Exception::class)
    protected fun getFilesToRun(label: String?): NestedSet<Artifact?> {
        return getConfiguredTarget(label).getProvider(FilesToRunProvider::class.java).getFilesToRun()
    }

    @Throws(Exception::class)
    protected fun getRunfilesSupport(label: String?): RunfilesSupport {
        return getConfiguredTarget(label).getProvider(FilesToRunProvider::class.java).getRunfilesSupport()
    }

    val targetConfiguration: BuildConfigurationValue
        get() = targetConfig

    protected val execConfiguration: BuildConfigurationValue?
        get() = execConfig

    private fun getConfiguration(label: String?): BuildConfigurationValue {
        try {
            return getConfiguration(getConfiguredTarget(label))
        } catch (e: LabelSyntaxException) {
            throw IllegalArgumentException(e)
        }
    }

    protected fun getConfiguration(ct: ConfiguredTarget): BuildConfigurationValue {
        return skyframeExecutor.getConfiguration(reporter, ct.getConfigurationKey())
    }

    @Throws(OptionsParsingException::class)
    protected fun useLoadingOptions(vararg options: String?) {
        customLoadingOptions = Options.parse(LoadingOptions::class.java, options).options
    }

    @Throws(Exception::class)
    protected fun update(target: String, loadingPhaseThreads: Int, doAnalysis: Boolean): AnalysisResult? {
        return update(
            ImmutableList.of<String?>(target),
            ImmutableList.of<String?>(),  /* keepGoing= */
            true,  // value doesn't matter since we have only one target.
            loadingPhaseThreads,
            doAnalysis,
            EventBus()
        )
    }

    @Throws(Exception::class)
    protected fun update(
        targets: MutableList<String?>?,
        keepGoing: Boolean,
        loadingPhaseThreads: Int,
        doAnalysis: Boolean,
        eventBus: EventBus?
    ): AnalysisResult? {
        return update(
            targets, ImmutableList.of<String?>(), keepGoing, loadingPhaseThreads, doAnalysis, eventBus
        )
    }

    @Throws(Exception::class)
    protected fun update(
        targets: MutableList<String?>?,
        aspects: MutableList<String?>?,
        keepGoing: Boolean,
        loadingPhaseThreads: Int,
        doAnalysis: Boolean,
        eventBus: EventBus?
    ): AnalysisResult? {
        val loadingOptions: LoadingOptions? =
            if (customLoadingOptions == null)
                Options.getDefaults<O?>(LoadingOptions::class.java)
            else
                customLoadingOptions

        val viewOptions: AnalysisOptions? = Options.getDefaults<O?>(AnalysisOptions::class.java)

        val loadingResult: TargetPatternPhaseValue? =
            skyframeExecutor.loadTargetPatternsWithFilters(
                reporter,
                targets,
                PathFragment.EMPTY_FRAGMENT,
                loadingOptions,
                loadingPhaseThreads,
                keepGoing,  /* determineTests= */
                false
            )
        if (!doAnalysis) {
            // TODO(bazel-team): What's supposed to happen in this case?
            return null
        }
        return view!!.update(
            loadingResult,
            targetConfig.getOptions(),  /* explicitTargetPatterns= */
            ImmutableSet.of<Label?>(),
            aspects,  /* aspectsParameters= */
            ImmutableMap.of<String?, String?>(),
            viewOptions,
            keepGoing,
            loadingPhaseThreads,
            AnalysisTestUtil.TOP_LEVEL_ARTIFACT_CONTEXT,
            reporter,
            eventBus
        )
    }

    /**
     * Utility method for tests that result in errors early during package loading. Given the name of
     * the package for the test, and the rules for the build file, create a scratch file, load the
     * build file, and produce the package.
     * 
     * @param packageName the name of the package for the build file
     * @param lines the rules for the build file as an array of strings
     * @return the loaded package from the populated package cache
     * @throws Exception if there is an error creating the temporary files for the test.
     */
    @Throws(Exception::class)
    protected fun createScratchPackageForImplicitCycle(
        packageName: String?, vararg lines: String?
    ): Package {
        eventCollector.clear()
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(packageName + "/BUILD", *lines)
        return this.packageManager
            .getPackage(reporter, PackageIdentifier.createInMainRepo(packageName))
    }

    /**
     * Copies the protolark-provided `project` scl definition into the given scratch file path.
     * 
     * 
     * `PROJECT.scl` files load this file to define their configuration. This method loads
     * the actual (non-mocked) file, so tests can effectively match production code.
     */
    @Throws(Exception::class)
    protected fun writeProjectSclDefinition(dest: String?) {
        scratch.file(
            dest,
            Files.readString(
                Path.of(
                    Runfiles.preload()
                        .withSourceRepository("")
                        .rlocation(
                            (TestConstants.WORKSPACE_NAME
                                    + "/"
                                    + TestConstants.PROJECT_SCL_DEFINITION_PATH)
                        )
                )
            )
        )
    }

    /** A stub analysis environment.  */
    protected inner class StubAnalysisEnvironment : AnalysisEnvironment {
        public override fun registerAction(action: ActionAnalysisMetadata?) {
            throw UnsupportedOperationException()
        }

        public override fun hasErrors(): Boolean {
            return false
        }

        public override fun getConstantMetadataArtifact(
            rootRelativePath: PathFragment?,
            root: ArtifactRoot?
        ): Artifact? {
            throw UnsupportedOperationException()
        }

        public override fun getRunfilesArtifact(
            rootRelativePath: PathFragment?,
            root: ArtifactRoot?
        ): SpecialArtifact? {
            throw UnsupportedOperationException()
        }

        public override fun getTreeArtifact(rootRelativePath: PathFragment?, root: ArtifactRoot?): SpecialArtifact? {
            throw UnsupportedOperationException()
        }

        public override fun getSymlinkArtifact(rootRelativePath: PathFragment?, root: ArtifactRoot?): SpecialArtifact? {
            throw UnsupportedOperationException()
        }

        val eventHandler: ExtendedEventHandler?
            get() = reporter

        public override fun getLocalGeneratingAction(artifact: Artifact?): Action? {
            throw UnsupportedOperationException()
        }

        val registeredActions: ImmutableList<ActionAnalysisMetadata>?
            get() {
                throw UnsupportedOperationException()
            }

        val skyframeEnv: SkyFunction.Environment?
            get() {
                throw UnsupportedOperationException()
            }

        val starlarkSemantics: StarlarkSemantics
            get() = buildLanguageOptions.toStarlarkSemantics()

        val starlarkDefinedBuiltins: ImmutableMap<String?, Any?>?
            get() {
                throw UnsupportedOperationException()
            }

        public override fun getFilesetArtifact(rootRelativePath: PathFragment?, root: ArtifactRoot?): Artifact? {
            throw UnsupportedOperationException()
        }

        public override fun getDerivedArtifact(
            rootRelativePath: PathFragment?, root: ArtifactRoot?
        ): Artifact.DerivedArtifact? {
            throw UnsupportedOperationException()
        }

        val stableWorkspaceStatusArtifact: Artifact?
            get() {
                throw UnsupportedOperationException()
            }

        val volatileWorkspaceStatusArtifact: Artifact?
            get() {
                throw UnsupportedOperationException()
            }

        public override fun declareStampSettingDep() {
            throw UnsupportedOperationException()
        }

        val owner: ActionLookupKey?
            get() {
                throw UnsupportedOperationException()
            }

        val orphanArtifacts: ImmutableSet<Artifact>?
            get() {
                throw UnsupportedOperationException()
            }

        val treeArtifactsConflictingWithFiles: ImmutableSet<Artifact>?
            get() {
                throw UnsupportedOperationException()
            }

        public override fun getActionKeyContext(): ActionKeyContext? {
            return actionKeyContext
        }

        val mainRepoMapping: RepositoryMapping?
            get() {
                throw UnsupportedOperationException()
            }
    }

    @Throws(Exception::class)
    protected fun baselineCoverageArtifactBasenames(target: ConfiguredTarget): ImmutableList<String?> {
        val baselineCoverageArtifacts: ImmutableList<Artifact?> =
            target
                .get(InstrumentedFilesInfo.provider)
                .getBaselineCoverageArtifacts()
                .toList()

        val basenames = ImmutableList.builder<String?>()
        for (baselineCoverage in baselineCoverageArtifacts) {
            val baselineCoverageAction: BaselineCoverageAction? =
                getGeneratingAction(baselineCoverage) as BaselineCoverageAction?
            val bytes = ByteArrayOutputStream()
            baselineCoverageAction
                .newDeterministicWriter(ActionsTestUtil.createContext(reporter))
                .writeTo(bytes)

            for (line in Splitter.on('\n').split(bytes.toString(StandardCharsets.UTF_8))) {
                if (line.startsWith("SF:")) {
                    val basename: String = line.substring(line.lastIndexOf('/') + 1)
                    basenames.add(basename)
                }
            }
        }
        return basenames.build()
    }

    /**
     * Finds an artifact in the transitive closure of a set of other artifacts by following a path
     * based on artifact name suffixes.
     * 
     * 
     * This selects the first artifact in the input set that matches the first suffix, then selects
     * the first artifact in the inputs of its generating action that matches the second suffix etc.,
     * and repeats this until the supplied suffixes run out.
     */
    protected fun artifactByPath(artifacts: NestedSet<Artifact?>, vararg suffixes: String?): Artifact? {
        return artifactByPath(artifacts.toList(), suffixes)
    }

    /**
     * Finds an artifact in the transitive closure of a set of other artifacts by following a path
     * based on artifact name suffixes.
     * 
     * 
     * This selects the first artifact in the input set that matches the first suffix, then selects
     * the first artifact in the inputs of its generating action that matches the second suffix etc.,
     * and repeats this until the supplied suffixes run out.
     */
    protected fun artifactByPath(artifacts: Iterable<Artifact?>?, vararg suffixes: String?): Artifact? {
        var artifacts: Iterable<Artifact?>? = artifacts
        var artifact: Artifact? = getFirstArtifactEndingWith(artifacts, suffixes[0])
        var action: Action? = null
        for (i in 1..<suffixes.size) {
            if (artifact == null) {
                checkNotNull(action != null) {
                    java.lang.String.format(
                        "No suffix %s among artifacts: %s",
                        suffixes[0], ActionsTestUtil.baseArtifactNames(artifacts)
                    )
                }
                throw IllegalStateException(
                    java.lang.String.format(
                        "No suffix %s among inputs of action %s: %s",
                        suffixes[i], action.describe(), ActionsTestUtil.baseArtifactNames(artifacts)
                    )
                )
            }

            action = getGeneratingAction(artifact)
            artifacts = action.getInputs().toList()
            artifact = getFirstArtifactEndingWith(artifacts, suffixes[i])
        }

        return artifact
    }

    /**
     * Retrieves an instance of `PseudoAction` that is shadowed by an extra action
     * 
     * @param targetLabel Label of the target with an extra action
     * @param actionListenerLabel Label of the action listener
     */
    @Throws(Exception::class)
    protected fun getPseudoActionViaExtraAction(
        targetLabel: String, actionListenerLabel: String?
    ): PseudoAction<*>? {
        useConfiguration(String.format("--experimental_action_listener=%s", actionListenerLabel))

        val target: ConfiguredTarget? = getConfiguredTarget(targetLabel)
        val actions: MutableList<Action?> = getExtraActionActions(target)

        Truth.assertThat(actions).isNotNull()
        Truth.assertThat(actions).hasSize(2)

        var extraAction: ExtraAction? = null

        for (action in actions) {
            if (action is ExtraAction) {
                extraAction = action
                break
            }
        }

        Truth.assertWithMessage(actions.toString()).that(extraAction).isNotNull()

        val pseudoAction: Action = extraAction.getShadowedAction()

        assertThat(pseudoAction).isInstanceOf(PseudoAction::class.java)
        assertThat(pseudoAction.getPrimaryOutput().getExecPathString())
            .isEqualTo(
                java.lang.String.format(
                    "%s%s.extra_action_dummy",
                    targetConfig.getGenfilesFragment(RepositoryName.MAIN),
                    convertLabelToPath(targetLabel)
                )
            )

        return pseudoAction as PseudoAction<*>
    }

    @Throws(EvalException::class)
    protected fun getImplicitOutputPath(
        target: ConfiguredTarget, outputFunction: SafeImplicitOutputsFunction
    ): String? {
        val rule: Rule
        try {
            rule = skyframeExecutor.getPackageManager().getTarget(reporter, target.getLabel()) as Rule
        } catch (e: NoSuchPackageException) {
            throw IllegalStateException(e)
        } catch (e: NoSuchTargetException) {
            throw IllegalStateException(e)
        } catch (e: InterruptedException) {
            throw IllegalStateException(e)
        }
        val attr: RawAttributeMapper? = RawAttributeMapper.of(rule.getAssociatedRule())

        return Iterables.getOnlyElement<T?>(outputFunction.getImplicitOutputs(eventCollector, attr))
    }

    /**
     * Gets the artifact whose name is derived from `outputFunction`. Despite the name, this can
     * be called for artifacts that are not declared as implicit outputs: it just finds the artifact
     * inside the configured target by calling [.getBinArtifact] on
     * the result of the `outputFunction`.
     */
    @Throws(EvalException::class)
    protected fun getImplicitOutputArtifact(
        target: ConfiguredTarget, outputFunction: SafeImplicitOutputsFunction
    ): Artifact? {
        return getBinArtifact(getImplicitOutputPath(target, outputFunction), target)
    }

    val execRoot: Path
        get() = directories.getExecRoot(ruleClassProvider.getRunfilesPrefix())

    /** Creates instances of [ActionExecutionContext] consistent with test case.  */
    inner class ActionExecutionContextBuilder {
        private var actionInputFileCache: InputMetadataProvider? = null
        private val clientEnv: TreeMap<String?, String?> = TreeMap<String?, String?>()
        private var executor: Executor? = DummyExecutor(
            fileSystem,
            this.execRoot
        )

        @CanIgnoreReturnValue
        fun setMetadataProvider(
            actionInputFileCache: InputMetadataProvider?
        ): ActionExecutionContextBuilder {
            this.actionInputFileCache = actionInputFileCache
            return this
        }

        @CanIgnoreReturnValue
        fun setExecutor(executor: Executor?): ActionExecutionContextBuilder {
            this.executor = executor
            return this
        }

        fun build(): ActionExecutionContext? {
            return ActionExecutionContext(
                executor,
                actionInputFileCache,  /* actionInputPrefetcher= */
                null,
                actionKeyContext,  /* outputMetadataStore= */
                null,  /* rewindingEnabled= */
                false,
                LostInputsCheck.NONE,
                actionLogBufferPathGenerator.generate(ArtifactPathResolver.IDENTITY),
                reporter,
                clientEnv,  /* actionFileSystem= */
                null,
                DiscoveredModulesPruner.DEFAULT,
                SyscallCache.NO_CACHE,
                ThreadStateReceiver.NULL_INSTANCE
            )
        }
    }

    companion object {
        protected const val LOADING_PHASE_THREADS: Int = 20

        /** Returns a [BuildOptions] with options in `exclude` trimmed away.  */
        private fun trimConfiguration(
            original: BuildOptions, exclude: MutableSet<Class<out FragmentOptions?>?>
        ): BuildOptions {
            val trimmed: BuildOptions.Builder = original.toBuilder()
            exclude.forEach(trimmed::removeFragmentOptions)
            return trimmed.build()
        }

        /**
         * Asserts that two configurations are the same, with exclusions.
         * 
         * 
         * Any fragments options of type specified in excludeFragmentOptions are excluded from the
         * comparison.
         * 
         * 
         * Generally, this means they share the same checksum, which is computed by iterating over all
         * the individual @Option annotated values contained within the [FragmentOptions] classes
         * contained within the [BuildOptions] inside the given configurations.
         */
        protected fun assertConfigurationsEqual(
            config1: BuildConfigurationValue,
            config2: BuildConfigurationValue,
            excludeFragmentOptions: MutableSet<Class<out FragmentOptions?>?>
        ) {
            // BuildOptions and crosstool files determine a configuration's content. Within the context
            // of these tests only the former actually change.

            assertThat(trimConfiguration(config2.cloneOptions(), excludeFragmentOptions))
                .isEqualTo(trimConfiguration(config1.cloneOptions(), excludeFragmentOptions))
        }

        protected fun assertConfigurationsEqual(
            config1: BuildConfigurationValue, config2: BuildConfigurationValue
        ) {
            assertConfigurationsEqual(
                config1,
                config2,  /* excludeFragmentOptions= */
                ImmutableSet.of<Class<out FragmentOptions?>?>()
            )
        }

        private fun findArtifactNamed(
            name: String, artifacts: NestedSet<Artifact?>, context: Any?
        ): Artifact {
            return artifacts.toList().stream()
                .filter(artifactNamed(name))
                .findFirst()
                .orElseThrow(
                    {
                        NoSuchElementException(
                            String.format(
                                "Artifact named '%s' not found in %s (%s)", name, context, artifacts
                            )
                        )
                    })
        }

        /**
         * Given a list of PathFragments, returns a corresponding list of strings. Such strings make
         * assertions easier to write.
         */
        protected fun pathfragmentsToStrings(pathFragments: MutableList<PathFragment?>): ImmutableList<String?> {
            return pathFragments.stream().map<Any?>(PathFragment::toString)
                .collect(ImmutableList.toImmutableList<Any?>())
        }

        /** Returns the input [Artifact]s to the given [Action] with the given exec paths.  */
        protected fun getInputs(owner: Action, execPaths: MutableCollection<String?>): MutableList<Artifact?> {
            val expectedPaths: MutableSet<String?> = HashSet<String?>(execPaths)
            val result: MutableList<Artifact?> = ArrayList<Artifact?>()
            for (output in owner.getInputs().toList()) {
                if (expectedPaths.remove(output.getExecPathString())) {
                    result.add(output)
                }
            }
            assertWithMessage("expected paths not found in: %s", Artifact.asExecPaths(owner.getInputs()))
                .that(expectedPaths)
                .isEmpty()
            return result
        }

        protected fun getMapperFromConfiguredTargetAndTarget(
            ctad: ConfiguredTargetAndData
        ): ConfiguredAttributeMapper {
            return ctad.getAttributeMapperForTesting()
        }

        protected fun actionInputsToPaths(
            actionInputs: NestedSet<out ActionInput?>
        ): ImmutableList<String?> {
            return ImmutableList.copyOf<E?>(
                Lists.transform<F?, T?>(actionInputs.toList(), ActionInput::getExecPathString)
            )
        }

        /**
         * Utility method for asserting that a list contains the elements of a sublist. This is useful for
         * checking that a list of arguments contains a particular set of arguments.
         */
        protected fun assertContainsSublist(list: MutableList<String?>, sublist: MutableList<String?>) {
            assertContainsSublist(null, list, sublist)
        }

        /**
         * Utility method for asserting that a list contains the elements of a sublist. This is useful for
         * checking that a list of arguments contains a particular set of arguments.
         */
        protected fun assertContainsSublist(
            message: String?, list: MutableList<String?>, sublist: MutableList<String?>
        ) {
            if (Collections.indexOfSubList(list, sublist) == -1) {
                Assert.fail(
                    String.format(
                        "%sexpected: <%s> to contain sublist: <%s>",
                        if (message == null) "" else (message + ' '), list, sublist
                    )
                )
            }
        }

        protected fun collectRunfiles(target: ConfiguredTarget): NestedSet<Artifact?> {
            val runfilesProvider: RunfilesProvider? = target.getProvider(RunfilesProvider::class.java)
            if (runfilesProvider != null) {
                return runfilesProvider.getDefaultRunfiles().getAllArtifacts()
            } else {
                return Runfiles.EMPTY.getAllArtifacts()
            }
        }

        protected fun getFilesToBuild(target: TransitiveInfoCollection): NestedSet<Artifact?> {
            return target.getProvider(FileProvider::class.java).getFilesToBuild()
        }

        protected fun getOutputGroup(
            target: TransitiveInfoCollection?, outputGroup: String?
        ): NestedSet<Artifact?> {
            val provider: OutputGroupInfo? = OutputGroupInfo.get(target)
            return if (provider == null)
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            else
                provider.getOutputGroup(outputGroup)
        }

        protected fun getExtraActionArtifacts(
            target: ConfiguredTarget
        ): NestedSet<Artifact.DerivedArtifact?> {
            return target.getProvider(ExtraActionArtifactsProvider::class.java).getExtraActionArtifacts()
        }

        protected fun getExecutable(target: TransitiveInfoCollection): Artifact {
            return target.getProvider(FilesToRunProvider::class.java).getExecutable()
        }

        protected fun getFilesToRun(target: TransitiveInfoCollection): NestedSet<Artifact?> {
            return target.getProvider(FilesToRunProvider::class.java).getFilesToRun()
        }

        protected fun getRunfilesSupport(target: TransitiveInfoCollection): RunfilesSupport {
            return target.getProvider(FilesToRunProvider::class.java).getRunfilesSupport()
        }

        protected fun getDefaultRunfiles(target: ConfiguredTarget): Runfiles {
            return target.getProvider(RunfilesProvider::class.java).getDefaultRunfiles()
        }

        protected fun getDataRunfiles(target: ConfiguredTarget): Runfiles {
            return target.getProvider(RunfilesProvider::class.java).getDataRunfiles()
        }

        protected fun artifactNamed(name: String): Predicate<Artifact?> {
            return Predicate { artifact: Artifact? -> name == artifact.prettyPrint() }
        }

        /**
         * Utility method for tests. Converts an array of strings into a set of labels.
         * 
         * @param strings the set of strings to be converted to labels.
         * @throws LabelSyntaxException if there are any syntax errors in the strings.
         */
        @Throws(LabelSyntaxException::class)
        fun asLabelSet(vararg strings: String?): MutableSet<Label?> {
            return asLabelSet(ImmutableList.copyOf<String?>(strings))
        }

        /**
         * Utility method for tests. Converts an array of strings into a set of labels.
         * 
         * @param strings the set of strings to be converted to labels.
         * @throws LabelSyntaxException if there are any syntax errors in the strings.
         */
        @Throws(LabelSyntaxException::class)
        fun asLabelSet(strings: Iterable<String?>): MutableSet<Label?> {
            val result: MutableSet<Label?> = Sets.newTreeSet<Label?>()
            for (s in strings) {
                result.add(Label.parseCanonical(s))
            }
            return result
        }

        protected fun getErrorMsgNoGoodFiles(
            attrName: String?, ruleType: String?, ruleName: String?, depRuleName: String?
        ): String? {
            return String.format(
                "in %s attribute of %s rule %s: '%s' does not produce any %s %s files",
                attrName, ruleType, ruleName, depRuleName, ruleType, attrName
            )
        }

        protected fun getErrorMsgMisplacedFiles(
            attrName: String?, ruleType: String?, ruleName: String?, fileName: String?
        ): String? {
            return String.format(
                "in %s attribute of %s rule %s: source file '%s' is misplaced here",
                attrName, ruleType, ruleName, fileName
            )
        }

        protected fun getErrorNonExistingTarget(
            attrName: String?, ruleType: String?, ruleName: String?, targetName: String?
        ): String? {
            return String.format(
                "in %s attribute of %s rule %s: target '%s' does not exist",
                attrName, ruleType, ruleName, targetName
            )
        }

        protected fun getErrorNonExistingRule(
            attrName: String?, ruleType: String?, ruleName: String?, targetName: String?
        ): String? {
            return String.format(
                "in %s attribute of %s rule %s: rule '%s' does not exist",
                attrName, ruleType, ruleName, targetName
            )
        }

        protected fun getErrorMsgMisplacedRules(
            attrName: String?, ruleType: String?, ruleName: String?, depRuleType: String?, depRuleName: String?
        ): String? {
            return String.format(
                "in %s attribute of %s rule %s: %s rule '%s' is misplaced here",
                attrName, ruleType, ruleName, depRuleType, depRuleName
            )
        }

        protected fun getErrorMsgNonEmptyList(
            attrName: String?, ruleType: String?, ruleName: String?
        ): String? {
            return String.format(
                "in %s attribute of %s rule %s: attribute must be non empty", attrName, ruleType, ruleName
            )
        }

        protected fun getErrorMsgWrongAttributeValue(value: String?, vararg expected: String?): String? {
            return String.format(
                "has to be one of %s instead of '%s'",
                StringUtil.joinEnglishListSingleQuoted(ImmutableSet.copyOf<String?>(expected)), value
            )
        }

        protected fun getErrorMsgMandatoryProviderMissing(
            offendingRule: String?, providerName: String?
        ): String? {
            return String.format(
                "'%s' does not have mandatory providers: '%s'", offendingRule, providerName
            )
        }

        /**
         * Converts the given label to an output path where double slashes and colons are replaced with
         * single slashes.
         */
        private fun convertLabelToPath(label: String): String {
            return label.replace(':', '/').substring(1)
        }

        /** Returns true iff commandLine contains the option --flagName followed by arg.  */
        protected fun containsFlag(flagName: String, arg: String, commandLine: Iterable<String?>): Boolean {
            val iterator: MutableIterator<String?> = commandLine.iterator()
            while (iterator.hasNext()) {
                if (flagName == iterator.next() && iterator.hasNext() && arg == iterator.next()) {
                    return true
                }
            }
            return false
        }

        /** Returns the list of arguments in commandLine that follow after --flagName.  */
        protected fun flagValue(flagName: String, commandLine: Iterable<String?>): ImmutableList<String?> {
            val resultBuilder = ImmutableList.builder<String?>()
            val iterator: MutableIterator<String> = commandLine.iterator()
            var found = false
            while (iterator.hasNext()) {
                val `val` = iterator.next()
                if (found) {
                    if (`val`.startsWith("--")) {
                        break
                    }
                    resultBuilder.add(`val`)
                } else if (flagName == `val`) {
                    found = true
                }
            }
            Preconditions.checkArgument(found)
            return resultBuilder.build()
        }
    }
}
