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
import com.google.devtools.build.lib.platform.SystemSuspensionEvent

/**
 * This event is fired from [ ][com.google.devtools.build.lib.platform.SystemSuspensionModule.suspendCallback] to indicate that
 * the user either suspended the build with a signal or put their computer to sleep.
 */
class SystemSuspensionEvent(reason: Int) : Postable {
    /** The possible reasons a system could be suspended.  */
    enum class Reason(logString: String) {
        SIGTSTP("Signal SIGTSTP"),
        SIGCONT("Signal SIGCONT"),
        SLEEP("Computer put to sleep"),
        WAKE("Computer woken up");

        private val logString: String

        init {
            this.logString = logString
        }

        fun logString(): String {
            return logString
        }

        companion object {
            /** These constants are mapped to enum in third_party/bazel/src/main/native/unix_jni.h.  */
            fun fromInt(number: Int): Reason {
                return when (number) {
                    0 -> com.google.devtools.build.lib.platform.SystemSuspensionEvent.Reason.SIGTSTP
                    1 -> com.google.devtools.build.lib.platform.SystemSuspensionEvent.Reason.SIGCONT
                    2 -> com.google.devtools.build.lib.platform.SystemSuspensionEvent.Reason.SLEEP
                    3 -> com.google.devtools.build.lib.platform.SystemSuspensionEvent.Reason.WAKE
                    else -> throw java.lang.IllegalStateException("Unknown suspension reason: " + number)
                }
            }
        }
    }

    private val reason: Reason

    init {
        this.reason = com.google.devtools.build.lib.platform.SystemSuspensionEvent.Reason.Companion.fromInt(reason)
    }

    fun reason(): Reason {
        return reason
    }

    fun logString(): String {
        return "SystemSuspensionEvent: " + reason.logString()
    }
}
