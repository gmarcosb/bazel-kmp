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
package com.google.devtools.build.lib.query2.testutil

import com.google.common.base.Joiner
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.clock.BlazeClock
import com.google.devtools.build.lib.packages.Rule.ALL_LABELS
import com.google.devtools.build.lib.query2.engine.QueryException
import com.google.devtools.build.lib.query2.engine.QueryParser
import com.google.devtools.build.lib.query2.engine.QuerySyntaxException
import com.google.devtools.common.options.Options
import com.google.errorprone.annotations.ForOverride
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableIterator
import kotlin.collections.MutableList
import kotlin.collections.MutableSet

/** An implementation of AbstractQueryHelper to support testing bazel query.  */
abstract class SkyframeQueryHelper : AbstractQueryHelper<Target?>() {
    protected var skyframeExecutor: SkyframeExecutor? = null
    protected var fileSystem: FileSystem = InMemoryFileSystem(BlazeClock.instance(), DigestHashFunction.SHA256)
    private var registry: FakeRegistry? = null

    protected var rootDirectory: Path? = null
    protected var outputBase: Path? = null
    protected var moduleRoot: Path? = null
    protected var directories: BlazeDirectories? = null
    private var toolsRepository: RepositoryName? = null

    protected var analysisMock: AnalysisMock? = null
    private var queryEnvironmentFactory: QueryEnvironmentFactory? = null

    private var pkgManager: PackageManager? = null
    private var targetParser: TargetPatternPreloader? = null
    private var lazyMacroExpansionPackages: LazyMacroExpansionPackages? = LazyMacroExpansionPackages.NONE
    protected val actionKeyContext: ActionKeyContext = ActionKeyContext()

    private val ignoredSubdirectoriesFile: PathFragment? = PathFragment.create(".bazelignore")
    private val delegatingSyscallCache: DelegatingSyscallCache = DelegatingSyscallCache()

    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        analysisMock = AnalysisMock.get()
        rootDirectory = createDir(this.rootDirectoryNameForSetup)
        outputBase = createDir(fileSystem.getPath("/output").getPathString())
        directories =
            BlazeDirectories(
                ServerDirectories(
                    rootDirectory,
                    outputBase,
                    outputBase,
                    outputBase.getRelative(ServerDirectories.EXECROOT),
                    if (useVirtualSourceRoot()) Root.fromPath(rootDirectory) else null,
                    FAKE_INSTALL_MD5_STRING
                ),
                rootDirectory,
                analysisMock.productName
            )
        delegatingSyscallCache.setDelegate(SyscallCache.NO_CACHE)

        moduleRoot = createDir(outputBase.getRelative("modules").getPathString())
        registry = FakeRegistry.DEFAULT_FACTORY.newFakeRegistry(moduleRoot.getPathString())
        writeFile("MODULE.bazel", "module( name = \"root\", version = \"1.0\")")

        val mockToolsConfig: MockToolsConfig = MockToolsConfig(rootDirectory)
        analysisMock.setupMockClient(mockToolsConfig)
        analysisMock.setupMockToolsRepository(mockToolsConfig)
        analysisMock.ccSupport().setup(mockToolsConfig)
        analysisMock.pySupport().setup(mockToolsConfig)
        performAdditionalClientSetup(mockToolsConfig)

        initTargetPatternEvaluator(analysisMock.createRuleClassProvider())

        this.queryEnvironmentFactory = makeQueryEnvironmentFactory()
    }

    override fun cleanUp() {
        skyframeExecutor.getEvaluator().cleanupInterningPools()
    }

    protected abstract val rootDirectoryNameForSetup: String?

    @ForOverride
    protected fun useVirtualSourceRoot(): Boolean {
        return false
    }

    @Throws(IOException::class)
    protected abstract fun performAdditionalClientSetup(mockToolsConfig: MockToolsConfig?)

    @Throws(IOException::class)
    private fun createDir(pathName: String?): Path {
        val dir: Path = fileSystem.getPath(pathName)
        dir.createDirectoryAndParents()
        return dir
    }

    @Throws(AbruptExitException::class, InterruptedException::class)
    override fun maybeHandleDiffs() {
        if (skyframeExecutor.hasDiffAwareness()) {
            skyframeExecutor.handleDiffsForTesting(getReporter())
        }
    }

    override fun getIgnoredSubdirectoriesFile(): PathFragment? {
        return ignoredSubdirectoriesFile
    }

    @ForOverride
    protected open fun makeQueryEnvironmentFactory(): QueryEnvironmentFactory {
        return QueryEnvironmentFactory()
    }

    override fun getRootDirectory(): Path {
        return rootDirectory
    }

    @Throws(IOException::class)
    override fun clearAllFiles() {
        rootDirectory.deleteTreesBelow()
    }

    @Throws(IOException::class)
    override fun writeFile(fileName: String?, vararg lines: String?) {
        val file: Path = rootDirectory.getRelative(fileName)
        if (file.exists()) {
            throw IOException("Could not create scratch file (file exists) " + fileName)
        }
        file.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(file, Joiner.on('\n').join(lines))
    }

    @Throws(IOException::class)
    override fun overwriteFile(fileName: String?, vararg lines: String?) {
        val file: Path = rootDirectory.getRelative(fileName)
        file.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(file, Joiner.on('\n').join(lines))
    }

    @Throws(IOException::class)
    override fun ensureSymbolicLink(link: String?, target: String?) {
        val linkPath: Path = rootDirectory.getRelative(link)
        val targetPath: Path? = rootDirectory.getRelative(target)
        linkPath.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.ensureSymbolicLink(linkPath, targetPath)
    }

    override fun getQueryEnvironment(): AbstractBlazeQueryEnvironment<Target?> {
        return queryEnvironmentFactory.create(
            skyframeExecutor.getQueryTransitivePackagePreloader(),
            skyframeExecutor,
            pkgManager,
            pkgManager,
            targetParser,
            mainRepoTargetParser,  /* relativeWorkingDirectory= */
            PathFragment.EMPTY_FRAGMENT,
            keepGoing,  /* strictScope= */
            true,
            orderedResults,
            universeScope,  /* loadingPhaseThreads= */
            1,  /* trackIncrementalState= */
            true,  /* labelFilter= */
            ALL_LABELS,
            getReporter(),
            this.settings,
            this.extraQueryFunctions,
            pkgManager.getPackagePath(),  /* useGraphlessQuery= */
            false,
            LabelPrinter.legacy()
        )
    }

    protected abstract val extraQueryFunctions: Iterable<QueryFunction>?

    @Throws(QueryException::class, InterruptedException::class)
    override fun evaluateQuery(query: String?): ResultAndTargets<Target?> {
        getQueryEnvironment().use { env ->
            return evaluateQuery(query, env)
        }
    }

    @Throws(QueryException::class, InterruptedException::class)
    override fun evaluateQueryRaw(query: String?): MutableSet<Target?> {
        val result: MutableSet<Target?> = LinkedHashSet<Target?>()
        val callback: ThreadSafeOutputFormatterCallback<Target?> =
            object : ThreadSafeOutputFormatterCallback<Target?>() {
                @kotlin.jvm.Synchronized
                public override fun processOutput(partialResult: Iterable<Target?>) {
                    Iterables.addAll<Target?>(result, partialResult)
                }
            }
        getQueryEnvironment().use { env ->
            try {
                env.evaluateQuery(env.transformParsedQuery(QueryParser.parse(query, env)), callback)
            } catch (e: IOException) {
                // Should be impossible since the callback we passed in above doesn't throw IOException.
                throw IllegalStateException(e)
            } catch (e: QuerySyntaxException) {
                // Expect valid query syntax in tests.
                throw IllegalArgumentException(e)
            }
        }
        return result
    }

    override fun getToolsRepository(): RepositoryName? {
        return toolsRepository
    }

    override fun getLabel(target: Target): String {
        return target.getLabel().toString()
    }

    override fun addModule(key: ModuleKey?, vararg moduleFileLines: String?) {
        registry.addModule(key, moduleFileLines)
    }

    private fun initTargetPatternEvaluator(ruleClassProvider: ConfiguredRuleClassProvider) {
        this.toolsRepository = ruleClassProvider.getToolsRepository()
        if (skyframeExecutor != null) {
            cleanUp()
        }
        skyframeExecutor = createSkyframeExecutor(ruleClassProvider)
        val packageOptions: PackageOptions = Options.getDefaults<O>(PackageOptions::class.java)

        packageOptions.setDefaultVisibility(RuleVisibility.PRIVATE)
        packageOptions.setShowLoadingProgress(true)
        packageOptions.setGlobbingThreads(7)
        packageOptions.setPackagePath(ImmutableList.of<E?>(rootDirectory.getPathString()))
        packageOptions.setLazyMacroExpansionPackages(lazyMacroExpansionPackages)

        val buildLanguageOptions: BuildLanguageOptions = Options.getDefaults<O>(BuildLanguageOptions::class.java)
        buildLanguageOptions.setExperimentalGoogleLegacyApi(!analysisMock.isThisBazel)
        // TODO(b/256127926): Delete once flipped.
        buildLanguageOptions.setExperimentalEnableSclDialect(true)
        buildLanguageOptions.setExperimentalDormantDeps(true)

        val buildFilesByPriority: ImmutableList<BuildFileName?>? = skyframeExecutor.getBuildFilesByPriority()
        val packageLocator: PathPackageLocator? =
            if (useVirtualSourceRoot())
                PathPackageLocator.createWithoutExistenceCheck( /* outputBase= */
                    null,
                    ImmutableList.of<E?>(directories.getVirtualSourceRoot()),
                    buildFilesByPriority
                )
            else
                PathPackageLocator.create(
                    directories.getOutputBase(),
                    packageOptions.getPackagePath(),
                    getReporter(),
                    directories.getWorkspace().asFragment(),
                    rootDirectory,
                    buildFilesByPriority
                )
        try {
            skyframeExecutor.sync(
                getReporter(),
                packageLocator,
                UUID.randomUUID(),
                ImmutableMap.of<K?, V?>(),
                TimestampGranularityMonitor(BlazeClock.instance()),
                QuiescingExecutorsImpl.forTesting(),
                FakeOptions.builder().put(packageOptions).put(buildLanguageOptions).build(),  /* commandName= */
                "query",  /* commandExecutes= */
                false
            )
        } catch (e: InterruptedException) {
            throw IllegalStateException(e)
        } catch (e: AbruptExitException) {
            throw IllegalStateException(e)
        }
        pkgManager = skyframeExecutor.getPackageManager()
        targetParser = SkyframeTargetPatternEvaluator(skyframeExecutor)
    }

    override fun useRuleClassProvider(ruleClassProvider: ConfiguredRuleClassProvider) {
        initTargetPatternEvaluator(ruleClassProvider)
    }

    fun setSyscallCache(syscallCache: SyscallCache?) {
        this.delegatingSyscallCache.setDelegate(syscallCache)
    }

    fun setLazyMacroExpansionPackages(lazyMacroExpansionPackages: LazyMacroExpansionPackages?) {
        this.lazyMacroExpansionPackages = lazyMacroExpansionPackages
    }

    protected fun createSkyframeExecutor(ruleClassProvider: ConfiguredRuleClassProvider?): SkyframeExecutor {
        val extraPrecomputedValues: ImmutableList<PrecomputedValue.Injected?> =
            ImmutableList.builder<PrecomputedValue.Injected?>()
                .addAll(analysisMock.precomputedValues)
                .add(
                    PrecomputedValue.injected(
                        ModuleFileFunction.REGISTRIES, ImmutableSet.of<E?>(registry.getUrl())
                    )
                )
                .build()
        val pkgFactory: PackageFactory? =
            (TestPackageFactoryBuilderFactory.getInstance()
                .builder(directories) as PackageFactoryBuilderWithSkyframeForTesting)
                .setExtraSkyFunctions(analysisMock.getSkyFunctions(directories))
                .setExtraPrecomputeValues(extraPrecomputedValues)
                .build(ruleClassProvider, fileSystem)
        val skyframeExecutor: SkyframeExecutor =
            BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
                .setPkgFactory(pkgFactory)
                .setFileSystem(fileSystem)
                .setDirectories(directories)
                .setActionKeyContext(actionKeyContext)
                .setExtraSkyFunctions(analysisMock.getSkyFunctions(directories))
                .setSyscallCache(delegatingSyscallCache)
                .build()
        skyframeExecutor.injectExtraPrecomputedValues(extraPrecomputedValues)
        SkyframeExecutorTestHelper.process(skyframeExecutor)
        return skyframeExecutor
    }

    @Throws(Exception::class)
    override fun assertPackageNotLoaded(packageName: String?) {
        val evaluator: MemoizingEvaluator = skyframeExecutor.getEvaluator()
        val key: SkyKey? = PackageIdentifier.createInMainRepo(packageName)
        check(
            !(evaluator.getExistingValue(key) != null
                    || evaluator.getExistingErrorForTesting(key) != null)
        ) { "Package was loaded: " + packageName }
    }

    override fun getModuleRoot(): Path? {
        return moduleRoot
    }

    /**
     * A wrapper to maintain an ordered copy of set of targets which also respect equality rules
     * defined by [ThreadSafeMutableSet].
     */
    private class OrderedThreadSafeImmutableSet(env: QueryEnvironment<Target?>, targets: MutableSet<Target?>) :
        AbstractSet<Target?>() {
        private val targetSet: ThreadSafeMutableSet<Target?>?
        private val orderedTargetList: MutableList<Target?>

        init {
            this.targetSet = env.createThreadSafeMutableSet()
            this.orderedTargetList = ArrayList<Target?>(targets.size)

            // The order is determined by implementation of iterator on the source set of targets, which
            // can be deterministic or non-deterministic.
            for (target in targets) {
                if (targetSet.add(target)) {
                    orderedTargetList.add(target)
                }
            }
        }

        override fun iterator(): MutableIterator<Target?>? {
            return orderedTargetList.iterator()
        }

        override fun size(): Int {
            return targetSet.size
        }

        override fun add(element: Target?): Boolean {
            throw IllegalStateException("Add operation on immutable set is not supported.")
        }

        override fun contains(obj: Any?): Boolean {
            return targetSet.contains(obj)
        }

        override fun remove(obj: Any?): Boolean {
            throw IllegalStateException("Remove operation on immutable set is not supported.")
        }
    }

    companion object {
        private const val FAKE_INSTALL_MD5_STRING = "abcedf1234567890abcedf1234567890"

        @Throws(QueryException::class, InterruptedException::class)
        fun evaluateQuery(
            query: String?, env: AbstractBlazeQueryEnvironment<Target?>
        ): ResultAndTargets<Target?> {
            val callback: AggregateAllOutputFormatterCallback<Target?, *> =
                QueryUtil.newOrderedAggregateAllOutputFormatterCallback<Target?>(env)
            val queryEvalResult: QueryEvalResult?
            try {
                queryEvalResult =
                    env.evaluateQuery(env.transformParsedQuery(QueryParser.parse(query, env)), callback)
            } catch (e: IOException) {
                // Should be impossible since AggregateAllOutputFormatterCallback doesn't throw IOException.
                throw IllegalStateException(e)
            } catch (e: QuerySyntaxException) {
                // Expect valid query syntax in tests.
                throw IllegalArgumentException(e)
            }
            return ResultAndTargets<Target?>(
                queryEvalResult, OrderedThreadSafeImmutableSet(env, callback.result)
            )
        }
    }
}
