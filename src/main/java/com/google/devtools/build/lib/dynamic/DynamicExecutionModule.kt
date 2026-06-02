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
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Sets
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.google.devtools.build.lib.actions.ActionExecutionContext
import com.google.devtools.build.lib.concurrent.ExecutorUtil
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.events.Reporter
import com.google.devtools.build.lib.exec.SpawnStrategyRegistry
import com.google.devtools.common.options.OptionsBase
import com.google.errorprone.annotations.ForOverride
import java.lang.String
import java.util.*
import java.util.function.Function
import kotlin.Boolean
import kotlin.Int
import kotlin.collections.Iterable
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet

/** [BlazeModule] providing support for dynamic spawn execution and scheduling.  */
class DynamicExecutionModule : BlazeModule {
    private var executorService: ExecutorService? = null
    var ignoreLocalSignals: MutableSet<Int?> = ImmutableSet.of<Int?>()
    protected var reporter: Reporter? = null
    protected var verboseFailures: Boolean = false
    private var localOptions: LocalExecutionOptions? = null

    constructor()

    @VisibleForTesting
    internal constructor(executorService: ExecutorService?) {
        this.executorService = executorService
    }

    override fun getCommandOptions(commandName: String): Iterable<Class<out OptionsBase?>?> {
        return if (commandName == "build")
            ImmutableList.of<Class<out OptionsBase?>?>(DynamicExecutionOptions::class.java)
        else
            ImmutableList.of<Class<out OptionsBase?>?>()
    }

    override fun beforeCommand(env: CommandEnvironment) {
        val buildRequestOptions: O? = env.getOptions().getOptions<O?>(BuildRequestOptions::class.java)
        if (buildRequestOptions != null && buildRequestOptions.useAsyncExecution) {
            executorService =
                Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("dynamic-execution-thread-", 0).factory()
                )
        } else {
            executorService =
                Executors.newCachedThreadPool(
                    ThreadFactoryBuilder().setNameFormat("dynamic-execution-thread-%d").build()
                )
        }
        env.getEventBus().register(this)
        val executionOptions: ExecutionOptions? =
            env.getOptions().getOptions<ExecutionOptions?>(ExecutionOptions::class.java)
        verboseFailures = executionOptions != null && executionOptions.getVerboseFailures()
        val dynamicOptions: DynamicExecutionOptions? =
            env.getOptions().getOptions<DynamicExecutionOptions?>(DynamicExecutionOptions::class.java)
        localOptions = env.getOptions().getOptions<LocalExecutionOptions?>(LocalExecutionOptions::class.java)
        ignoreLocalSignals =
            if (dynamicOptions != null && dynamicOptions.getIgnoreLocalSignals() != null)
                dynamicOptions.getIgnoreLocalSignals()
            else
                ImmutableSet.of<Int?>()
        reporter = env.getReporter()
    }

    @VisibleForTesting
    @Throws(AbruptExitException::class)
    fun getLocalStrategies(
        options: DynamicExecutionOptions, sandboxingSupported: Boolean
    ): ImmutableMap<String?, MutableList<String?>?> {
        // Options that set "allowMultiple" to true ignore the default value, so we replicate that
        // functionality here.
        val localAndWorkerStrategies = ImmutableMap.builder<String?, MutableList<String?>?>()
        val defaultLocalStrategies = ImmutableList.builder<String?>()
        defaultLocalStrategies.add("worker")
        if (sandboxingSupported) {
            defaultLocalStrategies.add("sandboxed")
        }
        if (localOptions != null && localOptions.getLocalLockfreeOutput()) {
            // Without local lock free, having standalone execution risks very bad performance.
            defaultLocalStrategies.add("standalone")
        }
        localAndWorkerStrategies.put("", defaultLocalStrategies.build())

        for (entry in options.getDynamicLocalStrategy()) {
            localAndWorkerStrategies.put(entry)
            throwIfContainsDynamic(entry.getValue(), "--dynamic_local_strategy")
        }
        return localAndWorkerStrategies.buildKeepingLast()
    }

    @Throws(AbruptExitException::class)
    private fun getRemoteStrategies(options: DynamicExecutionOptions): ImmutableMap<String?, MutableList<String?>?> {
        val strategies: MutableMap<String?, MutableList<String?>?> =
            HashMap<String?, MutableList<String?>?>() // Needed to dedup
        for (e in options.getDynamicRemoteStrategy()) {
            throwIfContainsDynamic(e.getValue(), "--dynamic_remote_strategy")
            strategies.put(e.getKey(), e.getValue())
        }
        return if (options.getDynamicRemoteStrategy().isEmpty())
            ImmutableMap.of<String?, MutableList<String?>?>("", ImmutableList.of<String?>(remoteStrategyName()))
        else
            ImmutableMap.copyOf<String?, MutableList<String?>?>(strategies)
    }

    @ForOverride
    protected fun remoteStrategyName(): String {
        return "remote"
    }

    @Throws(AbruptExitException::class)
    override fun registerSpawnStrategies(
        registryBuilder: SpawnStrategyRegistry.Builder, env: CommandEnvironment
    ) {
        val options: DynamicExecutionOptions? =
            env.getOptions().getOptions<DynamicExecutionOptions?>(DynamicExecutionOptions::class.java)
        val execOptions: ExecutionOptions? =
            env.getOptions().getOptions<ExecutionOptions?>(ExecutionOptions::class.java)
        registerSpawnStrategies(
            registryBuilder,
            options!!,
            execOptions.getLocalResources().get(ResourceSet.CPU).toInt(),
            env.getOptions().getOptions<O?>(BuildRequestOptions::class.java).jobs
        )
    }

    // CommandEnvironment is difficult to access in tests, so use this method for testing.
    @VisibleForTesting
    @Throws(AbruptExitException::class)
    fun registerSpawnStrategies(
        registryBuilder: SpawnStrategyRegistry.Builder,
        options: DynamicExecutionOptions,
        numCpus: Int,
        jobs: Int
    ) {
        if (!options.getInternalSpawnScheduler()) {
            return
        }

        val strategy: SpawnStrategy =
            DynamicSpawnStrategy(
                executorService,
                options,
                Function { spawn: Spawn? -> this.getExecutionPolicy(spawn) },
                Function { spawn: Spawn? -> this.getPostProcessingSpawnForLocalExecution(spawn) },
                numCpus,
                jobs,
                IgnoreFailureCheck { spawn: Spawn?, context: ActionExecutionContext?, exitCode: Int, errorMessage: String?, outErr: FileOutErr?, isLocal: Boolean ->
                    this.canIgnoreFailure(
                        spawn,
                        context,
                        exitCode,
                        errorMessage,
                        outErr,
                        isLocal
                    )
                })
        registryBuilder.registerStrategy(strategy, "dynamic", "dynamic_worker")
        val sandboxingSupported = registryBuilder.isStrategyRegistered("sandboxed")
        registryBuilder.addDynamicLocalStrategies(getLocalStrategies(options, sandboxingSupported))
        registryBuilder.addDynamicRemoteStrategies(getRemoteStrategies(options))
    }

    @Throws(AbruptExitException::class)
    private fun throwIfContainsDynamic(strategies: MutableList<String?>, flagName: String?) {
        val identifiers = ImmutableSet.of<String?>("dynamic", "dynamic_worker")
        if (!Sets.intersection<String?>(identifiers, ImmutableSet.copyOf<String?>(strategies)).isEmpty()) {
            val message =
                String.format(
                    "Cannot use strategy %s in flag %s as it would create a cycle during" + " execution",
                    identifiers, flagName
                )
            throw AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setExecutionOptions(
                            ExecutionOptions.newBuilder().setCode(Code.INVALID_CYCLIC_DYNAMIC_STRATEGY)
                        )
                        .build()
                )
            )
        }
    }

    /**
     * Use the [Spawn] metadata to determine if it can be executed locally, remotely, or both.
     * 
     * @param spawn the [Spawn] action
     * @return the [ExecutionPolicy] containing local/remote execution policies
     */
    protected fun getExecutionPolicy(spawn: Spawn?): ExecutionPolicy {
        if (!Spawns.mayBeExecutedRemotely(spawn)) {
            return ExecutionPolicy.Companion.LOCAL_EXECUTION_ONLY
        }
        if (!Spawns.mayBeExecutedLocally(spawn)) {
            return ExecutionPolicy.Companion.REMOTE_EXECUTION_ONLY
        }

        return ExecutionPolicy.Companion.ANYWHERE
    }

    /**
     * Returns a post processing [Spawn] if one needs to be executed after given [Spawn]
     * when running locally.
     * 
     * 
     * The intention of this is to allow post-processing of the original [spawn][Spawn]
     * when executing it locally. In particular, such spawn should never create outputs which are not
     * included in the generating action of the original one.
     */
    protected fun getPostProcessingSpawnForLocalExecution(spawn: Spawn?): Optional<Spawn?> {
        return Optional.empty<Spawn?>()
    }

    /**
     * If true, the failure passed in can be ignored in one branch to allow the other branch to finish
     * it instead. This can e.g. allow ignoring remote execution timeouts or local-only permission
     * failures.
     * 
     * @param spawn The spawn being executed.
     * @param exitCode The exit code from executing the spawn
     * @param errorMessage Error messages returned from executing the spawn
     * @param outErr The location of the stdout and stderr from the spawn.
     * @param isLocal True if this is the locally-executed branch.
     * @return True if this failure is one that we want to allow the other branch to succeed at, even
     * though this branch failed already.
     */
    fun canIgnoreFailure(
        spawn: Spawn,
        context: ActionExecutionContext?,
        exitCode: Int,
        errorMessage: kotlin.String?,
        outErr: FileOutErr?,
        isLocal: Boolean
    ): Boolean {
        // By convention, when killed by a signal, a process gives exit code (128 + signal number).
        // More accurate information could be had through {@code waitid(2)}, but Java does not expose
        // that. But accuracy is not critical here, at worst we are a bit slower in getting either
        // a success or a failure.
        val signal = exitCode - 128
        if (isLocal && ignoreLocalSignals.contains(signal)) {
            if (verboseFailures) {
                reporter!!.handle(
                    Event.Companion.info(
                        String.format(
                            "Local execution for %s stopped by signal %d, ignoring in favor of remote"
                                    + " execution.",
                            spawn.getResourceOwner().prettyPrint(), signal
                        )
                    )
                )
            }
            logger.atInfo().log("Ignoring dynamic local branch killed by signal %d", signal)
            return true
        }
        return false
    }

    internal fun interface IgnoreFailureCheck {
        fun canIgnoreFailure(
            spawn: Spawn?,
            context: ActionExecutionContext?,
            exitCode: Int,
            errorMessage: kotlin.String?,
            outErr: FileOutErr?,
            isLocal: Boolean
        ): Boolean
    }

    override fun afterCommand() {
        ExecutorUtil.interruptibleShutdown(executorService)
        executorService = null
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
