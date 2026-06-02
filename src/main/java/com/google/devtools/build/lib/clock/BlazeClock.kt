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
package com.google.devtools.build.lib.clock

import com.google.common.annotations.VisibleForTesting
import com.google.devtools.build.lib.concurrent.ThreadSafety
import java.util.concurrent.TimeUnit

/**
 * Provides the clock implementation used by Blaze, which is [JavaClock] by default, but can
 * be overridden at runtime. If you set this clock, you also have to set the clock used by the
 * Profiler.
 * 
 * 
 * Note that clock readings are relative to an unspecified reference time, so returned values are
 * only meaningful when compared to each other. A [NanosToMillisSinceEpochConverter] or [ ] may be used to convert clock readings into milliseconds since
 * the epoch or vice-versa.
 */
@ThreadSafety.ThreadSafe
object BlazeClock {
    @kotlin.concurrent.Volatile
    private var instance: Clock = JavaClock()

    /** Returns singleton instance of the clock  */
    @kotlin.jvm.JvmStatic
    fun instance(): Clock {
        return instance
    }

    /** Overrides default clock instance.  */
    @kotlin.jvm.JvmStatic
    @kotlin.jvm.Synchronized
    fun setClock(clock: Clock) {
        instance = clock
    }

    @kotlin.jvm.JvmStatic
    fun nanoTime(): Long {
        return instance().nanoTime()
    }

    /**
     * Creates a [NanosToMillisSinceEpochConverter] from the current [BlazeClock]
     * instance.
     */
    @kotlin.jvm.JvmStatic
    fun createNanosToMillisSinceEpochConverter(): NanosToMillisSinceEpochConverter {
        return createNanosToMillisSinceEpochConverter(instance)
    }

    /** Creates a [NanosToMillisSinceEpochConverter] from the given [Clock].  */
    @kotlin.jvm.JvmStatic
    @VisibleForTesting
    fun createNanosToMillisSinceEpochConverter(
        clock: Clock
    ): NanosToMillisSinceEpochConverter {
        val nowInMillis = clock.currentTimeMillis()
        val nowInNanos = clock.nanoTime()
        return BlazeClock.NanosToMillisSinceEpochConverter { timeNanos: Long ->
            nowInMillis - TimeUnit.NANOSECONDS.toMillis(
                nowInNanos - timeNanos
            )
        }
    }

    /**
     * Creates a [NanosToMillisSinceEpochConverter] from the current [BlazeClock]
     * instance.
     */
    @kotlin.jvm.JvmStatic
    fun createMillisSinceEpochToNanosConverter(): MillisSinceEpochToNanosConverter {
        return createMillisSinceEpochToNanosConverter(instance)
    }

    /** Creates a [MillisSinceEpochToNanosConverter] from the given [Clock].  */
    @VisibleForTesting
    fun createMillisSinceEpochToNanosConverter(
        clock: Clock
    ): MillisSinceEpochToNanosConverter {
        val nowInMillis = clock.currentTimeMillis()
        val nowInNanos = clock.nanoTime()
        return BlazeClock.MillisSinceEpochToNanosConverter { timeMillis: Long ->
            nowInNanos - TimeUnit.MILLISECONDS.toNanos(
                nowInMillis - timeMillis
            )
        }
    }

    /** Converts from nanos to millis since the epoch.  */
    interface NanosToMillisSinceEpochConverter {
        /** Converts from nanos to millis since the epoch.  */
        fun toEpochMillis(timeNanos: Long): Long
    }

    /** Converts from millis since the epoch to nanos.  */
    interface MillisSinceEpochToNanosConverter {
        /** Converts from millis since the epoch to nanos.  */
        fun toNanos(timeMillis: Long): Long
    }
}
