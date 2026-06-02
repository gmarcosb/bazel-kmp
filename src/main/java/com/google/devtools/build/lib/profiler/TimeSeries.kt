// Copyright 2018 The Bazel Authors. All rights reserved.
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

/**
 * Converts a set of ranges into a graph by counting the number of ranges that are active at any
 * point in time. Time is split into equal-sized buckets, and we compute one value per bucket. If a
 * range partially overlaps a bucket, then the bucket is incremented by the fraction of overlap.
 */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
interface TimeSeries {
    /** Adds a new range to the time series, by increasing every affected bucket by 1.  */
    fun addRange(startTime: java.time.Duration?, endTime: java.time.Duration?)

    /** Adds a new range to the time series, by increasing every affected bucket by value.  */
    fun addRange(rangeStart: java.time.Duration?, rangeEnd: java.time.Duration?, value: Double)

    fun toDoubleArray(len: Int): DoubleArray?
}
