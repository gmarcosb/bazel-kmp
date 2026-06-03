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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.actions.SpawnMetrics


/** Includes limits to SpawnMetrics, if set.  */
// Use ints instead of Durations to improve build time (cl/505728570)
class FullSpawnMetrics internal constructor(builder: com.google.devtools.build.lib.actions.SpawnMetrics.Builder) :
    SpawnMetrics(builder) {
    private val inputBytesLimit: Long
    private val inputFilesLimit: Long
    private val outputBytesLimit: Long
    private val outputFilesLimit: Long
    private val memoryBytesLimit: Long
    private val timeLimitInMs: Int

    init {
        this.inputBytesLimit = builder.inputBytesLimit
        this.inputFilesLimit = builder.inputFilesLimit
        this.outputBytesLimit = builder.outputBytesLimit
        this.outputFilesLimit = builder.outputFilesLimit
        this.memoryBytesLimit = builder.memoryBytesLimit
        this.timeLimitInMs = builder.timeLimitInMs
    }

    /** Limit of total size in bytes of inputs or 0 if unavailable.  */
    override fun inputBytesLimit(): Long {
        return inputBytesLimit
    }

    /** Limit of total number of input files or 0 if unavailable.  */
    override fun inputFilesLimit(): Long {
        return inputFilesLimit
    }

    /** Limit of total size in bytes of outputs or 0 if unavailable.  */
    override fun outputBytesLimit(): Long {
        return outputBytesLimit
    }

    /** Limit of total number of output files or 0 if unavailable.  */
    override fun outputFilesLimit(): Long {
        return outputFilesLimit
    }

    /** Memory limit or 0 if unavailable.  */
    override fun memoryLimit(): Long {
        return memoryBytesLimit
    }

    /** Time limit in milliseconds or 0 if unavailable.  */
    override fun timeLimitInMs(): Int {
        return timeLimitInMs
    }
}
