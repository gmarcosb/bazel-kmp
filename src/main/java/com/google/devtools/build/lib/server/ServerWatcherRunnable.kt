// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.server

import com.google.devtools.build.lib.platform.SystemMemoryPressureMonitor

/**
 * Runnable that checks to see if a [GrpcCommandServer] has been idle for too long and shuts
 * down the server if so.
 */
internal class ServerWatcherRunnable @com.google.common.annotations.VisibleForTesting constructor(
    server: GrpcCommandServer,
    maxIdleSeconds: Long,
    shutdownOnLowSysMem: Boolean,
    commandManager: CommandManager,
    lowMemoryChecker: LowMemoryChecker
) : java.lang.Runnable {
    private val server: GrpcCommandServer
    private val maxIdleSeconds: Long
    private val commandManager: CommandManager
    private val lowMemoryChecker: LowMemoryChecker
    private val shutdownOnLowSysMem: Boolean

    /** Generic abstraction to check for low memory conditions on different platforms.  */
    private abstract class LowMemoryChecker {
        /** Timestamp of the moment the server went idle.  */
        private var lastIdleTimeMillis: Long = -1

        /** Checks if the server should shut down due to a low memory condition.  */
        fun shouldShutdown(): Boolean {
            com.google.common.base.Preconditions.checkState(
                lastIdleTimeMillis >= 0,
                "reset() ought to have been called before this"
            )

            if (com.google.devtools.build.lib.clock.BlazeClock.instance().currentTimeMillis() - lastIdleTimeMillis
                < TIME_IDLE_BEFORE_MEMORY_CHECK.toMillis()
            ) {
                // Only run memory check if the server has been idle for longer than
                // TIME_IDLE_BEFORE_MEMORY_CHECK.
                return false
            }

            return check()
        }

        /** Returns true if the system has observed low memory conditions.  */
        abstract fun check(): Boolean

        /** Notifies the checker that the server went idle at the given timestamp.  */
        fun reset(lastIdleTimeMillis: Long) {
            this.lastIdleTimeMillis = lastIdleTimeMillis
        }

        companion object {
            /** Creates a memory checker that makes sense for the current platform.  */
            fun forCurrentOS(): LowMemoryChecker {
                when (com.google.devtools.build.lib.util.OS.getCurrent()) {
                    com.google.devtools.build.lib.util.OS.LINUX -> return ProcMeminfoLowMemoryChecker(
                        ProcMeminfoParserSupplier { ProcMeminfoParser() })

                    else -> return MemoryPressureLowMemoryChecker()
                }
            }
        }
    }

    /**
     * A low memory conditions checker that relies on memory pressure state.
     * 
     * 
     * Memory pressure state is provided by the platform-agnostic [ ] class, which may be a no-op for the current platform.
     */
    private class MemoryPressureLowMemoryChecker : LowMemoryChecker() {
        override fun check(): Boolean {
            return SystemMemoryPressureMonitor.instance.level() !== Level.NORMAL
        }
    }

    /** A low memory condition checker that uses instantaneous data from `/proc/meminfo`.  */
    internal class ProcMeminfoLowMemoryChecker(private val supplier: ProcMeminfoParserSupplier) : LowMemoryChecker() {
        /** Supplier for a [ProcMeminfoParser].  */
        internal interface ProcMeminfoParserSupplier {
            @Throws(IOException::class)
            fun get(): ProcMeminfoParser
        }

        override fun check(): Boolean {
            try {
                val meminfoParser: ProcMeminfoParser = supplier.get()
                val freeRamKb: Long = meminfoParser.getFreeRamKb()
                val usedRamKb: Long = meminfoParser.getTotalKb()
                val fractionRamFree = (freeRamKb.toDouble()) / usedRamKb

                // Shutdown when both the absolute amount and percentage of free RAM is lower than the set
                // thresholds.
                return fractionRamFree < FREE_MEMORY_PERCENTAGE_THRESHOLD
                        && freeRamKb < FREE_MEMORY_KB_ABSOLUTE_THRESHOLD
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log("Unable to read memory info.")
                return false
            }
        }
    }

    constructor(
        server: GrpcCommandServer,
        maxIdleSeconds: Long,
        shutdownOnLowSysMem: Boolean,
        commandManager: CommandManager
    ) : this(
        server,
        maxIdleSeconds,
        shutdownOnLowSysMem,
        commandManager,
        LowMemoryChecker.Companion.forCurrentOS()
    )

    init {
        com.google.common.base.Preconditions.checkArgument(
            maxIdleSeconds > 0,
            "Expected to only check idleness when --max_idle_secs > 0 but it was %s",
            maxIdleSeconds
        )
        this.server = server
        this.maxIdleSeconds = maxIdleSeconds
        this.commandManager = commandManager
        this.lowMemoryChecker = lowMemoryChecker
        this.shutdownOnLowSysMem = shutdownOnLowSysMem
    }

    override fun run() {
        var idle: Boolean = commandManager.isEmpty()
        var wasIdle = false
        var shutdownTimeMillis: Long = -1

        while (true) {
            if (!wasIdle && idle) {
                val now: Long = com.google.devtools.build.lib.clock.BlazeClock.instance().currentTimeMillis()
                shutdownTimeMillis = now + java.time.Duration.ofSeconds(maxIdleSeconds).toMillis()
                lowMemoryChecker.reset(now)
            }

            try {
                if (idle) {
                    com.google.common.base.Verify.verify(shutdownTimeMillis > 0)
                    if (shutdownOnLowSysMem && lowMemoryChecker.shouldShutdown()) {
                        logger.atSevere().log("Available RAM is low. Shutting down idle server...")
                        break
                    }
                    // Re-run the check every 5 seconds if no other commands have been sent to the server.
                    commandManager.waitForChange(IDLE_MEMORY_CHECK_INTERVAL.toMillis())
                } else {
                    commandManager.waitForChange()
                }
            } catch (e: java.lang.InterruptedException) {
                // Dealt with by checking the current time below.
            }

            wasIdle = idle
            idle = commandManager.isEmpty()
            if (wasIdle && idle && com.google.devtools.build.lib.clock.BlazeClock.instance()
                    .currentTimeMillis() >= shutdownTimeMillis
            ) {
                logger.atInfo().log("About to shutdown due to idleness")
                break
            }
        }
        server.shutdown()
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
        private val IDLE_MEMORY_CHECK_INTERVAL: java.time.Duration = java.time.Duration.ofSeconds(5)
        private val TIME_IDLE_BEFORE_MEMORY_CHECK: java.time.Duration = java.time.Duration.ofMinutes(5)
        private val FREE_MEMORY_KB_ABSOLUTE_THRESHOLD = 1L shl 20
        private const val FREE_MEMORY_PERCENTAGE_THRESHOLD = 0.05
    }
}
