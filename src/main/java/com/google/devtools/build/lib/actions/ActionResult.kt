// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.auto.value.AutoBuilder
import com.google.devtools.build.lib.actions.SpawnResult

/**
 * Holds the result(s) of an action's execution.
 * 
 * @param spawnResults Returns the SpawnResults for the action.
 */
class ActionResult(spawnResults: com.google.common.collect.ImmutableList<SpawnResult?>?) {
    /**
     * Returns the cumulative total of long values taken from a series of [SpawnResult]s.
     * 
     * @param getSpawnResultLongValue a selector that returns a long value for each [     ] being considered
     * @return the total, or null if no spawn results contained this long value
     */
    private fun getCumulativeLong(getSpawnResultLongValue: java.util.function.Function<SpawnResult?, Long?>): Long? {
        var longTotal: Long? = null
        for (spawnResult in this.spawnResults) {
            val longValue: Long? = getSpawnResultLongValue.apply(spawnResult)
            if (longValue != null) {
                if (longTotal == null) {
                    longTotal = longValue
                } else {
                    longTotal += longValue
                }
            }
        }
        return longTotal
    }

    /**
     * Returns the cumulative total of int values taken from a series of [SpawnResult]s.
     * 
     * @param getSpawnResultIntValue a selector that returns an int value for each [SpawnResult]
     * being considered
     * @return the total value of this values
     */
    private fun getCumulativeInt(getSpawnResultIntValue: java.util.function.Function<SpawnResult?, Int?>): Int {
        var intTotal = 0
        for (spawnResult in this.spawnResults) {
            intTotal += getSpawnResultIntValue.apply(spawnResult)
        }
        return intTotal
    }

    /**
     * Returns the cumulative command execution wall time for the [Action].
     * 
     * @return the cumulative measurement, or zero in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeCommandExecutionWallTimeInMs(): Int {
        return getCumulativeInt(java.util.function.Function { obj: SpawnResult? -> obj.getWallTimeInMs() })
    }

    /**
     * Returns the cumulative command execution user time for the [Action].
     * 
     * @return the cumulative measurement, or zero in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeCommandExecutionUserTimeInMs(): Int {
        return getCumulativeInt(java.util.function.Function { obj: SpawnResult? -> obj.getUserTimeInMs() })
    }

    /**
     * Returns the cumulative command execution system time for the [Action].
     * 
     * @return the cumulative measurement, or zero in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeCommandExecutionSystemTimeInMs(): Int {
        return getCumulativeInt(java.util.function.Function { obj: SpawnResult? -> obj.getSystemTimeInMs() })
    }

    /**
     * Returns the cumulative number of block input operations for the [Action].
     * 
     * @return the cumulative measurement, or null in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeCommandExecutionBlockInputOperations(): Long? {
        return getCumulativeLong(java.util.function.Function { obj: SpawnResult? -> obj.getNumBlockInputOperations() })
    }

    /**
     * Returns the cumulative number of block output operations for the [Action].
     * 
     * @return the cumulative measurement, or null in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeCommandExecutionBlockOutputOperations(): Long? {
        return getCumulativeLong(java.util.function.Function { obj: SpawnResult? -> obj.getNumBlockOutputOperations() })
    }

    /**
     * Returns the cumulative number of involuntary context switches for the [Action].
     * 
     * @return the cumulative measurement, or null in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeCommandExecutionInvoluntaryContextSwitches(): Long? {
        return getCumulativeLong(java.util.function.Function { obj: SpawnResult? -> obj.getNumInvoluntaryContextSwitches() })
    }

    /**
     * Returns the cumulative number of involuntary context switches for the [Action]. The
     * spawns on one action could execute simultaneously, so the sum of spawn's memory usage is better
     * estimation.
     * 
     * @return the cumulative measurement, or null in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeCommandExecutionMemoryInKb(): Long? {
        return getCumulativeLong(java.util.function.Function { obj: SpawnResult? -> obj.getMemoryInKb() })
    }

    /**
     * Returns the cumulative spawns total time for the [Action].
     * 
     * @return the cumulative measurement, or zero in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeSpawnsTotalTimeInMs(): Int {
        return getCumulativeInt(java.util.function.Function { s: SpawnResult? -> s.getMetrics().totalTimeInMs() })
    }

    /**
     * Returns the cumulative spawns parse time for the [Action].
     * 
     * @return the cumulative measurement, or zero in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeSpawnsParseTimeInMs(): Int {
        return getCumulativeInt(java.util.function.Function { s: SpawnResult? -> s.getMetrics().parseTimeInMs() })
    }

    /**
     * Returns the cumulative spawns network time for the [Action].
     * 
     * @return the cumulative measurement, or zero in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeSpawnsNetworkTimeInMs(): Int {
        return getCumulativeInt(java.util.function.Function { s: SpawnResult? -> s.getMetrics().networkTimeInMs() })
    }

    /**
     * Returns the cumulative spawns fetch time for the [Action].
     * 
     * @return the cumulative measurement, or zero in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeSpawnsFetchTimeInMs(): Int {
        return getCumulativeInt(java.util.function.Function { s: SpawnResult? -> s.getMetrics().fetchTimeInMs() })
    }

    /**
     * Returns the cumulative spawns queue time for the [Action].
     * 
     * @return the cumulative measurement, or zero in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeSpawnsQueueTimeInMs(): Int {
        return getCumulativeInt(java.util.function.Function { s: SpawnResult? -> s.getMetrics().queueTimeInMs() })
    }

    /**
     * Returns the cumulative spawns setup time for the [Action].
     * 
     * @return the cumulative measurement, or zero in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeSpawnsSetupTimeInMs(): Int {
        return getCumulativeInt(java.util.function.Function { s: SpawnResult? -> s.getMetrics().setupTimeInMs() })
    }

    /**
     * Returns the cumulative spawns upload time for the [Action].
     * 
     * @return the cumulative measurement, or zero in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeSpawnsUploadTimeInMs(): Int {
        return getCumulativeInt(java.util.function.Function { s: SpawnResult? -> s.getMetrics().uploadTimeInMs() })
    }

    /**
     * Returns the cumulative spawns execution wall time for the [Action].
     * 
     * @return the cumulative measurement, or zero in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeExecutionWallTimeInMs(): Int {
        return getCumulativeInt(java.util.function.Function { s: SpawnResult? ->
            s.getMetrics().executionWallTimeInMs()
        })
    }

    /**
     * Returns the cumulative spawns process output time for the [Action].
     * 
     * @return the cumulative measurement, or zero in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeProcessOutputTimeInMs(): Int {
        return getCumulativeInt(java.util.function.Function { s: SpawnResult? ->
            s.getMetrics().processOutputsTimeInMs()
        })
    }

    /**
     * Returns the cumulative spawns retry time for the [Action].
     * 
     * @return the cumulative measurement, or zero in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeRetryTimeInMs(): Int {
        return getCumulativeInt(java.util.function.Function { s: SpawnResult? -> s.getMetrics().retryTimeInMs() })
    }

    /**
     * Returns the cumulative command execution CPU time for the [Action].
     * 
     * @return the cumulative measurement, or zero in case of execution errors or when the measurement
     * is not implemented for the current platform
     */
    fun cumulativeCommandExecutionCpuTimeInMs(): Int {
        val userTime = cumulativeCommandExecutionUserTimeInMs()
        val systemTime = cumulativeCommandExecutionSystemTimeInMs()

        // If userTime or systemTime is nondefined (=0), then it will not change a result
        return userTime + systemTime
    }

    /** Builder for a [ActionResult] instance, which is immutable once built.  */
    @AutoBuilder
    abstract class Builder {
        /** Sets the SpawnResults for the action.  */
        abstract fun setSpawnResults(spawnResults: com.google.common.collect.ImmutableList<SpawnResult?>?): Builder?

        /** Builds and returns an ActionResult object.  */
        abstract fun build(): ActionResult?
    }

    val spawnResults: com.google.common.collect.ImmutableList<SpawnResult?>?

    init {
        this.spawnResults = spawnResults
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<SpawnResult?>?>(
            spawnResults,
            "spawnResults"
        )
    }

    companion object {
        /** An empty ActionResult used by Actions that don't have any metadata to return.  */
        @kotlin.jvm.JvmField
        val EMPTY: ActionResult? = create(com.google.common.collect.ImmutableList.of<SpawnResult?>())

        /** Returns a builder that can be used to construct a [ActionResult] object.  */
        fun builder(): Builder {
            return AutoBuilder_ActionResult_Builder()
        }

        /** Creates an ActionResult given a list of SpawnResults.  */
        fun create(spawnResults: MutableList<SpawnResult?>?): ActionResult? {
            if (spawnResults == null) {
                return EMPTY
            } else {
                return builder()
                    .setSpawnResults(com.google.common.collect.ImmutableList.copyOf<SpawnResult?>(spawnResults))!!
                    .build()
            }
        }
    }
}
