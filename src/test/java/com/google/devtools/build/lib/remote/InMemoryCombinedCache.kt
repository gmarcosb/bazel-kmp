// Copyright 2021 The Bazel Authors. All rights reserved.
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

internal class InMemoryCombinedCache : RemoteExecutionCache {
    constructor(casEntries: MutableMap<Digest?, ByteArray?>, digestUtil: DigestUtil?) : super(
        InMemoryCacheClient(casEntries),  /* diskCacheClient= */
        null,  /* symlinkTemplate= */
        null,
        digestUtil,  /* chunkingEnabled= */
        false
    )

    constructor(casEntries: MutableMap<Digest?, ByteArray?>, digestUtil: DigestUtil?, symlinkTemplate: String?) : super(
        InMemoryCacheClient(casEntries),  /* diskCacheClient= */
        null,
        symlinkTemplate,
        digestUtil,  /* chunkingEnabled= */
        false
    )

    constructor(digestUtil: DigestUtil?) : super(
        InMemoryCacheClient(),  /* diskCacheClient= */
        null,  /* symlinkTemplate= */
        null,
        digestUtil,  /* chunkingEnabled= */
        false
    )

    constructor(remoteCacheClient: RemoteCacheClient?, digestUtil: DigestUtil?) : super(
        remoteCacheClient,  /* diskCacheClient= */
        null,  /* symlinkTemplate= */
        null,
        digestUtil,  /* chunkingEnabled= */
        false
    )

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun addContents(context: RemoteActionExecutionContext?, txt: String): Digest? {
        return addContents(context, txt.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun addContents(context: RemoteActionExecutionContext?, bytes: ByteArray): Digest? {
        val digest: Digest? = digestUtil.compute(bytes)
        Utils.getFromFuture(
            remoteCacheClient.uploadBlob(
                context, digest, ByteString.copyFrom(bytes),  /* force= */false
            )
        )
        return digest
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun addContents(context: RemoteActionExecutionContext?, m: Message): Digest? {
        return addContents(context, m.toByteArray())
    }

    fun addException(txt: String, e: java.lang.Exception?): Digest? {
        val digest: Digest? = digestUtil.compute(txt.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        (remoteCacheClient as InMemoryCacheClient).addDownloadFailure(digest, e)
        return digest
    }

    fun addException(m: Message?, e: java.lang.Exception?): Digest? {
        val digest: Digest? = digestUtil.compute(m)
        (remoteCacheClient as InMemoryCacheClient).addDownloadFailure(digest, e)
        return digest
    }

    val numSuccessfulDownloads: Int
        get() = (remoteCacheClient as InMemoryCacheClient).getNumSuccessfulDownloads()

    val numFailedDownloads: Int
        get() = (remoteCacheClient as InMemoryCacheClient).getNumFailedDownloads()

    val numFindMissingDigests: MutableMap<Digest, Int?>?
        get() = (remoteCacheClient as InMemoryCacheClient).getNumFindMissingDigests()
}
