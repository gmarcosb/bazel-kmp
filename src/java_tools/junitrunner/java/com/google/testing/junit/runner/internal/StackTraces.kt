// Copyright 2011 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.internal

import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import com.sun.management.HotSpotDiagnosticMXBean
import net.starlark.java.syntax.Identifier.getName
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Paths
import java.util.HashMap

/** Utilities for stack traces.  */
object StackTraces {
    /**
     * Prints all stack traces to the given stream.
     * 
     * @param out Stream to print to
     * @param emitJsonThreadDump Whether to also emit a JSON thread dump to a file
     */
    /**
     * Prints all stack traces to the given stream.
     * 
     * @param out Stream to print to
     */
    @kotlin.jvm.JvmOverloads
    fun printAll(out: PrintStream, emitJsonThreadDump: Boolean = false) {
        out.println("Starting full thread dump ...\n")
        val mb: java.lang.management.ThreadMXBean = java.lang.management.ManagementFactory.getThreadMXBean()

        // ThreadInfo has comprehensive information such as locks.
        val threadInfos: Array<java.lang.management.ThreadInfo> = mb.dumpAllThreads(true, true)

        // But we can know whether a thread is daemon only from Thread
        val threads: MutableSet<java.lang.Thread> = java.lang.Thread.getAllStackTraces().keys
        val threadMap: MutableMap<Long?, java.lang.Thread?> = HashMap<Long?, java.lang.Thread?>()
        for (thread in threads) {
            threadMap.put(thread.getId(), thread)
        }

        // Dump non-daemon threads first
        for (threadInfo in threadInfos) {
            val thread: java.lang.Thread? = threadMap.get(threadInfo.getThreadId())
            if (thread != null && !thread.isDaemon()) {
                dumpThreadInfo(threadInfo, thread, out)
            }
        }

        // Dump daemon threads
        for (threadInfo in threadInfos) {
            val thread: java.lang.Thread? = threadMap.get(threadInfo.getThreadId())
            if (thread != null && thread.isDaemon()) {
                dumpThreadInfo(threadInfo, thread, out)
            }
        }

        val deadlockedThreads: LongArray? = mb.findDeadlockedThreads()
        if (deadlockedThreads != null) {
            out.println("Detected deadlocked threads: " + deadlockedThreads.contentToString())
        }
        val monitorDeadlockedThreads: LongArray? = mb.findMonitorDeadlockedThreads()
        if (monitorDeadlockedThreads != null) {
            out.println(
                "Detected monitor deadlocked threads: " + monitorDeadlockedThreads.contentToString()
            )
        }
        out.println("\nDone full thread dump.")
        out.flush()

        if (!emitJsonThreadDump) {
            return
        }
        // The thread dump above does not include virtual threads, so also capture a structured dump
        // that includes virtual threads. Since the dump is potentially large, we write it to a file in
        // the test outputs directory instead of printing it to the console.
        // Find the dumpThreads(String, ThreadDumpFormat) method via reflection to maintain
        // compatibility with JDKs prior to 21.
        val dumpThreadsMethod: java.util.Optional<java.lang.reflect.Method?> =
            java.util.Arrays.stream<java.lang.reflect.Method?>(HotSpotDiagnosticMXBean::class.java.getMethods())
                .filter { m: java.lang.reflect.Method? ->
                    m.getName() == "dumpThreads"
                            && m.getParameterCount() == 2 && m.getParameterTypes()[0] == String::class.java && m.getParameterTypes()[1].isEnum()
                }
                .findFirst()
        if (!dumpThreadsMethod.isPresent()) {
            return
        }
        val threadDumpFormatJson: java.util.Optional<*> =
            java.util.Arrays.stream(dumpThreadsMethod.get().getParameterTypes()[1].getEnumConstants())
                .filter { e: Any? -> e.toString().equals("JSON", ignoreCase = true) }
                .findFirst()
        if (!threadDumpFormatJson.isPresent()) {
            return
        }
        val diagnosticBean: HotSpotDiagnosticMXBean? =
            java.lang.management.ManagementFactory.getPlatformMXBean<HotSpotDiagnosticMXBean?>(HotSpotDiagnosticMXBean::class.java)
        val testOutputsDir: String? = java.lang.System.getenv("TEST_UNDECLARED_OUTPUTS_DIR")
        if (testOutputsDir == null) {
            return
        }
        try {
            val jsonDump: Path = java.nio.file.Files.createTempFile(Paths.get(testOutputsDir), "thread_dump", ".json")
            java.nio.file.Files.delete(jsonDump)
            out.println("Writing JSON thread dump to " + jsonDump)
            dumpThreadsMethod
                .get()
                .invoke(diagnosticBean, jsonDump.toString(), threadDumpFormatJson.get())
            out.println("Done writing JSON thread dump to " + jsonDump)
        } catch (e: java.lang.ReflectiveOperationException) {
            out.println("Failed to write JSON thread dump:")
            e.printStackTrace(out)
        } catch (e: IOException) {
            out.println("Failed to write JSON thread dump:")
            e.printStackTrace(out)
        } finally {
            out.flush()
        }
    }

    // Adopted from ThreadInfo.toString(), without MAX_FRAMES limit
    private fun dumpThreadInfo(t: java.lang.management.ThreadInfo, thread: java.lang.Thread, out: PrintStream) {
        out.print(
            "\"" + t.getThreadName() + "\"" + " Id=" + t.getThreadId() + " " + t.getThreadState()
        )
        if (t.getLockName() != null) {
            out.print(" on " + t.getLockName())
        }
        if (t.getLockOwnerName() != null) {
            out.print(" owned by \"" + t.getLockOwnerName() + "\" Id=" + t.getLockOwnerId())
        }
        if (t.isSuspended()) {
            out.print(" (suspended)")
        }
        if (t.isInNative()) {
            out.print(" (in native)")
        }
        if (thread.isDaemon()) {
            out.print(" (daemon)")
        }
        out.print('\n')
        val stackTrace: Array<java.lang.StackTraceElement> = t.getStackTrace()
        val lockedMonitors: Array<java.lang.management.MonitorInfo> = t.getLockedMonitors()
        for (i in stackTrace.indices) {
            val ste: java.lang.StackTraceElement = stackTrace[i]
            out.print("\tat " + ste.toString())
            out.print('\n')
            if (i == 0 && t.getLockInfo() != null) {
                val ts: java.lang.Thread.State = t.getThreadState()
                when (ts) {
                    java.lang.Thread.State.BLOCKED -> {
                        out.print("\t-  blocked on " + t.getLockInfo())
                        out.print('\n')
                    }

                    java.lang.Thread.State.WAITING -> {
                        out.print("\t-  waiting on " + t.getLockInfo())
                        out.print('\n')
                    }

                    java.lang.Thread.State.TIMED_WAITING -> {
                        out.print("\t-  waiting on " + t.getLockInfo())
                        out.print('\n')
                    }

                    else -> {}
                }
            }

            for (mi in lockedMonitors) {
                if (mi.getLockedStackDepth() == i) {
                    out.print("\t-  locked " + mi)
                    out.print('\n')
                }
            }
        }

        val locks: Array<java.lang.management.LockInfo?> = t.getLockedSynchronizers()
        if (locks.size > 0) {
            out.print("\n\tNumber of locked synchronizers = " + locks.size)
            out.print('\n')
            for (li in locks) {
                out.print("\t- " + li)
                out.print('\n')
            }
        }
        out.print('\n')
    }
}
