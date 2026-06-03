// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.util

import build.bazel.remote.execution.v2.ActionCacheUpdateCapabilities

/** A [RemoteCacheClient] that stores its contents in memory.  */
class InMemoryCacheClient : RemoteCacheClient {
    private val executorService: com.google.common.util.concurrent.ListeningExecutorService =
        com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(100))
    private val downloadFailures: ConcurrentMap<Digest?, java.lang.Exception?> =
        ConcurrentHashMap<Digest?, java.lang.Exception?>()
    private val ac: ConcurrentMap<ActionKey?, ActionResult?> = ConcurrentHashMap<ActionKey?, ActionResult?>()
    private val cas: ConcurrentMap<Digest?, ByteArray?>

    private val numSuccess: AtomicInteger = AtomicInteger()
    private val numFailures: AtomicInteger = AtomicInteger()
    private val numFindMissingDigests: ConcurrentMap<Digest?, AtomicInteger?> =
        ConcurrentHashMap<Digest?, AtomicInteger?>()

    constructor(casEntries: MutableMap<Digest?, ByteArray?>) {
        this.cas = ConcurrentHashMap<Digest?, ByteArray?>()
        for (entry in casEntries.entrySet()) {
            cas.put(entry.getKey(), entry.getValue())
        }
    }

    constructor() {
        this.cas = ConcurrentHashMap<Digest?, ByteArray?>()
    }

    fun addDownloadFailure(digest: Digest?, e: java.lang.Exception?) {
        downloadFailures.put(digest, e)
    }

    val numSuccessfulDownloads: Int
        get() = numSuccess.get()

    val numFailedDownloads: Int
        get() = numFailures.get()

    fun getNumFindMissingDigests(): MutableMap<Digest?, Int?> {
        return numFindMissingDigests.entrySet().stream()
            .map<AbstractMap.SimpleEntry<Any?, Int?>?>(java.util.function.Function { entry: MutableMap.MutableEntry<Digest?, AtomicInteger?>? ->
                AbstractMap.SimpleEntry<Any?, Int?>(
                    entry.getKey(),
                    entry.getValue().get()
                )
            })
            .collect(
                Collectors.toMap(
                    java.util.function.Function { obj: Any? -> obj.getKey() },
                    java.util.function.Function { obj: Any? -> obj.getValue() })
            )
    }

    public override fun downloadBlob(
        context: RemoteActionExecutionContext?, digest: Digest?, out: java.io.OutputStream
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        val failure: java.lang.Exception? = downloadFailures.get(digest)
        if (failure != null) {
            numFailures.incrementAndGet()
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(failure)
        }

        val data: ByteArray? = cas.get(digest)
        if (data == null) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<V?>(CacheNotFoundException(digest))
        }

        try {
            out.write(data)
            out.flush()
        } catch (e: IOException) {
            numFailures.incrementAndGet()
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(e)
        }
        numSuccess.incrementAndGet()
        return com.google.common.util.concurrent.Futures.immediateFuture<java.lang.Void?>(null)
    }

    val serverCapabilities: ServerCapabilities
        get() {
            val cacheCapabilities: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                CacheCapabilities.newBuilder()
                    .setActionCacheUpdateCapabilities(
                        ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build()
                    )
                    .setSymlinkAbsolutePathStrategy(SymlinkAbsolutePathStrategy.Value.ALLOWED)
                    .build()
            return ServerCapabilities.newBuilder().setCacheCapabilities(cacheCapabilities).build()
        }

    val authority: com.google.common.util.concurrent.ListenableFuture<String?>
        get() = com.google.common.util.concurrent.Futures.immediateFuture<String?>("")

    public override fun downloadActionResult(
        context: RemoteActionExecutionContext?,
        actionKey: ActionKey?,
        inlineOutErr: Boolean,
        inlineOutputFiles: MutableSet<String?>?
    ): com.google.common.util.concurrent.ListenableFuture<ActionResult?> {
        val actionResult: ActionResult? = ac.get(actionKey)
        return com.google.common.util.concurrent.Futures.immediateFuture<ActionResult?>(actionResult)
    }

    public override fun uploadActionResult(
        context: RemoteActionExecutionContext?, actionKey: ActionKey?, actionResult: ActionResult?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        ac.put(actionKey, actionResult)
        return com.google.common.util.concurrent.Futures.immediateFuture<java.lang.Void?>(null)
    }

    public override fun uploadBlobImpl(
        context: RemoteActionExecutionContext?, digest: Digest?, blob: Blob
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        try {
            cas.put(digest, blob.get().readAllBytes())
        } catch (e: IOException) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(e)
        }
        return com.google.common.util.concurrent.Futures.immediateFuture<java.lang.Void?>(null)
    }

    public override fun findMissingDigests(
        context: RemoteActionExecutionContext?, digests: Iterable<Digest?>
    ): com.google.common.util.concurrent.ListenableFuture<com.google.common.collect.ImmutableSet<Digest?>?> {
        return executorService.submit<com.google.common.collect.ImmutableSet<Digest?>?>(
            java.util.concurrent.Callable {
                val missingBuilder: com.google.common.collect.ImmutableSet.Builder<Digest?> =
                    com.google.common.collect.ImmutableSet.builder<Digest?>()
                for (digest in digests) {
                    numFindMissingDigests
                        .computeIfAbsent(digest, java.util.function.Function { key: Digest? -> AtomicInteger(0) })
                        .incrementAndGet()
                    if (!cas.containsKey(digest)) {
                        missingBuilder.add(digest)
                    }
                }
                missingBuilder.build()
            })
    }

    public override fun close() {
        cas.clear()
        ac.clear()
    }
}
