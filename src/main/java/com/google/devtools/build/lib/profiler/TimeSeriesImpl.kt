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
package com.google.devtools.build.lib.profiler

/** Implementation of [TimeSeries].  */
class TimeSeriesImpl internal constructor(startTime: java.time.Duration, bucketDuration: java.time.Duration) :
    com.google.devtools.build.lib.profiler.TimeSeries {
    private val startTime: java.time.Duration
    private val bucketSizeMillis: Long

    @javax.annotation.concurrent.GuardedBy("this")
    private var data = DoubleArray(INITIAL_SIZE)

    init {
        this.startTime = startTime
        this.bucketSizeMillis = bucketDuration.toMillis()
    }

    override fun addRange(startTime: java.time.Duration, endTime: java.time.Duration) {
        addRange(startTime, endTime,  /* value= */1.0)
    }

    override fun addRange(rangeStart: java.time.Duration, rangeEnd: java.time.Duration, value: Double) {
        // Compute times relative to start and their positions in the data array.
        var rangeStart: java.time.Duration = rangeStart
        var rangeEnd: java.time.Duration = rangeEnd
        rangeStart = rangeStart.minus(startTime)
        rangeEnd = rangeEnd.minus(startTime)
        var startPosition: Int = (rangeStart.toMillis() / bucketSizeMillis).toInt()
        var endPosition: Int = (rangeEnd.toMillis() / bucketSizeMillis).toInt()

        // Assume we add the following range R:
        // ----------------------------------
        // |     |ssRRR|RRRRR|Reeee|      |
        // ----------------------------------
        // we cannot just add value to each affected bucket but have to correct the values for the first
        // and last bucket by calculating the size of 's' and 'e'.
        var missingStartFraction: Double =
            ((rangeStart.minusMillis(bucketSizeMillis * startPosition).toMillis().toDouble())
                    / bucketSizeMillis)
        var missingEndFraction: Double =
            ((bucketSizeMillis * (endPosition + 1) - rangeEnd.toMillis()).toDouble()) / bucketSizeMillis

        if (startPosition < 0) {
            startPosition = 0
            missingStartFraction = 0.0
        }
        if (endPosition < startPosition) {
            endPosition = startPosition
            missingEndFraction = 0.0
        }

        synchronized(this) {
            // Resize data array if necessary so it can at least fit endPosition.
            if (endPosition >= data.length) {
                data = java.util.Arrays.copyOf(data, java.lang.Math.max(endPosition + 1, 2 * data.length))
            }

            // Do the actual update.
            for (i in startPosition..endPosition) {
                var fraction = 1.0
                if (i == startPosition) {
                    fraction -= missingStartFraction
                }
                if (i == endPosition) {
                    fraction -= missingEndFraction
                }
                data[i] += fraction * value
            }
        }
    }

    @kotlin.jvm.Synchronized
    override fun toDoubleArray(len: Int): DoubleArray {
        return java.util.Arrays.copyOf(data, len)
    }

    companion object {
        private const val INITIAL_SIZE = 100
    }
}
