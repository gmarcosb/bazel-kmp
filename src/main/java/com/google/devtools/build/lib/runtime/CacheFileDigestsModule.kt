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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.buildtool.BuildRequest

/** Enables the caching of file digests in [DigestUtils].  */
class CacheFileDigestsModule : BlazeModule() {
    /** Stats gathered at the beginning of a command, to compute deltas on completion.  */
    private var stats: com.github.benmanes.caffeine.cache.stats.CacheStats? = null

    /**
     * Last known size of the cache. Changes to this value cause the cache to be reinitialized. null
     * if we don't know anything about the last value yet (i.e. before any command has been run).
     */
    private var lastKnownCacheSize: Long? = null

    public override fun executorInit(env: CommandEnvironment?, request: BuildRequest, builder: ExecutorBuilder?) {
        val options: ExecutionOptions = request.getOptions(ExecutionOptions::class.java)
        if (lastKnownCacheSize == null
            || options.cacheSizeForComputedFileDigests !== lastKnownCacheSize
        ) {
            logger.atInfo().log(
                "Reconfiguring cache with size=%d", options.cacheSizeForComputedFileDigests
            )
            com.google.devtools.build.lib.vfs.DigestUtils.configureCache(options.cacheSizeForComputedFileDigests)
            lastKnownCacheSize = options.cacheSizeForComputedFileDigests
        }

        if (options.cacheSizeForComputedFileDigests === 0) {
            stats = null
            logger.atInfo().log("Disabled cache")
        } else {
            stats = com.google.devtools.build.lib.vfs.DigestUtils.getCacheStats()
            logStats("Accumulated cache stats before command", stats)
        }
    }

    public override fun commandComplete() {
        if (stats != null) {
            val newStats: com.github.benmanes.caffeine.cache.stats.CacheStats? =
                com.google.devtools.build.lib.vfs.DigestUtils.getCacheStats()
            com.google.common.base.Preconditions.checkNotNull<com.github.benmanes.caffeine.cache.stats.CacheStats?>(
                newStats,
                "The cache is enabled so we must get some stats back"
            )
            logStats("Accumulated cache stats after command", newStats)
            logStats("Cache stats for finished command", newStats.minus(stats))
            stats = null // Silence stats until next command that uses the executor.
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * Adds a line to the log with cache statistics.
         * 
         * @param message message to prefix to the written line
         * @param stats the cache statistics to be logged
         */
        private fun logStats(message: String?, stats: com.github.benmanes.caffeine.cache.stats.CacheStats) {
            logger.atInfo().log(
                "%s: hit count=%d, miss count=%d, hit rate=%g, eviction count=%d",
                message, stats.hitCount(), stats.missCount(), stats.hitRate(), stats.evictionCount()
            )
        }
    }
}
