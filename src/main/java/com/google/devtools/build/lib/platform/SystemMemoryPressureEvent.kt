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
import com.google.devtools.build.lib.platform.SystemMemoryPressureMonitor

/**
 * This event is fired from [ ][com.google.devtools.build.lib.platform.SystemMemoryPressureModule.memoryPressureCallback] to
 * indicate that a memory pressure event has occurred.
 */
class SystemMemoryPressureEvent(level: com.google.devtools.build.lib.platform.SystemMemoryPressureMonitor.Level) :
    Postable {
    private val level: com.google.devtools.build.lib.platform.SystemMemoryPressureMonitor.Level

    init {
        this.level = level
    }

    fun level(): com.google.devtools.build.lib.platform.SystemMemoryPressureMonitor.Level {
        return level
    }

    fun logString(): String {
        return "SystemMemoryPressureEvent: " + level.logString()
    }
}
