// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.dynamic

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode.LOCAL
import com.google.devtools.build.lib.profiler.Profiler
import java.lang.String
import java.time.Duration
import java.util.*
import java.util.function.Function
import java.util.function.Predicate
import kotlin.AssertionError
import kotlin.Int
import kotlin.Throwable
import kotlin.plus

/**
 * The local version of a Branch. On top of normal Branch things, this handles delaying after remote
 * cache hits and passing the extra-spawn function.
 */
@VisibleForTesting
internal class LocalBranch(
    actionExecutionContext: ActionExecutionContext?,
    spawn: Spawn?,
    strategyThatCancelled: AtomicReference<DynamicMode?>?,
    options: DynamicExecutionOptions?,
    ignoreFailureCheck: IgnoreFailureCheck?,
    getExtraSpawnForLocalExecution: Function<Spawn?, Optional<Spawn?>>,
    delayLocalExecution: AtomicBoolean
) : Branch(actionExecutionContext, spawn, strategyThatCancelled, options) {
    private val ignoreFailureCheck: IgnoreFailureCheck?
    private val getExtraSpawnForLocalExecution: Function<Spawn?, Optional<Spawn?>>
    private val delayLocalExecution: AtomicBoolean
    private val creationTime: Instant = Instant.now()

    init {
        this.ignoreFailureCheck = ignoreFailureCheck
        this.getExtraSpawnForLocalExecution = getExtraSpawnForLocalExecution
        this.delayLocalExecution = delayLocalExecution
    }

    override fun getMode(): DynamicMode {
        return LOCAL
    }

    val age: Duration?
        get() = Duration.between(creationTime, Instant.now())

    @Throws(InterruptedException::class, ExecException::class)
    override fun callImpl(context: ActionExecutionContext): ImmutableList<SpawnResult?> {
        checkNotNull(otherBranch) { "prepareFuture not called" }
        try {
            if (!starting.compareAndSet(true, false)) {
                // If we ever get here, it's because we were cancelled early and the listener
                // ran first. Just make sure that's the case.
                Preconditions.checkState(Thread.interrupted())
                throw InterruptedException()
            }
            if (delayLocalExecution.get()) {
                Profiler.instance().profile("delay local branch").use { c ->
                    Thread.sleep(options.getLocalExecutionDelay().toLong())
                }
            }
            return Companion.runLocally(
                spawn,
                context,
                SandboxedSpawnStrategy.StopConcurrentSpawns? { exitCode, errorMessage, outErr ->
                if (!future.isCancelled()) {
                    maybeIgnoreFailure(exitCode, errorMessage, outErr)
                }
                DynamicSpawnStrategy.Companion.stopBranch(
                    otherBranch, this, strategyThatCancelled, options, this.context
                )
            },
            getExtraSpawnForLocalExecution)
        } catch (e: DynamicInterruptedException) {
            if (options.getDebugSpawnScheduler()) {
                logger.atInfo().log(
                    "Local branch of %s self-cancelling with %s: '%s'",
                    spawn.getResourceOwner().prettyPrint(), e.getClass().getSimpleName(), e.getMessage()
                )
            }
            // This exception can be thrown due to races in stopBranch(), in which case
            // the branch that lost the race may not have been cancelled yet. Cancel it here
            // to prevent the listener from cross-cancelling.
            cancel()
            throw e
        } catch (e: Throwable) {
            if (options.getDebugSpawnScheduler()) {
                logger.atInfo().log(
                    "Local branch of %s failed with %s: '%s'",
                    spawn.getResourceOwner().prettyPrint(), e.getClass().getSimpleName(), e.getMessage()
                )
            }
            throw e
        } finally {
            done.release()
        }
    }

    /**
     * Called when execution failed, to check if we should allow the other branch to continue instead
     * of failing.
     * 
     * @throws DynamicInterruptedException if this failure can be ignored in favor of the result of
     * the other branch.
     */
    @Throws(DynamicInterruptedException::class)
    protected fun maybeIgnoreFailure(exitCode: Int, errorMessage: String?, outErr: FileOutErr?) {
        if (exitCode == 0 || ignoreFailureCheck == null) {
            return
        }
        synchronized(spawn) {
            if (ignoreFailureCheck.canIgnoreFailure(
                    spawn, context, exitCode, errorMessage, outErr, true
                )
            ) {
                throw DynamicInterruptedException(
                    String.format(
                        "Local branch of %s cancelling self in favor of remote.",
                        spawn.getResourceOwner().prettyPrint()
                    )
                )
            }
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * Try to run the given spawn locally.
         * 
         * 
         * Precondition: At least one `dynamic_local_strategy` returns `true` from its
         * [canExec][SpawnStrategy.canExec] method for the given `spawn`.
         */
        @Throws(ExecException::class, InterruptedException::class)
        fun runLocally(
            spawn: Spawn,
            actionExecutionContext: ActionExecutionContext,
            stopConcurrentSpawns: SandboxedSpawnStrategy.StopConcurrentSpawns?,
            getExtraSpawnForLocalExecution: Function<Spawn?, Optional<Spawn?>>
        ): ImmutableList<SpawnResult?> {
            val spawnResult: ImmutableList<SpawnResult?> =
                runSpawnLocally(spawn, actionExecutionContext, stopConcurrentSpawns)
            if (spawnResult.stream()
                    .anyMatch(Predicate { result: SpawnResult? -> result.status() !== Status.SUCCESS })
            ) {
                return spawnResult
            }

            val extraSpawn: Optional<Spawn?> = getExtraSpawnForLocalExecution.apply(spawn)
            if (!extraSpawn.isPresent()) {
                return spawnResult
            }

            // The remote branch was already cancelled -- we are holding the output lock during the
            // execution of the extra spawn.
            val extraSpawnResult: ImmutableList<SpawnResult?> =
                runSpawnLocally(extraSpawn.get(), actionExecutionContext, null)
            return ImmutableList.builderWithExpectedSize<SpawnResult?>(
                spawnResult.size() + extraSpawnResult.size()
            )
                .addAll(spawnResult)
                .addAll(extraSpawnResult)
                .build()
        }

        @Throws(ExecException::class, InterruptedException::class)
        private fun runSpawnLocally(
            spawn: Spawn,
            actionExecutionContext: ActionExecutionContext,
            stopConcurrentSpawns: SandboxedSpawnStrategy.StopConcurrentSpawns?
        ): ImmutableList<SpawnResult?> {
            val dynamicStrategyRegistry: DynamicStrategyRegistry =
                actionExecutionContext.getContext(DynamicStrategyRegistry::class.java)

            for (strategy in dynamicStrategyRegistry.getDynamicSpawnActionContexts(spawn, LOCAL)) {
                if (strategy.canExec(spawn, actionExecutionContext)) {
                    val results: ImmutableList<SpawnResult?>? =
                        strategy.exec(spawn, actionExecutionContext, stopConcurrentSpawns)
                    if (results == null) {
                        logger.atWarning().log(
                            "Local strategy %s for %s target %s returned null, which it shouldn't do.",
                            strategy, spawn.getMnemonic(), spawn.getResourceOwner().prettyPrint()
                        )
                    }

                    return results
                }
            }
            throw AssertionError("canExec passed but no usable local strategy for action " + spawn)
        }
    }
}
