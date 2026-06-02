// Copyright 2026 The Bazel Authors. All rights reserved.
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

/**
 * A class that buckets values into buckets based on the length of the decimal representation of the
 * value and its leading digit.
 * 
 * 
 * To use this class, call [.add] to add values to the buckets. Then call [ ][.getBuckets] to get the buckets.
 */
class DecimalBucketer {
    private val counts: java.util.ArrayList<Long?> = java.util.ArrayList<Long?>()

    /** Adds a value to the bucketer. It must be non-negative.  */
    @kotlin.jvm.Synchronized
    fun add(value: Long) {
        var value = value
        require(value >= 0) { "value must be non-negative" }

        // Each length has 9 buckets, one for each leading digit, except for 0-9, which has 10.
        var bucketIdx = 0
        while (value >= 10) {
            value /= 10
            bucketIdx += 9
        }
        bucketIdx += value.toInt() // value here is always >0 except if the input is 0 so this works out

        while (counts.size() <= bucketIdx) {
            counts.add(0L)
        }
        counts.set(bucketIdx, counts.get(bucketIdx) + 1L)
    }

    @get:kotlin.jvm.Synchronized
    val buckets: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Bucket?>
        /** Returns the buckets in which there are values in increasing order of the bucket minimum.  */
        get() {
            val builder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.util.Bucket?> =
                com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.util.Bucket?>()

            var base: Long = 1
            var leadingDigit: Long = 0

            for (count in counts) {
                if (count > 0) {
                    val min = base * leadingDigit
                    val max = if (java.lang.Long.MAX_VALUE - base < min) java.lang.Long.MAX_VALUE else min + base
                    builder.add(com.google.devtools.build.lib.util.Bucket(min, max, count))
                }

                leadingDigit += 1
                if (leadingDigit > 9) {
                    leadingDigit = 1
                    base *= 10
                }
            }
            return builder.build()
        }
}
