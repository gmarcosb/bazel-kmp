// Copyright 2023 The Bazel Authors. All rights reserved.
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

/** A [LeaseExtension] implementation that uses REAPI.  */
class RemoteLeaseExtension(
    memoizingEvaluator: MemoizingEvaluator,
    actionCache: ActionCache?,
    buildRequestId: String?,
    commandId: String?,
    combinedCache: CombinedCache,
    remoteCacheTtl: java.time.Duration
) : LeaseExtension {
    private val scheduledExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("lease-extension-%d").build()
    )

    private val lock: ReentrantLock = ReentrantLock()

    private val memoizingEvaluator: MemoizingEvaluator
    private val actionCache: ActionCache?
    private val combinedCache: CombinedCache
    private val remoteCacheTtl: java.time.Duration
    private val context: RemoteActionExecutionContext

    init {
        this.memoizingEvaluator = memoizingEvaluator
        this.actionCache = actionCache
        this.combinedCache = combinedCache
        this.remoteCacheTtl = remoteCacheTtl
        val requestMetadata: RequestMetadata? =
            TracingMetadataUtils.buildMetadata(buildRequestId, commandId, "lease-extension", null)
        this.context = RemoteActionExecutionContext.Companion.create(requestMetadata)
    }

    override fun start() {
        // Immediately extend leases for outputs that are already known to skyframe. For clean build,
        // the set of outputs is empty. For incremental build, it contains outputs that were not
        // invalidated after skyframe's dirtiness check.
        val unused: java.util.concurrent.ScheduledFuture<*>? =
            scheduledExecutor.schedule(java.lang.Runnable { this.extendLeases() }, 0, TimeUnit.MILLISECONDS)
    }

    private fun extendLeases() {
        // Acquire the lock to prevent multiple doExtendLeases() running.
        lock.lock()
        try {
            Profiler.instance().profile("doExtendLeases").use { silentCloseable ->
                doExtendLeases()
            }
        } finally {
            lock.unlock()
        }
    }

    private fun doExtendLeases() {
        val valuesMap: MutableMap<SkyKey, SkyValue?> = memoizingEvaluator.getValues()
        // We will extend leases for all known outputs so the earliest time when one output could be
        // expired is (now + ttl).
        val earliestExpiration: Instant = Instant.now().plus(remoteCacheTtl)

        try {
            for (entry in valuesMap.entrySet()) {
                val key: SkyKey = entry.getKey()
                val value: SkyValue? = entry.getValue()
                if (value != null && ACTION_FILTER.test(key)) {
                    val action: Action = getActionFromSkyKey(key)
                    val actionExecutionValue: ActionExecutionValue = value as ActionExecutionValue
                    val remoteFiles: com.google.common.collect.ImmutableList<MutableMap.MutableEntry<out Artifact?, FileArtifactValue>> =
                        collectRemoteFiles(actionExecutionValue)
                    if (!remoteFiles.isEmpty()) {
                        // Lease extensions are performed on action basis, not by collecting all outputs and
                        // issue one giant `FindMissingBlobs` call to avoid increasing memory footprint. Since
                        // this happens in the background, increased network calls are acceptable.
                        Profiler.instance().profile(action.describe()).use { silentCloseable1 ->
                            extendLeaseForAction(action, remoteFiles, earliestExpiration)
                        }
                    }
                }
            }
        } catch (e: java.lang.InterruptedException) {
            java.lang.Thread.currentThread().interrupt()
            return
        } catch (e: Throwable) {
            logger.atWarning().withCause(e).log("Failed to extend the lease")
        }

        // Only extend the leases again when one of the outputs is about to expire.
        val now: Instant = Instant.now()
        val delay: java.time.Duration
        if (earliestExpiration.isAfter(now)) {
            delay = java.time.Duration.between(now, earliestExpiration)
        } else {
            delay = java.time.Duration.ZERO
        }
        val unused: java.util.concurrent.ScheduledFuture<*>? = scheduledExecutor.schedule(
            java.lang.Runnable { this.extendLeases() },
            delay.toMillis(),
            TimeUnit.MILLISECONDS
        )
    }

    private fun collectRemoteFiles(
        actionExecutionValue: ActionExecutionValue
    ): com.google.common.collect.ImmutableList<MutableMap.MutableEntry<out Artifact?, FileArtifactValue>> {
        val result: com.google.common.collect.ImmutableList.Builder<MutableMap.MutableEntry<out Artifact?, FileArtifactValue?>?> =
            com.google.common.collect.ImmutableList.builder<MutableMap.MutableEntry<out Artifact?, FileArtifactValue?>?>()
        for (entry in actionExecutionValue.getAllFileValues().entrySet()) {
            if (isRemoteMetadataWithTtl(entry.getValue())) {
                result.add(entry)
            }
        }

        for (treeMetadata in actionExecutionValue.getAllTreeArtifactValues().values()) {
            for (entry in treeMetadata.getChildValues().entrySet()) {
                if (isRemoteMetadataWithTtl(entry.getValue())) {
                    result.add(entry)
                }
            }
        }

        return result.build()
    }

    /** Returns `true` iff the outputs of the action  */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun extendLeaseForAction(
        action: Action?,
        remoteFiles: com.google.common.collect.ImmutableList<MutableMap.MutableEntry<out Artifact?, FileArtifactValue>>,
        expirationTime: Instant?
    ) {
        val missingDigests: com.google.common.collect.ImmutableSet<Digest?>
        Profiler.instance().profile("findMissingDigests").use { silentCloseable ->
            // We assume remote server will extend the leases for all referenced blobs by a
            // FindMissingBlobs call.
            (.also {
                missingDigests = it
            }
            < ImmutableSet < Digest shr com.google.devtools.build.lib.remote.util.Utils.getFromFuture<com.google.common.collect.ImmutableSet<Digest?>?>(
            combinedCache.findMissingDigests(
                context,
                com.google.common.collect.Iterables.transform<MutableMap.MutableEntry<out Artifact?, FileArtifactValue?>?, Digest?>(
                    remoteFiles,
                    com.google.common.base.Function { remoteFile: MutableMap.MutableEntry<out Artifact?, FileArtifactValue?>? ->
                        buildDigest(remoteFile.getValue())
                    })
            )
        ))
        }
        val token = getActionCacheToken(action)
        for (remoteFile in remoteFiles) {
            val artifact: Artifact? = remoteFile.getKey()
            val metadata: FileArtifactValue = remoteFile.getValue()
            // Only extend the lease for the remote output if it is still alive remotely.
            if (!missingDigests.contains(buildDigest(metadata))) {
                metadata.setExpirationTime(expirationTime)
                if (token != null) {
                    if (artifact is TreeFileArtifact) {
                        token.extendOutputTreeFile(artifact, expirationTime)
                    } else {
                        token.extendOutputFile(artifact, expirationTime)
                    }
                }
            }
        }

        if (actionCache != null && token != null && token.dirty) {
            // Only update the action cache entry if the token was updated because it usually involves
            // serialization.
            actionCache.put(token.key, token.entry)
        }
    }

    override fun stop() {
        if (ExecutorUtil.uninterruptibleShutdownNow(scheduledExecutor)) {
            java.lang.Thread.currentThread().interrupt()
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getActionFromSkyKey(key: SkyKey): Action {
        val actionLookupData: ActionLookupData = key.argument() as ActionLookupData
        val actionLookupValue: ActionLookupValue =
            com.google.common.base.Preconditions.checkNotNull<SkyValue?>(
                memoizingEvaluator.getExistingValue(actionLookupData.getActionLookupKey())
            ) as ActionLookupValue
        return actionLookupValue.getAction(actionLookupData.getActionIndex())
    }

    private fun getActionCacheToken(action: Action?): ActionCacheToken? {
        if (actionCache != null) {
            val actionCacheEntryWithKey: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                ActionCacheUtils.getCacheEntryWithKey(actionCache, action)
            if (actionCacheEntryWithKey != null) {
                return ActionCacheToken(
                    actionCacheEntryWithKey.getKey(), actionCacheEntryWithKey.getValue()
                )
            }
        }

        return null
    }

    private class ActionCacheToken(val key: String?, entry: Entry) {
        val entry: ActionCache.Entry
        private var dirty = false

        init {
            this.entry = entry
        }

        fun extendOutputFile(artifact: Artifact?, expirationTime: Instant?) {
            val metadata: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                entry.getOutputFile(artifact)
            if (metadata != null) {
                metadata.setExpirationTime(expirationTime)
                dirty = true
            }
        }

        fun extendOutputTreeFile(treeFile: TreeFileArtifact, expirationTime: Instant?) {
            val treeMetadata: SerializableTreeArtifactValue? = entry.getOutputTree(treeFile.getParent())
            if (treeMetadata != null) {
                val metadata: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    treeMetadata.childValues().get(treeFile.getTreeRelativePathString())
                if (metadata != null) {
                    metadata.setExpirationTime(expirationTime)
                    dirty = true
                }
            }
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
        private val ACTION_FILTER: java.util.function.Predicate<SkyKey?> =
            SkyFunctionName.functionIs(SkyFunctions.ACTION_EXECUTION)

        private fun isRemoteMetadataWithTtl(metadata: FileArtifactValue): Boolean {
            return metadata.isRemote() && metadata.getExpirationTime() != null
        }

        private fun buildDigest(metadata: FileArtifactValue): Digest? {
            return DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize())
        }
    }
}
