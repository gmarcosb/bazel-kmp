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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionExecutionException

/** CompletionFunction builds the artifactsToBuild collection of a [ConfiguredTarget].  */
class CompletionFunction<ValueT : ConfiguredObjectValue?, ResultT : SkyValue?, KeyT : TopLevelActionLookupKeyWrapper?>
internal constructor(
    pathResolverFactory: PathResolverFactory?,
    completor: Completor<ValueT?, ResultT?, KeyT?>?,
    skyframeActionExecutor: SkyframeActionExecutor?,
    topLevelArtifactsMetric: FilesMetricConsumer?,
    actionRewindStrategy: ActionRewindStrategy?,
    bugReporter: BugReporter?
) : SkyFunction {
    /**
     * A strategy for completing the build.
     * 
     * 
     * Any Skyframe lookups in methods passed an [Environment] must return an already-done
     * value. For example, it is acceptable to call [ ][ConfiguredTargetAndData.fromExistingConfiguredTargetInSkyframe].
     */
    internal interface Completor<ValueT, ResultT : SkyValue?, KeyT : TopLevelActionLookupKeyWrapper?> {
        /** Creates an event reporting an absent input artifact.  */
        @Throws(java.lang.InterruptedException::class)
        fun getRootCauseError(
            key: KeyT?,
            value: ValueT?,
            rootCause: LabelCause?,
            env: SkyFunction.Environment?
        ): com.google.devtools.build.lib.events.Event?

        @Throws(java.lang.InterruptedException::class)
        fun getLocationIdentifier(key: KeyT?, value: ValueT?, env: SkyFunction.Environment?): Any?

        /** Provides a successful completion value.  */
        val result: ResultT?

        /**
         * Creates a failed completion event.
         * 
         * 
         * The event must be [stored][Postable.storeForReplay].
         */
        @Throws(java.lang.InterruptedException::class)
        fun createFailed(
            skyKey: KeyT?,
            value: ValueT?,
            rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?,
            ctx: CompletionContext?,
            outputs: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?>?,
            env: SkyFunction.Environment?
        ): Postable

        /**
         * Creates a succeeded completion event.
         * 
         * 
         * The event must be [stored][Postable.storeForReplay].
         */
        @Throws(java.lang.InterruptedException::class)
        fun createSucceeded(
            skyKey: KeyT?,
            value: ValueT?,
            completionContext: CompletionContext?,
            artifactsToBuild: ArtifactsToBuild?,
            env: SkyFunction.Environment?
        ): EventReportingArtifacts?
    }

    private val pathResolverFactory: PathResolverFactory
    private val completor: Completor<ValueT?, ResultT?, KeyT?>
    private val skyframeActionExecutor: SkyframeActionExecutor
    private val topLevelArtifactsMetric: FilesMetricConsumer
    private val actionRewindStrategy: ActionRewindStrategy
    private val bugReporter: BugReporter

    init {
        this.pathResolverFactory =
            com.google.common.base.Preconditions.checkNotNull<PathResolverFactory>(pathResolverFactory)
        this.completor =
            com.google.common.base.Preconditions.checkNotNull<Completor<ValueT?, ResultT?, KeyT?>>(completor)
        this.skyframeActionExecutor =
            com.google.common.base.Preconditions.checkNotNull<SkyframeActionExecutor>(skyframeActionExecutor)
        this.topLevelArtifactsMetric =
            com.google.common.base.Preconditions.checkNotNull<FilesMetricConsumer>(topLevelArtifactsMetric)
        this.actionRewindStrategy =
            com.google.common.base.Preconditions.checkNotNull<ActionRewindStrategy>(actionRewindStrategy)
        this.bugReporter = com.google.common.base.Preconditions.checkNotNull<BugReporter>(bugReporter)
    }

    @Throws(CompletionFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val key = skyKey as KeyT?
        val valueAndArtifactsToBuild: com.google.devtools.build.lib.util.Pair<ValueT?, ArtifactsToBuild?>? =
            Companion.getValueAndArtifactsToBuild<ValueT?>(key, env)
        if (env.valuesMissing()) {
            return null
        }
        val value: ValueT? = valueAndArtifactsToBuild.first
        val artifactsToBuild: ArtifactsToBuild? = valueAndArtifactsToBuild.second

        val allArtifacts: com.google.common.collect.ImmutableList<Artifact> =
            artifactsToBuild.getAllArtifacts().toList()
        val inputDeps: SkyframeLookupResult = env.getValuesAndExceptions(Artifact.keys(allArtifacts))

        val allArtifactsAreImportant: Boolean = artifactsToBuild.areAllOutputGroupsImportant()

        val inputMap: ActionInputMap = ActionInputMap(allArtifacts.size())
        // Prepare an ActionInputMap for important artifacts separately, to be used by BEP events. The
        // _validation output group can contain orders of magnitude more unimportant artifacts than
        // there are important artifacts, and BEP events will retain the ActionInputMap until the
        // event is delivered to transports. If the BEP events reference *all* artifacts it can increase
        // heap high-watermark by multiple GB.
        val importantInputMap: ActionInputMap?
        val importantArtifacts: com.google.common.collect.ImmutableCollection<Artifact>
        if (allArtifactsAreImportant) {
            importantArtifacts = allArtifacts
            importantInputMap = inputMap
        } else {
            importantArtifacts = artifactsToBuild.getImportantArtifacts().toSet()
            importantInputMap = ActionInputMap(importantArtifacts.size())
        }

        var worstActionExecutionException: ActionExecutionException? = null
        val rootCausesBuilder: NestedSetBuilder<com.google.devtools.build.lib.causes.Cause?> =
            NestedSetBuilder.stableOrder()
        val builtArtifacts: MutableSet<Artifact?> = HashSet<Artifact?>()
        // Don't double-count files due to Skyframe restarts.
        val currentConsumer: FilesMetricConsumer = FilesMetricConsumer()
        for (input in allArtifacts) {
            try {
                val artifactValue: SkyValue? =
                    inputDeps.getOrThrow<E1?, E2?>(
                        Artifact.key(input), ActionExecutionException::class.java, SourceArtifactException::class.java
                    )
                if (artifactValue == null) {
                    continue
                }
                if (artifactValue is MissingArtifactValue) {
                    handleSourceFileError(
                        input,
                        (artifactValue as MissingArtifactValue).getDetailedExitCode(),
                        rootCausesBuilder,
                        env,
                        value,
                        key
                    )
                } else {
                    builtArtifacts.add(input)
                    ActionInputMapHelper.addToMap(inputMap, input, artifactValue, currentConsumer)
                    if (!allArtifactsAreImportant && importantArtifacts.contains(input)) {
                        // Calling #addToMap a second time with `input` and `artifactValue` will perform no-op
                        // updates to the secondary collections passed in (eg. treeArtifacts, expandedFilesets).
                        // MetadataConsumerForMetrics.NO_OP is used to avoid double-counting.
                        ActionInputMapHelper.addToMap(
                            importantInputMap, input, artifactValue, MetadataConsumerForMetrics.NO_OP
                        )
                    }
                }
            } catch (e: ActionExecutionException) {
                if (e.getRootCauses().isEmpty()) {
                    BugReport.sendNonFatalBugReport(
                        java.lang.IllegalStateException(
                            "Caught ActionExecutionException from %s with no root causes".formatted(input),
                            e
                        )
                    )
                } else {
                    rootCausesBuilder.addTransitive(e.getRootCauses())
                }
                // Prefer a catastrophic exception as the one we propagate.
                if (worstActionExecutionException == null) {
                    worstActionExecutionException = e
                } else {
                    worstActionExecutionException =
                        SEVERITY_ORDERING.max<ActionExecutionException?>(worstActionExecutionException, e)
                }
            } catch (e: SourceArtifactException) {
                if (!input.isSourceArtifact()) {
                    bugReporter.logUnexpected(
                        e, "Non-source artifact had SourceArtifactException: %s", input
                    )
                }
                handleSourceFileError(input, e.getDetailedExitCode(), rootCausesBuilder, env, value, key)
            }
        }
        val ctx: CompletionContext =
            CompletionContext.create(
                key.topLevelArtifactContext().expandFilesets(), importantInputMap, pathResolverFactory
            )

        val rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?> = rootCausesBuilder.build()
        if (!rootCauses.isEmpty()) {
            var rewindPlanResult: RewindPlanResult? = null
            if (!builtArtifacts.isEmpty()) {
                // In error bubbling, we may be interrupted by Skyframe. Ensure that the interrupt doesn't
                // prevent us from staging built artifacts and posting the failed event.
                val interruptedDuringErrorBubbling = env.inErrorBubbling() && java.lang.Thread.interrupted()
                try {
                    rewindPlanResult =
                        informImportantOutputHandler(
                            key,
                            value,
                            env,
                            com.google.common.collect.ImmutableList.copyOf<Artifact?>(
                                if (allArtifactsAreImportant)
                                    builtArtifacts
                                else
                                    com.google.common.collect.Iterables.filter<Artifact?>(
                                        builtArtifacts,
                                        com.google.common.base.Predicate { `object`: Artifact? ->
                                            importantArtifacts.contains(`object`)
                                        })
                            ),
                            rootCauses,
                            ctx,
                            artifactsToBuild,
                            builtArtifacts,
                            inputMap
                        )
                } finally {
                    if (interruptedDuringErrorBubbling) {
                        java.lang.Thread.currentThread().interrupt()
                    }
                }
            }
            postFailedEvent(key, value, rootCauses, ctx, artifactsToBuild, builtArtifacts, env)
            if (rewindPlanResult != null) {
                // Only return a reset after posting the failed event. If we're in --nokeep_going mode, the
                // attempt to rewind will be ignored, so this is our only opportunity to post the event. If
                // we're in --keep_going mode, rewinding will take place, the event won't actually get
                // emitted (per the spec of SkyFunction.Environment#getListener for stored events), and
                // we'll get another opportunity to post an event after rewinding.
                return rewindPlanResult.toNullIfMissingDependenciesElseReset()
            }
            if (worstActionExecutionException != null) {
                throw CompletionFunctionException(worstActionExecutionException)
            }
            val locationPrefix = completor.getLocationIdentifier(key, value, env)
            val codeAndMessage: com.google.devtools.build.lib.util.Pair<DetailedExitCode?, String?> =
                ActionExecutionFunction.Companion.createSourceErrorCodeAndMessage(rootCauses.toList(), key)
            val message: String?
            if (locationPrefix is net.starlark.java.syntax.Location) {
                message = codeAndMessage.getSecond()
                env.getListener().handle(
                    com.google.devtools.build.lib.events.Event.error(
                        locationPrefix as net.starlark.java.syntax.Location,
                        message
                    )
                )
            } else {
                message = locationPrefix.toString() + " " + codeAndMessage.getSecond()
                env.getListener().handle(com.google.devtools.build.lib.events.Event.error(message))
            }
            throw CompletionFunctionException(
                InputFileErrorException(message, codeAndMessage.getFirst())
            )
        }

        // Only check for missing values *after* reporting errors: if there are missing files in a build
        // with --nokeep_going, there may be missing dependencies during error bubbling, we still need
        // to report the error.
        if (env.valuesMissing()) {
            return null
        }

        val rewindPlanResult: RewindPlanResult? =
            informImportantOutputHandler(
                key,
                value,
                env,
                importantArtifacts,
                rootCauses,
                ctx,
                artifactsToBuild,
                builtArtifacts,
                inputMap
            )
        if (rewindPlanResult != null) {
            // Either initiates action rewinding to generate lost inputs or requests a Skyframe restart to
            // wait for missing analysis dependencies.
            return rewindPlanResult.toNullIfMissingDependenciesElseReset()
        }

        val event: Postable? = completor.createSucceeded(key, value, ctx, artifactsToBuild, env)
        checkStored(event, key)
        env.getListener().post(event)
        topLevelArtifactsMetric.mergeIn(currentConsumer)

        return completor.result
    }

    @Throws(java.lang.InterruptedException::class)
    private fun postFailedEvent(
        key: KeyT?,
        value: ValueT?,
        rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?,
        ctx: CompletionContext?,
        artifactsToBuild: ArtifactsToBuild,
        builtArtifacts: MutableSet<Artifact?>?,
        env: SkyFunction.Environment
    ) {
        val builtOutputs: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?>? =
            SuccessfulArtifactFilter(com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (builtArtifacts))
                .filterArtifactsInOutputGroup(artifactsToBuild.getAllArtifactsByOutputGroup())
        val event: Postable = completor.createFailed(key, value, rootCauses, ctx, builtOutputs, env)
        checkStored(event, key)
        env.getListener().post(event)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun handleSourceFileError(
        input: Artifact,
        detailedExitCode: DetailedExitCode?,
        rootCausesBuilder: NestedSetBuilder<com.google.devtools.build.lib.causes.Cause?>,
        env: SkyFunction.Environment,
        value: ValueT?,
        key: KeyT?
    ) {
        val cause: LabelCause =
            ActionExecutionFunction.Companion.createLabelCause(
                input, detailedExitCode, key.actionLookupKey().getLabel(), bugReporter
            )
        rootCausesBuilder.add(cause)
        env.getListener().handle(completor.getRootCauseError(key, value, cause, env))
        skyframeActionExecutor.recordExecutionError()
    }

    /**
     * Calls [ImportantOutputHandler.processOutputsAndGetLostArtifacts].
     * 
     * 
     * If any outputs are lost, returns a [Reset] which can be used to initiate action
     * rewinding and regenerate the lost outputs. Otherwise, returns `null`.
     */
    @Throws(CompletionFunctionException::class, java.lang.InterruptedException::class)
    private fun informImportantOutputHandler(
        key: KeyT?,
        value: ValueT?,
        env: SkyFunction.Environment,
        importantArtifacts: com.google.common.collect.ImmutableCollection<Artifact>,
        rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?,
        ctx: CompletionContext,
        artifactsToBuild: ArtifactsToBuild,
        builtArtifacts: MutableSet<Artifact?>?,
        inputMap: ActionInputMap?
    ): RewindPlanResult? {
        var rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>? = rootCauses
        val importantOutputHandler: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeActionExecutor.getActionContextRegistry().getContext(ImportantOutputHandler::class.java)
        if (importantOutputHandler == null) {
            return null
        }

        val label: Label = key.actionLookupKey().getLabel()
        val metadataProvider: InputMetadataProvider =
            ActionInputMetadataProvider(
                if (importantOutputHandler.requiresHiddenOutputMetadata())
                    inputMap
                else
                    ctx.getImportantInputMap()
            )
        try {
            val lostOutputs: LostArtifacts
            GoogleAutoProfilerUtils.profiledAndLogged(
                "Informing important output handler of top-level outputs for " + label,
                ProfilerTask.INFO,
                ImportantOutputHandler.LOG_THRESHOLD
            ).use { ignored ->
                lostOutputs =
                    importantOutputHandler.processOutputsAndGetLostArtifacts(
                        if (key.topLevelArtifactContext().expandFilesets())
                            importantArtifacts
                        else
                            com.google.common.collect.Iterables.filter<T?>(
                                importantArtifacts,
                                com.google.common.base.Predicate { artifact: T? -> !artifact.isFileset() }),
                        metadataProvider
                    )
            }
            if (lostOutputs.isEmpty()) {
                return null
            }

            var artifactsRelevantForRewinding: Iterable<Artifact>? = importantArtifacts
            if (importantOutputHandler.requiresHiddenOutputMetadata()) {
                val hiddenTopLevelArtifacts: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    artifactsToBuild.getAllArtifactsByOutputGroup().get(OutputGroupInfo.HIDDEN_TOP_LEVEL)
                if (hiddenTopLevelArtifacts != null) {
                    artifactsRelevantForRewinding =
                        com.google.common.collect.Iterables.concat(
                            artifactsRelevantForRewinding, hiddenTopLevelArtifacts.getArtifacts().toList()
                        )
                }
            }

            return actionRewindStrategy.prepareRewindPlanForLostTopLevelOutputs(
                key,
                com.google.common.collect.ImmutableSet.copyOf(Artifact.keys(artifactsRelevantForRewinding)),
                lostOutputs.byDigest(),
                metadataProvider,
                builtArtifacts,
                env
            )
        } catch (e: ActionRewindException) {
            val cause: LabelCause = LabelCause(label, e.getDetailedExitCode())
            rootCauses = NestedSetBuilder.fromNestedSet(rootCauses).add(cause).build()
            env.getListener().handle(completor.getRootCauseError(key, value, cause, env))
            skyframeActionExecutor.recordExecutionError()
            postFailedEvent(key, value, rootCauses, ctx, artifactsToBuild, builtArtifacts, env)
            throw CompletionFunctionException(
                TopLevelOutputException(e.getMessage(), e.getDetailedExitCode())
            )
        } catch (e: ImportantOutputException) {
            val cause: LabelCause = LabelCause(label, e.getDetailedExitCode())
            rootCauses = NestedSetBuilder.fromNestedSet(rootCauses).add(cause).build()
            env.getListener().handle(completor.getRootCauseError(key, value, cause, env))
            skyframeActionExecutor.recordExecutionError()
            postFailedEvent(key, value, rootCauses, ctx, artifactsToBuild, builtArtifacts, env)
            throw CompletionFunctionException(
                TopLevelOutputException(e.getMessage(), e.getDetailedExitCode())
            )
        }
    }

    override fun extractTag(skyKey: SkyKey): String {
        return Label.print((skyKey as TopLevelActionLookupKeyWrapper).actionLookupKey().getLabel())
    }

    private class CompletionFunctionException : SkyFunctionException {
        private val actionException: ActionExecutionException?

        internal constructor(e: ActionExecutionException?) : super(e, Transience.PERSISTENT) {
            this.actionException = e
        }

        internal constructor(e: InputFileErrorException?) : super(e, Transience.PERSISTENT) {
            // Not transient from the point of view of this SkyFunction.
            this.actionException = null
        }

        internal constructor(e: TopLevelOutputException?) : super(e, Transience.TRANSIENT) {
            this.actionException = null
        }

        val isCatastrophic: Boolean
            get() = actionException != null && actionException.isCatastrophe()
    }

    companion object {
        /**
         * Ordering function that ranks [ActionExecutionException]s by severity, with less severe
         * exceptions comparing "less than" more severe exceptions.
         */
        @kotlin.jvm.JvmField
        @com.google.common.annotations.VisibleForTesting
        val SEVERITY_ORDERING: com.google.common.collect.Ordering<ActionExecutionException?> =
            com.google.common.collect.Ordering.compound<ActionExecutionException?>(
                com.google.common.collect.ImmutableList.of<java.util.Comparator<Any?>?>(
                    java.util.Comparator.comparing<Any?, Any?>(ActionExecutionException::isCatastrophe),
                    java.util.Comparator.comparing<Any?, DetailedExitCode?>(
                        ActionExecutionException::getDetailedExitCode,
                        DetailedExitCodeComparator.INSTANCE
                    )
                )
            )

        @Throws(java.lang.InterruptedException::class)
        fun <ValueT : ConfiguredObjectValue?>
                getValueAndArtifactsToBuild(
            key: TopLevelActionLookupKeyWrapper, env: SkyFunction.Environment
        ): com.google.devtools.build.lib.util.Pair<ValueT?, ArtifactsToBuild?>? {
            val value = env.getValue(key.actionLookupKey()) as ValueT?
            if (env.valuesMissing()) {
                return null
            }

            val topLevelContext: TopLevelArtifactContext? = key.topLevelArtifactContext()
            val artifactsToBuild: ArtifactsToBuild? =
                TopLevelArtifactHelper.getAllArtifactsToBuild(value.configuredObject, topLevelContext)
            return com.google.devtools.build.lib.util.Pair.of<ValueT?, ArtifactsToBuild?>(value, artifactsToBuild)
        }

        private fun checkStored(event: Postable, key: TopLevelActionLookupKeyWrapper?) {
            com.google.common.base.Preconditions.checkState(
                event.storeForReplay(), "Completion events must be stored, got %s for %s", event, key
            )
        }
    }
}
