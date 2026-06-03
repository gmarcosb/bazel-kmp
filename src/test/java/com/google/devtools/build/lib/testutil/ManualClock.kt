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
package com.google.devtools.build.lib.testutil

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A fake clock for testing.
 */
class ManualClock : com.google.devtools.build.lib.clock.Clock {
    private val currentTimeMillis: AtomicLong = AtomicLong()

    override fun currentTimeMillis(): Long {
        return currentTimeMillis.get()
    }

    /**
     * Nano time should not be confused with wall time. Nano time is only mean to compute time
     * differences. Because of this, we shift the time returned by 1000s, to test that the users
     * of this class do not rely on nanoTime == currentTimeMillis.
     */
    override fun nanoTime(): Long {
        return (TimeUnit.MILLISECONDS.toNanos(currentTimeMillis.get())
                + TimeUnit.SECONDS.toNanos(1000))
    }

    fun advanceMillis(time: Long): Long {
        return currentTimeMillis.addAndGet(time)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun advance(duration: java.time.Duration): Long {
        return advanceMillis(duration.toMillis())
    }
}
