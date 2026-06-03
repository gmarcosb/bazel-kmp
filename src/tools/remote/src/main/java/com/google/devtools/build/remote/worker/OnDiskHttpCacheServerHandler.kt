// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.remote.worker

import build.bazel.remote.execution.v2.Digest

/** A simple HTTP REST disk cache used during test.  */
@io.netty.channel.ChannelHandler.Sharable
class OnDiskHttpCacheServerHandler(cache: OnDiskBlobStoreCache) : AbstractHttpCacheServerHandler() {
    private val cache: OnDiskBlobStoreCache

    init {
        this.cache = cache
    }

    @Throws(IOException::class)
    override fun readFromCache(uri: String): ByteArray? {
        val diskCache: DiskCacheClient = cache.getDiskCacheClient()
        val path: Path?
        if (uri.startsWith("/ac/")) {
            path = diskCache.toPath(uri.substring("/ac/".length), Store.AC)
        } else if (uri.startsWith("/cas/")) {
            path = diskCache.toPath(uri.substring("/cas/".length), Store.CAS)
        } else {
            throw IOException("Invalid uri: " + uri)
        }

        try {
            java.io.ByteArrayOutputStream().use { out ->
                path.getInputStream().use { `in` ->
                    com.google.common.io.ByteStreams.copy(`in`, out)
                    return out.toByteArray()
                }
            }
        } catch (e: FileNotFoundException) {
            return null
        }
    }

    @Throws(IOException::class)
    override fun writeToCache(uri: String, content: ByteArray) {
        val diskCache: DiskCacheClient = cache.getDiskCacheClient()
        val digest: Digest
        val store: Store
        if (uri.startsWith("/ac/")) {
            digest = DigestUtil.buildDigest(uri.substring(4), content.size)
            store = Store.AC
        } else if (uri.startsWith("/cas/")) {
            digest = DigestUtil.buildDigest(uri.substring(5), content.size)
            store = Store.CAS
        } else {
            throw IOException("Invalid uri: " + uri)
        }

        diskCache.saveFile(digest, store, ByteArrayInputStream(content))
    }
}
