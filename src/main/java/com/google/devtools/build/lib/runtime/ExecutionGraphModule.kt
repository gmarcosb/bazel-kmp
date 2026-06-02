// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime

import com.github.luben.zstd.ZstdOutputStream

/**
 * Blaze module that writes a partial execution graph with performance data. The file will be zstd
 * compressed, length-delimited binary execution_graph.Node protos.
 */
class ExecutionGraphModule : BlazeModule() {
    /** Options for the generated execution graph.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class ExecutionGraphOptions : com.google.devtools.common.options.OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "experimental_enable_execution_graph_log",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
            defaultValue = "false",
            help = ("Enabling this flag makes Blaze write a file of all actions executed during a build. "
                    + "Note that this dump may use a different granularity of actions than other APIs, "
                    + "and may also contain additional information as necessary to reconstruct the "
                    + "full dependency graph in combination with other sources of data.")
        )
        abstract val enableExecutionGraphLog: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_execution_graph_log_path",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
            defaultValue = "",
            help = ("Local path at which the execution path will be written. If this is set, the log will"
                    + " only be written locally, and not to BEP. If this is set when"
                    + " experimental_enable_execution_graph_log is disabled, there will be an error. If"
                    + " this is unset while BEP uploads are disabled and"
                    + " experimental_enable_execution_graph_log is enabled, the log will be written to"
                    + " a local default.")
        )
        abstract val executionGraphLogPath: String

        @get:com.google.devtools.common.options.Option(
            name = "experimental_execution_graph_log_dep_type",
            converter = DependencyInfoConverter::class,
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            defaultValue = "none",
            help = ("Selects what kind of dependency information is reported in the action dump. If 'all',"
                    + " every inter-action edge will be reported.")
        )
        abstract val depType: DependencyInfo?

        @get:com.google.devtools.common.options.Option(
            name = "experimental_execution_graph_log_queue_size",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            defaultValue = "-1",
            help = ("The size of the action dump queue, where actions are kept before writing. Larger"
                    + " sizes will increase peak memory usage, but should decrease queue blocking. -1"
                    + " means unbounded")
        )
        abstract val queueSize: Int

        @get:com.google.devtools.common.options.Option(
            name = "execution_graph_log_queued_bytes_limit",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            defaultValue = "-1",
            help = ("The maximum number of bytes that can be enqueued at a time in the action dump queue."
                    + " -1 means unbounded. Setting this can limit peak memory at the cost of stalling"
                    + " execution threads.")
        )
        abstract val queuedBytesLimit: Int

        @get:com.google.devtools.common.options.Option(
            name = "experimental_execution_graph_enable_edges_from_filewrite_actions",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            defaultValue = "true",
            help = "Handle edges from filewrite actions to their inputs correctly."
        )
        abstract val logFileWriteEdges: Boolean

        @com.google.devtools.common.options.Option(
            name = "experimental_execution_graph_include_change_pruned_actions",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            defaultValue = "false",
            help = "Whether to include change pruned actions in execution graph."
        )
        abstract fun getIncludeChangePrunedActions(): Boolean
    }

    /** What level of dependency information to include in the dump.  */
    enum class DependencyInfo {
        NONE,
        RUNFILES,
        ALL
    }

    /** Converter for dependency information level.  */
    class DependencyInfoConverter : com.google.devtools.common.options.EnumConverter<DependencyInfo?>(
        DependencyInfo::class.java,
        "dependency edge strategy"
    )

    private var includeChangePrunedActions = false
    private var writer: ActionDumpWriter? = null
    private var env: CommandEnvironment? = null
    private var graph: WalkableGraph? = null
    private var nanosToMillis: com.google.devtools.build.lib.clock.BlazeClock.NanosToMillisSinceEpochConverter =
        com.google.devtools.build.lib.clock.BlazeClock.createNanosToMillisSinceEpochConverter()

    // Only relevant for Skymeld: there may be multiple events and we only count the first one.
    private val executionStarted: AtomicBoolean = AtomicBoolean()

    public override fun getCommandOptions(commandName: String): Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
        return if (commandName == "build")
            com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
                ExecutionGraphOptions::class.java
            )
        else
            com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()
    }

    @com.google.common.annotations.VisibleForTesting
    fun setWriter(writer: ActionDumpWriter?) {
        this.writer = writer
    }

    @com.google.common.annotations.VisibleForTesting
    fun setGraph(graph: WalkableGraph?) {
        this.graph = graph
    }

    @com.google.common.annotations.VisibleForTesting
    fun setNanosToMillis(nanosToMillis: com.google.devtools.build.lib.clock.BlazeClock.NanosToMillisSinceEpochConverter) {
        this.nanosToMillis = nanosToMillis
    }

    public override fun beforeCommand(env: CommandEnvironment) {
        this.env = env

        if (env.getCommand().buildPhase().executes()) {
            val options: ExecutionGraphOptions =
                checkNotNull(
                    env.getOptions().getOptions(ExecutionGraphOptions::class.java),
                    "ExecutionGraphOptions must be present for ExecutionGraphModule"
                )
            if (options.enableExecutionGraphLog) {
                env.getEventBus().register(this)
            } else if (!options.executionGraphLogPath.isBlank()) {
                env.getBlazeModuleEnvironment()
                    .exit(
                        AbruptExitException(
                            DetailedExitCode.of(
                                ExitCode.COMMAND_LINE_ERROR,
                                FailureDetail.newBuilder()
                                    .setMessage(
                                        "experimental_execution_graph_log_path cannot be set when"
                                                + " experimental_enable_execution_graph_log is false"
                                    )
                                    .setBuildReport(
                                        BuildReport.newBuilder().setCode(Code.BUILD_REPORT_WRITE_FAILED)
                                    )
                                    .build()
                            )
                        )
                    )
            }

            includeChangePrunedActions = options.getIncludeChangePrunedActions()
        }
    }

    @com.google.common.eventbus.Subscribe
    fun executionPhaseStarting(@Suppress("unused") event: ExecutionStartingEvent?) {
        handleExecutionBegin()
    }

    @com.google.common.eventbus.Subscribe
    fun someExecutionStarted(@Suppress("unused") event: SomeExecutionStartedEvent?) {
        if (executionStarted.compareAndSet( /* expectedValue= */false,  /* newValue= */true)) {
            handleExecutionBegin()
        }
    }

    private fun handleExecutionBegin() {
        if (includeChangePrunedActions) {
            graph = SkyframeExecutorWrappingWalkableGraph.of(env.getSkyframeExecutor())
        }
        try {
            // Defer creation of writer until the start of the execution phase. This is done for two
            // reasons:
            //   - The writer's consumer thread spends 4MB on buffer space, and this is wasted retained
            //     heap during the analysis phase.
            //   - We want to start the writer only when we have the guarantee we'll shut it down in
            //     #buildComplete. It'd be unsound to start the writer before BuildStartingEvent, and
            //     ExecutionStartingEvent definitely postdates that.
            writer = createActionDumpWriter(env)
        } catch (e: InvalidPackagePathSymlinkException) {
            val detailedExitCode: DetailedExitCode =
                DetailedExitCode.of(makeReportUploaderNeedsPackagePathsDetail())
            env.getBlazeModuleEnvironment().exit(AbruptExitException(detailedExitCode, e))
        } catch (e: ActionDumpFileCreationException) {
            val detailedExitCode: DetailedExitCode = DetailedExitCode.of(makeReportWriteFailedDetail())
            env.getBlazeModuleEnvironment().exit(AbruptExitException(detailedExitCode, e))
        } finally {
            env = null
        }
    }

    @com.google.common.eventbus.Subscribe
    fun buildComplete(event: BuildCompleteEvent) {
        try {
            shutdown(event.getResult().buildToolLogCollection)
        } catch (e: java.lang.InterruptedException) {
            java.lang.Thread.currentThread().interrupt()
            // Env might be set to null by a concurrent call to shutdown (via afterCommand).
            val localEnv: CommandEnvironment? = env
            if (localEnv != null) {
                // Inform environment that we were interrupted: this can override the existing exit code
                // in some cases when the environment "finalizes" the exit code.
                localEnv
                    .getBlazeModuleEnvironment()
                    .exit(
                        InterruptedFailureDetails.abruptExitException(
                            "action dump shutdown interrupted", e
                        )
                    )
            }
        }
    }

    /** Records the input discovery time.  */
    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun discoverInputs(event: DiscoveredInputsEvent) {
        val localWriter = writer
        if (localWriter != null) {
            localWriter.enqueue(event)
        }
    }

    /** Record an action that didn't publish any SpawnExecutedEvents.  */
    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionComplete(event: ActionCompletionEvent) {
        actionEvent(
            event.getAction(),
            event.getInputMetadataProvider(),
            event.getRelativeActionStartTimeNanos(),
            event.getFinishTimeNanos()
        )
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    @Throws(java.lang.InterruptedException::class)
    fun actionChangePruned(event: ActionChangePrunedEvent) {
        if (graph == null) {
            return
        }

        if (Actions.getAction(graph, event.actionLookupData()) !is Action) {
            return
        }

        // TODO(chiwang): Handle runfiles for change-pruned actions.
        actionEvent(
            action,  /* inputMetadataProvider= */
            null,
            event.finishTimeNanos(),
            event.finishTimeNanos()
        )
    }

    /**
     * Record an action that was not executed because it was in the (disk) cache. This is needed so
     * that we can calculate correctly the dependencies tree if we have some cached actions in the
     * middle of the critical path.
     */
    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionCached(event: CachedActionEvent) {
        actionEvent(
            event.getAction(),
            event.getInputMetadataProvider(),
            event.getNanoTimeStart(),
            event.getNanoTimeFinish()
        )
    }

    private fun actionEvent(
        action: Action,
        inputMetadataProvider: InputMetadataProvider?,
        nanoTimeStart: Long,
        nanoTimeFinish: Long
    ) {
        val localWriter = writer
        if (localWriter != null) {
            localWriter.enqueue(
                action,
                inputMetadataProvider,
                nanosToMillis.toEpochMillis(nanoTimeStart),
                nanosToMillis.toEpochMillis(nanoTimeFinish)
            )
        }
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionShared(event: SharedActionEvent) {
        val localWriter = writer
        if (localWriter != null) {
            localWriter.actionShared(event)
        }
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun spawnExecuted(event: SpawnExecutedEvent) {
        // Writer might be modified by a concurrent call to shutdown. See b/184943744.
        // It may be possible to get a BuildCompleteEvent before a duplicate Spawn that runs with a
        // dynamic execution strategy, in which case we wouldn't export that Spawn. That's ok, since it
        // didn't affect the latency of the build.
        val localWriter = writer
        if (localWriter != null) {
            localWriter.enqueue(event)
        }
    }

    @Throws(AbruptExitException::class)
    public override fun afterCommand() {
        // Defensively shut down in case we failed to do so under normal operation.
        try {
            shutdown(null)
        } catch (e: java.lang.InterruptedException) {
            throw InterruptedFailureDetails.abruptExitException("action dump shutdown interrupted", e)
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun shutdown(logs: BuildToolLogCollection?) {
        // Writer might be set to null by a concurrent call to shutdown (via afterCommand).
        val localWriter = writer
        try {
            // Writer might never have been set if the execution phase never happened (see
            // executionPhaseStarting).
            if (localWriter != null) {
                localWriter.shutdown(logs)
            }
        } finally {
            writer = null
            env = null
            graph = null
            executionStarted.set(false)
        }
    }

    /** An ActionDumpWriter writes action dump data to a given [OutputStream].  */
    @com.google.common.annotations.VisibleForTesting
    abstract class ActionDumpWriter internal constructor(
        bugReporter: BugReporter,
        eventBus: com.google.common.eventbus.EventBus,
        localLockFreeOutputEnabled: Boolean,
        logFileWriteEdges: Boolean,
        outStream: java.io.OutputStream?,
        depType: DependencyInfo?,
        queueSize: Int,
        queuedBytesLimit: Int
    ) : java.lang.Runnable {
        private fun actionToNode(
            action: Action,
            inputMetadataProvider: InputMetadataProvider?,
            startMillis: Long,
            finishMillis: Long
        ): ExecutionGraph.Node {
            val index: Int = nextIndex.getAndIncrement()
            val node: ExecutionGraph.Node.Builder =
                ExecutionGraph.Node.newBuilder()
                    .setMetrics(
                        ExecutionGraph.Metrics.newBuilder()
                            .setStartTimestampMillis(startMillis)
                            .setDurationMillis((finishMillis - startMillis).toInt())
                            .setProcessMillis((finishMillis - startMillis).toInt())
                    )
                    .setDescription(action.prettyPrint())
                    .setMnemonic(action.getMnemonic())
            if (depType != DependencyInfo.NONE) {
                node.setIndex(index)
            }
            setFieldsFromOwner(node, action.getOwner())

            maybeAddEdges(
                node,
                action.getOutputs(),
                action.getInputs(),
                action,
                inputMetadataProvider,
                startMillis,
                finishMillis - startMillis,
                index
            )

            return node.build()
        }

        private fun toProto(event: SpawnExecutedEvent): ExecutionGraph.Node {
            val nodeBuilder: ExecutionGraph.Node.Builder = ExecutionGraph.Node.newBuilder()
            val index: Int = nextIndex.getAndIncrement()
            val spawn: Spawn = event.getSpawn()
            val startMillis: Long = event.getStartTimeInstant().toEpochMilli()
            var spawnResult: SpawnResult? = event.getSpawnResult()
            nodeBuilder // TODO(vanja) consider switching prettyPrint() to description()
                .setDescription(event.getActionMetadata().prettyPrint())
                .setMnemonic(spawn.getMnemonic())
                .setRunner(spawnResult.getRunnerName())
                .setRunnerSubtype(spawnResult.getRunnerSubtype())
            if (event.getSpawnIdentifier() != null) {
                nodeBuilder.setIdentifier(event.getSpawnIdentifier())
            }

            if (depType != DependencyInfo.NONE) {
                nodeBuilder.setIndex(index)
            }
            setFieldsFromOwner(nodeBuilder, spawn.getResourceOwner().getOwner())

            var metrics: SpawnMetrics? = spawnResult.getMetrics()
            spawnResult = null
            var totalMillis: Int = metrics.totalTimeInMs()

            val firstOutput: ActionInput? = getFirstOutput(spawn.getResourceOwner(), spawn.getOutputFiles())
            val discoverInputsTimeInMs = outputToDiscoverInputsTimeMs.get(firstOutput)
            if (discoverInputsTimeInMs != null) {
                // Remove this so we don't count it again later, if an action has multiple spawns.
                outputToDiscoverInputsTimeMs.remove(firstOutput)
                totalMillis += discoverInputsTimeInMs
            }

            val metricsBuilder: ExecutionGraph.Metrics.Builder =
                ExecutionGraph.Metrics.newBuilder()
                    .setStartTimestampMillis(startMillis)
                    .setDurationMillis(totalMillis)
                    .setFetchMillis(metrics.fetchTimeInMs())
                    .setDiscoverInputsMillis(if (discoverInputsTimeInMs != null) discoverInputsTimeInMs else 0)
                    .setParseMillis(metrics.parseTimeInMs())
                    .setProcessMillis(metrics.executionWallTimeInMs())
                    .setQueueMillis(metrics.queueTimeInMs())
                    .setRetryMillis(metrics.retryTimeInMs())
                    .setSetupMillis(metrics.setupTimeInMs())
                    .setUploadMillis(metrics.uploadTimeInMs())
                    .setNetworkMillis(metrics.networkTimeInMs())
                    .setOtherMillis(metrics.otherTimeInMs())
                    .setProcessOutputsMillis(metrics.processOutputsTimeInMs())

            for (entry in metrics.retryTimeByError().entrySet()) {
                metricsBuilder.putRetryMillisByError(entry.getKey(), entry.getValue())
            }
            metrics = null

            val inputFiles: NestedSet<out ActionInput?>
            if (logFileWriteEdges && spawn.getResourceOwner() is AbstractFileWriteAction) {
                // In order to handle file write like actions correctly, get the inputs
                // from the corresponding action.
                inputFiles = spawn.getResourceOwner().getInputs()
            } else {
                inputFiles = spawn.getInputFiles()
            }

            // maybeAddEdges can take a while, so do it last and try to give up references to any objects
            // we won't need.
            maybeAddEdges(
                nodeBuilder,
                spawn.getOutputEdgesForExecutionGraph(),
                inputFiles,
                spawn.getResourceOwner(),
                event.getInputMetadataProvider(),
                startMillis,
                totalMillis.toLong(),
                index
            )
            return nodeBuilder.setMetrics(metricsBuilder).build()
        }

        private fun maybeAddEdges(
            nodeBuilder: ExecutionGraph.Node.Builder,
            outputs: Iterable<out ActionInput?>,
            inputs: NestedSet<out ActionInput?>,
            metadata: ActionExecutionMetadata,
            inputMetadataProvider: InputMetadataProvider?,
            startMillis: Long,
            totalMillis: Long,
            index: Int
        ) {
            if (depType == DependencyInfo.NONE) {
                return
            }

            val primaryOutput: ActionInput? = getFirstOutput(metadata, outputs)
            if (primaryOutput != null) {
                // If primaryOutput is null, then we know that outputs is also empty, and we don't need to
                // do any of the following.
                val previousAttempt = outputToNode.get(primaryOutput)
                if (previousAttempt != null) {
                    // The same action may issue multiple spawns for various reasons:
                    //
                    // Different "primary output" for each spawn (hence not entering this if condition):
                    //   - Actions with multiple spawns (e.g. inputs discovering actions).
                    //   - Remote execution splitting the spawn into multiple ones (spawns generating tree
                    //     artifacts).
                    //
                    // Running sequentially:
                    //   - Test retries.
                    //   - Java compilation (fallback) after an attempt with a reduced classpath.
                    //   - Retry of a spawn after remote execution failure when using `--local_fallback`.
                    //
                    /** Running in parallel: */
                    //   - Dynamic execution with `--experimental_local_lockfree_output`--with that setting,
                    //     it is possible for both local and remote spawns to finish and send a corresponding
                    //     event.
                    if (previousAttempt.finishMs <= startMillis) {
                        nodeBuilder.setRetryOf(previousAttempt.index)
                    } else if (localLockFreeOutputEnabled) {
                        // Special case what could be dynamic execution with
                        // `--experimental_local_lockfree_output`, skip adding the dependencies for the second
                        // spawn, but report both spawns.
                        return
                    } else {
                        // TODO(b/227635546): Remove the bug report once we capture all cases when it can
                        //  fire.
                        bugReporter.sendNonFatalBugReport(
                            java.lang.IllegalStateException(
                                java.lang.String.format(
                                    "See b/227635546. Multiple spawns produced '%s' with overlapping execution"
                                            + " time. Previous index: %s. Current index: %s",
                                    primaryOutput.getExecPathString(), previousAttempt.index, index
                                )
                            )
                        )
                    }
                }

                val currentAttempt: NodeInfo =
                    com.google.devtools.build.lib.runtime.ExecutionGraphModule.ActionDumpWriter.NodeInfo(
                        index,
                        startMillis + totalMillis
                    )
                for (output in outputs) {
                    outputToNode.put(output, currentAttempt)
                }
                // Some actions, like tests, don't have their primary output in getOutputFiles().
                outputToNode.put(primaryOutput, currentAttempt)
            }

            val runfilesArtifactsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
            var inputsList: com.google.common.collect.ImmutableList<out ActionInput> = inputs.toList()
            val deps: it.unimi.dsi.fastutil.ints.IntArrayList =
                it.unimi.dsi.fastutil.ints.IntArrayList(inputsList.size())

            // Track the previous dep index to reduce the number of duplicates added to deps. Duplicates
            // are often seen consecutively due to NestedSet structure (e.g. when all outputs of an action
            // are added as inputs).
            var previousDepIndex = -1

            for (input in inputsList) {
                // We don't use inputMetadataProvider.getRunfilesTrees() because this method is called both
                // for Spawns and Actions and the runfiles on a Spawn can be a subset of the runfiles of the
                // action during whose execution it was created.
                if ((input is Artifact)
                    && (input as Artifact).isRunfilesTree()
                    && inputMetadataProvider != null
                ) {
                    // This is a runfiles tree. Collect the artifacts in it into
                    // runfilesArtifactsBuilder.
                    val runfilesTree: RunfilesTree =
                        inputMetadataProvider.getRunfilesMetadata(input).getRunfilesTree()
                    runfilesArtifactsBuilder.addTransitive(runfilesTree.getArtifacts())
                }

                if (depType == DependencyInfo.ALL) {
                    val dep = outputToNode.get(input)
                    if (dep != null && dep.index != previousDepIndex) {
                        deps.add(dep.index)
                        previousDepIndex = dep.index
                    }
                }
            }

            inputsList = runfilesArtifactsBuilder.build().toList()
            deps.ensureCapacity(deps.size() + inputsList.size())
            for (runfilesInput in inputsList) {
                val dep = outputToNode.get(runfilesInput)
                if (dep != null && dep.index != previousDepIndex) {
                    deps.add(dep.index)
                    previousDepIndex = dep.index
                }
            }

            // Sort and deduplicate. Compression is more effective when the data is sorted.
            val size: Int = deps.size()
            val elems: IntArray = deps.elements()
            IntArrays.radixSort(elems, 0, size)
            previousDepIndex = -1
            for (i in 0..<size) {
                val depIndex = elems[i]
                if (depIndex != previousDepIndex) {
                    nodeBuilder.addDependentIndex(depIndex)
                    previousDepIndex = depIndex
                }
            }
        }

        private class NodeInfo(private val index: Int, private val finishMs: Long)

        private val bugReporter: BugReporter
        private val eventBus: com.google.common.eventbus.EventBus
        private val localLockFreeOutputEnabled: Boolean
        private val logFileWriteEdges: Boolean
        private val outputToNode: MutableMap<ActionInput?, NodeInfo?> = ConcurrentHashMap<ActionInput?, NodeInfo?>()
        private val outputToDiscoverInputsTimeMs: MutableMap<ActionInput?, Int?> =
            ConcurrentHashMap<ActionInput?, Int?>()
        private val depType: DependencyInfo?
        private val nextIndex: AtomicInteger = AtomicInteger(0)

        // At larger capacities, ArrayBlockingQueue uses slightly less peak memory, but it doesn't
        // matter at lower capacities. Wall time performance is the same either way.
        // In benchmarks, capacities under 100 start increasing wall time and between 1000 and 100000
        // seem to have roughly the same wall time and memory usage. In the real world, using a queue
        // of size 10000 causes many builds to block for a total of more than 100ms. The queue
        // entries should be about 256 bytes, so a queue size of 1_000_000 will use up to 256MB,
        // but the vast majority of builds don't have that many actions.
        private val queue: BlockingQueue<ByteArray>
        private val blockedMillis: AtomicLong = AtomicLong(0)
        private val currentQueuedBytes: AtomicLong = AtomicLong(0)
        private val maxQueuedBytes: AtomicLong = AtomicLong(0)
        private val queuedBytesLimit: Int
        private val queuedBytesSemaphore: Semaphore?
        private val outStream: java.io.OutputStream?
        private val thread: java.lang.Thread

        init {
            this.bugReporter = bugReporter
            this.eventBus = eventBus
            this.localLockFreeOutputEnabled = localLockFreeOutputEnabled
            this.logFileWriteEdges = logFileWriteEdges
            this.outStream = outStream
            this.depType = depType
            if (queueSize < 0) {
                queue = LinkedBlockingQueue<ByteArray>()
            } else {
                queue = LinkedBlockingQueue<ByteArray>(queueSize)
            }
            if (queuedBytesLimit < 0) {
                queuedBytesSemaphore = null
            } else {
                queuedBytesSemaphore = Semaphore(queuedBytesLimit)
            }
            this.queuedBytesLimit = queuedBytesLimit
            this.thread = java.lang.Thread(this, "action-graph-writer")
            this.thread.start()
        }

        fun enqueueBytes(entry: ByteArray) {
            if (queuedBytesSemaphore != null && entry.size > 0) {
                val permits = numPermits(entry)
                if (!queuedBytesSemaphore.tryAcquire(permits)) {
                    val sw: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
                    try {
                        queuedBytesSemaphore.acquire(permits)
                    } catch (e: java.lang.InterruptedException) {
                        logger.atWarning().atMostEvery(10, TimeUnit.SECONDS).withCause(e).log(
                            "Interrupted while trying to acquire queued bytes semaphore"
                        )
                        java.lang.Thread.currentThread().interrupt()
                        return
                    } finally {
                        blockedMillis.addAndGet(sw.elapsed(TimeUnit.MILLISECONDS))
                    }
                }
            }

            val current: Long = currentQueuedBytes.addAndGet(entry.size.toLong())
            // Avoid expensive CAS operations in accumulateAndGet() once a high peak is established.
            if (current > maxQueuedBytes.get()) {
                maxQueuedBytes.accumulateAndGet(
                    current,
                    LongBinaryOperator { a: Long, b: Long -> java.lang.Math.max(a, b) })
            }
            if (queue.offer(entry)) {
                return
            }
            val sw: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
            try {
                queue.put(entry)
            } catch (e: java.lang.InterruptedException) {
                logger.atWarning().atMostEvery(10, TimeUnit.SECONDS).withCause(e).log(
                    "Interrupted while trying to put to queue"
                )
                releaseQueuedBytes(entry)
                java.lang.Thread.currentThread().interrupt()
            }
            blockedMillis.addAndGet(sw.elapsed().toMillis())
        }

        private fun releaseQueuedBytes(entry: ByteArray) {
            currentQueuedBytes.addAndGet(-entry.size.toLong())
            if (queuedBytesSemaphore != null) {
                queuedBytesSemaphore.release(numPermits(entry))
            }
        }

        private fun numPermits(entry: ByteArray): Int {
            // Clamp to queuedBytesLimit. This is intentional to prevent deadlocks when a single entry is
            // larger than the total limit, even though it allows the memory limit to be exceeded for that
            // specific entry.
            return java.lang.Math.min(entry.size, queuedBytesLimit)
        }

        fun enqueue(event: DiscoveredInputsEvent) {
            // The other times from SpawnMetrics are not needed. The only instance of
            // DiscoveredInputsEvent sets only total and parse time, and to the same value.
            val totalTime: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                event.getMetrics().totalTimeInMs()
            val firstOutput: ActionInput? = getFirstOutput(event.getAction(), event.getAction().getOutputs())
            var sum = outputToDiscoverInputsTimeMs.getOrDefault(firstOutput, 0)
            sum += totalTime
            outputToDiscoverInputsTimeMs.put(firstOutput, sum)
        }

        fun enqueue(
            action: Action,
            inputMetadataProvider: InputMetadataProvider?,
            startMillis: Long,
            finishMillis: Long
        ) {
            // This is here just to capture actions which don't have spawns. If we already know about
            // an output, don't also include it again.
            if (outputToNode.containsKey(getFirstOutput(action, action.getOutputs()))) {
                return
            }
            if (action.getMnemonic().equals("TestRunner")) {
                // Test actions have a different primary output than their spawns, which would result in
                // them recording an extra node. See b/290959382. Since test actions should always have/
                // spawns, we can just skip them here.
                return
            }
            enqueueBytes(
                actionToNode(action, inputMetadataProvider, startMillis, finishMillis).toByteArray()
            )
        }

        fun enqueue(event: SpawnExecutedEvent) {
            enqueueBytes(toProto(event).toByteArray())
        }

        @Throws(java.lang.InterruptedException::class)
        fun shutdown(logs: BuildToolLogCollection?) {
            enqueueBytes(INVOCATION_COMPLETED)
            thread.join()
            if (logs != null) {
                updateLogs(logs)
            }
            eventBus.post(
                ExecutionGraphWriterStats.newBuilder()
                    .setBlockedMillis(blockedMillis.get())
                    .setMaxQueuedBytes(maxQueuedBytes.get())
                    .build()
            )
        }

        fun actionShared(event: SharedActionEvent) {
            copySharedArtifacts(
                event.getExecuted().getAllFileValues(), event.getTransformed().getAllFileValues()
            )
            copySharedArtifacts(
                event.getExecuted().getAllTreeArtifactValues(),
                event.getTransformed().getAllTreeArtifactValues()
            )
        }

        private fun copySharedArtifacts(executed: MutableMap<Artifact?, *>, transformed: MutableMap<Artifact?, *>) {
            com.google.common.collect.Streams.forEachPair<Artifact?, Artifact?>(
                executed.keySet().stream(),
                transformed.keySet().stream(),
                java.util.function.BiConsumer { existing: Artifact?, shared: Artifact? ->
                    val node = outputToNode.get(existing)
                    if (node != null) {
                        outputToNode.put(shared, node)
                    } else {
                        bugReporter.logUnexpected("No node for %s (%s)", existing, existing.getOwner())
                    }
                })
        }

        protected abstract fun updateLogs(logs: BuildToolLogCollection?)

        /** Test hook to allow injecting failures in tests.  */
        @com.google.common.annotations.VisibleForTesting
        @Throws(IOException::class)
        open fun createCompressingOutputStream(): ZstdOutputStream {
            // zstd compression at the default level produces 20% smaller outputs than gzip, while being
            // faster to compress and decompress. Higher levels get slower quickly, without much benefit
            // in size. For example, level 4 produces 1% smaller outputs, but takes twice as long to
            // compress in standalone benchmarks. Lower levels quickly increase size, without much benefit
            // in speed. For example, level -3 produces 60% bigger outputs, but only runs 10% faster in
            // standalone benchmarks.
            return ZstdOutputStream(outStream)
        }

        /**
         * Saves all gathered information from taskQueue queue to the file. Method is invoked internally
         * by the Timer-based thread and at the end of profiling session.
         */
        override fun run() {
            try {
                // Track when we receive the last entry in case there's a failure in the implied #close()
                // call on the OutputStream.
                var receivedLastEntry = false
                try {
                    createCompressingOutputStream().use { out ->
                        val codedOut: CodedOutputStream = CodedOutputStream.newInstance(out, OUTPUT_BUFFER_SIZE)
                        var data: ByteArray
                        while ((queue.take().also { data = it }) != INVOCATION_COMPLETED) {
                            codedOut.writeByteArrayNoTag(data)
                            releaseQueuedBytes(data)
                        }
                        receivedLastEntry = true
                        codedOut.flush()
                    }
                } catch (e: IOException) {
                    // Fixing b/117951060 should mitigate, but may happen regardless.
                    logger.atWarning().withCause(e).log("Failure writing action dump")
                    if (!receivedLastEntry) {
                        var data: ByteArray
                        while ((queue.take().also { data = it }) != INVOCATION_COMPLETED) {
                            releaseQueuedBytes(data)
                        }
                    }
                }
            } catch (e: java.lang.InterruptedException) {
                // This thread exits immediately, so there's nothing checking this bit. Just exit silently.
                java.lang.Thread.currentThread().interrupt()
            }
        }

        companion object {
            private fun setFieldsFromOwner(node: ExecutionGraph.Node.Builder, owner: ActionOwner?) {
                if (owner != null) {
                    if (owner.getTargetKind() != null) {
                        node.setRuleClass(owner.getTargetKind())
                    }
                    if (owner.getLabel() != null) {
                        node.setTargetLabel(owner.getLabel().toString())
                    }
                }
            }

            private fun getFirstOutput(
                metadata: ActionExecutionMetadata, outputs: Iterable<out ActionInput?>
            ): ActionInput? {
                // Spawn.getOutputFiles can be empty. For example, SpawnAction can be made to not report
                // outputs, and ExtraAction uses that. In that case, fall back to the owner's primary output.
                var primaryOutput: ActionInput? = com.google.common.collect.Iterables.getFirst(outputs, null)
                if (primaryOutput == null) {
                    // Despite the stated contract of getPrimaryOutput(), it can return null, like in
                    // GrepIncludesAction.
                    primaryOutput = metadata.getPrimaryOutput()
                }
                return primaryOutput
            }

            // This queue entry signals that there are no more entries that need to be written.
            private val INVOCATION_COMPLETED = ByteArray(0)

            // Based on benchmarks. 2Mib buffers seem sufficient, and buffers bigger than that don't
            // provide much benefit.
            private val OUTPUT_BUFFER_SIZE = 1 shl 21
        }
    }

    private class FilesystemActionDumpWriter(
        bugReporter: BugReporter,
        eventBus: com.google.common.eventbus.EventBus,
        localLockFreeOutputEnabled: Boolean,
        logFileWriteEdges: Boolean,
        actionGraphFile: com.google.devtools.build.lib.vfs.Path,
        depType: DependencyInfo?,
        queueSize: Int,
        queuedBytesLimit: Int
    ) : ActionDumpWriter(
        bugReporter,
        eventBus,
        localLockFreeOutputEnabled,
        logFileWriteEdges,
        actionGraphFile.getOutputStream(),
        depType,
        queueSize,
        queuedBytesLimit
    ) {
        private val actionGraphFile: com.google.devtools.build.lib.vfs.Path

        init {
            this.actionGraphFile = actionGraphFile
        }

        override fun updateLogs(logs: BuildToolLogCollection) {
            logs.addLocalFile(
                ACTION_DUMP_NAME,
                actionGraphFile,
                LocalFileType.PERFORMANCE_LOG,
                LocalFileCompression.NONE
            )
        }
    }

    private class StreamingActionDumpWriter(
        bugReporter: BugReporter,
        eventBus: com.google.common.eventbus.EventBus,
        localLockFreeOutputEnabled: Boolean,
        logFileWriteEdges: Boolean,
        uploadContext: UploadContext,
        depType: DependencyInfo?,
        queueSize: Int,
        queuedBytesLimit: Int
    ) : ActionDumpWriter(
        bugReporter,
        eventBus,
        localLockFreeOutputEnabled,
        logFileWriteEdges,
        uploadContext.outputStream,
        depType,
        queueSize,
        queuedBytesLimit
    ) {
        private val uploadContext: UploadContext

        init {
            this.uploadContext = uploadContext
        }

        override fun updateLogs(logs: BuildToolLogCollection) {
            logs.addUriFuture(ACTION_DUMP_NAME, uploadContext.uriFuture())
        }
    }

    /** Exception thrown when a FilesystemActionDumpWriter cannot create its output file.  */
    private class ActionDumpFileCreationException(path: com.google.devtools.build.lib.vfs.Path?, e: IOException?) :
        IOException("could not create new action dump file on filesystem at path: " + path, e)

    companion object {
        private const val ACTION_DUMP_NAME = "execution_graph_dump.proto.zst"

        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        @Throws(InvalidPackagePathSymlinkException::class)
        private fun newUploader(
            env: CommandEnvironment, bepOptions: BuildEventProtocolOptions
        ): BuildEventArtifactUploader {
            return env.getRuntime()
                .getBuildEventArtifactUploaderFactoryMap()
                .select(bepOptions.buildEventUploadStrategy)
                .create(env)
        }

        @Throws(InvalidPackagePathSymlinkException::class, ActionDumpFileCreationException::class)
        private fun createActionDumpWriter(env: CommandEnvironment): ActionDumpWriter {
            val parsingResult: com.google.devtools.common.options.OptionsParsingResult = env.getOptions()
            val bepOptions: BuildEventProtocolOptions =
                com.google.common.base.Preconditions.checkNotNull<T>(
                    parsingResult.getOptions<O?>(
                        BuildEventProtocolOptions::class.java
                    )
                )
            val executionGraphOptions: ExecutionGraphOptions =
                com.google.common.base.Preconditions.checkNotNull<ExecutionGraphOptions>(
                    parsingResult.getOptions<ExecutionGraphOptions?>(
                        ExecutionGraphOptions::class.java
                    )
                )
            if (bepOptions.streamingLogFileUploads
                && executionGraphOptions.executionGraphLogPath.isBlank()
            ) {
                return StreamingActionDumpWriter(
                    env.getRuntime().getBugReporter(),
                    env.getEventBus(),
                    env.getOptions().getOptions(LocalExecutionOptions::class.java).localLockfreeOutput,
                    executionGraphOptions.logFileWriteEdges,
                    newUploader(env, bepOptions).startUpload(LocalFileType.PERFORMANCE_LOG, null),
                    executionGraphOptions.depType,
                    executionGraphOptions.queueSize,
                    executionGraphOptions.queuedBytesLimit
                )
            }

            var path = executionGraphOptions.executionGraphLogPath
            if (path.isBlank()) {
                path = ACTION_DUMP_NAME
            }
            val actionGraphFile: com.google.devtools.build.lib.vfs.Path = env.getOutputBase().getRelative(path)
            try {
                return FilesystemActionDumpWriter(
                    env.getRuntime().getBugReporter(),
                    env.getEventBus(),
                    env.getOptions().getOptions(LocalExecutionOptions::class.java).localLockfreeOutput,
                    executionGraphOptions.logFileWriteEdges,
                    actionGraphFile,
                    executionGraphOptions.depType,
                    executionGraphOptions.queueSize,
                    executionGraphOptions.queuedBytesLimit
                )
            } catch (e: IOException) {
                throw ActionDumpFileCreationException(actionGraphFile, e)
            }
        }

        private fun makeReportUploaderNeedsPackagePathsDetail(): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage("could not create action dump uploader due to failed package path resolution")
                .setBuildReport(
                    BuildReport.newBuilder().setCode(Code.BUILD_REPORT_UPLOADER_NEEDS_PACKAGE_PATHS)
                )
                .build()
        }

        private fun makeReportWriteFailedDetail(): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage("could not open action dump file for writing")
                .setBuildReport(BuildReport.newBuilder().setCode(Code.BUILD_REPORT_WRITE_FAILED))
                .build()
        }
    }
}
