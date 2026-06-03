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
package com.google.testing.junit.runner.util

import com.google.devtools.build.lib.clock.Clock.nanoTime
import java.time.Instant

/**
 * A time source used to obtain:
 *  * a monotonic timestamp with no relation to a wall time;
 *  * a timestamp that can be used to obtain wall time but is not guaranteed to be monotonic.
 */
abstract class TestClock
/** Constructor for use by subclasses.  */
protected constructor() {
    /**
     * Returns an immutable value type that contains both a monotonic timestamp (used to measure
     * relative time but unrelated to wall time) and an EPOCH relative timestamp.
     */
    open fun now(): TestInstant? {
        return TestInstant(wallTime(), monotonicTime())
    }

    /**
     * Returns a monotonic timestamp that can only be used to compute relative time.
     * 
     * 
     * **Warning:** the returned timestamp can only be used to measure elapsed time, not wall
     * time.
     */
    abstract fun monotonicTime(): java.time.Duration?

    /**
     * A timestamp that may be used to obtain wall time, but is not guaranteed to be monotonic.
     * 
     * 
     * **Warning:** the returned timestamp is not guaranteed to be monotonic, and it may appear
     * to go back in time in certain cases (e.g. daylight saving time).
     */
    abstract fun wallTime(): Instant?

    /**
     * An immutable value type that contains both a monotonic timestamp (used to measure relative time
     * but unrelated to wall time) and an EPOCH timestamp.
     */
    class TestInstant(wallTime: Instant?, monotonicTime: java.time.Duration?) {
        private val wallTime: Instant?
        private val monotonicTime: java.time.Duration?

        init {
            this.wallTime = wallTime
            this.monotonicTime = monotonicTime
        }

        /**
         * A timestamp that may be used to obtain wall time, but is not guaranteed to be monotonic.
         * 
         * 
         * **Warning:** the returned timestamp is not guaranteed to be monotonic, and it may
         * appear to go back in time in certain cases (e.g. daylight saving time).
         */
        fun wallTime(): Instant? {
            return wallTime
        }

        /**
         * Returns a monotonic timestamp that can only be used to compute relative time.
         * 
         * 
         * **Warning:** the returned timestamp can only be used to measure elapsed time, not wall
         * time.
         */
        fun monotonicTime(): java.time.Duration? {
            return monotonicTime
        }

        companion object {
            val UNKNOWN: TestInstant = TestInstant(Instant.EPOCH, java.time.Duration.ZERO)
        }
    }

    companion object {
        /**
         * A time source that produces an epoch timestamp using [System.currentTimeMillis] and a
         * monotonic timestamp using [System.nanoTime].
         */
        fun systemClock(): TestClock {
            return SYSTEM_TEST_CLOCK
        }

        private val SYSTEM_TEST_CLOCK: TestClock = object : TestClock() {
            public override fun monotonicTime(): java.time.Duration? {
                return java.time.Duration.ofNanos(java.lang.System.nanoTime())
            }

            public override fun wallTime(): Instant? {
                return Instant.ofEpochMilli(java.lang.System.currentTimeMillis())
            }
        }
    }
}
