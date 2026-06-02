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
package com.google.devtools.build.lib.profiler

import com.google.devtools.build.lib.profiler.MetricData
import com.google.devtools.build.lib.profiler.MetricData.HistogramElement
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAccumulator
import java.util.function.LongBinaryOperator

/**
 * A stat recorder that can record time histograms, count of calls, average time, Std. Deviation
 * and max time.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
class SingleStatRecorder(description: Any?, buckets: Int) : com.google.devtools.build.lib.profiler.StatRecorder {
    private val buckets: Int
    private val description: Any?
    private val histogram: AtomicIntegerArray
    private val sum: AtomicLong = AtomicLong(0)
    private val sumSquared: AtomicLong = AtomicLong(0)
    private val max: LongAccumulator =
        LongAccumulator(LongBinaryOperator { a: Long, b: Long -> java.lang.Math.max(a, b) }, -1)

    init {
        this.description = description
        com.google.common.base.Preconditions.checkArgument(
            buckets > 1, "At least two buckets (one for bellow start and one"
                    + "for above start) are required"
        )
        this.buckets = buckets
        histogram = AtomicIntegerArray(buckets)
    }

    /** Create an snapshot of the stats recorded up to now.  */
    fun snapshot(): MetricData {
        val result: com.google.common.collect.ImmutableList.Builder<HistogramElement?> =
            com.google.common.collect.ImmutableList.builder<HistogramElement?>()
        result.add(HistogramElement(com.google.common.collect.Range.closedOpen<Int?>(0, 1), histogram.get(0)))
        var from = 1
        for (i in 1..<histogram.length() - 1) {
            val to = from shl 1
            result.add(HistogramElement(com.google.common.collect.Range.closedOpen<Int?>(from, to), histogram.get(i)))
            from = to
        }
        result.add(
            HistogramElement(
                com.google.common.collect.Range.atLeast<Int?>(from),
                histogram.get(histogram.length() - 1)
            )
        )
        var n = 0
        for (i in 0..<histogram.length()) {
            n += histogram.get(i)
        }
        val stddev: Double
        if (n == 1) {
            stddev = 0.0
        } else {
            stddev = java.lang.Math.sqrt((sumSquared.longValue() - sum.get() * sum.doubleValue() / n) / n)
        }
        return MetricData(
            description, result.build(), n, sum.doubleValue() / n, stddev, max.intValue()
        )
    }

    override fun addStat(duration: Int, obj: Any?) {
        val histogramBucket: Int =
            java.lang.Math.min(32 - java.lang.Integer.numberOfLeadingZeros(duration), buckets - 1)
        sum.addAndGet(duration.toLong())
        sumSquared.addAndGet((duration * duration).toLong())
        max.accumulate(duration.toLong())
        histogram.incrementAndGet(histogramBucket)
    }

    override fun isEmpty(): Boolean {
        return snapshot().getCount() == 0
    }

    override fun toString(): String {
        return snapshot().toString()
    }
}
