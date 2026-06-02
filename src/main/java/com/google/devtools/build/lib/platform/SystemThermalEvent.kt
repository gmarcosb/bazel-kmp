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

/**
 * This event is fired from [ ][com.google.devtools.build.lib.platform.SystemThermalEventModule.thermalCallback] to indicate that
 * a thermal event has occurred.
 */
class SystemThermalEvent(value: Int, osDescription: String?) : Postable {
    private val value: Int
    private val osDescription: String?

    /**
     * SystemThermalEvent Constructor.
     * 
     * @param value The actual thermal value (0-100).
     * @param osDescription A description string that may be OS specific.
     */
    init {
        this.value = value
        this.osDescription = osDescription
    }

    fun value(): Int {
        return value
    }

    fun osDescription(): String? {
        return osDescription
    }

    fun logString(): String? {
        return java.lang.String.format("SystemThermalEvent: %d (%s)", value, osDescription)
    }
}
