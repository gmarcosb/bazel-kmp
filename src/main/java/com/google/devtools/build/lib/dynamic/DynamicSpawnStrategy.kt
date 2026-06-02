// Copyright 2018 The Bazel Authors. All rights reserved.
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
import com.google.common.base.Throwables
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode.LOCAL
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.profiler.Profiler
import com.google.devtools.build.lib.profiler.ProfilerTask
import com.google.errorprone.annotations.FormatMethod
import com.google.errorprone.annotations.FormatString
import java.lang.String
import java.time.Duration
import java.util.Optional
import java.util.concurrent.Future
import java.util.function.Function
import java.util.function.Supplier
import java.util.logging.Level
import kotlin.Any
import kotlin.AssertionError
import kotlin.Boolean
import kotlin.Int
import kotlin.RuntimeException
import kotlin.Throwable
import kotlin.plus
import kotlin.toString

/**
 * A spawn strategy that speeds up incremental builds while not slowing down full builds.
 * 
 * 
 * This strategy tries to run spawn actions on the local and remote machine at the same time, and
 * picks the spawn action that completes first. This gives the benefits of remote execution on full
 * builds, and local execution on incremental builds.
 * 
 * 
 * One might ask, why we don't run spawns on the workstation all the time and just "spill over"
 * actions to remote execution when there are no local resources available. This would work, except
 * that the cost of transferring action inputs and outputs from the local machine to and from remote
 * executors over the network is way too high - there is no point in executing an action locally and
 * save 0.5s of time, when it then takes us 5 seconds to upload the results to remote executors for
 * another action that's scheduled to run there.
 */
class DynamicSpawnStrategy(
    executorService: ExecutorService,
    private val options: DynamicExecutionOptions,
    getExecutionPolicy: Function<Spawn?, ExecutionPolicy>,
    getPostProcessingSpawnForLocalExecution: Function<Spawn?, Optional<Spawn?>?>,
    numCpus: Int,
    jobs: Int,
    ignoreFailureCheck: IgnoreFailureCheck?
) : SpawnStrategy {
    private val executorService: ListeningExecutorService
    private val getExecutionPolicy: Function<Spawn?, ExecutionPolicy>

    /**
     * Set to true by the first action that completes remotely. Until that happens, all local actions
     * are delayed by the amount given in [DynamicExecutionOptions.localExecutionDelay].
     * 
     * 
     * This is a rather simple approach to make it possible to score a cache hit on remote
     * execution before even trying to start the action locally. This saves resources that would
     * otherwise be wasted by continuously starting and immediately killing local processes. One
     * possibility for improvement would be to establish a reporting mechanism from strategies back to
     * here, where we delay starting locally until the remote strategy tells us that the action isn't
     * a cache hit.
     */
    private val delayLocalExecution: AtomicBoolean = AtomicBoolean(false)

    private val getExtraSpawnForLocalExecution: Function<Spawn?, Optional<Spawn?>?>

    /** A callback that allows checking if a given failure can be ignored on one branch.  */
    private val ignoreFailureCheck: IgnoreFailureCheck?

    /** Limit on how many threads we should use for dynamic execution.  */
    private val threadLimiter: ShrinkableSemaphore

    /** Set of jobs that are waiting for local execution.  */
    private val waitingLocalJobs: Deque<LocalBranch> = ArrayDeque<LocalBranch>()

    /**
     * Constructs a `DynamicSpawnStrategy`.
     * 
     * @param executorService an [ExecutorService] that will be used to run Spawn actions.
     * @param options The options for dynamic execution.
     * @param getExecutionPolicy Function that will give an execution policy for a given [     ].
     * @param getPostProcessingSpawnForLocalExecution A function that returns any post-processing
     * spawns that should be run after finishing running a spawn locally.
     * @param numCpus The number of CPUs allowed for local execution (--local_resources=cpu=).
     * @param jobs The maximum number of jobs (--jobs parameter).
     * @param ignoreFailureCheck A callback to check if a failure on one branch should be allowed to
     * be ignored in favor of the other branch.
     */
    init {
        this.executorService = MoreExecutors.listeningDecorator(executorService)
        this.getExecutionPolicy = getExecutionPolicy
        this.getExtraSpawnForLocalExecution = getPostProcessingSpawnForLocalExecution
        this.threadLimiter =
            ShrinkableSemaphore(
                if (options.getLocalLoadFactor() > 0) numCpus else jobs, jobs, options.getLocalLoadFactor()
            )
        this.ignoreFailureCheck = ignoreFailureCheck
    }

    public override fun canExec(spawn: Spawn?, actionContextRegistry: ActionContext.ActionContextRegistry): Boolean {
        val executionPolicy: ExecutionPolicy = getExecutionPolicy.apply(spawn)
        val dynamicStrategyRegistry: DynamicStrategyRegistry =
            actionContextRegistry.getContext(DynamicStrategyRegistry::class.java)

        return canExecLocal(spawn, executionPolicy, actionContextRegistry, dynamicStrategyRegistry)
                || canExecRemote(spawn, executionPolicy, actionContextRegistry, dynamicStrategyRegistry)
    }

    @Throws(ExecException::class, InterruptedException::class)
    public override fun exec(
        spawn: Spawn, actionExecutionContext: ActionExecutionContext
    ): ImmutableList<SpawnResult?> {
        val nonDynamicResults: ImmutableList<SpawnResult?>? =
            maybeExecuteNonDynamically(spawn, actionExecutionContext)
        if (nonDynamicResults != null) {
            return nonDynamicResults
        }

        debugLog("Dynamic execution of %s beginning%n", getSpawnReadableId(spawn))

        // else both can exec. Fallthrough to below.
        val strategyThatCancelled: AtomicReference<DynamicMode?> = AtomicReference<DynamicMode?>(null)

        val localBranch =
            LocalBranch(
                actionExecutionContext,
                spawn,
                strategyThatCancelled,
                options,
                ignoreFailureCheck,
                getExtraSpawnForLocalExecution,
                delayLocalExecution
            )
        val remoteBranch =
            RemoteBranch(
                actionExecutionContext,
                spawn,
                strategyThatCancelled,
                options,
                ignoreFailureCheck,
                delayLocalExecution
            )
        localBranch.prepareFuture(remoteBranch)
        remoteBranch.prepareFuture(localBranch)
        synchronized(waitingLocalJobs) {
            waitingLocalJobs.add(localBranch)
            tryScheduleLocalJob()
        }
        remoteBranch.execute(executorService)

        var results: ImmutableList<SpawnResult?>? = null
        try {
            results = waitBranches(localBranch, remoteBranch, spawn, options, actionExecutionContext)
            return results
        } finally {
            Preconditions.checkState(localBranch.isDone())
            Preconditions.checkState(remoteBranch.isDone())

            if (results != null && !results.isEmpty()) {
                updateStrategyWinner(actionExecutionContext, spawn, results.get(0), strategyThatCancelled)
            }

            synchronized(waitingLocalJobs) {
                if (!waitingLocalJobs.remove(localBranch)) {
                    threadLimiter.release()
                    tryScheduleLocalJob()
                }
            }
            debugLog(
                "Dynamic execution of %s ended with local %s, remote %s%n",
                getSpawnReadableId(spawn),
                if (localBranch.isCancelled()) "cancelled" else "done",
                if (remoteBranch.isCancelled()) "cancelled" else "done"
            )
        }
    }

    fun updateStrategyWinner(
        context: ActionExecutionContext,
        spawn: Spawn,
        result: SpawnResult,
        strategyThatCancelled: AtomicReference<DynamicMode?>
    ) {
        val dynamicStrategyRegistry: DynamicStrategyRegistry =
            context.getContext(DynamicStrategyRegistry::class.java)
        val executionPolicy: ExecutionPolicy = getExecutionPolicy.apply(spawn)

        // In case of remote runner, we could have "runner-name-cached" instead of "runner-name", in
        // this case we want more precise name of branch.
        val winner: String? = result.getRunnerName()
        val localStrategy: SandboxedSpawnStrategy? =
            getLocalStrategy(spawn, executionPolicy, context, dynamicStrategyRegistry)
        val remoteStrategy: SandboxedSpawnStrategy? =
            getRemoteStrategy(spawn, executionPolicy, context, dynamicStrategyRegistry)

        if (localStrategy == null || remoteStrategy == null) {
            return
        }

        var localName: String? = localStrategy.toString()
        var remoteName: String? = remoteStrategy.toString()

        var winnerBranchType: DynamicMode? = null
        if (strategyThatCancelled.get() == null) {
            return
        }

        when (strategyThatCancelled.get()) {
            LOCAL -> {
                localName = winner
                winnerBranchType = LOCAL
            }

            REMOTE -> {
                remoteName = winner
                winnerBranchType = REMOTE
            }
        }

        context
            .getEventHandler()
            .post(
                DynamicExecutionFinishedEvent(
                    spawn.getMnemonic(), localName, remoteName, winnerBranchType
                )
            )
    }

    /**
     * Tries to schedule as many local jobs as are permitted by [.threadLimiter]. "Scheduling"
     * here means putting it on a thread and making it start the normal strategy execution, but it
     * will still have to wait for resources, so it may not execute for a while.
     */
    private fun tryScheduleLocalJob() {
        synchronized(waitingLocalJobs) {
            threadLimiter.updateLoad(waitingLocalJobs.size())
            while (!waitingLocalJobs.isEmpty() && threadLimiter.tryAcquire()) {
                val job: LocalBranch
                // TODO(b/120910324): Prioritize jobs where the remote branch has already failed.
                if (options.getSlowRemoteTime() != null && options.getSlowRemoteTime()
                        .compareTo(Duration.ZERO) > 0 && waitingLocalJobs.peekFirst().getAge()
                        .compareTo(options.getSlowRemoteTime()) > 0
                ) {
                    job = waitingLocalJobs.pollFirst()
                } else {
                    job = waitingLocalJobs.pollLast()
                }
                job.execute(executorService)
            }
        }
    }

    /**
     * Checks if this action should be executed dynamically, and if not executes it locally or
     * remotely as applicable, or throws an exception if it cannot be executed at all.
     * 
     * @param spawn Spawn in the process of being executed.
     * @param actionExecutionContext Execution context
     * @return Results from execution if the action was executed (possibly empty) or null if this
     * action can be executed dynamically.
     * @throws ExecException If we tried to execute and executed failed.
     * @throws InterruptedException If we tried to execute and got interrupted.
     */
    @VisibleForTesting
    @Throws(ExecException::class, InterruptedException::class)
    fun maybeExecuteNonDynamically(
        spawn: Spawn, actionExecutionContext: ActionExecutionContext
    ): ImmutableList<SpawnResult?>? {
        val postProcessingSpawn: Spawn? = getExtraSpawnForLocalExecution.apply(spawn)!!.orElse(null)
        val executionPolicy: ExecutionPolicy = getExecutionPolicy.apply(spawn)
        val postProcessingSpawnExecutionPolicy: ExecutionPolicy =
            if (postProcessingSpawn == null) null else getExecutionPolicy.apply(postProcessingSpawn)
        val dynamicStrategyRegistry: DynamicStrategyRegistry =
            actionExecutionContext.getContext(DynamicStrategyRegistry::class.java)

        val spawnLocalCanExec: Boolean =
            canExecLocal(spawn, executionPolicy, actionExecutionContext, dynamicStrategyRegistry)
        val postProcessingSpawnLocalCanExec =
            postProcessingSpawn == null
                    || canExecLocal(
                postProcessingSpawn,
                postProcessingSpawnExecutionPolicy,
                actionExecutionContext,
                dynamicStrategyRegistry
            )
        // To declare a spawn being executable in local, we need to make sure that the post-processing
        // spawn is also executable in local.
        val localCanExec = spawnLocalCanExec && postProcessingSpawnLocalCanExec
        val remoteCanExec: Boolean =
            canExecRemote(spawn, executionPolicy, actionExecutionContext, dynamicStrategyRegistry)

        if (!localCanExec && !remoteCanExec) {
            val failure: FailureDetail? =
                FailureDetail.newBuilder()
                    .setMessage(
                        getNoExecutableUserExecExceptionMessage(
                            spawn,
                            spawnLocalCanExec,
                            executionPolicy,
                            postProcessingSpawn,
                            postProcessingSpawnLocalCanExec,
                            postProcessingSpawnExecutionPolicy
                        )
                    ) // This use of `setDynamicExecution` was overwritten by a call to `setSpawn` below, as
                    // they're in a oneof. This may be a bug! Please fix, or delete this redundant call.
                    .setDynamicExecution(
                        DynamicExecution.newBuilder().setCode(Code.NO_USABLE_STRATEGY_FOUND).build()
                    )
                    .setSpawn(
                        FailureDetails.Spawn.newBuilder()
                            .setCode(FailureDetails.Spawn.Code.NO_USABLE_STRATEGY_FOUND)
                            .build()
                    )
                    .build()
            debugLog(
                "Dynamic execution of %s can be done neither locally nor remotely%n",
                getSpawnReadableId(spawn)
            )
            throw UserExecException(failure)
        } else if (!localCanExec && remoteCanExec) {
            val spawnExplanation =
                String.format(
                    "Local execution policy of the spawn %s dynamic execution, local strategies of the"
                            + " spawn are %s",
                    if (executionPolicy.canRunLocally()) "allows" else "forbids",
                    dynamicStrategyRegistry.getDynamicSpawnActionContexts(spawn, DynamicMode.LOCAL)
                )
            val postProcessingSpawnExplanation =
                if (postProcessingSpawn == null)
                    "the post-processing spawn doesn't exist"
                else
                    String.format(
                        "local execution policy of the post-processing spawn %s dynamic execution, local"
                                + " strategies of the post-processing spawn are %s",
                        if (postProcessingSpawnExecutionPolicy.canRunLocally()) "allows" else "forbids",
                        dynamicStrategyRegistry.getDynamicSpawnActionContexts(
                            postProcessingSpawn, DynamicMode.LOCAL
                        )
                    )
            debugLog(
                "Dynamic execution of %s can only be done remotely: %s. And %s.%n",
                getSpawnReadableId(spawn), spawnExplanation, postProcessingSpawnExplanation
            )
            return RemoteBranch.Companion.runRemotely(spawn, actionExecutionContext, null, delayLocalExecution)
        } else if (localCanExec && !remoteCanExec) {
            debugLog(
                "Dynamic execution of %s can only be done locally: Remote execution policy %s it, "
                        + "remote strategies are %s.%n",
                getSpawnReadableId(spawn),
                if (executionPolicy.canRunRemotely()) "allows" else "forbids",
                dynamicStrategyRegistry.getDynamicSpawnActionContexts(spawn, REMOTE)
            )
            return LocalBranch.Companion.runLocally(
                spawn, actionExecutionContext, null, getExtraSpawnForLocalExecution
            )
        } else if (options.getExcludeTools()) {
            if (spawn.getResourceOwner().getOwner().isBuildConfigurationForTool()) {
                return RemoteBranch.Companion.runRemotely(spawn, actionExecutionContext, null, delayLocalExecution)
            }
        }
        return null
    }

    @FormatMethod
    private fun stepLog(
        level: Level?, cause: Throwable?, @FormatString fmt: kotlin.String?, vararg args: Any?
    ) {
        logger.at(level).withCause(cause).logVarargs(fmt, args)
    }

    @FormatMethod
    private fun debugLog(fmt: kotlin.String?, vararg args: Any?) {
        if (options.getDebugSpawnScheduler()) {
            stepLog(Level.FINE, null, fmt, *args)
        }
    }

    public override fun usedContext(actionContextRegistry: ActionContext.ActionContextRegistry) {
        actionContextRegistry
            .getContext(DynamicStrategyRegistry::class.java)
            .notifyUsedDynamic(actionContextRegistry)
    }

    override fun toString(): kotlin.String {
        return "dynamic"
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private fun canExecLocal(
            spawn: Spawn?,
            executionPolicy: ExecutionPolicy,
            acr: ActionContext.ActionContextRegistry?,
            dsr: DynamicStrategyRegistry
        ): Boolean {
            return getLocalStrategy(spawn, executionPolicy, acr, dsr) != null
        }

        private fun getLocalStrategy(
            spawn: Spawn?,
            executionPolicy: ExecutionPolicy,
            acr: ActionContext.ActionContextRegistry?,
            dsr: DynamicStrategyRegistry
        ): SandboxedSpawnStrategy? {
            if (!executionPolicy.canRunLocally()) {
                return null
            }
            for (s in dsr.getDynamicSpawnActionContexts(spawn, LOCAL)) {
                if (s.canExec(spawn, acr)) {
                    return s
                }
            }
            return null
        }

        private fun canExecRemote(
            spawn: Spawn?,
            executionPolicy: ExecutionPolicy,
            acr: ActionContext.ActionContextRegistry?,
            dsr: DynamicStrategyRegistry
        ): Boolean {
            return getRemoteStrategy(spawn, executionPolicy, acr, dsr) != null
        }

        private fun getRemoteStrategy(
            spawn: Spawn?,
            executionPolicy: ExecutionPolicy,
            acr: ActionContext.ActionContextRegistry?,
            dsr: DynamicStrategyRegistry
        ): SandboxedSpawnStrategy? {
            if (!executionPolicy.canRunRemotely()) {
                return null
            }

            for (s in dsr.getDynamicSpawnActionContexts(spawn, REMOTE)) {
                if (s.canExec(spawn, acr)) {
                    return s
                }
            }
            return null
        }

        /**
         * Returns an error string for being unable to execute locally and/or remotely the given execution
         * state.
         * 
         * 
         * Usage note, this method is only to be called after an impossible condition is already
         * detected by the caller, as all this does is give an error string to put in the exception.
         * 
         * 
         * When the spawn is executable in local but the post-processing spawn is not, it's also not
         * allowed to execute local actions. For this reason, we should log the information for both the
         * spawn and the post-processing spawn.
         * 
         * @param spawn The action that needs to be executed.
         * @param spawnLocalCanExec Whether the spawn can be executed locally or not.
         * @param spawnExecutionPolicy The execution policy for the spawn.
         * @param postProcessingSpawn The action that needs to be executed following the spawn.
         * @param postProcessingSpawnLocalCanExec Whether the post-processing spawn can be executed
         * locally or not.
         * @param postProcessingSpawnExecutionPolicy The execution policy for the post-processing spawn.
         */
        private fun getNoExecutableUserExecExceptionMessage(
            spawn: Spawn,
            spawnLocalCanExec: Boolean,
            spawnExecutionPolicy: ExecutionPolicy,
            postProcessingSpawn: Spawn,
            postProcessingSpawnLocalCanExec: Boolean,
            postProcessingSpawnExecutionPolicy: ExecutionPolicy
        ): kotlin.String {
            // TODO(b/188402092): Consider using Spawn.toString() when the mnemonic is included in the
            // output unconditionally.
            val msg = StringBuilder()
            if (!spawnLocalCanExec) {
                msg.append("Spawn is not executable in local: ")
                    .append(getSpawnNotExecutableReason(spawn, spawnExecutionPolicy))
            }
            if (!postProcessingSpawnLocalCanExec) {
                msg.append("Post-Processing Spawn is not executable in local: ")
                    .append(
                        getSpawnNotExecutableReason(postProcessingSpawn, postProcessingSpawnExecutionPolicy)
                    )
            }
            return msg.toString()
        }

        private fun getSpawnNotExecutableReason(
            spawn: Spawn, spawnExecutionPolicy: ExecutionPolicy
        ): kotlin.String {
            val msg = StringBuilder()
            if (!spawnExecutionPolicy.canRunLocally() && !spawnExecutionPolicy.canRunRemotely()) {
                msg.append("Neither local nor remote execution allowed for action ")
            } else if (!spawnExecutionPolicy.canRunRemotely()) {
                msg.append(
                    "No usable dynamic_local_strategy found (and remote execution disabled) for action "
                )
            } else if (!spawnExecutionPolicy.canRunLocally()) {
                msg.append(
                    "No usable dynamic_remote_strategy found (and local execution disabled) for action "
                )
            } else {
                msg.append("No usable dynamic_local_strategy or dynamic_remote_strategy found for action ")
            }
            msg.append(spawn.getMnemonic()).append(". ")
            return msg.toString()
        }

        /**
         * Waits for the two branches of a spawn's execution to complete.
         * 
         * 
         * This guarantees that the two branches are stopped both on successful termination and on an
         * exception.
         * 
         * @param localBranch the future running the local side of the spawn. This future must cancel
         * `remoteBranch` at some point during its successful execution to guarantee
         * termination. If we encounter an execution error, or if we are interrupted, then we handle
         * such cancellation here.
         * @param remoteBranch the future running the remote side of the spawn. Same restrictions apply as
         * in `localBranch`, but in the symmetric direction.
         * @param options the options relevant for dynamic execution
         * @param context execution context object
         * @return the result of the branch that terminates first
         * @throws ExecException the execution error of the spawn that terminated first
         * @throws InterruptedException if we get interrupted while waiting for completion
         */
        @VisibleForTesting
        @Throws(ExecException::class, InterruptedException::class)
        fun waitBranches(
            localBranch: LocalBranch,
            remoteBranch: RemoteBranch,
            spawn: Spawn,
            options: DynamicExecutionOptions,
            context: ActionExecutionContext
        ): ImmutableList<SpawnResult?> {
            val localResult: ImmutableList<SpawnResult?>?
            try {
                localResult = waitBranch(localBranch, options, context)
            } catch (e: ExecException) {
                if (options.getDebugSpawnScheduler()) {
                    context
                        .getEventHandler()
                        .handle(
                            Event.Companion.info(
                                String.format(
                                    "Cancelling remote branch of %s after local exception %s",
                                    getSpawnReadableId(spawn), e.getMessage()
                                )
                            )
                        )
                }
                remoteBranch.cancel()
                throw e
            } catch (e: InterruptedException) {
                if (options.getDebugSpawnScheduler()) {
                    context
                        .getEventHandler()
                        .handle(
                            Event.Companion.info(
                                String.format(
                                    "Cancelling remote branch of %s after local exception %s",
                                    getSpawnReadableId(spawn), e.getMessage()
                                )
                            )
                        )
                }
                remoteBranch.cancel()
                throw e
            } catch (e: RuntimeException) {
                if (options.getDebugSpawnScheduler()) {
                    context
                        .getEventHandler()
                        .handle(
                            Event.Companion.info(
                                String.format(
                                    "Cancelling remote branch of %s after local exception %s",
                                    getSpawnReadableId(spawn), e.getMessage()
                                )
                            )
                        )
                }
                remoteBranch.cancel()
                throw e
            }

            val remoteResult: ImmutableList<SpawnResult?>? = waitBranch(remoteBranch, options, context)

            if (remoteResult != null && localResult != null) {
                throw AssertionError(
                    String.format(
                        "Neither branch of %s cancelled the other one. Local was %s and remote was %s.",
                        getSpawnReadableId(spawn), localBranch.branchState(), remoteBranch.branchState()
                    )
                )
            } else if (localResult != null) {
                return localResult
            } else if (remoteResult != null) {
                return remoteResult
            } else {
                // TODO(b/173153395): Sometimes gets thrown for currently unknown reasons.
                // (sometimes happens in relation to the whole dynamic execution being cancelled)
                throw AssertionError(
                    String.format(
                        "Neither branch of %s completed. Local was %s and remote was %s.",
                        getSpawnReadableId(spawn), localBranch.branchState(), remoteBranch.branchState()
                    )
                )
            }
        }

        /**
         * Waits for a branch (a spawn execution) to complete.
         * 
         * @param branch the future running the spawn
         * @param options the options relevant for dynamic execution
         * @param context execution context object
         * @return the spawn result if the execution terminated successfully, or null if the branch was
         * cancelled
         * @throws ExecException the execution error of the spawn if it failed
         * @throws InterruptedException if we get interrupted while waiting for completion
         */
        @Throws(ExecException::class, InterruptedException::class)
        private fun waitBranch(
            branch: Branch, options: DynamicExecutionOptions, context: ActionExecutionContext
        ): ImmutableList<SpawnResult?>? {
            val mode: DynamicMode = branch.getMode()
            try {
                val spawnResults: ImmutableList<SpawnResult?>? = branch.getResults()
                if (spawnResults == null && options.getDebugSpawnScheduler()) {
                    context
                        .getEventHandler()
                        .handle(
                            Event.Companion.info(
                                String.format(
                                    "Null results from %s branch of %s",
                                    mode, getSpawnReadableId(branch.getSpawn())
                                )
                            )
                        )
                }
                return spawnResults
            } catch (e: CancellationException) {
                if (options.getDebugSpawnScheduler()) {
                    context
                        .getEventHandler()
                        .handle(
                            Event.Companion.info(
                                String.format(
                                    "CancellationException of %s branch of %s, returning null",
                                    mode, getSpawnReadableId(branch.getSpawn())
                                )
                            )
                        )
                }
                return null
            } catch (e: ExecutionException) {
                val cause: Throwable = e.getCause()
                if (cause is ExecException) {
                    throw cause
                } else if (cause is InterruptedException) {
                    // If the branch was interrupted, it might be due to a user interrupt or due to our request
                    // for cancellation. Assume the latter here because if this was actually a user interrupt,
                    // our own get() would have been interrupted as well. It makes no sense to propagate the
                    // interrupt status across threads.
                    if (options.getDebugSpawnScheduler()) {
                        context
                            .getEventHandler()
                            .handle(
                                Event.Companion.info(
                                    String.format(
                                        "Caught InterruptedException from ExecutionException for %s branch of %s,"
                                                + " which may cause a crash:\n%s",
                                        mode,
                                        getSpawnReadableId(branch.getSpawn()),
                                        Throwables.getStackTraceAsString(cause)
                                    )
                                )
                            )
                    }
                    return null
                } else {
                    // Even though we cannot enforce this in the future's signature (but we do in Branch#call),
                    // we only expect the exception types we validated above. Still, unchecked exceptions could
                    // propagate, so just let them bubble up.
                    Throwables.throwIfUnchecked(cause)
                    throw AssertionError(
                        String.format(
                            "Unexpected exception type %s from %s strategy.exec() for %s",
                            cause.getClass().getName(), mode, getSpawnReadableId(branch.getSpawn())
                        )
                    )
                }
            } catch (e: InterruptedException) {
                branch.cancel()
                throw e
            }
        }

        /**
         * Cancels and waits for a branch (a spawn execution) to terminate.
         * 
         * 
         * This is intended to be used as the body of the [ ] lambda passed to the spawn runners. Each strategy
         * may call this at most once.
         * 
         * @param otherBranch The other branch, the one that should be cancelled.
         * @param cancellingBranch The branch that is performing the cancellation.
         * @param strategyThatCancelled name of the first strategy that executed this method, or a null
         * reference if this is the first time this method is called. If not null, we expect the value
         * referenced by this to be different than `cancellingStrategy`, or else we have a bug.
         * @param options The options for dynamic execution.
         * @param context The context of this action execution.
         * @throws InterruptedException if we get interrupted for any reason trying to cancel the future
         * @throws DynamicInterruptedException if we lost a race against another strategy trying to cancel
         * us
         */
        @Throws(InterruptedException::class)
        fun stopBranch(
            otherBranch: Branch,
            cancellingBranch: Branch,
            strategyThatCancelled: AtomicReference<DynamicMode?>,
            options: DynamicExecutionOptions,
            context: ActionExecutionContext
        ) {
            val cancellingStrategy: DynamicMode = cancellingBranch.getMode()
            if (cancellingBranch.isCancelled()) {
                throw DynamicInterruptedException(
                    String.format(
                        "Execution of %s strategy was cancelled just before it could get the lock.",
                        cancellingStrategy
                    )
                )
            }
            // This multi-step, unlocked access to "strategyThatCancelled" is valid because, for a given
            // value of "cancellingStrategy", we do not expect concurrent calls to this method. (If there
            // are, we are in big trouble.)
            val current: DynamicMode? = strategyThatCancelled.get()
            if (!cancellingStrategy.equals(current)) {
                // Protect against the two branches from cancelling each other. The first branch to set the
                // reference to its own identifier wins and is allowed to issue the cancellation; the other
                // branch just has to give up execution.
                if (strategyThatCancelled.compareAndSet(null, cancellingStrategy)) {
                    if (options.getDebugSpawnScheduler()) {
                        context
                            .getEventHandler()
                            .handle(
                                Event.Companion.info(
                                    String.format(
                                        "%s branch of %s finished and was %s",
                                        strategyThatCancelled.get(),
                                        getSpawnReadableId(cancellingBranch.getSpawn()),
                                        if (cancellingBranch.isCancelled()) "cancelled" else "not cancelled"
                                    )
                                )
                            )
                    }

                    Profiler.instance()
                        .profile(
                            ProfilerTask.DYNAMIC_LOCK,
                            Supplier {
                                String.format(
                                    "Cancelling %s branch of %s",
                                    cancellingStrategy.other(),
                                    getSpawnReadableId(cancellingBranch.getSpawn())
                                )
                            }).use { c ->
                            if (!otherBranch.cancel()) {
                                // This can happen if the other branch is local under local_lockfree and has returned
                                // its result but not yet cancelled this branch, or if the other branch was already
                                // cancelled for other reasons. In the latter case, we are good to continue.
                                if (otherBranch.future.state() == Future.State.SUCCESS) {
                                    throw DynamicInterruptedException(
                                        String.format(
                                            "Execution of %s strategy stopped because %s strategy could not be cancelled",
                                            cancellingStrategy, cancellingStrategy.other()
                                        )
                                    )
                                }
                            }
                            otherBranch.getDoneSemaphore().acquire()
                        }
                } else {
                    throw DynamicInterruptedException(
                        String.format(
                            "Execution of %s strategy stopped because %s strategy finished first",
                            cancellingStrategy, strategyThatCancelled.get()
                        )
                    )
                }
            }
        }

        private fun getSpawnReadableId(spawn: Spawn): kotlin.String? {
            val action: ActionExecutionMetadata = spawn.getResourceOwner()
            val primaryOutput: Artifact? = action.getPrimaryOutput()
            // In some cases, primary output could be null despite the method promises. And in that case, we
            // can't use action.prettyPrint as it assumes a non-null primary output.
            if (primaryOutput == null) {
                var label = ""
                if (action.getOwner() != null && action.getOwner().getLabel() != null) {
                    label = " " + action.getOwner().getLabel().toString()
                }
                return spawn.getMnemonic() + label
            }

            return primaryOutput.prettyPrint()
        }
    }
}
