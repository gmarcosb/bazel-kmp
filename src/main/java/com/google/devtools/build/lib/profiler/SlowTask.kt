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

/** A task that was very slow.  */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
@kotlin.jvm.JvmRecord
data class SlowTask(
    durationNanos: Long,
    description: String?,
    type: com.google.devtools.build.lib.profiler.ProfilerTask?
) : Comparable<SlowTask?> {
    override fun compareTo(other: SlowTask): Int {
        return java.lang.Long.compare(this.durationNanos, other.durationNanos)
    }

    val durationNanos: Long
    val description: String?
    val type: com.google.devtools.build.lib.profiler.ProfilerTask?

    init {
        this.durationNanos = durationNanos
        this.description = description
        this.type = type
    }
}
