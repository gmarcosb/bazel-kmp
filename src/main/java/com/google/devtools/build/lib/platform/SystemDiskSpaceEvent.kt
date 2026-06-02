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
package com.google.devtools.build.lib.platform

import ExtendedEventHandler.Postable
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable
import com.google.devtools.build.lib.platform.SystemDiskSpaceEvent

/**
 * This event is fired from [ ][com.google.devtools.build.lib.platform.SystemDiskSpaceModule.diskSpaceCallback] to indicate that
 * a disk space event has occurred.
 */
class SystemDiskSpaceEvent(level: Int) : Postable {
    /** Rough description of why the disk space event fired.  */
    enum class Level(logString: String) {
        LOW("Low"),
        VERY_LOW("Very Low");

        private val logString: String

        init {
            this.logString = logString
        }

        fun logString(): String {
            return logString
        }

        companion object {
            /** These constants are mapped to enum in third_party/bazel/src/main/native/unix_jni.h.  */
            fun fromInt(number: Int): Level {
                return when (number) {
                    0 -> com.google.devtools.build.lib.platform.SystemDiskSpaceEvent.Level.LOW
                    1 -> com.google.devtools.build.lib.platform.SystemDiskSpaceEvent.Level.VERY_LOW
                    else -> throw java.lang.IllegalStateException("Unknown disk space level: " + number)
                }
            }
        }
    }

    private val level: Level

    init {
        this.level = com.google.devtools.build.lib.platform.SystemDiskSpaceEvent.Level.Companion.fromInt(level)
    }

    fun level(): Level {
        return level
    }

    fun logString(): String {
        return "SystemDiskSpaceEvent: " + level.logString()
    }
}
