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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.Action

/** The common code that's shared between various builder tests.  */
abstract class TimestampBuilderTestCase : FoundationTestCase() {
    protected var clock: com.google.devtools.build.lib.clock.Clock =
        com.google.devtools.build.lib.clock.BlazeClock.instance()
    protected var tsgm: TimestampGranularityMonitor? = null
    protected var differencer: RecordingDifferencer = SequencedRecordingDifferencer()
    private var actions: MutableSet<ActionAnalysisMetadata?>? = null
    protected var options: OptionsParser? = null

    protected val actionKeyContext: ActionKeyContext = ActionKeyContext()

    @Before
    @Throws(java.lang.Exception::class)
    fun initialize() {
        options =
            OptionsParser.builder()
                .optionsClasses(
                    KeepGoingOption::class.java,
                    KeepStateAfterBuildOption::class.java,
                    BuildRequestOptions::class.java,
                    CoreOptions::class.java,
                    ExecutionOptions::class.java
                )
                .build()
        options.parse()
        inMemoryCache = InMemoryActionCache()
        tsgm = TimestampGranularityMonitor(clock)
        actions = LinkedHashSet<ActionAnalysisMetadata?>()
        actionTemplateExpansionFunction = ActionTemplateExpansionFunction(actionKeyContext)
    }

    protected fun clearActions() {
        actions!!.clear()
    }

    protected fun <T : ActionAnalysisMetadata?> registerAction(action: T?): T? {
        actions!!.add(action)
        val actionLookupData: ActionLookupData? =
            ActionLookupData.create(ACTION_LOOKUP_KEY, actions!!.size - 1)
        for (output in action.getOutputs()) {
            (output as Artifact.DerivedArtifact).setGeneratingActionKey(actionLookupData)
        }
        return action
    }

    @Throws(java.lang.Exception::class)
    protected fun createBuilder(actionCache: ActionCache?): BuilderWithResult {
        return createBuilder(actionCache, 1,  /*keepGoing=*/false)
    }

    /** Create a ParallelBuilder with a DatabaseDependencyChecker using the specified ActionCache.  */
    @Throws(java.lang.Exception::class)
    protected fun createBuilder(
        actionCache: ActionCache?, threadCount: Int, keepGoing: Boolean
    ): BuilderWithResult {
        return createBuilder(actionCache, threadCount, keepGoing, EvaluationProgressReceiver.NULL)
    }

    @Throws(java.lang.Exception::class)
    protected fun createBuilder(
        actionCache: ActionCache?,
        threadCount: Int,
        keepGoing: Boolean,
        evaluationProgressReceiver: EvaluationProgressReceiver?
    ): BuilderWithResult {
        val pkgLocator: AtomicReference<PathPackageLocator?> =
            AtomicReference<PathPackageLocator?>(
                PathPackageLocator(
                    outputBase,
                    com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(rootDirectory)),
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                )
            )
        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(rootDirectory, outputBase, outputBase),
                rootDirectory,
                TestConstants.PRODUCT_NAME
            )
        val externalFilesHelper: ExternalFilesHelper? =
            ExternalFilesHelper.createForTesting(
                pkgLocator,
                ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
                directories
            )
        differencer = SequencedRecordingDifferencer()

        val statusReporter: ActionExecutionStatusReporter? =
            ActionExecutionStatusReporter.create(StoredEventHandler(), eventBus)
        val evaluatorRef: AtomicReference<InMemoryMemoizingEvaluator?> = AtomicReference<InMemoryMemoizingEvaluator?>()
        val skyframeActionExecutor: SkyframeActionExecutor =
            SkyframeActionExecutor(
                actionKeyContext,
                MetadataConsumerForMetrics.NO_OP,
                MetadataConsumerForMetrics.NO_OP,
                AtomicReference<V?>(statusReporter),  /* sourceRootSupplier= */
                com.google.common.collect.ImmutableList::of,
                SyscallCache.NO_CACHE,
                { k -> ThreadStateReceiver.NULL_INSTANCE },
                { key -> evaluatorRef.get().getExistingValue(key) as ActionLookupValue? })

        val actionOutputBase: Path = scratch.dir("/usr/local/google/_blaze_jrluser/FAKEMD5/action_out/")
        skyframeActionExecutor.setActionLogBufferPathGenerator(
            ActionLogBufferPathGenerator(actionOutputBase)
        )

        val cache: InputMetadataProvider =
            SingleBuildFileCache(
                rootDirectory.getPathString(),
                PathFragment.create("dummy-output-path"),
                scratch.getFileSystem(),
                SyscallCache.NO_CACHE
            )
        skyframeActionExecutor.configure(
            cache,
            ActionInputPrefetcher.NONE,
            DiscoveredModulesPruner.DEFAULT,  /* actionExecutionSalt= */
            "",  /* maxStdoutErrBytes= */
            Int.Companion.MAX_VALUE
        )

        val evaluator: InMemoryMemoizingEvaluator =
            InMemoryMemoizingEvaluator(
                com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
                    .put(
                        FileStateKey.FILE_STATE,
                        FileStateFunction({ tsgm }, SyscallCache.NO_CACHE, externalFilesHelper)
                    )
                    .put(SkyFunctions.FILE, FileFunction(pkgLocator, directories))
                    .put(
                        Artifact.ARTIFACT,
                        ArtifactFunction(
                            { true },
                            MetadataConsumerForMetrics.NO_OP,
                            SyscallCache.NO_CACHE,
                            skyframeActionExecutor,
                            { RemoteAnalysisCacheDeps.createDisabled() })
                    )
                    .put(
                        SkyFunctions.ACTION_EXECUTION,
                        ActionExecutionFunction(
                            ActionRewindStrategy(
                                skyframeActionExecutor,
                                BugReporter.defaultInstance(),
                                { RemoteAnalysisCacheDeps.createDisabled() }),
                            skyframeActionExecutor,
                            { evaluatorRef.get() },
                            directories,
                            { tsgm },
                            BugReporter.defaultInstance(),
                            { RemoteAnalysisCacheDeps.createDisabled() },
                            { null })
                    )
                    .put(SkyFunctions.PACKAGE, PackageFunction.newBuilder().build())
                    .put(
                        SkyFunctions.PACKAGE_LOOKUP,
                        PackageLookupFunction(
                            null,
                            CrossRepositoryLabelViolationStrategy.ERROR,
                            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                        )
                    )
                    .put(
                        SkyFunctions.ACTION_TEMPLATE_EXPANSION,
                        DelegatingActionTemplateExpansionFunction()
                    )
                    .put(SkyFunctions.ARTIFACT_NESTED_SET, ArtifactNestedSetFunction({ null }))
                    .buildOrThrow(),
                differencer,
                evaluationProgressReceiver,
                GraphInconsistencyReceiver.THROWING,
                EventFilter.FULL_STORAGE,
                EmittedEventState(),  /* keepEdges= */
                true,  /* usePooledInterning= */
                true
            )
        evaluatorRef.set(evaluator)
        PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID())
        PrecomputedValue.ACTION_ENV.set(differencer, com.google.common.collect.ImmutableMap.of<K?, V?>())
        PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get())

        return object : BuilderWithResult {
            var latestResult: EvaluationResult<SkyValue?>? = null

            override fun getLatestResult(): EvaluationResult<SkyValue?>? {
                return com.google.common.base.Preconditions.checkNotNull<EvaluationResult<SkyValue?>?>(latestResult)
            }

            @Throws(
                ActionConflictException::class,
                java.lang.InterruptedException::class,
                Actions.ArtifactGeneratedByOtherRuleException::class
            )
            fun setGeneratingActions() {
                if (evaluator.getExistingValue(ACTION_LOOKUP_KEY) == null) {
                    val generatingActions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> =
                        com.google.common.collect.ImmutableList.copyOf<ActionAnalysisMetadata?>(actions)
                    Actions.assignOwnersAndThrowIfConflictToleratingSharedActions(
                        actionKeyContext, generatingActions, ACTION_LOOKUP_KEY
                    )
                    differencer.inject(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            ACTION_LOOKUP_KEY, Delta.justNew(BasicActionLookupValue(generatingActions))
                        )
                    )
                }
            }

            @Throws(BuildFailedException::class, java.lang.InterruptedException::class, TestExecException::class)
            public override fun buildArtifacts(
                reporter: com.google.devtools.build.lib.events.Reporter?,
                artifacts: MutableSet<Artifact?>,
                parallelTests: MutableSet<ConfiguredTarget?>?,
                exclusiveTests: MutableSet<ConfiguredTarget?>?,
                targetsToBuild: MutableSet<ConfiguredTarget?>?,
                targetsToSkip: MutableSet<ConfiguredTarget?>?,
                aspects: com.google.common.collect.ImmutableSet<AspectKey?>?,
                executor: Executor?,
                options: OptionsProvider?,
                lastExecutionTimeRange: com.google.common.collect.Range<Long?>?,
                topLevelArtifactContext: TopLevelArtifactContext?,
                outputChecker: OutputChecker?
            ) {
                latestResult = null
                skyframeActionExecutor.prepareForExecution(
                    reporter,
                    executor,
                    options,
                    ActionCacheChecker(
                        actionCache,
                        null,
                        actionKeyContext,
                        ALWAYS_EXECUTE_FILTER,
                        ProxyMetadataFactory.NO_PROXIES,
                        null
                    ),
                    ActionOutputDirectoryHelper.createForTesting(),
                    LocalOutputService(directories),  /* keepStateAfterBuild= */
                    true
                )
                skyframeActionExecutor.setActionExecutionProgressReportingObjects(
                    { "" }, EMPTY_COMPLETION_RECEIVER
                )

                val keys: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()
                for (artifact in artifacts) {
                    keys.add(Artifact.key(artifact))
                }

                try {
                    setGeneratingActions()
                } catch (e: ActionConflictException) {
                    throw java.lang.IllegalStateException(e)
                } catch (e: Actions.ArtifactGeneratedByOtherRuleException) {
                    throw java.lang.IllegalStateException(e)
                }

                val evaluationContext: EvaluationContext? =
                    EvaluationContext.newBuilder()
                        .setKeepGoing(keepGoing)
                        .setParallelism(threadCount)
                        .setEventHandler(reporter)
                        .build()
                val result: EvaluationResult<SkyValue?> = evaluator.evaluate(keys, evaluationContext)
                this.latestResult = result

                if (result.hasError()) {
                    var hasCycles = false
                    for (entry in result.errorMap().entrySet()) {
                        val cycles: Iterable<CycleInfo?> = entry.value.getCycleInfo()
                        hasCycles = hasCycles or !com.google.common.collect.Iterables.isEmpty(cycles)
                    }
                    if (hasCycles) {
                        throw BuildFailedException(CYCLE_MSG, createDetailedExitCode(Code.CYCLE))
                    } else if (result.errorMap().isEmpty() || keepGoing) {
                        // The specific detailed code used here doesn't matter.
                        throw BuildFailedException(
                            null, createDetailedExitCode(Code.NON_ACTION_EXECUTION_FAILURE)
                        )
                    } else {
                        SkyframeErrorProcessor.rethrow(
                            com.google.common.base.Preconditions.checkNotNull<T?>(result.getError().getException()),
                            BugReporter.defaultInstance(),
                            result
                        )
                    }
                }
            }
        }
    }

    /** A non-persistent cache.  */
    protected var inMemoryCache: InMemoryActionCache? = null

    protected var actionTemplateExpansionFunction: SkyFunction? = null

    /** A class that records an event.  */
    protected class Button : java.lang.Runnable {
        var pressed: Boolean = false

        override fun run() {
            pressed = true
        }
    }

    /** A class that counts occurrences of an event.  */
    internal class Counter : java.lang.Runnable {
        var count: Int = 0

        override fun run() {
            count++
        }
    }

    protected fun createSourceArtifact(name: String?): Artifact {
        return createSourceArtifact(scratch.getFileSystem(), name)
    }

    protected fun createDerivedArtifact(name: String?): Artifact {
        return createDerivedArtifact(scratch.getFileSystem(), name)
    }

    /** Creates and returns a new "amnesiac" builder based on the amnesiac cache.  */
    @Throws(java.lang.Exception::class)
    protected fun amnesiacBuilder(): BuilderWithResult {
        return createBuilder(AMNESIAC_CACHE)
    }

    /** Creates and returns a new caching builder based on the [.inMemoryCache].  */
    @Throws(java.lang.Exception::class)
    protected fun cachingBuilder(): BuilderWithResult {
        return createBuilder(inMemoryCache)
    }

    /** [Builder] that saves its most recent [EvaluationResult].  */
    protected interface BuilderWithResult : Builder {
        val latestResult: EvaluationResult<SkyValue?>?
    }

    /**
     * Creates a TestAction from 'inputs' to 'outputs', and a new button, such that executing the
     * action causes the button to be pressed. The button is returned.
     */
    protected fun createActionButton(
        inputs: NestedSet<Artifact?>,
        outputs: com.google.common.collect.ImmutableSet<Artifact?>?
    ): Button {
        val button: Button = com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button()
        registerAction<T?>(TestAction(button, inputs, outputs))
        return button
    }

    /**
     * Creates a TestAction from 'inputs' to 'outputs', and a new counter, such that executing the
     * action causes the counter to be incremented. The counter is returned.
     */
    protected fun createActionCounter(
        inputs: NestedSet<Artifact?>, outputs: com.google.common.collect.ImmutableSet<Artifact?>?
    ): Counter {
        val counter: Counter = com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Counter()
        registerAction<T?>(TestAction(counter, inputs, outputs))
        return counter
    }

    @Throws(
        BuildFailedException::class,
        AbruptExitException::class,
        java.lang.InterruptedException::class,
        TestExecException::class
    )
    protected fun buildArtifacts(builder: Builder?, vararg artifacts: Artifact?) {
        buildArtifacts(
            builder,
            com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, rootDirectory),
            artifacts
        )
    }

    @Throws(
        BuildFailedException::class,
        AbruptExitException::class,
        java.lang.InterruptedException::class,
        TestExecException::class
    )
    protected fun buildArtifacts(builder: Builder, executor: Executor?, vararg artifacts: Artifact?) {
        tsgm.setCommandStartTime()
        val artifactsToBuild: MutableSet<Artifact?> = com.google.common.collect.Sets.newHashSet<Artifact?>(*artifacts)
        try {
            builder.buildArtifacts(
                reporter,
                artifactsToBuild,
                null,
                null,
                null,
                null,
                null,
                executor,
                options,
                null,
                null,
                OutputChecker.TRUST_LOCAL_ONLY
            )
        } finally {
            tsgm.waitForTimestampGranularity(reporter.getOutErr())
        }
    }

    /** [TestAction] that copies its single input to its single output.  */
    protected class CopyingAction internal constructor(
        effect: java.lang.Runnable?,
        input: Artifact?,
        output: Artifact
    ) : TestAction(
        effect,
        NestedSetBuilder.create(Order.STABLE_ORDER, input),
        com.google.common.collect.ImmutableSet.of<E?>(output)
    ) {
        @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
        override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
            val actionResult: ActionResult = super.execute(actionExecutionContext)
            try {
                FileSystemUtils.copyFile(
                    getInputs().getSingleton().getPath(),
                    com.google.common.collect.Iterables.getOnlyElement<T?>(getOutputs()).getPath()
                )
            } catch (e: IOException) {
                throw java.lang.IllegalStateException(e)
            }
            return actionResult
        }
    }

    /** In-memory [ActionCache] backed by a HashMap  */
    protected class InMemoryActionCache : ActionCache {
        private val actionCache: MutableMap<String?, Entry?> = HashMap<String?, Entry?>()

        @kotlin.jvm.Synchronized
        public override fun put(key: String?, entry: ActionCache.Entry?) {
            actionCache.put(key, entry)
        }

        @kotlin.jvm.Synchronized
        public override fun get(key: String?): Entry? {
            return actionCache.get(key)
        }

        @kotlin.jvm.Synchronized
        public override fun remove(key: String?) {
            actionCache.remove(key)
        }

        public override fun removeIf(predicate: java.util.function.Predicate<Entry?>?) {
            actionCache.values.removeIf(predicate)
        }

        @kotlin.jvm.Synchronized
        fun reset() {
            actionCache.clear()
        }

        public override fun save(): Long {
            // safe to ignore
            return 0
        }

        public override fun clear() {
            // safe to ignore
        }

        public override fun trim(threshold: Float, maxAge: java.time.Duration?): ActionCache? {
            throw java.lang.UnsupportedOperationException()
        }

        public override fun dump(out: PrintStream) {
            out.println("In-memory action cache has " + actionCache.size + " records")
        }

        public override fun size(): Int {
            return actionCache.size
        }

        public override fun accountHit() {
            // Not needed for these tests.
        }

        public override fun accountMiss(reason: MissReason?) {
            // Not needed for these tests.
        }

        public override fun mergeIntoActionCacheStatistics(builder: ActionCacheStatistics.Builder?) {
            // Not needed for these tests.
        }

        public override fun resetStatistics() {
            // Not needed for these tests.
        }
    }

    private inner class DelegatingActionTemplateExpansionFunction : SkyFunction {
        @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
        public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue {
            return actionTemplateExpansionFunction.compute(skyKey, env)
        }

        public override fun extractTag(skyKey: SkyKey?): String {
            return actionTemplateExpansionFunction.extractTag(skyKey)
        }
    }

    companion object {
        @SerializationConstant
        protected val ACTION_LOOKUP_KEY: ActionLookupKey = InjectedActionLookupKey("action_lookup_key")

        protected val ALWAYS_EXECUTE_FILTER: com.google.common.base.Predicate<Action?> =
            com.google.common.base.Predicates.alwaysTrue<Action?>()
        protected const val CYCLE_MSG: String = "Yarrrr, there be a cycle up in here"

        private fun createSourceArtifact(fs: FileSystem, name: String?): Artifact {
            val root: Path? = fs.getPath(com.google.devtools.build.lib.testutil.TestUtils.tmpDir())
            return ActionsTestUtil.createArtifactWithExecPath(
                ArtifactRoot.asSourceRoot(Root.fromPath(root)), PathFragment.create(name)
            )
        }

        fun createDerivedArtifact(fs: FileSystem, name: String?): Artifact {
            val execRoot: Path? = fs.getPath(com.google.devtools.build.lib.testutil.TestUtils.tmpDir())
            val execPath: PathFragment? = PathFragment.create("out").getRelative(name)
            return DerivedArtifact.create(
                ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out"), execPath, ACTION_LOOKUP_KEY
            )
        }

        protected var emptySet: MutableSet<Artifact?> = mutableSetOf<Artifact?>()
        protected var emptyNestedSet: NestedSet<Artifact?>? = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

        private fun createDetailedExitCode(detailedCode: Code?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setExecution(Execution.newBuilder().setCode(detailedCode))
                    .build()
            )
        }

        private val EMPTY_COMPLETION_RECEIVER: ActionCompletedReceiver = ActionCompletedReceiver { ald -> }
    }
}
