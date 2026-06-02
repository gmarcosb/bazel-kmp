// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.common

import build.bazel.remote.execution.v2.RequestMetadata

/**
 * A context providing remote execution related information for executing a [RemoteAction].
 * 
 * 
 * Terminology note: "action" is used here in the remote execution protocol sense, which is
 * equivalent to a Bazel "spawn" (a Bazel "action" being a higher-level concept).
 */
class RemoteActionExecutionContext private constructor(
    spawn: Spawn?,
    spawnExecutionContext: SpawnExecutionContext?,
    requestMetadata: RequestMetadata?,
    networkTime: NetworkTime?,
    writeCachePolicy: CachePolicy?,
    readCachePolicy: CachePolicy?
) {
    /** Determines whether to read/write remote cache, disk cache or both.  */
    enum class CachePolicy {
        NO_CACHE,
        REMOTE_CACHE_ONLY,
        DISK_CACHE_ONLY,
        ANY_CACHE;

        fun allowAnyCache(): Boolean {
            return this != CachePolicy.NO_CACHE
        }

        fun allowRemoteCache(): Boolean {
            return this == CachePolicy.REMOTE_CACHE_ONLY || this == CachePolicy.ANY_CACHE
        }

        fun allowDiskCache(): Boolean {
            return this == CachePolicy.DISK_CACHE_ONLY || this == CachePolicy.ANY_CACHE
        }

        fun addRemoteCache(): CachePolicy {
            if (this == CachePolicy.DISK_CACHE_ONLY || this == CachePolicy.ANY_CACHE) {
                return CachePolicy.ANY_CACHE
            }

            return CachePolicy.REMOTE_CACHE_ONLY
        }

        companion object {
            fun create(allowRemoteCache: Boolean, allowDiskCache: Boolean): CachePolicy {
                if (allowRemoteCache && allowDiskCache) {
                    return CachePolicy.ANY_CACHE
                } else if (allowRemoteCache) {
                    return CachePolicy.REMOTE_CACHE_ONLY
                } else if (allowDiskCache) {
                    return CachePolicy.DISK_CACHE_ONLY
                } else {
                    return CachePolicy.NO_CACHE
                }
            }
        }
    }

    private val spawn: Spawn?
    private val spawnExecutionContext: SpawnExecutionContext?
    private val requestMetadata: RequestMetadata?
    private val networkTime: NetworkTime?
    val writeCachePolicy: CachePolicy?
    val readCachePolicy: CachePolicy?

    private constructor(
        spawn: Spawn?,
        spawnExecutionContext: SpawnRunner.SpawnExecutionContext?,
        requestMetadata: RequestMetadata?,
        networkTime: NetworkTime?
    ) : this(
        spawn,
        spawnExecutionContext,
        requestMetadata,
        networkTime,
        CachePolicy.ANY_CACHE,
        CachePolicy.ANY_CACHE
    )

    init {
        this.spawn = spawn
        this.spawnExecutionContext = spawnExecutionContext
        this.requestMetadata = requestMetadata
        this.networkTime = networkTime
        this.writeCachePolicy = writeCachePolicy
        this.readCachePolicy = readCachePolicy
    }

    fun withWriteCachePolicy(writeCachePolicy: CachePolicy?): RemoteActionExecutionContext {
        return RemoteActionExecutionContext(
            spawn,
            spawnExecutionContext,
            requestMetadata,
            networkTime,
            writeCachePolicy,
            readCachePolicy
        )
    }

    fun withReadCachePolicy(readCachePolicy: CachePolicy?): RemoteActionExecutionContext {
        return RemoteActionExecutionContext(
            spawn,
            spawnExecutionContext,
            requestMetadata,
            networkTime,
            writeCachePolicy,
            readCachePolicy
        )
    }

    /**
     * Returns the [Spawn] of the [RemoteAction] being executed, or `null` if it has
     * no associated [Spawn].
     */
    fun getSpawn(): Spawn? {
        return spawn
    }

    /**
     * Returns the [SpawnExecutionContext] of the [RemoteAction] being executed, or `null` if it has no associated [Spawn].
     */
    fun getSpawnExecutionContext(): SpawnExecutionContext? {
        return spawnExecutionContext
    }

    /** Returns the [RequestMetadata] for the action being executed.  */
    fun getRequestMetadata(): RequestMetadata? {
        return requestMetadata
    }

    /**
     * Returns the [NetworkTime] instance used to measure the network time during the action
     * execution.
     */
    fun getNetworkTime(): NetworkTime? {
        return networkTime
    }

    val spawnOwner: ActionExecutionMetadata?
        get() {
            val spawn: Spawn? = getSpawn()
            if (spawn == null) {
                return null
            }

            return spawn.getResourceOwner()
        }

    companion object {
        /** Creates a [RemoteActionExecutionContext] with given [RequestMetadata].  */
        fun create(metadata: RequestMetadata?): RemoteActionExecutionContext {
            return RemoteActionExecutionContext( /* spawn= */
                null,  /* spawnExecutionContext= */null, metadata, NetworkTime()
            )
        }

        /**
         * Creates a [RemoteActionExecutionContext] with given [Spawn] and [ ].
         */
        fun create(
            spawn: Spawn?, spawnExecutionContext: SpawnExecutionContext?, metadata: RequestMetadata?
        ): RemoteActionExecutionContext {
            return RemoteActionExecutionContext(
                spawn, spawnExecutionContext, metadata, NetworkTime()
            )
        }

        fun create(
            spawn: Spawn?,
            spawnExecutionContext: SpawnExecutionContext?,
            requestMetadata: RequestMetadata?,
            writeCachePolicy: CachePolicy?,
            readCachePolicy: CachePolicy?
        ): RemoteActionExecutionContext {
            return RemoteActionExecutionContext(
                spawn,
                spawnExecutionContext,
                requestMetadata,
                NetworkTime(),
                writeCachePolicy,
                readCachePolicy
            )
        }
    }
}
