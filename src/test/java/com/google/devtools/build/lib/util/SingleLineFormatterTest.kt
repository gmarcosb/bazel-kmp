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

import com.google.common.truth.Truth
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.logging.LogRecord

@RunWith(JUnit4::class)
class SingleLineFormatterTest {
    @org.junit.Test
    fun testFormat() {
        val logRecord: LogRecord = createLogRecord(java.util.logging.Level.SEVERE, TIMESTAMP)
        assertThat(SingleLineFormatter().format(logRecord))
            .isEqualTo("170401 17:03:43.142:X 543 [SomeSourceClass.aSourceMethod] some message\n")
    }

    @org.junit.Test
    fun testLevel() {
        val logRecord: LogRecord = createLogRecord(java.util.logging.Level.WARNING, TIMESTAMP)
        val formatted: String? = SingleLineFormatter().format(logRecord)
        Truth.assertThat(formatted).contains("W")
        Truth.assertThat(formatted).doesNotContain("X")
    }

    @org.junit.Test
    fun testTime() {
        val logRecord: LogRecord =
            createLogRecord(
                java.util.logging.Level.SEVERE,
                ZonedDateTime.of(1999, 11, 30, 3, 4, 5, 0, ZoneOffset.UTC).plus(722, ChronoUnit.MILLIS)
            )
        com.google.common.truth.Subject.contains("991130 03:04:05.722")
    }

    @org.junit.Test
    fun testStackTrace() {
        val logRecord: LogRecord = createLogRecord(
            java.util.logging.Level.SEVERE, TIMESTAMP, java.lang.RuntimeException("something wrong")
        )
        assertThat(SingleLineFormatter().format(logRecord))
            .startsWith(
                ("170401 17:03:43.142:XT 543 [SomeSourceClass.aSourceMethod] some message\n"
                        + "java.lang.RuntimeException: something wrong\n"
                        + "\tat com.google.devtools.build.lib.util.SingleLineFormatterTest.testStackTrace")
            )
    }

    companion object {
        private val TIMESTAMP: ZonedDateTime =
            ZonedDateTime.of(2017, 4, 1, 17, 3, 43, 0, ZoneOffset.UTC).plus(142, ChronoUnit.MILLIS)

        private fun createLogRecord(
            level: java.util.logging.Level, dateTime: ZonedDateTime, thrown: java.lang.RuntimeException? = null
        ): LogRecord {
            val record: LogRecord = LogRecord(level, "some message")
            record.setMillis(dateTime.toInstant().toEpochMilli())
            record.setSourceClassName("SomeSourceClass")
            record.setSourceMethodName("aSourceMethod")
            record.setThreadID(543)
            if (thrown != null) {
                record.setThrown(thrown)
            }
            return record
        }
    }
}
