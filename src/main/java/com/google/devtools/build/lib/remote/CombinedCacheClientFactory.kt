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

import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions

/** A factory class for providing a [CombinedCacheClient].  */
object CombinedCacheClientFactory {
    @Throws(IOException::class)
    fun create(
        options: RemoteOptions,
        diskCachePath: PathFragment?,
        creds: com.google.auth.Credentials?,
        authAndTlsOptions: AuthAndTLSOptions?,
        workingDirectory: com.google.devtools.build.lib.vfs.Path?,
        digestUtil: DigestUtil,
        retrier: RemoteRetrier?
    ): CombinedCacheClient {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(
            workingDirectory,
            "workingDirectory"
        )
        var httpCacheClient: RemoteCacheClient? = null
        var diskCacheClient: DiskCacheClient? = null
        if (isHttpCache(options)) {
            httpCacheClient = createHttp(options, creds, authAndTlsOptions, digestUtil, retrier)
        }
        if (diskCachePath != null) {
            diskCacheClient = createDiskCache(workingDirectory, diskCachePath, digestUtil)
        }
        require(!(httpCacheClient == null && diskCacheClient == null)) {
            ("Unrecognized RemoteOptions configuration: remote Http cache URL and/or local disk cache"
                    + " options expected.")
        }
        return CombinedCacheClient(httpCacheClient, diskCacheClient)
    }

    fun isRemoteCacheOptions(options: RemoteOptions): Boolean {
        return isHttpCache(options) || options.isDiskCacheEnabled()
    }

    private fun createHttp(
        options: RemoteOptions,
        creds: com.google.auth.Credentials?,
        authAndTlsOptions: AuthAndTLSOptions?,
        digestUtil: DigestUtil?,
        retrier: RemoteRetrier?
    ): RemoteCacheClient {
        com.google.common.base.Preconditions.checkNotNull<String?>(options.getRemoteCache(), "remoteCache")

        try {
            val uri: java.net.URI = java.net.URI.create(options.getRemoteCache())
            com.google.common.base.Preconditions.checkArgument(
                com.google.common.base.Ascii.toLowerCase(uri.getScheme()).startsWith("http"),
                "remoteCache should start with http"
            )

            if (options.getRemoteProxy() != null) {
                if (options.getRemoteProxy().startsWith("unix:")) {
                    return HttpCacheClient.Companion.create(
                        io.netty.channel.unix.DomainSocketAddress(options.getRemoteProxy().replaceFirst("^unix:", "")),
                        uri,
                        java.lang.Math.toIntExact(options.getRemoteTimeout().toSeconds()),
                        options.getRemoteMaxConnections(),
                        options.getRemoteVerifyDownloads(),
                        effectiveHeaders(options),
                        digestUtil,
                        retrier,
                        creds,
                        authAndTlsOptions
                    )
                } else {
                    throw java.lang.Exception("Remote cache proxy unsupported: " + options.getRemoteProxy())
                }
            } else {
                return HttpCacheClient.Companion.create(
                    uri,
                    java.lang.Math.toIntExact(options.getRemoteTimeout().toSeconds()),
                    options.getRemoteMaxConnections(),
                    options.getRemoteVerifyDownloads(),
                    effectiveHeaders(options),
                    digestUtil,
                    retrier,
                    creds,
                    authAndTlsOptions
                )
            }
        } catch (e: java.lang.Exception) {
            throw java.lang.RuntimeException(e)
        }
    }

    @Throws(IOException::class)
    fun createDiskCache(
        workingDirectory: com.google.devtools.build.lib.vfs.Path, diskCachePath: PathFragment?, digestUtil: DigestUtil
    ): DiskCacheClient {
        val cacheDir: com.google.devtools.build.lib.vfs.Path =
            workingDirectory.getRelative(com.google.common.base.Preconditions.checkNotNull<PathFragment?>(diskCachePath))
        return DiskCacheClient(cacheDir, digestUtil)
    }

    fun isHttpCache(options: RemoteOptions): Boolean {
        return options.getRemoteCache() != null
                && (com.google.common.base.Ascii.toLowerCase(options.getRemoteCache()).startsWith("http://")
                || com.google.common.base.Ascii.toLowerCase(options.getRemoteCache()).startsWith("https://"))
    }

    fun effectiveHeaders(options: RemoteOptions): com.google.common.collect.ImmutableList<MutableMap.MutableEntry<String?, String?>?> {
        return com.google.common.collect.ImmutableList.builder<MutableMap.MutableEntry<String?, String?>?>()
            .addAll(options.getRemoteHeaders())
            .addAll(options.getRemoteCacheHeaders())
            .build()
    }

    /**
     * A record holding a [DiskCacheClient] and [RemoteCacheClient] pair. Either may be
     * absent.
     */
    class CombinedCacheClient(remoteCacheClient: RemoteCacheClient?, diskCacheClient: DiskCacheClient?) {
        val remoteCacheClient: RemoteCacheClient?
        val diskCacheClient: DiskCacheClient?

        init {
            this.remoteCacheClient = remoteCacheClient
            this.diskCacheClient = diskCacheClient
        }
    }
}
