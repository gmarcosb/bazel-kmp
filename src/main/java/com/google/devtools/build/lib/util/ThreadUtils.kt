// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.bugreport.BugReport.sendBugReport
import com.google.devtools.build.lib.bugreport.BugReporter
import com.google.devtools.build.lib.bugreport.BugReporter.sendBugReport
import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.lib.util.ThreadDumpAnalyzer
import com.google.devtools.build.lib.util.ThreadDumpAnalyzer.AnalyzedThreadDump
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Collectors

/** Utility to dump stack traces to logs and remotely log on slow interrupt.  */
object ThreadUtils {
    private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    private val STACK_SIZE_THEN_THREAD_COUNT_BOTH_DESCENDING_COMPARATOR: java.util.Comparator<MutableMap.MutableEntry<StackTraceAndState?, java.util.ArrayList<MutableMap.MutableEntry<java.lang.Thread?, Array<java.lang.StackTraceElement?>?>?>?>?>? =
        java.util.Map.Entry
            .comparingByKey<StackTraceAndState?, java.util.ArrayList<MutableMap.MutableEntry<java.lang.Thread?, Array<java.lang.StackTraceElement?>?>?>?>
    ()
    .thenComparingInt(ToIntFunction
    { o: kotlin.collections.MutableMap.MutableEntry<StackTraceAndState?, java.util.ArrayList<kotlin.collections.MutableMap.MutableEntry<java.lang.Thread?, kotlin.Array<java.lang.StackTraceElement?>?>?>?>? -> o.value.size })
    .reversed()
    private val MAP_WITH_ARRAY_LIST_VALUES_COLLECTOR: java.util.stream.Collector<MutableMap.MutableEntry<java.lang.Thread?, Array<java.lang.StackTraceElement?>?>?, *, MutableMap<StackTraceAndState, java.util.ArrayList<MutableMap.MutableEntry<java.lang.Thread?, Array<java.lang.StackTraceElement?>?>?>?>?> =
        Collectors.groupingBy(java.util.function.Function { threadEntry: MutableMap.MutableEntry<java.lang.Thread?, Array<java.lang.StackTraceElement?>?>? ->
            StackTraceAndState(threadEntry)
        }, Collectors.toCollection(java.util.function.Supplier { ArrayList() }))

    /** Write a thread dump to the blaze.INFO log if interrupt took too long.  */
    @kotlin.jvm.Synchronized
    fun warnAboutSlowInterrupt(
        slowInterruptMessageSuffix: String?
    ) {
        com.google.devtools.build.lib.util.ThreadUtils.warnAboutSlowInterrupt(
            slowInterruptMessageSuffix,
            BugReporter.defaultInstance()
        )
    }

    @com.google.common.annotations.VisibleForTesting
    @kotlin.jvm.Synchronized
    fun warnAboutSlowInterrupt(
        slowInterruptMessageSuffix: String?, bugReporter: BugReporter
    ) {
        com.google.devtools.build.lib.util.ThreadUtils.logger.atWarning()
            .log("Interrupt took too long. Dumping thread state.")
        val firstTrace: AtomicReference<StackTraceAndState?> = AtomicReference<StackTraceAndState?>()
        java.lang.Thread.getAllStackTraces().entries.stream()
            .collect(com.google.devtools.build.lib.util.ThreadUtils.MAP_WITH_ARRAY_LIST_VALUES_COLLECTOR)
            .entries
            .stream()
            .sorted(com.google.devtools.build.lib.util.ThreadUtils.STACK_SIZE_THEN_THREAD_COUNT_BOTH_DESCENDING_COMPARATOR)
            .forEach { e: MutableMap.MutableEntry<StackTraceAndState, java.util.ArrayList<MutableMap.MutableEntry<java.lang.Thread?, Array<java.lang.StackTraceElement?>?>?>?>? ->
                val stackTraceAndState = e!!.key
                // Store longest trace but omit "Unsafe#park" and "Object#wait" calls: they are
                // interruptible, so can't be the cause of the slow interrupt. In some cases, we
                // do these calls in a loop on interrupt (see
                // AbstractQueueVisitor#reallyAwaitTermination) but unless there's a bug, there should
                // still be another thread waiting for interrupt somewhere.
                if (firstTrace.get() == null && (!stackTraceAndState.trace[0].getClassName().endsWith("misc.Unsafe")
                            || stackTraceAndState.trace[0].getMethodName() != "park")
                    && (!stackTraceAndState.trace[0].getClassName().endsWith("java.lang.Object")
                            || stackTraceAndState.trace[0].getMethodName() != "wait")
                ) {
                    firstTrace.compareAndSet(null, stackTraceAndState)
                }
                com.google.devtools.build.lib.util.ThreadUtils.logger.atWarning().log(
                    "%s %s%s",
                    stackTraceAndState.state,
                    com.google.devtools.build.lib.util.ThreadUtils.makeThreadInfoString(e.value),
                    com.google.devtools.build.lib.util.ThreadUtils.makeString(stackTraceAndState.trace)
                )
            }

        try {
            com.google.devtools.build.lib.util.ThreadUtils.logger.atWarning()
                .log("Dumping additional thread state using ThreadDumper:")

            val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
            com.google.devtools.build.lib.util.ThreadDumper.dumpThreads(out)

            val `in`: ByteArrayInputStream = ByteArrayInputStream(out.toByteArray())
            val analyzedThreadDump: AnalyzedThreadDump = ThreadDumpAnalyzer().analyze(`in`)

            for (line in analyzedThreadDump.otherLines) {
                com.google.devtools.build.lib.util.ThreadUtils.logger.atWarning().log("%s", line)
            }
            for (stackTrace in analyzedThreadDump.groupedStackTraces) {
                com.google.devtools.build.lib.util.ThreadUtils.logger.atWarning().log("%s", stackTrace)
            }
        } catch (e: IOException) {
            com.google.devtools.build.lib.util.ThreadUtils.logger.atWarning().withCause(e)
                .log("Failed to dump threads with ThreadDumper.")
        }

        val inner =
            SlowInterruptInnerException(
                com.google.common.base.Joiner.on(' ')
                    .skipNulls()
                    .join("(Wrapper exception for longest stack trace)", slowInterruptMessageSuffix)
            )
        inner.setStackTrace(firstTrace.get().trace)
        val ex = SlowInterruptException(inner)
        bugReporter.sendBugReport(ex)
    }

    private fun makeString(stackTrace: Array<java.lang.StackTraceElement?>): String {
        val builder: java.lang.StringBuilder = java.lang.StringBuilder()
        for (elt in stackTrace) {
            builder.append('\n').append('\t').append(elt)
        }
        return builder.toString()
    }

    private val THREAD_NAME_THEN_ID: java.util.Comparator<MutableMap.MutableEntry<java.lang.Thread?, Array<java.lang.StackTraceElement?>?>?>? =
        java.util.Comparator.comparing<MutableMap.MutableEntry<java.lang.Thread?, Array<java.lang.StackTraceElement?>?>?, String?>(
            java.util.function.Function { o: MutableMap.MutableEntry<java.lang.Thread?, Array<java.lang.StackTraceElement?>?>? -> o!!.key.getName() })
            .thenComparingLong(java.util.function.ToLongFunction { o: MutableMap.MutableEntry<java.lang.Thread?, Array<java.lang.StackTraceElement?>?>? -> o!!.key.getId() })

    private fun makeThreadInfoString(
        entries: java.util.ArrayList<MutableMap.MutableEntry<java.lang.Thread?, Array<java.lang.StackTraceElement?>?>>
    ): String {
        val builder: java.lang.StringBuilder = java.lang.StringBuilder()
        if (entries.size > 10) {
            builder.append(entries.size).append(" threads, ")
        }
        entries.sort(com.google.devtools.build.lib.util.ThreadUtils.THREAD_NAME_THEN_ID)
        var first = true
        for (entry in entries) {
            if (first) {
                first = false
            } else {
                builder.append(", ")
            }
            val thread: java.lang.Thread = entry.key
            builder.append('<').append(thread.getName()).append(' ').append(thread.getId()).append('>')
        }
        return builder.toString()
    }

    private class SlowInterruptException(inner: SlowInterruptInnerException?) :
        java.lang.RuntimeException("Slow interrupt", inner)

    private class StackTraceAndState(threadEntry: MutableMap.MutableEntry<java.lang.Thread?, Array<java.lang.StackTraceElement?>?>) :
        Comparable<StackTraceAndState?> {
        private val trace: Array<java.lang.StackTraceElement?>
        private val state: java.lang.Thread.State

        init {
            this.trace = threadEntry.value
            this.state = threadEntry.key.getState()
        }

        override fun hashCode(): Int {
            return 31 * state.hashCode() + trace.contentHashCode()
        }

        override fun equals(obj: Any?): Boolean {
            if (obj === this) {
                return true
            }
            if (obj !is StackTraceAndState) {
                return false
            }
            return this.trace.contentEquals(obj.trace) && this.state == obj.state
        }

        override fun compareTo(o: StackTraceAndState): Int {
            return java.lang.Integer.compare(trace.size, o.trace.size)
        }
    }

    private class SlowInterruptInnerException(message: String?) : java.lang.Exception(message)
}
