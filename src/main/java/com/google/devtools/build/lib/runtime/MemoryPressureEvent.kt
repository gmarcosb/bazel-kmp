// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.auto.value.AutoBuilder

/** A memory pressure event.  */
class MemoryPressureEvent(
    val wasManualGc: Boolean,
    val wasGcLockerInitiatedGc: Boolean,
    @kotlin.jvm.JvmField val wasFullGc: Boolean,
    @kotlin.jvm.JvmField val tenuredSpaceUsedBytes: Long,
    @kotlin.jvm.JvmField val tenuredSpaceMaxBytes: Long,
    duration: java.time.Duration?
) {
    fun percentTenuredSpaceUsed(): Int {
        return ((this.tenuredSpaceUsedBytes * 100L) / this.tenuredSpaceMaxBytes).toInt()
    }

    /** A memory pressure event builder.  */
    @com.google.common.annotations.VisibleForTesting
    @AutoBuilder
    abstract class Builder {
        abstract fun setWasManualGc(value: Boolean): Builder?

        abstract fun setWasGcLockerInitiatedGc(value: Boolean): Builder?

        abstract fun setWasFullGc(value: Boolean): Builder?

        abstract fun setTenuredSpaceUsedBytes(value: Long): Builder?

        abstract fun setTenuredSpaceMaxBytes(value: Long): Builder?

        abstract fun setDuration(duration: java.time.Duration?): Builder?

        abstract fun build(): MemoryPressureEvent?
    }

    val duration: java.time.Duration?

    init {
        this.duration = duration
    }

    companion object {
        @com.google.common.annotations.VisibleForTesting
        fun newBuilder(): Builder {
            return AutoBuilder_MemoryPressureEvent_Builder()
                .setWasManualGc(false)
                .setWasGcLockerInitiatedGc(false)
                .setWasFullGc(false)
        }
    }
}
