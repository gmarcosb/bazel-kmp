// Copyright 2016 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.model

import com.google.testing.junit.runner.util.TestClock.TestInstant

/**
 * Implementation of an immutable time interval, representing a period of time between two instants.
 * 
 * 
 * This class is thread-safe and immutable.
 */
class TestInterval(startInstant: TestInstant, endInstant: TestInstant) {
    private val startInstant: TestInstant
    private val endInstant: TestInstant

    init {
        require(
            !(startInstant.monotonicTime().compareTo(endInstant.monotonicTime()) > 0)
        ) { "Start must be before end" }
        this.startInstant = startInstant
        this.endInstant = endInstant
    }

    val startMillis: Long
        get() = startInstant.wallTime().toEpochMilli()

    val endMillis: Long
        get() = endInstant.wallTime().toEpochMilli()

    fun toDurationMillis(): Long {
        return endInstant.monotonicTime().minus(startInstant.monotonicTime()).toMillis()
    }

    fun withEndMillis(now: TestInstant): TestInterval {
        return TestInterval(startInstant, now)
    }

    fun startInstantToString(): String {
        // Format as ISO8601 with 3 fractional digits on seconds
        // This format is not affected by timezones and locale which improves interoperability
        return ISO8601_WITH_MILLIS_FORMATTER.format(startInstant.wallTime())
    }

    companion object {
        private val ISO8601_WITH_MILLIS_FORMATTER: DateTimeFormatter =
            DateTimeFormatterBuilder().appendInstant(3).toFormatter()

        /** Returns a TestInterval that contains both TestIntervals passed as parameter.  */
        fun around(a: TestInterval, b: TestInterval): TestInterval {
            val start: TestInstant =
                if (a.startInstant.monotonicTime().compareTo(b.startInstant.monotonicTime()) < 0)
                    a.startInstant
                else
                    b.startInstant
            val end: TestInstant =
                if (a.endInstant.monotonicTime().compareTo(b.endInstant.monotonicTime()) > 0)
                    a.endInstant
                else
                    b.endInstant
            return TestInterval(start, end)
        }
    }
}
