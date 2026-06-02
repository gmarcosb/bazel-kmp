// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil.configurationIdMessage
import com.google.devtools.build.lib.skyframe.SkyframeErrorProcessor.createDetailedExecutionExitCode

/** A utility class that provides methods to parse errors from Skyframe EvaluationResults.  */
object SkyframeErrorProcessor {
    private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

    /**
     * Process only loading/analysis errors. Returns a [ErrorProcessingResult].
     * 
     * 
     * In case of --nokeep_going: immediately throw the exception.
     */
    @Throws(java.lang.InterruptedException::class, ViewCreationFailedException::class)
    fun processAnalysisErrors(
        result: EvaluationResult<out SkyValue?>,
        cyclesReporter: CyclesReporter,
        eventHandler: ExtendedEventHandler,
        keepGoing: Boolean,
        keepEdges: Boolean,
        eventBus: com.google.common.eventbus.EventBus?,
        bugReporter: BugReporter?
    ): ErrorProcessingResult {
        try {
            return processErrors(
                result,
                cyclesReporter,
                eventHandler,
                keepGoing,
                keepEdges,
                eventBus,
                bugReporter,  /* includeExecutionPhase= */
                false
            )
        } catch (unexpected: BuildFailedException) {
            throw java.lang.IllegalStateException("Unexpected execution phase exception: ", unexpected)
        } catch (unexpected: TestExecException) {
            throw java.lang.IllegalStateException("Unexpected execution phase exception: ", unexpected)
        }
    }

    /** Process only execution errors. Returns a [ErrorProcessingResult].  */
    @Throws(java.lang.InterruptedException::class, BuildFailedException::class, TestExecException::class)
    fun processExecutionErrors(
        result: EvaluationResult<out SkyValue?>,
        cyclesReporter: CyclesReporter,
        eventHandler: ExtendedEventHandler,
        keepGoing: Boolean,
        keepEdges: Boolean,
        eventBus: com.google.common.eventbus.EventBus?,
        bugReporter: BugReporter?,
        skyframeErrorHandlingRefactor: Boolean
    ): ErrorProcessingResult {
        if (skyframeErrorHandlingRefactor) {
            try {
                return processErrors(
                    result,
                    cyclesReporter,
                    eventHandler,
                    keepGoing,
                    keepEdges,
                    eventBus,
                    bugReporter,  /* includeExecutionPhase= */
                    true
                )
            } catch (unexpected: ViewCreationFailedException) {
                throw java.lang.IllegalStateException("Unexpected analysis phase exception: ", unexpected)
            }
        }
        val executionErrorExitCode: DetailedExitCode? =
            processResult(eventHandler, result, keepGoing, cyclesReporter, bugReporter)
        return ErrorProcessingResult.Companion.newBuilder()
            .setExecutionDetailedExitCode(executionErrorExitCode)
            .build()
    }

    /**
     * Process errors encountered during analysis/execution.
     * 
     * 
     * This method has different goals depending on --(no)keep_going:
     * 
     * 
     *  * In case of --keep_going: post the necessary events, then construct an [       ].
     *  * In case of --nokeep_going: post the necessary events, then throw an appropriate exception
     * ASAP, except when the error is caused by an action conflict: we need more downstream
     * information.
     * 
     * 
     * 
     * Visible only for use by tests via [ ][SkyframeExecutor.getConfiguredTargetMapForTesting]. When called there, `eventBus` must be null to
     * indicate that this is a test, and so there may be additional [SkyKey]s in the `result` that are not [AspectKeyCreator]s or [ConfiguredTargetKey]s. Those keys will
     * be ignored.
     * 
     * @throws ViewCreationFailedException when the root cause is analysis-related.
     * @throws BuildFailedException when the root cause is execution-related.
     * @throws TestExecException when the root cause is test-related.
     * @return an ErrorProcessingResult (only in --keep_going mode, or action conflict).
     */
    @Throws(
        java.lang.InterruptedException::class,
        ViewCreationFailedException::class,
        BuildFailedException::class,
        TestExecException::class
    )
    fun processErrors(
        result: EvaluationResult<out SkyValue?>,
        cyclesReporter: CyclesReporter,
        eventHandler: ExtendedEventHandler,
        keepGoing: Boolean,
        keepEdges: Boolean,
        eventBus: com.google.common.eventbus.EventBus?,
        bugReporter: BugReporter?,
        includeExecutionPhase: Boolean
    ): ErrorProcessingResult {
        val inBuildViewTest = eventBus == null
        var noKeepGoingAnalysisExceptionAspect: ViewCreationFailedException? = null
        val aggregatingResultBuilder: AggregatingBuilder =
            ErrorProcessingResult.Companion.newBuilder()

        for (errorEntry in result.errorMap().entrySet()) {
            val errorInfo: com.google.devtools.build.skyframe.ErrorInfo = errorEntry.getValue()

            // The cycle reporter requires that the path to the cycle starts at the top level key
            // (requested via SkyframeExecutor), hence we need to provide the original top level key here.
            //
            // Why is there a need for "original" vs "effective" error key?
            // 1) The non-skymeld code path deals with ActionLookupKeys as the top level key,
            // 2) We wanted to share the error handling code between skymeld and non skymeld.
            // To do so, we need to "normalize" the top level key in Skymeld mode by getting the effective
            // ActionLookupKey from a BuildDriverKey. The rest of the method can then be easily shared.
            cyclesReporter.reportCycles(
                errorInfo.getCycleInfo(),  /*topLevelKey=*/errorEntry.getKey(), eventHandler
            )

            val errorKey: SkyKey = getEffectiveErrorKey(errorEntry)
            if (includeExecutionPhase) {
                assertValidAnalysisOrExecutionException(
                    errorInfo, errorKey, result.getWalkableGraph(), keepEdges
                )
            } else {
                assertValidAnalysisException(errorInfo, errorKey, result.getWalkableGraph(), keepEdges)
            }
            val nullableCause: java.lang.Exception? = errorInfo.getException()
            com.google.common.base.Preconditions.checkState(
                nullableCause != null || !errorInfo.getCycleInfo().isEmpty(), errorInfo
            )

            // TODO(b/249690006): Can we remove this divergence?
            if (inBuildViewTest && !isValidErrorKeyType(errorKey.argument())) {
                // This means that we are in a BuildViewTestCase.
                //
                // Tests don't call target pattern parsing before requesting the analysis of a target.
                // Therefore if the package that contains them cannot be loaded, we get an error key that's
                // not a ConfiguredTargetKey, which cannot happen in production code.
                //
                // If it's an existing target in a nonexistent package, the error is signaled by posting an
                // AnalysisFailureEvent on the event bus, which is null in when running a BuildViewTestCase,
                // so we emit the root cause labels directly to the event handler below.
                eventHandler.handle(com.google.devtools.build.lib.events.Event.error(errorInfo.toString()))
                continue
            }

            val label: Label? = getLabel(errorKey)
            val individualErrorProcessingResult =
                processIndividualError(result, bugReporter, errorKey, errorInfo)

            // For action conflicts, more downstream operations are required to have all the
            // information. We intentionally don't send out any failure event, throw any exception (even
            // with --nokeep_going) or print a warning message at this point. These will be done elsewhere
            // at a later point.
            if (individualErrorProcessingResult.isActionConflictError) {
                aggregatingResultBuilder.aggregateSingleResult(individualErrorProcessingResult)
                continue
            }

            maybePostFailureEventsForNonConflictError(
                eventHandler,
                eventBus,
                inBuildViewTest,
                errorKey,
                label,
                individualErrorProcessingResult
            )

            val isExecutionException = isExecutionException(nullableCause)
            if (keepGoing) {
                aggregatingResultBuilder.aggregateSingleResult(individualErrorProcessingResult)
                logOrPrintWarningsKeepGoing(isExecutionException, label, eventHandler, nullableCause)
            } else {
                noKeepGoingAnalysisExceptionAspect =
                    throwOrReturnAspectAnalysisException(
                        result,
                        nullableCause,
                        bugReporter,
                        errorKey,
                        isExecutionException,  /* hasExecutionCycle= */
                        CYCLE_CODE == individualErrorProcessingResult.executionDetailedExitCode
                    )
            }
        }

        if (noKeepGoingAnalysisExceptionAspect != null) {
            throw noKeepGoingAnalysisExceptionAspect
        }

        return aggregatingResultBuilder.build()
    }

    /*
   * Post the relevant failure events if we're not in test.
   *
   * <p>There is 1 exception: for aspects, the failures should already have been reported to the
   * event handler, so we do nothing here.
   */
    private fun maybePostFailureEventsForNonConflictError(
        eventHandler: ExtendedEventHandler,
        eventBus: com.google.common.eventbus.EventBus?,
        inBuildViewTest: Boolean,
        errorKey: SkyKey,
        label: Label?,
        individualErrorProcessingResult: IndividualErrorProcessingResult
    ) {
        com.google.common.base.Preconditions.checkState(!individualErrorProcessingResult.isActionConflictError)
        if (inBuildViewTest) {
            // eventBus is null, but tests can still assert on the expected root causes being found.
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.error(
                    individualErrorProcessingResult.analysisRootCauses.toList().toString()
                )
            )
            return
        }

        com.google.common.base.Preconditions.checkNotNull<com.google.common.eventbus.EventBus?>(eventBus)
        if (errorKey !is ConfiguredTargetKey) {
            return
        }

        val ctKey: ConfiguredTargetKey? = errorKey.argument() as ConfiguredTargetKey?
        // For loading errors, we expect both LoadingFailureEvent and AnalysisFailureEvent.
        if (individualErrorProcessingResult.isLoadingError) {
            for (loadingRootCause in individualErrorProcessingResult.loadingRootCauses) {
                // This event is only for backwards compatibility with the old event protocol. Remove
                // once we've migrated to the build event protocol.
                eventBus.post(
                    LoadingFailureEvent(
                        com.google.common.base.Preconditions.checkNotNull<T?>(label),
                        loadingRootCause
                    )
                )
            }
        }

        if (individualErrorProcessingResult.isAnalysisError) {
            eventBus.post(
                AnalysisFailureEvent.whileAnalyzingTarget(
                    ctKey, individualErrorProcessingResult.analysisRootCauses
                )
            )
        }
    }

    /**
     * Throw the necessary exceptions based on the error processing result.
     * 
     * 
     * This method should be called in --nokeep_going mode, unless the error is an action conflict.
     * 
     * 
     * Special case: if the analysis error belongs to a top-level Aspect, we don't throw the
     * ViewCreationFailedException immediately to make sure that a target analysis error is preferred
     * over an aspect one.
     * 
     * @throws ViewCreationFailedException when the root cause is analysis-related.
     * @throws BuildFailedException when the root cause is execution-related.
     * @throws TestExecException when the root cause is test-related.
     * @return a ViewCreationFailedException if the error belongs to a top-level Aspect.
     */
    @Throws(BuildFailedException::class, TestExecException::class, ViewCreationFailedException::class)
    private fun throwOrReturnAspectAnalysisException(
        result: EvaluationResult<out SkyValue?>?,
        cause: java.lang.Exception?,
        bugReporter: BugReporter,
        errorKey: SkyKey,
        isExecutionException: Boolean,
        hasExecutionCycle: Boolean
    ): ViewCreationFailedException {
        // If the error is execution-related: straightaway rethrow. No further steps required.
        if (isExecutionException) {
            // cause is not null for execution exceptions.
            com.google.common.base.Preconditions.checkNotNull<java.lang.Exception?>(cause)
            rethrow(cause, bugReporter, result)
        }
        // If a --nokeep_going build found a cycle, that means there were no other errors thrown
        // during evaluation (otherwise, it wouldn't have bothered to find a cycle). So the best
        // we can do is throw a generic build failure exception, since we've already reported the
        // cycles above. Analysis cycles are handled below.
        if (hasExecutionCycle) {
            throw BuildFailedException(null, CYCLE_CODE)
        }

        if (errorKey is TopLevelAspectsKey) {
            val aspectKey: TopLevelAspectsKey = errorKey.argument() as TopLevelAspectsKey
            val errorMsg: String? =
                java.lang.String.format(
                    "Analysis of aspects '%s' failed; build aborted", aspectKey.getDescription()
                )
            return createViewCreationFailedException(cause, errorMsg)
        }

        val topLevelLabel: Label? = (errorKey as ConfiguredTargetKey).getLabel()
        throw createViewCreationFailedException(
            cause, java.lang.String.format("Analysis of target '%s' failed; build aborted", topLevelLabel)
        )
    }

    /**
     * Processes one individual error from the result.
     * 
     * 
     * No exception should ever be thrown here: this is just to gather the relevant information
     * around 1 single error. [.processErrors] will decide what to do with this information.
     */
    private fun processIndividualError(
        result: EvaluationResult<out SkyValue?>?,
        bugReporter: BugReporter,
        errorKey: SkyKey,
        errorInfo: com.google.devtools.build.skyframe.ErrorInfo
    ): IndividualErrorProcessingResult {
        val exception: java.lang.Exception? = errorInfo.getException()
        val loadingRootCauses: MutableSet<Label?> = com.google.common.collect.Sets.newHashSet<Label?>()
        var actionConflicts: com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?>? =
            com.google.common.collect.ImmutableMap.of<ActionAnalysisMetadata?, ActionConflictException?>()
        var executionDetailedExitCode: DetailedExitCode? = null
        var aspectKeyForConflictReporting: ActionLookupKey? = null

        // Legacy: analysis-related failure events for Aspects are sent somewhere else, so we don't have
        // to do any work related to constructing the analysis failure events here, only for the other
        // cases like action conflict or execution-related errors.
        // TODO(b/249690006): Can we simplify things by moving aspects events here?
        if (errorKey.argument() is AspectBaseKey) {
            if (exception is TopLevelConflictException) {
                actionConflicts = exception.getTransitiveActionConflicts()
            } else if (exception is ActionConflictException) {
                actionConflicts =
                    com.google.common.collect.ImmutableMap.of<K?, V?>(exception.getAttemptedAction(), exception)
                aspectKeyForConflictReporting = exception.getAspectKey()
            } else if (isExecutionException(exception)) {
                executionDetailedExitCode =
                    getExecutionDetailedExitCodeFromCause(result, exception, bugReporter)
            } else if (!errorInfo.getCycleInfo().isEmpty()
                && isExecutionCycle(errorInfo.getCycleInfo())
            ) {
                executionDetailedExitCode = CYCLE_CODE
            }
            return IndividualErrorProcessingResult.Companion.create(
                actionConflicts,
                executionDetailedExitCode,  /* analysisRootCauses= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* loadingRootCauses= */
                com.google.common.collect.ImmutableSet.of<Label?>(),
                aspectKeyForConflictReporting
            )
        }

        // Only possible with actions generating build-info.txt and build-changelist.txt.
        if (errorKey.argument() is ActionLookupData) {
            return IndividualErrorProcessingResult.Companion.create( /* actionConflicts= */
                com.google.common.collect.ImmutableMap.of<ActionAnalysisMetadata?, ActionConflictException?>(),
                getExecutionDetailedExitCodeFromCause(result, exception, bugReporter),  /* analysisRootCauses= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* loadingRootCauses= */
                com.google.common.collect.ImmutableSet.of<Label?>(),  /* aspectKeyForConflictReporting= */
                null
            )
        }

        com.google.common.base.Preconditions.checkState(
            errorKey.argument() is ConfiguredTargetKey,
            "expected '%s' to be a ConfiguredTargetKey",
            errorKey.argument()
        )
        val ctKey: ConfiguredTargetKey = errorKey.argument() as ConfiguredTargetKey
        val topLevelLabel: Label = ctKey.getLabel()
        val analysisRootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?

        if (exception is TopLevelConflictException) {
            actionConflicts = exception.getTransitiveActionConflicts()
            analysisRootCauses = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        } else if (exception is ActionConflictException) {
            actionConflicts =
                com.google.common.collect.ImmutableMap.of<K?, V?>(exception.getAttemptedAction(), exception)
            analysisRootCauses = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        } else if (exception is ConfiguredValueCreationException) {
            // Previously, the nested set was de-duplicating loading root cause labels. Now that we
            // track Cause instances including a message, we get one event per label and message. In
            // order to keep backwards compatibility, we deduplicate root cause labels here.
            // TODO(ulfjack): Remove this code once we've migrated to the BEP.
            for (rootCause in exception.getRootCauses().toList()) {
                if (rootCause is LoadingFailedCause) {
                    loadingRootCauses.add(rootCause.label)
                }
            }
            analysisRootCauses = exception.getRootCauses()
        } else if (!errorInfo.getCycleInfo().isEmpty()) {
            if (isExecutionCycle(errorInfo.getCycleInfo())) {
                // If we have a cycle, cause would be null, so it's guaranteed that this
                // executionDetailedExitCode is final.
                executionDetailedExitCode = CYCLE_CODE
                analysisRootCauses = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            } else {
                val analysisRootCause: Label? =
                    maybeGetConfiguredTargetCycleCulprit(topLevelLabel, errorInfo.getCycleInfo())
                analysisRootCauses =
                    if (analysisRootCause != null)
                        NestedSetBuilder.create(
                            Order.STABLE_ORDER,
                            LabelCause(
                                analysisRootCause,
                                DetailedExitCode.of(createFailureDetail("Dependency cycle", Code.CYCLE))
                            )
                        ) // TODO(ulfjack): We need to report the dependency cycle here. How?
                    else
                        NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            }
        } else if (exception is NoSuchThingException) {
            // This branch is only taken in --nokeep_going builds. In a --keep_going build, the
            // AnalysisFailedCause is properly reported through the ConfiguredValueCreationException.
            val analysisFailedCause: AnalysisFailedCause =
                AnalysisFailedCause(
                    topLevelLabel,
                    configurationIdMessage(ctKey.getConfigurationKey()),
                    (exception as NoSuchThingException).getDetailedExitCode()
                )
            analysisRootCauses = NestedSetBuilder.create(Order.STABLE_ORDER, analysisFailedCause)
        } else if (exception is ExternalDepsException) {
            val analysisFailedCause: AnalysisFailedCause =
                AnalysisFailedCause(
                    topLevelLabel,
                    configurationIdMessage(ctKey.getConfigurationKey()),
                    exception.getDetailedExitCode()
                )
            analysisRootCauses = NestedSetBuilder.create(Order.STABLE_ORDER, analysisFailedCause)
        } else if (exception is TargetCompatibilityCheckException) {
            analysisRootCauses = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        } else if (isExecutionException(exception)) {
            executionDetailedExitCode =
                getExecutionDetailedExitCodeFromCause(result, exception, bugReporter)
            analysisRootCauses =
                if (exception is ActionExecutionException)
                    exception.getRootCauses()
                else
                    NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        } else {
            BugReport.logUnexpected(
                exception, "Unexpected cause encountered while evaluating: %s", errorKey
            )
            analysisRootCauses = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        }

        return IndividualErrorProcessingResult.Companion.create(
            actionConflicts,
            executionDetailedExitCode,
            analysisRootCauses,
            com.google.common.collect.ImmutableSet.copyOf<Label?>(loadingRootCauses),  /* aspectKeyForConflictReporting= */
            null
        )
    }

    private fun getExecutionDetailedExitCodeFromCause(
        result: EvaluationResult<out SkyValue?>?, cause: java.lang.Exception, bugReporter: BugReporter
    ): DetailedExitCode {
        var executionDetailedExitCode: DetailedExitCode? = DetailedException.getDetailedExitCode(cause)
        if (executionDetailedExitCode == null) {
            executionDetailedExitCode =
                sendBugReportAndCreateUnknownExecutionDetailedExitCode(result, cause, bugReporter)
        }
        return executionDetailedExitCode
    }

    private fun sendBugReportAndCreateUnknownExecutionDetailedExitCode(
        result: EvaluationResult<out SkyValue?>?, cause: Throwable, bugReporter: BugReporter
    ): DetailedExitCode {
        // An undetailed exception means we may incorrectly attribute responsibility for the failure:
        // we need to fix that.
        bugReporter.sendNonFatalBugReport(
            java.lang.IllegalStateException(
                "action terminated with unexpected exception with result " + result, cause
            )
        )
        val message =
            "Unexpected exception, please file an issue with the Bazel team: " + cause.getMessage()
        return SkyframeErrorProcessor.createDetailedExecutionExitCode(message, UNKNOWN_EXECUTION)
    }

    private fun logOrPrintWarningsKeepGoing(
        isExecutionException: Boolean,
        topLevelLabel: Label?,
        eventHandler: ExtendedEventHandler,
        cause: java.lang.Exception?
    ) {
        // For execution exceptions, we don't print any extra warning.
        if (isExecutionException) {
            if (isExecutionCauseWorthLogging(cause)) {
                logger.atWarning().withCause(cause).log(
                    "Non-action-execution/input-error exception while building target %s", topLevelLabel
                )
            }
            return
        }
        var message: String? =
            java.lang.String.format(
                "errors encountered while analyzing target '%s', it will not be built.", topLevelLabel
            )
        if (cause != null) {
            message += java.lang.String.format("\n%s", cause.getMessage())
        }
        eventHandler.handle(com.google.devtools.build.lib.events.Event.warn(message))
    }

    private fun isExecutionCauseWorthLogging(cause: Throwable?): Boolean {
        return (cause !is ActionExecutionException) && (cause !is InputFileErrorException) && (cause !is TopLevelOutputException)
    }

    private fun isValidErrorKeyType(errorKey: Any?): Boolean {
        return errorKey is ConfiguredTargetKey || errorKey is AspectBaseKey
    }

    /** Peel away the wrapper layers to get to the ActionLookupKey of the top level target.  */
    private fun getEffectiveErrorKey(errorEntry: MutableMap.MutableEntry<SkyKey, com.google.devtools.build.skyframe.ErrorInfo>): SkyKey {
        if (errorEntry.getKey().argument() is BuildDriverKey) {
            return (errorEntry.getKey().argument() as BuildDriverKey).getActionLookupKey()
        }
        // For exclusive tests.
        if (errorEntry.getKey().argument() is TestCompletionKey) {
            return (errorEntry.getKey().argument() as TestCompletionKey).configuredTargetKey()
        }
        // For non-skymeld action executions.
        if (errorEntry.getKey().argument() is TargetCompletionKey) {
            return (errorEntry.getKey().argument() as TargetCompletionKey).actionLookupKey()
        }
        if (errorEntry.getKey().argument() is AspectCompletionKey) {
            return (errorEntry.getKey().argument() as AspectCompletionKey).actionLookupKey()
        }
        return errorEntry.getKey()
    }

    private fun getLabel(errorKey: SkyKey): Label? {
        return if (errorKey is ActionLookupKey) (errorKey as ActionLookupKey).getLabel() else null
    }

    private fun createViewCreationFailedException(
        e: java.lang.Exception?, errorMsg: String?
    ): ViewCreationFailedException {
        if (e == null) {
            return ViewCreationFailedException(
                errorMsg, createFailureDetail(errorMsg + " due to cycle", Code.CYCLE)
            )
        }
        return ViewCreationFailedException(
            errorMsg, maybeContextualizeFailureDetail(e, errorMsg), e
        )
    }

    /**
     * Returns a [FailureDetail] with message prefixed by `errorMsg` derived from the
     * failure detail in `e` if it's a [DetailedException], and otherwise returns one with
     * `errorMsg` and [Code.UNEXPECTED_ANALYSIS_EXCEPTION].
     */
    private fun maybeContextualizeFailureDetail(
        e: java.lang.Exception?, errorMsg: String?
    ): FailureDetail {
        val detailedException: DetailedException? = convertToAnalysisException(e)
        if (detailedException == null) {
            return createFailureDetail(errorMsg, Code.UNEXPECTED_ANALYSIS_EXCEPTION)
        }
        val originalFailureDetail: FailureDetail =
            detailedException.detailedExitCode.getFailureDetail()
        return originalFailureDetail.toBuilder()
            .setMessage(errorMsg + ": " + originalFailureDetail.getMessage())
            .build()
    }

    private fun createFailureDetail(errorMessage: String?, code: Code?): FailureDetail {
        return FailureDetail.newBuilder()
            .setMessage(errorMessage)
            .setAnalysis(Analysis.newBuilder().setCode(code))
            .build()
    }

    private fun maybeGetConfiguredTargetCycleCulprit(
        labelToLoad: Label?, cycleInfos: Iterable<CycleInfo>
    ): Label? {
        for (cycleInfo in cycleInfos) {
            val culprit: SkyKey? = com.google.common.collect.Iterables.getFirst<SkyKey?>(cycleInfo.getCycle(), null)
            if (culprit == null) {
                continue
            }
            if (culprit.functionName() == SkyFunctions.CONFIGURED_TARGET) {
                return (culprit.argument() as ConfiguredTargetKey).getLabel()
            } else if (culprit.functionName() == TransitiveTargetKey.Companion.NAME) {
                return (culprit as TransitiveTargetKey).getLabel()
            } else {
                return labelToLoad
            }
        }
        return null
    }

    @Throws(java.lang.InterruptedException::class)
    private fun assertValidAnalysisException(
        errorInfo: com.google.devtools.build.skyframe.ErrorInfo,
        key: SkyKey,
        walkableGraph: WalkableGraph,
        keepEdges: Boolean
    ) {
        val cause: Throwable? = errorInfo.getException()
        if (cause == null) {
            // Cycle.
            return
        }

        if (convertToAnalysisException(cause) != null) {
            // Valid exception type.
            return
        }

        logUnexpectedExceptionOrigin(errorInfo, key, walkableGraph, cause, keepEdges)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun assertValidAnalysisOrExecutionException(
        errorInfo: com.google.devtools.build.skyframe.ErrorInfo,
        key: SkyKey,
        walkableGraph: WalkableGraph,
        keepEdges: Boolean
    ) {
        val cause: Throwable? = errorInfo.getException()
        if (cause == null) {
            // Cycle.
            return
        }

        if (convertToAnalysisException(cause) != null || isExecutionException(cause)
            || cause is TopLevelConflictException
        ) {
            // Valid exception type.
            return
        }

        logUnexpectedExceptionOrigin(errorInfo, key, walkableGraph, cause, keepEdges)
    }

    /**
     * Walk the graph to find a path to the lowest-level node that threw unexpected exception and log
     * it.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun logUnexpectedExceptionOrigin(
        errorInfo: com.google.devtools.build.skyframe.ErrorInfo?,
        key: SkyKey,
        walkableGraph: WalkableGraph,
        cause: Throwable,
        keepEdges: Boolean
    ) {
        if (!keepEdges) {
            // Can't traverse the graph to find the origin.
            logUnexpectedException(key, errorInfo, "direct deps not stored")
            return
        }
        val path: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()
        try {
            var currentKey: SkyKey = key
            var foundDep: Boolean
            do {
                path.add(currentKey)
                foundDep = false

                val missingMap: MutableMap<SkyKey?, java.lang.Exception?> =
                    walkableGraph.getMissingAndExceptions(com.google.common.collect.ImmutableList.of<SkyKey?>(currentKey))
                if (missingMap.containsKey(currentKey) && missingMap.get(currentKey) == null) {
                    // This can happen in a no-keep-going build, where we don't write the bubbled-up error
                    // nodes to the graph.
                    break
                }

                for (dep in walkableGraph.getDirectDeps(currentKey)) {
                    if (cause == walkableGraph.getException(dep)) {
                        currentKey = dep
                        foundDep = true
                        break
                    }
                }
            } while (foundDep)
        } finally {
            logUnexpectedException(key, errorInfo, path)
        }
    }

    private fun logUnexpectedException(
        key: SkyKey?,
        errorInfo: com.google.devtools.build.skyframe.ErrorInfo?,
        extraInfo: Any?
    ) {
        BugReport.logUnexpected("Unexpected analysis error: %s -> %s, (%s)", key, errorInfo, extraInfo)
    }

    private fun convertToAnalysisException(cause: Throwable?): DetailedException? {
        // The cause may be NoSuch{Target,Package}Exception if we run the reduced loading phase and then
        // analyze with --nokeep_going.
        if (cause is SaneAnalysisException
            || cause is NoSuchTargetException
            || cause is NoSuchPackageException
            || cause is ExternalDepsException
        ) {
            return cause as DetailedException?
        }
        return null
    }

    private fun isExecutionException(cause: Throwable?): Boolean {
        return cause is ActionExecutionException
                || cause is InputFileErrorException
                || cause is TestExecException // Refer to UnusedInputsFailureIntegrationTest#incrementalFailureOnUnusedInput.
                || cause is ArtifactNestedSetEvalException // For top-level outputs errors in CompletionFunction.
                || cause is TopLevelOutputException
    }

    /**
     * Process an [EvaluationResult], taking into account the keepGoing setting.
     * 
     * 
     * Returns a nullable [DetailedExitCode] value, as follows:
     * 
     * 
     *  1. `null`, if `result` had no errors
     *  1. `e` if result had errors and one of them specified a [DetailedExitCode] value
     * `e`
     *  1. a [DetailedExitCode] with [Execution.Code.NON_ACTION_EXECUTION_FAILURE] if
     * result had errors but none specified a [DetailedExitCode] value
     * 
     * 
     * 
     * Throws on catastrophic failures and, if !keepGoing, on any failure. TODO(b/249690006):
     * Remove this method once the refactor is complete.
     */
    @Throws(BuildFailedException::class, TestExecException::class)
    fun processResult(
        eventHandler: ExtendedEventHandler?,
        result: EvaluationResult<*>,
        keepGoing: Boolean,
        cyclesReporter: CyclesReporter,
        bugReporter: BugReporter?
    ): DetailedExitCode? {
        if (result.hasError()) {
            for (entry in result.errorMap().entrySet()) {
                val cycles: com.google.common.collect.ImmutableList<CycleInfo>? = entry.getValue().getCycleInfo()
                cyclesReporter.reportCycles(cycles, entry.getKey(), eventHandler)
            }

            if (result.getCatastrophe() != null) {
                rethrow(result.getCatastrophe(), bugReporter, result)
            }
            if (keepGoing) {
                return getDetailedExitCodeKeepGoing(result)
            }
            val errorInfo: com.google.devtools.build.skyframe.ErrorInfo =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.skyframe.ErrorInfo>(
                    result.getError(),
                    result
                )
            val exception: java.lang.Exception? = errorInfo.getException()
            if (exception == null) {
                com.google.common.base.Preconditions.checkState(!errorInfo.getCycleInfo().isEmpty(), errorInfo)
                // If a keepGoing=false build found a cycle, that means there were no other errors thrown
                // during evaluation (otherwise, it wouldn't have bothered to find a cycle). So the best
                // we can do is throw a generic build failure exception, since we've already reported the
                // cycles above.
                throw BuildFailedException(null, CYCLE_CODE)
            } else {
                SkyframeErrorProcessor.rethrow(exception, bugReporter, result)
            }
        }

        return null
    }

    private fun getDetailedExitCodeKeepGoing(result: EvaluationResult<*>): DetailedExitCode? {
        // If build fails and keepGoing is true, an exit code is assigned using reported errors
        // in the following order:
        //   1. First infrastructure error with non-null exit code
        //   2. First non-infrastructure error with non-null exit code
        //   3. If the build fails but no interpretable error is specified, BUILD_FAILURE.
        var detailedExitCode: DetailedExitCode? = null
        var undetailedCause: Throwable? = null
        for (error in result.errorMap().entrySet()) {
            val cause: Throwable? = error.getValue().getException()
            if (cause is DetailedException) {
                // Update global exit code when current exit code is not null and global exit code has
                // a lower 'reporting' priority.
                detailedExitCode =
                    DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                        detailedExitCode, (cause as DetailedException).detailedExitCode
                    )
                if (isExecutionCauseWorthLogging(cause)) {
                    logger.atWarning().withCause(cause).log(
                        "Non-action-execution/input-error exception for %s", error
                    )
                }
            } else if (!error.getValue().getCycleInfo().isEmpty()) {
                detailedExitCode =
                    DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                        detailedExitCode, CYCLE_CODE
                    )
            } else {
                undetailedCause = cause
            }
        }
        if (detailedExitCode != null) {
            return detailedExitCode
        }
        return createDetailedExitCodeForUndetailedExecutionCauseKeepGoing(result, undetailedCause)
    }

    /**
     * Figure out why an action's analysis/execution failed and rethrow the right kind of exception.
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(BuildFailedException::class, TestExecException::class)
    fun rethrow(
        cause: Throwable, bugReporter: BugReporter, resultForDebugging: EvaluationResult<*>?
    ) {
        com.google.common.base.Throwables.throwIfUnchecked(cause)
        val innerCause: Throwable? = cause.getCause()
        if (innerCause is TestExecException) {
            throw innerCause
        }
        if (cause is ActionExecutionException) {
            var message: String = cause.getMessage()
            if (cause.getAction() != null) {
                message = cause.getAction().describe() + " failed: " + message
            }
            // Sometimes ActionExecutionExceptions are caused by Actions with no owner.
            if (cause.getLocation() != null) {
                message = cause.getLocation() + " " + message
            }
            throw BuildFailedException(
                message,
                cause.isCatastrophe(),  /* errorAlreadyShown= */
                !cause.showError(),
                cause.getDetailedExitCode()
            )
        }
        if (cause is InputFileErrorException) {
            throw cause
        }
        if (cause is TopLevelOutputException) {
            throw cause
        }

        // We encountered an exception we don't think we should have encountered. This can indicate
        // an exception-processing bug in our code, such as lower level exceptions not being properly
        // handled, or in our expectations in this method.
        if (cause is DetailedException) {
            // The exception escaped Skyframe error bubbling, but its failure detail can still be used.
            bugReporter.logUnexpected(
                cause as java.lang.Exception,
                "action terminated with unexpected exception with result %s",
                resultForDebugging
            )
            throw BuildFailedException(
                cause.getMessage(), (cause as DetailedException).detailedExitCode
            )
        }

        val unknownExitCode: DetailedExitCode =
            sendBugReportAndCreateUnknownExecutionDetailedExitCode(
                resultForDebugging, cause, bugReporter
            )
        throw BuildFailedException(
            com.google.common.base.Preconditions.checkNotNull<Any?>(unknownExitCode.getFailureDetail()).getMessage(),
            unknownExitCode
        )
    }

    private fun createDetailedExitCodeForUndetailedExecutionCauseKeepGoing(
        result: EvaluationResult<*>?, undetailedCause: Throwable?
    ): DetailedExitCode? {
        if (undetailedCause == null) {
            BugReport.sendBugReport("No exceptions found despite error in %s", result)
            return createDetailedExecutionExitCode(
                "keep_going execution failed without an action failure",
                Execution.Code.NON_ACTION_EXECUTION_FAILURE
            )
        }
        BugReport.sendBugReport(
            java.lang.IllegalStateException("No detailed exception found in " + result, undetailedCause)
        )
        return createDetailedExecutionExitCode(
            ("keep_going execution failed without an action failure: "
                    + undetailedCause.getMessage()
                    + " ("
                    + undetailedCause.getClass().getSimpleName()
                    + ")"),
            Execution.Code.NON_ACTION_EXECUTION_FAILURE
        )
    }

    private val CYCLE_CODE: DetailedExitCode =
        createDetailedExecutionExitCode("cycle found during execution", Execution.Code.CYCLE)
    private val UNKNOWN_EXECUTION: Execution? =
        Execution.newBuilder().setCode(Execution.Code.UNEXPECTED_EXCEPTION).build()

    private fun createDetailedExecutionExitCode(
        message: String?, detailedCode: Execution.Code?
    ): DetailedExitCode {
        return createDetailedExecutionExitCode(
            message, Execution.newBuilder().setCode(detailedCode).build()
        )
    }

    private fun createDetailedExecutionExitCode(
        message: String?, execution: Execution?
    ): DetailedExitCode {
        return DetailedExitCode.of(
            FailureDetail.newBuilder().setMessage(message).setExecution(execution).build()
        )
    }

    private fun isExecutionCycle(cycleInfoCollection: Iterable<CycleInfo>): Boolean {
        for (cycleInfo in cycleInfoCollection) {
            if (cycleInfo.getCycle().stream().allMatch(ACTION_OR_ARTIFACT_OR_TRANSITIVE_RDEP)) {
                // All these cycle info belong to the same top level key. If one of them is
                // execution-related, we consider the error to be execution-related.
                return true
            }
        }
        return false
    }

    /**
     * Indicates if there are errors with the various phases, and an exception to be thrown to halt
     * the build, in case of --nokeep_going.
     * 
     * 
     * The various attributes will be used later on to construct the FailureDetail in [ ][com.google.devtools.build.lib.analysis.BuildView.createAnalysisFailureDetail].
     * 
     * @param hasLoadingError whether there are loading errors.
     * @param hasAnalysisError whether there are analysis errors.
     * @param actionConflicts the action conflicts encountered during analysis.
     * @param executionDetailedExitCode the detailed exit code for execution errors. This is
     * 
     *  * `null`, if `result` had no errors or the errors were all analysis errors.
     *  * `e` if result had errors and one of them specified a [DetailedExitCode]
     * value `e`
     *  * a [DetailedExitCode] with [Execution.Code.NON_ACTION_EXECUTION_FAILURE]
     * if result had errors but none specified a [DetailedExitCode] value
     * 
     * 
     * @param aspectKeysForConflictReporting the aspect keys for conflict reporting.
     */
    class ErrorProcessingResult(
        hasLoadingError: Boolean,
        hasAnalysisError: Boolean,
        actionConflicts: com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?>?,
        executionDetailedExitCode: DetailedExitCode?,
        aspectKeysForConflictReporting: com.google.common.collect.ImmutableList<ActionLookupKey?>?
    ) {
        internal class AggregatingBuilder {
            private var hasLoadingError = false
            private var hasAnalysisError = false
            private val actionConflicts: MutableMap<ActionAnalysisMetadata?, ActionConflictException?> =
                com.google.common.collect.Maps.newHashMap<ActionAnalysisMetadata?, ActionConflictException?>()
            private var executionDetailedExitCode: DetailedExitCode? = null
            private val aspectKeysForConflictReporting: com.google.common.collect.ImmutableList.Builder<ActionLookupKey?> =
                com.google.common.collect.ImmutableList.builder<ActionLookupKey?>()

            fun aggregateSingleResult(individualErrorProcessingResult: IndividualErrorProcessingResult) {
                hasLoadingError = hasLoadingError || individualErrorProcessingResult.isLoadingError
                hasAnalysisError = hasAnalysisError || individualErrorProcessingResult.isAnalysisError
                actionConflicts.putAll(individualErrorProcessingResult.actionConflicts)
                executionDetailedExitCode =
                    DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                        executionDetailedExitCode,
                        individualErrorProcessingResult.executionDetailedExitCode
                    )
                if (individualErrorProcessingResult.aspectKeyForConflictReporting != null) {
                    aspectKeysForConflictReporting.add(
                        individualErrorProcessingResult.aspectKeyForConflictReporting
                    )
                }
            }

            // TODO(b/249690006) Only used for the rollout of the refactor. Remove afterwards.
            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setExecutionDetailedExitCode(executionDetailedExitCode: DetailedExitCode?): AggregatingBuilder {
                this.executionDetailedExitCode = executionDetailedExitCode
                return this
            }

            fun build(): ErrorProcessingResult {
                return ErrorProcessingResult(
                    hasLoadingError,
                    hasAnalysisError,
                    com.google.common.collect.ImmutableMap.copyOf<ActionAnalysisMetadata?, ActionConflictException?>(
                        actionConflicts
                    ),
                    executionDetailedExitCode,
                    aspectKeysForConflictReporting.build()
                )
            }
        }

        val hasLoadingError: Boolean
        val hasAnalysisError: Boolean
        val actionConflicts: com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?>?
        val executionDetailedExitCode: DetailedExitCode?
        val aspectKeysForConflictReporting: com.google.common.collect.ImmutableList<ActionLookupKey?>?

        init {
            this.aspectKeysForConflictReporting = aspectKeysForConflictReporting
            this.executionDetailedExitCode = executionDetailedExitCode
            this.actionConflicts = actionConflicts
            this.hasAnalysisError = hasAnalysisError
            this.hasLoadingError = hasLoadingError
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?>?>(
                actionConflicts,
                "actionConflicts"
            )
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<ActionLookupKey?>?>(
                aspectKeysForConflictReporting,
                "aspectKeysForConflictReporting"
            )
        }

        companion object {
            fun newBuilder(): AggregatingBuilder {
                return AggregatingBuilder()
            }
        }
    }

    /**
     * Represents the information around one single error in the build. These are the building blocks
     * for the final [ErrorProcessingResult].
     */
    internal class IndividualErrorProcessingResult(
        actionConflicts: com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?>?,
        executionDetailedExitCode: DetailedExitCode?,
        analysisRootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?,
        loadingRootCauses: com.google.common.collect.ImmutableSet<Label?>?,
        aspectKeyForConflictReporting: ActionLookupKey?
    ) {
        val isActionConflictError: Boolean
            get() = !this.actionConflicts.isEmpty()

        val isLoadingError: Boolean
            get() = !this.loadingRootCauses.isEmpty()

        val isAnalysisError: Boolean
            /** This is true for all non-execution errors: including loading & action conflict errors.  */
            get() = this.executionDetailedExitCode == null

        val actionConflicts: com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?>?
        val executionDetailedExitCode: DetailedExitCode?
        val analysisRootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?
        val loadingRootCauses: com.google.common.collect.ImmutableSet<Label?>?
        val aspectKeyForConflictReporting: ActionLookupKey?

        init {
            this.aspectKeyForConflictReporting = aspectKeyForConflictReporting
            this.loadingRootCauses = loadingRootCauses
            this.analysisRootCauses = analysisRootCauses
            this.executionDetailedExitCode = executionDetailedExitCode
            this.actionConflicts = actionConflicts
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?>?>(
                actionConflicts,
                "actionConflicts"
            )
            java.util.Objects.requireNonNull<Any?>(analysisRootCauses, "analysisRootCauses")
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<Label?>?>(
                loadingRootCauses,
                "loadingRootCauses"
            )
        }

        companion object {
            fun create(
                actionConflicts: com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?>?,
                executionDetailedExitCode: DetailedExitCode?,
                analysisRootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?,
                loadingRootCauses: com.google.common.collect.ImmutableSet<Label?>?,
                aspectKeyForConflictReporting: ActionLookupKey?
            ): IndividualErrorProcessingResult {
                return IndividualErrorProcessingResult(
                    actionConflicts,
                    executionDetailedExitCode,
                    analysisRootCauses,
                    loadingRootCauses,
                    aspectKeyForConflictReporting
                )
            }
        }
    }
}
