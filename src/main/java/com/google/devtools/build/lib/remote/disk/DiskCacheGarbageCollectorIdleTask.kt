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
package com.google.devtools.build.lib.remote.disk

import com.google.common.annotations.VisibleForTesting
import com.google.common.flogger.GoogleLogger
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.google.devtools.build.lib.remote.disk.DiskCacheGarbageCollector.CollectionPolicy
import com.google.devtools.build.lib.remote.options.RemoteOptions
import com.google.devtools.build.lib.server.IdleTask
import com.google.devtools.build.lib.server.IdleTaskException
import com.google.devtools.build.lib.vfs.Path
import com.google.devtools.build.lib.vfs.PathFragment
import java.io.IOException
import java.time.Duration
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** An [IdleTask] to run a [DiskCacheGarbageCollector].  */
class DiskCacheGarbageCollectorIdleTask private constructor(
    private val delay: Duration?,
    @get:VisibleForTesting val garbageCollector: DiskCacheGarbageCollector
) : IdleTask {
    override fun displayName(): String {
        return "Disk cache garbage collector"
    }

    override fun delay(): Duration? {
        return delay
    }

    @Throws(IdleTaskException::class, InterruptedException::class)
    override fun run() {
        try {
            val stats = garbageCollector.run()
            logger.atInfo().log("%s", stats.displayString())
        } catch (e: IOException) {
            throw IdleTaskException(e)
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private val executorService: ExecutorService = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            ThreadFactoryBuilder().setNameFormat("disk-cache-gc-%d").build()
        )

        /**
         * Creates a new [DiskCacheGarbageCollectorIdleTask] according to the options.
         * 
         * @param remoteOptions the remote options
         * @param diskCachePath the resolved disk cache path, or `null` if disabled
         * @param workingDirectory the working directory
         * @return the idle task, or null if garbage collection is disabled
         */
        fun create(
            remoteOptions: RemoteOptions, diskCachePath: PathFragment?, workingDirectory: Path
        ): DiskCacheGarbageCollectorIdleTask? {
            if (diskCachePath == null || diskCachePath.isEmpty()) {
                return null
            }
            var maxSizeBytes: Optional<Long?> = Optional.empty<Long?>()
            if (remoteOptions.getDiskCacheGcMaxSize() > 0) {
                maxSizeBytes = Optional.of<Long?>(remoteOptions.getDiskCacheGcMaxSize())
            }
            var maxAge: Optional<Duration?> = Optional.empty<Duration?>()
            if (!remoteOptions.getDiskCacheGcMaxAge().isZero()) {
                maxAge = Optional.of<Duration?>(remoteOptions.getDiskCacheGcMaxAge())
            }
            val delay = remoteOptions.getDiskCacheGcIdleDelay()
            if (maxSizeBytes.isEmpty() && maxAge.isEmpty()) {
                return null
            }
            val policy = CollectionPolicy(maxSizeBytes, maxAge)
            val gc =
                DiskCacheGarbageCollector(
                    workingDirectory.getRelative(diskCachePath), executorService, policy
                )
            return DiskCacheGarbageCollectorIdleTask(delay, gc)
        }
    }
}
