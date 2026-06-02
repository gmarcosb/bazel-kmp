// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionContext

/** Abstract common ancestor for spawn strategies implementing the common parts.  */
abstract class AbstractSpawnStrategy protected constructor(
    spawnRunner: SpawnRunner,
    executionOptions: ExecutionOptions
) : SandboxedSpawnStrategy {
    private val spawnInputExpander: SpawnInputExpander = SpawnInputExpander()
    private val spawnRunner: SpawnRunner
    private val executionOptions: ExecutionOptions

    init {
        this.spawnRunner = spawnRunner
        this.executionOptions = executionOptions
    }

    /**
     * Gets the [SpawnRunner] that this [AbstractSpawnStrategy] uses to actually run
     * spawns.
     * 
     * 
     * This is considered a stop-gap until we refactor the entire SpawnStrategy / SpawnRunner
     * mechanism to no longer need Spawn strategies.
     */
    fun getSpawnRunner(): SpawnRunner {
        return spawnRunner
    }

    public override fun canExec(spawn: Spawn?, actionContextRegistry: ActionContext.ActionContextRegistry?): Boolean {
        return spawnRunner.canExec(spawn)
    }

    @Throws(ExecException::class, java.lang.InterruptedException::class)
    public override fun exec(
        spawn: Spawn,
        actionExecutionContext: ActionExecutionContext
    ): com.google.common.collect.ImmutableList<SpawnResult?> {
        return exec(spawn, actionExecutionContext, null)
    }

    @Throws(ExecException::class, java.lang.InterruptedException::class)
    public override fun exec(
        spawn: Spawn,
        actionExecutionContext: ActionExecutionContext,
        stopConcurrentSpawns: SandboxedSpawnStrategy.StopConcurrentSpawns?
    ): com.google.common.collect.ImmutableList<SpawnResult?> {
        actionExecutionContext.maybeReportSubcommand(spawn, spawnRunner.getName())

        val timeout: java.time.Duration? = Spawns.getTimeout(spawn)
        val context: SpawnExecutionContext =
            SpawnExecutionContextImpl(spawn, actionExecutionContext, stopConcurrentSpawns, timeout)

        // Avoid caching for runners which handle caching internally e.g. RemoteSpawnRunner.
        var cache: SpawnCache? =
            if (spawnRunner.handlesCaching())
                SpawnCache.Companion.NO_CACHE
            else
                actionExecutionContext.getContext(SpawnCache::class.java)

        // In production, the getContext method guarantees that we never get null back. However, our
        // integration tests don't set it up correctly, so cache may be null in testing.
        if (cache == null) {
            cache = SpawnCache.Companion.NO_CACHE
        }

        // Avoid using the remote cache of a dynamic execution setup for the local runner.
        if (context.speculating() && !cache.usefulInDynamicExecution()) {
            cache = SpawnCache.Companion.NO_CACHE
        }
        var spawnResult: SpawnResult
        var ex: ExecException? = null
        try {
            cache.lookup(spawn, context).use { cacheHandle ->
                if (cacheHandle.hasResult()) {
                    spawnResult =
                        com.google.common.base.Preconditions.checkNotNull<SpawnResult>(cacheHandle.getResult())
                } else {
                    val startTime: Instant? =
                        Instant.ofEpochMilli(actionExecutionContext.getClock().currentTimeMillis())
                    // Actual execution.
                    spawnResult = spawnRunner.exec(spawn, context)

                    var spawnIdentifier: String? = null
                    if (spawnResult.getDigest() != null) {
                        spawnIdentifier = spawnResult.getDigest().getHash()
                    }
                    actionExecutionContext
                        .getEventHandler()
                        .post(
                            SpawnExecutedEvent(
                                spawn,
                                actionExecutionContext.getInputMetadataProvider(),
                                actionExecutionContext.getActionFileSystem(),
                                actionExecutionContext.getFileOutErr(),
                                spawnResult,
                                startTime,
                                spawnIdentifier
                            )
                        )
                    if (cacheHandle.willStore()) {
                        cacheHandle.store(spawnResult)
                    }
                }
            }
        } catch (e: InterruptedIOException) {
            throw java.lang.InterruptedException(e.getMessage())
        } catch (e: IOException) {
            throw EnvironmentalExecException(
                e,
                FailureDetail.newBuilder()
                    .setMessage("Exec failed due to IOException")
                    .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.EXEC_IO_EXCEPTION))
                    .build()
            )
        } catch (e: SpawnExecException) {
            ex = e
            spawnResult = e.getSpawnResult()
            // Log the Spawn and re-throw.
        }

        val spawnLogContext: SpawnLogContext? = actionExecutionContext.getContext(SpawnLogContext::class.java)
        if (spawnLogContext != null) {
            try {
                spawnLogContext.logSpawn(
                    spawn,
                    actionExecutionContext.getInputMetadataProvider(),
                    java.util.function.Supplier {
                        context.getInputMapping(
                            PathFragment.EMPTY_FRAGMENT,  /* willAccessRepeatedly= */false
                        )
                    },
                    if (actionExecutionContext.getActionFileSystem() != null)
                        actionExecutionContext.getActionFileSystem()
                    else
                        actionExecutionContext.getExecRoot().getFileSystem(),
                    context.getTimeout(),
                    spawnResult
                )
            } catch (e: IOException) {
                throw EnvironmentalExecException(
                    e,
                    FailureDetail.newBuilder()
                        .setMessage("IOException while logging spawn")
                        .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.SPAWN_LOG_IO_EXCEPTION))
                        .build()
                )
            }
        }
        if (ex != null) {
            throw ex
        }

        if (spawnResult.status() !== Status.SUCCESS) {
            val cwd: String? = actionExecutionContext.getExecRoot().getPathString()
            val resultMessage: String = spawnResult.getFailureMessage()
            val message =
                if (!com.google.common.base.Strings.isNullOrEmpty(resultMessage))
                    resultMessage
                else
                    CommandFailureUtils.describeCommandFailure(
                        executionOptions.getVerboseFailures(), cwd, spawn
                    )
            throw SpawnExecException(message, spawnResult,  /* forciblyRunRemotely= */false)
        }
        return com.google.common.collect.ImmutableList.of<SpawnResult?>(spawnResult)
    }

    private inner class SpawnExecutionContextImpl(
        spawn: Spawn?,
        actionExecutionContext: ActionExecutionContext?,
        stopConcurrentSpawns: SandboxedSpawnStrategy.StopConcurrentSpawns?,
        timeout: java.time.Duration?
    ) : AbstractSpawnExecutionContext(spawn, actionExecutionContext) {
        private val stopConcurrentSpawns: SandboxedSpawnStrategy.StopConcurrentSpawns?
        private val timeout: java.time.Duration?

        val id: Int = execCount.incrementAndGet()

        // Memoize the input mapping so that prefetchInputs can reuse it instead of recomputing it.
        // TODO(ulfjack): Guard against client modification of this map.
        private var lazyInputMapping: SortedMap<PathFragment?, ActionInput?>? = null
        private var inputMappingBaseDirectory: PathFragment? = null

        private var digest: Digest? = null

        init {
            this.stopConcurrentSpawns = stopConcurrentSpawns
            this.timeout = timeout
        }

        override fun setDigest(digest: Digest?) {
            if (this.digest != null) {
                checkArgument(
                    this.digest.equals(digest),
                    "setDigest was called more than once with different digests: %s vs %s",
                    this.digest,
                    digest
                )
            }
            this.digest = com.google.common.base.Preconditions.checkNotNull<Digest?>(digest)
        }

        override fun getDigest(): Digest? {
            return digest
        }

        val inputMetadataProvider: InputMetadataProvider
            get() = actionExecutionContext.getInputMetadataProvider()

        @Throws(java.lang.InterruptedException::class)
        override fun lockOutputFiles(exitCode: Int, errorMessage: String?, outErr: FileOutErr?) {
            if (stopConcurrentSpawns != null) {
                stopConcurrentSpawns.stop(exitCode, errorMessage, outErr)
            }
        }

        override fun speculating(): Boolean {
            return stopConcurrentSpawns != null
        }

        override fun getTimeout(): java.time.Duration? {
            return timeout
        }

        override fun getInputMapping(
            baseDirectory: PathFragment, willAccessRepeatedly: Boolean
        ): SortedMap<PathFragment?, ActionInput?> {
            // Return previously computed copy if present.
            if (lazyInputMapping != null && inputMappingBaseDirectory == baseDirectory) {
                return lazyInputMapping
            }

            val inputMapping: SortedMap<PathFragment?, ActionInput?>
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("AbstractSpawnStrategy.getInputMapping")
                .use { c ->
                    inputMapping =
                        spawnInputExpander.getInputMapping(
                            spawn, actionExecutionContext.getInputMetadataProvider(), baseDirectory
                        )
                }
            // Don't cache the input mapping if it is unlikely that it is used again.
            // This reduces memory usage in the case where remote caching/execution is
            // used, and the expected cache hit rate is high.
            if (willAccessRepeatedly) {
                inputMappingBaseDirectory = baseDirectory
                lazyInputMapping = inputMapping
            }
            return inputMapping
        }

        override fun report(progress: ProgressStatus) {
            val action: ActionExecutionMetadata = spawn.getResourceOwner()
            if (action.getOwner() == null) {
                return
            }

            // TODO(djasper): This should not happen as per the contract of ActionExecutionMetadata, but
            // there are implementations that violate the contract. Remove when those are gone.
            if (action.getPrimaryOutput() == null) {
                return
            }

            val eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler? =
                actionExecutionContext.getEventHandler()
            progress.postTo(eventHandler, action)
        }
    }

    companion object {
        /**
         * Last unique identifier assigned to a spawn by this strategy.
         * 
         * 
         * These identifiers must be unique per strategy within the context of a Bazel server instance
         * to avoid cross-contamination across actions in case we perform asynchronous deletions.
         */
        private val execCount: AtomicInteger = AtomicInteger()
    }
}
