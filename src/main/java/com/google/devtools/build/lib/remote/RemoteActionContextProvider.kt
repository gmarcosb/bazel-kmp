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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.Digest

/** Provides a remote execution context.  */
internal class RemoteActionContextProvider private constructor(
    env: CommandEnvironment?,
    combinedCache: CombinedCache?,
    remoteExecutor: RemoteExecutionClient?,
    retryScheduler: com.google.common.util.concurrent.ListeningScheduledExecutorService?,
    digestUtil: DigestUtil?,
    logDir: com.google.devtools.build.lib.vfs.Path?,
    remoteOutputChecker: RemoteOutputChecker?,
    outputService: OutputService?,
    knownMissingCasDigests: MutableSet<Digest?>?
) {
    private val env: CommandEnvironment
    private val combinedCache: CombinedCache?
    private val remoteExecutor: RemoteExecutionClient?
    private val retryScheduler: com.google.common.util.concurrent.ListeningScheduledExecutorService?
    private val digestUtil: DigestUtil?
    private val logDir: com.google.devtools.build.lib.vfs.Path?
    private var tempPathGenerator: TempPathGenerator? = null
    private var remoteExecutionService: RemoteExecutionService? = null
    private var actionInputFetcher: RemoteActionInputFetcher? = null
    private val remoteOutputChecker: RemoteOutputChecker?
    private val outputService: OutputService?
    private val knownMissingCasDigests: MutableSet<Digest?>?

    init {
        this.env = com.google.common.base.Preconditions.checkNotNull<CommandEnvironment>(env, "env")
        this.combinedCache = combinedCache
        this.remoteExecutor = remoteExecutor
        this.retryScheduler = retryScheduler
        this.digestUtil = digestUtil
        this.logDir = logDir
        this.remoteOutputChecker = remoteOutputChecker
        this.outputService = outputService
        this.knownMissingCasDigests = knownMissingCasDigests
    }

    private fun createRemotePathResolver(): RemotePathResolver {
        val execRoot: com.google.devtools.build.lib.vfs.Path = env.getExecRoot()
        val buildLanguageOptions: BuildLanguageOptions? =
            env.getOptions().getOptions<BuildLanguageOptions?>(BuildLanguageOptions::class.java)
        val remotePathResolver: RemotePathResolver
        if (buildLanguageOptions != null
            && buildLanguageOptions.getExperimentalSiblingRepositoryLayout()
        ) {
            remotePathResolver = SiblingRepositoryLayoutResolver(execRoot)
        } else {
            remotePathResolver = DefaultRemotePathResolver(execRoot)
        }
        return remotePathResolver
    }

    fun setActionInputFetcher(actionInputFetcher: RemoteActionInputFetcher?) {
        this.actionInputFetcher = actionInputFetcher
    }

    fun getActionInputFetcher(): RemoteActionInputFetcher {
        return com.google.common.base.Preconditions.checkNotNull<RemoteActionInputFetcher>(actionInputFetcher)
    }

    private fun getRemoteExecutionService(): RemoteExecutionService? {
        if (remoteExecutionService == null) {
            val workingDirectory: com.google.devtools.build.lib.vfs.Path = env.getWorkingDirectory()
            val remoteOptions: RemoteOptions = com.google.common.base.Preconditions.checkNotNull<RemoteOptions>(
                env.getOptions().getOptions<RemoteOptions?>(RemoteOptions::class.java)
            )
            var captureCorruptedOutputsDir: com.google.devtools.build.lib.vfs.Path? = null
            if (remoteOptions.getRemoteCaptureCorruptedOutputs() != null
                && !remoteOptions.getRemoteCaptureCorruptedOutputs().isEmpty()
            ) {
                captureCorruptedOutputsDir =
                    workingDirectory.getRelative(remoteOptions.getRemoteCaptureCorruptedOutputs())
            }

            val verboseFailures: Boolean =
                com.google.common.base.Preconditions.checkNotNull<T?>(
                    env.getOptions().getOptions<O?>(ExecutionOptions::class.java)
                ).verboseFailures
            remoteExecutionService =
                RemoteExecutionService(
                    env.getReporter(),
                    verboseFailures,
                    env.getExecRoot(),
                    createRemotePathResolver(),
                    env.getBuildRequestId(),
                    env.getCommandId().toString(),
                    env.getWorkspaceName(),
                    digestUtil,
                    com.google.common.base.Preconditions.checkNotNull<RemoteOptions?>(
                        env.getOptions().getOptions<RemoteOptions?>(RemoteOptions::class.java)
                    ),
                    com.google.common.base.Preconditions.checkNotNull<T?>(
                        env.getOptions().getOptions<O?>(ExecutionOptions::class.java)
                    ),
                    combinedCache,
                    remoteExecutor,
                    tempPathGenerator,
                    captureCorruptedOutputsDir,
                    remoteOutputChecker,
                    outputService,
                    knownMissingCasDigests
                )
            env.getEventBus().register(remoteExecutionService)
        }

        return remoteExecutionService
    }

    /**
     * Registers a remote spawn strategy if this instance was created with an executor, otherwise does
     * nothing.
     * 
     * @param registryBuilder builder with which to register the strategy
     */
    fun registerRemoteSpawnStrategy(registryBuilder: SpawnStrategyRegistry.Builder) {
        val executionOptions: ExecutionOptions =
            com.google.common.base.Preconditions.checkNotNull<T>(
                env.getOptions().getOptions<O?>(ExecutionOptions::class.java)
            )
        val spawnRunner: RemoteSpawnRunner =
            RemoteSpawnRunner(
                com.google.common.base.Preconditions.checkNotNull<RemoteOptions?>(
                    env.getOptions().getOptions<RemoteOptions?>(RemoteOptions::class.java)
                ),
                executionOptions.verboseFailures,
                env.getReporter(),
                retryScheduler,
                logDir,
                getRemoteExecutionService(),
                digestUtil
            )
        registryBuilder.registerStrategy(
            RemoteSpawnStrategy(spawnRunner, executionOptions), "remote"
        )
    }

    /**
     * Registers a spawn cache action context
     * 
     * @param registryBuilder builder with which to register the cache
     */
    fun registerSpawnCache(registryBuilder: ModuleActionContextRegistry.Builder) {
        val spawnCache: RemoteSpawnCache =
            RemoteSpawnCache(
                com.google.common.base.Preconditions.checkNotNull<RemoteOptions?>(
                    env.getOptions().getOptions<RemoteOptions?>(RemoteOptions::class.java)
                ),
                com.google.common.base.Preconditions.checkNotNull<T?>(
                    env.getOptions().getOptions<O?>(ExecutionOptions::class.java)
                ).verboseFailures,
                getRemoteExecutionService(),
                digestUtil
            )
        registryBuilder.register(SpawnCache::class.java, spawnCache, "remote-cache")
    }

    fun getCombinedCache(): CombinedCache? {
        return combinedCache
    }

    val remoteExecutionClient: RemoteExecutionClient?
        get() = remoteExecutor

    fun setTempPathGenerator(tempPathGenerator: TempPathGenerator?) {
        this.tempPathGenerator = tempPathGenerator
    }

    fun afterCommand() {
        // actionInputFetcher uses combinedCache to prefetch inputs, so it must be shut down first.
        if (actionInputFetcher != null) {
            actionInputFetcher.shutdown()
        }
        if (remoteExecutionService != null) {
            remoteExecutionService.shutdown()
        } else {
            if (combinedCache != null) {
                combinedCache.release()
            }
            if (remoteExecutor != null) {
                remoteExecutor.close()
            }
        }

        if (outputService is BazelOutputService) {
            outputService.shutdown()
        }
    }

    companion object {
        fun createForPlaceholder(
            env: CommandEnvironment?,
            retryScheduler: com.google.common.util.concurrent.ListeningScheduledExecutorService?,
            digestUtil: DigestUtil?,
            knownMissingCasDigests: MutableSet<Digest?>?
        ): RemoteActionContextProvider {
            return RemoteActionContextProvider(
                env,  /* combinedCache= */
                null,  /* remoteExecutor= */
                null,
                retryScheduler,
                digestUtil,  /* logDir= */
                null,  /* remoteOutputChecker= */
                null,  /* outputService= */
                null,
                knownMissingCasDigests
            )
        }

        fun createForRemoteCaching(
            env: CommandEnvironment?,
            combinedCache: CombinedCache?,
            retryScheduler: com.google.common.util.concurrent.ListeningScheduledExecutorService?,
            digestUtil: DigestUtil?,
            remoteOutputChecker: RemoteOutputChecker?,
            outputService: OutputService?,
            knownMissingCasDigests: MutableSet<Digest?>?
        ): RemoteActionContextProvider {
            return RemoteActionContextProvider(
                env,
                combinedCache,  /* remoteExecutor= */
                null,
                retryScheduler,
                digestUtil,  /* logDir= */
                null,
                remoteOutputChecker,
                com.google.common.base.Preconditions.checkNotNull<OutputService?>(outputService),
                knownMissingCasDigests
            )
        }

        fun createForRemoteExecution(
            env: CommandEnvironment?,
            remoteCache: RemoteExecutionCache?,
            remoteExecutor: RemoteExecutionClient?,
            retryScheduler: com.google.common.util.concurrent.ListeningScheduledExecutorService?,
            digestUtil: DigestUtil?,
            logDir: com.google.devtools.build.lib.vfs.Path?,
            remoteOutputChecker: RemoteOutputChecker?,
            outputService: OutputService?,
            knownMissingCasDigests: MutableSet<Digest?>?
        ): RemoteActionContextProvider {
            return RemoteActionContextProvider(
                env,
                remoteCache,
                remoteExecutor,
                retryScheduler,
                digestUtil,
                logDir,
                remoteOutputChecker,
                com.google.common.base.Preconditions.checkNotNull<OutputService?>(outputService),
                knownMissingCasDigests
            )
        }
    }
}
