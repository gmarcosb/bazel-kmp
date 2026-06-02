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

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.actions.ActionKeyContext
import com.google.devtools.build.lib.clock.BlazeClock
import com.google.devtools.common.options.Option
import com.google.devtools.common.options.Options
import org.junit.Assert
import org.junit.function.ThrowingRunnable

/** Testing framework for tests which create configuration collections.  */
@RunWith(JUnit4::class)
abstract class ConfigurationTestCase : FoundationTestCase() {
    @OptionsClass
    abstract class TestOptions : OptionsBase() {
        @get:Option(
            name = "multi_cpu",
            converter = CommaSeparatedOptionListConverter::class,
            allowMultiple = true,
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "null",
            help = "Additional target CPUs."
        )
        abstract val multiCpus: MutableList<String?>?
    }

    protected var mockToolsConfig: MockToolsConfig? = null
    protected var workspace: Path? = null
    protected var analysisMock: AnalysisMock? = null
    protected var skyframeExecutor: SequencedSkyframeExecutor? = null
    protected var buildOptionClasses: ImmutableSet<Class<out FragmentOptions?>?>? = null
    protected val actionKeyContext: ActionKeyContext = ActionKeyContext()
    private var fragmentFactory: FragmentFactory? = null

    @Before
    @Throws(Exception::class)
    fun initializeSkyframeExecutor() {
        workspace = rootDirectory
        analysisMock = AnalysisMock.Companion.get()

        val ruleClassProvider: ConfiguredRuleClassProvider = analysisMock!!.createRuleClassProvider()
        buildOptionClasses = ruleClassProvider.getFragmentRegistry().getOptionsClasses()
        val pkgLocator: PathPackageLocator =
            PathPackageLocator(
                outputBase,
                ImmutableList.of<E?>(Root.fromPath(rootDirectory)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(rootDirectory, outputBase, outputBase),
                rootDirectory,
                analysisMock!!.getProductName()
            )

        mockToolsConfig = MockToolsConfig(rootDirectory)
        analysisMock!!.setupMockToolsRepository(mockToolsConfig)
        analysisMock!!.setupMockClient(mockToolsConfig)

        val pkgFactory: PackageFactory? =
            analysisMock!!
                .getPackageFactoryBuilderForTesting(directories)
                .build(ruleClassProvider, fileSystem)
        val workspaceStatusActionFactory: DummyWorkspaceStatusActionFactory =
            DummyWorkspaceStatusActionFactory()
        skyframeExecutor =
            BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
                .setPkgFactory(pkgFactory)
                .setFileSystem(fileSystem)
                .setDirectories(directories)
                .setActionKeyContext(actionKeyContext)
                .setWorkspaceStatusActionFactory(workspaceStatusActionFactory)
                .setExtraSkyFunctions(analysisMock!!.getSkyFunctions(directories))
                .setSyscallCache(SyscallCache.NO_CACHE)
                .build()
        SkyframeExecutorTestHelper.process(skyframeExecutor)
        val defaultBuildOptions: BuildOptions =
            BuildOptions.getDefaultBuildOptionsForFragments(buildOptionClasses).clone()
        defaultBuildOptions
            .get(CoreOptions::class.java)
            .setStarlarkExecConfig(TestConstants.STARLARK_EXEC_TRANSITION)
        skyframeExecutor.injectExtraPrecomputedValues(
            ImmutableList.Builder<PrecomputedValue.Injected?>()
                .add(
                    PrecomputedValue.injected(
                        BaselineOptionsFunction.BASELINE_CONFIGURATION, defaultBuildOptions
                    )
                )
                .add(
                    PrecomputedValue.injected( // Reuse the build options as the baseline exec. This is technically wrong but
                        // will only impact the exec configuration output path.
                        BaselineOptionsFunction.BASELINE_EXEC_CONFIGURATION, defaultBuildOptions
                    )
                )
                .addAll(analysisMock!!.getPrecomputedValues())
                .build()
        )
        val packageOptions: PackageOptions = Options.getDefaults<O>(PackageOptions::class.java)
        packageOptions.setShowLoadingProgress(true)
        packageOptions.setGlobbingThreads(7)
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(BuildLanguageOptions::class.java).build()
        parser.parse(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS)
        val options: BuildLanguageOptions? = parser.getOptions<O?>(BuildLanguageOptions::class.java)
        skyframeExecutor.preparePackageLoading(
            pkgLocator,
            packageOptions,
            options,
            UUID.randomUUID(),
            ImmutableMap.of<K?, V?>(),
            QuiescingExecutorsImpl.forTesting(),
            TimestampGranularityMonitor(BlazeClock.instance())
        )
        skyframeExecutor.setActionEnv(ImmutableMap.of<K?, V?>())

        mockToolsConfig = MockToolsConfig(rootDirectory)
        analysisMock!!.setupMockClient(mockToolsConfig)
        fragmentFactory = FragmentFactory()
    }

    protected fun checkError(expectedMessage: String?, vararg options: String?) {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        Assert.assertThrows<T?>(InvalidConfigurationException::class.java, ThrowingRunnable { create(*options) })
        assertContainsEvent(expectedMessage)
    }

    /**
     * Returns a [BuildConfigurationValue] with the given non-default options.
     * 
     * @param args native option name/pair descriptions in command line form (e.g. "--cpu=k8")
     */
    @Throws(Exception::class)
    protected fun createConfiguration(vararg args: String?): BuildConfigurationValue {
        return createConfiguration(ImmutableMap.of<String?, Any?>(), *args)
    }

    /**
     * Variation of [.createConfiguration] that also supports Starlark-defined
     * options.
     * 
     * @param starlarkOptions map of Starlark-defined options where the keys are option names (in the
     * form of label-like strings) and the values are option values
     * @param args native option name/pair descriptions in command line form (e.g. "--cpu=k8")
     */
    @Throws(Exception::class)
    protected fun createConfiguration(
        starlarkOptions: ImmutableMap<String?, Any?>?, vararg args: String?
    ): BuildConfigurationValue {
        val targetOptions: BuildOptions = parseBuildOptions(starlarkOptions, *args)

        skyframeExecutor.handleDiffsForTesting(reporter)
        skyframeExecutor.setBaselineConfiguration(targetOptions, reporter)
        return skyframeExecutor.createConfiguration(reporter, targetOptions, false)
    }

    /** Parses purported commandline options into a BuildOptions (assumes default parsing context.)  */
    @Throws(Exception::class)
    private fun parseBuildOptionsWithTestOptions(
        starlarkOptions: ImmutableMap<String?, Any?>?, vararg args: String?
    ): Pair<BuildOptions?, TestOptions?> {
        val parser: OptionsParser =
            OptionsParser.builder()
                .optionsClasses(
                    ImmutableList.builder<Class<out OptionsBase?>?>()
                        .addAll(buildOptionClasses)
                        .add(TestOptions::class.java)
                        .build()
                )
                .build()
        parser.setStarlarkOptions(starlarkOptions, ImmutableSet.of<String?>())
        parser.parse(TestConstants.PRODUCT_SPECIFIC_FLAGS)
        parser.parse(*args)

        return Pair.of(
            BuildOptions.of(buildOptionClasses, parser), parser.getOptions<O?>(TestOptions::class.java)
        )
    }

    /** Parses purported commandline options into a BuildOptions (assumes default parsing context.)  */
    @Throws(Exception::class)
    protected fun parseBuildOptions(
        starlarkOptions: ImmutableMap<String?, Any?>?, vararg args: String?
    ): BuildOptions {
        return parseBuildOptionsWithTestOptions(starlarkOptions, *args).first
    }

    /** Parses purported commandline options into a BuildOptions (assumes default parsing context.)  */
    @Throws(Exception::class)
    protected fun parseBuildOptions(vararg args: String?): BuildOptions {
        return parseBuildOptions(ImmutableMap.of<String?, Any?>(), *args)
    }

    /** Returns a raw [BuildConfigurationValue] with the given parameters.  */
    @Throws(Exception::class)
    protected fun createRaw(
        buildOptions: BuildOptions?,
        mnemonic: String?,
        siblingRepositoryLayout: Boolean
    ): BuildConfigurationValue {
        return BuildConfigurationValue.createForTesting(
            buildOptions,
            mnemonic,
            siblingRepositoryLayout,
            skyframeExecutor.getBlazeDirectoriesForTesting(),
            skyframeExecutor.getRuleClassProviderForTesting(),
            fragmentFactory
        )
    }

    /**
     * Returns a target [BuildConfigurationValue] with the given non-default options.
     * 
     * @param args native option name/pair descriptions in command line form (e.g. "--cpu=k8")
     */
    @Throws(Exception::class)
    protected fun create(vararg args: String?): BuildConfigurationValue {
        return createConfiguration(*args)
    }

    /**
     * Variation of [.create] that also supports Starlark-defined options.
     * 
     * @param starlarkOptions map of Starlark-defined options where the keys are option names (in the
     * form of label-like strings) and the values are option values
     * @param args native option name/pair descriptions in command line form (e.g. "--cpu=k8")
     */
    @Throws(Exception::class)
    protected fun create(
        starlarkOptions: ImmutableMap<String?, Any?>?, vararg args: String?
    ): BuildConfigurationValue {
        return createConfiguration(starlarkOptions, *args)
    }

    /**
     * Returns an exec [BuildConfigurationValue] derived from a target configuration with the
     * given non-default options. Supports Starlark Options.
     * 
     * @param starlarkOptions map of Starlark-defined options where the keys are option names (in the
     * form of label-like strings) and the values are option values
     * @param args native option name/pair descriptions in command line form (e.g. "--cpu=k8")
     */
    @Throws(Exception::class)
    protected fun createExec(
        starlarkOptions: ImmutableMap<String?, Any?>?, vararg args: String?
    ): BuildConfigurationValue {
        return skyframeExecutor.getConfiguration(
            reporter,
            AnalysisTestUtil.execOptions(
                parseBuildOptions(starlarkOptions, *args), skyframeExecutor, reporter
            ),  /* keepGoing= */
            false
        )
    }

    /**
     * Returns an exec [BuildConfigurationValue] derived from a target configuration with the
     * given non-default options. Does not support Starlark Options
     * 
     * @param args native option name/pair descriptions in command line form (e.g. "--cpu=k8")
     */
    @Throws(Exception::class)
    protected fun createExec(vararg args: String?): BuildConfigurationValue {
        return createExec(ImmutableMap.of<String?, Any?>(), *args)
    }
}
