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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.actions.ExecException

/** A remote [SpawnCache] implementation.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe // If the RemoteActionCache implementation is thread-safe.
internal class RemoteSpawnCache(
    options: RemoteOptions,
    verboseFailures: Boolean,
    remoteExecutionService: RemoteExecutionService,
    digestUtil: DigestUtil
) : SpawnCache {
    private val options: RemoteOptions
    private val remoteExecutionService: RemoteExecutionService
    private val digestUtil: DigestUtil
    private val verboseFailures: Boolean
    private val inFlightExecutions: ConcurrentHashMap<ActionKey?, LocalExecution> =
        ConcurrentHashMap<ActionKey?, LocalExecution>()

    init {
        this.options = options
        this.verboseFailures = verboseFailures
        this.remoteExecutionService = remoteExecutionService
        this.digestUtil = digestUtil
    }

    @com.google.common.annotations.VisibleForTesting
    fun getRemoteExecutionService(): RemoteExecutionService {
        return remoteExecutionService
    }

    @get:com.google.common.annotations.VisibleForTesting
    val inFlightExecutionsSize: Int
        get() = inFlightExecutions.size()

    @Throws(java.lang.InterruptedException::class, IOException::class, ExecException::class)
    public override fun lookup(spawn: Spawn, context: SpawnExecutionContext): CacheHandle? {
        val shouldAcceptCachedResult: Boolean =
            remoteExecutionService.getReadCachePolicy(spawn).allowAnyCache()
        val shouldUploadLocalResults: Boolean =
            remoteExecutionService.getWriteCachePolicy(spawn).allowAnyCache()
        if (!shouldAcceptCachedResult && !shouldUploadLocalResults) {
            return SpawnCache.NO_RESULT_NO_STORE
        }

        val totalTime: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()

        val action: RemoteAction
        try {
            action =
                remoteExecutionService.buildRemoteAction(
                    spawn, context, BlobPolicy.DISCARD
                )
        } catch (e: RemoteExecutionCapabilitiesException) {
            if (options.getRemoteLocalFallbackForRemoteCache() && options.getRemoteLocalFallback()) {
                return SpawnCache.NO_RESULT_NO_STORE
            }
            throw com.google.devtools.build.lib.remote.util.Utils.createExecExceptionFromRemoteExecutionCapabilitiesException(
                e
            )
        }
        val spawnMetrics: SpawnMetrics.Builder =
            SpawnMetrics.Builder.forRemoteExec()
                .setInputBytes(action.getInputBytes())
                .setInputFiles(action.getInputFiles())

        context.setDigest(digestUtil.asSpawnLogProto(action.getActionKey()))

        val prof: com.google.devtools.build.lib.profiler.Profiler =
            com.google.devtools.build.lib.profiler.Profiler.instance()
        var thisExecution: LocalExecution? = null
        if (shouldAcceptCachedResult) {
            // With path mapping enabled, different Spawns in a single build can have the same ActionKey.
            // When their result isn't in the cache and two of them are scheduled concurrently, neither
            // will result in a cache hit before the other finishes and uploads its result, which results
            // in unnecessary work. To avoid this, we keep track of in-flight executions as long as their
            // results haven't been uploaded to the cache yet and deduplicate all of them against the
            // first one.
            var previousExecution: LocalExecution? = null
            try {
                thisExecution =
                    LocalExecution.Companion.createIfDeduplicatable(
                        action, java.lang.Runnable { inFlightExecutions.remove(action.getActionKey()) })
                if (shouldUploadLocalResults && thisExecution != null) {
                    val previousOrThisExecution: LocalExecution =
                        inFlightExecutions.merge(
                            action.getActionKey(),
                            thisExecution,
                            java.util.function.BiFunction { existingExecution: LocalExecution, thisExecutionArg: LocalExecution ->
                                if (existingExecution.registerForOutputReuse()) {
                                    return@merge existingExecution
                                } else {
                                    // The existing execution has completed and its results may have already
                                    // been modified by its action, so we can't deduplicate against it. Instead,
                                    // start a new in-flight execution.
                                    return@merge thisExecutionArg
                                }
                            })
                    if (previousOrThisExecution != thisExecution) {
                        // The current execution is not the first one to be registered for this action key, so
                        // we need to wait for the previous one to finish before we can reuse its result.
                        previousExecution = previousOrThisExecution
                        thisExecution = null
                    }
                }
                try {
                    val result: RemoteActionResult?
                    prof.profile(ProfilerTask.REMOTE_CACHE_CHECK, "check cache hit").use { c ->
                        result = remoteExecutionService.lookupCache(action)
                    }
                    // In case the remote cache returned a failed action (exit code != 0) or failed to create
                    // a mandatory output, we treat it as a cache miss.
                    if (result != null && result.getExitCode() == 0 && result.maybeGetMissingMandatoryOutput(action)
                            .isEmpty()
                    ) {
                        if (thisExecution != null) {
                            thisExecution.close()
                        }
                        val fetchTime: com.google.common.base.Stopwatch =
                            com.google.common.base.Stopwatch.createStarted()
                        val inMemoryOutput: InMemoryOutput?
                        prof.profile(ProfilerTask.REMOTE_DOWNLOAD, "download outputs").use { c ->
                            inMemoryOutput = remoteExecutionService.downloadOutputs(action, result)
                        }
                        fetchTime.stop()
                        totalTime.stop()
                        spawnMetrics
                            .setFetchTime(fetchTime.elapsed())
                            .setTotalTime(totalTime.elapsed())
                            .setNetworkTime(action.getNetworkTime().getDuration())
                        val spawnResult: SpawnResult? =
                            com.google.devtools.build.lib.remote.util.Utils.createSpawnResult(
                                digestUtil,
                                action.getActionKey(),
                                result.getExitCode(),  /* cacheHit= */
                                true,
                                result.cacheName(),
                                inMemoryOutput,
                                result.getExecutionMetadata().getExecutionStartTimestamp(),
                                result.getExecutionMetadata().getExecutionCompletedTimestamp(),
                                spawnMetrics.build(),
                                spawn.getMnemonic()
                            )
                        remoteExecutionService.maybeWriteParamFilesLocally(spawn)
                        return SpawnCache.success(spawnResult)
                    }
                } catch (e: CacheNotFoundException) {
                    // Intentionally left blank
                } catch (e: CredentialHelperException) {
                    if (thisExecution != null) {
                        thisExecution.close()
                    }
                    throw com.google.devtools.build.lib.remote.util.Utils.createExecExceptionForCredentialHelperException(
                        e
                    )
                } catch (e: RemoteExecutionCapabilitiesException) {
                    val shouldLocalFallback =
                        options.getRemoteLocalFallbackForRemoteCache() && options.getRemoteLocalFallback()
                    if (!shouldLocalFallback) {
                        if (thisExecution != null) {
                            thisExecution.close()
                        }
                        throw com.google.devtools.build.lib.remote.util.Utils.createExecExceptionFromRemoteExecutionCapabilitiesException(
                            e
                        )
                    }
                } catch (e: IOException) {
                    if (BulkTransferException.Companion.allCausedByCacheNotFoundException(e)) {
                        // Intentionally left blank
                    } else {
                        var errorMessage: String? =
                            com.google.devtools.build.lib.remote.util.Utils.grpcAwareErrorMessage(e, verboseFailures)
                        if (com.google.common.base.Strings.isNullOrEmpty(errorMessage)) {
                            errorMessage = e.getClass().getSimpleName()
                        }
                        errorMessage = "Remote Cache: " + errorMessage
                        remoteExecutionService.report(com.google.devtools.build.lib.events.Event.warn(errorMessage))
                    }
                }
                if (previousExecution != null) {
                    val fetchTime: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
                    val previousResult: SpawnResult?
                    prof.profile(ProfilerTask.REMOTE_DOWNLOAD, "reuse outputs").use { c ->
                        previousResult =
                            remoteExecutionService.waitForAndReuseOutputs(action, previousExecution)
                    }
                    if (previousResult != null) {
                        spawnMetrics
                            .setFetchTime(fetchTime.elapsed())
                            .setTotalTime(totalTime.elapsed())
                            .setNetworkTime(action.getNetworkTime().getDuration())
                        val buildMetrics: SpawnMetrics? = spawnMetrics.build()
                        remoteExecutionService.maybeWriteParamFilesLocally(spawn)
                        return SpawnCache.success(
                            object : DelegateSpawnResult(previousResult) {
                                val runnerName: String
                                    get() = "deduplicated"

                                val metrics: SpawnMetrics?
                                    get() = buildMetrics
                            })
                    }
                    // If we reach here, the previous execution was not successful (it encountered an
                    // exception or the spawn had an exit code != 0). Since it isn't possible to accurately
                    // recreate the failure without rerunning the action, we fall back to running the action
                    // locally. This means that we have introduced an unnecessary wait, but that can only
                    // happen in the case of a failing build with --keep_going.
                }
            } finally {
                if (previousExecution != null) {
                    previousExecution.unregister()
                }
            }
        }

        if (shouldUploadLocalResults) {
            val thisExecutionFinal: LocalExecution? = thisExecution
            return object : CacheHandle() {
                public override fun hasResult(): Boolean {
                    return false
                }

                val result: SpawnResult?
                    get() {
                        throw java.util.NoSuchElementException()
                    }

                public override fun willStore(): Boolean {
                    return true
                }

                @Throws(ExecException::class, java.lang.InterruptedException::class)
                public override fun store(result: SpawnResult) {
                    if (!remoteExecutionService.commitResultAndDecideWhetherToUpload(
                            result, thisExecutionFinal
                        )
                    ) {
                        return
                    }

                    // As soon as the result is in the cache, actions can get the result from it instead of
                    // from the first in-flight execution. Not keeping in-flight executions around
                    // indefinitely is important to avoid excessive memory pressure - Spawns can be very
                    // large.
                    remoteExecutionService.uploadOutputs(
                        action,
                        result,
                        if (thisExecutionFinal != null) thisExecutionFinal.delayClose() else java.lang.Runnable {},
                        options.getGuardAgainstConcurrentChanges()
                    )
                    if (thisExecutionFinal != null
                        && action.getSpawn().getResourceOwner().mayModifySpawnOutputsAfterExecution()
                    ) {
                        // In this case outputs have been uploaded synchronously and the callback above has run,
                        // so no new executions will be deduplicated against this one. We can safely await all
                        // existing executions finish the reuse.
                        // Note that while this call itself isn't interruptible, all operations it awaits are
                        // interruptible.
                        prof.profile(ProfilerTask.REMOTE_DOWNLOAD, "await output reuse").use { c ->
                            thisExecutionFinal.awaitAllOutputReuse()
                        }
                    }
                }

                public override fun close() {
                    if (thisExecutionFinal != null) {
                        thisExecutionFinal.close()
                    }
                }
            }
        } else {
            return SpawnCache.NO_RESULT_NO_STORE
        }
    }

    public override fun usefulInDynamicExecution(): Boolean {
        return false
    }
}
