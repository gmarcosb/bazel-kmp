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

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.platform.PlatformNativeDepsService
import com.google.devtools.build.lib.platform.SystemMemoryPressureEvent
import com.google.devtools.build.lib.platform.SystemMemoryPressureMonitor
import java.util.function.IntConsumer

/** A singleton that is the java side interface for dealing with memory pressure events.  */
class SystemMemoryPressureMonitor private constructor() {
    @javax.annotation.concurrent.GuardedBy("this")
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null

    private var service: PlatformNativeDepsService? = null

    fun registerJniService(service: PlatformNativeDepsService) {
        this.service = service
        service.registerMemoryPressureJni(IntConsumer { value: Int -> this.memoryPressureCallback(value) })
    }

    /** The possible memory pressure levels.  */
    enum class Level(logString: String) {
        NORMAL("Normal"),
        WARNING("Warning"),
        CRITICAL("Critical");

        private val logString: String?

        init {
            this.logString = logString
        }

        fun logString(): String? {
            return logString
        }

        companion object {
            /** These constants are mapped to enum in third_party/bazel/src/main/native/unix_jni.h.  */
            fun fromInt(number: Int): Level {
                return when (number) {
                    0 -> com.google.devtools.build.lib.platform.SystemMemoryPressureMonitor.Level.NORMAL
                    1 -> com.google.devtools.build.lib.platform.SystemMemoryPressureMonitor.Level.WARNING
                    2 -> com.google.devtools.build.lib.platform.SystemMemoryPressureMonitor.Level.CRITICAL
                    else -> throw java.lang.IllegalStateException("Unknown memory pressure level: " + number)
                }
            }
        }
    }

    /** Return current memory pressure  */
    fun level(): Level {
        return com.google.devtools.build.lib.platform.SystemMemoryPressureMonitor.Level.Companion.fromInt(service.systemMemoryPressure())
    }

    @kotlin.jvm.Synchronized
    fun setReporter(reporter: com.google.devtools.build.lib.events.Reporter?) {
        this.reporter = reporter
        val pressure: Int = service.systemMemoryPressure()
        if (com.google.devtools.build.lib.platform.SystemMemoryPressureMonitor.Level.Companion.fromInt(pressure) != com.google.devtools.build.lib.platform.SystemMemoryPressureMonitor.Level.NORMAL) {
            memoryPressureCallback(pressure)
        }
    }

    @kotlin.jvm.Synchronized
    fun memoryPressureCallback(value: Int) {
        val event: SystemMemoryPressureEvent = SystemMemoryPressureEvent(
            com.google.devtools.build.lib.platform.SystemMemoryPressureMonitor.Level.Companion.fromInt(value)
        )
        if (reporter != null) {
            reporter.post(event)
        }
        logger.atInfo().log("%s", event.logString())
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        @kotlin.jvm.JvmField
        private val singleton = SystemMemoryPressureMonitor()

        fun getInstance(): SystemMemoryPressureMonitor {
            return singleton
        }
    }
}
