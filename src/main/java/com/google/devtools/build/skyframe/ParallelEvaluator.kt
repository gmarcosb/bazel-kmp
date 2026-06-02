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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.collect.nestedset.NestedSet

/**
 * This class is not intended for direct use, and is only exposed as public for use in evaluation
 * implementations outside of this package.
 * 
 * 
 * Note on naming: there used to be an `Evaluator` interface this class (and likely some
 * others) implemented, but as of 2020-01-15 this was the only implementation so we deleted that
 * interface. Now `ParallelEvaluator` could be called just `Evaluator`, but renaming it
 * is not worth the effort.
 */
class ParallelEvaluator(
    graph: ProcessableGraph?,
    graphVersion: com.google.devtools.build.skyframe.Version?,
    minimalVersion: com.google.devtools.build.skyframe.Version?,
    skyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?,
    reporter: ExtendedEventHandler?,
    emittedEventState: EmittedEventState?,
    storedEventFilter: com.google.devtools.build.skyframe.EventFilter?,
    errorInfoManager: ErrorInfoManager?,
    progressReceiver: InflightTrackingProgressReceiver?,
    graphInconsistencyReceiver: GraphInconsistencyReceiver?,
    executor: QuiescingExecutor?,
    cycleDetector: CycleDetector?,
    unnecessaryTemporaryStateDropperReceiver: UnnecessaryTemporaryStateDropperReceiver,
    keepGoing: java.util.function.Predicate<SkyKey?>?
) : AbstractParallelEvaluator(
    graph,
    graphVersion,
    minimalVersion,
    skyFunctions,
    reporter,
    emittedEventState,
    storedEventFilter,
    errorInfoManager,
    progressReceiver,
    graphInconsistencyReceiver,
    executor,
    cycleDetector,
    keepGoing
) {
    private val unnecessaryTemporaryStateDropperReceiver: UnnecessaryTemporaryStateDropperReceiver

    init {
        this.unnecessaryTemporaryStateDropperReceiver = unnecessaryTemporaryStateDropperReceiver
    }

    @Throws(java.lang.InterruptedException::class)
    private fun informProgressReceiverThatValueIsDone(key: SkyKey?, entry: NodeEntry) {
        if (evaluatorContext.getProgressReceiver() == null) {
            return
        }
        com.google.common.base.Preconditions.checkState(entry.isDone(), entry)
        val value: SkyValue? = entry.getValue()
        val valueVersion: com.google.devtools.build.skyframe.Version = entry.getVersion()
        com.google.common.base.Preconditions.checkState(
            valueVersion.atMost(evaluatorContext.getGraphVersion()),
            "%s should be at most %s in the version partial ordering",
            valueVersion,
            evaluatorContext.getGraphVersion()
        )

        var error: com.google.devtools.build.skyframe.ErrorInfo? = null
        val valueMaybeWithMetadata: SkyValue? = entry.getValueMaybeWithMetadata()
        if (valueMaybeWithMetadata != null) {
            replay(ValueWithMetadata.Companion.getEvents(valueMaybeWithMetadata))
            error = ValueWithMetadata.Companion.getMaybeErrorInfo(valueMaybeWithMetadata)
        }

        // For most nodes we do not inform the progress receiver if they were already done when we
        // retrieve them, but top-level nodes are presumably of more interest.
        // If valueVersion is not equal to graphVersion, it must be less than it (by the
        // Preconditions check above), and so the node is clean.
        val changed = valueVersion == evaluatorContext.getGraphVersion()
        evaluatorContext
            .getProgressReceiver()
            .evaluated(
                key,
                EvaluationState.Companion.get(value, changed),  /* newValue= */
                if (changed) value else null,  /* newError= */
                if (changed) error else null,  /* directDeps= */
                null
            )
    }

    @ThreadCompatible
    @Throws(java.lang.InterruptedException::class)
    private fun <T : SkyValue?> doMutatingEvaluation(
        skyKeys: com.google.common.collect.ImmutableSet<SkyKey>
    ): EvaluationResult<T?> {
        injectErrorTransienceValue()
        try {
            val batch: NodeBatch = graph.createIfAbsentBatch(
                null,
                com.google.devtools.build.skyframe.QueryableGraph.Reason.PRE_OR_POST_EVALUATION,
                skyKeys
            )
            for (skyKey in skyKeys) {
                val entry: NodeEntry? = batch.get(skyKey)
                // This must be equivalent to the code in AbstractParallelEvaluator.Evaluate#enqueueChild,
                // in order to be thread-safe.
                when (entry.addReverseDepAndCheckIfDone(null)) {
                    DependencyState.NEEDS_SCHEDULING -> evaluatorContext.getVisitor().enqueueEvaluation(skyKey, null)
                    DependencyState.DONE -> informProgressReceiverThatValueIsDone(skyKey, entry)
                    DependencyState.ALREADY_EVALUATING -> {}
                    else -> throw java.lang.IllegalStateException(entry.toString() + " for " + skyKey + " in unknown state")
                }
            }
        } catch (ie: java.lang.InterruptedException) {
            // When multiple keys are being evaluated, it's possible that a key may get queued before
            // an InterruptedException is thrown from either #addReverseDepAndCheckIfDone or
            // #informProgressReceiverThatValueIsDone on a different key. Therefore we have to make sure
            // all evaluation threads are properly interrupted and shut down, if main thread (current
            // thread) is interrupted.
            java.lang.Thread.currentThread().interrupt()
            try {
                evaluatorContext.getVisitor().waitForCompletion()
            } catch (se: SchedulerException) {
                // A SchedulerException due to a SkyFunction observing the interrupt is completely expected.
                if (se.getCause() !is java.lang.InterruptedException) {
                    throw se
                }
            }

            // Rethrow the InterruptedException to avoid proceeding to construct the result.
            throw ie
        }

        return waitForCompletionAndConstructResult<T?>(skyKeys)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun injectErrorTransienceValue() {
        // We unconditionally add the ErrorTransienceValue here, to ensure that it will be created, and
        // in the graph, by the time that it is needed. Creating it on demand in a parallel context sets
        // up a race condition, because there is no way to atomically create a node and set its value.
        val errorTransienceEntry: NodeEntry? =
            graph
                .createIfAbsentBatch(
                    null,
                    com.google.devtools.build.skyframe.QueryableGraph.Reason.PRE_OR_POST_EVALUATION,
                    com.google.common.collect.ImmutableList.of<SkyKey?>(ErrorTransienceValue.Companion.KEY)
                )
                .get(ErrorTransienceValue.Companion.KEY)
        if (!errorTransienceEntry.isDone()) {
            injectValues(
                com.google.common.collect.ImmutableMap.of<SkyKey?, Delta?>(
                    ErrorTransienceValue.Companion.KEY,
                    Delta.Companion.justNew(ErrorTransienceValue.Companion.INSTANCE)
                ),
                evaluatorContext.getGraphVersion(),
                graph,
                evaluatorContext.getProgressReceiver()
            )
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun <T : SkyValue?> waitForCompletionAndConstructResult(
        skyKeys: Iterable<SkyKey>
    ): EvaluationResult<T?> {
        var bubbleErrorInfo: MutableMap<SkyKey?, ValueWithMetadata?>? = null
        var catastrophe = false
        try {
            evaluatorContext.getVisitor().waitForCompletion()
        } catch (e: SchedulerException) {
            propagateEvaluatorContextCrashIfAny()
            AbstractParallelEvaluator.Companion.propagateInterruption(e)
            val errorKey: SkyKey = com.google.common.base.Preconditions.checkNotNull<SkyKey>(e.getFailedValue(), e)
            // ErrorInfo could only be null if SchedulerException wrapped an InterruptedException, but
            // that should have been propagated.
            val errorInfo: com.google.devtools.build.skyframe.ErrorInfo =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.skyframe.ErrorInfo>(
                    e.getErrorInfo(),
                    errorKey
                )
            bubbleErrorInfo = bubbleErrorUp(errorInfo, errorKey, skyKeys, e.getRdepsToBubbleUpTo())
            if (evaluatorContext.keepGoing(errorKey)) {
                com.google.common.base.Preconditions.checkState(
                    errorInfo.isCatastrophic(),
                    "Scheduler exception only thrown for catastrophe in keep_going evaluation: %s",
                    e
                )
                catastrophe = true
                // For b/287183296
                logger.atInfo().withCause(e).log(
                    "Catastrophic exception in --keep_going mode while evaluating SkyKey: %s", errorKey
                )
            }
        }
        com.google.common.base.Preconditions.checkState(
            evaluatorContext.getVisitor().getCrashes().isEmpty(),
            evaluatorContext.getVisitor().getCrashes()
        )

        // Successful evaluation, barring evaluation-wide exceptions, either because keepGoing or
        // because we actually did succeed.
        // TODO(bazel-team): Maybe report root causes during the build for lower latency.
        return constructResult<T?>(skyKeys, bubbleErrorInfo, catastrophe)
    }

    /**
     * Walk up graph to find a top-level node (without parents) that wanted this failure. Store the
     * failed nodes along the way in a map, with ErrorInfos that are appropriate for that layer.
     * Example:
     * 
     * <pre>
     * foo   bar
     * \   /
     * unrequested   baz
     * \    |
     * failed-node
    </pre> * 
     * 
     * User requests foo, bar. When failed-node fails, we look at its parents. unrequested is not
     * in-flight, so we replace failed-node by baz and repeat. We look at baz's parents. foo is
     * in-flight, so we replace baz by foo. Since foo is a top-level node and doesn't have parents, we
     * then break, since we know a top-level node, foo, that depended on the failed node.
     * 
     * 
     * There's the potential for a weird "track jump" here in the case:
     * 
     * <pre>
     * foo
     * / \
     * fail1 fail2
    </pre> * 
     * 
     * If fail1 and fail2 fail simultaneously, fail2 may start propagating up in the loop below.
     * However, foo requests fail1 first, and then throws an exception based on that. This is not
     * incorrect, but may be unexpected.
     * 
     * 
     * Returns a map of errors that have been constructed during the bubbling up, so that the
     * appropriate error can be returned to the caller, even though that error was not written to the
     * graph. If a cycle is detected during the bubbling, this method aborts and returns null so that
     * the normal cycle detection can handle the cycle.
     * 
     * 
     * Note that we are not propagating error to the first top-level node but to the highest one,
     * because during this process we can add useful information about error from other nodes.
     * 
     * 
     * Every node on this walk but the leaf node is not done, by the following argument: the leaf
     * node is done, but the parents of it that we consider are in `rdepsToBubbleUpTo`. Each
     * parent is either (1) a parent that requested the leaf node and found it to be in error, meaning
     * it is not done, or (2) a parent that had registered a dependency on this leaf node before it
     * finished building. In the second case, that parent would not have been enqueued, since we
     * failed fast and prevented all new evaluations. Thus, we will only visit unfinished parents of
     * the leaf node. For the inductive argument, the only parents we consider are those that were
     * registered during this build (via [NodeEntry.getInProgressReverseDeps]. Since we don't
     * allow a node to build with unfinished deps, those parents cannot have built.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun bubbleErrorUp(
        leafFailure: com.google.devtools.build.skyframe.ErrorInfo,
        errorKey: SkyKey?,
        roots: Iterable<SkyKey>,
        rdepsToBubbleUpTo: MutableSet<SkyKey?>
    ): MutableMap<SkyKey?, ValueWithMetadata?>? {
        // Remove all the compute states so as to give the SkyFunctions a chance to do fresh
        // computations during error bubbling.
        var errorKey: SkyKey? = errorKey
        stateCache.invalidateAll()

        val rootValues: MutableSet<SkyKey?> = com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(roots)
        var error: com.google.devtools.build.skyframe.ErrorInfo = leafFailure
        val bubbleErrorInfo: LinkedHashMap<SkyKey?, ValueWithMetadata?> = LinkedHashMap<SkyKey?, ValueWithMetadata?>()
        var externalInterrupt = false
        var firstIteration = true
        while (true) {
            val errorEntry: NodeEntry =
                com.google.common.base.Preconditions.checkNotNull<NodeEntry>(
                    graph.get(
                        null,
                        com.google.devtools.build.skyframe.QueryableGraph.Reason.ERROR_BUBBLING,
                        errorKey
                    ), errorKey
                )
            val reverseDeps: Iterable<SkyKey?>
            if (errorEntry.isDone()) {
                com.google.common.base.Preconditions.checkState(
                    firstIteration,
                    "Non-leaf done node reached: %s %s %s %s %s",
                    errorKey,
                    leafFailure,
                    roots,
                    rdepsToBubbleUpTo,
                    bubbleErrorInfo
                )
                reverseDeps = rdepsToBubbleUpTo
            } else {
                com.google.common.base.Preconditions.checkState(
                    !firstIteration,
                    "undone first iteration: %s %s %s %s %s %s",
                    errorKey,
                    errorEntry,
                    leafFailure,
                    roots,
                    rdepsToBubbleUpTo,
                    bubbleErrorInfo
                )
                reverseDeps = errorEntry.getInProgressReverseDeps()
            }
            firstIteration = false
            // We should break from loop only when node doesn't have any parents.
            if (com.google.common.collect.Iterables.isEmpty(reverseDeps)) {
                com.google.common.base.Preconditions.checkState(
                    rootValues.contains(errorKey),
                    "Current key %s has to be a top-level key: %s",
                    errorKey,
                    rootValues
                )
                val valueMaybeWithMetadata: SkyValue? = errorEntry.getValueMaybeWithMetadata()
                if (valueMaybeWithMetadata != null) {
                    replay(ValueWithMetadata.Companion.getEvents(valueMaybeWithMetadata))
                }
                break
            }
            val parent: SkyKey = com.google.common.base.Preconditions.checkNotNull<SkyKey>(
                com.google.common.collect.Iterables.getFirst<SkyKey?>(
                    reverseDeps,
                    null
                )
            )
            if (bubbleErrorInfo.containsKey(parent)) {
                logger.atInfo().log(
                    "Bubbled into a cycle. Don't try to bubble anything up. Cycle detection will kick in."
                            + " %s: %s, %s, %s, %s, %s",
                    parent, errorEntry, bubbleErrorInfo, leafFailure, roots, rdepsToBubbleUpTo
                )
                return null
            }
            val parentEntry: NodeEntry =
                com.google.common.base.Preconditions.checkNotNull<NodeEntry>(
                    graph.get(
                        errorKey,
                        com.google.devtools.build.skyframe.QueryableGraph.Reason.ERROR_BUBBLING,
                        parent
                    ),
                    "parent %s of %s not in graph",
                    parent,
                    errorKey
                )
            com.google.common.base.Preconditions.checkState(
                !parentEntry.isDone(),
                "We cannot bubble into a done node entry: a done node cannot depend on a not-done node,"
                        + " and the first errorParent was not done: %s %s %s %s %s %s %s %s",
                errorKey,
                errorEntry,
                parent,
                parentEntry,
                leafFailure,
                roots,
                rdepsToBubbleUpTo,
                bubbleErrorInfo
            )
            // Expected 6 args, but got 8.
            com.google.common.base.Preconditions.checkState(
                evaluatorContext.getProgressReceiver().isInflight(parent),
                "In-progress reverse deps can only include in-flight nodes: " + "%s %s %s %s %s %s",
                errorKey,
                errorEntry,
                parent,
                parentEntry,
                leafFailure,
                roots,
                rdepsToBubbleUpTo,
                bubbleErrorInfo
            )
            // Expected 6 args, but got 8.
            com.google.common.base.Preconditions.checkState(
                parentEntry.getTemporaryDirectDeps().contains(errorKey),
                "In-progress reverse deps can only include nodes that have declared a dep: "
                        + "%s %s %s %s %s %s",
                errorKey,
                errorEntry,
                parent,
                parentEntry,
                leafFailure,
                roots,
                rdepsToBubbleUpTo,
                bubbleErrorInfo
            )
            com.google.common.base.Preconditions.checkNotNull<NodeEntry?>(parentEntry, "%s %s", errorKey, parent)
            val skyFunction: SkyFunction? = evaluatorContext.getSkyFunctions().get(parent.functionName())
            if (parentEntry.isDirty()) {
                when (parentEntry.getLifecycleState()) {
                    LifecycleState.CHECK_DEPENDENCIES -> {
                        // If this value's child was bubbled up to, it did not signal this value, and so we must
                        // manually make it ready to build.
                        parentEntry.signalDep(evaluatorContext.getGraphVersion(), errorKey)
                        AbstractParallelEvaluator.Companion.maybeMarkRebuilding(parentEntry)
                    }

                    LifecycleState.NEEDS_REBUILDING -> AbstractParallelEvaluator.Companion.maybeMarkRebuilding(
                        parentEntry
                    )

                    LifecycleState.REBUILDING -> {}
                    else -> throw java.lang.AssertionError(parent.toString() + " not in valid dirty state: " + parentEntry)
                }
            }
            val childErrorKey: SkyKey? = errorKey
            errorKey = parent
            val env: SkyFunctionEnvironment =
                SkyFunctionEnvironment.Companion.createForError(
                    parent,
                    parentEntry.getTemporaryDirectDeps(),
                    bubbleErrorInfo,
                    com.google.common.collect.ImmutableSet.of<SkyKey?>(),
                    evaluatorContext
                )
            externalInterrupt = externalInterrupt || java.lang.Thread.currentThread().isInterrupted()
            var completedRun = false
            try {
                // This build is only to check if the parent node can give us a better error. We don't
                // care about a return value.
                skyFunction.compute(parent, env)
                completedRun = true
            } catch (interruptedException: java.lang.InterruptedException) {
                logger.atInfo().withCause(interruptedException).log("Interrupted during %s eval", parent)
                // Do nothing.
                // This throw happens if the builder requested the failed node, and then checked the
                // interrupted state later -- getValueOrThrow sets the interrupted bit after the failed
                // value is requested, to prevent the builder from doing too much work.
            } catch (builderException: SkyFunctionException) {
                // Clear interrupted status. We're not listening to interrupts here.
                java.lang.Thread.interrupted()
                val reifiedBuilderException: ReifiedSkyFunctionException =
                    ReifiedSkyFunctionException(builderException)
                error =
                    com.google.devtools.build.skyframe.ErrorInfo.Companion.fromException(
                        reifiedBuilderException,  /*isTransitivelyTransient=*/
                        false
                    )
                val events: NestedSet<Reportable?> =
                    env.reportEventsAndGetEventsToStore(parentEntry,  /*expectDoneDeps=*/false)
                val valueWithMetadata: ValueWithMetadata? =
                    ValueWithMetadata.Companion.error(
                        com.google.devtools.build.skyframe.ErrorInfo.Companion.fromChildErrors(
                            errorKey,
                            com.google.common.collect.ImmutableSet.of<com.google.devtools.build.skyframe.ErrorInfo?>(
                                error
                            )
                        ), events
                    )
                replay(events)
                bubbleErrorInfo.put(errorKey, valueWithMetadata)
                continue
            } catch (e: java.lang.RuntimeException) {
                // About to crash. Print debugging to INFO log.
                logger.atSevere().log("Crashing on %s. Contents of bubbleErrorInfo:", parent)
                for (bubbleEntry in bubbleErrorInfo.entrySet()) {
                    logger.atSevere().log(
                        "  %.1000s -> %.1000s", bubbleEntry.getKey(), bubbleEntry.getValue()
                    )
                }
                throw e
            } finally {
                // Clear interrupted status. We're not listening to interrupts here.
                java.lang.Thread.interrupted()
            }
            // TODO(b/166268889, b/172223413): remove when fixed.
            if (completedRun
                && error.getException() != null && (error.getException() is IOException
                        || error.getException().getClass().getName().endsWith("SourceArtifactException"))
            ) {
                val skyFunctionName: String = parent.functionName().getName()
                if (!skyFunctionName.startsWith("FILE")
                    && !skyFunctionName.startsWith("DIRECTORY_LISTING")
                ) {
                    logger.atInfo().log(
                        "SkyFunction did not rethrow error, may be a bug that it did not expect one: %s"
                                + " via %s, %s (%s)",
                        errorKey, childErrorKey, error, bubbleErrorInfo
                    )
                }
            }
            if (completedRun && !env.encounteredErrorDuringBubbling()) {
                logger.atInfo().log(
                    "Skyfunction did not encounter error: %s via %s, %s (%s)",
                    errorKey, childErrorKey, error, bubbleErrorInfo
                )
            }
            // Builder didn't throw its own exception, so just propagate this one up.
            val events: NestedSet<Reportable?> =
                env.reportEventsAndGetEventsToStore(parentEntry,  /*expectDoneDeps=*/false)
            val valueWithMetadata: ValueWithMetadata? =
                ValueWithMetadata.Companion.error(
                    com.google.devtools.build.skyframe.ErrorInfo.Companion.fromChildErrors(
                        errorKey,
                        com.google.common.collect.ImmutableSet.of<com.google.devtools.build.skyframe.ErrorInfo?>(error)
                    ), events
                )
            replay(events)
            bubbleErrorInfo.put(errorKey, valueWithMetadata)
        }

        // Reset the interrupt bit if there was an interrupt from outside this evaluator interrupt.
        // Note that there are internal interrupts set in the node builder environment if an error
        // bubbling node calls getValueOrThrow() on a node in error.
        if (externalInterrupt) {
            java.lang.Thread.currentThread().interrupt()
        }
        return bubbleErrorInfo
    }

    /**
     * Constructs an [EvaluationResult] from the [.graph]. Looks for cycles if there are
     * unfinished nodes but no error was already found through bubbling up (as indicated by `bubbleErrorInfo` being null).
     * 
     * 
     * `visitor` may be null, but only in the case where all graph entries corresponding to
     * `skyKeys` are known to be in the DONE state (`entry.isDone()` returns true).
     */
    @Throws(java.lang.InterruptedException::class)
    private fun <T : SkyValue?> constructResult(
        skyKeys: Iterable<SkyKey>,
        bubbleErrorInfo: MutableMap<SkyKey?, ValueWithMetadata?>?,
        catastrophe: Boolean
    ): EvaluationResult<T?> {
        val result: com.google.devtools.build.skyframe.EvaluationResult.Builder<T?> =
            EvaluationResult.Companion.builder<T?>()
        val cycleRoots: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()
        var haveKeys = false
        for (skyKey in skyKeys) {
            com.google.common.base.Preconditions.checkState(
                !catastrophe || evaluatorContext.keepGoing(skyKey),
                "Catastrophe not consistent with keepGoing mode: %s %s %s",
                skyKey,
                catastrophe,
                bubbleErrorInfo
            )
            haveKeys = true
            val unwrappedValue: SkyValue? =
                maybeGetValueFromError(
                    skyKey,
                    graph.get(
                        null,
                        com.google.devtools.build.skyframe.QueryableGraph.Reason.PRE_OR_POST_EVALUATION,
                        skyKey
                    ),
                    bubbleErrorInfo
                )
            val valueWithMetadata: ValueWithMetadata? =
                if (unwrappedValue == null) null else ValueWithMetadata.Companion.wrapWithMetadata(unwrappedValue)
            // Cycle checking: if there is a cycle, evaluation cannot progress, therefore,
            // the final values will not be in DONE state when the work runs out.
            if (valueWithMetadata == null) {
                // Don't look for cycles if the build failed for a known reason.
                if (bubbleErrorInfo == null) {
                    cycleRoots.add(skyKey)
                }
                continue
            }
            val value: SkyValue? = valueWithMetadata.getValue()
            val errorInfo: com.google.devtools.build.skyframe.ErrorInfo? = valueWithMetadata.getErrorInfo()
            com.google.common.base.Preconditions.checkState(value != null || errorInfo != null, skyKey)
            if (errorInfo != null && !evaluatorContext.keepGoing(skyKey)) {
                // value will be null here unless the value was already built on a prior keepGoing build.
                result.addError(skyKey, errorInfo)
                continue
            }
            if (value == null) {
                // Note that we must be in the keepGoing case. Only make this value an error if it doesn't
                // have a value. The error shouldn't matter to the caller since the value succeeded after a
                // fashion.
                result.addError(skyKey, errorInfo)
            } else {
                result.addResult(skyKey, value)
            }
        }
        if (!cycleRoots.isEmpty()) {
            val cycleRootsSize: Int = cycleRoots.size()
            if (cycleRootsSize <= MAX_CYCLE_ROOTS_TO_LOG) {
                logger.atInfo().log("Detecting cycles with roots: %s", cycleRoots)
            } else {
                logger.atInfo().log(
                    "Detecting cycles with roots (%d total, showing first %d): %s",
                    cycleRootsSize,
                    MAX_CYCLE_ROOTS_TO_LOG,
                    com.google.common.collect.Iterables.limit<SkyKey?>(cycleRoots, MAX_CYCLE_ROOTS_TO_LOG)
                )
            }
            GoogleAutoProfilerUtils.logged("Checking for Skyframe cycles", java.time.Duration.ofMillis(10)).use { p ->
                cycleDetector.checkForCycles(cycleRoots, result, evaluatorContext)
            }
        }
        com.google.common.base.Preconditions.checkState(
            !result.isEmpty() || !haveKeys,
            "No result for keys %s (%s %s)",
            skyKeys,
            bubbleErrorInfo,
            catastrophe
        )
        result.maybeEnsureCatastrophe(catastrophe)
        val builtResult: EvaluationResult<T?> = result.build()
        com.google.common.base.Preconditions.checkState(
            bubbleErrorInfo == null || builtResult.hasError(),
            "If an error bubbled up, some top-level node must be in error: %s %s %s",
            bubbleErrorInfo,
            skyKeys,
            builtResult
        )
        return builtResult
    }

    /**
     * Evaluates a set of values. Returns an [EvaluationResult]. All elements of skyKeys must be
     * keys for Values of subtype T.
     */
    @ThreadCompatible
    @Throws(java.lang.InterruptedException::class)
    fun <T : SkyValue?> eval(skyKeys: Iterable<out SkyKey>): EvaluationResult<T?> {
        val skyKeySet: com.google.common.collect.ImmutableSet<SkyKey> =
            com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(skyKeys)

        // Optimization: if all required node values are already present in the cache, return them
        // directly without launching the heavy machinery, spawning threads, etc.
        // Inform progressReceiver that these nodes are done to be consistent with the main code path.
        var allAreDone = true
        val batch: NodeBatch =
            evaluatorContext.getGraph().getBatch(
                null,
                com.google.devtools.build.skyframe.QueryableGraph.Reason.PRE_OR_POST_EVALUATION,
                skyKeySet
            )
        for (key in skyKeySet) {
            if (!AbstractParallelEvaluator.Companion.isDoneForBuild(batch.get(key))) {
                allAreDone = false
                break
            }
        }
        if (allAreDone) {
            for (skyKey in skyKeySet) {
                informProgressReceiverThatValueIsDone(skyKey, batch.get(skyKey))
            }
            // Note that the 'catastrophe' parameter doesn't really matter here (it's only used for
            // checking).
            return constructResult<T?>(skyKeySet, null,  /*catastrophe=*/false)
        }

        val cachedErrorKeys: MutableSet<SkyKey> = HashSet<SkyKey>()
        for (skyKey in skyKeySet) {
            if (!evaluatorContext.keepGoing(skyKey)) {
                val entry: NodeEntry? = graph.get(
                    null,
                    com.google.devtools.build.skyframe.QueryableGraph.Reason.PRE_OR_POST_EVALUATION,
                    skyKey
                )
                if (entry == null) {
                    continue
                }
                if (entry.isDone() && entry.getErrorInfo() != null) {
                    informProgressReceiverThatValueIsDone(skyKey, entry)
                    cachedErrorKeys.add(skyKey)
                }
            }
        }

        // Errors, even cached ones, should halt evaluations not in keepGoing mode.
        if (!cachedErrorKeys.isEmpty()) {
            // Note that the 'catastrophe' parameter doesn't really matter here (it's only used for
            // checking).
            return constructResult<T?>(cachedErrorKeys, null,  /*catastrophe=*/false)
        }

        unnecessaryTemporaryStateDropperReceiver.onEvaluationStarted(
            UnnecessaryTemporaryStateDropper { evaluatorContext.stateCache().invalidateAll() })
        try {
            Profiler.instance().profile(ProfilerTask.SKYFRAME_EVAL, "Parallel Evaluator evaluation").use { c ->
                return doMutatingEvaluation<T?>(skyKeySet)
            }
        } finally {
            unnecessaryTemporaryStateDropperReceiver.onEvaluationFinished()
        }
    }


    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private const val MAX_CYCLE_ROOTS_TO_LOG = 100

        @Throws(java.lang.InterruptedException::class)
        private fun maybeGetValueFromError(
            key: SkyKey?,
            entry: NodeEntry?,
            bubbleErrorInfo: MutableMap<SkyKey?, ValueWithMetadata?>?
        ): SkyValue? {
            val value: SkyValue? = if (bubbleErrorInfo == null) null else bubbleErrorInfo.get(key)
            if (value != null) {
                return value
            }
            return if (AbstractParallelEvaluator.Companion.isDoneForBuild(entry)) entry.getValueMaybeWithMetadata() else null
        }

        @Throws(java.lang.InterruptedException::class)
        fun injectValues(
            injectionMap: MutableMap<SkyKey?, Delta?>,
            version: com.google.devtools.build.skyframe.Version?,
            graph: ProcessableGraph,
            progressReceiver: InflightTrackingProgressReceiver
        ) {
            val prevNodeEntries: NodeBatch =
                graph.createIfAbsentBatch(
                    null,
                    com.google.devtools.build.skyframe.QueryableGraph.Reason.OTHER,
                    injectionMap.keySet()
                )
            for (injectionEntry in injectionMap.entrySet()) {
                val key: SkyKey? = injectionEntry.getKey()
                val value: SkyValue? = injectionEntry.getValue().newValue
                val prevEntry: NodeEntry? = prevNodeEntries.get(key)
                val newState: DependencyState? = prevEntry.addReverseDepAndCheckIfDone(null)
                com.google.common.base.Preconditions.checkState(
                    newState != DependencyState.ALREADY_EVALUATING, "%s %s", key, prevEntry
                )
                if (prevEntry.isDirty()) {
                    // Get the node in the state where it is able to accept a value.
                    com.google.common.base.Preconditions.checkState(
                        newState == DependencyState.NEEDS_SCHEDULING, "%s %s", key, prevEntry
                    )
                    // If there was a node in the graph before, check that the previous node has no
                    // dependencies. Overwriting a value with deps with an injected value (which is by
                    // definition deps-free) needs a little additional bookkeeping (removing reverse deps from
                    // the dependencies), but more importantly it's something that we want to avoid, because it
                    // indicates confusion of input values and derived values.
                    com.google.common.base.Preconditions.checkState(
                        prevEntry.noDepsLastBuild(), "existing entry for %s has deps: %s", key, prevEntry
                    )
                }
                prevEntry.markRebuilding()
                val maxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version? =
                    injectionEntry.getValue().newMaxTransitiveSourceVersion
                prevEntry.setValue(value, version, maxTransitiveSourceVersion)
                // Now that this key's injected value is set, it is no longer dirty.
                progressReceiver.injected(key)
            }
        }
    }
}
