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

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.stream.Collectors

/**
 * Metric data for `description` object. Contains count, average, standard deviation, max and
 * histogram.
 */
class MetricData(
    description: Any?, histogram: com.google.common.collect.ImmutableList<HistogramElement?>, count: Int,
    avg: Double, stdDev: Double, max: Int
) {
    private val description: Any?
    private val histogram: com.google.common.collect.ImmutableList<HistogramElement?>
    private val count: Int
    @kotlin.jvm.JvmField
    private val avg: Double
    @kotlin.jvm.JvmField
    private val stdDev: Double
    private val max: Int

    init {
        this.description = description
        this.histogram = histogram
        this.count = count
        this.avg = avg
        this.stdDev = stdDev
        this.max = max
    }

    fun getDescription(): Any? {
        return description
    }

    fun getHistogram(): com.google.common.collect.ImmutableList<HistogramElement?> {
        return histogram
    }

    fun getCount(): Int {
        return count
    }

    fun getAvg(): Double {
        return avg
    }

    fun getStdDev(): Double {
        return stdDev
    }

    fun getMax(): Int {
        return max
    }

    override fun toString(): String {
        if (count == 0) {
            return "'" + description + "'. Zero data recorded"
        }
        val fmt: DecimalFormat = DecimalFormat("0.###", DecimalFormatSymbols(Locale.US))
        return ("'"
                + description
                + "'. "
                + " Count: "
                + count
                + " Avg: "
                + fmt.format(avg)
                + " StdDev: "
                + fmt.format(stdDev)
                + " Max: "
                + max
                + " Histogram:\n  "
                + histogram
            .stream()
            .filter(java.util.function.Predicate { element: HistogramElement? -> element.count > 0 })
            .map<String?>(java.util.function.Function { obj: HistogramElement? -> obj.toString() })
            .collect(Collectors.joining("\n  ")))
    }

    /** An histogram element that contains the range that applies to and the number of elements.  */
    class HistogramElement internal constructor(range: com.google.common.collect.Range<Int?>, count: Int) {
        private val range: com.google.common.collect.Range<Int?>
        private val count: Int

        init {
            this.range = range
            this.count = count
        }

        fun getRange(): com.google.common.collect.Range<Int?> {
            return range
        }

        fun getCount(): Int {
            return count
        }

        override fun toString(): String {
            return java.lang.String.format(
                "%-15s:%10s",
                ("[" + range.lowerEndpoint() + ".." + (if (range.hasUpperBound())
                    range.upperEndpoint()
                else
                    "\u221e") // infinite symbol
                        + " ms]"), count
            )
        }
    }
}
