// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories

/**
 * A [DiffAwareness] that use fsevents to watch the filesystem to use in lieu of [ ].
 * 
 * 
 * On OS X, the local diff awareness cannot work because WatchService is dummy and do polling,
 * which is slow (https://bugs.openjdk.java.net/browse/JDK-7133447).
 */
class MacOSXFsEventsDiffAwareness internal constructor(
    watchRoot: java.nio.file.Path?,
    ignoredPaths: IgnoredSubdirectories,
    latency: Double,
    fsEventsNativeDepsService: FsEventsNativeDepsService
) : LocalDiffAwareness(watchRoot) {
    private val latency: Double
    private val ignoredPaths: IgnoredSubdirectories
    private val service: FsEventsNativeDepsService

    private var closed = false
    private var opened = false

    /**
     * Watch changes on the file system under `watchRoot` with a granularity of `delay
    ` *  seconds.
     */
    init {
        this.ignoredPaths = ignoredPaths
        this.latency = latency
        this.service = fsEventsNativeDepsService
    }

    /** Watch changes on the file system under `watchRoot` with a granularity of 5ms.  */
    internal constructor(
        watchRoot: java.nio.file.Path?,
        ignoredPaths: IgnoredSubdirectories,
        fsEventsNativeDepsService: FsEventsNativeDepsService
    ) : this(watchRoot, ignoredPaths, 0.005, fsEventsNativeDepsService)

    /**
     * Helper function to start the watch of `paths`, which is expected to be an array of
     * byte arrays containing the UTF-8 bytes of the paths to watch, called by the constructor.
     */
    private fun create(paths: Array<ByteArray?>?, excludedPaths: Array<ByteArray?>?, latency: Double) {
        service.createFsEvents(paths, excludedPaths, latency)
    }

    /**
     * Runs the main loop to listen for fsevents.
     * 
     * @param listening latch that is decremented when the fsevents queue has been set up. The caller
     * must wait until this happens before polling for events to ensure no events are lost between
     * when this function returns and when the queue is listening.
     */
    private fun run(listening: CountDownLatch?) {
        service.runFsEvents(listening)
    }

    private fun init() {
        // The code below is based on the assumption that init() can never fail, which is currently the
        // case; if you change init(), then you also need to update {@link #getCurrentView}.
        // TODO(jmmv): This can break if the user interrupts as anywhere in this function.
        com.google.common.base.Preconditions.checkState(!opened)
        opened = true
        // TODO: Also cover otherwise literal patterns of the form dir/**.
        val excludedPaths: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ignoredPaths.prefixes().stream() // FSEvents only supports up to 8 excluded paths.
                .limit(8)
                .map({ obj: PathFragment? -> obj.getPathString() }) // The prefixes are all absolute paths converted to relative paths via
                // PathFragment#toRelative.
                .map({ path -> "/" + path })
                .map({ s: String? -> StringEncoding.internalToUnicode(s) })
                .map({ path -> path.getBytes(java.nio.charset.StandardCharsets.UTF_8) })
                .toArray({ _Dummy_.__Array__() })
        create(
            arrayOf<ByteArray?>(
                watchRoot.toAbsolutePath().toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            ),
            excludedPaths,
            latency
        )

        // Start a thread that just contains the OS X run loop.
        val listening: CountDownLatch = CountDownLatch(1)
        java.lang.Thread(java.lang.Runnable { this@MacOSXFsEventsDiffAwareness.run(listening) }, "osx-fs-events")
            .start()
        try {
            listening.await()
        } catch (e: java.lang.InterruptedException) {
            java.lang.Thread.currentThread().interrupt()
        }
    }

    /** Close this watch service, this service should not be used any longer after closing.  */
    public override fun close() {
        if (opened) {
            com.google.common.base.Preconditions.checkState(!closed)
            closed = true
            doClose()
        }
    }

    /** JNI code stopping the main loop and shutting down listening to FSEvents.  */
    private fun doClose() {
        service.doCloseFsEvents()
    }

    /**
     * JNI code returning the list of absolute path modified since last call.
     * 
     * @return the array of paths (in the form of byte arrays containing the UTF-8 representation)
     * modified since the last call, or null if we can't precisely tell what changed
     */
    private fun poll(): Array<ByteArray>? {
        return service.pollFsEvents()
    }

    @Throws(BrokenDiffAwarenessException::class)
    public override fun getCurrentView(options: com.google.devtools.common.options.OptionsProvider): View? {
        // See WatchServiceDiffAwareness#getCurrentView for an explanation of this logic.
        val watchFs: Boolean =
            options.getOptions<com.google.devtools.build.lib.skyframe.LocalDiffAwareness.Options?>(com.google.devtools.build.lib.skyframe.LocalDiffAwareness.Options::class.java)
                .getWatchFS()
        if (watchFs && !opened) {
            init()
        } else if (!watchFs && opened) {
            close()
            throw BrokenDiffAwarenessException("Switched off --watchfs again")
        } else if (!opened) {
            // The only difference with WatchServiceDiffAwareness#getCurrentView is this if; the init()
            // call above can never fail, so we don't need to re-check the opened flag after init().
            return LocalDiffAwareness.Companion.EVERYTHING_MODIFIED
        }
        com.google.common.base.Preconditions.checkState(!closed)
        val polledPaths = poll()
        if (polledPaths == null) {
            return LocalDiffAwareness.Companion.EVERYTHING_MODIFIED
        } else {
            val paths: com.google.common.collect.ImmutableSet.Builder<java.nio.file.Path?> =
                com.google.common.collect.ImmutableSet.builder<java.nio.file.Path?>()
            for (pathBytes in polledPaths) {
                paths.add(Paths.get(String(pathBytes, java.nio.charset.StandardCharsets.UTF_8)))
            }
            return newView(paths.build())
        }
    }
}
