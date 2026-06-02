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
package com.google.devtools.build.lib.metrics.criticalpath

import com.google.common.base.Preconditions
import com.google.common.collect.Comparators
import com.google.common.collect.ImmutableList
import com.google.common.eventbus.AllowConcurrentEvents
import com.google.common.eventbus.Subscribe
import com.google.common.flogger.StackSize
import com.google.devtools.build.lib.actions.Action
import java.time.Duration
import java.util.Map
import java.util.function.ToLongFunction
import java.util.stream.Stream
import javax.annotation.concurrent.ThreadSafe
import kotlin.Comparator
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Unit

/**
 * Computes the critical path in the action graph based on events published to the event bus.
 * 
 * 
 * After instantiation, this object needs to be registered on the event bus to work.
 */
@ThreadSafe
class CriticalPathComputer(actionKeyContext: ActionKeyContext?, graph: WalkableGraph?) {
    private val idGenerator: AtomicInteger = AtomicInteger()

    // outputArtifactToComponent is accessed from multiple event handlers.
    private val outputArtifactToComponent: ConcurrentMap<Artifact?, CriticalPathComponent?> =
        ConcurrentHashMap<Artifact?, CriticalPathComponent?>()
    private val actionKeyContext: ActionKeyContext?
    private val graph: WalkableGraph?

    /** Maximum critical path found.  */
    private val maxCriticalPath: AtomicReference<CriticalPathComponent?> = AtomicReference<CriticalPathComponent?>()

    init {
        this.actionKeyContext = actionKeyContext
        this.graph = graph
    }

    /**
     * Creates a critical path component for an action.
     * 
     * @param action the action for the critical path component
     * @param relativeStartNanos time when the action started to run in nanos. Only meant to be used
     * for computing time differences.
     */
    private fun createComponent(action: Action?, relativeStartNanos: Long): CriticalPathComponent {
        return CriticalPathComponent(idGenerator.getAndIncrement(), action, relativeStartNanos)
    }

    /**
     * Return the critical path stats for the current command execution.
     * 
     * 
     * This method allows us to calculate lazily the aggregate statistics of the critical path,
     * avoiding the memory and cpu penalty for doing it for all the actions executed.
     */
    fun aggregate(): AggregatedCriticalPath {
        val criticalPath = getMaxCriticalPath()
        if (criticalPath == null) {
            return AggregatedCriticalPath.Companion.EMPTY
        }

        val components = ImmutableList.builder<CriticalPathComponent?>()
        val metricsBuilder: AggregatedSpawnMetrics.Builder = Builder()
        var child: CriticalPathComponent? = criticalPath

        while (child != null) {
            val childSpawnMetrics: AggregatedSpawnMetrics? = child.getSpawnMetrics()
            if (childSpawnMetrics != null) {
                metricsBuilder.addDurations(childSpawnMetrics)
                metricsBuilder.addNonDurations(childSpawnMetrics)
            }
            components.add(child)
            child = child.getChild()
        }

        return AggregatedCriticalPath(
            criticalPath.getAggregatedElapsedTime(), metricsBuilder.build(), components.build()
        )
    }

    fun getCriticalPathComponentsMap(): MutableMap<Artifact?, CriticalPathComponent?> {
        return outputArtifactToComponent
    }

    /** Changes the phase of the action  */
    @Subscribe
    @AllowConcurrentEvents
    fun nextCriticalPathPhase(phase: SpawnExecutedEvent.ChangePhase) {
        val stats =
            outputArtifactToComponent.get(phase.getAction().getPrimaryOutput())
        if (stats != null) {
            stats.changePhase()
        }
    }

    /** Adds spawn metrics to the action stats.  */
    @Subscribe
    @AllowConcurrentEvents
    fun spawnExecuted(event: SpawnExecutedEvent) {
        val action: ActionAnalysisMetadata = event.getActionMetadata()
        val primaryOutput: Artifact? = action.getPrimaryOutput()
        if (primaryOutput == null) {
            // Despite the documentation to the contrary, the SpawnIncludeScanner creates an
            // ActionExecutionMetadata instance that returns a null primary output. That said, this
            // class is incorrect wrt. multiple Spawns in a single action. See b/111583707.
            return
        }
        val stats =
            Preconditions.checkNotNull<CriticalPathComponent>(outputArtifactToComponent.get(primaryOutput))

        val spawnResult: SpawnResult = event.getSpawnResult()
        stats.addSpawnResult(
            spawnResult.getMetrics(),
            spawnResult.getRunnerName(),
            spawnResult.getRunnerSubtype(),
            spawnResult.wasRemote()
        )
    }

    /** Returns the list of components using the most memory.  */
    fun getLargestMemoryComponents(): MutableList<CriticalPathComponent?> {
        return uniqueActions()!!
            .collect(
                Comparators.greatest<CriticalPathComponent?>(
                    LARGEST_MEMORY_COMPONENTS_SIZE,
                    Comparator.comparingLong<CriticalPathComponent?>(
                        ToLongFunction { c: CriticalPathComponent? ->
                            c!!.getSpawnMetrics().getMaxNonDuration(0, SpawnMetrics::memoryEstimate)
                        })
                )
            )
    }

    /** Returns the list of components with the largest input sizes.  */
    fun getLargestInputSizeComponents(): MutableList<CriticalPathComponent?> {
        return uniqueActions()!!
            .collect(
                Comparators.greatest<CriticalPathComponent?>(
                    LARGEST_INPUT_SIZE_COMPONENTS_SIZE,
                    Comparator.comparingLong<CriticalPathComponent?>(
                        ToLongFunction { c: CriticalPathComponent? ->
                            c!!.getSpawnMetrics().getMaxNonDuration(0, SpawnMetrics::inputBytes)
                        })
                )
            )
    }

    /** Returns the list of components with the largest input counts.  */
    fun getLargestInputCountComponents(): MutableList<CriticalPathComponent?> {
        return uniqueActions()!!
            .collect(
                Comparators.greatest<CriticalPathComponent?>(
                    LARGEST_INPUT_COUNT_COMPONENTS_SIZE,
                    Comparator.comparingLong<CriticalPathComponent?>(
                        ToLongFunction { c: CriticalPathComponent? ->
                            c!!.getSpawnMetrics().getMaxNonDuration(0, SpawnMetrics::inputFiles)
                        })
                )
            )
    }

    /** Returns the list of slowest components.  */
    fun getSlowestComponents(): MutableList<CriticalPathComponent?> {
        return uniqueActions()!!
            .collect(
                Comparators.greatest<CriticalPathComponent?>(
                    SLOWEST_COMPONENTS_SIZE,
                    Comparator.comparingLong<CriticalPathComponent?>(ToLongFunction { obj: CriticalPathComponent? -> obj!!.getElapsedTimeNanos() })
                )
            )
    }

    private fun uniqueActions(): Stream<CriticalPathComponent?>? {
        return outputArtifactToComponent.entries.stream()
            .filter { e: MutableMap.MutableEntry<Artifact?, CriticalPathComponent?>? -> e!!.value!!.isPrimaryOutput(e.key) }
            .map<CriticalPathComponent?> { Map.Entry.value }
    }

    /** Creates a CriticalPathComponent and adds the duration of input discovery and changes phase.  */
    @Subscribe
    @AllowConcurrentEvents
    @Throws(InterruptedException::class)
    fun discoverInputs(event: DiscoveredInputsEvent) {
        val stats =
            tryAddComponent(createComponent(event.getAction(), event.getStartTimeNanos()))
        stats.addSpawnResult(event.getMetrics(), null, "",  /* wasRemote= */false)
        stats.changePhase()
    }

    /**
     * Record an action that has started to run. If the CriticalPathComponent has not been created,
     * initialize it and then start running.
     * 
     * @param event information about the started action
     */
    @Subscribe
    @AllowConcurrentEvents
    @Throws(InterruptedException::class)
    fun actionStarted(event: ActionStartedEvent) {
        val action: Action? = event.getAction()
        tryAddComponent(createComponent(action, event.getNanoTimeStart())).startRunning()
    }

    /**
     * Try to add the component to the map of critical path components. If there is an existing
     * component for its primary output it uses that to update the rest of the outputs.
     * 
     * @return The component to be used for updating the time stats.
     */
    @Throws(InterruptedException::class)
    private fun tryAddComponent(newComponent: CriticalPathComponent): CriticalPathComponent {
        val newAction: Action = newComponent.getAction()
        val primaryOutput: Artifact? = newAction.getPrimaryOutput()
        var storedComponent =
            outputArtifactToComponent.putIfAbsent(primaryOutput, newComponent)
        if (storedComponent != null) {
            val oldAction: Action = storedComponent.getAction()
            // TODO(b/120663721) Replace this fragile reference equality check with something principled.
            check(!(oldAction !== newAction && !Actions.canBeShared(actionKeyContext, newAction, oldAction))) {
                ("Duplicate output artifact found for unsharable actions."
                        + "This can happen if a previous event registered the action.\n"
                        + "Old action: "
                        + oldAction
                        + "\n\nNew action: "
                        + newAction
                        + "\n\nArtifact: "
                        + primaryOutput
                        + "\n")
            }
        } else {
            storedComponent = newComponent
        }
        // Try to insert the existing component for the rest of the outputs even if we failed to be
        // the ones inserting the component so that at the end of this method we guarantee that all the
        // outputs have a component.
        for (output in newAction.getOutputs()) {
            if (output === primaryOutput) {
                continue
            }
            val old = outputArtifactToComponent.putIfAbsent(output, storedComponent)
            // If two actions run concurrently maybe we find a component by primary output but we are
            // the first updating the rest of the outputs.
            Preconditions.checkState(
                old == null || old === storedComponent, "Inconsistent state for %s", newAction
            )
        }
        return storedComponent
    }

    /**
     * Record an action that was not executed because it was in the (disk) cache. This is needed so
     * that we can calculate correctly the dependencies tree if we have some cached actions in the
     * middle of the critical path.
     */
    @Subscribe
    @AllowConcurrentEvents
    @Throws(InterruptedException::class)
    fun actionCached(event: CachedActionEvent) {
        val action: Action = event.getAction()
        val component =
            tryAddComponent(createComponent(action, event.getNanoTimeStart()))
        finalizeActionStat(
            event.getNanoTimeStart(), event.getNanoTimeFinish(), action, component, "action cache hit"
        )
    }

    /**
     * Records the elapsed time stats for the action. For each input artifact, it finds the real
     * dependent artifacts and records the critical path stats.
     */
    @Subscribe
    @AllowConcurrentEvents
    @Throws(InterruptedException::class)
    fun actionComplete(event: ActionCompletionEvent) {
        val action: Action = event.getAction()
        val component =
            Preconditions.checkNotNull<CriticalPathComponent>(
                outputArtifactToComponent.get(action.getPrimaryOutput()), action
            )
        finalizeActionStat(
            event.getRelativeActionStartTimeNanos(), event.getFinishTimeNanos(), action, component, ""
        )
    }

    @Subscribe
    @AllowConcurrentEvents
    @Throws(InterruptedException::class)
    fun actionChangePruned(event: ActionChangePrunedEvent) {
        if (graph == null) {
            return
        }

        val actionLookupData: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            event.actionLookupData()
        if (Actions.getAction(graph, actionLookupData) !is Action) {
            return
        }

        val component = tryAddComponent(createComponent(action, event.finishTimeNanos()))
        finalizeActionStat(
            event.finishTimeNanos(), event.finishTimeNanos(), action, component, "change pruned"
        )
    }

    /**
     * Record that the failed rewound action is no longer running. The action may or may not start
     * again later.
     */
    @Subscribe
    @AllowConcurrentEvents
    fun actionRewound(event: ActionRewoundEvent) {
        val action: Action = event.getFailedRewoundAction()
        val component =
            Preconditions.checkNotNull<CriticalPathComponent>(outputArtifactToComponent.get(action.getPrimaryOutput()))
        component.finishActionExecution(
            event.getRelativeActionStartTimeNanos(),
            event.getRelativeActionFinishTimeNanos(),
            "action rewound"
        )
    }

    /** Maximum critical path component found during the build.  */
    fun getMaxCriticalPath(): CriticalPathComponent? {
        return maxCriticalPath.get()
    }

    private fun finalizeActionStat(
        startTimeNanos: Long,
        finishTimeNanos: Long,
        action: Action,
        component: CriticalPathComponent,
        finalizeReason: String?
    ) {
        for (input in action.getInputs().toList()) {
            addArtifactDependency(component, input, finishTimeNanos)
        }
        if (Duration.ofNanos(finishTimeNanos - startTimeNanos).compareTo(Duration.ofMillis(-5)) < 0) {
            // See note in {@link Clock#nanoTime} about non increasing subsequent #nanoTime calls.
            logger.atWarning().withStackTrace(StackSize.MEDIUM).log(
                "Negative duration time for [%s] %s with start: %s, finish: %s.",
                action.getMnemonic(), action.getPrimaryOutput(), startTimeNanos, finishTimeNanos
            )
        }
        component.finishActionExecution(startTimeNanos, finishTimeNanos, finalizeReason)
        maxCriticalPath.accumulateAndGet(component, SELECT_LONGER_COMPONENT)
    }

    /** If "input" is a generated artifact, link its critical path to the one we're building.  */
    private fun addArtifactDependency(
        actionStats: CriticalPathComponent, input: Artifact, componentFinishNanos: Long
    ) {
        var depComponent = outputArtifactToComponent.get(input)
        if (depComponent == null && input.isChildOfDeclaredDirectory()) {
            depComponent = outputArtifactToComponent.get(input.getParent())
        }

        // Typically, the dep component should already be finished since its output was used as an input
        // for a just-completed action. However, we tolerate it still running for (a) action rewinding
        // and (b) the rare case that an action depending on a previously-cached shared action sees a
        // different shared action that is in the midst of being an action cache hit.
        if (depComponent != null && !depComponent.isRunning()) {
            actionStats.addDepInfo(depComponent, componentFinishNanos)
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** Number of top actions to record.  */
        const val SLOWEST_COMPONENTS_SIZE: Int = 30

        private const val LARGEST_MEMORY_COMPONENTS_SIZE = 20
        private const val LARGEST_INPUT_SIZE_COMPONENTS_SIZE = 20
        private const val LARGEST_INPUT_COUNT_COMPONENTS_SIZE = 20

        /** Selects and returns the longer of two components (the first may be `null`).  */
        private val SELECT_LONGER_COMPONENT: BinaryOperator<CriticalPathComponent?> =
            BinaryOperator { a: CriticalPathComponent?, b: CriticalPathComponent? ->
                if (a == null || a.getAggregatedElapsedTime().compareTo(b!!.getAggregatedElapsedTime()) < 0)
                    b
                else
                    a
            }
    }
}
