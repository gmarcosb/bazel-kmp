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
import com.google.devtools.build.lib.actions.Action
import com.google.devtools.build.lib.clock.BlazeClock
import java.time.Duration

/**
 * A component of the graph over which the critical path is computed. This may be identical to the
 * action graph, but does not have to be - it may also take into account individual spawns run as
 * part of an action.
 */
@ThreadCompatible
class CriticalPathComponent(
  /** An unique identifier of the component for one build execution  */
    private val id: Int,
  action: Action?, // These two fields are values of BlazeClock.nanoTime() at the relevant points in time.
  @kotlin.jvm.JvmField private var startNanos: Long
) {
    private var finishNanos: Long = 0

    @kotlin.concurrent.Volatile
    private var isRunning = false

    /** The longest aggregate runtime of this component and its critical path.  */
    private var aggregatedElapsedTime: Long = 0

    private val action: Action
    private val primaryOutput: Artifact?

    /** Spawn metrics for this action.  */
    private var phaseMaxMetrics: SpawnMetrics = EMPTY_PLACEHOLDER_METRICS

    private var totalSpawnMetrics: AggregatedSpawnMetrics = AggregatedSpawnMetrics.EMPTY
    private var longestRunningTotalDurationInMs = 0
    private var phaseChange = false

    /** Name of the runner used for the spawn.  */
    @kotlin.jvm.JvmField
    private var longestPhaseSpawnRunnerName: String? = null

    /** Details about the runner used for the spawn.  */
    private var longestPhaseSpawnRunnerSubtype: String? = null

    /** Child with the maximum critical path.  */
    @kotlin.jvm.JvmField
    private var child: CriticalPathComponent? = null

    /** Indication that there is at least one remote spawn metrics received.  */
    private var remote = false

    init {
        this.action = Preconditions.checkNotNull<Action>(action)
        this.primaryOutput = action.getPrimaryOutput()
    }

    /**
     * Record the elapsed time in case the new duration is greater. This method could be called
     * multiple times in the following cases:
     * 
     * 
     *  1. Shared actions run concurrently, and the one that really gets executed takes more time to
     * send the finish event and the one that was a cache hit manages to send the event before.
     *  1. An action gets rewound, and is later reattempted.
     * 
     * 
     * 
     * In both these cases we overwrite the components' times if the later call specifies a greater
     * duration.
     * 
     * 
     * In the former case the logic is known to be incorrect, as other actions that depend on this
     * action will not necessarily use the correct getElapsedTimeNanos(). But we do not want to block
     * action execution because of this. So in certain conditions we might see another path as the
     * critical path.
     * 
     * 
     * In addition, in the case of sequential spawns, Aggregate the last phase's duration values
     * with the total spawn metrics. To make sure not to add the last phase's duration multiple times,
     * only add if there is duration and reset the phase metrics once it has been aggregated.
     */
    @kotlin.jvm.Synchronized
    fun finishActionExecution(
        startNanos: Long, finishNanos: Long, finalizeReason: String
    ) {
        if (isRunning || finishNanos - startNanos > getElapsedTimeNanos()) {
            this.startNanos = startNanos
            this.finishNanos = finishNanos
            // In case aggregatedElapsedTime was never set (such as a leaf node with no depedencies) with
            // #addDepInfo, we want to set it here in which case the elapsed time is just the run time of
            // this component.
            aggregatedElapsedTime = max(aggregatedElapsedTime, this.finishNanos - this.startNanos)
            isRunning = false
            if (longestPhaseSpawnRunnerName == null && !finalizeReason.isEmpty()) {
                // This is probably not the best way to do it in face of getting called multiple times.
                longestPhaseSpawnRunnerName = finalizeReason
                longestPhaseSpawnRunnerSubtype = ""
                longestRunningTotalDurationInMs =
                    Duration.ofNanos(this.finishNanos - this.startNanos).toMillis().toInt()
            }
        }

        // If the phaseMaxMetrics has Duration, then we want to aggregate it to the total.
        if (!this.phaseMaxMetrics.isEmpty()) {
            this.totalSpawnMetrics = this.totalSpawnMetrics.sumDurationsMaxOther(phaseMaxMetrics)
            this.phaseMaxMetrics = EMPTY_PLACEHOLDER_METRICS
        }
    }

    fun isPrimaryOutput(possiblePrimaryOutput: Artifact?): Boolean {
        // We know that the keys in the CriticalPathComputer are exactly the values returned from
        // action.getPrimaryOutput(), so pointer equality is safe here.
        return possiblePrimaryOutput === primaryOutput
    }

    /** The action for which we are storing the stat.  */
    fun getAction(): Action {
        return action
    }

    /**
     * This is called by [CriticalPathComputer.actionStarted] to start running the action. The
     * three scenarios where this would occur is:
     * 
     * 
     *  1. A new CriticalPathComponent is created and should start running.
     *  1. A CriticalPathComponent has been created with discover inputs and beginning to execute.
     *  1. An action was rewound and starts again.
     * 
     */
    fun startRunning() {
        isRunning = true
    }

    fun isRunning(): Boolean {
        return isRunning
    }

    fun prettyPrintAction(): String {
        return action.prettyPrint()
    }

    fun getOwner(): Label? {
        val owner: ActionOwner? = action.getOwner()
        if (owner != null && owner.getLabel() != null) {
            return owner.getLabel()
        }
        return null
    }

    fun getMnemonic(): String {
        return action.getMnemonic()
    }

    /** An unique identifier of the component for one build execution  */
    fun getId(): Int {
        return id
    }

    /**
     * An action can run multiple spawns. Those calls can be sequential or parallel. If action is a
     * sequence of calls we aggregate the SpawnMetrics of all the SpawnResults. If there are multiples
     * of the same action run in parallel, we keep the maximum runtime SpawnMetrics. We will also set
     * the longestPhaseSpawnRunnerName to the longest running spawn runner name across all phases if
     * it exists.
     */
    fun addSpawnResult(
        metrics: SpawnMetrics, runnerName: String?, runnerSubtype: String?, wasRemote: Boolean
    ) {
        // Mark this component as having remote components if _any_ spawn result contributing
        // to it contains meaningful remote metrics. Subsequent non-remote spawns in an action
        // must not reset this flag.
        if (wasRemote) {
            this.remote = true
        }
        if (this.phaseChange) {
            if (!this.phaseMaxMetrics.isEmpty()) {
                this.totalSpawnMetrics = this.totalSpawnMetrics.sumDurationsMaxOther(phaseMaxMetrics)
            }
            this.phaseMaxMetrics = metrics
            this.phaseChange = false
        } else if (metrics.totalTimeInMs() > phaseMaxMetrics.totalTimeInMs()) {
            this.phaseMaxMetrics = metrics
        }

        if (runnerName != null && metrics.totalTimeInMs() > this.longestRunningTotalDurationInMs) {
            this.longestPhaseSpawnRunnerName = runnerName
            this.longestPhaseSpawnRunnerSubtype = runnerSubtype
            this.longestRunningTotalDurationInMs = metrics.totalTimeInMs()
        }
    }

    /** Set the phaseChange flag as true so we will aggregate incoming spawnMetrics.  */
    fun changePhase() {
        this.phaseChange = true
    }

    /**
     * Returns total spawn metrics of the maximum (longest running) spawn metrics of all phases for
     * the execution of the action.
     */
    fun getSpawnMetrics(): AggregatedSpawnMetrics {
        return totalSpawnMetrics
    }

    /**
     * Returns name of the maximum runner used for the finished spawn which took most time (see [ ][.addSpawnResult]), null if no spawns have finished for this action (either there
     * are no spawns or we asked before any have finished).
     */
    fun getLongestPhaseSpawnRunnerName(): String? {
        return longestPhaseSpawnRunnerName
    }

    /** Like getLongestPhaseSpawnRunnerName(), but returns the runner details.  */
    fun getLongestPhaseSpawnRunnerSubtype(): String? {
        return longestPhaseSpawnRunnerSubtype
    }

    /**
     * Updates the child component if the union of the new dependency component runtime and the
     * current component runtime is greater than the union of the current child runtime and current
     * component runtime. The caller should ensure the dependency component is not running.
     */
    @kotlin.jvm.Synchronized
    fun addDepInfo(dep: CriticalPathComponent, componentFinishNanos: Long) {
        val currentElapsedTime = componentFinishNanos - startNanos
        var aggregatedElapsedTime = dep.aggregatedElapsedTime + currentElapsedTime
        // This corrects the overlapping run time.
        if (dep.finishNanos > startNanos) {
            aggregatedElapsedTime -= dep.finishNanos - startNanos
        }
        if (child == null || aggregatedElapsedTime > this.aggregatedElapsedTime) {
            this.aggregatedElapsedTime = aggregatedElapsedTime
            child = dep
        }
    }

    fun getStartTimeNanos(): Long {
        return startNanos
    }

    fun getStartTimeMillisSinceEpoch(converter: BlazeClock.NanosToMillisSinceEpochConverter): Long {
        return converter.toEpochMillis(startNanos)
    }

    fun getElapsedTime(): Duration? {
        return Duration.ofNanos(getElapsedTimeNanos())
    }

    fun getElapsedTimeNanos(): Long {
        if (isRunning) {
            // It can happen that we're being asked to compute a critical path even though the build was
            // interrupted. In that case, we may not have gotten an action completion event. We don't have
            // access to the clock from here, so we have to return 0.
            // Note that the critical path never includes interrupted actions, so getAggregatedElapsedTime
            // does not get called in this state.
            // If we want the critical path to contain partially executed actions in a case of interrupt,
            // then we need to tell the critical path computer that the build was interrupt, and let it
            // artificially mark all such actions as done.
            return 0
        }
        return getElapsedTimeNanosNoCheck()
    }

    /** To be used only in debugging: skips state invariance checks to avoid crash-looping.  */
    private fun getElapsedTimeNoCheck(): Duration? {
        return Duration.ofNanos(getElapsedTimeNanosNoCheck())
    }

    private fun getElapsedTimeNanosNoCheck(): Long {
        // The delta value may be negative, see note in {@link Clock#nanoTime}.
        return max(0, finishNanos - startNanos)
    }

    /**
     * Returns the current critical path for the action.
     * 
     * 
     * Critical path is defined as : action_execution_time + max(child_critical_path).
     */
    fun getAggregatedElapsedTime(): Duration? {
        return Duration.ofNanos(aggregatedElapsedTime)
    }

    /**
     * Get the child critical path component.
     * 
     * 
     * The component dependency with the maximum total critical path time.
     */
    fun getChild(): CriticalPathComponent? {
        return child
    }

    /** Returns a string representation of the action. Only for use in crash messages and the like.  */
    private fun getActionString(): String {
        return action.prettyPrint()
    }

    /** Returns a user readable representation of the critical path stats with all the details.  */
    override fun toString(): String {
        val sb = StringBuilder()
        var currentTime = "still running"
        if (!isRunning) {
            currentTime = String.format("%.2f", getElapsedTimeNoCheck()!!.toMillis() / 1000.0) + "s"
        }
        sb.append(currentTime)
        if (remote) {
            sb.append(", ")
            sb.append(getSpawnMetrics().toString(getElapsedTimeNoCheck(),  /* summary= */false))
        }
        sb.append(" ")
        sb.append(getActionString())
        return sb.toString()
    }

    companion object {
        /** Empty metrics used to simplify handling of [.phaseMaxMetrics].  */
        private val EMPTY_PLACEHOLDER_METRICS: SpawnMetrics = SpawnMetrics.Builder.forOtherExec().build()
    }
}
