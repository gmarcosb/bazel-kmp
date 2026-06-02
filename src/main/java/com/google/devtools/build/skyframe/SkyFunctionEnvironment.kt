// Copyright 2016 The Bazel Authors. All rights reserved.
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
 * A [SkyFunction.Environment] implementation for [ParallelEvaluator].
 * 
 * 
 * The base [SkyFunctionEnvironment] class batch prefetches previously requested deps
 * during environment creation.
 * 
 * 
 * The [SkipsBatchPrefetch] subclass skips batch prefetching, so that it is more efficient
 * to create the environment when the number of previously requested deps is extremely large.
 */
// TODO: b/324948927 - Instead of having individual `SkyKey`s overriding the `skipsBatchPrefetch`
// method, some method similar to QueryableGraph#getLookupHint() when creating the environment to
// know whether batch prefetch should happen.
open class SkyFunctionEnvironment private constructor(
    skyKey: SkyKey?,
    previouslyRequestedDeps: GroupedDeps?,
    bubbleErrorInfo: MutableMap<SkyKey?, ValueWithMetadata?>?,
    oldDeps: MutableSet<SkyKey?>?,
    evaluatorContext: ParallelEvaluatorContext?,
    throwIfPreviouslyRequestedDepsUndone: Boolean,
    maxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?
) : AbstractSkyFunctionEnvironment(), SkyframeLookupResult, ExtendedEventHandler {
    private var building = true
    private var depErrorKey: SkyKey? = null
    private val skyKey: SkyKey
    private val previouslyRequestedDeps: GroupedDeps

    /**
     * The deps requested during the previous build of this node. Used for two reasons: (1) They are
     * fetched eagerly before the node is built, to potentially prime the graph and speed up requests
     * for them during evaluation. (2) When the node finishes building, any deps from the previous
     * build that are not deps from this build must have this node removed from them as a reverse dep.
     * Thus, it is important that all nodes in this set have the property that they have this node as
     * a reverse dep from the last build, but that this node has not added them as a reverse dep on
     * this build. That set is normally [NodeEntry.getAllRemainingDirtyDirectDeps], but in
     * certain corner cases, like cycles, further filtering may be needed.
     */
    private val oldDeps: MutableSet<SkyKey?>

    private var value: SkyValue? = null
    private var errorInfo: com.google.devtools.build.skyframe.ErrorInfo? = null

    private var maxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?

    /**
     * This is not `null` only during cycle detection and error bubbling. The nullness of this
     * field is used to detect whether evaluation is in one of those special states.
     * 
     * 
     * When this is not `null`, values in this map should be used (while getting
     * dependencies' values, events, or posts) over values from the graph for keys present in this
     * map.
     */
    private val bubbleErrorInfo: MutableMap<SkyKey?, ValueWithMetadata?>?

    private var encounteredErrorDuringBubbling = false

    /**
     * The values previously declared as dependencies during an earlier [SkyFunction.compute]
     * call for [.skyKey].
     * 
     * 
     * Values in this map were generally retrieved via [NodeEntry.getValueMaybeWithMetadata]
     * from done nodes. In some cases, values may be [.NULL_MARKER] (see [.batchPrefetch]
     * for more details).
     * 
     * 
     * In [SkipsBatchPrefetch], this map is not exhaustive. It populates as the [ ] re-requests dep values, and will contain [.PENDING_MARKER]s when a key is
     * about to be requested from the graph.
     */
    private val previouslyRequestedDepsValues: MutableMap<SkyKey?, SkyValue?>

    /**
     * The values newly requested from the graph during the [SkyFunction.compute] call for this
     * environment.
     * 
     * 
     * Values in this map were either retrieved via [NodeEntry.getValueMaybeWithMetadata] or
     * are one of the following special marker values:
     * 
     * 
     *  1. [.NULL_MARKER]: The key was already requested from the graph but was either not
     * present or not done.
     *  1. [.PENDING_MARKER]: The key is about to be requested from the graph. This is a
     * placeholder to detect duplicate keys in the same batch. It will be overwritten with
     * either [.NULL_MARKER] or a value once it is requested.
     *  1. [.MANUALLY_REGISTERED_MARKER]: The key was manually registered via [       ][.registerDependencies] and has not been otherwise requested. Such keys are assumed to be
     * done.
     * 
     * 
     * 
     * This map is ordered to preserve dep groups. The sizes of each group are stored in [ ][.newlyRequestedDepGroupSizes]. On a subsequent build, if the value is dirty, all deps in the
     * same group can be checked in parallel for changes. In other words, if dep1 and dep2 are in the
     * same group, then dep1 will be checked in parallel with dep2. See [ ][SkyFunction.Environment.getValuesAndExceptions] for more.
     * 
     * 
     * Keys in this map are disjoint with [.previouslyRequestedDepsValues]. This map may
     * contain entries from [.bubbleErrorInfo] if they were requested.
     */
    private val newlyRequestedDepsValues: MutableMap<SkyKey?, SkyValue?> = LinkedHashMap<SkyKey?, SkyValue?>()

    /** Size delimiters for dep groups in [.newlyRequestedDepsValues].  */
    private var newlyRequestedDepGroupSizes: MutableList<Int?>? = null

    /** The set of errors encountered while fetching children.  */
    private var childErrorInfos: MutableSet<com.google.devtools.build.skyframe.ErrorInfo?>? = null

    private val evaluatorContext: ParallelEvaluatorContext

    private var eventsToReport: MutableList<Reportable>? = null

    init {
        this.skyKey = com.google.common.base.Preconditions.checkNotNull<SkyKey>(skyKey)
        this.previouslyRequestedDeps =
            com.google.common.base.Preconditions.checkNotNull<GroupedDeps>(previouslyRequestedDeps)
        this.bubbleErrorInfo = bubbleErrorInfo
        this.oldDeps = com.google.common.base.Preconditions.checkNotNull<MutableSet<SkyKey?>>(oldDeps)
        this.evaluatorContext =
            com.google.common.base.Preconditions.checkNotNull<ParallelEvaluatorContext>(evaluatorContext)
        this.maxTransitiveSourceVersion = maxTransitiveSourceVersion
        this.previouslyRequestedDepsValues = batchPrefetch(throwIfPreviouslyRequestedDepsUndone)
    }

    @com.google.errorprone.annotations.ForOverride
    @Throws(java.lang.InterruptedException::class, UndonePreviouslyRequestedDeps::class)
    open fun batchPrefetch(throwIfPreviouslyRequestedDepsUndone: Boolean): MutableMap<SkyKey?, SkyValue?> {
        val excludedKeys: com.google.common.collect.ImmutableSet<SkyKey?>? =
            evaluatorContext.getGraph().prefetchDeps(skyKey, oldDeps, previouslyRequestedDeps)
        val keysToPrefetch: MutableCollection<SkyKey> =
            if (excludedKeys != null) excludedKeys else previouslyRequestedDeps.getAllElementsAsIterable()
        val batch: NodeBatch = evaluatorContext.getGraph()
            .getBatch(skyKey, com.google.devtools.build.skyframe.QueryableGraph.Reason.PREFETCH, keysToPrefetch)
        val depValuesBuilder: com.google.common.collect.ImmutableMap.Builder<SkyKey?, SkyValue?> =
            com.google.common.collect.ImmutableMap.builderWithExpectedSize<SkyKey?, SkyValue?>(keysToPrefetch.size())
        var missingRequestedDeps: com.google.common.collect.ImmutableList.Builder<SkyKey?>? = null
        for (depKey in keysToPrefetch) {
            val entry: NodeEntry? = batch.get(depKey)
            if (entry == null) {
                if (missingRequestedDeps == null) {
                    missingRequestedDeps = com.google.common.collect.ImmutableList.builder<SkyKey?>()
                }
                missingRequestedDeps.add(depKey)
                continue
            }

            val valueMaybeWithMetadata: SkyValue? = entry.getValueMaybeWithMetadata()
            val depDone = valueMaybeWithMetadata != null
            if (throwIfPreviouslyRequestedDepsUndone && !depDone) {
                // A previously requested dep may have transitioned from done to dirty between when the node
                // was read during a previous attempt to build this node and now. Notify the graph
                // inconsistency receiver so that we can crash if that's unexpected.
                evaluatorContext
                    .getGraphInconsistencyReceiver()
                    .noteInconsistencyAndMaybeThrow(
                        skyKey,
                        com.google.common.collect.ImmutableList.of<SkyKey?>(depKey),
                        Inconsistency.BUILDING_PARENT_FOUND_UNDONE_CHILD
                    )
                throw UndonePreviouslyRequestedDeps(com.google.common.collect.ImmutableList.of<SkyKey?>(depKey))
            }
            depValuesBuilder.put(depKey, if (!depDone) NULL_MARKER else valueMaybeWithMetadata)
            if (depDone) {
                maybeUpdateMaxTransitiveSourceVersion(entry)
            }
        }

        if (missingRequestedDeps != null) {
            // Notify `GraphInconsistencyReceiver` when there are some dependencies missing from the graph
            // to check whether this is expected.
            val allMissingDeps: com.google.common.collect.ImmutableList<SkyKey?> = missingRequestedDeps.build()
            evaluatorContext
                .getGraphInconsistencyReceiver()
                .noteInconsistencyAndMaybeThrow(
                    skyKey, allMissingDeps, Inconsistency.ALREADY_DECLARED_CHILD_MISSING
                )
            throw UndonePreviouslyRequestedDeps(allMissingDeps)
        }

        val prefetched: com.google.common.collect.ImmutableMap<SkyKey?, SkyValue?> = depValuesBuilder.buildOrThrow()
        com.google.common.base.Preconditions.checkState(
            !prefetched.containsKey(ErrorTransienceValue.Companion.KEY),
            "%s cannot have a dep on ErrorTransienceValue during building",
            skyKey
        )
        return prefetched
    }

    private fun checkActive() {
        com.google.common.base.Preconditions.checkState(building, skyKey)
    }

    /**
     * Reports events which were temporarily stored in this environment per the specification of
     * [SkyFunction.Environment.getListener]. Returns events that should be stored for potential
     * replay on a future evaluation.
     */
    @Throws(java.lang.InterruptedException::class)
    fun reportEventsAndGetEventsToStore(entry: NodeEntry, expectDoneDeps: Boolean): NestedSet<Reportable?>? {
        val eventFilter: com.google.devtools.build.skyframe.EventFilter = evaluatorContext.getStoredEventFilter()
        if (!eventFilter.storeEvents()) {
            if (eventsToReport != null && !eventsToReport!!.isEmpty()) {
                val tag = getTagFromKey()
                for (event in eventsToReport) {
                    event.withTag(tag).reportTo(evaluatorContext.getReporter())
                }
            }
            return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        }

        val depKeys: GroupedDeps = entry.getTemporaryDirectDeps()
        if ((eventsToReport == null || eventsToReport!!.isEmpty()) && depKeys.isEmpty()) {
            return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        }

        val eventBuilder: NestedSetBuilder<Reportable?> = NestedSetBuilder.stableOrder()
        if (eventsToReport != null && !eventsToReport!!.isEmpty()) {
            val tag = getTagFromKey()
            eventBuilder.addAll(
                com.google.common.collect.Lists.transform<F?, T?>(
                    eventsToReport,
                    com.google.common.base.Function { event: F? -> event.withTag(tag) })
            )
        }

        addTransitiveEventsFromDepValuesForDoneNode(
            eventBuilder,
            com.google.common.collect.Iterables.filter<SkyKey?>(
                depKeys.getAllElementsAsIterable(),
                com.google.common.base.Predicate { depKey: SkyKey? -> eventFilter.shouldPropagate(depKey, skyKey) }),
            expectDoneDeps
        )

        val events: NestedSet<Reportable?>? = eventBuilder.buildInterruptibly()
        evaluatorContext.getReplayingNestedSetEventVisitor().visit(events)
        return events
    }

    /**
     * Adds transitive events from done deps in `depKeys`, by looking in order at:
     * 
     * 
     *  1. [.bubbleErrorInfo]
     *  1. [.previouslyRequestedDepsValues]
     *  1. [.newlyRequestedDepsValues]
     *  1. [.evaluatorContext]'s graph accessing methods
     * 
     * 
     * 
     * Any key whose [NodeEntry]--or absence thereof--had to be read from the graph will also
     * be entered into [.newlyRequestedDepsValues] with its value or a [.NULL_MARKER].
     * 
     * 
     * This asserts that only keys manually registered via [.registerDependencies] require
     * reading from the graph, because this node is done, and so all other deps must have been
     * previously or newly requested.
     * 
     * 
     * If `assertDone`, this asserts that all deps in `depKeys` are done.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun addTransitiveEventsFromDepValuesForDoneNode(
        eventBuilder: NestedSetBuilder<Reportable?>, depKeys: Iterable<SkyKey?>, assertDone: Boolean
    ) {
        // depKeys may contain keys in newlyRegisteredDeps whose values have not yet been retrieved from
        // the graph during this environment's lifetime.
        var missingKeys: MutableList<SkyKey>? = null

        for (key in depKeys) {
            val value: SkyValue? = maybeGetValueFromErrorOrDeps(key)
            if (value == null) {
                if (key === ErrorTransienceValue.Companion.KEY) {
                    continue
                }
                com.google.common.base.Preconditions.checkState(
                    newlyRequestedDepsValues.get(key) === MANUALLY_REGISTERED_MARKER,
                    "Missing already declared dep %s (parent=%s)",
                    key,
                    skyKey
                )
                if (missingKeys == null) {
                    missingKeys = java.util.ArrayList<SkyKey>()
                }
                missingKeys!!.add(key)
            } else if (value === NULL_MARKER) {
                com.google.common.base.Preconditions.checkState(!assertDone, "%s had not done %s", skyKey, key)
            } else {
                eventBuilder.addTransitive(ValueWithMetadata.Companion.getEvents(value))
            }
        }
        if (missingKeys == null) {
            return
        }
        val missingEntries: NodeBatch =
            evaluatorContext.getGraph()
                .getBatch(skyKey, com.google.devtools.build.skyframe.QueryableGraph.Reason.DEP_REQUESTED, missingKeys)
        for (key in missingKeys) {
            val depEntry: NodeEntry? = missingEntries.get(key)
            val valueOrNullMarker: SkyValue? = getValueOrNullMarker(depEntry)
            newlyRequestedDepsValues.put(key, valueOrNullMarker)
            if (valueOrNullMarker === NULL_MARKER) {
                // TODO(mschaller): handle registered deps that transitioned from done to dirty during eval
                // But how? Resetting the current node may not help, because this dep was *registered*, not
                // requested. For now, no node that gets registered as a dep is eligible for
                // intra-evaluation dirtying, so let it crash.
                com.google.common.base.Preconditions.checkState(!assertDone, "%s had not done: %s", skyKey, key)
                continue
            }
            maybeUpdateMaxTransitiveSourceVersion(depEntry)
            eventBuilder.addTransitive(ValueWithMetadata.Companion.getEvents(valueOrNullMarker))
        }
    }

    fun setValue(newValue: SkyValue?) {
        com.google.common.base.Preconditions.checkState(
            errorInfo == null && bubbleErrorInfo == null,
            "%s %s %s %s",
            skyKey,
            newValue,
            errorInfo,
            bubbleErrorInfo
        )
        com.google.common.base.Preconditions.checkState(value == null, "%s %s %s", skyKey, value, newValue)
        value = newValue
    }

    /**
     * Set this node to be in error. The node's value must not have already been set. However, all
     * dependencies of this node *must* already have been registered, since this method may
     * register a dependence on the error transience node, which should always be the last dep.
     */
    @Throws(java.lang.InterruptedException::class)
    fun setError(state: NodeEntry, errorInfo: com.google.devtools.build.skyframe.ErrorInfo) {
        com.google.common.base.Preconditions.checkState(value == null, "%s %s %s", skyKey, value, errorInfo)
        com.google.common.base.Preconditions.checkState(
            this.errorInfo == null,
            "%s %s %s",
            skyKey,
            this.errorInfo,
            errorInfo
        )

        if (errorInfo.isDirectlyTransient()) {
            val errorTransienceNode: NodeEntry =
                com.google.common.base.Preconditions.checkNotNull<NodeEntry>(
                    evaluatorContext
                        .getGraph()
                        .get(
                            skyKey,
                            com.google.devtools.build.skyframe.QueryableGraph.Reason.RDEP_ADDITION,
                            ErrorTransienceValue.Companion.KEY
                        ),
                    "Null error value? %s",
                    skyKey
                )
            val triState: DependencyState?
            if (oldDeps.contains(ErrorTransienceValue.Companion.KEY)) {
                triState = errorTransienceNode.checkIfDoneForDirtyReverseDep(skyKey)
            } else {
                triState = errorTransienceNode.addReverseDepAndCheckIfDone(skyKey)
            }
            com.google.common.base.Preconditions.checkState(
                triState == DependencyState.DONE,
                "%s %s %s",
                skyKey,
                triState,
                errorInfo
            )
            state.addSingletonTemporaryDirectDep(ErrorTransienceValue.Companion.KEY)
            state.signalDep(evaluatorContext.getGraphVersion(), ErrorTransienceValue.Companion.KEY)
            maxTransitiveSourceVersion = null
        }

        this.errorInfo =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.skyframe.ErrorInfo?>(
                errorInfo,
                skyKey
            )
    }

    /**
     * Returns a value, `null`, or [.NULL_MARKER] for the given key by looking in order
     * at:
     * 
     * 
     *  1. [.bubbleErrorInfo]
     *  1. [.previouslyRequestedDepsValues]
     *  1. [.newlyRequestedDepsValues]
     * 
     * 
     * 
     * Returns `null` if no entries for `key` were found in any of those three maps, or
     * if the key was manually registered via [.registerDependencies] but never requested.
     */
    fun maybeGetValueFromErrorOrDeps(key: SkyKey?): SkyValue? {
        if (bubbleErrorInfo != null) {
            val bubbleErrorInfoValue: ValueWithMetadata? = bubbleErrorInfo.get(key)
            if (bubbleErrorInfoValue != null) {
                return bubbleErrorInfoValue
            }
        }
        var directDepsValue: SkyValue? = getPreviouslyRequestedDepValue(key)
        if (directDepsValue != null) {
            return directDepsValue
        }
        directDepsValue = newlyRequestedDepsValues.get(key)
        return if (directDepsValue === MANUALLY_REGISTERED_MARKER) null else directDepsValue
    }

    /**
     * Gets the value of previously requested dep from either the env-scoped map or the [ ][.evaluatorContext]'s graph.
     * 
     * 
     * In [SkipsBatchPrefetch], since previously requested deps values are not available
     * after environment creation, so it needs to query the [.evaluatorContext]'s graph on
     * demand.
     */
    @com.google.errorprone.annotations.ForOverride
    open fun getPreviouslyRequestedDepValue(key: SkyKey?): SkyValue? {
        return previouslyRequestedDepsValues.get(key)
    }

    @com.google.errorprone.annotations.ForOverride
    open fun lookupRequestedDep(depKey: SkyKey): SkyValue? {
        com.google.common.base.Preconditions.checkArgument(
            depKey != ErrorTransienceValue.Companion.KEY,
            "Error transience key cannot be in requested deps of %s",
            skyKey
        )
        if (bubbleErrorInfo != null) {
            val bubbleErrorInfoValue: ValueWithMetadata? = bubbleErrorInfo.get(depKey)
            if (bubbleErrorInfoValue != null) {
                newlyRequestedDepsValues.put(depKey, bubbleErrorInfoValue)
                return bubbleErrorInfoValue
            }
        }
        var directDepsValue: SkyValue? = previouslyRequestedDepsValues.get(depKey)
        if (directDepsValue != null) {
            return directDepsValue
        }
        directDepsValue = newlyRequestedDepsValues.putIfAbsent(depKey, PENDING_MARKER)
        return if (directDepsValue === MANUALLY_REGISTERED_MARKER) null else directDepsValue
    }

    private fun endDepGroup(sizeBeforeRequest: Int) {
        val newDeps: Int = newlyRequestedDepsValues.size() - sizeBeforeRequest
        if (newDeps > 0) {
            if (newlyRequestedDepGroupSizes == null) {
                newlyRequestedDepGroupSizes = java.util.ArrayList<Int?>()
            }
            newlyRequestedDepGroupSizes!!.add(newDeps)
        }
    }

    @Throws(E1::class, E2::class, E3::class, E4::class, java.lang.InterruptedException::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?, E4 : java.lang.Exception?>
            getValueOrThrowInternal(
        depKey: SkyKey,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?,
        exceptionClass4: java.lang.Class<E4?>?
    ): SkyValue? {
        checkActive()
        val sizeBeforeRequest: Int = newlyRequestedDepsValues.size()
        var depValue: SkyValue? = lookupRequestedDep(depKey)
        if (depValue != null) {
            processDepValue(depKey, depValue)
        } else {
            val depEntry: NodeEntry? = evaluatorContext.getGraph()
                .get(skyKey, com.google.devtools.build.skyframe.QueryableGraph.Reason.DEP_REQUESTED, depKey)
            depValue = processDepEntry(depKey, depEntry)
        }
        endDepGroup(sizeBeforeRequest)

        return unwrapOrThrow<E1?, E2?, E3?, E4?>(
            depKey, depValue, exceptionClass1, exceptionClass2, exceptionClass3, exceptionClass4
        )
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    override fun getValuesAndExceptions(depKeys: Iterable<out SkyKey>): SkyframeLookupResult {
        checkActive()

        // Lazily initialized when we encounter a missing key and the graph's lookup hint indicates that
        // the key should be requested in a batch. If the graph supports efficient lookups of individual
        // keys, we avoid constructing a list.
        var missingKeys: MutableList<SkyKey>? = null

        val sizeBeforeRequest: Int = newlyRequestedDepsValues.size()
        for (depKey in depKeys) {
            val value: SkyValue? = lookupRequestedDep(depKey)
            if (value === PENDING_MARKER) {
                continue  // Duplicate key in this request.
            }
            if (value != null) {
                processDepValue(depKey, value)
            } else if (evaluatorContext.getGraph().getLookupHint(depKey) == LookupHint.BATCH) {
                if (missingKeys == null) {
                    missingKeys = java.util.ArrayList<SkyKey>()
                }
                missingKeys!!.add(depKey)
            } else {
                val depEntry: NodeEntry? = evaluatorContext.getGraph()
                    .get(skyKey, com.google.devtools.build.skyframe.QueryableGraph.Reason.DEP_REQUESTED, depKey)
                processDepEntry(depKey, depEntry)
            }
        }
        endDepGroup(sizeBeforeRequest)

        if (missingKeys != null) {
            val missingEntries: NodeBatch =
                evaluatorContext.getGraph().getBatch(
                    skyKey,
                    com.google.devtools.build.skyframe.QueryableGraph.Reason.DEP_REQUESTED,
                    missingKeys
                )
            for (key in missingKeys) {
                processDepEntry(key, missingEntries.get(key))
            }
        }

        return this
    }

    @com.google.errorprone.annotations.ForOverride
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    open fun processDepEntry(depKey: SkyKey?, depEntry: NodeEntry?): SkyValue? {
        val valueOrNullMarker: SkyValue? = getValueOrNullMarker(depEntry)
        processDepValue(depKey, valueOrNullMarker)
        newlyRequestedDepsValues.put(depKey, valueOrNullMarker)
        if (valueOrNullMarker !== NULL_MARKER) {
            maybeUpdateMaxTransitiveSourceVersion(depEntry)
        }
        return valueOrNullMarker
    }

    fun processDepValue(depKey: SkyKey?, depValue: SkyValue?) {
        if (depValue === NULL_MARKER) {
            valuesMissing = true
            return
        }

        val errorInfo: com.google.devtools.build.skyframe.ErrorInfo? =
            ValueWithMetadata.Companion.getMaybeErrorInfo(depValue)
        if (errorInfo == null) {
            return
        }
        if (childErrorInfos == null) {
            childErrorInfos = LinkedHashSet<com.google.devtools.build.skyframe.ErrorInfo?>()
        }
        childErrorInfos!!.add(errorInfo)
        if (bubbleErrorInfo != null) {
            encounteredErrorDuringBubbling = true
            // Set interrupted status, to try to prevent the calling SkyFunction from doing anything fancy
            // after this. SkyFunctions executed during error bubbling are supposed to (quickly) rethrow
            // errors or return a value/null (but there's currently no way to enforce this).
            java.lang.Thread.currentThread().interrupt()
        }

        // If we get here, then the dep node is present and also (i) depends on a cycle or (ii) errorful
        // or (iii) both. The remaining question is whether or not to convey to the SkyFunction that the
        // dep node is missing.
        //
        // If the dep node depends on a cycle, then we always want the SkyFunction to act as though the
        // dep is missing (cycles are not supposed to be observable by SkyFunctions), so we always set
        // valuesMissing.
        //
        // If the dep node is errorful and we're in nokeep_going mode and not in error bubbling, then
        // the SkyFunction is not supposed to be able to observe the error and is supposed to act like
        // the dep is missing, so we set valuesMissing. In contrast, if we are in error bubbling, then
        // the SkyFunction is supposed to be able to observe the error (so as to have the chance to
        // produce an enriched error).
        //
        // If the dep node is errorful and we're in keep_going mode, then SkyFunction is supposed to be
        // able to observe the error (say, with a followup SkyframeLookupResult#getOrThrow) so we don't
        // set valuesMissing.
        if (!errorInfo.getCycleInfo().isEmpty()
            || (errorInfo.getException() != null && !shouldKeepGoingWhenEvaluatingDep(depKey) && bubbleErrorInfo == null)
        ) {
            valuesMissing = true
            // We arbitrarily record the first child error if we are about to abort.
            if (!shouldKeepGoingWhenEvaluatingDep(depKey) && depErrorKey == null) {
                depErrorKey = depKey
            }
        }
    }

    private fun shouldKeepGoingWhenEvaluatingDep(depKey: SkyKey?): Boolean {
        return evaluatorContext.keepGoing(depKey)
    }

    @Throws(E1::class, E2::class, E3::class)  // SkyframeLookupResult implementation.
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?> getOrThrow(
        depKey: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?
    ): SkyValue? {
        return unwrapOrThrow<E1?, E2?, E3?, java.lang.RuntimeException?>(
            depKey,
            maybeGetValueFromErrorOrDeps(depKey),
            exceptionClass1,
            exceptionClass2,
            exceptionClass3,
            null
        )
    }

    // SkyframeLookupResult implementation.
    override fun queryDep(depKey: SkyKey?, resultCallback: QueryDepCallback): Boolean {
        val maybeWrappedValue: SkyValue? = maybeGetValueFromErrorOrDeps(depKey)
        if (maybeWrappedValue == null) {
            BugReport.sendNonFatalBugReport(
                java.lang.IllegalStateException(
                    java.lang.String.format("Value for %s was missing, this should never happen", depKey)
                )
            )
            return false
        }
        if (maybeWrappedValue === NULL_MARKER) {
            valuesMissing = true
            return false
        }
        if (maybeWrappedValue !is ValueWithMetadata) {
            resultCallback.acceptValue(depKey, maybeWrappedValue)
            return true
        }
        if (!maybeWrappedValue.hasError()) {
            resultCallback.acceptValue(depKey, maybeWrappedValue.getValue())
            return true
        }

        // Otherwise, there's an error.
        val result = handleError(depKey, maybeWrappedValue)
        if (result is SkyValue) {
            resultCallback.acceptValue(depKey, result)
            return true
        }
        if (result is java.lang.Exception
            && resultCallback.tryHandleException(depKey, result)
        ) {
            return true
        }
        valuesMissing = true
        return false
    }

    @Throws(E1::class, E2::class, E3::class, E4::class)
    private fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?, E4 : java.lang.Exception?>
            unwrapOrThrow(
        depKey: SkyKey?,
        maybeWrappedValue: SkyValue?,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?,
        exceptionClass4: java.lang.Class<E4?>?
    ): SkyValue? {
        if (maybeWrappedValue == null) {
            BugReport.sendNonFatalBugReport(
                java.lang.IllegalStateException(
                    java.lang.String.format("Value for %s was missing, this should never happen", depKey)
                )
            )
            return null
        }
        if (maybeWrappedValue === NULL_MARKER) {
            valuesMissing = true
            return null
        }
        if (maybeWrappedValue !is ValueWithMetadata) {
            return maybeWrappedValue
        }
        if (!maybeWrappedValue.hasError()) {
            return maybeWrappedValue.getValue()
        }

        // Otherwise, there's an error.
        val result = handleError(depKey, maybeWrappedValue)
        if (result is SkyValue) {
            return result
        }
        if (result is java.lang.Exception) {
            SkyFunctionException.Companion.throwIfInstanceOf<E1?, E2?, E3?, E4?>(
                result as java.lang.Exception, exceptionClass1, exceptionClass2, exceptionClass3, exceptionClass4
            )
        }
        valuesMissing = true
        return null
    }

    /**
     * Processes wrapped values containing errors.
     * 
     * @param depKey the dependency key, used only for error messages.
     * @param wrappedError an instance of ValueWithMetadata containing an error.
     * @return A `SkyValue` when a value is available in keepGoing mode, an `Exception`
     * when one should be propagated or null otherwise.
     */
    private fun handleError(depKey: SkyKey?, wrappedError: ValueWithMetadata): Any? {
        if (shouldKeepGoingWhenEvaluatingDep(depKey)) {
            // In keepGoing mode, returns any computed value to the caller.
            val justValue: SkyValue? = wrappedError.getValue()
            if (justValue != null) {
                return justValue
            }
        }

        val errorInfo: com.google.devtools.build.skyframe.ErrorInfo? = wrappedError.getErrorInfo()
        val exception: java.lang.Exception? = errorInfo.getException()

        if (exception == null) {
            // If there's no exception, there must be a cycle.
            com.google.common.base.Preconditions.checkState(
                !errorInfo.getCycleInfo().isEmpty(),
                "%s %s %s %s",
                skyKey,
                depKey,
                errorInfo,
                wrappedError
            )
        } else if (shouldKeepGoingWhenEvaluatingDep(depKey) || bubbleErrorInfo != null) {
            // The exception may only propagate in keepGoing mode or during error bubbling.
            return exception
        }
        return null
    }

    /**
     * If `!keepGoing` and there is at least one dep in error, returns a dep in error. Otherwise
     * returns `null`.
     */
    fun getDepErrorKey(): SkyKey? {
        return depErrorKey
    }

    override fun getListener(): ExtendedEventHandler? {
        checkActive()
        return this
    }

    override fun getTemporaryDirectDeps(): GroupedDeps {
        return previouslyRequestedDeps
    }

    public override fun handle(event: Event) {
        var event: Event = event
        if (event.getKind() === EventKind.WARNING) {
            event = event.withTag(getTagFromKey())
            if (!evaluatorContext.getEmittedEventState().addWarning(event)) {
                return  // Duplicate warning.
            }
        }
        reportEvent(event)
    }

    public override fun post(obj: Postable) {
        reportEvent(obj)
    }

    private fun reportEvent(event: Reportable) {
        checkActive()
        if (event.storeForReplay()) {
            if (eventsToReport == null) {
                eventsToReport = java.util.ArrayList<Reportable>()
            }
            eventsToReport!!.add(event)
        } else {
            event.reportTo(evaluatorContext.getReporter())
        }
    }

    fun doneBuilding() {
        building = false
    }

    fun getNewlyRequestedDeps(): MutableSet<SkyKey?> {
        return newlyRequestedDepsValues.keySet()
    }

    /** Adds newly requested dep keys to the node's temporary direct deps.  */
    fun addTemporaryDirectDepsTo(entry: NodeEntry) {
        entry.addTemporaryDirectDepsInGroups(
            newlyRequestedDepsValues.keySet(),
            if (newlyRequestedDepGroupSizes == null) com.google.common.collect.ImmutableList.of<Int?>() else newlyRequestedDepGroupSizes
        )
    }

    fun removeUndoneNewlyRequestedDeps() {
        if (!valuesMissing || newlyRequestedDepGroupSizes == null) {
            return
        }
        val it: MutableIterator<SkyValue?> = newlyRequestedDepsValues.values().iterator()
        for (i in newlyRequestedDepGroupSizes.indices) {
            val groupSize: Int = newlyRequestedDepGroupSizes!!.get(i)!!
            var newGroupSize = groupSize
            for (j in 0..<groupSize) {
                if (it.next() === NULL_MARKER) {
                    it.remove()
                    newGroupSize--
                }
            }
            newlyRequestedDepGroupSizes!!.set(i, newGroupSize)
        }
    }

    fun isAnyDirectDepErrorTransitivelyTransient(): Boolean {
        com.google.common.base.Preconditions.checkState(
            bubbleErrorInfo == null,
            "Checking dep error transitive transience during error bubbling for: %s",
            skyKey
        )
        for (skyValue in previouslyRequestedDepsValues.values()) {
            val maybeErrorInfo: com.google.devtools.build.skyframe.ErrorInfo? =
                ValueWithMetadata.Companion.getMaybeErrorInfo(skyValue)
            if (maybeErrorInfo != null && maybeErrorInfo.isTransitivelyTransient()) {
                return true
            }
        }
        return false
    }

    fun isAnyNewlyRequestedDepErrorTransitivelyTransient(): Boolean {
        com.google.common.base.Preconditions.checkState(
            bubbleErrorInfo == null,
            "Checking dep error transitive transience during error bubbling for: %s",
            skyKey
        )
        for (skyValue in newlyRequestedDepsValues.values()) {
            val maybeErrorInfo: com.google.devtools.build.skyframe.ErrorInfo? =
                ValueWithMetadata.Companion.getMaybeErrorInfo(skyValue)
            if (maybeErrorInfo != null && maybeErrorInfo.isTransitivelyTransient()) {
                return true
            }
        }
        return false
    }

    fun getChildErrorInfos(): MutableSet<com.google.devtools.build.skyframe.ErrorInfo?> {
        return if (childErrorInfos == null) com.google.common.collect.ImmutableSet.of<com.google.devtools.build.skyframe.ErrorInfo?>() else childErrorInfos
    }

    /**
     * Applies the change to the graph (mostly) atomically and returns parents to potentially signal
     * and enqueue.
     * 
     * 
     * Parents should be enqueued unless (1) this node is being built after the main evaluation has
     * aborted, or (2) this node is being built with `--nokeep_going`, and so we are about to
     * shut down the main evaluation anyway.
     * 
     * @param expectDoneDeps whether to expect all deps to be done. Normally, a node can be done only
     * if its deps are done but in some cases (e.g. short-circuiting cycle detection) we don't
     * have that property so we can't assert all deps are done.
     */
    @Throws(java.lang.InterruptedException::class)
    fun commitAndGetParents(primaryEntry: NodeEntry, expectDoneDeps: Boolean): MutableSet<SkyKey?>? {
        // Construct the definitive error info, if there is one.
        if (errorInfo == null) {
            errorInfo =
                evaluatorContext
                    .getErrorInfoManager()
                    .getErrorInfoToUse(
                        skyKey,
                        value != null,
                        if (childErrorInfos == null) com.google.common.collect.ImmutableSet.of<com.google.devtools.build.skyframe.ErrorInfo?>() else childErrorInfos
                    )
            // TODO(b/166268889, b/172223413): remove when fixed.
            if (errorInfo != null && errorInfo.getException() is IOException) {
                val skyFunctionName: String = skyKey.functionName().getName()
                if (!skyFunctionName.startsWith("FILE")
                    && !skyFunctionName.startsWith("DIRECTORY_LISTING")
                ) {
                    logger.atInfo().withCause(errorInfo.getException()).log(
                        "Synthetic errorInfo for %s", skyKey
                    )
                }
            }
        }

        // We have the following implications:
        // errorInfo == null => value != null => enqueueParents.
        // All these implications are strict:
        // (1) errorInfo != null && value != null happens for values with recoverable errors.
        // (2) value == null && enqueueParents happens for values that are found to have errors
        // during a --keep_going build.
        val events: NestedSet<Reportable?>? =
            reportEventsAndGetEventsToStore(
                primaryEntry,  /* expectDoneDeps= */
                expectDoneDeps && !skyKey.supportsPartialReevaluation()
            )

        val valueWithMetadata: SkyValue?
        if (value == null) {
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.skyframe.ErrorInfo?>(
                errorInfo,
                "%s %s",
                skyKey,
                primaryEntry
            )
            valueWithMetadata = ValueWithMetadata.Companion.error(errorInfo, events)
        } else {
            valueWithMetadata = ValueWithMetadata.Companion.normal(value, errorInfo, events)
        }
        val temporaryDirectDeps: GroupedDeps = primaryEntry.getTemporaryDirectDeps()
        val resetDeps: com.google.common.collect.ImmutableSet<SkyKey?> = primaryEntry.getResetDirectDeps()
        if (!oldDeps.isEmpty() || !resetDeps.isEmpty()) {
            // Remove the rdep on this entry for each of 1) its old deps from a prior evaluation that are
            // no longer direct deps and 2) reset deps that were not requested again post-restart.
            val depsToRemove: com.google.common.collect.ImmutableList<SkyKey> =
                com.google.common.collect.ImmutableList.copyOf<SkyKey?>(
                    com.google.common.collect.Sets.difference<SkyKey?>(
                        com.google.common.collect.Sets.union<SkyKey?>(
                            oldDeps,
                            resetDeps
                        ), temporaryDirectDeps.toSet()
                    )
                )
            val oldDepEntries: NodeBatch =
                evaluatorContext.getGraph().getBatch(
                    skyKey,
                    com.google.devtools.build.skyframe.QueryableGraph.Reason.RDEP_REMOVAL,
                    depsToRemove
                )
            for (key in depsToRemove) {
                val oldDepEntry: NodeEntry =
                    com.google.common.base.Preconditions.checkNotNull<NodeEntry>(oldDepEntries.get(key), key)
                oldDepEntry.removeReverseDep(skyKey)
            }
        }

        // If this entry is dirty, setValue may not actually change it, if it determines that the data
        // being written now is the same as the data already present in the entry. We detect this case
        // by comparing versions before and after setting the value.
        val previousVersion: com.google.devtools.build.skyframe.Version = primaryEntry.getVersion()
        val reverseDeps: MutableSet<SkyKey?>? =
            primaryEntry.setValue(
                valueWithMetadata, evaluatorContext.getGraphVersion(), maxTransitiveSourceVersion
            )
        val currentVersion: com.google.devtools.build.skyframe.Version = primaryEntry.getVersion()
        val changed = currentVersion != previousVersion

        // Tell the receiver that this value was built. If currentVersion.equals(evaluationVersion), it
        // was evaluated this run, and so was changed. Otherwise, it is less than evaluationVersion, by
        // the Preconditions check above, and was not actually changed this run -- when it was written
        // above, its version stayed below this update's version, so its value remains the same.
        evaluatorContext
            .getProgressReceiver()
            .evaluated(
                skyKey,
                EvaluationState.Companion.get(value, changed),  /* newValue= */
                if (changed) value else null,  /* newError= */
                if (changed) errorInfo else null,
                temporaryDirectDeps
            )

        return reverseDeps
    }

    private fun getTagFromKey(): String? {
        return evaluatorContext.getSkyFunctions().get(skyKey.functionName()).extractTag(skyKey)
    }

    /**
     * Gets the latch that is counted down when an exception is thrown in `AbstractQueueVisitor`. For use in tests to check if an exception actually was thrown. Calling
     * `AbstractQueueVisitor#awaitExceptionForTestingOnly` can throw a spurious [ ] because [CountDownLatch.await] checks the interrupted bit before
     * returning, even if the latch is already at 0. See bug "testTwoErrors is flaky".
     */
    fun getExceptionLatchForTesting(): CountDownLatch? {
        return evaluatorContext.getVisitor().getExceptionLatchForTestingOnly()
    }

    override fun inErrorBubbling(): Boolean {
        return bubbleErrorInfo != null
    }

    override fun registerDependencies(keys: Iterable<SkyKey?>) {
        com.google.common.base.Preconditions.checkState(
            maxTransitiveSourceVersion == null,
            "Dependency registration not supported when tracking max transitive source versions"
        )
        val sizeBeforeRequest: Int = newlyRequestedDepsValues.size()
        for (key in keys) {
            if (!previouslyRequestedDepsValues.containsKey(key)) {
                newlyRequestedDepsValues.putIfAbsent(key, MANUALLY_REGISTERED_MARKER)
            }
        }
        endDepGroup(sizeBeforeRequest)
    }

    override fun injectVersionForNonHermeticFunction(version: com.google.devtools.build.skyframe.Version?) {
        com.google.common.base.Preconditions.checkState(
            skyKey.functionName().getHermeticity() == FunctionHermeticity.NONHERMETIC, skyKey
        )
        com.google.common.base.Preconditions.checkState(
            maxTransitiveSourceVersion == null,
            "Multiple injected versions (%s, %s) for %s",
            maxTransitiveSourceVersion,
            version,
            skyKey
        )
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.skyframe.Version?>(version, skyKey)
        com.google.common.base.Preconditions.checkState(
            !evaluatorContext.getGraphVersion().lowerThan(version),
            "Invalid injected version (%s > %s) for %s",
            version,
            evaluatorContext.getGraphVersion(),
            skyKey
        )
        maxTransitiveSourceVersion = version
    }

    fun maybeUpdateMaxTransitiveSourceVersion(depEntry: NodeEntry) {
        if (maxTransitiveSourceVersion == null
            || skyKey.functionName().getHermeticity() == FunctionHermeticity.NONHERMETIC
        ) {
            return
        }
        val depMtsv: com.google.devtools.build.skyframe.Version? = depEntry.getMaxTransitiveSourceVersion()
        if (depMtsv == null || maxTransitiveSourceVersion.atMost(depMtsv)) {
            maxTransitiveSourceVersion = depMtsv
        }
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("skyKey", skyKey)
            .add("oldDeps", oldDeps)
            .add("value", value)
            .add("errorInfo", errorInfo)
            .add("previouslyRequestedDepsValues", previouslyRequestedDepsValues)
            .add("newlyRequestedDepsValues", newlyRequestedDepsValues)
            .add("newlyRequestedDepGroupSizes", newlyRequestedDepGroupSizes)
            .add("childErrorInfos", childErrorInfos)
            .add("depErrorKey", depErrorKey)
            .add("maxTransitiveSourceVersion", maxTransitiveSourceVersion)
            .add("bubbleErrorInfo", bubbleErrorInfo)
            .add("evaluatorContext", evaluatorContext)
            .toString()
    }

    override fun getLookupHandleForPreviouslyRequestedDeps(): SkyframeLookupResult {
        checkActive()
        return this
    }

    override fun <T : SkyKeyComputeState?> getState(stateSupplier: java.util.function.Supplier<T?>): T? {
        return evaluatorContext.stateCache()
            .get(skyKey, java.util.function.Function { k: SkyKey? -> stateSupplier.get() }) as T?
    }

    fun encounteredErrorDuringBubbling(): Boolean {
        return encounteredErrorDuringBubbling
    }

    override fun getMaxTransitiveSourceVersionSoFar(): com.google.devtools.build.skyframe.Version? {
        return maxTransitiveSourceVersion
    }

    @Throws(UndonePreviouslyRequestedDeps::class, java.lang.InterruptedException::class)
    open fun ensurePreviouslyRequestedDepsFetched() {
        // Do nothing; previously requested deps were already fetched and checked for done-ness in
        // batchPrefetch.
    }

    open fun wasNewlyRequestedDepNullForPartialReevaluation(newlyRequestedDep: SkyKey?): Boolean {
        return false
    }

    /**
     * In the case when user intends to add a new parallelism, one approach is to aggregate the
     * existing skyframe-evaluator one and a new type of thread pool in a [ ] object, and inject
     * it into [ParallelEvaluatorContext].
     */
    override fun getParallelEvaluationExecutor(): QuiescingExecutor? {
        return evaluatorContext.getExecutor()
    }

    /** Thrown during environment construction if a previously requested dep is no longer done.  */
    internal class UndonePreviouslyRequestedDeps private constructor(depKeys: com.google.common.collect.ImmutableList<SkyKey?>?) :
        java.lang.Exception() {
        private val depKeys: com.google.common.collect.ImmutableList<SkyKey?>

        init {
            this.depKeys =
                com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<SkyKey?>>(
                    depKeys
                )
        }

        fun getDepKeys(): com.google.common.collect.ImmutableList<SkyKey?> {
            return depKeys
        }
    }

    /**
     * The environment that skips eagerly batch prefetching previously requested deps during creation.
     * Instead, their values are read from the graph on demand, in the same way as newly requested
     * deps.
     * 
     * 
     * This subclass is created if the [SkyKey] supports partial reevaluation or opts to skip
     * batch prefetching previously requested deps values.
     * 
     * 
     * The [.ensurePreviouslyRequestedDepsFetched] method, which gets called prior to node
     * completion, isn't a no-op, because they weren't prefetched. They're needed for version, error,
     * and event data during node completion.
     * 
     * 
     * The [.wasNewlyRequestedDepNullForPartialReevaluation] method may return `true`,
     * when the evaluator checks for a newly requested done dep to which the current node is being
     * added as an rdep, to ensure that dep's key gets delivered to this node's mailbox.
     */
    private class SkipsBatchPrefetch(
        skyKey: SkyKey?,
        previouslyRequestedDeps: GroupedDeps?,
        oldDeps: MutableSet<SkyKey?>?,
        evaluatorContext: ParallelEvaluatorContext?,
        maxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?
    ) : SkyFunctionEnvironment(
        skyKey,
        previouslyRequestedDeps,  /* bubbleErrorInfo= */
        null,
        oldDeps,
        evaluatorContext,
        false,
        maxTransitiveSourceVersion
    ) {
        override fun batchPrefetch(throwIfPreviouslyRequestedDepsUndone: Boolean): MutableMap<SkyKey?, SkyValue?> {
            // Partial reevaluations don't prefetch all previously requested deps, because doing so is too
            // expensive, with how many more times those nodes get reevaluated.
            return HashMap<SkyKey?, SkyValue?>()
        }

        override fun getPreviouslyRequestedDepValue(key: SkyKey?): SkyValue? {
            val env: SkyFunctionEnvironment = this
            if (!env.previouslyRequestedDeps.contains(key)) {
                return null
            }
            val possibleValueInMap: SkyValue? = env.previouslyRequestedDepsValues.get(key)
            if (possibleValueInMap != null) {
                return possibleValueInMap
            }
            try {
                // TODO: b/324948927#comment14 - Figure out the approach to properly handle possible missing
                // or undone deps before expanding the usage of `SkipsBatchPrefetch` or making
                // `SkipsBatchPrefetch` as the default environment to create.
                val depEntry: NodeEntry? =
                    env.evaluatorContext.getGraph()
                        .get(env.skyKey, com.google.devtools.build.skyframe.QueryableGraph.Reason.DEP_REQUESTED, key)
                return processDepEntry(key, depEntry)
            } catch (e: java.lang.InterruptedException) {
                throw java.lang.IllegalStateException("No interruption when getting depEntry from depGraph", e)
            }
        }

        override fun lookupRequestedDep(depKey: SkyKey): SkyValue? {
            val env: SkyFunctionEnvironment = this
            com.google.common.base.Preconditions.checkArgument(
                depKey != ErrorTransienceValue.Companion.KEY,
                "Error transience key cannot be in requested deps of %s",
                env.skyKey
            )
            if (env.previouslyRequestedDeps.contains(depKey)) {
                return env.previouslyRequestedDepsValues.putIfAbsent(depKey, PENDING_MARKER)
            }
            val directDepsValue: SkyValue? = env.newlyRequestedDepsValues.putIfAbsent(depKey, PENDING_MARKER)
            return if (directDepsValue === MANUALLY_REGISTERED_MARKER) null else directDepsValue
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(java.lang.InterruptedException::class)
        override fun processDepEntry(depKey: SkyKey?, depEntry: NodeEntry?): SkyValue? {
            val env: SkyFunctionEnvironment = this
            val valueOrNullMarker: SkyValue? = getValueOrNullMarker(depEntry)
            processDepValue(depKey, valueOrNullMarker)
            if (env.previouslyRequestedDeps.contains(depKey)) {
                env.previouslyRequestedDepsValues.put(depKey, valueOrNullMarker)
            } else {
                env.newlyRequestedDepsValues.put(depKey, valueOrNullMarker)
            }
            if (valueOrNullMarker !== NULL_MARKER) {
                maybeUpdateMaxTransitiveSourceVersion(depEntry)
            }
            return valueOrNullMarker
        }

        @Throws(UndonePreviouslyRequestedDeps::class, java.lang.InterruptedException::class)
        override fun ensurePreviouslyRequestedDepsFetched() {
            val env: SkyFunctionEnvironment = this
            val keysToFetch: com.google.common.collect.ImmutableList<SkyKey> =
                env.previouslyRequestedDeps.toSet().stream()
                    .filter(java.util.function.Predicate { k: SkyKey? ->
                        !env.previouslyRequestedDepsValues.containsKey(
                            k
                        )
                    })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<SkyKey?>())
            val batch: NodeBatch =
                env.evaluatorContext.getGraph().getBatch(
                    env.skyKey,
                    com.google.devtools.build.skyframe.QueryableGraph.Reason.PREFETCH,
                    keysToFetch
                )
            var missingRequestedDeps: com.google.common.collect.ImmutableList.Builder<SkyKey?>? = null
            for (depKey in keysToFetch) {
                val entry: NodeEntry? = batch.get(depKey)
                if (entry == null) {
                    if (missingRequestedDeps == null) {
                        missingRequestedDeps = com.google.common.collect.ImmutableList.builder<SkyKey?>()
                    }
                    missingRequestedDeps.add(depKey)
                    continue
                }
                val valueMaybeWithMetadata: SkyValue? = entry.getValueMaybeWithMetadata()
                val depDone = valueMaybeWithMetadata != null
                if (!depDone) {
                    // A previously requested dep may have transitioned from done to dirty between when the
                    // node was read during a previous attempt to build this node and now. Notify the graph
                    // inconsistency receiver so that we can crash if that's unexpected.
                    env.evaluatorContext
                        .getGraphInconsistencyReceiver()
                        .noteInconsistencyAndMaybeThrow(
                            env.skyKey,
                            com.google.common.collect.ImmutableList.of<SkyKey?>(depKey),
                            Inconsistency.BUILDING_PARENT_FOUND_UNDONE_CHILD
                        )
                    throw UndonePreviouslyRequestedDeps(com.google.common.collect.ImmutableList.of<SkyKey?>(depKey))
                }
                env.previouslyRequestedDepsValues.put(depKey, valueMaybeWithMetadata)
                maybeUpdateMaxTransitiveSourceVersion(entry)
            }

            if (missingRequestedDeps != null) {
                // Notify `GraphInconsistencyReceiver` when there are some dependencies missing from the
                // graph to check whether this is expected.
                val allMissingDeps: com.google.common.collect.ImmutableList<SkyKey?> = missingRequestedDeps.build()
                env.evaluatorContext
                    .getGraphInconsistencyReceiver()
                    .noteInconsistencyAndMaybeThrow(
                        env.skyKey, allMissingDeps, Inconsistency.ALREADY_DECLARED_CHILD_MISSING
                    )
                throw UndonePreviouslyRequestedDeps(allMissingDeps)
            }
        }

        override fun wasNewlyRequestedDepNullForPartialReevaluation(newlyRequestedDep: SkyKey?): Boolean {
            val env: SkyFunctionEnvironment = this
            return env.newlyRequestedDepsValues.get(newlyRequestedDep) === NULL_MARKER
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private val NULL_MARKER: SkyValue = object : SkyValue {}
        private val PENDING_MARKER: SkyValue = object : SkyValue {}
        private val MANUALLY_REGISTERED_MARKER: SkyValue = object : SkyValue {}

        @Throws(java.lang.InterruptedException::class, UndonePreviouslyRequestedDeps::class)
        fun create(
            skyKey: SkyKey,
            previouslyRequestedDeps: GroupedDeps?,
            oldDeps: MutableSet<SkyKey?>?,
            maxTransitiveSourceVersionSoFar: com.google.devtools.build.skyframe.Version?,
            evaluatorContext: ParallelEvaluatorContext
        ): SkyFunctionEnvironment {
            val maxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version? =
                if (skyKey.functionName().getHermeticity() != FunctionHermeticity.NONHERMETIC)
                    com.google.common.base.MoreObjects.firstNonNull<com.google.devtools.build.skyframe.Version?>(
                        maxTransitiveSourceVersionSoFar,
                        evaluatorContext.getMinimalVersion()
                    )
                else
                    null
            return if (skyKey.skipsBatchPrefetch())
                SkipsBatchPrefetch(
                    skyKey, previouslyRequestedDeps, oldDeps, evaluatorContext, maxTransitiveSourceVersion
                )
            else
                SkyFunctionEnvironment(
                    skyKey,
                    previouslyRequestedDeps,  /* bubbleErrorInfo= */
                    null,
                    oldDeps,
                    evaluatorContext,  /* throwIfPreviouslyRequestedDepsUndone= */
                    true,
                    maxTransitiveSourceVersion
                )
        }

        @Throws(java.lang.InterruptedException::class)
        fun createForError(
            skyKey: SkyKey?,
            previouslyRequestedDeps: GroupedDeps?,
            bubbleErrorInfo: MutableMap<SkyKey?, ValueWithMetadata?>?,
            oldDeps: MutableSet<SkyKey?>?,
            evaluatorContext: ParallelEvaluatorContext?
        ): SkyFunctionEnvironment {
            try {
                return SkyFunctionEnvironment(
                    skyKey,
                    previouslyRequestedDeps,
                    TODO("Cannot convert element")
                ) < Map < SkyKey
                TODO(
                    """
                    |Cannot convert element
                    |With text:
                    |ValueWithMetadata>>checkNotNull(bubbleErrorInfo),
                    |          oldDeps,
                    |          evaluatorContext,
                    |          /* throwIfPreviouslyRequestedDepsUndone= */ false,
                    |          // Cycles can lead to a state where the versions of done children don't accurately reflect
                    |          // the state that led to this node's value. Be conservative then.
                    |          /* maxTransitiveSourceVersion= */ null
                    """.trimMargin()
                )
            } catch (undonePreviouslyRequestedDeps: UndonePreviouslyRequestedDeps) {
                throw java.lang.IllegalStateException(undonePreviouslyRequestedDeps)
            }
        }

        @Throws(java.lang.InterruptedException::class)
        private fun getValueOrNullMarker(nodeEntry: NodeEntry?): SkyValue? {
            if (nodeEntry == null) {
                return NULL_MARKER
            }
            val valueMaybeWithMetadata: SkyValue? = nodeEntry.getValueMaybeWithMetadata()
            if (valueMaybeWithMetadata == null) {
                return NULL_MARKER
            }
            return valueMaybeWithMetadata
        }
    }
}
