// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.Lists
import com.google.devtools.build.lib.analysis.AnalysisResult
import com.google.devtools.build.lib.query2.engine.QueryException
import com.google.devtools.build.lib.query2.engine.QueryParser
import com.google.devtools.build.lib.query2.engine.QuerySyntaxException
import org.junit.After
import java.lang.reflect.Method
import java.util.*
import java.util.function.Function

/**
 * [QueryHelper] for queries that need the analysis phase. Big warts: uses an [ ] to do analysis before query, but [AnalysisTestCase] is meant to be
 * inherited from, not composed. In particular, means that @Before and @After annotations of [ ] must be run manually. @BeforeClass and @AfterClass are completely ignored for
 * now.
 */
abstract class PostAnalysisQueryHelper<T> : AbstractQueryHelper<T?>() {
    protected var parserPrefix: PathFragment? = null
    protected var analysisHelper: AnalysisHelper? = null
    var isWholeTestUniverse: Boolean = false
        private set

    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        parserPrefix = PathFragment.EMPTY_FRAGMENT
        this.isWholeTestUniverse = false
        this.analysisHelper = AnalysisHelper()
        // Reverse the @Before method list, so that superclass is called before subclass.
        for (method in Lists.reverse<Method>(getMethodsAnnotatedWith(AnalysisHelper::class.java, Before::class.java))) {
            method.invoke(analysisHelper)
        }
        val mockToolsConfig: MockToolsConfig = analysisHelper!!.mockToolsConfig
        MockProtoSupport.setup(mockToolsConfig)
        MockObjcSupport.setup(mockToolsConfig)
    }

    override fun cleanUp() {
        for (method in getMethodsAnnotatedWith(AnalysisHelper::class.java, After::class.java)) {
            try {
                method.invoke(analysisHelper)
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException(e)
            }
        }
    }

    val mockToolsConfig: MockToolsConfig
        get() = analysisHelper!!.mockToolsConfig

    fun setSyscallCache(syscallCache: SyscallCache?) {
        this.analysisHelper.setSyscallCache(syscallCache)
    }

    val universeScopeAsStringList: ImmutableList<String?>
        get() = universeScope.getConstantValueMaybe().get()

    override fun setUniverseScope(universeScope: String) {
        if (!this.isWholeTestUniverse) {
            super.setUniverseScope(universeScope)
        }
    }

    fun setWholeTestUniverseScope(universeScope: String) {
        super.setUniverseScope(universeScope)
        this.isWholeTestUniverse = true
    }

    override fun getRootDirectory(): Path {
        return analysisHelper!!.rootDirectory
    }

    override fun getIgnoredSubdirectoriesFile(): PathFragment {
        return PathFragment.EMPTY_FRAGMENT
    }

    val skyframeExecutor: SkyframeExecutor
        get() = analysisHelper!!.skyframeExecutor

    val packageManager: PackageManager
        get() = analysisHelper!!.packageManager

    @Throws(IOException::class)
    override fun clearAllFiles() {
        analysisHelper!!.rootDirectory.deleteTree()
    }

    @Throws(Exception::class)
    override fun useRuleClassProvider(ruleClassProvider: ConfiguredRuleClassProvider) {
        analysisHelper!!.useRuleClassProvider(ruleClassProvider)
    }

    @Throws(IOException::class)
    override fun writeFile(fileName: String?, vararg lines: String?) {
        analysisHelper
            .getScratch()
            .file(getRootDirectory().getRelative(fileName).getPathString(), lines)
    }

    val scratch: Scratch
        get() = analysisHelper.getScratch()

    fun turnOffFailFast() {
        analysisHelper!!.reporter.removeHandler(FoundationTestCase.failFastHandler)
    }

    @Throws(IOException::class)
    override fun overwriteFile(fileName: String?, vararg lines: String?) {
        analysisHelper
            .getScratch()
            .overwriteFile(getRootDirectory().getRelative(fileName).getPathString(), lines)
    }

    @Throws(IOException::class)
    override fun ensureSymbolicLink(link: String?, target: String?) {
        val rootDirectory: Path = getRootDirectory()
        val linkPath: Path = rootDirectory.getRelative(link)
        val targetPath: Path? = rootDirectory.getRelative(target)
        linkPath.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.ensureSymbolicLink(linkPath, targetPath)
    }

    override fun getQueryEnvironment(): QueryEnvironment<T?>? {
        throw UnsupportedOperationException()
    }

    @Throws(Exception::class)
    fun getPostAnalysisQueryEnvironment(
        universe: MutableCollection<String?>
    ): PostAnalysisQueryEnvironment<T?> {
        return getPostAnalysisQueryEnvironment(universe, ImmutableList.of<String?>())
    }

    @Throws(Exception::class)
    fun getPostAnalysisQueryEnvironment(
        universe: MutableCollection<String?>, aspects: Iterable<String?>
    ): PostAnalysisQueryEnvironment<T?> {
        if (ImmutableList.copyOf<String?>(universe)
            == ImmutableList.of<String?>(PostAnalysisQueryTest.Companion.DEFAULT_UNIVERSE)
        ) {
            throw QueryException(
                "Tests must set universe scope by either having parsable labels in each query expression "
                        + "or setting explicitly through query helper.",
                Query.Code.QUERY_UNKNOWN
            )
        }
        val analysisResult: AnalysisResult =
            analysisHelper!!.update(ImmutableList.copyOf<String?>(aspects), *universe.toTypedArray<String?>())
        val walkableGraph: WalkableGraph =
            SkyframeExecutorWrappingWalkableGraph.of(analysisHelper!!.skyframeExecutor)
        val transitiveConfigurations: ImmutableMap<String?, BuildConfigurationValue?> =
            getTransitiveConfigurations(
                analysisHelper!!.skyframeExecutor.getTransitiveConfigurationKeys(), walkableGraph
            )

        return getPostAnalysisQueryEnvironment(
            walkableGraph,
            TopLevelConfigurations(analysisResult.getTopLevelTargetsWithConfigs()),
            transitiveConfigurations,
            analysisResult.getAspectsMap()
        )
    }

    /**
     * Returns a [PostAnalysisQueryEnvironment] suitable for tests.
     * 
     * @param walkableGraph the Skyframe graph containing all configured targets that queries can
     * search over
     * @param topLevelConfigurations the configurations used to build the top-level targets in a
     * query's universe scope
     * @param transitiveConfigurations all configurations available in the build graph (including
     * those produced by configuration transitions in the top-level targets' transitive deps),
     * keyed by the configurations' checksums
     * @param topLevelAspects the top-level aspects to apply
     */
    @Throws(InterruptedException::class)
    protected abstract fun getPostAnalysisQueryEnvironment(
        walkableGraph: WalkableGraph?,
        topLevelConfigurations: TopLevelConfigurations?,
        transitiveConfigurations: ImmutableMap<String?, BuildConfigurationValue?>?,
        topLevelAspects: ImmutableMap<AspectKeyCreator.AspectKey?, ConfiguredAspect?>?
    ): PostAnalysisQueryEnvironment<T?>

    @Throws(Exception::class)
    override fun evaluateQuery(query: String?): ResultAndTargets<T?> {
        val env: PostAnalysisQueryEnvironment<T?> =
            getPostAnalysisQueryEnvironment(this.universeScopeAsStringList)
        val callback: AggregateAllOutputFormatterCallback<T?, *> =
            QueryUtil.newOrderedAggregateAllOutputFormatterCallback<T?>(env)
        val queryEvalResult: QueryEvalResult?
        try {
            queryEvalResult =
                env.evaluateQuery(env.transformParsedQuery(QueryParser.parse(query, env)), callback)
        } catch (e: IOException) {
            // Should be impossible since AggregateAllOutputFormatterCallback doesn't throw IOException.
            throw IllegalStateException(e)
        } catch (e: QuerySyntaxException) {
            // Expect the user to provide valid syntax.
            throw IllegalArgumentException(e)
        }
        val targets: MutableSet<T?> = env.createThreadSafeMutableSet()
        targets.addAll(callback.result)
        return ResultAndTargets<T?>(queryEvalResult, targets)
    }

    @Throws(Exception::class)
    override fun assertPackageNotLoaded(packageName: String?) {
    }

    @Throws(Exception::class)
    fun useConfiguration(vararg args: String?) {
        analysisHelper.useConfiguration(*args)
    }

    override fun addModule(key: ModuleKey?, vararg moduleFileLines: String?) {
        analysisHelper.addModule(key, *moduleFileLines)
    }

    override fun getModuleRoot(): Path {
        return analysisHelper!!.moduleRoot
    }

    override fun setMainRepoTargetParser(mapping: RepositoryMapping) {
        this.mainRepoTargetParser =
            Parser(
                parserPrefix,
                RepositoryName.MAIN,
                mapping.withAdditionalMappings(AbstractQueryHelper.Companion.DEFAULT_MAIN_REPO_MAPPING)
            )
    }

    /** Helper class that provides a framework for testing `PostAnalysisQueryHelper`  */
    class AnalysisHelper : AnalysisTestCase() {
        val rootDirectory: Path

        val moduleRoot: Path

        @Throws(Exception::class)
        public override fun update(aspects: ImmutableList<String?>?, vararg labels: String?): AnalysisResult {
            return super.update(aspects, *labels)
        }

        val skyframeExecutor: SkyframeExecutor

        val packageManager: PackageManager

        val mockToolsConfig: MockToolsConfig

        val reporter: Reporter?

        private fun setSyscallCache(syscallCache: SyscallCache?) {
            this.delegatingSyscallCache.setDelegate(syscallCache)
        }

        private fun addModule(key: ModuleKey?, vararg moduleFileLines: String?) {
            registry.addModule(key, moduleFileLines)
        }

        @get:Throws(InterruptedException::class)
        val targetConfiguration: BuildConfigurationValue
            get() = super.targetConfiguration

        @Throws(Exception::class)
        public override fun useRuleClassProvider(ruleClassProvider: ConfiguredRuleClassProvider) {
            super.useRuleClassProvider(ruleClassProvider)
            update()
        }
    }

    companion object {
        @Throws(InterruptedException::class)
        private fun getTransitiveConfigurations(
            transitiveConfigurationKeys: MutableCollection<SkyKey?>?, graph: WalkableGraph
        ): ImmutableMap<String?, BuildConfigurationValue?> {
            // BuildConfigurationKey and BuildConfigurationValue should be 1:1
            // so merge function intentionally omitted
            return graph.getSuccessfulValues(transitiveConfigurationKeys).values().stream()
                .map({ obj: Any? -> BuildConfigurationValue::class.java.cast(obj) })
                .sorted(Comparator.comparing<T?, U?>(BuildConfigurationValue::checksum))
                .collect(
                    ImmutableMap.toImmutableMap<T?, K?, V?>(
                        BuildConfigurationValue::checksum,
                        Function.identity<T?>()
                    )
                )
        }

        /**
         * Returns all methods with the given annotation for the given class in the entire hierarchy.
         * Methods are returned in hierarchy order: superclass after subclass.
         */
        private fun getMethodsAnnotatedWith(
            type: Class<*>, annotation: Class<out Annotation?>?
        ): MutableList<Method> {
            val methods: MutableList<Method> = ArrayList<Method>()
            var klass = type
            // need to iterate through hierarchy in order to retrieve methods from above the current
            // instance.
            while (klass != Any::class.java) {
                // iterate though the list of methods declared in the class represented by klass variable, and
                // add those annotated with the specified annotation
                val allMethods: MutableList<Method> =
                    ArrayList<Method>(Arrays.asList<Method?>(*klass.getDeclaredMethods()))
                for (method in allMethods) {
                    if (method.isAnnotationPresent(annotation)) {
                        methods.add(method)
                    }
                }
                // move to the upper class in the hierarchy in search for more methods
                klass = klass.getSuperclass()
            }
            return methods
        }
    }
}
