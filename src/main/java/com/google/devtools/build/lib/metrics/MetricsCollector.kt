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
package com.google.devtools.build.lib.metrics

import com.google.devtools.build.lib.actions.ActionCompletionEvent

internal class MetricsCollector @com.google.errorprone.annotations.CanIgnoreReturnValue private constructor(
    env: CommandEnvironment,
    numAnalyses: AtomicInteger,
    numBuilds: AtomicInteger
) {
    private val env: CommandEnvironment
    private val recordMetricsForAllMnemonics: Boolean
    private val recordSkyframeMetrics: Boolean

    // For ActionSummary.
    private val actionStatsMap: ConcurrentHashMap<String?, ActionStats> = ConcurrentHashMap<String?, ActionStats>()

    // For CumulativeMetrics.
    private val numAnalyses: AtomicInteger
    private val numBuilds: AtomicInteger

    private val actionSummary: ActionSummary.Builder = ActionSummary.newBuilder()
    private val targetMetrics: TargetMetrics.Builder = TargetMetrics.newBuilder()
    private val packageMetrics: PackageMetrics.Builder = PackageMetrics.newBuilder()
    private val bzlMetrics: BzlMetrics.Builder = BzlMetrics.newBuilder()

    private val timingMetrics: TimingMetrics.Builder = TimingMetrics.newBuilder()
    private val artifactMetrics: ArtifactMetrics.Builder = ArtifactMetrics.newBuilder()
    private val buildGraphMetrics: BuildGraphMetrics.Builder = BuildGraphMetrics.newBuilder()
    private val dynamicExecutionStats = DynamicExecutionStats()
    private val spawnStats: SpawnStats = SpawnStats()

    // Skymeld-specific: we don't have an ExecutionStartingEvent for skymeld, so we have to use
    // TopLevelTargetExecutionStartedEvent. This AtomicBoolean is so that we only account for the
    // build once.
    private val buildAccountedFor: AtomicBoolean

    // Identify when the actual actions execution starts (excluding workspace status actions).
    private val executionStarted: AtomicBoolean

    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    @kotlin.jvm.Synchronized
    fun logAnalysisStartingEvent(event: AnalysisPhaseStartedEvent?) {
        numAnalyses.getAndIncrement()
    }

    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    fun onAnalysisPhaseComplete(event: AnalysisPhaseCompleteEvent) {
        val actionsConstructed: TotalAndConfiguredTargetOnlyMetric = event.getActionsConstructed()
        actionSummary
            .setActionsCreated(actionsConstructed.total())
            .setActionsCreatedNotIncludingAspects(actionsConstructed.configuredTargetsOnly())
        val targetsConfigured: TotalAndConfiguredTargetOnlyMetric = event.getTargetsConfigured()
        targetMetrics
            .setTargetsConfigured(targetsConfigured.total())
            .setTargetsConfiguredNotIncludingAspects(targetsConfigured.configuredTargetsOnly())
        timingMetrics.setAnalysisPhaseTimeInMs(event.getTimeInMs())

        packageMetrics.setPackagesLoaded(event.getPkgManagerStats().getPackagesSuccessfullyLoaded())

        if (PackageMetricsPackageLoadingListener.Companion.getInstance().getPublishPackageMetricsInBep()) {
            val recorder: PackageMetricsRecorder? =
                PackageMetricsPackageLoadingListener.Companion.getInstance().getPackageMetricsRecorder()
            if (recorder != null) {
                var metrics: java.util.stream.Stream<PackageLoadMetrics?> = recorder.getPackageLoadMetrics().stream()

                if (recorder.getRecorderType() == com.google.devtools.build.lib.packages.metrics.PackageMetricsRecorder.Type.ONLY_EXTREMES) {
                    val extremaPackageMetricsRecorder: ExtremaPackageMetricsRecorder =
                        recorder as ExtremaPackageMetricsRecorder
                    // Safeguard: we have 5 metrics, so print at most 5 times the number of packages as being
                    // tracked per metric.
                    metrics = metrics.limit(5L * extremaPackageMetricsRecorder.getNumPackagesToTrack())
                }
                metrics.forEach(packageMetrics::addPackageLoadMetrics)
                bzlMetrics.mergeFrom(recorder.getBzlMetrics())
            }
        }

        val actionsConstructedByMnemonic: com.google.common.collect.ImmutableMap<String?, Int?> =
            event.getActionsConstructedByMnemonic()
        for (entry in actionsConstructedByMnemonic.entries) {
            val actionStats: ActionStats =
                actionStatsMap.computeIfAbsent(entry.key) { mnemonic: String? -> ActionStats(mnemonic) }
            actionStats.numActionsRegistered.addAndGet(entry.value.toLong())
        }
    }

    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    @kotlin.jvm.Synchronized
    fun logAnalysisGraphStats(event: AnalysisGraphStatsEvent) {
        // Check only one event per build. No proto3 check for presence, so check for not-default value.
        if (buildGraphMetrics.getActionLookupValueCount() > 0) {
            BugReport.sendBugReport(
                java.lang.IllegalStateException(
                    ("Already initialized build graph metrics builder: "
                            + buildGraphMetrics
                            + ", "
                            + event.getBuildGraphMetrics())
                )
            )
        }
        buildGraphMetrics.mergeFrom(event.getBuildGraphMetrics())
    }

    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    @kotlin.jvm.Synchronized
    fun logExecutionStartingEvent(event: ExecutionStartingEvent?) {
        numBuilds.getAndIncrement()
    }

    // Skymeld-specific: we don't have an ExecutionStartingEvent for skymeld, so we have to use
    // TopLevelTargetExecutionStartedEvent
    @com.google.common.eventbus.Subscribe
    @kotlin.jvm.Synchronized
    fun handleExecutionPhaseStart(
        @Suppress("unused") event: TopLevelTargetPendingExecutionEvent?
    ) {
        if (buildAccountedFor.compareAndSet( /* expectedValue= */false,  /* newValue= */true)) {
            numBuilds.getAndIncrement()
        }
    }

    @com.google.common.eventbus.Subscribe
    fun onSomeExecutionStarted(event: SomeExecutionStartedEvent) {
        if (event.countedInExecutionTime) {
            if (executionStarted.compareAndSet(false, true)) {
                val elapsedWallTime: java.time.Duration? =
                    com.google.devtools.build.lib.profiler.Profiler.Companion.instance().getProfileElapsedTime()
                if (elapsedWallTime != null) {
                    timingMetrics.setActionsExecutionStartInMs(elapsedWallTime.toMillis())
                }
            }
        }
    }

    @com.google.common.eventbus.Subscribe
    fun handleExecutionPhaseComplete(event: ExecutionPhaseCompleteEvent) {
        timingMetrics.setExecutionPhaseTimeInMs(event.timeInMs)
    }

    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun onActionComplete(event: ActionCompletionEvent) {
        val actionStats: ActionStats =
            actionStatsMap.computeIfAbsent(
                event.getAction().getMnemonic()
            ) { mnemonic: String? -> ActionStats(mnemonic) }
        actionStats.numActionsExecuted.incrementAndGet()
        actionStats.firstStarted.accumulate(event.getRelativeActionStartTimeNanos())
        actionStats.lastEnded.accumulate(com.google.devtools.build.lib.clock.BlazeClock.nanoTime())
        spawnStats.incrementActionCount()
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun actionResultReceived(event: ActionResultReceivedEvent) {
        spawnStats.countActionResult(event.getActionResult())
        val actionStats: ActionStats =
            actionStatsMap.computeIfAbsent(
                event.getAction().getMnemonic()
            ) { mnemonic: String? -> ActionStats(mnemonic) }
        val systemTime: Int = event.getActionResult().cumulativeCommandExecutionSystemTimeInMs()
        if (systemTime > 0) {
            actionStats.systemTime.addAndGet(systemTime.toLong())
        }
        val userTime: Int = event.getActionResult().cumulativeCommandExecutionUserTimeInMs()
        if (userTime > 0) {
            actionStats.userTime.addAndGet(userTime.toLong())
        }
    }

    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    fun onExecutionComplete(event: ExecutionFinishedEvent) {
        artifactMetrics
            .setSourceArtifactsRead(event.sourceArtifactsRead)
            .setOutputArtifactsSeen(event.outputArtifactsSeen)
            .setOutputArtifactsFromActionCache(event.outputArtifactsFromActionCache)
            .setTopLevelArtifacts(event.topLevelArtifacts)
    }

    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    fun onDynamicExecutionFinishedEvent(event: DynamicExecutionFinishedEvent) {
        dynamicExecutionStats.update(
            event.mnemonic,
            event.localBranchName,
            event.remoteBranchName,
            event.getWinnerBranchType()
        )
    }

    private fun toEvaluationStats(
        map: com.google.common.collect.ImmutableMap<SkyFunctionName?, Int?>
    ): com.google.common.collect.ImmutableList<BuildMetrics.EvaluationStat?> {
        return map.entries.stream()
            .map<Any?> { e: MutableMap.MutableEntry<SkyFunctionName?, Int?>? ->
                BuildMetrics.EvaluationStat.newBuilder()
                    .setSkyfunctionName(e!!.key.getName())
                    .setCount(e.value)
                    .build()
            }
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
    }

    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    fun onSkyframeGraphStats(event: SkyframeGraphStatsEvent) {
        val evaluationStats: EvaluationStats = event.getEvaluationStats()
        buildGraphMetrics.addAllDirtiedValues(toEvaluationStats(evaluationStats.dirtied))
        buildGraphMetrics.addAllChangedValues(toEvaluationStats(evaluationStats.changed))
        buildGraphMetrics.addAllBuiltValues(toEvaluationStats(evaluationStats.built))
        buildGraphMetrics.addAllCleanedValues(toEvaluationStats(evaluationStats.cleaned))
        buildGraphMetrics.addAllEvaluatedValues(toEvaluationStats(evaluationStats.evaluated))
        buildGraphMetrics.setPostInvocationSkyframeNodeCount(event.getGraphSize())
    }

    // This needs to be done in CommandPrecompleteEvent because the metrics are reported on the BEP,
    // which is closed in BlazeModule.afterCommand().
    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    fun onCommandPrecompleteEvent(event: CommandPrecompleteEvent?) {
        env.getEventBus().post(BuildMetricsEvent(createBuildMetrics()))
    }

    @com.google.common.eventbus.Subscribe
    fun onCriticalPath(event: CriticalPathEvent) {
        val protoDuration: com.google.protobuf.Duration? =
            Durations.fromNanos(event.criticalPath.getAggregatedElapsedTime().toNanos())
        timingMetrics.setCriticalPathTime(protoDuration)
    }

    @Suppress("unused")
    @com.google.common.eventbus.Subscribe
    private fun logActionCacheStatistics(stats: PostableActionCacheStats) {
        actionSummary.setActionCacheStatistics(stats.asProto())
    }

    private fun createBuildMetrics(): BuildMetrics {
        val workerProcessMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics> =
            WorkerProcessMetricsCollector.instance().collectMetrics()
        // Restrict the number of WorkerMetrics that we report based on a predefined prioritization so
        // that we don't spam the BEP in the event that something like a kill-create cycle happens.
        val workerMetrics: com.google.common.collect.ImmutableList<WorkerMetrics?>? =
            WorkerProcessMetricsCollector.limitWorkerMetricsToPublish(
                workerProcessMetrics.stream()
                    .map<WorkerMetrics?> { obj: WorkerProcessMetrics? -> obj.toProto() }
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<WorkerMetrics?>()),
                WorkerProcessMetricsCollector.MAX_PUBLISHED_WORKER_METRICS)

        addSkyframeStats(buildGraphMetrics)

        val remoteAnalysisCacheStatistics: RemoteAnalysisCacheStatistics = collectRemoteAnalysisCacheStats()

        val buildMetrics: BuildMetrics.Builder =
            BuildMetrics.newBuilder()
                .setActionSummary(finishActionSummary())
                .setMemoryMetrics(createMemoryMetrics())
                .setTargetMetrics(targetMetrics.build())
                .setPackageMetrics(packageMetrics.build())
                .setBzlMetrics(bzlMetrics.build())
                .setTimingMetrics(finishTimingMetrics())
                .setCumulativeMetrics(createCumulativeMetrics())
                .setArtifactMetrics(artifactMetrics.build())
                .setBuildGraphMetrics(buildGraphMetrics.build())
                .addAllWorkerMetrics(workerMetrics)
                .setWorkerPoolMetrics(createWorkerPoolMetrics(workerProcessMetrics))
                .setDynamicExecutionMetrics(dynamicExecutionStats.toMetrics())
                .setRemoteAnalysisCacheStatistics(remoteAnalysisCacheStatistics)

        val networkMetrics: NetworkMetrics? = NetworkMetricsCollector.Companion.instance().collectMetrics()
        if (networkMetrics != null) {
            buildMetrics.setNetworkMetrics(networkMetrics)
        }

        return buildMetrics.build()
    }

    private fun computeDistributionProto(buckets: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Bucket>): Distribution {
        val result: Distribution.Builder = Distribution.newBuilder()

        for (b in buckets) {
            result.addHistogramBucket(
                Distribution.HistogramBucket.newBuilder()
                    .setMin(b.minInclusive)
                    .setMax(b.maxExclusive)
                    .setCount(b.count)
                    .build()
            )
        }
        return result.build()
    }

    private fun collectRemoteAnalysisCacheStats(): RemoteAnalysisCacheStatistics {
        val listener: RemoteAnalysisCachingEventListener = env.getRemoteAnalysisCachingEventListener()
        val result: RemoteAnalysisCacheStatistics.Builder =
            RemoteAnalysisCacheStatistics.newBuilder()
                .setCacheHits(listener.getCacheHits().size)
                .setCacheMisses(listener.getCacheMisses().size)

        for (entry in listener.getMissesByReason().entries) {
            result.addCacheMissesByReason(
                Entry.newBuilder()
                    .setKey(entry.key.name())
                    .setValue(entry.value.get())
                    .build()
            )
        }

        for (entry in listener.getMissesBySkyFunctionName().entries) {
            result.addCacheMissesBySkyfunction(
                Entry.newBuilder()
                    .setKey(entry.key.getName())
                    .setValue(entry.value.get())
                    .build()
            )
        }

        for (entry in listener.getHitsBySkyFunctionName().entries) {
            result.addCacheHitsBySkyfunction(
                Entry.newBuilder()
                    .setKey(entry.key.getName())
                    .setValue(entry.value.get())
                    .build()
            )
        }

        val fvsStats: FingerprintValueStore.Stats =
            env.getRemoteAnalysisCachingEventListener().getFingerprintValueStoreStats()
        result
            .setValueStoreValueBytesReceived(fvsStats.valueBytesReceived)
            .setValueStoreValueBytesSent(fvsStats.valueBytesSent)
            .setValueStoreKeyBytesSent(fvsStats.keyBytesSent)
            .setValueStoreWriteOps(fvsStats.entriesWritten)
            .setValueStoreReadOpsSuccessful(fvsStats.entriesFound)
            .setValueStoreReadOpsNotFound(fvsStats.entriesNotFound)
            .setValueStoreReadBatches(fvsStats.getBatches)
            .setValueStoreWriteBatches(fvsStats.setBatches)
            .setValueStoreReadLatencyMicros(computeDistributionProto(fvsStats.getLatencyMicros))
            .setValueStoreReadBatchLatencyMicros(
                computeDistributionProto(fvsStats.getBatchLatencyMicros)
            )
            .setValueStoreWriteLatencyMicros(computeDistributionProto(fvsStats.setLatencyMicros))
            .setValueStoreWriteBatchLatencyMicros(
                computeDistributionProto(fvsStats.setBatchLatencyMicros)
            )

        val raccStats: RemoteAnalysisCacheClient.Stats =
            env.getRemoteAnalysisCachingEventListener().getRemoteAnalysisCacheStats()
        result
            .setAnalysisCacheBytesReceived(raccStats.bytesReceived)
            .setAnalysisCacheKeyBytesSent(raccStats.bytesSent)
            .setAnalysisCacheOps(raccStats.requestsSent)
            .setAnalysisCacheBatches(raccStats.batches)
            .setAnalysisCacheReadLatencyMicros(computeDistributionProto(raccStats.latencyMicros))
            .setAnalysisCacheReadBatchLatencyMicros(
                computeDistributionProto(raccStats.batchLatencyMicros)
            )
            .setMetadataLookupResult(raccStats.matchStatus)

        val invalidationMetrics: RemoteAnalysisCacheStatistics.InvalidationLookupMetrics? =
            listener.getInvalidationLookupMetrics()
        if (invalidationMetrics != null) {
            result.setInvalidationLookupMetrics(invalidationMetrics)
        }

        return result.build()
    }

    private fun buildActionData(actionStats: ActionStats): ActionData {
        val nanosToMillisSinceEpochConverter: com.google.devtools.build.lib.clock.BlazeClock.NanosToMillisSinceEpochConverter =
            com.google.devtools.build.lib.clock.BlazeClock.createNanosToMillisSinceEpochConverter()
        val numActionsExecuted: Long = actionStats.numActionsExecuted.get()
        val builder: ActionData.Builder =
            ActionData.newBuilder()
                .setMnemonic(actionStats.mnemonic)
                .setActionsExecuted(numActionsExecuted)
                .setActionsCreated(actionStats.numActionsRegistered.get())

        if (numActionsExecuted > 0) {
            builder
                .setFirstStartedMs(
                    nanosToMillisSinceEpochConverter.toEpochMillis(actionStats.firstStarted.toLong())
                )
                .setLastEndedMs(
                    nanosToMillisSinceEpochConverter.toEpochMillis(actionStats.lastEnded.toLong())
                )
        }

        val systemTime: Long = actionStats.systemTime.get()
        if (systemTime > 0) {
            builder.setSystemTime(Durations.fromMillis(systemTime))
        }
        val userTime: Long = actionStats.userTime.get()
        if (userTime > 0) {
            builder.setUserTime(Durations.fromMillis(userTime))
        }
        return builder.build()
    }

    init {
        this.env = env
        val options: com.google.devtools.build.lib.metrics.MetricsModule.Options? = env.getOptions()
            .getOptions<com.google.devtools.build.lib.metrics.MetricsModule.Options?>(com.google.devtools.build.lib.metrics.MetricsModule.Options::class.java)
        this.recordMetricsForAllMnemonics =
            options != null && options.getRecordMetricsForAllMnemonics()
        this.recordSkyframeMetrics = options != null && options.getRecordSkyframeMetrics()
        this.numAnalyses = numAnalyses
        this.numBuilds = numBuilds
        env.getEventBus().register(this)
        WorkerProcessMetricsCollector.instance().setClock(env.getClock())
        this.buildAccountedFor = AtomicBoolean()
        this.executionStarted = AtomicBoolean()
    }

    private fun finishActionSummary(): ActionSummary {
        var actionStatsStream: java.util.stream.Stream<ActionStats?> = actionStatsMap.values.stream()

        if (!recordMetricsForAllMnemonics) {
            actionStatsStream =
                actionStatsStream
                    .sorted(java.util.Comparator.comparingLong<ActionStats?>(java.util.function.ToLongFunction { a: ActionStats? -> -a!!.numActionsExecuted.get() }))
                    .limit(MAX_ACTION_DATA.toLong())
        }

        actionStatsStream.forEach { action: ActionStats? -> actionSummary.addActionData(buildActionData(action!!)) }

        val spawnSummary: com.google.common.collect.ImmutableMap<String?, Int?> = spawnStats.getSummary()
        actionSummary.setActionsExecuted(spawnSummary.getOrDefault("total", 0))
        spawnSummary
            .entries
            .forEach(
                java.util.function.Consumer { e: MutableMap.MutableEntry<String?, Int?>? ->
                    val builder: RunnerCount.Builder = RunnerCount.newBuilder()
                    builder.setName(e!!.key).setCount(e.value)
                    val execKind: String? = spawnStats.getExecKindFor(e.key)
                    if (execKind != null) {
                        builder.setExecKind(execKind)
                    }
                    actionSummary.addRunnerCount(builder.build())
                })
        return actionSummary.build()
    }

    private fun addSkyframeStats(builder: BuildGraphMetrics.Builder) {
        // short-circuit if not requested
        if (!recordSkyframeMetrics) {
            return
        }

        // NOTE: This can potentially unintentionally consume a pending Exception by
        // calling getSkyframeStats, with our Reporter which ends up consuming the
        // analysis failure unintentionally.  So if our CommandEnvironment has a
        // pending exception, don't touch the Skyframe executor.
        if (env.getPendingException() != null) {
            return
        }

        // getSkyframeStats return Nullable for unsupported implementations, so
        // ensure we get stats before proceeding.
        val skyframeStats: SkyframeStats? = env.getSkyframeExecutor().getSkyframeStats()
        if (skyframeStats == null) {
            return
        }

        skyframeStats
            .ruleStats
            .forEach(
                java.util.function.Consumer { a: SkyKeyStats? ->
                    builder.addRuleClass(
                        RuleClassCount.newBuilder()
                            .setKey(a.getKey())
                            .setRuleClass(a.getName())
                            .setCount(a.getCount())
                            .setActionCount(a.getActionCount())
                            .build()
                    )
                })
        skyframeStats
            .aspectStats
            .forEach(
                java.util.function.Consumer { a: SkyKeyStats? ->
                    builder.addAspect(
                        AspectCount.newBuilder()
                            .setKey(a.getKey())
                            .setAspectName(a.getName())
                            .setCount(a.getCount())
                            .setActionCount(a.getActionCount())
                            .build()
                    )
                })

        val starlarkProviders: com.google.common.collect.ImmutableMultiset<StarlarkProvider?> =
            skyframeStats.starlarkProviders
        val providerStats: StarlarkProviderStats.Builder =
            builder.getStarlarkProviderStatsBuilder().setTotalCount(starlarkProviders.size)
        val printer: LocationPrinter =
            LocationPrinter( /* attemptToPrintRelativePaths= */
                true,
                env.getDirectories().getWorkspace().asFragment()
            )
        printer.packageLocatorCreated(env.getPackageLocator())
        starlarkProviders.forEachEntry(
            ObjIntConsumer { provider: StarlarkProvider?, count: Int ->
                val providerBuilder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    providerStats
                        .addProvidersBuilder()
                        .setName(provider.getName())
                        .setLocation(printer.getLocationString(provider.getLocation()))
                        .setCount(count)
                val fields: com.google.common.collect.ImmutableList<String?>? = provider.getFields()
                if (fields != null) {
                    providerBuilder.getSchemaBuilder().setFieldCount(fields.size)
                }
            })
    }

    private fun createCumulativeMetrics(): CumulativeMetrics {
        return CumulativeMetrics.newBuilder()
            .setNumAnalyses(numAnalyses.get())
            .setNumBuilds(numBuilds.get())
            .build()
    }

    private fun finishTimingMetrics(): TimingMetrics {
        val elapsedWallTime: java.time.Duration? =
            com.google.devtools.build.lib.profiler.Profiler.Companion.instance().getProfileElapsedTime()
        if (elapsedWallTime != null) {
            timingMetrics.setWallTimeInMs(elapsedWallTime.toMillis())
        }
        val cpuTime: java.time.Duration? =
            com.google.devtools.build.lib.profiler.Profiler.Companion.instance().getServerProcessCpuTime()
        if (cpuTime != null) {
            timingMetrics.setCpuTimeInMs(cpuTime.toMillis())
        }
        return timingMetrics.build()
    }

    private class WorkerPoolStats(private val mnemonic: String?, private val hash: Int) {
        private var createdCount = 0
        private var destroyedCount = 0
        private var evictedCount = 0
        private var userExecExceptionDestroyedCount = 0
        private var ioExceptionDestroyedCount = 0
        private var interruptedExceptionDestroyedCount = 0
        private var unknownDestroyedCount = 0
        private var aliveCount = 0

        fun update(wpm: WorkerProcessMetrics) {
            val numWorkers: Int = wpm.getWorkerIds().size
            if (wpm.isNewlyCreated()) {
                createdCount += numWorkers
            }
            val status: WorkerProcessStatus = wpm.getStatus()
            if (status.isKilled()) {
                when (status.get()) {
                    WorkerProcessStatus.Status.KILLED_UNKNOWN -> unknownDestroyedCount += numWorkers
                    WorkerProcessStatus.Status.KILLED_DUE_TO_INTERRUPTED_EXCEPTION -> interruptedExceptionDestroyedCount += numWorkers
                    WorkerProcessStatus.Status.KILLED_DUE_TO_IO_EXCEPTION -> ioExceptionDestroyedCount += numWorkers
                    WorkerProcessStatus.Status.KILLED_DUE_TO_MEMORY_PRESSURE -> evictedCount += numWorkers
                    WorkerProcessStatus.Status.KILLED_DUE_TO_USER_EXEC_EXCEPTION -> userExecExceptionDestroyedCount += numWorkers
                    else -> {}
                }
                destroyedCount += numWorkers
            } else {
                aliveCount += numWorkers
            }
        }

        fun build(): WorkerPoolStats {
            return WorkerPoolMetrics.WorkerPoolStats.newBuilder()
                .setMnemonic(mnemonic)
                .setHash(hash)
                .setCreatedCount(createdCount)
                .setDestroyedCount(destroyedCount)
                .setEvictedCount(evictedCount)
                .setUserExecExceptionDestroyedCount(userExecExceptionDestroyedCount)
                .setIoExceptionDestroyedCount(ioExceptionDestroyedCount)
                .setInterruptedExceptionDestroyedCount(interruptedExceptionDestroyedCount)
                .setUnknownDestroyedCount(unknownDestroyedCount)
                .setAliveCount(aliveCount)
                .build()
        }
    }

    private class ActionStats(val mnemonic: String?) {
        val firstStarted: LongAccumulator
        val lastEnded: LongAccumulator
        val numActionsExecuted: AtomicLong
        val numActionsRegistered: AtomicLong
        val systemTime: AtomicLong
        val userTime: AtomicLong

        init {
            firstStarted = LongAccumulator(
                LongBinaryOperator { a: Long, b: Long -> java.lang.Math.min(a, b) },
                Long.Companion.MAX_VALUE
            )
            lastEnded = LongAccumulator(LongBinaryOperator { a: Long, b: Long -> java.lang.Math.max(a, b) }, 0)
            numActionsExecuted = AtomicLong()
            numActionsRegistered = AtomicLong()
            systemTime = AtomicLong()
            userTime = AtomicLong()
        }
    }

    /* Collects stats about dynamic execution races  of remote vs local branches **/
    internal class DynamicExecutionStats {
        // Mapping from tuple <mnemonic, local branch name, remote branch name> to pair of numbers,
        // which represents corresponding number of wins of local and remote branches.
        val branchWinners: ConcurrentHashMap<RaceIdentifier, RaceWinners>

        init {
            this.branchWinners = ConcurrentHashMap<RaceIdentifier, RaceWinners>()
        }

        fun update(menemonic: String?, localName: String?, remoteName: String?, winner: DynamicMode) {
            branchWinners.compute(
                RaceIdentifier.Companion.create(menemonic, localName, remoteName)
            ) { k: RaceIdentifier?, oldValue: RaceWinners ->
                var newValue = RaceWinners( /* localWins= */0,  /* remoteWins= */0)
                if (oldValue != null) {
                    newValue = oldValue
                }

                when (winner) {
                    LOCAL -> newValue.incrementLocalWins()
                    REMOTE -> newValue.incrementRemoteWins()
                }
                newValue
            }
        }

        internal class RaceWinners(private var localWins: Int, private var remoteWins: Int) {
            fun getLocalWins(): Int {
                return localWins
            }

            fun getRemoteWins(): Int {
                return remoteWins
            }

            fun incrementLocalWins() {
                localWins++
            }

            fun incrementRemoteWins() {
                remoteWins++
            }
        }

        @kotlin.jvm.JvmRecord
        internal data class RaceIdentifier(val mnemonic: String?, val localName: String?, val remoteName: String?) {
            init {
                java.util.Objects.requireNonNull<String?>(mnemonic, "mnemonic")
                java.util.Objects.requireNonNull<String?>(localName, "localName")
                java.util.Objects.requireNonNull<String?>(remoteName, "remoteName")
            }

            companion object {
                fun create(mnemonic: String?, localName: String?, remoteName: String?): RaceIdentifier {
                    return RaceIdentifier(mnemonic, localName, remoteName)
                }
            }
        }

        fun toMetrics(): DynamicExecutionMetrics {
            val builder: DynamicExecutionMetrics.Builder = DynamicExecutionMetrics.newBuilder()
            for (raceIdentifier in branchWinners.keys) {
                val raceWinners: RaceWinners = branchWinners.get(raceIdentifier)
                builder.addRaceStatistics(
                    DynamicExecutionMetrics.RaceStatistics.newBuilder()
                        .setMnemonic(raceIdentifier.mnemonic)
                        .setLocalRunner(raceIdentifier.localName)
                        .setRemoteRunner(raceIdentifier.remoteName)
                        .setLocalWins(raceWinners.getLocalWins())
                        .setRemoteWins(raceWinners.getRemoteWins())
                        .build()
                )
            }

            return builder.build()
        }
    }

    companion object {
        fun installInEnv(
            env: CommandEnvironment, numAnalyses: AtomicInteger, numBuilds: AtomicInteger
        ) {
            MetricsCollector(env, numAnalyses, numBuilds)
        }

        private const val MAX_ACTION_DATA = 20

        private fun createMemoryMetrics(): MemoryMetrics {
            val memoryMetrics: MemoryMetrics.Builder = MemoryMetrics.newBuilder()
            if (MemoryProfiler.Companion.instance().getHeapUsedMemoryAtFinish() > 0) {
                memoryMetrics.setUsedHeapSizePostBuild(MemoryProfiler.Companion.instance().getHeapUsedMemoryAtFinish())
            }
            setPeakHeapSize(
                PostGCMemoryUseRecorder.Companion.get().getPeakPostGcHeap(), memoryMetrics::setPeakPostGcHeapSize
            )

            if (memoryMetrics.getPeakPostGcHeapSize() < memoryMetrics.getUsedHeapSizePostBuild()) {
                // If we just did a GC and computed the heap size, update the one we got from the GC
                // notification (which may arrive too late for this specific GC).
                memoryMetrics.setPeakPostGcHeapSize(memoryMetrics.getUsedHeapSizePostBuild())
            }

            setPeakHeapSize(
                PostGCMemoryUseRecorder.Companion.get().getPeakPostGcHeapTenuredSpace(),
                memoryMetrics::setPeakPostGcTenuredSpaceHeapSize
            )

            setPeakHeapSize(
                PostGCMemoryUseRecorder.Companion.get().getPeakPostGcHeapDuringExecution(),
                memoryMetrics::setPeakPostGcHeapSizeDuringExecution
            )

            setPeakHeapSize(
                PostGCMemoryUseRecorder.Companion.get().getPeakPostGcHeapTenuredSpaceDuringExecution(),
                memoryMetrics::setPeakPostGcTenuredSpaceHeapSizeDuringExecution
            )

            val garbageStats: MutableMap<String?, Long?> = PostGCMemoryUseRecorder.Companion.get().getGarbageStats()
            for (garbageEntry in garbageStats.entries) {
                val garbageMetrics: GarbageMetrics.Builder = GarbageMetrics.newBuilder()
                garbageMetrics.setType(garbageEntry.key).setGarbageCollected(garbageEntry.value)
                memoryMetrics.addGarbageMetrics(garbageMetrics.build())
            }

            return memoryMetrics.build()
        }

        private fun setPeakHeapSize(peakHeap: java.util.Optional<PeakHeap?>, setter: java.util.function.LongConsumer) {
            peakHeap.ifPresent(java.util.function.Consumer { peak: PeakHeap? -> setter.accept(peak.bytes) })
        }

        /** Creates the WorkerPoolMetrics by aggregating the collected WorkerProcessMetrics.  */
        fun createWorkerPoolMetrics(
            collectedWorkerProcessMetrics: com.google.common.collect.ImmutableList<WorkerProcessMetrics>
        ): WorkerPoolMetrics {
            val aggregatedPoolStats: HashMap<Int?, WorkerPoolStats> = HashMap<Int?, WorkerPoolStats>()
            for (wpm in collectedWorkerProcessMetrics) {
                val poolStats: WorkerPoolStats =
                    aggregatedPoolStats.computeIfAbsent(
                        wpm.getWorkerKeyHash()
                    ) { hash: Int? -> WorkerPoolStats(wpm.getMnemonic(), hash!!) }
                poolStats.update(wpm)
            }
            return WorkerPoolMetrics.newBuilder()
                .addAllWorkerPoolStats(
                    aggregatedPoolStats.values.stream()
                        .map<WorkerPoolStats?> { obj: WorkerPoolStats? -> obj!!.build() }
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>()))
                .build()
        }
    }
}
