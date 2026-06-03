// Copyright 2010 The Bazel Authors. All Rights Reserved.
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

import com.google.devtools.build.lib.clock.Clock.now
import com.google.testing.junit.runner.util.TestClock
import com.google.testing.junit.runner.util.TestClock.TestInstant
import java.time.Instant

/**
 * A Ticker whose value can be advanced programmatically in test.
 * 
 * 
 * The ticker can be configured so that the time is incremented whenever [.now] is
 * called.
 * 
 * 
 * This class is thread-safe.
 */
class FakeTestClock : TestClock() {
    private var wallTimeOffset: Instant = Instant.EPOCH
    private var monotonic: java.time.Duration = java.time.Duration.ZERO
    private val autoIncrementStep: java.time.Duration = java.time.Duration.ZERO

    /** Advances the ticker value by `time` in `timeUnit`.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @kotlin.jvm.Synchronized
    fun advance(duration: java.time.Duration): FakeTestClock {
        monotonic = monotonic.plus(duration)
        return this
    }

    /**
     * Sets the wall time offset to the specified value. That is the offset between the wall time and
     * the monotonic advance set either via [.setAutoIncrementStep] or [ ][.advance].
     * 
     * 
     * The default behavior is to have an offset of zero, which means that the monotonic timestamp
     * has the same value as the wall time (relative to EPOCH).
     */
    fun setWallTimeOffset(wallTimeOffset: Instant) {
        this.wallTimeOffset = wallTimeOffset
    }

    override fun monotonicTime(): java.time.Duration {
        return monotonic
    }

    override fun wallTime(): Instant? {
        return wallTimeOffset.plus(monotonic)
    }

    @kotlin.jvm.Synchronized
    override fun now(): TestInstant? {
        advance(autoIncrementStep)
        return super.now()
    }
}

