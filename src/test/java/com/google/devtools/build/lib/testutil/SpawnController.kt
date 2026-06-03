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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.actions.ActionContext.ActionContextRegistry

/**
 * Test utility that allows for controlling the behavior of spawns by using [SpawnShim].
 * 
 * 
 * To install in integration tests, use [ControllableActionStrategyModule].
 */
class SpawnController {
    /** The means of controlling [SpawnStrategy.exec] calls.  */
    interface SpawnShim {
        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun getExecResult(spawn: Spawn?, context: ActionExecutionContext?): ExecResult
    }

    /**
     * Represents the desired behavior of a [SpawnStrategy.exec] call. Instances represent one
     * of the following:
     * 
     * 
     *  * A [SpawnResult], created with [.of].
     *  * An [ExecException], created with [.ofException].
     *  * A delegate to the underlying [SpawnStrategy], created with [.delegate].
     * 
     */
    class ExecResult private constructor(execException: ExecException?, spawnResult: SpawnResult?) {
        private val execException: ExecException?
        private val spawnResult: SpawnResult?

        init {
            this.execException = execException
            this.spawnResult = spawnResult
        }

        companion object {
            private val DELEGATE = ExecResult( /*execException=*/null,  /*spawnResult=*/null)

            /** Override the action by returning the provided [SpawnResult].  */
            fun of(spawnResult: SpawnResult?): ExecResult {
                return ExecResult( /*execException=*/null,
                    com.google.common.base.Preconditions.checkNotNull<SpawnResult?>(spawnResult)
                )
            }

            /** Override the action by throwing the provided [ExecException].  */
            fun ofException(execException: ExecException?): ExecResult {
                return ExecResult(
                    com.google.common.base.Preconditions.checkNotNull<ExecException?>(execException),  /*spawnResult=*/
                    null
                )
            }

            /** Do not override the action. Allow the underlying [SpawnStrategy] to handle it.  */
            fun delegate(): ExecResult {
                return DELEGATE
            }
        }
    }

    private val executedSpawnDescriptions: MutableList<String?> =
        Collections.synchronizedList<String?>(java.util.ArrayList<String?>())

    private val spawnShims: com.google.common.collect.ListMultimap<String?, SpawnShim?> =
        com.google.common.collect.Multimaps.synchronizedListMultimap<String?, SpawnShim?>(com.google.common.collect.LinkedListMultimap.create<String?, SpawnShim?>())

    /**
     * Returns a list of all executed spawn descriptions seen by strategies created via [.wrap]
     * (in order) since the last call to [.clearExecutedSpawnDescriptions].
     */
    fun getExecutedSpawnDescriptions(): com.google.common.collect.ImmutableList<String?> {
        return com.google.common.collect.ImmutableList.copyOf<String?>(executedSpawnDescriptions)
    }

    /** Clears the list of executed spawn descriptions.  */
    fun clearExecutedSpawnDescriptions() {
        executedSpawnDescriptions.clear()
    }

    /**
     * Injects custom spawn behavior for [controllable strategies][.wrap].
     * 
     * 
     * The given [SpawnShim] is enqueued for a single execution of a spawn with the given
     * description. When a matching spawn is seen, an associated [SpawnShim] is dequeued and
     * used in a FIFO manner. If there are no matching shims enqueued, the delegate strategy is used.
     */
    fun addSpawnShim(spawnDescription: String?, spawnShim: SpawnShim?) {
        spawnShims.put(spawnDescription, spawnShim)
    }

    /**
     * Creates a new [SpawnStrategy] that picks up custom behavior added via [ ][.addSpawnShim] and delegates to the given `delegate` if necessary.
     */
    fun wrap(delegate: SpawnStrategy?): SpawnStrategy {
        return ControllableSpawnStrategy(delegate)
    }

    /**
     * Checks that all spawn shims added via [.addSpawnShim] have been consumed, throwing an
     * [IllegalStateException] if any remain.
     * 
     * 
     * This can be used to verify that shims were configured correctly.
     */
    fun verifyAllShimsConsumed() {
        com.google.common.base.Preconditions.checkState(spawnShims.isEmpty(), "Remaining spawn shims: %s", spawnShims)
    }

    private inner class ControllableSpawnStrategy(delegate: SpawnStrategy?) : SandboxedSpawnStrategy {
        private val delegate: SpawnStrategy

        init {
            this.delegate = com.google.common.base.Preconditions.checkNotNull<SpawnStrategy>(delegate)
        }

        @Throws(ExecException::class, java.lang.InterruptedException::class)
        public override fun exec(
            spawn: Spawn,
            actionExecutionContext: ActionExecutionContext?,
            stopConcurrentSpawns: SandboxedSpawnStrategy.StopConcurrentSpawns?
        ): com.google.common.collect.ImmutableList<SpawnResult?>? {
            return exec(spawn, actionExecutionContext)
        }

        @Throws(ExecException::class, java.lang.InterruptedException::class)
        public override fun exec(
            spawn: Spawn, actionExecutionContext: ActionExecutionContext?
        ): com.google.common.collect.ImmutableList<SpawnResult?>? {
            val description: String = spawn.getResourceOwner().describe()
            executedSpawnDescriptions.add(description)

            val events: MutableList<SpawnShim?> = spawnShims.get(description)
            if (!events.isEmpty()) {
                val execResult: ExecResult
                try {
                    execResult = events.removeAt(0)!!.getExecResult(spawn, actionExecutionContext)
                } catch (e: IOException) {
                    throw SpawnShimException(e, description)
                }
                if (execResult.execException != null) {
                    throw execResult.execException
                }
                if (execResult.spawnResult != null) {
                    return com.google.common.collect.ImmutableList.of<SpawnResult?>(execResult.spawnResult)
                }
            }

            return delegate.exec(spawn, actionExecutionContext)
        }

        public override fun canExec(spawn: Spawn?, actionContextRegistry: ActionContextRegistry?): Boolean {
            return delegate.canExec(spawn, actionContextRegistry)
        }
    }

    private class SpawnShimException(e: IOException?, private val description: String) : ExecException(e) {
        protected val messageForActionExecutionException: String
            get() = ("In a test shim, failed to determine ExecException for action: "
                    + description
                    + ": "
                    + getCause().getMessage())

        protected override fun getFailureDetail(message: String?): FailureDetail {
            return CrashFailureDetails.forThrowable(this)
        }
    }
}
