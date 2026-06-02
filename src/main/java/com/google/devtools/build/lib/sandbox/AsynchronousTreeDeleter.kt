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
package com.google.devtools.build.lib.sandbox

import com.google.devtools.build.lib.exec.TreeDeleter

/**
 * Executes file system tree deletions asynchronously.
 * 
 * 
 * The number of threads used to process the backlog of tree deletions can be configured at any
 * time via [.setThreads]. While a build is running, this number should be low to not use
 * precious resources that could otherwise be used for the build itself. But when the build is
 * finished, this number should be raised to quickly go through any pending deletions.
 */
class AsynchronousTreeDeleter(trashBase: com.google.devtools.build.lib.vfs.Path) : TreeDeleter {
    private val trashCount: AtomicInteger = AtomicInteger(0)

    /** Thread pool used to execute asynchronous tree deletions; null in synchronous mode.  */
    private var service: ThreadPoolExecutor?

    private val trashBase: com.google.devtools.build.lib.vfs.Path

    private var trashBaseCreated = false

    /** Constructs a new asynchronous tree deleter backed by just one thread.  */
    init {
        logger.atInfo().log("Starting async tree deletion pool with 1 thread")

        val threadFactory: ThreadFactory =
            com.google.common.util.concurrent.ThreadFactoryBuilder()
                .setNameFormat("tree-deleter")
                .setDaemon(true)
                .setPriority(java.lang.Thread.MIN_PRIORITY)
                .build()

        service =
            ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.SECONDS, LinkedBlockingQueue<java.lang.Runnable?>(), threadFactory
            )

        this.trashBase = trashBase
    }

    /**
     * Resizes the thread pool to the given number of threads.
     * 
     * 
     * If the pool of active threads is larger than the requested number of threads, the resize
     * will progressively happen as those active threads become inactive. If the requested size is
     * zero, this will wait for all pending deletions to complete.
     * 
     * @param threads desired number of threads, or 0 to go back to synchronous deletion
     */
    fun setThreads(threads: Int) {
        com.google.common.base.Preconditions.checkState(
            threads > 0,
            "Use SynchronousTreeDeleter if no async behavior is desired"
        )
        logger.atInfo().log("Resizing async tree deletion pool to %d threads", threads)
        com.google.common.base.Preconditions.checkNotNull<ThreadPoolExecutor?>(
            service,
            "Cannot call setThreads after shutdown"
        ).setMaximumPoolSize(threads)
    }

    @Throws(IOException::class)
    public override fun deleteTree(path: com.google.devtools.build.lib.vfs.Path) {
        if (!trashBaseCreated) {
            trashBase.createDirectory()
            trashBaseCreated = true
        }
        if (!path.exists()) {
            return
        }
        val trashPath: com.google.devtools.build.lib.vfs.Path =
            trashBase.getRelative(trashCount.getAndIncrement().toString())
        try {
            path.renameTo(trashPath)
        } catch (e: IOException) {
            logger.atWarning().withCause(e).log(
                "Failed to rename %s -> %s for asynchronous removal. Removing synchronously.",
                path, trashPath
            )
            path.deleteTree()
            return
        }
        com.google.common.base.Preconditions.checkNotNull<ThreadPoolExecutor?>(
            service,
            "Cannot call deleteTree after shutdown"
        )
            .execute(
                java.lang.Runnable {
                    try {
                        Profiler.instance().profile("trashPath.deleteTree").use { c ->
                            trashPath.deleteTree()
                        }
                    } catch (e: IOException) {
                        logger.atWarning().withCause(e).log(
                            "Failed to delete tree %s asynchronously", path
                        )
                    }
                })
    }

    public override fun shutdown() {
        if (service != null) {
            logger.atInfo().log("Finishing %d pending async tree deletions", service.getTaskCount())
            service.shutdown()
            service = null
        }
    }

    fun getTrashBase(): com.google.devtools.build.lib.vfs.Path {
        return trashBase
    }

    companion object {
        const val MOVED_TRASH_DIR: String = "_moved_trash_dir"

        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
