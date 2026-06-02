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
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode.REMOTE
import com.google.devtools.build.lib.events.Event
import java.lang.String
import kotlin.AssertionError
import kotlin.Int
import kotlin.Throwable

/**
 * The remove version of Branch. On top of the usual stop handles setting [ ][.delayLocalExecution] when getting a cache hit.
 */
@VisibleForTesting
internal class RemoteBranch(
    actionExecutionContext: ActionExecutionContext?,
    spawn: Spawn?,
    strategyThatCancelled: AtomicReference<DynamicMode?>?,
    options: DynamicExecutionOptions?,
    ignoreFailureCheck: IgnoreFailureCheck?,
    delayLocalExecution: AtomicBoolean
) : Branch(actionExecutionContext, spawn, strategyThatCancelled, options) {
    private val ignoreFailureCheck: IgnoreFailureCheck?
    private val delayLocalExecution: AtomicBoolean

    init {
        this.ignoreFailureCheck = ignoreFailureCheck
        this.delayLocalExecution = delayLocalExecution
    }

    override fun getMode(): DynamicMode {
        return REMOTE
    }

    @Throws(InterruptedException::class, ExecException::class)
    public override fun callImpl(context: ActionExecutionContext): ImmutableList<SpawnResult> {
        checkNotNull(otherBranch) { "prepareFuture not called" }
        try {
            if (!starting.compareAndSet(true, false)) {
                // If we ever get here, it's because we were cancelled early and the listener
                // ran first. Just make sure that's the case.
                Preconditions.checkState(Thread.interrupted())
                throw InterruptedException()
            }
            return runRemotely(
                spawn,
                context,
                StopConcurrentSpawns? { exitCode, errorMessage, outErr ->
                if (!future.isCancelled()) {
                    maybeIgnoreFailure(exitCode, errorMessage, outErr)
                }
                DynamicSpawnStrategy.Companion.stopBranch(
                    otherBranch, this, strategyThatCancelled, options, this.context
                )
            },
            delayLocalExecution)
        } catch (e: DynamicInterruptedException) {
            if (options.getDebugSpawnScheduler()) {
                logger.atInfo().log(
                    "Remote branch of %s self-cancelling with %s: '%s'",
                    spawn.getResourceOwner().prettyPrint(), e.getClass().getSimpleName(), e.getMessage()
                )
            }
            // This exception can be thrown due to races in stopBranch(), in which case
            // the branch that lost the race may not have been cancelled yet. Cancel it here
            // to prevent the listener from cross-cancelling.
            future.cancel(true)
            throw e
        } catch (e: Throwable) {
            if (options.getDebugSpawnScheduler()) {
                logger.atInfo().log(
                    "Remote branch of %s failed with %s: '%s'",
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
    @kotlin.jvm.Synchronized
    @Throws(DynamicInterruptedException::class)
    protected fun maybeIgnoreFailure(
        exitCode: Int, errorMessage: String?, outErr: FileOutErr?
    ) {
        if (exitCode == 0 || ignoreFailureCheck == null) {
            return
        }
        synchronized(spawn) {
            if (ignoreFailureCheck.canIgnoreFailure(
                    spawn, context, exitCode, errorMessage, outErr, false
                )
            ) {
                throw DynamicInterruptedException(
                    String.format(
                        "Remote branch of %s cancelling self in favor of local.",
                        spawn.getResourceOwner().prettyPrint()
                    )
                )
            }
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * Try to run the given spawn remotely. If successful, updates [.delayLocalExecution] if
         * there was a cache hit among the results.
         * 
         * 
         * Precondition: At least one `dynamic_remote_strategy` returns `true` from its
         * [canExec][SpawnStrategy.canExec] method for the given `spawn`.
         */
        @Throws(ExecException::class, InterruptedException::class)
        fun runRemotely(
            spawn: Spawn,
            actionExecutionContext: ActionExecutionContext,
            stopConcurrentSpawns: StopConcurrentSpawns?,
            delayLocalExecution: AtomicBoolean
        ): ImmutableList<SpawnResult> {
            val dynamicStrategyRegistry: DynamicStrategyRegistry =
                actionExecutionContext.getContext(DynamicStrategyRegistry::class.java)

            for (strategy in dynamicStrategyRegistry.getDynamicSpawnActionContexts(spawn, REMOTE)) {
                if (strategy.canExec(spawn, actionExecutionContext)) {
                    val results: ImmutableList<SpawnResult>? =
                        strategy.exec(spawn, actionExecutionContext, stopConcurrentSpawns)
                    if (results == null) {
                        actionExecutionContext
                            .getEventHandler()
                            .handle(
                                Event.Companion.warn(
                                    String.format(
                                        "Remote strategy %s for %s target %s returned null, which it shouldn't"
                                                + " do.",
                                        strategy, spawn.getMnemonic(), spawn.getResourceOwner().prettyPrint()
                                    )
                                )
                            )
                    }
                    for (r in results!!) {
                        if (r.isCacheHit()) {
                            delayLocalExecution.set(true)
                            break
                        }
                    }
                    return results
                }
            }
            throw AssertionError("canExec passed but no usable remote strategy for action " + spawn)
        }
    }
}
