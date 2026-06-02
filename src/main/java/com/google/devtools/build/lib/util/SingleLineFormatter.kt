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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.io.PrintWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.logging.LogRecord

/**
 * Formatter to write java.util.logging messages out in single-line format.
 * 
 * 
 * Log entries contain the date and time (in UTC), log level (as letter and numerical value),
 * source location, thread ID, message and, if applicable, a stack trace.
 */
class SingleLineFormatter : java.util.logging.Formatter() {
    override fun format(rec: LogRecord): String {
        val buf: java.lang.StringBuilder = java.lang.StringBuilder()

        // Timestamp
        buf.append(
            DATE_TIME_FORMAT.format(Instant.ofEpochMilli(rec.getMillis()).atZone(ZoneOffset.UTC))
        )
            .append(':')

        // One character code for level
        buf.append(CODES_BY_LEVEL.get(rec.getLevel().intValue()))

        // The stack trace, if any
        val thrown: Throwable? = rec.getThrown()
        if (thrown != null) {
            buf.append('T')
        }

        buf.append(' ')

        // Information about the source of the exception
        buf.append(rec.getThreadID())
            .append(" [")
            .append(rec.getSourceClassName())
            .append('.')
            .append(rec.getSourceMethodName())
            .append("] ")

        // The actual message
        buf.append(formatMessage(rec)).append('\n')

        if (thrown != null) {
            val sw: java.io.StringWriter = java.io.StringWriter()
            val pw: PrintWriter = PrintWriter(sw)
            thrown.printStackTrace(pw)
            pw.flush()
            buf.append(sw.toString())
        }

        return buf.toString()
    }

    companion object {
        /** Single-character codes based on [Level]s.  */
        private val CODES_BY_LEVEL: com.google.common.collect.ImmutableRangeMap<Int?, Char?> =
            com.google.common.collect.ImmutableRangeMap.builder<Int?, Char?>()
                .put(com.google.common.collect.Range.atMost<Int?>(java.util.logging.Level.FINE.intValue()), 'D')
                .put(
                    com.google.common.collect.Range.open<Int?>(
                        java.util.logging.Level.FINE.intValue(),
                        java.util.logging.Level.WARNING.intValue()
                    ), 'I'
                )
                .put(
                    com.google.common.collect.Range.closedOpen<Int?>(
                        java.util.logging.Level.WARNING.intValue(),
                        java.util.logging.Level.SEVERE.intValue()
                    ), 'W'
                )
                .put(com.google.common.collect.Range.atLeast<Int?>(java.util.logging.Level.SEVERE.intValue()), 'X')
                .build()

        /** A thread safe, immutable formatter that can be used by all without contention.  */
        private val DATE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyMMdd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
    }
}
