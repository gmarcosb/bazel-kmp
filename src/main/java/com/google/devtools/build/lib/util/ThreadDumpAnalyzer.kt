// Copyright 2025 The Bazel Authors. All rights reserved.
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

import java.io.BufferedReader
import java.io.IOException
import java.io.PrintWriter
import java.util.HashMap

/**
 * Analyzes thread dumps from `jcmd Thread.dump_to_file`, or [ ].
 * 
 * 
 * The analyzer groups threads with the same stack trace and sorts them by name.
 */
class ThreadDumpAnalyzer {
    private val threadsPerStackTrace: MutableMap<String?, MutableList<ThreadLine>> =
        HashMap<String?, MutableList<ThreadLine>>()

    @kotlin.jvm.JvmRecord
    private data class ThreadLine(
        val raw: String?,
        val id: String?,
        val name: String?,
        val states: MutableList<String?>?
    )

    /**
     * The result of the thread dump analysis.
     * 
     * @param otherLines The lines that are not related to threads.
     * @param groupedStackTraces The stack traces of the threads that have the same stack trace. The
     * threads in each stack trace are sorted by name.
     */
    @kotlin.jvm.JvmRecord
    data class AnalyzedThreadDump(val otherLines: MutableList<String?>?, val groupedStackTraces: MutableList<String?>?)

    /**
     * Analyzes the given thread dump from the given input stream.
     * 
     * @return The analyzed thread dump.
     */
    @Throws(IOException::class)
    fun analyze(`in`: java.io.InputStream): AnalyzedThreadDump {
        val otherLines: java.util.ArrayList<String?> = java.util.ArrayList<String?>()

        val reader: BufferedReader =
            BufferedReader(java.io.InputStreamReader(`in`, java.nio.charset.StandardCharsets.UTF_8))
        while (true) {
            val line: String? = reader.readLine()
            if (line == null) {
                break
            }

            val threadMatcher: java.util.regex.Matcher = THREAD_PATTERN.matcher(line)
            if (threadMatcher.matches()) {
                val threadLine =
                    ThreadLine(line, threadMatcher.group(1), threadMatcher.group(2), java.util.ArrayList<String?>())
                if (groupStackTrace(threadLine, reader)) {
                    break
                }
            } else {
                otherLines.add(line)
            }
        }

        // Sort the threads with the same stack trace by name
        for (threads in threadsPerStackTrace.values) {
            threads.sort(java.util.Comparator { a: ThreadLine?, b: ThreadLine? -> a!!.name!!.compareTo(b!!.name!!) })
        }

        val sortedEntries: java.util.ArrayList<MutableMap.MutableEntry<String?, MutableList<ThreadLine>>> =
            java.util.ArrayList<MutableMap.MutableEntry<String?, MutableList<ThreadLine>>>(threadsPerStackTrace.entries)
        // Sort the entries by the first thread's name in the group.
        sortedEntries.sort(
            java.util.Comparator.comparing<MutableMap.MutableEntry<String?, MutableList<ThreadLine?>?>?, String?>(
                java.util.function.Function { x: MutableMap.MutableEntry<String?, MutableList<ThreadLine?>?>? -> x!!.value.getFirst().name })
        )

        val groupedStackTraces: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
        for (entry in sortedEntries) {
            val sb: java.lang.StringBuilder = java.lang.StringBuilder()
            val stackTrace: String? = entry.key
            val threads: MutableList<ThreadLine> = entry.value
            for (thread in threads) {
                sb.append(thread.raw).append(java.lang.System.lineSeparator())
                for (state in thread.states!!) {
                    sb.append(state).append(java.lang.System.lineSeparator())
                }
            }
            sb.append(stackTrace)
            groupedStackTraces.add(sb.toString())
        }

        return AnalyzedThreadDump(otherLines, groupedStackTraces)
    }

    /**
     * Analyzes the given thread dump from the given input stream and writes the analysis to the given
     * output stream.
     */
    @Throws(IOException::class)
    fun analyze(`in`: java.io.InputStream, out: java.io.OutputStream) {
        val analyzedThreadDump = analyze(`in`)
        PrintWriter(out, false, java.nio.charset.StandardCharsets.UTF_8).use { writer ->
            for (line in analyzedThreadDump.otherLines!!) {
                writer.println(line)
            }
            for (stackTrace in analyzedThreadDump.groupedStackTraces!!) {
                writer.println(stackTrace)
            }
        }
    }

    /**
     * Groups the stack trace of the given thread with other threads having the same stack trace.
     * 
     * @return true if reached EOF.
     */
    @Throws(IOException::class)
    private fun groupStackTrace(threadLine: ThreadLine, reader: BufferedReader): Boolean {
        val sb: java.lang.StringBuilder = java.lang.StringBuilder()
        var eof = false
        while (true) {
            val line: String? = reader.readLine()
            if (line == null) {
                eof = true
                break
            }

            if (line.isBlank()) {
                break
            }

            if (THREAD_STATE_PATTERN.matcher(line).matches()) {
                threadLine.states!!.add(line)
            } else {
                sb.append(line).append(java.lang.System.lineSeparator())
            }
        }
        val stackTrace = sb.toString()
        val threads: MutableList<ThreadLine?> =
            threadsPerStackTrace.computeIfAbsent(stackTrace) { t: String? -> java.util.ArrayList<ThreadLine?>() }
        threads.add(threadLine)
        return eof
    }

    companion object {
        private val THREAD_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("#(\\d+)\\s\"([^\"]+)\".*")
        private val THREAD_STATE_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("\\s+-\\s(locked|lock|parking|waiting).*")
    }
}
